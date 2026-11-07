-- ACQ-ADMIN-001 B1 runtime 권한(D-FFR-002 최소 권한). DELETE 없음. 명세: docs/repository/ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md §6.
-- D-ACQ-011("ADMIN 입력은 ACQ-ADMIN-001, 그 전 재매입 실행 불가")에 따라 입력 경로(ADMIN 조작 전용 서명 채널 — D-ACQA-001)와 함께 승인 표 INSERT를 연다.
-- V13의 "SELECT만"을 바꾸는 것은 완화가 아니라 결정된 개방이다: 기록은 서명 채널 → FINANCIAL 재검사(D-ADMIN-007) → 결정 행과 같은 로컬 TX로만 생긴다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_ops_decision', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_reacquisition_approval', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_reacquisition_not_required', 'SELECT, INSERT');
