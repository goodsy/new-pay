# KPI-POLICY-001 운영 홈 KPI 집계 정책과 구현·시험 정합 검증 (rev 0.2b — 정책 정본 후보)

## 개정 이력

- **rev 0.1** (2026-11-26): D-GOV-010 ⑤ 사용자 지시 9항목으로 정책 초안을 쓰고 정적 정합 검증(35항목: 일치 24·불일치 5·시험 없음 4·확인 불가 2)을 했다. 질문은 Q-KPI-1~3.
- **rev 0.2** (2026-11-26): 사용자 답 D-GOV-013 ③(Q-KPI-2)과 D-GOV-014 ②(Q-KPI-1)·③(Q-KPI-3)을 반영해 **정책 정본 후보**로 개정했다.
  - 미결이던 대상·기간, hold, 명칭·병기를 확정 문장으로 바꿨다.
  - 새로 정한 항목: 당일 실적과 현재 미해결의 기간 구분, 승인·취소 건수 구분, 장기 미해결 식별, KPI 클릭 → 상세 목록, 성능·인덱스, 캐시·집계 테이블 금지.
  - 구현 정합을 다시 검증했다(§2). 건수 쿼리에 쓸 기존 인덱스는 Flyway migration 원문으로 확인했다.
  - 대장 변경안(§3), 구현 카드 제안(§4), 남은 결정 3개(§5)를 추가했다.
  - 자기 완료 판정이 아니다. Reviewer와 사용자가 확인할 대상이다.
- **rev 0.2a** (2026-11-26): 독립 리뷰 CHANGES_REQUIRED를 반영했다. 정책의 업무 의미는 바꾸지 않았다.
  - MAJOR-4: 대장 변경안에서 기존 행 문장을 고치지 않는다. 기존 행은 `대체됨→BR-…` 상태만 바꾸고, 새 문장은 새 ID(CAN 112~122, ADM 139~141)로 다시 매겼다. 한 행에 확정과 제안을 섞지 않았다(§3).
  - MAJOR-5: "payment.status 인덱스 없음"은 틀렸다. `ix_payment_requested_expiry (status, checkout_expires_at, payment_id)`(V7:13, status 선두)가 있다. §2.4, 결론, M-8, K-IDX-1, V-35를 정정했고, 근거로 인용했던 명세 ADMIN-HOME-KPI-001 §1.2의 "status 색인 없음"도 정정 대상으로 표시했다.
  - m-1: BR-CAN-112 초안에 섞여 있던 P-23 집계 단위(구현 결정)와 P-24 확장 해석, BR-ADM-139 초안의 P-39 권한·마스킹·감사(구현 결정)를 `제안/적용` 행으로 분리했다. P-24의 상태도 제안으로 바꿨다.
  - m-2: V-32 서술을 정정했다. 기간 필수는 일반 모드만이고, 31일은 D-TXOPS-001의 Demo 값이며, D-GOV-016 ②가 정확 조회의 기간 면제를 확정했다.
  - m-7: R-2(확인 필요 범주 겹침)는 표시만의 문제라 Coordinator 구현 결정으로 처리하고 질문에서 뺐다(P-30). 남은 질문은 R-1·R-3이다.
  - 기준 HEAD를 `45d18d2`로 올렸다. `30c550c..45d18d2` 사이 `apps/` 변경은 없다.
- **rev 0.2b** (2026-11-26): 재리뷰(ID 충돌 해소 조건부 APPROVED)의 MINOR를 반영했다. 정책 의미와 판정 수는 바뀌지 않았다.
  - 새 ID는 Coordinator 결정대로 BR-CAN-112~122·BR-ADM-139~141을 그대로 유지한다(CAN-OPS 문서가 123~로 이동).
  - K-m1: §5 R-1의 막히는 작업을 BR-CAN-114 → BR-CAN-119로 정정했다.
  - K-m2: §2.5 M-2의 거래 검색 서술을 "일반 모드만 기간 필수, 31일은 Demo 값"으로 정정했다.
  - K-m3: BR-ADM-139 확정 행에서 화면 배치 문장을 뺐다(P-33 구현 결정 — 대장 대상 아님).
  - K-m4: BR-ADM-140 상태 칸은 값만 두고, 괄호 설명은 근거 칸으로 옮겼다.
  - C-1을 "질문 후보"로 표시했다.

## 범위와 원칙

- 대상: 관리자 운영 홈 KPI 카드. 명세는 ADMIN-HOME-KPI-001 rev 0.3a이고, 후속 작업은 ADMIN-HOME-ATTENTION-001이다.
- 이 문서가 **하지 않는 일**:
  - 소스·시험·규칙 대장(`docs/rules/*.md`)·`docs/DECISIONS.md`·명세 본문을 고치지 않는다. 커밋하지 않는다.
  - 대장은 §3의 변경안으로만 두고, 구현은 §4의 카드 제안으로만 둔다.
- 근거 우선순위:
  1. 사용자 결정: D-GOV-014 ②③, D-GOV-013 ③, D-GOV-010 ⑤, D-ADMIN-HOME-001·002, D-GOV-002 ③④
  2. 확정 대장 행: BR-CAN-097~104·109~111, BR-ADM-135~138
  3. 불변식과 기존 결정: INV-C2·C8, D-CANCEL-002·003·006·011, D-CPD-001, D-CP-002
  4. CLAUDE.md
- 검증 기준 소스: `main` HEAD `45d18d2`(rev 0.2까지는 `30c550c` — 그 사이 `apps/` 변경 없음). `apps/`에는 미커밋 변경이 없다.
  - 파일 hash(git blob 앞 12자): `JooqHomeKpiReader.kt` dfccfd53f7dc, `HomeKpiWindow.kt` 7b89532a05e4, `HomeScreen.tsx` 68aee6ee465e, `ADMIN-HOME-KPI-001-HOME-KPI.md` 3256f7b9f983
- **검증 방식**: 코드·SQL·migration·시험 원문을 직접 읽어 대조한 정적 검증이다. **시험은 실행하지 않았다.** "시험 있음"은 시험 코드가 저장소에 있다는 뜻일 뿐이며, 통과 여부는 Verifier가 확인한다.

## 0. 한눈에

| 묶음 | 기간 | 값(각 (환경, 통화) 행) | 현재 구현 |
|---|---|---|---|
| **A. 당일 실적** | KST 기준일 00:00:00.000 ~ 조회 시각(미포함), PG 확정 시각 기준 | 순결제금액, 승인금액, 취소금액, 승인 건수 | API는 네 값을 모두 준다. 화면은 순액과 건수만 보이고, 이름은 "순승인액"이다 |
| **B. 현재 미해결** | 거래 발생일과 무관하게 조회 시각 현재 미해결인 전체 | 승인 UNKNOWN, 승인 진행 중, 취소 UNKNOWN, 취소 진행 중 건수(각각) + 장기 미해결 표시 | **없음** |
| **C. 확인 필요 예외** | 조회 시각 현재 | 활성 hold 건수, 늦은 취소 성공 FORCED 건수, 종료 Payment 늦은 승인 건수 | **없음** |
| 공통 | — | TEST/LIVE·통화 비합산, "PG 확정 시각 기준 · 정산 금액 아님", KPI 클릭 → 같은 기준의 상세 목록 | 비합산과 표지는 있다. **클릭 상세 목록은 없다** |

정책 문장은 §1에 **42개**다. 상태별로 확정 32개, 구현 결정 7개, 제안 1개, 결정 필요 2개다.

재검증표는 §2에 **38항목**이다. 판정별로 **일치 21, 불일치 12, 시험 없음 4, 확인 불가 1**이다.

## 1. 정책 (정본 후보 문장)

상태 표기는 넷이다.
- **확정**: 사용자 결정(D-ID)이나 확정 대장 행을 그대로 옮긴 문장이다.
- **구현 결정**: 가역 구현 세부로, Coordinator나 저장소 관례로 정하고 기록한다. 업무 의미는 바꾸지 않는다.
- **제안**: 확정 결정을 확장해 해석한 문장이다. 사용자 확인 전이다.
- **결정 필요**: §5의 질문이다.

### 1.1 용어

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-01 | 화면과 정책 용어는 **순결제금액**으로 통일한다. D-ADMIN-HOME-001의 "순승인액"은 이 용어로 대체한다. | 확정 | D-GOV-014 ③ |
| P-02 | **승인금액**은 집계 구간에 PG가 승인 성공을 확정한 승인 거래 금액의 합이다. | 확정 | D-GOV-014 ③, BR-CAN-109 |
| P-03 | **취소금액**은 같은 구간에 PG가 취소 성공을 확정한 취소 거래 금액의 합이다. 전액취소, 부분취소, 운영자 보정 취소를 포함한다. **전일 이전 승인을 당일 취소한 금액도 당일 취소금액에 넣는다.** | 확정 | D-GOV-014 ③, D-ADMIN-HOME-001 ②, BR-CAN-097 |
| P-04 | **순결제금액 = 승인 성공 확정금액 − 취소 성공 확정금액**이다. 음수를 허용한다. | 확정 | D-GOV-014 ③, BR-CAN-097·109 |
| P-05 | 화면은 순결제금액과 함께 같은 행의 승인금액과 취소금액을 **병기**한다. | 확정 | D-GOV-014 ③ |
| P-06 | 순결제금액은 정산금액이 아니며 지급(예정)액이나 매입금액도 아니다. 수수료, VAT, 보류, 상계, 정산 거래일을 반영하지 않는다. 정산·대사·지급 판정에 쓰지 않는다. 화면에는 "PG 확정 시각 기준 · 정산 금액 아님" 표지를 단다. | 확정 | D-GOV-014 ③, BR-CAN-101·104, BR-ADM-136, D-GOV-002 ③ |
| P-07 | **승인 건수**는 구간에 확정된 승인 중 조회 시각까지 전액취소되지 않은 결제 수다. 부분취소 건은 포함한다. | 확정 | D-ADMIN-HOME-001 ③, BR-CAN-098 |
| P-08 | 금액은 minor unit 정수로 다룬다. 부동소수를 쓰지 않고 범위 제한도 두지 않는다. | 구현 결정 | 명세 §0·T-K1-14(기존 구현) |

### 1.2 기간

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-09 | **당일 실적**(A) 구간은 조회 시각 기준 KST(Asia/Seoul) 날짜의 00:00:00.000부터 조회 시각 직전까지다. 승인과 취소는 각각 **PG 결과 확정 시각**으로 그 날에 속하는지 정한다. | 확정 | D-GOV-014 ③, D-ADMIN-HOME-002 ③, BR-CAN-097·101·111 |
| P-10 | **현재 미해결**(B·C)은 거래 발생일, 개시일, 확정일과 무관하게 **조회 시각 현재 미해결인 전체**를 센다. | 확정 | D-GOV-014 ② |
| P-11 | 응답과 화면은 A와 B·C의 기간이 다르다는 것을 **구분해 표기**한다. A에는 "오늘 0시(KST) ~ HH:MM · PG 확정 시각 기준", B·C에는 "HH:MM 현재 · 전체 기간"을 단다. | 확정(구분 표기 의무) / 문구는 구현 결정 | D-GOV-014 ②, BR-CAN-111 |
| P-12 | 조회 시각(asOf)은 PAYMENT가 한 번 정한다. 한 응답의 A, B, C는 같은 asOf와 같은 읽기 스냅샷을 쓴다. | 구현 결정 | 명세 §3.1·T-K1-10 관례 확장 |
| P-13 | 자정 커밋 지연이나 스냅샷 뒤 커밋으로 생기는 일시 과소·과다는 비권위 표시값이라 허용한다. | 확정(BR-CAN-104 비권위) / 한계 기록 | 명세 L-2 |

### 1.3 포함·제외

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-14 | A의 금액과 건수에는 PG가 성공을 확정한 승인·취소 거래만 넣는다. UNKNOWN과 진행 중은 금액에서 뺀다. | 확정 | D-GOV-010 ⑤, BR-CAN-102 |
| P-15 | 활성 incident hold가 걸린 AUTHORIZED Payment도 A의 금액·건수에 **포함**하고, hold 건수는 C에서 따로 센다. 현행 T-K1-16을 유지한다. | 확정 | D-GOV-013 ③ |
| P-16 | 종료(FAILED/EXPIRED) Payment의 늦은 승인과 그 보정 취소는 A에서 둘 다 빼고 C에서 센다. | 확정 | D-ADMIN-HOME-002 ①, BR-CAN-099 |
| P-17 | 늦은 취소 성공 FORCED는 A의 차감과 전액취소 판정에서 빼고 C에서 센다. | 확정 | D-ADMIN-HOME-002 ②, BR-CAN-100 |
| P-18 | 환불 같은 새 반환 거래 유형이 생기면 취소금액 정의를 다시 정하기 전까지 집계하지 않는다. | 구현 결정(guard 시험 유지) | 명세 §3.5, T-K1-12 |

### 1.4 미해결 건수(B)

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-19 | **UNKNOWN**은 승인 또는 취소의 결과가 미확정인 건이다. 승인 UNKNOWN은 Payment 상태 UNKNOWN, 취소 UNKNOWN은 취소 operation 상태 UNKNOWN이다. | 확정 | D-GOV-014 ② |
| P-20 | **진행 중**은 승인 AUTHORIZING Payment와 취소 OPEN operation이다. **REQUESTED(결제창 대기)는 세지 않는다.** | 확정 | D-GOV-014 ② |
| P-21 | 승인 건수와 취소 건수를 **구분**한다. 행마다 승인 UNKNOWN, 승인 진행 중, 취소 UNKNOWN, 취소 진행 중 4개 값을 따로 보이며 서로 합치지 않는다. | 확정 | D-GOV-014 ② |
| P-22 | B는 TEST/LIVE와 통화별 행으로 나누고 비합산한다. 환경은 Payment·취소의 환경, 통화는 Payment·취소 금액의 통화다. | 확정(분리) / 열 선택은 구현 결정 | D-GOV-014 ②, BR-CAN-102 |
| P-23 | **중복 집계를 막는다.** 승인 쪽은 Payment 하나를 1건, 취소 쪽은 취소 operation 하나를 1건으로 센다. 같은 건의 재시도, 재조회, Recovery, 여러 Attempt는 건수를 늘리지 않는다. Payment당 진행 중 취소는 하나뿐이다(D-CANCEL-003). 승인 미해결 Payment에는 취소가 없으므로 승인 쪽과 취소 쪽은 서로 겹치지 않는다. | 확정(원칙) / 집계 단위는 구현 결정 | D-GOV-014 ②, D-CANCEL-003, BR-CAN-001·002 |
| P-24 | 미해결 건은 확정되는 순간 B에서 빠지고, 성공이면 그 확정일의 A에 한 번만 들어간다. 같은 시각에 A와 B에 동시에 세지 않는다. | 제안(BR-CAN-110을 미해결 → 확정 전이로 확장한 해석) | D-GOV-014 ②, BR-CAN-110 |
| P-25 | UNKNOWN 판정은 업무 상태(Payment·취소 status)로 한다. 기관 Attempt 상태(OUTCOME_UNKNOWN 등)는 장기 식별 근거로만 쓴다. | 구현 결정 | CLAUDE.md(UNKNOWN 단정 금지), Payment.kt:17-23 |

### 1.5 장기 미해결 식별

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-26 | B 가운데 오래 머문 미해결(장기 UNKNOWN, 장기 진행 중)을 **식별해 표시**한다. | 확정 | D-GOV-014 ② |
| P-27 | 장기 판단 시간은 매입사와 결제 처리 정책에 따라 **별도로 정의**한다. 정의 전에는 임의 숫자를 운영값으로 쓰지 않는다. | 확정(별도 정의) | D-GOV-014 ② |
| P-28 | 정의 전의 표시 방식(기존 Recovery 이관 신호를 쓸지, Demo 값을 쓸지) | 결정 필요(§5 R-1) | — |

### 1.6 확인 필요 예외(C)

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-29 | 활성 hold 건수, FORCED 건수, 종료 Payment 늦은 승인 건수를 각각 센다. 환경과 통화로 분리한다. | 확정 | D-GOV-013 ③, D-ADMIN-HOME-002 ①②, D-GOV-014 ② |
| P-30 | 한 Payment가 여러 범주에 걸칠 수 있다(FORCED·늦은 승인은 hold도 함께 연다 — D-CANCEL-006·011). 범주별 건수는 각각 보인다. 화면 "확인이 필요한 업무" 총계(`H:238`)에는 이 범주들을 더하지 않고, 지금처럼 처리 대기 업무 출처만 둔다. hold 범주는 전체 활성 hold를 보이되, 범주끼리 겹칠 수 있다고 표기한다. | 구현 결정(rev 0.2의 R-2 — 표시만, Coordinator 처리) | D-GOV-013 ③, D-ADMIN-HOME-002 ①② |
| P-31 | FORCED와 늦은 승인이 언제 C에서 빠지는지. 관련 hold가 해제될 때인지, 계속 남는지. | 결정 필요(§5 R-3) | — |

### 1.7 분리·비합산(공통)

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-32 | A, B, C의 모든 값은 (환경, 통화) 행별로 계산하고 보인다. TEST+LIVE 합계나 통화 간 합계를 어떤 화면·API에도 만들지 않는다. | 확정 | D-GOV-010 ⑤, D-GOV-014 ②, BR-CAN-102, BR-ADM-137 |
| P-33 | 화면은 환경 묶음마다 환경 표지를 항상 보이고 LIVE를 먼저 둔다. | 구현 결정(기존 C-1) | 명세 C-1 |
| P-34 | 외화는 정산 통화 결정 전까지 기준정보에서 비활성이다. KPI는 통화를 거르지 않는다. | 확정 | D-GOV-002 ④ |

### 1.8 중복 집계 방지(A)

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-35 | A의 금액은 확정 거래 하나당 그 확정일에 한 번만 센다. 재처리, 재수신, 재조회, Recovery, 대사 재확인, 같은 요청키 재시도로 다시 세지 않는다. | 확정 | BR-CAN-110 |
| P-36 | 반복 부분취소는 확정 취소 거래마다 한 번씩 차감한다. 누적 확정 취소액이 승인액과 같아지면 전액취소로 보고 승인 건수에서 뺀다. FORCED는 누적액에 넣지 않는다. | 확정 | BR-CAN-110, INV-C8 |

### 1.9 상세 목록(드릴다운)

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-37 | KPI 숫자를 클릭하면 그 숫자를 이루는 **거래 상세 목록**을 조회한다. | 확정 | D-GOV-014 ② |
| P-38 | 상세 목록은 KPI와 **같은 기준**(같은 상태 조건, 같은 기간 축, 같은 환경·통화)을 쓴다. 목록 건수와 KPI 숫자가 어긋나지 않게 한다. A 목록은 PG 확정 시각 구간의 승인·취소 거래이고, B·C 목록은 생성일 제한 없는 현재 미해결 건이다. | 확정(D-GOV-014 ②의 직접 귀결 — 다른 기준은 클릭 의미를 깨뜨림) | D-GOV-014 ② |
| P-39 | 상세 목록은 기존 거래 조회와 같은 권한(VIEW 4역할), 마스킹, 감사 규칙을 따른다. 목록 조회 감사는 없고, 상세 열람은 `PAYMENT_VIEWED`로 감사한다. | 구현 결정(기존 규칙 재사용) | BR-CAN-094, BR-ADM-138, D-TXOPS-002 |

### 1.10 성능·구조

| # | 정책 문장 | 상태 | 근거 |
|---|---|---|---|
| P-40 | 집계 쿼리는 **기존 인덱스부터** 검토하고, 실제 실행 계획(EXPLAIN) 시험으로 전체 표 스캔이 없음을 확인한다. 필요할 때만 최소 인덱스를 추가한다. 인덱스는 집계 테이블이나 캐시가 아니다. | 확정(검토 의무) / 인덱스 추가는 구현 결정 | D-GOV-014 ②, 명세 §9 P-1 관례 |
| P-41 | **집계 테이블(일 요약 projection 등)과 캐시를 선제 도입하지 않는다.** 원천은 append-only 확정 사실과 현재 상태 표뿐이다. | 확정 | D-GOV-014 ②, 명세 C-5 |
| P-42 | 기존 A 집계 로직은 유지하고 불필요한 구조 변경을 하지 않는다. 기존 API 필드 이름(`netApprovedAmountMinor` 등)은 내부 계약이라 바꾸지 않고 화면 용어만 바꾼다. | 확정(구조 변경 금지) / 필드 이름 유지는 구현 결정 | D-GOV-014 ③ |

상태별 문장 수는 아래와 같다. 일부 확정 문장은 문구나 열 선택 같은 구현 결정을 함께 지닌다. 대장으로 옮길 때는 §3처럼 확정 부분과 제안·구현 부분을 다른 행으로 나눈다.
- 확정 32: P-01~07, 09~11, 13~17, 19~23, 26, 27, 29, 32, 34~38, 40~42
- 구현 결정 7: P-08, 12, 18, 25, 30, 33, 39
- 제안 1: P-24
- 결정 필요 2: P-28(R-1), P-31(R-3)

## 2. 구현 정합 재검증표

판정 기준은 넷이다.
- **일치**: 코드가 정책대로이고 직접 시험 코드가 있다.
- **불일치**: 정책과 다르거나 구현되지 않았다.
- **시험 없음**: 코드는 맞는데 직접 시험이 없거나 부분적이다.
- **확인 불가**: 정책이 미결이다.

경로 약칭은 다음과 같다.
- `R` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/query/JooqHomeKpiReader.kt`
- `RS` = `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/adapter/query/HomeKpiReaderSpec.kt`
- `H` = `apps/internal-admin/frontend/src/screens/HomeScreen.tsx`, `HT` = 같은 폴더의 `HomeScreen.test.tsx`
- `PAY` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/`
- `ADM` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/paymentops/`
- `MIG` = `apps/payment-api/src/main/resources/db/migration/payment/`

### 2.1 A. 당일 실적

| # | 정책 | 코드 위치 | 시험 | 판정 |
|---|---|---|---|---|
| V-01 | P-01 용어 "순결제금액" | 화면 이름이 "오늘 순승인액"이다(`H:316-317`). 힌트는 "순승인액(취소 차감)"이다(`H:344-345`). | `HT` 단언이 "오늘 순승인액"으로 고정돼 있다(`HT:162,170`). | **불일치** |
| V-02 | P-02 승인금액 | `R:37-44,67` Σ AUTHORIZATION | `RS::T-K1-3 a partial cancel today of today's approval` | 일치 |
| V-03 | P-03 취소금액(전일 승인 당일 취소, 보정 취소) | `R:42,68`, 원승인 날짜 조건 없음 | `RS::T-K1-5 yesterday's approval cancelled today`, `RS::T-K1-15 an operator correction cancel on an AUTHORIZED payment` | 일치 |
| V-04 | P-04 순결제 = 승인 − 취소, 음수 허용 | `PAY domain/HomeKpiFigures.kt:18`, `ADM adapter/integration/HomeKpiAnswer.kt:33`, `H:116` | `HomeKpiWindowSpec::… net = approved − cancelled and may be negative`, `HT::T-K4-2 negative net` | 일치 |
| V-05 | P-05 승인·취소금액 병기 | API는 세 값을 모두 준다(`ADM adapter/integration/PaymentResponseShape.kt:120-121`). 화면은 순액만 그린다(`H:328-331`). | 없음 | **불일치** |
| V-06 | P-06 정산 아님 표지 | `H:140,344` | `HT::T-K4-1 …`(`HT:136`), `HT::T-K4-4 …`(`HT:173`) | 일치 |
| V-07 | P-06 정산·대사에 쓰지 않음 | PAYMENT ArchUnit(`HomeKpiQuery`·`HomeKpiReader` 사용처 제한) | `apps/payment-api/src/test/kotlin/com/paycore/payment/architecture/ArchitectureSpec.kt:760-765`. ADMIN 쪽 규칙은 없다. | 일치(ADMIN 미검사) |
| V-08 | P-07 승인 건수 | `R:46-57` | `RS::T-K1-3`, `RS::T-K1-4 full cancels today …`, `RS::T-K1-13 a full cancel confirmed after asOf` | 일치 |
| V-09 | P-09 KST 0시 ~ asOf, 확정 시각 | `PAY domain/HomeKpiWindow.kt:26-32`, `R:35-36,42,56` | `RS::T-K1-1 …`, `RS::T-K1-2 …`, `RS::T-K1-17 … both confirmed today`, `HomeKpiWindowSpec::asOf instants around the Asia/Seoul midnight` | 일치 |
| V-10 | P-11 A/B 기간 구분 표기 | A만 있다. 힌트는 "`HH:MM` 기준"뿐이고 "KST 0시~" 표기가 없다(`H:344`). | `HT::T-K4-4`는 `'10:30 기준'`만 단언한다. | **불일치** |
| V-11 | P-14 확정 승인·취소만, UNKNOWN·진행 중 제외 | `R:42`(`p.status='AUTHORIZED'` + 거래 행) | `RS::T-K1-6 unconfirmed facts today …`, `RS::T-K1-6 a full cancel left OPEN …` | 일치 |
| V-12 | P-15 활성 hold 포함 | 상태 조건만 보고 hold와 무관하게 포함한다. | `RS::T-K1-16 an AUTHORIZED payment with an active CONFLICTING_INSTITUTION_RESULT hold` | 일치 |
| V-13 | P-16 늦은 승인·보정 취소 제외 | `R:42,56` | `RS::T-K1-8 a late approval recorded on a terminated (FAILED) payment …` | 일치 |
| V-14 | P-17 FORCED 제외 | 취소 거래가 없어서 자동으로 빠진다. | `RS::T-K1-7 a FORCED late success of a declined cancel` | 일치 |
| V-15 | P-32 환경·통화 분리(A) | `R:43,58` GROUP BY, `PAY application/HomeKpiQueryService.kt:22,28` | `RS::the execution plans … (P-1)`는 GROUP BY 키를 단언한다(`RS:495-496`). LIVE·USD 사실로 보는 DB 시험은 없다(명세 L-7). | 시험 없음(부분) |
| V-16 | P-32 화면 비합산 | `H:324-335,354-365` | `HT::T-K4-1 … never summed across environments or currencies` | 일치 |
| V-17 | P-35 재처리 중복 없음(A) | 쓰기 측 UNIQUE가 막는다: `MIG V9__authorization.sql:33,161`, `MIG V17__cancellation_existing_tables.sql:17`, `MIG V15__cancellation.sql:40-41` | 쓰기 측 시험만 있다: `CancelResultConcurrencySpec::… still one CANCEL transaction`. KPI 수준 시험은 없다. | 시험 없음 |
| V-18 | P-36 반복 부분취소, FORCED 비누적 | `R:42,46-50`, `MIG V15__cancellation.sql:165`(`ck_return_balance_within_authorized`) | `RS::T-K1-4`(PARTIAL+PARTIAL), `RS::T-K1-7`, `CancelStartSpec::CX-04 …` | 일치 |
| V-19 | P-08 정확 정수 | `R:65,91-93`, `H:127` | `RS::T-K1-14(a) …`, `HomeKpiDecimalSpec`, `HT::T-K4-3 amounts beyond 2^53` | 일치 |
| V-20 | P-18 새 반환 유형 guard | 스키마 CHECK | `RS::T-K1-12 the payment_transaction type CHECK` | 일치 |
| V-21 | P-12 한 스냅샷 | `R:31` RR 읽기 TX에서 2문장을 읽는다. A만 있어서 B·C를 포함한 스냅샷은 아직 해당이 없다. | `RS::T-K1-10 …` | 일치(A 한정) |
| V-22 | P-13 자정 커밋 지연 한계 | 설계상 한계다. | 없음 | 시험 없음 |

### 2.2 B. 현재 미해결, C. 확인 필요

| # | 정책 | 코드 위치 | 시험 | 판정 |
|---|---|---|---|---|
| V-23 | P-19·20·21 승인 UNKNOWN과 승인 진행 중 건수(각각) | KPI 응답에 필드가 없다(`PAY application/provided/HomeKpiQuery.kt`, `PaymentResponseShape.kt:113-125`). 참고로 Observability gauge가 Attempt `OUTCOME_UNKNOWN` 수를 센다(`PAY adapter/query/JooqAuthorizationBacklogReader.kt:26`). 하지만 환경·통화 분리가 없고 Payment 상태 기준도 아니며 화면에도 없다. | 없음 | **불일치** |
| V-24 | P-19·20·21 취소 UNKNOWN과 취소 진행 중 건수(각각) | KPI에 없다. gauge는 취소 UNKNOWN 수만 센다(`PAY adapter/query/JooqCancellationViewReader.kt:116`). OPEN은 세지 않고 환경 분리도 없다. | 없음 | **불일치** |
| V-25 | P-10 전체 기간 현재 미해결 | 없음 | 없음 | **불일치** |
| V-26 | P-22 B의 환경·통화 분리 | 없음 | 없음 | **불일치** |
| V-27 | P-23·24 B의 중복 방지(Payment·operation 단위) | 구조적 근거는 있다: `uk_payment_cancellation_in_flight`(`MIG V15__cancellation.sql:42`, 진행 중 취소는 Payment당 하나), `uk_authorization_attempt_in_flight`(`MIG V9__authorization.sql:72`). 집계 쿼리는 없다. | 없음 | **불일치**(미구현) |
| V-28 | P-26 장기 미해결 식별 | 없음. 참고 신호로 gauge의 최고 연령 `oldestUnknownPreparedAt`(`JooqAuthorizationBacklogReader.kt:26`)와 Recovery 이관 수(escalated, `:28-29`, 취소는 `JooqCancellationViewReader.kt:117`)가 있다. | 없음 | **불일치** |
| V-29 | P-27·28 장기 판단 시간 | — | — | 확인 불가(R-1) |
| V-30 | P-29 hold, FORCED, 늦은 승인 건수 | KPI에 없다. gauge `activeIncidentHolds`(`JooqAuthorizationBacklogReader.kt:31`)는 환경 분리가 없고 화면에도 없다. 화면 "확인이 필요한 업무"의 출처는 승인함, 내 요청, 대사 발견뿐이다(`H:26-35`). | 없음(대장 BR-CAN-099·100에도 "셈 없음"으로 기록돼 있음) | **불일치** |
| V-31 | P-37 KPI 클릭 → 상세 목록 | KPI 카드(`H:316-372`)에 클릭 동작이나 링크가 없다. | 없음 | **불일치** |
| V-32 | P-38 같은 기준의 상세 목록 | 기존 거래 검색(`PAY application/provided/AdminPaymentQuery.kt:25-56`)의 모드는 셋이다. paymentId 정확 조회는 기간이 없다(`:42-43`). MID + merchantOrderId 조회는 기간이 선택이다(`:44-45`). 그 밖의 **일반 목록 모드만 생성일(created_at) 기간이 필수**다(`:46-47`). 기간 상한 31일은 D-TXOPS-001의 Demo 값(운영값 아님, `:55-56`)이고, D-GOV-016 ②가 정확 조회의 기간 면제와 일반 목록의 기간 필수를 확정했다. 상태 필터는 Payment `status` 하나뿐이다(`:32`). 그래서 KPI와 같은 기준의 목록을 만들 수 없다: ① A의 PG 확정 시각 구간(생성일 축이 아님 — 전일 생성·당일 확정 승인, 전일 승인의 당일 취소), ② B·C의 전체 기간 현재 미해결(일반 목록은 생성일 기간 필수), ③ 취소 상태(OPEN/UNKNOWN) 필터, ④ hold·FORCED·늦은 승인 필터. | 없음 | **불일치** |
| V-33 | P-39 상세 목록 권한·마스킹 | 기존 거래 조회 규칙이 있다(`TransactionLookupService` VIEW 4역할). 재사용할 수 있다. | 기존 `TransactionLookupServiceSpec::view roles (I-1)` | 일치(재사용 전제) |

### 2.3 성능·구조

| # | 정책 | 코드·migration | 시험 | 판정 |
|---|---|---|---|---|
| V-34 | P-40 A 집계 인덱스·실행 계획 | `MIG V35__payment_transaction_confirmed_index.sql` `ix_payment_transaction_confirmed (type, confirmed_at, currency, amount_minor, payment_id)`, `fk_payment_transaction_payment` | `RS::the execution plans of the two statements (P-1)`(ANALYZE 뒤 EXPLAIN — range, eq_ref, filesort 없음) | 일치 |
| V-35 | P-40 B·C 건수 쿼리용 기존 인덱스 | 결과는 §2.4. 승인 미해결(`ix_payment_requested_expiry` status 선두, V7:13)과 취소 미해결(`uk_payment_cancellation_in_flight`, V15:37-42)은 기존 인덱스로 범위를 좁힐 수 있다. 인덱스가 없는 것은 FORCED(`return_reservation.state`)뿐이고, hold 표는 전체 스캔이다. | 없음(쿼리 미구현 — EXPLAIN 계획 시험 필요) | 시험 없음 |
| V-36 | P-41 집계 테이블·캐시 없음 | 없다(명세 C-5 "캐시 없음", 요약 표 migration 없음). 화면은 polling도 하지 않는다. | 직접 시험은 없다(부재 성질). | 일치 |
| V-37 | P-42 기존 로직·필드 유지 | A의 Reader, API, 검사를 그대로 둘 수 있다. | 기존 시험 | 일치 |
| V-38 | P-33 환경 표지, LIVE 먼저 | `H:134-138`, `HomeKpiQueryService.kt:28` | `HT::T-K4-1`, `HomeKpiQueryServiceSpec::a reader returning rows in arbitrary order` | 일치 |

판정 집계(38행)는 아래와 같다.
- 일치 21: V-02~04, 06~09, 11~14, 16, 18~21, 33, 34, 36~38. V-07은 ADMIN 쪽 미검사, V-21은 A 한정, V-33은 기존 규칙 재사용 전제다.
- 불일치 12: V-01, 05, 10, 23~28, 30~32
- 시험 없음 4: V-15, 17, 22, 35
- 확인 불가 1: V-29

### 2.4 건수 쿼리용 기존 인덱스 검토(Flyway migration 원문)

| 셀 값 | 필요한 조건 | 기존 인덱스 | 평가 |
|---|---|---|---|
| 승인 UNKNOWN과 진행 중(Payment 상태) | `payment.status IN ('AUTHORIZING', 'UNKNOWN')` + environment·currency | **`ix_payment_requested_expiry (status, checkout_expires_at, payment_id)`**(`MIG V7__checkout_authentication.sql:13`, status 선두). 이미 `PAY adapter/query/JooqElapsedRequestedPaymentReader.kt:21`이 REQUESTED 범위로 쓰고 있다. 그 밖에 PK, `uk_payment_key`, `uk_payment_order_generation`(V1:34,37), `ix_payment_created`, `ix_payment_mid_created`(V31:10-11)가 있다. **정정**: rev 0.2의 "status 인덱스 없음"은 틀렸다. 근거로 인용한 명세 ADMIN-HOME-KPI-001 §1.2의 "status 색인 없음"도 V7 기준 사실과 달라 **명세 정정 대상**이다(이 문서는 명세를 고치지 않는다). | status 선두 인덱스로 `IN ('AUTHORIZING', 'UNKNOWN')` 두 범위를 훑을 수 있다. environment·currency는 인덱스에 없어 PK lookup이 필요하지만, 미해결 Payment 수만큼이라 작다. **새 인덱스는 필요 없다.** 미해결 건 수에 비례하는 범위 읽기인지(type=range, key=`ix_payment_requested_expiry`, 전체 스캔 없음)는 EXPLAIN 계획 시험으로 확인한다. Attempt 표 경로(동치 증명이 필요한 대안)는 쓰지 않아도 된다. |
| 취소 UNKNOWN과 진행 중(OPEN) | `payment_cancellation.status IN (OPEN, UNKNOWN)` + environment·currency | **status 인덱스는 없다**(V15:7-65. 인덱스는 PK, `uk_…_merchant_key`, `uk_…_approval`, `uk_…_in_flight`, `ix_payment_cancellation_payment (payment_id, opened_at)`). 생성 열 `in_flight_payment_id`(OPEN·UNKNOWN일 때만 값, V15:37-42)에 UNIQUE 인덱스가 있다. | `in_flight_payment_id IS NOT NULL` 범위는 **미해결 행만 담은 인덱스**라 그대로 쓸 수 있다. status·environment·currency는 PK lookup으로 읽는다(미해결 수만큼이라 작다). 기존 gauge의 `c.STATUS.eq("UNKNOWN")`(`JooqCancellationViewReader.kt:116`)는 전체 스캔 가능성이 있다(참고 — 이번 범위 밖). |
| 장기 판단용 경과 시간 | 승인은 Attempt `prepared_at`, 취소는 `opened_at`이나 Attempt `prepared_at` | 승인은 `ix_authorization_attempt_state (state, prepared_at, …)`로 쓸 수 있다. `cancel_attempt`에는 state 인덱스가 없다(V15:69-90). | 승인은 인덱스로 처리한다. 취소는 미해결 취소 행(위 in-flight 범위)에서 `opened_at`을 읽으면 된다. 새 인덱스는 필요 없다. |
| 활성 hold | hold 중 해제 기록이 없는 것 + environment, 통화는 Payment join | `incident_hold`: PK, `uk_…_order_payment_reason_occurrence (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence)`(V11:13), `incident_hold_release.uk_incident_hold_release_hold (incident_hold_id)`(V9:227) | anti-join 쪽은 release UNIQUE로 처리된다. hold 표 자체는 **전체 스캔**이다(append-only라 계속 늘어난다). 기존 gauge도 같다(`JooqAuthorizationBacklogReader.kt:31`). 당장은 작아서 허용할 수 있지만 LIVE Gate에서 측정해야 한다. |
| FORCED | `return_reservation.state = 'FORCED'`(V15:188-209) | **state 인덱스는 없다**(PK cancellation_id, `uk_…_seq`, `uk_…_completion`, `uk_…_in_flight`) | 전체 스캔이다. 대안: FORCED는 hold를 함께 열므로(D-CANCEL-006) **활성 hold 경로로 좁혀** 센다. R-3의 답에 따라 다르다. 아니면 최소 인덱스를 추가한다. |
| 종료 Payment 늦은 승인 | hold reason `SUCCESS_AFTER_TERMINATION` 활성, 또는 FAILED Payment의 AUTHORIZATION(RECONCILIATION) 거래 | hold 경로는 위와 같다. 거래 경로는 `ix_payment_transaction_confirmed`의 type 선두 범위인데 **기간이 없으면 전체 승인 범위**가 된다. | hold 경로를 권장한다(R-3과 연동). |

결론: 승인 미해결(V7 `ix_payment_requested_expiry`), 취소 미해결(`uk_payment_cancellation_in_flight`), 활성 hold(전체 스캔이지만 작음 — LIVE Gate에서 측정)는 **기존 인덱스로 처리한다.** 인덱스가 없는 것은 FORCED(`return_reservation.state`)뿐이다. R-3 권장안(관련 hold가 활성인 동안만 셈)이면 활성 hold 경로로 좁혀 셀 수 있어 새 인덱스가 필요 없다. 어느 경우든 기존 P-1처럼 ANALYZE 뒤 EXPLAIN 계획 시험을 같이 둔다. 집계 테이블과 캐시는 쓰지 않는다(P-41).

### 2.5 불일치·결손 목록 (수정하지 않음)

| ID | 심각도 | 내용 | 관련 V | rev 0.1 대응 |
|---|---|---|---|---|
| M-1 | **높음** | B(현재 미해결 4개 건수: 승인/취소 × UNKNOWN/진행 중, 전체 기간, 환경·통화별)가 전혀 구현되지 않았다. | V-23~27 | G-1 |
| M-2 | **높음** | KPI 클릭 → 상세 목록이 없다. 기존 거래 검색은 일반 목록 모드만 생성일 기간이 필수이고(상한 31일은 D-TXOPS-001 Demo 값, 정확 조회는 기간 면제 — D-GOV-016 ②), 상태 필터는 Payment 상태 하나뿐이라 **같은 기준 목록을 만들 수 없다.** 특히 A(확정 시각 구간)와 취소·hold 목록이 안 된다. | V-31·32 | 신규 |
| M-3 | 중간 | C(hold, FORCED, 늦은 승인 건수)가 미구현이다. 사용자 확정 D-ADMIN-HOME-002 ①②와 D-GOV-013 ③의 "따로 셈"을 이행하지 않았다. | V-30 | G-2·G-7 |
| M-4 | 중간 | 장기 미해결 식별이 없다. 판단 시간은 별도 정의 대상이다. | V-28·29 | 신규 |
| M-5 | 중간 | 용어가 "순승인액"으로 남아 있다(화면과 시험 단언). D-GOV-014 ③과 다르다. | V-01 | G-6 |
| M-6 | 중간 | 승인금액·취소금액을 병기하지 않는다(값은 API에 이미 있음). | V-05 | G-6 |
| M-7 | 낮음 | A와 B의 기간 구분 표기가 없고, A 구간에도 "KST 0시~" 표기가 없다. BR-CAN-111 화면 부분과 다르다. | V-10 | G-5 |
| M-8 | 낮음 | B·C 쿼리용 인덱스 가운데 FORCED(`return_reservation.state`)에만 인덱스가 없다. R-3 권장안이면 hold 경로로 대신할 수 있다. hold 표 전체 스캔은 LIVE Gate 측정 대상이다(§2.4). rev 0.2의 "Payment status 인덱스 없음"은 정정했다(V7 `ix_payment_requested_expiry`가 있다). | V-35 | 신규(rev 0.2a 정정) |
| T-1 | 중간 | 환경·통화 분리의 DB 수준 시험이 없다(L-7, TEST·KRW Route뿐). B·C도 같은 한계를 물려받는다. | V-15 | G-3 |
| T-2 | 낮음 | A 재처리 뒤 합계·건수가 그대로인지 보는 KPI 수준 시험이 없다. | V-17 | G-4 |
| T-3 | 정보 | ADMIN 쪽 BR-CAN-104 사용처 제한 검사가 없다. 자정 커밋 지연은 한계로만 기록돼 있다. | V-07·22 | G-8·G-9 |
| C-1 | **질문 후보**(정책 정합 확인 — Coordinator 판단) | D-GOV-016 ②는 "일반 목록·부분 일치 검색은 생성일 기간 필수"다. KPI 클릭 상세 목록(P-37·38, D-GOV-014 ②)은 B·C에서 생성일 기간이 없는 현재 미해결 목록이고, A에서는 생성일이 아니라 PG 확정 시각 구간 목록이다. 두 목록이 기간 필수 규칙의 "일반 목록"에 해당하는지, 아니면 상태로 범위가 묶인 별도 목록(미해결 건만, 상한·keyset 페이지)이라 해당하지 않는지 해석이 필요하다. 권장 해석은 별도 목록(KPI 숫자와 같은 기준이 D-GOV-014 ②의 요구)이다. 사용자 질문으로 올릴지는 Coordinator가 판단한다. | V-32 | 신규 |

## 3. 대장 변경안 (반영하지 않음)

원칙은 셋이다(리뷰 MAJOR-4·m-1).
- 기존 행의 문장은 고치지 않는다. 기존 행은 상태만 `대체됨→BR-…`으로 바꾼다.
- 새 문장은 새 ID로 둔다. ID는 현재 대장의 마지막 번호(CAN 111, ADM 138) 다음으로 매겼고, 최종 부여는 Coordinator가 한다.
- 한 행에 확정과 제안을 섞지 않는다. 구현 결정이나 확장 해석은 `제안 · 적용` 행으로 나눈다. 화면 배치·문구·구현 방식은 대장에 넣지 않는다(CAN.md 머리 기준 — P-30 같은 표시 결정은 이 문서에만 기록한다).

### 3.1 기존 행 상태 변경안

| 기존 행 | 변경 상태 | 이유 |
|---|---|---|
| BR-CAN-097 | `대체됨→BR-CAN-112` | 용어 "순승인액" → "순결제금액"(D-GOV-014 ③) |
| BR-CAN-109 | `대체됨→BR-CAN-112` | 괄호 "(Q-KPI-2)", "(명칭은 Q-KPI-3)" 해소, 병기 추가(D-GOV-013 ③, D-GOV-014 ③) |
| BR-CAN-102 | `대체됨→BR-CAN-113` | 미해결 건수의 대상·기간·구분(D-GOV-014 ②) |
| BR-CAN-111 | `대체됨→BR-CAN-114` | 당일 실적과 현재 미해결의 기간 구분 표기(D-GOV-014 ②) |
| BR-ADM-137 | `대체됨→BR-ADM-139` | 위 BR-CAN-113의 ADM 경계 행 |
| BR-CAN-098·099·100·101·103·104·110, BR-ADM-135·136·138 | 변경 없음 | 문장이 새 결정과 충돌하지 않는다. BR-CAN-099·100 검증 열의 "셈 없음"은 구현(K-ATT-1) 뒤 Coordinator가 갱신한다. |

### 3.2 새 행 초안

| 새 ID | 대장 | 규칙 문장 | 상태 · 출처 | 근거 |
|---|---|---|---|---|
| BR-CAN-112 | CAN §12 | 운영 홈 KPI의 당일 **승인금액**은 KST 기준일 0시부터 조회 시각 전까지 PG가 승인 성공을 확정한 승인 거래 금액의 합이다(활성 incident hold가 걸린 AUTHORIZED Payment 포함). **취소금액**은 같은 구간에 PG가 취소 성공을 확정한 취소 거래 금액의 합이다(전액·부분·운영자 보정 취소, 전일 이전 승인의 당일 취소 포함). **순결제금액** = 승인금액 − 취소금액이며 음수가 될 수 있다. 모두 (환경, 통화)별 minor unit 정수다. 화면은 순결제금액과 승인금액·취소금액을 함께 보인다. | 확정 · 사용자 | D-GOV-014 ③, D-GOV-013 ③, D-ADMIN-HOME-001 ①② |
| BR-CAN-113 | CAN §12 | 운영 홈 KPI는 (환경, 통화)별로 나누고 TEST/LIVE 간·통화 간 합계를 만들지 않는다. 금액에는 PG가 성공을 확정한 승인·취소만 넣는다. 결과 미확정(UNKNOWN)과 진행 중은 금액에서 빼고, **조회 시각 현재 미해결 전체**(거래 발생일 무관)를 승인 UNKNOWN(Payment UNKNOWN), 승인 진행 중(Payment AUTHORIZING), 취소 UNKNOWN, 취소 진행 중(취소 OPEN)의 4개 값으로 각각 센다. 4개 값은 서로 합치지 않고, REQUESTED는 세지 않는다. | 확정 · 사용자 | D-GOV-014 ②, D-GOV-010 ⑤ |
| BR-CAN-114 | CAN §12 | 운영 홈 KPI는 응답과 화면에 당일 실적의 기준일·구간(KST 기준일 0시부터 조회 시각 전까지)·시간대·PG 확정 시각 기준임을 밝히고, 현재 미해결은 조회 시각 현재·전체 기간 기준임을 구분해 밝힌다. | 확정 · 사용자 | D-GOV-014 ②③, D-GOV-010 ⑤ |
| BR-CAN-115 | CAN §12 | 미해결 건수는 같은 건의 재시도·재조회·Recovery·여러 Attempt로 늘어나지 않는다(중복 집계 금지). | 확정 · 사용자 | D-GOV-014 ②("승인·취소 건수 구분·중복 집계 방지") |
| BR-CAN-116 | CAN §12 | 미해결 건수의 집계 단위는 승인 쪽이 Payment 하나, 취소 쪽이 취소 operation 하나다(Payment당 진행 중 취소 하나 — D-CANCEL-003). | 제안 · 적용 | P-23(구현 결정), D-CANCEL-003 |
| BR-CAN-117 | CAN §12 | 미해결 건은 확정되는 순간 미해결에서 빠지고, 성공이면 그 확정일의 당일 실적에 한 번만 들어간다(같은 시각에 두 곳에 세지 않음). | 제안 · 적용 | P-24(BR-CAN-110 확장 해석) |
| BR-CAN-118 | CAN §12 | 활성 incident hold가 걸린 AUTHORIZED Payment는 KPI 금액·건수에 포함하고, 활성 hold 건수는 (환경, 통화)별 "확인 필요"로 따로 센다. | 확정 · 사용자 | D-GOV-013 ③ |
| BR-CAN-119 | CAN §12 | 미해결 가운데 오래 머문 건(장기 UNKNOWN·장기 진행 중)을 식별해 표시한다. 판단 시간은 매입사·결제 처리 정책에 따라 별도로 정의한다. | 확정 · 사용자 | D-GOV-014 ② |
| BR-CAN-120 | CAN §12 | KPI 집계는 집계 테이블과 캐시를 선제 도입하지 않고, 기존 인덱스와 집계 쿼리 성능을 먼저 검토한다. | 확정 · 사용자 | D-GOV-014 ② |
| BR-CAN-121 | CAN §12 | 자정 커밋 지연이나 스냅샷 뒤 커밋으로 생기는 일시 과소·과다는 비권위 표시값이라 허용하며, 다음 조회에서 수렴한다. | 제안 · 적용 | 명세 L-2(rev 0.1 N-6) |
| BR-CAN-122 | CAN §12 | 새 반환 거래 유형(Refund 등)이 생기면 KPI 취소금액 정의를 다시 정하기 전까지 그 유형을 집계하지 않는다. | 제안 · 적용 | 명세 §3.5, T-K1-12(rev 0.1 N-7) |
| BR-ADM-139 | ADM (KPI) `[경계 CAN]` | BR-CAN-113과 같다. | 확정 · 사용자 | D-GOV-014 ②, D-GOV-010 ⑤ |
| BR-ADM-140 | ADM (KPI) | KPI 숫자를 누르면 그 숫자를 이루는 거래 상세 목록을 연다. 목록은 KPI와 같은 기준(상태 조건, 기간 축, 환경·통화)이다. | 확정 · 사용자 | D-GOV-014 ②(같은 기준은 원문 "해당 거래 상세 목록"의 귀결) |
| BR-ADM-141 | ADM (KPI) | KPI 상세 목록은 기존 거래 조회의 권한(VIEW 4역할)·마스킹·감사 규칙을 그대로 쓴다(목록 감사 없음, 상세 열람은 `PAYMENT_VIEWED`). | 제안 · 적용 | P-39(구현 결정), BR-CAN-094, BR-ADM-138, D-TXOPS-002 |

rev 0.2 초안과 새 ID의 대응은 다음과 같다.
- BR-CAN-112(rev 0.2 초안, 미해결) → BR-CAN-113 + 115(확정)와 116·117(제안)로 나눴다.
- BR-CAN-113(hold) → BR-CAN-118
- BR-CAN-114(장기) → BR-CAN-119
- BR-CAN-115(구조) → BR-CAN-120
- BR-ADM-139(상세 목록) → BR-ADM-140(확정)과 141(제안)로 나눴다.
- rev 0.2 §3.1의 "기존 행 문장 보완"은 모두 철회했다. 대신 대체 행 BR-CAN-112·113·114와 BR-ADM-139를 새로 둔다.

## 4. 구현 작업 범위 (카드 제안만 — 등록·구현하지 않음)

| 카드 | 범위 | API 필드(제안) | 화면 | 시험(제안) | 의존 |
|---|---|---|---|---|---|
| **K-TERM-1** 용어·병기·기간 표기(작음) | ADMIN 화면만 | 변경 없음. `approvedAmountMinor`·`cancelledAmountMinor`가 이미 있다. | "오늘 순결제금액" 이름, 행마다 "승인 X · 취소 Y" 병기, 힌트 "오늘 0시(KST) ~ HH:MM · PG 확정 시각 기준 · 정산 금액 아님" | `HT` 단언 갱신(사용자 결정에 따른 문구 변경 — 기대값 변경 근거 D-GOV-014 ③), 병기·음수·2^53 사례 | 없음 |
| **K-ATT-1** 현재 미해결·확인 필요 집계(ADMIN-HOME-ATTENTION-001 갱신) | PAYMENT Reader(jOOQ)·조회 서비스·endpoint, ADMIN 모양 검사, 화면 | `GET /internal/v1/admin/home-kpis` 응답에 `openItems: [{environment, currency, authorizationUnknown, authorizationInProgress, cancellationUnknown, cancellationInProgress, activeHolds, forcedLateCancels, lateApprovals, oldest…?}]`를 추가한다(같은 asOf와 스냅샷). 또는 별도 endpoint로 둔다(가역 — 같은 스냅샷이 필요하면 같은 응답). 장기 필드는 R-1 답 뒤에 정한다. | B 카드(4개 값, "HH:MM 현재 · 전체 기간"), C 범주별 건수. "확인이 필요한 업무" 총계에는 넣지 않고 범주별 건수에 겹침 가능을 표기한다(P-30 구현 결정). | 공식 경로 Named Fixture로 상태별 1건씩 만든다. 승인과 취소가 섞이지 않음, Payment·operation 단위로 1건(재시도·Recovery 뒤 불변), 확정 시 B에서 빠지고 A로 이동, 전일 개시 UNKNOWN 포함(전체 기간), REQUESTED 제외, 환경·통화 행 키, **EXPLAIN 계획 시험**(§2.4), ADMIN 모양 위반 → 503, 화면 표시 | K-IDX-1(필요 시), R-1·R-3 |
| **K-IDX-1** 최소 인덱스(필요할 때만) | PAYMENT migration | — | — | ANALYZE 뒤 EXPLAIN으로 전체 스캔이 없음을 확인. INSERT 비용은 LIVE Gate에 기록 | 승인·취소 미해결은 기존 인덱스(V7 `ix_payment_requested_expiry`, V15 `uk_payment_cancellation_in_flight`)로 충분하다. FORCED만 대상이며, R-3이 권장안(hold 활성 동안)이 아니거나 hold 경로 EXPLAIN이 불충분할 때만 만든다 |
| **K-DRILL-1** KPI → 상세 목록 | PAYMENT 관리 조회 새 모드, ADMIN 조회, 화면 링크 | 새 검색 모드(기존 `AdminPaymentSearchCriteria`의 세 모드 — 정확 조회 기간 면제, 일반 목록 생성일 기간 필수(D-GOV-016 ②, 상한 31일은 Demo) — 는 그대로 두고 별도 모드로 만든다. D-GOV-016 ②와의 정합은 §2.5 C-1 해석에 따른다): ① `todayFacts(environment, currency, type=AUTHORIZATION/CANCEL)` — 확정 시각 구간의 거래 행(같은 asOf), ② `open(environment, currency, kind=AUTH_UNKNOWN/AUTH_IN_PROGRESS/CANCEL_UNKNOWN/CANCEL_OPEN/HOLD/FORCED/LATE_APPROVAL)` — 기간 없음, keyset 페이지 | KPI 숫자를 링크로 바꾸고 거래 조회 화면에 필터를 적용한 상태로 연다. 목록 머리에 기준을 표기한다. | **목록 건수 = KPI 숫자**(같은 스냅샷 시점 fixture), 권한(VIEW 4역할)·마스킹·감사(목록은 없음, 상세는 PAYMENT_VIEWED), 계획 시험, keyset 경계 | K-ATT-1(B·C 목록 모드) |
| **K-TEST-1** 분리 DB 시험(선택) | 시험 설정 | — | — | 시험 전용 LIVE/USD Simulator Route를 정한 뒤 A·B·C의 환경·통화 분리를 실제 사실로 확인(T-K1-9·11, Test Fixture Policy 준수) | Coordinator 판단 |

모든 카드에 공통으로 다음을 지킨다.
- A의 Reader와 기존 필드 이름은 바꾸지 않는다(P-42).
- 쓰기 경로와 금융 효과는 없다.
- 집계 테이블과 캐시는 두지 않는다.
- 기존 시험 기대값을 바꿀 수 있는 것은 사용자 결정에 따른 문구 변경(K-TERM-1)뿐이다.

## 5. 남은 결정 (2개)

**R-1 장기 미해결 표시 — 판단 시간 정의 전에 무엇으로 보일까**
- 권장안: 새 숫자를 정하지 않는다. 대신 다음 두 가지를 보인다.
  - ① **가장 오래된 미해결의 경과 시간**: 승인은 Attempt `prepared_at`, 취소는 `opened_at` 기준이다.
  - ② **기존 Recovery 이관(escalated) 건수**: 이미 있는 운영 신호다. 승인 `authorization_recovery_schedule.escalated_at`, 취소 `cancellation_recovery_schedule`.
  - 판단 시간은 매입사·결제 처리 정책 명세에서 정한다.
- 대안: (b) Demo 기본값(예: 승인 30분, 취소 1일)을 **필수 설정·운영값 아님**으로 두고 그 시간을 넘은 건수를 보인다. (c) 판단 시간 정의 때까지 장기 식별 표시를 미룬다.
- 영향: 표시만 바뀌고 금융 효과는 없다. (b)는 숫자가 운영 기준처럼 읽힐 위험이 있다.
- 막히는 작업: K-ATT-1의 장기 필드, BR-CAN-119 보완.

(rev 0.2의 R-2 "확인 필요 범주 간 중복"은 표시만의 문제라 Coordinator 구현 결정으로 처리했다 — §1.6 P-30.)

**R-3 FORCED·늦은 승인이 확인 필요에서 빠지는 시점**
- 권장안: **관련 incident hold가 해제되면** 빠진다(hold 해제가 운영 처리 완료 근거 — D-CANCEL-006·011, BR-CAN 보정 흐름). FORCED 예약 자체는 append-only라 영구히 남는다.
- 대안: (b) 영구히 센다(누적 사고 건수). (c) 대사 MATCH로 확인된 뒤에 뺀다.
- 영향: C 건수와 쿼리 경로(§2.4 — 권장안은 활성 hold 경로라 새 인덱스가 필요 없음).
- 막히는 작업: K-ATT-1, BR-CAN-099·100 검증 열 갱신.

## 부록 A. rev 0.1 → rev 0.2 → rev 0.2a 대응

| rev 0.1 | rev 0.2 |
|---|---|
| Q-KPI-1(대상·기간) | D-GOV-014 ② → P-10·19~24, BR-CAN-112 초안 |
| Q-KPI-2(hold) | D-GOV-013 ③ → P-15·29, BR-CAN-113 초안 |
| Q-KPI-3(명칭·병기) | D-GOV-014 ③ → P-01~06, BR-CAN-109·097 변경안 |
| G-1 / G-2·G-7 / G-5 / G-6 | M-1 / M-3 / M-7 / M-5·M-6 |
| G-3 / G-4 / G-8·G-9 | T-1 / T-2 / T-3 |
| V-26(확인 불가) | V-12 일치(확정됨) |
| rev 0.2 R-2 | rev 0.2a P-30 구현 결정 |
| rev 0.2 §3 초안 ID(CAN 112~115, ADM 139) | rev 0.2a §3.2 새 ID(CAN 112~122, ADM 139~141) |
