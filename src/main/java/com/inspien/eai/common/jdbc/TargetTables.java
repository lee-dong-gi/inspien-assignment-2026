package com.inspien.eai.common.jdbc;

import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import com.inspien.eai.common.secret.SecretsLoader;

import java.util.Properties;

/**
 * 적재 대상 테이블명 — BOOT-000 산출물에서 한 번만 읽는다.
 *
 * <h2>왜 상수로 박지 않는가</h2>
 * 대상 스키마는 우리가 정한 것이 아니라 <b>과제 측이 알려 준 것</b>이다
 * ({@code ORDER_TB_CONN} 의 {@code TABLE} 필드). 알려 준 경로에서 읽는 것이 맞고,
 * 코드에 박아 두면 두 곳이 어긋날 수 있는데 그때 어느 쪽이 진실인지 판단할 근거가 없다.
 *
 * <h2>왜 타입으로 모으는가</h2>
 * 이 값을 필요로 하는 자리가 넷이다 — 주문 적재, 채번 시딩(주문), 배송 적재 + 상태 갱신,
 * 채번 시딩(배송). 각 config 가 {@code secrets/} 를 직접 읽으면 같은 파싱 코드가 넷이 되고,
 * 그중 하나만 다른 키를 보게 되는 날이 온다.
 *
 * <p>더 중요한 것은 <b>두 이름을 함께 들고 있다</b>는 점이다. {@code ShipmentTbReceiver} 는
 * {@code SHIPMENT_TB} 에 INSERT 하면서 {@code ORDER_TB} 를 UPDATE 한다 — 두 이름이
 * 서로 다른 경로로 들어오면 <b>엉뚱한 테이블을 갱신하는</b> 조립 실수가 가능해진다.
 *
 * <h2>검증을 여기서 한 번 더 한다</h2>
 * 테이블명은 이 프로젝트에서 <b>유일하게 SQL 에 문자열로 조립되는 값</b>이다
 * (JDBC 바인딩은 값 자리에만 쓸 수 있다). 출처가 BOOT-000 산출물이라 실질적 위험은 낮지만,
 * 검증을 두면 "출처가 안전하다" 는 판단이 코드에 남는다 — {@link SqlIdentifiers} 참조.
 *
 * @param orderTable    IF-ORD-001 의 적재 대상이자 IF-SHP-001 의 조회·갱신 대상
 * @param shipmentTable IF-SHP-001 의 적재 대상
 */
public record TargetTables(String orderTable, String shipmentTable) {

    private static final String TABLE_KEY = "TABLE";

    public TargetTables {
        orderTable = SqlIdentifiers.requireSafe(orderTable, "ORDER_TB 테이블명");
        shipmentTable = SqlIdentifiers.requireSafe(shipmentTable, "SHIPMENT_TB 테이블명");
    }

    /**
     * BOOT-000 산출물에서 읽어 온다.
     *
     * <p>{@code require} 를 쓰므로 값이 없으면 기동이 실패한다. 기본값으로 넘어가지 않는 이유는,
     * 테이블명을 추측해서 맞았다면 <b>우연</b>이고 틀렸다면 <b>엉뚱한 테이블에 적재</b>하기
     * 때문이다. 후자는 append-only 환경에서 되돌릴 수 없다.
     */
    public static TargetTables from(SecretsLoader secretsLoader) {
        Properties orderConn = secretsLoader.load(BootstrapArtifactStore.ORDER_TB_CONN);
        Properties shipmentConn = secretsLoader.load(BootstrapArtifactStore.SHIPMENT_TB_CONN);

        return new TargetTables(
                secretsLoader.require(orderConn, TABLE_KEY, "ORDER_TB_CONN"),
                secretsLoader.require(shipmentConn, TABLE_KEY, "SHIPMENT_TB_CONN"));
    }
}
