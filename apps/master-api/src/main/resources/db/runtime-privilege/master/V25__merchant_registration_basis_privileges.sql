-- MERCHANT-FLOW-001 FLOW-3(BR-BIZ-337): V24 가맹점 등록 근거 표 runtime 권한 — INSERT 전용(SELECT, INSERT만 — UPDATE·DELETE는 1142). Entity는 @Immutable·persist만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_registration_basis', 'SELECT, INSERT');
