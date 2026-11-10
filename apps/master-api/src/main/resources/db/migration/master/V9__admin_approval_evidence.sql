-- 2인 승인 명령의 MASTER 승인 근거(MERCHANT-OPS-002 B2-2 §9.3 — D-MOPS-007 ①·D-MOPS-008 ①·③, D-ADMIN-007, C-15·C-21·C-29). INSERT-only(runtime 권한 SELECT, INSERT — V10).
-- 명세: docs/repository/MERCHANT-OPS-002-MERCHANT-ONBOARDING.md §9.3(열 rev 0.6 확정). 적용된 V1~V7은 고치지 않는다. 번호: schema 홀수·privilege 짝수 관례로 V8은 비워 둔다(C-21 — 이후 V8 추가 금지).
-- 한 행 = 승인 하나(approval_id PK — 1회 사용). 성공(EXECUTED)과 저장된 거절(REJECTED) 모두 행을 남긴다(C-15). 예외: APPROVAL_ID_ALREADY_USED는 PK 충돌이라 행이 없다(admin_request 거절만).
-- 불변식(n-a): 승인 operation의 admin_request 결과에는(APPROVAL_ID_ALREADY_USED 제외) 같은 TX에서 쓴 근거 행이 request_record_id로 1:1 — admin_request.request_record_id는 UNIQUE가 아니라
-- FK 대상이 될 수 없으므로 FK 없이 UNIQUE만 둔다(review n-2). Revision 등록자는 기존 contract_revision.registered_by를 쓴다(새 열 없음).
-- 형식 CHECK는 web parser·Domain 형식과 같은 값이다(이중 방어): approval_id `apr_<ULID>`, 행위자 정규형 `admin:adm_<ULID 대문자>`, evidence_ref `[A-Za-z0-9._:#/-]{1,128}`, 지문 `mop1:<64 hex 소문자>`.
-- DB 방어선: EXECUTED는 요청자 ≠ 승인자·기한 > 승인 시각. decided_via = DEADLINE_CONFIRM(기한 확인 전용 호출)은 근거 판정 1·2·4 거절만(review m-a·n-b — 열도 NOT NULL).
CREATE TABLE admin_approval_evidence (
    approval_id          VARCHAR(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    operation            VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_record_id    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_by         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_by          VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approved_at          DATETIME(3)  NOT NULL,
    execute_before       DATETIME(3)  NOT NULL,
    evidence_ref         VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    approval_fingerprint CHAR(69)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    outcome              VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    result_code          VARCHAR(48)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    result_reference     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    decided_via          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_at          DATETIME(3)  NOT NULL,
    CONSTRAINT pk_admin_approval_evidence PRIMARY KEY (approval_id),
    CONSTRAINT uk_admin_approval_evidence_request_record UNIQUE (request_record_id),
    CONSTRAINT ck_admin_approval_evidence_approval_id CHECK (REGEXP_LIKE(approval_id, '^apr_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_approval_evidence_operation CHECK (operation IN ('ApproveContractRevision', 'IssueFirstDefaultMerchantAccount')),
    CONSTRAINT ck_admin_approval_evidence_requested_by CHECK (REGEXP_LIKE(requested_by, '^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_approval_evidence_approved_by CHECK (REGEXP_LIKE(approved_by, '^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_admin_approval_evidence_evidence_ref CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    CONSTRAINT ck_admin_approval_evidence_fingerprint CHECK (REGEXP_LIKE(approval_fingerprint, '^mop1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_admin_approval_evidence_outcome CHECK (outcome IN ('EXECUTED', 'REJECTED')),
    CONSTRAINT ck_admin_approval_evidence_decided_via CHECK (decided_via IS NOT NULL AND decided_via IN ('EXECUTE', 'DEADLINE_CONFIRM')),
    CONSTRAINT ck_admin_approval_evidence_distinct_actors CHECK (outcome = 'REJECTED' OR requested_by <> approved_by),
    CONSTRAINT ck_admin_approval_evidence_window CHECK (outcome = 'REJECTED' OR execute_before > approved_at),
    CONSTRAINT ck_admin_approval_evidence_result CHECK (
        (outcome = 'EXECUTED' AND result_code IS NULL AND result_reference IS NOT NULL)
        OR (outcome = 'REJECTED' AND result_code IS NOT NULL AND result_reference IS NULL)),
    CONSTRAINT ck_admin_approval_evidence_deadline_confirm CHECK (
        decided_via = 'EXECUTE'
        OR (outcome = 'REJECTED' AND result_code IN ('APPROVAL_EXPIRED', 'APPROVAL_SAME_ACTOR', 'APPROVAL_WINDOW_INVALID')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
