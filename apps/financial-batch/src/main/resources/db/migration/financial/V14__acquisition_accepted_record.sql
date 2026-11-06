-- ACQUIRING-001 rev 1.1(명세 docs/repository/ACQUIRING-001-ACQUIRING.md §4.6·§11): 리뷰 m-2 격리 사유 추가, 사용자 결정 D-ACQ-014 "거래·종류당 세대를 넘는 ACCEPTED는 하나".
-- V12의 UNIQUE(transaction_id, record_type, new_generation)·레코드 표 UNIQUE(transaction_id, record_type, generation)는 **같은 세대**의 중복만 막는다 — 서로 다른 세대
-- (예: 재매입 세대 ACCEPTED 뒤 앞 세대의 늦은 ACCEPTED)가 둘 다 ACCEPTED가 되는 것은 막지 못한다. 이 표가 그것을 DB에서 막는다(앱 규칙 RecordResultRule이 먼저 CONFLICT로 격리).

-- (리뷰 m-2) 전송 축이 TRANSMISSION_QUARANTINED인 파일의 응답 격리 사유.
ALTER TABLE acquisition_response DROP CHECK ck_acquisition_response_reason;

ALTER TABLE acquisition_response
    ADD CONSTRAINT ck_acquisition_response_reason CHECK (quarantine_reason IS NULL OR quarantine_reason IN (
        'FORMAT_INVALID', 'NAME_MISMATCH', 'UNKNOWN_FILE', 'NAME_REUSED', 'HASH_MISMATCH', 'COUNT_MISMATCH', 'RECORD_MISMATCH', 'RECEIPT_CONFLICT',
        'ACK_CONFLICT', 'AFTER_CONFLICT', 'TRANSMISSION_QUARANTINED'));

-- 매입 확정 표지(append-only): ACCEPTED 결과 행을 쓰는 TX가 함께 INSERT한다. PK(transaction_id, record_type) = 세대를 넘어 ACCEPTED 하나(D-ACQ-014).
-- 가리키는 결과 행(file_id, seq, entry_no)은 그 ACCEPTED 행이다(FK). 기존 데이터: V10~V13이 처음 적용되는 볼륨이라 채울 행이 없다(Demo 볼륨은 V9 — §11).
CREATE TABLE acquisition_accepted_record (
    transaction_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_type    VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq            INT         NOT NULL,
    entry_no       INT         NOT NULL,
    generation     INT         NOT NULL,
    accepted_at    DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_accepted_record PRIMARY KEY (transaction_id, record_type),
    CONSTRAINT fk_acquisition_accepted_record_result FOREIGN KEY (file_id, seq, entry_no) REFERENCES acquisition_record_result (file_id, seq, entry_no),
    CONSTRAINT ck_acquisition_accepted_record_type CHECK (record_type IN ('SALE', 'CANCEL')),
    CONSTRAINT ck_acquisition_accepted_record_generation CHECK (generation >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
