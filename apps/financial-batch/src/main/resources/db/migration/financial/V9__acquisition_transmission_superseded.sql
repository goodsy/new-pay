-- ACQUIRING-001 배치2 2-D 리뷰 반영(명세 rev 0.6 §4.5·§11): 전송 시도 결과 표 확장.
-- (m-5) lease가 지나 다른 시도가 이어받은 뒤 늦게 끝난 시도도 관찰을 감사 기록으로 남긴다: outcome = SUPERSEDED, observed = 그 시도가 관찰한 결과 코드,
--       resulting_state = 그때 파일의 현재 상태(바꾸지 않는다 — 이어받은 시도가 진행 중이면 TRANSMITTING).
-- (MJ-1·m-1) 사유 추가: LEASE_EXHAUSTED(남은 lease가 rename 안전 여유보다 짧아 rename을 보내지 않음), INTERNAL_ERROR(채널 밖 예외 — 접속 전 NOT_DELIVERED, 뒤 UNKNOWN).
-- 기존 행은 모두 새 CHECK를 만족한다(observed NULL·기존 사유·기존 상태).

ALTER TABLE acquisition_transmission_outcome
    ADD COLUMN observed VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER outcome;

ALTER TABLE acquisition_transmission_outcome DROP CHECK ck_acquisition_transmission_outcome_kind;
ALTER TABLE acquisition_transmission_outcome DROP CHECK ck_acquisition_transmission_outcome_reason;
ALTER TABLE acquisition_transmission_outcome DROP CHECK ck_acquisition_transmission_outcome_state;

ALTER TABLE acquisition_transmission_outcome
    ADD CONSTRAINT ck_acquisition_transmission_outcome_kind CHECK (outcome IN ('DELIVERED', 'NOT_DELIVERED', 'UNKNOWN', 'CONTENT_MISMATCH', 'SUPERSEDED')),
    ADD CONSTRAINT ck_acquisition_transmission_outcome_observed CHECK (
        (outcome = 'SUPERSEDED') = (observed IS NOT NULL)
        AND (observed IS NULL OR observed IN ('DELIVERED', 'NOT_DELIVERED', 'UNKNOWN', 'CONTENT_MISMATCH'))),
    ADD CONSTRAINT ck_acquisition_transmission_outcome_reason CHECK (
        (outcome IN ('NOT_DELIVERED', 'UNKNOWN')) = (reason IS NOT NULL)
        AND (reason IS NULL OR reason IN ('CONNECT_FAILED', 'HOST_KEY_REJECTED', 'AUTHENTICATION_FAILED', 'UPLOAD_FAILED', 'RENAME_REFUSED',
                                          'RENAME_OUTCOME_UNKNOWN', 'VERIFY_FAILED', 'RENAMED_BUT_ABSENT', 'LEASE_EXHAUSTED', 'INTERNAL_ERROR'))),
    ADD CONSTRAINT ck_acquisition_transmission_outcome_state CHECK (
        resulting_state IN ('SEALED', 'TRANSMITTED', 'TRANSMISSION_UNKNOWN', 'TRANSMISSION_QUARANTINED')
        OR (outcome = 'SUPERSEDED' AND resulting_state = 'TRANSMITTING'));
