-- MERCHANT-FLOW-001 FLOW-1(명세 §4A.14 — M-2): V19 계약 접수 건 표의 runtime 표 단위 권한. DELETE 없음(접수 건·이력은 지우지 않는다 — D-FLOW-001 ② 이력 1:N).

-- contract_intake: INSERT = 접수 건 생성(JpaContractIntakeRepository.insert — FLOW-2 명령), UPDATE = 상태 전이·열린 건 접수 항목 수정(@Version 변경 감지 — 현재 상태 행,
-- append-only 대상 아님 — 증거는 전이 이력·master_change_history). SELECT = 사업자 행 잠금 뒤 최신 접수 건 읽기·상세 Reader.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_intake', 'SELECT, INSERT, UPDATE');

-- contract_intake_transition: INSERT 전용(SELECT, INSERT만 — UPDATE·DELETE는 1142). Entity는 @Immutable·persist만(JpaContractIntakeTransitionStore).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_intake_transition', 'SELECT, INSERT');
