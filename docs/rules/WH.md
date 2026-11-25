# WH 업무 규칙 대장 — 웹훅·외부 통지·이벤트 전달 (초안 rev 0.1)

- 초안 2026-11-25(RULE-GOV-001 2단계, 문서만). 사용자 확인 전이며 `제안` 행은 Coordinator·구현 결정에서 끌어낸 규칙이다.
- 원천: `docs/DECISIONS.md` D-WH-001~009·D-FFR-001·004·D-ACQ-001·D-CPC-003·D-AUTHN-008·D-MS-004·D-MCODE-002(Q-MC-8)·D-ADMIN-UI-007 ④, `harness/questions.json` Q-WH-01~09, `docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md`(rev 0.3), `docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md`(rev 0.2), baseline `architecture/03`·`06`·`10`, 코드 `apps/payment-api/…/payment/{domain,application,adapter}/*MerchantWebhook*·*MerchantNotification*`, `apps/institution-simulator/…/simulator/merchant/`.
- 범위: 가맹점 Webhook 공개 계약·전달, Outbox 기록·불변, 내부 이벤트 전달(PAYMENT→FINANCIAL·MASTER→PAYMENT)의 **전달 의미**. 투영·보류 판정(FINANCIAL)은 FIN, 권리 Snapshot 내용은 BIZ, 이벤트를 낳는 업무 전이는 PAY·CAN 대장이 정본이다. 순수 기술 관례(guard 행 직렬화·SKIP LOCKED·Mapper·명명·지표 이름)는 넣지 않았다.
- 약어: AC1 = `AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`, CC = `CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`, 03/06/10 = `docs/baseline/architecture/0x-…md`, 범위 40행 = `docs/baseline/PayCore-최초구현범위-권장안.md` 40행. 시험 파일은 `apps/<app>/src/test/kotlin/…` 아래 파일명만 적는다.

## 1. 통지 대상(어떤 사실을 가맹점에 알리는가)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-001 | 가맹점 Webhook v1 이벤트 종류는 `payment.authorized`·`payment.failed`·`payment.expired`·`payment.cancelled`·`payment.cancel_failed` 다섯 가지뿐이다. | 확정 | 기준 | AC1 §10.2·§11, CC §9.3·§14-6, D-WH-008 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1 | MerchantNotificationPolicySpec::the public wire names — exactly the five names |
| BR-WH-002 | 내부 위험·운영 이벤트(`*IncidentDetected`·`*OutcomeUnresolved`)는 가맹점에 보내지 않는다. | 제안 | 적용 | D-CPQ-001, AC1 §11, WEBHOOK-001 §4.1 도출·§17.9 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1 | MerchantNotificationPolicySpec::Outbox events that are not merchant notifications — not applicable |
| BR-WH-003 | 결제 생성(`PaymentRequested`)은 가맹점에 통지하지 않는다(가맹점은 동기 생성 응답으로 안다). | 제안 | 적용 | WEBHOOK-001 WH-01 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1·§16 | MerchantNotificationPolicySpec::Outbox events that are not merchant notifications (creation) |
| BR-WH-004 | 진행 중 상태(AUTHORIZING·UNKNOWN, 취소 IN_PROGRESS·UNKNOWN) 진입은 통지하지 않고, 회복으로 확정된 결과를 포함해 확정 결과만 통지한다. | 제안 | 적용 | WEBHOOK-001 F-5·§4.1·§17.9, 범위 40행 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1·§4.4 | ConfirmFlowSpec::PENDING → 202 UNKNOWN … no outbox event |
| BR-WH-005 | AUTHORIZED Payment의 운영자 보정 취소·취소 실패는 `payment.cancelled`/`payment.cancel_failed`로 알리되 개시자는 `initiator: OPERATOR`만 담고 `merchantCancelId`·운영자 신원·사유·approvalId는 담지 않는다. | 확정 | 사용자 | D-WH-001, Q-WH-01, D-CPQ-002 (i) | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1·§4.2.0 | MerchantNotificationFanoutDbSpec::T-F4 bodies … initiator OPERATOR for the operator one; MerchantWebhookBodyJsonSpec::payment.cancelled by an operator: initiator only |
| BR-WH-006 | 가맹점에 FAILED/EXPIRED로 알려진 Payment(P1)의 늦은 승인과 그 취소·취소 실패는 가맹점에 보내지 않는다. | 확정 | 사용자 | D-WH-001, D-CPQ-002 (ii), D-CANCEL-013 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1·§4.4 | MerchantNotificationFanoutDbSpec::T-F3 P1 … SUPPRESSED and no merchant event |
| BR-WH-007 | v1 Webhook은 사고 보류(hold) 이벤트와 보류 필드를 보내지 않는다(RECON 보류 해제 Use Case와 함께 재결정). | 확정 | 사용자 | D-WH-002, Q-WH-02, D-CPQ-001 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.4 | MerchantWebhookBodyJsonSpec::each event type (exact bytes — T-C2) |
| BR-WH-008 | 통지 대상 이벤트가 그 이벤트가 생길 수 없는 Payment 상태와 함께 오면(또는 읽을 수 없으면) 숨기지도 보내지도 않고 비종결 실패로 남겨 경보하며, 같은 Payment의 뒤 이벤트는 그동안 보내지 않는다(다른 Payment는 계속). | 제안 | 적용 | WEBHOOK-001 rev 0.2 H-3·m-3 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.0 | MerchantNotificationFanoutDbSpec::T-F5 anomalous row FANOUT_FAILED … later row stays PENDING; MerchantNotificationFanoutServiceSpec::a poison row … never SUPPRESSED |
| BR-WH-009 | 이전 generation 사건으로 주문이 보류 중이어도 현재 Payment가 AUTHORIZED로 확정되면 `payment.authorized`를 보낸다. | 제안 | 적용 | D-AUTH-011, WEBHOOK-001 §4.1·§18-11 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.1 | 없음 |
| BR-WH-010 | Webhook 기능 배포 전에 쌓인 Outbox 이벤트는 가맹점에 보내지 않는다(과거 결과를 한꺼번에 보내지 않음). | 제안 | 적용 | WEBHOOK-001 §18-4 판정·§20.1-3 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.11 | OutboxNotificationMigrationSpec::existing volume at V22 … old rows SKIPPED_PRE_DEPLOY and new inserts become PENDING |
| BR-WH-011 | 통지 시점에 그 (환경, MID)의 endpoint가 없으면 이벤트는 기록하고 순번을 쓰되 보내지 않으며, 나중에 endpoint가 생겨도 과거 이벤트를 자동으로 몰아 보내지 않는다(재전송은 운영자 명령). | 제안 | 적용 | WEBHOOK-001 WH-12 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.10 | MerchantNotificationFanoutServiceSpec::a delivery row exists only when the (environment, MID) has an endpoint (자동 몰아 보내기 금지 부분은 없음) |

## 2. 공개 본문·비노출

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-012 | Webhook 본문은 Outbox payload를 그대로 노출하지 않는 별도 공개 계약(봉투 v1)이다. | 확정 | 기준 | AC1 §10.2, 03 핵심 규칙("명시적 Integration Contract") | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | MerchantWebhookBodyJsonSpec::each event type (exact bytes — T-C2) |
| BR-WH-013 | 본문의 각 섹션 키·값은 같은 Payment의 GET 응답이 노출하는 키·값의 부분집합이다(Webhook으로 새로 드러나는 사실이 없다). | 제안 | 적용 | WEBHOOK-001 WH-06·rev 0.2 m-1 Coordinator 판정, D-CPQ-001·002·005 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | MerchantWebhookBodyJsonSpec::every webhook data key is a GET key; MerchantWebhookConsistencySpec::T-V1 every section of every body is a subset of what GET shows |
| BR-WH-014 | 본문에는 카드정보·Secret·인증 증거·기관 원문·기관 거래번호(PTX)·기관 일자·발급/매입사 코드·기관 계약계정·정책/권리/Route 버전·실패 근거 원 이름·운영자 정보·hold 사유·내부 키(Outbox event_id·effect_key·`mer_`·`biz_`)·`merchantSiteId`·`orderName`·`paymentKey`가 없다. | 확정 | 기준 | AC1 §10.1, 05-security 19~24행, D-MS2-005, D-CPQ-001·002, D-ADMIN-004 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0·§11 | MerchantWebhookBodyJsonSpec::no internal key (mer_ / biz_); OutboxNotificationPayloadsSpec::provider ids, institution dates and versions are not carried; MerchantWebhookConsistencySpec::T-N1 logs carry no secret … no URL |
| BR-WH-015 | 가맹점 코드(`merchantCode`)는 Webhook·결제 응답에 출력 전용으로만 싣고, 식별·인증은 MID·API 키로 한다. | 확정 | 사용자 | D-MCODE-002 Q-MC-8 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md 머리말 rev 0.3 | MerchantWebhookBodyJsonSpec::a MID with a code carries it right after merchantAccountId |
| BR-WH-016 | `merchantCode`는 코드가 전파된 MID에만 넣고 없으면 키를 생략(null 아님)하며, 추가 전에 만든 이벤트는 저장된 바이트 그대로(코드 없이) 재전송한다. | 제안 | 적용 | MERCHANT-CODE-001 C-36(MC-4), 03 스키마 가산 규칙 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md 머리말 rev 0.3 | MerchantWebhookBodyJsonSpec::a MID without a code omits the key (not null); MerchantWebhookDeliveryDbSpec::the event stored before the promotion is resent with exactly the stored bytes |
| BR-WH-017 | Site 코드·계약 코드는 Webhook에 노출하지 않는다(관리자 화면·MASTER 전용). | 확정 | 사용자 | D-ADMIN-UI-007 ④, Q-SC-7 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | MerchantWebhookBodyJsonSpec::each event type (exact bytes — 키 집합 고정으로 간접) |
| BR-WH-018 | 취소 본문의 `cancelTransactionId`는 PayCore `transactionId`이며 기관 거래번호(PTX)가 아니다. | 제안 | 적용 | WEBHOOK-001 rev 0.2 n-5 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | OutboxNotificationPayloadsSpec::cancelTransactionId is PayCore's transactionId, never the provider PTX; MerchantNotificationFanoutDbSpec::T-F4 bodies carry GET values |
| BR-WH-019 | 본문 값은 그 사실 시점 값이고 현재값(`cancellableAmount`·취소 진행 금액)은 넣지 않는다. 봉투 `createdAt`은 가맹점 이벤트 생성 시각(업무 시각 아님)이며 실패·만료·취소 실패 시각은 넣지 않는다. | 제안 | 적용 | WEBHOOK-001 WH-06·rev 0.2 m-1, D-CPQ-005 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | MerchantWebhookBodyJsonSpec::no time other than GET's own times and the envelope's creation time; MerchantNotificationFanoutServiceSpec::the envelope time is the fan-out time |
| BR-WH-020 | 공개 스키마는 필드 추가를 같은 `schemaVersion`의 가산 변경으로 하고 의미 변경은 새 버전으로 하며, 수신자는 모르는 키를 무시해야 한다. | 확정 | 기준 | 03 순서·버전·오류("추가적 변경 우선… 의미 변경은 새 버전"), WEBHOOK-001 §4.2.1 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md 머리말 rev 0.3·§4.2.1 | MerchantWebhookBodyJsonSpec::field added, version kept … both bodies declare schemaVersion 1 |
| BR-WH-021 | 가맹점은 판단에 서명된 본문 값을 쓰고 서명되지 않은 헤더(`PayCore-Event-Id`·`-Event-Type`·`-Delivery-Attempt`) 값을 근거로 삼지 않는다(가맹점 안내). | 제안 | 적용 | WEBHOOK-001 rev 0.2 n-7 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0 | 없음 |

## 3. 서명·재전송 방지

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-022 | 모든 Webhook은 (MID, 환경)별 비밀로 HMAC-SHA256(`"{t}.{본문 바이트 그대로}"`) 서명하고 `PayCore-Signature: t=…,kid=…,v1=…` 헤더로 보낸다. | 확정 | 사용자 | D-WH-003, Q-WH-03 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.1·§21.0 | HmacMerchantWebhookSignerSpec::the header is t first, then one kid/v1 pair; MerchantWebhookSignatureGoldenSpec::PG header equals the golden header |
| BR-WH-023 | 키 교체 중에는 활성 키마다(최대 2개) 같은 `t`로 서명 쌍을 하나씩 붙이며, 가맹점은 그중 하나라도 맞으면 통과시킨다. | 확정 | 사용자 | D-WH-003, D-WH-005 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §21.0 | HmacMerchantWebhookSignerSpec::two active keys during rotation; WebhookSignatureCheckSpec::during rotation the second pair may be the one the receiver knows |
| BR-WH-024 | 가맹점 검증 규칙은 상수 시간 비교, `|now − t| ≤ 시각 창`(경계 포함), `eventId` 중복 제거다. | 확정 | 사용자 | D-WH-003 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.1·§21.0 | WebhookSignatureCheckSpec::the window is inclusive at ±300s and refused at ±301s; WebhookReceiverVerificationSpec(Simulator) |
| BR-WH-025 | 재전송 방지 시각 창은 300초다(Demo, 운영값은 LIVE Gate). | Demo값 | 사용자 | D-WH-003 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §21.0 | WebhookSignatureCheckSpec::±300s inclusive, ±301s refused |
| BR-WH-026 | 서명 `t`는 시도마다 새 전송 시각이고, 본문 바이트는 그 이벤트의 모든 시도·재전송에서 같다. | 제안 | 적용 | WEBHOOK-001 WH-08·§6.1 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.0·§6.1 | MerchantWebhookDeliveryDbSpec::T-D2 the resend carries the same event id and the same body bytes; only t and the signature differ |
| BR-WH-027 | HMAC 키는 가맹점에게 보이는 비밀 문자열 전체(`whsec_…`)의 UTF-8 바이트다. | 제안 | 적용 | WEBHOOK-001 WH-19 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §21.0 | MerchantWebhookSignatureGoldenSpec::vector … equals the golden header |
| BR-WH-028 | TEST와 LIVE의 서명 비밀은 다르며, TEST 비밀로 LIVE 이벤트를 서명하는 경로가 없다(LIVE 서명 키 원천이 생기기 전 LIVE는 서명·전송하지 않는다). | 확정 | 기준 | 05-security 15행, D-WH-005 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.1·§6.2 | MerchantWebhookDeliveryServiceSpec::no endpoint (e.g. LIVE) or no signing key is not sent |

## 4. 전달 보장·순서·중복

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-029 | Webhook 전달은 at-least-once이며 exactly-once를 주장하지 않는다. 같은 이벤트는 항상 같은 `eventId`·같은 본문 바이트로 다시 보낸다. | 확정 | 기준 | 03 핵심 규칙·금지사항, WEBHOOK-001 §5.7 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.7 | MerchantWebhookDeliveryDbSpec::T-D2 … merchant de-duplicates by eventId |
| BR-WH-030 | 공개 `eventId`는 가맹점 이벤트용 새 식별자이고 내부 Outbox `event_id`는 노출하지 않는다. | 제안 | 적용 | WEBHOOK-001 WH-05 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.2.1·§16 | MerchantNotificationFanoutServiceSpec::… a new public id; MerchantWebhookDeliverySpec::identifiers have the server prefix |
| BR-WH-031 | 가맹점 스트림은 Outbox의 필터된 스트림이므로 Payment별 연속 `sequence`를 따로 둔다. | 확정 | 기준 | 03 순서·버전·오류("필터된 스트림은 별도 연속 sequence") | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.4·§5.7 | MerchantNotificationFanoutServiceSpec::merchant events get a per-payment contiguous sequence |
| BR-WH-032 | `sequence`는 1부터 그 Payment의 인과 순서대로 붙고, 숨기거나 해당 없는 이벤트는 번호를 쓰지 않는다. 서로 다른 Payment의 sequence는 관계가 없다. | 제안 | 적용 | WEBHOOK-001 WH-04·§5.4 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.4·§5.7 | MerchantNotificationFanoutServiceSpec::suppressed events take no number; MerchantNotificationFanoutDbSpec::C-F1 each payment's sequence is 1, 2, 3 in Outbox id order |
| BR-WH-033 | 전달 순서는 보장하지 않는다(재시도·병렬 전달). 가맹점은 `sequence`로 낡은 이벤트를 구별하거나 GET으로 현재 상태를 확인한다. | 제안 | 적용 | WEBHOOK-001 WH-11·§5.7 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.7 | 없음 |
| BR-WH-034 | 전달 성공은 시간 안의 HTTP 2xx이고 응답 본문은 해석하지 않는다. 그 밖(3xx·4xx·5xx·timeout·연결 실패·목적지 차단)은 모두 실패다. | 제안 | 적용 | WEBHOOK-001 WH-09 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.3 | HttpMerchantWebhookSenderSpec::2xx is success; non-2xx is a failure with its status; 3xx is REDIRECT |
| BR-WH-035 | 늦게 확인된 2xx는 소유를 잃은 시도이거나 포기(GAVE_UP) 뒤라도 전달 완료로 바꾸며, 완료된 전달은 실패로 되돌리지 않는다(성공 단조). | 제안 | 적용 | WEBHOOK-001 rev 0.2 n-1 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.0 | MerchantWebhookDeliverySpec::success is monotonic … a late 2xx after GAVE_UP still delivers; MerchantWebhookDeliveryDbSpec::T-D3 a late 2xx … marks it DELIVERED |
| BR-WH-036 | lease 만료는 이전 실행자가 보내지 않았다는 근거가 아니라 중복 전송 가능성으로만 다룬다. | 확정 | 기준 | 06 핵심 규칙(lease 만료 가정 금지), CLAUDE.md 핵심 규칙 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.5 | MerchantWebhookDeliveryDbSpec::T-D3 a worker loses its lease while its attempt is still in flight |
| BR-WH-037 | 저장된 본문의 해시가 맞지 않으면 그 이벤트를 보내지 않고 무결성 실패로 기록한다. | 제안 | 적용 | WEBHOOK-001 rev 0.2 m-2 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.0 | MerchantWebhookDeliveryServiceSpec::a stored body whose SHA-256 no longer matches is not sent |

## 5. 재시도·포기·재전송

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-038 | 실패한 전달은 지수 backoff + jitter로 시도 수·첫 시도 후 경과 시간 상한까지 재시도하고, 먼저 닿은 상한에서 GAVE_UP이 된다. GAVE_UP은 완료가 아니라 경보 대상이다. | 확정 | 사용자 | D-WH-006, Q-WH-06, 03("DLQ는 완료 상태가 아니다") | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.6·§5.8 | WebhookRetryScheduleSpec::give-up (either cap — D-WH-006); MerchantWebhookDeliveryDbSpec::T-D1 retries and give-up |
| BR-WH-039 | 가맹점의 4xx 응답도 즉시 포기하지 않고 재시도한다. | 확정 | 사용자 | D-WH-006 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.3·§5.6 | MerchantWebhookDeliveryDbSpec::T-D1 … then a 404 and a final 500 give up |
| BR-WH-040 | 전달 실패가 이어져도 endpoint를 자동 비활성화하거나 가맹점을 차단하지 않는다. | 확정 | 사용자 | D-WH-006 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.8 | 없음 |
| BR-WH-041 | 재시도 수치(초기 10초·최대 간격 1시간·10회·24시간·jitter 0.2)는 기본값 없는 설정의 Demo 값이며 운영값은 LIVE Gate에서 정한다. | Demo값 | 사용자 | D-WH-006, D-AUTH-009 방식 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.6 | WebhookRetryScheduleSpec::10s, 20s … then capped at 1h; MerchantWebhookDeliverySettingsSpec::the Demo values bind into the domain schedule unchanged |
| BR-WH-042 | GAVE_UP 전달의 재전송은 운영자 명령으로만 한다(ADMIN 이후). | 확정 | 사용자 | D-WH-006, D-WH-008 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.9 | 없음 |
| BR-WH-043 | 수동 재전송은 새 이벤트가 아니라 같은 `eventId`·같은 본문이며, 대상·사유·권한·결과를 감사로 남긴다. | 제안 | 적용 | WEBHOOK-001 §5.9, 07-operations(재처리 감사) | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.9 | 없음 |
| BR-WH-044 | 가맹점 이벤트·전달·시도 기록(GAVE_UP 포함)은 삭제하지 않는다(보관·파기 정책은 LIVE Gate). | 제안 | 적용 | WEBHOOK-001 §5.8·§5.12, 03 금지사항 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.12 | MerchantWebhookPersistenceSpec::the runtime account cannot change or delete it (insert-only, 1142) |

## 6. 목적지 안전·환경 분리

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-045 | Webhook 목적지는 `https`·포트 443·host 이름만 허용하고 IP 리터럴·userinfo·fragment는 거절한다. | 확정 | 사용자 | D-WH-007, Q-WH-07 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.2 | HttpMerchantWebhookSenderSpec::URL grammar: only https on 443 with a host name |
| BR-WH-046 | 해석된 모든 주소가 공인 unicast일 때만 보내고(loopback·사설·link-local·메타데이터·CGNAT·ULA·mapped 등 특수 대역 차단, 하나라도 차단이면 차단), 검사한 주소로만 연결한다. | 확정 | 사용자 | D-WH-007, 05-security 24행 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.2·§21.0b | WebhookAddressPolicySpec::all must be public — one blocked address blocks the destination; HttpMerchantWebhookSenderSpec::a name resolving to any special-purpose address is blocked |
| BR-WH-047 | redirect는 따르지 않고, TLS 인증서·hostname 검증을 하며, 시스템 프록시 설정을 쓰지 않는다. | 확정 | 사용자 | D-WH-007 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.2·§21.0b | HttpMerchantWebhookSenderSpec::3xx is REDIRECT and is never followed; HttpMerchantWebhookSenderSpec::a certificate for another host name is rejected; ArchitectureSpec::no HttpClient is built with useSystemProperties() |
| BR-WH-048 | 응답 크기(4 KiB)·timeout(연결 3초·응답 10초·전체 15초)은 Demo 값이다. | Demo값 | 사용자 | D-WH-007 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.2·§21.0b | HttpMerchantWebhookSenderSpec::time budgets (m-4) |
| BR-WH-049 | TEST 예외는 값으로 켠 정확한 host:port 허용 목록뿐이며, 그 예외는 LIVE 전달에는 적용하지 않는다. | 확정 | 사용자 | D-WH-007, WEBHOOK-001 rev 0.2 m-6 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.2·§21.0b | HttpMerchantWebhookSenderSpec::the allowlist applies only to TEST deliveries (T-E1) |
| BR-WH-050 | endpoint 등록 경로·운영 비밀 저장소·목적지 정책 적용 전에는 LIVE Webhook을 보내지 않는다(LIVE 이벤트는 기록만, 전달 없음 — fail-closed). | 확정 | 사용자 | D-WH-008, D-WH-004 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.1·§20.1-5 | MerchantWebhookPersistenceSpec::a LIVE payment … can only be NO_ENDPOINT; MerchantNotificationFanoutServiceSpec::LIVE has none (fail-closed, NO_ENDPOINT) |

## 7. endpoint·서명 비밀 관리

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-051 | Webhook endpoint 원본은 MASTER가 갖고 (MID, 환경)당 하나(URL·상태·버전)이며, 운영자가 ADMIN에서 등록하고 기존 서명 Snapshot 채널로 PAYMENT에 전파한다. | 확정 | 사용자 | D-WH-004, Q-WH-04 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.1 (WEBHOOK-ENDPOINT-001 명세 없음) | 없음 |
| BR-WH-052 | LIVE endpoint 등록·변경은 2인 승인 대상이다. | 확정 | 사용자 | D-WH-004, D-ADMIN-003 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §7.1 (WEBHOOK-ENDPOINT-001 명세 없음) | 없음 |
| BR-WH-053 | ADMIN 등록 경로가 생기기 전에는 TEST Demo 설정 endpoint와 TEST 시험 키만 쓴다. | 확정 | 사용자 | D-WH-004, D-WH-005, D-WH-008 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.2·§7.1 | MerchantWebhookPersistenceSpec::a LIVE delivery row is rejected by the table CHECK |
| BR-WH-054 | 서명 비밀은 PAYMENT가 (MID, 환경)별로 `whsec_` + kid + 256-bit로 발급하고, 비밀 저장소에서 주입한 환경별 관리 키로 봉투 암호화해 보관하며, 원문은 발급 때 한 번만 보여 준다. | 확정 | 사용자 | D-WH-005, Q-WH-05 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.2 (WEBHOOK-ENDPOINT-001 명세 없음) | 없음 |
| BR-WH-055 | (MID, 환경)당 활성 서명 비밀은 교체 중 최대 2개이고, 폐기는 사유·행위자와 함께 append-only로 기록한다. | 확정 | 사용자 | D-WH-005 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.2 | HmacMerchantWebhookSignerSpec::no key, more than two keys … is refused (폐기 기록은 없음) |
| BR-WH-056 | LIVE 서명 비밀 발급은 운영 키 저장소 구성 뒤에만 한다. | 확정 | 사용자 | D-WH-005, LIVE 준비 Gate | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §6.2 | 없음 |
| BR-WH-057 | endpoint URL·서명 키는 전송 시점에 조회하고 전달 기록에 복사하지 않는다(변경이 다음 시도부터 반영, 비밀 복제 없음). | 제안 | 적용 | WEBHOOK-001 WH-12 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.10 | MerchantWebhookDeliveryServiceSpec::the attempt row records the endpoint ref, the key ids … never the URL or the body |

## 8. 조회와의 관계·금융 영향

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-058 | 결제 현재 상태의 권위는 가맹점 조회(GET)이고 Webhook은 확정 사실의 통지다. Webhook 미수신·포기·sequence 구멍은 GET으로 메운다. | 확정 | 기준 | AC1 §10.2, D-CP-003, 범위 40행 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §8 | MerchantWebhookConsistencySpec::T-V1 every section of every body is a subset of what GET shows |
| BR-WH-059 | Webhook 전달 실패·포기는 Payment·반환 잔액·정산·한도·hold 등 어떤 금융 상태도 바꾸지 않는다. | 확정 | 사용자 | D-WH-006 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §8·§17.9 | MerchantWebhookConsistencySpec::T-V2 GET bytes, the return balance and the Outbox publish state did not change |
| BR-WH-060 | Webhook 경로의 결함은 승인·취소 등 업무 트랜잭션을 실패·rollback시키거나 지연시키지 않는다(업무 TX는 Outbox 기록까지만). | 제안 | 적용 | WEBHOOK-001 WH-02·rev 0.2 H-3 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.2 | ArchitectureSpec::business application services do not depend on merchant webhook ports; MerchantNotificationFanoutDbSpec::C-F2 … commit without waiting |
| BR-WH-061 | GET에서 숨기는 사실은 Webhook에도 없다(조회와 통지는 같은 사실 범위). | 제안 | 적용 | WEBHOOK-001 §4.4, D-CPQ-002 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §4.4 | MerchantNotificationFanoutDbSpec::T-F3 …; MerchantWebhookConsistencySpec::T-V1 |
| BR-WH-062 | 가맹점 통지 처리 표시와 FINANCIAL 전달 표시는 독립이며, 가맹점 2xx는 FINANCIAL 전달 완료로 보지 않는다. | 제안 | 적용 | WEBHOOK-001 F-1·WH-03, 03("단순 HTTP 성공은 대체가 아니다") | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §3.2 F-1·§5.3 | MerchantNotificationFanoutDbSpec::T-F6 publish_status stays PENDING |

## 9. Outbox 기록·불변(이벤트 생산 공통)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-063 | 업무 효과·요약·요청 결과와 그 Outbox 이벤트는 소유 DB의 같은 로컬 TX로 커밋하며, 커밋 후 메모리 이벤트만 발행하는 이중 쓰기를 하지 않는다(rollback이면 이벤트도 없다). | 확정 | 기준 | CLAUDE.md 핵심 규칙, 03 핵심 규칙·금지사항, 10 INV-04 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.2 | CreatePaymentServiceSpec::… one PaymentRequested event are committed together; RequestedPaymentExpiryServiceSpec::a failure after PaymentExpired was flushed (zero events on rollback) |
| BR-WH-064 | 외부 호출(가맹점 Webhook·내부 HTTP push)은 DB TX 밖에서 하고 호출 동안 DB 잠금을 잡지 않는다. | 확정 | 기준 | CLAUDE.md 핵심 규칙, TECHNOLOGY-AND-PRINCIPLES §3 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.2 | MerchantWebhookDeliveryServiceSpec::claim → send outside the TX → result |
| BR-WH-065 | 결제 생성은 `PaymentRequested` v1, REQUESTED 만료는 `PaymentExpired`(비금융 통합 이벤트, 결제별 효과 키 하나) 통합 이벤트를 같은 TX로 남기며, 금융 효과 이벤트와 이름·의미를 구분한다. | 확정 | 사용자 | D-CPC-003, D-AUTHN-008 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §3.1 | CreatePaymentServiceSpec::one PaymentRequested event …; RequestedPaymentExpiryServiceSpec::exactly one PaymentExpired |
| BR-WH-066 | 이벤트는 Domain Entity 직렬화가 아니라 명시적 통합 계약이며 외부기관 DTO·Secret·카드정보·불필요한 개인정보(예: `PaymentExpired`의 paymentKey)를 담지 않는다. | 확정 | 기준 | 03 핵심 규칙, D-AUTHN-008 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §3.2 F-3 | 없음 |
| BR-WH-067 | Outbox 이벤트의 내용(payload 등)은 기록 뒤 바뀌지 않고 삭제되지 않는다. runtime이 바꿀 수 있는 것은 전달 표시 열(가맹점 통지 2열·FINANCIAL 전달 2열)뿐이다. | 확정 | 사용자 | D-WH-009, D-FFR-001, Q-WH-09, Q-FFR-01 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §5 | DatabaseAccountSeparationSpec::D-WH-009 + D-FFR-001: on outbox_event only the two merchant notification columns and the two FINANCIAL relay marks are updatable; MerchantNotificationFanoutDbSpec::T-F6 every other column is byte-identical |
| BR-WH-068 | runtime 계정은 `outbox_event`의 `publish_status` 수정을 DB에서 거부당한다(가맹점 통지 2열만 수정 가능). | 대체됨→BR-WH-067 | 사용자 | D-WH-009 원문 → D-FFR-001 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.0·§21.4 | 없음(현행 시험은 BR-WH-067 기준 — 아래 진단 (a)) |
| BR-WH-069 | Outbox/Inbox 중복 제거 기록·Job 실행 기록은 소비 완료·보존기간 확인 없이 또는 장애 해결 목적으로 삭제하지 않는다. | 확정 | 기준 | 03 금지사항, 06 금지사항 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §5.12 | DatabaseAccountSeparationSpec::no table can be deleted from … and outbox events cannot be updated |
| BR-WH-070 | 소비자 장애 중에도 업무(승인)는 계속하고 Outbox 적체를 보존·재전달하며, 적체량·최고 연령을 관측한다(경보 임계값은 운영값). | 확정 | 기준 | 06 핵심 규칙·장애표 | docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md §11 | MerchantWebhookConsistencySpec::the GAVE_UP gauge shows the given-up deliveries; FinancialFactRelayDbSpec::FR-R8 … after draining, nothing FINANCIAL_FACT is left undiscovered |

## 10. 내부 이벤트 전달(PAYMENT→FINANCIAL·MASTER→PAYMENT — 전달 의미만)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-WH-071 | PAYMENT의 금융 사실은 PAYMENT Outbox → 서명된 내부 HTTP push → FINANCIAL Inbox로만 전달하고 cross-DB SQL을 쓰지 않는다. | 확정 | 사용자 | D-ACQ-001, Q-ACQ-01 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §1·§4.1 | FinancialFactRelayDbSpec::FR-R1 both facts arrive once each, signed |
| BR-WH-072 | 전송 완료는 수신 측의 내구 저장 확인(ACK) 뒤에만 표시하고, 보낸 `eventId`·효과 키를 되돌려 주지 않는 ACK는 완료로 보지 않는다. 응답 유실 시 같은 `eventId`로 다시 보낸다. | 확정 | 기준 | 03 핵심 규칙 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §4.1·§6 | FinancialFactRelayDbSpec::FR-R3 PAYMENT does not mark it delivered …; FinancialFactRelayDbSpec::FR-R5 an ACK that does not echo the sent eventId is not trusted |
| BR-WH-073 | 수신 측은 같은 `eventId` 중복과 새 `eventId`로 온 같은 업무 효과(효과 키)를 각각 막고, Inbox 기록과 업무 반영을 같은 로컬 TX로 커밋한다(전송 중복은 허용, 효과 중복은 금지). | 확정 | 사용자 | D-ACQ-001, 03, 10 INV-05·INV-06, CLAUDE.md 핵심 규칙 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §4.2 | PaymentFactInboxDbSpec::FR-I1 re-delivery answers DUPLICATE; PaymentFactInboxDbSpec::FR-I3 business effect recorded exactly once; PaymentFactInboxDbSpec::FR-I9 whole TX rolls back; EntitlementInboxSpec::the same eventId sent 8 times at once is APPLIED once |
| BR-WH-074 | 같은 `eventId`에 다른 본문이 오면 충돌로 거절하고 아무것도 저장하지 않는다. | 확정 | 기준 | CLAUDE.md 핵심 규칙(같은 키 다른 내용 충돌) | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §4.2·§6 | PaymentFactInboxDbSpec::FR-I2 EventIdConflict and nothing … is stored; EntitlementInboxSpec::another body under that eventId is 409 |
| BR-WH-075 | FINANCIAL은 모르는 schema 버전의 사실을 UNSUPPORTED로 원문 보존한 뒤 ACK하고 투영하지 않는다(재처리 도구는 후속). | 확정 | 사용자 | D-FFR-004, Q-FFR-04 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §2·§6 | PaymentFactInboxDbSpec::FR-I6 kept as UNSUPPORTED |
| BR-WH-076 | 수신 측이 계약 코드로 내용을 거절한 사실은 격리(QUARANTINED)하며, 격리는 완료가 아니고 자동 재전송하지 않는다(소유자 경보). | 확정 | 기준 | 03 순서·버전·오류("계약 오류는 격리 큐… DLQ는 완료 상태가 아니다") | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §3·§6 | FinancialFactRelayDbSpec::FR-R4 QUARANTINED (not delivered, not resent even later) |
| BR-WH-077 | FINANCIAL 사실 전달은 포기 없이(상한 없이) 재시도하고, 계약 코드가 없는 4xx·401·5xx·연결 실패도 재시도한다. | 제안 | 적용 | FIN-FACT-RELAY-001 I-2(Implementer 해석) | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §2 I-2·§4.4 | FinancialFactRelayDbSpec::FR-R2 … next attempt = now + 1s backoff; FinancialFactRelayContractSpec::m-1: 400·409·422 are quarantined only with the matching FINANCIAL contract error code (golden statusCases) |
| BR-WH-078 | 같은 Payment의 사실이 순서가 뒤바뀌어 도착해도(원승인보다 취소 먼저) 버리지 않고 보류로 반영하며, 시간순 정렬만으로 순서를 확정하지 않는다(보류 판정·해제는 FIN 대장). | 확정 | 사용자 | D-ACQ-002, 03 순서·버전·오류 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §6 | PaymentFactInboxDbSpec::FR-I4 the early cancel is projected and HELD |
| BR-WH-079 | 내부 채널은 환경별 키 HMAC-SHA256 봉투 서명으로 인증하고(교체용 2키 동시 허용, 불일치·키 없음은 거절·저장 없음), 운영은 mTLS를 더한다(LIVE 전). | 확정 | 사용자 | D-MS-004, D-ACQ-001(패턴 재사용) | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §4.1·§9.1 L-2 | EntitlementInboxSpec::missing, malformed, unknown-key or wrong signatures are 401 and nothing is stored; the rotation key is accepted; PaymentFactInboxHttpSpec(FR-H3) |
| BR-WH-080 | 격리 해제·이벤트 재생으로 승인·지급 같은 금융 실행을 새로 일으키지 않는다. | 확정 | 기준 | 03 금지사항 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §9.1 L-5 | 없음 |

## 진단

### (a) 대체 사슬

1. **D-WH-009 → D-FFR-001**: D-WH-009는 "publish_status·payload 등 나머지 열 수정은 DB가 계속 거부(1143)"라고 했으나 D-FFR-001이 publish_status·published_at 두 열 UPDATE를 추가했다 → BR-WH-068 `대체됨→BR-WH-067`. `docs/DECISIONS.md` 150행 D-WH-009에는 대체 표시가 없다.
2. **FIN-FACT-RELAY-001 I-1 → D-FFR-001**: 해석 I-1(Outbox 행·열을 바꾸지 않고 별도 표로 전달 상태 관리)은 rev 0.2에서 D-FFR-001로 대체됐다(명세 §2에는 표시됨). 대장 행은 없다(사용자 규칙이 아니었다).
3. **WEBHOOK-001 rev 0.1 → rev 0.2 → rev 0.3**: `HELD_DECISION`(Q-WH-01 답 전 보류) 폐기(D-WH-001), 봉투 `createdAt` 의미가 "업무 시각(occurred_at)"에서 "fan-out 시각"으로 바뀜(m-1), `data` 묶음 → 섹션별 GET 부분집합(§4.2.0), fan-out "회차 전체 정지" → 행 단위 FANOUT_FAILED(H-3), 구현 결정 WH-10 변경, rev 0.3 `merchantCode` 가산. 모두 명세 안에서 "rev 0.2가 우선"으로 처리돼 있고 rev 0.1 본문이 남아 있다(→ (c)).

### (b) 결정 간 충돌

1. **순서 보장**: baseline 03 47행 "Payment 단위 전달 순서를 유지한다"와 WEBHOOK-001 WH-11(가맹점 Webhook 순서 비보장, BR-WH-033), FIN-FACT-RELAY-001 L-4(앞 사실 실패가 뒤 사실을 막지 않음)가 맞지 않는다. 03은 같은 줄에서 "필터된 스트림은 sequence"를 허용하지만 "전달 순서 유지"는 별도 문장이다. WH-11은 구현 결정(가역)으로 처리됐고 명세 스스로 "Coordinator가 공개 계약 의미로 보면 질문"이라 적었다 — 질문 후보 WH-Q1.
2. **채널마다 다른 4xx 처리**: MASTER→PAYMENT 권리 relay는 모든 4xx 격리, PAYMENT→FINANCIAL은 계약 코드 붙은 400·409·422만 격리, 가맹점 Webhook은 4xx도 재시도(D-WH-006). 가맹점 쪽은 사용자 결정이고 FINANCIAL 쪽은 Implementer 해석(I-2)이다. 모순은 아니나 D-ACQ-001 "MASTER→PAYMENT 패턴 재사용"과 다르게 동작한다 — BR-WH-077 확인 필요.
3. **재시도 상한**: 가맹점 Webhook은 유한 재시도·GAVE_UP(D-WH-006), FINANCIAL 사실은 상한 없음(I-2). 의도된 차이(통지 vs 금융 사실)지만 FINANCIAL 쪽은 사용자 결정이 없다.
4. 사용자 결정끼리 정면 충돌은 찾지 못했다. D-WH-001(운영자 취소 통지)과 D-CPQ-002 (i)·(ii)는 일치한다.

### (c) 명세 본문이 현재 결정과 다른 곳

1. WEBHOOK-001 §0 요약(87~99행): Q-WH-01~07을 아직 미결로 적음("통지 여부는 Q-WH-01", "형태는 Q-WH-06", "분할을 제안"). 모두 D-WH-001~008로 답변됨.
2. WEBHOOK-001 §4.4 표(330~331행): AUTHORIZED 운영자 보정 취소·hold 행의 표시가 `미결` — D-WH-001·002로 확정.
3. WEBHOOK-001 §4.2.1(299행): `createdAt` = 원 업무 사실 시각(Outbox occurred_at) — rev 0.2 m-1은 fan-out 시각(코드도 fan-out 시각). `data` 안 `failedAt`·`expiredAt`(308~311행)도 rev 0.2에서 제외.
4. WEBHOOK-001 §5.1(391행)·§5.3(417행)·§11(614행): `HELD_DECISION` 상태·지표가 남아 있음 — rev 0.2에서 폐기(코드 `MerchantNotificationFanoutStore.kt:37`도 "HELD_DECISION 없음").
5. WEBHOOK-001 §5.3(442행): "outbox_event UPDATE 추가(표 단위 가능)" — D-WH-009는 열 단위 GRANT 두 열.
6. WEBHOOK-001 §7.2(557행) "응답 본문 최대 N바이트만 읽고 버림"·D-WH-007 "응답 크기(Demo 4KiB) 제한" — 구현은 상태 줄로 판정 뒤 본문을 읽지 않고 abort하며 `max-response-bytes` 설정은 쓰이지 않는다(§21.0b 최종 검토 n-1에 기록). 더 엄격한 쪽이지만 결정 문구와 다르다.
7. WEBHOOK-001 §4.1(233행) `PaymentAuthorizationFailed` → `payment.failed` 조건 "항상" — 같은 표 240행(rev 0.2)과 코드는 Payment FAILED일 때만 통지, 아니면 FANOUT_FAILED.
8. WEBHOOK-001 §1.2(127행): FINANCIAL relay 소유를 "ACQUIRING-001·SETTLEMENT·RECON 계열"로 적음 — 실제로는 FIN-FACT-RELAY-001(DONE)이 구현.
9. `docs/DECISIONS.md` D-WH-009(150행) 본문이 D-FFR-001 이후에도 "publish_status 수정 거부"로 남음(대체 표시 없음).
10. baseline 03 구현 체크리스트 "격리 큐 조회·수정 후 재처리·감사를 제공한다" — 현재 어느 채널에도 없음(FIN L-5, WEBHOOK-ENDPOINT-001 BACKLOG). 기준 문서가 요구하는 것을 명세가 후속으로 미룬 상태.

### (d) 시험 없는 확정 규칙

BR-WH-040(endpoint 자동 비활성 없음), BR-WH-042(운영자 명령 재전송 — 미구현), BR-WH-051·052·054·056(endpoint MASTER 원본·LIVE 2인 승인·비밀 발급·LIVE 키 — WEBHOOK-ENDPOINT-001 BACKLOG, 명세 없음), BR-WH-055의 폐기 append-only 부분, BR-WH-066(통합 이벤트 비밀·개인정보 미포함 — 웹훅 본문 외 Outbox payload 전체 검사는 없음), BR-WH-080(재생으로 금융 실행 금지), BR-WH-017은 키 집합 고정 시험으로만 간접 확인. `제안` 중 시험 없음: BR-WH-009(gen2 승인 통지), BR-WH-021, BR-WH-033, BR-WH-043.

### (e) 코드가 규칙과 다른 곳

1. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/integration/MerchantWebhookTransportSettings.kt:14,23` — `maxResponseBytes`가 검증만 되고 쓰이지 않는다(D-WH-007 "응답 크기 제한" 문구와 다름, 실제로는 본문을 읽지 않아 더 엄격). 규칙 위반은 아니나 설정이 뜻 없는 값으로 남음.
2. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/MerchantWebhookDelivery.kt:76-84` — GAVE_UP 뒤 늦은 2xx를 DELIVERED로 바꾼다(BR-WH-035). D-WH-006은 GAVE_UP을 "완료 아님·경보"로만 정했고 이 전이는 사용자 결정에 없다(경보가 조용히 사라질 수 있음) — 위반 판정 아님, 확인 필요.
3. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/integration/MerchantWebhookSettings.kt:27` — TEST 설정 endpoint는 https만 허용하는데 전송 adapter(`HttpMerchantWebhookSender.kt:79-83`)는 TEST allowlist 목적지에 http를 허용한다. 규칙(BR-WH-049)보다 설정이 엄격 — 위반 아님, 두 층의 기준이 다름.
4. 규칙과 반대로 동작하는 코드는 찾지 못했다. 확정 규칙 중 BR-WH-051~056은 코드가 없다(미구현이지 다름 아님 — LIVE는 `ConfiguredMerchantWebhookEndpointDirectory.kt:15`·`ConfiguredMerchantWebhookSigningKeys.kt:15`에서 fail-closed).

### (f) 미결·질문 후보

1. **WH-Q1 (순서 보장 공개 계약)**: 가맹점 Webhook을 순서 비보장 + sequence + GET으로 확정할지(권장, 현 구현) vs Payment 단위 순서 보장(앞 이벤트 전달 전 뒤 이벤트 보류). baseline 03 47행과의 정합. 영향: BR-WH-033, 가맹점 계약 문서. 막히는 작업: 없음(현 구현 유지 가능) — 대장 확정만.
2. **WH-Q2 (GAVE_UP 뒤 늦은 성공)**: 포기 뒤 늦게 확인된 2xx를 DELIVERED로 바꿀지(현 구현) vs GAVE_UP 유지 + 성공 시도 기록만. 영향: BR-WH-035, GAVE_UP 경보.
3. **WH-Q3 (FINANCIAL 사실 재시도 상한·4xx 분류)**: I-2(상한 없음, 계약 코드 없는 4xx·401 재시도)를 사용자 규칙으로 채택할지. 금융 사실 유실 방지와 적체 사이 선택. 영향: BR-WH-077.
4. **Coordinator 적용 규칙 일괄 확인**: BR-WH-002·003·004·008·010·011·013·016·018·019·021·026·027·030·032·034·037·043·044·057·060·061·062 — WEBHOOK-001 도출·구현 결정에서 온 가맹점 체감 계약. 한 번에 "현행 유지" 확인 요청 후보.
5. **D-WH-002 재결정 조건**: "RECON hold 해제 Use Case와 함께 재결정" — RECON-CONTRACT-001은 BACKLOG. 그 작업 착수 시 hold 통지(BR-WH-007)를 다시 묻는다.
6. **WEBHOOK-ENDPOINT-001 착수 가능**: dependsOn(ADMIN-BOOT-001·ADMIN-PAYMENT-OPS-001·WEBHOOK-001) 모두 DONE인데 BACKLOG·명세 없음. 운영자 보정 취소 진입점(ADMIN-PAYMENT-OPS-001)이 생겨 BR-WH-005 경로는 이미 실제 발생 가능. MASTER-CREDENTIAL-001과의 병합 여부(openItems)도 미정.
7. **수동 재전송 세부(BR-WH-043)**: 재전송 시 시도 수·첫 시도 시각 재설정 여부, 2인 승인 필요 여부, 대상 범위(단건·기간) — WEBHOOK-ENDPOINT-001 명세에서 질문.
8. **격리·UNSUPPORTED 재처리 도구**: baseline 03 체크리스트 요구, 내부 채널 모두 없음. 운영자 승인 절차 필요(FIN-FACT-RELAY L-5).
9. **보관·파기**: Webhook 이벤트·전달·시도 기록, Outbox/Inbox 보존기간(BR-WH-044·069)은 LIVE Gate 별도 정책 — 미정.
10. **대장 경계**: BR-WH-065(이벤트 생산)·BR-WH-071~080(내부 전달)은 PAY·FIN 대장과 겹칠 수 있다. 종합 단계에서 한 곳만 정본으로 남기고 다른 쪽은 ID 참조로 바꿀 것.
