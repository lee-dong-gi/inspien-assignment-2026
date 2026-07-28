# INSPIEN EAI 과제 2026

시스템 간 연계(EAI) 구조를 Sender / Mapper / Receiver 3계층으로 구현한 미니 연계 엔진.

> 이 프로젝트는 "주문 REST API 서버"가 아니라 **연계 엔진**이다.
> 주문·운송 도메인 로직을 소유하지 않고, **수신 → 변환 → 전달**만 수행한다.

## 인터페이스 목록

| IF-ID | 명칭 | 유형 | 주기 | Sender | Receiver |
|---|---|---|---|---|---|
| `IF-ORD-001` | 주문 생성 연계 | Real-time (SYNC) | 요청 시 | REST (XML) | JDBC(ORDER_TB) + FTP(영수증) |
| `IF-SHP-001` | 운송사 전송 배치 | Batch (ASYNC) | 5분 | JDBC Polling | JDBC(SHIPMENT_TB) + STATUS Update |

제어 평면 — 데이터 평면이 아니다. 주문 데이터가 흐르는 경로가 아니라 엔진 기동 전에 설정과 대상 스펙을 확보하는 경로이므로 별도 패키지·별도 실행 경로로 분리했다.

| 채널 | 명칭 | 성격 |
|---|---|---|
| `BOOT-000` | 과제 정보 수신 | 1회성 설정 프로비저닝 |
| `BOOT-001` | 연계 대상 사전 점검 | 읽기 전용 접속·스펙 확인 |

상세 매핑 규칙·검증 기준·설계 결정은 **[docs/interface-spec.md](docs/interface-spec.md)** 에 있다. 이 README 와 어긋나면 정의서를 따른다.

## 기술 스택

| 구분 | 선택 | 근거 |
|---|---|---|
| Language | Java 21 | — |
| Framework | Spring Boot 3.5 | REST Sender, 스케줄러, 트랜잭션 |
| DB | **Oracle 19c** (원격, 과제 제공) | BOOT-001 실측. 드라이버 `ojdbc11` |
| FTP | 평문 FTP (`commons-net`) | BOOT-001 실측 |
| Cache | Redis | ORDER_ID 원자 채번(INCR), 배치 분산 락 |
| Infra | Docker Compose | 로컬 Redis |

과제 가이드의 *"필요한 라이브러리만 이용"* 에 따라 사용하지 않는 스타터는 넣지 않는다. `spring-boot-starter-web` 은 화면(UI)용이 아니라 **시나리오 1의 REST Sender 자체**이므로 필수 의존성이다.

---

## 실행 순서

### 1. 로컬 인프라 기동

```bash
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

`application-local.yml` 은 `.gitignore` 대상이다. 환경변수(`INSPIEN_API_USERNAME` 등)로 주입해도 된다.

### 3. 복호화 규격 검증 (외부 호출 전)

```bash
gradlew test --tests "*CredentialDecryptorTest"
```

외부 API 를 때리기 전에 복호화 로직 자체가 맞는지 먼저 확인한다. 여기가 통과해야 실패 원인을 "설정 값" 으로 좁힐 수 있다.

### 4. BOOT-000 — 과제 정보 수신 및 복호화

```bash
gradlew bootstrapRun --args="--inspien.bootstrap.source=api"   # 최초 1회, 실제 호출
gradlew bootstrapRun                                           # 이후 저장된 원문 재사용 (기본값)
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

```bash
gradlew probeRun
```

명세서가 아니라 **실물**을 기준으로 삼기 위한 읽기 전용 점검이다. 대상 시스템의 상태는 바꾸지 않는다.

- **Oracle** — 버전, 캐릭터셋/길이 의미론, 컬럼 정의(타입·길이·NULL·DEFAULT), PK 구성, 서버 시각, 기존 행 수
- **FTP** — 프로토콜 판정, 업로드 디렉터리 존재, UTF8 지원 선언

여기서 확인된 값으로 인터페이스 정의서를 v1.0(확정)으로 갱신했다.

---

## 보안 취급

복호화 키의 탐색 공간은 국내 휴대폰 번호 약 1억 개로, 암호문을 확보하면 전수 대입이 현실적이다. 따라서 응답 JSON 은 **평문 크리덴셜과 동일 등급**으로 취급한다.

- 접속정보·인증정보는 소스와 커밋 이력에 남기지 않는다
- 커밋 전 확인: `git status --ignored`
- 로그에 개인정보(`NAME`, `ADDRESS`)를 남길 때는 마스킹한다

## 진행 현황

- [x] 프로젝트 뼈대 / 로컬 인프라
- [x] BOOT-000 과제 정보 수신 및 복호화
- [x] BOOT-001 연계 대상 사전 점검 (Oracle / FTP 실측)
- [x] 인터페이스 정의서 v1.0 확정
- [x] EAI 코어 (Message / Sender / Mapper / Receiver / Flow / InterfaceLogger)
- [x] 채번 (Redis `INCRBY` 전량 선점 + `MAX(ORDER_ID)` 시딩)
- [x] IF-ORD-001 — XML 파서 · Validator · Mapper
- [x] IF-ORD-001 — JDBC Receiver (`ORDER_TB`, 커밋 보류)
- [ ] IF-ORD-001 — FTP Receiver (영수증 파일)
- [ ] IF-ORD-001 — IntegrationFlow · 주문 REST API
- [ ] IF-SHP-001 운송사 전송 배치
- [ ] To-Be 아키텍처 다이어그램 · 발표 자료
