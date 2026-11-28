-- BIZ-CRIT-APPROVAL-001 S2(§3.1): V26 제안·1인 근거 원문 표 runtime 권한 — INSERT 전용(SELECT, INSERT만 — UPDATE·DELETE는 1142). Entity는 @Immutable·persist만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_change_proposal', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_change_evidence', 'SELECT, INSERT');
