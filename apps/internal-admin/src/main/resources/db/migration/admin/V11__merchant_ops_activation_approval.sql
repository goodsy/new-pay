-- MERCHANT-OPS-002 B3(§9.16·§9.22 — C-35·C-42): 가맹점 개통 2인 승인 종류 7개를 B3-1에서 한 번에 넣는다(V9 선례 — 앱은 각 배치 전까지 그 종류를 만들지 않는다. B3-1 앱은
-- MERCHANT_PAYMENT_METHOD_ACTIVATION·MERCHANT_SITE_REVIEW_APPROVAL만 만든다). 표·열·권한 변경 없음 → runtime 권한 V12는 비운다(V8 선례).
-- 기존 종류·대상·상태·미해결 키 의미는 바꾸지 않는다: B2 두 종류는 'MERCHANT:<merchantId>' 그대로(C-20), Payment·원 파일 키 그대로.
-- MySQL 8.4: 생성 열 식 변경은 표 재작성(개발 볼륨 작음 — V5·V7·V9와 같은 주의). 시험: AdminSchemaSpec(V11 — CHECK 3819·그룹 미해결 1062·다른 그룹 공존·B2 키 불변).

-- 1) 종류·대상·소유 서비스 상태 CHECK: 가맹점 대상(target_kind MERCHANT)은 가맹점 9종류만, 가맹점 9종류는 가맹점 대상만.
-- review n-1(nit-3과 같은 이유): DROP과 ADD를 한 ALTER 문으로 — CHECK가 없는 중간 상태를 만들지 않는다.
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_target,
    DROP CHECK ck_admin_approval_request_owner_states,
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION',
        'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
        'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
        'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT'
            AND kind NOT IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION')
            AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)
        OR (target_kind = 'ACQUISITION_FILE' AND kind = 'ACQ_REACQUISITION' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c'))
        OR (target_kind = 'MERCHANT'
            AND kind IN ('MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION')
            AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c'))),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN')
        OR kind IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
            'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
            'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION'));

-- 2) 미해결 키(C-35 — 대상 그룹별 가맹점당 하나): B2 두 종류 'MERCHANT:<merchantId>' 그대로, B3는 'MERCHANT:<그룹>:<merchantId>'.
--    그룹: PM(수단 개통 승인·재개), SITE_REVIEW, CURRENCY, ROUTE(Route 승인·재개), SITE_CONFIRM. 최장 'MERCHANT:' 9 + 'SITE_REVIEW' 11 + ':' 1 + 30 = 51 ≤ 80.
--    MASTER 직렬화(merchant 행 잠금)는 키와 무관하게 정확성을 지킨다 — 키는 같은 대상 그룹의 중복 대기 요청만 막는다.
ALTER TABLE admin_approval_request
    MODIFY COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN
            CASE target_kind
                WHEN 'PAYMENT' THEN CONCAT('PAYMENT:', target_ref)
                WHEN 'ACQUISITION_FILE' THEN CONCAT('ACQFILE:', target_ref)
                WHEN 'MERCHANT' THEN
                    CASE kind
                        WHEN 'MERCHANT_PAYMENT_METHOD_ACTIVATION' THEN CONCAT('MERCHANT:PM:', target_ref)
                        WHEN 'MERCHANT_PAYMENT_METHOD_RESUME' THEN CONCAT('MERCHANT:PM:', target_ref)
                        WHEN 'MERCHANT_SITE_REVIEW_APPROVAL' THEN CONCAT('MERCHANT:SITE_REVIEW:', target_ref)
                        WHEN 'MERCHANT_CURRENCY_ENABLEMENT' THEN CONCAT('MERCHANT:CURRENCY:', target_ref)
                        WHEN 'MERCHANT_CARD_ROUTE_ACTIVATION' THEN CONCAT('MERCHANT:ROUTE:', target_ref)
                        WHEN 'MERCHANT_CARD_ROUTE_RESUME' THEN CONCAT('MERCHANT:ROUTE:', target_ref)
                        WHEN 'MERCHANT_SITE_ACTIVATION_CONFIRMATION' THEN CONCAT('MERCHANT:SITE_CONFIRM:', target_ref)
                        ELSE CONCAT('MERCHANT:', target_ref)
                    END
            END
        END) STORED;

-- 3) 가맹점 승인 명령 표 command_kind: B2 2값 + B3 7값(앱은 B3-1 두 값만 쓴다).
ALTER TABLE admin_merchant_ops_command
    DROP CHECK ck_admin_merchant_ops_command_kind,
    ADD CONSTRAINT ck_admin_merchant_ops_command_kind CHECK (command_kind IN (
        'CONTRACT_APPROVAL', 'FIRST_MID_ISSUANCE',
        'PAYMENT_METHOD_ACTIVATION', 'SITE_REVIEW_APPROVAL', 'CURRENCY_ENABLEMENT', 'CARD_ROUTE_ACTIVATION', 'PAYMENT_METHOD_RESUME', 'CARD_ROUTE_RESUME',
        'SITE_ACTIVATION_CONFIRMATION'));
