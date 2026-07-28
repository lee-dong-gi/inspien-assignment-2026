package com.inspien.eai.engine.flow;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.ProcessResult;

/**
 * 인터페이스 1건의 실행 단위 — 파이프라인 조립체.
 *
 * <pre>
 *   Sender → Validator → Mapper → Receiver(s)
 * </pre>
 *
 * <p>시나리오 1과 시나리오 2는 이 계약을 <b>공유</b>한다. 트리거(REST 요청 / 스케줄러)와
 * 구성 부품만 다르고, 흐름의 골격은 같다. 과제가 요구하는 것이 기능 두 벌이 아니라
 * <b>연계 구조 하나</b>라는 판단에서 나온 설계다.
 *
 * <p>구현체의 책임:
 * <ul>
 *   <li>{@code txId} 를 MDC 에 심어 전 구간 로그에 전파 — 코드를 오염시키지 않고 추적성을 얻는다</li>
 *   <li>구간마다 {@link com.inspien.eai.engine.log.InterfaceLogger} 로 시작·종료·소요 기록</li>
 *   <li>검증 결과가 거부면 Receiver 를 호출하지 않고 즉시 종료</li>
 *   <li>스킵 건수를 결과에 반영 — 스킵이 있으면 {@code SUCCESS} 가 아니라 {@code PARTIAL}</li>
 *   <li>예외를 {@link ProcessResult#fail} 로 변환. 파이프라인 밖으로 원시 예외를 흘리지 않는다</li>
 * </ul>
 *
 * @param <I> 트리거 입력
 */
public interface IntegrationFlow<I> {

    InterfaceId ifId();

    /**
     * 인터페이스를 1회 실행한다.
     *
     * <p><b>예외를 던지지 않고 결과를 반환한다.</b> 연계에서 실패는 예외 상황이 아니라
     * 정상적으로 보고해야 하는 결과다. 호출자(REST 컨트롤러 / 스케줄러)는
     * 성공·부분성공·실패를 같은 방식으로 다룰 수 있어야 한다.
     */
    ProcessResult execute(I trigger);
}
