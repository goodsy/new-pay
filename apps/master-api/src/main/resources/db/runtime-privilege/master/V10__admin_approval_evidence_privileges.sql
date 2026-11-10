-- MASTER runtime 표 단위 권한(MERCHANT-OPS-002 B2-2 §9.3, ARCHITECTURE-CONVENTIONS §9.2 관례). V9(업무 schema)의 승인 근거 표에 앱 runtime 계정이 **현재 main 코드에서 실제로 쓰는**
-- 권한만 준다: persist(INSERT)와 PK 조회(SELECT). append-only 확정 사실이라 UPDATE·DELETE 경로가 없다 — 둘 다 1142로 거부된다(잠금 읽기 FOR UPDATE도 UPDATE 권한이 없어 거부).

-- admin_approval_evidence: INSERT = 실행·기한 확인의 근거 행(EXECUTED/REJECTED), SELECT = 판정 3(approvalId 1회 사용)·결정 조회.
CALL ${flyway:database}_privilege.set_runtime_table_privileges('admin_approval_evidence', 'SELECT, INSERT');
