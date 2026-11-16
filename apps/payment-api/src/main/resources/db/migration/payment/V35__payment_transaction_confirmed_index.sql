-- ADMIN-HOME-KPI-001 §5·§9: 운영 홈 오늘 KPI 집계 색인(append-only migration — 데이터·권한 변경 없음, 새 표 없음 → runtime 권한 migration 불필요).
-- 번호: migration V32 + runtime-privilege V33·V34 다음(같은 Flyway 이력).
-- 집계는 `type IN (AUTHORIZATION, CANCEL)`(합계)·`type = AUTHORIZATION`(건수) + `confirmed_at ∈ [KST 오늘 00:00, asOf)` 범위를 FORCE INDEX로 읽는다.
-- 열 순서(rev 0.3 NIT): type 선두 — 건수 문장은 승인 범위 하나만 읽고(같은 시간대 취소 행을 건너뛰지 않음), 합계 문장은 두 type 범위 각각 confirmed_at으로 좁힌다.
-- 뒤 3열(currency, amount_minor, payment_id)은 covering — 표 본문 접근 없이 합계·payment join 키를 읽는다.
-- 온라인 DDL(ALGORITHM=INPLACE, LOCK=NONE): 색인 생성 중에도 확정 사실 INSERT를 막지 않는다(지원되지 않으면 MySQL이 오류로 거절하며 잠금 방식으로 몰래 바꾸지 않는다).
-- 시작·끝의 짧은 metadata lock은 남는다(운영 적용 절차는 LIVE Gate). 비용: payment_transaction INSERT마다 보조 색인 1개 갱신(명세 §14 L-1, 부하 측정은 LIVE Gate).
ALTER TABLE payment_transaction
    ADD INDEX ix_payment_transaction_confirmed (type, confirmed_at, currency, amount_minor, payment_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;
