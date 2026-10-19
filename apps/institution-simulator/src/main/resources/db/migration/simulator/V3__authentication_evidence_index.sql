-- 가상 VAN의 인증 증거 재검증(AUTH-001 §5.3-3)이 증거 참조로 인증 거래를 찾는다. 증거 참조는 128bit 난수라 UNIQUE를 가정하지 않고 색인만 둔다(둘 이상이면 VAN이 무효로 본다).
CREATE INDEX ix_authentication_transaction_evidence_ref ON authentication_transaction (evidence_ref);
