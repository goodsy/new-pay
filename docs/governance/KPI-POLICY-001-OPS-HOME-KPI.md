# KPI-POLICY-001 운영 홈 KPI 집계 정책과 구현·시험 정합 검증 (rev 0.1)

- **rev 0.1** (2026-11-26, D-GOV-010 ⑤ 사용자 지시에 따른 정책 문서 초안 + 정적 정합 검증). 자기 완료 판정 아님(Reviewer·사용자 확인 대상).
- 범위: 관리자 운영 홈의 "오늘 순승인액"·"오늘 승인 건수" KPI(명세 ADMIN-HOME-KPI-001 rev 0.3a)와 사용자 지시 9항목. 지급 예정액(BR-CAN-103)·KPI 조회 권한(BR-ADM-138)은 바꾸지 않으며 참고로만 둔다.
- 이 문서가 **하지 않는 일**: 소스·시험·규칙 대장(`docs/rules/*.md`)·`docs/DECISIONS.md`·명세 본문 수정, 커밋. 대장 반영은 §2의 **반영안**으로만 두고, 불일치는 §4에 보고만 한다.
- 근거(우선순위 순): D-GOV-010 ⑤(2026-11-26 사용자 지시 — BR-CAN-102·BR-ADM-137 현행 유지 승인 + 집계 정책 9항목), D-ADMIN-HOME-001 ①~④, D-ADMIN-HOME-002 ①~③, D-GOV-002 ③④, D-CANCEL-003·006·011, INV-C2·C8(CANCEL-CONTRACT-001), D-CPD-001(TEST/LIVE), D-CP-002(통화·minor unit), 대장 BR-CAN-097~104·BR-ADM-135~138.
- 검증 기준 소스: `main` HEAD `30c550c`(작업 트리의 미커밋 변경은 `docs/`·`harness/`뿐 — `apps/` 변경 없음). 파일 hash(git blob 앞 12자): `JooqHomeKpiReader.kt` dfccfd53f7dc, `HomeKpiWindow.kt` 7b89532a05e4, `HomeScreen.tsx` 68aee6ee465e, `ADMIN-HOME-KPI-001-HOME-KPI.md` 3256f7b9f983.
- **검증 방식**: 코드·SQL·migration·시험 원문을 직접 읽은 **정적 대조**다. 이번 작업에서 시험을 실행하지 않았다(실행 증거 아님). 시험 "있음"은 해당 시험 코드가 저장소에 있다는 뜻이며 최신 통과 여부는 Verifier가 별도로 확인해야 한다.

## 0. 한눈에

| 사용자 지시(D-GOV-010 ⑤) | 정책(§1) | 현재 구현 | 판정 요약 |
|---|---|---|---|
| ① TEST/LIVE 분리·비합산 | P-ENV | 행 키에 환경, 화면 환경별 묶음 | 코드 일치, DB 수준 분리 시험 없음 |
| ② 통화별 분리 | P-CUR | 행 키에 통화 | 코드 일치, DB 수준 분리 시험 없음 |
| ③ 확정 승인·취소만 금액 | P-INC | `p.status='AUTHORIZED'` + 확정 거래 행만 | 일치 |
| ④ UNKNOWN·진행 중 금액 제외 | P-INC | 위 조건으로 자동 제외 | 일치 |
| ⑤ UNKNOWN 건수·진행 중 건수 각각 표시 | P-ATT | **없음**(ADMIN-HOME-ATTENTION-001 BACKLOG, 명세 미작성) | **불일치** |
| ⑥ 승인·취소·순결제금액 산출 기준 | P-DEF·P-CALC | API에 세 값 모두, 화면은 순액만 | 산출 일치, 화면 표시·명칭은 결정 필요(Q-KPI-3) |
| ⑦ 순결제금액 ≠ 정산금액 | P-SETL | 화면 표지 "PG 확정 시각 기준 · 정산 금액 아님" | 일치 |
| ⑧ 전체·부분·반복 부분취소·재처리 중복 방지 | P-DUP | 거래 행 단위 합산 + DB UNIQUE | 일치, 재처리는 KPI 수준 시험 없음 |
| ⑨ 기준일·시간대 명시 | P-TIME | [KST 0시, asOf), confirmed_at, 응답 `timeZone` | 계산 일치, 화면 구간 표기 부족 |

검증표(§3) 35항목: **일치 24 · 불일치 5 · 시험 없음 4 · 확인 불가 2**.

## 1. 정책 (정본 후보 문장)

표기: **[확정 후보]** = 사용자 결정(D-ID)이나 이번 사용자 지시로 정해진 문장. **[제안]** = 기존 결정·불변식에서 도출한 문장(사용자 확인 전). **[사용자 결정 필요]** = 기존 결정으로 정해지지 않음 — §1.9·§5에 권장안·대안·영향을 두며 이 문서가 확정하지 않는다.

### 1.1 P-DEF 용어 정의

| 용어 | 정의 | 상태 | 근거 |
|---|---|---|---|
| 집계 구간 | 조회 시각(asOf)의 KST 날짜 00:00:00.000부터 asOf 직전까지 **[from, asOf)** | 확정 후보 | D-ADMIN-HOME-001 ②, D-GOV-010 ⑤ ⑨ |
| 확정 승인 거래 | PG가 승인 성공을 확정해 기록한 append-only 승인 거래(`payment_transaction.type = AUTHORIZATION`)로서 그 Payment가 AUTHORIZED인 것 | 확정 후보 | D-GOV-010 ⑤ ③, D-ADMIN-HOME-002 ① |
| 확정 취소 거래 | PG가 취소 성공을 확정해 기록한 append-only 취소 거래(`type = CANCEL`, 취소 operation COMPLETED와 1:1 — INV-C2)로서 그 Payment가 AUTHORIZED인 것. 운영자 보정 취소 포함 | 확정 후보 | D-GOV-010 ⑤ ③, INV-C2, BR-CAN-097(T-K1-15) |
| **승인금액** | 구간 안에 확정된 확정 승인 거래 금액의 합(환경·통화별, minor unit 정수, ≥ 0) | 확정 후보 | D-ADMIN-HOME-001 ②, D-GOV-010 ⑤ ⑥ |
| **취소금액** | 구간 안에 확정된 확정 취소 거래 금액의 합(전액·부분취소, 이전 날짜 승인의 취소 포함, ≥ 0) | 확정 후보 | D-ADMIN-HOME-001 ②, D-GOV-010 ⑤ ⑥ |
| **순결제금액** | 승인금액 − 취소금액(음수 가능). 현 명세·화면 이름 "오늘 순승인액"과 **같은 값**이다(이름 통일 여부는 Q-KPI-3) | 확정 후보(값) / 사용자 결정 필요(명칭) | D-ADMIN-HOME-001 ①②, D-GOV-010 ⑤ ⑥ |
| 승인 건수 | 구간 안에 확정된 확정 승인 거래 중, asOf까지 확정된 취소 거래 누적액이 승인액보다 작은 결제 수(부분취소 건 포함, 전액취소 건 제외) | 확정(BR-CAN-098) | D-ADMIN-HOME-001 ③ |
| **확인 필요 — UNKNOWN 건수** | 결과 미확정(UNKNOWN)인 승인·취소의 수. 금액에 넣지 않는다 | 확정 후보(분리 표시 원칙) / 사용자 결정 필요(대상·기간 — Q-KPI-1) | D-GOV-010 ⑤ ④⑤, D-ADMIN-HOME-001 기본값 |
| **확인 필요 — 진행 중 건수** | 기관 처리가 끝나지 않은(결과 대기) 승인·취소의 수. UNKNOWN 건수와 **합치지 않고 따로** 보인다 | 확정 후보(분리 표시 원칙) / 사용자 결정 필요(대상·기간 — Q-KPI-1) | D-GOV-010 ⑤ ⑤ |
| 확인 필요 — 기타 예외 | 늦은 취소 성공 FORCED, 종료 Payment의 늦은 승인과 그 보정 취소 — 금액·건수에서 빼고 "확인 필요"로 셈 | 확정(BR-CAN-099·100) | D-ADMIN-HOME-002 ①② |

### 1.2 P-CALC 산출식(환경 e, 통화 c)

- 승인금액(e,c) = Σ 금액 | 확정 승인 거래 ∧ from ≤ confirmed_at < asOf ∧ Payment.environment = e ∧ 거래 통화 = c. **[확정 후보]**
- 취소금액(e,c) = Σ 금액 | 확정 취소 거래 ∧ 같은 구간·환경·통화. 원승인의 날짜는 묻지 않는다. **[확정 — BR-CAN-097]**
- 순결제금액(e,c) = 승인금액(e,c) − 취소금액(e,c), 음수 허용. **[확정 — BR-CAN-097]**
- 승인 건수(e,c) = |{ 구간 안 확정 승인 거래 t : t.금액 > Σ(같은 Payment의 asOf 이전 확정 취소 거래 금액) }|. **[확정 — BR-CAN-098]**
- 금액 표현: minor unit 정수, 부동소수 없음, 범위 제한 없음(SQL SUM DECIMAL → 정확 정수 변환). **[제안 — ADMIN-HOME-KPI-001 §0·T-K1-14]**
- 현재 반환 수단은 취소뿐이다. 환불(Refund) 거래 유형이 생기면 취소금액 정의를 다시 정한다(그 전에는 집계하지 않는다). **[제안 — 명세 §3.5]**

### 1.3 P-INC 포함/제외 조건(상태별)

| 대상 | 상태 | 승인금액 | 취소금액 | 승인 건수 | 확인 필요 | 근거 |
|---|---|---|---|---|---|---|
| Payment | REQUESTED(결제창 대기) | 제외 | — | 제외 | 사용자 결정 필요(Q-KPI-1 — 권장: 세지 않음) | D-GOV-010 ⑤ ③ |
| Payment | AUTHORIZING(기관 처리 중) | 제외 | — | 제외 | **진행 중**으로 셈 | D-GOV-010 ⑤ ④⑤ |
| Payment | UNKNOWN(결과 미확정) | 제외 | — | 제외 | **UNKNOWN**으로 셈 | D-GOV-010 ⑤ ④⑤, CLAUDE.md(UNKNOWN 단정 금지) |
| Payment | AUTHORIZED + 확정 승인 거래 | **포함** | — | 포함(전액취소 전) | — | D-ADMIN-HOME-001 ①③ |
| Payment | AUTHORIZED + 활성 incident hold | 현행 포함 | 현행 포함 | 현행 포함 | 사용자 결정 필요(Q-KPI-2) | 명세 T-K1-16(Coordinator 해석) |
| Payment | FAILED·EXPIRED | 제외 | — | 제외 | — | D-GOV-010 ⑤ ③ |
| Payment | FAILED + 늦은 승인(RECON 기록, hold) | 제외 | 그 보정 취소도 제외 | 제외 | "확인 필요"로 셈 | D-ADMIN-HOME-002 ①, BR-CAN-099 |
| 취소 | OPEN(기관 처리 중·결과 반영 전) | — | 제외 | 전액판정에 넣지 않음 | **진행 중**으로 셈 | D-GOV-010 ⑤ ④⑤ |
| 취소 | UNKNOWN | — | 제외 | 전액판정에 넣지 않음 | **UNKNOWN**으로 셈 | D-GOV-010 ⑤ ④⑤, D-CANCEL-003 |
| 취소 | COMPLETED(확정 취소 거래 1건) | — | **포함**(확정일 기준) | 전액판정에 포함 | — | D-ADMIN-HOME-001 ②, INV-C2 |
| 취소 | FAILED(기관 거절) | — | 제외 | 영향 없음 | — | D-GOV-010 ⑤ ③ |
| 취소 | 늦은 취소 성공 FORCED(취소 거래 없음, hold) | — | 제외 | 전액판정에서 제외 | "확인 필요"로 셈 | D-ADMIN-HOME-002 ②, BR-CAN-100 |

### 1.4 P-ENV·P-CUR 환경·통화 분리

- KPI의 모든 값(금액·건수·확인 필요 건수)은 **(환경, 통화) 행** 단위로 계산·표시한다. TEST와 LIVE의 값을 더한 합계, 서로 다른 통화의 값을 더한 합계를 어떤 화면·API에도 만들지 않는다. **[확정 후보 — D-GOV-010 ⑤ ①②, D-ADMIN-HOME-001 기본값(이번 지시로 사용자 확정), D-CPD-001, D-CP-002]**
- 환경은 Payment의 환경(TEST/LIVE), 통화는 확정 거래의 통화다. 화면은 환경 묶음마다 환경 표지를 항상 보이고 LIVE를 먼저 둔다. **[제안 — 명세 C-1]**
- 외화는 정산 통화 결정 전까지 기준정보에서 비활성이다(D-GOV-002 ④). KPI는 통화를 거르지 않고 확정 거래의 통화를 그대로 행으로 둔다. **[제안]**

### 1.5 P-TIME 집계 기준일·시간대

- 기준 시간대 = **Asia/Seoul(KST, UTC+9, 서머타임 없음)**. 기준일 = asOf의 KST 날짜. 구간 = **[기준일 00:00:00.000 KST, asOf)**(KST 자정 정각 조회는 빈 구간). **[확정 — D-ADMIN-HOME-001 ②, BR-CAN-097]**
- 거래가 어느 날에 속하는지는 **PG 확정 시각(confirmed_at)** 으로 정한다(요청·개시 시각이 아님). 어제 UNKNOWN이던 승인·취소가 오늘 확정되면 오늘 값이다. 정산 거래일(기관 거래일, BR-FIN-077·079)과 다르다. **[확정 — D-ADMIN-HOME-002 ③, D-GOV-002 ③, BR-CAN-101]**
- asOf는 PAYMENT가 조회 직전에 한 번 정한 시각(밀리초)이며 ADMIN·브라우저 시계를 쓰지 않는다. **[제안 — 명세 §3.1]**
- 화면은 "KST 오늘 0시 ~ 조회 시각(HH:MM), PG 확정 시각 기준"을 **명시**한다. **[확정 후보 — D-GOV-010 ⑤ ⑨]**
- 허용 한계: 확정 시각은 커밋 직전에 정해지므로 자정 직전 확정·자정 뒤 커밋된 사실은 어제 값에 다시 들어가지 않고, 조회 스냅샷 뒤 커밋된 사실은 다음 조회에 반영된다(일시 과소·과다). 비권위 표시값이라 허용한다. **[제안 — 명세 L-2]**

### 1.6 P-DUP 중복 집계 방지 기준

- 원칙: 금액은 **확정 거래 행 하나당 한 번**, 그 행의 확정일에만 센다. 같은 업무 사실이 두 번 금액에 들어가지 않는다. **[확정 후보 — D-GOV-010 ⑤ ⑧]**
- 승인: Payment당 Authorization 하나, Authorization당 확정 승인 거래 하나다. 같은 승인 결과의 재수신·재조회·Recovery·대사 재확인은 새 거래를 만들지 않는다. 같은 주문의 실패 뒤 새 Payment는 앞 Payment가 FAILED/EXPIRED라 금액에 들어가지 않는다(주문당 활성 Payment 하나). **[제안 — DB `uk_payment_authorization_payment`·`uk_payment_transaction_authorization`(V9), CLAUDE.md 주문 규칙]**
- 취소: 취소 operation 하나에 확정 취소 거래 하나다(INV-C2). 같은 취소 요청의 재시도(같은 merchantCancelId·같은 승인 ID)는 과거 결과를 돌려주고 새 취소를 만들지 않으며, 취소 결과 재반영·복구는 거래를 다시 만들지 않는다. **[제안 — `uk_payment_transaction_cancellation`(V17), `uk_payment_cancellation_merchant_key`·`_approval`(V15), D-CANCEL-002·003]**
- 전액취소: 확정 취소 거래 1건으로 승인액만큼 차감하고, 승인 건수에서 뺀다. **[확정 — BR-CAN-098]**
- 부분취소·반복 부분취소: 각 확정 취소 거래를 그 확정일에 한 번씩 차감한다. 누적 확정 취소액은 승인액을 넘을 수 없다(반환 잔액 — DB `ck_return_balance_within_authorized`). 누적액이 승인액과 같아지면 그 결제는 전액취소로 보고 건수에서 뺀다(FORCED는 누적액에 넣지 않음 — INV-C8). **[확정 후보 — D-GOV-010 ⑤ ⑧, BR-CAN-098, INV-C8]**
- 늦은 성공 FORCED: 취소 거래가 없으므로 금액·전액판정에 넣지 않고 확인 필요로만 센다 — 나중에 보정 결과가 확정 취소 거래로 생기면 그때 그 확정일에 한 번 센다. **[확정 — D-ADMIN-HOME-002 ②]**
- 진행 중 → 확정: 진행 중·UNKNOWN일 때는 확인 필요로만 세고, 확정되면 확인 필요에서 빠지고 금액에 한 번 들어간다(같은 시각에 두 곳에 동시에 세지 않음). **[제안]**

### 1.7 P-SETL 순결제금액 ≠ 정산금액

- 순결제금액은 PG 확정 시각 기준 조회 전용·비권위 표시값이며 정산금액·지급(예정)액·매입금액이 아니다. 수수료·VAT·보류·상계·정산 거래일을 반영하지 않는다. 정산·대사·지급·한도 판정에 쓰지 않는다. **[확정 — D-ADMIN-HOME-001, D-ADMIN-HOME-002 ③, BR-CAN-104, D-GOV-010 ⑤ ⑦]**
- 화면에는 "PG 확정 시각 기준 · 정산 금액 아님" 표지를 KPI 금액 옆에 항상 단다. 정산 화면의 용어(정산금액·지급액)를 KPI에 쓰지 않는다. **[확정 — BR-ADM-136·BR-CAN-101 / 용어 금지는 확정 후보 — D-GOV-010 ⑤ ⑦]**

### 1.8 P-ATT 확인 필요 건수

- UNKNOWN 건수와 진행 중 건수는 금액과 분리해 **각각** 보인다(합친 한 숫자로 보이지 않는다). 환경·통화 분리 원칙(§1.4)을 따른다. **[확정 후보 — D-GOV-010 ⑤ ⑤]**
- FORCED·종료 Payment 늦은 승인(과 그 보정 취소)도 확인 필요로 센다. **[확정 — D-ADMIN-HOME-002 ①②]**
- 대상 상태와 기간(오늘 개시분인지, 조회 시각 현재 미해결 전체인지)은 **사용자 결정 필요(Q-KPI-1)**.

### 1.9 사용자 결정 필요(이 문서가 확정하지 않음)

| ID | 미결 | 권장안 | 대안 | 영향 |
|---|---|---|---|---|
| Q-KPI-1 | UNKNOWN·진행 중 건수의 대상·기간 | UNKNOWN = 승인 UNKNOWN Payment + 취소 UNKNOWN, 진행 중 = 승인 AUTHORIZING + 취소 OPEN, REQUESTED 제외, 기간 = **조회 시각 현재 미해결 전체**(날짜 무관), 환경·통화별 | (b) 오늘(KST 0시 이후) 개시분만 (c) REQUESTED도 진행 중에 포함 | 표시만(금융 효과 없음). ADMIN-HOME-ATTENTION-001 명세·구현의 입력 |
| Q-KPI-2 | 활성 incident hold(예: 기관 결과 충돌)가 걸린 AUTHORIZED Payment의 금액 포함 | **현행 포함**(승인 거래 자체는 확정) + hold 건수를 확인 필요로 별도 셈 | (b) 금액·건수에서 빼고 확인 필요로만 셈 | 순결제금액 숫자. 현행 시험 T-K1-16 기대값이 (b)면 바뀜 |
| Q-KPI-3 | 명칭과 화면 표시 | 화면 이름을 **"오늘 순결제금액"**으로 통일하고 같은 카드에 승인금액·취소금액을 함께 표시, 구간 "KST 0시~HH:MM" 명시 | (b) "순승인액" 유지, 문서에서 동의어로만 정의 (c) 이름만 변경 | 화면 문구·배치(API 변경 없음 — 세 값이 이미 응답에 있음) |

## 2. 정책 → 대장 반영안 (반영은 하지 않음)

### 2.1 기존 행 문장 보완안

| 대장 행 | 현재 | 보완안 | 상태 안 | 근거 |
|---|---|---|---|---|
| CAN BR-CAN-102 | KPI는 통화별·환경별(TEST/LIVE 합산 금지)로 나누고, 결과 미확정(UNKNOWN)·진행 중 승인·취소는 금액에서 빼고 "확인 필요"로 센다. (제안, 출처 적용) | 운영 홈 KPI의 금액·건수는 (환경, 통화) 행별로 계산·표시하고 TEST/LIVE 간·통화 간 합계를 만들지 않는다. 금액에는 PG가 성공을 확정한 승인·취소 거래만 넣고, 결과 미확정(UNKNOWN)과 진행 중 승인·취소는 금액에서 빼며 **UNKNOWN 건수와 진행 중 건수를 각각 따로** 센다. | **확정 후보**(출처 사용자) | D-GOV-010 ⑤, D-ADMIN-HOME-001 |
| ADM BR-ADM-137 | KPI는 통화별로 나누어 보이고, UNKNOWN·진행 중은 금액에서 빼고 '확인 필요'로 세며, TEST와 LIVE를 섞지 않는다. (제안) | 위 BR-CAN-102와 같은 문장 + `[경계 CAN]`. 화면은 환경 묶음마다 환경 표지를 보이고 UNKNOWN·진행 중 건수를 금액 카드와 분리해 각각 보인다. | **확정 후보** | D-GOV-010 ⑤ |
| CAN BR-CAN-097 | 오늘 순승인액 = … | "승인금액·취소금액·순결제금액(= 순승인액)" 용어를 넣고 각 합의 조건(확정 승인 거래·확정 취소 거래, Payment AUTHORIZED)을 명시. 명칭은 Q-KPI-3 답 후 | 확정 유지(문언 보완은 확정 후보) | D-ADMIN-HOME-001 ①②, D-GOV-010 ⑤ ⑥ |
| CAN BR-CAN-104 | 조회 전용·비권위, 정산·대사 판정에 쓰지 않음 | + "순결제금액은 정산금액·지급액·매입금액이 아니며 수수료·VAT·보류·정산 거래일을 반영하지 않고, KPI에 정산 용어를 쓰지 않는다." | 확정 유지 + 추가 문장 확정 후보 | D-GOV-010 ⑤ ⑦ |
| 두 행 공통 | 출처 "D-ADMIN-HOME-001 코디네이터 구현 기본값(사용자 확인 대상 아님)" | 출처를 D-GOV-010 ⑤(사용자)로 바꾸고 D-ADMIN-HOME-001의 "사용자 확인 대상 아님" 문구는 정정 대상으로 역참조 | — | RULE-CONFIRM-001 Q3 권장안 |

### 2.2 새 행 초안 (ID 미정)

| 초안 | 대장 | 규칙 | 상태 안 | 근거 |
|---|---|---|---|---|
| N-1 | CAN §12 | 승인금액 = 구간 확정 승인 거래 합, 취소금액 = 구간 확정 취소 거래 합(전액·부분, 이전 날짜 승인 포함), 순결제금액 = 승인금액 − 취소금액(음수 가능) — 모두 환경·통화별 minor unit 정수 | 확정 후보 | D-GOV-010 ⑤ ⑥, D-ADMIN-HOME-001 ①② |
| N-2 | CAN §12 | 금액은 확정 거래 행 하나당 그 확정일에 한 번만 센다. 결과 재수신·재조회·Recovery·대사 재확인·같은 요청키 재시도는 새 거래를 만들지 않아 다시 세지 않는다. 반복 부분취소는 각 거래를 한 번씩 차감하고 누적 취소액은 승인액을 넘지 않으며, 누적액 = 승인액이면 전액취소로 건수에서 뺀다(FORCED 미포함) | 원칙 확정 후보 / 행 키 근거(UNIQUE) 문장은 제안 | D-GOV-010 ⑤ ⑧, INV-C2·C8, D-CANCEL-002·003 |
| N-3 | CAN §12 | 집계 기준 = KST(Asia/Seoul) asOf 날짜 00:00:00.000 ~ asOf(미포함), 소속일 = PG 확정 시각. 화면에 구간과 시간대를 표기한다 | 확정 후보(표기 의무), 구간·시각은 이미 확정(BR-CAN-097·101) | D-GOV-010 ⑤ ⑨ |
| N-4 | ADM §(KPI) `[경계 CAN]` | UNKNOWN 건수·진행 중 건수의 대상 상태와 기간 — Q-KPI-1 답 문장 | 제안(답 전 운영 동작 구현 금지) | Q-KPI-1 |
| N-5 | CAN §12 | 활성 incident hold AUTHORIZED Payment의 금액 포함 여부 — Q-KPI-2 답 문장(현행: 포함 + hold 확인 필요) | 제안 | 명세 T-K1-16, Q-KPI-2 |
| N-6 | CAN §12 | 자정 커밋 지연·스냅샷 뒤 커밋으로 인한 일시 과소·과다는 비권위 표시값이라 허용하고 다음 조회에서 수렴한다 | 제안 | 명세 L-2 |
| N-7 | CAN §12 | 새 반환 거래 유형(Refund 등)이 생기면 KPI 취소금액 정의를 다시 정하기 전까지 그 유형을 집계하지 않는다 | 제안 | 명세 §3.5, T-K1-12 |

## 3. 구현 정합 검증표

판정: **일치** = 코드가 정책대로이고 그 성질을 직접 확인하는 시험 코드가 있음. **불일치** = 코드·화면이 정책과 다르거나 정책 요구가 구현되지 않음. **시험 없음** = 코드는 정책대로이나 직접 시험이 없거나 부분적. **확인 불가** = 정책이 미결이라 판정할 수 없음.
경로 약칭: `R` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/query/JooqHomeKpiReader.kt`, `RS` = `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/adapter/query/HomeKpiReaderSpec.kt`, `H` = `apps/internal-admin/frontend/src/screens/HomeScreen.tsx`, `HT` = 같은 폴더 `HomeScreen.test.tsx`, `PAY` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/`, `ADM` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/paymentops/`.

| # | 정책 항목 | 코드 위치 | 시험 | 판정 |
|---|---|---|---|---|
| V-01 | 환경 분리(P-ENV) | `R:43` S1 `GROUP BY p.environment, t.currency, t.type`, `R:58` S2 `GROUP BY p.environment, t.currency`, 행 키 `R:64`; 정렬 `PAY application/HomeKpiQueryService.kt:28`; 중복 행 거부 `:22`; ADMIN `ADM adapter/integration/HomeKpiAnswer.kt:26-27` | `RS::the execution plans of the two statements (P-1)`(실행 문장 GROUP BY 키 단언, `RS:495-496`), `HomeKpiQueryServiceSpec::a reader returning rows in arbitrary order` — **LIVE 사실을 실제로 만들어 분리 결과를 보는 DB 시험(T-K1-11) 없음**(명세 L-7, 시험 Route TEST뿐) | 시험 없음(부분) |
| V-02 | 통화 분리(P-CUR) | 같은 GROUP BY(`t.currency`) | 같음 — **다통화 DB 시험(T-K1-9) 없음**(L-7, KRW뿐) | 시험 없음(부분) |
| V-03 | 환경·통화 간 합계를 만들지 않음 | `H:324-335`(금액), `H:354-365`(건수) — 행별 표시, 합계 계산 없음; `H:134-138` 환경 묶음 | `HT::T-K4-1 multi-row … never summed across environments or currencies` | 일치 |
| V-04 | 확정 승인만 금액(P-INC) | `R:42` `t.type IN (AUTHORIZATION, CANCEL)` ∧ `p.status = 'AUTHORIZED'` | `RS::T-K1-6 unconfirmed facts today: REQUESTED, AUTHORIZING, UNKNOWN and FAILED payments …` | 일치 |
| V-05 | 확정 취소만 금액(P-INC) | `R:42` CANCEL 거래 행만 합산(취소 거래는 COMPLETED에만 존재 — INV-C2) | `RS::T-K1-6 … FAILED and UNKNOWN cancels`, `RS::T-K1-6 a full cancel left OPEN …` | 일치 |
| V-06 | UNKNOWN 금액 제외 | 승인: `p.status='AUTHORIZED'`(`R:42`), 취소: 거래 없음 | `RS::T-K1-6`(두 사례) | 일치 |
| V-07 | 진행 중 금액 제외(REQUESTED·AUTHORIZING·취소 OPEN) | 같음 | `RS::T-K1-6`(두 사례) | 일치 |
| V-08 | **UNKNOWN 건수 분리 표시**(P-ATT) | 응답 모양에 필드 없음(`PAY application/provided/HomeKpiQuery.kt`, `ADM adapter/integration/PaymentResponseShape.kt:113-125`); 화면 "확인이 필요한 업무" 카드 `H:377-400`의 출처는 승인 대기·대사 발견 등 기존 목록뿐(`H:28-45`) | 없음 — 후속 `ADMIN-HOME-ATTENTION-001` BACKLOG(`harness/tasks.json:490`, 명세 미작성) | **불일치** |
| V-09 | **진행 중 건수 분리 표시**(P-ATT) | 같음 | 없음 | **불일치** |
| V-10 | 승인금액 산출 | `R:37-44,67` Σ AUTHORIZATION | `RS::T-K1-3 a partial cancel today of today's approval`(approved 10,000) | 일치 |
| V-11 | 취소금액 산출(이전 날짜 승인의 오늘 취소·운영자 보정 취소 포함) | `R:42,68` Σ CANCEL(원승인 날짜 조건 없음) | `RS::T-K1-5 yesterday's approval cancelled today`, `RS::T-K1-15 an operator correction cancel on an AUTHORIZED payment` | 일치 |
| V-12 | 순결제금액 = 승인 − 취소, 음수 허용 | `PAY domain/HomeKpiFigures.kt:18`; ADMIN 재검사 `HomeKpiAnswer.kt:33`; 화면 검사 `H:116` | `HomeKpiWindowSpec::figures … net = approved − cancelled and may be negative`, `RS::T-K1-5`, `HT::T-K4-2 negative net` | 일치 |
| V-13 | 승인·취소금액의 화면 표시와 "순결제금액" 명칭 | API는 세 값 제공(`PaymentResponseShape.kt:120-121`), 화면은 순액만·이름 "오늘 순승인액"(`H:316-330`) | — | 확인 불가(Q-KPI-3) |
| V-14 | 승인 건수(전액취소 제외, 부분취소 포함) | `R:46-57` 상관 subquery `t.amount > COALESCE(Σ c2 CANCEL < asOf, 0)` | `RS::T-K1-3`(count 1), `RS::T-K1-4 full cancels today …`, `RS::T-K1-13 a full cancel confirmed after asOf` | 일치 |
| V-15 | "정산 금액 아님" 표지(P-SETL) | `H:140` `KPI_LABEL`, `H:344` | `HT::T-K4-1 …`(`HT:136`), `HT::T-K4-4 empty list …`(`HT:173`) | 일치 |
| V-16 | KPI를 정산·대사에 쓰지 않음 | PAYMENT ArchUnit: `HomeKpiQuery` 호출자 = 조회 서비스·관리 컨트롤러만, `HomeKpiReader` 사용처 = 조회 서비스만 | `ArchitectureSpec`(`apps/payment-api/src/test/kotlin/com/paycore/payment/architecture/ArchitectureSpec.kt:760-765`, 위반 fixture `:356-357`) — ADMIN 쪽 사용처 제한 규칙은 없음 | 일치(ADMIN 쪽 미검사 — G-8) |
| V-17 | 전액취소 1회 | CANCEL 거래 1행 합산 + 건수 판정 | `RS::T-K1-4`(FULL) | 일치 |
| V-18 | 반복 부분취소 합산 | 각 CANCEL 행 합산(`R:42`), 누적 비교(`R:46-50`) | `RS::T-K1-4`(PARTIAL 3,000 + PARTIAL 7,000 → count 0), `RS::T-K1-3` | 일치 |
| V-19 | 누적 취소 ≤ 승인(과다 차감 없음) | 쓰기 측 `ck_return_balance_within_authorized`(`apps/payment-api/src/main/resources/db/migration/payment/V15__cancellation.sql:165`) | `CancelStartSpec::CX-04 after 3,000 completed 7,001 is rejected and the balance is unchanged`, `::CX-05 … ALREADY_FULLY_CANCELLED` | 일치 |
| V-20 | 재처리·재시도 중복 집계 방지 | 쓰기 측 UNIQUE: `uk_payment_authorization_payment`·`uk_payment_transaction_authorization`(V9:33·161), `uk_payment_transaction_cancellation`(V17:17), `uk_payment_cancellation_merchant_key`·`_approval`(V15:40-41) — KPI는 거래 행만 셈 | 쓰기 측 간접: `CancelResultConcurrencySpec::… still one CANCEL transaction`(`:110`), `CancelFlowSpec`(`:82`). **KPI 수준에서 "같은 결과 재반영·Recovery 뒤에도 합계 불변"을 보는 시험 없음** | 시험 없음(KPI 수준) |
| V-21 | 같은 주문 재시도 Payment 중복 없음 | `p.status='AUTHORIZED'` — FAILED/EXPIRED 앞 시도 제외, 주문당 활성 하나 | `RS::T-K1-6`(FAILED Payment 제외) | 일치 |
| V-22 | FORCED 차감·전액판정 제외 | 거래 없음 → 자동 제외(`R:42,49`) | `RS::T-K1-7 a FORCED late success of a declined cancel` | 일치 |
| V-23 | **FORCED "확인 필요"로 셈** | 없음 | 없음(대장 BR-CAN-100에 "'확인 필요' 셈은 없음" 기록) | **불일치** |
| V-24 | 종료 Payment 늦은 승인·그 보정 취소 제외 | `p.status='AUTHORIZED'`(`R:42,56`) | `RS::T-K1-8 a late approval recorded on a terminated (FAILED) payment …` | 일치 |
| V-25 | **늦은 승인 "확인 필요"로 셈** | 없음 | 없음(BR-CAN-099 기록) | **불일치** |
| V-26 | 활성 hold AUTHORIZED Payment 포함 | 상태 조건만 — hold 무관 포함 | `RS::T-K1-16 an AUTHORIZED payment with an active CONFLICTING_INSTITUTION_RESULT hold` | 확인 불가(Q-KPI-2) |
| V-27 | 소속일 = PG 확정 시각 | `R:42,56` `t.confirmed_at` | `RS::T-K1-1 …`, `RS::T-K1-17 an approval UNKNOWN since yesterday and a cancel UNKNOWN since yesterday, both confirmed today` | 일치 |
| V-28 | KST 0시 경계·[from, asOf) | `PAY domain/HomeKpiWindow.kt:26-32`(Asia/Seoul, `atStartOfDay`), `R:35-36`(UTC 리터럴), `R:42`(`ge from`, `lt asOf`), 빈 구간 `HomeKpiQueryService.kt:21` | `RS::T-K1-1`(23:59:59.999 제외·00:00:00.000 포함·asOf 제외), `RS::T-K1-2`, `HomeKpiWindowSpec::asOf instants around the Asia/Seoul midnight`, `HomeKpiQueryServiceSpec::asOf exactly at the KST midnight` | 일치 |
| V-29 | asOf = PAYMENT 시계·밀리초 | `HomeKpiQueryService.kt:20`, `HomeKpiWindow.kt:29` | `HomeKpiWindowSpec::… truncated to milliseconds` | 일치 |
| V-30 | 응답에 기준일·시간대 명시·검사 | 응답 `asOf·businessDate·timeZone·from`(`HomeKpiQueryService.kt:23`); ADMIN `PaymentResponseShape.kt:116`(리터럴 Asia/Seoul), `HomeKpiAnswer.kt:23`; 화면 `H:98-103` | `HomeKpiGatewaySpec::T-K3-5 shape and this-request violations …`, `AdminHomeKpiApiSpec::T-K2-3 …exact body bytes` | 일치 |
| V-31 | 화면에 구간·시간대 명시(P-TIME) | `H:344` hint는 "`HH:MM` 기준"만 — "KST"·"0시부터" 문구 없음(`formatKst`는 KST로 변환하지만 표기 안 함) | `HT::T-K4-4`(`'10:30 기준'`만 단언) | **불일치**(낮음) |
| V-32 | 자정 커밋 지연 틈 허용(L-2) | 설계상 한계(확정 시각이 커밋 전 결정) | 없음(재현 시험 없음 — 명세가 허용 한계로 기록) | 시험 없음 |
| V-33 | minor unit 정확 정수·float 없음 | `R:65`, `R:91-93` `toBigIntegerExact`, 화면 `H:127` 문자열 그대로 | `RS::T-K1-14(a) the sum statement`, `HomeKpiDecimalSpec`, `HT::T-K4-3 amounts beyond 2^53` | 일치 |
| V-34 | 새 반환 유형 생기면 재정의 | 스키마 guard | `RS::T-K1-12 the payment_transaction type CHECK`, T-K1-18(`RS:330-363`) | 일치 |
| V-35 | 한 스냅샷으로 금액·건수 일관 | `R:31` `@Transactional(readOnly, REPEATABLE_READ)` 2문장 | `RS::T-K1-10 a cancel committed by another connection between the sum statement and the count statement` | 일치 |

**집계: 일치 24 · 불일치 5(V-08·09·23·25·31) · 시험 없음 4(V-01·02·20·32) · 확인 불가 2(V-13·26).**

쿼리 직접 확인 결과(요청 항목):
- UNKNOWN·진행 중 구분: 금액 쿼리는 둘을 **구분하지 않고 함께 제외**한다(`p.status='AUTHORIZED'`와 CANCEL 거래 존재로). 건수를 세는 쿼리·필드는 없다.
- 환경·통화 GROUP BY: S1 `(p.environment, t.currency, t.type)`, S2 `(p.environment, t.currency)` — 환경은 Payment 열, 통화는 거래 열. 정합.
- 시간대 경계: `LocalDate.ofInstant(asOf, Asia/Seoul).atStartOfDay(Asia/Seoul)` → UTC 전날 15:00:00.000Z, SQL은 UTC 리터럴 `confirmed_at >= from AND < asOf`. 정합.
- 부분취소 합산: CANCEL 거래 행별 `SUM`(S1)과 Payment별 asOf 이전 CANCEL 누적 `COALESCE(SUM, 0)` 상관 subquery(S2, 하한 없음 — 이전 날짜 취소도 누적). `return_balance` 현재 값이 아니라 거래 사실로 계산. 정합.

## 4. 불일치·결손 목록 (수정하지 않음)

| ID | 심각도 | 내용 | 관련 | 다음 동작(제안) |
|---|---|---|---|---|
| G-1 | **높음** | UNKNOWN 건수·진행 중 건수를 금액과 분리해 **각각** 보이라는 사용자 지시(D-GOV-010 ⑤ ⑤)가 구현돼 있지 않다. API 필드·화면 표시 모두 없다. 후속 작업 ADMIN-HOME-ATTENTION-001은 BACKLOG이고 명세가 없다. 대장 BR-CAN-102·BR-ADM-137 문장은 "확인 필요로 센다"이지만 실제로 세는 곳이 없어 검증 열이 금액 제외만 증명한다. | V-08·09 | Q-KPI-1 답 → ADMIN-HOME-ATTENTION-001 명세 작성(PAYMENT 집계 + ADMIN 모양 + 화면) |
| G-2 | 중간 | 사용자 확정 결정 D-ADMIN-HOME-002 ①②의 "확인 필요로 셈"(FORCED, 종료 Payment 늦은 승인)이 미구현. 금액 제외 부분만 구현됨(대장에 이미 "셈은 없음"으로 기록) | V-23·25 | G-1과 같은 작업에 포함 |
| G-3 | 중간 | 환경·통화 분리가 DB 수준에서 실제 사실로 시험되지 않는다(시험 Route TEST·KRW뿐 — 명세 L-7). 문장 모양(GROUP BY 키) 단언과 서비스·화면 단위 시험만 있다. 사용자 지시 ①②의 핵심 보증이 문장 모양 단언에 기댄다 | V-01·02 | 시험 전용 LIVE/USD Simulator Route 추가 여부 결정 후 T-K1-9·11 DB 시험(직접 SQL fixture 금지 — Test Fixture Policy) |
| G-4 | 낮음 | 재처리 중복 방지가 쓰기 측 UNIQUE와 쓰기 측 시험에만 기댄다. KPI 수준에서 "같은 취소 결과 재반영·Recovery·같은 요청키 재시도 뒤에도 합계·건수 불변" 시험이 없다 | V-20 | `HomeKpiReaderSpec`에 공식 경로 재시도 사례 추가 |
| G-5 | 낮음 | 화면이 집계 구간·시간대를 명시하지 않는다("HH:MM 기준"만, "KST 0시~" 표기 없음) — 사용자 지시 ⑨ | V-31 | Q-KPI-3 답과 함께 문구 수정 |
| G-6 | 낮음 | 사용자 지시는 "순결제금액", 결정·명세·화면은 "순승인액" — 같은 값의 두 이름. 화면에 승인금액·취소금액이 보이지 않아 산출 기준이 화면만으로는 드러나지 않는다 | V-13 | Q-KPI-3 |
| G-7 | 낮음 | 활성 hold(기관 결과 충돌 포함) AUTHORIZED Payment가 금액에 포함된다(T-K1-16, Coordinator 해석). "확정 거래만" 지시와 형식상 충돌하지 않지만 사용자 확인이 없다 | V-26 | Q-KPI-2 |
| G-8 | 정보 | BR-CAN-104(정산·대사 미사용)의 ADMIN 쪽 사용처 제한 검사가 없다(PAYMENT ArchUnit만) | V-16 | 필요 시 ADMIN ArchUnit 규칙 |
| G-9 | 정보 | 자정 커밋 지연 틈(L-2)은 허용 한계로 기록돼 있으나 대장에 없다 | V-32 | §2.2 N-6 반영 |
| G-10 | 정보 | D-ADMIN-HOME-001의 "사용자 확인 대상 아님" 문구와 대장 두 행의 출처 "적용"은 D-GOV-010 ⑤로 사용자 확정된 상태와 맞지 않는다(문서 정정 대상 — 이 작업은 수정하지 않음) | §2.1 | Coordinator가 DECISIONS 역참조·대장 상태 갱신 |

## 5. 사용자 결정 필요 질문 (3개)

**Q-KPI-1 UNKNOWN·진행 중 건수의 대상과 기간**
- 권장안: UNKNOWN = 승인 결과 미확정 Payment(UNKNOWN) + 취소 UNKNOWN, 진행 중 = 승인 AUTHORIZING + 취소 OPEN, 결제창 대기(REQUESTED)는 세지 않음. 기간은 **조회 시각 현재 미해결 전체**(어제부터 UNKNOWN인 건도 보임), 환경·통화별로 각각 표시.
- 대안: (b) 오늘(KST 0시 이후) 개시된 건만 (c) REQUESTED도 진행 중에 포함.
- 영향: 표시만(금융 효과 없음). 오래된 UNKNOWN을 놓치지 않는 것이 권장 이유.
- 막히는 작업: ADMIN-HOME-ATTENTION-001 명세·구현(G-1·G-2).

**Q-KPI-2 활성 hold가 걸린 AUTHORIZED Payment의 금액 포함**
- 권장안: 현행 유지 — 승인 거래는 PG 확정 사실이므로 금액·건수에 포함하고, hold 건수를 "확인 필요"로 따로 센다.
- 대안: (b) hold가 풀릴 때까지 금액·건수에서 빼고 확인 필요로만 센다.
- 영향: 순결제금액 숫자. (b)면 시험 T-K1-16 기대값과 Reader 조건 변경.
- 막히는 작업: 대장 N-5 확정, (b)면 KPI 조회 수정 작업.

**Q-KPI-3 명칭과 화면 표시**
- 권장안: 화면 이름을 "오늘 순결제금액"으로 통일하고, 같은 카드에 승인금액·취소금액을 함께 보이며, "KST 0시 ~ HH:MM · PG 확정 시각 기준 · 정산 금액 아님"을 표기한다(API 변경 없음).
- 대안: (b) "순승인액" 유지, 정책 문서에서만 동의어로 정의 (c) 이름만 바꾸고 승인·취소 금액은 보이지 않음.
- 영향: 화면 문구·배치(G-5·G-6). 금융 효과 없음.
- 막히는 작업: 대장 BR-CAN-097 문언 보완, 화면 수정 작업.
