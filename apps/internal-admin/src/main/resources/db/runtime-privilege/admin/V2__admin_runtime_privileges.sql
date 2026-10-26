-- ADMIN-BOOT-001 §4.2.2 runtime 권한(위치 db/runtime-privilege/admin — 업무 schema와 분리, jOOQ 생성기는 적용하지 않는다, ARCHITECTURE-CONVENTIONS §9.2).
-- 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)의 procedure만 부른다(받는 쪽 = paycore_admin_runtime 고정, DEFINER = 잠긴 grantor).
-- 순서 주의(관례 §19 측정): 열 단위 UPDATE를 받는 표는 표 단위 helper('SELECT, INSERT')를 **먼저** 부르고 열 helper를 그 뒤에 부른다.
--   표 helper를 나중에 다시 부르면 여집합 REVOKE UPDATE가 열 단위 UPDATE까지 회수한다 — 이 표들에 표 단위 helper를 다시 쓰지 않는다.
-- 열 helper는 1회 8열 상한이라 admin_account는 세 번 나눠 부른다. 열 목록 = JPA Entity의 updatable 열(S3에서 대조 시험).
-- 시험: AdminDatabaseAccountSpec(표·열 권한 정확 비교, append-only UPDATE/DELETE 1142, 불변 열 1143, 열 UPDATE 표의 잠금 읽기 허용 — S0 m-4).

-- append-only(확정 기록): SELECT, INSERT만 — UPDATE·DELETE·잠금 읽기 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_role_event', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_audit_event', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_bootstrap', 'SELECT, INSERT');

-- 계정: 표 단위 SELECT, INSERT + 열 단위 UPDATE(actor_id·login_name·created_at 제외 — 1143). DELETE 없음(actor ID 재사용 방지 — D-ADMIN-004).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_account', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_account',
    'display_name, status, status_before_suspend, pending_purpose, pending_basis, password_hash, totp_secret_ciphertext, totp_key_version');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_account',
    'last_totp_step, failed_attempts, locked_at, active_roles, suspended_roles, session_generation, security_version, version');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_account', 'updated_at, credential_basis, credential_basis_requester, credential_basis_approver, credential_basis_carried');

-- 승인 요청: 결정 열만 UPDATE(종류·대상·요청자·대상 버전·요청·만료 시각은 1143).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_approval_request', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_approval_request',
    'status, decided_by, decided_by_display_name, decided_at, result_code, version');

-- 등록 코드: 소비·폐기 시각만 UPDATE.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_enrollment_code', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_enrollment_code', 'used_at, revoked_at');

-- 복구 회차: 종료 시각만 UPDATE.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_recovery_episode', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_recovery_episode', 'closed_at');
