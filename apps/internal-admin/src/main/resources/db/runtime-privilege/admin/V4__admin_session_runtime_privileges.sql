-- ADMIN-BOOT-001 §4.2.2 Spring Session JDBC 표의 runtime 권한: 세션 생성·갱신·속성 교체·로그아웃·만료 정리(DELETE)라 전체 DML.
-- 업무 사실이 아니다(권위는 계정 행의 session_generation — §4.4.2). 열 단위 권한 표가 아니므로 표 단위 helper만 쓴다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_session', 'SELECT, INSERT, UPDATE, DELETE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_session_attributes', 'SELECT, INSERT, UPDATE, DELETE');
