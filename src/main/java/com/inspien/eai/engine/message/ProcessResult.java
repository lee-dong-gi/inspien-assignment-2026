package com.inspien.eai.engine.message;

import com.inspien.eai.engine.exception.EaiErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인터페이스 실행 결과.
 *
 * <p>성공/실패의 2분법을 쓰지 않는다. 연계에서 가장 위험한 상태는 실패가 아니라
 * <b>일부만 처리됐는데 성공으로 보고된 상태</b>다. 63건 적재하고 11건을 조용히 버린 뒤
 * "성공" 이라고 답하는 것이 실제 운영 사고로 이어진다.
 *
 * <p>따라서 {@link Outcome#PARTIAL} 을 1급 상태로 두고, 버린 건수와 사유를 반드시 동반한다.
 *
 * @param processed  적재 성공 건수
 * @param skipped    정합성 불일치로 제외된 건수 (메시지는 정상, 대응 대상이 없음)
 * @param failed     처리 시도 중 실패한 건수
 * @param skipDetail 사유별 스킵 건수. 집계만 남기면 원인을 못 찾는다
 */
public record ProcessResult(
        Outcome outcome,
        int processed,
        int skipped,
        int failed,
        Map<String, Integer> skipDetail,
        EaiErrorCode errorCode,
        String errorMessage
) {

    public ProcessResult {
        skipDetail = (skipDetail == null) ? Map.of() : Map.copyOf(skipDetail);
    }

    public static ProcessResult success(int processed) {
        return new ProcessResult(Outcome.SUCCESS, processed, 0, 0, Map.of(), null, null);
    }

    public static ProcessResult partial(int processed, int skipped, Map<String, Integer> skipDetail) {
        return new ProcessResult(Outcome.PARTIAL, processed, skipped, 0,
                new LinkedHashMap<>(skipDetail), null, null);
    }

    public static ProcessResult fail(EaiErrorCode code, String message) {
        return new ProcessResult(Outcome.FAIL, 0, 0, 0, Map.of(), code, message);
    }

    /**
     * 처리 건수에 따라 SUCCESS / PARTIAL 을 자동 판정한다.
     *
     * <p>스킵이 하나라도 있으면 PARTIAL 이다. 호출자가 임의로 SUCCESS 라고 부를 여지를 남기지 않는다.
     */
    public static ProcessResult of(int processed, int skipped, Map<String, Integer> skipDetail) {
        return skipped > 0 ? partial(processed, skipped, skipDetail) : success(processed);
    }

    public enum Outcome {
        /** 전건 처리 완료 */
        SUCCESS,
        /** 일부 처리. 반드시 스킵 건수·사유를 동반한다 */
        PARTIAL,
        /** 처리 불가. 아무것도 적재되지 않았음이 보장되어야 한다 */
        FAIL
    }
}
