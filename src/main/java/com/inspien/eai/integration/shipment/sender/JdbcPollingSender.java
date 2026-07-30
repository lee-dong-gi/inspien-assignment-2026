package com.inspien.eai.integration.shipment.sender;

import com.inspien.eai.common.jdbc.JdbcErrorTranslator;
import com.inspien.eai.common.jdbc.SqlIdentifiers;
import com.inspien.eai.common.secret.ApplicantKey;
import com.inspien.eai.engine.InterfaceId;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.message.CanonicalMessage;
import com.inspien.eai.engine.message.MessageHeader;
import com.inspien.eai.engine.sender.Sender;
import com.inspien.eai.integration.order.target.OrderStatus;
import com.inspien.eai.integration.shipment.source.PendingOrder;
import com.inspien.eai.integration.shipment.source.PollCursor;
import com.inspien.eai.integration.shipment.source.ShipmentSourceMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.util.List;

/**
 * IF-SHP-001 Sender — {@code ORDER_TB} 폴링.
 *
 * <h2>이 클래스가 이 과제의 증명이다</h2>
 * {@code OrderRestSender} 를 이것으로 바꿔 끼우는 것만으로 시나리오 2가 완성된다.
 * Validator · Mapper · Receiver 의 <b>계약</b>도, 조율자도, 실행 이력 로거도 그대로다.
 * 과제가 요구한 것이 "기능 두 벌" 이 아니라 <b>"연계 구조 하나"</b> 라는 판단의 실증이며,
 * {@link Sender} 인터페이스가 존재하는 이유 그 자체다.
 *
 * <h2>push 와 pull 의 차이는 여기서 끝난다</h2>
 * <pre>
 *   REST Sender : 보내온 것을 받는다   — 무엇을 처리할지 <b>송신 측</b>이 정한다
 *   폴링 Sender : 스스로 찾아 읽는다   — 무엇을 처리할지 <b>우리</b>가 정한다
 * </pre>
 * 그래서 트리거의 성격도 다르다. REST 의 트리거는 요청 본문 그 자체지만,
 * 폴링의 트리거는 <b>"어디부터 읽을지" 뿐</b>이다 ({@link PollCursor}).
 * 조회 조건({@code APPLICANT_KEY} · {@code STATUS='N'})은 트리거가 정하지 않는다 —
 * 그것은 <b>이 인터페이스의 정의</b>이고, 외부가 바꿀 수 있는 것이 아니다.
 *
 * <h2>{@code APPLICANT_KEY} 조건은 협상 대상이 아니다</h2>
 * 이 테이블은 지원자 전원이 공유한다. 조건을 한 번 빠뜨리면 <b>다른 지원자의 주문을 읽어
 * 우리 SHIPMENT_TB 에 적재하고 그쪽 STATUS 를 'Y' 로 바꾼다.</b>
 * append-only 환경이라 되돌릴 수도 없다. 그래서 이 값은 {@code String} 이 아니라
 * {@link ApplicantKey} 타입으로 받는다 — 인자 자리를 바꿔 넣으면 컴파일이 깨진다.
 *
 * <h2>{@code LIMIT} 이 아니라 {@code FETCH FIRST} 다</h2>
 * 대상이 Oracle 19c 이므로 {@code LIMIT} 은 문법 오류다(정의서 4.2 / B1).
 * {@code FETCH FIRST … ROWS ONLY} 는 12c 부터 지원되며 바인드 변수를 받는다.
 *
 * <p>{@code ORDER BY ORDER_ID} 는 <b>필수</b>다. 정렬 없는 {@code FETCH FIRST} 는
 * "아무 100건" 이므로 커서 전진의 근거가 사라진다. 우리 채번 형식은 사전식 정렬 순서가
 * 채번 순서와 일치하므로({@code A000 < A999 < B000}) 이 정렬은 곧 <b>발생 순서</b>다 —
 * 먼저 들어온 주문이 먼저 배송된다.
 *
 * <h2>커서 조건절을 붙일 때와 안 붙일 때로 SQL 을 나눈다</h2>
 * 첫 청크에 "모든 값보다 작은 초기 커서" 를 넘겨 문장을 하나로 합칠 수도 있었지만
 * 하지 않았다. Oracle 에서 <b>빈 문자열은 {@code NULL}</b> 이므로 {@code ORDER_ID > ''} 는
 * 참이 되지 않고 <b>말없이 0건</b>을 돌려준다. 예외도 나지 않아 "미전송 주문이 없다" 로 보인다.
 * {@code '0'} 같은 리터럴 센티널은 {@code NLS_SORT} 설정에 따라 순서가 달라질 수 있다.
 * 문장을 둘로 두는 쪽이 코드 두 줄 더 쓰고 <b>가정을 하나도 안 하는</b> 길이다.
 *
 * <h2>{@code SELECT … FOR UPDATE} 를 쓰지 않는다 (D-05)</h2>
 * 조회와 갱신 사이에 끼어들 주체가 없기 때문이다. {@code STATUS} 를 바꾸는 것은
 * 이 배치뿐이고, 배치는 분산 락으로 하나만 돈다. IF-ORD-001 은 새 행을 INSERT 할 뿐
 * 기존 행의 상태를 건드리지 않는다.
 *
 * <p>행 잠금을 걸면 얻는 것 없이 <b>커밋까지 잠금을 붙잡는다.</b> 이미 보상 트랜잭션 구조상
 * 커넥션을 오래 점유하는 설계이므로 잠금 시간을 더 늘리는 선택은 하지 않는다.
 * 인스턴스를 여러 대로 늘린다면 그때는 상태 선점({@code N}→{@code P}) 쪽이
 * {@code FOR UPDATE} 보다 낫다 — 잠금은 커넥션이 끊기면 풀리지만 상태는 남는다.
 *
 * <h2>실행당 여러 번 호출된다</h2>
 * IF-ORD-001 의 Sender 는 실행당 한 번 불리지만, 이쪽은 <b>청크마다</b> 불린다.
 * {@link Sender} 계약에 어긋나지 않는다 — "송신 시스템에서 메시지를 받아 표준 메시지로
 * 감싼다" 는 책임은 그대로이고, 몇 번 부를지는 실행 단위가 정한다.
 */
@Slf4j
public class JdbcPollingSender implements Sender<PollCursor, ShipmentSourceMessage> {

    /**
     * 조회할 컬럼은 셋뿐이다.
     *
     * <p>{@code SELECT *} 를 쓰지 않는 이유가 성능만은 아니다. 전 컬럼을 읽으면
     * {@code NAME} 처럼 <b>운송사에 넘기지 않기로 한 개인정보</b>가 애플리케이션 메모리를
     * 통과하게 된다. 필요 없는 것은 애초에 가져오지 않는 것이 가장 강한 통제다.
     */
    private static final String SELECT_FIRST_TEMPLATE = """
            SELECT ORDER_ID, ITEM_ID, ADDRESS
              FROM %s
             WHERE APPLICANT_KEY = ?
               AND STATUS = ?
             ORDER BY ORDER_ID
             FETCH FIRST ? ROWS ONLY""";

    /** 두 번째 청크부터. {@code ORDER_ID > :cursor} 로 이미 읽은 구간을 지나친다. */
    private static final String SELECT_AFTER_TEMPLATE = """
            SELECT ORDER_ID, ITEM_ID, ADDRESS
              FROM %s
             WHERE APPLICANT_KEY = ?
               AND STATUS = ?
               AND ORDER_ID > ?
             ORDER BY ORDER_ID
             FETCH FIRST ? ROWS ONLY""";

    /** 값을 손보지 않는다. trim 도 하지 않는다 — 우리가 넣은 값을 우리가 다시 읽는 것이다. */
    private static final RowMapper<PendingOrder> ROW_MAPPER = (rs, rowNum) -> new PendingOrder(
            rs.getString(1),
            rs.getString(2),
            rs.getString(3));

    private final JdbcTemplate jdbcTemplate;
    private final ApplicantKey applicantKey;
    private final String table;
    private final String selectFirstSql;
    private final String selectAfterSql;
    private final int chunkSize;

    public JdbcPollingSender(JdbcTemplate jdbcTemplate,
                             ApplicantKey applicantKey,
                             String table,
                             int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("청크 크기는 양수여야 한다: " + chunkSize);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.applicantKey = applicantKey;
        this.table = SqlIdentifiers.requireSafe(table, "테이블명");
        this.selectFirstSql = SELECT_FIRST_TEMPLATE.formatted(this.table);
        this.selectAfterSql = SELECT_AFTER_TEMPLATE.formatted(this.table);
        this.chunkSize = chunkSize;
    }

    @Override
    public InterfaceId ifId() {
        return InterfaceId.IF_SHP_001;
    }

    @Override
    public CanonicalMessage<ShipmentSourceMessage> receive(MessageHeader header, PollCursor cursor) {
        PollCursor effective = (cursor == null) ? PollCursor.first() : cursor;

        List<PendingOrder> rows;
        try {
            rows = effective.fromBeginning()
                    ? jdbcTemplate.query(selectFirstSql, ROW_MAPPER,
                            applicantKey.value(), OrderStatus.UNSENT.code(), chunkSize)
                    : jdbcTemplate.query(selectAfterSql, ROW_MAPPER,
                            applicantKey.value(), OrderStatus.UNSENT.code(),
                            effective.afterOrderId(), chunkSize);

        } catch (DataAccessException e) {
            throw translate(e);
        }

        // 건수와 커서만 남긴다. ORDER_ID 목록을 실으면 청크마다 로그가 100줄이 되고,
        // 정작 필요한 정보(몇 건 왔는가)가 묻힌다. 개별 행은 문제가 있을 때만 지목한다.
        log.debug("[{}] 미전송 주문 {}건 조회 (요청 {}건, cursor>{}, {})",
                table, rows.size(), chunkSize,
                effective.fromBeginning() ? "처음부터" : effective.afterOrderId(),
                applicantKey);

        return new CanonicalMessage<>(header, new ShipmentSourceMessage(rows, chunkSize));
    }

    /**
     * {@link DataAccessException} → 엔진 예외.
     *
     * <p>{@link JdbcTemplate} 은 {@link SQLException} 을 스프링 예외로 감싸므로,
     * 재시도 여부 판정을 한 곳에 모아 둔 {@link JdbcErrorTranslator} 에 넘기려면
     * 원래의 {@code SQLException} 을 다시 꺼내야 한다. 판정 규칙을 여기서 따로 만들면
     * "일시 단절을 영구 실패로 처리" 하는 기준 분산이 시작된다.
     */
    private RuntimeException translate(DataAccessException e) {
        String context = "[" + table + "] 미전송 주문 조회 실패";

        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLException sql) {
            return JdbcErrorTranslator.translate(context, sql);
        }
        // SQLException 이 아닌 원인(RowMapper 내부 오류 등)은 데이터·코드 문제이므로 재시도 대상이 아니다.
        return new NonRetryableException(EaiErrorCode.JDBC_EXEC_ERROR,
                context + " — " + e.getClass().getSimpleName(), e);
    }
}
