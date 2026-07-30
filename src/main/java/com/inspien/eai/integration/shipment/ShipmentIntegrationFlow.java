package com.inspien.eai.integration.shipment;

import com.inspien.eai.common.lock.DistributedLock;
import com.inspien.eai.common.lock.LockHandle;
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
import com.inspien.eai.integration.shipment.source.PollCursor;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * IF-SHP-001 — 운송사 전송 배치 파이프라인.
 *
 * <pre>
 *   락 획득 → [ Sender(JDBC Polling) → Validator → Mapper → Coordinator[ JDBC ] ] × 청크 → 락 반납
 * </pre>
 *
 * <h2>{@code OrderIntegrationFlow} 와 나란히 읽어야 한다</h2>
 * 두 클래스의 구간 순서는 <b>같다.</b> 다른 것은 셋뿐이다.
 *
 * <table border="1">
 *   <caption>실시간과 배치의 차이</caption>
 *   <tr><th></th><th>IF-ORD-001 (SYNC)</th><th>IF-SHP-001 (ASYNC)</th></tr>
 *   <tr><td>실행 횟수</td><td>파이프라인 1회</td><td><b>청크마다 반복</b></td></tr>
 *   <tr><td>동시 실행</td><td>제한 없음 (요청마다 독립)</td><td><b>분산 락으로 1개</b></td></tr>
 *   <tr><td>실패 처리</td><td>호출자에게 응답 → 고쳐 다시 보낸다</td>
 *       <td>호출자가 없다 → <b>다음 주기가 자연 재처리</b></td></tr>
 * </table>
 *
 * 나머지(구간 로깅, txId 발급, 예외를 결과로 변환, PARTIAL 판정)는 그대로다.
 * 이것이 과제가 요구한 "기능 두 벌이 아니라 연계 구조 하나" 의 모습이다.
 *
 * <h2>청크를 반복하는 이유와 반복이 만드는 함정</h2>
 * 전체를 한 번에 메모리에 올리지 않기 위해 청크로 자른다(정의서 4.5). 그런데
 * 조회 조건이 {@code STATUS='N'} 이고 <b>스킵된 행은 상태가 바뀌지 않으므로</b>,
 * 같은 조건으로 다시 조회하면 그 행이 또 나온다. 순진하게 "0건 나올 때까지" 돌면
 * <b>무한 루프</b>가 되고, 스킵 집계도 청크마다 중복 계산된다.
 *
 * <p>해법은 {@link PollCursor} 다 — 읽은 마지막 {@code ORDER_ID} 를 기억해 그 뒤부터 읽는다.
 * 덕분에 종료 조건이 <b>"청크가 덜 찼다"</b> 하나로 단순해지고, 한 실행 안에서 같은 행을
 * 두 번 읽지 않는다.
 *
 * <p>{@code maxChunksPerRun} 상한을 함께 두는 것은 다른 이유다 — <b>수행 시간의 상한</b>이
 * 없으면 락 TTL 을 넘길 수 있고, 그때 배치가 겹쳐 돈다. 상한에 걸려 남은 일은
 * 다음 주기가 이어받는다. 배치에서는 "이번에 다 못 했다" 가 정상적인 결과다.
 *
 * <h2>중간 실패는 앞의 청크를 무효로 만들지 않는다</h2>
 * 청크마다 <b>독립된 트랜잭션</b>이다. 3번째 청크가 실패해도 1·2번 청크의 100건은
 * 이미 확정되어 있고 {@code STATUS='Y'} 다. 그 상태에서 {@code FAIL / processed=0} 으로
 * 보고하면 <b>거짓</b>이다 — 운영자는 아무것도 처리되지 않은 줄 알고 원인을 엉뚱한 데서 찾는다.
 *
 * <p>그래서 실패해도 <b>그때까지의 건수를 보존</b>해 {@code PARTIAL} + 에러 코드로 보고한다.
 * D-14 가 세운 경계("예외 = 아무 데도 남지 않았다 / 반환 = 어딘가엔 남았다")를
 * 청크 단위로 확장한 것이다.
 *
 * <h2>락을 못 잡으면 실패로 보고한다 — 성공 0건이 아니다</h2>
 * 겹침 방지는 정상 동작이지만, <b>처리하지 못한 일이 남아 있다</b>는 사실은 감추지 않는다.
 * {@code SUCCESS / processed=0} 으로 보고하면 이력만 보고는 "할 일이 없었다" 와
 * "이전 주기가 아직 돌고 있다" 를 구분할 수 없고, 후자가 계속되면 배치는 사실상 멈춘 상태다.
 * HTTP 로는 500 이 아니라 {@code 409 Conflict} 다 (→ {@code InterfaceHttpStatus}).
 */
@Slf4j
public class ShipmentIntegrationFlow implements IntegrationFlow<BatchTrigger> {

    private final Sender<PollCursor, ShipmentSourceMessage> sender;
    private final Validator<ShipmentSourceMessage> validator;
    private final Mapper<ShipmentSourceMessage, ShipmentRecord> mapper;
    private final DeliveryCoordinator<ShipmentRecord> coordinator;

    /**
     * 수신처 목록. 지금은 하나뿐이다 ({@code SHIPMENT_TB} + 상태 갱신).
     *
     * <p>하나여도 조율자를 거치는 이유는 {@code ShipmentTbReceiver} javadoc 참조 —
     * 골격을 공유해야 "구조는 하나" 라는 말이 성립하고, 수신처가 늘어나는 날
     * (예: 운송사 API 통보) 이 클래스를 고칠 필요가 없다.
     */
    private final List<Receiver<ShipmentRecord>> receivers;

    private final DistributedLock distributedLock;
    private final ShipmentBatchProperties properties;
    private final InterfaceLogger interfaceLogger;

    public ShipmentIntegrationFlow(Sender<PollCursor, ShipmentSourceMessage> sender,
                                   Validator<ShipmentSourceMessage> validator,
                                   Mapper<ShipmentSourceMessage, ShipmentRecord> mapper,
                                   DeliveryCoordinator<ShipmentRecord> coordinator,
                                   List<Receiver<ShipmentRecord>> receivers,
                                   DistributedLock distributedLock,
                                   ShipmentBatchProperties properties,
                                   InterfaceLogger interfaceLogger) {
        this.sender = sender;
        this.validator = validator;
        this.mapper = mapper;
        this.coordinator = coordinator;
        this.receivers = List.copyOf(receivers);
        this.distributedLock = distributedLock;
        this.properties = properties;
        this.interfaceLogger = interfaceLogger;
    }

    @Override
    public InterfaceId ifId() {
        return InterfaceId.IF_SHP_001;
    }

    @Override
    public ProcessResult execute(BatchTrigger trigger) {
        BatchTrigger source = (trigger == null) ? BatchTrigger.SCHEDULED : trigger;

        MessageHeader header = MessageHeader.issue(ifId());
        interfaceLogger.begin(header, "TRIGGER=" + source);

        // 초기값을 둔 이유: run() 이 Error 로 빠져나가도 finally 가 읽을 값이 있어야 한다.
        // 그때는 '끝나지 않았다' 가 사실이며, END 줄이 통째로 빠지는 것보다 낫다.
        ProcessResult result = ProcessResult.fail(header.txId(), EaiErrorCode.FLOW_ERROR, "실행이 종료되지 않았다");
        try {
            result = run(header, source);

        } catch (EaiException e) {
            // 락 저장소 장애(EAI-4006) 가 여기로 온다. 청크 루프 안의 실패는 drain() 이 이미 결과로 바꿨다.
            result = ProcessResult.fail(header.txId(), e.errorCode(), EaiExceptions.reason(e, e.errorCode()));

        } catch (RuntimeException e) {
            EaiException wrapped = EaiExceptions.wrap(e, EaiErrorCode.FLOW_ERROR, "배치 실행 중 예기치 못한 오류");
            log.error("[{}] 구간에 귀속되지 않는 실패", wrapped.errorCode().code(), e);
            result = ProcessResult.fail(header.txId(), wrapped.errorCode(),
                    EaiExceptions.reason(wrapped, wrapped.errorCode()));

        } finally {
            // MDC 해제도 여기서 일어난다. 빠뜨리면 다음 실행 로그에 이전 txId 가 붙는다.
            interfaceLogger.complete(header, result);
        }
        return result;
    }

    /**
     * 락을 잡고 본체를 실행한다.
     *
     * <p>{@code try-with-resources} 로 반납을 강제한다. {@code finally} 에 손으로 쓰면
     * 실패 경로 하나에서 빠지고, 그 빠짐은 <b>TTL 이 만료될 때까지 배치가 멈추는</b>
     * 증상으로 나타난다.
     */
    private ProcessResult run(MessageHeader header, BatchTrigger trigger) {
        Optional<LockHandle> acquired =
                distributedLock.tryAcquire(properties.lockKey(), properties.lockTtl());

        if (acquired.isEmpty()) {
            // 장애가 아니다. 안전장치가 동작한 것이다 — 다만 남은 일이 있다는 사실은 남긴다.
            return ProcessResult.fail(header.txId(), EaiErrorCode.BATCH_LOCK_ACQUIRE_FAILED,
                    "이전 주기가 아직 수행 중이다 — 중복 전송을 막기 위해 이번 주기를 건너뛴다");
        }

        try (LockHandle held = acquired.get()) {
            log.debug("[IF-SHP-001] 배치 시작 (trigger={}, lock={})", trigger, held.key());
            return drain(header);
        }
    }

    /**
     * 청크를 반복해 미전송 주문을 비운다.
     *
     * <p>세 가지 이유로 멈춘다.
     * <ol>
     *   <li><b>0건 조회</b> — 커서 뒤로 남은 것이 없다</li>
     *   <li><b>청크가 덜 찼다</b> — 이번이 마지막 덩어리였다</li>
     *   <li><b>청크 상한 도달</b> — 수행 시간을 락 TTL 안에 묶기 위한 상한.
     *       남은 일은 다음 주기가 이어받는다</li>
     * </ol>
     */
    private ProcessResult drain(MessageHeader header) {
        Tally tally = new Tally();
        PollCursor cursor = PollCursor.first();
        int maxChunks = properties.maxChunksPerRun();

        try {
            for (int chunk = 1; chunk <= maxChunks; chunk++) {
                CanonicalMessage<ShipmentSourceMessage> received = poll(header, cursor, chunk);
                ShipmentSourceMessage source = received.payload();

                if (source.isEmpty()) {
                    break;
                }

                ValidationResult<ShipmentSourceMessage> validation = validate(received, chunk);
                if (validation.rejected()) {
                    // ShipmentValidator 는 치명적 위반을 만들지 않으므로 도달하지 않는다(D-23).
                    // 그래도 분기를 두는 것은 Validator 계약이 그것을 허용하기 때문이다 —
                    // 구현이 바뀌었을 때 조용히 무시되는 대신 여기서 멈춘다.
                    return tally.rejected(header.txId(), validation);
                }
                tally.addSkips(validation);

                CanonicalMessage<List<ShipmentRecord>> mapped =
                        map(received.withPayload(validation.accepted()), chunk);

                // 수신처별 PREPARE/COMMIT 기록은 조율자가 남긴다 — 구간 경계를 아는 쪽이 그쪽이다.
                DeliveryOutcome outcome = coordinator.deliver(mapped, receivers);
                tally.addDelivered(outcome);

                // 커서는 '처리한 마지막 행' 이 아니라 '읽은 마지막 행' 으로 전진한다.
                // 스킵된 꼬리를 지나치지 않으면 다음 청크가 같은 행을 다시 읽는다.
                boolean mayHaveMore = source.mayHaveMore();
                String lastReadOrderId = source.lastReadOrderId();

                if (!mayHaveMore) {
                    break;
                }
                // 커서를 만드는 것은 다음 청크를 읽을 때뿐이다. 마지막 덩어리에서 만들면
                // 쓰이지도 않는 값 때문에 꼬리 행의 ORDER_ID 가 비었을 때 종료 직전에 터진다.
                // 지금은 NOT NULL 이라 일어나지 않지만, 그 전제에 기댈 이유가 없다.
                cursor = PollCursor.after(lastReadOrderId);
                if (chunk == maxChunks) {
                    log.info("[IF-SHP-001] 청크 상한({})에 도달했다 — 남은 미전송 주문은 다음 주기가 처리한다",
                            maxChunks);
                }
            }

            log.info("[IF-SHP-001] 배치 종료 — 전송 {}건, 제외 {}건", tally.processed, tally.skipped);
            return tally.toResult(header.txId());

        } catch (RuntimeException e) {
            // 앞선 청크는 이미 확정되어 STATUS='Y' 다. 그 사실을 지우고 FAIL 로 보고하면 거짓이 된다.
            EaiException wrapped = EaiExceptions.wrap(e, EaiErrorCode.FLOW_ERROR, "배치 청크 처리 실패");
            if (!(e instanceof EaiException)) {
                log.error("[{}] 구간에 귀속되지 않는 실패", wrapped.errorCode().code(), e);
            }
            return tally.interrupted(header.txId(), wrapped.errorCode(),
                    EaiExceptions.reason(wrapped, wrapped.errorCode()));
        }
    }

    /**
     * 미전송 주문 조회.
     *
     * <p>실패 시 기본 코드가 {@code JDBC_EXEC_ERROR} 인 것은 이 인터페이스의 <b>소스가 DB</b> 이기
     * 때문이다. IF-ORD-001 의 같은 구간은 {@code SOURCE_PARSE_ERROR} 를 쓴다 —
     * 같은 {@code SENDER} 구간이라도 상대 시스템이 다르면 연락할 담당자가 다르다.
     */
    private CanonicalMessage<ShipmentSourceMessage> poll(MessageHeader header, PollCursor cursor, int chunk) {
        try (StepScope scope = interfaceLogger.step(header, Step.SENDER)) {
            try {
                CanonicalMessage<ShipmentSourceMessage> message = sender.receive(header, cursor);
                ShipmentSourceMessage source = message.payload();

                scope.detail("CHUNK=%d LIMIT=%d CURSOR=%s", chunk, source.chunkSize(),
                                cursor.fromBeginning() ? "-" : cursor.afterOrderId())
                        .success(source.count());
                return message;

            } catch (RuntimeException e) {
                throw record(scope, e, EaiErrorCode.JDBC_EXEC_ERROR, "미전송 주문 조회 실패");
            }
        }
    }

    private ValidationResult<ShipmentSourceMessage> validate(CanonicalMessage<ShipmentSourceMessage> message,
                                                            int chunk) {
        try (StepScope scope = interfaceLogger.step(message.header(), Step.VALIDATOR)) {
            try {
                ValidationResult<ShipmentSourceMessage> result = validator.validate(message);

                if (result.rejected()) {
                    scope.detail("CHUNK=%d FATAL=%d", chunk, result.fatal().size())
                            .fail(EaiErrorCode.VALIDATION_ERROR, violationSummary(result));
                    return result;
                }

                int accepted = result.accepted().count();
                int skipped = result.skipped().size();
                scope.detail("CHUNK=%d %s", chunk, skipSummary(result));

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

    private CanonicalMessage<List<ShipmentRecord>> map(CanonicalMessage<ShipmentSourceMessage> message, int chunk) {
        try (StepScope scope = interfaceLogger.step(message.header(), Step.MAPPER)) {
            try {
                List<ShipmentRecord> records = mapper.map(message.payload());
                scope.detail("CHUNK=%d ROWS=%d", chunk, records.size()).success(records.size());
                return message.withPayload(records);

            } catch (RuntimeException e) {
                throw record(scope, e, EaiErrorCode.MAPPING_ERROR, "매핑 실패");
            }
        }
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

    /** 예: {@code MISSING_SHIPPING_ADDRESS=2} — 비어 있으면 {@code -} */
    private String skipSummary(ValidationResult<ShipmentSourceMessage> result) {
        Map<String, Integer> detail = result.skipDetail();
        return detail.isEmpty()
                ? "-"
                : detail.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(" "));
    }

    /**
     * 위반 요약. 앞 3건만 싣는다 — 전건을 실으면 이력 파일 한 줄이 수천 자가 된다.
     *
     * <p>값은 담기지 않는다. {@code Violation} 이 애초에 값을 갖지 않으므로
     * (규칙·필드·설명만) 개인정보가 이 경로로 샐 자리가 없다.
     */
    private String violationSummary(ValidationResult<ShipmentSourceMessage> result) {
        String head = result.fatal().stream()
                .limit(3)
                .map(v -> v.rule() + " " + v.field() + " " + v.detail())
                .collect(Collectors.joining("; "));

        int total = result.fatal().size();
        return total <= 3 ? total + "건 위반: " + head
                : total + "건 위반: " + head + " … 외 " + (total - 3) + "건";
    }

    /**
     * 실행 전체의 누적 집계.
     *
     * <p>청크마다 결과를 만들어 마지막 것만 돌려주면 <b>앞의 청크가 사라진다.</b>
     * 특히 중간에 실패했을 때 그때까지 확정된 건수를 알고 있어야, 실패를
     * {@code FAIL}(아무것도 안 됨)이 아니라 {@code PARTIAL}(여기까지는 됨)로 보고할 수 있다.
     *
     * <p>스킵 사유는 <b>합산</b>한다. 청크마다 덮어쓰면 사유별 건수가 마지막 청크의 것만 남는다.
     * 커서 페이징 덕에 같은 행이 두 번 집계되지 않으므로 합산이 곧 정확한 총계다.
     */
    private static final class Tally {

        private int processed;
        private int skipped;
        private final Map<String, Integer> skipDetail = new LinkedHashMap<>();

        private EaiErrorCode manualActionCode;
        private String manualActionDetail;

        void addSkips(ValidationResult<?> validation) {
            skipped += validation.skipped().size();
            validation.skipDetail().forEach((reason, count) -> skipDetail.merge(reason, count, Integer::sum));
        }

        void addDelivered(DeliveryOutcome outcome) {
            processed += outcome.count();

            if (outcome.needsManualAction()) {
                // 수신처가 하나인 지금은 발생하지 않는다(부분 확정이 성립하려면 둘 이상이어야 한다).
                // 그래도 받아 두는 이유는 수신처가 늘어나는 순간 즉시 필요해지기 때문이다 — D-14.
                manualActionCode = outcome.manualActionCode();
                manualActionDetail = outcome.manualActionDetail();
            }
        }

        /** 정상 종료. 스킵이 있으면 {@code ProcessResult.of} 가 PARTIAL 로 판정한다. */
        ProcessResult toResult(String txId) {
            if (manualActionCode != null) {
                return new ProcessResult(txId, ProcessResult.Outcome.PARTIAL,
                        processed, skipped, 0, skipDetail, manualActionCode, manualActionDetail);
            }
            return ProcessResult.of(txId, processed, skipped, skipDetail);
        }

        /**
         * 청크 도중 실패.
         *
         * <p>이미 확정된 건이 있으면 {@code PARTIAL} 이다. {@code FAIL} 로 보고하면
         * "아무것도 적재되지 않았다" 는 뜻이 되고, 그것은 사실이 아니다.
         */
        ProcessResult interrupted(String txId, EaiErrorCode code, String reason) {
            if (processed == 0) {
                // 확정된 것이 없으므로 FAIL 이 맞다. 다만 스킵 집계는 남긴다 —
                // "조회는 됐고 일부는 이상 데이터였다" 는 정보가 실패 사유와 함께 있어야 진단이 된다.
                return new ProcessResult(txId, ProcessResult.Outcome.FAIL,
                        0, skipped, 0, skipDetail, code, reason);
            }
            return new ProcessResult(txId, ProcessResult.Outcome.PARTIAL,
                    processed, skipped, 0, skipDetail, code,
                    processed + "건을 확정한 뒤 실패했다 — 재실행이 아니라 다음 주기가 남은 건을 처리한다. " + reason);
        }

        ProcessResult rejected(String txId, ValidationResult<?> validation) {
            return new ProcessResult(txId, ProcessResult.Outcome.FAIL,
                    processed, skipped, 0, skipDetail, EaiErrorCode.VALIDATION_ERROR,
                    validation.fatal().size() + "건의 치명적 위반으로 청크를 처리할 수 없다");
        }
    }
}
