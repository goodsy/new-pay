-- MERCHANT-FLOW-001 FLOW-2a 부분 실패 복구 — 되돌림 SQL. V21이 중간에 실패한 MASTER DB를 V20 상태로 되돌린다(적용된 것만 역순 제거·복원):
-- 대상 FK → 새 표 5개(자식 → 부모) → 종류·대상 CHECK·열 → V19 열린·완료 건 UNIQUE(active_key·uk_cin_active) 복원 → open_key·uk_cin_open 제거 → V19 CHECK 복원.
-- business·merchant·권리·Outbox·전이 이력 행은 건드리지 않는다(V21은 그 표의 행을 바꾸지 않는다 — 접수 건 kind 열 값만 채웠고 열째 지운다).
-- **머리의 전제 점검(business-contract-v21-precheck.sql과 같은 판정)이 하나라도 0이 아니면 첫 변경 전에 오류로 멈춘다**(없는 표를 PREPARE —
-- 오류 1146 `v21_revert_refused_precheck_failed`). 오류에서 멈추는 실행기로 실행한다(mysql 클라이언트 기본 동작 — `--force` 금지).
-- 실행 계정: DBA(PROCESS 권한·대상 DB DDL). 뒤이어 점검 SQL로 V20 상태 확인 → `flyway repair` → `migrate`(V21 재실행) → 점검 SQL로 완료 확인 → 기동.
-- 각 단계는 그 단계가 적용됐을 때만 실행한다(information_schema 판정 — 어느 문장에서 실패한 DB든 같은 스크립트). V19 CHECK 복원은 항상 V19 정의로 다시 만든다(같은 결과).
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 BusinessContractMigrationSpec이 고정한다.
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

-- 전제 점검 관문: 위반이 있으면 여기서 오류(되돌림 거부 — 변경 0).
SET @s = IF(@revert_violations = 0, 'DO 0', 'SELECT 1 FROM v21_revert_refused_precheck_failed');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 4단계 끝: 변경 접수 대상 FK(와 MySQL이 FK용으로 만든 같은 이름 색인).
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND CONSTRAINT_NAME = 'fk_cin_target' AND CONSTRAINT_TYPE = 'FOREIGN KEY'),
    'ALTER TABLE contract_intake DROP FOREIGN KEY fk_cin_target, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND INDEX_NAME = 'fk_cin_target'),
    'ALTER TABLE contract_intake DROP INDEX fk_cin_target, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 4·5단계: 새 표(자식 → 부모 — FK 순서). 이관 행은 이 표에만 있다.
DROP TABLE IF EXISTS business_contract_event;
DROP TABLE IF EXISTS business_contract_version_close;
DROP TABLE IF EXISTS business_contract_version_service;
DROP TABLE IF EXISTS business_contract_version;
DROP TABLE IF EXISTS business_contract;

-- 3단계: 종류·대상 CHECK와 열.
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_cin_kind'),
    'ALTER TABLE contract_intake DROP CHECK ck_cin_kind, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_cin_target'),
    'ALTER TABLE contract_intake DROP CHECK ck_cin_target, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_cin_migrated_kind'),
    'ALTER TABLE contract_intake DROP CHECK ck_cin_migrated_kind, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'target_contract_seq'),
    'ALTER TABLE contract_intake DROP COLUMN target_contract_seq, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'kind'),
    'ALTER TABLE contract_intake DROP COLUMN kind, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 2단계: V19 열린·완료 건 UNIQUE를 먼저 되살리고(방어가 끊기지 않게) 그 뒤 open_key·uk_cin_open 제거. 열 순서는 V19와 같아진다(active_key가 마지막).
SET @s = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'active_key'),
    'ALTER TABLE contract_intake ADD COLUMN active_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IF(status IN (''CONSULTING'', ''DOCUMENT_RECEIVED'', ''COMPLETED''), business_id, NULL)) STORED, ADD CONSTRAINT uk_cin_active UNIQUE (active_key), ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND INDEX_NAME = 'uk_cin_open'),
    'ALTER TABLE contract_intake DROP INDEX uk_cin_open, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contract_intake' AND COLUMN_NAME = 'open_key'),
    'ALTER TABLE contract_intake DROP COLUMN open_key, ALGORITHM = COPY', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 1단계: V19 CHECK 정의로 다시 만든다(V19__contract_intake.sql과 같은 식 — 적용 전이어도 같은 결과).
ALTER TABLE contract_intake
    DROP CHECK ck_cin_status,
    DROP CHECK ck_cin_migrated,
    ADD CONSTRAINT ck_cin_status CHECK (status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED')),
    ADD CONSTRAINT ck_cin_migrated CHECK (migrated = 0 OR (intake_seq = 1 AND status IN ('COMPLETED', 'TERMINATED') AND received_on IS NULL AND intake_channel IS NULL
        AND contract_manager IS NULL AND desires_card_auth = 0 AND desires_card_keyin = 0)),
    ALGORITHM = COPY;
ALTER TABLE contract_intake_transition
    DROP CHECK ck_cint_status,
    DROP CHECK ck_cint_move,
    DROP CHECK ck_cint_document_target,
    DROP CHECK ck_cint_reason_required,
    ADD CONSTRAINT ck_cint_status CHECK (to_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED')
        AND (from_status IS NULL OR from_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED'))),
    ADD CONSTRAINT ck_cint_move CHECK (
        (from_status IS NULL AND ((to_status = 'CONSULTING' AND migrated = 0) OR (to_status = 'COMPLETED' AND migrated = 1)))
        OR (from_status IS NOT NULL AND migrated = 0 AND (
            (from_status = 'CONSULTING' AND to_status IN ('DOCUMENT_RECEIVED', 'REJECTED'))
            OR (from_status = 'DOCUMENT_RECEIVED' AND to_status IN ('CONSULTING', 'COMPLETED', 'REJECTED'))
            OR (from_status = 'COMPLETED' AND to_status = 'TERMINATED')))),
    ADD CONSTRAINT ck_cint_document_target CHECK (document_ref IS NULL OR to_status IN ('COMPLETED', 'TERMINATED')),
    ADD CONSTRAINT ck_cint_reason_required CHECK (reason IS NOT NULL OR from_status IS NULL
        OR NOT ((from_status = 'DOCUMENT_RECEIVED' AND to_status = 'CONSULTING') OR to_status IN ('REJECTED', 'TERMINATED'))),
    ALGORITHM = COPY;
