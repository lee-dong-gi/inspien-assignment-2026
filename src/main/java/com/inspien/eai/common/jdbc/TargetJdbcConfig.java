package com.inspien.eai.common.jdbc;

import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import com.inspien.eai.common.secret.SecretsLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 적재 대상 DB 배선 (데이터 평면).
 *
 * <h2>DataSource 가 하나인 이유 (D-04 / B3)</h2>
 * {@code ORDER_TB} 와 {@code SHIPMENT_TB} 는 <b>동일 인스턴스·동일 계정</b>이다
 * (BOOT-001 실측). 따라서 배치의 "SHIPMENT INSERT + ORDER 상태 갱신" 을
 * 단일 트랜잭션으로 묶을 수 있고, 커넥션 풀도 하나면 된다.
 *
 * <p>다만 이 사실은 <b>과거의 실측</b>이다. 접속정보가 바뀌어 두 대상이 갈라지면
 * 단일 트랜잭션 전제가 조용히 무너지고, 증상은 "가끔 SHIPMENT 만 들어가고 STATUS 는 N" 으로
 * 뒤늦게 나타난다. 그래서 기동할 때마다 두 접속정보를 실제로 비교한다.
 * <b>문서에 적힌 전제는 코드가 매번 확인해야 전제로 남는다.</b>
 *
 * <h2>{@code spring.datasource.*} 를 쓰지 않는다</h2>
 * 접속정보는 설정 파일이 아니라 BOOT-000 이 복호화해 만든 런타임 산출물에서 온다.
 * {@code spring.datasource} 에 값을 채우려면 크리덴셜을 설정 파일이나 환경변수로
 * 한 번 더 복사해야 하고, 그 복사본이 곧 커밋 사고의 경로가 된다.
 * {@code DataSourceAutoConfiguration} 을 배제한 이유가 이것이다
 * ({@link com.inspien.eai.InspienEaiApplication} 참조).
 *
 * <h2>제어 평면에서는 통째로 꺼진다</h2>
 * {@code inspien.jdbc.enabled=false} 면 이 설정 전체가 만들어지지 않는다.
 * BOOT-000 은 <b>접속정보를 받아 오는</b> 단계인데 그 단계에 DB 커넥션 풀이 떠야 한다면
 * 순환이고, HikariCP 는 기본적으로 풀 생성 시점에 접속을 시도하므로 실제로 기동이 막힌다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TargetJdbcConfig {

    /**
     * 적재 대상 커넥션 풀.
     *
     * <p>{@code initializationFailTimeout} 기본값(1ms 이상 = fail fast)을 그대로 둔다.
     * DB 에 닿지 못하는 상태로 애플리케이션이 뜨면 첫 주문 요청에서야 실패를 알게 되는데,
     * 그때는 이미 호출자가 기다리고 있다. 기동 시점에 끊는 편이 낫다.
     */
    @Bean(destroyMethod = "close")
    public DataSource targetDataSource(SecretsLoader secretsLoader, JdbcTargetProperties properties) {
        Properties orderConn = secretsLoader.load(BootstrapArtifactStore.ORDER_TB_CONN);
        Properties shipmentConn = secretsLoader.load(BootstrapArtifactStore.SHIPMENT_TB_CONN);
        requireSameInstance(secretsLoader, orderConn, shipmentConn);

        String url = secretsLoader.require(orderConn, "URL", "ORDER_TB_CONN");

        HikariConfig config = new HikariConfig();
        config.setPoolName("eai-target");
        config.setJdbcUrl(url);
        config.setUsername(secretsLoader.require(orderConn, "ID", "ORDER_TB_CONN"));
        config.setPassword(secretsLoader.require(orderConn, "PASSWORD", "ORDER_TB_CONN"));

        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(properties.poolWaitTimeout().toMillis());

        // 풀 기본값은 autoCommit=true 로 둔다. 트랜잭션이 필요한 쪽(Receiver)이
        // 빌린 뒤 명시적으로 끄고 반납 전에 되돌린다. 반대로 풀 기본값을 false 로 두면
        // 단순 조회(시딩·폴링 등)까지 트랜잭션을 열어 놓고 닫지 않는 상태가 생긴다.
        config.setAutoCommit(true);

        // jdbcUrl 방식에서도 dataSourceProperties 는 DriverManager 로 그대로 전달된다.
        // 기본값이 무한 대기이므로 반드시 명시한다 (정의서 5.2).
        config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT",
                String.valueOf(properties.connectTimeout().toMillis()));
        config.addDataSourceProperty("oracle.jdbc.ReadTimeout",
                String.valueOf(properties.readTimeout().toMillis()));

        log.info("[JDBC] 적재 대상 커넥션 풀 생성 — pool={}, max={}, connectTimeout={}, readTimeout={}, queryTimeout={}s",
                config.getPoolName(), properties.maximumPoolSize(),
                properties.connectTimeout(), properties.readTimeout(), properties.queryTimeoutSeconds());

        return new HikariDataSource(config);
    }

    /**
     * 조회용 템플릿.
     *
     * <p>적재(INSERT)에는 쓰지 않는다. {@link JdbcTemplate} 은 메서드 하나가 끝나면
     * 커넥션을 반납하므로, <b>확정을 보류한 채 커넥션을 붙잡아야 하는</b> 보상 트랜잭션과
     * 맞지 않는다(→ {@link PendingCommitDelivery}). 채번 시딩이나 배치 폴링처럼
     * 한 번에 끝나는 읽기에는 이쪽이 간결하다.
     */
    @Bean
    public JdbcTemplate targetJdbcTemplate(DataSource targetDataSource, JdbcTargetProperties properties) {
        JdbcTemplate template = new JdbcTemplate(targetDataSource);
        template.setQueryTimeout(properties.queryTimeoutSeconds());
        return template;
    }

    /**
     * 대상 테이블명.
     *
     * <p>빈으로 두는 이유는 이 값을 필요로 하는 config 가 넷이기 때문이다
     * (주문 적재 · 주문 채번 시딩 · 배송 적재 · 배송 채번 시딩).
     * 각자 {@code secrets/} 를 읽으면 같은 파싱이 넷으로 흩어진다 — {@link TargetTables} 참조.
     */
    @Bean
    public TargetTables targetTables(SecretsLoader secretsLoader) {
        TargetTables tables = TargetTables.from(secretsLoader);
        log.info("[JDBC] 대상 테이블 — 주문={}, 배송={}", tables.orderTable(), tables.shipmentTable());
        return tables;
    }

    /**
     * 두 대상이 같은 인스턴스·같은 계정인지 확인한다 (D-04 의 전제).
     *
     * <p>비밀번호는 비교 대상에서 뺀다. 같은 계정이라면 URL·ID 가 같고,
     * 비밀번호까지 맞대 보아야 얻는 것이 없다. 값을 비교하는 코드가 늘수록
     * 실패 메시지에 크리덴셜이 섞여 들어갈 여지도 늘어난다.
     */
    private void requireSameInstance(SecretsLoader loader, Properties order, Properties shipment) {
        String orderUrl = loader.require(order, "URL", "ORDER_TB_CONN");
        String shipmentUrl = loader.require(shipment, "URL", "SHIPMENT_TB_CONN");
        String orderId = loader.require(order, "ID", "ORDER_TB_CONN");
        String shipmentId = loader.require(shipment, "ID", "SHIPMENT_TB_CONN");

        if (!orderUrl.equals(shipmentUrl) || !orderId.equals(shipmentId)) {
            throw new IllegalStateException("""
                    [D-04] ORDER_TB 와 SHIPMENT_TB 의 접속 대상이 서로 다릅니다.
                    단일 DataSource · 단일 트랜잭션 전제(B3 실측)가 더 이상 성립하지 않습니다.
                    배치의 'SHIPMENT INSERT + ORDER 상태 갱신' 을 한 트랜잭션에 묶을 수 없으므로,
                    DataSource 를 분리하고 정합성 보장 방식을 다시 설계해야 합니다.
                    """);
        }
        log.debug("[D-04] 두 대상이 동일 인스턴스·동일 계정임을 확인 — 단일 DataSource 사용");
    }
}
