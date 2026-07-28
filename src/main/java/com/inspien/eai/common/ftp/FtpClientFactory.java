package com.inspien.eai.common.ftp;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.exception.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;

/**
 * FTP 세션 개설 — 순서가 곧 정확성인 절차.
 *
 * <h2>왜 별도 클래스인가</h2>
 * 아래 순서를 <b>한 단계라도 바꾸면 조용히 깨진다.</b> Receiver 안에 인라인으로 두면
 * 나중에 누군가 "로그인부터 하고 인코딩은 나중에" 로 정리하고 싶어지는데,
 * 그 변경은 테스트를 통과하고 업로드도 성공하며 파일명만 {@code ?} 로 바뀐다.
 * 절차를 한 곳에 가두고 이유를 적어 두는 것이 유일한 방어다.
 *
 * <h2>순서 (정의서 3.7)</h2>
 * <ol>
 *   <li><b>{@code setControlEncoding} — 반드시 {@code connect()} 이전.</b>
 *       commons-net 은 연결 시점에 제어 채널의 Reader/Writer 를 만들며, 그때 인코딩이 고정된다.
 *       연결 후에 바꾸면 이미 만들어진 스트림에는 반영되지 않는다</li>
 *   <li>{@code connect()} → 응답 코드 확인. FTP 는 연결이 성립해도 서버가 거절할 수 있다</li>
 *   <li>{@code FEAT} 에 {@code UTF8} 이 있으면 {@code OPTS UTF8 ON} 전송.
 *       우리가 UTF-8 로 보낼 것임을 서버에 <b>알려</b> 준다</li>
 *   <li>{@code login} → {@code setFileType(BINARY)} → passive.
 *       타입 설정은 로그인 뒤여야 한다 — 인증 전 {@code TYPE} 을 거절하는 서버가 있다</li>
 *   <li>업로드 디렉터리로 {@code CWD}</li>
 * </ol>
 *
 * <p><b>{@code BINARY} 인 이유:</b> ASCII 모드는 서버가 개행을 플랫폼에 맞춰 <b>변조</b>한다.
 * 영수증 파일의 종결자는 {@code \n} 으로 고정이며, {@code \r\n} 으로 바뀌면 포맷 위반이다.
 *
 * <h2>세션을 풀링하지 않는다</h2>
 * 요청마다 새로 연결한다. 서버 배너가 <b>"15분 무활동 시 연결 종료"</b> 를 명시하고 있어
 * (BOOT-001 실측) 풀에 넣어 두면 죽은 세션을 잡게 되고, 그 실패는 주문 요청 위에서 터진다.
 * TCP 핸드셰이크 + 로그인 비용은 주문 1건당 한 번뿐이라 이 규모에서는 문제가 되지 않는다.
 */
@Slf4j
public class FtpClientFactory {

    private final FtpConnectionSettings settings;
    private final FtpTargetProperties properties;

    public FtpClientFactory(FtpConnectionSettings settings, FtpTargetProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    /**
     * 연결·로그인·디렉터리 진입까지 끝난 클라이언트를 돌려준다.
     *
     * <p>반환된 클라이언트의 수명은 <b>호출자 책임</b>이다. 실패 시에는 이 메서드가
     * 내부에서 정리하고 예외를 던지므로, 호출자는 정상 반환된 경우만 신경 쓰면 된다.
     */
    public FTPClient open() {
        FTPClient client = newClient();
        try {
            client.connect(settings.host(), settings.port());
            requirePositive(client, "제어 채널 연결");

            enableUtf8IfSupported(client);

            if (!client.login(settings.user(), settings.password())) {
                // 자격 증명 문제는 재시도해도 같다. 크리덴셜은 메시지에 담지 않는다.
                throw new NonRetryableException(EaiErrorCode.FTP_CONN_ERROR,
                        "로그인 거부됨 — " + settings + ", " + reply(client));
            }

            client.setFileType(FTP.BINARY_FILE_TYPE);
            if (properties.passiveMode()) {
                client.enterLocalPassiveMode();
            }

            if (!settings.path().isEmpty() && !client.changeWorkingDirectory(settings.path())) {
                throw new NonRetryableException(EaiErrorCode.FTP_CONN_ERROR,
                        "업로드 디렉터리 진입 실패: " + settings.path() + " — " + reply(client));
            }

            log.debug("FTP 세션 개설 — {}, controlEncoding={}", settings, properties.controlEncoding());
            return client;

        } catch (IOException e) {
            FtpSessions.closeQuietly(client);
            throw new RetryableException(EaiErrorCode.FTP_CONN_ERROR,
                    "FTP 연결 실패 — " + settings, e);
        } catch (RuntimeException e) {
            FtpSessions.closeQuietly(client);
            throw e;
        }
    }

    /**
     * 연결 전 설정.
     *
     * <p>{@code setAutodetectUTF8(false)} 로 두고 UTF8 협상을 직접 한다.
     * 자동 감지에 맡기면 서버가 {@code FEAT} 에 {@code UTF8} 을 싣지 않았을 때
     * 라이브러리가 인코딩을 임의로 되돌릴 수 있고, 그 순간 파일명이 깨진다.
     * <b>인코딩은 우리가 정한다</b> — 서버의 선언은 참고 자료이지 결정권자가 아니다.
     */
    private FTPClient newClient() {
        FTPClient client = new FTPClient();
        client.setControlEncoding(properties.controlEncoding());
        client.setAutodetectUTF8(false);
        client.setConnectTimeout((int) properties.connectTimeout().toMillis());
        client.setDefaultTimeout((int) properties.dataTimeout().toMillis());
        client.setDataTimeout(properties.dataTimeout());
        return client;
    }

    /**
     * {@code OPTS UTF8 ON}.
     *
     * <p>서버가 지원을 선언하지 않아도 <b>실패시키지 않는다.</b> 우리는 이미 제어 채널을
     * UTF-8 로 열었고, 많은 서버가 선언 없이도 UTF-8 파일명을 그대로 저장한다.
     * 진짜 판정은 선언이 아니라 <b>업로드 후 리스팅</b>으로 한다 — 선언은 약속이고
     * 리스팅은 사실이다.
     */
    private void enableUtf8IfSupported(FTPClient client) throws IOException {
        if (!"UTF-8".equalsIgnoreCase(properties.controlCharset().name())) {
            return;
        }
        if (!client.hasFeature("UTF8")) {
            log.warn("FTP 서버가 FEAT 에 UTF8 을 선언하지 않는다. "
                    + "UTF-8 로 진행하되 업로드 후 파일명 검증에서 확인한다");
            return;
        }
        int code = client.sendCommand("OPTS", "UTF8 ON");
        if (!FTPReply.isPositiveCompletion(code)) {
            log.warn("OPTS UTF8 ON 거부됨 (code={}). 업로드 후 파일명 검증으로 판정한다", code);
        }
    }

    private void requirePositive(FTPClient client, String phase) {
        int code = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(code)) {
            throw new RetryableException(EaiErrorCode.FTP_CONN_ERROR,
                    phase + " 응답 비정상 (code=" + code + ") — " + settings);
        }
    }

    private String reply(FTPClient client) {
        String replyString = client.getReplyString();
        return replyString == null ? "" : replyString.trim();
    }
}
