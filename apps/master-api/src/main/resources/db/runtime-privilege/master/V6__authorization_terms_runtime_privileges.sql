-- MASTER runtime 표 단위 권한(MASTER-SNAPSHOT-002, ARCHITECTURE-CONVENTIONS §9.2 관례). V5(업무 schema)의 새 표에 앱 runtime 계정이 **현재 main
-- 코드에서 실제로 쓰는** 권한만 준다. DELETE 경로는 없다. 잠금 읽기(`SELECT … FOR UPDATE`)는 SELECT와 UPDATE 권한이 필요하다(MySQL).

-- transaction_limit_policy: INSERT = 등록·교체의 새 정책, UPDATE = 종료(effective_to·ended_*, @Version)와 lock(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('transaction_limit_policy', 'SELECT, INSERT, UPDATE');

-- policy_assignment: INSERT = 등록·교체, UPDATE = 종료(@Version)와 lock(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('policy_assignment', 'SELECT, INSERT, UPDATE');

-- card_route_activation: UPDATE = 상태 전이(status·evidence_ref·status_changed_at, @Version)와 lock(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('card_route_activation', 'SELECT, INSERT, UPDATE');

-- append-only(정책 버전 등록부·카드사 개통 이력): persist만 → SELECT, INSERT. UPDATE/DELETE는 1142로 거부된다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('policy_version', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('card_route_activation_event', 'SELECT, INSERT');
