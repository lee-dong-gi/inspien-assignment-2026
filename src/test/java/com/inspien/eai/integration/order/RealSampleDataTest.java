package com.inspien.eai.integration.order;

import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.ValidationResult.SkipReason;
import com.inspien.eai.integration.order.sender.OrderXmlParser;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.validator.OrderValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 실물 샘플 데이터에 대한 검증 — <b>파일이 있을 때만</b> 실행된다.
 *
 * <p>샘플 XML 은 BOOT-000 산출물이라 {@code secrets/} 아래에 있고 커밋되지 않는다.
 * 이 저장소를 클론한 사람의 빌드를 깨뜨리지 않기 위해 {@link Assumptions} 로 건너뛴다.
 *
 * <p>회귀 방지의 본체는 {@code order-source-mini.xml} 픽스처 테스트다.
 * 이 테스트는 <b>정의서에 기록한 실측 수치가 코드로 재현되는지</b> 확인하는 용도이며,
 * 개발 중과 시연 직전에 의미가 있다.
 *
 * <p>정의서 3.2 기준 기대값:
 * <pre>
 *   HEADER 15 / ITEM 70
 *     ├─ 정상 매칭 : HEADER 11 → ITEM 63
 *     ├─ 고아 ITEM : 7
 *     └─ 빈 HEADER : 4
 *   → 적재 63행, 스킵 11건, 결과 PARTIAL
 * </pre>
 */
@DisplayName("실물 샘플 데이터 — 정의서 실측치 재현 (secrets/ 있을 때만)")
class RealSampleDataTest {

    private static final Path SAMPLE = Path.of("secrets", "sample-data.euckr.xml");

    private final OrderXmlParser parser = new OrderXmlParser();
    private final OrderValidator validator = new OrderValidator();

    @Test
    @DisplayName("파싱 결과가 HEADER 15 / ITEM 70 이다")
    void parsesRealSample() throws IOException {
        OrderSourceMessage source = loadSample();

        assertAll(
                () -> assertEquals(15, source.headerCount()),
                () -> assertEquals(70, source.itemCount()));
    }

    @Test
    @DisplayName("검증 결과가 적재 63행 / 스킵 11건(고아 7 + 빈 헤더 4) 이다")
    void reproducesDocumentedCounts() throws IOException {
        OrderSourceMessage source = loadSample();
        CanonicalMessage<OrderSourceMessage> message =
                new CanonicalMessage<>(MessageHeader.issue(InterfaceId.IF_ORD_001), source);

        ValidationResult<OrderSourceMessage> result = validator.validate(message);

        assertAll(
                () -> assertFalse(result.rejected(), "샘플에 구조 오류는 없어야 한다"),
                () -> assertEquals(11, result.accepted().headerCount()),
                () -> assertEquals(63, result.accepted().itemCount(), "적재될 행 수 = 영수증 라인 수"),
                () -> assertEquals(11, result.skipped().size()),
                () -> assertEquals(7, result.skipDetail().get(SkipReason.ORPHAN_ITEM.name())),
                () -> assertEquals(4, result.skipDetail().get(SkipReason.HEADER_WITHOUT_ITEM.name())));
    }

    private OrderSourceMessage loadSample() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE),
                "샘플이 없어 건너뜀 — 먼저 'gradlew bootstrapRun' 실행: " + SAMPLE.toAbsolutePath());
        return parser.parse(Files.readAllBytes(SAMPLE));
    }
}
