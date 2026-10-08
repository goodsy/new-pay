-- 시험 전용(PAYMENT-DBACCOUNT-001, 위치 db/testprivilege/master — 권한 migration은 schema migration과 다른 위치, ARCHITECTURE-CONVENTIONS §9.2). V900 probe 테이블의 runtime 표 단위 권한. runtime 계정은 DB 단위 권한이 없어(role의 표 단위 권한만)
-- 이 GRANT가 없으면 앱 pool이 MASTER DB에 접속조차 못 한다(1044). 운영 권한 migration과 같은 방식으로 초기화 스크립트의 procedure만 부른다
-- (ARCHITECTURE-CONVENTIONS §9 runtime 권한 관례 — MASTER-DOMAIN-001의 첫 업무 테이블도 같은 방식). runtime DML·DDL 경계 시험 대상이라 전체 DML이다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('tech_session_probe', 'SELECT, INSERT, UPDATE, DELETE');
