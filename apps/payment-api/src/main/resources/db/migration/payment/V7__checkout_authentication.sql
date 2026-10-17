-- AUTHN-001 결제창·인증 세션(명세 rev 1.3 §10.1). 공통 관례(V1): 문자열 utf8mb4_0900_bin, 시각 UTC DATETIME(3), version ≥ 0, 자식 → 부모 FK.
-- 원문 비밀(토큰·쿠키 비밀·state·결과 참조) 컬럼은 없다 — 종류 접두 + SHA-256 hex digest만(D-AUTHN-001). 카드번호·CVC 컬럼 없음(가상 카드 참조만).
-- 생성 컬럼 UNIQUE는 MASTER 관례(active_channel_key)와 같다: domain 불변식(미교환 토큰·ACTIVE 결제창 세션·비종료 인증 세션·귀속 세션 각 Payment당 1)을
-- DB가 다시 지킨다. 표 사이 시각 관계(토큰·결제창 세션 ≤ Payment 결제창 시각, 인증 세션 ≤ 결제창 세션)는 CHECK로 표현할 수 없어 application이 자른다.

-- D-AUTHN-005: Payment 결제창 절대 만료 시각(생성 시각 + 버전 있는 결제 유효기간 설정). NULL = V7 이전 행(D-AUTHN-007 — 애플리케이션이 채운 뒤
-- V8_1이 REQUESTED의 NOT NULL을 CHECK로 건다). 인덱스는 만료 후보 조회(status = 'REQUESTED' AND checkout_expires_at <= ?, 키셋)용.
ALTER TABLE payment
    ADD COLUMN checkout_expires_at       DATETIME(3) NULL,
    ADD COLUMN checkout_validity_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD CONSTRAINT ck_payment_checkout_window_pair CHECK ((checkout_expires_at IS NULL) = (checkout_validity_version IS NULL)),
    ADD CONSTRAINT ck_payment_checkout_window_after_created CHECK (checkout_expires_at IS NULL OR checkout_expires_at > created_at),
    ADD INDEX ix_payment_requested_expiry (status, checkout_expires_at, payment_id);

-- D-AUTHN-007 채우기 기준(m-B): V7 뒤 첫 기동이 그때 활성인 결제 유효기간 설정(버전·기간)을 한 행으로 고정한다. 모든 채우기가 이 값을 쓴다(배포 시점 버전).
-- 한 행만(pin_id = 1), 바꾸지 않는다(runtime 권한 SELECT·INSERT).
CREATE TABLE payment_checkout_window_pin (
    pin_id               TINYINT     NOT NULL,
    validity_version     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    validity_duration_ms BIGINT      NOT NULL,
    pinned_at            DATETIME(3) NOT NULL,
    CONSTRAINT pk_payment_checkout_window_pin PRIMARY KEY (pin_id),
    CONSTRAINT ck_payment_checkout_window_pin_single CHECK (pin_id = 1),
    CONSTRAINT ck_payment_checkout_window_pin_duration CHECK (validity_duration_ms > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 결제창 1회용 토큰(D-AUTHN-001, 명세 §5). 보안 프로토콜 기록(Store, AN-14). 미교환(ISSUED) 토큰은 Payment당 1(AN-04).
CREATE TABLE checkout_token (
    checkout_token_id       VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    token_digest            CHAR(69)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id              VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment             VARCHAR(4)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id     VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id       VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id        VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor            BIGINT        NOT NULL,
    currency                CHAR(3)       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    success_url             VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fail_url                VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    site_host               VARCHAR(253)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    site_snapshot_version   VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status                  VARCHAR(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issued_at               DATETIME(3)   NOT NULL,
    expires_at              DATETIME(3)   NOT NULL,
    consumed_at             DATETIME(3)   NULL,
    closed_at               DATETIME(3)   NULL,
    issued_by_credential_id VARCHAR(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_version          VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version                 BIGINT        NOT NULL,
    outstanding_payment_id  VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status = 'ISSUED', payment_id, NULL)) STORED,
    CONSTRAINT pk_checkout_token PRIMARY KEY (checkout_token_id),
    CONSTRAINT uk_checkout_token_digest UNIQUE (token_digest),
    CONSTRAINT uk_checkout_token_outstanding UNIQUE (outstanding_payment_id),
    CONSTRAINT fk_checkout_token_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT ck_checkout_token_digest CHECK (REGEXP_LIKE(token_digest, '^cth1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_checkout_token_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_checkout_token_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_checkout_token_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_checkout_token_urls CHECK (REGEXP_LIKE(success_url, '^https://', 'c') AND REGEXP_LIKE(fail_url, '^https://', 'c')),
    CONSTRAINT ck_checkout_token_status CHECK (status IN ('ISSUED', 'CONSUMED', 'SUPERSEDED', 'EXPIRED', 'CLOSED')),
    CONSTRAINT ck_checkout_token_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_checkout_token_consumed CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT ck_checkout_token_closed CHECK ((status IN ('SUPERSEDED', 'EXPIRED', 'CLOSED')) = (closed_at IS NOT NULL)),
    CONSTRAINT ck_checkout_token_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 결제창 브라우저 세션(쿠키 비밀 digest, handle은 경로 식별자 — 판정 m-1). 토큰 1개 = 세션 1개, ACTIVE 세션은 Payment당 1.
CREATE TABLE checkout_session (
    checkout_session_id VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    handle              CHAR(22)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    session_digest      CHAR(69)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    checkout_token_id   VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id          VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    success_url         VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fail_url            VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    site_host           VARCHAR(253)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status              VARCHAR(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    close_reason        VARCHAR(24)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at          DATETIME(3)   NOT NULL,
    expires_at          DATETIME(3)   NOT NULL,
    closed_at           DATETIME(3)   NULL,
    policy_version      VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version             BIGINT        NOT NULL,
    active_payment_id   VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status = 'ACTIVE', payment_id, NULL)) STORED,
    CONSTRAINT pk_checkout_session PRIMARY KEY (checkout_session_id),
    CONSTRAINT uk_checkout_session_handle UNIQUE (handle),
    CONSTRAINT uk_checkout_session_digest UNIQUE (session_digest),
    CONSTRAINT uk_checkout_session_token UNIQUE (checkout_token_id),
    CONSTRAINT uk_checkout_session_active UNIQUE (active_payment_id),
    CONSTRAINT fk_checkout_session_token FOREIGN KEY (checkout_token_id) REFERENCES checkout_token (checkout_token_id),
    CONSTRAINT fk_checkout_session_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT ck_checkout_session_handle CHECK (REGEXP_LIKE(handle, '^[A-Za-z0-9_-]{22}$', 'c')),
    CONSTRAINT ck_checkout_session_digest CHECK (REGEXP_LIKE(session_digest, '^cks1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_checkout_session_urls CHECK (REGEXP_LIKE(success_url, '^https://', 'c') AND REGEXP_LIKE(fail_url, '^https://', 'c')),
    CONSTRAINT ck_checkout_session_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_checkout_session_open CHECK ((status = 'ACTIVE') = (close_reason IS NULL) AND (status = 'ACTIVE') = (closed_at IS NULL)),
    CONSTRAINT ck_checkout_session_close_reason CHECK (
        close_reason IS NULL
        OR (status = 'COMPLETED' AND close_reason IN ('VERIFIED_REDIRECT', 'CANCELLED', 'PAYMENT_NOT_AVAILABLE'))
        OR (status = 'REVOKED' AND close_reason = 'SUPERSEDED')
        OR (status = 'EXPIRED' AND close_reason IN ('TTL_ELAPSED', 'PAYMENT_CLOSED'))),
    CONSTRAINT ck_checkout_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_checkout_session_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 고객 카드 인증 세션(Aggregate, AUTH-CONTRACT-001 §5.3·AUTHN-001 §7). 단방향 저장 규칙(H-4): 대체·만료된 VERIFIED 세션은 증거·영수증을 가진 EXPIRED로 남는다.
-- bound_authorization_id 쓰기 경로는 AUTH-001(confirm)이다.
CREATE TABLE authentication_session (
    authentication_session_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    checkout_session_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment               VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor              BIGINT       NOT NULL,
    currency                  CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind                 VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months        INT          NULL,
    card_company              VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status                    VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state_digest              CHAR(69)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    provider_transaction_ref  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    result_ref_digest         CHAR(69)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    failure_reason            VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    expiry_cause              VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    evidence_ref              VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    card_type                 VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    issuer_code               VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    acquirer_code             VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_code             VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_valid_until      DATETIME(3)  NULL,
    verified_at               DATETIME(3)  NULL,
    authentication_ref        CHAR(27)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    bound_authorization_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    profile_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_policy_version       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_version            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at                DATETIME(3)  NOT NULL,
    expires_at                DATETIME(3)  NOT NULL,
    version                   BIGINT       NOT NULL,
    open_payment_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status IN ('CREATED', 'IN_PROGRESS'), payment_id, NULL)) STORED,
    bound_payment_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(bound_authorization_id IS NOT NULL, payment_id, NULL)) STORED,
    CONSTRAINT pk_authentication_session PRIMARY KEY (authentication_session_id),
    CONSTRAINT uk_authentication_session_state UNIQUE (state_digest),
    CONSTRAINT uk_authentication_session_result_ref UNIQUE (result_ref_digest),
    CONSTRAINT uk_authentication_session_ref UNIQUE (authentication_ref),
    CONSTRAINT uk_authentication_session_bound_authorization UNIQUE (bound_authorization_id),
    CONSTRAINT uk_authentication_session_open UNIQUE (open_payment_id),
    CONSTRAINT uk_authentication_session_bound UNIQUE (bound_payment_id),
    CONSTRAINT fk_authentication_session_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_authentication_session_checkout FOREIGN KEY (checkout_session_id) REFERENCES checkout_session (checkout_session_id),
    CONSTRAINT ck_authentication_session_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_authentication_session_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_authentication_session_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_authentication_session_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_authentication_session_status CHECK (status IN ('CREATED', 'IN_PROGRESS', 'VERIFIED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_authentication_session_state_digest CHECK (REGEXP_LIKE(state_digest, '^std1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_authentication_session_result_ref_digest CHECK (result_ref_digest IS NULL OR REGEXP_LIKE(result_ref_digest, '^rrd1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_authentication_session_ref_format CHECK (authentication_ref IS NULL OR REGEXP_LIKE(authentication_ref, '^aref_[A-Za-z0-9_-]{22}$', 'c')),
    CONSTRAINT ck_authentication_session_failure CHECK (
        (status = 'FAILED') = (failure_reason IS NOT NULL)
        AND (failure_reason IS NULL OR failure_reason IN ('AUTHENTICATION_FAILED', 'USER_CANCELLED', 'BINDING_MISMATCH', 'STATE_MISMATCH', 'PROVIDER_EXPIRED'))),
    CONSTRAINT ck_authentication_session_expiry_cause CHECK (
        (status = 'EXPIRED') = (expiry_cause IS NOT NULL)
        AND (expiry_cause IS NULL OR expiry_cause IN ('TTL_ELAPSED', 'PAYMENT_CLOSED', 'SUPERSEDED'))),
    -- 증거 컬럼(인증 참조·검증 시각 포함)은 모두 있거나 모두 없다.
    CONSTRAINT ck_authentication_session_evidence_all CHECK (
        (evidence_ref IS NULL AND card_type IS NULL AND issuer_code IS NULL AND acquirer_code IS NULL AND provider_code IS NULL
            AND provider_valid_until IS NULL AND verified_at IS NULL AND authentication_ref IS NULL)
        OR (evidence_ref IS NOT NULL AND card_type IS NOT NULL AND issuer_code IS NOT NULL AND acquirer_code IS NOT NULL AND provider_code IS NOT NULL
            AND provider_valid_until IS NOT NULL AND verified_at IS NOT NULL AND authentication_ref IS NOT NULL)),
    CONSTRAINT ck_authentication_session_card_type CHECK (card_type IS NULL OR card_type IN ('CREDIT', 'CHECK')),
    -- 단방향(H-4): VERIFIED ⇒ 증거 + 결과 참조, 증거 ⇒ VERIFIED·EXPIRED, 결과 참조 ⇒ VERIFIED·FAILED·(증거 있는) EXPIRED(n-h).
    CONSTRAINT ck_authentication_session_verified CHECK (status <> 'VERIFIED' OR (evidence_ref IS NOT NULL AND result_ref_digest IS NOT NULL)),
    CONSTRAINT ck_authentication_session_evidence_status CHECK (evidence_ref IS NULL OR status IN ('VERIFIED', 'EXPIRED')),
    CONSTRAINT ck_authentication_session_result_ref_status CHECK (
        result_ref_digest IS NULL OR status IN ('VERIFIED', 'FAILED') OR (status = 'EXPIRED' AND evidence_ref IS NOT NULL)),
    CONSTRAINT ck_authentication_session_bound_verified CHECK (bound_authorization_id IS NULL OR status = 'VERIFIED'),
    CONSTRAINT ck_authentication_session_provider_ref CHECK (
        (status NOT IN ('IN_PROGRESS', 'VERIFIED') OR provider_transaction_ref IS NOT NULL) AND (status <> 'CREATED' OR provider_transaction_ref IS NULL)),
    CONSTRAINT ck_authentication_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_authentication_session_version CHECK (version >= 0),
    INDEX ix_authentication_session_payment (payment_id, created_at),
    INDEX ix_authentication_session_checkout (checkout_session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 인증 세션 결정 로그(append-only, runtime SELECT·INSERT만). seq는 payment → 세션 잠금 아래 max + 1. 반복 가능한 종류는 (종류, 상세)당 1행(§7.6, n-b).
CREATE TABLE authentication_session_event (
    authentication_session_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq                       INT          NOT NULL,
    kind                      VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    detail_code               VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    occurred_at               DATETIME(3)  NOT NULL,
    correlation_id            VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    repeat_key                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(kind IN ('DUPLICATE_RESULT', 'IGNORED_LATE_RESULT', 'REJECTED_RESULT', 'LOOKUP_UNVERIFIABLE'),
                                CONCAT(kind, ':', COALESCE(detail_code, '')), NULL)) STORED,
    CONSTRAINT pk_authentication_session_event PRIMARY KEY (authentication_session_id, seq),
    CONSTRAINT uk_authentication_session_event_repeat UNIQUE (authentication_session_id, repeat_key),
    CONSTRAINT fk_authentication_session_event_session FOREIGN KEY (authentication_session_id)
        REFERENCES authentication_session (authentication_session_id),
    CONSTRAINT ck_authentication_session_event_seq CHECK (seq >= 0),
    CONSTRAINT ck_authentication_session_event_kind CHECK (kind IN (
        'CREATED', 'STARTED', 'VERIFIED', 'FAILED', 'EXPIRED', 'DUPLICATE_RESULT', 'IGNORED_LATE_RESULT', 'REJECTED_RESULT', 'LOOKUP_UNVERIFIABLE',
        'REGISTRATION_FAILED')),
    CONSTRAINT ck_authentication_session_event_detail CHECK (detail_code IS NULL OR REGEXP_LIKE(detail_code, '^[A-Z][A-Z_]{0,31}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
