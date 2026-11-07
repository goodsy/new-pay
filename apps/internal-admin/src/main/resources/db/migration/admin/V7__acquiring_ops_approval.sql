-- ACQ-ADMIN-001 B1-A(§6·§17.5 1): 재매입 2인 승인(ACQ_REACQUISITION — 요청 OPERATOR, 승인 APPROVER + TOTP, 실행은 FINANCIAL이 조작 전용 키 채널로 재검사 — D-ACQA-001·002)의
-- ADMIN DB 구조. runtime 권한은 db/runtime-privilege/admin/V8__acquiring_ops_approval_privileges.sql(위치 분리 — 관례 §9.2). 기존 PAYMENT 승인 행·제약 의미는 바꾸지 않는다
-- (CHECK는 새 종류·대상만 더한다). MySQL 8.4: 생성 열 식 변경은 표 재작성(개발 볼륨 작음 — V5와 같은 주의).
-- 시험: AdminSchemaSpec(V7 — CHECK 3819·ACQFILE UNIQUE 1062·지문 acd1·명령 FK 1452), AdminDatabaseAccountSpec(V8), AcquiringOpsApprovalFlowSpec.

-- 1) 종류·대상 CHECK 확장: 파일 대상(target_kind ACQUISITION_FILE, target_ref = 원 파일 ID, target_actor_id NULL)은 재매입 종류만, 재매입 종류는 파일 대상만.
--    소유 서비스 전용 상태(EXECUTING·EXECUTION_UNKNOWN)는 소유 서비스 두 종류만(ADMIN 소유 6종 차단 유지 — H-1 DB 방어).
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_target,
    DROP CHECK ck_admin_approval_request_owner_states;

ALTER TABLE admin_approval_request
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT' AND kind NOT IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION') AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)
        OR (target_kind = 'ACQUISITION_FILE' AND kind = 'ACQ_REACQUISITION' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c'))),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN') OR kind IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION'));

-- 2) 미해결 승인 하나: Payment는 그대로 'PAYMENT:<paymentId>', 원 파일은 'ACQFILE:<fileId>'(원 파일당 미해결 재매입 승인 하나 — §17.5 1 가역 해석 I-19: 같은 레코드 이중 승인을
--    파일 단위로 막는다. FINANCIAL은 UNIQUE(거래, 종류, 새 세대)·결정 표로 다시 막는다). 종료 상태면 NULL. 길이: 'ACQFILE:' 8 + 파일 ID 최대 54 ≤ 80.
ALTER TABLE admin_approval_request
    MODIFY COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN
            CASE target_kind WHEN 'PAYMENT' THEN CONCAT('PAYMENT:', target_ref) WHEN 'ACQUISITION_FILE' THEN CONCAT('ACQFILE:', target_ref) END
        END) STORED;

-- 3) 실행 행 지문: 운영자 보정 취소 oct1(대상 지문) 또는 재매입 acd1(FINANCIAL 결정 내용 hash와 같은 값 — ReacquisitionCommandBodySpec 독립 벡터).
ALTER TABLE admin_owner_execution DROP CHECK ck_admin_owner_execution_fingerprint;
ALTER TABLE admin_owner_execution
    ADD CONSTRAINT ck_admin_owner_execution_fingerprint CHECK (REGEXP_LIKE(fingerprint, '^(oct1|acd1):[0-9a-f]{64}$', 'c'));

-- 4) 재매입 요청 고정 값(INSERT-only — runtime SELECT, INSERT만 1142, Entity @Immutable). pinned_target(레코드별 세대·상태·예상 순액)이 승인 TX1 비교의 권위 값이고,
--    승인 행 target_snapshot에는 같은 JSON을 표시용 사본으로 넣는다. 사유 문구는 여기에만(FINANCIAL에는 사유 코드·근거 참조만 — I-4). 카드사 값 없음(D-ACQV-002).
CREATE TABLE admin_acquiring_ops_command (
    approval_id      VARCHAR(30)  NOT NULL,
    original_file_id VARCHAR(64)  NOT NULL,
    reason_code      VARCHAR(32)  NOT NULL,
    evidence_ref     VARCHAR(128) NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    pinned_target    JSON         NOT NULL,
    created_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_acquiring_ops_command PRIMARY KEY (approval_id),
    CONSTRAINT fk_admin_acquiring_ops_command_approval FOREIGN KEY (approval_id) REFERENCES admin_approval_request (approval_id),
    CONSTRAINT ck_admin_acquiring_ops_command_file CHECK (REGEXP_LIKE(original_file_id, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c')),
    CONSTRAINT ck_admin_acquiring_ops_command_reason_code CHECK (reason_code IN ('INSTITUTION_CONFIRMED_ERROR', 'DATA_CORRECTED_AT_SOURCE', 'INSTITUTION_REQUESTED')),
    CONSTRAINT ck_admin_acquiring_ops_command_evidence CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
