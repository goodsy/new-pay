-- ACQ-ADMIN-001 B1(명세 docs/repository/ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md §3.2·§6): 재매입 실행 입력 경로(D-ACQ-011 — 사용자 결정 "ADMIN 입력은 ACQ-ADMIN-001").
-- (1) acquisition_ops_decision: ADMIN 승인 ID별 최초 확정 결정(append-only, UNIQUE approval_id — D-ADMIN-007 양쪽 검사의 FINANCIAL 자기 기록, D-OPS-005 선례).
--     같은 승인 ID 재전송은 이 행을 재생한다. 사유 문구는 넣지 않는다(내용 hash만 — 사유 원문은 ADMIN).
-- (2) acquisition_reacquisition_approval 키 재정의: 승인 하나 = 같은 원 파일 레코드 묶음(D-ACQA-003) → PK(approval_id, file_id, seq), 결정 행 FK, (파일, 순번, 새 세대)
--     복합 UNIQUE(ACQUIRING-001 리뷰 n-4). **이 표는 비어 있다**: V13이 runtime INSERT를 막아 왔고(1142) 운영 경로가 없었다. 행이 남아 있으면 아래 결정 FK 추가가
--     1452로 실패해 migration이 멈춘다(결정 행 없는 승인은 있을 수 없다 — 수동 확인 대상).
-- (3) acquisition_reacquisition_not_required: 실행 시점 순액 0(D-ACQ-013 계산 결과 — 그 사이 전액 취소)인 대상의 "재매입 불요" 종결(D-ACQA-004). 거래·종류당 하나(PK).
-- 공통 규칙: 키·식별 열 utf8mb4_0900_bin, 시각 UTC DATETIME(3).
CREATE TABLE acquisition_ops_decision (
    seq                BIGINT       NOT NULL AUTO_INCREMENT,
    approval_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    kind               VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decision           VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    rejection_code     VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    target_ref         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_file_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    content_hash       CHAR(69)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_by       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_at        DATETIME(3)  NOT NULL,
    execute_before     DATETIME(3)  NOT NULL,
    decided_at         DATETIME(3)  NOT NULL,
    recorded_at        DATETIME(3)  NOT NULL DEFAULT (NOW(3)),
    origin             VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_acquisition_ops_decision PRIMARY KEY (seq),
    CONSTRAINT uk_acquisition_ops_decision_approval UNIQUE (approval_id),
    CONSTRAINT fk_acquisition_ops_decision_result_file FOREIGN KEY (result_file_id) REFERENCES acquisition_file (file_id),
    CONSTRAINT ck_acquisition_ops_decision_kind CHECK (kind IN ('REACQUISITION')),
    CONSTRAINT ck_acquisition_ops_decision_shape CHECK (
        (decision = 'EXECUTED' AND result_file_id IS NOT NULL AND rejection_code IS NULL)
        OR (decision = 'NOT_REQUIRED' AND result_file_id IS NULL AND rejection_code IS NULL)
        OR (decision = 'REJECTED' AND result_file_id IS NULL AND REGEXP_LIKE(rejection_code, '^[A-Z][A-Z0-9_]{0,47}$', 'c'))),
    CONSTRAINT ck_acquisition_ops_decision_hash CHECK (REGEXP_LIKE(content_hash, '^acd1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_acquisition_ops_decision_origin CHECK (
        origin = 'EXECUTION_COMMAND' OR (origin = 'DEADLINE_CONFIRMATION' AND decision = 'REJECTED' AND rejection_code = 'APPROVAL_EXECUTION_EXPIRED')),
    CONSTRAINT ck_acquisition_ops_decision_window CHECK (execute_before > approved_at),
    INDEX ix_acquisition_ops_decision_recorded (recorded_at, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

ALTER TABLE acquisition_reacquisition_approval
    DROP PRIMARY KEY,
    ADD CONSTRAINT pk_acquisition_reacquisition_approval PRIMARY KEY (approval_id, file_id, seq),
    ADD CONSTRAINT uk_acquisition_reacquisition_approval_record UNIQUE (file_id, seq, new_generation),
    ADD CONSTRAINT fk_acquisition_reacquisition_approval_decision FOREIGN KEY (approval_id) REFERENCES acquisition_ops_decision (approval_id);

CREATE TABLE acquisition_reacquisition_not_required (
    transaction_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    record_type            VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_id            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    file_id                VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seq                    INT         NOT NULL,
    approved_amount_minor  BIGINT      NOT NULL,
    cancelled_amount_minor BIGINT      NOT NULL,
    currency               CHAR(3)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    decided_at             DATETIME(3) NOT NULL,
    CONSTRAINT pk_acquisition_reacquisition_not_required PRIMARY KEY (transaction_id, record_type),
    CONSTRAINT fk_acquisition_reacquisition_not_required_approval FOREIGN KEY (approval_id, file_id, seq)
        REFERENCES acquisition_reacquisition_approval (approval_id, file_id, seq),
    CONSTRAINT ck_acquisition_reacquisition_not_required_type CHECK (record_type IN ('SALE', 'CANCEL')),
    CONSTRAINT ck_acquisition_reacquisition_not_required_amount CHECK (approved_amount_minor >= 1 AND cancelled_amount_minor = approved_amount_minor),
    CONSTRAINT ck_acquisition_reacquisition_not_required_currency CHECK (currency = 'KRW')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
