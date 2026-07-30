package com.inspien.eai.integration.shipment.mapper;

import com.inspien.eai.common.id.IdGenerator;
import com.inspien.eai.engine.mapper.Mapper;
import com.inspien.eai.integration.shipment.source.PendingOrder;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import com.inspien.eai.integration.shipment.target.ShipmentRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * IF-SHP-001 매퍼 — 주문 행을 배송 행으로 변환한다.
 *
 * <h2>1:1 이다 — 그래서 {@code OrderMapper} 와 대비된다</h2>
 * <pre>
 *   OrderMapper    : HEADER 1 × ITEM n  →  n 행   (평탄화. 구조가 바뀐다)
 *   ShipmentMapper : ORDER_TB 1 행      →  1 행   (구조는 그대로, <b>컬럼이 줄어든다</b>)
 * </pre>
 *
 * <p>두 매퍼를 나란히 두면 <b>변환의 두 가지 얼굴</b>이 보인다. 하나는 계층을 평탄화하는
 * 구조 변환이고, 하나는 필요한 것만 골라내는 <b>취사선택</b>이다. 후자를 "변환이 아니다" 라고
 * 여기면 Mapper 를 건너뛰고 Sender 가 읽은 것을 Receiver 에 바로 넘기게 되는데,
 * 그러면 <b>"무엇을 넘기고 무엇을 버리는가" 라는 규칙이 코드에서 사라진다.</b>
 * 그 규칙이야말로 운송사와 쇼핑몰의 계약이고, 한 곳에 있어야 하는 것이다.
 *
 * <h2>버리는 것을 여기서 말한다</h2>
 * {@code NAME} · {@code ITEM_NAME} · {@code PRICE} · {@code STATUS} 는 전달하지 않는다.
 * 다만 <b>실제로 버려지는 자리는 Sender 다</b> — 애초에 조회하지 않는다.
 * 매퍼가 "버린다" 고 말할 수 있는 필드를 손에 들고 있지 않은 것은 의도된 결과이며,
 * 필요 없는 개인정보를 애플리케이션 안으로 들이지 않는 것이 가장 강한 통제다.
 * 그 판단의 기록은 {@code PendingOrder} 와 {@code ShipmentRecord} 의 javadoc 에 남겼다.
 *
 * <h2>채번은 여기서, 청크당 한 번</h2>
 * {@link IdGenerator#allocate(int)} 를 청크마다 정확히 한 번 호출해 전량을 선점한다.
 * 행마다 부르면 왕복이 행 수만큼 늘고, 중간에 실패하면 <b>절반만 번호가 붙은 상태</b>가 남는다.
 *
 * <p>{@code ORDER_ID} 와 <b>다른 카운터</b>를 쓴다({@code eai:seq:shipment}).
 * 하나를 공유하면 26,000 공간을 둘이 나눠 쓰게 되고, 주문 채번의 소진이
 * 배송 채번을 함께 끌어내린다. 두 카운터는 서로를 모르는 편이 낫다.
 *
 * <h2>전제</h2>
 * 입력은 <b>검증을 통과한</b> 청크다. 빈 배송지·키 누락 행은 {@code ShipmentValidator} 가
 * 이미 걷어냈다. 여기서 다시 걸러 내면 "몇 건이 왜 빠졌는가" 를 집계하는 지점이
 * 두 곳으로 흩어지고, 결과 보고에서 스킵 건수가 어긋난다.
 */
public class ShipmentMapper implements Mapper<ShipmentSourceMessage, ShipmentRecord> {

    private final IdGenerator idGenerator;
    private final String applicantKey;

    public ShipmentMapper(IdGenerator idGenerator, String applicantKey) {
        if (applicantKey == null || applicantKey.isBlank()) {
            // 이 값이 비면 전 행이 잘못된 PK 로 적재된다. 그런데 조회는 올바른 키로 했으므로
            // 배치는 "성공" 을 보고하고, 잘못된 행은 아무 조회에도 걸리지 않는다.
            // 발견이 가장 늦는 종류의 사고이므로 조립 시점에 끊는다.
            throw new IllegalArgumentException("APPLICANT_KEY 없이는 매핑할 수 없다. BOOT-000 산출물을 확인할 것");
        }
        this.idGenerator = idGenerator;
        this.applicantKey = applicantKey;
    }

    @Override
    public List<ShipmentRecord> map(ShipmentSourceMessage source) {
        List<PendingOrder> orders = source.orders();
        if (orders.isEmpty()) {
            // 채번을 호출하지 않는다. 0건에 번호를 태울 이유가 없다.
            return List.of();
        }

        // 전량 선점. 중간에 실패하면 한 건도 만들어지지 않는다.
        List<String> shipmentIds = idGenerator.allocate(orders.size());

        List<ShipmentRecord> records = new ArrayList<>(orders.size());
        for (int i = 0; i < orders.size(); i++) {
            PendingOrder order = orders.get(i);
            records.add(new ShipmentRecord(
                    shipmentIds.get(i),
                    applicantKey,
                    order.orderId(),
                    order.itemId(),
                    order.address()));
        }
        return List.copyOf(records);
    }
}
