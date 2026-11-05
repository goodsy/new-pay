-- FIN-FACT-RELAY-001: PAYMENT → FINANCIAL 결제 사실 수신(Inbox)과 결제 사실 투영. 명세: docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §5.
-- D-ACQ-001: PAYMENT Outbox → 서명된 내부 HTTP push → FINANCIAL Inbox(effect key unique, Inbox + 투영 같은 로컬 TX). PAYMENT 표를 참조하지 않는다(cross-DB FK·SQL 없음).
-- 공통 규칙(PAYMENT V1과 같다): 키·식별 열은 utf8mb4_0900_bin(입력 그대로 비교), 시각은 UTC DATETIME(3), 금액은 통화 minor unit 정수.
-- 이 표들은 모두 append-only다(투영 사실·보류는 INSERT만). 단 payment_fact_stream(결제별 직렬화·건수)만 UPDATE가 있다. 삭제 경로는 없다.
-- FINANCIAL은 아직 단일 계정(ALL)이라 append-only를 DB 권한으로 강제하지 못한다(명세 §9 LIVE Gate L-1 — 계정 분리 후 runtime 권한 migration 추가).

-- Inbox(수신 증거·재처리 원문). event_id·effect_key 둘 다 UNIQUE: 같은 이벤트·같은 업무 효과는 한 번만 기록된다(재전송은 저장된 결과로 응답 — DUPLICATE).
-- payload = 받은 봉투(JSON 값 — MySQL이 정규화해 저장하므로 원문 바이트 증거는 payload_sha256). outcome: APPLIED(투영, 보류 없음)·HELD(투영 + 보류)·UNSUPPORTED
-- (지원하지 않는 eventType/schemaVersion — 투영 없이 원문만 보존, 경보 대상). payment_id·transaction_id는 지원 이벤트일 때만 있다.
CREATE TABLE payment_fact_inbox (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    event_id          CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_type        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version    INT          NOT NULL,
    producer          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_type    VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    outcome           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    transaction_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    payload           JSON         NOT NULL,
    payload_sha256    CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    signing_key_id    VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    received_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_payment_fact_inbox PRIMARY KEY (id),
    CONSTRAINT uk_payment_fact_inbox_event UNIQUE (event_id),
    CONSTRAINT uk_payment_fact_inbox_effect UNIQUE (effect_key),
    CONSTRAINT ck_payment_fact_inbox_outcome CHECK (outcome IN ('APPLIED', 'HELD', 'UNSUPPORTED')),
    CONSTRAINT ck_payment_fact_inbox_subject CHECK (
        (outcome = 'UNSUPPORTED') OR (payment_id IS NOT NULL AND transaction_id IS NOT NULL)),
    CONSTRAINT ck_payment_fact_inbox_versions CHECK (schema_version >= 1 AND aggregate_version >= 0),
    CONSTRAINT ck_payment_fact_inbox_sha256 CHECK (REGEXP_LIKE(payload_sha256, '^[0-9a-f]{64}$', 'c')),
    INDEX ix_payment_fact_inbox_outcome (outcome, id),
    INDEX ix_payment_fact_inbox_payment (payment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 결제별 직렬화 행: 같은 Payment의 사실(승인·취소)은 이 행의 FOR UPDATE에서 줄을 선다(검사 — 원승인 존재·합계·순번 — 가 경합하지 않는다).
-- 첫 사실의 신원(환경·MID·사이트·가맹점 주문번호)을 남기고, 뒤 사실의 신원이 다르면 보류(IDENTITY_MISMATCH). fact_count = 반영한 사실 수(APPLIED + HELD).
-- 행은 첫 사실과 같은 TX에서 IODKU로 생기고(그 TX가 rollback되면 함께 사라진다) 같은 Payment의 두 번째 사실부터는 기존 행의 잠금을 기다린다.
CREATE TABLE payment_fact_stream (
    payment_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fact_count          INT         NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    version             BIGINT      NOT NULL,
    CONSTRAINT pk_payment_fact_stream PRIMARY KEY (payment_id),
    CONSTRAINT ck_payment_fact_stream_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_fact_stream_count CHECK (fact_count >= 0),
    CONSTRAINT ck_payment_fact_stream_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 승인 사실 투영(PaymentAuthorized v1 — PAYMENT 확정 승인 거래 1건 = 행 1개). 업무 키 = transaction_id(= effect key `PaymentAuthorized:{transactionId}`).
-- 카드번호·Secret·인증 증거·기관 계약계정은 PAYMENT payload에도 없다. 정책·권리 버전은 원승인 고정값(수수료·VAT Snapshot 근거 — 정산 작업이 쓴다).
CREATE TABLE payment_fact_authorization (
    transaction_id               VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                  VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                 BIGINT       NOT NULL,
    currency                     CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind                    VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months           INT          NULL,
    card_type                    VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issuer_code                  VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquirer_code                VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_number              VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    provider_transaction_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_transaction_date DATE         NOT NULL,
    institution_business_date    DATE         NULL,
    approved_at                  DATETIME(3)  NOT NULL,
    policy_assignment_version    VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    entitlement_version          VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_event_id              CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key                   VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    received_at                  DATETIME(3)  NOT NULL,
    CONSTRAINT pk_payment_fact_authorization PRIMARY KEY (transaction_id),
    CONSTRAINT uk_payment_fact_authorization_event UNIQUE (source_event_id),
    CONSTRAINT uk_payment_fact_authorization_effect UNIQUE (effect_key),
    CONSTRAINT fk_payment_fact_authorization_stream FOREIGN KEY (payment_id) REFERENCES payment_fact_stream (payment_id),
    CONSTRAINT ck_payment_fact_authorization_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_payment_fact_authorization_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_fact_authorization_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_fact_authorization_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_payment_fact_authorization_card_type CHECK (card_type IN ('CREDIT', 'CHECK')),
    INDEX ix_payment_fact_authorization_payment (payment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 취소 사실 투영(PaymentCancelled v1 — PAYMENT 확정 CANCEL 거래 1건 = 행 1개). 업무 키 = transaction_id(= effect key `PaymentCancelled:{transactionId}`).
-- original_transaction_id로 원승인을 가리키지만 FK를 두지 않는다: 원승인보다 먼저 도착한 취소도 잃지 않고 보류(ORIGINAL_UNKNOWN)로 남긴다(D-ACQ-002 Hold 입력).
-- original_known_at_receipt = 수신 시점에 원승인 투영이 있었는가(이후 원승인이 도착해도 이 열은 바뀌지 않는다 — 현재 연결 여부는 원승인 표와 join으로 판단).
CREATE TABLE payment_fact_cancellation (
    transaction_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id                 VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancellation_id                  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                      VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id              VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id                 VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                     BIGINT       NOT NULL,
    currency                         CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    scope                            VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorized_amount_minor          BIGINT       NOT NULL,
    return_sequence                  INT          NOT NULL,
    cumulative_returned_minor        BIGINT       NOT NULL,
    return_status_after              VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancel_approval_number           VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_transaction_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_provider_transaction_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_transaction_date     DATE         NOT NULL,
    institution_business_date        DATE         NULL,
    cancelled_at                     DATETIME(3)  NOT NULL,
    policy_assignment_version        VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    entitlement_version              VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    initiator_kind                   VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_plan_kind               VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_installment_months      INT          NULL,
    original_card_type               VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_known_at_receipt        BOOLEAN      NOT NULL,
    source_event_id                  CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effect_key                       VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    received_at                      DATETIME(3)  NOT NULL,
    CONSTRAINT pk_payment_fact_cancellation PRIMARY KEY (transaction_id),
    CONSTRAINT uk_payment_fact_cancellation_event UNIQUE (source_event_id),
    CONSTRAINT uk_payment_fact_cancellation_effect UNIQUE (effect_key),
    CONSTRAINT fk_payment_fact_cancellation_stream FOREIGN KEY (payment_id) REFERENCES payment_fact_stream (payment_id),
    CONSTRAINT ck_payment_fact_cancellation_amount CHECK (amount_minor >= 1 AND authorized_amount_minor >= 1 AND cumulative_returned_minor >= amount_minor),
    CONSTRAINT ck_payment_fact_cancellation_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_fact_cancellation_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_fact_cancellation_scope CHECK (scope IN ('FULL', 'PARTIAL')),
    CONSTRAINT ck_payment_fact_cancellation_sequence CHECK (return_sequence >= 1),
    CONSTRAINT ck_payment_fact_cancellation_initiator CHECK (initiator_kind IN ('MERCHANT', 'OPERATOR')),
    CONSTRAINT ck_payment_fact_cancellation_plan CHECK (
        (original_plan_kind = 'LUMP_SUM' AND original_installment_months IS NULL)
        OR (original_plan_kind = 'INSTALLMENT' AND original_installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_payment_fact_cancellation_card_type CHECK (original_card_type IN ('CREDIT', 'CHECK')),
    INDEX ix_payment_fact_cancellation_payment (payment_id),
    INDEX ix_payment_fact_cancellation_original (original_transaction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 보류(append-only). 투영과 같은 TX에 사유마다 한 행. 해제(release) 표·경로는 없다 — 해제 규칙은 매입 작업(ACQUIRING-001, D-ACQ-002)이 정한다(명세 §2 I-4).
-- ORIGINAL_UNKNOWN: 원승인 투영 없이 도착한 취소(D-ACQ-002 "원거래를 모르는 취소는 Hold"). 나머지는 fail-closed 정합성 보류(Implementer 해석 I-5 — 명세 §2).
CREATE TABLE payment_fact_hold (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    transaction_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fact_kind       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason          VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_event_id CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    opened_at       DATETIME(3) NOT NULL,
    CONSTRAINT pk_payment_fact_hold PRIMARY KEY (id),
    CONSTRAINT uk_payment_fact_hold_reason UNIQUE (transaction_id, reason),
    CONSTRAINT fk_payment_fact_hold_stream FOREIGN KEY (payment_id) REFERENCES payment_fact_stream (payment_id),
    CONSTRAINT ck_payment_fact_hold_kind CHECK (fact_kind IN ('AUTHORIZATION', 'CANCELLATION')),
    CONSTRAINT ck_payment_fact_hold_reason CHECK (reason IN (
        'ORIGINAL_UNKNOWN', 'ORIGINAL_MISMATCH', 'RETURN_SEQUENCE_CONFLICT', 'RETURN_EXCEEDS_AUTHORIZED', 'AUTHORIZATION_CONFLICT', 'IDENTITY_MISMATCH')),
    INDEX ix_payment_fact_hold_payment (payment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
