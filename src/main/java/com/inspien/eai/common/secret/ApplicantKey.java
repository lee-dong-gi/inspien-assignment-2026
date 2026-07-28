package com.inspien.eai.common.secret;

/**
 * 지원자 키 — 두 테이블의 복합 PK 두 번째 컬럼이자, <b>데이터 격리의 유일한 경계</b>.
 *
 * <p>원시 {@code String} 으로 흘리지 않고 타입으로 감싼 이유는 이 값이 조회 조건에서
 * 빠졌을 때의 결과 때문이다. {@code WHERE APPLICANT_KEY = ?} 를 한 번만 빠뜨리면
 * <b>다른 지원자의 데이터까지 조회·갱신</b>된다. 같은 테이블을 여러 지원자가 공유하는
 * 이번 환경에서는 그 사고가 실제로 가능하다.
 *
 * <p>{@code String} 파라미터는 다른 {@code String} 과 섞여도 컴파일러가 잡아 주지 않지만,
 * 전용 타입이면 자리를 바꿔 넣는 순간 컴파일이 깨진다.
 *
 * <p>{@link #toString()} 을 마스킹한 것도 의도다. 이 값 자체는 비밀이 아니지만
 * 정의서·README·로그에 실제 값을 남기지 않기로 했고(보안 취급 등급), 객체를 그대로
 * 문자열 결합에 넣는 실수는 예외 메시지에서 가장 흔하게 일어난다.
 * 값이 필요한 곳은 {@link #value()} 를 명시적으로 호출한다.
 */
public record ApplicantKey(String value) {

    public ApplicantKey {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "APPLICANT_KEY 가 비어 있다. BOOT-000 산출물(applicant-key.txt)을 확인할 것");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return "APPLICANT_KEY(" + value.charAt(0) + "***, " + value.length() + "자)";
    }
}
