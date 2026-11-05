-- 시험 전용(FIN-DBACCOUNT-001, 위치 db/testprivilege/financial — 권한 migration은 schema migration과 다른 위치, master-api V901과 같은 관례).
-- V900 기술 smoke 출력 표(tech_smoke_batch_out)의 runtime 표 단위 권한. runtime 계정은 DB 단위 권한이 없어(role의 표·열 단위 권한만) 이 GRANT가 없으면
-- Batch 재시작 smoke의 writer INSERT와 시험 준비 DELETE가 1142로 실패한다. 업무 의미 없는 시험 표라 전체 DML이다(운영 jar에 들어가지 않는다).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('tech_smoke_batch_out', 'SELECT, INSERT, UPDATE, DELETE');
