#!/usr/bin/env bash
# 소유 DB별 독립 schema와 계정. 각 계정은 자기 DB에만 권한을 가진다(cross-DB 접근 금지).
# collation 주의: 지정하지 않으면 MySQL 8.4 서버 기본값 utf8mb4_0900_ai_ci(대소문자·악센트 무시)가 적용된다.
#   이는 중립이 아니다. D-CP-001(키는 입력 그대로 비교, NO PAD binary)에 따라 PAYMENT migration(V1__payment_core.sql)은 테이블 기본값과
#   키·fingerprint 컬럼마다 utf8mb4_0900_bin을 명시하고 PaymentSchemaSpec이 information_schema와 실제 INSERT로 검사한다.
# 계정 주의: 개발 편의상 앱 계정이 DDL 권한까지 가진다(앱 기동 시 Flyway가 같은 계정으로 migration을 실행한다).
#   CP-DB-001 결정: 운영은 소유 DB마다 migration 계정(해당 DB ALL)과 runtime 계정(SELECT, INSERT, UPDATE, DELETE만)을 분리한다.
#   권한 경계는 DatabaseAccountSeparationSpec이 실제 MySQL에서 확인했다(runtime 계정의 CREATE/ALTER/DROP/TRUNCATE·다른 DB 조회 거부).
#   이 compose 적용(migration 계정 비밀번호 주입, 앱의 spring.flyway.user 분리)은 compose.yaml·.env.example 변경이 필요한 후속 작업이다.
# mysql entrypoint는 실행 비트가 없는 .sh를 source한다. shell 옵션이 entrypoint에 새지 않도록 set -u/pipefail은 쓰지 않는다.
# 비밀번호는 로컬 개발용이며 작은따옴표/역슬래시를 포함하지 않는다고 가정한다.
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS paycore_master;
CREATE DATABASE IF NOT EXISTS paycore_payment;
CREATE DATABASE IF NOT EXISTS paycore_financial;

CREATE USER IF NOT EXISTS 'paycore_master'@'%'    IDENTIFIED BY '${PAYCORE_MASTER_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_payment'@'%'   IDENTIFIED BY '${PAYCORE_PAYMENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_financial'@'%' IDENTIFIED BY '${PAYCORE_FINANCIAL_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON paycore_master.*    TO 'paycore_master'@'%';
GRANT ALL PRIVILEGES ON paycore_payment.*   TO 'paycore_payment'@'%';
GRANT ALL PRIVILEGES ON paycore_financial.* TO 'paycore_financial'@'%';
FLUSH PRIVILEGES;
SQL
