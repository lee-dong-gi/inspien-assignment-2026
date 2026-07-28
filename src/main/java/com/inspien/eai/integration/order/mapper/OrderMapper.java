package com.inspien.eai.integration.order.mapper;

import com.inspien.eai.common.id.IdGenerator;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.exception.NonRetryableException;
import com.inspien.eai.engine.mapper.Mapper;
import com.inspien.eai.integration.order.source.OrderSourceMessage;
import com.inspien.eai.integration.order.source.SourceHeader;
import com.inspien.eai.integration.order.source.SourceItem;
import com.inspien.eai.integration.order.target.OrderRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IF-ORD-001 매퍼 — 계층 XML(HEADER 1 : ITEM N)을 평탄한 주문 행 목록으로 변환한다.
 *
 * <p>이 클래스가 EAI 의 본체다. 송신 시스템은 주문자와 품목을 <b>나눠서</b> 말하고,
 * 수신 시스템(주문 테이블 · 영수증 파일)은 한 줄에 <b>합쳐서</b> 듣는다.
 * 어느 쪽도 상대에 맞춰 바뀌지 않으며, 그 간극을 흡수하는 것이 여기의 유일한 책임이다.
 *
 * <h2>변환 규칙 (정의서 3.5 = 인터페이스 정의서의 매핑 표)</h2>
 * <table border="1">
 *   <caption>ORDER_TB 매핑</caption>
 *   <tr><th>타깃</th><th>소스</th><th>규칙</th></tr>
 *   <tr><td>ORDER_ID</td><td>—</td><td>자체 채번. <b>행마다 1개</b></td></tr>
 *   <tr><td>APPLICANT_KEY</td><td>BOOT-000</td><td>전 행 고정값</td></tr>
 *   <tr><td>USER_ID</td><td>HEADER</td><td>원본 유지</td></tr>
 *   <tr><td>ITEM_ID</td><td>ITEM</td><td>원본 유지</td></tr>
 *   <tr><td>NAME / ADDRESS</td><td>HEADER</td><td>원본 유지 (내부 공백 보존)</td></tr>
 *   <tr><td>ITEM_NAME / PRICE</td><td>ITEM</td><td>원본 유지. PRICE 는 문자열 그대로</td></tr>
 *   <tr><td>STATUS</td><td>—</td><td>{@code N} 로 정규화 (D-01)</td></tr>
 * </table>
 *
 * <h2>채번은 여기서, 단 한 번</h2>
 * 매핑 실행당 {@link IdGenerator#allocate(int)} 를 정확히 한 번 호출해 전량을 선점한다.
 * 반환된 리스트를 JDBC · FTP 두 Receiver 가 <b>공유</b>하므로 DB 행과 영수증 라인의
 * {@code ORDER_ID} 는 구조적으로 같을 수밖에 없다. Receiver 가 각자 채번하는 설계였다면
 * 두 시스템의 데이터를 서로 대조할 방법이 사라진다.
 *
 * <h2>전제</h2>
 * 입력은 <b>검증을 통과한</b> {@link OrderSourceMessage} 다. 고아 ITEM 과 짝 없는 HEADER 는
 * Validator 가 이미 걷어냈다. 여기서 다시 걸러 내면 "몇 건이 왜 빠졌는가" 를 집계하는 지점이
 * 두 곳으로 흩어지고, 결과 보고에서 스킵 건수가 어긋난다.
 */
public class OrderMapper implements Mapper<OrderSourceMessage, OrderRecord> {

    /**
     * 적재 시점의 상태값 (D-01).
     *
     * <p>소스의 {@code STATUS} 를 그대로 쓰지 않고 {@code N} 으로 고정한다.
     * 배치(IF-SHP-001)의 조회 조건이 {@code STATUS = 'N'} 이므로, 송신 측이 다른 값을 실어 보내면
     * 그 주문은 <b>영원히 운송사로 전달되지 않는다.</b> 값을 존중하는 편이 원칙적이지만,
     * 여기서는 조용히 누락되는 쪽의 피해가 명백히 크다.
     *
     * <p>샘플 15건은 모두 {@code N} 이라 실측상 차이는 없다. 그래도 명시적으로 덮어쓰는 이유는
     * "우연히 맞았다" 와 "보장했다" 가 다르기 때문이다.
     */
    public static final String STATUS_UNSENT = "N";

    private final IdGenerator idGenerator;
    private final String applicantKey;

    public OrderMapper(IdGenerator idGenerator, String applicantKey) {
        if (applicantKey == null || applicantKey.isBlank()) {
            // 이 값이 비면 전 행이 잘못된 PK 로 적재되고, 배치 조회에서도 잡히지 않는다.
            // 늦게 발견될수록 되돌리기 어려우므로 조립 시점에 끊는다.
            throw new IllegalArgumentException("APPLICANT_KEY 없이는 매핑할 수 없다. BOOT-000 산출물을 확인할 것");
        }
        this.idGenerator = idGenerator;
        this.applicantKey = applicantKey;
    }

    @Override
    public List<OrderRecord> map(OrderSourceMessage source) {
        List<SourceHeader> headers = source.headers();
        requireUniqueJoinKeys(headers);

        Map<String, List<SourceItem>> itemsByUser = groupItemsByUser(source.items());

        int rows = countRows(headers, itemsByUser);
        if (rows == 0) {
            // 채번을 호출하지 않는다. 0건 요청에 번호를 태울 이유가 없다.
            return List.of();
        }

        // 전량 선점. 중간에 실패하면 한 건도 만들어지지 않는다.
        List<String> orderIds = idGenerator.allocate(rows);

        List<OrderRecord> records = new ArrayList<>(rows);
        int cursor = 0;
        for (SourceHeader header : headers) {
            for (SourceItem item : itemsByUser.getOrDefault(header.userId(), List.of())) {
                records.add(new OrderRecord(
                        orderIds.get(cursor++),
                        applicantKey,
                        header.userId(),
                        item.itemId(),
                        header.name(),
                        header.address(),
                        item.itemName(),
                        item.price(),
                        STATUS_UNSENT));
            }
        }
        return List.copyOf(records);
    }

    /**
     * 조인 키 중복 검사.
     *
     * <p>같은 {@code USER_ID} 를 가진 HEADER 가 둘이면 어느 쪽 이름·주소를 쓸지 정할 근거가 없고,
     * 그대로 두면 <b>같은 ITEM 이 두 번 적재</b>된다. 실측 샘플에는 중복이 없지만
     * (정의서 3.2: HEADER USER_ID 중복 0건) 그것은 이번 데이터가 그랬다는 뜻일 뿐이다.
     *
     * <p>임의로 한쪽을 고르거나 조용히 건너뛰지 않고 실패시킨다. 중복 적재는 실패보다 발견이 늦다.
     */
    private void requireUniqueJoinKeys(List<SourceHeader> headers) {
        Set<String> seen = new HashSet<>();
        for (SourceHeader header : headers) {
            if (!seen.add(header.userId())) {
                // 값 자체는 담지 않는다. 위치만으로 원본을 찾아갈 수 있다.
                throw new NonRetryableException(EaiErrorCode.MAPPING_ERROR,
                        "HEADER 의 USER_ID 가 중복이라 조인이 모호하다 (HEADER[" + header.sequence() + "])");
            }
        }
    }

    /**
     * {@code USER_ID} 로 ITEM 을 묶는다.
     *
     * <p><b>문서 순서로 짝짓지 않는다.</b> 실측 샘플에서 ITEM 은 HEADER 순서대로 정렬돼 있지 않다
     * (정의서 3.2). 순서에 기대는 조인은 이 데이터에서 즉시 깨진다.
     *
     * <p>그룹 내부의 ITEM 순서는 <b>문서 등장 순서를 유지</b>한다. 그래야 같은 입력에 대해
     * 같은 {@code ORDER_ID} 배치가 나오고, 실행 결과를 재현·대조할 수 있다.
     */
    private Map<String, List<SourceItem>> groupItemsByUser(List<SourceItem> items) {
        // Collectors.groupingBy 를 쓰지 않는 이유: 키가 null 이면 NPE 로 터진다.
        // 검증을 통과했다면 null 은 없지만, 매퍼가 검증기의 완벽함을 전제로 깨질 필요는 없다.
        Map<String, List<SourceItem>> grouped = new LinkedHashMap<>();
        for (SourceItem item : items) {
            grouped.computeIfAbsent(item.userId(), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /**
     * 생성될 행 수를 <b>미리</b> 센다.
     *
     * <p>채번을 한 번에 하기 위해 필요한 값이다. 레코드를 만들면서 하나씩 발급하면
     * 왕복이 행 수만큼 늘어나고, 중간 실패 시 절반만 번호가 붙은 상태가 남는다.
     */
    private int countRows(List<SourceHeader> headers, Map<String, List<SourceItem>> itemsByUser) {
        // getOrDefault 의 기본값 조회를 반복하지 않도록 HashMap 조회 결과를 그대로 쓴다.
        Map<String, Integer> sizes = new HashMap<>();
        itemsByUser.forEach((userId, list) -> sizes.put(userId, list.size()));

        int rows = 0;
        for (SourceHeader header : headers) {
            rows += sizes.getOrDefault(header.userId(), 0);
        }
        return rows;
    }
}
