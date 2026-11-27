-- MERCHANT-FLOW-001 FLOW-2c(명세 rev 0.4d §4B.10 4·§5.5 — 사용자 답 D-GOV-022, Q-FLOW-38): 정지·정지 해제 사건도 근거 참조 **선택**(해지와 같은 규칙 — BR-BIZ-336·339 해석).
-- V21의 `ck_bce_evidence_kind`(근거 = 해지만)를 없앤다. 근거 형식 CHECK(`ck_bce_evidence_format`)·사유 필수(NOT NULL·`ck_bce_reason_length`)는 그대로, 근거 NULL 허용도 그대로.
-- forward-only: 되돌림 SQL은 두지 않는다 — 한 문장(CHECK 삭제)이라 부분 실패 상태가 없고, 정지·정지 해제 근거 행이 생긴 뒤 CHECK를 되살리면 그 행이 위반이라 되돌릴 수 없다
-- (되돌림이 필요하면 근거 행 처리를 사람이 먼저 결정 — Coordinator). 한 번 적용된 뒤에는 이 파일을 바꾸지 않는다(Flyway checksum).
-- 표 행은 바뀌지 않는다(제약 완화 — 기존 행은 모두 새 규칙을 만족). 새 표·열 없음 → runtime 권한 변경 없음. 마지막 번호 V22 다음이라 V23(FLOW-3 근거 기록 표는 V24 이후).
ALTER TABLE business_contract_event
    DROP CHECK ck_bce_evidence_kind,
    ALGORITHM = COPY;
