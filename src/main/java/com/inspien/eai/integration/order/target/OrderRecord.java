package com.inspien.eai.integration.order.target;

/**
 * IF-ORD-001 의 타깃 레코드 — 평탄화가 끝난 주문 <b>1행</b>.
 *
 * <p>이 타입 하나가 두 수신처를 동시에 만족시킨다. {@code ORDER_TB} 의 한 행이자
 * 영수증 파일의 한 라인이며, 같은 인스턴스에서 나오므로 {@code ORDER_ID} 가 어긋날 수 없다.
 * 수신처마다 별도 DTO 를 두면 그 사이 어딘가에서 값이 갈라질 여지가 생긴다.
 *
 * <p><b>필드 순서를 그대로 join 하면 안 된다.</b> 선언 순서는 {@code ORDER_TB} 의 컬럼 순서지만
 * 영수증 파일의 필드 순서는 다르다.
 *
 * <pre>
 *   DB   : ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS
 *   파일 : ORDER_ID, USER_ID, ITEM_ID, APPLICANT_KEY, NAME, ADDRESS, ITEM_NAME, PRICE
 *                    └── 2·3·4번이 다르고, STATUS 는 아예 없다 (9필드 vs 8필드)
 * </pre>
 *
 * 라인 조립은 FTP Receiver 의 책임이며, 그곳에서 필드를 <b>이름으로</b> 지목해 꺼낸다.
 * 이 레코드는 값을 담기만 하고 어떤 표현으로 나갈지 알지 않는다.
 *
 * @param price  문자열이다. 여기서 숫자로 바꾸지 않는다 — 대상 컬럼이
 *               {@code VARCHAR2} 이고, 변환하는 순간 원본과 다른 값이 적재될 수 있다
 * @param status 적재 시점에는 항상 미전송({@code N}). 배치가 전송에 성공하면 {@code Y} 로 갱신된다
 */
public record OrderRecord(
        String orderId,
        String applicantKey,
        String userId,
        String itemId,
        String name,
        String address,
        String itemName,
        String price,
        String status
) {
}
