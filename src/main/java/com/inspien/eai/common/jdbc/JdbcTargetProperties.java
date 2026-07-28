package com.inspien.eai.common.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 적재 대상 DB 접속 설정.
 *
 * <p><b>접속정보(URL·계정·비밀번호)는 여기 없다.</b> 그것들은 BOOT-000 이 복호화해
 * {@code secrets/} 에 떨어뜨린 런타임 산출물이고, 이 레코드가 다루는 것은
 * 우리가 정하는 <b>운영 파라미터</b>다. 둘을 한 곳에 섞으면 크리덴셜이 설정 파일로
 * 새어 나가는 경로가 생긴다.
 *
 * <h2>타임아웃을 전부 명시하는 이유 (정의서 5.2)</h2>
 * JDBC 의 기본값은 대부분 <b>무한 대기</b>다. 상대 시스템이 죽는 것보다
 * <b>응답하지 않는 것</b>이 위험한 이유가 여기 있다 — 실패는 즉시 드러나지만,
 * 무한 대기는 스레드를 하나씩 잡아먹다가 전체 서비스를 멈춘다.
 * 실시간 SYNC 응답 경로 위에 있는 인터페이스에서는 특히 그렇다.
 *
 * @param connectTimeout  {@code oracle.net.CONNECT_TIMEOUT} — TCP 연결 수립 한도
 * @param readTimeout     {@code oracle.jdbc.ReadTimeout} — 소켓 응답 대기 한도
 * @param queryTimeout    {@code Statement.setQueryTimeout} — 문장 하나의 수행 한도.
 *                        소켓이 살아 있어도 서버가 오래 붙잡고 있으면 이쪽이 끊는다
 * @param poolWaitTimeout HikariCP {@code connectionTimeout} — 풀에서 커넥션을 빌리기까지의 한도.
 *                        <b>{@code connectTimeout} 보다 길어야 한다.</b> 풀이 비어 새 커넥션을
 *                        만드는 중이라면 이 대기에 드라이버의 접속 시간이 포함되므로,
 *                        더 짧게 잡으면 드라이버가 제대로 된 원인을 알려 주기 전에 풀이 먼저 끊는다
 * @param maximumPoolSize 동시 커넥션 상한. 실시간 1건 + 배치 1건이 전부인 단일 인스턴스이므로 작게 잡는다.
 *                        다만 보상 트랜잭션 구조상 <b>커넥션이 FTP 업로드 시간만큼 점유</b>되므로
 *                        1로 두면 배치와 실시간이 서로를 기다린다
 * @param batchSize       한 번의 {@code executeBatch} 에 실을 최대 행 수. 샘플은 63행이라 한 번에 끝나지만,
 *                        건수가 늘어도 드라이버 버퍼가 무한정 커지지 않도록 상한을 둔다
 */
@ConfigurationProperties(prefix = "inspien.jdbc")
public record JdbcTargetProperties(
        Duration connectTimeout,
        Duration readTimeout,
        Duration queryTimeout,
        Duration poolWaitTimeout,
        int maximumPoolSize,
        int batchSize
) {

    public JdbcTargetProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(20);
        }
        if (queryTimeout == null) {
            queryTimeout = Duration.ofSeconds(20);
        }
        if (poolWaitTimeout == null) {
            poolWaitTimeout = Duration.ofSeconds(15);
        }
        if (maximumPoolSize <= 0) {
            maximumPoolSize = 5;
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
        if (poolWaitTimeout.compareTo(connectTimeout) < 0) {
            // 조용히 보정하지 않고 실패시킨다. 보정하면 설정 파일에 적힌 값과
            // 실제 동작이 달라지고, 장애 분석 때 설정을 믿을 수 없게 된다.
            throw new IllegalArgumentException(
                    "inspien.jdbc.pool-wait-timeout(" + poolWaitTimeout + ") 은 "
                            + "connect-timeout(" + connectTimeout + ") 보다 길어야 한다. "
                            + "짧으면 드라이버가 접속 실패 원인을 알려 주기 전에 풀이 먼저 끊는다");
        }
    }

    /** {@code Statement.setQueryTimeout} 은 초 단위 {@code int} 다. 0 은 '무제한' 이므로 최소 1초로 올린다. */
    public int queryTimeoutSeconds() {
        return (int) Math.max(1L, queryTimeout.toSeconds());
    }
}
