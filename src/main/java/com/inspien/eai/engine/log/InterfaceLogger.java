package com.inspien.eai.engine.log;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.message.ProcessResult;

/**
 * 인터페이스 실행 이력 로거.
 *
 * <p>애플리케이션 로그와 <b>분리</b>한다 (과제 3.3: local 파일 저장).
 * 출력 경로는 {@code logs/interface/interface-yyyyMMdd.log}.
 *
 * <p>둘을 나누는 이유는 독자가 다르기 때문이다. 애플리케이션 로그는 개발자가 디버깅할 때 보고,
 * 인터페이스 이력은 <b>운영자가 "오늘 몇 건 흘렀고 뭐가 실패했나" 를 볼 때</b> 본다.
 * 한 파일에 섞이면 디버그 로그 사이에 실행 이력이 파묻혀 후자의 용도로는 쓸 수 없게 된다.
 * 사전 안내에 나오는 RTIMS(실시간 모니터링)의 사상을 파일 레벨로 축소 구현한 것이다.
 *
 * <h2>개인정보</h2>
 * 이 로그에는 {@code NAME}·{@code ADDRESS} 가 <b>애초에 실리지 않는다.</b>
 * 건수·구간·결과·에러 코드만 남기기 때문이다. 마스킹으로 걸러내는 것보다
 * <b>구조적으로 들어갈 자리를 두지 않는 것</b>이 강한 통제다. 마스킹은 규칙을 한 곳에서
 * 빠뜨리면 그대로 새지만, 스키마에 자리가 없으면 실수로도 넣을 수 없다.
 *
 * <h2>한 실행의 구성</h2>
 * <pre>
 *   begin()   → START 1줄
 *   step()    → 구간마다 1줄 (종료 시점에 기록, 소요 시간 포함)
 *   complete()→ END 1줄
 * </pre>
 * 구간마다 시작·종료 2줄을 남기지 않는 것은 의도다. 파일이 두 배가 되는 대신 얻는 것이 적다.
 * 어딘가 매달리면 <b>{@code START} 는 있는데 {@code END} 가 없는</b> 상태로 드러나고,
 * 마지막 {@code STEP} 다음 구간에서 멈춘 것으로 특정할 수 있다.
 */
public interface InterfaceLogger {

    /** 이 로거 전용 Logback 로거 이름. 클래스명이 아니라 채널 이름으로 분리한다 */
    String LOGGER_NAME = "INTERFACE";

    /**
     * 인터페이스 실행 시작을 기록한다.
     *
     * @param detail 부가정보. 트리거 종류 등. 전체 {@code txId} 도 여기에 한 번 남긴다
     */
    void begin(MessageHeader header, String detail);

    /**
     * 구간 실행을 기록한다. try-with-resources 로 사용한다.
     *
     * <pre>{@code
     * try (StepScope scope = logger.step(header, Step.VALIDATOR)) {
     *     ValidationResult<?> r = validator.validate(message);
     *     scope.detail("ORPHAN_ITEM=%d", 7).partial(63, 11);
     * }
     * }</pre>
     *
     * <p>범위 객체로 만든 이유는 <b>소요 시간과 종료 여부를 강제로 남기기 위해서</b>다.
     * 시작만 찍고 끝을 안 찍는 로그가 운영에서 가장 흔하고, 그런 로그는
     * "멈춘 것인지 느린 것인지" 를 구분해주지 못한다.
     */
    StepScope step(MessageHeader header, Step step);

    /** 인터페이스 실행 전체의 종료를 기록한다. 건수·결과·에러 코드가 여기서 확정된다 */
    void complete(MessageHeader header, ProcessResult result);

    /**
     * 구간 범위.
     *
     * <p>{@link #close()} 시점에 결과가 표시되지 않았다면 {@code ABORTED} 로 남긴다.
     * <b>조용히 사라지는 구간을 만들지 않는 것</b>이 이 타입의 목적이다.
     * 예외로 빠져나간 경우가 대표적인데, 그때 아무 기록도 없으면 운영자는
     * 그 구간이 실행조차 안 된 것으로 오해한다.
     */
    interface StepScope extends AutoCloseable {

        /** DETAIL 열에 실을 부가정보. 개인정보를 넣지 않는다 */
        StepScope detail(String format, Object... args);

        void success(int ok);

        void partial(int ok, int skip);

        void fail(EaiErrorCode code, String message);

        void fail(EaiErrorCode code, String message, int failed);

        @Override
        void close();
    }
}
