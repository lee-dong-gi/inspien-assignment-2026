package com.inspien.eai.common.jdbc;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 식별자(테이블·컬럼명) 검증.
 *
 * <p><b>왜 필요한가.</b> 값은 전부 파라미터 바인딩({@code ?})으로 넣지만,
 * <b>테이블명은 바인딩할 수 없다.</b> JDBC 의 바인딩은 값의 자리에만 쓸 수 있고
 * 식별자 자리에는 쓸 수 없기 때문이다. 그래서 테이블명만은 문자열로 조립되며,
 * 이 프로젝트에서 유일하게 SQL 에 직접 끼워 넣는 값이 된다.
 *
 * <p>그 값의 출처는 사용자 입력이 아니라 BOOT-000 산출물({@code TABLE=ORDER_TB})이므로
 * 실질적 위험은 낮다. 그럼에도 검증을 두는 이유는 <b>"출처가 안전하니 괜찮다" 는 판단이
 * 코드에 남지 않기 때문</b>이다. 나중에 이 메서드를 다른 곳에서 재사용하는 사람은
 * 원래 출처가 무엇이었는지 알 수 없다. 검증은 그 맥락을 코드로 고정해 둔다.
 *
 * <p>Oracle 의 비따옴표(unquoted) 식별자 규격을 따른다 — 첫 글자는 문자,
 * 이후 문자·숫자·{@code _ $ #}, 최대 30자.
 */
public final class SqlIdentifiers {

    private static final Pattern UNQUOTED = Pattern.compile("^[A-Z][A-Z0-9_$#]{0,29}$");

    private SqlIdentifiers() {
    }

    /**
     * 식별자를 대문자로 정규화해 돌려준다. 규격 밖이면 실패시킨다.
     *
     * @param role 실패 메시지에 쓸 역할 이름 (예: {@code "테이블명"})
     */
    public static String requireSafe(String identifier, String role) {
        String normalized = identifier == null ? "" : identifier.trim().toUpperCase(Locale.ROOT);
        if (!UNQUOTED.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "SQL 식별자로 쓸 수 없는 " + role + " 이다: '" + identifier + "'. "
                            + "기대 형식 [A-Z][A-Z0-9_$#]{0,29}");
        }
        return normalized;
    }
}
