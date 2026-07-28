package com.inspien.eai.integration.order.source;

import java.util.List;

/**
 * IF-ORD-001 의 소스 페이로드 — 파싱된 주문 XML 전체.
 *
 * <p>HEADER 와 ITEM 을 <b>평탄화하지 않고 그대로</b> 들고 있는다.
 * 조인은 Mapper 의 책임이고, 그 전에 Validator 가 "짝이 맞는가" 를 판정해야 하기 때문이다.
 * 파싱 단계에서 미리 합쳐버리면 고아 ITEM 이 조용히 사라져 검출할 기회 자체가 없어진다.
 *
 * <p>관계는 {@code HEADER : ITEM = 1 : N}, 조인 키는 {@code USER_ID}.
 */
public record OrderSourceMessage(
        List<SourceHeader> headers,
        List<SourceItem> items
) {

    public OrderSourceMessage {
        headers = (headers == null) ? List.of() : List.copyOf(headers);
        items = (items == null) ? List.of() : List.copyOf(items);
    }

    public static OrderSourceMessage empty() {
        return new OrderSourceMessage(List.of(), List.of());
    }

    public int headerCount() {
        return headers.size();
    }

    public int itemCount() {
        return items.size();
    }
}
