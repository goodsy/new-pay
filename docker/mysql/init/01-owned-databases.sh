#!/usr/bin/env bash
# 소유 DB별 독립 schema와 계정. 각 계정은 자기 DB에만 권한을 가진다(cross-DB 접근 금지).
# collation 주의: 지정하지 않으면 MySQL 8.4 서버 기본값 utf8mb4_0900_ai_ci(대소문자·악센트 무시)가 적용된다.
#   이는 중립이 아니다. D-CP-001(키는 입력 그대로 비교, NO PAD binary)에 따라 PAYMENT migration(V1__payment_core.sql)은 테이블 기본값과
#   키·fingerprint 컬럼마다 utf8mb4_0900_bin을 명시하고 PaymentSchemaSpec이 information_schema와 실제 INSERT로 검사한다.
#
# 계정·권한(CP-DB-001 계정 분리 → MASTER-BOOT-001 적용 → PAYMENT-DBACCOUNT-001 표 단위 runtime 권한). MASTER·PAYMENT 공통(<db> = 소유 DB):
#   - migration 계정 <db>_migration: 자기 DB ALL PRIVILEGES(GRANT OPTION 없음) + 아래 procedure EXECUTE. Flyway(spring.flyway.user)만 쓴다.
#   - runtime role <db>_runtime: 표 단위 권한만 받는다. DB 단위(<db>.*) 권한은 없다. 권한은 테이블을 만든 migration 다음의 권한 migration이
#     아래 procedure로 준다(append-only 확정 사실 표는 SELECT, INSERT만).
#   - runtime 계정 <db>: role <db>_runtime만 가진다(기본 role — MySQL 8.4는 activate_all_roles_on_login=OFF가 기본이라 SET DEFAULT ROLE이
#     없으면 접속 세션에 role이 활성화되지 않는다). 앱 DataSource가 쓴다. DB 단위 권한은 하나도 없다: 실행할 때마다 <db>.*의 ALL PRIVILEGES와
#     GRANT OPTION을 REVOKE한다(권한은 합산이다. MASTER-BOOT-001 이전 volume의 runtime 계정은 <db>.* ALL — DDL로 append-only 표를 DROP할 수
#     있었다). 표 단위 권한(role)은 건드리지 않는다. migration 계정도 <db>.*의 GRANT OPTION을 REVOKE해 'ALL(GRANT OPTION 없음)'으로 끝낸다.
#   - 권한 부여 procedure <db>_privilege.set_runtime_table_privileges(table, privileges): migration이
#     `CALL ${flyway:database}_privilege.set_runtime_table_privileges(...)`로 부른다(DB·계정·role 이름을 migration에 쓰지 않는다).
#     SQL SECURITY DEFINER이고 DEFINER는 잠긴(ACCOUNT LOCK, 접속 불가) 계정 <db>_grantor@localhost다. 이 계정만 <db> DML에 GRANT OPTION을 가진다.
#     procedure는 (1) 받는 쪽을 <db>_runtime role로 고정하고(인자 없음) (2) <db>의 base table만(이름 ^[a-z][a-z0-9_]{0,63}$, 줄바꿈 문자 거부,
#     flyway_schema_history·view 거부) (3) 허용 집합 'SELECT' | 'SELECT, INSERT' | 'SELECT, INSERT, UPDATE' | 'SELECT, INSERT, UPDATE, DELETE'만 받아
#     그 표의 runtime 권한을 그 집합으로 교체한다: 먼저 집합을 GRANT하고 그다음 나머지 DML(여집합)만 REVOKE IF EXISTS한다 — 운영 중 재실행해도
#     유지되는 권한이 잠시 사라지는 틈이 없다(재실행 안전).
#     migration 계정에 GRANT OPTION을 주지 않는 이유: MySQL GRANT OPTION은 받는 계정을 제한할 수 없어 자기 DB 권한을 다른 DB 계정 등 아무 계정에나
#     줄 수 있다. procedure를 <db> 밖(<db>_privilege)에 두는 이유: (a) Flyway는 routine이 있는 schema를 비어 있지 않다고 보고 첫 migrate를 거부한다
#     (b) migration 계정이 <db>_privilege에는 EXECUTE만 있어 procedure를 지우거나 바꿀 수 없다.
#   - 시험: apps/master-api MasterDatabaseAccountSpec과 apps/payment-api DatabaseAccountSeparationSpec이 이 파일을 그대로 MySQL 8.4.11 컨테이너에
#     넣어 SHOW GRANTS 전체, runtime의 DDL·append-only 표 UPDATE/DELETE 거부(1142), migration 계정의 외부 계정 GRANT 거부를 확인한다.
#   - FINANCIAL은 Batch metadata 위치·권한(Q-BATCH-META) 답변 전이라 기존 단일 계정(ALL)을 유지한다.
#   - 이 스크립트는 데이터 디렉터리가 빈 첫 기동에 entrypoint가 실행한다. 기존 compose volume에는 다시 실행되지 않으므로 volume을 재생성하거나
#     실행 중인 컨테이너에서 이 스크립트를 한 번 직접 실행한다(재실행 안전: IF NOT EXISTS, runtime 계정의 DB 단위 ALL·GRANT OPTION과
#     migration 계정의 DB 단위 GRANT OPTION REVOKE IF EXISTS, grantor ACCOUNT LOCK 재적용, procedure 재생성 — 이미 준 표 단위 권한은 유지된다).
#     방법은 .env.example. 운영 DB에 DBA가 설치할 때도 같은 SQL(특히 REVOKE와 ALTER USER … ACCOUNT LOCK)을 그대로 쓴다.
#
# mysql entrypoint는 실행 비트가 있으면 실행하고, 없으면 source한다. shell 옵션이 entrypoint에 새지 않도록 set -u/pipefail은 쓰지 않는다.
# 줄바꿈은 LF여야 한다(.gitattributes *.sh eol=lf). CRLF면 'bash\r'를 찾지 못해 초기화가 실패한다.
# 비밀번호는 로컬 개발용이며 작은따옴표/역슬래시를 포함하지 않는다고 가정한다.
set -e

for required in MYSQL_ROOT_PASSWORD \
    PAYCORE_MASTER_DB_PASSWORD PAYCORE_MASTER_DB_MIGRATION_PASSWORD \
    PAYCORE_PAYMENT_DB_PASSWORD PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD \
    PAYCORE_FINANCIAL_DB_PASSWORD; do
    if [ -z "${!required}" ]; then
        echo "01-owned-databases.sh: ${required} is not set" >&2
        exit 1
    fi
done

# 소유 DB 하나(<db>)의 runtime role·grantor·권한 부여 procedure. migration·runtime 계정은 먼저 만들어져 있어야 한다.
runtime_privilege_sql() {
    local db="$1"
    cat <<SQL
CREATE DATABASE IF NOT EXISTS ${db}_privilege;
CREATE ROLE IF NOT EXISTS '${db}_runtime'@'%';
CREATE USER IF NOT EXISTS '${db}_grantor'@'localhost' ACCOUNT LOCK;
ALTER USER '${db}_grantor'@'localhost' ACCOUNT LOCK;
GRANT SELECT, INSERT, UPDATE, DELETE ON ${db}.* TO '${db}_grantor'@'localhost' WITH GRANT OPTION;

REVOKE IF EXISTS ALL PRIVILEGES ON ${db}.* FROM '${db}'@'%';
REVOKE IF EXISTS GRANT OPTION ON ${db}.* FROM '${db}'@'%';
REVOKE IF EXISTS GRANT OPTION ON ${db}.* FROM '${db}_migration'@'%';
GRANT '${db}_runtime'@'%' TO '${db}'@'%';
SET DEFAULT ROLE '${db}_runtime'@'%' TO '${db}'@'%';

DROP PROCEDURE IF EXISTS ${db}_privilege.set_runtime_table_privileges;
DELIMITER //
CREATE DEFINER = '${db}_grantor'@'localhost' PROCEDURE ${db}_privilege.set_runtime_table_privileges(
    IN p_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    IN p_privileges VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin)
    MODIFIES SQL DATA
    SQL SECURITY DEFINER
    COMMENT 'PAYMENT-DBACCOUNT-001: grant-then-revoke-complement replacement of the table-level privileges of the ${db} runtime role on one ${db} base table'
BEGIN
    IF p_privileges IS NULL
        OR p_privileges NOT IN ('SELECT', 'SELECT, INSERT', 'SELECT, INSERT, UPDATE', 'SELECT, INSERT, UPDATE, DELETE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'runtime privileges must be one of the allowed sets';
    END IF;
    IF p_table IS NULL
        OR NOT REGEXP_LIKE(p_table, '^[a-z][a-z0-9_]{0,63}\$', 'c')
        OR LOCATE(CHAR(10 USING ascii), p_table) > 0
        OR LOCATE(CHAR(13 USING ascii), p_table) > 0
        OR p_table = 'flyway_schema_history'
        OR NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                       WHERE TABLE_SCHEMA = '${db}' AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'runtime privileges can be set only on a base table of the owned database';
    END IF;
    -- 먼저 목표 집합을 GRANT(이미 있으면 그대로)하고, 그다음 목표에 없는 DML만 회수한다. 유지되는 권한은 한순간도 사라지지 않는다.
    SET @paycore_runtime_privilege_sql =
        CONCAT('GRANT ', p_privileges, ' ON \`${db}\`.\`', p_table, '\` TO \'${db}_runtime\'@\'%\'');
    PREPARE paycore_runtime_privilege_stmt FROM @paycore_runtime_privilege_sql;
    EXECUTE paycore_runtime_privilege_stmt;
    DEALLOCATE PREPARE paycore_runtime_privilege_stmt;
    SET @paycore_runtime_privilege_complement = CASE p_privileges
        WHEN 'SELECT' THEN 'INSERT, UPDATE, DELETE'
        WHEN 'SELECT, INSERT' THEN 'UPDATE, DELETE'
        WHEN 'SELECT, INSERT, UPDATE' THEN 'DELETE'
        ELSE NULL
    END;
    IF @paycore_runtime_privilege_complement IS NOT NULL THEN
        SET @paycore_runtime_privilege_sql =
            CONCAT('REVOKE IF EXISTS ', @paycore_runtime_privilege_complement, ' ON \`${db}\`.\`', p_table,
                   '\` FROM \'${db}_runtime\'@\'%\'');
        PREPARE paycore_runtime_privilege_stmt FROM @paycore_runtime_privilege_sql;
        EXECUTE paycore_runtime_privilege_stmt;
        DEALLOCATE PREPARE paycore_runtime_privilege_stmt;
    END IF;
    SET @paycore_runtime_privilege_complement = NULL;
    SET @paycore_runtime_privilege_sql = NULL;
END //
DELIMITER ;
GRANT EXECUTE ON PROCEDURE ${db}_privilege.set_runtime_table_privileges TO '${db}_grantor'@'localhost';
GRANT EXECUTE ON PROCEDURE ${db}_privilege.set_runtime_table_privileges TO '${db}_migration'@'%';
SQL
}

# WEBHOOK-001 D-WH-009: 열 단위 UPDATE 부여 procedure <db>_privilege.grant_runtime_column_update(table, columns). 지금은 PAYMENT에만 설치한다
# (outbox_event의 가맹점 알림 표시 두 열 — 나머지 열 UPDATE는 1143으로 계속 거부, DELETE는 1142). set_runtime_table_privileges와 같은 방어:
# DEFINER = 잠긴 grantor, 받는 쪽 = <db>_runtime role 고정, 표는 <db>의 base table만(flyway_schema_history 거부), 열 목록은 소문자 이름을 ", "로 이은 1~8개이고
# 모두 그 표에 실제로 있어야 한다(아니면 SIGNAL 1644). 부여만 한다(같은 열 재부여는 그대로 — 재실행 안전). 열 권한 회수는 DBA 절차다.
# 주의(측정): 같은 표에 set_runtime_table_privileges를 나중에 부르면 여집합 REVOKE UPDATE가 열 단위 UPDATE도 회수하고, 'SELECT, INSERT, UPDATE'는 모든 열로 넓힌다 —
# 열 권한이 있는 표에는 표 단위 helper를 다시 쓰지 않는다(V24 주석).
runtime_column_privilege_sql() {
    local db="$1"
    cat <<SQL
DROP PROCEDURE IF EXISTS ${db}_privilege.grant_runtime_column_update;
DELIMITER //
CREATE DEFINER = '${db}_grantor'@'localhost' PROCEDURE ${db}_privilege.grant_runtime_column_update(
    IN p_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    IN p_columns VARCHAR(600) CHARACTER SET ascii COLLATE ascii_bin)
    MODIFIES SQL DATA
    SQL SECURITY DEFINER
    COMMENT 'WEBHOOK-001 D-WH-009: grant column-level UPDATE on existing columns of one ${db} base table to the ${db} runtime role'
BEGIN
    IF p_table IS NULL
        OR NOT REGEXP_LIKE(p_table, '^[a-z][a-z0-9_]{0,63}\$', 'c')
        OR LOCATE(CHAR(10 USING ascii), p_table) > 0
        OR LOCATE(CHAR(13 USING ascii), p_table) > 0
        OR p_table = 'flyway_schema_history'
        OR NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                       WHERE TABLE_SCHEMA = '${db}' AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'column privileges can be granted only on a base table of the owned database';
    END IF;
    IF p_columns IS NULL
        OR NOT REGEXP_LIKE(p_columns, '^[a-z][a-z0-9_]{0,63}(, [a-z][a-z0-9_]{0,63}){0,7}\$', 'c')
        OR LOCATE(CHAR(10 USING ascii), p_columns) > 0
        OR LOCATE(CHAR(13 USING ascii), p_columns) > 0
        OR (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = '${db}' AND TABLE_NAME = p_table AND FIND_IN_SET(COLUMN_NAME, REPLACE(p_columns, ', ', ',')) > 0)
           <> (CHAR_LENGTH(p_columns) - CHAR_LENGTH(REPLACE(p_columns, ',', '')) + 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'column privileges need existing lower-case column names of that table';
    END IF;
    SET @paycore_runtime_column_sql =
        CONCAT('GRANT UPDATE (', p_columns, ') ON \`${db}\`.\`', p_table, '\` TO \'${db}_runtime\'@\'%\'');
    PREPARE paycore_runtime_column_stmt FROM @paycore_runtime_column_sql;
    EXECUTE paycore_runtime_column_stmt;
    DEALLOCATE PREPARE paycore_runtime_column_stmt;
    SET @paycore_runtime_column_sql = NULL;
END //
DELIMITER ;
GRANT EXECUTE ON PROCEDURE ${db}_privilege.grant_runtime_column_update TO '${db}_grantor'@'localhost';
GRANT EXECUTE ON PROCEDURE ${db}_privilege.grant_runtime_column_update TO '${db}_migration'@'%';
SQL
}

{
cat <<SQL
CREATE DATABASE IF NOT EXISTS paycore_master;
CREATE DATABASE IF NOT EXISTS paycore_payment;
CREATE DATABASE IF NOT EXISTS paycore_financial;

CREATE USER IF NOT EXISTS 'paycore_master_migration'@'%'  IDENTIFIED BY '${PAYCORE_MASTER_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_master'@'%'            IDENTIFIED BY '${PAYCORE_MASTER_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_payment_migration'@'%' IDENTIFIED BY '${PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_payment'@'%'           IDENTIFIED BY '${PAYCORE_PAYMENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_financial'@'%'         IDENTIFIED BY '${PAYCORE_FINANCIAL_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON paycore_master.* TO 'paycore_master_migration'@'%';
GRANT ALL PRIVILEGES ON paycore_payment.* TO 'paycore_payment_migration'@'%';
GRANT ALL PRIVILEGES ON paycore_financial.* TO 'paycore_financial'@'%';
SQL
runtime_privilege_sql paycore_master
runtime_privilege_sql paycore_payment
runtime_column_privilege_sql paycore_payment
} | mysql -uroot -p"${MYSQL_ROOT_PASSWORD}"
