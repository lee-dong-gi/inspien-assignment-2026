package com.inspien.eai.integration.order;

import com.inspien.eai.common.id.InMemoryIdSequence;
import com.inspien.eai.common.id.SequenceKey;
import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.log.RecordingInterfaceLogger;
import com.inspien.eai.engine.log.Step;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.engine.receiver.Delivery;
import com.inspien.eai.engine.receiver.DeliveryCoordinator;
import com.inspien.eai.engine.receiver.OrderedDeliveryCoordinator;
import com.inspien.eai.engine.receiver.Receiver;
import com.inspien.eai.integration.order.mapper.OrderMapper;
import com.inspien.eai.integration.order.sender.OrderRestSender;
import com.inspien.eai.integration.order.sender.OrderXmlParser;
import com.inspien.eai.integration.order.target.OrderRecord;
import com.inspien.eai.integration.order.validator.OrderValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IF-ORD-001 파이프라인 조립 검증.
 *
 * <p>수신처만 대역으로 두고 <b>파서·검증기·매퍼·조율자는 실물</b>을 쓴다.
 * 각 부품은 이미 자기 테스트가 있으므로, 여기서 볼 것은 <b>이어 붙였을 때의 행동</b>이다 —
 * 결과가 SUCCESS 인가 PARTIAL 인가, 거부되면 수신처를 정말 안 부르는가,
 * 어떤 실패든 실행 이력에 시작과 끝이 남는가.
 *
 * <p>DB 도 FTP 도 Redis 도 띄우지 않는다. 그것들 없이 검증되지 않는 부분은
 * 이 클래스의 관심사가 아니다.
 */
@DisplayName("OrderIntegrationFlow — IF-ORD-001 파이프라인")
class OrderIntegrationFlowTest {

    /** 소스 XML 은 선언부 없는 EUC-KR 이다. 테스트도 같은 조건으로 들여보낸다 */
    private static final Charset SOURCE = Charset.forName("EUC-KR");

    private static final String ONE_ORDER = """
            <HEADER>
                <USER_ID>USER01</USER_ID>
                <NAME>김철수</NAME>
                <ADDRESS>서울특별시 강남구</ADDRESS>
                <STATUS>N</STATUS>
            </HEADER>
            <ITEM>
                <USER_ID>USER01</USER_ID>
                <ITEM_ID>ITEM01</ITEM_ID>
                <ITEM_NAME>운동화</ITEM_NAME>
                <PRICE>59000</PRICE>
            </ITEM>
            """;

    private static final String BAD_PRICE = """
            <HEADER>
                <USER_ID>USER01</USER_ID>
                <NAME>김철수</NAME>
                <ADDRESS>서울특별시 강남구</ADDRESS>
                <STATUS>N</STATUS>
            </HEADER>
            <ITEM>
                <USER_ID>USER01</USER_ID>
                <ITEM_ID>ITEM01</ITEM_ID>
                <ITEM_NAME>운동화</ITEM_NAME>
                <PRICE>오만구천원</PRICE>
            </ITEM>
            """;

    private final List<String> trace = new ArrayList<>();
    private final RecordingInterfaceLogger interfaceLogger = new RecordingInterfaceLogger();

    // ------------------------------------------------------------------ 정상 경로

    @Test
    @DisplayName("전건 정상이면 SUCCESS 로 보고하고 수신처를 순서대로 거친다")
    void reportsSuccessWhenNothingIsDropped() {
        ProcessResult result = flow().execute(euckr(ONE_ORDER));

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.SUCCESS, result.outcome()),
                () -> assertEquals(1, result.processed()),
                () -> assertEquals(0, result.skipped()),
                () -> assertEquals(List.of(
                        "RECEIVER_JDBC:PREPARE(1)",
                        "RECEIVER_FTP:PREPARE(1)",
                        "RECEIVER_JDBC:COMMIT",
                        "RECEIVER_FTP:COMMIT"), trace));
    }

    @Test
    @DisplayName("실행 이력에 파이프라인 전 구간이 순서대로 남는다")
    void writesEveryStepInOrder() {
        flow().execute(euckr(ONE_ORDER));

        assertEquals(List.of(
                "START",
                "SENDER SUCCESS HEADER=1 ITEM=1",
                "VALIDATOR SUCCESS",
                "MAPPER SUCCESS ROWS=1",
                "RECEIVER_JDBC SUCCESS PREPARE",
                "RECEIVER_FTP SUCCESS PREPARE",
                "RECEIVER_JDBC SUCCESS COMMIT",
                "RECEIVER_FTP SUCCESS COMMIT",
                "END SUCCESS"), interfaceLogger.lines());
    }

    // ------------------------------------------------------------------ 부분 처리

    @Test
    @DisplayName("짝 없는 건이 있으면 PARTIAL 이다 — 버린 건수와 사유를 반드시 동반한다")
    void reportsPartialWithSkipDetail() {
        // 픽스처: HEADER 3(USER01·02·03) / ITEM 5. 고아 ITEM 2건, 짝 없는 HEADER 1건.
        ProcessResult result = flow().execute(euckr(Fixtures.read(Fixtures.ORDER_SOURCE_MINI)));

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(3, result.processed(), "USER01 2건 + USER02 1건"),
                () -> assertEquals(3, result.skipped()),
                () -> assertEquals(2, result.skipDetail().get("ORPHAN_ITEM")),
                () -> assertEquals(1, result.skipDetail().get("HEADER_WITHOUT_ITEM")),
                // 조용히 63건만 넣고 성공이라 답하는 것이 실패보다 위험하다.
                () -> assertTrue(interfaceLogger.steps().stream()
                        .anyMatch(line -> line.startsWith("VALIDATOR PARTIAL"))));
    }

    // ------------------------------------------------------------------ 거부

    @Test
    @DisplayName("검증이 거부하면 수신처를 한 번도 부르지 않는다 — 되돌릴 것이 없는 자리에서 끝낸다")
    void neverTouchesReceiversWhenValidationRejects() {
        ProcessResult result = flow().execute(euckr(BAD_PRICE));

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(EaiErrorCode.VALIDATION_ERROR, result.errorCode()),
                () -> assertTrue(result.errorMessage().contains("V-05")),
                () -> assertTrue(trace.isEmpty(), "거부된 요청이 대상 시스템에 닿으면 안 된다"),
                () -> assertEquals("END FAIL", interfaceLogger.last()));
    }

    @Test
    @DisplayName("첫 구간에서 죽어도 실행 이력에 시작과 끝이 남는다 — txId 를 플로우가 발급하기 때문이다")
    void keepsTraceEvenWhenSenderFails() {
        ProcessResult result = flow().execute(new byte[0]);

        assertAll(
                () -> assertEquals(EaiErrorCode.SOURCE_PARSE_ERROR, result.errorCode()),
                () -> assertEquals("START", interfaceLogger.lines().get(0)),
                () -> assertEquals("END FAIL", interfaceLogger.last()),
                () -> assertTrue(interfaceLogger.steps().stream()
                        .anyMatch(line -> line.startsWith("SENDER FAIL EAI-1003"))));
    }

    // ------------------------------------------------------------------ 되돌릴 수 없는 실패

    @Test
    @DisplayName("확정 후 FTP rename 이 실패하면 FAIL 이 아니라 PARTIAL + 에러 코드다 — D-14")
    void reportsPartialWhenDeliveryNeedsManualAction() {
        Receiver<OrderRecord> jdbc = receiver(Step.RECEIVER_JDBC, null);
        Receiver<OrderRecord> ftp = receiver(Step.RECEIVER_FTP,
                new NonRetryableException(EaiErrorCode.FTP_RENAME_FAILED, "서버가 rename 을 거부했다"));

        ProcessResult result = flow(new OrderedDeliveryCoordinator<>(interfaceLogger), List.of(jdbc, ftp))
                .execute(euckr(ONE_ORDER));

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(EaiErrorCode.FTP_RENAME_FAILED, result.errorCode()),
                // FAIL 로 답하면 호출자가 재요청하고, 그 재요청이 곧 중복 적재다.
                () -> assertEquals(1, result.processed()),
                () -> assertFalse(trace.contains("RECEIVER_JDBC:COMPENSATE"),
                        "확정된 DB 트랜잭션을 되돌리면 멀쩡한 주문이 사라진다"));
    }

    // ------------------------------------------------------------------ 우리 코드의 버그

    @Test
    @DisplayName("구간에 귀속되지 않는 예외는 EAI-4005 로 분류하고, 예외를 밖으로 흘리지 않는다")
    void convertsUnattributedFailureIntoResult() {
        DeliveryCoordinator<OrderRecord> broken = (message, receivers) -> null;

        ProcessResult result = flow(broken, List.of()).execute(euckr(ONE_ORDER));

        assertAll(
                () -> assertEquals(ProcessResult.Outcome.FAIL, result.outcome()),
                () -> assertEquals(EaiErrorCode.FLOW_ERROR, result.errorCode()),
                () -> assertEquals("END FAIL", interfaceLogger.last()));
    }

    // ------------------------------------------------------------------ 추적성

    @Test
    @DisplayName("성공이든 실패든 결과가 txId 를 달고 나온다 — 응답과 로그를 잇는 유일한 고리다")
    void everyResultCarriesTxId() {
        ProcessResult ok = flow().execute(euckr(ONE_ORDER));
        ProcessResult failed = flow().execute(new byte[0]);

        assertAll(
                () -> assertFalse(ok.txId().isBlank()),
                // 첫 구간에서 죽은 실행도 마찬가지다. 그러려고 발급을 맨 앞으로 당겼다 (D-16).
                () -> assertFalse(failed.txId().isBlank()),
                () -> assertNotEquals(ok.txId(), failed.txId(), "실행마다 새로 발급된다"));
    }

    // ------------------------------------------------------------------ 조립

    private OrderIntegrationFlow flow() {
        return flow(new OrderedDeliveryCoordinator<>(interfaceLogger),
                List.of(receiver(Step.RECEIVER_JDBC, null), receiver(Step.RECEIVER_FTP, null)));
    }

    private OrderIntegrationFlow flow(DeliveryCoordinator<OrderRecord> coordinator,
                                      List<Receiver<OrderRecord>> receivers) {
        return new OrderIntegrationFlow(
                new OrderRestSender(new OrderXmlParser()),
                new OrderValidator(),
                new OrderMapper(new SequentialIdGenerator(new InMemoryIdSequence(), SequenceKey.ORDER), "TESTKEY"),
                coordinator,
                receivers,
                interfaceLogger);
    }

    private static byte[] euckr(String xml) {
        return xml.getBytes(SOURCE);
    }

    /** 호출된 사실만 적어 두는 수신처 대역. */
    private Receiver<OrderRecord> receiver(Step step, RuntimeException commitFailure) {
        return new Receiver<>() {

            @Override
            public Step step() {
                return step;
            }

            @Override
            public Delivery prepare(CanonicalMessage<List<OrderRecord>> message) {
                int count = message.payload().size();
                trace.add(step + ":PREPARE(" + count + ")");

                return new Delivery() {
                    @Override
                    public int count() {
                        return count;
                    }

                    @Override
                    public void commit() {
                        trace.add(step + ":COMMIT");
                        if (commitFailure != null) {
                            throw commitFailure;
                        }
                    }

                    @Override
                    public void compensate() {
                        trace.add(step + ":COMPENSATE");
                    }
                };
            }
        };
    }
}
