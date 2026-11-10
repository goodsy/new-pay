-- MERCHANT-OPS-002 B2-2 runtime 권한(위치 db/runtime-privilege/admin — 업무 schema V9와 분리, 관례 §9.2·§19). 초기화 스크립트의 procedure만 부른다.
-- 기존 표 권한은 바꾸지 않는다(승인 행·실행 행·대사 발견은 V6 그대로 — 새 종류도 같은 열만 UPDATE). 시험: AdminDatabaseAccountSpec(표 권한 정확 비교, INSERT-only 1142).

-- 가맹점 승인 요청 고정 값: INSERT-only(UPDATE·DELETE·잠금 읽기 1142).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_merchant_ops_command', 'SELECT, INSERT');
