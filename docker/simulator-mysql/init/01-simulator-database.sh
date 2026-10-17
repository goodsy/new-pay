#!/usr/bin/env bash
# 가상 인증기관 Simulator 전용 DB와 계정(AUTHN-001 §13.1, AN-19). PayCore 소유 DB(MASTER·PAYMENT·FINANCIAL)와 다른 MySQL 인스턴스(compose 서비스
# simulator-mysql)에 둔다 — PG 재시작·rollback과 무관하게 기관 사실이 남는다(허용 §5.5). PayCore 공유 초기화 스크립트(docker/mysql/init)는 건드리지 않는다.
#   - migration 계정 paycore_simulator_migration: paycore_simulator ALL(GRANT OPTION 없음). Flyway(spring.flyway.user)만 쓴다.
#   - runtime 계정 paycore_simulator: paycore_simulator.* DML(SELECT, INSERT, UPDATE, DELETE)만 — DDL 없음. PayCore §9.2 표 단위 role·procedure는 적용하지
#     않는다(외부 기관 대역 — AUTH-001 가상 VAN 원장 도입 때 append-only 표 권한 재검토).
#   - 시험: apps/institution-simulator 시험이 이 파일을 그대로 MySQL 8.4.11 컨테이너에 넣는다.
#   - 빈 volume의 첫 기동에만 실행된다. 재실행 안전(IF NOT EXISTS, REVOKE IF EXISTS).
# mysql entrypoint는 실행 비트가 없으면 source한다 — set -u/pipefail은 쓰지 않는다. 줄바꿈은 LF. 비밀번호는 로컬 개발용(작은따옴표·역슬래시 없음 가정).
set -e

for required in MYSQL_ROOT_PASSWORD PAYCORE_SIMULATOR_DB_PASSWORD PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD; do
    if [ -z "${!required}" ]; then
        echo "01-simulator-database.sh: ${required} is not set" >&2
        exit 1
    fi
done

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS paycore_simulator CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE USER IF NOT EXISTS 'paycore_simulator_migration'@'%' IDENTIFIED BY '${PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_simulator'@'%' IDENTIFIED BY '${PAYCORE_SIMULATOR_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON paycore_simulator.* TO 'paycore_simulator_migration'@'%';
REVOKE IF EXISTS GRANT OPTION ON paycore_simulator.* FROM 'paycore_simulator_migration'@'%';
REVOKE IF EXISTS ALL PRIVILEGES ON paycore_simulator.* FROM 'paycore_simulator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON paycore_simulator.* TO 'paycore_simulator'@'%';
FLUSH PRIVILEGES;
SQL
