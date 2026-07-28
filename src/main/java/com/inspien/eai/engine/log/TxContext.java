package com.inspien.eai.engine.log;

import com.inspien.eai.engine.message.MessageHeader;
import org.slf4j.MDC;

/**
 * 추적 컨텍스트 — {@code txId} 를 MDC 에 실어 전 구간 로그에 전파한다.
 *
 * <p>이것이 없으면 모든 로그 호출에 {@code txId} 를 인자로 넘겨야 한다.
 * 그러면 로깅 관심사가 도메인 코드 시그니처를 오염시키고, 한 군데만 빠뜨려도
 * 추적이 끊긴다. MDC 에 한 번 심고 <b>Logback 패턴에서 꺼내 쓰면</b>
 * 애플리케이션 로그 전체가 자동으로 추적 가능해진다.
 *
 * <pre>
 *   %d{HH:mm:ss.SSS} %-5level [%X{txId:-}] %logger{36} - %msg%n
 * </pre>
 *
 * <p><b>반드시 해제해야 한다.</b> MDC 는 ThreadLocal 이고 스레드는 풀에서 재사용된다.
 * 해제하지 않으면 다음 요청의 로그에 이전 요청의 {@code txId} 가 붙어,
 * 추적하려고 만든 장치가 오히려 <b>추적을 오염시킨다.</b>
 */
public final class TxContext {

    public static final String TX_ID = "txId";
    public static final String IF_ID = "ifId";

    private TxContext() {
    }

    public static void bind(MessageHeader header) {
        MDC.put(TX_ID, header.txId());
        MDC.put(IF_ID, header.ifId().code());
    }

    public static void clear() {
        MDC.remove(TX_ID);
        MDC.remove(IF_ID);
    }

    public static String currentTxId() {
        return MDC.get(TX_ID);
    }
}
