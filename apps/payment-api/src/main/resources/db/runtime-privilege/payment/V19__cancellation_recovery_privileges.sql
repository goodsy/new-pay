-- CANCEL-001 CN-24: cancellation_recovery_schedule — INSERT(TX1 취소 Attempt와 함께), UPDATE(TXd lease·TX2/회복 결론·조회 수·이관, 후보 선점 FOR UPDATE SKIP LOCKED).
-- 행 삭제 경로 없음(결론나면 closed_at). 쓰기 경로 = JpaCancellationRecoveryScheduleStore.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('cancellation_recovery_schedule', 'SELECT, INSERT, UPDATE');
