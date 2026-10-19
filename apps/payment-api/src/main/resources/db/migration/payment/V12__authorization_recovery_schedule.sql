-- AUTH-001 AU-16(§6.1): Attempt 회복 일정(domain 밖 bookkeeping — Store ③). 쓰기 경로(TX1 생성·TXd lease·TX2/회복 갱신)와 함께 들어온다(관례 §9.2, rev 0.3 구현 결정).
-- 금융 판단의 근거가 아니다(판단은 잠근 Aggregate). 주문 키는 회복이 잠금 순서(control 먼저)를 지키려고 둔다(불변 값). 행 삭제 경로 없음 — 결론나면 closed_at.
CREATE TABLE authorization_recovery_schedule (
    attempt_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment          VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    dispatch_lease_until DATETIME(3)  NULL,
    next_check_at        DATETIME(3)  NOT NULL,
    inquiry_count        INT          NOT NULL,
    escalated_at         DATETIME(3)  NULL,
    closed_at            DATETIME(3)  NULL,
    -- 검토 #3 m-4: TX1이 적은 회복 설정 버전, 이관 판단 때의 버전(이관 전 NULL) — Auditability.
    recovery_config_version   VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    escalation_config_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at           DATETIME(3)  NOT NULL,
    updated_at           DATETIME(3)  NOT NULL,
    version              BIGINT       NOT NULL,
    CONSTRAINT pk_authorization_recovery_schedule PRIMARY KEY (attempt_id),
    CONSTRAINT fk_authorization_recovery_schedule_attempt FOREIGN KEY (attempt_id) REFERENCES authorization_attempt (attempt_id),
    CONSTRAINT ck_authorization_recovery_schedule_inquiry_count CHECK (inquiry_count >= 0),
    CONSTRAINT ck_authorization_recovery_schedule_version CHECK (version >= 0),
    CONSTRAINT ck_authorization_recovery_schedule_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_authorization_recovery_schedule_closed CHECK (closed_at IS NULL OR closed_at >= created_at),
    CONSTRAINT ck_authorization_recovery_schedule_escalation CHECK ((escalated_at IS NULL) = (escalation_config_version IS NULL)),
    INDEX ix_authorization_recovery_schedule_due (closed_at, next_check_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
