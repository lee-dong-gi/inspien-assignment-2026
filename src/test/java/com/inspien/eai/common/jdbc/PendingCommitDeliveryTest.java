package com.inspien.eai.common.jdbc;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 보상 트랜잭션의 DB 쪽 절반을 검증한다 (정의서 3.9).
 *
 * <p>여기서 확인하는 것은 SQL 이 아니라 <b>커넥션 수명 관리</b>다. 확정·보상이 각각 한 번만
 * 일어나는가, 어떤 경로로 끝나든 커넥션이 반납되는가, 보상 실패가 원래의 실패를 덮지 않는가.
 * 전부 실제 DB 로는 관측하기 어렵고 대역으로는 그대로 단언이 되는 성질들이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingCommitDelivery — 커밋 보류 트랜잭션")
class PendingCommitDeliveryTest {

    private static final String LABEL = "ORDER_TB";

    @Mock
    private Connection connection;

    private PendingCommitDelivery delivery(int count) {
        return new PendingCommitDelivery(connection, count, LABEL);
    }

    @Test
    @DisplayName("건수는 준비된 행 수 그대로다")
    void reportsPreparedCount() {
        assertEquals(63, delivery(63).count());
    }

    @Test
    @DisplayName("확정하면 커밋하고 커넥션을 반납한다 — 반납은 커밋 뒤에 온다")
    void commitThenRelease() throws Exception {
        PendingCommitDelivery target = delivery(2);

        target.commit();

        var order = inOrder(connection);
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        order.verify(connection).close();
        assertEquals(PendingCommitDelivery.State.COMMITTED, target.state());
    }

    @Test
    @DisplayName("보상하면 롤백하고 커넥션을 반납한다")
    void compensateThenRelease() throws Exception {
        PendingCommitDelivery target = delivery(2);

        target.compensate();

        var order = inOrder(connection);
        order.verify(connection).rollback();
        order.verify(connection).close();
        assertAll(
                () -> assertEquals(PendingCommitDelivery.State.COMPENSATED, target.state()),
                () -> verify(connection, never()).commit());
    }

    @Test
    @DisplayName("확정을 두 번 호출해도 두 번 커밋하지 않는다")
    void commitIsIdempotent() throws Exception {
        PendingCommitDelivery target = delivery(2);

        target.commit();
        target.commit();

        assertAll(
                () -> verify(connection, times(1)).commit(),
                () -> verify(connection, times(1)).close());
    }

    @Test
    @DisplayName("이미 확정된 것은 보상하지 않는다 — 조율자 계약 ③")
    void doesNotCompensateAfterCommit() throws Exception {
        PendingCommitDelivery target = delivery(2);

        target.commit();
        target.compensate();

        assertAll(
                () -> verify(connection, never()).rollback(),
                () -> verify(connection, times(1)).close(),
                () -> assertEquals(PendingCommitDelivery.State.COMMITTED, target.state()));
    }

    @Test
    @DisplayName("커밋 실패는 EAI-2002 로 올리되 커넥션은 반드시 반납한다")
    void translatesCommitFailure() throws Exception {
        willThrow(new SQLException("ORA-00060: deadlock detected", "61000", 60))
                .given(connection).commit();
        PendingCommitDelivery target = delivery(2);

        NonRetryableException e = assertThrows(NonRetryableException.class, target::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.JDBC_EXEC_ERROR, e.errorCode()),
                () -> verify(connection).close(),
                () -> assertEquals(PendingCommitDelivery.State.FAILED, target.state()));
    }

    @Test
    @DisplayName("커밋 시점의 일시 단절은 재시도 가능(EAI-2001)으로 분류한다")
    void classifiesRecoverableCommitFailure() throws Exception {
        willThrow(new SQLRecoverableException("ORA-17002: IO Error")).given(connection).commit();

        RetryableException e = assertThrows(RetryableException.class, delivery(2)::commit);

        assertEquals(EaiErrorCode.JDBC_CONN_ERROR, e.errorCode());
    }

    @Test
    @DisplayName("보상 실패는 예외를 던지지 않는다 — 원래의 실패 원인을 덮지 않기 위해서다")
    void compensationFailureNeverThrows() throws Exception {
        willThrow(new SQLException("connection closed")).given(connection).rollback();
        PendingCommitDelivery target = delivery(2);

        assertDoesNotThrow(target::compensate);

        assertAll(
                // 조용히 넘기는 것이 아니다 — 상태로 남고 로그에 EAI-2002 로 기록된다.
                () -> assertEquals(PendingCommitDelivery.State.FAILED, target.state()),
                () -> verify(connection).close());
    }

    @Test
    @DisplayName("반납 중 close 가 실패해도 확정 결과를 뒤집지 않는다")
    void releaseFailureDoesNotUndoCommit() throws Exception {
        willThrow(new SQLException("already closed")).given(connection).close();
        PendingCommitDelivery target = delivery(2);

        assertDoesNotThrow(target::commit);

        assertEquals(PendingCommitDelivery.State.COMMITTED, target.state(),
                "커밋은 이미 성공했다. 반납 실패로 그 사실을 되돌릴 수는 없다");
    }
}
