package com.inspien.eai.common.lock;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.RetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis 없이 검증한다.
 *
 * <p>이 클래스가 지켜야 할 성질은 전부 <b>어떤 명령을 어떤 인자로 보내는가</b>의 문제다.
 *
 * <ul>
 *   <li>획득은 {@code NX} + {@code PX} 를 <b>한 명령에</b> 실어야 한다 → {@code setIfAbsent(k, v, ttl)}</li>
 *   <li>반납은 <b>내가 넣은 토큰일 때만</b> 지워야 한다 → 스크립트에 그 토큰을 넘겼는가</li>
 *   <li>반납은 <b>예외를 던지지 않아야</b> 한다 → 본 작업의 결과를 덮으면 안 된다</li>
 * </ul>
 *
 * <p>실제 Redis 를 띄우면 이 중 어느 것도 관측하기 쉬워지지 않는다. 특히
 * "남의 락을 지우지 않았다" 는 <b>TTL 만료를 기다려야</b> 재현되므로 단위 테스트로는 불가능하고,
 * 대역으로 세우면 <b>스크립트에 넘긴 토큰</b>을 그대로 단언할 수 있다.
 *
 * <p>여기서 검증되지 <b>않는</b> 것은 원자성 그 자체다. {@code SET NX PX} 와 Lua 스크립트가
 * 원자적이라는 것은 Redis 의 보장이며, 우리가 할 일은 그 보장에 기대는 명령을 고르는 것뿐이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisDistributedLock — 분산 락 획득·반납")
class RedisDistributedLockTest {

    private static final String KEY = "eai:lock:if-shp-001";
    private static final Duration TTL = Duration.ofMinutes(4);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisDistributedLock lock() {
        return new RedisDistributedLock(redis);
    }

    // ------------------------------------------------------------------ 획득

    @Test
    @DisplayName("획득은 NX·PX 를 한 명령에 실어 보낸다 — SETNX 후 EXPIRE 로 나누면 만료 없는 락이 남는다")
    void acquiresWithSingleAtomicCommand() {
        givenAcquire(true);

        Optional<LockHandle> handle = lock().tryAcquire(KEY, TTL);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(KEY), token.capture(), eq(TTL));

        assertAll(
                () -> assertTrue(handle.isPresent()),
                () -> assertEquals(KEY, handle.get().key()),
                () -> assertFalse(token.getValue().isBlank(), "소유를 증명할 토큰이 있어야 반납이 안전하다"));
    }

    @Test
    @DisplayName("이미 점유 중이면 비어 있는 결과다 — 예외가 아니다. 설계대로 동작한 것이다")
    void returnsEmptyWhenAlreadyHeld() {
        givenAcquire(false);

        Optional<LockHandle> handle = lock().tryAcquire(KEY, TTL);

        assertAll(
                () -> assertTrue(handle.isEmpty()),
                // 잡지 못했으므로 지울 것도 없다. 여기서 스크립트를 부르면 남의 락을 푼다.
                () -> verify(redis, never()).execute(
                        ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.any()));
    }

    @Test
    @DisplayName("저장소에 닿지 못하면 EAI-4006 으로 실패시킨다 — 락 없이 진행하는 선택지를 두지 않는다")
    void failsWithStoreErrorWhenRedisIsUnreachable() {
        given(redis.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(TTL)))
                .willThrow(new RedisConnectionFailureException("connection refused"));

        RetryableException e = assertThrows(RetryableException.class, () -> lock().tryAcquire(KEY, TTL));

        assertAll(
                // '누가 쥐고 있다'(EAI-4001) 로 뭉뚱그리면 Redis 장애가 정상 동작으로 읽힌다.
                () -> assertEquals(EaiErrorCode.BATCH_LOCK_STORE_ERROR, e.errorCode()),
                () -> assertTrue(e.errorCode().retryable(), "인프라 장애는 다시 하면 달라질 수 있다"));
    }

    @Test
    @DisplayName("TTL 이 없거나 양수가 아니면 조립 시점에 거부한다 — 영구 잠금이 될 자리다")
    void rejectsNonPositiveTtl() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> lock().tryAcquire(KEY, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> lock().tryAcquire(KEY, Duration.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> lock().tryAcquire(KEY, Duration.ofSeconds(-1))));
    }

    @Test
    @DisplayName("실행마다 새 토큰을 만든다 — 고정값이면 같은 인스턴스의 앞 실행과 구분되지 않는다")
    void issuesFreshTokenPerAcquire() {
        givenAcquire(true);
        RedisDistributedLock lock = lock();

        lock.tryAcquire(KEY, TTL);
        lock.tryAcquire(KEY, TTL);

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(eq(KEY), tokens.capture(), eq(TTL));

        assertNotEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
    }

    // ------------------------------------------------------------------ 반납

    @Test
    @DisplayName("반납은 획득할 때 넣은 그 토큰으로 지운다 — 이 클래스의 요점이다")
    void releasesWithTheSameTokenItStored() {
        givenAcquire(true);
        givenRelease(1L);

        LockHandle handle = lock().tryAcquire(KEY, TTL).orElseThrow();
        handle.close();

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(KEY), stored.capture(), eq(TTL));

        ArgumentCaptor<Object> released = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), released.capture());

        // 토큰을 확인하지 않고 DEL 을 부르면, TTL 을 넘겼을 때 남의 락을 풀어 세 번째 실행을 들여보낸다.
        assertEquals(stored.getValue(), released.getValue());
    }

    @Test
    @DisplayName("두 번 반납해도 한 번만 지운다 — 두 번째 삭제는 남의 락을 지울 수 있다")
    void releasesAtMostOnce() {
        givenAcquire(true);
        givenRelease(1L);

        LockHandle handle = lock().tryAcquire(KEY, TTL).orElseThrow();
        handle.close();
        handle.close();

        verify(redis, times(1)).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("반납 시점에 내 락이 아니었어도 예외를 던지지 않는다 — 본 작업의 결과를 덮으면 안 된다")
    void doesNotThrowWhenTokenNoLongerMine() {
        givenAcquire(true);
        givenRelease(0L);

        LockHandle handle = lock().tryAcquire(KEY, TTL).orElseThrow();

        // TTL 을 넘겨 수행한 상태다. 겹쳐 돌았을 가능성은 ERROR 로그로 남기고, 흐름은 막지 않는다.
        handle.close();
    }

    @Test
    @DisplayName("반납 도중 저장소가 죽어도 예외를 던지지 않는다 — 남은 락은 TTL 로 풀린다")
    void doesNotThrowWhenReleaseFails() {
        givenAcquire(true);
        given(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.any()))
                .willThrow(new RedisConnectionFailureException("connection reset"));

        LockHandle handle = lock().tryAcquire(KEY, TTL).orElseThrow();

        handle.close();
    }

    @Test
    @DisplayName("try-with-resources 로 쓰면 어느 경로로 빠져나가도 반납이 지나간다")
    void releasesOnExceptionalPath() {
        givenAcquire(true);
        givenRelease(1L);

        RedisDistributedLock lock = lock();

        assertThrows(IllegalStateException.class, () -> {
            try (LockHandle held = lock.tryAcquire(KEY, TTL).orElseThrow()) {
                assertEquals(KEY, held.key());
                throw new IllegalStateException("배치 도중 실패");
            }
        });

        verify(redis).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.any());
    }

    // ── 대역 배선 ───────────────────────────────────────────────

    private void givenAcquire(boolean acquired) {
        given(redis.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(TTL))).willReturn(acquired);
    }

    /** {@code 1} = 내 토큰을 지웠다 / {@code 0} = 이미 내 것이 아니었다 */
    private void givenRelease(Long deleted) {
        given(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), ArgumentMatchers.any()))
                .willReturn(deleted);
    }
}
