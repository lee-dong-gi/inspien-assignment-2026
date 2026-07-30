package com.inspien.eai.integration.shipment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * IF-SHP-001 배치 운영 파라미터 (정의서 4.5).
 *
 * <p>접속정보는 여기 없다 — BOOT-000 산출물({@code secrets/})에서 온다.
 * 이 레코드가 다루는 것은 <b>우리가 정하는 운영 값</b>뿐이다 (D-12 와 같은 기준).
 *
 * <h2>{@code fixedDelay} 를 여기에도 두는 이유</h2>
 * {@code @Scheduled(fixedDelayString = "${inspien.batch.shipment.fixed-delay}")} 는
 * 이 레코드를 거치지 않고 프로퍼티를 직접 읽는다. 그럼에도 필드로 둔 것은
 * <b>{@code lockTtl} 과의 관계를 검증하기 위해서</b>다. 두 값의 관계가 틀리면
 * 배치가 겹쳐 돌거나 반대로 오래 멈추는데, 그 증상은 5분 뒤에야 나타나고
 * 원인을 설정에서 찾기 어렵다. 기동할 때 끊는 편이 낫다.
 *
 * @param enabled         스케줄러 자동 실행 여부. <b>수동 트리거는 이 값과 무관</b>하게 동작한다.
 *                        시연 준비 중 상태를 고정해야 할 때 자동 실행만 끄는 용도다
 * @param fixedDelay      주기. {@code fixedDelay} 이므로 <b>이전 수행이 끝난 뒤부터</b> 센다.
 *                        {@code fixedRate} 는 수행이 주기보다 길어지면 실행이 밀려 쌓인다
 * @param initialDelay    기동 후 첫 실행까지의 대기. 0 으로 두면 채번 시딩·커넥션 풀 준비와
 *                        경합하며 기동 직후에 돈다. 기동 로그와 배치 로그가 섞여
 *                        <b>시연에서 무엇이 무엇인지 설명하기 어려워진다</b>
 * @param chunkSize       한 번의 조회로 끌어올 최대 행 수. 전체를 메모리에 올리지 않는다
 * @param maxChunksPerRun 한 실행이 처리할 최대 청크 수. <b>수행 시간의 상한</b>이며,
 *                        이 값이 있어야 {@code lockTtl} 을 넘기지 않는다고 말할 수 있다
 * @param lockKey         분산 락 키. 인터페이스마다 달라야 한다 — 하나를 공유하면
 *                        서로 무관한 배치가 서로를 막는다
 * @param lockTtl         락 만료 시간. 아래 논의 참조
 */
@ConfigurationProperties(prefix = "inspien.batch.shipment")
public record ShipmentBatchProperties(
        boolean enabled,
        Duration fixedDelay,
        Duration initialDelay,
        int chunkSize,
        int maxChunksPerRun,
        String lockKey,
        Duration lockTtl
) {

    private static final String DEFAULT_LOCK_KEY = "eai:lock:if-shp-001";

    public ShipmentBatchProperties {
        if (fixedDelay == null) {
            fixedDelay = Duration.ofMinutes(5);
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofMinutes(1);
        }
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        if (maxChunksPerRun <= 0) {
            maxChunksPerRun = 50;
        }
        if (lockKey == null || lockKey.isBlank()) {
            lockKey = DEFAULT_LOCK_KEY;
        }
        if (lockTtl == null) {
            lockTtl = Duration.ofMinutes(4);
        }

        if (lockTtl.isZero() || lockTtl.isNegative()) {
            // TTL 없는 락은 프로세스가 죽는 순간 영구 잠금이 된다.
            // 조용히 기본값으로 보정하지 않는다 — 설정 파일과 실제 동작이 달라지면
            // 장애 분석 때 설정을 믿을 수 없게 된다 (JdbcTargetProperties 와 같은 기준).
            throw new IllegalArgumentException(
                    "inspien.batch.shipment.lock-ttl 은 양수여야 한다: " + lockTtl);
        }
        if (lockTtl.compareTo(fixedDelay) >= 0) {
            throw new IllegalArgumentException("""
                    inspien.batch.shipment.lock-ttl(%s) 은 fixed-delay(%s) 보다 짧아야 합니다.

                    길게 두면 프로세스가 비정상 종료해 락을 반납하지 못했을 때,
                    남은 TTL 동안 배치가 한 주기 이상 통째로 멈춥니다.
                    짧게 두면 손실은 최대 한 주기로 제한되고, 수행이 TTL 을 넘긴 경우는
                    반납 시점의 토큰 불일치로 검출됩니다(RedisDistributedLock).
                    """.formatted(lockTtl, fixedDelay));
        }
    }
}
