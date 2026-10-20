-- CANCEL-001 명세 §8.1 V15: 취소 새 표만(기존 표 ALTER는 V17 — MySQL DDL은 TX가 아니라 섞지 않는다, AU-19). 카드번호·CVC 열 없음.
-- 업무 규칙: CANCEL-CONTRACT-001(D-CANCEL-001~013), D-CANCEL1-001~004. 시각은 UTC DATETIME(3), 금액은 통화 minor unit 정수.
-- MySQL CHECK는 NULL이면 통과하므로(rev 0.3 m-2) 짝 조건은 IS NULL / IS NOT NULL로 명시한다. CHECK 대상 열의 FK에는 참조 동작(ON DELETE/UPDATE)이 없다.

-- 취소 operation(Aggregate Root, Payment 1:N — CC-01). 업무 효과 키: 가맹점 (payment_id, merchant_cancel_id) UNIQUE(D-CANCEL-002), 운영자 approval_id UNIQUE(CC-12) — 영구.
-- Payment당 진행 중(OPEN·UNKNOWN) 취소 하나(D-CANCEL-003): 생성 열 in_flight_payment_id UNIQUE. 운영자 행은 요청자 ≠ 승인자(D-CANCEL-005, D-ADMIN-007 — DB 이중 방어).
CREATE TABLE payment_cancellation (
    cancellation_id                  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id                       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id                 VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment                      VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id              VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_order_id                VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                     BIGINT       NOT NULL,
    currency                         CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    scope                            VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status                           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    failure_evidence                 VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    -- 사건 수(D-CANCEL1-002 — rev 0.3 H-1로 사건 동일성 규칙 보류 중, 열 자체는 사건 순번의 영속 자리).
    incident_count                   INT          NOT NULL,
    initiator_kind                   VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_cancel_id               VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approval_id                      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    requested_by                     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approved_by                      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    operator_reason                  VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    purpose_kind                     VARCHAR(40)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    purpose_ref                      VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    approved_target_fingerprint      VARCHAR(80)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    original_provider_transaction_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    policy_assignment_version        VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    entitlement_version              VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancel_capability_version        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    opened_at                        DATETIME(3)  NOT NULL,
    version                          BIGINT       NOT NULL,
    in_flight_payment_id             VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status IN ('OPEN', 'UNKNOWN'), payment_id, NULL)) STORED,
    CONSTRAINT pk_payment_cancellation PRIMARY KEY (cancellation_id),
    CONSTRAINT uk_payment_cancellation_merchant_key UNIQUE (payment_id, merchant_cancel_id),
    CONSTRAINT uk_payment_cancellation_approval UNIQUE (approval_id),
    CONSTRAINT uk_payment_cancellation_in_flight UNIQUE (in_flight_payment_id),
    CONSTRAINT fk_payment_cancellation_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_payment_cancellation_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT fk_payment_cancellation_original FOREIGN KEY (original_transaction_id) REFERENCES payment_transaction (transaction_id),
    CONSTRAINT ck_payment_cancellation_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_payment_cancellation_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_payment_cancellation_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_payment_cancellation_scope CHECK (scope IN ('FULL', 'PARTIAL')),
    CONSTRAINT ck_payment_cancellation_status CHECK (status IN ('OPEN', 'UNKNOWN', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_payment_cancellation_failure CHECK (
        (status = 'FAILED' AND failure_evidence IS NOT NULL
            AND failure_evidence IN ('INSTITUTION_CONFIRMED_DECLINE', 'INSTITUTION_CONFIRMED_NO_EFFECT', 'PRE_TRANSMISSION_FAILURE_CONFIRMED'))
        OR (status <> 'FAILED' AND failure_evidence IS NULL)),
    -- 사건은 진행 중 취소를 UNKNOWN으로 만든다 — OPEN에는 사건이 없다(rev 0.3 n-4, domain reconstitute와 같은 규칙).
    CONSTRAINT ck_payment_cancellation_incident CHECK (incident_count >= 0 AND (status <> 'OPEN' OR incident_count = 0)),
    CONSTRAINT ck_payment_cancellation_initiator CHECK (
        (initiator_kind = 'MERCHANT' AND merchant_cancel_id IS NOT NULL AND approval_id IS NULL AND requested_by IS NULL AND approved_by IS NULL
            AND operator_reason IS NULL AND purpose_kind IS NULL AND purpose_ref IS NULL AND approved_target_fingerprint IS NULL)
        OR (initiator_kind = 'OPERATOR' AND merchant_cancel_id IS NULL AND approval_id IS NOT NULL AND requested_by IS NOT NULL AND approved_by IS NOT NULL
            AND operator_reason IS NOT NULL AND purpose_kind IS NOT NULL AND purpose_ref IS NOT NULL AND approved_target_fingerprint IS NOT NULL
            AND requested_by <> approved_by
            AND purpose_kind IN ('INCIDENT_OR_RECON_CORRECTION', 'MERCHANT_TERMINATED_OBLIGATION'))),
    CONSTRAINT ck_payment_cancellation_version CHECK (version >= 0),
    INDEX ix_payment_cancellation_payment (payment_id, opened_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기관 취소 실행 시도(취소당 하나 — CC-11). 기관 요청 namespace UNIQUE(operation = 'CANCEL'). Route 프로파일 버전은 전송한 Attempt에만(D-CANCEL1-004, rev 0.3 n-3).
CREATE TABLE cancel_attempt (
    attempt_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancellation_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment           VARCHAR(4)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    institution_account   VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation             VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state                 VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    abandon_cause         VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    unknown_cause         VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    route_profile_version VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    prepared_at           DATETIME(3)  NOT NULL,
    version               BIGINT       NOT NULL,
    CONSTRAINT pk_cancel_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT uk_cancel_attempt_cancellation UNIQUE (cancellation_id),
    CONSTRAINT uk_cancel_attempt_namespace UNIQUE (institution, environment, institution_account, operation, request_id),
    CONSTRAINT fk_cancel_attempt_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    CONSTRAINT ck_cancel_attempt_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_cancel_attempt_operation CHECK (operation = 'CANCEL'),
    CONSTRAINT ck_cancel_attempt_state CHECK (state IN ('PREPARED', 'DISPATCHING', 'ABANDONED_BEFORE_DISPATCH', 'OUTCOME_UNKNOWN', 'RESOLVED')),
    CONSTRAINT ck_cancel_attempt_abandon CHECK (
        (state = 'ABANDONED_BEFORE_DISPATCH' AND abandon_cause IS NOT NULL AND abandon_cause IN ('GATE_REFUSED', 'FENCED_BY_RECOVERY', 'CONNECT_FAILED'))
        OR (state <> 'ABANDONED_BEFORE_DISPATCH' AND abandon_cause IS NULL)),
    CONSTRAINT ck_cancel_attempt_unknown CHECK (
        (state = 'OUTCOME_UNKNOWN' AND unknown_cause IS NOT NULL)
        OR (state = 'RESOLVED')
        OR (state NOT IN ('OUTCOME_UNKNOWN', 'RESOLVED') AND unknown_cause IS NULL)),
    CONSTRAINT ck_cancel_attempt_route_profile_version CHECK (
        (state IN ('DISPATCHING', 'OUTCOME_UNKNOWN', 'RESOLVED') AND route_profile_version IS NOT NULL)
        OR (state = 'PREPARED' AND route_profile_version IS NULL)
        OR (state = 'ABANDONED_BEFORE_DISPATCH' AND abandon_cause IS NOT NULL AND abandon_cause = 'CONNECT_FAILED' AND route_profile_version IS NOT NULL)
        OR (state = 'ABANDONED_BEFORE_DISPATCH' AND abandon_cause IS NOT NULL AND abandon_cause IN ('GATE_REFUSED', 'FENCED_BY_RECOVERY') AND route_profile_version IS NULL)),
    CONSTRAINT ck_cancel_attempt_route_profile_version_format CHECK (
        route_profile_version IS NULL OR REGEXP_LIKE(route_profile_version, '^[!-~]{1,64}$', 'c')),
    CONSTRAINT ck_cancel_attempt_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기관 취소 결과 관찰(append-only — INSERT만, `@Immutable`). variant 0 = 첫 관찰, n ≥ 1 = 같은 키·다른 내용의 상충 증거. 확정 취소 조건은 APPROVED에만.
CREATE TABLE cancel_observation (
    id                               BIGINT       NOT NULL AUTO_INCREMENT,
    cancellation_id                  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_id                       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source                           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source_result_id                 VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    variant                          INT          NOT NULL,
    outcome                          VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cancelled_amount_minor           BIGINT       NULL,
    cancelled_currency               CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    original_provider_transaction_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    provider_transaction_id          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    cancel_approval_number           VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_transaction_date     DATE         NULL,
    institution_business_date        DATE         NULL,
    evidence_hash                    VARCHAR(80)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    observed_at                      DATETIME(3)  NOT NULL,
    CONSTRAINT pk_cancel_observation PRIMARY KEY (id),
    CONSTRAINT uk_cancel_observation_key UNIQUE (attempt_id, source, source_result_id, variant),
    CONSTRAINT fk_cancel_observation_attempt FOREIGN KEY (attempt_id) REFERENCES cancel_attempt (attempt_id),
    CONSTRAINT fk_cancel_observation_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    CONSTRAINT ck_cancel_observation_source CHECK (source IN ('RESPONSE', 'INQUIRY', 'NOTIFICATION', 'RECONCILIATION')),
    CONSTRAINT ck_cancel_observation_variant CHECK (variant >= 0),
    CONSTRAINT ck_cancel_observation_outcome CHECK (outcome IN ('APPROVED', 'DECLINED', 'NO_EFFECT_CONFIRMED', 'PENDING', 'UNKNOWN', 'NOT_FOUND')),
    CONSTRAINT ck_cancel_observation_terms CHECK (
        (outcome = 'APPROVED' AND cancelled_amount_minor IS NOT NULL AND cancelled_currency IS NOT NULL AND original_provider_transaction_id IS NOT NULL
            AND provider_transaction_id IS NOT NULL AND institution_transaction_date IS NOT NULL)
        OR (outcome <> 'APPROVED' AND cancelled_amount_minor IS NULL AND cancelled_currency IS NULL AND original_provider_transaction_id IS NULL
            AND provider_transaction_id IS NULL AND cancel_approval_number IS NULL AND institution_transaction_date IS NULL AND institution_business_date IS NULL)),
    CONSTRAINT ck_cancel_observation_amount CHECK (cancelled_amount_minor IS NULL OR cancelled_amount_minor >= 1),
    INDEX ix_cancel_observation_cancellation (cancellation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 반환 가능 금액 통제(Aggregate Root, Payment 1:1 — CC-02 지연 개설). 합계 열은 예약 행에서 계산해 쓴다(CN-17 — 복원 때 불일치면 거부).
-- 불변식 INV-C1(review H-1): completed + reserved ≤ authorized(FORCED 제외 — 늦은 취소 성공은 승인액을 넘어도 기록한다). revision = 업무 변경 수(운영자 승인 대상 버전,
-- JPA version과 별개 — CN-16).
CREATE TABLE return_balance (
    payment_id              VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorization_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    authorized_minor        BIGINT      NOT NULL,
    currency                CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    completed_minor         BIGINT      NOT NULL,
    reserved_minor          BIGINT      NOT NULL,
    forced_minor            BIGINT      NOT NULL,
    revision                BIGINT      NOT NULL,
    created_at              DATETIME(3) NOT NULL,
    updated_at              DATETIME(3) NOT NULL,
    version                 BIGINT      NOT NULL,
    CONSTRAINT pk_return_balance PRIMARY KEY (payment_id),
    CONSTRAINT uk_return_balance_authorization UNIQUE (authorization_id),
    CONSTRAINT fk_return_balance_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_return_balance_authorization FOREIGN KEY (authorization_id) REFERENCES payment_authorization (authorization_id),
    CONSTRAINT fk_return_balance_original FOREIGN KEY (original_transaction_id) REFERENCES payment_transaction (transaction_id),
    CONSTRAINT ck_return_balance_authorized CHECK (authorized_minor >= 1),
    CONSTRAINT ck_return_balance_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_return_balance_parts CHECK (completed_minor >= 0 AND reserved_minor >= 0 AND forced_minor >= 0),
    CONSTRAINT ck_return_balance_within_authorized CHECK (completed_minor + reserved_minor <= authorized_minor),
    CONSTRAINT ck_return_balance_revision CHECK (revision >= 0),
    CONSTRAINT ck_return_balance_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 운영자 보정 취소 명령의 거절 감사(D-CANCEL1-006, append-only — INSERT만): PAYMENT가 운영자 명령을 거절하면(요청자 = 승인자·대상 변경·사유/근거 없음·진행 중 취소 등) 그 판정과 같은
-- 로컬 TX에서 한 행. 개인정보 없음(운영자 내부 불변 ID만, 사유 문구 없음). 거절 한 번 = 행 하나(D-CANCEL1-008 — 같은 승인·같은 사유의 반복 거절도 각각 시각을 가진 새 행, 중복 제거 UNIQUE 없음).
-- 요청자 = 승인자도 기록 대상이라 둘의 같음을 막는 CHECK가 없다. Payment가 없는 명령의 거절도 남길 수 있게 payment_id에 FK를 두지 않는다.
-- (미배포·미커밋 상태에서 제자리 수정: rev 0.3의 UNIQUE(approval_id, payment_id, rejection_reason)를 D-CANCEL1-008로 제거 — 명세 §20 개발 볼륨 메모.)
CREATE TABLE operator_cancel_rejection (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    approval_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_by     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    rejection_reason VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    rejected_at      DATETIME(3) NOT NULL,
    CONSTRAINT pk_operator_cancel_rejection PRIMARY KEY (id),
    CONSTRAINT ck_operator_cancel_rejection_reason CHECK (REGEXP_LIKE(rejection_reason, '^[A-Z_]{1,48}$', 'c')),
    INDEX ix_operator_cancel_rejection_approval (approval_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 취소 하나의 반환 예약. 진행 중(RESERVED) 예약은 Payment당 하나(D-CANCEL-003): 생성 열 UNIQUE. 완료 순번은 CONSUMED에만(Payment 안 UNIQUE), 초과 표시는 FORCED에만.
CREATE TABLE return_reservation (
    cancellation_id     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq                 INT         NOT NULL,
    amount_minor        BIGINT      NOT NULL,
    state               VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    excess              VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    completion_sequence INT         NULL,
    updated_at          DATETIME(3) NOT NULL,
    version             BIGINT      NOT NULL,
    in_flight_payment_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(state = 'RESERVED', payment_id, NULL)) STORED,
    CONSTRAINT pk_return_reservation PRIMARY KEY (cancellation_id),
    CONSTRAINT uk_return_reservation_seq UNIQUE (payment_id, seq),
    CONSTRAINT uk_return_reservation_completion UNIQUE (payment_id, completion_sequence),
    CONSTRAINT uk_return_reservation_in_flight UNIQUE (in_flight_payment_id),
    CONSTRAINT fk_return_reservation_balance FOREIGN KEY (payment_id) REFERENCES return_balance (payment_id),
    CONSTRAINT fk_return_reservation_cancellation FOREIGN KEY (cancellation_id) REFERENCES payment_cancellation (cancellation_id),
    CONSTRAINT ck_return_reservation_seq CHECK (seq >= 0),
    CONSTRAINT ck_return_reservation_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_return_reservation_state CHECK (state IN ('RESERVED', 'CONSUMED', 'RELEASED', 'FORCED')),
    CONSTRAINT ck_return_reservation_excess CHECK (
        (state = 'FORCED' AND excess IS NOT NULL AND excess IN ('WITHIN', 'EXCEEDED'))
        OR (state <> 'FORCED' AND excess IS NULL)),
    CONSTRAINT ck_return_reservation_completion CHECK (
        (state = 'CONSUMED' AND completion_sequence IS NOT NULL AND completion_sequence >= 1)
        OR (state <> 'CONSUMED' AND completion_sequence IS NULL)),
    CONSTRAINT ck_return_reservation_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
