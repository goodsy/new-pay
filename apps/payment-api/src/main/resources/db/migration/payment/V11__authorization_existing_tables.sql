-- AUTH-001 명세 §7.1 V11: 기존 표 확장(새 표는 V9). 각 ALTER는 한 표만 바꾼다.

-- incident_hold: D-AUTH-012·013의 해제 뒤 새 hold(occurrence + 1)와 hold를 연 관찰(rev 0.3 H-1).
-- 새 UNIQUE를 먼저 추가한 뒤 옛 UNIQUE를 지운다 — 옛 UNIQUE의 앞부분 (OrderKey)이 FK fk_incident_hold_order의 인덱스 역할을 하므로 순서를 바꾸면 1553.
-- 여는 관찰 열은 FK 없이 자연 키(institution_observation UNIQUE와 같은 (attempt, 출처, 기관 결과 ID, variant) — 검토 #1 H-1: 같은 키의 상충 variant는 다른 관찰)로 둔다:
-- 넷 다 NULL = 출처 열 도입 전 legacy hold·취소 사건 hold(CANCEL-001에서 연결). 승인 관찰 경로의 새 hold는 domain이 NOT NULL을 강제한다. 기존 행은 occurrence 1, 출처 NULL.
ALTER TABLE incident_hold
    ADD COLUMN occurrence INT NOT NULL DEFAULT 1,
    ADD COLUMN source_attempt_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN source_kind VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN source_result_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN source_variant INT NULL,
    ADD CONSTRAINT uk_incident_hold_order_payment_reason_occurrence
        UNIQUE (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence),
    ADD CONSTRAINT ck_incident_hold_occurrence CHECK (occurrence >= 1),
    ADD CONSTRAINT ck_incident_hold_source CHECK (
        (source_attempt_id IS NULL) = (source_kind IS NULL) AND (source_kind IS NULL) = (source_result_id IS NULL) AND (source_kind IS NULL) = (source_variant IS NULL)
        AND (source_kind IS NULL OR source_kind IN ('RESPONSE', 'INQUIRY', 'NOTIFICATION', 'RECONCILIATION'))
        AND (source_variant IS NULL OR source_variant >= 0));

-- reason CHECK 교체(검토 #1 n-3): 새 이름으로 한 ALTER 안에서 지우기·추가를 함께 해 CHECK가 없는 구간을 만들지 않는다(같은 이름 재사용은 한 문장에서 불가).
ALTER TABLE incident_hold
    DROP CHECK ck_incident_hold_reason,
    ADD CONSTRAINT ck_incident_hold_reason_v2 CHECK (reason IN ('SUCCESS_AFTER_TERMINATION', 'CONFLICTING_INSTITUTION_RESULT'));

ALTER TABLE incident_hold DROP INDEX uk_incident_hold_order_payment_reason;

-- 기존 행은 기본값을 지운 뒤에도 1로 남는다. 새 행은 애플리케이션이 occurrence를 명시한다(묵시적 기본값으로 잘못된 occurrence가 들어가지 않게).
ALTER TABLE incident_hold ALTER COLUMN occurrence DROP DEFAULT;

-- payment_termination_evidence: 근거의 출처·기록 시각·기록자(AUTH-CONTRACT-001 §5.5·§17-7, rev 0.3 m-6). 넷 다 NULL = V11 이전 legacy 행.
ALTER TABLE payment_termination_evidence
    ADD COLUMN source_kind VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN source_ref VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN recorded_at DATETIME(3) NULL,
    ADD COLUMN recorded_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD CONSTRAINT ck_payment_termination_evidence_source CHECK (
        (source_kind IS NULL) = (source_ref IS NULL) AND (source_kind IS NULL) = (recorded_at IS NULL) AND (source_kind IS NULL) = (recorded_by IS NULL)
        AND (source_kind IS NULL OR source_kind IN ('OBSERVATION', 'ATTEMPT_ABANDON', 'CHECKOUT_EXPIRY')));

-- idempotency_request: confirm(operation ConfirmPayment) 결과(AUTH-001 명세 §4.8, AUTH-CONTRACT-001 §17-7). 성공 결과는 Authorization 참조(D-AUTH-004 — 재생 = 현재 결과),
-- 거절은 Payment 참조 + 고정 분류 상세(result_detail, 예: PAYMENT_NOT_ELIGIBLE의 reason). CreatePayment 행의 규칙은 그대로다.
ALTER TABLE idempotency_request
    ADD COLUMN result_authorization_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN result_detail VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD CONSTRAINT ck_idempotency_request_operation CHECK (operation IN ('CreatePayment', 'ConfirmPayment'));

ALTER TABLE idempotency_request DROP CHECK ck_idempotency_request_result;

ALTER TABLE idempotency_request
    ADD CONSTRAINT ck_idempotency_request_result CHECK (
        (result_kind = 'PENDING' AND result_code IS NULL AND result_payment_id IS NULL AND result_authorization_id IS NULL AND result_detail IS NULL)
        OR (operation = 'CreatePayment' AND result_authorization_id IS NULL AND result_detail IS NULL AND (
            (result_kind = 'SUCCESS' AND result_code IN ('CREATED', 'EXISTING_ACTIVE_PAYMENT') AND result_payment_id IS NOT NULL)
            OR (result_kind = 'REJECTED'
                AND result_code IN ('FIXED_INTENT_CONFLICT', 'INCIDENT_HOLD', 'AUTHORIZED_NEW_ORDER_REQUIRED', 'TERMINATION_EVIDENCE_INSUFFICIENT')
                AND result_payment_id IS NOT NULL)))
        OR (operation = 'ConfirmPayment' AND (
            (result_kind = 'SUCCESS' AND result_code IN ('ACCEPTED', 'REUSED_ACTIVE_AUTHORIZATION')
                AND result_payment_id IS NOT NULL AND result_authorization_id IS NOT NULL AND result_detail IS NULL)
            OR (result_kind = 'REJECTED'
                AND result_code IN ('CONFIRM_CONTENT_CONFLICT', 'AUTHENTICATION_NOT_VERIFIED', 'PAYMENT_PLAN_NOT_ALLOWED', 'PAYMENT_NOT_ELIGIBLE',
                                    'LIMIT_EXCEEDED', 'INCIDENT_HOLD', 'AUTHORIZED_NEW_ORDER_REQUIRED', 'PAYMENT_TERMINATED')
                AND result_payment_id IS NOT NULL AND result_authorization_id IS NULL))));
