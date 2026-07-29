package com.inspien.eai.common.ftp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;

/**
 * FTP 전송 설정.
 *
 * <p>{@code JdbcTargetProperties} 와 마찬가지로 <b>접속정보는 여기 없다.</b>
 * 호스트·계정·경로는 BOOT-000 산출물({@code secrets/ftp.conn.properties})에서 온다.
 *
 * <h2>인코딩이 둘인 이유 (정의서 3.7)</h2>
 * <b>파일명과 파일 내용은 서로 다른 채널로 나간다.</b> 파일명은 제어 채널(명령)에 실리고,
 * 내용은 데이터 채널에 바이트 그대로 실린다. 따라서 인코딩도 따로 정해야 한다.
 *
 * <table border="1">
 *   <caption>인코딩 결정</caption>
 *   <tr><th>대상</th><th>값</th><th>근거</th></tr>
 *   <tr><td>파일 <b>이름</b></td><td>{@code UTF-8}</td>
 *       <td>서버가 {@code FEAT} 에서 UTF8 지원을 선언하며, <b>실제 업로드로 보존을 확인했다</b></td></tr>
 *   <tr><td>파일 <b>내용</b></td><td>{@code EUC-KR}</td>
 *       <td>원본 XML 인코딩 계승 (D-07). 판단이 갈릴 수 있어 설정으로 분리했다</td></tr>
 * </table>
 *
 * <p><b>{@code controlEncoding} 의 기본값이 {@code ISO-8859-1} 이라는 것이 이 과제의 함정이다.</b>
 * 그대로 두고 한글 파일명을 실으면 인코딩 불가 문자가 {@code 0x3F}({@code ?})로 치환된 채
 * 전송되고, <b>예외는 나지 않는다.</b> 업로드 디렉터리에 있던 다른 지원자들의
 * {@code INSPIEN_???_...txt} 파일 53개가 그 결과다 (BOOT-001 실측).
 *
 * <h2>임시 파일명 설정이 없는 이유 (D-21)</h2>
 * 초판에는 {@code temp-suffix} 가 있었다. {@code .tmp} 로 올린 뒤 rename 으로 확정하는
 * 설계였기 때문이다. 그런데 대상 서버가 rename 을 거부한다
 * ({@code 451 Rename/move failure: Operation not permitted}).
 * 업로드를 확정 단계로 옮기면서 임시 이름이라는 개념 자체가 사라졌으므로 설정도 제거했다 —
 * <b>쓰이지 않는 설정을 남겨 두면 다음 사람이 그것이 동작한다고 믿는다.</b>
 *
 * @param connectTimeout    제어 채널 연결 수립 한도
 * @param dataTimeout       데이터 채널 응답 대기 한도
 * @param controlEncoding   파일명이 실리는 제어 채널 인코딩. <b>반드시 명시</b>
 * @param contentEncoding   파일 내용 인코딩 (D-07)
 * @param passiveMode       방화벽/NAT 환경에서 active 는 데이터 채널이 막힌다. 기본 passive
 * @param verifyUploadedName 업로드 후 리스팅으로 <b>파일명과 크기</b> 보존을 확인할지.
 *                           <b>끄지 않는 것을 권한다</b> — 인코딩 손상도 잘린 파일도
 *                           예외 없이 성공으로 보고된다
 */
@ConfigurationProperties(prefix = "inspien.ftp")
public record FtpTargetProperties(
        Duration connectTimeout,
        Duration dataTimeout,
        String controlEncoding,
        String contentEncoding,
        Boolean passiveMode,
        Boolean verifyUploadedName
) {

    public FtpTargetProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (dataTimeout == null) {
            dataTimeout = Duration.ofSeconds(15);
        }
        if (controlEncoding == null || controlEncoding.isBlank()) {
            controlEncoding = "UTF-8";
        }
        if (contentEncoding == null || contentEncoding.isBlank()) {
            contentEncoding = "EUC-KR";
        }
        if (passiveMode == null) {
            passiveMode = Boolean.TRUE;
        }
        if (verifyUploadedName == null) {
            verifyUploadedName = Boolean.TRUE;
        }
    }

    public Charset controlCharset() {
        return charset(controlEncoding, "inspien.ftp.control-encoding");
    }

    public Charset contentCharset() {
        return charset(contentEncoding, "inspien.ftp.content-encoding");
    }

    /**
     * 인코딩 이름 해석.
     *
     * <p>기동 시점에 실패시킨다. 오타 하나로 잘못된 인코딩이 쓰이면 그 결과는 예외가 아니라
     * <b>깨진 파일</b>로 나타나고, 그때는 이미 상대 시스템에 올라간 뒤다.
     */
    private static Charset charset(String name, String property) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException(
                    property + " 값 '" + name + "' 은(는) 이 JVM 이 아는 인코딩이 아니다", e);
        }
    }
}
