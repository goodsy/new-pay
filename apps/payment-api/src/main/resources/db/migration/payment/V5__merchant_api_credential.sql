-- 가맹점 공개 API Credential 투영과 Payment 생성 행위자(CP-API-001, D-CPA-001). 명세: docs/repository/CP-API-001-CREATE-PAYMENT-API.md §5.
-- Credential 원본·발급은 MASTER(DOMAIN-DEFINITION), 이 표는 PAYMENT 로컬 검증용 투영이다(원본 아님). 채우는 수신 경로는 MASTER-CREDENTIAL-001이며
-- 그 전까지 운영 표는 비어 있어 모든 가맹점 요청이 401이다(fail-closed). 공통 규칙은 V1과 같다(utf8mb4_0900_bin, UTC DATETIME(3)).
-- 적용된 V1~V4는 고치지 않는다. runtime 권한은 db/runtime-privilege/payment/V6.

-- 원문 키는 저장하지 않는다: secret_sha256 = 키 원문 전체(`pc_<test|live>_sk_<keyId>.<secret>`)의 SHA-256 lowercase hex.
-- lease_until: 이 행을 최신으로 믿을 수 있는 한계(지나면 거절 — 전파 단절 시 폐기를 놓치지 않게, D-CPA-001 "lease 상한").
-- (MID, 환경)당 활성 키 최대 2개(D-CPA-001)는 발급 측(MASTER) 규칙이다 — 투영은 받은 상태를 그대로 검증에 쓴다.
CREATE TABLE merchant_api_credential (
    key_id              VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    secret_sha256       CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status              VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    valid_from          DATETIME(3) NOT NULL,
    valid_until         DATETIME(3) NULL,
    lease_until         DATETIME(3) NOT NULL,
    revision            BIGINT      NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_api_credential PRIMARY KEY (key_id),
    CONSTRAINT ck_merchant_api_credential_key_id CHECK (REGEXP_LIKE(key_id, '^[A-Za-z0-9]{8,32}$', 'c')),
    CONSTRAINT ck_merchant_api_credential_environment CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT ck_merchant_api_credential_sha256 CHECK (REGEXP_LIKE(secret_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_merchant_api_credential_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_merchant_api_credential_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_merchant_api_credential_revision CHECK (revision >= 1),
    INDEX ix_merchant_api_credential_mid (merchant_account_id, environment)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Payment 생성 행위자(Auditability). 새 Payment는 항상 값이 있다. NULL은 이 migration 이전 행만(expand migration — 기존 행을 지어내지 않는다).
ALTER TABLE payment
    ADD COLUMN created_by_credential_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER created_at;
