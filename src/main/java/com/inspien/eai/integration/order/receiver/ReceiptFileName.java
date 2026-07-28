package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.common.secret.ApplicantName;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 영수증 파일명 규칙 — {@code INSPIEN_{참여자명}_yyyyMMddHHmmss.txt}.
 *
 * <h2>시각을 여기서 찍지 않는다</h2>
 * {@code LocalDateTime.now()} 를 부르지 않고 <b>인터페이스 실행 시작 시각</b>을 받는다
 * ({@link com.inspien.eai.engine.message.MessageHeader#occurredAt()}).
 *
 * <p>이유는 두 가지다. 첫째, 정의서 3.6 이 "타임스탬프 기준 시각은 인터페이스 실행 시작 시각" 으로
 * 고정하고 있다. 둘째, 그래야 <b>로그의 {@code txId} 와 파일명이 같은 시각을 가리킨다.</b>
 * Receiver 에서 다시 찍으면 검증·매핑·DB 적재에 걸린 시간만큼 어긋나고, 운영자가
 * "이 파일이 어느 요청에서 나왔나" 를 시각으로 좁힐 때 그 오차가 그대로 방해가 된다.
 *
 * <p>재시도가 생기면 차이는 더 커진다 — 같은 요청이 두 파일을 만들면서 이름은 달라지고,
 * 둘 중 무엇이 유효한지 판단할 근거가 사라진다.
 */
public final class ReceiptFileName {

    private static final String PREFIX = "INSPIEN_";
    private static final String EXTENSION = ".txt";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ReceiptFileName() {
    }

    /**
     * @param occurredAt 인터페이스 실행 시작 시각. <b>전 라인·전 파일이 이 하나를 공유한다</b>
     */
    public static String of(ApplicantName applicantName, LocalDateTime occurredAt) {
        return PREFIX + applicantName.value() + "_" + TIMESTAMP.format(occurredAt) + EXTENSION;
    }
}
