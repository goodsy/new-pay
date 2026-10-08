#!/usr/bin/env bash
# 소유 DB별 독립 schema와 계정. 각 계정은 자기 DB에만 권한을 가진다(cross-DB 접근 금지).
# collation 주의: 지정하지 않으면 MySQL 8.4 서버 기본값 utf8mb4_0900_ai_ci(대소문자·악센트 무시)가 적용된다.
#   이는 중립이 아니다. D-CP-001(키는 입력 그대로 비교, NO PAD binary)에 따라 PAYMENT migration(V1__payment_core.sql)은 테이블 기본값과
#   키·fingerprint 컬럼마다 utf8mb4_0900_bin을 명시하고 PaymentSchemaSpec이 information_schema와 실제 INSERT로 검사한다.
#
# 계정 분리(CP-DB-001 결정, MASTER-BOOT-001에서 MASTER·PAYMENT에 적용):
#   - migration 계정 <db>_migration: 자기 DB ALL PRIVILEGES. Flyway(spring.flyway.user/password)만 쓴다.
#   - runtime 계정 <db>: 자기 DB SELECT, INSERT, UPDATE, DELETE만. 앱 DataSource가 쓴다(DDL 불가).
#   - 한계(현재 상태): runtime 계정 권한은 DB 단위 DML이다. 따라서 PAYMENT의 확정 금융 사실(append-only) 테이블
#     (payment_termination_evidence, incident_hold 등)에 대한 UPDATE/DELETE 금지는 DB 권한으로 강제되지 않는다(애플리케이션 규칙만).
#     이 파일에서 표 단위로 좁힐 수 없는 이유: MySQL 권한은 더해지기만 해서 DB 단위 GRANT에서 표 하나를 뺄 수 없고, 이 스크립트는
#     Flyway가 테이블을 만들기 전에 실행되며, migration 계정은 GRANT OPTION이 없어 migration 안에서 GRANT를 할 수도 없다.
#   - 정한 방향(D1, 후속 PAYMENT-DBACCOUNT-001): runtime 계정의 DB 단위 DML을 없애고, migration 계정에 자기 DB 한정 GRANT OPTION을
#     주며, 테이블을 만드는 각 migration이 runtime 계정에 표 단위 권한을 준다(append-only 표는 SELECT, INSERT만).
#     MASTER는 MASTER-DOMAIN-001에서 첫 테이블부터 같은 방식으로 한다(지금 MASTER에는 테이블이 없다).
#   - 시험: apps/master-api MasterDatabaseAccountSpec이 이 파일을 그대로 MySQL 8.4.11 컨테이너에 넣어 권한 결과(SHOW GRANTS)와
#     runtime 계정의 DDL 거부·다른 DB 접근 거부를 확인한다. payment-api DatabaseAccountSeparationSpec은 같은 형태의 권한을 임시 계정으로 확인한다.
#   - payment-api는 application.yml에 Flyway 계정 바인딩·init-sqls UTC가 아직 없다(D2, PAYMENT-DBACCOUNT-001. 임시 방법은 .env.example).
#   - FINANCIAL은 Batch metadata 위치·권한(Q-BATCH-META) 답변 전이라 기존 단일 계정(ALL)을 유지한다.
#   - 이 스크립트는 데이터 디렉터리가 빈 첫 기동에만 실행된다. 이전에 만든 compose volume은 권한이 바뀌지 않는다(volume 재생성 또는 수동 GRANT 필요).
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

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS paycore_master;
CREATE DATABASE IF NOT EXISTS paycore_payment;
CREATE DATABASE IF NOT EXISTS paycore_financial;

CREATE USER IF NOT EXISTS 'paycore_master_migration'@'%'  IDENTIFIED BY '${PAYCORE_MASTER_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_master'@'%'            IDENTIFIED BY '${PAYCORE_MASTER_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_payment_migration'@'%' IDENTIFIED BY '${PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_payment'@'%'           IDENTIFIED BY '${PAYCORE_PAYMENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'paycore_financial'@'%'         IDENTIFIED BY '${PAYCORE_FINANCIAL_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON paycore_master.* TO 'paycore_master_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON paycore_master.* TO 'paycore_master'@'%';

GRANT ALL PRIVILEGES ON paycore_payment.* TO 'paycore_payment_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON paycore_payment.* TO 'paycore_payment'@'%';

GRANT ALL PRIVILEGES ON paycore_financial.* TO 'paycore_financial'@'%';
FLUSH PRIVILEGES;
SQL
