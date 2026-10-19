-- AUTH-001 AU-16: authorization_recovery_schedule — INSERT(TX1 Attempt 생성과 함께), UPDATE(TXd lease·TX2/회복 결론·조회 횟수·이관, 후보 선점 FOR UPDATE SKIP LOCKED).
-- 행 삭제 경로 없음(결론나면 closed_at).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('authorization_recovery_schedule', 'SELECT, INSERT, UPDATE');
