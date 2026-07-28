package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.message.CanonicalMessage;

import java.util.List;

/**
 * 여러 Receiver 로의 전달을 <b>순서</b>를 지켜 조율한다.
 *
 * <p>구현체가 지켜야 할 계약 (설계 근거는 인터페이스 정의서 3.9):
 *
 * <ol>
 *   <li>등록된 순서대로 전부 {@link Receiver#prepare} — 하나라도 실패하면
 *       이미 준비된 것들을 <b>역순으로</b> {@link Delivery#compensate}</li>
 *   <li>등록된 순서대로 {@link Delivery#commit}</li>
 *   <li>확정 도중 실패하면 <b>아직 확정하지 않은</b> 것만 보상. 이미 확정된 것은 되돌리지 않는다</li>
 *   <li>첫 확정 이후의 실패는 {@code PARTIAL} 로 기록하고 수동 조치 대상으로 남긴다</li>
 * </ol>
 *
 * <p><b>역순 보상인 이유:</b> 나중에 준비된 것이 먼저 준비된 것에 의존할 수 있다.
 * 의존의 반대 방향으로 풀어야 중간 상태가 남지 않는다.
 *
 * <p><b>등록 순서가 곧 정책이다.</b> 시나리오 1에서는 JDBC 를 먼저, FTP 를 나중에 둔다.
 * 되돌리기 비용이 싼 쪽을 나중에 확정해야 보상 실패의 확률과 피해가 작아진다.
 * DB 롤백은 확실하지만 원격 FTP 파일 삭제는 네트워크에 달려 있기 때문이다.
 *
 * @param <T> 타깃 구조
 */
public interface DeliveryCoordinator<T> {

    /**
     * 전 Receiver 에 전달하고 확정한다.
     *
     * @return 확정된 건수
     * @throws com.inspien.eai.engine.exception.EaiException 전달 실패.
     *         이 예외가 던져졌을 때 <b>모든 대상 시스템이 원상 복구되었음</b>이 보장되거나,
     *         복구되지 못했다면 그 사실이 로그에 기록되어 있어야 한다
     */
    int deliver(CanonicalMessage<List<T>> message, List<Receiver<T>> receivers);
}
