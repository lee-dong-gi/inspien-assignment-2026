package com.inspien.eai.common.jdbc;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.receiver.Delivery;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 커밋을 보류한 채 열려 있는 DB 트랜잭션 — {@link Delivery} 의 JDBC 구현.
 *
 * <p>INSERT 는 이미 끝났고 {@code commit} 만 남은 상태를 <b>객체로 붙잡아 둔다.</b>
 * 이 객체가 살아 있는 동안 FTP 업로드가 진행되고, 그것이 성공해야 비로소 {@link #commit()} 이
 * 불린다. 정의서 3.9 의 보상 트랜잭션에서 1단계와 3단계 사이의 간격이 곧 이 객체의 수명이다.
 *
 * <h2>왜 {@code @Transactional} 이나 {@code TransactionTemplate} 이 아닌가</h2>
 * 둘 다 <b>트랜잭션 경계가 하나의 메서드 안에 닫혀 있다</b>는 전제 위에 서 있다.
 * 그런데 우리가 필요한 경계는 {@code prepare()} 에서 시작해 {@code commit()} 에서 끝나며,
 * 그 사이에 <b>DB 가 아닌 시스템(FTP)의 작업이 끼어든다.</b>
 * 콜백 안에 FTP 업로드를 밀어 넣어 흉내 낼 수는 있지만, 그러면 Receiver 하나가
 * 다른 Receiver 를 호출하는 구조가 되어 "수신처가 늘어도 Receiver 만 추가한다" 는 설계가 무너진다.
 *
 * <p>Spring 의 {@code PlatformTransactionManager} 를 직접 잡고 {@code TransactionStatus} 를
 * 들고 다니는 방법도 있다. 쓰지 않은 이유는 그 방식이 트랜잭션을 <b>ThreadLocal 에</b> 묶기
 * 때문이다. {@code prepare} 와 {@code commit} 이 반드시 같은 스레드에서 불려야 한다는
 * 제약이 타입에 드러나지 않은 채 생기고, 나중에 FTP 업로드를 비동기로 돌리는 순간
 * 컴파일도 테스트도 통과한 채 조용히 깨진다. 커넥션을 필드로 들고 있으면 그 결합이 눈에 보인다.
 *
 * <h2>대가 — 커넥션 점유</h2>
 * 이 설계는 <b>FTP 업로드가 끝날 때까지 DB 커넥션을 붙잡는다.</b> 공짜가 아니다.
 * 그래서 풀 크기를 1로 두지 않고({@link JdbcTargetProperties#maximumPoolSize()}),
 * FTP 쪽 타임아웃을 반드시 명시한다 — FTP 가 무한 대기하면 그동안 DB 커넥션도 함께 묶인다.
 * 이 결합을 없애려면 트랜잭셔널 아웃박스로 가야 하고, 그건 과제 범위를 넘는다(정의서 3.9).
 *
 * <h2>상태를 갖는 이유</h2>
 * 확정·보상은 <b>각각 한 번만</b> 일어나야 한다. 조율자가 실패 처리 중에 보상을 중복 호출하는 것은
 * 흔한 사고이고, 그때 이미 닫힌 커넥션에 다시 {@code rollback} 을 걸면 원래의 실패 원인이
 * 엉뚱한 예외로 덮인다. 상태를 두어 두 번째 호출을 <b>조용히가 아니라 기록하면서</b> 무시한다.
 */
@Slf4j
public final class PendingCommitDelivery implements Delivery {

    private final Connection connection;
    private final int count;
    private final String label;

    private State state = State.PREPARED;

    /**
     * @param connection {@code autoCommit=false} 이고 INSERT 가 끝난 커넥션.
     *                   이 시점부터 커넥션의 수명은 이 객체가 책임진다
     * @param label      로그 식별용 이름 (예: {@code ORDER_TB})
     */
    public PendingCommitDelivery(Connection connection, int count, String label) {
        this.connection = connection;
        this.count = count;
        this.label = label;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public void commit() {
        if (state != State.PREPARED) {
            log.warn("[{}] 이미 종료된 전달 작업에 commit 이 다시 호출됐다 (state={})", label, state);
            return;
        }
        try {
            connection.commit();
            state = State.COMMITTED;
            log.debug("[{}] 커밋 완료 — {}행", label, count);
        } catch (SQLException e) {
            state = State.FAILED;
            throw JdbcErrorTranslator.translate("[" + label + "] 커밋 실패 (" + count + "행)", e);
        } finally {
            release();
        }
    }

    @Override
    public void compensate() {
        if (state != State.PREPARED) {
            log.debug("[{}] 되돌릴 준비 상태가 아니다 — 보상을 건너뛴다 (state={})", label, state);
            return;
        }
        try {
            connection.rollback();
            state = State.COMPENSATED;
            log.info("[{}] 롤백 완료 — {}행 취소", label, count);
        } catch (SQLException e) {
            state = State.FAILED;
            // 예외를 던지지 않는다. 보상은 이미 다른 실패를 처리하는 도중에 불리므로,
            // 여기서 던지면 원래의 실패 원인이 이 예외로 덮여 사라진다.
            // 대신 조용히 넘기지도 않는다 — 삼키기 가장 쉬운 종류의 실패다.
            log.error("[{}] 롤백 실패 ({}). 커넥션을 닫아 DB 측 정리에 맡긴다 — "
                            + "미확정 트랜잭션은 세션 종료 시 롤백된다",
                    label, EaiErrorCode.JDBC_EXEC_ERROR.code(), e);
        } finally {
            release();
        }
    }

    /** 테스트·진단용. 이 전달 작업이 어떻게 끝났는지. */
    public State state() {
        return state;
    }

    /**
     * 커넥션 반납.
     *
     * <p>확정이든 보상이든 실패든 <b>반드시</b> 지난다. 여기를 빠뜨리면 풀의 커넥션이
     * 하나씩 새어 나가고, 증상은 한참 뒤 "실시간 API 가 갑자기 응답하지 않는다" 로 나타난다.
     *
     * <p>{@code autoCommit} 을 되돌리는 것은 HikariCP 가 반납 시 알아서 하지만,
     * 풀 구현이 무엇이든 빌린 상태 그대로 돌려주는 것이 예의다. 실패해도 반납은 진행한다 —
     * 상태 복원 실패로 커넥션 자체를 못 돌려주는 것이 더 큰 문제다.
     */
    private void release() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.debug("[{}] autoCommit 복원 실패 — 풀 반납 시 정리에 맡긴다", label, e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("[{}] 커넥션 반납 실패 — 누수 가능성이 있다", label, e);
        }
    }

    public enum State {
        /** INSERT 완료, 확정 대기 */
        PREPARED,
        /** 확정됨. 되돌릴 수 없다 */
        COMMITTED,
        /** 보상됨. 대상 시스템에 흔적이 없다 */
        COMPENSATED,
        /** 확정 또는 보상 자체가 실패 */
        FAILED
    }
}
