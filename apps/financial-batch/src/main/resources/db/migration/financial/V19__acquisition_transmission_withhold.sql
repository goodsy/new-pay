-- ACQ-ADMIN-001 B2(명세 docs/repository/ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md §15 L-9·§17.10): 재매입 파일 전송 보류.
-- 사용자 결정 D-ACQ-014("거래·종류당 세대를 넘는 ACCEPTED는 하나"): 재매입 파일을 봉인한 뒤 전송 전에 그 레코드가 다른 세대에서 매입 확정(acquisition_accepted_record 표지)이
-- 되었으면 그 파일을 보내지 않는다(이중 매입 요청 방지 — 표지 PK(V14)는 두 번째 ACCEPTED를 CONFLICT로 남길 뿐 기관 송신을 막지 못한다). 전송 점유 TX가 잠금 아래 표지를
-- 다시 보고 파일을 TRANSMISSION_WITHHELD(종결 — 자동 해제·재전송 없음, 대사)로 두고 그 근거를 아래 표에 append-only로 남긴다.

ALTER TABLE acquisition_file DROP CHECK ck_acquisition_file_transmission;

ALTER TABLE acquisition_file
    ADD CONSTRAINT ck_acquisition_file_transmission CHECK (
        transmission_state IN ('SEALED', 'TRANSMITTING', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN', 'TRANSMISSION_QUARANTINED', 'TRANSMISSION_WITHHELD'));

-- 보류 근거(append-only): 보류한 파일의 레코드(file_id, seq)마다, 그 거래·종류의 매입 확정 표지가 가리키던 다른 세대 레코드(accepted_file_id, accepted_seq).
-- prior_state = 보류 직전 전송 축(SEALED = 기관에 간 적 없음, TRANSMISSION_UNKNOWN·TRANSMITTING = 갔을 수 있음 — 대사가 구분한다).
CREATE TABLE acquisition_transmission_withhold (
    file_id             VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq                 INT         NOT NULL,
    transaction_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_type         VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    accepted_file_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    accepted_seq        INT         NOT NULL,
    accepted_generation INT         NOT NULL,
    prior_state         VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    withheld_at         DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_transmission_withhold PRIMARY KEY (file_id, seq),
    CONSTRAINT fk_acquisition_transmission_withhold_record FOREIGN KEY (file_id, seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT fk_acquisition_transmission_withhold_accepted FOREIGN KEY (accepted_file_id, accepted_seq) REFERENCES acquisition_file_record (file_id, seq),
    CONSTRAINT ck_acquisition_transmission_withhold_type CHECK (record_type IN ('SALE', 'CANCEL')),
    CONSTRAINT ck_acquisition_transmission_withhold_other CHECK (accepted_file_id <> file_id),
    CONSTRAINT ck_acquisition_transmission_withhold_generation CHECK (accepted_generation >= 0),
    CONSTRAINT ck_acquisition_transmission_withhold_prior CHECK (prior_state IN ('SEALED', 'TRANSMITTING', 'TRANSMISSION_UNKNOWN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 보류 뒤 늦게 끝난 앞 시도(lease 만료로 이어받힘)의 관찰 행(SUPERSEDED — 상태 불변, resulting_state = 그때 파일 현재 상태)이 TRANSMISSION_WITHHELD를 남길 수 있게 한다.
-- 결과 행(상태를 바꾸는 행)의 resulting_state 집합은 그대로다(보류는 전송 시도 결과가 아니다).
ALTER TABLE acquisition_transmission_outcome DROP CHECK ck_acquisition_transmission_outcome_state;

ALTER TABLE acquisition_transmission_outcome
    ADD CONSTRAINT ck_acquisition_transmission_outcome_state CHECK (
        resulting_state IN ('SEALED', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN', 'TRANSMISSION_QUARANTINED')
        OR (outcome = 'SUPERSEDED' AND resulting_state IN ('TRANSMITTING', 'TRANSMISSION_WITHHELD')));
