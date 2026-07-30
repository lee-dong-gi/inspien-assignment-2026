package com.inspien.eai.common.lock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 분산 락 조립.
 *
 * <p>{@code IdGeneratorConfig} 와 나란히 둔다. 둘 다 <b>"프로세스 밖에 있어야 하는 상태"</b> 를
 * Redis 에 두는 부품이고, 성격이 같으므로 같은 층에 있는 것이 읽기 좋다.
 *
 * <p>인터페이스별 조건을 걸지 않았다. Lettuce 는 지연 연결이라 빈 생성 시점에 Redis 로
 * 접속하지 않으므로, 이 빈이 있다고 제어 평면 실행({@code bootstrapRun} · {@code probeRun})이
 * Redis 를 요구하지는 않는다. 실제로 접속이 필요해지는 시점은 락을 시도할 때뿐이며,
 * 그 시점은 배치 스케줄러가 살아 있는 데이터 평면에만 존재한다.
 */
@Configuration
public class DistributedLockConfig {

    @Bean
    public DistributedLock distributedLock(StringRedisTemplate redisTemplate) {
        return new RedisDistributedLock(redisTemplate);
    }
}
