-- BIZ-CRIT-APPROVAL-001 S2(명세 rev 0.3a §3.1·§3.2·§3.5 — D-GOV-016 ③·D-GOV-025·D-GOV-026): 사업자 중요정보 변경 제안 표·1인 경로 선택 근거 원문 표, 근거 operation CHECK 확장.
-- 마지막 번호 V24(schema)·V25(privilege) 다음이라 V26, runtime 권한은 V27. 적용된 V1~V25는 고치지 않는다.
-- * business_change_proposal: 2인 칸 저장 한 건의 **원문 전체**(content — 정규 내용 JSON: 구분·상호·등록정보·하위 행)와 마스킹 변경 항목(changes)·중요 항목·정정/대표자 표시·사유·근거 참조·요청자.
--   INSERT 전용(상태 없음 — '적용됨' = 근거 EXECUTED 행이 이 제안을 가리킴). 사업자등록번호는 들어가지 않는다(불변 — P-6). ADMIN에는 ID·digest·항목 이름만 간다(M-5).
--   content_digest = `h1:` + HMAC-SHA256(MASTER fingerprint 키, 정규 content + business_id + base_version) — 실행 판정은 저장된 content로 **다시 계산한** 값으로 한다(이 열은 응답·대조용).
--   요청자는 ADMIN 정규형(`admin:adm_<ULID>`) — 실행 TX가 근거 요청자와 대조한다(M-6). request_record_id는 제안 명령의 admin_request 기록(1:1).
-- * business_change_evidence: 1인 경로 선택 근거 원문(R3-m2 — BR-BIZ-391). 그 저장의 변경 이력 행(change_history_id)에 연결, 화면·이력 응답에 나가지 않는다(별도 조회만).
-- * representative_changed(NIT-1): Domain 값 — 대표자 집합이 바뀌었는가(일반/중요 무관, 변경 항목에 `representatives`가 있음과 같다). ADMIN pinned·감사 `rep=Y`·조회 응답의
--   `representativeChanged`는 다른 뜻(대표자 변경이 **중요로 판정됨** = important_fields에 `representatives`) — 조회 adapter가 important_fields로 계산한다(Coordinator 결정).
-- * 근거 operation CHECK: `ApplyBusinessCriticalChange` 추가(제안 `ProposeBusinessCriticalChange`는 1인 명령 — 근거 행 없음, 넣지 않는다). V11 nit-3처럼 DROP·ADD를 한 ALTER 문으로.
-- 부분 실패(NIT-4): MySQL DDL은 문장마다 암묵 commit이라 이 파일이 중간에 실패하면 앞 문장(표 생성)은 남고 Flyway 기록은 실패로 남는다. 운영 절차: 원인 수정 → 이미 만들어진 객체를
-- migration 계정으로 확인·수동 정리(DROP TABLE business_change_evidence / business_change_proposal, CHECK는 V13 값으로 되돌림) → `flyway repair`로 실패 기록 제거 → 다시 migrate.
-- 앱 코드는 이 표가 없으면 기동 검증(ddl-auto=validate)에서 멈추므로 반쯤 적용된 상태로 서비스하지 않는다.
CREATE TABLE business_change_proposal (
    proposal_id            VARCHAR(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_id            VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    base_version           BIGINT       NOT NULL,
    content                JSON         NOT NULL,
    content_digest         CHAR(67)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    changes                JSON         NOT NULL,
    important_fields       JSON         NOT NULL,
    representative_changed BOOLEAN      NOT NULL,
    subject_correction     BOOLEAN      NOT NULL,
    reason                 VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref           VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_by           VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    requested_at           DATETIME(3)  NOT NULL,
    request_record_id      VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_business_change_proposal PRIMARY KEY (proposal_id),
    CONSTRAINT uk_bcp_request_record UNIQUE (request_record_id),
    CONSTRAINT fk_bcp_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_bcp_id CHECK (REGEXP_LIKE(proposal_id, '^bcp_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    CONSTRAINT ck_bcp_base_version CHECK (base_version >= 0),
    CONSTRAINT ck_bcp_digest CHECK (REGEXP_LIKE(content_digest, '^h1:[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_bcp_evidence_ref CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    CONSTRAINT ck_bcp_requested_by CHECK (REGEXP_LIKE(requested_by, '^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$', 'c')),
    INDEX ix_bcp_business (business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE business_change_evidence (
    change_history_id BIGINT       NOT NULL,
    business_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    admin_request_id  VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    evidence_ref      VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_by       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    recorded_at       DATETIME(3)  NOT NULL,
    CONSTRAINT pk_business_change_evidence PRIMARY KEY (change_history_id),
    CONSTRAINT fk_bce_history FOREIGN KEY (change_history_id) REFERENCES master_change_history (id),
    CONSTRAINT fk_bce_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_bce_evidence_ref CHECK (REGEXP_LIKE(evidence_ref, '^[A-Za-z0-9._:#/-]{1,128}$', 'c')),
    INDEX ix_bce_business (business_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

ALTER TABLE admin_approval_evidence
    DROP CHECK ck_admin_approval_evidence_operation,
    ADD CONSTRAINT ck_admin_approval_evidence_operation CHECK (operation IN (
        'ApproveContractRevision', 'IssueFirstDefaultMerchantAccount',
        'ApprovePaymentMethodActivation', 'DecideSiteReview', 'EnableCurrency', 'ApproveCardRouteActivation',
        'ResumePaymentMethodActivation', 'ResumeCardRouteActivation', 'ConfirmSiteActivation',
        'ChangeMerchantCode', 'ApplyBusinessCriticalChange'));
