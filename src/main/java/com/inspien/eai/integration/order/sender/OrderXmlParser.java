package com.inspien.eai.integration.order.sender;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 XML 파서 — 소스 문서를 {@link OrderSourceMessage} 로 옮긴다.
 *
 * <p>이 문서는 일반적인 XML 이 아니다. BOOT-001 에서 실측한 특성이 세 가지 있고,
 * 각각이 그냥 파싱하면 깨지는 지점이다.
 *
 * <ol>
 *   <li><b>XML 선언부가 없다.</b> {@code <?xml encoding="EUC-KR"?>} 가 없으므로 파서가
 *       인코딩을 알아낼 방법이 없다. XML 규격상 선언부가 없으면 UTF-8 로 간주되고,
 *       EUC-KR 바이트를 UTF-8 로 읽으면 한글이 전부 깨진다.
 *       → <b>바이트를 파서에 넘기지 않는다.</b> 우리가 먼저 EUC-KR 로 해독해 문자열로 만든 뒤
 *       {@link StringReader} 로 넘긴다. 이러면 파서가 인코딩을 추측할 여지 자체가 사라진다</li>
 *   <li><b>루트 엘리먼트가 없다.</b> {@code <HEADER>} 와 {@code <ITEM>} 이 최상위에 나열돼 있어
 *       그대로는 well-formed 가 아니다. → {@code <ROOT>} 로 감싼 뒤 파싱한다</li>
 *   <li><b>ITEM 이 HEADER 순서대로 정렬돼 있지 않다.</b> → 문서 순서에 의존하지 않고
 *       전부 읽어 담은 뒤 {@code USER_ID} 로 조인한다 (조인은 이후 단계의 책임)</li>
 * </ol>
 *
 * <p><b>해독은 엄격 모드로 한다.</b> Java 의 기본 동작은 해독 불가 바이트를 치환 문자로
 * 바꿔치기하고 조용히 넘어가는 것이다. 그러면 깨진 한글이 그대로 DB 와 FTP 영수증까지 흘러가
 * 시연 자리에서야 발견된다. FTP 디렉터리에서 관측한 {@code INSPIEN_???_...} 파일들이
 * 정확히 그 결과물이다. 인코딩 사고는 예외 없이 성공으로 보고되므로, 여기서 명시적으로 터뜨린다.
 */
public class OrderXmlParser {

    /** 소스 인코딩. 선언부가 없으므로 <b>코드가 알고 있어야 하는</b> 정보다 (B7) */
    public static final Charset SOURCE_CHARSET = Charset.forName("EUC-KR");

    private static final String EL_HEADER = "HEADER";
    private static final String EL_ITEM = "ITEM";

    private static final String ROOT_OPEN = "<ROOT>";
    private static final String ROOT_CLOSE = "</ROOT>";

    /**
     * EUC-KR 원본 바이트를 파싱한다.
     *
     * @throws NonRetryableException 해독 불가 바이트가 있는 경우. 치환하지 않고 실패시킨다
     */
    public OrderSourceMessage parse(byte[] euckrBytes) {
        return parse(decodeStrict(euckrBytes));
    }

    /**
     * 이미 해독된 문자열을 파싱한다.
     *
     * <p>바이트 진입점과 분리해 둔 이유는 <b>인코딩 문제와 구조 문제를 따로 진단하기 위해서</b>다.
     * 한 메서드에 묶으면 실패했을 때 "인코딩이 틀렸나 구조가 틀렸나" 를 구분할 수 없다.
     */
    public OrderSourceMessage parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new NonRetryableException(EaiErrorCode.SOURCE_PARSE_ERROR, "소스 문서가 비어 있다");
        }

        List<SourceHeader> headers = new ArrayList<>();
        List<SourceItem> items = new ArrayList<>();

        XMLStreamReader reader = null;
        try {
            reader = newInputFactory().createXMLStreamReader(
                    new StringReader(ROOT_OPEN + xml + ROOT_CLOSE));

            Map<String, String> current = null;
            String field = null;

            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = reader.getLocalName();
                        if (EL_HEADER.equals(name) || EL_ITEM.equals(name)) {
                            current = new HashMap<>();
                            field = null;
                        } else if (current != null) {
                            field = name;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS -> {
                        // IS_COALESCING=true 이므로 텍스트가 조각나지 않는다.
                        if (current != null && field != null) {
                            current.put(field, reader.getText().trim());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        String name = reader.getLocalName();
                        if (EL_HEADER.equals(name) && current != null) {
                            headers.add(toHeader(headers.size() + 1, current));
                            current = null;
                        } else if (EL_ITEM.equals(name) && current != null) {
                            items.add(toItem(items.size() + 1, current));
                            current = null;
                        } else {
                            field = null;
                        }
                    }
                    default -> {
                        // 주석·공백 등은 무시
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new NonRetryableException(EaiErrorCode.SOURCE_PARSE_ERROR,
                    "XML 구조가 올바르지 않다: " + e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }

        return new OrderSourceMessage(headers, items);
    }

    /**
     * 엄격 해독. 해독 불가 바이트를 만나면 치환하지 않고 예외를 던진다.
     *
     * <p>{@code new String(bytes, charset)} 은 이 상황에서 조용히 U+FFFD 로 치환한다.
     * 편해 보이지만, 데이터 연계에서는 <b>틀린 값을 성공으로 흘려보내는</b> 가장 흔한 경로다.
     */
    private String decodeStrict(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new NonRetryableException(EaiErrorCode.SOURCE_PARSE_ERROR, "소스 바이트가 비어 있다");
        }
        CharsetDecoder decoder = SOURCE_CHARSET.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(raw));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new NonRetryableException(EaiErrorCode.SOURCE_ENCODING_ERROR,
                    SOURCE_CHARSET.name() + " 로 해독할 수 없는 바이트가 있다 (" + raw.length + " bytes)", e);
        }
    }

    /**
     * 외부 엔티티·DTD 를 차단한 파서 팩토리.
     *
     * <p>소스 XML 은 외부에서 들어오는 입력이다. DTD 를 허용하면 엔티티 확장 공격(XXE, billion laughs)에
     * 노출되며, 연계 엔진은 여러 시스템의 접속정보를 쥐고 있으므로 침해 시 피해 범위가 넓다.
     * 이 과제의 소스는 DTD 를 쓰지 않으므로 차단해도 잃는 것이 없다.
     */
    private XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory;
    }

    private SourceHeader toHeader(int sequence, Map<String, String> values) {
        return new SourceHeader(
                sequence,
                values.get("USER_ID"),
                values.get("NAME"),
                values.get("ADDRESS"),
                values.get("STATUS"));
    }

    private SourceItem toItem(int sequence, Map<String, String> values) {
        return new SourceItem(
                sequence,
                values.get("USER_ID"),
                values.get("ITEM_ID"),
                values.get("ITEM_NAME"),
                values.get("PRICE"));
    }

    private void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // 닫기 실패는 원래 예외를 덮지 않는다
        }
    }
}
