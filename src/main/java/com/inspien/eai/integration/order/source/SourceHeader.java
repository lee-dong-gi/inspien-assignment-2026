package com.inspien.eai.integration.order.source;

/**
 * 소스 XML 의 {@code <HEADER>} 1건 — 주문자 정보.
 *
 * <p>송신 시스템의 구조를 <b>그대로</b> 담는다. 이 단계에서 값을 정규화하거나 보정하지 않는다.
 * 정제는 Validator 와 Mapper 의 몫이고, 여기서 손대면 "원본이 무엇이었는지" 를 되짚을 수 없다.
 *
 * @param sequence 문서 내 등장 순번(1부터). 필수 필드가 없어 {@code userId} 로 지목할 수 없는
 *                 위반을 보고할 때 쓴다. 값을 로그에 노출하지 않고도 위치를 특정하기 위한 장치다
 * @param status   샘플은 전건 {@code N} 이지만 정규화하지 않고 원본을 유지한다 (D-01 은 Mapper 에서 적용)
 */
public record SourceHeader(
        int sequence,
        String userId,
        String name,
        String address,
        String status
) {
}
