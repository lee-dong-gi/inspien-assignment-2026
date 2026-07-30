package com.inspien.eai.integration.order.target;

/**
 * {@code ORDER_TB.STATUS} 의 값 체계.
 *
 * <h2>왜 리터럴 {@code "N"} / {@code "Y"} 를 흩어 두지 않는가</h2>
 * 이 컬럼은 <b>두 인터페이스가 공유하는 유일한 상태</b>다.
 *
 * <pre>
 *   IF-ORD-001 (적재)  : STATUS = 'N' 으로 넣는다
 *   IF-SHP-001 (조회)  : WHERE STATUS = 'N' 으로 찾는다
 *   IF-SHP-001 (갱신)  : STATUS = 'Y' 로 바꾼다
 * </pre>
 *
 * 세 자리가 각자 리터럴을 들고 있으면, 한쪽만 바뀌었을 때 <b>실패가 예외로 나타나지 않는다.</b>
 * 조회 조건이 어긋나면 배치는 조용히 0건을 처리하고 성공을 보고하며, 주문은 영원히
 * 운송사로 전달되지 않는다. 컴파일도 테스트도 통과하고, 발견은 "왜 배송이 안 됐지" 다.
 *
 * <h2>왜 {@code order} 패키지에 두는가</h2>
 * 이 값의 <b>의미를 정하는 쪽</b>이 IF-ORD-001 이기 때문이다. 적재하는 쪽이 어휘를 정의하고
 * 배치는 그것을 읽어 쓴다. 그래서 {@code integration.shipment} → {@code integration.order.target}
 * 방향의 의존이 하나 생기는데, 이는 <b>같은 테이블을 두 인터페이스가 공유한다는 사실</b>의
 * 정직한 표현이다. 공통 패키지로 끌어올리면 "아무도 소유하지 않는 값" 이 되고,
 * 값의 의미가 바뀔 때 누구에게 물어야 하는지가 사라진다.
 *
 * <p>연계 엔진이 도메인을 소유하지 않는다는 원칙과 충돌하지 않는다. 이 열거형은
 * <b>대상 시스템이 이미 쓰고 있는 값</b>을 옮겨 적은 것이고(과제 PDF p.5·p.6),
 * 우리가 새 상태를 만들어 넣는 것이 아니다. 실제로 {@code 'E'}(오류) 같은 값을
 * 추가하지 않는 이유도 그것이다 — 스키마의 어휘는 우리 것이 아니다.
 */
public enum OrderStatus {

    /** 미전송. 배치의 조회 대상이다 */
    UNSENT("N"),

    /** 운송사 전송 완료. 배치가 적재에 성공한 건에만 부여된다 */
    SENT("Y");

    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    /** DB 에 실제로 저장되는 문자열 */
    public String code() {
        return code;
    }
}
