-- 기관 식별 코드 공통 형식(INST-CODE-001, D-INST-CODE-001): 카드사(매입사)/Route 개통의 매입사·Route 코드는 `^[A-Z0-9]{1,32}$`(하이픈·밑줄 금지).
-- 명세: docs/repository/INST-CODE-001-INSTITUTION-CODE-FORMAT.md §5. 적용된 V1~V6은 고치지 않는다(새 버전만).
-- 한 ALTER 문이라 원자적이다: 기존 행이 새 CHECK를 어기면 문장 전체가 실패하고 V5 CHECK가 그대로 남는다(제약 없는 중간 상태 없음).
-- 기존 행 자동 변환은 하지 않는다(개통 키·Snapshot 근거 보존). 형식 밖 행이 있는 비운영 DB는 초기화 후 다시 seed한다. route_ref 열 길이(64)는 그대로 — CHECK가 32자로 좁힌다.
-- 새 이름(_code 접미사)은 같은 문장에서 같은 이름을 지우고 다시 만드는 동작에 기대지 않기 위해서다. runtime 권한 변화 없음.
ALTER TABLE card_route_activation
    DROP CHECK ck_card_route_activation_acquirer,
    DROP CHECK ck_card_route_activation_route,
    ADD CONSTRAINT ck_card_route_activation_acquirer_code CHECK (REGEXP_LIKE(acquirer_code, '^[A-Z0-9]{1,32}$', 'c')),
    ADD CONSTRAINT ck_card_route_activation_route_code CHECK (REGEXP_LIKE(route_ref, '^[A-Z0-9]{1,32}$', 'c'));
