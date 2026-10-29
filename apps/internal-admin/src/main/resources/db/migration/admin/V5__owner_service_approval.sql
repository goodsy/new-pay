-- ADMIN-PAYMENT-OPS-001 §4.11(S4): 소유 서비스 승인(OPERATOR_CORRECTION_CANCEL — 요청 OPERATOR, 승인 APPROVER + TOTP, 실행은 PAYMENT가 재검사)의 ADMIN DB 구조.
-- runtime 권한은 db/runtime-privilege/admin/V6__owner_service_approval_privileges.sql(위치 분리 — 관례 §9.2). MySQL 8.4: 생성 열 추가·NULL 변경은 표 재작성(개발 볼륨 작음,
-- 운영 적용 전 확인 — 명세 R-4). 시험: AdminSchemaSpec(CHECK 3819·UNIQUE 1062·FK 1452·기존 행 채움), AdminDatabaseAccountSpec(V6), UnresolvedApprovalRaceSpec.

-- 1) 승인 요청: 대상 종류(계정 | Payment). 기존 행은 ADD COLUMN 기본값으로 ADMIN_ACCOUNT가 채워지고, 그 뒤 기본값을 없앤다(새 행은 앱이 반드시 명시 — 1364).
--    Payment 대상은 target_ref(paymentId), 계정 대상은 기존 target_actor_id(이제 NULL 허용). 대상 버전은 같은 target_security_version 열(Payment = balanceRevision, I-13).
ALTER TABLE admin_approval_request
    ADD COLUMN target_kind VARCHAR(16) NOT NULL DEFAULT 'ADMIN_ACCOUNT' AFTER kind,
    MODIFY COLUMN target_actor_id VARCHAR(30) NULL,
    ADD COLUMN target_ref VARCHAR(64) NULL AFTER target_actor_id;

ALTER TABLE admin_approval_request ALTER COLUMN target_kind DROP DEFAULT;

-- 2) 종류·상태 CHECK 확장(APPROVED는 쓰지 않는다 — I-20), 대상 열 조합 + 종류 일치, 소유 서비스 전용 상태는 소유 서비스 종류만(ADMIN 소유 6종의 EXECUTING 저장 차단 — H-1 DB 방어).
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_status;

ALTER TABLE admin_approval_request
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL')),
    ADD CONSTRAINT ck_admin_approval_request_status CHECK (status IN (
        'PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'INVALIDATED', 'OWNER_REJECTED')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT' AND kind <> 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN') OR kind = 'OPERATOR_CORRECTION_CANCEL');

-- 3) Payment 하나에 미해결 승인 하나(I-14): PENDING·EXECUTING·EXECUTION_UNKNOWN이면 'PAYMENT:<paymentId>', 종료 상태면 NULL(UNIQUE는 NULL을 여럿 허용).
--    pending_key(같은 종류·계정 대상 PENDING 하나)는 그대로 — Payment 행은 target_actor_id가 NULL이라 pending_key도 NULL(F-10).
ALTER TABLE admin_approval_request
    ADD COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN target_kind = 'PAYMENT' AND status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN CONCAT('PAYMENT:', target_ref) END) STORED,
    ADD CONSTRAINT uk_admin_approval_request_unresolved UNIQUE (unresolved_key),
    ADD INDEX ix_admin_approval_request_target_ref (target_kind, target_ref);

-- 4) 운영자 보정 취소 요청 고정 값(INSERT-only — runtime SELECT, INSERT만 1142, Entity @Immutable). pinned_target이 승인 TX1 비교의 권위 값이고,
--    승인 행 target_snapshot에는 같은 JSON을 목록 표시용 사본으로 넣는다. 사유·근거는 여기에만 저장한다(로그·지표·감사 detail에 없음 — §6).
CREATE TABLE admin_operator_cancel_command (
    approval_id   VARCHAR(30)  NOT NULL,
    payment_id    VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    purpose_kind  VARCHAR(40)  NOT NULL,
    purpose_ref   VARCHAR(200) NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    pinned_target JSON         NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_operator_cancel_command PRIMARY KEY (approval_id),
    CONSTRAINT fk_admin_operator_cancel_command_approval FOREIGN KEY (approval_id) REFERENCES admin_approval_request (approval_id),
    CONSTRAINT ck_admin_operator_cancel_command_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_admin_operator_cancel_command_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_admin_operator_cancel_command_purpose CHECK (purpose_kind IN ('INCIDENT_OR_RECON_CORRECTION', 'MERCHANT_TERMINATED_OBLIGATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 5) 소유 서비스 실행 행(승인 TX1이 INSERT). 불변: 저장 본문(재전송은 이 바이트 그대로)·지문·승인 시각·실행 기한·실행자(= 승인자) — 열 권한 없음 1143.
--    변경: 시도 수(보낸 명령 수 — TX1이 1, 회복 선점만 +1)·다음/마지막 시도·마지막 응답·마지막 실패·무결성 플래그·재전송 중단·소유 결과·version(@Version, L-4).
CREATE TABLE admin_owner_execution (
    approval_id          VARCHAR(30)  NOT NULL,
    request_body         MEDIUMBLOB   NOT NULL,
    fingerprint          VARCHAR(69)  NOT NULL,
    approved_at          DATETIME(3)  NOT NULL,
    execute_before       DATETIME(3)  NOT NULL,
    executor_actor_id    VARCHAR(30)  NOT NULL,
    attempt_count        INT          NOT NULL,
    next_attempt_at      DATETIME(3)  NOT NULL,
    last_attempt_at      DATETIME(3)  NULL,
    last_response_at     DATETIME(3)  NULL,
    last_failure         VARCHAR(48)  NULL,
    integrity_flag       VARCHAR(48)  NULL,
    resend_halted_reason VARCHAR(48)  NULL,
    owner_result_code    VARCHAR(48)  NULL,
    owner_reference      VARCHAR(64)  NULL,
    version              BIGINT       NOT NULL,
    CONSTRAINT pk_admin_owner_execution PRIMARY KEY (approval_id),
    CONSTRAINT fk_admin_owner_execution_approval FOREIGN KEY (approval_id) REFERENCES admin_approval_request (approval_id),
    CONSTRAINT ck_admin_owner_execution_attempts CHECK (attempt_count >= 1),
    CONSTRAINT ck_admin_owner_execution_fingerprint CHECK (REGEXP_LIKE(fingerprint, '^oct1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_admin_owner_execution_deadline CHECK (execute_before > approved_at),
    INDEX ix_admin_owner_execution_next (next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 6) 대사(D-OPS-008 — S7이 쓴다): checkpoint 단일 행(id = 1, 이 migration이 만든다 — runtime은 INSERT 없음), 발견 INSERT-only + UNIQUE(kind, approval_id)
--    (같은 발견 반복 INSERT 없음 — 경보 폭주 방지, 1062는 "이미 기록됨"). ORPHAN_DECISION은 ADMIN에 없는 approvalId일 수 있어 승인 FK가 없다.
CREATE TABLE admin_ops_reconciliation_checkpoint (
    id                TINYINT     NOT NULL,
    watermark         BIGINT      NOT NULL,
    last_run_at       DATETIME(3) NULL,
    last_full_scan_at DATETIME(3) NULL,
    version           BIGINT      NOT NULL,
    CONSTRAINT pk_admin_ops_reconciliation_checkpoint PRIMARY KEY (id),
    CONSTRAINT ck_admin_ops_reconciliation_checkpoint_single_row CHECK (id = 1),
    CONSTRAINT ck_admin_ops_reconciliation_checkpoint_watermark CHECK (watermark >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO admin_ops_reconciliation_checkpoint (id, watermark, last_run_at, last_full_scan_at, version) VALUES (1, 0, NULL, NULL, 0);

CREATE TABLE admin_ops_reconciliation_finding (
    finding_id  BIGINT       NOT NULL AUTO_INCREMENT,
    kind        VARCHAR(32)  NOT NULL,
    approval_id VARCHAR(64)  NOT NULL,
    payment_ref VARCHAR(64)  NULL,
    detail_code VARCHAR(200) NULL,
    found_at    DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_ops_reconciliation_finding PRIMARY KEY (finding_id),
    CONSTRAINT uk_admin_ops_reconciliation_finding UNIQUE (kind, approval_id),
    CONSTRAINT ck_admin_ops_reconciliation_finding_kind CHECK (kind IN (
        'ORPHAN_DECISION', 'FINGERPRINT_MISMATCH', 'STATE_DIVERGENCE', 'MISSING_DECISION', 'UNRESOLVED_WITH_DECISION', 'DECISION_FOR_UNAPPROVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 7) 감사: 새 종류의 모든 감사 행은 대상 Payment를 target_ref에 남긴다(target_actor_id NULL — §4.10, I-15). 쓰기는 S5(감사 Entity).
ALTER TABLE admin_audit_event ADD COLUMN target_ref VARCHAR(64) NULL AFTER target_actor_id;
