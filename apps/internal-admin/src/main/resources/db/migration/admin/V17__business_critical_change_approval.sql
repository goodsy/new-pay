-- BIZ-CRIT-APPROVAL-001 S3(명세 rev 0.3a §3.1·§3.2·§4.1 — D-GOV-016 ③·D-GOV-025·D-GOV-026): 사업자 중요정보 변경 2인 승인(종류 BUSINESS_CRITICAL_CHANGE, 대상 BUSINESS).
-- 마지막 schema 번호 V15(V16은 권한 변경 없음으로 비어 있다) 다음이라 V17, runtime 권한은 V18. 적용된 V1~V15는 고치지 않는다.
-- 1) 종류·대상·소유 서비스 상태 CHECK 확장(DROP·ADD를 한 ALTER 문으로 — CHECK 없는 중간 상태 없음): 사업자 대상(target_kind BUSINESS, target_ref = biz_<ULID>, target_actor_id NULL,
--    대상 버전 0 — 기준 version은 명령 행 base_version)은 이 종류만, 이 종류는 사업자 대상만. 기존 값 집합·식은 V13·V15 그대로.
-- 2) 미해결 키 `BUSINESS:<biz_>`(사업자당 미해결 하나 — PENDING·EXECUTING·EXECUTION_UNKNOWN). 기존 키 식은 V13 그대로.
-- 3) admin_business_change_command: 승인 하나의 고정 값(제안 ID·기준 version·digest·항목 이름·대표자 중요 변경·정정 여부·근거 참조). **상호·개인정보 열 없음**(M-5). INSERT 전용(V18).
-- 부분 실패: MySQL DDL은 문장마다 암묵 commit — 중간 실패 시 migration 계정으로 만들어진 객체를 확인·정리(DROP TABLE admin_business_change_command, CHECK·생성 열은 V15 식으로 되돌림) →
-- `flyway repair` → 다시 migrate(MASTER V26과 같은 절차).
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_target,
    DROP CHECK ck_admin_approval_request_owner_states,
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION',
        'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
        'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
        'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE', 'BUSINESS_CRITICAL_CHANGE')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT'
            AND kind NOT IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE', 'BUSINESS_CRITICAL_CHANGE')
            AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)
        OR (target_kind = 'ACQUISITION_FILE' AND kind = 'ACQ_REACQUISITION' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c'))
        OR (target_kind = 'MERCHANT'
            AND kind IN ('MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE')
            AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^(mer_[0-9A-HJKMNP-TV-Z]{26}|[a-z][a-z0-9]{5,13})$', 'c'))
        OR (target_kind = 'BUSINESS' AND kind = 'BUSINESS_CRITICAL_CHANGE' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^biz_[0-9A-HJKMNP-TV-Z]{26}$', 'c'))),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN')
        OR kind IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
            'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
            'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE', 'BUSINESS_CRITICAL_CHANGE')),
    ALGORITHM=COPY;

ALTER TABLE admin_approval_request
    MODIFY COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN
            CASE target_kind
                WHEN 'PAYMENT' THEN CONCAT('PAYMENT:', target_ref)
                WHEN 'ACQUISITION_FILE' THEN CONCAT('ACQFILE:', target_ref)
                WHEN 'BUSINESS' THEN CONCAT('BUSINESS:', target_ref)
                WHEN 'MERCHANT' THEN
                    CASE kind
                        WHEN 'MERCHANT_PAYMENT_METHOD_ACTIVATION' THEN CONCAT('MERCHANT:PM:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_PAYMENT_METHOD_RESUME' THEN CONCAT('MERCHANT:PM:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_SITE_REVIEW_APPROVAL' THEN CONCAT('MERCHANT:SITE_REVIEW:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CURRENCY_ENABLEMENT' THEN CONCAT('MERCHANT:CURRENCY:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CARD_ROUTE_ACTIVATION' THEN CONCAT('MERCHANT:ROUTE:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CARD_ROUTE_RESUME' THEN CONCAT('MERCHANT:ROUTE:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_SITE_ACTIVATION_CONFIRMATION' THEN CONCAT('MERCHANT:SITE_CONFIRM:', COALESCE(target_merchant_id, target_ref))
                        ELSE CONCAT('MERCHANT:', COALESCE(target_merchant_id, target_ref))
                    END
            END
        END) STORED;

CREATE TABLE admin_business_change_command (
    approval_id            VARCHAR(30)  NOT NULL,
    business_id            VARCHAR(30)  NOT NULL,
    base_version           BIGINT       NOT NULL,
    proposal_id            VARCHAR(30)  NOT NULL,
    content_digest         CHAR(67)     NOT NULL,
    changed_fields         VARCHAR(512) NOT NULL,
    representative_changed BOOLEAN      NOT NULL,
    subject_correction     BOOLEAN      NOT NULL,
    evidence_ref           VARCHAR(128) NOT NULL,
    created_at             DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_business_change_command PRIMARY KEY (approval_id),
    CONSTRAINT uk_admin_business_change_command_proposal UNIQUE (proposal_id),
    CONSTRAINT fk_admin_business_change_command_approval FOREIGN KEY (approval_id) REFERENCES admin_approval_request (approval_id),
    CONSTRAINT ck_admin_business_change_command_business CHECK (REGEXP_LIKE(business_id, '^biz_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_business_change_command_base_version CHECK (base_version >= 0),
    CONSTRAINT ck_admin_business_change_command_proposal CHECK (REGEXP_LIKE(proposal_id, '^bcp_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_business_change_command_digest CHECK (REGEXP_LIKE(content_digest, '^h1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_admin_business_change_command_fields CHECK (REGEXP_LIKE(changed_fields, '^[a-zA-Z.]+(,[a-zA-Z.]+)*$', 'c')),
    CONSTRAINT ck_admin_business_change_command_evidence CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
