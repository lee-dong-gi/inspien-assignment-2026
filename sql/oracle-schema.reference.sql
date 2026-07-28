-- ─────────────────────────────────────────────────────────────
-- 적재 대상 스키마 참조본 (Oracle Database 19c)
--
-- ⚠️ 실행용 스크립트가 아니다. 이 테이블들은 과제 측이 이미 생성해 두었으며
--    지원자는 DDL 권한을 갖지 않는다. 아래는 BOOT-001(probeRun)이 조회한
--    실제 컬럼 정의를 사람이 읽을 수 있는 형태로 재현한 참조 문서다.
--
--    조회 근거:
--      all_tab_columns   — 타입 / 길이 / CHAR_USED / NULLABLE / DATA_DEFAULT
--      all_constraints   — PK 구성
--      nls_database_parameters
--
--    환경 (BOOT-001 실측 / 2026-07-28)
--      NLS_CHARACTERSET      = AL32UTF8        (한글 1자 = 3바이트)
--      NLS_LENGTH_SEMANTICS  = BYTE            (길이 검증 기준이 문자 수가 아니다)
--      DBTIMEZONE            = +09:00 / Asia/Seoul
--      스키마 소유자          = RECRUIT
--      두 테이블은 동일 인스턴스·동일 계정 → 단일 트랜잭션 가능 (설계 결정 D-04)
--      APPLICANT_KEY 기준 기존 행 수 = 0 / 0  (멱등성 검증의 출발점)
--
--    정본은 docs/interface-spec.md 다. 두 문서가 어긋나면 정의서를 따른다.
-- ─────────────────────────────────────────────────────────────


-- ── ORDER_TB : 시나리오 1 적재 대상 ─────────────────────────
--
-- 전 컬럼이 VARCHAR2(100 BYTE) 다. PRICE 도 문자열이므로
-- 숫자로 변환하거나 콤마를 넣지 않고 원본을 그대로 유지한다.
--
CREATE TABLE ORDER_TB (
    ORDER_ID      VARCHAR2(100 BYTE)  NOT NULL,  -- 자체 채번 [A-Z][0-9]{3}. 자동 채번 아님
    APPLICANT_KEY VARCHAR2(100 BYTE)  NOT NULL,  -- BOOT-000 제공 고정값
    USER_ID       VARCHAR2(100 BYTE),            -- HEADER : ITEM 조인 키
    ITEM_ID       VARCHAR2(100 BYTE),
    NAME          VARCHAR2(100 BYTE),            -- 개인정보 — 로그 마스킹 대상
    ADDRESS       VARCHAR2(100 BYTE),            -- 개인정보 — 로그 마스킹 대상
    ITEM_NAME     VARCHAR2(100 BYTE),
    PRICE         VARCHAR2(100 BYTE),            -- 명세상 string. 포맷 변경 금지
    STATUS        VARCHAR2(100 BYTE),            -- N=미전송, Y=운송사 전송 완료
    CREATE_TIME   TIMESTAMP(6) DEFAULT SYSDATE,  -- ★ INSERT 목록에서 제외한다
    CONSTRAINT PK_ORDER_TB PRIMARY KEY (ORDER_ID, APPLICANT_KEY)
);

-- CREATE_TIME 의 DEFAULT 가 SYSDATE 이므로 등록 시각은 DB 서버 시계로 찍힌다.
-- DB 시간대가 Asia/Seoul 이라 시연 당일 조회가 로컬 날짜와 일치한다 (설계 결정 D-06).
--
--   SELECT * FROM ORDER_TB
--    WHERE APPLICANT_KEY = :applicantKey
--      AND TRUNC(CREATE_TIME) = TRUNC(SYSDATE)
--    ORDER BY ORDER_ID;


-- ── SHIPMENT_TB : 시나리오 2 적재 대상 ──────────────────────
--
-- NAME · ITEM_NAME · PRICE · STATUS 는 넘기지 않는다.
-- 운송사는 배송에 필요한 정보만 받으면 된다 — 수신 시스템이 필요로 하는 것만
-- 변환해 전달한다는 EAI 기본 태도의 직접적 구현이다.
--
-- 컬럼 구성이 ORDER_TB 와 동일한 규격이다 (전 컬럼 VARCHAR2(100 BYTE)).
-- 그럼에도 타입을 추정하지 않고 all_tab_columns 로 확인한 뒤 기록했다.
--
CREATE TABLE SHIPMENT_TB (
    SHIPMENT_ID   VARCHAR2(100 BYTE)  NOT NULL,  -- 자체 채번 [A-Z][0-9]{3}
    APPLICANT_KEY VARCHAR2(100 BYTE)  NOT NULL,
    ORDER_ID      VARCHAR2(100 BYTE),            -- ORDER_TB.ORDER_ID 원본 유지
    ITEM_ID       VARCHAR2(100 BYTE),
    ADDRESS       VARCHAR2(100 BYTE),
    CREATE_DATE   TIMESTAMP(6) DEFAULT SYSDATE,  -- ★ INSERT 목록에서 제외한다
    CONSTRAINT PK_SHIPMENT_TB PRIMARY KEY (SHIPMENT_ID, APPLICANT_KEY)
);

-- ⚠️ 등록일시 컬럼명이 두 테이블에서 다르다.
--    ORDER_TB = CREATE_TIME,  SHIPMENT_TB = CREATE_DATE.
--    공통 매퍼로 뭉뚱그리면 틀린다.


-- ── 연계에서 실제로 쓰는 문장 (Oracle 문법) ─────────────────

-- IF-SHP-001 Source : 미전송 주문 조회
--   Oracle 에는 LIMIT 이 없다. FETCH FIRST … ROWS ONLY (12c+)
--
--   SELECT ORDER_ID, ITEM_ID, ADDRESS
--     FROM ORDER_TB
--    WHERE APPLICANT_KEY = ?          -- 누락 시 타 지원자 데이터까지 조회된다
--      AND STATUS = 'N'
--    ORDER BY ORDER_ID
--    FETCH FIRST ? ROWS ONLY;         -- 청크 기본 100

-- IF-SHP-001 후행 처리 : 적재 성공 건만 상태 갱신
--   SHIPMENT INSERT 와 동일 트랜잭션에 묶는다.
--   분리하면 "적재됐는데 STATUS=N" → 다음 주기에 중복 전송된다.
--
--   UPDATE ORDER_TB SET STATUS = 'Y'
--    WHERE ORDER_ID = ? AND APPLICANT_KEY = ?;
