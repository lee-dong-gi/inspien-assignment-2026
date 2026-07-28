package com.inspien.eai.bootstrap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * BOOT-000 요청 본문.
 *
 * <p>필드명이 대문자 스네이크 표기이므로 {@link JsonProperty} 로 고정한다.
 * 값은 PDF 에서 복사하지 말고 직접 타이핑한 것을 설정에 넣는다(전각 문자 혼입 방지).
 */
public record BootstrapRequest(

        @JsonProperty("NAME")
        String name,

        @JsonProperty("PHONE_NUMBER")
        String phoneNumber,

        @JsonProperty("E_MAIL")
        String email
) {
}
