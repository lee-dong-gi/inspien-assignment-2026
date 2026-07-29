package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.ftp.FtpClientFactory;
import com.inspien.eai.common.ftp.FtpSessions;
import com.inspien.eai.common.ftp.FtpTargetProperties;
import com.inspien.eai.common.ftp.PendingUploadDelivery;
import com.inspien.eai.common.secret.ApplicantName;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.target.OrderRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;

import java.util.List;

/**
 * IF-ORD-001 Receiver ② — 영수증 파일 FTP 전송.
 *
 * <p>{@code OrderTbReceiver} 와 <b>같은 레코드 리스트</b>를 받는다. 두 Receiver 가 각자
 * 매핑하거나 각자 채번했다면 DB 행의 {@code ORDER_ID} 와 파일 라인의 {@code ORDER_ID} 가
 * 어긋나 두 시스템을 대조할 수 없게 된다. 여기서도 <b>값을 손보지 않는다</b> —
 * 하는 일은 순서를 바꿔 이어 붙이고 인코딩하는 표현 변환뿐이다.
 *
 * <h2>준비 단계는 서버를 건드리지 않는다 (D-21)</h2>
 * <ol>
 *   <li>파일명 확정 — 시각은 '지금' 이 아니라 <b>인터페이스 실행 시작 시각</b>이다</li>
 *   <li>내용 조립 + 인코딩 검증 — 구분자·개행 혼입, 표현 불가 문자를 여기서 걸러낸다</li>
 *   <li>세션 개설 — 접속·로그인·{@code OPTS UTF8}·디렉터리 진입까지</li>
 * </ol>
 *
 * <p><b>업로드는 하지 않는다.</b> 대상 서버가 rename 도 삭제도 허용하지 않으므로
 * (실측: {@code 451 Operation not permitted} / {@code ORA-01031}), 준비 단계에서 원격에 무언가를
 * 쓰는 순간 <b>되돌릴 방법이 없는 상태</b>가 만들어진다. 그래서 쓰기를 확정 단계로 미룬다 —
 * 자세한 근거는 {@link PendingUploadDelivery} 참조.
 *
 * <p>대신 세션 개설까지는 준비 단계에서 끝낸다. 접속 불가·인증 실패·디렉터리 부재처럼
 * <b>가장 흔한 FTP 실패</b>는 여전히 DB 확정 전에 걸리며, 그때는 DB 가 온전히 롤백된다.
 * 준비가 정상 반환했다는 것은 "지금 업로드하면 성공할 가능성이 높다" 는 뜻이다 —
 * 초판처럼 "지금 rename 하면 성공한다" 만큼의 보장은 아니고, 그 차이가 이 설계의 값이다.
 *
 * <h2>내용을 연결보다 먼저 만드는 이유</h2>
 * 조립이나 인코딩에서 실패할 것이라면 <b>세션을 열기 전에</b> 실패하는 편이 낫다.
 * 순서를 뒤집으면 정리할 세션만 늘어나고, 동시 접속 5 제한 아래에서는 그 누수가
 * 곧 다음 주문의 접속 거부로 나타난다.
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

        String fileName = ReceiptFileName.of(applicantName, message.header().occurredAt());
        byte[] content = ReceiptLineFormatter.render(records, properties.contentCharset());

        FTPClient client = clientFactory.open();
        try {
            log.debug("[FTP] {}행 준비 완료 — {} ({} bytes, 업로드 대기)",
                    records.size(), fileName, content.length);
            return new PendingUploadDelivery(client, fileName, content, records.size(), properties);

        } catch (RuntimeException e) {
            // 여기까지 왔는데 실패했다면 서버에는 아무것도 쓰이지 않았다. 세션만 반납하면 된다.
            FtpSessions.closeQuietly(client);
            throw e;
        }
    }
}
