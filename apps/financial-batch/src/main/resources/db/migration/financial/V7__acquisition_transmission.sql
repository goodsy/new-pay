-- ACQUIRING-001 배치2 2-D(명세 docs/repository/ACQUIRING-001-ACQUIRING.md §4.1·§4.5·§11): 매입 파일 SFTP 전송 상태기계·전송 시도 기록.
-- 사용자 결정: D-ACQ-004(전송·수신·결과 축 분리, 자동 재매입 없음), D-ACQ-006(키 인증 + host key 고정). Implementer 해석: I-9(전송 완료 근거 3종),
-- I-10(같은 파일 ID·같은 봉인 바이트 재업로드 허용, 새 ID 재전송 없음), I-23(같은 ID 다른 내용 = TRANSMISSION_QUARANTINED 격리).
-- 외부 호출(SFTP)은 DB TX 밖이다: 점유 TX(SEALED → TRANSMITTING + lease + 시도 행) → SFTP → 결과 TX(@Version 기대 version 비교 + 결과 행).

-- 파일 행의 전송 축 열. lease는 TRANSMITTING일 때만, transmitted_at은 TRANSMITTED일 때만 있다(Domain 불변식과 같은 CHECK).
ALTER TABLE acquisition_file
    ADD COLUMN transmission_lease_until DATETIME(3) NULL AFTER result_state,
    ADD COLUMN transmitted_at           DATETIME(3) NULL AFTER transmission_lease_until;

ALTER TABLE acquisition_file DROP CHECK ck_acquisition_file_transmission;

ALTER TABLE acquisition_file
    ADD CONSTRAINT ck_acquisition_file_transmission CHECK (
        transmission_state IN ('SEALED', 'TRANSMITTING', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN', 'TRANSMISSION_QUARANTINED')),
    ADD CONSTRAINT ck_acquisition_file_transmission_lease CHECK ((transmission_state = 'TRANSMITTING') = (transmission_lease_until IS NOT NULL)),
    ADD CONSTRAINT ck_acquisition_file_transmitted_at CHECK ((transmission_state = 'TRANSMITTED') = (transmitted_at IS NOT NULL));

-- 전송 시도(append-only): 점유 TX가 한 행을 INSERT한다. 결과 행이 없는 시도 = 진행 중이거나 중단된 시도(lease 만료 뒤 다음 시도가 확인 모드로 이어받는다).
-- resume_state = 진전 없이 끝났을 때 돌아갈 상태(SEALED에서 점유했으면 SEALED, 결과 모름·lease 만료에서 점유했으면 TRANSMISSION_UNKNOWN).
CREATE TABLE acquisition_transmission_attempt (
    file_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_no     INT         NOT NULL,
    resume_state   VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    content_sha256 CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    claimed_at     DATETIME(3) NOT NULL,
    lease_until    DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_transmission_attempt PRIMARY KEY (file_id, attempt_no),
    CONSTRAINT fk_acquisition_transmission_attempt_file FOREIGN KEY (file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_transmission_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_acquisition_transmission_attempt_resume CHECK (resume_state IN ('SEALED', 'TRANSMISSION_UNKNOWN')),
    CONSTRAINT ck_acquisition_transmission_attempt_lease CHECK (lease_until > claimed_at),
    CONSTRAINT ck_acquisition_transmission_attempt_sha256 CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 전송 시도 결과(append-only, 시도당 한 행). evidence는 DELIVERED만, reason은 NOT_DELIVERED·UNKNOWN만 가진다.
CREATE TABLE acquisition_transmission_outcome (
    file_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    attempt_no      INT         NOT NULL,
    outcome         VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence        VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason          VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    resulting_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decided_at      DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_transmission_outcome PRIMARY KEY (file_id, attempt_no),
    CONSTRAINT fk_acquisition_transmission_outcome_attempt FOREIGN KEY (file_id, attempt_no) REFERENCES acquisition_transmission_attempt (file_id, attempt_no),
    CONSTRAINT ck_acquisition_transmission_outcome_kind CHECK (outcome IN ('DELIVERED', 'NOT_DELIVERED', 'UNKNOWN', 'CONTENT_MISMATCH')),
    CONSTRAINT ck_acquisition_transmission_outcome_evidence CHECK (
        (outcome = 'DELIVERED') = (evidence IS NOT NULL)
        AND (evidence IS NULL OR evidence IN ('RENAMED_AND_VERIFIED', 'VERIFIED_FINAL_PRESENT', 'RENAMED'))),
    CONSTRAINT ck_acquisition_transmission_outcome_reason CHECK (
        (outcome IN ('NOT_DELIVERED', 'UNKNOWN')) = (reason IS NOT NULL)
        AND (reason IS NULL OR reason IN ('CONNECT_FAILED', 'HOST_KEY_REJECTED', 'AUTHENTICATION_FAILED', 'UPLOAD_FAILED', 'RENAME_REFUSED',
                                          'RENAME_OUTCOME_UNKNOWN', 'VERIFY_FAILED', 'RENAMED_BUT_ABSENT'))),
    CONSTRAINT ck_acquisition_transmission_outcome_state CHECK (
        resulting_state IN ('SEALED', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN', 'TRANSMISSION_QUARANTINED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
