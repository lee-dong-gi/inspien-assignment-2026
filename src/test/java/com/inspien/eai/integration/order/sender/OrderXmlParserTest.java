package com.inspien.eai.integration.order.sender;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.EaiException;
import com.inspien.eai.integration.order.Fixtures;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderXmlParser — 소스 XML 파싱")
class OrderXmlParserTest {

    private final OrderXmlParser parser = new OrderXmlParser();

    @Test
    @DisplayName("루트 엘리먼트가 없어도 파싱된다")
    void parsesDocumentWithoutRootElement() {
        OrderSourceMessage result = parser.parse(Fixtures.read(Fixtures.ORDER_SOURCE_MINI));

        assertAll(
                () -> assertEquals(3, result.headerCount()),
                () -> assertEquals(5, result.itemCount()));
    }

    @Test
    @DisplayName("ITEM 이 HEADER 순서와 무관해도 USER_ID 로 대응된다 — 문서 순서에 의존하지 않는다")
    void doesNotDependOnDocumentOrder() {
        OrderSourceMessage result = parser.parse(Fixtures.read(Fixtures.ORDER_SOURCE_MINI));

        // 픽스처에서 USER02 의 ITEM 은 USER02 의 HEADER 보다 먼저 등장한다.
        List<String> itemOwners = result.items().stream().map(SourceItem::userId).toList();
        List<String> headerIds = result.headers().stream().map(SourceHeader::userId).toList();

        assertAll(
                () -> assertEquals("USER02", itemOwners.get(0), "첫 ITEM 의 주인은 두 번째 HEADER 다"),
                () -> assertEquals("USER01", headerIds.get(0)),
                () -> assertEquals(2, itemOwners.stream().filter("USER01"::equals).count()));
    }

    @Test
    @DisplayName("필드 값이 정확히 옮겨진다 — PRICE 는 문자열 그대로")
    void mapsFieldsVerbatim() {
        OrderSourceMessage result = parser.parse(Fixtures.read(Fixtures.ORDER_SOURCE_MINI));

        SourceHeader first = result.headers().get(0);
        SourceItem sunglasses = result.items().stream()
                .filter(i -> "ITEM05".equals(i.itemId()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals("USER01", first.userId()),
                () -> assertEquals("김철수", first.name()),
                () -> assertEquals("서울특별시 강남구", first.address(), "주소 내부 공백은 보존한다"),
                () -> assertEquals("N", first.status()),
                () -> assertEquals("선글라스", sunglasses.itemName()),
                () -> assertEquals("95000", sunglasses.price(), "숫자로 변환하지 않는다"));
    }

    @Test
    @DisplayName("EUC-KR 바이트를 해독한다 — UTF-8 로 읽으면 깨질 입력")
    void decodesEucKrBytes() {
        String xml = """
                <HEADER>
                    <USER_ID>USER01</USER_ID>
                    <NAME>김철수</NAME>
                    <ADDRESS>서울특별시 강남구</ADDRESS>
                    <STATUS>N</STATUS>
                </HEADER>
                """;
        byte[] euckr = xml.getBytes(OrderXmlParser.SOURCE_CHARSET);

        OrderSourceMessage result = parser.parse(euckr);
        SourceHeader header = result.headers().get(0);

        assertAll(
                () -> assertEquals("김철수", header.name()),
                () -> assertEquals("서울특별시 강남구", header.address()),
                () -> assertFalse(header.name().contains("\uFFFD"), "치환 문자가 섞이면 안 된다"),
                () -> assertFalse(header.name().contains("?"), "물음표로 뭉개지면 안 된다"));
    }

    @Test
    @DisplayName("해독 불가 바이트는 치환하지 않고 실패시킨다")
    void failsLoudlyOnUndecodableBytes() {
        // 0xFF 는 EUC-KR 의 유효한 선행 바이트가 아니다.
        byte[] broken = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        EaiException thrown = assertThrows(EaiException.class, () -> parser.parse(broken));

        assertEquals(EaiErrorCode.SOURCE_ENCODING_ERROR, thrown.errorCode());
    }

    @Test
    @DisplayName("구조가 깨진 XML 은 파싱 오류로 구분된다 — 인코딩 오류와 다른 코드")
    void distinguishesStructuralErrorFromEncodingError() {
        EaiException thrown = assertThrows(EaiException.class,
                () -> parser.parse("<HEADER><USER_ID>USER01</HEADER>"));

        assertEquals(EaiErrorCode.SOURCE_PARSE_ERROR, thrown.errorCode());
    }

    @Test
    @DisplayName("빈 입력은 즉시 실패한다")
    void rejectsEmptyInput() {
        assertAll(
                () -> assertThrows(EaiException.class, () -> parser.parse("   ")),
                () -> assertThrows(EaiException.class, () -> parser.parse(new byte[0])));
    }

    @Test
    @DisplayName("DTD 를 차단한다 — 외부 엔티티 확장 공격 방지")
    void blocksDtd() {
        String withDtd = """
                <!DOCTYPE foo [<!ENTITY xxe "expanded">]>
                <HEADER>
                    <USER_ID>&xxe;</USER_ID>
                </HEADER>
                """;

        EaiException thrown = assertThrows(EaiException.class, () -> parser.parse(withDtd));

        assertTrue(thrown.errorCode() == EaiErrorCode.SOURCE_PARSE_ERROR);
    }
}
