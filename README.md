# INSPIEN EAI 과제 2026

시스템 간 연계(EAI) 구조를 Sender / Validator / Mapper / Coordinator / Receiver 로 구현한 미니 연계 엔진.

> 이 프로젝트는 "주문 REST API 서버"가 아니라 **연계 엔진**이다.
> 주문·운송 도메인 로직을 소유하지 않고, **수신 → 검증 → 변환 → 조율 → 전달**만 수행한다.

## 인터페이스 목록

| IF-ID | 명칭 | 유형 | 주기 | Sender | Receiver |
|---|---|---|---|---|---|
| `IF-ORD-001` | 주문 생성 연계 | Real-time (SYNC) | 요청 시 | REST (XML) | JDBC(ORDER_TB) + FTP(영수증) |
| `IF-SHP-001` | 운송사 전송 배치 | Batch (ASYNC) | 5분 | JDBC Polling | JDBC(SHIPMENT_TB + STATUS 갱신) |

제어 평면 — 데이터 평면이 아니다. 주문 데이터가 흐르는 경로가 아니라 엔진 기동 전에 설정과 대상 스펙을 확보하는 경로이므로 별도 패키지·별도 실행 경로로 분리했다.

| 채널 | 명칭 | 성격 |
|---|---|---|
| `BOOT-000` | 과제 정보 수신 | 1회성 설정 프로비저닝 |
| `BOOT-001` | 연계 대상 사전 점검 | 읽기 전용 접속·스펙 확인 |

상세 매핑 규칙·검증 기준·설계 결정은 **[docs/interface-spec.md](docs/interface-spec.md)** 에 있다. 이 README 와 어긋나면 정의서를 따른다.

---

## 아키텍처 요약

과제 3.4 가 요구한 As-Is / To-Be 대비는 **[docs/architecture.md](docs/architecture.md)** 에 있다. 그림 둘만 먼저 옮긴다.

### ① As-Is → To-Be 대비

![As-Is 와 To-Be 대비](docs/images/architecture-1-asis-tobe.svg)

### ② To-Be 내부 계층

파이프라인을 인터페이스별로 두 줄 그리지 않고 **골격 하나에 ① Sender · ⑤ Receiver 를 교체 지점으로** 표시했다.
보라 실선이 IF-ORD-001, 청록 점선이 IF-SHP-001 이다.

![To-Be 내부 계층](docs/images/architecture-2-tobe-layers.svg)

핵심만 옮기면 이렇다. 과제 2.1 이 준 참고 구조는 **Sender → 변환 → Receiver** 세 칸인데, 가운데 한 칸을 셋으로 나눴다.

```
Sender ──▶ Validator ──▶ Mapper ──▶ DeliveryCoordinator ──▶ Receiver(N)
```

- **Validator 를 분리한 이유** — 변환 전에 걸러내야 한다. 실제 샘플에 고아 ITEM 7건, ITEM 없는 HEADER 4건이 섞여 있고, 변환 단계에서 함께 처리하면 "몇 건이 왜 빠졌는지" 세는 곳이 두 군데로 흩어진다.
- **Coordinator 를 분리한 이유** — 수신처가 둘이다. 세 칸짜리 구조에는 *DB 는 성공했는데 FTP 가 실패했을 때 무엇을 되돌릴지* 맡을 자리가 없다. 순서와 되돌리기를 전담하는 칸을 뒀다.
- **두 인터페이스가 이 골격을 공유한다.** 다른 것은 맨 앞(Sender)과 맨 뒤(Receiver)뿐이다.

검증 가능한 근거 하나 — IF-SHP-001 을 추가하면서 **실행 이력의 구간(`Step`) 열거형이 하나도 늘지 않았다.** 구간을 도메인(`RECEIVER_ORDER_TB`)이 아니라 프로토콜(`RECEIVER_JDBC`) 단위로 나눴기 때문이다.

## 기술 스택

| 구분 | 선택 | 근거 |
|---|---|---|
| Language | Java 21 | `record` 로 불변 표준 메시지, `sealed`·pattern matching 으로 결과 분기 |
| Framework | Spring Boot 3.5 | REST Sender, 스케줄러, DI |
| DB | **Oracle 19c** (원격, 과제 제공) | BOOT-001 실측. 드라이버 `ojdbc11` |
| DB 접근 | `JdbcTemplate` (ORM 미사용) | 스키마가 불변 조건이다. 엔티티를 만드는 순간 "우리가 주문을 소유한다"는 잘못된 신호가 되고, DDL 자동 생성 여지가 있는 기능은 그 자체로 위험 요소다 |
| FTP | 평문 FTP (`commons-net`) | BOOT-001 실측 |
| Cache | Redis | ORDER_ID/SHIPMENT_ID 원자 채번(`INCRBY`), 배치 분산 락 |
| Infra | Docker Compose | 로컬 Redis |

과제 가이드의 *"필요한 라이브러리만 이용"* 에 따라 사용하지 않는 스타터는 넣지 않는다. `spring-boot-starter-web` 은 화면(UI)용이 아니라 **시나리오 1의 REST Sender 자체**이므로 필수 의존성이다.

**일부러 쓰지 않은 것** — JPA(위 사유), Spring Batch(청크 반복이 20줄 남짓이라 프레임워크가 문제보다 커진다), Virtual Thread(요청당 스레드가 병목이 아니고, 오히려 커넥션 점유 시간이 병목이다), 로컬 DB 컨테이너(D-08).

---

## 실행 순서

### 0. 사전 요구

| 항목 | 값 |
|---|---|
| JDK | 21 (Gradle toolchain 이 강제) |
| Docker | Redis 컨테이너용 |
| 셸 | 아래 명령은 PowerShell 기준. macOS · Linux 는 바로 아래 대조표 참조 |

```powershell
# 콘솔 한글 출력. 이걸 안 하면 로그·응답의 한글이 깨져 보이고,
# 실제로는 정상인 데이터를 깨진 것으로 오진하게 된다.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

#### macOS · Linux 에서 실행할 때

터미널이 기본 UTF-8 이므로 위 인코딩 설정은 필요 없다. 명령만 아래로 바꿔 읽으면 된다.

| PowerShell | macOS · Linux |
|---|---|
| `.\gradlew.bat` | `./gradlew` |
| `curl.exe` | `curl` |
| `` ` `` (줄 이음) | `\` |
| `$(Get-Date -Format yyyyMMdd)` | `$(date +%Y%m%d)` |
| `Get-Content ... -Encoding UTF8` | `cat` (인코딩 옵션 불필요) |
| `Get-Content -Wait -Tail 20` | `tail -f -n 20` |
| `Copy-Item a b` | `cp a b` |

> Windows 에서 클론한 저장소를 그대로 옮겨 왔다면 `chmod +x gradlew` 가 필요하고,
> `bad interpreter` 가 나면 개행이 CRLF 인 것이다 (`perl -pi -e 's/\r\n/\n/' gradlew`).

> EUC-KR 인 `secrets/sample-data.euckr.xml` 을 터미널로 볼 때는 변환이 필요하다.
> `iconv -f EUC-KR -t UTF-8 secrets/sample-data.euckr.xml | head -20`

### 1. 로컬 인프라 기동

```powershell
docker compose up -d
docker compose ps        # healthy 확인
```

| 서비스 | 호스트 포트 | 비고 |
|---|---|---|
| Redis | 16379 | 채번 · 분산 락 |

포트는 기존 로컬 인스턴스와 충돌하지 않도록 비표준으로 매핑했다.

> **로컬 DB 컨테이너는 두지 않는다 (설계 결정 D-08).**
> 적재 대상이 Oracle 19c 로 확정됐고, MySQL 컨테이너는 충실한 테스트 대역이 아니다.
> `LIMIT` vs `FETCH FIRST`, `SYSDATE`, `VARCHAR2` 의 BYTE 길이 의미론이 전부 다르다.
> 로컬에서 통과한 SQL 이 원격에서 깨지는 대역은 안전망이 아니라 위험 요소다.
> 원격 DB 는 `APPLICANT_KEY` 로 지원자별 격리돼 있어 직접 사용해도 안전하다.
> 실제 컬럼 정의는 [`sql/oracle-schema.reference.sql`](sql/oracle-schema.reference.sql) 참조.

### 2. 크리덴셜 설정

```powershell
Copy-Item src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

복사한 파일에 안내 메일로 받은 Basic 인증 정보와 지원자 정보를 **직접 타이핑**해 채운다.
PDF 에서 복사하면 전각 따옴표·전각 하이픈이 섞여 들어가 호출 또는 복호화가 실패한다.

`application-local.yml` 은 `.gitignore` 대상이다. 환경변수로 주입해도 된다 (아래 표 참조).

### 3. 복호화 규격 검증 (외부 호출 전)

```powershell
.\gradlew.bat test --tests "*CredentialDecryptorTest"
```

외부 API 를 때리기 전에 복호화 로직 자체가 맞는지 먼저 확인한다. 여기가 통과해야 실패 원인을 "설정 값" 으로 좁힐 수 있다.

### 4. BOOT-000 — 과제 정보 수신 및 복호화

```powershell
.\gradlew.bat bootstrapRun --args="--inspien.bootstrap.source=api"   # 최초 1회, 실제 호출
.\gradlew.bat bootstrapRun                                           # 이후 저장된 원문 재사용 (기본값)
```

수행 내용:

1. 과제 API 호출 → **응답 원문을 먼저 저장** (이후 단계가 실패해도 재호출하지 않기 위해)
2. 응답 파싱 → 필수 필드 5종 확인
3. 전화번호 → SHA-1 → 앞 16바이트 → AES-128/ECB 로 접속정보 3종 복호화
4. `SAMPLE_DATA` Base64 디코딩 → **EUC-KR** 로 문자열화

> 기본값이 `file` 인 것은 의도된 선택이다. 파서·복호화를 고칠 때마다 상대 시스템을 때리는 구조는 EAI 관점에서 옳지 않다.

산출물은 전부 `secrets/` 에 떨어지며 `.gitignore` 로 커밋이 차단된다.

| 파일 | 내용 |
|---|---|
| `bootstrap-response.raw` | 응답 원문 |
| `applicant-key.txt` | 지원자 키 (평문) |
| `order-tb.conn.properties` | 주문 DB 접속정보 |
| `shipment-tb.conn.properties` | 운송 DB 접속정보 |
| `ftp.conn.properties` | FTP 접속정보 |
| `sample-data.euckr.xml` | 원본 바이트 (EUC-KR) |
| `sample-data.utf8.xml` | UTF-8 변환본 (편집기 확인용) |

> 콘솔에는 복호화 결과의 **앞 12자만** 출력된다. 크리덴셜 전체는 로그에 남기지 않는다.

### 5. BOOT-001 — 연계 대상 사전 점검

```powershell
.\gradlew.bat probeRun
```

명세서가 아니라 **실물**을 기준으로 삼기 위한 읽기 전용 점검이다. 대상 시스템의 상태는 바꾸지 않는다.

- **Oracle** — 버전, 캐릭터셋/길이 의미론, 컬럼 정의(타입·길이·NULL·DEFAULT), PK 구성, 서버 시각, 기존 행 수
- **FTP** — 프로토콜 판정, 업로드 디렉터리 존재, UTF8 지원 선언

여기서 확인된 값으로 인터페이스 정의서를 v1.0(확정)으로 갱신했다.

### 6. 엔진 기동

```powershell
.\gradlew.bat bootRun
```

기동 시 순서대로 일어나는 일:

1. `secrets/` 에서 접속정보 로드 (`SecretsLoader`)
2. Oracle `MAX(ORDER_ID)` / `MAX(SHIPMENT_ID)` 조회 → Redis 채번 카운터 시딩
3. HTTP 리스너 오픈 → 배치 스케줄러 등록

> **2번은 `@Bean(initMethod)` 으로 돈다 (D-13).** `ApplicationRunner` 는 웹 서버가 요청을 받기 시작한 **뒤에** 실행되므로, 그 사이에 들어온 첫 요청이 이미 적재된 번호와 충돌한다.
> 시딩이 실패하면 기동을 중단한다 — 복원 없이 뜨면 첫 요청부터 PK 위반이다.

> `bootRun` 은 서버가 떠 있는 동안 진행률이 80% 에서 멈춘 것처럼 보인다. 정상이다. 100% 는 종료할 때 도달한다.

### 7. IF-ORD-001 — 실시간 주문 연계 호출

```powershell
curl.exe -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/octet-stream" `
  --data-binary "@secrets/sample-data.euckr.xml"
```

> `-H "Content-Type: application/octet-stream"` 를 **반드시** 붙인다. 생략하면 curl 이 폼 인코딩해 본문이 부풀고 파서가 0건을 반환한다.
> `--data-binary` 도 필수다. `-d` 는 개행을 제거해 XML 이 한 줄로 뭉개진다.

제공된 샘플 기준 기대 응답:

```json
{ "ifId": "IF-ORD-001", "result": "PARTIAL", "processedCount": 63, "skippedCount": 11 }
```

`skippedCount` 11 은 샘플에 의도적으로 심어진 **고아 ITEM 7건 + ITEM 없는 HEADER 4건**이다. 전체 거부하지 않고 부분 처리하는 이유는 D-02 참조.

### 8. IF-SHP-001 — 운송사 전송 배치

자동 실행은 기동 1분 뒤 시작해 5분 간격(`fixedDelay`)으로 돈다. 기다리지 않고 지금 돌리려면:

```powershell
curl.exe -X POST http://localhost:8080/api/v1/shipments/batch
```

수동 트리거는 `inspien.batch.shipment.enabled=false` 로 자동 실행을 꺼도 **그대로 동작한다.** 조립을 `ShipmentFlowConfig`(파이프라인) / `ShipmentScheduleConfig`(스케줄러)로 나눈 이유가 이것이다.

**멱등성 확인** — 한 번 더 누르면 `processedCount=0`, `result=SUCCESS` 가 나온다. 조회 조건이 `STATUS='N'` 이고 적재와 상태 갱신이 같은 트랜잭션이라, 확정된 건은 다음 조회에서 빠진다. 멱등성의 근거가 PK 위반 처리가 아니라 **조회 조건 + 트랜잭션 경계**에 있다는 것이 이 인터페이스 설계의 요점이다 (D-22).

**겹침 방지 확인** — 락을 직접 걸어 둔 뒤 트리거한다.

```powershell
docker exec inspien-redis redis-cli SET eai:lock:if-shp-001 demo PX 30000
curl.exe -i -X POST http://localhost:8080/api/v1/shipments/batch   # 30초 안에
docker exec inspien-redis redis-cli DEL eai:lock:if-shp-001
```

`409` + `EAI-4001` 이 돌아오고, 실행 이력에는 `START` 와 `END` **두 줄만** 남는다 — 락을 잡지 못해 파이프라인에 진입조차 하지 않았다는 사실이 구간 줄의 부재로 드러난다.

500 이 아닌 이유는, 서버가 고장난 것이 아니라 상태가 충돌한 것이고 호출자가 할 일은 "잠시 뒤 다시" 이지 "담당자에게 연락" 이 아니기 때문이다 (D-19).

> **두 창에서 동시에 누르는 방식은 권하지 않는다.** 수행이 수십 ms 로 끝나 경합이 재현되지 않고,
> 만에 하나 락이 듣지 않으면 같은 주문이 두 벌 적재되어 **되돌릴 수 없다**(append-only).

---

## 설정과 환경변수

접속정보(Oracle · FTP)는 **설정 파일에 없다.** BOOT-000 이 복호화해 `secrets/` 에 놓은 산출물에서 읽는다 (D-12). `spring.datasource.*` 를 쓰지 않고 `DataSourceAutoConfiguration` 을 배제한 것도 같은 이유다 — 자동 설정은 크리덴셜을 설정 파일로 한 번 더 복사하게 만들고, 그 복사본이 커밋 사고의 경로다.

### 환경변수 (BOOT-000 전용)

| 변수 | 용도 |
|---|---|
| `INSPIEN_API_USERNAME` / `INSPIEN_API_PASSWORD` | 과제 API HTTP Basic |
| `INSPIEN_APPLICANT_NAME` | 지원자명. FTP 영수증 파일명에 그대로 쓰인다 |
| `INSPIEN_APPLICANT_PHONE` | **복호화 키의 seed.** 하이픈·공백 하나만 달라도 전부 실패한다 |
| `INSPIEN_APPLICANT_EMAIL` | 과제 API 요청 필드 |
| `REDIS_HOST` / `REDIS_PORT` | 기본 `localhost` / `16379` |
| `SPRING_PROFILES_ACTIVE` | 기본 `local` |

### 주요 운영 파라미터 (`application.yml`)

| 키 | 값 | 왜 이 값인가 |
|---|---|---|
| `inspien.jdbc.connect-timeout` / `read-timeout` | 10s / 20s | 기본값 무한 대기가 실제 장애의 주범이다. 상대가 죽는 것보다 **응답하지 않는 것**이 서비스를 멈춘다 |
| `inspien.jdbc.pool-wait-timeout` | 15s | 드라이버 connect 보다 길어야 한다. 짧으면 진짜 원인을 보기 전에 풀이 먼저 끊는다 |
| `inspien.jdbc.maximum-pool-size` | 5 | 보상 트랜잭션 구조상 커넥션이 FTP 업로드 시간만큼 점유된다. 1이면 배치와 실시간이 서로를 기다린다 |
| `inspien.ftp.control-encoding` | UTF-8 | **파일명이 나가는 채널.** commons-net 기본값 ISO-8859-1 로 두면 한글이 `?` 로 치환된 채 올라가고 예외도 안 난다 |
| `inspien.ftp.content-encoding` | EUC-KR | **파일 내용.** 원본 XML 인코딩 계승 (D-07) |
| `inspien.ftp.verify-uploaded-name` | true | 업로드 후 이름·크기를 다시 조회한다. **끄지 말 것** — 인코딩 손상도 잘린 파일도 예외 없이 성공으로 보고된다 |
| `inspien.batch.shipment.fixed-delay` | `PT5M` | `fixedRate` 는 수행이 주기보다 길어지면 실행이 밀려 쌓인다. **ISO-8601 표기 필수** — `@Scheduled(fixedDelayString)` 은 `5m` 축약을 못 읽고 기동이 실패한다 |
| `inspien.batch.shipment.lock-ttl` | `PT4M` | 주기보다 **짧아야** 한다(기동 시 검증). 길면 프로세스가 죽어 락을 반납 못 했을 때 그 시간 동안 배치가 통째로 멈춘다 |
| `inspien.batch.shipment.chunk-size` / `max-chunks-per-run` | 100 / 50 | 청크 상한이 곧 **수행 시간의 상한**이다. 이 값이 있어야 락 TTL 을 넘기지 않는다고 말할 수 있다 |

---

## 테스트

```powershell
.\gradlew.bat test
```

223건 (IF-ORD-001 158 + IF-SHP-001 65). 외부 시스템 없이 전부 돈다.

**실제 DB 로 통합 테스트를 하지 않은 이유** — 대상 환경이 append-only 공유 환경이라 실패 경로를 재현하면 되돌릴 수 없는 행이 남는다. 더 근본적으로, 이 설계의 핵심 단언인 *"아직 커밋하지 않았다"* 는 실물 DB 로는 관측할 수 없다. 그래서 대역은 **Sender · 수신처 · 락**만 두고 검증기·매퍼·조율자는 실물로 돌린다.

> 결과 집계에 PowerShell 을 쓴다면 `[xml](Get-Content ...)` 는 피할 것. PowerShell 5.1 은 BOM 없는 UTF-8 을 시스템 코드페이지로 읽어 JUnit XML 파싱이 실패한다.

---

## 설계 결정 요약

전체 26건은 [docs/interface-spec.md](docs/interface-spec.md) 6장에 근거와 함께 기록돼 있다. 그중 구조를 바꾼 것들만 옮긴다.

| ID | 결정 | 근거 요약 |
|---|---|---|
| D-02 | 이상 데이터는 **건 단위 스킵 + PARTIAL** | 샘플에 11건이 의도적으로 심어져 있다. 전체 거부하면 정상 63건도 적재되지 않는다 |
| D-04 | 두 테이블을 **단일 트랜잭션**으로 | 동일 인스턴스·동일 계정임을 BOOT-001 로 확인했다. 기동할 때마다 재확인한다 |
| D-11 | `@Transactional` 대신 **커넥션 직접 보유** | 트랜잭션 경계가 `prepare()`→`commit()` 이고 그 사이에 FTP 가 낀다. 선언적 트랜잭션은 경계가 한 메서드 안에 닫힌다는 전제라 성립하지 않는다 |
| D-14 | 조율 결과를 예외가 아니라 **반환값**으로 | "DB 는 커밋됐고 FTP 만 실패" 를 `FAIL` 로 응답하면 호출자가 재요청하고, 그 재요청이 곧 중복 적재다. **예외 = 아무 데도 남지 않았다 / 반환 = 어딘가엔 남았다** |
| D-19 | `PARTIAL` 은 **200**, 락 충돌은 **409** | 상태 코드의 실질적 역할은 호출자의 다음 행동을 정하는 것이다. 수동 조치가 필요한 상황에 5xx 를 주면 재시도 로직이 곧 중복 적재가 된다 |
| **D-21** | FTP 업로드를 **준비 → 확정 단계로 이동** | 실측이 전제를 깨뜨렸다. 서버가 rename 을 거부하고(`451`) Oracle 은 `DELETE` 권한이 없다(`ORA-01031`). **보상 트랜잭션 설계의 첫 질문은 "보상이 이 환경에서 가능한가" 이며**, 불가능하면 준비 단계의 부수효과를 없애야 한다 |
| **D-22** | 배치 멱등성의 근거는 **조회 조건 + 트랜잭션 경계** | 초판의 "PK 위반은 이미 처리된 건으로 스킵" 을 철회했다. `SHIPMENT_ID` 를 우리가 채번하므로 PK 위반은 "이미 처리됨" 이 아니라 **채번 카운터가 손상됐다**는 신호다. 스킵하면 그 손상을 조용히 덮는다 |
| **D-23** | 배치는 **치명적 위반을 두지 않는다** | 실시간엔 고쳐 줄 호출자가 있고 배치엔 없다. 전체 거부는 잘못된 1건이 정상 99건을 영구히 막는다 |
| **D-26** | 청크 반복은 **keyset 페이징** | 스킵된 행이 `'N'` 으로 남아 다시 조회되므로 "0건까지 반복" 은 무한 루프이고, `OFFSET` 은 아직 처리하지 않은 행을 건너뛴다. 커서는 영속화하지 않는다 |

---

## 운영 관점 (과제 3.3)

### 실행 이력 로그

애플리케이션 로그와 **분리**해 `logs/interface/interface-yyyyMMdd.log` 로 남긴다.

```powershell
Get-Content -Wait -Tail 20 logs\interface\interface-$(Get-Date -Format yyyyMMdd).log -Encoding UTF8
```

> `-Encoding UTF8` 을 빠뜨리지 않는다. PowerShell 5.1 은 BOM 없는 UTF-8 파일을 시스템 코드페이지로
> 읽어 **멀쩡한 이력의 한글을 깨뜨려 보여 준다.** 파일은 정상인데 화면만 깨지는 것이라 오진하기 쉽다.
> macOS · Linux 는 `tail -f -n 20 logs/interface/interface-$(date +%Y%m%d).log` 로 옵션 없이 된다.

한 실행이 이렇게 남는다.

```
START → SENDER → VALIDATOR → MAPPER
      → RECEIVER_JDBC(PREPARE) → RECEIVER_FTP(PREPARE)
      → RECEIVER_JDBC(COMMIT)  → RECEIVER_FTP(COMMIT)  → END
```

**이 순서 자체가 보상 트랜잭션 설계의 증거다.** 수신처마다 `PREPARE` 와 `COMMIT` 을 나눈 이유(D-17)도 여기 있다 — 합치면 *"업로드까지는 됐는데 확정에서 죽었다"* 와 *"업로드부터 실패했다"* 를 구분할 수 없고, 전자는 수동 조치·후자는 재시도로 조치가 정반대다.

열은 고정폭이다. 눈으로 세로로 훑기 위해서다. `키=값` 형식은 파싱은 쉬워도 사람이 못 읽는다.

**개인정보는 마스킹이 아니라 애초에 싣지 않는다.** 건수·구간·결과·코드만 남기므로 스키마에 이름·주소가 들어갈 자리가 없다. 검증 위반 기록도 값이 아니라 규칙·필드·위치만 담는다.

### 에러 코드

대역 구분의 목적은 **"누구에게 연락할지"** 다.

```
1xxx  메시지 자체의 문제   (송신 시스템 / 데이터)
2xxx  JDBC 구간
3xxx  FTP 구간
4xxx  공통 운영 (배치 제어 · 채번 · 전달 조율 · 파이프라인)
```

코드마다 **재시도 가능 여부**를 함께 들고 있다. 호출부가 매번 판단하면 검증 실패를 무한 재시도하는 사고가 난다.

같은 FTP 업로드 실패라도 **준비 단계면 재시도 가능(`EAI-3002`), 확정 단계면 불가(`EAI-3005`)** 로 코드를 나눴다. 준비 단계 실패는 DB 가 함께 롤백되지만 확정 단계 실패는 이미 63행이 들어간 뒤라 사람이 파일 하나만 올리면 되는 상황이기 때문이다.

같은 이유로 락 관련 코드도 둘이다 — `EAI-4001`(락이 잡혀 있다 = 정상 동작, 409)과 `EAI-4006`(락을 확인할 수 없다 = 인프라 장애, 503)은 조치가 정반대다. 하나로 묶으면 Redis 장애가 정상처럼 보인다.

전체 목록은 정의서 5.2 참조.

---

## 개발 환경과 AI 활용 내역 (과제 5.1 요구 항목)

### 개발 환경

| 구분 | 도구 |
|---|---|
| IDE | IntelliJ IDEA |
| 빌드 | Gradle (Wrapper 동봉, toolchain 으로 JDK 21 강제) |
| 형상관리 | Git / GitHub — 작업 단위 커밋 |
| 로컬 인프라 | Docker Desktop (Redis 7.4) |
| DB 클라이언트 | DBeaver — 원격 Oracle 19c 조회 |
| FTP 클라이언트 | FileZilla — 업로드 결과 확인 (문자셋 UTF-8 강제) |
| API 호출 | curl |
| 테스트 | JUnit 5 · Mockito (Mockito 5 엄격 모드) |
| AI | **Claude (Opus)** — 웹 대화 + MCP 파일시스템 서버 |

AI 는 **Claude 하나만** 썼다. 여러 개를 섞지 않은 것은, 같은 맥락을 이어서 판단을 검증받는 쪽이 낫다고 봤기 때문이다. 대화가 끊기면 앞서 정한 설계 결정을 다시 설명해야 하고, 그때 생기는 미묘한 어긋남이 일관성 없는 코드로 나타난다.

### AI 활용

**도구를 쓴 것이 아니라 작업 방식을 정해 놓고 들어갔다.** 역할을 셋으로 나눴다.

| 주체 | 담당 |
|---|---|
| 사람 | 무엇을 만들지 · 무엇을 포기할지 결정, 빌드 · git · 원격 DB/FTP 접속 · 실행 검증 |
| Claude (Opus, 웹) | 설계 논의 상대, 코드 작성, 문서화 |
| 사람 | 돌려 보고 확인 |

구체적으로 한 것:

1. **프로젝트 디렉터리를 MCP(Model Context Protocol) 파일시스템 서버로 연결**해 파일 생성·편집을 위임했다. 채팅창에서 코드를 복사해 붙여넣는 방식이 아니다. 대신 셸 실행 권한은 주지 않았다 — 빌드·git·원격 접속은 전부 직접 했다.
2. **대화를 그때그때 설계 결정으로 옮겨 적었다.** 26건 전부 근거가 함께 있고, 그중 **D-22 는 내가 세웠다가 스스로 철회한 것**이다. 처음엔 "PK 가 충돌하면 이미 처리된 건으로 보고 넘어가자" 였는데, 번호를 우리가 직접 채번한다는 사실 때문에 충돌은 넘어갈 일이 아니라 **채번이 망가진 신호**라는 것을 깨닫고 뒤집었다.
3. **아키텍처 다이어그램은 Mermaid 를 쓰다가 버리고 SVG 를 직접 작성**했다. 서브그래프 경계를 넘는 화살표가 있으면 `direction` 지시가 무시돼 계층도가 가로로 늘어지는 것을 실측하고 내린 판단이다.

AI 가 만든 결과를 그대로 받지 않았다는 근거로 위 2번을 든다. **이해하지 못한 결정은 뒤집을 수 없다.**

---

## 보안 취급

복호화 키의 탐색 공간은 국내 휴대폰 번호 약 1억 개로, 암호문을 확보하면 전수 대입이 현실적이다. 따라서 응답 JSON 은 **평문 크리덴셜과 동일 등급**으로 취급한다.

- 접속정보·인증정보는 소스와 커밋 이력에 남기지 않는다
- 커밋 전 확인: `git status --ignored`
- 로그에 개인정보(`NAME`, `ADDRESS`)를 남기지 않는다
- 복호화 결과는 콘솔에 앞 12자만 출력한다

## FTP 업로드 디렉터리 참고

영수증 파일은 과제 제공 FTP 서버의 `/Recruit/2026` 에 올라간다. 이 디렉터리에
`INSPIEN_이동기_20260730015842.txt.tmp` 가 하나 남아 있다.

초판은 `.tmp` 로 업로드한 뒤 rename 으로 확정하는 보상 트랜잭션이었고, 위 파일은
그 방식으로 실행했던 시점의 흔적이다. 실측 결과 서버가 rename 과 삭제를 모두 거부해
회수할 수 없다.

```
RNFR  →  451 Rename/move failure: Operation not permitted
DELE  →  550 Could not delete ...: Operation not permitted
```

같은 원칙이 Oracle 에도 걸려 있다 (`DELETE` 권한 없음 — `ORA-01031`).
지원자 전원이 같은 테이블과 같은 디렉터리를 공유하므로 서로의 제출물을 건드리지
못하게 막아 둔 구성으로 보인다 — **대상 환경 전체가 append-only** 이다 (정의서 B10).

이 제약 때문에 업로드를 **준비 단계에서 확정 단계로 옮겼다** (설계 결정 D-21).
준비 단계는 접속과 내용 생성까지만 하고 서버에 아무것도 쓰지 않으며,
확정 시점에 **최종 파일명으로 한 번 업로드**한다. 되돌릴 수 없는 환경에서는
보상 로직을 정교하게 짜는 것이 아니라 **되돌릴 상태를 만들지 않는 쪽**으로 가야 한다.

> **확인 대상 파일은 `.tmp` 가 붙지 않은 `INSPIEN_{지원자명}_{yyyyMMddHHmmss}.txt` 이다.**

재미있는 것은 같은 제약이 반대편에서는 **보장**으로 작동한다는 점이다. 배치가 읽은 행이 사라질 수 없으므로, PK 기준 `UPDATE` 는 반드시 1행이라고 단언할 수 있다. 환경을 추측하지 않고 실측한 값이 한쪽에서는 제약으로, 다른 쪽에서는 정합성 근거로 쓰였다.

---

## 문서

| 문서 | 내용 |
|---|---|
| [docs/interface-spec.md](docs/interface-spec.md) | 인터페이스 정의서 v1.2 — 매핑 규칙, 검증 기준, 에러 코드, 설계 결정 D-01~D-26, 시연 절차, **검증 이력·면접 당일 실행 순서(8장)** |
| [docs/architecture.md](docs/architecture.md) | 과제 3.4 아키텍처 (As-Is / To-Be, 보상 트랜잭션 시퀀스, 배치 청크 루프) |
| [sql/oracle-schema.reference.sql](sql/oracle-schema.reference.sql) | BOOT-001 로 실측한 대상 스키마 (참조용 — 이 프로젝트는 DDL 을 실행하지 않는다) |
| [docs/presentation-outline.md](docs/presentation-outline.md) | 발표 대본 — 결과 화면을 나열하지 않고 설계 판단을 순서대로 푸는 구성 |
| [docs/presentation-notes.md](docs/presentation-notes.md) | 설계 논의 중 나온 설명 표현 모음과 최종 덱 구성표 |

## 진행 현황

- [x] 프로젝트 뼈대 / 로컬 인프라
- [x] BOOT-000 과제 정보 수신 및 복호화
- [x] BOOT-001 연계 대상 사전 점검 (Oracle / FTP 실측)
- [x] 인터페이스 정의서 v1.2 확정 (설계 결정 D-01~D-26 + 검증 이력)
- [x] EAI 코어 (Message / Sender / Validator / Mapper / Coordinator / Receiver / InterfaceLogger)
- [x] 채번 (Redis `INCRBY` 전량 선점 + `MAX(ID)` 시딩)
- [x] IF-ORD-001 — XML 파서 · Validator · Mapper
- [x] IF-ORD-001 — JDBC Receiver (`ORDER_TB`, 커밋 보류)
- [x] IF-ORD-001 — FTP Receiver (영수증 파일, 최종명 업로드 + 이름·크기 검증)
- [x] IF-ORD-001 — DeliveryCoordinator · IntegrationFlow · 주문 REST API
- [x] IF-ORD-001 — **end-to-end 검증 완료** (63행 적재 / 11건 스킵 / 영수증 전송)
- [x] IF-SHP-001 — JDBC Polling Sender · Validator · Mapper · Receiver · 분산 락 · 스케줄러 · 수동 트리거
- [x] IF-SHP-001 — 단위 테스트 65건 (전체 223건 통과)
- [x] To-Be 아키텍처 다이어그램 (과제 3.4 제출물)
- [x] IF-SHP-001 — **end-to-end 검증 완료** (126행 전건 처리 / 2청크 / 멱등성·겹침 방지·스케줄러 확인 — 정의서 8.2)
- [x] 면접 발표 자료 18장 (대본은 `docs/presentation-outline.md`, 설명 표현 모음은 `docs/presentation-notes.md`)
- [x] 시연 절차 확정 (정의서 7장 · 8.3 면접 당일 실행 순서)
