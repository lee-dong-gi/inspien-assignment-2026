package com.inspien.eai.common.id;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트용 인메모리 카운터.
 *
 * <p><b>이 클래스가 테스트 소스에만 있는 것은 의도다.</b> 운영 소스에 두면
 * "Redis 가 안 뜰 때만 잠깐" 이라는 이유로 배선되고, 그 순간 채번은 재기동을 버티지 못하는
 * 상태가 된다. 컴파일 단계에서 그 선택지를 없앤다.
 *
 * <p>{@link AtomicLong#addAndGet} 은 Redis {@code INCRBY} 와 같은 의미(증가 후 값 반환)이므로
 * 형식·정책 계층의 동작을 그대로 검증할 수 있다. 검증하지 못하는 것은 <b>프로세스 간</b>
 * 원자성뿐이고, 그것은 이 클래스가 아니라 Redis 의 책임이다.
 */
public class InMemoryIdSequence implements IdSequence {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long reserve(String key, int count) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(count);
    }

    @Override
    public void seedAtLeast(String key, long value) {
        counters.computeIfAbsent(key, k -> new AtomicLong())
                .updateAndGet(current -> Math.max(current, value));
    }

    public long peek(String key) {
        AtomicLong counter = counters.get(key);
        return counter == null ? 0L : counter.get();
    }
}
