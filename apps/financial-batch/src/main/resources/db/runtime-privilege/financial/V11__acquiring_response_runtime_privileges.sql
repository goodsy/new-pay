-- ACQUIRING-001 배치2 2-F runtime 권한(D-FFR-002 — 새 표·새 상태 전이마다 최소 권한 migration과 권한 시험). V3·V6·V8과 같은 procedure·규칙. DELETE 없음.
-- 명세: docs/repository/ACQUIRING-001-ACQUIRING.md §11.

-- acquisition_file: 수신·결과 축 전이(JpaAcquisitionFileRepository.update — 변경 감지 + @Version)가 바꾸는 두 열을 더한다(V8의 전송 축 다섯 열은 그대로 — 열 helper는
-- 더하기만 한다). 식별·봉인·합계·hash 열은 계속 1143. 표 단위 helper는 다시 부르지 않는다(여집합 REVOKE UPDATE가 열 권한을 회수한다 — 01-owned-databases.sh 주의 문구).
CALL ${flyway:database}_privilege.grant_runtime_column_update('acquisition_file', 'receipt_state, result_state');

-- 응답 처리 기록·레코드 결과·"취소 매입 불요"(append-only): 응답 TX·결과 마감 TX·봉인 TX의 persist + flush만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_response', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_record_result', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_cancel_not_required', 'SELECT, INSERT');
