package com.inspien.eai.common.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * 프로세스 밖의 배타 락 — 배치가 겹쳐 도는 것을 막는다.
 *
 * <h2>왜 필요한가 (정의서 4.5)</h2>
 * IF-SHP-001 은 5분 주기다. 그런데 한 번 수행이 5분을 넘기면 <b>다음 주기가 겹쳐 시작된다.</b>
 * {@code @Scheduled(fixedDelay)} 는 같은 스레드에서의 겹침만 막아 주고,
 * 인스턴스가 둘이거나 수동 트리거가 함께 들어오는 상황은 막아 주지 않는다.
 *
 * <p>겹쳐 돌면 두 실행이 <b>같은 {@code STATUS='N'} 행을 동시에 읽는다.</b> 결과는
 * SHIPMENT_TB 에 같은 주문이 두 번 적재되는 것이고, 이 환경은 append-only 이므로
 * <b>지울 수 없다.</b> 예외로 드러나지도 않는다 — SHIPMENT_ID 는 각자 다르게 채번되므로
 * PK 위반조차 나지 않고, 운송사는 같은 물건을 두 번 배송한다.
 *
 * <h2>왜 애플리케이션 안의 락이 아닌가</h2>
 * {@code synchronized} 나 {@code ReentrantLock} 은 <b>한 JVM 안에서만</b> 배타적이다.
 * 채번({@code RedisIdSequence})을 프로세스 밖에 둔 것과 정확히 같은 이유로,
 * "동시에 하나만 돈다" 는 보장도 프로세스 밖에 있어야 한다.
 *
 * <h2>Redis 를 쓰는 값</h2>
 * 이미 채번을 위해 들어와 있는 인프라다. 락을 위해 새 의존을 들이지 않았고,
 * 반대로 락을 위해 DB 에 테이블을 만들지도 않았다 — 대상 스키마는 불변 조건이다.
 */
public interface DistributedLock {

    /**
     * 락을 시도한다. <b>기다리지 않는다.</b>
     *
     * <p>대기하지 않는 이유는 배치의 성격이다. 이미 누가 돌고 있다면 이번 주기는
     * 할 일이 없고, 기다려 봐야 <b>다음 주기와 겹칠 시점에 시작</b>하게 된다.
     * 지금 건너뛰고 5분 뒤에 다시 오는 것이 옳다.
     *
     * @param ttl 만료 시간. 프로세스가 죽어 반납하지 못한 락을 영원히 남기지 않기 위해 필수다.
     *            <b>배치 최대 수행시간보다 길게</b> 잡아야 하며, 짧으면 수행 중에 소유권을 잃는다
     * @return 획득했으면 핸들, 이미 누가 쥐고 있으면 {@link Optional#empty()}.
     *         <b>저장소에 닿지 못한 경우는 empty 가 아니라 예외다</b> —
     *         "누가 쥐고 있다"(정상)와 "확인할 수 없다"(장애)는 조치가 정반대다
     * @throws com.inspien.eai.engine.exception.EaiException 락 저장소 접근 실패
     *         ({@code EAI-4006}). 락 없이 진행하는 선택지는 두지 않는다
     */
    Optional<LockHandle> tryAcquire(String key, Duration ttl);
}
