-- DEMO-SEED-001 §4.5(D-DEMO-002·004): DEMO 배포 전용 `demo-credential` 명령의 1회성·감사 행. **Demo — 운영 Credential 발급 경로 아님**
-- (운영 발급·전파는 MASTER-CREDENTIAL-001, 도입 시 이 명령·Demo 행 처리 방침을 확정 — D-DEMO-002). 행은 많아야 하나다: id 고정 1(CHECK) + PK가
-- 동시 실행·재실행의 권위다(두 번째 INSERT는 1062 → 패자 LOST / 사전 조회로 ALREADY_ISSUED). 원문 키·SHA-256은 넣지 않는다(keyId만).
-- 같은 TX에서 merchant_api_credential(V5)에 투영 행 하나를 INSERT한다. 공통 규칙은 V1과 같다(utf8mb4_0900_bin, UTC DATETIME(3)). runtime 권한은 V30.
CREATE TABLE demo_credential_issuance (
    id                  TINYINT     NOT NULL,
    key_id              VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    merchant_account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    environment         VARCHAR(4)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    issued_at           DATETIME(3) NOT NULL,
    actor               VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    correlation_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_demo_credential_issuance PRIMARY KEY (id),
    CONSTRAINT ck_demo_credential_issuance_single CHECK (id = 1),
    CONSTRAINT ck_demo_credential_issuance_environment CHECK (environment = 'TEST'),
    CONSTRAINT ck_demo_credential_issuance_key_id CHECK (REGEXP_LIKE(key_id, '^demo[A-Za-z0-9]{4,28}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
