package com.inspien.eai.engine.log;

/**
 * 인터페이스 실행 구간.
 *
 * <p>운영 로그의 단위다. "실패했다" 만으로는 아무것도 못 하고,
 * <b>어느 구간에서</b> 실패했는지가 있어야 담당 시스템을 특정할 수 있다.
 * MAPPER 실패는 우리 책임이고, RECEIVER_JDBC 실패는 DB 담당자와 이야기할 일이다.
 *
 * <h2>구간은 프로토콜 단위로 나눈다 — 도메인 단위가 아니다</h2>
 * {@code RECEIVER_JDBC} / {@code RECEIVER_FTP} 이지 {@code RECEIVER_ORDER_TB} 가 아니다.
 * 운영자가 알아야 하는 것은 "어느 종류의 상대 시스템에 연락할 것인가" 이고,
 * 그것은 테이블 이름이 아니라 프로토콜로 결정된다. 덕분에 이 열거형은 인터페이스가
 * 늘어나도 커지지 않는다 — IF-SHP-001 을 추가하면서 구간은 하나도 늘지 않았다.
 *
 * <h2>{@code STATUS_UPDATE} 를 두지 않는다 (D-24)</h2>
 * IF-SHP-001 의 후행 상태 갱신({@code ORDER_TB.STATUS='Y'})을 별도 구간으로 남길 수도 있으나
 * 두지 않았다. 그 갱신은 {@code SHIPMENT_TB} INSERT 와 <b>동일 트랜잭션</b>이므로
 * "적재는 됐는데 갱신은 안 됐다" 는 상태가 <b>구조적으로 존재할 수 없다.</b>
 * 그런데도 구간을 나누면 로그는 <b>존재하지 않는 상태를 표현 가능한 것처럼</b> 보이게 만들고,
 * 운영자는 "RECEIVER_JDBC 는 SUCCESS 인데 STATUS_UPDATE 줄이 없는" 경우를 찾아다니게 된다.
 *
 * <p>건수를 잃지도 않는다. 두 문장이 <b>같은 레코드 리스트</b>를 소비하므로
 * 적재 건수와 갱신 건수는 <b>항상 같고</b>, 그 값은 {@code RECEIVER_JDBC} 구간의
 * {@code COUNT} 열에 이미 있다. 두 열로 나눠 적으면 "다를 수도 있다" 는
 * 잘못된 기대만 만든다. 문장별 내역이 필요한 진단은 애플리케이션 로그가 담당한다.
 */
public enum Step {

    /** 송신 시스템 수신 */
    SENDER,
    /** 유효성 검증 */
    VALIDATOR,
    /** 구조 변환 */
    MAPPER,
    /** DB 적재 — IF-ORD-001 은 ORDER_TB, IF-SHP-001 은 SHIPMENT_TB + 상태 갱신 */
    RECEIVER_JDBC,
    /** FTP 전송 */
    RECEIVER_FTP
}
