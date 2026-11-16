# ADMIN-HISTORY-KEYS-001 가맹점 변경 이력 "대상" 열 내부 키 비노출 명세 (rev 0.1b)

- **rev 0.1b** (2026-11-17, Coordinator — 코드 리뷰 E-1: §8 빌드 수용 기준 문구를 실제 빌드 경로(Gradle, GRADLE-CHECK 안)로 정정. 기준 완화 아님).
- **rev 0.1a** (2026-11-17, Coordinator — 명세 리뷰 REVIEW_PASS(조건부) 반영. 업무 규칙 변경 없음). MAJOR-1 대상 종류 이름은 `CODE_LABELS` 밖 별도 맵 13개(PolicyVersion 제외), `KindLabel` 선택 prop `label` · MINOR-1 집계 수 정정(소유 11종 + Business·Merchant = 13종) · MINOR-2 MC-2b fixture에 Business 이력 행 추가(단언 그대로) + T-HK-2 단언 명시 · NIT-1 `includes` 판정 · NIT-2 가맹점 행은 `merchantLabelText` 재사용.
- **rev 0.1** (2026-11-17, Coordinator 초안 — 확정 결정 적용 작업, 사용자 질문 없음). Reviewer 검토 전, 자기 완료 판정 아님.

## 0. 한눈에
- 문제: 관리자 콘솔 가맹점 상세 "변경 이력" 표의 대상 열이 `aggregateId`를 그대로 보여 준다. MC-2에서 가맹점 자신(`mer_…`)만 코드로 바꿨고, **사업자 행(`Business`)은 `biz_…` 내부 키가 화면에 그대로 나온다** — D-ADMIN-UI-004 ③·D-MCODE-003 ② 위반(독립 리뷰 2026-11-16 발견).
- 고침: **화면만**(ADMIN frontend). 사업자 행 = `<상호> · <마스킹 사업자등록번호>`, 가맹점 행 = `<상호> · <가맹점 코드>`. 대상 종류는 한국어 이름(+ 원래 코드 작은 글씨, 기존 `KindLabel` 방식). 그 밖의 대상(Site·계약·MID·개통 등)은 화면의 다른 패널이 이미 보여 주는 식별자 그대로.
- API·MASTER·ADMIN 서버·DB 변경 없음. 응답 JSON에는 `aggregateId`(biz_ 포함)가 계속 있다 — D-BIZLOOKUP-001과 같은 "응답 포함, 화면 미표시" 원칙.

## 1. 현재 상태(코드 확인)
- MASTER `JooqMerchantHistoryReader.page`: 가맹점 1개에 대해 `Business`(그 가맹점의 사업자 1개)·`Merchant`(자신)·가맹점 소유 집계 11종(합계 13종 — `MerchantSite`·`Contract`·`ContractServiceAgreement`·`MerchantAccount`·`SiteAssignment`·`PaymentMethodActivation`·`SiteActivationConfirmation`·`CurrencyEnablement`·`PolicyAssignment`·`TransactionLimitPolicy`·`CardRouteActivation`)의 이력을 읽는다. `detail`·`request_record_id`는 읽지 않는다.
- ADMIN frontend `MerchantsScreen.tsx` 변경 이력 표: 대상 열 = `aggregateType` 원문 + (`aggregateId === merchantId`면 `merchantCode`, 아니면 `aggregateId`).
- 시험 공백: MC-2-3 DOM 시험은 `Merchant` 행만, MC-2b DOM 시험(biz_ 0건)은 **이력이 빈 상태**로만 확인해 사업자 행을 놓쳤다.

## 2. 사용자 결정 적용(재질문하지 않음)
- D-ADMIN-UI-004 ③: 사업자 ID는 화면 표시에서 제외, 내부 키 유지.
- D-MCODE-003: 사람에게는 가맹점 코드만, `mer_`는 내부 전용. 사업자는 `사업자: <상호> · <사업자등록번호 마스킹 값>`(평문 재노출 없음). 내부 키(`mer_`·`biz_`)는 시스템 안에서 유지.
- D-MOPS-003: 사업자등록번호는 마스킹 값만.

## 3. 설계(가역 화면 세부 — Coordinator 결정)
1. 대상 열 표시 규칙(`historyTarget(entry, detail)` 순수 함수, `lib/`):
   | 조건 | 표시 |
   |---|---|
   | `aggregateType = Merchant` 이고 `aggregateId = detail.merchant.merchantId` | `<detail.merchant.name> · <merchantCode>`(`merchantLabelText` 재사용 — 거래 목록과 같은 형식) |
   | `aggregateType = Business` 이고 `aggregateId = detail.business.businessId` | `<detail.business.name> · <registrationNumberMasked>` |
   | `aggregateId`에 `mer_` 또는 `biz_`가 들어 있는데(`includes` — 시험 `domOccurrences`와 같은 기준) 위 두 줄에 해당하지 않음(정상 경로에서는 없음 — 방어) | `—` (키 미표시, fail-closed) |
   | 그 밖 | `aggregateId` 그대로(Site·계약·MID·정책 ID 등 — 같은 화면 다른 패널이 이미 보여 주는 식별자. 숨김 대상 결정 없음) |
2. 대상 종류: **`CODE_LABELS` 밖** 이력 전용 맵 `HISTORY_TARGET_LABELS`(`lib/historyTarget.ts`, 13개)의 한국어 이름. 표에는 `KindLabel`에 선택 prop `label`을 더해 같은 마크업(한국어 이름 + 원래 코드 작은 글씨)으로 보인다. 맵에 없는 종류는 코드 그대로.
   - Business 사업자, Merchant 가맹점, MerchantSite 사이트, Contract 계약, ContractServiceAgreement 서비스 약정(CSA), MerchantAccount MID, SiteAssignment 사이트 배정, PaymentMethodActivation 결제수단 개통, SiteActivationConfirmation 사이트 적용 확인, CurrencyEnablement 통화 활성화, PolicyAssignment 정책 배정, TransactionLimitPolicy 거래 한도, CardRouteActivation 카드사 Route 개통.
   - `PolicyVersion`은 MASTER가 가맹점 이력에서 제외하므로(`JooqMerchantHistoryReader`) 넣지 않는다. `CODE_LABELS`·`labels.test.ts`(서버 code 집합·죽은 이름 금지 시험)는 바꾸지 않는다.
3. 행 `aria-label`(순번)·`key`는 그대로. 원문 `aggregateId`를 `title`·`data-*`·`aria-*` 어디에도 넣지 않는다.
4. 행위자 열(`actorRef`)은 범위 밖(정규 actor `admin:adm_…`·`system:…`는 다른 패널 "등록자"와 같은 표시 — 숨김 결정 없음).

## 4. API
- 변경 없음. ADMIN `GET /api/merchants/{id}/history` 응답 그대로.

## 5. 구조
- `apps/internal-admin/frontend/src/lib/historyTarget.ts`(+ `historyTarget.test.ts`), `components/DataBits.tsx`(`KindLabel` 선택 prop `label`), `screens/MerchantsScreen.tsx`(대상 열), `screens/MerchantsScreen.test.tsx`(DOM 시험).
- 새 의존성 없음, 런타임 `<style>`·style 속성 없음(기존 `noRuntimeStyles` 규칙).

## 6. 권한·감사·노출
- 역할·감사 변경 없음(D-MOPS-002: 이력 조회는 감사 없음 — 그대로).
- 노출: 화면 DOM(텍스트·모든 속성·입력 값)에 `biz_`·`mer_` 0건, 사업자등록번호 평문 0건.

## 7. 8대 원칙
- Financial Consistency·Transaction Safety·Idempotency·Concurrency·Recoverability·Reconciliation: 해당 없음(읽기 화면 표시만, 서버·DB 무변경).
- Auditability: 이력 행 자체(append-only `master_change_history`)는 그대로, 표시만 바뀜. 원문 키는 응답에 남아 추적 가능.
- Observability: 해당 없음.

## 8. 시험·수용 기준(TDD — RED 먼저)
- T-HK-1 `historyTarget` 단위: Merchant 일치 → 상호·코드; Business 일치 → 상호·마스킹 번호; 불일치 `mer_`/`biz_` → `—`; Site·계약·MID → 원문 ID.
- T-HK-2 화면 DOM: 이력에 Business·Merchant·MerchantSite·CardRouteActivation 행이 있을 때 `domOccurrences(document.body, 'biz_')`·`'mer_'` = `[]`, 사업자 행 텍스트에 `'데모 사업자'`·마스킹 번호, `domOccurrences(document.body, BRN)`·`'123-45-67890'` = `[]`, 다른 대상 행은 원문 ID 유지(기존 MC-2-3 단언 유지).
- T-HK-3 대상 종류 한국어 이름 표시(사업자·가맹점·카드사 Route 개통) + 원래 코드 작은 글씨. `HISTORY_TARGET_LABELS` 키 집합 = MASTER 이력 집계 13종(PolicyVersion 없음).
- MC-2b DOM 시험 fixture 보강: `merchantHistory`에 Business 행(`aggregateId: BIZ1`) 추가 — 원래 누락 원인(빈 이력) 차단. 단언은 그대로.
- 기존 시험 기대값 변경 없음(MC-2-3의 "Merchant 행 = 코드" 단언은 `<상호> · <코드>`에도 코드가 포함되어 그대로 통과해야 함; `labels.test.ts` 무변경).
- 수용: Vitest 전부 GREEN, `tsc` 0, 프런트 번들 빌드 0(이 저장소에는 `npm run build` 스크립트가 없다 — 운영 번들은 Gradle `paycore.frontend` 태스크가 GRADLE-CHECK 안에서 만든다), 전체 게이트 PASS.

## 9. 위험·한계
- 서버 응답에는 `biz_`가 남는다(브라우저 개발자 도구에서 보임) — D-BIZLOOKUP-001 원칙과 같고 이번 범위 밖.
- 사업자 상호가 이력 시점 이후 바뀌었다면 현재 상호가 보인다(이력 행 시점 상호가 아님). 이력 표는 "대상"을 가리킬 뿐 당시 값을 보여 주지 않는다 — 기존 가맹점 코드 표시(MC-2)와 같은 성질.
