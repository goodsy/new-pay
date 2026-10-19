-- PAYMENT runtime 표 단위 권한(AUTH-001 명세 §7.2, ARCHITECTURE-CONVENTIONS §9.2). 실제 쓰기 경로만:
-- payment_authorization: INSERT 승인 시작(TX1), UPDATE 상태 전이(TXd·TX2·회복, 잠금 읽기 포함). DELETE 경로 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_authorization', 'SELECT, INSERT, UPDATE');
-- authorization_attempt: INSERT 시작, UPDATE dispatch 상태(version 조건부 갱신 = fencing).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('authorization_attempt', 'SELECT, INSERT, UPDATE');
-- institution_observation: append-only 기관 결과 — INSERT만(@Immutable). UPDATE·DELETE·잠금 읽기 거부(1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('institution_observation', 'SELECT, INSERT');
-- payment_transaction: append-only 확정 금융 사실 — INSERT만(@Immutable).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_transaction', 'SELECT, INSERT');
-- daily_limit_usage: insert-if-absent(INSERT … ON DUPLICATE KEY UPDATE) + 잠금 읽기 + version 갱신.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('daily_limit_usage', 'SELECT, INSERT, UPDATE');
-- limit_reservation: 점유 INSERT, 상태 UPDATE(소비·해제·강제 재점유·정리).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('limit_reservation', 'SELECT, INSERT, UPDATE');
-- incident_hold_release: 해제 쓰기 경로 없음(RECON-CONTRACT-001이 INSERT 권한 migration을 함께 추가) — 복원용 SELECT만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('incident_hold_release', 'SELECT');
