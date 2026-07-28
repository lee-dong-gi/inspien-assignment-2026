package com.inspien.eai.integration.order.validator;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.ValidationResult.SkipReason;
import com.inspien.eai.engine.validator.ValidationResult.Violation;
import com.inspien.eai.integration.order.Fixtures;
import com.inspien.eai.integration.order.sender.OrderXmlParser;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderValidator — 구조 오류와 정합성 불일치의 분리")
class OrderValidatorTest {

    private final OrderXmlParser parser = new OrderXmlParser();
    private final OrderValidator validator = new OrderValidator();

    private CanonicalMessage<OrderSourceMessage> wrap(OrderSourceMessage payload) {
        return new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_ORD_001), payload);
    }

    private CanonicalMessage<OrderSourceMessage> miniFixture() {
        return wrap(parser.parse(Fixtures.read(Fixtures.ORDER_SOURCE_MINI)));
    }

    // ── 정합성 불일치: 건 단위 스킵 ────────────────────────────────

    @Test
    @DisplayName("고아 ITEM 과 빈 HEADER 를 건 단위로 스킵하고 나머지는 통과시킨다")
    void skipsUnmatchedRecordsAndKeepsTheRest() {
        ValidationResult<OrderSourceMessage> result = validator.validate(miniFixture());

        assertAll(
                () -> assertFalse(result.rejected(), "정합성 불일치는 전체 거부 사유가 아니다"),
                () -> assertEquals(3, result.skipped().size()),
                () -> assertEquals(2, result.accepted().headerCount()),
                () -> assertEquals(3, result.accepted().itemCount(), "적재될 행 수"));
    }

    @Test
    @DisplayName("스킵 사유를 구분해 집계한다 — 총계만으로는 원인을 못 찾는다")
    void reportsSkipReasonsSeparately() {
        ValidationResult<OrderSourceMessage> result = validator.validate(miniFixture());

        assertAll(
                () -> assertEquals(2, result.skipDetail().get(SkipReason.ORPHAN_ITEM.name())),
                () -> assertEquals(1, result.skipDetail().get(SkipReason.HEADER_WITHOUT_ITEM.name())));
    }

    @Test
    @DisplayName("스킵된 건은 통과 목록에서 완전히 빠진다")
    void excludesSkippedRecordsFromAccepted() {
        ValidationResult<OrderSourceMessage> result = validator.validate(miniFixture());

        List<String> acceptedItemOwners = result.accepted().items().stream()
                .map(SourceItem::userId).toList();
        List<String> acceptedHeaderIds = result.accepted().headers().stream()
                .map(SourceHeader::userId).toList();

        assertAll(
                () -> assertFalse(acceptedItemOwners.contains("USER77")),
                () -> assertFalse(acceptedItemOwners.contains("USER78")),
                () -> assertFalse(acceptedHeaderIds.contains("USER03")));
    }

    // ── 구조 오류: 전체 거부 ──────────────────────────────────────

    @Test
    @DisplayName("HEADER 필수 필드가 없으면 요청 전체를 거부한다 (V-01)")
    void rejectsWholeRequestWhenHeaderFieldMissing() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(new SourceHeader(1, null, "김철수", "서울특별시 강남구", "N")),
                List.of(new SourceItem(1, "USER01", "ITEM01", "운동화", "59000")));

        ValidationResult<OrderSourceMessage> result = validator.validate(wrap(source));

        assertAll(
                () -> assertTrue(result.rejected()),
                () -> assertTrue(hasRule(result.fatal(), "V-01")));
    }

    @Test
    @DisplayName("PRICE 가 숫자가 아니면 거부한다 (V-05)")
    void rejectsNonNumericPrice() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(new SourceHeader(1, "USER01", "김철수", "서울특별시 강남구", "N")),
                List.of(new SourceItem(1, "USER01", "ITEM01", "운동화", "59,000")));

        ValidationResult<OrderSourceMessage> result = validator.validate(wrap(source));

        assertAll(
                () -> assertTrue(result.rejected()),
                () -> assertTrue(hasRule(result.fatal(), "V-05")));
    }

    /**
     * 이 테스트가 이 클래스에서 가장 중요하다.
     *
     * <p>대상 DB 는 {@code NLS_LENGTH_SEMANTICS=BYTE} 다. 문자 수로 검증하면
     * 34자짜리 주소가 검증을 통과한 뒤 적재 시점에 {@code ORA-12899} 로 터진다.
     * 그때는 이미 트랜잭션이 시작된 뒤라 롤백·보상 비용이 발생한다.
     */
    @Test
    @DisplayName("길이는 문자 수가 아니라 UTF-8 바이트로 잰다 (V-06)")
    void measuresLengthInBytesNotCharacters() {
        String just99Bytes = "가".repeat(33);   // 33자 × 3바이트 = 99
        String just102Bytes = "가".repeat(34);  // 34자 × 3바이트 = 102

        assertAll(
                () -> assertEquals(99, OrderValidator.utf8Length(just99Bytes)),
                () -> assertEquals(102, OrderValidator.utf8Length(just102Bytes)),
                () -> assertEquals(34, just102Bytes.length(), "문자 수로는 상한 이내로 보인다"));

        ValidationResult<OrderSourceMessage> passing = validator.validate(wrap(withAddress(just99Bytes)));
        ValidationResult<OrderSourceMessage> failing = validator.validate(wrap(withAddress(just102Bytes)));

        assertAll(
                () -> assertFalse(passing.rejected(), "99바이트는 통과해야 한다"),
                () -> assertTrue(failing.rejected(), "102바이트는 거부해야 한다"),
                () -> assertTrue(hasRule(failing.fatal(), "V-06")));
    }

    @Test
    @DisplayName("위반 메시지에 개인정보 원문을 담지 않는다")
    void doesNotLeakPersonalDataIntoViolations() {
        String longAddress = "서울특별시 강남구 테헤란로 위워크빌딩 12층 1234호 어쩌구저쩌구 아파트 101동 202호";
        ValidationResult<OrderSourceMessage> result = validator.validate(wrap(withAddress(longAddress)));

        assertTrue(result.rejected());
        result.fatal().forEach(v -> assertFalse(v.detail().contains("테헤란로"),
                "위반 상세에 주소 원문이 들어가면 로그로 개인정보가 샌다"));
    }

    private OrderSourceMessage withAddress(String address) {
        return new OrderSourceMessage(
                List.of(new SourceHeader(1, "USER01", "김철수", address, "N")),
                List.of(new SourceItem(1, "USER01", "ITEM01", "운동화", "59000")));
    }

    private boolean hasRule(List<Violation> violations, String rule) {
        return violations.stream().anyMatch(v -> rule.equals(v.rule()));
    }
}
