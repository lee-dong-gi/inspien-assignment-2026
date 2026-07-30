package com.inspien.eai.integration.shipment.schedule;

import com.inspien.eai.engine.exception.EaiErrorCode;
import com.inspien.eai.engine.message.ProcessResult;
import com.inspien.eai.integration.shipment.BatchTrigger;
import com.inspien.eai.integration.shipment.ShipmentIntegrationFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * IF-SHP-001 스케줄러 — 5분 주기 트리거.
 *
 * <h2>이 클래스는 아무것도 모른다</h2>
 * ORDER_TB 도, SHIPMENT_TB 도, 청크도, 락도 모른다. 하는 일은 <b>파이프라인을 깨우고
 * 결과를 로그 수준으로 옮기는 것</b>뿐이다. 그래서 트리거를 하나 더 붙이는 일
 * (수동 API · 메시지 큐 · 운영 콘솔)이 파이프라인에 아무 영향을 주지 않는다 —
 * 실제로 {@code ShipmentController} 가 같은 흐름을 {@code MANUAL} 로 부른다.
 *
 * <h2>{@code fixedDelay} 다 — {@code fixedRate} 가 아니다 (정의서 4.5)</h2>
 * <pre>
 *   fixedRate  : 시작 시각 기준. 수행이 주기보다 길어지면 <b>다음 실행이 밀려 쌓인다</b>
 *   fixedDelay : 종료 시각 기준. 느려지면 주기가 늘어날 뿐 쌓이지 않는다
 * </pre>
 * 배치가 느려지는 상황은 대상 DB 가 느려진 상황이다. 그때 실행을 쌓아 올리는 것은
 * <b>이미 힘든 상대에게 부하를 더 얹는</b> 일이다.
 *
 * <h2>주기 값을 코드에 박지 않는다</h2>
 * {@code fixedDelayString} 으로 프로퍼티를 읽는다. 시연 중에 주기를 줄여 보여 주거나
 * (5분은 면접 자리에서 너무 길다) 반대로 늘려 상태를 고정해야 할 때, <b>재빌드 없이</b>
 * 바꿀 수 있어야 한다.
 *
 * <h2>예외를 밖으로 흘리지 않는다</h2>
 * 파이프라인은 <b>예외를 던지지 않기로</b> 된 계약이므로 정상적으로는 필요 없는 방어다.
 * 그럼에도 감싸는 이유는, 스케줄러 메서드에서 예외가 올라가면 스프링의 기본 핸들러가
 * 자기 형식으로 기록하고 <b>우리 인터페이스 로그에는 아무 흔적이 남지 않는다.</b>
 * 5분마다 조용히 실패하는 배치는 며칠 뒤에 발견된다.
 */
@Slf4j
public class ShipmentBatchScheduler {

    private final ShipmentIntegrationFlow flow;

    public ShipmentBatchScheduler(ShipmentIntegrationFlow flow) {
        this.flow = flow;
    }

    @Scheduled(
            fixedDelayString = "${inspien.batch.shipment.fixed-delay}",
            initialDelayString = "${inspien.batch.shipment.initial-delay}")
    public void runOnSchedule() {
        try {
            report(flow.execute(BatchTrigger.SCHEDULED));

        } catch (RuntimeException e) {
            // 여기 걸리는 것은 파이프라인 계약 위반, 즉 우리 코드의 버그다.
            // 삼키되 반드시 드러낸다 — 던지면 인터페이스 로그 밖에서 사라진다.
            log.error("[{}] 배치 스케줄 실행이 예외로 끝났다 — 파이프라인이 결과를 반환하지 않았다",
                    EaiErrorCode.FLOW_ERROR.code(), e);
        }
    }

    /**
     * 결과를 <b>적절한 로그 수준</b>으로 옮긴다.
     *
     * <p>수준을 나누는 것이 이 메서드의 요점이다. 전부 {@code info} 면 실패가 묻히고,
     * 전부 {@code error} 면 <b>정상 동작인 겹침 방지가 5분마다 오류로 쌓인다.</b>
     * 알림을 에러 로그에 걸어 둔 운영 환경이라면 후자는 그날부터 알림을 무시하게 만든다.
     */
    private void report(ProcessResult result) {
        switch (result.outcome()) {
            case SUCCESS -> log.info("[IF-SHP-001] 배치 완료 — 전송 {}건 (txId={})",
                    result.processed(), result.txId());

            // 스킵이 있거나, 확정 도중 실패해 일부만 처리된 경우다. 둘 다 사람이 봐야 한다.
            case PARTIAL -> log.warn("[IF-SHP-001] 배치 부분 처리 — 전송 {}건, 제외 {}건 {} (txId={})",
                    result.processed(), result.skipped(),
                    result.errorCode() == null ? "" : result.errorCode().code(), result.txId());

            case FAIL -> reportFailure(result);
        }
    }

    private void reportFailure(ProcessResult result) {
        if (result.errorCode() == EaiErrorCode.BATCH_LOCK_ACQUIRE_FAILED) {
            // 안전장치가 동작한 것이다. 다만 계속 반복되면 배치가 사실상 멈춘 상태이므로
            // 흔적은 남긴다 — 이 줄이 연속으로 쌓이는 것 자체가 신호다.
            log.info("[IF-SHP-001] 이번 주기를 건너뛴다 — {}", result.errorMessage());
            return;
        }
        log.error("[IF-SHP-001] 배치 실패 — {} {} (txId={})",
                result.errorCode() == null ? "" : result.errorCode().code(),
                result.errorMessage(), result.txId());
    }
}
