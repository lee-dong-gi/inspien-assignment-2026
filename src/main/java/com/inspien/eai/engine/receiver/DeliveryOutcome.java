package com.inspien.eai.engine.receiver;

import com.inspien.eai.engine.exception.EaiErrorCode;

/**
 * 전달 조율의 결과 — {@link DeliveryCoordinator#deliver} 의 반환 타입.
 *
 * <h2>왜 {@code int} 로는 부족한가</h2>
 * 조율의 결과는 성공/실패 두 가지가 아니다. <b>되돌릴 수 없는 자리에서 실패하는</b> 경우가 있다.
 *
 * <pre>
 *   JDBC commit 성공 → FTP rename 실패
 *     ⇒ DB 에는 63행이 유효하게 들어 있고, 서버에는 .tmp 가 남아 있다
 * </pre>
 *
 * 이 상태를 예외로 던지면 호출자는 {@code FAIL} 로 응답하고, 응답을 받은 쪽은 <b>재요청한다.</b>
 * 그 순간 같은 주문이 두 번 적재된다 — {@link EaiErrorCode#FTP_RENAME_FAILED} 가
 * 재시도 불가로 못박혀 있는 이유가 그것이다. 반대로 조용히 성공으로 보고하면 영수증이
 * 영원히 수집되지 않는다.
 *
 * <p>그래서 <b>확정된 건수와 "사람이 손대야 한다" 는 사실을 함께</b> 돌려준다.
 * 정의서 3.10 의 응답 포맷이 이미 {@code PARTIAL} 을 1급 상태로 두고 있으므로,
 * 조율자가 그 상태를 표현하지 못하면 그 자리에서 정보가 잘린다.
 *
 * @param count              확정된 건수. 수신처들이 <b>동일한 레코드 리스트</b>를 소비하므로
 *                           수신처별 건수를 더하지 않는다 (63행 × 2 = 126 은 오답이다)
 * @param confirmedTargets   확정에 성공한 수신처 수
 * @param totalTargets       전체 수신처 수
 * @param manualActionCode   수동 조치가 필요할 때의 에러 코드. 완전 확정이면 {@code null}
 * @param manualActionDetail 운영자가 무엇을 해야 하는지. 코드만으로는 조치할 수 없다
 */
public record DeliveryOutcome(
        int count,
        int confirmedTargets,
        int totalTargets,
        EaiErrorCode manualActionCode,
        String manualActionDetail
) {

    public DeliveryOutcome {
        if (manualActionCode != null && (manualActionDetail == null || manualActionDetail.isBlank())) {
            // 코드만 남기고 조치 내용을 비우면 운영자는 로그를 보고도 할 수 있는 일이 없다.
            throw new IllegalArgumentException("수동 조치 대상은 조치 내용을 반드시 동반해야 한다.");
        }
    }

    /** 전 수신처 확정 완료. */
    public static DeliveryOutcome completed(int count, int targets) {
        return new DeliveryOutcome(count, targets, targets, null, null);
    }

    /**
     * 일부 수신처만 확정된 채 되돌릴 수 없는 상태.
     *
     * <p>{@code count} 를 0 으로 낮추지 않는다. 실제로 적재된 건수를 줄여 보고하면
     * <b>정합성이 깨진 사실 자체가 숨겨진다.</b>
     */
    public static DeliveryOutcome manualActionRequired(int count,
                                                       int confirmedTargets,
                                                       int totalTargets,
                                                       EaiErrorCode code,
                                                       String detail) {
        return new DeliveryOutcome(count, confirmedTargets, totalTargets, code, detail);
    }

    public boolean needsManualAction() {
        return manualActionCode != null;
    }
}
