-- TECH-001 기술 조합 smoke 전용 테이블. 테스트 classpath에만 존재하며 운영 migration이 아니다.
-- 업무 의미 없음: id는 테스트가 직접 지정한다(업무 ID 전략 아님). name은 키 컬럼이 아니다(collation 미지정은 의도적 — Q-CP-KEY 미결).
CREATE TABLE tech_smoke_item (
    id      BIGINT       NOT NULL PRIMARY KEY,
    name    VARCHAR(100) NOT NULL,
    version BIGINT       NOT NULL
) ENGINE=InnoDB;
