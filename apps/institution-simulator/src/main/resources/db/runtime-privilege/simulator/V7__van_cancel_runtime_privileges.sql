-- 가상 VAN 취소 원장 runtime 권한(CANCEL-001 §6.5, rev 0.3 n-6 — 권한만). V4와 같은 procedure로만 준다(받는 쪽 = runtime role 고정, 허용 집합만, 재실행 안전).
-- van_cancel_entry(append-only 기관 금융 사실 원본): INSERT = JpaVanCancelLedgerStore.insert만. UPDATE/DELETE → DB가 1142로 거부한다(VanLedgerPrivilegeGuard 탐침).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('van_cancel_entry', 'SELECT, INSERT');
