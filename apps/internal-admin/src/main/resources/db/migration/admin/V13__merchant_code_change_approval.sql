-- MERCHANT-CODE-001 MC-1b(§5.1·§5.2·§5.5 — D-MCODE-002 Q-MC-7, C-15·C-25): 가맹점 코드 1회 변경 2인 승인 종류 MERCHANT_CODE_CHANGE + 가맹점 내부 키 열 target_merchant_id
-- + 미해결 키를 내부 키(mer_) 기준으로 재작성. V12는 비어 있다(MERCHANT-OPS-002 C-42 — 이후 V12 추가 금지, outOfOrder 없음). admin_approval_request는 표 단위 INSERT 권한이라
-- 새 열에 runtime 권한 변경이 필요 없다 → runtime 권한 V14는 비운다(명세 §1.4). 기존 행 수정 0(Q-MC-9): 기존 가맹점 행은 target_ref = mer_ 그대로라 같은 미해결 키를 만든다.
-- MySQL 8.4: 생성 열 식 변경은 표 재작성(개발 볼륨 작음 — V5·V7·V9·V11과 같은 주의). 시험: AdminSchemaSpec(V13 — CHECK ①~④ 각 3819, 종류·명령 종류 CHECK, 기존 키 불변).

-- 1) 종류·대상·소유 서비스 상태 CHECK에 MERCHANT_CODE_CHANGE(가맹점 대상)를 더한다. DROP과 ADD를 한 ALTER 문으로(CHECK 없는 중간 상태 없음 — V11 review n-1).
--    가맹점 대상 target_ref는 MC-1b 동안 `^mer_<ULID>$`만(C-21 — 코드 표기는 MC-3 V15).
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_kind,
    DROP CHECK ck_admin_approval_request_target,
    DROP CHECK ck_admin_approval_request_owner_states,
    ADD CONSTRAINT ck_admin_approval_request_kind CHECK (kind IN (
        'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION',
        'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
        'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
        'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE')),
    ADD CONSTRAINT ck_admin_approval_request_target CHECK (
        (target_kind = 'ADMIN_ACCOUNT'
            AND kind NOT IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE')
            AND target_actor_id IS NOT NULL AND target_ref IS NULL)
        OR (target_kind = 'PAYMENT' AND kind = 'OPERATOR_CORRECTION_CANCEL' AND target_actor_id IS NULL AND target_ref IS NOT NULL)
        OR (target_kind = 'ACQUISITION_FILE' AND kind = 'ACQ_REACQUISITION' AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$', 'c'))
        OR (target_kind = 'MERCHANT'
            AND kind IN ('MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
                'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
                'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE')
            AND target_actor_id IS NULL AND target_ref IS NOT NULL
            AND target_security_version = 0
            AND REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c'))),
    ADD CONSTRAINT ck_admin_approval_request_owner_states CHECK (
        status NOT IN ('EXECUTING', 'EXECUTION_UNKNOWN')
        OR kind IN ('OPERATOR_CORRECTION_CANCEL', 'ACQ_REACQUISITION', 'MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE',
            'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL', 'MERCHANT_CURRENCY_ENABLEMENT', 'MERCHANT_CARD_ROUTE_ACTIVATION',
            'MERCHANT_PAYMENT_METHOD_RESUME', 'MERCHANT_CARD_ROUTE_RESUME', 'MERCHANT_SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE'));

-- 2) 가맹점 내부 키 열(§5.1 — MC-1b부터 가맹점 대상 새 행은 mer_ 필수, 앱의 요청 생성 팩토리 한 곳이 채운다). CHECK ①~④(리뷰 m-2 — C-25):
--    ① 형식 ② 가맹점 대상이 아니면 NULL ③ 가맹점 대상이면 값 있음 또는 target_ref가 mer_(기존 행 허용 — 새 행 필수는 앱·시험) ④ target_ref가 mer_ 형식이면 같은 값(두 값이 다른 가맹점을 가리킬 수 없음).
ALTER TABLE admin_approval_request
    ADD COLUMN target_merchant_id VARCHAR(30) NULL AFTER target_ref,
    ADD CONSTRAINT ck_admin_approval_request_target_merchant_format CHECK (
        target_merchant_id IS NULL OR REGEXP_LIKE(target_merchant_id, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    ADD CONSTRAINT ck_admin_approval_request_target_merchant_kind CHECK (target_kind = 'MERCHANT' OR target_merchant_id IS NULL),
    ADD CONSTRAINT ck_admin_approval_request_target_merchant_present CHECK (
        target_kind <> 'MERCHANT' OR target_merchant_id IS NOT NULL OR REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    ADD CONSTRAINT ck_admin_approval_request_target_merchant_same CHECK (
        target_ref IS NULL OR NOT REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c') OR target_merchant_id IS NULL OR target_merchant_id = target_ref);

-- 3) 미해결 키(§5.2 — C-15): 가맹점 대상은 COALESCE(target_merchant_id, target_ref) = 내부 키 기준(코드가 바뀌어도 같은 가맹점·같은 그룹은 키 하나). MERCHANT_CODE_CHANGE는
--    'MERCHANT:<mer_>' 그룹을 계약 승인·첫 MID와 공유한다(ELSE 분기 — 변경 요청과 계약 승인 요청은 동시에 미해결일 수 없다). 다른 그룹·종류의 키 의미는 V11 그대로.
ALTER TABLE admin_approval_request
    MODIFY COLUMN unresolved_key VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN') THEN
            CASE target_kind
                WHEN 'PAYMENT' THEN CONCAT('PAYMENT:', target_ref)
                WHEN 'ACQUISITION_FILE' THEN CONCAT('ACQFILE:', target_ref)
                WHEN 'MERCHANT' THEN
                    CASE kind
                        WHEN 'MERCHANT_PAYMENT_METHOD_ACTIVATION' THEN CONCAT('MERCHANT:PM:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_PAYMENT_METHOD_RESUME' THEN CONCAT('MERCHANT:PM:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_SITE_REVIEW_APPROVAL' THEN CONCAT('MERCHANT:SITE_REVIEW:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CURRENCY_ENABLEMENT' THEN CONCAT('MERCHANT:CURRENCY:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CARD_ROUTE_ACTIVATION' THEN CONCAT('MERCHANT:ROUTE:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_CARD_ROUTE_RESUME' THEN CONCAT('MERCHANT:ROUTE:', COALESCE(target_merchant_id, target_ref))
                        WHEN 'MERCHANT_SITE_ACTIVATION_CONFIRMATION' THEN CONCAT('MERCHANT:SITE_CONFIRM:', COALESCE(target_merchant_id, target_ref))
                        ELSE CONCAT('MERCHANT:', COALESCE(target_merchant_id, target_ref))
                    END
            END
        END) STORED;

-- 4) 가맹점 승인 명령 표 command_kind에 MERCHANT_CODE_CHANGE.
ALTER TABLE admin_merchant_ops_command
    DROP CHECK ck_admin_merchant_ops_command_kind,
    ADD CONSTRAINT ck_admin_merchant_ops_command_kind CHECK (command_kind IN (
        'CONTRACT_APPROVAL', 'FIRST_MID_ISSUANCE',
        'PAYMENT_METHOD_ACTIVATION', 'SITE_REVIEW_APPROVAL', 'CURRENCY_ENABLEMENT', 'CARD_ROUTE_ACTIVATION', 'PAYMENT_METHOD_RESUME', 'CARD_ROUTE_RESUME',
        'SITE_ACTIVATION_CONFIRMATION', 'MERCHANT_CODE_CHANGE'));
