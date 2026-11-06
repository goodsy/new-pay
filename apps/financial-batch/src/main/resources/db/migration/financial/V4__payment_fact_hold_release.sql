-- ACQUIRING-001 §12(D-FFR-003 — 사용자 결정 2026-11-05): ORIGINAL_UNKNOWN 보류의 자동 해제 기록. 명세: docs/repository/ACQUIRING-001-ACQUIRING.md.
-- 원승인보다 먼저 도착해 보류된 취소는 원승인이 도착하고 금액·통화·식별이 MATCH하면 RELEASED, 아니면 REVIEW_REQUIRED(운영자 검토 — 해소 입력은 후속 작업)로
-- **이 표에 append-only로** 기록한다. 보류 원본 행(payment_fact_hold)·취소 투영 행·original_known_at_receipt는 바꾸지 않는다(UPDATE/DELETE 경로 없음).
-- 보류 하나에 판정 하나(UNIQUE hold_id): 같은 원승인의 재전송(DUPLICATE)·SWEEP 재실행이 두 번째 판정을 만들지 않는다.
-- 공통 규칙(V2와 같다): 키·식별 열은 utf8mb4_0900_bin, 시각은 UTC DATETIME(3).
CREATE TABLE payment_fact_hold_release (
    id                           BIGINT       NOT NULL AUTO_INCREMENT,
    hold_id                      BIGINT       NOT NULL,
    payment_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancellation_transaction_id  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_transaction_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decision                     VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- REVIEW_REQUIRED일 때만: 불일치 이름을 정해진 순서(HoldReleaseMismatch 선언 순)로 쉼표로 이은 값. 금액·키 원문은 넣지 않는다.
    mismatch_reasons             VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    decided_by                   VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decided_at                   DATETIME(3)  NOT NULL,
    CONSTRAINT pk_payment_fact_hold_release PRIMARY KEY (id),
    CONSTRAINT uk_payment_fact_hold_release_hold UNIQUE (hold_id),
    CONSTRAINT fk_payment_fact_hold_release_hold FOREIGN KEY (hold_id) REFERENCES payment_fact_hold (id),
    CONSTRAINT fk_payment_fact_hold_release_stream FOREIGN KEY (payment_id) REFERENCES payment_fact_stream (payment_id),
    CONSTRAINT fk_payment_fact_hold_release_authorization FOREIGN KEY (authorization_transaction_id) REFERENCES payment_fact_authorization (transaction_id),
    CONSTRAINT fk_payment_fact_hold_release_cancellation FOREIGN KEY (cancellation_transaction_id) REFERENCES payment_fact_cancellation (transaction_id),
    CONSTRAINT ck_payment_fact_hold_release_decision CHECK (decision IN ('RELEASED', 'REVIEW_REQUIRED')),
    CONSTRAINT ck_payment_fact_hold_release_reasons CHECK (
        (decision = 'RELEASED' AND mismatch_reasons IS NULL)
        OR (decision = 'REVIEW_REQUIRED' AND REGEXP_LIKE(mismatch_reasons, '^[A-Z_]{1,40}(,[A-Z_]{1,40}){0,4}$', 'c'))),
    CONSTRAINT ck_payment_fact_hold_release_decided_by CHECK (decided_by IN ('INBOX', 'SWEEP')),
    INDEX ix_payment_fact_hold_release_payment (payment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
