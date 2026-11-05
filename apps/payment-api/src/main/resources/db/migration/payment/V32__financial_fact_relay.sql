-- FIN-FACT-RELAY-001 §5.1: PAYMENT Outbox → FINANCIAL 결제 사실 Relay 전달 상태(D-ACQ-001 — Outbox → 서명 내부 HTTP push → FINANCIAL Inbox).
-- 전달 상태는 이 표에 Outbox 행당 한 행으로 둔다(Relay가 미전달 FINANCIAL_FACT Outbox 행을 발견하면 만든다 — 업무 TX는 이 표를 쓰지 않는다). Outbox 쪽에는 전달 완료 표시
-- publish_status = PUBLISHED·published_at만 같은 TX에 남긴다(D-FFR-001 — 열 단위 UPDATE 권한은 V34). 가맹점 Webhook fan-out과 서로 다른 소비자 상태다(fan-out은
-- merchant_notification_* 열만, Relay는 publish_* 열만 바꾼다). Outbox로 가는 FK는 두지 않는다(WH-23과 같은 이유 — 부모 행 공유 잠금 회피).

-- 발견 질의(FINANCIAL_FACT·publish_status = PENDING — 미전달 행만)용 색인: 비용이 전체 사실 수가 아니라 미전달 행 수에 비례한다(D-FFR-001).
-- 한 ALTER의 온라인 DDL(INPLACE, LOCK=NONE) — 색인 생성 중에도 업무 INSERT를 막지 않는다(V31과 같은 방식).
ALTER TABLE outbox_event
    ADD INDEX ix_outbox_event_financial_pending (event_category, publish_status, id),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- 전달 상태(Outbox 행당 하나). PENDING(미전달 — 다음 시도 시각·claim) → DELIVERED(FINANCIAL 내구 커밋 ACK — 종결) | QUARANTINED(FINANCIAL이 내용을 거절 — 완료 아님, 경보).
-- claim_owner·claim_until: 다른 Relay 인스턴스와 같은 행을 동시에 보내지 않게 하는 lease(만료되면 다른 인스턴스가 다시 집는다 — Relay crash 회복). 같은 행이 두 번 가도
-- FINANCIAL Inbox가 eventId·effect key로 멱등 처리한다(at-least-once + 멱등 수신).
-- ack_outcome: FINANCIAL이 처음 처리한 결과(APPLIED·HELD·UNSUPPORTED — DUPLICATE ACK면 originalOutcome). attempts: 전송 결과를 기록한 횟수(ACK 포함).
CREATE TABLE financial_fact_relay (
    outbox_id         BIGINT      NOT NULL,
    event_id          CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status            VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempts          INT         NOT NULL,
    next_attempt_at   DATETIME(3) NOT NULL,
    claim_owner       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    claim_until       DATETIME(3) NULL,
    last_failure_code VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ack_outcome       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    delivered_at      DATETIME(3) NULL,
    quarantined_at    DATETIME(3) NULL,
    version           BIGINT      NOT NULL,
    CONSTRAINT pk_financial_fact_relay PRIMARY KEY (outbox_id),
    CONSTRAINT uk_financial_fact_relay_event UNIQUE (event_id),
    CONSTRAINT ck_financial_fact_relay_status CHECK (
        (status = 'PENDING' AND delivered_at IS NULL AND quarantined_at IS NULL AND ack_outcome IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL AND quarantined_at IS NULL AND ack_outcome IS NOT NULL AND claim_owner IS NULL)
        OR (status = 'QUARANTINED' AND quarantined_at IS NOT NULL AND delivered_at IS NULL AND ack_outcome IS NULL AND claim_owner IS NULL)),
    CONSTRAINT ck_financial_fact_relay_claim CHECK ((claim_owner IS NULL) = (claim_until IS NULL)),
    CONSTRAINT ck_financial_fact_relay_first_outcome CHECK (ack_outcome IS NULL OR ack_outcome IN ('APPLIED', 'HELD', 'UNSUPPORTED')),
    CONSTRAINT ck_financial_fact_relay_failure_code CHECK (last_failure_code IS NULL OR REGEXP_LIKE(last_failure_code, '^[A-Z0-9_]{1,48}$', 'c')),
    CONSTRAINT ck_financial_fact_relay_counts CHECK (attempts >= 0 AND version >= 0),
    -- claim 질의는 status = PENDING 안에서 outbox_id 순(LIMIT)으로 읽고 next_attempt_at·claim_until은 남은 조건으로 거른다(검토 m-3 — 정렬 = 색인 순서).
    INDEX ix_financial_fact_relay_claim (status, outbox_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ACK 기록(append-only 감사 — FINANCIAL이 내구 커밋했다는 응답을 받을 때마다 한 행, 중복 전달의 DUPLICATE ACK도 남는다). 삭제 경로 없음(runtime SELECT·INSERT만 — V33).
CREATE TABLE financial_fact_relay_ack (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    outbox_id        BIGINT      NOT NULL,
    event_id         CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    consumer         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outcome          VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_outcome VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    claim_owner      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acked_at         DATETIME(3) NOT NULL,
    CONSTRAINT pk_financial_fact_relay_ack PRIMARY KEY (id),
    CONSTRAINT ck_financial_fact_relay_ack_outcome CHECK (
        (outcome IN ('APPLIED', 'HELD', 'UNSUPPORTED') AND original_outcome IS NULL)
        OR (outcome = 'DUPLICATE' AND original_outcome IN ('APPLIED', 'HELD', 'UNSUPPORTED'))),
    INDEX ix_financial_fact_relay_ack_outbox (outbox_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
