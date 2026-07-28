package com.inspien.eai.bootstrap.client;

import com.inspien.eai.bootstrap.BootstrapProperties;
import com.inspien.eai.bootstrap.dto.BootstrapRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * BOOT-000 — 과제 정보 수신 클라이언트.
 *
 * <p>응답을 DTO 로 바로 바인딩하지 않고 <b>원문 문자열</b>로 받는다.
 * 원문을 먼저 보존해야 파싱이 실패해도 재분석이 가능하고,
 * 1회성 외부 호출을 반복하지 않을 수 있다.
 *
 * <p>타임아웃을 명시하는 이유: 연계 시스템에서 무한 대기는 장애를 전파시킨다.
 * 제어 평면 호출이라도 예외가 아니다.
 */
@Slf4j
@Component
public class RecruitingTestClient {

    private final RestClient restClient;
    private final String endpoint;

    public RecruitingTestClient(BootstrapProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());

        this.endpoint = properties.endpoint();
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth(properties.username(), properties.password()))
                .build();
    }

    /**
     * @return 응답 본문 원문 (JSON 문자열로 기대하되, 검증하지 않고 그대로 반환)
     */
    public String call(BootstrapRequest request) {
        log.info("[BOOT-000] 과제 정보 수신 요청 → {}", endpoint);

        String body = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .body(request)
                .exchange((req, res) -> {
                    String responseBody = res.bodyTo(String.class);
                    if (res.getStatusCode().isError()) {
                        throw new IllegalStateException(
                                "[BOOT-000] 호출 실패 status=" + res.getStatusCode()
                                        + " — Basic 인증 정보와 요청 필드(NAME/PHONE_NUMBER/E_MAIL)를 확인하세요.");
                    }
                    return responseBody;
                });

        if (body == null || body.isBlank()) {
            throw new IllegalStateException("[BOOT-000] 응답 본문이 비어 있습니다.");
        }

        log.info("[BOOT-000] 응답 수신 완료 ({} bytes)", body.length());
        return body;
    }
}
