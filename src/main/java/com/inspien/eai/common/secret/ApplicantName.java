package com.inspien.eai.common.secret;

/**
 * 참여자명(한글) — 영수증 파일명 규칙 {@code INSPIEN_{참여자명}_yyyyMMddHHmmss.txt} 의 가운데 조각.
 *
 * <p>{@link ApplicantKey} 와 달리 이 값은 <b>BOOT-000 응답이 아니라 우리가 설정한 값</b>이다.
 * 과제 API 요청 본문에 실었던 이름과 <b>같은 출처</b>({@code inspien.bootstrap.applicant.name})를
 * 쓴다. 두 곳에 따로 적으면 API 에 제출한 이름과 파일명의 이름이 갈라질 수 있고,
 * 채점자는 그 둘을 대조한다.
 *
 * <h2>왜 검증을 여기서 하는가</h2>
 * 값이 비어 있으면 파일명이 {@code INSPIEN__20260729...txt} 가 된다. 예외도 나지 않고
 * 업로드도 성공하며, <b>면접 시연 자리에서 파일 목록을 열었을 때 처음 알게 된다.</b>
 * 경로 구분자나 제어문자가 섞이는 경우도 마찬가지로 조용히 엉뚱한 경로에 쓰이거나
 * 서버가 이름을 잘라 버린다. 조립 시점에 끊는 편이 훨씬 싸다.
 */
public record ApplicantName(String value) {

    public ApplicantName {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("""
                    참여자명이 비어 있다. 영수증 파일명 규칙(INSPIEN_{참여자명}_yyyyMMddHHmmss.txt)을
                    만족할 수 없다. application-local.yml 의 inspien.bootstrap.applicant.name 을 확인할 것
                    """);
        }
        value = value.trim();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20) {
                throw new IllegalStateException(
                        "참여자명에 파일명으로 쓸 수 없는 문자가 있다 (위치 " + i + "). "
                                + "경로 구분자·제어문자는 서버에서 조용히 잘리거나 다른 경로로 해석된다");
            }
        }
    }
}
