-- MASTER runtime 표 단위 권한(MASTER-SNAPSHOT-001, ARCHITECTURE-CONVENTIONS §9.2 관례). V3(업무 schema)의 새 표와 새 쓰기 경로에 앱 runtime
-- 계정이 **현재 main 코드에서 실제로 쓰는** 권한만 준다. DELETE 경로는 어느 표에도 없다. 호출은 그 표의 권한을 지정 집합으로 교체한다(재실행 안전).
-- 잠금 읽기(`SELECT … FOR UPDATE`)는 SELECT와 UPDATE 권한이 필요하다(MySQL).

-- outbox_event: UPDATE 추가(AC m-7) = JpaEntitlementRelayStore.claim(`SELECT … FOR UPDATE SKIP LOCKED` + claim 표시)·markPublished·markRetry·
-- markQuarantined(publish_status·claim·attempt 컬럼). 이벤트 내용 컬럼은 Entity updatable=false(표 단위 권한은 열을 구분하지 못한다).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('outbox_event', 'SELECT, INSERT, UPDATE');

-- payment_method_activation: UPDATE 추가 = JpaPaymentMethodActivationStore.update(status·evidence_ref·status_changed_at, @Version)와 lock(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_method_activation', 'SELECT, INSERT, UPDATE');

-- currency_enablement: INSERT = insert, UPDATE = update(종료 시각·ended_*, @Version)와 lock(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('currency_enablement', 'SELECT, INSERT, UPDATE');

-- append-only 확정 사실(이력·확인·철회·심사 결정·발행·ACK): persist만 → SELECT, INSERT. UPDATE/DELETE는 1142로 거부된다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_method_activation_event', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('site_activation_confirmation', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('site_activation_confirmation_revocation', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_site_review_decision', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('entitlement_snapshot_issue', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('entitlement_snapshot_ack', 'SELECT, INSERT');

-- currency_unit: 기준정보 읽기만(값은 migration이 넣는다).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('currency_unit', 'SELECT');
