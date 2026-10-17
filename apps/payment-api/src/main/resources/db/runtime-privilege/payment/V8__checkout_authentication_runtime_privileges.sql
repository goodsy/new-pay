-- PAYMENT runtime 표 단위 권한(AUTHN-001 명세 §10.2, ARCHITECTURE-CONVENTIONS §9.2). 실제 쓰기 경로만:
-- checkout_token: INSERT 발급, UPDATE 소비·대체·만료·닫힘(잠금 읽기 포함). 삭제 Job 없음 → DELETE 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('checkout_token', 'SELECT, INSERT, UPDATE');
-- checkout_session: INSERT 교환, UPDATE 완료·취소·대체·만료.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('checkout_session', 'SELECT, INSERT, UPDATE');
-- authentication_session: INSERT 시작, UPDATE 전이(+ AUTH-001 귀속).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('authentication_session', 'SELECT, INSERT, UPDATE');
-- authentication_session_event: append-only 결정 로그 — INSERT만(@Immutable). UPDATE·DELETE·잠금 읽기 거부(1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('authentication_session_event', 'SELECT, INSERT');
-- payment_checkout_window_pin: V7 뒤 첫 기동의 채우기 기준 한 행 INSERT, 이후 읽기만(D-AUTHN-007, m-B).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_checkout_window_pin', 'SELECT, INSERT');
-- payment의 새 컬럼(checkout_expires_at·checkout_validity_version)은 기존 표 단위 INSERT·UPDATE 권한(V2)으로 쓴다(새 Payment INSERT, V7 이전 행 채우기 UPDATE).
