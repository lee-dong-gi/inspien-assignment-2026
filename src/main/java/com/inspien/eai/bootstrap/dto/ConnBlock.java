package com.inspien.eai.bootstrap.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BOOT-000 응답의 접속정보 블록 하나 ({@code ORDER_TB_CONN} / {@code SHIPMENT_TB_CONN} / {@code FTP_CONN}).
 *
 * <p><b>이 레코드가 존재하는 이유.</b> 세 블록은 모두 {@code URL} · {@code ID} · {@code PASSWORD} 라는
 * <b>동일한 키 이름</b>을 사용한다. 따라서 응답 트리를 전역 평탄화하면 나중에 만난 블록의 값이
 * 먼저 만난 값에 가려져 <b>조용히 소실</b>된다. 실제로 초기 구현이 그 함정에 빠졌고,
 * 필수 필드 누락으로 즉시 실패(fail-fast)했기에 데이터 소실이 드러났다.
 * 연계 대상이 여럿일 때 <b>스코프를 무너뜨리는 정규화는 그 자체가 결함</b>이라는 교훈을 코드로 고정한다.
 *
 * <p>암호화 상태(수신 그대로 / 복호화 후)는 이 레코드가 구분하지 않는다.
 * 값의 변환 책임은 {@code CredentialDecryptor} 에 있고, 여기서는 <b>구조만</b> 보존한다.
 *
 * @param blockName 원 응답에서의 블록 이름. 예외 메시지 추적용
 * @param fields    블록 내부 필드. 키는 정규화(대문자)되고 수신 순서를 유지한다
 */
public record ConnBlock(String blockName, Map<String, String> fields) {

    public ConnBlock {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * 필드 조회. 값은 예외 메시지에 절대 싣지 않고 <b>어떤 키가 왔는지만</b> 알린다.
     */
    public String require(String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "BOOT-000 " + blockName + " 블록에 '" + key + "' 가 없습니다. 수신된 키: " + fields.keySet());
        }
        return value;
    }

    public String getOrDefault(String key, String fallback) {
        String value = fields.get(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * 지정한 키의 값이 다른 블록과 동일한지 비교한다.
     *
     * <p>암호문 상태에서 호출해도 유효하다. AES-ECB 는 결정적이므로 동일 평문 → 동일 암호문이며,
     * 복호화 없이도 두 접속 대상이 같은지 판별할 수 있다.
     * (동시에 ECB 가 운영 환경에서 권장되지 않는 이유이기도 하다.)
     */
    public boolean sameValueAs(ConnBlock other, String key) {
        if (other == null) {
            return false;
        }
        String mine = fields.get(key);
        return mine != null && mine.equals(other.fields().get(key));
    }
}
