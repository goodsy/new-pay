-- ADMIN-PAYMENT-OPS-001 §4.11(S4) runtime 권한(위치 db/runtime-privilege/admin — 업무 schema V5와 분리, 관례 §9.2·§19). 초기화 스크립트의 procedure만 부른다.
-- 순서 주의(관례 §19 측정): 열 단위 UPDATE를 받는 표는 표 단위 helper를 **먼저**, 열 helper를 그 뒤에 부른다(뒤에 표 helper를 다시 부르면 여집합 REVOKE가 열 UPDATE를 회수).
-- 기존 표 권한은 바꾸지 않는다(admin_approval_request의 새 열 target_kind·target_ref는 생성 불변 — 열 권한 없음 1143, admin_audit_event는 기존 SELECT, INSERT).
-- 시험: AdminDatabaseAccountSpec(표·열 권한 정확 비교, INSERT-only 1142, 불변 열 1143), AdminPersistenceMappingSpec(열 권한 = Entity updatable 열).

-- 운영자 보정 취소 요청 고정 값·대사 발견: INSERT-only(UPDATE·DELETE·잠금 읽기 1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_operator_cancel_command', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_ops_reconciliation_finding', 'SELECT, INSERT');

-- 실행 행: SELECT, INSERT + 변경 열만 UPDATE(= JpaOwnerExecutionEntity updatable 열). 저장 본문·지문·승인 시각·기한·실행자는 1143. 열 helper는 1회 8열 상한이라 둘로 나눈다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_owner_execution', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_owner_execution',
    'attempt_count, next_attempt_at, last_attempt_at, last_response_at, last_failure, integrity_flag, resend_halted_reason, owner_result_code');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_owner_execution', 'owner_reference, version');

-- 대사 checkpoint: 단일 행은 V5가 만든다 — runtime은 SELECT + 열 UPDATE만(INSERT·DELETE 1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_ops_reconciliation_checkpoint', 'SELECT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('admin_ops_reconciliation_checkpoint', 'watermark, last_run_at, last_full_scan_at, version');
