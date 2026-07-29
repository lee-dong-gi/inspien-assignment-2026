package com.inspien.eai.integration.order.api;

import com.inspien.eai.common.api.InterfaceResponse;
import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.integration.order.OrderIntegrationFlow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * IF-ORD-001 진입점 — 주문 생성 API.
 *
 * <p>하는 일은 셋뿐이다: 바이트를 받고, 파이프라인에 넘기고, 결과를 HTTP 로 옮긴다.
 * <b>주문에 대해 아는 것이 하나도 없다.</b> XML 도 ORDER_TB 도 영수증도 여기에 없으므로,
 * 시나리오 2 가 스케줄러로 같은 파이프라인을 부를 때 이 클래스는 아무 영향을 받지 않는다.
 *
 * <h2>{@code byte[]} 로 받는다 — {@code String} 으로 받으면 이미 늦었다</h2>
 * 소스 XML 은 <b>선언부 없는 EUC-KR</b> 이다. 파라미터를 {@code String} 으로 두면
 * 서블릿 컨테이너가 먼저 디코딩하는데, 그 문자셋은 요청 헤더와 컨테이너 기본값이 정한다.
 * 송신 측이 {@code charset} 을 안 붙이면 대부분 ISO-8859-1 이나 UTF-8 로 해독되고
 * <b>한글은 그 자리에서 이미 깨진다.</b> 뒤에서 EUC-KR 을 아무리 지정해도 복구되지 않는다.
 * 원본 바이트를 그대로 받아 해독 시점을 우리가 소유한다.
 *
 * <p>같은 이유로 {@code consumes} 를 좁히지 않는다. {@code application/xml} 로 제한하면
 * {@code Content-Type} 을 붙이지 않거나 다르게 붙이는 송신 시스템이 <b>415 로 튕긴다</b> —
 * 우리는 본문 바이트만 쓰므로 선언된 타입에 의존할 이유가 없다.
 *
 * <h2>{@code required = false} 인 이유</h2>
 * 기본값 {@code true} 로 두면 빈 본문일 때 스프링이 우리 코드에 닿기 전에 400 을 만들고,
 * 그 응답은 {@link InterfaceResponse} 형식이 아니다 — <b>txId 도 에러 코드도 없는 응답</b>이
 * 섞여 나간다. {@code null} 을 그대로 흘려보내면 {@code OrderRestSender} 가
 * {@code EAI-1003} 으로 거부하므로, 어떤 입력이든 응답 형식이 하나로 유지된다.
 *
 * <h2>컴포넌트 스캔으로 등록한다 (다른 부품과 다르게)</h2>
 * 이 프로젝트는 부품을 {@code @Configuration} 에서 손으로 잇는다. 컨트롤러만 예외인 이유는,
 * {@code @RestController} 가 {@code @Component} 계열이라 <b>스캔과 {@code @Bean} 등록이 겹치면
 * 같은 핸들러가 두 번 등록</b>되어 매핑 충돌로 기동이 실패하기 때문이다.
 * 손 조립의 이유였던 테스트 용이성은 여기서 손해가 없다 — 애노테이션이 붙어 있어도
 * 단위 테스트에서는 그냥 {@code new OrderController(flow)} 로 만들면 된다.
 *
 * <p>대신 {@code OrderFlowConfig} 와 <b>같은 조건</b>을 건다. 제어 평면(bootstrap · probe)에서는
 * 파이프라인 빈이 아예 만들어지지 않으므로, 조건이 없으면 주입 대상이 없어 기동이 깨진다.
 */
@RestController
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderController {

    private final OrderIntegrationFlow flow;

    public OrderController(OrderIntegrationFlow flow) {
        this.flow = flow;
    }

    /**
     * 주문 XML 1건을 연계한다.
     *
     * <p>파이프라인은 <b>예외를 던지지 않기로</b> 된 계약이므로 여기에 {@code try-catch} 가 없다.
     * 성공·부분성공·실패가 전부 {@link ProcessResult} 로 돌아온다.
     */
    @PostMapping(
            path = "/api/v1/orders",
            consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InterfaceResponse> createOrders(@RequestBody(required = false) byte[] body) {
        ProcessResult result = flow.execute(body);

        return ResponseEntity
                .status(statusOf(result))
                .body(InterfaceResponse.from(flow.ifId(), result));
    }

    /**
     * 실행 결과를 HTTP 상태로 옮긴다.
     *
     * <h2>{@code PARTIAL} 은 200 이다 (D-19)</h2>
     * 요청 처리 자체는 정상적으로 끝났고, <b>결과가 부분 성공</b>일 뿐이다.
     * 상태 코드로 한 번 더 표현하면 호출자가 본문을 안 보고 판단하게 되는데,
     * "63건 들어가고 11건 빠졌다" 는 정보는 상태 코드에 담을 수 없다.
     *
     * <p>보상 트랜잭션이 되돌릴 수 없는 자리에서 실패한 경우({@code errorCode} 가 실린 PARTIAL)도
     * 마찬가지로 200 이다. 5xx 를 주면 호출자의 재시도 로직이 돌고,
     * <b>그 재요청이 이미 적재된 63행을 한 번 더 넣는다</b> — 정확히 D-14 가 막으려던 사고다.
     */
    private static HttpStatus statusOf(ProcessResult result) {
        if (result.outcome() != ProcessResult.Outcome.FAIL) {
            return HttpStatus.OK;
        }
        return statusOf(result.errorCode());
    }

    /**
     * 에러 코드 대역을 그대로 상태 코드로 옮긴다.
     *
     * <pre>
     *   1xxx  메시지 자체의 문제   → 400  <b>송신 측이 고쳐야 한다.</b> 같은 요청을 다시 보내면 또 실패한다
     *   2xxx/3xxx 재시도 가능      → 503  대상 시스템 일시 장애. 잠시 후 같은 요청을 다시 보내면 된다
     *   2xxx/3xxx 재시도 불가      → 502  대상 시스템에서 확정적으로 실패했다
     *   4xxx  우리 쪽 문제         → 500  조립 오류·버그·운영 한계. 송신 측이 할 수 있는 일이 없다
     * </pre>
     *
     * <p>이 구분이 실제로 하는 일은 <b>전화를 누구에게 걸지 정해 주는 것</b>이다.
     * 전부 500 으로 뭉뚱그리면 송신 시스템 담당자는 자기 데이터가 잘못됐다는 사실을 영원히 모른다.
     *
     * <p>{@code default} 를 두지 않았다. Java 21 의 exhaustive switch 라서
     * <b>{@link EaiErrorCode} 에 코드를 하나 추가하면 이 자리에서 컴파일이 깨진다.</b>
     * 새 실패 유형이 아무 생각 없이 500 으로 흘러가는 것을 막는 장치이며,
     * 이 프로젝트가 {@code ApplicantKey} 타입을 도입한 것과 같은 발상이다.
     */
    private static HttpStatus statusOf(EaiErrorCode code) {
        if (code == null) {
            // 코드 없는 FAIL 은 우리 쪽 결함이다. 결과를 만든 자리가 사유를 붙이지 않았다는 뜻이다.
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            // 1xxx — 보낸 것이 잘못됐다
            case VALIDATION_ERROR, MAPPING_ERROR, SOURCE_PARSE_ERROR, SOURCE_ENCODING_ERROR ->
                    HttpStatus.BAD_REQUEST;

            // 2xxx/3xxx + 채번 — 다시 하면 될 수 있다 (EaiErrorCode.retryable() 과 같은 기준)
            case JDBC_CONN_ERROR, FTP_CONN_ERROR, FTP_UPLOAD_ERROR, ID_ISSUE_FAILED ->
                    HttpStatus.SERVICE_UNAVAILABLE;

            // 2xxx/3xxx — 대상 시스템에서 확정적으로 실패했다
            case JDBC_EXEC_ERROR, FTP_ENCODING_ERROR, FTP_COMMIT_FAILED ->
                    HttpStatus.BAD_GATEWAY;

            // 4xxx — 우리 쪽 문제
            case ID_SPACE_EXHAUSTED, BATCH_LOCK_ACQUIRE_FAILED, DELIVERY_ERROR, FLOW_ERROR ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
