package com.inspien.eai.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.message.ProcessResult;

import java.util.Map;

/**
 * 인터페이스 실행 결과의 HTTP 응답 표현 (정의서 3.10).
 *
 * <h2>{@link ProcessResult} 를 그대로 직렬화하지 않는 이유</h2>
 * 두 가지다.
 *
 * <p>첫째, 정의서 3.10 이 요구하는 {@code ifId} 가 {@code ProcessResult} 에는 없다.
 * 있어서도 안 된다 — 결과는 "무엇이 얼마나 처리됐는가" 를 말하는 값이고,
 * "어느 인터페이스였는가" 는 그 결과를 만든 <b>실행 주체</b>가 아는 정보다.
 * 경계에서 합치는 것이 맞다.
 *
 * <p>둘째, 내부 타입을 그대로 내보내면 <b>필드명과 enum 이름이 곧 API 계약</b>이 된다.
 * {@code processed} 를 {@code processedCount} 로 옮겨 적는 이 한 줄이,
 * 나중에 내부 이름을 바꿀 때 응답이 조용히 깨지는 것을 막는 유일한 지점이다.
 *
 * <h2>{@code errorCode} 는 실패 전용이 아니다</h2>
 * {@code result=PARTIAL} 인데 {@code errorCode} 가 실린 응답이 나올 수 있다.
 * 보상 트랜잭션이 <b>되돌릴 수 없는 자리에서 실패</b>한 경우다 (D-14) —
 * 적재는 유효하지만 사람의 조치가 필요하다는 뜻이므로, 둘 다 실어야 정보가 온전하다.
 * 이 조합을 표현하지 못하면 호출자는 "성공했다" 와 "손대야 한다" 중 하나를 잃는다.
 *
 * @param result         {@code SUCCESS} / {@code PARTIAL} / {@code FAIL}
 * @param ifId           {@code IF-ORD-001} 형식의 인터페이스 코드
 * @param txId           추적용 트랜잭션 ID. 실행 이력 파일과 대조하는 열쇠다
 * @param processedCount 적재 성공 건수
 * @param skippedCount   정합성 불일치로 제외된 건수
 * @param skipDetail     사유별 스킵 건수. 집계만 주면 원인을 못 찾는다
 * @param errorCode      {@code EAI-xxxx}. 실패 또는 수동 조치 필요 시
 * @param errorMessage   운영자용 사유
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterfaceResponse(
        String result,
        String ifId,
        String txId,
        int processedCount,
        int skippedCount,
        Map<String, Integer> skipDetail,
        String errorCode,
        String errorMessage
) {

    /**
     * 실행 결과를 응답으로 옮긴다.
     *
     * <p>{@code result} 와 {@code errorCode} 를 <b>문자열로 고정</b>하는 것이 이 메서드의 요점이다.
     * enum 을 그대로 직렬화하면 상수 이름을 바꾸는 순간 API 가 말없이 바뀐다.
     * 여기서 {@code name()} · {@code code()} 를 호출해 두면, 그 변환이 <b>보이는 코드</b>가 되고
     * 계약을 바꾸려면 이 자리를 지나가야 한다.
     *
     * <p>{@code skipDetail} 은 비어 있어도 {@code {}} 로 내보낸다. 필드를 통째로 지우면
     * 호출자가 "스킵이 없었다" 와 "이 서버는 스킵을 보고하지 않는다" 를 구분할 수 없다.
     */
    public static InterfaceResponse from(InterfaceId ifId, ProcessResult result) {
        return new InterfaceResponse(
                result.outcome().name(),
                ifId.code(),
                result.txId(),
                result.processed(),
                result.skipped(),
                result.skipDetail(),
                result.errorCode() == null ? null : result.errorCode().code(),
                result.errorMessage());
    }
}
