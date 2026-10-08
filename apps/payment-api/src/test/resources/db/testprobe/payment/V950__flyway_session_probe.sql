-- 시험 전용(PaymentDbTest 컨텍스트만, 운영 migration 아님). PAYMENT-DBACCOUNT-001 D2: Flyway가 migration을 적용한 **그 세션**의
-- time_zone과 접속 계정을 기록한다(spring.flyway.init-sqls·spring.flyway.user 효과의 행위 증거). runtime 권한을 주지 않는다 — 시험은 root로 읽는다.
CREATE TABLE tech_flyway_session_probe (
    probe_id       VARCHAR(32)  NOT NULL PRIMARY KEY,
    session_zone   VARCHAR(64)  NOT NULL,
    migration_user VARCHAR(300) NOT NULL
) ENGINE = InnoDB;

INSERT INTO tech_flyway_session_probe (probe_id, session_zone, migration_user)
SELECT 'flyway-migration', @@session.time_zone, CURRENT_USER();
