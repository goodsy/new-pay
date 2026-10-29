-- ADMIN-PAYMENT-OPS-001 §4.4.4(D-OPS-005): 운영자 보정 취소 명령의 approvalId별 최초 확정 결정(실행 수락 STARTED 또는 업무 거절 REJECTED). INSERT 전용 — 같은 approvalId에는
-- 항상 이 결정을 돌려준다. UNIQUE(approval_id)가 경합의 권위다(서로 다른 Payment를 잠근 두 TX는 행 잠금으로 직렬화되지 않는다). payment_id는 FK 없음(PAYMENT_NOT_FOUND도 결정).
-- 사유·근거 문구는 넣지 않는다(내용 hash opd1:만). recorded_at은 DB가 INSERT 문 실행 시각으로 채운다(대사 안정 watermark 기준 — 앱 시각 아님, rev 0.3 L-a).
CREATE TABLE operator_approval_decision (
    seq                         BIGINT       NOT NULL AUTO_INCREMENT,
    approval_id                 VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decision                    VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    rejection_code              VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    cancellation_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    content_hash                CHAR(69)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_minor                BIGINT       NOT NULL,
    currency                    CHAR(3)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by                 VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_target_fingerprint VARCHAR(69)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at                 DATETIME(3)  NOT NULL,
    execute_before              DATETIME(3)  NOT NULL,
    decided_at                  DATETIME(3)  NOT NULL,
    recorded_at                 DATETIME(3)  NOT NULL DEFAULT (NOW(3)),
    origin                      VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_operator_approval_decision PRIMARY KEY (seq),
    CONSTRAINT uk_operator_approval_decision_approval UNIQUE (approval_id),
    CONSTRAINT fk_operator_approval_decision_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    CONSTRAINT ck_operator_approval_decision_kind CHECK (decision IN ('STARTED', 'REJECTED')),
    CONSTRAINT ck_operator_approval_decision_code CHECK (rejection_code IS NULL OR REGEXP_LIKE(rejection_code, '^[A-Z_]{1,48}$', 'c')),
    CONSTRAINT ck_operator_approval_decision_shape CHECK (
        (decision = 'STARTED' AND cancellation_id IS NOT NULL AND rejection_code IS NULL)
        OR (decision = 'REJECTED' AND cancellation_id IS NULL AND rejection_code IS NOT NULL)),
    CONSTRAINT ck_operator_approval_decision_hash CHECK (REGEXP_LIKE(content_hash, '^opd1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_operator_approval_decision_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_operator_approval_decision_origin CHECK (origin IN ('EXECUTION_COMMAND', 'DEADLINE_CONFIRMATION')),
    INDEX ix_operator_approval_decision_recorded (recorded_at, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
