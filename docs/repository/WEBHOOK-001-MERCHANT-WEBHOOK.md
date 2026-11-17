# WEBHOOK-001 가맹점 서명 Webhook 전달 명세 (rev 0.3 — rev 0.2 + MERCHANT-CODE-001 MC-4 가산 필드 `merchantCode`)

> **rev 0.3 갱신(2026-11-17, MERCHANT-CODE-001 MC-4 — Coordinator 결정 C-36(MC-4), 새 정책 없음)**: 봉투 최상위에 출력 전용 **가산 필드 `merchantCode`**(사람이 읽는 가맹점 코드 — D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8)를
> `merchantAccountId` 바로 뒤에 둔다. 코드가 전파되지 않은 MID(권리 Snapshot v1~v3 head·빈 head)는 **키를 생략**한다(null을 쓰지 않는다). **`schemaVersion`은 1 그대로**다 — §4.2.1 "추가 필드는 가산적, 의미 변경은 새 버전"
> 규칙이며 V21 CHECK `schema_version = 1`도 그대로다. 나머지 키·순서·값 의미 불변, 이미 저장된 이벤트는 그 바이트 그대로 재전송한다(WH-08 — 승격 전에 만든 이벤트는 코드 없이 재전송).
> 가맹점 수신 측은 모르는 키를 무시해야 한다(가산 규칙). 내부 키(`mer_`·`biz_`)는 본문에 없다. 근거·시험: MERCHANT-CODE-001 §6·§6.1①, `MerchantWebhookDeliveryDbSpec`(C-36 묶음)·`MerchantWebhookBodyJsonSpec`.

> **rev 0.2 갱신(2026-10-21)**: §17 질문 8개(Q-WH-01~07 + 분할 Q-WH-08)는 모두 권장안으로 답변됐다 — **D-WH-001~008**(`docs/DECISIONS.md`). 명세 검토 spec-1(CHANGES_REQUIRED: H-1~H-3, m-1~m-9, n-1~n-8)과
> Coordinator §18 판정을 반영했다. **이 머리말과 §4·§5·§17·§18·§21의 rev 0.2 문장이 rev 0.1 문장보다 우선한다.** 요점:
> - **H-1**: `HELD_DECISION` 상태는 없다(D-WH-001 — AUTHORIZED Payment의 운영자 보정 취소·취소 실패는 `payment.cancelled`/`payment.cancel_failed`, `initiator: OPERATOR`, `merchantCancelId` 없음). §4.5는 폐기.
>   분할 확정(D-WH-008): **WEBHOOK-ENDPOINT-001** BACKLOG 등록(endpoint 등록·비밀 발급/교체/폐기·수동 재전송), ADMIN-BOOT-001 AC에 GAVE_UP 조회·재전송 진입점·LIVE endpoint 2인 승인 추가(Coordinator 적용).
> - **H-2**: `PaymentCancelFailed` payload에는 initiator가 없다(`OutboxPayloadJson` 취소 실패 맵) → initiator와 `merchantCancelId`는 불변 `payment_cancellation.initiator_kind`·`merchant_cancel_id`에서 읽는다(F-8·WH-07).
>   `PaymentCancelFailed` + Payment FAILED/EXPIRED(P1 운영자 취소 실패) → SUPPRESSED(§4.1 행 추가).
> - **H-3·m-3(구현 결정 WH-10 대체)**: fan-out은 기존 `LocalTransactions`(READ COMMITTED) 위에서 **행마다 한 로컬 TX**로 돈다. 후보는 비잠금 읽기, 각 행 TX는 guard 행 `FOR UPDATE`로 fan-out 쓰기를 직렬화하고
>   Outbox는 **PK UPDATE**(`merchant_notification_*` 열만)로 표시한다 — Outbox 행·인덱스 범위를 잠그지 않으므로 업무 TX의 Outbox INSERT는 fan-out을 기다리지 않는다(시험 C-F2).
>   분류 이상(예: Payment가 AUTHORIZING인데 PaymentAuthorized)·읽기 불가 payload·결함은 그 행만 비종결 **`FANOUT_FAILED`**(재시도 시각 있음, 오류 지표·경보)로 두고 같은 Payment의 뒤 행만 막는다 —
>   다른 Payment는 계속 처리한다. 이상 행은 절대 SUPPRESSED가 되지 않는다(fail-closed). "rev 0.1의 회차 전체 정지"는 폐기.
> - **m-1(Coordinator 판정)**: 본문은 GET 응답의 **엄격한 부분집합**(필드 이름도 GET과 같다): `failedAt`·`expiredAt`·취소 실패 시각 없음, 봉투 `createdAt`은 업무 시각이 아니라 **가맹점 이벤트 생성(fan-out) 시각**,
>   FAILED의 `authorizationId`는 GET(`GetPaymentController.authorization`)이 노출하므로 포함. §4.2 rev 0.2 표, 시험 T-C2b(GET ⊇ Webhook, 종류별).
> - **m-2·n-1·n-3·n-4**: 시도 번호는 claim TX에서 `attempt_count`를 올려 예약하고 결과 TX는 그 번호로 시도 행을 INSERT(lease 인수 시 두 시도 행 모두 남음). 소유를 잃은 worker의 2xx도 DELIVERED로 표시할 수 있다(성공 단조).
>   claim·lease 시각은 DB가 아니라 주입 `PaymentClock`(UTC). 전송 직전 `body_sha256` 재계산, 다르면 보내지 않음(`BODY_INTEGRITY` 실패 기록). claim 커밋 뒤 전송 전 crash면 예약 번호의 시도 행이 없는 틈이 남는다(n-2 — 허용, 문서화).
> - **m-4·m-5·m-6**: 전체 요청 기한(예약된 abort)·DNS 해석 예산, 시험 CA·https 수신기(자체 서명·hostname 불일치 거절, 고정 IP 연결 + 원 host SNI 검증), LIVE 설정에 사용자 정의 trust 금지 기동 검사,
>   `useSystemProperties()` 금지 ArchUnit. 차단 대역에 IANA 특수 용도 대역(198.18/15·192.0.0/24·192.88.99/24·100::/64·2001::/32·2002::/16·64:ff9b::/96·::/96·fec0::/10·::/128·2001:db8::/32) 추가, **기본 거부**.
>   TEST allowlist 예외는 **전달 행의 environment가 TEST일 때만** 코드에서 적용(T-E1에 LIVE 전달 + allowlist host 차단 행).
> - **m-7**: 열 단위 GRANT 가능성은 S2에서 측정한다. 기존 helper는 표 단위뿐이다. 표 단위로 가면 fan-out은 `merchant_notification_*`만 바꾸는 native PK UPDATE, `JpaOutboxEventEntity`는 `@Immutable`,
>   publish_status·payload 불변 열 고정 시험. **단, 어느 쪽이든 기존 `DatabaseAccountSeparationSpec` 기대값(outbox_event `GRANT SELECT, INSERT`·"outbox update"=1142)이 바뀐다 — §21 B-1로 Coordinator 판정 요청.**
> - **m-8**: 서명 헤더 문법 고정(§6.1 rev 0.2), HMAC 키 = **가맹점에 보이는 비밀 문자열 전체의 UTF-8 바이트**(WH-19), Simulator는 `MessageDigest.isEqual`, `t`는 주입 시계, golden은 ±300초 양 경계.
> - **m-9**: migration 순서 = `ADD COLUMN … NOT NULL DEFAULT 'SKIPPED_PRE_DEPLOY'`(기존 행 즉시) → `ALTER … SET DEFAULT 'PENDING'` → CHECK·인덱스. T-M1(V20 볼륨 → 기존 행 SKIPPED, 새 INSERT PENDING).
> - **n-5**: `cancelTransactionId` = PayCore `transactionId`(PTX 아님) — 대응 시험. **n-6**: Simulator 수신기는 TEST가 아닌 environment 거절. **n-7**: 가맹점 안내 — 서명되지 않은 헤더가 아니라 본문 값을 쓴다. n-8 기록.
> - **S0**: Apache HttpClient 5는 Boot 4.1.1 BOM 관리 항목이다(`httpclient5.version` 5.6.4, `httpcore5` 5.4.3) — 의존성 추가는 S3에서 Coordinator 보고 뒤(§21.1).

rev 0.1 작성 2026-10-21 KST, 기준 commit `df6174d`(CP-QUERY-001 DONE, branch `feat/foundation-setup`, push 완료). **1단계 명세이며 코드·시험 변경 없음.**
작업 트리에는 Coordinator 소유 `harness/tasks.json`·`harness/progress.json` 미커밋 변경이 있으며(`docs/repository/AUTH-001-AUTHORIZATION.md`의 M 표시는 stat cache — 내용은 HEAD와 같다) 이 문서는 읽기만 했다.
이 문서는 새 금융·보안 정책을 채택하지 않는다 — §17 질문 후보는 답변 전 해당 부분의 운영 동작을 구현하지 않는다. 2단계 TDD는 Coordinator가 질문·범위 관찰(§18)을 처리한 뒤 시작한다.

표시(CANCEL-001·CP-QUERY-001과 같다): **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — 의심되면 Coordinator가 질문으로
올린다), **구현 결정(WH-xx)** = 금융·보안 의미가 없는 가역적 선택(§16), **미결(Q-WH-xx)** = §17 질문 후보, **Demo** = Simulator·시험 전용 값(운영값 아님),
**범위 관찰** = 작업 경계 확인 사항(§18), **발견(F-x)** = 이번 조사에서 찾은 기존 코드·문서의 공백(§3.2).

권위 계약: 승인 이벤트·응답 경계는 `AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(이하 **AC1**) §10.2·§11, 취소 이벤트는 `CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(이하 **CC**) §9.3·§14-6,
조회 표현은 `CP-QUERY-001-PAYMENT-QUERY.md`(이하 **CPQ**) §4.4·§5(D-CPQ-001~005). 이 명세는 그 계약을 다시 정하지 않고 **가맹점 통지 경로**(공개 Webhook 계약·전달 구조·시험)만 정한다.

입력 문서(sha256 앞 8자…끝 4자, 2026-10-21 읽은 시점 작업 트리 — 전체 hash는 Verifier가 같은 파일에 `sha256sum`으로 재현):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 지침 | `CLAUDE.md` | 지침 | `829b5a44…d813` |
| 결정 | `docs/DECISIONS.md`(D-CPC-003, D-CPD-001·004, D-CPA-001, D-MD-003, D-MS-004, D-MS2-005, D-AUTH-002·003·008·009·011, D-AUTHN-004·008, D-ADMIN-003·004·005·007, D-CANCEL-005·006·010~013, D-CANCEL1-001·002, D-CPQ-001~005, D-ENV-001) | 확정 | `96591fd8…e184` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md`(§1 8대 원칙, §3 JPA, §4 CQRS, §5 Kafka/Redis) | 사용자 지정 | `a053e150…8c1f` |
| 하네스 | `docs/HARNESS-GUIDE.md` / `docs/BASELINE-INDEX.md` | 기준 | `3f2220c4…06c8` / `f2461472…6251` |
| 이벤트 | `docs/baseline/architecture/03-event-integration-architecture.md`(at-least-once, 필터된 스트림의 별도 sequence, DLQ는 완료 아님, 비밀 미포함) | 기준 | `45a25538…9950` |
| 보안 | `docs/baseline/architecture/05-security-architecture.md`(15행 TEST/LIVE 분리, 19·20행 비밀 저장, 24행 "Webhook URL은 SSRF·사설망·redirect 우회를 방어하고 서명·재전송 방지") | 기준 | `e9dcabd0…b956` |
| 신뢰성 | `docs/baseline/architecture/06-reliability-recovery-architecture.md`(Outbox 적체·연령 한계, lease 만료 가정 금지) | 기준 | `f0f57739…54c1` |
| 관측 | `docs/baseline/architecture/07-operations-observability.md`(ID label 금지, 재처리 명령 감사) | 기준 | `c28964d9…1492` |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md`(40행 "가맹점 서버 조회/서명된 Webhook으로 전달 … UNKNOWN은 확인 중") | 확정(D-AUTH-001) | `3cf08d32…11be` |
| 착수 | `docs/baseline/PayCore-실서비스-착수검토-2026-10-03.md`(38행 "이벤트 ID, 서명, 재전달, ACK, 순서 역전, 미수신 복구, 조회 대체 경로") | 기준 | `a263dbf1…8c9c` |
| 작업 | `harness/tasks.json` WEBHOOK-001(AC 1개·allowedWritePaths·dependsOn AUTH-001), ADMIN-BOOT-001·MASTER-CREDENTIAL-001·RECON-CONTRACT-001 AC | Coordinator 소유(미커밋) | `ab30bcfa…99ee` |
| 진행 | `harness/progress.json`(activeTaskId WEBHOOK-001, agreedOrder … CP-QUERY-001 → **WEBHOOK-001** → ADMIN-BOOT-001 → MASTER-LIFECYCLE-001 → MASTER-CREDENTIAL-001 …) | Coordinator 소유(미커밋) | `6fdff86c…92f6` |
| 질문 | `harness/questions.json`(Q-CPQ-01·02 dependentTasks에 WEBHOOK-001) | Coordinator 소유 | `9b001a0c…d0f3` |
| 명령 | `harness/commands.json`(VERIFY-GATE 5400s, GRADLE-CHECK 하한 2136, HARNESS-SELFTEST 93) | 하네스 | `039a39a1…f63c` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§10.2 388행, §11 398·399행, §18-4 565행) | 확정 계약 | `86b3506a…e209` |
| AU | `docs/repository/AUTH-001-AUTHORIZATION.md`(§1.2 106행, §4.10 이벤트) | 구현 반영 명세 | `e8d9e878…efbe` |
| CC | `docs/repository/CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(§9.3 480행, §14-6 767행) | 확정 계약 | `f73bd602…4485` |
| CN | `docs/repository/CANCEL-001-CANCELLATION.md`(§1.2 100행, §15 판정 (6) 855·865행) | 구현 반영 명세 | `05efed47…6afb` |
| CPQ | `docs/repository/CP-QUERY-001-PAYMENT-QUERY.md`(§4.4·§5, Q-CPQ-01·02 영향 534·541·547행, 구조 선례) | 구현 반영 명세 | `ade697f4…d822` |
| AN | `docs/repository/AUTHN-001-PAYMENT-WINDOW.md`(51행 범위 밖, D-AUTHN-004 Simulator 실행 단위) | 구현 반영 명세 | `319f4402…63ef` |
| CPA | `docs/repository/CP-API-001-CREATE-PAYMENT-API.md`(공개 API·D-CPA-001 키 형식) | 구현 반영 명세 | `ae41bb69…96e3` |
| MS1 | `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md`(push relay·SKIP LOCKED·봉투 HMAC 선례) | 구현 반영 명세 | `bd761cb4…a554` |
| ADR | `docs/repository/ADR-001-KAFKA-REDIS-DEFERRAL.md` | 채택(미도입) | `445af4c1…753f` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§2·§3 계층 규칙, §9.2 권한, §14 명명, §16·§17 잠금 순서, §18) | 관례 | `bb9d86f8…c001` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례 | `4bb8712b…73fc` |
| HVC | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md`(§2·§7·§8) | 하네스 계약 | `e47b5959…2aa7` |
| 코드(schema) | PAYMENT `V1__payment_core.sql`(outbox_event 133~158행) `9e2b9dca…6545`, `V2__payment_runtime_privileges.sql`(37·38행 outbox SELECT·INSERT만) `c0b208ae…6377` | `df6174d` | — |
| 코드(outbox) | `payment/application/required/OutboxStore.kt` `32a5bd02…2fad`, `adapter/persistence/JpaOutboxStore.kt` `b08cbf5e…e9ab`, `adapter/persistence/OutboxPayloadJson.kt` `13d6d37a…49b1` | `df6174d` | — |
| 코드(relay 선례) | master `EntitlementRelayService.kt` `4358fc0b…2108`, `JpaEntitlementRelayStore.kt` `35d35514…6ccc`, `HttpEntitlementDispatcher.kt` `3a3c08a9…1327` | `df6174d` | — |

주: Coordinator 소유 파일(작업·진행·질문·명령)은 이후 바뀔 수 있다 — 2단계 S0에서 입력 hash 표를 다시 고정한다(§15). 이 문서 자신의 hash는 Verifier가 계산한다.

rev 0.2 S0 재고정(2026-10-21, D-WH-001~008·spec-1 판정 반영 뒤 작업 트리): `docs/DECISIONS.md` `c34259fa…d0f0`(D-WH-001~008 추가), `harness/tasks.json` `3e8b4ee8…be6f`(WEBHOOK-001 AC 2·dependsOn CANCEL-001·CP-QUERY-001·
reviewRounds spec-1, WEBHOOK-ENDPOINT-001 신설, ADMIN-BOOT-001 AC 추가), `harness/progress.json` `74e51901…45c1`, `harness/questions.json` `10ef79b7…787e`(Q-WH-01~08 ANSWERED), `harness/commands.json` `039a39a1…f63c`(불변).
그 밖의 입력은 위 표와 같다(코드·계약 문서 불변).

---

## 0. 요약

- **목적(확정 범위 40행·AC1 §10.2)**: PAYMENT가 로컬 TX로 확정한 결제 결과(승인·실패·만료·취소·취소 실패)를 가맹점 서버에 **서명된 HTTP Webhook**으로 알린다. Webhook은 **통지**이고 권위는
  `GET /v1/payments/{paymentId}`(CP-QUERY-001)다(§8). Webhook 실패는 어떤 금융 상태도 바꾸지 않는다(도출 — §17.9).
- **이벤트(§4)**: 기존 Outbox 이벤트 10종 중 5종만 가맹점 이벤트로 바꾼다 — `payment.authorized`·`payment.failed`·`payment.expired`·`payment.cancelled`·`payment.cancel_failed`.
  내부 위험·운영 이벤트(`*IncidentDetected`·`*OutcomeUnresolved`)와 `PaymentRequested`는 보내지 않는다. **P1**(가맹점에 FAILED/EXPIRED로 알려진 Payment)의 늦은 승인·그 운영자 취소는 보내지 않는다(도출 D-CPQ-002·D-CANCEL-013).
  AUTHORIZED Payment의 운영자 보정 취소 통지 여부는 **Q-WH-01**, 보류(hold) 통지는 **Q-WH-02**.
- **공개 계약(§4.3)**: Outbox payload를 그대로 노출하지 않는 별도 v1 봉투(확정 AC1 §10.2). 본문 필드는 GetPayment 응답이 이미 노출하는 값의 부분집합(WH-06) — 카드정보·Secret·기관 원문·PTX·내부 버전·운영자 정보·hold 사유 없음.
- **전달(§5)**: 업무 TX는 **바꾸지 않는다**. 별도 PAYMENT 로컬 TX인 **fan-out**이 `outbox_event`의 미처리 행을 읽어 가맹점 이벤트(본문 바이트 고정·Payment별 연속 `sequence`)와 전달 행을 만들고,
  **delivery worker**가 `FOR UPDATE SKIP LOCKED` claim(짧은 TX) → **TX 밖 HTTP** → 결과 TX(행마다)로 보낸다. at-least-once·순서 비보장·중복 가능, 가맹점은 `eventId`로 중복 제거(§5.7).
  재시도는 지수 backoff + jitter, 시도 수·경과 상한 도달 시 `GAVE_UP`(완료 아님 — 경보)(Demo 값, 형태는 **Q-WH-06**).
- **서명(§6)**: 권장 HMAC-SHA256(`{t}.{body}`), `PayCore-Signature: t=…,kid=…,v1=…`, 교체 중 서명 2개, 재전송 방지 창(Demo) — 방식은 **Q-WH-03**, 비밀 발급·보관·교체는 **Q-WH-05**.
- **목적지(§7)**: endpoint 소유·등록 경로 **Q-WH-04**, SSRF·허용 목적지 정책 **Q-WH-07**. MASTER·PAYMENT 어디에도 가맹점 Webhook URL·서명 비밀이 없다(F-2) → **이 작업은 TEST 환경 Demo endpoint(설정 주입)만 전달하고 LIVE는 fail-closed**로 두는 분할을 제안한다(§18-1).
- **Simulator(§9)**: `apps/institution-simulator`에 가상 가맹점 수신기 모듈 `merchant`(서명 검증·수신 원장·장애 시나리오) + 두 앱이 공유하는 서명 golden.
- **발견 9건**(§3.2), **질문 후보 7개**(§17), **범위 관찰 12건**(§18). 규모: AUTH-001의 약 0.6~0.8배(§20).

---

## 1. 범위와 범위 밖

### 1.1 범위

| 구분 | 내용 | 근거 |
|---|---|---|
| 공개 Webhook 계약 v1 | 이벤트 종류 5개·봉투·본문·헤더·서명·성공 판정·순서/중복 의미·재시도 의미 | AC, AC1 §10.2, CC §14-6, 착수 38행 |
| fan-out | `outbox_event` → 가맹점 이벤트(분류·P1 억제·본문 조립·sequence) — 별도 PAYMENT 로컬 TX | AC "Outbox 기반 전달", 이벤트 13~17행 |
| delivery | claim(SKIP LOCKED)·lease·TX 밖 HTTP·결과 반영·재시도·포기·시도 기록(append-only) | AC "재시도", 이벤트 14·15·50행, MS1 relay 선례 |
| 서명·전송 adapter | HMAC 서명 adapter, 목적지 검사(SSRF)·redirect 금지·timeout·응답 크기 상한 HTTP adapter | 보안 24행, Q-WH-03·07 |
| 목적지 원천(이 작업 범위) | TEST 환경 Demo endpoint·시험 키를 설정에서 읽는 required Port 구현 하나, LIVE 원천 없음(fail-closed) | §18-1 분할 제안, F-2 |
| Simulator | `merchant` 모듈(가상 가맹점 수신기), 서명 golden, harness 독립 생성 시험 | D-AUTHN-004 관례, AU·CN golden 선례 |
| 관측 | 지표·gauge(적체·최고 연령·포기 수·fan-out 지연)·구조화 로그(비밀·본문 없음) | 원칙 §1, 관측 문서 |
| 시험 | 실제 MySQL 8.4.11 경합·rollback·응답 유실·재시작·SSRF·비노출·환경 분리 | CLAUDE.md, §12 |
| 문서 | 관례 새 절(WEBHOOK-001), AC1 §10.2·CC §14-6·CPQ §1.2 교차 참조, HVC §7 기록 | 선례 |

### 1.2 범위 밖

| 항목 | 소유(제안) | 근거 |
|---|---|---|
| 가맹점 endpoint 등록·변경 명령·화면, LIVE endpoint | 후속 **WEBHOOK-ENDPOINT-001**(신규 제안) + ADMIN-BOOT-001(또는 MASTER-CREDENTIAL-001) — Q-WH-04 답에 따라 | F-2, §18-1 |
| 서명 비밀 발급·교체·폐기 명령, 운영 비밀 저장소·관리 키 | 같은 후속 + LIVE Gate | Q-WH-05, D-MS-004 선례 "운영 키는 LIVE 허용 전 별도" |
| 수동 재전송(GAVE_UP 재개) 진입점·화면 | ADMIN-BOOT-001(진입점) + 후속(Use Case) — §5.9 | D-CANCEL1-001 진입점 관례, 관측 문서 재처리 감사 |
| hold 통지 이벤트 | Q-WH-02 답 전 없음 | D-CPQ-001 일관성 |
| FINANCIAL Inbox·PAYMENT→FINANCIAL relay(`publish_status`) | ACQUIRING-001·SETTLEMENT·RECON 계열 | AU §1.2 "Outbox Relay", F-1 |
| 결제창 브라우저 return(redirect) | AUTHN-001(완료) | AN |
| 기관 → PG 통보(push) | AU §1.2 범위 관찰 | — |
| Kafka·Redis·외부 메시지 큐 | 도입 안 함 | ADR-001, §14 |
| 가맹점 rate limit·Webhook 전용 egress 프록시 인프라 | LIVE Gate·인프라 | Q-WH-07 대안 (c) |
| 보관·파기(webhook 이벤트·시도 기록) | LIVE Gate 별도 정책(D-CPQ-004와 같은 방식) | §5.12 |

### 1.3 인계 항목 전수 대조(문서·하네스 전체 grep "WEBHOOK-001"·"Webhook" — 2026-10-21, `build/` 제외)

| 출처 | 항목 | 처리 |
|---|---|---|
| tasks.json WEBHOOK-001 AC | 서명·재시도·순서/중복 허용 계약, Outbox 기반 전달, 가맹점 조회와의 관계, 비밀·카드정보 비노출 | §2 전 행 |
| tasks.json AUTH-CONTRACT-001 기록(2120행) | 2단계 범위 결정에서 WEBHOOK-001 BACKLOG 신설 | 이 작업 |
| tasks.json CANCEL-001 기록(2541행 — §15 판정 (6)) | 취소 GET 없음, 취소 이벤트와 운영자 보정 취소의 가맹점 통지 여부는 WEBHOOK-001 | §4.1, Q-WH-01 |
| tasks.json AUTHN-001 AC(4248행) | 범위 밖: Webhook(WEBHOOK-001) | 이 작업 |
| DECISIONS D-CPQ-001(114행) | GetPayment `onHold` boolean만 — 영향 작업 WEBHOOK-001 | §4.4, Q-WH-02 |
| DECISIONS D-CPQ-002(115행) | 운영자 보정 취소는 `initiator: OPERATOR`만, P1 늦은 승인·그 운영자 취소는 숨김 — 영향 작업 WEBHOOK-001 | §4.4(P1 억제 도출), Q-WH-01 |
| questions.json Q-CPQ-01·02(1727·1743행) | dependentTasks에 WEBHOOK-001 | 위와 같음 |
| AC1 3행(2단계 갱신) | 신규 BACKLOG WEBHOOK-001 | 이 작업 |
| AC1 103행(흐름 [응답]) | confirm 응답 / 조회 API / (후속) 서명 Webhook | §8 |
| AC1 §10.2(388행) | Outbox 통합 이벤트를 소비하는 별도 전달 작업. **Webhook payload는 Outbox payload를 그대로 노출하지 않는 별도 공개 계약.** 서명·재전송 방지·SSRF 방어는 그 작업에서(A05) | §4.3, §6, §7 |
| AC1 §11(398·399행) | `PaymentAuthorizationFailed`·`PaymentExpired` 소비자 = "가맹점 통지·조회 projection" | §4.1 |
| AC1 §18-4(565행) | 서명 방식은 그 작업의 보안 질문 후보 | Q-WH-03 |
| AU §1.2(106행) | 범위 밖: 서명 Webhook → WEBHOOK-001 | 이 작업 |
| AN 51행 | 범위 밖: Webhook → WEBHOOK-001 | 이 작업 |
| CC 232행(흐름 [응답]) | (후속) 서명 Webhook | §8 |
| CC §9.3(480행) | `PaymentCancelFailed` 소비자 = 가맹점 통지·조회 | §4.1 |
| CC §14-6(767행) | WEBHOOK-001: `PaymentCancelled`·`PaymentCancelFailed` 공개 계약 | §4.1·§4.3 |
| CN 11·100행 | 취소 GET 없음(CP-QUERY-001·WEBHOOK-001 인계), 가맹점 Webhook 공개 계약 | §4 |
| CN §15 판정 (6)(855·865행) | `PaymentCancelled`·`PaymentCancelFailed`(**운영자 보정 취소도 가맹점에 알릴지 포함**) 인계 | Q-WH-01 |
| CPQ 97·114행 | 가맹점 Webhook·운영자 보정 취소의 가맹점 통지 → WEBHOOK-001 | Q-WH-01 |
| CPQ 534행(Q-CPQ-01 영향) | WEBHOOK-001의 보류 통지 여부와 일관성 | Q-WH-02 |
| CPQ 541·547행(Q-CPQ-02 배경·영향) | 가맹점 통지 여부는 WEBHOOK-001, 통지 계약과 일관성 | §4.4, Q-WH-01 |
| progress.json 4·10행 | activeTaskId WEBHOOK-001, 다음 재개 동작(1단계 명세 → 질문 → review → TDD → VERIFY-GATE·DEPS/CVE·review → DONE, 그다음 ADMIN-BOOT-001 착수 전 사용자 알림) | 진행 순서, §18-1 |
| 범위 문서 40행(D-AUTH-001 채택) | 결과를 가맹점 서버 조회/서명된 Webhook으로 전달, 가맹점은 승인 확정 후 주문 완료, UNKNOWN은 확인 중 | §0, §8 |
| 착수검토 38·94행 | 이벤트 ID·서명·재전달·ACK·순서 역전·미수신 복구·조회 대체 경로 정의, P0 API·보안 계약에 Webhook | §5.7·§6·§8 |
| 보안 문서 24행 | Webhook URL SSRF·사설망·redirect 우회 방어, 서명·재전송 방지 | §6·§7, Q-WH-07 |
| `V1__payment_core.sql` 131행 주석, `V2__…privileges.sql` 11·37행 주석 | Relay(lease·재발행·published 표시)는 별도 작업, 그 작업이 UPDATE 권한 migration 추가 | F-1, §5.3 |

인계를 받았으나 이 작업에서 처리하지 않는 것: 없음(전 행이 §2·§4~§8 또는 질문 후보에 대응한다).

---

## 2. AC 대응표(tasks.json WEBHOOK-001 → 세부 → 절 → 계획 시험)

| # | AC 세부 | 출처 | 기존 코드 | 공백 → 이 작업 | 절 | 계획 시험(이름 가역적) |
|---|---|---|---|---|---|---|
| 1-a | 서명 계약 | AC1 §10.2·§18-4, 보안 24행 | MASTER 봉투 HMAC(내부 채널) | 공개 서명 헤더·서명 대상 바이트·kid·교체·재전송 창 | §6 | `MerchantWebhookSignatureSpec`(golden 벡터), harness `test_webhook_signature_golden.py`, Simulator `WebhookReceiverVerificationSpec` |
| 1-b | 재시도 계약 | 이벤트 15·50행 | MASTER relay backoff(상한 없음) | 성공 판정·backoff·상한·포기·lease | §5.6·§5.8 | `WebhookRetryScheduleSpec`(순수), `WebhookDeliveryRetrySpec`(실 MySQL·시험 수신기) |
| 1-c | 순서·중복 허용 계약 | 이벤트 13·47·49행, 착수 38행 | 집계별 `aggregate_version`만(F-4) | at-least-once·순서 비보장·Payment별 연속 `sequence`·`eventId` 안정 | §5.7 | `WebhookSequenceSpec`, `WebhookDuplicateDeliverySpec`(응답 유실·lease 만료 재전송 — 같은 eventId·같은 본문 바이트) |
| 2 | Outbox 기반 전달 | 이벤트 14·16행, AC1 §10.2 | Outbox append만(Relay 없음 — F-1) | fan-out(같은 DB 로컬 TX)·delivery·claim | §5 | `WebhookFanoutAtomicitySpec`, `WebhookFanoutConcurrencySpec`, `WebhookDeliveryClaimSpec` |
| 3 | 가맹점 조회와의 관계 | AC1 §10.2, D-CP-003, D-CPQ-001·002 | GetPayment | GET 권위·Webhook 통지, 표시 범위 일치(P1 억제·운영자 취소·hold) | §4.4·§8 | `WebhookVisibilityConsistencySpec`(같은 Payment의 GET 표시와 Webhook 이벤트 집합 대조) |
| 4 | 비밀·카드정보 비노출 | 보안 19·21·23행, AC1 §10.1, D-MS2-005, D-ADMIN-004 | Outbox payload(내부 필드 포함 — F-3) | 공개 본문 필드 고정·로그·설정 마스킹 | §4.3·§11 | `WebhookBodyContractSpec`(키 집합 정확 비교), `WebhookNonDisclosureSpec`(캡처 로그·예외·설정 toString) |
| 4-b | (보안 24행) SSRF·redirect·사설망 | 보안 24행 | 없음 | 목적지 검사·DNS 고정·redirect 미추종·응답 상한 | §7 | `WebhookDestinationGuardSpec`, `WebhookRedirectAndSizeSpec` |
| 4-c | (보안 15행) TEST/LIVE 분리 | D-CPD-001, D-MD-003 | 환경 축 | 환경별 endpoint·비밀, LIVE 원천 없음 → 전달 없음 | §7.1 | `WebhookEnvironmentIsolationSpec` |

---

## 3. 현황 조사(2026-10-21, `df6174d`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것(이 작업) |
|---|---|---|
| PAYMENT Outbox | `outbox_event`(event_id·effect_key UNIQUE, `publish_status` PENDING/PUBLISHED, `ix_outbox_event_pending(publish_status, id)`), `JpaOutboxStore.append`(MANDATORY TX, persist+flush), payload 10종(`OutboxPayloadJson` — 금액 정수·시각 UTC ms) | 어떤 소비자도 없다(Relay·fan-out 없음), runtime 권한 SELECT·INSERT만 |
| 이벤트 생산 | `PaymentRequested`(생성), `PaymentExpired`(만료 — D-AUTHN-008), `PaymentAuthorized`·`PaymentAuthorizationFailed`·`AuthorizationIncidentDetected`·`AuthorizationOutcomeUnresolved`(AUTH-001), `PaymentCancelled`·`PaymentCancelFailed`·`CancellationIncidentDetected`·`CancellationOutcomeUnresolved`(CANCEL-001) — 모두 업무 효과와 같은 로컬 TX | 가맹점 공개 이벤트 계약 |
| relay 선례(MASTER) | `EntitlementRelayService`(claim TX → TX 밖 HTTP → 행마다 결과 TX, 지수 backoff+jitter, 상한 없음, 4xx 격리), `JpaEntitlementRelayStore`(native `FOR UPDATE SKIP LOCKED`, claim 소유자 fencing, `CLAIM_LOST`), `HttpEntitlementDispatcher`(RestClient·connect/read timeout), `EntitlementSettings`(운영 기본값 없음·기동 검사·키 toString 마스킹), `EntitlementSchedules`(`@Scheduled`, enabled 필수) | 외부(가맹점) 목적지 검사·redirect 금지·응답 크기 상한 — MASTER relay는 신뢰된 내부 endpoint 하나 |
| 가맹점 자격 | PAYMENT `merchant_api_credential` projection(keyId+SHA-256만 — D-CPA-001), MASTER-CREDENTIAL-001(BACKLOG — API key 발급·전파) | 가맹점 Webhook URL·서명 비밀(어디에도 없음 — F-2) |
| Simulator | `institution-simulator` 모듈 `authentication`(HTTP)·`van`(TCP), 별도 DB·표 단위 권한·golden 계약 | 가상 가맹점 수신기 |
| 조회 | GetPayment(D-CPQ-001~005 표시 규칙), `UtcWallClock` | — |
| 계층 규칙 | application은 `java.net`·`javax..`(JCE 포함) 금지(ArchUnit `applicationDependsOnlyOnPorts`) | 서명·HTTP는 adapter(F-7) |

### 3.2 발견(F-x)

1. **F-1 PAYMENT Outbox에 소비자가 없다**: `V1` 주석 "Relay(lease·재발행·published 표시)는 별도 작업", `V2` 권한 `outbox_event` SELECT·INSERT만. Webhook이 PAYMENT Outbox의 **첫 소비자**다.
   `publish_status`는 계약상 FINANCIAL 전달("내구성 ACK 이후 전송 완료" — 이벤트 15행)의 표시로 남겨 두는 것이 맞다 → **도출**: Webhook은 `publish_status`를 쓰지 않고 자기 처리 표시를 따로 둔다(§5.3, WH-03).
   가맹점 HTTP 2xx는 FINANCIAL Inbox 내구 저장이 아니므로 같은 열로 표시하면 이후 FINANCIAL relay가 이벤트를 건너뛰는 결함이 된다.
2. **F-2 가맹점 Webhook URL·서명 비밀의 원천이 없다**: MASTER migration V1·V3·V5에 URL·endpoint·webhook 열이 없고(grep 0건 — 75행은 Site WEB host 주석), MASTER-CREDENTIAL-001 AC는 API key만 다룬다.
   API key 저장 방식(keyId+SHA-256만 — D-CPA-001)은 **검증 전용**이라 서명 비밀에 쓸 수 없다(HMAC 서명자는 원문이 필요 — 보안 20행 "기관 호출에 원문이 필요한 비밀은 관리 키로 암호화"). 원천·등록 경로가
   없으므로 agreedOrder(WEBHOOK-001 → ADMIN-BOOT-001 → … → MASTER-CREDENTIAL-001)에서 이 작업이 LIVE 등록까지 끝낼 수 없다 → §18-1 분할 제안.
3. **F-3 Outbox payload에 가맹점 비공개 값이 있다**: `PaymentAuthorized` = issuer/acquirer 코드·ProviderTransactionId·기관 거래일/업무일·정책 Assignment 버전·권리 revision, `PaymentCancelled` = PTX 2개·기관 일자·정책 버전,
   `*Failed` = `failureEvidence`(내부 근거 이름), `*IncidentDetected` = 관찰 금액·출처·승인번호·위험 힌트, `*Unresolved` = attemptId·requestId·설정 버전. GetPayment는 이 중 어느 것도 노출하지 않는다(CPQ §4.4 "노출 안 함" 행)
   → **확정(AC1 §10.2)** 별도 공개 계약, 본문은 GET 노출 집합의 부분집합(WH-06).
4. **F-4 Payment 단위 순번이 없다**: `aggregate_version`은 집계별(`Payment`·`PaymentAuthorization`·`PaymentCancellation` 각각의 JPA version)이고 집계마다 다른 수열이다. 가맹점 스트림은 이 중 5종만 고른 **필터된 스트림**이다
   → **확정(이벤트 47행)** "필터된 스트림은 별도 연속 sequence 또는 명시적 선행 eventId를 정의한다" → Payment별 연속 `sequence`(WH-04).
5. **F-5 진행 중·보류 상태에는 이벤트가 없다**: AUTHORIZING·UNKNOWN 진입, 취소 IN_PROGRESS·UNKNOWN 진입은 Outbox 이벤트가 없다(가맹점은 confirm/cancel 202와 GET으로 안다). hold 열림은 `*IncidentDetected`(RISK_INCIDENT,
   내부 관찰 값 포함)로만, hold 해제는 Use Case 자체가 없다(RECON-CONTRACT-001). → 가맹점 이벤트는 **확정 결과 5종**만이며, hold 통지는 Q-WH-02.
6. **F-6 P1 경로는 운영 코드에서 아직 만들어지지 않는다**: P1 늦은 승인 기록(`PaymentAuthorization.recordLateApprovalByReconciliation`)의 호출자가 없고(RECON), 운영자 보정 취소는 inbound 진입점이 없다(D-CANCEL1-001).
   그러나 두 경로가 생기면 `PaymentAuthorized`(D-CANCEL-012 — P1에도 발행)·`PaymentCancelled`(`initiatorKind=OPERATOR`)가 Outbox에 들어온다 → fan-out은 **지금** 억제 규칙을 가져야 하고, 시험은 Invalid/우회 Fixture로 한다(§12 T-F3).
7. **F-7 서명·HTTP·IP 판정은 application에 둘 수 없다**: `applicationDependsOnlyOnPorts`가 `java.net`·`javax..`를 막는다(HMAC `javax.crypto.Mac`, `InetAddress`). → 서명자·전송자·목적지 판정은 required Port 뒤 `adapter.integration`
   (MASTER `EntitlementEnvelope` 선례). domain에는 IP 바이트 분류 같은 순수 규칙만 둘 수 있다(`java.net` 없이 `ByteArray`).
8. **F-8 일부 payload에 공개 본문 재료가 없다**: `PaymentAuthorizationFailed`·`PaymentCancelFailed`에 `merchantSiteId`·승인 금액이 없고, 취소 이벤트에 `merchantCancelId`가 없다(GET은 `merchantCancelId`를 노출).
   → fan-out이 PAYMENT DB의 **불변 행**(Payment 생성 속성, `payment_cancellation.merchant_cancel_id`)을 같은 fan-out TX에서 읽어 채운다(WH-07). 누적 반환액·`returnStatusAfter`처럼 시점에 묶인 값은 **payload(이벤트 시점 값)** 를 쓴다.
9. **F-9 MySQL `JSON` 열은 바이트를 보존하지 않는다**: `outbox_event.payload`는 `JSON` 형식이라 저장 시 키 순서·공백이 정규화된다. 서명은 **보낸 바이트 그대로**에 대해야 하고 재시도마다 같은 본문이어야 하므로(§5.7)
   가맹점 본문은 `JSON` 열이 아니라 바이트 열(`MEDIUMBLOB`) + `body_sha256`으로 저장한다(WH-08).

---

## 4. 이벤트 카탈로그와 공개 본문

### 4.1 Outbox 이벤트 → 가맹점 이벤트

| Outbox 이벤트(분류) | 가맹점 이벤트 | 조건 | 표시 |
|---|---|---|---|
| `PaymentAuthorized`(FINANCIAL_FACT) | `payment.authorized` | fan-out 시점 Payment `status = AUTHORIZED` | **도출**(범위 40행·AC1 §10.2 — 승인 결과 통지가 이 작업의 제목) |
| 〃 | (보내지 않음) | Payment `status ∈ {FAILED, EXPIRED}` = **P1**(D-CANCEL-012로 발행된 늦은 승인) | **도출**(D-CPQ-002 "P1 늦은 승인은 숨김" + D-CANCEL-013 "가맹점은 실패를 통보받음" — D-CPQ-002의 영향 작업에 WEBHOOK-001) |
| `PaymentAuthorizationFailed`(INTEGRATION_NON_FINANCIAL) | `payment.failed` | 항상 | **확정**(AC1 §11 398행 "가맹점 통지") |
| `PaymentExpired`(INTEGRATION_NON_FINANCIAL) | `payment.expired` | 항상 | **확정**(AC1 §11 399행 "가맹점 통지") |
| `PaymentCancelled`(FINANCIAL_FACT) `initiatorKind=MERCHANT` | `payment.cancelled` | Payment `status = AUTHORIZED` | **확정**(CC §14-6) |
| 〃 `initiatorKind=OPERATOR`, Payment AUTHORIZED | `payment.cancelled`(`initiator: OPERATOR`, `merchantCancelId` 없음) | — | **확정 D-WH-001**(rev 0.2) |
| 〃 Payment FAILED/EXPIRED(P1 운영자 취소) | (보내지 않음 — SUPPRESSED) | — | **도출**(D-CPQ-002 (ii)) |
| `PaymentCancelFailed`(INTEGRATION_NON_FINANCIAL), Payment AUTHORIZED | `payment.cancel_failed`(MERCHANT·OPERATOR 모두 — initiator는 불변 취소 행에서, H-2) | — | **확정**(CC §9.3 480행) + **D-WH-001** |
| 〃 Payment FAILED/EXPIRED(P1 운영자 취소 실패) | (보내지 않음 — SUPPRESSED) | — | **도출**(D-CPQ-002 (ii), rev 0.2 H-2) |
| 위 5종 + 그 밖의 Payment 상태(REQUESTED·AUTHORIZING·UNKNOWN, 또는 PaymentAuthorizationFailed가 FAILED 아님 등) | (아직 판단 안 함) | 그 Outbox 행 `FANOUT_FAILED`(비종결, 오류 지표) | **도출 fail-closed**(rev 0.2 H-3 — 존재할 수 없는 조합을 숨기지도 보내지도 않는다) |
| `PaymentRequested` | (보내지 않음) | — | **구현 결정 WH-01**(가맹점이 동기 생성 응답으로 이미 앎 — 추가는 가역·가산적) |
| `AuthorizationIncidentDetected`·`CancellationIncidentDetected`(RISK_INCIDENT) | (보내지 않음) | — | **도출**(RECON Case 입력·조사 중 사실 — D-CPQ-001 "사유·회차·관찰·FORCED 비노출"), hold 사실 통지는 Q-WH-02 |
| `AuthorizationOutcomeUnresolved`·`CancellationOutcomeUnresolved`(OPERATIONAL_RISK) | (보내지 않음) | — | **도출**(상태 변화 없음 — UNKNOWN은 GET이 "확인 중"으로 표시, CPQ §5.1) |

- **P1 판정의 경합 안전성(도출)**: fan-out은 이벤트가 커밋된 **뒤** 비잠금으로 Payment `status`를 읽는다. 판정에 쓰는 상태(AUTHORIZED·FAILED·EXPIRED)는 모두 **종료 상태**라(Payment는 AUTHORIZED 뒤 바뀌지 않고 —
  취소는 반환 상태만 바꾼다, FAILED/EXPIRED는 P1이어도 유지 — D-CANCEL-011) 이벤트 시점과 fan-out 시점의 판정이 같다. 이 성질이 깨지는 변경(예: RECON이 EXPIRED P1을 다른 상태로 바꿈 — CPQ 최종 review n-2 인계)은 이 규칙의 시험(T-F3)이 잡는다.
- **D-AUTH-011 gen2 승인**: 이전 generation 사건으로 **주문** hold가 있어도 현재 Payment의 승인은 기관 확정 효과로 `PaymentAuthorized`가 나오고 Payment는 AUTHORIZED다 → `payment.authorized`를 보낸다(도출 — GET도 AUTHORIZED로 보인다).
  그 Payment 자신의 `onHold`는 GET에서 확인한다(Q-WH-02).
- **UNKNOWN → 회복 확정**: 회복 Use Case(`AuthorizationRecoveryService`·`CancellationRecoveryService`)가 확정하면 같은 Outbox 이벤트가 나오므로 `payment.authorized`/`payment.failed`/`payment.cancelled`/`payment.cancel_failed`가 그대로 간다.
  confirm·cancel 202를 받은 가맹점에게 Webhook이 가장 큰 가치를 갖는 경로다(범위 40행).

### 4.2 공개 봉투 v1

#### 4.2.0 (rev 0.2 — m-1 Coordinator 판정, 아래 rev 0.1 표를 대체) 본문 = GET의 엄격한 부분집합

```json
{"eventId":"whe_01JB…","type":"payment.cancelled","schemaVersion":1,"environment":"TEST","merchantAccountId":"mid_…",
 "createdAt":"2026-10-21T01:02:03.456Z","sequence":2,
 "payment":{"paymentId":"pay_01J…","merchantOrderId":"order-0001","amount":10000,"currency":"KRW","status":"AUTHORIZED"},
 "cancellation":{"cancellationId":"pcn_…","initiator":"MERCHANT","merchantCancelId":"c-1","status":"COMPLETED","amount":3000,"currency":"KRW",
                 "cancelTransactionId":"ptx_…","cancelApprovalNumber":"…","cancelledAt":"…Z"},
 "returnSummary":{"authorizedAmount":10000,"cancelledAmount":3000,"returnStatus":"PARTIALLY_CANCELLED"}}
```

| 위치 | 키(정확 집합, 없는 값은 생략) | GET 대응(`GetPaymentController` 실제 출력) |
|---|---|---|
| 봉투 | `eventId`·`type`·`schemaVersion`·`environment`·`merchantAccountId`·`merchantCode`(rev 0.3 가산 — 코드 있는 MID만, `merchantAccountId` 바로 뒤, GET 최상위에도 같은 이름)·`createdAt`(**가맹점 이벤트 생성 시각** = fan-out 시각 — 업무 시각 아님, 실패·만료 시각을 새로 드러내지 않는다)·`sequence` | Webhook 메타데이터(GET 사실 아님) |
| `payment` | `paymentId`·`merchantOrderId`·`amount`·`currency`(Payment 생성 금액 — 불변 Payment 행)·`status`(GET 공개 값: AUTHORIZED·FAILED·EXPIRED)·`failureCategory`(FAILED만) | GET 최상위 같은 이름 |
| `authorization` | authorized: `authorizationId`·`status`·`approvedAmount`·`currency`·`plan`{`kind`,`months`?}·`cardType`·`approvalNumber`·`approvedAt` / failed: `authorizationId`·`status`·`failureCategory` / expired: 없음 | GET `authorization`(FAILED에도 `authorizationId`·`status`·`failureCategory`를 낸다) |
| `cancellation` | cancelled: `cancellationId`·`initiator`·`merchantCancelId`(MERCHANT만)·`status`=COMPLETED·`amount`·`currency`·`cancelTransactionId`(**PayCore transactionId** — PTX 아님, n-5)·`cancelApprovalNumber`?·`cancelledAt` / cancel_failed: `cancellationId`·`initiator`·`merchantCancelId`(MERCHANT만)·`status`=FAILED·`amount`·`currency`·`failureCategory` | GET `cancellations[]` 항목 |
| `returnSummary` | cancelled만: `authorizedAmount`·`cancelledAmount`(이벤트 시점 누적)·`returnStatus`(이벤트 시점) | GET `returnSummary`의 부분(`cancellableAmount`·`cancelInProgressAmount` 제외 — 현재값은 GET) |

- 제외(rev 0.1 대비): `failedAt`·`expiredAt`·취소 실패 시각, 봉투 업무 시각, `payment.sequence`(최상위 `sequence`로 이동). 시험 T-C2b: 종류별로 GET 키 집합 ⊇ Webhook 키 집합(섹션별).
- 가맹점 안내(n-7): 판단에는 서명된 **본문** 값을 쓴다(`PayCore-Event-Id`·`PayCore-Event-Type`·`PayCore-Delivery-Attempt` 헤더는 서명 대상이 아니다).

#### 4.2.1 (rev 0.1 원문 — 기록)

```http
POST <endpoint>
Content-Type: application/json
User-Agent: PayCore-Webhook/1
PayCore-Event-Id: whe_01JB…
PayCore-Event-Type: payment.cancelled
PayCore-Signature: t=1792500000,kid=whk_t_01JB…,v1=5f2b…(hex)
PayCore-Delivery-Attempt: 2

{"eventId":"whe_01JB…","type":"payment.cancelled","schemaVersion":1,"environment":"TEST","merchantAccountId":"mid_…",
 "createdAt":"2026-10-21T01:02:03.456Z",
 "payment":{"paymentId":"pay_01J…","merchantOrderId":"order-0001","sequence":2},
 "data":{…}}
```

| 필드 | 규칙 | 표시 |
|---|---|---|
| `eventId` | 가맹점 이벤트 ID(`whe_` + ULID, 128bit 이상). Outbox `event_id`(내부)를 노출하지 않는다. **재시도·재전송 내내 같다** | 구현 결정 WH-05, 도출(이벤트 15행 "같은 eventId로 다시 보낸다") |
| `type` | §4.1 다섯 값 | §4.1 |
| `schemaVersion` | 1. 추가 필드는 가산적, 의미 변경은 새 버전(이벤트 51행) | 확정(이벤트 51행) |
| `environment`·`merchantAccountId` | 인증된 (MID, 환경) — 가맹점이 자기 것인지 대조(다중 MID 가맹점·잘못된 전달 탐지). 가맹점 자신의 식별자라 비노출 대상 아님(D-CPA-001 키 접두와 같은 축) | 도출(보안 13행 MID 축) |
| `createdAt` | 원 업무 사실 시각(Outbox `occurred_at`, UTC ms `Z`) — 전달 시각이 아니다 | WH-06 |
| `payment.sequence` | 그 Payment의 가맹점 이벤트 연속 순번(1부터, 억제된 이벤트는 번호를 쓰지 않음) | WH-04 |
| `data` | 아래 표 | WH-06 |

`data`(GET 응답 필드 이름·형식 — 금액 JSON 정수·시각 UTC ms `Z`·없는 값은 키 생략, CPQ CQ-03 관례):

| type | `data` 키 집합(정확) | 출처 |
|---|---|---|
| `payment.authorized` | `status`=`AUTHORIZED`, `authorizationId`, `amount`, `currency`, `plan`{`kind`, `months`?}, `cardType`, `approvalNumber`, `approvedAt` | payload(이벤트 시점, 불변 거래) — GET `authorization` terms와 같은 값 |
| `payment.failed` | `status`=`FAILED`, `authorizationId`, `failureCategory`(DECLINED·NOT_SENT·NO_EFFECT), `failedAt` | payload `failureCategory` |
| `payment.expired` | `status`=`EXPIRED`, `expiredAt` | payload |
| `payment.cancelled` | `cancellationId`, `initiator`(MERCHANT·OPERATOR), `merchantCancelId`(MERCHANT만), `amount`, `currency`, `cancelTransactionId`, `cancelApprovalNumber`(있으면), `cancelledAt`, `returnAfter`{`authorizedAmount`, `cancelledAmount`(누적), `returnStatus`} | payload(`returnSequence`·누적·`returnStatusAfter` = 이벤트 시점) + `merchantCancelId`는 불변 취소 행(F-8) |
| `payment.cancel_failed` | `cancellationId`, `initiator`, `merchantCancelId`(MERCHANT만), `amount`, `currency`, `failureCategory`, `failedAt` | payload + 불변 취소 행 |

- **노출하지 않는 것(확정 — CPQ §4.4 "노출 안 함" 행·AC1 §10.1·CC §8·D-MS2-005·D-ADMIN-004)**: 카드번호(저장 안 함), 인증 증거, 기관 원문, ProviderTransactionId·원거래 PTX, 기관 거래일/업무일, issuer/acquirer 코드(GET에 없음),
  기관 계약계정, 정책·권리·Route·Capability 버전, `failureEvidence`·`terminationEvidence` 원 이름, 운영자 신원·사유·approvalId·목적, JPA version, `merchantSiteId`·`orderName`·`paymentKey`, hold 사유·회차·FORCED·excess, Outbox `event_id`·`effect_key`.
- **`cancellableAmount`를 넣지 않는다(WH-06)**: GET의 `cancellableAmount`는 FORCED 예약을 뺀 **현재** 권위 값이다(D-CPQ-005). 이벤트 시점 값으로 고정하면 hold·FORCED 이후 GET과 다른 수가 남는다 → 가맹점은 GET으로 확인.
- **본문은 fan-out 때 한 번 만들어 바이트로 고정**(WH-08). 같은 이벤트의 모든 시도·재전송은 같은 바이트, 서명만 시도마다 새 `t`로 다시 계산.

### 4.3 성공 판정(가맹점 응답)

- **구현 결정 WH-09**: 시간 안의 HTTP `2xx` = 전달 완료(응답 본문은 상한까지 읽고 버림 — 해석하지 않음). `3xx`(redirect — 따라가지 않음)·`4xx`·`5xx`·timeout·연결 실패·목적지 차단 = 실패 → 재시도 대상(§5.6).
  4xx를 즉시 포기로 볼지(MASTER relay의 "4xx 격리")는 Q-WH-06 대안 — 가맹점 4xx는 계약 오류가 아니라 수신 설정 문제일 수 있어 권장안은 재시도 후 상한 포기.
- 가맹점 안내(API 문서 규칙, **도출** 이벤트 13행·착수 38행): (1) 서명 검증 후 처리, (2) `eventId`로 중복 제거, (3) 빨리 2xx 응답(처리는 비동기), (4) 순서 역전 가능 — `sequence`로 낡은 이벤트를 구별하거나 GET으로 현재 상태 확인,
  (5) Webhook을 못 받아도 GET이 권위.

### 4.4 D-CPQ-001·002 일관성(조회와 통지가 같은 사실 범위를 보인다)

| 사실 | GET(확정) | Webhook | 표시 |
|---|---|---|---|
| P1 늦은 승인·그 운영자 취소 | 숨김, FAILED·분류만(D-CPQ-002 (ii)) | 보내지 않음(§4.1) | 도출 |
| AUTHORIZED의 운영자 보정 취소 | 이력에 `initiator: OPERATOR`만(D-CPQ-002 (i)) | Q-WH-01(권장: `payment.cancelled` + `initiator: OPERATOR`, 같은 최소 내용) | 미결 |
| hold | `onHold` boolean(D-CPQ-001) | Q-WH-02(권장: v1 hold 이벤트·필드 없음) | 미결 |
| FORCED·excess | `cancellableAmount`에만 반영(D-CPQ-005) | 넣지 않음(WH-06) | 구현 결정 |
| UNKNOWN·IN_PROGRESS | `UNKNOWN`·`IN_PROGRESS` + notice | 이벤트 없음(F-5) — 확정 시 결과 이벤트 | 도출 |

- 일관성 시험(T-V1): 같은 Payment의 공식 경로 시나리오마다 "Webhook으로 받은 사실 집합 ⊆ 그 시점 이후 GET이 보이는 사실 집합"이고, GET에서 숨기는 사실(P1)은 Webhook에도 없다.

### 4.5 질문 답 전 동작 — rev 0.2에서 폐기(H-1: D-WH-001·002 답변으로 `HELD_DECISION` 없음, hold 통지 없음 확정). 아래는 rev 0.1 기록이다.

- Q-WH-01 답 전: `initiatorKind=OPERATOR`인 `PaymentCancelled`·`PaymentCancelFailed`는 **가맹점 이벤트 행을 만들지 않고** Outbox 행을 `HELD_DECISION`(처리 보류)으로 둔다 — 답이 "통지"면 그 행들을 그대로 fan-out하고,
  "통지 안 함"이면 `SUPPRESSED`로 닫는다(어느 쪽이든 유실·선점 없음). 운영 경로에는 아직 운영자 취소가 없다(F-6).
- Q-WH-02 답 전: hold 관련 이벤트·필드를 만들지 않는다(현재 공개 범위 = GET `onHold`).

---

## 5. Outbox relay·전달 설계

### 5.0 (rev 0.2 — H-3·m-2·m-3·m-7·m-9·n-1·n-3·n-4, 아래 §5.1~§5.5의 rev 0.1 문장과 다르면 이 절이 우선)

```text
[업무 TX — 변경 없음] outbox_event INSERT(새 열은 DEFAULT 'PENDING')

[fan-out 회차 — application, TX 밖 조정]
  후보 = 비잠금 읽기(짧은 읽기 전용 TX): merchant_notification_status = 'PENDING'
         ∪ (FANOUT_FAILED ∧ merchant_notification_retry_at ≤ now) ORDER BY id LIMIT n,
         같은 Payment(payload paymentId)에 이 행보다 앞선 FANOUT_FAILED(아직 재시도 시각 전) 행이 있으면 제외
  행마다 [LocalTransactions READ COMMITTED 한 TX]:
     merchant_webhook_fanout_guard FOR UPDATE              -- fan-out 쓰기 직렬화(인스턴스 여럿)
     → outbox 행 PK 비잠금 재읽기(아직 PENDING/만기 FANOUT_FAILED인지 — guard가 다른 fan-out 쓰기를 막으므로 충분)
     → payload 해석 + Payment 상태·금액(불변 생성 값) + (취소면) payment_cancellation.initiator_kind·merchant_cancel_id 비잠금 읽기
     → 분류 → 대상: stream +1 → merchant_webhook_event INSERT → (endpoint 있으면) merchant_webhook_delivery INSERT
     → outbox PK UPDATE: merchant_notification_status/notified_at만 (NOTIFIED | SUPPRESSED | NOT_APPLICABLE)
  실패(분류 이상·읽기 불가·결함·저장 실패): 그 TX rollback → 새 TX(guard)로 그 행만 FANOUT_FAILED(failure_code·failures+1·retry_at = now + 재시도 간격, 비종결)
     → 같은 Payment의 뒤 행은 이번 회차에서 건너뜀(순서 보존), 다른 Payment는 계속. 지표 paycore.webhook.fanout{result=failed} + 경보(gauge).
```

- **업무 TX 비대기(H-3)**: fan-out은 Outbox를 잠금 읽기하지 않고(`FOR UPDATE`·범위 잠금 없음) PK 한 행만 UPDATE한다. READ COMMITTED라 갭 잠금이 없어 업무 TX의 Outbox INSERT는 fan-out TX가 열려 있어도 기다리지 않는다
  (시험 C-F2: fan-out TX를 guard·PK UPDATE 뒤 붙잡은 채 다른 연결의 승인/취소 TX가 즉시 커밋 — `performance_schema.data_lock_waits` 0). guard 행은 webhook 전용 표라 업무 잠금 순서와 교차하지 않는다.
- **행 단위 격리(m-3)**: 독성 행 하나가 전체를 멈추지 않는다(다른 Payment 계속). 같은 Payment의 뒤 행만 막아 sequence = 인과 순서를 지킨다. `FANOUT_FAILED`는 종결이 아니다 — 재시도 시각마다 다시 시도, 성공하면 NOTIFIED/SUPPRESSED/NOT_APPLICABLE.
  이상 조합은 SUPPRESSED가 되지 않는다(fail-closed). 재시도 간격은 기본값 없는 설정(Demo 1분).
- **Outbox 열(m-9, 검토 #1 n-1 정정)**: `merchant_notification_status`(PENDING·NOTIFIED·SUPPRESSED·NOT_APPLICABLE·FANOUT_FAILED·SKIPPED_PRE_DEPLOY), `merchant_notified_at`(종결 시각) **두 개뿐**(D-WH-009).
  실패 횟수·코드·재시도 시각은 webhook 전용 표 `merchant_notification_failure`(WH-24). 추가 순서: ADD COLUMN status `NOT NULL DEFAULT 'SKIPPED_PRE_DEPLOY'`(기존 행 즉시 표시 — §18-4 판정) → `ALTER … SET DEFAULT 'PENDING'` → 나머지 열·CHECK·인덱스.
  두 문장 사이에 이전 인스턴스가 INSERT하면 그 행은 SKIPPED가 될 수 있다 — 배포 관례(이전 인스턴스 모두 내린 뒤 migration, HVC §7)를 따른다.
- **권한(m-7, D-WH-009 — n-1 정정)**: fan-out이 바꾸는 열은 `merchant_notification_*` 두 개뿐(native PK UPDATE), runtime 권한도 그 두 열의 열 단위 UPDATE(측정·적용 완료 — §21.1·§21.4), `JpaOutboxEventEntity`는 `@Immutable`,
  T-F6 열 고정 시험. 같은 Payment의 앞 미종결 행이 있으면 행 TX 안에서 DEFERRED(검토 #1 H-1 — §21.5).
- **delivery(m-2·n-1·n-3·n-4 — S3)**: claim TX에서 `attempt_count`를 +1 해 이번 시도 번호를 예약하고 claim 소유자·`claim_until`을 적는다. 결과 TX는 그 번호로 `merchant_webhook_attempt`를 INSERT(UNIQUE(delivery_id, attempt_no) — lease 인수 시 두 시도 행 모두 남는다).
  2xx는 소유를 잃었어도 DELIVERED로 표시할 수 있다(성공 단조 — 이미 DELIVERED면 그대로), 실패는 소유자일 때만 다음 시각·포기를 반영한다. claim·lease 비교 시각은 주입 `PaymentClock`(UTC — DB `NOW()` 아님).
  전송 직전 저장 본문 sha256을 재계산해 다르면 보내지 않고 `BODY_INTEGRITY` 실패로 기록. claim 커밋 뒤 전송 전 crash면 예약 번호의 시도 행이 없는 틈이 생긴다(n-2 — 허용·문서화, 다음 claim은 다음 번호).

### 5.1 흐름(rev 0.1 원문 — fan-out 부분은 §5.0이 대체)

```text
[업무 TX — 변경 없음] 효과 + 요약 + 멱등 결과 + outbox_event(INSERT) — 같은 PAYMENT 로컬 TX (D-CPC-003·AU·CN 그대로)

[fan-out TX — PAYMENT 로컬 TX, 한 번에 하나(직렬화 행 잠금)]
  merchant_webhook_fanout_guard FOR UPDATE (직렬화)
  → outbox_event WHERE merchant_notification_status = 'PENDING' ORDER BY id LIMIT n FOR UPDATE
  → 행마다: 분류(§4.1) → 억제/보류/대상 판정(Payment status 비잠금 읽기)
       대상: 불변 행 읽기(F-8) → 본문 바이트 조립 → stream(payment_id) 순번 +1
             → merchant_webhook_event INSERT(source_event_id UNIQUE)
             → endpoint 있음(그 환경·MID): merchant_webhook_delivery INSERT(PENDING) / 없음: event.delivery_plan = NO_ENDPOINT
       outbox_event.merchant_notification_status = NOTIFIED | SUPPRESSED | NOT_APPLICABLE | HELD_DECISION
  → COMMIT

[delivery worker — 인스턴스 여러 개 가능]
  TX1(claim): merchant_webhook_delivery PENDING ∧ next_attempt_at ≤ now ∧ (claim 없음 ∨ claim_until < now)
              ORDER BY next_attempt_at, delivery_id LIMIT n FOR UPDATE SKIP LOCKED → claim_owner·claim_until 표시 → COMMIT
  (TX 밖) endpoint·서명 키 조회 → 목적지 검사(§7) → 서명(§6) → HTTP POST(timeout·redirect 금지·응답 상한) — DB 잠금·TX 없음
  TX2(행마다): 행 FOR UPDATE → claim 소유자 확인 → merchant_webhook_attempt INSERT(append-only)
              → 2xx: DELIVERED / 실패: attempt_count+1, next_attempt_at = now + backoff / 상한: GAVE_UP → COMMIT
```

### 5.2 TX 경계(확정 CLAUDE.md "외부 호출은 DB TX 밖, 업무 효과·요약·요청 결과·Outbox는 소유 DB의 같은 TX")

- **업무 TX는 바꾸지 않는다(구현 결정 WH-02, 근거 도출)**: Webhook 테이블을 업무 TX에 넣으면(대안 A) fan-out 결함·제약 위반이 **승인·취소 TX를 rollback**시킨다 — "Webhook 실패는 금융에 영향 없음"(§17.9)과
  어긋난다. Outbox 행 자체가 이미 원자적 기록이므로 그 뒤의 분기는 별도 로컬 TX로 충분하다.
- **fan-out TX**: Outbox 처리 표시·가맹점 이벤트·sequence·전달 행을 **한 PAYMENT 로컬 TX**로 — 중간 실패 시 모두 rollback, 다음 회차에 같은 Outbox 행을 다시 처리(`source_event_id` UNIQUE가 이중 생성을 막는 두 번째 방어).
- **HTTP는 TX 밖**(MASTER relay 선례): claim TX를 커밋한 뒤 호출, 결과는 새 TX. 외부 호출 동안 DB 잠금·연결을 잡지 않는다(원칙 §3 "외부 소켓 호출 동안 잠금을 유지하지 않는다").
- **잠금 순서(구현 결정 WH-10)**: fan-out = guard → outbox_event 행 → merchant_webhook_stream → (INSERT event·delivery). 업무 TX는 outbox_event를 INSERT만 하고 잠그지 않으며 webhook 표를 건드리지 않으므로
  업무 잠금 순서(관례 §16·§17: control → payment → …)와 교차하지 않는다. fan-out의 Payment·취소 행 읽기는 **비잠금**(`EntityManager.find`/jOOQ가 아닌 persistence Store의 비잠금 조회 — §10).
  delivery = delivery 행 하나만 잠근다.

### 5.3 표(PAYMENT DB, 새 migration — 이름·자료형은 구현 결정, 2단계 S2에서 확정)

```text
ALTER outbox_event
  + merchant_notification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
      CHECK IN ('PENDING','NOTIFIED','SUPPRESSED','NOT_APPLICABLE','HELD_DECISION','SKIPPED_PRE_DEPLOY')
  + merchant_notified_at DATETIME(3) NULL
  + INDEX ix_outbox_event_merchant_notification (merchant_notification_status, id)
  (기존 행 처리 = 범위 관찰 §18-4: 권장 SKIPPED_PRE_DEPLOY로 한 번 갱신)

merchant_webhook_fanout_guard(name PK, version)                         -- 행 1개, fan-out 직렬화용 FOR UPDATE 대상
merchant_webhook_stream(payment_id PK, last_sequence INT NOT NULL CHECK ≥ 0, version)
merchant_webhook_event(
  webhook_event_id VARCHAR(40) PK('whe_'+ULID), source_event_id CHAR(36) UNIQUE(outbox event_id),
  environment, merchant_account_id, payment_id, payment_sequence INT,
  event_type VARCHAR(32), schema_version INT, occurred_at DATETIME(3),
  body MEDIUMBLOB, body_sha256 CHAR(64), delivery_plan VARCHAR(16) CHECK IN ('DELIVER','NO_ENDPOINT'), created_at,
  UNIQUE(payment_id, payment_sequence))                                  -- insert-only
merchant_webhook_delivery(
  delivery_id PK('whd_'+ULID), webhook_event_id UNIQUE, environment, merchant_account_id,
  status CHECK IN ('PENDING','DELIVERED','GAVE_UP'), attempt_count, next_attempt_at, first_attempt_at NULL,
  claim_owner NULL, claim_until NULL, last_failure_code NULL, delivered_at NULL, gave_up_at NULL, schedule_version, version, created_at,
  CHECK 상태별 시각 짝, INDEX(status, next_attempt_at))
merchant_webhook_attempt(
  attempt_id PK, delivery_id, attempt_no, endpoint_ref VARCHAR(64), signing_key_ids VARCHAR(80),
  started_at, finished_at, outcome CHECK IN ('SUCCESS','HTTP_STATUS','TIMEOUT','CONNECTION','TLS','REDIRECT',
  'DESTINATION_BLOCKED','RESPONSE_TOO_LARGE','NO_ENDPOINT','NO_SIGNING_KEY'), http_status SMALLINT NULL,
  UNIQUE(delivery_id, attempt_no))                                       -- insert-only(append-only)
```

- 권한 migration(관례 §9.2): `outbox_event` UPDATE 추가(V2 주석이 예고한 경로. 열 단위 GRANT를 helper가 지원하지 않으면 표 단위 + **ArchUnit/시험으로 갱신 열 고정** — S2에서 확인), `merchant_webhook_event`·`merchant_webhook_attempt`
  SELECT·INSERT만(UPDATE/DELETE 1142 시험), `merchant_webhook_delivery`·`merchant_webhook_stream` SELECT·INSERT·UPDATE(DELETE 없음), guard SELECT·UPDATE.
- **WH-03(Outbox 처리 표시 위치)**: 별도 열(`merchant_notification_status`)을 Outbox에 둔다. 대안 비교 — (A) 업무 TX에 webhook 행 INSERT: 업무 TX 위험(기각, WH-02), (B) **열 추가(채택)**: 업무 코드 무변경(DEFAULT),
  미처리 탐색이 인덱스 하나, (C) Outbox anti-join(`NOT EXISTS merchant_webhook_event`): 전체 이력 스캔 또는 시각/ID 창 필요 — id는 커밋 순서가 아니라 창은 유실 위험(이벤트 56행) → 기각.
  MASTER `outbox_event`도 relay 열(claim·attempt·priority)을 V3에서 추가한 선례가 있다.

### 5.4 fan-out claim과 sequence(WH-04·WH-10)

- **직렬화**: fan-out TX는 시작에서 guard 행을 `FOR UPDATE`로 잡는다 → 인스턴스가 여러 개여도 fan-out TX는 한 번에 하나. TX가 짧아(DB만, 외부 호출 없음) 처리량 문제가 작다. lease 만료 개념이 없다(잠금은 TX와 함께 풀림 — 프로세스가 죽으면 연결 종료로 해제).
- **Payment 안 순서 = 인과 순서(도출)**: 같은 Payment의 전달 대상 이벤트는 앞 이벤트를 만든 TX가 커밋된 상태를 전제로만 다음 TX가 시작된다(승인 확정 → 취소, 취소는 한 번에 하나 — D-CANCEL-003, 만료와 승인 시작은 같은 잠금으로 배타).
  뒤 TX는 앞 TX 커밋 뒤에 행 잠금을 얻고 INSERT하므로 Outbox `id`(INSERT 시 부여)도 그 순서다. fan-out이 직렬로 `id` 순 처리하므로 sequence가 인과 순서와 같다. 커밋 안 된 작은 id(다른 Payment의 긴 TX)는 보이지 않을 뿐 다음 회차에 처리된다(Payment 간 순서는 계약하지 않는다).
- `merchant_webhook_stream`은 `INSERT … ON DUPLICATE KEY UPDATE`(또는 잠금 후 갱신)로 `last_sequence + 1`. 이중 방어 UNIQUE(payment_id, payment_sequence).
- 회차 크기·주기: Demo 설정(기본값 없음 — §5.6).

### 5.5 delivery claim·lease·fencing(MASTER relay 선례 + 외부 목적지 보강)

- claim은 짧은 TX의 native `SELECT … FOR UPDATE SKIP LOCKED`(JPA 표준 잠금 API에 없는 조합 — `JpaEntitlementRelayStore`·AUTH 회복 `claimDue` 선례, 관례 §16 "query adapter는 읽기 전용이라 선점은 persistence").
- **claim lease > 한 시도의 최대 시간 + 여유**(기동 검사 — `CancellationTimingConsistency` 선례): `claimLease ≥ connectTimeout + 전체 요청 timeout + 서명·목적지 해석 예산 + margin`. 그래도 GC·정지로 lease가 먼저 끝날 수 있다 →
  다른 worker가 같은 이벤트를 **다시 보낼 수 있고**(중복 전달 — 허용, §5.7), 늦은 worker의 결과 TX는 `claim_owner`가 다르면 상태를 덮어쓰지 않고 시도 기록만 남긴다(`CLAIM_LOST`). 이미 `DELIVERED`면 그대로(성공 사실 우선).
- **확정(신뢰성 문서 "Lease 만료만으로 이전 실행자가 호출하지 않았다고 가정하지 않는다")**: lease 만료 = 중복 가능성으로만 다룬다. 금융 효과가 없으므로 중복의 비용은 가맹점 중복 제거뿐이다.

### 5.6 재시도·backoff(Demo — 형태는 Q-WH-06, 값은 운영값 아님)

- 지수 backoff(`initial × 2^attempt`, 상한 `max`) + jitter(비율 [0,1)), MASTER relay `backoff()`와 같은 식. **포기 조건 둘(OR)**: 시도 수 ≥ `maxAttempts` 또는 첫 시도 후 경과 ≥ `maxAge`.
- 설정 `paycore.webhook.delivery.*`: `schedule-version`(필수, 행에 기록 — D-AUTH-009 관례), `initial`, `max`, `jitter-ratio`, `max-attempts`, `max-age`, `batch-size`, `claim-lease`, `poll-interval`, `enabled` — **기본값 없음, 누락·범위 밖이면 기동 실패**(D-AUTH-009, `EntitlementSettings` 선례).
- Demo 값(시험·로컬 Simulator만): initial 10s, max 1h, jitter 0.2, max-attempts 10, max-age 24h, batch 50, claim-lease 60s. 시험은 ms 단위 값을 명시 주입한다. 운영값은 LIVE Gate에서 사용자 결정(D-AUTH-009와 같은 방식).

### 5.7 순서·중복 의미(공개 계약 — 도출 이벤트 13·47·49행, 착수 38행)

| 성질 | 계약 |
|---|---|
| 전달 보장 | **at-least-once**(재시도·lease 만료·응답 유실). exactly-once를 주장하지 않는다(이벤트 70행 금지 정신) |
| 중복 | 같은 이벤트는 항상 같은 `eventId`·같은 `body` 바이트. 가맹점은 `eventId`로 중복 제거 |
| 순서 | **보장하지 않는다**(재시도·병렬 worker). 같은 Payment 안 인과 순서는 `payment.sequence`(1부터 연속)로 표현 |
| 누락 탐지 | 가맹점은 sequence 구멍을 볼 수 있다(아직 도착 안 한 재시도 또는 포기). 조회 대체 경로 = GET |
| 서로 다른 Payment | 순서·sequence 관계 없음 |
| 이벤트 = 사실 시점 | `data`는 그 사실 시점 값. 현재 상태는 GET |

- **구현 결정 WH-11(엄격 순서 전달을 하지 않음)**: Payment 단위 head-of-line 대기(앞 sequence 전달 전 뒤 sequence 보류)는 앞 이벤트가 포기되면 뒤가 영원히 막히거나 별도 해제 규칙이 필요하다. 비보장 + sequence + GET이 더 단순하고,
  나중에 엄격 순서로 바꾸는 것은 가맹점에 더 강한 보장이라 호환된다(가역). Coordinator가 공개 계약 의미로 보아 질문이 필요하다고 판단하면 Q로 올린다(§17.9).

### 5.8 포기(dead-letter)

- 포기 = `GAVE_UP`(완료 아님 — **확정** 이벤트 50행 "DLQ는 완료 상태가 아니다"). 지표·경보 대상(§11). 금융 상태·Payment·정산에 영향 없음(§17.9).
- 자동 endpoint 비활성·가맹점 차단 없음(정책 필요 — Q-WH-06 대안 (c)).
- 포기 행은 삭제하지 않는다(보관 정책 전 — §5.12).

### 5.9 수동 재전송(범위 관찰 §18-6)

- 이 작업은 상태 모델만 준비한다: `GAVE_UP → PENDING`(attempt_count·first_attempt_at 재설정 여부는 그 작업의 결정) 전이는 **운영자 명령 + 감사 행**으로만(관측 문서 "재처리는 대상·사유·권한·승인·결과 기록").
- Use Case·진입점·감사 표는 ADMIN-BOOT-001(진입점·서비스 간 인증) 이후의 후속 작업(WEBHOOK-ENDPOINT-001 또는 별도). 재전송은 같은 `eventId`·같은 본문(새 이벤트 아님) — 업무 효과 없음.

### 5.10 endpoint 없음·변경

- **WH-12**: fan-out 시점에 그 (환경, MID)의 활성 endpoint가 없으면 `merchant_webhook_event`는 남기고(`delivery_plan = NO_ENDPOINT`, sequence 소비) 전달 행은 만들지 않는다 — 나중에 endpoint가 생겨도 과거 이벤트를 자동으로 몰아 보내지 않는다(재전송은 §5.9 명령).
  LIVE는 원천이 없으므로 항상 이 경로(§7.1 fail-closed).
- endpoint URL·서명 키는 **전송 시점**에 조회한다(전달 행에 URL·키를 복사하지 않음 — 비밀 복제 금지, 변경 즉시 반영). 전송 시점에 endpoint가 사라졌으면 시도 `NO_ENDPOINT`로 기록하고 재시도 일정을 따른다(상한 포기).

### 5.11 배포·기존 행

- (검토 #1 n-1 정정) 혼재 배포 제약이 **있다**: V23 ①(DEFAULT SKIPPED_PRE_DEPLOY)과 ②(DEFAULT PENDING) 사이 INSERT는 SKIPPED가 되므로 이전 인스턴스를 모두 내린 뒤 적용한다(§20.1-2).
  ② 이후에는 새 열을 모르는 이전 코드의 INSERT도 DEFAULT로 PENDING이다(T-M1).
- 배포 전 행은 §18-4 판정(권장: 일괄 `SKIPPED_PRE_DEPLOY` — TEST 데이터뿐이며 과거 결과를 한꺼번에 가맹점에 보내지 않음).

### 5.12 보관

- 이벤트·전달·시도 행 삭제 경로를 만들지 않는다(D-CPQ-004와 같은 방식 — 보관·파기는 LIVE Gate 별도 정책). 성장 감시는 표 크기·적체 gauge(§11).

---

## 6. 서명(Q-WH-03 — 아래는 권장안의 형태, 답 전 운영 키 없음)

### 6.1 권장 방식(HMAC-SHA256, (MID, 환경)별 공유 비밀)

```text
signed_payload = "<t>" + "." + <body bytes 그대로>            t = Unix 초(전송 시각, 시도마다 새로)
v1             = lowercase hex( HMAC-SHA256(secret, signed_payload) )
header         = PayCore-Signature: t=<t>,kid=<keyId>,v1=<hex>[,kid=<keyId2>,v1=<hex2>]   (교체 중 활성 키마다 하나)
```

- **본문 그대로 서명**: 가맹점이 JSON을 다시 직렬화하지 않고 받은 바이트로 검증(F-9 — 저장도 바이트).
- **kid**: 비밀 식별자(비밀 아님 — D-CPA-001 keyId와 같은 성격). 교체 중 (MID, 환경)당 활성 비밀 최대 2개 → 서명 2개(D-CPA-001·D-MS-004 "교체용 2키 동시 허용"과 같은 방식 — 도출 아님, Q-WH-05에서 확인).
- **재전송 방지(가맹점 검증 규칙)**: `|now − t| ≤ window`(Demo 300초) 밖이면 거절, `eventId` 중복 제거. PG 쪽은 시도마다 새 `t`(재시도가 창 밖으로 밀리지 않음).
- **비교**: 상수 시간 비교(가맹점 안내 + Simulator 구현).
- **환경 분리(확정 보안 15행)**: TEST·LIVE 비밀은 다르다. TEST 비밀로 LIVE 이벤트를 서명하는 경로가 없어야 한다(T-E1).
- **구현 위치(F-7)**: required Port `MerchantWebhookSigner`(application) → `HmacMerchantWebhookSigner`(adapter.integration, `javax.crypto.Mac`). 비밀 원문은 adapter 안에서만, 로그·예외·toString 금지.

### 6.2 비밀이 어디에 있고 어떻게 들어오는가(Q-WH-05)

- **도출(보안 19·20행 + 이벤트 18행)**: (1) 서명자는 원문이 필요하다 → 해시 저장(D-CPA-001 방식) 불가, **관리 키로 암호화 저장** 또는 비밀 저장소 직접 주입. (2) 비밀은 이벤트·로그·이미지·소스에 넣지 않는다 →
  **MASTER Outbox/권리 Snapshot 채널로 비밀 원문을 전파할 수 없다**(그 채널은 Outbox·Inbox 행에 내용을 남긴다).
- 따라서 원천은 (a) PAYMENT가 생성·암호화 보관(서명 주체가 소유) 또는 (b) 비밀 저장소에 운영자가 넣고 PAYMENT가 읽음 중 하나 — 선택은 Q-WH-05. 발급 화면·명령은 ADMIN 이후(§18-1).
- **이 작업(답 전에도 가능한 것)**: required Port `MerchantWebhookSigningKeys`(활성 키 목록 조회)와 **TEST 전용 설정 구현**(`paycore.webhook.test-endpoints[*].signing-keys` — Base64 ≥ 32바이트, 환경이 TEST가 아니면 기동 거부,
  toString 마스킹). 시험 키는 저장소의 시험 자원·Demo 설정에만(운영 키 없음 — D-MS-004 선례).

---

## 7. endpoint 등록·검증·목적지 안전

### 7.1 endpoint 원천(Q-WH-04)

- required Port `MerchantWebhookEndpointDirectory.find(environment, merchantAccountId): MerchantWebhookEndpoint?`(URL·endpointRef·버전). 이 작업 구현은 **설정 기반 TEST 전용**(`ConfiguredMerchantWebhookEndpointDirectory`):
  항목의 environment ≠ TEST면 기동 실패, LIVE 조회는 항상 `null` → LIVE 이벤트는 `NO_ENDPOINT`(fail-closed — 등록 경로·SSRF 정책·운영 비밀이 정해지기 전 LIVE로 아무것도 보내지 않는다).
- 원천을 MASTER(등록·전파)로 할지 PAYMENT(셀프서비스 API)로 할지, (MID, 환경)당 endpoint 수, LIVE 변경의 승인 등급은 Q-WH-04. Port가 원천을 감추므로 답과 무관하게 전달 코어는 같다.

### 7.2 목적지 검사(Q-WH-07 — 아래는 권장안 형태, 답 전에는 이 형태를 **TEST 설정 검증**에만 적용)

| 단계 | 규칙(권장) | 근거 |
|---|---|---|
| URL 문법(등록·설정 시) | `https`만, host 필수·소문자 정규형(D-MD-009 정신 — 자동 정규화 없음), IP 리터럴 금지(D-MD-014 "WEB은 host만" 정신), userinfo·fragment 금지, 포트는 443만(또는 Q 답), 길이 상한 | 보안 24행 |
| DNS 해석(전송 시) | 해석된 **모든** 주소가 공인 unicast일 때만: loopback(127/8·::1), 사설(10/8·172.16/12·192.168/16), link-local(169.254/16 — 클라우드 metadata 포함·fe80::/10), CGNAT 100.64/10, ULA fc00::/7, 0.0.0.0/8, multicast·broadcast·예약(240/4), 문서용, IPv4-mapped/6to4/NAT64에 감싼 금지 대역 → `DESTINATION_BLOCKED` | 보안 24행 "사설망" |
| 연결 고정 | 검사한 주소에만 연결(해석 → 검사 → 그 IP로 소켓, TLS SNI·hostname 검증은 원 host) — DNS rebinding(검사 후 재해석) 차단 | 보안 24행 |
| redirect | 따라가지 않는다(3xx = 실패 `REDIRECT`) | 보안 24행 "redirect 우회" |
| TLS | 인증서·hostname 검증 필수, 사용자 정의 trust 없음(운영) | 보안 18행 |
| 시간 | connect·응답 header·전체 요청 timeout(Demo: 3s·10s·15s), slow-drip 차단은 전체 timeout | 신뢰성 |
| 응답 | 본문 최대 N바이트(Demo 4 KiB)만 읽고 버림, 초과는 연결 종료(2xx 판정은 유지할지 — WH-09: 상태 코드로 판정, 초과는 기록만) | 자원 보호 |
| 프록시 | 시스템 프록시·환경변수 무시(명시 설정만) | 우회 차단 |
| 로그 | URL 원문(경로·query) 대신 endpointRef·host 해시 | §11 |

- **TEST 시험 예외(구현 결정 WH-13 — Q-WH-07과 함께 확인)**: Testcontainers·시험 수신기는 loopback/사설망이다. `paycore.webhook.destination.test-allowlist`(정확한 host:port 목록, `http` 허용은 이 목록만)를
  두고 **profile이 아니라 값으로** 켠다. 목록이 있는데 TEST 아닌 endpoint가 설정되면 기동 거부, 목록 항목은 시험 자원·로컬 compose 설정에만. 차단 규칙 자체의 시험은 목록 없이 별도(T-S5).
- **구현(WH-14)**: 연결 고정을 위해 사용자 정의 DNS resolver가 필요하다. JDK `HttpClient`는 resolver를 바꿀 수 없어(IP로 연결하면 SNI·hostname 검증이 깨진다) Apache HttpClient 5(`DnsResolver`)를 후보로 한다 —
  **Boot 4.1.1 BOM 관리 여부·버전을 S0에서 확인**(관리 항목이면 카탈로그에 버전 없이 추가, 아니면 질문 없이 쓰지 않고 대안 재검토). 새 의존성 → GRADLE-DEPS×5·CVE-SCAN. 주소 분류 함수는 순수 함수(바이트 입력)로 domain 또는 adapter에 두고 표 전 행을 단위 시험.

---

## 8. 가맹점 조회와의 관계

- **확정(AC1 §10.2·D-CP-003·범위 40행)**: 현재 상태의 권위는 GetPayment. Webhook은 "무언가 확정됐다"는 **통지**이며 본문은 그 사실 시점 값이다(§4.2).
- **도출(가맹점 안내)**: 주문 완료·상품 제공은 AUTHORIZED 확인 후(범위 40행). Webhook 수신만으로 처리해도 되지만(서명 검증 전제) 중요한 동작 전 GET 확인을 권장, Webhook 미수신·포기·sequence 구멍은 GET으로 메운다.
  confirm·cancel 202("확인 중")를 받은 가맹점은 Webhook 또는 GET 폴링 중 하나로 결과를 안다 — 둘 다 같은 사실 범위(§4.4).
- 같은 시점 불일치 허용: Webhook 처리 지연 동안 GET이 더 새 상태를 보일 수 있다(예: `payment.authorized` 도착 전 이미 부분취소됨). 가맹점 계약 문서에 명시.
- Webhook은 조회를 대체하지 않는다: Webhook 전달 실패·포기가 GET 결과를 바꾸지 않는다(T-V2).

---

## 9. Simulator 가상 가맹점 수신기

- **새 모듈 `com.paycore.simulator.merchant`**(구현 결정 WH-15, D-AUTHN-004 "외부 상대방은 institution-simulator" 관례 — 가맹점은 기관이 아니지만 PG 바깥 상대방 대역이다):
  - inbound `POST /sim/merchant/v1/webhooks/{receiverId}` — 서명 검증(kid별 시험 키, 상수 시간, 재전송 창), `eventId` 중복 제거, 수신 원장 append-only(`merchant_webhook_receipt` — eventId·sequence·type·body_sha256·검증 결과·수신 횟수),
    응답 시나리오(receiver별 설정: 즉시 2xx / n회 5xx 후 2xx / 지연 / 3xx / 큰 본문 / 저장 후 연결 끊기 = "응답 유실").
  - 조회 `GET /sim/merchant/v1/webhooks/{receiverId}/receipts`(E2E 확인용, 시험 client 키 인증 — 기존 Simulator client 인증 관례).
  - Simulator DB migration(V8·권한 V9 — 원장 SELECT·INSERT만, `VanLedgerPrivilegeGuard` 방식 기동 검사), `SimulatorArchitectureSpec`·`test_simulator_isolation.py`(PayCore 패키지 import 금지) 적용.
- **payment-api 시험 대역**: 장애 주입은 시험 프로세스 안 실제 HTTP 수신기(`ProgrammableMerchantReceiver` — `src/test`, `ProgrammableVan` 선례)로 한다(시점 제어·응답 유실·lease 경합). Simulator 수신기는 계약 golden·로컬 E2E용.
- **서명 golden**: `webhook-signature-v1.golden.json`(입력 비밀·t·body 바이트 → 서명 hex, 교체 2서명 헤더, 재전송 창 경계) — harness `test_webhook_signature_golden.py`가 Python 표준 라이브러리(`hmac`)로 **독립 생성**해
  `apps/{payment-api,institution-simulator}/src/test/resources/contracts/`와 바이트 비교(AU·CN van golden 선례, `.gitattributes -text`).

---

## 10. JPA·jOOQ·CQRS(원칙 §3 — 이번 변경에서 하는 일 → 예 → 이유 → 주의)

- **Domain/Entity 분리**: 역할 — 순수 domain `MerchantWebhookDelivery`(상태 PENDING/DELIVERED/GAVE_UP, `recordSuccess`·`recordFailure(now, schedule)`·`giveUp` 규칙, 재시도 일정 계산은 `WebhookRetrySchedule` 값 객체)와
  `JpaMerchantWebhookDeliveryEntity`(행 모양만) + Mapper(`reconstitute` — 상태·시각 짝이 맞지 않으면 거부). 예 — 결과 TX가 행을 `FOR UPDATE`로 잠근 뒤 domain에 "실패, 지금 시각"을 주면 domain이 다음 시각 또는 포기를 정하고 Mapper가 열에 반영한다.
  이유 — 재시도·포기 규칙을 Spring 없이 표 시험(T-D0)하고, Entity는 DB 제약만 지킨다. 주의 — 분리한 domain 객체 변경은 자동 추적되지 않으므로 Mapper가 version과 함께 반영(stale 덮어쓰기 금지 — 원칙 §3).
- **영속성 컨텍스트·잠금**: claim은 native `FOR UPDATE SKIP LOCKED`로 id를 잠근 **뒤** 그 행만 Entity로 읽는다(잠근 뒤 읽으므로 1차 캐시에 낡은 값이 없다 — `JpaEntitlementRelayStore` 선례). 결과 TX는 별도 TX라 영속성 컨텍스트를 공유하지 않는다.
  `@Version`은 잠금 밖에서 읽은 낡은 객체 저장을 막는 두 번째 방어(관례 §16 정정 문장과 같은 뜻), 실제 직렬화는 비관적 X 잠금. 충돌 시 무조건 재시도하지 않고 다시 잠가 재판정.
- **insert-only 사실**: `merchant_webhook_event`·`merchant_webhook_attempt`는 `@Immutable` Entity, `@OneToMany(cascade)` 없음, runtime 권한 SELECT·INSERT(1142 시험) — cascade·orphanRemoval이 기록을 지우는 경로가 없다.
- **flush ≠ commit**: fan-out TX에서 이벤트 INSERT를 `flush()`해 UNIQUE 위반을 TX 안에서 감지하되(→ `PersistenceFailure.UniqueConflict` → 그 행은 이미 처리됨으로 수렴), delivery worker는 **커밋된** 전달 행만 본다.
  fan-out 커밋 전 crash면 이벤트·전달·Outbox 표시 모두 없음(T-F1).
- **jOOQ(CQRS 조회)**: 적체·연령·포기 수·fan-out 지연 gauge는 `MerchantWebhookBacklogReader` → `JooqMerchantWebhookBacklogReader`(adapter.query, 읽기 전용, 시각 `UtcWallClock` 문자열·비교는 UTC 리터럴 — 관례 §9.1·§13). 명령 판정에 쓰지 않는다(ArchUnit — CPQ CQ-06 방식).
  fan-out의 Payment·취소 행 읽기는 명령 TX 안이므로 jOOQ Reader가 아니라 persistence의 비잠금 조회 Port(`MerchantNotificationSourceStore` — ③ 프로토콜 Store 성격)로 한다(JPA와 jOOQ가 같은 TX라고 가정하지 않는다 — 원칙 §4).
- **Flyway가 schema 기준**: 새 표·열·CHECK·권한은 migration, jOOQ 생성 코드는 migration에서 재현. `ddl-auto` 없음.
- **(rev 0.2 — m-7·D-WH-009) Outbox Entity 불변**: 역할 — `JpaOutboxEventEntity`는 `@Immutable`이라 영속성 컨텍스트가 그 행의 변경을 감지해도 UPDATE를 보내지 않는다. 예 — fan-out은 Entity를 읽지 않고 native PK UPDATE로
  `merchant_notification_*` 두 열만 바꾸며(그 두 열은 Entity에 매핑하지 않음 — INSERT는 DEFAULT), DB 권한도 그 두 열뿐이라(나머지 열 1143) JPA·SQL 어느 쪽으로도 publish_status·payload를 바꿀 수 없다.
  이유 — FINANCIAL 전달 표시·통합 계약 본문은 Webhook과 무관한 사실이다. 주의 — native UPDATE는 영속성 컨텍스트를 거치지 않으므로 같은 TX에서 그 Outbox Entity를 다시 읽지 않는다(fan-out은 행마다 새 TX, 읽기도 native).

---

## 11. 비노출·로그·관측

- 본문 키 집합 고정(§4.2), 헤더에 비밀 없음(kid만). 로그: `operation=WebhookDelivery webhookEventId deliveryId attemptNo outcome httpStatus endpointRef` — 본문·서명 hex·비밀·URL 경로/query·응답 본문 없음.
  설정 객체 `toString` 마스킹(`EntitlementSettings.Dispatch` 선례), 예외 메시지에 URL·본문 금지(`SafeThrowable` 관례).
- 지표(label에 ID 없음 — 원칙 §1): counter `paycore.webhook.fanout{result=NOTIFIED|SUPPRESSED|NOT_APPLICABLE|HELD_DECISION|NO_ENDPOINT}`, `paycore.webhook.delivery{result=DELIVERED|RETRY|GAVE_UP|CLAIM_LOST|BLOCKED}`,
  timer `paycore.webhook.delivery.duration`, gauge `paycore.webhook.pending`·`paycore.webhook.pending.oldest.age`·`paycore.webhook.gave_up`·`paycore.webhook.fanout.oldest.age`(미처리 Outbox 최고 연령 — 관측 문서 "Outbox 최고 연령").
- **구현된 지표(최종 검토 m-1 — 이 목록이 위 계획을 대체한다; label은 `result` 하나뿐, ID·URL·MID 없음, domain·application은 Micrometer를 모르고 batch adapter가 회차 결과로 기록 — AUTH-001 선례)**:
  - counter `paycore.webhook.fanout{result=notified|no_endpoint|suppressed|not_applicable|failed|deferred}`(`notified` = 전달 행을 만든 통지, HELD_DECISION은 rev 0.2에서 폐기 — §4.5).
  - counter `paycore.webhook.delivery{result=delivered|already_delivered|retry|gave_up|claim_lost|record_failed|released_lease_short|blocked}`(`blocked` = 보낸 시도 중 `DESTINATION_BLOCKED`·`DESTINATION_REJECTED` — retry·gave_up과 겹치는 원인 축).
    lease 부족 되돌림은 저장소 답대로 센다: 실제로 되돌림(true) → `released_lease_short`, false(그 사이 claim을 잃었거나 행 상태가 바뀜) → `claim_lost`, 저장소 실패(재시도 소진 포함) → `record_failed`(최종 검토 #3 n-1).
  - timer `paycore.webhook.delivery.duration`(실제로 보낸 시도마다 — 보낸 시도의 준비(이벤트 읽기·무결성 확인·조회)·서명·전송 시간, label 없음).
  - gauge `paycore.webhook.fanout.{failed,failed.oldest_age_seconds,pending,pending.oldest_age_seconds}`, `paycore.webhook.delivery.{pending,pending.oldest_due_age_seconds,gave_up}`.
- 경보 기준값은 운영값(LIVE Gate) — 이 작업은 gauge만.

---

## 12. 시험 계획(실제 MySQL 8.4.11 Testcontainers·실제 HTTP — Mock 단독 금지)

| # | 시험 | 계층 | 기대 |
|---|---|---|---|
| T-D0 | 재시도 일정·포기 표 | 단위 `WebhookRetryScheduleSpec`·`MerchantWebhookDeliverySpec` | backoff 수열·상한·jitter 범위, 시도 수/경과 상한 각각·동시 도달, 상태 전이 불가 조합 거부 |
| T-C1 | 분류·억제 표 §4.1 | 단위 `MerchantNotificationClassifierSpec` | 10종 × (Payment 상태, initiator) 전 행 — P1 억제, OPERATOR → HELD_DECISION(답 전) |
| T-C2 | 본문 계약 | 단위 `WebhookBodyContractSpec` | type별 키 집합 정확 비교, 금액 정수·시각 `Z`, 노출 금지 필드 부재, 같은 입력 = 같은 바이트 |
| T-F1 | fan-out 원자성 | `@PaymentDbTest` + `FaultInjectionConfig` | 이벤트 INSERT 뒤·Outbox 표시 전 예외 → 전부 rollback, 재실행 시 정확히 1건(UNIQUE), commit 불명확(`CommitFaults`) 뒤 재실행 수렴 |
| T-F2 | 업무 TX 무영향 | 기존 confirm·cancel·만료 시험 + `@PaymentDbTest` | fan-out 장애(표 권한 회수·제약 위반 주입) 중에도 승인·취소·만료 커밋·응답 불변, Outbox 행은 PENDING으로 남음 |
| T-F3 | P1·운영자 억제 | `@PaymentDbTest`(Invalid/우회 Fixture: `lateApprovalOnFailedPaymentForSuppression`) | P1 `PaymentAuthorized`·P1 운영자 `PaymentCancelled` → 가맹점 이벤트 0, Outbox `SUPPRESSED`. AUTHORIZED 운영자 취소 → `HELD_DECISION`(답 전) |
| T-F4 | sequence | `@PaymentDbTest` 공식 경로 | 승인 → 부분취소 → 취소 실패 → 잔액 취소 = 1..4 연속, 억제 이벤트는 번호 없음 |
| C-F1 | fan-out 경합 | 다중 연결 N=4 worker 동시 회차 × 200 이벤트 | guard 직렬화: 중복 이벤트 0, sequence 구멍·중복 0, 처리 누락 0 |
| T-D1 | 전달 기본 | `@PaymentApiTest` + `ProgrammableMerchantReceiver` | 2xx → DELIVERED, 5xx·timeout·연결 거부·3xx → 재시도 일정, 상한 → GAVE_UP, 시도 행 append-only 수 = 시도 수 |
| T-D2 | 응답 유실 | 수신기가 저장 후 연결 끊기 | 재전송 본문 바이트 동일·eventId 동일·`t`만 다름, 수신기 중복 제거 |
| T-D3 | worker crash | claim 뒤 HTTP 전 정지(시험 hook) / HTTP 뒤 결과 TX 전 정지 | lease 만료 후 다른 worker가 전송(두 번째는 중복 허용), 늦은 결과는 `CLAIM_LOST`로 상태 미덮어쓰기, DELIVERED 우선 |
| C-D1 | claim 경합 | 4 worker × 500 전달, 실 MySQL | 같은 lease 동안 한 행을 둘이 잡지 않음(SKIP LOCKED), 최종 상태 일관, deadlock 0 |
| T-S1 | 서명 golden | 단위 + harness | payment-api·Simulator가 golden과 바이트 일치, 교체 2서명 헤더 |
| T-S2 | Simulator 검증 | Simulator `WebhookReceiverVerificationSpec` | 잘못된 서명·kid 없음·창 밖 t·본문 1바이트 변경 → 거절(4xx), 정상 → 저장 |
| T-S5 | 목적지 차단 | `WebhookDestinationGuardSpec`(시험 resolver 주입) | 금지 대역 전 행 차단, 공인+사설 혼합 해석 → 차단, 재해석 rebinding 시나리오 → 검사한 IP에만 연결, http·IP 리터럴·userinfo → 설정 거부 |
| T-S6 | redirect·크기·slow | `WebhookRedirectAndSizeSpec` | 3xx 미추종, 상한 초과 본문 연결 종료, 느린 응답 전체 timeout |
| T-E1 | 환경 분리 | `@PaymentApiTest` | LIVE Payment 이벤트 → NO_ENDPOINT·전달 행 0, TEST 키로 LIVE 서명 경로 없음, LIVE endpoint 설정 → 기동 실패 |
| T-V1 | 조회 일관성 | `@PaymentApiTest` | 시나리오별 Webhook 사실 집합과 GET 표시 대조(§4.4) |
| T-V2 | 금융 무영향 | `@PaymentApiTest` | 수신기 영구 실패·포기 동안 GET·취소 가능액·정산 이벤트(Outbox `publish_status`) 불변 |
| T-N1 | 비노출 | `CapturedLogs` | 로그·예외·설정 toString에 비밀·본문·서명·URL 경로 없음 |
| T-P1 | 권한 | `DatabaseAccountSeparationSpec` 확장 | event·attempt UPDATE/DELETE 1142, delivery DELETE 1142, outbox UPDATE 허용 열 범위(S2 결정) |
| T-R1 | 재시작 | 컨텍스트 재기동 | PENDING 적체·미처리 Outbox가 재시작 후 이어서 처리, 중복 이벤트 0 |
| T-Z1 | 비UTC JVM | `withJvmTimeZone("Asia/Seoul")` | `createdAt`·`t`가 UTC 기준, 저장 시각과 일치 |
| T-M1 | 혼재 배포 | migration 시험 | 새 열 추가 후 이전 INSERT 열 목록으로 넣은 Outbox 행 = PENDING |
| A-1 | ArchUnit | `ArchitectureSpec` | 서명자·전송자 Port 구현은 `adapter.integration`만, application에 `javax.crypto`·`java.net` 없음(기존 규칙), Backlog Reader 사용처 = gauge 서비스, 재전송 Use Case 진입점 없음(만들 경우) |

- Fixture: 상태는 공식 경로(`ConfirmFixture`·`CancelFixture`·만료 Use Case)로 만들고, P1·운영자 행처럼 운영 경로가 아직 없는 사실만 의도를 이름에 적은 Invalid/우회 Fixture(root 연결 INSERT 또는 domain 공개 경로 시험 호출)로 만든다. Fixture Monkey 미사용.
- RED 기록: 단계마다 RED-C·RED-A·핵심 변이 RED(P1 억제 제거 → T-F3, guard 제거 → C-F1 sequence 중복 관찰 시도, claim 소유자 확인 제거 → T-D3, 목적지 검사 제거 → T-S5, redirect 허용 → T-S6, 본문 재직렬화 → T-D2·T-S1).
  경합 변이 RED가 재현되지 않으면 "변이 RED 미확보"로 정직하게 기록한다.

---

## 13. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | 적용(표시·무효과) | 본문 금액·통화·승인/취소 값은 불변 거래·payload에서 그대로(재계산 없음, T-C2), Webhook이 어떤 금융 상태도 바꾸지 않음(T-V2), 업무 TX 무변경(T-F2) |
| Transaction Safety | 적용 | fan-out 한 로컬 TX 원자성(T-F1), HTTP는 TX 밖(구조 + T-D3), 결과 TX 행마다, 업무 TX에 webhook 쓰기 없음(WH-02) |
| Idempotency | 적용 | `source_event_id` UNIQUE(fan-out 재실행 수렴), 같은 `eventId`·같은 본문 재전송(T-D2), 가맹점 중복 제거 계약, sequence UNIQUE |
| Concurrency Control | 적용 | guard 직렬화(C-F1), SKIP LOCKED claim·소유자 fencing(C-D1·T-D3) — 실제 MySQL 다중 연결 |
| Recoverability | 적용 | 재시작 재개(T-R1), lease 만료 재전송, GAVE_UP 상태 보존(재전송 명령은 후속), GET 대체 경로(§8) |
| Reconciliation | 간접 | Outbox ↔ 가맹점 이벤트 대응(미처리 gauge·`source_event_id`), 전달 원장(시도 append-only)으로 "보냈는가" 대조 가능. 가맹점 대사는 GET·Webhook 같은 사실 범위(T-V1). 금융 대사 대상 아님 |
| Auditability | 적용(전달 증거) | 시도 기록 append-only(시각·결과·키 ID·endpointRef), 억제·보류 사유가 Outbox 상태로 남음, 재전송 감사는 후속(§5.9) |
| Observability | 적용 | §11 "구현된 지표": counter `paycore.webhook.{fanout,delivery}{result}`·timer `paycore.webhook.delivery.duration`·gauge(적체·최고 연령·포기 — `MerchantWebhookSchedulersSpec`·T-V2), 구조화 로그 비노출(T-N1) |

---

## 14. BOM·Kafka/Redis

- **BOM**: 후보 새 의존성 Apache HttpClient 5(WH-14) — Boot 4.1.1 BOM 관리 버전만 사용(카탈로그에 버전 없이), 관리 밖이면 쓰지 않고 S0에서 대안(JDK HttpClient + 해석 결과 검사의 TOCTOU 한계 명시) 판정 요청. 빌드 파일 변경 시 GRADLE-DEPS×5·CVE-SCAN.
  Kotlin·Hibernate·jOOQ 버전 변경 없음(라이브러리별 최신 숫자를 섞지 않는다 — 원칙 §2).
- **Kafka/Redis**: 도입하지 않는다(ADR-001). Outbox → fan-out → 전달 행 → HTTP는 소유 DB 로컬 TX와 SKIP LOCKED로 at-least-once를 증명한다. 브로커가 있어도 가맹점 HTTP 전달의 중복·재시도·서명·SSRF 문제는 그대로이며,
  Kafka가 Webhook 전달 정합성이나 금융 안전성을 대신하지 않는다(원칙 §5). ADR-001 §3 trigger(DB 폴링 적체가 목표 초과·다중 독립 consumer)는 gauge로 관찰만 한다. Redis는 쓰지 않는다(재전송 방지는 가맹점 쪽 eventId 저장 — PG에 캐시 없음).

---

## 15. 단계별 구현 계획(2단계 TDD — 각 단계 시험 먼저 → RED-C/RED-A 기록 → 최소 구현 → 핵심 변이 RED)

| 단계 | 내용 | 막힘(질문) | 시험(추정) |
|---|---|---|---|
| S0 | 입력 hash 재고정, §18 판정 반영(rev 0.2), BOM의 httpclient5 관리 여부 확인(WH-14), 모듈별 기준 시험 수 측정, §19 실행 기록 절 개설 | Coordinator | 0 |
| S1 | domain·application 순수: 분류기(§4.1 — OPERATOR는 HELD_DECISION), 본문 조립기(§4.2), `WebhookRetrySchedule`·`MerchantWebhookDelivery`, 주소 분류 함수, Port 정의(`MerchantWebhookEndpointDirectory`·`MerchantWebhookSigningKeys`·`MerchantWebhookSigner`·`MerchantWebhookSender`·Store들) | Q-WH-01·02는 답 전 보류 동작으로 진행 | 60~90 |
| S2 | migration(표·열·CHECK·권한), JPA Entity·Mapper·Store, fan-out 서비스·scheduler, T-F1~F4·C-F1·T-P1·T-M1·T-R1(fan-out) | §18-4 기존 행 판정 | 30~45 |
| **검토 #1** | S1·S2(분류·억제·본문 비노출·fan-out 원자성·sequence 증거) | — | — |
| S3 | delivery: claim/lease Store, 결과 TX, HMAC 서명 adapter(TEST 설정 키), 목적지 검사 + HTTP adapter, 설정(기본값 없음)·기동 검사, `ProgrammableMerchantReceiver`, T-D1~D3·C-D1·T-S1·T-S5·T-S6·T-E1·T-N1 | Q-WH-03(서명 형태 — 답이 다르면 adapter만 교체), Q-WH-07(LIVE 규칙 — TEST 검증에만 적용) | 45~70 |
| **검토 #2** | S3(TX 밖 호출·fencing·SSRF·비밀 비노출) | — | — |
| S4 | Simulator `merchant` 모듈(수신·검증·원장·시나리오·조회), migration·권한·기동 검사, golden + harness 시험, T-S2 | Q-WH-03 | 20~35(+harness 2~4) |
| S5 | 지표·gauge·Backlog Reader, T-V1·T-V2·T-Z1·A-1, 문서(관례 새 절, AC1 §10.2·CC §14-6·CPQ §1.2 교차 참조, HVC §7 기록), 전체 check → VERIFY-GATE·DEPS/CVE 보고 | — | 15~25 |

- 질문 답이 나오면: Q-WH-01 → 분류 표 1행 + `HELD_DECISION` 행 처리 Use Case(일회성 수렴), Q-WH-02 → 이벤트 종류/필드 추가(가산적), Q-WH-03·05 → 서명 adapter·키 원천, Q-WH-04 → endpoint 원천 구현(후속 작업), Q-WH-06 → 일정 형태, Q-WH-07 → 목적지 규칙 값.
- timeout 제안(§18-9): 단계 모듈 시험 30분, VERIFY-GATE 90분(현행 5400), 수정 반복 실패당 3회. 호스트 메모리 부족 중단은 FAIL/환경으로 기록.

---

## 16. 구현 결정 목록(가역적, 질문 아님 — Coordinator가 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| WH-01 | `PaymentRequested`는 보내지 않음 | 가맹점이 동기 생성 응답으로 앎, 추가는 가산적 |
| WH-02 | 업무 TX 무변경, fan-out은 별도 로컬 TX | Webhook 결함이 금융 TX를 rollback하지 않게(§5.2) |
| WH-03 | Outbox 처리 표시 = 새 열 `merchant_notification_status`(+ 인덱스), `publish_status`는 건드리지 않음 | F-1, 대안 비교 §5.3 |
| WH-04 | Payment별 연속 `sequence`(억제 이벤트 번호 없음), `merchant_webhook_stream` + UNIQUE | 이벤트 47행, F-4 |
| WH-05 | 공개 `eventId` = 새 ID(`whe_`+ULID), Outbox `event_id` 비노출, 재전송 내내 같음 | 내부 식별자 비노출, 이벤트 15행 |
| WH-06 | 본문 = GET 노출 필드의 부분집합(§4.2 표), `cancellableAmount`·hold·FORCED 미포함 | 새 노출 없음, D-CPQ-001·005 |
| WH-07 | payload에 없는 공개 재료(`merchantCancelId`)는 fan-out TX에서 불변 행 비잠금 읽기, 시점 값은 payload | F-8 |
| WH-08 | 본문은 fan-out 때 바이트로 고정(`MEDIUMBLOB` + sha256), `JSON` 열 미사용 | F-9, 서명·중복 동일성 |
| WH-09 | 성공 = 시간 안 2xx, 그 외 모두 재시도 대상(상한 포기), 응답 본문 해석 안 함 | 단순·보수, 4xx 즉시 포기는 Q-WH-06 대안 |
| WH-10 | fan-out 직렬화 = guard 행 `FOR UPDATE`, Outbox `id` 순 처리, 잠금 순서 guard → outbox → stream | §5.4 인과 순서 |
| WH-11 | 엄격 순서 전달 없음(비보장 + sequence + GET) | §5.7, 강화는 호환 |
| WH-12 | endpoint 없음 = 이벤트는 기록·전달 행 없음(`NO_ENDPOINT`), URL·키는 전송 시점 조회 | 비밀 복제 금지, LIVE fail-closed |
| WH-13 | TEST 시험 수신기용 정확 host:port allowlist(값으로 켬, LIVE와 공존 시 기동 거부) | 시험 가능성, Q-WH-07과 함께 확인 |
| WH-14 | 연결 고정용 HTTP 구현 후보 Apache HttpClient 5(BOM 관리 확인 조건) | §7.2 DNS rebinding |
| WH-15 | Simulator 새 모듈 `merchant`, payment-api 시험은 `ProgrammableMerchantReceiver` | D-AUTHN-004 관례, `ProgrammableVan` 선례 |
| WH-16 | 명명(관례 §14): Aggregate `MerchantWebhookDelivery` → `MerchantWebhookDeliveryRepository`/`JpaMerchantWebhookDeliveryRepository`, ③ Store `MerchantWebhookEventStore`·`MerchantWebhookAttemptStore`·`MerchantNotificationFanoutStore`(Outbox claim·표시)·`MerchantNotificationSourceStore`, Reader `MerchantWebhookBacklogReader`, provided `FanOutMerchantNotificationsUseCase`·`DeliverMerchantWebhooksUseCase`, 서비스 `MerchantNotificationFanoutService`·`MerchantWebhookDeliveryService`, scheduler `adapter.batch.MerchantWebhookSchedules`, adapter `HmacMerchantWebhookSigner`·`HttpMerchantWebhookSender`·`ConfiguredMerchantWebhookEndpointDirectory` | §14, 모듈은 §18-5 |
| WH-17 | 설정 `paycore.webhook.*` 전부 기본값 없음·기동 검사(lease > 시도 최대 시간 + margin 등), 일정 버전을 전달 행에 기록 | D-AUTH-009, MASTER·CANCEL 선례 |

---

## 17. 질문 후보(Coordinator → 사용자) — rev 0.2: 모두 답변됨

| 질문 | 결정 | 답변 요지 | 반영 |
|---|---|---|---|
| Q-WH-01 | **D-WH-001** | AUTHORIZED Payment의 운영자 보정 취소·취소 실패를 알림, `initiator: OPERATOR`만(merchantCancelId·운영자 정보 없음), P1 숨김 | §4.1, §4.2.0 |
| Q-WH-02 | **D-WH-002** | v1 hold 이벤트·필드 없음(RECON 해제 Use Case와 함께 재결정) | §4.4 |
| Q-WH-03 | **D-WH-003** | HMAC-SHA256 (MID,환경)별 비밀, `{t}.{body}`, `PayCore-Signature: t,kid,v1`, 교체 중 2서명, Demo 창 300초 | §6, WH-19·WH-20 |
| Q-WH-04 | **D-WH-004** | MASTER 원본·ADMIN 등록·LIVE 2인 승인·snapshot 전파, ADMIN 전 TEST 설정만·LIVE fail-closed | §7.1, WEBHOOK-ENDPOINT-001 |
| Q-WH-05 | **D-WH-005** | PAYMENT 발급 whsec_·봉투 암호화·1회 표시·활성 최대 2·폐기 append-only, ADMIN 전 TEST 키 설정만 | §6.2, WEBHOOK-ENDPOINT-001 |
| Q-WH-06 | **D-WH-006** | 유한 재시도 → GAVE_UP+경보, 4xx도 재시도, 자동 비활성 없음, Demo 10s~1h·10회·24h(기본값 없음), 전달 실패 금융 효과 없음 | §5.6·§5.8 |
| Q-WH-07 | **D-WH-007** | 엄격 목적지(https·443·공인 IP·고정 연결·redirect 금지·TLS·크기·timeout·proxy 무시), TEST 정확 host:port allowlist만 | §7.2 + rev 0.2 m-4~m-6 |
| Q-WH-08(분할) | **D-WH-008** | WEBHOOK-001 = 전달 핵심(LIVE fail-closed), 등록·발급·재전송은 WEBHOOK-ENDPOINT-001, LIVE Gate 조건 추가 | §1.2, §18-1 |

§17.9 항목은 Coordinator가 도출로 확정했다. 아래는 rev 0.1 질문 원문(기록)이다.

### Q-WH-01 AUTHORIZED 결제의 운영자 보정 취소를 가맹점 Webhook으로 알릴지

- **배경**: 운영자 보정 취소(D-CANCEL-005 — 사고·대사 보정만, 2인 승인)는 가맹점이 요청하지 않은 반환이다. GET은 이미 이력에 `initiator: OPERATOR`만 보인다(D-CPQ-002 (i) — 신원·사유·approvalId·목적 없음). CN §15 판정 (6)이 통지 여부를 이 작업에 넘겼다.
  가맹점 정산(반환 차감)과 `cancellableAmount`에 영향이 있다. P1(가맹점에 FAILED로 알려진 결제)의 운영자 취소는 D-CPQ-002 (ii)로 이미 숨긴다(통지 안 함 — 도출).
- **권장안**: **알린다** — `payment.cancelled`(실패 시 `payment.cancel_failed`)에 `initiator: "OPERATOR"`만 붙이고 `merchantCancelId` 없음, 운영자 정보·사유 없음(GET (i)과 같은 최소 내용). 이유 — 가맹점이 모르는 반환을 Webhook으로만 받는 가맹점이 놓치면
  주문·재고·정산 대사가 어긋난다. GET과 같은 사실 범위라 새 노출이 없다.
- **대안**: (b) 알리지 않음(GET에서만 보임) — Webhook만 쓰는 가맹점은 반환을 모른다. (c) 일반 취소처럼(`initiator` 없이) — 가맹점이 자기 요청으로 오인(D-CPQ-002 대안 (d)와 같은 문제). (d) 운영자 취소 전용 이벤트 종류(`payment.cancelled_by_operator`).
- **영향**: 분류 표 1행, `HELD_DECISION` 행 수렴 처리, 가맹점 문서 문구, 운영자 취소 진입점(ADMIN-BOOT-001) 이후 실제 발생.
- **막히는 작업**: OPERATOR 개시 취소 이벤트의 fan-out만(답 전 `HELD_DECISION` — 유실·선점 없음). 운영 경로에 아직 운영자 취소가 없다(D-CANCEL1-001).

### Q-WH-02 사고 보류(hold)를 Webhook으로 알릴지

- **배경**: hold는 Payment 상태를 바꾸지 않으며(D-AUTH-002) GET은 `onHold` boolean만 보인다(D-CPQ-001). Webhook으로 받은 결과가 나중에 의심 상태가 되는 경우가 있다 — 예: 가맹점이 `payment.cancel_failed`를 받은 뒤 기관의 늦은 성공이 와서
  취소가 실제로 났을 수 있음(D-CANCEL-006 — FORCED, hold), FAILED 뒤 늦은 승인(P1, hold). hold 열림 이벤트(`*IncidentDetected`)는 내부 관찰 값을 담고, hold 해제 Use Case는 아직 없다(RECON).
- **권장안**: **v1은 hold 통지 없음**(이벤트·본문 필드 모두 없음). 가맹점은 막힌 요청의 `ORDER_ON_HOLD`와 GET `onHold`로 안다. 이유 — 조사 중 사실을 push하면 확정처럼 보이고, 해제 이벤트가 없어 "보류 시작"만 알리는 비대칭이 생긴다.
  해제 Use Case(RECON-CONTRACT-001)와 함께 다시 판단.
- **대안**: (b) `payment.hold_changed`(`onHold: true|false`만, 사유 없음) — 해제 Use Case 전에는 true만 나감. (c) 결과 이벤트 본문에 `onHold` 스냅샷 — 발송 후 바뀌어 낡은 값이 남음.
- **영향**: 이벤트 종류·fan-out 원천(현재 RISK_INCIDENT를 공개 이벤트로 바꾸는 매핑), RECON 해제 이벤트 계약, CS 안내.
- **막히는 작업**: 없음(권장안이면 만들 것 없음). (b)(c)면 S1·S2에 분류·본문 추가.

### Q-WH-03 Webhook 서명 방식

- **배경**: AC1 §18-4가 "서명 방식은 그 작업의 보안 질문 후보"로 넘겼다. 보안 문서 24행은 서명·재전송 방지를 요구한다. 내부 채널은 HMAC-SHA256(D-MS-004)이다.
- **권장안**: **HMAC-SHA256, (MID, 환경)별 공유 비밀**, 서명 대상 `"{t}.{body bytes}"`, 헤더 `PayCore-Signature: t=…,kid=…,v1=…`(교체 중 활성 키마다 하나), 가맹점 검증 규칙 = 서명 일치 + `|now − t| ≤ 창`(Demo 300초) + `eventId` 중복 제거,
  상수 시간 비교. 이유 — 업계 관행이라 가맹점 구현이 쉽고, 비밀이 가맹점별이라 다른 가맹점 앞 메시지를 재사용(교차 재전송)할 수 없다.
- **대안**: (b) **PG 비대칭 서명(Ed25519 등)** — 환경별 PG 개인키 하나, 가맹점은 공개키로 검증. 가맹점별 비밀 발급·보관이 필요 없어 Q-WH-05 의존이 사라지지만 가맹점이 `merchantAccountId` 대조를 반드시 해야 교차 재전송을 막고, 키 공개·교체 경로(공개키 배포)가 필요.
  (c) mTLS(PG 클라이언트 인증서) — 전송 계층 인증만, 본문 무결성·재전송 방지는 별도. (d) HMAC + 비대칭 둘 다.
- **영향**: 서명 adapter·golden·Simulator 검증·가맹점 문서, 비밀 원천(Q-WH-05), 재전송 창 값(운영값은 LIVE Gate).
- **막히는 작업**: 운영 서명 경로. 답 전에도 Port 뒤 TEST 시험 키 HMAC으로 S3·S4 진행 가능(답이 (b)면 adapter·golden 교체 — 전달 코어 불변).

### Q-WH-04 가맹점 Webhook endpoint의 소유와 등록 경로

- **배경**: MASTER·PAYMENT 어디에도 Webhook URL이 없다(F-2). 관리 화면은 ADMIN-BOOT-001(agreedOrder상 이 작업 다음), 가맹점 자격은 MASTER-CREDENTIAL-001(그 뒤)이다. 결제창 return URL은 MASTER Site host와 정확히 같아야 한다(D-AUTHN-002).
  endpoint 변경은 "알림을 어디로 보내는가"를 바꾸는 보안 민감 변경이다(탈취 시 결제 사실이 공격자에게 감 — 금전 이동은 아님).
- **권장안**: **MASTER가 원본**((MID, 환경)당 endpoint 1개: URL·상태·버전), **운영자가 ADMIN에서 등록·변경**(LIVE 등록·변경은 2인 승인 — D-ADMIN-003 목록에 추가하는 것이므로 사용자 결정), 기존 서명 Snapshot 채널로 PAYMENT에 전파(URL은 비밀이 아니므로 채널 사용 가능),
  host 제약은 두지 않음(가맹점 API 서버는 Site host와 다를 수 있음 — 대신 Q-WH-07 목적지 검사). **ADMIN 전에는 TEST만 설정 Demo endpoint, LIVE는 fail-closed**(§18-1).
- **대안**: (b) **가맹점 셀프서비스 API**(`PUT /v1/webhook-endpoint`, 가맹점 API key 인증, PAYMENT 소유) — ADMIN 의존이 없어 빠르지만 API key 탈취 = 통지 경로 변경, 변경 감사·알림 필요. (c) PAYMENT 소유 표 + ADMIN 등록(MASTER 무관).
  (d) Site 단위 endpoint(여러 개).
- **영향**: 원천 Port 구현 위치(MASTER 전파 확장 또는 PAYMENT API), ADMIN 화면·2인 승인 목록, 작업 분할(§18-1).
- **막히는 작업**: LIVE 전달, 등록 명령. 전달 코어는 Port 뒤 TEST 설정으로 진행 가능.

### Q-WH-05 서명 비밀의 발급·보관·교체·폐기

- **배경**: HMAC 서명자는 비밀 원문이 필요하다(해시 저장 불가). 비밀은 이벤트·로그·소스에 넣지 않는다(보안 19행) → MASTER Outbox/Snapshot으로 원문을 전파할 수 없다(도출 §6.2). 가맹점에게 원문을 한 번 전달하는 경로도 필요하다.
- **권장안(Q-WH-03 = HMAC일 때)**: **PAYMENT(서명 주체)가 (MID, 환경)별 생성**(`whsec_` + kid + 256bit 난수), PAYMENT DB에 **환경별 관리 키(비밀 저장소 주입)로 암호화 저장**(envelope), 원문은 발급 응답에서 1회만 노출(ADMIN 화면 또는 Q-WH-04 경로),
  교체 중 활성 최대 2개(둘 다로 서명), 폐기 append-only(사유·행위자), TEST/LIVE 분리, LIVE 발급은 운영 키 저장소 구성 뒤(D-MS-004·MASTER-CREDENTIAL-001 "LIVE 키는 운영 키 저장소·mTLS 후"와 같은 방식). **ADMIN 전에는 TEST 시험 키를 설정으로만**.
- **대안**: (b) MASTER가 발급·암호화 보관하고 PAYMENT가 서명 시 MASTER에 동기 요청 — 전달 경로가 MASTER 가용성에 묶임(06 문서 "MASTER 중단에도 지속"과 충돌). (c) 비밀 저장소(운영 Secret Manager)에 운영자가 직접 넣고 PAYMENT가 읽음 — DB 암호화 불필요, 운영 절차 의존.
  (d) Q-WH-03 (b) 비대칭이면 가맹점별 비밀 없음.
- **영향**: 새 표(암호화 비밀·폐기 기록)·관리 키 주입 설정, 발급 명령·2인 승인 여부, 가맹점 문서.
- **막히는 작업**: LIVE 서명, 발급·교체 명령. 이 작업은 Port + TEST 설정 키로 진행.

### Q-WH-06 재시도 기간과 포기(give-up) 정책

- **배경**: MASTER→PAYMENT relay는 재시도 상한이 없다(차단 Snapshot 포기 불가 — 내부 계약). 가맹점 endpoint는 외부라 장기 장애·폐기된 서버가 흔하고, 무한 재시도는 적체를 키운다(06 문서 "Outbox 적체 용량·연령 한계").
  Webhook은 통지이고 GET이 권위다.
- **권장안**: **유한 재시도** — 지수 backoff + jitter, 시도 수·첫 시도 후 경과 둘 다 상한, 도달 시 `GAVE_UP`(완료 아님, 경보), 자동 endpoint 중지 없음, 재전송은 운영자 명령(ADMIN 후). 4xx도 재시도(가맹점 설정 문제일 수 있음). 수치는 Demo(10s~1h, 10회, 24h), 운영값은 LIVE Gate.
- **대안**: (b) 무기한 재시도(MASTER relay와 같음) — 유실 없음, 적체 무한. (c) 연속 실패 N회·기간 경과 시 endpoint 자동 중지 + 가맹점 알림 — 운영 편의, 정책·알림 경로 필요. (d) 4xx는 즉시 포기(410만 포기 등).
- **영향**: 상태 모델(GAVE_UP 유무)·gauge·경보, 재전송 명령 필요성, 운영 runbook.
- **막히는 작업**: 없음(권장 형태로 Demo 값 진행, 답에 따라 일정 함수만 교체).

### Q-WH-07 Webhook 목적지 허용 정책(SSRF·사설망·redirect)

- **배경**: 보안 문서 24행(확정)은 방어를 요구하지만 구체 규칙(허용 scheme·포트·대역·redirect·응답 상한)은 정하지 않았다. 규칙은 보안 정책이며 가맹점이 쓸 수 있는 URL을 제한한다.
- **권장안**: §7.2 표 — `https`만, 포트 443만, IP 리터럴·userinfo·fragment 금지, 해석된 모든 주소가 공인 unicast(사설·loopback·link-local·metadata·CGNAT·ULA·mapped 대역 차단), 검사한 IP에 연결 고정(DNS rebinding 차단), redirect 미추종,
  TLS 검증 필수, 응답 본문 상한, timeout, 시스템 프록시 무시. TEST의 시험 수신기는 정확 host:port allowlist(값으로 켬)만 예외(WH-13).
- **대안**: (b) https + 사설망 차단만(포트 자유) — 가맹점 편의, 내부 서비스 포트 탐색 위험 소폭 증가. (c) 운영 egress 프록시 강제(인프라 단에서 대역 차단·감사) + 앱 검사 병행 — 가장 강하나 인프라 작업. (d) 가맹점 IP allowlist 공개(PG 송신 IP 고정) — 가맹점 측 방어, 인프라 필요.
- **영향**: 목적지 검사 adapter·설정 검증·등록 검증(Q-WH-04), 의존성(WH-14), 시험.
- **막히는 작업**: LIVE 전달. TEST 경로는 권장 규칙을 시험 대상으로 구현해 진행(답이 다르면 규칙 값만 교체).

### 17.9 검토했으나 질문으로 올리지 않은 것(Coordinator 확인 요청)

| 항목 | 처리 근거 |
|---|---|
| Webhook 전달 실패가 금융에 영향을 주는가 | **도출 — 영향 없음**: AC1 §10.2(조회가 권위, Webhook은 Outbox 소비 전달), 범위 40행(가맹점은 AUTHORIZED 확인 후 제공 — 조회로 가능), 이벤트 16행(단순 HTTP 성공은 FINANCIAL 전달이 아님 — F-1). Payment·정산·한도·hold를 바꾸는 경로를 만들지 않는다(T-V2). Coordinator가 사용자 확인이 필요하다고 보면 질문으로 올린다(권장 = 영향 없음) |
| 이벤트 종류 5개·내부 위험/운영 이벤트 제외 | 확정 AC1 §11·CC §9.3 소비자 열("가맹점 통지") + D-CPQ-001(사유 비노출). `PaymentRequested` 제외는 WH-01 |
| P1 늦은 승인·P1 운영자 취소 억제 | 도출 D-CPQ-002 (ii)·D-CANCEL-013(영향 작업에 WEBHOOK-001) |
| 본문 필드 범위(GET 부분집합) | WH-06 — 새 노출 없음. 더 많은 필드(PTX·기관일 등)를 원하면 질문 |
| at-least-once·순서 비보장·sequence | 도출 이벤트 13·47행 + WH-11. 엄격 순서를 원하면 질문(강화는 호환) |
| TEST/LIVE 비밀·endpoint 분리 | 확정 보안 15행, D-CPD-001 |
| 카드번호 비노출 | 확정 — PAYMENT는 PAN을 저장하지 않는다(AC1 §5.3) |
| UNKNOWN 진행 이벤트 없음 | 도출(Outbox에 없음 — F-5, GET이 "확인 중") |
| 재시도·timeout·창 수치 | Demo — 운영값은 LIVE Gate(D-AUTH-009 방식) |
| 열린 질문(Q-SETTLE-CURRENCY 등) | 이 작업을 막지 않음(통화·정산 값을 새로 노출하지 않는다) |

---

## 18. 범위 관찰(Coordinator 판정 — rev 0.2에서 판정됨)

판정 요약(tasks.json WEBHOOK-001 기록): 1 분할 확정(D-WH-008 — WEBHOOK-ENDPOINT-001 BACKLOG, ADMIN-BOOT-001 AC 추가, LIVE Gate 조건), 2 dependsOn CANCEL-001·CP-QUERY-001 추가(적용됨),
3 열 단위 GRANT 가능하면 우선, 아니면 표 단위 + 바뀌는 열 고정 시험(→ §21 B-1: 어느 쪽이든 기존 시험 기대값 변경이 필요해 판정 요청), 4 배포 전 행 SKIPPED_PRE_DEPLOY(m-9 순서), 5 payment 모듈 안,
6 수동 재전송은 상태 모델만, 7 HttpClient 5는 BOM 관리 시에만 — S0 확인 결과 관리됨(5.6.4), 추가 전 보고, 8 경로 충분, 9 timeout 제안대로, 10 하한은 Coordinator 실측, 11 gen2 해석 수용, 12 ADMIN-BOOT-001 전 사용자 알림 유지.
아래는 rev 0.1 관찰 원문(기록)이다.

1. **의존·순서 문제와 분할 제안**: endpoint·비밀의 원천과 등록 경로가 없고(F-2), 그 경로는 ADMIN-BOOT-001(진입점·2인 승인)과 Q-WH-04·05 답에 달려 있다. agreedOrder는 WEBHOOK-001을 ADMIN-BOOT-001·MASTER-CREDENTIAL-001 **앞**에 둔다.
   제안: **WEBHOOK-001 = 전달 코어**(계약·fan-out·delivery·서명/목적지 adapter·Simulator·TEST 설정 Demo endpoint·LIVE fail-closed)로 DONE 판정 가능하게 하고, 신규 BACKLOG **WEBHOOK-ENDPOINT-001**(endpoint 원천 구현·등록/변경 명령·
   서명 비밀 발급/교체/폐기·수동 재전송 Use Case, dependsOn ADMIN-BOOT-001 — Q-WH-04가 MASTER면 MASTER 전파 확장 포함, MASTER-CREDENTIAL-001과 병행/통합 검토)을 등록. ADMIN-BOOT-001 AC에 "Webhook GAVE_UP 조회·재전송 진입점(서비스 간 인증)" 추가,
   LIVE Gate 조건에 "LIVE Webhook = 등록 경로·운영 비밀 저장소·목적지 정책(Q-WH-07) 확정 뒤" 추가. agreedOrder 변경 없음(코어는 지금 가능).
2. **dependsOn**: 현재 AUTH-001만. 취소 이벤트·D-CPQ 일관성을 쓰므로 CANCEL-001·CP-QUERY-001 추가 제안(기록 정합성 — CPQ 판정 8 선례).
3. **Outbox UPDATE 권한과 미래 FINANCIAL relay**: `outbox_event`에 UPDATE를 처음 연다(V2 주석 예고). Webhook은 `merchant_notification_*` 열만 바꾸고 `publish_status`는 건드리지 않는다(F-1·WH-03). 열 단위 GRANT 가능 여부는 S2 확인 —
   불가하면 표 단위 + 갱신 열 고정 시험으로 대신해도 되는지 판정.
4. **배포 전 Outbox 행**: 권장 = migration에서 일괄 `SKIPPED_PRE_DEPLOY`(과거 TEST 결과를 한꺼번에 보내지 않음, LIVE 데이터 없음). 대안 = PENDING으로 두고 fan-out(TEST endpoint가 설정된 MID만 전달). 판정 요청.
5. **모듈 위치**: payment-api 기존 `payment` 모듈 안(파일 접두 `MerchantWebhook*`·`MerchantNotification*`)을 제안 — Outbox 표·Payment·취소 행 읽기가 같은 모듈 persistence에 있고 `@NamedInterface` 위치 불변(CLAUDE.md). 별도 Modulith 모듈(`notification`)은
   Outbox 표를 모듈 경계 너머로 읽게 되어 provided Port 추가가 필요 — 후속 하위 패키지 작업(ARCH-SUBPKG-PAYMENT)과 함께 재검토.
6. **수동 재전송**: 이 작업은 상태 모델만, Use Case·진입점·감사는 1번의 후속(§5.9). 동의 여부.
7. **새 의존성**: Apache HttpClient 5(WH-14, BOM 관리 조건) → GRADLE-DEPS×5·CVE-SCAN 대상. 관리 밖이면 S0에서 대안 판정.
8. **허용 경로**: tasks.json allowedWritePaths로 충분(`apps/payment-api/**`·`apps/institution-simulator/**`·`docs/repository/**`·`harness/tests/**`·`harness/commands.json`(Coordinator 하한 갱신용)). golden 줄끝 규칙은 기존 `.gitattributes`의
   `*.golden.json`·`apps/*/src/test/resources/contracts/**` `-text`가 이미 덮는다(HVC §7 CANCEL-001 기록) — `.gitattributes`는 목록에 없으므로 바꿀 필요가 생기면 경로 추가 판정. `compose.yaml`·`.env.example`은 로컬 Simulator 수신기 E2E 설정 시에만.
9. **timeout**: `taskTimeoutSeconds` 5400(현재 값) 유지, 단계 모듈 시험 30분·VERIFY-GATE 90분 제안. 규모(§20)상 2단계가 여러 세션에 걸칠 수 있다 — 단계별 체크포인트(S2·S3 검토) 기준 판정.
10. **harness**: 새 harness 시험(`test_webhook_signature_golden.py`) → HARNESS-SELFTEST 하한·GRADLE-CHECK 하한은 Coordinator가 실측으로 올린다(구현자는 commands.json 불변 — CPQ 선례). 새 Gradle 모듈 없음(Simulator 안 새 패키지) → HVC §7 모듈 등록 변화 없음.
11. **D-AUTH-011 gen2·hold 중 승인 통지**: §4.1 도출(보낸다)이 AC1 §8.1 "보류 중 이벤트 없음"·D-AUTH-003 "해제 판정 전 거래 없음"과 충돌하지 않는지 확인 — 해당 조항은 hold 해제 판정 전 **늦은 승인 거래** 미생성에 관한 것이고(예외 D-CANCEL-012) gen2 승인은 D-AUTH-011이 거래·PaymentAuthorized를 명시했다(이 작업의 해석).
12. **ADMIN-BOOT-001 착수 전 알림**: progress.json 다음 재개 동작대로 이 작업 DONE 뒤 ADMIN-BOOT-001 착수 전에 사용자에게 알린다(1번 분할 시 ADMIN AC 추가분 포함).

---

## 19. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | Webhook 경로 결함이 승인·취소 TX를 깨뜨림 | 업무 TX 무변경(WH-02), T-F2 |
| R2 | SSRF(내부망·metadata 접근), DNS rebinding | §7.2 검사·연결 고정·redirect 금지(Q-WH-07), T-S5·T-S6, LIVE fail-closed |
| R3 | 서명 비밀·본문이 로그·예외·설정 출력에 노출 | adapter 안에서만 원문, toString 마스킹, T-N1 |
| R4 | sequence가 인과 순서와 다름 | guard 직렬화 + id 순(§5.4), UNIQUE, C-F1 |
| R5 | JSON 열 정규화로 재전송 본문·서명 불일치 | 바이트 저장(WH-08), T-D2·T-S1 |
| R6 | lease 만료 중복 전송·늦은 결과 덮어쓰기 | 소유자 fencing·DELIVERED 우선(§5.5), T-D3·C-D1, 가맹점 중복 제거 계약 |
| R7 | P1 억제 규칙이 RECON 변경(EXPIRED P1 등)으로 깨짐 | 종료 상태 가정 시험(T-F3), RECON 인계 기록 |
| R8 | Outbox 미처리 탐색 비용·적체 | 인덱스(status, id), fan-out 최고 연령 gauge |
| R9 | 가맹점이 Webhook만 믿고 GET과 다른 판단 | 계약 문서(§8), 본문에 현재값(`cancellableAmount`) 미포함 |
| R10 | 새 HTTP 의존성 취약점·BOM 밖 버전 | BOM 관리 조건(WH-14), CVE-SCAN |
| R11 | 질문 답 전 부분 구현이 의미 선점 | HELD_DECISION·hold 미구현·LIVE fail-closed(§4.5·§7.1) |
| R12 | 호스트 메모리 부족으로 전체 check 중단(선례) | 모듈 단위 실행 기록, 중단은 FAIL/환경, VERIFY-GATE로 최종 확인 |

---

## 20. 규모 추정

- 기준: AUTH-001(payment-api 약 +313건), CANCEL-001(AUTH-001의 0.8~1.0배), CP-QUERY-001(0.15~0.25배). 이 작업은 새 표 5개·Outbox 열·권한 migration·외부 HTTP·두 worker·Simulator 새 모듈·golden이 있으나 기관 UNKNOWN 회복·금액 규칙이 없다.
- **AUTH-001의 약 0.6~0.8배**. 시험 수 추정: payment-api +150~230(단위 60~90, DB 30~45, 전달·HTTP 45~70, 일관성·관측 15~25), institution-simulator +20~35, harness +2~4.
- 일정 감(구현자 1 + 검토 2~3회): S1~S2 1.5일, S3 1~1.5일, S4 0.5~1일, S5·gate·최종 검토 0.5일 — 약 3.5~4.5일. 질문 답 지연은 운영자 취소 통지(Q-WH-01)·hold 통지(Q-WH-02)·LIVE 경로(Q-WH-03~05·07)만 막고 TEST 코어는 막지 않는다.

### 20.1 배포 조건(rev 0.2 — D-WH-009, AUTH-001 §20 방식)

1. **기존 MySQL 볼륨은 초기화 스크립트를 한 번 다시 실행한다**: `docker compose exec mysql bash /docker-entrypoint-initdb.d/01-owned-databases.sh`(재실행 안전 — 표·열 권한 유지). 새 helper
   `paycore_payment_privilege.grant_runtime_column_update`가 없으면 권한 migration V24가 실패하고 payment-api가 기동하지 않는다(fail-closed). 운영 DB는 DBA가 같은 SQL로 helper를 설치한다.
2. **이전 payment-api 인스턴스를 모두 내린 뒤 V21~V24를 적용한다**(rolling 혼재 금지): V23 ①(DEFAULT SKIPPED_PRE_DEPLOY)과 ②(DEFAULT PENDING) 사이에 이전 인스턴스가 Outbox를 INSERT하면 그 행은 알림 없이 SKIPPED가 된다.
   ② 이후에는 이전 코드가 넣는 행도 DEFAULT로 PENDING이라 알림 대상이다(새 열을 모르는 INSERT도 안전 — T-M1).
3. 배포 전 Outbox 행은 모두 `SKIPPED_PRE_DEPLOY`(가맹점에 과거 결과를 보내지 않는다 — §18-4 판정, TEST 데이터).
4. 필수 설정 `paycore.webhook.test-endpoints`(빈 목록 가능)·`paycore.webhook.fanout.{enabled,fixed-delay,batch-size,failed-retry-interval}` — 기본값 없음(`.env.example` JSON에 Demo 값).
5. LIVE: endpoint 원천 없음 → 모든 LIVE 이벤트 `NO_ENDPOINT`(DB CHECK 이중 방어). LIVE Webhook은 WEBHOOK-ENDPOINT-001·운영 비밀 저장소·D-WH-007 적용 뒤(D-WH-008 LIVE Gate).
6. 수용한 부수 효과(D-WH-009): runtime 계정이 outbox_event를 `SELECT … FOR UPDATE`로 잠글 수 있다. 코드 경로에는 없다(fan-out은 guard 직렬화 + PK UPDATE).
7. (S3~S5 추가) V25·V26(시도 원장)도 이전 인스턴스를 내린 뒤 적용한다. 필수 설정 `paycore.webhook.transport.*`·`paycore.webhook.delivery.{claim-lease,schedule.*,job.*}`·`paycore.webhook.test-signing-keys`(빈 목록 가능) — 기본값 없음,
   claim-lease > dns-timeout + request-deadline + 5s가 아니면 기동 거부. 한 claim은 min(batch, floor(lease / 한 시도 예산))개(최소 1 — 최종 검토 n-2)만 잡는다. Demo 값(lease 60s, 예산 2 + 15 + 5 = 22s)에서는
   회차당 최대 2건이라 job batch 50은 사실상 적용되지 않는다. 인스턴스당 처리량은 lease/예산과 fixed-delay가 정하고, 보내기 직전 남은 lease 검사는 GC·정지 대비 backstop이다(검토 #2 H-1 — 남은 lease가 짧으면 되돌려 다음 회차로).
   운영값(lease·batch·fixed-delay·인스턴스 수)은 LIVE Gate에서 처리량 실측과 함께 정한다.
8. (S5) HttpClient `org.apache.hc.client5.http.wire`·`…headers` 로거는 기본 WARN으로 고정되어 있다 — 외부 설정으로 DEBUG를 켜지 않는다(서명 헤더·본문이 찍힌다).
9. (S4) Simulator `merchant_webhook_receipt`(V8·V9)는 기존 procedure로 권한만 준다(Simulator init 재실행 불필요). 가상 가맹점 수신기는 기본 0개 — 로컬 E2E에서만 `paycore.simulator.merchant.*`로 켠다(Demo 비밀은 환경변수로만).

---

## 21. rev 0.2 보강(서명 문법·목적지·시험 변경·구현 결정 추가)과 2단계 실행 기록

### 21.0 서명 헤더 문법·키 바이트(m-8 — §6.1을 구체화)

```text
PayCore-Signature = "t=" T "," pair [ "," pair ]          (공백 없음, 순서 고정: t 먼저, 그다음 활성 키 순서대로 최대 2쌍)
pair              = "kid=" KID "," "v1=" HEX64
T                 = 1*10DIGIT (Unix 초, 주입 UtcWallClock/PaymentClock — 시도마다 새로)
KID               = [A-Za-z0-9_-]{1,40}
HEX64             = 64 소문자 16진
검증자(Simulator·가맹점 안내): 쉼표로 나눈 key=value 중 모르는 key는 무시, t 정확히 1개, (kid, v1) 쌍 1~2개, v1은 소문자 64자만,
                   쌍 중 하나라도 일치하면 통과(MessageDigest.isEqual), |now − t| ≤ 300초(Demo, 경계 포함) — golden이 −300·+300 통과, −301·+301 거절을 고정
```

- **WH-19(키 바이트)**: HMAC 키 = 가맹점에게 보이는 비밀 문자열 전체(`whsec_…`)의 UTF-8 바이트. 해독 단계가 없어 가맹점 구현 오류가 적다. 256bit 난수는 문자열 안 base64url 부분이 담는다. golden·Simulator·PG가 같은 규칙.
- **WH-20(서명 대상)**: `"{t}.{body}"` — t의 ASCII 숫자 + `.`(0x2E) + 저장된 본문 바이트 그대로.

### 21.0b 목적지·TLS(m-4·m-5·m-6 — §7.2 보강, S3)

- 전체 요청 기한은 소켓 idle timeout이 아니라 **예약 abort**(요청 시작 + 전체 기한에 강제 취소)로 강제, DNS 해석에도 별도 예산(Demo 2s). T-S6: slow-drip 응답(바이트를 timeout보다 짧은 간격으로 조금씩) → 전체 기한에 중단, 그 시간이 claim lease보다 짧다(기동 검사).
- 시험 CA + https 시험 수신기: 자체 서명 인증서 거절, hostname 불일치 거절, 고정 IP로 연결하면서 SNI·hostname 검증은 원 host(성공). LIVE 설정에 사용자 정의 trust store가 있으면 기동 거부. ArchUnit: HttpClient `useSystemProperties()` 호출 금지.
- 차단 대역(기본 거부 — 공인 unicast로 판정된 것만 허용): 0.0.0.0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4,
  255.255.255.255, ::/128, ::1/128, ::/96(IPv4 호환), ::ffff:0:0/96(mapped — 안의 v4도 판정), 64:ff9b::/96, 100::/64, 2001::/32, 2001:db8::/32, 2002::/16, fc00::/7, fe80::/10, fec0::/10, ff00::/8.
- allowlist 예외는 **전달 행 environment = TEST일 때만** 코드에서 적용(설정 값만으로 LIVE 전달에 적용되지 않는다). T-E1 행 추가: allowlist host라도 LIVE 전달이면 차단.
- (최종 검토 n-1) `max-response-bytes`는 **쓰이지 않으며 설정 호환을 위해 남긴다** — 전송 adapter는 상태 줄로 판정한 뒤 본문을 읽지 않고 abort한다(검토 #2 m-1, 상한보다 엄격).

### 21.0c 시험 계획 변경(§12에 더함 — 기대값 추가만)

| # | 시험 | 기대 |
|---|---|---|
| T-C1(갱신) | 분류 표 | HELD_DECISION 행 없음. OPERATOR+AUTHORIZED 취소·취소 실패 → 통지, P1(FAILED/EXPIRED) PaymentCancelled·PaymentCancelFailed(가맹점·운영자) → SUPPRESSED, 이상 조합 → 예외(→ FANOUT_FAILED) |
| T-C2b | GET ⊇ Webhook | 종류 5개 × 섹션(payment·authorization·cancellation·returnSummary)마다 Webhook 키 ⊆ GET 키(`GetPaymentController` 출력 집합) |
| T-C3 | n-5 | `cancelTransactionId` = payload `transactionId`(PayCore), `providerTransactionId`가 아님 |
| T-F3(갱신) | P1 억제 | P1 운영자 PaymentCancelFailed 행 추가 |
| T-F5 | 이상 조합 | PaymentAuthorized + Payment AUTHORIZING/UNKNOWN(우회 Fixture) → 그 행 FANOUT_FAILED·이벤트 0, SUPPRESSED 아님, 다른 Payment 행은 NOTIFIED, 같은 Payment 뒤 행은 PENDING 유지 |
| C-F2 | 업무 TX 비대기 | fan-out 행 TX를 guard·PK UPDATE 뒤 hook으로 붙잡은 동안 다른 연결의 Outbox INSERT(실 업무 TX) 즉시 커밋, lock wait 0 |
| T-F6 | 열 고정(m-7) | fan-out 뒤 outbox 행의 merchant_notification_* 밖 열(publish_status·payload·event_id·effect_key…) 바이트 동일 |
| T-M1(갱신) | migration 순서 | V20까지 적용한 볼륨에 기존 행 → 새 migration 뒤 SKIPPED_PRE_DEPLOY, 이후 INSERT는 PENDING |
| T-D3(갱신) | lease 인수 | 두 worker의 시도 행이 서로 다른 attempt_no로 둘 다 남음 |
| T-S2(갱신) | Simulator | 환경 TEST 아님 → 거절(n-6), `MessageDigest.isEqual` |

### 21.0d 구현 결정 추가·변경

| ID | 결정 | 이유 |
|---|---|---|
| WH-10(변경) | fan-out = 행마다 `LocalTransactions` TX, guard `FOR UPDATE` 직렬화 + 비잠금 후보 읽기 + Outbox PK UPDATE | H-3·m-3 |
| WH-18 | 실패 행 = 비종결 `FANOUT_FAILED`(실패 코드·횟수·재시도 시각), 같은 Payment 뒤 행만 보류 | m-3, fail-closed |
| WH-19·WH-20 | §21.0 | m-8 |
| WH-21 | 봉투 `createdAt` = fan-out 시각, 업무 시각 미포함 | m-1 |
| WH-22 | 본문 `payment.amount/currency`는 Payment 생성 값(불변 행), 승인 금액은 `authorization.approvedAmount` | GET 어휘 |
| WH-16(보정) | Store를 하나로: `MerchantNotificationFanoutStore`(후보·guard·로드·표시·실패 표시·sequence) — `MerchantNotificationSourceStore`는 만들지 않음(같은 프로토콜 기록 Store) | 단순화 |

### 21.1 S0(2026-10-21)

- 입력 hash 재고정: 머리말 아래 "rev 0.2 S0 재고정" 문단.
- **BOM 확인(Coordinator 판정 7)**: `~/.gradle/caches/…/spring-boot-dependencies-4.1.1.pom` 77행 `<httpclient5.version>5.6.4</httpclient5.version>`, 79행 `<httpcore5.version>5.4.3</httpcore5.version>`, 843행 이후 `org.apache.httpcomponents.client5:httpclient5` 관리 항목 존재
  → **BOM 관리 항목**. 카탈로그에는 버전 없이 추가할 수 있다. 의존성 추가는 S3에서 하며 그 전에 Coordinator에게 보고한다(이번 체크포인트까지 빌드 파일 변경 없음).
- **B-1 판정 요청(m-7 측정 전 발견)**: 기존 `DatabaseAccountSeparationSpec`은 (a) runtime role 권한 `"outbox_event" to "Select,Insert"`·SHOW GRANTS 줄 `GRANT SELECT, INSERT ON …outbox_event…`을 고정하고 (b) "outbox events cannot be updated"
  시험에서 `UPDATE outbox_event SET publish_status = 'PENDING'` = **1142**를 고정한다. fan-out의 PK UPDATE는 Outbox에 UPDATE 권한이 필요하므로:
  - 표 단위 UPDATE(helper 그대로): (a) 두 기대값이 `Select,Insert,Update`로 **바뀌고** (b) publish_status UPDATE가 **허용**된다(append-only 보호 약화 — 시험 기대 완화). 
  - 열 단위 `GRANT UPDATE (merchant_notification_*)`: (b)는 거절이 유지되지만 오류 코드가 1143(열 접근 거부)으로 바뀌고 SHOW GRANTS 줄이 바뀐다. 기존 helper는 표 단위만 받으므로 `docker/mysql/init/01-owned-databases.sh`에 열 단위 helper를 추가해야 하고
    기존 볼륨은 init 재실행이 필요하다(이번 회차 허용 경로 밖 — docker/**는 작업 경로에는 있으나 Coordinator 지시 목록에 없음).
  - 대안: Outbox를 바꾸지 않는 별도 INSERT 전용 표지 표(`merchant_notification_marker`) + anti-join — 기존 기대값 불변, 단 미처리 탐색 비용이 이력에 비례하고 커밋 지연 행 처리 규칙이 필요.
  - **권장: 열 단위 GRANT(새 helper)** — 기존 보호 의도(publish_status·payload 변경 불가)를 DB가 계속 강제한다. 기대값 변경 2건(SHOW GRANTS 줄, 1142 → 1143)과 docker/** 변경·볼륨 재실행 배포 메모가 필요하다. 판정 전에는 S2의 Outbox 열·권한·fan-out store를 만들지 않는다.
- **m-7 측정(2026-10-21T03:4xZ, 일회용 `mysql:8.4.11` 컨테이너 — 저장소 밖 탐침 스크립트, 운영 DB 아님)**: role에 `GRANT SELECT, INSERT ON p.o` + `GRANT UPDATE (merchant_notification_status, merchant_notified_at) ON p.o`.
  결과 — SHOW GRANTS `GRANT SELECT, INSERT, UPDATE (`…`) ON …`, `mysql.tables_priv.Table_priv` = `Select,Insert`(표 단위 기대 맵은 그대로), Column_priv = `Update`; 알림 열 PK UPDATE 성공, `UPDATE … SET publish_status` → **1143**,
  DELETE → 1142, `SELECT … FOR UPDATE`·`FOR SHARE` → **허용**(열 UPDATE 권한도 잠금 읽기를 연다 — 현재 Outbox 잠금 읽기 경로는 없으며 ArchUnit/시험으로 막아야 한다). 열 단위 GRANT는 MySQL 8.4.11에서 **가능**하지만 기존 helper로는 줄 수 없다
  (표 단위 허용 집합만) → 새 helper(`docker/mysql/init/01-owned-databases.sh`)와 기존 볼륨 init 재실행이 필요하다. B-1 판정 요청은 그대로다.

### 21.2 S1 — domain·application 순수 규칙(2026-10-21)

| 시각(UTC) | 단계 | 명령 | 결과 |
|---|---|---|---|
| 03:34:53→03:35:23 | RED-C | `:apps:payment-api:compileTestKotlin` | exit 1, `e:` 116건 — 모두 application·adapter 새 타입 미정의(`OutboxNotificationMark` 13, `MerchantNotificationFacts` 10, `PendingOutboxNotification` 5 …). domain 시험 3개는 이때 이미 있던 domain 코드로 컴파일됐다(정직 기록: domain 코드는 시험 작성 직후·첫 실행 전에 썼다 — domain RED는 아래 변이 RED로 확보) |
| 03:40:49→03:41:23 | GREEN | `:apps:payment-api:test --tests` 6 spec + `ArchitectureSpec` | exit 0, 137건(정책 40·재시도 7·전달 9·fan-out 11·본문 16·payload 9·ArchitectureSpec 45), 실패·오류·skip 0 |
| 03:41:5x→03:42:51 | 변이 RED | 9 변이(`/tmp/mut1.py` — 적용 → 해당 spec → 복원) | 9/9 죽음: M1 P1 승인 통지(2/40 실패), M2 취소 이상→SUPPRESSED(7/40), M3 같은 Payment 차단 제거(3/11), M4 실패→SUPPRESSED(2/11), M5 cancelTransactionId=PTX(1/9), M6 운영자 merchantCancelId 노출(2/16), M7 maxAge 무시(1/7), M8 취소 실패 개시자 payload 기본값(2/9), M9 봉투 시각=업무 시각(1/11) |

S1 산출물(main): domain `MerchantNotificationPolicy`(종류·판정·이상 예외), `WebhookRetrySchedule`, `MerchantWebhookIds`, `MerchantWebhookDelivery`(m-2 `startAttempt` 예약·n-1 성공 단조); application `MerchantNotificationFanoutService`(행마다 TX·FANOUT_FAILED·같은 Payment 보류),
`SecureRandomMerchantWebhookIdentifierGenerator`, provided `FanOutMerchantNotificationsUseCase`, required `MerchantNotificationFacts`·`MerchantNotificationFanoutStore`·`MerchantWebhookEventStore`·`MerchantWebhookDeliveryRepository`·`MerchantWebhookEndpointDirectory`·`MerchantWebhookIdentifierGenerator`;
adapter(순수) `MerchantWebhookBodyJson`(본문 v1 — GET 부분집합), `OutboxNotificationPayloads`(payload 해석 — n-5·H-2). **편차**: fan-out 서비스는 아직 Spring bean이 아니다(Outbox 표시 Store가 B-1 판정 대기) — 판정 뒤 `@Service`·trigger 연결.

### 21.3 S2(부분) — 가맹점 Webhook 전용 표·저장(2026-10-21)

- V21 `merchant_webhook_fanout_guard`(행 1개 seed)·`merchant_webhook_stream`·`merchant_webhook_event`(MEDIUMBLOB 본문·sha256·source UNIQUE·sequence UNIQUE·FK outbox_event/payment·**LIVE면 NO_ENDPOINT만** CHECK)·`merchant_webhook_delivery`(상태 짝 CHECK·**TEST만** CHECK·claim 짝).
  V22 runtime 권한(guard·stream·delivery `SELECT, INSERT, UPDATE`, event `SELECT, INSERT`). 모든 문자열 열 utf8mb4_0900_bin(기존 관례). JPA `JpaMerchantWebhookEventEntity`(`@Immutable`)·`JpaMerchantWebhookEventStore`, `JpaMerchantWebhookDeliveryEntity`·`JpaMerchantWebhookDeliveryRepository`(insertNew·find),
  `StoreConstraint`·`PersistenceFailures` 5항목 추가.
- **기대값 변경(추가만)**: `DatabaseAccountSeparationSpec` 표 권한 맵 4행·SHOW GRANTS 4줄 추가, 적용 migration 목록에 "21","22" 추가(`DatabaseAccountSeparationSpec`·`PaymentApiContextSmokeSpec`·`CheckoutWindowCheckMigrationStrategySpec`·`OldVolumeStartSpec`) — "최신까지 적용" 기대의 연장이며
  `CheckoutWindowCheckMigrationStrategySpec` 111행 `history().size` 21 → 23은 같은 목록 길이다(이전 작업들과 같은 방식). 기존 행·값은 바꾸지 않았다.
- 실행: 03:48:59→03:51:24 `:test` 7 spec — `MerchantWebhookPersistenceSpec` 10/10, 실패 2(적용 migration 목록 — 위 추가 전), 04:00~04:02 재실행 `CheckoutWindowCheckMigrationStrategySpec` 6·`OldVolumeStartSpec` 4·`MerchantWebhookPersistenceSpec` 10 exit 0, 03:52~03:55 `DatabaseAccountSeparationSpec` 15·`PaymentApiContextSmokeSpec` 5 통과.
- 변이 RED: DB-1 LIVE CHECK 제거 → `MerchantWebhookPersistenceSpec` 1/10 실패, DB-2 body 열 JSON → 컨텍스트 기동 실패(Hibernate schema 검증 — 의도한 시험 단언이 아니라 검증 단계에서 죽음, 기록), D-1 성공 비단조 → `MerchantWebhookDeliverySpec` 1/13 실패. 모두 복원.
- **아직 없음(B-1 판정 대기)**: Outbox 알림 열 migration(m-9 순서)·Outbox UPDATE 권한·`MerchantNotificationFanoutStore` 구현(후보 SQL·guard·PK UPDATE·payload 해석 연결)·fan-out bean·trigger·설정, T-F1~F6·C-F1·C-F2·T-M1. 검토 #1 체크포인트는 이것들이 끝난 뒤다.

### 21.4 S2 완료 — Outbox 표시·권한·fan-out Store·bean·trigger(2026-10-21, D-WH-009 반영)

- **D-WH-009(B-1 판정 — 열 단위 GRANT)**: `docker/mysql/init/01-owned-databases.sh`에 PAYMENT 전용 helper `grant_runtime_column_update(table, columns)`(DEFINER 잠긴 grantor, 받는 쪽 runtime role 고정, base table만, 소문자 열 이름 1~8개가 모두
  실제 열이어야 함 — 아니면 SIGNAL 1644, 부여만·재실행 안전). MASTER에는 설치하지 않는다(master-api 시험·권한 불변). V23(m-9 순서: ADD COLUMN DEFAULT SKIPPED_PRE_DEPLOY → SET DEFAULT PENDING →
  알림 시각·CHECK·인덱스, 비종결 실패 기록 표 `merchant_notification_failure`), V24(두 열 열 단위 UPDATE + 실패 표 권한). `JpaOutboxEventEntity` `@Immutable`(m-7).
- **설계 보정(WH-23·WH-24, 가역)**: WH-23 — webhook 표는 업무 표(payment·outbox_event)로 FK를 두지 않는다(FK 검사가 부모 행에 공유 잠금을 걸어 업무 TX의 payment `FOR UPDATE`가 fan-out을 기다리게 된다 —
  변이 MS9가 C-F2를 깨뜨려 확인). WH-24 — D-WH-009가 Outbox에서 바꿀 수 있는 열을 둘로 제한하므로 FANOUT_FAILED의 횟수·코드·재시도 시각은 별도 표(§5.0의 "Outbox 열" 문장 대체).
- **기대값 변경**: D-WH-009가 승인한 두 가지 — `DatabaseAccountSeparationSpec` SHOW GRANTS의 outbox_event 줄(`UPDATE (…두 열…)` 포함), "outbox update"(publish_status) 1142 → **1143**. 그 밖은 추가만:
  helper EXECUTE 줄 2(migration·grantor), 실패 표 권한 맵·SHOW GRANTS 각 1, 적용 migration 목록 "23","24"(4개 spec, `history().size` 23 → 25), 새 시험 2개(열 전수 probe — 알림 두 열 외 모든 열 1143·DELETE 1142·잠금 읽기 허용 기록,
  helper 입력 검증 1644·runtime 호출 1370·멱등).
- **RED**: 05:27:19Z 권한 시험 RED-A(`DatabaseAccountSeparationSpec` 5/17 실패 — helper·열 권한 없음), 05:33:13Z RED-C(`MerchantNotificationFanoutDbSpec` — `JpaMerchantNotificationFanoutStore` 미정의 2건).
- **GREEN**: 05:36:19→05:40:39Z 11 spec — `OutboxNotificationMigrationSpec` 2·`DatabaseAccountSeparationSpec` 17·`MerchantWebhookPersistenceSpec` 10·`CheckoutWindowCheckMigrationStrategySpec` 6·`OldVolumeStartSpec` 4·
  `PaymentApiContextSmokeSpec` 5·`ArchitectureSpec` 45·`LocalDemoAuthorizationConfigSpec` 2·`MainConfigurationSpec` 6·`MerchantNotificationFanoutServiceSpec` 11 통과, `MerchantNotificationFanoutDbSpec` 3/11 실패 — 시험 가정 오류
  (`ConfirmFixture`는 CreatePayment 없이 Payment를 준비해 PaymentRequested가 없다)를 고친 뒤 05:40:55Z 11/11.
- **변이 RED(S2)**: MS2 후보 SQL의 같은 Payment 앞 실패 행 제외 제거 → T-F5 2/11 실패, MS5 helper 열 존재 검사 제거 → 1/17, MS6 V24에 publish_status 추가 → 5/17, MS7 DEFAULT를 SKIPPED로 유지 → T-M1 1/2,
  MS9 stream→payment FK 추가 → C-F2 1/11(업무 TX가 fan-out을 기다림). **MS3 guard `FOR UPDATE` 제거 → 살아남음(0/11, 결정적 형태 C-F3도 0/12)**: 동시 fan-out의 정확성은 guard 없이도
  같은 Payment의 stream 행 잠금(IODKU)·`source_event_id` UNIQUE·조건부 PK UPDATE가 지킨다(같은 행을 두 worker가 잡으면 늦은 쪽은 stream 잠금을 기다린 뒤 UNIQUE로 실패 → 실패 표시는 종결 행이라 무동작).
  guard는 중복 작업을 줄이는 방어층이며 구분하는 시험을 만들 수 없어 C-F3는 지웠다(비판별 시험을 남기지 않음). **검토 #1 판단 요청**: guard 유지(현행) 또는 제거.
- 시험 목록(S2 신규): `OutboxNotificationMigrationSpec`(T-M1 + 알림 CHECK), `MerchantNotificationFanoutDbSpec`(T-F4 공식 경로 승인·가맹점 부분취소·운영자 취소 sequence 1·2·3과 본문 값, T-F3 P1 억제 — 늦은 승인·운영자 취소·운영자 취소 실패,
  T-F5 이상 조합 FANOUT_FAILED·뒤 행 보류·재시도, T-F1 이벤트 INSERT 뒤 결함 → 전체 rollback·재시도 1회 NOTIFIED, T-F6 열 고정, C-F1 4 worker × 30 Payment × 3 이벤트, C-F2 업무 Outbox INSERT·payment FOR UPDATE 비대기 — lock wait 0),
  `DatabaseAccountSeparationSpec` 새 Then 2.

### 21.5 검토 #1(CHANGES_REQUIRED) 대응·전체 check 실패 대응(2026-10-21~22)

- **전체 check 05:52:42Z FAIL**: `:apps:master-api:test` 320건 중 1건 — `MasterDatabaseAccountSpec`(초기화 스크립트 SHOW GRANTS 고정)이 PAYMENT migration·grantor 계정의 새 helper EXECUTE 줄 2개를 몰랐다. 스크립트 결함이 아니라 D-WH-009 helper의
  예상된 추가다 → 그 두 줄만 기대값에 **추가**(MASTER·FINANCIAL 기대값 불변). `apps/master-api/**`는 WEBHOOK-001 allowedWritePaths 밖이며 Coordinator 지시(2026-10-21)로 이 시험 파일의 기대값 추가만 했다.
- **H-1(다중 인스턴스 FANOUT_FAILED 순서)**: Port `hasEarlierOpenRow(outboxId, paymentKey)` — 행 TX에서 guard 뒤, 같은 Payment(payload paymentId)에 더 작은 id의 PENDING·FANOUT_FAILED 행이 있으면 `DEFERRED`(이벤트·sequence 없음)
  → 서비스가 그 Payment를 이번 회차에서 차단. RED-C 06:01:52Z(Port에 메서드 없음 1건), GREEN 06:04~06:05Z, 결정적 시험 "H-1: an instance read its candidates…"(후보 읽기 직후 멈춘 인스턴스 B, 그 사이 A가 앞 행을 실패로 커밋, B 재개 → 뒤 행 PENDING·이벤트 0)
  + 단위 시험. 변이 MH1(검사 제거) → DB 1/15·단위 2/12 실패(죽음).
- **m-1**: C-F1에 "모든 회차 failed 합 0, 이 Payment들의 실패 행 0" 추가 → **MS3(guard 제거) 재실행 시 죽음(1/15)** — guard 없이 경합한 worker가 FANOUT_FAILED를 남긴다. guard 유지.
- **m-2**: C-F2 두 번째 지점 — `store.mark` 직후(Outbox 행 X 잠금·보조 인덱스 갱신 뒤) 붙잡은 동안 업무 Outbox INSERT와 같은 Payment `FOR UPDATE`가 기다리지 않음(lock wait 0, < 5s).
- **n-2**: T-F4·C-F1 끝에 고아 anti-join(event→outbox 0, event→payment 0).
- **n-3**: gauge `paycore.webhook.fanout.failed`(> 0 경보)·`.failed.oldest_age_seconds`·`.pending`·`.pending.oldest_age_seconds`(`JooqMerchantNotificationBacklogReader` → `MerchantNotificationBacklogService` → `MerchantNotificationBacklogGauges`, label 없음).
  경보 임계값·경보 규칙은 운영값(LIVE Gate — 저장소에 경보 규칙 기반이 아직 없다). T-F5에 gauge 원천 단언 추가, 변이 MG1(상태 조건 오기) → 1/15 실패. **한계 기록**: payload에서 paymentId를 읽을 수 없는 행은 같은 Payment의 뒤 행을 막을 수 없다
  (실패 표시·경보는 됨). gauge 코드는 시험보다 먼저 썼다(정직 기록 — RED는 변이로 확보).
- **n-4**: 측정(mysql:8.4.11) — 표 단위 helper를 같은 표에 다시 부르면 `REVOKE IF EXISTS UPDATE`가 **열 단위 UPDATE까지 회수**하고, 'SELECT, INSERT, UPDATE'는 표 단위 UPDATE로 모든 열을 연다. V24·초기화 스크립트에 주석.
- **n-5**: `OutboxLockReadScanSpec` — main Kotlin·SQL(주석 제거 뒤)에 `outbox_event … FOR UPDATE|FOR SHARE` 없음 + 자기 시험(위반 2·주석·다른 표 무시).
- **n-6 (T-F2)**: 별도 시험을 만들지 않는다. 증거 = C-F2(두 지점 — 업무 TX가 fan-out을 기다리지 않음) + ArchUnit `businessServicesIndependentOfMerchantWebhook`(application의 Webhook 밖 클래스가 Webhook 타입에 의존하지 않음 — 위반 fixture `archfixture.webhook.CancelServiceTouchingWebhook`)
  + 전체 check(기존 업무 시험 회귀 없음). §12 T-F2 행은 이 묶음으로 대체한다.
- **n-7**: S1 TDD 편차(domain 코드를 시험 실행 전에 씀 — RED는 변이로)는 Coordinator가 수용. S3부터 단계마다 실제 RED-C·RED-A를 남긴다.
- **n-1**: §5.0 "Outbox 열"·"권한" 문장과 §5.11 rev 0.1 "혼재 배포 제약 없음"을 정정했다(위 본문).

### 21.6 S3 — delivery·서명·전송(HttpClient 5)·목적지 검사·TLS(2026-10-22)

- **전체 check(S2·검토 #1 대응 체크포인트)**: `./gradlew --continue check` 07:14:13Z → 07:25:50Z exit 0. 새로 실행된 모듈: payment-api 1,610건(07:14:37~07:22:56Z)·master-api 320건(07:23:04~07:25:47Z), 실패·오류·skip 0.
  financial-batch 26·internal-admin 29·institution-simulator 168·libs/test-support 112는 입력이 바뀌지 않아 `:test UP-TO-DATE`(이전 실행 보고서 — 2026-10-21/20)였다. 합계 보고서 2,265건. VERIFY-GATE(`--rerun-tasks`)가 아니다.
  이 실행의 payment-api 시험 컴파일은 S3 시험 파일을 쓰기 전(07:14)이라 S3 코드를 포함하지 않는다.
- **의존성**: `org.apache.httpcomponents.client5:httpclient5` — Boot 4.1.1 BOM 관리 버전(5.6.4), 카탈로그 `apache-httpclient5`(버전 없음)·payment-api `implementation`. 빌드 파일 변경 → GRADLE-DEPS×5·CVE-SCAN 대상(Verifier).
- **RED-C**: 07:26:50→07:30:09Z `compileTestKotlin` exit 1, `e:` 199건(새 S3 타입 미정의 — `MerchantWebhookSendResult` 26, `DeliveryAttemptResult` 14, `WebhookSigningKey` 10 …; httpclient5 fixture는 의존성 추가로 해석됨).
- **RED-A**: 07:33:32→07:34:39Z stub(주소 정책 = 항상 공인, delivery 서비스 = 아무것도 안 함)으로 `WebhookAddressPolicySpec` 43/72·`MerchantWebhookDeliveryServiceSpec` 8/8 실패.
- **GREEN**: 07:34:50→07:37:08Z — `WebhookAddressPolicySpec` 72, `MerchantWebhookDeliveryServiceSpec` 8, `HmacMerchantWebhookSignerSpec` 5, `HttpMerchantWebhookSenderSpec` 16, `MerchantWebhookDeliverySettingsSpec` 4,
  `MerchantWebhookDeliveryDbSpec` 7, `DatabaseAccountSeparationSpec` 17, `PaymentApiContextSmokeSpec` 5, `MainConfigurationSpec` 6 통과, `ArchitectureSpec` 1/47 실패(n-6 규칙의 Webhook 타입 목록에 S3 타입이 없어 Webhook 안 타입끼리의 의존을
  위반으로 봄) → 목록에 S3 타입 이름 추가(규칙 완화가 아니라 분류 갱신 — 업무 서비스는 여전히 의존 금지) 07:37:57Z 47/47. 07:42Z 소유 상실 실패 시험 추가 뒤 `MerchantWebhookDeliveryDbSpec` 8/8.
- **변이 RED(S3)** 10/10 죽음: MD1 실패 반영의 소유 확인 제거 → 처음엔 살아남음(기존 시험은 DELIVERED 뒤 늦은 실패만 봄) → "새 소유자가 아직 보내는 중일 때 늦은 실패는 CLAIM_LOST, 새 claim 유지" 시험 추가 뒤 죽음(1/8),
  MD2 성공에 소유 요구 → 1/7, MD3 본문 sha 확인 제거 → 1/8, MD4 redirect 추종 → 1/16, MD5 allowlist를 환경과 무관하게 → 1/16(LIVE 전달 차단 시험), MD6 전체 기한 abort 제거 → 1/16(slow-drip),
  MD7 해석 주소 판정 생략 → 1/16, MD8 예약 번호 대신 1 → 1/8, MD9 서명 hex 대문자 → 3/5, MD10 lease 경계 포함(>=) → 1/4.
- **구현(가역 — 명세 §5.0·§7.2·§21.0·§21.0b와 같음)**: domain `WebhookAddressPolicy`(IPv4 IANA 특수 대역·IPv6 2000::/3 밖 기본 거부·mapped/호환/6to4/NAT64/Teredo 차단), application `MerchantWebhookDeliveryService`(claim TX 예약 →
  TX 밖 시도 → 결과 TX 반영 + 시도 행), Port `MerchantWebhookAttemptStore`·`MerchantWebhookEventReader`·`MerchantWebhookSigningKeys`·`MerchantWebhookSigner`·`MerchantWebhookSender`·`MerchantWebhookDeliveryTimings`,
  `DeliverMerchantWebhooksUseCase`; persistence `JpaMerchantWebhookDeliveryRepository.claimDue/recordResult`(SKIP LOCKED·lockRows·성공 단조·소유자 실패만·끝나면 claim 해제), `JpaMerchantWebhookAttemptEntity/Store`(V25·V26 append-only, UNIQUE(전달, 번호)),
  `JpaMerchantWebhookEventStore.find`(읽기 전용 TX); integration `HmacMerchantWebhookSigner`(§21.0 문법·WH-19 키 바이트), `HttpMerchantWebhookSender`(URL 문법 → `DESTINATION_REJECTED`, 해석 예산 → `DNS_TIMEOUT`,
  모든 주소 공인 → 아니면 `DESTINATION_BLOCKED`, 검사한 주소에 고정 연결·원 host SNI·hostname 검증, redirect·재시도·쿠키·시스템 속성 없음, 예약 abort 전체 기한, 응답 4 KiB 상한, TEST allowlist는 **전달 환경 TEST일 때만**),
  `MerchantWebhookTransportSettings`(기본값 없음, JVM 전역 trustStore 설정 시 기동 거부 — m-5), `MerchantWebhookDeliverySettings`+`MerchantWebhookTimingConsistency`(lease > DNS + 기한 + 5s), `ConfiguredMerchantWebhookSigningKeys`
  (`paycore.webhook.test-signing-keys` — TEST만, MID당 최대 2), `MerchantWebhookTransportConfiguration`(TEST allowlist 전용 추가 CA PEM, 선택); batch `MerchantWebhookDeliveryScheduler`(+ JobSettings); ArchUnit `useSystemProperties()` 금지(위반 fixture).
- **시험 범위**: T-D1(성공·서명 HMAC 독립 검증·재시도 10s·포기 GAVE_UP), T-D2(응답 유실 → 같은 eventId·같은 본문, t·서명만 다름), T-D3(lease 인수 — 두 시도 행, 늦은 실패 CLAIM_LOST, 새 소유자 송신 중 늦은 실패 무변경,
  소유 잃은 2xx DELIVERED), C-D1(4 worker × 25 전달 — 전달마다 시도 1·수신 1), T-S5·T-S6·T-E1·TLS(자체 서명·hostname 불일치 거절, 고정 IP + 원 host SNI 성공, 시험 CA 없으면 거절) — 시험 TLS 재료는 실행 때 JDK keytool로 만든다(저장소에 키 없음).
- **남은 것(S4·S5)**: Simulator `merchant` 수신기 모듈·서명 golden(+ harness 독립 생성, ±300초 양 경계), T-N1 로그 비노출 캡처, T-Z1 비UTC JVM, T-V1/T-V2 API 대조, 관례 새 절·교차 참조, GAVE_UP gauge. DNS rebinding은 해석 1회 → 그 주소로만 연결하는 구조로 막으며
  (재해석 경로 없음) 외부망 없이 재현하는 시험은 두지 않았다(검토 판단 요청).

### 21.7 검토 #2(CHANGES_REQUIRED) 대응·S4·S5 실행 기록(2026-10-22)

**검토 #2 대응(시험 먼저 — RED-C 08:36:24Z: 새 기호 12건 미해결 컴파일 오류)**

- **H-1(lease 대 회차)**: 보내기 직전마다 `now + attemptBudget ≤ claimUntil`(경계 포함)을 확인하고, 아니면 보내지 않고 `releaseClaim(id, owner, attemptNo)`로 claim과 예약 번호를 되돌린다(domain
  `releaseReservedAttempt` — PENDING·최신 예약만, 시도 수 0이면 첫 시도 시각도 지움). `ClaimedDelivery.claimUntil`, Port `MerchantWebhookDeliveryTimings.attemptBudget`(구현 `ConfiguredMerchantWebhookDeliveryTimings`
  = DNS 예산 + 전체 기한 + 5s, 같은 값으로 기동 검사), 회차 결과 `released`. 되돌림 TX가 실패하면 lease 만료 뒤 다시 잡히며 그때 번호 하나가 시도 행 없이 남는다(n-2와 같은 틈 — 기록).
  회차 크기 × 예산의 기동 상한은 두지 않았다(보내기 직전 검사가 회차 크기와 무관하게 지킨다 — 선택 사항으로 남김).
  - 실 MySQL 시험(`MerchantWebhookDeliveryDbSpec` "H-1 (review #2)"): 수신 2s, 이 시험 전용 전송(응답·기한 4s), 예산 5s, lease 7s, batch 4, worker 2개가 20s 동안 계속 회차를 돈다. 전달마다
    수신 수 = 시도 행 수 = attempt_count, 시도 번호 1..n 연속 + **추가 기대: 전달마다 수신 정확히 1**.
  - 시험 구성 변경 기록(기대 완화 아님): 처음 구성(수신 0.8s, lease 4.5s, 회차 수 12)에서는 한 회차가 lease 안에 끝나 검사 제거 변이(MR1b)가 살아남았다 → 수신 2s(공용 전송 응답 1s를 넘으므로 전용 전송),
    worker를 고정 시간 동안 돌게 바꿈(빈 회차만 돈 worker가 lease 만료 전에 멈추던 원인) → MR1b 죽음(08:53:54Z, 1/10).
- **m-1(응답 상한)**: 상태 줄을 받으면 본문을 읽지도 비우지도 않고 `post.cancel()`(abort) 뒤 닫는다(abort 뒤 close의 IOException은 결과와 무관 — 무시). T-S6 추가: 2xx 뒤 끝없는 느린 본문 → Succeeded(200), 1s 미만.
  상태 줄을 바이트씩 흘리는 서버(`SlowHeadersServer`) → 전체 기한에 TIMEOUT(1.4~3s). 결과: `paycore.webhook.transport.max-response-bytes` 설정은 남아 있지만 전송 경로가 본문을 읽지 않아 쓰이지 않는다(설정 호환 — 제거는 후속 판단).
- **n-1**: 실패 결과는 소유자 + `attemptNo == attempt_count`(최신 예약)일 때만 반영, 그 밖은 CLAIM_LOST. 같은 소유자의 앞선 시도 늦은 실패는 진행 중인 최신 claim을 지우지 않는다(claim 해제 조건에 최신 예약 추가).
- **n-2(IANA IPv6 special-purpose registry 재확인)**: 추가 차단 2001:2::/48 BENCHMARK(전역 False), 2001:10::/28 ORCHID(폐기), 2001:20::/28 ORCHID_V2(레지스트리 전역 True지만 overlay 식별자 — 기본 거부로 더 엄격하게
  둠, 검토 확인 대상), 3fff::/20 DOCUMENTATION(RFC 9637, 전역 False). 전역 True인 2001:1::1/128·2001:1::2/128(anycast)·2001:3::/32(AMT)·2001:4:112::/48(AS112)는 허용, 64:ff9b:1::/48·5f00::/16은 2000::/3 밖이라 이미 거부.
- **n-3**: 해석 스레드 `ThreadPoolExecutor(0, DNS_POOL_SIZE = 8, SynchronousQueue)` — 가득 차면 거부 → `DNS_TIMEOUT`(새 스레드 없음, 수신기 무수신).
- **n-4(DNS rebinding — 구조 방어 수용)**: 근거 시험 = `HttpMerchantWebhookSenderSpec` "the connection goes to the resolved (pinned) address while SNI and hostname verification use the original host name (n-4: one lookup per connection)"
  — 고정 IP 연결 + 원 host SNI·검증 성공, **연결 하나당 resolver 호출 1회**(단언 추가).
- **n-5**: main `application.yml`에 `org.apache.hc.client5.http.wire`·`…headers` = WARN. T-N1 `MerchantWebhookStartupDisclosureSpec`: 잘못된 `test-signing-keys` 비밀·중복 key id로 기동 실패 시 실패 보고(Boot FailureAnalyzer
  "APPLICATION FAILED TO START" 수집 확인)·예외 사슬에 비밀 canary 없음 + 로거 WARN 고정. 실측(탐침 09:08:50Z): Boot 4.1 생성자 검증 실패 보고는 Description·Reason만 있고 "Property/Value" 줄이 없다 — 제품 코드 변경 불필요.
- **n-6(기록)**: `hasEarlierOpenRow`는 후보마다 같은 Payment 앞 행을 다시 보므로 한 Payment 행 수에 대해 O(n²)이다(한 Payment 이벤트는 수 건). 후속: 생성 열(paymentId) + 인덱스.
- **n-7**: gauge RED는 변이로만(수용). S4·S5는 실제 RED-C·RED-A를 받았다(아래).
- **n-8(기록)**: ArchUnit 업무 서비스 ↔ Webhook 분리는 타입 이름 접두 목록이다. 하위 패키지 분류로 바꾸는 것은 ARCH-SUBPKG 후속.
- **RED-A(08:37:46Z)**: m-1 끝없는 본문 → `Failed(TIMEOUT)`(기대 Succeeded), n-3 포화 → `Succeeded`(기대 DNS_TIMEOUT), n-2 8행 Public. H-1·n-1은 구현과 같은 편집에서 들어가 RED-A를 변이로 받았다.
- **GREEN**: 08:39:57~08:42:20Z 영향 spec 전부(첫 GREEN에서 abort 뒤 close IOException → CONNECTION 분류, n-1 claim 해제 조건 — 둘 다 고침), H-1 시험 강화 뒤 08:51:37Z `MerchantWebhookDeliveryDbSpec` 10/10.
- **변이 RED(검토 #2)** 7/7 죽음: MR1 예산 검사 제거(단위) 1/10, MR1b 같은 변이(실 MySQL) 1/10, MR2 abort 제거 1/18, MR3 번호 fence 제거 1/10, MR4 무제한 해석 풀 1/18, MR5 domain 되돌림 생략(번호 소모) 1/10,
  MR6 되돌림이 첫 시도 시각을 남김 1/16. 서명 golden: MG1 hex 대문자 2/3, MG2 서명 대상의 "." 누락 2/3.

**S4 — Simulator `merchant` 가상 가맹점 수신기·서명 golden**

- 새 패키지 `com.paycore.simulator.merchant`(새 Gradle 모듈 없음): domain `WebhookSignatureCheck`·`WebhookVerdict`·`WebhookVerification`·`ReceiverScenario`·`ReceiverReply`·`ReceiptOutcome`·`ReceivedEnvelope`·`MerchantWebhookReceipt`,
  application `MerchantWebhookReceiverService`(provided `ReceiveMerchantWebhookUseCase`·`MerchantWebhookReceiptQuery`, required `MerchantWebhookReceivers`·`WebhookSignatureMatcher`·`MerchantWebhookReceiptStore`·`MerchantReceiverClock`·`DuplicateMerchantReceipt`),
  adapter web `MerchantWebhookReceiverController`(`POST /sim/merchant/v1/webhooks/{receiverId}`, `GET …/receipts` — 소유 PG client Bearer, 다른 client·모르는 수신기 404), `MerchantWebhookJson`, integration
  `HmacWebhookSignatureMatcher`(`MessageDigest.isEqual`)·`MerchantReceiverSettings`(기본 수신기 0개 — fail-closed, 수신기가 있으면 `signature-window` 필수), persistence `JpaMerchantWebhookReceiptEntity`(`@Immutable`)·
  `JpaMerchantWebhookReceiptStore`·`MerchantReceiptPrivilegeGuard`(SmartLifecycle 가장 이른 phase — VAN 원장 검사 뒤·서버 시작 전, 옛 볼륨 진단 메시지 유지). Simulator V8(`merchant_webhook_receipt` — 요청당 한 행, 본문 대신 SHA-256,
  첫 2xx만 ACCEPTED: (수신기, first_acceptance_event_id) UNIQUE, 모양 CHECK), V9 권한 SELECT·INSERT.
- 판정 순서: 수신기 → 서명(문법 → ±300s → 서명) → 본문 봉투 → environment TEST(n-6) → 시나리오 응답·중복. 거절 4xx(MALFORMED·BODY·ENVIRONMENT 400, STALE·SIGNATURE 401). 검증 전 본문 값은 원장에 남기지 않는다.
- 편차: 시나리오 "저장 후 연결 끊기(응답 유실)"는 Simulator에 두지 않았다 — 서블릿 응답 안에서 연결만 끊는 표준 수단이 없고, 같은 장애는 payment-api 시험 대역 `ProgrammableMerchantReceiver`(T-D2)가 덮는다(§9 "Simulator 수신기는 계약 golden·로컬 E2E용").
- 서명 golden `webhook-signature-v1.golden.json`(두 앱 `src/test/resources/contracts/`, 바이트 동일): 서명 벡터 2(단일 키, 교체 2키 + UTF-8 본문), 검증 사례 17(±300 통과·±301 STALE, 본문 1바이트, 모르는 kid, 교체 중 퇴역 키 + 아는 키,
  모르는 매개변수 무시, 대문자 hex·t 2개·t 없음·3쌍·kid 없는 v1·공백·`=` 없는 조각 → MALFORMED). harness `test_webhook_signature_golden.py` 4건(Python `hmac`·`hashlib` 독립 생성) — RED(파일 없음 오류) → `--write` → GREEN.
  harness 전체 97건(이전 93 + 4) — `test_selftest_minimum_equals_number_of_harness_tests`는 `HARNESS-SELFTEST.minTests`(93)를 Coordinator가 97로 올릴 때까지 실패한다(구현자는 `commands.json` 불변).
- RED-C 08:57:29Z(Simulator 시험 컴파일 — `HmacWebhookSignatureMatcher`·`MerchantReceiverClock` 미해결 3건; domain 시험 2개는 domain 구현과 같은 단계라 RED는 변이로). GREEN 09:00:32→09:01:36Z: `WebhookSignatureCheckSpec` 6,
  `ReceiverScenarioSpec` 4, `WebhookReceiverVerificationSpec` 19(golden 17 + 벡터 2), `MerchantWebhookReceiverApiSpec` 11(실 Tomcat·MySQL), `SimulatorArchitectureSpec` 16(+2: merchant ↔ authentication provided만·VAN 무관, 원장 Store 연산·`@Immutable`),
  `SimulatorDatabaseAccountSpec` 4(기대 추가: runtime 권한 줄·1142 탐침 2·기동 거부 grant 2), `SimulatorBootSpec` 2. payment-api `MerchantWebhookSignatureGoldenSpec` 3.
- **변이 RED(S4)** 9/9 죽음: MS4-1 창 경계 배타 2/19, MS4-2 서명 바이트 미비교 3/19, MS4-3 모르는 매개변수 거절 1/6, MS4-4 environment 검사 제거 1/11, MS4-5 eventId 중복 제거 제거 1/11, MS4-6 n회 실패 경계 1/4,
  MS4-7 guard DELETE 탐침 제거 1/4, MS4-8 V9가 UPDATE 부여 → guard가 기동 거부 2/2, MS4-9 거절 행에 미검증 봉투 저장 1/11.

**S5 — gauge·일관성·무효과·시간대·비노출·문서**

- gauge(§11): `paycore.webhook.delivery.pending`·`paycore.webhook.delivery.pending.oldest_due_age_seconds`·`paycore.webhook.delivery.gave_up`(label 없음, jOOQ Reader 확장 — status 인덱스 접두). 편차: §11의 counter
  `paycore.webhook.{fanout,delivery}{result}`·timer `paycore.webhook.delivery.duration`은 이번에 만들지 않았다(구조화 로그 줄 + gauge로 관측 — 후속 판단 요청).
- `MerchantWebhookConsistencySpec`(`@PaymentApiTest`, 실 Tomcat·MySQL, 운영 경로): **T-V1** 승인 + 가맹점 부분취소 + 운영자 잔액 취소(3 이벤트), 거절 confirm(`payment.failed`), 취소 거절(`payment.cancel_failed`), 만료(`payment.expired`) —
  섹션별 Webhook 키·값 ⊆ 공개 GET 키·값(취소는 같은 cancellationId 항목, 마지막 이벤트의 returnSummary는 현재 GET과 같음). P1 숨김은 T-F3. **T-V2** 수신기 영구 500 → 두 전달 GAVE_UP(실제 송신 ≥ 2) 동안 GET 응답 바이트·
  `return_balance` 행(version·updated_at 포함)·Outbox `publish_status` 불변 + GAVE_UP gauge. **T-N1** fan-out·delivery 로그에 비밀·서명 값·본문 조각·URL(host:port·경로)·주문 ID 없음. **T-Z1** JVM 기본 Asia/Seoul,
  KST 날짜가 바뀌는 시각: 본문 `createdAt`·이벤트 `created_at`·전달 `delivered_at`·서명 `t`가 같은 UTC 순간.
- RED-C 09:10:50Z(`gaveUpRows` 미해결). 첫 실행 2/6 실패는 시험 구성 문제였다(Then마다 afterEach가 수신 기록을 지움 → Given에서 수집, 앞 Given의 전달이 T-Z1 시각에 도래해 같은 수신기로 감 → 이 Payment eventId로 거름) — 기대 불변.
  GREEN 09:13:15Z 6/6, `MerchantNotificationFanoutDbSpec` 15/15. T-N1 기동 spec 3/3(09:07:31Z).
- **변이 RED(S5)**: MV1 GAVE_UP gauge 0 → 1/6, MV2 취소 본문 값 변조 → T-V1 1/6, MV3 포기 시 return_balance 갱신 → T-V2 1/6, MN1 검증 메시지에 비밀 → 1/3, MN2 wire 로거 WARN 제거 → 1/3, MV4 본문 시각을 JVM 시간대로 → 3/6(T-Z1·T-V1), MV5 delivery 로그에 URL → T-N1 1/6, MV6 delivery 로그에 서명 헤더 → T-N1 1/6. S5 변이 8/8 죽음.
- **전체 check(분리 실행, `./gradlew --continue check`, HEAD df6174d + 작업 트리)**: 09:19:43Z → 09:31:25Z, exit 0, BUILD SUCCESSFUL 11m41s. 보고서 수: payment-api 1,753(새로 실행), institution-simulator 210(새로 실행),
  master-api 320·financial-batch 26·internal-admin 29·libs/test-support 112(입력 불변 — `:test UP-TO-DATE`, 이전 보고서), 합계 2,450, 실패·오류·skip 0. VERIFY-GATE(`--rerun-tasks`)가 아니다.
  harness 97건 중 1건(`test_selftest_minimum_equals_number_of_harness_tests` — minTests 93 ≠ 97)은 Coordinator가 하한을 올릴 때까지 실패한다.

**최종 검토(PASS_WITH_FIXES) 대응(2026-10-22)**

- **m-1 지표**: counter에 `released_lease_short`·`blocked` 추가, timer `paycore.webhook.delivery.duration` 추가(회차 결과 `MerchantWebhookDeliveryReport.blocked`·`attemptDurations` — application은 단조 시계 `System.nanoTime`만, Micrometer는
  batch adapter). §11 "구현된 지표"·§13 Observability 행을 구현과 맞췄다. 시험 `MerchantWebhookSchedulersSpec`(SimpleMeterRegistry — result별 값, label 키는 result 하나, timer 3회·합계)와 서비스 단위 시험(blocked 2, 보낸 시도만 시간).
  RED-C 09:36:59Z(새 필드 미해결) → 필드만 stub(0·빈 목록)으로 넣고 RED-A 09:37:31Z(`released_lease_short` 0, blocked 0, cap 미적용 3건) → GREEN 09:39:06Z.
- **n-1**: `MerchantWebhookTransportSettings` KDoc·§21.0b에 `max-response-bytes` 미사용(호환 유지) 기록.
- **n-2 claim 상한**: 한 claim은 `min(batch, floor(lease / 한 시도 예산))`개만 잡는다(최소 1 — 기동 검사 lease > 예산). 단위 시험(lease 60s·예산 22s·batch 20 → 2, batch 5 → 5)과 실 MySQL H-1 시험 추가 기대
  "회차당 claim ≤ floor(7/5) = 1". 단위 시험 fixture 기본 예산을 22s → 3s로 바꿨다(기존 `claim:20:60`·`claim:5:60` 기대를 그대로 두기 위해 — H-1 시험은 22s를 명시). 실 MySQL H-1 시험 구성에서는 이제 상한이 겹침을 먼저
  막으므로 보내기 직전 lease 검사는 그 시험에서 backstop이 된다(검사 자체의 RED는 단위 MR1이 계속 잡는다). HVC §7에 인스턴스 시계 NTP 동기 조건 추가.
- **변이 RED** 7/7 죽음: MM1 released counter 누락 1/2, MM2 blocked를 다른 label 키로 1/2, MM3 timer 미기록 1/2, MM4 fan-out no_endpoint 누락 1/2, MM5 상한 제거(단위) 1/14, MM5b 상한 제거(실 MySQL) 1/11,
  MM6 서비스 blocked 미집계 1/14. ArchitectureSpec 47/47(09:41:45Z).
- **전체 check(분리 실행, `--continue check`)**: 09:42:26Z → 09:56:32Z, exit 0, BUILD SUCCESSFUL 14m5s. payment-api 1,760·institution-simulator 210·master-api 320·internal-admin 29(새로 실행),
  financial-batch 26·libs/test-support 112(UP-TO-DATE — 이전 보고서), 합계 2,457, 실패·오류·skip 0. harness 97건 전부 통과(Coordinator가 HARNESS-SELFTEST 하한을 갱신한 뒤).

**최종 검토 #2(CHANGES_REQUIRED m-1) 대응 — releaseClaim 실 DB 증거(2026-10-22)**

- claim 상한 뒤 H-1 DB 시험(lease 7s·예산 5s → 상한 1)은 되돌림 경로에 닿지 않는다. `MerchantWebhookDeliveryDbSpec`에 결정적 시험 3개 추가("final review #2 m-1: releaseClaim on real MySQL"):
  ① 첫 예약 되돌림 → true, attempt_count 0·first_attempt_at NULL·claim_owner/claim_until NULL·PENDING, 시도 행 없음, 다음 claim이 다시 1을 예약(첫 시도 시각 새로 기록),
  ② 실제 시도 1회(HTTP_500 → RETRY) 뒤 두 번째 예약 되돌림 → attempt_count 1·first_attempt_at 유지·claim 해제·last_failure_code 유지, 이어서 다른 소유자·낡은 시도 번호 → false·행 무변경,
  ③ DELIVERED 행 → false·행 무변경. 모든 행 변경은 `ck_merchant_webhook_delivery_attempts`를 실제 MySQL에서 통과한다. 구현은 이미 있었으므로 GREEN 10:01:09Z 14/14 뒤 RED는 변이로 받았다.
- **변이 RED** 3/3 죽음: MRC1 mapper가 되돌림에서 first_attempt_at 유지 1/14, MRC2 되돌림이 claim을 지우지 않음 2/14, MRC3 소유자 확인 제거 1/14.
- **MR1b(보내기 직전 lease 검사 제거, DB 수준) — DB 수준 동등 변이로 수용**: 재실행 10:07:10Z 0/14(생존). claim 상한이 겹침을 먼저 막아 그 시험 구성에서는 검사가 결정을 바꾸지 않는다. 검사의 판정은 단위 MR1이,
  되돌림 쓰기는 위 실 DB 시험이 지킨다(최종 검토 #2 판정).
- **전체 check(분리 실행, `--continue check`)**: 10:16:53Z → 10:31:02Z, exit 0, BUILD SUCCESSFUL 14m9s. payment-api 1,763·master-api 320·institution-simulator 210·financial-batch 26(새로 실행),
  internal-admin 29·libs/test-support 112(UP-TO-DATE — 이전 보고서), 합계 2,460, 실패·오류·skip 0. harness 97건 통과.
- 문서: 관례 §19(새 절 — JPA 역할·예·이유·주의, Kafka/Redis 미도입), AC1 §10.2·CC §14-6·CPQ §1.2 교차 참조 한 줄씩, HVC §7 배포 메모 갱신, 이 명세 §20.1 7~9항.

**최종 검토 #3(PASS_WITH_FIXES) 대응(2026-10-24)**

- **n-1 lease 부족 되돌림 집계**: `MerchantWebhookDeliveryService.release()`가 저장소 답을 그대로 반영한다 — `releaseClaim` true → `released`(counter `released_lease_short`), false(그 사이 claim을 잃었거나 행 상태가 바뀜) →
  `claimLost`(결과 반영 CLAIM_LOST와 합산, counter `claim_lost`), 저장소 예외(재시도 소진 포함, RELEASE_FAILED ERROR 로그 유지) → `recordFailed`(counter `record_failed`). 로그 줄 `released=false`는 그대로 남긴다.
  `MerchantWebhookDeliveryScheduler`는 report 필드를 그대로 counter에 쓰므로 매핑 변경·새 result 없음(label은 result 하나) — KDoc만 보강, `MerchantWebhookSchedulersSpec` 변경 없음. `MerchantWebhookDeliveryReport` KDoc 갱신.
  시험 `MerchantWebhookDeliveryServiceSpec` "n-1 (final review #3)" 3개 추가(true → released 1·claimLost 0·recordFailed 0, false → released 0·claimLost 1, 비재시도 예외와 Deadlock 재시도 소진(releaseClaim 3회) → released 0·recordFailed 1).
  기존 기대값 변경 없음. RED 07:03:34Z(2/17 — false: released 기대 0 실제 1, 예외: recordFailed 기대 1 실제 0) → GREEN 07:04:43Z 17/17.
- **변이 RED** 3/3 죽음: MN1 false도 released로 셈 1/17, MN2 되돌림 예외를 어디에도 세지 않음 1/17, MN3 false를 claimLost에 더하지 않음 1/17(원본 복원 cmp 확인).
- **m-1 문서**: §20.1 7항을 실제 동작으로 고쳤다 — 한 claim은 min(batch, floor(lease / 한 시도 예산))개(최소 1), Demo 값(lease 60s·예산 22s)에서는 회차당 최대 2건이라 job batch 50은 사실상 미적용,
  처리량은 lease/예산과 fixed-delay가 정하고 보내기 직전 lease 검사는 GC·정지 대비 backstop, 운영값은 LIVE Gate에서 처리량 실측과 함께 정한다. 관례 §19 시간 설정 행(min(batch, floor(lease / 예산)))과 일치. HVC §7 배포 메모에 같은 처리량 한 줄.
- **n-2 문서**: timer `paycore.webhook.delivery.duration`은 `attempt()` 호출 전부터 재므로 §11과 `DeliverMerchantWebhooksUseCase` KDoc을 "보낸 시도의 준비(이벤트 읽기·무결성 확인·조회)·서명·전송 시간"으로 고쳤다. 측정 지점 코드 불변.
- **실행 시험(영향 클래스만, 07:06:42Z → 07:08:26Z, exit 0)**: `MerchantWebhookDeliveryServiceSpec` 17·`MerchantWebhookSchedulersSpec` 2·`MerchantWebhookDeliveryDbSpec` 14(실 MySQL)·`ArchitectureSpec` 47, 합계 80, 실패·오류·skip 0.
  전체 check·VERIFY-GATE는 실행하지 않았다(Coordinator 담당).
