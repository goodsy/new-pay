-- TECH-001 기술 조합 smoke 전용 테이블. 테스트 classpath에만 존재하며 운영 migration이 아니다.
-- 업무 의미 없음: 재시작 시 중복/누락 여부를 PRIMARY KEY와 행 수로 검사하기 위한 출력 대상.
CREATE TABLE tech_smoke_batch_out (
    item_id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
