-- ACQUIRING-001 배치1(명세 docs/repository/ACQUIRING-001-ACQUIRING.md §4·§11): 매입 lane·봉인 파일·바이트·레코드(membership)·상계·제외.
-- 사용자 결정: D-ACQ-002(승인 확정 건만, 전송 전 전액 취소 제외·부분 취소 상계), D-ACQ-003(매입사별 하루 1파일, 마감 = 버전 설정), D-ACQ-008(Demo merchant_ref = MID,
-- LIVE 매입 차단), D-ACQ-012(상계 경계 = membership 확정 = 봉인 TX 커밋). 전송·ACK·레코드 결과·재매입 표는 배치2 migration이 더한다.
-- 공통 규칙(V2와 같다): 키·식별 열 utf8mb4_0900_bin, 시각 UTC DATETIME(3), 금액 BIGINT minor unit(파일 v1은 KRW만). PAYMENT 표를 참조하지 않는다.
-- 봉인 뒤 바뀌지 않는 표(file_content·file_record·netting·exclusion)는 append-only다(runtime 권한 SELECT·INSERT — V6). acquisition_file은 배치1에서 INSERT만
-- (전송·수신·결과 상태 열 UPDATE 권한은 그 전이를 구현하는 배치2 migration이 연다). acquisition_lane만 마지막 봉인 업무일·version을 바꾼다.

-- lane((매입사, 환경)당 한 행): 같은 lane 봉인을 FOR UPDATE로 직렬화하고 업무일 순서를 기억한다(Implementer 해석 I-3 — D 봉인 전 D+1 거부).
CREATE TABLE acquisition_lane (
    acquirer_code             VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment               VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    last_sealed_business_date DATE        NULL,
    created_at                DATETIME(3) NOT NULL,
    updated_at                DATETIME(3) NOT NULL,
    version                   BIGINT      NOT NULL,
    CONSTRAINT pk_acquisition_lane PRIMARY KEY (acquirer_code, environment),
    CONSTRAINT ck_acquisition_lane_acquirer CHECK (REGEXP_LIKE(acquirer_code, '^[A-Z0-9]{1,32}$', 'c')),
    CONSTRAINT ck_acquisition_lane_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_acquisition_lane_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 봉인 파일(Aggregate). (매입사, 환경, 업무일, 버전) UNIQUE — 정기 파일은 버전 1(D-ACQ-003), 재매입은 2 이상 + original_file_id(D-ACQ-004·011, 배치2).
-- 합계 열은 트레일러와 같다(net = sale − cancel). content_sha256 = 봉인 hash(파일 전체 바이트 SHA-256 — ACK에서 되돌려 받아 대조, 배치2).
-- 전송·수신·결과는 서로 다른 축(INV-12): 전송 완료(TRANSMITTED)만 "매입 요청" 근거, RECEIVED(ACK)는 매입 확정 아님.
CREATE TABLE acquisition_file (
    file_id               VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquirer_code         VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment           VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_date         DATE        NOT NULL,
    file_version          INT         NOT NULL,
    original_file_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    sender_id             VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cutoff_policy_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    cutoff_at             DATETIME(3) NOT NULL,
    sealed_at             DATETIME(3) NOT NULL,
    record_count          INT         NOT NULL,
    sale_count            INT         NOT NULL,
    sale_amount_minor     BIGINT      NOT NULL,
    cancel_count          INT         NOT NULL,
    cancel_amount_minor   BIGINT      NOT NULL,
    net_amount_minor      BIGINT      NOT NULL,
    content_sha256        CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_bytes         INT         NOT NULL,
    transmission_state    VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    receipt_state         VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_state          VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at            DATETIME(3) NOT NULL,
    updated_at            DATETIME(3) NOT NULL,
    version               BIGINT      NOT NULL,
    CONSTRAINT pk_acquisition_file PRIMARY KEY (file_id),
    CONSTRAINT uk_acquisition_file_lane_date_version UNIQUE (acquirer_code, environment, business_date, file_version),
    CONSTRAINT fk_acquisition_file_lane FOREIGN KEY (acquirer_code, environment) REFERENCES acquisition_lane (acquirer_code, environment),
    CONSTRAINT fk_acquisition_file_original FOREIGN KEY (original_file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_file_version CHECK (file_version BETWEEN 1 AND 999 AND ((file_version = 1) = (original_file_id IS NULL))),
    CONSTRAINT ck_acquisition_file_counts CHECK (
        record_count >= 0 AND sale_count >= 0 AND cancel_count >= 0 AND record_count = sale_count + cancel_count
        AND sale_amount_minor >= 0 AND cancel_amount_minor >= 0 AND net_amount_minor = sale_amount_minor - cancel_amount_minor),
    CONSTRAINT ck_acquisition_file_sha256 CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_acquisition_file_bytes CHECK (content_bytes >= 1),
    CONSTRAINT ck_acquisition_file_transmission CHECK (transmission_state IN ('SEALED', 'TRANSMITTING', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN')),
    CONSTRAINT ck_acquisition_file_receipt CHECK (receipt_state IN ('NONE', 'RECEIVED', 'FILE_REJECTED', 'RECEIPT_CONFLICT')),
    CONSTRAINT ck_acquisition_file_result CHECK (result_state IN ('AWAITING', 'COMPLETE', 'DEADLINE_PASSED')),
    CONSTRAINT ck_acquisition_file_version_counter CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 봉인 바이트(파일 행과 나눈 표 — 상태 변경 때 BLOB를 읽지 않게. Hibernate @Lob LAZY는 bytecode enhancement 없이 지켜지지 않는다). 재시작은 같은 바이트로 다시 보낸다
-- (같은 파일 ID 다른 내용 금지 — 기준선 §5.4-3, Implementer 해석 I-11). Demo 크기 전제(MEDIUMBLOB ≤ 16 MiB) — 대용량은 객체 저장소 + hash(LIVE Gate).
CREATE TABLE acquisition_file_content (
    file_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content        MEDIUMBLOB  NOT NULL,
    content_sha256 CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_acquisition_file_content PRIMARY KEY (file_id),
    CONSTRAINT fk_acquisition_file_content_file FOREIGN KEY (file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_file_content_sha256 CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 파일 레코드 = membership(파일 v1 D 줄과 같은 값). UNIQUE(transaction_id, record_type, generation): 같은 거래의 첫 매입(generation 0)은 어느 파일에도 한 번뿐이다
-- (이중 매입 DB 방어 — 봉인 판정은 lane·결제별 행 잠금 아래 미판정만 고른다). 재매입은 generation ≥ 1 + reacquisition_of(배치2, 2인 승인 — D-ACQ-011).
CREATE TABLE acquisition_file_record (
    file_id                          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq                              INT         NOT NULL,
    record_type                      VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    transaction_id                   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    merchant_ref                     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_number                  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    provider_transaction_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_provider_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_transaction_date     DATE        NOT NULL,
    event_at                         DATETIME(3) NOT NULL,
    currency                         CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_amount_minor            BIGINT      NOT NULL,
    netted_cancel_amount_minor       BIGINT      NOT NULL,
    record_amount_minor              BIGINT      NOT NULL,
    plan_kind                        VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    installment_months               INT         NULL,
    card_type                        VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issuer_code                      VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    generation                       INT         NOT NULL,
    reacquisition_of                 VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    CONSTRAINT pk_acquisition_file_record PRIMARY KEY (file_id, seq),
    CONSTRAINT uk_acquisition_file_record_transaction UNIQUE (transaction_id, record_type, generation),
    CONSTRAINT fk_acquisition_file_record_file FOREIGN KEY (file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_file_record_seq CHECK (seq >= 1),
    CONSTRAINT ck_acquisition_file_record_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_acquisition_file_record_shape CHECK (
        (record_type = 'SALE' AND original_transaction_id IS NULL AND original_provider_transaction_id IS NULL
            AND approved_amount_minor >= 1 AND netted_cancel_amount_minor >= 0 AND record_amount_minor >= 1
            AND record_amount_minor = approved_amount_minor - netted_cancel_amount_minor)
        OR (record_type = 'CANCEL' AND original_transaction_id IS NOT NULL AND original_provider_transaction_id IS NOT NULL
            AND netted_cancel_amount_minor = 0 AND record_amount_minor >= 1 AND record_amount_minor <= approved_amount_minor)),
    CONSTRAINT ck_acquisition_file_record_plan CHECK (
        (plan_kind = 'LUMP_SUM' AND installment_months IS NULL) OR (plan_kind = 'INSTALLMENT' AND installment_months BETWEEN 2 AND 99)),
    CONSTRAINT ck_acquisition_file_record_generation CHECK (generation >= 0 AND ((generation = 0) = (reacquisition_of IS NULL))),
    INDEX ix_acquisition_file_record_original (original_transaction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 상계(D-ACQ-002·D-ACQ-012): membership 확정 전에 받은 취소를 원승인 매입에서 뺐다. 취소 하나는 한 번만 상계(PK). 레코드 상계면 (file_id, seq), 전액 취소 제외면 둘 다 NULL.
-- 이 표에 없는 취소 = membership 확정 뒤 취소 → 별도 CANCEL 레코드 판정(D-ACQ-009 — 원 SALE ACCEPTED일 때만, 배치2).
CREATE TABLE acquisition_netting (
    cancellation_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    amount_minor                BIGINT      NOT NULL,
    currency                    CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id                     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    seq                         INT         NULL,
    decided_at                  DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_netting PRIMARY KEY (cancellation_transaction_id),
    CONSTRAINT fk_acquisition_netting_record FOREIGN KEY (file_id, seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT ck_acquisition_netting_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_acquisition_netting_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_acquisition_netting_target CHECK ((file_id IS NULL) = (seq IS NULL)),
    INDEX ix_acquisition_netting_original (original_transaction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 제외(D-ACQ-002 "전송 전 전액 취소는 매입 제외"): 승인 하나에 한 행(PK). 상계한 취소는 acquisition_netting(file_id NULL)에 있다.
CREATE TABLE acquisition_exclusion (
    transaction_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    acquirer_code         VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment           VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason                VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_amount_minor BIGINT      NOT NULL,
    netted_amount_minor   BIGINT      NOT NULL,
    currency              CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_date         DATE        NOT NULL,
    decided_at            DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_exclusion PRIMARY KEY (transaction_id),
    CONSTRAINT fk_acquisition_exclusion_lane FOREIGN KEY (acquirer_code, environment) REFERENCES acquisition_lane (acquirer_code, environment),
    CONSTRAINT ck_acquisition_exclusion_reason CHECK (reason IN ('FULLY_CANCELLED_BEFORE_SUBMISSION')),
    CONSTRAINT ck_acquisition_exclusion_amount CHECK (approved_amount_minor >= 1 AND netted_amount_minor = approved_amount_minor),
    CONSTRAINT ck_acquisition_exclusion_currency CHECK (currency = 'KRW')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
