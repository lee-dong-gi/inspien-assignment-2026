package com.inspien.eai.common.secret;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * BOOT-000 산출물({@code secrets/}) 로더.
 *
 * <p>애플리케이션 설정({@code application.yml})과 분리돼 있는 이유는, 이 값들이
 * <b>런타임에 외부에서 프로비저닝된 크리덴셜</b>이기 때문이다. 소스나 설정 파일에
 * 하드코딩되는 순간 커밋 사고가 난다.
 *
 * <p><b>제어 평면 전용이 아니다.</b> 초판은 BOOT-001 점검 코드 옆에 있었지만,
 * 접속정보는 데이터 평면(JDBC · FTP Receiver)도 똑같이 필요로 한다.
 * 데이터 평면이 {@code bootstrap.probe} 패키지를 import 하는 것은 의존 방향이 거꾸로이므로
 * 공통으로 끌어올렸다. 이 클래스는 "파일을 읽는다" 이상의 책임을 갖지 않는다 —
 * 무엇이 필수인지, 값을 어떻게 해석할지는 각 사용처가 판단한다.
 */
public final class SecretsLoader {

    private final Path secretsDir;

    public SecretsLoader(Path secretsDir) {
        this.secretsDir = secretsDir;
    }

    public Properties load(String fileName) {
        Path target = require(fileName);
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("[SECRETS] 접속정보 로드 실패: " + target.toAbsolutePath(), e);
        }
        return props;
    }

    /** {@code applicant-key.txt} 처럼 키=값 구조가 아닌 산출물을 읽는다. */
    public String readText(String fileName) {
        Path target = require(fileName);
        try {
            return Files.readString(target, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("[SECRETS] 산출물 읽기 실패: " + target.toAbsolutePath(), e);
        }
    }

    public String require(Properties props, String key, String source) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[SECRETS] " + source + " 에 '" + key + "' 가 없습니다.");
        }
        return value.trim();
    }

    public Path dir() {
        return secretsDir;
    }

    /**
     * 산출물 존재 확인.
     *
     * <p>없을 때 "파일 없음" 만 던지지 않고 <b>다음에 할 일</b>을 함께 알려 준다.
     * 이 파일들은 사람이 만드는 것이 아니라 BOOT-000 이 만들어 내는 것이므로,
     * 부재는 곧 "선행 단계를 아직 돌리지 않았다" 는 뜻이다.
     */
    private Path require(String fileName) {
        Path target = secretsDir.resolve(fileName);
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("""
                    [SECRETS] %s 이(가) 없습니다.
                    먼저 BOOT-000 을 실행해 접속정보를 확보하세요:  gradlew bootstrapRun
                    """.formatted(target.toAbsolutePath()));
        }
        return target;
    }
}
