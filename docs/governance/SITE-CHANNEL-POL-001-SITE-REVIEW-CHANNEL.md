# SITE-CHANNEL-POL-001 Site 심사·운영 상태 분리와 채널 점유 — 정책 영향 분석

> 상태: **rev 0.1 — 영향 분석·정책 초안(사용자 확정 전)**.
> - 근거: **D-GOV-020 ②**(2026-11-26, Q-RC-14 답 — RULE-CONFIRM-001 §2 R5-Q14 권장안 미채택·정책 재정의). 함께 재검토: D-GOV-004 ①②, D-MS-002(a), D-MS-005, D-MD-008, D-MD-006, D-MOPS-004·009 ③·010 ②.
> - 이 문서는 소스·시험·DB migration·대장(`docs/rules/BIZ.md`)·`docs/DECISIONS.md`·명세를 바꾸지 않는다. §6 대장 변경안은 Coordinator가 사용자 확정 뒤 반영한다. **정책 확정 전 소스·DB 변경 금지**(D-GOV-020 ② 원문).
> - 기준: `main` HEAD `45d18d2`(작업 트리의 다른 문서 변경은 읽지 않았다). 작성 2026-11-26 KST. 시험은 **실행하지 않았다** — 시험 이름은 코드에 있는지만 확인했다. Demo DB 데이터는 조회하지 않았다(§5 '미확인').
> - 표기: **[사실]** 코드·문서로 확인, **[해석]** Coordinator/작성자 해석(사용자 확인 필요), **결정 필요** 사용자 답이 필요한 항목.

**개정 이력**
- **0.1** (2026-11-26): 첫 작성.
- **0.1a** (2026-11-26, 독립 리뷰 CHANGES_REQUIRED 반영 — 이 문서만 변경. 사실 대조는 리뷰가 모두 맞다고 판정):
  - M-1: BR-BIZ-351(확정)을 **심사 축 안의 해제 조건**으로 한정했다(운영 정지·정지 Site 처리로는 해제 안 함). Site 종료로 끝나는 점유(BR-BIZ-354·358·§4.2)와 모순을 없앴고, 범위 한정은 §1 [해석]에 적었다.
  - M-2: 확정 행 081·083·086·087·088을 제안 행으로 대체해 확정 의미가 강등되던 문제를 고쳤다. 원래 확정 부분을 **확정 행 BR-BIZ-367~372**로 떼어 냈고, §6.1 상태 변경은 이 확정 행을 가리킨다. 제안 행 355~358은 새 모델에서만 생기는 부분만 남겼다.
  - M-3: 승인 시작 경로를 추가했다(`AuthorizationTermsEvaluator.kt:71` A1 표, `:89` `EntitlementEvaluator.evaluate` 재호출) — §2.4, BR-BIZ-363 문장, §4.3, §5 시험.
  - M-4: §5 Snapshot을 고쳤다 — 기존 필드 재사용을 배제하고 schemaVersion 5 + PAYMENT 먼저 배포로 한정했다(모르는 enum = 계약 위반 `EntitlementSnapshot.kt:343,365` → Merchant 전체 fail-closed, `Lifecycle` enum 공유 영향, `reviewStatus=SUSPENDED` 재사용은 P1 위반).
  - L-1: BR-BIZ-353의 '(append-only)'를 뺐다(심사 결정 append-only는 확정 행 367, 운영·점유 이력 append-only는 제안 행 361). L-2: 대체 사슬을 §3.2·§6.1·§6.5에서 같게 맞췄고, BR-BIZ-354에 '(정지 후 종료는 BR-BIZ-365)'를 붙였다. L-4: §5에 `SiteWebHostEvaluator` '해제된 Site 제외'(심층 방어)를 추가했다.
  - 질문 Q-SCP-1~3은 이미 사용자에게 보였으므로 내용을 바꾸지 않았다.

---

## 1. 사용자 정책 문장(D-GOV-020 ② 원문을 번호로 나눔)

| # | 문장 |
|---|---|
| P1 | Site의 **심사 상태와 운영 상태를 분리**한다. |
| P2 | **심사 거절과 운영 정지는 다른 업무 행위**다. |
| P3 | **운영 정지 중에는 채널 점유를 유지**하고 **신규 Site 신청을 제한**한다. |
| P4 | **최초 심사 거절 시에만** 채널 점유를 해제하고 **같은 가맹점의 재신청**을 허용한다. |
| P5 | 다른 가맹점의 채널 재사용은 **관리자 예외 승인 + 소유권 확인**으로 허용한다. |
| P6 | **심사·운영·채널 점유 이력**을 보존한다. |
| P7 | BR-BIZ-084·D-GOV-004와의 충돌을 재검토한다. |
| P8 | 상태 전이표·채널 점유 정책·재신청 조건·예외 승인 조건을 명시한다. |
| P9 | 기존 도메인 모델·정책 영향 분석을 먼저 하고, **정책 확정 전 소스·DB를 바꾸지 않는다**. |

해석이 필요한 말(§4·§7에서 다룬다):
- **[해석] '운영 정지' = 지금의 Site 심사 값 `SUSPENDED`**. 현재 Site에 '정지'라는 개념은 심사 enum의 `SUSPENDED`뿐이다(§2.1). P1·P2에 따라 이것을 심사 축에서 빼서 운영 축으로 옮기는 것으로 읽었다.
- **[해석] '최초 심사 거절'** — 후보 셋: ⓐ Site 단위로 승인 이력 없이 받은 첫(그리고 유일한) 심사 결정이 거절인 경우(PENDING→REJECTED) ⓑ 같은 가맹점·같은 채널의 **첫 신청**이 거절된 경우(재신청 Site가 다시 거절되면 해제 안 함) ⓒ 승인 뒤 '재심사'에서 거절(지금 `SUSPENDED→REJECTED`)은 '최초'가 아님. → **Q-SCP-1**.
- **[해석] P4 '최초 심사 거절 시에만'의 범위(rev 0.1a)** — '에만'은 **심사 축 안의 해제 조건**을 한정하는 말로 읽었다. 심사 축에서는 최초 심사 거절만 점유를 풀고, 운영 정지나 정지 Site에 대한 처리(지금의 정지→거절)로는 풀지 않는다. **Site 종료로 끝나는 점유**(D-GOV-004 ② — 종료 채널의 같은 가맹점 재사용 허용)는 운영 축의 결과라 P4가 막지 않는다고 봤다(BR-BIZ-354·358, §4.2). 종료까지 막는 뜻이라면 D-GOV-004 ②와 충돌하므로 사용자 확인 대상이다(정지 후 종료는 Q-SCP-2 ⓑ).
- **[해석] '신규 Site 신청 제한'** — 같은 채널의 신규 Site만인지, 그 가맹점의 모든 신규 Site인지. → **Q-SCP-2**.
- **[해석] '채널 재사용'(P5)** — 해제된 채널(P4)·종료된 Site 채널(D-GOV-004 ②) 모두에 적용하고, 지금 점유 중인 채널(심사 대기·운영 중·운영 정지)은 예외 승인 대상이 아니라고 읽었다. → **Q-SCP-3**.

---

## 2. 현재 모델 사실(file:line)

### 2.1 Site 상태 — 축은 둘이지만 '정지'가 심사 축에 있다

| 항목 | 사실 | 근거 |
|---|---|---|
| 생명주기 | `MerchantSite.lifecycle` = `Lifecycle { ACTIVE, TERMINATED }`. 등록 시 ACTIVE. | `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/MerchantSite.kt:10,21-24`, `…/domain/Lifecycle.kt:7`, DB CHECK `V1__master_core.sql:79` |
| Site 종료 명령 | **없음**. runtime 권한이 `merchant_site`에 SELECT·INSERT뿐(주석 "종료는 MASTER-LIFECYCLE-001"). TERMINATED Site는 지금 생길 경로가 없다. | `apps/master-api/src/main/resources/db/runtime-privilege/master/V2__master_runtime_privileges.sql:19-20`, `harness/tasks.json` MASTER-LIFECYCLE-001(BACKLOG) |
| 심사 상태 | `SiteReviewStatus { PENDING, APPROVED, REJECTED, SUSPENDED }` — **'정지'가 심사 값**. 결정은 append-only 표, 현재 = seq 최대, 기록 없으면 PENDING. | `…/domain/SiteReviewDecision.kt:9,11-26,41`, `V3__entitlement_snapshot.sql:141-156`(CHECK `:155`), 권한 SELECT·INSERT `runtime-privilege/master/V4__entitlement_runtime_privileges.sql:19` |
| 심사 전이(BR-BIZ-084) | PENDING→APPROVED·REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED·REJECTED, REJECTED 종결. 같은 상태·PENDING 결정 거절. APPROVED는 생명주기 ACTIVE Site만. | `SiteReviewDecision.kt:33-38,56-57`; 명세 `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md` §4.4(`:180` — "개별 전이는 출처에 없어 좁게 골랐다(관례)") |
| 등록자 금지 | 근거 경로 APPROVED(PENDING·SUSPENDED 시작 모두)는 Site 등록자 금지, 중지 실행자는 허용(D-MOPS-010 ②). | `SiteReviewDecision.kt:61-77` |
| 명령 경로 | ① 1인 거절 `rejectSiteReview`(REJECTED 고정) ② 2인 근거 승인 `approveSiteReviewWithEvidence`(APPROVED 고정) ③ 근거 없는 `decideSiteReview` — APPROVED는 Demo seed 전용, 호출자는 seed뿐(ArchUnit). **SUSPENDED를 만드는 관리 경로는 없다**(B5 미구현). 세 경로 모두 결정 행·이력·revision +1·Snapshot 발행을 같은 MASTER TX에서 한다. | `…/application/MerchantEntitlementService.kt:326-356`(공통 본문 `:361-385`), `…/application/DemoMerchantSeedService.kt:197`, `docs/repository/MERCHANT-OPS-002-MERCHANT-ONBOARDING.md:426`(C-40 채널 분리), `:54`(B5 개요), `:832`([해석] Site SUSPENDED = 안전 방향 1인 → B5) |
| ADMIN API | `POST /api/merchant-registrations/site-review-rejections`(1인), 2인 승인은 승인함 `MERCHANT_SITE_REVIEW_APPROVAL`. MASTER 쪽 `/site-review-rejections`·`/site-review-approvals`. | `apps/internal-admin/src/main/kotlin/com/paycore/admin/merchantops/adapter/web/MerchantRegistrationController.kt:56-58`, `apps/master-api/…/adapter/web/admin/MerchantActivationController.kt:49-51,61-63` |
| ADMIN 화면 | 거절 폼은 `lifecycle == ACTIVE ∧ (심사 결정 없음 ∨ SUSPENDED)`에 표시 — **정지 Site를 '심사 거절'할 수 있게 설계돼 있다**(지금은 SUSPENDED가 생기지 않아 실제로는 안 보임). 활성화 조건 ② '대표 Site 심사 승인'. | `apps/internal-admin/frontend/src/lib/merchantDetailLocks.ts:51-52`, `…/components/MerchantActivationArea.tsx:81,393,412` |
| Site 심사 pinned ABA | B5 Site 중지 착수 전 조건: 상세 `latestReview.seq`·ADMIN pinned `reviewSeq` 비교(L-24). | `MERCHANT-OPS-002…md:495-496` |
| Assignment 상태 | `SiteAssignmentStatus { ACTIVE, SUSPENDED, ENDED }` — Site 운영 상태와 다른 축(MID–Site 관계). | `…/domain/SiteAssignment.kt:9,26-27` |

### 2.2 채널 점유 키 계산

| 항목 | 사실 | 근거 |
|---|---|---|
| DB 생성 열 | `active_channel_key = IF(lifecycle = 'ACTIVE', CONCAT(channel_type,'|',channel_identifier), NULL) STORED` + `UNIQUE(active_channel_key)` — **생명주기만 본다**. 심사 결정은 다른 표라 생성 열이 볼 수 없다. | `V1__master_core.sql:64-65,70`(주석 `:56-57` "예외 승인 기록은 아직 없다(후속)") |
| 앱 사전 조회 | `findActiveByChannel`: `channelType·channelIdentifier·lifecycle = 'ACTIVE'` — 심사 상태 조건 없음. | `…/adapter/persistence/MerchantSiteJpaRepository.kt:8-12` |
| 등록 검사 | Merchant 잠금 → `findActiveByChannel` 있으면 `SITE_CHANNEL_DUPLICATE` → Site INSERT·revision +1·Outbox·Snapshot 발행(한 TX). 가맹점 구분 없음(같은·다른 가맹점 모두 거절). | `…/application/MerchantOnboardingService.kt:121-149`(검사 `:131`) |
| 결과 | **[사실]** REJECTED Site도 생명주기 ACTIVE라 채널을 계속 점유 → D-GOV-004 ① **미시행**(GOV-MER-001 GM-02). 종료 Site 채널은 코드상 누구나 재사용 가능하나 종료 명령이 없어 발생 경로 없음(BIZ.md 진단 `:353`). 예외 승인 기록 표·명령 없음. | `docs/governance/GOV-MER-001-MERCHANT-CRUD-CONSISTENCY.md` GM-02·MF-1, `docs/rules/BIZ.md:316,353` |

### 2.3 재신청 경로

- [사실] 재신청 = **새 Site 등록**(D-MS-005, `SiteReviewDecision.kt:29`). REJECTED Site는 재심사 불가(전이 없음).
- [사실] 같은 채널로 새 Site를 등록하면 거절 Site가 점유 중이라 `SITE_CHANNEL_DUPLICATE` — **재신청 경로가 사실상 없다**(`MerchantOnboardingService.kt:131`, BIZ.md 진단 `:316`).
- [사실] 가맹점 상세의 'Site 추가' 진입 없음(GOV-MER-001 GM-01·MF-2, 결정 완료·미시행).
- [사실] 이전 거절 Site와 새 Site를 잇는 기록(재신청 참조) 없음.

### 2.4 결제 허용에 쓰이는 상태(MASTER Snapshot → PAYMENT)

| 단계 | 사실 | 근거 |
|---|---|---|
| Snapshot 조립 | `sites[] = SiteState(siteId, lifecycle, reviewStatus, channel)`, 결정 없으면 PENDING. 직렬화 키 `lifecycle`·`reviewStatus`·`channel`. schemaVersion 4. | `…/domain/EntitlementSnapshotAssembler.kt:32`, `…/domain/EntitlementSnapshot.kt:23,36,164`(MASTER 버전 상수 `ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION = 4`는 `:23`) |
| PAYMENT 파싱 | `SiteEntry(siteId, lifecycle, reviewStatus, channel?)`, 필드 집합을 `requireOnly`로 엄격 검사 — **새 필드는 schemaVersion 올림이 필요**. PAYMENT도 `SiteReviewStatus` 4값 enum 보유. | `apps/payment-api/src/main/kotlin/com/paycore/payment/entitlement/domain/EntitlementSnapshot.kt:24,51,364-365` |
| 결제 허용 판정 | F1 `lifecycle != ACTIVE` → NotFoundOrForbidden(SITE_TERMINATED). E3 `reviewStatus != APPROVED` → `METHOD_NOT_ENABLED`(내부 SITE_REVIEW). 순서 F1 → E1 → E2 → **E3** → E4 … (BR-BIZ-213). | `…/payment/entitlement/domain/EntitlementEvaluator.kt:29,43,76-77,93` |
| 사용처 | ① 결제 생성(`CreatePaymentService`)·결제창(`CheckoutPageService`)이 Eligibility를 쓴다. ② **승인 시작**: `AuthorizationTermsEvaluator`가 A1 단계에서 권리 F1·E1~E5를 **다시 평가**한다(`EntitlementEvaluator.evaluate` 재호출 — 생성 때 판정을 대신하지 않음). 그래서 생성 뒤 Site가 미승인이 되면 REQUESTED 건의 승인 시작도 막힌다. 취소 경로는 Site 심사 상태를 보지 않는다. | `…/payment/payment/application/CreatePaymentService.kt`, `CheckoutPageService.kt`(`PaymentEligibilityPort` 사용 파일 목록), `…/payment/entitlement/domain/AuthorizationTermsEvaluator.kt:71`(A1 표),`:89`(재호출) |
| 결제창 host 판정 | `SiteWebHostEvaluator`는 **생명주기만** 본다(심사 상태 무관). | `…/entitlement/domain/SiteWebHostEvaluator.kt:46`, 사용 `…/application/CheckoutTokenIssuanceService.kt`(AUTHN-001 §9.4) |

---

## 3. 정책 vs 현행 차이표

### 3.1 정책 문장별

| # | 정책 | 현행 | 차이 | 영향 대상 |
|---|---|---|---|---|
| P1 | 심사·운영 상태 분리 | '정지'(SUSPENDED)가 심사 enum 값. 운영 축은 생명주기 ACTIVE/TERMINATED뿐 | **모델 변경** — 심사 4값 → 3값(PENDING/APPROVED/REJECTED), 운영 축에 정지 추가 | Domain·V3 CHECK·Snapshot·PAYMENT enum·ADMIN 표시 |
| P2 | 거절 ≠ 정지 | `SUSPENDED→REJECTED` 허용(`SiteReviewDecision.kt:36`), 화면 거절 폼이 SUSPENDED에 열림(`merchantDetailLocks.ts:52`) | **정지 Site를 거절하는 경로 제거** — 정지 Site 마감은 운영 축(종료)으로만 | Domain 전이·ADMIN 폼·시험 기대값 |
| P3-a | 정지 중 점유 유지 | 정지 Site도 생명주기 ACTIVE라 점유 유지 | 결과 같음. 다만 지금은 정지→거절로 점유를 풀 수 있는 설계(D-GOV-004 ① 시행 시) — P2로 막힘 | 점유 판정 |
| P3-b | 정지 중 신규 Site 신청 제한 | 같은 채널 신청은 중복으로 막힘. 다른 채널 신청은 제한 없음 | 범위 [해석] 필요(Q-SCP-2). 가맹점 단위 제한이면 **새 검사 추가** | 등록 명령 |
| P4 | 최초 심사 거절만 해제, 같은 가맹점 재신청 | 거절도 점유 유지(미시행). 재신청 경로 없음 | **점유 판정에 심사 결과 반영 + 가맹점 한정 해제** | DB 생성 열/점유 표·사전 조회·등록 명령 |
| P5 | 다른 가맹점 = 예외 승인 + 소유권 확인 | 예외 승인 기록·명령 없음. 종료 채널은 코드상 무조건 허용(경로 없음) | **새 기록·새 2인 승인 종류·소유권 확인 근거** | MASTER 표·ADMIN 승인함 |
| P6 | 이력 보존 | 심사 결정은 append-only. 운영 정지·종료·점유 변경 이력 표 없음 | **운영 이력·점유 이력 기록 추가** | migration |
| P7 | BR-BIZ-084·D-GOV-004 재검토 | — | §3.2 | 대장·DECISIONS 역참조 |

### 3.2 기존 결정·규칙 문장별 충돌·대체

| 기존 | 문장 | 새 정책과의 관계 | 처리안 |
|---|---|---|---|
| **BR-BIZ-084**(제안) | APPROVED→SUSPENDED, SUSPENDED→APPROVED·REJECTED 포함 전이 | **충돌**(P1·P2): SUSPENDED는 심사 값이 아니다. SUSPENDED→REJECTED는 '정지를 거절로 바꾸는' 행위라 P2 위반 | `대체됨→BR-BIZ-355`(심사 전이, 제안). 운영 전이는 BR-BIZ-356(제안) |
| **BR-BIZ-083**(확정, D-MS-002(a)) | 심사 상태 PENDING/APPROVED/REJECTED/SUSPENDED | **부분 대체**(P1): 상태 집합에서 SUSPENDED가 운영 축으로 이동. 'PENDING 시작·append-only·현재 = 마지막 결정'은 유지 | `대체됨→BR-BIZ-367`(확정 부분 — append-only·PENDING 시작·현재 = 마지막 결정). 새 상태 집합은 BR-BIZ-348(확정)·355(제안) |
| **D-GOV-004 ①** | 심사 거절 Site는 채널 점유 해제(채널 중복 검사에서 **제외** — 글자 그대로는 전역 해제), 같은 주소로 새 Site 재신청 가능 | **좁혀짐**(P4·P5): ⓐ 해제 조건이 '심사 거절' → **'최초 심사 거절'** ⓑ 해제 효과가 전역 → **같은 가맹점 재신청만**, 다른 가맹점은 예외 승인 + 소유권 확인. [해석] Q-SCP-1 ⓐ안(승인 이력 없는 Site의 거절 = 최초)을 고르면 P2로 SUSPENDED→REJECTED가 없어지므로 '심사 거절'과 '최초 심사 거절'의 대상이 같아져 ⓐ는 실질 변화가 없고 ⓑ만 남는다 | D-GOV-004 ①에 '[변경: D-GOV-020 ② — 해제 효과는 같은 가맹점 재신청만, 최초 심사 거절에 한함]' 역참조. 대장 새 행 BR-BIZ-351 |
| **D-GOV-004 ②**(Q-MD-18) | 종료 Site 채널의 다른 가맹점 재사용은 **예외 승인(근거 + 2인)** 없으면 거절, 같은 가맹점 재사용 허용 | **보강·확장**(P5): ⓐ 예외 승인 조건에 **소유권 확인** 추가 ⓑ 적용 대상이 '종료 Site 채널' → '종료 + 최초 거절로 해제된 채널' ⓒ '근거 + 2인'은 새 정책이 말하지 않으므로 **유지**(재질문 안 함) ⓓ 같은 가맹점 재사용 허용은 유지 — 단 '정지 상태에서 종료'된 경우는 P3 우회 가능성 있어 Q-SCP-2 ⓑ | D-GOV-004 ②에 '[보강: D-GOV-020 ② — 소유권 확인 추가, 해제 채널에도 적용]' 역참조. 대장 BR-BIZ-352·354·360 |
| BR-BIZ-082(미결 — D-GOV-004 ② 반영 대기) | 종료 Site 채널의 다른 가맹점 등록 | P5로 조건 보강 | `대체됨→BR-BIZ-352`(확정). 같은 가맹점·2인 근거는 BR-BIZ-354(확정) |
| BR-BIZ-081(확정, D-MD-008) | **활성** 채널 식별값 중복 금지(예외는 승인 기록 — 후속) | '활성'의 뜻이 '생명주기 ACTIVE' → **'점유 중'**(BR-BIZ-358)으로 바뀜. 동시 등록 하나만 성공은 유지 | `대체됨→BR-BIZ-372`(확정 부분 — 전역 중복 금지·동시 등록 하나만). '점유' 정의는 BR-BIZ-358(제안) |
| BR-BIZ-085(확정, D-MS-005) | REJECTED 종결, 재신청은 새 Site | **유지**. P4가 '재신청 가능한 경우'를 좁힘 | 유지 + 새 행 BR-BIZ-359가 참조 |
| BR-BIZ-086(확정, D-MS-002(a)) | 결제는 심사 APPROVED에서만 | **보강**: 결제 허용 = 심사 APPROVED **∧ 운영 ACTIVE**. 응답 코드 매핑은 미정(§4.3 메모) | `대체됨→BR-BIZ-371`(확정 부분 — 심사 APPROVED에서만·응답 매핑). 운영 ACTIVE 조건은 BR-BIZ-357(제안) |
| BR-BIZ-087(확정) | Site 심사 APPROVED(**재승인 포함**) 2인, REJECTED 1인 | '재승인'(SUSPENDED→APPROVED)이 운영 재개로 이동. 인원 규칙 자체는 유지 | `대체됨→BR-BIZ-368`(확정 부분 — 등록 1인·심사 APPROVED 2인·REJECTED 1인). 재승인 부분은 BR-BIZ-370(확정) |
| BR-BIZ-088(확정, D-MOPS-010 ②) | SUSPENDED→APPROVED 재승인도 원 Site 등록자 금지, 중지 실행자 허용 | 대상 행위가 '운영 재개'로 이름만 바뀜. 규칙 의미 유지 [해석] | `대체됨→BR-BIZ-369`(확정 — 등록자 금지). 정지 Site 재승인(운영 재개) 등록자 금지·정지 실행자 허용은 BR-BIZ-370(확정) |
| D-MD-006 | (Business·Merchant·MID) 차단은 신규 승인만 막고 조회·취소·정산 허용 | Site 운영 정지에 같은 원칙 준용 [해석] — 사용자 원문에 없음 | 새 행 BR-BIZ-363 `제안` |
| D-MD-005 | Assignment 상태 ACTIVE/SUSPENDED/ENDED | Site 운영 상태와 별개 축. 충돌 없음 | 유지(혼동 방지 메모만) |

---

## 4. 정책 초안

용어(가칭 — 이름은 가역 구현 세부): **심사 상태**(PG가 이 Site를 결제 채널로 받아들였는가), **운영 상태**(받아들인 Site를 지금 쓰게 하는가·끝냈는가), **채널 점유**(그 채널 식별값으로 다른 Site를 만들 수 없게 하는 상태), **채널 보유 가맹점**(점유가 끝난 뒤에도 같은 가맹점 재사용·다른 가맹점 예외 판정의 기준이 되는 마지막 가맹점).

### 4.1 심사 상태 전이표(제안)

| 현재 | 행위 | 다음 | 인원 | 조건 | 채널 점유 효과 |
|---|---|---|---|---|---|
| (등록) | Site 등록 | PENDING | 1인(OPERATOR) | 채널 미점유, 가맹점 ACTIVE, §4.5 재신청 조건 | 점유 시작(이력 기록) |
| PENDING | 심사 승인 | APPROVED | 2인, 등록자 금지(BR-BIZ-088) | 운영 상태 ACTIVE | 점유 유지 |
| PENDING | 심사 거절 | REJECTED | 1인 | 운영 상태 ACTIVE | **최초 심사 거절 → 점유 해제, 보유 가맹점 = 이 가맹점**(Q-SCP-1) |
| APPROVED | — | — | — | **심사 축 종결**(승인 뒤 문제는 운영 축으로만 — P2) | — |
| REJECTED | — | — | — | 종결(D-MS-005). 재신청은 새 Site | — |
| 모든 상태 | 같은 상태·PENDING 결정 | 거절 | — | `SITE_REVIEW_TRANSITION_NOT_ALLOWED` | 변화 없음 |

- 결정은 append-only(사유·근거·행위자·시각·요청 기록), 현재 = 마지막 결정(BR-BIZ-083 유지).
- **삭제되는 전이**: APPROVED→SUSPENDED, SUSPENDED→APPROVED, SUSPENDED→REJECTED(운영 축으로 이동 또는 폐지).
- [해석] 승인 뒤 '재심사'(정기 재심사 등) 행위는 지금 정의돼 있지 않다. 필요하면 운영 정지 사유로 처리한다(Q-SCP-1 대안 ⓒ에서 따로 다룸).

### 4.2 운영 상태 전이표(제안)

| 현재 | 행위 | 다음 | 인원 | 조건 | 채널 점유 효과 |
|---|---|---|---|---|---|
| (등록) | — | ACTIVE(운영 가능) | — | 기존 생명주기 ACTIVE와 같은 시작값 | — |
| ACTIVE | 운영 정지 | SUSPENDED | 1인 즉시(안전 방향, D-MOPS-004) | **심사 APPROVED만**([해석] — 심사 중 문제는 심사 거절로) | **점유 유지**(P3) |
| SUSPENDED | 운영 재개 | ACTIVE | 2인, 원 Site 등록자 금지·정지 실행자 허용(D-MOPS-010 ②) | 정지 사유 해소 근거 | 점유 유지 |
| ACTIVE·SUSPENDED | Site 종료 | TERMINATED | 1인 즉시(안전 방향) | 비가역(D-MD-006 준용) | **점유 종료, 보유 가맹점 = 이 가맹점** — 같은 가맹점 재사용 허용(D-GOV-004 ②), **정지에서 종료된 경우는 Q-SCP-2 ⓑ** |
| TERMINATED | — | — | — | 종결. 재사용은 새 Site | — |

- 운영 결정도 append-only 이력(행위·사유·근거·행위자·시각). 정지는 **여러 건 독립 차단 기록**(D-MD-006 방식)으로 할지, 단일 상태로 할지는 Coordinator 구현 결정(사용자 원문 없음 — 단일 상태가 기존 Site 심사 SUSPENDED와 같음).
- REJECTED Site의 운영 상태: ACTIVE로 남겨도 결제·점유에 영향이 없다(점유는 심사 결과로 이미 해제). 정리를 위한 종료는 허용(점유 효과 없음).

### 4.3 두 축 조합 매트릭스(제안)

| 심사 \ 운영 | ACTIVE | SUSPENDED | TERMINATED |
|---|---|---|---|
| **PENDING** | 점유 **O** / 같은 채널 신규 신청: 같은·다른 가맹점 모두 **불가**(중복) / 결제 **X**(E3) | **조합 없음**(정지는 APPROVED만) | 점유 **X**(신청 철회·종료) / 같은 가맹점 재신청 **O** / 다른 가맹점 **예외 승인 + 소유권 확인** / 결제 X |
| **APPROVED** | 점유 **O** / 신규 신청 불가(중복) / 결제 **O**(다른 조건 충족 시 — BR-BIZ-139) | 점유 **O**(유지) / **신규 Site 신청 제한**(범위 Q-SCP-2) / 다른 가맹점 예외 **대상 아님** / 새 결제 생성 **X**·승인 시작 **X**(REQUESTED 건도 — A1 재평가) / AUTHORIZING·UNKNOWN 건의 결과 조회·대사와 취소·정산 영향 없음([해석] D-MD-006 준용, BR-BIZ-363) | 점유 **X** / 같은 가맹점 재사용 **O**(D-GOV-004 ② — 정지 후 종료는 Q-SCP-2 ⓑ) / 다른 가맹점 예외 승인 + 소유권 확인 / 결제 X |
| **REJECTED** | 점유 **X**(최초 거절 해제 — Q-SCP-1) / 같은 가맹점 재신청 **O**(새 Site) / 다른 가맹점 예외 승인 + 소유권 확인(Q-SCP-3 ⓐ) / 결제 X | 조합 없음 | 점유 X(변화 없음) / 재신청 조건은 REJECTED×ACTIVE와 같음 / 결제 X |

- **점유 판정식(제안)**: `점유 = 운영 ≠ TERMINATED ∧ 심사 ≠ REJECTED(해제 대상 거절)`. 채널당 점유 Site는 최대 1개(DB 이중 강제 유지).
- **결제 허용(제안)**: `심사 = APPROVED ∧ 운영 = ACTIVE` ∧ 기존 조건. PAYMENT 응답: 운영 정지를 심사 미승인과 같은 `METHOD_NOT_ENABLED`(내부 detail 분리)로 둘지, D-MD-019처럼 `MERCHANT_SUSPENDED`로 둘지는 **결정 필요(후속 — 외부 계약 의미)**. 이번 3개 질문에서는 빼고 구현 명세에서 묻는다. 답 전 fail-closed(어느 쪽이든 차단).

### 4.4 채널 점유 정책(제안)

1. 채널 점유는 **Site 등록 TX에서 시작**하고, 최초 심사 거절·Site 종료 결정과 **같은 MASTER 로컬 TX**에서 끝난다(결정 행·이력·revision +1·Outbox·Snapshot과 같은 TX — BR-BIZ-215).
2. 점유가 끝나도 **채널 보유 가맹점**을 남긴다. 다음 등록 판정: 보유 가맹점과 같으면 §4.5, 다르면 §4.6 예외 승인 필요.
3. 점유 이력(시작·종료·사유 Site 결정 참조·예외 승인 참조·행위자·시각)은 append-only(P6).
4. 앱 사전 조회와 DB 유일 제약의 **이중 강제를 유지**한다. 경합(같은 채널 동시 등록, 거절과 재신청 경합, 예외 승인 사용과 원 가맹점 재신청 경합)은 하나만 성공.
5. 운영 정지는 점유를 바꾸지 않는다. 정지 Site를 거절로 바꿔 점유를 푸는 경로는 없다(P2).

### 4.5 재신청 조건(같은 가맹점)

| 경우 | 허용 | 조건 |
|---|---|---|
| 직전 Site가 **최초 심사 거절** | O | 새 Site(D-MS-005)·처음부터 심사(PENDING)·이전 거절 Site 참조 기록·가맹점 ACTIVE·그 채널 점유 Site 없음. 반복 횟수 제한은 **결정 필요(Q-SCP-1 ⓑ)** |
| 직전 Site가 **종료**(정지 이력 없음) | O | D-GOV-004 ② — 위와 같은 등록 조건 |
| 직전 Site가 **정지 상태에서 종료** | **결정 필요(Q-SCP-2 ⓑ)** | 권장: 예외 승인 필요(정지 사유 우회 방지) |
| 같은 채널 Site가 **운영 정지 중** | X | 점유 유지(P3). 재개로만 |
| 같은 채널 Site가 심사 대기·운영 중 | X | 중복 |
| 다른 채널 신규 Site, 가맹점의 다른 Site가 정지 중 | **결정 필요(Q-SCP-2 ⓐ)** | 권장: 허용(제한은 같은 채널만) |

### 4.6 예외 승인 조건(다른 가맹점의 채널 재사용)

| 항목 | 초안 | 근거·상태 |
|---|---|---|
| 대상 채널 | 점유가 끝난 채널(최초 거절 해제·종료)만. 점유 중(심사 대기·운영 중·운영 정지)은 대상 아님 | [해석] — Q-SCP-3 ⓐ |
| 인원 | 2인(OPERATOR 요청, APPROVER 승인, 요청자≠승인자) | D-GOV-004 ② '근거 + 2인' 유지(확정 — 재질문 안 함), D-ADMIN-003 |
| 근거 | 사유 + **소유권 확인 근거 참조** 필수 | P5(확정). 근거의 **종류·검증 방법은 결정 필요** — Q-SCP-3 ⓑ |
| 소유권 확인 방법 | WEB: 신청 가맹점이 지정 DNS TXT 또는 지정 경로 파일을 게시했음을 확인한 기록. APP: 스토어 개발자 계정 소유 증빙 | **결정 필요**(Q-SCP-3 ⓑ 권장안) |
| 원 보유 가맹점 처리 | 통지만(동의 불요) | **결정 필요**(Q-SCP-3 ⓒ) |
| 사용 범위 | 승인 1건 = (채널, 신청 가맹점) 1회 사용·소진, 승인 창 안에서만(창 길이는 승인함 공통 설정 — Demo값) | 제안(가역 구현 세부 + Demo값) |
| TX | 예외 소진·점유 시작·보유 가맹점 변경·새 Site 등록·이력·Outbox를 같은 MASTER TX | CLAUDE.md 핵심 규칙 |
| 이력 | 예외 요청·승인·거절·소진 append-only, 감사에 채널 식별값·양쪽 가맹점 코드 | P6, BR-BIZ-250(내부 키 화면 비표시) |

---

## 5. 영향 범위(구현 범위 목록만 — 이번에 고치지 않음)

**MASTER Domain**
- `SiteReviewStatus`에서 SUSPENDED 제거, `SiteReviewRules` 전이 축소(`SiteReviewDecision.kt:9,33-38`). 운영 상태·운영 결정 기록·전이 규칙 신설(생명주기 확장 또는 별도 값 — Coordinator 구현 결정). 채널 점유·보유 가맹점·예외 승인 Domain 규칙. 등록 명령(`MerchantOnboardingService.kt:121-149`)에 §4.5·§4.6 판정. 새 명령: 운영 정지·재개·Site 종료(MASTER-LIFECYCLE-001·MERCHANT-OPS-002 B5와 범위 정리), 예외 승인 실행.

**migration(새 버전만 — V1·V3 수정 금지)**
- `active_channel_key` 생성 열은 생명주기만 본다(`V1:64-65`). 심사 결과는 다른 표(`V3:141-156`)라 **어느 길이든 업무 schema migration 필요**: ⓐ `merchant_site`에 점유 상태 열(+ 생성 열 재정의) ⓑ 별도 채널 점유 표(현재 점유 UNIQUE + append-only 이력). 되돌리기 쉬운 결정이 아니므로 리뷰 대상(GOV-MER-001 MF-1과 같은 판단).
- 운영 결정 이력 표, 채널 보유·예외 승인 기록 표, `ck_merchant_site_review_decision_value`(`V3:155`)에서 SUSPENDED 제거 여부(기존 행 이관 방식에 따라).
- runtime 권한 Flyway privilege migration(`merchant_site` 지금 SELECT·INSERT — `V2:20`).
- **데이터 이관(미확인)**: Demo DB의 기존 REJECTED·SUSPENDED 결정 행 수를 조회하지 않았다. 앱 경로로는 SUSPENDED가 생기지 않는다(§2.1). REJECTED Site는 migration 시점에 점유가 풀린다(데이터 효과) — 이관 전 건수·가맹점 확인 필요.

**Snapshot·PAYMENT**
- **기존 필드 재사용 배제(rev 0.1a)**. ⓐ `sites[].lifecycle`에 새 값(예: SUSPENDED)을 넣으면 PAYMENT `fromJson`이 모르는 enum 값을 계약 위반으로 처리한다(`apps/payment-api/…/entitlement/domain/EntitlementSnapshot.kt:343`, Site 파싱 `:365`) → 그 Merchant 전체 Snapshot 거부(fail-closed). 받아들이게 바꿔도 F1 `lifecycle != ACTIVE`(`EntitlementEvaluator.kt:77`)가 정지를 NotFoundOrForbidden(SITE_TERMINATED, 404 계열)로 답하게 되고, PAYMENT `Lifecycle` enum(`EntitlementSnapshot.kt:16`)은 Business·Merchant·MID와 공유라 영향이 Site 밖으로 번진다. ⓑ `reviewStatus = SUSPENDED`를 운영 정지 표시로 계속 쓰는 것은 심사·운영 분리(P1) 위반이다.
- 따라서 운영 상태는 **새 필드(또는 새 값) + schemaVersion 5**로 싣고, **PAYMENT를 먼저 배포**(v5 해석 가능)한 뒤 MASTER가 v5를 발행한다. 필드 이름·모양은 Coordinator 구현 결정(통합 계약이라 명세·리뷰 대상).
- `EntitlementEvaluator.kt:93` E3 판정에 운영 상태 추가(같은 판정을 승인 시작 A1이 재호출 — `AuthorizationTermsEvaluator.kt:89`, 한 곳 수정으로 두 경로 반영)·응답 매핑(§4.3 메모 — 결정 필요 후속). 판정 순서 BR-BIZ-213 갱신.
- `SiteWebHostEvaluator.kt:46`(결제창 host)는 생명주기만 본다 — 운영 정지 Site의 결제창 진입 차단 위치 확인.
- 정비(심층 방어): `SiteWebHostEvaluator`에서 **점유가 해제된 Site(최초 거절)·운영 정지 Site를 host 후보에서 제외** — 해제된 채널을 다른 가맹점이 다시 쓰게 될 때 옛 Site의 host가 결제창 판정에 남지 않게 한다.
- MASTER·PAYMENT `SiteReviewStatus` enum 양쪽 변경, 권리 revision 매트릭스(BR-BIZ-215).

**ADMIN(API·화면)**
- 거절 폼 표시 조건에서 SUSPENDED 제거(`merchantDetailLocks.ts:51-52`, `MerchantActivationArea.tsx:393,412`).
- 심사 상태·운영 상태 두 칸 표시, 라벨(`lib/labels.ts`), 활성화 조건 ②에 운영 상태(`MerchantActivationArea.tsx:81`).
- 운영 정지(1인)·재개(2인 승인함)·종료(1인) 화면·API. Site pinned ABA 조건 L-24(`MERCHANT-OPS-002…md:495-496`)는 운영 결정 seq로 옮김.
- 예외 승인 새 승인 종류(ADMIN approval kind CHECK — `apps/internal-admin/src/main/resources/db/migration/admin/V11__merchant_ops_activation_approval.sql:15-20` 선례대로 새 migration), 소유권 확인 근거 입력.
- 가맹점 상세 'Site 추가'(GOV-MER-001 MF-2)와 재신청 안내(이전 거절 참조).

**시험(기대값 변경은 정책 확정·근거 기록 뒤)**
- 바뀌는 기대값: `SiteConfirmationAndReviewSpec`(M — `PENDING → APPROVED → SUSPENDED → APPROVED`·SUSPENDED 시작 재승인 등록자 금지 시험 `:118,158,165`), `EntitlementEvaluatorSpec`(P), `EntitlementSnapshotSpec`(M·P), `DemoSeedSnapshotGoldenSpec`(M, schemaVersion 바뀌면), frontend 거절 폼 시험.
- 새 시험: **정지 후 REQUESTED 건의 승인 시작 거절**(A1 재평가 — `AuthorizationTermsEvaluator.kt:89`)·정지 중 AUTHORIZING·UNKNOWN 건 결과 조회·대사와 취소 허용, 최초 거절 후 같은 가맹점 같은 채널 재신청 성공·다른 가맹점 `SITE_CHANNEL_DUPLICATE`, 정지 Site 거절 불가, 정지 중 점유 유지·신청 제한, 종료 후 같은 가맹점 재사용, 예외 승인 2인·소유권 근거 필수·1회 소진, **실제 MySQL 두 연결 경합**(같은 채널 재신청 vs 예외 사용, 거절 TX rollback 시 점유 그대로 — `MerchantOperationsConcurrencySpec.kt:62` 선례), runtime 권한 시험, revision 매트릭스, Snapshot 계약 버전 시험.

**문서**: MASTER-SNAPSHOT-001 §4.4, MERCHANT-OPS-002 B5·C-40·§9.27, GOV-MER-001 GM-02·MF-1(가)(나)·S-4, RULE-CONFIRM-001 R5-Q14 상태, `harness/tasks.json` MASTER-LIFECYCLE-001 openItems(Q-MD-18 → 답 완료 표시).

---

## 6. 대장 변경안(`docs/rules/BIZ.md` — Coordinator가 사용자 확정 뒤 반영)

원칙(rev 0.1a — M-2): 기존 `확정` 행은 **확정 행으로만** 대체한다. 기존 확정 의미는 새 확정 행(BR-BIZ-367~372)에 그대로 떼어 옮기고, 새 모델에서만 생기는 부분은 제안 행(355~363)에 둔다.

### 6.1 기존 행 상태 변경

| 기존 ID | 현재 상태 | 변경 | 메모 |
|---|---|---|---|
| BR-BIZ-081 | 확정 | `대체됨→BR-BIZ-372` | '점유' 정의는 358(제안) |
| BR-BIZ-082 | 미결(Q-MD-18, 반영 대기) | `대체됨→BR-BIZ-352` | 같은 가맹점·2인 근거는 354 |
| BR-BIZ-083 | 확정 | `대체됨→BR-BIZ-367` | 새 상태 집합은 348(확정)·355(제안) |
| BR-BIZ-084 | 제안 | `대체됨→BR-BIZ-355` | 운영 전이는 356 |
| BR-BIZ-086 | 확정 | `대체됨→BR-BIZ-371` | 운영 ACTIVE 조건은 357(제안) |
| BR-BIZ-087 | 확정 | `대체됨→BR-BIZ-368` | 재승인 부분은 370(확정) |
| BR-BIZ-088 | 확정 | `대체됨→BR-BIZ-369` | 재승인 부분은 370(확정) |
| BR-BIZ-085 | 확정 | 유지(변경 없음) | 359가 참조 |

### 6.2 새 행 — 확정(사용자 원문 D-GOV-020 ②·D-GOV-004 ②)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-348 | Site는 심사 상태와 운영 상태를 별개로 가지며, 심사 거절과 운영 정지는 다른 업무 행위다 | 확정 | 사용자 | D-GOV-020 ② | SITE-CHANNEL-POL-001 §4.1·§4.2 | 없음(미구현) |
| BR-BIZ-349 | 운영 정지 중인 Site는 채널 점유를 유지한다 | 확정 | 사용자 | D-GOV-020 ② | SITE-CHANNEL-POL-001 §4.4 | 없음(미구현) |
| BR-BIZ-350 | 운영 정지 중에는 신규 Site 신청을 제한한다(제한 범위는 BR-BIZ-365) | 확정 | 사용자 | D-GOV-020 ② | SITE-CHANNEL-POL-001 §4.5 | 없음(미구현) |
| BR-BIZ-351 | 심사 축에서 채널 점유는 최초 심사 거절 때만 해제되고 운영 정지나 정지 Site 처리로는 해제되지 않으며, 해제된 채널은 같은 가맹점이 새 Site로 재신청할 수 있다('최초'의 뜻은 BR-BIZ-364, Site 종료로 끝나는 점유는 BR-BIZ-354) | 확정 | 사용자 | D-GOV-020 ②, D-GOV-004 ①(좁힘), D-MS-005 | SITE-CHANNEL-POL-001 §1 [해석]·§4.4·§4.5 | 없음(미구현) |
| BR-BIZ-352 | 다른 가맹점이 채널을 재사용하려면 관리자 예외 승인과 소유권 확인이 모두 있어야 한다 | 확정 | 사용자 | D-GOV-020 ②, D-GOV-004 ②(보강), Q-MD-18 | SITE-CHANNEL-POL-001 §4.6 | 없음(미구현) |
| BR-BIZ-353 | Site 심사 결정·운영 결정·채널 점유 변경은 이력으로 보존한다(저장 방식은 BR-BIZ-361·367) | 확정 | 사용자 | D-GOV-020 ② | SITE-CHANNEL-POL-001 §4.4 | 없음(운영·점유 이력 미구현) |
| BR-BIZ-354 | 종료된 Site의 채널은 같은 가맹점이 재사용할 수 있고(정지 후 종료는 BR-BIZ-365), 다른 가맹점 재사용의 예외 승인은 근거와 2인 승인을 요구한다 | 확정 | 사용자 | D-GOV-004 ② | SITE-CHANNEL-POL-001 §4.2·§4.6 | 없음(종료 명령 미구현) |
| BR-BIZ-367 | Site 심사 결정은 사유·행위자를 가진 append-only 기록이고, 신규 Site는 결정 기록 없는 PENDING에서 시작하며, 현재 심사 상태는 마지막 결정이다 | 확정 | 사용자 | D-MS-002(a), D-MD-016 | MASTER-SNAPSHOT-001 §4.4 | SiteConfirmationAndReviewSpec(M)::PENDING 시작, 현재 = 마지막 결정 |
| BR-BIZ-368 | Site 등록은 OPERATOR 1인, Site 심사 APPROVED는 2인(요청자≠승인자), Site 심사 REJECTED는 1인이다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③ | MERCHANT-OPS-002 §9.12·§9B | MerchantActivationApprovalServiceSpec(A)::reject code는 2인 경로에서 400 NOT_ALLOWED; MerchantRegistrationServiceSpec(A)::Site 심사 거절 1인 명령 |
| BR-BIZ-369 | Site 등록자는 그 Site의 심사 승인자가 될 수 없다 | 확정 | 사용자 | D-MOPS-009 ③ | MERCHANT-OPS-002 §9.27 | SiteConfirmationAndReviewSpec(M)::APPROVER_IS_SITE_REGISTRANT PENDING; MerchantActivationApprovalServiceSpec(A)::Site 등록자 403 |
| BR-BIZ-370 | 정지된 Site를 다시 결제 가능하게 하는 승인(현행 SUSPENDED→APPROVED 재승인, 새 모델의 운영 재개)은 2인이며 원 Site 등록자는 승인자가 될 수 없고 정지 실행자는 금지 대상이 아니다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-010 ②(Q-MOPS-25) | MERCHANT-OPS-002 §9.27, SITE-CHANNEL-POL-001 §4.2 | SiteConfirmationAndReviewSpec(M)::APPROVER_IS_SITE_REGISTRANT SUSPENDED, 중지 실행자 허용(현행 심사 축 시험) |
| BR-BIZ-371 | 결제는 Site 심사 APPROVED에서만 허용하고, 미승인은 NotEligible METHOD_NOT_ENABLED(내부 로그에만 SITE_REVIEW 구분)로 답한다 | 확정 | 사용자 | D-MS-002(a) | MASTER-SNAPSHOT-001 §6.1 E3 | EntitlementEvaluatorSpec(P)::review 거절 METHOD_NOT_ENABLED 순서; EntitlementSnapshotSpec(M)::결정 없는 Site는 PENDING |
| BR-BIZ-372 | 점유 중인 채널 식별값(점유 정의는 BR-BIZ-358)은 같은 Merchant 안에서도, 다른 Merchant와도 중복 등록할 수 없고(예외는 BR-BIZ-352), 동시 등록도 하나만 성공한다 | 확정 | 사용자 | D-MD-008 | MASTER-DOMAIN-001 §2.2 | MerchantOnboardingServiceSpec(M)::다른 Merchant 활성 채널 거절; MerchantOperationsConcurrencySpec(M)::같은 채널 두 Merchant 1건만 |

### 6.3 새 행 — 제안(Coordinator 적용, 사용자 확인 전)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-355 | Site 심사 상태는 PENDING/APPROVED/REJECTED이고, 심사 전이는 PENDING→APPROVED·PENDING→REJECTED만이며 APPROVED·REJECTED는 심사 축에서 종결이다(인원·기록은 BR-BIZ-367~369) | 제안 | 적용 | D-GOV-020 ②, D-MS-002(a), D-MS-005 | SITE-CHANNEL-POL-001 §4.1 | 없음(현 시험은 4값 전이) |
| BR-BIZ-356 | Site 운영 상태는 ACTIVE/SUSPENDED/TERMINATED이고, 정지는 심사 APPROVED Site에만 1인 즉시, 종료는 1인 즉시·비가역이다(재개 인원은 BR-BIZ-370) | 제안 | 적용 | D-GOV-020 ②, D-MOPS-004, D-MD-006 | SITE-CHANNEL-POL-001 §4.2 | 없음(미구현) |
| BR-BIZ-357 | BR-BIZ-371에 더해 결제는 Site 운영 ACTIVE일 때만 허용한다 | 제안 | 적용 | D-GOV-020 ② | SITE-CHANNEL-POL-001 §4.3 | 없음(미구현) |
| BR-BIZ-358 | 채널은 운영이 종료되지 않았고 최초 심사 거절로 해제되지 않은 Site가 점유하며, 한 채널의 점유 Site는 하나뿐이다 | 제안 | 적용 | D-GOV-020 ②, D-GOV-004 ②, D-MD-008 | SITE-CHANNEL-POL-001 §4.4 | 없음(현행은 생명주기 기준) |
| BR-BIZ-359 | 같은 가맹점의 재신청은 새 Site로 처음부터 심사하고 이전 거절·종료 Site를 참조로 남긴다 | 제안 | 적용 | D-GOV-020 ②, D-MS-005 | SITE-CHANNEL-POL-001 §4.5 | 없음(미구현) |
| BR-BIZ-360 | 채널 예외 승인은 점유가 끝난 채널에만, OPERATOR 요청·APPROVER 승인 2인(요청자≠승인자)·사유·소유권 확인 근거 참조로 하며, 승인 1건은 (채널, 신청 가맹점) 1회만 쓰인다 | 제안 | 적용 | D-GOV-020 ②, D-GOV-004 ②, D-ADMIN-003 | SITE-CHANNEL-POL-001 §4.6 | 없음(미구현) |
| BR-BIZ-361 | Site 운영 결정·채널 점유 이력·예외 승인 기록은 append-only이고, 점유의 시작·해제·예외 승인 사용은 그 원인 결정(등록·거절·종료)·이력·Outbox와 같은 MASTER 로컬 TX이며, 앱 사전 조회와 DB 유일 제약이 함께 강제한다 | 제안 | 적용 | D-GOV-020 ②, BR-BIZ-215, CLAUDE.md 핵심 규칙(append-only·로컬 TX) | SITE-CHANNEL-POL-001 §4.4 | 없음(미구현) |
| BR-BIZ-362 | 기존 Site 심사 값 SUSPENDED는 운영 정지로 옮기고, 정지 Site를 심사 거절로 바꾸는 경로는 두지 않는다 | 제안 | 적용 | D-GOV-020 ②([해석] 운영 정지 = 기존 SUSPENDED) | SITE-CHANNEL-POL-001 §3.2 | 없음 |
| BR-BIZ-363 | Site 운영 정지는 새 Payment 생성과 승인 시작(REQUESTED 건 포함)을 막고, AUTHORIZING·UNKNOWN 건의 결과 조회·대사와 취소·정산에는 영향이 없다 | 제안 | 적용 | D-MD-006 준용([해석]), D-AUTH-002·008 | SITE-CHANNEL-POL-001 §2.4·§4.3 | 없음(승인 시작 A1 재평가는 있음 — 정지 시험 없음) |

### 6.4 새 행 — 미결

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-364 | '최초 심사 거절'의 뜻(승인 이력 없는 Site의 거절인지, 같은 채널 첫 신청의 거절인지)과 같은 가맹점 재신청 반복 허용 여부 | 미결(Q-SCP-1) | — | D-GOV-020 ② | SITE-CHANNEL-POL-001 §7 | 없음 |
| BR-BIZ-365 | 운영 정지 중 신규 Site 신청 제한의 범위와, 정지 상태에서 종료된 Site 채널의 같은 가맹점 재사용 조건 | 미결(Q-SCP-2) | — | D-GOV-020 ②, D-GOV-004 ② | SITE-CHANNEL-POL-001 §7 | 없음 |
| BR-BIZ-366 | 채널 예외 승인의 대상 채널 상태·소유권 확인 근거의 종류·원 보유 가맹점 처리 | 미결(Q-SCP-3) | — | D-GOV-020 ② | SITE-CHANNEL-POL-001 §7 | 없음 |

### 6.5 진단 절 기록안

- 대체 사슬(§3.2·§6.1과 같음): 081 → 372, 082 → 352, 083 → 367, 084 → 355, 086 → 371, 087 → 368, 088 → 369. 087·088의 재승인 부분은 370(확정 — 의미 유지, 대상 행위 이름만 '재승인' → '운영 재개'). 새 모델 부분은 348(확정)·355~358(제안).
- 결정 간 관계: D-GOV-004 ①은 D-GOV-020 ②로 **좁혀짐**(전역 해제 → 같은 가맹점 재신청, 심사 축에서는 최초 거절만), ②는 **보강**(소유권 확인 추가, 해제 채널에도 적용). DECISIONS 역참조 표기만 — 원문 수정 없음.
- 코드와 다른 규칙: BR-BIZ-351·355·358·372(현 코드는 거절 Site도 점유, SUSPENDED 심사 값, 생명주기 기준 점유).

---

## 7. 사용자 결정 필요 질문(3개 — 금융·권리 의미)

**Q-SCP-1 '최초 심사 거절'의 뜻과 재신청 반복**
- 현재: 심사 거절은 Site 단위 종결(D-MS-005), 지금은 정지(SUSPENDED)에서도 거절할 수 있다. 새 정책(P2)으로 정지→거절 경로가 없어지면 **모든 심사 거절은 승인 이력 없는 Site의 첫 결정**이 된다.
- 권장안: **ⓐ 승인 이력이 없는 Site의 심사 거절(PENDING→REJECTED) = 최초 심사 거절** — 해제 후 같은 가맹점 재신청 횟수는 제한하지 않는다(매 재신청이 새 Site·처음부터 2인 심사). 승인 뒤 문제는 운영 축(정지·종료)으로만 처리.
- 대안: ⓑ 같은 가맹점·같은 채널의 **첫 신청 거절만** 해제 — 재신청 Site가 다시 거절되면 점유 유지(이후 재신청은 예외 승인) ⓒ 승인 뒤 '재심사' 행위를 심사 축에 두고, 재심사 거절은 점유 유지(최초 아님).
- 영향: 채널 점유 권리·반복 재신청 남용. ⓑ는 '두 번째 거절 뒤 점유 유지'를 위해 채널별 신청 차수 기록이 필요하고 영구 잠김 채널이 생긴다. ⓐ는 모델이 단순하나 거절 → 재신청 반복을 심사 인력으로만 막는다.
- 막히는 작업: BR-BIZ-351·355·364 확정, 점유 판정 migration 설계, GOV-MER-001 MF-1(가).

**Q-SCP-2 운영 정지 중 '신규 Site 신청 제한'의 범위와 정지 후 종료 채널**
- 현재: 같은 채널 신청은 중복으로 막히고, 다른 채널 신청은 제한이 없다. D-GOV-004 ②는 종료 Site 채널의 같은 가맹점 재사용을 허용한다 — **정지 → 종료 → 같은 가맹점 같은 채널 재등록**으로 정지 사유를 우회할 수 있다.
- 권장안: ⓐ 제한은 **정지 Site와 같은 채널의 신규 Site만**(가맹점의 다른 채널 신청은 허용) ⓑ **정지 상태에서 종료된 Site의 채널은 같은 가맹점이라도 예외 승인(2인 + 사유) 뒤에만 재사용**.
- 대안: ⓐ' 정지 Site가 있는 동안 그 **가맹점의 모든 신규 Site 신청 제한** ⓑ' 정지 후 종료도 D-GOV-004 ② 그대로(같은 가맹점 자유 재사용).
- 영향: 정지 사유(불법 판매·민원 등) 회피 위험 vs 정상 가맹점의 다른 채널 확장. ⓐ'는 가맹점 단위 차단이라 Merchant 운영 중지(D-MD-006)와 의미가 겹친다. ⓑ는 D-GOV-004 ② '같은 가맹점 허용'에 예외를 만든다.
- 막히는 작업: BR-BIZ-350·365, 등록 명령 판정, Site 종료 명령(MASTER-LIFECYCLE-001).

**Q-SCP-3 다른 가맹점 채널 재사용 예외 승인의 조건**
- 현재: 예외 승인 기록·명령 없음. 확정은 '근거 + 2인'(D-GOV-004 ②)과 '예외 승인 + 소유권 확인'(D-GOV-020 ②)까지.
- 권장안: ⓐ 대상은 **점유가 끝난 채널(최초 거절 해제·종료)만** — 점유 중(심사 대기·운영 중·운영 정지) 채널은 예외로도 못 가져간다 ⓑ 소유권 확인 근거 = **WEB: 신청 가맹점이 PayCore 지정 DNS TXT 값(또는 지정 경로 파일)을 게시했음을 확인한 기록, APP: 스토어 개발자 계정 소유 증빙 참조** — 운영 확인 절차의 기록 참조만 받고 자동 검증은 후속 ⓒ 원 보유 가맹점에는 **통지만**(동의 불요).
- 대안: ⓐ' 점유 중 채널도 예외 승인으로 이전(분쟁 시 소유권 이전) ⓑ' 근거 종류를 정하지 않고 자유 형식 근거 참조 ⓒ' 원 보유 가맹점 동의 필수.
- 영향: 결제 채널 이전 권리·도메인 탈취 위험. ⓐ'는 운영 중 Site의 결제를 다른 가맹점으로 넘기는 경로가 생겨 정산·분쟁 귀속이 복잡해진다. ⓒ'는 폐업·연락 불가 가맹점의 채널을 영구 잠근다.
- 막히는 작업: BR-BIZ-352·360·366, 예외 승인 기록 표·ADMIN 승인 종류·명령.

**질문하지 않음(이미 정해졌거나 가역)**
- 예외 승인 인원 2인·근거 필수 — D-GOV-004 ② 확정.
- 종료 Site 채널의 같은 가맹점 재사용(정지 이력 없음) — D-GOV-004 ② 확정.
- REJECTED 종결·재신청은 새 Site — D-MS-005 확정.
- 운영 재개 2인·등록자 금지 — D-MOPS-004·010 ② 의미 그대로 이동.
- 상태 이름, 운영 상태 저장 방식(생명주기 확장 vs 별도 값), 점유 표 구조, Snapshot 필드 설계 — Coordinator 구현 결정(통합 계약·migration은 리뷰 대상).
- 운영 정지 Site의 PAYMENT 응답 코드(METHOD_NOT_ENABLED vs MERCHANT_SUSPENDED) — 외부 계약 의미라 결정 필요하나 이번 3개 상한 밖. 구현 명세 단계에서 묻는다(답 전에는 어느 쪽이든 차단 — fail-closed).

---

## 8. 하지 않은 것

- 소스·시험·DB migration·runtime 권한·대장(`docs/rules/BIZ.md`)·`docs/DECISIONS.md`·`harness/questions.json`·`harness/tasks.json`·명세를 바꾸지 않았다. 커밋하지 않았다.
- 시험을 실행하지 않았다(시험 열은 이름 확인만).
- Demo DB의 기존 REJECTED·SUSPENDED 결정 행·같은 채널 데이터를 조회하지 않았다(§5 데이터 이관 '미확인').
- PAYMENT 응답 매핑·Snapshot schemaVersion·점유 표 구조를 정하지 않았다(§5 Coordinator 구현 결정·후속 질문).
- 질문 Q-SCP-1~3을 `harness/questions.json`에 등록하지 않았다(Coordinator).
- MASTER-LIFECYCLE-001·MERCHANT-OPS-002 B5의 범위 재조정을 하지 않았다(목록만).
