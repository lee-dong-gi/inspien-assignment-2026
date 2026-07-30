package com.inspien.eai.integration.shipment.receiver;

import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.jdbc.JdbcTargetProperties;
import com.inspien.eai.common.jdbc.MaxIdSequenceSeeder;
import com.inspien.eai.common.jdbc.TargetTables;
import com.inspien.eai.common.secret.ApplicantKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * IF-SHP-001 의 수신처 조립 — {@code SHIPMENT_TB} 적재 + {@code ORDER_TB} 상태 갱신.
 *
 * <h2>수신처가 하나다 — 그래도 조립을 따로 둔다</h2>
 * {@code OrderReceiverConfig} 와 대칭을 유지하는 것이 목적이다. 인터페이스별 수신처 조립이
 * 같은 자리에 있으면, 인터페이스를 하나 더 추가할 사람이 <b>어디를 보고 따라 쓸지</b> 알 수 있다.
 *
 * <h2>배치 스케줄러와 조건을 분리했다</h2>
 * 이 설정은 {@code inspien.jdbc.enabled} 만 본다. {@code inspien.batch.shipment.enabled} 를
 * 꺼도 수신처와 채번 시딩은 살아 있어야 하는데, <b>수동 트리거</b>가 그것들을 쓰기 때문이다.
 * 자동 주기를 멈춘 상태에서 시연용으로 한 번만 돌리는 것이 시연 준비의 기본 동작이다.
 */
@Configuration
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentReceiverConfig {

    private static final String ID_COLUMN = "SHIPMENT_ID";

    /**
     * 두 테이블 이름을 <b>함께</b> 넘긴다.
     *
     * <p>{@code SHIPMENT_TB} 에 INSERT 하면서 {@code ORDER_TB} 를 UPDATE 하는 하나의 트랜잭션이므로,
     * 두 이름이 서로 다른 경로로 들어오면 엉뚱한 테이블을 갱신하는 조립 실수가 가능해진다
     * ({@link TargetTables} 참조).
     */
    @Bean
    public ShipmentTbReceiver shipmentTbReceiver(DataSource targetDataSource,
                                                 TargetTables targetTables,
                                                 JdbcTargetProperties properties) {
        return new ShipmentTbReceiver(
                targetDataSource,
                targetTables.shipmentTable(),
                targetTables.orderTable(),
                properties.batchSize(),
                properties.queryTimeoutSeconds());
    }

    /**
     * {@code SHIPMENT_ID} 채번 카운터 복원 (D-09 / D-13).
     *
     * <p>{@code ORDER_ID} 와 <b>별개의 카운터</b>이므로 시딩도 따로 해야 한다.
     * 이것을 빠뜨리면 Redis 를 비운 뒤 첫 배치가 {@code A000} 을 다시 발급하고,
     * 이미 적재된 배송 건과 PK 가 충돌한다 — 그리고 이 환경에는 {@code DELETE} 권한이 없어
     * 충돌한 쪽을 정리할 수도 없다.
     *
     * <p>{@code MAX(SHIPMENT_ID)} 로 복원할 수 있는 것은 채번 형식의 성질 때문이다 —
     * 고정 3자리 제로패딩이라 사전식 정렬 순서가 채번 순서와 일치한다.
     */
    @Bean(initMethod = "seed")
    public MaxIdSequenceSeeder shipmentIdSequenceSeeder(
            JdbcTemplate targetJdbcTemplate,
            @Qualifier("shipmentIdGenerator") SequentialIdGenerator shipmentIdGenerator,
            ApplicantKey applicantKey,
            TargetTables targetTables) {

        return new MaxIdSequenceSeeder(
                targetJdbcTemplate, shipmentIdGenerator, applicantKey,
                targetTables.shipmentTable(), ID_COLUMN);
    }
}
