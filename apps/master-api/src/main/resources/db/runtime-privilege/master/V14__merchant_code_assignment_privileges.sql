-- MERCHANT-CODE-001 MC-1(§3.2): V13 배정 표의 runtime 표 단위 권한. INSERT = 등록 TX의 seq 1 배정(persist), SELECT = 배정 읽기(Merchant 조립·코드 → 가맹점 사전 조회).
-- append-only 확정 사실(코드 영구 점유)이라 UPDATE·DELETE 경로가 없다 — 둘 다 1142(잠금 읽기 FOR UPDATE도 UPDATE 권한이 없어 거부). merchant 표 권한은 바꾸지 않는다(V2 그대로).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_code_assignment', 'SELECT, INSERT');
