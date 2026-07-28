package com.inspien.eai.common.id;

/**
 * 원자적 카운터 — 채번의 <b>동시성</b>만 책임진다.
 *
 * <p>형식({@link SerialIdCodec})과 정책({@link SequentialIdGenerator})에서 저장소를 분리한 이유는
 * 이 계층만 외부 인프라에 의존하기 때문이다. 덕분에 형식·정책은 Redis 없이 단위 테스트할 수 있고,
 * 저장소가 바뀌어도 규격 로직은 손대지 않는다.
 *
 * <p><b>여기서 다루는 값은 1-based</b>다. Redis {@code INCRBY} 의 반환값이 증가 <i>후</i> 값이므로,
 * 최초 1개 선점의 반환값은 1이다. 0-based 인덱스로의 변환은 {@link SequentialIdGenerator} 가 한다.
 * 경계를 넘나드는 ±1 계산을 한 곳에만 두기 위한 구분이다.
 */
public interface IdSequence {

    /**
     * {@code count} 개를 원자적으로 선점하고 <b>선점 구간의 마지막 값</b>을 돌려준다.
     *
     * <p>반환값이 {@code end} 이면 호출자가 소유하는 구간은 {@code [end - count + 1, end]} 다.
     * 한 번에 여러 개를 가져오는 것이 핵심이다 — 63행을 63번 호출하면 왕복이 63번인 것도 문제지만,
     * 그보다 <b>중간에 실패했을 때 절반만 번호가 붙은 상태</b>가 만들어지는 것이 더 큰 문제다.
     */
    long reserve(String key, int count);

    /**
     * 카운터를 최소 {@code value} 이상으로 끌어올린다. 이미 그 이상이면 아무것도 하지 않는다.
     *
     * <p>저장소가 비워졌을 때 이미 적재된 데이터로부터 카운터를 복원하는 용도다
     * ({@code SELECT MAX(ORDER_ID)} → {@link SerialIdCodec#decode} → +1).
     *
     * <p><b>기동 시 1회, 트래픽 유입 전에만 호출한다.</b> 구현이 원자적이지 않아도 되는 것은
     * 그 전제 때문이며, 운영 중에 부르면 선점과 경합해 같은 번호가 두 번 나갈 수 있다.
     */
    void seedAtLeast(String key, long value);
}
