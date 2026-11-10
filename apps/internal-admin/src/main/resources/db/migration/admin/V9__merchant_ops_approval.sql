-- MERCHANT-OPS-002 B2-2(§9.5): 가맹점 2인 승인(MERCHANT_CONTRACT_APPROVAL — 요청 OPERATOR, 승인 APPROVER + TOTP, 실행은 MASTER가 승인 근거·업무 규칙을 재검사)의 ADMIN DB 구조.
-- B2-3(MERCHANT_FIRST_MID_ISSUANCE)도 같은 대상·같은 미해결 키·같은 명령 표를 쓰므로 CHECK에 두 종류를 함께 둔다(명세 §9.5 — 앱은 B2-3 전까지 그 종류를 만들지 않는다).
-- runtime 권한은 db/runtime-privilege/admin/V10__merchant_ops_approval_privileges.sql(위치 분리 — 관례 §9.2). 기존 PAYMENT·재매입 승인 행·제약 의미는 바꾸지 않는다(CHECK는 새 종류·대상만 더한다).
-- MySQL 8.4: 생성 열 식 변경은 표 재작성(개발 볼륨 작음 — V5·V7과 같은 주의). 시험: AdminSchemaSpec(V9 — CHECK 3819·MERCHANT UNIQUE 1062·mop1·명령 FK 1452), AdminDatabaseAccountSpec(V10).

-- 1) 종류·대상 CHECK 확장: 가맹점 대상(target_kind MERCHANT, target_ref = merchantId, target_actor_id NULL, 대상 버전 0 — C-16)은 가맹점 두 종류만, 가맹점 두 종류는 가맹점 대상만.
--    소유 서비스 전용 상태(EXECUTING·EXECUTION_UNKNOWN)는 소유 서비스 종류만(ADMIN 소유 6종 차단 유지 — H-1 DB 방어).
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_target,
    DROP CHECK ck_admin_approval_request_owner_states;

ALTER TABLE admin_approval_request
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION',
        'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT'
            AND kind NOT IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE')
            AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)
        OR (target_kind = 'ACQUISITION_FILE' AND kind = 'ACQ_REACQUISITION' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c'))
        OR (target_kind = 'MERCHANT' AND kind IN ('MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE') AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c'))),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN')
        OR kind IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE'));

-- 2) 미해결 승인 하나: Payment·원 파일은 그대로, 가맹점은 'MERCHANT:<merchantId>'(두 종류 합쳐 가맹점당 미해결 하나 — C-20). 종료 상태면 NULL. 길이: 'MERCHANT:' 9 + 30 ≤ 80.
ALTER TABLE admin_approval_request
    MODIFY COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN
            CASE target_kind
                WHEN 'PAYMENT' THEN CONCAT('PAYMENT:', target_ref)
                WHEN 'ACQUISITION_FILE' THEN CONCAT('ACQFILE:', target_ref)
                WHEN 'MERCHANT' THEN CONCAT('MERCHANT:', target_ref)
            END
        END) STORED;

-- 3) 실행 행 지문: oct1·acd1에 가맹점 승인 mop1(MASTER 근거 지문과 같은 값 — ContractApprovalCommandBodySpec 독립 벡터, C-23)을 더한다.
ALTER TABLE admin_owner_execution DROP CHECK ck_admin_owner_execution_fingerprint;
ALTER TABLE admin_owner_execution
    ADD CONSTRAINT ck_admin_owner_execution_fingerprint CHECK (REGEXP_LIKE(fingerprint, '^(oct1|acd1|mop1):[0-9a-f]{64}$', 'c'));

-- 4) 운영 대사 발견 종류: 기존 6값(V5 — review n-c 확인) + OWNER_UNRESOLVABLE(C-28 — 기한 뒤에도 MASTER 결정으로 확정할 수 없는 건, 승인당 1회).
ALTER TABLE admin_ops_reconciliation_finding DROP CHECK ck_admin_ops_reconciliation_finding_kind;
ALTER TABLE admin_ops_reconciliation_finding
    ADD CONSTRAINT ck_admin_ops_reconciliation_finding_kind CHECK (kind IN (
        'ORPHAN_DECISION', 'FINGERPRINT_MISMATCH', 'STATE_DIVERGENCE', 'MISSING_DECISION', 'UNRESOLVED_WITH_DECISION', 'DECISION_FOR_UNAPPROVED', 'OWNER_UNRESOLVABLE'));

-- 5) 가맹점 승인 요청 고정 값(INSERT-only — runtime SELECT, INSERT만 1142, Entity @Immutable). pinned_target(가맹점·계약·Revision·Scope·기간·등록자)이 승인 TX1 비교의 권위 값이고,
--    승인 행 target_snapshot에는 같은 JSON을 표시용 사본으로 넣는다. 업무 근거 참조(evidence_ref — D-MOPS-008 ① 필수, 재매입 evidence_ref와 같은 문법)는 여기에만 둔다
--    (pinned·승인 행 사본·감사·로그·목록·상세에 없음 — APPROVER·요청자만 /justification). 사업자등록번호·채널 식별값·자유 문구 없음.
CREATE TABLE admin_merchant_ops_command (
    approval_id   VARCHAR(30)  NOT NULL,
    command_kind  VARCHAR(32)  NOT NULL,
    merchant_id   VARCHAR(30)  NOT NULL,
    pinned_target JSON         NOT NULL,
    evidence_ref  VARCHAR(128) NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_merchant_ops_command PRIMARY KEY (approval_id),
    CONSTRAINT fk_admin_merchant_ops_command_approval FOREIGN KEY (approval_id) REFERENCES admin_approval_request (approval_id),
    CONSTRAINT ck_admin_merchant_ops_command_kind CHECK (command_kind IN ('CONTRACT_APPROVAL', 'FIRST_MID_ISSUANCE')),
    CONSTRAINT ck_admin_merchant_ops_command_merchant CHECK (REGEXP_LIKE(merchant_id, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_merchant_ops_command_evidence CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
