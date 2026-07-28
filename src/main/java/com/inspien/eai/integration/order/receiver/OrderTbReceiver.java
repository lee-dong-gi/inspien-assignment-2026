package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.jdbc.JdbcErrorTranslator;
import com.inspien.eai.common.jdbc.PendingCommitDelivery;
import com.inspien.eai.common.jdbc.SqlIdentifiers;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.target.OrderRecord;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * IF-ORD-001 Receiver ① — {@code ORDER_TB} 적재.
 *
 * <p>표준 레코드를 Oracle 의 행으로 옮기는 것이 전부다. <b>값을 손보지 않는다.</b>
 * {@code PRICE} 를 숫자로 바꾸지도, {@code STATUS} 를 다시 정규화하지도 않는다.
 * 여기서 한 글자라도 고치면 <b>DB 에 들어간 값과 영수증 파일에 쓰인 값이 달라지고</b>,
 * 두 시스템을 대조할 방법이 사라진다. 변환은 Mapper 에서 이미 끝났다.
 *
 * <h2>{@code CREATE_TIME} 을 넣지 않는다</h2>
 * 이 컬럼은 {@code DEFAULT SYSDATE} 다(BOOT-001 실측). INSERT 목록에서 빼면
 * 등록 시각이 <b>DB 서버 시계</b>로 찍힌다. 우리가 애플리케이션 시각을 채워 넣으면
 * 서버·클라이언트 시계가 어긋나는 만큼 오차가 들어오고, 시연의
 * {@code TRUNC(CREATE_TIME) = TRUNC(SYSDATE)} 조회가 어긋날 수 있다(D-06).
 * 값을 소유한 쪽이 값을 채우게 두는 것이 원칙적으로도 맞다 —
 * 등록 시각은 우리 데이터가 아니라 <b>수신 시스템의 기록</b>이다.
 *
 * <h2>{@code prepare} 가 커밋하지 않는다</h2>
 * INSERT 까지만 하고 커밋을 보류한 {@link PendingCommitDelivery} 를 돌려준다.
 * FTP 업로드가 성공해야 비로소 확정되고, 실패하면 롤백된다(정의서 3.9).
 * 이 메서드가 정상 반환했다는 것은 <b>"지금 커밋하면 성공한다"</b> 는 뜻이어야 하므로,
 * 확정 단계에 검증거리를 남기지 않는다.
 *
 * <h2>파라미터 바인딩</h2>
 * 값은 전부 {@code ?} 로 바인딩한다. 문자열 결합 SQL 은 SQL 인젝션 이전에
 * <b>한글·따옴표·공백이 섞인 주소</b>에서 즉시 깨진다. 조립되는 것은 테이블명 하나뿐이고,
 * 그것도 {@link SqlIdentifiers} 검증을 거친다.
 */
@Slf4j
public class OrderTbReceiver implements Receiver<OrderRecord> {

    /**
     * 적재 컬럼 9개. {@code CREATE_TIME} 은 의도적으로 빠져 있다.
     *
     * <p>순서는 {@code ORDER_TB} 의 컬럼 순서다. 영수증 파일의 필드 순서와 다르므로
     * (파일은 2·3·4번이 {@code USER_ID}·{@code ITEM_ID}·{@code APPLICANT_KEY}, {@code STATUS} 없음)
     * 이 배열을 파일 조립에 재사용하면 오답이 된다.
     */
    private static final String INSERT_TEMPLATE = """
            INSERT INTO %s (
                ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID,
                NAME, ADDRESS, ITEM_NAME, PRICE, STATUS
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final DataSource dataSource;
    private final String table;
    private final String insertSql;
    private final int batchSize;
    private final int queryTimeoutSeconds;

    public OrderTbReceiver(DataSource dataSource, String table, int batchSize, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.table = SqlIdentifiers.requireSafe(table, "테이블명");
        this.insertSql = INSERT_TEMPLATE.formatted(this.table);
        this.batchSize = batchSize;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public Step step() {
        return Step.RECEIVER_JDBC;
    }

    @Override
    public Delivery prepare(CanonicalMessage<List<OrderRecord>> message) {
        List<OrderRecord> records = message.payload();
        if (records == null || records.isEmpty()) {
            // 검증에서 전건이 걸러진 경우다. 넣을 것이 없는데 트랜잭션을 열 이유가 없다.
            log.debug("[{}] 적재 대상 0건 — 트랜잭션을 열지 않는다", table);
            return Delivery.empty();
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            insertBatch(connection, records);

            log.debug("[{}] {}행 INSERT 완료 — 커밋 보류 상태로 반환", table, records.size());
            return new PendingCommitDelivery(connection, records.size(), table);

        } catch (SQLException e) {
            discard(connection);
            throw JdbcErrorTranslator.translate(
                    "[" + table + "] 적재 준비 실패 (" + records.size() + "행)", e);
        } catch (RuntimeException e) {
            // 우리 쪽 정합성 검사(NonRetryableException 등)로 빠져나가는 경로.
            // 여기서 커넥션을 놓치면 풀이 조용히 말라 간다.
            discard(connection);
            throw e;
        }
    }

    /**
     * 배치 INSERT.
     *
     * <p>행마다 왕복하지 않는다. 63행이면 왕복 63회가 63회의 네트워크 지연으로 쌓이고,
     * 그 지연은 그대로 실시간 SYNC 응답 시간이 된다.
     *
     * <p>{@link #batchSize} 로 끊어 실행하는 것은 드라이버 측 버퍼가 무한정 커지는 것을 막기 위함이다.
     * 끊어도 <b>트랜잭션은 하나</b>이므로 중간에 실패하면 앞의 배치까지 함께 롤백된다.
     */
    private void insertBatch(Connection connection, List<OrderRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            int pending = 0;
            int applied = 0;
            for (OrderRecord record : records) {
                bind(statement, record);
                statement.addBatch();
                if (++pending >= batchSize) {
                    applied += executeBatch(statement, pending);
                    pending = 0;
                }
            }
            if (pending > 0) {
                applied += executeBatch(statement, pending);
            }

            if (applied != records.size()) {
                throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                        "[" + table + "] 적재 건수 불일치 — 요청 " + records.size() + "행, 반영 " + applied + "행");
            }
        }
    }

    /**
     * 배치 실행 결과 검증.
     *
     * <p>반환값을 <b>합산하지 않고 개수를 센다.</b> JDBC 명세상 배치 결과는 영향 행 수일 수도 있지만
     * {@link Statement#SUCCESS_NO_INFO}({@code -2}) 일 수도 있다 — 드라이버가 "성공했지만 몇 행인지는
     * 모른다" 고 답하는 정상 응답이다. 합산하면 이 값이 음수로 섞여 들어가 멀쩡한 적재를
     * 실패로 판정한다.
     */
    private int executeBatch(PreparedStatement statement, int expected) throws SQLException {
        int[] results = statement.executeBatch();

        if (results.length != expected) {
            throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                    "[" + table + "] 배치 결과 개수가 요청과 다르다 — 요청 " + expected + ", 응답 " + results.length);
        }
        for (int i = 0; i < results.length; i++) {
            if (results[i] == Statement.EXECUTE_FAILED) {
                // 값은 담지 않는다. 배치 내 위치만으로 원본 레코드를 찾아갈 수 있다.
                throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                        "[" + table + "] 배치 내 " + i + "번째 행이 실패했다");
            }
        }
        return results.length;
    }

    /**
     * 컬럼 바인딩.
     *
     * <p>{@code CREATE_TIME} 자리는 없다 — {@link #INSERT_TEMPLATE} 참조.
     */
    private void bind(PreparedStatement statement, OrderRecord record) throws SQLException {
        statement.setString(1, record.orderId());
        statement.setString(2, record.applicantKey());
        statement.setString(3, record.userId());
        statement.setString(4, record.itemId());
        statement.setString(5, record.name());
        statement.setString(6, record.address());
        statement.setString(7, record.itemName());
        statement.setString(8, record.price());
        statement.setString(9, record.status());
    }

    /**
     * 준비 도중 실패했을 때의 뒷정리.
     *
     * <p>{@link PendingCommitDelivery} 가 아직 만들어지지 않았으므로 커넥션을 책임질 주체가 없다.
     * 여기서 놓치면 실패할 때마다 풀에서 커넥션이 한 개씩 사라지고, 증상은 한참 뒤
     * "실시간 API 가 갑자기 응답하지 않는다" 로 나타난다.
     */
    private void discard(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException e) {
            log.warn("[{}] 준비 실패 후 롤백에 실패했다 — 커넥션 종료로 정리에 맡긴다", table, e);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.debug("[{}] autoCommit 복원 실패", table, e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("[{}] 커넥션 반납 실패 — 누수 가능성이 있다", table, e);
        }
    }
}
