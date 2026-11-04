-- TXN-OPS-001 §3.2: 관리자 거래 목록 keyset 색인(append-only migration — 데이터·권한 변경 없음, 새 표 없음 → runtime 권한 migration 불필요).
-- 목록은 `ORDER BY created_at DESC, payment_id DESC` + `(created_at, payment_id) < cursor`이고 기간은 필수(D-TXOPS-001, 31일 Demo 상한)다.
-- * ix_payment_created: MID 없는 기간 검색(환경·상태는 잔여 조건 — 기간이 스캔 범위를 묶는다).
-- * ix_payment_mid_created: MID 기간 검색(환경은 잔여 조건 — MID당 최대 2값).
-- 주문번호 조회는 기존 uk_payment_order_generation(environment, merchant_account_id, merchant_order_id, slot_generation)을 쓴다.
-- 리뷰 MINOR-2: 한 ALTER로 두 색인을 온라인 DDL(ALGORITHM=INPLACE, LOCK=NONE)로 만든다 — 색인 생성 중에도 Payment INSERT·UPDATE를 막지 않는다(지원되지 않으면 MySQL이 오류로
-- 거절하며 잠금 방식으로 몰래 바꾸지 않는다). 시작·끝의 짧은 metadata lock(MDL)은 남는다 — 운영 적용 절차(lock_wait_timeout·긴 TX 확인)는 명세 §9.1 L-10(LIVE Gate).
-- 비용: Payment INSERT마다 색인 2개 갱신(관리 조회의 전체 표 스캔을 막는 대가 — 부하 측정은 LIVE Gate, 명세 §9.1 L-5).
ALTER TABLE payment
    ADD INDEX ix_payment_created (created_at, payment_id),
    ADD INDEX ix_payment_mid_created (merchant_account_id, created_at, payment_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;
