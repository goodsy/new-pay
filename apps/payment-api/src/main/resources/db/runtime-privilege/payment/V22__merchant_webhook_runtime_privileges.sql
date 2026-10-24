-- WEBHOOK-001 V21 표의 runtime 권한(관례 §9.2). 삭제 경로 없음(보관·파기는 LIVE Gate 별도 정책 — §5.12).
-- merchant_webhook_fanout_guard: fan-out 행 TX의 FOR UPDATE 직렬화(잠금 읽기에 UPDATE 권한 필요 — 허용 집합 중 최소인 SELECT, INSERT, UPDATE. INSERT 경로는 없다 — 행은 V21이 넣었다).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_fanout_guard', 'SELECT, INSERT, UPDATE');
-- merchant_webhook_stream: Payment별 순번 INSERT … ON DUPLICATE KEY UPDATE(fan-out).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_stream', 'SELECT, INSERT, UPDATE');
-- merchant_webhook_event: insert-only(가맹점 이벤트는 만든 뒤 바뀌지 않는다 — 본문 바이트 고정, WH-08).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_event', 'SELECT, INSERT');
-- merchant_webhook_delivery: INSERT(fan-out)·UPDATE(delivery claim·결과 — S3).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_delivery', 'SELECT, INSERT, UPDATE');
