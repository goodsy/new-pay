-- D-AUTHN-007: V7 이전 REQUESTED 행을 애플리케이션이 채운 뒤 REQUESTED는 결제창 시각을 반드시 가진다. 채우기 전에는 payment-api
-- FlywayMigrationStrategy(CheckoutWindowCheckMigrationStrategy)가 이 migration을 미룬다(V8까지만 적용 + "V8_1 pending" 신호, 다음 기동에 적용 — m-A).
-- 주의(R14): MySQL 8.4의 ADD CONSTRAINT … CHECK는 기존 행 전체를 검증하며 테이블 재구성·동시 DML 차단일 수 있다 — LIVE에서는 유지보수 창에서 기동.
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_requested_checkout_window CHECK (status <> 'REQUESTED' OR checkout_expires_at IS NOT NULL);
