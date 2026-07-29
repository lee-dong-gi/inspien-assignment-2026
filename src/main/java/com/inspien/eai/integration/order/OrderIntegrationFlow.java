package com.inspien.eai.integration.order;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.EaiException;
import com.inspien.eai.engine.exception.EaiExceptions;
import com.inspien.eai.engine.flow.IntegrationFlow;
import com.inspien.eai.engine.log.InterfaceLogger;
import com.inspien.eai.engine.log.InterfaceLogger.StepScope;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.mapper.Mapper;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.engine.receiver.DeliveryCoordinator;
import com.inspien.eai.engine.receiver.DeliveryOutcome;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.engine.sender.Sender;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.Validator;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.target.OrderRecord;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * IF-ORD-001 — 주문 생성 연계 파이프라인.
 *
 * <pre>
 *   Sender(REST/XML) → Validator → Mapper → Coordinator[ JDBC → FTP ]
 * </pre>
 *
 * <h2>이 클래스에 도메인 지식이 없다</h2>
 * XML 을 읽는 법도, ORDER_TB 컬럼도, 영수증 라인 포맷도 여기에는 없다.
 * 하는 일은 <b>순서를 잇고, 구간마다 기록하고, 결과를 한 형태로 모으는 것</b>뿐이다.
 * 그래서 시나리오 2 는 이 골격을 그대로 쓰고 부품만 갈아 끼운다 — 그것이 과제가 요구한
 * "기능 두 벌" 이 아니라 "연계 구조 하나" 다.
 *
 * <h2>txId 를 여기서 발급한다 (D-16)</h2>
 * {@code txId} 는 <b>이 실행</b>의 식별자다. Sender 가 만들면 파싱 실패 시 추적 ID 가 없어
 * 실행 이력도 응답도 남길 수 없다. 발급을 맨 앞으로 당기면 <b>어떤 실패든 txId 를 갖는다.</b>
 *
 * <h2>예외를 밖으로 흘리지 않는다</h2>
 * 연계에서 실패는 예외 상황이 아니라 <b>정상적으로 보고해야 하는 결과</b>다.
 * 호출자(REST 컨트롤러 · 스케줄러)가 성공·부분성공·실패를 같은 방식으로 다룰 수 있어야 하므로
 * 전부 {@link ProcessResult} 로 변환한다.
 *
 * <h2>가장 중요한 결과 — PARTIAL</h2>
 * 샘플 74건 중 11건은 짝이 없어 제외된다. 63건을 넣고 {@code SUCCESS} 라 답하면
 * 호출자는 74건이 다 들어간 줄 안다. <b>버린 건수와 사유를 반드시 동반</b>하며,
 * 전달 조율이 되돌릴 수 없는 자리에서 실패한 경우에도 {@code PARTIAL} 로 보고한다
 * (에러 코드 동반, D-14).
 */
@Slf4j
public class OrderIntegrationFlow implements IntegrationFlow<byte[]> {

    private final Sender<byte[], OrderSourceMessage> sender;
    private final Validator<OrderSourceMessage> validator;
    private final Mapper<OrderSourceMessage, OrderRecord> mapper;
    private final DeliveryCoordinator<OrderRecord> coordinator;

    /**
     * 수신처 목록. <b>순서가 곧 정책이다</b> — JDBC 먼저, FTP 나중 (정의서 3.9).
     * 되돌리기가 불확실한 쪽을 마지막에 확정해 보상이 필요한 상황을 줄인다.
     */
    private final List<Receiver<OrderRecord>> receivers;

    private final InterfaceLogger interfaceLogger;

    public OrderIntegrationFlow(Sender<byte[], OrderSourceMessage> sender,
                                Validator<OrderSourceMessage> validator,
                                Mapper<OrderSourceMessage, OrderRecord> mapper,
                                DeliveryCoordinator<OrderRecord> coordinator,
                                List<Receiver<OrderRecord>> receivers,
                                InterfaceLogger interfaceLogger) {
        this.sender = sender;
        this.validator = validator;
        this.mapper = mapper;
        this.coordinator = coordinator;
        this.receivers = List.copyOf(receivers);
        this.interfaceLogger = interfaceLogger;
    }

    @Override
    public InterfaceId ifId() {
        return InterfaceId.IF_ORD_001;
    }

    @Override
    public ProcessResult execute(byte[] trigger) {
        MessageHeader header = MessageHeader.issue(ifId());
        interfaceLogger.begin(header, "TRIGGER=REST BYTES=" + (trigger == null ? 0 : trigger.length));

        // 초기값을 둔 이유: run() 이 Error 로 빠져나가도 finally 가 읽을 값이 있어야 한다.
        // 그때는 '끝나지 않았다' 가 사실이며, END 줄이 통째로 빠지는 것보다 낫다.
        ProcessResult result = ProcessResult.fail(header.txId(), EaiErrorCode.FLOW_ERROR, "실행이 종료되지 않았다");
        try {
            result = run(header, trigger);

        } catch (EaiException e) {
            result = ProcessResult.fail(header.txId(), e.errorCode(), EaiExceptions.reason(e, e.errorCode()));

        } catch (RuntimeException e) {
            // 구간별 catch 를 빠져나온 예외 = 사실상 우리 코드의 버그.
            // 여기서 스택트레이스를 남기지 않으면 원인을 찾을 단서가 사라진다.
            EaiException wrapped = EaiExceptions.wrap(e, EaiErrorCode.FLOW_ERROR, "파이프라인 실행 중 예기치 못한 오류");
            log.error("[{}] 구간에 귀속되지 않는 실패", wrapped.errorCode().code(), e);
            result = ProcessResult.fail(header.txId(), wrapped.errorCode(),
                    EaiExceptions.reason(wrapped, wrapped.errorCode()));

        } finally {
            // MDC 해제도 여기서 일어난다. 빠뜨리면 다음 요청 로그에 이전 txId 가 붙는다.
            interfaceLogger.complete(header, result);
        }
        return result;
    }

    /**
     * 파이프라인 본체.
     *
     * <p>구간별 실패는 각 메서드가 <b>기록하고 코드를 달아</b> 올려보내므로,
     * 여기에는 흐름만 남는다. 흐름을 읽는 데 예외 처리가 끼어들지 않는 것이 목적이다.
     */
    private ProcessResult run(MessageHeader header, byte[] trigger) {
        CanonicalMessage<OrderSourceMessage> received = receive(header, trigger);

        ValidationResult<OrderSourceMessage> validation = validate(received);
        if (validation.rejected()) {
            // Receiver 를 호출하기 전에 끊는다. 되돌릴 것이 없는 상태에서 끝내는 것이 가장 싸다.
            return rejected(header.txId(), validation);
        }

        CanonicalMessage<List<OrderRecord>> mapped = map(received.withPayload(validation.accepted()));

        // 수신처별 PREPARE/COMMIT 기록은 조율자가 남긴다 — 구간 경계를 아는 쪽이 그쪽이다.
        DeliveryOutcome outcome = coordinator.deliver(mapped, receivers);

        return toResult(header.txId(), outcome, validation);
    }

    private CanonicalMessage<OrderSourceMessage> receive(MessageHeader header, byte[] trigger) {
        try (StepScope scope = interfaceLogger.step(header, Step.SENDER)) {
            try {
                CanonicalMessage<OrderSourceMessage> message = sender.receive(header, trigger);
                OrderSourceMessage source = message.payload();

                scope.detail("HEADER=%d ITEM=%d", source.headerCount(), source.itemCount())
                        .success(source.itemCount());
                return message;

            } catch (RuntimeException e) {
                throw record(scope, e, EaiErrorCode.SOURCE_PARSE_ERROR, "소스 수신 실패");
            }
        }
    }

    private ValidationResult<OrderSourceMessage> validate(CanonicalMessage<OrderSourceMessage> message) {
        try (StepScope scope = interfaceLogger.step(message.header(), Step.VALIDATOR)) {
            try {
                ValidationResult<OrderSourceMessage> result = validator.validate(message);

                if (result.rejected()) {
                    scope.detail("FATAL=%d", result.fatal().size())
                            .fail(EaiErrorCode.VALIDATION_ERROR, violationSummary(result));
                    return result;
                }

                int accepted = result.accepted().itemCount();
                int skipped = result.skipped().size();
                scope.detail(skipSummary(result));

                // 스킵이 하나라도 있으면 SUCCESS 가 아니다. 구간 로그에서부터 그렇게 남긴다.
                if (skipped > 0) {
                    scope.partial(accepted, skipped);
                } else {
                    scope.success(accepted);
                }
                return result;

            } catch (RuntimeException e) {
                throw record(scope, e, EaiErrorCode.VALIDATION_ERROR, "검증 실패");
            }
        }
    }

    private CanonicalMessage<List<OrderRecord>> map(CanonicalMessage<OrderSourceMessage> message) {
        try (StepScope scope = interfaceLogger.step(message.header(), Step.MAPPER)) {
            try {
                List<OrderRecord> records = mapper.map(message.payload());
                scope.detail("ROWS=%d", records.size()).success(records.size());
                return message.withPayload(records);

            } catch (RuntimeException e) {
                throw record(scope, e, EaiErrorCode.MAPPING_ERROR, "매핑 실패");
            }
        }
    }

    /**
     * 전달 결과를 실행 결과로 옮긴다.
     *
     * <p>스킵 건수는 <b>검증에서</b> 오고 적재 건수는 <b>전달에서</b> 온다.
     * 한쪽만 보면 "63건 적재 성공" 이라는 참이지만 불완전한 보고가 된다.
     */
    private ProcessResult toResult(String txId, DeliveryOutcome outcome,
                                   ValidationResult<OrderSourceMessage> validation) {
        int skipped = validation.skipped().size();
        Map<String, Integer> skipDetail = validation.skipDetail();

        if (outcome.needsManualAction()) {
            // 적재는 됐지만 일부 수신처가 확정되지 못했다. FAIL 로 보고하면 재요청 → 중복 적재다.
            return new ProcessResult(txId, ProcessResult.Outcome.PARTIAL,
                    outcome.count(), skipped, 0, skipDetail,
                    outcome.manualActionCode(), outcome.manualActionDetail());
        }
        return ProcessResult.of(txId, outcome.count(), skipped, skipDetail);
    }

    private ProcessResult rejected(String txId, ValidationResult<OrderSourceMessage> validation) {
        return ProcessResult.fail(txId, EaiErrorCode.VALIDATION_ERROR, violationSummary(validation));
    }

    /**
     * 구간 실패를 <b>기록하고</b> 코드를 달아 돌려준다.
     *
     * <p>기록과 예외 변환을 한 자리에 묶은 이유는 둘 중 하나만 하는 실수를 막기 위해서다.
     * 기록만 하면 호출자가 실패를 모르고, 변환만 하면 <b>그 구간이 실행조차 안 된 것처럼</b> 보인다.
     */
    private EaiException record(StepScope scope, RuntimeException e, EaiErrorCode fallback, String context) {
        EaiErrorCode code = EaiExceptions.codeOf(e, fallback);
        scope.fail(code, EaiExceptions.reason(e, code));
        return EaiExceptions.wrap(e, fallback, context);
    }

    /** 예: {@code ORPHAN_ITEM=7 HEADER_WITHOUT_ITEM=4} */
    private String skipSummary(ValidationResult<OrderSourceMessage> result) {
        return result.skipDetail().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
    }

    /**
     * 위반 요약.
     *
     * <p>앞 3건만 싣는다. 전건을 실으면 한 줄이 수천 자가 되어 이력 파일을 못 읽게 되고,
     * 원인 파악에는 앞 몇 건이면 충분하다 — 같은 규칙 위반이 줄줄이 이어지는 것이 보통이다.
     *
     * <p><b>값은 담기지 않는다.</b> {@code Violation} 이 애초에 값을 갖지 않으므로
     * (규칙·필드·설명만) 개인정보가 이 경로로 샐 자리가 없다.
     */
    private String violationSummary(ValidationResult<OrderSourceMessage> result) {
        String head = result.fatal().stream()
                .limit(3)
                .map(v -> v.rule() + " " + v.field() + " " + v.detail())
                .collect(Collectors.joining("; "));

        int total = result.fatal().size();
        return total <= 3 ? total + "건 위반: " + head
                : total + "건 위반: " + head + " … 외 " + (total - 3) + "건";
    }
}
