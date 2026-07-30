package com.inspien.eai.integration.shipment.source;

/**
 * IF-SHP-001 의 소스 레코드 — {@code ORDER_TB} 에서 읽은 미전송 주문 <b>1행</b>.
 *
 * <h2>세 컬럼만 읽는다</h2>
 * {@code ORDER_TB} 에는 컬럼이 10개 있지만 조회하는 것은 셋뿐이다
 * ({@code ORDER_ID}, {@code ITEM_ID}, {@code ADDRESS}).
 *
 * <p>이것이 이 인터페이스에서 가장 먼저 드러나는 EAI 의 태도다 —
 * <b>필드 축소는 Mapper 에서 시작되지 않고 Sender 에서 시작된다.</b>
 * 전 컬럼을 읽어 온 뒤 Mapper 가 버리는 구조로 짜면, {@code NAME}·{@code ADDRESS} 같은
 * 개인정보가 <b>필요 없는데도 애플리케이션 메모리와 스택트레이스를 통과</b>한다.
 * 필요 없는 것은 애초에 가져오지 않는다.
 *
 * <h2>{@code APPLICANT_KEY} 가 없는 이유</h2>
 * 조회 <b>조건</b>이지 조회 <b>결과</b>가 아니다. 전 행이 같은 값이므로 행마다 실어 나르는 것은
 * 낭비이고, 더 중요하게는 <b>어디서 온 값인지가 흐려진다.</b> 적재할 때 쓰는
 * {@code APPLICANT_KEY} 는 BOOT-000 산출물이 진실의 원천이며, Mapper 가 그것을 주입한다.
 * DB 에서 읽어 온 값을 다시 DB 에 넣는 경로를 만들면, 어느 쪽이 기준인지 알 수 없게 된다.
 *
 * <h2>{@code STATUS} 도 담지 않는다</h2>
 * 조회 조건이 {@code STATUS='N'} 이므로 <b>읽은 모든 행의 값이 이미 정해져 있다.</b>
 * 담아 두면 "혹시 다른 값일 수도 있나" 를 코드가 되묻게 되고, 그 물음은 답이 없다.
 *
 * @param orderId 원본 주문 식별자. {@code SHIPMENT_TB.ORDER_ID} 로 그대로 옮겨지며,
 *                동시에 후행 상태 갱신의 PK 조건이 된다
 * @param itemId  품목 식별자. 원본 유지
 * @param address 배송지. <b>개인정보다.</b> 로그·예외 메시지에 값을 담지 않는다
 */
public record PendingOrder(
        String orderId,
        String itemId,
        String address
) {
}
