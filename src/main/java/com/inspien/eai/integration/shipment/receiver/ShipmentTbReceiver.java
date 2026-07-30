package com.inspien.eai.integration.shipment.receiver;

import com.inspien.eai.common.jdbc.JdbcBatches;
import com.inspien.eai.common.jdbc.JdbcErrorTranslator;
import com.inspien.eai.common.jdbc.PendingCommitDelivery;
import com.inspien.eai.common.jdbc.SqlIdentifiers;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.target.OrderStatus;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * IF-SHP-001 Receiver — {@code SHIPMENT_TB} 적재 + {@code ORDER_TB} 상태 갱신.
 *
 * <h2>둘이 한 트랜잭션이다 — 이 인터페이스에서 가장 중요한 한 가지 (정의서 4.4)</h2>
 * <pre>
 *   INSERT INTO SHIPMENT_TB …          (배송 지시 생성)
 *   UPDATE ORDER_TB SET STATUS='Y' …   (전송 완료 표시)
 * </pre>
 *
 * 나누면 반드시 사고가 난다.
 *
 * <table border="1">
 *   <caption>분리했을 때의 결과</caption>
 *   <tr><th>순서</th><th>중간에 실패하면</th><th>결과</th></tr>
 *   <tr><td>INSERT 커밋 → UPDATE 실패</td><td>적재됐는데 {@code STATUS='N'}</td>
 *       <td><b>다음 주기가 같은 주문을 또 보낸다</b> — 이중 배송</td></tr>
 *   <tr><td>UPDATE 커밋 → INSERT 실패</td><td>{@code STATUS='Y'} 인데 배송 지시 없음</td>
 *       <td><b>주문이 영구히 유실된다</b> — 조회 조건에 다시 걸리지 않는다</td></tr>
 * </table>
 *
 * <p>둘 중 어느 쪽도 예외로 드러나지 않는다. 전자는 운송사가 물건을 두 번 보내고,
 * 후자는 고객이 물건을 못 받는다. 그리고 <b>이 환경은 append-only 여서 되돌릴 수 없다.</b>
 *
 * <h2>단일 트랜잭션이 가능한 근거 (D-04 / B3)</h2>
 * {@code ORDER_TB} 와 {@code SHIPMENT_TB} 가 <b>동일 인스턴스·동일 계정</b>임을 실측했고
 * ({@code TargetJdbcConfig} 가 기동할 때마다 다시 확인한다), 그래서 커넥션 하나로 두 문장을
 * 묶을 수 있다. 갈라져 있었다면 여기가 통째로 다시 설계돼야 했다 —
 * 분산 트랜잭션이 아니라 <b>아웃박스 패턴</b>으로 갔을 것이다.
 *
 * <h2>{@code DELETE} 권한이 없는 것이 여기서는 이득이다</h2>
 * 정의서 B10 이 기록한 append-only 제약은 FTP 보상 트랜잭션을 통째로 뒤집게 만든
 * <b>제약</b>이었다(D-21). 그런데 이 자리에서는 <b>보장</b>으로 작동한다.
 *
 * <p>Sender 가 읽은 행은 <b>사라질 수 없다.</b> 아무도 지울 권한이 없기 때문이다.
 * 따라서 PK({@code ORDER_ID}, {@code APPLICANT_KEY})로 지목한 UPDATE 는
 * 정확히 1행에 적용된다. 같은 제약이 한쪽에서는 재설계를 강요하고 다른 쪽에서는
 * 정합성 근거가 된다는 것이, 환경 제약을 실측해 문서에 남겨 둔 값이다.
 *
 * <h2>{@code prepare} 가 커밋하지 않는다 — 수신처가 하나여도</h2>
 * 수신처가 하나뿐이므로 즉시 커밋해도 정합성은 깨지지 않는다. 그럼에도
 * {@link PendingCommitDelivery} 를 돌려주는 이유는 <b>파이프라인 골격을 공유하기 위해서</b>다.
 * 조율자가 준비/확정을 나눠 실행하고 구간마다 이력을 남기는 흐름이 IF-ORD-001 과 같아야,
 * "인터페이스가 늘어도 구조는 하나" 라는 주장이 말이 된다.
 *
 * <p>공짜는 아니다 — 확정 전까지 커넥션을 붙잡는다. 여기서는 준비와 확정 사이에
 * 다른 시스템의 작업이 끼지 않으므로 그 간격이 사실상 0 이고, 대가는 없다고 봐도 된다.
 * 대신 수신처가 하나 늘어나는 날(예: 운송사 API 통보) <b>구조를 고칠 필요가 없다.</b>
 *
 * <h2>PK 위반은 "이미 처리된 건" 이 아니다 (D-22)</h2>
 * 정의서 초판은 멱등성 정책으로 "{@code (SHIPMENT_ID, APPLICANT_KEY)} PK 위반은 이미 처리된
 * 건으로 간주해 스킵" 을 적었다. <b>우리 구조에서는 성립하지 않는다.</b>
 *
 * <p>{@code SHIPMENT_ID} 를 우리가 채번하므로, 같은 주문을 두 번 처리해도 <b>PK 는 다르다</b> —
 * PK 위반으로 걸러질 일이 애초에 없다. 반대로 PK 위반이 실제로 났다면 그것은
 * <b>채번 카운터가 적재 이력보다 뒤에 있다</b>는 뜻이며(Redis 초기화 후 시딩 실패 등),
 * 스킵하면 그 손상을 조용히 덮고 다음 주기에 또 만난다. 그래서 <b>실패시킨다.</b>
 *
 * <p>실제 멱등성 근거는 다른 곳에 있다 — <b>조회 조건 {@code STATUS='N'} + 단일 트랜잭션</b>이다.
 * 커밋이 성공했다면 {@code STATUS='Y'} 이므로 다시 읽히지 않고,
 * 커밋이 실패했다면 배송 지시도 만들어지지 않았다. 둘 사이의 중간 상태가 없다.
 *
 * <h2>값을 손보지 않는다</h2>
 * Mapper 가 이미 결정한 것을 그대로 옮긴다. 여기서 한 글자라도 고치면
 * {@code SHIPMENT_TB.ADDRESS} 와 {@code ORDER_TB.ADDRESS} 가 달라지고,
 * 두 시스템을 대조할 방법이 사라진다.
 */
@Slf4j
public class ShipmentTbReceiver implements Receiver<ShipmentRecord> {

    /**
     * 적재 컬럼 5개. {@code CREATE_DATE} 는 의도적으로 빠져 있다 ({@code DEFAULT SYSDATE}).
     *
     * <p>컬럼명이 {@code ORDER_TB} 의 {@code CREATE_TIME} 과 <b>다르다.</b>
     * 두 테이블을 공통 코드로 뭉뚱그리면 틀리는 지점인데, 양쪽 모두 이 컬럼을
     * 아예 다루지 않으므로 실수가 발생할 자리가 없다.
     */
    private static final String INSERT_TEMPLATE = """
            INSERT INTO %s (
                SHIPMENT_ID, APPLICANT_KEY, ORDER_ID, ITEM_ID, ADDRESS
            ) VALUES (?, ?, ?, ?, ?)""";

    /**
     * 후행 상태 갱신.
     *
     * <p>WHERE 절이 <b>PK 전체</b>다. {@code APPLICANT_KEY} 를 빼면 다른 지원자의 주문까지
     * {@code 'Y'} 로 바꾸고, 그쪽은 자기 주문이 배송되지 않는 이유를 영원히 못 찾는다.
     *
     * <p>{@code AND STATUS='N'} 을 붙이지 않았다. 붙이면 "이미 'Y' 인 행은 건드리지 않는다" 는
     * 보호가 생기는 것처럼 보이지만, 실제로는 <b>0행 갱신이 정상인지 이상인지 구분할 수 없게</b>
     * 만든다. PK 로 지목한 행이 방금 {@code STATUS='N'} 으로 조회된 행이고 아무도 지울 수 없으므로,
     * 조건을 더 붙여서 얻을 것이 없다.
     */
    private static final String UPDATE_TEMPLATE = """
            UPDATE %s
               SET STATUS = ?
             WHERE ORDER_ID = ?
               AND APPLICANT_KEY = ?""";

    private final DataSource dataSource;
    private final String shipmentTable;
    private final String orderTable;
    private final String insertSql;
    private final String updateSql;
    private final String label;
    private final int batchSize;
    private final int queryTimeoutSeconds;

    public ShipmentTbReceiver(DataSource dataSource,
                              String shipmentTable,
                              String orderTable,
                              int batchSize,
                              int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.shipmentTable = SqlIdentifiers.requireSafe(shipmentTable, "적재 테이블명");
        this.orderTable = SqlIdentifiers.requireSafe(orderTable, "상태 갱신 테이블명");
        this.insertSql = INSERT_TEMPLATE.formatted(this.shipmentTable);
        this.updateSql = UPDATE_TEMPLATE.formatted(this.orderTable);
        // 로그 식별자에 두 테이블을 함께 적는다. 트랜잭션이 양쪽에 걸쳐 있다는 사실이
        // 로그만 보고도 드러나야, 커밋 실패의 영향 범위를 오해하지 않는다.
        this.label = this.shipmentTable + "+" + this.orderTable + ".STATUS";
        this.batchSize = batchSize;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public Step step() {
        return Step.RECEIVER_JDBC;
    }

    @Override
    public Delivery prepare(CanonicalMessage<List<ShipmentRecord>> message) {
        List<ShipmentRecord> records = message.payload();
        if (records == null || records.isEmpty()) {
            // 미전송 주문이 없거나 전건이 스킵된 경우다. 트랜잭션을 열 이유가 없다.
            log.debug("[{}] 전송 대상 0건 — 트랜잭션을 열지 않는다", label);
            return Delivery.empty();
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);

            insertShipments(connection, records);
            markOrdersSent(connection, records);

            log.debug("[{}] INSERT {}행 + STATUS 갱신 {}행 완료 — 커밋 보류 상태로 반환",
                    label, records.size(), records.size());
            return new PendingCommitDelivery(connection, records.size(), label);

        } catch (SQLException e) {
            discard(connection);
            throw JdbcErrorTranslator.translate(
                    "[" + label + "] 전송 준비 실패 (" + records.size() + "행)", e);
        } catch (RuntimeException e) {
            // 우리 쪽 정합성 검사로 빠져나가는 경로. 커넥션을 놓치면 풀이 조용히 말라 간다.
            discard(connection);
            throw e;
        }
    }

    /**
     * {@code SHIPMENT_TB} 배치 INSERT.
     *
     * <p>{@link JdbcBatches} 가 결과를 판정한다 — 반환값을 합산하지 않고 개수로 세는 이유는 그쪽 참조.
     */
    private void insertShipments(Connection connection, List<ShipmentRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            int pending = 0;
            int applied = 0;
            for (ShipmentRecord record : records) {
                statement.setString(1, record.shipmentId());
                statement.setString(2, record.applicantKey());
                statement.setString(3, record.orderId());
                statement.setString(4, record.itemId());
                statement.setString(5, record.address());
                statement.addBatch();

                if (++pending >= batchSize) {
                    applied += JdbcBatches.execute(statement, shipmentTable, pending);
                    pending = 0;
                }
            }
            if (pending > 0) {
                applied += JdbcBatches.execute(statement, shipmentTable, pending);
            }

            if (applied != records.size()) {
                throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                        "[" + shipmentTable + "] 적재 건수 불일치 — 요청 " + records.size()
                                + "행, 반영 " + applied + "행");
            }
        }
    }

    /**
     * {@code ORDER_TB.STATUS} 를 {@code 'Y'} 로 갱신한다. <b>같은 커넥션, 같은 트랜잭션이다.</b>
     *
     * <h2>{@code APPLICANT_KEY} 를 레코드에서 꺼내 쓴다</h2>
     * 별도로 주입받지 않는 이유는 <b>방금 INSERT 한 값과 같아야</b> 하기 때문이다.
     * 두 경로로 들어오면 어긋날 여지가 생기고, 어긋나면 배송 지시는 만들어졌는데
     * 상태는 그대로인 행이 남는다 — 같은 객체에서 꺼내면 그 여지가 없다.
     *
     * <h2>"몇 행이 갱신됐는가" 를 세지 않는 이유</h2>
     * Oracle 드라이버는 배치 원소로 {@link java.sql.Statement#SUCCESS_NO_INFO}({@code -2})를
     * 흔히 돌려준다. <b>영향 행 수를 알려 주지 않는다는 뜻</b>이므로, "정확히 1행" 을
     * 확인하고 싶어도 확인할 방법이 없다. 문장마다 {@code executeUpdate()} 로 바꾸면
     * 셀 수 있지만 왕복이 청크 크기만큼 늘어난다.
     *
     * <p>그 대가를 치르지 않는 근거는 셋이다.
     * <ol>
     *   <li>{@code STATUS} 를 바꾸는 주체가 <b>이 배치뿐</b>이고, 배치는 분산 락으로 하나만 돈다</li>
     *   <li>IF-ORD-001 은 새 행을 INSERT 할 뿐 기존 행의 상태를 건드리지 않는다</li>
     *   <li>대상 환경에 {@code DELETE} 권한이 없으므로 <b>조회한 행이 사라질 수 없다</b></li>
     * </ol>
     * 즉 PK 로 지목한 행은 반드시 존재하고 반드시 {@code 'N'} 이다.
     * 이 전제 중 하나라도 깨지면(다중 인스턴스 운영 등) 여기를 다시 설계해야 하며,
     * 그때는 상태 선점({@code N}→{@code P}) 패턴이 답이다.
     *
     * <p>대신 <b>문장 개수</b>는 검증한다. 배치에 쌓은 수와 응답 개수가 다르거나
     * 실패한 문장이 섞여 있으면 {@link JdbcBatches} 가 실패시키고, 그 실패는
     * INSERT 와 함께 롤백된다.
     */
    private void markOrdersSent(Connection connection, List<ShipmentRecord> records) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            int pending = 0;
            int applied = 0;
            for (ShipmentRecord record : records) {
                statement.setString(1, OrderStatus.SENT.code());
                statement.setString(2, record.orderId());
                statement.setString(3, record.applicantKey());
                statement.addBatch();

                if (++pending >= batchSize) {
                    applied += JdbcBatches.execute(statement, orderTable, pending);
                    pending = 0;
                }
            }
            if (pending > 0) {
                applied += JdbcBatches.execute(statement, orderTable, pending);
            }

            if (applied != records.size()) {
                throw new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                        "[" + orderTable + "] 상태 갱신 문장 수 불일치 — 요청 " + records.size()
                                + "건, 응답 " + applied + "건");
            }
        }
    }

    /**
     * 준비 도중 실패했을 때의 뒷정리.
     *
     * <p>{@link PendingCommitDelivery} 가 아직 만들어지지 않았으므로 커넥션을 책임질 주체가 없다.
     * 여기서 놓치면 실패할 때마다 풀에서 커넥션이 하나씩 사라지고, 증상은 한참 뒤
     * "배치가 5분마다 조용히 실패한다" 로 나타난다.
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
            log.warn("[{}] 준비 실패 후 롤백에 실패했다 — 커넥션 종료로 정리에 맡긴다", label, e);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.debug("[{}] autoCommit 복원 실패", label, e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("[{}] 커넥션 반납 실패 — 누수 가능성이 있다", label, e);
        }
    }
}
