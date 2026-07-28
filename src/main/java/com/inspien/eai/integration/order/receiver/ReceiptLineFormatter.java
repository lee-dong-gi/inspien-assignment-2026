package com.inspien.eai.integration.order.receiver;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.integration.order.target.OrderRecord;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;

/**
 * 영수증 파일 라인 조립 — 8필드, 구분자 {@code ^}, 종결자 {@code \n}.
 *
 * <pre>
 *   ORDER_ID ^ USER_ID ^ ITEM_ID ^ APPLICANT_KEY ^ NAME ^ ADDRESS ^ ITEM_NAME ^ PRICE \n
 * </pre>
 *
 * <h2>DB 컬럼 순서를 그대로 쓰면 오답이다</h2>
 * <pre>
 *   DB   : ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS
 *   파일 : ORDER_ID, USER_ID, ITEM_ID, APPLICANT_KEY, NAME, ADDRESS, ITEM_NAME, PRICE
 *                    └── 2·3·4번이 다르고 STATUS 는 아예 없다 (9필드 vs 8필드)
 * </pre>
 * {@link OrderRecord} 의 선언 순서는 DB 기준이므로, 필드를 순서대로 이어 붙이는 방식은
 * 컴파일도 되고 테스트도 통과하며 <b>값만 어긋난다.</b> 그래서 여기서는 접근자를
 * <b>이름으로 하나씩 지목</b>한다.
 *
 * <h2>두 가지를 조용히 깨지게 두지 않는다</h2>
 * <ol>
 *   <li><b>값에 구분자가 섞인 경우.</b> 이 포맷에는 이스케이프 규칙이 없다. 주소에 {@code ^} 가
 *       들어 있으면 8필드가 9필드로 읽히고, 수신 측은 그것을 <b>정상 파일로 파싱</b>해
 *       엉뚱한 값을 배송 정보로 쓴다. 실패시키는 편이 낫다</li>
 *   <li><b>대상 인코딩으로 표현할 수 없는 문자.</b> {@code String.getBytes(EUC_KR)} 는
 *       표현 불가 문자를 예외 없이 {@code ?} 로 바꾼다. FTP 파일명이 {@code ?} 로 깨진
 *       다른 지원자들의 파일과 <b>정확히 같은 종류의 사고</b>이며, 마찬가지로 성공으로 보고된다.
 *       미리 검사해서 끊는다</li>
 * </ol>
 */
public final class ReceiptLineFormatter {

    static final char DELIMITER = '^';
    static final char TERMINATOR = '\n';

    /** 라인 하나의 대략적인 길이. StringBuilder 초기 용량 힌트일 뿐 상한이 아니다. */
    private static final int ESTIMATED_LINE_LENGTH = 96;

    private ReceiptLineFormatter() {
    }

    /**
     * 전체 파일 내용을 바이트로 만든다.
     *
     * <p>메모리에 통째로 올린다. 한 요청의 행 수는 소스 XML 크기에 묶여 있고(샘플 63행 ≈ 5KB)
     * 스트리밍으로 얻을 이득보다, <b>업로드를 시작하기 전에 내용 전체가 유효함을 확인</b>하는
     * 이득이 크다. 절반쯤 올리다 인코딩 오류로 멈추면 서버에 잘린 파일이 남는다.
     *
     * <p><b>마지막 라인에도 종결자를 붙인다.</b> 과제 예시가 그 형태이고, 수신 측 파서가
     * 라인 단위로 읽을 때 마지막 줄만 다르게 취급하지 않아도 된다.
     */
    public static byte[] render(List<OrderRecord> records, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder content = new StringBuilder(records.size() * ESTIMATED_LINE_LENGTH);

        for (int row = 0; row < records.size(); row++) {
            String line = format(records.get(row), row);
            requireEncodable(line, encoder, charset, row);
            content.append(line).append(TERMINATOR);
        }
        return content.toString().getBytes(charset);
    }

    /** 라인 하나. 필드를 <b>이름으로</b> 지목한다 — 순서 실수를 컴파일러가 잡아 주지 않기 때문이다. */
    static String format(OrderRecord record, int row) {
        return new StringBuilder(ESTIMATED_LINE_LENGTH)
                .append(field(record.orderId(), "ORDER_ID", row)).append(DELIMITER)
                .append(field(record.userId(), "USER_ID", row)).append(DELIMITER)
                .append(field(record.itemId(), "ITEM_ID", row)).append(DELIMITER)
                .append(field(record.applicantKey(), "APPLICANT_KEY", row)).append(DELIMITER)
                .append(field(record.name(), "NAME", row)).append(DELIMITER)
                .append(field(record.address(), "ADDRESS", row)).append(DELIMITER)
                .append(field(record.itemName(), "ITEM_NAME", row)).append(DELIMITER)
                .append(field(record.price(), "PRICE", row))
                .toString();
        // STATUS 는 붙이지 않는다. 회계 담당자에게 전송 상태는 필요 없는 정보이고,
        // 파일은 8필드로 고정이다 (정의서 3.6).
    }

    /**
     * 필드 하나의 안전성 확인.
     *
     * <p>값 자체는 메시지에 담지 않는다. {@code NAME}·{@code ADDRESS} 가 예외를 타고
     * 로그 파일로 흘러가는 경로를 만들지 않기 위해서다. 행 번호와 컬럼명만으로
     * 원본을 찾아갈 수 있다.
     */
    private static String field(String value, String column, int row) {
        if (value == null) {
            // DB 라면 NULL 로 눈에 보이지만, 파일에서는 빈 필드가 되어 구분이 되지 않는다.
            // 검증을 통과했다면 도달하지 않지만, 도달하면 조용히 넘기지 않는다.
            throw new NonRetryableException(EaiErrorCode.MAPPING_ERROR,
                    row + "번째 행의 " + column + " 이 비어 있어 영수증 라인을 조립할 수 없다");
        }
        if (value.indexOf(DELIMITER) >= 0) {
            throw new NonRetryableException(EaiErrorCode.MAPPING_ERROR,
                    row + "번째 행의 " + column + " 에 구분자 '" + DELIMITER + "' 가 들어 있다. "
                            + "이 포맷에는 이스케이프 규칙이 없어 수신 측이 필드 수를 잘못 읽는다");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new NonRetryableException(EaiErrorCode.MAPPING_ERROR,
                    row + "번째 행의 " + column + " 에 개행이 들어 있다. 한 건이 두 라인으로 쪼개진다");
        }
        return value;
    }

    /**
     * 대상 인코딩으로 표현 가능한지.
     *
     * <p>{@code getBytes()} 는 표현 불가 문자를 조용히 {@code ?} 로 바꾼다. 그 파일은
     * 업로드도 성공하고 크기도 정상이며, <b>열어 보기 전까지 아무도 모른다.</b>
     */
    private static void requireEncodable(String line, CharsetEncoder encoder, Charset charset, int row) {
        if (encoder.canEncode(line)) {
            return;
        }
        for (int i = 0; i < line.length(); i++) {
            if (!encoder.canEncode(line.charAt(i))) {
                // 코드포인트 하나만 남긴다. 어느 글자가 문제인지는 알려주되 값 전체는 담지 않는다.
                throw new NonRetryableException(EaiErrorCode.FTP_ENCODING_ERROR,
                        row + "번째 행의 " + i + "번째 문자(U+"
                                + String.format("%04X", (int) line.charAt(i)) + ")를 "
                                + charset.name() + " 로 표현할 수 없다. "
                                + "그대로 내보내면 '?' 로 치환된 채 성공으로 보고된다");
            }
        }
    }
}
