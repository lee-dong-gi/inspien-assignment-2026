package com.inspien.eai.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * BOOT-000 설정.
 *
 * <p>실제 값은 환경변수 또는 {@code application-local.yml}(git 제외)로 주입한다.
 * 소스와 커밋 이력에는 어떤 크리덴셜도 남기지 않는다.
 */
@ConfigurationProperties(prefix = "inspien.bootstrap")
public record BootstrapProperties(

        /** true 일 때만 BootstrapRunner 가 기동된다. 평상시 애플리케이션 실행에는 영향이 없다. */
        boolean enabled,

        /** 응답 획득 경로. 기본값 FILE — 외부 호출은 명시적으로 요청할 때만 일어난다. */
        Source source,

        /** 과제 제공 엔드포인트 (SAP Integration Suite iFlow) */
        String endpoint,

        /** HTTP Basic 인증 */
        String username,
        String password,

        /** 요청 본문에 실릴 지원자 정보 */
        Applicant applicant,

        /** 산출물 저장 위치. .gitignore 대상 */
        Path outputDir,

        Duration connectTimeout,
        Duration readTimeout
) {

    public BootstrapProperties {
        if (source == null) {
            source = Source.FILE;
        }
        if (outputDir == null) {
            outputDir = Path.of("secrets");
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (applicant == null) {
            applicant = new Applicant(null, null, null);
        }
    }

    /**
     * 응답 원문을 어디서 얻을지.
     *
     * <p>외부 연계 시스템 호출은 비용이자 리스크다. 파서·복호화를 고칠 때마다 상대 시스템을
     * 때리는 구조는 EAI 관점에서 옳지 않으므로, 최초 1회만 {@link #API} 로 받아 원문을 영속화하고
     * 이후 반복 검증은 {@link #FILE} 로 수행한다. <b>기본값이 FILE 인 것은 의도된 선택</b>이다.
     */
    public enum Source {
        /** 과제 API 를 실제로 호출한다. 최초 1회. */
        API,
        /** 이미 저장된 {@code secrets/bootstrap-response.raw} 를 재사용한다. */
        FILE
    }

    /**
     * @param phoneNumber {@code 010-1234-5678} 형식 고정.
     *                    <b>이 문자열이 그대로 복호화 키의 seed</b> 이므로
     *                    하이픈 유무·공백 하나만 달라도 모든 복호화가 실패한다.
     */
    public record Applicant(String name, String phoneNumber, String email) {
    }
}
