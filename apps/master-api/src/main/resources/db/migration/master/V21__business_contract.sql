-- MERCHANT-FLOW-001 FLOW-2a(명세 docs/repository/MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md rev 0.4a §4B.10): 사업자 계약 schema + 접수 건 개정 + 기존 이관 접수 건의 사업자 계약 이관.
-- 결정: D-GOV-013 ①(사업자 계약 — 접수 건과 분리), D-GOV-014 ①(버전·변경 접수, 완료 건이 있어도 새 접수), D-GOV-018 ①②ⓑ(기간 규칙, 접수 건 해지 전이 삭제), D-GOV-021(BR-BIZ-324~347).
-- 대장: docs/rules/BIZ.md §13(BR-BIZ-313~347). 마지막 번호 V19·권한 V20(V18은 빈 번호 — V17 주석)이라 이 파일이 V21, runtime 권한은 V22.
--
-- * 효력 없음(BR-BIZ-313·321): 사업자 계약은 새 가맹점 등록 자격일 뿐 — merchant·entitlement·outbox 표를 건드리지 않는다.
-- * 새 표 5개는 INSERT 전용(runtime SELECT, INSERT만 — V22). 상태 열은 없다(효력 전·유효·정지·해지·만료는 Domain이 버전 기간·사건·판정 시각으로 계산 — §4B.4).
-- * 문장 순서(§4B.10 0~5): (0) 전제 guard → (1) V19 CHECK 교체(표마다 ALTER 한 문장) → (2) 열린 건 UNIQUE 교체(새 것 먼저) → (3) 접수 건 종류·대상 열 →
--   (4) 새 표 5개 + 접수 건 대상 FK → (5) 이관 접수 건마다 사업자 계약 v1 이관. MySQL DDL은 문장마다 암묵 commit이라 이 파일은 원자적이지 않다.
--   부분 실패 복구(전제 점검·점검·되돌림)는 db/recovery/master/business-contract-v21-*.sql(BusinessContractMigrationSpec이 정확성을 고정)을 따른다. 운영 실행은 LIVE Gate.
-- * ALTER는 문장마다 ALGORITHM을 명시한다(표가 작아 COPY — 생성 열·CHECK·FK 추가가 모두 COPY를 요구하거나 허용한다, MINOR-I 관례).
-- * MySQL CHECK 이름은 schema 전체에서 유일 → 표별 접두(ck_bc_·ck_bcv_·ck_bcvs_·ck_bcvc_·ck_bce_). V19 CHECK는 같은 이름으로 다시 만든다(한 ALTER 안 DROP + ADD — MySQL 8.4 허용).

-- (0) 전제 guard(리뷰 MINOR-2 ⓒ): 판정 4개가 모두 0이어야 한다 — 접수 건 TERMINATED 0행, 전이 이력 → TERMINATED 0행, 이관이 아닌 계약 완료 건 0행(FLOW-1에는 전이 명령이 없다),
-- 저장된 `CONTRACT_INTAKE_COMPLETED_EXISTS` 거절 0행(enum 값 삭제 — §4B.7). 하나라도 0이 아니면 없는 표를 PREPARE해 오류 1146(v21_refused_precheck_failed)으로 **DDL 전에** 멈춘다
-- (선례 db/recovery/master/contract-intake-v19-revert.sql). 자동 변환 없음 — 상태 의미가 바뀌는 데이터라 사람이 결정한다(Coordinator 보고). 같은 판정을 사람이 보는 SQL =
-- db/recovery/master/business-contract-v21-precheck.sql.
SET @v21_precheck_violations = (SELECT COUNT(*) FROM contract_intake WHERE status = 'TERMINATED')
    + (SELECT COUNT(*) FROM contract_intake_transition WHERE to_status = 'TERMINATED')
    + (SELECT COUNT(*) FROM contract_intake WHERE migrated = 0 AND status = 'COMPLETED')
    + (SELECT COUNT(*) FROM admin_request WHERE result_code = 'CONTRACT_INTAKE_COMPLETED_EXISTS');
SET @v21_precheck_guard = IF(@v21_precheck_violations = 0, 'DO 0', 'SELECT 1 FROM v21_refused_precheck_failed');
PREPARE v21_precheck_guard FROM @v21_precheck_guard;
EXECUTE v21_precheck_guard;
DEALLOCATE PREPARE v21_precheck_guard;

-- (1) V19 CHECK 교체(리뷰 MINOR-2 ⓐ — 표마다 ALTER 한 문장): TERMINATED 제거(BR-BIZ-338), 이관 건 상태 = COMPLETED만, COMPLETED → TERMINATED 갈래 삭제, 근거 대상 = COMPLETED만,
-- 사유 필수에서 TERMINATED 제거. CHECK 추가는 기존 행을 검사한다 — (0)을 통과한 데이터만 온다(실패하면 3819).
ALTER TABLE contract_intake
    DROP CHECK ck_cin_status,
    DROP CHECK ck_cin_migrated,
    ADD CONSTRAINT ck_cin_status CHECK (status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED')),
    ADD CONSTRAINT ck_cin_migrated CHECK (migrated = 0 OR (intake_seq = 1 AND status = 'COMPLETED' AND received_on IS NULL AND intake_channel IS NULL
        AND contract_manager IS NULL AND desires_card_auth = 0 AND desires_card_keyin = 0)),
    ALGORITHM = COPY;

ALTER TABLE contract_intake_transition
    DROP CHECK ck_cint_status,
    DROP CHECK ck_cint_move,
    DROP CHECK ck_cint_document_target,
    DROP CHECK ck_cint_reason_required,
    ADD CONSTRAINT ck_cint_status CHECK (to_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED')
        AND (from_status IS NULL OR from_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED'))),
    -- NULL 안전: from_status가 NULL인 행은 첫 갈래만, NOT NULL인 행은 둘째 갈래만 본다(V19와 같은 모양에서 해지 갈래만 뺀다).
    ADD CONSTRAINT ck_cint_move CHECK (
        (from_status IS NULL AND ((to_status = 'CONSULTING' AND migrated = 0) OR (to_status = 'COMPLETED' AND migrated = 1)))
        OR (from_status IS NOT NULL AND migrated = 0 AND (
            (from_status = 'CONSULTING' AND to_status IN ('DOCUMENT_RECEIVED', 'REJECTED'))
            OR (from_status = 'DOCUMENT_RECEIVED' AND to_status IN ('CONSULTING', 'COMPLETED', 'REJECTED'))))),
    ADD CONSTRAINT ck_cint_document_target CHECK (document_ref IS NULL OR to_status = 'COMPLETED'),
    ADD CONSTRAINT ck_cint_reason_required CHECK (reason IS NOT NULL OR from_status IS NULL
        OR NOT ((from_status = 'DOCUMENT_RECEIVED' AND to_status = 'CONSULTING') OR to_status = 'REJECTED')),
    ALGORITHM = COPY;

-- (2) 열린 건 UNIQUE 교체(리뷰 MINOR-2 ⓑ — 새 것 먼저): ① 열린 건(상담 중·계약서 접수)일 때만 값이 생기는 open_key + uk_cin_open 추가 → ② 그 뒤 uk_cin_active·active_key 삭제.
-- 중간 실패 때도 열린 건 ≤ 1 방어가 끊기지 않는다(옛 UNIQUE는 더 엄격한 집합 — 함께 있어도 기존 행은 통과). D-GOV-014 ①: 완료 건은 UNIQUE에서 빠진다(완료 건이 있어도 새 접수).
ALTER TABLE contract_intake
    ADD COLUMN open_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status IN ('CONSULTING', 'DOCUMENT_RECEIVED'), business_id, NULL)) STORED,
    ADD CONSTRAINT uk_cin_open UNIQUE (open_key),
    ALGORITHM = COPY;

ALTER TABLE contract_intake
    DROP INDEX uk_cin_active,
    DROP COLUMN active_key,
    ALGORITHM = COPY;

-- (3) 접수 건 종류(BR-BIZ-324·325): kind NULL 추가 → 기존 건 모두 NEW(계약이 없던 때의 접수 = 신규) → NOT NULL + CHECK. 대상 계약 순번은 변경 접수만(ck_cin_target),
-- 이관 건은 신규(ck_cin_migrated_kind). 대상 FK는 (4)의 표 뒤. 기본값은 두지 않는다(앱이 종류를 빠뜨린 행은 INSERT 오류 — fail-closed).
ALTER TABLE contract_intake
    ADD COLUMN kind VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER status,
    ADD COLUMN target_contract_seq INT NULL AFTER kind,
    ALGORITHM = COPY;

UPDATE contract_intake SET kind = 'NEW';

ALTER TABLE contract_intake
    MODIFY COLUMN kind VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_cin_kind CHECK (kind IN ('NEW', 'CHANGE')),
    ADD CONSTRAINT ck_cin_target CHECK ((kind = 'NEW') = (target_contract_seq IS NULL) AND (target_contract_seq IS NULL OR target_contract_seq >= 1)),
    ADD CONSTRAINT ck_cin_migrated_kind CHECK (migrated = 0 OR kind = 'NEW'),
    ALGORITHM = COPY;

-- (4) 새 표(INSERT 전용). 시각은 UTC DATETIME(3)(TZ-FIX-001). 사업자 계약 ID = (business_id, contract_seq)(§4B.2).
-- 사업자 계약 머리 행: 신규 접수 계약 완료 1건 = 계약 1개(uk_bc_origin_intake — I-2), 이관 계약은 순번 1·생성 접수 건 1(이관 건).
CREATE TABLE business_contract (
    business_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq      INT         NOT NULL,
    origin_intake_seq INT         NOT NULL,
    migrated          TINYINT(1)  NOT NULL,
    created_at        DATETIME(3) NOT NULL,
    created_by        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_business_contract PRIMARY KEY (business_id, contract_seq),
    CONSTRAINT uk_bc_origin_intake UNIQUE (business_id, origin_intake_seq),
    CONSTRAINT fk_bc_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT fk_bc_intake FOREIGN KEY (business_id, origin_intake_seq) REFERENCES contract_intake (business_id, intake_seq),
    CONSTRAINT ck_bc_seq CHECK (contract_seq >= 1 AND origin_intake_seq >= 1),
    CONSTRAINT ck_bc_migrated CHECK (migrated IN (0, 1) AND (migrated = 0 OR (contract_seq = 1 AND origin_intake_seq = 1)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 버전(BR-BIZ-316·317·329·335): 변경 접수 계약 완료 1건 = 버전 1개(uk_bcv_origin_intake — I-2. 계약 표 UNIQUE와 이유가 다르다: 계약 표 = 신규 완료 → 계약, 이 표 = 완료 → 버전).
-- 일반 버전 = 시작일·근거 필수, 시작 시각 = 시작일 00:00 KST(저장 UTC = 시작일 - 9시간, KST 고정 +9 — I-8). 이관 버전 = 버전 1만, 시작일·종료일·근거·사유 없음(I-7).
CREATE TABLE business_contract_version (
    business_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq      INT          NOT NULL,
    version_no        INT          NOT NULL,
    origin_intake_seq INT          NOT NULL,
    migrated          TINYINT(1)   NOT NULL,
    starts_at         DATETIME(3)  NOT NULL,
    start_date        DATE         NULL,
    end_date          DATE         NULL,
    document_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason            VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at        DATETIME(3)  NOT NULL,
    created_by        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_business_contract_version PRIMARY KEY (business_id, contract_seq, version_no),
    CONSTRAINT uk_bcv_origin_intake UNIQUE (business_id, origin_intake_seq),
    CONSTRAINT fk_bcv_contract FOREIGN KEY (business_id, contract_seq) REFERENCES business_contract (business_id, contract_seq),
    CONSTRAINT fk_bcv_intake FOREIGN KEY (business_id, origin_intake_seq) REFERENCES contract_intake (business_id, intake_seq),
    CONSTRAINT ck_bcv_seq CHECK (version_no >= 1 AND origin_intake_seq >= 1),
    CONSTRAINT ck_bcv_flags CHECK (migrated IN (0, 1)),
    -- NULL 안전: 갈래마다 IS [NOT] NULL을 명시해 NULL 비교가 CHECK를 UNKNOWN(= 통과)으로 만들지 않게 한다.
    CONSTRAINT ck_bcv_shape CHECK (
        (migrated = 0 AND start_date IS NOT NULL AND document_ref IS NOT NULL AND starts_at = TIMESTAMP(start_date) - INTERVAL 9 HOUR
            AND (end_date IS NULL OR end_date >= start_date))
        OR (migrated = 1 AND version_no = 1 AND start_date IS NULL AND end_date IS NULL AND document_ref IS NULL AND reason IS NULL)),
    CONSTRAINT ck_bcv_document_format CHECK (document_ref IS NULL OR REGEXP_LIKE(document_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    CONSTRAINT ck_bcv_reason_length CHECK (reason IS NULL OR CHAR_LENGTH(reason) BETWEEN 1 AND 200)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 버전 범위(BR-BIZ-315·329 — I-5): 서비스 종류 한 행씩. 지금은 카드 PG 결제만(값 추가는 CSA ServiceKind와 함께 검토). 버전마다 ≥ 1행은 Domain·저장 시험이 지킨다(DB는 표현 못 함).
CREATE TABLE business_contract_version_service (
    business_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq INT         NOT NULL,
    version_no   INT         NOT NULL,
    service_kind VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_business_contract_version_service PRIMARY KEY (business_id, contract_seq, version_no, service_kind),
    CONSTRAINT fk_bcvs_version FOREIGN KEY (business_id, contract_seq, version_no) REFERENCES business_contract_version (business_id, contract_seq, version_no),
    CONSTRAINT ck_bcvs_kind CHECK (service_kind IN ('CARD_PG_PAYMENT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 직전 버전 닫기(BR-BIZ-341): 버전당 닫기 1개(PK), 닫은 번호 = 닫힌 번호 + 1(I-3), 두 버전 모두 FK. 닫는 시각 = 새 버전 시작 시각(Domain — 표 사이 비교는 CHECK로 못 한다).
CREATE TABLE business_contract_version_close (
    business_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq         INT         NOT NULL,
    version_no           INT         NOT NULL,
    closed_at            DATETIME(3) NOT NULL,
    closed_by_version_no INT         NOT NULL,
    created_at           DATETIME(3) NOT NULL,
    created_by           VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_business_contract_version_close PRIMARY KEY (business_id, contract_seq, version_no),
    CONSTRAINT fk_bcvc_closed FOREIGN KEY (business_id, contract_seq, version_no) REFERENCES business_contract_version (business_id, contract_seq, version_no),
    CONSTRAINT fk_bcvc_closing FOREIGN KEY (business_id, contract_seq, closed_by_version_no) REFERENCES business_contract_version (business_id, contract_seq, version_no),
    CONSTRAINT ck_bcvc_next CHECK (closed_by_version_no = version_no + 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 정지·정지 해제·해지 기록(BR-BIZ-336·339): 사유 필수(1~200 code point), 근거 참조는 해지만(Q-FLOW-38 답 전 — 정지·정지 해제 근거 허용/필수로 답이 나오면 FLOW-2c migration이 CHECK를 바꾼다).
-- 해지는 계약당 1회(I-6): 해지일 때만 1인 생성 열 terminate_key + UNIQUE(사업자, 계약, terminate_key)(NULL은 UNIQUE에서 빠진다 — [구현 관례], 명세의 'UNIQUE(terminate_key)'와 같은 효과).
CREATE TABLE business_contract_event (
    business_id   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq  INT          NOT NULL,
    event_seq     INT          NOT NULL,
    kind          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    reason        VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    actor_ref     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at   DATETIME(3)  NOT NULL,
    terminate_key TINYINT GENERATED ALWAYS AS (IF(kind = 'TERMINATE', 1, NULL)) STORED,
    CONSTRAINT pk_business_contract_event PRIMARY KEY (business_id, contract_seq, event_seq),
    CONSTRAINT uk_bce_terminate UNIQUE (business_id, contract_seq, terminate_key),
    CONSTRAINT fk_bce_contract FOREIGN KEY (business_id, contract_seq) REFERENCES business_contract (business_id, contract_seq),
    CONSTRAINT ck_bce_seq CHECK (event_seq >= 1),
    CONSTRAINT ck_bce_kind CHECK (kind IN ('SUSPEND', 'RESUME', 'TERMINATE')),
    CONSTRAINT ck_bce_reason_length CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 200),
    CONSTRAINT ck_bce_evidence_format CHECK (evidence_ref IS NULL OR REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    CONSTRAINT ck_bce_evidence_kind CHECK (evidence_ref IS NULL OR kind = 'TERMINATE')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 변경 접수 건의 대상 계약 FK(I-9 — 값은 ck_cin_target이 변경 접수만 허용).
ALTER TABLE contract_intake
    ADD CONSTRAINT fk_cin_target FOREIGN KEY (business_id, target_contract_seq) REFERENCES business_contract (business_id, contract_seq),
    ALGORITHM = COPY;

-- (5) 이관(BR-BIZ-334·343): 이관 접수 건(migrated = 1 AND status = 'COMPLETED')마다 사업자 계약(순번 1·생성 접수 건 = 그 건·이관 표시·created_at = 이관 시각 UTC_TIMESTAMP(3) —
-- 한 문장 안에서 같은 값)·버전 1(이관, 시작 = 계약 created_at, 시작일·종료일·근거·사유 없음)·범위 카드 PG 결제 1행. 열 목록 명시, 표마다 한 문장(빠지는 행 없음).
-- 이관 계약은 판정 시각에 유효다(종료일 없음) — 기존 사업자의 새 가맹점 등록이 FLOW-3 관문 뒤에도 끊기지 않는다.
INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by)
SELECT business_id, 1, intake_seq, 1, UTC_TIMESTAMP(3), 'system:migration'
FROM contract_intake
WHERE migrated = 1 AND status = 'COMPLETED';

INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, reason,
    created_at, created_by)
SELECT business_id, contract_seq, 1, origin_intake_seq, 1, created_at, NULL, NULL, NULL, NULL, created_at, 'system:migration'
FROM business_contract
WHERE migrated = 1;

INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind)
SELECT business_id, contract_seq, version_no, 'CARD_PG_PAYMENT'
FROM business_contract_version
WHERE migrated = 1;
