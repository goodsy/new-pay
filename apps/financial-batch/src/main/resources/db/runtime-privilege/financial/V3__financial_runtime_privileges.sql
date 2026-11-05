-- FINANCIAL runtime 표·열 단위 권한(FIN-DBACCOUNT-001, D-FFR-002). 명세: docs/repository/FIN-DBACCOUNT-001-FINANCIAL-DB-ACCOUNTS.md §4.
-- V1(Spring Batch metadata)·V2(결제 사실 Inbox·투영)의 각 표에 앱 runtime 계정이 실제로 쓰는 권한만 준다(PAYMENT-DBACCOUNT-001과 같은 규칙).
--
-- * 이 파일은 runtime 권한 전용 위치(db/runtime-privilege/financial)에 있다. 버전 번호는 업무 schema migration과 같은 순서 공간을 쓴다.
-- * 권한은 초기화 스크립트(docker/mysql/init/01-owned-databases.sh, 운영은 DBA가 같은 내용을 설치)가 만든 procedure로만 준다:
--   `<DB>_privilege.set_runtime_table_privileges`(표 단위 — 그 표의 runtime 권한을 지정 집합으로 **교체**)와
--   `<DB>_privilege.grant_runtime_column_update`(열 단위 UPDATE 추가). 둘 다 받는 쪽을 이 DB의 runtime role로 고정한다. migration 계정에는 GRANT OPTION이 없다.
--   `${flyway:database}`는 Flyway 기본 placeholder(접속한 DB 이름)라 이 파일에는 DB·계정·role 이름이 없다.
-- * 순서 주의(측정 — PAYMENT V24 주석): 표 단위 helper는 여집합 REVOKE로 열 단위 UPDATE까지 회수한다. 그래서 표마다 표 단위 helper를 먼저, 열 단위 helper를 나중에 부른다.
--   이후 migration이 열 권한이 있는 표에 표 단위 helper를 다시 부르면 그 뒤에 열 단위 helper도 다시 불러야 한다.
-- * DELETE는 어느 표에도 주지 않는다. 현재 main 코드·Spring Batch 6.0.5 정상 실행 경로에 DELETE가 없다(Batch의 delete* DAO는 JobRepository.deleteJobInstance/
--   deleteJobExecution을 명시적으로 부를 때만 쓰인다 — 그런 운영 절차는 metadata 보존·정리 정책과 함께 별도 작업이 권한 migration으로 추가한다).
-- * append-only 사실 표에 UPDATE/DELETE가 필요해 보이면 정책 의미 문제이므로 구현하지 않고 Coordinator에 질문한다.

-- ── 결제 사실(V2) ─────────────────────────────────────────────────────────────────────────────────────────────
-- payment_fact_inbox(append-only 수신 증거): INSERT = JpaPaymentFactInboxStore의 persist + flush만. 읽기 = findByEvent·findByEffect(잠금 없음).
-- Entity는 @Immutable·모든 열 updatable=false → UPDATE/DELETE 경로 없음 → DB가 1142로 거부한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_inbox', 'SELECT, INSERT');

-- payment_fact_authorization·payment_fact_cancellation(append-only 확정 사실 투영): INSERT = JpaPaymentFactProjectionStore.insertAuthorization/insertCancellation만.
-- 읽기 = findByPayment(잠금 없음 — 직렬화는 stream 행 잠금). UPDATE/DELETE 경로 없음 → 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_authorization', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_cancellation', 'SELECT, INSERT');

-- payment_fact_hold(append-only 보류): INSERT = insertHolds만. 해제는 이 표를 바꾸지 않는다(D-FFR-003 — 해제 기록은 ACQUIRING-001의 append-only 기록). UPDATE/DELETE → 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_hold', 'SELECT, INSERT');

-- payment_fact_stream(결제별 직렬화 행): INSERT·UPDATE(version) = PaymentFactStreamJpaRepository.insertIfAbsent
-- (INSERT … ON DUPLICATE KEY UPDATE version = version — IODKU는 INSERT와 갱신 열의 UPDATE가 둘 다 필요). 잠금 = lockStream의 refresh(PESSIMISTIC_WRITE)
-- = SELECT … FOR UPDATE(SELECT + UPDATE 권한 필요 — 열 단위 UPDATE로 충분). UPDATE = recordFact의 변경 감지(fact_count·updated_at + @Version version).
-- 신원 열(payment_id·environment·merchant_*·created_at)은 updatable=false이고 DB도 1143으로 거부한다. 삭제 경로 없음.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_stream', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('payment_fact_stream', 'fact_count, updated_at, version');

-- ── Spring Batch metadata(V1, D-ACQ-007 — FINANCIAL 같은 schema) ─────────────────────────────────────────────
-- 근거: spring-batch-core 6.0.5 JDBC DAO SQL(JdbcJobInstanceDao·JdbcJobExecutionDao·JdbcStepExecutionDao·JdbcExecutionContextDao)과
-- spring-jdbc 7.0.9 MySQLMaxValueIncrementer(`update <seq> set ID = last_insert_id(ID + ?) limit 1` 후 `select last_insert_id()`). Batch를 올리면 이 근거를 다시 확인한다.
-- BATCH_JOB_INSTANCE: INSERT(createJobInstance)·SELECT만(UPDATE SQL 없음).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_INSTANCE', 'SELECT, INSERT');
-- BATCH_JOB_EXECUTION: INSERT(saveJobExecution) + UPDATE_JOB_EXECUTION의 SET 열(START_TIME·END_TIME·STATUS·EXIT_CODE·EXIT_MESSAGE·VERSION·CREATE_TIME·LAST_UPDATED).
-- 식별 열(JOB_EXECUTION_ID·JOB_INSTANCE_ID)은 WHERE에서 읽기만 한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_EXECUTION', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_JOB_EXECUTION', 'START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, VERSION, CREATE_TIME, LAST_UPDATED');
-- BATCH_JOB_EXECUTION_PARAMS: INSERT(insertJobParameters)·SELECT만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_EXECUTION_PARAMS', 'SELECT, INSERT');
-- BATCH_STEP_EXECUTION: INSERT(saveStepExecution) + UPDATE_STEP_EXECUTION의 SET 열 15개(helper는 한 번에 8개까지 — 두 번 부른다, 열 권한은 합산).
-- 식별 열(STEP_EXECUTION_ID·STEP_NAME·JOB_EXECUTION_ID·CREATE_TIME)은 바꾸지 않는다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_STEP_EXECUTION', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_STEP_EXECUTION', 'START_TIME, END_TIME, STATUS, COMMIT_COUNT, READ_COUNT, FILTER_COUNT, WRITE_COUNT, EXIT_CODE');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_STEP_EXECUTION', 'EXIT_MESSAGE, VERSION, READ_SKIP_COUNT, PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT, ROLLBACK_COUNT, LAST_UPDATED');
-- BATCH_JOB_EXECUTION_CONTEXT·BATCH_STEP_EXECUTION_CONTEXT: INSERT(saveExecutionContext) + UPDATE_*_EXECUTION_CONTEXT의 SET 열(SHORT_CONTEXT·SERIALIZED_CONTEXT).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_EXECUTION_CONTEXT', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_JOB_EXECUTION_CONTEXT', 'SHORT_CONTEXT, SERIALIZED_CONTEXT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_STEP_EXECUTION_CONTEXT', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_STEP_EXECUTION_CONTEXT', 'SHORT_CONTEXT, SERIALIZED_CONTEXT');
-- BATCH_*_SEQ(순번 표 — 행은 V1이 하나 만든다): MySQLMaxValueIncrementer의 UPDATE(ID)와 ID 읽기만. INSERT·DELETE 없음, UNIQUE_KEY는 바꾸지 않는다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_INSTANCE_SEQ', 'SELECT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_JOB_INSTANCE_SEQ', 'ID');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_JOB_EXECUTION_SEQ', 'SELECT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_JOB_EXECUTION_SEQ', 'ID');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('BATCH_STEP_EXECUTION_SEQ', 'SELECT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('BATCH_STEP_EXECUTION_SEQ', 'ID');
