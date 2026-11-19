-- MERCHANT-NAME-SEARCH-001 MNS-1: 가맹점명 앞부분 검색(`display_name LIKE ? ESCAPE '!'`)용 색인 — V15 ix_business_name_id 선례(값 열 + 정렬·keyset 열).
-- 새 표·열 없음 → runtime 권한 변경 없음, V18은 비워 둔다(V8·V12 선례 — 이후 V18 추가 금지). 온라인 추가(INPLACE, LOCK=NONE — 쓰기를 막지 않는다).
ALTER TABLE merchant
    ADD INDEX ix_merchant_name_id (display_name, merchant_id),
    ALGORITHM = INPLACE, LOCK = NONE;
