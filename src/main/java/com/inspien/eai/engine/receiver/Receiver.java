package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;

import java.util.List;

/**
 * Receiver — 수신 시스템 어댑터.
 *
 * <p>변환이 끝난 표준 메시지를 대상 시스템의 프로토콜로 실어 나른다.
 * Sender 와 대칭이며, 마찬가지로 <b>변환하지 않는다.</b> 여기서 값을 손보기 시작하면
 * "DB 에 들어간 값과 파일에 쓰인 값이 다른" 상황이 만들어진다.
 *
 * <p>구현체는 프로토콜별로 나뉜다 — {@code JdbcReceiver}, {@code FtpReceiver}.
 * 수신처가 하나 더 늘어도 이 인터페이스를 구현하기만 하면 되고, Sender/Mapper 는 손대지 않는다.
 *
 * <p><b>메서드가 {@code deliver} 가 아니라 {@code prepare} 인 것이 핵심이다.</b>
 * 즉시 확정하지 않고 되돌릴 수 있는 상태의 {@link Delivery} 를 돌려준다.
 * 이유는 {@link Delivery} 의 설명 참조 — 이기종 리소스 간 정합성을 맞추는 유일한 현실적 방법이다.
 *
 * @param <T> 타깃 구조
 */
public interface Receiver<T> {

    /** 로그·모니터링에서 이 Receiver 를 식별하는 구간 이름 */
    Step step();

    /**
     * 확정 직전까지 진행한다.
     *
     * <p>이 호출이 정상 반환됐다는 것은 "지금 commit 하면 성공한다" 는 뜻이어야 한다.
     * 확정 단계에 검증 여지를 남기면 보상 순서 설계가 무의미해진다.
     */
    Delivery prepare(CanonicalMessage<List<T>> message);
}
