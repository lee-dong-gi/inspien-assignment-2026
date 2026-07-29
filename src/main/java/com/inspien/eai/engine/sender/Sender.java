package com.inspien.eai.engine.sender;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;

/**
 * Sender — 송신 시스템 어댑터.
 *
 * <p>이름이 헷갈리기 쉬운데, EAI 에서 Sender 는 "엔진이 무언가를 보낸다" 는 뜻이 아니라
 * <b>송신 시스템 쪽에 붙는 어댑터</b>를 말한다. 외부의 무언가를 받아 표준 메시지로 감싸는 것이
 * 유일한 책임이다. 검증도, 변환도, 적재도 하지 않는다.
 *
 * <p><b>이 인터페이스의 존재 이유가 곧 과제의 핵심이다.</b> 시나리오 1(실시간 REST)과
 * 시나리오 2(5분 주기 배치)는 트리거도 프로토콜도 다르지만, 그 차이는 전부 이 구현체 안에서 끝난다.
 * {@code RestSender} 를 {@code JdbcPollingSender} 로 바꿔 끼우면 이후 파이프라인
 * (Validator → Mapper → Receiver)은 <b>한 줄도 바뀌지 않는다.</b>
 *
 * @param <I> 트리거 입력. REST 는 원본 XML 문자열, 배치는 조회 조건
 * @param <P> 송신 시스템의 구조를 그대로 담은 소스 페이로드
 */
public interface Sender<I, P> {

    InterfaceId ifId();

    /**
     * 송신 시스템으로부터 메시지를 수신해 표준 메시지로 감싼다.
     *
     * <p>여기서 페이로드를 "예쁘게" 만들지 않는다. 소스 구조를 왜곡 없이 옮기는 것이 원칙이다.
     * 정제는 Validator 와 Mapper 의 몫이며, 단계를 섞으면 어디서 값이 바뀌었는지 추적할 수 없다.
     *
     * <p><b>헤더를 받아서 쓴다 — 직접 발급하지 않는다 (D-16).</b>
     * {@code txId} 는 <b>실행 단위의 식별자</b>이지 메시지의 속성이 아니다.
     * 이 메서드가 헤더를 만들면, 파싱이 실패했을 때 <b>추적 ID 자체가 존재하지 않아</b>
     * 실행 이력을 남길 수도, 응답에 {@code txId} 를 실을 수도 없다.
     * 추적하려고 만든 장치가 정작 첫 구간의 실패를 못 추적하는 상황이 된다.
     *
     * @param header 실행 단위({@code IntegrationFlow})가 발급한 추적 정보. 그대로 실어 나른다
     */
    CanonicalMessage<P> receive(MessageHeader header, I trigger);
}
