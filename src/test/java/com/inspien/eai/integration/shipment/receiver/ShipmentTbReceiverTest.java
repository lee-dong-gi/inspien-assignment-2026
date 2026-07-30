package com.inspien.eai.integration.shipment.receiver;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 Oracle 없이 검증한다.
 *
 * <p>이 Receiver 가 지켜야 할 성질 중 가장 중요한 것은 <b>INSERT 와 STATUS 갱신이
 * 한 트랜잭션이라는 것</b>인데, 그것은 "커넥션을 몇 개 얻었고 커밋을 언제 불렀는가" 의 문제다.
 * 원격 DB 를 띄워 놓고는 <b>"아직 커밋하지 않았다" 를 관측할 수단이 없다.</b>
 * 커넥션을 대역으로 세우면 그것이 곧 단언 대상이 된다.
 *
 * <p>게다가 대상 환경은 append-only 이고 지원자 공유다 — 실 DB 로 실패 경로를 재현하면
 * <b>되돌릴 수 없는 행이 쌓인다.</b> 이 클래스가 대역을 쓰는 것은 편의가 아니라 제약이다.
 *
 * <h2>배선을 두 종류로 나눈 이유</h2>
 * {@code wireInsertOnly()} 는 UPDATE 문장을 미리 준비하지 않는다. Mockito 의 엄격 모드가
 * <b>쓰이지 않은 대역 배선을 실패로 처리</b>하기 때문인데, 이것이 오히려 도움이 된다 —
 * "적재가 실패하면 상태 갱신을 시도조차 하지 않는다" 는 성질이 배선 자체로 고정된다.
 * 나중에 누군가 실패 경로에서 UPDATE 를 준비하게 만들면 그 테스트가 깨진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShipmentTbReceiver — SHIPMENT_TB 적재 + ORDER_TB 상태 갱신")
class ShipmentTbReceiverTest {

    private static final String SHIPMENT_TABLE = "SHIPMENT_TB";
    private static final String ORDER_TABLE = "ORDER_TB";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement insert;
    @Mock
    private PreparedStatement update;

    private ShipmentTbReceiver receiver(int batchSize) {
        return new ShipmentTbReceiver(dataSource, SHIPMENT_TABLE, ORDER_TABLE, batchSize, 20);
    }

    @Test
    @DisplayName("구간 이름은 RECEIVER_JDBC 다 — 상태 갱신을 별도 구간으로 두지 않는다 (D-24)")
    void reportsJdbcStep() {
        assertEquals(Step.RECEIVER_JDBC, receiver(500).step());
    }

    // ------------------------------------------------------------------ 단일 트랜잭션 (정의서 4.4)

    @Test
    @DisplayName("적재와 상태 갱신을 같은 커넥션에서 하고 커밋하지 않는다 — 나누면 이중 배송 아니면 주문 유실이다")
    void insertsAndUpdatesInOneUncommittedTransaction() throws Exception {
        wireHappyPath(2);

        Delivery delivery = receiver(500).prepare(message(records(2)));

        assertAll(
                () -> assertEquals(2, delivery.count()),
                // 커넥션이 하나라는 것이 '한 트랜잭션' 의 유일한 근거다 (D-04 / B3 실측).
                () -> verify(dataSource, times(1)).getConnection(),
                () -> verify(connection).setAutoCommit(false),
                () -> verify(insert, times(1)).executeBatch(),
                () -> verify(update, times(1)).executeBatch(),
                () -> verify(connection, never()).commit(),
                () -> verify(connection, never()).close());
    }

    @Test
    @DisplayName("INSERT 를 먼저, 상태 갱신을 나중에 한다 — 순서가 뒤집히면 되돌릴 대상이 달라진다")
    void insertsBeforeMarkingSent() throws Exception {
        wireHappyPath(1);

        receiver(500).prepare(message(records(1)));

        var order = inOrder(connection);
        order.verify(connection).prepareStatement(startsWith("INSERT"));
        order.verify(connection).prepareStatement(startsWith("UPDATE"));
    }

    @Test
    @DisplayName("배치 크기를 넘으면 나눠 실행하되 트랜잭션은 여전히 하나다")
    void splitsIntoBatchesWithinOneTransaction() throws Exception {
        wireConnection();
        given(insert.executeBatch()).willReturn(new int[]{1, 1}, new int[]{1, 1}, new int[]{1});
        given(update.executeBatch()).willReturn(new int[]{1, 1}, new int[]{1, 1}, new int[]{1});

        Delivery delivery = receiver(2).prepare(message(records(5)));

        assertAll(
                () -> assertEquals(5, delivery.count()),
                () -> verify(insert, times(3)).executeBatch(),
                () -> verify(update, times(3)).executeBatch(),
                () -> verify(dataSource, times(1)).getConnection(),
                () -> verify(connection, never()).commit());
    }

    // ------------------------------------------------------------------ SQL 모양

    @Test
    @DisplayName("적재 컬럼은 5개이고 CREATE_DATE 는 목록에 없다 — DEFAULT SYSDATE 로 DB 서버가 찍는다")
    void doesNotInsertCreateDate() throws Exception {
        wireHappyPath(1);

        receiver(500).prepare(message(records(1)));

        String sql = capturedSql("INSERT");
        assertAll(
                () -> assertFalse(sql.contains("CREATE_DATE"),
                        "등록 시각은 우리 데이터가 아니라 수신 시스템의 기록이다"),
                // ORDER_TB 는 CREATE_TIME, SHIPMENT_TB 는 CREATE_DATE 다. 뭉뚱그리면 틀리는 지점이다.
                () -> assertFalse(sql.contains("CREATE_TIME")),
                () -> assertEquals(5, countPlaceholders(sql)),
                () -> assertTrue(sql.contains("INSERT INTO " + SHIPMENT_TABLE)));
    }

    @Test
    @DisplayName("NAME·ITEM_NAME·PRICE·STATUS 를 운송사 테이블로 넘기지 않는다 — 변환은 취사선택이다")
    void carriesOnlyWhatCarrierNeeds() throws Exception {
        wireHappyPath(1);

        receiver(500).prepare(message(records(1)));

        String sql = capturedSql("INSERT");
        assertAll(
                () -> assertFalse(sql.contains("NAME"), "이름은 배송 지시에 필요하지 않다"),
                () -> assertFalse(sql.contains("PRICE"),
                        "가격을 넘기면 운송사 시스템이 쇼핑몰의 가격 정책에 결합된다"),
                () -> assertFalse(sql.contains("USER_ID")));
    }

    @Test
    @DisplayName("상태 갱신의 WHERE 절은 PK 전체다 — APPLICANT_KEY 를 빼면 다른 지원자의 주문을 닫는다")
    void updatesByFullPrimaryKey() throws Exception {
        wireHappyPath(1);

        receiver(500).prepare(message(records(1)));

        String sql = capturedSql("UPDATE");
        assertAll(
                () -> assertTrue(sql.contains("UPDATE " + ORDER_TABLE)),
                () -> assertTrue(sql.contains("ORDER_ID = ?")),
                () -> assertTrue(sql.contains("APPLICANT_KEY = ?")),
                // SET 1개 + WHERE 2개. AND STATUS='N' 을 붙이면 0행 갱신의 정상/이상을 구분할 수 없다.
                () -> assertEquals(3, countPlaceholders(sql)));
    }

    // ------------------------------------------------------------------ 바인딩

    @Test
    @DisplayName("적재 값을 SHIPMENT_TB 컬럼 순서로 바인딩하고 손보지 않는다")
    void bindsShipmentColumnsInTableOrder() throws Exception {
        wireHappyPath(1);
        ShipmentRecord record = new ShipmentRecord("B001", "KEY00001", "A113", "ITEM1", "서울특별시 금천구");

        receiver(500).prepare(message(List.of(record)));

        var order = inOrder(insert);
        order.verify(insert).setString(1, "B001");
        order.verify(insert).setString(2, "KEY00001");
        order.verify(insert).setString(3, "A113");
        order.verify(insert).setString(4, "ITEM1");
        // 한 글자라도 고치면 SHIPMENT_TB.ADDRESS 와 ORDER_TB.ADDRESS 를 대조할 수 없다.
        order.verify(insert).setString(5, "서울특별시 금천구");
        order.verify(insert).addBatch();
        verify(insert, never()).setString(eq(6), anyString());
    }

    @Test
    @DisplayName("상태 값은 리터럴이 아니라 OrderStatus.SENT 에서 온다 — 조회 조건과 같은 어휘를 쓴다")
    void bindsSentStatusFromSharedVocabulary() throws Exception {
        wireHappyPath(1);
        ShipmentRecord record = new ShipmentRecord("B001", "KEY00001", "A113", "ITEM1", "서울특별시 금천구");

        receiver(500).prepare(message(List.of(record)));

        var order = inOrder(update);
        order.verify(update).setString(1, "Y");
        order.verify(update).setString(2, "A113");
        // 별도로 주입받지 않고 레코드에서 꺼낸다 — 방금 INSERT 한 값과 어긋날 여지를 없앤다.
        order.verify(update).setString(3, "KEY00001");
    }

    // ------------------------------------------------------------------ 실패 경로

    @Test
    @DisplayName("적재가 실패하면 상태 갱신을 시도조차 하지 않고 롤백·반납한다")
    void neverMarksSentWhenInsertFails() throws Exception {
        // UPDATE 문장을 배선조차 하지 않는다. 준비되면 엄격 모드가 '쓰이지 않은 배선' 으로 실패시키므로,
        // 이 배선 자체가 "실패 경로에서 UPDATE 로 넘어가지 않는다" 는 단언이 된다.
        wireInsertOnly();
        willThrow(new SQLSyntaxErrorException("ORA-00942: table or view does not exist", "42000", 942))
                .given(insert).executeBatch();

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(2))));

        assertAll(
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> verify(connection, never()).prepareStatement(startsWith("UPDATE")),
                () -> verify(connection).rollback(),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("상태 갱신이 실패하면 적재까지 함께 취소된다 — 'STATUS=N 인데 적재됨' 은 이중 배송이다")
    void rollsBackInsertWhenStatusUpdateFails() throws Exception {
        wireConnection();
        given(insert.executeBatch()).willReturn(new int[]{1, 1});
        willThrow(new SQLSyntaxErrorException("ORA-00904: invalid identifier", "42000", 904))
                .given(update).executeBatch();

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(2))));

        var order = inOrder(connection);
        assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode());
        order.verify(connection).rollback();
        order.verify(connection).close();
    }

    @Test
    @DisplayName("PK 위반은 '이미 처리된 건' 이 아니라 실패다 (D-22) — 채번 카운터 손상을 덮으면 안 된다")
    void treatsConstraintViolationAsFailure() throws Exception {
        wireInsertOnly();
        willThrow(new SQLIntegrityConstraintViolationException(
                "ORA-00001: unique constraint violated", "23000", 1))
                .given(insert).executeBatch();

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(1))));

        assertAll(
                // SHIPMENT_ID 를 우리가 채번하므로 PK 위반은 '중복 처리' 가 아니라 카운터 손상이다.
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> assertFalse(e.errorCode().retryable(), "같은 값으로 몇 번을 해도 같다"),
                () -> verify(connection).rollback(),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("SUCCESS_NO_INFO(-2) 는 성공이다 — 합산하면 멀쩡한 적재가 실패로 뒤집힌다")
    void treatsSuccessNoInfoAsSuccess() throws Exception {
        wireConnection();
        given(insert.executeBatch())
                .willReturn(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});
        given(update.executeBatch())
                .willReturn(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});

        Delivery delivery = receiver(500).prepare(message(records(2)));

        assertEquals(2, delivery.count());
    }

    @Test
    @DisplayName("배치 응답 개수가 요청과 다르면 EAI-2002 로 끊는다 — 문장 개수는 셀 수 있다")
    void failsWhenBatchResponseCountDiffers() throws Exception {
        wireConnection();
        given(insert.executeBatch()).willReturn(new int[]{1, 1});
        given(update.executeBatch()).willReturn(new int[]{1});

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(2))));

        assertAll(
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> verify(connection).rollback(),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("커넥션 일시 단절은 재시도 가능(EAI-2001) — 다음 주기가 이어받는다")
    void classifiesConnectionLossAsRetryable() throws Exception {
        given(dataSource.getConnection()).willThrow(new SQLRecoverableException("ORA-17002: IO Error"));

        RetryableException e = assertThrows(RetryableException.class,
                () -> receiver(500).prepare(message(records(1))));

        assertEquals(EaiErrorCode.JDBC_CONN_ERROR, e.errorCode());
    }

    @Test
    @DisplayName("전건이 스킵돼 0건이면 커넥션조차 얻지 않는다")
    void emptyPayloadOpensNoTransaction() throws Exception {
        Delivery delivery = receiver(500).prepare(message(List.of()));

        assertAll(
                () -> assertEquals(0, delivery.count()),
                () -> verify(dataSource, never()).getConnection());

        // 확정·보상을 불러도 아무 일이 없어야 한다. 조율자가 0건을 특별 취급할 필요가 없다.
        delivery.commit();
        delivery.compensate();
    }

    @Test
    @DisplayName("두 테이블명 모두 규격 밖이면 조립 시점에 거부한다 — SQL 에 직접 끼워 넣는 유일한 값이다")
    void rejectsUnsafeTableNames() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ShipmentTbReceiver(dataSource, "SHIPMENT_TB; DROP TABLE X", ORDER_TABLE, 500, 20)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ShipmentTbReceiver(dataSource, SHIPMENT_TABLE, "ORDER_TB--", 500, 20)));
    }

    // ── 대역 배선 ───────────────────────────────────────────────

    /** 적재까지만 가는 경로. UPDATE 문장은 준비되지 않아야 한다 */
    private void wireInsertOnly() throws Exception {
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.prepareStatement(startsWith("INSERT"))).willReturn(insert);
    }

    private void wireConnection() throws Exception {
        wireInsertOnly();
        given(connection.prepareStatement(startsWith("UPDATE"))).willReturn(update);
    }

    private void wireHappyPath(int rows) throws Exception {
        wireConnection();
        given(insert.executeBatch()).willReturn(allApplied(rows));
        given(update.executeBatch()).willReturn(allApplied(rows));
    }

    private static int[] allApplied(int rows) {
        int[] results = new int[rows];
        Arrays.fill(results, 1);
        return results;
    }

    private String capturedSql(String prefix) throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());

        return sql.getAllValues().stream()
                .filter(statement -> statement.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(prefix + " 문장이 준비되지 않았다: " + sql.getAllValues()));
    }

    private CanonicalMessage<List<ShipmentRecord>> message(List<ShipmentRecord> records) {
        return new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_SHP_001), records);
    }

    private List<ShipmentRecord> records(int count) {
        List<ShipmentRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(new ShipmentRecord(
                    "B%03d".formatted(i), "KEY00001", "A%03d".formatted(i), "ITEM" + i, "서울특별시 금천구"));
        }
        return records;
    }

    private int countPlaceholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }
}
