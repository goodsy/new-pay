# RULE-CONFIRM-001 — 업무 규칙 대장 `제안` 행 일괄 확인 목록

> **목록 준비 문서다. 정책을 확정하지 않는다.** 대장(`docs/rules/*.md`) 상태·DECISIONS·questions·소스·시험은 이 작업에서 바꾸지 않았다.
> 근거 결정: D-GOV-005 ④(일괄 확인 + 금융 영향 있는 것만 개별 질문), NORMALIZATION-PLAN §6 U-11, POLICY-CONFLICTS §5.1(PC-65)·§5.2(PC-66~74). 질문 수 상한: HARNESS-GUIDE §4·D-GOV-009 ①(한 번에 3개 이하).

## 0. 기준

| 항목 | 값 |
|---|---|
| 기준 커밋 | `6da29e7` (main, 2026-11-25 20:30:42 +0900 "harness: BIZ-NORM-002 DONE") |
| 작성 시각 | rev 0.1 2026-11-25 20:42 KST, rev 0.1a 같은 날(독립 리뷰 반영) |
| 작업 트리 | 커밋되지 않은 변경 있음(다른 작업 진행 중): `docs/rules/BIZ.md`(BR-BIZ-048·304·305 `확정` 행과 `## 진단` 절 — `제안` 행 변경 없음), `docs/DECISIONS.md`, `harness/questions.json` 등. **`제안` 행 수는 HEAD와 작업 트리가 같다(121)**. 이 문서는 작성 시각의 작업 트리 내용을 읽었다 |
| 추출 방법 | 각 대장 `\| BR-… \|` 행 중 7열이고 상태 열(3열)이 `제안`으로 시작하는 행. `## 진단` 절 안의 요약 표(2·3열 형식)는 제외 |
| 코드 동작 표기 | `일치(대장 검증 열)` = 대장 검증 열에 시험이 적혀 있음(**이번 작업에서 시험을 다시 돌리지 않았다**). file:line은 이번에 직접 읽은 곳. `미확인` = 시험 없음 + 코드 확인 안 함(추측하지 않음) |

### 0.1 119와의 차이

POLICY-CONFLICTS §5.1(PC-65)·D-GOV-005 ④의 119행 이후 **BR-BIZ-309·310 두 행이 `제안`으로 추가**되었다(D-GOV-008 ⑦⑧ "대장 행 추가 후 D-GOV-005 ④ 일괄 확인에서 현행 유지 확인 예정", D-GOV-009 ② 용어 절 규칙 문장 이동). 그 밖 119행의 BR-ID는 §5.1 목록과 하나도 빠짐없이 같다(스크립트 대조). 같은 이동으로 생긴 BR-BIZ-311·312는 `확정`이라 대상이 아니다.

**119 + 2 = 121행.**

### 0.2 집계

| 도메인 | `제안` 행 | A 현행 유지 확인 | B 개별 질문 | C 결정으로 확정·대체 | D 보류 |
|---|---|---|---|---|---|
| BIZ | 16 | 11 | 4 | 1 | 0 |
| PAY | 10 | 10 | 0 | 0 | 0 |
| CAN | 26 | 15 | 9 | 2 | 0 |
| FIN | 16 | 11 | 3 | 1 | 1 |
| WH | 27 | 24 | 0 | 3 | 0 |
| ADM | 26 | 24 | 1 | 1 | 0 |
| **합계** | **121** | **95** | **17** | **8** | **1** |

- 분류 기준: **A** fail-closed·안전 방향·표시/안내 수준·이미 결정된 원칙의 직접 적용 / **B** 금액·정산·매입 효과·상태 전이·키 동일성·개인정보 노출 범위·권리에서 다른 선택이 실제 결과를 바꾸는 것 / **C** 최근 D-ID가 이미 답했는데 대장 상태만 `제안`으로 남은 것 / **D** 이번 확인에서 제외하고 해당 명세 착수 때 결정(보류).
- **우선순위: fail-closed(거절·대기·보류·엄격) 방향이면 B 기준보다 우선해 A로 둔다.** 단 D-GOV-005 ④가 이름을 든 항목(매입 효과 C-5·I-5·L-6, KPI 정의, 최신 접수 건, 첫 계약 승인, Q-CAN-3·5~8)과 PC-72 개별 확인 권고(BR-BIZ-084)는 방향과 무관하게 B. A 중 금융·키·상태 의미가 있으나 안전 방향이라 A로 둔 29행은 비고에 `[금융·키·상태 의미 있음 — 안전 방향이라 A]`로 표시했다(§1 Q-A 첨부 목록에도 표시).
- **BR-WH-035는 행 하나를 세 부분으로 나눈다**: 집계는 C 1행으로 세지만 (a)(c) 부분은 §1 Q-A에 포함, (b) 부분만 D-GOV-004 ④로 대체(§3).
- 질문 구성: 라운드 0 = Q-A(A 일괄) + Q-C(C 반영 확인) 2개, 라운드 1~5 = B 17행 → 질문 14개(라운드당 3개 이하).

### 0.3 사용 방법

1. **라운드 0(아침 첫 질문, 2개)** — Q-A "A 행 전부 현행 확정? 예 / 예외 BR-ID 지정", Q-C "C 8행을 근거 D-ID대로 반영(BR-WH-035는 (b)만 대체)할까요? 예 / 예외". 도메인별 A 목록은 Q-A 첨부로 본다.
2. **라운드 1~5** — §2 B 질문을 라운드 순서로, 한 번에 3개 이하. Q-A에서 예외로 뺀 행은 다음 라운드 B 질문으로 옮긴다(라운드당 3개 상한 유지).
3. **Coordinator가 반영한다**(이 문서 작업 범위 밖) — 답마다 `harness/questions.json`에 질문·답 기록, `docs/DECISIONS.md`에 D-ID 1행(또는 묶음), 대장 행 상태 `제안` → `확정`(출처 `사용자`, 근거에 새 D-ID) 또는 수정/대체.
4. BR-WH-035: (b)는 `대체` 처리와 D-GOV-004 ④ 문구의 새 `확정` 행 추가, 코드 정비 카드 등록(GAVE_UP 유지 + `reconstitute` 불변식 `MerchantWebhookDelivery.kt:122-127` 포함). (a)(c)는 Q-A 답에 따라 별도 행으로 확정. 대장 행 분할 방식(번호)은 Coordinator가 정한다.
5. D 행(BR-FIN-100)은 이번에 묻지 않고 `제안`으로 두며 SETTLEMENT-CONTRACT-001 착수 때 질문한다.
6. 대장 확정 뒤 D-GOV-006 ③(착수 전 관문은 대장 확정 즉시 새 작업부터) 적용 가능. 이 문서 작성 뒤 대장이 바뀌었으면 §0 추출 방법으로 다시 세어 차이를 먼저 확인한다.

### 0.4 범위 밖 잔여(이 목록에 없음)

`제안`이 아니라 이 목록에 없지만 출처 확인이 권고된 것: BR-PAY-089(`확정`, D-AUTH-010 해석 정정)·BR-PAY-098(`Demo값`, D-AUTH-009)·BR-ADM-030(`확정`, 배제 범위 확대), PC-74의 D-ACQ-003 "(매입사, 환경, 업무일)당 1파일" 환경 축 해석. PC-67(RENAMED 전송 완료)은 D-GOV-002 ②로 답함.

## 1. 라운드 0 — A 일괄 확인 + C 반영 확인(질문 2개)

**Q-A** — "아래 A 95행과 BR-WH-035 (a)(c) 부분을 전부 현행대로 확정할까요?"
- 권장안: **예(전부 현행 `확정`)** — 대부분 fail-closed·안전 방향이고 각 대장 진단 권장(PAY F-2, FIN RG-08, WH (f)-4, ADM f-8, CAN Q-CAN-4 "현행 유지")과 같다.
- 대안: 예외로 뺄 BR-ID 지정(그 행은 B로 옮겨 개별 질문).
- 영향: D(보류) 1행(BR-FIN-100)은 이 질문에 포함되지 않는다(SETTLEMENT-CONTRACT-001 착수 때 질문). `확정`이 되면 RULE-TRACE "확정 규칙만 착수" 관문 통과. 미구현·불일치 행은 규칙만 확정되고 구현은 후속 작업. BR-WH-035 (a)를 받지 않으면 이미 받은 이벤트를 다시 보내는 중복 재전송 위험.
- 막히는 작업: 6개 도메인 대장 확정.

Q-A 첨부(도메인별 목록, `⚑` = 금융·키·상태 의미 있음 — 안전 방향이라 A):

- **BIZ** (11행): `BR-BIZ-` 017, 309, 310, 064⚑, 162⚑, 167, 172⚑, 213⚑, 234, 285, 291
  - 미구현·불일치 포함(규칙만 확정, 구현 후속): BR-BIZ-285, BR-BIZ-291
- **PAY** (10행): `BR-PAY-` 040, 049, 051⚑, 053⚑, 063⚑, 081, 105⚑, 106⚑, 109, 116⚑
- **CAN** (15행): `BR-CAN-` 005⚑, 014⚑, 019⚑, 023⚑, 025⚑, 041⚑, 048⚑, 063, 072, 075, 078, 080, 085, 095, 096
  - 미구현·불일치 포함(규칙만 확정, 구현 후속): BR-CAN-080
- **FIN** (11행): `BR-FIN-` 012⚑, 013⚑, 024⚑, 028⚑, 041⚑, 050⚑, 051⚑, 053⚑, 057, 111, 123
  - 미구현·불일치 포함(규칙만 확정, 구현 후속): BR-FIN-013, BR-FIN-050, BR-FIN-111, BR-FIN-123
- **WH** (24행 + WH-035 일부): `BR-WH-` 002, 003, 004, 008⚑, 009, 010, 011, 013, 016, 018, 019, 021, 026, 027, 030, 032, 034, 037⚑, 043, 044, 057, 060, 061, 062, 035(a)(c)⚑
  - 미구현·불일치 포함(규칙만 확정, 구현 후속): BR-WH-043
- **ADM** (24행): `BR-ADM-` 009, 017, 022, 023, 024⚑, 028, 036, 041, 046, 057, 060⚑, 062, 063, 071, 072, 073, 108, 111, 120, 121, 130, 131, 132, 138

**Q-C** — "아래 §3의 C 8행을 근거 D-ID대로 반영할까요(BR-WH-035는 (b) GAVE_UP 뒤 늦은 2xx 부분만 대체 + 코드 정비 카드)?"
- 권장안: **예** — 사용자가 이미 답한 결정(D-GOV-002 ③·003 ④·004 ③④·005 ③)을 대장 상태에 옮기는 일.
- 대안: 예외 BR-ID 지정(근거 해석이 다르면 그 행만 재질문).
- 영향: 대장 상태 정리. BR-WH-035 (b)는 코드 정비(현 GAVE_UP→DELIVERED 전이 제거)가 따라온다.
- 막히는 작업: WH·CAN·FIN·BIZ·ADM 대장 확정, 웹훅 GAVE_UP 정비 작업.

## 2. B — 개별 질문(라운드 1~5, 라운드당 3개 이하, 질문 14개)

우선순위: 라운드 1 = 돈 흐름·금액 정의(P1), 2 = 권리·관문(후속 작업을 막는 것 먼저), 3·4 = 취소 운영 해석(PC-73, Q-CAN-3·5~8)·승인 수준, 5 = 키 동일성·상태 전이(나머지).

### 라운드 1 — 돈 흐름·금액 정의

**Q1 (BR-FIN-019, ACQUIRING-001 C-5, PC-66) 같은 Payment에 보류가 하나라도 있으면 매입 후보 전체 대기**
- 현재: 같은 Payment에 해제되지 않은 결제 사실 보류가 하나라도 있으면 그 Payment의 모든 매입 후보(원승인 SALE 포함)를 `PAYMENT_HELD`로 대기(`AcquisitionCandidateRule.kt:24`). Coordinator 결정(리뷰 MJ-2), 사용자 결정 없음.
- 권장안: **현행 유지(Payment 전체 대기)** — fail-closed. D-GOV-001 ④(사고 사실을 FINANCIAL로 넘겨 매입 후보·정산 보류)와 같은 방향.
- 대안: (b) 보류가 걸린 사실(그 승인 또는 그 취소)만 대기하고 나머지는 매입 진행 (c) 보류 종류별 — 정합성 보류는 Payment 전체, 그 밖은 해당 사실만.
- 영향: 권장안은 취소 하나의 보류로 원승인 매입까지 늦어져 기관 입금·가맹점 지급이 늦어질 수 있다. 대안은 상계 전 원승인 전액이 매입되어 뒤 취소 반영이 어긋날 위험.
- 막히는 작업: FIN 대장 확정(Q-FIN-RG-08). (b)·(c) 선택 시 ACQUIRING 후보 규칙 변경 작업.

**Q2 (BR-FIN-040, ACQ-ADMIN-001 I-14·L-6, PC-66) 재매입된 원 SALE 뒤 취소의 매입 판정 — 시험 기대값 CC-4 → CC-8 이동**
- 현재: 원 SALE이 재매입(세대 ≥ 1)된 경우 그 뒤 받은 취소는 가장 높은 세대 SALE 결과로 판정해 CANCEL 레코드를 만든다(`CancelCandidateRule.kt:48-53`, `CancelCandidateRuleSpec::CC-8`). rev 0.6까지는 세대 0만 정합 판정했고(코드 주석), 이 변경 때 시험 기대값이 CC-4에서 CC-8로 옮겨졌다 — "정책·기대값을 바꿔 통과 금지" 원칙에 걸리는 사례로 진단됨.
- 권장안: **현행 유지 + 기대값 이동을 이 답으로 사후 승인 기록**(FIN 진단 RG-08 "일괄 확인", D-ACQ-009 "원 SALE ACCEPTED일 때만"을 최고 세대에 적용).
- 대안: (b) 재매입 세대 뒤 취소는 자동 판정하지 않고 대기 → 운영자 검토(rev 0.6 동작으로 되돌림) (c) 자동 판정은 유지하되 첫 운영 전 대사 확인 건으로 표시.
- 영향: 권장안은 재매입 건의 취소가 자동으로 매입사에 CANCEL로 나간다. (b)는 재매입 건 취소마다 수동 처리·지급 지연.
- 막히는 작업: FIN 대장 확정, 기대값 이동 건 종결(PC-66).

**Q3 (BR-CAN-102·BR-ADM-137, D-ADMIN-HOME-001 Coordinator 기본값, PC-70) 운영 홈 KPI 금액 정의**
- 현재: KPI를 통화별·환경별(TEST/LIVE 합산 금지)로 나누고, 결과 미확정(UNKNOWN)·진행 중 승인·취소는 금액에서 빼고 "확인 필요"로 센다. D-ADMIN-HOME-001이 "사용자 확인 대상 아님"으로 기록했지만 금융 의미가 있어 진단이 확인 대상으로 올림. 환경·통화 분리는 DB 수준 시험 없음(KPI1 §14 L-7).
- 권장안: **현행 유지**(CAN 진단 Q-CAN-9), D-ADMIN-HOME-001의 "확인 대상 아님" 문구는 이 답으로 정정.
- 대안: (b) TEST/LIVE 합산 보기 허용(필터로 선택) (c) UNKNOWN·진행 중 금액을 따로 "잠정 금액"으로 함께 표시.
- 영향: 조회 전용·금융 효과 없음. 운영자가 보는 숫자의 의미(정산 금액 아님 표지는 D-ADMIN-HOME-002 ③ 그대로).
- 막히는 작업: ADM·CAN 대장 확정. (b)·(c) 선택 시 KPI 화면·조회 수정 작업.

### 라운드 2 — 권리·관문

**Q4 (BR-BIZ-280, MERCHANT-FLOW-001 §4A.5 리뷰 m-3, PC-45) 가맹점 등록 관문의 "최신 접수 건"**
- 현재: 명세는 가맹점 등록 관문을 "사업자 안 생성 순번이 가장 큰 접수 건이 계약 완료"로 정했다. 사용자 결정 D-FLOW-001 ①은 "사업자 REGISTERED + 계약 완료"라고만 했다. 차이는 "계약 해지 뒤 새 접수 진행 중"일 때만 난다 — 현행이면 예전 완료 건이 있어도 새 건이 완료될 때까지 새 가맹점 등록이 막힌다. 관문 코드는 미구현(FLOW-3), "최신" 조회는 `JpaContractIntakeRepository.kt:28`.
- 권장안: **현행(최신 접수 건 = 생성 순번 최대, 그 건이 계약 완료일 때만 허용)** — 해지 뒤에는 새 계약 완료 전까지 막는 안전 방향.
- 대안: (b) 해지되지 않은 계약 완료 건이 하나라도 있으면 허용 (c) 최신을 접수일 기준으로(같은 날이면 순번).
- 영향: 가맹점을 새로 등록할 수 있는 시점(권리). 기존 가맹점·결제에는 영향 없음.
- 막히는 작업: MERCHANT-FLOW-001 FLOW-3(tasks.json nextResumeAction "FLOW-3 전 개별 확인").

**Q5 (BR-BIZ-067, MERCHANT-CODE-001 C-14, PC-72) "첫 계약 승인" 정의 — 가맹점 코드 변경 잠금 시점**
- 현재: 그 가맹점 계약의 어느 Revision이든 승인 사실이 하나라도 있으면 "첫 계약 승인 뒤"로 보고 가맹점 코드 변경을 잠근다(효력 시작 전 승인 포함, 철회·종료 뒤에도 잠김). 시험 `MerchantCodeChangeApprovalSpec::LOCKED`.
- 권장안: **현행 유지** — 승인 뒤 코드가 외부(웹훅 `merchantCode`, BR-WH-016)로 나갈 수 있으므로 한 번 승인되면 계속 잠금.
- 대안: (b) 효력이 시작된 승인만 "첫 승인"으로 봄 (c) 현재 유효 계약이 있을 때만 잠금(철회·종료 뒤 다시 변경 가능).
- 영향: 외부 노출 식별자의 불변 시점. 대안은 이미 알려진 코드가 바뀔 수 있음.
- 막히는 작업: BIZ 대장 확정.

**Q6 (BR-FIN-020, ACQUIRING-001 I-5, PC-66) KRW 외 통화 승인의 매입**
- 현재: KRW 외 승인은 매입하지 않고 대기, 종결 판정을 기록하지 않는다(`AcquisitionCandidateRule.kt:25`). 승인은 KRW만(D-AUTH1-002), 외화는 기준정보에서 비활성(D-GOV-002 ④), 정산 통화는 Q-SETTLE-CURRENCY 미결 — 지금은 KRW 외 승인이 생길 경로가 없어 방어 규칙이다.
- 권장안: **현행 유지(대기, 종결 기록 없음)** — Q-SETTLE-CURRENCY 결정 때 다시 본다.
- 대안: (b) "매입 대상 아님"으로 종결 기록 (c) 대기 + 즉시 경보(정상 경로에서 생기면 안 되는 건으로 취급).
- 영향: 현재 실질 영향 적음. 외화 도입 시 매입 누락(b) vs 대기 적체(권장).
- 막히는 작업: FIN 대장 확정만.

### 라운드 3 — 취소 운영 해석(1)

**Q7 (BR-CAN-002, CANCEL-CONTRACT-001 N-2, Q-CAN-3) 승인 결과 미확정 Payment 취소**
- 현재: AUTHORIZING·UNKNOWN Payment는 가맹점·운영자 누구의 취소도 거절하고 원 Attempt 조회·대사로 승인 결과를 먼저 확정한다(`CancelStart.kt:88-93` — 가맹점은 AUTHORIZED, 운영자는 확정 승인 거래 필요, `CancelStartSpec::CX-07`).
- 권장안: **거절 유지**(CLAUDE.md "UNKNOWN은 원 Attempt 결과 조회/대사 우선").
- 대안: (b) 운영자 보정 취소(2인)만 허용 (c) 가맹점 취소를 접수만 해 두고 승인 확정 뒤 실행. **(b)·(c)는 채택 시 CLAUDE.md 핵심 규칙("AUTHORIZING/UNKNOWN… UNKNOWN은 원 Attempt 결과 조회/대사 우선") 개정이 필요하다.**
- 영향: 승인 결과 모르는 상태에서 취소하면 이중 효과·잔액 불일치 위험 vs 고객 반환 지연.
- 막히는 작업: CAN 대장 확정.

**Q8 (BR-CAN-065, ADMIN-PAYMENT-OPS-001 I-35, Q-CAN-6) PAYMENT 기록 결정이 ADMIN 승인 내용과 다를 때**
- 현재: 무결성 플래그를 먼저 남기고 경보·감사한 뒤 PAYMENT 결정을 그대로 반영한다(반영 보류 없음). 명세 §12.2가 "보류를 원하면 사용자 질문"이라 적음.
- 권장안: **플래그 후 반영**(CAN 진단 — 보류하면 그 Payment 처리가 영구 차단됨. 금융 사실의 소유자는 PAYMENT).
- 대안: (b) 반영 보류 + 수동 해소(2인) 전까지 ADMIN 승인 상태 고정.
- 영향: 승인과 실제 실행이 어긋날 때 무엇을 기준으로 둘지(감사·통제 vs 운영 차단).
- 막히는 작업: CAN 대장 확정.

**Q9 (BR-CAN-067·068, ADMIN-PAYMENT-OPS-001 §4.12·I-32, Q-CAN-5) Payment당 미해결 보정 취소 승인 1건**
- 현재: 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN) 보정 취소 승인은 Payment당 1건, 두 번째는 409 `DUPLICATE_UNRESOLVED`. 진행 중 취소가 있으면 요청 단계는 거절, 승인 단계는 PENDING 유지·TOTP 미소비.
- 권장안: **현행 유지**.
- 대안: (b) 여러 건 허용하고 실행 시 잔액 재검사로만 막기 (c) 진행 중 취소가 있어도 승인 단계에서 거절(승인 소진).
- 영향: 이중 취소·잔액 경쟁 위험 vs 운영 유연성.
- 막히는 작업: CAN 대장 확정.

### 라운드 4 — 취소 운영 해석(2)·승인 수준

**Q10 (BR-CAN-052, CANCEL-001 CN-32, Q-CAN-7) 같은 approvalId에 다른 내용**
- 현재: 내용이 다른 명령은 새 결정 없이 409(요청자·승인자만 다르면 `APPROVAL_IDENTITY_MISMATCH`, 그 밖 `APPROVAL_CONFLICT`), 기존 결과를 돌려주지 않는다. Reviewer 확인 요청 상태로 남아 있음.
- 권장안: **현행 유지**(CLAUDE.md "같은 요청키·다른 내용은 충돌"과 같은 방향, fail-closed).
- 대안: (b) 충돌이라도 기존 결과를 함께 돌려줌 (c) 두 코드를 하나(`APPROVAL_CONFLICT`)로 합침.
- 영향: 키 동일성 판정. 금융 효과 없음(새 결정 없음).
- 막히는 작업: CAN 대장 확정.

**Q11 (BR-CAN-089·094, TXN-OPS-001 I-1·I-3, Q-CAN-8) 거래 조회 기간 면제와 조회 역할**
- 현재: 결제 ID 정확 조회·MID + 주문번호 조회는 기간 없이 허용(089). 거래 조회·상세는 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN(094). D-TXOPS-001은 "생성일 기간 필수"라고만 적어 089와 문언이 다르다.
- 권장안: **현행 유지**(정확 조회는 결과가 1건이라 기간이 범위 제한 역할을 못 함), D-TXOPS-001 문언은 "목록 검색만 기간 필수"로 보정.
- 대안: (b) 정확 조회도 기간 필수(D-TXOPS-001 문언대로) (c) SECURITY_ADMIN을 조회 역할에서 제외.
- 영향: 개인정보 노출 범위(상세 조회 감사 D-TXOPS-002는 그대로).
- 막히는 작업: CAN 대장 확정.

**Q12 (BR-BIZ-018, POL TBD-07, PC-72) 사업자 중요정보 변경 승인 수준**
- 현재: 중요정보 변경은 2인 승인·재검증 없이 OPERATOR 1인 + 사유 + 전후 이력(TBD-07 결정 전 기본값). 중요정보 범위는 BR-BIZ-017(구분 변경·대표자 추가 포함).
- 권장안: **현행 유지(Demo·TEST 기간), TBD-07(2인·재검증)은 LIVE 전 결정 과제로 남김**.
- 대안: (b) 지금부터 2인 승인(D-MOPS-004 방식) (c) 사업체 구분·대표자 변경만 2인.
- 영향: 사업자 정보 변경 통제 수준(권리·감사). 지급 계좌 변경은 별도(D-ADMIN-003 2인).
- 막히는 작업: BIZ 대장 확정. (b)·(c) 선택 시 BUSINESS-PROFILE 승인 흐름 작업.

### 라운드 5 — 키 동일성·상태 전이(남은 것)

**Q13 (BR-CAN-022, CANCEL-001 §4.8 n-8, PC-73) 업무 거절된 merchantCancelId 재사용**
- 현재: 업무 거절로 취소 행이 생기지 않은 merchantCancelId는 새 Idempotency-Key로 다시 쓸 수 있다(`CancelStartServiceSpec::(n-8)`). 같은 Idempotency-Key 재요청의 거절 재생 여부는 이번에 확인하지 않음(결제 API 선례 D-CPC-007 "409 업무 거절 30일 재생" — 취소 적용 **미확인**).
- 권장안: **현행 유지** — 거절은 금융 효과가 없고, 업무 키 영구 UNIQUE(BR-CAN-023)는 취소 행이 생긴 경우에 적용.
- 대안: (b) merchantCancelId는 한 번 쓰이면(거절 포함) 영구 소진.
- 영향: 가맹점 재시도 편의 vs 키 의미 단순성. 금융 효과 없음.
- 막히는 작업: CAN 대장 확정.

**Q14 (BR-BIZ-084, MASTER-SNAPSHOT-001 §4.4 관례, PC-72) Site 심사 전이와 거절 Site의 채널 해제**
- 현재: Site 심사 전이는 PENDING→APPROVED/REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED/REJECTED만 허용(출처에 개별 전이 없음 — 명세 관례). 시험 `SiteConfirmationAndReviewSpec(M)::REJECTED 종결`.
- 상호작용: D-GOV-004 ①("심사 거절 Site는 채널 점유 해제, 같은 주소로 새 Site 재신청 가능")과 합치면 **정지(SUSPENDED) Site를 REJECTED로 바꿔 채널을 풀고 재신청**하는 경로가 생긴다. 다른 가맹점 재사용은 D-GOV-004 ②(종료 Site 채널 — 예외 승인 없으면 거절)와의 관계가 정해져 있지 않다(**미확인**).
- 권장안: **현행 전이 유지 + SUSPENDED→REJECTED로 풀린 채널의 재사용도 D-GOV-004 ②와 같은 기준(같은 가맹점만 허용, 다른 가맹점은 예외 승인)**. 권장안도 정책 선택이라 사용자 확인 필요.
- 대안: (b) SUSPENDED→REJECTED 금지(정지 Site는 종료 절차로만 닫음, MASTER-LIFECYCLE-001) (c) 현행 그대로(REJECTED면 누구나 같은 채널 재신청).
- 영향: 결제 채널(사이트 주소) 점유·이전 권리, 정지 Site 우회 위험. 권장안을 고르면 D-GOV-004 ①(거절 Site 채널 해제 — 가맹점 제한 없음)의 적용 범위를 이 경로(SUSPENDED→REJECTED)에 한해 좁히는 것이다. 대안 (c)는 D-GOV-004 ①을 글자 그대로 적용한 경우다.
- 막히는 작업: BIZ 대장 확정, MASTER-LIFECYCLE-001 Site 종료 설계.

## 3. C — 이미 사용자 결정으로 확정·대체 가능(근거만)

| BR-ID | 근거 D-ID | 반영 |
|---|---|---|
| BR-BIZ-301 | D-GOV-003 ④ — "삭제된 대표자 생년월일 저장만·비응답(D-FLOW-009 적용 메모) 수용(확정)". 적용 메모 전체(현재 대표자 key 행만 응답)를 수용한 것으로 읽음 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-CAN-037 | D-GOV-004 ③ — "취소 사고 보류 중 가맹점 추가 취소만 차단, 운영자 보정 취소(2인) 허용", D-CANCEL-006 문언 정정 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-CAN-077 | D-GOV-002 ③ — "정산 거래일 = 기관 거래일(BR-FIN-077·079)". 시간대·기관 거래일 규약은 Q-CALENDAR(PROPOSED) 그대로 남음 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-FIN-008 | D-GOV-005 ③ — "상한 없이 재시도 유지 + 오래 밀린 사실 경보, 현 격리 기준 유지". 밀림 경보는 LIVE 조건(별도 행/작업) | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-WH-033 | D-GOV-004 ④ — "가맹점 웹훅 순서 비보장(일련번호 + 조회 API) 계약 확정", baseline 03 대체 표시 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-WH-035 | **세 부분으로 나눔.** (b) "GAVE_UP 뒤 늦게 확인된 2xx → DELIVERED"만 D-GOV-004 ④("GAVE_UP 상태 유지·늦은 성공은 시도 기록만, 경보 유지, 현 코드 GAVE_UP→DELIVERED 전이는 정비 대상")로 **대체**. (a) PENDING 중 소유를 잃은 시도의 늦은 2xx → DELIVERED, (c) DELIVERED는 실패로 되돌리지 않음(성공 단조)은 D-GOV-004 ④가 다루지 않아 `제안`으로 남겨 §1 A 질문에 포함 | (b) `대체` + 새 확정 행 + 코드 정비 카드(`reconstitute` 불변식 :122-127 포함). (a)(c)는 Q-A로 |
| BR-WH-077 | D-GOV-005 ③ — BR-FIN-008과 같은 결정(상한 없음·현 격리 기준). 밀림 경보는 LIVE 조건 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |
| BR-ADM-101 | D-GOV-003 ④ — BR-BIZ-301과 같은 규칙(경계 행). 같은 근거 | `제안` → `확정`(출처 `사용자`, 근거에 D-ID 추가) |

### 3.1 D — 보류(이번 확인에서 제외)

| BR-ID | 이유 |
|---|---|
| BR-FIN-100 | 선지급 정책 Scope — SETTLEMENT-CONTRACT-001 명세 착수 때 결정(보류). D-GOV-003 ②(수수료·정산 정책 = CSA 기본 + MID 개별 우선)가 선지급 정책까지 덮는지 미확인 |

## 4. 전체 목록(121행)

열: BR-ID | 규칙(원문) | 출처(대장 출처 열: 근거 열) | 현재 코드 동작 | 분류 | 비고. 행 순서는 대장 순서, 대장 줄 번호는 비고 끝 `L…`.

### 4.1 BIZ (16행 — A 11 · B 4 · C 1 · D 0)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-BIZ-017 | 사업체 구분 변경과 REGISTERED 사업자의 대표자 추가만 하는 변경도 중요정보 변경으로 본다 | 적용: BUSINESS-PROFILE-001 C-12·M-2(Coordinator·리뷰) | 일치(대장 검증 열) — BusinessProfileChangeSpec(M)::TC-13 구분 변경 중요, M-2 대표자 추가만도 중요 | **A** | 중요정보 범위를 넓히는 방향(엄격); `BIZ.md` L58 |
| BR-BIZ-018 | 중요정보 변경은 2인 승인·재검증 없이 OPERATOR 1인 + 사유 + 전후 이력으로 처리한다(TBD-07 결정 전 기본값) | 적용: POL TBD-07, BUSINESS-PROFILE-001 §3.3 | 일치(대장 검증 열) — BusinessProfileServiceSpec(A)::CREATE=UPDATE=COMPLETE={OPERATOR} | **B** | → §2 R4-Q12; `BIZ.md` L59 |
| BR-BIZ-309 | 대표자 유형 '단독'은 현재 대표자가 1명일 때만 쓰고, 공동·각자는 한 사업자 안에서 섞일 수 있다 | 적용: D-GOV-008 ⑧(대장 행 추가 후 D-GOV-005 ④ 일괄 확인에서 현행 유지 확인 예정), BUSINESS-PROFILE-001 §4.3 C-22 | 일치(대장 검증 열) — BusinessProfileRulesSpec(M)::SOLE means exactly one representative; JOINT/EACH mixes are allowed (C-22) | **A** | D-GOV-008 ⑧이 현행을 택했고 확정은 이 일괄 확인으로 미룸. 정책서 POL §5 대체 표(BASELINE-INDEX BI-BIZ-5) 연결; `BIZ.md` L66 |
| BR-BIZ-310 | 개업일·법인등록번호를 처음 입력하는 저장은 일반정보 변경으로 보아 사유를 요구하지 않는다(이미 값이 있을 때 바꾸면 중요정보) | 적용: D-GOV-008 ⑦(대장 행 추가 후 D-GOV-005 ④ 일괄 확인에서 현행 유지 확인 예정), BUSINESS-PROFILE-001 §4.5 | 일치(대장 검증 열) — BusinessProfileChangeSpec(M)::first input is general | **A** | D-GOV-008 ⑦이 현행을 택했고 확정은 이 일괄 확인으로 미룸. POL-004 대체 표(BI-BIZ-6) 연결; `BIZ.md` L67 |
| BR-BIZ-064 | 가맹점 코드 형식은 첫 글자 영문, 6~14자(^[a-z][a-z0-9]{5,13}$)이며 예약어(paycore·admin·system·test·live·demo·api·root·null·undefined) 완전·접두 일치는 거절한다 | 적용: MERCHANT-CODE-001 C-1·C-2 | 일치(대장 검증 열) — MerchantCodeSpec(M)::MC-1-1 형식·경계, 예약어 MERCHANT_CODE_RESERVED | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 외부 노출 키 형식이지만 거절(제한) 방향; `BIZ.md` L104 |
| BR-BIZ-067 | "첫 계약 승인"은 그 가맹점 대상 계약의 어느 Revision이든 승인 사실이 하나라도 있는 것이다(효력 시작 전 승인 포함, 철회·종료 뒤에도 승인 뒤로 본다) | 적용: MERCHANT-CODE-001 C-14 | 일치(대장 검증 열) — MerchantCodeChangeApprovalSpec(M)::LOCKED(계약 승인 뒤) | **B** | → §2 R2-Q5; `BIZ.md` L107 |
| BR-BIZ-084 | Site 심사 전이는 PENDING→APPROVED/REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED/REJECTED만 허용하고 같은 상태·PENDING 결정은 거절한다 | 적용: MASTER-SNAPSHOT-001 §4.4(관례 — 출처에 개별 전이 없음) | 일치(대장 검증 열) — SiteConfirmationAndReviewSpec(M)::REJECTED 종결, 같은 상태·PENDING 거절 | **B** | → §2 R5-Q14; SUSPENDED→REJECTED 허용 포함. D-GOV-004 ①(심사 거절 Site는 채널 점유 해제)과 겹치면 정지 Site를 거절해 채널을 풀고 같은 주소로 재신청할 수 있게 됨 — PC-72 개별 확인 권고에 따라 B; `BIZ.md` L124 |
| BR-BIZ-162 | 재개의 "새 기관 근거"는 그 개통에서 한 번도 쓰지 않은 근거 참조여야 한다 | 적용: MASTER-SNAPSHOT-001 §4.1(관례 해석), review m-2 | 일치(대장 검증 열) — ActivationSpec(M)::이전에 쓴 근거로 재개 거절 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 재개 조건을 좁히는 방향; `BIZ.md` L182 |
| BR-BIZ-167 | 개통이 UNDER_REVIEW인 동안 PRIMARY Site의 별도 적용 확인은 거절하고, 같은 (개통, Site)의 유효 확인은 하나만 둔다 | 적용: MASTER-SNAPSHOT-001 §4.2 review M-1 | 일치(대장 검증 열) — SiteConfirmationAndReviewSpec(M)::승인 전 PRIMARY 확인 거절; ActivationSpec(M)::두 번째 유효 PRIMARY 확인 없음 | **A** | `BIZ.md` L187 |
| BR-BIZ-172 | 통화 활성화는 소급 시작·소급 종료 금지, 미래 종료 예약 허용, 같은 (MID, 통화) 기간 중첩 금지다 | 적용: MASTER-SNAPSHOT-001 §4.3·§14-10(D-MD-005 유추 해석) | 일치(대장 검증 열) — CurrencyEnablementSpec(M)::소급 시작 거절, 중첩 거절, 미래 종료 1회 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] D-GOV-002 ④(외화 기준정보 비활성)와 충돌 없음; `BIZ.md` L192 |
| BR-BIZ-213 | 결제 허용 판정 순서는 Unavailable(Snapshot 없음·미지원·만료) → NotFoundOrForbidden(관계) → MERCHANT_SUSPENDED → CONTRACT_NOT_EFFECTIVE → Site 심사 → 개통·적용 확인 → 통화다 | 적용: MASTER-SNAPSHOT-001 §6.1(순서 관례) | 일치(대장 검증 열) — EntitlementEvaluatorSpec(P)::Unavailable 먼저, 관계 거절, 고정 순서 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 모두 거절 사이의 순서. 응답 코드만 달라짐; `BIZ.md` L221 |
| BR-BIZ-234 | 업무 근거 참조는 LIVE 전에 원천(계약 문서·심사 기록) 대조가 동작해야 하며, 그 전에는 LIVE에서 2인 명령을 열지 않는다 | 적용: MERCHANT-OPS-002 L-15·L-13(LIVE Gate 메모) | 미확인 — 대장 검증 `없음`, LIVE 2인 명령 차단 코드는 이번에 확인하지 않음 | **A** | LIVE 전 차단(fail-closed); `BIZ.md` L235 |
| BR-BIZ-280 | 관문의 "최신 접수 건"은 사업자 안 생성 순번이 가장 큰 건이다 | 적용: MERCHANT-FLOW-001 §4A.5 리뷰 m-3 | 미구현(관문) — "최신" 정의는 `JpaContractIntakeRepository.kt:17,28`(순번 내림차순 첫 행)로 규칙과 같으나, 이를 쓰는 가맹점 등록 관문은 FLOW-3 미착수 | **B** | → §2 R2-Q4; `BIZ.md` L270 |
| BR-BIZ-285 | 계약 담당자는 ACTIVE OPERATOR 계정만 고를 수 있다 | 적용: MERCHANT-FLOW-001 §4A.6(구현 관례) | 미구현 — MASTER는 형식만 안다(`ContractIntakeItems.kt:6`), ACTIVE OPERATOR 검사는 ADMIN 접수 명령(FLOW-2)과 함께. 접수 명령 미구현 | **A** | `BIZ.md` L275 |
| BR-BIZ-291 | 현재 증권 기록 사이에 (보증보험사, 증권번호)가 같으면 거절한다 | 적용: MERCHANT-FLOW-001 §4A.8 리뷰 MINOR-C | 미구현 — 담보 증권 명령 없음(FLOW-1은 접수 저장 구조·조회만) | **A** | `BIZ.md` L281 |
| BR-BIZ-301 | KYC 현재 값·마스킹 표시·원문 조회는 현재 대표자 행만 대상으로 하고 종료된 대표자 행은 저장만 하며 응답하지 않는다 | 적용: D-FLOW-009 Coordinator 적용 메모(3차 리뷰 MAJOR-3) | 미구현 — KYC 명령·조회 코드 없음(main 소스에 kyc 0건) | **C** | → §3 확정; `BIZ.md` L291 |

### 4.2 PAY (10행 — A 10 · B 0 · C 0 · D 0)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-PAY-040 | confirm 멱등 기록도 30일 보존한다(D-CPC-004의 operation 공통 적용) | 적용: D-CPC-004, AU CA-07 | 일치(DB) — `V1__payment_core.sql:96,103,119` 멱등 표가 operation 공통이고 `expires_at = created_at + 30 DAY` CHECK. confirm 30일 경계 시험은 없음(PAY 진단) | **A** | `PAY.md` L81 |
| BR-PAY-049 | 인증 결과 조회가 timeout·연결·5xx·형식 위반·404로 확인 불가이면 세션을 FAILED로 바꾸지 않고 IN_PROGRESS로 두어 같은 복귀 URL 재조회를 허용한다(TTL 경과 시 EXPIRED) | 적용: D-AUTHN-003, CLAUDE.md timeout 원칙 | 일치(대장 검증 열) — AuthenticationResultCheckSpec::R3 확인 불가 조회는 IN_PROGRESS 유지; CardAuthenticationFlowSpec::확인 불가 뒤 같은 복귀 URL로 VERIFIED 수렴 | **A** | CLAUDE.md "timeout만으로 효과 없음 단정 금지"와 같은 방향; `PAY.md` L95 |
| BR-PAY-051 | 같은 Payment의 비종료 세션은 하나, 귀속 세션은 최대 하나이며 새 인증 시작은 미귀속 세션(VERIFIED 미귀속 포함)을 대체(SUPERSEDED)해 사용 가능한 인증 근거를 하나로 한다 | 적용: D-AUTH-001, AC1 §3, AN AN-07 | 일치(대장 검증 열) — AuthenticationSessionSpec::Payment당 열린 세션 하나; AuthenticationSessionStartSpec::미귀속 VERIFIED도 대체 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 세션 하나·대체로 인증 근거 하나; `PAY.md` L97 |
| BR-PAY-053 | VERIFIED 뒤 다른 증거는 거절하고 덮어쓰지 않으며 같은 증거는 멱등이고, FAILED·EXPIRED 세션의 늦은 인증 성공은 승인 권한을 만들지 않는다 | 적용: D-AUTH-001, AC1 §5.3 | 일치(대장 검증 열) — AuthenticationSessionSpec::VERIFIED 뒤 다른 증거 거절; ::종료 세션의 늦은 성공 무시; AuthenticationResultCheckSpec::P1·P2·P3 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 늦은 성공이 승인 권한을 만들지 않음; `PAY.md` L99 |
| BR-PAY-063 | confirm 본문의 금액·통화·주문 ID 재진술이 Payment 생성 값과 다르면 승인을 시작하지 않고 충돌로 거절·저장한다 | 적용: 범위 §3-6, AU CA-08 | 일치(대장 검증 열) — ConfirmStartSpec::재진술이 고정 입력과 다르면 CONFIRM_CONTENT_CONFLICT·시작 없음 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 금액·통화 재진술 불일치 거절; `PAY.md` L114 |
| BR-PAY-081 | cardType의 최종 근거는 인증·기관 결과이며 클라이언트 선택만 신뢰하지 않는다 | 적용: 범위 §5(미채택 절), AC1 §3 | 미확인 — 대장 검증 `없음` | **A** | `PAY.md` L137 |
| BR-PAY-105 | '같은 승인'은 금액·통화·Plan·cardType과 ProviderTransactionId·승인번호·기관 거래일이 모두 같은 것이며, 하나라도 다르면 별개 승인 가능성으로 보고 상충 hold를 연다(두 번째 거래 없음) | 적용: D-AUTH-003, AC1 review M-1 | 일치(대장 검증 열) — AuthorizationResolutionSpec::같은 terms 다른 PTX·승인번호·기관 거래일 → CONFLICTING hold; ::식별자·terms 모두 같으면 효과 없음 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 키 동일성 정의지만 하나라도 다르면 hold(보수적) — PAY F-2 권장 "현행 확정"; `PAY.md` L176 |
| BR-PAY-106 | 같은 관찰 키에 다른 내용이 오면 첫 관찰을 유지하고 상충 증거로 append하며 CONFLICTING hold를 연다(진행 중이면 UNKNOWN) | 적용: D-AUTH-002, AC1 review m-1 | 일치(대장 검증 열) — AuthorizationResolutionSpec::진행 중 상충 증거·첫 관찰 유지·UNKNOWN + hold; ::승인·실패 상태 유지 + hold | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 상충 시 첫 관찰 유지 + hold; `PAY.md` L177 |
| BR-PAY-109 | 승인 실패·전송 전 실패는 비금융 PaymentAuthorizationFailed, 새 hold 개설은 AuthorizationIncidentDetected, 대사 이관은 AuthorizationOutcomeUnresolved로 상태 변경과 같은 TX에 기록하고 금융 사실 이벤트와 분류를 나눈다 | 적용: D-AUTH-002, D-AUTH-009, AC1 CA-19 | 일치(대장 검증 열) — ConfirmFlowSpec::DECLINED PaymentAuthorizationFailed 1·부분 승인 AuthorizationIncidentDetected 1; AuthorizationRecoverySpec::예산 소진 Unresolved 1 | **A** | `PAY.md` L180 |
| BR-PAY-116 | hold 해제 판정이 실제 기관 승인(REAL_APPROVAL_CONFIRMED)이면 그 주문은 승인 성공 이력 주문(새 Payment 금지)이 되고, 무효과(NO_EFFECT_CONFIRMED)면 원 종료 근거가 유지되어 새 Payment가 가능하다 | 적용: 시도 §2, D-CPD-002, AC1 §8.2(도출) | 일치(대장 검증 열) — IncidentHoldReleaseSpec::REAL → 성공 이력·새 Payment 없음; ::NO_EFFECT → 새 Payment 허용 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] CLAUDE.md 핵심 규칙(승인 성공 주문 재승인 금지, 무효과 확인 후 새 Payment) 직접 적용; `PAY.md` L192 |

### 4.3 CAN (26행 — A 15 · B 9 · C 2 · D 0)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-CAN-002 | 승인 결과 미확정(AUTHORIZING·UNKNOWN) Payment는 누구의 취소도 거절하고, 원 Attempt 조회·대사로 승인 결과를 먼저 확정한다. | 적용: CANCEL-CONTRACT-001 §13 말미 N-2(Coordinator 도출 채택), CLAUDE.md(UNKNOWN은 원 Attempt 조회 우선) | 일치 — `CancelStart.kt:88-93`(가맹점은 Payment·Authorization AUTHORIZED, 운영자는 확정 승인 거래가 있어야 함 — 아니면 PAYMENT_NOT_CANCELLABLE) + 대장 검증 `CancelStartSpec::CX-07` | **B** | → §2 R3-Q7; `CAN.md` L14 |
| BR-CAN-005 | 부분취소 허용은 원 경로 기관 프로파일 Capability(PaymentPlan·cardType별)로 판정하고 PG 자체 금지 규칙(예: 할부 부분취소 금지)을 두지 않으며, 판정할 수 없으면(프로파일 없음·버전 미지원) 취소를 시작하지 않는다(fail-closed, 저장 없음). | 적용: CANCEL-CONTRACT-001 §4.2 도출(K-2 + AUTH CA-04) | 일치(대장 검증 열) — `CancelStartSpec::CX-09 no capability no cancel`, `CancelStartServiceSpec::check card partial PARTIAL_CANCEL_NOT_SUPPORTED`, `CancelStartServiceSpec::sim-van-1 TemporarilyUnavailable` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] PG 자체 금지 없음은 허용 방향이지만 판정 불가 시 fail-closed. CAN 진단 Q-CAN-4 권장 "현행 유지", 실제 기관 연동 시 재검토 — 예외로 빼려면 표시; `CAN.md` L17 |
| BR-CAN-014 | 취소 통화는 원 승인 거래 통화와 같아야 하며 다르면 `CURRENCY_MISMATCH`로 거절한다(환전 없음). | 적용: CANCEL-CONTRACT-001 §5.2 도출(결제시도 §3 "승인된 주문의 금액·통화 변경 안 함") | 일치(대장 검증 열) — `CancelStartSpec::CX-06 another currency is rejected` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 통화 불일치 거절; `CAN.md` L31 |
| BR-CAN-019 | 범위 FULL은 완료·진행 중 반환이 없는(해제된 예약만 있는) Payment의 원 승인 전액 1회 취소이고, 그 밖(앞선 부분취소 뒤 남은 잔액 전부 포함)은 PARTIAL이며 시작 시점에 고정한다. | 적용: CANCEL-CONTRACT-001 CC-04 | 일치(대장 검증 열) — `CancelStartSpec::CX-01·CX-02·CX-03`, `CancelStartSpec::CB-02 a declined cancel is not a return` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] FULL/PARTIAL 판정은 표지만이 아니다 — PARTIAL이면 `CancelStart.kt:107-113`에서 기관 Capability 부분취소 검사(PARTIAL_CANCEL_NOT_SUPPORTED)를 받는다. 앞선 부분취소 뒤 잔액 전부도 PARTIAL이라 부분취소 미지원 경로에서는 잔액 취소가 거절될 수 있음; `CAN.md` L36 |
| BR-CAN-022 | 업무 거절로 취소 행이 생기지 않은 merchantCancelId는 새 Idempotency-Key로 다시 쓸 수 있다. | 적용: CANCEL-001 §4.8(n-8) | 일치(대장 검증 열) — `CancelStartServiceSpec::(n-8) rejected merchantCancelId can be used again` | **B** | → §2 R5-Q13; `CAN.md` L44 |
| BR-CAN-023 | 업무 키((Payment, merchantCancelId)·운영자 approvalId) UNIQUE는 멱등 기록 30일 보존과 무관하게 영구다. | 적용: CANCEL-CONTRACT-001 §4.5 "업무 키의 영속성" | 일치(대장 검증 열) — `PaymentCancellationSpec::business key (permanent, not tied to the 30-day retention)` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 업무 키 영구 UNIQUE; `CAN.md` L45 |
| BR-CAN-025 | 같은 키 재요청은 그 취소의 현재 결과를 돌려주며 기관을 다시 호출하지 않는다. | 적용: CANCEL-CONTRACT-001 CC-05(D-AUTH-004 선례) | 일치(대장 검증 열) — `CancelFlowSpec::same key replays the current result without a new institution call` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 같은 키 재요청은 기관 재호출 없음; `CAN.md` L47 |
| BR-CAN-037 | 취소 사건 hold가 해제되기 전에는 그 Payment의 가맹점 추가 취소를 막고 운영자 보정 취소는 허용한다. | 적용: D-CANCEL-006("hold 해제 전 그 Payment 추가 취소 차단") + D-CANCEL-001·005 — 계약 §4.10이 "(가맹점) 취소"로 한정 | 일치 — `CancelStart.kt:95-97` hold 검사는 `CancelInitiator.Merchant`에만 | **C** | → §3 확정; `CAN.md` L69 |
| BR-CAN-041 | 사건이 기록된 취소의 이후 결과는 기록만 하고 자동 확정·종료하지 않으며, hold는 같은 Payment의 다른 취소 결과 반영을 막지 않는다. | 적용: CANCEL-CONTRACT-001 CC-16(D-CANCEL-006·D-AUTH-008 원칙) | 일치(대장 검증 열) — `CancellationRecoverySpec::in-flight cancellation with an incident (CC-16)` | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 사건 뒤 결과 자동 확정 안 함; `CAN.md` L73 |
| BR-CAN-048 | 승인 대상 지문은 paymentId·금액·통화·반환 잔액 revision·approvalId·요청자·승인자·목적 종류·근거 참조로 만들고 사유 문구는 넣지 않으며, revision은 잔액이 업무적으로 바뀔 때마다 올라간다. | 적용: CANCEL-CONTRACT-001 CC-13 | 일치(대장 검증 열) — `CancelStartSpec::approved target binds approvalId, requester, approver, purpose…reason not bound`, `OperatorCancelTargetGoldenSpec`, `OperatorCancelFingerprintGoldenSpec`(ADMIN) | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 지문에 사유 미포함 — 사유 차이는 BR-CAN-052에서 충돌로 잡힘; `CAN.md` L85 |
| BR-CAN-052 | 같은 approvalId에 내용(결제·금액·통화·요청자·승인자·목적·근거·사유·지문·승인 시각·기한)이 다른 명령은 새 결정 없이 409로 거절하고(요청자·승인자만 다르면 `APPROVAL_IDENTITY_MISMATCH`, 그 밖 `APPROVAL_CONFLICT`) 기존 결과를 돌려주지 않는다. | 적용: CANCEL-001 CN-32(Reviewer 확인 요청), ADMIN-PAYMENT-OPS-001 §4.4.2 | 일치(대장 검증 열) — `AdminOperatorCancellationApiSpec::another amount APPROVAL_CONFLICT, other participants APPROVAL_IDENTITY_MISMATCH`, `OperatorCorrectionCancelSpec::another requester or approver identity is APPROVAL_IDENTITY_MISMATCH` | **B** | → §2 R4-Q10; `CAN.md` L89 |
| BR-CAN-063 | 참여자 정지로 멈춘 재전송은 자격을 다시 얻어도 재개하지 않는다. | 적용: ADMIN-PAYMENT-OPS-001 I-26 | 일치(대장 검증 열) — `OwnerExecutionRecoverySpec::…no resume after the role is granted back` | **A** | `CAN.md` L105 |
| BR-CAN-065 | PAYMENT 기록 결정의 내용이 ADMIN 승인과 다르면 무결성 플래그를 먼저 남기고 경보·감사한 뒤 그 결정을 그대로 반영한다(반영을 보류하지 않음). | 적용: ADMIN-PAYMENT-OPS-001 I-35(§12.2 "보류를 원하면 사용자 질문") | 일치(대장 검증 열) — `OwnerExecutionRecoverySpec::DecisionContentCheckSpec` | **B** | → §2 R3-Q8; `CAN.md` L107 |
| BR-CAN-067 | Payment당 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN) 운영자 보정 취소 승인은 1건이며 두 번째 요청은 409 `DUPLICATE_UNRESOLVED`다. | 적용: ADMIN-PAYMENT-OPS-001 §4.11·§4.12(unresolved_key — 사용자 결정 없음) | 일치(대장 검증 열) — `OwnerServiceApprovalFlowSpec::409 DUPLICATE_UNRESOLVED` | **B** | → §2 R3-Q9; `CAN.md` L109 |
| BR-CAN-068 | 진행 중 취소가 있는 Payment는 보정 취소 요청 단계에서 거절하고, 승인 단계에서는 승인을 PENDING으로 두고 TOTP를 소비하지 않는다(일시 거절로 승인이 소진되는 것을 막음). | 적용: ADMIN-PAYMENT-OPS-001 I-32(L-7) | 일치(대장 검증 열) — `OwnerServiceApprovalFlowSpec::advisory (L-7)`, `OwnerServiceApprovalFlowSpec::TARGET_BUSY` | **B** | → §2 R3-Q9; `CAN.md` L110 |
| BR-CAN-072 | 수수료 계산은 SETTLEMENT 소유이며 PAYMENT는 계산하지 않고 원 Authorization의 정책·권리 버전과 원 거래 ID를 취소 사실에 복사해 넘긴다. | 적용: CANCEL-CONTRACT-001 §5.1 도출(K-4 + D-MS2-003), D-CANCEL-008("계산 소유는 SETTLEMENT") | 일치(대장 검증 열) — `PaymentCancellationSpec::CX-11` | **A** | D-CANCEL-008("계산 소유는 SETTLEMENT")에서 도출; `CAN.md` L119 |
| BR-CAN-075 | 완료된 취소는 FINANCIAL 사실 이벤트 1건으로 원 거래 ID·승인액·Payment 안 반환 순번·반환 후 누적액·반환 상태·기관 취소 거래일·PG 확정 시각·개시자 종류를 넘기고 카드번호·Secret·운영자 개인정보·기관 계약계정은 넣지 않는다. | 적용: CANCEL-CONTRACT-001 CC-08, CANCEL-001 CN-13, D-AUTH-003(업무 키 = transactionId) | 일치(대장 검증 열) — `CancelFlowSpec::PaymentCancelled once`, `CancelFlowSpec::PaymentCancelled names the OPERATOR initiator` | **A** | `CAN.md` L122 |
| BR-CAN-077 | 취소일은 기관이 확정한 취소 사실의 날짜(기관 취소 거래일)이고 PG 회복·확정 시각과 섞지 않는다(시간대·기관 거래일 규약은 미결). | 적용: CANCEL-CONTRACT-001 §5.3 도출(K-8 + K-9), Q-CALENDAR PROPOSED | 일치(대장 검증 열) — `PaymentCancellationSpec::institution business date is preserved`(보존만) | **C** | → §3 확정; `CAN.md` L124 |
| BR-CAN-078 | PAYMENT는 매입 전/후로 취소를 나누지 않고 원 경로로 기관 취소 1건만 요청하며, 매입 쪽 처리는 FINANCIAL이 취소 사실로 정한다. | 적용: CANCEL-CONTRACT-001 §4.9 도출(INV-02 + 초기 58행) | 미확인 — 대장 검증 `없음` | **A** | `CAN.md` L125 |
| BR-CAN-080 | 취소 FAILED도 기관의 늦은 성공이 나중에 확인될 수 있으므로 최종 무반환으로 단정해 다른 수단으로 반환하지 않도록 안내하고, PayCore는 다른 반환 수단으로 자동 전환하지 않는다. | 적용: CANCEL-001 §4.11(D-CANCEL-006·007에서 옮긴 안내 문구) | 미구현(안내) — 관리자 화면·가맹점 문서로 옮기는 작업 미등록(CAN 진단 Q-CAN-10) | **A** | `CAN.md` L132 |
| BR-CAN-085 | 가맹점 취소 FAILED 응답은 실패 분류(DECLINED·NOT_SENT·NO_EFFECT)만 주고 기관 거절 세부 분류는 노출·영속하지 않는다. | 적용: CP-QUERY-001 F-7(Coordinator 판정 11) | 일치(대장 검증 열) — `CancelPaymentApiSpec::200 FAILED with failureCategory DECLINED` | **A** | 비노출(축소) 방향; `CAN.md` L142 |
| BR-CAN-089 | 결제 ID 정확 조회와 MID + 주문번호 조회는 기간 없이 허용한다. | 적용: TXN-OPS-001 I-3(Implementer 해석) | 일치 — 대장 검증 `TransactionLookupServiceSpec::the period is required (except the exact paymentId and order lookups)`. 단 D-TXOPS-001 문언("생성일 기간 필수")과 다름 | **B** | → §2 R4-Q11; `CAN.md` L151 |
| BR-CAN-094 | 거래 조회·상세는 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN만 할 수 있다. | 적용: TXN-OPS-001 I-1(Implementer 해석) | 일치(대장 검증 열) — `TransactionLookupServiceSpec::view roles (I-1)` | **B** | → §2 R4-Q11; `CAN.md` L156 |
| BR-CAN-095 | 관리 조회는 반환 잔액의 저장 값만 보이고 취소 가능액 식을 화면·조회에서 다시 만들지 않는다. | 적용: TXN-OPS-001 I-6 | 일치(대장 검증 열) — `AdminPaymentReaderSpec::the detail reads…return balance` | **A** | `CAN.md` L157 |
| BR-CAN-096 | 거래 목록의 가맹점 표시값(상호·가맹점 코드)은 MASTER 응답이 일부라도 이상하면 전부 버리고 MID만 보이며(틀린 상호를 다른 거래에 붙이지 않음) 목록 자체는 그대로 돌려준다. | 적용: TXN-LIST-ENRICH-001 §3.3(Coordinator C-) | 일치(대장 검증 열) — `TransactionDetailLabelSpec::a label failure still audits and returns the detail with merchant null and merchantLabels UNAVAILABLE`, `TransactionLookupHttpSpec::T-E3-8` | **A** | `CAN.md` L158 |
| BR-CAN-102 | KPI는 통화별·환경별(TEST/LIVE 합산 금지)로 나누고, 결과 미확정(UNKNOWN)·진행 중 승인·취소는 금액에서 빼고 "확인 필요"로 센다. | 적용: D-ADMIN-HOME-001 "코디네이터 구현 기본값(사용자 확인 대상 아님)" | 부분 일치 — `HomeKpiReaderSpec::T-K1-6`. 환경·통화 분리는 DB 수준 시험 없음(KPI1 §14 L-7) | **B** | → §2 R1-Q3; `CAN.md` L169 |

### 4.4 FIN (16행 — A 11 · B 3 · C 1 · D 1)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-FIN-008 | 전달 실패는 재시도(상한 없음, 연령 경보)하고, FINANCIAL 계약 코드가 붙은 400·409·422만 격리하며 격리는 완료가 아니다. | 적용: FIN-FACT-RELAY-001 I-2(Implementer 해석) | 일치(대장 검증 열) — FinancialFactRelayContractSpec::FR-P3, FinancialFactRelayDbSpec::FR-R2·FR-R4 | **C** | → §3 확정; `FIN.md` L21 |
| BR-FIN-012 | 원승인 자체가 정합성 보류이면 딸린 ORIGINAL_UNKNOWN 취소는 자동 해제하지 않고 REVIEW_REQUIRED로 둔다. | 적용: ACQUIRING-001 I-13 | 일치(대장 검증 열) — HoldReleaseRuleSpec::HR-D5, HoldReleaseDbSpec::HR-5 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] FIN RG-08 일괄 확인 권장(fail-closed); `FIN.md` L30 |
| BR-FIN-013 | REVIEW_REQUIRED 보류의 운영자 해소는 2인 승인("강제 Hold 해제")이다. | 적용: D-ADMIN-003 적용(ACQ-ADMIN-001 §2) | 미구현 — internal-admin에 REVIEW_REQUIRED 해소(강제 Hold 해제) 명령 없음(grep 0건). financial-batch `HoldReleaseRule.kt`·`OriginalUnknownHoldRelease.kt`는 자동 해제 판정만 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] D-ADMIN-003 2인 목록의 "강제 Hold 해제"에 REVIEW_REQUIRED 해소를 넣은 해석. 엄격 방향; `FIN.md` L31 |
| BR-FIN-019 | 같은 Payment에 해제되지 않은 결제 사실 보류가 하나라도 있으면 그 Payment의 모든 매입 후보를 대기시킨다. | 적용: ACQUIRING-001 C-5(Coordinator 결정, 리뷰 MJ-2) | 일치 — `AcquisitionCandidateRule.kt:24` `paymentHeld → Wait(PAYMENT_HELD)` | **B** | → §2 R1-Q1; `FIN.md` L42 |
| BR-FIN-020 | KRW 외 통화 승인은 매입하지 않고 대기시키며 종결 판정을 기록하지 않는다. | 적용: ACQUIRING-001 I-5, D-AUTH1-002(승인 KRW만), Q-SETTLE-CURRENCY | 일치 — `AcquisitionCandidateRule.kt:25` `currency != KRW → Wait(UNSUPPORTED_CURRENCY)` | **B** | → §2 R2-Q6; `FIN.md` L43 |
| BR-FIN-024 | 이미 봉인한 업무일의 마감 정책 버전이 바뀌었거나 사라졌으면 그 날 재봉인을 거부한다(새 값은 새 버전·새 시행일로). | 적용: ACQUIRING-001 I-21(D-ACQ-003 버전 관리에서 끌어냄) | 일치(대장 검증 열) — AcquisitionSealDbSpec::AQ-DB8 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] FIN RG-08 일괄 확인 권장(fail-closed); `FIN.md` L52 |
| BR-FIN-028 | 전송 불명 회복은 같은 파일 ID·같은 봉인 바이트의 확인 후 재업로드로만 하고 새 파일 ID로 다시 보내지 않는다. | 적용: ACQUIRING-001 I-10·I-25 | 일치(대장 검증 열) — AcquisitionTransmissionDbSpec::AQ-T10·AQ-T16 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] FIN RG-08 일괄 확인 권장(이중 전송 방지); `FIN.md` L56 |
| BR-FIN-040 | 원 SALE이 재매입된 경우 그 뒤 받은 취소는 가장 높은 세대 원 SALE의 결과를 기준으로 판정한다. | 적용: ACQ-ADMIN-001 I-14·L-6(CC-4 기대값 변경) | 일치 — `CancelCandidateRule.kt:48-53`(판정; 주석 :48-49 "rev 0.6까지는 세대 0만 정합") + `CancelCandidateRuleSpec::CC-8` | **B** | → §2 R1-Q2; `FIN.md` L78 |
| BR-FIN-041 | 원 SALE 상계액 + 이미 봉인한 CANCEL + 이번 취소가 원승인액을 넘거나 원거래와 값이 어긋나면 CANCEL 레코드를 만들지 않고 그 취소만 대기시킨다. | 적용: ACQUIRING-001 I-47 | 일치(대장 검증 열) — CancelCandidateRuleSpec::CC-3·CC-4 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] FIN RG-08 일괄 확인 권장(초과 취소 대기); `FIN.md` L79 |
| BR-FIN-050 | CANCEL 레코드의 재매입은 원 SALE이 현재 ACCEPTED일 때만 한다. | 적용: ACQ-ADMIN-001 I-7(D-ACQ-009에서 끌어냄) | 불일치(더 엄격) — `ReacquisitionExecutionRule.kt:50` CANCEL 레코드 재매입 자체를 `CANCEL_NOT_SUPPORTED`로 거절(I-7, B1 범위 밖). 규칙의 조건부 허용은 미구현 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] D-ACQ-009에서 끌어냄. 지금 코드는 더 엄격(전부 거절) — 규칙 확정해도 구현은 후속; `FIN.md` L93 |
| BR-FIN-051 | 봉인한 재매입 파일의 레코드가 그 사이 다른 세대에서 ACCEPTED되면 그 파일 전송을 보류(TRANSMISSION_WITHHELD)한다. | 적용: ACQ-ADMIN-001 L-9(D-ACQ-014에서 끌어냄) | 일치(대장 검증 열) — AcquisitionTransmissionRuleSpec::AQ-TD6, ReacquisitionResultDbSpec::RR-3·RR-4 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] D-ACQ-014에서 끌어냄(이중 매입 방지); `FIN.md` L94 |
| BR-FIN-053 | 재매입 승인은 승인 뒤 실행 기한 안에서만 실행되고 기한이 지나면 실행하지 않는다(운영자 보정 취소 D-OPS-002·009 선례). | 적용: ACQ-ADMIN-001 I-2·I-3(D-OPS 선례 적용) | 일치(대장 검증 열) — ReacquisitionExecutionDbSpec::RX-8·RX-10 | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] D-OPS-002·009 선례 적용; `FIN.md` L96 |
| BR-FIN-057 | 재매입 근거 참조가 카드번호 패턴이면 거부한다. | 적용: ACQ-ADMIN-001 rev 0.5 M-1·I-23 | 일치(대장 검증 열) — ReacquisitionCommandParserSpec::card number verdict | **A** | `FIN.md` L105 |
| BR-FIN-100 | 선지급 정책 Scope는 ContractServiceAgreement 또는 명시적 MID Override이며 GID 기반 자동 상속은 없다. | 기준: SCP §11("권장"), 연결 C | 미구현 — 정산·선지급 미착수 | **D** | → §3.1 보류; `FIN.md` L173 |
| BR-FIN-111 | HoldType 후보는 RISK·RECONCILIATION·CONTRACT·ACCOUNT·ADVANCE_LIMIT·MANUAL이고 Scope 후보는 MERCHANT_ACCOUNT·SETTLEMENT_ENTRY·SETTLEMENT_STATEMENT·PAYABLE·PAYOUT이다. | 기준: SCP §14·§20("후보") | 미구현 — 정산·지급 Hold 미착수 | **A** | 출처 `기준`(SCP "후보"). **"후보 목록으로서 확정"** — HoldType·Scope의 최종 목록·의미·동작은 SETTLEMENT 명세에서 정하며 이 확인으로 정해지지 않음; `FIN.md` L189 |
| BR-FIN-123 | FeePolicy·SettlementPolicy·BusinessCalendar·AutoCorrectionPolicy 변경은 운영 적용 전 과거 업무일 대상 Dry Run(기존 결과 대비)으로 비교한다. | 기준: SCP §26("권장") | 미구현 — 정책 Dry Run 미착수 | **A** | 출처 `기준`(SCP "권장"). 운영 적용 전 비교 — 안전 방향; `FIN.md` L206 |

### 4.5 WH (27행 — A 24 · B 0 · C 3 · D 0)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-WH-002 | 내부 위험·운영 이벤트(`*IncidentDetected`·`*OutcomeUnresolved`)는 가맹점에 보내지 않는다. | 적용: D-CPQ-001, AC1 §11, WEBHOOK-001 §4.1 도출·§17.9 | 일치(대장 검증 열) — MerchantNotificationPolicySpec::Outbox events that are not merchant notifications — not applicable | **A** | `WH.md` L13 |
| BR-WH-003 | 결제 생성(`PaymentRequested`)은 가맹점에 통지하지 않는다(가맹점은 동기 생성 응답으로 안다). | 적용: WEBHOOK-001 WH-01 | 일치(대장 검증 열) — MerchantNotificationPolicySpec::Outbox events that are not merchant notifications (creation) | **A** | `WH.md` L14 |
| BR-WH-004 | 진행 중 상태(AUTHORIZING·UNKNOWN, 취소 IN_PROGRESS·UNKNOWN) 진입은 통지하지 않고, 회복으로 확정된 결과를 포함해 확정 결과만 통지한다. | 적용: WEBHOOK-001 F-5·§4.1·§17.9, 범위 40행 | 일치(대장 검증 열) — ConfirmFlowSpec::PENDING → 202 UNKNOWN … no outbox event | **A** | `WH.md` L15 |
| BR-WH-008 | 통지 대상 이벤트가 그 이벤트가 생길 수 없는 Payment 상태와 함께 오면(또는 읽을 수 없으면) 숨기지도 보내지도 않고 비종결 실패로 남겨 경보하며, 같은 Payment의 뒤 이벤트는 그동안 보내지 않는다(다른 Payment는 계속). | 적용: WEBHOOK-001 rev 0.2 H-3·m-3 | 일치(대장 검증 열) — MerchantNotificationFanoutDbSpec::T-F5 anomalous row FANOUT_FAILED … later row stays PENDING; MerchantNotificationFanoutServiceSpec::a poison row … never SUPPRESSED | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 이상 행은 보내지도 숨기지도 않음; `WH.md` L19 |
| BR-WH-009 | 이전 generation 사건으로 주문이 보류 중이어도 현재 Payment가 AUTHORIZED로 확정되면 `payment.authorized`를 보낸다. | 적용: D-AUTH-011, WEBHOOK-001 §4.1·§18-11 | 미확인 — `MerchantNotificationPolicy.kt:50-53` 판정은 이벤트 종류 + Payment 상태만 본다(AUTHORIZED면 통지, 보류 여부 입력 없음). 이전 generation 보류 경우 시험 없음 | **A** | D-AUTH-011 근거; `WH.md` L20 |
| BR-WH-010 | Webhook 기능 배포 전에 쌓인 Outbox 이벤트는 가맹점에 보내지 않는다(과거 결과를 한꺼번에 보내지 않음). | 적용: WEBHOOK-001 §18-4 판정·§20.1-3 | 일치(대장 검증 열) — OutboxNotificationMigrationSpec::existing volume at V22 … old rows SKIPPED_PRE_DEPLOY and new inserts become PENDING | **A** | `WH.md` L21 |
| BR-WH-011 | 통지 시점에 그 (환경, MID)의 endpoint가 없으면 이벤트는 기록하고 순번을 쓰되 보내지 않으며, 나중에 endpoint가 생겨도 과거 이벤트를 자동으로 몰아 보내지 않는다(재전송은 운영자 명령). | 적용: WEBHOOK-001 WH-12 | 부분 일치 — endpoint 없으면 전달 행 없음은 시험 있음, "나중에 몰아 보내지 않음"은 시험 없음(대장 검증 열) | **A** | `WH.md` L22 |
| BR-WH-013 | 본문의 각 섹션 키·값은 같은 Payment의 GET 응답이 노출하는 키·값의 부분집합이다(Webhook으로 새로 드러나는 사실이 없다). | 적용: WEBHOOK-001 WH-06·rev 0.2 m-1 Coordinator 판정, D-CPQ-001·002·005 | 일치(대장 검증 열) — MerchantWebhookBodyJsonSpec::every webhook data key is a GET key; MerchantWebhookConsistencySpec::T-V1 every section of every body is a subset of what GET shows | **A** | D-CPQ-001·002·005 범위 안(축소 방향); `WH.md` L29 |
| BR-WH-016 | `merchantCode`는 코드가 전파된 MID에만 넣고 없으면 키를 생략(null 아님)하며, 추가 전에 만든 이벤트는 저장된 바이트 그대로(코드 없이) 재전송한다. | 적용: MERCHANT-CODE-001 C-36(MC-4), 03 스키마 가산 규칙 | 일치(대장 검증 열) — MerchantWebhookBodyJsonSpec::a MID without a code omits the key (not null); MerchantWebhookDeliveryDbSpec::the event stored before the promotion is resent with exactly the stored bytes | **A** | `WH.md` L32 |
| BR-WH-018 | 취소 본문의 `cancelTransactionId`는 PayCore `transactionId`이며 기관 거래번호(PTX)가 아니다. | 적용: WEBHOOK-001 rev 0.2 n-5 | 일치(대장 검증 열) — OutboxNotificationPayloadsSpec::cancelTransactionId is PayCore's transactionId, never the provider PTX; MerchantNotificationFanoutDbSpec::T-F4 bodies carry GET values | **A** | `WH.md` L34 |
| BR-WH-019 | 본문 값은 그 사실 시점 값이고 현재값(`cancellableAmount`·취소 진행 금액)은 넣지 않는다. 봉투 `createdAt`은 가맹점 이벤트 생성 시각(업무 시각 아님)이며 실패·만료·취소 실패 시각은 넣지 않는다. | 적용: WEBHOOK-001 WH-06·rev 0.2 m-1, D-CPQ-005 | 일치(대장 검증 열) — MerchantWebhookBodyJsonSpec::no time other than GET's own times and the envelope's creation time; MerchantNotificationFanoutServiceSpec::the envelope time is the fan-out time | **A** | `WH.md` L35 |
| BR-WH-021 | 가맹점은 판단에 서명된 본문 값을 쓰고 서명되지 않은 헤더(`PayCore-Event-Id`·`-Event-Type`·`-Delivery-Attempt`) 값을 근거로 삼지 않는다(가맹점 안내). | 적용: WEBHOOK-001 rev 0.2 n-7 | 해당 없음(가맹점 안내 문구) — 안내 문서 반영 여부 미확인 | **A** | `WH.md` L37 |
| BR-WH-026 | 서명 `t`는 시도마다 새 전송 시각이고, 본문 바이트는 그 이벤트의 모든 시도·재전송에서 같다. | 적용: WEBHOOK-001 WH-08·§6.1 | 일치(대장 검증 열) — MerchantWebhookDeliveryDbSpec::T-D2 the resend carries the same event id and the same body bytes; only t and the signature differ | **A** | `WH.md` L47 |
| BR-WH-027 | HMAC 키는 가맹점에게 보이는 비밀 문자열 전체(`whsec_…`)의 UTF-8 바이트다. | 적용: WEBHOOK-001 WH-19 | 일치(대장 검증 열) — MerchantWebhookSignatureGoldenSpec::vector … equals the golden header | **A** | `WH.md` L48 |
| BR-WH-030 | 공개 `eventId`는 가맹점 이벤트용 새 식별자이고 내부 Outbox `event_id`는 노출하지 않는다. | 적용: WEBHOOK-001 WH-05 | 일치(대장 검증 열) — MerchantNotificationFanoutServiceSpec::… a new public id; MerchantWebhookDeliverySpec::identifiers have the server prefix | **A** | `WH.md` L56 |
| BR-WH-032 | `sequence`는 1부터 그 Payment의 인과 순서대로 붙고, 숨기거나 해당 없는 이벤트는 번호를 쓰지 않는다. 서로 다른 Payment의 sequence는 관계가 없다. | 적용: WEBHOOK-001 WH-04·§5.4 | 일치(대장 검증 열) — MerchantNotificationFanoutServiceSpec::suppressed events take no number; MerchantNotificationFanoutDbSpec::C-F1 each payment's sequence is 1, 2, 3 in Outbox id order | **A** | `WH.md` L58 |
| BR-WH-033 | 전달 순서는 보장하지 않는다(재시도·병렬 전달). 가맹점은 `sequence`로 낡은 이벤트를 구별하거나 GET으로 현재 상태를 확인한다. | 적용: WEBHOOK-001 WH-11·§5.7 | 일치(설계) — 시험 없음(WH 진단) | **C** | → §3 확정; `WH.md` L59 |
| BR-WH-034 | 전달 성공은 시간 안의 HTTP 2xx이고 응답 본문은 해석하지 않는다. 그 밖(3xx·4xx·5xx·timeout·연결 실패·목적지 차단)은 모두 실패다. | 적용: WEBHOOK-001 WH-09 | 일치(대장 검증 열) — HttpMerchantWebhookSenderSpec::2xx is success; non-2xx is a failure with its status; 3xx is REDIRECT | **A** | `WH.md` L60 |
| BR-WH-035 | 늦게 확인된 2xx는 소유를 잃은 시도이거나 포기(GAVE_UP) 뒤라도 전달 완료로 바꾸며, 완료된 전달은 실패로 되돌리지 않는다(성공 단조). | 적용: WEBHOOK-001 rev 0.2 n-1 | 규칙과 코드는 일치(`MerchantWebhookDelivery.kt:76-80` GAVE_UP→DELIVERED) — (b) 부분이 **D-GOV-004 ④와 불일치**. 정비 시 `reconstitute` 불변식(`MerchantWebhookDelivery.kt:122-127`, DELIVERED·GAVE_UP 상태별 require)도 함께 바뀜 | **C(b)+A(a)(c)** | → §3 (b) 대체, (a)(c) Q-A; (a)(c)는 A 질문에 포함, (b)는 §3 대체. (a)를 반영하지 않으면(늦은 2xx 무시) 이미 받은 이벤트를 다시 보내는 중복 재전송 위험; `WH.md` L61 |
| BR-WH-037 | 저장된 본문의 해시가 맞지 않으면 그 이벤트를 보내지 않고 무결성 실패로 기록한다. | 적용: WEBHOOK-001 rev 0.2 m-2 | 일치(대장 검증 열) — MerchantWebhookDeliveryServiceSpec::a stored body whose SHA-256 no longer matches is not sent | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 해시 불일치 미전송; `WH.md` L63 |
| BR-WH-043 | 수동 재전송은 새 이벤트가 아니라 같은 `eventId`·같은 본문이며, 대상·사유·권한·결과를 감사로 남긴다. | 적용: WEBHOOK-001 §5.9, 07-operations(재처리 감사) | 미구현 — 수동 재전송은 WEBHOOK-ENDPOINT-001(BACKLOG), WH 진단 BR-WH-042 미구현 | **A** | `WH.md` L74 |
| BR-WH-044 | 가맹점 이벤트·전달·시도 기록(GAVE_UP 포함)은 삭제하지 않는다(보관·파기 정책은 LIVE Gate). | 적용: WEBHOOK-001 §5.8·§5.12, 03 금지사항 | 일치(대장 검증 열) — MerchantWebhookPersistenceSpec::the runtime account cannot change or delete it (insert-only, 1142) | **A** | 삭제 금지 — 보관·파기 정책은 LIVE Gate; `WH.md` L75 |
| BR-WH-057 | endpoint URL·서명 키는 전송 시점에 조회하고 전달 기록에 복사하지 않는다(변경이 다음 시도부터 반영, 비밀 복제 없음). | 적용: WEBHOOK-001 WH-12 | 일치(대장 검증 열) — MerchantWebhookDeliveryServiceSpec::the attempt row records the endpoint ref, the key ids … never the URL or the body | **A** | `WH.md` L98 |
| BR-WH-060 | Webhook 경로의 결함은 승인·취소 등 업무 트랜잭션을 실패·rollback시키거나 지연시키지 않는다(업무 TX는 Outbox 기록까지만). | 적용: WEBHOOK-001 WH-02·rev 0.2 H-3 | 일치(대장 검증 열) — ArchitectureSpec::business application services do not depend on merchant webhook ports; MerchantNotificationFanoutDbSpec::C-F2 … commit without waiting | **A** | 업무 TX 보호; `WH.md` L106 |
| BR-WH-061 | GET에서 숨기는 사실은 Webhook에도 없다(조회와 통지는 같은 사실 범위). | 적용: WEBHOOK-001 §4.4, D-CPQ-002 | 일치(대장 검증 열) — MerchantNotificationFanoutDbSpec::T-F3 …; MerchantWebhookConsistencySpec::T-V1 | **A** | `WH.md` L107 |
| BR-WH-062 | 가맹점 통지 처리 표시와 FINANCIAL 전달 표시는 독립이며, 가맹점 2xx는 FINANCIAL 전달 완료로 보지 않는다. | 적용: WEBHOOK-001 F-1·WH-03, 03("단순 HTTP 성공은 대체가 아니다") | 일치(대장 검증 열) — MerchantNotificationFanoutDbSpec::T-F6 publish_status stays PENDING | **A** | `WH.md` L108 |
| BR-WH-077 | FINANCIAL 사실 전달은 포기 없이(상한 없이) 재시도하고, 계약 코드가 없는 4xx·401·5xx·연결 실패도 재시도한다. | 적용: FIN-FACT-RELAY-001 I-2(Implementer 해석) | 일치(대장 검증 열) — FinancialFactRelayDbSpec::FR-R2 … next attempt = now + 1s backoff; FinancialFactRelayContractSpec::m-1: 400·409·422 are quarantined only with the matching FINANCIAL contract error code (golden statusCases) | **C** | → §3 확정; `WH.md` L133 |

### 4.6 ADM (26행 — A 24 · B 1 · C 1 · D 0)

| BR-ID | 규칙(원문) | 출처 | 현재 코드 동작 | 분류 | 비고 |
|---|---|---|---|---|---|
| BR-ADM-009 | 미인증 로그인 시도의 감사에는 login_name 원문 대신 비밀 키 HMAC 값만 남긴다. | 적용: BOOT1 n-2, D-ADMIN-004 | 일치(대장 검증 열) — LoginServiceSpec::an unknown login is audited as ANONYMOUS with only the keyed HMAC | **A** | `ADM.md` L22 |
| BR-ADM-017 | 권한은 세션에 저장한 값이 아니라 요청마다 계정 행의 현재 상태(ACTIVE)와 현재 활성 역할로 판정한다. | 적용: D-ADMIN1-003, D-ADMIN1-004(즉시 무효), BOOT1 D-08 | 일치(대장 검증 열) — AdminAccountCommandSpec::the requester's permission is judged on the locked (latest committed) row | **A** | `ADM.md` L35 |
| BR-ADM-022 | 정지하면 대상의 미사용 등록 코드를 모두 폐기하고, 정지 해제로 되살리지 않는다. | 적용: BOOT1 rev 0.3 H-B | 일치(대장 검증 열) — AdminAccountCommandSpec::its unused enrollment codes are revoked (H-B); EnrollmentFlowSpec(SuspendRevokesEnrollment) | **A** | `ADM.md` L45 |
| BR-ADM-023 | SECURITY_ADMIN은 자기 계정을 정지할 수 없다. | 적용: BOOT1 rev 0.3 n-E | 일치(대장 검증 열) — AdminAccountCommandSpec::suspending oneself or an unknown target is rejected | **A** | `ADM.md` L46 |
| BR-ADM-024 | 역할을 가진(활성 또는 정지) 계정의 등록 코드는 1인으로 재발급할 수 없고, 2인 재설정 승인 또는 복구 명령으로만 다시 발급한다. | 적용: D-ADMIN1-003("역할 없는 계정"), BOOT1 rev 0.4 H-1 | 일치(대장 검증 열) — AdminAccountCommandSpec::a role-bearing pending account (bootstrap pair) is refused; AdminAccountCommandSpec::a credential-reset account holding only suspended roles is refused | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] 재발급은 2인·복구로만; `ADM.md` L47 |
| BR-ADM-028 | 역할을 가진 계정에 확정 자격(비밀번호·TOTP) 중 하나라도 없으면 2인 재발급은 두 요소를 모두 설정하게 한다. | 적용: BOOT1 rev 0.5 m-1 | 일치(대장 검증 열) — AdminOwnedCommandSpec::a second reset of a pending account … must set both elements | **A** | `ADM.md` L56 |
| BR-ADM-036 | 복구 자동 재활성은 완료 시점에도 그 역할의 활성 계정이 0명이고 회차가 열려 있을 때만 한다. | 적용: BOOT1 rev 0.3 m-B | 일치(대장 검증 열) — RecoveryCommandSpec::① a task-B APPROVER enrolls first … does not restore APPROVER; RecoveryCommandSpec::③ … ⑤ a code used after the episode expired is refused | **A** | `ADM.md` L69 |
| BR-ADM-041 | ACTIVE가 아닌 계정(LOCKED·SUSPENDED·등록 대기)은 역할을 가지고 있어도 어떤 권한도 없다. | 적용: D-ADMIN1-003, D-ADMIN1-005 | 일치(대장 검증 열) — AdminPermissionSpec::LOCKED, SUSPENDED and PENDING_ENROLLMENT accounts have no permission even with roles | **A** | `ADM.md` L79 |
| BR-ADM-046 | 업무 운영 명령(운영자 보정 취소·재매입·가맹점 승인 요청)의 요청자는 OPERATOR이고, ADMIN 소유 명령(계정·역할·자격)의 요청자는 SECURITY_ADMIN이다. | 적용: BOOT1 §4.5.1, OPS1 §4.9, D-ADMIN1-003 | 일치(대장 검증 열) — OwnerServiceApprovalFlowSpec::a non-OPERATOR is denied without calling PAYMENT; AdminOwnedCommandSpec::non-SECURITY_ADMIN requesters are denied | **A** | `ADM.md` L84 |
| BR-ADM-057 | 요청 뒤 요청자가 정지되거나 요청 역할을 잃으면 승인 시 무효(INVALIDATED)로 닫고 실행하지 않는다. | 적용: BOOT1 rev 0.3 n-A | 일치(대장 검증 열) — AdminOwnedCommandSpec::the requester was suspended after the request → INVALIDATED | **A** | `ADM.md` L100 |
| BR-ADM-060 | 같은 (종류, 대상)의 대기 중 승인 요청은 하나만 둔다. | 적용: BOOT1 n-6 | 일치(대장 검증 열) — AdminOwnedCommandSpec::a second PENDING of the same kind and target is DUPLICATE_PENDING; OwnerServiceApprovalFlowSpec::409 DUPLICATE_UNRESOLVED | **A** | [금융·키·상태 의미 있음 — 안전 방향이라 A] (종류, 대상) 대기 하나; `ADM.md` L103 |
| BR-ADM-062 | 철회는 요청자 본인만, 대기(PENDING) 중일 때만 할 수 있다. | 적용: BOOT1 n-B | 일치(대장 검증 열) — AdminOwnedCommandSpec::withdraw: only the requester, only while PENDING | **A** | `ADM.md` L105 |
| BR-ADM-063 | 승인 요청은 유효기간이 지나면 만료(EXPIRED)되고 실행하지 않는다(판정은 승인 TX 시각). | 적용: BOOT1 n-5 | 일치(대장 검증 열) — AdminOwnedCommandSpec::expiry is judged with the TX clock | **A** | `ADM.md` L106 |
| BR-ADM-071 | 감사 대상: 로그인 각 단계·잠금·로그아웃·세션 교체, 미인증 경로 IP 거부, 계정 생성·정지·코드 발급/사용/폐기, 역할 부여·회수·정지·재활성, 승인 요청·승인·거절·철회·만료·무효·실행 결과, 재인증, 마스킹 해제, 감사 조회, 부트스트랩·복구. | 적용: BOOT1 §4.6(D-ADMIN-002·D-ADMIN1-001·008 일부) | 일치(대장 검증 열) — AuditFailureClosesActionSpec; LogoutAuditFailureSpec; AdminOwnedCommandSpec::withdrawing an expired request expires it with an audit row | **A** | `ADM.md` L124 |
| BR-ADM-072 | 감사를 남기지 못하면 그 행위도 실패한다(상태 변경과 같은 TX, 조회는 결과 미반환). | 적용: BOOT1 §4.6, PII §6-5(사용자 결정은 D-TXOPS-002 거래 상세 조회 한정) | 일치(대장 검증 열) — AuditFailureClosesActionSpec::account creation / suspension / enrollment completion / authentication failure; TransactionAuditFailureHttpSpec | **A** | 사용자 결정 D-TXOPS-002는 거래 상세 조회 한정 — 전체 확대는 fail-closed 방향; `ADM.md` L125 |
| BR-ADM-073 | 관리자 감사 detail에는 고정 `key=value` 요약만 남기고, 자유 문구는 마스킹 해제 사유(reason 열)에만 허용한다. | 적용: BOOT1 §4.6, D-ADMIN1-019 | 일치(대장 검증 열) — AdminNonDisclosureSpec::the unmask reason only in its audit reason column; OperatorDecisionReconciliationSpec::a finding record accepts only column names or codes as detail | **A** | `ADM.md` L126 |
| BR-ADM-101 | KYC 현재 값·마스킹 표시·원문 조회는 현재 대표자 key 행만 대상으로 하고, 종료된 대표자 key 행은 저장만 하며 응답하지 않는다. [경계 BIZ] | 적용: D-FLOW-009 Coordinator 적용 메모, D-FLOW-008 ②③ | 미구현 — BR-BIZ-301과 같음 | **C** | → §3 확정; `ADM.md` L169 |
| BR-ADM-108 | 사업자등록번호를 식별자로 쓰지 않으며 화면 URL·브라우저 저장소에 원문 번호·내부 키를 두지 않는다. | 적용: D-BIZLOOKUP-001, BUSINESS-PROFILE-001 §8 | 일치(대장 검증 열) — BusinessesScreen.test.tsx(window.location.href에 biz_ 없음); test/fakeApi(브라우저 저장소 비어 있음 확인) | **A** | D-BIZLOOKUP-001·D-PII-001 방향; `ADM.md` L181 |
| BR-ADM-111 | 카드번호 패턴 판정: 십진 숫자(모든 문자권) 13~19자리, 숫자 사이 공백 또는 하이픈 1개 허용, 앞뒤가 숫자가 아님, Luhn 검사 없음. 거부 응답은 고정 code만 돌려주고 값은 넣지 않는다. | 적용: OPS1 I-17·I-111, PII §4.4 | 일치(대장 검증 열) — OperatorCancelInputRulesSpec::ReasonCardData (I-17); ::card digit runs in any decimal digit script; ::every refusal has one fixed reason code | **A** | `ADM.md` L184 |
| BR-ADM-120 | LIVE 배치는 모든 주소를 허용하는 목록(0.0.0.0/0, ::/0)을 거부한다. | 적용: BOOT1 S4 m-5 | 일치(대장 검증 열) — IpAllowlistSpec::a LIVE deployment refuses an allowlist that permits every address | **A** | `ADM.md` L203 |
| BR-ADM-121 | 배치 모드(DEMO/LIVE)는 기본값 없이 반드시 지정하고, LIVE에서 TOTP 암호화 키·login_name HMAC 키가 없거나 알려진 Demo 키이거나 보안 수치가 Demo 값이거나 BCrypt cost가 하한 미만이면 기동하지 않는다. | 적용: BOOT1 n-4, Q-ADMIN-SEC-VALUES | 일치(대장 검증 열) — LiveModeStartupGuardSpec::every Demo key, every Demo security value and a BCrypt cost below the LIVE minimum are reported; ::the deployment mode has no default | **A** | Q-ADMIN-SEC-VALUES(수치)는 미결 그대로; `ADM.md` L204 |
| BR-ADM-130 | 간편 로그인은 실패 계수를 올리지도 0으로 되돌리지도 않고, 잠김·정지·등록 대기·한도 도달 계정과 허용 목록 밖 이름은 같은 401로 거부·감사한다. | 적용: DEMO1 C-5, §5 | 일치(대장 검증 열) — DemoQuickLoginHttpSpec(T-4) | **A** | DEMO 한정 임시 기능; `ADM.md` L218 |
| BR-ADM-131 | DEMO + 간편 로그인 켜짐인데 IP 허용 목록이 모든 주소를 허용하면 기동하지 않는다. | 적용: DEMO1 MINOR-2(a) | 일치(대장 검증 열) — DeploymentModeAllowlistSpec(T-15) | **A** | DEMO 한정 임시 기능; `ADM.md` L219 |
| BR-ADM-132 | 기능을 끈 뒤 남은 간편 로그인 세션은 다음 요청에서 폐기하고, 켜짐 조건은 요청마다 다시 판정한다. | 적용: DEMO1 MINOR-B | 일치(대장 검증 열) — DemoQuickLoginDisabledHttpSpec(T-12)::defense in depth: even a demo-session context … gets no skip | **A** | DEMO 한정 임시 기능; `ADM.md` L220 |
| BR-ADM-137 | KPI는 통화별로 나누어 보이고, 결과 미확정(UNKNOWN)·진행 중은 금액에서 빼고 '확인 필요'로 세며, TEST와 LIVE를 섞지 않는다. | 적용: D-ADMIN-HOME-001(Coordinator 구현 기본값) | 부분 일치 — `HomeKpiQueryServiceSpec`·`HomeKpiReaderSpec::T-K1-6`. 환경·통화 분리 DB 시험 없음(KPI1 §14 L-7) | **B** | → §2 R1-Q3; `ADM.md` L230 |
| BR-ADM-138 | KPI 조회 역할은 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN이며 UNMASK만 가진 계정은 볼 수 없다. | 적용: KPI1 C-2 | 일치(대장 검증 열) — HomeKpiServiceSpec::T-K3-1·2 VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN get the document; UNMASK-only … forbidden | **A** | KPI 조회 역할 — KPI 금액 정의(BR-ADM-137, R1-Q3)와 별개; `ADM.md` L231 |

## 5. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(RULE-CONFIRM-001 — 기준 `6da29e7` + 작업 트리, `제안` 121행) |
| 0.1a | 2026-11-25 | 독립 리뷰 CHANGES_REQUIRED 반영: M-1 A·C를 라운드 0 질문 2개로(질문 수 3개 이하), M-2 BR-WH-035 (a)(b)(c) 분리 — (b)만 대체·reconstitute 불변식·중복 재전송 위험 명시, M-3 BR-FIN-100 보류(D)·BR-FIN-111 "후보 목록으로서 확정" 문구, m-1 인용 줄 정정(CancelStart.kt:88-93·95-97, CancelCandidateRule.kt:48-53, MerchantNotificationPolicy.kt:50-53), m-2 fail-closed 우선순위 명시·⚑ 표시, m-3 BR-BIZ-084 → B(D-GOV-004 ① 상호작용), m-4 Q7 대안에 CLAUDE.md 개정 필요, m-5 BR-CAN-019 Capability 검사 영향, m-6 범위 밖 잔여 |
