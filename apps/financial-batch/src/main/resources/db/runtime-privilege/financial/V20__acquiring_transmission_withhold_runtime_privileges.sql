-- ACQ-ADMIN-001 B2 runtime 권한(D-FFR-002 최소 권한): 전송 보류 근거는 append-only — SELECT, INSERT만(UPDATE·DELETE 없음).
-- acquisition_file의 transmission_state는 V8에서 이미 UPDATE 가능(새 값은 V19 CHECK가 받는다 — 권한 변화 없음).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_transmission_withhold', 'SELECT, INSERT');
