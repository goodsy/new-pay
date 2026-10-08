-- 시험 전용(테스트 classpath에만 존재, 운영 migration 아님, 업무 의미 없음). MASTER-BOOT-001.
-- 1) Flyway(migration 계정)가 migration을 실행한 MySQL 세션의 time_zone을 기록한다. 시험 컨테이너 OS 시간대는 Asia/Seoul이므로
--    세션 설정이 없으면 SYSTEM(+09:00)이 남는다. 앱 runtime pool과 같은 UTC 세션이어야 migration의 시각 기본값/변환이 어긋나지 않는다.
-- 2) runtime 계정의 DML 가능·DDL 불가 시험 대상 테이블.
CREATE TABLE tech_session_probe (
    probe_id     VARCHAR(32) NOT NULL PRIMARY KEY,
    session_zone VARCHAR(64) NOT NULL,
    observed_at  DATETIME(3) NOT NULL
) ENGINE=InnoDB;

INSERT INTO tech_session_probe (probe_id, session_zone, observed_at)
VALUES ('flyway-migration', @@session.time_zone, NOW(3));
