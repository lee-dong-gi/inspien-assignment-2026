package com.inspien.eai.integration.shipment.source;

import java.util.List;

/**
 * IF-SHP-001 의 소스 페이로드 — 폴링으로 끌어온 <b>청크 1개</b>.
 *
 * <p>IF-ORD-001 의 {@code OrderSourceMessage} 와 자리가 같지만 성격이 다르다.
 * 저쪽은 "송신 시스템이 보낸 메시지 1건" 이고, 이쪽은 <b>"우리가 스스로 잘라 온 한 덩어리"</b> 다.
 * 그 차이가 이 레코드에 {@code chunkSize} 가 들어 있는 이유다.
 *
 * <h2>{@code chunkSize} 를 페이로드가 들고 있는 이유</h2>
 * 파이프라인은 청크를 반복해야 하고 <b>언제 멈출지</b>를 판정해야 한다.
 * 그 판정의 근거는 "이번에 몇 건을 요청했고 몇 건이 왔는가" 인데,
 * 요청한 건수를 아는 것은 조회를 실행한 Sender 뿐이다.
 * 흐름이 설정을 다시 읽어 비교하게 두면, Sender 의 설정과 흐름의 설정이
 * <b>서로 다른 값일 수 있는 구조</b>가 된다.
 *
 * @param orders    조회된 행. 조회 순서({@code ORDER BY ORDER_ID})를 유지한다
 * @param chunkSize 이 조회에 요청한 최대 행 수
 */
public record ShipmentSourceMessage(
        List<PendingOrder> orders,
        int chunkSize
) {

    public ShipmentSourceMessage {
        orders = (orders == null) ? List.of() : List.copyOf(orders);
        if (chunkSize <= 0) {
            // 0 이면 조회가 0건을 돌려주고, 배치는 "할 일이 없다" 며 조용히 성공한다.
            // 설정 실수가 정상 동작으로 위장되는 대표적인 경로다.
            throw new IllegalArgumentException("청크 크기는 양수여야 한다: " + chunkSize);
        }
    }

    public int count() {
        return orders.size();
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    /**
     * 뒤에 더 남아 있을 가능성이 있는가.
     *
     * <p>요청한 만큼 꽉 찼다면 잘려서 온 것이므로 더 있을 수 있다. 덜 왔다면 그것이 전부다.
     * "더 있다" 를 확정하지 않고 "있을 수 있다" 로 두는 이유는, 정확히 청크 크기만큼
     * 남아 있던 경우를 구분할 방법이 없기 때문이다 — 그때는 한 번 더 조회해 0건을 받는다.
     * {@code COUNT(*)} 를 먼저 세어 왕복을 줄일 수도 있지만, 그러면 세는 시점과 읽는 시점
     * 사이의 변화를 다시 다뤄야 한다. 빈 조회 한 번이 더 싸다.
     *
     * <p><b>검증을 통과한 부분집합에 대고 물으면 안 된다.</b> 스킵된 건이 빠져 있어
     * "꽉 차지 않았다" 로 보이고, 아직 남은 청크를 처리하지 않고 멈춘다.
     * 반드시 <b>조회 직후의 원본</b>에 대고 판정한다.
     */
    public boolean mayHaveMore() {
        return orders.size() >= chunkSize;
    }

    /**
     * 다음 커서로 쓸, <b>조회된 마지막 행</b>의 {@code ORDER_ID}.
     *
     * <p>처리에 성공한 마지막 행이 아니라 <b>읽은 마지막 행</b>이다. 스킵된 행이 마지막이라면
     * 그 행의 값을 넘겨야 한다 — 그러지 않으면 다음 청크가 같은 행을 다시 읽는다
     * ({@link PollCursor} 참조).
     *
     * <p>그래서 이 메서드는 {@link #withOrders(List)} 로 걸러낸 결과가 아니라
     * <b>조회 직후의 원본</b>에 대고 불러야 한다. 걸러낸 쪽에 대고 부르면
     * 스킵된 꼬리만큼 커서가 덜 전진하고, 그만큼이 매 청크 다시 읽힌다.
     */
    public String lastReadOrderId() {
        return orders.isEmpty() ? null : orders.get(orders.size() - 1).orderId();
    }

    /**
     * 같은 청크 설정을 유지하면서 행 목록만 교체한다. Validator 가 걸러낸 결과를 담는 용도다.
     *
     * <p>{@code chunkSize} 를 승계하는 것은 이 값이 <b>이번 조회의 사실</b>이고
     * 걸러냄과 무관하기 때문이다.
     */
    public ShipmentSourceMessage withOrders(List<PendingOrder> filtered) {
        return new ShipmentSourceMessage(filtered, chunkSize);
    }
}
