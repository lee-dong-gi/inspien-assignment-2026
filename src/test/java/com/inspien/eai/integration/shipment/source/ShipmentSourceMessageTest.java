package com.inspien.eai.integration.shipment.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 청크 경계 판정.
 *
 * <p>사소해 보이는 세 메서드({@code mayHaveMore} · {@code lastReadOrderId} · {@code withOrders})가
 * 배치의 <b>종료 조건과 커서 전진</b>을 전부 결정한다. 여기가 틀리면 증상은
 * 무한 루프이거나 <b>남은 청크를 조용히 놓치는 것</b>이라, 어느 쪽도 예외로 드러나지 않는다.
 */
@DisplayName("ShipmentSourceMessage — 청크 경계와 커서 근거")
class ShipmentSourceMessageTest {

    private static final int CHUNK = 3;

    @Test
    @DisplayName("요청한 만큼 꽉 찼으면 뒤에 더 있을 수 있다 — 잘려서 온 것이다")
    void fullChunkMayHaveMore() {
        assertTrue(chunk(CHUNK).mayHaveMore());
    }

    @Test
    @DisplayName("덜 왔으면 그것이 전부다 — 여기서 멈추지 않으면 빈 조회를 한 번 더 한다")
    void partialChunkIsTheEnd() {
        assertAll(
                () -> assertFalse(chunk(CHUNK - 1).mayHaveMore()),
                () -> assertFalse(chunk(0).mayHaveMore()),
                () -> assertTrue(chunk(0).isEmpty()));
    }

    @Test
    @DisplayName("커서 근거는 '읽은 마지막 행' 이다 — 처리에 성공한 마지막 행이 아니다")
    void lastReadOrderIdIsTheLastRowRegardlessOfProcessing() {
        ShipmentSourceMessage source = new ShipmentSourceMessage(List.of(
                new PendingOrder("A001", "ITEM01", "서울특별시 금천구"),
                new PendingOrder("A002", "ITEM02", "서울특별시 금천구"),
                // 스킵될 행이 꼬리에 있다. 이 값을 넘기지 않으면 다음 청크가 같은 행을 또 읽는다.
                new PendingOrder("A003", "ITEM03", null)), CHUNK);

        assertEquals("A003", source.lastReadOrderId());
    }

    @Test
    @DisplayName("0건이면 커서를 전진시킬 근거가 없다 — 그때는 반복이 끝난 자리다")
    void emptyChunkHasNoCursor() {
        assertNull(chunk(0).lastReadOrderId());
    }

    @Test
    @DisplayName("걸러낸 목록으로 교체해도 chunkSize 는 이번 조회의 사실로 남는다")
    void withOrdersKeepsChunkSize() {
        ShipmentSourceMessage source = chunk(CHUNK);

        ShipmentSourceMessage filtered = source.withOrders(source.orders().subList(0, 1));

        assertAll(
                () -> assertEquals(CHUNK, filtered.chunkSize()),
                () -> assertEquals(1, filtered.count()),
                // 걸러낸 쪽에 대고 물으면 '덜 찼다' 로 보여 아직 남은 청크를 놓친다.
                () -> assertFalse(filtered.mayHaveMore()),
                () -> assertTrue(source.mayHaveMore(), "원본은 그대로다"));
    }

    @Test
    @DisplayName("청크 크기가 0 이하면 조립 시점에 거부한다 — 설정 실수가 '할 일 없음' 으로 위장된다")
    void rejectsNonPositiveChunkSize() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ShipmentSourceMessage(List.of(), 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ShipmentSourceMessage(List.of(), -1)));
    }

    @Test
    @DisplayName("행 목록이 null 이어도 빈 목록으로 다룬다 — 이후 단계가 null 검사를 하지 않아도 된다")
    void treatsNullOrdersAsEmpty() {
        ShipmentSourceMessage source = new ShipmentSourceMessage(null, CHUNK);

        assertAll(
                () -> assertTrue(source.isEmpty()),
                () -> assertEquals(0, source.count()));
    }

    private static ShipmentSourceMessage chunk(int rows) {
        List<PendingOrder> orders = new java.util.ArrayList<>(rows);
        for (int i = 1; i <= rows; i++) {
            orders.add(new PendingOrder("A%03d".formatted(i), "ITEM%02d".formatted(i), "서울특별시 금천구"));
        }
        return new ShipmentSourceMessage(orders, CHUNK);
    }
}
