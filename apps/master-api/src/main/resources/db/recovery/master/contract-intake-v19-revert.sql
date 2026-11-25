-- MERCHANT-FLOW-001 FLOW-1 부분 실패 복구 — 되돌림 SQL. V19가 중간에 실패한 MASTER DB를 V17 상태로 되돌린다(적용된 것만 역순 제거): 전이 이력 표 DROP → 접수 건 표 DROP.
-- business·merchant·권리·Outbox 표와 행은 건드리지 않는다(V19는 그 표를 바꾸지 않는다 — 이관 행은 새 표에만 있다).
-- **머리의 전제 점검(contract-intake-v19-precheck.sql과 같은 판정)이 하나라도 0이 아니면 첫 DROP 전에 오류로 멈춘다**(없는 표를 PREPARE —
-- 오류 1146 `v19_revert_refused_precheck_failed`). 오류에서 멈추는 실행기로 실행한다(mysql 클라이언트 기본 동작 — `--force` 금지).
-- 실행 계정: DBA(PROCESS 권한·대상 DB DDL). 뒤이어 점검 SQL로 V17 상태 확인 → `flyway repair` → `migrate`(V19 재실행) → 점검 SQL로 완료 확인 → 기동.
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 ContractIntakeMigrationSpec이 고정한다.
-- 유지 주의: 아래 판정 블록(SET @runtime_user … SET @violations)은 contract-intake-v19-precheck.sql과 contract-intake-v19-revert.sql에 **같은 내용으로 두 번** 있다
-- (되돌림이 별도 점검 없이 단독 실행돼도 스스로 거부하게 — V15 복구 스크립트 관례). 한쪽을 고치면 다른 쪽도 같이 고친다 — ContractIntakeMigrationSpec이 두 스크립트를 함께 시험한다.
-- @runtime_user는 환경마다 runtime 계정 이름으로 바꿔 넣는다(이 저장소 초기화 스크립트 기본값 = paycore_master — 운영은 DBA가 실제 계정 이름으로 치환).
SET @runtime_user = 'paycore_master';
SET @runtime_connections = (SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = @runtime_user);

-- 되돌림은 **V19가 실패로 기록된 DB에서만** — flyway_schema_history에 version 19 행이 정확히 하나이고 success = 0, version 20 행 없음.
-- 완료된 V19·V20 DB, V19가 아직 없는 DB(V17), 기록이 다른 DB는 모두 거부한다(되돌림 대상 아님 — 완료 DB의 접수 건·이력은 업무 기록이다).
SET @flyway_mismatch = IF(
    (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19') = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19' AND success = 0) = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20') = 0, 0, 1);

-- migration 밖 행(이관 모양이 아닌 접수 건·전이 이력 행)이 하나라도 있으면 거부(앱·사람이 쓴 기록을 지우지 않는다). 이관 모양 = 점검 SQL의 migrated_* 판정과 같다.
SET @foreign_rows = 0;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake'),
    'SELECT COUNT(*) INTO @c FROM contract_intake i LEFT JOIN business b ON b.business_id = i.business_id WHERE NOT COALESCE(i.intake_seq = 1 AND i.status = ''COMPLETED'' AND i.migrated = 1 AND i.received_on IS NULL AND i.intake_channel IS NULL AND i.contract_manager IS NULL AND i.desires_card_auth = 0 AND i.desires_card_keyin = 0 AND i.version = 0 AND i.created_by = ''system:migration'' AND i.updated_by = ''system:migration'' AND i.created_at = b.created_at AND i.updated_at = b.created_at, FALSE)',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake_transition'),
    'SELECT COUNT(*) INTO @c FROM contract_intake_transition t LEFT JOIN business b ON b.business_id = t.business_id WHERE NOT COALESCE(t.intake_seq = 1 AND t.transition_seq = 1 AND t.from_status IS NULL AND t.to_status = ''COMPLETED'' AND t.migrated = 1 AND t.reason IS NULL AND t.document_ref IS NULL AND t.actor_ref = ''system:migration'' AND t.occurred_at = b.created_at, FALSE)',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @foreign_rows = @foreign_rows + @c;

SET @violations = @runtime_connections + @flyway_mismatch + @foreign_rows;

-- 전제 점검 관문: 위반이 있으면 여기서 오류(되돌림 거부 — DROP 0).
SET @s = IF(@violations = 0, 'DO 0', 'SELECT 1 FROM v19_revert_refused_precheck_failed');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 새 표(자식 → 부모 — FK 순서).
DROP TABLE IF EXISTS contract_intake_transition;
DROP TABLE IF EXISTS contract_intake;
