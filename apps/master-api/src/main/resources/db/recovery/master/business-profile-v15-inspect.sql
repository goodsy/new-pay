-- BUSINESS-PROFILE-001 L-8 부분 실패 복구 — 점검 SQL(V15 문장별 적용 여부 판정). 되돌림 전(어디까지 적용됐나)·후(V14 상태인가)·V15 재실행 후(완료인가) 세 번 실행해 기록한다.
-- 결과 열: columns_added(새 열 수 0|5 — 문장 1), backfill_pending(백필 안 된 행 수, 열 없으면 NULL — 문장 2), business_checks(새 CHECK 수 0..5 — 문장 3),
-- registration_status_nullable(YES = 문장 3 전, NO = 뒤, NULL = 열 없음), business_indexes(새 색인 수 0..3 — 문장 4), registration_table / registration_rows(문장 5·6),
-- representative_table·category_table·address_table·contact_table(문장 7~10). V14 상태 = 0, NULL, 0, NULL, 0, 0, NULL, 0, 0, 0, 0.

SET @new_columns = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business'
    AND COLUMN_NAME IN ('business_type', 'registration_status', 'verification_status', 'updated_at', 'updated_by'));
SET @pending = NULL;
SET @s = IF(@new_columns = 5,
    'SELECT COUNT(*) INTO @pending FROM business WHERE registration_status IS NULL OR verification_status IS NULL OR updated_at IS NULL OR updated_by IS NULL',
    'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;
SET @registration_rows = NULL;
SET @s = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_registration'),
    'SELECT COUNT(*) INTO @registration_rows FROM business_registration', 'DO 0');
PREPARE st FROM @s;
EXECUTE st;
DEALLOCATE PREPARE st;

SELECT
    @new_columns AS columns_added,
    @pending AS backfill_pending,
    (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
        AND CONSTRAINT_NAME IN ('ck_business_type', 'ck_business_reg_status', 'ck_business_type_required', 'ck_business_verification', 'ck_business_updated')) AS business_checks,
    (SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business' AND COLUMN_NAME = 'registration_status') AS registration_status_nullable,
    (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business'
        AND INDEX_NAME IN ('ix_business_status_id', 'ix_business_name_id', 'ix_business_created_id')) AS business_indexes,
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_registration') AS registration_table,
    @registration_rows AS registration_rows,
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_representative') AS representative_table,
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_category') AS category_table,
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_address') AS address_table,
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_contact') AS contact_table;