-- AUTH-001 D-AUTH1-007(Q-S8-1 = A): 전송 게이트 통과(TXd) 때 Route 프로파일(Capability) 버전을 Attempt에 고정한다. 회복은 이 값이 현재 등록부 버전과 같을 때만
-- 무효과 종결(closeIfAbsent)을 요청하고, 다르거나 NULL이면 조회·대사 이관만 한다(fail-closed).
-- NULL 허용: PREPARED(아직 전송 전)와 V14 이전에 전송된 행(버전을 알 수 없음 — 회복이 불일치로 본다). 값은 등록부 기동 검사와 같은 1..64 printable ASCII.
-- 기존 runtime 표 단위 UPDATE 권한(V10)으로 쓴다 — 권한 migration 없음. ALTER 하나(AU-19 — 반쯤 적용되지 않게 새 열과 CHECK를 한 문장으로).
ALTER TABLE authorization_attempt
    ADD COLUMN route_profile_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER unknown_cause,
    ADD CONSTRAINT ck_authorization_attempt_route_profile_version CHECK (
        (state <> 'PREPARED' OR route_profile_version IS NULL)
        AND (route_profile_version IS NULL OR REGEXP_LIKE(route_profile_version, '^[!-~]{1,64}$', 'c')));
