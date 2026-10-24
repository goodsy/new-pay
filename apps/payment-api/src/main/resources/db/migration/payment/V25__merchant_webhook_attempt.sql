-- WEBHOOK-001 §5.0 delivery·m-2 V25: 전송 시도 기록(append-only 증거). claim TX가 예약한 번호로 결과 TX가 INSERT한다 — lease 인수 때 두 worker의 시도가 서로 다른 번호로 남는다.
-- URL·본문·서명 값·응답 본문 열이 없다(endpointRef·키 ID·고정 결과 코드·HTTP 상태·일정 버전·시각만). webhook 표끼리의 FK만 둔다(WH-23).
CREATE TABLE merchant_webhook_attempt (
    attempt_id       BIGINT       NOT NULL AUTO_INCREMENT,
    delivery_id      CHAR(30)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_no       INT          NOT NULL,
    worker           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    endpoint_ref     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    signing_key_ids  VARCHAR(96)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    outcome          VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    http_status      SMALLINT     NULL,
    schedule_version VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    started_at       DATETIME(3)  NOT NULL,
    finished_at      DATETIME(3)  NOT NULL,
    CONSTRAINT pk_merchant_webhook_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT uk_merchant_webhook_attempt_no UNIQUE (delivery_id, attempt_no),
    CONSTRAINT fk_merchant_webhook_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES merchant_webhook_delivery (delivery_id),
    CONSTRAINT ck_merchant_webhook_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_merchant_webhook_attempt_outcome CHECK (REGEXP_LIKE(outcome, '^[A-Z0-9_]{1,48}$', 'c')),
    CONSTRAINT ck_merchant_webhook_attempt_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_merchant_webhook_attempt_time CHECK (finished_at >= started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
