package com.inspien.eai.common.ftp;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 보상 트랜잭션의 FTP 쪽 절반 (정의서 3.9, D-21).
 *
 * <p>JDBC 쪽과 대칭이지만 <b>비대칭이 하나 있다.</b> DB 는 준비 단계에서 이미 행을 써 두고
 * 롤백으로 지우지만, FTP 는 <b>준비 단계에서 아무것도 쓰지 않는다</b> — 대상 서버가
 * rename 도 삭제도 허용하지 않기 때문이다. 그 결과 보상이 비어 있다는 것,
 * 그리고 확정 실패가 되돌릴 수 없는 자리에서 일어난다는 것을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingUploadDelivery — 확정 시점에 업로드하는 전달 작업")
class PendingUploadDeliveryTest {

    private static final String FILE = "INSPIEN_이동기_20260729055227.txt";
    private static final byte[] CONTENT = "A000^USER01^ITEM01\n".getBytes(StandardCharsets.UTF_8);

    private final FtpTargetProperties properties = new FtpTargetProperties(
            Duration.ofSeconds(10), Duration.ofSeconds(15),
            "UTF-8", "EUC-KR", true, true);

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

    private PendingUploadDelivery delivery(int count) {
        return new PendingUploadDelivery(client, FILE, CONTENT, count, properties);
    }

    /** 서버가 우리가 보낸 그대로 저장했다고 답하는 대역. */
    private void serverHasFile(String name, long size) throws IOException {
        FTPFile file = new FTPFile();
        file.setName(name);
        file.setSize(size);
        given(client.listFiles()).willReturn(new FTPFile[]{file});
    }

    @Test
    @DisplayName("확정은 최종 파일명으로 업로드하는 것이다 — 임시 파일도 rename 도 없다")
    void commitUploadsWithFinalName() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile(FILE, CONTENT.length);
        PendingUploadDelivery target = delivery(63);

        target.commit();

        var order = inOrder(client);
        order.verify(client).storeFile(eq(FILE), any(InputStream.class));
        order.verify(client).listFiles();
        order.verify(client).logout();
        order.verify(client).disconnect();
        assertAll(
                () -> assertEquals(63, target.count()),
                () -> assertEquals(PendingUploadDelivery.State.COMMITTED, target.state()),
                () -> verify(client, never()).rename(anyString(), anyString()),
                () -> verify(client, never()).deleteFile(anyString()));
    }

    @Test
    @DisplayName("보상은 서버에 손대지 않는다 — 준비 단계에서 쓴 것이 없기 때문이다")
    void compensateTouchesNothingOnServer() throws Exception {
        PendingUploadDelivery target = delivery(63);

        target.compensate();

        assertAll(
                () -> verify(client, never()).storeFile(anyString(), any(InputStream.class)),
                () -> verify(client, never()).deleteFile(anyString()),
                () -> verify(client, never()).rename(anyString(), anyString()),
                () -> verify(client).disconnect(),
                () -> assertEquals(PendingUploadDelivery.State.COMPENSATED, target.state()));
    }

    @Test
    @DisplayName("업로드 거부는 EAI-3005 — 재시도가 아니라 사람이 처리할 일이다")
    void uploadRejectionRequiresManualAction() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(false);
        given(client.getReplyString()).willReturn("550 Permission denied");

        NonRetryableException e = assertThrows(NonRetryableException.class, delivery(63)::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_COMMIT_FAILED, e.errorCode()),
                () -> assertTrue(e.getMessage().contains(FILE), "어느 파일을 올려야 할지 알려야 한다"),
                () -> assertTrue(e.getMessage().contains("63"), "몇 행이 이미 적재됐는지 알려야 한다"),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("전송 중 통신 오류는 잘린 파일이 남았을 수 있음을 알린다 — 지울 권한이 없다")
    void ioErrorWarnsAboutResidue() throws Exception {
        willThrow(new IOException("connection reset"))
                .given(client).storeFile(anyString(), any(InputStream.class));

        NonRetryableException e = assertThrows(NonRetryableException.class, delivery(63)::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_COMMIT_FAILED, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("삭제를 허용하지 않는다"),
                        "회수할 수 없다는 사실을 반드시 알려야 한다"),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("파일명이 보존되지 않으면 실패시킨다 — 인코딩 사고는 예외 없이 성공으로 보고된다")
    void mangledFileNameFails() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile("INSPIEN_???_20260729055227.txt", CONTENT.length);
        PendingUploadDelivery target = delivery(63);

        NonRetryableException e = assertThrows(NonRetryableException.class, target::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_COMMIT_FAILED, e.errorCode()),
                () -> assertEquals(PendingUploadDelivery.State.FAILED, target.state()),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("크기가 다르면 실패시킨다 — 이름만 보면 잘린 파일을 통과시킨다")
    void truncatedFileFails() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile(FILE, CONTENT.length - 5);

        NonRetryableException e = assertThrows(NonRetryableException.class, delivery(63)::commit);

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_COMMIT_FAILED, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("끊겼을 수 있다")),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("크기를 읽지 못하는 서버는 통과시킨다 — 못 읽은 것을 불일치로 단정하지 않는다")
    void unknownRemoteSizePasses() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile(FILE, -1);
        PendingUploadDelivery target = delivery(63);

        assertDoesNotThrow(target::commit);

        assertEquals(PendingUploadDelivery.State.COMMITTED, target.state());
    }

    @Test
    @DisplayName("확정을 두 번 호출해도 두 번 업로드하지 않는다")
    void commitIsIdempotent() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile(FILE, CONTENT.length);
        PendingUploadDelivery target = delivery(63);

        target.commit();
        target.commit();

        assertAll(
                () -> verify(client, times(1)).storeFile(anyString(), any(InputStream.class)),
                () -> verify(client, times(1)).disconnect());
    }

    @Test
    @DisplayName("이미 확정된 것은 보상하지 않는다")
    void doesNotCompensateAfterCommit() throws Exception {
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        serverHasFile(FILE, CONTENT.length);
        PendingUploadDelivery target = delivery(63);

        target.commit();
        target.compensate();

        assertEquals(PendingUploadDelivery.State.COMMITTED, target.state());
    }
}
