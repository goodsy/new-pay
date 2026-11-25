# ADMIN-UI-DIAGNOSIS — 관리자 UI·React 소스 컨벤션 진단 (rev 0.1)

- 작성: 2026-11-25, GOV-DIAG-001(관리체계 정상화 진단)의 관리자 UI·컨벤션 부분. **진단만 했다.** 소스·시험·git 변경은 없고, vite·vitest·게이트는 실행하지 않았다(정적 읽기와 grep/스크립트 집계).
- 기준: main `41139db`(2026-11-25 09:22 KST). `apps/internal-admin/frontend`에는 작업 트리 변경이 없다(`git status --short` 0줄).
  - 기준 파일 sha256 앞 16자: `App.tsx` `a1f2690757f773c8`, `workspace.css` `3270fab43b71bda1`, `styles.css` `7f39210f4ad43229`, `MerchantRegistrationScreen.tsx` `25a456a359077601`, `MerchantContractApprovalDetail.tsx` `683f976eeb6eaca1`.
- 대상: `apps/internal-admin/frontend/src`. 로컬 미리보기 `src/uiref`(3,622줄)는 참고자료(ADMIN-DS-001 §1)라 집계에서 뺐다. 결제창 `apps/payment-api/frontend`는 경계만 본다(§7).
- 다른 진단과의 경계: 메뉴·기능·API 인벤토리는 [ADMIN-FEATURE-INVENTORY.md](ADMIN-FEATURE-INVENTORY.md)가 맡는다. 이 문서는 기능 목록을 다시 쓰지 않고 **UI 구조·디자인 시스템·컨벤션·시험 구성**만 본다.
- 앞선 진단 [FRONTEND-ASSESSMENT.md](../assessment/FRONTEND-ASSESSMENT.md)(2026-11-20, `378aff7`)는 고쳐 쓰지 않는다. §2에서 그 뒤 달라진 점만 확인·반박한다.
- 표기
  - **[사실]**: file:line, grep·스크립트 결과. 수치는 아래 §8 방법으로 셌다.
  - **[추정]**: 해석. 결정이 필요한 것은 "질문 필요"로 적는다(정책은 이 문서가 정하지 않는다).

## 0. 결론 요약

| 우선순위 | 뜻 | 건수 |
|---|---|---|
| P0 | 금융·보안·데이터 | 0 |
| P1 | 정책 충돌·상태 | 3 |
| P2 | 경계·아키텍처 | 8 (UI-04~11) |
| P3 | 관리자 기능·UI 불일치 | 14 (UI-12~25) |
| P4 | 문서·명명·편의 | 5 (UI-26~30) |
| **합계** | | **30** |

- **P0 없음의 근거** [사실]: main 코드에 `style=` 0, `dangerouslySetInnerHTML` 0, `fetch(` 는 `api/client.ts` 밖 0, 브라우저 저장소는 테마 키만(`lib/theme.ts:17·26·27`), 결과 불명 잠금은 공용 hook 하나(§2). 카드정보 입력 칸 없음. 금융 이중 효과로 바로 이어지는 화면 결함은 찾지 못했다.
- **가장 큰 위험은 "정한 규칙과 화면이 다른 곳"이다.** 내부 키 비노출 규칙(DS §3.2·PII #25·D-ADMIN-UI-005·010 ③)이 가맹점 화면에서는 지켜지지만, 승인 상세·가맹점 등록·승인 ID 표시에서는 지켜지지 않고 시험이 그 노출을 단언한다(UI-02·03·04).
- **디자인 시스템 이행은 B1·V1까지만 왔다.** shadcn 사용 파일 27, 두 체계를 함께 쓰는 파일 16, `workspace` 분기는 앞선 진단보다 늘었다(17 → 22). §3.1 금지 utility는 453개다.

### 상위 10

| 순위 | ID | P | 한 줄 |
|---|---|---|---|
| 1 | UI-01 | P1 | 승인·거절·철회 응답 유실 시 "잠시 후 다시 시도"만 보이고 목록·상세를 다시 읽지 않는다(결과 불명 표시 없음, 시험 없음) |
| 2 | UI-02 | P1 | 승인함 가맹점 상세가 `csa_`·`site_`·`ctr_`·`act_`를 그리고 시험이 단언한다 — 명세 간 충돌(질문 필요) |
| 3 | UI-03 | P1 | 가맹점 등록 화면이 `site_`·`ctr_`·`csa_`를 표시하고 직접 입력받는다(D-ADMIN-UI-005 결정, 구현 작업 BACKLOG) |
| 4 | UI-04 | P2 | 내부 키 금지 접두어 목록이 문서·코드·시험에 네 가지(5·7+·11·13개), 가맹점 밖 화면 시험은 `mer_`·`biz_`만 본다 |
| 5 | UI-05 | P2 | 승인 ID(`apr_`)를 4화면이 그리는데 가맹점 화면은 숨긴다 — 같은 값, 다른 규칙 |
| 6 | UI-07 | P2 | `workspace` 모양 분기 22곳·전달 29곳(진단 당시 17·19에서 증가), 한 폼이 boolean과 `layout` variant 두 축을 가진다 |
| 7 | UI-06 | P2 | 두 스타일 체계 공존: shadcn import 27파일, 시안 class와 함께 쓰는 파일 16 |
| 8 | UI-08 | P2 | 구조 단언 시험 증가(`toHaveClass` 210 → 224줄, `querySelector` 304 → 341줄), B4 전환 미착수 |
| 9 | UI-10 | P2 | App 세션 리셋 3벌(메모리 5종 × 3 = 15호출)·flush 화면 10개 하드코딩이 진단 뒤에도 그대로 |
| 10 | UI-18 | P3 | 2인 승인 요청 폼 6벌이 같은 오류 표·근거 참조 규칙을 복사(문구 11곳, 정규식 2벌) |

## 1. 기준 문서 적용 상태

| 기준 | 내용 | 적용 상태 [사실] |
|---|---|---|
| ADMIN-DS-001 §8 | B1 토큰 편입 → V1 대비 보정 → B2 원자 → B3 줄·카드·패널 → B4 구조 단언 → B5 Tailwind 정리 | B1 DONE(`harness/tasks.json`), V1 커밋 `41139db`(task IN_PROGRESS), **B2~B5 미착수**. §7.3 시험 중 B1 것(토큰 대비·리터럴·다크 선택자·assetsInlineLimit)만 있고 B2(모양 분기 0·shadcn 잔존 0)·B5(금지 utility 0) 시험은 없다 |
| ADMIN-DS-001 §7.1 보호 시험 | 삭제·완화 금지 목록 | 모두 있다: `test/noRuntimeStyles.test.tsx` 11개, `WorkspaceShell.test.tsx:75` 샘플 비노출, `lib/theme.test.tsx:114` prefers-color-scheme 없음, `lib/designTokens.test.ts:255` assetsInlineLimit 0, `uiref/UiRefIsolation.test.ts` 13개, `domOccurrences` 사용 28파일 |
| ADMIN-DS-001 §5 카탈로그 | 역할 하나에 정본 하나 | 버튼·입력·표·태그·알림·제목 줄·입력 줄·상세 한 줄·상세 패널 모두 2~3벌 공존(UI-06·13·15·16·17·23). `DetailTabs`·`FilterPanel`·`StatusBadge` tone 규칙(`lib/statusTone.ts` 한 곳)은 정본대로 |
| ADMIN-DS-001 §3.2 | `style=`·inline script·외부 자원 없음, 테마는 data-theme | 지켜짐. TSX의 hex 17개는 전부 주석(UI-27) |
| ADMIN-UI-001 §3 | code→한국어 `labels.ts` 한 곳, 상태 색은 의미만, 양식 label 위 | 상태 색은 한 곳. code→한국어 표는 `labels.ts` 밖에 약 24개(UI-21). "label 위"는 D-ADMIN-UI-005 ④(이름 왼쪽)와 충돌하는 낡은 문구(UI-16) |
| MERCHANT-IA-001 | 사업자·가맹점 항목 정리(B1~B6) | task DONE. 가맹점 화면 DOM 내부 키 13접두어 시험(`MerchantsScreen.iaB3.test.tsx:37`, `iaB4.test.tsx:90`), 결제 준비 줄·DetailTabs·FilterPanel 있음. B1 NIT-2 '사이트'/'Site' 통일은 화면 문자열에서 '사이트' 0으로 **해소 확인** |
| PII-MASKING-POLICY §3 | 화면 표시 규칙 | 사업자등록번호 원문(BRN-UNMASK-001 DONE), 목록 대표자 마스킹(`BusinessesScreen.tsx:605` `representativeNameMasked`) 일치. #25 내부 키 화면 미표시는 승인 상세·등록 화면에서 어긋남(UI-02·03·05) |
| D-ADMIN-008·D-FE-001 | 같은 출처 정적 번들, 결제창도 React | `client.ts`가 `/api/`만 허용·`credentials:'same-origin'`·`redirect:'error'`·`no-store`(`api/client.ts:64·77-79`). 결제창 React(§7) |
| D-ADMIN-UI-005·007·010 ③ | site_/ctr_/csa_/act_/apr_ 화면 미표시(사람용 값) | 가맹점 조회·상세는 적용, 등록 화면은 SITE-CONTRACT-CODE-001(BACKLOG) 대기, 승인함은 미적용(UI-02·03·05) |
| D-ADMIN-UI-009·011 | 가맹점 상세 화면 전환, 필터 묶음, 2열 상세 | 사업자·가맹점만. 다른 목록·상세는 이전 모양(UI-14·15) |

## 2. FRONTEND-ASSESSMENT 이후 달라진 점 (확인·반박)

| 진단 항목(§) | 당시 | 지금 [사실] | 판정 |
|---|---|---|---|
| §5 결과 불명 잠금 3벌 복사, UNKNOWN_CODES 3개 | 3곳 | `lib/useUnknownLockedSubmission.ts` 하나. `UNKNOWN_CODES` 정의 1곳(:15), `RegistrationStep`·`BusinessProfileFormScreen`은 `sendUnknownLocked`, 개통은 `useUnknownLockedSubmission`(`MerchantActivationArea.tsx:217`) | **해소**(LOCK-UNIFY-001 DONE) |
| §5 ③ useOneShot 상태가 컴포넌트 useState | 탭 이동 시 소실 | `useOneShot`가 ScreenMemory 키를 받는다(`MerchantActivationArea.tsx:216-217`), App의 `revisionMemory`에 둔다 | **해소** |
| §5 ③ 재전송 중 503·429면 잠금 해제 | 규칙 차이 | `useUnknownLockedSubmission.ts:71` `UNKNOWN_CODES.includes(code) \|\| (retrying && !conflict)` — 재전송 중 409 외에는 잠금 유지 | **해소**(ONESHOT-LOCK-001 DONE) |
| §4.1 두 체계 함께 쓰는 파일 14 | 14 | 정의를 "시안 class + shadcn import"로 좁히면 16, "시안 class + 금지 utility 또는 shadcn"이면 22 | **악화**(UI-06) |
| §4.2 `workspace` 분기 17·전달 19 | 17·19 | 조건 22(BusinessProfileForm 14·RegistrationStep 5+`rows` 2·ActionButton 1), 전달 29 | **악화**(UI-07) |
| §4.4 workspace.css 394줄, 다크 64선택자, `!important` 3 | — | 522줄, `data-theme` 4줄(토큰 재정의·예외 주석), `!important` 3 | 다크 중복 **해소**(B1), 줄 수 증가(FilterPanel·필드 행 규칙) |
| §4.4 낡은 주석 "시안에는 다크 모드가 없어" | 있음 | 0건 | **해소** |
| §2 큰 파일 | MerchantsScreen 849 등 | MerchantsScreen 1,140(+291), BusinessesScreen 799(+226), BusinessProfileForm 841(+156), MerchantActivationArea 629(+75), adminApi 1,125(+31) | **악화**(UI-12) |
| §2 규모 main 13,595 / test 12,083 | 0.89배 | main 14,960 / test 15,850(88파일) = 1.06배 | 시험 비중 증가 |
| §6 `toHaveClass` 210/16, `querySelector` 304/21, `readFileSync` 34/11 | — | 224/21, 341/36, 17/4 | readFileSync는 B1 전환으로 **감소**, 구조 단언은 **증가**(UI-09) |
| §3 App 세션 리셋 3곳 반복, flush 하드코딩 10 | — | 그대로(`App.tsx:117·138·153`, `:274`) | **미해소**(UI-10) |
| §3 같은 justification 경로 메서드 3개 | — | 그대로(`api/adminApi.ts:1089·1090·1115`) | **미해소**(UI-11) |
| §1 "내부 키 비노출 DOM 검사(21파일)" | 보안 장치로 평가 | 28파일. 다만 가맹점 밖 화면은 `mer_`·`biz_`·`adm_`만 검사하고 승인 상세는 `csa_`·`site_` 노출을 **단언**한다 | **부분 반박**(UI-02·04) |

## 3. 문제 목록

열: ID | P | 기존 규칙(문서§) | 문제 | 파일 경로(file:line) | 근거 | 영향 범위(수치) | 난이도 | 예상 영향 | 검증 방법 | 개선 방향

### 3.1 P1 — 정책 충돌·상태

| ID | P | 기존 규칙 | 문제 | 파일:줄 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| UI-01 | P1 | CLAUDE.md "timeout·응답 유실만으로 금융효과가 없다고 단정하지 않는다", ADMIN-UI-001 §3.1 "결과 확인 중" 표시, DS §5 "금융 안전 장치" | 승인·거절·철회(`act`)가 네트워크 오류·5xx면 `codeOf`가 `SERVER_ERROR`로 바꿔 "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."만 보인다. 목록·열린 상세를 다시 읽지 않는다. 승인은 OWNER 명령(보정 취소·재매입·가맹점 명령)을 실행하므로 실제로는 실행됐을 수 있다 | `screens/ApprovalsScreen.tsx:143-154`(act), `:124-128`(fail), `errors/messages.ts:59`(기본 SERVER_ERROR), `:50`(문구) | 승인 시험 파일 8개 중 approve 응답 유실(TypeError·5xx) 시험 0건(grep `TypeError\|SERVER_ERROR` × approve) | 승인 종류 전부(ApprovalsScreen 1곳 → 상세 3종). 서버는 `ALREADY_DECIDED`·새 TOTP로 중복 결정을 막는다 [사실: `messages.ts:19` 코드 존재, 서버 동작은 이번에 대조 안 함] → 이중 실행 위험은 낮음 [추정] | 하 | 운영자가 "실패"로 오해하고 기관·가맹점에 잘못 안내, 같은 건을 다시 승인 시도 | Vitest: `api.approve` reject(TypeError) → 문구가 "결과를 알 수 없음·목록 다시 확인"이고 목록 재조회 호출 1회 | 결정 계열 실패는 "처리 결과 확인 필요" 문구 + 목록·상세 재조회. 문구는 가역 UI 세부지만 "결과 불명" 표시 기준은 Coordinator 확인 |
| UI-02 | P1 | PII-MASKING §3 #25·DS §3.2(:74)·D-ADMIN-UI-010 ③(내부 키 화면 미표시) **대** MERCHANT-OPS-002 §9 :734("첫 MID는 CSA·Revision·PRIMARY Site ID") | 승인함 가맹점 승인 상세가 pinned 내부 키를 그대로 그린다: 계약 `ctr_`#Revision, `CSA: csa_…`, `PRIMARY Site: site_…`, 범위 Site 목록, 개통 `act_…`, Site 심사 `site_…`. 시험이 이 노출을 기대값으로 고정한다 | `components/MerchantContractApprovalDetail.tsx:346·347·359·360·361·372·373·383`, 시험 `screens/ApprovalsScreen.merchant.test.tsx:125-126` | 같은 사람용 라벨 함수가 이미 있다(`lib/merchantLabels.ts:21` siteLabel, `:39` contractLabel, `:45` agreementLabel)지만 승인 상세는 `MerchantDetail`을 읽지 않아 못 쓴다 | 승인 상세 5종 중 4종(계약·첫 MID·개통·Site 심사), 노출 표현 8줄, 시험 단언 2줄 | 중(라벨 원천 필요 — pinned에 코드가 없음) | 승인자가 대상을 키로만 확인 → 오승인 위험·결정 위반 | 문서 대조 + Vitest `domOccurrences(document.body, prefix)`를 승인 상세에 13접두어로 | **질문 필요**: 승인 상세의 대상 확인 표시를 내부 키로 둘지(MERCHANT-OPS-002) 사람용 값으로 바꿀지(PII #25). 답 전에는 시험 기대값을 바꾸지 않는다 |
| UI-03 | P1 | D-ADMIN-UI-005(2026-11-19: 가맹점 등록 화면의 Site ID·계약 ID·서비스 계약 ID 미표시, 직접 입력 칸은 목록 선택으로) | 가맹점 등록 마법사가 저장 결과에 `Site ID site_…`·`계약 ID ctr_…`·`서비스 계약 ID csa_…`를 보이고, 계약 범위 체크 목록·검토 단계에 `site_`를 그리며, "Site ID 직접 입력"(placeholder `site_…, site_…`)·"계약 ID"(placeholder `ctr_…`) 칸으로 운영자가 내부 키를 손으로 넣는다 | `screens/MerchantRegistrationScreen.tsx:705·756·795·804-813·858·864-866·909` | 구현 작업 SITE-CONTRACT-CODE-001이 BACKLOG(`harness/tasks.json`) | 화면 1개, 표시 6곳·입력 2칸 | 중~상(사람용 코드 발급이 선행 — 별도 명세) | 운영자 입력 오류(키 복사), 결정 미이행 상태가 Demo에 노출 | `grep -n "site_\|ctr_\|csa_" MerchantRegistrationScreen.tsx` 0(주석 제외), 등록 화면 시험에 13접두어 domScan | SITE-CONTRACT-CODE-001 착수 순서를 Coordinator가 정한다. 그 전까지 "알려진 미이행"으로 DS·IA 문서에 명시 |

### 3.2 P2 — 경계·아키텍처

| ID | P | 기존 규칙 | 문제 | 파일:줄 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| UI-04 | P2 | DS §3.2, PII #25, D-ADMIN-UI-010 ③ | 화면에 그리면 안 되는 접두어 목록이 네 가지다: DS 5개(`mer_ biz_ site_ ctr_ csa_`), PII #25 7개+"등", `lib/merchantLabels.ts:6` 11개, 시험 `PREFIXES` 13개(두 파일에 복사). 가맹점 밖 화면 시험은 `mer_`·`biz_`·`adm_`만 검사 | `docs/repository/ADMIN-DS-001-DESIGN-SYSTEM.md:74`, `docs/repository/PII-MASKING-POLICY.md:83`, `lib/merchantLabels.ts:6`, `screens/MerchantsScreen.iaB3.test.tsx:37`, `MerchantsScreen.iaB4.test.tsx:90` | `domOccurrences` 인자 집계: `'biz_'` 37·`'mer_'` 35·`'adm_'` 2, 13접두어 반복은 가맹점 시험 2파일뿐 | domScan 사용 28파일 중 13접두어 검사 2파일 | 하 | 새 화면에서 `site_`·`csa_` 누출을 시험이 못 잡음(UI-02가 실제 사례) | 공용 상수 하나를 `test/domScan.ts`에 두고 전 화면 시험이 import(grep으로 복사본 0) | 정본 목록을 PII #25 한 곳에 두고 DS §3.2는 참조로. 화면별 예외(MID·paymentId·fileId처럼 외부 식별자)는 명시 목록으로 |
| UI-05 | P2 | D-ADMIN-UI-010 ③("apr_ → 승인함 링크"), PII #25(`apr_` 화면 미표시) | 승인 ID `apr_…`를 승인함 상세 제목·대사 발견 표·보정 취소 성공 문구·재매입 성공 문구가 그린다. 가맹점 화면은 같은 값을 숨기고 "승인함에서 보기"로 바꿨다 | `screens/ApprovalsScreen.tsx:219`, `screens/ReconciliationScreen.tsx:78·80`, `screens/PaymentOpsScreen.tsx:308`, `components/ReacquisitionRequest.tsx:150`, 숨김 쪽 `screens/MerchantsScreen.tsx:823·847` | 시험이 노출을 단언: `PaymentOpsScreen.test.tsx:37`, `PaymentOpsScreen.workspace.test.tsx:123·188`, `ReconciliationScreen.test.tsx:21`, `ApprovalsScreen.workspace.test.tsx:95`, `AcquisitionsScreen.reacquisition.test.tsx:96` | 노출 4화면·5곳, 시험 단언 6줄 | 하(표시) / 결정 필요 | 같은 값이 화면마다 다른 규칙 — 운영자가 승인 ID로 소통하는지 여부가 정해지지 않음 | grep `approvalId}` 렌더 위치 | **질문 필요**: `apr_`가 운영자 소통용 외부 식별자인지(그렇다면 PII #25에서 예외로 명시), 내부 키인지 |
| UI-06 | P2 | DS §3.1(정본 시안 class 하나), §8 B2, D-ADMIN-UI-008 ① | shadcn 복사본과 시안 class가 공존한다. shadcn import 27파일(button 10·input 16·native-select 8·table 5·badge 5·label 3·textarea 3·alert 3·card 1), JSX 사용 `<Button>` 47·`<Input>` 50·`<Table>` 15·`<TableCell>` 77. 시안 class 토큰 402개는 32파일 | `components/ui/*.tsx` 9파일, 사용처 예 `screens/MerchantsScreen.tsx`, `components/MerchantActivationArea.tsx` | DS §2(`e9e4d1c`) 26파일 → 27(alert에 `DemoQuickLoginPanel.tsx` 추가) | 시안 class + shadcn import 16파일, shadcn만 11파일, 시안 class만·금지 utility 0인 파일 10(App·AppLayout·FilterPanel·MapNote·MenuCard·MenuSearch·ThemeToggle·WorkspaceHeader·DemoQuickLoginStatus·HomeScreen) | 상(B2 전체) | 화면마다 버튼·입력 모양·초점 링이 다르다, 토큰 두 곳 동기화 시험(`designTokens.test.ts:142`) 유지 비용 | §7.3 B2 시험(shadcn import 0) 추가 후 통과 | DS §8 B2 순서 그대로. 금융 잠금 버튼 동작 시험(§7.1)은 바꾸지 않는다 |
| UI-07 | P2 | DS §3.1("모양 분기 prop 두지 않는다"), §6.3(boolean으로 다른 모양 금지) | `workspace` boolean 분기가 늘었다: 조건 22(BusinessProfileFormScreen 14, RegistrationStep 5 + `rows` 2, ActionButton 1), 전달 29(ActionButton 25·RegistrationStep 4 + App 1·지역 `Wrap` 1). BusinessProfileFormScreen은 `workspace` boolean과 `layout` variant(`'sections'`/`'detail'`) 두 축을 함께 가져, 같은 폼이 제목 줄을 MapNote(:755·775)와 PageHeader(:765·795)로, 버튼을 `.btn`과 shadcn(`workspace={false}` :656-668)으로 나눠 그린다 | `components/ActionButton.tsx:14-19`, `components/RegistrationStep.tsx:136·180-225`, `screens/BusinessProfileFormScreen.tsx:340-738·807`, `App.tsx:310`, `screens/MerchantRegistrationScreen.tsx:652·703·754·856` | FRONTEND-ASSESSMENT §4.2 17·19 | 컴포넌트 3, 화면 4(사업자 등록·사업자 상세·가맹점 등록·계약 Revision 폼) | 중 | 사업자 등록과 사업자 상세 편집 폼이 같은 컴포넌트인데 버튼 모양이 다르다. 분기마다 시험이 두 벌 | §7.3 B2 "모양 분기 prop 0" 시험 | B2에서 ActionButton 흡수·`workspace` 제거. BusinessProfileForm은 `layout` variant 하나로(§6.3) |
| UI-08 | P2 | DS §7.2·§8 B4, FRONTEND-ASSESSMENT §6 개선 ⑤ | 구조 단언이 진단 뒤 늘었다: `toHaveClass` 224줄/21파일, `querySelector` 341줄/36파일, `:scope` 선택자 83줄/14파일, `shape(` DOM 경로 32줄/8파일. 구조 전용 파일(`*.workspace.test.tsx` 10 + layout 1 + WorkspaceShell) 12파일 2,556줄 | 상위: `screens/BusinessProfileFormScreen.workspace.test.tsx`(toHaveClass 49·querySelector 34), `MerchantRegistrationScreen.workspace.test.tsx`(32·35), `PaymentOpsScreen.workspace.test.tsx`(11·36), `layout/WorkspaceShell.test.tsx`(12·25) | 비교: 역할 질의 `ByRole` 2,059줄/71파일, `toHaveAccessibleName` 69줄/20파일 | 시험 88파일 중 구조 단언 파일 36 | 상(매핑표 필요 — §7.2) | B2·B3 모양 정리 때 시험 수정량이 main보다 커진다(DS §8 재정비 지표) | 배치마다 `git show --numstat` 시험 순증 ≤ main 순증 | B2~B3 배치가 건드리는 단언부터 §7.2 매핑표로 전환. §7.1 보호 시험은 대상 아님 |
| UI-09 | P2 | ADMIN-DS-001 §7.3 B5·§3.1, ARCHITECTURE-CONVENTIONS(가역 세부 저장소 관례) | (기록용 통합 항목 — 아래 수치는 UI-06·07의 원인) Tailwind 반응형 접두사 35곳이 남아 §7.4 캡처를 12폭으로 유지해야 한다 | `grep -oE '\b(sm\|md\|lg\|xl):'` main TSX 35 | DS §2 "약 36곳" | 캡처 480장/배치(20상태 × 12폭 × 2테마) | 중 | 모양 배치마다 캡처 비용 2배 | 반응형 utility 0 → 6폭으로 축소 | B5에서 반응형 배치를 시안 class 경계(1100·720)로 옮긴다 |
| UI-10 | P2 | FRONTEND-ASSESSMENT §7 "유지 + 정리"(resetSession 하나, flush 메타데이터) | 로그인·세션 종료·로그아웃 세 곳이 화면 메모리 5종을 각각 새로 만든다(15호출). `hasUnresolvedRequest` 인자도 두 곳에 복사. flush 판정은 page 10개 OR 하드코딩 | `App.tsx:117-121·138-143·153-158`(리셋), `:137·152`(hasUnresolvedRequest), `:274`(flush) | 진단 당시와 같다 | 메모리 5종 × 3 | 하 | 6번째 메모리를 더할 때 한 곳을 빠뜨리면 이전 사용자의 입력(원문 사업자등록번호 포함 — 등록 화면 메모리)이 다음 로그인에 남는다 [추정] | Vitest: 로그아웃·세션 종료·재로그인 뒤 각 메모리가 비었는지(기존 App 시험에 메모리 목록 단언 추가) | `resetSession()` 하나 + 메모리 목록 배열, flush는 `navigation.ts` 메뉴 메타데이터로 |
| UI-11 | P2 | FRONTEND-ASSESSMENT §7 개선 ⑥, ARCHITECTURE-CONVENTIONS §14(주요 타입당 파일 하나 — 백엔드 기준) | `api/adminApi.ts` 1,125줄에 타입 80개·메서드 62개. 같은 `/justification` 경로 메서드 3개(`justification`·`reacquisitionEvidence`·`merchantApprovalEvidence`) | `api/adminApi.ts:939-1027`(interface), `:1089·1090·1115` | 진단 당시 1,094줄 | 화면·컴포넌트 import 다수(파일 하나에 변경 충돌 집중) | 중 | 병렬 작업 충돌, 리뷰 범위 큼 | 파일별 줄 수, 같은 경로 메서드 1개 | 도메인별 타입 파일 분리(merchant·business·approval·payment·acquisition), 메서드는 한 객체 유지 |

### 3.3 P3 — 관리자 기능·UI 불일치

| ID | P | 기존 규칙 | 문제 | 파일:줄 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| UI-12 | P3 | DS §6.1·§6.2, FRONTEND-ASSESSMENT §7 "재구현 후보" | 큰 파일이 더 커졌다(main 줄 수 상위): MerchantsScreen 1,140 / adminApi 1,125 / MerchantRegistrationScreen 979 / BusinessProfileFormScreen 841 / BusinessesScreen 799 / MerchantActivationArea 629 / AcquisitionsScreen 615 / HomeScreen 524 / workspace.css 522 / TransactionsScreen 432 / MerchantContractApprovalDetail 431. 시험 최대 MerchantRegistrationScreen.test 1,639 | 각 파일 | `useState`: AcquisitionsScreen 21, MerchantActivationArea 18, MerchantsScreen 16, TransactionsScreen 16 | main 상위 10파일 8,045줄(main 전체 14,960의 54%) | 상 | 한 화면 변경이 1,000줄 파일 리뷰가 된다 | 줄 수 추이(배치마다) | 목록 / 상세 패널 / 명령 영역 분할. 동작 시험을 안전망으로 두는 조건(진단 §7 그대로) |
| UI-13 | P3 | DS §5 "제목 줄: MapNote, WorkspaceHeader / PageHeader는 사용처 확인 뒤 하나로", ADMIN-UI-001 §3.2 | 화면 제목 줄이 세 방식이다: `MapNote` 컴포넌트(가맹점 목록·상세·가맹점 등록·사업자 등록), `PageHeader`(사업자 목록·감사 기록·사업자 등록 비시안 분기), 직접 쓴 `<div className="mapnote">`(계정·매입·승인함·운영 홈·보정 취소·대사·거래 7화면). 같은 메뉴의 형제 탭 사업자 목록(PageHeader, flush 아님)과 가맹점 목록(MapNote, flush)이 다르다 | `screens/BusinessesScreen.tsx:525`, `screens/MerchantsScreen.tsx:1038`, `screens/AuditScreen.tsx:114`, `screens/AccountsScreen.tsx:283`, `AcquisitionsScreen.tsx:486`, `ApprovalsScreen.tsx:163`, `PaymentOpsScreen.tsx:267`, `ReconciliationScreen.tsx:38`, `TransactionsScreen.tsx:306`, `App.tsx:274` | 패턴 행렬(§8 스크립트) | 14화면 중 MapNote 4 / PageHeader 3 / 직접 7 | 하 | 제목 크기·초점 이동(h2 tabIndex) 규칙이 화면마다 다름 — `PageHeader`는 `focusable`일 때만 tabIndex | 화면별 제목 컴포넌트 1종 grep | MapNote로 통일(§6.4 절차), PageHeader 사용처 3곳 정리 |
| UI-14 | P3 | D-ADMIN-UI-011 ① 목록 필터 묶음, DS §5 "다른 화면의 `.filters` 줄은 그대로(옮길 때 §6.4)", MERCHANT-IA-001 §7.7 건수 문구 | 목록 조회 조건이 세 방식이다: `FilterPanel`(사업자·가맹점), `.filters many` 줄(거래·매입), `Field` 격자 + shadcn Table(감사). 건수·다음 쪽 문구도 넷이다: '총 n건'·'다음 쪽'(`lib/listCount.ts`, 2화면), '이 쪽 n건'·'다음'(거래), '더 보기'(매입), '표시 n건'(승인함·계정·대사) | `screens/BusinessesScreen.tsx:534`, `MerchantsScreen.tsx:1044`, `TransactionsScreen.tsx:310`, `AcquisitionsScreen.tsx:495`, `AuditScreen.tsx:114-142`, `ApprovalsScreen.tsx:210` | `listCountText` import 2파일 | 목록 화면 7 | 중 | 운영자가 목록마다 조작법·건수 의미(전체/이 쪽)를 다시 익혀야 함 | 목록 화면 FilterPanel 사용 수, 건수 문구 grep | 거래·매입·감사 목록을 FilterPanel로(§6.4), 건수는 listCount 하나 |
| UI-15 | P3 | D-ADMIN-UI-009 ①(가맹점 상세 = 화면 전환), DS §5 "상세 패널: 공용 DetailPanels(승격)", §6.2 | 상세 표시가 두 방식이다: 목록↔상세 화면 전환(사업자·가맹점)과 목록 아래 `.detailarea`(승인함·거래·매입). 지역 `Panel`·`DetailPanels`가 세 화면에 따로 있다 | `screens/TransactionsScreen.tsx:49·67·408`, `AcquisitionsScreen.tsx:110·184·581`, `MerchantsScreen.tsx:129·152`, `ApprovalsScreen.tsx:214` | §5 정리 대상 그대로 | 지역 Panel 3·DetailPanels 3 | 중 | 상세를 연 뒤 위치·복귀 방식이 화면마다 다름 | 공용 DetailPanels 사용처 수 | 어느 화면이 화면 전환인지 기준을 Coordinator가 정하고(가역 UI 세부) DetailPanels 승격 |
| UI-16 | P3 | D-ADMIN-UI-005 ④(모든 입력 화면 이름 왼쪽), DS §5 입력 줄 = FormRow, **ADMIN-UI-001 §3.6 "label 위"(충돌하는 낡은 문구)** | 입력 줄이 세 방식이다: `Field`(위 label) 25곳/9파일, `FormRow`(왼쪽 140px) 29곳, BusinessProfileForm 지역 `Row` 12곳. AccountsScreen은 Field와 FormRow를 함께 쓴다. Field 사용처 대부분이 DS 예외(조회 조건·로그인·일회용 코드)가 아닌 승인 요청 폼 | `components/DataBits.tsx:74`, `components/FormRow.tsx:18`, `screens/BusinessProfileFormScreen.tsx:815`, `screens/AccountsScreen.tsx:69·306`, Field 사용 파일: AccountsScreen·ApprovalsScreen·AuditScreen·LoginScreen·MerchantActivationArea·MerchantCodeChangeRequest·ContractApprovalRequest·ReacquisitionRequest·FirstMidRequest | `docs/repository/ADMIN-UI-001-SHADCN.md:49` | 입력 줄 66곳 | 중(B3) | 같은 폼 안에서 이름 위치가 다름 | Field 사용처 = DS 예외 목록만 | B3에서 FormRow로. ADMIN-UI-001 §3.6에 "D-ADMIN-UI-005 ④로 대체" 표시 |
| UI-17 | P3 | DS §5 "상세 한 줄: DetailRow" | 상세 한 줄이 네 방식이다: `DetailRow` 42곳, 직접 `.detailgrid` 16곳(가맹점 등록·보정 취소), 직접 `<dl>` 17곳, 승인 상세 3종의 `<ul><li>'이름: '` 나열 | `components/DetailCard.tsx:17`, `screens/MerchantRegistrationScreen.tsx:236`(지역 Row), `screens/PaymentOpsScreen.tsx`, `components/MerchantContractApprovalDetail.tsx:344-404` | §5 정리 대상 그대로 | 4종 | 중(B3) | 승인자가 보는 상세가 다른 상세와 배치가 다름(이름·값 열 없음) | DetailRow 외 상세 표현 0 | B3 DetailRow 통합 |
| UI-18 | P3 | DS §6.2(두 번째 화면부터 승격), ADMIN-UI-001 §3.1 | 2인 승인 요청 폼 6벌(개통 영역 `EvidenceRequest`, ContractApprovalRequest, FirstMidRequest, MerchantCodeChangeRequest, ReacquisitionRequest, 보정 취소)이 같은 틀을 복사한다: 지역 오류 표 4벌(`TARGET_NOT_ELIGIBLE_ADVISORY`·`DUPLICATE_UNRESOLVED`·`INVALID_INPUT`), 근거 참조 정규식 2벌, 형식 안내 문구 11곳. 모두 shadcn Button·Input·Field만 쓴다 | `components/MerchantActivationArea.tsx:243·255`, `ContractApprovalRequest.tsx:11·14`, `FirstMidRequest.tsx:36`, `MerchantCodeChangeRequest.tsx:32`, `ReacquisitionRequest.tsx:38·144`, `screens/PaymentOpsScreen.tsx:111·213` | grep `영문·숫자와 . _ : # / - 만` 11 | 폼 6 | 중 | 근거 참조 규칙이 바뀌면 6곳 수정, 문구가 조금씩 다름 | 공용 컴포넌트 1 + 정규식 정의 1 | `ApprovalRequestForm`(가칭) 승격 — 결과 불명·중복 대기 표시를 한 곳에서 |
| UI-19 | P3 | DS §5 "금융 안전 장치는 모양 정리 때 바꾸지 않는다", MERCHANT-OPS-002 §5.3 | 결과 불명 잠금 표시가 두 모양이다. 등록 단계·사업자 폼: `role=group aria-label="결과 확인 필요"` + "같은 내용 다시 보내기"·"그래도 새로 입력". 개통 1인 명령: `WarningNote` + 버튼 글자만 "같은 거절 다시 보내기"·"같은 개통 등록 다시 보내기", 새로 입력 없음 | `components/RegistrationStep.tsx:212-225`, `screens/BusinessProfileFormScreen.tsx:711-718`, `components/MerchantActivationArea.tsx:238·354·555` | 동작 규칙은 공용 hook 하나(§2) — 모양·문구만 다름 | 잠금 표시 2모양 | 하(문구) | 운영자가 잠금 상태를 다르게 인지. 1인 명령에 "새로 입력"이 없는 것이 의도인지 문서에 없음 [추정] | 잠금 표시 컴포넌트 1종 | 공용 `UnknownOutcomeNotice`(가칭). 1인 명령의 포기 경로 유무는 Coordinator 확인 |
| UI-20 | P3 | (용어집 없음) MERCHANT-IA-001 §7 B6 "용어 통일", ADMIN-UI-001 §3.1 code→한국어 | 업무 용어가 영어·한국어로 섞이고 같은 개념이 여러 이름이다: 화면 문자열에 'Site' 76/15파일, 'MID' 60/17, 'Revision' 38/13, 'Route' 13/6. 서비스 계약은 'CSA' 7·'서비스 계약' 9·'서비스 약정' 2. 대표 Site는 'PRIMARY Site'(FirstMidRequest:125, 승인 상세:361)·'대표 Site'(개통 점검표 :81·83)·'대표(PRIMARY) Site'(:373, messages:71) 세 표기. 인증 수단은 '인증 코드' 12·'OTP' 6·'TOTP' 3 | `components/FirstMidRequest.tsx:125·136`, `MerchantActivationArea.tsx:81·83`, `MerchantContractApprovalDetail.tsx:360·361·373`, `errors/messages.ts:71`, `lib/historyTarget.ts`, `lib/merchantChangeTypes.ts` | §8 용어 스크립트. '사이트' 0·'결제 계정' 0·'상점' 0 — 그쪽 혼용은 없다 | 화면 문자열 22파일 | 하(문구) / 결정 필요(정식 용어) | 같은 대상을 다른 이름으로 보여 운영자 교육·문의 응대 비용 | 용어집 기준 grep 0 | 관리자 화면 용어집(정식 이름·허용 별칭·금지어)을 문서로 두고 `labels.ts`와 대조 시험. 정식 용어 선택은 사용자 확인 대상 |
| UI-21 | P3 | ADMIN-UI-001 §3.1 "code → 한국어는 `src/lib/labels.ts` 한 곳" | `labels.ts` 밖에 code→한국어 표가 약 24개다: TransactionsScreen 6(`INITIATOR`·`TRANSACTION_TYPE`·`PLAN`·`CARD_TYPE`·`SCOPE`·`CANCEL_STATUS`), businessProfile 6, AcquisitionsScreen 3, ReacquisitionRequest 2, MerchantContractApprovalDetail 2, RegistrationStep 1, merchantChangeTypes 1, historyTarget 1, messages 1 등 | `screens/TransactionsScreen.tsx:22-27`, `lib/businessProfile.ts:12-26`, `screens/AcquisitionsScreen.tsx:72·78·83`, `components/MerchantContractApprovalDetail.tsx:39·92` | `labels.test.ts`가 서버 enum 집합을 대조하는 것은 `labels.ts`뿐 | 표 약 24 | 중 | 서버 enum이 늘면 화면 표에서 빠져도 시험이 못 잡음(`CANCEL_STATUS.UNKNOWN` '결과 확인 중'처럼 같은 뜻을 두 곳에서 정의) | labels 밖 code 표 0 또는 대조 시험 | 문서를 "표시 라벨은 lib/labels 아래 도메인별 파일, 모두 대조 시험"으로 고치거나 표를 옮긴다(가역 세부) |
| UI-22 | P3 | D-ADMIN-UI-004 ③·PII #25(내부 키), ADMIN-UI-001 §3.1(code 대신 한국어) | 감사 기록 조회 조건이 내부 형식을 그대로 입력받는다: 행위 칸 placeholder `예: LOGIN_SUCCEEDED`(서버 action code), 대상 칸 placeholder `adm_…`(actorId). 같은 화면의 가맹점 조건은 사람용 가맹점 코드로 바꿨다 | `screens/AuditScreen.tsx:119·124` | `AuditScreen.tsx:31` 주석 "내부 키는 화면에 그리지 않는다"와 다름 | 칸 2 | 하~중 | 운영자가 코드를 외워야 검색 가능 | 화면 문자열 grep `adm_` 0 | 행위는 `AUDIT_ACTION_LABELS` 선택 상자, 대상은 계정 표시명 선택(서버 API 범위 확인) |
| UI-23 | P3 | DS §5 "상태 태그: StatusBadge(`tag`+tone), shadcn Badge는 정리 대상" | 같은 상태 표시를 두 렌더러가 그린다: shadcn `Badge` 기반 `StatusBadge` 13곳/3파일, 시안 `.tag`(`tagClass`·`toneTagClass` 8곳/8파일 + 직접 `className="tag…"` 16곳). 색 규칙은 `statusTone.ts` 한 곳이라 의미는 같다 | `components/DataBits.tsx:11`, `lib/statusTone.ts:1·18·28-38` | `BadgeVariant` 타입을 `statusTone.ts`·AcquisitionsScreen이 import | 2렌더러 | 하(B2) | 같은 상태가 화면마다 다른 크기·모양 | shadcn Badge import 0 | B2: `BadgeVariant` → StatusBadge tone 타입, StatusBadge가 `.tag`를 그리게 |

### 3.4 P3 — 접근성·디자인 시스템 수치 (기록 항목)

| ID | P | 기존 규칙 | 문제 | 파일:줄 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| UI-24 | P3 | DS §3.1(색·글자 크기·굵기·줄 간격·테두리 색·그림자·radius utility 금지), §4.1 리터럴 규칙, §7.3 B5 | main TSX className의 금지 utility 453개(색 159/28파일, 글자 크기 207/30파일, 굵기 51, radius 28, 줄 간격·자간 5, 그림자 1, 기타 2). 상위: `text-xs` 137·`text-muted-foreground` 88·`text-sm` 62·`font-medium` 32. 임의값 `text-[11px]`·`text-[13px]`·`text-[17px]`·`rounded-[11px]`·`rounded-[13px]`. 토큰 밖 색: `bg-black/50`(OneTimeCode — `--ds-overlay-dim` 토큰이 있음), `text-white` 3. `font-mono` 108은 §3.1 목록에 없어 별도(규칙 공백) | 파일별 상위: MerchantsScreen 92·MerchantRegistrationScreen 67·MerchantActivationArea 57·MerchantContractApprovalDetail 51·BusinessesScreen 41·BusinessProfileFormScreen 38, `components/OneTimeCode.tsx:33`, `components/PageHeader.tsx`(`text-[17px]`) | Tailwind 팔레트 색(`text-red-600` 등) 0, TSX hex 0(주석 제외) | 금지 utility 있는 파일 35 | 상(B5) | 토큰을 바꿔도 shadcn 토큰(`--muted-foreground` 등)을 쓰는 글자는 따로 움직인다(V1에서 두 곳 동시 보정 필요했던 원인) | §7.3 B5 금지 utility 시험 | B5. `font-mono`(식별자 표시)를 허용할지 §3.1에 명시 |
| UI-25 | P3 | WCAG 2.1(DS §4.3), ADMIN-UI-001 §2(표 이름 유지) | 접근성 작은 항목: 표 30개 중 1개(감사 기록)가 접근 가능한 이름이 없다. `<caption>` 0(전부 `aria-label` 방식 — 일관됨). 비대화형 요소 onClick은 메뉴 검색 배경 1곳(`MenuSearch.tsx:121` — Esc·초점 가두기 시험 있음, 허용 가능 [추정]). raw `<button>` 67개 모두 `type` 있음 | `screens/AuditScreen.tsx:144` | §8 스크립트 | 표 1 | 하 | 화면 낭독기에서 감사 표 구분 어려움 | Vitest `getByRole('table', { name })` | `aria-label="감사 기록 목록"` 추가(문구는 가역) |

### 3.5 P4 — 문서·명명·편의

| ID | P | 기존 규칙 | 문제 | 파일:줄 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| UI-26 | P4 | DS §6.2(일반 이름 금지, 파일 하나에 주요 공용 컴포넌트 하나) | 지역 일반 이름: `Row` 3(BusinessProfileForm:815·MerchantRegistration:236·Approvals:30), `Panel` 3(Transactions:49·Acquisitions:110·Merchants:129), `Section`(BusinessProfileForm:830), `Wrap`(:807), 지역 `Tone`(Acquisitions:98). `DataBits.tsx` 한 파일에 공용 7개(StatusBadge·RoleBadges·KindLabel·KstTime·EmptyRow·Spinner·Field), `DetailCard.tsx`는 `InfoCard`를 export(파일명 불일치). `lib/merchantLabel.ts`(23줄)와 `lib/merchantLabels.ts`(124줄)는 한 글자 차이 이름에 다른 내용. hook 3개(`use*.ts`)가 `lib/`에 있음 | 각 file:line | §8 grep | 지역 이름 9, 다중 export 파일 1 | 하 | 검색·리뷰 혼동 | §6.2 이름 grep 0 | B3에서 역할 이름(`ActivationSiteRow` 식). merchantLabel → `transactionMerchantLabel` 식 개명은 가역 세부 |
| UI-27 | P4 | DS §4.3(V1에서 `.notice` 대비 해소), DS §2 수치 | 낡은 주석·문서 수치: TSX 주석 5곳이 "#64748b는 #f6f8fb 바탕에서 4.47:1로 AA 미달"이라 적고 `text-foreground`로 덮어쓴다 — V1에서 `--ds-text-muted`가 #637389(4.54:1)로 바뀌어 근거가 사라졌다. DS §2는 `e9e4d1c` 수치(workspace.css 394줄·shadcn 26파일)를 "현재 상태"로 둔다(지금 522줄·27파일). ADMIN-UI-001 §4는 `--brand: #2563eb`라 적지만 실제 `#116ef5`(`styles.css:19`) | `components/OwnerApprovalDetail.tsx:168`, `MerchantContractApprovalDetail.tsx:421`, `ReacquisitionApprovalDetail.tsx:116`, `screens/MerchantRegistrationScreen.tsx:942`, `docs/repository/ADMIN-DS-001-DESIGN-SYSTEM.md:30-43`, `docs/repository/ADMIN-UI-001-SHADCN.md:56` | DS §11.2 V1 표 | 주석 5·문서 3곳 | 하 | 다음 구현자가 낡은 근거로 같은 덮어쓰기를 반복 | 주석 grep `4.47` 0 | 주석 정리는 B2(덮어쓰기 제거 여부는 캡처로 판단). DS §2에 "기준 시점 수치" 표시, ADMIN-UI-001 §4에 "DS-001이 대체" 표시 |
| UI-28 | P4 | (결정 없음) ADMIN-HOME-KPI-001 명세만 해요체("없어요") | 화면 말투가 섞인다: 해요체 문장 15곳(HomeScreen 13·MenuSearch 1·merchantLabel 1), 합쇼체 '니다' 466곳. `MERCHANT_LABELS_UNAVAILABLE`('…못했어요')은 승인함·감사 등 합쇼체 화면에도 나온다 | `screens/HomeScreen.tsx:260-502`, `components/MenuSearch.tsx:140`, `lib/merchantLabel.ts:10` | §8 grep | 15곳 | 하 | 같은 화면 안 어조 불일치(승인함 표 아래 안내) | 말투 grep | 화면 문구 말투 기준을 용어집(UI-20)에 함께. 운영 홈 해요체 유지 여부는 사용자 결정이었으므로 범위만 정리 |
| UI-29 | P4 | DS §6.1(쓰지 않는 컴포넌트 정리), §7.1(noRuntimeStyles 보호) | 쓰지 않는 코드: `errors/ErrorScreen.tsx`(42줄, shadcn Button 사용)는 main에서 import 0 — 시험 2파일(보호 시험 `noRuntimeStyles.test.tsx:7·63` 포함)만 쓴다. `components/ui/card.tsx`의 `CardDescription`·`CardFooter`, `badgeVariants`·`buttonVariants` export 사용처 0. main에서 import되지 않는 export 약 50개(대부분 같은 파일 안 상수·도우미) | `errors/ErrorScreen.tsx:25`, `components/ui/card.tsx:17·25` | §8 export 사용처 스크립트 | 파일 1·export 약 50 | 하 | ErrorScreen을 지우면 §7.1 보호 시험 대상이 바뀐다 — 지우기 전에 Coordinator 확인 필요 | main import 0 export 목록 | ErrorScreen을 실제 오류 화면으로 쓸지/지울지 결정 후 §7.1 매핑표와 함께 |
| UI-30 | P4 | DS §7.2 매핑표 관례, HARNESS(기능별 시험) | 시험 파일 이름이 작업 배치 ID다: `*.iaB1~iaB6.test.tsx` 9파일, `*.workspace.test.tsx` 10파일, 그 밖 접미어 27종. 행동이 아니라 배치로 나뉘어 같은 화면 시험이 최대 11파일(MerchantsScreen.*)로 흩어진다. 13접두어 `PREFIXES`도 두 파일에 복사(UI-04) | `src/screens/MerchantsScreen.*.test.tsx`(11파일), `BusinessProfileFormScreen.*`(4), `ApprovalsScreen.*`(10) | §8 접미어 집계 | 시험 88파일 중 접미어 47 | 하 | 회귀 시 어느 시험이 어떤 행동을 지키는지 찾기 어려움 | — | B4 전환 때 행동 기준 파일로 합치고 매핑표에 남긴다(삭제·완화 없이 이동만) |

> UI-09(반응형 utility 35곳 → 캡처 12폭 유지 비용)는 UI-06·24의 비용을 따로 적은 기록 항목이다. P2 8건에 넣었다. 합치면 P2는 7건이 된다.

## 4. 상태 관리·API 클라이언트·에러 처리 패턴 [사실]

| 항목 | 현재 | 평가 |
|---|---|---|
| 화면 전환 | 라우터 없이 `useState<View>`(App.tsx), 메뉴 표시는 `navigation.ts` | 유지. flush만 하드코딩(UI-10) |
| 화면 메모리 | App 수준 ScreenMemory 5종(`registration`·`business`·`businesses`·`revision`·`merchants`). `useRemembered` 사용 6파일, `memory.read/write` 4파일 | 결과 불명 잠금·입력 보존은 메모리로 일관. 리셋은 3벌(UI-10) |
| 지역 상태 | `useState`: Acquisitions 21·MerchantActivationArea 18·Merchants 16·Transactions 16 | 큰 화면 분할 대상(UI-12) |
| 늦은 응답 무시 | Home·Audit·Businesses·Merchants·MerchantRegistration에 alive/stale 가드 | Approvals·Accounts·Reconciliation은 가드 없음. 화면이 조건부 렌더로 언마운트되어 실제 문제는 찾지 못함 |
| API 진입점 | `api/client.ts` 하나(`/api/`만, XSRF, same-origin, `redirect:'error'`, `no-store`). `fetch(` 다른 곳 0 | 유지 |
| 오류 → 문구 | `ConsoleErrorCode` → `ERROR_MESSAGES`(`errors/messages.ts`), 알 수 없는 예외는 `SERVER_ERROR`. `SESSION_ENDING` 분기 22파일 44곳. 지역 문구 표 `Partial<Record<ConsoleErrorCode,…>>` 8개 | 패턴은 일관. 결정 명령의 응답 유실을 일반 오류로 보이는 점(UI-01), 승인 요청 표 복사(UI-18) |
| 결과 불명 잠금 | 공용 hook 하나, `UNKNOWN_CODES` 1개 | 해소(§2). 표시 모양 2종(UI-19) |

## 5. 시험 구성 [사실]

| 지표 | 값 |
|---|---|
| 시험 파일 / 줄 / `it·test` | 88 / 15,938 / 687 |
| 역할 질의 `ByRole` | 2,059줄 / 71파일 |
| `ByLabelText` / `ByText` | 467줄 / 54파일, 404줄 / 53파일 |
| userEvent | 1,027줄 / 70파일 |
| `toHaveAccessibleName`·`aria-selected/expanded/pressed/busy` 단언 | 69줄 / 20파일, 69줄 / 19파일 |
| `toHaveClass` / `querySelector` / `:scope` / `shape(` | 224/21, 341/36, 83/14, 32/8 |
| `readFileSync`(CSS·소스 직접 읽기) | 17줄 / 4파일(B1에서 34 → 17) |
| 내부 키 DOM 검사 `domOccurrences` | 97줄 / 28파일 |
| 보호 시험(DS §7.1) | 전부 있음(§1 표) |

- 판단: 동작·역할 단언이 다수이고 보호 시험은 완전하다. 비용은 구조 전용 12파일(2,556줄)과 화면 시험 안 class·DOM 경로 단언에서 나온다(UI-08). 금융 안전 시험(결과 불명 잠금·submissionId)은 보존 대상이며 이번 지적 대상이 아니다.
- 시험이 정책과 다른 화면을 고정하는 곳: UI-02(승인 상세 `csa_`·`site_`), UI-05(`apr_` 6줄). 기대값은 정책 답 전에 바꾸지 않는다(CLAUDE.md "정책·시험 기대값·게이트 기준을 바꿔서 검사 통과시키지 않는다").

## 6. 파일·컴포넌트 명명 [사실]

- 화면 `screens/<Name>Screen.tsx` 14개, 공용 `components/<PascalCase>.tsx`, 원자 `components/ui/<kebab>.tsx`(shadcn 관례 9개), 도우미 `lib/<camelCase>.ts`. 경로 alias 없음(ADMIN-UI-001 §5 그대로).
- 어긋남: UI-26(일반 이름·다중 export·이름 혼동·hook 위치), UI-30(시험 파일 배치 이름).
- 백엔드 ARCHITECTURE-CONVENTIONS §14(Repository/Store/Reader 명명)는 프런트에 해당 규칙이 없다. 프런트 명명 규칙은 DS §6.1·§6.2가 전부다 — `lib/` 안 도메인별 묶음·hook 위치·시험 파일 이름 규칙은 문서에 없다 [사실: 두 문서 대조].

## 7. 결제창 프런트와의 경계

- `apps/payment-api/frontend/src`: 전체 819줄(main 550), 런타임 의존성 React·react-dom만, Tailwind·shadcn 없음, `noRuntimeStyles` 시험 3개. 관리자 프런트 코드를 import하지 않는다(상대 경로 `../../`는 자기 `index.html?raw`뿐). 진단 당시와 같다 [사실].
- 같은 이름 `ErrorScreen.tsx`가 두 앱에 있다. 결제창 것은 쓰이고 관리자 것은 쓰이지 않는다(UI-29). 공유하지 않는 것이 맞다 — 결제창은 별도 CSP(D-CHECKOUT-CSP-001)다 [추정].
- 관리자 DS(ADMIN-DS-001 §1)는 결제창을 범위 밖으로 둔다. 이 진단도 결제창 개선 항목을 만들지 않는다.

## 8. 집계 방법 (재현)

- 모두 저장소 밖 스크립트·grep으로 셌다. 스크립트는 커밋하지 않았다(세션 임시 디렉터리). 같은 결과를 내려면 아래 규칙으로 다시 센다.
- **대상 파일**: `src` 아래 `.ts`·`.tsx`, `uiref/` 제외. main = `.test.` 아니고 `test/` 아님. 시험 = `*.test.ts(x)`(uiref 제외).
- **시안 class**: `workspace.css`에서 주석을 지우고 `.이름` 선택자로 정의된 class 85개. className 문자열(`"…"`, `{'…'}`, `` {`…`} ``, `cn(…)` 안 문자열)의 토큰이 이 집합에 있으면 시안 class.
- **금지 utility**(DS §3.1): `text-(xs|sm|base|lg|…|[px])` 크기, `text-<색·토큰>`·`bg-*`·`border-<색>`·`ring-*`·`fill/stroke-*` 색, `font-(thin…black)` 굵기, `leading-*`·`tracking-*`, `shadow*`, `rounded*`. `font-mono` 별도. 변형 접두사(`hover:` 등)는 떼고 분류.
- **shadcn 사용**: `from '…ui/<name>'` import 파일(components/ui 자신 제외), JSX `<Button` 등 태그 수.
- **용어**: 주석(`//`, `/* */`, `{/* */}`)을 지우고 문자열 리터럴·JSX 글자 안에서 단어 출현 수.
- **패턴 행렬**: 화면 파일마다 `<MapNote`·`<PageHeader`·`className="mapnote`·`<FilterPanel`·`className="filters`·`detailarea`·`<DetailTabs`·`tablewrap`·`<Table`·`<Field`·`<FormRow`·`<DetailRow`·`detailgrid`·`<dl` 출현 수.
- **export 사용처**: main 파일의 `export const|function` 이름이 다른 main 파일에 단어로 나오지 않으면 "main 사용 0".
- 한계: grep 근사치다. 주석 안 class 이름·문자열 조합 class는 일부 섞이거나 빠질 수 있다(예: `tablewrap` 60은 주석 포함 가능). 시험·빌드·캡처는 실행하지 않았다 — 화면 모양 판정은 코드 기준이다.

## 9. 다음 동작 제안 (Coordinator용, 결정 아님)

1. **질문 2건 묶음**: UI-02(승인 상세 대상 표시 — 내부 키 대 사람용 값), UI-05(`apr_`가 외부 식별자인지). 권장안 [추정]: 승인 상세는 가맹점 화면과 같은 사람용 값 + 접힌 "기술 정보"에 키, `apr_`는 운영자 소통용으로 PII #25 예외 명시.
2. 결정 없이 할 수 있는 작은 작업: UI-01(결정 명령 응답 유실 문구·재조회 — 금융 의미 문구라 Coordinator 문구 확인), UI-04(접두어 상수 공용화 — 시험 추가만), UI-10(resetSession), UI-25(감사 표 이름).
3. DS §8 B2를 시작할 때 UI-06·07·23·27(주석)을 함께, B3에서 UI-15·16·17·18·26, B4에서 UI-08·30, B5에서 UI-24.
4. 용어집(UI-20·28)은 사용자에게 정식 용어를 물을 항목 목록부터 만든다.

## 10. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(GOV-DIAG-001 관리자 UI·컨벤션 진단, 기준 `41139db`) |
