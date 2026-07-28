package com.inspien.eai.engine.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InterfaceLogFormatter — 고정폭 열 조립")
class InterfaceLogFormatterTest {

    private static final String TX = "8f2c1a94-3d5e-4b71-9a02-1c7f6e8d4b23";

    @Test
    @DisplayName("열 개수와 폭이 고정된다 — 세로로 훑을 수 있어야 한다")
    void producesFixedWidthColumns() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "VALIDATOR", "PARTIAL", "63", "11", "0", "12", "ORPHAN_ITEM=7");

        String[] columns = line.split(" \\| ", -1);

        assertAll(
                () -> assertEquals(9, columns.length, "TIME 은 Logback 이 붙이므로 9열"),
                () -> assertEquals(10, columns[0].length(), "IF_ID"),
                () -> assertEquals(8, columns[1].length(), "TX_ID"),
                () -> assertEquals(13, columns[2].length(), "STEP"),
                () -> assertEquals(7, columns[3].length(), "RESULT"),
                () -> assertEquals(5, columns[4].length(), "OK"),
                () -> assertEquals(4, columns[5].length(), "SKIP"),
                () -> assertEquals(4, columns[6].length(), "FAIL"),
                () -> assertEquals(6, columns[7].length(), "MS"));
    }

    @Test
    @DisplayName("가장 긴 값이 들어가도 열이 밀리지 않는다")
    void widestValuesStillFit() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "RECEIVER_JDBC", "SUCCESS", "63", "11", "63", "10021", "x");

        String[] columns = line.split(" \\| ", -1);

        assertAll(
                () -> assertEquals("IF-ORD-001", columns[0], "IF_ID 는 정확히 10자"),
                () -> assertEquals("RECEIVER_JDBC", columns[2], "STEP 최장 13자"),
                () -> assertEquals("SUCCESS", columns[3], "RESULT 최장 7자"));
    }

    @Test
    @DisplayName("숫자는 우측 정렬된다 — 자릿수 차이가 눈에 띄어야 한다")
    void alignsNumbersToTheRight() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "MAPPER", "SUCCESS", "63", "0", "0", "16", null);

        String[] columns = line.split(" \\| ", -1);

        assertAll(
                () -> assertEquals("   63", columns[4]),
                () -> assertEquals("   0", columns[5]),
                () -> assertEquals("    16", columns[7]));
    }

    @Test
    @DisplayName("TX_ID 는 앞 8자로 자른다")
    void truncatesTxId() {
        assertAll(
                () -> assertEquals("8f2c1a94", InterfaceLogFormatter.shortTxId(TX)),
                () -> assertEquals("-", InterfaceLogFormatter.shortTxId(null)),
                () -> assertEquals("short", InterfaceLogFormatter.shortTxId("short")));
    }

    @Test
    @DisplayName("빈 값은 '-' 로 채운다 — 열이 밀린 것과 값이 없는 것을 구분하기 위해")
    void marksAbsentValues() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "START", "-", null, null, null, null, null);

        String[] columns = line.split(" \\| ", -1);

        assertAll(
                () -> assertEquals("    -", columns[4]),
                () -> assertEquals("   -", columns[5]),
                () -> assertEquals("     -", columns[7]),
                () -> assertEquals("-", columns[8]));
    }

    /**
     * 이 테스트가 중요한 이유: DETAIL 은 자유 형식이라 값이 흘러들 수 있는 유일한 열이다.
     * 여기에 구분자가 섞이면 열 구조가 통째로 어긋나 파싱이 조용히 잘못된 결과를 낸다.
     */
    @Test
    @DisplayName("DETAIL 의 구분자와 개행을 무력화한다 — 열 구조가 깨지면 안 된다")
    void sanitizesDetailColumn() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "RECEIVER_FTP", "FAIL", "0", "0", "63", "10021",
                "broken | pipe\nand newline");

        String[] columns = line.split(" \\| ", -1);

        assertAll(
                () -> assertEquals(9, columns.length, "DETAIL 때문에 열이 늘어나면 안 된다"),
                () -> assertFalse(columns[8].contains("|")),
                () -> assertFalse(columns[8].contains("\n")),
                () -> assertTrue(columns[8].contains("broken / pipe")));
    }

    @Test
    @DisplayName("지나치게 긴 DETAIL 은 잘라낸다")
    void truncatesOverlongDetail() {
        String line = InterfaceLogFormatter.format(
                "IF-ORD-001", TX, "SENDER", "FAIL", "0", "0", "0", "5", "x".repeat(500));

        String detail = line.split(" \\| ", -1)[8];

        assertAll(
                () -> assertEquals(120, detail.length()),
                () -> assertTrue(detail.endsWith("...")));
    }
}
