-- 가상 VAN 취소 원장(CANCEL-001 §6.5 — CN-21, rev 0.3 n-6: 새 표만. van_inquiry_log ALTER는 V6, 권한은 V7 — MySQL DDL은 TX가 아니라 섞지 않는다).
-- append-only 기관 금융 사실 원본: namespace(client·environment·institution_account·operation CANCEL·request_id)당 한 행 — 결정(DECISION) 또는 무효과 종결 표지(CLOSURE).
-- 같은 원 승인의 승인 취소는 순번 approved_sequence(n + 1)로 직렬화한다(CN-22): UNIQUE(원 승인, 순번) 1062 → 새 TX 재판정. 거절·종결 표지는 순번 NULL(UNIQUE 대상 아님).
-- MySQL CHECK는 NULL로 평가되면 통과하므로 모양 CHECK는 IS NULL / IS NOT NULL을 명시한다. 카드번호·CVC 열 없음(원 승인의 가상 카드 참조만).
CREATE TABLE van_cancel_entry (
    entry_id                          CHAR(29)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                       VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account               VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation                         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id                        CHAR(29)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    kind                              VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload_hash                      CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    original_request_id               CHAR(29)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    card_ref                          VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    outcome                           VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    cancel_approval_number            VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_transaction_id           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    original_provider_transaction_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    cancelled_amount_minor            BIGINT      NULL,
    currency                          CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_transaction_date      DATE        NULL,
    institution_business_date         DATE        NULL,
    approved_sequence                 INT         NULL,
    decline_category                  VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at                        DATETIME(3) NOT NULL,
    CONSTRAINT pk_van_cancel_entry PRIMARY KEY (entry_id),
    CONSTRAINT uk_van_cancel_entry_namespace UNIQUE (client_id, environment, institution_account, operation, request_id),
    CONSTRAINT uk_van_cancel_entry_approved_sequence UNIQUE (client_id, environment, institution_account, original_request_id, approved_sequence),
    CONSTRAINT ck_van_cancel_entry_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_van_cancel_entry_operation CHECK (operation = 'CANCEL'),
    CONSTRAINT ck_van_cancel_entry_kind CHECK (kind IN ('DECISION', 'CLOSURE')),
    CONSTRAINT ck_van_cancel_entry_outcome CHECK (outcome IS NULL OR outcome IN ('APPROVED', 'DECLINED')),
    CONSTRAINT ck_van_cancel_entry_decline CHECK (decline_category IS NULL OR decline_category IN (
        'ORIGINAL_NOT_FOUND', 'ORIGINAL_NOT_APPROVED', 'ORIGINAL_MISMATCH', 'CANCEL_PERIOD_EXPIRED', 'PARTIAL_NOT_SUPPORTED', 'EXCEEDS_REMAINING', 'ISSUER_DECLINED')),
    -- 승인 결정: 취소 조건 전부(취소 승인번호만 NULL 허용 — 기관이 주지 않을 수 있음, n-5)·순번 NOT NULL, 거절 분류 NULL.
    -- 거절 결정: 거절 분류 NOT NULL, 취소 조건·순번 전부 NULL. 종결 표지: 결정 열 전부 NULL.
    CONSTRAINT ck_van_cancel_entry_shape CHECK (
        (kind = 'DECISION' AND payload_hash IS NOT NULL AND original_request_id IS NOT NULL AND outcome IS NOT NULL AND (
            (outcome = 'APPROVED' AND provider_transaction_id IS NOT NULL AND original_provider_transaction_id IS NOT NULL AND cancelled_amount_minor IS NOT NULL
                AND currency IS NOT NULL AND institution_transaction_date IS NOT NULL AND institution_business_date IS NOT NULL AND approved_sequence IS NOT NULL
                AND decline_category IS NULL)
            OR (outcome = 'DECLINED' AND decline_category IS NOT NULL AND cancel_approval_number IS NULL AND provider_transaction_id IS NULL
                AND original_provider_transaction_id IS NULL AND cancelled_amount_minor IS NULL AND currency IS NULL AND institution_transaction_date IS NULL
                AND institution_business_date IS NULL AND approved_sequence IS NULL)))
        OR (kind = 'CLOSURE' AND payload_hash IS NULL AND original_request_id IS NULL AND card_ref IS NULL AND outcome IS NULL AND cancel_approval_number IS NULL
            AND provider_transaction_id IS NULL AND original_provider_transaction_id IS NULL AND cancelled_amount_minor IS NULL AND currency IS NULL
            AND institution_transaction_date IS NULL AND institution_business_date IS NULL AND approved_sequence IS NULL AND decline_category IS NULL)),
    CONSTRAINT ck_van_cancel_entry_amount CHECK (cancelled_amount_minor IS NULL OR cancelled_amount_minor >= 1),
    CONSTRAINT ck_van_cancel_entry_sequence CHECK (approved_sequence IS NULL OR approved_sequence >= 1),
    INDEX ix_van_cancel_entry_original (client_id, environment, institution_account, original_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
