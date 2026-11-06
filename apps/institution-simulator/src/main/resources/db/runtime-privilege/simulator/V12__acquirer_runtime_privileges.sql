-- 가상 매입사 원장 runtime 권한(ACQUIRING-001 2-E §7 — 권한만). V4·V7·V9와 같은 procedure로만 준다(받는 쪽 = runtime role 고정, 허용 집합만, 재실행 안전). DELETE 없음.
-- 기관 사실(append-only): INSERT = JpaAcquirerReceiptStore·JpaAcquirerPublicationStore의 persist + flush만. UPDATE/DELETE → DB가 1142로 거부한다
-- (AcquirerLedgerPrivilegeGuard 기동 탐침).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquirer_received_file', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquirer_record_decision', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquirer_result_line', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquirer_outbound_file', 'SELECT, INSERT');

-- Worker 진행 표: 표 단위 SELECT, INSERT + 게시 전이(JpaAcquirerPublicationStore.markPublished — 변경 감지 + @Version)가 바꾸는 열만 UPDATE.
-- 식별·계획 열(receipt_id·kind·seq·file_name·due_at·created_at)은 1143, DELETE는 1142. 열 권한 procedure는 docker/simulator-mysql/init(2-E에서 추가) —
-- 기존 Simulator 볼륨은 init 스크립트를 한 번 다시 실행해야 이 migration이 돈다(.env.example). 같은 표에 표 단위 procedure를 다시 부르지 않는다(열 권한 회수 방지).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquirer_work_item', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('acquirer_work_item', 'state, published_at, updated_at, version');
