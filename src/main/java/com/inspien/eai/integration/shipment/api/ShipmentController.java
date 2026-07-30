package com.inspien.eai.integration.shipment.api;

import com.inspien.eai.common.api.InterfaceHttpStatus;
import com.inspien.eai.common.api.InterfaceResponse;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.integration.shipment.BatchTrigger;
import com.inspien.eai.integration.shipment.ShipmentIntegrationFlow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IF-SHP-001 수동 트리거 — 운영·시연용.
 *
 * <h2>왜 배치에 API 를 붙이는가</h2>
 * 첫째는 <b>시연</b>이다. 제출 요구사항은 5분 주기 동작을 보이는 것인데, 면접 자리에서
 * 5분을 기다리는 것은 현실적이지 않다. 정의서 7의 시연 절차도 "5분 대기 또는 수동 트리거" 다.
 *
 * <p>둘째는 <b>운영</b>이다. 배치가 실패했을 때 다음 주기를 기다리지 않고 다시 돌릴 수단이
 * 필요하다. 그 수단이 없으면 사람이 하는 일은 "5분 뒤에 로그를 다시 본다" 뿐이다.
 *
 * <h2>재실행이 안전한 근거</h2>
 * 이 엔드포인트를 몇 번 눌러도 같은 주문이 두 번 전송되지 않는다.
 * 조회 조건이 {@code STATUS='N'} 이고, 적재와 상태 갱신이 <b>같은 트랜잭션</b>이므로
 * 확정된 건은 다음 조회에서 빠진다. 멱등성의 근거가 PK 위반 처리가 아니라
 * <b>조회 조건 + 트랜잭션 경계</b>에 있다는 것이 이 인터페이스 설계의 요점이다 (D-22).
 *
 * <p>동시에 눌러도 안전하다 — 분산 락이 하나만 통과시키고, 나머지는
 * {@code EAI-4001} 과 함께 <b>409 Conflict</b> 를 받는다. 500 이 아니다:
 * 서버가 고장난 것이 아니라 <b>상태가 충돌</b>한 것이고, 호출자가 할 일은
 * "잠시 뒤 다시" 이지 "담당자에게 연락" 이 아니다.
 *
 * <h2>자동 주기와 무관하게 살아 있다</h2>
 * {@code inspien.batch.shipment.enabled=false} 로 자동 실행을 끈 상태에서도 동작한다.
 * 조건이 {@code inspien.jdbc.enabled} 뿐인 것은 의도다 — append-only 환경에서
 * 시연 직전에 상태를 고정해 두고 원하는 순간 한 번만 돌리는 것이 안전한 절차이며,
 * 그때 필요한 것이 "자동은 끄고 수동은 켠" 구성이다.
 *
 * <h2>본문이 없다</h2>
 * 조회 조건을 호출자가 정하지 않는다. {@code APPLICANT_KEY} 나 청크 크기를 파라미터로
 * 받으면 <b>호출자가 다른 지원자의 주문을 조회하도록 만들 수 있는 문</b>이 열린다.
 * 배치의 조회 기준은 인터페이스의 정의이지 요청의 내용이 아니다.
 *
 * <p>{@code @RestController} 로 스캔 등록하는 이유와 조건을 함께 거는 이유는
 * {@code OrderController} 와 같다 (D-20).
 */
@RestController
@ConditionalOnProperty(prefix = "inspien.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentController {

    private final ShipmentIntegrationFlow flow;

    public ShipmentController(ShipmentIntegrationFlow flow) {
        this.flow = flow;
    }

    /**
     * 배치를 지금 1회 실행한다.
     *
     * <p>동기 호출이다. 응답을 기다리게 두는 이유는 시연·운영 모두 <b>결과를 보려고</b>
     * 부르는 것이기 때문이다. {@code 202 Accepted} 로 던져 놓으면 호출자는 로그를 따로
     * 찾아봐야 하고, 그러면 이 엔드포인트가 존재하는 이유가 절반 사라진다.
     *
     * <p>{@link BatchTrigger#MANUAL} 을 넘기므로 실행 이력의 {@code START} 줄에
     * {@code TRIGGER=MANUAL} 이 남는다 — 자동 주기와 섞여도 어느 쪽이 처리했는지 구분된다.
     */
    @PostMapping(
            path = "/api/v1/shipments/batch",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InterfaceResponse> runBatch() {
        ProcessResult result = flow.execute(BatchTrigger.MANUAL);

        return ResponseEntity
                .status(InterfaceHttpStatus.of(result))
                .body(InterfaceResponse.from(flow.ifId(), result));
    }
}
