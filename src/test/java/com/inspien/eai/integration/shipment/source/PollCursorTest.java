package com.inspien.eai.integration.shipment.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * keyset 페이징 커서 (D-26).
 *
 * <p>이 타입에서 검증할 것은 <b>전진하지 못하는 상태를 만들 수 없다</b>는 것 하나다.
 * 커서가 조용히 처음으로 되돌아가면 같은 조회가 반복되고, 그 증상은 무한 루프와
 * 스킵 집계 부풀림으로 나타난다 — 둘 다 예외 없이 진행되므로 조립 시점에 끊어야 한다.
 */
@DisplayName("PollCursor — 다음 청크의 시작점")
class PollCursorTest {

    @Test
    @DisplayName("첫 청크는 조건절 없이 처음부터 읽는다")
    void firstChunkReadsFromBeginning() {
        PollCursor cursor = PollCursor.first();

        assertAll(
                () -> assertTrue(cursor.fromBeginning()),
                // null 이어야 Sender 가 '조건절 없는 SQL' 을 고른다.
                // Oracle 에서 ORDER_ID > '' 는 빈 문자열이 NULL 이라 말없이 0건이 된다.
                () -> assertNull(cursor.afterOrderId()));
    }

    @Test
    @DisplayName("읽은 마지막 ORDER_ID 뒤부터 읽는다")
    void advancesPastGivenOrderId() {
        PollCursor cursor = PollCursor.after("A003");

        assertAll(
                () -> assertFalse(cursor.fromBeginning()),
                () -> assertEquals("A003", cursor.afterOrderId()));
    }

    @Test
    @DisplayName("전진시킬 값이 없으면 실패시킨다 — 조용히 처음으로 되돌아가는 것이 최악이다")
    void refusesToStandStill() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> PollCursor.after(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> PollCursor.after("")),
                () -> assertThrows(IllegalArgumentException.class, () -> PollCursor.after("   ")));
    }

    @Test
    @DisplayName("사전식 순서가 채번 순서와 같다 — 이 성질이 keyset 페이징의 근거다")
    void lexicographicOrderMatchesIssueOrder() {
        // [A-Z][0-9]{3} 고정 3자리 제로패딩이므로 문자열 비교가 곧 발생 순서다.
        // 이 성질이 깨지면 ORDER_ID > :cursor 가 아직 처리하지 않은 행을 건너뛴다.
        assertAll(
                () -> assertTrue("A000".compareTo("A999") < 0),
                () -> assertTrue("A999".compareTo("B000") < 0),
                () -> assertTrue("A009".compareTo("A010") < 0, "제로패딩이 없으면 A9 > A10 이 된다"));
    }
}
