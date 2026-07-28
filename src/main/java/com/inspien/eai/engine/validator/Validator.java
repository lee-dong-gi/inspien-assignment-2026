package com.inspien.eai.engine.validator;

import com.inspien.eai.engine.message.CanonicalMessage;

/**
 * Validator — 수신한 메시지가 <b>대상 시스템에 넣어도 되는 것인지</b> 판정한다.
 *
 * <p>Mapper 나 Receiver 안에서 검증하지 않고 단계를 분리한 이유는 두 가지다.
 *
 * <ol>
 *   <li><b>실패 시점을 앞당긴다.</b> 적재를 시작한 뒤 중간에 거부하면 롤백·보상 비용이 발생한다.
 *       Receiver 를 호출하기 전에 끊으면 되돌릴 것이 없다.</li>
 *   <li><b>판정 기준을 한 곳에 모은다.</b> 검증이 매퍼와 리시버에 흩어지면
 *       "이 값이 왜 거부됐는가" 를 추적할 수 없고, 규칙이 조용히 중복·모순된다.</li>
 * </ol>
 *
 * <p>검증 기준은 추정이 아니라 <b>대상 시스템의 실측 스펙</b>에서 온다.
 * 예를 들어 길이 검증(V-06)의 상한은 {@code VARCHAR2(100 BYTE)} 이고,
 * 이 DB 는 {@code NLS_LENGTH_SEMANTICS=BYTE} 이므로 문자 수가 아니라
 * <b>UTF-8 바이트 길이</b>로 재야 한다. 한글 1자는 3바이트다.
 *
 * @param <P> 검증 대상 소스 페이로드
 */
public interface Validator<P> {

    /**
     * 검증한다. 예외를 던지지 않고 <b>결과 객체로 돌려준다.</b>
     *
     * <p>부분 실패를 표현해야 하기 때문이다. 예외는 "전부 실패" 만 표현할 수 있어
     * "63건은 되고 11건은 안 된다" 를 담지 못한다.
     */
    ValidationResult<P> validate(CanonicalMessage<P> message);
}
