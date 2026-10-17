-- 가상 인증기관 Simulator 저장소(AUTHN-001 §13.2, Simulator 전용 MySQL — PayCore DB와 분리). 카드번호·CVC 컬럼 없음(가상 카드 참조만).
-- state는 기관이 echo해야 하므로 원문을 보관한다(TEST 전용 대역 값 — PayCore 쪽은 digest만 저장). 결과는 한 번 확정되고 바뀌지 않는다.
CREATE TABLE authentication_transaction (
    transaction_id     CHAR(26)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id          VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state              CHAR(43)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_id         VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_ref       VARCHAR(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment        VARCHAR(4)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor       BIGINT        NOT NULL,
    currency           CHAR(3)       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    plan_kind          VARCHAR(12)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months INT           NULL,
    card_company       VARCHAR(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    return_url         VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status             VARCHAR(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    card_ref           VARCHAR(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    result_ref         CHAR(26)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    evidence_ref       VARCHAR(128)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at         DATETIME(3)   NOT NULL,
    expires_at         DATETIME(3)   NOT NULL,
    completed_at       DATETIME(3)   NULL,
    valid_until        DATETIME(3)   NULL,
    version            BIGINT        NOT NULL,
    CONSTRAINT pk_authentication_transaction PRIMARY KEY (transaction_id),
    CONSTRAINT uk_authentication_transaction_client_state UNIQUE (client_id, state),
    CONSTRAINT uk_authentication_transaction_result_ref UNIQUE (result_ref),
    CONSTRAINT ck_authentication_transaction_status CHECK (status IN ('REGISTERED', 'AUTHENTICATED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_authentication_transaction_completed CHECK (
        (status = 'REGISTERED') = (result_ref IS NULL) AND (result_ref IS NULL) = (completed_at IS NULL) AND (result_ref IS NULL) = (valid_until IS NULL)),
    CONSTRAINT ck_authentication_transaction_evidence CHECK ((status = 'AUTHENTICATED') = (evidence_ref IS NOT NULL)),
    CONSTRAINT ck_authentication_transaction_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_authentication_transaction_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_authentication_transaction_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 결과 조회 기록(append-only, 시험 관찰·"한 번만" 시나리오 판정).
CREATE TABLE lookup_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    result_ref  CHAR(26)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    looked_up_at DATETIME(3) NOT NULL,
    behaviour   VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_lookup_log PRIMARY KEY (id),
    CONSTRAINT ck_lookup_log_behaviour CHECK (behaviour IN ('NORMAL', 'DELAY', 'SERVER_ERROR')),
    INDEX ix_lookup_log_result_ref (result_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
