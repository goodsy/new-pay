# ACQ-REACQ-CANCEL-001 원승인의 '최종 유효 매입 상태'로 취소 매입 판정 — 현황 분석·정책 초안·상태 전이표·시험 시나리오

> 상태: **분석·초안(정책 승인 전)**. 이 문서는 소스·시험·규칙 대장(`docs/rules/FIN.md`)·`docs/DECISIONS.md`를 바꾸지 않는다. 시험 기대값을 바꾸지 않았고, CC-4 → CC-8 기대값 이동도 확정하지 않는다(사용자 지시 2026-11-26, D-GOV-010 ④).
> 기준: `main` HEAD `30c550c`(작업 트리에 사용자 변경 `docs/DECISIONS.md`·`harness/questions.json` 있음 — 읽기만 함). 작성 2026-11-26 KST.
> 근거 결정: D-ACQ-004·009·010·012·013·014, D-ACQA-003·004, D-GOV-010 ④. 관련 대장 행: BR-FIN-037·038·039·040·041·046·048·049·050·051·052. 관련 진단: POLICY-CONFLICTS PC-66, NORMALIZATION-PLAN NP-21, RULE-CONFIRM-001 §2 R1-Q2.

**개정 이력**
- **0.1** (2026-11-26): 첫 초안.
- **0.1a** (2026-11-26, Coordinator 리뷰 반영 — 이 문서만 변경): m-7 §4.2 규칙 5에 예외를 더했다. '불요'의 근거 세대가 최종 유효 세대가 아니면 자동으로 다시 판정하지 않고 대사·Adjustment 경로로 보낸다. 이미 잘못 종결됐을 수 있는 '불요' 기록을 찾는 시나리오 ARC-20(조회 SQL 포함, 실행하지 않음)을 더했다. m-8 재매입 쪽 이중 매입 경로(g0 CONFLICT + g1 REJECTED면 g2 승인 가능)를 §5.3 행과 ARC-21로 더했다. m-9 질문을 다시 짰다: 옛 Q-ARC-3(CC-4 → CC-8 기록 방식)을 Q-ARC-1에 합쳤고, 기록 방식은 Coordinator가 정할 가역 세부로 §4.3에 적었다. m-7·m-8 결정을 새 Q-ARC-3으로 묶었다. §3.2의 모호한 표현을 고쳤고, `4bd0105`·`1ad6ed8` 인용은 `git show`로 다시 확인했다.

## 1. 배경과 질문

**BR-FIN-040 현재 문장**(`docs/rules/FIN.md:78`, 상태 `제안`, 출처 `적용`):
> 원 SALE이 재매입된 경우 그 뒤 받은 취소는 가장 높은 세대 원 SALE의 결과를 기준으로 판정한다.
> 근거: ACQ-ADMIN-001 I-14·L-6(CC-4 기대값 변경) / 검증: CancelCandidateRuleSpec::CC-8, ReacquisitionResultDbSpec::RR-1·RR-2

**RULE-CONFIRM-001 §2 R1-Q2**는 이 행을 "시험 기대값 CC-4 → CC-8 이동"으로 사용자에게 물었고, 권장안은 "현행 유지 + 사후 승인"이었다.

**사용자 답(2026-11-26, D-GOV-010 ④ — 확정 보류 + 지시)** 요약:
- 최초 매입이나 가장 최근(최고 세대) 매입 결과가 아니라 **원승인 거래의 최종 유효 매입 상태**를 기준으로 취소 매입 여부를 정하는 정책으로 정의한다.
- 먼저 검증할 6개 경우: ① 최초 매입 실패 → 재매입 성공 ② 최초 매입 성공 → 중복 재매입 실패 ③ 반복 재매입·결과 미수신 ④ 재매입 처리 중 전체·부분취소 ⑤ 취소 매입 중복 전송 방지 ⑥ 매입사 응답과 내부 매입 상태 정합.
- CC-4·CC-8의 정의와 기대값 변경 근거를 설명하고, 업무 정책·상태 전이표·시험 시나리오를 먼저 쓴다.
- 정책 승인 전 기존 변경 확정·소스 수정·기대값 변경 금지.

**이 문서가 답하려는 질문**: 지금 코드의 "최고 세대 기준"이 "최종 유효 매입 상태 기준"과 어디서 갈리는가, 그 차이가 이중 매입·취소 누락·이중 취소 매입 위험을 만드는가, 승인받을 정책은 무엇인가.

## 2. 현재 구현 사실(코드 근거)

경로 접두 `apps/financial-batch/src/main/kotlin/com/paycore/financial/acquiring/` 는 `…/`로, migration `apps/financial-batch/src/main/resources/db/migration/financial/` 은 `V*/`로 줄인다.

### 2.1 매입 레코드·세대·결과 상태

| 사실 | 근거 |
|---|---|
| 매입 레코드 = 파일의 한 줄. 종류 SALE·CANCEL. `generation` 0 = 첫 매입, ≥ 1 = 재매입이며 재매입만 `reacquisitionOf = {fileId}#{seq}`를 가진다 | `…/domain/AcquisitionRecord.kt:14-35`, `:75-76`, `:92` |
| CANCEL 레코드는 언제나 generation 0으로 만든다(CANCEL 재매입 없음) | `…/domain/CancelCandidateRule.kt:86-95`(`generation = 0, reacquisitionOf = null`) |
| 레코드 결과는 append-only 결과 행에서 계산한다. 상태 값 = AWAITING·ACCEPTED·REJECTED·FILE_REJECTED·UNKNOWN·CONFLICT. CONFLICT 행이 하나라도 있으면 CONFLICT, 그다음 첫 확정 행(ACCEPTED/REJECTED), 없으면 UNKNOWN 행 여부 | `…/domain/RecordResultRule.kt:18-27`, `:139` |
| 같은 결과 반복 = 중복(행 없음), 확정 뒤 다른 결과 = CONFLICT 행(격리). AWAITING/UNKNOWN에서 결과가 오면 반영(늦은 결과 자동 반영 — D-ACQ-010) | `…/domain/RecordResultRule.kt:35-61` |
| 마감까지 결과 없는 레코드만 UNKNOWN 행. 마감 처리는 **전송 축 TRANSMITTED 파일만** 본다 — 전송 보류·격리 파일 레코드는 AWAITING에 머문다 | `…/domain/RecordResultRule.kt:64-66`, `…/application/AcquisitionResultCloseService.kt:73` |
| **매입 확정 표지**: ACCEPTED 결과 행을 쓰는 같은 TX가 `acquisition_accepted_record`(PK(transaction_id, record_type) — 세대를 넘어 하나)를 INSERT. 다른 세대에 표지가 있으면 들어온 ACCEPTED는 CONFLICT 행 | `V14__acquisition_accepted_record.sql:15-27`, `…/application/AcquirerResponseService.kt:248-253`, `…/domain/RecordResultRule.kt:45-46` |
| 원 SALE이 CONFLICT가 되면 그 레코드를 가리키는 "취소 매입 불요" 기록이 있을 때만 error 로그(대사) | `…/application/AcquirerResponseService.kt:264-268` |

### 2.2 재매입 조건(SALE만)

| 사실 | 근거 |
|---|---|
| 대상 = 상태 REJECTED·FILE_REJECTED, 그 거래·종류의 최고 세대, **어느 세대에도 ACCEPTED 없음**(ALREADY_ACCEPTED) | `…/domain/ReacquisitionRule.kt:28-36` |
| UNKNOWN 대상은 대사 기능 전이라 거절(UNKNOWN_NOT_RECONCILED) | `…/domain/ReacquisitionRule.kt:33` (BR-FIN-048, I-55) |
| 실행 직전 재검사: 어느 세대든 ACCEPTED가 됐거나 상태·세대가 바뀌면 TARGET_CHANGED | `…/domain/ReacquisitionRule.kt:54-64` |
| CANCEL 레코드 재매입은 미지원(CANCEL_NOT_SUPPORTED — ACQ-ADMIN-002로 이월) | `…/domain/ReacquisitionExecutionRule.kt:50` |
| 아직 매입 판정이 안 된 취소가 있으면 실행 거절(CANCELLATION_UNDECIDED), 미해제 보류면 PAYMENT_HELD | `…/domain/ReacquisitionExecutionRule.kt:52-53` |
| 재매입 레코드 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(각 한 번), 순액 0이면 파일 없이 '재매입 불요' | `…/domain/ReacquisitionExecutionRule.kt:57-67`, `…/domain/ReacquisitionRule.kt:70-75` (D-ACQ-013, D-ACQA-004) |
| 묶음 중 하나라도 거절이면 승인 전체 거절 | `…/domain/ReacquisitionExecutionRule.kt:43-49` (D-ACQA-003) |

**자연 도달 범위(명세 ACQ-ADMIN-001 §15 L-1)**: 세대 1은 세대 0이 REJECTED·FILE_REJECTED로 **종결된 뒤에만** 생긴다. 종결 뒤 들어온 ACCEPTED는 ACCEPTED가 아니라 CONFLICT가 된다. 그래서 "앞 세대가 ACCEPTED(표지)인데 뒤 세대도 있는" 상태는 지금 규칙으로는 자연히 생기지 않는다. 대사(RECON)가 UNKNOWN 재매입을 열면 생긴다(UNKNOWN → 재매입 → 앞 세대의 늦은 ACCEPTED). 반면 "앞 세대 CONFLICT + 뒤 세대 존재"는 **지금도 생긴다**(앞 세대 REJECTED → 재매입 실행 → 매입사가 앞 세대에 상충 ACCEPTED를 보냄).

### 2.3 결과 반영

- 응답 파일의 줄마다 `RecordResultRule.apply` → Append(결정)·Conflict(격리 행)·Duplicate·AlreadyConflicted. ACCEPTED Append면 같은 TX에서 표지 INSERT(`…/application/AcquirerResponseService.kt:232-276`).
- 파일 전체 거절(ACK FILE_REJECTED)은 그 파일 레코드 전부를 FILE_REJECTED로 종결(`…/domain/RecordResultRule.kt:11`, `:24`).
- 동시 경합은 표지 PK 위반 → 재시도 → CONFLICT로 수렴(ACQUIRING-001 I-56).

### 2.4 취소 후보 판정(전송 후 취소)

| 단계 | 근거 |
|---|---|
| 봉인 TX에서 lane 잠금 → 결제별 행 FOR UPDATE → 판정 끝난 취소(상계·CANCEL 레코드·불요) 다시 제외 | `…/application/AcquisitionSealService.kt:120`, `:157`, `:234-235` |
| **원 SALE 고르기 = 그 원승인의 SALE 레코드 중 `maxBy { generation }`** — 최고 세대. 확정(표지) 세대가 아니다 | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:51-64`(`:61`) |
| 고른 레코드 **하나의** 결과 상태만 계산해 넘긴다 | `…/application/AcquisitionSealService.kt:237-239`, `:258-259` |
| 판정: LIVE·마감 뒤·보류·통화·비정합(누적 한도 포함)이 먼저, 그다음 원 SALE 결과로 ACCEPTED → CANCEL 레코드, AWAITING → 대기, UNKNOWN·CONFLICT → 대사(기록 없음, 다음 봉인에 다시 판정), REJECTED·FILE_REJECTED → '취소 매입 불요' 기록(종결) | `…/domain/CancelCandidateRule.kt:20-42`, `…/domain/CancelRecordRule.kt:11-16`, `…/domain/RecordResultRule.kt:72-78` |
| 정합 검사: 원 레코드가 이 거래의 SALE이고 승인액이 같으며, 원 레코드 상계액 + 이미 봉인한 CANCEL + 이 취소 ≤ 원승인액. rev 0.7에서 `sale.generation != 0` 조건을 뺐다 | `…/domain/CancelCandidateRule.kt:44-54`(`:48-50`) |
| 같은 원승인의 여러 취소는 (취소 시각, 거래 ID) 순으로 누적 | `…/application/AcquisitionSealService.kt:224`, `:248` |

### 2.5 중복 전송 방지 장치

| 장치 | 막는 것 | 근거 |
|---|---|---|
| UNIQUE(transaction_id, record_type, generation) | 같은 세대 레코드 두 번. CANCEL은 언제나 세대 0이라 **취소 하나당 CANCEL 레코드 하나** | `V5__acquisition_submission.sql:102` |
| 상계 PK(cancellation_transaction_id), 불요 PK(cancellation_transaction_id) | 같은 취소의 상계·불요 두 번 | `V5__…:128`, `V10__acquisition_response.sql:88` |
| 잠금 아래 판정 끝남 재확인(상계 ∪ CANCEL 레코드 ∪ 불요) | 같은 취소가 세 경로 중 둘 이상으로 판정됨(표를 넘는 DB 제약은 없고 잠금 + 재확인이 지킨다) | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:78-86` |
| 누적 한도(상계 + 봉인 CANCEL + 이 취소 ≤ 원승인액) | 원승인액을 넘는 취소 매입 | `…/domain/CancelCandidateRule.kt:51-53` |
| 매입 확정 표지 PK(거래, 종류) | 세대를 넘는 두 번째 ACCEPTED를 결정으로 인정 | `V14__…:23` |
| 재매입 승인 UNIQUE(거래, 종류, 새 세대)·(파일, 순번, 새 세대), 승인 ID UNIQUE | 같은 세대 재매입 두 번·같은 승인 재실행 | `V12__…:23`, `V17__…:26`, `:43` |
| 전송 보류 TRANSMISSION_WITHHELD | 봉인한 재매입 파일의 레코드가 그 사이 다른 세대에서 표지를 얻으면 보내지 않음(다른 세대 파일 행 잠금 아래 재확인, 종결·자동 해제 없음) | `…/application/AcquisitionTransmissionService.kt:167-182`, `…/domain/AcquisitionFile.kt:58`, `:68-76`, `V19__…:24` |
| 전송 확인 우선(최종명 hash MATCH면 다시 올리지 않음) | 같은 파일 재업로드 | `…/application/AcquisitionTransmissionService.kt`(upload — `Check.MATCH`) |

남은 위험으로 명세가 적은 것(ACQ-ADMIN-001 §15 L-9 rev 0.8): m-3 보류 중 받은 취소가 최고 세대(보류된 파일)를 기다리며 무기한 대기, m-4 전송 점유 커밋 **뒤** 도착한 원 세대 ACCEPTED는 막을 수 없어 이중 매입 요청 가능(CONFLICT·대사로 처리).

## 3. CC-4와 CC-8 — 변경 전후 정의와 진단

### 3.1 변경 전(커밋 `4bd0105`, 2026-11-06 17:06 KST — ACQUIRING-001. 아래 인용은 0.1a에서 `git show 4bd0105:<경로>`로 다시 확인)

- 원 SALE 조회: `saleMemberships`가 **세대 0만** 읽었다 — `"... r.recordType = 'SALE' and r.generation = 0 ..."`(`git show 4bd0105:…/JpaAcquisitionMembershipStore.kt:54`).
- 정합 규칙: `if (sale.type != SALE || sale.generation != 0 || ...) return false`(`git show 4bd0105:…/CancelCandidateRule.kt:48`).
- CC-4 넷째 벡터(원문, `git show 4bd0105:…/CancelCandidateRuleSpec.kt:63`):
  ```kotlin
  cancelAfterMembership(original).let { it.copy(originalSale = it.originalSale.copy(generation = 1, reacquisitionOf = "ACQ.SIMACQA.TEST.20261105.001#1")) },
  ```
  기대값: `CancelCandidateRule.decide(it, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.INCONSISTENT)` — 세대 1 원 SALE은 결과가 ACCEPTED여도 대기.
- 이 시점에는 세대 1 레코드를 원 SALE로 넘기는 경로가 없어서(조회가 세대 0만) 방어 벡터였다.

### 3.2 중간 단계(ACQ-ADMIN-001 rev 0.2 B1 — 커밋 없음, 명세 기록으로만 남음)

git 이력에서 `CancelCandidateRule.kt`·`JpaAcquisitionMembershipStore.kt`·`CancelCandidateRuleSpec.kt`를 바꾼 커밋은 `4bd0105`와 `1ad6ed8` 두 개뿐이다(`git log --oneline -- <세 파일>`). ACQ-ADMIN-001 명세 파일도 `1ad6ed8`에서 처음 추가됐다(`git show 1ad6ed8 --stat` — 823줄 추가). 따라서 B1(rev 0.2)과 B2(rev 0.7)는 **따로 커밋되지 않았다**. 아래 B1 상태는 명세 rev 0.2 기록(§11 I-14, §15 L-6, §17 실행 기록)으로만 확인할 수 있고, git으로 되돌려 볼 수 있는 시점은 없다.

- I-14(명세 `:283`): `saleMemberships`가 **최고 세대**를 고르게 바뀌었다. 이유(현재 코드 주석 `JpaAcquisitionMembershipStore.kt:52-54`): 세대 0 REJECTED로 '취소 매입 불요'를 써 버리면 세대 1이 ACCEPTED될 때 그 취소가 매입사에 가지 않는다.
- 명세 rev 0.2 기록상 이때 세대 ≥ 1 원 SALE은 CC-4 넷째 벡터대로 INCONSISTENT 대기였다. 그래서 잘못된 종결 기록이 없었다(L-6 `:372`, §17 RX-7 "INCONSISTENT 대기 관찰" `:413`).

### 3.3 변경 후(커밋 `1ad6ed8`, 2026-11-07 22:08 KST — ACQ-ADMIN-001 B1+B2를 한 번에, 명세 rev 0.8 상태로 커밋. 인용은 `git show 1ad6ed8`로 다시 확인)

- `saleMemberships`: `r.generation = 0` 조건 삭제 + `rows.maxBy { it.generation }`(`git show 1ad6ed8:…/JpaAcquisitionMembershipStore.kt:61` — 현재 HEAD도 같은 줄).
- 정합 규칙에서 `sale.generation != 0` 삭제(`…/domain/CancelCandidateRule.kt:48-50`, 커밋 diff `-` 줄과 `+` 줄로 확인).
- CC-4: 넷째 벡터 삭제, 자리에 "원 레코드 종류 CANCEL", "원 레코드 승인액 10,001" 불일치 벡터 2개 추가(강화) — `CancelCandidateRuleSpec.kt:57-76`, 주석 `:60-61`.
- CC-8 신설(`CancelCandidateRuleSpec.kt:78-108`) — 원문 제목: "a cancellation received after the original was re-acquired: judged against the highest generation SALE (I-14)". 입력: 원승인 10,000, 실행 전 판정 끝난 취소 3,000(상계액), 실행 뒤 받은 취소 2,000. 기대값:
  - `OriginalSaleResult.ACCEPTED` → `Include`, CANCEL 레코드(원승인액 10,000, 금액 2,000, 상계 0, 세대 0, reacquisitionOf null)
  - `REJECTED` → `NotRequired`, `AWAITING` → `Wait(ORIGINAL_AWAITING_RESULT)`, `UNKNOWN` → `Reconcile`
  - 세대 2도 ACCEPTED → `Include`
  - 누적 한도: 2,001 + 3,000 + 5,000 > 10,000 → INCONSISTENT, 2,000이면 Include, REJECTED여도 초과면 INCONSISTENT
  - 다른 거래·승인액 다른 재매입 레코드 → INCONSISTENT
- 함께 바뀐 기대값: `ReacquisitionExecutionDbSpec` RX-7 `cancelWaiting = {INCONSISTENT:1}` → `{ORIGINAL_AWAITING_RESULT:1}`(불요 0·CANCEL 0 단언은 그대로) — `ReacquisitionExecutionDbSpec.kt:258-266`.
- 명세 근거: ACQ-ADMIN-001 rev 0.7 이력 줄(`ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md:17` "L-6 CancelCandidateRule 세대 ≥ 1 개방(CC-4 기대값 변경 — 근거 L-6), … 사용자 결정 변경 없음"), §15 L-6(`:372`), §17.10 "바뀐 기존 기대값과 근거" 표(`:703-705`). L-6 원문(rev 0.2): "새 세대 ACCEPTED 뒤 CANCEL 레코드·새 세대 REJECTED 뒤 불요 판정은 B2에서 CancelCandidateRule을 바꿔 연다(기존 CC-4 기대값 변경 = B2 명세 근거 필요)."
- B2 독립 검토: PASS(MAJOR 0), 남은 위험 m-2·m-3·m-4를 기록만(`:376-379`). m-3 원문이 "코드 수정(확정 세대 우선 선택)은 ACQ-ADMIN-002"라고 적어, **최고 세대가 아니라 확정 세대를 써야 한다는 점은 이미 그때 알려져 있었다**.

### 3.4 진단

| 질문 | 판단 | 근거 |
|---|---|---|
| 정책 변경이 시험 기대값 이동으로 들어갔는가 | **그렇다.** "세대 ≥ 1 원 SALE 뒤 취소 = 자동 판정하지 않음(대기)"에서 "최고 세대 결과로 자동 CANCEL·불요 판정"으로 바뀐 것은 취소 매입 여부(금융 의미)를 바꾼다. 이것이 Implementer 명세(L-6)와 CC-4 → CC-8 기대값 이동으로만 들어갔다 | rev 0.7 이력 "사용자 결정 변경 없음", FIN.md:78 출처 `적용` |
| 사용자 승인 근거가 있는가 | **없다.** D-ACQ-009는 "원 SALE ACCEPTED일 때만"(세대 언급 없음), D-ACQ-013은 재매입 금액, D-ACQ-014는 세대를 넘는 ACCEPTED 하나. 어느 것도 "재매입 뒤 취소는 최고 세대 기준"을 정하지 않았다. 사용자 질문 기록도 없다 | DECISIONS.md:208·212·213, PC-66 |
| 변경이 위험을 줄였는가 늘렸는가 | 세대 0 REJECTED 뒤 세대 1 ACCEPTED 경우(정상 재매입 성공)는 **바르게** CANCEL을 보낸다(RR-1) — rev 0.6은 이 취소를 무기한 대기시켰다. 그러나 "최고 세대 = 유효 세대"라는 가정이 깨지는 경우(앞 세대 CONFLICT·앞 세대 표지 + 뒤 세대 거절·보류)에는 잘못된 종결 또는 무기한 대기가 생긴다(§5 다른 칸) | §5 |
| 원칙 위반 여부 | CLAUDE.md "정책·테스트 기대값·게이트 기준을 바꿔서 검사 통과시키지 않는다. 새 정책은 사용자 답변과 근거를 기록한다"에 걸린다. 다만 검사를 통과시키려고 바꾼 것이 아니라 명세(L-6)를 먼저 고친 뒤 RED → GREEN으로 바꿨다(§17.10 RED 기록 "CC-8: INCONSISTENT"). 절차 위반은 **사용자 승인 없이 금융 규칙을 연 것**이다 | ACQ-ADMIN-001 §17.10 |

**결론**: CC-8의 기대값 자체(세대 ≥ 1 SALE 결과를 세대 0처럼 판정)는 "다른 세대에 표지·상충이 없을 때"에 한해 아래 제안 정책과 같다. 틀린 부분은 기대값이 아니라 **입력을 정하는 곳**(최고 세대 하나만 고르는 `saleMemberships`)이다. 그래서 CC-8의 기존 기대값은 바꿀 필요가 없고, 제안 정책이 승인되면 **새 벡터를 더하는 것**으로 충분할 것으로 본다(§7).

## 4. '최종 유효 매입 상태' 정의 초안

### 4.1 용어

- **원승인 매입 사실 묶음**: 한 원승인 거래(transaction_id)의 SALE 레코드 전부(세대 0..n), 각 레코드의 결과 상태(§2.1), 그 레코드가 든 파일의 전송 상태, 매입 확정 표지(`acquisition_accepted_record`, 거래·SALE당 최대 하나).
- **최종 유효 매입 상태(Effective Acquisition State, EAS)**: 위 묶음에서 계산하는 원승인 수준의 값. 저장하지 않고 판정 때마다 잠금 아래에서 계산한다(append-only 사실에서 파생 — 이력 불변).

| EAS 값 | 뜻 | 취소 판정 |
|---|---|---|
| `ACQUIRED(g)` | 세대 g 레코드에 매입 확정 표지가 있다(세대 무관, 하나뿐 — D-ACQ-014) | CANCEL 레코드(정합·누적 한도는 **세대 g 레코드**의 상계액으로 검사) |
| `ACQUIRED_DISPUTED(g)` | 표지는 세대 g에 있지만 다른 세대가 CONFLICT이거나, 다른 세대가 전송됨·결과 대기·UNKNOWN이라 매입사가 두 번 매입했을 수 있다 | **사용자 결정 필요(Q-ARC-2)** — 권장: CANCEL 레코드 + 대사 경보 |
| `NOT_ACQUIRED(g)` | 표지 없음, 어느 세대도 CONFLICT 아님, 최고 세대 g가 REJECTED·FILE_REJECTED로 종결, 그 위 진행 중 세대 없음 | '취소 매입 불요'(세대 g 레코드를 가리킴). 뒤에 재매입하면 D-ACQ-013 순액이 이 취소를 뺀다 |
| `IN_PROGRESS(g)` | 표지 없음, CONFLICT 없음, 최고 세대 g가 결과 대기이고 그 파일이 정상 전송 경로(SEALED·TRANSMITTING·TRANSMITTED·TRANSMISSION_UNKNOWN)에 있다 | 자동 대기(기록 없음) |
| `UNRESOLVED` | 표지 없음이면서: 어느 세대든 CONFLICT, 최고 세대 UNKNOWN, 최고 세대 파일이 TRANSMISSION_WITHHELD·TRANSMISSION_QUARANTINED(결과가 올 정상 경로 없음), 또는 표지 없는 ACCEPTED 상태(무결성 위반) | 대사·경보(기록 없음, 다음 봉인에 다시 판정) |

### 4.2 판정 규칙(순서대로 — 앞이 이긴다)

1. **거래 단위 대기 먼저**(현행 유지 — I-47): LIVE·마감 뒤·취소 보류·Payment 보류·KRW 외·원거래 값 불일치·표현 불가 → 그 취소만 대기. 근거: D-ACQ-009 + 리뷰 NIT-3, fail-closed.
2. **표지가 있으면 그 세대가 유효하다**: 표지 세대 g → `ACQUIRED(g)`(다른 세대 상태와 무관하게 g가 매입 확정). 근거: BR-FIN-037(매입 확정은 기관 ACCEPTED뿐), BR-FIN-038·D-ACQ-014(세대를 넘는 ACCEPTED 하나). 이어서 다른 세대에 CONFLICT·진행 중·UNKNOWN이 있으면 `ACQUIRED_DISPUTED(g)`.
3. **표지가 없고 어느 세대든 CONFLICT면 `UNRESOLVED`**: CONFLICT 행은 매입사가 그 레코드에 ACCEPTED를 보냈을 수 있다는 뜻이라, 더 높은 세대가 REJECTED여도 "매입 안 됨"으로 단정하지 않는다. 근거: CLAUDE.md "timeout/조회 NOT_FOUND/lease 만료만으로 금융효과가 없다고 단정하지 않는다", D-ACQ-010(상충 = 격리).
4. **표지·CONFLICT가 없으면 최고 세대가 정한다**: REJECTED·FILE_REJECTED → `NOT_ACQUIRED`, 결과 대기(정상 전송 경로) → `IN_PROGRESS`, UNKNOWN 또는 결과가 올 경로가 없는 파일(보류·격리) → `UNRESOLVED`. 근거: D-ACQ-004(종결 거절은 그 파일에서 종결, UNKNOWN → 대사), D-ACQ-009.
5. **이미 판정 끝난 취소는 자동으로 다시 판정하지 않는다**(현행 유지): '취소 매입 불요'로 종결된 취소는 뒤에 재매입이 ACCEPTED되어도 CANCEL로 보내지 않는다. 재매입 순액이 그 취소를 이미 뺐기 때문이다(D-ACQ-013 확정, CANCELLATION_UNDECIDED 거절, I-17 무결성 대조). 즉 "최종 유효"는 **판정 시점의 유효 상태**이다. 취소 금액은 "CANCEL 레코드"나 "다음 세대 순액 차감" 중 정확히 한 곳에만 반영된다.
   - **전제**: '불요'의 근거 세대 g보다 **높은** 세대가 나중에 생기고, 그 세대 순액이 이 취소를 뺀다. 이 전제는 g가 최종 유효 세대이거나, g 뒤에 재매입이 실행될 때만 성립한다.
   - **예외 5-a(0.1a — 리뷰 m-7)**: '불요'의 근거 세대 g가 최종 유효 세대가 아닌 경우가 있다. 더 **낮은** 세대에 표지가 있거나(§5.2 #17), 다른 세대가 CONFLICT인데 g를 근거로 종결된 경우다(#14). 이때는 더 높은 세대 재매입이 없어서(#17은 표지가 있어 재매입 자체가 거절됨 — `ReacquisitionRule.kt:28`) 순액 차감도 CANCEL도 생기지 않는다. 취소가 **영구 누락**된다. 이 경우:
     - 자동 재판정을 하지 않는다. 판정 끝난 취소를 봉인이 다시 CANCEL로 넣는 자동 Recovery를 두지 않는다. '불요' 기록은 append-only라 지우거나 고치지 않는다.
     - 그 원승인을 **대사·Adjustment 경로**로 보낸다. ① 탐지: 그 '불요'를 "근거 세대 ≠ 유효 세대"로 표시하고 경보를 낸다. ② 위험 건 Hold: 그 원승인·Payment의 정산 지급 대상 금액을 보류한다(D-GOV-001 ④ 방향). ③ 정정: 대사 결과로 확인된 뒤 2인 승인 Adjustment로 append-only 정정 사실을 남긴다(예: '불요 무효' 기록 + 정정 CANCEL 레코드, 또는 매입사와 수기 정산 기록). ④ MATCH 확인 전에는 해결로 처리하지 않는다(CLAUDE.md "보정 후 MATCH 확인 전 해결 금지").
     - 무엇으로 정정할지(정정 CANCEL을 매입 파일로 보낼지, 수기 정산만 할지), Hold 범위, 승인 주체는 **사용자 결정 필요(Q-ARC-3)**. 대사(RECON)·Adjustment 기능은 아직 없다. 그래서 지금 할 수 있는 것은 탐지(ARC-20)와 경보뿐이다.
   - 새 판정에서는 예외 5-a 상태가 생기지 않는다. 규칙 2~4를 따르면 #14는 대사, #17은 CANCEL로 판정되어 처음부터 '불요'로 종결되지 않는다. 예외 5-a는 **규칙 변경 전에 이미 쓰인 '불요'**와, 판정 뒤에 앞 세대 상태가 바뀐 경우(늦은 상충·늦은 표지)를 위한 것이다.

### 4.3 결정이 필요한 지점

- **(Q-ARC-1)** 위 규칙 2~4를 BR-FIN-040의 새 문장으로 채택할지. 채택하면 CC-4 → CC-8 기대값 이동도 이 답으로 사후 승인된다(§8).
- **(Q-ARC-2)** `ACQUIRED_DISPUTED`에서 CANCEL을 보낼지 보류할지(§8).
- **(Q-ARC-3)** 예외 5-a의 정정 방식, 그리고 재매입 승인이 EAS UNRESOLVED(앞 세대 CONFLICT)를 거절할지(m-8, §5.3)(§8).
- 결정 불요(저장소 관례로 정할 가역 세부):
  - EAS를 계산하는 Domain 규칙 이름·위치(예: `EffectiveAcquisitionRule`), `saleMemberships`가 세대 전부와 표지를 돌려주게 하는 Port 모양, 대사 경보 지표 이름. 정책 승인 뒤 구현 명세에서 정한다.
  - **CC-4 → CC-8 사후 승인 기록 방식(0.1a — 리뷰 m-9)**: Q-ARC-1 답이 나오면 Coordinator가 정한다. 예: DECISIONS 새 행에 "CC-8 기대값 = Q-ARC-1 답으로 승인, 범위 = 다른 세대에 표지·상충 없음"으로 적는다. BR-FIN-040 근거 칸을 `적용(L-6)`에서 그 결정 ID로 바꾼다. CC-8 제목·주석 문구를 좁히는 것은 기대값 불변 정리로 처리한다. 기대값의 금융 의미는 Q-ARC-1이 정하고, 어떤 파일·문구로 남길지는 가역 세부다.

## 5. 상태 전이표

### 5.1 레코드(세대) 수준 — 현행 유지(차이 없음)

| 레코드 상태 \ 이벤트 | 전송 완료 | ACCEPTED 수신 | REJECTED 수신 | ACK 파일 거절 | 결과 마감(미수신) | 다른 세대 표지 있음 + ACCEPTED 수신 |
|---|---|---|---|---|---|---|
| AWAITING | AWAITING(전송 축만 변함) | ACCEPTED + 표지 | REJECTED | FILE_REJECTED | UNKNOWN(TRANSMITTED 파일만) | CONFLICT(관찰 ACCEPTED) |
| UNKNOWN | — | ACCEPTED + 표지(늦은 결과) | REJECTED | FILE_REJECTED | 변화 없음 | CONFLICT |
| ACCEPTED | — | 중복(행 없음) | CONFLICT | CONFLICT | 변화 없음 | — |
| REJECTED·FILE_REJECTED | — | CONFLICT | 같은 사유면 중복, 다르면 CONFLICT | 같으면 중복 | 변화 없음 | CONFLICT |
| CONFLICT | — | 행 없음(격리 유지) | 행 없음 | 행 없음 | 변화 없음 | 행 없음 |

근거: `RecordResultRule.kt:35-66`. 재매입 승인·실행은 앞 세대 레코드 상태를 바꾸지 않고 새 세대 레코드(AWAITING)를 만든다.

### 5.2 원승인 수준 — 취소 도착 시 판정(현재 코드 vs 제안)

표기: g0 = 세대 0, g1 = 세대 1. "표지"는 매입 확정 표지. **≠** = 현재 코드와 제안 정책의 판정이 다른 칸. 권장안 기준(Q-ARC-2 = CANCEL + 경보).

| # | 원승인 매입 사실 묶음(취소 도착 시점) | 현재 코드(최고 세대 하나) | 제안 EAS → 판정 | 차이 | 지금 도달 가능? | 기존 시험 |
|---|---|---|---|---|---|---|
| 1 | g0 AWAITING | 대기 | IN_PROGRESS → 대기 | = | 예 | CC-2, AQ-X1 |
| 2 | g0 ACCEPTED(표지) | CANCEL | ACQUIRED(0) → CANCEL | = | 예 | CC-1, AQ-X1 |
| 3 | g0 REJECTED·FILE_REJECTED, 재매입 없음 | 불요 | NOT_ACQUIRED(0) → 불요 | = | 예 | CC-2, AQ-X1 |
| 4 | g0 UNKNOWN | 대사 | UNRESOLVED → 대사 | = | 예 | CC-2, AQ-X1 |
| 5 | g0 CONFLICT, 재매입 없음 | 대사 | UNRESOLVED → 대사 | = | 예 | CC-2 |
| 6 | g0 REJECTED, 재매입 승인만 있음(미실행) | 불요(g0) | NOT_ACQUIRED(0) → 불요, 실행 때 순액 차감 | = | 예 | RX-1·RX-13 |
| 7 | g0 REJECTED, g1 결과 대기(정상 전송 경로) | 대기 | IN_PROGRESS(1) → 대기 | = | 예 | RX-7 |
| 8 | g0 REJECTED, g1 ACCEPTED(표지 g1) | CANCEL | ACQUIRED(1) → CANCEL | = | 예 | CC-8, RR-1 |
| 9 | g0 REJECTED, g1 REJECTED | 불요(g1) | NOT_ACQUIRED(1) → 불요(g1) | = | 예 | CC-8, RR-2 |
| 10 | g0 REJECTED, g1 UNKNOWN | 대사 | UNRESOLVED → 대사 | = | 예 | CC-8(도메인만) |
| 11 | g0 REJECTED, g1 CONFLICT, 표지 없음 | 대사 | UNRESOLVED → 대사 | = | 예 | 없음 |
| 12 | g0 CONFLICT(앞 세대 상충 ACCEPTED), g1 ACCEPTED(표지 g1) | CANCEL | ACQUIRED_DISPUTED(1) → CANCEL + 이중 매입 대사 경보 | = (경보만 추가) | 예 | AQ-R14(결과만, 취소 없음) |
| 13 | g0 CONFLICT, g1 결과 대기, 표지 없음 | 대기 | UNRESOLVED → 대사 | **≠** (금액 효과 없음 — 둘 다 안 보냄) | 예 | 없음 |
| 14 | g0 CONFLICT, g1 REJECTED, 표지 없음 | **불요(g1) — 종결** | UNRESOLVED → 대사 | **≠ (취소 누락 위험)** | **예** | 없음 |
| 15 | g0 ACCEPTED(표지 g0, UNKNOWN에서 늦게), g1 파일 TRANSMISSION_WITHHELD(레코드 AWAITING) | 대기(무기한 — m-3) | ACQUIRED(0) → CANCEL | **≠ (취소 무기한 미전송)** | 아니오(UNKNOWN 재매입 개방 뒤) | RR-3(보류만, 취소 없음) |
| 16 | g0 ACCEPTED(표지 g0), g1 전송됨·결과 대기 | 대기 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음) | 없음 |
| 17 | g0 ACCEPTED(표지 g0), g1 REJECTED(중복 재매입 거절) | **불요(g1) — 종결** | ACQUIRED(0) → CANCEL | **≠ (취소 누락 — 확정)** | 아니오(같음) | 없음 |
| 18 | g0 ACCEPTED(표지 g0), g1 CONFLICT(관찰 ACCEPTED — 이중 매입 의심) | 대사 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음, m-4 경로는 예) | 없음 |
| 19 | g0 ACCEPTED(표지 g0), g1 UNKNOWN | 대사 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음) | 없음 |
| 20 | g0 REJECTED, g1·g2… 반복 거절 뒤 최고 세대 gn 결과 대기 | 대기 | IN_PROGRESS(n) → 대기 | = | 예 | RR-2(g2 실행까지) |
| 21 | g0 REJECTED, g1 파일 TRANSMISSION_QUARANTINED 또는 TRANSMISSION_WITHHELD(표지 없음) | 대기(무기한) | UNRESOLVED → 대사 | **≠** (금액 효과 없음) | 예(격리) | 없음 |

**다른 칸 수: 8칸**(#13·14·15·16·17·18·19·21. 0.1a: §5.3 재매입 승인 1행을 더하면 모두 9칸). 이 중 **금액 효과가 있는 칸 3개**: #14(지금 도달 가능 — 매입사가 앞 세대에 ACCEPTED를 보냈을 수 있는데 취소를 '불요'로 종결해 취소 매입 누락 가능), #17(취소 매입 누락 — UNKNOWN 재매입 개방 뒤), #15(취소가 매입사에 무기한 가지 않음). #16·18·19는 Q-ARC-2 답에 따라 같음/다름이 바뀐다(대안 '보류'를 고르면 #16은 대기→대사로 금액 효과 없는 차이, #18·19는 같음). #13·21은 보내지 않는다는 점은 같고 대기 대신 대사 경보로 드러내는 차이다.

### 5.3 원승인 수준 — 그 밖 이벤트(0.1a: 재매입 승인 1행이 다름 — 나머지는 현재 코드 = 제안)

| 이벤트 | 현재 동작 | 제안 | 근거 |
|---|---|---|---|
| 재매입 승인 요청(표지 있음) | 거절 ALREADY_ACCEPTED | 같음 | `ReacquisitionRule.kt:28`, RQ-7, RX-2 |
| **(0.1a — 리뷰 m-8) 재매입 승인 요청: g0 CONFLICT(표지 없음 — 매입사가 상충 ACCEPTED를 보냄) + 최고 세대 g1 REJECTED** | **g2 승인·실행 가능.** 대상 검사는 최고 세대 g1의 상태(REJECTED)와 `acceptedInAnyGeneration`(표지 유무)만 본다(`ReacquisitionRule.kt:28-36`). 실행 재검사도 대상 세대·상태·표지만 본다(`:54-64`). g0의 CONFLICT는 보지 않는다 | **≠ 사용자 결정 필요(Q-ARC-3 ②)**. 권장: EAS가 UNRESOLVED(어느 세대든 CONFLICT)면 재매입 거절(새 거절 사유 — 예 `ORIGINAL_UNRESOLVED`) → 대사 확인 뒤에만 재매입 | 이중 매입 경로: 매입사가 g0를 실제로 매입했다면 g2가 두 번째 매입 요청이 된다. 표지 PK(V14)는 g2 ACCEPTED를 결정으로 인정하지만(g0에 표지가 없으므로), 매입사 쪽 이중 매입은 막지 못한다. 기존 시험 없음. 지금 도달 가능(§2.2) |
| 재매입 승인 요청(최고 세대 UNKNOWN) | 거절 UNKNOWN_NOT_RECONCILED | 같음(대사 기능 전) | `ReacquisitionRule.kt:33` |
| 재매입 실행 직전 앞 세대 상태 변화 | TARGET_CHANGED | 같음 | `ReacquisitionRule.kt:54-64`, RQ-8 |
| 재매입 실행 시 미판정 취소 있음 | CANCELLATION_UNDECIDED 거절 | 같음 | `ReacquisitionExecutionRule.kt:53`, RX-4 |
| 재매입 실행 시 전액 취소됨(순액 0) | '재매입 불요' 종결, 파일 없음 | 같음 | `ReacquisitionExecutionRule.kt:63-64`, RX-3 |
| 봉인한 재매입 파일 전송 전 다른 세대 표지 발생 | TRANSMISSION_WITHHELD(종결) | 같음. 단 그 뒤 취소는 #15처럼 표지 세대로 판정 | `AcquisitionTransmissionService.kt:167-182`, RR-3·RR-4 |
| CANCEL 레코드 REJECTED | 재매입 경로 없음(CANCEL_NOT_SUPPORTED) | 같음(ACQ-ADMIN-002, BR-FIN-050 — 이 문서 범위 밖) | `ReacquisitionExecutionRule.kt:50` |

## 6. 사용자가 든 6개 경우별 분석

### ① 최초 매입 실패 → 재매입 성공
- **현재**: g0 REJECTED 동안 받은 취소 → '불요'(g0)로 종결(#3·6), 재매입 순액이 그 취소를 뺀다(RX-1·RX-13). 실행 뒤 받은 취소 → g1 결과 대기(#7, RX-7), g1 ACCEPTED 뒤 → CANCEL(#8, RR-1). I-17 무결성 대조가 "취소가 순액과 CANCEL 양쪽에 들어가지 않음"을 지킨다(RR-1 세 번째 Then).
- **제안**: 같다.
- **차이·위험**: 없음. 이중 취소 매입 위험은 '불요' 기록 + 순액 차감 + 판정 끝남 재확인으로 막혀 있다.

### ② 최초 매입 성공 → 중복 재매입 실패
- **현재**: g0 표지가 있으면 재매입 승인·실행이 거절된다(RQ-7, RX-2) — 정상 경로로는 중복 재매입이 생기지 않는다. 그러나 g0가 UNKNOWN에서 늦게 ACCEPTED된 경우(대사로 UNKNOWN 재매입이 열린 뒤) 세대 1이 이미 있을 수 있고, 그때 취소는 **최고 세대 g1** 결과로 판정된다: g1이 보류면 무기한 대기(#15), 전송됐고 매입사가 중복으로 거절하면 '불요' 종결(#17).
- **제안**: 표지 세대 g0가 유효 → CANCEL(#15·17), g1이 아직 진행 중·UNKNOWN·CONFLICT면 CANCEL + 대사 경보(#16·18·19, Q-ARC-2).
- **차이·위험**: #17은 **취소 매입 누락**(매입사는 원승인을 매입했고 고객 취소는 매입사에 전달되지 않음 → 매입사 입금 과다·가맹점 지급 과다 가능). 지금은 UNKNOWN 재매입이 막혀 도달하지 않지만, 대사(RECON)가 열리는 순간 생긴다. 기존 시험 없음.

### ③ 반복 재매입·결과 미수신
- **현재**: g1 REJECTED → g2 실행(RR-2), 최고 세대 결과 대기면 대기(#20). 전송된 파일이 마감까지 결과가 없으면 UNKNOWN → 취소는 대사(#10, 봉인마다 다시 판정 — 늦은 ACCEPTED면 다음 봉인이 CANCEL). UNKNOWN 재매입은 거절. 전송 결과 모름(TRANSMISSION_UNKNOWN)은 확인 우선 재업로드로 수렴, 그동안 대기. 전송 격리·보류 파일은 마감 처리 대상이 아니라 레코드가 AWAITING에 머물고 취소가 무기한 대기(#21).
- **제안**: #21만 다르다 — 결과가 올 정상 경로가 없는 파일은 UNRESOLVED(대사 경보)로 드러낸다.
- **차이·위험**: 금액 효과 없음(둘 다 보내지 않음). 무기한 대기가 지표에 '대기'로만 보여 대사 대상에서 빠질 위험. 기존 시험 없음.

### ④ 재매입 처리 중 전체·부분취소
- **현재**(시점별):
  - (a) g0 종결 거절 뒤·승인 전: 다음 봉인이 '불요'(g0)로 종결 → 실행 순액에서 차감(RX-1).
  - (b) 승인 뒤·실행 전: 봉인이 먼저 돌면 (a)와 같고, 아니면 실행이 CANCELLATION_UNDECIDED로 승인 전체 거절(RX-4) → 다시 요청. 승인 기록에는 금액이 없어(I-54) 승인 시점 금액이 낡지 않는다.
  - (c) 실행 전 전액 취소: 순액 0 → '재매입 불요'(RX-3).
  - (d) 실행 뒤 g1 결과 대기: 대기(RX-7) → g1 ACCEPTED면 CANCEL(RR-1, 부분취소만 시험), g1 REJECTED면 '불요'(g1) → g2 순액에서 차감(RR-2).
  - 누적 한도: g1 상계액 + 봉인 CANCEL + 이 취소 ≤ 원승인액(CC-8 두 번째 Then).
- **제안**: 같다. 단 (d)에서 g0가 CONFLICT가 되면 #13·14 규칙(대사)으로 바뀐다.
- **차이·위험**: (d)에서 g1 ACCEPTED 뒤 **전액**(남은 금액 전부) 취소, 여러 번 부분취소의 누적, (b)의 봉인·실행 동시 경합에서 같은 취소가 '불요'와 순액에 두 번 들어가지 않는지는 DB 시험이 부분적이다(RX-12는 잠금 순서만). 새 시험 필요(§7 T-09·T-10·T-11).

### ⑤ 취소 매입 중복 전송 방지
- **현재**: §2.5 장치. CANCEL은 언제나 세대 0이라 UNIQUE(거래, CANCEL, 0)가 취소 하나당 CANCEL 레코드 하나를 DB에서 보장한다. 상계·불요·CANCEL 세 경로 사이의 배타성은 DB 제약이 아니라 lane 잠금 + 결제별 행 잠금 + 잠금 아래 재확인이 지킨다. CANCEL 레코드 파일의 재전송은 확인 우선(hash MATCH면 재업로드 없음). CANCEL REJECTED의 재매입은 미지원이라 두 번 보낼 경로가 없다.
- **제안**: 같다. EAS를 바꿔도 "판정 끝난 취소는 다시 판정하지 않는다"(규칙 5)가 유지되므로 #15·17에서 CANCEL을 새로 보내도 같은 취소의 두 번째 CANCEL은 생기지 않는다.
- **차이·위험**: 없음. 세 경로 배타성의 동시 봉인 경합 DB 시험이 있으면 좋다(§7 T-12, 기존 RX-12·AQ 봉인 경합 시험과 대응 확인 필요).

### ⑥ 매입사 응답 결과와 내부 매입 상태 정합
- **현재**: 결과 행 append-only, 중복/상충 구분, 세대를 넘는 ACCEPTED 하나(앱 규칙 + V14 PK), 파일 거절 종결, 늦은 결과 자동 반영. 원 SALE이 CONFLICT가 되면 그 레코드를 가리키는 '불요'가 있을 때만 경보한다(`AcquirerResponseService.kt:264-268`). 원승인 **수준**의 유효 상태는 어디에도 계산되지 않고, 취소 판정은 최고 세대 레코드 하나만 본다.
- **제안**: EAS를 원승인 수준의 유일한 판정 입력으로 둔다. CONFLICT 경보를 "그 원승인의 다른 세대 레코드를 가리키는 '불요'·CANCEL"까지 넓힌다(#14가 지금 경보 없이 종결되는 이유).
- **차이·위험**: #14. 매입사가 g0에 상충 ACCEPTED를 보낸 뒤 g1이 거절되면, 지금은 취소가 '불요'(g1)로 경보 없이 종결된다. 매입사 쪽에서는 g0가 매입됐을 수 있다. 대사(RECON) 기능이 없어서 이 불일치를 맞출 경로가 아직 없다. 같은 상태(g0 CONFLICT + g1 REJECTED)에서 g2 재매입도 승인될 수 있어(§5.3 m-8) **이중 매입**으로 번질 수 있다. 이미 쓰인 '불요'는 예외 5-a(§4.2)와 ARC-20으로 탐지한다.

## 7. 시험 시나리오

ID 접두 `ARC-`(이 문서용 임시 ID). "기존 대응"은 같은 사실을 이미 단언하는 시험이다. 기존 시험의 기대값을 바꿔야 하는 경우는 **승인 후 변경 후보**로만 적는다. 지금 바꾸지 않는다.

| ID | Given | When | Then | 기존 대응 | 구분 |
|---|---|---|---|---|---|
| ARC-01 | g0 ACCEPTED(표지 g0) | 취소 3,000 봉인 | CANCEL 3,000(세대 0, 원승인액) | CC-1, AQ-X1 | 기존 유지 |
| ARC-02 | g0 REJECTED, 재매입 없음 | 취소 봉인 | '불요'(g0) | CC-2, AQ-X1 | 기존 유지 |
| ARC-03 | g0 REJECTED → 취소 3,000 '불요' → g1 실행(7,000) → g1 ACCEPTED | 취소 2,000 봉인 | CANCEL 2,000, g1 상계액 무결성 0 | RR-1 | 기존 유지 |
| ARC-04 | g0 REJECTED → g1 REJECTED | 취소 봉인 → g2 실행 | '불요'(g1), g2 = 10,000 − 3,000 − 2,000 | RR-2 | 기존 유지 |
| ARC-05 | g0 REJECTED → g1 실행(결과 대기) | 취소 봉인 | 대기 ORIGINAL_AWAITING_RESULT, 불요 0·CANCEL 0 | RX-7 | 기존 유지 |
| ARC-06 | g0 CONFLICT(REJECTED 뒤 상충 ACCEPTED), g1 REJECTED, 표지 없음 | 취소 봉인 | 대사(기록 없음), 불요 0, 경보 +1 — **#14** | 없음 | **새로 필요**(지금 도달 가능 — 우선) |
| ARC-07 | g0 CONFLICT, g1 결과 대기 | 취소 봉인 | 대사(대기 아님) — **#13** | 없음 | 새로 필요 |
| ARC-08 | g0 UNKNOWN → (대사 미매입 확정 근거 Fixture) g1 봉인 → g0 늦은 ACCEPTED → g1 보류 | 취소 봉인 | CANCEL(표지 g0 기준, g0 상계액으로 누적 한도) — **#15** | RR-3(보류만) | 새로 필요(명시 상태 Fixture — L-1) |
| ARC-09 | g0 표지 + g1 REJECTED(중복 재매입 거절) | 취소 봉인 | CANCEL — **#17** | 없음 | 새로 필요(명시 상태 Fixture) |
| ARC-10 | g0 표지 + g1 전송됨·결과 대기 / g1 UNKNOWN / g1 CONFLICT(관찰 ACCEPTED) | 취소 봉인 | Q-ARC-2 권장: CANCEL + 이중 매입 경보 / 대안: 대사 — **#16·18·19** | 없음 | 새로 필요(Q-ARC-2 답 뒤 기대값 확정) |
| ARC-11 | g1 ACCEPTED(표지 g1) + g0 CONFLICT(AQ-R14 상태) | 취소 봉인 | CANCEL + 경보 — **#12** | AQ-R14(결과만) | 새로 필요(AQ-R14 확장) |
| ARC-12 | g0 REJECTED, g1 파일 TRANSMISSION_QUARANTINED(또는 표지 없는 WITHHELD) | 결과 마감 지남 → 취소 봉인 | 대사(무기한 대기 아님) — **#21** | 없음 | 새로 필요 |
| ARC-13 | g1 ACCEPTED, 실행 전 상계 3,000 | 남은 7,000 전액 취소 봉인 | CANCEL 7,000(경계), 7,001이면 INCONSISTENT | CC-8 두 번째 Then(도메인만) | DB 시험 새로 필요(④) |
| ARC-14 | g1 ACCEPTED | 부분취소 2,000 → 3,000 → 2,000(누적 7,000) 세 봉인 | CANCEL 세 개, 네 번째 1원은 INCONSISTENT | 없음 | 새로 필요(④) |
| ARC-15 | 재매입 승인 기록됨·실행 전 | 같은 lane 정기 봉인과 실행을 실제 두 연결로 경합(취소 하나 미판정) | 취소는 '불요' 또는 CANCELLATION_UNDECIDED 거절 중 하나, 순액에 두 번 들어가지 않음(I-17 0) | RX-12(잠금 순서) | 확장 필요(④·⑤) |
| ARC-16 | 같은 취소 | 두 봉인 TX 동시(서로 다른 업무일 lane 재시도 포함) | CANCEL 레코드·불요·상계 중 정확히 하나 | 봉인 경합 시험(ACQUIRING-001 AQ 계열 — 대응 확인 필요) | 대응 확인 후 필요 시 새로(⑤) |
| ARC-17 | CANCEL 레코드 파일 TRANSMISSION_UNKNOWN | 재전송 | 최종명 hash MATCH면 재업로드 없음, CANCEL 레코드 수 불변 | AQ-T 계열(SALE 파일) | CANCEL 포함 파일로 확인(⑤) |
| ARC-18 | g0 표지 있음 | 재매입 요청 | ALREADY_ACCEPTED | RQ-7, RX-2 | 기존 유지(②) |
| ARC-19 | EAS 규칙 표(§5.2 21행) | 도메인 규칙 표 시험 | 행마다 EAS 값·판정 | 없음 | 새로 필요(Domain 규칙 — 명시 Fixture, 연관 상태 random 금지) |
| ARC-20 | (0.1a — m-7) 규칙 변경 전에 이미 쓰인 '불요' 중 근거 세대 ≠ 유효 세대인 것 | 탐지 조회(아래 SQL — 읽기 전용) | 해당 원승인 목록 → 대사·Hold 후보(자동 재판정·수정 없음) | 없음 | 새로 필요. 구현 뒤에는 탐지 Reader + DB 시험(명시 상태 Fixture로 #14·#17 '불요'를 만들어 탐지 1건, 정상 재매입 성공(#3 → #8)은 0건) |
| ARC-21 | (0.1a — m-8) g0 CONFLICT(표지 없음) + g1 REJECTED | g2 재매입 승인·실행 요청 | Q-ARC-3 ② 권장: 거절(`ORIGINAL_UNRESOLVED` — 이름 가역), 결정 기록만, 봉인 없음 / 현재 코드: 승인·봉인됨 | RQ-5·RQ-8(대상 세대 변화만) | 새로 필요(ReacquisitionRuleSpec 표 + ReacquisitionExecutionDbSpec 1건). 기존 RQ·RX 기대값 변경 없음 |

**ARC-20 탐지 조회(초안 — 이번에 실행하지 않음)**. Demo·TEST FINANCIAL DB에서 **조회 전용 계정으로 SELECT만** 한다. 운영 DB에는 쓰지 않는다. 접속은 Demo 실행 문서의 FINANCIAL MySQL 접속 방법을 따르고, 결과는 거래 ID·파일 ID·순번·금액만 본다(카드 정보 열 없음). 표·열 이름: V5 `acquisition_file_record`, V10 `acquisition_record_result`·`acquisition_cancel_not_required`, V14 `acquisition_accepted_record`.

```sql
-- (A) #17형: '불요'의 근거 레코드보다 낮은 세대에 매입 확정 표지가 있다(근거 세대 ≠ 유효 세대, 뒤 재매입 없음 → 영구 누락 후보).
SELECT n.cancellation_transaction_id, n.original_transaction_id, n.original_file_id, n.original_seq, r.generation AS basis_generation,
       a.file_id AS accepted_file_id, a.seq AS accepted_seq, a.generation AS accepted_generation, n.amount_minor, n.decided_at
FROM acquisition_cancel_not_required n
JOIN acquisition_file_record r ON r.file_id = n.original_file_id AND r.seq = n.original_seq
JOIN acquisition_accepted_record a ON a.transaction_id = n.original_transaction_id AND a.record_type = 'SALE'
WHERE a.generation < r.generation;

-- (B) #14형: 표지가 없고, 같은 원승인의 다른 SALE 세대에 CONFLICT 결과 행이 있는데 '불요'가 그 세대가 아닌 레코드를 근거로 종결됐다.
SELECT n.cancellation_transaction_id, n.original_transaction_id, n.original_file_id, n.original_seq, r.generation AS basis_generation,
       r2.file_id AS conflict_file_id, r2.seq AS conflict_seq, r2.generation AS conflict_generation, n.amount_minor, n.decided_at
FROM acquisition_cancel_not_required n
JOIN acquisition_file_record r ON r.file_id = n.original_file_id AND r.seq = n.original_seq
JOIN acquisition_file_record r2 ON r2.transaction_id = n.original_transaction_id AND r2.record_type = 'SALE'
     AND NOT (r2.file_id = r.file_id AND r2.seq = r.seq)
WHERE EXISTS (SELECT 1 FROM acquisition_record_result x WHERE x.file_id = r2.file_id AND x.seq = r2.seq AND x.result = 'CONFLICT')
  AND NOT EXISTS (SELECT 1 FROM acquisition_accepted_record a WHERE a.transaction_id = n.original_transaction_id AND a.record_type = 'SALE');
```

- 근거 레코드 **자신**이 CONFLICT가 된 경우는 지금도 응답 TX가 경보를 낸다(`AcquirerResponseService.kt:264-268`). 그래서 (B)는 **다른** 세대만 본다.
- 낮은 세대 '불요' + 높은 세대 표지는 정상 재매입 성공이다(①, 순액 차감 — I-17이 대조). (A)는 `a.generation < r.generation`으로 이 경우를 뺀다.
- 열 이름은 V10 표 정의로 확인했다(`acquisition_cancel_not_required`: cancellation_transaction_id·original_transaction_id·original_file_id·original_seq·amount_minor·currency·business_date·decided_at — `V10__acquisition_response.sql:79-87`).
- 결과가 0건이어도 "누락 없음"의 증거가 아니다. 결과를 아직 받지 않은 세대가 나중에 상충할 수 있다. 정기 탐지(대사 입력)로 둘지는 Q-ARC-3에서 함께 정한다.

**기대값 변경이 필요한 기존 시험(승인 후 변경 후보)**: **없을 것으로 본다.** 기존 시험이 단언하는 칸(#1~12·20)은 모두 제안과 같다. 다만 구현에서 `CancelCandidateRule.decide`의 입력을 `OriginalSaleResult`(레코드 하나)에서 EAS로 바꾸면 **CC-2·CC-8의 호출 형태**가 바뀐다(기대값 불변, 입력 Fixture만 바뀜) — 이것도 승인 후 변경 후보로 둔다. RX-7 대기 사유는 그대로(#7). 승인 전에는 어느 것도 바꾸지 않는다.

## 8. 사용자 결정 필요 질문

**Q-ARC-1 BR-FIN-040 새 기준 — '최종 유효 매입 상태'(EAS) 채택 + CC-4 → CC-8 사후 승인**
- 권장안: **채택**.
  - 판정 순서: 표지(세대 무관) 우선 → 표지 없고 어느 세대든 CONFLICT면 대사 → 둘 다 없으면 최고 세대 결과(§4.2).
  - 이미 판정 끝난 취소는 자동으로 다시 판정하지 않는다(D-ACQ-013 유지, 예외 5-a는 Q-ARC-3).
  - 이 답으로 CC-8 기대값(세대 ≥ 1 SALE 결과를 세대 0처럼 판정)을 "다른 세대에 표지·상충이 없을 때"에 한해 사후 승인한다. 기존 기대값은 바꾸지 않는다. 기록 방식은 Coordinator가 정할 가역 세부다(§4.3).
- 대안: (b) 현행 '최고 세대 기준'을 유지하고 m-3(보류 중 무기한 대기)만 고친다. CC-8은 그대로 사후 승인한다. (c) 재매입이 한 번이라도 있으면 그 뒤 취소는 자동 판정하지 않고 운영자가 검토한다(rev 0.6 동작 — CC-8·RR-1·RR-2·RX-7 기대값을 되돌려야 함).
- 영향:
  - 권장안은 §5.2 다른 칸 8개를 고친다. 그중 금액에 영향이 있는 것은 #14(지금 도달 가능한 취소 누락)와 #17·#15(대사 개방 뒤 취소 누락·무기한 미전송)다.
  - (b)는 #14·17이 남는다.
  - (c)는 #8·9 정상 재매입의 취소도 수동이 되어 지급이 늦어진다.
- 막히는 작업: BR-FIN-040 확정(RULE-CONFIRM-001 R1-Q2·PC-66·NP-21 종결), ACQ-ADMIN-002의 '확정 세대 우선' 코드 수정(m-3), 대사(RECON)로 UNKNOWN 재매입을 여는 작업(#15~19가 그때 열림).

**Q-ARC-2 표지가 있는데 다른 세대가 미결·상충일 때(ACQUIRED_DISPUTED) 취소 매입**
- 권장안: **CANCEL 레코드를 보내고 이중 매입 대사 경보를 남긴다** — 표지 세대의 매입은 확정 사실(BR-FIN-037·038)이라 그 취소를 매입사에 보내지 않으면 취소 누락이 확실히 생긴다. 이중 매입 여부는 대사로 따로 맞춘다.
- 대안: (b) 그 취소를 대사로 보류하고 다른 세대가 결정될 때까지 보내지 않음 (c) 다른 세대가 CONFLICT일 때만 보류하고 진행 중·UNKNOWN이면 보냄.
- 영향: 권장안은 매입사가 실제로 두 번 매입했다면 취소 한 건으로 다 상쇄되지 않는다(대사·보정 필요 — 자동 Recovery 아님). (b)는 취소 반영이 늦어져 매입사 입금·가맹점 지급이 취소액만큼 과다해질 수 있다.
- 막히는 작업: §5.2 #12·16·18·19의 기대값, ARC-10·11.

**Q-ARC-3 유효 세대가 흔들린 원승인의 처리 — ① 이미 쓰인 '불요'(예외 5-a) ② 재매입 승인(m-8)**
- 권장안:
  - **①** 근거 세대 ≠ 유효 세대인 '불요'(#14·17형)는 자동으로 다시 판정하지 않는다. 탐지(ARC-20)하고 경보를 낸 뒤, 그 Payment의 정산 지급 대상을 Hold하고 대사로 보낸다. 대사로 확인된 뒤에는 **2인 승인 Adjustment**로 append-only 정정 사실을 남긴다. 정정 수단은 정정 CANCEL 레코드를 다음 정기 파일로 보내는 것을 기본으로 하고, 매입사 수기 정산은 예외 기록으로 둔다. MATCH 확인 전에는 해결로 처리하지 않는다.
  - **②** 재매입 승인·실행 재검사가 EAS를 함께 본다. 어느 세대든 CONFLICT(UNRESOLVED)면 거절한다(새 사유, 예 `ORIGINAL_UNRESOLVED`). 대사 확인 근거가 생긴 뒤에만 재매입한다(UNKNOWN_NOT_RECONCILED와 같은 fail-closed).
- 대안:
  - ① (b) 대사 확인 뒤 봉인이 그 취소를 자동으로 다시 판정해 CANCEL로 넣는다(자동 Recovery — 사람 승인 없음). (c) 탐지·경보만 하고 정정은 매입사 수기 정산으로만 한다(Hold 없음).
  - ② (b) 현행 유지(최고 세대·표지만 보고, CONFLICT는 대사로만 처리). (c) 재매입은 허용하고 CONFLICT 세대가 있다는 표시만 승인 화면에 보인다.
- 영향:
  - ① 권장안은 정정까지 사람 승인 두 번(대사 확인 + Adjustment)이 필요하고, Hold로 지급이 늦어진다. (b)는 빠르지만 append-only '불요'와 모순되는 자동 효과가 생겨 원칙("자동 Recovery와 Adjustment 구분")과 맞지 않는다. (c)는 지급 과다가 대사 전까지 남는다.
  - ② 권장안은 매입사의 상충 응답 하나로 그 건의 재매입이 대사 때까지 막힌다(매입사 입금 지연). (b)는 이중 매입 요청 경로가 남는다(§5.3, 지금 도달 가능).
- 막히는 작업: ① 탐지 Reader·Hold·Adjustment 명세(대사 RECON 작업과 묶임), ARC-20. ② ReacquisitionRule 변경(ACQ-ADMIN-002), ARC-21.

## 9. 이번에 하지 않은 것

- 소스(`apps/**`)·시험·migration을 고치지 않았다. `CancelCandidateRule`·`saleMemberships`의 '최고 세대' 선택은 그대로다.
- 시험 기대값을 바꾸지 않았다. CC-4 → CC-8 이동을 확정하거나 되돌리지 않았다.
- `docs/rules/FIN.md`(BR-FIN-040 상태 `제안` 그대로)·`docs/DECISIONS.md`·`harness/questions.json`·`harness/tasks.json`을 고치지 않았다. Q-ARC-1~3은 이 문서에만 있고 질문 대장 등록은 Coordinator가 한다.
- 시험을 실행하지 않았다. §2·§3의 사실은 코드·git 이력·명세 문서를 읽어 확인한 것이고, §5.2 "현재 코드" 칸 중 기존 시험이 없는 칸(#11·13~19·21)은 코드를 읽고 추론한 동작이다. 실행해 확인하지 않았다.
- 대사(RECON)·CANCEL 재매입(BR-FIN-050)·전송 보류 해소(Q-ACQA-10·BR-FIN-052)는 범위 밖이다. 이 문서는 그 작업들이 열릴 때 §5.2 #15~19가 실제로 생긴다는 점만 적는다.
- (0.1a) ARC-20 탐지 SQL을 Demo·TEST DB에서 실행하지 않았다. 기존 '불요' 기록 중 잘못 종결된 것이 실제로 있는지는 아직 모른다. §5.3 m-8 행의 "현재 코드: g2 승인 가능"은 `ReacquisitionRule.kt:28-36`·`:54-64`를 읽고 추론한 것이고, 시험으로 재현하지 않았다.
