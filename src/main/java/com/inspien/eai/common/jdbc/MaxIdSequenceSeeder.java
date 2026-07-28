package com.inspien.eai.common.jdbc;

import com.inspien.eai.common.id.SequentialIdGenerator;
import com.inspien.eai.common.secret.ApplicantKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기동 시 채번 카운터 복원 — D-09 의 나머지 절반.
 *
 * <p>{@link SequentialIdGenerator#seedFrom(String)} 은 "마지막으로 쓴 식별자" 를 받아
 * 카운터를 끌어올리는 기능만 갖고 있다. 그 값을 <b>어디서 가져올 것인가</b>가 여기의 책임이다.
 *
 * <pre>
 *   SELECT MAX(ID컬럼) FROM 테이블 WHERE APPLICANT_KEY = ?
 * </pre>
 *
 * <h2>이것이 있어야 Redis 가 "진실의 원천" 이 아니게 된다</h2>
 * 없으면 {@code docker compose down -v} 한 번에 카운터가 0으로 돌아가고,
 * 다음 요청은 이미 적재된 {@code A000} 을 다시 발급해 PK 위반으로 죽는다.
 * 시딩이 있으면 진실은 <b>이미 적재된 데이터</b>에 있고 Redis 는 그 다음 번호를 원자적으로
 * 나눠 주는 조정 계층이 된다. 읽기 전용 조회 하나로 끝나므로 대상 스키마를 건드리지 않는다 —
 * 시퀀스 테이블을 만드는 폴백을 철회한 이유가 이것이다.
 *
 * <h2>{@code MAX()} 가 통하는 이유</h2>
 * 식별자 형식이 {@code [A-Z][0-9]{3}} 이고 숫자부가 <b>고정 3자리 제로패딩</b>이라
 * 사전식 정렬 순서와 채번 순서가 일치한다({@code A000 < A999 < B000 < Z999}).
 * 값이 전부 ASCII 이므로 DB 의 정렬 방식(NLS_SORT)에 좌우되지도 않는다.
 * 패딩을 생략한 형식이었다면 {@code A9 > A10} 이 되어 이 방법 자체가 성립하지 않는다.
 *
 * <h2>실패하면 기동을 멈춘다</h2>
 * 조회 실패나 규격 밖 식별자 발견은 경고로 넘기지 않고 기동 실패로 처리한다.
 * 카운터를 복원하지 못한 채 뜨면 <b>첫 요청부터 PK 를 위반</b>하기 때문이다.
 * "일단 떴다가 요청이 오면 실패" 보다 "아예 뜨지 않음" 이 발견이 빠르고 피해가 작다.
 */
@Slf4j
public class MaxIdSequenceSeeder {

    private final JdbcTemplate jdbcTemplate;
    private final SequentialIdGenerator generator;
    private final ApplicantKey applicantKey;
    private final String table;
    private final String idColumn;

    public MaxIdSequenceSeeder(JdbcTemplate jdbcTemplate,
                               SequentialIdGenerator generator,
                               ApplicantKey applicantKey,
                               String table,
                               String idColumn) {
        this.jdbcTemplate = jdbcTemplate;
        this.generator = generator;
        this.applicantKey = applicantKey;
        this.table = SqlIdentifiers.requireSafe(table, "테이블명");
        this.idColumn = SqlIdentifiers.requireSafe(idColumn, "컬럼명");
    }

    /**
     * 카운터를 복원한다.
     *
     * <p>빈 초기화 메서드로 등록해 <b>컨텍스트 갱신 중</b>에 실행한다
     * ({@code @Bean(initMethod = "seed")}). {@code ApplicationRunner} 로 두지 않은 것은
     * 러너가 <b>웹 서버가 요청을 받기 시작한 뒤</b>에 돌기 때문이다. 그 사이에 요청이 들어오면
     * 시딩 전 카운터로 채번된다. 이 메서드는 트래픽 유입 전 1회 실행이 전제이며
     * ({@link com.inspien.eai.common.id.IdSequence#seedAtLeast} 는 원자적이지 않다),
     * 그 전제를 지키는 유일한 자리가 초기화 시점이다.
     */
    public void seed() {
        String sql = "SELECT MAX(" + idColumn + ") FROM " + table + " WHERE APPLICANT_KEY = ?";

        String lastIssued;
        try {
            // queryForObject 를 쓰지 않는다. 결과가 NULL 인 경우(적재 이력 없음)가 정상인데
            // queryForObject 는 그것과 "행이 없음" 을 구분해 주지 않는다.
            lastIssued = jdbcTemplate.query(sql,
                    rs -> rs.next() ? rs.getString(1) : null,
                    applicantKey.value());
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "[D-09] " + table + "." + idColumn + " 최대값 조회에 실패했다. "
                            + "채번 카운터를 복원하지 못한 채로는 기동할 수 없다 — "
                            + "첫 요청부터 이미 적재된 번호와 충돌한다", e);
        }

        if (lastIssued == null || lastIssued.isBlank()) {
            log.info("[D-09] {} 적재 이력 없음 ({}) — 카운터를 그대로 둔다",
                    table, applicantKey);
            return;
        }

        try {
            generator.seedFrom(lastIssued);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "[D-09] " + table + "." + idColumn + " 의 최대값이 채번 규격에 맞지 않는다. "
                            + "우리가 모르는 경로로 들어온 데이터가 섞여 있다는 뜻이며, "
                            + "그대로 이어 채번하면 충돌한다", e);
        }

        log.info("[D-09] {} 채번 카운터 복원 — {}.{} 최대값 {} 다음부터 발급 ({})",
                generator.key(), table, idColumn, lastIssued, applicantKey);
    }
}
