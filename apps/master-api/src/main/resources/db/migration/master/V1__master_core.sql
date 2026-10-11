-- MASTER DB 첫 운영 migration (MASTER-DOMAIN-001). 명세: docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md
-- 업무 규칙 근거: 구조 확정본(docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md), 초기 확정사항, D-MD-001~014.
--
-- 공통 규칙
-- * 모든 문자열 컬럼은 utf8mb4 + utf8mb4_0900_bin(NO PAD binary). 서버 기본 utf8mb4_0900_ai_ci(대소문자 무시)를 상속하지 않는다
--   (PAYMENT와 같은 관례 — 식별자·등록번호·채널 식별값은 입력 그대로 비교한다).
-- * 시각은 UTC DATETIME(3)(ms). 애플리케이션이 UTC Instant로 쓴다. DB 기본값 CURRENT_TIMESTAMP는 쓰지 않는다.
-- * 식별자는 서버 생성 `<prefix>_` + ULID 26자(Crockford 대문자). CHECK가 형식을 강제한다(형식 밖 값이 다른 경로로 생기지 않게).
-- * 관계 규칙을 DB 복합 FK로 이중 방어한다(domain 검사와 같은 규칙):
--   - MID·Site·계약 Scope의 같은 Merchant / 같은 Business(구조 §8, D-MD-004 당사자 = Business)
--   - MID 발급 근거는 승인된 Revision만(초기 셋째 문단, A10 INV-17) — FK가 contract_revision_approval을 가리킨다
--   - 한 계약의 모든 Revision과 그 계약을 근거로 한 MID는 계약의 대상 Merchant 하나(D-MD-013) — contract.merchant_id 복합 FK
--   "발급 시각에 적용되는 Revision만 근거(D-MD-011·012)"는 시각 판정이라 DB 제약으로 표현하지 않고 contract 행 잠금 아래 domain이 판정한다.
-- * Cross-DB FK 없음. PAYMENT/FINANCIAL을 참조하지 않는다.
-- * MySQL에는 기간 배제(exclusion) 제약이 없다. 닫힌 기간끼리의 중첩은 merchant 행 잠금 아래 domain 검사로 막고, 열린(종료 없는) 연결의
--   유일성만 생성 컬럼 UNIQUE로 DB가 보장한다(D-MD-005·D-MD-007).

-- 사업 주체(법적 주체). 국내 사업자등록번호로 하나(D-MD-008). 등록번호는 10자리 숫자만(하이픈 등 다른 표기는 거절 — 암묵 정규화 없이
-- 같은 번호가 다른 문자열로 중복 등록되지 않게). 로그·이벤트·이력에는 평문을 남기지 않는다(마스킹, D-MD-008).
CREATE TABLE business (
    business_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    registration_number CHAR(10)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    display_name        VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version             BIGINT       NOT NULL,
    CONSTRAINT pk_business PRIMARY KEY (business_id),
    CONSTRAINT uk_business_registration_number UNIQUE (registration_number),
    CONSTRAINT ck_business_id CHECK (REGEXP_LIKE(business_id, '^biz_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_business_registration_number CHECK (REGEXP_LIKE(registration_number, '^[0-9]{10}$', 'c')),
    CONSTRAINT ck_business_lifecycle CHECK (lifecycle IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT ck_business_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 가맹점. Business 1:N(초기). entitlement_revision은 결제 허용 판정 입력이 바뀔 때마다 같은 TX에서 +1하는 단조 값이다
-- (MASTER-SNAPSHOT-001이 "과거 허용으로 차단 되돌림 금지"를 버전 비교로 구현하기 위한 관례 — JPA version과 별개).
CREATE TABLE merchant (
    merchant_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    display_name         VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle            VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    entitlement_revision BIGINT       NOT NULL,
    created_at           DATETIME(3)  NOT NULL,
    created_by           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version              BIGINT       NOT NULL,
    CONSTRAINT pk_merchant PRIMARY KEY (merchant_id),
    CONSTRAINT uk_merchant_business UNIQUE (merchant_id, business_id),
    CONSTRAINT fk_merchant_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_merchant_id CHECK (REGEXP_LIKE(merchant_id, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_merchant_lifecycle CHECK (lifecycle IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT ck_merchant_entitlement_revision CHECK (entitlement_revision >= 0),
    CONSTRAINT ck_merchant_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 결제 채널. Merchant 1:N(구조 §13). 채널 식별값(WEB: 소문자 host, APP: 소문자 앱 식별자)은 활성 Site 사이에서 전역 하나(D-MD-008:
-- 같은 Merchant 안 활성 중복 금지 + 다른 Merchant의 같은 값 거절). 예외 승인 기록은 아직 없다(후속).
CREATE TABLE merchant_site (
    merchant_site_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    channel_type       VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    channel_identifier VARCHAR(253) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    active_channel_key VARCHAR(262) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(lifecycle = 'ACTIVE', CONCAT(channel_type, '|', channel_identifier), NULL)) STORED,
    created_at         DATETIME(3)  NOT NULL,
    created_by         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_merchant_site PRIMARY KEY (merchant_site_id),
    CONSTRAINT uk_merchant_site_merchant UNIQUE (merchant_site_id, merchant_id),
    CONSTRAINT uk_merchant_site_active_channel UNIQUE (active_channel_key),
    CONSTRAINT fk_merchant_site_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
    CONSTRAINT ck_merchant_site_id CHECK (REGEXP_LIKE(merchant_site_id, '^site_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_merchant_site_channel_type CHECK (channel_type IN ('WEB', 'APP')),
    CONSTRAINT ck_merchant_site_channel_identifier CHECK (REGEXP_LIKE(channel_identifier, '^[a-z0-9][a-z0-9._-]*$', 'c')),
    -- WEB은 도메인 host만(IP 리터럴 거절, D-MD-014). 마지막 라벨이 10진 숫자 또는 0x 16진수면 WHATWG URL이 IPv4로 해석하므로 거절
    -- (review n-2: a.0x7f, 0x7f.0x1). 형식 전체 검사는 domain SiteChannel(D-MD-009).
    CONSTRAINT ck_merchant_site_web_not_ip CHECK (
        channel_type <> 'WEB' OR NOT REGEXP_LIKE(channel_identifier, '(^|[.])([0-9]+|0x[0-9a-f]*)$', 'c')),
    CONSTRAINT ck_merchant_site_lifecycle CHECK (lifecycle IN ('ACTIVE', 'TERMINATED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 계약. 당사자 = Business(D-MD-004), 대상 Merchant 하나(모든 Revision 공통 — D-MD-013, 다른 Merchant는 새 계약).
-- Revision·서비스 계약(CSA)은 자식 표(append-only).
CREATE TABLE contract (
    contract_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    created_by  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version     BIGINT      NOT NULL,
    CONSTRAINT pk_contract PRIMARY KEY (contract_id),
    CONSTRAINT uk_contract_business UNIQUE (contract_id, business_id),
    CONSTRAINT uk_contract_merchant UNIQUE (contract_id, merchant_id),
    CONSTRAINT fk_contract_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT fk_contract_merchant FOREIGN KEY (merchant_id, business_id) REFERENCES merchant (merchant_id, business_id),
    CONSTRAINT ck_contract_id CHECK (REGEXP_LIKE(contract_id, '^ctr_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_contract_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 계약 Revision(append-only, 내용 불변 — D-MD-004). Scope는 MERCHANT 또는 SITES(1개 이상), Scope 변경은 새 Revision.
-- Scope Merchant는 당사자 Business 소속이어야 한다(복합 FK). 효력 기간 [from, to).
CREATE TABLE contract_revision (
    contract_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision_no       INT         NOT NULL,
    business_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    scope_kind        VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    scope_merchant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effective_from    DATETIME(3) NOT NULL,
    effective_to      DATETIME(3) NULL,
    registered_at     DATETIME(3) NOT NULL,
    registered_by     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_contract_revision PRIMARY KEY (contract_id, revision_no),
    CONSTRAINT uk_contract_revision_scope_merchant UNIQUE (contract_id, revision_no, scope_merchant_id),
    CONSTRAINT fk_contract_revision_contract FOREIGN KEY (contract_id, business_id) REFERENCES contract (contract_id, business_id),
    CONSTRAINT fk_contract_revision_scope_merchant FOREIGN KEY (scope_merchant_id, business_id)
        REFERENCES merchant (merchant_id, business_id),
    CONSTRAINT fk_contract_revision_contract_merchant FOREIGN KEY (contract_id, scope_merchant_id)
        REFERENCES contract (contract_id, merchant_id),
    CONSTRAINT ck_contract_revision_no CHECK (revision_no >= 1),
    CONSTRAINT ck_contract_revision_scope_kind CHECK (scope_kind IN ('MERCHANT', 'SITES')),
    CONSTRAINT ck_contract_revision_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- SITES Scope의 Site 목록(append-only). Site는 Revision의 Scope Merchant 소속이어야 한다(복합 FK 두 개).
CREATE TABLE contract_revision_scope_site (
    contract_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision_no      INT         NOT NULL,
    merchant_site_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_contract_revision_scope_site PRIMARY KEY (contract_id, revision_no, merchant_site_id),
    CONSTRAINT fk_contract_revision_scope_site_revision FOREIGN KEY (contract_id, revision_no, merchant_id)
        REFERENCES contract_revision (contract_id, revision_no, scope_merchant_id),
    CONSTRAINT fk_contract_revision_scope_site_site FOREIGN KEY (merchant_site_id, merchant_id)
        REFERENCES merchant_site (merchant_site_id, merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Revision 승인 사실(append-only 확정 사실, D-MD-004). Revision당 하나. 철회·재활성화는 MASTER-LIFECYCLE-001.
CREATE TABLE contract_revision_approval (
    contract_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision_no INT         NOT NULL,
    approved_at DATETIME(3) NOT NULL,
    approved_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_contract_revision_approval PRIMARY KEY (contract_id, revision_no),
    CONSTRAINT fk_contract_revision_approval_revision FOREIGN KEY (contract_id, revision_no)
        REFERENCES contract_revision (contract_id, revision_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 서비스 계약(CSA). Contract 1:N(D-MD-001), 1건 = 서비스 종류 1개(D-MD-002) → UNIQUE(contract_id, service_kind).
CREATE TABLE contract_service_agreement (
    agreement_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    service_kind VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    added_at     DATETIME(3) NOT NULL,
    added_by     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_contract_service_agreement PRIMARY KEY (agreement_id),
    CONSTRAINT uk_contract_service_agreement_kind UNIQUE (contract_id, service_kind),
    CONSTRAINT uk_contract_service_agreement_contract UNIQUE (agreement_id, contract_id),
    CONSTRAINT fk_contract_service_agreement_contract FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT ck_contract_service_agreement_id CHECK (REGEXP_LIKE(agreement_id, '^csa_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_contract_service_agreement_kind CHECK (service_kind IN ('CARD_PG_PAYMENT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- MID(계약 계정, D-MD-003: 모두 Production MID). 한 Merchant 소속(구조 §8), 발급 근거 CSA + 승인된 Revision(불변 — 초기).
-- lifecycle ACTIVE(발급 완료)/TERMINATED(비가역, D-MD-006). 신규 결제 중지는 별도 차단 기록(MASTER-LIFECYCLE-001)이다.
CREATE TABLE merchant_account (
    merchant_account_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issuing_contract_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issuing_agreement_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issuing_revision_no  INT         NOT NULL,
    lifecycle            VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issued_at            DATETIME(3) NOT NULL,
    issued_by            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version              BIGINT      NOT NULL,
    CONSTRAINT pk_merchant_account PRIMARY KEY (merchant_account_id),
    CONSTRAINT uk_merchant_account_merchant UNIQUE (merchant_account_id, merchant_id),
    CONSTRAINT fk_merchant_account_merchant FOREIGN KEY (merchant_id, business_id) REFERENCES merchant (merchant_id, business_id),
    CONSTRAINT fk_merchant_account_contract FOREIGN KEY (issuing_contract_id, business_id) REFERENCES contract (contract_id, business_id),
    CONSTRAINT fk_merchant_account_contract_merchant FOREIGN KEY (issuing_contract_id, merchant_id)
        REFERENCES contract (contract_id, merchant_id),
    CONSTRAINT fk_merchant_account_agreement FOREIGN KEY (issuing_agreement_id, issuing_contract_id)
        REFERENCES contract_service_agreement (agreement_id, contract_id),
    CONSTRAINT fk_merchant_account_approved_revision FOREIGN KEY (issuing_contract_id, issuing_revision_no)
        REFERENCES contract_revision_approval (contract_id, revision_no),
    CONSTRAINT ck_merchant_account_id CHECK (REGEXP_LIKE(merchant_account_id, '^mid_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_merchant_account_lifecycle CHECK (lifecycle IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT ck_merchant_account_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 현재 Default MID(Merchant당 최대 1 — PK). 같은 Merchant의 MID만(복합 FK). 교체·해제는 Q-MD-07 미결이라 경로 없음.
CREATE TABLE default_designation (
    merchant_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    designated_at       DATETIME(3) NOT NULL,
    designated_by       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_default_designation PRIMARY KEY (merchant_id),
    CONSTRAINT uk_default_designation_account UNIQUE (merchant_account_id),
    CONSTRAINT fk_default_designation_account FOREIGN KEY (merchant_account_id, merchant_id)
        REFERENCES merchant_account (merchant_account_id, merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- MID–Site 연결(구조 §9). MID와 Site는 같은 Merchant(복합 FK 두 개). 기간 [from, to) UTC, 소급 시작 금지(D-MD-005 — from >= 생성 시각),
-- 상태 ACTIVE/SUSPENDED/ENDED(D-MD-005). 열린(종료 없음·ENDED 아님) 연결은 같은 MID–Site 하나, MID당 PRIMARY 하나(D-MD-007).
CREATE TABLE merchant_account_site_assignment (
    assignment_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    role                VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status              VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effective_from      DATETIME(3)  NOT NULL,
    effective_to        DATETIME(3)  NULL,
    created_at          DATETIME(3)  NOT NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    open_pair_key       VARCHAR(130) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL AND status <> 'ENDED',
                                CONCAT(merchant_account_id, '|', merchant_site_id), NULL)) STORED,
    open_primary_key    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(role = 'PRIMARY' AND effective_to IS NULL AND status <> 'ENDED', merchant_account_id, NULL)) STORED,
    CONSTRAINT pk_merchant_account_site_assignment PRIMARY KEY (assignment_id),
    CONSTRAINT uk_assignment_open_pair UNIQUE (open_pair_key),
    CONSTRAINT uk_assignment_open_primary UNIQUE (open_primary_key),
    CONSTRAINT fk_assignment_account FOREIGN KEY (merchant_account_id, merchant_id)
        REFERENCES merchant_account (merchant_account_id, merchant_id),
    CONSTRAINT fk_assignment_site FOREIGN KEY (merchant_site_id, merchant_id) REFERENCES merchant_site (merchant_site_id, merchant_id),
    CONSTRAINT ck_assignment_id CHECK (REGEXP_LIKE(assignment_id, '^asg_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_assignment_role CHECK (role IN ('PRIMARY', 'ADDITIONAL')),
    CONSTRAINT ck_assignment_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT ck_assignment_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_assignment_not_retroactive CHECK (effective_from >= created_at),
    CONSTRAINT ck_assignment_ended_has_end CHECK (status <> 'ENDED' OR effective_to IS NOT NULL),
    INDEX ix_assignment_account (merchant_account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 개통 등록(최소 저장, Q-MD-09 미결): MID 단위(구조 §12 Phase 1) 결제수단 하나당 하나. 상태 컬럼이 없다 — 상태 의미가 정해지기 전까지
-- 이 행은 "등록됨"일 뿐 개통 완료가 아니다(결제 허용 판정은 fail-closed로 미개통으로 본다, MASTER-SNAPSHOT-001).
-- **잠정**: UNIQUE(merchant_account_id, payment_method)와 중복 거절(ACTIVATION_DUPLICATE)은 Q-MD-09(카드사·Site 단위, 상태 이력) 답변 전의
-- fail-closed 선택이다. 답에 따라 키·상태 컬럼이 바뀌면 새 버전 migration(적용된 V1은 고치지 않음)이 필요하다.
CREATE TABLE payment_method_activation (
    activation_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_method      VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    registered_at       DATETIME(3) NOT NULL,
    registered_by       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_payment_method_activation PRIMARY KEY (activation_id),
    CONSTRAINT uk_payment_method_activation_account_method UNIQUE (merchant_account_id, payment_method),
    CONSTRAINT fk_payment_method_activation_account FOREIGN KEY (merchant_account_id) REFERENCES merchant_account (merchant_account_id),
    CONSTRAINT ck_payment_method_activation_id CHECK (REGEXP_LIKE(activation_id, '^act_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_payment_method_activation_method CHECK (payment_method IN ('CARD'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 변경 이력(append-only 감사, A05 "변경 전후·사유·행위자"). detail JSON에 등록번호 평문을 넣지 않는다(마스킹).
CREATE TABLE master_change_history (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    request_record_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation         VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_type    VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    change_type       VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    actor_ref         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at       DATETIME(3) NOT NULL,
    detail            JSON        NOT NULL,
    CONSTRAINT pk_master_change_history PRIMARY KEY (id),
    INDEX ix_master_change_history_aggregate (aggregate_type, aggregate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 관리 명령 멱등 기록. 범위 UNIQUE(operation, idempotency_key). 보존: 삭제 경로 없음(무기한 — 기술 기본값, D-CPC-004는 적용하지 않음).
-- PENDING은 같은 TX 안의 예약 상태다(결과와 함께 커밋되므로 커밋된 PENDING은 없어야 한다).
CREATE TABLE admin_request (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    operation           VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    idempotency_key     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_fingerprint VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    actor_ref           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_kind         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_code         VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    result_reference    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    version             BIGINT      NOT NULL,
    CONSTRAINT pk_admin_request PRIMARY KEY (id),
    CONSTRAINT uk_admin_request_scope UNIQUE (operation, idempotency_key),
    CONSTRAINT ck_admin_request_key CHECK (REGEXP_LIKE(idempotency_key, '^[A-Za-z0-9._:-]{1,64}$', 'c')),
    CONSTRAINT ck_admin_request_result CHECK (
        (result_kind = 'PENDING' AND result_code IS NULL AND result_reference IS NULL)
        OR (result_kind = 'SUCCESS' AND result_code IS NULL AND result_reference IS NOT NULL)
        OR (result_kind = 'REJECTED' AND result_code IS NOT NULL)),
    CONSTRAINT ck_admin_request_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 통합 이벤트 Outbox(같은 MASTER TX로 append, Relay는 MASTER-SNAPSHOT-001). PAYMENT outbox_event와 같은 모양.
CREATE TABLE outbox_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    event_id          CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_type        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version    INT          NOT NULL,
    event_category    VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    producer          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_type    VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    correlation_id    VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    causation_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload           JSON         NOT NULL,
    publish_status    VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    published_at      DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT uk_outbox_event_effect_key UNIQUE (effect_key),
    CONSTRAINT ck_outbox_event_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_event_publish_status CHECK (
        (publish_status = 'PENDING' AND published_at IS NULL) OR (publish_status = 'PUBLISHED' AND published_at IS NOT NULL)),
    INDEX ix_outbox_event_pending (publish_status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
