-- CANCEL-001 명세 §8.1 V17·rev 0.3 m-2: 기존 표 확장(새 표는 V15). 각 ALTER는 한 표만 바꾸고, CHECK 교체는 같은 ALTER 안에서 지우기·추가를 함께 한다(CHECK 없는 구간 없음).
-- idempotency_request의 CancelPayment 결과 CHECK는 쓰기 경로(취소 API)가 생기는 S5 migration에서 추가한다(관례 §9.2 — 쓰지 않는 값을 미리 두지 않는다, 명세 §18 편차 기록).

-- payment_transaction(AU-23 공용 표): CANCEL 거래 열 추가, 승인 전용 열은 NULL 허용으로 바꾸고 type별 짝을 명시적 IS NULL / IS NOT NULL로 묶는다(MySQL CHECK는 NULL에서 통과 — m-2).
-- AUTHORIZATION ⇒ plan_kind·card_type·approval_number NOT NULL, 취소 열 NULL. CANCEL ⇒ plan_kind·installment_months·card_type NULL, 취소 열 NOT NULL(취소 승인번호는 기관이 주지 않을 수 있어 NULL 허용).
-- 취소 하나에 CANCEL 거래 하나(생성 열 cancel_txn_key UNIQUE). FK에는 참조 동작이 없다(CHECK 대상 열 — MySQL 제약). recorded_by: 결과 반영은 RESOLUTION(RECON이 기록하게 되면 RECONCILIATION — n-9).
ALTER TABLE payment_transaction
    MODIFY COLUMN plan_kind VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    MODIFY COLUMN card_type VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    MODIFY COLUMN approval_number VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cancellation_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER authorization_id,
    ADD COLUMN original_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER cancellation_id,
    ADD COLUMN cancel_scope VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER original_transaction_id,
    ADD COLUMN original_provider_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER provider_transaction_id,
    ADD COLUMN cancel_txn_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(type = 'CANCEL', cancellation_id, NULL)) STORED,
    ADD CONSTRAINT uk_payment_transaction_cancellation UNIQUE (cancel_txn_key),
    ADD CONSTRAINT fk_payment_transaction_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    ADD CONSTRAINT fk_payment_transaction_original FOREIGN KEY (original_transaction_id) REFERENCES payment_transaction (transaction_id),
    DROP CHECK ck_payment_transaction_type,
    ADD CONSTRAINT ck_payment_transaction_type_v2 CHECK (type IN ('AUTHORIZATION', 'CANCEL')),
    DROP CHECK ck_payment_transaction_plan,
    ADD CONSTRAINT ck_payment_transaction_plan_v2 CHECK (
        plan_kind IS NULL
        OR (plan_kind = 'LUMP_SUM' AND installment_months IS NULL)
        OR (plan_kind = 'INSTALLMENT' AND installment_months IS NOT NULL AND installment_months BETWEEN 2 AND 99)),
    DROP CHECK ck_payment_transaction_card_type,
    ADD CONSTRAINT ck_payment_transaction_card_type_v2 CHECK (card_type IS NULL OR card_type IN ('CREDIT', 'CHECK')),
    ADD CONSTRAINT ck_payment_transaction_kind CHECK (
        (type = 'AUTHORIZATION' AND plan_kind IS NOT NULL AND card_type IS NOT NULL AND approval_number IS NOT NULL
            AND cancellation_id IS NULL AND original_transaction_id IS NULL AND cancel_scope IS NULL AND original_provider_transaction_id IS NULL)
        OR (type = 'CANCEL' AND plan_kind IS NULL AND installment_months IS NULL AND card_type IS NULL
            AND cancellation_id IS NOT NULL AND original_transaction_id IS NOT NULL AND original_provider_transaction_id IS NOT NULL
            AND cancel_scope IS NOT NULL AND cancel_scope IN ('FULL', 'PARTIAL')));

-- incident_hold: 취소 결과 사건 hold(D-CANCEL-006, review m-1). 출처 열은 NULL(취소 관찰 키는 승인 관찰 출처와 섞지 않는다 — 명세 CN-15).
ALTER TABLE incident_hold
    DROP CHECK ck_incident_hold_reason_v2,
    ADD CONSTRAINT ck_incident_hold_reason_v3 CHECK (reason IN ('SUCCESS_AFTER_TERMINATION', 'CONFLICTING_INSTITUTION_RESULT', 'CANCEL_RESULT_CONFLICT'));
