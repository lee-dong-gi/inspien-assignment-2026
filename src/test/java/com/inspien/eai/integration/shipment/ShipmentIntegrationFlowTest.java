package com.inspien.eai.integration.shipment;

import com.inspien.eai.common.id.InMemoryIdSequence;
import com.inspien.eai.common.id.SequenceKey;
import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.lock.DistributedLock;
import com.inspien.eai.common.lock.LockHandle;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.RecordingInterfaceLogger;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.OrderedDeliveryCoordinator;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.engine.sender.Sender;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.Validator;
import com.inspien.eai.integration.shipment.mapper.ShipmentMapper;
import com.inspien.eai.integration.shipment.source.PendingOrder;
import com.inspien.eai.integration.shipment.source.PollCursor;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;
import com.inspien.eai.integration.shipment.validator.ShipmentValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IF-SHP-001 파이프라인 조립 검증.
 *
 * <p>{@code OrderIntegrationFlowTest} 와 같은 방식이다 — <b>Sender·수신처·락만 대역</b>이고
 * 검증기·매퍼·조율자는 실물을 쓴다. 부품은 각자 테스트가 있으므로 여기서 볼 것은
 * <b>이어 붙였을 때의 행동</b>이며, 그중 이 인터페이스에만 있는 것이 셋이다.
 *
 * <ol>
 *   <li><b>청크 반복.</b> 커서가 어디까지 전진하는가, 언제 멈추는가 (D-26)</li>
 *   <li><b>누적 집계.</b> 청크 중간에 실패했을 때 앞의 건수가 살아 있는가</li>
 *   <li><b>분산 락.</b> 못 잡았을 때의 보고, 그리고 어떤 경로로 끝나도 반납되는가</li>
 * </ol>
 *
 * <p>DB·Redis 를 띄우지 않는다. 위 셋은 전부 <b>호출 순서와 집계</b>의 문제이지
 * 저장소의 문제가 아니며, 오히려 대역으로 세워야 "커서가 A003 으로 갔다" 를 단언할 수 있다.
 */
@DisplayName("ShipmentIntegrationFlow — IF-SHP-001 배치 파이프라인")
class ShipmentIntegrationFlowTest {

    /** 청크 크기를 작게 둔다. 반복·상한·커서 전진을 3건 단위로 관찰할 수 있다 */
    private static final int CHUNK = 3;

    private final List<String> trace = new ArrayList<>();
    private final RecordingInterfaceLogger interfaceLogger = new RecordingInterfaceLogger();

    // ------------------------------------------------------------------ 정상 경로

    @Test
    @DisplayName("청크가 덜 차면 한 번만 조회하고 끝낸다 — 빈 조회를 한 번 더 하지 않는다")
    void stopsWhenChunkIsNotFull() {
        StubSender sender = new StubSender().chunk(ok("A001"), ok("A002"));
        StubLock lock = StubLock.granting();

        ProcessResult result = flow(sender, lock).execute(BatchTrigger.MANUAL);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(2, result.processed()),
                () -> assertEquals(0, result.skipped()),
                () -> assertEquals(1, sender.calls(), "덜 찬 청크는 그것이 전부라는 뜻이다"),
                () -> assertEquals(List.of("PREPARE(2)", "COMMIT(2)"), trace),
                () -> assertEquals(1, lock.released(), "락은 정상 종료에서도 반납된다"));
    }

    @Test
    @DisplayName("청크가 꽉 차면 마지막으로 읽은 ORDER_ID 뒤부터 다음 청크를 읽는다")
    void advancesCursorPastTheLastReadRow() {
        StubSender sender = new StubSender()
                .chunk(ok("A001"), ok("A002"), ok("A003"))
                .chunk(ok("A004"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(4, result.processed()),
                () -> assertEquals(2, sender.calls()),
                // OFFSET 이 아니라 keyset 이다. 처리된 행이 'Y' 로 빠져도 커서는 어긋나지 않는다.
                () -> assertEquals(List.of("-", "A003"), sender.requestedCursors()));
    }

    @Test
    @DisplayName("미전송 주문이 없으면 수신처를 부르지 않고 SUCCESS 0건이다")
    void doesNothingWhenNothingIsPending() {
        StubSender sender = new StubSender().chunk();

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(0, result.processed()),
                () -> assertTrue(trace.isEmpty(), "0건에 트랜잭션을 열 이유가 없다"),
                () -> assertEquals(1, sender.calls()));
    }

    // ------------------------------------------------------------------ 커서와 스킵 (D-26)

    @Test
    @DisplayName("스킵된 행도 커서를 전진시킨다 — 그러지 않으면 같은 행을 매 청크 다시 읽는다")
    void skippedRowsStillAdvanceTheCursor() {
        StubSender sender = new StubSender()
                // 꼬리가 스킵 대상이다. 커서를 '처리한 마지막 행'(A002)으로 두면 A003 을 또 읽는다.
                .chunk(ok("A001"), ok("A002"), noAddress("A003"))
                .chunk(ok("A004"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(3, result.processed()),
                () -> assertEquals(1, result.skipped()),
                () -> assertEquals(List.of("-", "A003"), sender.requestedCursors()));
    }

    @Test
    @DisplayName("전건이 스킵된 청크도 커서를 전진시켜 다음으로 넘어간다 — 무한 루프의 자리다")
    void fullySkippedChunkDoesNotLoopForever() {
        StubSender sender = new StubSender()
                .chunk(noAddress("A001"), noAddress("A002"), noAddress("A003"))
                .chunk(ok("A004"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(1, result.processed()),
                () -> assertEquals(3, result.skipped()),
                () -> assertEquals(2, sender.calls(), "커서가 전진하지 않으면 상한까지 같은 조회를 반복한다"),
                () -> assertEquals(List.of("-", "A003"), sender.requestedCursors()));
    }

    @Test
    @DisplayName("스킵 사유는 청크를 넘어 합산된다 — 마지막 청크의 것만 남으면 집계가 거짓이 된다")
    void accumulatesSkipDetailAcrossChunks() {
        StubSender sender = new StubSender()
                .chunk(ok("A001"), noAddress("A002"), noItem("A003"))
                .chunk(noAddress("A004"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(1, result.processed()),
                () -> assertEquals(3, result.skipped()),
                () -> assertEquals(2, result.skipDetail().get("MISSING_SHIPPING_ADDRESS")),
                () -> assertEquals(1, result.skipDetail().get("MISSING_ORDER_KEY")),
                // 커서 페이징 덕에 같은 행이 두 번 집계되지 않으므로 합산이 곧 정확한 총계다.
                () -> assertEquals(3, result.skipDetail().values().stream().mapToInt(Integer::intValue).sum()));
    }

    @Test
    @DisplayName("청크 상한에 걸리면 남은 일을 다음 주기에 넘긴다 — 배치에서는 '다 못 했다'가 정상이다")
    void stopsAtChunkLimit() {
        StubSender sender = new StubSender()
                .chunk(ok("A001"), ok("A002"), ok("A003"))
                .chunk(ok("A004"), ok("A005"), ok("A006"))
                .chunk(ok("A007"), ok("A008"), ok("A009"));

        ProcessResult result = flow(sender, StubLock.granting(), 2).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(6, result.processed()),
                () -> assertEquals(2, sender.calls(), "상한이 없으면 락 TTL 을 넘길 수 있다"));
    }

    @Test
    @DisplayName("마지막 청크의 꼬리 행에 ORDER_ID 가 없어도 종료 직전에 터지지 않는다")
    void buildsNoCursorForTheFinalChunk() {
        // ORDER_TB.ORDER_ID 는 NOT NULL 이므로 실제로는 오지 않는 행이다. 그럼에도 단언하는 이유는
        // 쓰이지도 않을 커서를 먼저 만들어 두면, 그 전제가 깨지는 날 조용히 실패하기 때문이다.
        StubSender sender = new StubSender()
                .chunk(ok("A001"), new PendingOrder("   ", "ITEM02", "서울특별시 금천구"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(1, result.processed()),
                () -> assertEquals(1, result.skipped()),
                () -> assertNull(result.errorCode(), "정상 종료여야 한다"),
                () -> assertEquals(1, sender.calls()));
    }

    // ------------------------------------------------------------------ 분산 락

    @Test
    @DisplayName("락을 못 잡으면 EAI-4001 로 보고하고 조회조차 하지 않는다 — 성공 0건이 아니다")
    void reportsLockContentionInsteadOfSilentSuccess() {
        StubSender sender = new StubSender().chunk(ok("A001"));
        StubLock lock = StubLock.busy();

        ProcessResult result = flow(sender, lock).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(EaiErrorCode.BATCH_LOCK_ACQUIRE_FAILED, result.errorCode()),
                // SUCCESS/0건으로 보고하면 "할 일이 없었다" 와 구분되지 않고, 배치가 멈춘 것을 못 본다.
                () -> assertEquals(0, sender.calls()),
                () -> assertEquals("END FAIL", interfaceLogger.last()),
                () -> assertEquals(0, lock.released(), "잡지 못한 락을 반납하면 남의 락을 푼다"));
    }

    @Test
    @DisplayName("락 저장소에 닿지 못하면 EAI-4006 이다 — '누가 쥐고 있다'와 조치가 정반대다")
    void distinguishesLockStoreFailureFromContention() {
        StubSender sender = new StubSender().chunk(ok("A001"));
        StubLock lock = StubLock.broken(new RetryableException(
                EaiErrorCode.BATCH_LOCK_STORE_ERROR, "락 저장소에 닿지 못했다"));

        ProcessResult result = flow(sender, lock).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(EaiErrorCode.BATCH_LOCK_STORE_ERROR, result.errorCode()),
                () -> assertEquals(0, sender.calls(), "락 없이 배치를 돌리지 않는다"),
                () -> assertEquals("END FAIL", interfaceLogger.last()));
    }

    @Test
    @DisplayName("실패 경로에서도 락을 반납한다 — 빠뜨리면 TTL 만료까지 배치가 멈춘다")
    void releasesLockEvenWhenChunkFails() {
        StubSender sender = new StubSender()
                .failure(new RetryableException(EaiErrorCode.JDBC_CONN_ERROR, "커넥션이 끊겼다"));
        StubLock lock = StubLock.granting();

        ProcessResult result = flow(sender, lock).execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(1, lock.released()),
                () -> assertEquals("eai:lock:if-shp-001-test", lock.requestedKey()),
                () -> assertEquals(Duration.ofMinutes(4), lock.requestedTtl()));
    }

    // ------------------------------------------------------------------ 누적 집계와 중간 실패

    @Test
    @DisplayName("앞 청크가 확정된 뒤 실패하면 FAIL 이 아니라 PARTIAL 이고 건수를 보존한다")
    void keepsConfirmedCountWhenLaterChunkFails() {
        StubSender sender = new StubSender()
                .chunk(ok("A001"), ok("A002"), ok("A003"))
                .failure(new RetryableException(EaiErrorCode.JDBC_CONN_ERROR, "커넥션이 끊겼다"));

        ProcessResult result = flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertAll(
                // FAIL/0건으로 보고하면 운영자는 아무것도 처리되지 않은 줄 알고 원인을 엉뚱한 데서 찾는다.
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(3, result.processed()),
                () -> assertEquals(EaiErrorCode.JDBC_CONN_ERROR, result.errorCode()),
                () -> assertTrue(result.errorMessage().contains("3건"),
                        "확정된 건수가 사유 문구에 있어야 다음 주기 재처리와 재실행을 혼동하지 않는다"),
                () -> assertEquals(List.of("PREPARE(3)", "COMMIT(3)"), trace));
    }

    @Test
    @DisplayName("확정된 것이 없으면 FAIL 이지만 스킵 집계는 남긴다 — 진단에 필요한 정보다")
    void reportsFailWithSkipDetailWhenNothingWasConfirmed() {
        StubSender sender = new StubSender().chunk(ok("A001"), noAddress("A002"), ok("A003"));
        Receiver<ShipmentRecord> failing = receiver(1,
                new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR, "제약 위반"));

        ProcessResult result = flow(sender, StubLock.granting(), 50, List.of(failing), new ShipmentValidator())
                .execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(0, result.processed()),
                () -> assertEquals(1, result.skipped()),
                () -> assertEquals(1, result.skipDetail().get("MISSING_SHIPPING_ADDRESS")),
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, result.errorCode()));
    }

    // ------------------------------------------------------------------ 거부 (D-23 의 방어 분기)

    @Test
    @DisplayName("검증이 치명적 위반을 내면 수신처를 부르지 않는다 — 배치 Validator 는 그러지 않지만 계약은 허용한다")
    void neverTouchesReceiverWhenValidationRejects() {
        StubSender sender = new StubSender().chunk(ok("A001"));
        Validator<ShipmentSourceMessage> rejecting = message -> ValidationResult.reject(
                List.of(new ValidationResult.Violation("V-99", "TEST", "일부러 거부한다")));

        ProcessResult result = flow(sender, StubLock.granting(), 50, List.of(receiver()), rejecting)
                .execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(EaiErrorCode.VALIDATION_ERROR, result.errorCode()),
                () -> assertTrue(trace.isEmpty(), "거부된 청크가 대상 시스템에 닿으면 안 된다"),
                () -> assertEquals(1, sender.calls(), "다음 청크로 넘어가지 않는다"));
    }

    // ------------------------------------------------------------------ 실행 이력과 추적성

    @Test
    @DisplayName("실행 이력에 청크 번호를 달고 전 구간이 순서대로 남는다")
    void writesEveryStepWithChunkNumber() {
        StubSender sender = new StubSender().chunk(ok("A001"), ok("A002"));

        flow(sender, StubLock.granting()).execute(BatchTrigger.MANUAL);

        assertEquals(List.of(
                "START",
                "SENDER SUCCESS CHUNK=1 LIMIT=3 CURSOR=-",
                "VALIDATOR SUCCESS CHUNK=1 -",
                "MAPPER SUCCESS CHUNK=1 ROWS=2",
                "RECEIVER_JDBC SUCCESS PREPARE",
                "RECEIVER_JDBC SUCCESS COMMIT",
                "END SUCCESS"), interfaceLogger.lines());
    }

    @Test
    @DisplayName("스킵이 있으면 구간 로그에서부터 PARTIAL 로 남고 사유가 붙는다")
    void marksValidatorStepPartialWithReasons() {
        StubSender sender = new StubSender().chunk(ok("A001"), noAddress("A002"));

        flow(sender, StubLock.granting()).execute(BatchTrigger.SCHEDULED);

        assertTrue(interfaceLogger.steps().stream()
                        .anyMatch(line -> line.equals("VALIDATOR PARTIAL CHUNK=1 MISSING_SHIPPING_ADDRESS=1")),
                "실제 구간 로그: " + interfaceLogger.steps());
    }

    @Test
    @DisplayName("호출자가 없어도 결과는 txId 를 달고 나온다 — 로그와 결과를 잇는 유일한 고리다")
    void everyResultCarriesTxId() {
        ProcessResult first = flow(new StubSender().chunk(ok("A001")), StubLock.granting())
                .execute(BatchTrigger.SCHEDULED);
        ProcessResult second = flow(new StubSender().chunk(), StubLock.busy())
                .execute(BatchTrigger.SCHEDULED);

        assertAll(
                () -> assertFalse(first.txId().isBlank()),
                () -> assertFalse(second.txId().isBlank()),
                () -> assertNotEquals(first.txId(), second.txId(), "실행마다 새로 발급된다"));
    }

    @Test
    @DisplayName("트리거가 null 이어도 스케줄 실행으로 간주하고 돈다")
    void treatsNullTriggerAsScheduled() {
        ProcessResult result = flow(new StubSender().chunk(ok("A001")), StubLock.granting()).execute(null);

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(1, result.processed()),
                () -> assertNull(result.errorCode()));
    }

    @Test
    @DisplayName("인터페이스 식별자는 IF-SHP-001 이다")
    void reportsOwnInterfaceId() {
        assertEquals(InterfaceId.IF_SHP_001, flow(new StubSender(), StubLock.granting()).ifId());
    }

    // ── 조립 ───────────────────────────────────────────────────

    private ShipmentIntegrationFlow flow(StubSender sender, StubLock lock) {
        return flow(sender, lock, 50);
    }

    private ShipmentIntegrationFlow flow(StubSender sender, StubLock lock, int maxChunks) {
        return flow(sender, lock, maxChunks, List.of(receiver()), new ShipmentValidator());
    }

    private ShipmentIntegrationFlow flow(StubSender sender,
                                         StubLock lock,
                                         int maxChunks,
                                         List<Receiver<ShipmentRecord>> receivers,
                                         Validator<ShipmentSourceMessage> validator) {
        ShipmentMapper mapper = new ShipmentMapper(
                new SequentialIdGenerator(new InMemoryIdSequence(), SequenceKey.SHIPMENT), "TESTKEY");

        return new ShipmentIntegrationFlow(
                sender,
                validator,
                mapper,
                new OrderedDeliveryCoordinator<>(interfaceLogger),
                receivers,
                lock,
                properties(maxChunks),
                interfaceLogger);
    }

    private static ShipmentBatchProperties properties(int maxChunks) {
        return new ShipmentBatchProperties(
                false,
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                CHUNK,
                maxChunks,
                "eai:lock:if-shp-001-test",
                Duration.ofMinutes(4));
    }

    // ── 소스 행 ─────────────────────────────────────────────────

    private static PendingOrder ok(String orderId) {
        return new PendingOrder(orderId, "ITEM01", "서울특별시 금천구");
    }

    /** 배송지가 공백뿐인 행 — 스킵 대상 */
    private static PendingOrder noAddress(String orderId) {
        return new PendingOrder(orderId, "ITEM01", "   ");
    }

    /** 품목 키가 없는 행 — 스킵 대상. ORDER_ID 는 살아 있으므로 커서는 전진한다 */
    private static PendingOrder noItem(String orderId) {
        return new PendingOrder(orderId, null, "서울특별시 금천구");
    }

    // ── 대역 ───────────────────────────────────────────────────

    /**
     * 청크를 미리 쌓아 두고 순서대로 돌려주는 Sender 대역.
     *
     * <p>쌓아 둔 것이 소진되면 <b>빈 청크</b>를 돌려준다. 실제 DB 도 그렇게 동작하며,
     * 이 대역이 없으면 종료 조건 테스트가 대역의 예외로 끝나 버린다.
     */
    private static final class StubSender implements Sender<PollCursor, ShipmentSourceMessage> {

        private final List<Supplier<ShipmentSourceMessage>> chunks = new ArrayList<>();
        private final List<String> requestedCursors = new ArrayList<>();
        private int calls;

        StubSender chunk(PendingOrder... orders) {
            ShipmentSourceMessage message = new ShipmentSourceMessage(List.of(orders), CHUNK);
            chunks.add(() -> message);
            return this;
        }

        StubSender failure(RuntimeException e) {
            chunks.add(() -> {
                throw e;
            });
            return this;
        }

        int calls() {
            return calls;
        }

        List<String> requestedCursors() {
            return List.copyOf(requestedCursors);
        }

        @Override
        public InterfaceId ifId() {
            return InterfaceId.IF_SHP_001;
        }

        @Override
        public CanonicalMessage<ShipmentSourceMessage> receive(MessageHeader header, PollCursor cursor) {
            requestedCursors.add(cursor.fromBeginning() ? "-" : cursor.afterOrderId());
            int index = calls++;

            ShipmentSourceMessage payload = (index < chunks.size())
                    ? chunks.get(index).get()
                    : new ShipmentSourceMessage(List.of(), CHUNK);

            return new CanonicalMessage<>(header, payload);
        }
    }

    /**
     * 락 대역. 획득 여부와 <b>반납 횟수</b>를 관찰한다.
     *
     * <p>반납 횟수가 단언 대상인 이유는, 빠뜨렸을 때의 증상이 예외가 아니라
     * "TTL 만료까지 배치가 멈춘다" 라서 실행 중에는 아무 신호가 없기 때문이다.
     */
    private static final class StubLock implements DistributedLock {

        private final boolean grant;
        private final RuntimeException storeFailure;

        private String requestedKey;
        private Duration requestedTtl;
        private int released;

        private StubLock(boolean grant, RuntimeException storeFailure) {
            this.grant = grant;
            this.storeFailure = storeFailure;
        }

        static StubLock granting() {
            return new StubLock(true, null);
        }

        /** 다른 실행이 쥐고 있다 (정상 동작) */
        static StubLock busy() {
            return new StubLock(false, null);
        }

        /** 저장소에 닿지 못했다 (장애) */
        static StubLock broken(RuntimeException e) {
            return new StubLock(false, e);
        }

        int released() {
            return released;
        }

        String requestedKey() {
            return requestedKey;
        }

        Duration requestedTtl() {
            return requestedTtl;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            this.requestedKey = key;
            this.requestedTtl = ttl;

            if (storeFailure != null) {
                throw storeFailure;
            }
            if (!grant) {
                return Optional.empty();
            }
            return Optional.of(new LockHandle() {

                @Override
                public String key() {
                    return key;
                }

                @Override
                public void close() {
                    released++;
                }
            });
        }
    }

    private Receiver<ShipmentRecord> receiver() {
        return receiver(0, null);
    }

    /**
     * 호출된 사실만 적어 두는 수신처 대역.
     *
     * @param failAtCall 몇 번째 {@code prepare} 에서 실패할지 (1-based, 0 이면 실패하지 않는다)
     */
    private Receiver<ShipmentRecord> receiver(int failAtCall, RuntimeException failure) {
        return new Receiver<>() {

            private int calls;

            @Override
            public Step step() {
                return Step.RECEIVER_JDBC;
            }

            @Override
            public Delivery prepare(CanonicalMessage<List<ShipmentRecord>> message) {
                int count = message.payload().size();
                trace.add("PREPARE(" + count + ")");

                if (failure != null && ++calls == failAtCall) {
                    throw failure;
                }
                return new Delivery() {

                    @Override
                    public int count() {
                        return count;
                    }

                    @Override
                    public void commit() {
                        trace.add("COMMIT(" + count + ")");
                    }

                    @Override
                    public void compensate() {
                        trace.add("COMPENSATE(" + count + ")");
                    }
                };
            }
        };
    }
}
