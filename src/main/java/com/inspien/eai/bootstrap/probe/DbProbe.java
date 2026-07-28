package com.inspien.eai.bootstrap.probe;

import com.inspien.eai.common.secret.SecretsLoader;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * BOOT-001 ① — 적재 대상 DB 사전 점검.
 *
 * <p>연계 개발에 들어가기 전에 <b>수신 시스템의 실제 스펙</b>을 확인한다.
 * 명세서가 아니라 실물이 기준이라는 게 EAI 의 원칙이고, 앞선 BOOT-000 에서
 * 이미 "추정으로 짠 코드가 실물과 어긋난" 경험을 했다.
 *
 * <p>확인 항목
 * <ul>
 *   <li>캐릭터셋 / 길이 의미론 — 한글 적재 가능 여부, VARCHAR2 길이가 BYTE 인지 CHAR 인지</li>
 *   <li>컬럼 정의 — 타입·길이·NULL 허용·<b>DEFAULT</b> → 유효성 검증(V-06)과 적재 컬럼 결정</li>
 *   <li>PK 구성 — 복합 PK 여부, 멱등성 설계의 전제</li>
 *   <li>기존 행 수 — 재실행 시 중복 적재 판단의 출발점</li>
 * </ul>
 */
@Slf4j
public class DbProbe {

    private static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    /** 접속이 매달리지 않도록 명시한다. 기본값 무한 대기가 실제 장애의 주범이다. */
    private static final String CONNECT_TIMEOUT_MS = "10000";
    private static final String READ_TIMEOUT_MS = "20000";

    private static final String NLS_QUERY = """
            SELECT parameter, value FROM nls_database_parameters
             WHERE parameter IN ('NLS_CHARACTERSET','NLS_NCHAR_CHARACTERSET','NLS_LENGTH_SEMANTICS')
            """;

    // data_default 는 LONG 타입이라 SELECT 목록의 마지막에 두고 마지막에 읽는다.
    private static final String COLUMN_QUERY = """
            SELECT owner, column_id, column_name, data_type, data_length, char_used, nullable, data_default
              FROM all_tab_columns
             WHERE table_name = ?
             ORDER BY owner, column_id
            """;

    /**
     * CREATE_TIME / CREATE_DATE 의 DEFAULT 가 SYSDATE 이므로 등록 시각은 DB 서버 시계로 찍힌다.
     * 면접 시연의 "당일 날짜 기준 조회" 가 우리 로컬 날짜와 어긋날 수 있어 미리 확인한다.
     */
    private static final String TIME_QUERY = """
            SELECT TO_CHAR(SYSDATE,'YYYY-MM-DD HH24:MI:SS'),
                   TO_CHAR(SYSTIMESTAMP,'YYYY-MM-DD HH24:MI:SS TZH:TZM'),
                   DBTIMEZONE,
                   SESSIONTIMEZONE
              FROM dual
            """;

    private static final String PK_QUERY = """
            SELECT c.constraint_name, cc.column_name, cc.position
              FROM all_constraints c
              JOIN all_cons_columns cc
                ON c.owner = cc.owner AND c.constraint_name = cc.constraint_name
             WHERE c.table_name = ? AND c.constraint_type = 'P'
             ORDER BY cc.position
            """;

    public void probe(Properties conn, SecretsLoader loader, String source, String applicantKey) {
        String url = loader.require(conn, "URL", source);
        String table = loader.require(conn, "TABLE", source);

        Properties info = new Properties();
        info.put("user", loader.require(conn, "ID", source));
        info.put("password", loader.require(conn, "PASSWORD", source));
        info.put("oracle.net.CONNECT_TIMEOUT", CONNECT_TIMEOUT_MS);
        info.put("oracle.jdbc.ReadTimeout", READ_TIMEOUT_MS);

        log.info("[BOOT-001] {} 접속 시도 → {}", source, maskUrl(url));

        try (Connection c = DriverManager.getConnection(url, info)) {
            DatabaseMetaData meta = c.getMetaData();
            log.info("[BOOT-001] 접속 성공 — {} / driver {}",
                    meta.getDatabaseProductVersion().lines().findFirst().orElse("?"), meta.getDriverVersion());

            reportNls(c);
            reportColumns(c, table);
            reportPrimaryKey(c, table);
            reportRowCount(c, table, applicantKey);

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "[BOOT-001] " + source + " 접속/조회 실패 (SQLState=" + e.getSQLState()
                            + ", ErrorCode=" + e.getErrorCode() + "): " + e.getMessage(), e);
        }
    }

    private void reportNls(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder("\n  ── 캐릭터셋 / 길이 의미론 ──\n");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(NLS_QUERY)) {
            while (rs.next()) {
                sb.append("    ").append(String.format("%-24s", rs.getString(1)))
                  .append(" = ").append(rs.getString(2)).append('\n');
            }
        }
        sb.append("    → BYTE 의미론이면 유효성 검증은 문자 수가 아니라 UTF-8 바이트 길이로 해야 한다.\n");

        sb.append("\n  ── 서버 시각 (등록일시는 SYSDATE 로 찍힌다) ──\n");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(TIME_QUERY)) {
            if (rs.next()) {
                sb.append("    SYSDATE         = ").append(rs.getString(1)).append('\n');
                sb.append("    SYSTIMESTAMP    = ").append(rs.getString(2)).append('\n');
                sb.append("    DBTIMEZONE      = ").append(rs.getString(3)).append('\n');
                sb.append("    SESSIONTIMEZONE = ").append(rs.getString(4)).append('\n');
            }
        }
        sb.append("    로컬 시각        = ")
          .append(ZonedDateTime.now().format(LOCAL_TIME_FORMAT)).append('\n');
        sb.append("    → 날짜가 서로 다르면 TRUNC(CREATE_TIME)=TRUNC(SYSDATE) 조회가 시연 당일과 어긋난다.");
        log.info(sb.toString());
    }

    private void reportColumns(Connection c, String table) throws SQLException {
        StringBuilder sb = new StringBuilder("\n  ── " + table + " 컬럼 정의 ──\n");
        sb.append(String.format("    %-9s %-3s %-15s %-13s %-8s %-5s %-6s %s%n",
                "OWNER", "#", "COLUMN", "TYPE", "DATA_LEN", "CHAR", "NULL?", "DEFAULT"));
        boolean found = false;
        try (PreparedStatement ps = c.prepareStatement(COLUMN_QUERY)) {
            ps.setString(1, table.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found = true;
                    sb.append(String.format("    %-9s %-3d %-15s %-13s %-8d %-5s %-6s %s%n",
                            rs.getString("owner"),
                            rs.getInt("column_id"),
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            rs.getInt("data_length"),
                            nullSafe(rs.getString("char_used")),
                            rs.getString("nullable"),
                            readDefault(rs)));
                }
            }
        }
        if (!found) {
            sb.append("    (조회 결과 없음 — 테이블명 또는 권한 확인 필요)\n");
        }
        sb.append("    DEFAULT 가 있는 컬럼은 INSERT 목록에서 제외한다. 없다면 직접 채워야 날짜 기준 조회(시연)가 가능하다.");
        log.info(sb.toString());
    }

    /** all_tab_columns.data_default 는 LONG 이라 드라이버에 따라 읽기가 실패할 수 있다. */
    private String readDefault(ResultSet rs) {
        try {
            String value = rs.getString("data_default");
            return (value == null || value.isBlank()) ? "-" : value.trim();
        } catch (SQLException e) {
            return "(읽기 실패)";
        }
    }

    private void reportPrimaryKey(Connection c, String table) throws SQLException {
        StringBuilder sb = new StringBuilder("\n  ── " + table + " PK ──\n");
        boolean found = false;
        try (PreparedStatement ps = c.prepareStatement(PK_QUERY)) {
            ps.setString(1, table.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found = true;
                    sb.append("    ").append(rs.getInt("position")).append(". ")
                      .append(rs.getString("column_name"))
                      .append("   (").append(rs.getString("constraint_name")).append(")\n");
                }
            }
        }
        if (!found) {
            sb.append("    (PK 제약 없음 — 중복 적재 방지를 애플리케이션이 전담해야 한다)\n");
        }
        log.info(sb.toString());
    }

    private void reportRowCount(Connection c, String table, String applicantKey) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE APPLICANT_KEY = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, applicantKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    log.info("\n  ── {} 내 APPLICANT_KEY 기존 행 수 : {} ──", table, rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            log.warn("\n  ── {} 행 수 조회 실패: {} ──", table, e.getMessage());
        }
    }

    private String nullSafe(String value) {
        return value == null ? "-" : value;
    }

    /** 호스트·SID 는 크리덴셜에 준해 다룬다. 스킴만 드러낸다. */
    private String maskUrl(String url) {
        int at = url.indexOf('@');
        return at > 0 ? url.substring(0, at + 1) + "…(총 " + url.length() + "자)" : "…(총 " + url.length() + "자)";
    }
}
