-- BUSINESS-PROFILE-001 L-8 부분 실패 복구 — 전제 점검(r-1 전제 점검표 1~4의 자동화 부분). V15(db/migration/master/V15__business_profile.sql)가 중간에 실패한 MASTER DB에서
-- 되돌림(business-profile-v15-revert.sql) **전에** 실행하고 결과를 기록한다. 같은 판정이 되돌림 스크립트 머리에도 들어 있어 하나라도 0이 아니면 되돌림이 실행을 거부한다.
-- 실행 계정: DBA(다른 세션을 볼 수 있는 PROCESS 권한 — 권한이 없으면 1번 점검이 자기 세션만 보고 0이 된다). 대상 DB를 기본 DB로 연결한다(DATABASE()).
-- 운영 실행은 이 개발 요청 범위 밖(LIVE Gate). 정확성은 BusinessSchemaMigrationSpec(BIZ-1-2)이 고정한다.
--
-- 점검(모두 0이어야 ok = 1):
--   flyway_state_mismatch : flyway_schema_history가 "V15 실패 기록(success = 0) 하나·V16 없음"이 아니면 1(m-2 — 완료·미적용 DB 되돌림 거부).
--   runtime_connections   : 이 DB의 runtime 계정 연결 수(MASTER web 전 프로세스·이전 버전·Demo seed 실행기 정지 확인 — 아래 @runtime_user는 환경의 runtime 계정 이름).
--   child_rows            : 하위 4표(있으면)의 행 수 합.
--   registration_mismatch : business_registration(있으면)의 행 수가 0도 business 행 수도 아닌 차이 + 선택 열 값이 있거나 국가가 KR이 아닌 행 수.
--                           (행 수 0 허용 — 문장 (5) 뒤·(6) 전 실패. INSERT … SELECT 한 문장이라 일부 행만 있는 상태는 없다 — rev 0.2.2 정정.)
--   business_value_mismatch : business 새 열(있으면)이 백필 값과 다른 행 수(registration_status ∈ {NULL, DRAFT}, verification_status ∈ {NULL, NOT_CHECKED}, business_type NULL,
--                           updated_at ∈ {NULL, created_at}, updated_by ∈ {NULL, created_by}) + 새 열이 일부만 있는 이상 상태(1).
-- 1번 점검 외(프로세스 목록·이전 버전 인스턴스 확인)는 사람이 기록한다(L-8 절차 ①).

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
SELECT @runtime_connections AS runtime_connections, @flyway_mismatch AS flyway_state_mismatch, @child_rows AS child_rows, @registration_mismatch AS registration_mismatch,
    @business_mismatch AS business_value_mismatch, IF(@violations = 0, 1, 0) AS ok;