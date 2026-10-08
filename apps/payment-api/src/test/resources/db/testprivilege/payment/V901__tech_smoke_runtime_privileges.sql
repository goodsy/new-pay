-- 시험 전용(TechSmoke 컨텍스트만). test-only tech_smoke_item(V900, db/testmigration/payment)의 runtime 표 단위 권한.
-- 운영 권한 migration과 같은 방식(ARCHITECTURE-CONVENTIONS §9 runtime 권한 관례): 초기화 스크립트가 만든 procedure만 부른다.
-- db/testmigration/payment와 다른 위치인 이유: jOOQ test 생성기(generateJooqTest)는 그 위치만 적용하고 생성기 컨테이너에는 procedure가 없다.
-- smoke 시험은 JPA save(merge)·jOOQ insert/update/delete 기술 조합을 보므로 전체 DML이다(업무 의미 없음).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('tech_smoke_item', 'SELECT, INSERT, UPDATE, DELETE');
