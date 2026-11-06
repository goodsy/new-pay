-- ACQUIRING-001 rev 1.1 runtime 권한(D-FFR-002 — 새 표마다 최소 권한). DELETE 없음. 명세: docs/repository/ACQUIRING-001-ACQUIRING.md §11.

-- acquisition_accepted_record(append-only 매입 확정 표지 — D-ACQ-014): 응답 TX의 persist + flush만, 읽기 = 세대를 넘는 ACCEPTED 확인·재매입 검사.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_accepted_record', 'SELECT, INSERT');
