-- 가상 매입사 원장(ACQUIRING-001 2-E §7 — Simulator Demo 대역, 새 표만. VAN 표 색인은 V11, 권한은 V12 — MySQL DDL은 TX가 아니라 섞지 않는다).
-- rev 0.9(재리뷰 m-1): rev 0.7의 V13(기관 근거 열·UNIQUE·CHECK)을 이 파일에 합쳤다. 2-E는 미커밋이고 V10~V13이 적용된 볼륨은 없다(Demo Simulator 볼륨은 V9까지 —
-- 2-E 이전에 멈춤, 시험은 매번 새 컨테이너). 만약 V10 이상이 적용된 로컬 볼륨이 있으면 Flyway checksum 검증이 기동을 거부한다 → 그 Simulator 볼륨을 다시 만든다
-- (.env.example). 백필(V13 앞 UPDATE)은 택하지 않았다: 옛 판정 행에는 상계액을 되살릴 값(승인액)이 없어 정확히 채울 수 없다.
-- 기관 사실은 append-only다: 수신 파일·레코드 판정·게시 계획(결과 줄)·게시 기록은 INSERT만(runtime 권한 SELECT·INSERT). Worker 진행 표(acquirer_work_item)만
-- 상태 열 UPDATE를 연다(state·published_at·updated_at·version — 열 단위 권한). PG(FINANCIAL) rollback·재시작과 무관하게 남는다(Simulator 전용 MySQL).
-- MySQL CHECK는 NULL로 평가되면 통과하므로 모양 CHECK는 IS NULL / IS NOT NULL을 명시한다.

-- 수신 원장: (매입사, 파일 ID, 내용 hash)당 한 행. receipt_ordinal = 그 파일 ID의 수신 순번(= ACK 순번 nn). 첫 수신(1)이 RECEIVED 또는 FILE_REJECTED,
-- 같은 ID에 다른 내용이 올 때마다 RECEIPT_CONFLICT(2..99). 같은 ID·같은 내용은 새 행 없이 멱등(UNIQUE content가 동시 수신도 하나로 만든다).
CREATE TABLE acquirer_received_file (
    receipt_id       BIGINT      NOT NULL AUTO_INCREMENT,
    acquirer_code    VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    receipt_ordinal  INT         NOT NULL,
    content_sha256   CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_bytes    BIGINT      NOT NULL,
    record_count     INT         NULL,
    outcome          VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason_code      VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    received_at      DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquirer_received_file PRIMARY KEY (receipt_id),
    CONSTRAINT uk_acquirer_received_file_ordinal UNIQUE (acquirer_code, file_id, receipt_ordinal),
    CONSTRAINT uk_acquirer_received_file_content UNIQUE (acquirer_code, file_id, content_sha256),
    CONSTRAINT ck_acquirer_received_file_outcome CHECK (outcome IN ('RECEIVED', 'FILE_REJECTED', 'RECEIPT_CONFLICT')),
    CONSTRAINT ck_acquirer_received_file_ordinal CHECK (receipt_ordinal BETWEEN 1 AND 99 AND (outcome = 'RECEIPT_CONFLICT') = (receipt_ordinal > 1)),
    CONSTRAINT ck_acquirer_received_file_shape CHECK (
        (outcome = 'RECEIVED' AND reason_code IS NULL AND record_count IS NOT NULL AND record_count >= 0)
        OR (outcome = 'FILE_REJECTED' AND reason_code IS NOT NULL AND reason_code <> 'CONTENT_MISMATCH' AND record_count IS NULL)
        OR (outcome = 'RECEIPT_CONFLICT' AND reason_code = 'CONTENT_MISMATCH' AND record_count IS NULL)),
    CONSTRAINT ck_acquirer_received_file_content CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c') AND content_bytes >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 레코드 판정(기관 내부 사실): RECEIVED 수신의 레코드마다 한 행. scenario = 적용된 Demo 시나리오(없으면 NULL). 이중 매입 확정 방어(UNIQUE — NULL은 대상 아님):
--   accepted_evidence_entry_id = ACCEPTED일 때만 근거 VAN 원장 행(SALE = 승인 행, CANCEL = 취소 행) — 같은 기관 근거의 두 번째 ACCEPTED를 거래 ID가 달라도 막는다(리뷰 MJ-1).
--   accepted_transaction_id    = ACCEPTED일 때만 거래 ID — 같은 거래 ID의 두 번째 ACCEPTED(이중 방어).
-- authorization_entry_id = ACCEPTED 레코드가 속한 원승인 VAN 행(SALE = 자기 근거, CANCEL = 수락된 원 SALE의 근거) — 원승인 단위 취소 금액 상한(상계 + CANCEL)의 키.
-- netted_cancel_amount_minor = SALE의 상계액(CANCEL은 0).
CREATE TABLE acquirer_record_decision (
    receipt_id               BIGINT      NOT NULL,
    record_seq               INT         NOT NULL,
    acquirer_code            VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_type              VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    transaction_id           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    provider_transaction_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_amount_minor      BIGINT      NOT NULL,
    netted_cancel_amount_minor BIGINT    NOT NULL,
    decision                 VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason_code              VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    evidence_entry_id        CHAR(29)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    authorization_entry_id   CHAR(29)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    scenario                 VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    accepted_transaction_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    accepted_evidence_entry_id CHAR(29)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    decided_at               DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquirer_record_decision PRIMARY KEY (receipt_id, record_seq),
    CONSTRAINT fk_acquirer_record_decision_receipt FOREIGN KEY (receipt_id) REFERENCES acquirer_received_file (receipt_id),
    CONSTRAINT uk_acquirer_record_decision_accepted UNIQUE (acquirer_code, record_type, accepted_transaction_id),
    CONSTRAINT uk_acquirer_record_decision_accepted_evidence UNIQUE (acquirer_code, record_type, accepted_evidence_entry_id),
    CONSTRAINT ck_acquirer_record_decision_type CHECK (record_type IN ('SALE', 'CANCEL')),
    CONSTRAINT ck_acquirer_record_decision_seq CHECK (record_seq >= 1 AND record_amount_minor >= 1),
    CONSTRAINT ck_acquirer_record_decision_shape CHECK (
        (decision = 'ACCEPTED' AND reason_code IS NULL AND accepted_transaction_id IS NOT NULL AND accepted_transaction_id = transaction_id)
        OR (decision = 'REJECTED' AND reason_code IS NOT NULL AND accepted_transaction_id IS NULL)),
    CONSTRAINT ck_acquirer_record_decision_evidence CHECK (
        (decision = 'ACCEPTED' AND evidence_entry_id IS NOT NULL AND authorization_entry_id IS NOT NULL
            AND accepted_evidence_entry_id IS NOT NULL AND accepted_evidence_entry_id = evidence_entry_id)
        OR (decision = 'REJECTED' AND accepted_evidence_entry_id IS NULL)),
    CONSTRAINT ck_acquirer_record_decision_netted CHECK (
        netted_cancel_amount_minor >= 0 AND (record_type = 'SALE' OR netted_cancel_amount_minor = 0)),
    INDEX ix_acquirer_record_decision_transaction (acquirer_code, transaction_id, record_type),
    INDEX ix_acquirer_record_decision_authorization (acquirer_code, authorization_entry_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 게시 계획(결과 파일 줄): 수신 TX에서 한 번 정해 기록한다(Worker는 이 줄만 게시 — 재게시가 같은 바이트). 결과 중복·상충 시나리오에서는 한 레코드가 두 회차에
-- 나오고 상충 줄은 기관 판정과 다른 결과다(그래서 판정 표와 따로 둔다).
CREATE TABLE acquirer_result_line (
    receipt_id           BIGINT      NOT NULL,
    result_seq           INT         NOT NULL,
    record_seq           INT         NOT NULL,
    result               VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason_code          VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    CONSTRAINT pk_acquirer_result_line PRIMARY KEY (receipt_id, result_seq, record_seq),
    CONSTRAINT fk_acquirer_result_line_decision FOREIGN KEY (receipt_id, record_seq) REFERENCES acquirer_record_decision (receipt_id, record_seq),
    CONSTRAINT ck_acquirer_result_line_seq CHECK (result_seq BETWEEN 1 AND 99),
    CONSTRAINT ck_acquirer_result_line_shape CHECK (
        (result = 'ACCEPTED' AND reason_code IS NULL) OR (result = 'REJECTED' AND reason_code IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Worker 진행(게시 작업): 수신 TX가 ACK·결과 파일마다 PENDING 한 행을 만들고, Worker가 outbox 게시(임시명 → rename) 뒤 PUBLISHED로 바꾼다(@Version 조건부).
-- 유일한 갱신 표 — runtime 권한도 state·published_at·updated_at·version 열 UPDATE만(V12).
CREATE TABLE acquirer_work_item (
    work_item_id   BIGINT       NOT NULL AUTO_INCREMENT,
    receipt_id     BIGINT       NOT NULL,
    acquirer_code  VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    kind           VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq            INT          NOT NULL,
    file_name      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    due_at         DATETIME(3)  NOT NULL,
    state          VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    published_at   DATETIME(3)  NULL,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    version        BIGINT       NOT NULL,
    CONSTRAINT pk_acquirer_work_item PRIMARY KEY (work_item_id),
    CONSTRAINT fk_acquirer_work_item_receipt FOREIGN KEY (receipt_id) REFERENCES acquirer_received_file (receipt_id),
    CONSTRAINT uk_acquirer_work_item_name UNIQUE (acquirer_code, file_name),
    CONSTRAINT uk_acquirer_work_item_slot UNIQUE (receipt_id, kind, seq),
    CONSTRAINT ck_acquirer_work_item_kind CHECK (kind IN ('ACK', 'RESULT') AND seq BETWEEN 1 AND 99),
    CONSTRAINT ck_acquirer_work_item_state CHECK (state IN ('PENDING', 'PUBLISHED') AND (state = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT ck_acquirer_work_item_name CHECK (REGEXP_LIKE(file_name, '^(ACK|RES)\\.ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}\\.[0-9]{2}\\.dat$', 'c')),
    INDEX ix_acquirer_work_item_due (state, due_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 게시 기록(append-only): outbox에 rename으로 게시한(또는 이미 같은 바이트로 있던) 파일 하나당 한 행. 작업 하나당 하나(UNIQUE work_item_id).
CREATE TABLE acquirer_outbound_file (
    acquirer_code   VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_name       VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    work_item_id    BIGINT       NOT NULL,
    content_sha256  CHAR(64)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_bytes   INT          NOT NULL,
    published_at    DATETIME(3)  NOT NULL,
    CONSTRAINT pk_acquirer_outbound_file PRIMARY KEY (acquirer_code, file_name),
    CONSTRAINT fk_acquirer_outbound_file_work_item FOREIGN KEY (work_item_id) REFERENCES acquirer_work_item (work_item_id),
    CONSTRAINT uk_acquirer_outbound_file_work_item UNIQUE (work_item_id),
    CONSTRAINT ck_acquirer_outbound_file_content CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c') AND content_bytes >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
