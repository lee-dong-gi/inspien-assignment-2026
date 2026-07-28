package com.inspien.eai.engine.exception;

/**
 * 재시도해도 결과가 같은 예외.
 *
 * <p>유효성 검증 실패, 매핑 오류, PK 위반처럼 <b>메시지 자체 또는 로직의 문제</b>다.
 * 즉시 실패로 처리하고, 원인을 로그에 남긴다.
 */
public final class NonRetryableException extends EaiException {

    public NonRetryableException(EaiErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public NonRetryableException(EaiErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
