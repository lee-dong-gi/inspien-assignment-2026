package com.inspien.eai.common.id;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 기반 카운터.
 *
 * <p><b>왜 애플리케이션 메모리가 아닌가.</b> {@code AtomicLong} 은 프로세스 안에서만 원자적이다.
 * 재기동하면 0부터 다시 시작해 이미 적재된 번호와 정면충돌하고, 인스턴스가 둘이면
 * 같은 번호를 동시에 발급한다. 채번은 프로세스 밖에 있어야 하는 상태다.
 *
 * <p><b>왜 DB 시퀀스가 아닌가 (D-09).</b> 대상 스키마는 이 과제의 불변 조건이다.
 * 시퀀스 객체나 채번 테이블을 새로 만드는 것은 "우리 편의를 위해 남의 시스템을 고치는" 일이고,
 * 그것을 하지 않는 것이 EAI 의 존재 이유다. Redis 는 우리가 소유한 인프라이므로 이 제약이 없다.
 *
 * <p><b>Redis 가 죽으면 어떻게 하는가.</b> 실패시킨다. 대체 채번기로 자동 전환하지 않는다 —
 * 두 채번기가 동시에 살아 있는 순간이 곧 중복 발급이다. 일시 단절은 재시도 가능
 * ({@link EaiErrorCode#ID_ISSUE_FAILED})으로 분류하고, 회복은 재시도에 맡긴다.
 */
public class RedisIdSequence implements IdSequence {

    private final StringRedisTemplate redis;

    public RedisIdSequence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * {@code INCRBY key count} — 증가와 조회가 한 번의 왕복에서 원자적으로 일어난다.
     *
     * <p>{@code GET} 후 {@code SET} 으로 흉내 낼 수 없다. 그 사이에 다른 요청이 끼어들면
     * 두 요청이 같은 구간을 선점하고, 그 결과는 예외가 아니라 <b>PK 위반으로 뒤늦게</b> 나타난다.
     */
    @Override
    public long reserve(String key, int count) {
        try {
            Long end = redis.opsForValue().increment(key, count);
            if (end == null) {
                // 파이프라인/트랜잭션 모드가 아닌 한 도달하지 않지만, null 을 그대로 흘리면
                // 다음 계산에서 NPE 로 둔갑해 원인이 가려진다.
                throw new RetryableException(EaiErrorCode.ID_ISSUE_FAILED,
                        "Redis 가 증가 결과를 돌려주지 않았다 (key=" + key + ")");
            }
            return end;
        } catch (DataAccessException e) {
            throw new RetryableException(EaiErrorCode.ID_ISSUE_FAILED,
                    "Redis 채번 실패 (key=" + key + ", count=" + count + ")", e);
        }
    }

    /**
     * 기동 시 카운터 복원. {@code GET} → 비교 → {@code SET} 이며 원자적이지 않다.
     *
     * <p>그래도 되는 이유는 <b>트래픽 유입 전 1회</b>라는 전제 때문이다.
     * 운영 중에 호출하면 선점과 경합해 같은 번호가 두 번 나갈 수 있으므로,
     * 이 메서드는 기동 경로 밖에서 부르지 않는다.
     */
    @Override
    public void seedAtLeast(String key, long value) {
        try {
            String raw = redis.opsForValue().get(key);
            long current = parseCounter(key, raw);
            if (current < value) {
                redis.opsForValue().set(key, Long.toString(value));
            }
        } catch (DataAccessException e) {
            throw new RetryableException(EaiErrorCode.ID_ISSUE_FAILED,
                    "Redis 카운터 복원 실패 (key=" + key + ")", e);
        }
    }

    /**
     * 카운터 값 해석.
     *
     * <p>숫자가 아닌 값이 들어 있다면 키가 다른 용도로 쓰이고 있다는 뜻이다.
     * 0으로 간주하고 넘어가면 카운터를 통째로 되돌려 중복 채번을 만든다. 실패시킨다.
     */
    private long parseCounter(String key, String raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new NonRetryableException(EaiErrorCode.ID_ISSUE_FAILED,
                    "채번 카운터에 숫자가 아닌 값이 들어 있다 (key=" + key + "). 키 충돌을 확인할 것", e);
        }
    }
}
