# CP-API-001 CreatePayment Application/API·통합 검증 명세 (1단계 명세 → 2단계 구현 반영)

> **2단계 갱신(2026-10-14)**: Q-CPA-01은 사용자가 "Bearer API Key (Recommended)"로 답했다(**D-CPA-001**, `docs/DECISIONS.md`). §19의 범위 항목은
> Coordinator가 결정했다(§19 각 항목 끝 "결정"). 2단계 TDD 구현이 반영됐고 명세와 다른 점은 §20에 적었다. 아래 1단계 문장 중 "답 전에는"·"미결"로
> 남은 표현은 §5·§18·§20의 확정 내용이 우선한다.

작성 2026-10-14 KST. **1단계 명세이며 코드 변경 없음.** 기준 commit `f3b705c`(MASTER-SNAPSHOT-001). 작업 트리에는 Coordinator 소유 상태 파일
(`harness/tasks.json`·`harness/progress.json`) 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 업무 정책을 채택하지 않는다 —
§18 질문 후보는 답변 전 해당 운영 동작(가맹점 Credential 운영 검증)을 구현하지 않는다. 2단계 TDD 구현은 Coordinator가 질문을 처리한 뒤 시작한다.

표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **구현 결정** = 금융 의미가 없는 가역적 구현 선택(§17에 모음, 질문 아님), **미결(Q-CPA-xx)** =
§18 질문 후보, **Demo** = 시험 전용 값(운영값 아님), **범위 관찰** = AC 해석·범위에 관한 Coordinator 확인 사항(§19).

입력 문서(sha256, 2026-10-14 읽은 시점):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 | `d1ab2b0c…b431` |
| 결정 | `docs/DECISIONS.md`(D-CP-*, D-CPC-*, D-CPD-*, D-MD-016~019, D-MS-*) | 확정 | `61de854a…bba0` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md` §3·§7–§10 | 관례 | `07e7036f…83d4` |
| MS명세 | `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md` §5·§6·§13 | 구현 완료 명세 | `42d61b76…27e6` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…c38c1f` |
| A05 | `docs/baseline/architecture/05-security-architecture.md` | 아키텍처 자료 | `e9dcabd0…b956` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §4 | 확정 | `a8b02b77…37ae5` |
| 초안 | `docs/baseline/PAYCORE-CREATE-PAYMENT-IMPLEMENTATION-CONTRACT-v0.1.md` §7(CP-01~12) | 구현 검토 초안 | `8d715715…6cfb` |
| 작업 | `harness/tasks.json` CP-API-001 AC 23개(미커밋 작업 트리) | Coordinator 소유 | `9d52b03f…6175` |

---

## 1. AC 대응표(tasks.json CP-API-001 → 명세 절 → 계획 시험)

| # | AC(요약) | 절 | 계획 시험(2단계, 이름은 가역적) |
|---|---|---|---|
| 1 | MID/환경 권한 확인 | §5 | `MerchantScopeApiSpec`(body MID ≠ 인증 MID 403, 다른 MID·다른 환경 Credential로 같은 키 → 결과 비노출), `MerchantAuthenticationSpec`(S5, Q-CPA-01) |
| 2 | 응답 유실 후 재요청·재시작 | §9.1·§9.2 | `ResponseLossReplayApiSpec`(응답 버린 뒤 같은 키 → 200·replayed·행 1), `RestartReplaySpec`(컨텍스트 A 커밋 → 종료 → 컨텍스트 B 같은 DB·같은 키 → 같은 paymentId) |
| 3 | 동일 source fingerprint 검증 + 독립 리뷰 | §16 끝 | Verifier/Reviewer 역할(구현자 판정 아님). 구현자는 VERIFY-GATE 등 등록 명령만 보고 |
| 4 | Fixture Monkey 테스트 전용·프로젝트 Fixture 뒤·production 유입 금지 | §12 | 기존 `RandomCreatePaymentFixture` 유지 + `RandomCreatePaymentRequestFixture`(API 본문), `verifyRuntimeHasNoTestTools`·`verifyNoInstancio`(check 의존) |
| 5 | (L-2) checkAuthorizationStart + startAuthorization 단일 연산 | §3.6 | `OrderPaymentControlSpec` 추가 행(hold·과거 승인·stale → 거절 + Payment 상태 불변), ArchUnit `paymentStartAuthorizationOnlyThroughControl`(위반 fixture 포함) |
| 6 | (D-CP-003) 같은 키 재요청 시 현재 권한 재확인 후 저장 결과 | §5.4 | `ReplayAuthorityApiSpec`(Credential 폐기 뒤 재생 401, body MID 불일치 재생 403, 다른 환경 Credential → 다른 범위) |
| 7 | 계약 §9 오류 표 전체·내부 경합 503·비노출 | §7 | `CreatePaymentErrorMappingSpec`(표의 모든 행), `ErrorBodyLeakSpec`(입력값·존재 여부·내부 값 미포함) |
| 8 | r1 + golden vector(필드 있음/없음), orderName 길이 단위·DB 컬럼 | §6, §4.4 | `RequestFingerprintV1Spec`(golden 5종 = Python 독립 계산 파일), `OrderNameRuleSpec`(100 code point 경계·보충 문자·DB 왕복) |
| 9 | 알 수 없는 필드 400, 정확한 정수 파서 | §4.2·§4.3 | `StrictJsonParserSpec`(소수·지수·문자열·`+`·선행 0·`-0`·Long 초과·중복 키·null·trailing token·중첩) |
| 10 | (§6.4) 저장 fingerprint 버전으로 재계산 비교 | §6.4 | `FingerprintVersionDispatchSpec`(저장 `v1`/`r1` 정상, 저장 `v2:`/`r2:` → 503·변화 없음, KEY_REUSED·INTENT_CONFLICT 오분류 없음) |
| 11 | (D-CPC-004/007) 응답 유실·30일 경과 재요청이 동시 활성·이중 승인 없음, 거절 재생 | §9.3·§9.4 | `IdempotencyExpiryApiSpec`(30일 경계 -1ms 재생 / 정각 새 요청 → EXISTING_ACTIVE·안전 종료 뒤 CREATED gen2·승인 뒤 409, 동시 8건), `RejectionReplayApiSpec` |
| 12 | IN_PROGRESS 처리와 lock wait timeout 분류 | §7.3 | `LockWaitClassificationSpec`(같은 키 대기 → 409, 인접 갭 대기 → 409, control·head 대기 → 503, TX 전체 rollback 확인) |
| 13 | (D-CP-003) 생성 응답에 현재 상태 없음, GetPayment 분리 | §4.5·§4.6 | `CreateResponseShapeSpec`(Payment가 AUTHORIZING/UNKNOWN으로 바뀐 뒤 재생해도 `lifecycleAtCreation=REQUESTED`, 응답 필드 집합 고정). GetPayment endpoint는 **범위 관찰 §19-2** |
| 14 | CP-01·02·05~07·12 API 수준 | §9.5 | `CreatePaymentApiScenarioSpec`(시나리오별 HTTP·DB 행 수) |
| 15 | 서로게이트·제어문자·Utf8Text·응답 schema·헤더·이벤트·ID 생성기 | §4, §6, §10 | `StringIntegrityApiSpec`, `PaymentRequestedEnvelopeApiSpec`, `PaymentIdentifierGeneratorSpec` |
| 16 | (L-1) IN_PROGRESS에 인접 갭 잠금 대기 포함 — 계약 §9·KDoc | §7.3 | 계약 §9 문구 갱신(S0) + `RequestInProgress` KDoc + 인접 갭 재현 시험 |
| 17 | (L-4) afterCommit 예외 → 같은 키 replayed·행 1 | §9.1 | `CommitOutcomeUnknownApiSpec`(afterCommit 예외 주입 → 503 → 같은 키 200 replayed, 응답 유실 시험과 같은 Spec) |
| 18 | (L-10) 세션 time_zone 기동/health 검사, URL 시간대 파라미터 금지 | §11.3 | `DbSessionTimeZoneSpec`(기동 실패·health DOWN·URL 파라미터 거부), 배포 규칙 문서 |
| 19 | (m-7) jOOQ 시각 조회 시 §9.1 정정 적용 | §11.4 | GetPayment 이월 시 해당 없음(이유 기록). 새 jOOQ 시각 조회 없음 |
| 20 | (n-4) hibernate 로거 OFF + 1062 로그 캡처 | §11.2 | `MainConfigurationSpec` 행 추가, `DuplicateKeyLogLeakSpec`(멱등 키·주문 키·payment_key 1062 경합 → 로그에 값 없음) |
| 21 | (n-3) 오류 처리기 예외 원문 로그 금지 | §11.1 | `ErrorHandlerLoggingSpec`(로그 줄에 메시지 원문·입력값 없음, 허용 필드만) |
| 22 | (MS 이월) /internal 공개 API 노출 차단 | §8 | `InternalPortIsolationSpec`(공개 포트 `/internal/**` → 404, 내부 포트 `/v1/**` → 404, 실서버 두 포트) |
| 23 | (MS 이월) 일시 불가 응답 Retry-After 규칙 | §7.4 | `RetryAfterSpec`(503 두 종류·409 IN_PROGRESS만 헤더, 나머지 없음, 설정 누락 기동 실패) |

---

## 2. 현황 조사(2026-10-14, `f3b705c`)

| 영역 | 있는 것 | 없는 것(이 작업) |
|---|---|---|
| domain(CP-DOMAIN-001, CP-CONTRACT-001) | `OrderPaymentControl`(decide·occupy·checkAuthorizationStart·recordAuthorized·hold), `Payment`(전이·종료 근거), `Money`·`CurrencyCode`·`OrderIntent.fingerprint()` v1, `MerchantOrderId`·`IdempotencyKey`(D-CPC-002 생성자 강제), `MerchantAccountId`·`MerchantSiteId`·`PaymentKey`(서로게이트 거부), `Utf8Text`(엄격 UTF-8) | 승인 시작 단일 연산(L-2) |
| application(CP-DB-001) | `CreatePaymentUseCase`/`CreatePaymentService`(멱등 → eligibility → control → Payment → Outbox, 30일 만료 갱신, 거절 저장·재생, 교착 재시도 3회), `LocalTransactions`(READ COMMITTED, commit 단계 실패 = OutcomeUnknown), `CreatePaymentFailure`(RequestInProgress·TemporarilyUnavailable·IdentifierCollision·OutcomeUnknown), `RequestFingerprint` 값 객체(`r<n>:` 형식만 검사) | r1 계산, ID 생성기, 서버 시계 Port, 버전별 재계산, IdentifierCollision 재시도, HTTP 매핑 |
| persistence | V1 schema(`payment.order_name VARCHAR(100) utf8mb4_0900_bin`, 모든 키 bin), V2 권한, JPA Store·`PersistenceFailures`(1062·1205·1213·08xxx 분류) | 진단 필드(vendor code·SQLState) 전달 |
| eligibility(MASTER-SNAPSHOT-001) | `EntitlementEligibilityAdapter`(운영 구현 1개), head `FOR SHARE`, 잠금 순서 멱등 → head(S) → control → payment | **G-1**: entitlement 쪽 `EntitlementPersistenceFailure`(LockTimeout·Deadlock·Unavailable)가 payment 서비스 분류를 거치지 않고 원형으로 올라간다(현재 500이 됨 — §3.7) |
| web | `/internal/v1/entitlement-snapshots`(HMAC, 같은 포트) | 가맹점 공개 API 전부, 내부 경로 격리 |
| 보안 | **payment-api에 Spring Security 없음.** 가맹점 Credential 발급·저장·검증 어디에도 없음(DOMAIN-DEFINITION: Credential 메타데이터 = MASTER 소유, 작업 미등록) | 인증 필터·Credential 검증(Q-CPA-01) |
| 설정 | `application.yml`: OSIV off, ddl validate, `hibernate.jdbc.time_zone=UTC`, Hikari `connectionTimeZone=UTC`·`forceConnectionTimeZoneToSession=true`, actuator health만 노출 | hibernate JDBC 오류 로거 OFF(n-4), Retry-After 설정, 내부 포트 |
| 시험 기반 | `@PaymentDbTest`(MySQL 8.4.11, runtime 계정, lock wait 8초, pool 16), `FaultInjectionConfig`, `EligibilityTestConfig`(`RecordingEligibilityPort` @Primary), Named Fixture(`CreatePaymentFixture`·`OrderFixture`…), Fixture Monkey(`RandomCreatePaymentFixture`, seed 고정) | 실서버(RANDOM_PORT) HTTP 시험 기반, 로그 캡처 도구(payment-api), 시험용 인증기 |

---

## 3. Application 유스케이스

### 3.1 흐름

```text
[HTTP 필터: 포트 격리(§8) → 인증(§5) → requestId 발급]
  → web CreatePaymentController(§4): 헤더·본문 엄격 파싱/형식 검증(400) → body MID = 인증 MID(403)
  → application.provided SubmitCreatePaymentUseCase.submit(authContext, request, idempotencyKey, requestId)   ── 새 facade(구현 결정)
       ├ domain 값 객체 생성(최후 방어선, 예외 = 결함 500 — web이 먼저 400을 만든다)
       ├ 요청 내용 CreatePaymentRequestContent(닫힌 7필드) 구성 — r1은 서비스가 저장 버전으로 계산(§6.4)
       ├ 서버 시계 PaymentClock.now() → requestedAt
       ├ PaymentIdentifierGenerator → paymentId·paymentKey·멱등 기록 ID·eventId
       └ CreatePaymentUseCase.create(command)   ── 기존 로컬 TX(§3.2)
            IdentifierCollision → 새 식별자로 1회 재시도(§3.5), 그래도 충돌 → TemporarilyUnavailable
  → commit 뒤 결과 → HTTP 매핑(§7)
```

- web은 `application.provided`만 호출한다(ArchUnit `webAdapterUsesOnlyApplication`). facade는 TX를 열지 않는다 — TX는 `CreatePaymentService`의 `LocalTransactions`가 소유(이미 TX면 거부, 관례 §9).
- facade를 따로 두는 이유(구현 결정): ID 생성·시계·충돌 재시도·r1 입력 구성은 HTTP와 무관한 application 책임이고, Batch·관리 경로가 생겨도 같은 경로를 쓰게 한다. `CreatePaymentUseCase`는 그대로 "검증된 command의 로컬 TX"로 남는다.

### 3.2 TX 경계와 잠금 순서(확정·기존 — 변경 없음)

- 한 PAYMENT 로컬 TX(READ COMMITTED): 멱등 기록(insert-first, `FOR UPDATE`) → `entitlement_head` **FOR SHARE**(eligibility, MS명세 §5.3) → `order_payment_control` → `payment` → Outbox(CREATED만) → 멱등 결과 → commit → 응답.
- 외부 호출 없음. 인증(§5)은 TX 밖에서 끝난다 — Credential 검증 읽기는 생성 TX의 잠금 순서에 들어가지 않는다(구현 결정: 인증 읽기는 별도 짧은 읽기 전용 TX, 잠금 없음).
- 재생 경로(같은 키·같은 내용·미만료)는 eligibility·판정을 하지 않는다(D-CP-003) — 권한 재확인은 TX 밖 인증 필터 + body MID 검사 + 멱등 범위(environment, 인증 MID)가 한다(§5.4).
- 기존 경합 시험(`CreatePaymentConcurrencySpec`·`EntitlementEligibilitySpec`)은 그대로 통과해야 한다(회귀 기준).

### 3.3 command 변경(구현 결정)

| 현재 | 변경 |
|---|---|
| `CreatePaymentCommand.requestFingerprint: RequestFingerprint`(호출자가 계산) | `requestContent: CreatePaymentRequestContent`(닫힌 7필드 값) — 서비스가 새 예약에는 현재 버전(r1)으로, 기존 기록에는 **저장된 버전**으로 계산해 비교(§6.4). `RequestFingerprint` 값 객체·DB 컬럼은 그대로 |
| `requestedAt`을 호출자가 넣음 | facade가 `PaymentClock` Port 값으로 넣는다(가맹점 시각 아님, 계약 §2). 시험은 고정·이동 가능한 시계로 30일 경계를 만든다 |

`CreatePaymentRequestContent`는 계약 §7 입력 그대로: merchantAccountId·merchantSiteId·merchantOrderId(문자열), amount(Long), currency·paymentMethod(문자열), orderName(String?). 생성자는 `Utf8Text.isWellFormed`·orderName 규칙(§4.4)을 다시 검사한다(최후 방어선).

### 3.4 식별자 생성기와 시계(구현 결정)

| 값 | 형식 | 생성 | 근거 |
|---|---|---|---|
| paymentId | `pay_` + ULID 26자(Crockford 대문자, 48bit ms 시각 + 80bit `SecureRandom`) = 30자 | `PaymentIdentifierGenerator`(application.required) + 운영 구현 `SecureRandomPaymentIdentifierGenerator`(application, JDK만 — MASTER `RandomMasterIdGenerator`와 같은 관례) | 계약 §5 관례 "`pay_` + 26자 ULID 계열", VARCHAR(64) |
| paymentKey | `pk_` + base64url(무패딩, 32바이트 = 256bit `SecureRandom`) = 46자 | 같은 생성기 | 계약 §5 "128bit 이상 난수". 자격 증명 아님 |
| 멱등 기록 ID·eventId | UUID v4 소문자 표준 표기 | 같은 생성기 | 기존 값 객체 형식 |

- **서로게이트 검사 여부(AC 15 결정)**: 생성기 출력은 구성상 ASCII `[A-Za-z0-9_-]`라 짝 없는 서로게이트가 생길 수 없다. 그래도 domain `PaymentKey`·`PaymentId` 생성자 검사는 **유지**한다(비용 무시 가능, 외부 입력 경로가 생길 때의 최후 방어선). 생성기 시험은 출력 정규식·길이·10만 건 중복 없음·`SecureRandom` 사용(주입 대역으로 확인)만 본다.
- `PaymentClock`(application.required) + 운영 `SystemPaymentClock`. ms로 자르는 것은 기존 서비스가 한다.
- ULID 시각 부분은 생성 시각을 드러낸다 — paymentId는 응답 필드라 생성 시각(`paymentCreatedAt`)과 같은 정보다. 추측 불가성은 80bit 난수 부분이 담당한다.

### 3.5 서버 생성 ID 충돌(CP-10)

`CreatePaymentFailure.IdentifierCollision`(rollback 확정)이면 facade가 **새 식별자 전체**로 같은 command를 1회 다시 실행한다(같은 멱등키·같은 요청 시각). 두 번째도 충돌이면 503 `TEMPORARILY_UNAVAILABLE` + ERROR 로그(분류 `IDENTIFIER_COLLISION_REPEATED`). 사용자 payload 충돌(409 `*_CONFLICT`)로 보이지 않는다(계약 §9 마지막 행). 시험: 생성기 대역이 첫 호출에 기존 paymentId/paymentKey/eventId를 내게 해 201·행 1, 두 번 연속이면 503·행 0.

### 3.6 승인 시작 단일 연산(L-2)

- domain에 `OrderPaymentControl.startAuthorization(payment: Payment)` 추가: `checkAuthorizationStart(payment)`(hold·과거 승인·stale·REQUESTED 아님 거절) → 통과하면 `payment.startAuthorization()`. 검사 실패 시 Payment 상태는 그대로(전이 전 예외). `checkAuthorizationStart`는 `private`으로 바꾼다.
- Kotlin에는 package-private가 없어 `Payment.startAuthorization`을 control만 부르게 언어로 막을 수 없다(`internal`은 Gradle 모듈 전체). 그래서 **ArchUnit 규칙** `paymentStartAuthorizationOnlyThroughControl`: main 코드에서 `Payment.startAuthorization()` 호출자는 `OrderPaymentControl`뿐(위반 fixture: application 서비스가 직접 호출 → 실패). 시험 코드는 대상 아님(`DO_NOT_INCLUDE_TESTS`) — 기존 상태 준비 Fixture는 계속 쓸 수 있다.
- **범위 관찰 §19-1**: CreatePayment는 승인을 시작하지 않는다. 승인 시작 Use Case와 그 application 시험(같은 TX에서 control 잠금 → 연산 → 저장)은 AUTH-001 AC(같은 L-2 문구가 있음)에 남는다. 이 작업은 domain 연산 + 우회 차단 규칙까지다.

### 3.7 eligibility 경로 기술 실패 분류(G-1, 구현 결정)

현재 `EntitlementEvaluationService`(entitlement 모듈)가 던지는 `EntitlementPersistenceFailure`(entitlement `application.required`)는 payment 모듈이 볼 수 없는 타입이라(Modulith 공개 범위) `CreatePaymentService.toFailure`가 분류하지 못하고 원형으로 올라간다 → 500. 변경:

- entitlement `application.provided`에 `EntitlementEvaluationFailure`(sealed: `Deadlock`·`LockTimeout`·`Unavailable`, 메시지에 값 없음)를 두고 `EntitlementEvaluationQuery.evaluate`가 저장소 실패를 그것으로 바꿔 던진다.
- `EntitlementEligibilityAdapter`(payment `adapter.integration`)가 그것을 payment `PersistenceFailure.Deadlock/LockTimeout/Unavailable`로 옮긴다 → 기존 분류: Deadlock은 새 TX 재시도, head 대기 LockTimeout은 **503 `TEMPORARILY_UNAVAILABLE`**(같은 키 대기가 아님 — §7.3), Unavailable은 503.
- 시험: Inbox 적용 TX가 head X를 쥔 채 멈춘 동안 CreatePayment → lock wait timeout → 503·Retry-After·행 0, Inbox 재개 후 같은 키 재요청 → 정상 판정.

---

## 4. Web adapter

### 4.1 endpoint·헤더

| 항목 | 규칙 | 근거 |
|---|---|---|
| 경로 | `POST /v1/payments`(공개 포트만, §8) | 계약 §3 관례 |
| 요청 `Content-Type` | `application/json`, charset 파라미터가 있으면 `utf-8`(대소문자 무시)만. 그 밖은 400 `INVALID_REQUEST` | 구현 결정(계약 오류 표를 닫힌 집합으로 유지 — 415를 새로 만들지 않음) |
| 요청 `Accept` | 없음·`*/*`·`application/json`만 허용, 그 밖은 400 `INVALID_REQUEST` | 구현 결정 |
| `Idempotency-Key` | 정확히 1개. 없음·여러 개·형식 밖 → 400 `INVALID_IDEMPOTENCY_KEY`(field `Idempotency-Key`) | 확정 D-CPC-002, 계약 §3 |
| `X-Request-Id`(가맹점) | 있으면 출력 가능 ASCII 1..128자일 때만 로그에 `merchantRequestId`로 보존, 아니면 버림(오류 아님). 업무 키 아님 | 계약 §2 관례 |
| 응답 `PayCore-Request-Id` | 모든 응답(401 포함)에 서버 생성 requestId(UUID). 오류 본문 `requestId`와 같고 command `correlationId`·이벤트 correlationId와 같다 | 계약 §2·§9, A07 |
| 응답 `Idempotent-Replayed` | 멱등 결과를 거친 응답(201·200·409 업무 거절)에 항상 `true`/`false` | 계약 §5 관례(재생 `true`), `false` 명시는 구현 결정 |
| 본문 크기 | 16 KiB 초과 → 400 `INVALID_REQUEST`(스트림을 한도+1바이트까지만 읽음) | 구현 결정(정상 최대 본문 ≈ 1 KiB) |
| 방법 | 다른 HTTP 방법 → 405 `METHOD_NOT_ALLOWED`, 없는 경로 → 404 `NOT_FOUND`(인증 뒤, 같은 오류 형식) | 구현 결정(라우팅 오류, 업무 표 밖) |

### 4.2 엄격 JSON 파서(확정 계약 §3.1·D-CPC-009, 구현 방식은 구현 결정)

Spring 메시지 변환기·데이터 바인딩을 쓰지 않고 본문 바이트를 직접 처리한다(기본 Jackson 설정·Boot 기본값 변화에 정확성을 맡기지 않는다):

1. 본문 바이트를 **엄격 UTF-8 디코더**(`CodingErrorAction.REPORT`)로 문자열화. 잘못된 UTF-8·BOM(U+FEFF 시작)·빈 본문 → 400 `INVALID_REQUEST`. (Jackson의 UTF-16/32 자동 감지를 막는다 — 계약은 UTF-8만.)
2. 그 문자열을 Jackson 3 스트리밍 파서(`tools.jackson.core`, `JsonParser` 토큰 단위)로 읽는다. 최상위는 객체 하나, 끝 뒤 토큰 있으면 400. 값은 스칼라만(배열·객체 → 400).
3. 필드 이름은 닫힌 집합 7개만. 모르는 이름 → 400 `INVALID_REQUEST`(이름을 응답·로그에 echo하지 않음). 같은 이름 두 번 → 400.
4. `null` → 400(필수·선택 모두, 계약 §3.1). 선택 필드는 "없음"만 허용.
5. amount: 토큰이 `VALUE_NUMBER_INT`이고 원문 텍스트가 `^-?(0|[1-9][0-9]*)$`이며 `toLongOrNull()` 성공·1 이상이어야 한다. 소수(`1.0`)·지수(`1e3`)·문자열 `"100"`·`+1`·선행 0(`010`)·`-0`·0·음수·Long 초과(`9223372036854775808`) → 400 `INVALID_AMOUNT`. 부동소수점 변환 없음.
   - **한계(review round2 n-b, 코드 변경 없음)**: Jackson `StreamReadConstraints`의 숫자 길이 상한(기본 1000자리)을 넘는 숫자 토큰은 Jackson이 토큰화 단계에서 거절한다. 그 경우 amount 분류까지 가지 않고 400 `INVALID_REQUEST`(field 없음)가 된다. 1000자리를 넘는 금액은 어떤 경우에도 Long 범위 밖이라 거절 결과(400·저장 없음)는 같고 오류 코드만 `INVALID_AMOUNT`가 아니다. 본문 16 KiB 상한 안에서 생길 수 있는 경계다.
6. 문자열 값: `VALUE_STRING`만(숫자·불리언 → 400). Jackson이 `\uD800` escape를 짝 없는 서로게이트 그대로 돌려주므로 **모든 문자열 값에 `Utf8Text.isWellFormed`** 검사 → 위반 400 `INVALID_REQUEST`(field 이름 포함). 치환 없음.

### 4.3 필드 검증 표(순서 = 보고 순서, 첫 위반만 응답 — 구현 결정)

| 순서 | 필드 | 위반 | 응답 |
|---|---|---|---|
| 0 | `Idempotency-Key` 헤더 | 없음·중복·`^[A-Za-z0-9\-_.:]{1,64}$` 밖 | 400 `INVALID_IDEMPOTENCY_KEY` |
| 1 | 본문 구조 | §4.2 1–4 | 400 `INVALID_REQUEST`(field 없음) |
| 2 | merchantAccountId | 없음·빈 값·비문자열·서로게이트·64자 초과(영속 컬럼)·거절 문자(§4.4와 같은 Cc·U+2028/2029·bidi 제어 — review round2 m-2) | 400 `INVALID_REQUEST`, field |
| 3 | merchantSiteId | 같은 규칙 | 400 `INVALID_REQUEST`, field |
| 4 | merchantOrderId | `^[A-Za-z0-9\-_.:]{1,64}$` 밖(D-CPC-002) | 400 `INVALID_REQUEST`, field |
| 5 | amount | §4.2-5 | 400 `INVALID_AMOUNT`, field |
| 6 | currency | `^[A-Z]{3}$` 밖(대소문자 변환 없음) | 400 `INVALID_CURRENCY`, field |
| 7 | paymentMethod | `CARD` 외 문자열(알려진 enum 밖) | 400 `INVALID_REQUEST`, field. 알려진 값이지만 미개통이면 422 `METHOD_NOT_ENABLED`(eligibility) |
| 8 | orderName | §4.4 | 400 `INVALID_REQUEST`, field |
| 9 | body MID ≠ 인증 MID | — | 403 `MERCHANT_SCOPE_MISMATCH`(400 검사가 모두 통과한 뒤, 계약 §3.2-3) |

MID·Site의 64자 상한: MASTER 발급 형식(`mid_`·`site_` + ULID 26자, 30·31자)과 PAYMENT 컬럼 VARCHAR(64)에 맞춘 기술 상한(구현 결정). 형식 자체(접두·ULID)는 PAYMENT가 강제하지 않는다 — 형식 밖 Site는 eligibility에서 404(존재 비노출).

### 4.4 orderName 규칙(계약 §3 이월 L-8 확정 — 구현 결정)

- **길이 단위 = Unicode code point, 1..100.** 이유: `payment.order_name VARCHAR(100) CHARACTER SET utf8mb4`는 MySQL에서 **문자(code point) 100개**를 뜻한다. UTF-16 code unit 단위면 보충 문자(이모지 등)가 2로 세져 DB보다 좁고, UTF-8 byte 단위면 한글 100자를 거절해 DB보다 좁다. code point는 DB 컬럼과 정확히 같은 한계라 "API 통과 → DB 길이 오류" 경로가 없다. **migration 변경 없음**(기존 `CreatePaymentCommand` init의 `codePointCount ≤ 100`과 같음).
- 경계 시험: 보충 문자 100개(UTF-16 200 unit, UTF-8 400 byte) 통과·DB 왕복 동일 / 101개 거절 / 한글 100자 통과 / 빈 문자열 거절.
- **거절 문자(구현 결정)**: 짝 없는 서로게이트(§4.2-6), 제어문자 Cc 전부(`Character.isISOControl`: U+0000–U+001F, U+007F, U+0080–U+009F — 탭·개행 포함), 줄·문단 구분자 U+2028·U+2029(로그 줄 분리), 양방향 embedding/override/isolate U+202A–U+202E·U+2066–U+2069(표시 위조). 그 밖 Cf(예: 이모지 결합 ZWJ U+200D)·공백만 있는 이름은 허용(trim·NFC 없음, D-CP-001 정신).
- orderName은 고정 입력이 아니며(D-CPC-001) 이벤트 payload에서 제외된다(계약 §10). 멱등 r1에는 포함된다(같은 키로 orderName만 바꾸면 409 `IDEMPOTENCY_KEY_REUSED`).

### 4.5 응답(확정 계약 §5·D-CP-003)

```json
HTTP/1.1 201 Created            (재생·EXISTING_ACTIVE_PAYMENT는 200)
Idempotent-Replayed: false
PayCore-Request-Id: 6f1c…
{"paymentId":"pay_01J…","paymentKey":"pk_…","outcome":"CREATED","lifecycleAtCreation":"REQUESTED",
 "paymentCreatedAt":"2026-10-14T01:02:03.456Z","merchantOrderId":"order-0001","amount":10000,"currency":"KRW"}
```

- 필드 집합 고정(시험이 정확한 키 집합 비교). `status`·현재 상태·merchantSiteId·orderName·eligibility 버전 없음.
- `paymentCreatedAt`: UTC, ms 3자리 고정, `Z`(구현 결정 — 원문 형식 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
- amount는 JSON 정수(직렬화도 Long 그대로, D-CPC-009).
- 재생은 최초 결과의 paymentId·outcome·시각을 그대로(같은 바이트 본문 — 시험).
- `Location` 헤더 없음(GetPayment 경로가 아직 없음 — §4.6).

### 4.6 GetPayment 범위(구현 결정 + 범위 관찰 §19-2)

이 작업은 **GetPayment endpoint를 만들지 않는다.** AC 13의 "GetPayment 분리"는 "생성 응답에 현재 상태를 넣지 않고 현재 상태는 별도 조회 API로만"(D-CP-003)이라는 계약 경계로 해석하고, 이 작업에서는 생성 응답이 현재 상태를 담지 않음을 시험한다. 이유: 현재 상태 표현(특히 UNKNOWN·AUTHORIZING을 가맹점에 어떻게 보이는지, 재시도 안내)은 AUTH-CONTRACT-001의 상태 계약에 묶이고, 조회는 jOOQ CQRS 조회 adapter로 별도 설계한다. Coordinator가 별도 작업(예: PAYMENT-QUERY-001)이나 AUTH-001 범위로 등록할지 정한다.

---

## 5. 가맹점 인증과 MID/환경 권한

### 5.1 근거와 현재 상태

| 근거 | 내용 | 상태 |
|---|---|---|
| 계약 §2 | authenticatedPrincipal·authenticatedMid·environment는 **인증 Credential에서 서버가 결정**, body로 덮어쓰지 않음, environment는 body에 없음 | 확정 계약 |
| 계약 §2·§9 | body merchantAccountId ≠ 인증 MID → 403 `MERCHANT_SCOPE_MISMATCH`, 인증 없음·Credential 폐기 → 401 `UNAUTHENTICATED` | 확정 계약 |
| 허용 §4.1-1 | 신뢰된 인증 주체의 MID·환경·**operation** 범위 일치, Credential 유효 | 확정 |
| 허용 §4.2 | 긴급 MID/**키** 차단 우선 전파·인스턴스 ACK, 무기한 로컬 캐시 금지(A05 금지사항) | 확정(구조) |
| A05 | Credential을 MID·환경·허용 작업에 연결, 검증 전용 API 키는 **단방향 검증값**으로 저장, 비밀은 비밀 저장소 | 아키텍처 자료 |
| D-MD-003 | MID 하나 = 계약 계정, TEST/LIVE는 **Credential·요청 축** | 확정 |
| DOMAIN-DEFINITION §2 | Credential 메타데이터 = MASTER 소유 | 확정(구조) |
| MS명세 §13-3 | Credential 긴급 차단 전파는 "Credential 작업(미등록)" | 이월 |

**코드 현황**: payment-api에 Spring Security도 Credential 저장·검증도 없다. MASTER에도 Credential 발급이 없다. 작업 목록에 Credential 작업이 없다.

**확정(D-CPA-001, 2026-10-14 — Q-CPA-01 답변 "Bearer API Key (Recommended)")**: (MID, 환경)별 secret API key `pc_test_sk_`/`pc_live_sk_` + keyId + 256bit 난수를 `Authorization: Bearer`로 전송. 저장은 keyId + SHA-256만, 교체 중 (MID, 환경)당 활성 키 최대 2개, **v1 권한 범위는 공개 가맹점 API 전체**(operation 축 없음). MASTER가 발급·소유하고 기존 서명 채널(D-MS-004)로 PAYMENT 투영에 전파, PAYMENT는 로컬 검증, 폐기는 우선 전파(lease 상한·무기한 cache 금지). 발급·전파는 MASTER-CREDENTIAL-001이며 그 전까지 운영 인증은 fail-closed(모든 가맹점 요청 401). 이 작업은 PAYMENT 쪽(투영 표·검증·필터·행위자 컬럼)만 구현했다 — 구현 내용은 §20-2.

### 5.2 설계(답과 무관한 부분 — 지금 명세, 2단계 S4에서 구현)

- payment-api에 `spring-boot-starter-security`(Boot 4.1.1 BOM 관리, 카탈로그에 이미 있음 — internal-admin 사용) 추가. 공개 포트 필터 체인: stateless, 세션 없음, CSRF 끔(쿠키·세션 없는 헤더 인증 JSON API — 브라우저 자동 전송 자격 증명이 없어 CSRF 대상이 아니다), 기본 거부: `POST /v1/payments`는 인증 필요, `/actuator/health`만 무인증, 나머지는 인증 뒤 404.
- 인증 필터는 `MerchantAuthenticator`(web 쪽 계약) → `application.provided` `AuthenticateMerchantUseCase.authenticate(presented): MerchantAuthentication?` → `application.required` `MerchantCredentialStore`. 결과 `MerchantAuthentication(principalId(credentialId), mid, environment, operations)`를 SecurityContext에 둔다. **Spring Security 타입은 web adapter·설정 패키지에만**(application은 모름).
- 401은 원인 구분 없이 같은 응답(`UNAUTHENTICATED`, 고정 문구) — 키 없음·형식 오류·없는 키·폐기·만료를 응답으로 구분하지 않는다(열거 방지). 원인은 metric `paycore.payment.api.auth.failures{reason}`(낮은 cardinality)·WARN 로그(keyId 접두만, 비밀 없음)에만.
- operation 검사: **없음** — D-CPA-001이 v1 키 권한을 공개 가맹점 API 전체로 정했다(세분 범위가 생기면 새 결정과 함께 추가).
- 2단계 시험 기반: `TestMerchantAuthenticationConfig`(src/test, @Primary)가 Demo Credential(`Authorization: Bearer demo-test-…` 등 시험 문자열 → MID·환경)을 준다. **Demo 값이며 운영 Credential이 아니다.** 운영 구현 bean은 Q-CPA-01 답 뒤 S5.

### 5.3 운영 Credential 구현(S5, Q-CPA-01 답 뒤)

권장안 기준 계획(답이 다르면 이 절을 갱신): PAYMENT 로컬 투영 표 `merchant_api_credential`(credential_id PK, merchant_account_id, environment, verifier_hash, operations, status, valid_from/until, revision) — V5 migration + V6 표 단위 권한(SELECT만 runtime, 쓰기는 수신 경로 작업이 추가). 운영에서 이 표를 채우는 발급·전파(MASTER 발급 → 서명 push → PAYMENT Inbox)는 별도 작업(제안 MASTER-CREDENTIAL-001)이며, **그 전 운영 payment-api는 모든 가맹점 요청을 401로 거절한다(fail-closed)**. 시험은 Demo 행을 시험 전용 경로로 넣는다. 캐시 없음(A05 "폐기가 전달되지 않는 무기한 로컬 캐시" 금지). 생성 행위자(credential_id)를 멱등 기록에 남기는 컬럼(Auditability)은 같은 migration에서 추가한다.

### 5.4 같은 키 재요청의 권한 재확인(D-CP-003, AC 6)

재생 응답 전에 매번 다음이 다시 통과해야 한다 — 저장 결과가 있다는 사실로 권한을 대신하지 않는다:

1. 인증 필터(현재 Credential 유효 — 폐기 뒤 재생은 401, 본문에 아무 결과도 없음).
2. operation 범위(§5.2).
3. body MID = 인증 MID(403) — 멱등 조회 **전**(계약 §3.2-3).
4. 멱등 범위 = (인증 environment, 인증 MID, operation, key): 다른 MID·다른 환경 Credential은 같은 키여도 다른 범위라 그 결과를 찾을 수 없다(다른 MID → body MID 불일치 403 또는 자기 MID의 새 요청).
5. 기존 `present()`의 범위 검사(저장 결과 Payment의 environment·MID = 요청 범위, 어긋나면 결함 — 노출 없이 500).

시험(`ReplayAuthorityApiSpec`): (a) 생성 → Credential 폐기 → 같은 키·같은 내용 → 401·본문에 paymentId 없음, (b) 생성 → 같은 MID의 TEST Credential로 같은 키·같은 본문 → LIVE 결과가 아니라 TEST 범위 새 판정, (c) 생성 → 다른 MID Credential + 원 MID body → 403(결과 미노출), (d) 정상 Credential 재생 → 200 replayed. (a)는 운영 Credential 저장소(S5)가 있어야 실제 폐기를 시험할 수 있다 — S4에서는 시험용 인증기의 폐기 표시로 같은 경로를 확인한다.

---

## 6. 요청 fingerprint r1(계약 §7, 이월 L-8)

### 6.1 정의(계약 §7 그대로, 구현 위치 = application)

```text
token(s)          = <UTF-8 byte length of s, 10진수> ":" s          // 길이·bytes 모두 Utf8Text.encode(엄격, 치환 금지)
canonicalRequestV1 = token("paycore.create-payment-request") + token("1")
                   + 각 필드(merchantAccountId, merchantSiteId, merchantOrderId, amount, currency, paymentMethod, orderName 순서)
                     token(fieldName) + (값 있음: "1" + token(value) | 없음: "0")
requestFingerprint = "r1:" + lowercase hex(SHA-256(Utf8Text.encode(canonicalRequestV1)))     // 67자
```

- amount = `Long.toString`(부호·선행 0 없음 — 파서가 그 외 표기를 이미 거절). 필수 필드는 항상 "1" 분기. 지금 선택 필드는 orderName 하나.
- JSON 원문 bytes·공백·필드 순서는 영향 없음(파싱 뒤 값으로 계산). 헤더·environment·인증 MID는 넣지 않는다(멱등 범위가 이미 포함).
- 구현: `payment.application.RequestFingerprints`(`fun compute(content, version)`; v1 계산은 동결). JDK `MessageDigest`·`HexFormat`만.

### 6.2 golden vector(Python 3 `hashlib`로 독립 계산, 2026-10-14. Demo 값 — 운영 MID·Site 아님)

공통: merchantAccountId=`mid_01HZY3K8Q4M2N6P8R0T2V4X6Z8`, merchantSiteId=`site_01HZY3K8Q4M2N6P8R0T2V4X6Z9`, merchantOrderId=`order-0001`, paymentMethod=`CARD`.

| # | 차이 | amount/currency | orderName | canonical UTF-8 bytes | requestFingerprint |
|---|---|---|---|---|---|
| A | orderName 있음(ASCII) | 10000/KRW | `Demo T-shirt` | 256 | `r1:cc6b69c4f397c03694e0e2e90e8f506be1ad2b616006a949ae6e9425f31b24fc` |
| B | orderName **없음** | 10000/KRW | (없음 → `9:orderName0`) | 241 | `r1:66a20aca332812e0bd433cbbfd8a3a81ef24da3e0e8b8bda21ab4dc3b7190a49` |
| C | 비ASCII(한글 + 보충 문자 U+1F600) | 1050/USD | `데모 상품 😀`(18 byte) | 261 | `r1:efb1a382999bc6668f4bef43e953ff84207ede2f200272916bcd0adcc65d36be` |
| D | A와 orderName 끝 공백만 다름 | 10000/KRW | `Demo T-shirt `(13 byte) | 257 | `r1:4e2241a4a773fbf929304cb866401d5c081f0ab764e21e13729c3918c4bcf254` |
| E | amount = Long.MAX | 9223372036854775807/KRW | `x` | 259 | `r1:549bbee3a8f6e8f8ebfc69f32427a81da05d4bd8e2971f5cdd77cd2ebdf254d8` |

A의 canonical 원문(검토용): `30:paycore.create-payment-request1:117:merchantAccountId130:mid_01HZY3K8Q4M2N6P8R0T2V4X6Z814:merchantSiteId131:site_01HZY3K8Q4M2N6P8R0T2V4X6Z915:merchantOrderId110:order-00016:amount15:100008:currency13:KRW13:paymentMethod14:CARD9:orderName112:Demo T-shirt`

- 2단계: golden 파일 `apps/payment-api/src/test/resources/contracts/create-payment-request-r1.golden.json`을 `harness/tests/test_create_payment_request_golden.py`(Python 표준 라이브러리만)가 생성하고, HARNESS-SELFTEST가 매번 다시 만들어 바이트 비교한다(MS명세 §15-10과 같은 독립성 방식). Kotlin 시험은 이 파일을 읽어 canonical 원문·hash를 모두 비교한다(값을 코드에 복제하지 않음). HARNESS-SELFTEST `minTests` 하한은 새 Python 시험 수만큼 올린다(`harness/commands.json`, module-registry 시험이 하한 = 시험 수를 검사).
- 짝 없는 서로게이트가 든 content는 생성자에서 거부되고, 우회해 `compute`에 닿아도 `Utf8Text.encode`가 예외(치환 없음) — 시험으로 `"a\uD800"`과 `"a?"`가 같은 fingerprint가 될 수 없음을 확인.

### 6.3 intent fingerprint(v1)과의 관계

같은 키·다른 orderName → r1 다름 → 409 `IDEMPOTENCY_KEY_REUSED`. 다른 키·같은 주문·다른 orderName → v1 같음 → 활성 시도 재사용(200 `EXISTING_ACTIVE_PAYMENT`). 시험 쌍으로 두 fingerprint의 범위 차이를 고정한다(계약 §7 표의 "예" 행).

### 6.4 저장 버전으로 재계산 비교(AC 10, 계약 §6.4)

| 저장 값 | 서비스 동작 |
|---|---|
| 멱등 기록 `request_fingerprint` 접두 `r1` | 요청 content를 r1로 계산해 문자열 비교(같음 → 재생, 다름 → 409 KEY_REUSED) |
| 접두가 지원 밖(`r2` 등 — 새 버전 배포 뒤 롤백된 인스턴스) | **비교하지 않는다**. 503 `TEMPORARILY_UNAVAILABLE` + ERROR 로그 `UNSUPPORTED_REQUEST_FINGERPRINT_VERSION`, TX rollback(변화 없음). KEY_REUSED로 오분류하지 않는다(구현 결정, fail-closed) |
| control `fixed_intent_fingerprint` 접두 `v1` | `OrderIntent.fingerprint()`(v1)과 비교 |
| 접두가 지원 밖(`v2` 등) | 503 `TEMPORARILY_UNAVAILABLE` + ERROR 로그, 새 Payment 없음. 현재 `requireComparableIntentVersion`의 `check`(IllegalStateException → 500)를 분류 가능한 실패로 바꾼다 |

버전별 계산은 `when(version) { 1 -> v1 }` 분기 하나(L-9 "버전 지정 API"는 v2 도입 때 확장). 시험은 실제 MySQL에 `r2:`/`v2:` 행을 시험 전용 SQL로 넣어 같은 키·같은 주문 요청이 503·행 수 불변·KEY_REUSED/INTENT_CONFLICT 아님을 확인한다.

---

## 7. 오류 매핑(계약 §9 전체)

### 7.1 오류 본문(계약 §9 관례 + 구현 결정 필드)

`{"error":{"code":"…","message":"<코드별 고정 문구>","retryable":bool,"requestId":"…","field":"…?","reason":"…?","referencePaymentId":"…?"}}`

- `field`: 400에서 schema의 알려진 필드 이름 또는 `Idempotency-Key`만(모르는 필드 이름은 echo 안 함).
- `reason`: 422에서만, 고정 4종(`CURRENCY_NOT_ENABLED`·`METHOD_NOT_ENABLED`·`CONTRACT_NOT_EFFECTIVE`·`MERCHANT_SUSPENDED`). 503 `ELIGIBILITY_UNAVAILABLE`의 원인(SNAPSHOT_MISSING 등)은 응답에 넣지 않고 로그·지표에만(내부 상태, 계약 §9.1).
- `referencePaymentId`: 409 업무 거절 4종에서만(같은 인증 MID 자기 주문).

### 7.2 표

| 경우 | 응용 결과 | HTTP | code | retryable | Retry-After | Idempotent-Replayed |
|---|---|---|---|---|---|---|
| 새 주문·안전 종료 뒤 새 키 | Accepted(CREATED, replayed=false) | 201 | — | — | 없음 | false |
| 같은 키·같은 내용 | Accepted/Rejected(replayed=true) | 원 결과와 같은 본문, 성공은 200 | 원 결과 | 원 결과 | 없음 | true |
| 다른 키·같은 주문·같은 고정 입력·활성 | Accepted(EXISTING_ACTIVE_PAYMENT) | 200 | — | — | 없음 | false |
| 같은 키·다른 내용 | IdempotencyKeyReused | 409 | `IDEMPOTENCY_KEY_REUSED` | false | 없음 | 없음(결과 아님) |
| 고정 입력 다름 | Rejected(FIXED_INTENT_CONFLICT) | 409 | `ORDER_INTENT_CONFLICT` | false | 없음 | false/true |
| incident hold | Rejected(INCIDENT_HOLD) | 409 | `ORDER_ON_HOLD` + referencePaymentId(D-CPD-004) | false | 없음 | false/true |
| 과거 승인 | Rejected(AUTHORIZED_NEW_ORDER_REQUIRED) | 409 | `ORDER_ALREADY_AUTHORIZED` | false | 없음 | false/true |
| 종료 근거 불충분 | Rejected(TERMINATION_EVIDENCE_INSUFFICIENT) | 409 | `ORDER_RESULT_UNCONFIRMED` | false | 없음 | false/true |
| 형식·필드 | web 검증 | 400 | `INVALID_REQUEST`/`INVALID_AMOUNT`/`INVALID_CURRENCY`/`INVALID_IDEMPOTENCY_KEY` | false | 없음 | 없음 |
| 인증 없음·폐기 | 필터 | 401 | `UNAUTHENTICATED` | false | 없음 | 없음 |
| body MID ≠ 인증 MID·operation 없음 | web | 403 | `MERCHANT_SCOPE_MISMATCH` | false | 없음 | 없음 |
| Site 없음·다른 MID·Assignment 무효 | EligibilityRefused(NotFoundOrForbidden) | 404 | `MERCHANT_SITE_NOT_FOUND` | false | 없음 | 없음 |
| 미활성·계약 밖·중지 | EligibilityRefused(NotEligible) | 422 | `PAYMENT_NOT_ELIGIBLE` + reason | false | 없음 | 없음 |
| Snapshot 누락(미전파)·만료·미지원 | EligibilityRefused(Unavailable) | 503 | `ELIGIBILITY_UNAVAILABLE` | true | **있음** | 없음 |
| 교착 재시도 소진·잠금 timeout(같은 키 대기 아님)·DB 일시 오류·연결 끊김·IdentifierCollision 반복·저장 버전 미지원·내부 경합 재시도 소진 | TemporarilyUnavailable | 503 | `TEMPORARILY_UNAVAILABLE` | true | **있음** | 없음 |
| commit 결과 불명확(afterCommit 예외·commit 중 끊김) | OutcomeUnknown | 503 | `TEMPORARILY_UNAVAILABLE`(문구: "같은 Idempotency-Key로 다시 요청해 결과를 확인") | true | **있음** | 없음 |
| 같은 키 처리 중(멱등 예약 대기 timeout, 인접 갭 포함) | RequestInProgress | 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | true | **있음** | 없음 |
| StaleSlot·PaymentIdReuse·OptimisticConflict 등 내부 경합 | 서비스가 새 TX로 재판정(최대 3회) → 위 행 중 하나 / 소진 시 503 | — | `*_CONFLICT` **쓰지 않음**(계약 초안 CP-10) | | | |
| 예상 밖 결함(check 실패 등, rollback됨) | RuntimeException | 500 | `INTERNAL_ERROR` | false | 없음 | 없음 |
| 없는 경로·방법 | 라우팅 | 404/405 | `NOT_FOUND`/`METHOD_NOT_ALLOWED` | false | 없음 | 없음 |

`INTERNAL_ERROR`·`NOT_FOUND`·`METHOD_NOT_ALLOWED`는 계약 §9 표 밖 라우팅·결함용 코드로 추가한다(구현 결정, 업무 의미 없음 — 계약 §14.2에 기록). 500은 TX가 rollback된 결함(commit 단계 예외는 OutcomeUnknown으로 이미 분리)이라 retryable=false — 같은 키 재시도는 해롭지 않지만 같은 결함을 반복한다.

### 7.3 IDEMPOTENCY_REQUEST_IN_PROGRESS와 lock wait timeout 분류(L-1·L-4, AC 12·16)

- 분류 기준 = **어느 Port에서 1205가 났는가**(기존 서비스: `reserveOrLock`의 `LockTimeout`만 `RequestInProgress`):
  - 멱등 예약(`INSERT … ON DUPLICATE KEY UPDATE` + `FOR UPDATE`)의 대기 → 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`. **같은 키의 미커밋 TX뿐 아니라 인접 범위 갭 잠금 대기도 포함한다**: IODKU가 기존 키에서 중복 경로를 타면 그 인덱스 레코드에 next-key 잠금을 잡고(READ COMMITTED에서도 중복 검사 잠금은 남는다), 그 앞 갭에 다른 키를 넣는 TX가 기다릴 수 있다. 이 경우도 409·retryable·저장 없음이라 금융 의미는 같고, 응답만 "다른 요청과 경합"을 뜻한다. 계약 §9 해당 행과 `CreatePaymentFailure.RequestInProgress` KDoc에 이 문장을 명시한다(S0).
  - head `FOR SHARE`(§3.7)·control·payment 대기 → 503 `TEMPORARILY_UNAVAILABLE`.
- TX rollback 보장: MySQL 기본 `innodb_rollback_on_timeout=OFF`는 **문장만** 되돌린다. `LocalTransactions`가 예외 시 TX 전체를 rollback하므로 PENDING 멱등 행이 남지 않는다. 시험은 timeout 뒤 **다른 연결**에서 행 0, 같은 연결(풀 반환 뒤 재사용)이 열린 TX 없음(`INNODB_TRX`가 아니라 `performance_schema`/`@@in_transaction` 확인)을 본다.
- 시험(`LockWaitClassificationSpec`, 실제 MySQL, 시험 lock wait 8초): (a) TX1이 키 K 예약 뒤 멈춤 → TX2 같은 K → 409, (b) TX1이 기존 키 K2에 IODKU 중복 경로로 next-key 잠금 → TX2가 K2 앞 갭의 새 키 K1 → 409(인접 갭 재현, `data_lock_waits`로 갭 대기 확인), (c) TX1이 control 잠금 → TX2 다른 키·같은 주문 → 503, (d) Inbox가 head X를 쥠 → CreatePayment → 503, 모두 Retry-After 있음·행 변화 없음.

### 7.4 Retry-After 규칙(MS 이월, AC 23)

- 붙는 응답: 503 `ELIGIBILITY_UNAVAILABLE`, 503 `TEMPORARILY_UNAVAILABLE`(OutcomeUnknown 포함), 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`. 그 밖(400·401·403·404·409 업무 거절·422·500)에는 붙이지 않는다 — 같은 키 재시도로 결과가 바뀌지 않는 응답에 재시도를 권하지 않는다.
- 값: delta-seconds 정수(HTTP-date 아님). 설정 `paycore.payment.api.retry-after.eligibility-unavailable`·`.temporarily-unavailable`·`.request-in-progress`(Duration, 1초 이상·1시간 이하, 초 미만 올림). **운영 기본값 없음**(없으면 기동 실패 — MS명세 §8 관례와 같음). 시험 값은 test classpath `config/application.yml`의 Demo 값.
- 의미: 재시도 권고일 뿐 lease·Snapshot 전파 시간을 약속하지 않는다. 같은 `Idempotency-Key`로 재시도하라는 고정 문구를 본문에 둔다(새 키 금지 — OutcomeUnknown에서 특히).

### 7.5 입력·존재 비노출(계약 §9.1)

- 응답 본문·헤더에 merchantOrderId·금액·키·MID·Site 값을 echo하지 않는다(성공 응답의 계약 echo 필드 제외). 고정 문구만.
- 404는 Site 없음/다른 Merchant/Assignment 무효를 구분하지 않는다. 401은 원인을 구분하지 않는다. 403은 다른 MID 존재를 말하지 않는다.
- 내부 예외 메시지·stack·SQL·Snapshot 버전·정책 값·기관 코드 없음. 시험(`ErrorBodyLeakSpec`): 각 오류 응답 본문에 요청에 넣은 표식 문자열(merchantOrderId·Site·MID·키 값)이 없음을 바이트 검색으로 확인.

---

## 8. /internal 경로 격리(MS 이월, AC 22)

- **구현 결정: 별도 포트.** 내장 Tomcat에 두 번째 connector(설정 `paycore.payment.internal-port`, 운영 기본값 없음 — 없으면 기동 실패)를 추가하고, 최상위 필터 `ConnectorSegregationFilter`가 요청의 local port로 가른다:
  - 공개 포트: `/internal/**` → 404(없는 경로와 같은 응답 — 존재 비노출). `/v1/**`·`/actuator/health`만 라우팅.
  - 내부 포트: `/internal/**`만, 그 밖(`/v1/**` 포함) → 404.
- 내부 포트 요청은 Spring Security에서 permitAll(인증은 기존 봉투 HMAC, D-MS-004) — 가맹점 Credential로 내부 경로를 열 수 없고, 내부 서명으로 공개 API를 열 수 없다.
- 네트워크 노출(운영 방화벽·LB가 공개 포트만 외부에 노출, 내부 포트는 MASTER 망에서만, mTLS)은 운영 배포 작업 — D-MS-004의 "운영 mTLS는 LIVE 전 별도"와 같은 범주로 배포 문서에 적는다.
- 경로만 막는 대안(같은 포트 + 필터) 대신 포트를 쓰는 이유: LB/프록시 경로 규칙 실수 하나로 내부 endpoint가 외부에 열리는 단일 실패점을 없앤다(심층 방어). 되돌리기 쉽다(설정·필터).
- 시험(`InternalPortIsolationSpec`, `webEnvironment=RANDOM_PORT` + 내부 포트 0 → 실제 포트 조회): 공개 포트 `POST /internal/v1/entitlement-snapshots`(올바른 서명 포함) → 404·Inbox 행 0, 내부 포트 `POST /v1/payments`(유효 Demo Credential) → 404·Payment 0, 내부 포트 Inbox 정상 200, 공개 포트 `/actuator/health` 200(세부 없음).

---

## 9. 응답 유실·재시작·커밋 불명확·30일·거절 재생

### 9.1 응답 유실·afterCommit 예외(AC 2·17, L-4)

- `ResponseLossReplayApiSpec`: 실서버에 HTTP 요청 → 서버 커밋 → 클라이언트가 응답을 읽지 않고 연결을 끊음(소켓 close) → 같은 키·같은 본문 재요청 → 200·`Idempotent-Replayed: true`·첫 paymentId와 같음, DB Payment 1·control 1·멱등 1·Outbox 1.
- `CommitOutcomeUnknownApiSpec`: 시험 전용 hook이 TX에 `TransactionSynchronization.afterCommit`에서 예외를 던지는 동기화를 등록(실제 commit은 완료) → `LocalTransactions`가 commit 단계 예외로 보고 `OutcomeUnknown` → 503 `TEMPORARILY_UNAVAILABLE`·Retry-After → 같은 키 재요청 → 200 replayed=true·행 1. 대조: beforeCommit 예외(실제 rollback) → 503 → 같은 키 → 201 replayed=false·행 1(기존 CP-DB 시험과 같은 결과를 API 수준에서).

### 9.2 재시작(AC 2)

`RestartReplaySpec`: 같은 MySQL 컨테이너에 Spring 컨텍스트 A(실서버)로 생성 → 컨텍스트 A 종료(풀·스레드 정리) → 새 컨텍스트 B 기동 → 같은 키·같은 본문 → 200 replayed, 같은 paymentId·paymentCreatedAt·본문 바이트. 추가: A에서 커밋 직후 응답 전에 컨텍스트를 강제 종료(hook에서 종료 신호) → B에서 같은 키 → 재생. 범위 관찰 §19-6: 프로세스 kill -9 수준은 아니다(같은 JVM 안 컨텍스트 재생성) — DB가 유일한 권위 상태이고 메모리 상태가 없어 같은 의미라는 근거를 기록한다.

### 9.3 30일 경과(D-CPC-004, AC 11)

시험 시계(`PaymentClock` 대역)를 움직여 실제 MySQL에서:

| 시나리오 | 기대 |
|---|---|
| 생성 t0 → t0+30일−1ms 같은 키·같은 내용 | 200 재생 |
| t0+30일 정각 같은 키·같은 내용(원 Payment REQUESTED) | 새 요청 → 200 `EXISTING_ACTIVE_PAYMENT`, 같은 paymentId, Payment 1(동시 활성 없음), 멱등 기록 1행(같은 행 갱신, `request_record_id` 교체) |
| t0+30일, 원 Payment AUTHORIZING/UNKNOWN | 200 EXISTING_ACTIVE(새 Payment 없음 → 이중 승인 경로 없음) |
| t0+30일, 원 Payment AUTHORIZED | 409 `ORDER_ALREADY_AUTHORIZED` |
| t0+30일, 원 Payment FAILED + D-CPD-002 충분 근거 | 201 CREATED generation 2(계약 §8의 정당한 두 번째 Payment) |
| t0+30일, 원 Payment FAILED 근거 불충분 | 409 `ORDER_RESULT_UNCONFIRMED` |
| t0+30일 같은 키·**다른 내용** | 새 요청으로 처리(KEY_REUSED 아님 — D-CPC-004 "새 요청"), 주문 판정 규칙 그대로 |
| t0+30일 같은 키 동시 8건 | 갱신 1, 모두 같은 결과, Payment 증가 없음 |

### 9.4 저장된 거절 재생(D-CPC-007)

`RejectionReplayApiSpec`: 4종 거절 각각 → 같은 키 재요청 → 같은 409 code·referencePaymentId·`Idempotent-Replayed: true`. 그 사이 주문 상태가 바뀌어도(예: 근거 불충분 → 충분 근거 추가) 30일 안에는 저장 거절 재생, **새 키**로는 새 판정(201 gen2). eligibility 거절(404/422/503)·400·401·403은 저장하지 않으므로 같은 키 재요청이 다시 평가된다(시험).

### 9.5 CP 초안 시나리오 API 수준(AC 14)

| ID | API 시험 |
|---|---|
| CP-01 | 201, Payment REQUESTED 1·control 1(generation 1)·멱등 SUCCESS 1·Outbox PaymentRequested 1, 승인 사실·기관 호출 0(payment-api에 외부 기관 client bean 없음 — 컨텍스트 검사) |
| CP-02 | amount 0·음수·소수·지수·문자열·Long 초과 400 `INVALID_AMOUNT`, currency 형식 밖 400 `INVALID_CURRENCY`, 형식은 맞지만 미활성 통화 422 `CURRENCY_NOT_ENABLED`, `paymentMethod` 미지원 400, 미개통 422 `METHOD_NOT_ENABLED` — 모두 행 0 |
| CP-05 | 현재 REQUESTED/AUTHORIZING/UNKNOWN + 새 키 같은 입력 → 200 EXISTING_ACTIVE(같은 paymentId), AUTHORIZED → 409 ORDER_ALREADY_AUTHORIZED. "전액 반환 후"는 아직 반환 모델이 없어 control의 승인 이력(`authorized_payment_id`)으로 대신 확인(범위 관찰 §19-7) |
| CP-06 | FAILED/EXPIRED + 충분 근거 + 새 키 → 201 gen2, 원 키 → 원 결과 재생, 근거 없음/timeout/NOT_FOUND/lease 만료 → 409 ORDER_RESULT_UNCONFIRMED |
| CP-07 | 다른 MID body 403, 다른 환경 Credential → 다른 범위, 다른 Merchant Site 404, Snapshot 만료·미전파 503+Retry-After, Credential 폐기 401(S5), 오류 본문 민감값 없음 |
| CP-12 | `Order-1`/`order-1`, `ord.1`/`ord.1 `(후자는 형식 밖 400), 대소문자 다른 Site → 다른 고정 입력(INTENT_CONFLICT 또는 404) — API 판정 = DB 키 동일성(행 비교) |

---

## 10. PaymentRequested·식별자(AC 15)

- `PaymentRequestedEnvelopeApiSpec`(API로 생성한 뒤 Outbox 행): eventType `PaymentRequested`, schemaVersion 1, eventCategory `INTEGRATION_NON_FINANCIAL`, producer `payment-api/payment`, aggregateType/Id `Payment`/paymentId, aggregateVersion 0, occurredAt = paymentCreatedAt, correlationId = 응답 `PayCore-Request-Id`, causationId = 멱등 기록 ID, effectKey `PaymentRequested:{paymentId}`; payload 키 집합이 정확히 계약 §10 12개(paymentKey·orderName·카드정보 없음 — 시험이 키 집합 비교 + paymentKey·orderName 값 문자열이 payload 원문에 없음 확인), amount JSON 정수(2^53+1 값 그대로). EXISTING_ACTIVE·재생·거절·eligibility 거절은 Outbox 0.
- `PaymentIdentifierGeneratorSpec`: 형식(`^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$`, `^pk_[A-Za-z0-9_-]{43}$`, UUID), 10만 건 중복 0, 대역 시험으로 충돌 재시도(§3.5).

---

## 11. 로깅·설정 이월

### 11.1 오류 처리기 로그(n-3, AC 21)

- web `@RestControllerAdvice`(+ 인증 실패 entry point)는 **예외 메시지 원문을 로그에 남기지 않는다.** 남기는 필드(구조화, key=value): `operation=CreatePayment`, `correlationId`, `merchantRequestId`(정제된 경우만), `result`(오류 code), `failure`(분류: `LOCK_TIMEOUT`·`DEADLOCK`·`DB_UNAVAILABLE`·`OUTCOME_UNKNOWN`·`IDENTIFIER_COLLISION`·`UNSUPPORTED_FINGERPRINT_VERSION`·`DEFECT` 등), `vendorCode`, `sqlState`, `constraint`(StoreConstraint 이름), 결함이면 예외 **클래스 이름 사슬**과 각 원인의 첫 stack frame(메시지 없음).
- vendorCode·SQLState는 web이 `java.sql`을 볼 수 없으므로(ArchUnit 영속 기술 위치) persistence adapter의 `PersistenceFailures`가 `PersistenceFailure`에 `diagnostic: StoreDiagnostic(vendorCode, sqlState)`로 담아 올린다(값 없는 숫자·코드만, 구현 결정).
- 수준: 503·500·OutcomeUnknown → ERROR/WARN, 409 IN_PROGRESS → INFO, 400/401/403/404/409 업무 → INFO 이하(공격성 반복은 지표로).
- 시험(`ErrorHandlerLoggingSpec`, Logback ListAppender — master `CapturedLogs`와 같은 방식을 payment 시험 지원에 둠): 각 실패 주입 경로에서 로그 줄에 입력 표식 문자열·예외 메시지 원문이 없고 허용 필드가 있음.

### 11.2 Hibernate JDBC 오류 로거 OFF + 1062 캡처(n-4, AC 20)

- payment-api `application.yml`: `logging.level.org.hibernate.orm.jdbc.error: "OFF"`, `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper: "OFF"`(master-api와 같음). `MainConfigurationSpec`에 원문 고정 행.
- `DuplicateKeyLogLeakSpec`(실제 MySQL): (a) 같은 멱등키 최초 경합(IODKU라 1062가 안 나는 경로 + 강제 1062 경로), (b) 주문 키 control 최초 insert 경합, (c) 생성기 대역으로 기존 payment_key 재사용 → 1062 — 로그 전체에 키 값(멱등키·merchantOrderId·paymentKey·MID)이 없음.

### 11.3 세션 time_zone(L-10, AC 18)

- persistence adapter `PaymentDbSessionTimeZoneCheck`: (1) 기동 시(SmartInitializingSingleton) 풀 연결로 `SELECT @@session.time_zone` = `+00:00` 아니면 기동 실패(값만 로그), (2) `HealthIndicator` `paymentDbSessionTimeZone` — 같은 검사, 다르면 DOWN(세부는 health show-details=never라 외부에 안 보임).
- datasource URL에 시간대 관련 파라미터(`connectionTimeZone`·`serverTimezone`·`forceConnectionTimeZoneToSession`·`preserveInstants`·`sessionVariables`에 time_zone)가 있으면 기동 실패 — driver 속성(application.yml)이 유일한 출처(URL이 driver 속성을 덮으면 비UTC 저장 위험, 관례 §9.1).
- 배포 규칙 문서: `docs/repository/TECH-BASELINE.md`(또는 이 명세 §14.2)에 "`PAYCORE_PAYMENT_DB_URL`에 시간대 파라미터 금지, 세션 UTC는 driver 속성이 강제, 기동 검사가 막음"을 적는다. 루트 `.env.example` 주석은 허용 경로 밖(범위 관찰 §19-4).
- 시험(`DbSessionTimeZoneSpec`): URL에 `serverTimezone=Asia/Seoul` → 기동 실패, `forceConnectionTimeZoneToSession=false` 속성 + 서버 기본 시간대 비UTC 컨테이너 → 기동 실패, 정상 → health UP.

### 11.4 m-7(jOOQ 시각 조회)

이 작업은 jOOQ 조회를 추가하지 않는다(GetPayment 이월, §4.6) → **해당 없음**. 관찰(Reviewer 참고, 변경 없음): 기존 `JooqEntitlementFreshnessReader`는 시각 컬럼을 읽지 않고(count·`TIMESTAMPDIFF`) UTC `LocalDateTime`을 **파라미터로 바인딩**한다 — §9.1 정정은 읽기 방향만 다루므로, 바인딩 방향의 비UTC JVM 증거가 있는지 MS 시험에서 확인할 가치가 있다(이 작업 범위 밖).

---

## 12. Fixture Monkey(AC 4)

- 이미 도입됨(CP-DB-001): `fixture-monkey-kotlin` testImplementation, `RandomCreatePaymentFixture`(seed 고정, 공식 생성자로 정상 값), `verifyRuntimeHasNoTestTools`(runtime classpath에 시험 도구 없음 — check 의존), `verifyNoInstancio`. 이 작업은 의존성을 새로 추가하지 않는다.
- 추가: `RandomCreatePaymentRequestFixture`(src/test) — Fixture Monkey seed를 **공식 규칙(§4.3·§4.4)을 만족하는 JSON 본문**으로 바꾼다(금액 1..Demo 상한, 통화 KRW/USD Demo, 주문명 허용 code point만, 선택 필드 있음/없음). 사용처: 대량 API 시험(무작위 40건 → 각 201 → 각 재생 200 같은 본문, r1은 Kotlin 계산과 저장 값 일치). 핵심 규칙 시험(오류 매핑·경계)은 명시 Fixture(`CreatePaymentRequests.valid()`, `InvalidCreatePaymentRequests.amountWithExponent()` 등 — 의도가 이름에 드러나는 Invalid Fixture)를 쓴다.
- 유입 검사: 위 Gradle 검사 + ArchUnit(main import에 `com.navercorp.fixturemonkey` 없음 — 기존 규칙이 domain/application만 보면 web·전체로 확장하는 규칙 1개 추가, 위반 fixture 포함).

---

## 13. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거(시험) |
|---|---|---|
| Financial Consistency | 금액 minor Long 정확 파싱(부동소수점 없음), API 금액 = 저장 금액 = 이벤트 금액, 주문당 활성 Payment 하나 유지(30일·응답 유실·재시작 포함), 금융 효과 없음(승인 사실 0) | `StrictJsonParserSpec`(Long.MAX·2^53+1), CP-01/05/06, `IdempotencyExpiryApiSpec`(Payment 수 불변) |
| Transaction Safety | 응답은 commit 뒤, 외부 호출 없음, 인증 읽기 TX 밖, 저장 버전 미지원·eligibility 기술 실패도 전체 rollback, commit 불명확은 OutcomeUnknown | `CommitOutcomeUnknownApiSpec`, `LockWaitClassificationSpec`(TX 전체 rollback 확인), G-1 시험 |
| Idempotency | r1 golden·버전별 재계산, 같은 키 재생(현재 권한 재확인), 거절 재생, 30일 경계, IdentifierCollision 재시도가 같은 키 결과 1개 | §6 golden, `ReplayAuthorityApiSpec`, `RejectionReplayApiSpec`, `FingerprintVersionDispatchSpec` |
| Concurrency Control | 기존 DB 직렬화 그대로 + HTTP 수준 동시 8건(같은 키·다른 키 같은 주문), lock wait 분류, head FOR SHARE timeout 분류 | 실서버 다중 HTTP 클라이언트 + `data_lock_waits` 관찰 |
| Recoverability | 응답 유실·재시작·commit 불명확 → 같은 키로 수렴, Retry-After로 같은 키 재시도 유도, 새 키 금지 문구 | §9.1·§9.2 시험 |
| Reconciliation | **해당 없음**(금융 효과 없음, PaymentRequested는 비금융). 후속: AUTH-001 이후 기관 결과 대사. Outbox Relay 후 이벤트 누락 대사는 Relay 작업 | 이유 기록 |
| Auditability | 요청 correlationId = 이벤트 correlationId, causationId = 멱등 기록, eligibility snapshot 버전 보존(기존), 생성 행위자(credential_id) 기록은 S5(Q-CPA-01) | `PaymentRequestedEnvelopeApiSpec`, S5 행위자 컬럼 시험 |
| Observability | 결과 counter `paycore.payment.create.results{result}`(CREATED·EXISTING·REPLAYED·REJECTED_<code>·ELIGIBILITY_<kind>·FAILURE_<kind> — MID·paymentId label 없음), 인증 실패 counter, 구조화 WARN/ERROR(n-3), http.server.requests(actuator 기본). Kafka/Redis 없이 DB Outbox가 전달 증거(ADR-001) | 장애 주입 시 counter 값·로그 줄 정확 일치, 민감값 없음 |

---

## 14. JPA·jOOQ·BOM(이번 변경에 연결)

### 14.1 JPA 선택(역할 → 예 → 이유 → 주의)

- **OSIV off + DTO 응답.** 역할: 컨트롤러는 use case가 commit 뒤 돌려준 `CreatePaymentResult`(값 객체)만 JSON으로 바꾼다. 예: 재생 응답의 Payment 값은 `present()`가 TX 안에서 `findCreationRecord`로 읽어 값 객체로 복사한다. 이유: 요청 처리 중 영속성 컨텍스트가 열려 있지 않아 web에서 LAZY 로딩·Entity 직렬화가 구조적으로 불가능하다. 주의: 응답에 새 필드를 넣으려면 use case 결과에 넣어야 하고 Entity를 web으로 넘기지 않는다(ArchUnit web → persistence 금지).
- **flush와 commit, 그리고 afterCommit.** 역할: Port의 flush가 제약 위반을 Port 안에서 드러내고, commit은 그 뒤 한 번. afterCommit 콜백 예외는 이미 커밋된 뒤라 rollback이 아니다. 예: `CommitOutcomeUnknownApiSpec` — afterCommit 예외인데 DB에는 행이 있고 같은 키가 재생된다. 이유: "응답 실패 = 저장 실패"로 가정하면 새 키 재시도가 이중 결제 시도를 만든다. 주의: commit 단계 예외는 모두 OutcomeUnknown(503, 같은 키 확인)으로 다룬다.
- **`PESSIMISTIC_READ`(FOR SHARE) timeout.** 역할: eligibility가 head를 공유 잠금으로 읽는다. 예: Inbox 적용 중 생성 요청이 기다리다 1205. 이유: 그 timeout은 같은 키 경합이 아니므로 409 IN_PROGRESS가 아니라 503이어야 한다(G-1). 주의: 1205는 문장만 되돌리므로 TX 전체 rollback을 `LocalTransactions`가 보장한다.
- **(S5, 답 뒤) Credential 투영 Entity.** 역할: `merchant_api_credential` 행을 읽기 전용으로 조회(runtime SELECT만). 이유: 검증값(hash)만 두고 원문 비밀을 저장하지 않는다(A05). 주의: Entity equals/hashCode는 credential_id만, toString에 hash 없음, 2차 캐시·쿼리 캐시 없음(폐기 즉시성).
- 새 JPA Entity·migration은 S5 외에 없다. 기존 영속 관례(§9)를 그대로 쓴다.

### 14.2 jOOQ(CQRS)

이 작업의 경로는 모두 Command(쓰기·판정)라 JPA + 잠금 읽기만 쓴다. jOOQ 조회 adapter는 추가하지 않는다 — 조회(GetPayment·가맹점 거래 목록)는 별도 작업에서 `adapter.query` jOOQ DTO 조회로 만든다(권한 범위 MID·environment 강제, 시각은 `DATE_FORMAT` 문자열 — 관례 §9.1). jOOQ로 쓰지 않는다(`queryAdapterDoesNotWriteWithJooq`).

### 14.3 BOM·의존성

- 추가: `spring-boot-starter-security`(runtime), `spring-boot-starter-security-test`(test) — 둘 다 Boot 4.1.1 BOM 관리, 카탈로그 기존 항목(버전 숫자 추가 없음). Logback ListAppender는 Boot 기본 logging에 포함.
- 검증: GRADLE-DEPS-PAYMENT-API resolved tree 비교, CVE-SCAN, `verifyRuntimeHasNoTestTools`. Kafka/Redis 도입 없음 — 금융 안전성(멱등·활성 하나·Outbox 원자성)은 DB 제약·로컬 TX가 보장하며 브로커·캐시가 이를 대체하지 않는다(ADR-001). Rate limit이 필요해져도 Redis 장애 시 fail-open으로 인증·권한을 완화하지 않는다.

---

## 15. 계획 파일 목록

| 구분 | 경로(`apps/payment-api/src/…` 기준, 가역적 이름) | 단계 |
|---|---|---|
| domain | `main/kotlin/…/payment/domain/OrderPaymentControl.kt`(startAuthorization, check private) | S1 |
| application | `…/payment/application/provided/SubmitCreatePaymentUseCase.kt`, `CreatePaymentRequestContent.kt`, `CreatePaymentValues.kt`(command 변경), `…/application/RequestFingerprints.kt`, `SubmitCreatePaymentService.kt`, `SecureRandomPaymentIdentifierGenerator.kt`, `SystemPaymentClock.kt`, `CreatePaymentService.kt`(버전 분기·G-1 분류), `…/application/required/PaymentIdentifierGenerator.kt`, `PaymentClock.kt`, `PersistenceFailure.kt`(StoreDiagnostic) | S2 |
| entitlement | `…/entitlement/application/provided/EntitlementUseCases.kt`(EntitlementEvaluationFailure), `EntitlementServices.kt`, `…/payment/adapter/integration/EntitlementEligibilityAdapter.kt` | S2 |
| web | `…/payment/adapter/web/CreatePaymentController.kt`, `StrictCreatePaymentRequestParser.kt`, `CreatePaymentResponses.kt`, `ApiErrorHandler.kt`, `RetryAfterSettings.kt` | S3–S4 |
| 보안·포트 | 공통 설정 패키지(`com.paycore.payment.config` — ArchUnit 공통 설정 패키지로 등록): `ApiSecurityConfig.kt`, `InternalConnectorConfig.kt`, `ConnectorSegregationFilter.kt`, `MerchantAuthenticationFilter.kt` | S4 |
| 인증 | `…/payment/application/provided/AuthenticateMerchantUseCase.kt`, `…/application/required/MerchantCredentialStore.kt`, persistence 구현·V5/V6 migration | S5(Q-CPA-01) |
| persistence | `PersistenceFailures.kt`(diagnostic), `PaymentDbSessionTimeZoneCheck.kt` | S2, S7 |
| 설정 | `main/resources/application.yml`(로거 OFF, retry-after·internal-port 키 바인딩만 — 값 없음), `test/resources/config/application.yml`(Demo 값) | S4, S7 |
| 시험 | §1 표의 Spec들, `test/…/support/CapturedLogs.kt`, `TestMerchantAuthenticationConfig.kt`, `ApiTestClient.kt`, `RandomCreatePaymentRequestFixture.kt`, `CreatePaymentRequests.kt`, `test/resources/contracts/create-payment-request-r1.golden.json` | 각 단계 |
| test-support | `libs/test-support/…/PayCoreArchitectureRules.kt`(startAuthorization 호출 제한·Fixture Monkey main 금지 규칙 + meta-test fixture) | S1, S8 |
| harness | `harness/tests/test_create_payment_request_golden.py`, `harness/commands.json`(HARNESS-SELFTEST minTests 상향) | S2 |
| 문서 | 이 명세, 계약 §9 L-1 문구·§14.2 구현 범위, ARCHITECTURE-CONVENTIONS §11(CP-API-001 관례), TECH-BASELINE 배포 규칙(L-10) | S0, S9 |
| migration | **없음**(orderName VARCHAR(100) 유지). S5에서만 V5(Credential 투영·행위자 컬럼)·V6(권한), Q-CPA-01 답 뒤 | S5 |

---

## 16. 구현 단계(S0..S9)

| 단계 | 내용 | 막힘 | 시험(추정) |
|---|---|---|---|
| S0 | 계약 §9 IN_PROGRESS 문구(L-1)·라우팅/결함 코드 추가 기록, 관례 §11 골격 | 없음 | 0 |
| S1 | L-2 domain 연산 + ArchUnit 규칙·위반 fixture | 없음 | 6–8 |
| S2 | r1·golden(Python 생성·selftest)·버전 분기, command 변경, ID 생성기·시계, facade·충돌 재시도, G-1 분류, StoreDiagnostic | 없음 | 30–38 |
| S3 | 엄격 파서·필드 검증·응답·헤더·오류 매핑 전 표(시험용 인증기, MockMvc + 실 DB) | 없음 | 60–75 |
| S4 | Spring Security 체인·포트 격리·Retry-After 설정·오류 처리기 로그(n-3)·로거 OFF(n-4)·인증 필터 골격(시험용 인증기) | 없음(운영 인증 bean 제외) | 20–28 |
| S5 | 운영 Credential 검증(투영 표·검증기·폐기·행위자 기록) | **Q-CPA-01** | 20–30 |
| S6 | 실서버 통합: CP 시나리오·응답 유실·재시작·afterCommit·30일·거절 재생·권한 재확인·lock wait 분류·HTTP 동시성·1062 로그 | S5의 폐기 시험만 | 40–50 |
| S7 | L-10 세션 시간대 기동/health·URL 검사·배포 규칙 | 없음 | 5–7 |
| S8 | 관측(counter·로그)·Fixture Monkey 요청 Fixture·main 유입 규칙 | 없음 | 8–12 |
| S9 | 문서 갱신(관례 §11 JPA 설명, 계약 §14.2, 이 명세 편차 기록) | 없음 | 0 |

합계 추정 190–250건 증가. **운영 기동 주의**: S4에서 인증 필터가 운영 `MerchantAuthenticator` bean을 요구하면 S5 전까지 컨텍스트 smoke(`PaymentApiContextSmokeSpec`·`EntitlementDbTest`)가 깨진다. 처리안(구현 결정, Coordinator 확인): S4~S5 사이에는 공개 API 체인을 "모든 요청 401"로 두는 대신 **운영 구현 bean 없음 = 기동 실패**를 유지하고(MS명세 §5.4 "기본 구현 없음" 관례), S4·S5를 같은 검증 묶음으로 Verifier에 넘긴다. Q-CPA-01이 늦어지면 S4 직전까지(S0–S3, S7, S8 일부)만 먼저 검증한다.

Verifier: VERIFY-GATE(payment-api 포함)·HARNESS-SELFTEST(새 golden 시험)·TEST-SUMMARY·GRADLE-DEPS-PAYMENT-API(의존성 변경)·CVE-SCAN을 같은 source fingerprint로. 이 1단계 문서에는 실행 증거가 없다.

---

## 17. 구현 결정 목록(가역적, 질문 아님)

1. facade `SubmitCreatePaymentUseCase`(ID·시계·r1 입력·충돌 1회 재시도), TX는 기존 서비스 소유(§3.1).
2. command가 요청 content를 받고 서비스가 저장 버전으로 r1 계산(§3.3, §6.4). 지원 밖 저장 버전 → 503 + ERROR(오분류 금지).
3. paymentId `pay_`+ULID, paymentKey `pk_`+256bit base64url, UUID v4. 생성기 출력은 ASCII라도 domain 서로게이트 검사 유지(§3.4).
4. orderName = code point 1..100(DB VARCHAR(100) utf8mb4와 동일), Cc·U+2028/2029·bidi 제어 거절, migration 없음(§4.4).
5. 엄격 UTF-8 선디코딩 + Jackson 스트리밍 토큰 파서(데이터 바인딩 미사용), 본문 16 KiB(§4.2).
6. 400 첫 위반만 보고, field는 알려진 필드만(§4.3). Content-Type·Accept 위반도 400(오류 코드 집합 유지).
7. 응답 헤더 `PayCore-Request-Id` 항상, `Idempotent-Replayed` true/false 명시, `Location` 없음(§4.1·§4.5).
8. 422 본문에 고정 reason, 503 eligibility 원인은 비노출(§7.1).
9. 라우팅·결함 코드 `NOT_FOUND`·`METHOD_NOT_ALLOWED`·`INTERNAL_ERROR` 추가(§7.2).
10. Retry-After: 503 두 종류·409 IN_PROGRESS만, delta-seconds, 운영 기본값 없는 필수 설정(§7.4).
11. IN_PROGRESS = 멱등 예약 단계 1205(인접 갭 포함), 그 밖 1205 = 503(§7.3).
12. /internal 별도 포트 + connector 필터, 공개 포트 `/internal/**` 404(§8).
13. Spring Security stateless·CSRF off(헤더 인증 JSON API), 401 원인 비구분, operation 부족 = 403 MERCHANT_SCOPE_MISMATCH(§5.2 — Q-CPA-01 답에 따라 조정).
14. entitlement provided 실패 타입 → payment PersistenceFailure 매핑(G-1, §3.7).
15. 진단 필드 `StoreDiagnostic(vendorCode, sqlState)`를 persistence가 채움, web 로그는 클래스 이름·첫 frame만(§11.1).
16. 세션 시간대 기동 실패 + health DOWN + URL 시간대 파라미터 거부(§11.3).
17. GetPayment endpoint는 이 작업에서 만들지 않음(§4.6).

---

## 18. 질문 후보(Coordinator → 사용자)

### Q-CPA-01 가맹점 API Credential 방식(형식·전달·검증 데이터·폐기 전파) — **답변됨: D-CPA-001(권장안 채택, 2026-10-14)**

(아래는 1단계 질문 원문이다. 확정 내용은 §5.1.) 2단계에서 새 질문 후보는 없다(§20 끝).

- 질문: 가맹점 서버가 CreatePayment 등 공개 API를 호출할 때 어떤 Credential로 인증하고, PAYMENT는 무엇으로 검증하며, 폐기는 어떻게 전파하는가? 확정 근거는 "Credential이 MID·환경(TEST/LIVE)·operation을 결정(계약 §2, 허용 §4.1-1, D-MD-003), 단방향 검증값 저장(A05), 키 차단 우선 전파·무기한 캐시 금지(허용 §4.2, A05)"까지이고, 연동 방식·저장·전파 경로는 정해지지 않았다. Credential 발급·저장 작업도 없다.
- 권장안: **MID·환경별 비밀 API 키(Bearer)** —
  - 형식: `pc_test_sk_` / `pc_live_sk_` + keyId + `.` + 256bit 난수(base64url). 접두가 환경을 드러내 잘못된 환경 사용을 운영자가 알아보기 쉽고, keyId로 조회한다(전체 비교 없음).
  - 전달: `Authorization: Bearer <key>`(TLS 필수). 브라우저·결제창에 보내지 않는 서버용 비밀(A05).
  - 저장: 원문 비저장, keyId + SHA-256(256bit 난수라 느린 hash 불필요) 검증값, 상수 시간 비교. MID당 환경별 활성 키 최대 2개(교체 기간 동시 허용).
  - operation 범위: v1 키는 그 (MID, 환경)의 가맹점 공개 API 전체(지금은 CREATE_PAYMENT) — 세분 범위(조회 전용 키 등)는 후속 확장.
  - 발급·소유: Credential 원본은 MASTER(DOMAIN-DEFINITION), 검증 데이터(keyId·hash·MID·환경·operation·상태·기간)를 기존 서명 push 채널(D-MS-004)로 PAYMENT 투영에 보내고 PAYMENT는 로컬로만 검증(평상시 MASTER 동기 호출 없음, 허용 §4.2). 폐기는 차단 우선 전파·ACK(권리 Snapshot과 같은 규칙), 상한 = lease. 발급·전파는 새 작업(제안 MASTER-CREDENTIAL-001), CP-API-001은 PAYMENT 쪽 투영 표·검증기·필터와 Demo 시험 키까지 — **그 작업 전 운영 payment-api는 모든 가맹점 요청 401(fail-closed)**.
- 대안: (A) **요청 서명(HMAC)** — 키 비밀로 메서드·경로·시각·nonce·본문 hash에 서명, 재전송 방지 창 필요. 도청·재전송에 더 강하지만 가맹점 구현 부담·시계 의존·nonce 저장 필요. (B) **가맹점별 mTLS 클라이언트 인증서** — 강하지만 인증서 발급·갱신 운영 부담, 다수 가맹점에 무거움. (C) **OAuth2 client-credentials**(토큰 발급 서버) — 표준이지만 인가 서버 신규 구성. (D) PAYMENT가 Credential을 직접 발급·소유 — 빠르지만 Master 소유 구조(DOMAIN-DEFINITION)와 어긋난다.
- 영향: 인증 필터·401 응답(`WWW-Authenticate`), V5 migration(투영 표·생성 행위자 컬럼), CP-07 "키 폐기" 시험, Auditability(누가 생성했나), 가맹점 연동 문서, LIVE 허용 조건(허용 §4.1-1), Credential 작업 등록.
- 막히는 단계: **S5**(운영 검증기·migration·폐기 시험)와 그에 묶인 S4 운영 기동, AC 1·6의 실제 Credential 증거. 막히지 않는 것: S0–S3, S7, S8, S6 대부분(시험용 인증기로 같은 경로 시험).

(다른 질문 없음. 아래 항목은 확정 근거가 있거나 가역적이라 질문하지 않았다: orderName 길이 단위·거절 문자(§4.4 — 표시 필드, 키 아님), Retry-After 수치(재시도 권고·금융 의미 없음, 운영 설정), /internal 포트 분리(D-MS-004 채널 인증의 배치 방식), IN_PROGRESS 분류(계약 §9 행 안의 분류 문구), 30일 경과 다른 내용 = 새 요청(D-CPC-004 문언).)

---

## 19. AC 해석·모순·범위 밖(Coordinator 확인)

(각 항목 끝 **결정**은 Coordinator 2026-10-14.)

1. **L-2(AC 5)는 승인 시작 규칙**이다. CreatePayment는 승인을 시작하지 않으므로 이 작업은 domain 단일 연산 + main 우회 금지 ArchUnit까지 하고, "함께 호출함을 강제하는 application 시험"은 승인 시작 Use Case가 생기는 AUTH-001(같은 AC 보유)에 남긴다. **결정: 그대로(domain 연산 + ArchUnit은 여기, application 시험은 AUTH-001).**
2. **GetPayment(AC 13)**: "분리"를 계약 경계로 해석해 endpoint를 만들지 않는다(§4.6). 현재 상태 표현은 AUTH-CONTRACT-001 상태 계약에 묶인다. 조회 작업 등록 여부는 Coordinator 판단. 이에 따라 m-7(AC 19)은 해당 없음. **결정: GetPayment는 CP-QUERY-001로 등록, m-7 N/A.**
3. **Credential 작업 부재(AC 1·6·14 CP-07)**: Q-CPA-01 + 발급·전파 작업(제안 MASTER-CREDENTIAL-001) 등록 필요. MS명세 §13-3의 "Credential 긴급 차단 전파"도 그 작업으로. **결정: MASTER-CREDENTIAL-001(BACKLOG) 등록.**
4. **L-10 배포 문서(AC 18)**: 루트 `.env.example`은 허용 경로 밖이다. 규칙은 `docs/repository`에 적고, `.env.example` 주석 갱신이 필요하면 허용 경로 확장을 Coordinator가 정한다. **결정: docs/repository에만(TECH-BASELINE §8), `.env.example`은 손대지 않음.**
5. **AC 3(동일 fingerprint 검증 + 독립 리뷰)**은 Verifier/Reviewer 역할이며 구현자가 충족을 주장하지 않는다. **결정: 동의.**
6. **재시작(AC 2)**: 같은 JVM 안의 Spring 컨텍스트 종료·재기동으로 시험한다(§9.2). 프로세스 강제 종료 수준 시험이 필요하면 별도 하네스 명령(컨테이너화된 앱)이 필요하다 — 현재 commands.json에 없다. **결정: 같은 JVM 컨텍스트 재기동으로 충분.**
7. **CP-05 "전액 반환 후"**: 반환(취소·환불) 모델이 아직 없어 승인 이력(control `authorized_payment_id`)으로만 확인한다. 반환 후 시나리오의 실제 시험은 취소·환불 작업. **결정: control의 승인 이력으로 확인하고 한계를 명세에 적는다(§20-9).**
8. **G-1(§3.7)**: MASTER-SNAPSHOT-001 구현의 분류 공백(entitlement 기술 실패가 500)이다. entitlement provided API를 바꾸므로 Reviewer가 MS 시험 회귀도 함께 봐야 한다. **결정: 고치고 MASTER-SNAPSHOT-001 시험 전부 통과 유지.**
9. **Fixture Monkey(AC 4)**는 CP-DB-001에서 이미 도입·검사됨 — 이 작업은 요청 Fixture 추가와 main 유입 규칙 확장만 한다.
10. **S4·S5 묶음**(§16 끝): 운영 인증 bean 없이 기동 실패를 유지하는 처리안이 Coordinator 판단과 맞는지 확인 필요(대안: 운영 "전부 401" 구현 — 관례상 비권장). **결정: 운영 인증 bean 없음 = 기동 실패 관례 유지, S4·S5 함께 검증.** (결과: S5를 같은 단계에서 구현해 운영 bean이 있다 — 투영이 비어 모든 요청 401, §20-2.)
11. **HARNESS-SELFTEST 하한**: 새 Python golden 시험으로 `harness/commands.json`의 minTests(현재 69)를 올려야 module-registry 시험이 통과한다(허용 경로 안). **결정: 새 Python 시험 수만큼 정확히 올리고 payment-api·전체 minTests는 실제 수로(올리기만).**
12. 기존 `EntitlementInboxController`의 503 로그 `failure={}`는 `ReceiveFailure` 메시지(고정 문구)를 남긴다 — n-3 기준(원문 메시지 금지)으로는 분류 이름으로 바꾸는 편이 일관적이다. 이 작업에서 같은 오류 처리 관례로 맞출지 Coordinator 판단(작은 변경). **결정: 맞춘다(§20-14).**

---

## 20. 2단계 구현 편차·결정 기록(2026-10-14)

1. **AC → 실제 시험**(§1 표의 계획 이름과 다르다): 파서·orderName·헤더 `CreatePaymentRequestParserSpec`, r1 golden `RequestFingerprintV1Spec`(+ Python `harness/tests/test_create_payment_request_golden.py` 2건), 생성기·인증·facade `ApiApplicationUnitSpec`, HTTP 시나리오(CP-01·02·05·06·12, 재생·KEY_REUSED·거절 재생·eligibility·TEST/LIVE·Fixture Monkey 40건) `CreatePaymentApiSpec`, 인증·권한·재생 권한 재확인·라우팅·`/internal` 격리·투영 권한 `ApiSecuritySpec`, afterCommit·beforeCommit·응답 유실·lock wait 3분류·동시 8건·저장 버전 미지원·ID 충돌·1062 로그·30일·결함 로그 `ApiFailureSpec`, 재시작·세션 시간대 `ApiLifecycleSpec`, G-1 `EligibilityStoreFailureSpec`, L-2 `OrderPaymentControlSpec`·`ArchitectureSpec`, 설정 `MainConfigurationSpec`.
2. **Credential(D-CPA-001) 구현**: `MerchantAuthenticationService`(application) — 형식 `^pc_(test|live)_sk_[A-Za-z0-9]{8,32}\.[A-Za-z0-9_-]{43}$`, 검증값 = 키 원문 전체 SHA-256(상수 시간 비교), 접두 환경 = 저장 환경, ACTIVE, `[valid_from, valid_until)`, **`lease_until` 이전**(투영 행마다 lease — 전파가 끊기면 거절, "lease 상한"의 PAYMENT 쪽 구현). 투영 `merchant_api_credential`(V5) runtime SELECT만(V6). 운영 bean이 있으므로 운영 기동은 성공하고 투영이 비어 있어 모든 가맹점 요청이 401이다(fail-closed). (MID, 환경)당 활성 키 최대 2개는 발급 측(MASTER-CREDENTIAL-001) 규칙이며 투영은 강제하지 않는다.
3. **operation 검사 없음**: D-CPA-001 v1 권한 = 공개 API 전체(§5.2 갱신).
4. **행위자 컬럼 위치**: §5.3 계획(멱등 기록)과 달리 `payment.created_by_credential_id`(생성 시 불변)에 둔다 — 멱등 기록은 30일 뒤 갱신·삭제될 수 있어 감사 증거로 약하다. 재사용·거절 결과의 요청자는 구조화 로그(correlationId)로만 남는다(필요하면 후속 작업에서 멱등 기록 컬럼 추가).
5. **r1 입력**: §3.3 계획(`requestContent` command 필드)과 달리 서비스가 command 값(OrderKey MID = 인증 MID = 본문 MID, Site, 주문 ID, 금액, 통화, 결제수단, orderName)에서 바로 계산한다. command에 fingerprint·content를 따로 두지 않아 호출자가 다른 값을 끼울 수 없다.
6. **`+`·선행 0 금액**: JSON 문법 오류라 Jackson이 거절하지만 계약 §3.1대로 `INVALID_AMOUNT`로 분류한다(두 표기만 허용하는 분류 전용 재해석, 값은 받지 않음).
7. **commit 결과 불명확 문구**: code는 계약대로 `TEMPORARILY_UNAVAILABLE`, 문구만 "같은 Idempotency-Key로 확인, 새 키 금지".
8. **health 본문**: 상태와 probe `groups`만(세부 없음). 시험은 `status`·`groups` 외 키가 없음을 본다.
9. **CP-05 "전액 반환 후"**: 반환 모델이 없어 control `authorized_payment_id`로만 확인한다(Coordinator 결정). 반환 뒤 재요청 시험은 취소·환불 작업.
10. **인접 갭 잠금 재현**: 같은 범위에서 키 `kb-…`의 KEY_REUSED 재요청(IODKU 중복 경로)이 잡은 next-key 잠금 앞 갭에 `ka-…` 새 예약이 대기 → 409 IN_PROGRESS로 실제 MySQL에서 재현됐다.
11. **HTTP 동시 8건**: 같은 키 8건은 모두 시험 lock wait(8초) 안에 직렬화돼 201 1건 + 200 재생 7건이다(IN_PROGRESS는 timeout 시나리오에서 따로 본다).
12. **1062 로그**: 멱등 키·control은 IODKU라 정상 경로에 1062가 없다. 1062가 나는 경로(서버 생성 paymentId·paymentKey 충돌, 주문 동시 최초 요청 6건)에서 로그에 키·주문 값·"Duplicate entry"가 없음을 확인했다.
13. **RED 증거**: 시험은 구현과 같은 단계에서 작성했다(엄격한 시험 선행 아님). 대신 핵심 규칙에 대해 **변이 RED**를 실행했다 — (a) G-1 adapter 분류 제거 → `EligibilityStoreFailureSpec` 2건 실패, (b) 진입 필터 격리 끄기 → 공개 포트 `/internal` 422(404 기대) 실패, (c) 저장 버전 재계산 대신 현재 버전 직접 비교 → r2 기록에 409(503 기대) 실패, (d) orderName bidi 거절 제거 → 파서 시험 실패. 변이는 모두 되돌렸다. L-2 규칙은 위반 fixture로 실패를 확인한다. 기존 시험의 RED: V5·V6 추가 직후 `PaymentApiContextSmokeSpec`·`DatabaseAccountSeparationSpec` 5건이 기대값(V1~4, 권한 표) 불일치로 실패 → 기대값을 새 migration에 맞춰 갱신(권한은 SELECT만 추가).
14. **EntitlementInboxController 503 로그**(n-3 정렬): `failure={}`가 예외 메시지 대신 예외 클래스 이름 사슬을 남긴다.
15. **세션 시간대 시험 주의**: `SpringApplicationBuilder.properties`는 가장 낮은 우선순위라 application.yml 값을 덮지 못한다(처음 시험이 이 때문에 검사를 통과해 버렸다) — 덮어쓰기는 명령행 인자로 넘긴다.
16. **migration**: V5(`merchant_api_credential`, `payment.created_by_credential_id` NULL 허용 expand), V6(SELECT 권한). orderName 컬럼 변경 없음.
17. **설정**: 운영 `application.yml`에 Hibernate 오류 로거 OFF·health 세부 never, 필수 설정(`paycore.payment.api.internal-port`, `retry-after.*`)은 값 없음(기동 실패). 시험 값은 test `config/application.yml`(내부 포트 0, Retry-After 2/1/1초 — Demo).
18. **새 질문 후보 없음.** 금융·보안 의미의 새 미결은 나오지 않았다. 후속(질문 아님): MASTER-CREDENTIAL-001(발급·전파·폐기 우선 전파·lease 갱신), CP-QUERY-001(GetPayment, jOOQ), 인증 실패 반복에 대한 rate limit/잠금 정책(A07 Security 경보) — 도입 시 fail-open 금지.
19. **review round1(REVIEW_FAIL, `151a7cf9…`) 수정(2026-10-14~15, 수정 시도 1/3)**. 정책·기대값·게이트 기준은 바꾸지 않았다.

    | 지적 | 수정 | 증거(시험) |
    |---|---|---|
    | M-1 AC11 | 만료 뒤 같은 키·다른 내용을 **같은 Credential(같은 MID 범위)**로 보내 KEY_REUSED가 아님을 확인(30일 전에는 KEY_REUSED). §9.3 행 추가: AUTHORIZING·UNKNOWN(200 EXISTING_ACTIVE, Payment 1), FAILED 근거 부족(409 ORDER_RESULT_UNCONFIRMED), 같은 키 동시 8건(갱신 1·같은 본문·`request_record_id` 교체). §9.4 거절 재생 4종 모두(ALREADY_AUTHORIZED·RESULT_UNCONFIRMED·INTENT_CONFLICT·ON_HOLD) | `ApiFailureSpec`(30일 묶음), `CreatePaymentApiSpec`(거절 재생) |
    | m-1 | 세션 시간대 검사를 설정 출처와 무관한 **효과 검사**로: `@@session.time_zone` + plain JDBC `Timestamp` 왕복 probe(쓰기·읽기). URL 검사는 host 부분 속성·퍼센트 인코딩까지 보는 보조 검사 | `ApiLifecycleSpec`(data-source 속성 `connectionTimeZone=Asia/Seoul` + 세션 UTC → 기동 실패, host 부분·인코딩 URL 거부) |
    | m-2 | L-2 ArchUnit 규칙이 호출뿐 아니라 메서드 참조 접근(Kotlin `payment::startAuthorization`, Java `Payment::startAuthorization`)도 검출 | `ArchitectureSpec` + 위반 fixture 3종(`archfixture/startauth`) |
    | m-3 | (구현은 있었음) Content-Type·Accept 위반 400(빈 값·text/plain·`application/*`·비UTF-8 charset·잘못된 charset 이름·형식 오류, Accept 비호환)과 허용 변형 201, X-Request-Id 정제(출력 가능 ASCII 1..128, 공백·제어·비ASCII·CRLF 버림, 응답 미반영, 업무 키 아님) | `CreatePaymentApiSpec`(media type·X-Request-Id 로그), `ApiWebUnitSpec`(정제 함수) — 변이 RED: 검사 끄기 → 400 기대 실패 |
    | m-4 | 오류 본문·헤더 비노출 시험(401·400×5·403·404·422·503·409×2·라우팅 404에서 MID·Site·주문 ID·키·주문명·금액·모르는 필드 이름이 없음, error 객체 허용 키만), 422 `PAYMENT_NOT_ELIGIBLE` 4개 reason(METHOD_NOT_ENABLED 포함) API 시험, G-1 HTTP 503(운영 eligibility 경로·head 잠금), CP-06 FAILED/EXPIRED × timeout·NOT_FOUND·lease 만료·근거 없음 → 409, 충분 근거(NOT_TRANSMITTED_AND_SESSION_DISCARDED·PRE_TRANSMISSION_FAILURE_CONFIRMED) → 201 gen2 | `CreatePaymentApiSpec`, `ApiLifecycleSpec`(G-1) |
    | n-1 | 투영 표 runtime **INSERT**도 1142(UPDATE·DELETE와 함께) | `ApiSecuritySpec` |
    | n-2 | Bearer scheme 이름 대소문자 무시(RFC 7235 §2.1), 키 자체는 정확 비교 | `ApiSecuritySpec`(bearer·BEARER 201, secret 대문자 401), `ApiWebUnitSpec` — 변이 RED: 대소문자 구분 → 실패 |
    | n-3 | `/error` dispatch(`ApiErrorController`)와 방화벽 거절(`RequestRejectedHandler`)을 공개 API 오류 형식으로 통일(경로·timestamp 없음) | `ApiSecuritySpec`(405·`/error` 직접 401/404·`;`·`%25` 경로 400). 방화벽 handler를 빼도 기본 handler의 sendError가 같은 `/error` 형식으로 끝나 시험은 결과(형식)를 본다 |
    | n-4 | 선택 설정 `paycore.payment.api.internal-address`(내부 connector 바인딩 주소), internal-port = server.port면 기동 거부 | `ApiLifecycleSpec` |
    | n-5 | `+`·선행 0 금액 분류가 필드 순서를 지킨다. 추가 발견·수정: 문법 오류가 다른 필드(currency 자리 `+1`)에 있을 때 정상 amount를 INVALID_AMOUNT로 오분류하던 것을 amount **토큰 원문** 검사로 고침 | `CreatePaymentRequestParserSpec` — RED(INVALID_AMOUNT ≠ INVALID_CURRENCY) → 수정 후 통과 |
    | n-6 | 진단 값(vendorCode·SQLState) 전달: persistence → `PersistenceFailure`, entitlement → `EntitlementEvaluationFailure` → payment `PersistenceFailure`, 실패 로그 | `ApiFailureSpec`(IN_PROGRESS·control 대기 로그 `vendorCode=1205`, SQLState 있음, 원문 메시지 없음), `ApiLifecycleSpec`(G-1 로그) |
    | n-7 | 인증 필터: 저장소 일시 실패 → 503+Retry-After, 그 밖 예외 → 500(원문 미기록). 수정 중 웹이 `application.required.PersistenceFailure`를 직접 잡아 ArchUnit `webAdapterUsesOnlyApplication`을 어긴 것을 발견(RED) → application이 provided `MerchantAuthenticationUnavailable`로 옮기고 웹은 그것만 잡는다 | `ApiWebUnitSpec`, `ApiApplicationUnitSpec`, `ArchitectureSpec` |
    | n-8 | Credential 투영 Entity `@Immutable` + 값 컬럼 insertable/updatable=false | 코드(`JpaMerchantCredentialStore.kt`) |
20. **review round2(REVIEW_FAIL, `06fe02f3…`, M급 없음) 수정(2026-10-15, 수정 시도 2/3)**. 정책·기대값·게이트 기준은 바꾸지 않았다. 이월(손대지 않음): n-c(운영 internal-port 0), n-d(방화벽 시험은 형식만 보장).

    | 지적 | 수정 | 증거(시험) · RED |
    |---|---|---|
    | m-1 인증 읽기 중 연결 끊김 → 500 | (b) `MerchantAuthenticationService`를 선언적 `@Transactional(readOnly)`에서 CP-DB-001 관례 `LocalTransactions`(읽기 전용 옵션 추가, 1회 실행)로 바꿨다. 원 예외를 보존하고(rollback 실패는 suppressed), commit 실패(`CommitOutcomeUnknownException`)는 `MerchantAuthenticationUnavailable(DB_UNAVAILABLE)`, TX 시작 실패와 그 밖 `TransactionException`은 `(TRANSACTION_UNAVAILABLE)`로 옮긴다. (a) 방어로 필터도 `TransactionException` 전체를 503으로 분류한다. 웹은 여전히 application.required 타입을 보지 않는다 | `ApiSecuritySpec`: 실제 MySQL에서 시험 전용 fault point(`CREDENTIAL_BEFORE_FIND`/`CREDENTIAL_FIND`, `FaultInjectionConfig`)가 TX에 묶인 연결을 root `KILL CONNECTION`으로 끊는다(읽기 중이면 질의·rollback 실패, 읽기 뒤면 commit 실패). 503 `TEMPORARILY_UNAVAILABLE`, Retry-After, `WWW-Authenticate` 없음, 로그에 failure 분류 있고 원문 없음, 다음 요청 201. **RED 확인: 수정 전 두 경우 모두 500**. 단위: `ApiApplicationUnitSpec`(시작·commit·rollback 실패, read-only 정의), `ApiWebUnitSpec`(`TransactionSystemException` → 503) |
    | m-2 merchantSiteId 로그 줄 주입 | 파서가 merchantAccountId·merchantSiteId에 orderName과 같은 거절 문자 규칙(`LineSafeText`: Cc·U+2028/2029·bidi 제어)을 적용해 400 `INVALID_REQUEST`(field)로 거절한다(§4.3 표, 계약 §3.1 기록). entitlement 로그의 외부 유래 식별자(mid·siteId·merchantId·eventId)는 `EntitlementLogValues.safe`로 이스케이프한다(위험 문자는 `\u`+16진 4자리 표기, 백슬래시 이중화, 128자에서 자름) | `CreatePaymentRequestParserSpec`(필드 2 × 문자 9), `ApiSecuritySpec`(개행 Site·MID → 400, 행 0, 로그에 표식 없음), `EntitlementLogValuesSpec`. **RED(변이) 확인: 규칙을 빼면 개행 Site가 201로 저장됐다** |
    | n-a | `versionOf`가 `versionOfStoredValue`(`toIntOrNull`, 실패 시 `UnsupportedFingerprintVersionException` → 503)를 쓴다 | `RequestFingerprintV1Spec`. RED(변이): `toInt()`로 되돌리면 NumberFormatException |
    | n-e | 진입 필터가 요청 requestId를 SLF4J MDC `correlationId`에 넣고 요청이 끝나면 지운다. entitlement 판정 로그 3줄에 `correlationId=`를 추가했다(키는 web `ApiResponses.CORRELATION_ID_MDC_KEY`와 entitlement `EntitlementLogValues.CORRELATION_ID_MDC_KEY` — 시험이 일치를 확인). 저장소에 기존 MDC 관례가 없어 이번에 정한 구현 결정이다 | `ApiLifecycleSpec`(운영 eligibility 경로: 404 판정 로그의 correlationId = 응답 `PayCore-Request-Id`, 요청 뒤 MDC 비움), `EntitlementLogValuesSpec` |
    | n-b(문서) | §4.2-5에 Jackson 숫자 길이 상한(1000자리) 초과 → 400 `INVALID_REQUEST` 한계 기록 | 코드 변경 없음 |

### 20.1 실행 결과(구현자 실행, Verifier 판정 아님)

- `./gradlew :apps:payment-api:test`: 563건, 실패 0·오류 0·skip 0(이전 466건). 다른 모듈은 변경 없음(직전 보고서 기준 master-api 269, financial-batch 26, internal-admin 29, test-support 112) → 전체 하한 999.
- `python3 -B -m unittest discover -s harness/tests`: 71건 OK(새 golden 시험 2건).
- `harness/commands.json`: HARNESS-SELFTEST minTests 69 → 71, GRADLE-CHECK minTests·TEST-SUMMARY 하한 902 → 999.
- review round1 수정 뒤(2026-10-15 08:57–09:01 KST, Docker 29.8.0, `./gradlew --stop` 뒤): `./gradlew :apps:payment-api:test` exit 0, 592건(파일 46), 실패 0·오류 0·skip 0. `:apps:payment-api:check`(verifyNoInstancio·verifyRuntimeHasNoTestTools) exit 0. 하한 값은 바꾸지 않았다(실제 수가 하한보다 크다). 전체 VERIFY-GATE·HARNESS-SELFTEST는 Verifier 몫.
- review round2 수정 뒤(2026-10-15 09:25–09:28 KST, Docker 29.8.0, `./gradlew --stop` 뒤): `./gradlew :apps:payment-api:test` exit 0, 602건(파일 47), 실패 0·오류 0·skip 0. `:apps:payment-api:check` exit 0(verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 113 모듈에 시험 도구 없음).
