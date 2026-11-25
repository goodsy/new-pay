# GOV-MER-001 — 가맹점(Merchant) 등록·조회·수정 기능 정합성 확인

- 작업: GOV-MER-001(정상화 작업, 분석만). 작성일 2026-11-25. **rev 0.1b**(같은 날 — 독립 리뷰 MAJOR 2·MINOR 9, 재리뷰 MAJOR 1·MINOR 1·NIT 2 반영, 개정 이력 §9).
- **기준 커밋 `d02f576`**(main, BIZ-NORM-003 — 문서만). 코드는 `6da29e7`과 같다. 작성 중 다른 작업이 `a08aa48`(RULE-CONFIRM-001 — 문서만)을 커밋했다. 그 문서는 읽기만 했고 코드·대장은 바뀌지 않았다.
- 형식·기준은 [GOV-BIZ-001](GOV-BIZ-001-BUSINESS-CRUD-CONSISTENCY.md) rev 0.1a를 따른다.
- 이 문서는 **진단**이다. 소스·DB·시험·결정·작업 상태·다른 문서는 바꾸지 않았고, 정책을 새로 정하거나 바꾸지 않았다.
- **시험은 실행하지 않았다.** 시험 파일이 있는지와 이름만 확인했다. "검증 방법"은 제안이다.
- 표기: **[사실]** = 파일:줄로 확인. **[해석]** = 사실에서 끌어낸 판단. **미확인** = 확인 못 함.

## 0. 범위·제외·참조

### 0.1 범위

| 구분 | 포함 |
|---|---|
| 등록 | 가맹점 등록 마법사 ① 사업자 선택 → ② 가맹점·Site → ③ 계약 초안·서비스 계약(CSA) → ④ 검토·완료. 진입은 가맹점 목록 '＋ 가맹점 등록'과 사업자 상세 '이 사업자로 가맹점 등록' |
| 조회 | 가맹점 목록 검색(가맹점 코드·가맹점명·사업자등록번호), 상세(머리 요약·MID 선택 머리·결제 준비 줄, 탭 개요/Site·계약/MID·개통/한도/변경 이력/기술 정보) |
| 수정 | 가맹점 기본정보 수정(**기능 없음** — §1.1 F-11), 가맹점 코드 변경(1회, 2인) |
| 연결 관계만 | 상세 안 명령 9종(목록만 — §1.4), 사업자 계약 접수 관문(D-FLOW-001 ①, FLOW-3) |

### 0.2 제외

- 개통·한도·MID·Site 심사·계약 승인의 세부 업무 규칙(상세 안 명령의 본문). §1.4에 목록과 근거만 둔다.
- 결제·정산 등 다른 도메인. 가맹점 등록을 직접 막는 선행 조건만 본다(사업자 REGISTERED 관문).
- MERCHANT-FLOW-001 FLOW-2 이후(접수 건 전이 등). 가맹점 등록 관문과의 연결만 본다.

### 0.3 사용한 기존 진단(ID로만 참조, 내용 반복 없음)

| 문서 | 이 문서에서 참조한 ID |
|---|---|
| [DOMAIN-CONSISTENCY-1-MASTER.md](DOMAIN-CONSISTENCY-1-MASTER.md)(기준 `41139db`) | DC-M-01, 02, 04, 06, 08, 09, 11, 13, 15, 16, 18, 24, 25, §3.2~§3.4 |
| [ADMIN-FEATURE-INVENTORY.md](ADMIN-FEATURE-INVENTORY.md) | ADM-F-MER-01~13, ADM-F-BIZ-04·06, §5-1 #3, §5-3 #1·#8, §5-4 #12 |
| [ADMIN-UI-DIAGNOSIS.md](ADMIN-UI-DIAGNOSIS.md) | UI-03, 04, 12, 16, 18, 19, 20, 26, 30 |
| [SOURCE-CONVENTION-REVIEW.md](SOURCE-CONVENTION-REVIEW.md) | SC-05, SC-07, SC-19 |
| [POLICY-CONFLICTS.md](POLICY-CONFLICTS.md) | PC-01, 10, 11, 14, 15, 33, 45, 47, 59, 71, 72 |
| [NORMALIZATION-PLAN.md](NORMALIZATION-PLAN.md) | NP-05, 07, 27, 35, 37, 67, 78, 95 / U-07, U-09 |
| [GOV-BIZ-001](GOV-BIZ-001-BUSINESS-CRUD-CONSISTENCY.md) rev 0.1b | F-09·F-10(사업자 → 가맹점 연결), G-07(불변 규칙의 DB 미강제 서술 방식) |
| [docs/rules/BIZ.md](../rules/BIZ.md) | BR-BIZ-022, 030~040, 060~074, 080~088, 100~115, 230~237, 250~256, 280, 진단 (b)-1·(e)-3·(f)-1·(f)-2 |
| [RULE-CONFIRM-001](RULE-CONFIRM-001-PROPOSED-RULES.md)(`a08aa48`, Q14는 같은 커밋의 rev 0.1b) | R2-Q4(BR-BIZ-280), R2-Q5(BR-BIZ-067), R5-Q14(BR-BIZ-084 + 거절 Site 채널 해제, `:184-189`(a08aa48 기준 — rev 0.1b에서 Q14 권장안 확대, 같은 커밋)) — 질문 중복을 피하려고 참조만 |

### 0.4 기존 진단 이후 달라진 사실(`41139db` → `d02f576`)

| 기존 진단 | 지금 | 근거 |
|---|---|---|
| DC-M-02·PC-01·NP-05(거절 Site 같은 채널 재신청 불가 — 결정 필요) | **결정됨**: 거절 Site는 채널 점유 해제(D-GOV-004 ①). 코드는 그대로 → §4 GM-02 | `DEC:259`, Q-GOV-U09a ANSWERED |
| DC-M-13·PC-15(Q-MD-18 종료 채널 재사용 미결) | **결정됨**: 다른 가맹점 재사용은 예외 승인(근거 + 2인) 없으면 거절(D-GOV-004 ②). Site 종료 명령이 없어 지금은 발생 경로 없음 | `DEC:259`, questions Q-MD-18 ANSWERED |
| DC-M-04·PC-33·NP-07(한 가맹점 동시 유효 계약 — 결정 없음) | **결정됨**: 같은 서비스 종류의 기간 겹치는 유효 계약 금지(D-GOV-003 ①). 코드는 그대로 → §4 GM-03 | `DEC:258`, Q-GOV-U07a ANSWERED |
| PC-71(가맹점 목록 4역할 BRN 원문 무감사 — Coordinator 해석) | **수용 확정**(D-GOV-003 ④) | `DEC:258` |
| DC-M-01·PC-12(관리 명령 지문 무염 SHA-256) | **시행됨**: 모든 관리 명령(가맹점 등록·Site·계약·CSA·코드 변경 포함) 지문 = `h1:` 비밀 키 HMAC | `d02f576` 기준 M/`application/AdminCommandExecutor.kt:72-76,460-462`(SEC-BIZ-001 `439fa1a`) |
| DC-M-16(새 가맹점 관문 계약 완료 — `wip/flow-1` 미병합) | FLOW-1이 main에 병합됐다(`cf86c67`). **관문은 여전히 없다**(FLOW-3) | M/`domain/Merchant.kt:91-96` |

경로 약어

- `FE/` = `apps/internal-admin/frontend/src/`
- `A/` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/merchantops/`
- `AA/` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/`
- `M/` = `apps/master-api/src/main/kotlin/com/paycore/master/merchant/`
- `SQL/` = `apps/master-api/src/main/resources/db/migration/master/`
- `PRIV/` = `apps/master-api/src/main/resources/db/runtime-privilege/master/`
- `DEC` = `docs/DECISIONS.md`
- `MOPS1`·`MOPS2` = MERCHANT-OPS-001·002, `MC1` = MERCHANT-CODE-001, `IA1` = MERCHANT-IA-001, `MNS1` = MERCHANT-NAME-SEARCH-001, `SCC1` = SITE-CONTRACT-CODE-001(모두 `docs/repository/`)

---

## 1. 현재 제공 기능과 화면별 업무 흐름

### 1.1 기능 목록(11개 — 구현 10, 없음 1)

구현된 기능은 모두 **코드 있음 + 시험 파일 있음 + 시험 미실행** 상태다. "완성"은 실행 검증이 아니다.

| ID | 기능 | INV ID | 구현 근거 |
|---|---|---|---|
| F-01 | 마법사 ① 사업자 선택: 사업자등록번호 조회(0·1건, 매 시도 감사) → REGISTERED만 '선택', DRAFT·사용중지는 선택 불가 안내 | MER-13 | FE/`screens/MerchantRegistrationScreen.tsx:254-364`(선택 가능 조건 `:341-353`), 사업자 상세에서 넘김 FE/`App.tsx:193-196` |
| F-02 | 마법사 ② 가맹점 저장: 가맹점명 + 가맹점 코드(영문 상호로 제안·직접 입력) | MER-13 | FE/`MerchantRegistrationScreen.tsx:466-486,674-694`, A/`application/MerchantRegistrationService.kt:52-94`, M/`application/MerchantOnboardingService.kt:93-119` |
| F-03 | 마법사 ② Site 등록(채널 웹·앱 + 채널 식별값, '다른 Site 추가 입력'으로 여러 개) | MER-13 | FE/`MerchantRegistrationScreen.tsx:487-505,699-744`, M/`MerchantOnboardingService.kt:121-149` |
| F-04 | 마법사 ③ 계약 초안 등록(계약 당사자·범위 가맹점 전체/선택 Site·시작일·종료일) | MER-13 | FE/`MerchantRegistrationScreen.tsx:506-544,750-850`, M/`MerchantOnboardingService.kt:151-167` |
| F-05 | 마법사 ③ 서비스 계약(CSA) 추가(계약 ID + 서비스 종류 카드 PG 결제) | MER-13 | FE/`MerchantRegistrationScreen.tsx:545-557,852-878`, M/`MerchantOnboardingService.kt:249-267` |
| F-06 | 마법사 ④ 검토·완료('가맹점 조회에서 보기', '새 가맹점 등록 시작') | MER-13 | FE/`MerchantRegistrationScreen.tsx:886-961`, 상세 이동 FE/`App.tsx:316-319` |
| F-07 | 목록 검색(가맹점 코드 단일 / 가맹점명 앞부분 / 사업자등록번호 정확 일치 — keyset '더 보기') | MER-01 | FE/`screens/MerchantsScreen.tsx:530,1044-1092`, A/`application/MerchantLookupService.kt:53-107` |
| F-08 | 상세 열람(머리 요약·MID 선택 머리·결제 준비 줄 + 탭 6개, 열람 감사) | MER-02·11·12 | FE/`MerchantsScreen.tsx:449-459,741-792`, A/`MerchantLookupService.kt:109-121` |
| F-09 | 상세 '변경 이력' 탭('이력 더 보기') | MER-03 | FE/`MerchantsScreen.tsx:612,884-930`, A/`MerchantLookupService.kt:123-133` |
| F-10 | 가맹점 코드 변경(1회, 계약 승인 전, 2인: 요청 → 승인함 승인 → MASTER 실행) | MER-10 | FE/`components/MerchantCodeChangeRequest.tsx:28-113`, AA/`application/MerchantOpsApprovalService.kt:387-413`, M/`MerchantOnboardingService.kt:393-418`, M/`domain/Merchant.kt:54-72` |
| F-11 | **가맹점 기본정보(가맹점명) 수정 — 없음.** 화면·ADMIN API·MASTER 명령·Domain 메서드가 모두 없다. 명세·결정도 없다 | — | [사실] ADMIN 등록 명령 목록에 수정 종류 없음(A/`application/required/MasterCommand.kt:25-46`), ADMIN 가맹점 경로는 조회 3개뿐(A/`adapter/web/MerchantController.kt:37,59,73`), Domain `Merchant`에 이름 변경 메서드 없음(M/`domain/Merchant.kt` 전체), JPA 저장은 이름을 복사하지 않음(M/`adapter/persistence/JpaMerchantRepository.kt:46-65`). 명세·DEC·대장 검색 결과 없음 → §6 QM-2 |

### 1.2 화면 → 단계 → 호출 API

메뉴는 '사업자·가맹점'이고 탭은 '가맹점'·'사업자'다(D-ADMIN-UI-006, FE/`navigation.ts:93`). 가맹점 등록은 탭이 아니라 하위 화면이다(FE/`navigation.ts:96`). 상세 선택·마법사 진행 상태는 App 메모리에만 둔다(URL·브라우저 저장소 없음).

#### A. 가맹점 목록 — 탭 '가맹점'(`page = 'merchants'`, FE/`App.tsx:277-291`)

| 단계 | 화면 동작 | ADMIN API(FE 호출) | MASTER 내부 API(A/`adapter/integration/HttpMasterOpsGateway.kt`) |
|---|---|---|---|
| 1 | 필터 묶음 [가맹점 코드][가맹점명][사업자등록번호(OPERATOR·APPROVER만 보임)] → 검색 / '더 보기' | `POST /api/merchant-searches`(FE/`api/adminApi.ts:1133`) | `POST /internal/v1/admin/merchant-searches`(`:131`) |
| 2 | 열: 가맹점(이름·코드) / 사업자(상호·번호) / MID 수 / 등록일 / 상세(FE/`MerchantsScreen.tsx:1106`) | — | — |
| 3 | 행 '상세' → B | — | — |
| 4 | '＋ 가맹점 등록' → C(OPERATOR만 — FE/`App.tsx:289`, `navigation.ts:60`) | — | — |

#### B. 가맹점 상세 — 같은 탭 안 화면 전환(D-ADMIN-UI-009 ①)

| 단계 | 화면 동작 | ADMIN API | MASTER 내부 API |
|---|---|---|---|
| 1 | 상세 열기(머리: 가맹점명·코드·"사업자: 상호 · 번호", MID 선택 머리, 결제 준비 줄) | `GET /api/merchants/{id}`(FE/`MerchantsScreen.tsx:627,650`) | `GET …/merchants/{id}`(`:147`) + ADMIN 미해결 승인(A/`adapter/web/MerchantController.kt:59-66`) |
| 2 | 탭 개요 / Site·계약 / MID·개통 / 한도 / 변경 이력 / 기술 정보(FE/`MerchantsScreen.tsx:453-459`) | — | — |
| 3 | 변경 이력 탭 / '이력 더 보기' | `GET /api/merchants/{id}/history?after=`(FE/`adminApi.ts:1135`) | `GET …/history/after/{seq}`(`:152`) |
| 4 | 개요 탭 '코드 변경 요청'(OPERATOR + `merchantCodeChangeable` + 같은 그룹 승인 대기 없음 — FE/`MerchantCodeChangeRequest.tsx:28-30`, FE/`MerchantsScreen.tsx:864`) | `POST /api/approvals/merchant-code-change`(FE/`adminApi.ts:1163`) | 승인 뒤 `POST …/merchant-operations/merchant-code-changes`(M/`adapter/web/admin/MerchantApprovalController.kt:79`) |
| 5 | 그 밖 상세 안 명령 9종 → §1.4(연결만) | — | — |

#### C. 가맹점 등록 마법사 — 하위 화면(`page = 'merchantRegistration'`, FE/`App.tsx:312-324`)

진입점은 목록 '＋ 가맹점 등록'과 사업자 상세 '이 사업자로 가맹점 등록'(REGISTERED·OPERATOR — 사업자가 이미 골라진 채 ②로 열림, FE/`App.tsx:193-196`) 두 곳이다. **기존 가맹점을 골라 ②(Site)·③부터 시작하는 진입점은 없다**(§4 GM-01).

| 단계 | 화면 동작 | ADMIN API | MASTER 내부 API |
|---|---|---|---|
| ① | 사업자등록번호 → '찾기'(보낸 즉시 입력란 비움 `:275-276`) → REGISTERED만 '선택' | `POST /api/businesses/registration-number-lookups`(`adminApi.ts:1137`) | `POST …/businesses/registration-number-lookups` |
| ② 가맹점 | 가맹점명, 영문 상호(저장 안 함 — 코드 제안용), 가맹점 코드('다시 제안') → '다음 단계 →'가 가맹점 → Site 순으로 저장 | `POST /api/merchant-registrations/merchants`(`adminApi.ts:1143`) | `POST …/merchant-operations/merchants`(M/`adapter/web/admin/MerchantOperationsController.kt:44`) |
| ② Site | 소속 가맹점(읽기 전용 — 이 마법사에서 저장한 가맹점만 `:495`), 채널, 채널 식별값 | `POST /api/merchant-registrations/merchant-sites`(`:1144`) | `POST …/merchant-sites`(`:49`) |
| ③ 계약 초안 | 계약 당사자·소속 가맹점(읽기 전용), 범위(가맹점 전체 / 선택 Site — 이 마법사의 Site 체크 + **'Site ID 직접 입력'** `:802-816`), 시작일(최솟값 오늘 KST — 안내만 `:824`), 종료일(선택, '이 날 0시 종료(미포함)') | `POST /api/merchant-registrations/contracts` | `POST …/contracts`(`:55`) |
| ③ 서비스 계약 | **계약 ID 입력칸**(`ctr_…` 자유 입력 — 앞 카드 성공 값이 채워짐 `:541,864-866`), 서비스 종류 | `POST /api/merchant-registrations/service-agreements`(`:1146`) | `POST …/service-agreements`(`:67`) |
| ④ | 저장 결과 검토(내부 키 `site_`·`ctr_`·`csa_` 표시 — UI-03), '가맹점 조회에서 보기' → B, '새 가맹점 등록 시작'(잠금 없을 때만 메모리 전부 비움 `:599-602`) | — | — |

각 카드는 결과 확정(200·409)까지 같은 `submissionId`로 재전송하고, 결과 불명이면 카드를 잠근다(FE/`components/RegistrationStep.tsx`, MOPS2 §5.3).

### 1.3 역할별 차이

권한 판정은 서버 Use Case가 한다. 화면은 표시만 한다.

| 역할 | 메뉴(FE/`navigation.ts:55,60`) | 목록·상세·이력 | 사업자등록번호 검색 | 등록 4종(가맹점·Site·계약 초안·CSA) | 코드 변경 |
|---|---|---|---|---|---|
| OPERATOR | 가맹점 탭 + 등록 화면 | 허용(상세만 감사) | 허용(매 시도 감사) | 허용(A/`MerchantRegistrationService.kt:59`) | 요청(AA/`domain/ApprovalKind.kt:65`) |
| APPROVER | 가맹점 탭 | 허용 | 허용 | 403 + 감사 `FORBIDDEN`(`:57-59`) | 승인함에서 승인(요청자 ≠ 승인자) |
| VIEWER·SECURITY_ADMIN | 가맹점 탭 | 허용(A/`MerchantLookupService.kt:209`) | 칸 없음. API는 403 + **감사 `FORBIDDEN`**(`:85`) | 403 + 감사 | 없음 |
| 업무 역할 없음·비활성 | — | 403, 감사 없음(`:135-136`) | 403, 감사 없음 | 403, 감사 `FORBIDDEN`(세션 없으면 감사 없음 `:54`) | — |

[사실] MASTER 내부 API에는 역할 판정이 없다. ADMIN_SERVICE 서명 채널을 신뢰한다(D-MOPS-005, BR-BIZ-235).

### 1.4 상세 안 명령(연결 관계만 — 이번 범위의 업무 규칙 판정 대상 아님)

| 명령 | 위치(탭) | 승인 | 근거 | INV |
|---|---|---|---|---|
| 계약 Revision 등록 | Site·계약 | 1인 | D-MOPS-004·007 ②, BR-BIZ-107·113 | MER-04 |
| 계약 승인 요청 | Site·계약 | 2인(근거 필수, Revision 등록자 ≠ 승인자) | D-MOPS-008 ①③, BR-BIZ-113·114 | MER-05 |
| Site 심사 승인 요청 / 거절 | Site·계약 | 2인 / 1인 | D-MOPS-007 ③·009 ③, BR-BIZ-087·088 | MER-08 |
| 첫 MID 발급 요청 | 상세 머리(MID 선택 아래) | 2인 | D-MOPS-004·008, BR-BIZ-134·138 | MER-06 |
| 결제수단 개통 등록 / 승인 요청 | MID·개통 | 1인 / 2인 | D-MOPS-009 ③, BR-BIZ-087 | MER-07 |
| 통화 활성화 요청 | MID·개통 | 2인 | D-MOPS-009 ② | MER-09 |
| 가맹점 코드 변경 요청 | 개요 | 2인 | D-MCODE-002, BR-BIZ-066~070 | MER-10(이 문서 F-10) |

---

## 2. 적용 정책

### 2.1 확정

"확정"은 근거가 사용자 답이거나 baseline 확정 문서라는 뜻이다(BIZ 대장 머리 정의).

| 규칙 | 내용(요약) | 근거 결정 |
|---|---|---|
| BR-BIZ-022 | 새 가맹점은 REGISTERED 사업자만(기존 가맹점 후속 명령은 무영향) | D-BIZ-001 |
| (관문) | 새 가맹점 = 사업자 REGISTERED **+ 계약 완료**(FLOW-3에서 구현 예정) | D-FLOW-001 ① |
| BR-BIZ-060·061 | Merchant 소속 Business 불변, 생명주기 ACTIVE/TERMINATED(종료 비가역) | 구조 §13, D-MD-006 |
| BR-BIZ-062 | 같은 사업자 아래 같은 가맹점명 허용, 중복 클릭은 같은 요청키 재전송 | D-MOPS-006 ⑥ |
| BR-BIZ-063·065·066·068·069 | 등록 때 가맹점 코드 필수 발급(영문 소문자+숫자 14자 이내, 제안·수정·직접 입력), MASTER 전체 유일(은퇴 코드 포함), 첫 계약 승인 전 2인 1회 변경·그 뒤 불변, 옛 코드 영구 예약, 요청자 ≠ 승인자만 | D-MCODE-001·002 |
| BR-BIZ-071~074 | 코드는 출력 전용·기관 전송 안 함, 새 감사·승인 대상은 코드 | D-MCODE-001·002 |
| BR-BIZ-080·081·083·085 | 채널 식별값 소문자 정규형, 활성 채널 전역 유일, 신규 Site 심사 PENDING, REJECTED 종결·재신청은 새 Site | D-MD-008·009·014, D-MS-002·005 |
| (Site 재신청) | 거절 Site는 **채널 점유 해제**(채널 중복 검사에서 제외, 거절 기록 유지) — 같은 주소로 새 Site 재신청 가능 | **D-GOV-004 ①** |
| (종료 채널) | 종료 Site 채널의 다른 가맹점 재사용은 예외 승인(근거 + 2인) 없으면 거절, 같은 가맹점은 허용 | **D-GOV-004 ②**(Q-MD-18) |
| BR-BIZ-100~104·107·108 | Contract 1:N CSA, CSA 1건 = 서비스 종류 1개(계약 안 중복 거절), 당사자 = Business, 범위 = 그 Business의 가맹점(전체/선택 Site), Revision 불변·승인 append-only, 소급 시작 거절(KST), 종료 당사자 거절 | D-MD-001·002·004·010·013, D-MOPS-006 ⑤·007 ② |
| (계약 수) | 한 가맹점 같은 서비스 종류의 **기간 겹치는 유효 계약 금지**(조건 변경·갱신은 Revision). 원칙은 확정, 세부 범위(Site 범위가 다른 계약, CSA 없는 승인 계약)는 §6 QM-7 | **D-GOV-003 ①** |
| BR-BIZ-113·230 | 계약 초안·CSA·가맹점·Site 등록은 OPERATOR 1인, 권리를 넓히는 명령은 2인 | D-MOPS-004·007 ③ |
| BR-BIZ-235~237 | ADMIN_SERVICE 서명 채널, 1인 등록 감사 2행(시도·결과), 같은 키·같은 내용 재생·다른 내용 충돌 | D-MOPS-005·006 ②, CLAUDE.md |
| BR-BIZ-030·035~040 | 화면·조회 응답은 사업자등록번호 원문, 번호 검색은 OPERATOR·APPROVER·매 시도 감사, 상세 열람 감사(실패 시 결과 미반환), 목록·이력은 감사 없음 | D-MOPS-002·003, D-BIZ-007, D-PII-001, **D-GOV-003 ④(목록 4역할 무감사 수용)** |
| BR-BIZ-250~256 | 내부 키 화면 미표시, Site·계약 사람용 코드(`site-<가맹점코드>-NN`·`ctr-…`) 발급·불변, CSA는 '<계약 코드> · 서비스 종류' 표시 | D-ADMIN-UI-004 ③·005·007, D-MCODE-003 |
| (화면) | 마법사 소속 가맹점은 맥락으로 정하고 읽기 전용 "<가맹점명> · <코드>" | D-MCODE-003 ① |
| (화면) | 가맹점 상세 = 화면 전환 + 탭 6개 + MID 선택 머리 + 기술 정보 접기, 결제 준비 줄, 목록 필터 묶음 | D-ADMIN-UI-009 ①②, D-ADMIN-UI-010 ①~⑤, D-ADMIN-UI-011 ① |
| (화면) | 탭 2개 + 목록의 '＋ 가맹점 등록'(권한 불변) | D-ADMIN-UI-006 |
| (MID 수) | 추가 MID·기본 MID 교체는 MASTER-LIFECYCLE-001 착수 때 결정, 그 전 가맹점당 MID 1 | D-GOV-005 ① |
| (관리체계) | 대장이 현재 유효 규칙의 정본, **결정이 바뀌면 대장·정책서·DECISIONS를 같은 커밋에서** | D-GOV-006 ② |

### 2.2 미확정(대장 '제안'·'Demo값'·질문·결정 없음)

| ID | 내용 | 상태 | 현재 코드 동작 |
|---|---|---|---|
| BR-BIZ-064 | 코드 첫 글자 영문·6~14자·예약어 완전·접두 일치 거절 | **제안**(MC1 C-1·C-2). RULE-CONFIRM-001 일괄 확인 대상 | 그렇게 동작(M/`domain/MerchantCode.kt:12,16-18`, `MerchantCodeReservedWords.kt:8-12`) |
| BR-BIZ-067 | "첫 계약 승인" = 그 가맹점 대상 계약의 어느 Revision이든 승인 사실 하나(효력 전 승인 포함) | **제안**(MC1 C-14). D-GOV-005 ④ 개별 질문 대상 → RULE-CONFIRM-001 R2-Q5로 준비 중 | 그렇게 동작(M/`MerchantOnboardingService.kt:398`, M/`adapter/query/JooqMerchantDetailReader.kt:79`) |
| BR-BIZ-070 | 코드 변경 사유 고정 목록 3종 | **Demo값**(MC1 C-12) — 운영값 아님, 운영 사유 체계는 그 결정 때 정함 | 세 계층 같은 목록(FE/`MerchantCodeChangeRequest.tsx:15-19`, A/`domain/MerchantCodeChangeCommandBody.kt:44`, AA/`domain/MerchantOpsInputRules.kt:81`) |
| BR-BIZ-280 / PC-45 | 관문의 "최신 접수 건" | **제안**. RULE-CONFIRM-001 R2-Q4(FLOW-3 전) | 관문 자체 없음 |
| DC-M-08 / PC-34 / NP-34 | CSA 추가 승인 수준(승인된 계약에 1인 추가 가능) | **결정 없음** — U-07 질문 문안에는 있었으나 Q-GOV-U07a·b 답 범위에 없음, questions.json 미등록 | 1인, 계약 승인 여부 검사 없음(M/`domain/Contract.kt:81-86`) |
| (가맹점명 수정) | 가맹점 기본정보 수정 가능 여부·절차 | **명세·결정 없음** | 기능 없음(F-11) |
| (거절 채널의 적용 범위) | D-GOV-004 ① 문언 '채널 중복 검사에서 제외'는 **글자 그대로 읽으면 가맹점 제한 없는 전역 해제**다(다른 가맹점도 그 채널로 등록 가능). [사실] RULE-CONFIRM-001 R5-Q14(`a08aa48` 문안, `:184-189`(a08aa48 기준 — rev 0.1b에서 Q14 권장안 확대, 같은 커밋))는 **SUSPENDED→REJECTED로 풀린 채널만** D-GOV-004 ② 기준으로 좁히는 안을 묻고, 직접 거절된 채널의 다른 가맹점 재사용은 묻지 않는다. Coordinator 결정 (a): Q14 문안을 넓혀 하나로 묻는다 — RULE-CONFIRM-001 Q14(Coordinator 결정 (a)로 넓힐 문안: 거절 경로 — 직접 거절·SUSPENDED→REJECTED — 와 무관하게 다른 가맹점 재사용은 D-GOV-004 ② 기준(예외 승인 없으면 거절), 같은 가맹점 재신청은 허용). 넓힌 문안의 권장안도 결정 문언(전역 해제)의 적용 범위를 좁히는 대안이다 | **답 대기**(넓힌 Q14 — 이 문서는 따로 묻지 않음) | 지금은 거절 Site도 점유 유지라 아무도 못 씀 |
| Q-PII-7 범위 | 개인 이름을 포함할 수 있는 **가맹점명**의 마스킹 | Q-PII-7은 사업자 **상호**만 적음(PROPOSED) | 마스킹 없음. 감사 detail에는 가맹점명을 넣지 않음(MOPS2 §6) |
| IA1 §7.3 NIT-2 / PII #18 | 등록자·행위자 `admin:adm_…` 표시 | 미결 기록(결정 없음) | Site 등록자 열·변경 이력 행위자 열에 그대로 표시(FE/`MerchantsScreen.tsx:916`) |
| MNS1 L-3 | 가맹점명 검색 최소 접두 길이 또는 statement timeout | LIVE Gate 결정 항목 | 둘 다 없음 |

---

## 3. 계층 책임과 연결

### 3.1 호출 연결(가맹점 저장 기준 — Site·계약 초안·CSA도 같은 틀)

| 계층 | 책임 | 연결 file:line |
|---|---|---|
| 화면 | 사업자 선택(REGISTERED만), 입력 복제 검사(서버가 권위), 코드 제안, `submissionId`·결과 불명 잠금, 카드 순서 저장 | FE/`MerchantRegistrationScreen.tsx:341-353,466-486,570-597`, `lib/registration.ts:9`, `lib/merchantCode.ts:10,54-58` |
| ADMIN API | 413·415 읽기 전 거절, 엄격 해석 | A/`adapter/web/MerchantRegistrationController.kt:30-31,61-101`, `MerchantRegistrationBodyParser.kt` |
| ADMIN Application | 세션 → 역할(OPERATOR) → 형식(MASTER 규칙 복제 C-10) → 등록 창 → 시도 감사 → MASTER(TX 밖) → 결과 감사. 키 `mo1.<계정>.<submissionId>` | A/`application/MerchantRegistrationService.kt:52-94,99-137`, `RegistrationInputRules.kt:40-51` |
| ADMIN → MASTER | 서명, 쓰기 permit, TX 안 호출 금지, timeout·모양 위반은 Unknown | A/`HttpMasterOpsGateway.kt:157-158` |
| MASTER API | permit, 엄격 해석(값 객체 — 위반 400) | M/`adapter/web/admin/MerchantOperationsController.kt:44-71`, `MerchantOperationsRequestParser.kt:69-78,87` |
| MASTER Application | 실행기 한 로컬 TX(멱등 `admin_request` + 업무 변경 + 이력 + Outbox, 지문 `h1:` HMAC), 사업자 행 잠금 → 등록 판정 → 코드 사전 조회 | M/`application/MerchantOnboardingService.kt:93-119`, `AdminCommandExecutor.kt:92` |
| Domain | 사업자 생명주기 → 등록 상태(REGISTERED) → 코드 예약어, seq 1 배정 | M/`domain/Merchant.kt:91-96`, `Business.kt:40-42`, `MerchantCode.kt:12-18`, `DisplayName.kt:7-20` |
| DB | `merchant`(이름 VARCHAR(100)), 배정 표 UNIQUE·CHECK, runtime 권한 | SQL/`V1__master_core.sql:38-54`, SQL/`V13__merchant_code_assignment.sql:8-33`, PRIV/`V2__master_runtime_privileges.sql:17`, PRIV/`V14__merchant_code_assignment_privileges.sql:3` |

### 3.2 규칙별 강제 위치

○ = 강제(거절), △ = 안내만 또는 부분, — = 없음.

| 규칙 | 화면 | ADMIN App | MASTER App·API | Domain | DB |
|---|---|---|---|---|---|
| 등록 = OPERATOR(D-MOPS-006 ①) | △ 표시(`navigation.ts:60`, `App.tsx:289`) | ○(`MerchantRegistrationService.kt:59`) | — 서명 채널 신뢰 | — | — |
| 새 가맹점 = REGISTERED(BR-BIZ-022) | △ 선택 불가(`MerchantRegistrationScreen.tsx:341-353`) | — | 사업자 행 잠금(`MerchantOnboardingService.kt:101`) | ○ `BUSINESS_NOT_REGISTERED`(`Merchant.kt:93`, `Business.kt:40-42`) | — |
| 새 가맹점 = 계약 완료(D-FLOW-001 ①) | **—** | **—** | **—** | **—**(`Merchant.kt:91-96`) | **—** |
| 가맹점명 1~100자·앞뒤 공백·제어문자(D-MOPS-006, C-10) | △ `validName` — **JS `trim()`으로 유니코드 공백 전체를 봄**(`lib/registration.ts:9`) | ○ U+0020만 봄(`RegistrationInputRules.kt:100-116`) | ○ 값 객체 400(`MerchantOperationsRequestParser.kt:71`) | ○ U+0020만 봄(`DisplayName.kt:7-8`) | △ 길이만(V1:41) |
| 같은 가맹점명 허용(BR-BIZ-062) | 경고 없음 | — | — | — | UNIQUE 없음(V1:38-54) — 일치 |
| 코드 형식(BR-BIZ-063·064) | ○(`MerchantRegistrationScreen.tsx:474`, `lib/merchantCode.ts:10`) | ○(`RegistrationInputRules.kt:21,41`) | ○ 값 객체(`MerchantCode.kt:12`) | ○ | ○ `ck_mca_code`(V13:25) — **다중** |
| 코드 예약어(BR-BIZ-064) | **—** 제안이 예약 접두(`test…`·`demo…`)를 만들 수 있음(`lib/merchantCode.ts:28-58`) — 저장 때 거절 | — | ○ | ○ `MERCHANT_CODE_RESERVED`(`MerchantCode.kt:16-18`) | — |
| 코드 전역 유일·은퇴 코드 점유(BR-BIZ-065·068) | — | — | ○ 사전 조회(`MerchantOnboardingService.kt:106,404`) + 1062 재판정 | △ 자기 배정 코드만(`Merchant.kt:68`) | ○ `uk_mca_code`(V13:19) — **이중** |
| 코드 1회 변경(BR-BIZ-066) | △ 표시 조건(`MerchantCodeChangeRequest.tsx:28-30`) | △ advisory 409(AA/`MerchantOpsApprovalService.kt:387-404`) | ○ | ○ `ALREADY_CHANGED`(`Merchant.kt:64`) | ○ PK + `ck_mca_seq IN (1,2)`(V13:18,24), 권한 SELECT·INSERT(V14:3) — **다중** |
| 첫 계약 승인 뒤 불변(BR-BIZ-066·067) | △ 표시(`JooqMerchantDetailReader.kt:79`) | △ advisory | ○ merchant 잠금 **뒤** 승인 사실 조회(`MerchantOnboardingService.kt:396-398`) | ○ `MERCHANT_CODE_LOCKED`(`Merchant.kt:65`) | — |
| 코드 변경 요청자 ≠ 승인자(BR-BIZ-069) | — | ○ 승인 종류(AA/`domain/ApprovalKind.kt:65`) | ○ `APPROVAL_SAME_ACTOR`(M/`domain/ApprovalEvidence.kt:33`) — **이중** | — 승인자는 판정 입력 아님(`Merchant.kt:51`, Q-MC-10) | — |
| 코드 변경 사유 목록(BR-BIZ-070) | ○ 선택 상자 | ○(`MerchantCodeChangeCommandBody.kt:27`, AA/`MerchantOpsInputRules.kt:81`) | ○ parser(`MerchantApprovalCommandParser.kt:68`) | ○ `MerchantCodeChangeReason` | △ 값 목록 CHECK 없음(V13:15·29-32는 유무만) |
| 코드 변경 = 권리 revision +1(MC1 MC-4) | — | — | ○ 같은 TX(`MerchantOnboardingService.kt:406,416`) | ○ | — |
| 소속 Business 불변(BR-BIZ-060) | — | — | — | ○ `val`(`Merchant.kt:14`) | △ runtime `merchant` UPDATE 권한 있음(PRIV/V2:17). JPA `updatable = false`(`JpaMerchantEntity.kt:19`) + 저장 시 `check`(`JpaMerchantRepository.kt:50`). 계약이 있으면 `fk_contract_merchant`(V1:95)가 간접 보호 |
| 채널 정규형(BR-BIZ-080) | ○(`MerchantRegistrationScreen.tsx:124-128`) | ○(`RegistrationInputRules.kt:119-123`) | ○ `SiteChannel`(`MerchantOperationsRequestParser.kt:78`) | ○ | △ 더 느슨(DC-M-25) |
| 활성 채널 유일(BR-BIZ-081) | — | — | ○ `findActiveByChannel`(`MerchantOnboardingService.kt:131`) | — | ○ `uk_merchant_site_active_channel`(V1:64-65,70) — **이중** |
| 거절 Site 채널 점유 해제(D-GOV-004 ①) | **—** | **—** | **—**(위 행이 막음) | **—** | **—** 생성 열이 생명주기만 봄(V1:64-65) |
| 당사자 = 소속 가맹점의 사업자(BR-BIZ-102) | △ `partyMismatch`(`MerchantRegistrationScreen.tsx:524`) | — | ○ `validScope`(`MerchantOnboardingService.kt:425-429`) | ○ `SCOPE_MERCHANT_OF_OTHER_BUSINESS`(`ContractScope.kt:31`) | ○ `fk_contract_merchant (merchant_id, business_id)`(V1:95) — **다중** |
| 소급 시작 거절(BR-BIZ-107) | △ 날짜 선택 최솟값(`:824`) | — 의도적으로 판정 안 함(`RegistrationInputRules.kt:75`) | 전달 | ○(`Contract.kt:130`) | — |
| 기간 겹치는 유효 계약 금지(D-GOV-003 ①) | **—** | **—** | **—**(`MerchantOnboardingService.kt:151-167` 다른 계약 조회 없음) | **—** | **—**(V1 `contract`에 가맹점 단위 제약 없음) |
| 같은 계약 안 같은 종류 CSA 하나(BR-BIZ-101) | △ 거절 문구가 다른 뜻(§4 GM-07) | — | 계약 잠금 | ○ `SERVICE_KIND_DUPLICATE`(`Contract.kt:81-84`) | ○ `uk_contract_service_agreement_kind`(V1:156) — **이중** |
| 멱등·충돌(BR-BIZ-237) | ○ 같은 `submissionId` 재전송 | ○ 키 `mo1.<계정>.<submissionId>`(`MerchantRegistrationService.kt:102`) | ○ 지문 `h1:` HMAC 비교(`AdminCommandExecutor.kt:72-76`) | — | ○ `admin_request` |
| 결과 불명(외부 호출 TX 밖) | ○ 카드 잠금·재전송 | ○ Unknown·감사 `OUTCOME_UNKNOWN`(`:89-92`), TX 안 호출 금지(`HttpMasterOpsGateway.kt:158`) | ○ commit 불명 → OUTCOME_UNKNOWN | — | — |
| 감사(BR-BIZ-040·236) | — | ○ 등록 2행(`:66,81-85`), 상세 반환 전(`MerchantLookupService.kt:115-117,175-190`), 번호 검색 매 시도(`:76-107`), 감사 실패 = 결과 미반환 | 이력 `master_change_history`(`MerchantOnboardingService.kt:109-117`) | — | — |
| 내부 키 화면 미표시(BR-BIZ-250) | 상세 ○(시험 `MerchantsScreen.iaB4.test.tsx:90,278`) / **마법사 ✗**(UI-03) | — | — | — | — |

[해석] 업무 판정의 권위는 MASTER Domain·Application이다. 화면·ADMIN은 즉시 안내용 복제다(MOPS2 C-10). 사업자 기능(GOV-BIZ-001 §3.2)과 같은 구조다.

---

## 4. 코드 ≠ 정책 불일치

기존 진단 ID가 있는 행은 **새로 확인한 사실만** 적는다. 우선순위: P0 금융·보안·데이터 손상, P1 정책 충돌·상태 전이, P2 경계·아키텍처, P3 관리자 기능·UI, P4 문서·명명.

| ID | P | 불일치 | 근거(정책 ↔ file:line) | 기존 ID | 영향 |
|---|---|---|---|---|---|
| GM-01 | P3 | **기존 가맹점에 Site(·계약 초안)를 붙이는 화면 경로가 없다.** 새로 확인한 사실 ① MOPS2 §5.2의 "기존 ID로 이어서" 입력은 MC-2b(D-MCODE-003 ①)로 없어졌고, 대신 쓰려던 `offerMerchant`("다른 화면이 '지금 보는 가맹점'을 넘길 때도 같은 경로" — KDoc)는 **마법사 안과 시험에서만 호출된다** ② Site 카드는 이 마법사 메모리가 아는 가맹점만 받는다 ③ 메모리는 '새 가맹점 등록 시작'·세션 경계에서 비워진다 ④ 가맹점 상세에는 Site 추가 버튼이 없다. 그런데 Site 거절 확인 문구는 "재신청은 새 Site로 해야 합니다"이다 | D-MS-005·BR-BIZ-085("재신청은 새 Site"), D-GOV-004 ①("같은 주소로 새 Site 재신청 가능"), D-MCODE-003 ① ↔ FE/`MerchantRegistrationScreen.tsx:206-216`(정의), `:484`(유일한 호출), FE/`App.tsx:312-324`(넘김 없음), `MerchantRegistrationScreen.tsx:495`(아는 가맹점만), `:599-602`, FE/`components/MerchantActivationArea.tsx:345`. API는 있음(A/`MerchantRegistrationController.kt:33`) | 없음(새로 확인). 관련 DC-M-02·PC-01 | 거절 뒤 재신청·두 번째 웹사이트 추가를 화면으로 할 수 없다. 결정(D-GOV-004 ①)을 시행해도(GM-02) 운영자가 쓸 길이 없다 |
| GM-02 | P1 | **거절 Site 채널 점유 해제(D-GOV-004 ①)가 시행되지 않았고, 지금 DB 구조로는 앱 수정만으로 시행할 수 없다.** 재확인(DC-M-02·DC-M §3.3과 같은 내용): 활성 채널 유일은 앱 사전 조회 + DB `UNIQUE(active_channel_key)` 이중이고, 생성 열은 생명주기만 보며, runtime은 `merchant_site`에 SELECT·INSERT만 있다. **새로 확인한 사실** ① 심사 결정은 별도 표(`merchant_site_review_decision`)라 생성 열이 볼 수 없다 ② 그래서 어느 길이든 **업무 schema migration이 필요**하다(점유 표현 변경. 권한 변경도 Flyway privilege migration) ③ 결정(`7d521fd`) 이후 관련 코드 변경이 없다 | D-GOV-004 ①(`DEC:259`) ↔ M/`MerchantOnboardingService.kt:131`, SQL/`V1:64-65,70`, SQL/`V3__entitlement_snapshot.sql:141-156`(DC-M §3.3), PRIV/`V2:20` | DC-M-02, PC-01, NP-05(결정 전 진단 — 지금은 결정 완료) | 같은 도메인을 다시 심사받을 수 없다. 해제의 적용 범위(다른 가맹점 재사용 — 거절 경로 무관, SUSPENDED→REJECTED)는 넓힌 RULE-CONFIRM-001 Q14 답 대기(Coordinator 결정 (a)) |
| GM-03 | P1 | **기간 겹치는 유효 계약 금지(D-GOV-003 ①)가 어느 계층에도 없다.** 재확인(DC-M-04와 같은 내용): 계약 등록에 기존 계약 검사가 없고 DB에도 가맹점 단위 제약이 없다. **새로 확인한 사실** ① Revision 승인(근거 경로·seed 경로)·CSA 추가도 같은 가맹점의 다른 계약을 읽지 않는다 ② CSA 추가 경로는 contract 행만 잠가(`MerchantOnboardingService.kt:249-251`) merchant를 먼저 잠그는 승인과 직렬화되지 않는다 ③ 마법사 '계약 초안 새로 입력'과 결과 불명 뒤 '그래도 새로 입력'으로 같은 가맹점에 두 번째 계약을 만들 수 있다(MOPS2 §5.3도 "중복 행이 생길 수 있다"고 적음) ④ 승인은 merchant 행을 먼저 잠그므로(`:222`) 같은 가맹점 승인끼리는 이미 직렬화된다 ⑤ 이미 겹치는 승인 계약이 DB에 있는지는 확인하지 않았다(미확인) | D-GOV-003 ①(`DEC:258`) ↔ M/`MerchantOnboardingService.kt:151-167,212-237,249-267`, M/`domain/Contract.kt:62-86`, SQL/`V1:84-98`, FE/`MerchantRegistrationScreen.tsx:757` | DC-M-04, PC-33, NP-07(결정 전 진단 — 지금은 결정 완료) | 같은 서비스에 승인된 유효 계약이 둘이 되면 수수료·정산 근거 CSA가 모호하다(지금은 MID 1개라 실제 영향 작음 — D-GOV-005 ①) |
| GM-04 | P4 | **대장이 D-GOV-003 ①·D-GOV-004 ①②를 반영하지 않았다.** BR-BIZ-082는 "미결(Q-MD-18)"인데 Q-MD-18은 ANSWERED(D-GOV-004 ②)다. BR-BIZ-085에 D-GOV-004 ①이 없고, D-GOV-003 ①에 해당하는 BR 행이 없다. 진단 (b)-1·(e)-3·(f)-1·(f)-2도 결정 전 서술 그대로다 | D-GOV-006 ②(대장 = 정본, 결정·대장 같은 커밋) ↔ `docs/rules/BIZ.md` BR-BIZ-082·085 행, §진단 (b)-1·(e)-3·(f)-1·(f)-2, questions Q-MD-18·Q-GOV-U07a·U09a ANSWERED | 없음(새로 확인) | 대장을 보고 구현하면 이미 결정된 규칙을 미결로 읽거나 빠뜨린다 |
| GM-05 | P4 | **MOPS2 §5.1·§5.2(마법사 화면 서술)에 뒤 결정 대체 표시가 없다.** §5.1 "'가맹점 등록' 탭"(→ 하위 화면 D-ADMIN-UI-006), §5.2 ① 사업자 등록번호·사업자명 입력 단계(→ BUSINESS-PROFILE-001 C-27로 제거), "각 단계 위에 '기존 ID로 이어서' 입력"(→ MC-2b D-MCODE-003 ①), 완료 패널 "만든 ID 목록"(→ D-ADMIN-UI-004 ③·005) | MOPS2:210-230(특히 `:225`) ↔ FE/`navigation.ts:96`, FE/`MerchantRegistrationScreen.tsx:83-89,164-176`. PC-59는 MOPS2 §4.4·L-7만 다룸 | PC-59 보충 | 명세를 근거로 ID 직접 입력·사업자 입력 단계를 되살리는 구현·리뷰가 나올 수 있다 |
| GM-06 | P4 | MNS1 §9 배치 표가 MNS-3을 "구현(리뷰·게이트 전)"으로 적는다. 작업 기록은 DONE(MNS3-gate1 PASS)이다 | MNS1:95(머리 rev 0.4 `:6`도 같음) ↔ `harness/archive/tasks-done-2026-11-22.json` MERCHANT-NAME-SEARCH-001 | 없음(새로 확인) | 재개 판단 혼동(작은 영향) |
| GM-07 | P4 | 거절 code `SERVICE_KIND_DUPLICATE`의 화면 문구가 "이미 같은 서비스 종류의 **계약**이 있습니다"다. 실제 규칙은 "같은 **계약 안**에 같은 종류 서비스 계약은 하나"다. 문구는 가맹점 단위 규칙(D-GOV-003 ① — 미시행)처럼 읽힌다 | BR-BIZ-101 ↔ FE/`components/RegistrationStep.tsx:33`, M/`domain/Contract.kt:81-84` | 없음(새로 확인). 관련 UI-20 | 운영자가 가맹점 단위 중복이 막힌다고 오해한다(실제로는 막히지 않음 — GM-03) |
| GM-08 | P4 | 가맹점명 복제 규칙이 화면만 다르다. 화면 `validName`은 `value === value.trim()`이라 NBSP·전각 공백(U+3000) 등 유니코드 공백 전체를 앞뒤에서 거절한다. ADMIN·MASTER는 U+0020만 본다. 화면이 더 엄격해 API로는 들어가는 값이 화면에서는 막힌다 | MOPS2 C-10(복제는 같은 규칙) ↔ FE/`lib/registration.ts:9` vs A/`RegistrationInputRules.kt:101`, M/`domain/DisplayName.kt:8` | DC-M-24·SC-19 보충(문자열 규칙 여러 벌) | 효과 없음(거절 방향). 복제본 drift 신호 |
| GM-09 | P3 | 마법사 '서비스 계약' 카드의 **계약 ID 자유 입력**으로 이 마법사 맥락 밖 계약(다른 가맹점 계약, 이미 승인된 계약)에도 1인 CSA를 붙일 수 있다. ADMIN은 형식만, MASTER는 계약 존재·같은 종류 중복만 본다. **권한 우회는 아니다** — 가맹점별 소유 권한 모델이 원래 없고 OPERATOR는 어느 가맹점에든 등록 명령을 낼 수 있다(A/`application/required/MasterCommand.kt:33`). 문제는 **오입력 방지**다: Site 범위는 `validScope`로 소속을 검증하는데(M/`MerchantOnboardingService.kt:425-429`) CSA 추가만 맥락 검증이 없고, CSA는 철회 경로가 없어(PRIV/`V2:30` INSERT만, DC-M-06) 잘못 붙이면 되돌릴 수 없다 | D-ADMIN-UI-005("직접 입력 칸은 목록 선택으로") ↔ FE/`MerchantRegistrationScreen.tsx:552,864-866`, A/`RegistrationInputRules.kt:45`, M/`MerchantOnboardingService.kt:249-256`, M/`domain/Contract.kt:81-86` | UI-03·PC-14(직접 입력 칸), DC-M-08(CSA 1인 구조) 보충 | 키 복사 실수로 남의 계약에 서비스 권리를 붙이면 되돌릴 수 없다. 서비스 종류가 하나라 지금은 CSA 없는 계약일 때만 해당 |

#### 이번 범위에서 정상화 필수가 아닌 관련 항목

참조만 하고 집계에 넣지 않는다. 모양·구조 정리 항목이며 가맹점 업무 규칙과 어긋나지는 않는다.

- UI-12·NP-78: 큰 파일(MerchantsScreen 1,140줄, MerchantRegistrationScreen 979줄).
- UI-16·18·19: 입력 줄 세 방식(코드 변경 폼은 `Field` 위 label), 2인 요청 폼 복사, 결과 불명 표시 두 모양.
- UI-26: `lib/merchantLabel.ts`·`lib/merchantLabels.ts` 이름 혼동. UI-30: `MerchantsScreen.*.test.tsx` 11파일.
- SC-07: A/`MerchantLookupService.kt:179,194`·`MerchantRegistrationService.kt`가 현재 시각을 직접 읽는다.
- DC-M-15: master Modulith 모듈 하나.

#### 확인했고 일치하는 것(요약)

- 상세 감사는 MASTER가 답했을 때만, 결과 반환 전에 남고 실패하면 결과를 주지 않는다(A/`MerchantLookupService.kt:113-120,175-190`). 목록·이력은 감사 없음(D-MOPS-002, D-GOV-003 ④).
- 번호 검색은 역할 없음(VIEWER 등)도 `FORBIDDEN` 감사, 형식 위반 `brn=INVALID`, 공유 번호 버킷(`:76-107`).
- 감사 대상 저장값은 MC1 C-32 표와 같다 — 가맹점 등록 성공·상세 FOUND = 코드, Site·계약 = `mer_`(`MerchantRegistrationService.kt:78-85`, `MerchantLookupService.kt:116-117`).
- 코드 변경 요청과 계약 승인 요청은 같은 미해결 그룹이라 동시에 대기하지 않는다(FE/`MerchantCodeChangeRequest.tsx:28-30`, MC1 §5.2). MASTER는 merchant 잠금 뒤 승인 사실을 읽어 순서를 직렬화한다(`MerchantOnboardingService.kt:396-398`).
- 화면 `merchantCodeChangeable` 계산(`JooqMerchantDetailReader.kt:79`)은 Domain 판정(`Merchant.kt:63-65`)과 같은 조건이다(ACTIVE·변경 0회·승인 사실 없음).
- 사용중지 사업자는 마법사에서 고를 수 없고(`MerchantRegistrationScreen.tsx:350`), MASTER도 `BUSINESS_NOT_REGISTERED`로 거절한다(`Business.kt:40-42`). 종료 사업자 문구는 D-GOV-008 ①을 따른다(`RegistrationStep.tsx:21-22`).
- 가맹점명은 사실상 불변이다 — 변경 메서드가 없고 저장 시 이름을 복사하지 않는다. 이름 불변을 정한 정책도 없다(F-11 — 결정 사항으로 §6).

### 4.1 기존 진단 재확인(`d02f576` 기준 유효 — 새 사실 없음, 집계 제외)

| 기존 ID | 가맹점 기능 관련 내용 | 확인 근거 |
|---|---|---|
| UI-03 / PC-14 / NP-27 | 마법사가 `site_`·`ctr_`·`csa_`를 표시하고 'Site ID 직접 입력'·'계약 ID' 칸으로 받는다(SCC1 BACKLOG) | FE/`MerchantRegistrationScreen.tsx:705,756,795,804-813,858,864-866,909,925,936` |
| DC-M-16 / NP-67 / PC-45 | 새 가맹점 관문(계약 완료) 없음 — FLOW-3 | M/`Merchant.kt:91-96` |
| DC-M-08 | CSA는 계약 승인 여부와 무관하게 1인 추가(결정 없음 — §2.2) | M/`Contract.kt:81-86` |
| DC-M-09 / NP-35 | 계약 승인·CSA 추가가 당사자·대상 생명주기 미검사(종료 경로 없음) | M/`Contract.kt:62-86` |
| DC-M-11 / NP-37 | 가맹점당 MID 1개 — D-GOV-005 ①로 의도 확정 | MC1·DC-M §3.5 |
| DC-M-25 | Site 채널 DB CHECK가 Domain보다 느슨 | SQL/`V1:74-78` |
| UI-20 / PC-10 | 서비스 계약 표기 '서비스 계약'·'서비스 계약(CSA)'·'계약·CSA'·'서비스 약정(CSA)'·'서비스 약정 추가' 혼재(§7 용어 표) | FE/`MerchantsScreen.tsx:254`, `components/FirstMidRequest.tsx:118`, `lib/historyTarget.ts:18`, `lib/merchantChangeTypes.ts:22` |
| IA1 §7.3 NIT-2 / PII #18 | 등록자 `admin:adm_…` 표시 미결. **변경 이력 탭 '행위자' 열도 같은 값을 보인다**(시험 fixture `MerchantsScreen.test.tsx:68` — 금지 접두어 목록에 `adm_` 없음 `iaB4.test.tsx:90`) | FE/`MerchantsScreen.tsx:916` |

### 4.2 집계

| 우선순위 | 건수 | ID |
|---|---|---|
| P0 | 0 | — |
| P1 | 2 | GM-02, GM-03(둘 다 결정 완료 — 시행 대기, 기존 ID에 사실 보탬) |
| P2 | 0 | — |
| P3 | 2 | GM-01, GM-09 |
| P4 | 5 | GM-04, GM-05, GM-06, GM-07, GM-08 |
| 합계 | 9 | 새 사실 중심: GM-01·04·06·07 / 기존 ID에 사실 보탬: GM-02(DC-M-02)·03(DC-M-04)·05(PC-59)·08(DC-M-24·SC-19)·09(UI-03·DC-M-08). 재확인만 한 기존 진단은 §4.1(집계 제외). 기능 없음 F-11은 정책이 없어 불일치로 세지 않았다(§6 QM-2) |

---

## 5. 최소 수정 사항

가맹점 등록·조회·수정 정상화에 꼭 필요한 것만 적는다. "결정 완료 — 시행만"은 새 사용자 결정이 필요 없다는 뜻이다.

| # | 구분 | 수정 | 근거 | 결정 상태 | 검증 방법(제안) | 난이도 |
|---|---|---|---|---|---|---|
| MF-1 | 코드 + migration + 시험 + 대장 | 거절 Site의 채널 점유를 푼다. **사실(GM-02)**: 점유는 DB 생성 열(생명주기만 봄)이 강제하고 심사 결정은 다른 표라, **어느 길이든 업무 schema migration이 필요**하다(점유 열·별도 점유 표 등 점유 표현 변경. runtime 권한을 바꾸는 길도 Flyway privilege migration). 방식 선택은 Coordinator 구현 결정이지만 되돌리기 쉬운 결정은 아니다(migration·데이터 영향 — 리뷰 대상). 점유 해제는 **REJECTED 결정·이력·Outbox와 같은 MASTER 로컬 TX**에서 한다. 제약: '거절 = Site 종료'로 구현하면 사용자가 고르지 않은 대안이 되므로 안 된다. 앱 사전 조회(`findActiveByChannel`)와 DB 제약을 함께 바꿔 이중 강제를 유지한다. 권리 Snapshot `sites[]`에 주는 영향은 미확인(§8) | GM-02 | **(가) 같은 가맹점의 재신청(PENDING→REJECTED로 직접 거절된 Site) — 부분 시행 가능.** D-GOV-004 ① 문언(전역 해제)보다 **좁힌 fail-closed 부분 시행**임을 명세·커밋에 적는다. **(나) 다른 가맹점 재사용(직접 거절·SUSPENDED→REJECTED 모두)과 SUSPENDED→REJECTED로 푼 채널 — 넓힌 RULE-CONFIRM-001 Q14 답 대기**(Coordinator 결정 (a). 답 전에는 다른 가맹점에 대해 점유 유지 — fail-closed) | MASTER 시험: 등록 → 거절 → 같은 가맹점 같은 채널 새 Site 성공(거절 기록 유지), 다른 가맹점은 (나) 답 전 `SITE_CHANNEL_DUPLICATE` 유지, SUSPENDED→REJECTED Site는 (나) 답 전 점유 유지, 활성 Site끼리 중복은 1062·`SITE_CHANNEL_DUPLICATE`(실제 MySQL 두 연결 경합), 거절 TX rollback 시 점유 그대로, runtime 권한 시험 | 중~상 |
| MF-2 | 화면 + 시험 | 가맹점 상세(Site·계약 탭)에 'Site 추가' 진입을 두고 마법사에 `offerMerchant`로 그 가맹점을 넘긴다(또는 상세 안 폼 — 진입 위치는 가역 UI 세부). 내부 키는 요청 본문에만 | GM-01 | **결정 완료 — 시행만**(D-MS-005, D-GOV-004 ①, D-MCODE-003 ①). 같은 채널 재신청은 MF-1 뒤에 동작 | FE 시험: 상세 → Site 추가 → 소속 가맹점 '<가맹점명> · <코드>' 표시·DOM 내부 키 0(13접두어)·요청 본문 `merchantId`, 잠금 중 넘김 거부(`registrationHandoffState`) | 하~중 |
| MF-3 | 코드 + 시험 + 대장 | 같은 가맹점·같은 서비스 종류의 기간 겹치는 유효 계약을 MASTER에서 거절한다. ① 판정 지점: 계약 Revision 승인(근거 경로·seed 경로 모두 — merchant 잠금 뒤, `:222`)과 승인된 계약에 CSA를 더할 때. ② **동시성 공백**: CSA 추가 경로는 지금 contract만 잠근다(`MerchantOnboardingService.kt:249-251`). 판정을 넣으려면 **merchant → contract 순서로 잠근 뒤** 판정해야 승인과 직렬화된다. 기존 잠금 순서(승인 merchant → contract `:222-223`, Revision 등록 business → merchant → contract `:176-179,426`)와 같은 방향이라 교착은 생기지 않을 것으로 보이나 명세 리뷰에서 확인한다. ③ "유효" = 승인 ∧ 효력 기간(BR-BIZ-104·105). 승인 전 초안끼리의 겹침은 막지 않는 해석이 결정 문언("유효 계약")에 맞는다 — 초안 등록까지 막는 쪽은 의미가 바뀌므로 그때 질문한다. ④ **범위 해석**(Site 범위가 서로 다른 계약의 겹침, CSA 없는 승인 계약을 세는지)은 금융 의미 확인 항목으로 분리했다(§6 QM-7). 답 전에 시행한다면 **가맹점 전체 기준의 가장 엄격한 해석**(Site 범위와 무관하게, CSA 없는 승인 계약도 계약 단위로 겹침 판정 — fail-closed)으로 한다. ⑤ 이미 DB에 있는 겹치는 승인 계약의 처리(탐지·Hold 등)는 미확인·후속 작업. 새 거절 code는 `MasterRejection` 추가만(영속 이름 — SC-01) | GM-03 | **결정 완료(원칙 — D-GOV-003 ①) + 범위 해석 확인 필요(QM-7).** 판정 지점·잠금은 명세에서 Coordinator 결정 + 리뷰 | MASTER Domain·DB 시험: 계약 A 승인 뒤 겹치는 계약 B 승인 → 저장된 거절·revision·Snapshot 불변, 안 겹치면 성공, 같은 가맹점 두 승인 동시 → 1건만(merchant 잠금), **계약 A 승인 ∥ 계약 B CSA 추가 동시 → 1건만**(실제 MySQL 두 연결), 같은 계약 Revision 승인은 영향 없음 | 중 |
| MF-4 | 대장 | BR-BIZ-082 → 확정(D-GOV-004 ②), BR-BIZ-085 근거에 D-GOV-004 ① 추가(또는 새 행), D-GOV-003 ① 새 BR 행, 진단 (b)-1·(e)-3·(f)-1·(f)-2 해소 표시 | GM-04 | 결정 불필요(Coordinator — D-GOV-006 ② 정리). MF-1·MF-3과 같은 커밋이면 D-GOV-006 ②에 맞다 | 대장 grep: Q-MD-18 '미결' 0, D-GOV-003·004 근거 행 존재 | 하 |
| MF-5 | 문서 | MOPS2 §5.1·§5.2 머리에 대체 표시(현행 = D-ADMIN-UI-006, BUSINESS-PROFILE-001 C-27, MC1 MC-2b, D-ADMIN-UI-004 ③·005). MNS1 §9 MNS-3 상태를 DONE(MNS3-gate1)으로 표시. 본문은 보존 | GM-05, GM-06 | 결정 불필요(Coordinator — 명세 stale 정리, BIZ-NORM-003 M-4 방식). S3-DOC-001 등 다른 작업이 먼저 대체 표시를 달면 그 결과로 해소만 확인한다 | 문서 대조 | 하 |
| MF-6 | 화면 문구 | `SERVICE_KIND_DUPLICATE` 문구를 "이 계약에 같은 서비스 종류의 서비스 계약이 이미 있습니다"처럼 규칙 범위(계약 안)로 고친다. MF-3의 새 거절 code 문구와 함께 둔다 | GM-07 | 결정 불필요(가역 문구) | FE 시험 문구 | 하 |
| MF-7 | 화면 + 시험 | 화면 `validName`을 서버 규칙(U+0020·제어문자·100 code point)과 같게 맞추고 공통 벡터로 대조한다(권위는 MASTER — C-10). 서버를 더 엄격하게 바꾸는 쪽은 받는 값이 바뀌므로 하지 않는다 | GM-08 | 결정 불필요(복제 규칙 정합) | 공통 벡터(앞뒤 U+0020·U+00A0·U+3000·탭·100/101자)로 FE·ADMIN·MASTER 결과 비교 | 하 |
| MF-8 | 화면 + 시험 | '서비스 계약' 카드의 계약 ID 자유 입력을 D-ADMIN-UI-005 문언대로 **'해당 가맹점의 계약 목록'에서 고르기**로 바꾼다(맥락 가맹점 = 소속 가맹점). **화면만 고치고 API·MASTER 설계는 그대로**다(권한 모델 변경 아님 — GM-09는 오입력 방지). 사람용 코드 표시는 SCC1이 선행이지만 목록 선택은 코드 없이도 할 수 있다 | GM-09 | **결정 완료 — 시행만**(D-ADMIN-UI-005 "직접 입력 칸은 목록 선택으로") | FE 시험: 자유 입력 칸 없음, 선택지 = 소속 가맹점의 계약만, 요청 본문 `contractId` | 하~중 |

- 넣지 않은 것: UI-03의 내부 키 **표시** 제거는 SCC1(사람용 코드 발급)이 선행이라 그 작업으로 둔다. 'Site ID 직접 입력' 칸(UI-03)도 같은 이유로 SCC1과 묶는다 — MF-8은 CSA 칸만 먼저 고친다.
- 관문(D-FLOW-001 ①)은 FLOW-3에서 한다(DC-M-16).
- 가맹점명 수정(F-11)은 결정 전이라 넣지 않았다(QM-2).

---

## 6. 사용자 결정 사항

확정 정책은 다시 묻지 않는다(아래 "질문하지 않음"). 질문 등록(`harness/questions.json`)은 Coordinator가 한다 — 이 문서는 등록하지 않았다.

> **메모(Coordinator용)**: 아래 질문은 RULE-CONFIRM-001 라운드와 **같은 아침 묶음**이다. 두 문서의 질문을 합쳐 Coordinator가 한 번에 3개 이하로 순서를 정한다. 이 문서의 순서는 제안일 뿐이다. 거절 Site 채널 질문은 Coordinator 결정 (a)로 **넓힌 RULE-CONFIRM-001 Q14** 하나로 묻는다(아래 문단).

rev 0.1의 QM-1(거절 Site 채널을 다른 가맹점이 쓸 수 있는가)은 RULE-CONFIRM-001 Q14로 합친다. [사실] `a08aa48`의 R5-Q14 문안(`:184-189`(a08aa48 기준 — rev 0.1b에서 Q14 권장안 확대, 같은 커밋))은 SUSPENDED→REJECTED 경로만 좁히는 안을 묻는다. **Coordinator 결정 (a)**: Q14 문안을 '거절 경로(직접 거절·SUSPENDED→REJECTED)와 무관하게 다른 가맹점 재사용은 D-GOV-004 ② 기준(예외 승인 없으면 거절), 같은 가맹점 재신청은 허용'으로 넓혀 한 질문으로 묻는다. 이 문서는 그 넓힌 Q14를 참조하고 따로 묻지 않는다(아래 "질문하지 않음").

### 이 문서의 질문(우선순위 제안 순)

**QM-7 (금융 — 수수료·정산 근거) '기간 겹치는 유효 계약 금지'(D-GOV-003 ①)의 세부 범위**
- 배경: 원칙은 확정이다. 다만 코드에 넣으려면 두 가지를 정해야 한다. ① 같은 가맹점의 두 계약이 **서로 다른 Site 범위**(예: 계약 A = Site 1, 계약 B = Site 2)일 때도 "겹침"인가 ② **서비스 계약(CSA)이 아직 없는 승인 계약**도 "같은 서비스 종류의 유효 계약"으로 세는가.
- 권장안: **① 가맹점 단위로 본다(Site 범위가 달라도 겹침) ② CSA가 없는 승인 계약은 세지 않되, 그 계약에 CSA를 더하는 순간 판정한다.** 수수료·정산 정책은 CSA 기준(D-GOV-003 ②)이라 CSA가 생길 때가 근거가 모호해지는 시점이다.
- 대안: (b) Site 범위가 겹칠 때만 겹침(같은 가맹점에 Site별 계약 허용) — **확정 문언(D-GOV-003 ① '한 가맹점')보다 금지를 좁히는 대안** (c) CSA 유무와 무관하게 계약 단위로 셈(가장 엄격 — 답 전 시행 기본값).
- 영향: 어느 계약의 수수료·정산 정책인지의 근거, Site별 별도 계약 허용 여부.
- 막히는 수정: MF-3의 최종 형태(답 전에는 (c)로 fail-closed 시행 가능).

**QM-2 (업무 범위·감사) 가맹점 기본정보(가맹점명) 수정 기능을 둘 것인가**
- 배경: 수정 기능·명세·결정이 모두 없다(F-11). 가맹점명은 식별자가 아니다(식별은 가맹점 코드, 같은 이름 허용 BR-BIZ-062). 이름이 쓰이는 곳(확인한 범위):
  - MASTER 변경 이력·Outbox `MerchantRegistered` payload에 `displayName`이 들어간다(M/`MerchantOnboardingService.kt:112`). 소비자가 없는 REFERENCE 이벤트다(DC-M-14).
  - ADMIN 승인함은 요청 때 가맹점명을 pinned 대상에 **스냅샷**으로 담고, 대상 일치 비교(`sameTargetAs`)는 이름을 **무시**한다(AA/`domain/PinnedContractApprovalTarget.kt:12,16,43` — 다른 Pinned*Target도 같은 틀). 이름이 바뀌어도 진행 중 승인은 무효가 되지 않고, 승인 화면에는 요청 때 이름이 보인다.
  - PAYMENT·권리 Snapshot에서는 찾지 못했다(문자열 검색 0건 — 실행 확인 아님).
- 권장안: **OPERATOR 1인 + '사유 및 비고' + 변경 이력 + version 충돌 거절**(사업자 일반정보 수정과 같은 틀 — D-MOPS-004 분류상 권리를 넓히지 않는 변경). 첫 계약 승인 뒤에도 허용.
- 대안: (b) 만들지 않음 — 바꾸려면 새 가맹점 등록(코드·계약·MID가 새로 생겨 사실상 불가). (c) 2인 승인.
- 영향: 새 MASTER 명령·이력·Outbox·ADMIN API·화면. 승인 화면의 이름 스냅샷과 현재 이름이 달라질 수 있음(표시 규칙 확인 필요).
- 막히는 수정: 가맹점 기본정보 수정 기능 명세 착수.

**QM-3 (용어) 가맹점 범위 정식 용어 후보 일괄 확인(§7.2 표)**
- 권장안: §7.2 후보 그대로 채택 → 독립 리뷰 뒤 BIZ.md '용어' 절에 '가맹점' 소절 추가.
- 대안: 항목별 수정('이전 코드' vs '은퇴 코드', 'Revision' 한국어 표기 등).
- 영향: 화면 문구 정렬 작업(별도 구현)과 대장 용어 정본.
- 막히는 수정: 없음(MF-6 문구는 후보와 무관하게 가능).

### 다음 라운드(나머지)

| ID | 내용 | 권장안(근거) | 막히는 것 |
|---|---|---|---|
| QM-4 | CSA 추가 승인 수준(승인된 계약에 1인 추가 — DC-M-08·**PC-34·NP-34**). U-07 질문 문안에 있었으나 답(D-GOV-003) 범위 밖, 미등록 | 서비스 종류가 늘기 전까지 현행 1인(DC-M §6, PC-34 ③) | 서비스 종류 확장 |
| QM-5 | 가맹점명(개인 이름 포함 가능) 마스킹 — Q-PII-7을 가맹점명까지 넓힐지 | Q-PII-7과 같은 LIVE Gate 질문에 묶음(PC-47 방식) | LIVE Gate |
| QM-6 | 등록자·행위자 `admin:adm_…` 화면 표시(IA1 §7.3 NIT-2, PII #18) | 사람용 표시(로그인 이름 스냅샷) — 등록자 ≠ 승인자 판단 근거는 유지 | 화면 정리 |

### 질문하지 않음(확정이거나 다른 작업이 이미 묻는 것)

| 항목 | 근거 |
|---|---|
| 등록 1인·권리 확대 2인·안전 방향 1인 | D-MOPS-004·007 ③ |
| 등록 역할 OPERATOR, 같은 가맹점명 허용 | D-MOPS-006 ①⑥ |
| 코드 필수·형식 기본·전역 유일·1회 변경·옛 코드 예약·요청자 ≠ 승인자 | D-MCODE-001·002 |
| 마법사 소속 가맹점 맥락 표시, 내부 키 미표시, Site·계약 사람용 코드 | D-MCODE-003 ①, D-ADMIN-UI-004 ③·005·007 |
| 상세 화면 전환·탭·MID 선택 머리·기술 정보 접기·필터 묶음 | D-ADMIN-UI-009·010·011 ① |
| 상세만 감사, 목록 4역할 원문 무감사 | D-MOPS-002, D-PII-001 ②, D-GOV-003 ④ |
| 거절 Site 채널 점유 해제(원칙), 종료 채널 재사용 | D-GOV-004 ①② |
| **거절 Site 채널 해제의 적용 범위(다른 가맹점 재사용 — 직접 거절·SUSPENDED→REJECTED 모두)** | **RULE-CONFIRM-001 Q14가 묻는다** — `a08aa48` 문안(`:184-189`(a08aa48 기준 — rev 0.1b에서 Q14 권장안 확대, 같은 커밋))은 SUSPENDED→REJECTED만 다루므로 Coordinator 결정 (a)로 문안을 넓혀 rev 0.1 QM-1을 합침 |
| 기간 겹치는 유효 계약 금지(원칙) | D-GOV-003 ① — 세부 범위만 QM-7 |
| 가맹점당 MID 1개(현 단계) | D-GOV-005 ① |
| 새 가맹점 = REGISTERED, 계약 완료 관문 | D-BIZ-001, D-FLOW-001 ① |
| "첫 계약 승인" 정의(BR-BIZ-067) | D-GOV-005 ④ 개별 질문 대상 — **RULE-CONFIRM-001 R2-Q5**가 묻는다 |
| "최신 접수 건"(BR-BIZ-280) | RULE-CONFIRM-001 R2-Q4(FLOW-3 전) |
| 코드 형식 세부(BR-BIZ-064) | 금융 영향 없음 → D-GOV-005 ④ 일괄 확인(RULE-CONFIRM-001 목록) |
| 코드 변경 사유 목록(BR-BIZ-070) | **Demo값**(대장 상태 '제안' 아님 — RULE-CONFIRM-001 목록 밖). 운영 사유 체계를 정할 때 다시 정함 |

---

## 7. 용어 대조(D-GOV-009 — `docs/rules/README.md` '용어 절' 절차)

이 절은 **후보 제시만** 한다. BIZ.md 반영은 사용자 확인(QM-3)과 독립 리뷰 뒤에 한다. 정의에는 구현 세부·규칙을 넣지 않고 BR-ID를 가리킨다. 사업자 용어(BIZ.md §0)와 겹치는 것은 그 정본을 따른다.

### 7.1 문서·화면·코드 대조

| 개념 | 문서 | 화면(FE) | 코드·DB | 관찰 |
|---|---|---|---|---|
| 가맹점 | Merchant(MD1 §2.2), BR-BIZ-060 | '가맹점', '가맹점 상세'(`MerchantsScreen.tsx:757`) | `Merchant`, `merchant` | 일치. '상점'은 `uiref`(배포 밖)에만 |
| 가맹점 이름 | BR-BIZ-062 '가맹점 이름', MOPS2 §4.3 '가맹점명', BIZ §0 상호 _Avoid_ '가맹점 이름' | '가맹점명'(`MerchantRegistrationScreen.tsx:674`, `MerchantsScreen.tsx:1060`) | `DisplayName`(KDoc '표시 이름' `DisplayName.kt:3`), `display_name` | 세 이름. 대장 행이 화면과 다름 |
| 가맹점 코드 | D-MCODE-001(토스 '상점아이디' 참고) | '가맹점 코드' | `MerchantCode`, `merchant_code_assignment` | 일치 |
| 가맹점 내부 키 | D-ADMIN-UI-004 ③ '가맹점 ID 간소화', MOPS2 `merchantId` | 표시 없음(MC-2) | `MerchantId` `mer_` | 문서의 '가맹점 ID'가 내부 키와 코드 둘 다로 읽힘 |
| 바뀌기 전 코드 | D-MCODE-002 '옛 코드 영구 예약', MC1 '은퇴', IA1 §4.3 '이전 코드 n개' | '은퇴 — 다시 쓸 수 없음', '코드 이력'(`MerchantsScreen.tsx:195,201`) | `replaces_code`, source `CHANGE` | 세 이름 |
| 코드 변경 사유 | MC1 C-12 고정 목록 | '변경 사유'(`MerchantCodeChangeRequest.tsx:95`) | `MerchantCodeChangeReason` | 사업자 '사유 및 비고'(자유 문구, D-FLOW-006)와 다른 개념인데 사업자 쪽 _Avoid_가 '변경 사유' |
| 첫 계약 승인 | MC1 §3.7.1, BR-BIZ-067(제안) | '계약 승인 전 1회'(`MerchantCodeChangeRequest.tsx:22`) | `hasApprovedRevisionFor` | 정의는 미확정(R2-Q5) |
| Site | MerchantSite, D-MD-008 | 'Site'(IA1 B6에서 '사이트' 0으로 통일) | `MerchantSite` `site_` | 일치 |
| 채널 식별값 | D-MD-009 | '채널 식별값' | `channel_identifier` | 일치 |
| 소속 가맹점 | D-MCODE-003 ① | '소속 가맹점'(`:166`) | `merchantId`·`scopeMerchantId` | 일치 |
| 계약 초안 | MOPS2 §0 'registerContract(초안, Revision 1)' | '계약 초안', '계약 초안(Revision 1, 승인 전)'(`:922`) | 상태 값 없음 — 승인 사실 유무(DC-M §3.4) | 화면 용어만 있고 코드 상태는 없음 |
| 계약 Revision | D-MD-004 | 'Revision'(13파일), '새 Revision 등록' | `ContractRevision` | 영어 그대로. INV는 '계약 조건 변경' |
| 계약 당사자·범위 | D-MD-004·010 Scope | '계약 당사자', '범위' — '가맹점 전체'/'선택 Site' | `ContractScope.MerchantWide/Sites` | 일치 |
| 서비스 계약 | BR-BIZ-100 CSA, D-ADMIN-UI-007 ③ '서비스 계약' | '서비스 계약', '서비스 계약(CSA)', '계약·CSA', '서비스 약정(CSA)', '서비스 약정 추가' | `ContractServiceAgreement` `csa_` | 다섯 표기(UI-20) |
| 중복 거절 문구 | BR-BIZ-101(계약 안) | '이미 같은 서비스 종류의 계약이 있습니다'(`RegistrationStep.tsx:33`) | `SERVICE_KIND_DUPLICATE` | 문구가 범위를 넓게 말함(GM-07) |
| 가맹점 등록 | MOPS2 B1 '가맹점 등록' 탭(5종 명령 전체) | 화면 제목 '가맹점 등록'(마법사 전체) + 카드 버튼 '가맹점 등록'(가맹점 한 건) | `REGISTER_MERCHANT` | 같은 말이 마법사 전체와 가맹점 저장 한 번을 가리킴 |
| 등록일(가맹점) | MOPS1 | '등록일'(`MerchantsScreen.tsx:185`, 목록 열) | `created_at` | 사업자는 '최초 저장일'(D-GOV-008 ②). 가맹점은 임시저장이 없어 처음 저장 = 등록 |
| 계약(가맹점) vs 계약 완료(사업자) | D-MD-004 승인·철회 vs D-FLOW-001 계약 완료·해지 | '계약 승인 요청' vs 사업자 '계약 진행' | `Contract` vs `ContractIntake` | 동명이의 — D-GOV-008 ⑥으로 사업자 쪽 정리됨(PC-10) |

### 7.2 정식 용어 후보(_Avoid_ 형식 — 사용자 확인 전 초안)

- **가맹점**: 사업자 아래에서 결제를 받는 영업 단위(사업자와의 관계는 BR-BIZ-001·060). _Avoid_: 상점, 상호, 사업자. — 구조 §13
- **가맹점명**: 운영자가 가맹점을 알아보려고 붙인 이름. 식별자가 아니다(같은 이름 허용은 BR-BIZ-062). _Avoid_: 가맹점 이름, 상호, 표시 이름. — D-MOPS-006 ⑥
- **가맹점 코드**: 사람이 읽는 가맹점 식별 값(형식·유일·변경은 BR-BIZ-063~069). _Avoid_: 가맹점 ID, 상점아이디, MID. — D-MCODE-001·002
- **가맹점 키**: 시스템 내부 가맹점 식별자(화면 노출 규칙은 BR-BIZ-250). _Avoid_: 가맹점 ID(화면), 가맹점 코드. — D-ADMIN-UI-004 ③, D-MCODE-003
- **이전 코드**: 바뀌기 전의 가맹점 코드(재사용 규칙은 BR-BIZ-068). _Avoid_: 은퇴 코드, 옛 코드. — D-MCODE-002 *(대안: '은퇴 코드' 유지 — QM-3에서 선택)*
- **코드 변경 사유**: 가맹점 코드를 바꿀 때 고르는 정해진 사유(목록은 BR-BIZ-070). _Avoid_: 사유 및 비고(사업자 공통 자유 입력 칸), 변경 사유(단독). — MC1 C-12
- **첫 계약 승인**: 가맹점 코드가 굳는 시점을 가르는 계약 승인 사실(정의는 BR-BIZ-067 — 확정 전이라 용어 반영 보류). _Avoid_: 계약 효력 시작, 계약 완료. — MC1 §3.7.1
- **Site**: 결제를 받는 채널 하나(웹 도메인 또는 앱). 심사 단위다(BR-BIZ-083·085). _Avoid_: 사이트, 상점, 홈페이지. — D-MD-008, D-MS-002
- **채널 식별값**: Site를 가리키는 웹 도메인 이름 또는 앱 식별자(형식은 BR-BIZ-080). _Avoid_: URL, 주소, 도메인(앱 포함 시). — D-MD-009
- **소속 가맹점**: Site·계약이 딸린 가맹점. _Avoid_: 대상 가맹점(계약 범위와 혼동), 가맹점 ID. — D-MCODE-003 ①
- **계약 초안**: 아직 승인되지 않은 가맹점 계약(결제 권리와의 관계는 BR-BIZ-109·139). _Avoid_: 임시저장(사업자 등록 상태), 미승인 계약. — D-MOPS-004
- **계약 Revision**: 계약 내용(범위·기간)의 한 판(변경·불변 규칙은 BR-BIZ-104). _Avoid_: 계약 버전, 계약 갱신(같은 서비스 계약 갱신 BR-BIZ-112와 다름). — D-MD-004 *(한국어 표기 여부는 QM-3)*
- **계약 당사자**: 가맹점 계약을 맺는 사업자(BR-BIZ-102). _Avoid_: 계약자, 계약 가맹점. — D-MD-004
- **계약 범위**: 계약이 미치는 곳 — 가맹점 전체 또는 고른 Site(BR-BIZ-102). _Avoid_: Scope(화면), 대상. — D-MD-010
- **서비스 계약**: 계약 안에서 서비스 종류 하나에 대한 권리(BR-BIZ-100·101). _Avoid_: 서비스 약정, CSA(화면), 약정. — D-MD-001·002, D-ADMIN-UI-007 ③
- **가맹점 등록**: 새 가맹점 한 건을 저장하는 일(가맹점 코드 발급 포함). Site·계약 초안·서비스 계약 등록까지 묶은 화면은 **가맹점 등록 마법사**라 한다. _Avoid_: 가맹점 개통, 가맹점 승인. — D-MOPS-004·006
- **등록일(가맹점)**: 가맹점을 처음 저장한 날. 계약 승인·개통 날이 아니다. _Avoid_: 개통일, 계약일. — MOPS1 §3

### 7.3 경계 시나리오(현재 코드 동작은 판독 기준 — 실행 안 함)

| # | 시나리오 | 지금 동작 | 용어 경계 |
|---|---|---|---|
| S-1 | 마법사에서 가맹점만 저장하고 Site·계약 초안 전에 '새 가맹점 등록 시작'을 눌렀다 | 가맹점은 저장돼 목록·상세에 보인다. 이 가맹점에 Site를 붙일 화면 경로가 없다(GM-01) | '가맹점 등록'은 가맹점 한 건 저장으로 끝난다. 마법사 완료와 다르다 |
| S-2 | 결과 불명 뒤 '그래도 새로 입력'으로 같은 사업자·같은 가맹점명을 다시 저장 | 가맹점 2개·코드 2개가 생긴다(BR-BIZ-062, MOPS2 §5.3 경고) | 가맹점명은 식별자가 아니다. 사람이 가맹점을 가리키는 값은 가맹점 코드다 |
| S-3 | 효력 시작이 다음 달인 계약 Revision이 승인됐다 | 코드 변경이 `MERCHANT_CODE_LOCKED`로 막힌다. 결제는 아직 불가(효력 전) | '첫 계약 승인'은 효력 시작과 다르다(정의 확정은 R2-Q5) |
| S-4 | Site 심사가 거절된 도메인으로 같은 가맹점이 다시 신청 | 지금 `SITE_CHANNEL_DUPLICATE`(GM-02). 결정은 허용(D-GOV-004 ①). 다른 가맹점 재사용(거절 경로 무관)·SUSPENDED→REJECTED 경로는 넓힌 RULE-CONFIRM-001 Q14 답 대기(답 전 다른 가맹점은 점유 유지) | 재신청은 '새 Site'다. 채널 식별값은 Site의 속성이고, 거절은 그 Site의 끝이다 |
| S-5 | 같은 가맹점에 기간이 겹치는 카드 PG 결제 계약 초안을 두 개 만들고 둘 다 승인 요청 | 둘 다 저장·승인 가능(GM-03). '이미 같은 서비스 종류의 계약이 있습니다'는 같은 계약에 서비스 계약을 두 번 더할 때만 뜬다(GM-07) | '서비스 계약'은 계약 안의 권리 단위이고, 가맹점 단위 중복 금지는 '유효 계약' 기준이다(초안은 아님 — MF-3 해석, Site 범위·CSA 유무는 QM-7) |

---

## 8. 미확인

- **시험을 하나도 실행하지 않았다.** 시험 파일 이름은 존재만 확인했다. 예: MASTER `MerchantOnboardingServiceSpec`·`MerchantOperationsApiSpec`·`MerchantOperationsConcurrencySpec`·`MerchantOperationsLostResponseSpec`·`MerchantOperationsRequestParserSpec`·`MerchantCodeSpec`·`MerchantCodeRegistrationSpec`·`MerchantCodeChangeSpec`·`MerchantCodeChangeApprovalSpec`·`MerchantCodeChangeConcurrencySpec`·`MerchantCodeSchemaSpec`·`MerchantNameSearchSpec`·`MerchantRegistrationGateSpec`·`ContractSpec`·`SiteChannelHostVectorsSpec`·`MasterValuesSpec`, ADMIN `MerchantRegistrationServiceSpec`·`MerchantRegistrationHttpSpec`·`MerchantRegistrationBodyParserSpec`·`MerchantLookupServiceSpec`·`MerchantLookupHttpSpec`·`MerchantCodeChangeApprovalServiceSpec`·`MerchantCodeChangeCommandBodySpec`, FE `MerchantRegistrationScreen.test.tsx`·`.workspace`·`MerchantsScreen.*.test.tsx`(11)·`lib/merchantCode.test.ts`·`App.merchantDetailView.test.tsx`.
- GM-01은 화면 코드 판독이다. Demo에서 기존 가맹점에 Site를 붙이려 해 보지는 않았다.
- GM-08의 차이(유니코드 공백)는 `String.prototype.trim` 명세와 Kotlin 조건 비교로 판단했다. 값을 넣어 보지 않았다.
- 가맹점명이 PAYMENT·Webhook·Snapshot으로 나가지 않는다는 판단(QM-2 배경)은 `apps/payment-api`·`EntitlementSnapshotAssembler` 문자열 검색 결과다. 다른 경로(예: FINANCIAL)는 보지 않았다.
- 코드 변경 승인 실행(승인함 TX1·TX2, 회복 job)의 세부는 AA/`MerchantOpsApprovalService.kt` 요청 부분까지만 읽었다(MC1 §5.5 검증 기록에 의존).
- MASTER `MerchantSearchRequestParser`와 ADMIN `MerchantLookupService.wellFormed`(`:138-151`)가 모든 조합에서 같은지는 코드 단일 기준·가맹점명 규칙만 확인했다.
- MF-1의 migration 방식별 영향(기존 행 채우기·권리 Snapshot `sites[]` 변화 여부)은 보지 않았다.
- MF-3: DB에 이미 기간 겹치는 승인 계약이 있는지, 있으면 어떻게 처리할지(탐지·Hold 등)는 보지 않았다(후속). CSA 추가 경로에 merchant 잠금을 더했을 때 교착이 없다는 판단은 잠금 순서 판독일 뿐이다.

## 9. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1b | 2026-11-25 | 재리뷰 반영. **MAJOR N-1** R5-Q14 사실 정정(`a08aa48` 문안은 SUSPENDED→REJECTED만 좁힘) — §2.2·§6 문단·질문하지 않음 표. Coordinator 결정 (a)(Q14 문안을 거절 경로 무관 '다른 가맹점 = D-GOV-004 ② 기준, 같은 가맹점 허용'으로 넓혀 하나로 질문)를 §6 메모·MF-1 (나)·S-4에 반영, 답 전 다른 가맹점 점유 유지(fail-closed) 그대로. **MINOR-1** QM-7 대안 (b)에 '확정 문언보다 금지를 좁히는 대안' 표시. **NIT-2** BR-BIZ-064(RULE-CONFIRM 목록)·070(Demo값) 행 분리. **NIT-3** MF-5에 다른 작업 선행 시 해소 확인만 |
| 0.1a | 2026-11-25 | 독립 리뷰 CHANGES_REQUIRED 반영. **MAJOR-1** QM-1을 RULE-CONFIRM-001 R5-Q14로 합침(질문하지 않음 표에 추가). §2.2 해석 정정 — D-GOV-004 ① 문언은 글자 그대로 전역 해제, 권장안은 적용 범위를 좁히는 대안. MF-1 상태 분리((가) 같은 가맹점 직접 거절 = 좁힌 fail-closed 부분 시행 가능 / (나) 다른 가맹점·SUSPENDED→REJECTED = R5-Q14 대기). **MAJOR-2** MF-3에 CSA 추가 경로 잠금 공백(contract만 잠금 `:249-251`)·merchant → contract 잠금·동시성 검증 추가, 범위 해석을 QM-7(새 질문)로 분리, 답 전 가장 엄격한 해석, 기존 겹침 데이터 처리 미확인·후속, 상태 '결정 완료(원칙) + 범위 해석 확인 필요'. **MINOR** ① GM-02/MF-1 — 어느 길이든 업무 schema migration, '가역' 표현 정정, 점유 해제는 REJECTED 결정·이력·Outbox와 같은 TX, Snapshot 영향 미확인 유지 ② GM-02·GM-03의 DC-M 재기술 부분을 '재확인'으로 표시 ③ GM-09 — 권한 우회 아닌 오입력 방지(OPERATOR 전역), CSA만 소속 미검증, CSA 철회 경로 없음, MF-8 '해당 가맹점의 계약 목록'·화면만 수정 ④ QM-2 배경에 Outbox `displayName`·승인 pinned 이름 스냅샷 ⑤ BR-BIZ-070 근거 문구 ⑥ QM-4에 PC-34·NP-34 ⑦ §7.2 용어 3개(가맹점·계약 초안·계약 Revision)에서 규칙 문장 제거 ⑧ §4.2 GM-05 분류를 기존 ID 보탬으로 일치 ⑨ §6 머리에 아침 묶음 메모 |
| 0.1 | 2026-11-25 | 최초 작성(기능 11 — 구현 10·없음 1, 불일치 9 — P1 2·P3 2·P4 5, 최소 수정 MF-1~8, 질문 QM-1~3 + 다음 라운드 3, 용어 후보 17·경계 시나리오 5) |
