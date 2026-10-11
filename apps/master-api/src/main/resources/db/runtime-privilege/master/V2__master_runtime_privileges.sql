-- MASTER runtime 표 단위 권한(MASTER-DOMAIN-001, ARCHITECTURE-CONVENTIONS §9.2 관례). V1(업무 schema)의 각 표에 앱 runtime 계정이
-- **현재 main 코드에서 실제로 쓰는** 권한만 준다. DELETE 경로는 어느 표에도 없다.
--
-- * 권한 migration 전용 위치(db/runtime-privilege/master). jOOQ 생성기는 업무 schema 위치(db/migration/master)만 적용한다(생성기 컨테이너에는
--   아래 procedure가 없다). 버전은 업무 schema와 같은 순서 공간(V1 표 → V2 권한).
-- * 권한은 초기화 스크립트(docker/mysql/init/01-owned-databases.sh, 운영은 DBA가 같은 내용 설치)가 만든 procedure로만 준다(받는 쪽 = 이 DB의
--   runtime role 고정, migration 계정에는 GRANT OPTION 없음). 호출은 그 표의 runtime 권한을 지정 집합으로 **교체**한다(재실행 안전).
-- * 잠금 읽기(`SELECT … FOR UPDATE`)는 SELECT와 UPDATE 권한이 필요하다(MySQL). 잠가 읽는 표는 UPDATE를 갖는다.
-- * 새 쓰기 경로(상태 전이·종료·교체·Relay published 표시 등, MASTER-LIFECYCLE-001 / MASTER-SNAPSHOT-001)는 그 작업이 새 권한 migration을
--   추가한다. append-only 확정 사실 표에 UPDATE/DELETE가 필요해 보이면 정책 질문 대상이다.

-- business: INSERT = JpaBusinessStore.insert(persist). UPDATE = BusinessJpaRepository.lockById(FOR UPDATE — Merchant·계약 등록 직렬화).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business', 'SELECT, INSERT, UPDATE');

-- merchant: INSERT = JpaMerchantStore.insert. UPDATE = lockById(FOR UPDATE, Merchant 안 관계 변경의 직렬화 지점)와
-- JpaMerchantStore.update(entitlement_revision +1, @Version).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant', 'SELECT, INSERT, UPDATE');

-- merchant_site: INSERT = JpaMerchantSiteStore.insert만(잠금·갱신 경로 없음 — 종료는 MASTER-LIFECYCLE-001).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_site', 'SELECT, INSERT');

-- contract: INSERT = JpaContractStore.insertNew. UPDATE = lockById(FOR UPDATE)와 자식 행 추가 시 PESSIMISTIC_FORCE_INCREMENT(@Version).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract', 'SELECT, INSERT, UPDATE');

-- contract_revision / contract_revision_scope_site / contract_revision_approval: **append-only**(Revision 내용 불변, 승인 사실 — D-MD-004).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_revision', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_revision_scope_site', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_revision_approval', 'SELECT, INSERT');

-- contract_service_agreement: INSERT만(CSA 철회는 MASTER-LIFECYCLE-001).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_service_agreement', 'SELECT, INSERT');

-- merchant_account: INSERT = JpaMerchantAccountStore.insertFirstDefault. UPDATE = lockById(FOR UPDATE, 개통 등록 직렬화).
-- 발급 근거·소속 컬럼은 Entity updatable=false(표 단위 권한은 열을 구분하지 못한다).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_account', 'SELECT, INSERT, UPDATE');

-- default_designation: INSERT만(최초 Default 발급). 교체·해제는 Q-MD-07 미결이라 경로 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('default_designation', 'SELECT, INSERT');

-- merchant_account_site_assignment: INSERT만(최초 PRIMARY). 종료·중지·교체(UPDATE)는 MASTER-LIFECYCLE-001이 권한을 추가한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_account_site_assignment', 'SELECT, INSERT');

-- payment_method_activation: INSERT만(최소 저장, Q-MD-09).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_method_activation', 'SELECT, INSERT');

-- master_change_history: **append-only 감사**.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('master_change_history', 'SELECT, INSERT');

-- admin_request: INSERT·UPDATE = AdminRequestJpaRepository.insertPendingIfAbsent(INSERT … ON DUPLICATE KEY UPDATE).
-- UPDATE = lockByScope(FOR UPDATE)와 JpaAdminRequestStore.complete. 삭제 경로 없음(무기한 보존).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_request', 'SELECT, INSERT, UPDATE');

-- outbox_event: INSERT = JpaOutboxStore.append만. Relay(published 표시 UPDATE)는 MASTER-SNAPSHOT-001.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('outbox_event', 'SELECT, INSERT');
