-- PAYMENT runtime 표 단위 권한(PAYMENT-DBACCOUNT-001, D1 / CP-DB-001 review L-3). V1(업무 schema, 적용됨·수정 금지)의 각 테이블에
-- 앱 runtime 계정이 쓰는 권한만 준다. 규칙: docs/repository/ARCHITECTURE-CONVENTIONS.md §9 "runtime 표 단위 권한".
--
-- * 이 파일은 runtime 권한 전용 위치(db/runtime-privilege/payment)에 있다. jOOQ 생성기는 업무 schema 위치(db/migration/payment)만 적용하고
--   생성기 컨테이너에는 아래 procedure가 없기 때문이다. 버전 번호는 업무 schema migration과 같은 순서 공간을 쓴다(테이블 생성 → 다음 버전에 권한).
-- * 권한은 초기화 스크립트(docker/mysql/init/01-owned-databases.sh, 운영은 DBA가 같은 내용을 설치)가 만든 procedure
--   `<DB>_privilege.set_runtime_table_privileges`로만 준다. procedure는 받는 쪽을 이 DB의 runtime role로 고정하고 허용 집합만 받는다.
--   migration 계정에는 GRANT OPTION이 없다. `${flyway:database}`는 Flyway 기본 placeholder(접속한 DB 이름)라 이 파일에는 DB·runtime 계정·
--   role 이름이 없다. 호출은 그 표의 runtime 권한을 지정 집합으로 **교체**한다(재실행 안전).
-- * SELECT는 모든 업무 표에 준다(조회·잠금 읽기). 쓰기 권한은 현재 main 코드의 실제 쓰기 경로에서 도출했다. DELETE 경로는 어느 표에도 없다.
--   새 쓰기 경로(예: Outbox Relay의 published 갱신, 멱등 기록 30일 삭제 Job)는 그 작업이 새 권한 migration으로 추가한다.
--   append-only 확정 사실 표에 UPDATE/DELETE가 필요해 보이면 정책 의미 문제이므로 구현하지 않고 Coordinator에 질문한다.

-- payment: INSERT = JpaPaymentStore.insertNew(persist). UPDATE = JpaPaymentStore.update(status 변경, @Version 증가,
-- 종료 근거만 늘 때 PESSIMISTIC_FORCE_INCREMENT) + PaymentJpaRepository.lockById(SELECT … FOR UPDATE는 SELECT와 UPDATE 권한이 필요).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment', 'SELECT, INSERT, UPDATE');

-- payment_termination_evidence(append-only 확정 사실, D-CPD-002): INSERT = JpaPaymentStore.update의 새 근거 persist만.
-- 읽기는 findByPaymentIdOrdered(잠금 없음). UPDATE/DELETE 경로 없음 → DB가 1142로 거부한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_termination_evidence', 'SELECT, INSERT');

-- order_payment_control(주문 슬롯, 영구 보존): INSERT·UPDATE = OrderPaymentControlJpaRepository.insertIfAbsent
-- (INSERT … ON DUPLICATE KEY UPDATE version = version은 두 권한 모두 필요). UPDATE = lockById(FOR UPDATE)와
-- JpaOrderPaymentControlStore.update(current_payment_id·generation·authorized_payment_id·updated_at, @Version, FORCE_INCREMENT).
-- 행 삭제 경로 없음(슬롯은 영구 보존) → DELETE 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('order_payment_control', 'SELECT, INSERT, UPDATE');

-- incident_hold(append-only 사건 보류): INSERT = JpaOrderPaymentControlStore.update의 새 hold persist만. 읽기는 findByOrder(잠금 없음).
-- UPDATE/DELETE 경로 없음 → DB가 1142로 거부한다(보류 해제는 미구현 — 해제가 필요해지면 정책 질문 대상).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('incident_hold', 'SELECT, INSERT');

-- idempotency_request: INSERT·UPDATE = IdempotencyRequestJpaRepository.insertPendingIfAbsent(INSERT … ON DUPLICATE KEY UPDATE).
-- UPDATE = lockByScope(FOR UPDATE), JpaIdempotencyRecordStore.renewExpired(만료 행 갱신)·complete(결과 기록).
-- 30일 경과 행 삭제 Job은 미구현(후속 작업이 DELETE 권한 migration을 추가) → DELETE 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('idempotency_request', 'SELECT, INSERT, UPDATE');

-- outbox_event: INSERT = JpaOutboxStore.append(persist)만. Relay(lease·published 표시 UPDATE)는 별도 작업이 UPDATE 권한을 추가한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('outbox_event', 'SELECT, INSERT');
