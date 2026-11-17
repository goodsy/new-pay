-- MERCHANT-CODE-001 MC-4(§6 — D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8): 권리 투영 head에 적용한 Snapshot v4의 가맹점 코드(출력 전용 표시값 — 웹훅·결제 응답),
-- 결제 생성 멱등 결과에 그 요청 때의 가맹점 코드(C-37(MC-4) — D-CP-003 "저장된 생성 결과만": 재생 응답은 이 저장값만 쓴다).
-- 번호: schema V35(ADMIN-HOME-KPI-001) 다음(같은 Flyway 이력, MERCHANT-CODE-001 rev 0.4.11 §1.4 정정).
-- 권한 migration 없음: entitlement_head·idempotency_request는 표 단위 SELECT, INSERT, UPDATE(runtime-privilege V4:8·V2:35) — 새 열도 그 권한으로 쓰고 읽는다.
-- NULL 허용: v1~v3 head·빈 head(revision 0)는 코드가 없다(외부 필드 생략). 기존 head 행은 NULL로 시작하고 MASTER v4 승격 발행(revision + 1)이 채운다.
-- 기존 멱등 기록(MC-4 이전)·코드 없는 MID의 기록·다른 operation 행은 NULL — 재생 때도 필드를 생략한다.
-- 데이터·권리 판정 열 변경 없음(금액·상태·멱등 키·주문 키·요청 지문 의미 불변).
-- 문장별 ALGORITHM을 명시한다(MC-3a V15 선례 — MySQL이 더 무거운 방식으로 조용히 바꾸지 못하게, 지원되지 않으면 오류로 거절). 시험 SchemaRolloutOrderV4Spec이 원문을 고정한다.
-- 운영 적용 창: MERCHANT-CODE-001 L-14(rev 0.4.12 — 표 크기별 예상 시간·잠금).

-- (1) entitlement_head 열 추가: 메타데이터만(행 재작성 없음).
ALTER TABLE entitlement_head
    ADD COLUMN merchant_code VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM=INSTANT;

-- (2) entitlement_head 형식·채움 불변식(MASTER V13 merchant_code_assignment CHECK와 같은 문법, 빈 head에는 코드 없음). CHECK 추가는 기존 행 검증을 위해 표 복사(DML 차단 —
-- entitlement_head는 가맹점당 한 행이라 작다. 운영 적용 시간·잠금은 L-14 유지보수 창).
ALTER TABLE entitlement_head
    ADD CONSTRAINT ck_entitlement_head_merchant_code CHECK (
        merchant_code IS NULL OR (revision >= 1 AND REGEXP_LIKE(merchant_code, '^[a-z][a-z0-9]{5,13}$', 'c'))),
    ALGORITHM=COPY;

-- (3) idempotency_request 열 추가: 메타데이터만(행 재작성 없음 — 요청마다 쌓이는 큰 표라 COPY를 쓰지 않는다). CHECK는 두지 않는다(추가하면 표 복사·DML 차단):
-- 형식은 쓰는 쪽(값의 출처가 V36 CHECK가 지키는 entitlement_head.merchant_code)과 읽는 쪽(StoredRequestResult.Succeeded 형식 require — 결함이면 fail-closed)이 지킨다.
ALTER TABLE idempotency_request
    ADD COLUMN result_merchant_code VARCHAR(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM=INSTANT;
