package com.inspien.eai.common.ftp;

import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import com.inspien.eai.common.secret.SecretsLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * FTP 전송 대상 배선 (데이터 평면).
 *
 * <p>{@code TargetJdbcConfig} 와 같은 이유로 제어 평면에서는 통째로 꺼진다.
 * 다만 JDBC 와 달리 이 설정은 <b>빈 생성 시점에 네트워크를 건드리지 않는다</b> —
 * 세션은 요청마다 열기 때문이다. 그럼에도 조건을 거는 것은
 * {@code secrets/ftp.conn.properties} 를 읽기 때문이고, 그 파일은 BOOT-000 이
 * <b>만들어 내는</b> 산출물이라 최초 실행 시점에는 존재하지 않는다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "inspien.ftp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FtpTargetConfig {

    private static final String SOURCE = "FTP_CONN";

    @Bean
    public FtpConnectionSettings ftpConnectionSettings(SecretsLoader secretsLoader) {
        Properties conn = secretsLoader.load(BootstrapArtifactStore.FTP_CONN);
        return new FtpConnectionSettings(
                secretsLoader.require(conn, "URL", SOURCE),
                parsePort(secretsLoader.require(conn, "PORT", SOURCE)),
                secretsLoader.require(conn, "ID", SOURCE),
                secretsLoader.require(conn, "PASSWORD", SOURCE),
                conn.getProperty("PATH", ""));
    }

    @Bean
    public FtpClientFactory ftpClientFactory(FtpConnectionSettings settings, FtpTargetProperties properties) {
        log.info("[FTP] 전송 대상 설정 — {}, 파일명={}, 내용={}, passive={}, 임시꼬리={}",
                settings, properties.controlEncoding(), properties.contentEncoding(),
                properties.passiveMode(), properties.tempSuffix());
        return new FtpClientFactory(settings, properties);
    }

    /**
     * 포트는 비표준이다 (30021 → 서버 내부 21 포워딩).
     *
     * <p>숫자가 아니면 기동 시점에 끊는다. 여기서 넘기면 첫 주문 요청에서
     * {@code NumberFormatException} 이 연계 실패로 둔갑해 원인이 가려진다.
     */
    private int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "FTP_CONN.PORT 가 숫자가 아니다: '" + raw + "'. BOOT-000 산출물을 확인할 것", e);
        }
    }
}
