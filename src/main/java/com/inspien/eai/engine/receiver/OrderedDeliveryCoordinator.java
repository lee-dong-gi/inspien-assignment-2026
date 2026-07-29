package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.EaiException;
import com.inspien.eai.engine.exception.EaiExceptions;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.log.InterfaceLogger;
import com.inspien.eai.engine.log.InterfaceLogger.StepScope;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 등록 순서를 정책으로 삼는 {@link DeliveryCoordinator} 구현 — 보상 트랜잭션의 조율자.
 *
 * <p>정의서 3.9 의 표를 그대로 코드로 옮긴 것이다.
 *
 * <pre>
 *   ① prepare(JDBC)  INSERT, commit 보류        실패 → 던진다 (Receiver 가 스스로 정리했다)
 *   ② prepare(FTP)   접속 + 콘텐츠 생성        실패 → ① 롤백 후 던진다
 *   ③ commit(JDBC)   DB COMMIT                  실패 → ② 보상(서버에 쓴 것이 없다) 후 던진다
 *   ④ commit(FTP)    최종 파일명으로 STOR      실패 → <b>되돌리지 않는다.</b> 수동 조치 결과 반환
 * </pre>
 *
 * <h2>이 클래스가 하는 일은 순서를 지키는 것뿐이다</h2>
 * 되돌리는 <b>방법</b>은 각 {@link Delivery} 가 스스로 안다. 조율자가 "JDBC 면 롤백, FTP 면 삭제"
 * 를 알기 시작하면 수신처를 하나 늘릴 때마다 이 클래스를 고쳐야 하고, 그 순간
 * "수신처가 늘어도 Receiver 만 추가한다" 는 설계가 무너진다.
 *
 * <h2>등록 순서가 곧 정책이다</h2>
 * JDBC 를 먼저, FTP 를 나중에 둔다. <b>되돌리기가 불확실한 쪽을 마지막에</b> 확정해
 * 보상이 필요한 상황 자체를 줄인다 — DB 롤백은 확실하지만 원격 FTP 파일 삭제는 네트워크에 달려 있다.
 * 순서를 뒤집으면 "FTP 는 확정됐는데 DB commit 이 실패" 가 되고, 그때는 이미 배포된 영수증을
 * 회수해야 한다.
 *
 * <h2>예외 vs 반환값 — 이 클래스의 가장 중요한 경계</h2>
 * <ul>
 *   <li><b>예외</b> = 아무 데도 남지 않았다. 호출자는 안전하게 재시도할 수 있다</li>
 *   <li><b>{@link DeliveryOutcome#needsManualAction() 수동 조치 결과}</b> = 어딘가에는 남았다.
 *       재시도하면 중복이다</li>
 * </ul>
 * ④ 를 예외로 던지면 호출자는 {@code FAIL} 로 응답하고, 응답받은 쪽은 재요청하며,
 * 그 재요청이 <b>이미 적재된 63행을 한 번 더 넣는다.</b> 필요한 조치는 재실행이 아니라
 * 파일 하나를 올리는 일이므로 사람에게 넘긴다 (D-14).
 *
 * <h2>실행 이력에 준비와 확정을 따로 남긴다</h2>
 * 수신처마다 {@code PREPARE} 1줄, {@code COMMIT} 1줄이 남는다. 합쳐 1줄로 줄이면
 * <b>"업로드까지는 됐는데 rename 에서 죽었다" 와 "업로드부터 실패했다" 를 구분할 수 없다</b> —
 * 전자는 수동 조치, 후자는 재시도로 조치가 정반대다. 이 로그가 그대로
 * 보상 트랜잭션의 진행 순서를 보여주는 증거가 된다.
 *
 * <h2>남는 한계 — 숨기지 않는다</h2>
 * {@link Delivery#compensate()} 는 반환값이 없으므로 <b>조율자는 보상의 성패를 알 수 없다.</b>
 * 보상 실패는 각 {@code Delivery} 가 {@code EAI-2002} 로 애플리케이션 로그에
 * 남긴다 (FTP 쪽은 D-21 이후 보상할 원격 상태가 없어 실패할 것도 없다). 반환값을 받도록 계약을 바꾸면 조율자가 집계할 수 있지만, 보상은 이미 다른 실패를
 * 처리하는 중에 불리므로 <b>그 결과로 다시 분기하는 코드</b>는 실패 경로를 한 겹 더 복잡하게 만든다.
 * 지금 규모에서는 기록으로 충분하다고 판단했다.
 *
 * @param <T> 타깃 구조
 */
@Slf4j
public final class OrderedDeliveryCoordinator<T> implements DeliveryCoordinator<T> {

    private static final String PHASE_PREPARE = "PREPARE";
    private static final String PHASE_COMMIT = "COMMIT";
    private static final String NOTHING = "-";

    private final InterfaceLogger interfaceLogger;

    public OrderedDeliveryCoordinator(InterfaceLogger interfaceLogger) {
        this.interfaceLogger = interfaceLogger;
    }

    @Override
    public DeliveryOutcome deliver(CanonicalMessage<List<T>> message, List<Receiver<T>> receivers) {
        if (receivers == null || receivers.isEmpty()) {
            // 0건 전달을 성공으로 보고하면, 수신처 빈으로 조립이 잘못된 채 떠 있는 서버가
            // 모든 요청에 SUCCESS 를 돌려주면서 아무것도 적재하지 않는다.
            throw new NonRetryableException(EaiErrorCode.DELIVERY_ERROR,
                    "수신처가 하나도 등록되지 않았다 — 조립 오류다");
        }

        int count = countOf(message);
        List<Prepared> prepared = prepareAll(message, receivers);
        return commitAll(message, prepared, count);
    }

    /**
     * 1단계 — 등록 순서대로 전부 준비한다.
     *
     * <p>하나라도 실패하면 <b>이미 준비된 것들만</b> 역순으로 되돌린다.
     * 실패한 Receiver 자신의 뒷정리(커넥션 반납 · {@code .tmp} 삭제)는 그 Receiver 가 이미 끝냈다 —
     * 조율자가 한 번 더 손대면 이중 정리가 된다.
     */
    private List<Prepared> prepareAll(CanonicalMessage<List<T>> message, List<Receiver<T>> receivers) {
        List<Prepared> prepared = new ArrayList<>(receivers.size());

        for (Receiver<T> receiver : receivers) {
            Step step = receiver.step();
            try (StepScope scope = interfaceLogger.step(message.header(), step)) {
                try {
                    Delivery delivery = receiver.prepare(message);
                    prepared.add(new Prepared(step, delivery));
                    scope.detail(PHASE_PREPARE).success(delivery.count());

                } catch (RuntimeException e) {
                    EaiErrorCode code = EaiExceptions.codeOf(e, EaiErrorCode.DELIVERY_ERROR);
                    List<Step> undone = compensateFrom(prepared, 0);

                    scope.detail("%s COMPENSATE=%s", PHASE_PREPARE, describe(undone))
                            .fail(code, EaiExceptions.reason(e, code), countOf(message));
                    throw unexpected(e);
                }
            }
        }
        return prepared;
    }

    /**
     * 2단계 — 등록 순서대로 확정한다.
     *
     * <p>확정이 하나라도 성공한 뒤의 실패는 <b>되돌릴 수 없다.</b>
     * 순차 확정이므로 "아직 하나도 확정되지 않았다" 는 곳 {@code i == 0} 으로 판정된다 —
     * 이 한 줄이 <b>예외로 끝낼지 수동 조치로 넘길지</b> 를 가른다.
     */
    private DeliveryOutcome commitAll(CanonicalMessage<List<T>> message, List<Prepared> prepared, int count) {
        int total = prepared.size();

        for (int i = 0; i < total; i++) {
            Prepared current = prepared.get(i);

            try (StepScope scope = interfaceLogger.step(message.header(), current.step())) {
                try {
                    current.delivery().commit();
                    scope.detail(PHASE_COMMIT).success(current.delivery().count());

                } catch (RuntimeException e) {
                    EaiErrorCode code = EaiExceptions.codeOf(e, EaiErrorCode.DELIVERY_ERROR);
                    String reason = EaiExceptions.reason(e, code);

                    // 확정에 실패한 것 자신은 보상 대상이 아니다. commit 이 실패한 시점에
                    // 그 Delivery 는 이미 자기 자원을 정리했고(커넥션 반납 / 세션 종료),
                    // FTP 의 경우 .tmp 에 유효한 데이터가 들어 있어 지우면 안 된다.
                    List<Step> undone = compensateFrom(prepared, i + 1);

                    if (i == 0) {
                        // 아무것도 확정되지 않았다 → 온전한 실패. 재시도해도 안전하다.
                        scope.detail("%s COMPENSATE=%s", PHASE_COMMIT, describe(undone))
                                .fail(code, reason, count);
                        throw unexpected(e);
                    }

                    // 이미 확정된 수신처가 있다 → 되돌릴 수 없다. 실패로 보고하면 재요청 → 중복 적재.
                    String action = "%s 확정 실패. 수신처 %d/%d 만 확정됨 — 재실행하면 중복 적재가 된다. %s"
                            .formatted(current.step(), i, total, reason);

                    scope.detail("%s CONFIRMED=%d/%d MANUAL_ACTION", PHASE_COMMIT, i, total)
                            .fail(code, reason, count);
                    log.error("[{}] 되돌릴 수 없는 지점에서 실패했다 — {}", code.code(), action, e);

                    return DeliveryOutcome.manualActionRequired(count, i, total, code, action);
                }
            }
        }
        return DeliveryOutcome.completed(count, total);
    }

    /**
     * {@code fromInclusive} 부터 끝까지를 <b>역순으로</b> 되돌린다.
     *
     * <p>역순인 이유는 나중에 준비된 것이 먼저 준비된 것에 의존할 수 있기 때문이다.
     * 의존의 반대 방향으로 풀어야 중간 상태가 남지 않는다.
     *
     * <p>{@link Delivery#compensate()} 는 예외를 던지지 않기로 한 계약이지만,
     * 그 계약을 어기는 구현이 섞이더라도 <b>나머지 보상을 멈추지 않는다.</b>
     * 하나가 못 돌아갔다는 이유로 되돌릴 수 있는 것까지 남겨 둘 이유가 없다.
     *
     * @return 실제로 보상을 요청한 구간들 (요청 순서 = 역순)
     */
    private List<Step> compensateFrom(List<Prepared> prepared, int fromInclusive) {
        List<Step> undone = new ArrayList<>();

        for (int i = prepared.size() - 1; i >= fromInclusive; i--) {
            Prepared target = prepared.get(i);
            try {
                target.delivery().compensate();
                undone.add(target.step());
            } catch (RuntimeException e) {
                log.error("[{}] {} 보상 중 예외가 올라왔다 — 나머지 보상은 계속한다",
                        EaiErrorCode.DELIVERY_ERROR.code(), target.step(), e);
            }
        }
        return undone;
    }

    /**
     * 건수는 페이로드에서 센다.
     *
     * <p>수신처별 {@link Delivery#count()} 를 더하지 않는다. 두 수신처가 <b>같은 레코드 리스트</b>를
     * 소비하므로 63행이 126건으로 부풀어 오른다.
     */
    private int countOf(CanonicalMessage<List<T>> message) {
        List<T> payload = message.payload();
        return payload == null ? 0 : payload.size();
    }

    /**
     * 수신처가 계약을 어기고 {@link EaiException} 이 아닌 것을 던졌을 때 코드를 달아 준다.
     *
     * <p>그대로 올려보내면 호출자가 재시도 가능 여부를 판단할 근거가 없다.
     * 원인은 {@code cause} 로 보존된다.
     */
    private static EaiException unexpected(RuntimeException e) {
        return EaiExceptions.wrap(e, EaiErrorCode.DELIVERY_ERROR, "수신처가 예기치 못한 예외를 던졌다");
    }

    private static String describe(List<Step> steps) {
        return steps.isEmpty()
                ? NOTHING
                : steps.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * 준비를 마친 전달 작업과 그 출처 구간.
     *
     * <p>{@link Delivery} 만 모아 두면 실패했을 때 <b>어느 수신처인지 말할 수 없다.</b>
     * "전달 실패" 로그와 "RECEIVER_FTP 전달 실패" 로그는 운영자에게 전혀 다른 정보다.
     */
    private record Prepared(Step step, Delivery delivery) {
    }
}
