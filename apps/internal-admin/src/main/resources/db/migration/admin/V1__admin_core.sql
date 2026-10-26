-- ADMIN-BOOT-001 §4.2.2 ADMIN DB(internal-admin 소유, D-ADMIN-005) — 관리 메타데이터만(금융 사실 없음, AC1).
-- 규칙: 문자열 키·값은 utf8mb4_0900_bin(입력 그대로 비교), 시각은 DATETIME(3) UTC, 상태·종류는 CHECK로 고정 값만.
-- runtime 권한(표 단위 SELECT·INSERT + 열 단위 UPDATE — 생성 불변 열 제외, DELETE 없음)은 db/runtime-privilege/admin/V2(위치 분리 — jOOQ 생성기는 이 위치만 적용, §9.2).
-- 시험: AdminSchemaSpec(제약), AdminDatabaseAccountSpec(권한).

-- 관리자 계정(Aggregate root). actor ID = 'adm_' + ULID(불변·재사용 금지 — DELETE 권한 없음), login_name 변경 기능 없음(D-06).
-- 현재 활성·정지 역할 집합은 이벤트(admin_role_event)와 같은 TX에서 갱신하는 현재값이다(요청마다 PK 단건으로 판정 — §4.5).
CREATE TABLE admin_account (
    actor_id               VARCHAR(30)  NOT NULL,
    login_name             VARCHAR(64)  NOT NULL,
    display_name           VARCHAR(100) NOT NULL,
    status                 VARCHAR(24)  NOT NULL,
    status_before_suspend  VARCHAR(24)  NULL,
    pending_purpose        VARCHAR(24)  NULL,
    pending_basis          VARCHAR(40)  NULL,
    -- 현재 자격을 만들 코드의 발급 근거(D-ADMIN1-014·015): 근거 ID와 그 요청자·승인자(코드 수령자). 다음 코드 발급 때만 바뀐다(코드 사용·정지와 무관).
    credential_basis           VARCHAR(40) NOT NULL,
    credential_basis_requester VARCHAR(30) NULL,
    credential_basis_approver  VARCHAR(30) NULL,
    -- D-98(재리뷰 m-2): ENROLL 재발급은 자격을 바꾸지 않으므로 등록 완료 전까지 이전 발급자들도 배제(쉼표 구분, 최대 32명). 완료 때 비운다.
    credential_basis_carried   VARCHAR(1000) NULL,
    password_hash          VARCHAR(100) NULL,
    totp_secret_ciphertext VARBINARY(64) NULL,
    totp_key_version       INT          NULL,
    last_totp_step         BIGINT       NULL,
    failed_attempts        INT          NOT NULL,
    locked_at              DATETIME(3)  NULL,
    active_roles           SET('VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK') NOT NULL,
    suspended_roles        SET('VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK') NOT NULL,
    session_generation     BIGINT       NOT NULL,
    security_version       BIGINT       NOT NULL,
    version                BIGINT       NOT NULL,
    created_at             DATETIME(3)  NOT NULL,
    updated_at             DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_account PRIMARY KEY (actor_id),
    CONSTRAINT uk_admin_account_login_name UNIQUE (login_name),
    CONSTRAINT ck_admin_account_status CHECK (status IN ('PENDING_ENROLLMENT', 'ACTIVE', 'LOCKED', 'SUSPENDED')),
    CONSTRAINT ck_admin_account_status_before_suspend
        CHECK (status_before_suspend IS NULL OR status_before_suspend IN ('PENDING_ENROLLMENT', 'ACTIVE', 'LOCKED')),
    CONSTRAINT ck_admin_account_pending_purpose
        CHECK (pending_purpose IS NULL OR pending_purpose IN ('ENROLL', 'PASSWORD_RESET', 'TOTP_REENROLL', 'RECOVERY')),
    CONSTRAINT ck_admin_account_counters
        CHECK (failed_attempts >= 0 AND session_generation >= 0 AND security_version >= 0 AND version >= 0),
    -- 대기 사유와 발급 근거는 함께 있거나 함께 없다. 대기 사유는 PENDING_ENROLLMENT에서만(H-B). 역방향(PENDING ⇒ 사유 있음)은 두지 않는다 —
    -- 정지 TX가 사유를 비운 계정을 정지 해제하면 사유 없는 PENDING_ENROLLMENT로 돌아간다(§4.3.1, 새 코드 발급 전 등록 불가).
    CONSTRAINT ck_admin_account_pending_pair CHECK ((pending_purpose IS NULL) = (pending_basis IS NULL)),
    CONSTRAINT ck_admin_account_pending_status CHECK (pending_purpose IS NULL OR status = 'PENDING_ENROLLMENT'),
    -- 정지 직전 상태는 SUSPENDED 계정에만, SUSPENDED 계정에는 반드시(정지 해제가 돌아갈 상태 — domain 불변식과 같다, S3 리뷰 nit).
    CONSTRAINT ck_admin_account_suspend_pair CHECK ((status = 'SUSPENDED') = (status_before_suspend IS NOT NULL)),
    -- 대기 중 코드의 근거 = 자격 발급 근거, 승인자는 요청자와 함께만(domain 불변식과 같다 — D-ADMIN1-014).
    CONSTRAINT ck_admin_account_credential_basis CHECK (pending_basis IS NULL OR pending_basis = credential_basis),
    CONSTRAINT ck_admin_account_credential_issuers
        CHECK (credential_basis_approver IS NULL OR (credential_basis_requester IS NOT NULL AND credential_basis_approver <> credential_basis_requester)),
    -- 재리뷰 m-3: 계정은 자기 자격의 발급 근거 참여자가 아니다(NULL 허용).
    CONSTRAINT ck_admin_account_credential_not_self
        CHECK ((credential_basis_requester IS NULL OR credential_basis_requester <> actor_id) AND (credential_basis_approver IS NULL OR credential_basis_approver <> actor_id))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 역할 이벤트(append-only — runtime SELECT, INSERT만). 이력 원천. 원인 참조(cause_ref) = 재설정 승인 ID 또는 복구 회차.
CREATE TABLE admin_role_event (
    event_id        BIGINT       NOT NULL AUTO_INCREMENT,
    target_actor_id VARCHAR(30)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    event_type      VARCHAR(16)  NOT NULL,
    actor_kind      VARCHAR(20)  NOT NULL,
    actor_id        VARCHAR(30)  NULL,
    approval_id     VARCHAR(30)  NULL,
    cause_ref       VARCHAR(40)  NULL,
    occurred_at     DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_role_event PRIMARY KEY (event_id),
    CONSTRAINT fk_admin_role_event_target FOREIGN KEY (target_actor_id) REFERENCES admin_account (actor_id),
    CONSTRAINT ck_admin_role_event_role CHECK (role IN ('VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK')),
    CONSTRAINT ck_admin_role_event_type CHECK (event_type IN ('GRANT', 'REVOKE', 'SUSPEND', 'REACTIVATE')),
    CONSTRAINT ck_admin_role_event_actor_kind CHECK (actor_kind IN ('ADMIN', 'SYSTEM_BOOTSTRAP', 'SYSTEM_RECOVERY')),
    INDEX ix_admin_role_event_target (target_actor_id, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 1회용 등록 코드(§4.3.6). 원문은 저장하지 않고 SHA-256만 PK. 대상당 활성 1개는 발급 TX가 이전 코드를 revoked_at으로 폐기해 지킨다.
-- runtime 열 단위 UPDATE는 used_at·revoked_at만(원자 소비 UPDATE … WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > ?).
CREATE TABLE admin_enrollment_code (
    code_hash           BINARY(32)   NOT NULL,
    target_actor_id     VARCHAR(30)  NOT NULL,
    purpose             VARCHAR(24)  NOT NULL,
    basis               VARCHAR(40)  NOT NULL,
    recovery_episode_no BIGINT       NULL,
    recovery_roles      SET('SECURITY_ADMIN', 'APPROVER') NULL,
    issued_at           DATETIME(3)  NOT NULL,
    expires_at          DATETIME(3)  NOT NULL,
    used_at             DATETIME(3)  NULL,
    revoked_at          DATETIME(3)  NULL,
    CONSTRAINT pk_admin_enrollment_code PRIMARY KEY (code_hash),
    CONSTRAINT fk_admin_enrollment_code_target FOREIGN KEY (target_actor_id) REFERENCES admin_account (actor_id),
    CONSTRAINT ck_admin_enrollment_code_purpose CHECK (purpose IN ('ENROLL', 'PASSWORD_RESET', 'TOTP_REENROLL', 'RECOVERY')),
    -- 복구 코드(작업 A)는 회차와 복구 시점 SA·APPROVER 보유 집합(빈 집합 허용)을 가진다. 보유 집합은 복구 코드에만.
    -- 역방향(회차 번호 ⇒ RECOVERY)은 두지 않는다 — 복구 작업 B의 새 계정 ENROLL 코드도 회차 번호를 기록한다(§4.9.2 회차 종료 판정).
    CONSTRAINT ck_admin_enrollment_code_recovery
        CHECK (purpose <> 'RECOVERY' OR (recovery_episode_no IS NOT NULL AND recovery_roles IS NOT NULL)),
    CONSTRAINT ck_admin_enrollment_code_recovery_roles CHECK (recovery_roles IS NULL OR purpose = 'RECOVERY'),
    INDEX ix_admin_enrollment_code_target (target_actor_id),
    INDEX ix_admin_enrollment_code_episode (recovery_episode_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 2인 승인 요청(§4.7). 같은 (종류, 대상) PENDING은 하나(생성 열 pending_key UNIQUE — n-6). 소유 서비스 종류(OPS·Webhook)와 그 상태
-- (APPROVED·EXECUTING·EXECUTION_UNKNOWN)는 그 작업의 migration이 종류와 함께 CHECK를 넓힌다(D-59 — 지금은 ADMIN 소유 6종의 상태만 저장 가능).
-- runtime 열 단위 UPDATE는 결정 열(status·결정자·표시명·결정 시각·결과 code·version)만 — 종류·대상·요청자·대상 버전은 1143.
-- 대상·요청자 FK는 두지 않는다(S2 리뷰 M-1): 대상 FK는 INSERT가 부모 계정 행에 S 잠금을 걸어, §4.13 생성 순서(INSERT → 계정 FOR UPDATE)에서
-- 같은 대상의 다른 종류 요청 두 건을 동시에 만들면 1213이 났다(AdminSchemaSpec 재현). runtime에 계정 DELETE 권한이 없어 참조가 끊기지 않고,
-- 대상 존재는 생성 TX가 계정 FOR UPDATE 아래에서 검사한다(S5).
CREATE TABLE admin_approval_request (
    approval_id               VARCHAR(30)  NOT NULL,
    kind                      VARCHAR(40)  NOT NULL,
    target_actor_id           VARCHAR(30)  NOT NULL,
    target_security_version   BIGINT       NOT NULL,
    target_snapshot           JSON         NOT NULL,
    granted_role              VARCHAR(16)  NULL,
    cause_approval_id         VARCHAR(30)  NULL,
    requested_by              VARCHAR(30)  NOT NULL,
    requested_by_display_name VARCHAR(100) NOT NULL,
    requested_at              DATETIME(3)  NOT NULL,
    expires_at                DATETIME(3)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL,
    decided_by                VARCHAR(30)  NULL,
    decided_by_display_name   VARCHAR(100) NULL,
    decided_at                DATETIME(3)  NULL,
    result_code               VARCHAR(48)  NULL,
    version                   BIGINT       NOT NULL,
    pending_key               VARCHAR(80)  GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN CONCAT(kind, ':', target_actor_id) END) STORED,
    CONSTRAINT pk_admin_approval_request PRIMARY KEY (approval_id),
    CONSTRAINT uk_admin_approval_request_pending UNIQUE (pending_key),
    CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE')),
    CONSTRAINT ck_admin_approval_request_status CHECK (status IN (
        'PENDING', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'INVALIDATED', 'OWNER_REJECTED')),
    CONSTRAINT ck_admin_approval_request_granted_role
        CHECK (granted_role IS NULL OR granted_role IN ('VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK')),
    INDEX ix_admin_approval_request_status (status, expires_at),
    INDEX ix_admin_approval_request_target (target_actor_id),
    INDEX ix_admin_approval_request_requester (requested_by, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 관리자 감사(append-only — runtime SELECT, INSERT만, §4.6). 비밀번호·코드·시크릿·세션 ID·CSRF 토큰을 담지 않는다.
-- 미인증 시도의 login_name은 비밀 키 HMAC-SHA256(login_name_hmac)으로만 남긴다(n-2).
CREATE TABLE admin_audit_event (
    event_id           BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at        DATETIME(3)  NOT NULL,
    actor_kind         VARCHAR(20)  NOT NULL,
    actor_id           VARCHAR(30)  NULL,
    actor_display_name VARCHAR(100) NULL,
    executor_actor_id  VARCHAR(30)  NULL,
    action             VARCHAR(48)  NOT NULL,
    target_actor_id    VARCHAR(30)  NULL,
    result_code        VARCHAR(48)  NOT NULL,
    approval_id        VARCHAR(30)  NULL,
    correlation_id     VARCHAR(64)  NOT NULL,
    remote_ip          VARCHAR(45)  NULL,
    login_name_hmac    BINARY(32)   NULL,
    detail             VARCHAR(500) NULL,
    -- 마스킹 해제 사유(사용자 입력 — UNMASK 행에만, 제어 문자 없음·500자 이하, S5 D-88). detail은 고정 요약만이라 자유 문구를 넣지 않는다.
    reason             VARCHAR(500) NULL,
    CONSTRAINT pk_admin_audit_event PRIMARY KEY (event_id),
    CONSTRAINT ck_admin_audit_event_actor_kind CHECK (actor_kind IN ('ADMIN', 'ANONYMOUS', 'SYSTEM_BOOTSTRAP', 'SYSTEM_RECOVERY', 'SYSTEM_JOB')),
    INDEX ix_admin_audit_event_actor (actor_id, event_id),
    INDEX ix_admin_audit_event_time (occurred_at, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 부트스트랩 1회성 고정 행(§4.9.1 — 계정 생성과 같은 TX에 INSERT, 동시 실행 패자는 PK 1062).
CREATE TABLE admin_bootstrap (
    id                      TINYINT      NOT NULL,
    executed_at             DATETIME(3)  NOT NULL,
    security_admin_actor_id VARCHAR(30)  NOT NULL,
    approver_actor_id       VARCHAR(30)  NOT NULL,
    CONSTRAINT pk_admin_bootstrap PRIMARY KEY (id),
    CONSTRAINT ck_admin_bootstrap_single_row CHECK (id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 복구 회차(§4.9.2). 열린 회차는 하나(생성 열 open_marker UNIQUE — 닫히면 NULL이라 UNIQUE에서 빠진다). runtime 열 단위 UPDATE는 closed_at만.
CREATE TABLE admin_recovery_episode (
    episode_no  BIGINT      NOT NULL,
    opened_at   DATETIME(3) NOT NULL,
    expires_at  DATETIME(3) NOT NULL,
    closed_at   DATETIME(3) NULL,
    open_marker TINYINT     GENERATED ALWAYS AS (CASE WHEN closed_at IS NULL THEN 1 END) STORED,
    CONSTRAINT pk_admin_recovery_episode PRIMARY KEY (episode_no),
    CONSTRAINT uk_admin_recovery_episode_open UNIQUE (open_marker)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
