-- ACQUIRING-001 배치2 2-D runtime 권한(D-FFR-002 — 새 표·새 상태 전이마다 최소 권한 migration과 권한 시험). V3·V6와 같은 procedure·규칙. DELETE 없음.
-- 명세: docs/repository/ACQUIRING-001-ACQUIRING.md §11.

-- acquisition_file: 전송 축 전이(JpaAcquisitionFileRepository.update — 변경 감지 + @Version)가 바꾸는 열만 연다. 식별·봉인·합계·hash 열과 아직 전이가 없는
-- 수신·결과 축 열(receipt_state·result_state — 2-F가 연다)은 1143. 표 단위 권한(SELECT, INSERT)은 V6 그대로다 — set_runtime_table_privileges를 다시 부르면
-- 여집합 REVOKE UPDATE가 열 권한을 회수하므로 부르지 않는다(01-owned-databases.sh 주의 문구).
CALL ${flyway:database}_privilege.grant_runtime_column_update('acquisition_file', 'transmission_state, transmission_lease_until, transmitted_at, updated_at, version');

-- 전송 시도·결과(append-only): 점유 TX·결과 TX의 persist + flush만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_transmission_attempt', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_transmission_outcome', 'SELECT, INSERT');
