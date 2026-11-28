-- BIZ-CRIT-APPROVAL-001 S3 runtime 권한(위치 db/runtime-privilege/admin — 업무 schema V17과 분리). 기존 표 권한은 바꾸지 않는다(승인 행·실행 행은 V6 그대로 — 새 종류도 같은 열만 UPDATE).
-- 사업자 중요정보 변경 요청 고정 값: INSERT 전용(UPDATE·DELETE 1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_business_change_command', 'SELECT, INSERT');
