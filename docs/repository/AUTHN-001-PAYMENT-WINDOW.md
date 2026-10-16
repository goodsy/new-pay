# AUTHN-001 결제창(Thymeleaf)·AuthenticationSession endpoint·가상 인증기관 Simulator 명세 (1단계 명세)

작성 2026-10-16 KST. **1단계 명세이며 코드·migration·빌드 변경 없음.** 기준 commit `273068a`(ARCH-STRUCTURE-001). 작업 트리에는 Coordinator 소유
미커밋 변경(`docs/DECISIONS.md`·`docs/repository/ARCHITECTURE-CONVENTIONS.md`·`docs/repository/ARCH-STRUCTURE-001-MAPPING.md`·`harness/*.json`)이 있으며
이 문서는 읽기만 했다. 이 문서는 새 업무 정책을 채택하지 않는다 — §23 질문 후보는 답변 전 해당 운영 동작을 구현하지 않는다. 2단계 TDD는 Coordinator가
질문을 처리한 뒤 시작한다.

표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — Coordinator가
의심되면 질문으로 올린다), **구현 결정(AN-xx)** = 금융·보안 의미가 없는 가역적 선택(§21에 모음), **미결(Q-AUTHN-xx)** = §23 질문 후보,
**Demo** = Simulator·시험 전용 값(운영값 아님), **범위 관찰** = 작업 경계·기준 문서 편차에 관한 Coordinator 확인 사항(§22).

입력 문서(sha256 앞 8자…끝 4자, 2026-10-16 읽은 시점):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001~013, **D-AUTHN-001~004**, D-CPD-001~004, D-CP-*, D-CPC-*, D-CPA-001, D-MD-008·009·014, D-MS-004, D-MS2-004·005) | 확정(미커밋 작업 트리) | `d45abee7…8bb4` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 작업 | `harness/tasks.json` AUTHN-001 AC 9개·allowedWritePaths, AUTH-001 AC | Coordinator 소유(미커밋) | `4d28b7a2…5c6b` |
| 질문 | `harness/questions.json` Q-AUTHN-01~04 원문 | Coordinator 소유(미커밋) | `cbbfc33a…6c0c` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§4.1·§4.3·§5.3·§7·§9·§18·§19) | 구현 반영 명세 | `563b0180…cefd` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9·§9.2·§10~§14) | 관례(미커밋 §14 보강 포함) | `eb8c6fe4…7f07` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례(미커밋) | `4bb8712b…73fc` |
| CP계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 | `03eecede…ae83` |
| CP-API | `docs/repository/CP-API-001-CREATE-PAYMENT-API.md`(§4·§5·§7·§8) | 구현 반영 명세 | `4469e8e0…78e7` |
| MS1 | `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md`(§3.1·§3.5·§5) | 구현 반영 명세 | `bd761cb4…a554` |
| MS2 | `docs/repository/MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md`(§4·§5.2·§5.4) | 구현 반영 명세 | `b555e1fa…d458` |
| 하네스 | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md` §7, `harness/commands.json` `6c698958…06f4`, `harness/scripts/cve-scan.py` `7f539329…b258` | 관례 | `52cff39a…250b` |
| A01·A05·A08 | `docs/baseline/architecture/01-runtime…` `193f15dc…719f`, `05-security…` `e9dcabd0…b956`, `08-testing…`(Test Fixture Policy) `086f9300…9152` | 아키텍처 자료 | — |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md` §3·§4(D-AUTH-001로 채택) | 확정 | `3cf08d32…11be` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §5.5 | 확정 | `a8b02b77…7ae5` |
| 코드 | `payment/domain/AuthenticationSession.kt` `ba680597…ee62`, `RequestedPaymentExpiry.kt` `70ee1b3e…a76c`, `AuthorizationStart.kt` `0a5141c5…acc5`, `PaymentPlan.kt` `b18e8777…b4cb`, `V1__payment_core.sql` `9e2b9dca…6545`, `entitlement/domain/SnapshotSchema.kt` `54ade904…e3f2`, `adapter/web/PaymentApiSecurityConfiguration.kt` `575c88d7…8185`; MASTER `SiteChannel.kt` `7d5e56d8…a533`, `EntitlementSnapshot.kt` `5d7a264b…f55f` | `273068a` | — |

---

## 1. 범위와 범위 밖

| 구분 | 내용 | 근거 |
|---|---|---|
| 범위 | 가맹점 서버용 결제창 토큰 발급 API `POST /v1/payments/{paymentId}/checkout`(MID 인증) | **확정 D-AUTHN-001** |
| 범위 | 결제창(Thymeleaf, payment-api의 별도 checkout connector): 1회 토큰 교환 → 결제창 쿠키 세션 + CSRF, 카드사·PaymentPlan 선택, 인증 시작, 인증 결과 복귀 처리, 취소, 가맹점 successUrl/failUrl redirect | **확정 D-AUTHN-001**, AC 6 |
| 범위 | `AuthenticationSession`·결제창 토큰·결제창 세션 영속화(PAYMENT V7/V8), `AuthenticationSessionRepository`, 표 단위 runtime 권한 | AC 7 |
| 범위 | MASTER Site WEB host를 권리 Snapshot(schemaVersion 3)으로 PAYMENT에 전파, successUrl/failUrl 검증(https + host 정확히 일치), 전파 없음/모호 → 토큰 발급 거절 | **확정 D-AUTHN-002**(master-api 변경 포함) |
| 범위 | 인증 결과 서버 간 조회(1회용 결과 참조 + state), 바인딩·유효기간 대조, 조회 실패 ≠ VERIFIED, 외부 호출은 DB TX 밖 | **확정 D-AUTHN-003** |
| 범위 | 새 실행 단위 `apps/institution-simulator`(별도 프로세스·포트·MySQL): 고객 인증 화면·등록/결과 조회 API·가상 카드 시나리오, payment-api HTTP Adapter, REAL Adapter 없음, 격리 시험, 하네스 모듈 등록 | **확정 D-AUTHN-004** |
| 범위(미결 포함) | REQUESTED Payment 결제창 만료(EXPIRED) 발동 Use Case·주기 작업·경합 시험 | **Q-AUTHN-05**(§23) — 답 전 구현 안 함 |
| 범위 밖 | confirm API·승인 시작·인증 근거 귀속(`bindTo`)·기관 승인 전송(가상 VAN TCP) | AUTH-001(AC 9) |
| 범위 밖 | Webhook, React 결제창, 실제 기관 인증 모듈·전문·인증 시험, 앱카드/ISP 앱 전환 복귀 | WEBHOOK-001, AC 9, 허용 §5.6 |
| 범위 밖 | 결제 조회 API(GetPayment) | CP-QUERY-001 |
| 범위 밖 | 토큰·세션 행 보존기간·삭제 Job | 후속(§22-6) |

카드번호·CVC·인증 비밀번호는 어디에도 없다 — 가상 카드 참조만 쓴다(**확정** 허용 §5.5, 범위 §4). TTL·timeout·재시도 값은 버전 있는 설정이며 코드에 운영
기본값이 없고 시험·Simulator는 Demo 값만 쓴다(**확정 D-AUTH-009**).

---

## 2. AC 대응표(tasks.json AUTHN-001 → 명세 절 → 계획 시험)

| # | AC(요약) | 절 | 계획 시험(이름은 가역적) |
|---|---|---|---|
| 1 | (D-AUTH-001) 토큰 바인딩, 서버 검증(state/nonce), AuthenticationSession 영속화, 성공/실패/취소/만료·중복 callback·재사용, Simulator/REAL 격리, 카드정보 비노출 | §4, §7, §13, §17 | `AuthenticationResultCheckSpec`(판정표), `CardAuthenticationCompletionSpec`(실 MySQL·Simulator stub 시나리오 전부), `AuthenticationReuseSpec`(결과 참조·state·토큰 재사용), `AuthenticationBackendIsolationSpec`, `NoCardDataSchemaSpec`(PAYMENT·Simulator 컬럼 이름 검사) |
| 2 | (D-AUTHN-001) 토큰 API: 소유 MID·환경, REQUESTED만, 256bit·hash·짧은 만료·1회 교환, 쿠키 세션(HttpOnly·Secure·SameSite·ID 재발급)·CSRF, 결제 생성 API 불변 | §5, §6, §12 | `CheckoutTokenIssuanceSpec`(서비스), `CheckoutTokenApiSpec`(HTTP: 다른 MID 404·종료 409·형식 400), `CheckoutExchangeSpec`(재사용·만료·동시 교환 8), `CheckoutCookieSecuritySpec`, 기존 `CreatePaymentApiSpec` 응답 키 집합 불변 |
| 3 | (D-AUTHN-002) Site WEB host 전파(Inbox 같은 TX), https + host 정확히 일치(포트·userinfo·서브도메인·IP·스킴 변형 거절), 전파 없음/모호 → 거절 | §8, §9 | `MerchantReturnUrlSpec`(문법 표 + `java.net.URI` 교차 확인), MASTER `EntitlementSnapshotAssemblerSpec` v3·`SchemaPromotionV3Spec`, PAYMENT `EntitlementContentV3Spec`·`SiteWebHostQuerySpec`(v2 head·APP·종료 Site·만료 → 거절), golden v3(harness) |
| 4 | (D-AUTHN-003) 서버 간 조회로만 VERIFIED, state·바인딩·유효기간 대조, 조회 실패·timeout ≠ VERIFIED(재조회/FAILED 확정), 외부 호출 TX 밖, 중복 멱등·다른 증거 거부·늦은 결과 무시 | §7 | `AuthenticationResultCheckSpec`, `CardAuthenticationCompletionSpec`(timeout → IN_PROGRESS 유지 → 재조회 VERIFIED, `LookupOutsideTransactionSpec`: 조회 중 연결의 열린 TX·잠금 0) |
| 5 | (D-AUTHN-004) `apps/institution-simulator`, 가상 카드 참조, 시나리오, issuer/acquirer/provider 구분, 결과 조회 API, HTTP Adapter만, REAL 없음, TEST 외 기동 거부, 하네스 등록 | §13, §18 | Simulator `AuthenticationTransactionSpec`·`SimulatorApiContractSpec`(golden)·`SimulatorRestartDurabilitySpec`·`SimulatorArchitectureSpec`, payment-api `AuthenticationBackendIsolationSpec`, harness `test_module_registry`·`test_authentication_contract_golden` |
| 6 | Thymeleaf 결제창: 카드사·Plan 선택(서버 판정), XSS·CSP·frame-ancestors 'none'·nosniff·Referrer-Policy, 오류 화면 내부 사유 비노출, 카드정보·Secret·토큰 원문 로그 금지 | §6, §12 | `CheckoutPageSecuritySpec`(헤더 정확 값·escape), `CheckoutTemplateScanSpec`(`th:utext`·`<script` 금지 + 위반 fixture), `CheckoutErrorPageLeakSpec`, `CheckoutLogRedactionSpec`(로그 캡처) |
| 7 | PAYMENT migration, 표 단위 권한(§9.2), 잠금 순서 CA-05 부분열, `AuthenticationSessionRepository` | §10, §11 | `CheckoutSchemaSpec`(CHECK·생성 컬럼 UNIQUE·collation), `DatabaseAccountSeparationSpec` 고정 기대값 갱신(새 표 추가 — 완화 아님)·append-only 1142, `AuthenticationSessionRepositorySpec`, 경합 시험(§14) |
| 8 | 명세(1·2단계), TDD, Test Fixture Policy, 8대 원칙, VERIFY-GATE PASS·시험 1220 이상·harness 72 이상, DEPS·CVE | 이 문서, §15, §17, §18 | VERIFY-GATE, GRADLE-DEPS-PAYMENT-API·MASTER-API·INSTITUTION-SIMULATOR, CVE-SCAN |
| 9 | 범위 밖 목록 | §1 | — |

---

## 3. 현황 조사(2026-10-16, `273068a`)

| 영역 | 있는 것 | 없는 것(이 작업이 정의) |
|---|---|---|
| domain `payment` | `AuthenticationSession`(5상태, `create`(비종료·귀속 세션 있으면 거부)·`start()`·`verify`·`fail`·`expire`·`bindingRejection`·`bindTo`·`reconstitute`), `AuthenticationBinding`(paymentId·OrderKey·Money·Plan·cardCompany), `VerifiedAuthentication`(evidenceRef·binding·cardType·issuer/acquirer/provider), `RequestedPaymentExpiry`, `PaymentPlanRule.check(plan, cardType, …)`, `AuthenticationSessionId` | 상관 값(state digest·기관 거래 참조), 만료 원인 저장, 대체(supersede), 결과 판정 domain 서비스, 반환 URL 값, cardType 없는 Plan 사전 판정 |
| application `payment` | `CreatePaymentService`·`LocalTransactions`(TX 소유, 교착 재시도 3회)·`PersistenceFailure` 분류, `MerchantAuthenticator` | 토큰·결제창·인증 Use Case, `CardAuthenticationPort`, 저장 Port 4개 |
| web `payment` | 공개 포트 Bearer 체인(stateless, CSRF 끔), 내부 connector 격리(`ApiEntryFilter`), 엄격 JSON 파서, `ApiErrorCode` | checkout connector·브라우저 보안 체인(CSRF·CSP·쿠키), Thymeleaf(payment-api 미의존 — 카탈로그에는 있음, internal-admin 사용) |
| persistence(PAYMENT V1~V6) | payment·control·hold·idempotency·outbox·entitlement 투영·credential 투영 | `checkout_token`·`checkout_session`·`authentication_session`·`authentication_session_event` |
| 권리 Snapshot | schemaVersion 1·2(MS2), 해석기는 모르는/빠진 필드 거절, 같은 revision·다른 hash = INTEGRITY_CONFLICT, 스키마 승격은 revision +1(MS2 §5.2, MASTER `EntitlementLeaseService`에 구현됨). **Site 채널 식별값은 MS1 §3.1에서 의도적으로 제외** | `sites[].channel`(WEB host) — D-AUTHN-002가 MS1 제외를 대체 |
| MASTER | `merchant_site(channel_type, channel_identifier)` 원본, `SiteChannel`(WEB = 소문자 host만, IP 리터럴 거절, D-MD-009·014), 채널 불변(등록만, 변경 연산 없음), 활성 채널 전역 UNIQUE(D-MD-008) | 조립·직렬화에 채널 포함 |
| 실행 단위 | master-api·payment-api·financial-batch·internal-admin | `apps/institution-simulator` |
| 하네스 | `test_module_registry`(settings ↔ commands.json ↔ cve MODULES 강제), GRADLE-CHECK minTests 1103(마지막 gate 실측 1220), SELFTEST 72 | 5번째 실행 단위 등록, 인증 계약 golden |

발견한 제약:

1. **기준 문서 편차**: A01 실행 단위 표는 `payment-window`(결제 사용자 화면, DB 없음)를 별도 실행 단위로 둔다. AC·allowedWritePaths는 결제창을 payment-api에
   둔다(새 `apps/payment-window` 경로 없음). → §11.1에서 payment-api 별도 connector로 두고 추출 가능 구조를 유지, §22-1 범위 관찰.
2. **Payment에는 결제창 만료 시각이 없다**(`payment` 표·domain 모두). D-AUTH-005는 "결제창 만료 시 EXPIRED"를 정했지만 발동 기준 시각은 없다
   (`DOMAIN-DEFINITION.md` "결제창 만료 시간 수치는 정하지 않았다") → **Q-AUTHN-05**.
3. **환경 축**: Payment `environment`(TEST/LIVE)는 요청 축이고 한 payment-api가 둘 다 처리한다(D-MD-003). 배포 단계(dev/prod) 개념은 저장소에 없다. AC의
   "TEST 외 환경에서 Simulator 연결 기동 거부"는 Payment 환경 축으로 해석한다(§13.6 도출, §22-2).
4. `AuthenticationSession.start()`는 인자가 없어 기관 거래 참조를 남길 곳이 없고, `expire(cause)`는 원인을 저장하지 않는다(감사 공백) → §7.1 domain 보강.
5. `create`는 비종료 세션만 막으므로 **VERIFIED 미귀속 세션이 여러 개** 생길 수 있다(재인증 시) → §7.2 대체 규칙(AN-07).
6. PAYMENT 다음 migration 번호는 V7(MS2 §8.2는 "AUTH-001이 V7 이후"라고 적었다) → 이 작업이 V7/V8을 쓰고 AUTH-001은 V9부터(§22-4).

---

## 4. 종단 흐름

### 4.1 단계와 TX 경계

```text
[가맹점 서버] POST /v1/payments (기존, 불변) → Payment REQUESTED, paymentKey
[가맹점 서버] POST /v1/payments/{paymentId}/checkout  (Bearer, {successUrl, failUrl})
  TX-I  head(S) → control(X) → payment(X) → checkout_token(X)
        소유 MID·환경 확인, REQUESTED·hold 없음, Site WEB host(Snapshot v3) 조회, URL 검증,
        같은 Payment의 미교환 토큰 SUPERSEDED, 새 토큰(digest만) INSERT                       ── 커밋
  응답  201 {paymentId, checkoutUrl(=…/checkout?token=<원문>), expiresAt}  (원문은 응답에만, 저장·로그 없음)
[브라우저]   GET /checkout?token=…   (checkout connector)
  TX-X  payment(X) → checkout_session(X) → checkout_token(X)
        토큰 digest 조회 → ISSUED·미만료·바인딩 = Payment 생성 값·REQUESTED 확인 → 같은 Payment ACTIVE 세션 REVOKED
        → 새 checkout_session(쿠키 비밀은 서버 생성, digest만 저장) → 토큰 CONSUMED                ── 커밋
  응답  303 /checkout  + Set-Cookie __Host-pc_checkout=<세션 비밀>
[브라우저]   GET /checkout → 결제창(금액·통화·주문명·Site host·카드사 목록·Plan 목록·CSRF)   (조회: jOOQ, 비권위)
[브라우저]   POST /checkout/authentication {cardCompany, plan, _csrf}
  서버 판정: 카드사 ∈ 인증 프로파일 allowlist, Plan 사전 판정(정책 Demo), Payment 환경의 인증 backend 존재
  TX-A  payment(X) → authentication_session(X)
        같은 Payment의 미폐기·미귀속 세션 EXPIRED(SUPERSEDED/TTL_ELAPSED) → 새 세션 CREATED(바인딩·state digest)  ── 커밋
  TX 밖 CardAuthenticationPort.register(바인딩, state 원문, returnUrl) → 기관 거래 참조·고객 URL
  TX-B  payment(X) → authentication_session(X): CREATED → IN_PROGRESS(기관 거래 참조)                     ── 커밋
  응답  303 <Simulator 고객 URL>
[Simulator]  고객이 가상 카드 선택 → 인증/실패/취소 → 303 <PG returnUrl>?resultRef=…&state=…
[브라우저]   GET /checkout/authentication/return?resultRef=…&state=…  (쿠키 필수)
  검사  쿠키 → ACTIVE checkout_session → 그 세션의 비종료 인증 세션 → URL state digest 일치(불일치 → 거절·상태 불변)
  TX 밖 CardAuthenticationPort.lookup(resultRef) → 결과(또는 Unverifiable)
  TX-C  payment(X) → authentication_session(X) → checkout_session(X)
        domain AuthenticationResultCheck 판정 → VERIFIED / FAILED / EXPIRED / 불변 + 이벤트 append        ── 커밋
  응답  VERIFIED → 303 successUrl?paymentId=…&authenticationRef=…   (checkout_session COMPLETED)
        FAILED   → 200 실패 화면(같은 결제창에서 다시 시도 / failUrl?paymentId=…&code=…)
        Unverifiable → 503 "확인 중" 화면(같은 복귀 URL로 재시도 링크)
[가맹점 서버] confirm(AUTH-001 범위)
```

- **확정**: 외부 호출(등록·조회)은 DB TX 밖이고 잠금을 쥐지 않는다(CLAUDE.md, AC1 §4.1, D-AUTHN-003). 업무 효과·요약·이벤트는 PAYMENT 같은 로컬 TX다.
- **확정**: 인증 성공 ≠ 승인 성공. 이 흐름에서 Payment는 REQUESTED를 유지한다(범위 §3-5, AC1 §4.1). Outbox 이벤트 없음 — 금융 효과가 없고 AC1 §11이
  "인증 세션 전이는 내부 상태"로 정했다.
- 브라우저 successUrl 표시만 신뢰하지 않는다: 가맹점은 confirm에서 서버가 다시 바인딩을 대조한다(AC1 §4.3, AUTH-001).

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
| 헤더 | `Content-Type`·`Accept`·본문 16 KiB 규칙은 CP-API §4.1 그대로. **`Idempotency-Key`는 받지 않는다** — 있으면 400 `INVALID_REQUEST`(field `Idempotency-Key`) | §5.4, AN-02 |
| 본문 | 엄격 JSON(CP-API §4.2 방식), 닫힌 필드 2개: `successUrl`, `failUrl`(둘 다 필수 문자열, null 금지) | AN-03 |
| 필드 검증 | 각 URL은 §8.1 문법(형식 위반 → 400 `INVALID_RETURN_URL` + field). host 일치는 §8.2(불일치 → 422 `RETURN_URL_NOT_ALLOWED`) | **확정 D-AUTHN-002** |

### 5.2 판정 순서(TX-I, 앞 단계가 결정하면 멈춤)

1. 형식 검사(400) — TX 전.
2. TX-I 시작. Payment 존재 + `environment`·`merchant_account_id` = 인증 주체(아니면 404 — 다른 MID·환경은 존재 비노출, A05 "소유 MID 검증").
3. 인증 backend: Payment 환경에 설정된 인증 backend가 없으면(현재 LIVE) 422 `CHECKOUT_NOT_AVAILABLE`(§13.6).
4. Site WEB host: `SiteWebHostPort.resolve(mid, siteId, now)`(§9.4) — Unavailable(Snapshot 없음·만료·막힘·v1/v2 head) → 503 `CHECKOUT_UNAVAILABLE`; host 없음(APP 채널·
   종료 Site·Snapshot에 Site 없음) → 422 `RETURN_URL_NOT_ALLOWED`(fail-closed, D-AUTHN-002).
5. 잠금: control(X) → payment(X). incident hold 활성 → 409 `ORDER_ON_HOLD`(**도출**: hold 중 승인 시작 불가 D-CPD-004·AC1 §4.2 → 인증 창을 열어도 confirm이 거절된다,
   fail-early). Payment가 FAILED·EXPIRED → 409 `PAYMENT_TERMINATED`, AUTHORIZING·UNKNOWN·AUTHORIZED → 409 `PAYMENT_AUTHORIZATION_STARTED`.
6. URL host = Site host(§8.2), 아니면 422 `RETURN_URL_NOT_ALLOWED`.
7. 같은 Payment의 ISSUED 토큰 → SUPERSEDED(만료 지났으면 EXPIRED) — 미교환 토큰은 Payment당 최대 1개(AN-04).
8. 새 토큰: 256bit 난수 원문(`ckt_` + base64url 43자), DB에는 `cth1:` + SHA-256 hex만. 바인딩(Payment·OrderKey(환경·MID·주문)·Site·금액·통화)·successUrl·
   failUrl·검증에 쓴 Site host·Snapshot 버전·발급 Credential·정책 버전·`expires_at = now + checkout.token-ttl`(Demo) 저장. (Q-AUTHN-05 답이 A면 Payment 만료
   시각으로 자른다.)
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
| FAILED·EXPIRED | 409 | `PAYMENT_TERMINATED` | false | 없음 |
| AUTHORIZING·UNKNOWN·AUTHORIZED | 409 | `PAYMENT_AUTHORIZATION_STARTED` | false | 없음 |
| host 불일치·host 없음 | 422 | `RETURN_URL_NOT_ALLOWED` | false | 없음 |
| 인증 backend 없음(LIVE) | 422 | `CHECKOUT_NOT_AVAILABLE` | false | 없음 |
| Snapshot 판정 불가·DB 일시 오류·교착 재시도 소진·commit 불명확 | 503 | `CHECKOUT_UNAVAILABLE` | true | 있음 |
| 결함 | 500 | `INTERNAL_ERROR` | false | 없음 |

- 새 code 6개는 기존 `ApiErrorCode` 고정 문구 관례를 따른다(문구에 Idempotency-Key 언급 없음 — 이 API는 멱등키가 없다). 응답에 URL·host·Snapshot 버전·내부
  사유를 echo하지 않는다(CP-API §7.5와 같다). 구현 결정 AN-05.
- commit 불명확(503)이어도 안전하다: 토큰이 저장됐더라도 원문이 전달되지 않았고, 재요청이 새 토큰을 내며 이전 것을 SUPERSEDED한다.

### 5.4 멱등성 판단(왜 멱등키 재생이 아닌가)

- CLAUDE.md·D-CP-003의 "같은 요청키·같은 내용 = 과거 결과"는 **결과를 다시 줄 수 있는 요청**의 규칙이다. 이 API의 결과는 비밀(토큰 원문)이고 D-AUTHN-001이
  "DB에는 hash만"을 정했으므로 과거 결과(원문)를 재생할 수 없다. 원문을 저장해 재생하면 D-AUTHN-001 위반이다.
- 이 API는 금융 효과가 없다: Payment·주문 슬롯·한도·Outbox를 바꾸지 않는다. 반복 호출의 효과는 "유효 토큰이 최신 것 하나"로 수렴한다(§5.2-7) — 같은 요청을
  N번 보내도 남는 상태는 1번과 같다(효과 멱등). 그래서 멱등키를 받지 않고(AN-02), 재시도 안내는 "그냥 다시 호출"이다.
- 다른 키·같은 주문 재사용 규칙(활성 시도 재사용)은 이 API와 무관하다(새 Payment를 만들지 않는다).

---

## 6. 결제창 endpoint(checkout connector)

### 6.1 경로

| 방법·경로 | 동작 | 성공 응답 |
|---|---|---|
| `GET /checkout?token=…` | 토큰 교환(TX-X) | 303 `/checkout` + `Set-Cookie` |
| `GET /checkout` | 결제창 화면(쿠키 세션 필수) | 200 HTML |
| `POST /checkout/authentication` | 카드사·Plan 제출 → 인증 시작(TX-A·등록·TX-B) | 303 Simulator 고객 URL |
| `GET /checkout/authentication/return?resultRef=…&state=…` | 결과 처리(검사·조회·TX-C) | 303 successUrl / 200 실패 화면 / 503 확인 중 |
| `POST /checkout/cancel` | 결제창 취소: 비종료 인증 세션 FAILED(USER_CANCELLED), checkout_session COMPLETED | 303 failUrl?…&code=USER_CANCELLED |
| `GET /checkout/assets/**` | 정적 CSS(JS 없음) | 200 |

- POST는 CSRF 필수(§12.3). 쿼리·폼 필드는 닫힌 집합(모르는 이름 → 400 화면).
- `GET`인 복귀 endpoint가 상태를 바꾸는 이유: Simulator가 top-level GET redirect로 돌아오고 SameSite=Lax 쿠키는 이 경우에만 전송된다(§12.2). 위조 GET의 영향은
  §7.4·§12.3에서 막는다(URL state 불일치는 상태 불변).
- 토큰 교환이 GET인 이유와 한계: D-AUTHN-001이 `/checkout?token=…` 진입을 정했다. 링크 미리보기·prefetch가 토큰을 소비할 수 있는 위험은 가맹점 서버의 즉시
  redirect·짧은 TTL·`Cache-Control: no-store`로 줄이고 남는 위험은 §20에 둔다(AN-06).

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
| 토큰 없음·형식 오류·없음·사용됨·대체됨·만료 | 400 | "결제창을 열 수 없습니다. 가맹점에서 다시 시도해 주세요." (사유 구분 없음 — 토큰 열거 방지) | 없음(사용된 토큰 재사용은 보안 이벤트 로그·지표) |
| 쿠키 없음·세션 없음·REVOKED·EXPIRED | 401 | "결제창 세션이 만료되었습니다." | 없음 |
| CSRF 불일치 | 403 | "요청을 처리할 수 없습니다." | 없음 |
| Payment가 REQUESTED 아님 | 409 | "결제를 진행할 수 없습니다." + failUrl(code=PAYMENT_NOT_AVAILABLE) 링크 | 세션 COMPLETED |
| 카드사·Plan 불허 | 422 | 같은 화면 + 고정 안내 | 없음 |
| 인증기관 등록 실패·timeout | 503 | "인증기관 연결에 실패했습니다. 다시 시도해 주세요." | 세션 CREATED 유지(다음 시도가 대체) |
| 복귀: URL state 불일치·비종료 세션 없음·결과 참조 NOT_FOUND | 400 | "인증 결과를 확인할 수 없습니다." | 없음(보안 이벤트) |
| 복귀: 조회 Unverifiable | 503 + Retry-After | "인증 결과를 확인 중입니다." + 같은 URL 재시도 링크 | 세션 IN_PROGRESS 유지 + 이벤트 |
| 복귀: FAILED(인증 실패·사용자 취소·불일치·기관 만료) | 200 | "인증이 완료되지 않았습니다." + 다시 시도 / failUrl(code=AUTHENTICATION_FAILED 또는 USER_CANCELLED) | 세션 FAILED |
| 복귀: 세션 EXPIRED·늦은 결과 | 410 | "인증 시간이 지났습니다." + 다시 시도 | 없음(IGNORED 이벤트) |
| 결함 | 500 | "일시적인 오류입니다." | rollback |

- failUrl query: `paymentId`, `code` ∈ {`USER_CANCELLED`, `AUTHENTICATION_FAILED`, `CHECKOUT_EXPIRED`, `PAYMENT_NOT_AVAILABLE`}(AN-08). successUrl query: `paymentId`,
  `authenticationRef`(**확정** AC1 §4.1). 기존 query는 보존하고 `&`로 덧붙이며 값은 percent-encoding한다(§8.3).
- 인증 실패 뒤 같은 결제창에서 다시 시도할 수 있다(**확정** AC1 §3 "인증 실패 후 같은 결제창에서 재인증", §5.3 FAILED → 새 세션 가능). 그래서 실패는 자동
  failUrl redirect가 아니라 선택 화면이다(AN-08).

---

## 7. AuthenticationSession 규칙 적용

### 7.1 domain 보강(순수 Kotlin, 기존 시험 기대값 완화 없음)

| 변경 | 내용 | 근거 |
|---|---|---|
| 상관 값 | `AuthenticationCorrelation(stateDigest)`를 생성 시 고정, `start(providerTransactionRef)`가 기관 거래 참조를 고정(CREATED → IN_PROGRESS) | D-AUTHN-003 state, AC1 §4.3 "요청 ID·원 세션" 대조. 기존 `start()` 서명 변경 → `AuthenticationSessionSpec` 호출부 갱신(기대값 동일) |
| 만료 원인 저장 | `expiryCause` 속성, 원인에 `SUPERSEDED` 추가 | Auditability(§3-4), §7.2 |
| 실패 사유 | `AuthenticationFailure.PROVIDER_EXPIRED` 추가(기관이 결과 유효기간 경과를 확정) | §7.3 R5. 세션 TTL 만료(EXPIRED)와 구분 |
| 근거 유효기간 | `VerifiedAuthentication.providerValidUntil` 추가, `bindingRejection`은 `now < min(expiresAt, providerValidUntil)`일 때만 사용 가능(아니면 EXPIRED) | **도출**: D-AUTHN-003 "유효기간 대조" + 범위 §3-4 — 기관이 정한 근거 유효기간을 넘긴 근거로 승인 시작 불가. confirm 판정은 AUTH-001이 이 함수를 그대로 쓴다 |
| 대체 domain 서비스 | `AuthenticationSessionStart.start(payment, sessionsOfPayment, id, binding, correlation, expiresAt, now)`: 모든 검사 → 같은 Payment의 미폐기·미귀속 세션을 EXPIRED(SUPERSEDED, 만료 지났으면 TTL_ELAPSED) → `create` | 관례 §12 "다중 Aggregate 연산은 검사 먼저". ArchUnit: main의 `AuthenticationSession.create` 호출은 이 서비스만 |
| 결과 판정 domain 서비스 | `AuthenticationResultCheck.decide(session, presentedStateDigest, result, now)` → §7.3 판정 | 판정 규칙을 Spring 없이 표로 시험 |
| Plan 사전 판정 | `PaymentPlanRule.precheck(plan, money, policy)` — 개월·최소금액만(cardType 없음) | AC 6 "허용 판정은 서버" |
| 반환 URL | `MerchantReturnUrl`(§8) | D-AUTHN-002 |
| digest | `SecretDigest.of(prefix, raw)` = prefix + SHA-256 hex(JDK `MessageDigest`, domain 허용 목록 안 — `OrderIntent`와 같다) | 비교는 `MessageDigest.isEqual` |

### 7.2 같은 Payment의 세션 수

- **확정**: 비종료(CREATED·IN_PROGRESS) 세션은 Payment당 하나, 귀속 세션은 최대 하나(AC1 §3·§5.3).
- **구현 결정 AN-07**: 새 인증 시작은 같은 Payment의 미폐기·미귀속 세션(비종료와 **VERIFIED 미귀속** 모두)을 먼저 만료(SUPERSEDED)한다 → 사용 가능한 인증 근거는
  Payment당 최대 하나. 이유: 재인증(D-AUTH-005 "재인증 후 새 키 confirm")을 허용하면서 오래된 근거가 남아 confirm에 쓰이는 노출을 없앤다(fail-closed, 금융 효과
  없음 — 오래된 근거로 confirm하면 `AUTHENTICATION_NOT_VERIFIED`, 새 키로 다시). 대체가 confirm과 겹치면 payment 행 잠금으로 직렬화된다(§14 C8). Coordinator가
  이것을 인증 근거 유효성 정책으로 보면 질문으로 바꾼다(그 경우 대안: 비종료 세션만 대체).
- 대체는 토큰 교환이 아니라 **인증 시작**에서만 한다: 새 결제창 세션을 연 것만으로 이미 VERIFIED된 근거를 지우지 않는다.

### 7.3 조회 결과 판정표(`AuthenticationResultCheck`, 위에서 아래로 첫 일치)

전제: 쿠키 세션의 비종료 인증 세션 S를 찾았다(없으면 400 화면, 상태 불변). `presentedStateDigest` = URL `state`의 digest.

| # | 조건 | 결과 | S 상태 | 이벤트 |
|---|---|---|---|---|
| R0 | S가 VERIFIED이고 결과 참조 digest = S의 것, 결과 내용 = 저장 증거 | DUPLICATE | 불변 | DUPLICATE_RESULT |
| R0′ | S가 VERIFIED이고 그 밖 | 거절(증거 덮어쓰기 금지) | 불변 | REJECTED_RESULT(EVIDENCE_CONFLICT, 보안) |
| R1 | S가 FAILED·EXPIRED | IGNORED_TERMINAL(늦은 결과는 기록만) | 불변 | IGNORED_LATE_RESULT |
| R2 | URL state digest ≠ S.stateDigest | 거절 | 불변 | REJECTED_RESULT(STATE_URL_MISMATCH, 보안) |
| R3 | 조회 Unverifiable(timeout·연결·5xx·응답 형식/필드 위반·HTTP 404) | 확인 불가 | **IN_PROGRESS 유지** | LOOKUP_UNVERIFIABLE(분류) |
| R4 | `now ≥ S.expiresAt` | 세션 TTL 만료 | EXPIRED(TTL_ELAPSED) | EXPIRED |
| R5 | 결과 outcome EXPIRED 또는 `now ≥ validUntil` | 기관 근거 만료 | FAILED(PROVIDER_EXPIRED) | FAILED |
| R6 | 결과의 기관 거래 참조 ≠ S.providerTransactionRef, 또는 결과 state digest ≠ S.stateDigest | 다른 거래의 결과 | FAILED(STATE_MISMATCH) | FAILED |
| R7 | 결과 바인딩(paymentId·금액·통화·MID·환경·Plan·카드사) ≠ S 바인딩, 또는 issuerCode ≠ 선택 카드사 | 바인딩 불일치 | FAILED(BINDING_MISMATCH) | FAILED |
| R8 | outcome FAILED | 인증 실패 | FAILED(AUTHENTICATION_FAILED) | FAILED |
| R9 | outcome CANCELLED | 사용자 취소 | FAILED(USER_CANCELLED) | FAILED |
| R10 | outcome AUTHENTICATED이나 cardType ∉ {CREDIT, CHECK} | 미지원 카드 | FAILED(AUTHENTICATION_FAILED, 상세 UNSUPPORTED_CARD_TYPE) | FAILED |
| R11 | outcome AUTHENTICATED, 위 모두 통과 | 검증 성공 | VERIFIED(증거·authenticationRef·결과 참조 digest) | VERIFIED |

- **R3 확정(AC 4 "명세에서 확정")**: 조회 실패·timeout은 세션을 **IN_PROGRESS로 유지**하고 같은 복귀 URL로 재조회할 수 있다. TTL이 지나면 R4로 EXPIRED. 근거:
  AC1 §9.1 `CardAuthenticationPort` 결과 `Unverifiable(일시 — 세션 상태 불변)`, D-AUTHN-003 "조회 실패면 VERIFIED 아님(fail-closed)", D-AUTH-009(TTL은 설정).
  FAILED로 바꾸지 않는 이유: timeout은 기관 결과가 없다는 증거가 아니다(CLAUDE.md "timeout만으로 효과 없음을 단정하지 않는다"의 같은 원칙) — 인증에는 금융
  효과가 없어 재조회 허용이 안전하다. 새 정책이 아니라 기존 규칙의 적용이다.
- HTTP 404(결과 참조 없음)도 R3(상태 불변)이다: 브라우저가 가져온 참조가 틀린 것이며 세션을 실패시키면 위조 GET으로 고객 세션을 망가뜨릴 수 있다(R2와 같은 이유).
- R0′·R2는 **상태를 바꾸지 않는다**(AC1 §5.3 "다른 증거 callback → 거절(보안 사건 로그), 덮어쓰지 않음"). R2를 FAILED로 하지 않는 이유: 복귀 endpoint는 GET이라
  공격자 페이지가 고객 브라우저로 틀린 state를 보내 세션을 FAILED로 만드는 DoS가 가능하다 — 기관이 확인한 불일치(R6)만 FAILED(AN-09).
- 결과 참조는 1회용: VERIFIED·FAILED 기록 시 결과 참조 digest를 UNIQUE 컬럼에 저장 → 다른 세션에서 같은 참조는 DB가 거부(1062 → 거절, 상태 불변).
- Payment가 REQUESTED가 아니면(TX-C 잠금 뒤 확인) 세션을 EXPIRED(PAYMENT_CLOSED)로 하고 VERIFIED로 만들지 않는다(종료·승인 시작된 Payment에 새 근거 금지 —
  도출 AC1 §5.3·`RequestedPaymentExpiry`).
- `authenticationRef` = `aref_` + base64url 128bit(서버 생성, 세션당 하나, UNIQUE). 자격 증명이 아니라 식별자다 — confirm은 MID 인증과 바인딩 대조를 요구한다
  (paymentKey와 같은 등급, CP계약 §5). 로그에는 앞 9자만.

### 7.4 상태 전이 요약(이 작업이 쓰는 경로)

| 전이 | 출처 | TX |
|---|---|---|
| — → CREATED | 인증 시작(`AuthenticationSessionStart`) | TX-A |
| CREATED → IN_PROGRESS | 등록 성공 뒤 | TX-B |
| CREATED·IN_PROGRESS·VERIFIED(미귀속) → EXPIRED(SUPERSEDED/TTL_ELAPSED) | 새 인증 시작 | TX-A |
| IN_PROGRESS → VERIFIED / FAILED / EXPIRED | §7.3 | TX-C |
| CREATED·IN_PROGRESS → FAILED(USER_CANCELLED) | 결제창 취소 | 취소 TX |
| 미폐기·미귀속 → EXPIRED(PAYMENT_CLOSED) | Payment 결제창 만료(`RequestedPaymentExpiry`) | **Q-AUTHN-05** |
| VERIFIED → 귀속 | 승인 시작 TX1 | AUTH-001 |

---

## 8. successUrl/failUrl 검증(D-AUTHN-002)

### 8.1 허용 문법(엄격 부분집합 — 정규화하지 않고 거절)

`MerchantReturnUrl`은 아래 문법에 **정확히** 맞을 때만 만들어진다. 다른 표기를 고쳐 주지 않는다(D-MD-009 "자동 정규화 없음"과 같은 정신, AN-10).

```text
url    = "https://" host [ path ] [ "?" query ]           ; 길이 ≤ 2048, 전부 출력 가능 ASCII(0x21–0x7E)
host   = label *( "." label )                             ; 소문자 [a-z0-9-], label 1..63, 전체 ≤ 253, 끝 점 없음
path   = 1*( "/" segment )                                 ; segment = *( unreserved / pct / sub / ":" / "@" )
query  = *( unreserved / pct / sub / ":" / "@" / "/" / "?" )
pct    = "%" HEXDIG HEXDIG
```

| 입력 예 | 결과 | 이유 |
|---|---|---|
| `https://shop.example.com/pay/ok?x=1` | 허용(host = Site host일 때) | — |
| `https://shop.example.com` | 허용(path 없음) | — |
| `http://…`, `HTTPS://…`, `javascript:…`, `//shop.example.com/…`, `https:/shop…`, `https:shop…` | 400 | https 소문자 + `//` 필수 |
| `https://user@shop.example.com/`, `https://shop.example.com@evil.com/` | 400 | userinfo(`@` 는 authority에 없음) |
| `https://shop.example.com:443/`, `:8443`, `:` | 400 | 포트 표기 전부 거절(기본 포트 포함 — AC "포트 변형") |
| `https://SHOP.example.com/`, `https://shop.example.com./`, `https://xn--…`(소문자 ASCII는 허용, 비교만), `https://샵.example.com/` | 대문자·끝 점·비ASCII 400 | 정규화 없음 |
| `https://127.0.0.1/`, `https://[::1]/`, `https://2130706433/`, `https://0x7f.0x1/`, `https://a.0x7f/` | 400 | IP 리터럴(마지막 label 숫자·`0x` 16진, `[`) — D-MD-014와 같은 판정 |
| `https://shop.example.com%2eevil.com/`, `https://shop%2Eexample.com/` | 400 | host에 `%` 금지 |
| `https://shop.example.com\@evil.com/`, `https://shop.example.com\evil` | 400 | 역슬래시(WHATWG가 `/`로 바꾸는 문자) 금지 |
| 탭·CR·LF·공백·제어문자 포함(예: `https://shop.example.com\n.evil.com/`) | 400 | 브라우저가 탭·개행을 지워 host가 바뀌는 우회 차단 |
| `https://shop.example.com/#frag` | 400 | fragment 금지(덧붙일 query 위치 모호, AN-10) |
| `https://shop.example.com/a%zz` | 400 | 잘못된 percent-encoding |
| 2049자 이상 | 400 | 컬럼 VARCHAR(2048) |

### 8.2 host 판정

- **확정**: 허용 = 문법 통과 ∧ `host == Site WEB host`(문자열 완전 일치, D-AUTHN-002). `www.shop.example.com`·`shop.example.com.evil.com`·`evil-shop.example.com`은
  다른 host다(D-MD-009 "www. 등 서브도메인은 다른 채널").
- Site host는 Snapshot의 ACTIVE Site의 WEB 채널 식별값이다. **APP 채널·TERMINATED Site·Snapshot에 Site 없음 → host 없음 → 거절**. 종료 Site를 거절하는 이유(도출):
  활성 채널 유일성(D-MD-008)은 ACTIVE에만 걸리므로 종료된 Site의 host는 다른 Merchant가 다시 등록할 수 있다 — 종료 Site host를 허용 근거로 쓰면 다른 Merchant
  도메인으로 redirect할 수 있다.
- 교차 확인 시험(adapter 계층, `java.net.URI` 사용 가능): 허용된 모든 시험 URL에 대해 `URI(url)`의 scheme = `https`, host = Site host, port = -1, userInfo = null임을
  확인한다(엄격 부분집합이 RFC 3986·WHATWG 해석과 갈라지지 않음을 증명). 거절 표의 각 행은 domain 시험에 1행씩.

### 8.3 redirect 구성

- 저장된(검증된) URL에 `paymentId`·`authenticationRef`(또는 `code`)를 덧붙인다: query가 없으면 `?`, 있으면 `&`. 값은 RFC 3986 unreserved 밖을 percent-encoding.
  구성 결과를 다시 `MerchantReturnUrl`로 파싱해 host가 그대로인지 확인한 뒤 `Location`에 쓴다(방어적 재검증).
- redirect 대상은 토큰에 저장된 URL뿐이다. 요청 파라미터로 redirect 대상을 받지 않는다(open redirect 원천 차단).

---

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
| `checkout_token` | `checkout_token_id`(PK `ctk_`+ULID), `token_digest`(`cth1:`+hex, UNIQUE), `payment_id`(FK payment), `environment`·`merchant_account_id`·`merchant_order_id`·`merchant_site_id`·`amount_minor`·`currency`(바인딩), `success_url`·`fail_url`(VARCHAR 2048, `^https://` CHECK), `site_host`, `site_snapshot_version`, `status`(ISSUED/CONSUMED/SUPERSEDED/EXPIRED), `issued_at`·`expires_at`·`consumed_at`·`closed_at`, `issued_by_credential_id`, `policy_version` | `expires_at > issued_at`, CONSUMED ⇔ consumed_at, 생성 컬럼 `outstanding_payment_id = IF(status='ISSUED', payment_id, NULL)` **UNIQUE**(미교환 토큰 Payment당 1) |
| `checkout_session` | `checkout_session_id`(PK `cks_`+ULID), `session_digest`(`cks1:`+hex, UNIQUE), `checkout_token_id`(FK, **UNIQUE** — 토큰 1개 = 세션 1개), `payment_id`(FK), `success_url`·`fail_url`·`site_host`(토큰에서 복사), `status`(ACTIVE/COMPLETED/REVOKED/EXPIRED), `close_reason`(VERIFIED_REDIRECT·CANCELLED·SUPERSEDED·PAYMENT_CLOSED·PAYMENT_NOT_AVAILABLE), `created_at`·`expires_at`·`closed_at`, `policy_version` | 생성 컬럼 `active_payment_id = IF(status='ACTIVE', payment_id, NULL)` **UNIQUE**, 닫힘 ⇔ close_reason·closed_at |
| `authentication_session` | `authentication_session_id`(PK `aus_`+ULID), `payment_id`(FK), `checkout_session_id`(FK), 바인딩(`environment`·`merchant_account_id`·`merchant_order_id`·`amount_minor`·`currency`·`plan_kind`·`installment_months`·`card_company`), `status`(5종 CHECK), `state_digest`(UNIQUE), `provider_transaction_ref`, `result_ref_digest`(UNIQUE NULL), `failure_reason`, `expiry_cause`, 증거(`evidence_ref`·`card_type`·`issuer_code`·`acquirer_code`·`provider_code`·`verified_at`·`provider_valid_until`), `authentication_ref`(UNIQUE NULL), `bound_authorization_id`(UNIQUE NULL — AUTH-001이 씀), `profile_id`·`plan_policy_version`·`policy_version`, `created_at`·`expires_at`·`closed_at` | 생성 컬럼 `open_payment_id = IF(status IN ('CREATED','IN_PROGRESS'), payment_id, NULL)` **UNIQUE**, `bound_payment_id = IF(bound_authorization_id IS NOT NULL, payment_id, NULL)` **UNIQUE**; VERIFIED ⇔ 증거·authentication_ref·result_ref_digest NOT NULL; failure_reason ⇔ FAILED; expiry_cause ⇔ EXPIRED; bound ⇒ VERIFIED; IN_PROGRESS·VERIFIED ⇒ provider_transaction_ref NOT NULL; `plan_kind='INSTALLMENT'` ⇔ months 2..99; card_type ∈ {CREDIT, CHECK} |
| `authentication_session_event` | PK(`authentication_session_id`, `seq`), `kind`(CREATED·STARTED·VERIFIED·FAILED·EXPIRED·DUPLICATE_RESULT·IGNORED_LATE_RESULT·REJECTED_RESULT·LOOKUP_UNVERIFIABLE·REGISTRATION_FAILED), `detail_code`(고정 분류, NULL), `result_ref_digest` NULL, `occurred_at`, `correlation_id` | **append-only**, `seq ≥ 0`, FK → authentication_session |

- 원문 비밀(토큰·쿠키 비밀·state·결과 참조) 컬럼은 **없다**. digest만. 카드번호·CVC 컬럼 없음(`NoCardDataSchemaSpec`이 information_schema 컬럼 이름
  `card_number|pan|cvc|cvv|expiry_month` 부재를 확인).
- 생성 컬럼 UNIQUE는 MASTER 관례(`active_channel_key`, MS2 `open_route_key`)와 같다. domain 불변식(비종료 1·귀속 1·ACTIVE 1·미교환 1)을 DB가 다시 지킨다.
- Q-AUTHN-05 답이 A면 같은 V7에 `payment.checkout_expires_at`(NULL = V7 이전 행) 추가 — 답 전에는 넣지 않는다.

### 10.2 `db/runtime-privilege/payment/V8__checkout_authentication_runtime_privileges.sql`(관례 §9.2)

| 표 | runtime 권한 | 쓰기 경로(근거) | 없는 권한 |
|---|---|---|---|
| `checkout_token` | SELECT, INSERT, UPDATE | INSERT 발급, UPDATE 소비·대체·만료(잠금 읽기 FOR UPDATE 포함) | DELETE(삭제 Job 없음) |
| `checkout_session` | SELECT, INSERT, UPDATE | INSERT 교환, UPDATE 취소·완료·대체 | DELETE |
| `authentication_session` | SELECT, INSERT, UPDATE | INSERT 시작, UPDATE 전이(+ AUTH-001 귀속) | DELETE |
| `authentication_session_event` | SELECT, INSERT | INSERT만(`@Immutable`) | UPDATE, DELETE, 잠금 읽기(1142) |

`DatabaseAccountSeparationSpec`의 고정 SHOW GRANTS 기대값·표 목록 비교에 4개 표를 추가한다(새 표 추가 — 기대값 완화 아님, Reviewer 확인 대상).

### 10.3 잠금 순서(AC1 CA-05 확장 — 구현 결정 AN-12)

```text
멱등 기록 → entitlement_head(S) → order_payment_control → payment → payment_authorization → authentication_session → 한도 사용량
        → checkout_session → checkout_token
```

| Use Case | 부분열 |
|---|---|
| 토큰 발급(TX-I) | head(S) → control → payment → checkout_token |
| 토큰 교환(TX-X) | payment → checkout_session → checkout_token |
| 인증 시작(TX-A·TX-B) | payment → authentication_session (→ checkout_session 비잠금 읽기: payment 잠금 아래라 일관) |
| 결과 반영(TX-C)·취소 | payment → authentication_session → checkout_session |
| Payment 결제창 만료(Q-AUTHN-05) | control → payment → authentication_session → checkout_session → checkout_token |
| confirm TX1(AUTH-001, 변경 없음) | 멱등 → head(S) → control → payment → payment_authorization → authentication_session → 한도 |

- 모든 결제창 Use Case가 **payment 행을 먼저 X로 잠근다** → 같은 Payment의 결제창 작업·만료·confirm은 payment 행에서 직렬화되어 서로 교착할 수 없다. 토큰은
  digest로 비잠금 조회해 payment_id를 얻은 뒤 순서대로 잠그고 다시 판정한다.
- FK 자식 INSERT는 부모 행에 S 잠금을 건다(InnoDB) — `authentication_session` INSERT가 `checkout_session`에 S를 걸어 순서가 역전되지만, 이 INSERT는 항상 같은
  Payment의 payment X 잠금 아래에서만 일어나므로 경쟁 TX가 없다(시험: §14 C3·C6을 실 MySQL로).
- 교착(1213)은 기존 `LocalTransactions` 재시도(최대 3회), 잠금 timeout(1205)은 503(관례 §9).

---

## 11. 모듈·패키지·Port

### 11.1 모듈 결정(구현 결정 AN-13)

**기존 `com.paycore.payment.payment` 모듈에 둔다.** 새 Modulith 모듈 `checkout`은 기각한다.

| 판단 | 근거 |
|---|---|
| `AuthenticationSession`은 이미 `payment` domain Aggregate다 | AC1 CA-01 — `AuthorizationStart`(승인 시작)·`RequestedPaymentExpiry`와 같은 TX·같은 잠금 순서. 다른 모듈에 두면 Modulith가 domain 사용을 막아 상태 복사 DTO와 모듈 경계를 넘는 MANDATORY TX가 모든 단계에 생긴다 |
| 토큰 발급·교환·시작은 Payment 행 잠금과 같은 TX여야 한다 | §10.3 — payment 행이 직렬화 지점 |
| 테이블 소유 맵 규칙이 아직 없다 | 관례 §3 "두 번째 업무 모듈이 PAYMENT 표를 가질 때 소유 맵 규칙 도입" — 새 모듈이면 이 작업에서 그 규칙까지 만들어야 한다 |
| 표면 분리는 다른 수단으로 | 별도 checkout connector(포트)·별도 SecurityFilterChain·`adapter.web`의 결제창 Controller는 `application.provided`만 호출(기존 ArchUnit) → A01 `payment-window` 실행 단위로 추출할 때 provided Use Case를 JSON API로 노출하면 된다(§22-1) |

### 11.2 Port·타입(§14 명명 기준)

| 계층 | 이름 | 종류(§14 판정) |
|---|---|---|
| provided | `IssueCheckoutTokenUseCase`, `OpenCheckoutSessionUseCase`, `StartCardAuthenticationUseCase`, `CompleteCardAuthenticationUseCase`, `CancelCheckoutUseCase` | 명령 Use Case |
| provided | `CheckoutSessionQuery`(쿠키 digest → 세션 확인), `CheckoutViewQuery`(화면 DTO) | 판정·DTO 조회 |
| required | `AuthenticationSessionRepository` → `JpaAuthenticationSessionRepository` | **Aggregate 저장**: 이 모듈이 소유한 Aggregate를 자기 식별자로 load/save, `Versioned` |
| required | `AuthenticationSessionEventStore` → `JpaAuthenticationSessionEventStore` | Store ①: (세션, seq)로 쌓이는 결정 로그 |
| required | `CheckoutTokenStore` → `JpaCheckoutTokenStore` | Store ③: 행 식별자가 있지만 Aggregate가 아닌 보안 프로토콜 기록(멱등 기록과 같은 성격) |
| required | `CheckoutSessionStore` → `JpaCheckoutSessionStore` | Store ③: 브라우저 세션 프로토콜 기록(업무 사실 아님) |
| required | `CheckoutViewReader` → `JooqCheckoutViewReader` | jOOQ 조회 |
| required | `AuthenticationBacklogReader` → `JooqAuthenticationBacklogReader` | jOOQ 조회(지표용) |
| required | `SiteWebHostPort` → `adapter.integration.EntitlementSiteWebHostAdapter` | 다른 모듈 provided 연결 |
| required | `CardAuthenticationPort` → `adapter.integration.institution.SimulatorCardAuthenticationAdapter` | 외부 기관(AC1 §9.1 이름, CA-17 패키지) |
| required | `CheckoutSecretGenerator` → `SecureRandomCheckoutSecretGenerator`(application, JDK `SecureRandom`) | 기존 `PaymentIdentifierGenerator` 관례 |
| entitlement provided | `SiteWebHostQuery` | 판정 조회 |
| 서비스 | `CheckoutTokenIssuanceService`, `CheckoutSessionOpeningService`, `CardAuthenticationStartService`, `CardAuthenticationCompletionService`, `CheckoutCancellationService` | `<업무><책임>Service`. 외부 호출을 끼는 서비스는 TX를 열지 않는 조정자이고 TX는 `LocalTransactions`로 단계마다 연다 |
| web | `CheckoutTokenController`(공개 포트 JSON), `CheckoutPageController`(checkout 포트 HTML), `CheckoutSecurityConfiguration`, `CheckoutConnectorConfiguration` | `adapter.web` |

`CheckoutToken`·`CheckoutSession`을 Aggregate로 두지 않는 이유(AN-14): 업무 사실이 아니라 웹 보안 프로토콜 기록이다(관리자 세션을 Spring Session에 두는 D-ADMIN-004와
같은 성격). 규칙(1회·만료·바인딩)은 작은 순수 값(`CheckoutBinding`, `SecretDigest`)과 서비스에 두고 domain 업무 모델을 웹 개념으로 넓히지 않는다. 업무 사실은
`AuthenticationSession`이다.

---

## 12. 웹 보안

### 12.1 connector·보안 체인

- checkout 전용 connector(`paycore.payment.checkout.port`, 운영 기본값 없음 — 없으면 기동 실패, 공개·내부 포트와 같으면 기동 거부). `ApiEntryFilter`를 3-connector로
  확장: 공개 포트 = `/v1/**`·`/actuator/health`, 내부 포트 = `/internal/**`, checkout 포트 = `/checkout/**`만. 그 밖은 404(CP-API §8 이유 — 단일 실패점 제거).
  운영 배포에서는 다른 origin(예: checkout 전용 host)으로 노출한다(배포 작업). AN-15.
- 별도 `SecurityFilterChain`(`securityMatcher("/checkout/**")`, 순서가 API 체인보다 앞): Bearer 필터 없음, HttpSession 없음(STATELESS), CSRF **켬**(§12.3),
  `permitAll` — 접근 통제는 결제창 세션 쿠키로 application이 한다. API 체인은 그대로(CSRF 끔·Bearer) — 시험: checkout 체인이 `/v1/**`에 걸리지 않음, 쿠키로
  `/v1/**` 인증 불가.

### 12.2 쿠키

| 속성 | 값 | 이유 |
|---|---|---|
| 이름 | `__Host-pc_checkout` | `__Host-` 접두 → Secure·Path=/·Domain 없음을 브라우저가 강제(하위 도메인 주입 차단) |
| 값 | 256bit 난수(base64url), DB에는 `cks1:` digest만 | D-AUTHN-001 "hash만" 정신을 쿠키에도 적용 |
| HttpOnly·Secure | 켬 | **확정 D-AUTHN-001** |
| SameSite | **Lax** | 기관 → PG 복귀가 cross-site top-level GET이다. Strict면 복귀에 쿠키가 없다. Lax는 cross-site POST·iframe·fetch에는 보내지 않는다(AN-16) |
| 수명 | Max-Age = 결제창 세션 TTL(Demo) | D-AUTH-009 |
| 세션 ID 재발급 | 교환 때마다 **서버가 새 비밀 생성**, 브라우저가 보낸 기존 쿠키 값은 새 세션 ID로 쓰지 않는다(고정 공격 불가). 같은 Payment의 이전 세션은 REVOKED | **확정 D-AUTHN-001** |

### 12.3 CSRF

- Spring Security `CsrfFilter` + 사용자 정의 `CsrfTokenRepository`: CSRF 토큰 = `base64url(SHA-256("paycore.checkout.csrf.v1" ‖ 0x00 ‖ 쿠키 비밀))` — 서버 세션 비밀에서
  파생한 세션 결합 synchronizer 토큰. 저장이 필요 없고(원문 비밀을 DB에 두지 않음), 쿠키 없이는 계산할 수 없으며, 토큰이 노출돼도 쿠키 비밀을 역산할 수 없다.
  Thymeleaf `th:action`이 숨김 필드를 넣고 BREACH 대응 마스킹(`XorCsrfTokenRequestAttributeHandler`)은 기본값을 쓴다. AN-17(대안: DB에 CSRF 원문 저장).
- 모든 POST(`/checkout/authentication`, `/checkout/cancel`)에 필수. 불일치 → 403 화면, 상태 불변.
- 복귀 GET은 CSRF 토큰을 가질 수 없다(기관 redirect) → state(256bit, 세션별) + 쿠키 세션 결합 + URL state 불일치 시 상태 불변(§7.3 R2)으로 방어.

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
- 등록의 `returnUrl`은 client별 설정 prefix(예: PG checkout origin + `/checkout/authentication/return`)와 정확히 일치해야 한다 → Simulator가 open redirect가 되지 않는다.

### 13.4 API 계약 v1(golden — §17.4)

| endpoint | 인증 | 요청 | 응답 |
|---|---|---|---|
| `POST /sim/v1/authentications` | Bearer(client 키) | 엄격 JSON: `paymentId`, `merchantRef`, `environment`, `amountMinor`(정수), `currency`, `plan`{`kind`, `months`?}, `cardCompany`, `state`, `returnUrl` | 201 `{authenticationTransactionId, customerUrl, expiresAt}`. **(client, state) 멱등**: 같은 state·같은 내용 → 같은 거래(200), 다른 내용 → 409 |
| `GET /sim/authenticate/{id}` / `POST …` | 없음(고객 화면, 거래 ID는 128bit 이상 난수) | 가상 카드 선택·인증/취소 | 303 `returnUrl?resultRef=…&state=…` |
| `GET /sim/v1/authentication-results/{resultRef}` | Bearer | — | 200 `{resultRef, authenticationTransactionId, outcome(AUTHENTICATED/FAILED/CANCELLED/EXPIRED), state, binding{…echo}, cardType?, issuerCode?, acquirerCode?, providerCode?, evidenceRef?, authenticatedAt?, validUntil}` / 404(없음·다른 client — 존재 비노출) |

- 조회는 **읽기 전용·멱등**(같은 참조 → 같은 본문, outcome만 시각 함수 — validUntil 경과 뒤 EXPIRED). 조회 기록은 append-only `lookup_log`(시험 관찰용).
- payment-api ↔ Simulator 인증(AN-20): client별 Bearer 키(시험·로컬 Demo 키는 환경변수/시험 자원, 저장소 운영 키 없음), Simulator는 SHA-256 digest만 저장. 본문 서명은
  하지 않는다(TEST 전용 대역, D-AUTHN-003 "서명 키 불필요").
- PG adapter는 응답을 닫힌 필드 집합으로 엄격 파싱한다(모르는 필드·null·타입 오류 → Unverifiable — 검증 실패 응답을 성공으로 처리하지 않음, AC1 §6-4).

### 13.5 payment-api 연결(HTTP Adapter)

- `SimulatorCardAuthenticationAdapter`(`payment.adapter.integration.institution`): Spring `RestClient` + JDK `HttpClient` 요청 factory(새 의존성 없음), connect/read
  timeout은 설정(Demo). Port 결과: `Registered(ref, customerUrl)` / `RegistrationFailed(분류)` / `Found(ProviderAuthenticationResult)` / `Unverifiable(TIMEOUT·
  CONNECTION·SERVER_ERROR·NOT_FOUND·MALFORMED)`. 업무 예외를 던지지 않는다(AC1 §9.1).
- `customerUrl`은 설정된 Simulator 고객 origin으로 시작할 때만 redirect(응답 조작으로 임의 redirect 불가).
- Adapter는 domain 상태 전이를 호출하지 않는다(기존 ArchUnit `institutionAdapterDoesNotMutateDomain`).

### 13.6 Simulator/REAL 격리(도출 — §22-2)

```yaml
paycore.authentication:
  backends:            # Payment environment → 인증 backend
    TEST: { type: SIMULATOR, profile: sim-auth-demo }
    # LIVE 없음 → LIVE Payment 결제창은 422 CHECKOUT_NOT_AVAILABLE
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
| C1 | 같은 토큰 동시 교환 8 | payment X → 토큰 X 재판정, `checkout_session.checkout_token_id` UNIQUE | 세션 1, 쿠키 1, 나머지 400 화면, 토큰 CONSUMED 1 |
| C2 | 같은 Payment 서로 다른 토큰 동시 교환 | payment X, `active_payment_id` UNIQUE | ACTIVE 1(나중 것), 이전 REVOKED |
| C3 | 두 탭(같은 쿠키) 동시 인증 시작 | payment X, `open_payment_id` UNIQUE | 비종료 세션 1, 먼저 만든 것 EXPIRED(SUPERSEDED), 그 탭의 복귀 → 410 화면(IGNORED) |
| C4 | 같은 결과 참조 동시 복귀 2 | 조회 2회(멱등) → payment X → 세션 X | VERIFIED 1, 다른 요청 DUPLICATE, authenticationRef 1개 |
| C5 | 서로 다른 결과 참조 동시 복귀(혼합) | 같은 | 하나만 반영, 다른 쪽 R0′/R6 거절, VERIFIED 덮어쓰기 없음 |
| C6 | 복귀 TX-C vs 새 인증 시작 | payment X | 먼저 커밋된 쪽 기준: 시작이 이기면 복귀 IGNORED, 복귀가 이기면 VERIFIED 뒤 시작이 SUPERSEDED |
| C7 | 복귀 vs Payment 결제창 만료 | control X → payment X(만료), payment X(복귀) | 만료가 이기면 세션 EXPIRED(PAYMENT_CLOSED)·복귀 IGNORED, 복귀가 이기면 VERIFIED 뒤 만료가 EXPIRED — **Q-AUTHN-05 답 뒤**(답 B면 AUTH-001로 이관) |
| C8 | 인증 시작 vs confirm(AUTH-001) | payment X | AUTH-001 시험 기준으로 기록: confirm이 이기면 Payment AUTHORIZING → 시작 거절(409 화면), 시작이 이기면 기존 VERIFIED SUPERSEDED → confirm `AUTHENTICATION_NOT_VERIFIED` |
| C9 | 토큰 발급 동시 2(가맹점 재시도) | control X → payment X, `outstanding_payment_id` UNIQUE | ISSUED 1, 다른 것 SUPERSEDED |
| C10 | 조회가 오래 걸리는 동안 | — | 조회 중 그 요청 연결에 열린 TX·잠금 없음(`performance_schema.data_locks`·`@@in_transaction`), 다른 결제창 요청이 대기하지 않음 |

장애 주입(`FaultInjectionConfig` 방식): TX-I·TX-X·TX-A·TX-C의 각 Port flush 직후 예외 → 해당 TX의 모든 행 rollback(토큰·세션·이벤트 고아 없음), commit 단계 예외 →
같은 요청 재시도로 수렴.

---

## 15. 8대 원칙 증거 계획

| 원칙 | 적용 | 증거(시험·근거) |
|---|---|---|
| Financial Consistency | 적용(경계) | 인증은 금융 사실이 아니며 Payment는 REQUESTED 유지(시험: 전 흐름 뒤 payment 행·control·Outbox 불변). 바인딩 금액·통화 = Payment 생성 값(토큰·세션·기관 echo 3중 대조, R7). 결제 생성 API·응답 불변(기존 시험) |
| Transaction Safety | 적용 | 외부 호출 TX 밖(C10), 단계 TX별 flush 뒤 실패 주입 rollback, TX-C 실패 뒤 재조회 수렴, commit 불명확 503 → 재시도 안전 |
| Idempotency | 적용 | 토큰 1회 교환(C1), 효과 멱등 발급(C9·§5.4), 중복 복귀 DUPLICATE(C4), 결과 참조 1회(UNIQUE), Simulator 등록 (client, state) 멱등·조회 멱등, Snapshot v3 같은 eventId 재전송 DUPLICATE(기존 경로) |
| Concurrency Control | 적용 | §14 C1~C6·C9·C10 실 MySQL, 생성 컬럼 UNIQUE 4개, payment 행 직렬화, 잠금 순서 §10.3 |
| Recoverability | 적용 | 조회 timeout·5xx → IN_PROGRESS 유지 → 재조회 VERIFIED(`VC-LOOKUP-*-ONCE`), 등록 실패 → 재시도 대체, PG 재시작 뒤 복귀 처리(세션 DB 영속), Simulator 재시작 뒤 조회 동일(`SimulatorRestartDurabilitySpec`), v2 head → 승격 뒤 해소 |
| Reconciliation | **N/A(이유)** | 인증은 기관 금융 효과가 아니며 대사 대상 금융 사실이 아니다(AC1 §11 "인증 세션 전이는 내부 상태"). 기관 승인 대사는 AUTH-001·RECON-001. 대신 비권위 jOOQ 점검(TTL 지난 비종료 세션·미교환 토큰 수) gauge |
| Auditability | 적용 | `authentication_session_event` append-only(1142 시험), 토큰 발급 Credential·Site host·Snapshot 버전·정책 버전 저장, 만료 원인·실패 사유 저장, 보안 이벤트 로그 |
| Observability | 적용 | counter `paycore.checkout.tokens{result}`, `paycore.checkout.exchanges{result}`, `paycore.authentication.sessions{transition}`, `paycore.authentication.lookups{outcome}`, timer 조회 지연, gauge 미해결 세션·미교환 토큰(label에 ID 금지), correlationId MDC, 로그 비노출 시험(§12.6) |

---

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

1. domain: `MerchantReturnUrlSpec`(§8.1 표 전 행 + redirect 구성), `AuthenticationSessionSpec` 추가 행(상관 값·SUPERSEDED·PROVIDER_EXPIRED·providerValidUntil),
   `AuthenticationSessionStartSpec`(대체 + 실패 시 전 세션 불변), `AuthenticationResultCheckSpec`(§7.3 R0~R11 각 1행 이상, 기대값은 표에서 독립 기술),
   `PaymentPlanPrecheckSpec`.
2. MASTER: `EntitlementSnapshotAssemblerSpec` v3(WEB host·APP·종료 Site), `SchemaPromotionV3Spec`(v2 issue → 갱신 시 revision + 1 v3), golden v3.
3. PAYMENT entitlement: `EntitlementContentV3Spec`(v3 수용, v2+channel·v3−channel 거절, host 문법 위반 거절), `SiteWebHostQuerySpec`(실 MySQL, §9.4 결과 전부),
   `SchemaRolloutOrderV3Spec`(역순 막힘·정순 APPLIED).
4. persistence: `CheckoutSchemaSpec`(information_schema·CHECK 위반 INSERT·생성 컬럼 UNIQUE·collation), `DatabaseAccountSeparationSpec` 갱신, Repository/Store 왕복·
   append-only 1142·잠금 읽기.
5. Simulator: 단위·MySQL·HTTP 계약·재시작 내구성·ArchitectureSpec(§13).
6. Adapter: `SimulatorCardAuthenticationAdapterSpec`(JDK `HttpServer` stub — 시험 전용, golden 응답·지연·5xx·형식 위반 → Port 결과), `AuthenticationBackendIsolationSpec`.
7. application(실 MySQL): `CheckoutTokenIssuanceSpec`, `CheckoutExchangeSpec`, `CardAuthenticationStartSpec`, `CardAuthenticationCompletionSpec`(Simulator stub 시나리오
   전부), `CheckoutRollbackSpec`(장애 주입), 경합 `CheckoutConcurrencySpec`(C1~C6·C9·C10).
8. web(RANDOM_PORT 3 connector): `CheckoutTokenApiSpec`, `CheckoutConnectorIsolationSpec`, `CheckoutCookieSecuritySpec`, `CheckoutCsrfSpec`, `CheckoutPageSecuritySpec`,
   `CheckoutTemplateScanSpec`, `CheckoutErrorPageLeakSpec`, `CheckoutLogRedactionSpec`.
9. (Q-AUTHN-05 답 뒤) 만료 Use Case·C7.

RED 기록: 새 시험이 대상 없음으로 컴파일 실패(RED-C) + 실행 RED(대상 stub이 잘못된 값) + 주요 규칙 변이 RED(예: URL 포트 허용, R2를 FAILED로, R3를 FAILED로, 대체 생략,
토큰 원문 저장, CSRF 끔, `th:utext` 허용 → 대상 Spec 실패)를 2단계 기록에 남긴다(AUTH-CONTRACT §19.4 방식). MS2의 "test-first 아님" 공정 편차를 반복하지 않는다.

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
| `authenticationTransitionsOnlyInApplicationOrDomain` | `AuthenticationSession`의 `start`·`verify`·`fail`·`expire` 호출은 `..application..`·`..domain..`만(`kotlinSourceName` 비교) | `archfixture.session.WebAdapterVerifiesSession` |
| `thymeleafOnlyInWebAdapter` | `org.thymeleaf..` 의존은 `adapter.web`만 | `archfixture.web.DomainUsesThymeleaf` |
| 기존 규칙 적용 | `institutionAdapterDoesNotMutateDomain`, `webAdapterUsesOnlyApplication`, `domainIsPure`(`MerchantReturnUrl`에 `java.net` 없음), 캐시 금지 규칙 | 기존 |
| Simulator | `PayCoreArchitectureRules` 공통 규칙을 base `com.paycore.simulator`로 적용(계층·domain 순수성·JPA 위치·Controller 위치·base root) | 공통 meta-test |

### 17.4 계약 golden(두 앱 독립성)

- `harness/tests/test_authentication_contract_golden.py`(Python 표준 라이브러리)가 등록 요청·조회 응답 v1 golden을 만들어 `apps/payment-api`·`apps/institution-simulator`
  시험 자원과 바이트 비교. Simulator 시험은 자기 응답이 golden과 같음을, payment-api adapter 시험은 golden을 파싱해 Port 결과가 됨을 확인 → 프로세스 경계를 넘는
  계약을 한 파일로 고정(앱 간 Gradle 의존 없음).
- 같은 파일에 `test_entitlement_golden.py` v3 대상 추가.
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
| S0 | 이 명세 확정·질문 처리 | — | — |
| S1 | domain(§7.1·§8) TDD | S0 | — |
| S2 | MASTER v3 조립·golden v3·승격 시험 | S0 | — |
| S3 | PAYMENT v3 해석·`SiteWebHostQuery`·adapter | S2(golden) | — |
| S4 | V7/V8·JPA Repository/Store·Mapper·스키마/권한 시험 | S1 | Q-AUTHN-05가 A면 `payment.checkout_expires_at` 포함 |
| S5 | Simulator 실행 단위 골격·DB·하네스 등록(RED: registry 시험 실패 → 등록) | S0 | — |
| S6 | Simulator 인증 domain·API·화면·시나리오·golden·시험 | S5 | — |
| S7 | `CardAuthenticationPort`·HTTP adapter·격리 설정·stub 시험 | S1, S6(golden) | — |
| S8 | 토큰 발급 Use Case·공개 API·오류 | S3, S4 | — |
| S9 | checkout connector·보안 체인·교환·화면(jOOQ)·템플릿·헤더/쿠키/CSRF 시험 | S4, S8 | — |
| S10 | 인증 시작·결과 반영·취소·경합·rollback 시험 | S7, S9 | — |
| S11 | Payment 결제창 만료 Use Case·주기 작업·C7 | S10 | **Q-AUTHN-05** |
| S12 | 지표·로그 비노출 시험, 문서(관례 새 절·DOMAIN-DEFINITION·HARNESS), 하한 갱신, 전체 check → VERIFY-GATE·DEPS×3·CVE 보고 | 전부 | — |

권장 검토 지점: S4 뒤(스키마·권한), S7 뒤(격리), S10 뒤(보안·경합) — Reviewer 중간 검토로 재작업 범위를 줄인다(단일 VERIFY-GATE는 유지).

---

## 20. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 작업량이 MS2의 약 2.0~2.5배(새 실행 단위 + PAYMENT 표 4개 + Use Case 5개 + 보안 웹 표면 + Snapshot v3) — 단일 gate 실패 시 재작업 큼 | §19 중간 검토, 단계별 모듈 시험 실행 |
| R2 | checkout 보안 체인 `securityMatcher` 순서 실수로 `/v1/**` 인증 우회 | `CheckoutConnectorIsolationSpec`: checkout 포트·쿠키로 `/v1/**` 불가, API 포트 Bearer 그대로 |
| R3 | CSP `form-action` redirect 검사는 브라우저별 차이, 실제 브라우저 시험 없음(헤더 값만 검증) | 헤더 정확 값 시험, 남는 위험 기록. 브라우저 시험은 후속 |
| R4 | GET 토큰 교환이 링크 미리보기·prefetch로 소비 | 짧은 TTL·즉시 redirect·no-store, 소비 시 가맹점 재발급(효과 멱등) |
| R5 | v3 배포 순서 역전 시 head 막힘(생성·승인 판정까지 503) | MS2 §5.4 절차 재사용, `SchemaRolloutOrderV3Spec` |
| R6 | 프로세스 간 자동 E2E가 gate에 없음 | 공유 golden 계약 + adapter/Simulator 각자 계약 시험 + compose smoke 기록 |
| R7 | 새 실행 단위·MySQL 컨테이너 추가로 gate 시간·메모리 증가(TECH-BASELINE §7.2) | Simulator 시험 컨테이너 1개 공유, `maxParallelForks=1` 유지 |
| R8 | V7/V8 사용으로 AUTH-001 migration 번호 이동 | §22-4 기록 |
| R9 | 관례 문서(§14 등)에 Coordinator 미커밋 변경 — 2단계 문서 갱신 충돌 | 2단계 관례 새 절(§15 후보) 추가는 Coordinator 커밋 뒤 |
| R10 | SameSite=Lax + GET 복귀의 위조 요청 | R2·R3(상태 불변), state 256bit, 쿠키 결합 |
| R11 | 앱카드·ISP 앱 전환 복귀(다른 브라우저 컨텍스트)는 쿠키가 없어 처리 불가 | 실제 기관 연동(LIVE Gate)에서 재설계 — 지금은 fail-closed(§22-7) |

---

## 21. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| AN-01 | 토큰 API 경로의 paymentId 형식 오류도 404 | 존재·형식 oracle 제거(CP-API §7.5) |
| AN-02 | 토큰 API는 Idempotency-Key를 받지 않고 있으면 400 | 원문 재생 불가(D-AUTHN-001), 효과 멱등(§5.4), 가맹점 오해 방지 |
| AN-03 | 본문 닫힌 필드 2개, CP-API 엄격 파서 재사용 | 기존 관례 |
| AN-04 | 미교환 토큰 Payment당 1(새 발급이 이전 것을 SUPERSEDED) | 유효 비밀 최소화 |
| AN-05 | 새 오류 code 6개와 고정 문구 | 공개 API 표현 선택 |
| AN-06 | 토큰 교환은 GET + 303(PRG) | D-AUTHN-001 진입 방식, URL에서 토큰 제거 |
| AN-07 | 새 인증 시작이 미폐기·미귀속 세션(VERIFIED 포함)을 SUPERSEDED | 사용 가능 근거 Payment당 1(fail-closed) — Coordinator가 정책으로 보면 질문 전환 |
| AN-08 | 인증 실패는 선택 화면(재시도 / failUrl), failUrl code 4종 | AC1 §3 재인증 허용 |
| AN-09 | URL state 불일치·결과 참조 404는 상태 불변, 기관 확인 불일치만 FAILED | GET 위조 DoS 방지, AC1 §5.3 정신 |
| AN-10 | 반환 URL 엄격 부분집합(정규화 없음, 포트·fragment·대문자·비ASCII 거절) | D-MD-009 정신, 파서 차이 제거 |
| AN-11 | 토큰 발급은 host만 판정(권리 재평가는 confirm) | 금융 효과 없음, 권위 판정은 승인 시작 |
| AN-12 | 잠금 순서 CA-05 뒤에 checkout_session → checkout_token | 기존 순서 보존·확장 |
| AN-13 | 기존 `payment` 모듈에 배치(새 `checkout` 모듈 기각) | §11.1 |
| AN-14 | 토큰·결제창 세션은 Store(프로토콜 기록), AuthenticationSession은 Repository | §14 판정 기준 |
| AN-15 | checkout 전용 connector + 3-connector 진입 필터 | CP-API §8 심층 방어 재사용, 추출 용이 |
| AN-16 | 쿠키 SameSite=Lax, `__Host-` 접두 | 복귀 GET 쿠키 필요 |
| AN-17 | CSRF 토큰은 쿠키 비밀에서 파생(저장 없음) | 원문 비밀 미저장 |
| AN-18 | CSP form-action에 Simulator·Site origin(세션별) | 폼 뒤 redirect 허용 최소화 |
| AN-19 | Simulator 별도 MySQL 인스턴스, runtime은 DB 단위 DML(§9.2 role 미적용) | 외부 기관 대역, 기존 DB 계정 시험 영향 없음 |
| AN-20 | PG ↔ Simulator는 client별 Bearer 키(digest 저장), 본문 서명 없음 | TEST 대역, D-AUTHN-003 "서명 키 불필요" |
| AN-21 | 인증 프로파일(카드사 allowlist·Plan 정책 Demo)은 PAYMENT 로컬 버전 설정, 세션에 버전 저장 | D-MS2-004 "기관 프로파일 allowlist", D-AUTH-009 |
| AN-22 | 식별자 접두 `ctk_`·`cks_`·`aus_`·`sat_`(+ULID), 비밀 digest 접두 `cth1:`·`cks1:`·`std1:`·`rrd1:` | 기존 접두 관례, 규칙 버전 표시 |
| AN-23 | 결제창 세션은 절대 TTL만(idle TTL 없음 — 요청마다 UPDATE 회피) | 단순성, 값은 Demo 설정 |

---

## 22. 범위 관찰(Coordinator 확인)

1. **A01 `payment-window` 편차**: 기준 문서는 결제창을 DB 없는 별도 실행 단위로 둔다. 이 작업은 AC·허용 경로대로 payment-api의 별도 connector에 두고(§11.1),
   provided Use Case만 호출하는 구조로 추출 가능성을 유지한다. 기준 편차로 기록할지(D-ADMIN-005처럼) Coordinator 판단.
2. **"TEST 외 환경" 해석**: Payment environment 축으로 해석해 LIVE Payment 결제창을 fail-closed로 거절한다(§13.6). 배포 단계(dev/staging/prod) 플래그를 의도했다면
   알려 달라 — 그 경우에도 LIVE Payment의 Simulator 사용은 LIVE Gate 열린 항목과 충돌하므로 별도 결정이 필요하다.
3. **AN-07(VERIFIED 미귀속 세션 대체)**은 보수적 구현 결정으로 두었다. 인증 근거 유효성 정책으로 보면 질문으로 올린다.
4. **migration 번호**: 이 작업이 PAYMENT V7/V8을 쓴다. AUTH-001 AC·MS2 §8.2 문구("V7 이후")를 V9 이후로 갱신 필요.
5. **Simulator DB 권한**: AUTH-001 가상 VAN 원장(기관 금융 사실 원본)이 들어올 때 append-only 표 권한(§9.2식)을 재검토하도록 AUTH-001 AC에 추가 권장.
6. **보존·삭제**: 토큰·결제창 세션·인증 세션 이벤트 행의 보존기간·삭제 Job은 범위 밖(DELETE 권한 없음). 후속 BACKLOG 후보.
7. **앱 전환 인증 복귀**(쿠키 없는 컨텍스트)는 실제 기관 연동 때 재설계(LIVE Gate 항목 후보).
8. **AUTH-001 인계**: confirm은 `authenticationRef`로 세션을 찾고(UNIQUE), `bindingRejection`(providerValidUntil 포함)·`bindTo`를 쓴다. `bound_authorization_id` 컬럼은
   V7이 만들고 쓰기 경로는 AUTH-001이다. C8 경합 시험은 AUTH-001 기준으로 이관.
9. **문서 갱신 위치**: 2단계에서 `ARCHITECTURE-CONVENTIONS.md`에 결제창·인증 절, `DOMAIN-DEFINITION.md` AuthenticationSession 행, `HARNESS-VERIFICATION-CONTRACT.md` §7을
   고친다 — Coordinator 미커밋 변경 커밋 뒤.

---

## 23. 질문 후보(Coordinator → 사용자)

### Q-AUTHN-05 REQUESTED Payment의 결제창 만료(EXPIRED) 발동 기준과 이번 범위

- **질문**: D-AUTH-005는 "결제창 만료 시 EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED)"를 정했지만, 어떤 시각을 기준으로 REQUESTED Payment가 만료되는지와 그 발동을
  이번 작업이 구현하는지는 정해지지 않았다(Payment에 만료 시각이 없다, DOMAIN-DEFINITION "결제창 만료 시간 수치는 정하지 않았다"). EXPIRED가 되면 같은 주문에 새
  Payment(새 키)가 허용된다(D-CPD-002).
- **권장안(A)**: Payment 생성 시각 + 결제 유효기간(버전 있는 설정, Demo 값)으로 **절대 만료 시각을 생성 때 고정 저장**하고, 이번 작업이 만료 Use Case
  (`RequestedPaymentExpiry`, 잠금 control → payment → 세션 → 결제창 세션 → 토큰)·주기 작업·복귀/교환과의 경합 시험(C7)을 구현한다. 토큰·결제창 세션·인증 세션 만료는
  Payment 만료 시각을 넘지 않게 자른다. CreatePayment API·응답은 바뀌지 않는다(서비스가 값만 저장).
- **대안(B)**: 이번 작업에서는 자동 만료를 하지 않는다 — REQUESTED는 계속 활성(같은 주문의 같은 내용 요청은 그 Payment를 재사용, 가맹점은 토큰을 다시 발급). 만료
  발동·기준 시각은 AUTH-001에서 confirm 대기 TTL과 함께 정한다(C7은 AUTH-001로 이관).
- **대안(C)**: 마지막 결제창 활동(토큰 발급·교환·인증) 기준 연장형 만료.
- **영향**: 주문의 새 Payment 허용 시점(상태 의미), 가맹점 재시도 UX(만료 뒤 토큰 발급은 409 `PAYMENT_TERMINATED`), CreatePayment 저장 변경 여부(A만), 버려진
  REQUESTED 누적(B는 계속 누적), 경합 시험 위치.
- **막히는 단계**: S11(만료 Use Case·주기 작업·C7), S4의 `payment.checkout_expires_at` 컬럼, 토큰·세션 TTL 자르기. 나머지(S1~S10, S12)는 답과 무관하게 진행한다.

(검토했으나 질문으로 올리지 않은 것: 조회 timeout 시 세션 처리 — AC1 §9.1 Unverifiable·D-AUTHN-003·D-AUTH-009로 "IN_PROGRESS 유지·TTL 내 재조회"가 정해진다(§7.3 R3).
토큰 API 멱등 — D-AUTHN-001 "hash만"이 재생을 불가능하게 하고 금융 효과가 없어 효과 멱등으로 충분(§5.4). 카드사 선택 값 대조 — D-MS2-004로 정해진다(§6.2).
"TEST 외 환경" — Payment 환경 축 해석으로 도출(§13.6, §22-2 확인 요청). 종료 Site host 거절 — D-MD-008에서 도출(§8.2).)
