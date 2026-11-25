-- MERCHANT-FLOW-001 FLOW-1 부분 실패 복구 — 점검 SQL(V19 문장별 적용 여부 판정, business-profile-v15-inspect.sql 관례). 되돌림 전(어디까지 적용됐나)·후(V17 상태인가)·V19 재실행 후(완료인가)
-- 세 번 실행해 기록한다. 실행 계정: DBA(대상 DB를 기본 DB로 연결 — DATABASE()). 운영 실행은 LIVE Gate. 정확성은 ContractIntakeMigrationSpec이 고정한다.
-- 결과 열: intake_table(문장 1 — 0|1), transition_table(문장 2 — 0|1), business_rows(사업자 행 수 — 이관 대상),
--   intake_rows / migrated_intake_rows(문장 3 — 접수 건 행 수 / 그중 이관 모양 행 수(순번 1·계약 완료·이관 표시·접수 항목 없음·version 0·system:migration·시각 = 사업자 생성 시각), 표 없으면 NULL),
--   transition_rows / migrated_transition_rows(문장 4 — 같은 방식, 표 없으면 NULL).
-- V17 상태 = 0, 0, N, NULL, NULL, NULL, NULL. V19 완료(앱 실행 전) = 1, 1, N, N, N, N, N(n-2 — 이관 건 = 순번 1 + 전이 이력 이관 행 1).

SET @intake_table = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake');
SET @transition_table = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake_transition');
SET @intake_rows = NULL;
SET @migrated_intake_rows = NULL;
SET @transition_rows = NULL;
SET @migrated_transition_rows = NULL;
SET @s = IF(@intake_table = 1,
    'SELECT COUNT(*), COALESCE(SUM(i.intake_seq = 1 AND i.status = ''COMPLETED'' AND i.migrated = 1 AND i.received_on IS NULL AND i.intake_channel IS NULL AND i.contract_manager IS NULL AND i.desires_card_auth = 0 AND i.desires_card_keyin = 0 AND i.version = 0 AND i.created_by = ''system:migration'' AND i.updated_by = ''system:migration'' AND i.created_at = b.created_at AND i.updated_at = b.created_at), 0) INTO @intake_rows, @migrated_intake_rows FROM contract_intake i LEFT JOIN business b ON b.business_id = i.business_id',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(@transition_table = 1,
    'SELECT COUNT(*), COALESCE(SUM(t.intake_seq = 1 AND t.transition_seq = 1 AND t.from_status IS NULL AND t.to_status = ''COMPLETED'' AND t.migrated = 1 AND t.reason IS NULL AND t.document_ref IS NULL AND t.actor_ref = ''system:migration'' AND t.occurred_at = b.created_at), 0) INTO @transition_rows, @migrated_transition_rows FROM contract_intake_transition t LEFT JOIN business b ON b.business_id = t.business_id',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

SELECT
    @intake_table AS intake_table,
    @transition_table AS transition_table,
    (SELECT COUNT(*) FROM business) AS business_rows,
    @intake_rows AS intake_rows,
    @migrated_intake_rows AS migrated_intake_rows,
    @transition_rows AS transition_rows,
    @migrated_transition_rows AS migrated_transition_rows;
