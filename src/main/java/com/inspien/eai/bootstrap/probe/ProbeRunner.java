package com.inspien.eai.bootstrap.probe;

import com.inspien.eai.bootstrap.BootstrapProperties;
import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import com.inspien.eai.common.secret.ApplicantKey;
import com.inspien.eai.common.secret.SecretsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * BOOT-001 — 연계 대상 사전 점검 (제어 평면).
 *
 * <p>BOOT-000 이 <b>설정을 확보</b>하는 단계라면, BOOT-001 은 그 설정으로 실제 대상에 닿는지
 * 그리고 대상의 실제 스펙이 무엇인지 <b>확인</b>하는 단계다. 데이터 평면에 속하지 않는다.
 *
 * <p>이 점검은 대상 시스템의 상태를 바꾸지 않는다 (읽기 전용 조회 + FTP 디렉터리 탐색만).
 *
 * <pre>
 *   gradlew probeRun
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "inspien.probe", name = "enabled", havingValue = "true")
public class ProbeRunner implements ApplicationRunner {

    private final BootstrapProperties properties;
    private final SecretsLoader loader;
    private final ApplicantKey applicantKey;

    @Override
    public void run(ApplicationArguments args) {
        log.info("════════════ BOOT-001 연계 대상 사전 점검 시작 ════════════");
        log.info("[BOOT-001] 산출물 위치 → {}", properties.outputDir().toAbsolutePath());

        Properties orderTb = loader.load(BootstrapArtifactStore.ORDER_TB_CONN);
        Properties shipmentTb = loader.load(BootstrapArtifactStore.SHIPMENT_TB_CONN);
        Properties ftp = loader.load(BootstrapArtifactStore.FTP_CONN);

        DbProbe dbProbe = new DbProbe();
        FtpProbe ftpProbe = new FtpProbe();

        log.info("\n╔══════════ ORDER_TB ══════════╗");
        dbProbe.probe(orderTb, loader, "ORDER_TB_CONN", applicantKey.value());

        log.info("\n╔══════════ SHIPMENT_TB ══════════╗");
        dbProbe.probe(shipmentTb, loader, "SHIPMENT_TB_CONN", applicantKey.value());

        log.info("\n╔══════════ FTP ══════════╗");
        ftpProbe.probe(ftp, loader, "FTP_CONN");

        log.info("""
                
                ════════════ BOOT-001 완료 ════════════
                이 결과로 확정할 항목:
                  B4  FTP / SFTP 판정 → 라이브러리 선택
                  B5  업로드 경로 진입 가능 여부
                  V-06 컬럼 길이 + CHAR_USED(B/C) → 유효성 검증 기준
                  D-06 등록일시 성격 컬럼 유무 → 면접 시연 조회 방법
                ══════════════════════════════════""");
    }
}
