package com.inspien.eai.common.ftp;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.receiver.Delivery;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;

import java.io.IOException;

/**
 * 임시 파일명으로 올라간 채 확정을 기다리는 업로드 — {@link Delivery} 의 FTP 구현.
 *
 * <p>{@code PendingCommitDelivery}(JDBC)와 <b>같은 모양</b>이다. 되돌릴 수 있는 지점까지만
 * 진행하고 멈춘 뒤, 조율자의 지시로 확정하거나 되돌린다. 다만 되돌리는 수단이 다르다.
 *
 * <pre>
 *   JDBC : prepare = INSERT (commit 보류) / commit = COMMIT      / compensate = ROLLBACK
 *   FTP  : prepare = .tmp 업로드          / commit = RENAME      / compensate = DELETE
 * </pre>
 *
 * <h2>왜 {@code .tmp} 로 올리는가</h2>
 * FTP 에는 트랜잭션이 없다. 최종 파일명으로 바로 올리면 <b>업로드가 끝난 그 순간부터</b>
 * 회계 담당자의 수집 배치가 그 파일을 가져갈 수 있고, 우리가 그 뒤에 DB 실패로 롤백해도
 * 이미 나간 영수증은 회수되지 않는다. 임시 이름으로 올려 두면 그 파일은 <b>아직 존재하지 않는
 * 것과 같다</b> — 수집 대상 패턴에 걸리지 않기 때문이다. rename 은 대부분의 파일시스템에서
 * 원자적이므로, 확정의 순간을 한 점으로 좁힐 수 있다.
 *
 * <h2>확정 순서상 마지막이라는 것의 의미</h2>
 * 조율자는 JDBC 를 먼저, FTP 를 나중에 확정한다. 되돌리기 비용이 <b>싼 쪽을 나중에</b> 두는 것이
 * 아니라 그 반대다 — DB 롤백은 확실하지만 원격 FTP 파일 삭제는 네트워크에 달려 있으므로,
 * 불확실한 쪽을 마지막에 두어 보상이 필요한 상황 자체를 줄인다.
 *
 * <p>그 결과 <b>rename 실패는 되돌릴 수 없는 자리에서 일어난다.</b> DB 는 이미 확정됐고
 * {@code .tmp} 에는 유효한 데이터가 온전히 들어 있다. 이때 롤백하면 멀쩡한 주문을 지우는 것이고,
 * 재시도하면 같은 주문을 두 번 넣는 것이다. 그래서 {@link EaiErrorCode#FTP_RENAME_FAILED} 로
 * <b>사람에게 넘긴다</b> — 필요한 조치는 재실행이 아니라 파일명 변경 하나다.
 */
@Slf4j
public final class UploadedFileDelivery implements Delivery {

    private final FTPClient client;
    private final String tempName;
    private final String finalName;
    private final int count;

    private State state = State.PREPARED;

    public UploadedFileDelivery(FTPClient client, String tempName, String finalName, int count) {
        this.client = client;
        this.tempName = tempName;
        this.finalName = finalName;
        this.count = count;
    }

    @Override
    public int count() {
        return count;
    }

    /** {@code .tmp} → 최종 파일명. 이 rename 이 성공한 순간 영수증이 세상에 존재하게 된다. */
    @Override
    public void commit() {
        if (state != State.PREPARED) {
            log.warn("[FTP] 이미 종료된 전달 작업에 commit 이 다시 호출됐다 (state={})", state);
            return;
        }
        try {
            if (!client.rename(tempName, finalName)) {
                state = State.FAILED;
                throw manualActionRequired("서버가 rename 을 거부했다 — " + reply(), null);
            }
            state = State.COMMITTED;
            log.debug("[FTP] 확정 — {} → {} ({}행)", tempName, finalName, count);

        } catch (IOException e) {
            state = State.FAILED;
            throw manualActionRequired("rename 중 통신 오류", e);
        } finally {
            release();
        }
    }

    /**
     * 업로드한 임시 파일을 지운다.
     *
     * <p><b>보상은 실패할 수 있고, 그 실패는 삼키기 가장 쉽다</b> — 이미 다른 실패를
     * 처리하는 중이기 때문이다. 예외를 던지면 원래의 실패 원인이 덮이므로 던지지 않되,
     * {@link EaiErrorCode#FTP_COMPENSATION_FAILED} 로 <b>수동 조치 대상임을 남긴다.</b>
     * 남은 {@code .tmp} 는 데이터 정합성을 깨뜨리지는 않지만(수집 대상이 아니므로)
     * 조용히 쌓이면 디렉터리를 오염시킨다.
     */
    @Override
    public void compensate() {
        if (state != State.PREPARED) {
            log.debug("[FTP] 되돌릴 준비 상태가 아니다 — 보상을 건너뛴다 (state={})", state);
            return;
        }
        try {
            if (client.deleteFile(tempName)) {
                state = State.COMPENSATED;
                log.info("[FTP] 보상 완료 — 임시 파일 삭제: {}", tempName);
            } else {
                state = State.FAILED;
                logCompensationFailure(reply(), null);
            }
        } catch (IOException e) {
            state = State.FAILED;
            logCompensationFailure("통신 오류", e);
        } finally {
            release();
        }
    }

    /** 테스트·진단용. */
    public State state() {
        return state;
    }

    private NonRetryableException manualActionRequired(String detail, Throwable cause) {
        String message = detail + ". DB 는 이미 확정되었고 '" + tempName + "' 에 유효한 " + count + "행이 들어 있다. "
                + "재실행하면 중복 적재가 되므로, '" + finalName + "' 로 이름만 바꿀 것";
        return (cause == null)
                ? new NonRetryableException(EaiErrorCode.FTP_RENAME_FAILED, message)
                : new NonRetryableException(EaiErrorCode.FTP_RENAME_FAILED, message, cause);
    }

    private void logCompensationFailure(String detail, Throwable cause) {
        log.error("[{}] 임시 파일 삭제 실패: {} ({}). 수동 삭제 대상 — 데이터 정합성은 유지되지만 "
                        + "임시 파일이 디렉터리에 남는다",
                EaiErrorCode.FTP_COMPENSATION_FAILED.code(), tempName, detail, cause);
    }

    /**
     * 세션 반납.
     *
     * <p>서버 동시 접속 수가 5로 제한돼 있다(BOOT-001 배너). 여기를 빠뜨리면 누수가
     * 곧 다음 주문의 접속 거부로 나타난다.
     */
    private void release() {
        FtpSessions.closeQuietly(client);
    }

    private String reply() {
        String replyString = client.getReplyString();
        return replyString == null ? "" : replyString.trim();
    }

    public enum State {
        /** 임시 파일명으로 업로드 완료, 확정 대기 */
        PREPARED,
        /** 최종 파일명으로 확정됨 */
        COMMITTED,
        /** 임시 파일 삭제 완료. 서버에 흔적이 없다 */
        COMPENSATED,
        /** 확정 또는 보상 자체가 실패 — 수동 조치 대상 */
        FAILED
    }
}
