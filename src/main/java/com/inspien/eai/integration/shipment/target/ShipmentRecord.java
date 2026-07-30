package com.inspien.eai.integration.shipment.target;

/**
 * IF-SHP-001 의 타깃 레코드 — {@code SHIPMENT_TB} 의 <b>1행</b>.
 *
 * <h2>이 레코드에 없는 것이 이 인터페이스의 핵심이다</h2>
 * <pre>
 *   ORDER_TB   : ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS
 *   ShipmentRecord :        APPLICANT_KEY,          ITEM_ID,       ADDRESS
 *                  + SHIPMENT_ID (자체 채번), ORDER_ID (원본 참조)
 * </pre>
 *
 * {@code NAME} · {@code ITEM_NAME} · {@code PRICE} · {@code STATUS} 는 <b>의도적으로 없다.</b>
 * 운송사는 물건을 옮기는 데 필요한 것만 받으면 되고, 그 이상을 넘기는 것은
 * 친절이 아니라 <b>불필요한 결합과 개인정보 확산</b>이다.
 *
 * <p>{@code PRICE} 가 특히 그렇다. 넘기면 운송사 시스템이 그 값에 의존하기 시작하고
 * (예: 보험 산정), 그 순간 쇼핑몰의 가격 정책 변경이 <b>운송사 시스템을 깨뜨리는</b>
 * 관계가 만들어진다. 시스템 사이의 결합은 이렇게 "일단 다 보내 두자" 에서 생긴다.
 *
 * <p>{@code STATUS} 를 넘기지 않는 이유는 다르다. 그 값은 <b>쇼핑몰 쪽의 전송 여부</b>이지
 * 배송 상태가 아니다. 넘기면 운송사가 자기 것으로 오해할 수 있는 값이고,
 * 애초에 우리가 방금 {@code 'Y'} 로 바꾸려는 값이다.
 *
 * <h2>{@code SHIPMENT_ID} 는 {@code ORDER_ID} 와 별개로 채번한다</h2>
 * 같은 값을 재사용하면 편하지만 그러면 안 된다. 두 테이블은 서로 다른 시스템의 것이고,
 * 식별자 체계도 각자의 것이다. 재사용하면 <b>운송사 PK 가 쇼핑몰 채번에 종속</b>되어,
 * 한 주문이 여러 배송으로 나뉘는 변경이 오는 순간 구조가 무너진다.
 * 원본과의 연결은 {@code ORDER_ID} 컬럼이 담당한다 — 그것이 참조가 하는 일이다.
 *
 * <h2>{@code CREATE_DATE} 가 없다</h2>
 * {@code DEFAULT SYSDATE} 이므로 INSERT 목록에서 빼면 DB 서버 시계로 찍힌다(정의서 4.3).
 * 등록 시각은 우리 데이터가 아니라 <b>수신 시스템의 기록</b>이다.
 *
 * <p><b>주의:</b> 이 컬럼명은 {@code ORDER_TB} 의 {@code CREATE_TIME} 과 <b>다르다.</b>
 * 두 테이블을 공통 코드로 뭉뚱그리면 틀리는 지점이며, 컬럼을 아예 다루지 않는
 * 이 설계에서는 그 실수가 발생할 자리조차 없다.
 *
 * @param shipmentId    자체 채번 {@code [A-Z][0-9]{3}}. {@code ORDER_ID} 와 별개의 공간
 * @param applicantKey  BOOT-000 산출물. 전 행 고정값이자 PK 두 번째 컬럼
 * @param orderId       원본 주문 참조. 후행 상태 갱신의 PK 조건으로도 쓰인다
 * @param itemId        원본 유지
 * @param address       배송지. <b>개인정보다.</b> 로그·예외 메시지에 값을 담지 않는다
 */
public record ShipmentRecord(
        String shipmentId,
        String applicantKey,
        String orderId,
        String itemId,
        String address
) {
}
