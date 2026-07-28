package com.inspien.eai.engine.exception;

/**
 * 에러 코드 체계.
 *
 * <p>운영자가 로그를 볼 때 필요한 것은 스택트레이스가 아니라 <b>어느 구간에서 무슨 종류의
 * 문제가 났는가</b>다. 대역을 구간별로 나눠 코드만 보고 담당 시스템을 특정할 수 있게 한다.
 *
 * <pre>
 *   1xxx  메시지 자체의 문제   (송신 시스템 / 데이터)
 *   2xxx  JDBC 구간
 *   3xxx  FTP 구간
 *   4xxx  배치 운영
 * </pre>
 *
 * <p>{@link #retryable} 은 예외 처리 전략을 코드에 붙여 둔 것이다.
 * 재시도 여부를 호출부가 매번 판단하면 기준이 흩어지고, 검증 실패를 무한 재시도하는 사고가 난다.
 */
public enum EaiErrorCode {

    VALIDATION_ERROR("EAI-1001", "유효성 검증 실패", false),
    MAPPING_ERROR("EAI-1002", "매핑 오류", false),
    SOURCE_PARSE_ERROR("EAI-1003", "소스 구문 분석 실패", false),
    SOURCE_ENCODING_ERROR("EAI-1004", "소스 인코딩 해독 실패", false),

    JDBC_CONN_ERROR("EAI-2001", "DB 접속 실패", true),
    JDBC_EXEC_ERROR("EAI-2002", "DB 실행 실패", false),

    FTP_CONN_ERROR("EAI-3001", "FTP 접속 실패", true),
    FTP_UPLOAD_ERROR("EAI-3002", "FTP 업로드 실패", true),
    FTP_COMPENSATION_FAILED("EAI-3003", "FTP 보상 처리 실패 — 수동 조치 필요", false),
    FTP_FILENAME_ENCODING_ERROR("EAI-3004", "FTP 파일명 인코딩 손상", false),

    BATCH_LOCK_ACQUIRE_FAILED("EAI-4001", "배치 분산 락 획득 실패", false),
    ID_ISSUE_FAILED("EAI-4002", "채번 실패", true);

    private final String code;
    private final String message;
    private final boolean retryable;

    EaiErrorCode(String code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /**
     * 재시도로 회복 가능한가.
     *
     * <p>기준은 "다시 하면 결과가 달라질 수 있는가" 다. 커넥션 타임아웃은 달라질 수 있고,
     * 검증 실패나 PK 위반은 몇 번을 해도 같다.
     *
     * <p>{@link #FTP_COMPENSATION_FAILED} 가 재시도 불가인 것은 특히 의도적이다.
     * 보상까지 실패한 상태는 자동 회복 대상이 아니라 <b>사람이 개입해야 하는 상태</b>이며,
     * 조용히 재시도로 덮으면 정합성 깨진 사실 자체가 묻힌다.
     */
    public boolean retryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return code + " " + message;
    }
}
