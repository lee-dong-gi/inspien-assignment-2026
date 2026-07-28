package com.inspien.eai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * INSPIEN EAI 과제 — 연계 엔진 진입점.
 *
 * <p>실행 경로는 두 가지로 분리된다.
 * <ul>
 *   <li><b>제어 평면</b> — {@code gradlew bootstrapRun} : BOOT-000 1회성 설정 프로비저닝</li>
 *   <li><b>데이터 평면</b> — {@code gradlew bootRun}     : IF-ORD-001 / IF-SHP-001 연계 처리</li>
 * </ul>
 *
 * <h2>{@code DataSourceAutoConfiguration} 을 배제한 이유</h2>
 * 자동 설정은 접속정보를 {@code spring.datasource.*} 에서 찾는다. 그러려면
 * BOOT-000 이 복호화해 {@code secrets/} 에 놓은 크리덴셜을 <b>설정 파일로 한 번 더 복사</b>해야
 * 하고, 그 복사본이 곧 커밋 사고의 경로가 된다. 접속정보는 설정이 아니라
 * <b>런타임에 프로비저닝된 산출물</b>이므로, DataSource 는
 * {@link com.inspien.eai.common.jdbc.TargetJdbcConfig} 가 직접 조립한다.
 *
 * <p>배제하지 않으면 제어 평면 실행({@code bootstrapRun})에서도 자동 설정이 깨어나
 * "URL 이 없다" 며 기동을 막는다. 접속정보를 <b>받아 오는</b> 단계가 접속정보를
 * 요구받는 순환이다.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class InspienEaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspienEaiApplication.class, args);
    }
}
