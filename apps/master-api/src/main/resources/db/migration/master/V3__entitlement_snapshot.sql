-- MASTER 권리 Snapshot·개통 상태·Site 적용 확인·통화 활성화·Site 심사 (MASTER-SNAPSHOT-001).
-- 명세: docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md §4.1–§4.5. 근거: D-MD-016~019, D-MS-001~004.
-- 적용된 V1·V2는 고치지 않는다(새 버전만). 공통 규칙은 V1과 같다(utf8mb4_0900_bin, UTC DATETIME(3), 서버 생성 ID 형식 CHECK,
-- 같은 Merchant 관계는 복합 FK 이중 방어, cross-DB FK 없음). runtime 표 단위 권한은 db/runtime-privilege/master/V4.

-- ① 개통 상태(D-MD-017, D-MS-003). 기존 행은 "등록됨, 개통 아님" = UNDER_REVIEW(명세 §4.1 해석).
--    잠정 UNIQUE(MID, 수단)(MASTER-DOMAIN-001 m-5)를 "TERMINATED가 아닌 행 하나" 생성 컬럼 UNIQUE로 바꾼다(재개통 = 새 행).
--    FK(merchant_account_id)가 기존 UNIQUE 인덱스를 쓰고 있으므로 먼저 단독 인덱스를 만든 뒤 UNIQUE를 지운다.
ALTER TABLE payment_method_activation
    ADD COLUMN status            VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN status_changed_at DATETIME(3) NULL,
    ADD COLUMN version           BIGINT NULL;

UPDATE payment_method_activation SET status = 'UNDER_REVIEW', status_changed_at = registered_at, version = 0;

ALTER TABLE payment_method_activation
    MODIFY COLUMN status            VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN status_changed_at DATETIME(3) NOT NULL,
    MODIFY COLUMN version           BIGINT NOT NULL;

ALTER TABLE payment_method_activation
    ADD COLUMN open_method_key VARCHAR(84) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status <> 'TERMINATED', CONCAT(merchant_account_id, '|', payment_method), NULL)) STORED,
    ADD INDEX ix_payment_method_activation_account (merchant_account_id),
    ADD CONSTRAINT uk_payment_method_activation_open_method UNIQUE (open_method_key),
    ADD CONSTRAINT uk_payment_method_activation_account UNIQUE (activation_id, merchant_account_id, payment_method),
    ADD CONSTRAINT ck_payment_method_activation_status CHECK (status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    ADD CONSTRAINT ck_payment_method_activation_active_evidence CHECK (status <> 'ACTIVE' OR evidence_ref IS NOT NULL),
    ADD CONSTRAINT ck_payment_method_activation_changed CHECK (status_changed_at >= registered_at),
    ADD CONSTRAINT ck_payment_method_activation_version CHECK (version >= 0);

ALTER TABLE payment_method_activation DROP INDEX uk_payment_method_activation_account_method;

-- ② 개통 전이 이력(append-only). 등록은 from_status NULL → UNDER_REVIEW. 기존 행에는 등록 이력을 보충하지 않는다(MASTER-DOMAIN-001
--    등록 감사는 master_change_history에 있다 — 이 표는 이번 migration 이후의 전이만 담는다).
CREATE TABLE payment_method_activation_event (
    activation_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq               INT          NOT NULL,
    from_status       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    to_status         VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    actor_ref         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_payment_method_activation_event PRIMARY KEY (activation_id, seq),
    CONSTRAINT fk_payment_method_activation_event_activation FOREIGN KEY (activation_id) REFERENCES payment_method_activation (activation_id),
    CONSTRAINT ck_payment_method_activation_event_seq CHECK (seq >= 1),
    CONSTRAINT ck_payment_method_activation_event_to CHECK (to_status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT ck_payment_method_activation_event_from CHECK (
        from_status IS NULL OR from_status IN ('UNDER_REVIEW', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_payment_method_activation_event_active_evidence CHECK (to_status <> 'ACTIVE' OR evidence_ref IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ③ Site 적용 확인(D-MD-016, append-only) — MID·Site 같은 Merchant(복합 FK 두 개), Activation은 같은 MID·같은 수단(복합 FK).
--    "같은 (Activation, Site)의 유효 확인 하나"는 철회가 별도 표라 DB로 표현하지 않고 merchant 잠금 아래 domain이 판정한다.
CREATE TABLE site_activation_confirmation (
    confirmation_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_site_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    payment_method      VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    activation_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    confirmed_at        DATETIME(3)  NOT NULL,
    confirmed_by        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    -- 같은 (Activation, Site) 안의 확인 순번. domain은 앞선 확인이 모두 철회됐을 때만 다음 순번을 만든다(유효 확인 하나 — review M-1).
    -- 같은 순번 동시 기록(경합·결함)은 UNIQUE가 막는다(DB 이중 방어).
    confirmation_seq    INT          NOT NULL,
    CONSTRAINT pk_site_activation_confirmation PRIMARY KEY (confirmation_id),
    CONSTRAINT uk_site_activation_confirmation_seq UNIQUE (activation_id, merchant_site_id, confirmation_seq),
    CONSTRAINT ck_site_activation_confirmation_seq CHECK (confirmation_seq >= 1),
    CONSTRAINT fk_site_activation_confirmation_account FOREIGN KEY (merchant_account_id, merchant_id)
        REFERENCES merchant_account (merchant_account_id, merchant_id),
    CONSTRAINT fk_site_activation_confirmation_site FOREIGN KEY (merchant_site_id, merchant_id)
        REFERENCES merchant_site (merchant_site_id, merchant_id),
    CONSTRAINT fk_site_activation_confirmation_activation FOREIGN KEY (activation_id, merchant_account_id, payment_method)
        REFERENCES payment_method_activation (activation_id, merchant_account_id, payment_method),
    CONSTRAINT ck_site_activation_confirmation_id CHECK (REGEXP_LIKE(confirmation_id, '^sac_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_site_activation_confirmation_method CHECK (payment_method IN ('CARD')),
    INDEX ix_site_activation_confirmation_activation (activation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Site 적용 확인 철회(D-MS-003, append-only — 확인당 하나: PK). 재확인은 새 확인 행.
CREATE TABLE site_activation_confirmation_revocation (
    confirmation_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revoked_at        DATETIME(3)  NOT NULL,
    revoked_by        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_site_activation_confirmation_revocation PRIMARY KEY (confirmation_id),
    CONSTRAINT fk_site_activation_confirmation_revocation FOREIGN KEY (confirmation_id)
        REFERENCES site_activation_confirmation (confirmation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ④ 통화 단위 기준정보(ISO 4217 표준 사실 — 허용 통화가 아니다. 어떤 MID도 이 migration으로 활성화되지 않는다, D-CP-002·D-MD-018).
CREATE TABLE currency_unit (
    currency_code CHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    minor_unit    INT     NOT NULL,
    CONSTRAINT pk_currency_unit PRIMARY KEY (currency_code),
    CONSTRAINT ck_currency_unit_code CHECK (REGEXP_LIKE(currency_code, '^[A-Z]{3}$', 'c')),
    CONSTRAINT ck_currency_unit_minor CHECK (minor_unit BETWEEN 0 AND 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO currency_unit (currency_code, minor_unit) VALUES ('KRW', 0), ('USD', 2);

-- 통화 활성화 기록(D-MD-018). 기간 [from, to), 소급 시작 금지(from ≥ 기록 시각). 열린(종료 미정) 기록은 같은 (MID, 통화) 하나(생성 컬럼 UNIQUE),
-- 닫힌 기간의 중첩은 merchant 잠금 아래 domain 검사(V1과 같은 이유 — MySQL에 기간 배제 제약이 없다). 종료는 effective_to·ended_* 갱신(UPDATE).
CREATE TABLE currency_enablement (
    enablement_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    currency_code       CHAR(3)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    effective_from      DATETIME(3)  NOT NULL,
    effective_to        DATETIME(3)  NULL,
    evidence_ref        VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    created_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_at            DATETIME(3)  NULL,
    ended_by            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    version             BIGINT       NOT NULL,
    open_currency_key   VARCHAR(68)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(merchant_account_id, '|', currency_code), NULL)) STORED,
    CONSTRAINT pk_currency_enablement PRIMARY KEY (enablement_id),
    CONSTRAINT uk_currency_enablement_open UNIQUE (open_currency_key),
    CONSTRAINT fk_currency_enablement_account FOREIGN KEY (merchant_account_id) REFERENCES merchant_account (merchant_account_id),
    CONSTRAINT fk_currency_enablement_unit FOREIGN KEY (currency_code) REFERENCES currency_unit (currency_code),
    CONSTRAINT ck_currency_enablement_id CHECK (REGEXP_LIKE(enablement_id, '^cur_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_currency_enablement_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_currency_enablement_not_retroactive CHECK (effective_from >= created_at),
    CONSTRAINT ck_currency_enablement_end_actor CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CONSTRAINT ck_currency_enablement_end_not_retroactive CHECK (ended_at IS NULL OR effective_to >= ended_at),
    CONSTRAINT ck_currency_enablement_version CHECK (version >= 0),
    INDEX ix_currency_enablement_account (merchant_account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑤ Site PG 심사 결정(D-MS-002 (a), append-only). 현재 상태 = seq 최대 결정, 없으면 PENDING. 결정 값에 PENDING은 없다(초기 상태).
CREATE TABLE merchant_site_review_decision (
    merchant_site_id  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq               INT          NOT NULL,
    merchant_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decision          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    decided_at        DATETIME(3)  NOT NULL,
    decided_by        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_merchant_site_review_decision PRIMARY KEY (merchant_site_id, seq),
    CONSTRAINT fk_merchant_site_review_decision_site FOREIGN KEY (merchant_site_id, merchant_id)
        REFERENCES merchant_site (merchant_site_id, merchant_id),
    CONSTRAINT ck_merchant_site_review_decision_seq CHECK (seq >= 1),
    CONSTRAINT ck_merchant_site_review_decision_value CHECK (decision IN ('APPROVED', 'REJECTED', 'SUSPENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑥ 권리 Snapshot 발행 기록(append-only). 변경 발행은 lease_seq 0, 같은 revision의 lease 갱신은 lease_seq+1. outbox_event_id = 같은 TX의
--    Outbox 이벤트(ENTITLEMENT_SNAPSHOT). 효력 확인 조회(§3.4-5)가 "r 미만 revision lease 최댓값"을 이 표에서 계산한다.
CREATE TABLE entitlement_snapshot_issue (
    merchant_id     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    revision        BIGINT      NOT NULL,
    lease_seq       BIGINT      NOT NULL,
    content_hash    VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issued_at       DATETIME(3) NOT NULL,
    lease_until     DATETIME(3) NOT NULL,
    priority        VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outbox_event_id CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_entitlement_snapshot_issue PRIMARY KEY (merchant_id, revision, lease_seq),
    CONSTRAINT uk_entitlement_snapshot_issue_event UNIQUE (outbox_event_id),
    CONSTRAINT fk_entitlement_snapshot_issue_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
    CONSTRAINT ck_entitlement_snapshot_issue_revision CHECK (revision >= 1),
    CONSTRAINT ck_entitlement_snapshot_issue_lease_seq CHECK (lease_seq >= 0),
    CONSTRAINT ck_entitlement_snapshot_issue_lease CHECK (lease_until > issued_at),
    CONSTRAINT ck_entitlement_snapshot_issue_priority CHECK (priority IN ('HIGH', 'LOW')),
    CONSTRAINT ck_entitlement_snapshot_issue_hash CHECK (REGEXP_LIKE(content_hash, '^ec1:[0-9a-f]{64}$', 'c')),
    INDEX ix_entitlement_snapshot_issue_lease (lease_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑦ PAYMENT Inbox 내구 커밋 확인(ACK, append-only). 같은 이벤트의 중복 ACK(응답 유실 후 재전송)는 무해하다(UNIQUE 없음).
CREATE TABLE entitlement_snapshot_ack (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    outbox_event_id  CHAR(36)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    consumer         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outcome          VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    applied_revision BIGINT      NOT NULL,
    acked_at         DATETIME(3) NOT NULL,
    -- DUPLICATE ACK이면 PAYMENT가 처음 처리 때 저장한 outcome(review m-3). 효력 확인이 "처음이 UNSUPPORTED(fail-closed)"를 놓치지 않게.
    original_outcome VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    CONSTRAINT pk_entitlement_snapshot_ack PRIMARY KEY (id),
    CONSTRAINT ck_entitlement_snapshot_ack_original CHECK (
        original_outcome IS NULL OR (outcome = 'DUPLICATE' AND original_outcome IN ('APPLIED', 'LEASE_EXTENDED', 'STALE', 'INTEGRITY_CONFLICT', 'UNSUPPORTED'))),
    CONSTRAINT fk_entitlement_snapshot_ack_issue FOREIGN KEY (outbox_event_id) REFERENCES entitlement_snapshot_issue (outbox_event_id),
    CONSTRAINT ck_entitlement_snapshot_ack_outcome CHECK (
        outcome IN ('APPLIED', 'LEASE_EXTENDED', 'STALE', 'DUPLICATE', 'INTEGRITY_CONFLICT', 'UNSUPPORTED')),
    CONSTRAINT ck_entitlement_snapshot_ack_revision CHECK (applied_revision >= 0),
    INDEX ix_entitlement_snapshot_ack_event (outbox_event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- ⑧ Outbox Relay 컬럼(명세 §4.7). 기존 행: priority 0, next_attempt_at = created_at, attempt 0. QUARANTINED(계약 오류 격리 — 완료 아님) 추가.
ALTER TABLE outbox_event
    ADD COLUMN priority          INT         NOT NULL DEFAULT 0,
    ADD COLUMN claim_owner       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN claim_until       DATETIME(3) NULL,
    ADD COLUMN attempt_count     INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at   DATETIME(3) NULL,
    ADD COLUMN last_failure_code VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;

UPDATE outbox_event SET next_attempt_at = created_at;

ALTER TABLE outbox_event MODIFY COLUMN next_attempt_at DATETIME(3) NOT NULL;

ALTER TABLE outbox_event DROP CHECK ck_outbox_event_publish_status;

ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_publish_status CHECK (
        (publish_status = 'PENDING' AND published_at IS NULL)
        OR (publish_status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (publish_status = 'QUARANTINED' AND published_at IS NULL)),
    ADD CONSTRAINT ck_outbox_event_claim CHECK ((claim_owner IS NULL) = (claim_until IS NULL)),
    ADD CONSTRAINT ck_outbox_event_attempt CHECK (attempt_count >= 0),
    ADD INDEX ix_outbox_event_relay (event_category, publish_status, priority, id);
