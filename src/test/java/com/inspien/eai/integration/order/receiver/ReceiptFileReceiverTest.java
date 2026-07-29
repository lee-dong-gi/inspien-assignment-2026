package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.ftp.FtpClientFactory;
import com.inspien.eai.common.ftp.FtpTargetProperties;
import com.inspien.eai.common.secret.ApplicantName;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.log.Step;
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
 * <p>D-21 이후 이 Receiver 의 책임은 <b>준비</b>뿐이다 — 파일명을 정하고, 내용을 만들고,
 * 세션을 연다. 업로드와 검증은 {@code PendingUploadDelivery} 가 확정 단계에서 한다.
 * 그래서 여기서 확인할 가장 중요한 성질은 하나로 좁혀진다.
 *
 * <p><b>준비 단계는 서버에 아무것도 쓰지 않는다.</b> 대상 서버가 rename 도 삭제도 허용하지 않으므로
 * (append-only — 실측), 준비 중에 원격에 무언가를 쓰는 순간 되돌릴 방법이 사라진다.
 * 이 단언이 깨지는 변경은 곧 D-21 재설계를 무효로 만드는 변경이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptFileReceiver — 영수증 파일 전송 준비")
class ReceiptFileReceiverTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 29, 5, 52, 27);
    private static final String EXPECTED_NAME = "INSPIEN_이동기_20260729055227.txt";

    @Mock
    private FtpClientFactory clientFactory;
    @Mock
    private FTPClient client;

    /**
     * 업로드 검증을 꺼 둔다.
     *
     * <p>파일명·크기 보존 검증은 {@code PendingUploadDeliveryTest} 가 이미 다룬다.
     * 여기서까지 리스팅 대역을 배선하면 <b>Receiver 를 검증하는 테스트가 Delivery 의 사정에
     * 묶여</b> 확정 로직을 바꿀 때마다 함께 깨진다.
     */
    private final FtpTargetProperties properties = new FtpTargetProperties(
            null, null, "UTF-8", "EUC-KR", true, false);

    private ReceiptFileReceiver receiver() {
        return new ReceiptFileReceiver(clientFactory, properties, new ApplicantName("이동기"));
    }

    @Test
    @DisplayName("구간 이름은 RECEIVER_FTP 다")
    void reportsFtpStep() {
        assertEquals(Step.RECEIVER_FTP, receiver().step());
    }

    @Test
    @DisplayName("준비 단계는 서버에 아무것도 쓰지 않는다 — D-21 의 핵심")
    void prepareWritesNothingToServer() throws Exception {
        given(clientFactory.open()).willReturn(client);

        Delivery delivery = receiver().prepare(message(records(2)));

        assertAll(
                () -> assertEquals(2, delivery.count()),
                () -> verify(client, never()).storeFile(anyString(), any(InputStream.class)),
                () -> verify(client, never()).rename(anyString(), anyString()),
                () -> verify(client, never()).deleteFile(anyString()),
                // 세션은 열려 있어야 한다. 확정 단계에서 이 커넥션으로 업로드한다.
                () -> verify(client, never()).disconnect());
    }

    @Test
    @DisplayName("업로드는 확정 단계에서 최종 파일명으로 일어난다 — 임시 이름을 쓰지 않는다")
    void commitUploadsWithFinalName() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);

        receiver().prepare(message(records(2))).commit();

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(client).storeFile(name.capture(), any(InputStream.class));

        assertAll(
                () -> assertEquals(EXPECTED_NAME, name.getValue()),
                () -> assertTrue(!name.getValue().endsWith(".tmp"),
                        "이 서버는 rename 을 거부한다 — 임시 이름으로 올리면 확정할 방법이 없다"),
                () -> verify(client, never()).rename(anyString(), anyString()));
    }

    @Test
    @DisplayName("파일명 시각은 인터페이스 실행 시작 시각이다 — Receiver 에서 다시 찍지 않는다")
    void usesInterfaceStartTimeForTimestamp() throws Exception {
        given(clientFactory.open()).willReturn(client);
        given(client.storeFile(anyString(), any(InputStream.class))).willReturn(true);

        receiver().prepare(message(records(1))).commit();

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(client).storeFile(name.capture(), any(InputStream.class));

        assertTrue(name.getValue().startsWith("INSPIEN_이동기_20260729055227"),
                "now() 를 쓰면 검증·매핑·DB 적재에 걸린 시간만큼 어긋난다");
    }

    @Test
    @DisplayName("인코딩 불가 문자가 있으면 연결조차 하지 않는다 — 세션을 열어 놓고 실패하지 않는다")
    void validatesContentBeforeOpeningSession() {
        OrderRecord broken = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지😀", "21000", "N");

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> receiver().prepare(message(List.of(broken))));

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_ENCODING_ERROR, e.errorCode()),
                // 동시 접속 5 제한 아래에서, 실패할 것이 뻔한 요청에 세션을 쓰면 안 된다.
                () -> verify(clientFactory, never()).open());
    }

    @Test
    @DisplayName("0건이면 빈 영수증을 만들지 않고 세션도 열지 않는다")
    void emptyPayloadUploadsNothing() {
        Delivery delivery = receiver().prepare(message(List.of()));

        assertAll(
                () -> assertEquals(0, delivery.count()),
                () -> verify(clientFactory, never()).open());

        delivery.commit();
        delivery.compensate();
    }

    // ── 대역 배선 ───────────────────────────────────────────────

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
