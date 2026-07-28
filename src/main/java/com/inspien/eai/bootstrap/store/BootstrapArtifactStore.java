package com.inspien.eai.bootstrap.store;

import com.inspien.eai.bootstrap.BootstrapProperties;
import com.inspien.eai.bootstrap.dto.ConnBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * BOOT-000 산출물 저장소.
 *
 * <p>모든 산출물은 {@code secrets/} 아래에 떨어지며 {@code .gitignore} 로 커밋이 차단된다.
 * 외부 인터페이스를 매 실행마다 호출하지 않기 위한 <b>1회성 프로비저닝 결과의 영속화</b>이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapArtifactStore {

    public static final String RAW_RESPONSE = "bootstrap-response.raw";
    public static final String APPLICANT_KEY = "applicant-key.txt";
    public static final String ORDER_TB_CONN = "order-tb.conn.properties";
    public static final String SHIPMENT_TB_CONN = "shipment-tb.conn.properties";
    public static final String FTP_CONN = "ftp.conn.properties";
    public static final String SAMPLE_DATA_ORIGINAL = "sample-data.euckr.xml";
    public static final String SAMPLE_DATA_UTF8 = "sample-data.utf8.xml";

    private final BootstrapProperties properties;

    public Path write(String fileName, String content) {
        return writeBytes(fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 복호화된 접속정보 블록을 사람이 읽을 수 있는 형태로 저장한다.
     *
     * <p>이 파일은 <b>평문 크리덴셜</b>이다. 애플리케이션이 직접 로드하는 설정 파일이 아니라,
     * 접속 확인·DDL 조회 등 수동 작업을 위한 참조본이다.
     */
    public Path writeConnBlock(String fileName, ConnBlock block) {
        StringBuilder sb = new StringBuilder();
        sb.append("# BOOT-000 ").append(block.blockName()).append(" — 복호화 결과\n");
        sb.append("# 평문 크리덴셜. git 커밋 금지 (secrets/ 는 .gitignore 대상)\n");
        block.fields().forEach((key, value) -> sb.append(key).append('=').append(value).append('\n'));
        return write(fileName, sb.toString());
    }

    /**
     * 원본 바이트를 변환 없이 저장한다.
     * SAMPLE_DATA 는 EUC-KR 원본을 그대로 남겨두어야 인코딩 문제를 나중에 재현·검증할 수 있다.
     */
    public Path writeBytes(String fileName, byte[] content) {
        try {
            Path dir = properties.outputDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            Files.write(target, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            log.info("[BOOT-000] 산출물 저장 → {} ({} bytes)", target.toAbsolutePath(), content.length);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("[BOOT-000] 산출물 저장 실패: " + fileName, e);
        }
    }

    /**
     * 저장된 산출물을 읽는다. {@code source=file} 모드에서 응답 원문을 재사용할 때 쓴다.
     */
    public String readText(String fileName) {
        Path target = properties.outputDir().resolve(fileName);
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("""
                    [BOOT-000] 저장된 응답 원문이 없습니다: %s
                    
                    source=file 은 이전 실행에서 저장된 원문을 재사용하는 모드입니다.
                    최초 1회는 아래처럼 실제 호출로 실행하세요.
                      gradlew bootstrapRun --args="--inspien.bootstrap.source=api"
                    """.formatted(target.toAbsolutePath()));
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            log.info("[BOOT-000] 저장된 응답 재사용 ← {} ({} bytes)", target.toAbsolutePath(), content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("[BOOT-000] 저장된 응답 읽기 실패: " + target.toAbsolutePath(), e);
        }
    }

    public Path outputDir() {
        return properties.outputDir().toAbsolutePath();
    }
}
