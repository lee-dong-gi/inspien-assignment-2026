package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.ftp.FtpClientFactory;
import com.inspien.eai.common.ftp.FtpTargetProperties;
import com.inspien.eai.common.secret.ApplicantName;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.integration.order.target.OrderRecord;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실제 FTP 서버 없이 검증한다.
 *
 * <p>이 Receiver 가 지켜야 할 성질은 전부 <b>호출 순서와 이름</b>의 문제다 —
 * 임시 이름으로 올리는가, 올린 뒤 다시 읽어 확인하는가, 실패하면 세션을 놓지 않는가.
 * 실서버로는 "파일명이 깨졌을 때 걸러내는가" 를 재현하기가 오히려 어렵다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptFileReceiver — 영수증 파일 FTP 전송")
class ReceiptFileReceiverTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 29, 5, 52, 27);
    private static final String EXPECTED_FINAL = "INSPIEN_이동기_20260729055227.txt";
    private static final String EXPECTED_TEMP = EXPECTED_FINAL + ".tmp";

    @Mock
    private FtpClientFactory clientFactory;
    @Mock
    private FTPClient client;

    private final FtpTargetProperties properties = new FtpTargetProperties(
            null, null, "UTF-8", "EUC-KR", true, ".tmp", true);

    private ReceiptFileReceiver receiver() {
        return new ReceiptFileReceiver(clientFactory, properties, new ApplicantName("이동기"));
    }

    @Test
    @DisplayName("구간 이름은 RECEIVER_FTP 다")
    void reportsFtpStep() {
        assertEquals(Step.RECEIVER_FTP, receiver().step());
    }

    @Test
    @DisplayName("최종 파일명이 아니라 .tmp 로 올린다 — 확정 전까지는 존재하지 않는 것과 같아야 한다")
    void uploadsWithTemporaryName() throws Exception {
        wireSuccessfulUpload();

        Delivery delivery = receiver().prepare(message(records(2)));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(client).storeFile(name.capture(), any(InputStream.class));

        assertAll(
                () -> assertEquals(EXPECTED_TEMP, name.getValue()),
                () -> assertEquals(2, delivery.count()),
                () -> verify(client, never()).rename(anyString(), anyString()));
    }

    @Test
    @DisplayName("파일명 시각은 인터페이스 실행 시작 시각이다 — Receiver 에서 다시 찍지 않는다")
    void usesInterfaceStartTimeForTimestamp() throws Exception {
        wireSuccessfulUpload();

        receiver().prepare(message(records(1)));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(client).storeFile(name.capture(), any(InputStream.class));

        assertTrue(name.getValue().startsWith("INSPIEN_이동기_20260729055227"),
                "now() 를 쓰면 검증·매핑·DB 적재에 걸린 시간만큼 어긋난다");
    }

    @Test
    @DisplayName("업로드 후 리스팅해 파일명 보존을 확인한다")
    void verifiesUploadedNameByListing() throws Exception {
        wireSuccessfulUpload();

        receiver().prepare(message(records(1)));

        verify(client).listNames();
    }

    @Test
    @DisplayName("파일명이 '?' 로 치환되면 EAI-3004 로 끊고 임시 파일까지 지운다")
    void detectsSilentlyCorruptedFileName() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        given(client.isConnected()).willReturn(true);
        // 다른 지원자들의 파일이 이 꼴이다 — 서버는 정상 응답을 줬다.
        given(client.listNames()).willReturn(new String[]{"INSPIEN_???_20260729055227.txt.tmp"});

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver().prepare(message(records(1))));

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_ENCODING_ERROR, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("UTF-8"), "어떤 인코딩으로 보냈는지 알려야 한다"),
                () -> verify(client).deleteFile(EXPECTED_TEMP),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("업로드 거부는 재시도 가능(EAI-3002)으로 분류하고 세션을 반납한다")
    void classifiesUploadRejectionAsRetryable() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(false);
        given(client.isConnected()).willReturn(true);

        RetryableException e = assertThrows(RetryableException.class,
                () -> receiver().prepare(message(records(1))));

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_UPLOAD_ERROR, e.errorCode()),
                () -> verify(client).disconnect());
    }

    @Test
    @DisplayName("리스팅이 실패하면 확정하지 않는다 — 확인하지 못한 것은 확인된 것이 아니다")
    void failsWhenVerificationImpossible() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        given(client.listNames()).willThrow(new IOException("data connection reset"));

        RetryableException e = assertThrows(RetryableException.class,
                () -> receiver().prepare(message(records(1))));

        assertEquals(EaiErrorCode.FTP_UPLOAD_ERROR, e.errorCode());
    }

    @Test
    @DisplayName("인코딩 불가 문자가 있으면 연결조차 하지 않는다 — 잘린 파일을 남기지 않기 위해서다")
    void validatesContentBeforeOpeningSession() {
        OrderRecord broken = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지😀", "21000", "N");

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver().prepare(message(List.of(broken))));

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_ENCODING_ERROR, e.errorCode()),
                () -> verify(clientFactory, never()).open());
    }

    @Test
    @DisplayName("0건이면 빈 영수증을 만들지 않는다")
    void emptyPayloadUploadsNothing() {
        Delivery delivery = receiver().prepare(message(List.of()));

        assertAll(
                () -> assertEquals(0, delivery.count()),
                () -> verify(clientFactory, never()).open());

        delivery.commit();
        delivery.compensate();
    }

    // ── 대역 배선 ───────────────────────────────────────────────

    private void wireSuccessfulUpload() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);
        given(client.listNames()).willReturn(new String[]{EXPECTED_TEMP});
    }

    private CanonicalMessage<List<OrderRecord>> message(List<OrderRecord> records) {
        return new CanonicalMessage<>(
                new MessageHeader("tx-test", InterfaceId.IF_ORD_001, OCCURRED_AT), records);
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
}
