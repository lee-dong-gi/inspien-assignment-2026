package com.inspien.eai.common.ftp;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;

import java.io.IOException;

/**
 * FTP 세션 정리.
 *
 * <p>세 곳에서 필요하다 — 개설 실패({@link FtpClientFactory}), 준비 실패(Receiver),
 * 확정·보상 종료({@link UploadedFileDelivery}). 여는 쪽과 닫는 쪽이 다르므로
 * 정리 자체를 별도 자리에 둔다.
 *
 * <h2>왜 이 정리를 빠뜨리면 안 되는가</h2>
 * 서버는 <b>동시 접속 5명</b>으로 제한돼 있다 (BOOT-001 배너 실측).
 * 세션 하나가 새면 그 자리는 15분의 유휴 타임아웃이 지나야 돌아온다.
 * 다섯 번 실패하면 그 뒤의 모든 주문이 <b>접속 거부</b>로 실패하고,
 * 그때의 증상은 "FTP 서버가 이상하다" 로 보여 원인 추적이 엉뚱한 곳으로 간다.
 *
 * <h2>조용히, 그러나 기록하면서</h2>
 * 정리는 대개 <b>다른 실패를 처리하는 중</b>에 불린다. 여기서 예외를 던지면
 * 원래의 실패 원인이 덮인다. 던지지는 않되 로그는 남긴다.
 */
@Slf4j
public final class FtpSessions {

    private FtpSessions() {
    }

    /**
     * 로그아웃 후 연결을 끊는다. 어느 단계에서 실패해도 다음 단계는 진행한다.
     *
     * <p>{@code logout()} 이 먼저인 것은 서버에 {@code QUIT} 을 보내 세션을 <b>정상 종료</b>
     * 시키기 위해서다. 소켓만 끊으면 서버 쪽 세션은 타임아웃까지 남을 수 있다.
     * 다만 로그인 전에 실패한 경우처럼 {@code logout} 이 의미 없는 상황도 있으므로,
     * 실패해도 연결 종료는 반드시 이어서 시도한다.
     */
    public static void closeQuietly(FTPClient client) {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            client.logout();
        } catch (IOException e) {
            log.debug("FTP logout 실패 — 연결 종료로 정리에 맡긴다", e);
        }
        try {
            client.disconnect();
        } catch (IOException e) {
            log.warn("FTP 연결 종료 실패 — 세션이 서버에 남을 수 있다 (동시 접속 5 제한)", e);
        }
    }
}
