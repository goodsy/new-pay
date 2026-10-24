-- 가상 가맹점 수신 원장 runtime 권한(WEBHOOK-001 §9 — 권한만). V4·V7과 같은 procedure로만 준다(받는 쪽 = runtime role 고정, 허용 집합만, 재실행 안전).
-- merchant_webhook_receipt(append-only): INSERT = JpaMerchantWebhookReceiptStore.append만. UPDATE/DELETE → DB가 1142로 거부한다(MerchantReceiptPrivilegeGuard 탐침).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_webhook_receipt', 'SELECT, INSERT');
