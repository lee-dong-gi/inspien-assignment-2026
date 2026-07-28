package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.integration.order.target.OrderRecord;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 Oracle 없이 검증한다.
 *
 * <p>이 Receiver 가 지켜야 할 성질 — <b>커밋하지 않는다 · CREATE_TIME 을 넣지 않는다 ·
 * 실패해도 커넥션을 놓지 않는다</b> — 는 전부 JDBC 호출 순서의 문제이지 DB 의 문제가 아니다.
 * 원격 DB 를 띄워 놓고 확인하면 느리고, 무엇보다 <b>"커밋을 안 했다" 를 관측할 수단이 없다.</b>
 * 커넥션을 대역으로 세우면 그것이 바로 단언 대상이 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderTbReceiver — ORDER_TB 적재 (커밋 보류)")
class OrderTbReceiverTest {

    private static final String TABLE = "ORDER_TB";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;

    private OrderTbReceiver receiver(int batchSize) {
        return new OrderTbReceiver(dataSource, TABLE, batchSize, 20);
    }

    @Test
    @DisplayName("구간 이름은 RECEIVER_JDBC 다 — 실패 시 어느 시스템 담당인지 로그로 특정된다")
    void reportsJdbcStep() {
        assertEquals(Step.RECEIVER_JDBC, receiver(500).step());
    }

    @Test
    @DisplayName("prepare 는 INSERT 까지만 하고 커밋하지 않는다 (정의서 3.9 - 1단계)")
    void prepareDoesNotCommit() throws Exception {
        wireHappyPath(new int[]{1, 1});

        Delivery delivery = receiver(500).prepare(message(records(2)));

        assertAll(
                () -> assertEquals(2, delivery.count()),
                () -> verify(connection).setAutoCommit(false),
                () -> verify(statement, times(1)).executeBatch(),
                () -> verify(connection, never()).commit(),
                () -> verify(connection, never()).close());
    }

    @Test
    @DisplayName("CREATE_TIME 은 INSERT 목록에 없다 — DEFAULT SYSDATE 로 DB 서버가 찍는다")
    void doesNotInsertCreateTime() throws Exception {
        wireHappyPath(new int[]{1});

        receiver(500).prepare(message(records(1)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());

        String insert = sql.getValue();
        assertAll(
                () -> assertFalse(insert.contains("CREATE_TIME"),
                        "CREATE_TIME 을 직접 채우면 DB 서버 시계와 어긋나 D-06 시연 조회가 깨진다"),
                () -> assertEquals(9, countPlaceholders(insert), "적재 컬럼은 9개다"),
                () -> assertTrue(insert.contains("INSERT INTO " + TABLE)));
    }

    @Test
    @DisplayName("9개 컬럼을 ORDER_TB 컬럼 순서로 바인딩한다 — 파일 필드 순서와 다르다")
    void bindsColumnsInTableOrder() throws Exception {
        wireHappyPath(new int[]{1});
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울특별시 금천구", "청바지", "21000", "N");

        receiver(500).prepare(message(List.of(record)));

        var order = inOrder(statement);
        order.verify(statement).setString(1, "A113");
        order.verify(statement).setString(2, "KEY00001");
        order.verify(statement).setString(3, "USER1");
        order.verify(statement).setString(4, "ITEM1");
        order.verify(statement).setString(5, "홍길동");
        order.verify(statement).setString(6, "서울특별시 금천구");
        order.verify(statement).setString(7, "청바지");
        order.verify(statement).setString(8, "21000");
        order.verify(statement).setString(9, "N");
        order.verify(statement).addBatch();
        verify(statement, never()).setString(eq(10), anyString());
    }

    @Test
    @DisplayName("PRICE 는 문자열 그대로 바인딩한다 — 컬럼이 VARCHAR2 다")
    void bindsPriceAsString() throws Exception {
        wireHappyPath(new int[]{1});
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지", "021000", "N");

        receiver(500).prepare(message(List.of(record)));

        verify(statement).setString(8, "021000");
        verify(statement, never()).setInt(anyInt(), anyInt());
        verify(statement, never()).setLong(anyInt(), anyLong());
    }

    @Test
    @DisplayName("배치 크기를 넘으면 나눠 실행하되 트랜잭션은 하나다")
    void splitsIntoBatchesWithinOneTransaction() throws Exception {
        wireConnection();
        given(statement.executeBatch()).willReturn(new int[]{1, 1}, new int[]{1, 1}, new int[]{1});

        Delivery delivery = receiver(2).prepare(message(records(5)));

        assertAll(
                () -> assertEquals(5, delivery.count()),
                () -> verify(statement, times(3)).executeBatch(),
                () -> verify(dataSource, times(1)).getConnection(),
                () -> verify(connection, never()).commit());
    }

    @Test
    @DisplayName("SUCCESS_NO_INFO(-2) 는 성공이다 — 합산하면 멀쩡한 적재가 실패로 뒤집힌다")
    void treatsSuccessNoInfoAsSuccess() throws Exception {
        wireHappyPath(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});

        Delivery delivery = receiver(500).prepare(message(records(2)));

        assertEquals(2, delivery.count());
    }

    @Test
    @DisplayName("배치 중 한 행이라도 실패하면 EAI-2002 로 끊고 커넥션을 반납한다")
    void failsWhenAnyRowRejected() throws Exception {
        wireHappyPath(new int[]{1, Statement.EXECUTE_FAILED});

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(2))));

        assertAll(
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> verify(connection).rollback(),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("PK 위반은 재시도 불가로 분류한다 — 몇 번을 해도 같은 값이다")
    void classifiesConstraintViolationAsNonRetryable() throws Exception {
        wireConnection();
        willThrow(new SQLIntegrityConstraintViolationException("ORA-00001: unique constraint violated", "23000", 1))
                .given(statement).executeBatch();

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver(500).prepare(message(records(1))));

        assertAll(
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("D-09"), "채번 시딩을 확인하라는 안내가 있어야 한다"),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("커넥션 일시 단절은 재시도 가능(EAI-2001)")
    void classifiesConnectionLossAsRetryable() throws Exception {
        given(dataSource.getConnection()).willThrow(new SQLRecoverableException("ORA-17002: IO Error"));

        RetryableException e = assertThrows(RetryableException.class,
                () -> receiver(500).prepare(message(records(1))));

        assertEquals(EaiErrorCode.JDBC_CONN_ERROR, e.errorCode());
    }

    @Test
    @DisplayName("준비 도중 어떤 실패든 커넥션을 반납한다 — 누수는 한참 뒤에 응답 지연으로 나타난다")
    void alwaysReleasesConnectionOnFailure() throws Exception {
        wireConnection();
        willThrow(new SQLSyntaxErrorException("ORA-00942: table or view does not exist", "42000", 942))
                .given(statement).executeBatch();

        assertThrows(NonRetryableException.class, () -> receiver(500).prepare(message(records(1))));

        var order = inOrder(connection);
        order.verify(connection).rollback();
        order.verify(connection).close();
    }

    @Test
    @DisplayName("0건이면 커넥션조차 얻지 않는다")
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
    @DisplayName("테이블명이 규격 밖이면 조립 시점에 거부한다 — 유일하게 SQL 에 끼워 넣는 값이다")
    void rejectsUnsafeTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderTbReceiver(dataSource, "ORDER_TB; DROP TABLE X", 500, 20));
    }

    // ── 대역 배선 ───────────────────────────────────────────────

    private void wireConnection() throws Exception {
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.prepareStatement(anyString())).willReturn(statement);
    }

    private void wireHappyPath(int[] batchResult) throws Exception {
        wireConnection();
        given(statement.executeBatch()).willReturn(batchResult);
    }

    private CanonicalMessage<List<OrderRecord>> message(List<OrderRecord> records) {
        return new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_ORD_001), records);
    }

    private List<OrderRecord> records(int count) {
        List<OrderRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(new OrderRecord(
                    "A%03d".formatted(i), "KEY00001", "USER" + i, "ITEM" + i,
                    "홍길동", "서울특별시 금천구", "청바지", "21000", "N"));
        }
        return records;
    }

    private int countPlaceholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }
}
