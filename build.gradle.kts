import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.inspien"
version = "0.0.1-SNAPSHOT"
description = "INSPIEN EAI Assignment 2026 - 미니 EAI 연계 엔진"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly { extendsFrom(configurations.annotationProcessor.get()) }
}

repositories {
    mavenCentral()
}

dependencies {
    // ── 과제 가이드: "필요한 라이브러리만 이용". 쓰지 않는 스타터는 넣지 않는다.
    //    web 스타터는 화면(UI)용이 아니라 시나리오1의 REST Sender 자체이므로 필수.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ── JDBC Receiver. DataSource / HikariCP / JdbcTemplate.
    //    ORM 을 쓰지 않는 것은 의도다 — 연계 엔진은 도메인을 소유하지 않고
    //    표준 레코드를 그대로 적재할 뿐이므로 엔티티·영속성 컨텍스트가 필요 없다.
    //    대상 스키마가 불변 조건인 과제에서는 DDL 자동 생성 여지가 있는 기능이 위험 요소이기도 하다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // ── 적재 대상 DB 는 Oracle 이다 (BOOT-000 복호화 결과: jdbc:oracle:thin).
    //    ojdbc11 = JDK 11+ 대상 빌드. Java 21 에서 사용.
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.4.0.24.05")

    // ── FTP Receiver. 포트 30021 의 프로토콜 판정은 BOOT-001 에서 수행한다.
    implementation("commons-net:commons-net:3.11.1")

    // ── 채번(INCRBY) · 배치 중복 실행 방지(분산 락).
    //    캐시가 아니라 "프로세스 밖에 있어야 하는 상태" 를 두는 자리다.
    //    AtomicLong 은 재기동하면 0부터 다시 시작해 이미 적재된 번호와 충돌한다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    // 한글 소스/리소스가 Windows 기본 인코딩(MS949)으로 컴파일되면 깨진다.
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

/**
 * 제어 평면 실행 태스크 공통 설정.
 *
 * 프로파일은 프로그램 인자(args)가 아니라 시스템 프로퍼티로 준다.
 * Gradle 의 `--args` 는 빌드 스크립트의 args 를 <b>대체</b>하므로,
 * args 로 프로파일을 주면 `--args="..."` 사용 시 프로파일이 통째로 사라진다.
 *
 * 콘솔 인코딩도 함께 고정한다. 파이프로 연결되면 JVM 이 콘솔 인코딩을 감지하지 못해
 * 한글이 깨지는데, 실행 환경(Windows/macOS/Linux)에 무관하게 재현되어야 한다.
 */
fun BootRun.controlPlaneDefaults(profiles: String) {
    group = "inspien"
    mainClass.set("com.inspien.eai.InspienEaiApplication")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs(
        "-Dspring.profiles.active=$profiles",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
    )
}

/**
 * BOOT-000 — 과제 정보 수신 & 복호화 (1회성).
 *
 *   gradlew bootstrapRun                                          # 저장된 원문 재사용 (기본)
 *   gradlew bootstrapRun --args="--inspien.bootstrap.source=api"  # 실제 호출 (최초 1회)
 */
tasks.register<BootRun>("bootstrapRun") {
    description = "BOOT-000: 과제 API 호출 → 응답 저장 → 접속정보 복호화"
    controlPlaneDefaults("local,bootstrap")
}

/**
 * BOOT-001 — 연계 대상 사전 점검.
 *
 *   gradlew probeRun
 */
tasks.register<BootRun>("probeRun") {
    description = "BOOT-001: Oracle / FTP 접속 확인 및 실제 스펙 조회"
    controlPlaneDefaults("local,probe")
}
