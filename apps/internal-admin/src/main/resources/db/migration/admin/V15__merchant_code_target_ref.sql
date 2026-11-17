-- MERCHANT-CODE-001 MC-3a(§5.1·§5.6.4 — Q-MC-4·Q-MC-9, C-30·C-32·C-36, rev 0.4.8): 가맹점 대상 승인·감사 행의 대상 표기(target_ref)를 그 시점의 가맹점 코드로 쓸 수 있게 한다.
-- 기존 행 수정 0(Q-MC-9 — 새 열은 NULL, 가상 열은 저장값 없음). V14·V16은 비어 있다(admin_approval_request·admin_audit_event 모두 표 단위 SELECT, INSERT 권한 —
-- 새 열에 runtime 권한 변경이 필요 없다, 명세 §1.4). 문장마다 기대 ALGORITHM을 적어 MySQL이 더 무거운 방식으로 조용히 바꾸지 못하게 한다(rev 0.4.7 m-3 — 시험이 원문을 단언).
-- 운영 적용: (3)·(4)는 ALGORITHM=COPY라 그 동안 두 표의 DML이 막힌다(로그인·감사 기록·승인 요청 정지) — LIVE Gate 유지보수 창 필요(L-14). Demo는 작은 표라 무시 가능.

-- (1) 감사 행의 가맹점 내부 키(target_merchant_id — 가맹점과 관련된 새 감사 행은 예외 없이 mer_, C-32·C-36) + 연결 조회용 가상 키(C-30).
--     target_merchant_key = 내부 키, 없으면 target_ref가 정확히 mer_ 형식일 때 그 값(MC-3a 전 행 포함 하나의 키 — rev 0.4.7 n-1: LIKE가 아니라 REGEXP_LIKE 정확 형식).
--     VIRTUAL — 기존 행 저장값 변화 없음, 메타데이터만(INSTANT).
ALTER TABLE admin_audit_event
    ADD COLUMN target_merchant_id VARCHAR(30) NULL AFTER target_ref,
    ADD COLUMN target_merchant_key VARCHAR(30) GENERATED ALWAYS AS (
        COALESCE(target_merchant_id, CASE WHEN REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c') THEN target_ref END)) VIRTUAL AFTER target_merchant_id,
    ALGORITHM=INSTANT;

-- (2) 연결 조회 색인(감사 필터 targetMerchantId → target_merchant_key = ?, 최신순 keyset). 가상 열 색인 생성 — 읽기·쓰기 허용, 표 크기에 비례하는 시간.
ALTER TABLE admin_audit_event
    ADD INDEX ix_admin_audit_event_target_merchant (target_merchant_key, event_id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- (3) 감사 대상 CHECK(§5.6.4 ①~③): ① 내부 키 형식 ② 내부 키가 있으면 대상 표기는 같은 mer_ 또는 코드 ③ 코드 표기 행은 내부 키 필수(코드만 남은 행 금지).
--     ②가 target_ref NULL을 막지 못하는 경우는 Domain(AdminAuditEvent init: targetMerchantId != null ⇒ targetRef != null)이 막는다. 기존 행은 모두 만족(새 열 NULL,
--     코드 형식 target_ref 없음 — `_`를 포함한 기존 ID·ACQ 참조는 코드 정규식과 겹치지 않음 §3.1). CHECK 추가는 기존 행 검증을 위해 표 복사(DML 차단).
ALTER TABLE admin_audit_event
    ADD CONSTRAINT ck_admin_audit_event_target_merchant_format CHECK (
        target_merchant_id IS NULL OR REGEXP_LIKE(target_merchant_id, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    ADD CONSTRAINT ck_admin_audit_event_target_merchant_ref CHECK (
        target_merchant_id IS NULL OR target_ref = target_merchant_id OR REGEXP_LIKE(target_ref, '^[a-z][a-z0-9]{5,13}$', 'c')),
    ADD CONSTRAINT ck_admin_audit_event_code_ref_merchant CHECK (
        target_ref IS NULL OR NOT REGEXP_LIKE(target_ref, '^[a-z][a-z0-9]{5,13}$', 'c') OR target_merchant_id IS NOT NULL),
    ALGORITHM=COPY;

-- (4) 승인 행 대상 CHECK 재생성(V13:17-33 정의 그대로, 가맹점 분기 정규식만 `^(mer_<ULID>|[a-z][a-z0-9]{5,13})$`로 넓힌다 — 새 가맹점 대상 행의 target_ref = 요청 시점 코드).
--     DROP과 ADD를 한 ALTER 문으로(CHECK 없는 중간 상태 없음 — V11 review n-1, V13 선례). 코드 표기 행의 내부 키 필수는 V13 ③(target_merchant_present)이 이미 강제한다.
ALTER TABLE admin_approval_request
    DROP CHECK ck_admin_approval_request_target,
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
            AND REGEXP_LIKE(target_ref, '^(mer_[0-9A-HJKMNP-TV-Z]{26}|[a-z][a-z0-9]{5,13})$', 'c'))),
    ALGORITHM=COPY;
