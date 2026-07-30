package com.inspien.eai.integration.shipment;

import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.jdbc.TargetTables;
import com.inspien.eai.common.lock.DistributedLock;
import com.inspien.eai.common.secret.ApplicantKey;
import com.inspien.eai.engine.log.InterfaceLogger;
import com.inspien.eai.engine.receiver.OrderedDeliveryCoordinator;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.shipment.mapper.ShipmentMapper;
import com.inspien.eai.integration.shipment.receiver.ShipmentTbReceiver;
import com.inspien.eai.integration.shipment.sender.JdbcPollingSender;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;
import com.inspien.eai.integration.shipment.validator.ShipmentValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * IF-SHP-001 파이프라인 조립.
 *
 * <h2>{@code OrderFlowConfig} 와 나란히 놓고 보라</h2>
 * 두 파일의 구조가 같다 — Sender · Validator · Mapper 를 만들고, 수신처를 순서대로 세워
 * {@code OrderedDeliveryCoordinator} 에 넘긴다. <b>바뀐 것은 Sender 의 종류와 수신처 목록뿐</b>이다.
 *
 * <p>조립 파일을 나란히 두면 리뷰어가 <b>두 인터페이스의 차이를 한눈에 대조</b>할 수 있다.
 * 하나의 거대한 config 에 몰아 넣으면 그 대조가 불가능해지고, 인터페이스가 셋이 될 때
 * 어느 빈이 어느 인터페이스의 것인지 알 수 없게 된다.
 *
 * <h2>스케줄러는 여기에 없다</h2>
 * {@code ShipmentScheduleConfig} 로 분리했다. 조건이 다르기 때문이다 —
 * 자동 주기를 끈 상태에서도 <b>수동 트리거</b>는 살아 있어야 하고, 그러려면
 * 파이프라인 빈은 만들어지되 스케줄러만 빠져야 한다.
 *
 * <h2>부품은 평범한 클래스다</h2>
 * {@code @Component} 로 흩뿌리지 않고 여기서 손으로 잇는다. 그래야 "무엇이 무엇에
 * 의존하는가" 를 클래스들을 뒤지지 않고 이 한 파일에서 읽을 수 있고,
 * 단위 테스트에서 스프링 없이 그대로 {@code new} 할 수 있다.
 */
@Configuration
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentFlowConfig {

    /**
     * 폴링 Sender.
     *
     * <p>조회는 {@link JdbcTemplate} 으로 한다. 적재와 달리 <b>확정을 보류할 것이 없는</b>
     * 단발성 읽기이므로, 커넥션을 직접 붙잡는 방식({@code PendingCommitDelivery})을 쓸 이유가 없다.
     *
     * <p>{@code ApplicantKey} 를 타입 그대로 넘긴다. 매퍼에는 문자열로 풀어 넘기는 것과
     * 대비되는데, 이유가 있다 — 매퍼는 이 값을 <b>적재 값</b>으로만 쓰지만
     * Sender 는 <b>WHERE 절 조건</b>으로 쓴다. 타입으로 막고 싶었던 사고(조건 누락·자리 바꿈)가
     * 실제로 일어나는 쪽은 Sender 다.
     */
    @Bean
    public JdbcPollingSender jdbcPollingSender(JdbcTemplate targetJdbcTemplate,
                                              ApplicantKey applicantKey,
                                              TargetTables targetTables,
                                              ShipmentBatchProperties properties) {
        return new JdbcPollingSender(
                targetJdbcTemplate,
                applicantKey,
                targetTables.orderTable(),
                properties.chunkSize());
    }

    @Bean
    public ShipmentValidator shipmentValidator() {
        return new ShipmentValidator();
    }

    /**
     * 매퍼는 <b>배송 전용 채번기</b>를 쥔다.
     *
     * <p>{@code orderIdGenerator} 를 재사용하면 26,000 공간을 둘이 나눠 쓰게 되고,
     * 주문 채번의 소진이 배송 채번을 함께 끌어내린다. {@code @Qualifier} 로 못박는 이유는
     * 두 빈이 같은 타입이어서 <b>잘못 주입돼도 컴파일이 통과</b>하기 때문이다.
     */
    @Bean
    public ShipmentMapper shipmentMapper(@Qualifier("shipmentIdGenerator") SequentialIdGenerator shipmentIdGenerator,
                                         ApplicantKey applicantKey) {
        return new ShipmentMapper(shipmentIdGenerator, applicantKey.value());
    }

    /**
     * 파이프라인 조립.
     *
     * <p>수신처가 하나이므로 등록 순서가 정책이 되지 않는다 — IF-ORD-001 에서
     * "JDBC 먼저, FTP 나중" 이 가장 중요한 한 줄이었던 것과 대비된다.
     * 그럼에도 {@code List} 로 넘기고 조율자를 거치는 이유는 {@code ShipmentTbReceiver} javadoc 참조.
     *
     * <p>조율자를 빈으로 두지 않고 여기서 만드는 이유는 {@code EngineConfig} 참조 —
     * 타깃 타입에 대해 제네릭이고 상태가 없다.
     */
    @Bean
    public ShipmentIntegrationFlow shipmentIntegrationFlow(JdbcPollingSender jdbcPollingSender,
                                                          ShipmentValidator shipmentValidator,
                                                          ShipmentMapper shipmentMapper,
                                                          ShipmentTbReceiver shipmentTbReceiver,
                                                          DistributedLock distributedLock,
                                                          ShipmentBatchProperties properties,
                                                          InterfaceLogger interfaceLogger) {
        List<Receiver<ShipmentRecord>> receivers = List.of(shipmentTbReceiver);

        return new ShipmentIntegrationFlow(
                jdbcPollingSender,
                shipmentValidator,
                shipmentMapper,
                new OrderedDeliveryCoordinator<>(interfaceLogger),
                receivers,
                distributedLock,
                properties,
                interfaceLogger);
    }
}
