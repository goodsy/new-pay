# AUTHN-001 결제창(Thymeleaf)·AuthenticationSession endpoint·가상 인증기관 Simulator 명세 (rev 1.3)

rev 1.0 작성 2026-10-16 KST(기준 `273068a`, sha256 `facee0ca1961…`). **rev 1.1 갱신 2026-10-16 KST, 기준 commit `746b7ca`**(1단계 명세·결정 기록 커밋).
rev 1.1은 사용자 결정 **D-AUTHN-005**(생성 시 절대 만료 시각)·**D-AUTHN-006**(payment-api 별도 connector — 기준 01 편차 승인)과 명세 검토(SPEC_FAIL: H-1~H-4,
m-1~m-10, n-1~n-8) 및 Coordinator 판정(U-3 만료 inline 판정, m-1 checkout handle 경로)을 반영했다(rev 1.1 sha256 `f911c9f7…0a08`). **rev 1.2**는 U-1·U-2 사용자 답변
**D-AUTHN-007**(V7 이전 REQUESTED 행 채우기 + CHECK)·**D-AUTHN-008**(PaymentExpired 이벤트)과 검토 n-6·n-8 원문(Coordinator 전달)을 반영한다 — §1·§2·§4.1(한 줄)·§5.1·§5.2·§5.3·§5.4·§7.1·
§7.5·§10.1·§14·§15·§19·§20·§21·§23만 바꿨다(rev 1.2 sha256 `90db4ab4…4d28`). **rev 1.3**은 재검토(SPEC_PASS_WITH_FIXES — m-A~m-E, n-a~n-h, Coordinator
판정)를 반영한다 — 처리 목록은 §24.5. 남은 사용자 미결 없음. 2단계 구현 편차는 §24에 쌓는다.

이 문서는 새 업무 정책을 채택하지 않는다. 표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용
(새 정책 아님), **판정(Coordinator)** = 결정을 적용하는 방식에 대한 Coordinator 판정, **구현 결정(AN-xx)** = 금융·보안 의미가 없는 가역적 선택(§21),
**미결(U-x)** = 사용자 답변 대기(§23), **Demo** = Simulator·시험 전용 값(운영값 아님), **범위 관찰** = 작업 경계 확인 사항(§22).

입력 문서(sha256 앞 8자…끝 4자, 2026-10-16 rev 1.3 작성 시점 작업 트리 — 결정 행만 rev 1.3에서 갱신):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001~013, **D-AUTHN-001~008**, D-CPD-001~004, D-CP-*, D-CPC-*, D-CPA-001, D-MD-008·009·014, D-MS-004, D-MS2-004·005) | D-AUTHN-001~006 확정(`746b7ca`), **D-AUTHN-007·008 확정(사용자 답변 2026-10-16, 작업 트리 — 미커밋)** | `1cbdf2be…b692` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 작업 | `harness/tasks.json` AUTHN-001 AC **11개**·allowedWritePaths·coordinatorDecisions, AUTH-001 AC(V9부터) | Coordinator 소유 | `7ffeda43…e1c4` |
| 질문 | `harness/questions.json` Q-AUTHN-01~06 | Coordinator 소유 | `ebae5ee2…b608` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§4.1·§4.3·§5.3·§7·§9·§18·§19) | 구현 반영 명세 | `563b0180…cefd` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9·§9.2·§10~§14) | 관례(`746b7ca`) | `eb8c6fe4…7f07` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례 | `4bb8712b…73fc` |
| CP계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 | `03eecede…ae83` |
| CP-API | `docs/repository/CP-API-001-CREATE-PAYMENT-API.md`(§4·§5·§7·§8) | 구현 반영 명세 | `4469e8e0…78e7` |
| MS1 | `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md`(§3.1·§3.5·§5) | 구현 반영 명세 | `bd761cb4…a554` |
| MS2 | `docs/repository/MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md`(§4·§5.2·§5.4) | 구현 반영 명세 | `b555e1fa…d458` |
| 하네스 | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md` §7 `52cff39a…250b`, `harness/commands.json` `6c698958…06f4`, `harness/scripts/cve-scan.py` `7f539329…b258` | 관례 | — |
| A01·A05·A08 | `docs/baseline/architecture/01-runtime…` `193f15dc…719f`, `05-security…` `e9dcabd0…b956`, `08-testing…`(Test Fixture Policy) `086f9300…9152` | 아키텍처 자료 | — |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md` §3·§4(D-AUTH-001로 채택) | 확정 | `3cf08d32…11be` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §5.5 | 확정 | `a8b02b77…7ae5` |
| 코드 | rev 1.0 표의 domain·persistence·web 파일(`273068a`와 `746b7ca` 사이 변경 없음) + `payment/domain/Payment.kt` `1f57b3d3…3d49`, `OrderPaymentControl.kt` `375462d5…267c`, `application/CreatePaymentService.kt` `a75e32e8…883e`, `V1__payment_core.sql`(payment 표) | `746b7ca` | — |

---

## 1. 범위와 범위 밖

| 구분 | 내용 | 근거 |
|---|---|---|
| 범위 | 가맹점 서버용 결제창 토큰 발급 API `POST /v1/payments/{paymentId}/checkout`(MID 인증) | **확정 D-AUTHN-001** |
| 범위 | 결제창(Thymeleaf, payment-api의 결제창 전용 connector·별도 SecurityFilterChain): 1회 토큰 교환 → handle별 결제창 쿠키 세션 + CSRF, 카드사·PaymentPlan 선택, 인증 시작, 인증 결과 복귀 처리, 취소, 가맹점 successUrl/failUrl redirect | **확정 D-AUTHN-001·D-AUTHN-006**, AC 6·10, 판정 m-1 |
| 범위 | `AuthenticationSession`·결제창 토큰·결제창 세션 영속화(PAYMENT V7/V8), `AuthenticationSessionRepository`, 표 단위 runtime 권한 | AC 7 |
| 범위 | MASTER Site WEB host를 권리 Snapshot(schemaVersion 3)으로 PAYMENT에 전파, successUrl/failUrl 검증(https + host 정확히 일치), 전파 없음/모호 → 토큰 발급 거절 | **확정 D-AUTHN-002**(master-api 변경 포함) |
| 범위 | 인증 결과 서버 간 조회(1회용 결과 참조 + state), 바인딩·유효기간 대조, 조회 실패 ≠ VERIFIED, 외부 호출은 DB TX 밖 | **확정 D-AUTHN-003** |
| 범위 | 새 실행 단위 `apps/institution-simulator`(별도 프로세스·포트·MySQL): 고객 인증 화면·등록/결과 조회 API·가상 카드 시나리오, payment-api HTTP Adapter, REAL Adapter 없음, 격리 시험, 하네스 모듈 등록 | **확정 D-AUTHN-004** |
| 범위 | Payment 생성 시 절대 결제창 만료 시각(`checkout_expires_at` = 생성 시각 + 버전 있는 결제 유효기간 설정, Demo) 저장, 만료 Use Case(`RequestedPaymentExpiry`)·주기 작업·control을 쥔 경로의 inline 만료·경합 시험(C7·C11~C15), 토큰·결제창 세션·인증 세션 만료를 Payment 만료로 자름, CreatePayment API·응답 불변 | **확정 D-AUTHN-005**, AC 9, 판정 U-3(§7.5) |
| 범위 | V7 이전 REQUESTED 행 결제창 시각 채우기(채우는 시점 활성 설정 버전, 애플리케이션 단계) + `CHECK(status <> 'REQUESTED' OR checkout_expires_at IS NOT NULL)`, 옛 볼륨 재실행 시험 | **확정 D-AUTHN-007**, AC 11(§7.5.6) |
| 범위 | REQUESTED → EXPIRED와 같은 TX의 PaymentExpired Outbox append(INTEGRATION_NON_FINANCIAL, effect_key `PaymentExpired:{paymentId}`) | **확정 D-AUTHN-008**, AC 12(§7.5.7) |
| 범위 밖 | confirm API·승인 시작·인증 근거 귀속(`bindTo`)·기관 승인 전송(가상 VAN TCP) | AUTH-001(AC 13) — 인계 §22-8 |
| 범위 밖 | Webhook, React 결제창, 실제 기관 인증 모듈·전문·인증 시험, 앱카드/ISP 앱 전환 복귀 | WEBHOOK-001, AC 13, 허용 §5.6 |
| 범위 밖 | 결제 조회 API(GetPayment), `payment-window` 실행 단위 분리 | CP-QUERY-001, 후속 후보(D-AUTHN-006) |
| 범위 밖 | 토큰·세션 행 보존기간·삭제 Job | 후속(tasks.json openItems) |

카드번호·CVC·인증 비밀번호는 어디에도 없다 — 가상 카드 참조만 쓴다(**확정** 허용 §5.5, 범위 §4). TTL·timeout·재시도·결제 유효기간 값은 버전 있는 설정이며 코드에
운영 기본값이 없고 시험·Simulator는 Demo 값만 쓴다(**확정 D-AUTH-009**, D-AUTHN-005).

---

## 2. AC 대응표(tasks.json AUTHN-001 → 명세 절 → 계획 시험)

| # | AC(요약) | 절 | 계획 시험(이름은 가역적) |
|---|---|---|---|
| 1 | (D-AUTH-001) 토큰 바인딩, 서버 검증(state/nonce), AuthenticationSession 영속화, 성공/실패/취소/만료·중복 callback·재사용, Simulator/REAL 격리, 카드정보 비노출 | §4, §7, §13, §17 | `AuthenticationResultCheckSpec`(2단계 판정표), `CardAuthenticationCompletionSpec`(실 MySQL·Simulator stub 시나리오 전부), `AuthenticationReuseSpec`(결과 참조·state·토큰 재사용), `AuthenticationBackendIsolationSpec`, `NoCardDataSchemaSpec` |
| 2 | (D-AUTHN-001) 토큰 API: 소유 MID·환경, REQUESTED만, 256bit·hash·짧은 만료·1회 교환, 쿠키 세션(HttpOnly·Secure·SameSite·ID 재발급)·CSRF, 결제 생성 API 불변 | §5, §6, §12 | `CheckoutTokenIssuanceSpec`, `CheckoutTokenApiSpec`(다른 MID 404·종료 409·형식 400), `CheckoutExchangeSpec`(재사용·만료·동시 교환 8), `CheckoutCookieSecuritySpec`, 기존 `CreatePaymentApiSpec` 응답 키 집합 불변 |
| 3 | (D-AUTHN-002) Site WEB host 전파(Inbox 같은 TX), https + host 정확히 일치, 전파 없음/모호 → 거절 | §8, §9 | `MerchantReturnUrlSpec`·`MerchantReturnUrlUriCrossCheckSpec`, MASTER `EntitlementSnapshotAssemblerSpec` v3·`SchemaPromotionV3Spec`, PAYMENT `EntitlementContentV3Spec`·`SiteWebHostQuerySpec`, golden v3(harness) |
| 4 | (D-AUTHN-003) 서버 간 조회로만 VERIFIED, state·바인딩·유효기간 대조, 조회 실패·timeout ≠ VERIFIED(재조회 가능 상태 유지로 확정 §7.3), 외부 호출 TX 밖, 중복 멱등·다른 증거 거부·늦은 결과 무시 | §7 | `AuthenticationResultCheckSpec`, `CardAuthenticationCompletionSpec`, `LookupOutsideTransactionSpec` |
| 5 | (D-AUTHN-004) `apps/institution-simulator`, 가상 카드 참조, 시나리오, issuer/acquirer/provider 구분, 결과 조회 API, HTTP Adapter만, REAL 없음, TEST 외 기동 거부, 하네스 등록 | §13, §18 | Simulator `AuthenticationTransactionSpec`·`SimulatorApiContractSpec`(golden)·`SimulatorRestartDurabilitySpec`·`SimulatorArchitectureSpec`, payment-api `AuthenticationBackendIsolationSpec`, harness `test_module_registry`·`test_authentication_contract_golden`·`test_simulator_isolation` |
| 6 | Thymeleaf 결제창: 카드사·Plan 선택(서버 판정), XSS·CSP·frame-ancestors 'none'·nosniff·Referrer-Policy, 오류 화면 내부 사유 비노출, 카드정보·Secret·토큰 원문 로그 금지 | §6, §12 | `CheckoutPageSecuritySpec`, `CheckoutTemplateScanSpec`(+ 위반 fixture), `CheckoutErrorPageLeakSpec`, `CheckoutLogRedactionSpec` |
| 7 | PAYMENT migration, 표 단위 권한(§9.2), 잠금 순서 CA-05 부분열, `AuthenticationSessionRepository` | §10, §11 | `CheckoutSchemaSpec`, `DatabaseAccountSeparationSpec` 고정 기대값 갱신(새 표 추가 — 완화 아님)·append-only 1142, `AuthenticationSessionRepositorySpec`, 경합 시험(§14) |
| 8 | 명세(1·2단계), TDD, Test Fixture Policy, 8대 원칙, VERIFY-GATE PASS·시험 1220 이상·harness 72 이상, DEPS·CVE | 이 문서, §15, §17, §18, §24 | VERIFY-GATE, GRADLE-DEPS-PAYMENT-API·MASTER-API·INSTITUTION-SIMULATOR, CVE-SCAN |
| 9 | (D-AUTHN-005) 생성 시 `checkout_expires_at` 저장(Demo 설정), 만료 Use Case·주기 작업·교환/복귀/토큰 발급과 만료 경합(C7, 실 MySQL), 토큰·세션 만료 자르기, CreatePayment API·응답 불변 | §7.5, §10, §14 | `RequestedPaymentExpirySpec` 추가 행(`expireIfElapsed`), `PaymentCheckoutWindowSpec`, `CreatePaymentCheckoutWindowSpec`(값 저장·재사용 시 연장 없음·응답 키 불변), `RequestedPaymentExpiryServiceSpec`(실 MySQL, NoChange·키셋·장애 주입 rollback), `CheckoutExpiryConcurrencySpec`(C7·C11~C15), `CreatePaymentInlineExpirySpec` |
| 10 | (D-AUTHN-006) 결제창 전용 connector·별도 SecurityFilterChain, 화면 코드는 provided Use Case만 호출(ArchUnit), 기준 01 편차 기록 | §11.1, §12.1, §17.3, §22-1 | `CheckoutConnectorIsolationSpec`, ArchUnit `checkoutWebUsesOnlyProvidedUseCases`(+ 위반 fixture) |
| 11 | (D-AUTHN-007) V7 이전 REQUESTED 행 시각 채우기(애플리케이션 단계·버전 기록, migration 상수 금지), 이후 CHECK, 옛 볼륨 재실행 시험 | §7.5.6, §10.1 | `CheckoutWindowBackfillSpec`(옛 볼륨: V1~V6 schema 행 → V7·V8 → V8_1 가드 거부 → 채우기·만료 → V8_1 성공 → NULL REQUESTED INSERT 3819), `PaymentCheckoutWindowSpec`(`assignMissingCheckoutWindow`) |
| 12 | (D-AUTHN-008) REQUESTED → EXPIRED와 같은 TX에서 PaymentExpired append, 중복 만료 1건, rollback 0건 | §7.5.7, §14 C16 | `PaymentExpiredEnvelopeSpec`, `CheckoutExpiryConcurrencySpec` C16, `RequestedPaymentExpiryServiceSpec`(장애 주입 0건) |
| 13 | 범위 밖 목록 | §1 | — |

---

## 3. 현황 조사(2026-10-16, `273068a` — `746b7ca`에서 코드 변경 없음)

| 영역 | 있는 것 | 없는 것(이 작업이 정의) |
|---|---|---|
| domain `payment` | `AuthenticationSession`(5상태, `create`·`start()`·`verify`·`fail`·`expire`·`bindingRejection`·`bindTo`·`reconstitute`), `AuthenticationBinding`, `VerifiedAuthentication`, `RequestedPaymentExpiry`, `PaymentPlanRule.check`, `AuthenticationSessionId`; `Payment`(만료 시각 없음), `OrderPaymentControl.decide`(REQUESTED → `UseExistingPayment`) | 상관 값, 만료 원인 저장, 대체, 결과 판정 domain 서비스, 반환 URL 값, Plan 사전 판정, **Payment 결제창 시각(`CheckoutWindow`)·`expireIfElapsed`** |
| application `payment` | `CreatePaymentService`·`LocalTransactions`(TX 소유, 교착 재시도 3회)·`PersistenceFailure`, `MerchantAuthenticator` | 토큰·결제창·인증·만료 Use Case, `CardAuthenticationPort`, 저장 Port 4개, 만료 후보 Reader |
| web `payment` | 공개 포트 Bearer 체인(stateless, CSRF 끔), 내부 connector 격리(`ApiEntryFilter`), 엄격 JSON 파서, `ApiErrorCode` | checkout connector·브라우저 보안 체인, Thymeleaf(payment-api 미의존) |
| persistence(PAYMENT V1~V6) | payment·control·hold·idempotency·outbox·entitlement 투영·credential 투영 | `checkout_token`·`checkout_session`·`authentication_session`·`authentication_session_event`, `payment.checkout_expires_at` |
| 권리 Snapshot | schemaVersion 1·2(MS2), 해석기는 모르는/빠진 필드 거절, 스키마 승격 revision +1. **Site 채널 식별값은 MS1 §3.1에서 의도적으로 제외** | `sites[].channel`(WEB host) — D-AUTHN-002가 MS1 제외를 대체 |
| MASTER | `merchant_site(channel_type, channel_identifier)`, `SiteChannel`(WEB = 소문자 host, IP 리터럴 거절), 채널 불변, 활성 채널 전역 UNIQUE | 조립·직렬화에 채널 포함 |
| 실행 단위 | master-api·payment-api·financial-batch·internal-admin | `apps/institution-simulator` |
| 하네스 | `test_module_registry`, GRADLE-CHECK minTests 1103(마지막 gate 실측 1220), SELFTEST 72 | 5번째 실행 단위 등록, 인증 계약 golden, Simulator 의존 격리 검사 |

발견한 제약(rev 1.1 처리 결과 포함):

1. **기준 01 편차**: A01은 `payment-window`를 별도 실행 단위로 둔다. → **D-AUTHN-006으로 승인된 편차**: payment-api 결제창 전용 connector·별도 SecurityFilterChain, 화면 코드는
   provided Use Case만 호출(ArchUnit 강제, §17.3), 실행 단위 분리는 후속 후보(§22-1).
2. **Payment에 결제창 만료 시각이 없다** → **D-AUTHN-005**: 생성 시 절대 시각 고정 저장(§7.5). 기존 domain에서 `OrderPaymentControl.decide`는 REQUESTED를 무조건
   `UseExistingPayment`로 돌려 시간이 지난 REQUESTED가 주문을 계속 막는다 → control을 쥔 경로가 inline 만료 후 판정한다(판정 U-3, §7.5.3).
3. **환경 축**: "TEST 외 환경" = Payment `environment` 축(LIVE) — **Coordinator 승인**(§13.6).
4. `start()`에 기관 거래 참조 자리가 없고 `expire(cause)`는 원인을 저장하지 않는다 → §7.1.
5. `create`는 비종료 세션만 막아 VERIFIED 미귀속 세션이 여러 개 생길 수 있다 → §7.2 대체(AN-07, Coordinator 유지 판정).
6. PAYMENT V7/V8은 이 작업, AUTH-001은 V9부터(**Coordinator 기록**, AUTH-001 AC). MS2 §8.2 문구 갱신(§22-4).
7. 기존 `reconstitute`는 "VERIFIED ⇔ 증거"라 대체·만료된 VERIFIED 세션(증거를 가진 EXPIRED)을 복원할 수 없다 → 단방향 규칙으로 바꾼다(§7.1, §24.1 — 의도된 불변식 변경).

---

## 4. 종단 흐름

### 4.1 단계와 TX 경계

```text
[가맹점 서버] POST /v1/payments (기존 API·응답 불변) → Payment REQUESTED(checkout_expires_at·유효기간 설정 버전 저장), paymentKey
[가맹점 서버] POST /v1/payments/{paymentId}/checkout  (Bearer, {successUrl, failUrl})
  TX-I  head(S) → control(X) → payment(X) → [만료 inline: authentication_session → checkout_session → checkout_token] → checkout_token(X)
        소유 MID·환경, now ≥ checkout_expires_at이면 inline 만료 후 409, REQUESTED·hold 없음, Site WEB host(Snapshot v3), URL 검증,
        미교환 토큰 SUPERSEDED, 새 토큰(digest만, expires_at = min(now+TTL, checkout_expires_at)) INSERT       ── 커밋
  응답  201 {paymentId, checkoutUrl(=<설정 checkout origin>/checkout?token=<원문>), expiresAt}  (원문은 응답에만)
[브라우저]   GET /checkout?token=…   (checkout connector)
  TX-X  payment(X) → checkout_session(X) → checkout_token(X)
        토큰 digest → ISSUED·미만료·바인딩·Payment REQUESTED·결제창 시각 안 → 같은 Payment ACTIVE 세션 REVOKED
        → 새 checkout_session(handle 128bit, 쿠키 비밀 256bit digest만, expires_at = min(now+TTL, checkout_expires_at)) → 토큰 CONSUMED ── 커밋
  응답  303 /checkout/s/{handle}  + Set-Cookie __Host-pc_checkout_<handle>=<세션 비밀>; Max-Age=<남은 수명>
[브라우저]   GET /checkout/s/{handle} → 결제창(금액·통화·주문명·Site host·카드사·Plan·CSRF)   (조회: jOOQ, 비권위)
[브라우저]   POST /checkout/s/{handle}/authentication {cardCompany, plan, _csrf}
  서버 판정: 카드사 allowlist, Plan 사전 판정(Demo 정책), Payment 환경의 인증 backend 존재
  TX-A  payment(X) → authentication_session(X) (→ checkout_session 갱신은 만료 기록 때만)
        Payment REQUESTED·결제창 시각 안, 세션 expiresAt = min(now+TTL, checkout_session.expires_at) > now
        같은 Payment 미폐기·미귀속 세션 EXPIRED(SUPERSEDED/TTL_ELAPSED) → 새 세션 CREATED(바인딩·state digest)            ── 커밋
  TX 밖 CardAuthenticationPort.register(바인딩, state 원문, returnUrl=<checkout origin>/checkout/s/{handle}/authentication/return)
  TX-B  payment(X) → authentication_session(X): CREATED → IN_PROGRESS(기관 거래 참조) — 다른 상태면 변경 없음(§7.4.1)              ── 커밋
  응답  303 <설정 Simulator 고객 origin>/sim/authenticate/{id}
[Simulator]  가상 카드 선택 → 인증/실패/취소 → 303 <returnUrl>?resultRef=…&state=…
[브라우저]   GET /checkout/s/{handle}/authentication/return?resultRef=…&state=…  (그 handle의 쿠키 필수)
  검사  query 고정 문법 → 쿠키 → checkout_session(handle 일치, ACTIVE 또는 VERIFIED로 COMPLETED, 미만료)
        → S = 그 checkout_session의 세션 중 state_digest = digest(URL state)  (없음 → 400, 보안 로그)
  1단계 (외부 호출 없음) S 종료 → 410 / S VERIFIED → DUPLICATE 303 또는 R0′ 400 / S CREATED → 400 / TTL 경과 → TX EXPIRED 410 /
        Payment 종료·결제창 시각 경과 → TX EXPIRED(PAYMENT_CLOSED) 410
  TX 밖 CardAuthenticationPort.lookup(resultRef) → 결과(또는 Unverifiable)
  TX-C  payment(X) → authentication_session(X) → checkout_session(X): 1단계 재판정 → 2단계 판정(R3·R6·R5·R7~R11) + 이벤트       ── 커밋
  응답  VERIFIED → 303 successUrl?paymentId=…&authenticationRef=…   (checkout_session COMPLETED(VERIFIED_REDIRECT))
        FAILED   → 200 실패 화면(같은 결제창에서 다시 시도 / failUrl?paymentId=…&code=…)
        Unverifiable → 503 "확인 중" 화면(같은 복귀 URL로 재시도 링크)
[주기 작업]  결제창 시각이 지난 REQUESTED 후보(jOOQ) → 건마다 TX control(X) → payment(X) → authentication_session(X) → checkout_session(X)
             → checkout_token(X): RequestedPaymentExpiry → Payment EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED), 세션·결제창 세션·토큰 닫음  ── 커밋
[가맹점 서버] confirm(AUTH-001 범위 — 먼저 결제창 시각 경과 확인 → inline 만료 → PAYMENT_TERMINATED, §22-8)
```

- **확정**: 외부 호출(등록·조회)은 DB TX 밖이고 잠금을 쥐지 않는다(CLAUDE.md, AC1 §4.1, D-AUTHN-003). 업무 효과·요약·이벤트는 PAYMENT 같은 로컬 TX다.
- **확정**: 인증 성공 ≠ 승인 성공. 이 흐름에서 Payment는 REQUESTED를 유지한다(범위 §3-5, AC1 §4.1). 인증 흐름에는 Outbox 이벤트가 없다(AC1 §11 "인증 세션 전이는 내부
  상태"). Payment 만료는 PaymentExpired(INTEGRATION_NON_FINANCIAL)를 같은 TX에 append한다(D-AUTHN-008, §7.5.7).
- 브라우저 successUrl 표시만 신뢰하지 않는다: 가맹점은 confirm에서 서버가 다시 바인딩을 대조한다(AC1 §4.3, AUTH-001).
- 모든 origin(checkoutUrl, PG returnUrl, Simulator 고객 URL, CSP form-action)은 **설정에서만** 만들고 기동 시 검증한다(§12.1). 요청의 `Host`·`X-Forwarded-*`를
  쓰지 않는다(m-9).
### 4.2 TX를 둘로 나눈 이유(TX-A/TX-B, 조회 앞뒤)

- 등록 호출이 실패·timeout이면 세션은 CREATED로 남고 브라우저는 Simulator로 가지 않는다(오류 화면 → 다시 시도 = 새 세션이 대체). 기관 쪽에 브라우저 없는
  등록이 남아도 그 거래는 기관 TTL로 만료될 뿐 금융 효과가 없다. 등록 timeout을 "등록 안 됨"으로 가정하지 않지만, 같은 state로 재등록하지 않고(새 state)
  기관 등록은 (client, state) 멱등이라 중복 등록도 무해하다(§13.4).
- 조회 결과를 받은 뒤 TX-C가 실패(DB 장애·commit 불명확)하면 세션은 IN_PROGRESS로 남는다. 조회는 읽기 전용·멱등(§13.4)이라 같은 복귀 URL 재요청이 같은
  결과로 수렴한다(Recoverability).

---

## 5. 결제창 토큰 발급 API

### 5.1 요청

| 항목 | 규칙 | 근거 |
|---|---|---|
| 경로 | `POST /v1/payments/{paymentId}/checkout`, 공개 API 포트만 | **확정 D-AUTHN-001**, CP-API §8 connector 관례 |
| 인증 | 기존 Bearer 체인(`MerchantBearerAuthenticationFilter`) — MID·환경은 Credential에서 결정 | 확정 D-CPA-001, CP계약 §2 |
| `paymentId` 경로 값 | 1..64자 출력 가능 ASCII가 아니면 404 `PAYMENT_NOT_FOUND`(형식 오류도 존재 비노출) | 구현 결정 AN-01 |
| 헤더 | `Content-Type`·`Accept`·본문 16 KiB 규칙은 CP-API §4.1 그대로. **`Idempotency-Key`는 받지 않는다** — 있으면 400 `INVALID_REQUEST`(field `Idempotency-Key`). 모든 요청에 멱등키를 붙이는 범용 가맹점 client는 이 API에서 헤더를 빼야 한다(검토 n-8 — 가역적, **Coordinator 판정: 400 유지**, 엄격 파서 관례와 일관, API 문서에 명시) | §5.4, AN-02 |
| 본문 | 엄격 JSON(CP-API §4.2 방식), 닫힌 필드 2개: `successUrl`, `failUrl`(둘 다 필수 문자열, null 금지) | AN-03 |
| 필드 검증 | 각 URL은 §8.1 문법(형식 위반 → 400 `INVALID_RETURN_URL` + field). host 일치는 §8.2(불일치 → 422 `RETURN_URL_NOT_ALLOWED`) | **확정 D-AUTHN-002** |

### 5.2 판정 순서(TX-I, 앞 단계가 결정하면 멈춤)

1. 형식 검사(400) — TX 전.
2. TX-I 시작. Payment 존재 + `environment`·`merchant_account_id` = 인증 주체(아니면 404 — 다른 MID·환경은 존재 비노출, A05 "소유 MID 검증").
3. 인증 backend: Payment 환경에 설정된 인증 backend가 없으면(현재 LIVE) 422 `CHECKOUT_NOT_SUPPORTED`(§13.6).
4. Site WEB host: `SiteWebHostPort.resolve(mid, siteId, now)`(§9.4) — Unavailable → 503 `CHECKOUT_UNAVAILABLE`; host 없음(APP 채널·종료 Site·Snapshot에 Site 없음) →
   422 `RETURN_URL_NOT_ALLOWED`(fail-closed, D-AUTHN-002).
5. 잠금: control(X) → payment(X).
   - **결제창 시각 경과 inline 만료(판정 U-3, §7.5.3)**: Payment REQUESTED이고 `now ≥ checkout_expires_at`이면 같은 TX에서 `RequestedPaymentExpiry`(세션·결제창
     세션·토큰 닫기·PaymentExpired 포함)를 실행·커밋하고 409 `PAYMENT_TERMINATED`. 결제창 시각이 없는 REQUESTED는 V8_1 뒤 존재할 수 없다(기동 전략이 채운 뒤
     V8_1 적용 — §7.5.6, 검토 #2 m-2). 만나면 `NO_WINDOW` 결함(rollback·500).
   - incident hold 활성 → 409 `ORDER_ON_HOLD`(**도출**: hold 중 승인 시작 불가 D-CPD-004·AC1 §4.2, fail-early).
   - Payment FAILED·EXPIRED → 409 `PAYMENT_TERMINATED`, AUTHORIZING·UNKNOWN·AUTHORIZED → 409 `PAYMENT_AUTHORIZATION_STARTED`.
6. URL host = Site host(§8.2), 아니면 422 `RETURN_URL_NOT_ALLOWED`.
7. 같은 Payment의 ISSUED 토큰 → SUPERSEDED(만료 지났으면 EXPIRED) — 미교환 토큰은 Payment당 최대 1개(AN-04).
8. 새 토큰: 256bit 난수 원문(`ckt_` + base64url 43자), DB에는 `cth1:` + SHA-256 hex만. 바인딩(Payment·OrderKey·Site·금액·통화)·successUrl·failUrl·Site host·Snapshot 버전·
   발급 Credential·정책 버전·`expires_at = min(now + checkout.token-ttl, checkout_expires_at)`(D-AUTHN-005 자르기 — 5단계에서 `now < checkout_expires_at`가 보장돼
   `expires_at > now`) 저장.
9. 커밋 → 201.

### 5.3 응답과 오류

```json
HTTP/1.1 201 Created
Cache-Control: no-store
PayCore-Request-Id: 6f1c…
{"paymentId":"pay_…","checkoutUrl":"https://checkout.example.test/checkout?token=ckt_…","expiresAt":"2026-10-16T01:02:03.456Z"}
```

| 경우 | HTTP | code | retryable | Retry-After |
|---|---|---|---|---|
| 형식(본문·헤더·Idempotency-Key 있음) | 400 | `INVALID_REQUEST` | false | 없음 |
| URL 문법 위반 | 400 | `INVALID_RETURN_URL`(field) | false | 없음 |
| 인증 없음·폐기 | 401 | `UNAUTHENTICATED` | false | 없음 |
| Payment 없음·다른 MID·다른 환경·경로 형식 오류 | 404 | `PAYMENT_NOT_FOUND` | false | 없음 |
| hold | 409 | `ORDER_ON_HOLD` | false | 없음 |
| FAILED·EXPIRED, 결제창 시각 경과(inline 만료) | 409 | `PAYMENT_TERMINATED` | false | 없음 |
| AUTHORIZING·UNKNOWN·AUTHORIZED | 409 | `PAYMENT_AUTHORIZATION_STARTED` | false | 없음 |
| host 불일치·host 없음 | 422 | `RETURN_URL_NOT_ALLOWED` | false | 없음 |
| 인증 backend 없음(LIVE) | 422 | `CHECKOUT_NOT_SUPPORTED` | false | 없음 |
| Snapshot 판정 불가·DB 일시 오류·교착 재시도 소진·commit 불명확 | 503 | `CHECKOUT_UNAVAILABLE` | true | 있음 |
| 결함 | 500 | `INTERNAL_ERROR` | false | 없음 |

- 새 code 6개는 기존 `ApiErrorCode` 고정 문구 관례를 따른다. rev 1.0의 `CHECKOUT_NOT_AVAILABLE`(422)은 503 `CHECKOUT_UNAVAILABLE`과 혼동되어
  `CHECKOUT_NOT_SUPPORTED`로 바꿨다(n-3). 응답에 URL·host·Snapshot 버전·내부 사유를 echo하지 않는다(CP-API §7.5). 구현 결정 AN-05.
- commit 불명확(503)이어도 안전하다: 토큰이 저장됐더라도 원문이 전달되지 않았고, 재요청이 새 토큰을 내며 이전 것을 SUPERSEDED한다.
- `checkoutUrl`의 origin은 설정 `paycore.checkout.public-base-url`에서만 온다(§12.1).

### 5.4 멱등성 판단(왜 멱등키 재생이 아닌가)

- CLAUDE.md·D-CP-003의 "같은 요청키·같은 내용 = 과거 결과"는 **결과를 다시 줄 수 있는 요청**의 규칙이다. 이 API의 결과는 비밀(토큰 원문)이고 D-AUTHN-001이
  "DB에는 hash만"을 정했으므로 과거 결과(원문)를 재생할 수 없다. 원문을 저장해 재생하면 D-AUTHN-001 위반이다.
- 이 API는 금융 효과가 없다: 주문 슬롯·한도를 바꾸지 않고, Payment·Outbox는 결제창 시각이 지났을 때의 inline 만료(§7.5.3 — Payment당 한 번, PaymentExpired 1건)와
  V7 이전 행 시각 채우기(한 번)에서만 바뀐다. 반복 호출의 효과는 "유효 토큰이 최신 것 하나"(또는 이미 만료된 Payment)로 수렴한다(§5.2-7) — 같은 요청을 N번 보내도
  남는 상태는 1번과 같다(효과 멱등). 그래서 멱등키를 받지 않고(AN-02), 재시도 안내는 "그냥 다시 호출"이다.
- 다른 키·같은 주문 재사용 규칙(활성 시도 재사용)은 이 API와 무관하다(새 Payment를 만들지 않는다).

---

## 6. 결제창 endpoint(checkout connector)

### 6.1 경로(판정 m-1: 결제창 handle을 경로에 둔다)

| 방법·경로 | 동작 | 성공 응답 |
|---|---|---|
| `GET /checkout?token=…` | 토큰 교환(TX-X) | 303 `/checkout/s/{handle}` + `Set-Cookie: __Host-pc_checkout_<handle>` |
| `GET /checkout/s/{handle}` | 결제창 화면(그 handle 쿠키 필수) | 200 HTML |
| `POST /checkout/s/{handle}/authentication` | 카드사·Plan 제출 → 인증 시작(TX-A·등록·TX-B) | 303 Simulator 고객 URL |
| `GET /checkout/s/{handle}/authentication/return?resultRef=…&state=…` | 결과 처리(검사·1단계·조회·TX-C) | 303 successUrl / 200 실패 화면 / 503 확인 중 / 410 |
| `POST /checkout/s/{handle}/cancel` | 결제창 취소: 비종료 인증 세션 FAILED(USER_CANCELLED), checkout_session COMPLETED(CANCELLED) | 303 failUrl?…&code=USER_CANCELLED |
| `GET /checkout/assets/**` | 정적 CSS(JS 없음) | 200 |

- **handle**: 결제창 세션마다 서버가 만드는 128bit 난수 base64url 22자(`^[A-Za-z0-9_-]{22}$`, `checkout_session.handle` UNIQUE). 자격 증명이 아니라 경로 식별자다 —
  접근 통제는 handle과 짝인 쿠키 `__Host-pc_checkout_<handle>`(256bit 비밀, digest만 저장)가 한다. 한 브라우저가 여러 Payment의 결제창을 동시에 열어도 쿠키가
  섞이지 않는다(m-1). 같은 Payment의 새 교환은 이전 ACTIVE 결제창 세션을 REVOKED한다(Payment당 ACTIVE 1).
- 경로 handle이 문법에 맞지 않으면 쿠키·DB를 보지 않고 400 화면. 서버가 만드는 URL(redirect·form action·returnUrl)은 handle을 경로 변수 encode로 넣는다(m-3).
- POST는 CSRF 필수(§12.3). 쿼리·폼 필드는 닫힌 집합(모르는 이름 → 400 화면).
- `GET`인 복귀 endpoint가 상태를 바꾸는 이유: Simulator가 top-level GET redirect로 돌아오고 SameSite=Lax 쿠키는 이 경우에만 전송된다(§12.2). 위조 GET은
  state(256bit) 일치 없이는 어떤 세션도 고르지 못한다(§7.3).
- 토큰 교환이 GET인 이유와 한계: D-AUTHN-001이 `/checkout?token=…` 진입을 정했다. prefetch 소비 위험은 §20 R4(AN-06).

### 6.2 화면 내용·서버 판정

- 표시: 주문명(`th:text`), 금액(통화 minor unit에 맞춘 표시 전용 서식), 통화, Site host, 카드사 목록(인증 프로파일 allowlist, §13.6), Plan 목록(일시불 +
  정책 할부 개월 중 금액 조건을 만족하는 것). 표시 값은 jOOQ `CheckoutViewReader`(비권위)에서 온다 — 판정은 POST 처리의 잠금 TX에서 다시 한다.
- 제출 판정(서버): `cardCompany ∈ allowlist`, Plan은 `PaymentPlanRule.precheck(plan, money, policy)`(cardType 없이 개월·최소금액만 — §7.1). 체크카드 할부 거절은
  cardType을 아는 confirm(AUTH-001)과 기관이 한다(**확정** AC1 §5.4, 범위 §2). 클라이언트 선택 값만으로 허용하지 않는다(A05).
- 카드사 선택 값은 **발급사 코드**다. 검증 결과의 issuerCode와 대조하고(§7.3 R7) 승인 시작의 카드사 개통 판정 키(매입사 코드)는 서버 검증값을 쓴다
  (**도출** D-MS2-004 "발급사 지원 여부는 기관 프로파일 allowlist, 결제창 선택값은 서버 검증값과 대조만").

### 6.3 화면 오류 매핑(브라우저용 — 고정 문구, 내부 사유 비노출)

| 경우 | HTTP | 화면 | 상태 변화 |
|---|---|---|---|
| 토큰 없음·형식 오류·없음·사용됨·대체됨·만료·닫힘 | 400 | "결제창을 열 수 없습니다. 가맹점에서 다시 시도해 주세요." (사유 구분 없음 — 토큰 열거 방지) | 없음(사용된 토큰 재사용은 보안 로그·지표) |
| 교환: Payment가 REQUESTED 아님 / 결제창 시각 경과 | 409 | "결제를 진행할 수 없습니다." + failUrl(code=PAYMENT_NOT_AVAILABLE / CHECKOUT_EXPIRED) 링크 | 토큰 CLOSED, 결제창 세션 만들지 않음, Payment 불변(§7.5.3) |
| handle 형식 오류(쿠키·DB를 보지 않음 — §6.1, Coordinator 판정 2026-10-16) | 400 | "결제창 세션이 만료되었습니다." | 없음 |
| 쿠키 없음·세션 없음·REVOKED·EXPIRED·만료 시각 경과 | 401 | "결제창 세션이 만료되었습니다." | 쓰기 경로면 만료 시각 경과 세션 EXPIRED(TTL_ELAPSED), 읽기 경로는 없음 |
| 쿠키가 맞는 결제창 세션이 Payment 쪽 사유로 이미 닫힘(EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE) — 화면·인증 시작·복귀·취소, 검토 #4 m-1·후속 판정 2026-10-17. 인증 시작은 기관 등록을 호출하지 않고, 취소는 인증 세션을 FAILED(USER_CANCELLED)로 바꾸지 않으며 취소 이벤트를 쓰지 않는다 — §24.14.1) | 410 / 409 | Payment가 REQUESTED(시각 경과)·EXPIRED면 410 "결제 시간이 지났습니다." + failUrl(code=CHECKOUT_EXPIRED), 그 밖은 409 "결제를 진행할 수 없습니다." + failUrl(code=PAYMENT_NOT_AVAILABLE) | 없음(이미 닫힘) |
| CSRF 불일치 | 403 | "요청을 처리할 수 없습니다." | 없음 |
| Payment가 REQUESTED 아님(화면·시작·취소) | 409 | "결제를 진행할 수 없습니다." + failUrl(code=PAYMENT_NOT_AVAILABLE) 링크 | 결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE) |
| 결제창 시각 경과(Payment 아직 REQUESTED — 시작·취소·TX-B·복귀) | 410 | "결제 시간이 지났습니다." + failUrl(code=CHECKOUT_EXPIRED) 링크 | Payment 불변(control 미보유 경로 — §7.5.3), 자기 인증 세션 EXPIRED(PAYMENT_CLOSED)·결제창 세션 EXPIRED(PAYMENT_CLOSED) |
| 인증 세션 만료 시각을 만들 수 없음(결제창 세션 남은 시간 없음) | 410 | "결제창 시간이 지났습니다." | 결제창 세션 EXPIRED(TTL_ELAPSED) |
| 카드사·Plan 불허 | 422 | 같은 화면 + 고정 안내 | 없음 |
| 인증기관 등록 실패·timeout | 503 | "인증기관 연결에 실패했습니다. 다시 시도해 주세요." | 세션 CREATED 유지 + REGISTRATION_FAILED 이벤트(§7.4.1) |
| TX-B: 다른 창의 시작으로 세션이 대체됨 | 409 | "다른 창에서 인증을 시작했습니다." | 없음(§7.4.1) |
| 복귀: query 형식 오류·state에 맞는 세션 없음 | 400 | "인증 결과를 확인할 수 없습니다." | 없음(보안 로그·지표, 행 없음 — 세션이 없다) |
| 복귀: 세션 CREATED(등록 미완료) | 400 | 같음 | 없음(REJECTED_RESULT(SESSION_NOT_STARTED) 1회) |
| 복귀: VERIFIED + 다른 결과 참조(R0′) | 400 | 같음 | 없음(REJECTED_RESULT(EVIDENCE_CONFLICT) 1회, 보안) |
| 복귀: VERIFIED + 같은 결과 참조(DUPLICATE) | 303 | 저장된 successUrl(같은 authenticationRef) | 없음(DUPLICATE_RESULT 1회) |
| 복귀: 조회 Unverifiable(timeout·연결·5xx·형식 위반·**HTTP 404**) | 503 + Retry-After | "인증 결과를 확인 중입니다." + 같은 URL 재시도 링크 | 세션 IN_PROGRESS 유지(LOOKUP_UNVERIFIABLE 1회) |
| 복귀: FAILED(인증 실패·사용자 취소·불일치·기관 만료) | 200 | "인증이 완료되지 않았습니다." + 다시 시도 / failUrl(code=AUTHENTICATION_FAILED 또는 USER_CANCELLED) | 세션 FAILED |
| 복귀: 세션 이미 FAILED·EXPIRED, 또는 TTL 경과 | 410 | "인증 시간이 지났습니다." + 다시 시도 | TTL 경과면 EXPIRED(TTL_ELAPSED), 이미 종료면 없음(IGNORED_LATE_RESULT 1회) |
| 결함 | 500 | "일시적인 오류입니다." | rollback |

- failUrl query: `paymentId`, `code` ∈ {`USER_CANCELLED`, `AUTHENTICATION_FAILED`, `CHECKOUT_EXPIRED`, `PAYMENT_NOT_AVAILABLE`}(AN-08). successUrl query: `paymentId`,
  `authenticationRef`(**확정** AC1 §4.1). 기존 query는 보존하고 `&`로 덧붙이며 값은 percent-encoding한다(§8.3).
- 인증 실패 뒤 같은 결제창에서 다시 시도할 수 있다(**확정** AC1 §3·§5.3). 그래서 실패는 자동 failUrl redirect가 아니라 선택 화면이다(AN-08).
- 조회 HTTP 404는 rev 1.0 §6.3(400)과 §7.3(R3)이 엇갈렸다 → **R3(상태 불변·503 재시도)로 통일**(m-5, AN-09): state로 세션을 이미 골랐으므로 404는 기관 쪽 일시 불일치이거나
  잘못된 결과 참조이며, 어느 쪽이든 세션을 실패시키지 않는다. 계속 404면 세션 TTL에서 R4로 끝난다.

## 7. AuthenticationSession 규칙 적용·결제창 만료

### 7.1 domain 보강(순수 Kotlin, 기존 시험 기대값 완화 없음)

| 변경 | 내용 | 근거 |
|---|---|---|
| 상관 값 | `AuthenticationCorrelation(stateDigest)`를 생성 시 고정, `start(providerTransactionRef)`가 기관 거래 참조를 고정(CREATED → IN_PROGRESS) | D-AUTHN-003 state, AC1 §4.3. 기존 `start()` 서명 변경 → 시험 호출부 갱신(기대값 동일) |
| 만료 원인 저장 | `expiryCause` 속성, 원인에 `SUPERSEDED` 추가 | Auditability, §7.2 |
| 실패 사유 | `AuthenticationFailure.PROVIDER_EXPIRED` 추가 | R5. 세션 TTL 만료(EXPIRED)와 구분 |
| 근거 유효기간 | `VerifiedAuthentication.providerValidUntil`, `bindingRejection`은 `now < min(expiresAt, providerValidUntil)`일 때만 사용 가능 | **도출**: D-AUTHN-003 "유효기간 대조" + 범위 §3-4. AUTH-001 confirm이 그대로 쓴다 |
| 결과 영수증 | VERIFIED 때 `resultRefDigest`·`authenticationRef`·`verifiedAt` 저장, 기관이 확정한 FAILED는 `resultRefDigest` 저장(1회용 소비 기록) | §7.3 결과 참조 1회 |
| **불변식(단방향, H-4)** | VERIFIED ⇒ 증거·authenticationRef·resultRefDigest·verifiedAt / 증거·authenticationRef ⇒ VERIFIED 또는 EXPIRED / resultRefDigest ⇒ VERIFIED·FAILED·EXPIRED / failure ⇔ FAILED / expiryCause ⇔ EXPIRED / 귀속 ⇒ VERIFIED / IN_PROGRESS·VERIFIED ⇒ providerTransactionRef, CREATED ⇒ providerTransactionRef 없음. `expire`는 VERIFIED 세션의 증거를 지우지 않는다(감사 보존) | rev 1.0의 "VERIFIED ⇔ 증거"는 대체·만료된 VERIFIED 세션을 저장할 수 없었다(H-4). **의도된 불변식 변경**(완화 아님 — §24.1) |
| 기존 `verify` 만료 분기(n-c) | `verify`가 `now ≥ expiresAt`로 EXPIRED를 만들 때 `expiryCause = TTL_ELAPSED`를 기록한다(기존 코드는 원인 없음 — expiryCause ⇔ EXPIRED 불변식). `verify`는 `AuthenticationResultCheck` R11의 저수준 전이로만 쓴다(main 호출 제한은 ArchUnit `authenticationTransitionsOnlyInApplicationOrDomain`) | 기존 시험 기대값(EXPIRED) 동일, 원인만 추가 |
| 인증 시작 domain 서비스 | `AuthenticationSessionStart.start(payment, sessionsOfPayment, id, binding, correlation, expiresAt, now)`: 모든 검사 → 미폐기·미귀속 세션 EXPIRED(SUPERSEDED, 만료 지났으면 TTL_ELAPSED) → `create` | 관례 §12. ArchUnit: main의 `AuthenticationSession.create` 호출은 이 서비스만 |
| 결과 판정 domain 서비스 | `AuthenticationResultCheck.beforeLookup(session, payment, presented, now)`·`afterLookup(session, payment, presented, lookup, authenticationRef, now)` → §7.3 두 단계 판정(afterLookup은 1단계를 먼저 다시 판정) | 판정 규칙을 Spring 없이 표로 시험 |
| Plan 사전 판정 | `PaymentPlanRule.precheck(plan, money, policy)` — 개월·최소금액만 | AC 6 |
| 반환 URL | `MerchantReturnUrl`(§8) | D-AUTHN-002 |
| digest | `SecretDigest.of(kind, raw)` = 종류 접두 + SHA-256 hex(JDK `MessageDigest`), 비교는 `MessageDigest.isEqual`, `toString`은 접두만 | AN-22 |
| **결제창 시각** | `Payment.checkoutWindow: CheckoutWindow(expiresAt, validityVersion)?` — `request`는 필수, `reconstitute`만 NULL 허용(V7 이전 행 — D-AUTHN-007로 채운다). `isCheckoutWindowElapsed(now)` = 시각 있음 ∧ `now ≥ expiresAt`. ~~`assignMissingCheckoutWindow(window)`~~(S11에서 제거 — 채우기는 기동 전략 JDBC 한 곳, §7.5.6·§24.13). `RequestedPaymentExpiry.expireIfElapsed(payment, sessions, now)` → `EXPIRED`/`NOT_DUE`/`NOT_REQUESTED`/`NO_WINDOW`(결함 방어, 기존 `expire`를 부른다) | **확정 D-AUTHN-005·007**, §7.5 |

### 7.2 같은 Payment의 세션 수

- **확정**: 비종료(CREATED·IN_PROGRESS) 세션은 Payment당 하나, 귀속 세션은 최대 하나(AC1 §3·§5.3).
- **구현 결정 AN-07(Coordinator 유지 판정)**: 새 인증 시작은 같은 Payment의 미폐기·미귀속 세션(비종료와 **VERIFIED 미귀속** 모두)을 먼저 만료(SUPERSEDED)한다 → 사용 가능한
  인증 근거는 Payment당 최대 하나. 대체된 VERIFIED 세션은 증거·authenticationRef를 보존한 EXPIRED(SUPERSEDED)다(H-4) — 그 authenticationRef로 confirm하면
  `bindingRejection` = NOT_VERIFIED → `AUTHENTICATION_NOT_VERIFIED`.
- 대체는 토큰 교환이 아니라 **인증 시작**에서만 한다.

### 7.3 복귀 판정(`AuthenticationResultCheck`, H-3 — 세션 선택 → 1단계(조회 전) → 조회 → 2단계(TX-C))

**세션 선택(판정 전, 외부 호출 없음)**:

1. query 고정 문법: `resultRef` = `^srr_[A-Za-z0-9_-]{22}$`, `state` = `^[A-Za-z0-9_-]{43}$`(256bit). 다른 이름·중복 파라미터·형식 위반 → 400 화면, 조회하지 않음(m-3).
2. 경로 handle + 쿠키 → checkout_session: digest 일치, `now < expires_at`, 상태 ACTIVE 또는 COMPLETED(close_reason VERIFIED_REDIRECT — DUPLICATE 재표시용). 단 digest가 맞고
   결제창 세션이 Payment 쪽 사유로 닫혔으면(EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE)) §6.3의 410/409 + failUrl(Payment REQUESTED·EXPIRED → CHECKOUT_EXPIRED,
   그 밖 → PAYMENT_NOT_AVAILABLE)로 답한다(상태 불변 — 검토 #4 m-1, C7). 그 밖은 401.
3. S = 그 checkout_session에 속한 인증 세션 중 `state_digest = digest(URL state)`(UNIQUE). **없으면 400 + 보안 로그·지표**(rev 1.0의 R2를 흡수 — 틀린 state로는
   어떤 세션도 고르지 못하므로 위조 GET이 상태를 바꿀 수 없다). 행이 없으므로 이벤트 행도 없다(n-4).

**1단계(조회 전 — 위에서 아래로 첫 일치)**. 비잠금 판정 결과로 응답하고, 상태를 바꾸는 행만 `payment(X) → S(X) → checkout_session(X)` TX에서 다시 판정해 적용한다:

| # | 조건 | 결과 | S 상태 | 이벤트(세션·종류당 1회, §10.1) |
|---|---|---|---|---|
| P1 | S가 FAILED·EXPIRED | 410 IGNORED | 불변 | IGNORED_LATE_RESULT |
| P2 | S가 VERIFIED이고 digest(resultRef) = S.resultRefDigest | DUPLICATE → 303 저장된 successUrl + 같은 authenticationRef | 불변 | DUPLICATE_RESULT |
| P3 | S가 VERIFIED이고 그 밖(R0′) | 400 거절(증거 덮어쓰기 금지, 보안) | 불변 | REJECTED_RESULT(EVIDENCE_CONFLICT) |
| P4 | S가 CREATED(등록 미완료 — TX-B 전) | 400 거절 | 불변 | REJECTED_RESULT(SESSION_NOT_STARTED) |
| P5 | Payment가 REQUESTED 아님 또는 `isCheckoutWindowElapsed(now)` | 410 + failUrl(CHECKOUT_EXPIRED / PAYMENT_NOT_AVAILABLE) | EXPIRED(PAYMENT_CLOSED), 결제창 세션 EXPIRED(PAYMENT_CLOSED) — **Payment 불변**(§7.5.3) | EXPIRED |
| P6 | `now ≥ S.expiresAt`(R4) | 410 | EXPIRED(TTL_ELAPSED) | EXPIRED |
| — | 그 밖(S IN_PROGRESS·유효) | 조회로 진행 | — | — |

**조회**: `CardAuthenticationPort.lookup(resultRef)`(TX 밖, 잠금 없음).

**TX-C(2단계)**: `payment(X) → S(X) → checkout_session(X)`. 먼저 1단계를 **잠금 아래에서 다시** 판정한다(동시 요청이 그 사이 S를 바꿨을 수 있다 — C4·C5·C7). 1단계가 결정하면
그 결과. 아니면:

| # | 조건 | 결과 | S 상태 | 이벤트 |
|---|---|---|---|---|
| R3 | 조회 Unverifiable(timeout·연결·5xx·응답 형식/필드 위반·HTTP 404) | 503 확인 불가 | **IN_PROGRESS 유지** | LOOKUP_UNVERIFIABLE(분류, 1회) |
| R6 | 결과의 기관 거래 참조 ≠ S.providerTransactionRef, 또는 결과 state digest ≠ S.stateDigest | 다른 거래의 결과 | FAILED(STATE_MISMATCH) | FAILED |
| R5 | 결과 outcome EXPIRED 또는 `now ≥ validUntil` | 기관 근거 만료 | FAILED(PROVIDER_EXPIRED) | FAILED |
| R7 | 결과 바인딩(paymentId·금액·통화·MID·환경·Plan·카드사) ≠ S 바인딩, 또는 outcome AUTHENTICATED이고 issuerCode ≠ 선택 카드사(AUTHENTICATED는 증거 필드 전부 필수 — m-E) | 바인딩 불일치 | FAILED(BINDING_MISMATCH) | FAILED |
| R8 | outcome FAILED | 인증 실패 | FAILED(AUTHENTICATION_FAILED) | FAILED |
| R9 | outcome CANCELLED | 사용자 취소 | FAILED(USER_CANCELLED) | FAILED |
| R10 | outcome AUTHENTICATED이나 cardType 토큰 ∉ {CREDIT, CHECK} | 미지원 카드 | FAILED(AUTHENTICATION_FAILED, 상세 UNSUPPORTED_CARD_TYPE) | FAILED |
| R11 | outcome AUTHENTICATED, 위 모두 통과 | 검증 성공 | VERIFIED(증거·authenticationRef·resultRefDigest·verifiedAt), 결제창 세션 COMPLETED(VERIFIED_REDIRECT) | VERIFIED |

- FAILED(R5·R7~R10)는 resultRefDigest를 저장한다(1회 소비). **R6(다른 거래의 결과)은 저장하지 않는다** — 그 결과 참조는 이 세션의 것이 아니므로 저장하면 UNIQUE 때문에 그 참조의 정당한 세션이 VERIFIED에서 1062로 막힌다(검토 m-1, Coordinator 판정 — 기존 규칙의 적용). R6 상세는 이벤트·로그로만 남긴다. VERIFIED·FAILED의 resultRefDigest는 UNIQUE — 다른 세션이 이미 쓴 결과 참조면 1062 → rollback → 새 TX에서
  상태 불변 400 + REJECTED_RESULT(RESULT_REF_REUSED)(보안).
- **R3 확정(AC 4 "명세에서 확정")**: 조회 실패·timeout은 세션을 **IN_PROGRESS로 유지**하고 같은 복귀 URL로 재조회할 수 있다. TTL이 지나면 P6으로 EXPIRED. 근거: AC1 §9.1
  `Unverifiable(일시 — 세션 상태 불변)`, D-AUTHN-003 fail-closed, D-AUTH-009. FAILED로 바꾸지 않는 이유: timeout은 기관 결과가 없다는 증거가 아니다(CLAUDE.md
  "timeout만으로 효과 없음을 단정하지 않는다"의 같은 원칙) — 인증에는 금융 효과가 없어 재조회가 안전하다.
- 2단계 순서 R3 → R6 → R5 → R7(검토 H-3): 다른 거래의 결과(R6)를 먼저 걸러야 그 결과의 유효기간(R5)·바인딩(R7)을 S에 대한 사실로 읽지 않는다.
- P3·P4·세션 없음은 상태를 바꾸지 않는다(AC1 §5.3 "다른 증거 callback → 거절, 덮어쓰지 않음"). 기관이 확인한 불일치(R6)만 FAILED(AN-09).
- `authenticationRef` = `aref_` + base64url 128bit(서버 생성, 세션당 하나, UNIQUE — 대체·만료된 행에도 유지). 자격 증명이 아니라 식별자(confirm은 MID 인증과 바인딩 대조를 요구).
  로그에는 앞 9자만.

### 7.4 상태 전이 요약(이 작업이 쓰는 경로)

| 전이 | 출처 | TX(잠금) |
|---|---|---|
| — → CREATED | 인증 시작(`AuthenticationSessionStart`) | TX-A(payment → session) |
| CREATED → IN_PROGRESS | 등록 성공 뒤 | TX-B(payment → session) |
| CREATED·IN_PROGRESS·VERIFIED(미귀속) → EXPIRED(SUPERSEDED/TTL_ELAPSED) | 새 인증 시작 | TX-A |
| IN_PROGRESS → VERIFIED / FAILED / EXPIRED | §7.3 | TX-C(payment → session → checkout_session) |
| CREATED·IN_PROGRESS → FAILED(USER_CANCELLED) | 결제창 취소 | 취소 TX(payment → session → checkout_session) |
| 비종료 → EXPIRED(PAYMENT_CLOSED) | 결제창 시각 경과를 control 미보유 경로가 발견(P5·TX-A·TX-B·취소) | 그 경로 TX — Payment 불변 |
| 미폐기·미귀속 → EXPIRED(PAYMENT_CLOSED) + Payment EXPIRED | `RequestedPaymentExpiry`(주기 작업·CreatePayment·TX-I inline) | control → payment → session → checkout_session → token |
| VERIFIED → 귀속 | 승인 시작 TX1 | AUTH-001 |

#### 7.4.1 등록 단계 응답·TX(m-10)

- **등록 실패·timeout**: 세션은 CREATED로 남는다. 별도 짧은 TX(`payment(X) → S(X)`)에서 S가 아직 CREATED면 REGISTRATION_FAILED 이벤트(분류)를 쓴다. 이 TX가 실패하면
  로그·지표만 남기고 503 화면(이벤트는 감사 보조이며 상태 사실이 아니다). 다음 시도는 새 세션이 대체한다. 같은 state로 재등록하지 않는다.
- **TX-B가 S를 CREATED가 아닌 상태로 발견**(다른 탭의 시작이 SUPERSEDED, C3): 변경 없음, 409 "다른 창에서 인증을 시작했습니다". 기관 쪽 등록 거래는 고객 없이 기관 TTL로
  끝난다(금융 효과 없음). 단 S가 control 보유 경로의 만료로 EXPIRED(PAYMENT_CLOSED)가 된 경우(C15 — 등록 중 만료 작업)는 변경 없이 Payment 종료면 409 결제 불가
  (failUrl code=PAYMENT_NOT_AVAILABLE), 아직 REQUESTED(시각 경과)면 410(code=CHECKOUT_EXPIRED)으로 답한다("다른 창" 안내가 사실이 아니므로 — 검토 #3 n-7).
- **TX-B가 Payment REQUESTED 아님·결제창 시각 경과 발견**(S는 아직 CREATED): Payment 종료면 결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE)·S EXPIRED(PAYMENT_CLOSED)·409,
  시각 경과면 S·결제창 세션 EXPIRED(PAYMENT_CLOSED)·410. Payment 불변(§7.5.3). 인증 시작·화면·취소와 같은 규칙이다(`CheckoutSessionGate`).

### 7.5 Payment 결제창 만료(D-AUTHN-005·007·008, 판정 U-3)

#### 7.5.1 저장 값

- CreatePayment가 새 Payment를 만들 때 `checkout_expires_at = created_at + paycore.payment.checkout-validity.duration`, `checkout_validity_version =
  paycore.payment.checkout-validity.version`을 같은 TX에서 저장한다(Demo: `checkout-validity-demo-1`, `PT30M` — 운영값 아님). 설정이 없거나 `duration ≤ 0`이면 기동 실패(운영 기본값 없음, n-g).
  CreatePayment API·응답은 바뀌지 않는다(**확정** D-AUTHN-005).
- **재사용은 만료를 연장하지 않는다**: `UseExistingPayment`·멱등 재생은 기존 Payment를 그대로 돌려준다(시각 불변).
- 자르기(m-6): 토큰 `expires_at ≤ checkout_expires_at`, 결제창 세션 `expires_at ≤ checkout_expires_at`, 인증 세션 `expiresAt ≤ checkout_session.expires_at`(따라서
  ≤ Payment 시각). 쿠키 Max-Age = 결제창 세션 남은 초(내림, 최소 1).

#### 7.5.2 domain

- `RequestedPaymentExpiry.expireIfElapsed(payment, sessionsOfPayment, now)`: Payment가 REQUESTED가 아니면 `NOT_REQUESTED`(변경 없음), 결제창 시각이 없으면 `NO_WINDOW`,
  `now < expiresAt`이면 `NOT_DUE`, 아니면 기존 `expire`(미폐기 세션 EXPIRED(PAYMENT_CLOSED) → Payment EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED)) → `EXPIRED`.
  `NO_WINDOW`는 업무 경로가 아니라 **결함 방어**다(§7.5.6 — control 보유 경로가 먼저 채우므로 정상 흐름에서 나오지 않는다. 나오면 application이 결함으로 rollback·500).
- ~~`Payment.assignMissingCheckoutWindow(window)`~~: 검토 #2 m-2 판정으로 채우기가 기동 migration 전략(JDBC) 한 곳이 되어 main 호출자가 없어졌고 S11에서 지웠다(§24.13 편차 11).
  domain은 V7 이전 행을 시각 없이 복원만 하며(`reconstitute`), JPA 매핑의 결제창 컬럼은 `updatable = false`다(기존 시각을 바꾸거나 연장하지 않는다).
- 결제창 세션·토큰 닫기는 application이 같은 TX에서 한다(웹 프로토콜 기록 — AN-14): ACTIVE checkout_session → EXPIRED(PAYMENT_CLOSED), ISSUED 토큰 → CLOSED.

#### 7.5.3 경로별 처리(판정 U-3 — D-AUTHN-005 + D-CPD-002에서 도출)

| 경로 | 잠금 | now ≥ checkout_expires_at인 REQUESTED Payment를 만나면 |
|---|---|---|
| CreatePayment(다른 키·같은 주문·같은 내용) | 멱등 → head(S) → control → payment → (session → checkout_session → token) | 같은 TX에서 `expireIfElapsed` + 닫기 + PaymentExpired append → `control.decide` = `CreateNewPayment(generation + 1)` → 새 Payment(+ PaymentRequested). 결과 code `CREATED`(응답 형식 불변) |
| 토큰 발급 TX-I | head(S) → control → payment → (…) | inline 만료 + PaymentExpired·커밋 → 409 `PAYMENT_TERMINATED` |
| 주기 작업 | control → payment → session → checkout_session → token | 만료 + PaymentExpired(§7.5.4) |
| 교환 TX-X | payment → checkout_session → token | **Payment 불변**(control을 나중에 잡으면 CA-05 역순) → 토큰 CLOSED, 409 화면(CHECKOUT_EXPIRED) |
| TX-A·TX-B·TX-C·취소 | payment → session → checkout_session | **Payment 불변** → 자기 비종료 세션 EXPIRED(PAYMENT_CLOSED)·결제창 세션 EXPIRED(PAYMENT_CLOSED), 410 화면 |
| confirm TX1(AUTH-001) | 멱등 → head(S) → control → payment → … | **먼저** 결제창 시각 경과 확인 → 같은 inline 만료 단계(`RequestedPaymentClosing` — PaymentExpired 포함)를 호출 → `PAYMENT_TERMINATED`(§22-8 인계) |

- control을 쥔 경로만 Payment를 EXPIRED로 바꾼다 — 잠금 순서(control → payment) 그대로라 교착이 생기지 않는다. control 미보유 경로는 Payment 상태 판단에 결제창 시각을
  쓰되 Payment를 바꾸지 않으므로, 시각이 지난 REQUESTED는 다음 control 보유 경로(주기 작업·CreatePayment·TX-I·confirm)가 닫는다.
- 근거: D-AUTHN-005(시각 경과 = 결제창 만료) + D-CPD-002(EXPIRED 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED → 새 Payment 허용). 시각이 지난 REQUESTED를 계속
  재사용하면 만료된 Payment가 주문을 막는다(검토 H-2).
- **inline 만료는 커밋되는 결과 값이다(n-d)**: TX-I·CreatePayment·confirm은 만료를 예외로 표현하지 않는다(`LocalTransactions`가 예외에 rollback한다). 만료 단계가
  `Closed`를 돌려주면 그 TX는 만료를 **커밋**하고, 업무 거절(409 `PAYMENT_TERMINATED`)은 커밋 뒤 결과 값으로 응답한다(C12·C16이 커밋된 만료 + 409를 확인).
- **Outbox 순서(n-e)**: CreatePayment inline 만료 TX에서는 PaymentExpired(이전 Payment)를 PaymentRequested(새 Payment)보다 먼저 append한다(같은 TX, `id` 순서).
- 모든 control 보유 만료 경로는 한 application 단계 `RequestedPaymentClosing.closeIfElapsed(control, payment, now, causation)`을 쓴다: 세션 잠금 → `expireIfElapsed` →
  결제창 세션·토큰 닫기 → Payment 저장 → PaymentExpired append(§7.5.7). 경로마다 다른 조합이 생기지 않는다.

#### 7.5.4 주기 작업

| 항목 | 결정 |
|---|---|
| 배치 | trigger `payment.adapter.batch.RequestedPaymentExpiryScheduler`(Spring `@Scheduled` — Spring Batch 아님, `adapter.batch`는 `classesResideInKnownLayers`가 허용하고 `batchAdapterUsesOnlyApplication`이 provided만 쓰게 강제) → provided `ExpireElapsedRequestedPaymentsUseCase` → `RequestedPaymentExpiryService` |
| 설정 | `paycore.payment.checkout-expiry.job.enabled`·`fixed-delay`·`batch-size`(Demo). 시험에서는 끄고 Use Case를 직접 부른다 |
| 한 회차 | 시각 지난 REQUESTED 후보 만료. V7 이전 행의 결제창 시각 채우기는 이 작업이 아니라 기동 migration 전략이 한다(§7.5.6, 검토 #2 m-2 — 채운 행 중 이미 지난 것은 이 작업의 다음 회차가 만료) |
| 후보 | `ElapsedRequestedPaymentReader` → `JooqElapsedRequestedPaymentReader`: `status = 'REQUESTED' AND checkout_expires_at <= :now`, 키셋 `(checkout_expires_at, payment_id) > :after` 정렬·LIMIT, 인덱스 `ix_payment_requested_expiry (status, checkout_expires_at, payment_id)`. 비권위 — 판정은 잠금 재판정 |
| 건별 TX | `LocalTransactions`로 후보마다 TX 하나: OrderKey로 control(X) → payment(X) → `RequestedPaymentClosing.closeIfElapsed`. 상태가 바뀌어 있으면(`NOT_REQUESTED`·`NOT_DUE`) NoChange 커밋 |
| 다중 인스턴스 | 여러 인스턴스가 같은 후보를 잡아도 control/payment X에서 직렬화, 늦은 쪽은 EXPIRED(또는 채워진 시각)를 보고 NoChange(종료 근거 1행·PaymentExpired 1건 — C13). 분산 lock·리더 선출 불필요(DB가 직렬화 지점) |
| 실패 | 후보 하나의 실패(교착 재시도 소진·잠금 timeout)는 그 건만 rollback·로그·지표, 다음 후보 계속. 다음 주기에 다시 후보가 된다(멱등) |
| 인과 값 | correlationId = 회차 ID(UUID, 로그 MDC), causationId = `checkout-expiry-job:<회차 ID>` |
| 지표 | counter `paycore.payment.checkout_expiry{result=expired·no_change·failed}`, gauge 시각 지난 REQUESTED 수(비권위 jOOQ, label에 ID 없음). 기동 채우기는 행 수·pin 버전을 INFO 로그로 남긴다(§7.5.6) |

#### 7.5.5 시험

`expireIfElapsed` 표(4결과), V7 이전 행 시각 없이 복원(채우기 연산은 S11 제거 — §24.13), `Payment.request`에 결제창 시각 필수·`reconstitute` NULL 허용,
CreatePayment 저장 값·재사용 시 연장 없음·응답 키 불변, CreatePayment inline 만료 → `CreateNewPayment(generation+1)`, 주기 작업 NoChange·키셋·두 번 동시(C13)·장애 주입
rollback(payment·근거·세션·결제창 세션·토큰·PaymentExpired 모두 원상), 교환·토큰 발급·인증 시작·복귀·CreatePayment와의 경합(C7·C11·C12·C14·C15), control 미보유 경로가
Payment를 바꾸지 않음, **옛 볼륨 재실행**(§7.5.6), **PaymentExpired 1건·rollback 0건**(§7.5.7, C16).

#### 7.5.6 V7 이전 REQUESTED 행 채우기(D-AUTHN-007 — rev 1.1의 임시 422 경로 삭제)

| 항목 | 결정 |
|---|---|
| 값 | `checkout_expires_at = created_at + 유효기간`, `checkout_validity_version` = **V7 뒤 첫 기동이 고정(pin)한 설정 버전**(m-B, Coordinator 판정): 첫 기동이 `payment_checkout_window_pin` 한 행에 그때 활성인 설정 버전·유효기간을 INSERT(여러 인스턴스는 PK 한 행 — 먼저 들어간 값이 유일, 이후 읽기만). 모든 채우기(작업·inline)는 pin 값을 쓰고, 그 뒤 설정이 바뀌어도 바뀌지 않는다 = 배포 시점 버전. 값은 migration 상수가 아니라 애플리케이션이 설정에서 읽어 기록한다(**확정 D-AUTHN-007**, D-AUTH-009). pin을 읽거나 쓸 수 없으면 기동 실패(시각 없는 REQUESTED가 남아 있는 동안 채우기 기준 없이 동작하지 않는다) |
| 쓰는 곳 | **payment-api 기동 migration 전략 한 곳**(검토 #2 m-2 — Coordinator 판정 option (c), rev 1.3의 "주기 작업 ①·control 보유 경로 inline 채우기"를 대체): EntityManagerFactory 기동 전에 runtime 계정의 좁은 JDBC 단계(한 로컬 TX)로 pin 확인·고정 → `UPDATE payment SET checkout_expires_at = created_at + pin 유효기간, checkout_validity_version = pin 버전, version = version + 1 WHERE status = 'REQUESTED' AND checkout_expires_at IS NULL`. 새 권한 없음(payment UPDATE·pin SELECT/INSERT — §10.2). 채운 시각이 이미 지난 행은 채우기만 하고 다음 만료 작업이 EXPIRED로 만든다(D-AUTHN-007 "이미 지난 건은 다음 만료 작업에서 EXPIRED"). JPA 재조회 왕복(m-C)은 S11 옛 볼륨 시험의 종료 기준 |
| 범위 | REQUESTED만 채운다. 종료·승인 시작된 V7 이전 행은 NULL로 둔다(CHECK가 허용 — 그 행에는 결제창 시각이 의미가 없다) |
| 업무 경로 | V8_1 뒤 시각 없는 REQUESTED는 DB가 막는다(3819). CreatePayment·TX-I·confirm·교환·인증·복귀 어느 경로도 채우기를 하지 않으며, 그래도 만나면 결함(rollback·500, `NO_WINDOW` 결함 방어) |
| CHECK | `V8_1__payment_requested_checkout_window_check.sql`(PAYMENT `db/migration`, AUTH-001 V9보다 앞): `ALTER TABLE payment ADD CONSTRAINT ck_payment_requested_checkout_window CHECK (status <> 'REQUESTED' OR checkout_expires_at IS NOT NULL)` |
| 적용 순서(검토 #2 m-2 — Coordinator 판정 option (c), m-A의 "다음 기동이 V8_1 적용"을 대체) | payment-api `FlywayMigrationStrategy` bean이 **한 번의 기동 안에서**: V8_1이 pending이면 ① V8까지 migrate(migration 계정) → ② pin(행이 없을 때만 지금 설정 버전·유효기간 INSERT, 다른 인스턴스와의 경합은 PK — 먼저 들어간 값) → ③ 채우기(위 행) → ④ 나머지 전부 migrate(V8_1과 **그 뒤 migration** — AUTH-001 V9 이후가 V8_1 보류에 묶여 `ddl-auto: validate` 기동 실패가 되는 경로 제거). ②·③ 실패 → 예외로 기동 실패(fail-closed), Flyway 기록은 성공한 V8까지(success = 0 행 없음, `flyway repair` 불필요). V8_1이 이미 적용됐으면 전부 migrate 뒤 pin만 확인(없으면 고정 — "V7 뒤 첫 기동", 검토 #2 n-2). "V8_1 pending" 신호·gauge는 없앤다(미루는 상태가 없다) |
| 시험(옛 볼륨 재실행) | (S4·m-2 전략 시험) Spring 없이 운영 계정·운영 migration 위치로 V6 schema + REQUESTED(시각 지남·안 지남)·AUTHORIZING·EXPIRED 행 → 전략 1회 → history V1~V8.1 전부 성공, pin = Demo 버전·유효기간, REQUESTED만 `created_at + pin 유효기간`(ms 보존)·pin 버전·version + 1, 시각 지난 행도 REQUESTED 유지(만료는 작업), 다른 상태 NULL → 설정을 바꾼 재기동은 pin 불변·적용 없음 → REQUESTED + NULL INSERT 3819; 채우기 계정 접속 불가 → 기동 실패·history V8까지 성공만. (S11) Spring 컨텍스트 전체로 같은 옛 볼륨이 **한 번의 기동**에 V8_1까지 도달·pin 기록, 채운 행을 JPA로 재조회(m-C 왕복 — 종료 기준), 주기 작업 1회 → 지난 행 EXPIRED + PaymentExpired 1건 |
| `NO_WINDOW`·422 | rev 1.1의 "시각 없는 Payment → 422 `CHECKOUT_NOT_SUPPORTED`" 업무 경로는 삭제. `NO_WINDOW`는 domain 결함 방어로만 남긴다(V8_1 뒤에는 DB가 그런 행을 막는다) |

#### 7.5.7 PaymentExpired 이벤트(D-AUTHN-008)

REQUESTED → EXPIRED와 **같은 PAYMENT 로컬 TX**에서 Outbox에 append한다(모든 만료 경로 — 주기 작업, CreatePayment·TX-I inline, AUTH-001 confirm이 부를 `RequestedPaymentClosing`).

| envelope | 값 |
|---|---|
| eventType / schemaVersion | `PaymentExpired` / 1 |
| eventCategory | `INTEGRATION_NON_FINANCIAL`(**확정 D-AUTHN-008**) — 금융 사실 아님(REQUESTED = 미전송, 기관 효과 없음) |
| producer | `payment-api/payment` |
| aggregateType / aggregateId / aggregateVersion | `Payment` / paymentId / 만료 갱신 뒤 Payment JPA version |
| occurredAt | 만료 시각(서버 clock, UTC ms) |
| correlationId / causationId | 요청 경로: requestId / 멱등 기록 ID(CreatePayment)·requestId(TX-I). 주기 작업: 회차 ID / `checkout-expiry-job:<회차 ID>`. confirm: AUTH-001이 정한다 |
| effectKey | `PaymentExpired:{paymentId}` — Payment당 하나, Outbox UNIQUE(**확정 D-AUTHN-008**) |

payload(PaymentRequested 규칙과 같은 기준, CP계약 §10): environment, merchantAccountId, merchantSiteId, merchantOrderId, paymentId, amount(minor 정수), currency, paymentMethod,
slotGeneration, lifecycle(`EXPIRED`), terminationEvidence(`NOT_TRANSMITTED_AND_SESSION_DISCARDED`), checkoutExpiresAt, checkoutValidityVersion, expiredAt. **제외**: paymentKey,
orderName, 카드정보·Secret·개인정보, 결제창 토큰·세션·인증 세션 식별값.

- 한 번만: 만료는 control·payment X 아래에서 REQUESTED일 때만 일어나고(두 번째 시도는 `NOT_REQUESTED` → append 없음), effect_key UNIQUE가 DB 방어선이다(1062는 결함 —
  rollback). rollback이면 Payment 전이와 함께 이벤트도 없다.
- 이벤트 카탈로그: AUTH-CONTRACT-001 §11 표에 "AUTHN-001 추가" 표식으로 한 행을 더한다(그 문서의 나머지는 바꾸지 않는다). 소비자 없음, Relay·Inbox는 후속(CP계약 §10과 같다).
- 시험: `PaymentExpiredEnvelopeSpec`(envelope·payload 키 집합 정확 비교·제외 필드 부재), 같은 Payment 만료 2회 순차·동시(주기 작업 2 + inline) → 이벤트 1건(C16), 만료 TX의
  Outbox flush 뒤 장애 주입 → Payment REQUESTED·이벤트 0건, CreatePayment inline 만료 → PaymentExpired 1 + PaymentRequested 1(새 Payment).

### 7.6 이벤트 기록(n-4)

- `authentication_session_event`의 seq는 `payment(X) → S(X)` 잠금 아래에서 `max(seq) + 1`로 정한다.
- 반복될 수 있는 종류(DUPLICATE_RESULT·IGNORED_LATE_RESULT·REJECTED_RESULT·LOOKUP_UNVERIFIABLE)는 **세션·(종류, 상세 분류)당 첫 1회만 행**으로 남기고 이후는 로그·counter만(생성 컬럼
  UNIQUE로 DB도 강제, 애플리케이션은 잠금 아래 존재 확인 후 INSERT — 정상 경로에서 1062 없음). GET 반복으로 행이 무한히 늘지 않는다.
- 상태 전이 종류(CREATED·STARTED·VERIFIED·FAILED·EXPIRED·REGISTRATION_FAILED)는 세션 lifecycle상 각 1회다.
## 8. successUrl/failUrl 검증(D-AUTHN-002)

### 8.1 허용 문법(엄격 부분집합 — 정규화하지 않고 거절)

`MerchantReturnUrl`은 아래 문법에 **정확히** 맞을 때만 만들어진다. 다른 표기를 고쳐 주지 않는다(D-MD-009 "자동 정규화 없음"과 같은 정신, AN-10).

```text
url    = "https://" host [ path ] [ "?" query ]           ; 길이 ≤ 2048, 전부 출력 가능 ASCII(0x21–0x7E), "\"·"#" 없음
host   = label 1*( "." label )                            ; MASTER SiteChannel WEB 문법과 같다: label = [a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?,
                                                          ; label 둘 이상, 전체 ≤ 253, 끝 점 없음, 마지막 label이 10진수·"0x"16진이면 IP 리터럴
path   = 1*( "/" segment )                                 ; segment = *( unreserved / pct / sub / ":" / "@" )
query  = *( unreserved / pct / sub / ":" / "@" / "/" / "?" )
pct    = "%" HEXDIG HEXDIG
```

| 입력 예 | 결과(사유) | 이유 |
|---|---|---|
| `https://shop.example.com/pay/ok?x=1`, `https://shop.example.com`, `https://shop.example.com?x=1` | 허용(host = Site host일 때) | — |
| `http://…`, `HTTPS://…`, `javascript:…`, `//shop.example.com/…`, `https:/shop…`, `https:shop…` | 400(SCHEME) | https 소문자 + `//` 필수 |
| `https://user@shop.example.com/`, `https://shop.example.com@evil.com/` | 400(USERINFO) | `@`는 authority에 없음 |
| `https://shop.example.com:443/`, `:8443`, `:` | 400(PORT) | 포트 표기 전부 거절(기본 포트 포함) |
| `https://SHOP.example.com/`, `https://shop.example.com./`, `https://localhost/`, `https://-a.example.com/` | 400(HOST_SYNTAX) | 정규화 없음, SiteChannel 문법 |
| `https://샵.example.com/`, 탭·CR·LF·공백·제어문자 | 400(NOT_PRINTABLE_ASCII) | 브라우저가 지워 host가 바뀌는 우회 차단 |
| `https://127.0.0.1/`, `https://[::1]/`, `https://2130706433/`, `https://0x7f.0x1/`, `https://a.0x7f/` | 400(IP_LITERAL) | D-MD-014와 같은 판정 |
| `https://shop.example.com%2eevil.com/` | 400(HOST_SYNTAX) | host에 `%` 금지 |
| `https://shop.example.com\@evil.com/`, `https://shop.example.com/a\b` | 400(BACKSLASH) | WHATWG가 `/`로 바꾸는 문자 |
| `https://shop.example.com/#frag` | 400(FRAGMENT) | 덧붙일 query 위치 모호(AN-10) |
| `https://shop.example.com/a%zz` | 400(PERCENT_ENCODING) | 잘못된 percent-encoding |
| 2049자 이상 | 400(TOO_LONG) | 컬럼 VARCHAR(2048) |

- rev 1.0의 host 문법(`label *("." label)`, `[a-z0-9-]`)보다 좁다(한 label·하이픈 시작/끝 거절). Site host가 SiteChannel 문법이라 rev 1.0 문법으로 통과해도 host 일치에서
  반드시 거절되던 입력이 400으로 앞당겨질 뿐 허용 집합은 같다(§24.2).

### 8.2 host 판정

- **확정**: 허용 = 문법 통과 ∧ `host == Site WEB host`(문자열 완전 일치, D-AUTHN-002). `www.shop.example.com`·`shop.example.com.evil.com`·`evil-shop.example.com`은
  다른 host다(D-MD-009 "www. 등 서브도메인은 다른 채널").
- Site host는 Snapshot의 ACTIVE Site의 WEB 채널 식별값이다. **APP 채널·TERMINATED Site·Snapshot에 Site 없음 → host 없음 → 거절**. 종료 Site를 거절하는 이유(도출):
  활성 채널 유일성(D-MD-008)은 ACTIVE에만 걸리므로 종료된 Site의 host는 다른 Merchant가 다시 등록할 수 있다 — 종료 Site host를 허용 근거로 쓰면 다른 Merchant
  도메인으로 redirect할 수 있다.
- 교차 확인 시험(adapter 계층, `java.net.URI` 사용 가능): 허용된 모든 시험 URL에 대해 `URI(url)`의 scheme = `https`, host = Site host, port = -1, userInfo = null임을
  확인한다(엄격 부분집합이 RFC 3986·WHATWG 해석과 갈라지지 않음을 증명). 거절 표의 각 행은 domain 시험에 1행씩.

### 8.3 redirect 구성

- 저장된(검증된) URL에 `paymentId`·`authenticationRef`(또는 `code`)를 덧붙인다: query가 없으면 `?`, 있으면 `&`. 값은 RFC 3986 unreserved 밖을 UTF-8 percent-encoding.
  파라미터 이름은 코드 상수(unreserved만). 구성 결과를 다시 같은 문법으로 파싱해 host가 그대로인지 확인한 뒤 `Location`에 쓴다(방어적 재검증). 2048자 제한은 저장 컬럼의
  제한이라 구성 결과에는 적용하지 않는다(저장 한계 URL도 redirect 가능 — 상한 8192, AN-24).
- redirect 대상은 토큰에 저장된 URL뿐이다. 요청 파라미터로 redirect 대상을 받지 않는다(open redirect 원천 차단).

## 9. MASTER Site host Snapshot 전파(schemaVersion 3)

### 9.1 내용 변경

| 위치 | 필드 | 규칙 |
|---|---|---|
| `sites[]` | `channel` | 객체. WEB: `{"type":"WEB","host":"<소문자 host>"}`, APP: `{"type":"APP"}`(앱 식별값은 싣지 않음 — 필요 없는 식별값 최소화) |

- MS1 §3.1의 "넣지 않는 것: 채널 식별값"은 **D-AUTHN-002(사용자 결정)가 WEB host에 한해 대체**한다. 사업자등록번호·표시 이름·APP 식별값은 여전히 제외.
- 채널은 MASTER에서 불변(등록만)이므로 새 revision 증가 지점이 없다 — Site 등록이 이미 revision을 올린다(MS1 §3.2). 지난 상태·종료 Site도 그대로 싣는다
  (내용 = 원본 상태만의 함수, MS1 §15-1).
- 정규 JSON·`ec1:` hash 규칙 불변(MS2 §2.3-5). golden v3 파일을 harness가 독립 생성(`test_entitlement_golden.py` 확장, 두 앱 바이트 비교).

### 9.2 버전과 승격(MS2 §5 방식 그대로)

| 항목 | 규칙 | 근거 |
|---|---|---|
| schemaVersion | MASTER 조립 3, PAYMENT 해석 {1, 2, 3} | 해석기가 모르는/빠진 필드를 거절(MS2 §2.3-1) — v2에 필드 추가는 계약 위반 |
| 승인 조건 | v3 ⊇ v2(terms 포함). `TERMS_SNAPSHOT_SCHEMA_VERSION = 2`는 "2 이상"으로 비교(기존 코드 `>=`) | MS2 §5.1 |
| 승격 | lease 갱신 시 최근 issue schema_version(2) < 3 → revision + 1 승격 발행(HIGH) — MASTER `EntitlementLeaseService` 기존 경로 | MS2 §5.2 |
| 배포 순서 | PAYMENT(v3 해석) 먼저, MASTER(v3 조립) 나중. 역순이면 v3 UNSUPPORTED로 head가 막힌다(fail-closed, 가용성 사고) | MS2 §5.4 |
| MASTER migration | 없음(`merchant_site.channel_*`·`entitlement_snapshot_issue.schema_version` 존재) | §3 |

### 9.3 PAYMENT 수신(Inbox 같은 TX)

- 새 표 없음. 기존 Inbox 로컬 TX(inbox_event + head + entitlement_snapshot, MS1 §5.1)가 v3 내용을 그대로 저장한다. host는 revision 내용에서 판정 시 읽는다.
- 수신 불변식(추가): WEB이면 host가 `SiteChannel`과 같은 WEB 문법(소문자·IP 아님·≤253)을 만족, APP이면 host 없음. 위반 → `SnapshotContractViolation`(4xx·저장 없음
  → MASTER 격리). PAYMENT 해석기는 MASTER를 믿지 않고 다시 확인한다(MS2 §4.1 관례).
- **기존 v1·v2 head**: host가 없다 → `SiteWebHostQuery`는 `Unavailable(HOST_NOT_PROPAGATED)` → 토큰 발급 503(fail-closed, D-AUTHN-002 "전파 없음 → 거절").
  다음 lease 갱신의 승격 발행(v3, revision + 1)이 적용되면 풀린다. CreatePayment eligibility·승인 조건 판정은 v1·v2·v3 모두 기존대로 동작한다(영향 없음).

### 9.4 조회 Port

- entitlement 모듈 `application.provided.SiteWebHostQuery`(MANDATORY TX, head `FOR SHARE`, 로컬 투영만, 캐시 없음 — `AuthorizationTermsQuery`와 같은 관례):
  입력 `(mid, siteId)` + `evaluatedAt` → `WebHost(host, snapshotVersion, validUntil)` / `NoWebHost(reason: APP_CHANNEL·SITE_TERMINATED·SITE_UNKNOWN)` /
  `Unavailable(SNAPSHOT_MISSING·SNAPSHOT_EXPIRED·VERSION_UNSUPPORTED·HOST_NOT_PROPAGATED)`. `now ≥ validUntil`이면 Unavailable(SNAPSHOT_EXPIRED)(MS1 §6.3 규칙).
- payment 모듈 `application.required.SiteWebHostPort` → `adapter.integration.EntitlementSiteWebHostAdapter` → provided(기존 `EntitlementEligibilityAdapter` 구조).
- 토큰 발급은 host만 판정한다. MID 차단·계약 효력 등 권리 재평가는 confirm(AC1 §4.6 A1)이 한다(AN-11 — 인증 창은 금융 효과가 없고 confirm이 권위 판정).

---

## 10. 데이터 모델·PAYMENT migration

### 10.1 `db/migration/payment/V7__checkout_authentication.sql`

공통: 문자열 `utf8mb4_0900_bin`, 시각 UTC `DATETIME(3)`, `version BIGINT ≥ 0`, 금액 `amount_minor ≥ 1`·통화 `^[A-Z]{3}$`·환경 CHECK(V1 관례), 자식 → 부모 FK(V1 관례).

| 표 | 핵심 컬럼 | 제약 |
|---|---|---|
| `payment_checkout_window_pin`(새 표, m-B) | `pin_id TINYINT`(PK, CHECK = 1 — 한 행), `validity_version VARCHAR(64)`, `validity_duration_ms BIGINT`(CHECK > 0), `pinned_at` | append-only 한 행(runtime SELECT·INSERT만) |
| `payment`(ALTER) | `checkout_expires_at DATETIME(3) NULL`, `checkout_validity_version VARCHAR(64) NULL`(JPA 매핑은 **updatable** — 채우기 UPDATE가 버려지지 않게, m-C; 값이 있는 행의 변경은 domain이 막는다) | 둘 다 NULL이거나 둘 다 NOT NULL, `checkout_expires_at > created_at`, 인덱스 `ix_payment_requested_expiry (status, checkout_expires_at, payment_id)`. NULL = V7 이전 행. REQUESTED NOT NULL은 채우기 뒤 **`V8_1__payment_requested_checkout_window_check.sql`**이 `CHECK (status <> 'REQUESTED' OR checkout_expires_at IS NOT NULL)`로 추가(migration 전략 가드 — 채우기 전에는 적용을 미룬다, D-AUTHN-007, §7.5.6) |
| `checkout_token` | `checkout_token_id`(PK `ctk_`+ULID), `token_digest`(`cth1:`+hex, UNIQUE), `payment_id`(FK payment), 바인딩(`environment`·`merchant_account_id`·`merchant_order_id`·`merchant_site_id`·`amount_minor`·`currency`), `success_url`·`fail_url`(VARCHAR 2048, `^https://` CHECK), `site_host`, `site_snapshot_version`, `status`(ISSUED/CONSUMED/SUPERSEDED/EXPIRED/CLOSED), `issued_at`·`expires_at`·`consumed_at`·`closed_at`, `issued_by_credential_id`, `policy_version` | `expires_at > issued_at`, CONSUMED ⇔ consumed_at, SUPERSEDED·EXPIRED·CLOSED ⇔ closed_at, 생성 컬럼 `outstanding_payment_id = IF(status='ISSUED', payment_id, NULL)` **UNIQUE** |
| `checkout_session` | `checkout_session_id`(PK `cks_`+ULID), `handle`(CHAR 22 base64url, **UNIQUE**), `session_digest`(`cks1:`+hex, UNIQUE), `checkout_token_id`(FK, **UNIQUE**), `payment_id`(FK), `success_url`·`fail_url`·`site_host`(토큰에서 복사), `status`(ACTIVE/COMPLETED/REVOKED/EXPIRED), `close_reason`, `created_at`·`expires_at`·`closed_at`, `policy_version` | 생성 컬럼 `active_payment_id = IF(status='ACTIVE', payment_id, NULL)` **UNIQUE**; ACTIVE ⇔ close_reason·closed_at NULL; COMPLETED ⇒ close_reason ∈ {VERIFIED_REDIRECT, CANCELLED, PAYMENT_NOT_AVAILABLE}; REVOKED ⇒ SUPERSEDED; EXPIRED ⇒ {TTL_ELAPSED, PAYMENT_CLOSED}; `expires_at > created_at` |
| `authentication_session` | `authentication_session_id`(PK `aus_`+ULID), `payment_id`(FK), `checkout_session_id`(FK), 바인딩(`environment`·`merchant_account_id`·`merchant_order_id`·`amount_minor`·`currency`·`plan_kind`·`installment_months`·`card_company`), `status`(5종), `state_digest`(UNIQUE), `provider_transaction_ref`, `result_ref_digest`(UNIQUE NULL), `failure_reason`, `expiry_cause`, 증거(`evidence_ref`·`card_type`·`issuer_code`·`acquirer_code`·`provider_code`·`provider_valid_until`), `verified_at`, `authentication_ref`(UNIQUE NULL), `bound_authorization_id`(UNIQUE NULL — AUTH-001), `profile_id`·`plan_policy_version`·`policy_version`, `created_at`·`expires_at`·`closed_at` | 생성 컬럼 `open_payment_id = IF(status IN ('CREATED','IN_PROGRESS'), payment_id, NULL)` **UNIQUE**, `bound_payment_id = IF(bound_authorization_id IS NOT NULL, payment_id, NULL)` **UNIQUE**. **단방향 규칙(H-4)**: VERIFIED ⇒ 증거 전부·authentication_ref·result_ref_digest·verified_at NOT NULL; 증거(evidence_ref)·authentication_ref NOT NULL ⇒ status ∈ {VERIFIED, EXPIRED}; 증거 컬럼은 모두 NULL이거나 모두 NOT NULL(authentication_ref·verified_at 포함); result_ref_digest NOT NULL ⇒ status ∈ {VERIFIED, FAILED, EXPIRED}; EXPIRED ∧ result_ref_digest NOT NULL ⇒ evidence_ref NOT NULL(n-h — 결과 참조를 가진 EXPIRED는 VERIFIED였던 행뿐); failure_reason ⇔ FAILED; expiry_cause ⇔ EXPIRED; bound ⇒ VERIFIED; IN_PROGRESS·VERIFIED ⇒ provider_transaction_ref NOT NULL; CREATED ⇒ provider_transaction_ref NULL; `plan_kind='INSTALLMENT'` ⇔ months 2..99; card_type ∈ {CREDIT, CHECK}; `expires_at > created_at` |
| `authentication_session_event` | PK(`authentication_session_id`, `seq`), `kind`(CREATED·STARTED·VERIFIED·FAILED·EXPIRED·DUPLICATE_RESULT·IGNORED_LATE_RESULT·REJECTED_RESULT·LOOKUP_UNVERIFIABLE·REGISTRATION_FAILED), `detail_code`(고정 분류, NULL), `occurred_at`, `correlation_id` | **append-only**, `seq ≥ 0`, FK → authentication_session, 생성 컬럼 `repeat_key = IF(kind IN ('DUPLICATE_RESULT','IGNORED_LATE_RESULT','REJECTED_RESULT','LOOKUP_UNVERIFIABLE'), CONCAT(kind, ':', COALESCE(detail_code, '')), NULL)` + UNIQUE(`authentication_session_id`, `repeat_key`)(§7.6 — n-b: REJECTED_RESULT의 SESSION_NOT_STARTED·EVIDENCE_CONFLICT·RESULT_REF_REUSED, LOOKUP_UNVERIFIABLE 분류가 서로 접히지 않는다) |

- 원문 비밀(토큰·쿠키 비밀·state·결과 참조) 컬럼은 **없다**. digest만. 카드번호·CVC 컬럼 없음(`NoCardDataSchemaSpec`이 information_schema 컬럼 이름
  `card_number·pan·cvc·cvv·expiry_month` 부재를 확인 — `checkout_expires_at`은 이 패턴에 걸리지 않는다).
- 생성 컬럼 UNIQUE는 MASTER 관례(`active_channel_key`, MS2 `open_route_key`)와 같다. domain 불변식(비종료 1·귀속 1·ACTIVE 1·미교환 1)을 DB가 다시 지킨다.
- 표 사이 시각 관계(토큰·결제창 세션 ≤ Payment 시각, 인증 세션 ≤ 결제창 세션)는 CHECK로 표현할 수 없어 application이 자른다(§7.5.1)·시험한다.
- `authentication_session_event`는 업무 Outbox가 아니다. PaymentExpired(D-AUTHN-008)는 기존 `outbox_event`에 append한다(새 표 없음, §7.5.7).

### 10.2 `db/runtime-privilege/payment/V8__checkout_authentication_runtime_privileges.sql`(관례 §9.2)

| 표 | runtime 권한 | 쓰기 경로(근거) | 없는 권한 |
|---|---|---|---|
| `payment` | 변경 없음(SELECT, INSERT, UPDATE — V2) | INSERT: 새 Payment의 결제창 시각. UPDATE: V7 이전 REQUESTED 행 시각 채우기(한 번, §7.5.6)와 기존 만료 전이 | — |
| `payment_checkout_window_pin` | SELECT, INSERT | INSERT: V7 뒤 첫 기동의 채우기 기준 고정(한 행, §7.5.6) | UPDATE, DELETE |
| `checkout_token` | SELECT, INSERT, UPDATE | INSERT 발급, UPDATE 소비·대체·만료·닫힘 | DELETE |
| `checkout_session` | SELECT, INSERT, UPDATE | INSERT 교환, UPDATE 완료·취소·대체·만료 | DELETE |
| `authentication_session` | SELECT, INSERT, UPDATE | INSERT 시작, UPDATE 전이(+ AUTH-001 귀속) | DELETE |
| `authentication_session_event` | SELECT, INSERT | INSERT만(`@Immutable`) | UPDATE, DELETE, 잠금 읽기(1142) |

`DatabaseAccountSeparationSpec`의 고정 SHOW GRANTS 기대값·표 목록 비교에 5개 표(결제창 4 + pin 1)를 추가한다(새 표 추가 — 기대값 완화 아님, Reviewer 확인 대상).

### 10.3 잠금 순서(AC1 CA-05 확장 — 구현 결정 AN-12)

```text
멱등 기록 → entitlement_head(S) → order_payment_control → payment → payment_authorization → authentication_session → 한도 사용량
        → checkout_session → checkout_token
```

| Use Case | 부분열 |
|---|---|
| 토큰 발급(TX-I) | head(S) → control → payment → [inline 만료: authentication_session → checkout_session → checkout_token] → checkout_token |
| CreatePayment(기존 + inline 만료) | 멱등 → head(S) → control → payment(현재) → [authentication_session → checkout_session → checkout_token] → 새 payment INSERT |
| 주기 작업 만료 | control → payment → authentication_session → checkout_session → checkout_token |
| 토큰 교환(TX-X) | payment → checkout_session → checkout_token |
| 인증 시작(TX-A·TX-B)·등록 실패 이벤트 | payment → authentication_session (→ checkout_session: 만료 기록 때만) |
| 결과 반영(TX-C)·1단계 쓰기·취소 | payment → authentication_session → checkout_session |
| confirm TX1(AUTH-001) | 멱등 → head(S) → control → payment → [inline 만료 …] → payment_authorization → authentication_session → 한도 |

- 모든 결제창 Use Case가 **payment 행을 먼저 X로 잠근다**(control 보유 경로는 control 다음) → 같은 Payment의 결제창 작업·만료·confirm은 payment 행에서 직렬화된다.
  control 미보유 경로는 control을 나중에 잡지 않는다(§7.5.3). 토큰은 digest로 비잠금 조회해 payment_id를 얻은 뒤 순서대로 잠그고 다시 판정한다.
- FK 자식 INSERT의 부모 S 잠금(`authentication_session` → `checkout_session`)은 같은 Payment의 payment X 아래에서만 일어나 경쟁 TX가 없다(C3·C6 실 MySQL 시험).
- 교착(1213)은 기존 `LocalTransactions` 재시도(최대 3회), 잠금 timeout(1205)은 503(관례 §9).

## 11. 모듈·패키지·Port

### 11.1 모듈 결정(구현 결정 AN-13)

**기존 `com.paycore.payment.payment` 모듈에 둔다.** 새 Modulith 모듈 `checkout`은 기각한다.

| 판단 | 근거 |
|---|---|
| `AuthenticationSession`은 이미 `payment` domain Aggregate다 | AC1 CA-01 — `AuthorizationStart`(승인 시작)·`RequestedPaymentExpiry`와 같은 TX·같은 잠금 순서. 다른 모듈에 두면 Modulith가 domain 사용을 막아 상태 복사 DTO와 모듈 경계를 넘는 MANDATORY TX가 모든 단계에 생긴다 |
| 토큰 발급·교환·시작은 Payment 행 잠금과 같은 TX여야 한다 | §10.3 — payment 행이 직렬화 지점 |
| 테이블 소유 맵 규칙이 아직 없다 | 관례 §3 "두 번째 업무 모듈이 PAYMENT 표를 가질 때 소유 맵 규칙 도입" — 새 모듈이면 이 작업에서 그 규칙까지 만들어야 한다 |
| 표면 분리는 다른 수단으로 | 별도 checkout connector(포트)·별도 SecurityFilterChain·`adapter.web`의 결제창 Controller는 `application.provided`만 호출(ArchUnit `checkoutWebUsesOnlyProvidedUseCases`, §17.3) → A01 `payment-window` 실행 단위로 추출할 때 provided Use Case를 JSON API로 노출하면 된다(§22-1) |

### 11.2 Port·타입(§14 명명 기준)

| 계층 | 이름 | 종류(§14 판정) |
|---|---|---|
| provided | `IssueCheckoutTokenUseCase`, `OpenCheckoutSessionUseCase`, `StartCardAuthenticationUseCase`, `CompleteCardAuthenticationUseCase`, `CancelCheckoutUseCase`, `ExpireElapsedRequestedPaymentsUseCase` | 명령 Use Case |
| provided | `CheckoutPageQuery`(handle + 쿠키 digest → 세션 확인 + 화면 DTO — rev 1.3의 `CheckoutSessionQuery`·`CheckoutViewQuery`를 하나로, §24.12) | 판정·DTO 조회 |
| required | `AuthenticationSessionRepository` → `JpaAuthenticationSessionRepository` | **Aggregate 저장**, `Versioned` |
| required | `AuthenticationSessionEventStore` → `JpaAuthenticationSessionEventStore` | Store ①: (세션, seq) 결정 로그 |
| required | `CheckoutTokenStore` → `JpaCheckoutTokenStore` | Store ③: 보안 프로토콜 기록 |
| required | `CheckoutSessionStore` → `JpaCheckoutSessionStore` | Store ③: 브라우저 세션 프로토콜 기록 |
| required | `CheckoutViewReader` → `JooqCheckoutViewReader` | jOOQ 조회 |
| required | `AuthenticationBacklogReader` → `JooqAuthenticationBacklogReader` | jOOQ 조회(지표용) |
| required | `ElapsedRequestedPaymentReader` → `JooqElapsedRequestedPaymentReader` | jOOQ 조회(만료 후보, 비권위) |
| required | `CheckoutValidityPolicy`(결제 유효기간 설정: version·duration, 기동 시 필수) — 구현 위치는 기존 설정 바인딩 관례를 따른다(2단계 기록 §24) | 설정 Port — 기존 `PaymentClock`과 같은 관례 |
| required | `SiteWebHostPort` → `adapter.integration.EntitlementSiteWebHostAdapter` | 다른 모듈 provided 연결 |
| required | `CardAuthenticationPort` → `adapter.integration.institution.SimulatorCardAuthenticationAdapter` | 외부 기관 |
| required | `CheckoutSecretGenerator` → `SecureRandomCheckoutSecretGenerator`(application, JDK `SecureRandom`) | 기존 `PaymentIdentifierGenerator` 관례 |
| entitlement provided | `SiteWebHostQuery` | 판정 조회 |
| 서비스 | `CheckoutTokenIssuanceService`, `CheckoutSessionOpeningService`, `CardAuthenticationStartService`, `CardAuthenticationCompletionService`, `CheckoutCancellationService`, `RequestedPaymentExpiryService`(주기 작업 + CreatePayment/TX-I가 쓰는 inline 만료 단계 `RequestedPaymentClosing`) | 외부 호출을 끼는 서비스는 TX를 열지 않는 조정자, TX는 `LocalTransactions` |
| web | `CheckoutTokenController`(공개 포트 JSON), `CheckoutPageController`(checkout 포트 HTML), `CheckoutSecurityConfiguration`, `CheckoutConnectorConfiguration` | `adapter.web` — provided만(ArchUnit §17.3) |
| batch | `RequestedPaymentExpiryScheduler` | `adapter.batch` — provided만 |

`CheckoutToken`·`CheckoutSession`을 Aggregate로 두지 않는 이유(AN-14): 업무 사실이 아니라 웹 보안 프로토콜 기록이다. 규칙(1회·만료·바인딩)은 작은 순수 값(`CheckoutBinding`,
`SecretDigest`)과 서비스에 둔다. 업무 사실은 `AuthenticationSession`과 Payment 결제창 시각이다.

## 12. 웹 보안

### 12.1 connector·보안 체인·origin 설정

- checkout 전용 connector(`paycore.payment.checkout.port`, 운영 기본값 없음 — 없으면 기동 실패, 공개·내부 포트와 같으면 기동 거부). `ApiEntryFilter`를 3-connector로
  확장: 내부 포트 ↔ `/internal/**`, checkout 포트 ↔ `/checkout/**`(짝이 아니면 404). 공개 포트의 그 밖 경로 규칙은 기존과 같다 — 공개 포트를 `/v1/**`·`/actuator/health`로
  좁히는 것은 후속 후보(Coordinator 판정 2026-10-16, §24.12 편차 1). 운영 배포는 checkout 전용 origin(배포 작업). AN-15.
  **D-AUTHN-006 승인 편차**(§22-1).
- 별도 `SecurityFilterChain`(`securityMatcher("/checkout/**")`, 순서가 API 체인보다 앞): Bearer 필터 없음, HttpSession 없음(STATELESS), CSRF **켬**(§12.3), `permitAll` — 접근
  통제는 handle 쿠키로 application이 한다. API 체인은 그대로 — 시험: checkout 체인이 `/v1/**`에 걸리지 않음, 쿠키로 `/v1/**` 인증 불가.
- **origin은 설정에서만(m-9)**: `paycore.checkout.public-base-url`(checkoutUrl·PG returnUrl의 origin), `paycore.authentication.simulator.customer-base-url`(Simulator 고객
  URL·CSP form-action), `paycore.authentication.simulator.base-url`(서버 간). 기동 시 검증: `https`(로컬 Demo 프로파일의 Simulator 서버 간 URL만 http loopback 허용 —
  시험 설정), userinfo·path(`/` 외)·query·fragment 없음, 위반이면 기동 실패. 요청의 `Host`·`X-Forwarded-*`·`Forwarded`로 URL을 만들지 않는다(시험: 조작한 Host 헤더가
  응답 URL·CSP에 나타나지 않음).

### 12.2 쿠키

| 속성 | 값 | 이유 |
|---|---|---|
| 이름 | `__Host-pc_checkout_<handle>` | `__Host-` 접두 → Secure·Path=/·Domain 없음을 브라우저가 강제. handle별 이름이라 동시 결제창이 섞이지 않는다(판정 m-1) |
| 값 | 256bit 난수(base64url), DB에는 `cks1:` digest만 | D-AUTHN-001 "hash만" 정신 |
| HttpOnly·Secure | 켬 | **확정 D-AUTHN-001** |
| SameSite | **Lax** | 기관 → PG 복귀가 cross-site top-level GET이다(AN-16) |
| 수명 | Max-Age = 결제창 세션 남은 초(내림, 최소 1) — 세션 `expires_at`은 Payment 결제창 시각으로 잘린다 | D-AUTH-009, D-AUTHN-005, m-6 |
| 세션 ID 재발급 | 교환 때마다 서버가 새 handle·새 비밀 생성, 같은 Payment의 이전 세션은 REVOKED | **확정 D-AUTHN-001** |

- 쿠키 삭제(m-D, Coordinator 판정 option 1): COMPLETED(VERIFIED_REDIRECT)에서는 **지우지 않는다** — 남은 수명 동안 같은 복귀 URL 재요청이 P2 DUPLICATE 303이 된다(Simulator "같은
  복귀 URL 재요청" 시나리오, 시험: 순차 중복 → 303 같은 successUrl). REVOKED·EXPIRED·COMPLETED(CANCELLED·PAYMENT_NOT_AVAILABLE)의 응답에서는 `Max-Age=0`으로 지운다.
  `expires_at` 경과 뒤에는 쿠키가 있어도 401.

### 12.3 CSRF

- Spring Security `CsrfFilter` + 사용자 정의 `CsrfTokenRepository`: CSRF 토큰 = `base64url(SHA-256("paycore.checkout.csrf.v1" ‖ 0x00 ‖ 쿠키 비밀))` — 서버 세션 비밀에서
  파생한 세션 결합 synchronizer 토큰. 저장이 필요 없고(원문 비밀을 DB에 두지 않음), 쿠키 없이는 계산할 수 없으며, 토큰이 노출돼도 쿠키 비밀을 역산할 수 없다.
  Thymeleaf `th:action`이 숨김 필드를 넣고 BREACH 대응 마스킹(`XorCsrfTokenRequestAttributeHandler`)은 기본값을 쓴다. AN-17(대안: DB에 CSRF 원문 저장).
- 모든 POST(`/checkout/s/{handle}/authentication`, `/checkout/s/{handle}/cancel`)에 필수. CSRF 토큰은 그 handle 쿠키 비밀에서 파생한다. 불일치 → 403 화면, 상태 불변.
- 복귀 GET은 CSRF 토큰을 가질 수 없다(기관 redirect) → state(256bit, 세션별) + 쿠키 세션 결합 + state가 맞는 세션이 없으면 상태 불변(§7.3 세션 선택)으로 방어.

### 12.4 응답 헤더(checkout 포트 모든 응답)

```text
Content-Security-Policy: default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; base-uri 'none';
                         frame-ancestors 'none'; form-action 'self' <Simulator 고객 origin> https://<Site host>
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Cache-Control: no-store
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()
```

- `form-action`에 Simulator origin과 Site origin을 넣는 이유: 폼 제출 뒤의 redirect(인증 시작 → Simulator, 취소 → failUrl)도 브라우저가 form-action으로 검사한다.
  Site host는 그 결제창 세션의 값으로 응답마다 만든다(고정 값이 아니라 세션 값 — 다른 Merchant 도메인을 넣지 않는다). AN-18.
- JS 없음(`script-src 'none'`). CSS는 self 정적 파일. HSTS는 TLS 종단(배포)에서.
- `Referrer-Policy: no-referrer` + 교환 직후 303으로 URL에서 토큰 제거 → 토큰이 Referer·history에 남는 경로를 줄인다.

### 12.5 XSS·오류 화면

- 템플릿은 `th:text`·`th:value`·`th:href`(`@{…}`)만. `th:utext`·`[(…)]`(비escape inline)·`th:inline="javascript"`·`<script`는 금지 — `CheckoutTemplateScanSpec`이
  `templates/checkout/**`를 검사하고, 위반 fixture 템플릿(시험 자원)으로 검사기가 실제로 잡는지 확인.
- 화면 모델은 application이 돌려준 DTO만(Entity 금지 — OSIV off, 관례 §11.1). orderName 등 가맹점 입력은 escape되어 표시(시험: `<script>alert(1)</script>` 주문명 →
  `&lt;script&gt;`, 응답에 원문 태그 없음).
- 오류 화면은 §6.3 고정 문구. 예외 메시지·stack·SQL·Snapshot 버전·기관 코드·URL 원문 없음(`CheckoutErrorPageLeakSpec`: 표식 문자열 바이트 검색).

### 12.6 로그·지표 비노출

- 원문 금지: 토큰, 쿠키 비밀, CSRF 토큰, state, 결과 참조, 기관 evidenceRef, 기관 등록 원문. `authenticationRef`·`paymentId`는 식별자 — 로그에는 접두만(지표 label 금지).
- 경로 query가 로그에 남는 경로 차단: Tomcat access log 미사용(쓰면 `%U`만, `%r`·`%q` 금지 — 설정 시험), 요청 로깅 필터 미사용, 400 처리기가 요청 URL을 로그에
  쓰지 않음, Simulator adapter가 요청·응답 본문을 로그에 쓰지 않음(HTTP client 로깅 off).
- `CheckoutLogRedactionSpec`: 전체 흐름(발급 → 교환 → 시작 → 복귀 → 실패·재시도)을 실행하며 로그를 캡처해 원문 값이 0회 나타남을 확인(CP-API `CapturedLogs` 방식).
- 보안 이벤트(토큰 재사용·URL state 불일치·증거 충돌·CSRF 실패)는 WARN 구조화 로그(분류·checkoutSessionId 내부 ID·correlationId) + counter.

---

## 13. 가상 인증기관 Simulator(`apps/institution-simulator`)

### 13.1 실행 단위

| 항목 | 결정 | 근거 |
|---|---|---|
| 패키지 | `com.paycore.simulator`, 모듈 `authentication`(이후 AUTH-001 `van`) | 관례 §2, D-AUTHN-004 |
| 프로세스·포트 | 별도 Boot 앱, `server.port`(운영 기본값 없음), PAYCORE 앱과 의존 관계 없음(Gradle project 의존 없음) | **확정 D-AUTHN-004**, 허용 §5.5 |
| 저장 | **별도 MySQL 인스턴스**(compose 서비스 `simulator-mysql`, DB `paycore_simulator`, migration·runtime 계정 분리, Flyway). 시험은 자체 Testcontainers MySQL 8.4.11 | 허용 §5.5 "별도 영속 저장소" — PG 재시작·rollback과 무관하게 기관 사실 존속. PAYCORE 공유 초기화 스크립트(`docker/mysql/init`)를 건드리지 않아 기존 DB 계정 시험(SHOW GRANTS 고정값)에 영향이 없다 |
| runtime 권한 | runtime 계정은 `paycore_simulator.*` DML만(DDL 없음). §9.2 표 단위 role·procedure는 적용하지 않음 | AN-19: §9.2는 PayCore 소유 DB(MASTER·PAYMENT·FINANCIAL) 관례이고 Simulator는 외부 기관 대역이다. AUTH-001 가상 VAN 원장이 들어올 때 append-only 표 권한을 재검토(§22-5) |
| 의존성 | webmvc·thymeleaf·data-jpa·flyway(+mysql)·actuator·mysql-connector·jackson-kotlin — 모두 Boot BOM 관리 카탈로그 항목. jOOQ 없음(CQRS 조회 요구 없음) | BOM |

### 13.2 domain

- `AuthenticationTransaction`(Aggregate): id(`sat_`+ULID), client, 바인딩 echo(paymentId·merchantRef·environment·금액·통화·Plan·cardCompany), state(원문 — 기관은 echo해야
  한다, TEST 전용 값), returnUrl, status(REGISTERED → AWAITING_CUSTOMER → AUTHENTICATED/FAILED/CANCELLED/EXPIRED), 선택한 가상 카드 참조, 결과 참조(`srr_`+128bit,
  UNIQUE), 결과(cardType·issuer·acquirer·provider·evidenceRef·authenticatedAt·validUntil), expiresAt. 결과는 한 번만 확정(append 의미), 이후 변경 없음.
- 시나리오 = 가상 카드 참조(카드번호 없음). Demo 카탈로그(코드 상수, 운영값 아님):

| 가상 카드 | issuer / acquirer / provider | cardType | 결과·조회 동작 |
|---|---|---|---|
| `VC-CREDIT-OK` | SIMISS-A / SIMACQ-A / SIMAUTH-1 | CREDIT | AUTHENTICATED |
| `VC-CHECK-OK` | SIMISS-A / SIMACQ-A / SIMAUTH-1 | CHECK | AUTHENTICATED |
| `VC-SPLIT-OK` | SIMISS-B / SIMACQ-A / SIMAUTH-2 | CREDIT | AUTHENTICATED(세 이름이 모두 다른 경로) |
| `VC-AUTH-FAIL` | SIMISS-A / SIMACQ-A / SIMAUTH-1 | CREDIT | FAILED |
| `VC-EXPIRED` | 〃 | CREDIT | 결과 validUntil이 이미 지남 → 조회 outcome EXPIRED |
| `VC-WRONG-AMOUNT` | 〃 | CREDIT | AUTHENTICATED, echo 금액 + 1 |
| `VC-WRONG-MID` | 〃 | CREDIT | AUTHENTICATED, echo merchantRef 변경 |
| `VC-STATE-MISMATCH` | 〃 | CREDIT | AUTHENTICATED, echo state 변경 |
| `VC-OTHER-ISSUER` | SIMISS-B / … | CREDIT | AUTHENTICATED, issuer ≠ 선택 카드사(선택이 SIMISS-A일 때) |
| `VC-UNSUPPORTED-TYPE` | 〃 | PREPAID | AUTHENTICATED, 미지원 cardType |
| `VC-LOOKUP-TIMEOUT-ONCE` | 〃 | CREDIT | 첫 조회만 read timeout을 넘겨 지연, 이후 정상 |
| `VC-LOOKUP-5XX-ONCE` | 〃 | CREDIT | 첫 조회만 503, 이후 정상 |

  고객 화면의 "취소" 버튼 → CANCELLED. 거래 TTL 경과 → EXPIRED. 중복 callback = 같은 복귀 URL 재요청, 늦은 결과 = PG 세션 TTL 뒤 복귀(시험 시계), 재사용 = 다른
  세션에 결과 참조·state를 끼움(시험 절차).
- 가상 시계: `SimulatorClock` Port(시험에서 조정). 결정 가능: 시나리오는 카드 참조로만 정해지고 난수 seed 없음.

### 13.3 TEST 전용 방어

- 등록 요청의 `environment ≠ TEST` → 400(ENVIRONMENT_NOT_SUPPORTED). PG 쪽 격리(§13.6)의 이중 방어.
- 등록의 `returnUrl`은 client별 설정 origin(PG checkout origin)과 경로 문법 `^/checkout/s/[A-Za-z0-9_-]{22}/authentication/return$`(query·fragment 없음)에 정확히 맞아야 한다
  (판정 m-1 handle 경로) → Simulator가 open redirect가 되지 않는다.

### 13.4 API 계약 v1(golden — §17.4)

| endpoint | 인증 | 요청 | 응답 |
|---|---|---|---|
| `POST /sim/v1/authentications` | Bearer(client 키) | 엄격 JSON: `paymentId`, `merchantRef`, `environment`, `amountMinor`(정수), `currency`, `plan`{`kind`, `months`?}, `cardCompany`, `state`, `returnUrl` | 201 `{authenticationTransactionId, customerUrl, expiresAt}`. **(client, state) 멱등**: 같은 state·같은 내용 → 같은 거래(200), 다른 내용 → 409 |
| `GET /sim/authenticate/{id}` / `POST …` | 없음(고객 화면, 거래 ID는 128bit 이상 난수) | 가상 카드 선택·인증/취소 | 303 `returnUrl?resultRef=…&state=…` |
| `GET /sim/v1/authentication-results/{resultRef}` | Bearer | — | 200 `{resultRef, authenticationTransactionId, outcome(AUTHENTICATED/FAILED/CANCELLED/EXPIRED), state, binding{…echo}, cardType?, issuerCode?, acquirerCode?, providerCode?, evidenceRef?, authenticatedAt?, validUntil}` / 404(없음·다른 client — 존재 비노출) |

- 형식: `resultRef` = `srr_` + base64url 22자(128bit), `state` = base64url 43자(256bit), 거래 ID `sat_` + base64url 22자. `cardType`은 **열린 토큰**
  `^[A-Z][A-Z0-9_]{0,31}$`(예: CREDIT·CHECK·PREPAID) — 계약은 PayCore 지원 여부를 정하지 않고, adapter는 원문 토큰을 넘기며 지원 판정은 domain R10이 한다(m-4).
- PG adapter는 `resultRef`를 경로에 넣기 전에 고정 문법을 다시 확인하고 경로 변수로 encode한다(m-3).
- 조회는 **읽기 전용·멱등**(같은 참조 → 같은 본문, outcome만 시각 함수 — validUntil 경과 뒤 EXPIRED). 조회 기록은 append-only `lookup_log`(시험 관찰용).
- payment-api ↔ Simulator 인증(AN-20): client별 Bearer 키(시험·로컬 Demo 키는 환경변수/시험 자원, 저장소 운영 키 없음), Simulator는 SHA-256 digest만 저장. 본문 서명 없음.
- PG adapter는 응답을 닫힌 필드 집합으로 엄격 파싱한다(모르는 필드·null·타입 오류·형식 위반 → Unverifiable(MALFORMED)).
- **outcome별 필수 필드(m-E, golden 계약)**: AUTHENTICATED ⇒ `cardType`·`issuerCode`·`acquirerCode`·`providerCode`·`evidenceRef`·`authenticatedAt` 전부 있음;
  FAILED·CANCELLED·EXPIRED ⇒ 그 여섯 필드 전부 없음. 위반은 adapter가 Unverifiable(MALFORMED) = R3(상태 불변)으로 바꾼다 — domain 결과 타입은 위반 조합을 만들 수 없다
  (R11에서 500 없음). golden에 위반 시험 벡터(AUTHENTICATED에 evidenceRef 없음, FAILED에 issuerCode 있음)를 둔다.

### 13.5 payment-api 연결(HTTP Adapter)

- `SimulatorCardAuthenticationAdapter`(`payment.adapter.integration.institution`): `RestClient.builder()`를 adapter가 직접 구성하고 요청 factory는
  `JdkClientHttpRequestFactory`(JDK `HttpClient`) — 둘 다 `spring-web`(webmvc starter에 이미 있음). Boot 4.1의 `RestClient.Builder` 자동 구성은 별도 모듈
  (`spring-boot-restclient`) 소관이라 주입받지 않고 새 의존성도 추가하지 않는다(n-7, AN-25 — GRADLE-DEPS에서 확인). connect/read timeout은 설정(Demo), 리다이렉트 따르지 않음.
  Port 결과: `Registered(ref, customerUrl)` / `RegistrationFailed(분류)` / `Found(ProviderAuthenticationResult)` / `Unverifiable(TIMEOUT·CONNECTION·SERVER_ERROR·NOT_FOUND·
  MALFORMED)`. 업무 예외를 던지지 않는다(AC1 §9.1).
- `customerUrl`은 설정된 Simulator 고객 origin + `/sim/authenticate/` + 거래 ID 문법일 때만 redirect(응답 조작으로 임의 redirect 불가).
- Adapter는 domain 상태 전이를 호출하지 않는다(기존 ArchUnit `institutionAdapterDoesNotMutateDomain`).
- Simulator와 PayCore 앱 사이에는 Gradle project 의존이 없다 — harness `test_simulator_isolation`이 `apps/institution-simulator/build.gradle.kts`에 `project(":apps:`가 없고
  PayCore 앱 build 파일이 `:apps:institution-simulator`를 참조하지 않음을 검사한다(n-5).
### 13.6 Simulator/REAL 격리(도출 — §22-2)

```yaml
paycore.authentication:
  backends:            # Payment environment → 인증 backend
    TEST: { type: SIMULATOR, profile: sim-auth-demo }
    # LIVE 없음 → LIVE Payment 결제창은 422 CHECKOUT_NOT_SUPPORTED
  profiles:
    sim-auth-demo:     # Demo — 운영값 아님
      version: sim-auth-demo-1
      card-companies: [ {code: SIMISS-A, name: 가상카드A}, {code: SIMISS-B, name: 가상카드B} ]
      plan-policy: { version: plan-demo-1, currency: KRW, installment-months: [2, 3, 6], minimum-installment-amount: 50000 }
  simulator: { base-url: …, customer-base-url: …, client-key: ${…}, connect-timeout: …, read-timeout: … }
```

| 설정 | 기동 |
|---|---|
| `TEST: SIMULATOR` | 허용 |
| `LIVE: SIMULATOR` | **기동 거부**(AC 5 "TEST 외 환경에서 Simulator 연결 기동 거부") |
| `type: REAL`(어느 환경이든) | 기동 거부 — REAL Adapter 없음(AC1 §9.4) |
| 모르는 type·profile 없음·Simulator URL에 userinfo | 기동 거부 |

- 해석(도출): 저장소의 "환경"은 Payment `environment`(TEST/LIVE, D-CPD-001)뿐이므로 AC의 "TEST 외 환경"은 LIVE다. D-CPD-001(TEST는 실기관 금지)·AC1 §9.4(현재
  어느 환경도 REAL 없음)·MS2 열린 항목(LIVE Gate에서 Simulator Route 차단)과 합치면, LIVE Payment는 지금 사용할 수 있는 인증 backend가 없어 fail-closed다. 배포
  단계 개념이 필요하면 별도 결정(§22-2).
- 방어 겹: (1) REAL Adapter 클래스 없음 + ArchUnit(`CardAuthenticationPort` 구현은 `adapter.integration.institution`의 `Simulator*`만, 위반 fixture
  `RealCardAuthenticationAdapter`), (2) 기동 검사, (3) LIVE Payment 토큰 발급 거절(stub 요청 0건 시험), (4) Simulator가 환경 ≠ TEST 거절, (5) egress 허용 목록은 배포 작업.

---

## 14. 동시성 사례(실 MySQL 다중 연결 — Mock 단독 금지)

| # | 경합 | 직렬화 지점 | 기대(시험) |
|---|---|---|---|
| C1 | 같은 토큰 동시 교환 8 | payment X → 토큰 X 재판정, `checkout_session.checkout_token_id` UNIQUE | 결제창 세션 1, 쿠키 1, 나머지 400 화면, 토큰 CONSUMED 1 |
| C2 | 같은 Payment 서로 다른 토큰 동시 교환 | payment X, `active_payment_id` UNIQUE | ACTIVE 1(나중 것), 이전 REVOKED |
| C3 | 같은 handle 두 탭 동시 인증 시작 | payment X, `open_payment_id` UNIQUE | 비종료 세션 1. 먼저 만든 세션 EXPIRED(SUPERSEDED). 그 탭의 TX-B → 변경 없음·409 "다른 창"(§7.4.1); TX-B가 이미 끝났으면 그 탭의 복귀 → P1 410 |
| C4 | 같은 결과 참조 동시 복귀 2 | 1단계 통과 2 → 조회 2(멱등) → TX-C payment X → S X에서 1단계 재판정 | 첫 TX-C VERIFIED, 둘째는 재판정 P2 → DUPLICATE 303(같은 successUrl·같은 authenticationRef). authenticationRef 1, DUPLICATE_RESULT 행 1 |
| C5 | 같은 state·서로 다른 결과 참조 동시 복귀 | 같음 | 하나만 VERIFIED, 다른 쪽은 재판정 P3(R0′) 400, VERIFIED 덮어쓰기 없음 |
| C6 | 복귀 TX-C vs 새 인증 시작 | payment X | 시작이 이기면 복귀 재판정 P1 410, 복귀가 이기면 VERIFIED 뒤 시작이 SUPERSEDED(증거 보존 EXPIRED) |
| C7 | 복귀 vs 주기 작업 만료 | control X → payment X(만료), payment X(복귀) | 만료가 이기면 S EXPIRED(PAYMENT_CLOSED) → 복귀 재판정 P1 410; 복귀가 이기면 VERIFIED 뒤 만료가 S EXPIRED(PAYMENT_CLOSED, 증거 보존)·Payment EXPIRED |
| C8 | 인증 시작 vs confirm(AUTH-001) | payment X | AUTH-001 시험으로 이관(기대: confirm이 이기면 시작 409, 시작이 이기면 confirm `AUTHENTICATION_NOT_VERIFIED`) |
| C9 | 토큰 발급 동시 2(가맹점 재시도) | control X → payment X, `outstanding_payment_id` UNIQUE | ISSUED 1, 다른 것 SUPERSEDED |
| C10 | 조회가 오래 걸리는 동안 | — | 조회 중 그 요청 연결에 열린 TX·잠금 없음(`performance_schema.data_locks`·`@@in_transaction`), 다른 결제창 요청이 대기하지 않음 |
| C11 | 교환 vs 주기 작업 만료 | payment X | 만료가 이기면 교환은 Payment EXPIRED를 보고 토큰 CLOSED·409; 교환이 이기면 결제창 세션 ACTIVE 뒤 만료가 EXPIRED(PAYMENT_CLOSED)·토큰은 이미 CONSUMED |
| C12 | 토큰 발급 vs 주기 작업 만료(시각 경과) | control X | 어느 쪽이 먼저든 Payment EXPIRED 1회, 발급은 409 `PAYMENT_TERMINATED`, ISSUED 토큰 0 |
| C13 | 주기 작업 동시 2(다중 인스턴스) | control X → payment X | 만료 전이 1회(종료 근거 1행), 다른 실행 NoChange |
| C14 | CreatePayment(다른 키·같은 주문) vs 주기 작업 만료 | control X | 어느 쪽이 먼저든 이전 Payment EXPIRED 1회 + 새 Payment generation+1 하나, 응답 키 집합 불변 |
| C15 | 인증 시작(TX-A) vs 주기 작업 만료 | payment X | 만료가 이기면 시작 409/410 화면·세션 없음; 시작이 이기면 세션 CREATED 뒤 만료가 EXPIRED(PAYMENT_CLOSED), 그 뒤 TX-B는 변경 없음 410 |
| C16 | 같은 Payment 만료 동시 시도(주기 작업 2 + 토큰 발급 inline + CreatePayment inline) | control X → payment X, Outbox `effect_key` UNIQUE | Payment EXPIRED 1회, 종료 근거 1행, **PaymentExpired 정확히 1건**(D-AUTHN-008), 진 쪽 NoChange·1062 없음 |
| C17 | (검토 #2 m-2로 삭제) 채우기는 기동 전략 한 곳에서만 한다 — 업무 경로·주기 작업의 채우기 경합이 없다. 여러 인스턴스 동시 기동은 pin PK·`WHERE checkout_expires_at IS NULL` UPDATE(멱등)로 수렴 | — | — |

장애 주입(`FaultInjectionConfig` 방식): TX-I·TX-X·TX-A·TX-C·만료 TX·채우기 TX의 각 Port flush 직후 예외 → 해당 TX의 모든 행 rollback(토큰·세션·이벤트·종료 근거·
PaymentExpired 고아 없음, Payment 상태·시각 원상), commit 단계 예외 → 같은 요청 재시도(주기 작업은 다음 주기)로 수렴.

## 15. 8대 원칙 증거 계획

| 원칙 | 적용 | 증거(시험·근거) |
|---|---|---|
| Financial Consistency | 적용(경계) | 인증은 금융 사실이 아니며 Payment는 REQUESTED 유지(전 흐름 뒤 payment·control·Outbox 불변). 바인딩 금액·통화 = Payment 생성 값(토큰·세션·기관 echo 3중 대조, R7). **만료**: REQUESTED만 EXPIRED, 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED 1행, PaymentExpired 1건(비금융), 새 Payment는 만료 뒤에만(generation+1), 재사용·채우기는 시각을 연장하지 않음. 결제 생성 API·응답 불변 |
| Transaction Safety | 적용 | 외부 호출 TX 밖(C10), 단계 TX·만료 TX별 flush 뒤 실패 주입 rollback, TX-C 실패 뒤 재조회 수렴, commit 불명확 503 → 재시도 안전 |
| Idempotency | 적용 | 토큰 1회 교환(C1), 효과 멱등 발급(C9·§5.4), 중복 복귀 DUPLICATE(C4), 결과 참조 1회(UNIQUE), 반복 이벤트 1행(§7.6), Simulator 등록·조회 멱등, 만료 재실행 NoChange(C13)·PaymentExpired effect_key 1건(C16)·채우기 1회(C17), Snapshot v3 재전송 DUPLICATE |
| Concurrency Control | 적용 | §14 C1~C7·C9~C15 실 MySQL, 생성 컬럼 UNIQUE 5개, payment 행 직렬화, control 보유 경로만 Payment 만료, 잠금 순서 §10.3 |
| Recoverability | 적용 | 조회 timeout·5xx → IN_PROGRESS 유지 → 재조회 VERIFIED, 등록 실패 → 재시도 대체, PG·Simulator 재시작 뒤 처리 동일, v2 head → 승격 뒤 해소, 만료 건 실패는 다음 주기에 다시 후보 |
| Reconciliation | **N/A(이유)** | 인증·결제창 만료는 기관 금융 효과가 아니다(AC1 §11; REQUESTED = 미전송). 기관 승인 대사는 AUTH-001·RECON-001. 대신 비권위 jOOQ 점검(TTL 지난 비종료 세션·미교환 토큰·시각 지난 REQUESTED 수) gauge |
| Auditability | 적용 | `authentication_session_event` append-only(1142), 토큰 발급 Credential·Site host·Snapshot·정책 버전, 만료 원인·실패 사유, 대체·만료된 VERIFIED 증거 보존(H-4), **Payment 결제창 유효기간 설정 버전 저장(V7 이전 행은 채운 시점 버전)**, PaymentExpired causation(요청·회차), 종료 근거 행, 보안 이벤트 로그 |
| Observability | 적용 | counter `paycore.checkout.tokens{result}`·`paycore.checkout.exchanges{result}`·`paycore.checkout.pages{result}`·`paycore.checkout.cancels{result}`·인증 세션 전이는 진입점별 `paycore.authentication.starts{result}`·`paycore.authentication.returns{result}`(전이 대응 아래 §15.1)·기관 호출 `paycore.authentication.registrations{outcome}`·`paycore.authentication.lookups{outcome}`·`paycore.payment.checkout_expiry{result}`, timer `paycore.authentication.lookup.duration`, gauge `paycore.payment.checkout_window.elapsed_requested`·`paycore.authentication.sessions.unresolved`·`paycore.checkout.tokens.unexchanged_expired`(label에 ID 금지 — label 이름은 result·outcome뿐), correlationId MDC, 로그 비노출 시험(§12.6). rev 1.3의 `paycore.authentication.sessions{transition}`은 편차 13(§24.14.1)으로 대체 |

### 15.1 인증 세션 전이 ↔ counter label(편차 13 — Coordinator 수용 2026-10-17)

전이 사실의 원본은 `authentication_session_event`(append-only)다. counter는 응답 시점에 한 번 세는 관찰값이며 rollback된 시도는 `unavailable`로 센다.

| counter `result`/`outcome` | 인증 세션 전이 |
|---|---|
| starts `redirected` | 새 세션 — → CREATED → IN_PROGRESS(TX-A·TX-B), 같은 Payment의 미폐기·미귀속 세션 → EXPIRED(SUPERSEDED/TTL_ELAPSED) |
| starts `registration_failed` | 새 세션 CREATED 유지(REGISTRATION_FAILED 이벤트) — 같은 Payment의 이전 세션 대체는 TX-A에서 이미 반영 |
| starts `superseded` | 변경 없음(다른 창의 시작이 이 세션을 대체 — C3) |
| starts `time_over` / `not_available` | 시각 경과(TX-A·TX-B)·TX-B의 Payment 종료: 자기 비종료 세션 → EXPIRED(PAYMENT_CLOSED)(control 미보유 — Payment 불변). TX-A의 Payment 종료: 인증 세션 변경 없음(결제창 세션만 COMPLETED(PAYMENT_NOT_AVAILABLE)). 이미 닫힌 결제창: 변경 없음 |
| starts `not_allowed`·`bad_request`·`session_expired`·`unavailable` | 변경 없음 |
| returns `verified` | IN_PROGRESS → VERIFIED(R11) |
| returns `failed` | IN_PROGRESS → FAILED(R5~R10) |
| returns `ignored` | 이미 FAILED·EXPIRED(P1, 변경 없음) 또는 IN_PROGRESS → EXPIRED(TTL_ELAPSED)(P6) |
| returns `unverifiable` | IN_PROGRESS 유지(R3) |
| returns `time_over` / `not_available` | → EXPIRED(PAYMENT_CLOSED)(P5) 또는 이미 닫힌 결제창이면 변경 없음 |
| returns `rejected`·`bad_request`·`session_expired`·`unavailable` | 변경 없음 |
| cancels `cancelled` | CREATED·IN_PROGRESS → FAILED(USER_CANCELLED) |
| cancels `time_over` / `not_available` | 시각 경과·Payment 종료를 취소 TX가 발견: 자기 비종료 세션 → EXPIRED(PAYMENT_CLOSED)(시각 경과일 때, Payment 불변). 이미 Payment 쪽 사유로 닫힌 결제창: 변경 없음 |
| cancels `session_expired`·`bad_request`·`unavailable` | 변경 없음 |
| registrations `outcome` = REGISTERED·TIMEOUT·CONNECTION·SERVER_ERROR·REJECTED·MALFORMED | 전이 아님 — 기관 등록 호출 결과(TX-B 전) |
| `paycore.payment.checkout_expiry{result=expired}` | control 보유 만료 — 미폐기·미귀속 세션 → EXPIRED(PAYMENT_CLOSED), Payment → EXPIRED |

한 label이 두 전이를 덮는 행(`ignored`, `time_over`/`not_available`)은 이벤트 행으로 구분한다. commit 뒤에 기록하는 정확한 전이 단위 counter(`TransactionSynchronization` afterCommit)는 후속 후보다(§24.14.1).

## 16. JPA·jOOQ·BOM·Kafka/Redis(이번 변경과의 연결 — 역할 → 예 → 이유 → 주의)

- **Entity/Domain 분리**: 역할 — `JpaAuthenticationSessionEntity`는 행 모양만, Mapper가 `AuthenticationSession.reconstitute`(존재할 수 없는 조합 거부)로 되돌린다. 예 —
  `status='VERIFIED', evidence_ref=NULL` 행은 DB CHECK가 먼저 막고, 우회돼도 `reconstitute`가 `IllegalArgumentException`. 이유 — 순수 domain + DB 이중 방어(관례 §9.1).
  주의 — Entity는 data class가 아니고 toString에 digest·증거를 넣지 않는다.
- **생성 컬럼 UNIQUE와 JPA**: 역할 — `open_payment_id` 같은 생성 컬럼은 Entity에 매핑하지 않거나 `insertable=false, updatable=false`로 읽기만 한다. 예 — 두 탭이 동시에
  CREATED를 만들면 두 번째 flush가 1062 → `UniqueConflict` → 새 TX 재판정에서 먼저 커밋된 세션을 보고 대체한다. 이유 — "Payment당 비종료 1"은 서로 다른 행 사이의
  불변식이라 `@Version`이 못 지킨다. 주의 — flush 실패 뒤 그 TX는 rollback 전용이라 같은 TX에서 재시도하지 않는다(관례 §9).
- **비관적 잠금(payment 행을 직렬화 지점으로)**: 역할 — 결제창 명령은 `PaymentRepository.lock`(`FOR UPDATE`)을 먼저 잡는다. 예 — 교환·시작·복귀·만료가 같은 Payment에서
  줄을 선다. 이유 — 세션·토큰·결제창 세션이 서로 다른 표라 한 행 잠금이 없으면 "REQUESTED 확인 + 세션 생성"이 confirm·만료와 엇갈린다. 주의 — 외부 조회·등록 동안
  잠금 없음(C10), 고정 순서(§10.3).
- **변경 감지 + `@Version`**: 역할 — 토큰 소비는 잠가 읽은 Entity의 `status`·`consumedAt`만 바꾸면 flush 때 `UPDATE … WHERE version = ?`. 예 — 동시 교환의 진 쪽은 잠금
  대기 뒤 CONSUMED를 보고 거절. 이유 — 1회 소비를 DB가 증명. 주의 — 새 행은 `persist`만(Spring Data `save`의 merge 금지 — 권한·덮어쓰기).
- **`@Immutable` insert-only**: `JpaAuthenticationSessionEventEntity` — 변경 감지가 UPDATE를 만들지 않고 runtime 권한도 SELECT·INSERT뿐(이중). cascade·orphanRemoval 없음.
- **flush vs commit / OSIV off**: 각 쓰기 Port 끝 flush로 제약 위반을 Port 안에서 드러내고, 화면에는 commit 뒤 DTO만 넘긴다(Thymeleaf가 Entity·LAZY에 닿지 않음).
- **jOOQ(CQRS)**: `JooqCheckoutViewReader`(결제창 화면 DTO: checkout_session + payment + 최신 인증 세션 상태 조인, 읽기 전용 TX 하나, 시각은 `DATE_FORMAT` 문자열 — 관례
  §9.1·§13)와 `JooqAuthenticationBacklogReader`(gauge). 둘 다 비권위 — 모든 상태 변경은 JPA 잠금 재판정. jOOQ 쓰기 금지 규칙 그대로.
- **BOM**: payment-api에 `spring-boot-starter-thymeleaf` 추가(카탈로그 기존 항목, Boot 4.1.1 BOM 관리 — Thymeleaf 3.1.x, internal-admin과 같다). Simulator는 BOM 관리
  starter만. HTTP client는 spring-web `RestClient` + JDK `HttpClient`(새 라이브러리 없음). 버전 override 없음. GRADLE-DEPS 3모듈·CVE-SCAN 필수(§18).
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 결제창 세션은 PAYMENT DB 행이다 — Redis 세션 저장소는 인프라·장애 모드만 늘리고 1회 교환·Payment당 하나 같은 불변식을
  대신 보장하지 않는다(원칙 §5). Snapshot v3 전파도 기존 Outbox → 서명 push → Inbox 같은 TX이며 브로커가 정합성을 대신하지 않는다.

---

## 17. 시험 계획

### 17.1 TDD 순서(각 단계 시험 먼저 → RED 확인 → 최소 구현)

1. domain: `MerchantReturnUrlSpec`(§8.1 표 전 행 + redirect 구성), `SecretDigestSpec`, `AuthenticationSessionSpec` 추가 행(상관 값·SUPERSEDED·PROVIDER_EXPIRED·providerValidUntil·
   단방향 불변식), `AuthenticationSessionStartSpec`(대체 + 실패 시 전 세션 불변), `AuthenticationResultCheckSpec`(§7.3 세션 선택 전제·P1~P6·R3·R5~R11 각 1행 이상, 재판정 행,
   기대값은 표에서 독립 기술), `PaymentPlanPrecheckSpec`, `PaymentCheckoutWindowSpec`·`RequestedPaymentExpirySpec` 추가 행(`expireIfElapsed`).
2. MASTER: `EntitlementSnapshotAssemblerSpec` v3, `SchemaPromotionV3Spec`, golden v3.
3. PAYMENT entitlement: `EntitlementContentV3Spec`, `SiteWebHostQuerySpec`(실 MySQL), `SchemaRolloutOrderV3Spec`.
4. persistence: `CheckoutSchemaSpec`(information_schema·CHECK 위반 INSERT·생성 컬럼 UNIQUE·단방향 규칙·collation), `DatabaseAccountSeparationSpec` 갱신, Repository/Store 왕복·
   append-only 1142·잠금 읽기, payment 결제창 시각 컬럼 왕복.
5. Simulator: 단위·MySQL·HTTP 계약·재시작 내구성·ArchitectureSpec(§13).
6. Adapter: `SimulatorCardAuthenticationAdapterSpec`(JDK `HttpServer` stub — 시험 전용), `AuthenticationBackendIsolationSpec`.
7. application(실 MySQL): `CreatePaymentCheckoutWindowSpec`·`CreatePaymentInlineExpirySpec`, `CheckoutTokenIssuanceSpec`, `CheckoutExchangeSpec`, `CardAuthenticationStartSpec`,
   `CardAuthenticationCompletionSpec`, `CheckoutRollbackSpec`, `RequestedPaymentExpiryServiceSpec`, 경합 `CheckoutConcurrencySpec`(C1~C6·C9·C10)·`CheckoutExpiryConcurrencySpec`
   (C7·C11~C15).
8. web(RANDOM_PORT 3 connector): `CheckoutTokenApiSpec`, `CheckoutConnectorIsolationSpec`, `CheckoutCookieSecuritySpec`(handle별 쿠키·동시 결제창 2개), `CheckoutCsrfSpec`,
   `CheckoutPageSecuritySpec`, `CheckoutOriginConfigurationSpec`(Host 헤더 조작 무시·기동 검증), `CheckoutTemplateScanSpec`, `CheckoutErrorPageLeakSpec`, `CheckoutLogRedactionSpec`.

RED 기록: 새 시험이 대상 없음으로 컴파일 실패(RED-C) + 주요 규칙 변이 RED(예: URL 포트 허용, 세션 선택을 state 없이, R3를 FAILED로, 대체 생략, 만료 inline 생략, control
미보유 경로가 Payment 변경, 토큰 원문 저장, CSRF 끔, `th:utext` 허용 → 대상 Spec 실패)를 §24에 남긴다(AUTH-CONTRACT §19.4 방식).

### 17.2 Named Fixture(Test Fixture Policy — `src/test`만)

- `CheckoutFixture`: `requestedPaymentWithWebSite(host)`, `issuedToken(payment)`, `openedCheckout(payment)`, `expiredToken()`, `consumedToken()`.
- `AuthenticationSessionFixture` 확장: `createdSession()`, `inProgressSession(providerRef)`, `verifiedUnboundSession()`(공식 `start`·`verify` 경로), `supersededSession()`.
- `ProviderResultFixture`: `authenticatedMatching(session)`, `authenticatedWithDifferentAmount(session)`(Invalid 의도 이름), `cancelled(session)`, `expiredAtProvider(session)`.
- `ReturnUrlFixture`: 허용/거절 표 행 상수(Demo host `shop.example.test`).
- `SnapshotFixture` 확장: v3 Site(WEB/APP/종료).
- 상태는 공식 factory·전이로만(reconstitute 금지, 단 Mapper 시험 제외). 연관 금액·상태를 독립 난수로 만들지 않는다. Fixture Monkey는 쓰지 않는다(핵심 보안 규칙은
  명시 Fixture). Instancio 없음.

### 17.3 ArchUnit 추가(위반 fixture 포함)

| 규칙 | 내용 | 위반 fixture |
|---|---|---|
| `cardAuthenticationPortImplementedOnlyBySimulatorAdapter` | main의 `CardAuthenticationPort` 구현은 `..adapter.integration.institution..`의 `Simulator*`만 | `archfixture.institution.RealCardAuthenticationAdapter` |
| `sessionCreateOnlyThroughAuthenticationSessionStart` | main의 `AuthenticationSession.create` 호출은 `AuthenticationSessionStart`만 | `archfixture.session.DirectSessionCreate` |
| `authenticationTransitionsOnlyInApplicationOrDomain` | `AuthenticationSession`의 `start`·`verify`·`fail`·`expire` 호출은 `..application..`·`..domain..`만 | `archfixture.session.WebAdapterVerifiesSession` |
| `checkoutWebUsesOnlyProvidedUseCases`(D-AUTHN-006, m-2, 검토 #3 m-A) | `payment.adapter.web`의 `Checkout*` 클래스(connector 구성 `CheckoutConnector*` 제외)는 `..payment.application.provided..`·`Checkout*` web 클래스·목록의 공용 web helper(`ApiResponses`·`ApiError`·`ApiErrorCode`·`RequestRejected`·`SafeThrowable`·`PaymentApiSettings` — domain·required·persistence 비의존을 별도 규칙이 확인)·JDK(`java..`·`kotlin..`)·Spring web 스택(`org.springframework.{web,http,stereotype,context,core,beans,boot,security,ui,util,validation}..`)·`org.thymeleaf..`·`jakarta.servlet..`·`org.slf4j..`·`tools.jackson..`·`io.micrometer..`·`org.jetbrains.annotations..`에만 의존. `CheckoutConnector*`만 Tomcat connector API를 더 쓸 수 있다 | `archfixture.checkoutweb.CheckoutControllerUsingDomain`(domain), `…UsingRequiredPort`(required), `…UsingJdbc`(JdbcTemplate), `…UsingDomainHelper`(목록 밖 helper), `…UsingTomcat`(connector 구성 밖 Tomcat) |
| `thymeleafOnlyInWebAdapter` | `org.thymeleaf..` 의존은 `adapter.web`만 | `archfixture.web.DomainUsesThymeleaf` |
| 기존 규칙 적용 | `institutionAdapterDoesNotMutateDomain`, `webAdapterUsesOnlyApplication`, `batchAdapterUsesOnlyApplication`(만료 scheduler — 빈 대상 허용을 false로), `domainIsPure`, 캐시 금지 규칙 | 기존 |
| Simulator | `PayCoreArchitectureRules` 공통 규칙을 base `com.paycore.simulator`로 적용 | 공통 meta-test |

### 17.4 계약 golden(두 앱 독립성)

- `harness/tests/test_authentication_contract_golden.py`(Python 표준 라이브러리)가 등록 요청·조회 응답 v1 golden을 만들어 `apps/payment-api`·`apps/institution-simulator`
  시험 자원과 바이트 비교. Simulator 시험은 자기 응답이 golden과 같음을, payment-api adapter 시험은 golden을 파싱해 Port 결과가 됨을 확인 → 프로세스 경계를 넘는
  계약을 한 파일로 고정(앱 간 Gradle 의존 없음).
- 같은 파일에 `test_entitlement_golden.py` v3 대상 추가.
- **host 문법 공유 벡터(n-f)**: `harness/tests/test_host_grammar_vectors.py`가 WEB host 문법 시험 벡터(허용·거절 + 사유 분류)를 Python 표준 라이브러리로 만들어
  `apps/master-api`(SiteChannel WEB)·`apps/payment-api`(Snapshot v3 수신 불변식, `MerchantReturnUrl` host 부분) 시험 자원 `host-grammar-vectors.json`과 바이트 비교한다.
  세 구현의 시험이 같은 파일을 읽어 판정이 갈라지지 않음을 보인다.
- 프로세스 둘을 함께 띄우는 자동 E2E는 VERIFY-GATE에 넣지 않는다(§20 R6) — compose 수동 smoke 절차를 2단계 기록에 남긴다.

---

## 18. 하네스 변경(새 실행 단위 — HARNESS-VERIFICATION-CONTRACT §7)

| 파일 | 변경 |
|---|---|
| `settings.gradle.kts` | `include(":apps:institution-simulator")` + 주석(소유 저장소 = Simulator 전용 MySQL) |
| `apps/institution-simulator/build.gradle.kts` | `paycore.kotlin-app` convention, 시험 의존(test-support·testcontainers·kotest·archunit) |
| `harness/commands.json` | GRADLE-CHECK `expectedReports`에 `apps/institution-simulator/build/test-results/test/TEST-*.xml`, `outputMustMatch`에 `^> Task :apps:institution-simulator:test$`, `minTests`·TEST-SUMMARY 하한 = 실측 합계(1220 + 증가분), TEST-SUMMARY argv에 결과 경로, `GRADLE-DEPS-INSTITUTION-SIMULATOR`(정해진 argv), HARNESS-SELFTEST `minTests` = harness 시험 수(golden 시험 추가분) |
| `harness/scripts/cve-scan.py` | `MODULES`에 `institution-simulator`(시험 전용 실행 단위여도 runtime SBOM 검사 — registry 시험이 강제) |
| `compose.yaml`·`docker/simulator-mysql/init/` | `simulator-mysql` 서비스(127.0.0.1 바인딩, 비밀번호 .env), DB·계정 초기화 스크립트(.env.example 갱신) |
| `IMAGE-DIGESTS` | 같은 `mysql:8.4.11` 이미지라 변경 없음 |
| `docs/repository/HARNESS-VERIFICATION-CONTRACT.md` §7 | 하한 기록 갱신 |
| `harness/tests/test_module_registry.py` | 변경 없음(등록이 맞으면 통과 — 등록 누락 RED를 먼저 확인) |

---

## 19. 작업 분해

| 단계 | 내용 | 의존 | 막힘 |
|---|---|---|---|
| S0 | 명세 rev 1.3(재검토 SPEC_PASS_WITH_FIXES 반영) | — | — |
| S1 | domain(§7.1·§7.3·§7.5.2·§8) TDD — `MerchantReturnUrl`·`SecretDigest`·Plan 사전 판정은 rev 1.0에서도 같아 먼저 진행 | S0(결과 판정·불변식·결제창 시각) | — |
| S2 | MASTER v3 조립·golden v3·승격 시험 | — | — |
| S3 | PAYMENT v3 해석·`SiteWebHostQuery`·adapter | S2(golden) | — |
| S4 | V7/V8(payment 시각 컬럼 포함)·V8_1 CHECK·JPA Repository/Store·Mapper·스키마/권한 시험 | S0, S1 | — |
| S5 | Simulator 실행 단위 골격·DB·하네스 등록(RED: registry 시험 실패 → 등록) | — | — |
| S6 | Simulator 인증 domain·API·화면·시나리오·golden·시험(handle 경로 returnUrl) | S5 | — |
| S7 | `CardAuthenticationPort`·HTTP adapter·격리 설정·stub 시험 | S1, S6(golden) | — |
| S8 | CreatePayment 결제창 시각 저장·inline 채우기/만료(PaymentExpired 포함), 토큰 발급 Use Case·공개 API·오류 | S3, S4 | — |
| S9 | checkout connector·보안 체인·교환·화면(jOOQ)·템플릿·헤더/쿠키/CSRF·origin 설정 시험 | S4, S8 | — |
| S10 | 인증 시작·결과 반영·취소·경합·rollback 시험 | S7, S9 | — |
| S11 | 결제창 만료 주기 작업(채우기 + 만료)·PaymentExpired·만료 경합(C7·C11~C17)·장애 주입·옛 볼륨 재실행 | S8, S10 | — |
| S12 | 지표·로그 비노출 시험, 문서(관례 새 절·DOMAIN-DEFINITION·HARNESS·MS2 §8.2), 하한 갱신, 전체 check → VERIFY-GATE·DEPS×3·CVE 보고 | 전부 | — |

Checkpoint(Coordinator 지시): S4 뒤, S7 뒤, S10 뒤 — Reviewer 중간 검토로 재작업 범위를 줄인다(단일 VERIFY-GATE는 유지).

## 20. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 작업량이 MS2의 약 2.5~3배(새 실행 단위 + PAYMENT 표 4개 + Use Case 6개 + 보안 웹 표면 + Snapshot v3 + 결제창 만료) — 단일 gate 실패 시 재작업 큼 | §19 checkpoint, 단계별 모듈 시험 실행 |
| R2 | checkout 보안 체인 `securityMatcher` 순서 실수로 `/v1/**` 인증 우회 | `CheckoutConnectorIsolationSpec` |
| R3 | CSP `form-action` redirect 검사는 브라우저별 차이, 실제 브라우저 시험 없음 | 헤더 정확 값 시험, 남는 위험 기록. 브라우저 시험은 후속 |
| R4 | GET 토큰 교환이 링크 미리보기·prefetch로 소비 | 짧은 TTL·즉시 redirect·no-store, 소비 시 가맹점 재발급(효과 멱등) |
| R5 | v3 배포 순서 역전 시 head 막힘 | MS2 §5.4 절차 재사용, `SchemaRolloutOrderV3Spec` |
| R6 | 프로세스 간 자동 E2E가 gate에 없음 | 공유 golden 계약 + 각자 계약 시험 + compose smoke 기록 |
| R7 | 새 실행 단위·MySQL 컨테이너 추가로 gate 시간·메모리 증가 | Simulator 시험 컨테이너 1개 공유, `maxParallelForks=1` 유지 |
| R8 | V7/V8 사용으로 AUTH-001 migration 번호 이동 | Coordinator 기록(AUTH-001 V9부터) |
| R9 | 시각이 지난 REQUESTED를 control 미보유 경로가 만나면 Payment는 다음 control 보유 경로까지 REQUESTED로 남는다 | 화면은 결제창 시각으로 거절(Payment 상태 판단은 시각 기준), 주기 작업 간격(Demo)·gauge로 적체 관찰. 그 사이 새 Payment 생성은 CreatePayment inline 만료가 처리 |
| R10 | SameSite=Lax + GET 복귀의 위조 요청 | state 256bit로만 세션 선택(§7.3), 쿠키·handle 결합, 상태 불변 거절 |
| R11 | 앱카드·ISP 앱 전환 복귀(다른 브라우저 컨텍스트)는 쿠키가 없어 처리 불가 | 실제 기관 연동(LIVE Gate)에서 재설계 — 지금은 fail-closed |
| R12 | **login-CSRF·세션 바꿔치기(n-2)**: 공격자가 자기 Payment의 토큰 URL을 피해자에게 열게 하면 피해자 브라우저에 공격자 결제창 쿠키가 생긴다. 금액·주문은 공격자 가맹점·주문이라 피해자의 금융 효과는 피해자가 그 화면에서 직접 인증할 때뿐이고, 화면이 주문명·금액·Site host를 보여 준다. handle별 쿠키라 피해자의 다른 결제창을 덮어쓰지 않는다 | 남는 위험으로 기록(토큰은 가맹점 서버가 즉시 redirect, 짧은 TTL). 교환 전 확인 화면 같은 추가 방어는 후속 후보 |
| R13 | (검토 #2 m-2·#3 m-B) V8_1을 미루는 상태는 없다 — 채우기 실패면 기동하지 않는다. 남는 경합: 채우기 커밋과 V8_1 ALTER 사이에 **AUTHN-001 이전 인스턴스**가 시각 없는 REQUESTED를 넣으면 ALTER가 3819로 실패할 수 있다 → 전략이 ALTER 직전에 다시 확인해 다시 채우고(최대 3회), 그래도 남으면 V8_1을 시도하지 않고 기동을 거부한다(실패 기록 없음). **배포 조건**: V7→V8_1 업그레이드 동안 AUTHN-001 이전 payment-api 인스턴스가 떠 있지 않아야 한다(구 인스턴스를 먼저 내리고 새 인스턴스를 올린다 — rolling 혼재 금지) | 기동 실패 로그(분류 vendorCode)로 관찰 |
| R14 | MySQL 8.4 `ALTER TABLE … ADD CONSTRAINT … CHECK`는 기존 행 전체를 검증하며 online DDL 표상 테이블 재구성·동시 DML 차단일 수 있다(8.4 문서 online DDL 표로 확인 필요) | LIVE 운영 메모: 큰 payment 표에서는 유지보수 창에서 기동(V8_1 적용). 개발·시험 볼륨은 작다 |

## 21. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| AN-01 | 토큰 API 경로의 paymentId 형식 오류도 404 | 존재·형식 oracle 제거(CP-API §7.5) |
| AN-02 | 토큰 API는 Idempotency-Key를 받지 않고 있으면 400(검토 n-8 — Coordinator 판정 유지) | 원문 재생 불가(D-AUTHN-001), 효과 멱등(§5.4), 엄격 파서 관례 |
| AN-03 | 본문 닫힌 필드 2개, CP-API 엄격 파서 재사용 | 기존 관례 |
| AN-04 | 미교환 토큰 Payment당 1 | 유효 비밀 최소화 |
| AN-05 | 새 오류 code 6개와 고정 문구(`CHECKOUT_NOT_SUPPORTED`·`CHECKOUT_UNAVAILABLE` 구분) | 공개 API 표현 선택 |
| AN-06 | 토큰 교환은 GET + 303(PRG) | D-AUTHN-001 진입 방식 |
| AN-07 | 새 인증 시작이 미폐기·미귀속 세션(VERIFIED 포함)을 SUPERSEDED(증거 보존) | 사용 가능 근거 Payment당 1 — Coordinator 유지 판정 |
| AN-08 | 인증 실패는 선택 화면, failUrl code 4종 | AC1 §3 재인증 허용 |
| AN-09 | 틀린 state·세션 없음·CREATED·증거 충돌·결과 참조 404는 상태 불변, 기관 확인 불일치만 FAILED | GET 위조 DoS 방지, AC1 §5.3 |
| AN-10 | 반환 URL 엄격 부분집합(정규화 없음, host는 SiteChannel 문법) | D-MD-009 정신, 파서 차이 제거 |
| AN-11 | 토큰 발급은 host만 판정(권리 재평가는 confirm). 주 1(검토 n-6): `SiteWebHostQuery`는 MID–Site Assignment 상태를 보지 않는다 — Snapshot의 Site는 같은 Merchant의 Site이므로 그 host로 redirect해도 다른 Merchant 도메인이 아니어서 허용한다(Assignment 판정은 confirm의 권리 판정). 주 2: MID 차단·계약 종료 뒤에도 결제창이 열릴 수 있으나 confirm이 거절한다 — 금융 효과 없음, 인증까지 마친 고객이 거절되는 UX 손실은 남는 위험 | 권위 판정은 승인 시작 |
| AN-12 | 잠금 순서 CA-05 뒤에 checkout_session → checkout_token, 만료 inline은 control 보유 경로만 | 기존 순서 보존·확장 |
| AN-13 | 기존 `payment` 모듈에 배치 | §11.1 |
| AN-14 | 토큰·결제창 세션은 Store(프로토콜 기록), AuthenticationSession은 Repository | §14 판정 기준 |
| AN-15 | checkout 전용 connector + 3-connector 진입 필터 | CP-API §8 심층 방어 재사용, D-AUTHN-006 |
| AN-16 | 쿠키 SameSite=Lax, `__Host-` 접두 | 복귀 GET 쿠키 필요 |
| AN-17 | CSRF 토큰은 쿠키 비밀에서 파생(저장 없음) | 원문 비밀 미저장 |
| AN-18 | CSP form-action에 Simulator·Site origin(세션별, 설정 origin) | 폼 뒤 redirect 허용 최소화 |
| AN-19 | Simulator 별도 MySQL 인스턴스, runtime은 DB 단위 DML | 외부 기관 대역 |
| AN-20 | PG ↔ Simulator는 client별 Bearer 키(digest 저장), 본문 서명 없음 | TEST 대역 |
| AN-21 | 인증 프로파일(카드사 allowlist·Plan 정책 Demo)은 PAYMENT 로컬 버전 설정, 세션에 버전 저장 | D-MS2-004, D-AUTH-009 |
| AN-22 | 식별자 접두 `ctk_`·`cks_`·`aus_`·`sat_`·`srr_`·`aref_`, 비밀 digest 접두 `cth1:`·`cks1:`·`std1:`·`rrd1:` | 기존 접두 관례 |
| AN-23 | 결제창 세션은 절대 TTL만(idle TTL 없음) | 단순성 |
| AN-24 | redirect 구성 결과는 2048 제한 대신 8192 상한 + 같은 문법 재검증 | 저장 한계 URL도 redirect 가능 |
| AN-25 | `RestClient`를 adapter가 직접 구성(JDK HttpClient factory), Boot `RestClient.Builder` 자동 구성 미사용 | 새 의존성 없음(n-7) |
| AN-26 | 반복 가능한 인증 이벤트는 세션·종류당 1행 | 행 무한 증가 방지(n-4) |
| AN-27 | 결제창 handle 128bit·경로 + handle별 쿠키 이름 | Coordinator 판정 m-1의 구현 형식 |
| AN-28 | 주기 작업 trigger는 `adapter.batch`의 `@Scheduled`(Spring Batch 미사용), 건별 TX | 기존 계층 규칙 안, 다중 인스턴스는 DB 잠금 직렬화 |
| AN-29 | V8_1 CHECK는 payment-api `FlywayMigrationStrategy`가 한 기동 안에서 V8 → pin → 채우기 → 나머지 순서로 적용(검토 #2 m-2), 채우기 기준은 첫 기동 pin, `NO_WINDOW`는 결함 방어로만 유지 | D-AUTHN-007 "배포 시점 버전·채운 뒤 CHECK"를 migration 상수·수동 절차·실패 migration·뒤 migration 보류 없이 지킨다(m-A·m-B·m-2) |
| AN-30 | 모든 control 보유 만료 경로가 `RequestedPaymentClosing` 한 단계를 공유(PaymentExpired 포함) | 경로별 조합 차이 방지, AUTH-001 confirm 재사용 |

## 22. 범위 관찰(Coordinator 처리 결과)

1. **A01 `payment-window` 편차 — 승인(D-AUTHN-006)**: 기준 01은 결제창을 DB 없는 별도 실행 단위로 둔다. 사용자가 payment-api 안 결제창 전용 connector·별도
   SecurityFilterChain을 **승인된 기준 편차**로 결정했다. 조건: 화면 코드는 provided Use Case만 호출(ArchUnit `checkoutWebUsesOnlyProvidedUseCases` + 위반 fixture, §17.3),
   서비스 간 API·인증 신설 없음. 실행 단위 분리는 후속 후보(tasks.json openItems).
2. **"TEST 외 환경" = Payment environment 축 — 승인**: LIVE Payment 결제창·Simulator 거절(fail-closed, §13.6).
3. **AN-07 — 구현 결정으로 유지(Coordinator)**.
4. **migration 번호 — 기록됨**: PAYMENT V7/V8은 AUTHN-001, AUTH-001은 V9부터(AUTH-001 AC). MS2 §8.2 문구는 S12 문서 갱신에서 고친다.
5. **Simulator DB 권한**: AUTH-001 가상 VAN 원장 도입 때 append-only 권한 재검토 — 후속 기록됨.
6. **보존·삭제**: 범위 밖 — openItems 기록됨.
7. **앱 전환 인증 복귀**: LIVE Gate 항목 — openItems 기록됨.
8. **AUTH-001 인계**: confirm TX1은 control·payment 잠금 뒤 **먼저** `isCheckoutWindowElapsed(now)`를 확인해 경과면 `RequestedPaymentExpiry`(inline, 같은 TX)를 실행하고
   `PAYMENT_TERMINATED`로 거절한다(§7.5.3). 그 밖: `authenticationRef`로 세션을 찾고(UNIQUE — 대체·만료된 행도 찾히며 `bindingRejection`이 NOT_VERIFIED), `bindingRejection`
   (providerValidUntil 포함)·`bindTo`를 쓴다. `bound_authorization_id` 쓰기 경로는 AUTH-001. C8 경합 시험은 AUTH-001로 이관.
9. **문서 갱신 위치**: S12에서 `ARCHITECTURE-CONVENTIONS.md`(결제창·인증 절), `DOMAIN-DEFINITION.md`(AuthenticationSession·결제창 시각), `HARNESS-VERIFICATION-CONTRACT.md` §7,
   MS2 §8.2.
10. **AUTH-001 인계(검토 #2 m-2)**: PAYMENT의 뒤 migration(V9~)은 기동 전략의 순서 "V8 → pin·채우기(runtime 계정) → 나머지 전부"에 기댄다. V9 이후 migration이
    `payment`의 결제창 시각 컬럼·V8_1 CHECK를 전제해도 되며, 전략을 바꾸거나 다른 `FlywayMigrationStrategy`를 두면 이 순서를 유지해야 한다. 채우기는 V7 이전 REQUESTED 행만
    대상이고 Payment 상태를 바꾸지 않는다(만료는 주기 작업).
11. **배포 메모(검토 #3 m-B)**: V7→V8_1 업그레이드(AUTHN-001 첫 배포)는 AUTHN-001 이전 payment-api 인스턴스를 모두 내린 뒤 새 인스턴스를 올린다(rolling 혼재 금지 — 구
    인스턴스는 결제창 시각 없는 REQUESTED를 만든다). 전략의 V8_1 직전 재확인·재채우기(최대 3회)는 방어선이며, 계속 남으면 기동을 거부한다(§20 R13).

## 23. 결정 기록과 미결

### 23.1 사용자 결정(2026-10-16)

| 결정 | 질문 | 채택 | 이 명세 반영 |
|---|---|---|---|
| D-AUTHN-005 | Q-AUTHN-05 결제창 만료 기준·범위 | 권장안 A: 생성 시 절대 만료 시각 고정 저장, 이번 작업이 만료 Use Case·주기 작업·경합 시험, 토큰·세션 만료 자르기, CreatePayment API·응답 불변 | §1, §7.5, §10.1, §14 C7·C11~C15, §15, §19 S8·S11 |
| D-AUTHN-006 | Q-AUTHN-06 결제창 위치 | 권장안: payment-api 별도 connector·SecurityFilterChain(기준 01 편차 승인), 화면은 provided Use Case만 | §3-1, §12.1, §17.3, §22-1 |

### 23.2 Coordinator 판정(2026-10-16)

- **U-3**: control을 쥔 경로(CreatePayment·TX-I·주기 작업·confirm)는 결제창 시각이 지난 REQUESTED를 inline으로 만료한 뒤 진행한다(D-AUTHN-005 + D-CPD-002에서 도출) — §7.5.3.
- **m-1**: checkout handle을 경로에 두고 handle별 쿠키 — §6.1·§12.2·§13.3.
- §22 (2)(3)(4) 처리 — §22.

### 23.3 사용자 결정(rev 1.2 — 2026-10-16)

| 결정 | 질문 | 채택 | 이 명세 반영 |
|---|---|---|---|
| D-AUTHN-007 | Q-AUTHN-07(U-1) V7 이전 REQUESTED 행 만료 시각 | 권장안: 배포 시점 설정 버전의 유효기간으로 `created_at + 유효기간`을 애플리케이션 단계·만료 작업이 기록(버전 기록, migration 상수 아님), 그 뒤 CHECK, 이미 지난 건은 다음 만료 작업에서 EXPIRED | §7.5.6, §10.1, §2 AC 11 |
| D-AUTHN-008 | Q-AUTHN-08(U-2) PaymentExpired 이벤트 | 권장안: 만료와 같은 PAYMENT 로컬 TX에서 Outbox append(INTEGRATION_NON_FINANCIAL, effect_key `PaymentExpired:{paymentId}`), payload에 paymentKey·카드정보·Secret·개인정보 없음 | §7.5.7, §14 C16, §2 AC 12 |

Coordinator 판정(rev 1.2): n-8 Idempotency-Key 400 유지(§5.1·AN-02), §24.2 host 문법 정렬 수용. 남은 미결 없음.

Coordinator 판정(rev 1.3 재검토): m-A migration 전략 가드 채택, m-B 첫 기동 pin 채택, m-D option 1(VERIFIED 뒤 쿠키 유지) 채택 — 처리는 §24.5.

## 24. 2단계 편차·기록(구현 중 갱신)

### 24.1 의도된 불변식 변경(H-4 — 완화 아님, Reviewer 확인 대상)

| 대상 | rev 1.0·기존 코드 | rev 1.1 | 왜 완화가 아닌가 |
|---|---|---|---|
| `AuthenticationSession.reconstitute`·DB CHECK | `(status == VERIFIED) == (verified != null)` | VERIFIED ⇒ 증거·authenticationRef·resultRefDigest·verifiedAt; 증거·authenticationRef ⇒ VERIFIED 또는 EXPIRED; resultRefDigest ⇒ VERIFIED·FAILED·EXPIRED | 기존 규칙에서는 `expire()`가 VERIFIED 세션을 EXPIRED로 바꿔도 증거가 남아 그 세션을 **저장·복원할 수 없었다**(존재 가능한 상태를 표현 못 함). 새 규칙은 증거를 가진 EXPIRED만 추가로 표현하며, 승인에 쓸 수 있는 상태(VERIFIED·미귀속·유효기간 내)는 넓어지지 않는다 — `bindingRejection`은 status ≠ VERIFIED면 NOT_VERIFIED. VERIFIED의 필수 값은 오히려 늘었다 |
| `failure` | `failure == null ∨ FAILED` | failure ⇔ FAILED | 더 엄격 |
| `expiryCause`·provider ref | 없음 | expiryCause ⇔ EXPIRED, CREATED ⇒ ref 없음, IN_PROGRESS·VERIFIED ⇒ ref | 새 컬럼의 정합 규칙 |

### 24.2 반환 URL host 문법

§8.1의 host 문법을 SiteChannel WEB 문법과 같게 좁혔다(한 label·하이픈 시작/끝 거절). 허용되는 최종 결과(host 일치)는 같고 오류 code만 422 → 400으로 앞당겨진다.

### 24.3 rev 1.1 전에 작성한 작업 트리 시험(미커밋, 코드 미구현 — RED-C 확인 전)

`ReturnUrlFixture`, `MerchantReturnUrlSpec`, `MerchantReturnUrlUriCrossCheckSpec`, `SecretDigestSpec`, `PaymentPlanPrecheckSpec`(rev 1.0과 같아 유지),
`AuthenticationSessionSpec` 추가 행·`AuthenticationSessionStartSpec`·`AuthorizationFixture` 갱신(rev 1.1 불변식 — 재검토 뒤 확정). `AuthenticationResultCheckSpec`은 rev 1.1 §7.3으로
작성 예정.

### 24.4 RED·실행 기록

(구현 단계에서 명령·exit·시험 수·보고서 경로를 단계별로 추가한다.)

### 24.5 rev 1.3 재검토 처리(SPEC_PASS_WITH_FIXES, 2026-10-16)

| 항목 | 처리 | 위치 |
|---|---|---|
| m-A V8_1 가드 | 두 단계 수동 배포·실패 migration 가드를 버리고 payment-api `FlywayMigrationStrategy`가 NULL REQUESTED가 있으면 V8까지만 적용 + 신호, 다음 기동에 자동 적용. 시험 계획 갱신(실패 migration 없음). R13 교체, MySQL 8.4 ADD CHECK 운영 메모 R14 추가, AN-29 교체 | §7.5.6, §10.1, §20, §21 |
| m-B 버전 drift | V7 뒤 첫 기동이 `payment_checkout_window_pin` 한 행에 활성 설정 버전·유효기간을 고정, 모든 채우기가 pin 값 사용, pin을 읽거나 쓸 수 없으면 기동 실패. 새 표 → V7·V8 권한(SELECT, INSERT) | §7.5.6, §10.1, §10.2 |
| m-C JPA 갱신 | 시각 컬럼은 updatable 매핑, 값이 있는 행의 변경은 domain이 막음, 채우기 → 재조회 왕복 시험, §10.2 문구 수정 | §7.5.6, §10.1, §10.2 |
| m-D 쿠키 | COMPLETED(VERIFIED_REDIRECT)에서 쿠키 유지 → 순차 중복 P2 303, 그 밖 종료 상태에서 삭제 | §12.2 |
| m-E 필수 필드 | outcome별 필수·금지 필드를 golden 계약에 명시, 위반 → MALFORMED(R3), R7 issuer 비교는 AUTHENTICATED에서 항상, 위반 시험 벡터 | §7.3 R7, §13.4 |
| n-a | §11.1 규칙 이름, §19 S0, 입력 표 시점, 결정 행 상태(D-AUTHN-007·008 작업 트리) | 머리·§11.1·§19 |
| n-b | repeat_key에 detail_code 포함 | §7.6, §10.1 |
| n-c | 기존 `verify` 만료 분기가 TTL_ELAPSED 원인 기록 | §7.1 |
| n-d | inline 만료는 커밋되는 결과 값(예외 아님) | §7.5.3 |
| n-e | CreatePayment inline 만료 TX의 Outbox 순서 PaymentExpired → PaymentRequested | §7.5.3 |
| n-f | host 문법 공유 벡터 harness 시험 | §17.4 |
| n-g | `duration ≤ 0` 기동 실패 | §7.5.1 |
| n-h | CHECK 채택: EXPIRED ∧ result_ref_digest ⇒ evidence_ref | §10.1 |

### 24.6 RED·실행 기록(S1 일부 — 재검토와 무관한 domain 값)

| 시각(UTC) | 단계 | 명령(WSL, `./gradlew --console=plain …`) | 결과 | 증거 |
|---|---|---|---|---|
| 2026-10-16T05:02:45Z | RED-C | `:apps:payment-api:compileTestKotlin` | exit 1, 컴파일 오류 168건 — `ReturnUrlRejection` 49, `SecretDigest` 12, `SecretKind` 10, `precheck` 7, `withQueryParameters` 7, `ReturnUrlParse`·`MerchantReturnUrl`·`host`·`hasHost`·`value` 등(대상 타입 없음) | `~/authn-evidence/redc-payment-20261016T050319Z.log` |
| 2026-10-16T05:06:01Z | 변이 RED | 4 변이 적용(`hasHost`를 `endsWith`로, host `:` → PORT 판정 제거, 할부 최소금액 경계 `<`→`<=`, digest hex 대문자) 후 `:apps:payment-api:test --tests …MerchantReturnUrlSpec …SecretDigestSpec …PaymentPlanPrecheckSpec …MerchantReturnUrlUriCrossCheckSpec …PaymentPlanSpec` | exit 1. MerchantReturnUrlSpec 11 중 2 실패(포트 행 사유, `www.` 서브도메인 일치), SecretDigestSpec 6 중 2 실패, PaymentPlanPrecheckSpec 5 중 1 실패, 기존 PaymentPlanSpec 6 중 1 실패(경계), CrossCheck 2 통과(변이가 host 해석을 바꾸지 않음) | `~/authn-evidence/mut-s1/` |
| 2026-10-16T05:07:08Z | GREEN | 변이 되돌림 후 `:apps:payment-api:test --tests 'com.paycore.payment.payment.domain.*' --tests …MerchantReturnUrlUriCrossCheckSpec --tests …ArchitectureSpec` | exit 0, 보고서 21개, 시험 407, 실패·오류·skip 0(기존 domain·ArchitectureSpec 포함) | `~/authn-evidence/green-s1/` |

- 차단된 시험 변경(`AuthenticationSessionSpec` 추가 행·`AuthorizationFixture` 새 서명·`AuthenticationSessionStartSpec`)은 S1 재개 전까지 작업 트리 밖(`~/authn-blocked/`)에 두었다 —
  payment-api 시험 컴파일을 막지 않기 위해서이며 기존 파일은 HEAD 내용 그대로다.

실행 기록(계속 — S1 나머지·S2·S3·S5):

| 시각(UTC) | 단계 | 명령(WSL, `./gradlew --console=plain …`) | 결과 | 증거 |
|---|---|---|---|---|
| 2026-10-16T05:14:45Z | RED-C(S1 세션·판정·결제창 시각) | `:apps:payment-api:compileTestKotlin` | exit 1, 컴파일 오류 360건(`verify`·`create`·`start` 새 서명, `AuthenticationResultCheck`·`ResultVerdict`·`AuthenticationEventDetail`·`CheckoutWindow`·`expireIfElapsed`·`expiryCause` 등 없음) | `~/authn-evidence/redc-s1b-*.log` |
| 2026-10-16T05:18:40Z | 변이 RED(S1) | 4 변이(대체를 비종료 세션만 — AN-07 생략, R3 확인 불가를 FAILED로, P5 결제창 시각 판정 제거, `bindingRejection`의 providerValidUntil 무시) 후 `:apps:payment-api:test --tests 'com.paycore.payment.payment.domain.*' --tests …ArchitectureSpec` | exit 1, 453 중 4 실패 — 각 변이의 대상 시험 1건씩(StartSpec VERIFIED 대체, ResultCheck R3, ResultCheck P5, SessionSpec min 경계) | `~/authn-evidence/mut-s1b/` |
| 2026-10-16T05:22:29Z | GREEN(S1 + 전체 payment-api) | `:apps:payment-api:test` | exit 0, 보고서 65, 시험 811, 실패·오류·skip 0 | `~/authn-evidence/green-s1b/` |
| 2026-10-16T05:24Z | RED(golden v3, harness) | `python3 harness/tests/test_entitlement_golden.py`(v3 생성 추가, 파일 없음) | exit 1(failures=1, errors=1) → MASTER v2 golden 삭제 + `--write` → exit 0(4 시험) | `~/authn-evidence/golden-v3-red.log`, `golden-v3-green.log` |
| 2026-10-16T05:25:52Z | RED-C(S2 MASTER v3) | `:apps:master-api:compileTestKotlin` | exit 1, `SiteState` 인자 수 4건 | `~/authn-evidence/redc-s2-*.log` |
| 2026-10-16T05:26:40Z | 변이 RED(S2) | APP 채널 직렬화에 식별값 포함 변이 후 `:apps:master-api:test --tests …EntitlementContractGoldenSpec --tests …EntitlementSnapshotSpec` | exit 1, 17 중 4 실패(golden 내용·봉투, APP 식별값 미포함, v3 채널 직렬화) | `~/authn-evidence/mut-s2/` |
| 2026-10-16T05:29:30Z | GREEN(S2 전체 master-api) | `:apps:master-api:test` | exit 0, 보고서 33, 시험 318, 실패·오류·skip 0 | `~/authn-evidence/green-s2/` |
| 2026-10-16T05:33:09Z | RED-C(S3 PAYMENT v3) | `:apps:payment-api:compileTestKotlin` | exit 1, 71건(`SiteWebHostDecision`·`SiteWebHostResult`·`SiteChannelEntry`·`SiteWebHostPort` 등 없음) | `~/authn-evidence/redc-s3-*.log` |
| 2026-10-16T05:36:35Z | 변이 RED(S3) | 2 변이(종료 Site host 허용, WEB host IP 리터럴 허용) 후 entitlement domain·golden·SiteWebHostQuerySpec | exit 1, 60 중 4 실패 — 변이 대상 2건(종료 Site, `127.0.0.1` 행) + 시험 결함 2건(Site 목록 순서에 의존한 data class 비교 — 정규 내용·정렬 비교로 고침, 기대값 변경 아님) | `~/authn-evidence/mut-s3/` |
| 2026-10-16T05:40:36Z | GREEN(S3 전체 payment-api) | `:apps:payment-api:test` | exit 0, 보고서 68, 시험 824, 실패·오류·skip 0 | `~/authn-evidence/green-s3/` |
| 2026-10-16T05:45Z | RED(S5 harness 등록) | settings에 `:apps:institution-simulator` 추가 후 `python3 harness/tests/test_module_registry.py` | exit 1, 9 중 6 실패(GRADLE-CHECK 보고서·task 줄, TEST-SUMMARY 경로, DEPS 명령, CVE MODULES, SELFTEST 하한) → 등록 후 HARNESS-SELFTEST 77 OK | `~/authn-evidence/registry-red.log`, `harness-selftest-s5.log` |
| 2026-10-16T05:49:05Z | GREEN(S5 Simulator 골격) | `:apps:institution-simulator:check` | exit 0, 시험 12(아키텍처 10, 기동·계정 2), `verifyRuntimeHasNoTestTools` 102 runtime 모듈 | `~/authn-evidence/green-s5/`, `deps-simulator.txt` |

### 24.7 구현 편차·결정(S1~S3·S5)

| # | 내용 | 이유 |
|---|---|---|
| 1 | `Payment.request`는 시각 없는 4인자 판과 `CheckoutWindow` 5인자 판이 함께 있다. S8에서 CreatePayment가 5인자 판으로 바뀌면 4인자 판을 지운다. `Payment.reconstitute`의 `checkoutWindow`는 S4 Mapper 전까지 기본값 null | 영속 컬럼(S4)·설정 Port(S8) 전 단계적 도입. 업무 경로(CreatePayment)는 아직 시각을 저장하지 않는다 — S8 완료 조건 |
| 2 | `AuthenticationResultCheck`는 domain 판정 결과로 `ResultVerdict`(PROCEED·IGNORED·DUPLICATE·REJECTED·PAYMENT_CLOSED·SESSION_EXPIRED·UNVERIFIABLE·FAILED·VERIFIED)와 이벤트 종류·상세 분류를 돌려준다. 화면 매핑은 application/web(§6.3) | 판정 규칙을 Spring 없이 표로 시험 |
| 3 | 기관 결과 domain 값 `ProviderAuthenticationResult`(state echo는 digest, AUTHENTICATED ⇔ `AuthenticatedDetails` — m-E)와 `ProviderResultLookup`(Found·Unverifiable) | domain이 기관 DTO에 의존하지 않는다 — adapter가 엄격 파싱해 만든다(S7) |
| 4 | MASTER v2 golden 파일 삭제(v3로 대체), PAYMENT는 v1·v2·v3 golden 유지. harness `test_v2_golden_files_of_both_apps_equal_the_independent_generation`은 이름을 유지하고 대상을 PAYMENT로 줄이며 MASTER v2 부재를 확인 | MS2의 v1 선례와 같다(MASTER는 현재 버전만 조립) |
| 5 | MASTER 시험의 `schema_version = 2` 고정 기대값을 `ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION`(3)으로 바꾸고, v1 승격 시험을 v1·v2 두 경우로 넓혔다(v2 → v3 승격 = SchemaPromotionV3) | 계약 버전 증가에 따른 "현재 버전으로 발행" 의미 유지 — 완화 아님(Reviewer 확인 대상) |
| 6 | PAYMENT 시험 fixture `UNSUPPORTED_SCHEMA_VERSION` 3 → 4 | 3이 지원 버전이 됨(입력 값 변경, 기대값 동일) |
| 7 | `SiteWebHostQuery`(entitlement provided)·`SiteWebHostService`·`SiteWebHostPort`(payment required)·`EntitlementSiteWebHostAdapter`를 S3에서 만들었다(토큰 발급 S8이 쓴다). 캐시 금지 ArchUnit 대상에 새 adapter 추가 | §9.4 |
| 8 | Simulator 골격: 시험 MySQL은 Simulator 전용 초기화 스크립트(`docker/simulator-mysql/init/01-simulator-database.sh`)를 그대로 쓰고, compose `simulator-mysql`(127.0.0.1:3307)·`.env.example` 변수를 추가했다. 아키텍처 계층 규칙은 업무 모듈이 없어 빈 대상 허용(S6에서 false) | AN-19 |
| 9 | 새 의존성 없음. Simulator는 BOM 관리 starter만(webmvc·thymeleaf·data-jpa·flyway·actuator·jackson-kotlin·flyway-mysql·mysql-connector) — `deps-simulator.txt` | §13.1 |

### 24.8 중간 코드 검토(S1·S2·S3·S5, REVIEW_PASS_WITH_FIXES) 처리

| 항목 | 처리 |
|---|---|
| m-1 R6 결과 참조 소비 | R6은 결과 참조 digest를 저장하지 않는다(`AuthenticationResultCheck`, 이벤트·로그로만). 시험 기대값을 "R6 → resultRefDigest null"로 바꿨다 — **Coordinator 판정(§7.3 "R5~R10"은 R5·R7~R10)에 따른 기대값 변경**이며 완화가 아니다(다른 세션의 정당한 VERIFIED를 막지 않게 하는 쪽). §7.3 문장 수정 |
| m-2 V8_1 vs CreatePayment | S4와 같은 변경에서 `CheckoutValidityPolicy`(`paycore.payment.checkout-validity`, 운영 기본값 없음, n-g)·CreatePayment 5인자 `Payment.request`로 결제창 시각·설정 버전 저장, 4인자 `request` 삭제, `reconstitute(checkoutWindow)` 기본값 삭제(Mapper가 넘긴다) |
| m-3 compose | `simulator-mysql`을 profile `simulator`로, 변수는 `${VAR:-}`. 사용자 `.env`는 건드리지 않고 시뮬레이터 변수 없는 임시 env 파일로 `docker compose config -q` exit 0·기본 서비스 `mysql`만·profile 시 `simulator-mysql` 확인. 시험(Testcontainers)은 compose를 쓰지 않는다 |
| n-1 | `institutionAdapterDoesNotMutateDomain` 목록에 `AuthenticationResultCheck`·`AuthenticationSessionStart` 추가 |
| n-2 | 기관 결과 타입·이벤트 종류/상세를 파일별로 분리(`ProviderOutcome`·`ProviderBindingEcho`·`AuthenticatedDetails`·`ProviderAuthenticationResult`·`LookupFailure`·`ProviderResultLookup`·`AuthenticationEventKind`·`AuthenticationEventDetail`) |
| n-3 | ArchUnit `sessionVerifyOnlyThroughAuthenticationResultCheck` + 위반 fixture `DirectSessionVerify` |
| n-4 | R7 echo 행(paymentId·통화·환경·Plan·카드사)과 P5-before-P6 행 추가 |
| n-5 | `SchemaRolloutOrderV3Spec` 추가(역순: 미지원 새 버전 → head 막힘·host·승인 판정 fail-closed / 정순: v2 head → v3 승격 APPLIED → host 해소·승인 판정 유지) |
| n-6 | `schema2Issues` → `currentSchemaIssues`, PAYMENT `EntitlementSnapshot` 문서 "(1·2·3)" |
| n-7 | `test_simulator_isolation`이 type-safe accessor `projects.apps.*`도 검사(위반 fixture 줄 포함) |
| n-8 | GRADLE-CHECK minTests는 S12에서 실측으로 올린다(추적) |
| S3 순서 의존 시험 2건(검토 요청) | `EntitlementContentV3Spec`: 변경 전 `parsed shouldBe v3`(data class 전체 비교 — Site 목록 순서 포함) → 변경 후 `parsed.canonical shouldBe v3.canonical` + `parsed.sites.sortedBy { it.siteId } shouldBe v3.sites.sortedBy { it.siteId }` + hash 비교. 종료 Site 시험: 변경 전 `fromJson(…) shouldBe content` → 변경 후 정규 내용 동일 + 그 Site의 (lifecycle, channel) = (TERMINATED, WEB host). 기대 내용은 같고 정규화가 Site를 siteId로 정렬한다는 사실만 반영 |

### 24.9 S4 실행 기록·편차

| 시각(UTC) | 단계 | 명령 | 결과 | 증거 |
|---|---|---|---|---|
| 2026-10-16T06:18:11Z | 변이 RED(S4·검토 수정) | 4 변이(migration 전략 가드 제거, Mapper가 expiryCause를 옮기지 않음, 결정 로그 반복 종류 중복 제거 생략, R6이 결과 참조 저장) 후 CheckoutWindowCheckMigrationStrategySpec·CheckoutPersistenceSpec·CheckoutSchemaSpec·AuthenticationResultCheckSpec | exit 1. 가드 제거 → V8_1 3819로 migrate 실패, R6 변이 → R6 행 실패. 같은 실행이 **실제 결함 2건**을 드러냈다: `JpaAuthenticationSessionEntity.newRow`가 status를 넣지 않음(NOT NULL 위반) → 고침, 시험 helper `inTx`가 null 결과를 거부 → 고침 | `~/authn-evidence/mut-s4/mut-s4.log` |
| 2026-10-16T06:20:14Z | 변이 RED(재실행) | 결함 수정 뒤 같은 변이로 CheckoutPersistenceSpec | exit 1, 8 중 3 실패 — expiryCause 변이는 DB CHECK(`ck_authentication_session_expiry_cause`)가 2건으로, 중복 제거 생략은 UNIQUE(`uk_authentication_session_event_repeat`) → UniqueConflict로 잡음 | `~/authn-evidence/mut-s4/mut-s4b.log` |
| 2026-10-16T06:24:29Z | GREEN 시도(전체 payment-api) | `:apps:payment-api:test` | 846 중 1 실패: `PaymentApiContextSmokeSpec` 적용 migration 목록 기대값이 V6까지 고정 → V7·V8·V8.1 추가(새 migration에 따른 기대값 갱신 — 완화 아님, Reviewer 확인 대상). 재실행 5/5 통과 | `~/authn-evidence/green-s4/` |

| # | S4 편차·결정 |
|---|---|
| 1 | `authentication_session`에 rev 1.3 표의 `closed_at` 컬럼을 두지 않았다 — domain 세션에 종료 시각이 없고, 종료 시각은 결정 로그(`occurred_at`)가 기록한다 |
| 2 | 토큰·결제창 세션 기록(`CheckoutTokenRecord`·`CheckoutSessionRecord`)과 상태 enum은 `application.required` Store 계약에 두고, 식별자(`CheckoutTokenId`·`CheckoutSessionId`·`CheckoutHandle`)는 domain 값으로 뒀다(AN-14 — Aggregate 아님) |
| 3 | 3819(CHECK 위반)는 PersistenceFailure로 분류하지 않는다(결함 — 정상 경로에서 나오지 않는다). 새 UNIQUE 제약 이름은 `StoreConstraint`에 추가 |
| 4 | m-C 왕복(채우기 UPDATE → 재조회)의 실 MySQL·JPA 시험은 결제창 시각 없는 REQUESTED 행이 V8_1 뒤 공유 시험 DB에 존재할 수 없어 S11 옛 볼륨 재실행 시험(Spring 컨텍스트 + V8까지 멈춘 볼륨)에서 한다. S4에서는 Mapper `applyCheckoutWindow`와 `updatable` 매핑(Entity)만 둔다 |
| 5 | migration 전략 시험은 Spring 없이 전략 클래스와 운영 migration 위치·계정으로 옛 볼륨(V6 + 시각 없는 REQUESTED·AUTHORIZING)을 재현했다. 새 볼륨 경로는 모든 DB 시험 컨텍스트(적용 목록에 8.1)가 보인다 |

### 24.10 S6(Simulator domain·API·화면·golden)·n-f·S7(인증 Port·HTTP adapter·격리) 실행 기록

| 시각(UTC) | 단계 | 명령 | 결과 | 증거 |
|---|---|---|---|---|
| 2026-10-16T06:3xZ | RED(계약 golden) | `python3 harness/tests/test_authentication_contract_golden.py`(파일 없음) | exit 1(errors=1) → `--write` → exit 0(2 시험) | `~/authn-evidence/authn-golden-red.log`, `authn-golden-green.log` |
| 2026-10-16T06:42:56Z | 첫 실행(S6) | `:apps:institution-simulator:test` | exit 0, 시험 30(첫 실행부터 통과 — 시험 먼저 쓰고 구현 뒤 한 번에 컴파일했으므로 RED-C 기록 없음. RED는 아래 변이로 보인다) | `~/gate-logs/s6-run1.log` |
| 2026-10-16T06:44:28Z | 변이 RED(S6) | 4 변이(반환 URL 고정 문법 검사 제거, TEST 전용 검사 제거, 조회 "한 번만" 동작 제거, VC-EXPIRED 유효기간 무시) | exit 1, 30 중 6 실패. TEST 검사 제거는 domain 시험 + API(→ DB CHECK `environment = 'TEST'`가 500으로 막음 — 이중 방어 확인). 반환 URL 변이는 같은 시험의 앞 행(LIVE)이 먼저 실패해 가려졌으므로 단독 재실행: 7 중 1 실패("foreign return URL" 201) | `~/authn-evidence/mut-s6/`, `mut-s6b/` |
| 2026-10-16T06:4xZ | RED(공유 host 벡터 n-f) | `python3 harness/tests/test_host_grammar_vectors.py`(파일 없음) | exit 1 → `--write` → exit 0. HARNESS-SELFTEST 81 OK(하한 77 → 81) | `~/authn-evidence/host-vectors-*.log`, `harness-selftest-s6.log` |
| 2026-10-16T06:51:23Z | 첫 실행(S7) | adapter·격리·ArchitectureSpec·HostGrammarVectorsSpec | exit 0, 45 | `~/gate-logs/s7-run1.log` |
| 2026-10-16T06:52:00Z | 변이 RED(S7) | 4 변이(m-E 증거 필드 존재 검사 제거, HTTP 404 → MALFORMED, 고객 URL origin 검사 제거, LIVE Simulator 허용) | exit 1, 10 중 4 실패 — 변이마다 대상 시험 1건. 이 실행 전에 시험 결함 1건을 고쳤다: 계약 위반 벡터를 한 결과 참조로 요청해 `failedWithIssuerCode`가 resultRef 불일치에 가려졌다 → 벡터마다 자기 resultRef로 요청(기대값 동일, 시험이 더 엄격해짐) | `~/authn-evidence/mut-s7/` |
| 2026-10-16T06:59:34Z | GREEN(3 모듈 전체) | `:apps:payment-api:test :apps:master-api:test :apps:institution-simulator:check` | exit 0. payment-api 859, master-api 319, institution-simulator 30, 실패·오류·skip 0 | `~/authn-evidence/green-s7-*/` |

| # | S6·S7 편차·결정 |
|---|---|
| 1 | Simulator 거래 상태는 REGISTERED → AUTHENTICATED/FAILED/CANCELLED/EXPIRED(§13.2의 AWAITING_CUSTOMER는 REGISTERED로 합쳤다 — 등록 직후가 고객 대기다). VC-EXPIRED는 AUTHENTICATED로 저장하고 validUntil = 완료 시각이라 조회 outcome이 EXPIRED다 |
| 2 | 조회의 echo 변조(WRONG_AMOUNT·WRONG_MID·STATE_MISMATCH)는 저장 값이 아니라 조회 시 카드 시나리오로 만든다(결과 사실은 그대로, 응답만 변조 — PG R6·R7 시험용). 브라우저 redirect의 state는 원래 값이다 |
| 3 | Simulator 설정(`paycore.simulator`)은 `adapter.integration.SimulatorSettings`(required Port 구현 — web adapter는 required에 의존하지 않는다), client 인증은 provided `SimulatorClientAuthenticator`. 고객 화면 POST에는 CSRF가 없다(거래 ID 128bit, TEST 대역 — 남는 위험) |
| 4 | Simulator DB는 `environment = 'TEST'` CHECK를 둔다(TEST 전용의 DB 방어) |
| 5 | payment-api 인증 설정 `AuthenticationSettings`(`adapter.integration.institution`)가 `AuthenticationProfiles`(required, 카드사 allowlist·Plan 정책 — S9/S10이 쓴다)를 구현한다. 기동 검사: SIMULATOR만, TEST만, profile 존재, URL 형식(서버 간 URL은 https 또는 loopback http — 시험·로컬 Demo) |
| 6 | 계약 golden은 앱이 만든 JSON을 트리 비교한다(바이트 비교는 harness가 두 앱 자원 파일끼리). 응답 키 순서는 계약 순서로 쓴다 |
| 7 | 새 의존성 없음: adapter는 spring-web `RestClient` + `JdkClientHttpRequestFactory`(AN-25), 시험 stub은 JDK `com.sun.net.httpserver` |

### 24.11 중간 검토 #2(S4·S6·n-f·S7, REVIEW_PASS_WITH_FIXES) 처리

| 시각(UTC) | 항목 | 명령(`:apps:payment-api:test --tests …`) | 결과 | 증거 |
|---|---|---|---|---|
| 2026-10-16T07:12:42Z | m-1 RED-A | `LockReadFreshnessSpec`(새 시험 — 결정적 순서: TX A 비잠금 읽기 → TX B 변경·커밋 → TX A 잠금 읽기, 토큰·결제창 세션·인증 세션·Payment 4행) | exit 1, 4 중 4 실패: 잠금 읽기가 `OptimisticConflict`(Hibernate가 관리 중인 오래된 Entity의 version 차이를 드러냄) — 진 쪽이 "이미 사용됨" 판정 대신 충돌 오류 | `~/authn-evidence/m1-red/` |
| 2026-10-16T07:30:03Z | 검토 nit RED-C | n-1·n-3·n-4·n-5 새 시험 + m-1 | exit 1, 컴파일 실패: `SimulatorConnection`에 `allowLoopbackHttp` 인자 없음(n-4) | `~/gate-logs/nits-redc.log` |
| 2026-10-16T07:51:20Z | 검토 nit RED-A | 인자만 추가(검사 없음) 뒤 같은 시험 | exit 1, 26 중 5 실패: n-1 `uk_authentication_session_bound_authorization` → OTHER, n-3 PT0.999S 수용, n-4 표식 없이 loopback http 수용, n-5 한도 초과 본문에 6013ms(전부 읽고 drain), m-1 인증 세션 목록 잠금이 CREATED(아래 2행) | `~/authn-evidence/nits-reda/` |
| 2026-10-16T08:02:15Z | m-1 원인 확인 | 같은 시험 + 진단 출력 | 같은 TX에서 이미 PESSIMISTIC_WRITE로 잠근 행을 목록 잠금 뒤 `refresh`하면 Hibernate가 잠금 절 없는 SELECT를 보내 시험 TX(MySQL 기본 REPEATABLE READ)의 TX 시작 스냅샷(CREATED·version 0)으로 되돌렸다 → 목록 잠금은 native `SELECT id … FOR UPDATE` + 행별 `lockRow`(이미 잠근 행은 다시 읽지 않음)로 바꿈 | `~/gate-logs/dbg.log` |
| 2026-10-16T08:14:36Z | GREEN(일부) | m-1·n-1·n-3·n-4 + `CheckoutPersistenceSpec` | 34 중 1 실패: n-5 시간 — 한도에서 끊어도 Spring 응답 close가 남은 본문을 drain | `~/authn-evidence/nits-green/` |
| 2026-10-16T08:35:00Z | GREEN | n-5: `exchange(…, close = false)` + 본문 stream 직접 close(drain 없음). 시험 stub을 요청별 스레드로(단일 스레드 stub이 두 번째 요청을 늦은 끝까지 막아 측정이 섞였다 — 기대값 동일) | exit 0, 8/8 | `~/authn-evidence/n5-green/` |
| 2026-10-16T08:46:47Z | m-2 RED-C | `CheckoutWindowCheckMigrationStrategySpec`(판정 option (c)로 기대값 변경 — 아래 표) | exit 1, 컴파일 실패: 전략 생성자에 runtime DataSource·유효기간 설정 없음 | `~/gate-logs/m2-redc.log` |
| 2026-10-16T09:19:36Z | m-2 GREEN | 같은 시험(+ 09:10 실행에서 `ContextSmoke`·`CheckoutPersistence`·`DatabaseAccountSeparation` 통과) | exit 0, 2/2. 채우기 실패의 SQLException을 기동 실패용 `IllegalStateException`(vendorCode만, 원인 사슬 보존)으로 감쌌다 | `~/authn-evidence/m2-green2/`, `m2-green/` |

| # | 처리 |
|---|---|
| m-1 | `PessimisticRowLock.kt`: `lockRow`(find → 아직 이 TX가 잠그지 않았으면 `refresh(PESSIMISTIC_WRITE)`), `lockRows`(native `SELECT id … FOR UPDATE` 결과 순서대로 `lockRow`). Payment·토큰·결제창 세션·인증 세션의 단건·목록 잠금이 모두 이것을 쓴다. JPQL `@Lock` 단건 조회(`lockById`)는 지웠다(다시 쓰지 못하게). `JpaPaymentRepository.lock`도 같은 함정(`findCreationRecord` 뒤 잠금)이라 함께 고쳤다. n-6(갱신 전 version 재확인)은 잠금 값이 최신이 되어 성립한다 |
| m-2 | 판정 option (c) 구현: `CheckoutWindowCheckMigrationStrategy(runtimeDataSource, CheckoutValidityPolicy)` — V8 → pin(없을 때만) → 채우기(runtime 계정 JDBC 한 TX, 새 권한 없음) → 나머지 전부. 본문 §7.5.4·§7.5.6·§5.2·§14 C17·§20 R13·AN-29를 이 판정으로 고쳤고 §22-10에 AUTH-001 인계를 더했다. "V8_1 pending" 신호(`CheckoutWindowCheckStatus`)는 미루는 상태가 없어져 지웠다(n-2의 신호 소비자 요구는 대상이 사라짐 — pin은 기동 시 고정). 결과: 주기 작업 ① 채우기 단계·control 보유 경로 inline 채우기·C17은 없앤다(채우기는 한 곳). domain `Payment.assignMissingCheckoutWindow`는 main 호출자가 없어졌다(시험만 — S12에서 제거 여부 판단, 가역) |
| n-1 | `StoreConstraint.AUTHENTICATION_SESSION_BOUND_AUTHORIZATION` + 매핑. 시험은 V1·V7(payment 모듈 소유 표) migration의 모든 `uk_` 제약이 OTHER가 아님을 확인(새 제약 누락을 이후에도 잡는다) |
| n-3 | `CheckoutValiditySettings`: ms 단위 정수·1초 이상 |
| n-4 | `paycore.authentication.simulator.allow-loopback-http`(기본 false). loopback http 서버 간 URL은 이 표식이 있을 때만, 그 밖 http는 표식이 있어도 거부. 시험 설정·Fixture만 켬 |
| n-5 | 응답 본문 `readNBytes(MAX + 1)` + drain 없는 close, 그 밖 `RestClientException` → SERVER_ERROR(등록·조회). "HTTP가 아닌 상대" 시험은 결과 타입만 확인한다 — 그 경로는 JDK가 I/O 오류(→ `ResourceAccessException`)로 내므로 새 catch의 RED는 만들 수 없었다(방어 catch — 남는 기록) |

변경한 시험 기대값(Reviewer 확인 대상):

| 시험 | 이전 | 이후 | 근거 |
|---|---|---|---|
| `CheckoutWindowCheckMigrationStrategySpec` | 옛 볼륨 1회차 → history V1~V8·pending 신호 true, 시험이 직접 채운 뒤 2회차 → V8.1 | 1회 기동 → V1~V8.1 전부 성공·pin 기록·REQUESTED만 채움(시각 지난 행도 REQUESTED 유지)·재기동 pin 불변 + 채우기 불가 → 기동 실패·history V8까지 | Coordinator 판정 m-2 option (c)(이전 m-A 판정 대체) |
| `AuthenticationSettingsFixture.connection` | 5인자 | `allowLoopbackHttp`(기본 true — 이 Fixture의 서버 URL이 loopback stub) | n-4 입력 변경(기대값 아님) |
| `SimulatorCardAuthenticationAdapterSpec` 기존 6시험 | — | 변경 없음(새 시험 2개 추가) | — |

### 24.12 S8(토큰 발급·CreatePayment inline 만료·PaymentExpired)·S9(checkout connector·교환·화면)·S10(인증 시작·결과 반영·취소) 실행 기록

모든 단계는 시험을 먼저 쓰고 RED를 확인했다(Coordinator 지시 — 검토 #2 과정 편차 이후). 명령은 `:apps:payment-api:test --tests …`(또는 compileTestKotlin), 증거는 `~/authn-evidence/<이름>/`·`~/gate-logs/<이름>.log`.

| 시각(UTC) | 단계 | 대상 | 결과 |
|---|---|---|---|
| 2026-10-16T09:38:06Z | S8 RED-C | `CreatePaymentInlineExpirySpec`·`CheckoutTokenIssuanceSpec`·`CheckoutTokenApiSpec`(새 시험) | exit 1, 컴파일 실패 — `IssueCheckoutTokenUseCase`·`IssueCheckoutTokenCommand`·`CheckoutTokenRejection`·`IssueCheckoutTokenResult` 없음 (`s8-redc`) |
| 2026-10-16T09:53Z | (환경) | 같은 대상 + `CheckoutSettingsSpec` | 컴파일 경고=오류 2건(시험의 불필요한 `!!`)·토큰 파서의 BOM 문자 리터럴 → 고침(기대값 무관) |
| 2026-10-16T10:04:32Z | S8 RED-A | 토큰 발급 구현 뒤, CreatePayment 변경 전 | 23 중 3 실패: `CreatePaymentInlineExpirySpec` 2건(EXISTING_ACTIVE_PAYMENT ≠ CREATED, 실패 주입 예외 없음 — inline 만료 없음), 발급 시험 1건은 시험 결함(제어문자 paymentId로 correlationId를 만듦 → 출력 가능 문자만 쓰게 고침, 기대값 동일) (`s8-reda`) |
| 2026-10-16T10:16:20Z | S8 GREEN(부분) | + 기존 `CreatePayment*`·`ArchitectureSpec` | 168 중 2 실패 — 기존 시험 2건이 30일 뒤 REQUESTED 재사용을 기대(아래 기대값 변경 표) (`s8-green`) |
| 2026-10-16T10:29:31Z | S8 전체 | payment-api 전체 | 894 중 5 실패 — 같은 이유의 기존 시험 5건(`ApiFailureSpec` 3·`UtcStorageSpec` 2) (`s8-full`) |
| 2026-10-16T10:50:46Z | S9·S10 RED-C | `CheckoutExchangeSpec`·`CheckoutWebSpec`·`CheckoutTemplateScanSpec`·ArchitectureSpec 새 규칙·`CardAuthenticationFlowSpec`(새 시험, 교환 provided 타입은 이 실행 동안 치워 둠) | exit 1, 컴파일 오류 175건 — `OpenCheckoutSession*`·`CheckoutFailCode`·`CheckoutConnector`·`StartCardAuthentication*`·`CompleteCardAuthentication*`·`CancelCheckout*`·`org.thymeleaf` 없음 (`s9s10-redc`) |
| 2026-10-16T11:03:04Z | (구현 결함) | S9·S10 구현 첫 컴파일 | KDoc 안의 `` `/checkout/**` `` 표기가 Kotlin 중첩 주석을 열어 3파일 "Unclosed comment" → 주석 표기를 "`/checkout` 아래"로 고침 |
| 2026-10-16T11:16:16Z | S9·S10 첫 실행 | 9 Spec | 98 중 21 실패: 결제창 세션 비잠금 조회를 TX 밖에서 불러 MANDATORY 거부(인증 시작·취소 — 같은 TX 안으로 옮김), ArchUnit `application depends only on ports` 2건(application의 `UriUtils`·Spring `DataAccessException` — handle은 검증된 base64url이라 encode 제거, jOOQ reader가 `PersistenceFailure.Unavailable`로 번역). S8·S9 시험·기대값 갱신 5건은 통과 (`s910-run2`) |
| 2026-10-16T11:37:22Z | S9·S10 GREEN | `ArchitectureSpec`·`CardAuthenticationFlowSpec`·`CheckoutAuthenticationWebSpec`·`CheckoutWebSpec` | exit 0, 61 (`s910-run4`) |
| 2026-10-16T11:48:52Z | 변이 RED(9개 동시) | 8 Spec | 58 중 17 실패 — M1 토큰 1회 소비 검사 생략 → 교환 시험(C1 진 쪽 0/7 InvalidToken), M2 결제창 CSRF 끔 → `CheckoutWebSpec` CSRF 403 시험(303), M3 등록을 TX-A 안에서 호출 → 시작 시험(등록 2회·TX 안), M5 CreatePayment inline 만료 생략 → inline 만료 시험 2, M6 `th:utext` → 템플릿 검사·XSS escape 시험, M7 TX-C의 1단계 재판정 대신 거절 → C4, M8 토큰 발급 inline 만료 생략 → 경과 시험, M9 본문 전부 읽기+drain → n-5 시간 시험. M4는 M2에 가려져 단독 재실행 (`mut-s8s10`) |
| 2026-10-16T11:58:38Z | 변이 RED(M4 단독) | `CheckoutAuthenticationWebSpec` | 4 중 1 실패 — VERIFIED 뒤 쿠키 삭제 변이를 "쿠키 유지(m-D)" 기대가 잡음 (`mut-m4`). 변이는 모두 되돌렸고 잔여 없음을 grep으로 확인 |
| 2026-10-16T12:12:51Z | GREEN(3 모듈 전체) | `:apps:payment-api:test :apps:master-api:test :apps:institution-simulator:check` | exit 0. payment-api 931, master-api 319, institution-simulator 30, 실패·오류·skip 0. HARNESS-SELFTEST(같은 시각대) Ran 81 OK (`green-s10`) |
| 2026-10-16T12:14:30Z | 변이 RED(설정)·TX-A rollback 시험 추가 | `CheckoutSettingsSpec`·`CardAuthenticationFlowSpec` | 20 중 1 실패 — public-base-url에 http 허용 변이를 `CheckoutSettingsSpec`이 잡음. 새 TX-A 장애 주입 시험(아래 15)은 통과 (`mut-settings-txa`). 변이 되돌림 |
| 2026-10-16T12:28:30Z | GREEN(payment-api 최종) | `:apps:payment-api:test` | exit 0, 932, 실패·오류·skip 0 (`green-s10-final`) |

변경한 시험 기대값(Reviewer 확인 대상 — 모두 D-AUTHN-005·판정 U-3 규칙 변경: 30일 된 REQUESTED Payment는 결제창 시각(Demo 30분)이 지나 갱신된 요청의 주문 판정 TX에서 inline 만료된다. 완화 아님):

| 시험 | 이전 | 이후 |
|---|---|---|
| `CreatePaymentServiceSpec` "at expiry it is a new request (renewed record, order rules still apply)" | EXISTING_ACTIVE_PAYMENT, 원 paymentId, rows payment 1·outbox 1, 결과 code EXISTING_ACTIVE_PAYMENT | CREATED, 새 paymentId, payment 2·outbox 3(PaymentRequested 2 + PaymentExpired 1), 결과 code CREATED |
| `CreatePaymentConcurrencySpec` CP-10 교차 교착 재시도 | EXISTING_ACTIVE_PAYMENT, payment 1·outbox 1 | CREATED, payment 2·outbox 3(교착 재시도 의미·eligibility 3회·교착 ≥ 1은 그대로) |
| `ApiFailureSpec` 30일 만료 1ms 전/정각 | 정각 200 EXISTING_ACTIVE_PAYMENT·같은 paymentId·payment 1·outbox 1 | 201 CREATED·다른 paymentId·원 Payment EXPIRED·payment 2·outbox 3 (제목 갱신) |
| `ApiFailureSpec` 30일 뒤 같은 키·다른 표시 정보 | 200 EXISTING_ACTIVE_PAYMENT, 마지막 rows payment 2·outbox 2 | 201 CREATED, payment 3·outbox 4(KEY_REUSED 아님은 그대로) |
| `ApiFailureSpec` 30일 뒤 같은 키 동시 8 | 전부 200·원 paymentId·payment 1·outbox 1·갱신 결과 code EXISTING_ACTIVE_PAYMENT | 201 1 + 200(재생) 7·같은 본문·새 paymentId·payment 2·outbox 3·결과 code CREATED (제목 갱신) |
| `UtcStorageSpec` 시나리오(UTC·비UTC JVM 2시험) | 갱신 결과 EXISTING_ACTIVE_PAYMENT, 시험이 첫 Payment를 직접 FAILED로 바꾼 뒤 늦은 성공 보고 | 갱신 결과 CREATED, 첫 Payment는 inline 만료로 이미 EXPIRED(시험이 EXPIRED를 확인), 저장 값 비교에 `payment.checkout_expires_at`·PaymentExpired `occurred_at`·payload `expiredAt` 3개 **추가**(기존 값 기대 불변) |

입력·시험 기반 변경(기대값 아님): `PaymentDbTest`·`PaymentApiTest`에 `SiteWebHostTestConfig`(Site host Port 대역)·`CardAuthenticationTestConfig`(인증기관 Port 대역) 추가, `FaultInjectionConfig`에 결제창·인증 세션 Store 장애 주입 지점 추가, 시험 설정에 `paycore.checkout.*`·`paycore.payment.checkout.port: 0`.

| # | S8~S10 편차·결정(가역 — Reviewer 확인) |
|---|---|
| 1 | 진입 필터는 checkout 포트 ↔ `/checkout` 아래만 새로 강제했다. 공개 포트의 허용 경로를 `/v1`·`/actuator/health`로 좁히지 않았다(§12.1 문장) — 기존 공개 포트 동작·기존 시험 기대값을 바꾸지 않기 위해. 결제창 경로는 공개·내부 포트에서 404, checkout 포트의 다른 경로는 404(시험) |
| 2 | ArchUnit `checkoutWebUsesOnlyProvidedUseCases` 허용 목록에 명세 목록 외 `tools.jackson..`(토큰 API 엄격 JSON 파서)·`io.micrometer..`(지표)·`org.jetbrains.annotations..`(Kotlin 컴파일러 annotation)·`org.apache.catalina.connector..`(checkout connector 구성)를 더했다. 모두 domain·required·persistence가 아니다. 위반 fixture 2개(domain·required 의존)를 규칙이 잡는다 |
| 3 | 명세 §11.2의 provided `CheckoutSessionQuery`·`CheckoutViewQuery`를 하나의 `CheckoutPageQuery`로 뒀다(화면 판정·DTO가 한 흐름). 새 required Port: `CheckoutPolicy`(`paycore.checkout.*` — origin·정책 버전·토큰/결제창 세션/인증 TTL), `CheckoutSecretGenerator`, `CheckoutViewReader`(jOOQ), `CardAuthenticationEndpoints`(고객 origin — 인증 설정이 구현), `IntegrationEventIdGenerator`(PaymentExpired event ID — 운영 식별자 생성기가 구현) |
| 4 | 복귀 1단계(P1~P6)를 비잠금 판정이 아니라 처음부터 잠금 TX(payment → 세션 → 결제창 세션)에서 판정한다 — 결과는 같고 TX-C가 다시 판정한다. 잠금 동안 외부 호출 없음(C10 시험) |
| 5 | handle 형식 오류는 §6.1대로 400 화면(§6.3 표의 401 행과 엇갈림 — §6.1 채택) |
| 6 | 교환의 "결제창 시각 경과 409(CHECKOUT_EXPIRED)"는 토큰 만료 시각 ≤ 결제창 시각이라 정상 흐름에서 도달하지 않는다(토큰 만료가 먼저 400) — 방어로 남겼다 |
| 7 | `AuthenticationEventDetail.REJECTED` 추가 — 인증기관이 등록을 4xx로 거절한 REGISTRATION_FAILED 분류(기존 분류에 대응 값 없음) |
| 8 | TX-B가 Payment 종료(REQUESTED 아님)를 보면 결제창 세션은 COMPLETED(PAYMENT_NOT_AVAILABLE)(인증 시작·화면·취소와 같은 규칙 — §6.3 표 행), 세션은 EXPIRED(PAYMENT_CLOSED). 결제창 시각 경과는 결제창 세션 EXPIRED(PAYMENT_CLOSED)(§7.4.1) |
| 9 | 복귀 P5의 failUrl code: Payment가 아직 REQUESTED(시각 경과)면 CHECKOUT_EXPIRED, 아니면 PAYMENT_NOT_AVAILABLE |
| 10 | 지표 counter는 web adapter가 결과 타입으로 센다(application은 Micrometer에 의존하지 않는다): `paycore.checkout.tokens`·`exchanges`·`pages`·`cancels`, `paycore.authentication.starts`·`returns`. 남은 지표·gauge·로그 비노출 시험은 S12 |
| 11 | 화면 금액 표시는 JDK ISO 4217 소수 자릿수로 서식(표시 전용 — 계산에 쓰지 않는다) |
| 12 | `spring-boot-starter-thymeleaf`를 payment-api에 추가(§16 계획 — 카탈로그 기존 항목, Boot BOM 관리, 버전 override 없음). 새 라이브러리·카탈로그 변경 없음. GRADLE-DEPS·CVE는 S12 |
| 13 | m-2로 채우기가 기동 전략 한 곳이 되어 `Payment.assignMissingCheckoutWindow`·`CheckoutWindowPinStore`(JPA)는 main 호출자가 없다(전략은 JDBC). 제거 여부는 S12에서 판단 |
| 14 | `CheckoutSettings`(설정 검사)는 클래스를 먼저 쓰고 같은 단계 안에서 `CheckoutSettingsSpec`을 썼다(시험 먼저 원칙의 작은 편차 — 같은 형식의 `CheckoutValiditySettings` 검사 시험이 먼저 있었다). S10 web 경로(`CheckoutAuthenticationWebSpec`)도 Controller 뒤에 썼다 — 두 곳 모두 변이 RED(M2·M4)로 시험이 규칙을 잡음을 보였다 |
| 15 | 장애 주입은 TX-I·TX-X·TX-A·TX-C·CreatePayment inline 만료를 다룬다(만료 작업·채우기는 S11). 로그 비노출·`CheckoutErrorPageLeakSpec` 전용 시험은 S12(지금은 400 화면 누출 검사만) |

### 24.13 S11(만료 주기 작업·옛 볼륨 기동·만료 경합)·중간 검토 #3(S8~S10, REVIEW_PASS_WITH_FIXES) 처리

Coordinator 판정(2026-10-16): 편차 1(공개 포트 경로 미축소) 수용 — 후속 후보로 §12.1에 기록. 편차 5(handle 형식 오류 400) 유지 — §6.3 표를 고쳤다. 편차 10(Thymeleaf) 수용 —
다음부터 계획된 의존성도 먼저 묻는다, S12에 DEPS·CVE 증거. 편차 11(죽은 코드) — S11에서 제거(아래). 편차 12(TDD) 기록 — 이후 모든 단계 시험 먼저.
검토 #3: 기대값 6건 정당 확인(30일 = 멱등 보존 기간 D-CPC-004 — 보존 기간 안 같은 키·다른 내용은 계속 IDEMPOTENCY_KEY_REUSED).

| 시각(UTC) | 단계 | 대상 | 결과 |
|---|---|---|---|
| 2026-10-16T12:44:13Z | S11 RED-C | `RequestedPaymentExpiryServiceSpec`·`CheckoutExpiryConcurrencySpec`·`OldVolumeStartSpec`·`RequestedPaymentExpirySchedulerSpec`(새 시험), ArchitectureSpec batch 규칙 빈 대상 금지 | exit 1, 컴파일 실패 — `ExpireElapsedRequestedPaymentsUseCase`·`…Command`·`ExpiryRunResult`·`RequestedPaymentExpiryScheduler`·`CheckoutExpiryJobSettings` 없음 (`s11-redc`) |
| 2026-10-16T13:09:47Z | S11 첫 실행(RED-A — 실제 결함) | 위 + 죽은 코드 제거 시험 | 87 중 9 실패: 만료 작업이 후보 0건 — jOOQ `LocalDateTime` 바인딩·읽기가 JVM 시간대(Asia/Seoul)로 9시간 어긋남(진단 실행 `dbg2`: 저장 01:32 → 읽기 10:32Z). 옛 볼륨 시험은 2020년 행이라 가려졌다. **같은 결함이 S9 `JooqCheckoutViewReader`에도 있었다**(만료 시각을 9시간 늦게 읽어 읽기 경로가 만료된 결제창을 보여 줄 수 있었다 — 쓰기 경로는 JPA 잠금 재판정이라 안전). C15 순차 시험 RED(TX-B가 "다른 창"으로 답함) (`s11-run2`) |
| 2026-10-16T13:2xZ | RED-A(읽기 시각) | `CheckoutWebSpec` 새 행: 결제창 세션 만료 1ms 뒤 화면 | 7 중 1 실패 — 만료 뒤에도 200 (`tz-red`). → `adapter.query.UtcWallClock`(관례 §9.1: 읽기 `DATE_FORMAT` 문자열, 비교 `CAST(… AS DATETIME(3))` 리터럴)로 두 reader를 고침 |
| 2026-10-16T13:43:14Z | 검토 #3 RED-C | m-B 전략 시험 2(hook으로 채우기와 V8_1 사이 경합 주입)·m-A 규칙·fixture 3·n-3 장벽·n-4 격리·n-5 표시 | exit 1, 컴파일 실패 — 전략에 재확인 hook 없음 (`r3-redc`) |
| 2026-10-16T13:57:41Z | 실행 | S11·검토 #3 묶음 11 Spec | 100 중 2 실패: ArchitectureSpec(새 규칙이 `ParsedCheckoutTokenBody` — Checkout 접두 없는 web 타입 — 를 잡음 → `CheckoutTokenBody`로 개명), C15 순차 시험의 시각 결함(결제창 세션 20분을 지난 29분에 시작 → 19분으로, 기대값 동일) (`s11-run3`) |
| 2026-10-16T14:10:29Z | GREEN(payment-api 전체) | `:apps:payment-api:test` | exit 0, 955, 실패·오류·skip 0 (`s11-full`) |
| 2026-10-16T14:24:50Z | 변이 RED(5개 동시) | 만료 작업·경합·전략·화면·ArchitectureSpec | 60 중 14 실패 — K1 만료 작업이 만료하지 않음 → 작업·경합 시험 전부, K3 V8_1 직전 재확인 생략 → m-B 시험 2(3819 FlywayMigrateException·실패 기록), K4 통화 단위 추측 → n-5 시험(200), K5 규칙을 이전처럼 넓힘 → m-A fixture 시험 2(Jdbc·Tomcat 위반 미검출). K2는 K1에 가려져 단독 재실행 (`mut-s11`) |
| 2026-10-16T14:33:20Z | 변이 RED(K2 단독) | `CheckoutExpiryConcurrencySpec` | 9 중 1 실패 — TX-B의 PAYMENT_CLOSED 구분 제거 → C15 순차 시험이 Superseded("다른 창")를 받음 (`mut-k2`). 변이는 모두 되돌렸고 잔여 없음을 grep으로 확인(2026-10-17 재개 때 다시 확인 — 남은 Gradle 프로세스·보관 파일·stage 없음) |
| 2026-10-17T01:12:12Z | GREEN(3 모듈 전체, 변이 복원 뒤) | `:apps:payment-api:test :apps:master-api:test :apps:institution-simulator:check` | exit 0. payment-api 955, master-api 319, institution-simulator 30, 실패·오류·skip 0. HARNESS-SELFTEST Ran 81 OK (`green-s11`) |

| # | 처리 |
|---|---|
| 죽은 코드(편차 11) | `Payment.assignMissingCheckoutWindow`, `CheckoutWindowPinStore`(+`CheckoutWindowPin`), `JpaCheckoutWindowPinStore`·Entity·Spring Data repository, `StoreConstraint.CHECKOUT_WINDOW_PIN`·매핑을 지웠다(main 호출자 없음 — pin·채우기는 기동 전략 JDBC). Payment 결제창 컬럼 JPA 매핑은 `updatable = false`(JPA가 바꾸지 않는다 — m-C 우려는 채우기가 JPA 밖으로 옮겨 사라짐), Mapper는 "갱신 때 저장 값과 같음"만 확인. 시험: `PaymentCheckoutWindowSpec` 채우기 행 2개 → "V7 이전 행은 어느 상태든 시각 없이 복원·경과 아님" 1행, `CheckoutPersistenceSpec` pin 행 → "기동이 pin 한 행을 고정, runtime은 UPDATE 불가(1142)", TX 밖 호출 목록에서 pin 제거. 삭제 단계 자체는 동작 RED가 없다(제거 — 컴파일·전체 GREEN이 증거) |
| S11 주기 작업 | provided `ExpireElapsedRequestedPaymentsUseCase`(회차 ID·batch) → `RequestedPaymentExpiryService`: jOOQ `JooqElapsedRequestedPaymentReader`(키셋 `(checkout_expires_at, payment_id)`, 인덱스 `ix_payment_requested_expiry`) → 후보마다 TX(control X → payment X → `RequestedPaymentClosing`), 실패는 그 건만 rollback·계속. trigger `adapter.batch.RequestedPaymentExpiryScheduler`(`@Scheduled(fixedDelay)`, `enabled` 끄면 무동작, counter `paycore.payment.checkout_expiry{result}`), 설정 `paycore.payment.checkout-expiry.job.{enabled,fixed-delay,batch-size}`(운영 기본값 없음). ArchUnit `batchAdapterUsesOnlyApplication`을 빈 대상 금지로 강화 |
| 옛 볼륨 기동(종료 기준) | `OldVolumeStartSpec`: 전용 컨테이너를 V6 + V7 이전 행(REQUESTED 2·AUTHORIZING·EXPIRED + control)으로 만든 뒤 **Spring 컨텍스트 한 번 기동** → history 1~8.1 전부 성공, pin Demo, REQUESTED만 채움, **JPA Repository 재조회가 `CheckoutWindow(created_at + 30분, pin 버전)`**(m-C 왕복), 다른 상태 NULL → 만료 작업 1회: 시각 지난 행 EXPIRED + PaymentExpired 1(payload pin 버전·시각) → REQUESTED + NULL INSERT 3819 |
| 경합 | `CheckoutExpiryConcurrencySpec`(API 시험 구성 — 공용 시험 시계): C7 두 순서, C11 두 순서, C12·C14·C15·C16 각 3회 동시, C13 동시 2회차, C15 순차(등록 중 만료). 모두 "Payment EXPIRED 1회·종료 근거 1행·PaymentExpired 1건·진 쪽 NoChange". 0건(rollback): `RequestedPaymentExpiryServiceSpec`의 PaymentExpired append 뒤 장애 주입 → 그 후보 REQUESTED·근거·이벤트 0, 다음 회차가 만료 |
| C7 기대(명세 정정) | ~~만료가 이기면 결제창 세션도 EXPIRED(PAYMENT_CLOSED)라 TX-C의 결제창 세션 확인에서 401(VERIFIED 없음). rev 1.3 §14 C7의 "P1 410"을 고쳤다~~ — **검토 #4 m-1이 이 편차를 거절했다**. §14 C7 행은 원문으로 되돌렸고 구현이 410 + failUrl(CHECKOUT_EXPIRED)로 돌아왔다(§24.14) |
| C15 TX-B | 등록 중 control 보유 만료로 S가 EXPIRED(PAYMENT_CLOSED)면 "다른 창"(409 Superseded)이 아니라 Payment 종료 409 PaymentNotAvailable / 시각 경과 410 CheckoutTimeOver(§7.4.1 갱신) |
| 검토 #3 m-A | ArchUnit 결제창 web 규칙: Spring은 `web·http·stereotype·context·core·beans·boot·security·ui·util·validation`만, 같은 패키지는 `Checkout*`과 목록 helper만(helper는 domain·required·persistence 비의존 — 별도 규칙), Tomcat connector는 `CheckoutConnector*`만(별도 규칙). fixture 3 추가(JdbcTemplate·목록 밖 helper·connector 밖 Tomcat). 새 규칙이 main의 `ParsedCheckoutTokenBody`를 잡아 `CheckoutTokenBody`로 개명 |
| 검토 #3 m-B | 전략이 V8_1 직전 재확인 → 다시 채우기(최대 3회) → 그래도 남으면 V8_1을 시도하지 않고 기동 거부(`IllegalStateException`, history V8까지 성공만). 시험: hook으로 1회 주입 → V8_1 도달·NULL 0, 매번 주입 → 거부·실패 기록 없음. §20 R13·§22-11에 배포 조건(구 인스턴스를 모두 내린 뒤 업그레이드) |
| 검토 #3 n-2 | 외부 redirect `RedirectView.setExpandUriTemplateVariables(false)`(반환 URL 문법이 `{`를 허용하지 않아 관찰 가능한 RED 없음 — 방어) |
| 검토 #3 n-3 | C4·C5에 조회 장벽(두 요청이 모두 1단계·조회에 들어온 뒤 TX-C) — 재판정 경로가 결정적. C4는 조회 2회도 확인 |
| 검토 #3 n-4 | 격리 수준: runtime DataSource 연결 기본 REPEATABLE READ(MySQL 기본), Use Case TX는 READ COMMITTED(`LocalTransactions`) — `TransactionIsolationSpec`(현재 동작 기록 시험이라 RED 없음). `lockRow`는 두 격리 수준 모두에서 맞다(이미 잠근 행 재조회 생략 — REPEATABLE READ 시험 TX의 `LockReadFreshnessSpec`). 설정은 바꾸지 않았다(Coordinator 지시 — 바꾸기 전 보고). ARCHITECTURE-CONVENTIONS §9 표에 기록 |
| 검토 #3 n-5 | 화면 금액 소수 자릿수 = PayCore 기준정보(권리 투영의 통화 minor unit — CreatePayment eligibility와 같은 원천, 읽기 전용 TX). 판정 불가면 503(추측 없음). JDK ISO 4217 표 제거 |
| 검토 #3 n-6 | 다른 503 화면의 `Retry-After`는 선택 사항으로 기록(추가 안 함) |
| 검토 #3 n-7 | 명세 본문 정렬: §6.3 handle 행(400), §7.4.1 TX-B 문구, §12.1 공개 포트 문장(후속 후보), §11.2 provided 조회 이름(`CheckoutPageQuery`), §17.3 허용 목록 |
| 검토 #3 n-1 | S12 범위로 기록: `CheckoutLogRedactionSpec`, 오류 화면 누출 바이트 검사, 한 브라우저 동시 결제창 2개(handle별 쿠키 — 일부는 `CheckoutWebSpec`에 있음), access log·query 로깅 설정 시험, GRADLE-DEPS·CVE-SCAN의 Thymeleaf |

변경한 시험 기대값(S11):

| 시험 | 이전 | 이후 | 근거 |
|---|---|---|---|
| `PaymentCheckoutWindowSpec` | 채우기 연산 2행("이미 있으면 다시 못 채움", "REQUESTED만 한 번 채움 / AUTHORIZING 거부") | 채우기 연산이 없어 삭제, "V7 이전 행은 어느 상태든 시각 없이 복원·경과 아님" | Coordinator 판정(편차 11) — 채우기는 기동 전략 한 곳 |
| `CheckoutPersistenceSpec` pin | JPA Store로 pin INSERT·두 번째 1062·값 유지 | 기동 전략이 pin 한 행(Demo 버전·1800000ms)을 고정, runtime UPDATE 1142 | 같은 판정 — JPA pin Store 제거 |
| `ArchitectureSpec` batch 규칙 | `allowEmptyShould(true)`(대상 없음) | `allowEmptyShould(false)`(대상 있음) | 강화 |
| `ArchitectureSpec` 결제창 web 규칙 | `org.springframework..` 전체·Tomcat connector 전체 허용, 같은 패키지 전체 | 검토 #3 m-A 목록(위) | 강화(검토 지시) |

### 24.14 S12(로그·화면 누출·두 창·로깅 설정·지표·문서·하네스)·중간 검토 #4(S11, REVIEW_PASS_WITH_FIXES) 처리

Coordinator 판정(2026-10-17): n-4(격리 수준) 변경 없음. 편차 2(C15 순차 시험 시각 19분) 수용. **편차 1(C7 401)은 검토 #4 m-1이 거절** — 원 명세대로 되돌렸다(아래).
n-8(토큰 API의 Idempotency-Key 400) 최종 판정: **400 유지**(§5.1 헤더 행·§5.3 오류 표·AN-02에 기록, `CheckoutTokenApiSpec` "an Idempotency-Key header is refused with 400
INVALID_REQUEST(field Idempotency-Key)"로 시험).

| 시각(UTC) | 단계 | 대상 | 결과 |
|---|---|---|---|
| 2026-10-17T01:22:32Z | S12·검토 #4 RED-C | `ExpiryRunResult.defects`(n-3), `CompleteCardAuthenticationResult.CheckoutClosed(code, failRedirect)`(m-1), `SimulatorCardAuthenticationAdapter(settings, registry)`(지표), `CheckoutBacklogGauges`, ArchUnit n-1 fixture 2(`UsingSql`·`UsingBootJdbc`) | exit 1, 컴파일 실패 — 대상 없음 (`s12-redc`) |
| 2026-10-17T01:34:38Z | RED-A | 위 시험 + `CheckoutWebSpec` m-1 행·C7 복원 행·스케줄러 defect counter | 76 중 7 실패: adapter 지표 없음(NPE), n-1 fixture 2(`java.sql`·`boot.jdbc` 미검출), 스케줄러 `result=defect` 없음, 화면 410 대신 401, C7 `SessionExpired`, gauge 없음(NPE), 작업 defects 0 (`s12-reda`) |
| 2026-10-17T01:50:42Z | GREEN | 같은 묶음 + 특성 시험 4(`CheckoutLogRedactionSpec`·`CheckoutErrorPageLeakSpec`·`CheckoutTwoWindowsSpec`·`CheckoutLoggingConfigurationSpec`) | exit 0, 76 / 76 (`s12-green1`). 특성 시험 4개는 첫 실행부터 통과(현재 동작 고정 — 아래 변이 RED로 검출력 확인) |
| 2026-10-17T02:02:51Z | n-4 첫 실행 | `CheckoutExpiryConcurrencySpec`(회차별 순서 강제 + 승자 집합 단언) | 9 중 1 실패 — C15 승자 판별을 시작 결과 종류로 했는데 창 끝(T0 + 30분)에는 결제창 세션(T0 + 20분)이 이미 TTL 경과라 시작이 먼저여도 `SessionExpired` → 시험 결함. 판별을 결제창 세션 close_reason(TTL_ELAPSED = 시작 먼저, PAYMENT_CLOSED = 작업 먼저)으로 고침, 기대값 불변 |
| 2026-10-17T02:04:43Z | GREEN(n-4) | 같은 Spec | exit 0, 9 / 9 (`s12-n4`). 회차별 승자: C12 issuance·issuance·job, C14 create-payment·create-payment·job, C15 start·start·job, C16 issuance·job·job·issuance·create-payment |
| 2026-10-17T02:06:49Z | 변이 RED(L1~L5 동시) | 특성 시험 4 | 4 중 4 실패 — L1 성공 복귀에서 `authenticationRef` 전체를 INFO 로그 → 로그 비노출 시험(2줄), L3 화면 문구에 예외 타입 이름 → 누출 시험(`Exception`), L4 `org.jooq: DEBUG` → 설정 시험, L5 handle과 무관한 첫 결제창 쿠키 사용 → 두 창 시험(401) (`s12-mut`) |
| 2026-10-17T02:07:29Z | 변이 RED(L2 단독) | `CheckoutErrorPageLeakSpec` | 1 중 1 실패 — 실패 화면의 다시 시도 링크에 복귀 query(state·resultRef) 재사용 → `200 failed` 화면에 결과 참조 원문 (`s12-mut2`). 변이 모두 되돌림(보관본과 바이트 비교 일치) |
| 2026-10-17T02:26:58Z | 전체 check 1회차 | `--no-daemon --no-build-cache --rerun-tasks check` | exit 1 — payment-api 964 중 1 실패: `SimulatorCardAuthenticationAdapterSpec` 지표 시험(SERVER_ERROR counter 1 → 2 기대, 1). 원인은 시험 stub: 단일 dispatcher `HttpServer`에서 앞 시험의 2초 지연 handler(read timeout 시험)가 끝나기 전에 온 요청이 줄을 서 TIMEOUT으로 분류됨(부하에서만). stub을 요청별 스레드로 고침, 기대값 불변. 다른 모듈 전부 통과 (`s12-fullcheck`, 보관 `~/authn-evidence/s12-fullcheck-1`) |
| 2026-10-17T02:45:06Z | GREEN(전체 check 2회차) | `--no-daemon --no-build-cache --rerun-tasks check`(시작 02:27:27Z) | exit 0, **1480**: payment-api 964, master-api 319, institution-simulator 30, financial-batch 26, internal-admin 29, libs/test-support 112, 실패·오류·skip 0 (`s12-fullcheck2`, 보관 `~/authn-evidence/s12-fullcheck2`) |
| 2026-10-17T02:46:20Z | HARNESS-SELFTEST | `python3 -B -m unittest discover -s harness/tests -v`(하한 갱신 뒤) | exit 0, Ran 81, OK (`s12-selftest`) |
| 2026-10-17T02:46Z | TEST-SUMMARY(등록 argv, 하한 1480) | `--not-before 2026-10-17T02:27:27Z 1480` + 6개 보고서 경로 | exit 0, PASS, total 1480 (`s12-testsummary`) |
| 2026-10-17T02:47:04Z | GRADLE-DEPS ×3(등록 argv) | payment-api·master-api·institution-simulator `runtimeClasspath` | 모두 exit 0, FAILED 해석 0. Thymeleaf(payment-api·institution-simulator): `spring-boot-starter-thymeleaf` 4.1.1(Boot BOM) → `org.thymeleaf:thymeleaf`·`thymeleaf-spring6` 3.1.5.RELEASE, `attoparser` 2.0.7.RELEASE, `unbescape` 1.1.6.RELEASE — 직접 버전 지정 없음 (`s12-deps-<module>`) |
| 2026-10-17T02:48:20Z | CVE-SCAN(등록 argv) | `python3 harness/scripts/cve-scan.py`(osv-scanner 2.6.0, SBOM `--no-build-cache --rerun-tasks` 재생성) | exit 0, PASS — 5개 실행 단위 모두 canary 검출·취약점 0: payment-api 118 구성요소, master-api 101, institution-simulator 101, financial-batch 53, internal-admin 58. payment-api·institution-simulator SBOM에 `thymeleaf`·`thymeleaf-spring6` 3.1.5.RELEASE·`attoparser` 2.0.7.RELEASE·`unbescape` 1.1.6.RELEASE 포함·취약점 0 (`s12-cve`, 보관 `~/authn-evidence/s12-cve`) |

| # | 처리 |
|---|---|
| 검토 #4 m-1 | `CheckoutPageService`·`CardAuthenticationCompletionService`(선택·1단계·TX-C 재판정): 쿠키 digest가 맞고 결제창 세션이 Payment 쪽 사유로 닫혔으면(EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE)) 401이 아니라 `NotAvailable`/`CheckoutClosed(code, failRedirect)`. code = Payment REQUESTED(시각 경과)·EXPIRED → CHECKOUT_EXPIRED(410 "결제 시간이 지났습니다."), 그 밖 → PAYMENT_NOT_AVAILABLE(409) — `CheckoutSessionGate.closedCode`. 상태는 바꾸지 않는다. §6.3 표에 행 추가, §7.3 세션 선택 2 갱신, **§14 C7 행을 원문으로 되돌림**("만료가 이기면 S EXPIRED(PAYMENT_CLOSED) → 복귀 재판정 P1 410"), §24.13 C7 행에 거절 표시. 인증 시작(`CardAuthenticationStartService`)은 검토 범위 밖이라 그대로다(결제창 세션이 TTL 안에서 PAYMENT_CLOSED로 닫히면 지금은 `SessionExpired` 401 — 같은 규칙 적용 여부는 Coordinator 확인 대상) |
| 검토 #4 n-1 | ArchUnit 결제창 web 허용 목록: JDK 안에서도 `java.sql.`·`javax.sql.` 거부(`CHECKOUT_WEB_DENIED_PREFIXES`), Boot는 `boot.context.properties.`·`boot.web.`·`boot.autoconfigure.`만(connector 클래스는 `boot.tomcat.` 추가). fixture `UsingSql`(java.sql)·`UsingBootJdbc`(boot.jdbc) 추가 |
| 검토 #4 n-2 | `JooqEntitlementFreshnessReader`는 고치지 않는다(아래 TZ-FIX-001) |
| 검토 #4 n-3 | 만료 작업: 저장소 일시 실패(`PersistenceFailure`·TX 시작/commit 불명확)는 `failed`(WARN), 그 밖 예외는 `defects`(ERROR, `SafeThrowableNames` 요약 — 메시지·ID 원문 없음, paymentId 앞 12자). `ExpiryRunResult(expired, noChange, failed, defects)`, counter `paycore.payment.checkout_expiry{result=expired·no_change·failed·defect}` |
| 검토 #4 n-4 | C12·C14·C15·C16: 회차 0은 동시, 회차 k ≥ 1은 (k-1)번째 작업을 먼저 끝낸 뒤 나머지를 동시에 → 두 순서(C16은 세 경로)가 반드시 나온다. 승자를 `PaymentExpired.causationId`(C15는 결제창 세션 close_reason)로 판별해 집합으로 단언(C12 {issuance, job}, C14 {create-payment, job}, C15 {start, job}, C16 {job, issuance, create-payment} — C16은 5회차) |
| 검토 #4 n-5 | 배포 메모: "V7→V8_1 업그레이드 동안 AUTHN-001 이전 payment-api 인스턴스가 떠 있지 않아야 한다"를 §20 R13·§22-11(기존)과 `HARNESS-VERIFICATION-CONTRACT.md` §7 AUTHN-001 항목에 적었다. 루트 `README.md`는 이 작업 허용 경로 밖이라 쓰지 않았다(Coordinator 판단 대상) |
| TZ-FIX-001(범위 밖 기록) | 같은 JVM 시간대 패턴(jOOQ 비교 값을 `LocalDateTime`으로 바인딩)을 쓰는 다른 reader는 이 작업 범위 밖이며 **TZ-FIX-001**로 넘긴다. 알려진 것: payment `entitlement/adapter/query/JooqEntitlementFreshnessReader.kt:26-34`(`LocalDateTime.ofInstant(now, UTC)`를 `LEASE_UNTIL.le(at)`·`TIMESTAMPDIFF`에 바인딩 — 검토 #4 n-2 확인), master `JooqEntitlementPropagationReader.kt:126`(`utc()` 바인딩 — 가능성, 미확인). 이 작업의 결제창 reader 3개는 `UtcWallClock`으로 고쳤다(§24.13) |
| S12 로그 비노출(§12.6, AC 6) | `CheckoutLogRedactionSpec`: 전 흐름(발급 → 교환 → 화면 → 시작 → 실패 복귀 → 재시도 → 확인 불가 → 성공 복귀 → 중복 복귀 → 다른 결제창 취소)을 PayCore 로거 DEBUG로 실행하며 모든 로그 줄을 모아 토큰·쿠키 비밀·CSRF·state·결과 참조·기관 evidenceRef 원문과 `authenticationRef` 전체가 0회 |
| S12 화면 누출 | `CheckoutErrorPageLeakSpec`: 400·401·403·422·200(실패)·410·503·409 화면 응답 바이트에 예외·스택·SQL·내부 분류·Snapshot·기관 코드 표식 0, 비밀 원문 0(503 다시 시도 링크의 자기 복귀 query만 허용) |
| S12 한 브라우저 두 창 | `CheckoutTwoWindowsSpec`: 두 결제창 쿠키를 매 요청에 함께 보내며 시작·복귀를 교차 → 각자 자기 successUrl, 한쪽 취소 뒤 다른 쪽 계속 사용 가능 |
| S12 로깅 설정 | `CheckoutLoggingConfigurationSpec`: access log 미사용(쓰면 `%r`·`%q`·common·combined 금지), 요청 로깅 필터 bean 없음, `spring.jpa.show-sql` 끔, SQL·jOOQ·HTTP client·Spring web·Security·Tomcat·PayCore 로거 INFO 이상, JDK HttpClient 디버그 로그 미설정 |
| S12 지표 | counter `paycore.checkout.tokens{result}`·`paycore.checkout.exchanges{result}`·`paycore.checkout.pages{result}`·`paycore.checkout.cancels{result}`·`paycore.authentication.starts{result}`·`paycore.authentication.returns{result}`·`paycore.authentication.registrations{outcome}`·`paycore.authentication.lookups{outcome}`·`paycore.payment.checkout_expiry{result}`, timer `paycore.authentication.lookup.duration`, gauge `paycore.payment.checkout_window.elapsed_requested`·`paycore.authentication.sessions.unresolved`·`paycore.checkout.tokens.unexchanged_expired`(jOOQ 비권위 점검). 시험: gauge 값·label 이름이 result/outcome뿐(ID 없음) — `CheckoutObservabilitySpec`, 조회·등록 지표 — `SimulatorCardAuthenticationAdapterSpec`, 만료 작업 counter — `RequestedPaymentExpirySchedulerSpec` |
| 편차 13(지표 이름) | §15의 `paycore.authentication.sessions{transition}` 하나 대신 진입점별 `paycore.authentication.starts{result}`·`returns{result}`·`registrations{outcome}`로 전이를 센다(결과 label이 전이와 1:1 — 예: returns `verified`·`failed`·`ignored`). 전이 자체의 사실은 `authentication_session_event`(append-only)다. 정확히 전이 단위 counter가 필요하면 commit 뒤 기록(TransactionSynchronization)이 필요해 후속으로 둔다 — Coordinator 확인 대상 |
| 문서 | `DOMAIN-DEFINITION.md`(AuthenticationSession AUTHN-001 규칙·CheckoutWindow·결제창 토큰/세션·PaymentExpired 행, §4.1 결제창 만료 문장, §7 코드 이름), `HARNESS-VERIFICATION-CONTRACT.md` §7(하한 갱신·배포 메모), `MASTER-SNAPSHOT-002…` §8.2·§13 이관 3(AUTHN-001이 V7·V8·V8_1 → AUTH-001은 V9부터). 본문의 지운 `assignMissingCheckoutWindow` 언급(§7.5.1 표·§7.5.4·§7.5.5)을 정리 |
| 하네스 | `GRADLE-CHECK.minTests`·`TEST-SUMMARY` 하한 1103 → 1480(전체 check 2회차 합계 — 위). HARNESS-SELFTEST 시험 수는 바뀌지 않음(81) |

변경한 시험 기대값(S12·검토 #4):

| 시험 | 이전 | 이후 | 근거 |
|---|---|---|---|
| `CheckoutExpiryConcurrencySpec` C7(만료가 이김) | `CompleteCardAuthenticationResult.SessionExpired`(401, 편차 1) | `CheckoutClosed(CHECKOUT_EXPIRED, failUrl?paymentId=…&code=CHECKOUT_EXPIRED)`(410) | 검토 #4 m-1 — 원 명세 §14 C7 복원 |
| `CheckoutExpiryConcurrencySpec` C12·C14·C15·C16 | 3회 동시, 승자 출력만 | 회차별 순서 강제 + 승자 집합 단언(C16 3 → 5회차) | 검토 #4 n-4(강화) |
| `RequestedPaymentExpiryServiceSpec` PaymentExpired append 뒤 `IllegalStateException` | `failed ≥ 1` | `defects ≥ 1`(+ 새 행: `PersistenceFailure.LockTimeout`은 `failed ≥ 1`) | 검토 #4 n-3 — 결함과 일시 실패 구분 |
| `RequestedPaymentExpirySchedulerSpec` | `result` label expired·no_change·failed | + `defect` | 검토 #4 n-3 |
| `ArchitectureSpec` 결제창 web 규칙 | `org.springframework.boot.` 전체·JDK 전체 허용 | Boot 3 하위 패키지만, `java.sql`·`javax.sql` 거부 | 검토 #4 n-1(강화) |
| `SimulatorCardAuthenticationAdapterSpec` | adapter 생성자 `(settings)` | `(settings, registry)` + 지표 행 1, stub 요청별 스레드 | 지표 추가(입력 변경 — 기대값 불변) |

#### 24.14.1 Coordinator 후속 판정(2026-10-17) 처리 — 편차 13 수용·인증 시작 m-1 규칙·README

Coordinator 판정: (1) 편차 13 **수용** — 진입점별 counter를 유지하고 §15가 실제 내보내는 counter·label 값·전이 대응을 적는다(§15 표 갱신·§15.1 대응표 추가).
commit 뒤에 기록하는 정확한 전이 단위 counter(`TransactionSynchronization` afterCommit, 예 `paycore.authentication.sessions{transition}`)는 **후속 후보**다.
(2) 인증 시작도 m-1 규칙을 따른다. (3) README n-5는 건너뛴다(HARNESS-VERIFICATION-CONTRACT §7·§20 R13·§22-11로 충분 — Coordinator 기록).

| 시각(UTC) | 단계 | 대상 | 결과 |
|---|---|---|---|
| 2026-10-17T02:53:49Z | RED-A | `CheckoutExpiryConcurrencySpec` 새 행(만료 작업이 닫은 결제창에서 인증 시작 2회 → 410 CheckoutTimeOver(CHECKOUT_EXPIRED), 등록 호출 0, 상태 불변), `CardAuthenticationFlowSpec` 기존 행에 추가 단언(COMPLETED(PAYMENT_NOT_AVAILABLE) 결제창의 다음 시작 → 409 PaymentNotAvailable + failUrl, 등록 호출 0) | exit 1, 27 중 2 실패 — 둘 다 `SessionExpired`(401) (`s12b-reda`) |
| 2026-10-17T02:56:23Z | GREEN | 위 2 Spec + `CheckoutAuthenticationWebSpec`·`CheckoutWebSpec`·`CheckoutTwoWindowsSpec`·`ArchitectureSpec` | exit 0, 77 / 77 (`s12b-green`) |
| 2026-10-17T03:13:30Z | GREEN(전체 check, 같은 명령) | `--no-daemon --no-build-cache --rerun-tasks check`(시작 02:57:49Z) | exit 0, **1481**: payment-api 965(+1 새 행), master-api 319, institution-simulator 30, financial-batch 26, internal-admin 29, libs/test-support 112, 실패·오류·skip 0. C15 회차별: start·start·job(작업 먼저 회차의 시작 결과는 이제 `CheckoutTimeOver`) (`s12b-fullcheck`, 보관 `~/authn-evidence/s12b-fullcheck`) |
| 2026-10-17T03:14:27Z | HARNESS-SELFTEST·TEST-SUMMARY(등록 argv) | 하한 1480 → 1481(`GRADLE-CHECK.minTests`·`TEST-SUMMARY` argv), `--not-before 2026-10-17T02:57:49Z` | SELFTEST exit 0 Ran 81 OK, TEST-SUMMARY exit 0 PASS total 1481 (`s12b-selftest`, `s12b-testsummary`). 의존성 변경 없음 — GRADLE-DEPS·CVE-SCAN은 02:47~02:48Z 결과 그대로(§24.14) |

| # | 처리 |
|---|---|
| 인증 시작 m-1 | `CardAuthenticationStartService`: 쿠키 digest가 맞고 결제창 세션이 EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE)이면 — 비잠금 식별 단계(`CheckoutSessionGate.findMatching`)와 잠금 아래 재판정 단계(`gate.gate`가 Expired일 때 `gate.lockedRecord`) 모두 — Payment를 잠가 상태를 읽고 `closedCode`로: REQUESTED·EXPIRED → 410 `CheckoutTimeOver(failUrl…code=CHECKOUT_EXPIRED)`, 그 밖 → 409 `PaymentNotAvailable(failUrl…code=PAYMENT_NOT_AVAILABLE)`. 상태 변경 없음, 기관 등록 호출 없음(TX-A에서 끝남). 판정 함수 `CheckoutSessionGate.closedForPayment`를 복귀(`CardAuthenticationCompletionService`)와 공유. §6.3 행 갱신(§7.4 전이 표는 바뀌지 않음 — 상태 변경 없는 응답) |
| 구현 결정(가역) | 시작 결과에 새 `CheckoutClosed` 타입을 만들지 않고 기존 `CheckoutTimeOver`(410)·`PaymentNotAvailable`(409, 둘 다 failRedirect 포함)를 썼다 — HTTP·화면·지표(`time_over`/`not_available`)가 Coordinator가 정한 결과와 같고 controller 변경이 없다 |
| 범위 관찰(Coordinator 확인) | **결제창 취소**(`CheckoutCancellationService`)는 이 판정 범위 밖이라 그대로다 — 이미 Payment 쪽 사유로 닫힌 결제창의 취소는 지금 401 `SessionExpired`. 같은 규칙을 적용할지 결정 필요(§6.3 행에 "취소는 아직 401"로 적었다) |
| 문서 | §15 Observability 행을 실제 counter로 바꾸고 §15.1(label ↔ 전이) 추가, §6.3 m-1 행에서 "취소"를 빼고 인증 시작의 등록 미호출을 적음 |

변경한 시험 기대값(후속):

| 시험 | 이전 | 이후 | 근거 |
|---|---|---|---|
| `CardAuthenticationFlowSpec` "a payment that is no longer REQUESTED …" | 첫 시작 409만 확인(다음 시작은 미확인 — 동작은 401) | + 다음 시작도 409 `PaymentNotAvailable(failUrl…PAYMENT_NOT_AVAILABLE)`, 등록 호출 0, 결제창 세션·인증 세션 불변 | Coordinator 후속 판정(m-1을 인증 시작에 적용) |
| `CheckoutExpiryConcurrencySpec`(새 행) | — | 만료 작업 뒤 시작 2회 모두 410 `CheckoutTimeOver(failUrl…CHECKOUT_EXPIRED)`, 등록 0, PaymentExpired 1 | 같은 판정 |
| `CheckoutExpiryConcurrencySpec` C15 동시 | 허용 결과 {CheckoutTimeOver, SessionExpired, PaymentNotAvailable} | 같음(작업이 먼저인 회차는 이제 CheckoutTimeOver — 허용 집합 안) | 기대값 변경 없음 |

##### 24.14.1 (계속) 결제창 취소에도 m-1 규칙(Coordinator 판정 2026-10-17)

위 "범위 관찰(취소)" 행의 결정: 취소도 같은 규칙을 따른다. 전체 check는 다시 돌리지 않는다(Coordinator 지시 — VERIFY-GATE가 최종 트리에서 전체를 돈다). `minTests`는 하한이라 1481 그대로(새 시험 1개로 실제 수는 1482 예상 — 아래 대상 실행만 확인).

| 시각(UTC) | 단계 | 대상 | 결과 |
|---|---|---|---|
| 2026-10-17T03:17:32Z | RED-A | `CheckoutExpiryConcurrencySpec` 새 행(시작 → IN_PROGRESS 뒤 만료 작업이 닫은 결제창에서 취소 2회 → 410 CheckoutTimeOver(CHECKOUT_EXPIRED), 작업의 EXPIRED(PAYMENT_CLOSED) 유지, FAILED 이벤트 0), `CardAuthenticationFlowSpec` 기존 행에 추가 단언(COMPLETED(PAYMENT_NOT_AVAILABLE) 결제창의 취소 → 409 PaymentNotAvailable + failUrl, 결제창 세션 불변) | exit 1, 28 중 2 실패 — 둘 다 `SessionExpired`(401) (`s12c-reda`) |
| 2026-10-17T03:19:40Z | GREEN(대상) | 위 2 Spec + `CheckoutAuthenticationWebSpec`·`CheckoutWebSpec`·`CheckoutTwoWindowsSpec`·`CheckoutErrorPageLeakSpec`·`CheckoutLogRedactionSpec`·`ArchitectureSpec` | exit 0, 80 / 80, 8 Spec (`s12c-green`) |
| 2026-10-17T03:20:00Z | HARNESS-SELFTEST | 등록 argv | exit 0, Ran 81, OK (`s12c-selftest`) |

| # | 처리 |
|---|---|
| 취소 m-1 | `CheckoutCancellationService`: 비잠금 식별(`gate.findMatching`)·잠금 아래 재판정(`gate.gate`가 Expired일 때 `gate.lockedRecord`) 모두에서 `CheckoutSessionGate.closedForPayment`면 Payment를 잠가 `closedCode`로 REQUESTED·EXPIRED → 410 `CheckoutTimeOver(failUrl…CHECKOUT_EXPIRED)`, 그 밖 → 409 `PaymentNotAvailable(failUrl…PAYMENT_NOT_AVAILABLE)`. 상태 변경·취소 이벤트(FAILED(USER_CANCELLED)) 없음. §6.3 행에 취소 추가, §15.1에 cancels `time_over`/`not_available`·무변경 label 행 추가. 위 "범위 관찰(취소)" 행은 이 판정으로 해소 |

변경한 시험 기대값(취소):

| 시험 | 이전 | 이후 | 근거 |
|---|---|---|---|
| `CardAuthenticationFlowSpec` "a payment that is no longer REQUESTED …" | 닫힌 결제창의 취소 미확인(동작은 401) | 취소 → 409 `PaymentNotAvailable(failUrl…PAYMENT_NOT_AVAILABLE)`, 결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE) 유지 | Coordinator 판정(취소에 m-1 적용) |
| `CheckoutExpiryConcurrencySpec`(새 행) | — | 만료 작업 뒤 취소 2회 모두 410 `CheckoutTimeOver(failUrl…CHECKOUT_EXPIRED)`, 인증 세션 EXPIRED(PAYMENT_CLOSED) 유지, FAILED 이벤트 0, PaymentExpired 1 | 같은 판정 |
| `CardAuthenticationFlowSpec` "cancel fails the open session …" 마지막 줄(이미 CANCELLED인 결제창의 두 번째 취소) | `SessionExpired` | 같음(CANCELLED는 Payment 쪽 사유가 아니다) | 기대값 변경 없음 |
