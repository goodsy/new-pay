-- FIN-FACT-RELAY-001 V34(D-FFR-001 — 사용자 결정 2026-11-05, D-WH-009 확장): outbox_event의 publish_status·published_at 두 열만 열 단위 UPDATE를 runtime에 더한다.
-- 쓰기 경로 = JpaFinancialFactRelayStore.markDelivered의 PK native UPDATE(`publish_status = 'PUBLISHED', published_at = ACK 시각 WHERE id = ? AND publish_status = 'PENDING'`, ACK 기록과
-- 같은 TX). FINANCIAL_FACT 행만 바꾼다(다른 topic 행은 PENDING 그대로 — 소비자 없음). payload 등 나머지 열 UPDATE는 1143, DELETE는 1142로 계속 거부된다.
-- grant_runtime_column_update는 **부여만** 한다(V24 알림 두 열 권한은 그대로 남는다). 표 단위 helper를 outbox_event에 다시 부르지 않는다(V24 주석 n-4 — 열 권한이 회수·확대된다).
CALL ${flyway:database}_privilege.grant_runtime_column_update('outbox_event', 'publish_status, published_at');
