-- MASTER 승인 시작 조건 원본(MASTER-SNAPSHOT-002): 거래 한도 정책, 수수료·정산 정책 버전 등록부, (CSA, MID, 통화) 정책 Assignment,
-- 카드사(매입사)/Route 개통. 명세: docs/repository/MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md §3·§8.1.
-- 근거: D-AUTH-006, D-MD-017·018, D-MS2-001~005. 적용된 V1~V4는 고치지 않는다(새 버전만). 공통 규칙은 V1과 같다(utf8mb4_0900_bin, UTC DATETIME(3),
-- 서버 생성 ID 형식 CHECK, 같은 Merchant 관계는 복합 FK 이중 방어, cross-DB FK 없음). 운영 수치(한도·요율)는 넣지 않는다(D-CPC-005, 구조 §3 —
-- Demo 값은 시험 fixture에만). runtime 표 단위 권한은 db/runtime-privilege/master/V6.

-- 정책 Assignment가 "MID의 발급 CSA와 같은 CSA"만 가리키도록 복합 FK 대상 UNIQUE(도출 — Coordinator 확인 2026-10-15).
ALTER TABLE merchant_account
    ADD CONSTRAINT uk_merchant_account_issuing_agreement UNIQUE (merchant_account_id, issuing_agreement_id);

-- ① 거래 한도 정책(D-AUTH-006). 범위 = CSA 기본(계약·CSA) 또는 MID Override(MID). 값은 LIMITED(금액 ≥ 0) 또는 UNLIMITED를 반드시 명시
--    (무제한은 명시 값만). 효력 [from, to)는 Asia/Seoul 자정만(D-MS2-002 — UTC 15:00:00.000, KST는 일광절약시간 없음), 소급 시작 금지.
--    열린 행은 같은 키(종류+대상+통화) 하나(생성 컬럼 UNIQUE), 닫힌 구간 중첩은 merchant 잠금 아래 domain 검사(D-MS2-001 같은 수준 중첩 거절).
--    종료는 effective_to·ended_* 갱신(UPDATE). 범위·통화·한도 값은 바꾸지 않는다(Entity updatable=false — 새 정책으로만).
CREATE TABLE transaction_limit_policy (
    policy_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    scope_kind          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    agreement_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    currency_code       CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    per_tx_kind         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    per_tx_amount       BIGINT       NULL,
    daily_kind          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    daily_amount        BIGINT       NULL,
    effective_from      DATETIME(3)  NOT NULL,
    effective_to        DATETIME(3)  NULL,
    reason              VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_at            DATETIME(3)  NULL,
    ended_by            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    version             BIGINT       NOT NULL,
    open_scope_key      VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(scope_kind, '|', COALESCE(agreement_id, merchant_account_id), '|', currency_code), NULL)) STORED,
    CONSTRAINT pk_transaction_limit_policy PRIMARY KEY (policy_id),
    CONSTRAINT uk_transaction_limit_policy_open UNIQUE (open_scope_key),
    CONSTRAINT fk_transaction_limit_policy_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
    CONSTRAINT fk_transaction_limit_policy_contract FOREIGN KEY (contract_id, merchant_id) REFERENCES contract (contract_id, merchant_id),
    CONSTRAINT fk_transaction_limit_policy_agreement FOREIGN KEY (agreement_id, contract_id)
        REFERENCES contract_service_agreement (agreement_id, contract_id),
    CONSTRAINT fk_transaction_limit_policy_account FOREIGN KEY (merchant_account_id, merchant_id)
        REFERENCES merchant_account (merchant_account_id, merchant_id),
    CONSTRAINT fk_transaction_limit_policy_unit FOREIGN KEY (currency_code) REFERENCES currency_unit (currency_code),
    CONSTRAINT ck_transaction_limit_policy_id CHECK (REGEXP_LIKE(policy_id, '^tlp_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_transaction_limit_policy_scope CHECK (
        (scope_kind = 'CSA_DEFAULT' AND contract_id IS NOT NULL AND agreement_id IS NOT NULL AND merchant_account_id IS NULL)
        OR (scope_kind = 'MID_OVERRIDE' AND contract_id IS NULL AND agreement_id IS NULL AND merchant_account_id IS NOT NULL)),
    CONSTRAINT ck_transaction_limit_policy_per_tx CHECK (
        (per_tx_kind = 'LIMITED' AND per_tx_amount IS NOT NULL AND per_tx_amount >= 0)
        OR (per_tx_kind = 'UNLIMITED' AND per_tx_amount IS NULL)),
    CONSTRAINT ck_transaction_limit_policy_daily CHECK (
        (daily_kind = 'LIMITED' AND daily_amount IS NOT NULL AND daily_amount >= 0)
        OR (daily_kind = 'UNLIMITED' AND daily_amount IS NULL)),
    CONSTRAINT ck_transaction_limit_policy_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_transaction_limit_policy_seoul_midnight CHECK (
        TIME(effective_from) = '15:00:00' AND (effective_to IS NULL OR TIME(effective_to) = '15:00:00')),
    CONSTRAINT ck_transaction_limit_policy_not_retroactive CHECK (effective_from >= created_at),
    CONSTRAINT ck_transaction_limit_policy_end_actor CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CONSTRAINT ck_transaction_limit_policy_end_not_retroactive CHECK (ended_at IS NULL OR effective_to >= ended_at),
    CONSTRAINT ck_transaction_limit_policy_version CHECK (version >= 0),
    INDEX ix_transaction_limit_policy_merchant (merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ② 수수료·정산 정책 버전 식별 등록부(D-MS2-003, append-only). 내용 모델은 SETTLEMENT-CONTRACT-001. content_status UNDEFINED·DEMO 모두
--    "내용 없는 버전" — TEST/Simulator 전용, LIVE 허용 Gate에서 차단. 같은 (종류, 코드)의 번호는 UNIQUE(동시 등록은 재시도로 다음 번호).
CREATE TABLE policy_version (
    policy_version_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_kind       VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_code       VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version_no        INT          NOT NULL,
    content_status    VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_hash      CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    registered_at     DATETIME(3)  NOT NULL,
    registered_by     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_policy_version PRIMARY KEY (policy_version_id),
    CONSTRAINT uk_policy_version_code UNIQUE (policy_kind, policy_code, version_no),
    CONSTRAINT uk_policy_version_kind UNIQUE (policy_version_id, policy_kind),
    CONSTRAINT ck_policy_version_id CHECK (REGEXP_LIKE(policy_version_id, '^pv_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_policy_version_kind CHECK (policy_kind IN ('FEE_SCHEDULE', 'SETTLEMENT_POLICY')),
    CONSTRAINT ck_policy_version_code CHECK (REGEXP_LIKE(policy_code, '^[A-Z0-9_-]{1,32}$', 'c')),
    CONSTRAINT ck_policy_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_policy_version_content_status CHECK (content_status IN ('UNDEFINED', 'DEMO')),
    CONSTRAINT ck_policy_version_content_hash CHECK (content_hash IS NULL OR REGEXP_LIKE(content_hash, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ③ 정책 Assignment(D-MD-018): (CSA, MID, 통화, 종류) → 정책 버전. CSA = MID 발급 CSA(복합 FK), 대상 버전의 종류 일치(복합 FK).
--    효력 [from, to) 소급 시작 금지, 열린 행은 (MID, 통화, 종류) 하나, 닫힌 구간 중첩은 merchant 잠금 아래 domain 검사. 종료는 UPDATE.
CREATE TABLE policy_assignment (
    assignment_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    agreement_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    currency_code       CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_kind         VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_version_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effective_from      DATETIME(3)  NOT NULL,
    effective_to        DATETIME(3)  NULL,
    reason              VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_at            DATETIME(3)  NULL,
    ended_by            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    version             BIGINT       NOT NULL,
    open_assignment_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(merchant_account_id, '|', currency_code, '|', policy_kind), NULL)) STORED,
    CONSTRAINT pk_policy_assignment PRIMARY KEY (assignment_id),
    CONSTRAINT uk_policy_assignment_open UNIQUE (open_assignment_key),
    CONSTRAINT fk_policy_assignment_account FOREIGN KEY (merchant_account_id, merchant_id) REFERENCES merchant_account (merchant_account_id, merchant_id),
    CONSTRAINT fk_policy_assignment_issuing_agreement FOREIGN KEY (merchant_account_id, agreement_id)
        REFERENCES merchant_account (merchant_account_id, issuing_agreement_id),
    CONSTRAINT fk_policy_assignment_version FOREIGN KEY (policy_version_id, policy_kind) REFERENCES policy_version (policy_version_id, policy_kind),
    CONSTRAINT fk_policy_assignment_unit FOREIGN KEY (currency_code) REFERENCES currency_unit (currency_code),
    CONSTRAINT ck_policy_assignment_id CHECK (REGEXP_LIKE(assignment_id, '^pas_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_policy_assignment_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_policy_assignment_not_retroactive CHECK (effective_from >= created_at),
    CONSTRAINT ck_policy_assignment_end_actor CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CONSTRAINT ck_policy_assignment_end_not_retroactive CHECK (ended_at IS NULL OR effective_to >= ended_at),
    CONSTRAINT ck_policy_assignment_version CHECK (version >= 0),
    INDEX ix_policy_assignment_account (merchant_account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ④ 카드사(매입사)/Route 개통(D-MD-017, D-MS2-004): (MID, 수단, 매입사)당 종료 아닌 행 하나 = Route 하나(생성 컬럼 UNIQUE). 상태 전이는 수단 개통과
--    같다(D-MS-003 준용). route_ref는 PAYMENT 로컬 기관 프로파일 ID(SIMULATOR/REAL의 권위는 PAYMENT), institution_merchant_ref는 기관 계약계정 —
--    내부 식별자 등급(D-MS2-005, 로그·응답 원문 금지). 기관 Credential·키는 이 표에 없다.
CREATE TABLE card_route_activation (
    activation_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id              VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_method           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquirer_code            VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    route_ref                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_merchant_ref VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status                   VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref             VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    registered_at            DATETIME(3)  NOT NULL,
    registered_by            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status_changed_at        DATETIME(3)  NOT NULL,
    request_record_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version                  BIGINT       NOT NULL,
    open_route_key           VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status <> 'TERMINATED', CONCAT(merchant_account_id, '|', payment_method, '|', acquirer_code), NULL)) STORED,
    CONSTRAINT pk_card_route_activation PRIMARY KEY (activation_id),
    CONSTRAINT uk_card_route_activation_open UNIQUE (open_route_key),
    CONSTRAINT fk_card_route_activation_account FOREIGN KEY (merchant_account_id, merchant_id)
        REFERENCES merchant_account (merchant_account_id, merchant_id),
    CONSTRAINT ck_card_route_activation_id CHECK (REGEXP_LIKE(activation_id, '^cra_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_card_route_activation_method CHECK (payment_method IN ('CARD')),
    CONSTRAINT ck_card_route_activation_acquirer CHECK (REGEXP_LIKE(acquirer_code, '^[A-Z0-9_]{1,32}$', 'c')),
    CONSTRAINT ck_card_route_activation_route CHECK (REGEXP_LIKE(route_ref, '^[A-Za-z0-9._:-]{1,64}$', 'c')),
    CONSTRAINT ck_card_route_activation_institution_ref CHECK (REGEXP_LIKE(institution_merchant_ref, '^[A-Za-z0-9._:-]{1,64}$', 'c')),
    CONSTRAINT ck_card_route_activation_status CHECK (status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT ck_card_route_activation_active_evidence CHECK (status <> 'ACTIVE' OR evidence_ref IS NOT NULL),
    CONSTRAINT ck_card_route_activation_changed CHECK (status_changed_at >= registered_at),
    CONSTRAINT ck_card_route_activation_version CHECK (version >= 0),
    INDEX ix_card_route_activation_account (merchant_account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑤ 카드사 개통 전이 이력(append-only). 등록은 from_status NULL → UNDER_REVIEW.
CREATE TABLE card_route_activation_event (
    activation_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq               INT          NOT NULL,
    from_status       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    to_status         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    actor_ref         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_card_route_activation_event PRIMARY KEY (activation_id, seq),
    CONSTRAINT fk_card_route_activation_event_activation FOREIGN KEY (activation_id) REFERENCES card_route_activation (activation_id),
    CONSTRAINT ck_card_route_activation_event_seq CHECK (seq >= 1),
    CONSTRAINT ck_card_route_activation_event_to CHECK (to_status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT ck_card_route_activation_event_from CHECK (from_status IS NULL OR from_status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_card_route_activation_event_active_evidence CHECK (to_status <> 'ACTIVE' OR evidence_ref IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑥ 발행 기록의 Snapshot schemaVersion(MASTER-SNAPSHOT-002 §5.2 — 옛 버전 발행 뒤 갱신은 revision을 올린 승격 발행). 기존 행은 모두 v1 발행이다.
ALTER TABLE entitlement_snapshot_issue ADD COLUMN schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE entitlement_snapshot_issue ALTER COLUMN schema_version DROP DEFAULT;
ALTER TABLE entitlement_snapshot_issue ADD CONSTRAINT ck_entitlement_snapshot_issue_schema CHECK (schema_version >= 1);
