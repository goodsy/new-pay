-- BUSINESS-PROFILE-001 BIZ-1(명세 docs/repository/BUSINESS-PROFILE-001-BUSINESS-PROFILE.md §5.1·§5.2): 사업자 기본정보 관리 schema.
-- 결정: D-BIZ-001(등록 상태 축 추가 — 생명주기 불변, 기존 사업자 → DRAFT), D-BIZ-002(DRAFT 최소 = 구분 + 번호 + 상호), D-MD-008(번호 UNIQUE·불변 그대로).
--
-- * 번호·상호는 business에 둔다(C-8 — 기존 UNIQUE·FK·조회 불변). 그 밖 등록정보는 business_registration(1:1 — UNIQUE(business_id)).
-- * 대표자·업태/종목·주소·등록 담당자는 유효기간 행(effective_from/to = PayCore 기록 시각 UTC, 법적 날짜 아님 — C-10). 종료 = effective_to·ended_by 기록,
--   물리 삭제 없음(runtime에 DELETE 권한 없음 — V16). 현재 행의 유일성은 생성 열 UNIQUE(종료 행은 NULL이라 제외)로 DB가 이중 방어한다.
-- * 진위확인은 NOT_CHECKED만(ck_business_verification — TC-21, P1이 실제 연동과 함께 넓힌다). INACTIVE는 CHECK 값만 있고 앱 경로가 없다(TBD-08).
-- * MySQL CHECK 이름은 schema 전체에서 유일 → 새 표 CHECK는 표별 접두(ck_breg_·ck_brep_·ck_bcat_·ck_badr_·ck_bcon_)를 쓴다.
-- * 문장별 ALGORITHM(C-7): (1) INSTANT 열 추가 → (2) 백필 → (3) NOT NULL·CHECK(COPY — 표 재작성, 운영 적용 창 L-8) → (4) 색인(INPLACE·LOCK=NONE).
--   MySQL DDL은 문장마다 암묵 commit이라 이 파일은 원자적이지 않다. 부분 실패 복구(전제 점검·점검·되돌림)는 L-8 절차와
--   db/recovery/master/business-profile-v15-*.sql(BIZ-1-2 BusinessSchemaMigrationSpec이 정확성을 고정)을 따른다. 운영 실행은 LIVE Gate.

-- (1) 새 열(NULL 허용, 끝에 추가) — 메타데이터만.
ALTER TABLE business
    ADD COLUMN business_type       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN registration_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN verification_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN updated_at          DATETIME(3) NULL,
    ADD COLUMN updated_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM = INSTANT;

-- (2) 기존 행 채우기(D-BIZ-001: DRAFT, 구분 NULL — X-1). 갱신 시각·행위자 = 생성 값(변경이 없었으므로).
UPDATE business SET registration_status = 'DRAFT', verification_status = 'NOT_CHECKED', updated_at = created_at, updated_by = created_by
 WHERE registration_status IS NULL;

-- (3) NOT NULL·CHECK — 표 재작성(작은 표, Demo 즉시. 운영 적용 창은 L-8).
ALTER TABLE business
    MODIFY registration_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY verification_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    MODIFY updated_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_business_type CHECK (business_type IS NULL OR business_type IN ('INDIVIDUAL', 'CORPORATION')),
    ADD CONSTRAINT ck_business_reg_status CHECK (registration_status IN ('DRAFT', 'REGISTERED', 'INACTIVE')),
    ADD CONSTRAINT ck_business_type_required CHECK (registration_status = 'DRAFT' OR business_type IS NOT NULL),
    ADD CONSTRAINT ck_business_verification CHECK (verification_status = 'NOT_CHECKED'),
    ADD CONSTRAINT ck_business_updated CHECK (updated_at >= created_at),
    ALGORITHM = COPY;

-- (4) 목록 색인(온라인 — BIZ-2 목록·필터용).
ALTER TABLE business
    ADD INDEX ix_business_status_id (registration_status, business_id),
    ADD INDEX ix_business_name_id (display_name, business_id),
    ADD INDEX ix_business_created_id (created_at, business_id),
    ALGORITHM = INPLACE, LOCK = NONE;

-- (5) 등록정보(1:1 — TBD-03 확장 대비 별도 표, 1차 UNIQUE(business_id)). 번호·상호는 business에 있다(§5.3).
-- corporate_registration_number: 13자리(체크섬 없음, UNIQUE 아님 — TBD-01·C-30). opening_date: 달력 날짜(DATE — 시간대 없음).
CREATE TABLE business_registration (
    registration_id               BIGINT       NOT NULL AUTO_INCREMENT,
    business_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    corporate_registration_number CHAR(13)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    opening_date                  DATE         NULL,
    country_code                  CHAR(2)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    english_name                  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    phone_number                  VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    email                         VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    CONSTRAINT pk_business_registration PRIMARY KEY (registration_id),
    CONSTRAINT uk_breg_business UNIQUE (business_id),
    CONSTRAINT fk_breg_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_breg_corporate_number CHECK (corporate_registration_number IS NULL OR REGEXP_LIKE(corporate_registration_number, '^[0-9]{13}$', 'c')),
    CONSTRAINT ck_breg_country CHECK (country_code = 'KR'),
    CONSTRAINT ck_breg_phone CHECK (phone_number IS NULL OR REGEXP_LIKE(phone_number, '^[01][0-9]{7,11}$', 'c')),
    CONSTRAINT ck_breg_email CHECK (email IS NULL OR REGEXP_LIKE(email, '^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+$', 'c')),
    CONSTRAINT ck_breg_english_name CHECK (english_name IS NULL OR REGEXP_LIKE(english_name, '^[ -~]+$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (6) 기존 사업자마다 등록정보 1행(1:1 불변식 — 모든 business에 정확히 1행, TC-18). 선택 열은 NULL(값 이관 없음).
INSERT INTO business_registration (business_id, country_code) SELECT business_id, 'KR' FROM business;

-- (7) 대표자(현재·과거 관계). 현재 순서 하나(uk_brep_current_order — 종료 행은 생성 열 NULL).
CREATE TABLE business_representative (
    representative_id   BIGINT      NOT NULL AUTO_INCREMENT,
    business_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name                VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    representative_type VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    display_order       SMALLINT    NOT NULL,
    effective_from      DATETIME(3) NOT NULL,
    effective_to        DATETIME(3) NULL,
    created_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_by            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    current_order_key   VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(business_id, '#', display_order), NULL)) STORED,
    CONSTRAINT pk_business_representative PRIMARY KEY (representative_id),
    CONSTRAINT uk_brep_current_order UNIQUE (current_order_key),
    CONSTRAINT fk_brep_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    INDEX ix_brep_business (business_id, effective_to),
    CONSTRAINT ck_brep_type CHECK (representative_type IN ('SOLE', 'JOINT', 'EACH')),
    CONSTRAINT ck_brep_order CHECK (display_order BETWEEN 1 AND 5),
    CONSTRAINT ck_brep_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_brep_ended CHECK ((effective_to IS NULL) = (ended_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (8) 업태(BUSINESS_TYPE)·종목(BUSINESS_ITEM) — 등록정보에 속한다(지침서 §6). 순서는 유형 안 1..10. 현재 행의 유형 안 순서 하나·유형 안 같은 이름 하나.
CREATE TABLE business_category (
    category_id       BIGINT      NOT NULL AUTO_INCREMENT,
    registration_id   BIGINT      NOT NULL,
    category_type     VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name              VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    display_order     SMALLINT    NOT NULL,
    effective_from    DATETIME(3) NOT NULL,
    effective_to      DATETIME(3) NULL,
    created_by        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    current_order_key VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(registration_id, '#', category_type, '#', display_order), NULL)) STORED,
    current_name_key  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(registration_id, '#', category_type, '#', name), NULL)) STORED,
    CONSTRAINT pk_business_category PRIMARY KEY (category_id),
    CONSTRAINT uk_bcat_current_order UNIQUE (current_order_key),
    CONSTRAINT uk_bcat_current_name UNIQUE (current_name_key),
    CONSTRAINT fk_bcat_registration FOREIGN KEY (registration_id) REFERENCES business_registration (registration_id),
    INDEX ix_bcat_registration (registration_id, effective_to),
    CONSTRAINT ck_bcat_type CHECK (category_type IN ('BUSINESS_TYPE', 'BUSINESS_ITEM')),
    CONSTRAINT ck_bcat_order CHECK (display_order BETWEEN 1 AND 10),
    CONSTRAINT ck_bcat_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_bcat_ended CHECK ((effective_to IS NULL) = (ended_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (9) 사업장(REGISTERED)·실사업장(ACTUAL) 주소 — 유형별 현재 하나(uk_badr_current_type). 상세주소 선택(C-31).
CREATE TABLE business_address (
    address_id       BIGINT       NOT NULL AUTO_INCREMENT,
    registration_id  BIGINT       NOT NULL,
    address_type     VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    postal_code      CHAR(5)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    base_address     VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    detail_address   VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    effective_from   DATETIME(3)  NOT NULL,
    effective_to     DATETIME(3)  NULL,
    created_by       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_by         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    current_type_key VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(registration_id, '#', address_type), NULL)) STORED,
    CONSTRAINT pk_business_address PRIMARY KEY (address_id),
    CONSTRAINT uk_badr_current_type UNIQUE (current_type_key),
    CONSTRAINT fk_badr_registration FOREIGN KEY (registration_id) REFERENCES business_registration (registration_id),
    INDEX ix_badr_registration (registration_id, effective_to),
    CONSTRAINT ck_badr_type CHECK (address_type IN ('REGISTERED', 'ACTUAL')),
    CONSTRAINT ck_badr_postal_code CHECK (REGEXP_LIKE(postal_code, '^[0-9]{5}$', 'c')),
    CONSTRAINT ck_badr_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_badr_ended CHECK ((effective_to IS NULL) = (ended_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (10) 등록 담당자(사업자 측 담당자 — 내부 PayCore 담당자 아님, POL-003). contact_role은 후속 역할 확장 자리(이번에는 REGISTRATION만).
-- 현재 순서 하나·현재 기본 담당자 최대 하나(uk_bcon_current_primary).
CREATE TABLE business_contact (
    contact_id          BIGINT       NOT NULL AUTO_INCREMENT,
    business_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contact_role        VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name                VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    email               VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    phone_number        VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    is_primary          TINYINT(1)   NOT NULL,
    display_order       SMALLINT     NOT NULL,
    effective_from      DATETIME(3)  NOT NULL,
    effective_to        DATETIME(3)  NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_by            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    current_order_key   VARCHAR(80)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(business_id, '#', display_order), NULL)) STORED,
    current_primary_key VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL AND is_primary = 1, business_id, NULL)) STORED,
    CONSTRAINT pk_business_contact PRIMARY KEY (contact_id),
    CONSTRAINT uk_bcon_current_order UNIQUE (current_order_key),
    CONSTRAINT uk_bcon_current_primary UNIQUE (current_primary_key),
    CONSTRAINT fk_bcon_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    INDEX ix_bcon_business (business_id, effective_to),
    CONSTRAINT ck_bcon_role CHECK (contact_role IN ('REGISTRATION')),
    CONSTRAINT ck_bcon_email CHECK (REGEXP_LIKE(email, '^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+$', 'c')),
    CONSTRAINT ck_bcon_phone CHECK (REGEXP_LIKE(phone_number, '^[01][0-9]{7,11}$', 'c')),
    CONSTRAINT ck_bcon_primary CHECK (is_primary IN (0, 1)),
    CONSTRAINT ck_bcon_order CHECK (display_order BETWEEN 1 AND 5),
    CONSTRAINT ck_bcon_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_bcon_ended CHECK ((effective_to IS NULL) = (ended_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
