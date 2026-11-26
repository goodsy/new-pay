-- MERCHANT-FLOW-001 FLOW-2a 전제 점검(명세 §4B.10 0 — V15·V19 복구 관례). 두 용도:
--   ① V21 실행 **전**(LIVE Gate): V21 첫 단계 guard와 같은 데이터 판정 4개를 사람이 보고 기록한다(migrate_ok = 1이어야 실행).
--   ② V21이 중간에 실패한 DB에서 되돌림(business-contract-v21-revert.sql) **전**: 되돌림 전제(runtime 연결·Flyway 기록·migration 밖 행)를 보고 기록한다(revert_ok).
--      같은 판정이 되돌림 스크립트 머리에도 들어 있어 하나라도 0이 아니면 되돌림이 실행을 거부한다.
-- 실행 계정: DBA(다른 세션을 볼 수 있는 PROCESS 권한 — 권한이 없으면 runtime 연결 점검이 자기 세션만 보고 0이 된다). 대상 DB를 기본 DB로 연결한다(DATABASE()).
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 BusinessContractMigrationSpec이 고정한다.
--
-- 결과 열(모두 0이어야 ok):
--   intake_terminated / transition_terminated / nonmigrated_completed / stored_completed_exists : V21 전제(①) — migrate_ok.
--   runtime_connections   : 이 DB의 runtime 계정 연결 수(MASTER web 전 프로세스·이전 버전·Demo seed 실행기 정지 확인).
--   flyway_state_mismatch : flyway_schema_history가 "V21 실패 기록(success = 0) 하나·V22 없음"이 아니면 1.
--   foreign_rows          : migration 밖 행 수(되돌리면 사라지거나 V19 제약을 되살릴 수 없는 행 — 지우지 않는다) — revert_ok.

-- V21 실행 전제(V21 첫 단계 guard와 같은 판정 — 운영 실행 전 LIVE Gate에서 사람이 본다). 0이 아니면 V21은 1146(v21_refused_precheck_failed)으로 멈춘다(자동 변환 없음 — Coordinator 보고).
SET @intake_terminated = (SELECT COUNT(*) FROM contract_intake WHERE status = 'TERMINATED');
SET @transition_terminated = (SELECT COUNT(*) FROM contract_intake_transition WHERE to_status = 'TERMINATED');
SET @nonmigrated_completed = (SELECT COUNT(*) FROM contract_intake WHERE migrated = 0 AND status = 'COMPLETED');
SET @stored_completed_exists = (SELECT COUNT(*) FROM admin_request WHERE result_code = 'CONTRACT_INTAKE_COMPLETED_EXISTS');

-- 유지 주의: 아래 판정 블록(SET @runtime_user … SET @revert_violations)은 business-contract-v21-precheck.sql과 business-contract-v21-revert.sql에 **같은 내용으로 두 번** 있다
-- (되돌림이 별도 점검 없이 단독 실행돼도 스스로 거부하게 — V15·V19 복구 스크립트 관례). 한쪽을 고치면 다른 쪽도 같이 고친다 — BusinessContractMigrationSpec이 두 스크립트를 함께 시험한다.
-- @runtime_user는 환경마다 runtime 계정 이름으로 바꿔 넣는다(이 저장소 초기화 스크립트 기본값 = paycore_master — 운영은 DBA가 실제 계정 이름으로 치환).
SET @runtime_user = 'paycore_master';
SET @runtime_connections = (SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = @runtime_user);

-- 되돌림은 **V21이 실패로 기록된 DB에서만** — flyway_schema_history에 version 21 행이 정확히 하나이고 success = 0, version 22 행 없음.
-- 완료된 V21·V22 DB, V21이 아직 없는 DB(V20), 기록이 다른 DB는 모두 거부한다(완료 DB의 사업자 계약·이관 행은 업무 기록이다).
SET @flyway_mismatch = IF(
    (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21') = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success = 0) = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '22') = 0, 0, 1);

-- migration 밖 행이 하나라도 있으면 거부(앱·사람이 쓴 기록을 지우지 않는다): 이관 모양이 아닌 사업자 계약·버전·범위 행, 닫기·사건 행(이관은 만들지 않는다),
-- 변경 접수(kind <> NEW 또는 대상 있음), V19 열린·완료 건 UNIQUE(uk_cin_active)를 되살릴 수 없는 사업자(열린·완료 건 2개 이상).
SET @foreign_rows = 0;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract'),
    'SELECT COUNT(*) INTO @c FROM business_contract WHERE NOT (migrated = 1 AND contract_seq = 1 AND origin_intake_seq = 1 AND created_by = ''system:migration'')',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_version'),
    'SELECT COUNT(*) INTO @c FROM business_contract_version WHERE NOT (migrated = 1 AND version_no = 1 AND contract_seq = 1 AND created_by = ''system:migration'')',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_version_service'),
    'SELECT COUNT(*) INTO @c FROM business_contract_version_service WHERE NOT (contract_seq = 1 AND version_no = 1 AND service_kind = ''CARD_PG_PAYMENT'')',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_version_close'),
    'SELECT COUNT(*) INTO @c FROM business_contract_version_close', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contract_event'),
    'SELECT COUNT(*) INTO @c FROM business_contract_event', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'kind'),
    'SELECT COUNT(*) INTO @c FROM contract_intake WHERE NOT (COALESCE(kind, ''NEW'') = ''NEW'' AND target_contract_seq IS NULL)', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @foreign_rows = @foreign_rows + (SELECT COUNT(*) FROM (SELECT business_id FROM contract_intake WHERE status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED')
    GROUP BY business_id HAVING COUNT(*) > 1) AS crowded);

SET @revert_violations = @runtime_connections + @flyway_mismatch + @foreign_rows;

SELECT @intake_terminated AS intake_terminated, @transition_terminated AS transition_terminated, @nonmigrated_completed AS nonmigrated_completed,
    @stored_completed_exists AS stored_completed_exists,
    IF(@intake_terminated + @transition_terminated + @nonmigrated_completed + @stored_completed_exists = 0, 1, 0) AS migrate_ok,
    @runtime_connections AS runtime_connections, @flyway_mismatch AS flyway_state_mismatch, @foreign_rows AS foreign_rows, IF(@revert_violations = 0, 1, 0) AS revert_ok;
