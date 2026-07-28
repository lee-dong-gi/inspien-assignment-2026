package com.inspien.eai.bootstrap.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BOOT-000 응답 파서.
 *
 * <p><b>설계 이력.</b> 초판은 응답 중첩 구조를 확정할 수 없다는 이유로 트리를 전역 평탄화한 뒤
 * 키로 조회했다. 그러나 실제 응답의 세 접속정보 블록이 모두 {@code URL} · {@code ID} · {@code PASSWORD}
 * 라는 같은 키 이름을 쓰기 때문에, 평탄화는 <b>구조를 잃는 데 그치지 않고 값을 소실시켰다</b>
 * (먼저 만난 블록의 값만 살아남음). 구조가 확인된 지금은 <b>블록 스코프를 보존하는 명시적 추출</b>로 교체한다.
 *
 * <p>남긴 유연성은 하나뿐이다: 응답이 래핑 객체에 한 겹 싸여 오더라도 {@code APPLICANT_KEY} 를
 * 가진 노드를 찾아 내려간다. 이는 <b>탐색</b>이지 <b>병합</b>이 아니므로 스코프를 무너뜨리지 않는다.
 */
@Component
public class BootstrapResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String APPLICANT_KEY = "APPLICANT_KEY";
    public static final String SAMPLE_DATA = "SAMPLE_DATA";
    public static final String ORDER_TB_CONN = "ORDER_TB_CONN";
    public static final String SHIPMENT_TB_CONN = "SHIPMENT_TB_CONN";
    public static final String FTP_CONN = "FTP_CONN";

    public BootstrapPayload parse(String rawJson) {
        JsonNode payload = locatePayloadNode(readTree(rawJson));

        return new BootstrapPayload(
                requireText(payload, APPLICANT_KEY),
                requireBlock(payload, ORDER_TB_CONN),
                requireBlock(payload, SHIPMENT_TB_CONN),
                requireBlock(payload, FTP_CONN),
                requireText(payload, SAMPLE_DATA)
        );
    }

    // ─────────────────────────────────────────────────────────

    private JsonNode readTree(String rawJson) {
        try {
            return OBJECT_MAPPER.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "BOOT-000 응답을 JSON 으로 파싱하지 못했습니다. "
                            + "secrets/bootstrap-response.raw 의 실제 응답 본문을 확인하세요. "
                            + "(인증 실패 시 HTML 오류 페이지가 반환될 수 있습니다)", e);
        }
    }

    /**
     * {@code APPLICANT_KEY} 를 직접 필드로 갖는 객체를 찾는다. 최상위에 있으면 그대로 반환한다.
     * 하위 구조는 건드리지 않으므로 블록 스코프가 유지된다.
     */
    private JsonNode locatePayloadNode(JsonNode root) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            JsonNode node = queue.poll();
            if (node.isObject() && findField(node, APPLICANT_KEY) != null) {
                return node;
            }
            node.forEach(queue::add);
        }
        throw new IllegalStateException(
                "BOOT-000 응답에서 " + APPLICANT_KEY + " 를 가진 객체를 찾지 못했습니다. 최상위 키: " + fieldNames(root));
    }

    /** 키 표기 흔들림(대소문자·하이픈)에 견디도록 정규화해 비교한다. */
    private JsonNode findField(JsonNode object, String normalizedName) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (normalize(entry.getKey()).equals(normalizedName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String requireText(JsonNode payload, String name) {
        JsonNode node = findField(payload, name);
        if (node == null || !node.isValueNode() || node.asText().isBlank()) {
            throw new IllegalStateException(
                    "BOOT-000 응답에 필수 필드 '" + name + "'(문자열) 이 없습니다. 수신된 키: " + fieldNames(payload));
        }
        return node.asText();
    }

    /**
     * 접속정보 블록을 <b>블록 단위로</b> 추출한다. 다른 블록과 병합하지 않는다.
     */
    private ConnBlock requireBlock(JsonNode payload, String name) {
        JsonNode node = findField(payload, name);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(
                    "BOOT-000 응답의 '" + name + "' 이 객체가 아닙니다"
                            + (node == null ? " (필드 없음)" : " (실제 타입: " + node.getNodeType() + ")")
                            + ". 수신된 키: " + fieldNames(payload));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isValueNode()) {
                fields.put(normalize(entry.getKey()), entry.getValue().asText());
            }
        }
        if (fields.isEmpty()) {
            throw new IllegalStateException("BOOT-000 '" + name + "' 블록에 값 필드가 하나도 없습니다.");
        }
        return new ConnBlock(name, fields);
    }

    private String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private String normalize(String key) {
        return key.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }
}
