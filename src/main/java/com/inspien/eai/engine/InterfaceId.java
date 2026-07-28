package com.inspien.eai.engine;

/**
 * 인터페이스 식별자.
 *
 * <p>연계 시스템에서 "무엇이 어디로 흐르는가"의 단위는 클래스나 메서드가 아니라
 * <b>인터페이스</b>다. 운영자는 {@code IF-ORD-001} 이 실패했다고 말하지
 * {@code OrderService.create()} 가 실패했다고 말하지 않는다.
 * 로그·응답·모니터링의 기준 축이므로 문자열이 아니라 타입으로 고정한다.
 */
public enum InterfaceId {

    IF_ORD_001("IF-ORD-001", "주문 생성 연계", Kind.REALTIME),
    IF_SHP_001("IF-SHP-001", "운송사 전송 배치", Kind.BATCH);

    private final String code;
    private final String description;
    private final Kind kind;

    InterfaceId(String code, String description, Kind kind) {
        this.code = code;
        this.description = description;
        this.kind = kind;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 처리 성격.
     *
     * <p>실시간과 배치는 <b>실패의 의미가 다르다.</b> 실시간은 호출자가 결과를 기다리므로
     * 부분 실패도 즉시 응답에 드러나야 하고, 배치는 호출자가 없으므로 실패 건을 남겨
     * 다음 주기에 자연 재처리시킨다. 파이프라인 구조는 공유하되 이 차이는 구분한다.
     */
    public enum Kind {
        /** 요청 기반 동기 처리 */
        REALTIME,
        /** 폴링 기반 비동기 처리 */
        BATCH
    }
}
