package com.inspien.eai.engine.log;

/**
 * 인터페이스 실행 이력 1줄을 조립한다.
 *
 * <p>Logback 패턴이 아니라 코드에서 조립하는 이유는 두 가지다.
 * 패턴 레이아웃으로 숫자 우측 정렬까지 맞추면 읽기 힘든 XML 이 되고,
 * 무엇보다 <b>정렬 규칙이 코드가 아니라 설정에 흩어져</b> 테스트할 수 없게 된다.
 * Logback 은 시각과 개행만 담당한다.
 *
 * <p>열을 고정폭으로 맞추는 이유는 <b>세로로 훑기 위해서</b>다.
 * {@code KEY=VALUE} 는 파싱은 쉬워도 값의 시작 위치가 줄마다 달라져 눈으로 읽히지 않는다.
 * 열이 고정되면 {@code RESULT} 열만 따라 내려가며 어디서 틀어졌는지 즉시 찾을 수 있고,
 * 구분자가 일정하므로 {@code Import-Csv -Delimiter '|'} 로 객체 파싱도 된다.
 *
 * <pre>
 * TIME(Logback) | IF_ID      | TX_ID    | STEP          | RESULT  |    OK | SKIP | FAIL |     MS | DETAIL
 *               | IF-ORD-001 | 8f2c1a94 | VALIDATOR     | PARTIAL |    63 |   11 |    0 |     12 | ORPHAN_ITEM=7
 * </pre>
 */
public final class InterfaceLogFormatter {

    /** 값이 없는 열의 표기. 공백으로 두면 열이 밀린 것인지 값이 없는 것인지 구분되지 않는다 */
    public static final String NONE = "-";

    /** TX_ID 표시 길이. 전체 UUID(36자)는 정렬을 무너뜨리므로 START 줄 DETAIL 에 한 번만 남긴다 */
    public static final int TX_ID_WIDTH = 8;

    private static final String LAYOUT = "%-10s | %-8s | %-13s | %-7s | %5s | %4s | %4s | %6s | %s";

    /** DETAIL 상한. 한 줄이 화면을 넘어가면 고정폭의 이점이 사라진다 */
    private static final int DETAIL_MAX = 120;

    private InterfaceLogFormatter() {
    }

    public static String format(String ifId,
                                String txId,
                                String step,
                                String result,
                                String ok,
                                String skip,
                                String fail,
                                String elapsedMs,
                                String detail) {
        return LAYOUT.formatted(
                nullSafe(ifId),
                shortTxId(txId),
                nullSafe(step),
                nullSafe(result),
                blankToNone(ok),
                blankToNone(skip),
                blankToNone(fail),
                blankToNone(elapsedMs),
                sanitizeDetail(detail));
    }

    /** UUID 앞 8자. 이 규모에서 충돌 가능성은 실질적으로 없고, 전체 값은 START 줄에서 복원된다 */
    public static String shortTxId(String txId) {
        if (txId == null || txId.isBlank()) {
            return NONE;
        }
        return txId.length() <= TX_ID_WIDTH ? txId : txId.substring(0, TX_ID_WIDTH);
    }

    /**
     * DETAIL 열 정제.
     *
     * <p>구분자({@code |})와 개행이 섞이면 열 구조가 깨져 파싱이 통째로 어긋난다.
     * 마지막 열이라 자유 형식이지만, <b>구조를 깨뜨릴 문자만은 차단</b>한다.
     */
    public static String sanitizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return NONE;
        }
        String cleaned = detail
                .replace('|', '/')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (cleaned.length() > DETAIL_MAX) {
            cleaned = cleaned.substring(0, DETAIL_MAX - 3) + "...";
        }
        return cleaned.isEmpty() ? NONE : cleaned;
    }

    private static String blankToNone(String value) {
        return (value == null || value.isBlank()) ? NONE : value;
    }

    private static String nullSafe(String value) {
        return value == null ? NONE : value;
    }
}
