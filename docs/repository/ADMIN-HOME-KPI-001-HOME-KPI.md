# ADMIN-HOME-KPI-001 운영 홈 오늘 KPI 조회 명세 (rev 0.3a)

- **rev 0.1** (2026-11-15, Planner 초안 — 코드·migration 없음).
- **rev 0.2** (2026-11-15, Coordinator — 사용자 답 Q-HOME-KPI-1~3 전부 권장안 → D-ADMIN-HOME-002 적용, §10 ANSWERED). Reviewer 검토 전, 자기 완료 판정 아님.
- **rev 0.3** (2026-11-15, Implementer — 명세 검토 REVIEW_PASS(MAJOR 0)의 MINOR 1~7·NIT 반영, 사용자 결정 의미 변경 없음): MINOR-1 C-6 후속 작업 ID `ADMIN-HOME-ATTENTION-001`(§11),
  MINOR-2 K-1 시험 추가(T-K1-15~17 — 운영자 보정 취소 차감, 활성 hold AUTHORIZED 포함, UNKNOWN 늦은 확정은 확정일 계산), MINOR-3 §9 정렬은 application(SQL ORDER BY 없음 → filesort 없음),
  "Using temporary" 허용, subquery 색인 실명 `fk_payment_transaction_payment`(V9) + c2 FORCE INDEX, MINOR-4 L-2 자정 커밋 지연 틈·일시 과다 집계, MINOR-5 기록 경로 guard 시험 T-K1-18 + L-6,
  MINOR-6 T-K1-14 방법(SUM DECIMAL·CAST 없음·`toBigIntegerExact` 단위 시험·한도 우회 fixture 없음), MINOR-7 §4.3 fail-closed 보강(timeZone 리터럴, `-0` 거부, approved 0 ⇒ count 0,
  행마다 approved>0 또는 cancelled>0, from ≤ asOf, asOf = from ⇒ 빈 items — T-K3-5). NIT: §4.4 경로 표기, 문장별 2초(최악 ~4초), C-4 설정 키 `home-kpi-per-minute`, 색인 열 순서
  `(type, confirmed_at, currency, amount_minor, payment_id)` 선택·근거와 INSERT 비용(L-1), dependsOn은 K-4만, §6 VIEWER 근거, T-K1-8 기존 internal fixture 경로, items ≤ 64 → L-5.
  자기 완료 판정 아님(Reviewer·Verifier 대상).
- **rev 0.3a** (2026-11-16, Implementer — K-1~K-3 코드 리뷰 MINOR-1~3·NIT 반영, 정의·사용자 결정·기대값 변경 없음): MINOR-1 T-K1-6 취소 OPEN 사례 추가(공식 취소 경로 + 기존
  FaultInjector TX2 실패 주입 — 기관 승인 후 OPEN·DISPATCHING·RESERVED, CANCEL 거래 없음 → 차감·전액판정 없음), MINOR-2 P-1에서 실제 실행 문장의 GROUP BY 키 단언(S1 =
  environment·currency·type, S2 = environment·currency — L-7 갱신), MINOR-3 T-K3-3 반대 방향(거래 조회 창 소진 직후 KPI 200), NIT-1 PAYMENT 404를 실제 결과(Unavailable `HTTP_404`)로
  기록, NIT-2 CAST 금지 검사 S2 적용, NIT-5 시험 날짜 시작을 공유 DB의 가장 늦은 확정 날짜 다음으로(충돌 없음), NIT-3·4·6·7과 home-kpis 계약 golden 부재를 §14 L-8~L-12로 기록.
  자기 완료 판정 아님(Reviewer·Verifier 대상).
- 표기: 사용자 결정 = docs/DECISIONS.md. C-n = Coordinator 가역 선택. L-n = 위험·한계.
- 근거: **D-ADMIN-HOME-001**(Q-HOME-1~4), **D-ADMIN-HOME-002**(Q-HOME-KPI-1~3), D-CPD-001(TEST/LIVE), D-CP-002(통화·minor unit), D-CANCEL-006·009·011, INV-C2·C8(CANCEL-CONTRACT-001 §4.3·§10), CANCEL-CONTRACT-001 §5.3, D-TXOPS-002, D-MOPS-002, D-ADMIN-002, ARCHITECTURE-CONVENTIONS §9.1·§14·§18·§21, TXN-OPS-001(ADMIN→PAYMENT 조회 패턴), PAYMENT-QUERY-PLAN-FLAKE-001(계획 시험 교훈), Test Fixture Policy.

## 0. 한눈에

| 항목 | 결정·제안 |
|---|---|
| 무엇 | 운영 홈 카드: **오늘 순승인액**·**오늘 승인 건수**(통화별·환경별), 조회 전용·금융 효과 없음·비권위(정산 금액 아님) |
| 순승인액 | [KST 오늘 00:00, asOf) 에 확정된 AUTHORIZATION 거래 합 − 같은 구간에 확정된 CANCEL 거래 합(부분취소 포함, 이전 날짜 승인의 오늘 취소 포함, 음수 가능) |
| 승인 건수 | 같은 구간에 확정된 AUTHORIZATION 거래 중 asOf 시점 누적 취소액 < 승인액 인 결제 수(부분취소 포함, 전액취소 제외) |
| 원천 | PAYMENT `payment_transaction`(append-only 확정 사실) + `payment`(environment·status) — PAYMENT 소유 DB만, cross-DB 없음 |
| 제외 | REQUESTED·AUTHORIZING·UNKNOWN·FAILED·EXPIRED Payment, 취소 OPEN·UNKNOWN·FAILED, FORCED(D-ADMIN-HOME-002 ②), 종료 Payment의 늦은 승인과 그 보정 취소(D-ADMIN-HOME-002 ①) |
| 시각 원천 | PG 확정 시각 `confirmed_at`(D-ADMIN-HOME-002 ③), 화면 "PG 확정 시각 기준 · 정산 금액 아님" |
| 환경 | 한 응답에 LIVE·TEST **별도 행**(합산 금지), 화면은 환경별 묶음(LIVE 먼저) — C-1 |
| 금액 표현 | minor unit 정수의 **10진 문자열**(`"-15000"`), float 없음, SQL SUM(DECIMAL) → BigInteger |
| endpoint | PAYMENT `GET /internal/v1/admin/home-kpis`(ADMIN 서명 채널), ADMIN `GET /api/home-kpis` |
| 권한 | VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN(거래 조회와 같은 VIEW 4역할), 그 밖 403 |
| 감사 | 없음(집계 — 개별 거래·가맹점 식별 없음, D-TXOPS-002·D-MOPS-002와 같은 이유) |
| 성능 | 새 색인 `ix_payment_transaction_confirmed (type, confirmed_at, currency, amount_minor, payment_id)`(V35, 온라인 DDL), FORCE INDEX + 문장별 `MAX_EXECUTION_TIME(2000)`(2문장 — 최악 ~4초), 계획 시험(ANALYZE TABLE 선행) |
| 배치 | K-1 PAYMENT 조회·색인 → K-2 PAYMENT endpoint → K-3 ADMIN → K-4 화면(K-4만 ADMIN-UI-WORKSPACE-001에 의존) |

## 1. 현재 상태(코드 확인, rev 0.1 Planner 조사)

### 1.1 ADMIN → PAYMENT 조회 패턴(TXN-OPS-001)
- ADMIN `paymentops.adapter.web.TransactionController`: `POST /api/payment-searches`, `GET /api/payments/{id}`, 모두 `Cache-Control: no-store`, 오류 `{"error": code}`.
- Use Case `paymentops.application.TransactionLookupService`: 계정 현재 행 ACTIVE + `VIEW_ROLES = VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN` → 형식 검사 → actor별 분당 창(`lookupPerMinute`) → gateway(DB TX 밖) → 결과. 검색은 감사 없음, 상세만 `PAYMENT_VIEWED` 감사 선기록.
- Port `application.required.PaymentReadGateway`(Found·NotFound·Unavailable) → `adapter.integration.HttpPaymentOpsGateway`: ADMIN 서명 v1(`AdminCallSigner`, §21), `lookup` Semaphore(대기 0 → `PAYMENT_OPS_BUSY`), TX 안 호출 거부(`outsideTransaction`), 응답 상한, `PaymentResponseShape`로 허용 필드·형식 재구성 + "이 요청의 답인가" 검사(fail-closed → Unavailable `UNREADABLE`).
- PAYMENT `payment.adapter.web.admin.AdminPaymentController`(`/internal/v1/admin/`): `AdminChannelPermits.read(endpoint)`(busy 503 `ADMIN_READ_BUSY`), `OperatorQueryFailure.TimedOut` → `paycore.payment.admin_channel.query_timeout{endpoint}` + 503 `TEMPORARILY_UNAVAILABLE`. ArchUnit(`ArchitectureSpec.kt` ADMIN 채널 컨트롤러 FQN 목록, `AdminPaymentQuery` 호출자 제한).
- PAYMENT jOOQ `JooqAdminPaymentReader`: 모든 문장 `AdminReadLimits.HINT`(`MAX_EXECUTION_TIME(2000)`), `AdminReadLimits.guard`(timeout → `QueryTimedOut`), FORCE INDEX로 모드별 색인 고정, 시각은 `UtcWallClock.literal/iso`(§9.1), 상세는 `@Transactional(readOnly, REPEATABLE_READ)` 다문장.
- 서명 채널 규칙(§21): query 문자열 금지(서명 전 400), 본문 상한 16 KiB.

### 1.2 PAYMENT 스키마(관련 열)
- `payment`(V1): `environment` TEST/LIVE, `amount_minor BIGINT ≥1`, `currency CHAR(3)`, `status ∈ {REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED, EXPIRED}` — **CANCELLED 상태 없음**(반환은 ReturnBalance). status 색인 없음.
- `payment_transaction`(V9 + V17): `type ∈ {AUTHORIZATION, CANCEL}`(`ck_payment_transaction_type_v2`), `payment_id`(FK 색인), `amount_minor BIGINT ≥1`, `currency`, `cancel_scope ∈ {FULL, PARTIAL}`(CANCEL만), `original_transaction_id`, `institution_transaction_date DATE`, `confirmed_at DATETIME(3)` UTC(PG 확정 시각). **environment 열 없음, confirmed_at 색인 없음.**
- `payment_cancellation`(V15): `status ∈ {OPEN, UNKNOWN, COMPLETED, FAILED}`, 완료 시각 = CANCEL 거래 `confirmed_at`. INV-C2: COMPLETED ⇔ CANCEL 거래 정확히 1.
- `return_balance`: `authorized_minor`, `completed_minor`, `reserved_minor`, `forced_minor`. INV-C8: FULLY_CANCELLED ⇔ completed = authorized(FORCED 미포함). FORCED = 늦은 취소 성공 재기록(CANCEL 거래 없음, hold — D-CANCEL-006).
- D-CANCEL-011: 종료(FAILED/EXPIRED) Payment에 늦은 AUTHORIZATION 거래가 추가될 수 있다(hold, 운영자 보정 취소 대상).
- 환불(Refund) 타입·표 없음(현재 반환 = 취소만). 최신 payment migration V34 → 새 migration **V35**(구현자가 번호 재확인).

### 1.3 화면
- `HomeScreen.tsx`는 ADMIN-UI-WORKSPACE-001 2단계에서 원본 배치로 바뀌는 중. 임시 client `getHomeKpis()`는 K-4에서 이 명세 모양(§4)으로 교체.
- 금액 표시: 문자열 그대로 천 단위 구분(부호 처리, `Number` 변환 금지), KRW `원`, 그 밖 `(최소 단위)`.

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| D-ADMIN-HOME-001 ① 순승인액 | 승인 합 − 취소 합(§3.2) |
| ② 완료일 기준 | 구간 [KST 오늘 00:00, asOf), 부분취소 포함, 이전 날짜 승인의 오늘 취소 차감, 음수 허용(§3.1) |
| ③ 건수 | 오늘 승인 중 전액취소되지 않은 건(부분취소 포함) — 전액 판정 = INV-C8(§3.3) |
| ④ 지급 예정액 | 이 API 범위 밖 — 화면 "정산 기능 준비 전" |
| D-ADMIN-HOME-002 ① | 종료 Payment 늦은 승인·그 보정 취소 → 금액·건수 제외(`p.status='AUTHORIZED'` 양쪽), "확인 필요"로 셈(C-6 후속) |
| D-ADMIN-HOME-002 ② | FORCED → 차감·전액판정 제외(ReturnStatus와 같은 정의), "확인 필요"로 셈(C-6 후속) |
| D-ADMIN-HOME-002 ③ | 시각 원천 `confirmed_at`, 화면 표지 "PG 확정 시각 기준 · 정산 금액 아님" |
| 코디네이터 기본값 | 통화별 분리, UNKNOWN·진행 중 금액 제외, TEST/LIVE 혼합 금지 |
| D-CPD-001·D-CP-002 | environment TEST/LIVE, 통화별 minor unit 정수, 통화 간 합산 없음 |
| D-TXOPS-002·D-MOPS-002 | 목록·검색류 감사 없음 → 집계도 감사 없음(§6) |

## 3. 정의(실제 열·상태 대응)

### 3.1 시간 구간
- `asOf` = PAYMENT 조회 서비스가 **조회 TX 시작 직전** 한 번 읽은 시각(밀리초 절사, 주입 시계 — 시험은 offset 시계). ADMIN 시계는 쓰지 않는다.
- `businessDate` = asOf의 `Asia/Seoul` 날짜. `from` = businessDate 00:00 Asia/Seoul → UTC(전날 15:00:00.000Z). 구간 **[from, asOf)**.
- SQL 비교는 `UtcWallClock.literal(from)`·`literal(asOf)`. asOf가 정확히 KST 00:00:00.000이면 빈 목록.
- 시각 기준 = `payment_transaction.confirmed_at`(D-ADMIN-HOME-002 ③). 늦게 확정된 UNKNOWN은 확정된 날에 잡힌다.

### 3.2 순승인액(환경 e, 통화 c)
- approvedMinor(e,c) = Σ t.amount_minor | t.type='AUTHORIZATION' ∧ from ≤ t.confirmed_at < asOf ∧ p.payment_id=t.payment_id ∧ p.environment=e ∧ p.status='AUTHORIZED' ∧ t.currency=c
- cancelledMinor(e,c) = Σ t.amount_minor | t.type='CANCEL' ∧ 같은 구간 ∧ p.environment=e ∧ p.status='AUTHORIZED' ∧ t.currency=c
- netApprovedMinor = approvedMinor − cancelledMinor(BigInteger, 음수 허용).
- CANCEL 거래만 차감 → 취소 OPEN·UNKNOWN·FAILED, FORCED(거래 없음)는 자동 제외(INV-C2, D-ADMIN-HOME-002 ②).
- `p.status='AUTHORIZED'` → REQUESTED·AUTHORIZING·UNKNOWN·FAILED·EXPIRED 제외, 종료 Payment 늦은 승인과 그 보정 취소 제외(D-ADMIN-HOME-002 ①).

### 3.3 승인 건수
- approvedCount(e,c) = |{ t : AUTHORIZATION, 구간 안, p.status='AUTHORIZED', p.environment=e, t.currency=c, t.amount_minor > COALESCE(Σ c2.amount_minor | c2.payment_id=t.payment_id ∧ c2.type='CANCEL' ∧ c2.confirmed_at < asOf, 0) }|
- 전액취소 = 누적 CANCEL 합 = 승인액(INV-C8, FORCED 미포함). `return_balance` 현재 값이 아니라 **asOf 이전 CANCEL 거래**로 계산.
- 오늘 승인 후 오늘 전액취소 → 금액 +A −A, 건수 0.

### 3.4 결과 행 집합
- 행 키 (environment, currency). 구간 안에 AUTHORIZATION 또는 CANCEL 사실이 하나라도 있는 (e,c)만 행(취소만 있으면 approved 0·count 0·net 음수).
- 정렬: environment LIVE → TEST, currency 오름차순 — **PAYMENT application(`HomeKpiQueryService`)이 정렬**한다(SQL ORDER BY 없음 → filesort 없음, §9). 빈 날 = 빈 목록(C-3).

### 3.5 환불
- 현재 반환은 취소뿐. 새 반환 거래 타입이 생기면 재정의 필수 → 스키마 guard 시험 T-K1-12.

### 3.6 기록 경로 전제(rev 0.3 MINOR-5)
- 현재 `payment_transaction` 행을 쓰는 경로: 승인 결과 반영(`recorded_by = RESOLUTION`, AUTHORIZED 승인), 종료 Payment의 늦은 승인 RECON 기록(`RECONCILIATION` — FAILED 승인에만,
  `PaymentAuthorizationPersistenceMapper.recordedBy`), 취소 결과 반영(`RESOLUTION` 고정 — `PaymentCancellationPersistenceMapper`). 즉 AUTHORIZED Payment의 사실은 전부 RESOLUTION이다.
- §3.2·§3.3은 이 전제에서 `recorded_by`를 조건에 넣지 않는다. guard 시험 T-K1-18이 값 집합·경로별 값을 고정하고, RECON이 AUTHORIZED Payment에 사실을 기록하는 경로가 생기면 §3을 재정의한다(L-6).

## 4. API

### 4.1 PAYMENT `GET /internal/v1/admin/home-kpis`
- ADMIN 서명 v1 채널(§21), 본문·query 없음. `AdminChannelPermits.read("home_kpi")`.
- 200 본문(키 순서 고정):
```json
{
  "asOf": "2026-11-15T05:12:33.123Z",
  "businessDate": "2026-11-15",
  "timeZone": "Asia/Seoul",
  "from": "2026-11-14T15:00:00.000Z",
  "items": [
    {"environment": "LIVE", "currency": "KRW", "approvedAmountMinor": "150000", "cancelledAmountMinor": "170000", "netApprovedAmountMinor": "-20000", "approvedCount": 2},
    {"environment": "TEST", "currency": "USD", "approvedAmountMinor": "1250", "cancelledAmountMinor": "0", "netApprovedAmountMinor": "1250", "approvedCount": 1}
  ]
}
```
- 금액 문자열 `^(0|-?[1-9][0-9]{0,39})$`(`-0` 거부, approved·cancelled 음수 금지), approvedCount 정수 ≥ 0.
- 오류: 401(서명, 원인 비구별), 400(query 문자열), 503 `ADMIN_READ_BUSY`·`TEMPORARILY_UNAVAILABLE`. 500 없음. `Cache-Control: no-store`.

### 4.2 ADMIN `GET /api/home-kpis`
- 세션 규칙 기존, 응답 = PAYMENT 문서를 `PaymentResponseShape.HOME_KPI`로 검사·재구성한 같은 모양, `no-store`.
- 오류: 403 `FORBIDDEN`, 429 `RATE_LIMITED`, 503 `PAYMENT_UNAVAILABLE`.

### 4.3 ADMIN 응답 검사(fail-closed — 위반 시 503)
- 모양: 위 필드만, 금액은 문자열(`^(0|-?[1-9][0-9]{0,39})$` — `-0`·앞자리 0·소수점·지수 거부), `timeZone` = 리터럴 `"Asia/Seoul"`, environment ∈ {LIVE, TEST}, currency `^[A-Z]{3}$`,
  approvedCount 정수 ≥ 0, items ≤ 64(L-5).
- 이 요청의 답: `from` = `businessDate` 00:00 Asia/Seoul의 UTC, `businessDate` = `asOf`의 Asia/Seoul 날짜, `from ≤ asOf`, `asOf = from` ⇒ items 빈 목록, (environment, currency) 중복 없음,
  정렬 규칙, net = approved − cancelled(BigInteger), approved·cancelled ≥ 0, approved = 0 ⇒ approvedCount = 0, 행마다 approved > 0 또는 cancelled > 0(사실 없는 행 없음).

### 4.4 임시 프론트 `getHomeKpis()`와의 차이(K-4에서 교체)
- 경로: 임시 client의 `/api/home/kpis` → 이 명세 `/api/home-kpis`. `HomeScreen.test.tsx`의 major unit 시험 값 `'-12.50'`은 K-4에서 minor unit 정수 문자열로 바꾼다(이번 K-1~K-3 범위 밖 — 화면 파일 미변경).
| 임시 | 이 명세 |
|---|---|
| 배열 `[{…}]` | `{asOf, businessDate, timeZone, from, items:[…]}` |
| `netApprovedAmount: string` | `netApprovedAmountMinor: string`(minor unit 정수) |
| environment 없음 | `environment` 필수 |
| 행마다 `asOf` | 최상위 `asOf` |
| — | `approvedAmountMinor`·`cancelledAmountMinor` |

## 5. 구조(ARCHITECTURE-CONVENTIONS §14)

PAYMENT `com.paycore.payment.payment`:
- domain `HomeKpiWindow`(순수 — `of(asOf)` → businessDate·from·asOf, Asia/Seoul), `HomeKpiFigures`(approved·cancelled·count → net, BigInteger, 음수 approved/cancelled·count 거부).
- provided `HomeKpiQuery` + `HomeKpiSnapshot`·`HomeKpiItem`, 구현 `application.HomeKpiQueryService`(TX를 열지 않음 — asOf 결정 후 Reader 호출).
- required `HomeKpiReader` → `adapter.query.JooqHomeKpiReader`(`@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, 2문장, 모든 문장 `AdminReadLimits.HINT`·`guard`, FORCE INDEX `ix_payment_transaction_confirmed`).
- web `adapter.web.admin.AdminHomeKpiController`.
- ArchUnit: ADMIN 채널 컨트롤러 FQN 목록에 추가, `HomeKpiQuery` 호출자 = `HomeKpiQueryService`·`AdminHomeKpiController`만, `HomeKpiReader` 사용처 = 조회 서비스만 — 각 위반 fixture.
- migration `db/migration/payment/V35__payment_transaction_confirmed_index.sql`(번호 확인: migration V32 + runtime-privilege V33·V34 다음):
  `ALTER TABLE payment_transaction ADD INDEX ix_payment_transaction_confirmed (type, confirmed_at, currency, amount_minor, payment_id), ALGORITHM = INPLACE, LOCK = NONE;` 새 표·권한 변경 없음.
  jOOQ codegen은 빌드가 migration에서 다시 만든다(색인 이름은 문자열 hint).
- 열 순서 선택(rev 0.3 NIT): `type` 선두 — S2(건수)는 `type = 'AUTHORIZATION'` 한 범위만 읽고(취소 행을 건너뛰지 않아도 됨), S1은 `type IN (AUTHORIZATION, CANCEL)` 두 범위 각각
  `confirmed_at` 범위로 좁혀진다. `confirmed_at` 선두면 S2가 같은 시간대의 취소 행까지 읽는다. 뒤 3열은 covering(표 본문 접근 없이 합·join 키). 비용은 L-1.

ADMIN `com.paycore.admin.paymentops`:
- provided `HomeKpiUseCase` + sealed `HomeKpiResult`(Found·Forbidden·RateLimited·Unavailable).
- application `HomeKpiService`(역할 → 속도 제한 → gateway, 감사 없음, 로그 분류만).
- required `HomeKpiGateway.homeKpis()` → `HttpPaymentOpsGateway`(같은 키·`lookup` permit·timeout·응답 상한 — 새 채널·키 없음). rev 0.3 구현 기록: rev 0.2의 `PaymentReadGateway.homeKpis()` 대신
  별도 Port(같은 구현 객체)로 둔다 — 기존 Port 시험 대역을 바꾸지 않기 위한 가역 구현 세부. 설정은 `HomeKpiSettings`(같은 접두사 `paycore.admin.payment-ops`, Port `HomeKpiLimits`), 지표 Port `HomeKpiMetrics`.
- `PaymentResponseShape.HOME_KPI` + 새 형식 `MinorDecimal(signed: Boolean)`, 이 요청의 답 검사 `HomeKpiAnswer`(§4.3).
- web `adapter.web.HomeKpiController`(`GET /api/home-kpis`). 지표 Port → Micrometer 구현.

## 6. 권한·감사
- 권한: 계정 현재 행 ACTIVE + VIEW 4역할(C-2). 그 밖(UNMASK 단독 등) 403, PAYMENT 미호출.
- VIEWER 근거(rev 0.3 NIT): VIEWER는 이미 거래 목록·상세에서 개별 결제 금액·상태를 보므로, 같은 사실의 환경·통화별 합계는 새 정보를 노출하지 않는다(가맹점·결제 식별자 없음).
- 감사 없음: 결과가 환경·통화별 합계뿐. 시험 T-K3-7로 감사 행 0 고정.
- 속도 제한: actor별 분당 창, 설정 키 `paycore.admin.payment-ops.home-kpi-per-minute`(**필수 — 기본값 없음**, 빠지면 기동 실패, Demo 30/분 — 운영값 아님), 거래 조회
  `lookup-per-minute` 창과 별개(C-4). 화면은 polling하지 않는다(진입·새로고침 때만). 시험 창도 거래 조회 창과 독립이다(T-K3-3).

## 7. 8대 원칙
| 원칙 | 적용 |
|---|---|
| Financial Consistency | 확정 사실만 합산, 미확정 제외, 통화·환경 합산 없음, 정확 정수, net 불변식 ADMIN 재검사 |
| Transaction Safety | 쓰기 없음. PAYMENT 읽기 전용 RR TX 하나, ADMIN 호출은 DB TX 밖 |
| Idempotency | 해당 없음(읽기) |
| Concurrency Control | 잠금 없음. 한 스냅샷 2문장(T-K1-10) |
| Recoverability | 해당 없음(상태 없음 — 실패 시 재조회) |
| Reconciliation | 비권위 표시값, 정산·대사 판정에 쓰지 않는다. 화면 표지 |
| Auditability | 감사 없음 — §6 근거 |
| Observability | §8 |

## 8. Observability
- PAYMENT: `paycore.payment.admin_channel.busy{endpoint="home_kpi"}`, `…query_timeout{endpoint="home_kpi"}`, timer `paycore.payment.admin_channel.home_kpi.duration`. 로그에 금액 없음.
- ADMIN: counter `paycore.admin.ops.home_kpi{result}`(OK·FORBIDDEN·RATE_LIMITED·UNAVAILABLE). label에 ID·금액·환경 없음.

## 9. 성능·실행 계획
- S1(합계): `payment_transaction t FORCE INDEX(ix_payment_transaction_confirmed)` range(type IN, confirmed_at) "Using index" → STRAIGHT_JOIN `payment p` eq_ref PRIMARY → GROUP BY (environment, currency, type).
- S2(건수): 같은 range(type='AUTHORIZATION') + 상관 subquery `payment_transaction c2 FORCE INDEX(fk_payment_transaction_payment)` ref(payment_id) — 색인 실명 `fk_payment_transaction_payment`(V9 FK 제약이 만든 색인).
- 정렬: SQL ORDER BY 없음 — application이 정렬한다(§3.4). 그래서 filesort가 없어야 한다. GROUP BY의 "Using temporary"는 허용(행 수 = 환경 × 통화 × 타입, 작다).
- 비용: 하루 확정 거래 수에 비례. 문장마다 `MAX_EXECUTION_TIME(2000)`(2문장 → 최악 ~4초) 초과 시 503. 대량 LIVE는 LIVE Gate(L-1).
- 계획 시험 P-1: 시험이 스스로 결제 ≥5건을 공식 경로로 만들고, EXPLAIN 직전 읽는 표 전부 `ANALYZE TABLE`(status OK 단언), 단언: type=ALL 없음, t key=`ix_payment_transaction_confirmed`, p key=PRIMARY,
  c2 key=`fk_payment_transaction_payment`, filesort 없음("Using temporary"는 허용). 재시도로 통과시키지 않는다.

## 10. 사용자 질문 — ANSWERED(D-ADMIN-HOME-002, 2026-11-15, 전부 권장안)
| ID | 질문 | 답 |
|---|---|---|
| Q-HOME-KPI-1 | 종료 Payment의 늦은 승인(D-CANCEL-011)·그 보정 취소 포함 여부 | **둘 다 제외**, "확인 필요"로 셈 |
| Q-HOME-KPI-2 | FORCED(D-CANCEL-006) 차감·전액판정 포함 여부 | **제외**(ReturnStatus 정의와 같음), "확인 필요"로 셈 |
| Q-HOME-KPI-3 | 완료 시각 원천 | **PG 확정 시각 `confirmed_at`**, 화면 "PG 확정 시각 기준 · 정산 금액 아님" |

## 11. Coordinator 결정(가역)
- C-1 한 응답에 LIVE·TEST 별도 행, 화면 LIVE 먼저·환경 표지 항상 표시.
- C-2 권한 VIEW 4역할. C-3 빈 날 = 빈 목록. C-4 별도 분당 창 `paycore.admin.payment-ops.home-kpi-per-minute`(필수, Demo 30 — 운영값 아님, polling 없음, 시험 창 독립). C-5 캐시 없음.
- C-6 "확인 필요" 수(UNKNOWN 승인·진행 중 취소·FORCED·활성 hold·종료 Payment 늦은 승인)는 후속 작업 **`ADMIN-HOME-ATTENTION-001`**(Coordinator 등록 — 이번 응답에 넣지 않음) — 화면 "확인이 필요한 업무" 카드는 그때까지 기존 원천(승인 대기·대사 발견)만.

## 12. 시험·수용 기준(TDD — 실 MySQL 8.4, 공식 경로 Named Fixture, offset 시계, Fixture Monkey 미사용)

K-1 PAYMENT 조회
- T-K1-1 경계: 승인 confirmed_at = KST 어제 23:59:59.999 제외, KST 00:00:00.000 포함, = asOf 제외.
- T-K1-2 asOf = KST 00:00:00.000 → 빈 목록. asOf 14:59:59.999Z → businessDate 당일, from 전날 15:00Z(`HomeKpiWindow` 단위 시험 포함).
- T-K1-3 부분취소: 오늘 승인 10,000 + 오늘 취소 3,000 → approved 10,000, cancelled 3,000, net 7,000, count 1.
- T-K1-4 전액취소: 오늘 승인 10,000 + 오늘 FULL 취소 → net 0, count 0. PARTIAL 3,000 + PARTIAL 7,000 → count 0.
- T-K1-5 이전 날짜 승인의 오늘 취소: 어제 승인 5,000, 오늘 취소 5,000 → approved 0, net −5,000, count 0.
- T-K1-6 미확정 제외: REQUESTED·AUTHORIZING·UNKNOWN Payment, 취소 OPEN·UNKNOWN·FAILED → 합계·건수 무변. 취소 OPEN(rev 0.3a MINOR-1)은 공식 취소 경로 + 기존 FaultInjector
  TX2 실패 주입(`CancelFlowSpec` §9.2 사례와 같은 방식)으로 만든다 — 기관이 전액 취소를 승인했지만 취소 OPEN·Attempt DISPATCHING·예약 RESERVED·CANCEL 거래 0 → approved 10,000,
  cancelled 0, count 1(차감·전액판정 없음). 직접 SQL fixture 없음.
- T-K1-7 FORCED → 차감·전액판정 제외.
- T-K1-8 종료 Payment 늦은 승인 + 운영자 보정 취소 → 둘 다 제외. 늦은 승인 거래는 RECON 기록 Use Case가 아직 없어 기존 internal fixture 경로(`CancellationFixture.kt:199-205`와 같은 모듈
  internal 전이 `PaymentAuthorization.recordLateApprovalByReconciliation` → 공식 Repository 저장)로만 만든다 — 새 우회(직접 SQL·reflection) 없음.
- T-K1-9 다통화: KRW·USD 별도 행.
- T-K1-10 스냅샷: S1 뒤·S2 전 다른 연결의 취소 커밋 → 결과 일관.
- T-K1-11 환경 격리: 같은 MID TEST·LIVE → 환경별 행.
- T-K1-12 스키마 guard: `ck_payment_transaction_type_v2` 값 집합 = {AUTHORIZATION, CANCEL}.
- T-K1-13 asOf 이후 확정 취소는 전액 판정에 안 들어감.
- T-K1-14 정밀도(rev 0.3 MINOR-6 방법): (a) DB — 실제 S1 문장의 합계 열 결과 형식이 `DECIMAL`이고 문장에 BIGINT·SIGNED로의 CAST가 없다(캡처한 SQL·ResultSetMetaData 단언),
  (b) 단위 — `BigDecimal` → `toBigIntegerExact()` → 10진 문자열 변환이 Long 범위를 넘는 값(예: 3 × Long.MAX)·음수 net에서 정확하고 소수부가 있으면 거부. float 경로 없음.
  Long 근처 금액을 실제로 쌓는 한도 우회 fixture는 만들지 않는다.
- T-K1-15(MINOR-2a) AUTHORIZED Payment의 운영자 보정 취소(OPERATOR CANCEL 거래)는 차감된다.
- T-K1-16(MINOR-2b) 활성 hold(CONFLICTING_INSTITUTION_RESULT)가 있는 AUTHORIZED Payment의 승인은 포함된다(hold는 "확인 필요" 후속 — C-6, 금액 제외 조건 아님).
- T-K1-17(MINOR-2c) 어제 UNKNOWN이던 승인이 오늘 확정되면 오늘 approved·count에 들어가고, 어제 개시돼 UNKNOWN이던 취소가 오늘 확정되면 오늘 차감된다(confirmed_at 기준).
- T-K1-18(MINOR-5) 기록 경로 guard: `ck_payment_transaction_recorded_by` 값 집합 = {RESOLUTION, RECONCILIATION}, AUTHORIZED 승인·취소 거래 = RESOLUTION, 종료 Payment 늦은 승인 = RECONCILIATION.
- P-1 실행 계획(§9). rev 0.3a MINOR-2: 같은 시험이 실제 실행한 두 문장(jOOQ inlined 렌더링 캡처)의 GROUP BY 키를 정확히 단언한다 — S1 `p.environment, t.currency, t.type`,
  S2 `p.environment, t.currency`(환경·통화 혼합 회귀 검출, L-7).
- T-K1-14(a) rev 0.3a NIT-2: CAST(SIGNED·BIGINT·UNSIGNED) 금지 검사를 S2(건수 문장 — 취소 합 subquery 포함)에도 적용한다.

K-2 PAYMENT endpoint
- T-K2-1 서명 없음·위조·시각 창 밖 → 401. query 문자열 → 400.
- T-K2-2 permit 소진 → 503 `ADMIN_READ_BUSY` + busy. timeout 주입 → 503 + query_timeout.
- T-K2-3 응답 바이트 모양·키 순서·`no-store`, 금액 문자열.
- T-K2-4 ArchUnit 규칙 3개 각 위반 fixture.

K-3 ADMIN
- T-K3-1 역할 거부(UNMASK 단독·비활성) → 403, PAYMENT 호출 0.
- T-K3-2 VIEW 4역할 각각 200. T-K3-3 속도 제한 429(거래 조회 창과 독립 — 한쪽 소진이 다른 쪽을 막지 않음. rev 0.3a MINOR-3: 두 방향 모두 시험 — KPI 창 소진 후 거래 검색 200,
  거래 조회 창 소진(429) 직후 KPI 200·PAYMENT 호출 1). T-K3-4 PAYMENT 503·timeout·401·상한 초과 → 503.
- T-K3-5 모양 위반(금액 JSON 숫자, net ≠ approved − cancelled, 중복, from 불일치, 모르는 environment, 소수점, `-0`, timeZone ≠ "Asia/Seoul", approved 0인데 count > 0,
  approved·cancelled 둘 다 0인 행, from > asOf, asOf = from인데 items 있음, 정렬 위반, items 65개) → 503.
- T-K3-6 TX 안 호출 → 거부. T-K3-7 감사 행 0, 로그에 금액 없음.

K-4 화면
- T-K4-1 환경 묶음·LIVE 먼저·환경 표지, T-K4-2 음수 net, T-K4-3 2^53 초과 문자열 정확 표시, T-K4-4 빈 목록 "오늘 완료된 승인 없음", T-K4-5 오류·세션 종료, T-K4-6 VIEWER 표시·UNMASK 단독 미호출, T-K4-7 "PG 확정 시각 기준 · 정산 금액 아님"·asOf 표시, 가짜 숫자 없음.

## 13. 배치
- K-1 PAYMENT domain + V35 색인 + Reader + P-1.
- K-2 PAYMENT Query 서비스·컨트롤러·ArchUnit.
- K-3 ADMIN gateway·shape·Use Case·컨트롤러·지표.
- K-4 화면(ADMIN-UI-WORKSPACE-001과 조율 — `getHomeKpis` 모양 교체).

## 14. 위험·한계
- L-1 대량 LIVE에서 하루 범위 집계 시간(문장별 2초 상한, 최악 ~4초) — LIVE Gate, 필요 시 일 요약 projection 별도 작업. 새 색인은 `payment_transaction` INSERT마다 보조 색인 1개
  갱신 비용(키 ≈ type 16 + confirmed_at 7 + currency 3 + amount 8 + payment_id 64 바이트 + PK)을 더한다 — 확정 사실 기록은 승인·취소당 1행이라 작지만 부하 측정은 LIVE Gate.
- L-2 스냅샷 뒤 커밋된 사실은 다음 조회에 반영 — 비권위 표시값. 자정 커밋 지연 틈(rev 0.3 MINOR-4): `confirmed_at`은 TX2 안에서 커밋 **전**에 정해지므로, 자정 직전
  `confirmed_at`을 받은 사실이 자정 뒤 커밋되면 이미 오늘 첫 조회가 지나간 "어제" 값에는 영영 들어가지 않고(어제 화면은 다시 계산되지 않음), 같은 이유로 asOf 직전 confirmed_at
  사실이 조회 스냅샷 뒤 커밋되면 그 조회에서 빠진다. 반대로 오늘 승인 + 스냅샷 뒤 커밋된 전액취소처럼 사실 쌍이 갈라지면 일시적으로 많게(과다) 보일 수 있다. 다음 조회에서 수렴하며,
  정산·대사 판정에 쓰지 않는 비권위 표시값이라 허용한다.
- L-3 속도 제한 인스턴스 메모리.
- L-4 순승인액은 정산 금액과 다를 수 있음(시각 원천·FORCED·사고 건) — 화면 표지로 고지.
- L-5 items 상한 64(환경 2 × 통화 32) — 활성 통화가 더 늘면 ADMIN 검사가 503으로 막는다(fail-closed). 상한 변경은 통화 정책 확장과 함께 별도 결정.
- L-7 시험 한계(rev 0.3 구현 기록, Coordinator 판단 대상): 시험 Route가 TEST·KRW뿐(`supported-currencies: [KRW]`, LIVE Route 없음 — Simulator/REAL 격리)이라 LIVE·다른 통화의
  확정 사실을 공식 경로로 만들 수 없다. T-K1-9(다통화)·T-K1-11(환경 격리)은 DB 수준이 아니라 서비스 단위(정렬·행 키)·컨트롤러 단위(USD/LIVE 본문)·ADMIN 검사(정렬·중복)로만 확인하고, Reader의
  GROUP BY (environment, currency)는 rev 0.3a부터 P-1이 실제 실행 문장의 GROUP BY 키를 정확히 단언한다(MINOR-2 — 문장 모양 단언이지 LIVE·USD 사실의 실제 분리 결과는 아님). 참고: 시험 DB(MySQL 8.4 기본 `sql_mode`의 ONLY_FULL_GROUP_BY)에서는 SELECT의 environment·currency를
  GROUP BY에서 빼면 DB가 문장을 거부해(오류 1055) 기존 시험도 실패한다 — 이 단언은 sql_mode와 무관하게 키 누락·추가를 잡는다. DB 수준 확인은 시험 전용 LIVE/USD Simulator Route 설정 추가 여부를 정한 뒤 별도로 한다(root SQL fixture는 Test Fixture Policy 위반이라 쓰지 않음).
- L-6 기록 경로 전제(§3.6): RECON이 AUTHORIZED Payment에 승인·취소 사실을 기록하는 경로(예: RECON 기록 CANCEL)가 생기면 §3 정의(recorded_by 포함 여부)를 재정의해야 한다 — T-K1-18이 그때 실패한다.
- L-8 계약 golden 부재(rev 0.3a 기록, TXN-OPS-001 L-7 선례): PAYMENT `GET /internal/v1/admin/home-kpis` 응답 모양(`AdminHomeKpiController`, T-K2-3)과 ADMIN 허용 모양
  (`PaymentResponseShape.HOME_KPI`·`HomeKpiAnswer`, T-K3-5)은 golden 계약 파일 없이 두 쪽 시험이 같은 필드 목록·형식을 각각 단언한다. 한쪽만 바뀌면 ADMIN이 fail-closed로 503을 낸다
  (정보 노출 방향 아님). 기록만 — 필요하면 계약 golden 추가는 후속.
- L-9 (리뷰 NIT-3) required Port `HomeKpiReader`가 provided DTO `HomeKpiItem`을 반환한다. 저장소 관례: 조회 측 required Port가 provided 조회 모델을 그대로 반환하는 선례가 있다
  (`AdminPaymentReader` 등 — 별도 내부 모델·Mapper를 두지 않음, CQRS 조회 측은 비권위 읽기 모델). Domain 의존 방향(Domain → Spring/JPA/jOOQ 없음)은 그대로이며 바꾸지 않는다.
- L-10 (리뷰 NIT-4) ADMIN 분당 창(`HomeKpiService`)은 `System.currentTimeMillis()` 분 단위(거래 조회·가맹점 조회 창과 같은 방식). 주입 시계가 아니라 시험은 분 경계를 피하려고 상한의
  두 배 + 2회 시도한다(T-K3-3). 표시용 속도 제한이라 허용하고 기록만 한다.
- L-11 (리뷰 NIT-6) ADMIN 응답 검사는 `count ≤ 승인 건 수`를 다시 확인하지 않는다(ADMIN은 개별 승인 건 수를 모름 — 금액만으로는 판정 불가). PAYMENT 정의(§3.3 — 건수는 같은 창
  AUTHORIZATION 행의 부분집합)와 K-1 시험이 보장한다. ADMIN 검사는 approved 0 ⇒ count 0만 본다(§4.3). 추가 검사는 선택 사항으로 기록만.
- L-12 (리뷰 NIT-7) T-K2-2 timeout은 주입 시험이다: 서비스 단위(`HomeKpiQueryServiceSpec` — Reader가 `QueryTimedOut` → `OperatorQueryFailure.TimedOut`)와 컨트롤러 단위
  (`AdminHomeKpiControllerSpec` — 503 + `query_timeout{endpoint=home_kpi}`). Reader가 MySQL 시간 초과를 `QueryTimedOut`으로 바꾸는 부분은 `JooqHomeKpiReader`가
  `AdminReadLimits.guard`·`HINT`를 쓰는 코드(검토 대상)와 guard 자체의 기존 시험(`AdminQueryTimeoutSpec`)에 기댄다. Reader 문장에 실제 MySQL timeout을 일으키는 시험은 없다
  (대량 데이터 필요 — L-1 LIVE Gate).
