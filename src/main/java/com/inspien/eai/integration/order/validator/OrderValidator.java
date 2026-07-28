package com.inspien.eai.integration.order.validator;

import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.validator.ValidationResult;
import com.inspien.eai.engine.validator.ValidationResult.Skip;
import com.inspien.eai.engine.validator.ValidationResult.SkipReason;
import com.inspien.eai.engine.validator.ValidationResult.Violation;
import com.inspien.eai.engine.validator.Validator;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IF-ORD-001 유효성 검증.
 *
 * <p>두 범주를 <b>구조적으로</b> 분리한다 (설계 결정 D-02).
 *
 * <table border="1">
 *   <caption>검증 규칙</caption>
 *   <tr><th>규칙</th><th>내용</th><th>결과</th></tr>
 *   <tr><td>V-01</td><td>HEADER 필수 필드</td><td>전체 거부</td></tr>
 *   <tr><td>V-02</td><td>ITEM 필수 필드</td><td>전체 거부</td></tr>
 *   <tr><td>V-05</td><td>PRICE 숫자 형식</td><td>전체 거부</td></tr>
 *   <tr><td>V-06</td><td>길이 초과 (UTF-8 바이트)</td><td>전체 거부</td></tr>
 *   <tr><td>V-03</td><td>고아 ITEM (대응 HEADER 없음)</td><td>건 단위 스킵</td></tr>
 *   <tr><td>V-04</td><td>빈 HEADER (대응 ITEM 없음)</td><td>건 단위 스킵</td></tr>
 * </table>
 *
 * <p><b>왜 V-03·V-04 는 거부가 아닌가.</b> 메시지 자체는 멀쩡하다. 단지 짝이 없을 뿐이다.
 * 과제 샘플에는 이런 건이 11개 심어져 있어서, 전체 거부를 택하면 정상 63건도 한 건을 못 넣는다.
 * 송신 시스템의 데이터 불완전성 때문에 정상 데이터까지 멈춰 세우는 것은 EAI 의 태도가 아니다.
 * 다만 <b>버린 건수는 반드시 결과에 실어 보낸다.</b> 조용히 63건만 넣고 성공이라 답하는 것이
 * 실패보다 위험하다.
 */
public class OrderValidator implements Validator<OrderSourceMessage> {

    /**
     * 길이 상한 — <b>문자 수가 아니라 UTF-8 바이트 수</b>.
     *
     * <p>대상 컬럼이 전부 {@code VARCHAR2(100 BYTE)} 이고 이 DB 는
     * {@code NLS_LENGTH_SEMANTICS=BYTE}, {@code NLS_CHARACTERSET=AL32UTF8} 이다 (BOOT-001 실측).
     * 즉 한글 1자가 3바이트를 먹으므로, 문자 수로 재면 34자짜리 주소가 통과한 뒤
     * <b>적재 시점에</b> {@code ORA-12899} 로 터진다. 검증의 의미가 없어진다.
     */
    static final int MAX_BYTES = 100;

    /**
     * PRICE 형식.
     *
     * <p>숫자인지 <b>검사만</b> 하고 변환하지는 않는다. 값은 문자열로 원본 그대로 적재한다.
     */
    private static final Pattern PRICE_PATTERN = Pattern.compile("^[0-9]+$");

    @Override
    public ValidationResult<OrderSourceMessage> validate(CanonicalMessage<OrderSourceMessage> message) {
        OrderSourceMessage source = message.payload();

        List<Violation> fatal = new ArrayList<>();
        source.headers().forEach(h -> checkHeader(h, fatal));
        source.items().forEach(i -> checkItem(i, fatal));

        // 구조 오류가 하나라도 있으면 여기서 끝낸다.
        // Receiver 를 호출하기 전에 끊어야 되돌릴 것이 없다.
        if (!fatal.isEmpty()) {
            return ValidationResult.reject(fatal);
        }

        return matchCardinality(source);
    }

    /** V-01 / V-06 */
    private void checkHeader(SourceHeader header, List<Violation> fatal) {
        String at = "HEADER[" + header.sequence() + "]";
        requirePresent(fatal, "V-01", at + ".USER_ID", header.userId());
        requirePresent(fatal, "V-01", at + ".NAME", header.name());
        requirePresent(fatal, "V-01", at + ".ADDRESS", header.address());
        requirePresent(fatal, "V-01", at + ".STATUS", header.status());

        requireWithinBytes(fatal, at + ".USER_ID", header.userId());
        requireWithinBytes(fatal, at + ".NAME", header.name());
        requireWithinBytes(fatal, at + ".ADDRESS", header.address());
        requireWithinBytes(fatal, at + ".STATUS", header.status());
    }

    /** V-02 / V-05 / V-06 */
    private void checkItem(SourceItem item, List<Violation> fatal) {
        String at = "ITEM[" + item.sequence() + "]";
        requirePresent(fatal, "V-02", at + ".USER_ID", item.userId());
        requirePresent(fatal, "V-02", at + ".ITEM_ID", item.itemId());
        requirePresent(fatal, "V-02", at + ".ITEM_NAME", item.itemName());
        requirePresent(fatal, "V-02", at + ".PRICE", item.price());

        requireWithinBytes(fatal, at + ".USER_ID", item.userId());
        requireWithinBytes(fatal, at + ".ITEM_ID", item.itemId());
        requireWithinBytes(fatal, at + ".ITEM_NAME", item.itemName());
        requireWithinBytes(fatal, at + ".PRICE", item.price());

        if (isPresent(item.price()) && !PRICE_PATTERN.matcher(item.price()).matches()) {
            // 값 자체를 담지 않는다. 길이만 남겨도 진단에는 충분하다.
            fatal.add(new Violation("V-05", at + ".PRICE",
                    "숫자 형식이 아니다 (길이 " + item.price().length() + ")"));
        }
    }

    /**
     * V-03 / V-04 — 카디널리티 대조.
     *
     * <p>{@code USER_ID} 기준 집합 연산으로 판정한다. 문서 순서에 의존하지 않는다.
     * 샘플에서 ITEM 이 HEADER 순서대로 정렬돼 있지 않기 때문이다.
     */
    private ValidationResult<OrderSourceMessage> matchCardinality(OrderSourceMessage source) {
        Set<String> headerUserIds = source.headers().stream()
                .map(SourceHeader::userId)
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> itemUserIds = source.items().stream()
                .map(SourceItem::userId)
                .collect(Collectors.toCollection(HashSet::new));

        List<Skip> skips = new ArrayList<>();

        // V-03: 대응 HEADER 가 없는 ITEM — ITEM 건 단위로 집계한다
        List<SourceItem> acceptedItems = new ArrayList<>();
        for (SourceItem item : source.items()) {
            if (headerUserIds.contains(item.userId())) {
                acceptedItems.add(item);
            } else {
                skips.add(new Skip(SkipReason.ORPHAN_ITEM, item.itemId()));
            }
        }

        // V-04: 대응 ITEM 이 없는 HEADER — HEADER 건 단위로 집계한다
        List<SourceHeader> acceptedHeaders = new ArrayList<>();
        for (SourceHeader header : source.headers()) {
            if (itemUserIds.contains(header.userId())) {
                acceptedHeaders.add(header);
            } else {
                skips.add(new Skip(SkipReason.HEADER_WITHOUT_ITEM, header.userId()));
            }
        }

        OrderSourceMessage accepted = new OrderSourceMessage(acceptedHeaders, acceptedItems);
        return new ValidationResult<>(accepted, List.of(), skips);
    }

    private void requirePresent(List<Violation> fatal, String rule, String field, String value) {
        if (!isPresent(value)) {
            fatal.add(new Violation(rule, field, "필수 값이 없다"));
        }
    }

    /**
     * 값 자체는 위반 메시지에 담지 않는다.
     *
     * <p>{@code NAME} 과 {@code ADDRESS} 는 개인정보다. 검증 실패 로그에 원문을 실으면
     * 마스킹 정책을 다른 곳에서 아무리 지켜도 이 경로로 새어 나간다.
     */
    private void requireWithinBytes(List<Violation> fatal, String field, String value) {
        if (!isPresent(value)) {
            return;
        }
        int bytes = utf8Length(value);
        if (bytes > MAX_BYTES) {
            fatal.add(new Violation("V-06", field,
                    "길이 초과: " + bytes + " bytes (상한 " + MAX_BYTES + ", 문자 수 " + value.length() + ")"));
        }
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
