package com.inspien.eai.integration.shipment;

/**
 * 배치를 무엇이 깨웠는가.
 *
 * <h2>왜 트리거를 타입으로 두는가</h2>
 * IF-ORD-001 의 트리거는 요청 본문({@code byte[]})이라 그 자체가 정보를 갖는다.
 * 반면 배치의 트리거는 <b>"지금 돌아라" 라는 신호뿐</b>이라서, 순진하게 짜면
 * {@code IntegrationFlow<Void>} 가 되고 {@code execute(null)} 을 부르게 된다.
 *
 * <p>그 {@code null} 자리에 실을 수 있는 유일하게 유용한 정보가 <b>누가 깨웠는가</b>다.
 * 실행 이력에 이 값이 남으면 운영자는 로그만 보고
 * "이 실행은 5분 주기가 돈 것" 과 "사람이 눌러서 돈 것" 을 구분할 수 있다.
 * 특히 시연 자리에서 수동 트리거와 자동 주기가 섞이면, 이 구분이 없으면
 * <b>어느 쪽이 데이터를 처리했는지 설명할 수 없다.</b>
 *
 * <h2>Sender 에게는 전달되지 않는다</h2>
 * 실행 단위({@code ShipmentIntegrationFlow})만 이 값을 안다. Sender 가 받는 것은
 * {@code PollCursor} 뿐이며, 조회 조건({@code APPLICANT_KEY} · {@code STATUS='N'})은
 * <b>이 인터페이스의 정의</b>이지 트리거가 정하는 것이 아니다.
 *
 * <p>경계를 이렇게 그으면 트리거를 하나 추가할 때(예: 운영 콘솔, 메시지 큐)
 * 데이터 접근 코드는 손대지 않는다. 반대로 트리거가 조회 조건까지 실어 오게 두면,
 * <b>호출자가 "다른 지원자의 주문을 조회" 하도록 만들 수 있는 문</b>이 열린다.
 */
public enum BatchTrigger {

    /** 스케줄러 5분 주기 */
    SCHEDULED,

    /** 운영·시연용 수동 호출 (POST /api/v1/shipments/batch) */
    MANUAL
}
