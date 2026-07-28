package com.inspien.eai.integration.order.source;

/**
 * 소스 XML 의 {@code <ITEM>} 1건 — 주문 품목.
 *
 * @param sequence 문서 내 등장 순번(1부터)
 * @param userId   {@link SourceHeader} 와의 조인 키. <b>문서 순서가 아니라 이 값으로 묶는다</b>
 * @param price    명세상 문자열이다. 여기서 숫자로 바꾸지 않는다 — 변환하는 순간
 *                 {@code 095000} 같은 원본이 {@code 95000} 이 되거나 콤마가 붙어 적재 값이 달라진다
 */
public record SourceItem(
        int sequence,
        String userId,
        String itemId,
        String itemName,
        String price
) {
}
