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

    /**
     * 건 단위 제외 사유.
     *
     * <h2>알고 있는 한계 (D-25)</h2>
     * 이 열거형은 <b>엔진 패키지에 있으면서 도메인 어휘를 담고 있다.</b>
     * {@code ORPHAN_ITEM} 은 주문 XML 의 사정이고 {@code MISSING_SHIPPING_ADDRESS} 는
     * 배송의 사정이니, 인터페이스를 하나 추가할 때마다 엔진이 커진다.
     * 원칙대로라면 사유를 문자열 코드로 열고 인터페이스별 열거형으로 내려야 한다
     * ({@code Step} 을 프로토콜 단위로 유지한 것과 같은 이야기다).
     *
     * <p>그럼에도 열거형으로 남겨 둔 이유는 <b>이 이름들이 사실상 외부 계약</b>이기 때문이다.
     * {@code skipDetail()} 을 거쳐 응답 JSON 과 실행 이력 파일에 그대로 실려 나간다.
     * 열거형이면 상수를 바꿀 때 컴파일이 깨지지만, 문자열로 열어 두면 오타 하나가
     * <b>조용히 새 사유 분류를 만들어</b> 집계를 둘로 쪼갠다. 그 보호를 잃는 대가가
     * 지금 규모에서는 더 크다고 판단해, 한계를 기록하고 유지한다.
     */
    public enum SkipReason {

        // ── IF-ORD-001 (주문 생성 연계) ─────────────────────────────────

        /** 대응 HEADER 가 없는 ITEM */
        ORPHAN_ITEM,
        /** 대응 ITEM 이 없는 HEADER */
        HEADER_WITHOUT_ITEM,

        // ── IF-SHP-001 (운송사 전송 배치) ───────────────────────────────

        /**
         * {@code ORDER_ID} 또는 {@code ITEM_ID} 가 비어 있는 행.
         *
         * <p>{@code SHIPMENT_TB} 에 넣을 수는 있다. 그러나 원본 주문을 가리키지 못하는
         * 배송 지시는 운송사 입장에서 어떤 조치도 할 수 없는 데이터다.
         */
        MISSING_ORDER_KEY,

        /**
         * {@code ADDRESS} 가 비어 있는 행.
         *
         * <p>배송지 없는 배송 지시는 정보가 아니라 잡음이다. 그런데 이를 밀어 넣고
         * {@code STATUS='Y'} 로 닫아 버리면 <b>다시 다룰 기회가 사라진다</b> —
         * 이 환경은 append-only 여서 잘못 적재한 것을 되돌릴 수 없다.
         */
        MISSING_SHIPPING_ADDRESS
    }
}
