-- MERCHANT-FLOW-001 FLOW-3(명세 rev 0.4e §4B.8·§4B.10 FLOW-3 migration — BR-BIZ-337): 가맹점 등록 근거 기록 표. 마지막 번호 V23(FLOW-2c) 다음이라 V24, runtime 권한은 V25.
-- * INSERT 전용(runtime SELECT, INSERT만 — V25). 가맹점당 1행(PK). 기록 시점 사실 — 뒤의 정지·해지·만료·새 버전에 바뀌지 않는다.
-- * 근거 = (사업자, 계약 순번, 버전 번호) — 가맹점과 같은 사업자(merchant (merchant_id, business_id) 복합 FK)이고 그 버전이 실제로 있어야 한다(버전 복합 FK).
-- * 기존 가맹점은 근거 행을 만들지 않는다(근거는 관문 이후 등록부터 — 과거 등록에 근거를 꾸며 넣지 않는다). 화면은 행이 없으면 "관문 전 등록"으로 표시한다.
-- * 판정·권리·Snapshot·Outbox에 쓰지 않는다(MerchantRegistered 이력·Outbox payload는 바뀌지 않는다 — PAYMENT golden 불변).
CREATE TABLE merchant_registration_basis (
    merchant_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    business_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    contract_seq INT         NOT NULL,
    version_no   INT         NOT NULL,
    recorded_at  DATETIME(3) NOT NULL,
    recorded_by  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    CONSTRAINT pk_merchant_registration_basis PRIMARY KEY (merchant_id),
    CONSTRAINT fk_mrb_merchant FOREIGN KEY (merchant_id, business_id) REFERENCES merchant (merchant_id, business_id),
    CONSTRAINT fk_mrb_version FOREIGN KEY (business_id, contract_seq, version_no) REFERENCES business_contract_version (business_id, contract_seq, version_no),
    CONSTRAINT ck_mrb_seq CHECK (contract_seq >= 1 AND version_no >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
