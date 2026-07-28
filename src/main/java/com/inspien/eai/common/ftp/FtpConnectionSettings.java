package com.inspien.eai.common.ftp;

/**
 * FTP 접속정보 — BOOT-000 산출물({@code secrets/ftp.conn.properties})의 타입화.
 *
 * <p>{@link java.util.Properties} 를 그대로 들고 다니지 않는 이유는, 그렇게 하면
 * 키 이름 오타가 <b>런타임에 {@code null}</b> 로 나타나기 때문이다. 로딩 지점에서 한 번
 * 꺼내 타입으로 고정하면, 산출물이 바뀌었을 때 기동 시점에 드러난다.
 *
 * @param password 로그에 남기지 않는다. {@link #toString()} 참조
 * @param path     업로드 디렉터리 (예: {@code Recruit/2026/}). 상대경로로 전달되며
 *                 서버에서 절대경로로 해석된다
 */
public record FtpConnectionSettings(
        String host,
        int port,
        String user,
        String password,
        String path
) {

    public FtpConnectionSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("FTP host 가 비어 있다 (FTP_CONN.URL)");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("FTP port 가 범위를 벗어난다: " + port);
        }
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("FTP user 가 비어 있다 (FTP_CONN.ID)");
        }
        path = (path == null) ? "" : path.trim();
    }

    /**
     * 크리덴셜을 문자열 결합 사고로 흘리지 않는다.
     *
     * <p>예외 메시지에 설정 객체를 통째로 넣는 것은 가장 흔한 유출 경로다.
     */
    @Override
    public String toString() {
        return "FTP(" + host + ":" + port + ", user=" + user + ", path=" + path + ")";
    }
}
