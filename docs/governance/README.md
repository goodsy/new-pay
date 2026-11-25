# docs/governance — 관리체계 정상화 진단 색인

- 작업: GOV-DIAG-001(관리체계 정상화 진단). 배경은 [RULE-GOV-001](../repository/RULE-GOV-001-BUSINESS-RULE-GOVERNANCE.md) §1 — "기능 구현 체계는 있지만 무엇을 어떤 업무 규칙으로 구현할지 통제하는 체계가 부족".
- 이 폴더의 문서는 모두 **진단·제안**이다. 소스·DB·결정·작업 상태를 바꾸지 않았고 정책을 정하지 않았다. 시험·게이트는 실행하지 않았다(정적 읽기·grep·스크립트 집계).
- 모든 문서의 기준 커밋은 `41139db`(main, 2026-11-25), 작성일은 2026-11-25다. 이 커밋 뒤 코드가 바뀌면 해당 진단을 다시 확인한다.

## 1. 읽는 순서

1. [NORMALIZATION-PLAN.md](NORMALIZATION-PLAN.md) — 결론·통합 문제 목록·정비 단계·사용자 결정 사항
2. [POLICY-CONFLICTS.md](POLICY-CONFLICTS.md) — 정책 충돌·미결 통합 목록(근거와 대안만)
3. 필요한 영역의 원 진단(아래 §2 표)

## 2. 문서 목록

| 문서 | 목적 | 기준 커밋 | 작성일 | 원본/참조 관계 |
|---|---|---|---|---|
| [ADMIN-FEATURE-INVENTORY.md](ADMIN-FEATURE-INVENTORY.md) | 내부 관리자 화면 기능 인벤토리 — 메뉴·라우트, ADMIN API 61개 ↔ 프런트 호출 대조, 기능 44개 상태, 중복·누락·권한 불일치 | `41139db` | 2026-11-25 | **원 진단(원본).** 기능 목록의 원천. 기준선 "기능·메뉴 인벤토리" 정본 후보(NORMALIZATION-PLAN §4.1 #1) |
| [DOMAIN-CONSISTENCY-1-MASTER.md](DOMAIN-CONSISTENCY-1-MASTER.md) | MASTER(Business·Merchant·Site·Contract·MID) 문서 ↔ 코드·DB 정합성, 문제 DC-M-01~25 | `41139db` | 2026-11-25 | **원 진단(원본).** DOMAIN-ASSESSMENT·CURRENT-STATE(`378aff7`)를 확인·정정(§2)하되 고쳐 쓰지 않음 |
| [DOMAIN-CONSISTENCY-2-PAYMENT-FINANCIAL.md](DOMAIN-CONSISTENCY-2-PAYMENT-FINANCIAL.md) | 결제·취소·매입·정산·지급·대사 정합성, 횡단 검사(TX·멱등·append-only·UNKNOWN), 문제 DC-P-01~19 | `41139db` | 2026-11-25 | **원 진단(원본).** 같은 방식으로 기존 진단 참조(§2) |
| [SOURCE-CONVENTION-REVIEW.md](SOURCE-CONVENTION-REVIEW.md) | 백엔드 소스 컨벤션 — 강제 상태, 명명·의존·TX·시험·중복, 문제 SC-01~22 | `41139db` | 2026-11-25 | **원 진단(원본).** COMPLEXITY·DOMAIN·REBUILD-VS-REFACTOR(`378aff7`) 대응표(§6) |
| [ADMIN-UI-DIAGNOSIS.md](ADMIN-UI-DIAGNOSIS.md) | 관리자 UI·React 컨벤션·디자인 시스템 이행·시험 구성, 문제 UI-01~30 | `41139db` | 2026-11-25 | **원 진단(원본).** FRONTEND-ASSESSMENT(`378aff7`) 이후 변화 확인(§2). 기능 목록은 INVENTORY에 맡김 |
| [POLICY-CONFLICTS.md](POLICY-CONFLICTS.md) | 전 도메인 정책을 (A) 충돌 (B) 코드≠문서 (C) 미결 (D) 과거·대체 표시 누락 (E) 사용자 결정 없이 정한 동작으로 통합, PC-01~74 | `41139db` | 2026-11-25 | **종합(참조).** 원 진단 5종 + `docs/rules` 대장 6종 `## 진단` 절 + `harness/questions.json`을 합친 것. 세부 근거는 원본에 있다 |
| [NORMALIZATION-PLAN.md](NORMALIZATION-PLAN.md) | 구현 수준 요약, 관리체계 문제(P-1~P-6, G-7~G-9), 통합 문제 NP-01~122(P0→P4), 기준선 문서 6종 구성안, 정비 단계 S1~S5, 사용자 결정 U-01~U-12 | `41139db` | 2026-11-25 | **종합(참조).** 같은 원천 + RULE-GOV-001·RECOVERY-ROADMAP. 정책 판단은 POLICY-CONFLICTS를 참조 |

- 종합 2종은 rev 0.1a(독립 리뷰 CHANGES_REQUIRED 반영)다. 종합 문서는 확정된 정책을 다시 묻지 않고, 확정 규칙을 바꾸는 대안에는 "변경안"을 표시한다.
- 같은 사실의 원본은 원 진단 쪽이다. 종합 문서와 원 진단이 다르면 원 진단(그리고 그 원천인 코드·DECISIONS·questions)을 따른다.
- ID 체계: `DC-M-nn`·`DC-P-nn`·`SC-nn`·`UI-nn`(원 진단 문제), `PC-nn`(정책 항목), `NP-nn`(통합 문제), `U-nn`(사용자 결정 묶음). `SC-nn`은 질문 ID `Q-SC-n`과 관계없다.

## 3. 다른 폴더와의 관계

| 폴더·문서 | 관계 |
|---|---|
| [`docs/rules/`](../rules/README.md) | 업무 규칙 대장 초안(BIZ·PAY·CAN·FIN·WH·ADM, 790행, RULE-GOV-001 2단계). 각 파일 끝 `## 진단` 절이 POLICY-CONFLICTS의 원천이다. 대장은 **현재 유효 규칙의 정본 후보**이고(NORMALIZATION-PLAN §4.2), 이 폴더는 대장을 고치지 않는다. 대장 확정(제안 → 확정)은 사용자 답 뒤 S3 단계 |
| [`docs/assessment/`](../assessment/) | 2026-11-20 `378aff7` 기준 진단 6종(CURRENT-STATE·DOMAIN·FRONTEND·COMPLEXITY·REBUILD-VS-REFACTOR·RECOVERY-ROADMAP). **고쳐 쓰지 않고 참조만** 한다. 그 뒤 달라진 사실은 각 원 진단의 대응 절(DC-M §2, DC-P §2, SC §6, UI §2)과 POLICY-CONFLICTS PC-64에 있다. RECOVERY-ROADMAP의 단계는 NORMALIZATION-PLAN §5 S5에서 순서만 다시 정한다 |
| [`docs/repository/RULE-GOV-001-BUSINESS-RULE-GOVERNANCE.md`](../repository/RULE-GOV-001-BUSINESS-RULE-GOVERNANCE.md) | 업무 규칙 통제 체계 설계(rev 0.1 초안). 이 폴더는 그 §4 진행 순서의 3단계("진단 종합 보고 → 충돌·빈칸 사용자 질문")에 해당한다 |
| `docs/DECISIONS.md`, `harness/questions.json` | 결정 이력·질문 원문의 정본(HARNESS-GUIDE §12). 이 폴더의 새 질문 후보(가칭 ID)는 아직 등록되지 않았다 — 등록은 Coordinator가 S2에서 |
| `harness/tasks.json` | 작업 상태 정본. GOV-DIAG-001 카드의 다음 동작("병렬 진단 종합 → 정책 충돌 목록·정상화 계획 작성 → 사용자 결정 사항 제시")이 이 폴더의 종합 문서이고, RULE-GOV-001 카드는 "관문·추적 게이트 구현은 정상화 계획 승인 후"다. 이 폴더는 tasks.json을 고치지 않는다 |

## 4. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(진단 5종 + 종합 2종 색인) |
| 0.1a | 2026-11-25 | 종합 2종 rev 0.1a(독립 리뷰 반영) 표시, 확정 정책 재질문 금지 원칙 명시 |
