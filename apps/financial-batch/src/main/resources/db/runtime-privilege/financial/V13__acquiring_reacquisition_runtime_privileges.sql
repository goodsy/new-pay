-- ACQUIRING-001 배치2 2-G runtime 권한(D-FFR-002 최소 권한 + D-ACQ-011 "입력 경로 전 재매입 실행 불가"). DELETE 없음. 명세: docs/repository/ACQUIRING-001-ACQUIRING.md §11.

-- acquisition_reacquisition_approval: **SELECT만**. 이번 작업에는 승인 명령을 받는 ADMIN 서명 채널이 없어 운영 코드가 INSERT하지 않는다(검증 Port 운영 구현이 언제나 거부).
-- runtime 계정 INSERT도 1142로 막아 기록 생성을 DB에서도 차단한다. 입력 경로(ACQ-ADMIN-001)가 생길 때 그 작업의 권한 migration이 INSERT를 더한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_reacquisition_approval', 'SELECT');
