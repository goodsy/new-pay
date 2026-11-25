# POLICY-CONFLICTS — 전 도메인 정책 충돌·미결 통합 목록 (rev 0.1a)

- 작성: 2026-11-25, GOV-DIAG-001 종합 단계. 기준 커밋 `41139db`(main). 소스·DB·기존 문서·git은 바꾸지 않았다.
- **이 문서는 충돌을 해결하지 않는다.** 근거와 대안만 적는다. "권장안"은 각 진단이 적은 권장을 그대로 인용한 것이며 전부 **권장안 — 미결정**이다. 사용자 답 전에는 어느 항목도 운영 동작으로 확정하지 않는다(CLAUDE.md 결정과 진행).
- 원천(약어)
  - 진단: `DC-M` = [DOMAIN-CONSISTENCY-1-MASTER](DOMAIN-CONSISTENCY-1-MASTER.md), `DC-P` = [DOMAIN-CONSISTENCY-2-PAYMENT-FINANCIAL](DOMAIN-CONSISTENCY-2-PAYMENT-FINANCIAL.md), `SC` = [SOURCE-CONVENTION-REVIEW](SOURCE-CONVENTION-REVIEW.md), `UI` = [ADMIN-UI-DIAGNOSIS](ADMIN-UI-DIAGNOSIS.md), `INV` = [ADMIN-FEATURE-INVENTORY](ADMIN-FEATURE-INVENTORY.md).
  - 규칙 대장 초안 진단 절: `BIZ`·`PAY`·`CAN`·`FIN`·`WH`·`ADM` = `docs/rules/<도메인>.md` `## 진단`의 (a)~(f) 항. 예: `BIZ (b)-1`.
  - 질문: `harness/questions.json`(이 문서 작성 시점 PROPOSED 9 · PENDING 5 · APPLIED 2 · ANSWERED 327).
- 분류
  - **(A)** 서로 충돌하는 정책 — 확정 문서·결정끼리 다른 것을 말한다.
  - **(B)** 코드와 문서가 다른 정책 — 결정은 있는데 코드가 다르거나, 코드가 정한 것을 문서가 다르게 적는다.
  - **(C)** 아직 결정하지 않은 정책 — 기존 PROPOSED/PENDING 질문 + 새 후보(가칭 ID, questions.json 미등록).
  - **(D)** 과거 정책·폐기 후보·대체 표시 누락 — 뒤 결정이 우선이라 실제 충돌은 아니지만 옛 문장이 그대로 남아 권위처럼 읽힌다.
  - **(E)** 사용자 결정 없이 Coordinator/Implementer가 정한 업무 동작 — 규칙 대장에서 상태 `제안`(출처 `적용`)인 것. 일괄 확인 대상.
- 우선순위: P0 금융 정합성·보안·데이터 손상 / P1 정책 충돌·잘못된 상태 전이 / P2 도메인 경계·아키텍처 / P3 관리자 기능·UI / P4 문서·명명·편의. 원 진단에 우선순위가 있으면 그대로 쓰고, 없으면 종합 단계에서 붙였다(행에 `*`).
- **확정된 정책은 다시 묻지 않는다.** 행 안에 이미 확정된 부분과 근거를 먼저 적고 남은 질문만 남겼다. 확정 규칙을 바꾸게 되는 대안에는 "○○ 변경안"을 붙였다. 원천 문서(대장 진단 절 등)의 권장안이 확정 규칙과 겹치면 원천은 고치지 않고 이 문서에 주석만 단다.
- 같은 문제가 여러 진단에 나오면 한 행으로 합치고 출처를 모두 적었다. 통합 문제 목록(NP-nn)과의 대응은 [NORMALIZATION-PLAN.md](NORMALIZATION-PLAN.md) §3에 있다.

## 0. 집계

| 분류 | 행 수 | ID |
|---|---|---|
| (A) 서로 충돌 | 11 | PC-01 ~ PC-11 |
| (B) 코드 ≠ 문서 | 15 | PC-12 ~ PC-26 |
| (C) 미결 | 25 (기존 질문 묶음 6 + 새 후보 19) | PC-27 ~ PC-51 |
| (D) 과거·대체 표시 누락 | 13 | PC-52 ~ PC-64 |
| (E) 사용자 결정 없이 정한 업무 동작 | 10 (일괄 대상 119행 + 개별 확인 권장 9묶음) | PC-65 ~ PC-74 |
| 합계 | **74** | |

우선순위별(행 기준): P0 4 · P1 25 · P2 18 · P3 7 · P4 20.

- P0 4행: PC-12(HMAC), PC-13(사유 카드번호 검사), PC-37(사건 hold의 FINANCIAL 반영), PC-38(RECON 전 LIVE 금지·UNKNOWN 끝맺음).

---

## 1. (A) 서로 충돌하는 정책

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-01 | A | **거절(REJECTED) Site의 재신청**: D-MS-005 "재신청은 새 Site"인데 REJECTED 뒤에도 Site 생명주기는 ACTIVE라 같은 채널로 새 Site를 등록하면 D-MD-008 활성 채널 유일성에 걸린다(`SITE_CHANNEL_DUPLICATE`). Site 종료 명령·UPDATE 권한도 없다 | DC-M-02·§4.3, BIZ (b)-1·(e)-3·(f)-2. `DEC:34,50`, `MerchantOnboardingService.kt:128-129`, `SQL/V1:66-70`, `PRIV/V2:20`, MOPS2:435 화면 문구 | ① 채널 유일성 판정에서 REJECTED 제외 ② REJECTED 결정 시 Site 생명주기 종료(종료 명령 선행) ③ 같은 채널 재신청은 D-MD-008 "예외 승인 기록"으로만 ④ 현행 유지 + D-MS-005 문구 축소. 권장안 — 미결정: DC-M §6 "거절 시 채널 점유 해제 또는 Site 종료 명령 선행" | 같은 도메인 재심사 불가, 화면 안내와 실제 동작 불일치, 우회 표기로 D-MD-009 취지 이탈 | Site 심사 운영, MASTER-LIFECYCLE-001, Q-MD-18(PC-15)과 함께 결정 | P1 |
| PC-02 | A | **선지급 기준 "매입 전송 완료"의 존속**: 초기-확정사항·CLAUDE.md "승인 완료/매입 전송 완료/매입 확정 3기준 유지" ↔ D-ACQ-005 사용자 원문 "승인시점과 매입결과 선택가능" + 적용문 "전송 완료 기준 존속은 정산 작업에서 확인". 같은 결정의 적용 범위(선지급만인지 입금 후 지급 포함인지)도 모호 | DC-P-05·§6 충돌 행, FIN (a)-5·(b)-1·(b)-2(Q-FIN-RG-03·04), BR-FIN-090·091 | (a) 3종 유지 (b) D-ACQ-005를 2종 축소 새 결정으로 보고 CLAUDE.md·정산지급방식 갱신 — **채택 시 `CLAUDE.md:41` 변경(사용자 지침, NORMALIZATION-PLAN U-12-A와 함께)** (c) 지급 시점 축과 선지급 근거 축을 별개로 정의. 적용 범위: 선지급만 / 모든 지급. 권장안 — 미결정: DC-P Q-DC-P-02·FIN Q-FIN-RG-03 "(a) 3종 유지", Q-FIN-RG-04 "선지급 정책에만 적용" | 정산 적격성·선지급 모델 자체가 달라진다 | SETTLEMENT-CONTRACT-001 | P1 |
| PC-03 | A | **외화 범위**: D-CP-002(생성 KRW·USD) ↔ D-AUTH-001(범위 §1 KRW, 외화 초기 제외)·D-AUTH1-002(승인 KRW만). Snapshot이 USD를 활성화하면 Payment는 생성되지만 승인은 불가하고 만료로만 끝난다 | PAY (b)-1·(e)-4·(f) F-1, DC-P §4.4 외화 행, `ConfirmStartService.kt:199`, Q-SETTLE-CURRENCY(PROPOSED), BR-PAY-011·013·014, FIN BR-FIN-020·087 | 정산 통화 결정 전까지 기준정보에서 외화 비활성(운영 절차) / 현행 유지(생성 가능·만료 종료) / 생성 단계에서 KRW 외 거절. 권장안 — 미결정: PAY F-1 "정산 통화 결정 전까지 외화를 활성화하지 않는다" | 생성은 성공하고 영구히 승인 못 하는 결제가 생길 수 있다(Demo 설정 시) | 외화 Demo·검증, SETTLEMENT-CONTRACT-001 | P1 |
| PC-04 | A | **정산 거래일과 운영 홈 KPI 날짜 문언**: 정산 거래일 = 금융 사실 실제 발생일(승인일·취소일·Refund 완료일), Refund 완료일 = 확정 외부 반환 사실의 업무 시각 — **이미 확정**(BR-FIN-077·079 `기준`, SCP §3·연결 B, CLAUDE.md:40, DC-P §6 "확정 현행"). 실제로 어긋나는 것은 KPI 결정 문언: D-ADMIN-HOME-001 ② "정산일 규칙과 같은 완료일 기준" ↔ D-ADMIN-HOME-002 ③ PG 확정 시각(confirmed_at). 결제 사실에 `approvedAt`·`institutionTransactionDate`·`institutionBusinessDate`(nullable)가 함께 있어 기관 거래일이 없는 경우의 처리와 시간대는 Q-CALENDAR 미결 | CAN (a)-10·(b)-2(Q-CAN-2), FIN (b)-3(Q-FIN-RG-05), DC-P §6, Q-CALENDAR(PROPOSED), `AuthorizedPaymentFact.kt:21-23`, BR-CAN-076·077, BR-FIN-077·079 | KPI는 PG 확정 시각 유지 + D-ADMIN-HOME-001 ② 문언 정리 / KPI도 기관 거래일로. 정산을 PG 확정 시각으로 보는 안은 **BR-FIN-077·079 해석 변경안**. 권장안 — 미결정: Q-CAN-2·Q-FIN-RG-05 "정산 = 기관 거래일(없으면 정산 대기, 시간대는 Q-CALENDAR), KPI = PG 확정 시각 유지" | 자정 근처·늦게 확정된 UNKNOWN 건은 KPI 날짜와 정산일이 다르다(KPI는 "정산 금액 아님" 표지로 고지) | SETTLEMENT-CONTRACT-001, 운영 홈 KPI 정의 | P1 |
| PC-05 | A | **수수료·정산 정책 Assignment 수준**: SCP §8·§11·연결 C "CSA 단위 Assignment + 명시적 MID Override" ↔ D-MD-018·MASTER V5 `policy_assignment` "(CSA, MID, 통화, 종류) MID별 배정만". 한도만 D-MS2-001로 정해졌다 | FIN (b)-4·(c)-3·(e)-7(Q-FIN-RG-06), `V5__authorization_terms.sql:110,113` | 한도처럼 CSA_DEFAULT + MID_OVERRIDE(Override 우선·전체 대체), 그 밖 모호는 AmbiguousPolicy / 현행 MID별 배정만. 권장안 — 미결정: Q-FIN-RG-06 "D-MS2-001 준용" | 정책 해석 규칙·MASTER schema가 달라진다 | SETTLEMENT-CONTRACT-001, MASTER V5 확장, PC-33과 연결 | P1 |
| PC-06 | A | **가맹점 Webhook 순서 보장**: baseline 03 47행 "Payment 단위 전달 순서 유지" ↔ WEBHOOK-001 WH-11 순서 비보장(+sequence+GET, 구현 결정) ↔ FIN-FACT-RELAY-001 L-4(앞 실패가 뒤를 막지 않음). 명세 스스로 "공개 계약 의미면 질문"이라 적었다 | WH (b)-1·(f)-1(WH-Q1), BR-WH-033(제안) | 순서 비보장 + sequence + GET 확정 / Payment 단위 순서 보장(앞 전달 전 뒤 보류). 권장안 — 미결정: WH-Q1 "순서 비보장(현 구현)" | 가맹점 공개 계약 의미 | 없음(현 구현 유지 가능) — 대장 확정, 가맹점 계약 문서 | P1 |
| PC-07 | A | **취소 사건 hold 중 추가 취소**: D-CANCEL-006 "hold 해제 전 그 Payment 추가 취소 차단" ↔ CANCEL-CONTRACT-001 §4.10 "(가맹점) 취소"로 한정 + D-CANCEL-001·005(보정이 해제 선행 조건). 코드는 가맹점만 막는다 | CAN (b)-1·(e)-1(Q-CAN-1), `CancelStart.kt:96`, BR-CAN-037(제안) | 가맹점만 차단·운영자 보정 허용(현행) / 운영자 보정도 차단. 권장안 — 미결정: Q-CAN-1 "가맹점만 차단, 운영자 보정 허용" | 보정 취소가 해제 경로의 일부인지가 바뀐다 | 대장 확정, RECON 해제 설계 | P1 |
| PC-08 | A | **승인 상세의 대상 표시**: MERCHANT-OPS-002 §9 :734 "첫 MID는 CSA·Revision·PRIMARY Site ID" ↔ PII-MASKING-POLICY §3 #25·ADMIN-DS-001 §3.2·D-ADMIN-UI-010 ③(내부 키 화면 미표시). 승인 상세가 `ctr_`·`csa_`·`site_`·`act_`를 그리고 시험이 그 노출을 기대값으로 고정한다 | UI-02·UI-04, `MerchantContractApprovalDetail.tsx:346-383`, `ApprovalsScreen.merchant.test.tsx:125-126` | 사람용 값(가맹점 화면과 동일) + 접힌 "기술 정보"에 키 / 현행(키 표시) 유지 + PII #25 예외 명시. 권장안 — 미결정: UI §9-1 "[추정] 사람용 값 + 접힌 기술 정보" | 승인자가 대상을 키로만 확인 → 오승인 위험. 답 전에는 시험 기대값을 바꾸지 않는다 | 승인 상세 정리, UI-04 접두어 공용화 | P1 |
| PC-09 | A | **승인 ID `apr_` 노출**: D-ADMIN-UI-010 ③("apr_ → 승인함 링크") ↔ PII #25(`apr_` 화면 미표시). 4화면 5곳이 그리고 시험 6줄이 단언, 가맹점 화면만 숨김 | UI-05, `ApprovalsScreen.tsx:219`, `ReconciliationScreen.tsx:78·80`, `PaymentOpsScreen.tsx:308`, `ReacquisitionRequest.tsx:150` | `apr_`를 운영자 소통용 외부 식별자로 보고 PII #25 예외 명시 / 내부 키로 보고 전 화면 숨김. 권장안 — 미결정: UI §9-1 "[추정] 운영자 소통용으로 PII #25 예외 명시" | 같은 값이 화면마다 다른 규칙 | 승인함·대사·보정 취소·재매입 화면 | P2 |
| PC-10 | A | **같은 이름, 다른 개념**: ① '계약' — 사업자 계약 접수의 '계약 완료/해지'(결제 무영향, D-FLOW-003 ②) vs 가맹점 Contract '승인/철회'(결제 영향, D-MD-004) ② '근거 참조' — 2인 승인 근거(D-MOPS-008 ①·D-OPS-010, APPROVER·요청자만) vs 계약서 수령 근거(D-FLOW-004 ④, 사업자 조회 역할 전체) ③ 'Hold' 4계통(PAYMENT IncidentHold / FINANCIAL PaymentFactHold / 매입 WITHHELD·QUARANTINED / 정산 Settlement·PayoutHold) ④ admin `Reconciliation*`(결정 대조)과 금융 대사 | DC-M-18·§4.3, ADM (b)-6, DC-P-16·§3 주의, UI-20 | 화면·용어 분리 / 고정 안내 표기 / 현행 + 용어사전에 분리 등재 / admin 쪽 "결정 대조"로 개명 후보. 권장안 — 미결정: DC-M §6 "화면 용어 분리 또는 고정 안내" | 운영자가 '계약 해지'로 결제가 멈췄다고 오해 등 | FLOW-2 화면, 용어집(PC-49) | P3 |
| PC-11 | A | **대상 종류별 저장 키 방침 차이**: 가맹점 코드는 감사·승인 대상 = 코드(D-MCODE-001 Q-MC-4), Site·계약은 내부 키 유지 + 화면 라벨(D-ADMIN-UI-007 ⑤/Q-SC-9). 둘 다 사용자 답 — 의도 확인 권장 | BIZ (b)-3 | 의도된 차이로 기록 / 한쪽으로 통일 | 감사 조회·이력 표시 규칙이 대상마다 다름 | SITE-CONTRACT-CODE-001 | P4* |

## 2. (B) 코드와 문서가 다른 정책

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-12 | B | **관리 명령 멱등 지문 HMAC 미적용**: D-MOPS-010 ②는 MASTER 비밀 키 HMAC인데 코드는 사업자등록번호·대표자/담당자 이름·전화·이메일을 입력으로 하는 무염 SHA-256(`m1:`) | DC-M-01, BIZ (e)-1(BR-BIZ-048), ADM (e)-6, PII-MASKING-POLICY X-2(BIZ 인용), `AdminCommandExecutor.kt:417-428`, `BusinessCommandFingerprintFields.kt:9-12`, `DEC:190`, MERCHANT-OPS-002 openItems "B3-3a … LIVE 사용 전 필수" | ① 결정대로 HMAC + 기존 행 이중 비교(Demo 한정, 제거 조건 기록) ② 개인정보 필드를 지문에서 빼고 별도 HMAC 필드 ③ LIVE 전까지 현행 + Gate 강화. **D-MOPS-010 ② 문언 범위(사업자 등록 요청 지문의 사업자등록번호)는 새 결정 없이 적용 시점만** 정하면 된다(DC-M §5.2). 다른 관리 명령 지문의 대표자/담당자 이름·전화·이메일을 HMAC으로 바꾸거나 빼는 것은 결정 문언 밖 — **범위 확장·설계 변경**이라 사용자 확인 대상(NORMALIZATION-PLAN U-01 ②). 권장안 — 미결정: DC-M §6 "결정 그대로, FLOW 구현 전 또는 LIVE 전" | DB 접근자가 상호·번호 후보로 지문을 대조해 번호·개인정보 확인 가능. Demo 단계, LIVE Gate 추적 중 | MERCHANT-OPS-002 B3-3a, LIVE Gate | P0 |
| PC-13 | B | **사유 칸 카드번호 패턴 거부 미구현**: D-FLOW-005 ⑧(마스킹 해제 사유)·D-FLOW-007 ①(사업자 중요정보 '사유 및 비고' 전체)은 카드번호 패턴 거부인데 코드는 빈 값·길이·제어 문자만 본다 | BIZ (e)-2(BR-BIZ-305), ADM (a)-4·(a)-5·(e)-1·(e)-2(BR-ADM-096·110), `UnmaskService.kt:32-33`, `BusinessTextRule.kt:8-14`(사용처 `BusinessChangeReason.kt:10`). 관련: SC-05(카드번호 정규식 4벌·공통 벡터 없음) | 예정 배치(MERCHANT-FLOW-001 FLOW-2)에서 시행 / 작은 독립 작업으로 선행 / SC-05 공통 벡터 도입과 함께. 정책 대안은 없음(결정 완료, 2026-11-25) — 진단에 권장 시점 없음 | 사유 칸으로 카드번호가 MASTER·감사에 저장될 수 있다(보안). 우선순위는 원 진단 미분류 — 종합에서 P0 부여. 근거: CLAUDE.md 검증과 안전 "Secret·카드정보 비노출", 사업자 중요정보 변경 사유는 main에 이미 있는 자유 입력 경로. 완화 사정: 결정일 2026-11-25·FLOW-2 시행 예정, Demo 한정, 마스킹 해제는 대상 공급자가 없어 항상 204(INV ACC-05) | FLOW-2, ADM 대장 확정 | P0* |
| PC-14 | B | **가맹점 등록 화면 내부 키 표시·직접 입력**: D-ADMIN-UI-005(2026-11-19 Site ID·계약 ID·서비스 계약 ID 미표시, 직접 입력은 목록 선택으로)인데 등록 마법사가 `site_`·`ctr_`·`csa_`를 6곳에 표시하고 2칸에서 손으로 입력받는다 | UI-03, INV §5-4 #12, BIZ (a)-4, `MerchantRegistrationScreen.tsx:705-909`, SITE-CONTRACT-CODE-001 BACKLOG | SITE-CONTRACT-CODE-001 착수 / 그 전까지 "알려진 미이행"으로 DS·IA 문서에 명시(UI-03 개선 방향) | 운영자 키 복사 입력 오류, 결정 미이행이 Demo에 노출 | SITE-CONTRACT-CODE-001(사람용 코드 선행) | P1 |
| PC-15 | B | **종료된 Site 채널 재사용(Q-MD-18 미결)**: 권장안은 "예외 승인 없으면 거절"인데 DB 유일성이 ACTIVE 행에만 걸려 종료 경로가 생기면 다른 Merchant의 재등록이 DB 수준에서 허용되는 구조. 코드가 사실상 "허용"으로 정해 둔 상태(종료 명령이 없어 지금은 발생 경로 없음) | DC-M-13, BIZ (e)-3·(f)-1, Q-MD-18(PROPOSED), `SQL/V1:66-70`, `MerchantOnboardingService.kt:128-129` | ① 권장안(예외 승인 없으면 거절 — 이력 조회 판정 추가) ② 유일성 키를 생명주기 무관으로 ③ 허용. 권장안 — 미결정: Q-MD-18 "예외 승인 없으면 거절" | 도메인 이전·사칭 위험 | Site 종료 명령(MASTER-LIFECYCLE-001), PC-01과 함께 | P2 |
| PC-16 | B | **Merchant 1:N MID·Site 공유**: 구조 §13·DD:94 "Merchant 1:N MID", D-MD-016 ADDITIONAL 5조건 ↔ 코드 Merchant당 MID 1개 고정, ADDITIONAL 경로 없음, 다른 MID 간 같은 Site 연결 규칙 없음(의도된 미구현) | DC-M-11·12, Q-MD-06·07(PROPOSED), `MerchantAccountIssuance.kt:40-42`, `SiteAssignment.kt:83-95`, `구조:306-356,611-618` | Q-MD-06·07 재제시 / 현행 1개 고정을 v0.1 범위로 구조 문서에 주석 / Site 공유는 기본 금지 + 예외 승인 또는 조건 확정 | 확장 전까지 여러 계약·기관 경로 표현 불가. 추가 MID가 생기면 "한 Site 복수 MID"가 조건 없이 열린다 | MASTER-LIFECYCLE-001, 추가 MID | P2 |
| PC-17 | B | **DefaultDesignation 소속**: MD1 §2.1 "Merchant Aggregate에 포함" ↔ 코드 MID 쪽 값·MID 저장소(`insertFirstDefault`) | DC-M-10, `MD1:56`, `MerchantAccount.kt:45-51`, `MerchantAccountRepository.kt:12-15` | 문서를 코드에 맞춤 / Merchant Aggregate로 이동 / Q-MD-07 답할 때 함께(가역 설계 — 사용자 질문 불필요로 DC-M 분류) | Default 교체 설계 시 불변식 책임 혼동(지금은 DB PK가 지킴) | Q-MD-07 이후 설계 | P2 |
| PC-18 | B | **entitlement_revision 증가 지점**: MD1 §3.3 "셋 — Revision 승인·최초 Default·개통 등록, Site 등록은 올리지 않음" ↔ 코드 30곳 이상(Site 등록 포함, MS1 review M-2를 따름) | DC-M-20, BIZ (c)-2, `MerchantOnboardingService.kt:133,404` | MD1에 "현행은 MS1 §4.6" 주석 / 증가 지점을 규칙 대장 한곳으로. 코드가 맞고 문서가 옛 내용 | 문서대로 구현하면 Snapshot 무결성 결함 재발 | 없음(문서 정정) | P4 |
| PC-19 | B | **내부 채널 격리·재처리 도구**: baseline 03 구현 체크리스트 "격리 큐 조회·수정 후 재처리·감사 제공" ↔ 어느 채널에도 없음(명세가 후속으로 미룸) | WH (c)-10·(f)-8, FIN-FACT-RELAY L-5 | 운영자 승인 절차와 함께 도구 설계 / LIVE Gate 항목으로 등록 | 격리된 이벤트를 운영자가 처리할 수 없다 | WEBHOOK-ENDPOINT-001, RECON 계열 | P2* |
| PC-20 | B | **계좌 환불 미지원 안내**: D-CANCEL-007 "API·운영 안내에 표시" ↔ `apps/` 전체에 안내 없음(문구는 CANCEL-001:381-400에만). 거래 조회·보정 취소 화면은 이미 있다 | CAN (d) BR-CAN-079·(e)-2·(f) Q-CAN-10 | Coordinator 적용 작업으로 작업 카드 등록(CAN 권장 — 질문 아님) | 운영자·가맹점 안내 누락 | 없음 | P3 |
| PC-21 | B | **운영 홈 '확인 필요' 셈**: D-ADMIN-HOME-002 ①② "UNKNOWN·진행 중은 확인 필요로 센다" ↔ `HomeScreen.tsx:377` 카드는 승인 대기·대사 발견만 센다 | CAN (d)·(e)-3, INV §5-4 #11, ADMIN-HOME-ATTENTION-001 BACKLOG(C-6) | 계획대로 ADMIN-HOME-ATTENTION-001 / 결정 문언 정리 | 결제 예외가 홈에 안 보인다 | ADMIN-HOME-ATTENTION-001 | P3 |
| PC-22 | B | **UNMASK 단독 계정의 감사 탭**: 화면은 항상 노출(`navigation.ts:68`, 시험 `navigation.test.ts:21`이 고정) ↔ 서버 VIEW_OWN_AUDIT = 조회4(`AdminPermission.kt:9-10`)·명세 권한표(`ADMIN-BOOT-001…md:277`) → 403 | INV §5-5 #1·§6-2 | 화면을 서버 권한에 맞춤 / 서버 권한 확장(정책 변경) | 유일하게 보이는 탭에서 403 | 없음 | P3 |
| PC-23 | B | **Webhook 전송 세부**: D-WH-007 "응답 크기(Demo 4KiB) 제한" ↔ 본문을 읽지 않고 abort, `maxResponseBytes` 미사용(더 엄격). TEST endpoint 설정은 https만, 전송 adapter는 allowlist 목적지에 http 허용(설정이 더 엄격) | WH (c)-6·(e)-1·(e)-3, `MerchantWebhookTransportSettings.kt:14,23`, `MerchantWebhookSettings.kt:27`, `HttpMerchantWebhookSender.kt:79-83` | 결정 문언을 구현에 맞춤 / 설정 제거 / 층별 기준 통일 | 위반 아님, 뜻 없는 설정값 | 없음 | P4 |
| PC-24 | B | **관리자 결정 문언과 구현 범위**: D-ADMIN1-020(SA는 loginName 볼 수 있음) ↔ 아무에게도 저장 안 함(더 엄격). D-ADMIN1-018 "거부마다 감사" ↔ 미인증 경로(`/api/auth/`·`/api/enroll/`)만 감사 | ADM (b)-2·(b)-3·(e)-4·(e)-7·(f)-3·(f)-4, `ApprovalService.kt:440`, `IpAllowlistFilter.kt:28-37` | 현행 유지(더 엄격) + 문언 확정 "미인증 경로 거부마다 감사". 권장안 — 미결정: ADM f-3 "현행 유지", f-4 "미인증 경로 거부마다로 문언 확정" | 위반 아님, 문언 불일치 | ADM 대장 확정 | P4 |
| PC-25 | B | **이벤트·용어 표기**: AUTH-CONTRACT-001 403행 RISK_INCIDENT effect key `{paymentId}:{reason}` ↔ 코드 `…:{occurrence}`(D-AUTH-012). CANCEL-CONTRACT-001 §9.3 effect key ↔ D-CANCEL1-002·CN-13. DD "여러 Attempt" ↔ 초기 1개(CA-13). DD "AcquisitionSubmission"·"취소 매입 HOLD" ↔ 코드 AcquisitionFile·RECONCILE. AUTH-001:246 그림 "게이트가 providerValidUntil 확인" ↔ §4.4·D-AUTH1-004·`AuthorizationDispatchService.kt:136` | DC-P-14·15·17, CAN (c)-3, PAY (c)-5 | 문서 정정 또는 "AUTH-001이 대체" 표시 / "허용 범위 = 여러 개, 현재 구현 = 1개" 두 칸 | 계약 독자 혼동 | 없음 | P4 |
| PC-26 | B | **코드 주석·시험 이름의 옛 규칙**: `PaymentMethod.kt:3-5` "체크·할부 채택 전", `settings.gradle.kts:36` internal-admin "DB 없음", `CreatePaymentApiSpec.kt:260` 이름 "refund is not modelled yet", `historyTarget.test.ts:29` 이름 "masked"(단언은 원문), TSX 대비 주석 5곳(V1 뒤 근거 소멸) | PAY (d)·(e)-1, SC-21, ADM (e)-5, UI-27 | 주석·시험 이름 정정(기대값 무변경) | RULE-TRACE "대체된 규칙을 지키는 시험" 검사에 걸릴 형태 | RULE-TRACE | P4 |

## 3. (C) 아직 결정하지 않은 정책

### 3.1 기존 질문(PROPOSED 9 · PENDING 5)

다른 행에 합친 것: Q-SETTLE-CURRENCY → PC-03, Q-MD-18 → PC-15.

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-27 | C | **Q-CALENDAR**(PROPOSED): D+N의 N 계산·마감·시간대·휴일 변경 적용 기준. 매입은 달력일 Demo 임시(D-ACQ-003, `CutoffPolicy.kt:28`) | questions.json, DC-P §4.4·§6, FIN (e)-4, CAN (f), BR-FIN-086 | 질문 기록 참조 | 정산일·매입 업무일 | SETTLEMENT-CONTRACT-001, PC-04 | P1* |
| PC-28 | C | **Q-CORRECTION**(PROPOSED): 자동 보정 허용 범위·고위험 지급 차단 | DC-P §4.6·§6, CAN (f) | 질문 기록 참조 | 대사·Adjustment 설계 | RECON-CONTRACT-001 | P1* |
| PC-29 | C | **Q-BATCH-JOBPARAM**(PROPOSED): 정산·대사 Job 식별 JobParameters — 기술 식별이라 업무 규칙 대장 제외(FIN (f)) | DC-P §6, FIN (f) | 질문 기록 참조 | Job 재실행 식별 | SETTLEMENT-001, RECON-001 | P2* |
| PC-30 | C | **Q-MD-06·Q-MD-07**(PROPOSED): 추가 MID 발급 근거·승인, Default 교체·종료 | DC-M §4.2·DC-M-11, BIZ (f)-3, BR-BIZ-141·142 | 질문 기록 권장안 | 금융 권리 의미 | MASTER-LIFECYCLE-001 | P2 |
| PC-31 | C | **Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF**(PROPOSED, 2026-10-16부터): 세션 idle/절대 만료·잠금 횟수·복구 회차 유효기간 등, APPROVER 운영 인원. Demo값 BR-ADM-008·015·019·037·064·079·082·092를 같이 묶는 권장 | ADM (b)-7·(f)-1·(f)-2, CAN (f), BR-ADM-039·122 | 지금 묻지 않음 — LIVE Gate에서 한 번에(ADM 권장) | LIVE 보안 값 | LIVE 준비 | P2* |
| PC-32 | C | **Q-ACQA-05·06·07·10**(PENDING — 이미 질문됨, 재제시 대상: NORMALIZATION-PLAN U-03 ③): 전송 격리 해소·격리 응답 재반영·REVIEW_REQUIRED 보류 해소·전송 보류 해소의 금융 의미. (Q-ACQA-08 Demo 허락은 업무 규칙 아님) | DC-P-07·§6, FIN (f), INV §5-4 #6, BR-FIN-014·030·031·052 | 질문 기록 참조 | 해당 결제의 매입 영구 대기 → 미입금 → 미지급 | ACQ-ADMIN-002 | P1 |

### 3.2 새 후보(가칭, questions.json 미등록)

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-33 | C | 한 Merchant에 **동시 유효 계약(같은 서비스 종류 CSA)이 여럿**일 수 있는가 — 결정·문서 없음, 코드 무제한 | DC-M-04·§4.2, `MerchantOnboardingService.kt:149-165`, `SQL/V1:84-98,156`, D-MD-001 | ① 기간 겹치는 유효 계약 금지(갱신은 Revision) ② 허용 + MID가 근거 CSA 하나만 따르는 현행 명시 ③ SETTLEMENT-CONTRACT-001에서 결정. 권장안 — 미결정: DC-M §6 ① | 어느 CSA의 수수료·정산 정책인지 모호(지금은 MID 1개라 영향 없음) | SETTLEMENT-CONTRACT-001, Q-MD-06 | P1 |
| PC-34 | C | **CSA 추가를 승인 대상으로** 올릴지 — 2인 승인된 계약에 OPERATOR 1인이 CSA를 덧붙이고 바로 MID 발급 근거가 된다 | DC-M-08, `Contract.kt:81-86,97`, D-MOPS-004 | ① CSA를 Revision 내용에 포함 ② CSA 추가 2인 ③ 현행 + "MID 발급 2인이 실질 통제" 명시. 권장안 — 미결정: DC-M §6 "서비스 종류가 늘기 전까지 현행, 확장 시 Revision 내용으로" | 서비스 종류 확장 시 승인 범위가 1인으로 넓어짐 | 서비스 종류 확장 | P2 |
| PC-35 | C | **한 Site를 여러 MID가 쓰는 조건** — 구조 경계 §2는 "가능"만 | DC-M-12·§4.2 | 같은 Merchant + 별도 계약/기관 경로 증거 + 2인 / 기본 금지 + 예외 승인. 권장안 — 미결정: DC-M §6 앞쪽 | 추가 MID 도입 시 주문 중복 제어 | 추가 MID | P2 |
| PC-36 | C | **사업자 사용중지(REGISTERED→INACTIVE)** 시점·권한 — 지침 전이 목록엔 있으나 BP1이 P1 F-011로 미룸, INACTIVE 행 수정은 `check()` 결함 처리 | DC-M-17, TBD-08, `Business.kt:60`, `지침:86-87` | 별도 작업으로 분리, 그 전 INACTIVE 수정은 업무 거절 / 현행 + 지침 주석. 권장안 — 미결정: DC-M §6 앞쪽 | 이관 데이터로 INACTIVE가 생기면 오류 응답 | 사업자 관리 | P3 |
| PC-37 | C | **PAYMENT 사건 hold·RISK_INCIDENT를 FINANCIAL 매입·정산에 어떻게 반영**할까(가칭 Q-DC-P-01) | DC-P-02·§5.3, `JpaFinancialFactRelayStore.kt:144`, `AcquisitionCandidateRule.kt:22-24` | (a) RISK_INCIDENT 전달 → FINANCIAL 결제 보류로 투영 (b) 매입·정산 직전 PAYMENT 조회(동기 의존) (c) 정산에서만 차단. 권장안 — 미결정: Q-DC-P-01 (a) | 반환된 승인의 SALE 매입, 이중 승인 건 정상 정산(TEST 한정 발현) | 매입 파일 구성, SETTLEMENT-CONTRACT-001 | P0 |
| PC-38 | C | **RECON 이전 LIVE 금지 조건의 적용 범위**. **이미 확정**: 이관된 취소 UNKNOWN은 임시 출구 없음 + RECON Use Case 전 LIVE 금지(D-CANCEL1-003 `DEC:131`, BR-CAN-030·031, BR-FIN-125), 이관 뒤 승인 UNKNOWN 유지(D-AUTH-009), hold 해제 = 2인 + 재대사 MATCH(D-AUTH-002 `DEC:59`), CLAUDE.md "보정 후 MATCH 확인 전 해결 금지". **남은 질문**: ① 같은 LIVE Gate 조건을 승인 UNKNOWN 이관·IncidentHold·FORCED(거절 뒤 늦은 취소 성공)·매입 UNKNOWN/RECONCILE에도 명시할지 ② RECON-001 범위에 매입 대사를 넣을지(DC-P-08) | DC-P-01·03·04·08, FIN (e)-3·(f) RG-09, PAY (e)-2·F-9, CAN BR-CAN-030·031, CANCEL-CONTRACT-001 m-1 | ①: 모든 범주에 LIVE Gate 조건 명시 / 취소 UNKNOWN만(확정 범위) 두고 나머지는 LIVE 준비 때 개별 판단. ②: RECON-001에 포함 / 별도 작업. "그 전 임시 운영자 확인 절차(2인)" 안은 **D-CANCEL1-003·D-AUTH-002·CLAUDE.md 변경안**이다(PAY F-9 "임시 수동 절차 정의"도 같음). 권장안 — 미결정: Q-DC-P-04 "예 — LIVE Gate 항목화". 주석: FIN (f) RG-09 권장("RECON-CONTRACT-001을 LIVE 필수 선행으로 명시")의 취소 UNKNOWN 부분은 이미 D-CANCEL1-003으로 확정이다(원천 `docs/rules/FIN.md`는 고치지 않음) | 주문 영구 차단·반환 예약 영구·한도 점유·영구 미매입이 LIVE에서 생기는지 | LIVE 준비, RECON-CONTRACT-001·RECON-001 | P0* |
| PC-39 | C | **수수료 Snapshot 내용의 FINANCIAL 전달 채널**(가칭 Q-DC-P-05) — 지금은 버전 문자열뿐, MASTER 정책 내용 UNDEFINED/DEMO | DC-P-09, FIN (e)-6, `V5:87`, `V2__payment_fact_inbox.sql:81,125` | (a) MASTER→FINANCIAL 정책 Snapshot Outbox (b) PAYMENT 사실에 요율 사본 (c) FINANCIAL이 MASTER API 버전 조회. 권장안 — 미결정: Q-DC-P-05 (a) | 정산 착수 시 새 서비스 간 계약 필요 | SETTLEMENT-001 | P2 |
| PC-40 | C | **선지급 뒤 매입 거절·UNKNOWN·후발 취소·미입금** 시 보류·회수·상계 규칙(가칭 Q-FIN-RG-01) | FIN (f), BR-FIN-102(미결) | 자동 회수 없음 + 영향 Payable Hold(위험 등급별) + 대사 Case, 회수·상계는 계약 근거·2인 / 다음 정산 자동 차감. 권장안 — 미결정: RG-01 앞쪽 | 선지급 손실 처리 | SETTLEMENT-001 | P1 |
| PC-41 | C | **TEST 매입분 정산 포함 여부**(가칭 Q-FIN-RG-02) | FIN (f), BR-FIN-106(미결) | TEST는 Simulator 정산까지, LIVE와 분리(환경 축) / TEST 정산 생략. 권장안 — 미결정: RG-02 앞쪽 | Statement·Payable 환경 축 | SETTLEMENT-CONTRACT-001 | P2 |
| PC-42 | C | **결제 사실 정합성 보류 거래의 정산·지급 포함**(가칭 Q-FIN-RG-07) | FIN (f) | 해제·대사 이관 전 정산 Entry 생성 대기 / 정산 포함 + PayoutHold. 권장안 — 미결정: RG-07 "생성 대기" | 정산 적격성 | SETTLEMENT-CONTRACT-001 | P1 |
| PC-43 | C | **범위 권장안 §4(가상 인증기관)·§5(확장 경계) 채택** 여부 — AC1 §3이 미채택 §5를 근거로 인용 | PAY (c)-7·(f) F-3, BR-PAY-081 | §4·§5 현행 기준 채택 / §5 중 cardType 근거만. 권장안 — 미결정: F-3 앞쪽 | cardType 최종 근거 | AC1 §3 정정 | P2 |
| PC-44 | C | **권리 변화 중 결제 사후 처리**: 계약 철회·MID 중지 중 진행 중 승인이 성공한 경우(허용 §4.2 "별도 업무 정책"), 지급계좌 변경·오류가 신규 승인을 막는지(허용 §4.1-10) | PAY (c)-10·(f) F-4·F-5, BR-PAY-060 | F-4: 거래 기록 + 정산 보류 + 검토 Case / 자동 취소 / 정상 정산. F-5: 지급만 보류 / 신규 승인도 차단. 권장안 — 미결정: F-4·F-5 앞쪽 | 권리 차단 판정 | RECON·정산, MASTER Snapshot | P2 |
| PC-45 | C | **새 가맹점 관문의 "최신 접수 건"**: D-FLOW-001 ① 사용자 문구엔 "최신"이 없고 명세 §4A.5가 추가 — 해지 뒤 새 접수 진행 중이면 새 가맹점 등록을 막는가 | BIZ (b)-6·(f)-4, BR-BIZ-280(제안), DC-M-16 | 최신 접수 건 기준(명세) / 계약 완료 이력이 하나라도 있으면 통과 | 가맹점 등록 관문 의미 | MERCHANT-FLOW-001 FLOW-3 | P1 |
| PC-46 | C | **승인 조회 권한 범위**: 승인 대기 목록·임의 승인 상세를 VIEWER·OPERATOR·SECURITY_ADMIN도 API로 조회 가능(근거·사유는 별도 권한), 화면은 APPROVER만 — 결정 근거 미확인 | INV §5-5 #2·#3·§6-3, `ApprovalQueryService.kt:22-28,62`, `ApprovalHttpSpec.kt:56` | 의도된 "마스킹된 조회"로 확정 / 요청자·대상·역할 필터 추가 | 승인 대상 정보 노출 범위 | ADM 대장 확정 | P1* |
| PC-47 | C | **개인정보 미등록 질문 묶음**: Q-PII-3 남은 부분(KYC 외 대상), Q-PII-5(보관·파기), Q-PII-7(개인사업자 상호 마스킹), Q-PII-8(주문명·merchantOrderId 등급), Q-PII-9(정산 지급 계좌), Q-PII-10(내보내기), 관리자 login_name·표시명·actorId 등급, Webhook·Outbox/Inbox 보존기간. BRN 원문의 가맹점용 화면·외부 API·내보내기 노출(D-PII-001 ① "생길 때 결정")은 보류 유지 | DC-M-23, ADM (f)-9·(f)-10·(f)-11, BIZ (f)-9, WH (f)-9, BR-BIZ-023·024, BR-ADM-115, PII-MASKING-POLICY §8 | 질문 등록(PROPOSED) 후 LIVE Gate·KYC(D-FLOW-002 ④)와 묶어 제시. login_name 권장안 — 미결정: ADM f-10 "마스킹된 조회 범위로 등급" | 질문 대장에서 빠져 재개 때 놓침 | KYC 원문 조회, LIVE Gate | P2 |
| PC-48 | C | **복구 작업 B 직후 SA 1명 상태의 역할 부여 교착**(D-ADMIN1-023은 bootstrap·하한 경보만) | ADM (f)-6, BOOT2 §8 Q-1 | 복구 작업 B도 SA 2명 생성 / 현행 + 운영 절차 | 관리자 권한 부여 불능 | ADM 대장 확정 | P2* |
| PC-49 | C | **관리자 화면 정식 용어·말투**: 'Site' 76·'MID' 60·'Revision' 38, 서비스 계약 3표기, 대표 Site 3표기, 인증 수단 3표기, 해요체 15 vs 합쇼체 466 — 용어집 없음 | UI-20·UI-28, PC-10 | 용어집(정식 이름·허용 별칭·금지어) + `labels.ts` 대조 시험. 정식 용어 선택은 사용자 확인 대상(UI) | 같은 대상을 다른 이름으로 인지 | 화면 정리 B3 | P3 |
| PC-50 | C | **Webhook endpoint·수동 재전송 세부**: WEBHOOK-ENDPOINT-001 명세 없음(선행 모두 DONE), 수동 재전송의 시도 수·시각 재설정·2인 여부·범위, D-WH-002 "RECON hold 해제와 함께 재결정" | WH (f)-5·(f)-6·(f)-7, BR-WH-007·043 | 각 명세 착수 시 질문 | 운영자 보정 취소 통지 경로는 이미 발생 가능 | WEBHOOK-ENDPOINT-001, RECON-CONTRACT-001 | P3 |
| PC-51 | C | **LIVE 운영 수치·약관**: 결제 유효기간·TTL·조회 예산·이관 기준·한도·할부 개월·최소금액·lease·ACK timeout, 정산 정책 말미 E 요율·세율·반올림, 결제창 약관(CHECKOUT-TERMS-001) | PAY (f) F-7·F-8, DC-P §6 미결 행 | 지금 묻지 않음 — LIVE 준비 Gate에서 기관 규약 확인 후(PAY 권장) | LIVE 전환만 | LIVE 준비, CHECKOUT-TERMS-001 | P4* |

## 4. (D) 과거 정책·폐기 후보·대체 표시 누락

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-52 | D | **사업자등록번호 마스킹 대체 사슬**: D-MD-008 → D-MOPS-003 → D-MOPS-006 ③ → D-ADMIN-UI-004 ⑦ → D-MCODE-003 ② → D-BIZ-003 → D-BIZ-006 ② → D-BIZ-007 → D-PII-001. 옛 행 4건(D-BIZ-006 ②·D-MOPS-003·D-BIZ-003·D-MCODE-003 ②)에 변경 역참조가 없어 "어디서나 마스킹"과 "원문"이 둘 다 확정으로 읽힌다(모범: D-MOPS-006 ③ 행) | DC-M-03·§4.3·§4.5, BIZ (a)-1·(b)-5, ADM (a)-2, RULE-GOV P-2, `DEC:177,183,226,229,231,246` | ① 옛 행에 `[변경: …]` 역참조(내용 무변경) ② 원장에 "대체 관계" 열 ③ 규칙 대장에서 현행값 한 줄로만. 실제 정책은 D-PII-001 하나 — Coordinator 처리 가능(DC-M §5.2) | 옛 결정을 근거로 마스킹을 되살리는 구현·리뷰 | 없음 | P1 |
| PC-53 | D | **D-WH-009 → D-FFR-001**: D-WH-009 "publish_status 수정 거부"가 D-FFR-001(두 열 UPDATE) 뒤에도 그대로, 대체 표시 없음 | WH (a)-1·(c)-9, `DEC:150` | 역참조 표시 | Outbox 권한 해석 혼동 | 없음 | P4 |
| PC-54 | D | **bootstrap 인원 D-ADMIN1-002(SA 1 + APPROVER 1) → D-ADMIN1-022(SA 2 + APPROVER 1)**: BOOT1 §0·§4.9.1 본문은 1+1 문장(현행화 주석 한 줄만), §4.9.2 작업 B는 SA 1명 | ADM (a)-1·(c)-7 | 본문 정정, 작업 B는 PC-48과 함께 | 운영 절차 오독 | 없음 | P4 |
| PC-55 | D | **사업자관리 정책서·지침서(PC-BIZ-POL-001·DEV-001)**: POL-003·§1-5·지침:55 "AML/KYB(생년월일·실소유자) 받지 않음" → D-FLOW-001 ④·D-FLOW-002 수집, §5 DRAFT 표(셋 다 선택) → D-BIZ-002(구분+번호+상호 필수), §7 범위 밖 표(접수·담보·가상자산) → D-FLOW-001·003, §4 흐름에 D-FLOW-001 ① 관문 없음, §1-5·§3 "Merchant/KYB/Contract 이번 구현 제외" | DC-M-21·§4.3·§4.5, BIZ (a)-7·(c)-3·(c)-4·(c)-5, RULE-GOV P-3 | ① baseline 원문 보존 + BASELINE-INDEX "부분 대체" 표 ② baseline 말미 변경 주석 | baseline이 확정 지위라 옛 문장이 권위로 인용됨 | 없음(표시 방식은 사용자 확인 — PC-56과 같은 묶음) | P4 |
| PC-56 | D | **구현 범위 관련 옛 문장**: `CLAUDE.md:42` "실제 통화/체크카드/할부·인증 범위 추가안은 사용자 채택 전", BASELINE-INDEX "최초 구현 범위 권장안 … 채택 전", 범위 권장안 머리말 "제안, 사용자 확정 전"·§2(신용 일시불 먼저 → D-AUTH1-003 동시 개방), 초기-확정사항:122 "추가 범위 확정 전" — D-AUTH-001(범위 §1~§3 채택)·D-AUTH1-003 이후 | PAY (a)·(c)-1·(c)-2·(c)-3·(f) F-6, FIN (c)-2 | 한 커밋으로 현재 결정에 맞춤. **CLAUDE.md는 사용자 지침이라 문구 변경은 사용자 승인 필요**(PAY F-6) | 착수 전 관문 정확도, FIN 카드 종류·KRW 근거가 어긋나 보임 | 사용자 지침 문구 승인 | P4 |
| PC-57 | D | **확정된 세부가 baseline·결정 본문에 미반영**: 시도 §4 "자동 보상취소 여부는 별도 정책" → D-AUTH-002. 초기-확정사항 "반환 비율/고정비 반환 값은 별도 설정"·SCP 연결 B "별도 확정" → D-CANCEL-008·009. D-AUTH-003 행에 유일 예외 D-CANCEL-012 표시 없음. D-ADMIN-HOME-001 ② "취소·환불 합계"(환불은 v1 범위 밖, D-CANCEL-007) | PAY (a)·(b)-3·(c)-4, FIN (a)-7·(c)-1, CAN (a)-6·(c)-7 | baseline 주석 / 결정 행 역참조 | 별도 확정이 남은 것처럼 읽힘 | 없음 | P4 |
| PC-58 | D | **답변된 질문이 "미결"로 남은 본문**: Q-MD-08/09/10(MD1 §2.2·§2.3, DD:98 → D-MD-016~018), Q-MS-05(MS1 §4.4 → D-MS-005), Q-PII-6(PII §7.3·§8 → D-FLOW-007 ①), Q-PII-3 첫 적용(PII §5.1·BOOT1 §1.2·§4.5.3 → D-FLOW-002 ① 일부), Q-ADMIN-AUTH(AC1:314 → D-ADMIN-001·002), Q-WH-01~07(WEBHOOK-001 §0·§4.4 → D-WH-001~008), Q-MOPS-04(MOPS-001 §1 → D-PII-001 ③), Q-ACQA-04 권장안 문구(ACQ-ADMIN-001 §12 → D-ACQA-004·`AcquisitionOpsDecision.kt:53`) | BIZ (a)-9·(c)-1·(c)-8·(c)-10·(c)-11, DC-M-19·20, ADM (a)-6·(c)-1·(c)-2·(c)-6, PAY (c)-6, WH (c)-1·(c)-2, FIN (a)-10·(c)-6 | 본문 정정 또는 "현행은 D-…" 머리 주석 | 해결된 질문을 다시 묻거나 옛 권장안을 구현 | 없음 | P4 |
| PC-59 | D | **명세 안 폐기 설계 잔존**: WEBHOOK-001 `HELD_DECISION`(§5.1·§5.3·§11)·`createdAt`=업무 시각(§4.2.1)·`failedAt`·표 단위 UPDATE(§5.3)·`payment.failed` "항상"(§4.1)·relay 소유(§1.2). CANCEL-001 §5.2·§5.3 "진입점 없음"·CN-02. CANCEL-CONTRACT-001 §4.5·§4.11 CC-12(→ D-OPS-005·CN-32), 제목 001~012 vs 본문 013, §6 "권장" 문단. MOPS-002 §4.4·L-7 D-MOPS-006 ③. MOPS-001 §2 "응답 마스킹 값만". MERCHANT-BIZ-LOOKUP-001 수용 기준 "권한 OPERATOR·평문 미노출". HK1 §0·§2·§3 "마스킹 사업자등록번호". PII §5.3 "IN_PROGRESS". D-CANCEL1-001 "진입점은 ADMIN-BOOT-001"(실제 ADMIN-PAYMENT-OPS-001). ADMIN-UI-001 §3.6 "label 위"(→ D-ADMIN-UI-005 ④)·§4 `--brand`. ADMIN-DS-001 §2 수치(394줄·26파일) | WH (a)-3·(c)-3~8, CAN (a)-1·(a)-2·(b)-3·(c)-1·(c)-2·(c)-4, BIZ (c)-6·(c)-8·(c)-9, ADM (c)-3·(c)-4, UI-16·UI-27 | 명세 본문을 "현재 규칙만"으로(HARNESS-GUIDE §12) — 개정 이력은 파일 끝 | 명세 독자가 폐기 설계를 현행으로 오독 | 없음 | P4 |
| PC-60 | D | **상태·위치 문구 stale**: ACQUIRING-001·FIN-DBACCOUNT-001 "DONE 아님"(archive에선 DONE), ACQUIRING-001 근거가 역사 자료 인용, MD1 제목 "1단계: 명세·질문 후보"·§1 "관리자 web 만들지 않는다", CP-CONTRACT-001:10·14·43, MERCHANT-BIZ-LOOKUP-001 카드 BACKLOG(D-BIZ-004로 합쳐짐), SITE-CONTRACT-CODE-001 "로컬 브랜치 wip/scode-1"(실제 `origin/wip/scode-1`만) | FIN (c)-4·(c)-5, DC-M-20·22, PAY (c)-8, BIZ (f)-11 | 문구·카드 정정(Coordinator) | 재개 때 닫힌 작업 재수행·브랜치 못 찾음 | 없음 | P4 |
| PC-61 | D | **DOMAIN-DEFINITION.md 전반**(2026-10-20 이후 미갱신): Q-MD-08 "미결", Business 등록 상태·Site 심사·가맹점 코드·계약 접수 누락, 인증기관 "미결"(:163), "KRW + 일부 외화"(:35), "Business의 Merchant/Agent/Seller 역할"(:93·211, 실제 모델에 없음) | DC-M-19·§4.5, PAY (c)-9, DC-P-15·17 | ① DD 갱신 ② DD를 규칙 대장·용어집으로 대체 ③ DD 머리에 "기준일 2026-10-20" 경고 | 색인 문서로 옛 규칙 인용 | 없음 | P4 |
| PC-62 | D | **baseline architecture 편차 미기록**: 05 §16 "내부 운영자 SSO/MFA" ↔ D-ADMIN-001 자체 계정 + TOTP(D-ADMIN-005는 편차 명시했으나 SSO는 없음), 01 실행 단위 9종(relay·worker 별도 프로세스) ↔ 통합 운영(D-PROC-005 유지), 02 소유 표 "Refund 거래 상태"(v1 범위 밖)·"AgentSettlement"(미결 A00) | ADM (b)-1·(c)-8·(f)-14, DC-P-19·§6 | DECISIONS에 편차 명시 / baseline 원문 보존 + 별도 문서에 표시 | 범위 오해 | 없음 | P4 |
| PC-63 | D | **D-MOPS-010 번호 반대 사용**: DECISIONS ① 재개 승인자 ② HMAC ③ Route 종료 ↔ MOPS-002 §9.29·L-4 "HMAC = ①", §11.2 "Q-MOPS-25 = ②", 시험 `SiteConfirmationAndReviewSpec` "②" = 재승인 | BIZ (b)-2·(c)-7 | 명세·시험 주석을 DECISIONS 번호로 | BR-ID 연결 시 혼동 | RULE-TRACE | P4 |
| PC-64 | D | **기존 진단 문서 상태 표시**: DOMAIN-ASSESSMENT §6 "재구현 후보: Mapper·다중 Entity 축소" → D-PROC-004로 유지 결정, §3.2 "domain Spec 28"(실제 Spec 20 + Fixture 8), CURRENT-STATE §5 tasks 수치(70/DONE 51 → 현재 41/DONE 15)·SCODE-1 위치 | DC-P §2·§6·DC-P-18, DC-M §2·DC-M-22 | 진단 문서는 고쳐 쓰지 않고 이 종합 문서에서 정정 사실만 참조(현행 방식) / 상태 머리 주석 | 진단을 근거로 결정된 사항을 다시 제안 | 없음 | P4 |

## 5. (E) 사용자 결정 없이 Coordinator/Implementer가 정한 업무 동작

### 5.1 일괄 확인 대상 — 규칙 대장 `제안` 행 (PC-65)

규칙 대장 초안 6개에서 상태 `제안`(출처 `적용`)인 행이다. 다수는 fail-closed 방향이라 ADM (f)-8·WH (f)-4·PAY F-2 권장대로 **목록 제시 + 예외만 개별 질문**하는 일괄 확인이 후보다(권장안 — 미결정). 금융·보안 의미가 커서 개별 확인을 권하는 행은 §5.2에 따로 적었다. §5.2의 BR-ID는 대부분 아래 목록에 들어 있지만, **BR-PAY-089(`확정`, D-AUTH-010 해석 정정)·BR-PAY-098(`Demo값`, D-AUTH-009)·BR-ADM-030(`확정`, 배제 범위 확대)은 `제안`이 아니라 119행에 없다** — 대장 상태와 별개로 출처 확인을 권하는 항목이다.

| 도메인 | 대장 규칙 수 | `제안` 수 | `제안` BR-ID (숫자만) | 대장 쪽 권장 |
|---|---|---|---|---|
| BIZ | 196 | 14 | 017, 018, 064, 067, 084, 162, 167, 172, 213, 234, 280, 285, 291, 301 | BIZ (f)-5~8 확인 권장 |
| PAY | 131 | 10 | 040, 049, 051, 053, 063, 081, 105, 106, 109, 116 | PAY F-2 "모두 현행 구현대로 확정" |
| CAN | 108 | 26 | 002, 005, 014, 019, 022, 023, 025, 037, 041, 048, 052, 063, 065, 067, 068, 072, 075, 077, 078, 080, 085, 089, 094, 095, 096, 102 | CAN Q-CAN-1~9(5~9 현행 유지) |
| FIN | 137 | 16 | 008, 012, 013, 019, 020, 024, 028, 040, 041, 050, 051, 053, 057, 100, 111, 123 | FIN Q-FIN-RG-08 "일괄 확인(fail-closed 유지)" |
| WH | 80 | 27 | 002, 003, 004, 008, 009, 010, 011, 013, 016, 018, 019, 021, 026, 027, 030, 032, 033, 034, 035, 037, 043, 044, 057, 060, 061, 062, 077 | WH (f)-4 "현행 유지 확인 요청 후보" |
| ADM | 138 | 26 | 009, 017, 022, 023, 024, 028, 036, 041, 046, 057, 060, 062, 063, 071, 072, 073, 101, 108, 111, 120, 121, 130, 131, 132, 137, 138 | ADM (f)-8 "일괄 확인 1건 + 예외만 개별" |
| **합계** | **790** | **119** | | |

- 집계 방법: 각 대장의 `## 진단` 앞 표에서 `| BR-… |` 행의 상태 열 첫 단어를 셌다(종합 단계 스크립트, 대장 머리·Coordinator 노트 수치와 일치). WH 1행은 상태 열이 표 형식에서 어긋나 분류되지 않았다(80행 중 79행 분류).
- `미결` 행(질문 연결): BIZ 5(023 Q-PII-7, 024 Q-PII-5, 082 Q-MD-18, 141 Q-MD-06, 142 Q-MD-07), PAY 1(013 Q-SETTLE-CURRENCY), FIN 8(014 Q-ACQA-07, 030 Q-ACQA-05, 031 Q-ACQA-06, 052 Q-ACQA-10, 086 Q-CALENDAR, 087 Q-SETTLE-CURRENCY, 102 RG-01 후보, 106 RG-02 후보), ADM 3(039 Q-ADMIN-APPROVER-STAFF, 115 Q-PII-5, 122 Q-ADMIN-SEC-VALUES). CAN·WH 0.

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-65 | E | 위 119행 일괄 확인 | 각 대장 (f), RULE-GOV-001 §2.1("적용 = 사용자 확인 전 제안"), RULE-GOV P-6 | 일괄 "현행 유지" 확인 + 예외 개별 / 도메인별 나눠 확인 / 행별 재검토 | 대장 확정(RULE-GOV 4단계), RULE-TRACE의 "확정 규칙만 착수" 관문 | 대장 확정, 각 도메인 후속 작업 착수 | P1 |

### 5.2 개별 확인 권장 (금융·보안·상태 의미)

| ID | 분류 | 내용 | 근거 | 대안 | 영향 | 막히는 작업 | P |
|---|---|---|---|---|---|---|---|
| PC-66 | E | **매입 효과를 만든 Coordinator/Implementer 결정**: 같은 Payment 다른 보류로 전체 대기 PAYMENT_HELD(C-5, BR-FIN-019), KRW 외 대기(I-5, BR-FIN-020), 재매입 세대 ≥ 1 취소 판정 개방(L-6, BR-FIN-040 — **시험 기대값 CC-4 → CC-8 이동**), 그 밖 재봉인 거부·같은 ID 재업로드·누적 초과 취소 대기·CANCEL 재매입·전송 보류·재매입 기한·REVIEW_REQUIRED 2인(BR-FIN-024·028·041·050·051·053·013) | FIN (a)-9·(e)-1·(e)-2·(e)-5·(f) Q-FIN-RG-08, `AcquisitionCandidateRule.kt:24-25`, `CancelCandidateRule.kt:48`, RULE-GOV P-6 | 일괄 확인(fail-closed 유지) / 항목별 재검토. 권장안 — 미결정: RG-08 "일괄 확인" | 돈 흐름 지연(매입 대기)·취소 매입 판정 기준. CC-4 → CC-8은 "정책·기대값을 바꿔 통과" 금지 원칙과 맞닿는 사례 | FIN 대장 확정 | P1 |
| PC-67 | E | **"매입 전송 완료" 근거에 RENAMED 포함**(ACQUIRING-001 I-9): rename 성공 응답 + 재접속 확인 실패도 TRANSMITTED. 정산지급방식 "라이브러리 송신 호출 성공만으로 전송 완료가 증명된다고 가정하지 않는다"와 어긋날 수 있다 | DC-P-06·§4.3·§6, `TransmissionOutcome.kt:32-40`(I-49 결과 마감 기준 포함) | (a) RENAMED 유지 (b) 정산 적격성은 VERIFIED_FINAL_PRESENT·ACK_HASH_MATCH만 (c) 전송 축 유지 + 정산 적격성은 별도 등급. 권장안 — 미결정: Q-DC-P-03 (c) | 확인되지 않은 전송이 선지급 근거가 될 수 있다(정산 구현 시) | SETTLEMENT-CONTRACT-001, PC-02와 함께 | P1 |
| PC-68 | E | **FINANCIAL 사실 전달 재시도 상한 없음·4xx 분류**(FIN-FACT-RELAY-001 I-2): 계약 코드 없는 4xx·401·5xx도 무한 재시도. MASTER→PAYMENT는 모든 4xx 격리, 가맹점 Webhook은 유한 재시도·GAVE_UP(D-WH-006) — D-ACQ-001 "MASTER→PAYMENT 패턴 재사용"과 다르게 동작 | WH (b)-2·(b)-3·(f)-3(WH-Q3), BR-WH-077 | I-2를 사용자 규칙으로 채택 / 상한 + 격리 도입. 권장안 — 미결정: 진단에 권장 없음(선택지만 제시) | 금융 사실 유실 방지 vs 적체 | WH 대장 확정 | P1 |
| PC-69 | E | **GAVE_UP 뒤 늦은 2xx를 DELIVERED로 전이**(WEBHOOK-001 rev 0.2 n-1): D-WH-006은 GAVE_UP을 "완료 아님·경보"로만 정했다 | WH (e)-2·(f)-2(WH-Q2), BR-WH-035, `MerchantWebhookDelivery.kt:76-84` | 현행(성공 단조) / GAVE_UP 유지 + 성공 시도 기록만. 권장안 — 미결정: 진단에 권장 없음 | GAVE_UP 경보가 조용히 사라질 수 있다 | WH 대장 확정 | P1 |
| PC-70 | E | **운영 홈 KPI 금액 정의**: 통화별 분리·UNKNOWN·진행 중 제외·TEST/LIVE 혼합 금지를 D-ADMIN-HOME-001이 "Coordinator 구현 기본값(사용자 확인 대상 아님)"으로 기록 | ADM (b)-5·(f)-7(BR-ADM-137), CAN (f) Q-CAN-9 | 현행 유지 + 사용자 확인 / 정의 변경. 권장안 — 미결정: Q-CAN-9 "현행 유지, 사용자 확인" | TEST/LIVE 혼합·UNKNOWN 금액 처리는 금융 의미 | ADM·CAN 대장 확정 | P1 |
| PC-71 | E | **개인정보 노출 범위의 Coordinator 해석**: 가맹점 목록 VIEW_MASKED 4역할(VIEWER·SECURITY_ADMIN 포함)이 사업자등록번호 원문을 감사 없이 봄(BRN1 L-5 "결정 범위 안" 해석), 종료된 대표자 KYC는 저장만·비응답(D-FLOW-009 적용 메모, BR-BIZ-301), ROLE_REACTIVATE 배제를 승인자까지 확대(BOOT1 H-A, BR-ADM-030 — 대장은 `확정`으로 둠) | ADM (a)-11·(b)-4·(f)-5, BIZ (f)-5 | D-PII-001 ①② 범위로 수용됐다는 해석을 사용자 확인 / 역할·감사 조정 | 개인정보 노출·권한 범위 | ADM·BIZ 대장 확정, FLOW 구현 | P1 |
| PC-72 | E | **MASTER 명세 해석으로 정한 상태·판정**: "첫 계약 승인" 정의(효력 전·철회 뒤 포함, C-14, BR-BIZ-067 — 가맹점 코드 불변 시점), Site 심사 개별 전이(084), 통화 활성화 소급 금지(172), 재개 "새 근거" 정의(162), 판정 순서(213), 사업체 구분·대표자 추가의 중요정보 취급(017)·중요정보 1인 처리(018, TBD-07 기본값) | BIZ (f)-6·(f)-7·(f)-8 | 확인 / 행별 수정 | 외부 노출 키 불변 시점·감사·승인 수준 | BIZ 대장 확정 | P2 |
| PC-73 | E | **취소·거래 운영 해석**: 승인 결과 미확정(AUTHORIZING·UNKNOWN) Payment 취소 불가(N-2, Q-CAN-3), Payment당 미해결 보정 취소 승인 1건(`DUPLICATE_UNRESOLVED`, I-32, Q-CAN-5), PAYMENT 기록 결정이 ADMIN 승인과 다를 때 "플래그 후 반영"(I-35, Q-CAN-6), 같은 approvalId 내용 불일치는 파생 충돌 거절(CN-32, Q-CAN-7), 거래 조회 역할(I-1)·정확 조회 기간 면제(I-3, D-TXOPS-001 "기간 필수" 문언과 다름, BR-CAN-089, Q-CAN-8), 가맹점 FAILED 응답 거절 세부 비노출(판정 11, BR-CAN-085), 업무 거절 ID 재사용 허용(n-8, BR-CAN-022) | CAN (a)-8·(b)-4·(b)-5·(c)-8·(f) Q-CAN-3·5~8 | 현행 유지(CAN 권장) / 항목별 수정. 권장안 — 미결정: Q-CAN-3 "거절 유지", 5~8 "현행 유지", 6 "플래그 후 반영" | 취소 가능 조건·운영자 경로 | CAN 대장 확정 | P2 |
| PC-74 | E | **결제·매입 해석**: D-AUTH-009 "질문 없이 기존 규칙 적용"(Q-AUTH-07 APPLIED, BR-PAY-098 — 내용은 CLAUDE.md "Demo 값은 운영값 아님"과 일치), D-AUTH-010 사용자 문구에 대한 AC1 §19.8 Coordinator 해석 정정(FORCED를 한도 판정 합에 포함, BR-PAY-089), D-ACQ-003 "매입사별 하루 1파일" → 명세·코드 "(매입사, 환경, 업무일)당 1파일"(환경 축 해석) | PAY (a)·(b)-2, FIN (c)-7 | 사용자 확인 / 결정 행 문언 보정 | 실질 충돌은 낮음(진단 판단), 출처 기록 정합 | PAY·FIN 대장 확정 | P4* |

---

## 6. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(GOV-DIAG-001 종합 — 진단 5종 + 규칙 대장 초안 6종 진단 절 통합) |
| 0.1a | 2026-11-25 | 독립 리뷰 반영: PC-38을 확정 부분(D-CANCEL1-003·D-AUTH-002·D-AUTH-009·CLAUDE.md)과 남은 질문으로 나누고 임시 운영자 확인 안을 변경안으로 표시·P0*, PC-04를 확정(BR-FIN-077·079)과 KPI 문언·Q-CALENDAR로 정리, PC-02 대안 (b)에 CLAUDE.md:41 변경 표시, PC-12 결정 문언 범위 축소·범위 확장 표시, PC-13 P0 근거 보강, PC-32 재제시 표시, §5.1 BR-ID 포함 문구 정정 |
