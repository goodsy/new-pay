-- ACQ-VIEW-001 §5.3 매입 현황 관리 조회 색인(append-only migration — 데이터·제약·권한 변경 없음, runtime 권한 migration 불필요: 색인은 표 권한을 바꾸지 않는다).
-- 매입사 없는 업무일 기간 검색(최신 업무일순 keyset `(business_date, file_id)`)을 위한 색인. 매입사가 있으면 기존 UNIQUE
-- uk_acquisition_file_lane_date_version(acquirer_code, environment, business_date, file_version)을 쓴다.
-- 온라인 DDL(INPLACE, LOCK=NONE)이지만 시작·끝에 배타 metadata lock이 필요하다 — 운영 적용 절차는 LIVE Gate(명세 §11 L-8).
ALTER TABLE acquisition_file
    ADD INDEX ix_acquisition_file_business_date (business_date, file_id),
    ALGORITHM=INPLACE, LOCK=NONE;
