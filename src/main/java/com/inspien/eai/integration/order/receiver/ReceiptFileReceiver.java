package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.ftp.FtpClientFactory;
import com.inspien.eai.common.ftp.FtpSessions;
import com.inspien.eai.common.ftp.FtpTargetProperties;
import com.inspien.eai.common.ftp.UploadedFileDelivery;
import com.inspien.eai.common.secret.ApplicantName;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.target.OrderRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * IF-ORD-001 Receiver ② — 영수증 파일 FTP 전송.
 *
 * <p>{@code OrderTbReceiver} 와 <b>같은 레코드 리스트</b>를 받는다. 두 Receiver 가 각자
 * 매핑하거나 각자 채번했다면 DB 행의 {@code ORDER_ID} 와 파일 라인의 {@code ORDER_ID} 가
 * 어긋나 두 시스템을 대조할 수 없게 된다. 여기서도 <b>값을 손보지 않는다</b> —
 * 하는 일은 순서를 바꿔 이어 붙이고 인코딩하는 표현 변환뿐이다.
 *
 * <h2>준비 단계에서 하는 일</h2>
 * <ol>
 *   <li><b>내용을 먼저 만든다.</b> 연결하기 전에 조립·인코딩 검증을 끝낸다 —
 *       올리다가 실패하면 서버에 잘린 파일이 남는다</li>
 *   <li>세션을 열고 {@code .tmp} 이름으로 업로드</li>
 *   <li><b>리스팅으로 파일명이 보존됐는지 확인</b> (아래 참조)</li>
 * </ol>
 * 여기까지 성공해야 {@link UploadedFileDelivery} 를 돌려준다. 정상 반환은
 * "지금 rename 하면 성공한다" 는 뜻이어야 한다.
 *
 * <h2>업로드하고 나서 반드시 다시 읽어 본다</h2>
 * 인코딩 사고는 <b>예외 없이 성공으로 보고된다.</b> 제어 채널 인코딩이 어긋나면
 * 한글 파일명이 {@code ?} 로 치환된 채 전송되고, 서버는 정상 응답을 준다.
 * 업로드 디렉터리에 있던 다른 지원자들의 {@code INSPIEN_???_...txt} 53개가 그 증거다.
 * <b>스스로 확인하지 않으면 면접 시연 자리에서 처음 알게 된다.</b>
 *
 * <p>검증은 "비ASCII 문자가 0자인지" 보다 강한 <b>정확 일치</b>로 한다. 서버가 돌려준 목록에
 * 우리가 보낸 이름과 <b>완전히 같은 이름</b>이 있어야 통과다. 치환·절단·정규화 중
 * 무엇이 일어나도 걸린다. 그리고 이 검증이 확정 <b>이전</b>에 있으므로, 실패하면
 * 아직 되돌릴 수 있다 — DB 도 함께 롤백된다.
 */
@Slf4j
public class ReceiptFileReceiver implements Receiver<OrderRecord> {

    private final FtpClientFactory clientFactory;
    private final FtpTargetProperties properties;
    private final ApplicantName applicantName;

    public ReceiptFileReceiver(FtpClientFactory clientFactory,
                               FtpTargetProperties properties,
                               ApplicantName applicantName) {
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.applicantName = applicantName;
    }

    @Override
    public Step step() {
        return Step.RECEIVER_FTP;
    }

    @Override
    public Delivery prepare(CanonicalMessage<List<OrderRecord>> message) {
        List<OrderRecord> records = message.payload();
        if (records == null || records.isEmpty()) {
            // 0건짜리 영수증은 회계 담당자에게 의미가 없고, 빈 파일이 수집 배치를 헷갈리게 한다.
            log.debug("[FTP] 전송 대상 0건 — 파일을 만들지 않는다");
            return Delivery.empty();
        }

        // 파일명의 시각은 '지금' 이 아니라 인터페이스 실행 시작 시각이다.
        String finalName = ReceiptFileName.of(applicantName, message.header().occurredAt());
        String tempName = finalName + properties.tempSuffix();

        // 연결 전에 조립·인코딩 검증을 끝낸다. 세션을 열어 놓고 실패하면 정리할 것만 늘어난다.
        byte[] content = ReceiptLineFormatter.render(records, properties.contentCharset());

        FTPClient client = clientFactory.open();
        try {
            upload(client, tempName, content);
            verifyUploadedName(client, tempName);

            log.debug("[FTP] {}행 업로드 완료 — {} (확정 대기)", records.size(), tempName);
            return new UploadedFileDelivery(client, tempName, finalName, records.size());

        } catch (RuntimeException e) {
            cleanupFailedUpload(client, tempName);
            throw e;
        }
    }

    /**
     * 임시 파일명으로 업로드.
     *
     * <p>{@code storeFile} 의 반환값을 반드시 본다. FTP 는 데이터 채널이 끊겨도
     * 예외가 아니라 <b>실패 응답</b>으로 답하는 경우가 있다.
     */
    private void upload(FTPClient client, String tempName, byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            if (!client.storeFile(tempName, in)) {
                throw new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR,
                        "업로드 거부됨: " + tempName + " (" + content.length + " bytes) — " + reply(client));
            }
        } catch (IOException e) {
            throw new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR,
                    "업로드 중 통신 오류: " + tempName, e);
        }
    }

    /**
     * 서버에 저장된 이름이 우리가 보낸 이름과 같은지 확인한다.
     *
     * <p>{@code NLST} 결과는 서버에 따라 경로가 붙어 오기도 하므로 마지막 구분자 뒤만 본다.
     */
    private void verifyUploadedName(FTPClient client, String expected) {
        if (!properties.verifyUploadedName()) {
            log.warn("[FTP] 파일명 검증이 꺼져 있다 (inspien.ftp.verify-uploaded-name=false). "
                    + "인코딩 손상이 성공으로 보고될 수 있다");
            return;
        }

        String[] names;
        try {
            names = client.listNames();
        } catch (IOException e) {
            throw new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR,
                    "업로드 후 리스팅 실패 — 파일명 보존을 확인할 수 없다: " + expected, e);
        }
        if (names == null) {
            throw new RetryableException(EaiErrorCode.FTP_UPLOAD_ERROR,
                    "업로드 후 리스팅이 비어 있다 — 파일명 보존을 확인할 수 없다: " + expected);
        }

        boolean preserved = Arrays.stream(names)
                .map(ReceiptFileReceiver::baseName)
                .anyMatch(expected::equals);

        if (!preserved) {
            throw new NonRetryableException(EaiErrorCode.FTP_ENCODING_ERROR, """
                    업로드한 파일명이 서버에서 그대로 보존되지 않았다.
                      보낸 이름   : %s
                      비ASCII 문자: %d자
                    제어 채널 인코딩(%s)이 서버와 맞지 않아 '?' 로 치환됐을 가능성이 크다.
                    한글이 인코딩 불가 문자로 바뀌어도 서버는 정상 응답을 준다 — 그래서 직접 확인한다.
                    """.formatted(expected, countNonAscii(expected), properties.controlEncoding()));
        }

        log.debug("[FTP] 파일명 보존 확인 — {} (비ASCII {}자)", expected, countNonAscii(expected));
    }

    /**
     * 준비 실패 시 뒷정리.
     *
     * <p>{@link UploadedFileDelivery} 가 아직 만들어지지 않았으므로 세션을 책임질 주체가 없다.
     * 업로드까지는 됐는데 이후 검증에서 실패한 경우라면 서버에 {@code .tmp} 가 남아 있으므로
     * 함께 지운다. 지우지 못해도 <b>예외를 바꾸지 않는다</b> — 원래의 실패 원인이 더 중요하다.
     */
    private void cleanupFailedUpload(FTPClient client, String tempName) {
        try {
            client.deleteFile(tempName);
        } catch (IOException e) {
            log.warn("[{}] 준비 실패 후 임시 파일 삭제 실패: {} — 수동 삭제 대상",
                    EaiErrorCode.FTP_COMPENSATION_FAILED.code(), tempName, e);
        } finally {
            FtpSessions.closeQuietly(client);
        }
    }

    private static String baseName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static long countNonAscii(String value) {
        return value.chars().filter(ch -> ch > 127).count();
    }

    private String reply(FTPClient client) {
        String replyString = client.getReplyString();
        return replyString == null ? "" : replyString.trim();
    }
}
