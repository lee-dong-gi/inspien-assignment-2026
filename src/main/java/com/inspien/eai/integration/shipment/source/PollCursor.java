package com.inspien.eai.integration.shipment.source;

/**
 * 폴링 커서 — 다음 청크를 어디부터 읽을지 가리킨다.
 *
 * <h2>왜 필요한가 — 스킵과 폴링이 만나면 생기는 문제</h2>
 * 조회 조건은 {@code STATUS='N'} 이고, 우리는 이상 데이터를 <b>스킵하되 상태를 바꾸지 않는다</b>
 * ({@code ShipmentValidator} 참조 — 대상 스키마의 어휘를 늘릴 수 없으므로 {@code 'E'} 같은
 * 값으로 밀어낼 수 없다). 그러면 같은 조건으로 다시 조회할 때
 * <b>스킵된 행이 그대로 다시 나온다.</b>
 *
 * <p>{@code OFFSET} 도 답이 아니다. 처리한 행은 {@code 'Y'} 로 바뀌어 조회 대상에서
 * 빠지므로, 다음 조회의 결과 집합 자체가 줄어든다. 고정 {@code OFFSET} 을 더하면
 * <b>아직 처리하지 않은 행을 건너뛴다.</b>
 *
 * <p>커서 없이 "0건이 나올 때까지" 반복하면 세 가지가 동시에 망가진다.
 * <ol>
 *   <li><b>무한 루프.</b> 스킵된 행이 청크를 채우면 매번 같은 결과가 돌아온다</li>
 *   <li><b>집계 부풀림.</b> 같은 행이 청크마다 스킵으로 다시 집계되어,
 *       스킵 11건이 응답에는 40건으로 나간다</li>
 *   <li><b>로그 폭증.</b> 같은 {@code ORDER_ID} 경고가 한 실행 안에서 반복된다</li>
 * </ol>
 *
 * <h2>해법 — 읽은 자리를 기억한다 (keyset 페이징)</h2>
 * <pre>
 *   … AND STATUS = 'N' AND ORDER_ID &gt; :cursor ORDER BY ORDER_ID FETCH FIRST :n ROWS ONLY
 * </pre>
 * 커서를 <b>조회된 마지막 행</b>(스킵된 행 포함)으로 전진시키면, 한 실행 안에서
 * 같은 행을 두 번 읽지 않는다. 종료 조건도 단순해진다 — <b>청크가 덜 차면 끝</b>이다.
 *
 * <p>이 방식이 성립하는 근거는 {@code ORDER_ID} 형식의 성질이다.
 * {@code [A-Z][0-9]{3}} 에 고정 3자리 제로패딩이므로 <b>사전식 정렬 순서 = 채번 순서</b>이고
 * ({@code A000 < A999 < B000}), 전 문자가 ASCII 라 DB 정렬 방식에 좌우되지 않는다.
 * {@code MAX(ORDER_ID)} 를 채번 시딩 기준으로 쓸 수 있는 것과 같은 성질이다.
 *
 * <h2>커서 때문에 놓치는 행은 없는가</h2>
 * 실행 중에 IF-ORD-001 이 새 주문을 넣으면 그 행의 {@code ORDER_ID} 는 <b>더 크다</b>
 * (Redis 카운터가 단조 증가한다). 커서 뒤쪽이므로 이번 실행에서 읽힌다.
 *
 * <p>설령 커서보다 작은 행이 생겨 이번에 빠져도 <b>손실은 아니다.</b>
 * 그 행은 {@code STATUS='N'} 으로 남아 다음 주기가 처음부터 읽는다 — 커서는
 * <b>실행 안에서만</b> 유효하고 실행 사이에는 저장되지 않는다. 커서를 영속화하면
 * "커서보다 작은 미처리 행" 이 영구 유실되므로, 일부러 저장하지 않는다.
 *
 * @param afterOrderId 이 값보다 <b>큰</b> {@code ORDER_ID} 만 읽는다.
 *                     {@code null} 이면 처음부터 (조건절 자체를 붙이지 않는다)
 */
public record PollCursor(String afterOrderId) {

    private static final PollCursor FIRST = new PollCursor(null);

    /** 실행의 첫 청크. 조건절 없이 전체를 정렬해 앞에서 자른다. */
    public static PollCursor first() {
        return FIRST;
    }

    /**
     * 다음 청크의 커서.
     *
     * <p>넘길 값은 <b>조회된 마지막 행</b>의 {@code ORDER_ID} 다. 처리에 성공한 마지막 행이
     * 아니라는 점이 중요하다 — 스킵된 행을 지나치지 않으면 다시 읽게 되고,
     * 그것이 이 타입이 존재하는 이유다.
     */
    public static PollCursor after(String lastReadOrderId) {
        if (lastReadOrderId == null || lastReadOrderId.isBlank()) {
            // 커서를 전진시킬 수 없다면 같은 조회를 반복하게 된다.
            // 조용히 처음으로 되돌아가는 것이 최악이므로 드러낸다.
            throw new IllegalArgumentException("커서를 전진시킬 ORDER_ID 가 없다");
        }
        return new PollCursor(lastReadOrderId);
    }

    public boolean fromBeginning() {
        return afterOrderId == null;
    }
}
