package com.inspien.eai.common.jdbc;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 배치 실행 결과 판정 — 두 JDBC Receiver 가 공유하는 규칙.
 *
 * <h2>왜 따로 두는가</h2>
 * {@code executeBatch()} 의 반환값 해석은 <b>직관과 다르고, 틀렸을 때 조용하다.</b>
 * 두 Receiver({@code ORDER_TB}, {@code SHIPMENT_TB})에 같은 판정을 복사해 두면
 * 한쪽만 고쳐지는 날이 오고, 그날 한쪽은 멀쩡한 적재를 실패로 뒤집거나
 * 실패한 적재를 성공으로 보고한다.
 *
 * <h2>반환값을 합산하지 않는다 — 개수를 센다</h2>
 * JDBC 명세상 배치 결과 원소는 영향 행 수일 수도 있지만
 * {@link Statement#SUCCESS_NO_INFO}({@code -2}) 일 수도 있다. 이것은 오류가 아니라
 * <b>"성공했지만 몇 행인지는 모른다"</b> 는 정상 응답이며, Oracle 드라이버는 실제로
 * 이 값을 자주 돌려준다. 합산하면 음수가 섞여 들어가
 * <b>63행을 정상 적재하고도 실패로 판정</b>된다.
 *
 * <p>그래서 성공 판정은 두 가지만 본다.
 * <ol>
 *   <li>응답 원소 개수가 요청한 문장 수와 같은가</li>
 *   <li>{@link Statement#EXECUTE_FAILED}({@code -3}) 가 섞여 있지 않은가</li>
 * </ol>
 *
 * <p>"몇 행이 바뀌었는가" 를 정확히 알아야 하는 검증은 이 방식으로 할 수 없다.
 * 그런 검증이 필요하면 배치를 포기하고 문장마다 {@code executeUpdate()} 를 해야 하며,
 * 그 대가(왕복 N회)를 치를 만한 이유가 있는지 호출부가 판단해야 한다
 * (→ {@code ShipmentTbReceiver} 의 상태 갱신 검증 논의 참조).
 */
public final class JdbcBatches {

    private JdbcBatches() {
    }

    /**
     * 배치를 실행하고 결과를 판정한다.
     *
     * @param label    실패 메시지에 실을 대상 이름 (예: {@code ORDER_TB})
     * @param expected 이 배치에 쌓아 둔 문장 수
     * @return 성공한 문장 수 (= {@code expected})
     * @throws NonRetryableException 개수가 어긋나거나 실패한 문장이 있는 경우
     */
    public static int execute(PreparedStatement statement, String label, int expected) throws SQLException {
        int[] results = statement.executeBatch();

        if (results.length != expected) {
            throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                    "[" + label + "] 배치 결과 개수가 요청과 다르다 — 요청 " + expected + ", 응답 " + results.length);
        }
        for (int i = 0; i < results.length; i++) {
            if (results[i] == Statement.EXECUTE_FAILED) {
                // 값은 담지 않는다. 배치 내 위치만으로 원본 레코드를 찾아갈 수 있고,
                // 값을 담으면 개인정보가 예외 메시지를 타고 로그로 나간다.
                throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                        "[" + label + "] 배치 내 " + i + "번째 행이 실패했다");
            }
        }
        return results.length;
    }
}
