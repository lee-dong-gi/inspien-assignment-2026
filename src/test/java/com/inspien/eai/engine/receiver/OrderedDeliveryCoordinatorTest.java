package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.RecordingInterfaceLogger;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보상 트랜잭션의 <b>순서</b>를 검증한다 (정의서 3.9).
 *
 * <p>여기서 확인하는 것은 DB 도 FTP 도 아니다. 조율자가 하는 일은 순서를 지키는 것뿐이므로
 * 검증할 것도 <b>호출 순서와 되돌림 범위</b>다. 그래서 Mockito 로 검증 구문을 쌓는 대신
 * 호출을 한 줄씩 적어 두는 기록형 대역을 썼다 — 기대값 리스트가 그대로 계약 문장이 된다.
 *
 * <pre>
 *   [RECEIVER_JDBC:PREPARE, RECEIVER_FTP:PREPARE, RECEIVER_JDBC:COMMIT, RECEIVER_FTP:COMMIT]
 * </pre>
 *
 * <p>가장 중요한 단언은 <b>"이미 확정된 것을 되돌리지 않는다"</b> 이다.
 * 그 실수는 운영에서 <b>멀쩡히 적재된 주문을 지우는</b> 사고가 된다.
 */
@DisplayName("OrderedDeliveryCoordinator — 보상 트랜잭션 조율")
class OrderedDeliveryCoordinatorTest {

    private static final int ROWS = 63;

    private final List<String> trace = new ArrayList<>();
    private final RecordingInterfaceLogger interfaceLogger = new RecordingInterfaceLogger();
    private final OrderedDeliveryCoordinator<String> coordinator =
            new OrderedDeliveryCoordinator<>(interfaceLogger);

    // ------------------------------------------------------------------ 정상 경로

    @Test
    @DisplayName("전부 준비한 뒤에야 확정을 시작한다 — 확정이 준비 사이에 끼어들면 안 된다")
    void preparesAllBeforeCommitting() {
        TraceReceiver jdbc = receiver(Step.RECEIVER_JDBC);
        TraceReceiver ftp = receiver(Step.RECEIVER_FTP);

        DeliveryOutcome outcome = coordinator.deliver(message(ROWS), List.of(jdbc, ftp));

        assertAll(
                () -> assertEquals(List.of(
                        "RECEIVER_JDBC:PREPARE",
                        "RECEIVER_FTP:PREPARE",
                        "RECEIVER_JDBC:COMMIT",
                        "RECEIVER_FTP:COMMIT"), trace),
                () -> assertFalse(outcome.needsManualAction()),
                () -> assertEquals(ROWS, outcome.count(), "수신처별 건수를 더하면 126 이 된다"),
                () -> assertEquals(2, outcome.confirmedTargets()),
                () -> assertEquals(2, outcome.totalTargets()));
    }

    @Test
    @DisplayName("0건이어도 수신처를 건너뛰지 않는다 — 건너뛸지는 Receiver 가 판단할 몫이다")
    void visitsEveryReceiverEvenWhenEmpty() {
        DeliveryOutcome outcome = coordinator.deliver(
                message(0), List.of(receiver(Step.RECEIVER_JDBC), receiver(Step.RECEIVER_FTP)));

        assertAll(
                () -> assertEquals(4, trace.size()),
                () -> assertEquals(0, outcome.count()),
                () -> assertFalse(outcome.needsManualAction()));
    }

    // ------------------------------------------------------------------ 준비 단계 실패

    @Test
    @DisplayName("준비가 실패하면 이미 준비된 것을 역순으로 되돌린다 — 정의서 3.9 ②")
    void compensatesPreparedOnesInReverseWhenPrepareFails() {
        TraceReceiver jdbc = receiver(Step.RECEIVER_JDBC);
        TraceReceiver ftp = failingPrepare(Step.RECEIVER_FTP,
                new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR, "업로드 거부됨"));

        RetryableException e = assertThrows(RetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(jdbc, ftp)));

        assertAll(
                () -> assertEquals(List.of(
                        "RECEIVER_JDBC:PREPARE",
                        "RECEIVER_FTP:PREPARE",
                        "RECEIVER_JDBC:COMPENSATE"), trace),
                () -> assertEquals(EaiErrorCode.FTP_UPLOAD_ERROR, e.errorCode()),
                // 실패한 Receiver 자신의 뒷정리는 Receiver 가 이미 끝냈다. 또 손대면 이중 정리다.
                () -> assertFalse(trace.contains("RECEIVER_FTP:COMPENSATE")));
    }

    @Test
    @DisplayName("첫 수신처가 준비에 실패하면 되돌릴 것이 없다")
    void nothingToCompensateWhenFirstPrepareFails() {
        TraceReceiver jdbc = failingPrepare(Step.RECEIVER_JDBC,
                new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR, "PK 위반"));

        assertThrows(NonRetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(jdbc, receiver(Step.RECEIVER_FTP))));

        assertAll(
                () -> assertEquals(List.of("RECEIVER_JDBC:PREPARE"), trace),
                () -> assertTrue(lastLogLine().contains("COMPENSATE=-")));
    }

    // ------------------------------------------------------------------ 확정 단계 실패

    @Test
    @DisplayName("첫 확정이 실패하면 아직 확정하지 않은 것만 되돌리고 예외로 끝난다 — 정의서 3.9 ③")
    void compensatesUncommittedOnesWhenFirstCommitFails() {
        TraceReceiver jdbc = failingCommit(Step.RECEIVER_JDBC,
                new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR, "커밋 실패"));
        TraceReceiver ftp = receiver(Step.RECEIVER_FTP);

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(jdbc, ftp)));

        assertAll(
                () -> assertEquals(List.of(
                        "RECEIVER_JDBC:PREPARE",
                        "RECEIVER_FTP:PREPARE",
                        "RECEIVER_JDBC:COMMIT",
                        "RECEIVER_FTP:COMPENSATE"), trace),
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                // 확정에 실패한 쪽은 이미 자기 자원을 정리했다. 여기서 또 부르면 이중 정리다.
                () -> assertFalse(trace.contains("RECEIVER_JDBC:COMPENSATE")));
    }

    @Test
    @DisplayName("이미 확정된 것이 있으면 되돌리지 않고 수동 조치 결과를 돌려준다 — 정의서 3.9 ④ / D-14")
    void returnsManualActionInsteadOfThrowingAfterFirstCommit() {
        TraceReceiver jdbc = receiver(Step.RECEIVER_JDBC);
        TraceReceiver ftp = failingCommit(Step.RECEIVER_FTP,
                new NonRetryableException(EaiErrorCode.FTP_COMMIT_FAILED, "서버가 업로드를 거부했다"));

        DeliveryOutcome outcome = coordinator.deliver(message(ROWS), List.of(jdbc, ftp));

        assertAll(
                () -> assertEquals(List.of(
                        "RECEIVER_JDBC:PREPARE",
                        "RECEIVER_FTP:PREPARE",
                        "RECEIVER_JDBC:COMMIT",
                        "RECEIVER_FTP:COMMIT"), trace),
                // 확정된 DB 트랜잭션을 되돌리려 들면 멀쩡한 주문 63건이 사라진다.
                () -> assertFalse(trace.contains("RECEIVER_JDBC:COMPENSATE")),
                () -> assertTrue(outcome.needsManualAction()),
                () -> assertEquals(EaiErrorCode.FTP_COMMIT_FAILED, outcome.manualActionCode()),
                // 적재된 건수를 0 으로 낮춰 보고하면 정합성이 깨진 사실 자체가 숨겨진다.
                () -> assertEquals(ROWS, outcome.count()),
                () -> assertEquals(1, outcome.confirmedTargets()),
                () -> assertEquals(2, outcome.totalTargets()),
                () -> assertTrue(outcome.manualActionDetail().contains("중복 적재")));
    }

    // ------------------------------------------------------------------ 조율자 자신의 오류

    @Test
    @DisplayName("수신처가 하나도 없으면 성공으로 보고하지 않고 EAI-4004 로 실패한다")
    void rejectsEmptyReceiverList() {
        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of()));

        assertEquals(EaiErrorCode.DELIVERY_ERROR, e.errorCode());
    }

    @Test
    @DisplayName("EAI 예외가 아닌 것이 올라와도 코드를 달아 내보낸다 — 원인은 cause 로 보존한다")
    void wrapsUnexpectedException() {
        IllegalStateException cause = new IllegalStateException("경계 밖의 버그");
        TraceReceiver jdbc = receiver(Step.RECEIVER_JDBC);
        TraceReceiver ftp = failingPrepare(Step.RECEIVER_FTP, cause);

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(jdbc, ftp)));

        assertAll(
                () -> assertEquals(EaiErrorCode.DELIVERY_ERROR, e.errorCode()),
                () -> assertSame(cause, e.getCause()),
                () -> assertTrue(trace.contains("RECEIVER_JDBC:COMPENSATE"),
                        "예상 밖의 예외라도 준비된 것은 되돌아가야 한다"));
    }

    @Test
    @DisplayName("보상 하나가 터져도 나머지 보상을 멈추지 않는다")
    void keepsCompensatingWhenOneCompensationBlowsUp() {
        TraceReceiver first = receiver(Step.RECEIVER_JDBC);
        TraceReceiver second = new TraceReceiver(Step.STATUS_UPDATE,
                new TraceDelivery(Step.STATUS_UPDATE, ROWS, null, new IllegalStateException("보상 폭발")),
                null);
        TraceReceiver third = failingPrepare(Step.RECEIVER_FTP,
                new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR, "업로드 거부됨"));

        assertThrows(RetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(first, second, third)));

        assertAll(
                () -> assertTrue(trace.contains("STATUS_UPDATE:COMPENSATE")),
                () -> assertTrue(trace.contains("RECEIVER_JDBC:COMPENSATE"),
                        "앞 보상이 터졌다고 먼저 준비된 쪽을 건너뛰면 중간 상태가 남는다"));
    }

    // ------------------------------------------------------------------ 실행 이력

    @Test
    @DisplayName("실행 이력에는 수신처마다 준비와 확정이 따로 남는다 — 조치가 정반대이기 때문이다")
    void writesPrepareAndCommitSeparately() {
        coordinator.deliver(message(ROWS),
                List.of(receiver(Step.RECEIVER_JDBC), receiver(Step.RECEIVER_FTP)));

        assertEquals(List.of(
                "RECEIVER_JDBC SUCCESS PREPARE",
                "RECEIVER_FTP SUCCESS PREPARE",
                "RECEIVER_JDBC SUCCESS COMMIT",
                "RECEIVER_FTP SUCCESS COMMIT"), interfaceLogger.lines());
    }

    @Test
    @DisplayName("여러 줄짜리 예외 메시지를 한 줄로 펴서 남긴다 — 이력 파일은 한 건이 한 줄이다")
    void flattensMultilineFailureMessage() {
        TraceReceiver ftp = failingPrepare(Step.RECEIVER_FTP,
                new NonRetryableException(EaiErrorCode.FTP_ENCODING_ERROR, """
                        업로드한 파일명이 보존되지 않았다.
                          보낸 이름: INSPIEN_홍길동_20260729.txt
                        """));

        assertThrows(NonRetryableException.class,
                () -> coordinator.deliver(message(ROWS), List.of(ftp)));

        assertFalse(lastLogLine().contains("\n"));
    }

    // ------------------------------------------------------------------ 대역

    private CanonicalMessage<List<String>> message(int rows) {
        List<String> payload = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            payload.add("row-" + i);
        }
        return new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_ORD_001), payload);
    }

    private TraceReceiver receiver(Step step) {
        return new TraceReceiver(step, new TraceDelivery(step, ROWS, null, null), null);
    }

    private TraceReceiver failingPrepare(Step step, RuntimeException failure) {
        return new TraceReceiver(step, null, failure);
    }

    private TraceReceiver failingCommit(Step step, RuntimeException failure) {
        return new TraceReceiver(step, new TraceDelivery(step, ROWS, failure, null), null);
    }

    private String lastLogLine() {
        return interfaceLogger.last();
    }

    /** 호출된 사실을 순서대로 적어 두는 Receiver 대역. */
    private final class TraceReceiver implements Receiver<String> {

        private final Step step;
        private final Delivery delivery;
        private final RuntimeException prepareFailure;

        private TraceReceiver(Step step, Delivery delivery, RuntimeException prepareFailure) {
            this.step = step;
            this.delivery = delivery;
            this.prepareFailure = prepareFailure;
        }

        @Override
        public Step step() {
            return step;
        }

        @Override
        public Delivery prepare(CanonicalMessage<List<String>> message) {
            trace.add(step + ":PREPARE");
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            return delivery;
        }
    }

    /**
     * 상태 방어를 <b>하지 않는</b> Delivery 대역.
     *
     * <p>실제 구현({@code PendingCommitDelivery} 등)은 확정된 뒤의 보상을 스스로 무시하지만,
     * 그 방어에 기대면 조율자의 순서 오류가 테스트에서 드러나지 않는다.
     * 여기서는 불린 그대로 기록해 <b>조율자가 부르지 말았어야 할 것을 불렀는지</b>를 노출시킨다.
     */
    private final class TraceDelivery implements Delivery {

        private final Step step;
        private final int count;
        private final RuntimeException commitFailure;
        private final RuntimeException compensateFailure;

        private TraceDelivery(Step step, int count,
                              RuntimeException commitFailure, RuntimeException compensateFailure) {
            this.step = step;
            this.count = count;
            this.commitFailure = commitFailure;
            this.compensateFailure = compensateFailure;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public void commit() {
            trace.add(step + ":COMMIT");
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        public void compensate() {
            trace.add(step + ":COMPENSATE");
            if (compensateFailure != null) {
                throw compensateFailure;
            }
        }
    }

}
