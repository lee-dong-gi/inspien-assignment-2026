# 인터페이스 정의서 (Interface Specification)

> 인스피언 신입/경력 개발자 과제 — 연계 인터페이스 매핑 명세

| 항목 | 내용 |
|---|---|
| 문서 버전 | **1.0 (확정)** |
| 근거 | `신입경력개발자_과제.pdf` 3.1 / 3.2 / 3.3 / 4.1 + **BOOT-000 / BOOT-001 실측** |
| 확정 방식 | 명세서가 아니라 **실물 조회 결과**를 기준으로 삼는다 |

> ⚠️ 이 문서에는 접속정보·인증정보·APPLICANT_KEY 실제 값을 기재하지 않는다. 형식과 구조만 기술한다.

---

## 0. 인터페이스 목록

| IF-ID | 명칭 | 유형 | 주기 | Sender | Mapper | Receiver |
|---|---|---|---|---|---|---|
| `IF-ORD-001` | 주문 생성 연계 | Real-time (SYNC) | 요청 시 | REST (XML) | XML → Flat Order | JDBC(ORDER_TB) + FTP(영수증) |
| `IF-SHP-001` | 운송사 전송 배치 | Batch (ASYNC) | 5분 | JDBC Polling | Order → Shipment | JDBC(SHIPMENT_TB) + STATUS Update |

제어 평면 (데이터 평면 아님):

| 채널 | 명칭 | 성격 |
|---|---|---|
| `BOOT-000` | 과제 정보 수신 | 1회성 설정 프로비저닝 |
| `BOOT-001` | 연계 대상 사전 점검 | 읽기 전용 접속·스펙 확인 |

---

## 1. 연계 대상 환경 (실측 확정)

### 1.1 Database

| 항목 | 값 | 근거 |
|---|---|---|
| DBMS | **Oracle Database 19c Enterprise Edition** | BOOT-001 |
| 드라이버 | `ojdbc11` 23.4.0.24.05 | |
| URL 형식 | `jdbc:oracle:thin:@…` | BOOT-000 복호화 |
| 스키마 소유자 | `RECRUIT` (시노님/권한으로 무자격 접근 가능) | BOOT-001 |
| `NLS_CHARACTERSET` | `AL32UTF8` | 한글 1자 = 3바이트 |
| `NLS_LENGTH_SEMANTICS` | **`BYTE`** | 길이 검증 기준이 문자 수가 아님 |
| `DBTIMEZONE` / `SESSIONTIMEZONE` | `+09:00` / `Asia/Seoul` | 시연 날짜 조회 안전 |
| ORDER_TB / SHIPMENT_TB | **동일 인스턴스·동일 계정** | 단일 트랜잭션 가능 |

> ORDER_TB 와 SHIPMENT_TB 의 접속정보가 완전히 동일하다는 사실은 **암호문 비교**로 먼저 확인했다.
> AES-ECB 는 결정적이므로 동일 평문이면 동일 암호문이 나온다. (동시에 ECB 가 운영에서 권장되지 않는 이유이기도 하다.)

### 1.2 FTP

| 항목 | 값 |
|---|---|
| 프로토콜 | **평문 FTP** (Pure-FTPd, `AUTH TLS` 지원하나 필수 아님) |
| 포트 | 30021 (서버 내부 21 로 포워딩) |
| 라이브러리 | `commons-net` 3.11.1 |
| 업로드 경로 | `Recruit/2026/` → 절대경로 `/Recruit/2026` |
| 전송 모드 | binary, passive |
| `FEAT UTF8` | **지원 선언함** |

### 1.3 확정된 사전 조사 항목

| # | 항목 | 확정값 |
|---|---|---|
| B1 | JDBC 스킴 | `jdbc:oracle:thin` — `LIMIT` 없음, `FETCH FIRST` 사용 |
| B2 | 접속정보 필드 | DB `URL`/`ID`/`PASSWORD`/`TABLE`, FTP `URL`/`PORT`/`ID`/`PASSWORD`/`PATH` |
| B3 | 동일 인스턴스 | **예** |
| B4 | FTP / SFTP | 평문 FTP |
| B5 | 업로드 디렉터리 | `Recruit/2026/` |
| B6 | APPLICANT_KEY | 8자 |
| B7 | XML 선언부 | **없음** — 인코딩을 코드가 알고 있어야 함 |
| B8 | 루트 엘리먼트 | **없음** — 파싱 전 `<ROOT>` 래핑 필수 |
| B9 | 샘플 건수 | HEADER 15 / ITEM 70, 최대 1:10 |

---

## 2. BOOT-000 — 과제 정보 수신

### 2.1 응답 구조 (실측)

```
{
  "APPLICANT_KEY"    : 평문 8자,
  "ORDER_TB_CONN"    : { URL, ID, PASSWORD, TABLE },      // 값마다 개별 AES-128 암호화
  "SHIPMENT_TB_CONN" : { URL, ID, PASSWORD, TABLE },
  "FTP_CONN"         : { URL, PORT, ID, PASSWORD, PATH },
  "SAMPLE_DATA"      : Base64(EUC-KR XML)                 // 암호화 대상 아님
}
```

> **암호화 단위는 블록이 아니라 블록 안의 개별 필드다.** (Base64 24자=16바이트, 64자=48바이트 — 모두 AES 블록 배수)

### 2.2 복호화 규격

```
key    = SHA-1( PHONE_NUMBER.getBytes("UTF-8") )[0..15]
cipher = AES/ECB/PKCS5Padding
plain  = cipher.doFinal( Base64.decode(field) )
```

`SAMPLE_DATA` 는 Base64 디코드 후 **EUC-KR** 로 문자열화한다. UTF-8 로 읽으면 한글이 전부 깨지고 그대로 FTP 영수증까지 전파된다.

### 2.3 파싱 시 주의 (실패 경험 기록)

세 접속정보 블록이 `URL` · `ID` · `PASSWORD` 라는 **동일한 키 이름**을 공유한다.
응답 트리를 전역 평탄화하면 먼저 만난 블록의 값만 남고 나머지는 **조용히 소실**된다.
초판 구현이 이 함정에 빠졌고, 필수 필드 누락으로 즉시 실패(fail-fast)했기에 드러났다.
→ **블록 스코프를 보존하는 명시적 추출**로 교체함 (`ConnBlock`).

### 2.4 보안 취급

복호화 키의 탐색 공간은 국내 휴대폰 번호 약 1억 개다. 암호문을 확보하면 전수 대입이 현실적이므로,
**응답 JSON 은 평문 크리덴셜과 동일 등급**으로 취급한다. `secrets/` 전체가 `.gitignore` 대상.

---

## 3. IF-ORD-001 — 주문 생성 연계 (Real-time / SYNC)

### 3.1 개요

| 항목 | 내용 |
|---|---|
| 트리거 | 주문자의 주문 생성 API 호출 |
| 입력 | XML (EUC-KR, 선언부·루트 없음) |
| 출력 | Oracle INSERT + 구분자 텍스트 파일 FTP 전송 |
| 응답 | JSON |
| 성격 | 동기. **JDBC · FTP 양쪽 모두 성공해야 성공 응답** |

### 3.2 소스 구조

`HEADER : ITEM = 1 : N`, 조인 키 `USER_ID`.

**샘플 데이터 실측**

```
HEADER 15건 / ITEM 70건
  ├─ 정상 매칭 : USER01~USER11 (11건) → ITEM 63건  (최대 USER09 = 10건)
  ├─ 고아 ITEM : USER16~USER22 (7건) — 대응 HEADER 없음
  └─ 빈 HEADER : USER12~USER15 (4건) — 대응 ITEM 없음
```

- HEADER `USER_ID` 중복 0건 → 조인 모호성 없음
- **ITEM 이 HEADER 순서대로 정렬돼 있지 않다.** 문서 순서 의존 파싱은 즉시 깨진다 — 반드시 `USER_ID` 로 조인
- 전 필드 공백/개행 없음(`TrimDiff=0`), 빈 값 없음. `STATUS` 는 15건 모두 `N`
- 그럼에도 `trim` 은 방어적으로 유지한다 (PDF 예시에는 개행이 포함돼 있었다)

### 3.3 유효성 검증 — 두 범주로 분리

| 범주 | 규칙 | 성격 | 처리 |
|---|---|---|---|
| **구조 오류** | V-01 HEADER 필수 필드<br>V-02 ITEM 필수 필드<br>V-05 PRICE 숫자 형식<br>V-06 길이 초과 | 메시지 자체가 깨짐 | **요청 전체 거부**. Receiver 호출 전 차단 |
| **정합성 불일치** | V-03 고아 ITEM<br>V-04 ITEM 없는 HEADER | 메시지는 정상, 대응 대상 없음 | **건 단위 스킵 + 경고 로그**, `RESULT=PARTIAL` |

> **왜 부분 처리인가 (D-02).** 과제가 제공한 샘플에 이상 데이터 11건이 의도적으로 심어져 있다.
> 전체 거부를 택하면 샘플로는 **단 한 건도 적재할 수 없다.** 송신 시스템의 데이터 불완전성 때문에
> 정상 63건까지 멈추는 것은 EAI 의 태도가 아니다.
> 단, **버린 건수를 반드시 드러낸다.** 조용히 63건만 넣고 성공이라 답하는 것이 진짜 사고다.

**V-06 길이 기준** — 대상 컬럼이 전부 `VARCHAR2(100 BYTE)` 이므로 **UTF-8 바이트 길이 100** 이 상한이다.

| 필드 | 샘플 최대 Char | 샘플 최대 **Byte** | 상한 |
|---|---|---|---|
| USER_ID / ITEM_ID / PRICE | 6 | 6 | 100 |
| NAME | 3 | 9 | 100 |
| ADDRESS | 10 | 28 | 100 |
| ITEM_NAME | 7 | 21 | 100 |

### 3.4 카디널리티

```
HEADER 1건 × 해당 USER_ID 의 ITEM n건  →  ORDER_TB n행  =  영수증 파일 n라인
샘플 기준: 63행 / 63라인 (70건 중 7건 스킵)
```

조인 후 평탄화(flatten)가 Mapper 의 핵심 로직이다.

### 3.5 Target A — ORDER_TB (JDBC Receiver)

| # | 컬럼 | 타입 | Source | 규칙 |
|---|---|---|---|---|
| 1 | `ORDER_ID` | VARCHAR2(100) **PK** | — | 자체 채번 `[A-Z][0-9]{3}` |
| 2 | `APPLICANT_KEY` | VARCHAR2(100) **PK** | BOOT-000 | 전 행 고정값 |
| 3 | `USER_ID` | VARCHAR2(100) | HEADER | 원본 유지 |
| 4 | `ITEM_ID` | VARCHAR2(100) | ITEM | 원본 유지 |
| 5 | `NAME` | VARCHAR2(100) | HEADER | 원본 유지 |
| 6 | `ADDRESS` | VARCHAR2(100) | HEADER | 원본 유지 (내부 공백 보존) |
| 7 | `ITEM_NAME` | VARCHAR2(100) | ITEM | 원본 유지 |
| 8 | `PRICE` | VARCHAR2(100) | ITEM | **문자열 그대로.** 숫자 검증만, 포맷 변경·콤마 삽입 금지 |
| 9 | `STATUS` | VARCHAR2(100) | HEADER | trim 후 `N` |
| 10 | `CREATE_TIME` | TIMESTAMP(6) | — | **DEFAULT `SYSDATE`. INSERT 목록에서 제외한다** |

- PK: `(ORDER_ID, APPLICANT_KEY)` 복합
- Batch INSERT, 파라미터 바인딩 필수(문자열 결합 SQL 금지). 실패 시 전체 롤백
- 배치 결과 검증은 **합산이 아니라 개수**로 한다. JDBC 는 성공을 `SUCCESS_NO_INFO`(`-2`) 로 돌려줄 수 있어, 합산하면 멀쩡한 적재가 실패로 뒤집힌다
- 테이블명은 바인딩할 수 없어 유일하게 문자열로 조립되는 값이다. 출처가 BOOT-000 산출물이라도 식별자 규격 검증을 거친다

### 3.6 Target B — 영수증 파일 (FTP Receiver)

**파일명**

```
INSPIEN_{참여자명}_yyyyMMddHHmmss.txt
```

- 참여자명 **한글**, 타임스탬프는 인터페이스 실행 시작 시각(전 라인 동일)
- **파일명 인코딩: UTF-8** (근거는 3.7)

**라인 포맷** — 8필드, 구분자 `^`, 종결 `\n`

```
ORDER_ID ^ USER_ID ^ ITEM_ID ^ APPLICANT_KEY ^ NAME ^ ADDRESS ^ ITEM_NAME ^ PRICE \n
```

**확정 사항 (위반 시 감점)**

- `STATUS` 는 파일에 **포함되지 않는다** (DB 9필드 / 파일 8필드)
- **필드 순서가 DB 컬럼 순서와 다르다.** DB 3·4번은 `USER_ID`·`ITEM_ID` 이나 파일은 2·3·4번이 `USER_ID`·`ITEM_ID`·`APPLICANT_KEY` 다. DB 컬럼 순서를 그대로 join 하면 오답
- 구분자 `^`, 종결자 `\n` 고정. 마지막 라인에도 `\n` 부여
- 값에 구분자·개행이 섞이면 **실패시킨다**. 이 포맷에는 이스케이프 규칙이 없어, 그대로 내보내면 수신 측이 **정상 파일로 파싱해** 엉뚱한 값을 배송 정보로 쓴다
- 대상 인코딩으로 표현할 수 없는 문자도 **사전 차단**한다. `String.getBytes()` 는 그런 문자를 예외 없이 `?` 로 바꾸며, 이는 파일명이 `?` 로 깨진 것과 **정확히 같은 종류의 사고**다
- 전송 모드 **binary** (ASCII 모드는 개행 변조 위험)
- **파일 내용 인코딩: EUC-KR** (D-07)

### 3.7 인코딩 결정 — 파일명과 내용은 별개다

| 대상 | 결정 | 근거 |
|---|---|---|
| 파일 **이름** | **UTF-8** | 서버가 `FEAT` 에서 UTF8 지원을 선언한다 |
| 파일 **내용** | **EUC-KR** (설정 외부화) | 원본 XML 인코딩 계승 |

> **기존 파일 실측.** 업로드 디렉터리의 53개 파일 중 한글 이름은 전부 `INSPIEN_???_...` 형태다.
> UTF-8 · EUC-KR 양쪽으로 리스팅했을 때 **비ASCII 문자가 0자**로 나왔다 —
> 즉 서버에 저장된 바이트 자체가 리터럴 물음표(`0x3F`)다. 우리가 잘못 읽은 것이 아니라,
> **다른 지원자의 클라이언트가 인코딩 불가 문자를 `?` 로 치환한 채 업로드한 것**이다.
> (`INSPIEN_KDH00020_...` 처럼 한글을 아예 포기한 파일도 섞여 있다.)
>
> 서버는 UTF8 을 지원하므로 우리는 UTF-8 로 업로드하고, **업로드 후 리스팅하여
> 비ASCII 문자 수가 0 이 아닌지 검증**한다. 검증까지 자동화하는 것이 이 결정의 핵심이다.

**구현 요건 (원인 규명).** 다른 지원자의 파일이 `?` 가 된 원인은 서버가 아니라 클라이언트다.
`commons-net` 의 제어 채널 인코딩 기본값은 `ISO-8859-1` 이라, 여기에 한글 파일명을 실으면
인코딩 불가 문자가 `0x3F`(`?`)로 치환된 채 전송된다. **예외도 나지 않고 조용히 깨진다.**
따라서 FTP Receiver 는 다음을 **로그인 전**에 수행한다.

| 순서 | 조치 |
|---|---|
| 1 | `ftp.setControlEncoding("UTF-8")` — 기본값 `ISO-8859-1` 을 반드시 덮어쓴다 |
| 2 | `FEAT` 응답에 `UTF8` 이 있으면 `OPTS UTF8 ON` 전송 |
| 3 | 로그인 → `setFileType(BINARY_FILE_TYPE)` → `enterLocalPassiveMode()` |
| 4 | 업로드 후 리스팅해 파일명의 비ASCII 문자 수 > 0 검증. 0 이면 실패로 처리 |

> **구현은 이보다 강하게 한다.** "비ASCII > 0" 대신 서버가 돌려준 목록에
> 우리가 보낸 이름과 **완전히 같은 이름이 있는지**를 본다. 치환·절단·정규화 중
> 무엇이 일어나도 걸리며, 참여자명이 영문인 경우에도 오판이 나지 않는다.
>
> 검증 위치는 **확정 이전**이다. `.tmp` 를 올린 직후에 확인하므로 실패하면 아직 되돌릴 수 있고,
> DB 도 함께 롤백된다. 확정 뒤에 발견했다면 수동 조치 대상이 됐을 것이다.

> 4번을 넣는 이유: 인코딩 사고는 **예외 없이 성공으로 보고된다.**
> 스스로 확인하지 않으면 면접 시연 자리에서 처음 알게 된다.

### 3.8 ORDER_ID 채번

**과제가 지시한 것과 우리가 정한 것을 구분한다.**

| 항목 | 결정 | 출처 |
|---|---|---|
| 형식 `[A-Z][0-9]{3}` | 대문자 1 + 숫자 3 (`A113`) | **과제 PDF p.5 명시** (`SHIPMENT_ID` 는 p.6, "자동 채번이 아니므로 지원자가 삽입") |
| 공간 26 × 1,000 = **26,000개** | 유한함을 인지하고 소진을 설계에 포함 | 형식에서 유도. PDF 는 공간 크기·소진 처리를 언급하지 않음 |
| 부여 단위 = **주문 라인(행)** | 헤더 단위 아님 | **우리 결정 (D-03).** PK 가 `(ORDER_ID, APPLICANT_KEY)` 이고 `APPLICANT_KEY` 는 전 행 고정값이므로, 한 HEADER 에 ITEM 이 2건이면 행 단위 외의 선택지가 PK 위반이다 |
| 진행 방식 = **순차 증가** | 무작위 아님 | 우리 결정. 재현성·추적성 우위 |
| 구현 = Redis `INCRBY` | 배치 단위 선점 | 우리 결정 (D-09) |

> **근거 정정.** 이전 판은 "샘플의 `A113`/`B114` 가 행 단위를 지시한다" 고 적었으나 이는 근거로 쓸 수 없다.
> PDF 예시는 USER1 · USER2 가 각각 ITEM 1건씩인 **1:1 케이스뿐**이라 행 단위와 헤더 단위를 구분해 주지 못한다.
> 논리적으로 닫힌 근거는 위 표의 **PK 제약**이며, 샘플은 그것과 모순되지 않는다는 방증에 그친다.

**채번 순서 (Redis `INCRBY`)**

```
end   = INCRBY eai:seq:order <행 수>      // 63행이면 왕복 1회로 63개 선점
start = end - 행 수 + 1
index = start-1 … end-1                   // 0-based
ORDER_ID = ('A' + index/1000) + %03d(index%1000)
```

- 행마다 `INCR` 을 부르지 않는다. 63왕복이 1왕복이 되는 것보다, **전량 선점에 실패하면 한 건도 만들지 않는다**는 성질이 중요하다
- 사전식 정렬 순서 = 채번 순서 (`A000` < `A999` < `B000` < `Z999`). 앞자리 문자 + 고정 3자리 제로패딩이므로 성립하며, 이 성질 덕에 `MAX(ORDER_ID)` 를 카운터 복원 기준으로 쓸 수 있다

**공간 소진 (26,000 도달)**

| 대상 | 처리 |
|---|---|
| 운영 | `EAI-4003 ID_SPACE_EXHAUSTED` 로 **실패**. 재시도 불가로 분류하고 되감기(`DECRBY`)하지 않는다 |
| 개발 | 코드가 아니라 **별도 도구**로 리셋 — `tools/reset-sequence.ps1` |

> 되감지 않는 이유: 실패한 요청이 번호를 태우는 손해보다, 되감기와 다른 요청의 선점이 경합해
> **같은 번호가 두 번 발급되는 사고**가 비교할 수 없이 크다. 이미 소진된 시점이라 태울 번호도 남아 있지 않다.
>
> 리셋을 코드에 두지 않는 이유: 카운터를 되돌리는 경로가 애플리케이션 안에 있으면
> 언젠가 설정 실수로 운영에서 실행된다. 개발 편의는 운영 경로 밖에 둔다.

**Redis 데이터 유실 대비 (폴백 재설계)**

초판은 "Redis 미가용 시 DB 시퀀스 테이블" 을 폴백으로 적었으나 **철회한다.**
시퀀스 테이블을 만드는 것은 대상 스키마에 손을 대는 일이고, 이는 이 과제의 전제인
**"기존 시스템의 스펙은 불변 조건"** 과 정면으로 충돌한다.

대신 두 가지로 나눈다.

| 상황 | 처리 |
|---|---|
| Redis 일시 단절 | `EAI-4002 ID_ISSUE_FAILED` (**재시도 가능**). 대체 채번기로 몰래 넘어가지 않는다 — 두 채번기가 동시에 살아 있으면 중복이 난다 |
| Redis 데이터 유실 (컨테이너 초기화 등) | 기동 시 `SELECT MAX(ORDER_ID) FROM ORDER_TB WHERE APPLICANT_KEY = ?` 로 카운터를 **시딩**. 읽기 전용이라 스키마를 건드리지 않는다 |

> 시딩이 있으면 Redis 는 "진실의 원천" 이 아니라 **동시성 조정 계층**이 된다.
> 진실은 이미 적재된 데이터에 있고, Redis 는 그 다음 번호를 빠르고 원자적으로 나눠 줄 뿐이다.
> 이 구도라야 `docker compose down -v` 한 번에 정합성이 깨지지 않는다.

> **정합성 요건.** 파일 라인과 DB 행은 동일한 `ORDER_ID` 를 공유해야 한다.
> Mapper 가 표준 레코드 리스트를 **한 번** 생성하고, 두 Receiver 가 그 **동일 리스트**를 각자 소비한다.
> 두 Receiver 가 각자 채번하면 정합성이 즉시 깨진다.

### 3.9 JDBC ↔ FTP 동기화 (보상 트랜잭션)

FTP 는 DB 트랜잭션에 참여할 수 없어 2PC 를 쓸 수 없다. 보상 방식으로 처리한다.

| 순서 | 동작 | 실패 시 |
|---|---|---|
| 1 | DB 트랜잭션 시작 → ORDER_TB INSERT (**commit 보류**) | 즉시 롤백, 실패 응답 |
| 2 | FTP 임시 파일명(`.tmp`)으로 업로드 | DB 롤백, 실패 응답 |
| 3 | DB commit | 업로드된 `.tmp` **삭제(보상)** 후 실패 응답 |
| 4 | FTP rename (`.tmp` → 최종 파일명) | **경고 로그 + 수동 조치 대상 기록**. 데이터는 유효하므로 롤백하지 않음 |
| 5 | 성공 응답 반환 | — |

- 보상(삭제)까지 실패하면 조용히 넘기지 않고 `RESULT=PARTIAL` 로 기록
- 대안(트랜잭셔널 아웃박스, 최종적 일관성) 비교는 발표 자료에 포함

### 3.10 응답 포맷 (JSON)

| 필드 | 설명 |
|---|---|
| `result` | `SUCCESS` / `PARTIAL` / `FAIL` |
| `ifId` | `IF-ORD-001` |
| `txId` | 추적용 트랜잭션 ID (UUID) |
| `processedCount` | 적재 성공 건수 |
| `skippedCount` | 정합성 불일치로 제외된 건수 |
| `skipDetail` | `orphanItem` / `headerWithoutItem` 별 건수 |
| `errorCode` / `errorMessage` | 실패 시 (`EAI-xxxx`) |

> 샘플 기준 기대 응답: `processedCount=63`, `skippedCount=11`, `result=PARTIAL`

---

## 4. IF-SHP-001 — 운송사 전송 배치 (Batch / ASYNC)

### 4.1 개요

| 항목 | 내용 |
|---|---|
| 트리거 | 스케줄러 5분 주기 (`fixedDelay`, cron 외부화) |
| Source | ORDER_TB (JDBC Polling) |
| Target | SHIPMENT_TB + ORDER_TB 상태 갱신 |
| 성격 | 비동기. 부분 실패 허용, 미처리 건 자연 재처리 |

시나리오 1 과 **동일한 Sender/Mapper/Receiver 파이프라인을 재사용**한다.
Sender 만 `RestSender` → `JdbcPollingSender` 로 바뀐다.

### 4.2 Source — 조회 (Oracle 문법)

```sql
SELECT ORDER_ID, ITEM_ID, ADDRESS
  FROM ORDER_TB
 WHERE APPLICANT_KEY = ?
   AND STATUS = 'N'
 ORDER BY ORDER_ID
 FETCH FIRST ? ROWS ONLY
```

- `APPLICANT_KEY` 조건 **필수**. 누락 시 타 지원자 데이터까지 조회된다
- `LIMIT` 은 Oracle 에 없다. `FETCH FIRST … ROWS ONLY` (12c+)
- 청크 크기 기본 **100**, 설정 외부화

### 4.3 Target — SHIPMENT_TB

| # | 컬럼 | Source | 규칙 |
|---|---|---|---|
| 1 | `SHIPMENT_ID` | — | 자체 채번 `[A-Z][0-9]{3}` |
| 2 | `APPLICANT_KEY` | BOOT-000 | 고정값 |
| 3 | `ORDER_ID` | ORDER_TB | 원본 유지 |
| 4 | `ITEM_ID` | ORDER_TB | 원본 유지 |
| 5 | `ADDRESS` | ORDER_TB | 원본 유지 |
| 6 | `CREATE_DATE` | — | **DEFAULT `SYSDATE`. INSERT 목록에서 제외** |

**행 대응**: ORDER_TB 1행 → SHIPMENT_TB 1행 (1:1)

> **의도적 필드 축소.** `NAME` · `ITEM_NAME` · `PRICE` · `STATUS` 는 전달하지 않는다.
> 운송사는 배송에 필요한 정보만 받으면 된다 — "수신 시스템이 필요로 하는 것만 변환해 전달"이라는
> EAI 기본 태도의 직접적 구현이다.

> **주의**: 등록일시 컬럼명이 두 테이블에서 다르다. ORDER_TB 는 `CREATE_TIME`, SHIPMENT_TB 는 `CREATE_DATE`.
> 공통 매퍼로 뭉뚱그리면 틀린다.

### 4.4 후행 처리 — 상태 갱신

```sql
UPDATE ORDER_TB SET STATUS = 'Y'
 WHERE ORDER_ID = ? AND APPLICANT_KEY = ?
```

- **적재 성공 건에 대해서만** 수행
- SHIPMENT INSERT 와 **동일 트랜잭션**. 분리하면 "적재됐는데 STATUS=N" → 다음 주기 중복 전송
- 두 테이블이 동일 인스턴스·동일 계정이므로 단일 `DataSource` 단일 트랜잭션으로 처리 가능 (B3 확정)

### 4.5 배치 운영 요건

| 항목 | 규격 |
|---|---|
| 중복 실행 방지 | Redis 분산 락 `SET key value NX PX <ttl>`. TTL 은 배치 최대 수행시간보다 길게 |
| 멱등성 | `(SHIPMENT_ID, APPLICANT_KEY)` PK 위반은 이미 처리된 건으로 간주해 스킵 |
| 부분 실패 | 실패 건은 `STATUS='N'` 유지 → 다음 주기 자연 재처리. 나머지는 진행 |
| 청크 | `FETCH FIRST 100 ROWS ONLY` + 반복. 전체를 메모리에 올리지 않는다 |
| 스케줄러 | `@Scheduled(fixedDelay)`. `fixedRate` 는 지연 시 폭주 |

---

## 5. 공통 — 운영 관점 (과제 3.3)

### 5.1 인터페이스 실행 로그

애플리케이션 로그와 **분리**하여 별도 파일로 출력한다 (요구사항: local 파일 저장).
출력 경로: `logs/interface/interface-yyyyMMdd.log`

| 항목 | 설명 |
|---|---|
| `TX_ID` | UUID. 요청 1건당 1개. MDC 로 전 구간 전파 |
| `IF_ID` | `IF-ORD-001` / `IF-SHP-001` |
| `STEP` | `SENDER` / `MAPPER` / `RECEIVER_JDBC` / `RECEIVER_FTP` |
| `START` / `END` / `ELAPSED` | 시각 및 소요 ms |
| `RESULT` | `SUCCESS` / `PARTIAL` / `FAIL` |
| `COUNT` | 성공 / 실패 / 스킵 건수 분리 |
| `ERROR` | 에러 코드 + 메시지 |

- 개인정보(`NAME`, `ADDRESS`)는 **마스킹** 후 기록
- 사전 안내의 RTIMS(실시간 모니터링 솔루션) 사상을 축소 구현한 것

### 5.2 예외 분류

| 분류 | 대상 | 처리 |
|---|---|---|
| `RetryableException` | FTP 커넥션 타임아웃, DB 일시 단절 | 지수 백오프 재시도 (최대 횟수 제한) |
| `NonRetryableException` | 유효성 검증 실패, 매핑 오류, PK 위반 | 즉시 실패 처리 |

**에러 코드**

| 코드 | 의미 |
|---|---|
| `EAI-1001` | VALIDATION_ERROR |
| `EAI-1002` | MAPPING_ERROR |
| `EAI-2001` | JDBC_CONN_ERROR |
| `EAI-2002` | JDBC_EXEC_ERROR |
| `EAI-3001` | FTP_CONN_ERROR |
| `EAI-3002` | FTP_UPLOAD_ERROR |
| `EAI-3003` | FTP_COMPENSATION_FAILED (임시 파일 삭제 실패 — 수동 조치) |
| `EAI-3004` | FTP_ENCODING_ERROR (파일명 또는 내용 인코딩 손상 — 재시도 불가) |
| `EAI-3005` | FTP_RENAME_FAILED (확정 후 이름 변경 실패 — 재시도 불가, 수동 조치) |
| `EAI-4001` | BATCH_LOCK_ACQUIRE_FAILED |
| `EAI-4002` | ID_ISSUE_FAILED (채번 실패 — 재시도 가능) |
| `EAI-4003` | ID_SPACE_EXHAUSTED (26,000 소진 — 재시도 불가) |

**타임아웃 (명시 설정 — 기본값 무한 대기가 실제 장애의 주범)**

| 대상 | 항목 | 값 |
|---|---|---|
| Oracle | `oracle.net.CONNECT_TIMEOUT` | 10s |
| Oracle | `oracle.jdbc.ReadTimeout` | 20s |
| Oracle | `Statement.setQueryTimeout` | 20s |
| Oracle | Hikari `connectionTimeout` (풀 대기) | 15s — 드라이버 connect 보다 길게 |
| FTP | connectTimeout / dataTimeout | 10s / 15s |

---

## 6. 설계 결정 기록

| ID | 쟁점 | 채택 | 근거 |
|---|---|---|---|
| D-01 | HEADER.STATUS 처리 | trim 후 `N` 로 정규화 | 배치 조회 조건이 `STATUS='N'`. 샘플은 15건 모두 `N` 이라 실질 영향 없음 |
| D-02 | 고아 ITEM / 빈 HEADER | **건 단위 스킵 + PARTIAL** | 샘플에 11건이 의도적으로 심어져 있음. 전체 거부 시 63건도 적재 불가 |
| D-03 | 채번 부여 단위·진행 방식 | **행 단위 + 순차 증가** | 행 단위는 PK `(ORDER_ID, APPLICANT_KEY)` 제약상 유일한 선택지. 순차는 재현성·추적성 우위 |
| D-04 | 두 테이블 트랜잭션 | **단일 트랜잭션** | B3 — 동일 인스턴스·동일 계정 확인됨 |
| D-05 | 배치 동시성 | **분산 락만** | 단일 인스턴스 실행. `SELECT FOR UPDATE` 는 과잉. 다중 인스턴스 확장 시 상태 선점(`N`→`P`) 도입 여지를 남김 |
| D-06 | 시연 조회 기준 | `CREATE_TIME` 활용 | DEFAULT `SYSDATE`, DB 시간대 `Asia/Seoul` → 로컬 날짜와 일치 |
| D-07 | 영수증 파일 내용 인코딩 | **EUC-KR**, 설정 외부화 | 원본 XML 인코딩 계승. 판단이 갈릴 수 있어 설정으로 분리 |
| D-08 | 로컬 Docker DB | 미사용 | 대상이 Oracle 19c 로 확정. MySQL 컨테이너는 충실한 테스트 대역이 아니며, 원격 DB 가 지원자별 `APPLICANT_KEY` 로 격리돼 있어 직접 사용해도 안전 |
| D-09 | 채번 저장소·폴백 | **Redis `INCRBY` 단일 채번기 + DB `MAX` 시딩.** DB 시퀀스 테이블 철회 | 시퀀스 테이블은 대상 스키마 변경이라 "기존 시스템 불변" 원칙 위반. 또 대체 채번기로 자동 전환하는 설계는 두 채번기가 동시에 살아 있는 순간 중복을 만든다 |
| D-10 | 공간 소진 시 | 운영은 `EAI-4003` 으로 실패, 리셋은 애플리케이션 밖 별도 도구(`tools/reset-sequence.ps1`) | 카운터를 되돌리는 경로가 앱 안에 있으면 설정 실수로 운영에서 실행된다 |
| D-11 | JDBC 트랜잭션 경계 관리 | **커넥션을 객체가 직접 보유** (`PendingCommitDelivery`). `@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 모두 미채택 | 앞 둘은 트랜잭션 경계가 **한 메서드 안에 닫힌다**는 전제인데, 우리 경계는 `prepare()`→`commit()` 이고 그 사이에 FTP 가 낀다. 콜백 안에 FTP 를 넣으면 Receiver 가 다른 Receiver 를 호출하는 구조가 돼 "수신처가 늘어도 Receiver 만 추가" 가 무너진다. 셋째는 트랜잭션을 **ThreadLocal 에 묶어** "두 호출이 같은 스레드여야 한다" 는 제약을 타입에 드러나지 않게 만든다 — FTP 를 비동기로 돌리는 순간 조용히 깨진다. **대가**: FTP 업로드 시간만큼 DB 커넥션을 점유한다 → 풀 크기 5, FTP 타임아웃 명시로 방어. 이 결합 자체를 없애려면 트랜잭셔널 아웃박스로 가야 한다(과제 범위 밖) |
| D-12 | 접속정보 주입 경로 | `spring.datasource.*` 미사용. `DataSourceAutoConfiguration` **배제** 후 `secrets/` 산출물로 직접 조립 | 자동 설정을 쓰려면 BOOT-000 이 복호화한 크리덴셜을 설정 파일로 **한 번 더 복사**해야 하고, 그 복사본이 곧 커밋 사고의 경로다. 또 배제하지 않으면 제어 평면(`bootstrapRun`)이 "URL 이 없다" 며 죽는다 — 접속정보를 *받아 오는* 단계가 접속정보를 요구받는 순환 |
| D-13 | 채번 시딩 실행 시점 | `@Bean(initMethod)` — 컨텍스트 갱신 중. `ApplicationRunner` 미채택 | 러너는 **웹 서버가 요청을 받기 시작한 뒤**에 돌아, 그 사이 요청은 시딩 전 카운터로 채번된다. `seedAtLeast` 는 원자적이지 않고 "트래픽 유입 전 1회" 가 전제이므로 그 전제를 지키는 자리는 초기화 시점뿐이다. 실패 시 **기동 중단** — 복원 없이 뜨면 첫 요청부터 PK 위반이다 |

---

## 7. 시연 절차 (면접 대비)

1. `gradlew bootstrapRun` — 저장된 응답으로 접속정보 복원
2. `gradlew probeRun` — 연계 대상 접속 확인
3. 애플리케이션 기동 → 샘플 XML 을 주문 API 로 POST
4. **DB 조회**
   ```sql
   SELECT * FROM ORDER_TB
    WHERE APPLICANT_KEY = '<내 키>'
      AND TRUNC(CREATE_TIME) = TRUNC(SYSDATE)
    ORDER BY ORDER_ID;
   ```
5. **FTP 확인** — `/Recruit/2026` 에서 `INSPIEN_{한글이름}_*.txt` 다운로드, 한글 파일명·내용 정상 확인
6. 5분 대기 또는 수동 트리거 → SHIPMENT_TB 적재 및 ORDER_TB `STATUS='Y'` 확인
7. `logs/interface/` 실행 이력 제시
