-- WEBHOOK-001 명세 §5.0(rev 0.2)·§5.3 V21: 가맹점 Webhook 전용 새 표만(Outbox 열 추가는 권한 판정 §21 B-1 뒤 별도 migration — MySQL DDL은 TX가 아니라 섞지 않는다).
-- 업무 규칙: D-WH-001~008. 가맹점 이벤트는 금융 사실이 아니며 금융 표를 바꾸지 않는다(D-WH-006). 시각은 UTC DATETIME(3). 카드번호·Secret·기관 원문 열 없음.
-- MySQL CHECK는 NULL이면 통과하므로 짝 조건은 IS NULL / IS NOT NULL로 명시한다.
-- 업무 표(payment·outbox_event)로 가는 FK를 두지 않는다(WH-23): InnoDB FK 검사는 부모 행에 공유 잠금을 걸어 fan-out TX가 열린 동안 업무 TX의 payment FOR UPDATE를
-- 기다리게 만든다(H-3 — 업무 TX는 fan-out을 기다리지 않는다). 대응은 fan-out이 같은 TX에서 읽은 행 값과 UNIQUE(source_event_id)가 지킨다. webhook 표끼리의 FK는 둔다.

-- fan-out 쓰기 직렬화 행(WH-10 rev 0.2): 행 TX마다 이 행을 FOR UPDATE로 잡는다. 업무 TX는 이 표를 쓰지 않는다(업무 잠금 순서와 교차 없음).
CREATE TABLE merchant_webhook_fanout_guard (
    guard_name VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_webhook_fanout_guard PRIMARY KEY (guard_name),
    CONSTRAINT ck_merchant_webhook_fanout_guard_name CHECK (guard_name = 'FANOUT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO merchant_webhook_fanout_guard (guard_name, created_at) VALUES ('FANOUT', UTC_TIMESTAMP(3));

-- Payment별 가맹점 이벤트 순번(WH-04): 마지막으로 쓴 sequence. 억제 이벤트는 번호를 쓰지 않는다.
CREATE TABLE merchant_webhook_stream (
    payment_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    last_sequence INT         NOT NULL,
    updated_at    DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_webhook_stream PRIMARY KEY (payment_id),
    CONSTRAINT ck_merchant_webhook_stream_sequence CHECK (last_sequence >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 가맹점 이벤트(insert-only): Outbox 이벤트 하나당 최대 하나(source_event_id UNIQUE — fan-out 재실행 수렴), Payment 안 sequence UNIQUE.
-- body는 보낸 바이트 그대로(MEDIUMBLOB — JSON 열은 키 순서·공백을 정규화해 서명 대상이 바뀐다, F-9·WH-08), body_sha256으로 전송 직전 무결성 확인(n-4).
-- LIVE는 endpoint 원천이 없어 항상 NO_ENDPOINT(D-WH-004 fail-closed — DB 이중 방어, WEBHOOK-ENDPOINT-001이 등록 경로와 함께 푼다).
CREATE TABLE merchant_webhook_event (
    webhook_event_id    CHAR(30)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_event_id     CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_sequence    INT         NOT NULL,
    event_type          VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version      INT         NOT NULL,
    body                MEDIUMBLOB  NOT NULL,
    body_sha256         CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    delivery_plan       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_webhook_event PRIMARY KEY (webhook_event_id),
    CONSTRAINT uk_merchant_webhook_event_source UNIQUE (source_event_id),
    CONSTRAINT uk_merchant_webhook_event_sequence UNIQUE (payment_id, payment_sequence),
    CONSTRAINT ck_merchant_webhook_event_id CHECK (REGEXP_LIKE(webhook_event_id, '^whe_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_merchant_webhook_event_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_merchant_webhook_event_sequence CHECK (payment_sequence >= 1),
    CONSTRAINT ck_merchant_webhook_event_type CHECK (
        event_type IN ('payment.authorized', 'payment.failed', 'payment.expired', 'payment.cancelled', 'payment.cancel_failed')),
    CONSTRAINT ck_merchant_webhook_event_schema CHECK (schema_version = 1),
    CONSTRAINT ck_merchant_webhook_event_sha CHECK (REGEXP_LIKE(body_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_merchant_webhook_event_plan CHECK (delivery_plan IN ('DELIVER', 'NO_ENDPOINT')),
    CONSTRAINT ck_merchant_webhook_event_live_no_endpoint CHECK (environment = 'TEST' OR delivery_plan = 'NO_ENDPOINT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 전달(Aggregate `MerchantWebhookDelivery`): 이벤트당 하나((MID,환경)당 endpoint 하나 — D-WH-004). attempt_count는 claim TX가 예약한 시도 수(m-2).
-- claim_owner·claim_until은 delivery worker bookkeeping(S3). v1은 TEST만(LIVE 전달 행은 생길 수 없다 — D-WH-004·D-WH-008 fail-closed).
CREATE TABLE merchant_webhook_delivery (
    delivery_id         CHAR(30)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    webhook_event_id    CHAR(30)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status              VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_count       INT         NOT NULL,
    next_attempt_at     DATETIME(3) NULL,
    first_attempt_at    DATETIME(3) NULL,
    last_failure_code   VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    delivered_at        DATETIME(3) NULL,
    gave_up_at          DATETIME(3) NULL,
    claim_owner         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    claim_until         DATETIME(3) NULL,
    version             BIGINT      NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_webhook_delivery PRIMARY KEY (delivery_id),
    CONSTRAINT uk_merchant_webhook_delivery_event UNIQUE (webhook_event_id),
    CONSTRAINT fk_merchant_webhook_delivery_event FOREIGN KEY (webhook_event_id) REFERENCES merchant_webhook_event (webhook_event_id),
    CONSTRAINT ck_merchant_webhook_delivery_id CHECK (REGEXP_LIKE(delivery_id, '^whd_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_merchant_webhook_delivery_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_merchant_webhook_delivery_attempts CHECK (attempt_count >= 0 AND ((attempt_count = 0) = (first_attempt_at IS NULL))),
    CONSTRAINT ck_merchant_webhook_delivery_failure_code CHECK (last_failure_code IS NULL OR REGEXP_LIKE(last_failure_code, '^[A-Z0-9_]{1,48}$', 'c')),
    CONSTRAINT ck_merchant_webhook_delivery_state CHECK (
        (status = 'PENDING' AND next_attempt_at IS NOT NULL AND delivered_at IS NULL AND gave_up_at IS NULL)
        OR (status = 'DELIVERED' AND attempt_count >= 1 AND delivered_at IS NOT NULL AND next_attempt_at IS NULL AND gave_up_at IS NULL)
        OR (status = 'GAVE_UP' AND attempt_count >= 1 AND gave_up_at IS NOT NULL AND last_failure_code IS NOT NULL AND next_attempt_at IS NULL AND delivered_at IS NULL)),
    CONSTRAINT ck_merchant_webhook_delivery_claim CHECK ((claim_owner IS NULL) = (claim_until IS NULL)),
    INDEX ix_merchant_webhook_delivery_due (status, next_attempt_at, delivery_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
