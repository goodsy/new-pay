-- SITE-CONTRACT-CODE-001 SCODE-1(§3.3·§3.5 — Q-SC-5 불변·재사용 금지): V19 코드 표 2개의 runtime 표 단위 권한. INSERT = 등록 TX의 코드 행(persist), SELECT = 순번 MAX 읽기·응답/재생 조회·상세 조회.
-- 발급 후 불변·삭제 경로 없음(빈 번호 재사용 불가) — UPDATE·DELETE는 1142(잠금 읽기 FOR UPDATE도 UPDATE 권한이 없어 거부 — 순번 직렬화는 merchant 행 잠금, C-13).
-- 기존 표(merchant_site·contract) 권한은 바꾸지 않는다(V2 그대로).
CALL ${flyway:database}_privilege.set_runtime_table_privileges('merchant_site_code', 'SELECT, INSERT');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('contract_code', 'SELECT, INSERT');
