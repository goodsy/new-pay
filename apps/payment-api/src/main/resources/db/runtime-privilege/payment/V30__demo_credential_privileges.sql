-- DEMO-SEED-001 §4.5(D-DEMO-002): `demo-credential` 명령이 Credential 투영 행과 1회성·감사 행을 INSERT한다. **UPDATE·DELETE는 주지 않는다**(1142 — I-24).
-- 영향(D-DEMO-002, LIVE Gate 검토 항목 — 명세 §11): 권한은 모든 배치에 생긴다. 코드 측 완화 = INSERT 빈은 명령 프로세스에만(I-23)·쓰기 Port 사용처 제한(I-22).
-- set_runtime_table_privileges는 표의 runtime 권한을 이 목록으로 맞춘다(V6의 SELECT → SELECT, INSERT).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_api_credential', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('demo_credential_issuance', 'SELECT, INSERT');
