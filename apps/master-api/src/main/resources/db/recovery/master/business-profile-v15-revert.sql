-- BUSINESS-PROFILE-001 L-8 부분 실패 복구 — 되돌림 SQL(절차 ③). V15가 중간에 실패한 MASTER DB를 V14 상태로 되돌린다(적용된 것만 역순 제거):
-- 새 표 DROP(자식 → business_registration) → 색인 DROP → CHECK DROP → 새 열 DROP. business의 기존 열·UNIQUE·FK·행은 건드리지 않는다.
-- **머리의 전제 점검(business-profile-v15-precheck.sql과 같은 판정)이 하나라도 0이 아니면 첫 DROP 전에 오류로 멈춘다**(없는 표를 PREPARE —
-- 오류 1146 `v15_revert_refused_precheck_failed`). 오류에서 멈추는 실행기로 실행한다(mysql 클라이언트 기본 동작 — `--force` 금지).
-- 실행 계정: DBA(PROCESS 권한·대상 DB DDL). 뒤이어 점검 SQL로 V14 상태 확인 → `flyway repair` → `migrate`(V15 재실행) → 점검 SQL로 완료 확인 → 기동(L-8 ④~⑥).
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 BusinessSchemaMigrationSpec(BIZ-1-2)이 고정한다.
-- 유지 주의(리뷰 n-4): 아래 판정 블록(SET @runtime_user … SET @violations)은 business-profile-v15-precheck.sql과 business-profile-v15-revert.sql에 **같은 내용으로 두 번** 있다
-- (되돌림이 별도 점검 없이 단독 실행돼도 스스로 거부하게). 한쪽을 고치면 다른 쪽도 같이 고친다 — BusinessSchemaMigrationSpec이 두 스크립트를 함께 시험한다.
-- @runtime_user는 환경마다 runtime 계정 이름으로 바꿔 넣는다(이 저장소 초기화 스크립트 기본값 = paycore_master — 운영은 DBA가 실제 계정 이름으로 치환).
SET @runtime_user = 'paycore_master';
SET @runtime_connections = (SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = @runtime_user);

-- 전제 점검 5(리뷰 m-2): 되돌림은 **V15가 실패로 기록된 DB에서만** — flyway_schema_history에 version 15 행이 정확히 하나이고 success = 0, version 16 행 없음.
-- 완료된 V15·V16 DB, V15가 아직 없는 DB(V14), 기록이 다른 DB는 모두 거부한다(되돌림 대상 아님).
SET @flyway_mismatch = IF(
    (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15') = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15' AND success = 0) = 1
    AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16') = 0, 0, 1);

SET @child_rows = 0;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_representative'), 'SELECT COUNT(*) INTO @c FROM business_representative', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @child_rows = @child_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_category'), 'SELECT COUNT(*) INTO @c FROM business_category', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @child_rows = @child_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_address'), 'SELECT COUNT(*) INTO @c FROM business_address', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @child_rows = @child_rows + @c;
SET @c = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contact'), 'SELECT COUNT(*) INTO @c FROM business_contact', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @child_rows = @child_rows + @c;

SET @registration_mismatch = 0;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_registration'),
    'SELECT IF((SELECT COUNT(*) FROM business_registration) IN (0, (SELECT COUNT(*) FROM business)), 0, 1) + (SELECT COUNT(*) FROM business_registration WHERE corporate_registration_number IS NOT NULL OR opening_date IS NOT NULL OR english_name IS NOT NULL OR phone_number IS NOT NULL OR email IS NOT NULL OR country_code <> ''KR'') INTO @registration_mismatch',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

SET @new_columns = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business'
    AND COLUMN_NAME IN ('business_type', 'registration_status', 'verification_status', 'updated_at', 'updated_by'));
SET @business_mismatch = IF(@new_columns IN (0, 5), 0, 1);
SET @s = IF(@new_columns = 5,
    'SELECT @business_mismatch + COUNT(*) INTO @business_mismatch FROM business WHERE business_type IS NOT NULL OR NOT (registration_status IS NULL OR registration_status = ''DRAFT'') OR NOT (verification_status IS NULL OR verification_status = ''NOT_CHECKED'') OR NOT (updated_at IS NULL OR updated_at = created_at) OR NOT (updated_by IS NULL OR updated_by = created_by)',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

SET @violations = @runtime_connections + @flyway_mismatch + @child_rows + @registration_mismatch + @business_mismatch;

-- 전제 점검 관문: 위반이 있으면 여기서 오류(되돌림 거부 — DROP 0).
SET @s = IF(@violations = 0, 'DO 0', 'SELECT 1 FROM v15_revert_refused_precheck_failed');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 새 표(자식 → 등록정보).
DROP TABLE IF EXISTS business_contact;
DROP TABLE IF EXISTS business_address;
DROP TABLE IF EXISTS business_category;
DROP TABLE IF EXISTS business_representative;
DROP TABLE IF EXISTS business_registration;

-- 색인(문장 4).
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business' AND INDEX_NAME = 'ix_business_status_id'), 'ALTER TABLE business DROP INDEX ix_business_status_id', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business' AND INDEX_NAME = 'ix_business_name_id'), 'ALTER TABLE business DROP INDEX ix_business_name_id', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business' AND INDEX_NAME = 'ix_business_created_id'), 'ALTER TABLE business DROP INDEX ix_business_created_id', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- CHECK(문장 3 — 열보다 먼저: CHECK가 참조하는 열은 DROP할 수 없다).
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_business_type'), 'ALTER TABLE business DROP CHECK ck_business_type', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_business_reg_status'), 'ALTER TABLE business DROP CHECK ck_business_reg_status', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_business_type_required'), 'ALTER TABLE business DROP CHECK ck_business_type_required', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_business_verification'), 'ALTER TABLE business DROP CHECK ck_business_verification', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_business_updated'), 'ALTER TABLE business DROP CHECK ck_business_updated', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 새 열(문장 1 — 한 ALTER라 5개가 함께 있거나 함께 없다). 문장 2의 백필 값도 열과 함께 사라진다.
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business' AND COLUMN_NAME = 'registration_status'), 'ALTER TABLE business DROP COLUMN business_type, DROP COLUMN registration_status, DROP COLUMN verification_status, DROP COLUMN updated_at, DROP COLUMN updated_by', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
