package com.inspien.eai.integration.shipment.schedule;

import com.inspien.eai.integration.shipment.ShipmentBatchProperties;
import com.inspien.eai.integration.shipment.ShipmentIntegrationFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 배치 자동 실행 조립 — <b>이 설정만 조건이 다르다.</b>
 *
 * <h2>왜 파이프라인 조립과 분리했는가</h2>
 * {@code inspien.batch.shipment.enabled=false} 로 <b>자동 주기만</b> 끄고
 * 수동 트리거는 살려 두어야 하기 때문이다. 파이프라인 빈과 같은 설정에 두면
 * 스위치 하나가 인터페이스를 통째로 없애 버린다.
 *
 * <p>이 구분이 실제로 필요한 상황이 있다. 대상 환경은 append-only 이고
 * ({@code DELETE} 권한 없음 — 정의서 B10) 지원자들이 테이블을 공유하므로,
 * <b>적재는 되돌릴 수 없다.</b> 시연 직전에는 상태를 고정해 두고 원하는 순간에
 * 한 번만 돌리는 것이 안전하다. 그때 필요한 것이 "자동은 끄고 수동은 켠" 구성이다.
 *
 * <h2>조건이 둘이다</h2>
 * {@code inspien.jdbc.enabled} 도 함께 본다. 제어 평면 실행({@code bootstrapRun} ·
 * {@code probeRun})에는 파이프라인 빈이 없으므로, 조건이 없으면 주입 대상이 없어 기동이 깨진다.
 * {@code @ConditionalOnProperty} 의 {@code name} 에 여럿을 주면 <b>전부</b> 만족해야 한다.
 *
 * <h2>스레드 풀을 1로 둔다</h2>
 * 인스턴스 안에서는 이것만으로 겹침이 불가능해진다 — {@code fixedDelay} + 단일 스레드면
 * 이전 수행이 끝나기 전에 다음이 시작되지 않는다.
 *
 * <p>그러면 분산 락은 왜 필요한가. 두 가지 경우가 남는다.
 * <ol>
 *   <li><b>인스턴스가 둘 이상</b>일 때 (스케일아웃 · 배포 중 겹침)</li>
 *   <li><b>수동 트리거가 자동 주기와 겹칠</b> 때 — 수동 호출은 웹 요청 스레드에서 돌아
 *       스케줄러 스레드와 무관하다. 시연 자리에서 실제로 일어날 수 있는 조합이다</li>
 * </ol>
 * 즉 스레드 풀 1은 락을 대신하지 못하고, 락은 스레드 풀 1을 불필요하게 만들지 않는다.
 * 앞의 것은 <b>대기 없는 직렬화</b>이고 뒤의 것은 <b>겹침 검출</b>이다.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = {"inspien.jdbc.enabled", "inspien.batch.shipment.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class ShipmentScheduleConfig {

    @Bean
    public ShipmentBatchScheduler shipmentBatchScheduler(ShipmentIntegrationFlow flow,
                                                        ShipmentBatchProperties properties) {
        log.info("[IF-SHP-001] 배치 스케줄러 활성화 — 주기 {}, 최초 지연 {}, 청크 {}행 × 최대 {}회, 락 TTL {}",
                properties.fixedDelay(), properties.initialDelay(),
                properties.chunkSize(), properties.maxChunksPerRun(), properties.lockTtl());

        return new ShipmentBatchScheduler(flow);
    }

    /**
     * 배치 전용 스케줄러 스레드.
     *
     * <p>직접 등록하는 이유는 <b>스레드 이름</b>이다. 기본값은 {@code scheduling-1} 이라
     * 로그를 볼 때 그 줄이 배치에서 나온 것인지 알 수 없다. {@code eai-batch-1} 이면
     * 시연 중에 {@code tail} 로 배치 동작을 보여 주기가 쉬워진다 —
     * 5분 주기가 실제로 돈다는 것을 보이는 것이 제출 요구사항이다.
     *
     * <p>풀 크기 1은 의도다. 위 논의 참조 — 인스턴스 내부 겹침을 구조적으로 없앤다.
     * {@code awaitTermination} 을 켜 두는 것은 종료 중에 배치가 잘리는 것을 막기 위해서다.
     * 트랜잭션 중간에 프로세스가 사라지면 커밋되지 않은 트랜잭션은 롤백되지만,
     * <b>락은 TTL 이 만료될 때까지 남는다.</b>
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("eai-batch-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
