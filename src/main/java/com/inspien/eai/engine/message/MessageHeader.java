package com.inspien.eai.engine.message;

import com.inspien.eai.engine.InterfaceId;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 메시지 헤더 — 페이로드와 분리된 <b>추적 정보</b>.
 *
 * <p>EAI 에서 헤더와 본문을 나누는 이유는 형식 때문이 아니라 <b>책임</b> 때문이다.
 * 본문은 송신 시스템의 관심사(주문 내용)이고, 헤더는 연계 엔진의 관심사(누가 언제 무엇을
 * 흘려보냈는가)다. 엔진은 본문을 이해하지 않고도 헤더만으로 라우팅·로깅·추적을 수행할 수 있어야 한다.
 *
 * @param txId       요청 1건당 1개. 전 구간(SENDER→MAPPER→RECEIVER) 로그와 응답에 동일하게 실린다
 * @param ifId       인터페이스 식별자
 * @param occurredAt 인터페이스 실행 시작 시각. FTP 영수증 파일명의 타임스탬프로도 쓰이므로
 *                   구간마다 다시 찍지 않고 <b>최초 1회 고정</b>한다
 */
public record MessageHeader(
        String txId,
        InterfaceId ifId,
        LocalDateTime occurredAt
) {

    public MessageHeader {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId 는 필수다. 추적 불가능한 메시지는 흘려보내지 않는다.");
        }
        if (ifId == null) {
            throw new IllegalArgumentException("ifId 는 필수다.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 은 필수다.");
        }
    }

    /**
     * 새 실행에 대한 헤더를 발급한다.
     *
     * <p>{@code txId} 는 여기서 <b>단 한 번</b> 생성된다. 중간 단계에서 새로 만들면
     * 로그가 끊어져 "어느 요청이 실패했는가"를 추적할 수 없게 된다.
     */
    public static MessageHeader issue(InterfaceId ifId) {
        return new MessageHeader(UUID.randomUUID().toString(), ifId, LocalDateTime.now());
    }
}
