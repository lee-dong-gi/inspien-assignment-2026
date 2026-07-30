package com.inspien.eai.integration.shipment.validator;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.ValidationResult.Skip;
import com.inspien.eai.engine.validator.ValidationResult.SkipReason;
import com.inspien.eai.integration.shipment.source.PendingOrder;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IF-SHP-001 검증 규칙.
 *
 * <p>여기서 확인할 것은 규칙 자체보다 <b>규칙의 성격</b>이다 — 배치의 이상 데이터는
 * 전부 <b>건 단위 스킵</b>이며 치명적 위반이 하나도 없다 (D-23). 전체 거부는
 * "잘못된 1건 때문에 정상 99건이 영구히 배송되지 않는 상태" 를 만들고, 고쳐 줄 호출자가 없다.
 *
 * <p>그래서 "전건이 이상해도 거부하지 않는다" 가 이 클래스의 가장 중요한 단언이다.
 * 나중에 누군가 편의를 위해 {@code reject()} 를 하나 넣으면 그 테스트가 깨진다.
 */
@DisplayName("ShipmentValidator — 운송사에 넘겨도 되는 행인지 판정")
class ShipmentValidatorTest {

    private static final int CHUNK = 100;

    private final ShipmentValidator validator = new ShipmentValidator();

    @Test
    @DisplayName("온전한 행은 전부 통과하고 조회 순서를 유지한다")
    void keepsIntactRowsInQueryOrder() {
        ValidationResult<ShipmentSourceMessage> result = validate(
                order("A001", "ITEM01", "서울특별시 금천구"),
                order("A002", "ITEM02", "부산광역시 해운대구"));

        assertAll(
                () -> assertFalse(result.rejected()),
                () -> assertEquals(2, result.accepted().count()),
                () -> assertTrue(result.skipped().isEmpty()),
                // ORDER BY ORDER_ID 로 읽은 순서가 곧 발생 순서다. 먼저 들어온 주문이 먼저 배송된다.
                () -> assertEquals(List.of("A001", "A002"),
                        result.accepted().orders().stream().map(PendingOrder::orderId).toList()));
    }

    @Test
    @DisplayName("배송지가 없으면 제외한다 — 배송지 없는 배송 지시는 정보가 아니라 잡음이다")
    void skipsRowsWithoutShippingAddress() {
        ValidationResult<ShipmentSourceMessage> result = validate(
                order("A001", "ITEM01", "서울특별시 금천구"),
                order("A002", "ITEM02", null),
                order("A003", "ITEM03", ""),
                order("A004", "ITEM04", "   "));

        assertAll(
                () -> assertEquals(1, result.accepted().count()),
                () -> assertEquals(3, result.skipped().size()),
                () -> assertEquals(3, result.skipDetail().get("MISSING_SHIPPING_ADDRESS")),
                // 공백만 있는 값도 배송지가 아니다. Oracle 에서 빈 문자열은 NULL 이지만
                // 공백 문자열은 NULL 이 아니므로 조회 조건으로는 걸러지지 않는다.
                () -> assertEquals(List.of("A002", "A003", "A004"),
                        result.skipped().stream().map(Skip::key).toList()));
    }

    @Test
    @DisplayName("주문 키가 없으면 제외한다 — 원본을 가리키지 못하는 배송 지시는 조치할 수 없다")
    void skipsRowsWithoutOrderKey() {
        ValidationResult<ShipmentSourceMessage> result = validate(
                order("A001", null, "서울특별시 금천구"),
                order("A002", "  ", "서울특별시 금천구"),
                order("", "ITEM03", "서울특별시 금천구"));

        assertAll(
                () -> assertEquals(0, result.accepted().count()),
                () -> assertEquals(3, result.skipDetail().get("MISSING_ORDER_KEY")),
                () -> assertTrue(result.skipped().stream()
                        .allMatch(skip -> skip.reason() == SkipReason.MISSING_ORDER_KEY)));
    }

    @Test
    @DisplayName("키 누락을 배송지 누락보다 먼저 본다 — 어느 주문인지 말할 수 없으면 조치 대상을 지목할 수 없다")
    void reportsMissingKeyBeforeMissingAddress() {
        ValidationResult<ShipmentSourceMessage> result = validate(order("", "", ""));

        assertAll(
                () -> assertEquals(1, result.skipped().size()),
                () -> assertEquals(SkipReason.MISSING_ORDER_KEY, result.skipped().get(0).reason()),
                // ORDER_ID 가 없으면 "null" 이 아니라 부재임을 알 수 있게 남긴다.
                () -> assertEquals("(ORDER_ID 없음)", result.skipped().get(0).key()));
    }

    @Test
    @DisplayName("전건이 이상해도 거부하지 않는다 (D-23) — 배치에는 고쳐 다시 보낼 호출자가 없다")
    void neverRejectsTheWholeChunk() {
        ValidationResult<ShipmentSourceMessage> result = validate(
                order("A001", "ITEM01", null),
                order("A002", null, null));

        assertAll(
                () -> assertFalse(result.rejected(), "전체 거부는 정상 건까지 영구히 막는다"),
                () -> assertTrue(result.fatal().isEmpty()),
                // 통과 목록은 비었지만 null 이 아니다. 이후 단계가 null 검사를 하지 않아도 된다.
                () -> assertEquals(0, result.accepted().count()),
                () -> assertEquals(2, result.skipped().size()));
    }

    @Test
    @DisplayName("통과 목록은 청크 크기를 승계한다 — 걸러낸 결과로 종료 조건을 판정하면 남은 청크를 놓친다")
    void carriesChunkSizeIntoAcceptedPayload() {
        ValidationResult<ShipmentSourceMessage> result = validate(
                order("A001", "ITEM01", "서울특별시 금천구"),
                order("A002", "ITEM02", null));

        assertAll(
                () -> assertEquals(CHUNK, result.accepted().chunkSize()),
                // 걸러낸 쪽은 1건이라 '덜 찼다' 로 보인다. 그래서 mayHaveMore 는 원본에 대고 물어야 한다.
                () -> assertFalse(result.accepted().mayHaveMore()));
    }

    @Test
    @DisplayName("0건 입력도 정상이다 — 미전송 주문이 없는 것은 예외 상황이 아니다")
    void acceptsEmptyChunk() {
        ValidationResult<ShipmentSourceMessage> result = validate();

        assertAll(
                () -> assertFalse(result.rejected()),
                () -> assertTrue(result.accepted().isEmpty()),
                () -> assertTrue(result.skipDetail().isEmpty()));
    }

    @Test
    @DisplayName("길이 검증을 하지 않는다 — 원본과 대상이 같은 VARCHAR2(100 BYTE) 라 초과가 불가능하다")
    void doesNotValidateLength() {
        // 100바이트를 넘는 배송지. ORDER_TB 에 들어갈 수 있었다면 SHIPMENT_TB 에도 들어간다.
        // 일어날 수 없는 일을 검사하는 코드는 정작 일어나는 일에 대한 주의를 흐린다.
        String longAddress = "서울특별시 금천구 가산디지털단지로 ".repeat(5);

        ValidationResult<ShipmentSourceMessage> result = validate(order("A001", "ITEM01", longAddress));

        assertAll(
                () -> assertFalse(result.rejected()),
                () -> assertEquals(1, result.accepted().count()),
                () -> assertTrue(result.skipped().isEmpty()));
    }

    // ── 조립 ───────────────────────────────────────────────────

    private ValidationResult<ShipmentSourceMessage> validate(PendingOrder... orders) {
        ShipmentSourceMessage source = new ShipmentSourceMessage(List.of(orders), CHUNK);
        return validator.validate(new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_SHP_001), source));
    }

    private static PendingOrder order(String orderId, String itemId, String address) {
        return new PendingOrder(orderId, itemId, address);
    }
}
