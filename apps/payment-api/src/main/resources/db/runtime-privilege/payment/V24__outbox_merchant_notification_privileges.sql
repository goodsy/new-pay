-- WEBHOOK-001 D-WH-009 V24: outbox_event는 표 단위 SELECT, INSERT 그대로(V2) + 가맹점 알림 표시 두 열만 열 단위 UPDATE.
-- 나머지 열(publish_status·payload·event_id·집계 열…) UPDATE는 1143, DELETE는 1142로 계속 거부된다(DatabaseAccountSeparationSpec).
-- 수용한 부수 효과: 열 UPDATE 권한으로 outbox_event 잠금 읽기(SELECT … FOR UPDATE)가 가능해진다 — 코드 경로에는 없다(fan-out은 guard 직렬화 + PK UPDATE).
-- 이 helper는 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)가 설치한다. 기존 볼륨은 초기화 스크립트를 한 번 다시 실행해야 이 migration이 적용된다(명세 §20).
-- 검토 #1 n-4(2026-10-21 mysql:8.4.11 측정): 이후 migration이 outbox_event에 표 단위 helper를 다시 부르면 열 권한이 바뀐다 —
--   set_runtime_table_privileges('outbox_event', 'SELECT, INSERT')는 여집합 `REVOKE IF EXISTS UPDATE, DELETE`로 **열 단위 UPDATE까지 회수**한다(fan-out 표시가 1143으로 실패 — fail-closed,
--   DatabaseAccountSeparationSpec이 잡는다). 'SELECT, INSERT, UPDATE'는 표 단위 UPDATE를 더해 **모든 열**을 바꿀 수 있게 넓힌다(D-WH-009 위반). outbox_event에는 표 단위 helper를 다시 부르지 않고,
--   필요하면 그 뒤에 이 열 단위 helper를 다시 부른다.
CALL ${flyway:database}_privilege.grant_runtime_column_update('outbox_event', 'merchant_notification_status, merchant_notified_at');
-- merchant_notification_failure: fan-out 실패 기록 INSERT … ON DUPLICATE KEY UPDATE(삭제 경로 없음).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_notification_failure', 'SELECT, INSERT, UPDATE');
