package com.inspien.eai.bootstrap.probe;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * BOOT-000 산출물({@code secrets/*.properties})을 읽어들이는 로더.
 *
 * <p>애플리케이션 설정({@code application.yml})과 분리돼 있는 이유는, 이 값들이
 * <b>런타임에 외부에서 프로비저닝된 크리덴셜</b>이기 때문이다. 소스나 설정 파일에
 * 하드코딩되는 순간 커밋 사고가 난다.
 */
public final class SecretsLoader {

    private final Path secretsDir;

    public SecretsLoader(Path secretsDir) {
        this.secretsDir = secretsDir;
    }

    public Properties load(String fileName) {
        Path target = secretsDir.resolve(fileName);
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("""
                    [BOOT-001] %s 이(가) 없습니다.
                    먼저 BOOT-000 을 실행해 접속정보를 확보하세요:  gradlew bootstrapRun
                    """.formatted(target.toAbsolutePath()));
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("[BOOT-001] 접속정보 로드 실패: " + target.toAbsolutePath(), e);
        }
        return props;
    }

    public String require(Properties props, String key, String source) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[BOOT-001] " + source + " 에 '" + key + "' 가 없습니다.");
        }
        return value.trim();
    }
}
