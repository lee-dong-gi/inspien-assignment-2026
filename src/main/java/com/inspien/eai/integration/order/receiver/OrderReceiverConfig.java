package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import com.inspien.eai.common.ftp.FtpClientFactory;
import com.inspien.eai.common.ftp.FtpTargetProperties;
import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.jdbc.JdbcTargetProperties;
import com.inspien.eai.common.jdbc.MaxIdSequenceSeeder;
import com.inspien.eai.common.secret.ApplicantKey;
import com.inspien.eai.common.secret.ApplicantName;
import com.inspien.eai.common.secret.SecretsLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * IF-ORD-001 의 수신처 조립 — JDBC(ORDER_TB) + FTP(영수증 파일).
 *
 * <p>테이블명을 상수로 박지 않고 BOOT-000 산출물({@code TABLE=ORDER_TB})에서 읽는다.
 * 대상 스키마는 우리가 정한 것이 아니라 <b>과제 측이 알려 준 것</b>이므로,
 * 알려 준 경로에서 읽는 것이 맞다. 코드에 박아 두면 두 곳이 어긋날 수 있고,
 * 그때 어느 쪽이 진실인지 판단할 근거가 없어진다.
 *
 * <p><b>두 Receiver 는 같은 레코드 리스트를 소비한다.</b> 여기서는 조립만 하고,
 * 전달 순서(JDBC 먼저 · FTP 나중)는 조율자가 정한다 — 정의서 3.9.
 */
@Configuration
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderReceiverConfig {

    private static final String ID_COLUMN = "ORDER_ID";

    @Bean
    public OrderTbReceiver orderTbReceiver(DataSource targetDataSource,
                                           SecretsLoader secretsLoader,
                                           JdbcTargetProperties properties) {
        return new OrderTbReceiver(
                targetDataSource,
                orderTable(secretsLoader),
                properties.batchSize(),
                properties.queryTimeoutSeconds());
    }

    /**
     * 영수증 파일 Receiver.
     *
     * <p>{@code inspien.ftp.enabled} 가 꺼져 {@link FtpClientFactory} 가 없으면
     * 이 빈도 만들어지지 못하고 기동이 실패한다. 의도한 동작이다 —
     * JDBC 만 켜고 FTP 를 끈 구성은 정상 구성이 아니며(정의서 3.1: 양쪽 모두 성공해야 성공),
     * 그런 구성으로 떠서 <b>영수증 없는 주문</b>을 만들어 내는 것보다 뜨지 않는 편이 낫다.
     */
    @Bean
    public ReceiptFileReceiver receiptFileReceiver(FtpClientFactory ftpClientFactory,
                                                   FtpTargetProperties ftpTargetProperties,
                                                   ApplicantName applicantName) {
        return new ReceiptFileReceiver(ftpClientFactory, ftpTargetProperties, applicantName);
    }

    /**
     * 채번 카운터 복원 (D-09).
     *
     * <p>{@code initMethod} 로 등록해 <b>컨텍스트 갱신 중</b>에 실행한다.
     * {@code ApplicationRunner} 는 웹 서버가 요청을 받기 시작한 뒤에 돌기 때문에,
     * 그 사이에 들어온 요청이 복원 전 카운터로 채번될 수 있다.
     *
     * <p>이 빈이 실패하면 애플리케이션이 기동하지 않는다. 의도한 동작이다 —
     * 카운터를 복원하지 못한 채 뜨면 첫 요청부터 PK 를 위반한다.
     */
    @Bean(initMethod = "seed")
    public MaxIdSequenceSeeder orderIdSequenceSeeder(JdbcTemplate targetJdbcTemplate,
                                                     @Qualifier("orderIdGenerator") SequentialIdGenerator orderIdGenerator,
                                                     ApplicantKey applicantKey,
                                                     SecretsLoader secretsLoader) {
        return new MaxIdSequenceSeeder(
                targetJdbcTemplate, orderIdGenerator, applicantKey, orderTable(secretsLoader), ID_COLUMN);
    }

    private String orderTable(SecretsLoader secretsLoader) {
        Properties conn = secretsLoader.load(BootstrapArtifactStore.ORDER_TB_CONN);
        return secretsLoader.require(conn, "TABLE", "ORDER_TB_CONN");
    }
}
