package com.inspien.eai.engine.exception;

/**
 * 연계 엔진 예외의 최상위 타입.
 *
 * <p>모든 예외가 {@link EaiErrorCode} 를 반드시 동반한다. 메시지만 있는 예외는
 * 로그에서 검색되지 않고 분류되지 않으며, 결국 "어디선가 터졌다" 이상을 남기지 못한다.
 *
 * <p>sealed 로 하위를 두 갈래로 못박은 것은 <b>재시도 가능/불가</b> 이외의 제3의 처리 방식을
 * 만들지 않겠다는 선언이다. 예외 종류가 늘어날수록 처리 분기가 흩어지고, 흩어지면 누락된다.
 */
public sealed class EaiException extends RuntimeException
        permits RetryableException, NonRetryableException {

    private final EaiErrorCode errorCode;

    protected EaiException(EaiErrorCode errorCode, String detail) {
        super(errorCode.code() + " " + errorCode.message()
                + (detail == null || detail.isBlank() ? "" : " — " + detail));
        this.errorCode = errorCode;
    }

    protected EaiException(EaiErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.code() + " " + errorCode.message()
                + (detail == null || detail.isBlank() ? "" : " — " + detail), cause);
        this.errorCode = errorCode;
    }

    public EaiErrorCode errorCode() {
        return errorCode;
    }
}
