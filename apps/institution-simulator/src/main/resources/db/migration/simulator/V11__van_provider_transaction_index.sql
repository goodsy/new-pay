-- 가상 매입사의 VAN 원장 대조 읽기 색인(ACQUIRING-001 2-E §7 — 기존 VAN 표 변경은 새 표와 다른 migration에 둔다, V3 관례). 매입 레코드는 기관 거래 참조
-- (provider_transaction_id)로 원승인·승인된 취소를 찾는다(D-ACQ-008: merchantRef = PayCore MID, 기관 측 대조는 기관 거래 참조). 색인만 — 데이터·제약 변경 없음.
ALTER TABLE van_ledger_entry ADD INDEX ix_van_ledger_entry_provider_transaction (client_id, environment, provider_transaction_id);
ALTER TABLE van_cancel_entry ADD INDEX ix_van_cancel_entry_provider_transaction (client_id, environment, provider_transaction_id);
