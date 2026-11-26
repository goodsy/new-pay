-- MERCHANT-FLOW-001 FLOW-2a 부분 실패 복구 — 점검 SQL(V21 단계별 적용 여부 판정, contract-intake-v19-inspect.sql 관례). 되돌림 전(어디까지 적용됐나)·후(V20 상태인가)·V21 재실행 후
-- (완료인가) 세 번 실행해 기록한다. 실행 계정: DBA(대상 DB를 기본 DB로 연결 — DATABASE()). 운영 실행은 LIVE Gate. 정확성은 BusinessContractMigrationSpec이 고정한다.
-- 결과 열(V21 단계 — §4B.10):
--   intake_check_v21 / transition_check_v21 (1단계 — 0|1: 접수 건 ck_cin_status / 전이 이력 ck_cint_move에 TERMINATED가 없으면 1),
--   open_key / active_key (2단계 — 0|1: 열 있음), kind_column / kind_not_null / kind_checks (3단계 — 0|1, 0|1, ck_cin_kind·ck_cin_target·ck_cin_migrated_kind 수 0..3),
--   new_tables (4단계 — 새 표 수 0..5), fk_target (4단계 끝 — 0|1),
--   migrated_intakes (이관 완료 접수 건 수 = 이관 대상), contract_rows / version_rows / service_rows (5단계 — 표 없으면 NULL).
-- V20 상태 = 0, 0, 0, 1, 0, 0, 0, 0, 0, N, NULL, NULL, NULL. V21 완료(앱 실행 전) = 1, 1, 1, 0, 1, 1, 3, 5, 1, N, N, N, N.

SET @intake_check_v21 = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_cin_status'
    AND CHECK_CLAUSE NOT LIKE '%TERMINATED%');
SET @transition_check_v21 = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_cint_move'
    AND CHECK_CLAUSE NOT LIKE '%TERMINATED%');
SET @open_key = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'open_key');
SET @active_key = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'active_key');
SET @kind_column = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'kind');
SET @kind_not_null = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'kind'
    AND IS_NULLABLE = 'NO');
SET @kind_checks = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
    AND CONSTRAINT_NAME IN ('ck_cin_kind', 'ck_cin_target', 'ck_cin_migrated_kind'));
SET @new_tables = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('business_contract', 'business_contract_version',
    'business_contract_version_service', 'business_contract_version_close', 'business_contract_event'));
SET @fk_target = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake'
    AND CONSTRAINT_NAME = 'fk_cin_target' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @contract_rows = NULL;
SET @version_rows = NULL;
SET @service_rows = NULL;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract'),
    'SELECT COUNT(*) INTO @contract_rows FROM business_contract', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_version'),
    'SELECT COUNT(*) INTO @version_rows FROM business_contract_version', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_version_service'),
    'SELECT COUNT(*) INTO @service_rows FROM business_contract_version_service', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

SELECT
    @intake_check_v21 AS intake_check_v21,
    @transition_check_v21 AS transition_check_v21,
    @open_key AS open_key,
    @active_key AS active_key,
    @kind_column AS kind_column,
    @kind_not_null AS kind_not_null,
    @kind_checks AS kind_checks,
    @new_tables AS new_tables,
    @fk_target AS fk_target,
    (SELECT COUNT(*) FROM contract_intake WHERE migrated = 1 AND status = 'COMPLETED') AS migrated_intakes,
    @contract_rows AS contract_rows,
    @version_rows AS version_rows,
    @service_rows AS service_rows;
