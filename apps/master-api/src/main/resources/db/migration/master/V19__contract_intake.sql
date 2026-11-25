-- MERCHANT-FLOW-001 FLOW-1(명세 docs/repository/MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md rev 0.3d §4A.3·§4A.4·§4A.9·§4A.14): 사업자 계약 접수 건·전이 이력 schema + 기존 사업자 이관.
-- 결정: D-FLOW-001 ②(접수 건 1:N·이관), D-FLOW-003 ①(열린 건 ≤ 1), D-FLOW-004 ①(계약 완료 건이 있으면 해지 뒤에만 새 접수), D-FLOW-005 ①(상담 중 → 계약 완료 거절).
-- V18은 빈 번호(V17 주석 — 추가 금지)라 이 파일이 V19, runtime 권한은 V20.
--
-- * 효력 없음(§4A.2): 접수 건은 권리·Snapshot·Outbox에 아무 효과가 없다 — merchant·entitlement·outbox 표를 건드리지 않는다.
-- * 접수 건(contract_intake): 사업자 1:N, (사업자, 생성 순번) PK — "최신" = 순번이 가장 큰 건(리뷰 m-3). 현재 상태 열은 전이 때 갱신(@Version)한다(append-only 아님 — §4A.14).
--   열린 건(상담 중·계약서 접수) 또는 계약 완료 건일 때만 값이 생기는 생성 열 UNIQUE(uk_cin_active — V15 current_order_key 관례)가 "사업자당 열린·완료 건 ≤ 1"을 DB에서 이중 방어한다.
--   열은 결정된 것만(리뷰 m-2): 영업대행사 참조 열·FK는 FLOW-4 migration이 더한다. 그때까지 경로 'SALES_AGENCY'는 CHECK가 받지 않는다(대행사 값 없는 대행사 경로 행 방지).
--   희망 서비스는 선택 가능한 두 가지 표시 열(효력 없음 — 가상계좌는 '준비 전'이라 저장 값 없음).
-- * 전이 이력(contract_intake_transition): INSERT 전용(runtime SELECT, INSERT만 — V20), (사업자, 접수 건, 순번) PK. 허용 전이 표(§4A.3)·사유 필수 전이·근거 참조 필수 여부를
--   같은 표 CHECK로 이중 방어한다(리뷰 n-2 — CHECK는 같은 표 열만 보므로 이관 표시 열을 두 표에 각각 둔다).
-- * 이관(§4A.9): 이 migration 시점의 **모든 사업자**(DRAFT·REGISTERED, 가맹점 유무 무관)에 이관 접수 건 1개(순번 1·계약 완료·이관 표시·접수 항목·근거·담당자 없음) +
--   전이 이력 이관 행 1개(전 상태 없음 → 계약 완료). 행위자 'system:migration', 시각 = business.created_at(V13 채우기 선례 — 결정적, 재실행해도 같은 값) [구현 관례].
-- * MySQL CHECK 이름은 schema 전체에서 유일 → 표별 접두(ck_cin_·ck_cint_).
-- * 문장 순서: (1) 접수 건 표 → (2) 전이 이력 표 → (3) 접수 건 이관 → (4) 전이 이력 이관. MySQL DDL은 문장마다 암묵 commit이라 이 파일은 원자적이지 않다.
--   부분 실패 복구(전제 점검·점검·되돌림)는 db/recovery/master/contract-intake-v19-*.sql(ContractIntakeMigrationSpec이 정확성을 고정)을 따른다. 운영 실행은 LIVE Gate.

-- (1) 계약 접수 건(현재 상태). 이관 건만 접수 항목(접수일·경로·계약 담당자)이 없다(R-6 — 화면은 '이관'으로 표시).
CREATE TABLE contract_intake (
    business_id        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    intake_seq         INT         NOT NULL,
    status             VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    migrated           TINYINT(1)  NOT NULL,
    received_on        DATE        NULL,
    intake_channel     VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    contract_manager   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    desires_card_auth  TINYINT(1)  NOT NULL,
    desires_card_keyin TINYINT(1)  NOT NULL,
    created_at         DATETIME(3) NOT NULL,
    created_by         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    updated_by         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    version            BIGINT      NOT NULL,
    active_key         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED'), business_id, NULL)) STORED,
    CONSTRAINT pk_contract_intake PRIMARY KEY (business_id, intake_seq),
    CONSTRAINT uk_cin_active UNIQUE (active_key),
    CONSTRAINT fk_cin_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_cin_seq CHECK (intake_seq >= 1),
    CONSTRAINT ck_cin_status CHECK (status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED')),
    CONSTRAINT ck_cin_flags CHECK (migrated IN (0, 1) AND desires_card_auth IN (0, 1) AND desires_card_keyin IN (0, 1)),
    CONSTRAINT ck_cin_channel CHECK (intake_channel IS NULL OR intake_channel IN ('DIRECT_SALES', 'PHONE', 'WEBSITE', 'OTHER')),
    CONSTRAINT ck_cin_manager CHECK (contract_manager IS NOT NULL OR migrated = 1),
    CONSTRAINT ck_cin_items CHECK (migrated = 1 OR (received_on IS NOT NULL AND intake_channel IS NOT NULL)),
    CONSTRAINT ck_cin_migrated CHECK (migrated = 0 OR (intake_seq = 1 AND status IN ('COMPLETED', 'TERMINATED') AND received_on IS NULL AND intake_channel IS NULL
        AND contract_manager IS NULL AND desires_card_auth = 0 AND desires_card_keyin = 0)),
    CONSTRAINT ck_cin_version CHECK (version >= 0),
    CONSTRAINT ck_cin_updated CHECK (updated_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (2) 전이 이력(INSERT 전용). 생성 행 = 순번 1·전 상태 없음(일반 → 상담 중, 이관 → 계약 완료). 사유 = 공통 '사유 및 비고'(1~200 code point — BusinessChangeReason과 같음),
-- 근거 참조 = 계약서 수령 근거(D-MOPS-008 ① 형식 1~128자 [A-Za-z0-9._:#/-] — 카드번호 패턴 거부는 앱 FLOW-2).
CREATE TABLE contract_intake_transition (
    business_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    intake_seq     INT          NOT NULL,
    transition_seq INT          NOT NULL,
    from_status    VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    to_status      VARCHAR(24)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    migrated       TINYINT(1)   NOT NULL,
    reason         VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    document_ref   VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    actor_ref      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at    DATETIME(3)  NOT NULL,
    CONSTRAINT pk_contract_intake_transition PRIMARY KEY (business_id, intake_seq, transition_seq),
    CONSTRAINT fk_cint_intake FOREIGN KEY (business_id, intake_seq) REFERENCES contract_intake (business_id, intake_seq),
    CONSTRAINT ck_cint_seq CHECK (transition_seq >= 1),
    CONSTRAINT ck_cint_status CHECK (to_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED')
        AND (from_status IS NULL OR from_status IN ('CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED', 'TERMINATED'))),
    CONSTRAINT ck_cint_flags CHECK (migrated IN (0, 1)),
    CONSTRAINT ck_cint_first CHECK ((from_status IS NULL) = (transition_seq = 1)),
    -- NULL 안전: from_status가 NULL인 행은 첫 갈래만, NOT NULL인 행은 둘째 갈래만 본다(NULL 비교가 CHECK를 UNKNOWN = 통과로 만들지 않게).
    CONSTRAINT ck_cint_move CHECK (
        (from_status IS NULL AND ((to_status = 'CONSULTING' AND migrated = 0) OR (to_status = 'COMPLETED' AND migrated = 1)))
        OR (from_status IS NOT NULL AND migrated = 0 AND (
            (from_status = 'CONSULTING' AND to_status IN ('DOCUMENT_RECEIVED', 'REJECTED'))
            OR (from_status = 'DOCUMENT_RECEIVED' AND to_status IN ('CONSULTING', 'COMPLETED', 'REJECTED'))
            OR (from_status = 'COMPLETED' AND to_status = 'TERMINATED')))),
    CONSTRAINT ck_cint_migrated CHECK (migrated = 0 OR (intake_seq = 1 AND transition_seq = 1 AND reason IS NULL AND document_ref IS NULL)),
    CONSTRAINT ck_cint_document CHECK (to_status <> 'COMPLETED' OR document_ref IS NOT NULL OR migrated = 1),
    CONSTRAINT ck_cint_document_target CHECK (document_ref IS NULL OR to_status IN ('COMPLETED', 'TERMINATED')),
    CONSTRAINT ck_cint_document_format CHECK (document_ref IS NULL OR REGEXP_LIKE(document_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    CONSTRAINT ck_cint_reason_required CHECK (reason IS NOT NULL OR from_status IS NULL
        OR NOT ((from_status = 'DOCUMENT_RECEIVED' AND to_status = 'CONSULTING') OR to_status IN ('REJECTED', 'TERMINATED'))),
    CONSTRAINT ck_cint_reason_length CHECK (reason IS NULL OR CHAR_LENGTH(reason) BETWEEN 1 AND 200)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- (3) 기존 사업자 이관 접수 건(§4A.9 — 사업자마다 정확히 1개, 열 목록 명시, 한 문장이라 빠지는 행 없음).
INSERT INTO contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin,
    created_at, created_by, updated_at, updated_by, version)
SELECT business_id, 1, 'COMPLETED', 1, NULL, NULL, NULL, 0, 0, created_at, 'system:migration', created_at, 'system:migration', 0
FROM business;

-- (4) 이관 접수 건마다 전이 이력 이관 행 1개(리뷰 n-2 — 전 상태 없음 → 계약 완료, 이관 표시, 근거·사유 없음).
INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at)
SELECT business_id, intake_seq, 1, NULL, 'COMPLETED', 1, NULL, NULL, 'system:migration', created_at
FROM contract_intake
WHERE migrated = 1;
