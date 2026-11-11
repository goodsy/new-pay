-- MERCHANT-OPS-002 B3(§9.15·§9.22, C-42 — rev 0.8.1): 승인 근거 표의 operation CHECK에 B3 2인 operation 7개를 더한다(B3-1에서 한 번에 — 앱은 각 배치 전까지 그 operation 경로를 만들지 않는다).
-- 새 표·열 없음 → runtime 권한 변경 없음, V12는 비워 둔다(V8 선례 — 이후 V12 추가 금지). 다른 열·CHECK·decided_via 규칙·불변식 n-a는 그대로다. admin_request에는 operation CHECK가 없다(V1).
-- nit-3: DROP과 ADD를 **한 ALTER 문**으로 — CHECK가 없는 중간 상태를 만들지 않는다. 1인 operation(RegisterPaymentMethodActivation·RejectSiteReview 등)은 근거 행이 없으므로 넣지 않는다.
ALTER TABLE admin_approval_evidence
    DROP CHECK ck_admin_approval_evidence_operation,
    ADD CONSTRAINT ck_admin_approval_evidence_operation CHECK (operation IN (
        'ApproveContractRevision', 'IssueFirstDefaultMerchantAccount',
        'ApprovePaymentMethodActivation', 'DecideSiteReview', 'EnableCurrency', 'ApproveCardRouteActivation',
        'ResumePaymentMethodActivation', 'ResumeCardRouteActivation', 'ConfirmSiteActivation'));
