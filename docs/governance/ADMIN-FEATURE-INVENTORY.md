# ADMIN-FEATURE-INVENTORY — 내부 관리자(React) 기능 인벤토리 (진단, rev 0.1)

- 작성: 2026-11-25, 진단 작업(GOV-DIAG-001 범위의 관리자 UI 기능 인벤토리). **코드 수정 없음, 실행 검증 없음** — 정적 읽기 결과다. 시험 파일은 "있음"만 확인했고 이번에 실행하지 않았다(통과 여부 미확인).
- 기준: main `41139db`(작업 트리에 이 진단과 무관한 미커밋 변경 있음 — `harness/tasks.json`, `docs/rules/`, `RULE-GOV-001`).
- 기준 파일 sha256: `navigation.ts` `9bf7c7d2…080800c`, `App.tsx` `a1f26907…ca017b6d`, `api/adminApi.ts` `ac966934…c4fc627`.
- 추측 금지: 확인하지 못한 칸은 **미확인**. 근거는 `file:line`.

## 0. 표기

| 약어 | 경로 |
|---|---|
| `FE/` | `apps/internal-admin/frontend/src/` |
| `BE/` | `apps/internal-admin/src/main/kotlin/com/paycore/admin/` |
| `BT/` | `apps/internal-admin/src/test/kotlin/com/paycore/admin/` |
| `M:` | ADMIN → MASTER 서명 내부 API(`/internal/v1/admin/...`) |
| `P:` | ADMIN → PAYMENT 서명 내부 API |
| `F:` | ADMIN → FINANCIAL 서명 내부 API |

- 역할: `V`=VIEWER, `O`=OPERATOR, `A`=APPROVER, `S`=SECURITY_ADMIN, `U`=UNMASK. "조회4" = V·O·A·S.
- 화면 가드는 표시만 한다(`FE/navigation.ts:4`, `FE/App.tsx:184-186`). 권한 판정은 서버 Use Case가 요청마다 계정 현재 행으로 한다 — Spring Security는 `/api/**` 인증만 요구하고 역할 규칙은 없다(`BE/security/SecurityConfig.kt:135-147`). 백엔드 권한 근거는 Use Case 상수다: `BE/account/domain/AdminPermission.kt:7-19`, `BE/merchantops/application/BusinessPermissions.kt:10-25`, `BE/approval/domain/ApprovalKind.kt:17-68`.
- 구현 상태: **완성** = 화면 + ADMIN API + 내부 API 연결과 시험 파일이 모두 있음(실행 미확인) / **부분** = 일부 단계·종류 없음 / **화면만** = 서버 호출 없음(의도된 내비게이션·표시 포함) / **백엔드만** = 화면 없음.
- 소유 도메인: 실제 호출되는 서비스 기준. ADMIN = ADMIN DB(`paycore_admin`)만.

## 1. 메뉴·라우트 구조

라우터 라이브러리 없음 — `view` 상태 하나로 화면을 바꾼다(URL에 상태 없음, `FE/App.tsx:83-87`, `FE/App.tsx:96`). 메뉴 정의 `FE/navigation.ts:77-115`, 역할별 표시 `FE/navigation.ts:49-70`, 화면 배치 `FE/App.tsx:254-331`.

| 사이드바 구역 | 메뉴(id) | 탭 / 하위 화면(page) | 화면 컴포넌트 | 표시 역할(`pagesFor`) |
|---|---|---|---|---|
| — | 운영 홈(home) | — | `HomeScreen` (`FE/App.tsx:255`) | 전원 |
| 내 업무 | 승인함(approvals) | 승인함(approvals) | `ApprovalsScreen view=pending` (`FE/App.tsx:275`) | A (`FE/navigation.ts:52`) |
| 내 업무 | 내 요청(mine) | 내 요청(mine) | `ApprovalsScreen view=mine` (`FE/App.tsx:276`) | S·O (`FE/navigation.ts:53`) |
| PAYMENT OPERATIONS | 사업자·가맹점(merchants) | 가맹점(merchants) → 하위: 가맹점 등록(merchantRegistration) | `MerchantsScreen`, `MerchantRegistrationScreen` (`FE/App.tsx:277-291`, `312-324`) | 가맹점 조회4 (`:55`), 등록 O (`:60`) |
| 〃 | 〃 | 사업자(businesses) → 하위: 사업자 등록(businessRegistration) | `BusinessesScreen`, `BusinessProfileFormScreen mode=create` (`FE/App.tsx:292-311`) | 사업자 O·A (`:57`), 등록 O (`:60`) |
| 〃 | 결제 관리(payments) | 결제 보정 취소(paymentOps), 거래 조회(transactions) | `PaymentOpsScreen`, `TransactionsScreen` (`FE/App.tsx:325-326`) | 조회4 (`:61`, `:63`) |
| 〃 | 매입·대사(recon) | 대사 발견(reconciliation), 매입 현황(acquisitions) | `ReconciliationScreen`, `AcquisitionsScreen` (`FE/App.tsx:327-328`) | 대사 S·A (`:67`), 매입 조회4 (`:65`) |
| WORKSPACE | 계정·권한(accounts) | 계정 관리(accounts), 접속·감사 이력(audit) | `AccountsScreen`, `AuditScreen` (`FE/App.tsx:329-330`) | 계정 U 외 역할 (`:66`), 감사 전원 (`:68`) |
| (로그인 전) | — | 로그인 / 등록 코드 | `LoginScreen`, `EnrollmentScreen` (`FE/App.tsx:172-182`) | 비로그인 |

- 하위 화면(등록 2개)은 탭 막대에 없고 목록 제목 줄 '＋ … 등록' 버튼으로 연다(D-ADMIN-UI-006, `FE/navigation.ts:91-97`, `FE/App.tsx:198-204`).
- `FE/uiref/**`(PG Workspace kit 재현 — 메뉴 지도·모듈 샘플 화면)는 `vite --mode uiref`일 때만 별도 빌드되는 PC 로컬 미리보기다. 관리자 jar에 들어가지 않는다(`apps/internal-admin/frontend/vite.config.ts` 주석·`input` 분기, `FE/uiref/UiRefIsolation.test.ts:4-5`). 이 인벤토리의 운영 기능에서 제외한다.

## 2. ADMIN 화면 API 전수 대조(백엔드 61개 ↔ 프런트 호출)

프런트 클라이언트 `FE/api/adminApi.ts:1051-1123`(모두 `apiFetch` 같은 출처 `/api/`, `FE/api/client.ts:63-64`). 백엔드 매핑은 각 컨트롤러.

| 컨트롤러 | 엔드포인트 수 | 프런트 호출 | 비고 |
|---|---|---|---|
| `AuthController` (`BE/account/adapter/web/AuthController.kt:42-116`) | 5 | 5 | csrf·password·totp·logout·me |
| `EnrollmentController` (`…/EnrollmentController.kt:36-85`) | 4 | 4 | |
| `DemoQuickLoginController` (`…/DemoQuickLoginController.kt:36,45`) | 2 | 2 | 조건부 Bean(`DemoQuickLoginCondition.kt`) |
| `AccountController` (`…/AccountController.kt:31-66`) | 6 | 6 | |
| `AuditController` (`…/AuditController.kt:26`) | 1 | 1 | |
| **`UnmaskController`** (`…/UnmaskController.kt:22`) | 1 | **0** | 화면 없음 — §5-2 |
| `ApprovalController` (`BE/approval/adapter/web/ApprovalController.kt:54-226`) | 15 | 15 | 종류별 요청 8 + 일반 요청 1 + 승인/거절/철회 3 + 목록·상세·근거 3 |
| `ReconciliationFindingController` (`…/ReconciliationFindingController.kt:15`) | 1 | 1 | |
| `MerchantController` (`BE/merchantops/adapter/web/MerchantController.kt:37,59,73`) | 3 | 3 | |
| `MerchantRegistrationController` (`…/MerchantRegistrationController.kt:30-57`) | 7 | 7 | |
| `MerchantTargetLabelController` (`…/MerchantTargetLabelController.kt:23`) | 1 | 1 | |
| `BusinessQueryController` (`…/BusinessQueryController.kt:31-49`) | 4 | 4 | |
| `BusinessCommandController` (`…/BusinessCommandController.kt:31-39`) | 3 | 3 | |
| `TransactionController` (`BE/paymentops/adapter/web/TransactionController.kt:32,42`) | 2 | 2 | |
| `PaymentTargetController` (`…/PaymentTargetController.kt:19-22`) | 1 | 1 | |
| `HomeKpiController` (`…/HomeKpiController.kt:19`) | 1 | 1 | |
| `AcquisitionController` (`BE/financialops/adapter/web/AcquisitionController.kt:28-47`) | 4 | 4 | |
| 합계 | **61** | **60** | 미사용 1 = `POST /api/unmask` |

그 밖 웹 진입점: `SpaFallbackController`(SPA 문서), `JsonErrorController`(`/error`), `GET /actuator/health`(관리 포트) — 업무 기능 아님.

## 3. 기능 인벤토리

### 3.1 공용(COM) · 인증(AUTH) — 메뉴 밖

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 상단 바 | ADM-F-COM-01 | 보이는 메뉴·탭 이름 검색(거래·가맹점 검색 아님) | 로그인 사용자 / 서버 호출 없음 | 입력 → 결과 경로 → 화면 열기 | — | 없음 (`FE/navigation.ts:154-167`, `FE/components/MenuSearch.tsx`) | 화면만(의도) | D-ADMIN-UI-003 | `FE/navigation.test.ts` / — |
| 사이드바 아래 | ADM-F-COM-02 | 라이트·다크 테마 | 전원 / 없음 | 선택 → `<html data-theme>` | — | 없음 (`FE/App.tsx:91`, `FE/lib/theme.ts`) | 화면만(의도) | D-ADMIN-HOME-001 | `FE/lib/theme.test.tsx` / — |
| 승인함·감사·변경 이력 공용 | ADM-F-COM-03 | 내부 키(mer_) 대신 현재 상호·코드 라벨 표시 | 조회 화면 사용자 / 조회4 (`BE/merchantops/application/MerchantTargetLabelService.kt:60`) | 화면이 대상 merchantId 묶음 → 라벨 → 표기 | MASTER | `POST /api/merchant-labels` (`FE/lib/merchantTarget.ts:94`) → `M: POST /internal/v1/admin/merchant-labels` (`BE/merchantops/adapter/integration/HttpMasterOpsGateway.kt:379`) | 완성 | MERCHANT-CODE-001 §5.6, ADMIN-HISTORY-KEYS-001 | `FE/lib/merchantTarget.test.ts`, `FE/screens/ApprovalsScreen.targetLabels.test.tsx`, `FE/screens/AuditScreen.targetLabels.test.tsx` / `MerchantTargetLabelHttpSpec`, `…ServiceSpec`, `…ControllerSpec`, `…RateLimitHttpSpec`, `MerchantLabelGatewaySpec` |
| 로그인 화면 | ADM-F-AUTH-01 | 2단계 로그인 + 세션 복원 | 비로그인 / permitAll(`SecurityConfig.kt:136-137`), `me`는 인증 | CSRF → 비밀번호 → TOTP → 홈. 새로고침 시 `me`로 복원 (`FE/App.tsx:125-131`) | ADMIN | `GET /api/auth/csrf`, `POST /api/auth/password`, `POST /api/auth/totp`, `GET /api/auth/me` (`FE/screens/LoginScreen.tsx:41-57`) | 완성 | ADMIN-BOOT-001 §4.3·§4.4, D-ADMIN-008 | `LoginScreen.test.tsx`, `LoginScreen.workspace.test.tsx`, `App.test.tsx`, `api/client.test.ts` / `security/LoginHttpSpec`, `account/application/LoginServiceSpec`, `AuthenticationFailureSpec`, `SpaCsrfSpec`, `IpAllowlistSpec` |
| 로그인 화면 '등록 코드' | ADM-F-AUTH-02 | 등록 코드로 비밀번호·TOTP 설정 | 비로그인 / permitAll(`SecurityConfig.kt:143`) | 코드 → (목적별) 비밀번호 → TOTP 키 확인 → 완료 | ADMIN | `POST /api/enroll/start·password·totp·complete` (`FE/screens/EnrollmentScreen.tsx:59-94`) | 완성 (TOTP는 QR 없이 문자열 — `EnrollmentScreen.tsx:26-27`) | ADMIN-BOOT-001 §4.3.6 | `EnrollmentScreen.test.tsx`, `…workspace.test.tsx` / `AccountEnrollmentHttpSpec`, `EnrollmentFlowSpec` |
| 사이드바 아래 '로그아웃' | ADM-F-AUTH-03 | 로그아웃·세션 종료 안내(결과 불명 요청 안내 포함) | 로그인 / 인증 | 로그아웃 → 메모리 비움 → 로그인 화면(미해결 안내) (`FE/App.tsx:133-160`) | ADMIN | `POST /api/auth/logout` | 완성 | ADMIN-BOOT-001 §4.4 | `App.test.tsx`, `layout/AppLayout.test.tsx` / `LogoutAuditFailureSpec` |
| 로그인 화면 위 데모 카드 + 계정 바꾸기 | ADM-F-AUTH-04 | Demo 계정 간편 로그인(Demo 전용) | 비로그인 / 서버 켜짐 조건일 때만(`SecurityConfig.kt:140-141`, 꺼지면 404 → 숨김 `FE/api/adminApi.ts:1061-1068`) | 계정 목록 → 선택 → 로그인. 승인 시 TOTP 생략 표시(`FE/screens/ApprovalsScreen.tsx:189`) | ADMIN | `GET/POST /api/auth/demo-quick-login` (`FE/components/DemoQuickLoginPanel.tsx:27`, `FE/lib/useDemoQuickLoginAccounts.ts:13`) | 완성(Demo 한정) | D-ADMIN-AUTH-DEMO-001, ADMIN-DEMO-LOGIN-001 | `api/demoQuickLogin.test.ts`, `layout/DemoQuickLogin.app.test.tsx`, `LoginScreen.demoQuick.test.tsx`, `ApprovalsScreen.demoQuick.test.tsx` / `DemoQuickLoginHttpSpec`, `…DisabledHttpSpec`, `…StartupGuardSpec`, `DemoQuickStepUpHttpSpec`, `DemoQuickOwnerStepUpHttpSpec`, `DemoQuickMerchantStepUpHttpSpec` |

### 3.2 운영 홈(HOME)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 운영 홈 KPI | ADM-F-HOME-01 | 오늘(KST) 승인액·건수·취소·순승인(환경·통화별) | '거래 조회' 보이는 역할(`FE/screens/HomeScreen.tsx:167,203-213`) / 조회4 (`BE/paymentops/application/HomeKpiService.kt:70`) | 진입 시 1회 조회(polling 없음), 모양 검사 실패 시 오류 | PAYMENT | `GET /api/home-kpis` → `P: GET /internal/v1/admin/home-kpis` (`HttpPaymentOpsGateway.kt:183,340`) | 완성 | D-ADMIN-HOME-001·002, ADMIN-HOME-KPI-001 §4 | `HomeScreen.test.tsx` / `HomeKpiHttpSpec`, `HomeKpiServiceSpec`, `HomeKpiGatewaySpec` |
| 운영 홈 '지금 처리할 업무' | ADM-F-HOME-02 | 승인 대기·내 요청·열린 대사 발견 건수 | 해당 화면이 보이는 역할만 부른다(`HomeScreen.tsx:164-165`) / 승인 조회4, 대사 S·A | 메트릭별 조회 → 건수(200건 상한 표시 `atLeast`) → 클릭 시 화면 이동 | ADMIN | `GET /api/approvals?view=pending|mine`, `GET /api/reconciliation/findings` (`HomeScreen.tsx:186-188`) | 완성 | D-ADMIN-HOME-001 | `HomeScreen.test.tsx` / `ApprovalHttpSpec`, `ReconciliationFindingHttpSpec` |
| 운영 홈 '최근 거래' | ADM-F-HOME-03 | 최근 거래 5건 | 거래 조회 역할 / 조회4 | 최근 n일 기간·limit 5 조회 → 목록 → '전체 거래 보기' | PAYMENT(+MASTER 라벨) | `POST /api/payment-searches` (`HomeScreen.tsx:218`) → `P: POST …/payment-searches`, `M: POST …/merchant-labels` | 완성 | TXN-OPS-001, TXN-LIST-ENRICH-001 M-6 | `HomeScreen.test.tsx` / `TransactionLookupHttpSpec`, `TransactionListLabelSpec` |
| 운영 홈 '오늘의 정산 흐름'·지급 예정액 | ADM-F-HOME-04 | 정산·지급 단계 현황 | 전원 / **백엔드 없음** | 모든 단계 "준비 전" 고정 표시 | (FINANCIAL 예정) | 없음 (`HomeScreen.tsx:38-39,374`) | 화면만(기능 미구현 자리) — 정산 기능 없음 | SETTLEMENT-001(BACKLOG) | `HomeScreen.test.tsx` / — |
| 운영 홈 바로가기·'＋ 사업자 등록' | ADM-F-HOME-05 | 자주 쓰는 화면으로 이동 | 보이는 화면만(`HomeScreen.tsx:41-47,275,294-296`) / 이동 대상 화면의 권한 | 버튼 → 화면 열기 | — | 없음 | 화면만(의도) | D-ADMIN-UI-006 | `HomeScreen.test.tsx` / — |

### 3.3 내 업무 — 승인함·내 요청(APR)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 승인함 | ADM-F-APR-01 | 2인 승인 요청 검토·승인/거절(직전 TOTP) — 14종 | 화면 A(`FE/navigation.ts:52`, `canDecide` `FE/App.tsx:275`) / 목록 **조회4**(`BE/approval/application/ApprovalQueryService.kt:22-24,62`), 결정 A(`ApprovalKind.kt:68`) — **불일치 §5-5 #2** | 목록 → (상세) → TOTP → 승인/거절. ADMIN 소유 6종은 단일 TX, 소유 서비스 8종은 2-TX(승인 TX → TX 밖 소유 서비스 호출 → 결과 TX) (`ApprovalKind.kt:3-8`, `ApprovalController.kt:186-195`) | ADMIN(+실행: PAYMENT·FINANCIAL·MASTER) | `GET /api/approvals?view=pending`, `POST /api/approvals/{id}/approve`, `POST /api/approvals/{id}/reject` (`FE/screens/ApprovalsScreen.tsx:132,192-193`). 실행: `P: POST /internal/v1/admin/operator-cancellations`(+`/{id}`, `/{id}/deadline-confirmation`) (`HttpPaymentOpsGateway.kt:81,107,111,337`), `F: POST …/acquiring-operations/reacquisitions`, `GET …/decisions/{id}`(+deadline) (`HttpFinancialOpsGateway.kt:64-74,276-277`), `M: POST …/merchant-operations/{contract-revision-approvals, first-default-merchant-accounts, payment-method-activation-approvals, site-review-approvals, currency-enablements, merchant-code-changes}`·`approval-decisions`·`approval-deadline-confirmations` (`HttpMasterOpsGateway.kt:195,225`, `BE/merchantops/application/provided/MerchantApprovalClient.kt:41-`) | 완성 | D-ADMIN-007, D-ADMIN1-004·009·014·015, ADMIN-BOOT-001 §4.7, ADMIN-PAYMENT-OPS-001 §4.9-§4.16, MERCHANT-OPS-002 §9.5 | `ApprovalsScreen.test.tsx`, `.workspace`, `.owner`, `.merchant`, `.acquiring`, `.activation`, `.currency`, `.codeChange`, `.demoQuick`, `.targetLabels` / `ApprovalHttpSpec`, `ApprovalRaceSpec`, `ApprovalLockOrderSpec`, `ApprovalDecisionTableSpec`, `ApprovalFailureInjectionSpec`, `ApprovalExpiryIsolationSpec`, `UnresolvedApprovalRaceSpec`, `OwnerServiceApprovalHttpSpec`, `OwnerServiceApprovalFlowSpec`, `OwnerApprovalConcurrencySpec`, `OwnerApprovalFailureInjectionSpec`, `OwnerApprovalStepUpSpec`, `OwnerExecutionRecoverySpec`, `MerchantOpsRecoverySpec`, `MerchantActivationRecoverySpec` |
| 승인함·내 요청 '상세' | ADM-F-APR-02 | 승인 상세(소유 서비스 진행·재전송·무결성 경보) + 근거·사유 별도 열람 | 화면: 근거 버튼 A 또는 요청자 본인(`FE/components/OwnerApprovalDetail.tsx:123`) / 상세 **조회4·대상 제한 없음**(`ApprovalQueryService.kt:27-28`), 근거 A 또는 요청자(`BE/approval/application/OperatorCancelJustificationService.kt:56`) | 행 '상세' → 종류별 상세(결제 / 재매입 / 가맹점) → '근거 보기'(시도마다 감사) | ADMIN(+PAYMENT 대상·FINANCIAL 대상 재조회) | `GET /api/approvals/{id}`, `GET /api/approvals/{id}/justification` (`FE/api/adminApi.ts:1088-1090,1115`), `GET /api/payment-ops/payments/{id}/target` (`OwnerApprovalDetail.tsx:50`), `POST /api/acquisition-files/{id}/reacquisition-targets` (`FE/components/ReacquisitionApprovalDetail.tsx:75`) | **부분** — 상세 버튼이 PAYMENT·ACQUISITION_FILE·MERCHANT 대상에만 있다(`FE/screens/ApprovalsScreen.tsx:105`). ADMIN_ACCOUNT 6종은 목록 행만. 재전송 중단·무결성 경보의 수동 종료 없음(의도 — `OwnerApprovalDetail.tsx:115`, D-OPS-003) | D-OPS-003·010, D-ACQA-005, D-MOPS-008 ① | `components/OwnerApprovalDetail.compare.test.tsx`, `ApprovalsScreen.owner.test.tsx`, `.merchant`, `.acquiring` / `OperatorCancelJustificationHttpSpec`, `OperatorCancelJustificationWindowSpec`, `MerchantTargetQuerySpec` |
| 내 요청 | ADM-F-APR-03 | 내가 만든 요청 진행 확인·대기 중 철회 | 화면 S·O(`FE/navigation.ts:53`) / 목록 조회4(요청자 필터 `ApprovalQueryService.kt:24`), 철회는 요청자(서비스 판정 — 세부 미확인) | 목록 → 대기 건 '철회' | ADMIN | `GET /api/approvals?view=mine`, `POST /api/approvals/{id}/withdraw` (`ApprovalsScreen.tsx:132,194`) | 완성 | D-ADMIN-007 | `ApprovalsScreen.test.tsx` / `ApprovalHttpSpec` |

### 3.4 사업자·가맹점 — 가맹점 탭(MER)

가맹점 상세의 명령 버튼은 모두 `MerchantsScreen` 안에서 연다(`FE/App.tsx:280-282` — `canRegisterRevision`·`canRequestApproval` = O). 1인 등록 명령 백엔드 = ACTIVE + O(`BE/merchantops/application/MerchantRegistrationService.kt:59`), 2인 요청 = 요청자 O·승인자 A(`ApprovalKind.kt:34-65`).

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 가맹점 목록 | ADM-F-MER-01 | 가맹점 검색(상호·코드·사업자등록번호 정확 일치) | 목록 조회4 / 조회4(`BE/merchantops/application/MerchantLookupService.kt:209`). 번호 검색 칸 O·A(`FE/App.tsx:280`, `FE/screens/MerchantsScreen.tsx:545`) / O·A(`MerchantLookupService.kt:212`) — 일치 | 필터 → 검색 → keyset 다음 → 행 '상세' | MASTER | `POST /api/merchant-searches` (`MerchantsScreen.tsx:530`) → `M: POST …/merchant-searches` (`HttpMasterOpsGateway.kt:144,371`) | 완성 | D-MOPS-001·003, D-MCODE-002, D-ADMIN-UI-010 ⑦, D-PII-001 | `MerchantsScreen.test.tsx`, `.iaB2`, `screens/ListFilters.iaB6.test.tsx` / `MerchantLookupHttpSpec`, `MerchantLookupServiceSpec`, `MerchantNameSearchSpec`, `MerchantCodeSearchSpec`, `MerchantSearchBodyLimitSpec` |
| 가맹점 상세(머리 + 탭 6개) | ADM-F-MER-02 | 가맹점·사업자·Site·계약·MID·한도·진행 중 승인 일관 읽기(상세 감사) | 조회4 / 조회4 | 목록 → 상세 화면 전환 → 탭(개요/Site·계약/MID·개통/한도/변경 이력/기술 정보) (`MerchantsScreen.tsx:449-459`) | MASTER(+ADMIN 진행 중 승인) | `GET /api/merchants/{id}` (`MerchantsScreen.tsx:627`) → `M: GET …/merchants/{id}` (`HttpMasterOpsGateway.kt:149`) + ADMIN 미해결 승인(`MerchantController.kt:66`) | 완성 | D-MOPS-002, D-ADMIN-UI-009·010, MERCHANT-OPS-001 §3.2·§5.1 | `MerchantsScreen.detailView.test.tsx`, `.iaB1`, `.iaB3`, `.iaB4`, `.techInfo`, `App.merchantDetailView.test.tsx`, `DetailViewFocus.test.tsx`, `components/DetailTabs.test.tsx` / `MerchantLookupHttpSpec`, `MerchantLookupServiceSpec` |
| 가맹점 상세 '변경 이력' 탭 | ADM-F-MER-03 | 가맹점 변경 이력(대상 사람용 표기) | 조회4 / 조회4 | 탭 → 이력 → '더 보기'(after) | MASTER | `GET /api/merchants/{id}/history?after=` (`MerchantsScreen.tsx:612`) → `M: GET …/merchants/{id}/history/after/{seq}` (`HttpMasterOpsGateway.kt:154`) | 완성 | ADMIN-HISTORY-KEYS-001, D-ADMIN-UI-010 ③ | `MerchantsScreen.iaB1.test.tsx`(종류 라벨), `lib/historyTarget.test.ts` / `MerchantLookupHttpSpec` |
| 상세 'Site·계약' — 계약 Revision 등록 | ADM-F-MER-04 | 계약 조건 변경(새 Revision, 1인) | O / O | 계약 행 → 폼 → 저장(결과 불명 잠금·같은 submissionId 재전송) | MASTER | `POST /api/merchant-registrations/contract-revisions` (`FE/components/ContractRevisionForm.tsx:75`) → `M: POST …/merchant-operations/contract-revisions` (`HttpMasterOpsGateway.kt:165`, `BE/merchantops/application/required/MasterCommand.kt:36-37`) | 완성 | D-MOPS-004·006, MERCHANT-OPS-002 §9.2 | `MerchantsScreen.revision.test.tsx`, `components/ContractRevisionForm.layout.test.tsx` / `MerchantRegistrationHttpSpec`, `MerchantRegistrationServiceSpec` |
| 상세 'Site·계약' — 계약 승인 요청 | ADM-F-MER-05 | 계약 Revision 승인(2인, 근거 참조 필수, 등록자 ≠ 승인자) | O / 요청 O·승인 A | Revision → 근거 입력 → 요청 → 승인함 승인 → MASTER 실행 | MASTER(실행) / ADMIN(승인 행) | `POST /api/approvals/merchant-contract-approval` (`FE/components/ContractApprovalRequest.tsx:46`, `ApprovalController.kt:96`) → `M: …/contract-revision-approvals` | 완성 | D-MOPS-004·005·007·008, MERCHANT-OPS-002 §9.3-§9.5 | `MerchantsScreen.approval.test.tsx`, `ApprovalsScreen.merchant.test.tsx` / `MerchantApprovalFlowHttpSpec`, `MerchantOpsApprovalServiceSpec`, `ContractApprovalCommandBodySpec`, `MerchantContractApprovalDomainSpec`, `MasterApprovalGatewaySpec` |
| 상세 'MID·개통' — 첫 MID 발급 요청 | ADM-F-MER-06 | 첫 Default MID 발급(2인) | O(+발급 가능 조건 `MerchantsScreen.tsx:794`) / 요청 O·승인 A | 조건 충족 → 근거 → 요청 → 승인 → MASTER 발급 | MASTER | `POST /api/approvals/merchant-first-mid` (`FE/components/FirstMidRequest.tsx:89`, `ApprovalController.kt:110`) → `M: …/first-default-merchant-accounts` | 완성 | D-MOPS-004·008, MERCHANT-OPS-002 §9.5 | `MerchantsScreen.firstMid.test.tsx`, `.iaB3` / `FirstMidIssuanceCommandBodySpec`, `MerchantFirstMidDomainSpec` |
| 상세 'MID·개통' — 결제수단 개통 | ADM-F-MER-07 | 결제수단 개통 등록(1인, UNDER_REVIEW) → 개통 승인(2인, 등록자 ≠ 승인자) | O(`canOperate` `MerchantsScreen.tsx:1001`) / 등록 O, 요청 O·승인 A | 개통 등록 → 기관 회신 근거 → 승인 요청 → 승인 → MASTER 개통 | MASTER | `POST /api/merchant-registrations/payment-method-activations` (`FE/components/MerchantActivationArea.tsx:552`), `POST /api/approvals/merchant-payment-method-activation` (`:526`, `ApprovalController.kt:123`) → `M: …/payment-method-activations`, `…/payment-method-activation-approvals` | 완성(카드 1종 — `FE/lib/paymentMethods.ts`, D-ADMIN-UI-004 ⑤) | D-MOPS-007 ③·009 ③, D-MD-016, MERCHANT-OPS-002 §9.13-§9.16 | `components/MerchantActivationArea.methods.test.tsx`, `MerchantsScreen.activation.test.tsx`, `.activationMemory`, `ApprovalsScreen.activation.test.tsx` / `MerchantActivationFlowHttpSpec`, `MerchantActivationApprovalServiceSpec`, `ActivationApprovalCommandBodySpec`, `MasterApprovalGatewayB3Spec` |
| 상세 'Site·계약' — Site 심사 | ADM-F-MER-08 | Site 심사 승인(2인, APPROVED) / 거절(1인, 되돌릴 수 없음) | O / 승인 요청 O·승인 A, 거절 O | 근거 → 승인 요청 / 사유 코드 → 확인 → 거절 | MASTER | `POST /api/approvals/merchant-site-review-approval` (`MerchantActivationArea.tsx:434`, `ApprovalController.kt:138`), `POST /api/merchant-registrations/site-review-rejections` (`:325`) → `M: …/site-review-approvals`, `…/site-review-rejections` | 완성 | D-MOPS-007 ③·009 ③·010 ②, MERCHANT-OPS-002 §9.16 | `MerchantsScreen.activation.test.tsx` / `MerchantActivationFlowHttpSpec`, `MerchantActivationApprovalServiceSpec` |
| 상세 'MID·개통' — 통화 활성화 | ADM-F-MER-09 | MID 통화(KRW만) 활성화(2인, 효력 시점) | O / 요청 O·승인 A | 효력 모드·날짜 → 근거 → 요청 → 승인 → MASTER | MASTER | `POST /api/approvals/merchant-currency-enablement` (`MerchantActivationArea.tsx:146`, `ApprovalController.kt:156`) → `M: …/currency-enablements` | 완성(KRW만 — C-41) | D-MOPS-009 ②, MERCHANT-OPS-002 §9.26 | `MerchantsScreen.currency.test.tsx`, `ApprovalsScreen.currency.test.tsx` / `MerchantCurrencyApprovalServiceSpec`, `CurrencyEnablementCommandBodySpec`, `CurrencyEnablementInputRulesSpec` |
| 상세 '개요' — 가맹점 코드 변경 | ADM-F-MER-10 | 가맹점 코드 1회 변경(2인, 계약 승인 전) | O(+조건 `MerchantsScreen.tsx:864`) / 요청 O·승인 A | 새 코드·사유 → 요청 → 승인 → MASTER | MASTER | `POST /api/approvals/merchant-code-change` (`FE/components/MerchantCodeChangeRequest.tsx:65`, `ApprovalController.kt:173`) → `M: …/merchant-code-changes` | 완성 | D-MCODE-001·002, MERCHANT-CODE-001 MC-1b §5.5 | `MerchantsScreen.codeChange.test.tsx`, `ApprovalsScreen.codeChange.test.tsx`, `lib/merchantCode.test.ts` / `MerchantCodeChangeApprovalServiceSpec`, `MerchantCodeChangeCommandBodySpec`, `MerchantCodeShapeGoldenSpec`, `MerchantCodeTargetRefSpec` |
| 상세 머리 '결제 준비 상태' 줄·개통 점검표·서비스 개통 현황 | ADM-F-MER-11 | 결제 생성·승인 시작 조건 ①~⑦ 표시 | 조회4 / (상세 응답에서 화면이 계산 — 별도 API 없음) | 상세 응답 → 점검표 계산 | MASTER(상세 데이터) | `GET /api/merchants/{id}` 재사용 (`FE/components/PaymentReadinessLine.tsx`, `ServiceOpeningTimeline.tsx`, `MerchantActivationArea.tsx:60-90`) | **부분** — ⑥ 카드 Route 활성·⑦ 수수료·정산·한도는 서버 기능이 없어 "준비 전"(`MerchantActivationArea.tsx:85-86,92`) | D-ADMIN-UI-010·011 ①, MERCHANT-IA-001 §3.6 | `components/ServiceOpeningTimeline.test.tsx`; 줄 단독 시험 미확인 / — |
| 상세 '한도' 탭 | ADM-F-MER-12 | 가맹점·선택 MID 한도 표시 | 조회4 / 조회4 | 탭 → 공통 행 + 선택 MID 행 (`MerchantsScreen.tsx:376-377,457`) | MASTER | `GET /api/merchants/{id}` 재사용 | **부분** — 읽기 전용. 한도·수수료 정책 등록·변경 명령(B4) 없음 | MERCHANT-OPS-002 §9.12 B4, D-MOPS-004·007 ③ | `MerchantsScreen.iaB4.test.tsx` / `MerchantLookupHttpSpec` |
| 가맹점 목록 '＋ 가맹점 등록' (하위 화면) | ADM-F-MER-13 | 가맹점 신규 등록 마법사: ① 사업자 선택(REGISTERED만) ② 가맹점·Site ③ 계약 초안 ④ CSA | 화면 O(`FE/navigation.ts:60`) / 등록 O(`MerchantRegistrationService.kt:59`), ① 번호 조회 O·A(`BusinessPermissions.kt:10`) | 번호 조회 → 선택 → 가맹점(코드 발급) → Site → 계약 → 서비스 약정 → "가맹점 조회에서 보기" | MASTER | `POST /api/businesses/registration-number-lookups` (`FE/screens/MerchantRegistrationScreen.tsx:279`), `POST /api/merchant-registrations/merchants` (`:469`), `…/merchant-sites` (`:490`), `…/contracts` (`:509`), `…/service-agreements` (`:548`) → `M: POST …/businesses/registration-number-lookups` (`HttpMasterBusinessGateway.kt:66`), `M: POST …/merchant-operations/{merchants, merchant-sites, contracts, service-agreements}` (`MasterCommand.kt:27-33`) | 완성 | D-MOPS-004·006, D-BIZ-001·004·006 ③, D-MCODE-003, D-ADMIN-UI-004·005, MERCHANT-OPS-002 §4-§5 | `MerchantRegistrationScreen.test.tsx`, `.workspace`, `components/RegistrationNumberInput.test.tsx` / `MerchantRegistrationHttpSpec`, `MerchantRegistrationServiceSpec`, `MerchantRegistrationBodyParserSpec`, `MasterOpsGatewaySpec` |

### 3.5 사업자·가맹점 — 사업자 탭(BIZ)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 사업자 목록 | ADM-F-BIZ-01 | 사업자 검색 + 사업자등록번호 정확 조회(매 시도 감사) | O·A(`FE/navigation.ts:57`) / O·A(`BusinessPermissions.kt:10`, `BE/merchantops/application/BusinessQueryService.kt:56`) — 일치 | 조건 → 목록(keyset) / 번호 입력 → 0·1건 결과 → 상세 | MASTER | `POST /api/businesses/searches` (`FE/screens/BusinessesScreen.tsx:148`), `POST /api/businesses/registration-number-lookups` (`:203`) → `M: POST …/businesses/searches`, `…/registration-number-lookups` (`HttpMasterBusinessGateway.kt:60,66`) | 완성 | D-BIZ-006 ①, D-BIZLOOKUP-001, D-PII-001, D-ADMIN-UI-010 ⑦ | `BusinessesScreen.test.tsx`, `.iaB2`, `ListFilters.iaB6.test.tsx`, `lib/registrationNumber.test.ts` / `BusinessHttpSpec`, `BusinessQueryServiceSpec`, `MasterBusinessGatewaySpec`, `BusinessShapeGoldenSpec` |
| 사업자 상세(항상 편집 폼 + 하단 저장) + 이력 | ADM-F-BIZ-02 | 사업자 정보 열람(열람마다 감사)·수정(중요 정보 사유·version 충돌)·이력 | 열람 O·A / 수정 O(`canEdit` `FE/App.tsx:296`) / 열람 O·A, 수정 O(`BusinessPermissions.kt:16`) — 일치 | 목록 → 상세 → 수정·저장 → 다시 읽기(C-18) / 이력 '더 보기' | MASTER | `GET /api/businesses/{id}` (`BusinessesScreen.tsx:167`), `GET /api/businesses/{id}/history` (`:258`), `PUT /api/businesses/{id}` (`FE/screens/BusinessProfileFormScreen.tsx:187`) → `M: GET …/businesses/{id}`, `…/{id}/history/after/{seq}`, `POST …/businesses/commands/update` (`HttpMasterBusinessGateway.kt:71,76,86`) | 완성 | D-ADMIN-UI-011 ②, D-BIZ-003·005·006 ②·007, BUSINESS-PROFILE-001 §4.5·§7 | `BusinessesScreen.iaB5.test.tsx`, `.iaB4`, `BusinessProfileFormScreen.test.tsx`, `lib/businessProfile.test.ts` / `BusinessHttpSpec`, `BusinessProfileServiceSpec`, `BusinessAuditTargetSpec` |
| 사업자 상세 '등록 완료' | ADM-F-BIZ-03 | DRAFT → REGISTERED(가맹점 등록 가능 상태) | O / O(`BusinessPermissions.kt:19`) | DRAFT 상세 → 이어서 입력 → 등록 완료 | MASTER | `POST /api/businesses/{id}/registration` (`BusinessProfileFormScreen.tsx:187`) → `M: POST …/businesses/commands/complete-registration` (`BE/merchantops/application/provided/BusinessProfileUseCase.kt:22-`) | 완성 | D-BIZ-001·006 ① | `BusinessProfileFormScreen.test.tsx` / `BusinessProfileServiceSpec`, `BusinessHttpSpec` |
| 사업자 상세 '연결 가맹점' 탭 | ADM-F-BIZ-04 | 이 사업자의 가맹점 목록 → 가맹점 상세로 이동 | O·A / 가맹점 검색 조회4 | 탭 → businessId 조건 가맹점 검색 → '상세' → 가맹점 탭 상세 (`FE/App.tsx:302-305`) | MASTER | `POST /api/merchant-searches` (`BusinessesScreen.tsx:276`) | 완성 | D-ADMIN-UI-010 ⑧(Q-IA-8) | `App.businessLinkedMerchants.test.tsx`, `App.businessMerchantTabs.test.tsx` / `MerchantLookupHttpSpec` |
| 사업자 목록 '＋ 사업자 등록'(하위 화면) | ADM-F-BIZ-05 | 사업자 신규 등록 — 번호 확인 먼저, 이미 있으면 기존 안내·이동 | O(`FE/navigation.ts:60`) / 생성 O(`BusinessPermissions.kt:13`), 번호 조회 O·A | 번호 확인 → (없으면) 섹션 입력 → 저장(DRAFT/REGISTERED) → 상세 | MASTER | `POST /api/businesses/registration-number-lookups` (`BusinessProfileFormScreen.tsx:277`), `POST /api/businesses` (`:185`) → `M: POST …/businesses/commands/create` | 완성 | D-ADMIN-UI-011 ①, D-BIZ-003·006 ①, BUSINESS-PROFILE-001 §7 | `BusinessProfileFormScreen.iaB5b.test.tsx`, `.workspace` / `BusinessHttpSpec`, `BusinessProfileServiceSpec`, `LegacyBusinessRouteRemovedHttpSpec` |
| 사업자 상세 '이 사업자로 가맹점 등록' | ADM-F-BIZ-06 | REGISTERED 사업자를 가맹점 등록 ①에 넘김 | O / (이동만) | 버튼 → 가맹점 등록 마법사 ② (`FE/App.tsx:194-196`) | — | 없음(메모리 전달) | 화면만(의도) | D-BIZ-001, D-ADMIN-UI-004 ③ | `BusinessesScreen.test.tsx`(세부 미확인) / — |

### 3.6 결제 관리(PAY)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 결제 관리 › 결제 보정 취소 | ADM-F-PAY-01 | 운영자 보정 취소(부분 허용) — 대상 조회 + 2인 승인 요청 | 탭 조회4, 요청 양식 O(`FE/App.tsx:325`) / 대상 조회4(`BE/paymentops/application/PaymentTargetLookupService.kt:59`), 요청 O·승인 A(`ApprovalKind.kt:25`) — 일치 | 결제 ID → 대상(반환 가능액·보류) → 금액·목적·근거·사유 → 요청 → 승인함 → PAYMENT 실행 → 결과 | PAYMENT | `GET /api/payment-ops/payments/{id}/target` (`FE/screens/PaymentOpsScreen.tsx:123`) → `P: GET …/payments/{id}/operator-cancel-target` (`HttpPaymentOpsGateway.kt:63`); `POST /api/approvals/operator-correction-cancel` (`:153`, `ApprovalController.kt:64`) → 승인 시 `P: POST …/operator-cancellations` | 완성 | D-ADMIN-007, D-OPS-001~010, ADMIN-PAYMENT-OPS-001 §4.12-§4.20 | `PaymentOpsScreen.test.tsx`, `.workspace` / `OwnerServiceApprovalHttpSpec`, `OperatorCancelInputRulesSpec`, `OperatorCancelRequestSnapshotSpec`, `OperatorCancelFingerprintGoldenSpec`, `PaymentOpsGatewaySpec`, `PaymentOpsContractGoldenSpec`, `PaymentOpsDecisionGoldenSpec`, `PaymentOpsNonDisclosureSpec` |
| 결제 관리 › 거래 조회 | ADM-F-PAY-02 | 거래 목록(기간·MID·상태 / 결제 ID / MID+주문번호)·상세(상세 감사) | 조회4 / 조회4(`BE/paymentops/application/TransactionLookupService.kt:178`) | 조건 → 목록(keyset) → 상세(결제·승인·거래 사실·취소 이력·반환 잔액) | PAYMENT(+MASTER 라벨) | `POST /api/payment-searches` (`FE/screens/TransactionsScreen.tsx:256`), `GET /api/payments/{id}` (`:292`) → `P: POST …/payment-searches`, `GET …/payments/{id}` (`HttpPaymentOpsGateway.kt:142,173`), `M: POST …/merchant-labels` | 완성(읽기 전용) | D-TXOPS-001~005, TXN-OPS-001 §4-§5, TXN-LIST-ENRICH-001 | `TransactionsScreen.test.tsx`, `.workspace` / `TransactionLookupHttpSpec`, `TransactionLookupServiceSpec`, `TransactionListLabelSpec`, `TransactionDetailLabelSpec`, `TransactionSearchBodyLimitSpec`, `TransactionAuditFailureHttpSpec`, `PaymentReadGatewaySpec`, `PaymentListGoldenSpec`, `LookupPermitIsolationSpec` |

### 3.7 매입·대사(REC)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 매입·대사 › 대사 발견 | ADM-F-REC-01 | 승인 기록 ↔ PAYMENT 결과 불일치(열린 발견 수·종류별·최근) | S·A(`FE/navigation.ts:67`) / S·A(`BE/approval/application/ReconciliationFindingQueryService.kt:35`) — 일치 | 화면 열기 → 집계·최근 목록(code 열만) | ADMIN(발견 생성 job이 `P: GET …/operator-decisions/after/{watermark}`를 읽음 — `HttpPaymentOpsGateway.kt:119`) | `GET /api/reconciliation/findings` (`FE/screens/ReconciliationScreen.tsx:22`) | **부분** — 해결·종료 버튼 없음(운영 절차 LIVE Gate, `ReconciliationScreen.tsx:14`). 대사 범위는 운영자 보정 취소 결정만 | D-OPS-008, ADMIN-PAYMENT-OPS-001 §4.17 | `ReconciliationScreen.test.tsx`, `.workspace` / `ReconciliationFindingHttpSpec`, `OperatorDecisionReconciliationSpec`, `OperatorDecisionReconciliationRunSpec` |
| 매입·대사 › 매입 현황 | ADM-F-REC-02 | 매입 파일 목록(업무일·매입사·환경·3축 상태·확인 필요)·상세·레코드(상세·레코드 감사) | 조회4 / 조회4(`BE/financialops/application/AcquisitionLookupService.kt:167`) | 조건 → 목록(keyset) → 상세 → 레코드 검색 | FINANCIAL | `POST /api/acquisition-file-searches` (`FE/screens/AcquisitionsScreen.tsx:404`), `GET /api/acquisition-files/{id}` (`:444`), `POST /api/acquisition-files/{id}/record-searches` (`:462`) → `F: POST …/acquisition-file-searches`, `GET …/acquisition-files/{id}`, `POST …/{id}/record-searches` (`HttpFinancialOpsGateway.kt:145,150,162`) | 완성(읽기 전용) | D-ACQV-001~003, ACQ-VIEW-001 §4 | `AcquisitionsScreen.test.tsx`, `.badge`, `.workspace` / `AcquisitionLookupHttpSpec`, `AcquisitionLookupServiceSpec`, `AcquisitionAuditFailureHttpSpec`, `FinancialOpsGatewaySpec` |
| 매입 현황 상세 '재매입 요청' | ADM-F-REC-03 | 재매입(2인) — 대상 레코드 적격 확인 후 요청 | O(`FE/App.tsx:327`) / 요청 O·승인 A(`ApprovalKind.kt:28`) — 일치 | 레코드 선택 → 대상 확인 → 근거·사유 → 요청 → 승인(대상 재확인) → FINANCIAL 실행 | FINANCIAL | `POST /api/acquisition-files/{id}/reacquisition-targets` (`FE/components/ReacquisitionRequest.tsx:84`) → `F: POST …/acquisition-files/{id}/reacquisition-targets` (`HttpFinancialOpsGateway.kt:55`); `POST /api/approvals/acq-reacquisition` (`:98`, `ApprovalController.kt:80`) → `F: POST …/acquiring-operations/reacquisitions` | 완성(재매입 한 종류만) | D-ACQ-011, D-ACQA-001~005, ACQ-ADMIN-001 §3.2·§5 | `AcquisitionsScreen.reacquisition.test.tsx`, `ApprovalsScreen.acquiring.test.tsx` / `AcquiringOpsApprovalHttpSpec`, `AcquiringOpsApprovalFlowSpec`, `AcquiringOpsApprovalDomainSpec`, `AcquiringOpsGatewaySpec`, `ReacquisitionCommandBodySpec` |

### 3.8 계정·권한(ACC)

| 메뉴/위치 | 기능 ID | 업무 목적 | 역할·권한(화면 / 백엔드) | 업무 흐름 | 소유 도메인 | 연관 API | 구현 상태 / 미완성 | 적용 정책 | 시험(FE / BE) |
|---|---|---|---|---|---|---|---|---|---|
| 계정·권한 › 계정 관리 | ADM-F-ACC-01 | 관리자 계정·역할(활성/정지) 목록 | U 외 역할(`FE/navigation.ts:66`) / 조회4(`BE/account/application/AccountQueryService.kt:22-24,43`), 로그인 이름은 S만(`:25-27`) — 일치 | 화면 열기 → 목록(200건 상한·잘림 표시) | ADMIN | `GET /api/accounts` (`FE/screens/AccountsScreen.tsx:221`) | 완성 | D-ADMIN-002, D-ADMIN1-017 | `AccountsScreen.test.tsx`, `.workspace` / `AccountListTruncationSpec` |
| 계정 행 작업(1인) | ADM-F-ACC-02 | 계정 생성·등록 코드 재발급·정지·역할 회수 | S(`canManage` `FE/App.tsx:329`) / S(`AdminPermission.kt:13-16`, `BE/account/application/AdminAccountCommandService.kt:67,84,98,113`) — 일치 | 생성(코드 1회 표시) / 행 메뉴 → 확인 → 실행 | ADMIN | `POST /api/accounts`, `POST /api/accounts/{id}/enrollment-code`, `…/suspend`, `…/roles/{role}/revoke` (`AccountsScreen.tsx:250-266`) | 완성 | D-ADMIN-002, ADMIN-BOOT-001 §4.5.1 | `AccountsScreen.test.tsx` / `AdminAccountCommandSpec`, `AccountEnrollmentHttpSpec`, `AdminAccountSpec`, `RoleSuspensionsSpec` |
| 계정 행 작업(2인 요청) | ADM-F-ACC-03 | ADMIN 소유 2인 승인 요청 6종(역할 부여·잠금 해제·재활성·비밀번호 재설정·TOTP 재등록·역할 재활성) | S / 요청 S·승인 A(`ApprovalKind.kt:17-22`), 원인 후보 S(`AccountQueryService.kt:33-37`) | 종류 선택(역할 재활성은 서버 원인 후보에서만) → 요청 → 승인함 | ADMIN | `POST /api/approvals` (`AccountsScreen.tsx:256`, `ApprovalController.kt:54`), `GET /api/accounts/{id}/reactivation-causes` (`AccountsScreen.tsx:345`) | 완성 | D-ADMIN-002, D-ADMIN1-004·014·015, ADMIN-BOOT-001 §4.7 | `AccountsScreen.test.tsx` / `AdminOwnedCommandSpec`, `CredentialIssuerExclusionSpec`, `ApprovalHttpSpec` |
| 계정·권한 › 접속·감사 이력 | ADM-F-ACC-04 | 감사 조회(범위 = 서버 판정: A·S 전체, V·O 자기 기록), 가맹점 코드로 대상 필터 | 전원(`FE/navigation.ts:68`) / 조회4만(`AdminPermission.kt:9-10`, `BE/account/application/AuditViewService.kt:25-26`) — **불일치 §5-5 #1** | 필터 → 조회(조회도 감사). 코드 → 가맹점 검색으로 내부 키 해석 → `targetMerchantId` | ADMIN(+MASTER 코드 해석) | `GET /api/audit?…` (`FE/screens/AuditScreen.tsx:56`), `POST /api/merchant-searches` (`:93`), `POST /api/merchant-labels` | 완성 | D-ADMIN1-008, MERCHANT-CODE-001 §5.6 MC-3c | `AuditScreen.test.tsx`, `.targetLabels` / `AuditQueryHttpSpec`, `AuditQueryPermissionSpec`, `AuditFailureClosesActionSpec`, `AdminAuditEventTargetRefSpec` |
| (화면 없음) | ADM-F-ACC-05 | 마스킹 해제(사유 + 직전 TOTP + 감사 1행) | 화면 없음 / U(`AdminPermission.kt:18`, `BE/account/application/UnmaskService.kt:31-36`) | 요청 → 권한·사유·TOTP 검사 → 감사 → **데이터 공급자 없음 = 204** (`UnmaskController.kt:18,34`) | ADMIN | `POST /api/unmask` (`UnmaskController.kt:22`) — 프런트 호출 없음 | **백엔드만** — "틀만(AC10), 첫 실제 화면은 후속"(`docs/repository/ADMIN-BOOT-001-ADMIN-FOUNDATION.md:278,287`) | D-ADMIN-002, ADMIN-BOOT-001 §4.5.3, MERCHANT-FLOW-001 FLOW-10 | — / `ApprovalHttpSpec`, `AdminNonDisclosureSpec`, `DemoQuickStepUpHttpSpec`(`/api/unmask` 사용). 명세가 이름 붙인 `UnmaskFrameworkSpec`은 저장소에 없음(이름 불일치 — `ADMIN-BOOT-001…md:287`) |

## 4. 기능 수(상태별)

| 상태 | 수 | 기능 ID |
|---|---|---|
| 완성 | 34 | COM-03, AUTH-01~04, HOME-01~03, APR-01·03, MER-01~10·13, BIZ-01~05, PAY-01·02, REC-02·03, ACC-01~04 |
| 부분 | 4 | APR-02, MER-11, MER-12, REC-01 |
| 화면만 | 5 | 의도(API 불필요) 4: COM-01, COM-02, HOME-05, BIZ-06 / 기능 미구현 자리 1: HOME-04 |
| 백엔드만 | 1 | ACC-05 |
| 합계 | **44** | |

- "완성"은 화면·API·시험 파일이 있다는 정적 판정이다. 시험 실행·Verifier 판정·운영 준비 완료를 뜻하지 않는다.

## 5. 식별 절

### 5-1. 메뉴에 없지만 구현된 기능(라우트·화면·백엔드)

| # | 대상 | 근거 | 판단 |
|---|---|---|---|
| 1 | 로그인·등록 코드·데모 간편 로그인 화면(로그인 전) | `FE/App.tsx:172-182`, `FE/components/DemoQuickLoginPanel.tsx` | 메뉴 밖이 정상 |
| 2 | 가맹점 등록·사업자 등록(탭이 아닌 하위 화면, 목록 '＋' 버튼·홈 버튼으로만 진입) | `FE/navigation.ts:94-97`, `FE/App.tsx:198-204`, `FE/screens/HomeScreen.tsx:294-296` | 의도(D-ADMIN-UI-006). 메뉴 검색에는 나온다(`FE/navigation.ts:163`) |
| 3 | 가맹점 상세 안 명령 9종(Revision·계약 승인·첫 MID·개통 등록/승인·Site 승인/거절·통화·코드 변경) | §3.4 | 메뉴가 아니라 상세 탭 안. 메뉴 설명에만 언급(`FE/navigation.ts:98`) |
| 4 | 백엔드 `POST /api/unmask` | `BE/account/adapter/web/UnmaskController.kt:22` | 화면 없음(§5-2) |
| 5 | 백그라운드 job(소유 서비스 실행 회복, 운영자 결정 대사, 승인 만료) | `BE/approval/application/OwnerExecutionRecoveryService.kt`, `OperatorDecisionReconciliationService.kt` | 화면 없는 서버 기능(의도). 결과만 승인 상세·대사 발견에 보임 |
| 6 | 1회성 비웹 명령(부트스트랩·복구) | `BE/account/adapter/batch/`(시험 `BT/account/adapter/batch/BootstrapCommandSpec.kt`, `RecoveryCommandSpec.kt`) | 화면 없는 운영 명령(의도, ADMIN-BOOT-001 §4.9) |
| 7 | `FE/uiref/**` 메뉴 지도·모듈 샘플 화면 | `vite.config.ts` uiref 분기 | 로컬 미리보기 전용, 배포 번들 밖 |

### 5-2. 화면만 있고 API 없는 / 백엔드만 있고 화면 없는 기능

- 화면만(기능 미구현): **HOME-04** 정산 흐름·지급 예정액 "준비 전"(`FE/screens/HomeScreen.tsx:38-39,374`) — 정산 기능 BACKLOG(SETTLEMENT-001).
- 화면만(서버 기능 없는 표시 항목): **MER-11** 점검표 ⑥ 카드 Route·⑦ 수수료·정산·한도 "준비 전"(`FE/components/MerchantActivationArea.tsx:85-86`).
- 화면만(의도): COM-01·COM-02·HOME-05·BIZ-06(내비게이션·표시).
- 백엔드만: **ACC-05** `POST /api/unmask` — UNMASK 역할을 받아도 쓸 화면이 없고, 서버도 대상 데이터 공급자가 없어 항상 204(`UnmaskController.kt:18,34`).
- 백엔드는 있는데 화면이 일부만 쓰는 것: `GET /api/approvals/{id}`가 ADMIN_ACCOUNT 6종도 돌려주지만 화면은 상세 버튼을 PAYMENT·ACQUISITION_FILE·MERCHANT에만 둔다(`FE/screens/ApprovalsScreen.tsx:105`).

### 5-3. 중복 기능(같은 업무를 두 화면 이상이 함)

| # | 업무 | 화면 | 판단 |
|---|---|---|---|
| 1 | 사업자등록번호 정확 조회(같은 API·같은 감사·같은 번호 버킷) | 사업자 목록 번호 검색(`BusinessesScreen.tsx:203`), 사업자 등록 1단계 중복 확인(`BusinessProfileFormScreen.tsx:277`), 가맹점 등록 ① 사업자 선택(`MerchantRegistrationScreen.tsx:279`) | 목적이 다름(검색/중복 방지/선택). 결과 표시·오류 문구 규칙이 세 곳에 따로 있음 — 공통화 후보(미확인: 문구 차이 여부) |
| 2 | 결제 한 건 보기 | 결제 보정 취소 '대상 조회'(`GET /api/payment-ops/payments/{id}/target`), 거래 조회 상세(`GET /api/payments/{id}`) | 다른 내부 API(`operator-cancel-target` vs `payments/{id}`). 보정 취소 탭에서 결제 ID를 직접 입력해야 하며 거래 조회 상세에서 넘어가는 경로는 없음(확인한 범위 — `FE/App.tsx:325-326`에 연결 prop 없음) |
| 3 | 사업자 등록 진입 | 운영 홈 '＋ 사업자 등록'(`HomeScreen.tsx:294-296`), 사업자 목록 '＋ 사업자 등록'(`FE/App.tsx:299`) | 같은 하위 화면. 진입점 중복(기능 중복 아님) |
| 4 | 승인 대기 건수·목록 | 운영 홈 '지금 처리할 업무'(`HomeScreen.tsx:186-190`), 승인함 | 같은 API 두 번 호출(목록 200건 상한 공유) |
| 5 | 최근/전체 거래 목록 | 운영 홈 최근 거래(`HomeScreen.tsx:218`), 거래 조회 | 같은 API(limit만 다름) |
| 6 | 근거 열람 클라이언트 | `justification`·`reacquisitionEvidence`·`merchantApprovalEvidence` 3 메서드가 같은 `GET /api/approvals/{id}/justification` (`FE/api/adminApi.ts:1089,1090,1115`) | 응답 타입만 다른 클라이언트 중복 |
| 7 | 재매입 대상 확인 | 요청 시(`ReacquisitionRequest.tsx:84`)와 승인 상세(`ReacquisitionApprovalDetail.tsx:75`) | 의도된 재확인(D-ACQA) — 중복 아님으로 판단 |
| 8 | 가맹점 상세 진입 | 가맹점 목록, 사업자 '연결 가맹점'(`FE/App.tsx:302-305`), 가맹점 등록 결과 '가맹점 조회에서 보기'(`FE/App.tsx:316-319`) | 한 화면 재사용(중복 아님) |

### 5-4. 누락된 업무 기능(명세·결정에 있으나 화면 없음)

| # | 업무 기능 | 근거 | 현재 |
|---|---|---|---|
| 1 | 사업자 '계약 진행'(접수 건 생성·전이), 계약 진행현황 목록, 영업대행사 관리, 사업자 메모, 리스크 표시, 담보(참고), 대표자·실소유자 KYC, 원문 조회 | D-FLOW-001~009, `docs/repository/MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md` §4A·§6 FLOW-1~10 | 명세 rev 0.3d·결정 완료, 코드 없음(`FE/` 전체에 '접수'·'KYC' 0건). 작업 MERCHANT-FLOW-001 IN_PROGRESS |
| 2 | 카드 Route 개통 등록(1인)·승인(2인), Route 종료 | D-MOPS-009 ①③, D-MOPS-010 ③, `MERCHANT-OPS-002…md` §9.13 B3-3 | ADMIN API·화면 없음(`MerchantRegistrationController.kt:30-57`, `ApprovalController.kt:54-173`에 없음). 점검표 ⑥ "준비 전" |
| 3 | 결제수단·Route 재개, Site 활성 재확인(2인) | D-MOPS-010 ②, §9.13 B3-4 | 없음 |
| 4 | 한도·수수료·정산 정책 등록·변경·종료(B4) | D-MOPS-004·007 ③, §9.12 B4 | 한도 탭 읽기 전용(MER-12) |
| 5 | 안전 방향 1인 중지·종료(B5: 결제수단·Route 중지/종료, Site 확인 철회, 통화 종료) | D-MOPS-004, §9.12 B5 | 없음 |
| 6 | 매입 조작: REVIEW_REQUIRED 보류 해소, TRANSMISSION_QUARANTINED 해소, 격리 응답 재반영 | `ACQ-ADMIN-001…md` §3.3-§3.5 | 재매입만 구현(REC-03). ACQ-ADMIN-002 BACKLOG |
| 7 | 마스킹 해제 실제 화면 | D-ADMIN-002, ADMIN-BOOT-001 §4.5.1·§4.5.3 | 백엔드 틀만(ACC-05) |
| 8 | 대사 발견 해결·종료·보정 | D-OPS-008, `ReconciliationScreen.tsx:14`, RECON-001 BACKLOG | 조회만(REC-01) |
| 9 | 정산·지급(정산 흐름·지급 예정액) | 운영 홈 자리(HOME-04), SETTLEMENT-001 BACKLOG | 없음 |
| 10 | 가맹점 생명주기(중지·재개·종료, 추가 MID, PRIMARY 교체), API key 발급, Webhook endpoint, 지급계좌 | D-ADMIN-002(관련 작업 열), `harness/tasks.json` MASTER-LIFECYCLE-001·MASTER-CREDENTIAL-001·WEBHOOK-ENDPOINT-001·MASTER-PAYOUT-ACCOUNT-001(모두 BACKLOG) | 없음 |
| 11 | 운영 홈 결제 예외 집계(UNKNOWN 승인·진행 중 취소·hold 등) | ADMIN-HOME-ATTENTION-001 BACKLOG | 없음 |
| 12 | Site·계약 사람용 코드 표시 | D-ADMIN-UI-007, SITE-CONTRACT-CODE-001 BACKLOG | 없음 |

### 5-5. 권한 불일치(화면 노출 vs 백엔드 권한)

| # | 대상 | 화면 | 백엔드 | 영향 |
|---|---|---|---|---|
| 1 | 접속·감사 이력 탭 — **UNMASK 단독 계정** | 항상 노출(`FE/navigation.ts:68`; `FE/navigation.test.ts:21`이 `['audit']`로 고정) | VIEW_OWN_AUDIT = 조회4, UNMASK 없음(`AdminPermission.kt:9-10`, `AuditViewService.kt:25-26`) → 거부 | UNMASK만 가진 계정은 유일하게 보이는 업무 탭에서 403. 명세 권한 표도 감사 조회를 조회4로 둔다(`ADMIN-BOOT-001…md:277`). 화면 쪽이 넓음 |
| 2 | 승인 대기 목록 | APPROVER에게만(`FE/navigation.ts:52`) | 조회4 모두 전체 대기 목록 조회 가능(`ApprovalQueryService.kt:22-24,62`; `BT/approval/ApprovalHttpSpec.kt:56`이 VIEWER의 `?view=pending` 호출을 시험) | 백엔드가 넓음(의도된 "마스킹된 조회"로 보이나 화면과 다름 — 결정 근거 미확인) |
| 3 | 승인 상세 `GET /api/approvals/{id}` | 목록에서만 연다 | 조회4면 어떤 승인 ID든 상세 조회(요청자·대상 필터 없음 — `ApprovalQueryService.kt:27-28`) | 백엔드가 넓음. 근거·사유는 별도 권한이라 비노출(`OperatorCancelJustificationService.kt:56`) |
| 4 | 내 요청 | S·O(`FE/navigation.ts:53`) | 조회4(`ApprovalQueryService.kt:24`) | V·A는 요청을 만들 수 없어 실제 영향 없음 |
| 5 | 마스킹 해제 | 화면 없음 | U 허용(`AdminPermission.kt:18`) | 권한은 부여 가능(`ROLE_GRANT`)하나 쓸 곳 없음 |
| 6 | 결제 보정 취소 탭 | 조회4에 탭 노출, 요청 양식만 O | 대상 조회 조회4, 요청 O | 일치(참고) |
| 7 | 사업자·가맹점 명령, 매입 재매입, 계정 명령, 대사 | O / O, S / S, S·A / S·A | — | 일치(확인 범위: §3.4~§3.8 각 행) |

## 6. 요약 상위 10

1. 기능 44개: 완성 34 · 부분 4 · 화면만 5(의도 4, 미구현 자리 1) · 백엔드만 1. 백엔드 화면 API 61개 중 60개를 프런트가 호출 — 미사용은 `POST /api/unmask` 하나.
2. **권한 불일치 1(화면이 넓음)**: UNMASK 단독 계정에 감사 탭이 보이지만 서버는 403(`navigation.ts:68` vs `AdminPermission.kt:9-10`).
3. **권한 불일치 2·3(백엔드가 넓음)**: 승인 대기 목록·임의 승인 상세를 VIEWER·OPERATOR·SECURITY_ADMIN도 API로 읽을 수 있음(`ApprovalQueryService.kt:22-28,62`) — 화면은 APPROVER만 보임. 의도 여부 결정 근거 미확인.
4. **누락 최대 묶음**: MERCHANT-FLOW-001(계약 진행·진행현황·영업대행사·메모·리스크·담보·KYC·원문 조회) — 결정 D-FLOW-001~009 완료, 코드 0.
5. **가맹점 개통 남은 배치**: 카드 Route(B3-3)·재개(B3-4)·한도/수수료(B4)·안전 중지(B5) 화면·API 없음 → 점검표 ⑥⑦ "준비 전"(MER-11·12 부분).
6. **마스킹 해제**는 백엔드 틀만(데이터 공급자 없음, 204) — 화면·대상 없음. 명세가 말한 `UnmaskFrameworkSpec` 이름의 시험 파일은 없음(다른 Spec이 대신 덮음).
7. **대사 발견은 조회만** — 해결·종료·보정 없음(LIVE Gate). 매입 조작은 재매입 한 종류만(보류·격리 해소 3종 BACKLOG).
8. **정산·지급 기능 없음** — 운영 홈의 정산 흐름·지급 예정액은 "준비 전" 자리표시(HOME-04).
9. **중복**: 사업자등록번호 정확 조회가 3 화면에 각각 구현, 근거 열람 클라이언트 메서드 3개가 같은 endpoint, 결제 한 건 보기가 두 경로(보정 취소 대상 / 거래 상세)로 서로 연결 없음.
10. **부분 화면**: 승인 상세는 소유 서비스 종류만 — ADMIN 계정 대상 6종(역할 부여 등)은 상세 화면 없음(`ApprovalsScreen.tsx:105`).

## 7. 미확인·한계

- 시험은 파일 존재와 describe 이름만 확인했다. 실행·통과·개수 미확인.
- 철회(`withdraw`)의 요청자 본인 판정은 `ApprovalService` 세부를 읽지 않아 미확인.
- 각 결정(D-ID)과 명세 §의 대응은 코드 주석·명세 머리를 따라 연결했다. 결정 원문 전체 대조는 하지 않았다(`docs/DECISIONS.md` 해당 행 번호: D-ADMIN-002 72, D-ADMIN-007 77, D-ADMIN-008 78, D-OPS-010 160, D-MOPS-001~010 181-190, D-ADMIN-UI-010·011 243-244, D-FLOW-001~009 247-255).
- 화면 문구 차이(중복 #1) 비교, 홈 '지금 처리할 업무' 행 구성 전체는 읽지 않았다.
