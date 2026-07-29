package com.inspien.eai.integration.order;

import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.secret.ApplicantKey;
import com.inspien.eai.engine.log.InterfaceLogger;
import com.inspien.eai.engine.receiver.OrderedDeliveryCoordinator;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.mapper.OrderMapper;
import com.inspien.eai.integration.order.receiver.OrderTbReceiver;
import com.inspien.eai.integration.order.receiver.ReceiptFileReceiver;
import com.inspien.eai.integration.order.sender.OrderRestSender;
import com.inspien.eai.integration.order.sender.OrderXmlParser;
import com.inspien.eai.integration.order.target.OrderRecord;
import com.inspien.eai.integration.order.validator.OrderValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * IF-ORD-001 파이프라인 조립.
 *
 * <p>부품은 전부 <b>평범한 클래스</b>다. {@code @Component} 로 흩뿌리지 않고 여기서 손으로 잇는다.
 * 그래야 "무엇이 무엇에 의존하는가" 를 클래스들을 뒤지지 않고 이 한 파일에서 읽을 수 있고,
 * 단위 테스트에서 스프링 없이 그대로 {@code new} 할 수 있다.
 *
 * <p>수신처 조립({@code ORDER_TB} · 영수증 파일)은 {@code OrderReceiverConfig} 가 맡는다.
 * 여기서는 그 결과를 <b>순서대로 세우는 것</b>까지만 한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderFlowConfig {

    @Bean
    public OrderXmlParser orderXmlParser() {
        return new OrderXmlParser();
    }

    @Bean
    public OrderRestSender orderRestSender(OrderXmlParser orderXmlParser) {
        return new OrderRestSender(orderXmlParser);
    }

    @Bean
    public OrderValidator orderValidator() {
        return new OrderValidator();
    }

    /**
     * 매퍼는 <b>채번기를 쥐고 있다.</b> 매핑 실행당 한 번 전량을 선점해야
     * DB 행과 영수증 라인의 {@code ORDER_ID} 가 같아진다 (정의서 3.7).
     *
     * <p>{@code APPLICANT_KEY} 는 여기서 문자열로 푼다. 매퍼 안까지 타입을 들고 가지 않는 이유는
     * 매퍼가 그 값을 <b>조회 조건이 아니라 적재 값</b>으로만 쓰기 때문이다.
     * 타입으로 막고 싶었던 사고(WHERE 절 누락)는 매퍼에서 일어나지 않는다.
     */
    @Bean
    public OrderMapper orderMapper(@Qualifier("orderIdGenerator") SequentialIdGenerator orderIdGenerator,
                                   ApplicantKey applicantKey) {
        return new OrderMapper(orderIdGenerator, applicantKey.value());
    }

    /**
     * 파이프라인 조립.
     *
     * <p><b>수신처의 등록 순서가 이 프로젝트에서 가장 중요한 한 줄이다.</b>
     * JDBC 를 먼저, FTP 를 나중에 둔다 — 되돌리기가 불확실한 쪽(원격 파일 삭제)을 마지막에 확정해
     * 보상이 필요한 상황 자체를 줄인다. 순서를 뒤집으면 "영수증은 나갔는데 DB commit 실패" 가 되고,
     * 그때는 이미 배포된 영수증을 회수해야 한다 (정의서 3.9).
     *
     * <p>조율자를 빈으로 두지 않고 여기서 만드는 이유는 {@code EngineConfig} 참조 —
     * 타깃 타입에 대해 제네릭이고 상태가 없다.
     */
    @Bean
    public OrderIntegrationFlow orderIntegrationFlow(OrderRestSender orderRestSender,
                                                     OrderValidator orderValidator,
                                                     OrderMapper orderMapper,
                                                     OrderTbReceiver orderTbReceiver,
                                                     ReceiptFileReceiver receiptFileReceiver,
                                                     InterfaceLogger interfaceLogger) {
        List<Receiver<OrderRecord>> receivers = List.of(orderTbReceiver, receiptFileReceiver);

        return new OrderIntegrationFlow(
                orderRestSender,
                orderValidator,
                orderMapper,
                new OrderedDeliveryCoordinator<>(interfaceLogger),
                receivers,
                interfaceLogger);
    }
}
