-- PAYMENT DB 첫 운영 migration (CP-DB-001). 근거: docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md §8–§12, D-CP-001/002,
-- D-CPC-002/003/004/005/007, ARCHITECTURE-CONVENTIONS §9.
--
-- 공통 규칙
-- * 모든 문자열 컬럼은 utf8mb4 + utf8mb4_0900_bin(NO PAD binary)이다(D-CP-001). 서버 기본 utf8mb4_0900_ai_ci(대소문자·악센트
--   무시)를 상속하지 않도록 테이블 기본값과 키·fingerprint 컬럼에 COLLATE를 명시한다. 대소문자·후행 공백이 다른 키는 다른 키다.
-- * 시각은 UTC DATETIME(3)(ms). 애플리케이션이 UTC Instant로 쓴다(hibernate.jdbc.time_zone=UTC).
-- * Cross-DB FK 없음. 같은 TX에서 control·멱등 기록이 Payment보다 먼저 잠기고(잠금 순서: 멱등 기록 → control → Payment) 그 순서로
--   삽입되므로 그 참조(current_payment_id, result_payment_id)에는 FK를 두지 않는다(MySQL에는 지연 FK가 없다). 원자성은 같은 로컬 TX가
--   보장하고 rollback 시험이 고아 행 없음을 확인한다. 부모가 먼저 있는 자식(종료 근거, incident hold)에는 FK를 둔다.
-- * MID·Site 길이 64는 MASTER 발급 규칙 확정 전 후보(계약 §12 관례). paymentId·paymentKey 형식은 CP-API-001.

CREATE TABLE payment (
    payment_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_key                  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                  VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                 BIGINT       NOT NULL,
    currency                     CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_method               VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- 표시용(고정 입력 아님, D-CPC-001). 길이 단위(UTF-16/code point/UTF-8 byte)는 CP-API-001(L-8)이며 어느 쪽이든 100 이하면
    -- code point 100 이하이므로 VARCHAR(100)(문자 = code point)에 들어간다.
    order_name                   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    order_intent_fingerprint     VARCHAR(80)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    slot_generation              BIGINT       NOT NULL,
    status                       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    eligibility_snapshot_version VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at                   DATETIME(3)  NOT NULL,
    -- JPA @Version(동시 수정 감지 기술 값). 업무 generation(slot_generation)과 별개다.
    version                      BIGINT       NOT NULL,
    CONSTRAINT pk_payment PRIMARY KEY (payment_id),
    CONSTRAINT uk_payment_key UNIQUE (payment_key),
    -- 주문 1:N 이력이라 (environment, MID, orderId) UNIQUE는 금지(계약 §12). 같은 주문 슬롯 generation당 Payment는 하나다
    -- (OrderPaymentControl.occupy가 generation을 1씩 올린다 — 이중 점유의 DB 안전망이며 이 인덱스가 주문 조회 인덱스도 겸한다).
    CONSTRAINT uk_payment_order_generation UNIQUE (environment, merchant_account_id, merchant_order_id, slot_generation),
    CONSTRAINT ck_payment_amount_positive CHECK (amount_minor >= 1),
    CONSTRAINT ck_payment_currency_format CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_method CHECK (payment_method IN ('CARD')),
    CONSTRAINT ck_payment_status CHECK (status IN ('REQUESTED', 'AUTHORIZING', 'UNKNOWN', 'AUTHORIZED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_payment_slot_generation CHECK (slot_generation >= 1),
    CONSTRAINT ck_payment_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Payment 종료 근거(append-only, D-CPD-002). seq는 domain 목록 순서. 기록 출처·시각·행위자는 AUTH-001에서 추가한다.
CREATE TABLE payment_termination_evidence (
    payment_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq        INT         NOT NULL,
    evidence   VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_payment_termination_evidence PRIMARY KEY (payment_id, seq),
    CONSTRAINT fk_payment_termination_evidence_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT ck_payment_termination_evidence_seq CHECK (seq >= 0),
    CONSTRAINT ck_payment_termination_evidence_value CHECK (evidence IN (
        'INSTITUTION_CONFIRMED_DECLINE', 'PRE_TRANSMISSION_FAILURE_CONFIRMED', 'INSTITUTION_CONFIRMED_NO_EFFECT',
        'NOT_TRANSMITTED_AND_SESSION_DISCARDED', 'TIMEOUT', 'INQUIRY_NOT_FOUND', 'LEASE_EXPIRED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 주문(OrderKey)당 결제 슬롯 하나(영구 보존). PK = OrderKey. 최초 insert 경쟁은 PK 충돌 대기(INSERT … ON DUPLICATE KEY)로 직렬화한다.
CREATE TABLE order_payment_control (
    environment              VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fixed_intent_fingerprint VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    current_payment_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- 업무 generation(몇 번째 시도가 슬롯을 점유했나). JPA @Version과 별개다.
    generation               BIGINT      NOT NULL,
    authorized_payment_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at               DATETIME(3) NOT NULL,
    updated_at               DATETIME(3) NOT NULL,
    version                  BIGINT      NOT NULL,
    CONSTRAINT pk_order_payment_control PRIMARY KEY (environment, merchant_account_id, merchant_order_id),
    CONSTRAINT ck_order_payment_control_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_order_payment_control_generation CHECK (generation >= 1),
    CONSTRAINT ck_order_payment_control_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 주문 사건 보류(append-only). 같은 늦은 성공의 중복 보고는 UNIQUE(OrderKey, payment_id, reason)로 한 건이다(CP-DOMAIN-001 M-3).
CREATE TABLE incident_hold (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason              VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    CONSTRAINT pk_incident_hold PRIMARY KEY (id),
    CONSTRAINT uk_incident_hold_order_payment_reason
        UNIQUE (environment, merchant_account_id, merchant_order_id, payment_id, reason),
    CONSTRAINT fk_incident_hold_order FOREIGN KEY (environment, merchant_account_id, merchant_order_id)
        REFERENCES order_payment_control (environment, merchant_account_id, merchant_order_id),
    CONSTRAINT ck_incident_hold_reason CHECK (reason IN ('SUCCESS_AFTER_TERMINATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- API 멱등 기록(D-CPC-004 30일, D-CPC-007 업무 거절 재생). 범위 UNIQUE(environment, MID, operation, key).
-- PENDING은 같은 TX 안의 예약 상태다. 결과와 함께 같은 TX로 커밋되므로 커밋된 PENDING은 없어야 한다(있으면 애플리케이션이 오류로 본다).
-- 만료(expires_at <= 요청 시각) 행은 삭제 전이라도 재생하지 않고 같은 행을 잠근 채 새 요청으로 갱신한다(request_record_id 교체).
CREATE TABLE idempotency_request (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation           VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    idempotency_key     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- 이 요청 기록의 ID(이벤트 causationId). 만료 후 새 요청으로 갱신되면 바뀐다.
    request_record_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_fingerprint VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_kind         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_code         VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    -- 재생용 참조: 성공은 생성/재사용 Payment, 거절은 referencePaymentId. 응답 값은 Payment의 생성 시 불변 컬럼에서 다시 읽는다.
    result_payment_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at          DATETIME(3) NOT NULL,
    expires_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    version             BIGINT      NOT NULL,
    CONSTRAINT pk_idempotency_request PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_request_scope UNIQUE (environment, merchant_account_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_request_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_idempotency_request_retention CHECK (expires_at = created_at + INTERVAL 30 DAY),
    CONSTRAINT ck_idempotency_request_result CHECK (
        (result_kind = 'PENDING' AND result_code IS NULL AND result_payment_id IS NULL)
        OR (result_kind = 'SUCCESS' AND result_code IN ('CREATED', 'EXISTING_ACTIVE_PAYMENT') AND result_payment_id IS NOT NULL)
        OR (result_kind = 'REJECTED'
            AND result_code IN ('FIXED_INTENT_CONFLICT', 'INCIDENT_HOLD', 'AUTHORIZED_NEW_ORDER_REQUIRED',
                                'TERMINATION_EVIDENCE_INSUFFICIENT')
            AND result_payment_id IS NOT NULL)),
    CONSTRAINT ck_idempotency_request_version CHECK (version >= 0),
    INDEX ix_idempotency_request_expires_at (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 통합 이벤트 Outbox(D-CPC-003). append만 이 작업 범위이며 Relay(lease·재발행·published 표시)는 별도 작업.
-- id(auto increment)는 커밋 순서를 보장하지 않는다(docs/baseline/architecture/03).
CREATE TABLE outbox_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    event_id          CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_type        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version    INT          NOT NULL,
    event_category    VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    producer          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_type    VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    correlation_id    VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    causation_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload           JSON         NOT NULL,
    publish_status    VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    published_at      DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT uk_outbox_event_effect_key UNIQUE (effect_key),
    CONSTRAINT ck_outbox_event_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_event_publish_status CHECK (
        (publish_status = 'PENDING' AND published_at IS NULL) OR (publish_status = 'PUBLISHED' AND published_at IS NOT NULL)),
    INDEX ix_outbox_event_pending (publish_status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
