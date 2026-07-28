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
 * <p><b>개인정보는 마스킹 후 기록한다.</b> 로그는 백업되고 오래 남으며 접근 통제가 느슨하다.
 * {@code NAME}·{@code ADDRESS} 가 원문으로 남는 순간 로그 파일 자체가 유출 대상이 된다.
 */
public interface InterfaceLogger {

    /**
     * 구간 실행을 기록한다. try-with-resources 로 사용한다.
     *
     * <pre>{@code
     * try (StepScope scope = logger.step(header, Step.RECEIVER_JDBC)) {
     *     int n = receiver.prepare(msg).count();
     *     scope.success(n);
     * }
     * }</pre>
     *
     * <p>범위 객체로 만든 이유는 <b>소요 시간과 종료 여부를 강제로 남기기 위해서</b>다.
     * 시작만 찍고 끝을 안 찍는 로그가 운영에서 가장 흔하고, 그런 로그는
     * "멈춘 것인지 느린 것인지" 를 구분해주지 못한다.
     */
    StepScope step(MessageHeader header, Step step);

    /** 인터페이스 실행 전체의 종료를 기록한다. 건수·결과·에러 코드가 여기서 확정된다. */
    void complete(MessageHeader header, ProcessResult result);

    /**
     * 구간 범위.
     *
     * <p>{@link #close()} 시점에 성공/실패 어느 쪽도 표시되지 않았다면 <b>비정상 종료</b>로 남긴다.
     * 조용히 사라지는 구간을 만들지 않는 것이 이 인터페이스의 목적이다.
     */
    interface StepScope extends AutoCloseable {

        void success(int count);

        void skip(int count, String reason);

        void fail(EaiErrorCode code, String detail);

        @Override
        void close();
    }
}
