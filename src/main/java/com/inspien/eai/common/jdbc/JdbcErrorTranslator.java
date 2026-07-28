package com.inspien.eai.common.jdbc;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.EaiException;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * {@link SQLException} → 엔진 예외 변환.
 *
 * <p>변환의 목적은 예쁜 메시지가 아니라 <b>재시도 여부의 판정</b>이다. 이 판정을
 * 호출부마다 하면 기준이 흩어지고, 결국 검증 실패를 무한 재시도하거나 일시 단절을
 * 영구 실패로 처리하는 사고가 난다. 판정 규칙을 한 곳에 못박는다.
 *
 * <h2>분류 기준 — "다시 하면 결과가 달라질 수 있는가"</h2>
 * <table border="1">
 *   <caption>SQLException 분류</caption>
 *   <tr><th>대상</th><th>코드</th><th>재시도</th><th>근거</th></tr>
 *   <tr><td>{@link SQLRecoverableException}<br>{@link SQLTransientException}</td>
 *       <td>{@link EaiErrorCode#JDBC_CONN_ERROR}</td><td>가능</td>
 *       <td>커넥션 단절·타임아웃. 네트워크가 회복되면 달라진다</td></tr>
 *   <tr><td>{@link SQLIntegrityConstraintViolationException}</td>
 *       <td>{@link EaiErrorCode#JDBC_EXEC_ERROR}</td><td>불가</td>
 *       <td>PK 중복. 같은 값으로 몇 번을 해도 같다. <b>재시도는 원인을 가릴 뿐이다</b></td></tr>
 *   <tr><td>그 외</td><td>{@link EaiErrorCode#JDBC_EXEC_ERROR}</td><td>불가</td>
 *       <td>컬럼 길이 초과·문법 오류 등. 데이터나 코드를 고쳐야 한다</td></tr>
 * </table>
 *
 * <h2>개인정보</h2>
 * 예외 메시지에 <b>레코드 값을 넣지 않는다.</b> {@code NAME}·{@code ADDRESS} 가 그대로
 * 스택트레이스를 타고 로그 파일에 남는 경로를 아예 만들지 않는다. 남기는 것은
 * SQLState·벤더 코드·드라이버 메시지 첫 줄뿐이며, 진단에는 그것으로 충분하다
 * (Oracle 은 {@code ORA-12899} 처럼 어느 컬럼이 문제인지를 코드로 알려 준다).
 */
public final class JdbcErrorTranslator {

    /** {@code getNextException()} 추적 깊이 상한. 순환 참조 방어. */
    private static final int MAX_CHAIN_DEPTH = 5;

    private JdbcErrorTranslator() {
    }

    public static EaiException translate(String context, SQLException e) {
        SQLException cause = unwrapBatch(e);

        if (cause instanceof SQLIntegrityConstraintViolationException) {
            return new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                    context + " — 제약 위반. PK(ORDER_ID, APPLICANT_KEY) 중복이라면 "
                            + "채번 카운터가 적재 이력보다 뒤에 있다는 뜻이다(D-09 시딩 확인). " + brief(cause), e);
        }
        if (cause instanceof SQLRecoverableException || cause instanceof SQLTransientException) {
            return new RetryableException(EaiErrorCode.JDBC_CONN_ERROR,
                    context + " — " + brief(cause), e);
        }
        return new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                context + " — " + brief(cause), e);
    }

    /**
     * {@link BatchUpdateException} 안쪽의 진짜 원인을 꺼낸다.
     *
     * <p>배치 실행이 실패하면 드라이버는 {@code BatchUpdateException} 을 던지는데,
     * 이 타입 자체는 "배치가 실패했다" 이상을 말해 주지 않는다. 실제 사유
     * (제약 위반인지 커넥션 단절인지)는 {@code getNextException()} 체인에 들어 있다.
     * 겉껍데기만 보고 분류하면 <b>PK 중복까지 재시도 대상으로 잘못 분류</b>된다.
     */
    private static SQLException unwrapBatch(SQLException e) {
        SQLException current = e;
        for (int depth = 0; depth < MAX_CHAIN_DEPTH; depth++) {
            if (!(current instanceof BatchUpdateException)) {
                return current;
            }
            SQLException next = current.getNextException();
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * 진단에 필요한 최소 정보.
     *
     * <p>메시지는 첫 줄만 취한다. Oracle 드라이버는 뒤에 스택 형태의 부가 정보를 길게 붙이는데,
     * 그것까지 예외 메시지에 실으면 로그 한 줄이 화면을 넘어가 정작 중요한 코드가 묻힌다.
     */
    private static String brief(SQLException e) {
        String message = e.getMessage();
        String firstLine = (message == null) ? "" : message.lines().findFirst().orElse("").trim();
        return "SQLState=" + e.getSQLState() + ", ErrorCode=" + e.getErrorCode()
                + (firstLine.isEmpty() ? "" : ", " + firstLine);
    }
}
