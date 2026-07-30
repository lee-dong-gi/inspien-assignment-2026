package com.inspien.eai.common.lock;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis 기반 분산 락 — {@code SET key token NX PX ttl}.
 *
 * <h2>획득: 한 번의 왕복에서 원자적으로</h2>
 * {@code EXISTS} 로 확인한 뒤 {@code SET} 하는 방식은 <b>쓸 수 없다.</b> 두 명령 사이에
 * 다른 실행이 끼어들면 둘 다 "비어 있었다" 고 판단하고 둘 다 락을 얻는다.
 * 그 결과는 예외가 아니라 <b>중복 배송</b>으로 나타난다 — 채번에서 {@code GET}+{@code SET} 을
 * 배제하고 {@code INCRBY} 를 쓴 것과 같은 판단이다.
 *
 * <p>{@code NX}(없을 때만)와 {@code PX}(만료시간)를 <b>한 명령에</b> 실어야 하는 이유도 같다.
 * {@code SETNX} 후 {@code EXPIRE} 로 나누면, 그 사이에 프로세스가 죽으면
 * <b>만료 시간이 없는 락</b>이 남아 배치가 영구히 멈춘다.
 *
 * <h2>반납: 토큰을 확인하고 지운다 — 이것이 이 클래스의 요점이다</h2>
 * 락에는 TTL 이 있으므로 <b>수행이 TTL 을 넘기면 소유권이 조용히 사라진다.</b>
 * 그 뒤에 다른 실행이 락을 새로 잡았는데 우리가 {@code DEL} 을 부르면,
 * <b>남의 락을 풀어 주고 세 번째 실행을 들여보낸다.</b>
 * 이것이 분산 락에서 가장 흔한 결함이며, 증상은 "락을 걸었는데 겹쳐 돌았다" 다.
 *
 * <pre>
 *   A: 락 획득 (토큰 a, TTL 4분)
 *   A: 수행이 5분 걸림 → 4분에 락 만료
 *   B: 락 획득 (토큰 b)          ← 정상. TTL 의 존재 이유다
 *   A: 수행 종료 → DEL           ← 여기서 b 를 지운다. B 는 자기가 락을 쥐고 있다고 믿는 중
 *   C: 락 획득 → B 와 겹쳐 돈다
 * </pre>
 *
 * 그래서 {@code GET} 으로 토큰을 비교한 뒤 지우는데, 그 비교와 삭제도 원자적이어야 하므로
 * <b>Lua 스크립트</b>로 서버에서 실행한다. 클라이언트에서 비교하면 비교와 삭제 사이가 또 벌어진다.
 *
 * <p>토큰이 이미 남의 것이 되어 있으면 삭제하지 않고 <b>ERROR 로 기록한다.</b>
 * 그 로그는 "수행 시간이 TTL 을 넘었다 = 겹쳐 돌았을 수 있다" 는 유일한 단서이며,
 * 조용히 넘기면 중복 적재의 원인을 영원히 못 찾는다.
 *
 * <h2>Redis 가 죽으면 락 없이 진행하지 않는다</h2>
 * 가용성을 위해 안전장치를 끄는 선택은 이 인터페이스에서 하지 않는다.
 * 되돌릴 수 없는 환경(append-only)에서 중복 전송은 "잠시 멈춤" 보다 훨씬 나쁜 결과다.
 * {@link EaiErrorCode#BATCH_LOCK_STORE_ERROR} 로 실패시키고, 회복은 다음 주기에 맡긴다.
 */
@Slf4j
public class RedisDistributedLock implements DistributedLock {

    /**
     * 토큰이 내 것일 때만 지운다.
     *
     * <p>{@code GET} 과 {@code DEL} 을 한 스크립트에 넣는 이유는 Redis 가 스크립트를
     * <b>단일 명령처럼</b> 실행하기 때문이다. 클라이언트에서 두 번 왕복하면
     * 그 사이에 TTL 이 만료되어 같은 사고가 난다.
     */
    private static final RedisScript<Long> RELEASE_IF_MINE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end""", Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            // TTL 없는 락은 프로세스가 죽는 순간 영구 잠금이 된다. 조립 시점에 끊는다.
            throw new IllegalArgumentException("락 TTL 은 양수여야 한다 (key=" + key + ", ttl=" + ttl + ")");
        }

        // 토큰은 실행마다 새로 만든다. "내 락인가" 를 판정할 유일한 근거이므로,
        // 고정값(호스트명 등)을 쓰면 같은 인스턴스의 앞 실행과 구분되지 않는다.
        String token = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        } catch (DataAccessException e) {
            throw new RetryableException(EaiErrorCode.BATCH_LOCK_STORE_ERROR,
                    "락 저장소에 닿지 못했다 (key=" + key + "). 락 없이 배치를 돌리지 않는다", e);
        }

        if (!Boolean.TRUE.equals(acquired)) {
            log.info("[LOCK] 이미 점유 중이다 — 이번 주기를 건너뛴다 (key={})", key);
            return Optional.empty();
        }

        log.debug("[LOCK] 획득 (key={}, ttl={})", key, ttl);
        return Optional.of(new RedisLockHandle(key, token));
    }

    /** 획득한 락 1개. 토큰을 들고 있어야 반납 시 소유를 증명할 수 있다. */
    private final class RedisLockHandle implements LockHandle {

        private final String key;
        private final String token;
        private boolean released;

        private RedisLockHandle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public void close() {
            if (released) {
                // 두 번 반납하면 두 번째는 남의 락을 지울 수 있다. 상태로 막는다.
                log.debug("[LOCK] 이미 반납된 락에 close 가 다시 호출됐다 (key={})", key);
                return;
            }
            released = true;

            Long deleted;
            try {
                deleted = redis.execute(RELEASE_IF_MINE, List.of(key), token);
            } catch (DataAccessException e) {
                // 예외를 던지지 않는다. 반납은 본 작업이 끝난 뒤에 불리므로,
                // 여기서 던지면 정상 결과나 원래의 실패 원인을 덮어 버린다.
                // 지우지 못한 락은 TTL 이 만료되면서 자연히 풀린다.
                log.error("[{}] 락 반납 실패 (key={}). TTL 만료까지 다음 주기가 건너뛸 수 있다",
                        EaiErrorCode.BATCH_LOCK_STORE_ERROR.code(), key, e);
                return;
            }

            if (deleted == null || deleted == 0L) {
                // 우리 토큰이 이미 사라졌다 = TTL 을 넘겨 수행했다는 뜻이다.
                // 그동안 다른 실행이 락을 잡았을 수 있으므로 겹침 가능성을 반드시 남긴다.
                log.error("[{}] 반납 시점에 락이 내 것이 아니었다 (key={}). "
                                + "수행 시간이 TTL 을 초과해 다른 주기와 겹쳐 돌았을 수 있다 — "
                                + "lock-ttl 을 늘리거나 chunk-size / max-chunks-per-run 을 줄일 것",
                        EaiErrorCode.BATCH_LOCK_ACQUIRE_FAILED.code(), key);
                return;
            }

            log.debug("[LOCK] 반납 (key={})", key);
        }
    }
}
