package com.inspien.eai.common.id;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SerialIdCodec — 과제 지정 형식 [A-Z][0-9]{3}")
class SerialIdCodecTest {

    @Nested
    @DisplayName("인코딩")
    class Encoding {

        @ParameterizedTest(name = "index {0} → {1}")
        @CsvSource({
                "0,     A000",
                "1,     A001",
                "113,   A113",   // 과제 PDF 예시값
                "999,   A999",   // 블록 끝
                "1000,  B000",   // 다음 문자로 넘어가는 경계
                "1114,  B114",   // 과제 PDF 예시값
                "25000, Z000",
                "25999, Z999"    // 공간의 마지막
        })
        @DisplayName("경계에서 문자가 정확히 한 칸 넘어간다")
        void encodesBoundaries(long index, String expected) {
            assertEquals(expected, SerialIdCodec.encode(index));
        }

        @Test
        @DisplayName("항상 4자리 — 숫자부는 제로패딩된다")
        void alwaysFourCharacters() {
            assertAll(
                    () -> assertEquals(4, SerialIdCodec.encode(0).length()),
                    () -> assertEquals(4, SerialIdCodec.encode(7).length()),
                    () -> assertEquals("A007", SerialIdCodec.encode(7)));
        }

        @Test
        @DisplayName("공간을 벗어나면 재시도 불가 예외 — 되풀이해도 결과가 같기 때문")
        void rejectsBeyondCapacity() {
            NonRetryableException e = assertThrows(NonRetryableException.class,
                    () -> SerialIdCodec.encode(SerialIdCodec.CAPACITY));

            assertEquals(EaiErrorCode.ID_SPACE_EXHAUSTED, e.errorCode());
            assertFalse(e.errorCode().retryable());
        }

        @Test
        @DisplayName("공간 크기는 26,000")
        void capacityIs26000() {
            assertEquals(26_000, SerialIdCodec.CAPACITY);
        }
    }

    @Nested
    @DisplayName("디코딩 — MAX(ORDER_ID) 로 카운터를 복원하기 위한 역방향")
    class Decoding {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"A000, 0", "A113, 113", "B000, 1000", "Z999, 25999"})
        void decodes(String id, int expected) {
            assertEquals(expected, SerialIdCodec.decode(id));
        }

        @Test
        @DisplayName("전 구간 왕복이 항등")
        void roundTrips() {
            for (int i = 0; i < SerialIdCodec.CAPACITY; i++) {
                assertEquals(i, SerialIdCodec.decode(SerialIdCodec.encode(i)));
            }
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"a113", "A11", "A1130", "AA13", "A-13", "1113", ""})
        @DisplayName("규격 밖 값은 조용히 넘기지 않는다 — 모르는 경로로 들어온 데이터로 이어 채번하면 충돌한다")
        void rejectsMalformed(String id) {
            assertThrows(NonRetryableException.class, () -> SerialIdCodec.decode(id));
        }

        @Test
        void rejectsNull() {
            assertAll(
                    () -> assertFalse(SerialIdCodec.matches(null)),
                    () -> assertThrows(NonRetryableException.class, () -> SerialIdCodec.decode(null)));
        }
    }

    @Test
    @DisplayName("사전식 정렬 순서 = 채번 순서 — MAX(ORDER_ID) 를 복원 기준으로 쓸 수 있는 근거")
    void lexicographicOrderMatchesNumericOrder() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < SerialIdCodec.CAPACITY; i += 137) {   // 전 구간을 성기게 훑는다
            ids.add(SerialIdCodec.encode(i));
        }

        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);

        assertEquals(ids, sorted, "제로패딩이 깨지면 A9 > A10 이 되어 이 성질이 무너진다");
        assertTrue("A999".compareTo("B000") < 0);
    }
}
