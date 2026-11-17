-- BUSINESS-PROFILE-001 BIZ-1(명세 §5.4): V15 사업자 표의 runtime 표 단위 권한. DELETE 없음(물리 삭제 경로 없음 — 종료는 effective_to·ended_by 기록).
-- business 권한은 바꾸지 않는다(V2 SELECT, INSERT, UPDATE — 새 열 갱신 포함: JpaBusinessRepository.update의 변경 감지 UPDATE·PESSIMISTIC_FORCE_INCREMENT).
-- MASTER DB에는 열 단위 UPDATE procedure가 없다(초기화 스크립트는 payment·financial·admin만 설치) → 하위 4표의 UPDATE는 표 단위이고, 내용 열은
-- Entity `updatable = false`(V2 주석의 기존 관례)와 시험(BIZ-1-6 — 종료 열 외 UPDATE 0)으로 막는다(C-11, L-10).

-- business_registration: INSERT = JpaBusinessRepository.insert(등록 1:1 행 persist — 새 사업자·옛 RegisterBusiness 과도기 경로 모두).
-- UPDATE = JpaBusinessRepository.update(등록정보 제자리 수정 — 법인등록번호·개업일·영문 상호·사업체 전화·이메일, 전후 값은 master_change_history).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_registration', 'SELECT, INSERT, UPDATE');

-- business_representative / business_category / business_address / business_contact: INSERT = JpaBusinessRepository.insert·update(새 현재 행 persist).
-- UPDATE = JpaBusinessRepository.update(종료 기록 effective_to·ended_by만 — 내용 열 updatable = false). SELECT = lockWithProfile(business FOR UPDATE 뒤 일반 SELECT).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_representative', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_category', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_address', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contact', 'SELECT, INSERT, UPDATE');
