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
 *   4xxx  공통 운영 (배치 제어 · 채번 · 전달 조율)
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
    FTP_ENCODING_ERROR("EAI-3004", "FTP 인코딩 손상 — 파일명 또는 내용", false),

    /**
     * 확정 단계의 FTP 업로드 실패 — <b>되돌릴 수 없는 자리</b>에서 일어난다.
     *
     * <p>조율자가 JDBC 를 먼저 확정하므로, 이 코드가 붙는 순간은 <b>DB 가 이미 확정된 뒤</b>다.
     * 재시도하면 같은 주문을 한 번 더 적재하게 되므로 반드시 재시도 불가여야 한다.
     * 필요한 조치는 재실행이 아니라 <b>해당 파일을 수동으로 올리는 것</b> 하나다.
     *
     * <p>이전 판은 {@code FTP_RENAME_FAILED}({@code .tmp} → 최종명 rename 실패)였으나,
     * 대상 서버가 rename 자체를 거부해 업로드를 확정 단계로 옮기면서(D-21)
     * 실패하는 <b>동작</b>이 바뀌었다. 번호를 유지한 것은 의미(“확정 단계 FTP 실패 =
     * 수동 조치”)가 그대로기 때문이다.
     */
    FTP_COMMIT_FAILED("EAI-3005", "FTP 확정 업로드 실패 — 수동 조치 필요", false),

    /**
     * 다른 실행이 락을 쥐고 있어 이번 주기를 건너뛴 경우 (IF-SHP-001).
     *
     * <p><b>이것은 장애가 아니라 설계대로 동작한 결과다.</b> 이전 주기가 5분을 넘겨 아직
     * 돌고 있다는 뜻이며, 겹쳐 돌면 같은 주문이 두 번 운송사로 전달된다.
     * 그럼에도 성공으로 보고하지 않는 이유는 <b>처리하지 못한 일이 남아 있다</b>는 사실을
     * 감추면 안 되기 때문이다 — 이 코드가 매 주기 반복되면 배치가 사실상 멈춘 상태다.
     *
     * <p>재시도 불가로 둔 것은 그 자리에서 다시 시도해도 여전히 락이 잡혀 있기 때문이다.
     * 회복은 재시도가 아니라 <b>다음 주기</b>가 담당한다.
     * HTTP 로는 500 이 아니라 {@code 409 Conflict} 로 옮긴다 — 서버 결함이 아니라 상태 충돌이다.
     */
    BATCH_LOCK_ACQUIRE_FAILED("EAI-4001", "배치 분산 락 획득 실패 — 이전 주기 수행 중", false),

    ID_ISSUE_FAILED("EAI-4002", "채번 실패", true),
    ID_SPACE_EXHAUSTED("EAI-4003", "채번 공간 소진 — 26,000개 한도 도달", false),

    /**
     * 전달 조율 자체의 오류 — 대상 시스템의 문제가 아니라 <b>우리 쪽 문제</b>다.
     *
     * <p>수신처가 하나도 등록되지 않은 조립 오류, 또는 Receiver 가
     * {@link EaiException} 이 아닌 예외를 흘린 경우에 붙는다.
     * 이것을 {@link #JDBC_EXEC_ERROR} 나 {@link #FTP_UPLOAD_ERROR} 로 뭉뚱그리면
     * 운영자가 <b>엉뚱한 담당자에게 연락한다</b> — 대역을 나눈 이유 자체가 그것이다.
     */
    DELIVERY_ERROR("EAI-4004", "전달 조율 실패", false),

    /**
     * 파이프라인 실행 자체의 오류 — 어느 구간의 문제로도 분류되지 않는 예외.
     *
     * <p>{@code IntegrationFlow} 는 <b>예외를 밖으로 흘리지 않기로</b> 된 계약이므로
     * 최종 catch 가 반드시 있어야 하고, 그 자리에도 코드가 필요하다.
     * 여기에 걸리는 것은 사실상 <b>우리 코드의 버그</b>이므로 재시도 대상이 아니다.
     */
    FLOW_ERROR("EAI-4005", "파이프라인 실행 오류", false),

    /**
     * 락 저장소(Redis) 자체에 닿지 못한 경우 — {@link #BATCH_LOCK_ACQUIRE_FAILED} 와 <b>다르다.</b>
     *
     * <pre>
     *   EAI-4001  락이 잡혀 있다      → 정상 동작. 다음 주기가 처리한다
     *   EAI-4006  락을 확인할 수 없다 → 인프라 장애. 사람이 봐야 한다
     * </pre>
     *
     * <p>둘을 한 코드로 묶으면 <b>Redis 가 죽어 있는 상태를 "정상적으로 겹침 방지 중" 으로
     * 읽게 된다.</b> 배치는 매 주기 조용히 아무것도 하지 않고, 로그는 정상처럼 보인다.
     * 겹침 방지 로그가 반복되는 것과 저장소 장애는 조치가 정반대이므로 반드시 나눈다.
     *
     * <p>락을 확인할 수 없을 때 <b>락 없이 진행하지 않는다.</b> 그 순간 배치가 겹쳐 돌 수 있고,
     * 결과는 같은 주문의 중복 전송 — 되돌릴 수 없는 환경(append-only)에서는 최악의 실패다.
     * "가용성을 위해 안전장치를 끄는" 선택은 이 인터페이스에서 하지 않는다.
     *
     * <p>재시도 가능으로 분류하되, 실제 회복은 다음 주기가 담당한다. HTTP 로는 503 이다.
     */
    BATCH_LOCK_STORE_ERROR("EAI-4006", "배치 락 저장소 접근 실패", true);

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
     * <p>{@link #FTP_COMMIT_FAILED} 가 재시도 불가인 것은 특히 의도적이다.
     * 이 시점에는 DB 가 이미 확정되어 63행이 유효하게 들어 있다.
     * 재시도하면 <b>이미 적재된 주문을 한 번 더 넣게 된다.</b> 필요한 것은 재실행이 아니라
     * <b>파일 하나를 올리는 일</b>이므로 사람에게 넘긴다.
     *
     * <p>같은 이유로 {@link #FTP_UPLOAD_ERROR}(재시도 가능)와 분리된다.
     * <b>동일한 업로드 실패라도 어느 단계에서 일어났느냐에 따라 조치가 정반대다</b> —
     * 준비 단계였다면 DB 가 함께 롤백되므로 그대로 다시 보내면 되고,
     * 확정 단계였다면 절대 다시 보내서는 안 된다.
     *
     * <p>{@link #FTP_ENCODING_ERROR} 가 재시도 불가인 이유는 더 단순하다 —
     * 같은 설정으로 보내면 백 번을 보내도 같은 자리에서 같은 문자가 깨진다.
     *
     * <p>채번 둘을 나눈 것도 같은 기준이다. {@link #ID_ISSUE_FAILED}(Redis 단절)는
     * 다시 하면 될 수 있지만, {@link #ID_SPACE_EXHAUSTED}(26,000 소진)는 몇 번을 해도 같다.
     * 둘을 한 코드로 묶으면 소진 상태를 영원히 재시도하게 된다.
     *
     * <p>배치 락 둘도 마찬가지다. {@link #BATCH_LOCK_ACQUIRE_FAILED}(누가 쥐고 있다)는
     * 즉시 재시도해도 같은 결과지만, {@link #BATCH_LOCK_STORE_ERROR}(저장소 장애)는 달라질 수 있다.
     */
    public boolean retryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return code + " " + message;
    }
}
