-- SITE-CONTRACT-CODE-001 SCODE-1(§3.2·§3.3·§3.6 — D-ADMIN-UI-005·007, Q-SC-1~5, C-1·C-2·C-5·C-8): Site·계약 사람용 코드의 append-only 표 2개와 기존 행 백필.
-- 형식 site-<가맹점코드>-<NN> / ctr-<가맹점코드>-<NN>(소문자·하이픈, NN = 가맹점·종류별 순번 1..9999 최소 2자리 0 채움). 내부 키 site_<ULID>·ctr_<ULID>는 그대로다(FK·Snapshot·외부 API).
-- * 기존 표(merchant_site·contract)에 열을 더하지 않는다(C-2): contract는 runtime 표 단위 UPDATE 권한이 있어 열 불변을 권한으로 보장할 수 없다 — 새 표는 V20에서 SELECT, INSERT만.
-- * UNIQUE(코드): MASTER 전체에서 하나(TEST·LIVE 공통 — Q-SC-3). UNIQUE(merchant_id, code_seq): 가맹점·종류별 순번 하나. 둘 다 잠금(merchant 행) 아래에서는 위반이 나면 안 되는 결함 backstop(C-3).
-- * FK(내부 키, merchant_id) → 원 표 UNIQUE(merchant_site_id, merchant_id)/(contract_id, merchant_id): 코드 행의 가맹점 = 원 행의 가맹점.
-- * FK(merchant_id, issued_merchant_code) → merchant_code_assignment(merchant_id, merchant_code): 박힌 가맹점 코드는 그 가맹점의 배정 코드(현재·은퇴 — Q-SC-2 발급 당시 값 유지).
-- * FK 동작은 지정하지 않는다(기본 NO ACTION — V1·V13 관례, CHECK 열과 공존 가능).
-- * ck_*_derived: 코드 = f(가맹점 코드, 순번)을 DB가 강제한다. 숫자→문자 변환과 리터럴마다 문자 집합(utf8mb4)과 collation(utf8mb4_0900_bin)을 모두 명시해 연결 기본 문자 집합·collation
--   (runtime 세션 포함)에 따라 결과가 달라지거나 혼합 collation 오류(1267)가 나지 않게 한다(C-8). LPAD만 쓰면 100 이상이 잘린다(LPAD('123', 2, '0') = '12') → IF로 100 미만만 채운다.
--   같은 의미의 식을 아래 백필과 앱(Kotlin HumanCodeSequence — padStart, 절단 없음) 세 곳에 둔다.
-- * 이 파일에 SIGNAL·RAND 없음. MySQL DDL은 자동 커밋이라 CREATE 뒤 백필 INSERT가 실패하면 빈 표가 남는다 — 복구 절차는 db/recovery/master/site-contract-code-v19-*.sql(§3.6 MINOR-4).
CREATE TABLE merchant_site_code (
    merchant_site_id     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    code_seq             SMALLINT    NOT NULL,
    issued_merchant_code VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    site_code            VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source               VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    assigned_at          DATETIME(3) NOT NULL,
    assigned_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_merchant_site_code PRIMARY KEY (merchant_site_id),
    CONSTRAINT uk_msc_code UNIQUE (site_code),
    CONSTRAINT uk_msc_merchant_seq UNIQUE (merchant_id, code_seq),
    CONSTRAINT fk_msc_site FOREIGN KEY (merchant_site_id, merchant_id) REFERENCES merchant_site (merchant_site_id, merchant_id),
    CONSTRAINT fk_msc_merchant_code FOREIGN KEY (merchant_id, issued_merchant_code) REFERENCES merchant_code_assignment (merchant_id, merchant_code),
    CONSTRAINT ck_msc_seq CHECK (code_seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_msc_source CHECK (source IN ('REGISTRATION', 'BACKFILL')),
    CONSTRAINT ck_msc_code_format CHECK (REGEXP_LIKE(site_code, '^site-[a-z][a-z0-9]{5,13}-(0[1-9]|[1-9][0-9]{1,3})$', 'c')),
    CONSTRAINT ck_msc_code_derived CHECK (site_code = CONCAT(
        _utf8mb4'site-' COLLATE utf8mb4_0900_bin,
        issued_merchant_code,
        _utf8mb4'-' COLLATE utf8mb4_0900_bin,
        IF(code_seq < 100,
            LPAD(CAST(code_seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin, 2, _utf8mb4'0' COLLATE utf8mb4_0900_bin),
            CAST(code_seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기존 Site 백필(C-5·C-8): 열 목록 명시, 한 문장(부분 백필 없음). 순번 = 가맹점별 created_at 오름차순, 동률은 내부 키 오름차순(결정적). 가맹점 코드 = 백필 시점 현재 코드
-- (가장 큰 assignment_seq 행 — Q-SC-2의 "발급 당시"는 백필 행에서 migration 시점). assigned_at = migration 시각(UTC_TIMESTAMP(3) — 한 문장 안 같은 값, 세션 time_zone 무관), assigned_by = system:migration.
-- 현재 코드 결합은 LEFT JOIN: 배정 행 없는 가맹점이면 NULL → NOT NULL(strict)·FK·CHECK 위반으로 migration 실패(fail-closed — 조용히 빠지는 행 없음).
-- 한 가맹점 9999 초과는 ck_msc_seq, 코드 충돌은 uk_msc_code 1062로 실패(fail-closed).
INSERT INTO merchant_site_code (merchant_site_id, merchant_id, code_seq, issued_merchant_code, site_code, source, assigned_at, assigned_by)
SELECT s.merchant_site_id, s.merchant_id, s.seq, cur.merchant_code,
       CONCAT(
           _utf8mb4'site-' COLLATE utf8mb4_0900_bin,
           cur.merchant_code,
           _utf8mb4'-' COLLATE utf8mb4_0900_bin,
           IF(s.seq < 100,
               LPAD(CAST(s.seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin, 2, _utf8mb4'0' COLLATE utf8mb4_0900_bin),
               CAST(s.seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin)),
       _utf8mb4'BACKFILL' COLLATE utf8mb4_0900_bin, UTC_TIMESTAMP(3), _utf8mb4'system:migration' COLLATE utf8mb4_0900_bin
FROM (SELECT merchant_site_id, merchant_id,
             ROW_NUMBER() OVER (PARTITION BY merchant_id ORDER BY created_at, merchant_site_id) AS seq
      FROM merchant_site) s
LEFT JOIN merchant_code_assignment cur
       ON cur.merchant_id = s.merchant_id
      AND cur.assignment_seq = (SELECT MAX(a.assignment_seq) FROM merchant_code_assignment a WHERE a.merchant_id = s.merchant_id);

CREATE TABLE contract_code (
    contract_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    code_seq             SMALLINT    NOT NULL,
    issued_merchant_code VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_code        VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source               VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    assigned_at          DATETIME(3) NOT NULL,
    assigned_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_contract_code PRIMARY KEY (contract_id),
    CONSTRAINT uk_cc_code UNIQUE (contract_code),
    CONSTRAINT uk_cc_merchant_seq UNIQUE (merchant_id, code_seq),
    CONSTRAINT fk_cc_contract FOREIGN KEY (contract_id, merchant_id) REFERENCES contract (contract_id, merchant_id),
    CONSTRAINT fk_cc_merchant_code FOREIGN KEY (merchant_id, issued_merchant_code) REFERENCES merchant_code_assignment (merchant_id, merchant_code),
    CONSTRAINT ck_cc_seq CHECK (code_seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_cc_source CHECK (source IN ('REGISTRATION', 'BACKFILL')),
    CONSTRAINT ck_cc_code_format CHECK (REGEXP_LIKE(contract_code, '^ctr-[a-z][a-z0-9]{5,13}-(0[1-9]|[1-9][0-9]{1,3})$', 'c')),
    CONSTRAINT ck_cc_code_derived CHECK (contract_code = CONCAT(
        _utf8mb4'ctr-' COLLATE utf8mb4_0900_bin,
        issued_merchant_code,
        _utf8mb4'-' COLLATE utf8mb4_0900_bin,
        IF(code_seq < 100,
            LPAD(CAST(code_seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin, 2, _utf8mb4'0' COLLATE utf8mb4_0900_bin),
            CAST(code_seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기존 계약 백필(위 Site 백필과 같은 규칙 — 계약의 가맹점 = contract.merchant_id).
INSERT INTO contract_code (contract_id, merchant_id, code_seq, issued_merchant_code, contract_code, source, assigned_at, assigned_by)
SELECT k.contract_id, k.merchant_id, k.seq, cur.merchant_code,
       CONCAT(
           _utf8mb4'ctr-' COLLATE utf8mb4_0900_bin,
           cur.merchant_code,
           _utf8mb4'-' COLLATE utf8mb4_0900_bin,
           IF(k.seq < 100,
               LPAD(CAST(k.seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin, 2, _utf8mb4'0' COLLATE utf8mb4_0900_bin),
               CAST(k.seq AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin)),
       _utf8mb4'BACKFILL' COLLATE utf8mb4_0900_bin, UTC_TIMESTAMP(3), _utf8mb4'system:migration' COLLATE utf8mb4_0900_bin
FROM (SELECT contract_id, merchant_id,
             ROW_NUMBER() OVER (PARTITION BY merchant_id ORDER BY created_at, contract_id) AS seq
      FROM contract) k
LEFT JOIN merchant_code_assignment cur
       ON cur.merchant_id = k.merchant_id
      AND cur.assignment_seq = (SELECT MAX(a.assignment_seq) FROM merchant_code_assignment a WHERE a.merchant_id = k.merchant_id);
