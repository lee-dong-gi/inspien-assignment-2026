package com.inspien.eai.engine.message;

/**
 * 표준 메시지 — 연계 엔진이 다루는 유일한 운반체.
 *
 * <p><b>이 타입이 이 프로젝트의 핵심 전제다.</b> 송신 시스템의 형식(EUC-KR XML)도,
 * 수신 시스템의 형식(Oracle 행 / 구분자 텍스트 라인)도 아닌 <b>중립 표현</b>을 가운데 둔다.
 *
 * <p>중립 표현이 없으면 송신 N개 × 수신 M개의 변환 조합이 필요하지만,
 * 가운데를 두면 N + M 으로 줄어든다. 사전 안내의 "ERP 는 통합, EAI 는 연결" 이라는 구도가
 * 코드 레벨에서 의미하는 바가 바로 이것이다 — 시스템을 하나로 합치는 대신
 * <b>서로를 모르게 두고 가운데에서 번역</b>한다.
 *
 * <p>실무적 귀결: 송신 시스템이 XML 에서 JSON 으로 바뀌어도 Mapper 이후는 손대지 않는다.
 * 수신처가 하나 늘어도 Receiver 만 추가하면 된다.
 *
 * @param header  추적 정보. 파이프라인 전 구간에서 동일 인스턴스가 전파된다
 * @param payload 단계별 표현. Sender 직후에는 소스 구조, Mapper 이후에는 타깃 구조를 담는다
 */
public record CanonicalMessage<P>(MessageHeader header, P payload) {

    public CanonicalMessage {
        if (header == null) {
            throw new IllegalArgumentException("header 없는 메시지는 추적할 수 없다.");
        }
    }

    /**
     * 페이로드를 교체하되 <b>헤더는 그대로 승계</b>한다.
     *
     * <p>Mapper 가 소스 구조를 타깃 구조로 바꿀 때 쓴다. 여기서 헤더를 새로 만들면
     * 변환 전후가 다른 트랜잭션으로 기록되어 추적이 끊긴다.
     */
    public <T> CanonicalMessage<T> withPayload(T next) {
        return new CanonicalMessage<>(header, next);
    }

    public String txId() {
        return header.txId();
    }
}
