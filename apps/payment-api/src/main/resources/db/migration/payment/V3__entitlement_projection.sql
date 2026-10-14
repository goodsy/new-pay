-- PAYMENT 권리 Snapshot 투영·Inbox (MASTER-SNAPSHOT-001). 명세: docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md §5.
-- PAYMENT는 이 투영을 원본으로 취급하지 않는다(MASTER가 원본, DOMAIN-DEFINITION §2.1). MASTER 표를 참조하지 않는다(cross-DB FK·직접 SQL 없음).
-- 공통 규칙은 V1과 같다(utf8mb4_0900_bin, UTC DATETIME(3)). 적용된 V1·V2는 고치지 않는다. runtime 권한은 db/runtime-privilege/payment/V4.

-- Inbox(append-only 수신 증거·재처리 원문, A02 Inbox UNIQUE). 업무 반영과 같은 로컬 TX로 기록한다. payload = 받은 봉투(JSON 값 — MySQL이
-- 정규화해 저장하므로 원문 바이트 증거는 payload_sha256), head_revision_after = 처리 뒤 head revision(ACK appliedRevision).
CREATE TABLE inbox_event (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    consumer            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_id            CHAR(36)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    event_type          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version      INT          NOT NULL,
    aggregate_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    aggregate_version   BIGINT       NOT NULL,
    outcome             VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    head_revision_after BIGINT       NOT NULL,
    payload             JSON         NOT NULL,
    payload_sha256      CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    received_at         DATETIME(3)  NOT NULL,
    CONSTRAINT pk_inbox_event PRIMARY KEY (id),
    CONSTRAINT uk_inbox_event_consumer_event UNIQUE (consumer, event_id),
    CONSTRAINT ck_inbox_event_outcome CHECK (outcome IN ('APPLIED', 'LEASE_EXTENDED', 'STALE', 'INTEGRITY_CONFLICT', 'UNSUPPORTED')),
    CONSTRAINT ck_inbox_event_versions CHECK (schema_version >= 1 AND aggregate_version >= 1 AND head_revision_after >= 0),
    CONSTRAINT ck_inbox_event_sha256 CHECK (REGEXP_LIKE(payload_sha256, '^[0-9a-f]{64}$', 'c')),
    INDEX ix_inbox_event_aggregate (aggregate_id, aggregate_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Merchant별 현재 적용 상태. revision 0 = "아직 없음"(Inbox가 IODKU로 만든 자리 — 최초 경쟁을 PK 대기로 직렬화). CreatePayment는 FOR SHARE,
-- Inbox는 FOR UPDATE로 잡는다(명세 §3.4-4). 막힌 head(VERSION_UNSUPPORTED/INTEGRITY_CONFLICT)로는 판정하지 않는다(fail-closed).
CREATE TABLE entitlement_head (
    merchant_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision         BIGINT      NOT NULL,
    content_hash     VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    schema_version   INT         NULL,
    issued_at        DATETIME(3) NULL,
    lease_seq        BIGINT      NULL,
    lease_until      DATETIME(3) NULL,
    applied_at       DATETIME(3) NULL,
    source_event_id  CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    blocked_reason   VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    blocked_revision BIGINT      NULL,
    version          BIGINT      NOT NULL,
    CONSTRAINT pk_entitlement_head PRIMARY KEY (merchant_id),
    CONSTRAINT ck_entitlement_head_revision CHECK (revision >= 0),
    CONSTRAINT ck_entitlement_head_filled CHECK (
        (revision = 0 AND content_hash IS NULL AND schema_version IS NULL AND issued_at IS NULL AND lease_seq IS NULL AND lease_until IS NULL)
        OR (revision >= 1 AND content_hash IS NOT NULL AND schema_version IS NOT NULL AND issued_at IS NOT NULL AND lease_seq IS NOT NULL
            AND lease_until IS NOT NULL AND lease_until > issued_at)),
    CONSTRAINT ck_entitlement_head_block CHECK (
        (blocked_reason IS NULL AND blocked_revision IS NULL)
        OR (blocked_reason IN ('VERSION_UNSUPPORTED', 'INTEGRITY_CONFLICT') AND blocked_revision IS NOT NULL)),
    CONSTRAINT ck_entitlement_head_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- revision별 권리 내용(append-only). Payment.eligibility_snapshot_version(es1:<merchant>:<revision>)이 가리키는 판정 근거를 그대로 남긴다
-- (Auditability). payload = 정규 내용 JSON, 읽을 때 content_hash를 다시 계산해 확인한다. 삭제 Job은 보존 정책 결정 뒤(명세 §13-7).
CREATE TABLE entitlement_snapshot (
    merchant_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision       BIGINT      NOT NULL,
    schema_version INT         NOT NULL,
    content_hash   VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payload        JSON        NOT NULL,
    CONSTRAINT pk_entitlement_snapshot PRIMARY KEY (merchant_id, revision),
    CONSTRAINT fk_entitlement_snapshot_head FOREIGN KEY (merchant_id) REFERENCES entitlement_head (merchant_id),
    CONSTRAINT ck_entitlement_snapshot_revision CHECK (revision >= 1),
    CONSTRAINT ck_entitlement_snapshot_hash CHECK (REGEXP_LIKE(content_hash, '^ec1:[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- MID 소속(불변 — 구조 §8). 판정은 이 표로 MID의 Merchant를 찾는다(인증된 MID가 아직 전파되지 않았으면 SNAPSHOT_MISSING). head 잠금 아래 INSERT.
CREATE TABLE entitlement_mid_owner (
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_at         DATETIME(3) NOT NULL,
    CONSTRAINT pk_entitlement_mid_owner PRIMARY KEY (merchant_account_id),
    CONSTRAINT fk_entitlement_mid_owner_head FOREIGN KEY (merchant_id) REFERENCES entitlement_head (merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
