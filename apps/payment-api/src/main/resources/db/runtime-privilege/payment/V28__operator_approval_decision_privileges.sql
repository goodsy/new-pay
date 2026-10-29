-- ADMIN-PAYMENT-OPS-001 §4.4.4: 운영자 승인 결정 기록(D-OPS-005) — INSERT 전용. runtime 계정은 SELECT·INSERT만(UPDATE·DELETE 1142, JpaOperatorApprovalDecisionStore).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('operator_approval_decision', 'SELECT, INSERT');
