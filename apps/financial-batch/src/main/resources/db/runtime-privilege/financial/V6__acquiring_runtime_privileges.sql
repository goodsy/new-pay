-- ACQUIRING-001 배치1 runtime 표·열 단위 권한(D-FFR-002 — 새 FINANCIAL 표마다 최소 권한 migration과 권한 시험). V3와 같은 procedure·규칙(받는 쪽 = runtime role 고정,
-- 표 helper 다음에 열 helper). DELETE는 어느 표에도 주지 않는다. 명세: docs/repository/ACQUIRING-001-ACQUIRING.md §11.

-- payment_fact_hold_release(V4 — D-FFR-003 append-only 해제 기록): INSERT = JpaPaymentFactProjectionStore.insertHoldReleases(persist + flush)만,
-- 읽기 = 해제 기록 없는 보류 조회(NOT EXISTS)·매입 조회(RELEASED 여부). Entity는 @Immutable → UPDATE/DELETE 1142.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('payment_fact_hold_release', 'SELECT, INSERT');

-- acquisition_lane: INSERT·UPDATE(version) = JpaAcquisitionLaneStore.lock의 IODKU(`ON DUPLICATE KEY UPDATE version = version`), 잠금 = refresh(PESSIMISTIC_WRITE)
-- = SELECT … FOR UPDATE(열 단위 UPDATE로 충분), UPDATE = markSealed의 변경 감지(last_sealed_business_date·updated_at + @Version version). 식별 열(acquirer_code·environment·
-- created_at)은 updatable=false이고 DB도 1143으로 거부한다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_lane', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.grant_runtime_column_update('acquisition_lane', 'last_sealed_business_date, updated_at, version');

-- acquisition_file: 배치1은 봉인 INSERT와 조회만(JpaAcquisitionFileRepository). 전송·수신·결과 상태 열 UPDATE는 그 전이를 구현하는 배치2 권한 migration이 연다.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_file', 'SELECT, INSERT');

-- 봉인 뒤 바뀌지 않는 append-only 표: 바이트·레코드(membership)·상계·제외. INSERT = 봉인 TX의 persist + flush만.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_file_content', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_file_record', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_netting', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('acquisition_exclusion', 'SELECT, INSERT');
