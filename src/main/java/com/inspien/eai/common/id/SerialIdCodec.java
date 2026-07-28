package com.inspien.eai.common.id;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;

import java.util.regex.Pattern;

/**
 * 채번 식별자 코덱 — 0-based 일련번호 ↔ {@code [A-Z][0-9]{3}} 문자열.
 *
 * <p>형식은 <b>과제가 지시한 것</b>이다 (PDF p.5 {@code ORDER_ID}, p.6 {@code SHIPMENT_ID}:
 * "알파벳 대문자 1개 + 숫자 3개, 예 {@code A113}"). 자동 채번이 아니므로 값을 우리가 만들어 넣어야 한다.
 *
 * <p>형식에서 따라 나오는 것이 <b>공간의 유한함</b>이다. 26 × 1,000 = {@value #CAPACITY} 개뿐이다.
 * PDF 는 소진을 언급하지 않지만, 언급이 없다고 해서 일어나지 않는 일이 되지는 않는다.
 * 한 번 실행에 63행을 쓰므로 약 412회 실행이면 바닥난다 — 개발 중에 충분히 도달 가능한 수치다.
 *
 * <h2>사전식 정렬 순서 = 채번 순서</h2>
 * <pre>
 *   index      0 → A000
 *   index    999 → A999
 *   index  1,000 → B000
 *   index 25,999 → Z999
 * </pre>
 *
 * <p>앞자리가 문자이고 뒷자리가 <b>고정 3자리 제로패딩</b>이므로 문자열 비교 순서와
 * 번호 순서가 일치한다. 이 성질은 장식이 아니라 설계의 일부다 —
 * {@code SELECT MAX(ORDER_ID)} 하나로 "마지막으로 쓴 번호" 를 복원할 수 있고,
 * 덕분에 Redis 카운터가 유실돼도 이미 적재된 데이터에서 다시 이어 붙일 수 있다.
 * ({@code A99} 처럼 패딩을 생략했다면 {@code A9} > {@code A10} 이 되어 이 방법이 통하지 않는다.)
 */
public final class SerialIdCodec {

    /** 채번 공간 크기. 26개 문자 × 1,000개 숫자 */
    public static final int CAPACITY = 26 * 1_000;

    private static final Pattern FORMAT = Pattern.compile("^[A-Z][0-9]{3}$");

    private static final int BLOCK = 1_000;

    private SerialIdCodec() {
    }

    /**
     * 0-based 일련번호를 식별자로 만든다.
     *
     * @throws NonRetryableException 공간을 벗어난 경우. 재시도 불가로 분류한다 —
     *                               몇 번을 다시 해도 26,000번째 다음은 없다
     */
    public static String encode(long index) {
        if (index < 0) {
            throw new IllegalArgumentException("일련번호는 음수일 수 없다: " + index);
        }
        if (index >= CAPACITY) {
            throw new NonRetryableException(EaiErrorCode.ID_SPACE_EXHAUSTED,
                    "요청 번호 " + index + " 가 공간(" + CAPACITY + ")을 벗어났다. "
                            + "운영이라면 채번 규격 확장을 협의해야 하고, 개발이라면 tools/reset-sequence.ps1 로 리셋한다");
        }
        int i = (int) index;
        char letter = (char) ('A' + i / BLOCK);
        return letter + String.format("%03d", i % BLOCK);
    }

    /**
     * 식별자를 0-based 일련번호로 되돌린다.
     *
     * <p>{@code MAX(ORDER_ID)} 로 카운터를 복원할 때 쓴다. 형식에 맞지 않으면 실패시킨다 —
     * 규격 밖의 값이 섞여 있다는 것은 우리가 모르는 경로로 데이터가 들어왔다는 뜻이고,
     * 그 상태에서 이어 채번하면 충돌한다.
     */
    public static int decode(String id) {
        if (!matches(id)) {
            throw new NonRetryableException(EaiErrorCode.ID_ISSUE_FAILED,
                    "채번 규격에 맞지 않는 식별자다 (기대: [A-Z][0-9]{3}, 길이 "
                            + (id == null ? "null" : String.valueOf(id.length())) + ")");
        }
        return (id.charAt(0) - 'A') * BLOCK + Integer.parseInt(id.substring(1));
    }

    public static boolean matches(String id) {
        return id != null && FORMAT.matcher(id).matches();
    }
}
