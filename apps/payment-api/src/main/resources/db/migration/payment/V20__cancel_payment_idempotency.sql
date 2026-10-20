-- 취소 API 멱등 결과(CANCEL-001 §4.8 — 명세 §8.1 V17 행에서 쓰기 경로가 생기는 S5로 옮김, D-S2-1). 기존 표 ALTER만(새 표·권한과 섞지 않는다 — AU-19).
-- operation CancelPayment: 성공(ACCEPTED·EXISTING_CANCELLATION)은 취소 참조(result_cancellation_id — 재생 = 그 취소의 **현재** 상태, CC-05) + Payment 참조, 거절은 Payment 참조만.
-- CreatePayment·ConfirmPayment 행의 규칙은 그대로이고 그 행에는 취소 참조가 없다. MySQL CHECK는 NULL로 평가되면 통과하므로 IS NULL / IS NOT NULL을 명시한다
-- (검토 #3 n-1: result_code IN 목록도 NULL이면 NULL이라 결과가 있는 모든 SUCCESS·REJECTED 가지에 result_code IS NOT NULL — 상속한 CreatePayment·ConfirmPayment 가지 포함).
-- 한 ALTER 안에서 DROP + ADD(CHECK 없는 구간 없음 — AU n-3). runtime 권한 변경 없음(idempotency_request는 이미 SELECT·INSERT·UPDATE).
ALTER TABLE idempotency_request
    ADD COLUMN result_cancellation_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER result_authorization_id,
    DROP CHECK ck_idempotency_request_operation,
    DROP CHECK ck_idempotency_request_result,
    ADD CONSTRAINT ck_idempotency_request_operation_v2 CHECK (operation IN ('CreatePayment', 'ConfirmPayment', 'CancelPayment')),
    ADD CONSTRAINT ck_idempotency_request_result_v2 CHECK (
        (result_kind = 'PENDING' AND result_code IS NULL AND result_payment_id IS NULL AND result_authorization_id IS NULL AND result_detail IS NULL
            AND result_cancellation_id IS NULL)
        OR (operation = 'CreatePayment' AND result_authorization_id IS NULL AND result_detail IS NULL AND result_cancellation_id IS NULL AND (
            (result_kind = 'SUCCESS' AND result_code IS NOT NULL AND result_code IN ('CREATED', 'EXISTING_ACTIVE_PAYMENT') AND result_payment_id IS NOT NULL)
            OR (result_kind = 'REJECTED' AND result_code IS NOT NULL
                AND result_code IN ('FIXED_INTENT_CONFLICT', 'INCIDENT_HOLD', 'AUTHORIZED_NEW_ORDER_REQUIRED', 'TERMINATION_EVIDENCE_INSUFFICIENT')
                AND result_payment_id IS NOT NULL)))
        OR (operation = 'ConfirmPayment' AND result_cancellation_id IS NULL AND (
            (result_kind = 'SUCCESS' AND result_code IS NOT NULL AND result_code IN ('ACCEPTED', 'REUSED_ACTIVE_AUTHORIZATION')
                AND result_payment_id IS NOT NULL AND result_authorization_id IS NOT NULL AND result_detail IS NULL)
            OR (result_kind = 'REJECTED' AND result_code IS NOT NULL
                AND result_code IN ('CONFIRM_CONTENT_CONFLICT', 'AUTHENTICATION_NOT_VERIFIED', 'PAYMENT_PLAN_NOT_ALLOWED', 'PAYMENT_NOT_ELIGIBLE',
                    'LIMIT_EXCEEDED', 'INCIDENT_HOLD', 'AUTHORIZED_NEW_ORDER_REQUIRED', 'PAYMENT_TERMINATED')
                AND result_payment_id IS NOT NULL AND result_authorization_id IS NULL)))
        OR (operation = 'CancelPayment' AND result_authorization_id IS NULL AND result_detail IS NULL AND (
            (result_kind = 'SUCCESS' AND result_code IS NOT NULL AND result_code IN ('ACCEPTED', 'EXISTING_CANCELLATION')
                AND result_payment_id IS NOT NULL AND result_cancellation_id IS NOT NULL)
            OR (result_kind = 'REJECTED' AND result_code IS NOT NULL
                AND result_code IN ('CANCEL_REQUEST_CONFLICT', 'PAYMENT_NOT_CANCELLABLE', 'ALREADY_FULLY_CANCELLED', 'CANCEL_IN_PROGRESS', 'ORDER_ON_HOLD',
                    'CANCEL_AMOUNT_EXCEEDS_BALANCE', 'CURRENCY_MISMATCH', 'PARTIAL_CANCEL_NOT_SUPPORTED')
                AND result_payment_id IS NOT NULL AND result_cancellation_id IS NULL))));
