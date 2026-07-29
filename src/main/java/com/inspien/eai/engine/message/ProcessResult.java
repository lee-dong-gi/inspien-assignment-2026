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
 * <h2>{@code txId} 를 결과가 들고 다니는 이유 (D-18)</h2>
 * 응답 포맷(정의서 3.10)에 {@code txId} 가 들어간다. 그런데 {@code txId} 는 실행 도중에만
 * MDC 에 있고 {@code complete()} 시점에 해제되므로, 결과가 스스로 들고 나오지 않으면
 * <b>호출자가 그 값을 얻을 방법이 없다.</b>
 *
 * <p>래퍼 타입을 새로 두는 대신 여기에 담은 것은, 이 값이 결과의 <b>부가 정보가 아니라
 * 신원</b>이기 때문이다. "63건 적재 성공" 은 어느 실행의 이야기인지 없이는 운영에서 쓸모가 없다.
 * 호출자가 없는 배치도 마찬가지다 — 로그와 결과를 잇는 고리가 이 값이다.
 *
 * @param txId       이 결과를 만든 실행의 추적 ID. {@code MessageHeader} 와 같은 값
 * @param processed  적재 성공 건수
 * @param skipped    정합성 불일치로 제외된 건수 (메시지는 정상, 대응 대상이 없음)
 * @param failed     처리 시도 중 실패한 건수
 * @param skipDetail 사유별 스킵 건수. 집계만 남기면 원인을 못 찾는다
 */
public record ProcessResult(
        String txId,
        Outcome outcome,
        int processed,
        int skipped,
        int failed,
        Map<String, Integer> skipDetail,
        EaiErrorCode errorCode,
        String errorMessage
) {

    public ProcessResult {
        if (txId == null || txId.isBlank()) {
            // 추적 불가능한 결과는 보고하지 않는다. MessageHeader 와 같은 기준이다.
            throw new IllegalArgumentException("txId 없는 결과는 로그와 대조할 수 없다.");
        }
        skipDetail = (skipDetail == null) ? Map.of() : Map.copyOf(skipDetail);
    }

    public static ProcessResult success(String txId, int processed) {
        return new ProcessResult(txId, Outcome.SUCCESS, processed, 0, 0, Map.of(), null, null);
    }

    public static ProcessResult partial(String txId, int processed, int skipped,
                                        Map<String, Integer> skipDetail) {
        return new ProcessResult(txId, Outcome.PARTIAL, processed, skipped, 0,
                new LinkedHashMap<>(skipDetail), null, null);
    }

    public static ProcessResult fail(String txId, EaiErrorCode code, String message) {
        return new ProcessResult(txId, Outcome.FAIL, 0, 0, 0, Map.of(), code, message);
    }

    /**
     * 처리 건수에 따라 SUCCESS / PARTIAL 을 자동 판정한다.
     *
     * <p>스킵이 하나라도 있으면 PARTIAL 이다. 호출자가 임의로 SUCCESS 라고 부를 여지를 남기지 않는다.
     */
    public static ProcessResult of(String txId, int processed, int skipped,
                                   Map<String, Integer> skipDetail) {
        return skipped > 0
                ? partial(txId, processed, skipped, skipDetail)
                : success(txId, processed);
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
