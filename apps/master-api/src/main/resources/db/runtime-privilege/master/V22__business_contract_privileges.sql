-- MERCHANT-FLOW-001 FLOW-2a(명세 §4B.10 V22 — §4A.14 M-2): V21 사업자 계약 표 5개의 runtime 표 단위 권한. 모두 **INSERT 전용**(SELECT, INSERT만 — UPDATE·DELETE는 1142):
-- 버전 내용은 저장 뒤 바꾸지 않고(BR-BIZ-329) 버전·닫기·사건은 append-only다(BR-BIZ-317·336). Entity는 @Immutable·persist만(JpaBusinessContractRepository).
-- contract_intake는 V20 그대로(SELECT, INSERT, UPDATE — 종류·대상 열은 INSERT 때만 쓴다: Entity updatable = false, JpaContractIntakeRepositorySpec이 UPDATE 문에 없음을 시험).
-- merchant_registration_basis(FLOW-3)의 권한은 그 표를 만드는 FLOW-3 migration(V23·V24 후보)이 더한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contract', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contract_version', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contract_version_service', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contract_version_close', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contract_event', 'SELECT, INSERT');
