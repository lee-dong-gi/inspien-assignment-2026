package com.inspien.eai.bootstrap.probe;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * BOOT-001 ② — FTP 전송 대상 사전 점검.
 *
 * <p>확인 항목
 * <ul>
 *   <li>제어 채널 배너 — 평문 FTP / SFTP 판별</li>
 *   <li>로그인 및 업로드 디렉터리 진입 가능 여부(B5)</li>
 *   <li><b>파일명 인코딩</b> — 과제는 파일명에 한글 참여자명을 요구한다.
 *       서버가 UTF-8 을 지원하는지, 기존 파일명이 어떤 인코딩으로 올라가 있는지 실측한다.</li>
 * </ul>
 *
 * <p>이 단계에서는 <b>파일을 쓰지 않는다.</b> 점검이 대상 시스템의 상태를 바꾸면 그건 점검이 아니다.
 */
@Slf4j
public class FtpProbe {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration DATA_TIMEOUT = Duration.ofSeconds(15);

    /** 비교할 제어 채널 인코딩 후보. 파일명이 어느 쪽에서 온전히 읽히는지가 판정 기준이다. */
    private static final List<String> ENCODING_CANDIDATES = List.of("UTF-8", "EUC-KR");

    public void probe(Properties conn, SecretsLoader loader, String source) {
        String host = loader.require(conn, "URL", source);
        int port = Integer.parseInt(loader.require(conn, "PORT", source));
        String user = loader.require(conn, "ID", source);
        String password = loader.require(conn, "PASSWORD", source);
        String path = conn.getProperty("PATH", "").trim();

        log.info("[BOOT-001] FTP 접속 시도 → {}:{}", host, port);

        // 1차 — 연결성·프로토콜·경로 확인
        inspect(host, port, user, password, path, "UTF-8", true);

        // 2차 — 파일명 인코딩 판정. 같은 디렉터리를 다른 인코딩으로 다시 읽어 비교한다.
        log.info("\n  ── 파일명 인코딩 비교 ──");
        for (String encoding : ENCODING_CANDIDATES) {
            List<String> names = inspect(host, port, user, password, path, encoding, false);
            log.info("    [{}] 상위 5건", encoding);
            names.stream().limit(5).forEach(name ->
                    log.info("      {}   (비ASCII {}자)", name, countNonAscii(name)));
        }
        log.info("""
                    판정 기준: 한글 참여자명이 온전히 보이는 쪽이 서버의 실제 파일명 인코딩이다.
                    양쪽 다 깨지면 기존 파일들이 잘못 올라간 것이므로, 우리는 서버의 UTF8 지원 여부(FEAT)를
                    근거로 인코딩을 정하고 그 결정을 README 에 남긴다.""");
    }

    /**
     * @param verbose true 면 배너·경로·FEAT 등 전체 정보를 로깅한다. false 면 파일명만 조용히 수집한다.
     * @return 디렉터리 파일명 목록
     */
    private List<String> inspect(String host, int port, String user, String password,
                                 String path, String controlEncoding, boolean verbose) {

        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftp.setDataTimeout(DATA_TIMEOUT);
        ftp.setControlEncoding(controlEncoding);
        // 서버가 UTF8 을 advertise 해도 우리가 지정한 인코딩을 유지시킨다(비교 실험이므로).
        ftp.setAutodetectUTF8(false);

        try {
            ftp.connect(host, port);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                throw new IllegalStateException(
                        "[BOOT-001] FTP 제어 채널 응답 비정상 (code=" + reply + "). "
                                + "포트 " + port + " 가 SFTP(SSH) 일 가능성을 확인하세요.");
            }

            if (verbose) {
                log.info("\n  ── 프로토콜 판정 ──\n    평문 FTP 확인 (SFTP 아님)\n    배너: {}",
                        ftp.getReplyString().trim());
            }

            if (!ftp.login(user, password)) {
                throw new IllegalStateException("[BOOT-001] FTP 로그인 실패: " + ftp.getReplyString());
            }

            // 방화벽/NAT 환경에서 active 모드는 데이터 채널이 막힌다. passive 를 기본으로 둔다.
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            if (verbose) {
                log.info("  로그인 성공 / 홈 디렉터리: {}", ftp.printWorkingDirectory());
                reportFeatures(ftp);
            }

            if (!path.isEmpty() && !ftp.changeWorkingDirectory(path)) {
                log.warn("  업로드 경로 진입 실패: {} ({})", path, ftp.getReplyString().trim());
                return List.of();
            }
            if (verbose) {
                log.info("  업로드 경로 진입 성공: {} → {}", path, ftp.printWorkingDirectory());
            }

            FTPFile[] files = ftp.listFiles();
            if (verbose) {
                log.info("  디렉터리 항목 수: {}", files.length);
            }
            List<String> names = Arrays.stream(files).map(FTPFile::getName).toList();

            ftp.logout();
            return names;

        } catch (IOException e) {
            throw new IllegalStateException("""
                    [BOOT-001] FTP 연결 실패: %s

                    포트 %d 가 SSH(SFTP) 라면 commons-net 으로는 붙지 않습니다.
                    그 경우 JSch 또는 Apache MINA sshd-sftp 로 교체해야 합니다.
                    """.formatted(e.getMessage(), port), e);
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException ignored) {
                    // 종료 중 실패는 점검 결과에 영향을 주지 않는다.
                }
            }
        }
    }

    /** 서버가 UTF8 을 지원한다고 선언하는지. 파일명 인코딩 결정의 근거가 된다. */
    private void reportFeatures(FTPClient ftp) throws IOException {
        ftp.features();
        String features = ftp.getReplyString();
        boolean utf8 = features != null && features.toUpperCase().contains("UTF8");
        log.info("  FEAT UTF8 지원 : {}", utf8 ? "예" : "아니오 (선언 없음)");
        log.info("  FEAT 원문:\n{}", features == null ? "(없음)" : features.trim());
    }

    private long countNonAscii(String value) {
        return value.chars().filter(ch -> ch > 127).count();
    }
}
