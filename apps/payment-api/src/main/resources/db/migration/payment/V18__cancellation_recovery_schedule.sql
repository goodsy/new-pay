-- CANCEL-001 명세 §7.1 CN-24(선택 A — AUTH-001 V12 설계 복제), rev 0.3 m-1(S2): 취소 Attempt 하나의 회복 일정(domain 밖 bookkeeping — Store ③, 판정 근거 아님).
-- TX1이 Attempt와 함께 만들고(next_check_at = PREPARED 마감), TXd가 dispatch lease를, TX2·회복이 결론(closed_at)·조회 수·이관을 적는다. 행 삭제 경로 없음.
-- 주문 키는 회복이 잠금 순서(control 먼저)를 지키려고 둔다(불변). 회복 설정 버전은 Auditability(취소 timing 설정의 필수 version).
CREATE TABLE cancellation_recovery_schedule (
    attempt_id                VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancellation_id           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment               VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    dispatch_lease_until      DATETIME(3) NULL,
    next_check_at             DATETIME(3) NOT NULL,
    inquiry_count             INT         NOT NULL,
    escalated_at              DATETIME(3) NULL,
    closed_at                 DATETIME(3) NULL,
    recovery_config_version   VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    escalation_config_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at                DATETIME(3) NOT NULL,
    updated_at                DATETIME(3) NOT NULL,
    version                   BIGINT      NOT NULL,
    CONSTRAINT pk_cancellation_recovery_schedule PRIMARY KEY (attempt_id),
    CONSTRAINT fk_cancellation_recovery_schedule_attempt FOREIGN KEY (attempt_id) REFERENCES cancel_attempt (attempt_id),
    CONSTRAINT fk_cancellation_recovery_schedule_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    CONSTRAINT ck_cancellation_recovery_schedule_inquiry_count CHECK (inquiry_count >= 0),
    CONSTRAINT ck_cancellation_recovery_schedule_version CHECK (version >= 0),
    CONSTRAINT ck_cancellation_recovery_schedule_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_cancellation_recovery_schedule_closed CHECK (closed_at IS NULL OR closed_at >= created_at),
    CONSTRAINT ck_cancellation_recovery_schedule_escalation CHECK (
        (escalated_at IS NULL AND escalation_config_version IS NULL) OR (escalated_at IS NOT NULL AND escalation_config_version IS NOT NULL)),
    INDEX ix_cancellation_recovery_schedule_due (closed_at, next_check_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
