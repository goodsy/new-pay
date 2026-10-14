-- PAYMENT runtime 표 단위 권한(MASTER-SNAPSHOT-001, ARCHITECTURE-CONVENTIONS §9). V3 표에 현재 main 코드가 실제로 쓰는 권한만 준다. DELETE 없음.
-- 잠금 읽기(FOR UPDATE / FOR SHARE)는 SELECT와 UPDATE 권한이 필요하다(MySQL 8.0.22+ locking read 규칙).

-- inbox_event: append-only(INSERT = JpaEntitlementProjectionStore.insertInbox, 조회 = findInbox — 비잠금). UPDATE/DELETE·잠금 읽기는 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('inbox_event', 'SELECT, INSERT');

-- entitlement_head: INSERT·UPDATE = IODKU(insertEmptyIfAbsent), UPDATE = lockHeadForUpdate(FOR UPDATE)·lockHeadForShare(FOR SHARE)·updateHead(@Version).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('entitlement_head', 'SELECT, INSERT, UPDATE');

-- entitlement_snapshot / entitlement_mid_owner: append-only(persist만).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('entitlement_snapshot', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('entitlement_mid_owner', 'SELECT, INSERT');
