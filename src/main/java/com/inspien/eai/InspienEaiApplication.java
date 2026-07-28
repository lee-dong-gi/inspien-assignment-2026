package com.inspien.eai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * INSPIEN EAI 과제 — 연계 엔진 진입점.
 *
 * <p>실행 경로는 두 가지로 분리된다.
 * <ul>
 *   <li><b>제어 평면</b> — {@code gradlew bootstrapRun} : BOOT-000 1회성 설정 프로비저닝</li>
 *   <li><b>데이터 평면</b> — {@code gradlew bootRun}     : IF-ORD-001 / IF-SHP-001 연계 처리</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InspienEaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspienEaiApplication.class, args);
    }
}
