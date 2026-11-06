-- ACQUIRING-001 배치2 2-F(명세 docs/repository/ACQUIRING-001-ACQUIRING.md §4.1·§5.1·§11): 기관 ACK·결과 수신 기록, 레코드 결과(append-only), "취소 매입 불요" 기록.
-- 사용자 결정: D-ACQ-004(수신·결과 축 분리, REJECTED 종결, 마감 UNKNOWN → 대사, 자동 재전송 없음), D-ACQ-009(원 SALE ACCEPTED일 때만 CANCEL, REJECTED → 취소 매입 불요),
-- D-ACQ-010(늦은 유효 결과 자동 반영·UNKNOWN 행 보존·ACCEPTED 인정 시각 = 수신 시각·상충 격리). 공통 규칙은 V2·V5와 같다(키 열 utf8mb4_0900_bin, 시각 UTC DATETIME(3)).
-- 세 표 모두 append-only다(runtime 권한 SELECT·INSERT — V11). 파일 행의 수신·결과 축 열(receipt_state·result_state)은 V11이 열 단위 UPDATE를 연다.

-- 기관 응답 파일 처리 기록(ACK·결과 — outbox 파일은 지워지지 않고 덮어써지지 않으므로 FINANCIAL이 (매입사, 이름, SHA-256)로 처리 여부를 기록한다 — §5.1).
-- 받은 바이트를 보존한다(격리 응답의 운영자 확인·재현 근거). file_id는 FINANCIAL에 그 파일이 있을 때만(UNKNOWN_FILE 격리는 NULL). 반영(APPLIED)은 언제나 파일이 있다.
CREATE TABLE acquisition_response (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    acquirer_code     VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment       VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_name         VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_sha256    CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_bytes     INT         NOT NULL,
    content           MEDIUMBLOB  NOT NULL,
    kind              VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    sequence_no       INT         NOT NULL,
    disposition       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    quarantine_reason VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ack_outcome       VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ack_reason_code   VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    institution_at    DATETIME(3) NULL,
    evidence          VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    observed_at       DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_response PRIMARY KEY (id),
    CONSTRAINT uk_acquisition_response_name_hash UNIQUE (acquirer_code, file_name, content_sha256),
    CONSTRAINT fk_acquisition_response_file FOREIGN KEY (file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_response_acquirer CHECK (REGEXP_LIKE(acquirer_code, '^[A-Z0-9]{1,32}$', 'c')),
    CONSTRAINT ck_acquisition_response_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_acquisition_response_name CHECK (REGEXP_LIKE(file_name, '^(ACK|RES)[.]ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}[.][0-9]{2}[.]dat$', 'c')),
    CONSTRAINT ck_acquisition_response_sha256 CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_acquisition_response_bytes CHECK (content_bytes = LENGTH(content)),
    CONSTRAINT ck_acquisition_response_kind CHECK (kind IN ('ACK', 'RESULT')),
    CONSTRAINT ck_acquisition_response_seq CHECK (sequence_no BETWEEN 1 AND 99),
    CONSTRAINT ck_acquisition_response_disposition CHECK (
        disposition IN ('APPLIED', 'QUARANTINED') AND ((disposition = 'QUARANTINED') = (quarantine_reason IS NOT NULL))
        AND (disposition = 'QUARANTINED' OR file_id IS NOT NULL)),
    CONSTRAINT ck_acquisition_response_reason CHECK (quarantine_reason IS NULL OR quarantine_reason IN (
        'FORMAT_INVALID', 'NAME_MISMATCH', 'UNKNOWN_FILE', 'NAME_REUSED', 'HASH_MISMATCH', 'COUNT_MISMATCH', 'RECORD_MISMATCH', 'RECEIPT_CONFLICT',
        'ACK_CONFLICT', 'AFTER_CONFLICT')),
    CONSTRAINT ck_acquisition_response_ack CHECK (
        (ack_outcome IS NULL OR (kind = 'ACK' AND ack_outcome IN ('RECEIVED', 'FILE_REJECTED', 'RECEIPT_CONFLICT')))
        AND (ack_reason_code IS NULL OR (ack_outcome IS NOT NULL AND ack_outcome <> 'RECEIVED'))),
    CONSTRAINT ck_acquisition_response_evidence CHECK (
        evidence IS NULL OR (disposition = 'APPLIED' AND evidence IN ('ACK_HASH_MATCH', 'RESULT_HASH_MATCH'))),
    INDEX ix_acquisition_response_file (file_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 레코드 결과(append-only — 레코드(파일, 순번)마다 행 번호 1..n). 상태는 행에서 계산한다(CONFLICT 있음 → CONFLICT, 결정 행 → 그 결과, UNKNOWN 행만 → UNKNOWN).
-- ACCEPTED = 매입 확정(인정 시각 recognized_at = FINANCIAL 수신 시각 — D-ACQ-010), REJECTED(출처 RESULT_FILE 또는 ACK FILE_REJECTED), UNKNOWN = 결과 마감(출처 DEADLINE,
-- 응답 없음), CONFLICT = 기존 결정과 다른 결과(들어온 결과 observed_result — 격리). 늦은 결과는 UNKNOWN 행 뒤에 새 행으로 쌓인다(UNKNOWN 보존).
CREATE TABLE acquisition_record_result (
    file_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq             INT         NOT NULL,
    entry_no        INT         NOT NULL,
    result          VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    observed_result VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason_code     VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    source          VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    response_id     BIGINT      NULL,
    recognized_at   DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_record_result PRIMARY KEY (file_id, seq, entry_no),
    CONSTRAINT fk_acquisition_record_result_record FOREIGN KEY (file_id, seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT fk_acquisition_record_result_response FOREIGN KEY (response_id) REFERENCES acquisition_response (id),
    CONSTRAINT ck_acquisition_record_result_entry CHECK (entry_no >= 1),
    CONSTRAINT ck_acquisition_record_result_source CHECK (source IN ('RESULT_FILE', 'FILE_REJECTED', 'DEADLINE') AND ((source = 'DEADLINE') = (response_id IS NULL))),
    CONSTRAINT ck_acquisition_record_result_reason CHECK (reason_code IS NULL OR REGEXP_LIKE(reason_code, '^[A-Z0-9_]{1,32}$', 'c')),
    CONSTRAINT ck_acquisition_record_result_shape CHECK (
        (result = 'ACCEPTED' AND observed_result IS NULL AND reason_code IS NULL AND source = 'RESULT_FILE')
        OR (result = 'REJECTED' AND observed_result IS NULL AND reason_code IS NOT NULL AND source IN ('RESULT_FILE', 'FILE_REJECTED'))
        OR (result = 'UNKNOWN' AND observed_result IS NULL AND reason_code IS NULL AND source = 'DEADLINE')
        OR (result = 'CONFLICT' AND observed_result IN ('ACCEPTED', 'REJECTED') AND ((observed_result = 'REJECTED') = (reason_code IS NOT NULL))
            AND source IN ('RESULT_FILE', 'FILE_REJECTED')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- "취소 매입 불요"(D-ACQ-009 — 원 SALE REJECTED·파일 거절): 전송 후 취소 하나에 한 행(PK, append-only). 원 SALE 레코드를 가리킨다.
-- 같은 취소가 상계 기록·CANCEL 레코드와 겹치지 않는 것은 봉인 TX가 결제별 행 잠금 아래 "판정 끝남"으로 확인한다(표 간 제약은 trigger 없이 앱 판정).
CREATE TABLE acquisition_cancel_not_required (
    cancellation_transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_transaction_id     VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_file_id            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    original_seq                INT         NOT NULL,
    amount_minor                BIGINT      NOT NULL,
    currency                    CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_date               DATE        NOT NULL,
    decided_at                  DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_cancel_not_required PRIMARY KEY (cancellation_transaction_id),
    CONSTRAINT fk_acquisition_cancel_not_required_record FOREIGN KEY (original_file_id, original_seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT ck_acquisition_cancel_not_required_amount CHECK (amount_minor >= 1),
    CONSTRAINT ck_acquisition_cancel_not_required_currency CHECK (currency = 'KRW'),
    INDEX ix_acquisition_cancel_not_required_original (original_transaction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
