package com.inspien.eai.common.id;

/**
 * 채번 카운터 키.
 *
 * <p>{@code ORDER_ID} 와 {@code SHIPMENT_ID} 는 <b>별개의 공간</b>이다. 서로 다른 테이블의
 * PK 이므로 값이 겹쳐도 무방하고, 하나의 카운터를 공유하면 26,000개를 둘이 나눠 쓰게 되어
 * 소진이 두 배로 빨라진다.
 *
 * <p>키를 문자열 상수로 흩어 두지 않고 열거형으로 고정한 것은, 오타 하나가
 * <b>0부터 다시 시작하는 새 카운터</b>를 조용히 만들어 내기 때문이다. Redis 는 없는 키에
 * {@code INCRBY} 하면 에러 대신 새로 만든다 — 실패가 아니라 중복으로 나타나는 종류의 사고다.
 */
public enum SequenceKey {

    /** IF-ORD-001 — ORDER_TB.ORDER_ID */
    ORDER("eai:seq:order"),

    /** IF-SHP-001 — SHIPMENT_TB.SHIPMENT_ID */
    SHIPMENT("eai:seq:shipment");

    private final String key;

    SequenceKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
