package com.inspien.eai.common.ftp;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.receiver.Delivery;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 아직 서버에 쓰이지 않은 업로드 — {@link Delivery} 의 FTP 구현 (D-21).
 *
 * <p>이름이 말하는 그대로다. 이 객체를 손에 넣은 시점에 <b>서버에는 아무것도 없다.</b>
 * 세션이 열려 있고 보낼 바이트가 준비돼 있을 뿐이다.
 *
 * <pre>
 *   JDBC : prepare = INSERT (commit 보류) / commit = COMMIT / compensate = ROLLBACK
 *   FTP  : prepare = 접속 + 콘텐츠 생성    / commit = STOR   / compensate = <b>할 일 없음</b>
 * </pre>
 *
 * <h2>왜 업로드가 확정 단계로 왔는가 — 실측이 전제를 깼다</h2>
 * 초판은 {@code .tmp} 로 올려 두고 rename 으로 확정했다. 대부분의 파일시스템에서 rename 이
 * 원자적이므로 확정의 순간을 한 점으로 좁힐 수 있다는, 그 자체로는 옳은 설계였다.
 * 그런데 대상 서버가 rename 을 거부한다.
 *
 * <pre>
 *   451 Rename/move failure: Operation not permitted
 * </pre>
 *
 * 우연이 아니다. Oracle 쪽도 {@code SELECT}/{@code INSERT}/{@code UPDATE} 만 있고
 * {@code DELETE} 가 없다({@code ORA-01031}). <b>대상 환경 전체가 append-only</b>다 —
 * 지원자들이 같은 테이블과 같은 디렉터리를 공유하므로 서로의 제출물을 지우지 못하게 막아 둔 것이다.
 *
 * <p>보상 트랜잭션을 설계할 때 가장 먼저 물어야 할 것은 "보상이 이 환경에서 <b>가능한가</b>" 다.
 * FTP 쪽 답은 아니오였다. 되돌릴 수 없다면, <b>되돌릴 상태를 만들지 않는 것</b>이 유일한 해법이다.
 *
 * <h2>바뀐 뒤가 오히려 단순하다</h2>
 * <table border="1">
 *   <caption>실패 지점별 결과</caption>
 *   <tr><th>실패 지점</th><th>서버 상태</th><th>결과</th></tr>
 *   <tr><td>FTP prepare</td><td>없음</td><td>DB 롤백 → 온전한 실패, 재시도 안전</td></tr>
 *   <tr><td>JDBC commit</td><td>없음</td><td>보상할 것이 <b>없다</b> → 온전한 실패, 재시도 안전</td></tr>
 *   <tr><td>FTP commit</td><td>없거나 잘린 파일</td><td>DB 확정됨 → 수동 조치 ({@code EAI-3005})</td></tr>
 * </table>
 *
 * 두 번째 줄이 요점이다. 초판에서는 이 자리에서 {@code .tmp} 를 지워야 했고,
 * <b>그 삭제가 이 서버에서는 불가능했다.</b> 지금은 지울 것이 애초에 없다.
 *
 * <h2>남는 한계 — 숨기지 않는다</h2>
 * 업로드가 <b>전송 도중</b> 끊기면 최종 파일명을 단 잘린 파일이 서버에 남을 수 있고,
 * 삭제 권한이 없으므로 회수할 수 없다. {@code .tmp} 였다면 최소한 수집 대상 패턴에
 * 걸리지 않았을 것이다. 이것이 이번 재설계가 치른 값이다.
 *
 * <p>없앨 수 없으므로 <b>반드시 검출</b>한다. 업로드 직후 리스팅으로 이름과 크기를 함께 확인하고,
 * 어긋나면 {@code EAI-3005} 로 사람에게 넘긴다. 조용히 성공으로 보고하는 것보다
 * "잘린 파일이 남았을 수 있다" 고 말하는 편이 낫다.
 */
@Slf4j
public final class PendingUploadDelivery implements Delivery {

    private final FTPClient client;
    private final String fileName;
    private final byte[] content;
    private final int count;
    private final FtpTargetProperties properties;

    private State state = State.PREPARED;

    public PendingUploadDelivery(FTPClient client, String fileName, byte[] content,
                                 int count, FtpTargetProperties properties) {
        this.client = client;
        this.fileName = fileName;
        this.content = content;
        this.count = count;
        this.properties = properties;
    }

    @Override
    public int count() {
        return count;
    }

    /**
     * 최종 파일명으로 업로드한다. 이 STOR 이 성공한 순간 영수증이 세상에 존재하게 된다.
     *
     * <p>조율자가 JDBC 를 먼저 확정하므로, 여기 도달했다는 것은 <b>DB 63행이 이미 확정됐다</b>는 뜻이다.
     * 따라서 이 자리의 실패는 재시도 대상이 아니다 — 재실행하면 같은 주문이 한 번 더 적재된다.
     */
    @Override
    public void commit() {
        if (state != State.PREPARED) {
            log.warn("[FTP] 이미 종료된 전달 작업에 commit 이 다시 호출됐다 (state={})", state);
            return;
        }
        try {
            store();
            verifyStored();

            state = State.COMMITTED;
            log.debug("[FTP] 확정 — {} ({}행, {} bytes)", fileName, count, content.length);

        } catch (NonRetryableException e) {
            state = State.FAILED;
            throw e;
        } finally {
            release();
        }
    }

    /**
     * 되돌린다 — <b>서버에 할 일이 없다.</b>
     *
     * <p>확정 전에는 원격에 아무것도 쓰이지 않으므로 정리 대상은 세션뿐이다.
     * 이 메서드가 비어 있다는 사실 자체가 D-21 재설계의 성과다. 초판에서는 여기서
     * {@code .tmp} 를 지워야 했고, 그 삭제가 실패하면 수동 조치 대상이 하나 더 늘었다.
     * 지금은 <b>실패할 수 있는 보상이 존재하지 않는다.</b>
     */
    @Override
    public void compensate() {
        if (state != State.PREPARED) {
            log.debug("[FTP] 되돌릴 준비 상태가 아니다 — 보상을 건너뛴다 (state={})", state);
            return;
        }
        state = State.COMPENSATED;
        log.debug("[FTP] 보상 완료 — 업로드 전이므로 서버에 남은 것이 없다: {}", fileName);
        release();
    }

    /** 테스트·진단용. */
    public State state() {
        return state;
    }

    /**
     * STOR.
     *
     * <p>반환값을 반드시 본다. FTP 는 데이터 채널이 끊겨도 예외가 아니라
     * <b>실패 응답</b>으로 답하는 경우가 있다.
     */
    private void store() {
        try (InputStream in = new ByteArrayInputStream(content)) {
            if (!client.storeFile(fileName, in)) {
                throw manualActionRequired("서버가 업로드를 거부했다 — " + reply(), false, null);
            }
        } catch (IOException e) {
            // 전송 도중 끊겼다면 잘린 파일이 남았을 수 있다. 지울 권한이 없으므로 그 사실을 알린다.
            throw manualActionRequired("업로드 중 통신 오류", true, e);
        }
    }

    /**
     * 올라간 파일이 우리가 보낸 그것인지 확인한다 — <b>이름과 크기 둘 다.</b>
     *
     * <p>이름을 보는 이유는 인코딩 사고가 <b>예외 없이 성공으로 보고되기</b> 때문이다.
     * 제어 채널 인코딩이 어긋나면 한글이 {@code ?} 로 치환된 채 저장되고 서버는 정상 응답을 준다.
     * 업로드 디렉터리의 다른 지원자 파일 {@code INSPIEN_???_...txt} 들이 그 증거다.
     *
     * <p>크기를 보는 이유는 이번 재설계로 생긴 한계 때문이다. 최종 파일명으로 바로 올리므로
     * <b>전송이 중간에 끊기면 잘린 파일이 최종 이름을 달고 남는다.</b> 이름만 보면 통과한다.
     */
    private void verifyStored() {
        if (!properties.verifyUploadedName()) {
            log.warn("[FTP] 업로드 검증이 꺼져 있다 (inspien.ftp.verify-uploaded-name=false). "
                    + "인코딩 손상과 잘린 파일이 성공으로 보고될 수 있다");
            return;
        }

        FTPFile[] listed;
        try {
            listed = client.listFiles();
        } catch (IOException e) {
            throw manualActionRequired("업로드 후 리스팅 실패 — 파일이 온전한지 확인할 수 없다", true, e);
        }
        if (listed == null) {
            throw manualActionRequired("업로드 후 리스팅이 비어 있다 — 파일이 온전한지 확인할 수 없다", true, null);
        }

        FTPFile stored = Arrays.stream(listed)
                .filter(file -> file != null && file.getName() != null)
                .filter(file -> fileName.equals(baseName(file.getName())))
                .findFirst()
                .orElse(null);

        if (stored == null) {
            throw manualActionRequired("""
                    업로드한 파일명이 서버에서 그대로 보존되지 않았다.
                      보낸 이름   : %s
                      비ASCII 문자: %d자
                    제어 채널 인코딩(%s)이 서버와 맞지 않아 '?' 로 치환됐을 가능성이 크다.\
                    """.formatted(fileName, countNonAscii(fileName), properties.controlEncoding()),
                    true, null);
        }

        // 크기를 못 읽는 서버·파서 조합이 있다. 그때는 통과시키되 확인하지 못했음을 남긴다 —
        // 읽지 못한 것을 불일치로 단정하면 멀쩡한 실행이 매번 수동 조치 대상이 된다.
        long remoteSize = stored.getSize();
        if (remoteSize < 0) {
            log.warn("[FTP] 원격 파일 크기를 읽지 못했다 — 잘림 여부는 확인하지 못했다: {}", fileName);
            return;
        }
        if (remoteSize != content.length) {
            throw manualActionRequired(
                    "업로드된 파일 크기가 다르다 — 전송이 중간에 끊겼을 수 있다 (보낸 %d bytes / 서버 %d bytes)"
                            .formatted(content.length, remoteSize), true, null);
        }

        log.debug("[FTP] 업로드 검증 통과 — {} ({} bytes, 비ASCII {}자)",
                fileName, remoteSize, countNonAscii(fileName));
    }

    /**
     * 확정 단계의 실패는 전부 수동 조치 대상이다.
     *
     * @param mayHaveResidue 서버에 잘리거나 잘못된 이름의 파일이 남았을 수 있는지.
     *                       삭제 권한이 없으므로 <b>남았다면 회수할 수 없다</b> — 반드시 알린다
     */
    private NonRetryableException manualActionRequired(String detail, boolean mayHaveResidue, Throwable cause) {
        String residue = mayHaveResidue
                ? " 서버에 불완전한 파일이 남았을 수 있으나 이 서버는 삭제를 허용하지 않는다 — 직접 확인할 것."
                : "";

        String message = detail + ". DB 는 이미 확정되어 " + count + "행이 적재되었다."
                + residue
                + " 재실행하면 중복 적재가 되므로, '" + fileName + "' 을 수동으로 업로드할 것";

        return (cause == null)
                ? new NonRetryableException(EaiErrorCode.FTP_COMMIT_FAILED, message)
                : new NonRetryableException(EaiErrorCode.FTP_COMMIT_FAILED, message, cause);
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

    private static String baseName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static long countNonAscii(String value) {
        return value.chars().filter(ch -> ch > 127).count();
    }

    private String reply() {
        String replyString = client.getReplyString();
        return replyString == null ? "" : replyString.trim();
    }

    public enum State {
        /** 콘텐츠 준비 완료, 세션 개설 완료. <b>서버에는 아직 아무것도 없다</b> */
        PREPARED,
        /** 최종 파일명으로 업로드·검증 완료 */
        COMMITTED,
        /** 되돌림 완료. 서버에 흔적이 없다 (애초에 쓰지 않았다) */
        COMPENSATED,
        /** 확정 실패 — 수동 조치 대상 */
        FAILED
    }
}
