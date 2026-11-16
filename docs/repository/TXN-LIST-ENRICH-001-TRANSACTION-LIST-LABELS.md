# TXN-LIST-ENRICH-001 거래 목록 가맹점명·결제수단 표시 명세 (rev 0.1b)

- **rev 0.1b** (2026-11-16, Implementer — 구현 독립 리뷰 M-b·NIT-1 반영, Coordinator 지시. 업무 규칙·분류 기준 변경 없음, 자기 완료 판정 아님).
  M-b §5.1에 T-E2-12(M-1)를 **permit 격리 수준**(ADMIN `MerchantLabelGatewaySpec`·MASTER `MerchantAccountLabelApiSpec`)에서 단언한다는 기록과 이유 추가 · NIT-1 §5 ADMIN merchantops 문구를 §3.6·§5.1과 맞춤(별도 Port `MasterLabelGateway`, 라벨 전용 permit·read timeout — "조회 permit·timeout 공유"로 읽히던 표현 정정).
  같은 리뷰 M-a(코드): 라벨 응답 상한(64 KiB) 초과를 §8 분류대로 `UNAVAILABLE`로 고침(전에는 `UNREADABLE`로 분류) — 명세 문구는 원래 §3.3·§8 그대로.
- **rev 0.1a** (2026-11-16, Implementer — 명세 리뷰 REVIEW_PASS(조건부) 2026-11-15의 must-fix M-1..M-6·NIT N-1..N-5 반영, Coordinator 선택 적용. 업무 규칙 변경 없음, 자기 완료 판정 아님).
  M-1 라벨 전용 permit(ADMIN·MASTER 양쪽, Demo 값) + "라벨 busy가 승인 회복 상태를 바꾸지 않음" 시험(§3.6, T-E2-12) · M-2 라벨 전용 짧은 read timeout(Demo 값) + 지연 상한 단언(§3.6, T-E2-8·T-E3-3) ·
  M-3 상세 응답 계약 §4.5 · M-4 이름 검사 = MASTER `DisplayName` 규칙(§4.4) · M-5 §1.3 정정 · M-6 운영 홈 `limit: 5`(§5, T-E5-8) · N-1..N-5(§4.4·§5·§3.4·§12). Q-TXENRICH-1은 Coordinator가 "카드"로 확정(§10).
- **rev 0.1** (2026-11-15, Planner 초안 — Coordinator가 저장소에 기록. 코드·migration 없음). Reviewer 검토 전, 자기 완료 판정 아님.
- 요청(사용자, 2026-11-15 "가맹점명·결제수단 칸을 추가"): 관리자 콘솔 거래 목록(운영 홈 "최근 거래" 카드 — ADMIN `POST /api/payment-searches`, 거래 조회 화면 포함)에 **가맹점명**·**결제수단** 열 추가.
- 표기: 사용자 결정 = docs/DECISIONS.md. C-n = Coordinator 가역 선택. L-n = 위험·한계.
- 근거: D-ADMIN-UI-004 ③⑤, D-MCODE-001·002·003, D-TXOPS-001~003, D-MOPS-001·002, CLAUDE.md 소유권(MASTER/PAYMENT/FINANCIAL 별도 DB, cross-DB SQL·공유 Entity 금지), TXN-OPS-001 rev 0.2, MERCHANT-OPS-001, MERCHANT-CODE-001 rev 0.4.5(§3.8·§6, MC-1b·MC-4), ADMIN-HOME-KPI-001(형식·배치 조율), ARCHITECTURE-CONVENTIONS §14·§21, Test Fixture Policy.

## 0. 한눈에

| 항목 | 결정·제안 |
|---|---|
| 무엇 | 거래 목록 항목에 **결제수단**과 **가맹점 표시 "상호 · 가맹점 코드"** 추가. 조회 전용, 금융 효과 없음 |
| 결제수단 원천 | PAYMENT `payment.payment_method`(PAYMENT 소유, V1 `ck_payment_method IN ('CARD')`) → PAYMENT 목록 응답 `paymentMethod` |
| 가맹점 원천 | MASTER `merchant_account`(MID → merchant) + `merchant.display_name` + 현재 코드(`MasterSqlText.currentMerchantCode`) → **새** MASTER 서명 endpoint `POST /internal/v1/admin/merchant-account-labels` |
| 붙이는 곳 | **ADMIN**(`TransactionLookupService.search`) — PAYMENT 답의 고유 MID ≤ 50개를 MASTER에 **한 번** 묶어 조회(N+1 없음). cross-DB SQL 없음 |
| 실패 동작 | MASTER 불가·바쁨·모양 위반 → **목록은 그대로**, `merchantLabels: "UNAVAILABLE"`, 항목 `merchant: null`(화면: MID만 + 안내 1줄). PAYMENT 실패는 지금처럼 503 |
| 내부 키 | `mer_`·`biz_`는 MASTER 응답에도 없음 → ADMIN·화면에 들어오지 않음(D-MCODE-003) |
| 코드 변경 | 현재 코드 표시. MID가 있는 가맹점은 코드가 이미 불변(MERCHANT-CODE-001 §6) |
| 결제수단 표기 | 기존 라벨 "카드"(`labels.ts`) — CARD에 체크카드 포함이라 "신용카드"는 쓰지 않음(C-3, Q-TXENRICH-1 확인 대상) |
| 권한·감사 | 바뀌지 않음(VIEW 4역할, 검색·가맹점 목록 미감사 D-TXOPS-002·D-MOPS-002) |
| 캐시·migration | 없음 |
| 배치 | E-1 PAYMENT `paymentMethod` + ADMIN 목록 모양 → E-2 MASTER 라벨 endpoint + ADMIN client → E-3 목록에 붙이기 → E-4 상세(권장) → E-5 화면. ADMIN-HOME-KPI-001 K-3·K-4, ADMIN-UI-WORKSPACE-001 2단계 뒤(§13) |

## 1. 현재 상태(코드 확인)

### 1.1 거래 목록(TXN-OPS-001)
- ADMIN `TransactionController` `POST /api/payment-searches` → `TransactionLookupService.search`(역할 → 형식 → actor별 분당 창 `lookup-per-minute` → `PaymentReadGateway.searchPayments`, 감사 없음).
- `HttpPaymentOpsGateway.searchPayments`: `lookup` permit, DB TX 안 호출 거부, `PaymentResponseShape.LIST` 정확 키 집합(모르는 키 → 503), `answers()`.
- 목록 항목 10키: `paymentId, environment, merchantAccountId, merchantOrderId, orderNamePreview, orderNameTruncated, amountMinor, currency, status, createdAt`. 가맹점명·코드·결제수단 없음(상세 `DETAIL.payment`에는 `paymentMethod` 있음).
- PAYMENT `JooqAdminPaymentReader.search`: 모드별 FORCE INDEX, Payment 행 본문을 이미 읽음 → `p.PAYMENT_METHOD` 추가로 계획 불변.
- `limit` 1..50(기본 20). 운영 홈 "최근 거래"는 최근 7일(KST) 5건.

### 1.2 결제수단(PAYMENT 소유)
- `payment.payment_method VARCHAR(16)`, `ck_payment_method IN ('CARD')`, domain `enum PaymentMethod { CARD }`. 승인 행 `authenticated_card_type ∈ {CREDIT, CHECK}` → CARD = 신용 + 체크. 목록에 card type은 넣지 않음.
- D-ADMIN-UI-004 ⑤: 지금은 카드만, 화면은 수단 목록 구조.

### 1.3 가맹점(MASTER 소유)과 ADMIN의 MASTER 읽기
- MASTER `merchant_account(merchant_account_id PK, merchant_id, …)`, MID `^mid_<ULID>$`. `merchant.display_name VARCHAR(100)` — rev 0.1a(M-5) 정정: runtime 계정에 UPDATE 권한은 **있다**(V2 runtime privilege). 상호를 바꾸는 **변경 명령·Domain 경로가 없다**(L-3).
- 현재 코드: `merchant_code_assignment` 마지막 seq(`MasterSqlText.currentMerchantCode`).
- MASTER ADMIN 채널(`MerchantOpsController`): `merchant-searches`, `merchants/{id}`, 이력 — **MID 조회·여러 merchantId 묶음 조회 없음**.
- ADMIN `HttpMasterOpsGateway`: 서명, 조회 permit(대기 0), timeout, 256 KiB, `MasterResponseShape`(정확 키 fail-closed). 다른 모듈은 `merchantops.application.provided`만 사용.
- PAYMENT `entitlement_mid_owner`는 권리 판정용 투영(상호 없음) — 표시 원천 아님.

### 1.4 진행 중인 다른 작업(충돌 지점)
- ADMIN-HOME-KPI-001: ADMIN `HttpPaymentOpsGateway`·`PaymentOpsConfiguration`·`application.yml`·`StubPaymentOpsServer`·`PaymentResponseShape`, PAYMENT `ArchitectureSpec`·`CancelFixture`, K-4 `HomeScreen.tsx`·`adminApi.ts`.
- ADMIN-UI-WORKSPACE-001: 프론트 다수.
- MERCHANT-CODE-001 MC-3/MC-4·MERCHANT-BIZ-LOOKUP-001(예정): MASTER ADMIN 채널·`MasterResponseShape`·`HttpMasterOpsGateway`.

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| D-ADMIN-UI-004 ③ / D-MCODE-003 | 가맹점 표시 "<상호> · <가맹점 코드>", `mer_` 없음 |
| D-MCODE-001 | 코드 정규식 `^[a-z][a-z0-9]{5,13}$` |
| D-MCODE-002 | 1회 변경 → 현재 코드 표시(§3.4) |
| D-ADMIN-UI-004 ⑤ | 수단 목록 구조, 새 수단 추가 없음 |
| D-TXOPS-002·003 | 검색 미감사, 주문명 10자 규칙 불변 |
| D-MOPS-001·002 | 가맹점 이름 VIEW 4역할, 미감사 |
| CLAUDE.md 소유권 | 합치기는 ADMIN 메모리, cross-DB 없음 |

## 3. 설계 결정

### 3.1 어디서 붙이나 — (A) 채택
- (A) PAYMENT 결제수단 + ADMIN이 MASTER에 MID 묶음 조회 — **채택**(각 원천 그대로, 쪽당 MASTER 1회, 장애 시 이름만 빠짐).
- (B) PAYMENT 가맹점 Snapshot — 기각(상호 없음, 결제 생성 금융 경로·Snapshot v5·golden 변경 필요).
- (C) ADMIN 라벨 사본 표 — 기각(동기화·대사 필요, 캐시 없음과 같은 이유).
- MID 기준 조회: PAYMENT 행에는 MID만 있고 MID 소속은 MASTER가 원천·불변. merchantId를 PAYMENT에서 내보내면 `mer_`가 ADMIN까지 들어옴. 라벨은 환경 무관.

### 3.2 N+1 방지와 상한
- ADMIN이 `items[].merchantAccountId` 고유값을 정렬해 모음. `^mid_<ULID>$`만 MASTER에 보내고, 형식 밖은 그 항목만 `merchant: null`.
- 검색 1회 = PAYMENT 1 + MASTER ≤1. 고유 MID ≤ 50. 0개면 MASTER 미호출. "다음 쪽"은 쪽마다.
- MASTER 본문: 1..50개, 중복 금지(위반 400 — ADMIN은 만들지 않음, 단언 시험).

### 3.3 실패 동작(목록 우선, 틀린 값 없음)
| MASTER 결과 | ADMIN 응답 |
|---|---|
| 200 + 모양·대응 통과 | `merchantLabels: "OK"`, 찾은 MID `merchant: {name, code}`, 못 찾은 MID `merchant: null` |
| busy, timeout, 연결 실패, 5xx, 401, 400, 상한 초과, 모양 위반, 요청에 없는 MID, 중복 | `merchantLabels: "UNAVAILABLE"`, 모든 항목 `merchant: null`, **HTTP 200 목록 그대로** |
| PAYMENT 실패 | 지금처럼 503 `PAYMENT_UNAVAILABLE`(MASTER 미호출) |
- 라벨 검사는 전부 아니면 전무(일부 이상 → 라벨 전체 버림 — 틀린 상호가 다른 거래에 붙는 일 방지).

### 3.4 표시 규칙
- 가맹점: `"<name> · <code>"`, 아래 작은 글씨 `환경 · MID`. OK + null → "가맹점 정보 없음" + MID. UNAVAILABLE → 표 아래 안내 1줄 "가맹점 정보를 불러오지 못했어요" + 칸은 MID만(C-5).
- 코드: MASTER 현재 코드. 거래가 있는 MID는 첫 계약 승인 뒤 발급, 코드 변경은 그 전만(D-MCODE-002) → 거래 시점 코드 = 현재 코드. (N-5) 예외·한계는 MERCHANT-CODE-001 L-6(코드 변경 경계)을 따른다 — 이 화면은 "현재 코드"를 표시할 뿐 거래 시점 코드를 따로 저장하지 않는다.
- 상호 변경 경로 없음. 생기면 "현재 상호" 표시가 의미(L-3).
- 결제수단: `labelOf(paymentMethod)`(`labels.ts` `CARD: '카드'`), 모르는 코드는 원문.
- React 텍스트 노드로만, CSP 불변.

### 3.5 캐시 — 없음(C-2).

### 3.6 라벨 호출 격리(rev 0.1a — M-1·M-2, Coordinator 선택)
- **전용 permit(M-1)**: 라벨 조회는 기존 MASTER 조회 permit(목록·상세·이력·**2인 승인 결정 조회 `approvalDecision` — 승인 회복 경로**)을 쓰지 않는다.
  - ADMIN `paycore.admin.master-ops.label-permits`(Demo **2**, 1..16, 기본값 없음 — `application.yml`에 Demo 값 기재, 환경변수 변경 불필요): `HttpMasterOpsGateway` 전용 `Semaphore`(대기 0 — 없으면 MASTER를 부르지 않고 Unavailable `MASTER_LABELS_BUSY`).
  - MASTER `paycore.master.admin-channel.label-permits`(Demo **2**, 1..16, 기본값 없음 — master `application.yml`에 Demo 값 기재): `AdminChannelPermits.labels(...)` 별도 `Semaphore`(busy 503 `ADMIN_READ_BUSY`, 지표 `busy{endpoint="merchant_account_labels"}`). MASTER `approval-decisions`·목록·상세는 기존 read permit 그대로.
  - 시험 T-E2-12: 라벨 permit이 모두 잡혀 있어도(ADMIN·MASTER 각각) `approvalDecision`·가맹점 목록 호출은 permit을 얻고, 승인 회복 Use Case의 상태(OwnerExecution/승인 요청 행)는 라벨 busy로 바뀌지 않는다. 역방향(조회 permit 소진 → 라벨은 동작)도 단언.
- **전용 짧은 read timeout(M-2)**: ADMIN `paycore.admin.master-ops.label-read-timeout`(Demo **2s**, 기본값 없음, `read-timeout` 이하 — 넘으면 기동 실패). 라벨 호출만 쓰는 별도 HTTP client(같은 connect timeout·프록시 없음·redirect NEVER·같은 서명기).
  - 목록 지연 상한: PAYMENT 응답 시간 + `connect-timeout` + `label-read-timeout`(MASTER 지연이 목록을 기존 10s까지 붙잡지 않는다). 시험 T-E2-8(gateway — MASTER가 응답을 늦추면 label-read-timeout 근처에서 Unavailable `TIMEOUT`, 상한 단언)·T-E3-3(목록 HTTP — MASTER 지연에도 200 + UNAVAILABLE이 상한 안에 온다).
- 두 값 모두 **Demo 값, 운영값 아님**(LIVE Gate). C-4 "MASTER 조회 permit 공유"와 L-4는 이 절로 대체한다.

## 4. API

### 4.1 PAYMENT `POST /internal/v1/admin/payment-searches` (변경)
- 항목에 `paymentMethod` 추가(11키, `currency` 뒤·`status` 앞 순서 고정). SELECT `p.PAYMENT_METHOD`, WHERE·색인 불변.

### 4.2 MASTER `POST /internal/v1/admin/merchant-account-labels` (신규)
- ADMIN 서명 v1 채널(query 금지, 16 KiB), 라벨 전용 permit `AdminChannelPermits.labels("merchant_account_labels")`(rev 0.1a §3.6 — 기존 read permit 아님).
- 요청(엄격): `{"merchantAccountIds": ["mid_…", …]}` 1..50, 각 `^mid_[0-9A-HJKMNP-TV-Z]{26}$`, 중복 금지 → 위반 400 `REQUEST_MALFORMED`.
- 200: `{"items": [{"merchantAccountId": "mid_…", "merchantName": "데모상점", "merchantCode": "demoshop01"}]}` — MID 오름차순, 없는 MID는 빠짐, TERMINATED 포함.
- 넣지 않음: `merchantId`·`businessId`·사업자 정보·lifecycle·등록번호·기관 참조.
- SQL 1문장 RO·RR: `merchant_account` PK IN → `merchant` PK → 현재 코드. 코드 없는 가맹점은 결함 → 503.
- 오류 401/400/503, `no-store`, 로그는 분류·항목 수만.

### 4.3 ADMIN `POST /api/payment-searches` (화면 응답 변경)
- 항목에 `paymentMethod`, `merchant: {name, code} | null`; 최상위 `merchantLabels: "OK" | "UNAVAILABLE"`(항목 0개면 OK, MASTER 미호출). 오류 코드·권한·속도 제한 불변.
- (rev 0.1a 확정) 키 순서: 항목 = PAYMENT 11키(순서 그대로) 뒤 `merchant`(12키), 최상위 = `items`, `nextCursor`, `merchantLabels`.

### 4.4 ADMIN 검사(fail-closed)
- `PaymentResponseShape.LIST` + `"paymentMethod" to CODE`(`^[A-Z][A-Z0-9_]{0,47}$`, 열거 고정 안 함).
- 새 `MasterResponseShape.MERCHANT_ACCOUNT_LABELS`: 정확 키, MID 형식, 코드 `^[a-z][a-z0-9]{5,13}$`, items ≤ 50.
- (M-4) 이름 = MASTER `DisplayName` 규칙과 같은 검사: 1..100 **code point**, ISO 제어문자(`Character.isISOControl` — U+0000..U+001F, U+007F..U+009F)만 금지, 앞뒤 공백 금지, 올바른 UTF-16. U+2028·U+2029는 **허용**(MASTER가 저장할 수 있는 값을 ADMIN이 이유 없이 버리지 않는다). `MasterResponseShape.Text`에 code point 길이 인자를 더한다. 대조 시험: 100 code point(보충 문자 포함) 통과·101 거부, U+2028 통과, U+0085·U+0007 거부.
- (N-3) 라벨 응답 해석 mapper는 `STRICT_DUPLICATE_DETECTION`(라벨 경로만 — 기존 MASTER 조회 경로 동작 불변). 중복 키 → 해석 불가 → UNAVAILABLE.
- (N-4) MID 형식 `^mid_[0-9A-HJKMNP-TV-Z]{26}$`은 모듈마다 **한 상수**(MASTER 요청 해석기·ADMIN gateway·enricher가 같은 상수를 참조 — 모듈 경계 밖 공유 없음).
- 대응: 항목 수 ≤ 보낸 수, 모든 MID ∈ 보낸 집합, 중복 없음, 오름차순. 위반 → 라벨 전체 UNAVAILABLE.

### 4.5 ADMIN `GET /api/payments/{paymentId}` (E-4 상세 — rev 0.1a M-3)
- 200 본문 = 기존 PAYMENT 상세 모양(`PaymentResponseShape.DETAIL`, 키·순서 불변) + `payment.merchant: {name, code} | null`(payment 객체 **끝**) + 최상위 `merchantLabels: "OK" | "UNAVAILABLE"`(최상위 **끝**).
- 값: MASTER 라벨 200·대응 통과 + 그 MID 있음 → `merchant` 값, `merchantLabels: "OK"`; MID 없음 → `merchant: null`, `"OK"`; MID 형식 밖 → MASTER 미호출, `merchant: null`, `"OK"`; MASTER 실패(§3.3 표 전부) → `merchant: null`, `"UNAVAILABLE"`.
- 순서: PAYMENT 상세 → (FOUND일 때만) 라벨 조회(실패해도 진행, DB TX 밖) → `PAYMENT_VIEWED` 감사(로컬 TX) → 반환. 감사 실패 → 503 `AUDIT_UNAVAILABLE`(라벨·상세 미반환). NOT_FOUND → MASTER 0회, 404 + 감사(기존). PAYMENT 불가 → 503(MASTER 0회, 감사 없음 — 기존).
- 감사 행·감사 문구에 상호·코드 없음(targetRef = paymentId, 기존).

## 5. 구조(ARCHITECTURE-CONVENTIONS §14)
- PAYMENT: `AdminPaymentListItem.paymentMethod`, Reader SELECT 1열, 컨트롤러 JSON 1키.
- MASTER `com.paycore.master.merchant`: provided `MerchantAccountLabelQuery` + `MerchantAccountLabel`; required `MerchantAccountLabelReader` → `JooqMerchantAccountLabelReader`; web 새 `MerchantAccountLabelController` + `MerchantAccountLabelRequestParser`; ArchUnit(쓰기 금지 query adapter, ADMIN 채널 목록, 호출자 제한); golden `merchant-ops-response-shapes-v1.golden.json`에 `accountLabels` 표본.
- ADMIN merchantops: provided `MerchantLabelClient.labels(ids): MerchantLabelResult`(Found·Unavailable); required **별도 Port** `MasterLabelGateway.accountLabels(ids)`(rev 0.1b 정정 — `MasterOpsGateway`에 더하지 않음, §5.1) → `HttpMasterOpsGateway`(같은 서명기·키, **라벨 전용 permit·라벨 전용 read timeout**(§3.6 — 조회 permit·timeout과 공유하지 않음), 응답 상한 64 KiB, TX 안 호출 거부); `MasterResponseShape.MERCHANT_ACCOUNT_LABELS`; ArchitectureSpec 사용처 제한.
- ADMIN paymentops: `TransactionLookupService.search` → `TransactionListEnricher`(순수 함수), `PaymentResponseShape.LIST` + `paymentMethod`, 지표 `TransactionOpsMetrics.merchantLabels(result)`.
  - (N-5) 서비스 이름은 그대로 `TransactionLookupService`. `TransactionListEnricher`는 **`paymentops.application`**(Spring·HTTP·JSON 라이브러리 없음, Map 문서와 라벨 값만 다루는 순수 Kotlin object — domain이 아닌 이유: 화면 응답 문서 모양을 아는 application 조립 규칙). 새 모듈 의존 `paymentops.application → merchantops.application.provided`(`MerchantLabelClient`·`MerchantLabelResult`만, 한 방향 — merchantops는 paymentops를 모른다). 두 `provided/package-info.java` 설명을 이에 맞게 고친다(키·permit·장애 격리는 §3.6 전용 permit·timeout으로 유지).
  - ArchUnit: `MerchantLabelClient`는 `TransactionLookupService`와 merchantops 내부만 사용, paymentops는 merchantops의 domain·adapter·required에 의존하지 않음, merchantops는 paymentops에 의존하지 않음.
- 프론트: `adminApi.ts` 타입, 새 `lib/merchantLabel.ts`, `HomeScreen.tsx` 최근 거래 열(주문/결제 ID | 가맹점 | 결제수단 | 거래일시 | 상태 | 금액), `TransactionsScreen.tsx` 같은 열, `fakeApi.ts`.
  - (M-6) 운영 홈 "최근 거래" 요청은 `limit: 5`를 보낸다(지금은 limit 없이 기본 20건을 받아 5건만 표시 — PAYMENT·MASTER 읽기 낭비). `HomeScreen.test.tsx` 호출 단언을 바꾸고 `기대값 변경` 주석으로 기록(T-E5-8).
  - 열 표시는 기존 workspace 디자인(`workspace.css` 클래스·토큰)만 쓴다 — 새 색은 라이트·다크 모두 WCAG AA(본문 4.5:1) 토큰에서만, 런타임 style 속성 없음(noRuntimeStyles).

### 5.1 구현 중 가역 결정(rev 0.1a, Implementer 기록 — 업무 규칙 불변)
- ADMIN 라벨 호출 Port는 `MasterOpsGateway`에 메서드를 더하지 않고 별도 required Port `MasterLabelGateway`(구현은 같은 `HttpMasterOpsGateway` — bean 반환 타입을 구현 타입으로, `PaymentOpsConfiguration`과 같은 방식). 이유: 조회·명령 Port를 쓰는 서비스·시험 대역이 라벨을 모르게(ISP).
- ADMIN MID 형식·상한 단일 상수는 `merchantops.application.provided.MerchantAccountIdRules`(N-4) — `MerchantLabelClient` 사용처 ArchUnit 제한이 상수 참조까지 막지 않게 분리.
- MASTER는 MID 형식을 Domain 값 객체 `MerchantAccountId` 한 곳으로 검사(N-4), 상한은 provided `MERCHANT_ACCOUNT_LABELS_MAX`.
- MASTER 라벨 SQL은 `merchant_account` `FORCE INDEX(PRIMARY)` — T-E2-7 RED(2026-11-16): 작은 표에서 optimizer가 `uk_merchant_account_merchant` 전체 색인 스캔(type=index)을 골랐다. PAYMENT TXN-OPS-001과 같은 방식.
- (rev 0.1b, 리뷰 M-b) T-E2-12(M-1)는 **permit 격리 수준**에서 단언한다: ADMIN `MerchantLabelGatewaySpec`(라벨 permit을 모두 잡은 채 `approvalDecision`·가맹점 목록이 permit을 얻어 정상 답 + 라벨은 MASTER 미호출 BUSY, 역방향 조회 permit 소진 → 라벨 Found — 순서는 대역 latch로 맞춤), MASTER `MerchantAccountLabelApiSpec`(라벨 busy ↔ approval-decisions·상세, read busy ↔ 라벨).
  이유: 승인 회복 Use Case가 MASTER 쪽에서 쓰는 것은 `approvalDecision` 호출 하나뿐이고 그 호출은 라벨 `Semaphore`를 참조하지 않는다(별도 객체, 코드상 공유 경로 없음). 그래서 "라벨 busy가 회복 상태(OwnerExecution/승인 요청 행)를 바꾸지 않는다"는 "라벨 busy 중에도 `approvalDecision`이 permit을 얻어 같은 답을 돌려준다"로 환원된다 — 같은 답을 받은 Use Case의 상태 전이는 기존 승인 회복 시험(`MerchantOpsRecoverySpec`·`OwnerExecutionRecoverySpec` — 결정 응답별 회복, `MasterApprovalGatewaySpec`·`MasterApprovalGatewayB3Spec` — 결정 응답 해석)이 이미 덮는다. Use Case 수준 DB 단언은 MASTER·ADMIN 두 앱과 실 DB를 함께 띄워야 해 비용 대비 추가 증거가 작다고 판단했다(가역 — Reviewer가 필요하다고 보면 후속 시험으로 추가).
- 화면 E-5: 상세 화면의 라벨 표시는 이 단계 범위 밖(타입만 선택 필드로 추가) — 목록(운영 홈·거래 조회)만 표시. 거래 조회 화면은 아직 workspace 디자인으로 옮기지 않은 화면이라 그 화면의 기존 클래스(`text-xs`·`text-muted-foreground`)를 쓴다.

## 6. 권한·감사·노출
- 권한 불변(ACTIVE + VIEW 4역할, 아니면 403 — PAYMENT·MASTER 미호출). 같은 4역할이 가맹점 목록에서 상호·코드를 이미 봄 → 새 노출 없음.
- 감사 불변(검색·라벨 미감사, 감사 행 0 시험). 상세(E-4): PAYMENT 상세 → 라벨(실패해도 진행) → `PAYMENT_VIEWED` 감사 → 반환.
- 로그·지표 label에 MID·상호·코드·paymentId 없음.

## 7. 8대 원칙
| 원칙 | 적용 |
|---|---|
| Financial Consistency | 금액·상태는 PAYMENT 값, 라벨은 보조 표시값 |
| Transaction Safety | 쓰기 없음, 외부 호출 모두 ADMIN DB TX 밖, MASTER RO·RR |
| Idempotency | 읽기 |
| Concurrency Control | 대기 0 permit, 잠금 없음 |
| Recoverability | MASTER 실패 = UNAVAILABLE 값, 다음 조회 회복 |
| Reconciliation | 해당 없음 |
| Auditability | 기존 규칙 유지 |
| Observability | §8 |

## 8. Observability
- ADMIN counter `paycore.admin.ops.transaction_merchant_labels{result}`(OK·SKIPPED·BUSY·UNAVAILABLE·UNREADABLE), 로그 항목 수만.
  - (rev 0.1a 확정) 분류: OK = 라벨 통과, SKIPPED = 보낼 MID 0개(MASTER 미호출), BUSY = ADMIN 라벨 permit 없음 또는 MASTER 503 `ADMIN_READ_BUSY`, UNREADABLE = 200인데 모양·대응 위반, UNAVAILABLE = 그 밖(timeout·연결·401·400·5xx·상한 초과). 상세(E-4)도 같은 지표.
- MASTER `…admin_channel.busy{endpoint="merchant_account_labels"}`.

## 9. 성능
- PAYMENT 열 1개 추가, TXN-OPS-001 계획 시험 그대로 통과.
- MASTER: `merchant_account` PK range(IN ≤ 50) → `merchant` eq_ref → assignment PK. 계획 시험 P-1(표본 ≥5 MID 공식 경로, ANALYZE TABLE, type=ALL 없음, key 단언).
- 지연: PAYMENT + MASTER 순차(L-1).

## 10. 사용자 질문
- 금융·보안 미결 없음.
- Q-TXENRICH-1(확인, 막히는 것 없음): 결제수단 표기 — 권장 "카드"(CARD = 신용 + 체크), 대안 "신용카드"(체크카드 결제를 신용카드로 잘못 표시). **rev 0.1a: Coordinator가 "카드"로 확정**(표시 문구 — 금융 의미 변경 없음).

## 11. Coordinator 결정(가역)
- C-1 (A), MID 기준 MASTER 묶음 조회, `mer_` 없음. C-2 캐시 없음. C-3 결제수단 라벨 "카드". C-4 라벨 조회 별도 창 없음(검색 창이 상한) — ~~MASTER 조회 permit 공유~~ rev 0.1a: 라벨 전용 permit·timeout(§3.6). C-5 UNAVAILABLE 안내 표 아래 1줄. C-6 상세 라벨(E-4) 포함. C-7 가맹점 코드·이름 필터 검색은 범위 밖(후속).

## 12. 시험·수용 기준(TDD — 실 MySQL 8.4, 공식 경로 Named Fixture, Fixture Monkey 미사용)
rev 0.1a 추가·보강: T-E1-4(N-1) 새 golden `admin-payment-list-v1.golden.json`은 PAYMENT 원본·ADMIN 사본 두 곳, ADMIN 시험이 **바이트 동일** 단언. T-E2-10(N-2) MASTER golden 원본·ADMIN 사본 동시 갱신(`accountLabels` 표본), `MerchantOpsShapeGoldenSpec` 설명 문구 갱신. T-E2-8·T-E3-3 지연 상한 단언(M-2). T-E2-12(M-1) 라벨 busy ↔ 승인 회복·조회 permit 격리. T-E2-9 이름 규칙 대조(M-4)·중복 키(N-3). T-E3-1(N-5) 사용자 입력 MID(검색 조건 `merchantAccountId`)가 아니라 **PAYMENT 응답 항목의 MID만** MASTER에 보낸다는 단언. T-E5-8(M-6) 운영 홈 검색 호출 본문 `limit: 5`.
E-1: T-E1-1 PAYMENT 11키·순서·`CARD`(바이트 단언 갱신), T-E1-2 TXN-OPS-001 계획 시험 불변, T-E1-3 ADMIN LIST 11키 통과·누락/형식 위반/10키 → 503, T-E1-4 교차 golden(`admin-payment-list-v1.golden.json`).
E-2: T-E2-1 MASTER 3 MID → 오름차순·이름·현재 코드(MC-1b 변경 가맹점은 새 코드), T-E2-2 없는 MID 빠짐·TERMINATED 포함, T-E2-3 본문 엄격성 400, T-E2-4 401/400/503+지표, T-E2-5 비노출(`mer_`·`biz_`·등록번호·기관 참조 없음, 대조군), T-E2-6 쓰기 없음, T-E2-7 계획 P-1, T-E2-8 ADMIN gateway(서명 golden, TX 거부, permit busy, timeout·5xx·과대 → Unavailable), T-E2-9 모양·대응 위반 → Unavailable, T-E2-10 교차 golden, T-E2-11 ArchUnit·Modulith.
E-3: T-E3-1 고유 MID 정렬 1회 호출(0개 → 미호출), T-E3-2 형식 밖 MID 항목만 null, T-E3-3 MASTER 실패 각각 → 200 + UNAVAILABLE + 지표, T-E3-4 PAYMENT 503 → MASTER 0회, T-E3-5 권한, T-E3-6 감사 0·로그 비노출, T-E3-7 순서·TX 밖, T-E3-8 응답 키 정확·`mer_` 0건, T-E3-9 속도 창 1만 소비.
E-4: T-E4-1 상세 라벨·실패해도 감사 후 200, T-E4-2 감사 실패 → 아무 값도 반환 안 함, T-E4-3 NOT_FOUND → MASTER 0회.
E-5: T-E5-1 "데모상점 · demoshop01" + `LIVE · mid_…` + "카드", T-E5-2 null/UNAVAILABLE 표시, T-E5-3 모르는 수단 원문, T-E5-4 거래 조회 쪽별 라벨 상태, T-E5-5 DOM `mer_`·`biz_` 0건, T-E5-6 `<script>` 텍스트 표시, T-E5-7 VIEWER 표시·UNMASK 미표시.
수용: 위 전부 + GRADLE-CHECK·HARNESS-SELFTEST·frontendTest PASS, skip 없음, 기존 시험 약화 없음.

## 13. 배치와 순서
| 배치 | 내용 | 착수 조건 |
|---|---|---|
| E-1 | PAYMENT `paymentMethod` + ADMIN LIST 모양 + golden(한 배포 단위) | ADMIN-HOME-KPI-001 K-3 커밋 뒤 |
| E-2 | MASTER 라벨 endpoint + ADMIN merchantops client + golden | MERCHANT-BIZ-LOOKUP-001·MC-3/MC-4 배치와 동시 진행 금지 |
| E-3 | `TransactionLookupService` 붙이기·지표 | E-1·E-2 뒤 |
| E-4 | 상세 붙이기 | E-3 뒤 |
| E-5 | 화면 | ADMIN-UI-WORKSPACE-001 2단계·ADMIN-HOME-KPI-001 K-4 커밋 뒤 |
- migration 없음. 참고: PAYMENT V35는 ADMIN-HOME-KPI-001(색인)이 사용 — MERCHANT-CODE-001 MC-4 명세의 V35는 구현 때 다음 번호로 재조정(Coordinator 기록).

## 14. 위험·한계
- L-1 지연: 목록 = PAYMENT + MASTER 순차.
- L-2 배포 단위: 정확 키 집합 → E-1은 PAYMENT·ADMIN 동시 배포.
- L-3 현재 상호·코드 표시(상호 변경 경로 생기면 과거 거래에도 새 상호).
- L-4 ~~MASTER 조회 permit 공유~~ rev 0.1a: 라벨 전용 permit(§3.6) — 라벨끼리만 경합(지표 관찰). 다중 ADMIN 인스턴스 합계가 MASTER `label-permits`를 넘으면 라벨만 UNAVAILABLE(목록 영향 없음).
- L-5 MID 형식 밖 값은 이름 없이 표시.
- L-6 다중 인스턴스 속도 제한 합산은 LIVE Gate.
