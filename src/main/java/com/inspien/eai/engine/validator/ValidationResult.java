package com.inspien.eai.engine.validator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 검증 결과.
 *
 * <p><b>두 종류의 이상(異常)을 구조적으로 분리한다.</b> 이 구분이 이 프로젝트의 설계 결정 D-02 다.
 *
 * <table border="1">
 *   <caption>이상 분류</caption>
 *   <tr><th>범주</th><th>의미</th><th>처리</th></tr>
 *   <tr>
 *     <td>{@code fatal}</td>
 *     <td>메시지 자체가 깨짐 (필수 필드 누락, 길이 초과, 형식 오류)</td>
 *     <td>요청 전체 거부. Receiver 를 호출하지 않는다</td>
 *   </tr>
 *   <tr>
 *     <td>{@code skipped}</td>
 *     <td>메시지는 정상이나 대응 대상이 없음 (고아 ITEM, ITEM 없는 HEADER)</td>
 *     <td>해당 건만 제외하고 진행. 결과는 PARTIAL</td>
 *   </tr>
 * </table>
 *
 * <p>둘을 뭉뚱그리면 둘 중 하나가 반드시 잘못된다. 전부 거부하면 이상 데이터 11건 때문에
 * 정상 63건까지 막히고, 전부 통과시키면 깨진 메시지가 그대로 적재된다.
 *
 * @param accepted 검증을 통과해 다음 단계로 넘길 페이로드
 * @param fatal    치명적 위반. 비어 있지 않으면 전체 거부
 * @param skipped  건 단위로 제외된 항목. 반드시 결과에 드러낸다
 */
public record ValidationResult<P>(
        P accepted,
        List<Violation> fatal,
        List<Skip> skipped
) {

    public ValidationResult {
        fatal = (fatal == null) ? List.of() : List.copyOf(fatal);
        skipped = (skipped == null) ? List.of() : List.copyOf(skipped);
    }

    public static <P> ValidationResult<P> ok(P accepted) {
        return new ValidationResult<>(accepted, List.of(), List.of());
    }

    public static <P> ValidationResult<P> reject(List<Violation> fatal) {
        return new ValidationResult<>(null, fatal, List.of());
    }

    public boolean rejected() {
        return !fatal.isEmpty();
    }

    /** 사유별 스킵 건수. 총계만 남기면 "무엇이 왜 빠졌는가" 를 되짚을 수 없다. */
    public Map<String, Integer> skipDetail() {
        return skipped.stream().collect(Collectors.groupingBy(
                s -> s.reason().name(), Collectors.summingInt(s -> 1)));
    }

    /**
     * 치명적 위반 1건.
     *
     * @param rule   규칙 식별자 (V-01 등). 정의서와 코드를 연결하는 고리
     * @param field  대상 필드
     * @param detail 사람이 읽을 설명. 값 자체는 담지 않는다 — 개인정보가 로그로 새는 경로가 된다
     */
    public record Violation(String rule, String field, String detail) {
    }

    /**
     * 건 단위 제외 1건.
     *
     * @param key 추적용 식별자 (USER_ID / ITEM_ID). 개인정보가 아닌 키만 남긴다
     */
    public record Skip(SkipReason reason, String key) {
    }

    public enum SkipReason {
        /** 대응 HEADER 가 없는 ITEM */
        ORPHAN_ITEM,
        /** 대응 ITEM 이 없는 HEADER */
        HEADER_WITHOUT_ITEM
    }
}
