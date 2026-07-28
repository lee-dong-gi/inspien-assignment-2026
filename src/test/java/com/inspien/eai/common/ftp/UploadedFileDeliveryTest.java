package com.inspien.eai.common.ftp;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 보상 트랜잭션의 FTP 쪽 절반 (정의서 3.9).
 *
 * <p>JDBC 쪽과 대칭이지만 <b>실패의 의미가 다르다.</b> DB 는 롤백이 확실하고 되돌리면 흔적이
 * 사라지는 반면, FTP 는 rename 이 확정이고 그 실패는 <b>되돌릴 수 없는 자리</b>에서 일어난다.
 * 그 차이가 코드에 반영돼 있는지를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadedFileDelivery — .tmp 업로드 후 확정 대기")
class UploadedFileDeliveryTest {

    private static final String TEMP = "INSPIEN_이동기_20260729055227.txt.tmp";
    private static final String FINAL = "INSPIEN_이동기_20260729055227.txt";

    @Mock
    private FTPClient client;

    /**
     * 세션이 살아 있는 상태로 둔다.
     *
     * <p>이 대역이 없으면 {@code disconnect()} 를 검증하는 단언이 <b>전부 헛돈다</b> —
     * 정리 로직이 {@code isConnected()} 를 먼저 보고 조용히 빠져나가기 때문이다.
     * 확정·보상이 무엇을 하는지보다 <b>세션을 반드시 반납하는지</b>가 이 타입의 핵심이므로
     * 여기를 느슨하게 두면 테스트 전체의 의미가 없어진다.
     */
    @BeforeEach
    void connected() {
        given(client.isConnected()).willReturn(true);
    }

    private UploadedFileDelivery delivery(int count) {
        return new UploadedFileDelivery(client, TEMP, FINAL, count);
    }

    @Test
    @DisplayName("확정은 .tmp 를 최종 파일명으로 rename 하는 것이다")
    void commitRenamesToFinalName() throws Exception {
        given(client.rename(TEMP, FINAL)).willReturn(true);
        UploadedFileDelivery target = delivery(63);

        target.commit();

        var order = inOrder(client);
        order.verify(client).rename(TEMP, FINAL);
        order.verify(client).logout();
        order.verify(client).disconnect();
        assertAll(
                () -> assertEquals(63, target.count()),
                () -> assertEquals(UploadedFileDelivery.State.COMMITTED, target.state()),
                () -> verify(client, never()).deleteFile(TEMP));
    }

    @Test
    @DisplayName("보상은 임시 파일을 지우는 것이다 — 최종 파일은 애초에 존재한 적이 없다")
    void compensateDeletesTempFile() throws Exception {
        given(client.deleteFile(TEMP)).willReturn(true);
        UploadedFileDelivery target = delivery(63);

        target.compensate();

        assertAll(
                () -> verify(client).deleteFile(TEMP),
                () -> verify(client, never()).rename(TEMP, FINAL),
                () -> verify(client).disconnect(),
                () -> assertEquals(UploadedFileDelivery.State.COMPENSATED, target.state()));
    }

    @Test
    @DisplayName("rename 실패는 EAI-3005 — 재시도가 아니라 사람이 처리할 일이다")
    void renameFailureRequiresManualAction() throws Exception {
        given(client.rename(TEMP, FINAL)).willReturn(false);
        given(client.getReplyString()).willReturn("550 Permission denied");

        NonRetryableException e = assertThrows(NonRetryableException.class, delivery(63)::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_RENAME_FAILED, e.errorCode()),
                () -> assertFalse(e.errorCode().retryable(),
                        "재시도하면 이미 적재된 63행을 한 번 더 넣게 된다"),
                () -> assertTrue(e.getMessage().contains(TEMP), "어느 파일을 조치할지 알려야 한다"),
                () -> assertTrue(e.getMessage().contains(FINAL), "어떤 이름으로 바꿀지 알려야 한다"),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("rename 중 통신 오류도 수동 조치 대상이다 — 데이터는 이미 유효하다")
    void renameIoErrorAlsoRequiresManualAction() throws Exception {
        willThrow(new IOException("connection reset")).given(client).rename(TEMP, FINAL);

        NonRetryableException e = assertThrows(NonRetryableException.class, delivery(63)::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_RENAME_FAILED, e.errorCode()),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("보상 실패는 예외를 던지지 않는다 — 원래의 실패 원인을 덮지 않기 위해서다")
    void compensationFailureNeverThrows() throws Exception {
        willThrow(new IOException("connection reset")).given(client).deleteFile(TEMP);
        UploadedFileDelivery target = delivery(63);

        assertDoesNotThrow(target::compensate);

        assertAll(
                () -> assertEquals(UploadedFileDelivery.State.FAILED, target.state()),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("서버가 삭제를 거부해도 예외를 던지지 않되 상태로 남긴다")
    void compensationRejectionIsRecorded() throws Exception {
        given(client.deleteFile(TEMP)).willReturn(false);
        UploadedFileDelivery target = delivery(63);

        assertDoesNotThrow(target::compensate);

        assertEquals(UploadedFileDelivery.State.FAILED, target.state());
    }

    @Test
    @DisplayName("확정을 두 번 호출해도 두 번 rename 하지 않는다")
    void commitIsIdempotent() throws Exception {
        given(client.rename(TEMP, FINAL)).willReturn(true);
        UploadedFileDelivery target = delivery(63);

        target.commit();
        target.commit();

        assertAll(
                () -> verify(client, times(1)).rename(TEMP, FINAL),
                () -> verify(client, times(1)).disconnect());
    }

    @Test
    @DisplayName("이미 확정된 것은 보상하지 않는다 — 확정된 영수증을 지우면 안 된다")
    void doesNotDeleteAfterCommit() throws Exception {
        given(client.rename(TEMP, FINAL)).willReturn(true);
        UploadedFileDelivery target = delivery(63);

        target.commit();
        target.compensate();

        assertAll(
                () -> verify(client, never()).deleteFile(TEMP),
                () -> assertEquals(UploadedFileDelivery.State.COMMITTED, target.state()));
    }
}
