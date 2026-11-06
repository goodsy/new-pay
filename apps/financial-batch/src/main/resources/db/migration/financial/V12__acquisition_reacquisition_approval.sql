-- ACQUIRING-001 배치2 2-G(명세 docs/repository/ACQUIRING-001-ACQUIRING.md §4.1·§11): 재매입 2인 승인 기록(D-ACQ-011 — 사용자 결정, D-ADMIN-007 양쪽 검사).
-- 소유 서비스(FINANCIAL)의 자기 감사 기록: ADMIN 서명 채널 승인 명령을 다시 검사한 결과(요청자 ≠ 승인자, 대상 = REJECTED·UNKNOWN·파일 거절 레코드의 최신 generation).
-- 새 버전 = generation + 1, 원 레코드 참조(reacquisition_of = '{fileId}#{seq}' — 파일 계약 v1 형식). 같은 (거래, 종류, 새 generation) 승인은 하나(UNIQUE) — 레코드 표의
-- UNIQUE(transaction_id, record_type, generation)와 함께 이중 매입을 막는다.
-- **입력 경로(ACQ-ADMIN-001) 전에는 기록이 생기지 않는다**: runtime 계정은 이 표에 SELECT만 가진다(V13 — INSERT 1142). 실행(새 버전 파일 봉인)은 이 표의 범위가 아니다.
CREATE TABLE acquisition_reacquisition_approval (
    approval_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq               INT          NOT NULL,
    transaction_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_type       VARCHAR(8)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    target_generation INT          NOT NULL,
    new_generation    INT          NOT NULL,
    target_status     VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reacquisition_of  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_by      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason_code       VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    channel           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_acquisition_reacquisition_approval PRIMARY KEY (approval_id),
    CONSTRAINT uk_acquisition_reacquisition_approval_generation UNIQUE (transaction_id, record_type, new_generation),
    CONSTRAINT fk_acquisition_reacquisition_approval_record FOREIGN KEY (file_id, seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT ck_acquisition_reacquisition_approval_people CHECK (requested_by <> approved_by),
    CONSTRAINT ck_acquisition_reacquisition_approval_generation CHECK (target_generation >= 0 AND new_generation = target_generation + 1),
    CONSTRAINT ck_acquisition_reacquisition_approval_type CHECK (record_type IN ('SALE', 'CANCEL')),
    CONSTRAINT ck_acquisition_reacquisition_approval_status CHECK (target_status IN ('REJECTED', 'UNKNOWN', 'FILE_REJECTED')),
    CONSTRAINT ck_acquisition_reacquisition_approval_reference CHECK (reacquisition_of = CONCAT(file_id, '#', seq)),
    CONSTRAINT ck_acquisition_reacquisition_approval_channel CHECK (channel = 'ADMIN_SIGNED'),
    CONSTRAINT ck_acquisition_reacquisition_approval_evidence CHECK (CHAR_LENGTH(evidence_ref) >= 1 AND CHAR_LENGTH(reason_code) >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
