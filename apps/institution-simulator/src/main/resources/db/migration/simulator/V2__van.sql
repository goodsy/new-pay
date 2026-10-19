-- 가상 VAN 저장소(AUTH-001 §5.5, Simulator 전용 MySQL — PayCore DB와 분리). 새 표만 둔다(기존 표 변경은 V3 — MySQL DDL은 TX가 아니라 섞지 않는다).
-- 카드번호·CVC 열 없음(가상 카드 참조만). 원장은 기관 금융 사실 원본이다: PG 재시작·PG DB rollback과 무관하게 남고 수정·삭제하지 않는다(runtime 권한 SELECT·INSERT — V4).

-- 원장(append-only): namespace(client·environment·institution_account·operation·request_id)당 한 행 — 결정(DECISION) 또는 무효과 종결 표지(CLOSURE).
CREATE TABLE van_ledger_entry (
    entry_id                     CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                  VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation                    VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id                   CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    kind                         VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload_hash                 CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    evidence_ref                 VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    card_ref                     VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    outcome                      VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approval_number              VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_transaction_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approved_amount_minor        BIGINT       NULL,
    currency                     CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    plan_kind                    VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    installment_months           INT          NULL,
    card_type                    VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_transaction_date DATE         NULL,
    institution_business_date    DATE         NULL,
    decline_category             VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at                   DATETIME(3)  NOT NULL,
    CONSTRAINT pk_van_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT uk_van_ledger_entry_namespace UNIQUE (client_id, environment, institution_account, operation, request_id),
    CONSTRAINT ck_van_ledger_entry_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_van_ledger_entry_operation CHECK (operation IN ('AUTHORIZE')),
    CONSTRAINT ck_van_ledger_entry_kind CHECK (kind IN ('DECISION', 'CLOSURE')),
    CONSTRAINT ck_van_ledger_entry_outcome CHECK (outcome IS NULL OR outcome IN ('APPROVED', 'DECLINED')),
    CONSTRAINT ck_van_ledger_entry_decline CHECK (decline_category IS NULL OR decline_category IN ('ISSUER_DECLINED', 'AUTHENTICATION_INVALID', 'PLAN_NOT_SUPPORTED')),
    -- 결정: payload hash·증거·outcome 필수, 승인 조건은 APPROVED에만(전부 함께), 거절 분류는 DECLINED에만. 종결 표지: 결정 열 전부 NULL.
    CONSTRAINT ck_van_ledger_entry_shape CHECK (
        (kind = 'DECISION' AND payload_hash IS NOT NULL AND evidence_ref IS NOT NULL AND outcome IS NOT NULL
            AND (outcome = 'APPROVED') = (approval_number IS NOT NULL)
            AND (approval_number IS NULL) = (provider_transaction_id IS NULL)
            AND (approval_number IS NULL) = (approved_amount_minor IS NULL)
            AND (approval_number IS NULL) = (currency IS NULL)
            AND (approval_number IS NULL) = (plan_kind IS NULL)
            AND (approval_number IS NULL) = (card_type IS NULL)
            AND (approval_number IS NULL) = (institution_transaction_date IS NULL)
            AND (approval_number IS NULL) = (institution_business_date IS NULL)
            AND (outcome = 'DECLINED') = (decline_category IS NOT NULL))
        OR (kind = 'CLOSURE' AND payload_hash IS NULL AND evidence_ref IS NULL AND outcome IS NULL AND approval_number IS NULL AND provider_transaction_id IS NULL
            AND approved_amount_minor IS NULL AND currency IS NULL AND plan_kind IS NULL AND installment_months IS NULL AND card_type IS NULL
            AND institution_transaction_date IS NULL AND institution_business_date IS NULL AND decline_category IS NULL)),
    CONSTRAINT ck_van_ledger_entry_amount CHECK (approved_amount_minor IS NULL OR approved_amount_minor >= 1),
    CONSTRAINT ck_van_ledger_entry_plan CHECK (
        plan_kind IS NULL
        OR (plan_kind = 'LUMP_SUM' AND installment_months IS NULL)
        OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    INDEX ix_van_ledger_entry_evidence_ref (evidence_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- PENDING 예약(rev 0.3 H-3): 확정 사실이 아니다. 결정 확정은 원장 새 DECISION 행(같은 UNIQUE(namespace)에서 종결 표지와 경합) + 이 행 RESOLVED.
CREATE TABLE van_pending_decision (
    pending_id          CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id          CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload_hash        CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    card_ref            VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind           VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months  INT          NULL,
    card_type           VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    resolve_after       DATETIME(3)  NOT NULL,
    state               VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    resolved_entry_id   CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    CONSTRAINT pk_van_pending_decision PRIMARY KEY (pending_id),
    CONSTRAINT uk_van_pending_decision_namespace UNIQUE (client_id, environment, institution_account, operation, request_id),
    CONSTRAINT ck_van_pending_decision_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_van_pending_decision_state CHECK (state IN ('PENDING', 'RESOLVED') AND (state = 'RESOLVED') = (resolved_entry_id IS NOT NULL)),
    CONSTRAINT ck_van_pending_decision_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_van_pending_decision_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 인증 증거 사용(append-only, §5.3-3 "다른 요청 ID에 쓰이지 않음"): 증거 하나 = 요청 ID 하나. PK가 다른 요청 ID의 동시 사용을 직렬화한다.
CREATE TABLE van_evidence_use (
    evidence_ref        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id          CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    CONSTRAINT pk_van_evidence_use PRIMARY KEY (evidence_ref),
    CONSTRAINT ck_van_evidence_use_environment CHECK (environment = 'TEST')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 조회 기록(append-only): 조회 1회 = 1행. request_kind는 종결 요청 여부(LOOKUP·CLOSE_IF_ABSENT).
CREATE TABLE van_inquiry_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    client_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_request_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    inquiry_id          CHAR(29)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_kind        VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result              VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    CONSTRAINT pk_van_inquiry_log PRIMARY KEY (id),
    CONSTRAINT ck_van_inquiry_log_request_kind CHECK (request_kind IN ('LOOKUP', 'CLOSE_IF_ABSENT')),
    CONSTRAINT ck_van_inquiry_log_result CHECK (result IN ('FOUND', 'PENDING', 'NOT_FOUND', 'NO_EFFECT_CONFIRMED', 'UNAVAILABLE')),
    INDEX ix_van_inquiry_log_namespace (client_id, environment, institution_account, original_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
