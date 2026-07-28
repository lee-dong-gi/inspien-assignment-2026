package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.integration.order.target.OrderRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReceiptLineFormatter — 영수증 라인 조립")
class ReceiptLineFormatterTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    /**
     * 과제 PDF p.5 의 예시를 그대로 고정한다.
     *
     * <pre>
     *   A113^USER1^ITEM1^지원자키^홍길동^서울특별시 금천구^청바지^21000\n
     * </pre>
     *
     * 필드 개수·순서·구분자·종결자는 위반 시 감점 항목이므로, 문서가 아니라
     * <b>테스트가 지키게</b> 한다.
     */
    @Test
    @DisplayName("과제 예시 라인을 그대로 재현한다")
    void reproducesAssignmentExample() {
        OrderRecord record = new OrderRecord(
                "A113", "지원자키", "USER1", "ITEM1",
                "홍길동", "서울특별시 금천구", "청바지", "21000", "N");

        assertEquals("A113^USER1^ITEM1^지원자키^홍길동^서울특별시 금천구^청바지^21000",
                ReceiptLineFormatter.format(record, 0));
    }

    @Test
    @DisplayName("STATUS 는 파일에 포함되지 않는다 — DB 9필드 / 파일 8필드")
    void omitsStatus() {
        String line = ReceiptLineFormatter.format(sample("A113", "N"), 0);

        assertAll(
                () -> assertEquals(8, line.split("\\^", -1).length),
                () -> assertFalse(line.endsWith("^N"), "STATUS 가 붙으면 9필드가 된다"));
    }

    @Test
    @DisplayName("APPLICANT_KEY 는 4번째다 — DB 컬럼 순서(2번째)와 다르다")
    void applicantKeyIsFourthNotSecond() {
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지", "21000", "N");

        String[] fields = ReceiptLineFormatter.format(record, 0).split("\\^", -1);

        assertAll(
                () -> assertEquals("A113", fields[0]),
                () -> assertEquals("USER1", fields[1]),
                () -> assertEquals("ITEM1", fields[2]),
                () -> assertEquals("KEY00001", fields[3], "DB 순서를 그대로 join 하면 여기가 USER1 이 된다"),
                () -> assertEquals("홍길동", fields[4]),
                () -> assertEquals("서울", fields[5]),
                () -> assertEquals("청바지", fields[6]),
                () -> assertEquals("21000", fields[7]));
    }

    @Test
    @DisplayName("마지막 라인에도 종결자를 붙인다")
    void terminatesEveryLineIncludingLast() {
        byte[] rendered = ReceiptLineFormatter.render(
                List.of(sample("A113", "N"), sample("B114", "N")), EUC_KR);

        String content = new String(rendered, EUC_KR);

        assertAll(
                () -> assertTrue(content.endsWith("\n")),
                () -> assertEquals(2, content.split("\n", -1).length - 1, "라인 2개 = 종결자 2개"),
                () -> assertFalse(content.contains("\r"), "CR 이 섞이면 포맷 위반이다"));
    }

    @Test
    @DisplayName("내용은 EUC-KR 로 인코딩한다 — 한글이 왕복해도 같아야 한다")
    void encodesContentAsEucKr() {
        byte[] rendered = ReceiptLineFormatter.render(List.of(sample("A113", "N")), EUC_KR);

        assertAll(
                () -> assertTrue(new String(rendered, EUC_KR).contains("홍길동")),
                () -> assertFalse(new String(rendered, StandardCharsets.UTF_8).contains("홍길동"),
                        "UTF-8 로 읽으면 깨져야 정상 — EUC-KR 로 쓴 것이 맞다는 뜻"));
    }

    @Test
    @DisplayName("주소에 구분자가 섞이면 실패시킨다 — 수신 측이 필드 수를 잘못 읽는다")
    void rejectsDelimiterInValue() {
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울^금천구", "청바지", "21000", "N");

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> ReceiptLineFormatter.format(record, 7));

        assertAll(
                () -> assertEquals(EaiErrorCode.MAPPING_ERROR, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("ADDRESS")),
                () -> assertTrue(e.getMessage().contains("7"), "행 번호로 원본을 찾아갈 수 있어야 한다"),
                () -> assertFalse(e.getMessage().contains("서울^금천구"), "값은 메시지에 담지 않는다"));
    }

    @Test
    @DisplayName("값에 개행이 섞이면 실패시킨다 — 한 건이 두 라인으로 쪼개진다")
    void rejectsNewlineInValue() {
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍\n길동", "서울", "청바지", "21000", "N");

        assertEquals(EaiErrorCode.MAPPING_ERROR,
                assertThrows(NonRetryableException.class,
                        () -> ReceiptLineFormatter.format(record, 0)).errorCode());
    }

    @Test
    @DisplayName("EUC-KR 로 표현할 수 없는 문자는 '?' 로 나가기 전에 끊는다")
    void rejectsUnencodableCharacter() {
        // 😀 는 EUC-KR 에 없다. getBytes 는 예외 없이 '?' 로 바꾼다.
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지😀", "21000", "N");

        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> ReceiptLineFormatter.render(List.of(record), EUC_KR));

        assertAll(
                () -> assertEquals(EaiErrorCode.FTP_ENCODING_ERROR, e.errorCode()),
                () -> assertTrue(e.getMessage().contains("EUC-KR")),
                () -> assertTrue(e.getMessage().contains("U+"), "어느 문자가 문제인지 코드포인트로 알려준다"));
    }

    @Test
    @DisplayName("같은 문자라도 UTF-8 이면 통과한다 — 인코딩 결정에 종속된 검사다")
    void unencodableIsRelativeToCharset() {
        OrderRecord record = new OrderRecord(
                "A113", "KEY00001", "USER1", "ITEM1", "홍길동", "서울", "청바지😀", "21000", "N");

        byte[] rendered = ReceiptLineFormatter.render(List.of(record), StandardCharsets.UTF_8);

        assertTrue(new String(rendered, StandardCharsets.UTF_8).contains("😀"));
    }

    @Test
    @DisplayName("0건이면 빈 바이트 배열이다")
    void rendersEmptyForNoRecords() {
        assertEquals(0, ReceiptLineFormatter.render(List.of(), EUC_KR).length);
    }

    private OrderRecord sample(String orderId, String status) {
        return new OrderRecord(orderId, "KEY00001", "USER1", "ITEM1",
                "홍길동", "서울특별시 금천구", "청바지", "21000", status);
    }
}
