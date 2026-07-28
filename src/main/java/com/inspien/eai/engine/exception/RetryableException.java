package com.inspien.eai.engine.exception;

/**
 * 재시도로 회복 가능한 예외.
 *
 * <p>커넥션 타임아웃, 일시적 단절 등 <b>대상 시스템의 상태 문제</b>다.
 * 지수 백오프로 제한된 횟수만 재시도한다. 무제한 재시도는 장애를 전파시킬 뿐이다.
 */
public final class RetryableException extends EaiException {

    public RetryableException(EaiErrorCode errorCode, String detail) {
        super(requireRetryable(errorCode), detail);
    }

    public RetryableException(EaiErrorCode errorCode, String detail, Throwable cause) {
        super(requireRetryable(errorCode), detail, cause);
    }

    /**
     * 코드와 예외 타입의 불일치를 <b>생성 시점에</b> 막는다.
     *
     * <p>{@code VALIDATION_ERROR} 를 재시도 예외로 던지면 잘못된 데이터를 영원히 재시도하게 된다.
     * 이런 종류의 실수는 런타임에 조용히 굴러가므로 컴파일 이후 최대한 이른 지점에서 끊는다.
     */
    private static EaiErrorCode requireRetryable(EaiErrorCode code) {
        if (!code.retryable()) {
            throw new IllegalArgumentException(
                    code + " 는 재시도 대상이 아니다. NonRetryableException 을 사용할 것.");
        }
        return code;
    }
}
