package com.inspien.eai.bootstrap;

import com.inspien.eai.bootstrap.client.RecruitingTestClient;
import com.inspien.eai.bootstrap.crypto.CredentialDecryptor;
import com.inspien.eai.bootstrap.dto.BootstrapPayload;
import com.inspien.eai.bootstrap.dto.BootstrapRequest;
import com.inspien.eai.bootstrap.dto.BootstrapResponseParser;
import com.inspien.eai.bootstrap.dto.ConnBlock;
import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.inspien.eai.bootstrap.crypto.CredentialDecryptor.SAMPLE_DATA_CHARSET;

/**
 * BOOT-000 — 과제 정보 수신 및 복호화 (1회성 제어 평면 작업).
 *
 * <p>이 클래스는 <b>데이터 평면(IF-ORD-001 / IF-SHP-001)에 속하지 않는다.</b>
 * 주문 데이터가 흐르는 경로가 아니라, 엔진이 돌기 전에 설정을 확보하는 경로다.
 * 아키텍처 다이어그램에서도 점선("1회성 설정 주입")으로 구분해 표기한다.
 *
 * <p>실행:
 * <pre>
 *   gradlew bootstrapRun                                        # 저장된 원문 재사용 (기본)
 *   gradlew bootstrapRun --args="--inspien.bootstrap.source=api"  # 실제 호출 (최초 1회)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "inspien.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapRunner implements ApplicationRunner {

    /** 과제 명세상 고정 형식. 이 문자열이 그대로 복호화 키의 seed 가 된다. */
    private static final Pattern PHONE_FORMAT = Pattern.compile("^01[016789]-\\d{3,4}-\\d{4}$");

    private static final String URL = "URL";
    private static final String PORT = "PORT";
    private static final String TABLE = "TABLE";
    private static final String PATH = "PATH";

    private final BootstrapProperties properties;
    private final RecruitingTestClient client;
    private final BootstrapResponseParser parser;
    private final CredentialDecryptor decryptor;
    private final BootstrapArtifactStore store;

    @Override
    public void run(ApplicationArguments args) {
        log.info("════════════ BOOT-000 과제 정보 수신 시작 (source={}) ════════════", properties.source());

        // 전화번호는 두 모드 모두에서 필요하다. 복호화 키의 seed 이기 때문이다.
        validateApplicant();

        // 1) 응답 원문 확보
        String rawResponse = acquireRawResponse();

        // 2) 파싱 — 블록 스코프를 보존한 채 구조만 추출한다(값은 아직 암호문).
        BootstrapPayload payload = parser.parse(rawResponse);
        log.info("[BOOT-000] 구조 확인 완료 — 접속정보 블록 3종 + APPLICANT_KEY + SAMPLE_DATA");

        // 3) 복호화 — 암호화 단위는 블록이 아니라 블록 안의 개별 필드다.
        SecretKey key = decryptor.deriveKey(properties.applicant().phoneNumber());

        ConnBlock orderTb = decryptor.decryptBlock(payload.orderTbConn(), key);
        ConnBlock shipmentTb = decryptor.decryptBlock(payload.shipmentTbConn(), key);
        ConnBlock ftp = decryptor.decryptBlock(payload.ftpConn(), key);

        // 4) SAMPLE_DATA — 암호화가 아니라 Base64(EUC-KR). 원본 바이트와 UTF-8 변환본을 모두 남긴다.
        byte[] sampleBytes = decryptor.decodeSampleData(payload.sampleData());
        String sampleXml = new String(sampleBytes, SAMPLE_DATA_CHARSET);

        // 5) 저장
        store.write(BootstrapArtifactStore.APPLICANT_KEY, payload.applicantKey());
        store.writeConnBlock(BootstrapArtifactStore.ORDER_TB_CONN, orderTb);
        store.writeConnBlock(BootstrapArtifactStore.SHIPMENT_TB_CONN, shipmentTb);
        store.writeConnBlock(BootstrapArtifactStore.FTP_CONN, ftp);
        store.writeBytes(BootstrapArtifactStore.SAMPLE_DATA_ORIGINAL, sampleBytes);
        store.write(BootstrapArtifactStore.SAMPLE_DATA_UTF8, sampleXml);

        report(payload, orderTb, shipmentTb, ftp, sampleXml);
    }

    // ─────────────────────────────────────────────────────────

    private String acquireRawResponse() {
        if (properties.source() == BootstrapProperties.Source.FILE) {
            return store.readText(BootstrapArtifactStore.RAW_RESPONSE);
        }
        validateApiCredentials();
        // 호출 직후 원문을 먼저 저장한다. 이후 단계가 실패해도 재호출하지 않기 위해서다.
        String raw = client.call(new BootstrapRequest(
                properties.applicant().name(),
                properties.applicant().phoneNumber(),
                properties.applicant().email()
        ));
        store.write(BootstrapArtifactStore.RAW_RESPONSE, raw);
        return raw;
    }

    private void validateApplicant() {
        String phone = properties.applicant().phoneNumber();
        if (isBlank(phone)) {
            throw new IllegalStateException("""
                    복호화 키 seed(전화번호)가 비어 있습니다: INSPIEN_APPLICANT_PHONE
                    
                    application-local.yml.example 을 application-local.yml 로 복사해 채우거나,
                    환경변수로 주입하세요. (application-local.yml 은 .gitignore 대상입니다)
                    """);
        }
        if (!PHONE_FORMAT.matcher(phone).matches()) {
            throw new IllegalStateException("""
                    전화번호 형식이 올바르지 않습니다: 자리수 %d
                    
                    반드시 '010-1234-5678' 형식(하이픈 포함, 공백 없음)이어야 합니다.
                    이 문자열이 그대로 AES 키의 seed 이므로 한 글자만 달라도 모든 복호화가 실패합니다.
                    PDF 에서 복사하지 말고 직접 타이핑하세요(전각 하이픈 혼입 방지).
                    """.formatted(phone.length()));
        }
    }

    /** 실제 호출 모드에서만 필요한 설정. FILE 모드에서는 요구하지 않는다. */
    private void validateApiCredentials() {
        List<String> missing = new ArrayList<>();
        if (isBlank(properties.endpoint())) missing.add("inspien.bootstrap.endpoint");
        if (isBlank(properties.username())) missing.add("INSPIEN_API_USERNAME");
        if (isBlank(properties.password())) missing.add("INSPIEN_API_PASSWORD");
        if (isBlank(properties.applicant().name())) missing.add("INSPIEN_APPLICANT_NAME");
        if (isBlank(properties.applicant().email())) missing.add("INSPIEN_APPLICANT_EMAIL");

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "BOOT-000 실제 호출(source=api)에 필요한 설정이 비어 있습니다: " + missing);
        }
    }

    /**
     * 인터페이스 정의서의 미확정 항목(B1~B6)을 실행 결과로 곧바로 채울 수 있게 요약한다.
     * 크리덴셜 자체는 출력하지 않고, <b>설계 판단에 필요한 사실</b>만 드러낸다.
     */
    private void report(BootstrapPayload payload, ConnBlock orderTb, ConnBlock shipmentTb,
                        ConnBlock ftp, String sampleXml) {

        boolean sameInstance = orderTb.require(URL).equals(shipmentTb.require(URL));

        StringBuilder sb = new StringBuilder("\n");
        sb.append("════════════ BOOT-000 완료 ════════════\n");
        sb.append("산출물 위치 : ").append(store.outputDir()).append("\n\n");

        sb.append("── 인터페이스 정의서 확정 항목 ──\n");
        sb.append("B1 JDBC 스킴        : ").append(scheme(orderTb.require(URL))).append("\n");
        sb.append("B3 동일 인스턴스     : ").append(sameInstance ? "예 (단일 트랜잭션 가능 → D-04 = 묶는다)"
                                                          : "아니오 (DataSource 2개, 트랜잭션 분리 필요)").append("\n");
        sb.append("   ORDER  TABLE     : ").append(orderTb.getOrDefault(TABLE, "(없음)")).append("\n");
        sb.append("   SHIPMENT TABLE   : ").append(shipmentTb.getOrDefault(TABLE, "(없음)")).append("\n");
        sb.append("B4 FTP PORT         : ").append(ftp.getOrDefault(PORT, "(없음)"))
          .append(protocolHint(ftp.getOrDefault(PORT, ""))).append("\n");
        sb.append("B5 FTP PATH         : ").append(ftp.getOrDefault(PATH, "(지정 없음)")).append("\n");
        sb.append("B6 APPLICANT_KEY    : ").append(payload.applicantKey().length()).append("자\n\n");

        sb.append("── 복호화 검증 (값은 secrets/ 파일에만 기록) ──\n");
        appendMasked(sb, orderTb);
        appendMasked(sb, shipmentTb);
        appendMasked(sb, ftp);

        sb.append("\n── SAMPLE_DATA (EUC-KR 디코딩 검증 — 한글이 깨져 보이면 실패) ──\n");
        sb.append(head(sampleXml, 500)).append("\n");

        sb.append("\n─────────── 다음 할 일 ───────────\n");
        sb.append("1. git status --ignored  → secrets/ 가 커밋 대상이 아닌지 확인\n");
        sb.append("2. 접속정보로 원격 DB 접속 → SHOW CREATE TABLE ORDER_TB / SHIPMENT_TB\n");
        sb.append("   → 실제 DDL 로 sql/init/01-schema.sql 교체, V-06(길이 검증) 기준 확정\n");
        sb.append("3. sample-data.utf8.xml 열어 루트 엘리먼트 유무(B8) · HEADER/ITEM 건수(B9) 확인\n");
        sb.append("4. 위 결과로 interface-spec.md 의 TBD 를 채워 v1.0 으로 갱신\n");
        sb.append("══════════════════════════════════");

        log.info(sb.toString());
    }

    private void appendMasked(StringBuilder sb, ConnBlock block) {
        sb.append("  ").append(block.blockName()).append("\n");
        block.fields().forEach((name, value) ->
                sb.append("    ").append(String.format("%-10s", name)).append(": ")
                  .append(mask(name, value)).append("\n"));
    }

    /**
     * 크리덴셜은 전체를 로그에 남기지 않는다. 복호화 성공 여부만 눈으로 확인할 수 있으면 충분하다.
     * TABLE · PORT · PATH 는 비밀이 아니고 설계 판단에 직접 쓰이므로 그대로 노출한다.
     */
    private String mask(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return "(비어 있음)";
        }
        if (TABLE.equals(fieldName) || PORT.equals(fieldName) || PATH.equals(fieldName)) {
            return value;
        }
        int visible = Math.min(6, value.length());
        return value.substring(0, visible) + "…(총 " + value.length() + "자)";
    }

    /** JDBC 드라이버·페이징 문법·잠금 구문 선택의 근거가 되는 스킴만 드러낸다. */
    private String scheme(String url) {
        int idx = url.indexOf("://");
        return idx > 0 ? url.substring(0, idx + 3) : "(스킴 구분자 '://' 없음 — 원문 확인 필요)";
    }

    private String protocolHint(String port) {
        return switch (port.trim()) {
            case "21" -> "  → FTP (commons-net)";
            case "22" -> "  → SFTP (JSch / sshd-sftp)";
            case "" -> "";
            default -> "  → 포트 비표준. 프로토콜 직접 확인 필요";
        };
    }

    private String head(String value, int limit) {
        if (value == null) {
            return "(없음)";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "\n… (이하 생략)";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
