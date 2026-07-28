package com.inspien.eai.common.id;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 채번기 조립.
 *
 * <p>인터페이스별로 <b>별개의 빈</b>을 만든다. 하나를 공유하면 26,000개 공간을 둘이 나눠 쓰게 되고,
 * 주문 채번의 소진이 배송 채번을 함께 끌어내린다. 두 카운터는 서로를 모르는 편이 낫다.
 *
 * <p>빈 생성 시점에 Redis 에 연결하지 않는다(Lettuce 는 지연 연결). 덕분에
 * {@code bootstrapRun} · {@code probeRun} 같은 제어 평면 실행은 Redis 없이도 그대로 뜬다.
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public IdSequence idSequence(StringRedisTemplate redisTemplate) {
        return new RedisIdSequence(redisTemplate);
    }

    /** IF-ORD-001 — ORDER_TB.ORDER_ID */
    @Bean
    public SequentialIdGenerator orderIdGenerator(IdSequence idSequence) {
        return new SequentialIdGenerator(idSequence, SequenceKey.ORDER);
    }

    /** IF-SHP-001 — SHIPMENT_TB.SHIPMENT_ID */
    @Bean
    public SequentialIdGenerator shipmentIdGenerator(IdSequence idSequence) {
        return new SequentialIdGenerator(idSequence, SequenceKey.SHIPMENT);
    }
}
