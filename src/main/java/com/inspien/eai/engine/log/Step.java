package com.inspien.eai.engine.log;

/**
 * 인터페이스 실행 구간.
 *
 * <p>운영 로그의 단위다. "실패했다" 만으로는 아무것도 못 하고,
 * <b>어느 구간에서</b> 실패했는지가 있어야 담당 시스템을 특정할 수 있다.
 * MAPPER 실패는 우리 책임이고, RECEIVER_JDBC 실패는 DB 담당자와 이야기할 일이다.
 */
public enum Step {

    /** 송신 시스템 수신 */
    SENDER,
    /** 유효성 검증 */
    VALIDATOR,
    /** 구조 변환 */
    MAPPER,
    /** DB 적재 */
    RECEIVER_JDBC,
    /** FTP 전송 */
    RECEIVER_FTP,
    /** 배치 후행 상태 갱신 */
    STATUS_UPDATE
}
