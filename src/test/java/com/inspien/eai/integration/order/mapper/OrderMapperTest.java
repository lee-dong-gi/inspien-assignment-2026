package com.inspien.eai.integration.order.mapper;

import com.inspien.eai.common.id.InMemoryIdSequence;
import com.inspien.eai.common.id.SequenceKey;
import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;
import com.inspien.eai.integration.order.target.OrderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderMapper — 계층 XML → 평탄 주문 행")
class OrderMapperTest {

    private static final String APPLICANT_KEY = "TESTKEY1";

    private InMemoryIdSequence sequence;
    private SequentialIdGenerator idGenerator;
    private OrderMapper mapper;

    @BeforeEach
    void setUp() {
        sequence = new InMemoryIdSequence();
        idGenerator = new SequentialIdGenerator(sequence, SequenceKey.ORDER);
        mapper = new OrderMapper(idGenerator, APPLICANT_KEY);
    }

    @Test
    @DisplayName("HEADER 1건 × ITEM 3건 → 3행. 헤더 정보가 전 행에 복제된다")
    void flattensOneToMany() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울특별시 금천구")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000"),
                        item(2, "USER1", "ITEM2", "티셔츠", "15800"),
                        item(3, "USER1", "ITEM3", "양말", "3000")));

        List<OrderRecord> records = mapper.map(source);

        assertAll(
                () -> assertEquals(3, records.size()),
                () -> assertTrue(records.stream().allMatch(r -> "USER1".equals(r.userId()))),
                () -> assertTrue(records.stream().allMatch(r -> "홍길동".equals(r.name()))),
                () -> assertTrue(records.stream().allMatch(r -> "서울특별시 금천구".equals(r.address()))),
                () -> assertEquals(List.of("ITEM1", "ITEM2", "ITEM3"),
                        records.stream().map(OrderRecord::itemId).toList()));
    }

    @Test
    @DisplayName("ORDER_ID 는 행마다 하나씩, 순서대로 붙는다 (D-03)")
    void assignsOneIdPerRow() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울"),
                        header(2, "USER2", "유관순", "구로")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000"),
                        item(2, "USER1", "ITEM2", "티셔츠", "15800"),
                        item(3, "USER2", "ITEM3", "양말", "3000")));

        List<OrderRecord> records = mapper.map(source);

        assertAll(
                () -> assertEquals(List.of("A000", "A001", "A002"),
                        records.stream().map(OrderRecord::orderId).toList()),
                () -> assertEquals(3, new HashSet<>(records.stream().map(OrderRecord::orderId).toList()).size()));
    }

    @Test
    @DisplayName("주문(헤더) 단위로 채번하지 않는다 — 그러면 ITEM 2건에서 PK 가 즉시 깨진다")
    void doesNotNumberPerHeader() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000"),
                        item(2, "USER1", "ITEM2", "티셔츠", "15800")));

        List<OrderRecord> records = mapper.map(source);

        // PK 는 (ORDER_ID, APPLICANT_KEY) 이고 APPLICANT_KEY 는 전 행 고정값이다.
        // 두 행의 ORDER_ID 가 같으면 두 번째 INSERT 가 ORA-00001 로 실패한다.
        assertAll(
                () -> assertEquals(2, records.size()),
                () -> assertEquals(2, records.stream().map(OrderRecord::orderId).distinct().count()),
                () -> assertEquals(1, records.stream().map(OrderRecord::applicantKey).distinct().count()));
    }

    @Test
    @DisplayName("채번은 실행당 한 번 — 63행이어도 카운터가 63만 오른다")
    void allocatesOnce() {
        OrderSourceMessage source = manyItems(63);

        List<OrderRecord> records = mapper.map(source);

        assertAll(
                () -> assertEquals(63, records.size()),
                () -> assertEquals(63, sequence.peek(SequenceKey.ORDER.key())));
    }

    @Test
    @DisplayName("문서 순서가 아니라 USER_ID 로 조인한다 — 실측 샘플의 ITEM 은 정렬돼 있지 않다")
    void joinsByUserIdNotDocumentOrder() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울"),
                        header(2, "USER2", "유관순", "구로")),
                List.of(item(1, "USER2", "ITEM_B", "티셔츠", "15800"),   // 순서가 섞여 있다
                        item(2, "USER1", "ITEM_A", "청바지", "21000"),
                        item(3, "USER2", "ITEM_C", "양말", "3000")));

        List<OrderRecord> records = mapper.map(source);

        assertAll(
                () -> assertEquals(3, records.size()),
                // 헤더 순서를 따라 USER1 → USER2 로 출력되고, 각 그룹 내부는 문서 등장 순서를 유지한다
                () -> assertEquals(List.of("ITEM_A", "ITEM_B", "ITEM_C"),
                        records.stream().map(OrderRecord::itemId).toList()),
                () -> assertEquals(List.of("홍길동", "유관순", "유관순"),
                        records.stream().map(OrderRecord::name).toList()));
    }

    @Test
    @DisplayName("STATUS 는 소스 값과 무관하게 N 으로 고정된다 (D-01)")
    void forcesStatusToUnsent() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(new SourceHeader(1, "USER1", "홍길동", "서울", "Y")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000")));

        List<OrderRecord> records = mapper.map(source);

        // 'Y' 를 존중했다면 이 주문은 배치 조회 조건(STATUS='N')에 걸리지 않아
        // 영원히 운송사로 전달되지 않는다.
        assertEquals("N", records.get(0).status());
    }

    @Test
    @DisplayName("PRICE 는 문자열 그대로 — 숫자로 바꾸면 원본과 달라진다")
    void preservesPriceVerbatim() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "095000")));

        assertEquals("095000", mapper.map(source).get(0).price(),
                "앞자리 0 이 사라지면 적재 값이 원본과 달라진다");
    }

    @Test
    @DisplayName("ADDRESS 내부 공백은 보존한다 — 주소에서 공백은 의미가 있다")
    void preservesInternalWhitespace() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울특별시 금천구 벚꽃로 278")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000")));

        assertEquals("서울특별시 금천구 벚꽃로 278", mapper.map(source).get(0).address());
    }

    @Test
    @DisplayName("APPLICANT_KEY 는 전 행 동일 고정값")
    void stampsApplicantKeyOnEveryRow() {
        List<OrderRecord> records = mapper.map(manyItems(10));

        assertTrue(records.stream().allMatch(r -> APPLICANT_KEY.equals(r.applicantKey())));
    }

    @Test
    @DisplayName("HEADER 의 USER_ID 가 중복이면 실패시킨다 — 조용히 한쪽을 고르면 중복 적재가 된다")
    void rejectsDuplicateJoinKeys() {
        OrderSourceMessage source = new OrderSourceMessage(
                List.of(header(1, "USER1", "홍길동", "서울"),
                        header(2, "USER1", "다른사람", "부산")),
                List.of(item(1, "USER1", "ITEM1", "청바지", "21000")));

        NonRetryableException e = assertThrows(NonRetryableException.class, () -> mapper.map(source));

        assertAll(
                () -> assertEquals(EaiErrorCode.MAPPING_ERROR, e.errorCode()),
                () -> assertEquals(0, sequence.peek(SequenceKey.ORDER.key()),
                        "실패한 매핑이 번호를 태우면 안 된다"),
                () -> assertTrue(e.getMessage().contains("HEADER[2]"), "위치를 지목해야 원본을 찾아갈 수 있다"),
                () -> assertFalse(e.getMessage().contains("다른사람"), "개인정보는 메시지에 담지 않는다"));
    }

    @Test
    @DisplayName("빈 소스는 채번하지 않는다")
    void emptySourceBurnsNoIds() {
        List<OrderRecord> records = mapper.map(OrderSourceMessage.empty());

        assertAll(
                () -> assertTrue(records.isEmpty()),
                () -> assertEquals(0, sequence.peek(SequenceKey.ORDER.key())));
    }

    @Test
    @DisplayName("반환 리스트는 불변 — 두 Receiver 가 공유하므로 어느 한쪽이 바꿀 수 없어야 한다")
    void returnsImmutableList() {
        List<OrderRecord> records = mapper.map(manyItems(2));

        assertThrows(UnsupportedOperationException.class, () -> records.remove(0));
    }

    @Test
    @DisplayName("APPLICANT_KEY 없이 조립할 수 없다")
    void requiresApplicantKey() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderMapper(idGenerator, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new OrderMapper(idGenerator, "  ")));
    }

    // ── 픽스처 ────────────────────────────────────────────────

    private static SourceHeader header(int sequence, String userId, String name, String address) {
        return new SourceHeader(sequence, userId, name, address, "N");
    }

    private static SourceItem item(int sequence, String userId, String itemId, String itemName, String price) {
        return new SourceItem(sequence, userId, itemId, itemName, price);
    }

    /** HEADER 1건에 ITEM {@code count} 건 */
    private static OrderSourceMessage manyItems(int count) {
        List<SourceItem> items = new java.util.ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            items.add(item(i, "USER1", "ITEM" + i, "품목" + i, String.valueOf(1000 * i)));
        }
        return new OrderSourceMessage(List.of(header(1, "USER1", "홍길동", "서울")), items);
    }
}
