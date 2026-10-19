-- Simulator runtime 표 단위 권한(AUTH-001 §5.5·AU-20 — PayCore 관례 §9.2와 같은 방식). 가상 VAN 원장이 회복 증명의 기준이므로 append-only 표에 UPDATE/DELETE 권한을
-- 주지 않는다. 권한은 초기화 스크립트(docker/simulator-mysql/init/01-simulator-database.sh)가 만든 procedure `<DB>_privilege.set_runtime_table_privileges`로만 준다
-- (받는 쪽 = 이 DB의 runtime role 고정, 허용 집합만, 지정 집합으로 교체 — 재실행 안전). `${flyway:database}`는 Flyway 기본 placeholder(접속한 DB 이름).
-- 쓰기 권한은 현재 main 코드의 실제 쓰기 경로에서 도출했다. DELETE 경로는 어느 표에도 없다.

-- authentication_transaction: INSERT = JpaAuthenticationTransactionRepository.insert(persist). UPDATE = 결과 확정(update — 변경 감지)·lockById(FOR UPDATE).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('authentication_transaction', 'SELECT, INSERT, UPDATE');

-- lookup_log(append-only): INSERT = JpaLookupLogStore.append.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('lookup_log', 'SELECT, INSERT');

-- van_ledger_entry(append-only 기관 금융 사실 원본): INSERT = JpaVanLedgerStore.insert만. UPDATE/DELETE → DB가 1142로 거부한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('van_ledger_entry', 'SELECT, INSERT');

-- van_pending_decision: INSERT = 예약, UPDATE = 확정 반영(PENDING → RESOLVED, 조건부 UPDATE). 삭제 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('van_pending_decision', 'SELECT, INSERT, UPDATE');

-- van_evidence_use(append-only): INSERT = JpaVanEvidenceUseStore.insert만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('van_evidence_use', 'SELECT, INSERT');

-- van_inquiry_log(append-only): INSERT = JpaVanInquiryLogStore.append만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('van_inquiry_log', 'SELECT, INSERT');
