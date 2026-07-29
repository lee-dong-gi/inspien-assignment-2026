package com.inspien.eai.integration.order.sender;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.sender.Sender;
import com.inspien.eai.integration.order.source.OrderSourceMessage;

/**
 * IF-ORD-001 Sender — REST 로 들어온 주문 XML 을 표준 메시지로 감싼다.
 *
 * <p>하는 일은 {@link OrderXmlParser} 호출 하나다. 그런데도 이 클래스를 두는 이유는
 * <b>파서가 {@link Sender} 를 직접 구현하면 안 되기 때문</b>이다. 파서는 "EUC-KR XML 을 읽는 법" 을
 * 아는 도구이고, Sender 는 "이 인터페이스가 어떤 트리거로 시작되는가" 를 아는 어댑터다.
 * 둘을 합치면 시나리오 2 에서 <b>같은 파서를 재사용할 수 없게</b> 되고,
 * 파서를 단위 테스트할 때 표준 메시지 껍데기를 함께 끌고 와야 한다.
 *
 * <h2>{@code byte[]} 를 받는 이유 — 문자열로 받으면 이미 늦었다</h2>
 * 소스 XML 은 <b>선언부가 없는 EUC-KR</b> 이다. 컨트롤러가 {@code String} 으로 받으면
 * 서블릿 컨테이너가 먼저 디코딩하는데, 그 시점의 문자셋은 요청 헤더와 컨테이너 기본값이 정한다.
 * 송신 측이 {@code charset} 을 안 붙이면 대부분 ISO-8859-1 또는 UTF-8 로 해독되고,
 * <b>한글은 그 자리에서 이미 깨진 뒤</b>다. 우리가 아무리 뒤에서 EUC-KR 을 지정해도 복구되지 않는다.
 *
 * <p>그래서 원본 바이트를 그대로 받아 <b>해독 시점을 우리가 소유한다.</b>
 * {@code OrderXmlParser} 는 엄격 모드로 해독하므로, 실제로 EUC-KR 이 아닌 것이 들어오면
 * 조용히 치환되지 않고 {@code EAI-1004} 로 즉시 드러난다.
 */
public class OrderRestSender implements Sender<byte[], OrderSourceMessage> {

    private final OrderXmlParser parser;

    public OrderRestSender(OrderXmlParser parser) {
        this.parser = parser;
    }

    @Override
    public InterfaceId ifId() {
        return InterfaceId.IF_ORD_001;
    }

    @Override
    public CanonicalMessage<OrderSourceMessage> receive(MessageHeader header, byte[] trigger) {
        if (trigger == null || trigger.length == 0) {
            // 빈 본문을 0건 성공으로 처리하지 않는다. 호출자는 무언가를 보냈다고 믿고 있다.
            throw new NonRetryableException(EaiErrorCode.SOURCE_PARSE_ERROR, "요청 본문이 비어 있다");
        }
        return new CanonicalMessage<>(header, parser.parse(trigger));
    }
}
