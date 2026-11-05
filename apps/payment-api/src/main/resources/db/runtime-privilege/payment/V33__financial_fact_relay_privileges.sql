-- FIN-FACT-RELAY-001 V33: Relay 전달 상태·ACK 표의 runtime 권한(PAYMENT-DBACCOUNT-001 규칙 — 쓰기 권한은 main 코드의 실제 쓰기 경로에서 도출).
-- outbox_event 권한은 이 파일에서 바꾸지 않는다(publish_status·published_at 열 단위 UPDATE는 V34 — D-FFR-001).

-- financial_fact_relay: INSERT = JpaFinancialFactRelayStore.discover(INSERT … SELECT … ON DUPLICATE KEY UPDATE version = financial_fact_relay.version — INSERT·UPDATE 둘 다 필요),
-- UPDATE = claim(SELECT … FOR UPDATE SKIP LOCKED 뒤 claim 표시)·releaseClaims·markDelivered/markRetry/markQuarantined(잠가 읽은 Entity 변경 감지 + @Version). DELETE 경로 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('financial_fact_relay', 'SELECT, INSERT, UPDATE');

-- financial_fact_relay_ack(append-only ACK 감사): INSERT = markDelivered의 persist만. UPDATE/DELETE 경로 없음 → 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('financial_fact_relay_ack', 'SELECT, INSERT');
