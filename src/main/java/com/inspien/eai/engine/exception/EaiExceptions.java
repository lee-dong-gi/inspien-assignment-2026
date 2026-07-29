package com.inspien.eai.engine.exception;

/**
 * 예외를 <b>구간 경계에서</b> 다루는 공통 규칙.
 *
 * <p>파이프라인의 모든 구간이 같은 일을 한다 — 올라온 예외에 코드를 붙이고, 로그에 실을 사유를
 * 다듬는다. 이 규칙이 구간마다 복사되면 어느 한 곳만 다르게 처리되는 순간
 * <b>그 구간의 실패만 분류되지 않은 채</b> 흘러간다.
 */
public final class EaiExceptions {

    /**
     * 실행 이력은 <b>한 건이 한 줄</b>이다.
     *
     * <p>여러 줄짜리 예외 메시지({@code FTP_ENCODING_ERROR} 가 그렇다)가 그대로 들어가면
     * 로그 파일이 기계로 읽히지 않는다.
     */
    private static final int MAX_REASON_LENGTH = 300;

    private EaiExceptions() {
    }

    /**
     * 예외에서 코드를 꺼낸다. {@link EaiException} 이 아니면 구간이 정한 기본값을 쓴다.
     *
     * <p>기본값을 <b>호출부가 정하게</b> 한 이유는 대역을 구간별로 나눈 목적 때문이다.
     * 매퍼에서 터진 NPE 를 {@code FTP_UPLOAD_ERROR} 로 분류하면 운영자가 엉뚱한 담당자를 찾는다.
     */
    public static EaiErrorCode codeOf(RuntimeException e, EaiErrorCode fallback) {
        return (e instanceof EaiException eai) ? eai.errorCode() : fallback;
    }

    /**
     * 어떤 경로로 빠져나가든 <b>코드를 달고 나가게</b> 한다.
     *
     * <p>구현체가 {@link EaiException} 을 던진다는 것은 계약이지 보장이 아니다.
     * NPE 가 그대로 올라가면 호출자는 재시도 가능 여부를 판단할 근거가 없다.
     * 원인은 {@code cause} 로 보존하므로 진단 정보가 사라지지는 않는다.
     */
    public static EaiException wrap(RuntimeException e, EaiErrorCode fallback, String context) {
        return (e instanceof EaiException eai)
                ? eai
                : new NonRetryableException(fallback, context + " (" + e.getClass().getName() + ")", e);
    }

    /**
     * 로그 한 줄에 실을 사유로 다듬는다.
     *
     * <p>{@link EaiException} 의 메시지는 이미 코드로 시작하는데 로거가 코드를 한 번 더 붙인다.
     * 그 중복을 걷어 내고, 개행·연속 공백을 한 칸으로 접고, 상한을 넘으면 자른다.
     */
    public static String reason(RuntimeException e, EaiErrorCode code) {
        String raw = (e.getMessage() == null || e.getMessage().isBlank())
                ? e.getClass().getSimpleName()
                : e.getMessage();

        if (code != null) {
            String prefix = code.code() + " ";
            if (raw.startsWith(prefix)) {
                raw = raw.substring(prefix.length());
            }
        }

        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_REASON_LENGTH ? flat : flat.substring(0, MAX_REASON_LENGTH) + "…";
    }
}
