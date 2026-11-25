-- MERCHANT-FLOW-001 FLOW-1 부분 실패 복구 — 전제 점검(V15 복구 관례). V19(db/migration/master/V19__contract_intake.sql)가 중간에 실패한 MASTER DB에서 되돌림(contract-intake-v19-revert.sql)
-- **전에** 실행하고 결과를 기록한다. 같은 판정이 되돌림 스크립트 머리에도 들어 있어 하나라도 0이 아니면 되돌림이 실행을 거부한다.
-- 실행 계정: DBA(다른 세션을 볼 수 있는 PROCESS 권한 — 권한이 없으면 runtime 연결 점검이 자기 세션만 보고 0이 된다). 대상 DB를 기본 DB로 연결한다(DATABASE()).
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 ContractIntakeMigrationSpec이 고정한다.
--
-- 점검(모두 0이어야 ok = 1):
--   runtime_connections   : 이 DB의 runtime 계정 연결 수(MASTER web 전 프로세스·이전 버전·Demo seed 실행기 정지 확인).
--   flyway_state_mismatch : flyway_schema_history가 "V19 실패 기록(success = 0) 하나·V20 없음"이 아니면 1.
--   foreign_rows          : 접수 건·전이 이력 표(있으면)의 이관 모양이 아닌 행 수(앱·사람이 쓴 행 — 지우지 않는다).
-- 프로세스 목록·이전 버전 인스턴스 확인 외 절차는 사람이 기록한다(V15 L-8 절차와 같다).

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
SELECT @runtime_connections AS runtime_connections, @flyway_mismatch AS flyway_state_mismatch, @foreign_rows AS foreign_rows, IF(@violations = 0, 1, 0) AS ok;
