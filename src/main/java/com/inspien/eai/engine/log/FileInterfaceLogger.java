package com.inspien.eai.engine.log;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 파일 기반 인터페이스 실행 이력 로거 (요구사항: local 파일 저장).
 *
 * <p>전용 로거 이름 {@link InterfaceLogger#LOGGER_NAME} 으로 기록하고,
 * {@code logback-spring.xml} 에서 {@code additivity=false} 로 콘솔·앱 로그와 완전히 분리한다.
 * 클래스명 기반 로거를 쓰지 않은 것은 <b>이것이 디버그 로그가 아니라 별도 채널</b>이기 때문이다.
 */
public class FileInterfaceLogger implements InterfaceLogger {

    private static final Logger LOG = LoggerFactory.getLogger(InterfaceLogger.LOGGER_NAME);

    private static final String STEP_START = "START";
    private static final String STEP_END = "END";
    private static final String RESULT_ABORTED = "ABORTED";

    @Override
    public void begin(MessageHeader header, String detail) {
        // 실행 전체를 감싸는 지점이므로 MDC 수명의 소유자도 여기다.
        TxContext.bind(header);
        LOG.info(InterfaceLogFormatter.format(
                header.ifId().code(), header.txId(), STEP_START, "-",
                null, null, null, null,
                "txId=" + header.txId() + (detail == null || detail.isBlank() ? "" : " " + detail)));
    }

    @Override
    public StepScope step(MessageHeader header, Step step) {
        return new DefaultStepScope(header, step);
    }

    @Override
    public void complete(MessageHeader header, ProcessResult result) {
        try {
            LOG.info(InterfaceLogFormatter.format(
                    header.ifId().code(),
                    header.txId(),
                    STEP_END,
                    result.outcome().name(),
                    String.valueOf(result.processed()),
                    String.valueOf(result.skipped()),
                    String.valueOf(result.failed()),
                    String.valueOf(elapsedSince(header.occurredAt())),
                    completionDetail(result)));
        } finally {
            // 기록에 실패하더라도 MDC 는 반드시 푼다.
            // 남겨두면 풀에서 재사용되는 다음 요청의 로그에 이전 txId 가 붙는다.
            TxContext.clear();
        }
    }

    private String completionDetail(ProcessResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.errorCode() != null) {
            sb.append(result.errorCode().code());
            if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
                sb.append(' ').append(result.errorMessage());
            }
        }
        if (!result.skipDetail().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            result.skipDetail().forEach((reason, count) ->
                    sb.append(reason).append('=').append(count).append(' '));
        }
        return sb.toString().trim();
    }

    /**
     * 전체 소요 시간은 헤더의 발급 시각에서 잰다.
     *
     * <p>시작 시각을 맵에 담아두면 {@code complete()} 가 호출되지 않은 실행의 항목이
     * 영원히 남아 누수가 된다. 헤더가 이미 시각을 들고 있으므로 상태를 둘 이유가 없다.
     */
    private long elapsedSince(LocalDateTime start) {
        return Math.max(0, Duration.between(start, LocalDateTime.now()).toMillis());
    }

    /**
     * 기본 구간 범위 구현.
     *
     * <p>결과가 표시되지 않은 채 닫히면 {@code ABORTED} 로 남긴다.
     * 예외로 빠져나간 구간이 흔적 없이 사라지면, 운영자는 그 구간이 아예 실행되지 않은 것으로 읽는다.
     */
    private static final class DefaultStepScope implements StepScope {

        private final MessageHeader header;
        private final Step step;
        private final long startedAtNanos = System.nanoTime();

        private String detail;
        private boolean recorded;

        private DefaultStepScope(MessageHeader header, Step step) {
            this.header = header;
            this.step = step;
        }

        @Override
        public StepScope detail(String format, Object... args) {
            this.detail = (args == null || args.length == 0) ? format : format.formatted(args);
            return this;
        }

        @Override
        public void success(int ok) {
            write("SUCCESS", String.valueOf(ok), "0", "0", detail);
        }

        @Override
        public void partial(int ok, int skip) {
            write("PARTIAL", String.valueOf(ok), String.valueOf(skip), "0", detail);
        }

        @Override
        public void fail(EaiErrorCode code, String message) {
            fail(code, message, 0);
        }

        @Override
        public void fail(EaiErrorCode code, String message, int failed) {
            String text = code.code() + (message == null || message.isBlank() ? "" : " " + message);
            write("FAIL", "0", "0", String.valueOf(failed),
                  detail == null ? text : text + " " + detail);
        }

        @Override
        public void close() {
            if (!recorded) {
                write(RESULT_ABORTED, null, null, null,
                      detail == null ? "결과 미표시 상태로 종료됨" : detail);
            }
        }

        private void write(String result, String ok, String skip, String fail, String detailText) {
            recorded = true;
            LOG.info(InterfaceLogFormatter.format(
                    header.ifId().code(),
                    header.txId(),
                    step.name(),
                    result,
                    ok, skip, fail,
                    String.valueOf(elapsedMillis()),
                    detailText));
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startedAtNanos) / 1_000_000;
        }
    }
}
