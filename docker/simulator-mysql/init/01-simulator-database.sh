#!/usr/bin/env bash
# 가상 기관 Simulator 전용 DB와 계정(AUTHN-001 §13.1·AN-19 → AUTH-001 §5.5·AU-20 표 단위 권한). PayCore 소유 DB(MASTER·PAYMENT·FINANCIAL)와 다른 MySQL 인스턴스
# (compose 서비스 simulator-mysql)에 둔다 — PG 재시작·rollback과 무관하게 기관 사실이 남는다(허용 §5.5). PayCore 공유 초기화 스크립트(docker/mysql/init)는 건드리지 않는다.
#   - migration 계정 paycore_simulator_migration: paycore_simulator ALL(GRANT OPTION 없음) + 아래 procedure EXECUTE. Flyway(spring.flyway.user)만 쓴다.
#   - runtime role paycore_simulator_runtime: 표 단위 권한만(DB 단위 권한 없음). 권한은 표를 만든 migration 다음의 권한 migration(db/runtime-privilege/simulator)이
#     procedure로 준다. 가상 VAN 원장(van_ledger_entry)·조회 기록·증거 사용은 append-only라 SELECT, INSERT만 — 원장이 회복 증명(AC1 §6-5)의 기준이다.
#   - runtime 계정 paycore_simulator: role만 가진다(SET DEFAULT ROLE — MySQL 8.4 activate_all_roles_on_login=OFF 기본). 실행할 때마다 paycore_simulator.*의
#     ALL PRIVILEGES·GRANT OPTION을 REVOKE한다 — AUTH-001 이전 볼륨의 runtime 계정은 DB 단위 DML(UPDATE·DELETE 포함)을 가졌다(rev 0.3 m-4).
#   - 권한 부여 procedure paycore_simulator_privilege.set_runtime_table_privileges(table, privileges): PayCore 관례 §9.2(docker/mysql/init/01-owned-databases.sh)와
#     같은 규칙 — SQL SECURITY DEFINER(잠긴 계정 paycore_simulator_grantor@localhost), 받는 쪽 role 고정, 이 DB의 base table만, 허용 집합만, 먼저 GRANT 후 여집합 REVOKE.
#   - 시험: apps/institution-simulator 시험이 이 파일을 그대로 MySQL 8.4.11 컨테이너에 넣는다(SimulatorDatabaseAccountSpec이 SHOW GRANTS·1142·재실행 수렴을 확인).
#   - 빈 volume의 첫 기동에 entrypoint가 실행한다. **기존 Simulator 볼륨에는 자동 실행되지 않는다** — 컨테이너 안에서 이 스크립트를 한 번 직접 실행해 수렴시킨다
#     (`docker compose exec simulator-mysql bash /docker-entrypoint-initdb.d/01-simulator-database.sh`, .env.example). 수렴 전에는 Simulator가 원장 표에 UPDATE/DELETE
#     권한이 남아 있음을 감지하고 기동을 거부한다. 재실행 안전: IF NOT EXISTS, REVOKE IF EXISTS, grantor ACCOUNT LOCK 재적용, procedure 재생성(이미 준 표 권한은 유지).
# mysql entrypoint는 실행 비트가 없으면 source한다 — set -u/pipefail은 쓰지 않는다. 줄바꿈은 LF. 비밀번호는 로컬 개발용(작은따옴표·역슬래시 없음 가정).
set -e

for required in MYSQL_ROOT_PASSWORD PAYCORE_SIMULATOR_DB_PASSWORD PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD; do
    if [ -z "${!required}" ]; then
        echo "01-simulator-database.sh: ${required} is not set" >&2
        exit 1
    fi
done

db=paycore_simulator

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS ${db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE DATABASE IF NOT EXISTS ${db}_privilege;
CREATE USER IF NOT EXISTS '${db}_migration'@'%' IDENTIFIED BY '${PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS '${db}'@'%' IDENTIFIED BY '${PAYCORE_SIMULATOR_DB_PASSWORD}';
CREATE ROLE IF NOT EXISTS '${db}_runtime'@'%';
CREATE USER IF NOT EXISTS '${db}_grantor'@'localhost' ACCOUNT LOCK;
ALTER USER '${db}_grantor'@'localhost' ACCOUNT LOCK;
GRANT SELECT, INSERT, UPDATE, DELETE ON ${db}.* TO '${db}_grantor'@'localhost' WITH GRANT OPTION;

GRANT ALL PRIVILEGES ON ${db}.* TO '${db}_migration'@'%';
REVOKE IF EXISTS GRANT OPTION ON ${db}.* FROM '${db}_migration'@'%';
REVOKE IF EXISTS ALL PRIVILEGES ON ${db}.* FROM '${db}'@'%';
REVOKE IF EXISTS GRANT OPTION ON ${db}.* FROM '${db}'@'%';
GRANT '${db}_runtime'@'%' TO '${db}'@'%';
SET DEFAULT ROLE '${db}_runtime'@'%' TO '${db}'@'%';

DROP PROCEDURE IF EXISTS ${db}_privilege.set_runtime_table_privileges;
DELIMITER //
CREATE DEFINER = '${db}_grantor'@'localhost' PROCEDURE ${db}_privilege.set_runtime_table_privileges(
    IN p_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    IN p_privileges VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin)
    MODIFIES SQL DATA
    SQL SECURITY DEFINER
    COMMENT 'AUTH-001 AU-20: grant-then-revoke-complement replacement of the table-level privileges of the ${db} runtime role on one ${db} base table'
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
FLUSH PRIVILEGES;
SQL
