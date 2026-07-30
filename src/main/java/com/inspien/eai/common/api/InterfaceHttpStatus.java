package com.inspien.eai.common.api;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.ProcessResult;
import org.springframework.http.HttpStatus;

/**
 * 실행 결과 → HTTP 상태 코드 매핑 (정의서 3.10 / D-19).
 *
 * <h2>왜 컨트롤러 밖으로 뺐는가</h2>
 * 인터페이스가 둘이 되면서 진입점도 둘이 됐다({@code OrderController}, {@code ShipmentController}).
 * 매핑을 각자 들고 있으면 <b>같은 에러 코드가 인터페이스에 따라 다른 상태로 나간다</b> —
 * 호출자 입장에서 상태 코드는 "다음에 무엇을 할지" 를 정하는 신호인데, 그 신호가
 * 엔드포인트마다 다르면 신호로서 기능하지 않는다.
 *
 * <h2>{@code PARTIAL} 은 200 이다</h2>
 * 요청 처리 자체는 정상적으로 끝났고, <b>결과가 부분 성공</b>일 뿐이다.
 * 상태 코드로 한 번 더 표현하면 호출자가 본문을 안 보고 판단하게 되는데,
 * "63건 들어가고 11건 빠졌다" 는 정보는 상태 코드에 담을 수 없다.
 *
 * <p>보상 트랜잭션이 되돌릴 수 없는 자리에서 실패한 경우({@code errorCode} 가 실린 PARTIAL)도
 * 마찬가지로 200 이다. 5xx 를 주면 호출자의 재시도 로직이 돌고,
 * <b>그 재요청이 이미 적재된 63행을 한 번 더 넣는다</b> — 정확히 D-14 가 막으려던 사고다.
 *
 * <h2>{@code default} 를 두지 않았다</h2>
 * Java 21 의 exhaustive switch 라서 <b>{@link EaiErrorCode} 에 코드를 하나 추가하면
 * 이 자리에서 컴파일이 깨진다.</b> 새 실패 유형이 아무 생각 없이 500 으로 흘러가는 것을
 * 막는 장치이며, 이 프로젝트가 {@code ApplicantKey} 타입을 도입한 것과 같은 발상이다.
 * 실제로 {@code EAI-4006}(락 저장소 장애)을 추가할 때 이 스위치가 먼저 깨졌고,
 * 그래서 503 으로 분류하는 판단을 <b>빠뜨릴 수 없었다.</b>
 */
public final class InterfaceHttpStatus {

    private InterfaceHttpStatus() {
    }

    /** 실행 결과 전체를 상태 코드로 옮긴다. {@code SUCCESS} · {@code PARTIAL} 은 200. */
    public static HttpStatus of(ProcessResult result) {
        if (result.outcome() != ProcessResult.Outcome.FAIL) {
            return HttpStatus.OK;
        }
        return of(result.errorCode());
    }

    /**
     * 에러 코드 대역을 그대로 상태 코드로 옮긴다.
     *
     * <pre>
     *   1xxx  메시지 자체의 문제   → 400  <b>송신 측이 고쳐야 한다.</b> 같은 요청을 다시 보내면 또 실패한다
     *   2xxx/3xxx 재시도 가능      → 503  대상 시스템 일시 장애. 잠시 후 같은 요청을 다시 보내면 된다
     *   2xxx/3xxx 재시도 불가      → 502  대상 시스템에서 확정적으로 실패했다
     *   4xxx  우리 쪽 문제         → 500  조립 오류·버그·운영 한계. 송신 측이 할 수 있는 일이 없다
     * </pre>
     *
     * <p>이 구분이 실제로 하는 일은 <b>전화를 누구에게 걸지 정해 주는 것</b>이다.
     * 전부 500 으로 뭉뚱그리면 송신 시스템 담당자는 자기 데이터가 잘못됐다는 사실을 영원히 모른다.
     */
    public static HttpStatus of(EaiErrorCode code) {
        if (code == null) {
            // 코드 없는 FAIL 은 우리 쪽 결함이다. 결과를 만든 자리가 사유를 붙이지 않았다는 뜻이다.
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            // 1xxx — 보낸 것이 잘못됐다
            case VALIDATION_ERROR, MAPPING_ERROR, SOURCE_PARSE_ERROR, SOURCE_ENCODING_ERROR ->
                    HttpStatus.BAD_REQUEST;

            // 2xxx/3xxx + 채번 + 락 저장소 — 다시 하면 될 수 있다 (EaiErrorCode.retryable() 과 같은 기준)
            case JDBC_CONN_ERROR, FTP_CONN_ERROR, FTP_UPLOAD_ERROR,
                 ID_ISSUE_FAILED, BATCH_LOCK_STORE_ERROR ->
                    HttpStatus.SERVICE_UNAVAILABLE;

            // 2xxx/3xxx — 대상 시스템에서 확정적으로 실패했다
            case JDBC_EXEC_ERROR, FTP_ENCODING_ERROR, FTP_COMMIT_FAILED ->
                    HttpStatus.BAD_GATEWAY;

            // 배치 겹침 방지 — 서버 결함이 아니라 상태 충돌이다.
            // 500 으로 주면 "고장났다" 는 신호가 되지만, 실제로는 안전장치가 제대로 동작한 것이다.
            case BATCH_LOCK_ACQUIRE_FAILED -> HttpStatus.CONFLICT;

            // 4xxx — 우리 쪽 문제
            case ID_SPACE_EXHAUSTED, DELIVERY_ERROR, FLOW_ERROR ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
