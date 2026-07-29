package com.inspien.eai.engine.log;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 이력 로거 대역 — 남긴 줄을 그대로 모아 둔다.
 *
 * <p>파일로 쓰지 않고 리스트에 담는 이유는, 검증하고 싶은 것이 <b>파일 입출력이 아니라
 * 무엇이 어떤 순서로 기록되는가</b>이기 때문이다. 포맷 자체는
 * {@code InterfaceLogFormatterTest} 가 따로 본다.
 *
 * <p>한 줄의 모양: {@code "구간 결과 부가정보"}. {@code begin}/{@code complete} 은
 * {@code START} / {@code END 결과} 로 남는다.
 */
public final class RecordingInterfaceLogger implements InterfaceLogger {

    private final List<String> lines = new ArrayList<>();

    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** {@code START} · {@code END} 를 뺀 구간 줄만. */
    public List<String> steps() {
        return lines.stream()
                .filter(line -> !line.equals("START") && !line.startsWith("END "))
                .toList();
    }

    public String last() {
        return lines.get(lines.size() - 1);
    }

    @Override
    public void begin(MessageHeader header, String detail) {
        lines.add("START");
    }

    @Override
    public StepScope step(MessageHeader header, Step step) {
        return new RecordingScope(step);
    }

    @Override
    public void complete(MessageHeader header, ProcessResult result) {
        lines.add("END " + result.outcome());
    }

    private final class RecordingScope implements StepScope {

        private final Step step;
        private String detail = "";
        private boolean recorded;

        private RecordingScope(Step step) {
            this.step = step;
        }

        @Override
        public StepScope detail(String format, Object... args) {
            this.detail = (args == null || args.length == 0) ? format : format.formatted(args);
            return this;
        }

        @Override
        public void success(int ok) {
            write("SUCCESS");
        }

        @Override
        public void partial(int ok, int skip) {
            write("PARTIAL");
        }

        @Override
        public void fail(EaiErrorCode code, String message) {
            fail(code, message, 0);
        }

        @Override
        public void fail(EaiErrorCode code, String message, int failed) {
            write("FAIL " + code.code() + " " + message);
        }

        /** 결과 표시 없이 닫히면 {@code ABORTED} — 조용히 사라지는 구간을 만들지 않는다. */
        @Override
        public void close() {
            if (!recorded) {
                write("ABORTED");
            }
        }

        private void write(String result) {
            recorded = true;
            lines.add((step + " " + result + " " + detail).trim());
        }
    }
}
