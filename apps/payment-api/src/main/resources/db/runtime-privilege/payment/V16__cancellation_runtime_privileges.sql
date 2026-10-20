-- PAYMENT runtime 표 단위 권한(CANCEL-001 명세 §8.2, ARCHITECTURE-CONVENTIONS §9.2). 쓰기 경로 = JpaPaymentCancellationRepository·JpaReturnBalanceRepository. DELETE 경로 없음.
-- payment_cancellation: INSERT 취소 시작(TX1), UPDATE 상태·사건 수(TXd·TX2·회복, 잠금 읽기 포함).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_cancellation', 'SELECT, INSERT, UPDATE');
-- cancel_attempt: INSERT 시작, UPDATE dispatch 상태·Route 프로파일 버전(version 조건부 갱신 = fencing).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('cancel_attempt', 'SELECT, INSERT, UPDATE');
-- cancel_observation: append-only 기관 취소 결과 — INSERT만(@Immutable). UPDATE·DELETE·잠금 읽기 거부(1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('cancel_observation', 'SELECT, INSERT');
-- return_balance: 첫 취소 시작에서 INSERT(지연 개설), 예약·소비·해제·FORCED 때 합계·revision UPDATE(잠금 읽기).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('return_balance', 'SELECT, INSERT, UPDATE');
-- operator_cancel_rejection: 운영자 명령 거절 감사(D-CANCEL1-006) — append-only, INSERT만(JpaOperatorCancelRejectionStore). UPDATE·DELETE 거부(1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('operator_cancel_rejection', 'SELECT, INSERT');
-- return_reservation: 예약 INSERT, 상태 UPDATE(소비·해제·FORCED 재기록).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('return_reservation', 'SELECT, INSERT, UPDATE');
