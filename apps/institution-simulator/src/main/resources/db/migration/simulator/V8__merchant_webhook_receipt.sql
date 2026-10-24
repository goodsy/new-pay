-- 가상 가맹점 수신 원장(WEBHOOK-001 §9 — Simulator Demo 대역, 새 표만. 권한은 V9 — MySQL DDL은 TX가 아니라 섞지 않는다).
-- append-only: 수신 요청 하나당 한 행. 본문은 저장하지 않고 SHA-256만 남긴다. 검증을 통과한 행만 봉투 값·키 ID를 갖는다(REJECTED_BODY는 키 ID만, MALFORMED·STALE·SIGNATURE는 둘 다 없음).
-- eventId 중복 제거: 첫 2xx(ACCEPTED) 행만 first_acceptance_event_id = event_id를 갖고, (receiver_id, first_acceptance_event_id) UNIQUE가 동시 첫 수신을 하나로 만든다
-- (NULL은 UNIQUE 대상 아님). MySQL CHECK는 NULL로 평가되면 통과하므로 모양 CHECK는 IS NULL / IS NOT NULL을 명시한다.
CREATE TABLE merchant_webhook_receipt (
    receipt_id                 BIGINT       NOT NULL AUTO_INCREMENT,
    receiver_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outcome                    VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    event_type                 VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    event_sequence             BIGINT       NULL,
    environment                VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    key_id                     VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    body_sha256                CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    response_status            SMALLINT     NOT NULL,
    first_acceptance_event_id  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    received_at                DATETIME(3)  NOT NULL,
    CONSTRAINT pk_merchant_webhook_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT uk_merchant_webhook_receipt_first_acceptance UNIQUE (receiver_id, first_acceptance_event_id),
    CONSTRAINT ck_merchant_webhook_receipt_outcome CHECK (outcome IN (
        'ACCEPTED', 'DUPLICATE', 'SCENARIO_REFUSED', 'REJECTED_MALFORMED', 'REJECTED_STALE', 'REJECTED_SIGNATURE', 'REJECTED_BODY', 'REJECTED_ENVIRONMENT')),
    CONSTRAINT ck_merchant_webhook_receipt_status CHECK (response_status BETWEEN 200 AND 599),
    CONSTRAINT ck_merchant_webhook_receipt_first_acceptance CHECK (
        (outcome = 'ACCEPTED' AND first_acceptance_event_id IS NOT NULL AND first_acceptance_event_id = event_id)
        OR (outcome <> 'ACCEPTED' AND first_acceptance_event_id IS NULL)),
    CONSTRAINT ck_merchant_webhook_receipt_shape CHECK (
        (outcome IN ('ACCEPTED', 'DUPLICATE', 'SCENARIO_REFUSED') AND event_id IS NOT NULL AND event_type IS NOT NULL AND event_sequence IS NOT NULL
            AND environment = 'TEST' AND key_id IS NOT NULL)
        OR (outcome = 'REJECTED_ENVIRONMENT' AND event_id IS NOT NULL AND event_type IS NOT NULL AND event_sequence IS NOT NULL AND environment IS NOT NULL
            AND environment <> 'TEST' AND key_id IS NOT NULL)
        OR (outcome = 'REJECTED_BODY' AND event_id IS NULL AND event_type IS NULL AND event_sequence IS NULL AND environment IS NULL AND key_id IS NOT NULL)
        OR (outcome IN ('REJECTED_MALFORMED', 'REJECTED_STALE', 'REJECTED_SIGNATURE') AND event_id IS NULL AND event_type IS NULL AND event_sequence IS NULL
            AND environment IS NULL AND key_id IS NULL)),
    INDEX ix_merchant_webhook_receipt_event (receiver_id, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
