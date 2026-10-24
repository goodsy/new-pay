-- WEBHOOK-001 V25 권한: 전송 시도 기록은 append-only(SELECT, INSERT — UPDATE/DELETE 1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_attempt', 'SELECT, INSERT');
