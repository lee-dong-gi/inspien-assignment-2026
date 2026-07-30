package com.inspien.eai.integration.shipment.validator;

import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.ValidationResult.Skip;
import com.inspien.eai.engine.validator.ValidationResult.SkipReason;
import com.inspien.eai.engine.validator.Validator;
import com.inspien.eai.integration.shipment.source.PendingOrder;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * IF-SHP-001 검증 — 운송사에 넘겨도 되는 행인지 판정한다.
 *
 * <h2>"우리가 넣은 데이터인데 왜 또 검증하는가"</h2>
 * 정당한 물음이고, 답은 <b>ORDER_TB 의 컬럼이 NULL 을 허용한다</b>는 것이다
 * ({@code ORDER_ID}·{@code APPLICANT_KEY} 만 NOT NULL — 정의서 1.1 실측).
 * 우리 Validator 를 거치지 않은 행이 섞일 경로는 실제로 존재한다.
 *
 * <ul>
 *   <li>검증이 없던 이전 버전이 적재한 행</li>
 *   <li>사람이 SQL 로 직접 넣은 행 (시연 준비 중 흔히 한다)</li>
 * </ul>
 *
 * <p>이것이 EAI 의 기본 태도이기도 하다 — <b>송신 시스템이 온전할 것이라고 전제하지 않는다.</b>
 * 여기서 {@code ORDER_TB} 는 우리 것이 아니라 <b>송신 시스템</b>이다. 우리가 방금 그 시스템에
 * 데이터를 넣었다는 사실은 우연이고, 인터페이스의 계약은 그 우연에 기대지 않는다.
 *
 * <h2>전부 스킵이다 — 치명적 위반을 두지 않는다 (D-23)</h2>
 * IF-ORD-001 은 구조 오류를 만나면 <b>요청 전체를 거부</b>한다. 호출자가 기다리고 있어서
 * 고쳐 다시 보낼 수 있기 때문이다.
 *
 * <p>배치에는 그 호출자가 없다. 전체 거부는 <b>이번 주기에 아무것도 처리하지 않는다</b>는 뜻이고,
 * 다음 주기도, 그다음 주기도 같은 행을 만나 같은 판단을 한다. 결과는 잘못된 1건 때문에
 * <b>정상 99건이 영구히 배송되지 않는 상태</b>다. 그래서 이쪽의 이상 데이터는 전부
 * 건 단위 스킵이며, 이것이 D-02(부분 처리)를 배치의 성격에 맞게 옮긴 것이다.
 *
 * <h2>스킵된 행은 사라지지 않는다 — 그리고 그것이 문제를 만든다</h2>
 * 스킵하면 {@code STATUS='N'} 으로 남고, <b>다음 주기가 같은 행을 다시 읽는다.</b>
 * 5분마다 같은 경고가 영원히 반복된다는 뜻이다.
 *
 * <p>{@code STATUS='E'} 같은 값으로 밀어내면 반복은 멈추지만, 그것은
 * <b>대상 시스템의 어휘를 우리가 늘리는 일</b>이다 — 과제의 전제(기존 스키마는 불변 조건)와
 * 정면으로 충돌하고, 그 값을 해석할 수 있는 시스템은 세상에 우리뿐이 된다.
 * 그렇다고 빈 배송지를 적재하고 {@code 'Y'} 로 닫아 버리면 <b>다시 다룰 기회가 사라진다</b>
 * (append-only 환경이므로 되돌릴 수 없다).
 *
 * <p>그래서 <b>반복을 감수하고 매번 기록한다.</b> 반복되는 경고는 잡음이 아니라
 * "원본 데이터를 고쳐야 한다" 는 지속적 신호이며, 조치 주체는 우리가 아니라
 * {@code ORDER_TB} 의 소유자다. 다만 청크가 통째로 스킵되는 상황은
 * 배치를 멈추게 하므로 파이프라인이 별도로 감지한다 ({@code ShipmentIntegrationFlow}).
 *
 * <h2>길이 검증을 하지 않는다</h2>
 * {@code ORDER_TB} 와 {@code SHIPMENT_TB} 의 대응 컬럼이 <b>모두 {@code VARCHAR2(100 BYTE)}</b>
 * 로 같다(정의서 4.3 실측). 원본에 들어갈 수 있었던 값은 대상에도 반드시 들어간다 —
 * 초과가 <b>구조적으로 불가능</b>하다.
 *
 * <p>V-06 을 형식적으로 한 번 더 넣을 수도 있었지만 넣지 않았다.
 * <b>검증은 방어가 아니라 판단이다.</b> 일어날 수 없는 일을 검사하는 코드는 읽는 사람에게
 * "이 경우가 일어날 수 있다" 는 잘못된 정보를 주고, 정작 일어나는 일(빈 배송지)에 대한
 * 주의를 흐린다. 두 컬럼의 규격이 갈라지는 날 이 판단은 다시 해야 하며,
 * 그 근거는 정의서 4.3 에 실측값으로 남아 있다.
 */
@Slf4j
public class ShipmentValidator implements Validator<ShipmentSourceMessage> {

    @Override
    public ValidationResult<ShipmentSourceMessage> validate(CanonicalMessage<ShipmentSourceMessage> message) {
        ShipmentSourceMessage source = message.payload();

        List<PendingOrder> accepted = new ArrayList<>(source.count());
        List<Skip> skipped = new ArrayList<>();

        for (PendingOrder order : source.orders()) {
            SkipReason reason = reasonToSkip(order);
            if (reason == null) {
                accepted.add(order);
                continue;
            }
            skipped.add(new Skip(reason, keyOf(order)));

            // 개별 행을 여기서 한 번 남긴다. 결과 집계만 남기면 "어느 주문이 문제인가" 를
            // 알 수 없고, 원본을 고쳐야 하는 사람에게 줄 정보가 없어진다.
            // ADDRESS 값 자체는 담지 않는다 — 개인정보다. 없다는 사실만 남긴다.
            log.warn("[IF-SHP-001] 전송 대상에서 제외 — {} (ORDER_ID={})", reason, keyOf(order));
        }

        // fatal 은 항상 비어 있다. 그럼에도 reject() 대신 3-인자 생성자를 쓰는 것은
        // "치명적 위반이 없다" 가 이 Validator 의 결정임을 코드에 남기기 위해서다.
        return new ValidationResult<>(source.withOrders(accepted), List.of(), skipped);
    }

    /**
     * 제외 사유. 없으면 {@code null}.
     *
     * <p>키 누락을 먼저 본다. {@code ORDER_ID} 가 없으면 <b>어느 주문인지조차 말할 수 없어</b>
     * 배송지 문제를 보고해도 조치할 대상을 지목할 수 없기 때문이다.
     */
    private SkipReason reasonToSkip(PendingOrder order) {
        if (isBlank(order.orderId()) || isBlank(order.itemId())) {
            return SkipReason.MISSING_ORDER_KEY;
        }
        if (isBlank(order.address())) {
            return SkipReason.MISSING_SHIPPING_ADDRESS;
        }
        return null;
    }

    /**
     * 추적용 키.
     *
     * <p>{@code ORDER_ID} 가 비어 있을 수도 있으므로 그때는 그 사실을 표시한다.
     * {@code null} 을 그대로 로그에 흘리면 {@code "null"} 이 찍히고,
     * 읽는 사람은 그것이 값인지 부재인지 구분할 수 없다.
     */
    private String keyOf(PendingOrder order) {
        return isBlank(order.orderId()) ? "(ORDER_ID 없음)" : order.orderId();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
