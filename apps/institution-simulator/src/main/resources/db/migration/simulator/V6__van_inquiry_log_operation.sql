-- 조회 기록 operation(CANCEL-001 §6.5 CN-26, rev 0.3 n-6: 기존 표 ALTER만). NULL = V6 이전 승인 조회 행(기존 행 보존, 뒤채움 없음). 새 행은 AUTHORIZE 또는 CANCEL.
ALTER TABLE van_inquiry_log
    ADD COLUMN operation VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER result,
    ADD CONSTRAINT ck_van_inquiry_log_operation CHECK (operation IS NULL OR operation IN ('AUTHORIZE', 'CANCEL'));
