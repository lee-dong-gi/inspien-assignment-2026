package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.exception.EaiErrorCode;

/**
 * 확정 대기 상태의 전달 작업 — 이 프로젝트에서 가장 중요한 추상화.
 *
 * <h2>문제</h2>
 * 시나리오 1은 Oracle 적재와 FTP 업로드가 <b>둘 다</b> 성공해야 성공이다.
 * 그런데 FTP 는 DB 트랜잭션에 참여할 수 없다. XA/2PC 를 걸 대상이 아니다.
 * 순진하게 짜면 반드시 다음 중 하나가 된다.
 *
 * <pre>
 *   DB commit → FTP 실패   ⇒ DB 에만 있는 주문 (영수증 없음)
 *   FTP 성공  → DB 실패    ⇒ 파일에만 있는 주문 (유령 영수증)
 * </pre>
 *
 * <h2>해법 — 준비와 확정의 분리</h2>
 * 각 Receiver 가 <b>되돌릴 수 있는 지점까지만</b> 먼저 진행하고 멈춘다.
 * 모두가 그 지점에 도달한 뒤에 순서대로 확정한다.
 *
 * <pre>
 *   JdbcDelivery : prepare = INSERT 후 commit 보류  / commit = DB commit  / compensate = rollback
 *   FtpDelivery  : prepare = .tmp 파일명으로 업로드 / commit = 최종명 rename / compensate = .tmp 삭제
 * </pre>
 *
 * 2PC 의 흉내가 아니라, <b>실무에서 실제로 쓰는 보상 트랜잭션</b>을 타입으로 고정한 것이다.
 * 되돌리는 방법을 각 Receiver 가 스스로 알고 있으므로, 조율자는 순서만 책임진다.
 *
 * <h2>남는 한계 — 정직하게 기록한다</h2>
 * 마지막 확정 이후의 실패는 되돌릴 수 없다. 예컨대 DB commit 이 끝난 뒤 FTP rename 이 실패하면
 * 데이터 자체는 유효하므로 롤백하지 않고, {@link EaiErrorCode#FTP_COMPENSATION_FAILED} 로
 * <b>수동 조치 대상</b>임을 남긴다. 완벽한 원자성은 달성되지 않으며, 그 사실을 숨기지 않는 것이
 * 조용히 성공으로 보고하는 것보다 낫다.
 */
public interface Delivery {

    /**
     * 대상이 0건일 때 쓰는 전달 작업. 확정할 것도 되돌릴 것도 없다.
     *
     * <p>{@code null} 을 돌려주고 조율자가 분기하게 두지 않는다. 0건은 예외 상황이 아니라
     * <b>정상적인 결과 중 하나</b>이고, 정상 흐름을 {@code null} 검사로 표현하기 시작하면
     * 그 검사는 언젠가 한 곳에서 빠진다.
     *
     * <p>실질적 이득도 있다 — 이것이 없으면 Receiver 가 0건에도 커넥션을 얻고
     * 트랜잭션을 열어야 한다. 아무 행도 넣지 않을 작업에 커넥션을 점유하는 것은
     * 실시간 응답 경로에서 순수한 낭비다.
     */
    static Delivery empty() {
        return NoOpDelivery.INSTANCE;
    }

    /** 이 전달 작업이 다루는 건수 */
    int count();

    /**
     * 확정한다. 이 호출이 끝나면 되돌릴 수 없다.
     *
     * @throws com.inspien.eai.engine.exception.EaiException 확정 실패
     */
    void commit();

    /**
     * 준비 상태를 되돌린다.
     *
     * <p><b>보상은 실패할 수 있다.</b> 그리고 보상의 실패는 삼키기 가장 쉬운 종류의 실패다
     * (이미 다른 실패를 처리하는 중이므로). 구현체는 조용히 넘기지 말고 반드시 기록해야 한다.
     */
    void compensate();
}
