package com.inspien.eai.engine.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FileInterfaceLogger — 실행 이력 기록")
class FileInterfaceLoggerTest {

    private ch.qos.logback.classic.Logger channel;
    private ListAppender<ILoggingEvent> captured;
    private FileInterfaceLogger logger;
    private MessageHeader header;

    @BeforeEach
    void setUp() {
        channel = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InterfaceLogger.LOGGER_NAME);
        channel.setAdditive(false);
        channel.setLevel(Level.INFO);

        captured = new ListAppender<>();
        captured.start();
        channel.addAppender(captured);

        logger = new FileInterfaceLogger();
        header = MessageHeader.issue(InterfaceId.IF_ORD_001);
    }

    @AfterEach
    void tearDown() {
        channel.detachAppender(captured);
        MDC.clear();
    }

    private List<String> lines() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String[] columnsOf(String line) {
        return line.split(" \\| ", -1);
    }

    @Test
    @DisplayName("START 줄에 전체 txId 를 남긴다 — 8자로 잘린 값을 복원할 수 있어야 한다")
    void startLineCarriesFullTxId() {
        logger.begin(header, "source=REST");

        String line = lines().get(0);

        assertAll(
                () -> assertTrue(columnsOf(line)[2].startsWith("START")),
                () -> assertTrue(line.contains("txId=" + header.txId()), "전체 UUID"),
                () -> assertTrue(line.contains("source=REST")));
    }

    @Test
    @DisplayName("구간 결과와 소요 시간을 기록한다")
    void recordsStepOutcome() {
        try (InterfaceLogger.StepScope scope = logger.step(header, Step.VALIDATOR)) {
            scope.detail("ORPHAN_ITEM=%d HEADER_WITHOUT_ITEM=%d", 7, 4).partial(63, 11);
        }

        String[] columns = columnsOf(lines().get(0));

        assertAll(
                () -> assertEquals("VALIDATOR    ", columns[2]),
                () -> assertEquals("PARTIAL", columns[3]),
                () -> assertEquals("   63", columns[4]),
                () -> assertEquals("  11", columns[5]),
                () -> assertEquals("ORPHAN_ITEM=7 HEADER_WITHOUT_ITEM=4", columns[8]));
    }

    @Test
    @DisplayName("실패 구간은 에러 코드와 실패 건수를 남긴다")
    void recordsFailureWithErrorCode() {
        try (InterfaceLogger.StepScope scope = logger.step(header, Step.RECEIVER_FTP)) {
            scope.fail(EaiErrorCode.FTP_CONN_ERROR, "connect timed out", 63);
        }

        String[] columns = columnsOf(lines().get(0));

        assertAll(
                () -> assertEquals("FAIL   ", columns[3]),
                () -> assertEquals("  63", columns[6]),
                () -> assertTrue(columns[8].contains("EAI-3001")),
                () -> assertTrue(columns[8].contains("connect timed out")));
    }

    /**
     * 이 테스트가 이 클래스의 핵심이다.
     *
     * <p>예외로 빠져나간 구간이 흔적 없이 사라지면, 운영자는 그 구간이 실행조차 되지 않은 것으로
     * 읽는다. "실패한 구간" 과 "기록이 없는 구간" 은 원인 추적에서 완전히 다른 이야기다.
     */
    @Test
    @DisplayName("예외로 빠져나가도 구간이 사라지지 않는다 — ABORTED 로 남는다")
    void marksAbortedWhenScopeClosesWithoutOutcome() {
        assertThrows(IllegalStateException.class, () -> {
            try (InterfaceLogger.StepScope scope = logger.step(header, Step.RECEIVER_JDBC)) {
                scope.detail("prepared");
                throw new IllegalStateException("적재 도중 중단");
            }
        });

        String[] columns = columnsOf(lines().get(0));

        assertAll(
                () -> assertEquals(1, lines().size(), "기록이 없으면 안 된다"),
                () -> assertEquals("ABORTED", columns[3]),
                () -> assertTrue(columns[2].startsWith("RECEIVER_JDBC")));
    }

    @Test
    @DisplayName("결과를 표시한 구간은 ABORTED 로 중복 기록되지 않는다")
    void doesNotDoubleRecordAfterOutcome() {
        try (InterfaceLogger.StepScope scope = logger.step(header, Step.MAPPER)) {
            scope.success(63);
        }

        assertEquals(1, lines().size());
    }

    @Test
    @DisplayName("END 줄에 최종 집계와 스킵 사유를 남긴다")
    void completionLineCarriesTotals() {
        logger.complete(header, ProcessResult.partial(header.txId(), 63, 11,
                Map.of("ORPHAN_ITEM", 7, "HEADER_WITHOUT_ITEM", 4)));

        String[] columns = columnsOf(lines().get(0));

        assertAll(
                () -> assertTrue(columns[2].startsWith("END")),
                () -> assertEquals("PARTIAL", columns[3]),
                () -> assertEquals("   63", columns[4]),
                () -> assertEquals("  11", columns[5]),
                () -> assertTrue(columns[8].contains("ORPHAN_ITEM=7")));
    }

    // ── MDC 수명 ─────────────────────────────────────────────────

    @Test
    @DisplayName("begin 이 txId 를 MDC 에 심는다 — 앱 로그 전체가 추적 가능해진다")
    void bindsTxIdToMdc() {
        logger.begin(header, null);

        assertAll(
                () -> assertEquals(header.txId(), MDC.get(TxContext.TX_ID)),
                () -> assertEquals("IF-ORD-001", MDC.get(TxContext.IF_ID)));
    }

    /**
     * MDC 는 ThreadLocal 이고 스레드는 풀에서 재사용된다.
     * 해제하지 않으면 다음 요청 로그에 이전 txId 가 붙어, 추적 장치가 추적을 오염시킨다.
     */
    @Test
    @DisplayName("complete 이 MDC 를 해제한다 — 스레드 재사용 시 오염 방지")
    void clearsMdcOnCompletion() {
        logger.begin(header, null);
        assertNotNull(MDC.get(TxContext.TX_ID));

        logger.complete(header, ProcessResult.success(header.txId(), 63));

        assertAll(
                () -> assertNull(MDC.get(TxContext.TX_ID)),
                () -> assertNull(MDC.get(TxContext.IF_ID)));
    }

    // ── 개인정보 ─────────────────────────────────────────────────

    /**
     * 이 로그에는 개인정보가 실릴 자리가 <b>구조적으로</b> 없다.
     * 마스킹은 한 곳만 빠뜨려도 새지만, 스키마에 자리가 없으면 실수로도 넣을 수 없다.
     */
    @Test
    @DisplayName("전 구간 기록에 개인정보가 실리지 않는다")
    void neverCarriesPersonalData() {
        logger.begin(header, "source=REST");
        try (InterfaceLogger.StepScope scope = logger.step(header, Step.MAPPER)) {
            scope.success(63);
        }
        logger.complete(header, ProcessResult.success(header.txId(), 63));

        String all = String.join("\n", lines());

        assertAll(
                () -> assertFalse(all.contains("김철수")),
                () -> assertFalse(all.contains("서울특별시")),
                () -> assertEquals(3, lines().size(), "START + STEP + END"));
    }
}
