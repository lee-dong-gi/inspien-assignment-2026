package com.inspien.eai.common.id;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;

import java.util.ArrayList;
import java.util.List;

/**
 * 순차 채번기 — {@link IdSequence} 가 나눠 준 구간을 {@link SerialIdCodec} 형식으로 옮긴다.
 *
 * <p>순차를 택한 이유(D-03)는 <b>재현성</b>이다. 무작위 채번은 충돌 확률이 낮을 뿐 0이 아니고,
 * 26,000이라는 좁은 공간에서는 생일 역설로 수백 건만에 충돌이 현실화된다.
 * 게다가 무작위 값은 "몇 번째 실행에서 생긴 데이터인가" 를 알려주지 않아 시연·디버깅에서 손해다.
 *
 * <h2>소진 처리 (D-10)</h2>
 * 공간을 벗어나면 {@link EaiErrorCode#ID_SPACE_EXHAUSTED} 로 <b>정직하게 실패</b>한다.
 * 되감지({@code DECRBY}) 않는다 — 실패한 요청이 번호를 태우는 손해보다,
 * 되감기와 다른 요청의 선점이 경합해 <b>같은 번호가 두 번 발급되는 사고</b>가 비교할 수 없이 크다.
 * 어차피 소진된 시점이므로 되찾을 번호도 남아 있지 않다.
 *
 * <p>개발 중 리셋은 이 클래스에 넣지 않고 {@code tools/reset-sequence.ps1} 로 분리했다.
 * 카운터를 되돌리는 경로가 애플리케이션 안에 있으면 언젠가 설정 실수로 운영에서 실행된다.
 */
public class SequentialIdGenerator implements IdGenerator {

    private final IdSequence sequence;
    private final SequenceKey key;

    public SequentialIdGenerator(IdSequence sequence, SequenceKey key) {
        this.sequence = sequence;
        this.key = key;
    }

    @Override
    public List<String> allocate(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("발급 개수는 음수일 수 없다: " + count);
        }
        if (count == 0) {
            return List.of();
        }

        // 선점을 시도하기 전에 자를 수 있는 조건. 한 요청이 공간 전체보다 크면
        // 카운터를 건드릴 이유가 없다 — 실패할 것을 알면서 번호를 태우지 않는다.
        if (count > SerialIdCodec.CAPACITY) {
            throw new NonRetryableException(EaiErrorCode.ID_SPACE_EXHAUSTED,
                    count + "개 요청은 채번 공간(" + SerialIdCodec.CAPACITY + ") 자체보다 크다");
        }

        long end = sequence.reserve(key.key(), count);
        long start = end - count + 1;

        // 저장소는 1-based, 형식은 0-based. 이 -1 은 여기 한 곳에만 존재한다.
        List<String> ids = new ArrayList<>(count);
        for (long value = start; value <= end; value++) {
            // 구간 중간에 공간을 벗어나면 여기서 터진다. 이미 선점된 번호는 회수하지 않는다.
            ids.add(SerialIdCodec.encode(value - 1));
        }
        return List.copyOf(ids);
    }

    /**
     * 이미 적재된 마지막 식별자를 기준으로 카운터를 복원한다.
     *
     * <p>기동 시 {@code SELECT MAX(ORDER_ID) FROM ORDER_TB WHERE APPLICANT_KEY = ?} 결과를 넘긴다.
     * 데이터가 없으면 {@code null} 을 넘기면 되고, 이 경우 아무것도 하지 않는다.
     *
     * <p><b>이 메서드가 있어서 Redis 는 "진실의 원천" 이 아니라 동시성 조정 계층이 된다.</b>
     * 진실은 이미 적재된 데이터에 있고 Redis 는 다음 번호를 빠르고 원자적으로 나눠 줄 뿐이므로,
     * 컨테이너를 지우고 다시 띄워도 정합성이 깨지지 않는다.
     * 사전식 정렬 순서가 곧 채번 순서라는 형식의 성질(→ {@link SerialIdCodec})이 이를 가능하게 한다.
     */
    public void seedFrom(String lastIssuedId) {
        if (lastIssuedId == null || lastIssuedId.isBlank()) {
            return;
        }
        // decode 는 규격 밖의 값이면 실패한다. 모르는 경로로 들어온 데이터가 섞인 채
        // 이어 채번하면 충돌하므로, 조용히 넘기지 않고 드러낸다.
        long usedCount = SerialIdCodec.decode(lastIssuedId.trim()) + 1L;
        sequence.seedAtLeast(key.key(), usedCount);
    }

    public SequenceKey key() {
        return key;
    }
}
