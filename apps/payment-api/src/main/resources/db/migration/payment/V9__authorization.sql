-- PAYMENT 승인 계약 영속화(AUTH-001 명세 §7.1, AUTH-CONTRACT-001 §3·§5). 새 표만 만든다 — 기존 표 ALTER는 V11(MySQL DDL은 TX가 아니라 실패 시 반쯤 적용되지 않게 나눈다, AU-19).
-- 공통: 문자열 utf8mb4_0900_bin, 시각 UTC DATETIME(3), version ≥ 0, 금액 ≥ 1·통화 ^[A-Z]{3}$·환경 CHECK, 자식 → 부모 FK(V1 관례). 카드번호·CVC 열 없음.
-- 업무 규칙은 domain(PaymentAuthorization·AuthorizationAttempt·InstitutionObservation·AuthorizationTransaction·DailyLimitUsage)이 지키고 DB는 같은 규칙을 이중으로 지킨다.

-- Payment의 논리 승인(Payment 1:1). 판정 근거(권리·정책 Assignment·Route·프로토콜·Plan·한도 정책 버전, evaluatedAt)를 생성 시 고정한다.
CREATE TABLE payment_authorization (
    authorization_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment               VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor              BIGINT       NOT NULL,
    currency                  CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind                 VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months        INT          NULL,
    authenticated_card_type   VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authentication_session_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    entitlement_version       VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_assignment_version VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    route_profile             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    protocol_version          VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evaluated_at              DATETIME(3)  NOT NULL,
    plan_policy_version       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    limit_policy_version      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    limit_business_date       DATE         NOT NULL,
    status                    VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    failure_evidence          VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    opened_at                 DATETIME(3)  NOT NULL,
    version                   BIGINT       NOT NULL,
    CONSTRAINT pk_payment_authorization PRIMARY KEY (authorization_id),
    -- Payment당 논리 승인 하나(AUTH-CONTRACT-001 §3, 중복 승인 차단 겹 (3) — §7).
    CONSTRAINT uk_payment_authorization_payment UNIQUE (payment_id),
    CONSTRAINT fk_payment_authorization_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_payment_authorization_session FOREIGN KEY (authentication_session_id) REFERENCES authentication_session (authentication_session_id),
    CONSTRAINT ck_payment_authorization_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_authorization_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_payment_authorization_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_authorization_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_payment_authorization_card_type CHECK (authenticated_card_type IN ('CREDIT', 'CHECK')),
    CONSTRAINT ck_payment_authorization_status CHECK (status IN ('OPEN', 'UNKNOWN', 'AUTHORIZED', 'FAILED')),
    -- FAILED ⇔ 충분한 종료 근거(전송 전 실패·기관 확정 거절·무효과 확정만 — D-CPD-002).
    CONSTRAINT ck_payment_authorization_failure CHECK (
        (status = 'FAILED') = (failure_evidence IS NOT NULL)
        AND (failure_evidence IS NULL OR failure_evidence IN (
            'INSTITUTION_CONFIRMED_DECLINE', 'PRE_TRANSMISSION_FAILURE_CONFIRMED', 'INSTITUTION_CONFIRMED_NO_EFFECT'))),
    CONSTRAINT ck_payment_authorization_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기관 승인 실행 시도(Authorization 안의 Entity). 기관 요청 namespace는 기관 + 환경 + 기관 계약계정 + operation + 요청 ID(허용 §5.2) UNIQUE.
-- institution_account(= institutionMerchantRef)는 내부 식별자 등급(D-MS2-005): 로그·지표·API·예외에 원문 금지 — Entity toString에 넣지 않는다.
CREATE TABLE authorization_attempt (
    attempt_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation           VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state               VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    abandon_cause       VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    unknown_cause       VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    prepared_at         DATETIME(3)  NOT NULL,
    seq                 INT          NOT NULL,
    version             BIGINT       NOT NULL,
    -- 동시 실행 Attempt는 Authorization당 하나(시도 §1, CA-13): 미전송·전송 중·결과 불명확 상태에서만 값이 있는 생성 열의 UNIQUE.
    in_flight_authorization_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(state IN ('PREPARED', 'DISPATCHING', 'OUTCOME_UNKNOWN'), authorization_id, NULL)) STORED,
    CONSTRAINT pk_authorization_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT uk_authorization_attempt_namespace UNIQUE (institution, environment, institution_account, operation, request_id),
    CONSTRAINT uk_authorization_attempt_in_flight UNIQUE (in_flight_authorization_id),
    CONSTRAINT uk_authorization_attempt_seq UNIQUE (authorization_id, seq),
    CONSTRAINT fk_authorization_attempt_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT ck_authorization_attempt_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_authorization_attempt_state CHECK (state IN ('PREPARED', 'DISPATCHING', 'ABANDONED_BEFORE_DISPATCH', 'OUTCOME_UNKNOWN', 'RESOLVED')),
    CONSTRAINT ck_authorization_attempt_abandon CHECK (
        (state = 'ABANDONED_BEFORE_DISPATCH') = (abandon_cause IS NOT NULL)
        AND (abandon_cause IS NULL OR abandon_cause IN ('GATE_REFUSED', 'FENCED_BY_RECOVERY', 'CONNECT_FAILED'))),
    CONSTRAINT ck_authorization_attempt_unknown CHECK (
        (state <> 'OUTCOME_UNKNOWN' OR unknown_cause IS NOT NULL)
        AND (state NOT IN ('PREPARED', 'DISPATCHING', 'ABANDONED_BEFORE_DISPATCH') OR unknown_cause IS NULL)
        AND (unknown_cause IS NULL OR unknown_cause IN ('TIMEOUT', 'CONNECTION_LOST', 'FRAMING', 'CORRELATION', 'INTEGRITY', 'DISPATCH_LEASE_EXPIRED',
                                                        'COMMIT_FAILURE_RECOVERY', 'INSTITUTION_UNCONFIRMED'))),
    CONSTRAINT ck_authorization_attempt_seq CHECK (seq >= 0),
    CONSTRAINT ck_authorization_attempt_version CHECK (version >= 0),
    INDEX ix_authorization_attempt_state (state, prepared_at, attempt_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기관 결과 관찰(append-only). 유일 키 (attempt, 출처, 기관 결과 ID, variant): variant 0 = 첫 관찰, n ≥ 1 = 같은 키·다른 내용의 상충 증거(review m-1).
-- 원문은 hash 참조만(카드번호·CVC·인증 비밀 없음).
CREATE TABLE institution_observation (
    id                           BIGINT       NOT NULL AUTO_INCREMENT,
    authorization_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source                       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_result_id             VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant                      INT          NOT NULL,
    outcome                      VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_amount_minor        BIGINT       NULL,
    approved_currency            CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approved_plan_kind           VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approved_installment_months  INT          NULL,
    approved_card_type           VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approval_number              VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_transaction_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_transaction_date DATE         NULL,
    -- 기관 영업일(검토 #1 m-2): 기관이 주지 않으면 NULL — 시간대·출처 의미는 Q-CALENDAR. 승인 조건이 아닌 관찰에는 항상 NULL.
    institution_business_date    DATE         NULL,
    evidence_hash                VARCHAR(80)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    observed_at                  DATETIME(3)  NOT NULL,
    CONSTRAINT pk_institution_observation PRIMARY KEY (id),
    CONSTRAINT uk_institution_observation_key UNIQUE (attempt_id, source, source_result_id, variant),
    CONSTRAINT fk_institution_observation_attempt FOREIGN KEY (attempt_id) REFERENCES authorization_attempt (attempt_id),
    CONSTRAINT fk_institution_observation_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT ck_institution_observation_source CHECK (source IN ('RESPONSE', 'INQUIRY', 'NOTIFICATION', 'RECONCILIATION')),
    CONSTRAINT ck_institution_observation_variant CHECK (variant >= 0),
    CONSTRAINT ck_institution_observation_outcome CHECK (outcome IN ('APPROVED', 'DECLINED', 'NO_EFFECT_CONFIRMED', 'PENDING', 'UNKNOWN', 'NOT_FOUND')),
    -- 승인 조건은 APPROVED에만, 그리고 전부 함께 있다.
    CONSTRAINT ck_institution_observation_terms CHECK (
        (outcome = 'APPROVED') = (approved_amount_minor IS NOT NULL)
        AND (approved_amount_minor IS NULL) = (approved_currency IS NULL)
        AND (approved_amount_minor IS NULL) = (approved_plan_kind IS NULL)
        AND (approved_amount_minor IS NULL) = (approved_card_type IS NULL)
        AND (approved_amount_minor IS NULL) = (approval_number IS NULL)
        AND (approved_amount_minor IS NULL) = (provider_transaction_id IS NULL)
        AND (approved_amount_minor IS NULL) = (institution_transaction_date IS NULL)
        AND (approved_amount_minor IS NOT NULL OR institution_business_date IS NULL)),
    CONSTRAINT ck_institution_observation_amount CHECK (approved_amount_minor IS NULL OR approved_amount_minor >= 1),
    CONSTRAINT ck_institution_observation_plan CHECK (
        approved_plan_kind IS NULL
        OR (approved_plan_kind = 'LUMP_SUM' AND approved_installment_months IS NULL)
        OR (approved_plan_kind = 'INSTALLMENT' AND approved_installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_institution_observation_card_type CHECK (approved_card_type IS NULL OR approved_card_type IN ('CREDIT', 'CHECK'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 확정 금융 사실(append-only, D-AUTH-003: 기관 확정 효과 1건 = 거래 1건). 공용 표 — 지금은 승인 거래만, 취소 거래·열은 CANCEL-001이 추가한다.
CREATE TABLE payment_transaction (
    transaction_id               VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    type                         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                 BIGINT       NOT NULL,
    currency                     CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind                    VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months           INT          NULL,
    card_type                    VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_number              VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    provider_transaction_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_transaction_date DATE         NOT NULL,
    institution_business_date    DATE         NULL,
    source_attempt_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_kind                  VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_result_id             VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_by                  VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    confirmed_at                 DATETIME(3)  NOT NULL,
    -- Authorization당 승인 거래 하나(FAILED + RECON 기록 거래 포함, D-CANCEL-011).
    authorization_txn_key        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(type = 'AUTHORIZATION', authorization_id, NULL)) STORED,
    CONSTRAINT pk_payment_transaction PRIMARY KEY (transaction_id),
    CONSTRAINT uk_payment_transaction_authorization UNIQUE (authorization_txn_key),
    CONSTRAINT fk_payment_transaction_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_payment_transaction_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT ck_payment_transaction_type CHECK (type IN ('AUTHORIZATION')),
    CONSTRAINT ck_payment_transaction_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_payment_transaction_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_transaction_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_payment_transaction_card_type CHECK (card_type IN ('CREDIT', 'CHECK')),
    CONSTRAINT ck_payment_transaction_source CHECK (source_kind IN ('RESPONSE', 'INQUIRY', 'NOTIFICATION', 'RECONCILIATION')),
    CONSTRAINT ck_payment_transaction_recorded_by CHECK (recorded_by IN ('RESOLUTION', 'RECONCILIATION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 일 한도 사용량(PAYMENT 소유 권위 상태 — 캐시·Redis 카운터가 대신하지 않는다). 키 = 환경 × MID × 통화 × Asia/Seoul 업무일(D-AUTH-006·007).
-- 동시 점유는 이 행의 비관적 잠금으로 직렬화한다(서로 다른 Authorization 사이 합계 — 행별 version으로는 못 지킨다).
CREATE TABLE daily_limit_usage (
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    currency            CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_date       DATE         NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    version             BIGINT       NOT NULL,
    CONSTRAINT pk_daily_limit_usage PRIMARY KEY (environment, merchant_account_id, currency, business_date),
    CONSTRAINT ck_daily_limit_usage_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_daily_limit_usage_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_daily_limit_usage_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 승인 operation 하나의 일 한도 점유(D-AUTH-007·010). excess는 FORCED에만(초과 표시 — 판정·차단에 쓰지 않음).
CREATE TABLE limit_reservation (
    authorization_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    currency            CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_date       DATE         NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    state               VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    excess              VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    seq                 INT          NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    CONSTRAINT pk_limit_reservation PRIMARY KEY (authorization_id),
    CONSTRAINT uk_limit_reservation_seq UNIQUE (environment, merchant_account_id, currency, business_date, seq),
    CONSTRAINT fk_limit_reservation_usage FOREIGN KEY (environment, merchant_account_id, currency, business_date)
        REFERENCES daily_limit_usage (environment, merchant_account_id, currency, business_date),
    CONSTRAINT fk_limit_reservation_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT ck_limit_reservation_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_limit_reservation_state CHECK (state IN ('RESERVED', 'CONSUMED', 'RELEASED', 'FORCED')),
    CONSTRAINT ck_limit_reservation_excess CHECK (
        (state = 'FORCED') = (excess IS NOT NULL) AND (excess IS NULL OR excess IN ('WITHIN', 'EXCEEDED', 'UNDETERMINED'))),
    CONSTRAINT ck_limit_reservation_seq CHECK (seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 사건 보류 해제 기록(append-only, D-AUTH-002·011). hold당 해제 최대 1회, 요청자 ≠ 승인자, 재대사 MATCH 참조 필수, 보정 참조 ⇔ DOUBLE_APPROVAL_CORRECTED.
-- 이 작업에는 해제 쓰기 경로가 없다(해제 Use Case는 RECON-CONTRACT-001) — runtime 권한은 SELECT만이고, 해제 Use Case가 INSERT 권한 migration을 함께 추가한다(관례 §9.2).
CREATE TABLE incident_hold_release (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    incident_hold_id         BIGINT       NOT NULL,
    requested_by             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by              VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reconciliation_match_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    finding                  VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    correction_ref           VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason                   VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    released_at              DATETIME(3)  NOT NULL,
    CONSTRAINT pk_incident_hold_release PRIMARY KEY (id),
    CONSTRAINT uk_incident_hold_release_hold UNIQUE (incident_hold_id),
    CONSTRAINT fk_incident_hold_release_hold FOREIGN KEY (incident_hold_id) REFERENCES incident_hold (id),
    CONSTRAINT ck_incident_hold_release_two_people CHECK (requested_by <> approved_by),
    CONSTRAINT ck_incident_hold_release_finding CHECK (finding IN ('REAL_APPROVAL_CONFIRMED', 'NO_EFFECT_CONFIRMED', 'DOUBLE_APPROVAL_CORRECTED')),
    CONSTRAINT ck_incident_hold_release_correction CHECK ((finding = 'DOUBLE_APPROVAL_CORRECTED') = (correction_ref IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
