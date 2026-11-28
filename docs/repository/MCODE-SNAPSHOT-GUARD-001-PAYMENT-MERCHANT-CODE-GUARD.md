# MCODE-SNAPSHOT-GUARD-001 PAYMENT 가맹점 코드 불변 방어 명세 (초안 rev 0.2)

- rev 0.1(2026-11-29): 첫 초안, 질문 Q-MSG-1~3.
- **rev 0.2(2026-11-29)**: 1차 리뷰 CHANGES_REQUIRED 반영(§9 대응표) — MAJOR-1 같은 revision 코드 단독 변경(F4) 사실·식별·질문 재구성(Q-MSG-3에 통합, Q-MSG-2 전제 정정), MAJOR-2 ACK 추가 필드 `merchantCodeHeld`(Coordinator 결정), MAJOR-3 Hold gauge 필수·Hold 목록 Reader·응답 유실 시험, MINOR-1~6, NIT. 문서만 — 소스·시험·대장·DECISIONS·questions·tasks 변경 없음, 커밋 없음. 자기 완료 판정 아님.
- 기준 revision: `main` `2437e78`. 작업 트리에는 **이 작업과 무관한 미커밋 변경**이 있다(`apps/master-api/…/merchant/domain/Business*.kt`·`MasterRejection.kt`·`ApprovedBusinessChange.kt` 등 BIZ-CRIT-APPROVAL-001 구현 진행분, `harness/questions.json`·`tasks.json`). 이 문서의 file:line은 그 파일들을 인용하지 않으며, 인용한 PAYMENT·MASTER 파일은 HEAD와 작업 트리가 같다.
- 근거 결정: **D-GOV-019 ③**(Q-MCODE-2 사용자 답 '기존 코드 유지 + Hold', 권장안과 다름), **D-GOV-011 ②**(가맹점 코드 영구 잠금), D-MCODE-001·002(출력 전용, BR-BIZ-071·BR-WH-015·016), D-GOV-001 ④(PAYMENT 사고 보류의 FINANCIAL 전파). 근거 보고서: `docs/governance/GOV-MCODE-001-MERCHANT-CODE-IMMUTABILITY.md` V-13·C-05·F4·§7 Q-MCODE-2.
- 표기: P/ = `apps/payment-api/src/main/kotlin/com/paycore/payment/`, PT/ = `apps/payment-api/src/test/kotlin/com/paycore/payment/`, PSQL/ = `apps/payment-api/src/main/resources/db/`, M/ = `apps/master-api/src/main/`. **[사실]** 코드·문서로 확인, **[결정]** 사용자 결정, **[해석]** Coordinator 해석(대장에서는 '제안'), **[구현 관례]** 가역 세부, **[질문]** §7.

---

## 1. 정책 문장(D-GOV-019 ③ 원문을 번호로 나눔)

| # | 정책 [결정] |
|---|---|
| P-1 | 가맹점 코드 불변성은 MASTER뿐 아니라 **PAYMENT에서도** 보장한다. |
| P-2 | MASTER는 기준정보의 원천으로 유지한다(PAYMENT가 원천이 되지 않는다). |
| P-3 | 같은 MID(= 그 MID가 속한 가맹점)에 PAYMENT가 이미 가진 코드와 수신 Snapshot의 코드가 다르면 **변경을 차단하고 PAYMENT의 기존 코드를 유지**한다. |
| P-4 | 웹훅·결제 응답 등 외부 연동은 **기존 코드**를 쓴다. |
| P-5 | 기준정보 불일치 **경보**를 내고 **Hold**를 등록한다. |
| P-6 | 코드 불일치만으로 정상 결제·취소를 **일괄 중단하지 않는다**. |
| P-7 | 같은 Snapshot의 다른 기준정보는 코드와 **독립성·정합성이 보장될 때만** 반영한다. |
| P-8 | 오류 Snapshot을 **정상 반영 완료로 처리하지 않는다**. |
| P-9 | 해소 = MASTER에서 원인 확인·정정 → 정상 Snapshot 재수신·정합 검증 → **기존 Hold 해제 정책**. |
| P-10 | 운영자가 PAYMENT의 코드를 **직접 수정하지 못한다**. |
| P-11 | Snapshot 중복·역순 수신을 방어하고, 반복 수신 때 Hold·경보를 **중복 생성하지 않는다**. |
| P-12 | 기존 Hold·동기화 구조를 활용하고, 불필요한 상태·테이블·관리 기능을 만들지 않는다. |
| P-13 | 영향 범위와 시험 시나리오를 작성한다(§3.10·§4). |

함께 유효한 확정 규칙(재질문 대상 아님): BR-BIZ-067(최초 계약 승인 뒤 영구 잠금), BR-BIZ-068(은퇴 코드 영구 예약 — 같은 가맹점 되돌리기 포함), BR-BIZ-071·BR-WH-015(출력 전용, 식별·인증은 MID·API 키), BR-WH-016(코드 없는 MID는 키 생략), MASTER-SNAPSHOT-001 R2(과거 허용으로 차단을 되돌리지 않음)·R3(lease 만료 = fail-closed)·§3.5(같은 revision·다른 hash = INTEGRITY_CONFLICT — 이 규칙과 P-6의 충돌은 §3.5·Q-MSG-3).

---

## 2. 현재 구현 사실

### 2.1 Snapshot 수신·적용 TX [사실]

| 단계 | 위치 | 내용 |
|---|---|---|
| 수신 endpoint | P/`entitlement/adapter/web/EntitlementInboxController.kt:55-70` | HMAC 검증·parse 뒤 use case 호출. 200 응답 = `{consumer, eventId, outcome, appliedRevision[, originalOutcome]}` — `outcome`은 `InboxOutcome.name` 그대로(`:65-67`) |
| TX 경계 | P/`entitlement/application/EntitlementInboxService.kt:43-54` | PAYMENT 로컬 TX 하나(READ COMMITTED), 교착·낙관 충돌·UNIQUE 충돌은 새 TX로 최대 3회 재시도(`:121-128`). commit 단계 실패는 `EntitlementCommitOutcomeUnknownException` → 수신 실패 응답(P/`entitlement/application/EntitlementLocalTransactions.kt:65-66` — MASTER 재전송으로 DUPLICATE/APPLIED에 수렴) |
| 잠금 | `EntitlementInboxService.kt:96`, P/`entitlement/adapter/persistence/JpaEntitlementProjectionStore.kt:44-49` | head IODKU(없으면 revision 0) → `refresh(PESSIMISTIC_WRITE)` = `FOR UPDATE`. 같은 Merchant의 수신은 이 잠금으로 직렬화된다 |
| 중복 eventId | `EntitlementInboxService.kt:97-103` | 같은 원문 SHA-256 → `DUPLICATE`(저장 outcome을 `originalOutcome`으로, head는 그대로). 다른 원문 → `EventIdConflict`(409) |
| 판정 | `EntitlementInboxService.kt:104`, P/`entitlement/domain/SnapshotApplicationRule.kt:31-64` | 순수 Domain 판정(§2.4 표) |
| 기록 | `EntitlementInboxService.kt:106-117` | inbox INSERT(append-only, outcome·`head_revision_after`·봉투 원문 JSON) → APPLIED면 revision별 내용 INSERT(`entitlement_snapshot`, append-only) + MID 소속 기록 → head UPDATE. 모두 같은 TX |
| 커밋 뒤 | `EntitlementInboxService.kt:55-89` | 지표·로그는 커밋된 결과에만. **commit 결과 불명확이면 지표·로그를 남기지 않고**, 재전송은 DUPLICATE라 그때도 남기지 않는다 → 카운터만으로는 경보가 유실될 수 있다(§3.9) |

### 2.2 코드 변경 감지·경보 [사실]

- 감지: `SnapshotApplicationRule.kt:52` — APPLIED 경로에서 `head.merchantCode != null && head.merchantCode != content.merchantCode`면 `merchantCodeChanged = true`. 다른 코드로 바뀜(REPLACED)과 사라짐(REMOVED — v4 head 뒤 더 높은 revision의 v3)을 함께 센다(`:5-8` 주석, 리뷰 MINOR-1).
- **현재 동작은 적용**이다: 새 head에 `content.merchantCode`를 그대로 넣는다(`SnapshotApplicationRule.kt:49`). 웹훅·결제 응답에 새 코드(또는 코드 없음)가 나간다.
- 경보: 커밋 뒤 `metrics.merchantCodeChangedWithMid()` + ERROR 로그(코드 값 없음)(`EntitlementInboxService.kt:79-87`), 카운터 `merchant_code_changed_with_mid`(P/`entitlement/adapter/integration/MicrometerEntitlementMetrics.kt:30-32`, label 없음).
- 현행 동작을 고정한 시험: PT/`entitlement/domain/EntitlementContentV4Spec.kt:90-118`, PT/`entitlement/SchemaRolloutOrderV4Spec.kt:181-196`. **이 기대값은 D-GOV-019 ③로 바뀐다**(§4.4 — 정책 변경 반영이지 완화가 아니다).

### 2.3 외부 노출 경로 — 전부 head의 코드를 읽는다 [사실]

| 경로 | 위치 | 코드 출처 |
|---|---|---|
| 조회 Port | P/`entitlement/application/MerchantCodeService.kt:20-22` | MID 소속 → head 비잠금 읽기 → `head.merchantCode` |
| 결제 생성 응답·멱등 저장 | P/`payment/application/CreatePaymentService.kt:225-227`(결과와 같은 TX에 `result_merchant_code` 저장, 재생은 저장값만) | 같은 Port |
| 결제 조회 응답 | P/`payment/application/PaymentStatusQueryService.kt:47`, P/`payment/adapter/web/GetPaymentController.kt:100` | 같은 Port |
| 웹훅 | P/`payment/application/MerchantNotificationFanoutService.kt:131`(이벤트 생성 때 코드를 넣어 저장, 재전송은 저장 바이트) | 같은 Port |
| 판정 | P/`entitlement/domain/EntitlementEvaluator.kt:62-71` | 코드를 **읽지 않는다**(`EntitlementHead.kt:11-12` "판정 입력 아님"). head 막힘이면 U2 Unavailable(`:63-65`), lease 한도 지나면 U3(`:66-69`), 그 밖에는 head revision·hash와 같은 revision의 저장 내용만 본다(`:71`) |
| 취소 | P/`payment/application/Cancellation*Service.kt` | 권리 투영을 쓰지 않는다(`lockHeadForShare`·`EntitlementEvaluation` 호출은 CreatePayment·Checkout·승인 terms·Site 경로뿐) |

→ **head의 `merchant_code` 열 하나만 지키면 P-4가 모든 외부 경로에서 성립한다.** FINANCIAL·ADMIN은 PAYMENT의 코드를 받지 않는다(grep 결과 없음).

### 2.4 revision 순서·중복 방어 [사실]

`SnapshotApplicationRule.kt:17-30`:

| 수신 | 결과 |
|---|---|
| 지원 버전, revision > `highestSeen`(= max(revision, blockedRevision)) | APPLIED(내용 교체·lease·막힘 해제) |
| revision == head.revision(막힘 없음)·같은 hash | lease가 더 길면 LEASE_EXTENDED(`sourceEventId`도 새 eventId로, `:58-59`), 아니면 STALE |
| revision == head.revision(막힘 없음)·다른 hash | INTEGRITY_CONFLICT(head 막음, `blockedRevision = revision`, `:55-57`) |
| 그 밖(더 작은 revision = 역순 도착, 막힌 head의 같은 revision 등) | STALE(head 불변 — R2) |
| 미지원 버전 | UNSUPPORTED(더 크면 VERSION_UNSUPPORTED로 막음) |
| 같은 eventId 재전송 | DUPLICATE(application, §2.1) |

MASTER의 lease 갱신은 **같은 revision·`leaseSeq + 1`·새 eventId**로 주기 재발행한다(`docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md` §3.3 116행, effect_key 208행; Demo 60초/잔여 30초 — 334-335행). 갱신은 언제나 **현재 MASTER 상태로 다시 조립**한다(M/`kotlin/com/paycore/master/merchant/application/EntitlementSnapshotPublisher.kt:46-57`). → 같은 오류 Snapshot이 lease 주기마다 다시 온다(P-11 반복 수신의 주된 모양).

### 2.5 같은 revision에서 코드만 바뀐 경우(F4) — 현행 동작 [사실]

GOV-MCODE-001 F4(DB 직접 INSERT·결함 배포로 승인 뒤 배정 seq 2가 들어감)처럼 **revision을 올리지 않고** MASTER의 현재 코드가 바뀌면:

| 순서 | 동작 | 근거 |
|---|---|---|
| 1 | 다음 lease 갱신이 현재 상태(새 코드)로 조립 → 같은 revision인데 hash가 직전 발행과 다름 → MASTER ERROR 로그 + `revisionHashDrift` 지표, **그대로 발행** | `EntitlementSnapshotPublisher.kt:50-56`, M/`kotlin/com/paycore/master/merchant/adapter/integration/MicrometerMasterOperationalMetrics.kt:30` |
| 2 | PAYMENT: 같은 revision·다른 hash → `INTEGRITY_CONFLICT`, head 막힘(`blockedRevision = N`) → 그 가맹점 **새 결제 생성 즉시 Unavailable(503)** | `SnapshotApplicationRule.kt:55-57`, `EntitlementEvaluator.kt:63-65` |
| 3 | 이후 같은 revision 갱신: MASTER는 최근 발행 hash(이미 drift된 값)와 같으므로 drift 지표를 더 올리지 않는다. PAYMENT는 막힌 head라 `STALE`, **lease 미연장** | `EntitlementSnapshotPublisher.kt:50`(`latest.contentHash` 비교), `SnapshotApplicationRule.kt:54,62` |
| 4 | 풀림: MASTER가 더 큰 revision을 발행할 때뿐(그 가맹점의 다른 변경 명령이 있어야 함). 그 revision도 새 코드를 담으면 지금은 APPLIED로 코드가 바뀐다(§2.2) | `SnapshotApplicationRule.kt:46` |

즉 현행 확정 규칙(§3.5 같은 revision·다른 hash → fail-closed)은 **코드만 바뀌어도 그 가맹점의 새 결제 생성을 장기 차단**한다. 이것은 P-6("코드 불일치만으로 일괄 중단 금지")과 충돌한다 → §3.5·Q-MSG-3. 취소·조회·웹훅은 영향이 없다(§2.3).

### 2.6 Hold 종류와 생명주기 [사실]

| 구조 | 단위 | 효과 | 생성 | 해제 | 이 작업에 쓸 수 있나 |
|---|---|---|---|---|---|
| `incident_hold`(PSQL/`migration/payment/V1__payment_core.sql:80-94`, V11:7-29, V17:37-39) | **주문·Payment**(FK `order_payment_control`, UNIQUE (OrderKey, payment_id, reason, occurrence)) | 그 주문의 새 결제·처리 차단(`INCIDENT_HOLD`), **사고 사실을 FINANCIAL로 전달해 매입 후보·정산 보류**(D-GOV-001 ④) | 늦은 성공·상충 기관 결과·취소 결과 상충 | `incident_hold_release`(V9:216-232): 2인 + 재대사 MATCH 참조, hold당 1회, 재발생은 occurrence + 1 | **불가** — ① 가맹점 단위 사건을 담을 주문 키가 없다(FK·NOT NULL·reason CHECK 변경 필요) ② 해제 조건(기관 재대사 MATCH)이 이 사건과 맞지 않는다 ③ D-GOV-001 ④ 전파로 그 가맹점 정상 거래의 매입·정산이 보류된다 — 코드는 금융 사실이 아니므로 P-6과 충돌 |
| head 막힘 `HeadBlock`(P/`entitlement/domain/EntitlementHead.kt:4`, PSQL/`migration/payment/V3__entitlement_projection.sql:49-51`) | **가맹점**(`blocked_reason`·`blocked_revision`) | 그 가맹점 **새 결제 생성 전부** Unavailable(503) | 미지원 버전·같은 revision 다른 내용 | **자동**: 더 큰 revision의 정상 적용 또는 막은 버전의 재처리. 운영자 해제 명령 없음 | 코드 불일치만으로는 **불가**(P-6). 가맹점 소속 자체가 흔들린 경우만(§3.4) |
| lease 만료 | 가맹점 | 새 결제 생성 Unavailable(SNAPSHOT_EXPIRED) | 갱신이 오지 않음 | 새 lease 적용 | 전체 보류의 **부작용**으로 생긴다(§3.1) |

### 2.7 운영자 쓰기 경로 [사실]

`updateHead` 호출자는 `EntitlementInboxService.kt:117` 하나다(grep). internal-admin·payment admin API에 권리 투영·코드 쓰기 endpoint가 없다. runtime 계정은 `entitlement_head`에 SELECT·INSERT·UPDATE(MERCHANT-CODE-001 rev 0.4.11 — `runtime-privilege/payment/V4…sql:8`)이므로 **DB 권한이 아니라 코드 경로로** P-10이 지켜진다.

### 2.8 ACK 계약 고정 상태 [사실]

- MASTER parser(M/`kotlin/com/paycore/master/merchant/adapter/integration/HttpEntitlementDispatcher.kt:57-75`)는 `outcome`·`appliedRevision`·`consumer`·`originalOutcome`만 `node.get`으로 읽고 **다른 키는 무시**한다. `outcome`·`originalOutcome`이 6종(`:75`) 밖이면 `ACK_UNREADABLE` → Retryable(무한 재시도). MASTER 저장 CHECK도 6종(M/`resources/db/migration/master/V3__entitlement_snapshot.sql:192-195`).
- `EntitlementContractGoldenSpec`(PT/`entitlement/EntitlementContractGoldenSpec.kt:29-133`)은 **요청 봉투**(서명·정규 내용·hash)만 고정하고 ACK 응답은 다루지 않는다. ACK는 PT/`entitlement/EntitlementInboxSpec.kt:60`이 `outcome`·`appliedRevision` 값만 읽고, MASTER 시험은 자체 가짜(`apps/master-api/src/test/kotlin/com/paycore/master/merchant/application/FakePaymentInbox.kt:49-61`)를 쓴다. **ACK 키 집합을 고정한 시험은 없다** → 필드 추가는 기존 시험을 깨지 않고, 새 시험으로 고정한다(T-26).

---

## 3. 설계

### 3.1 핵심 판단 — "전체 보류"는 결국 결제 중단이 된다 [해석]

수신 Snapshot 전체를 적용하지 않고 기다리면(head를 직전 revision에 둠):

1. MASTER는 그 revision(N)의 lease만 갱신한다(§2.4). 직전 revision(N−1)의 lease는 더 오지 않는다.
2. PAYMENT lease(N−1)가 만료되면(Demo 60초) 그 가맹점 새 결제 생성이 전부 SNAPSHOT_EXPIRED(R3).
3. 같은 Snapshot에 담긴 정지·차단(권리 축소)도 반영되지 않는다 — 막아야 할 결제를 lease 동안 허용한다.

따라서 **코드와 독립적인 권리 내용은 반영하고 코드만 기존 값에 고정**한다. "전체 보류"는 권리 내용이 이 가맹점의 것인지 의심될 때(§3.4)만 쓰며, 그때는 기존 head 막힘을 쓴다.

### 3.2 감지·차단 위치(최소 변경)

| 계층 | 변경 |
|---|---|
| Domain `SnapshotApplicationRule.decide` | 판정 표 §3.3을 적용. 순수 함수 유지 — 새 입력 `prior: PriorCodeState`(아래) [구현 관례: 이름] |
| `PriorCodeState`(Domain 값) | `acceptedCode` = head가 지금 내용·lease를 받은 이벤트(`head.sourceEventId`)의 코드, `appliedContent` = head.revision의 저장 내용(businessId·코드·hash), `blockedCode`·`blockedBusinessId` = head가 INTEGRITY_CONFLICT로 막혀 있으면 막은 이벤트의 코드·businessId. 빈 head면 모두 null |
| Application `EntitlementInboxService.once` | **head에 코드가 있으면 항상**(MINOR-1) 판정 전에 `PriorCodeState`를 읽는다: `findSnapshot(merchantId, head.revision)`(PK), `head.sourceEventId`의 inbox 원문 코드(UNIQUE (consumer, event_id)), 막힘이면 `(aggregate_id, aggregate_version = blockedRevision, outcome = INTEGRITY_CONFLICT)` 첫 행(기존 인덱스 `ix_inbox_event_aggregate`). 모두 X 잠금 아래 비잠금 읽기 |
| `ApplicationDecision` | `merchantCodeChanged: Boolean` → `codeHold: CodeHoldTransition`(NONE / OPENED / CONTINUED / KIND_CHANGED / RESOLVED) [구현 관례] |
| Inbox outcome | 새 값 `APPLIED_CODE_HELD`(17자), Q-MSG-3 (b)이면 `LEASE_EXTENDED_CODE_HELD`(24자)도. 규칙: **수락된 결과(APPLIED·LEASE_EXTENDED)에서 적용 뒤 head가 코드 Hold면 `_CODE_HELD`를 붙인다** — Hold 중 같은 revision 갱신도 정상 완료로 기록하지 않는다(P-8) |
| ACK | §3.6 |
| head | **새 열 없음**. 기존 `merchant_code` = "PAYMENT가 지키는 코드" |

### 3.3 판정 표

H = head 코드, C = 수신 코드(v1~v3면 null), N = 수신 revision > highestSeen, `ok(I-4)` = §3.4.

| # | 조건 | outcome | head 갱신 | 저장 내용 | Hold 전이 | 경보 |
|---|---|---|---|---|---|---|
| G-1 | H = null | 현행 그대로(APPLIED 등) | 현행(C가 있으면 첫 코드) | 현행 | NONE | 없음 |
| G-2 | H ≠ null, N, C = H, ok(I-4) | APPLIED | revision·hash·lease·sourceEventId 교체, 코드 H, 막힘 해제 | 받은 그대로 | 직전이 Hold(적용형·막힘형)면 **RESOLVED** | 해소 INFO 로그 |
| G-3 | H ≠ null, N, C ≠ H(다른 코드 또는 null), ok(I-4) | **APPLIED_CODE_HELD** | G-2와 같되 **코드는 H 유지** | 받은 그대로(MASTER가 보낸 C의 증거) | 직전 정상 → **OPENED**, 직전 Hold(적용형·막힘형) → CONTINUED, C 종류만 바뀜(REPLACED↔REMOVED) → KIND_CHANGED | OPENED만 카운터 +1·ERROR, KIND_CHANGED는 INFO(NIT) |
| G-4 | H ≠ null, N, **I-4 위반**(코드 같든 다르든) | INTEGRITY_CONFLICT(기존 값) | `blocked = INTEGRITY_CONFLICT, blockedRevision = N`, 내용·코드·lease 불변 | 저장 안 함(inbox 원문만) | 직전 정상 → OPENED(막힘형), 직전 Hold → CONTINUED | OPENED만 카운터 +1(MINOR-2) + 기존 integrity ERROR |
| G-5 | 같은 revision, 같은 hash, lease 더 김 | LEASE_EXTENDED, 적용 뒤 Hold면 **LEASE_EXTENDED_CODE_HELD** | lease·sourceEventId(현행) | — | 상태 유지(전이 없음) | 없음 |
| G-6 | 같은 revision, 다른 hash, **코드만 다름**(§3.5) | Q-MSG-3: (a) 현행 INTEGRITY_CONFLICT / (b) LEASE_EXTENDED_CODE_HELD | (a) 막힘 / (b) lease·sourceEventId만, 내용·코드 그대로 | 저장 안 함 | 직전 정상 → OPENED, 그 밖 CONTINUED | OPENED만 |
| G-7 | 그 밖(같은 revision 다른 내용, 더 작은 revision, 막힌 head의 같은 revision, 미지원 버전) | 기존 규칙 | 기존 | 기존 | 유지 | 없음 |

**Hold 상태의 정의(파생 — 새 상태·열 없음, 가맹점당 최대 1개)**:

- **적용형 Hold**: head 코드 H ≠ null이고 head가 막혀 있지 않으며, `head.sourceEventId` 이벤트(inbox 원문 `$.payload.snapshot.merchantCode`)의 코드가 H와 다르다(null 포함). G-3·G-5(Hold 중)·G-6(b)를 모두 같은 식으로 덮는다.
- **막힘형 Hold**: head가 `INTEGRITY_CONFLICT`로 막혀 있고, 막은 이벤트의 코드가 H와 다르거나 businessId가 head.revision 저장 내용과 다르다(G-4·G-6(a)). 코드·businessId가 같은 일반 무결성 막힘(다른 권리 필드의 revision 증가 누락)은 코드 Hold가 아니다.
- 전이 계산(OPENED/CONTINUED/RESOLVED)은 위 정의를 **직전** head로 한 번, **적용 뒤** head로 한 번 계산해 비교한다 — 막힘형을 포함한다(MINOR-2).
- Hold 이력 = inbox의 `_CODE_HELD`·코드 막힘 `INTEGRITY_CONFLICT` 행(append-only, 원문 포함).
- G-3의 저장 내용은 받은 그대로이므로 `content_hash`·revision 대조(`EntitlementEvaluator.kt:71`)가 그대로 성립한다. head CHECK(`V36…sql` `ck_entitlement_head_merchant_code`)·Domain `init`(`EntitlementHead.kt:29`)은 v3 내용 + 코드 유지 head도 허용한다 — head migration 불필요.

### 3.4 '다른 필드 반영' 조건과 '전체 보류' 조건 [해석 — Q-MSG-3 ①]

독립성·정합성(P-7) = 수신 Snapshot의 권리 내용이 **이 가맹점의 것**임이 코드와 무관한 키로 확인됨:

| # | 확인 | 근거 |
|---|---|---|
| I-1 | 봉투·내용 `merchantId` = head | 이미 강제(`SnapshotApplicationRule.kt:33`, `EntitlementSnapshot.kt:513-516`) |
| I-2 | 받은 hash = 다시 계산한 hash | 이미 강제(`EntitlementSnapshot.kt:517`) |
| I-3 | 내용의 MID가 다른 가맹점 소속이 아님 | 이미 강제(`JpaEntitlementProjectionStore.kt:87-97`) |
| I-4 | `businessId` = head.revision 저장 내용의 businessId | **새 확인**(H ≠ null인 모든 N에서 — G-2·G-3 공통, MINOR-1). MASTER `Merchant.businessId`는 불변(M/`kotlin/com/paycore/master/merchant/domain/Merchant.kt:14` `val`) |

- I-1~I-4 충족 → G-2(코드 같음) 또는 G-3(코드만 고정, 나머지 반영).
- I-4 위반 → G-4(기존 head 막힘, 새 결제 생성 즉시 fail-closed). 코드가 아니라 가맹점 소속이 흔들린 Snapshot이라 P-6의 "코드 불일치만으로"에 해당하지 않는다고 본다.
- I-3 위반의 현행 처리(`check` 실패 → rollback → 수신 실패 → MASTER 재시도)는 범위 밖(§8).

### 3.5 같은 revision 코드 단독 변경(F4)의 식별과 선택지 [Q-MSG-3 ②]

**식별 가능성** [사실 기반 해석]: 같은 revision·다른 hash를 받았을 때 PAYMENT에는 head.revision의 저장 내용(`entitlement_snapshot`, 읽을 때 hash 재검증 — `JpaEntitlementProjectionStore.kt:79-85`)이 있다. 수신 내용에서 `merchantCode`를 저장 내용의 코드로 바꿔 다시 계산한 hash가 저장 hash와 같으면 "코드만 다름"이다(정규 JSON·hash 계산은 Domain `EntitlementContent.contentHash` 그대로 — `copy(merchantCode = stored.merchantCode).contentHash == stored.contentHash`). 추가 조건: H ≠ null, schemaVersion 같음(같은 revision의 v3 → v4 재발행 — `SchemaRolloutOrderV4Spec.kt:113-120` "control" 시험 — 은 코드 단독 변경이 아니므로 현행 INTEGRITY_CONFLICT 유지), I-4 충족(코드 외 동일이면 자동 충족). 순수 Domain 판정으로 가능하다.

| 선택 | 동작 | 대가 |
|---|---|---|
| (a) 현행 유지 | INTEGRITY_CONFLICT 막힘(§2.5) + 막힘형 코드 Hold·경보. 새 결제 생성이 MASTER가 원래 코드로 더 큰 revision을 낼 때까지 중단 | **P-6의 예외를 명시**해야 한다("revision 증가 누락을 동반한 코드 변경은 무결성 결함으로 fail-closed"). Q-MSG-2 정정 경로가 없으면 중단이 무기한이 될 수 있다 |
| (b) 코드 단독이면 lease만 연장 | 저장 내용(검증된 직전 내용)·코드 H 그대로, 수신 lease가 더 길면 lease·sourceEventId만 갱신, outcome `LEASE_EXTENDED_CODE_HELD`, 적용형 Hold·경보 | ① 확정 규칙 "같은 revision·다른 hash는 무결성 결함으로 막는다"를 코드 필드에 한해 **약화** — PAYMENT가 저장 hash와 다른 내용의 lease를 받아들인다 ② 그 revision의 수신 원문은 inbox에만 있고 `entitlement_snapshot`에는 남지 않는다 ③ MASTER `revisionHashDrift`는 첫 갱신 1회만 오른다(§2.5-3) — 이후 탐지는 PAYMENT Hold에 의존 ④ 새 outcome 하나 더(V37 CHECK) ⑤ 다른 필드 drift는 지금처럼 막는다(제외 대상은 코드 하나) |

답 전에는 **G-6을 구현하지 않는다**(현행 INTEGRITY_CONFLICT 그대로 — 막힘형 Hold 파생·경보는 (a)·(b) 공통이라 구현 가능).

### 3.6 revision 처리의 의미와 ACK — "권리는 적용, 코드는 미적용" [ACK 부분: 해석 — Coordinator 결정(가역)]

| 질문 | 답 |
|---|---|
| G-3에서 revision N은 적용됐나 | **권리는 적용**(head.revision = N, 판정·lease N 기준). 코드는 **미적용** |
| inbox 기록 | `APPLIED_CODE_HELD`(P-8), `head_revision_after = N` |
| 다음 revision 대기인가 | 권리는 기다리지 않는다(§3.1). **코드 Hold만** 다음 정상 revision(G-2)을 기다린다 |
| Hold 중 같은 N 갱신 | G-5 → `LEASE_EXTENDED_CODE_HELD`, 전이·경보 없음(P-11) |
| 더 큰 revision인데 여전히 C ≠ H | `APPLIED_CODE_HELD`(CONTINUED), 경보 없음 |
| G-4·G-6(a) | 기존 막힘과 같다: `INTEGRITY_CONFLICT`, 같은 N 갱신은 STALE, 더 큰 revision이 G-2/G-3을 통과하면 막힘이 풀린다 |
| **ACK** | `outcome`은 MASTER가 아는 기본 값으로 보낸다(`APPLIED_CODE_HELD` → `"APPLIED"`, `LEASE_EXTENDED_CODE_HELD` → `"LEASE_EXTENDED"`) + **하위 호환 추가 필드 `"merchantCodeHeld": true`**(Hold 결과일 때만, 아니면 키 생략). DUPLICATE도 같은 방식: `originalOutcome`은 기본 값으로, 저장 outcome이 `_CODE_HELD`면 `merchantCodeHeld: true`. 이유: 새 outcome 값을 그대로 보내면 MASTER가 `ACK_UNREADABLE` 무한 재시도(§2.8). ACK의 계약 의미는 권리 효력 확인(R6 `appliedRevision ≥ r`)이고 권리는 실제로 적용됐으므로 기본 값은 사실과 맞고, 추가 필드가 "정상 완료가 아님"(P-8)을 MASTER에도 남긴다. MASTER parser는 이 필드를 무시한다(§2.8) — **MASTER 저장·표시는 후속 작업**(§8) |
| 계약 문서 | MASTER-SNAPSHOT-001 §4(ACK 모양)에 선택 필드 한 줄, "모르는 필드는 무시" 규칙 명시 |

### 3.7 정상 결제·취소 영향 없음 보장(P-6)

- G-3·G-5·G-6(b): 판정 입력(권리 내용·lease)은 정상 적용과 같다 → 결제 생성·승인 terms·Site 판정 결과가 같다. 코드는 판정 입력이 아니다(§2.3).
- 잠금 순서 불변: Inbox는 head X만 잡는다(`EntitlementProjectionStore.kt:8-11`). `PriorCodeState` 읽기는 PK·UNIQUE·기존 인덱스 비잠금 읽기.
- 취소·조회·웹훅은 권리 판정을 쓰지 않거나(취소) head 코드만 읽는다 → 기존 코드 H(P-4).
- `incident_hold`를 쓰지 않으므로 FINANCIAL 매입·정산 보류 전파(D-GOV-001 ④)가 생기지 않는다.
- 새 결제 생성이 막히는 경우: G-4(I-4 위반)와 Q-MSG-3 (a)를 택했을 때의 G-6뿐. 취소·조회·웹훅은 그때도 영향 없음.

### 3.8 해소 흐름(P-9)과 운영자 직접 수정 금지(P-10)

1. 경보(§3.9) → 운영자가 MASTER 배정 이력·감사·발행 기록(`entitlement_snapshot_issue`, `revisionHashDrift`)으로 원인을 확인한다(GOV-MCODE-001 §3.3 F1~F4).
2. MASTER 정정: 조립·배포 결함이면 수정 배포 뒤 revision + 1 재발행(MASTER-SNAPSHOT-002 §5.2 승격 경로와 같은 방법). **MASTER 데이터 자체가 바뀐 경우의 정정 수단은 없다**(Q-MSG-2).
3. PAYMENT 재수신: 더 큰 revision에서 C = H·I-1~I-4 충족 → G-2 APPLIED + RESOLVED(INFO `merchant code hold resolved`, 근거 = 그 inbox eventId). Q-MSG-3 (b)의 적용형 Hold는 같은 revision 갱신이 원래 hash로 돌아와도(G-5 정상) 해소된다.
4. 해제 정책 = 기존 동기화 구조(head 막힘)의 해제 방식과 같은 **자동 해제** [해석 — Q-MSG-1]. `incident_hold`의 2인·MATCH 해제는 쓰지 않는다(§2.6).
5. 운영자 경로: PAYMENT 코드 쓰기 경로는 Inbox 적용 하나로 고정(T-23). 새 관리 기능·endpoint를 만들지 않는다. Hold 목록 Reader(§3.9)는 읽기 전용이다.

### 3.9 관측 — gauge 필수, Hold 목록 Reader(MAJOR-3)

| 항목 | 내용 |
|---|---|
| 카운터 `merchant_code_changed_with_mid` | OPENED에서만 +1(커밋 뒤). **보조 신호**다 — commit 결과 불명확이면 증가가 유실된다(§2.1) |
| **gauge(필수)** `paycore.payment.entitlement.merchant_code_holds` [이름: 구현 관례] | 현재 코드 Hold 가맹점 수(적용형 + 막힘형, §3.3 정의). label 없음. **경보 규칙의 기준은 gauge > 0**(응답 유실·재시작과 무관하게 DB에서 다시 계산) |
| Hold 목록 Reader | `EntitlementFreshnessReader` 확장(새 Port 메서드, `JooqEntitlementFreshnessReader` 구현, REPEATABLE READ 읽기 전용 — 기존 `overview`와 같은 방식). 행 = merchantId · 종류(APPLIED/BLOCKED) · kind(REPLACED/REMOVED/IDENTITY) · **시작 revision · 시작 eventId**. 시작 = 그 가맹점 inbox에서 마지막 정상 수락 행(outcome APPLIED·LEASE_EXTENDED) 뒤의 첫 Hold 행(`_CODE_HELD` 또는 코드 막힘 INTEGRITY_CONFLICT) — `id` 순서. 새 표 없음. 코드 값은 결과에 넣지 않는다 |
| 목록 노출 | 이번 배치는 Reader·gauge까지. 운영 화면·API는 만들지 않는다(P-12) — 필요하면 후속 |
| JSON 비교(MINOR-6) | inbox 원문 코드 = `JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.snapshot.merchantCode'))`, 저장 내용 = `…(payload, '$.merchantCode')`, businessId도 같은 방식. JSON 함수 결과는 `utf8mb4_bin`이고 head 열은 `utf8mb4_0900_bin`이라 **비교 양쪽에 `COLLATE utf8mb4_0900_bin`을 명시**한다(암묵 변환·collation 혼합 오류 방지, 기존 `JpaMerchantNotificationFanoutStore.kt:134-150`의 JSON_EXTRACT 사용 선례). null(코드 키 없음)은 `IS NULL`로 따로 판정 |
| 비용(MINOR-6) | gauge: `entitlement_head`(가맹점당 1행) 전체 + 행마다 inbox UNIQUE (consumer, event_id) 1건(`source_event_id`) + `entitlement_snapshot` PK 1건 + 막힌 행만 `ix_inbox_event_aggregate` 1건. 비용 ∝ 가맹점 수(inbox 크기 무관). JSON 추출은 행마다 1회. 기존 freshness gauge와 같은 수집 주기. 시작 revision 계산(inbox 범위 조회)은 **목록 Reader에서만**, Hold 가맹점에 대해서만 한다 |

### 3.10 영향 범위

| 대상 | 변경 |
|---|---|
| P/`entitlement/domain/SnapshotApplicationRule.kt`, `EntitlementHead.kt`(InboxOutcome), 새 `PriorCodeState`·`CodeHoldTransition` | 판정 표 G-1~G-7 |
| P/`entitlement/application/EntitlementInboxService.kt`, `required/EntitlementProjectionStore.kt`(+ inbox 원문 코드·막은 이벤트 읽기 메서드), `JpaEntitlementProjectionStore.kt` | PriorCodeState 읽기, 전이 로그·카운터 |
| P/`entitlement/adapter/web/EntitlementInboxController.kt` | ACK 기본 값 변환 + `merchantCodeHeld` |
| P/`entitlement/application/required/EntitlementFreshnessReader.kt`, `adapter/query/JooqEntitlementFreshnessReader.kt`, `adapter/integration/EntitlementFreshnessGauges.kt`, `EntitlementMetrics.kt`·`MicrometerEntitlementMetrics.kt` | gauge·목록 Reader, 카운터 의미 |
| PSQL/`migration/payment/V37__inbox_outcome_code_held.sql`(새) | `inbox_event.outcome` CHECK 교체 — §5.1 |
| 시험 | §4, 기존 MC-4-4 기대값 갱신(§4.4) |
| 문서(구현 배치에서) | MERCHANT-CODE-001 §6 476·483행, MASTER-SNAPSHOT-001 §3.5 표·§4 ACK, GOV-MCODE-001 C-05 닫음, 대장 §6 |
| 변경 없음 | MASTER(api·DB), FINANCIAL, internal-admin·화면, head 열, `incident_hold`, `EntitlementEvaluator`, 웹훅·결제 응답 계약 |

데이터 이전 없음. 이 작업 전 현행 규칙으로 이미 코드가 바뀐 head(정상 경로에서는 불가, 시험·Demo 데이터만)는 지금 head 코드가 "기존 코드"가 된다(되돌릴 근거 값이 PAYMENT에 없음).

---

## 4. 시험 계획

Fixture는 `SnapshotFixture` 명시 Factory(코드·businessId·lease를 이름 있는 인자로)만 쓴다. 독립 random 금지. 실제 MySQL은 `EntitlementDbTest` 구성(PT/`entitlement/EntitlementDbTest.kt:22`).

### 4.1 Domain(순수)

| ID | Given | When | Then |
|---|---|---|---|
| T-01 | head 코드 A(rev 3), 정상 | rev 4 코드 B, 같은 businessId | `APPLIED_CODE_HELD`, newHead rev·hash·lease 4, **코드 A**, OPENED |
| T-02 | 같음 | rev 4 v3 내용(코드 없음) | `APPLIED_CODE_HELD`, 코드 A, OPENED(REMOVED) |
| T-03 | 적용형 Hold(수락 코드 B) | rev 5 코드 B | `APPLIED_CODE_HELD`, CONTINUED |
| T-03b | 적용형 Hold(B) | rev 5 v3(코드 없음) | `APPLIED_CODE_HELD`, KIND_CHANGED(경보 없음, INFO) |
| T-04 | 적용형 Hold(B) | rev 5 코드 A | APPLIED, RESOLVED |
| T-05 | head 코드 A | rev 4 **다른 businessId**(코드 B / 코드 A 각각) | INTEGRITY_CONFLICT, blockedRevision 4, 내용·코드 불변, OPENED(막힘형) |
| T-05b | 막힘형 Hold(rev 4) | rev 5 다른 businessId 계속 | INTEGRITY_CONFLICT, CONTINUED(경보 없음 — MINOR-2) |
| T-05c | 막힘형 Hold | rev 5 코드 B·같은 businessId | `APPLIED_CODE_HELD`, 막힘 해제, CONTINUED(막힘형 → 적용형, 경보 없음) |
| T-05d | 막힘형 Hold | rev 5 코드 A·같은 businessId | APPLIED, 막힘 해제, RESOLVED |
| T-06 | head 코드 null(v3) | rev 4 v4 코드 A | APPLIED, 코드 A, NONE |
| T-07 | 적용형 Hold(rev 4) | rev 4 같은 hash·더 긴 lease | `LEASE_EXTENDED_CODE_HELD`, 코드 A, 전이 없음 |
| T-08 | 적용형 Hold(rev 4) | rev 3(역순) 코드 A | STALE, head 불변(과거 revision으로 해소하지 않음) |
| T-09 | head 코드 A(rev 3) | rev 3 같은 schema·**코드만 다름** | 답 전: 현행 INTEGRITY_CONFLICT + OPENED(막힘형). (b) 채택 시: `LEASE_EXTENDED_CODE_HELD`, 내용 불변 |
| T-09b | head 코드 A(rev 3) | rev 3 코드 같고 다른 권리 필드 다름 | INTEGRITY_CONFLICT, **코드 Hold 아님**(NONE) |
| T-09c | head 코드 null v3(rev 1) | rev 1 v4 재발행 | 현행 INTEGRITY_CONFLICT(`SchemaRolloutOrderV4Spec.kt:113-120` 유지) |

### 4.2 실제 MySQL·HTTP(서명 봉투)

| ID | 시나리오 | 확인 |
|---|---|---|
| T-11 | rev 1(코드 A) → rev 2(코드 B) | ACK `outcome "APPLIED"`·`appliedRevision 2`·`merchantCodeHeld true`, DB inbox `APPLIED_CODE_HELD`, head 코드 A, `entitlement_snapshot` rev 2 코드 B, 카운터 +1, gauge 1 |
| T-12 | T-11 뒤 rev 2 lease 갱신 5회 | 모두 ACK `LEASE_EXTENDED`+`merchantCodeHeld`, inbox `LEASE_EXTENDED_CODE_HELD` 5행, 카운터 불변, gauge 1 |
| T-13 | T-11 뒤 같은 eventId 재전송 | DUPLICATE, `originalOutcome "APPLIED"`+`merchantCodeHeld true`, 카운터 불변 |
| T-14 | T-11 뒤 rev 1 재도착(역순) | STALE, head 불변 |
| T-15 | rev 3·4(계속 B) | 각 `APPLIED_CODE_HELD`, 카운터 합 +1 |
| T-16 | 해소: rev 5(A) | APPLIED, gauge 0, 해소 로그 1회. 이어 rev 6(B) → 다시 OPENED(+1), 목록 시작 revision 6 |
| T-17 | Hold 중 외부 응답 | 결제 생성 응답·재생·GET·`idempotency_request.result_merchant_code`·웹훅 저장 바이트 모두 A. 결제 생성 판정은 rev 2 권리로 Allowed |
| T-18 | rev 2가 코드 B + MID 정지 | Hold이면서 정지 반영(MERCHANT_SUSPENDED) |
| T-19 | 동시 수신(다른 커넥션) B인 rev 2·3 | head 잠금 직렬화, 카운터 +1, head rev 3·코드 A, gauge 1 |
| T-20 | 실패 주입(기존 "each storage point" 확장): inbox INSERT·snapshot INSERT·head UPDATE 뒤 | rollback, 카운터 0, gauge 0, 재전송 시 OPENED 1회 |
| **T-20b** | **응답 유실**: rev 2(B) 커밋 직후 commit-unknown 예외 주입(또는 응답 쓰기 실패) → 수신 실패 응답 → 같은 eventId 재전송 | DB는 1회 적용, 재전송 DUPLICATE(`merchantCodeHeld true`), **gauge 1**, 목록에 그 가맹점 1행(시작 rev 2·eventId), 카운터는 0일 수 있음(유실 — gauge가 기준임을 고정) |
| T-21 | G-4: rev 2 다른 businessId | INTEGRITY_CONFLICT, 새 결제 생성 Unavailable, **취소 정상**, 조회·웹훅 A, gauge 1(막힘형), rev 2 갱신 STALE·카운터 불변, rev 3(정상 A) → 막힘 해제·gauge 0 |
| T-21b | F4 재현: rev 1(A) 뒤 같은 rev 1 코드 B 갱신 | 답 전: INTEGRITY_CONFLICT·막힘형 gauge 1·카운터 +1, 이후 갱신 STALE·카운터 불변 |
| T-22 | V37 migration: runtime 계정으로 새 값 INSERT 성공, 모르는 값 3819, 기존 값 유지, 문장 원문·`ALGORITHM=COPY` 고정 | §5.1 |
| T-26 | ACK 키 집합 고정: Hold 아님 `{consumer, eventId, outcome, appliedRevision}`, Hold `+ merchantCodeHeld`, DUPLICATE `+ originalOutcome` | 새 시험(기존 ACK 시험 없음 — §2.8) |
| T-27 | MASTER 호환(MASTER 시험 하나, 소스 변경 없음): `merchantCodeHeld` 포함 ACK를 `HttpEntitlementDispatcher`가 Acknowledged로 읽음 | parser 무시 확인 — Coordinator 결정(2026-11-29): master-api 시험 경로에 추가 허용(시험만, MASTER main 변경 없음) |
| T-28 | 목록 Reader·gauge: 적용형·막힘형·일반 무결성 막힘(코드 같음)·정상 혼합 | 목록 = Hold 2행(일반 막힘 제외), gauge 2, 코드 값 미포함, collation 혼합 오류 없음 |

### 4.3 구조·관측 시험

| ID | 확인 |
|---|---|
| T-23 (ArchUnit) | `updateHead` 호출자 = `EntitlementInboxService` 하나, head `merchantCode`를 만드는 곳 = `SnapshotApplicationRule` 하나(P-10) |
| T-24 (ArchUnit) | Domain이 Spring·JPA·jOOQ에 의존하지 않음(기존 유지) |
| T-25 | 로그·지표에 코드 값 없음(시작·지속 종류 변경·해소 로그 포함) |

### 4.4 기존 시험의 기대값 갱신(정책 변경 — D-GOV-019 ③)

| 시험 | 현재 기대 | 새 기대 |
|---|---|---|
| `EntitlementContentV4Spec.kt:90-102` MC-4-4 | APPLIED, newHead 코드 `othershop01` | `APPLIED_CODE_HELD`, 코드 유지, OPENED. 같은 파일의 renewed(LEASE_EXTENDED) 기대는 Hold 아님이라 유지 |
| `EntitlementContentV4Spec.kt:104-118` MINOR-1 | APPLIED, 코드 null | `APPLIED_CODE_HELD`, 코드 유지 |
| `SchemaRolloutOrderV4Spec.kt:181-196` | ACK APPLIED, head 코드 교체, 카운터 +1·+2 | ACK APPLIED + `merchantCodeHeld`, head 코드 유지, 카운터 +1(두 번째는 KIND_CHANGED라 불변) |

완화가 아니라 사용자 정책 변경 반영이다. 시험 이름·주석에 `D-GOV-019 ③`.

검증은 하네스 등록 명령으로 한다(테스트 0건·skip은 실패, 보고서·revision·exit code 기록).

---

## 5. 단계(한 배치)

| 순서 | 내용 |
|---|---|
| S-1 | Domain RED: T-01~T-09c(T-09는 현행 기대), §4.4 Domain 갱신 → GREEN |
| S-2 | V37 + T-22 |
| S-3 | Application·adapter: PriorCodeState 읽기, 전이, ACK 변환 → T-11~T-21b, T-26, §4.4 HTTP 갱신 |
| S-4 | gauge·목록 Reader(T-28, T-20b), T-23~T-25, T-27 |
| S-5 | 문서(§3.10) |
| 게이트 | payment-api SCOPED(entitlement·웹훅·생성 응답) → 공유 FULL에서 DONE(D-PROC-007). **Q-MSG-3 답 전에는 DONE·배포하지 않는다**(§7) |

### 5.1 V37 migration과 적용 창(MINOR-3)

- 문장: `ALTER TABLE inbox_event DROP CHECK ck_inbox_event_outcome, ADD CONSTRAINT ck_inbox_event_outcome_v2 CHECK (outcome IN ('APPLIED', 'LEASE_EXTENDED', 'STALE', 'INTEGRITY_CONFLICT', 'UNSUPPORTED', 'APPLIED_CODE_HELD'[, 'LEASE_EXTENDED_CODE_HELD'])), ALGORITHM=COPY;` — 한 ALTER 안에서 교체해 CHECK 없는 구간을 만들지 않는다(V11 `ck_incident_hold_reason_v2` 선례). CHECK 추가는 기존 행 검증 때문에 COPY만 가능하므로 **ALGORITHM을 명시**해 MySQL이 다른 방식으로 바꾸지 못하게 한다(V36 선례). `LEASE_EXTENDED_CODE_HELD`는 Q-MSG-3 (b)일 때만 — 답을 받고 한 번에 넣어 COPY를 두 번 하지 않는다.
- 원문 고정 시험: `SchemaRolloutOrderV4Spec.kt:123-178`처럼 문장별 ALGORITHM 원문을 읽어 고정(T-22).
- **적용 창 조건**: `inbox_event`는 lease 갱신마다 행이 쌓이는 큰 append-only 표(가맹점 수 × 갱신 주기)다. COPY 동안 DML이 막혀 **Snapshot 수신이 대기·실패 → MASTER 재시도**. 표 복사 시간이 lease 잔여(운영 수치 미결, Demo 30~60초)를 넘으면 그 동안 lease가 만료된 가맹점의 새 결제 생성이 503이 된다(R3). 따라서: ① 유지보수 창에서 적용(MERCHANT-CODE-001 L-14 방식 — 표 크기별 예상 시간 기록) ② 적용 전 행 수·크기 측정, 예상 복사 시간 < lease 잔여 하한이 아니면 창 연장 또는 lease 수치 임시 조정을 운영 결정으로 ③ migration이 새 앱 배포보다 먼저(새 값은 새 앱만 씀, 옛 앱은 확장된 CHECK와 호환). 운영 적용은 이 개발 요청 범위 밖이며 Demo 적용 창만 기록한다.

---

## 6. 대장 변경안(PAY 대장, 다음 번호 BR-PAY-132 — 현재 최대 131 확인)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 시험 |
|---|---|---|---|---|---|---|
| BR-PAY-132 | PAYMENT는 가맹점 head에 이미 있는 가맹점 코드를 수신 Snapshot으로 바꾸거나 지우지 않는다(MASTER가 원천, PAYMENT는 기존 코드 유지) | 확정 | 사용자 | D-GOV-019 ③, D-GOV-011 ② | MCODE-SNAPSHOT-GUARD-001 §3.3 | T-01·T-02·T-11 |
| BR-PAY-133 | 웹훅·결제 생성·재생·조회 응답의 가맹점 코드는 PAYMENT가 유지한 기존 코드다 | 확정 | 사용자 | D-GOV-019 ③ | §2.3 | T-17 |
| BR-PAY-134 | 코드가 다른 Snapshot도 코드와 독립적인 권리 내용(정지·lease 포함)은 반영하며, 코드 불일치만으로 결제·취소를 막지 않는다 | 확정 | 사용자 | D-GOV-019 ③ | §3.1·§3.7 | T-17·T-18 |
| BR-PAY-135 | 코드 Hold 상태의 수락 결과는 정상 결과와 구분해 `_CODE_HELD`로 기록하고, ACK에는 기본 결과 값과 `merchantCodeHeld: true`를 싣는다 | 제안 | 적용 | D-GOV-019 ③ "정상 반영 완료로 처리 금지" + [해석] §3.6 | §3.6 | T-11·T-12·T-13·T-26 |
| BR-PAY-136 | 코드 Hold는 가맹점당 하나이며, 같은 오류의 반복 수신(lease 갱신·더 큰 revision·같은 eventId·역순)은 Hold·경보를 새로 만들지 않는다 | 확정 | 사용자 | D-GOV-019 ③ | §3.3 | T-12~T-15·T-19·T-05b |
| BR-PAY-137 | 해소 뒤 같은 가맹점에 다시 코드 불일치가 오면 새 Hold로 보고 경보를 다시 낸다 | 제안 | 적용 | [해석] incident_hold occurrence 선례(V11) | §3.3 | T-16 |
| BR-PAY-138 | 가맹점 소속(businessId)이 직전 내용과 다른 Snapshot은 반영하지 않고 기존 head 막힘으로 새 결제 생성을 fail-closed 한다 | 제안 | 적용 | [해석] §3.4, Q-MSG-3 ① | §3.4 | T-05·T-21 |
| BR-PAY-139 | 같은 revision에서 코드만 다른 Snapshot의 처리(Q-MSG-3 ② 답에 따라 문구 확정) | 제안 | 적용 | §3.5, Q-MSG-3 ② | §3.5 | T-09·T-21b |
| BR-PAY-140 | 코드 Hold는 같은 코드의 정상 수신으로 자동 해소되며 운영자 해제·코드 수정 기능은 없다 | 제안 | 적용 | [해석] §3.8, Q-MSG-1, D-GOV-019 ③ "운영자 직접 수정 불허" | §3.8 | T-16·T-23 |
| BR-PAY-141 | 코드 Hold의 경보 기준은 DB에서 다시 계산한 현재 Hold 가맹점 수(gauge)다(카운터는 보조) | 제안 | 적용 | [해석] §3.9 | §3.9 | T-20b·T-28 |

BIZ 대장: BR-BIZ-067 '적용 명세'에 "PAYMENT 하류 방어 MCODE-SNAPSHOT-GUARD-001(BR-PAY-132)" 참조만(새 BIZ 번호 없음 — 348~400 예약 구간 미사용). WH 대장: BR-WH-015 시험 열에 T-17 참조.

---

## 7. 사용자 결정 필요 질문(3개)

### Q-MSG-1 — 코드 Hold 해제 방식

- 배경: "기존 Hold 해제 정책"의 기존 Hold는 둘이다 — 주문 단위 `incident_hold`(2인 + 재대사 MATCH), 가맹점 단위 head 막힘(정상 revision 적용으로 **자동**).
- 권장(a): **자동 해소** — MASTER 정정 뒤 같은 코드의 정상 Snapshot을 받으면 정합 검증(코드 = 기존 코드, I-1~I-4)과 함께 해소, 근거 inbox eventId 기록. 운영자 명령 없음(직접 수정 불허와 맞고 새 관리 기능이 없다).
- 대안(b): 자동 정합 확인 뒤에도 **2인 해제 명령**(요청자 ≠ 승인자, 근거 참조)이 있어야 해소. 해제 기록 표·ADMIN 화면·API가 새로 필요(P-12와 충돌). 해제 전까지 '확인 필요'로 남는다.
- 영향: §3.8, BR-PAY-140, T-16.
- 막히는 작업: 없음(권장안으로 구현하되 BR-PAY-140은 '제안'). (b)면 해제 부분이 별도 작업.

### Q-MSG-2 — MASTER 데이터 자체가 바뀐 경우의 정정 수단(키 동일성)

- 배경: 해소는 "MASTER 정정 → 기존 코드로 재발행"이다. 조립·배포 결함이면 수정 배포로 끝난다. 그러나 MASTER 배정 이력에 승인 뒤 seq 2가 들어간 경우(F4)에는 BR-BIZ-068(같은 가맹점 되돌리기 금지)과 배정 표 append-only·seq ≤ 2 때문에 MASTER가 원래 코드로 돌아갈 정상 경로가 없다. 그러면 PAYMENT Hold가 해소되지 않는다. **그동안의 결제 영향은 경우에 따라 다르다**(rev 0.1 "결제는 계속 정상" 정정): F4가 이후 revision 증가와 함께 오면 G-3이라 결제는 정상이다. revision 증가 없이 오면(§2.5) Q-MSG-3 ② (a)일 때 **그 가맹점 새 결제 생성이 무기한 중단**되고, (b)일 때 정상이다.
- 권장(a): **결함 정정 전용 경로를 별도 작업으로 명세** — 2인 승인 + 사고 근거로 "승인 뒤 잘못 들어간 변경"을 무효 표시하고 원래 코드를 현재 코드로 되살리는 append-only 정정 기록(BR-BIZ-068의 되돌리기 금지는 정상 변경에 대한 것이고 결함 정정은 예외). 정정 발행은 revision + 1. 이 작업(PAYMENT 방어)은 그 결정과 독립으로 진행.
- 대안(b): 정정 경로를 만들지 않는다 — 그 경우 PAYMENT Hold 영구(위 결제 영향 그대로), MASTER 표시 코드와 외부 코드가 계속 다름.
- 대안(c): 그 경우에 한해 MASTER의 새 코드를 PAYMENT가 받아들이는 운영 예외(외부 코드가 바뀜 — D-GOV-011 ②·D-GOV-019 ③와 충돌).
- 영향: MASTER 코드 배정 규칙, BR-BIZ-068, 해소 절차.
- 막히는 작업: 이 명세 구현은 막지 않는다. MASTER 정정 작업(신규)만 답 대기.

### Q-MSG-3 — 정합성이 의심되는 Snapshot의 처리(전체 보류와 P-6의 경계)

두 부분을 함께 답해 주세요.

**① 가맹점 소속(businessId)이 바뀐 Snapshot**
- 권장(a): 반영하지 않고 기존 head 막힘(그 가맹점 새 결제 생성 즉시 fail-closed, 취소·조회·웹훅은 기존 코드로 계속). 코드가 아니라 소속이 흔들린 것이라 P-6 대상이 아니라고 본다(§3.4, BR-PAY-138).
- 대안(b): 코드만 고정하고 나머지는 반영(G-3과 같게) — 소속이 바뀐 권리도 반영된다.

**② 같은 revision에서 코드만 바뀐 Snapshot(F4 — revision 증가 없이 MASTER 코드가 바뀜, §2.5)**
- 현행 확정 규칙: 같은 revision·다른 hash → INTEGRITY_CONFLICT 막힘 → 이후 같은 revision 갱신은 STALE·lease 미연장 → **새 결제 생성 장기 차단**. P-6과 충돌한다.
- 대안(a): 현행 fail-closed 유지 + 막힘형 코드 Hold·경보. **P-6의 예외로 명시**("revision 증가 누락을 동반한 코드 변경은 무결성 결함"). Q-MSG-2 정정 경로가 없으면 중단이 무기한일 수 있다.
- **권장(b)**: PAYMENT가 "코드만 다름"을 판정할 수 있으면(저장 내용과 코드 제외 동일, 같은 schemaVersion — §3.5) 검증된 기존 내용·기존 코드로 **lease만 연장** + 적용형 코드 Hold·경보(`LEASE_EXTENDED_CODE_HELD`). 대가: 확정 규칙의 무결성 대조를 코드 필드에 한해 약화(저장 hash와 다른 내용의 lease 수락), 그 revision 원문은 inbox에만 남음, MASTER drift 지표는 첫 1회뿐이라 이후 탐지는 PAYMENT Hold에 의존, outcome 값 하나 추가. 다른 필드가 다르면 지금처럼 막는다.
- 영향: §3.3 G-4·G-6, §3.5, BR-PAY-138·139, V37 값 목록, T-05·T-09·T-21·T-21b.
- 막히는 작업: G-4(I-4)와 G-6 분기, V37 최종 값 목록. 나머지 S-1~S-4는 진행 가능하나 **답 전에는 배치를 DONE·배포하지 않는다**(I-4 없이 배포하면 ①(b)를, G-6 없이 배포하면 ②(a)를 사실상 채택하는 셈).

---

## 8. 하지 않은 것

- 소스·시험·migration·대장(`docs/rules/*`)·`docs/DECISIONS.md`·`harness/*`·다른 명세를 수정하지 않았다. 커밋하지 않았다. 이 문서 하나만 만들었다.
- 시험·게이트를 실행하지 않았다. §2의 사실은 코드·시험 본문을 읽어 확인한 것이다(실행 검증 아님). V37 COPY 소요 시간은 측정하지 않았다.
- MASTER의 `merchantCodeHeld` 저장·표시(ack 표 열·효력 확인 질의·화면)는 후속 작업으로 남겼다(§3.6). T-27은 master-api 시험 경로 허용 여부를 Coordinator가 정한다.
- MID 소속 위반(I-3)의 현행 처리(`check` 실패 → 수신 실패 → MASTER 반복 재시도)는 바꾸지 않았다.
- MASTER 쪽 원인 탐지(GOV-MCODE-001 C-02 — 사후 대사 질의 운영 연결), MASTER 정정 경로(Q-MSG-2)는 범위 밖이다.
- 운영 홈 KPI '확인 필요'(D-GOV-019 ②)에 코드 Hold를 넣을지, Hold 목록의 운영 화면은 판단·설계하지 않았다.
- `questions.json`에 Q-MSG-1~3을 등록하지 않았다(Coordinator 몫).

---

## 9. rev 0.2 리뷰 대응표

| 지적 | 반영 위치 |
|---|---|
| MAJOR-1 같은 revision 코드 단독 변경(F4) | §2.5(사실), §3.3 G-6, §3.5(식별·선택지), Q-MSG-3 ②, Q-MSG-2 전제 정정, T-09·T-09b·T-09c·T-21b, BR-PAY-139 |
| MAJOR-2 ACK `merchantCodeHeld` | §2.8(parser·Golden·ACK 시험 사실), §3.6, T-11~T-13·T-26·T-27, BR-PAY-135 |
| MAJOR-3 gauge 필수·목록 Reader·응답 유실 | §2.1(커밋 뒤 지표 유실 사실), §3.9, T-20b·T-28, BR-PAY-141 |
| MINOR-1 직전 내용 읽기 단순화·G-2/I-4 통일 | §3.2(H ≠ null이면 항상), §3.3 G-2·G-4, §3.4 I-4 |
| MINOR-2 G-4 전이 포함·중복 경보 시험 | §3.3 Hold 정의(막힘형)·전이 계산, T-05b~T-05d |
| MINOR-3 V37 COPY·적용 창·원문 시험 | §5.1, T-22 |
| MINOR-4 재발생 = 새 Hold를 제안 행으로 | BR-PAY-136(확정)·BR-PAY-137(제안) 분리 |
| MINOR-5 머리말 작업 트리 | 머리말 기준 revision 항목 |
| MINOR-6 JSON collation·gauge 비용 | §3.9 |
| NIT REPLACED↔REMOVED INFO | §3.3 G-3 KIND_CHANGED, T-03b |
| incident_hold 미사용 근거 D-GOV-001 ④ | §2.6, §3.7 |


## 10. 재리뷰 메모 (rev 0.2 APPROVED, 2026-11-29)

- m-1·m-2(G-5 전이·Q-MSG-3 (b) 반복 갱신 시험)는 Q-MSG-3 답 뒤 rev 0.3에서 반영 — (b) 채택 시 G-5 전이 열 '적용 뒤 기준 재계산(RESOLVED 가능)', T-21b 확장(drift 갱신 5회 → LEASE_EXTENDED_CODE_HELD 5행, 카운터 +1만).
- 참고: 막힘형 Hold의 막은 이벤트 조회는 비용이 보이면 첫 INTEGRITY_CONFLICT 행만(ORDER BY id LIMIT 1).
