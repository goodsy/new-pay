-- MERCHANT-CODE-001 MC-1(§3.2·§3.5, C-9·C-20·C-24·C-26): 사람이 읽는 가맹점 코드의 append-only 배정 이력 표. 현재 코드 = 그 가맹점의 가장 큰 assignment_seq 행.
-- merchant 표에는 코드 열을 두지 않는다(runtime이 코드 UPDATE 권한을 갖지 않게 — V14 권한 SELECT, INSERT만).
-- * UNIQUE(merchant_code): 현재·은퇴 코드를 한꺼번에 막는다(한 번 배정된 코드는 영구 점유 — D-MCODE-002, 감사 코드 → 가맹점 하나).
-- * PK(merchant_id, assignment_seq) + CHECK(seq IN (1, 2)): 1 = 첫 배정(등록·채우기), 2 = 유일한 변경(MC-1b) — 두 번째 변경 INSERT는 DB가 막는다.
-- * 자기 FK(merchant_id, replaces_code) → (merchant_id, merchant_code): 바뀌기 전 코드는 같은 가맹점의 배정 코드여야 한다.
-- * 두 FK 모두 ON DELETE·ON UPDATE 동작을 지정하지 않는다(기본 NO ACTION — V1 FK 관례, runtime에 UPDATE·DELETE 권한도 없음).
-- * approval_id는 FK 없이 UNIQUE만(C-10 — V9 request_record_id 선례). seq 2 ↔ 근거 EXECUTED 1:1은 MC-1b 불변식 시험.
CREATE TABLE merchant_code_assignment (
    merchant_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    assignment_seq TINYINT     NOT NULL,
    merchant_code  VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    replaces_code  VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    source         VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_id    VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    reason_code    VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    assigned_at    DATETIME(3) NOT NULL,
    assigned_by    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_merchant_code_assignment PRIMARY KEY (merchant_id, assignment_seq),
    CONSTRAINT uk_mca_code UNIQUE (merchant_code),
    CONSTRAINT uk_mca_merchant_code UNIQUE (merchant_id, merchant_code),
    CONSTRAINT uk_mca_approval UNIQUE (approval_id),
    CONSTRAINT fk_mca_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
    CONSTRAINT fk_mca_replaces FOREIGN KEY (merchant_id, replaces_code) REFERENCES merchant_code_assignment (merchant_id, merchant_code),
    CONSTRAINT ck_mca_seq CHECK (assignment_seq IN (1, 2)),
    CONSTRAINT ck_mca_code CHECK (REGEXP_LIKE(merchant_code, '^[a-z][a-z0-9]{5,13}$', 'c')),
    CONSTRAINT ck_mca_replaces_code CHECK (replaces_code IS NULL OR REGEXP_LIKE(replaces_code, '^[a-z][a-z0-9]{5,13}$', 'c')),
    CONSTRAINT ck_mca_source CHECK (source IN ('REGISTRATION', 'BACKFILL', 'CHANGE')),
    CONSTRAINT ck_mca_approval_id CHECK (approval_id IS NULL OR REGEXP_LIKE(approval_id, '^apr_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_mca_assignment CHECK (
        (assignment_seq = 1 AND replaces_code IS NULL AND approval_id IS NULL AND reason_code IS NULL AND source IN ('REGISTRATION', 'BACKFILL'))
        OR (assignment_seq = 2 AND replaces_code IS NOT NULL AND approval_id IS NOT NULL AND reason_code IS NOT NULL AND source = 'CHANGE'
            AND replaces_code <> merchant_code))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기존 가맹점 채우기(Demo — §3.5 C-24·C-26): 열 목록 명시, 한 문장(빠지는 행 없음). 'm' + SHA-256(merchant_id) 소문자 16진 앞 13자 = 14자(§3.1 형식, 예약어는 'm'으로 시작하지 않음).
-- 결정적(같은 merchant_id → 같은 코드, 시각은 merchant.created_at). mer_ 내부 키를 역산할 수 없다(D-ADMIN-UI-004 ③). 13자 접두가 겹치면 uk_mca_code 1062로 migration 실패(fail-closed).
INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by)
SELECT merchant_id, 1, CONCAT('m', LEFT(SHA2(merchant_id, 256), 13)), NULL, 'BACKFILL', NULL, NULL, created_at, 'system:migration'
FROM merchant;

-- C-20(MERCHANT-OPS-002 C-42 선례): 근거 operation CHECK에 ChangeMerchantCode를 미리 더한다(앱은 MC-1b 전까지 그 경로를 만들지 않는다 → MC-1b는 MASTER migration 없음).
-- V11 nit-3과 같이 DROP·ADD를 한 ALTER 문으로(CHECK 없는 중간 상태 없음). 기존 값 집합은 그대로.
ALTER TABLE admin_approval_evidence
    DROP CHECK ck_admin_approval_evidence_operation,
    ADD CONSTRAINT ck_admin_approval_evidence_operation CHECK (operation IN (
        'ApproveContractRevision', 'IssueFirstDefaultMerchantAccount',
        'ApprovePaymentMethodActivation', 'DecideSiteReview', 'EnableCurrency', 'ApproveCardRouteActivation',
        'ResumePaymentMethodActivation', 'ResumeCardRouteActivation', 'ConfirmSiteActivation',
        'ChangeMerchantCode'));
