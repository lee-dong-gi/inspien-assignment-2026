package com.inspien.eai.common.id;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SequentialIdGenerator — 전량 선점 채번")
class SequentialIdGeneratorTest {

    private InMemoryIdSequence sequence;
    private SequentialIdGenerator generator;

    @BeforeEach
    void setUp() {
        sequence = new InMemoryIdSequence();
        generator = new SequentialIdGenerator(sequence, SequenceKey.ORDER);
    }

    @Test
    @DisplayName("요청한 개수만큼 연속된 식별자를 돌려준다")
    void allocatesContiguousBlock() {
        List<String> ids = generator.allocate(3);

        assertEquals(List.of("A000", "A001", "A002"), ids);
    }

    @Test
    @DisplayName("호출을 나눠도 앞의 구간을 다시 쓰지 않는다")
    void doesNotReuseAcrossCalls() {
        List<String> first = generator.allocate(2);
        List<String> second = generator.allocate(2);

        assertAll(
                () -> assertEquals(List.of("A000", "A001"), first),
                () -> assertEquals(List.of("A002", "A003"), second));
    }

    @Test
    @DisplayName("샘플 규모(63행)를 한 번의 선점으로 처리한다 — 왕복 1회")
    void allocatesSampleSizeInOneReservation() {
        List<String> ids = generator.allocate(63);

        assertAll(
                () -> assertEquals(63, ids.size()),
                () -> assertEquals(63, new HashSet<>(ids).size(), "중복이 없어야 한다"),
                () -> assertEquals("A000", ids.get(0)),
                () -> assertEquals("A062", ids.get(62)),
                () -> assertEquals(63, sequence.peek(SequenceKey.ORDER.key()),
                        "카운터가 63만 올라야 한다 (건별 호출이면 값은 같아도 왕복이 63회)"));
    }

    @Test
    @DisplayName("0건 요청은 번호를 태우지 않는다")
    void zeroCountConsumesNothing() {
        List<String> ids = generator.allocate(0);

        assertAll(
                () -> assertTrue(ids.isEmpty()),
                () -> assertEquals(0, sequence.peek(SequenceKey.ORDER.key())));
    }

    @Test
    @DisplayName("반환 리스트는 불변 — 호출부가 채번 결과를 바꿔치기할 수 없다")
    void returnsImmutableList() {
        List<String> ids = generator.allocate(1);

        assertThrows(UnsupportedOperationException.class, () -> ids.set(0, "Z999"));
    }

    @Test
    @DisplayName("음수 요청은 프로그래밍 오류로 즉시 거부")
    void rejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> generator.allocate(-1));
    }

    @Test
    @DisplayName("공간 전체보다 큰 요청은 카운터를 건드리기 전에 자른다")
    void rejectsOversizedRequestWithoutBurningIds() {
        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> generator.allocate(SerialIdCodec.CAPACITY + 1));

        assertAll(
                () -> assertEquals(EaiErrorCode.ID_SPACE_EXHAUSTED, e.errorCode()),
                () -> assertEquals(0, sequence.peek(SequenceKey.ORDER.key()),
                        "실패할 것을 아는 요청에 번호를 태우지 않는다"));
    }

    @Test
    @DisplayName("26,000개를 다 쓰면 EAI-4003 으로 정직하게 실패한다 (D-10)")
    void failsWhenSpaceExhausted() {
        generator.allocate(SerialIdCodec.CAPACITY);   // 전량 소진

        NonRetryableException e = assertThrows(NonRetryableException.class, () -> generator.allocate(1));

        assertAll(
                () -> assertEquals(EaiErrorCode.ID_SPACE_EXHAUSTED, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("EAI-4003")));
    }

    @Test
    @DisplayName("공간의 마지막 번호는 Z999")
    void lastIdIsZ999() {
        List<String> ids = generator.allocate(SerialIdCodec.CAPACITY);

        assertEquals("Z999", ids.get(ids.size() - 1));
    }

    @Test
    @DisplayName("ORDER 와 SHIPMENT 는 서로 다른 공간을 쓴다 — 하나가 다른 하나를 끌어내리지 않는다")
    void keysAreIndependent() {
        SequentialIdGenerator shipment = new SequentialIdGenerator(sequence, SequenceKey.SHIPMENT);

        generator.allocate(10);

        assertEquals(List.of("A000"), shipment.allocate(1));
    }

    @Test
    @DisplayName("적재된 마지막 값으로 카운터를 복원하면 그 다음부터 이어 채번한다")
    void seedsFromLastIssuedId() {
        generator.seedFrom("A113");

        assertEquals(List.of("A114"), generator.allocate(1),
                "Redis 가 비워져도 이미 적재된 데이터와 충돌하지 않아야 한다");
    }

    @Test
    @DisplayName("복원 값이 현재보다 낮으면 카운터를 되돌리지 않는다")
    void seedNeverRewindsCounter() {
        generator.allocate(500);          // 현재 500
        generator.seedFrom("A099");       // 100 → 되돌리면 중복이 난다

        assertEquals(List.of("A500"), generator.allocate(1));
    }

    @Test
    @DisplayName("복원 값이 비어 있으면(적재 이력 없음) 아무것도 하지 않는다")
    void seedIgnoresBlank() {
        generator.seedFrom(null);
        generator.seedFrom("  ");

        assertEquals(List.of("A000"), generator.allocate(1));
    }

    @Test
    @DisplayName("동시 요청에도 같은 번호가 두 번 나가지 않는다")
    void concurrentAllocationsNeverCollide() throws Exception {
        int threads = 16;
        int perThread = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> generator.allocate(perThread)));
            }

            Set<String> all = new HashSet<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(threads * perThread, all.size(), "중복 발급이 없어야 한다");
        } finally {
            pool.shutdownNow();
        }
    }
}
