-- PAYMENT runtime 표 단위 권한(CP-API-001, ARCHITECTURE-CONVENTIONS §9.2). 현재 main 코드의 실제 경로만:
-- merchant_api_credential: 읽기만(JpaMerchantCredentialStore.find — 비잠금). 투영 적용(쓰기)은 MASTER-CREDENTIAL-001이 그 수신 경로와 함께
-- 새 권한 migration으로 추가한다. payment 표의 새 컬럼(created_by_credential_id)은 기존 표 단위 INSERT 권한(V2)으로 쓴다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_api_credential', 'SELECT');
