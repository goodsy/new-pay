# ADMIN-PAYMENT-OPS-001 관리자 → PAYMENT 운영자 보정 취소·서비스 간 인증 명세 (rev 0.4)

> **rev 0.4 갱신(2026-10-28)**: rev 0.3 REVIEW_PASS(High·Medium 없음, I-35 채택)의 Low 3건(L-1~L-3)과 구현 주의(InnoDB 1062는 문장만 rollback)를 반영했다. 변경 기록 §17.2. 설계 결정 변경 없음.
>
> **rev 0.3 갱신(2026-10-28)**: 검토 2차 CHANGES_REQUIRED(M-a·M-b, L-a~L-e, nit)와 새 사용자 결정 **D-OPS-009**(기한 확인 전용 호출)를 반영했다. 변경 기록 §17.1. **rev 0.3 문장이 앞 rev와 다르면 rev 0.3이 우선한다.** 미결 사용자 질문 없음.
>
> **rev 0.2 갱신(2026-10-27)**: 명세 검토 CHANGES_REQUIRED(H-1, M-1~M-7, L-1~L-9, nit)와 새 사용자 결정 **D-OPS-005~008**을 반영했다. 변경 기록은 §17(검토 항목별 반영 위치).
> **rev 0.2 문장이 rev 0.1과 다르면 rev 0.2가 우선한다.** 미결 사용자 질문은 없다(§12).

rev 0.1 작성 2026-10-27 KST, rev 0.2 같은 날. 기준 commit `43ecdc3`(TEST-ASSERT-001, branch `feat/foundation-setup`). **S0(1단계 명세)이며 코드·시험·빌드·migration 변경 없음.**
작업 트리의 Coordinator 소유 미커밋 변경(`docs/DECISIONS.md` D-OPS-001~008, `harness/{tasks,questions,progress}.json`)은 읽기만 했다. Gradle은 실행하지 않았다.
출발점은 Coordinator 설계 brief(`~/paycore-notes/ops-design-brief.md`, HEAD `514a747` 조사 기준)이며, 그 사실은 `43ecdc3`에서 다시 확인했다(§3.3). brief와 다르게 정한 곳은 §14.
자기 작업의 승인·완료 판정을 하지 않는다(Reviewer·Verifier 별도).

| 약어 | 문서 | 상태 |
|---|---|---|
| 지침 | `CLAUDE.md` | 지침 |
| 결정 | `docs/DECISIONS.md`(**D-OPS-001~008**, D-CANCEL-001·003·005·010·011·013, D-CANCEL1-001·003·006·008, D-ADMIN-002·003·004·007·008, D-ADMIN1-004·006, D-MS-004, D-CPQ-005, D-WH-003) | 확정 |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md`(§1 8대 원칙, §3 JPA, §4 CQRS·화면, §5 Kafka/Redis) | 사용자 지정 |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9, §14 명명, §16 회복 선점, §17 취소, §19 열 단위 권한) | 구현 관례 |
| 선행 명세 | `CANCEL-001-CANCELLATION.md`(§5 운영자 보정 취소 경계), `ADMIN-BOOT-001-ADMIN-FOUNDATION.md`(§4.5.1 권한 표, §4.7 승인 틀, §4.12 이관 참고, §4.13 잠금 순서) | DONE |
| 작업 | `harness/tasks.json` ADMIN-PAYMENT-OPS-001(AC 6개) | Coordinator 소유 |
| 질문 | `harness/questions.json` Q-OPS-01~08 ANSWERED, Q-ADMIN-APPROVER-STAFF·Q-ADMIN-SEC-VALUES PROPOSED(LIVE Gate) | Coordinator 소유 |

---

## 0. 요약

- **목적**: CANCEL-001이 진입점 없이 남긴 운영자 보정 취소(`OperatorCorrectionCancelUseCase`)를 ADMIN 2인 승인 → 서명된 내부 호출 → PAYMENT 재검사·실행으로 연다(AC2·AC3·AC5·AC6). 인증 D-OPS-001, 실행 기한 D-OPS-002, 결과 불명확 출구 D-OPS-003, 목적 근거 D-OPS-004.
- **PAYMENT 쪽**: 내부 connector `/internal/v1/admin/**` 아래 5개 endpoint(대상 조회, 실행, approvalId별 기록 결정 조회, 기한 확인 전용, 대사용 결정 목록). 전용 보안 체인이 `PayCore-Admin-Signature`(method·정규화 경로·시각·본문 SHA-256 HMAC)를 검증한다. 기본 체인의 `/internal/**`는 Entitlement 정확 경로 외 **denyAll**(M-3). 서명 통과 뒤 형식 오류도 감사(AC4). 관리 조회는 bulkhead·질의 시간 상한으로 공개 승인 경로와 격리(AC1, M-7).
- **approvalId별 최초 확정 결정(D-OPS-005, H-1)**: PAYMENT는 실행 수락·업무 거절을 결정 행(INSERT-only, UNIQUE(approval_id), 내용 hash)으로 TX1과 같은 TX에 남기고 같은 approvalId에는 항상 같은 결정을 돌려준다. **불변식: 409·404는 커밋된 결정 행이 뒷받침할 때만, 400은 같은 바이트면 항상 같은 결과일 때만, 그 밖은 503**(§4.4).
- **ADMIN 쪽**: 승인 틀에 소유 서비스 종류 `OPERATOR_CORRECTION_CANCEL`(요청 OPERATOR, 승인 APPROVER + 직전 TOTP)을 더하고 기존 단일 TX 경로는 실행 모델로 갈라 소유 서비스 종류를 fail-closed로 막는다(M-6). 요청 때 대상 고정, 승인 때 **TX 밖** 재조회·revision 비교·`oct1:` 계산, 승인 TX(→`EXECUTING`) → TX 밖 서명 호출 → 결과 TX. `APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`는 종료가 아니다(D-OPS-007 — UNKNOWN 유지 + 무결성 경보 + 기록 결정 조회 반영). 회복 job은 기록 결정 조회 → 없으면 같은 본문 재전송(상한 없음), 참여자 정지·역할 상실이면 재전송을 멈추고 조회만(D-OPS-006).
- **기한 확인 전용 호출(D-OPS-009)**: 결정이 없는(NONE) 미해결 승인은 실행 기한이 지나면 회복 job이 실행 명령 대신 서명된 기한 확인 호출을 보낸다. PAYMENT는 자기 시각으로 `now ≥ executeBefore`이고 결정이 없을 때만 `REJECTED(APPROVAL_EXECUTION_EXPIRED)`를 기록하고 취소는 절대 시작하지 않는다 → ADMIN `OWNER_REJECTED`, 그 Payment의 미해결 차단 해제(§4.4.5). 재전송 중단(D-OPS-006) 승인도 이 경로로 정리된다.
- **대사(D-OPS-008, M-5)**: PAYMENT 결정 목록(서명 GET, watermark)과 ADMIN 승인을 주기 대조 — 지문이 맞는 승인 없는 실행, 상태 불일치를 경보·발견 행으로 남긴다.
- **계약 golden 3종**: `oct1:` 지문(S1 선행 — CANCEL-001 기록과 달리 없음, C-1), ADMIN 호출 서명, API 요청·응답·ADMIN 상태 매핑(M-4). harness가 Python 표준 라이브러리로 독립 생성하고 두 앱이 바이트·판정 대조.
- **선행 결함 수정**: 반환 가능액 음수(F-1, D-CPQ-005) → S1.
- 미결 사용자 질문 없음. 규모(§16): WEBHOOK-001(+324)의 약 **1.1~1.4배**.

---

## 1. 범위와 범위 밖

### 1.1 범위(AC 1~6 + D-OPS-005~008)

| 구분 | 내용 | AC·결정 |
|---|---|---|
| 호출 경로·인증 | 내부 connector `/internal/v1/admin/**`, HMAC 서명, ADMIN 전용 키 공간·키 분리 기동 검사, 전용 체인, 기본 체인 `/internal/**` denyAll | 1, 5(F-10), D-OPS-001 |
| 장애 비전파 | PAYMENT 관리 조회 bulkhead·질의 시간 상한·풀 대비 permit 기동 검사·connector 상한, ADMIN 외부 호출 TX 밖 강제·permit 분리 | 1, 6 |
| 진입점 | 대상 조회, 실행, 기록 결정 조회, 결정 목록 | 2, D-OPS-005·008 |
| 결정 기록 | approvalId별 최초 확정 결정(INSERT-only·UNIQUE·내용 hash·재생) | D-OPS-005 |
| ArchUnit | Use Case·조회·recorder·`startOperator`·명령 생성의 호출자 제한, 진입 컨트롤러 정확 FQN만 | 3 |
| 형식 오류 감사 | 값 타입 단계 거절 OperatorId·approvalId·본문 감사 | 4 |
| golden | `oct1:`(선행)·서명·API 계약 | 2, 5 |
| ADMIN 승인 | 소유 서비스 종류·대상 고정·TX 밖 재조회·revision·`oct1:`·APPROVER·TOTP·2-TX·미해결 UNIQUE·기존 경로 fail-closed | 2, 5, 6 |
| 회복 | `EXECUTION_UNKNOWN`·stale `EXECUTING`, 기록 결정 조회 우선, 참여자 정지 시 재전송 중단 | 6, D-OPS-003·006·007 |
| 대사 | PAYMENT 결정 목록 ↔ ADMIN 승인 주기 대조·경보 | D-OPS-008 |
| 기한 확인 | 결정 없는 미해결 승인의 기한 경과 확인·`APPROVAL_EXECUTION_EXPIRED` 결정 기록·ADMIN 종결 | D-OPS-009 |
| 실행 기한·목적 근거 | D-OPS-002, D-OPS-004 | 2 |
| React | 결제 조회·요청 화면, 승인 화면 확장, 대사 발견 표시 | 2 |

### 1.2 범위 밖

| 항목 | 소유·근거 |
|---|---|
| 운영 mTLS·운영 시각 창·NTP·KMS·응답 서명 | LIVE Gate(D-OPS-001, §13) |
| 목적 근거 원천 대조 | LIVE Gate 차단 조건(D-OPS-004) |
| APPROVER 인원·관리자 보안 수치 | Q-ADMIN-APPROVER-STAFF·Q-ADMIN-SEC-VALUES(LIVE Gate) |
| 결제 목록·검색, 카드 정보 조회, 마스킹 해제 첫 화면 | 후속 |
| 다른 소유 서비스 명령 | 후속 작업 — 일반화만 재사용 가능하게 |
| 취소 실행·회복 로직 자체 | CANCEL-001 그대로 |
| 대사 발견의 자동 정정 | 하지 않음 — 경보·발견 행만(D-OPS-008). ADMIN 상태 수렴은 회복 job의 기록 결정 반영만 |
| Kafka·Redis | 도입 안 함(§9) |

---

## 2. AC 대응표

| # | AC 요지 | 절 | 계획 시험(이름 가역적) |
|---|---|---|---|
| 1 | 호출 경로·서비스 간 인증·관리 조회 장애 비전파 | §4.1·§4.2·§4.7 | `AdminCallSignatureSpec`, `AdminChannelSecurityChainSpec`, `InternalPathDenySpec`, `AdminChannelKeySeparationSpec`, `AdminReadIsolationSpec`(실제 연결 점유), `AdminChannelPoolBudgetSpec`, ADMIN `PaymentCallOutsideTransactionSpec` |
| 2 | 진입점·인증, `OperatorCancelTargetQuery`, `oct1:` ADMIN 대조, revision 변경 시 무효, APPROVER·TOTP | §4.3~§4.6·§4.9~§4.14·§4.19 | `AdminOperatorCancellationApiSpec`, `OperatorDecisionRecordSpec`, `OperatorCancelTargetApiSpec`, `OperatorCancelFingerprintGoldenSpec`, `OwnerApprovalTargetChangedSpec`, `OwnerApprovalStepUpSpec` |
| 3 | ArchUnit 완화(인증된 ADMIN 진입 adapter만) | §4.8 | payment `ArchitectureSpec` 규칙 11개(§4.8 표) + fixture |
| 4 | 형식 오류 OperatorId·approvalId 감사 | §4.6 | `AdminOperatorCancelMalformedAuditSpec` |
| 5 | F-4 golden 선행, F-5 스냅샷 고정·승인 시 지문·revision 재비교, F-10 서명 | §4.19·§4.13·§4.14·§4.2 | harness golden 3종, 양쪽 golden spec |
| 6 | 미해결 실행 시 새 요청 거부, `EXECUTION_UNKNOWN` 회복, 승인 전 재조회 TX 밖 | §4.11·§4.16·§4.14 | `UnresolvedApprovalRaceSpec`, `OwnerExecutionRecoverySpec`, `OwnerExecutionClaimRaceSpec`, `PaymentCallOutsideTransactionSpec` |
| D-OPS-008 | 주기 대사 | §4.17 | `OperatorDecisionReconciliationSpec`, `OperatorDecisionListApiSpec` |
| D-OPS-009 | 기한 확인 전용 호출 | §4.4.5·§4.16 | `DeadlineConfirmationApiSpec`, `DeadlineConfirmationRaceSpec`, `DeadlineConfirmationRecoverySpec` |

---

## 3. 현황 조사(2026-10-27, `43ecdc3`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것 |
|---|---|---|
| PAYMENT Use Case | `application/provided/OperatorCorrectionCancelUseCase.kt`(명령·거절 code 14종·결과 3종), `OperatorCorrectionCancelService.kt`(:23-30 TX1 뒤 새 시작이면 같은 요청 스레드에서 `dispatch`), `OperatorCancelTargetQuery.kt`(같은 서비스 :32-43) | 진입점, 서비스 간 인증, 실행 기한, 결정 기록 |
| PAYMENT 재검사 | `CancelStartService.operatorTx`(:197-232: 형식 아닌 paymentId 감사 :200-203 → 존재 :207-210 → `lockTarget` :211 → approvalId 기존 취소 **비잠금 조회** :214-227 → `startCancel` :228-231), domain `CancelStart.kt:117-121`(2인 재검사·`oct1:` 대조), `PaymentCancellation.kt:69-76` `approvalDefect` | 형식 오류 OperatorId·approvalId 감사 |
| PAYMENT TX | `LocalTransactions`(PAYMENT application) — **READ COMMITTED**(`ISOLATION_READ_COMMITTED`, :36), `CancelStartService`는 Deadlock·OptimisticConflict·UniqueConflict를 새 TX로 최대 3회 재판정(:83·:333-334) | — |
| PAYMENT 감사 | `operator_cancel_rejection`(V15 :174-185, NOT NULL ID 열, reason CHECK `^[A-Z_]{1,48}$`), V16 SELECT·INSERT | 결정 표 |
| 지문 | `domain/OperatorCancelTarget.kt:25-35` | golden |
| 내부 connector | `InternalConnectorConfiguration.kt`, `ApiEntryFilter.kt:41-44`(정규화 경로), `PaymentApiSettings.kt`(internalPort·internalAddress·retryAfter) | 스레드 상한, bulkhead |
| 보안 체인 | `PaymentApiSecurityConfiguration.kt:47`(`/internal/**`·`/actuator/health` permitAll), `CheckoutSecurityConfiguration.kt`(`@Order(HIGHEST_PRECEDENCE)`) | ADMIN 체인 |
| HMAC 선례 | `EntitlementSignature.kt`, `EntitlementInboxSettings.kt`, `HmacMerchantWebhookSigner.kt`, master `HttpEntitlementDispatcher.kt` | 경로·시각 서명 |
| ArchUnit | `ArchitectureSpec.kt:318-322·:617-624`, fixture `archfixture/operatorcancel/OperatorCancelEntryPoint.kt` | 조회·`startOperator`·명령 생성 제한 |
| ADMIN 승인 | `ApprovalKind.kt`(6종), `ApprovalStatus.kt`(소유 서비스 상태 정의), `ApprovalRequest.kt`(:34 요청자≠대상, :36 소유 서비스 상태 무조건 금지), `ApprovalParticipants.kt:11`(전역 SECURITY_ADMIN), `ApprovalService.kt`(request·approve·reject·withdraw·expireDue — 모두 단일 TX, `maxAttempts = 1`) | 실행 모델 분기 |
| ADMIN DB | `V1__admin_core.sql:109-139`(`pending_key`, kind·status CHECK), V2 열 단위 UPDATE, `admin_audit_event`(`SYSTEM_JOB` 있음) | 대상 종류·명령·실행·대사 표 |
| ADMIN 화면 | `ApprovalsScreen.tsx` 등 5개, `api/adminApi.ts` | 결제 운영 화면 |
| 설정 위치 | `.env.example`(ADMIN 키 :116-117, PAYMENT JSON 설정 :113), payment `application.yml` 주석 목록, `src/test/resources/config/application.yml`(두 앱) | 새 키·값 |
| context path | 두 앱 `application.yml`에 `server.servlet.context-path` 없음 | 금지 검사 |

### 3.2 발견

1. **F-1 반환 가능액 음수**: `OperatorCorrectionCancelService.kt:40` — FORCED 초과에서 음수. `ReturnBalance.cancellableMinor`(:170-171, max 0)·D-CPQ-005와 다름 → S1.
2. **F-2 승인 없는 Payment = 없음**: `JooqCancellationViewReader.findTarget`(:125-146)이 `payment_authorization`에서 시작 → `payment`에서 시작(S3).
3. **F-3 대상 view에 상태·hold·진행 중 취소·원 승인액 없음** → 확장(S3). hold는 운영자 경로를 막지 않음(`CancelStart.kt:96`, D-CANCEL-001).
4. **F-4 형식 오류 ID는 감사 전에 실패**(`AuthorizationIds.kt:49-53·76-80`) → placeholder 필요, reason CHECK 정규식이라 migration 불필요.
5. **F-5 `oct1:`에 승인자 포함** → ADMIN은 승인 때만 계산.
6. **F-6 `/internal/**` permitAll** → 정확 열거 + denyAll(§4.2, M-3).
7. **F-7 동기 VAN 전송** → ADMIN read timeout > 전송 예산(Demo route `total-deadline` 2초, 취소 lease 30초 — `.env.example:113`).
8. **F-8 내부 connector 스레드 상한 미설정**, Hikari 풀 공유(기본 10, 설정 없음) → §4.7.
9. **F-9 ADMIN 승인이 계정 대상 전용** → 일반화(§4.9).
10. **F-10 `pending_key`는 바꾸지 않아도 됨**(CONCAT NULL) → `unresolved_key`만 추가.
11. **F-11 키 분리 검사 모듈 경계** → `Binder`.
12. **F-12 거절이 approvalId에 묶이지 않음** → D-OPS-005로 해결(§4.4).
13. **F-13 오래된 KDoc**: `OperatorCorrectionCancelUseCase.kt:9`, `OperatorCancelTargetQuery.kt:7`, `OperatorCorrectionCancelService.kt:13`, `CancelStartService.kt:82`(운영자 재요청 판정 문장 — 결정 기록으로 바뀜), `ArchitectureSpec.kt:316-317·:623` → S3 현행화.
14. **F-14 READ COMMITTED 확인**: PAYMENT TX는 READ COMMITTED라 비잠금 조회는 **문장마다 최신 커밋**을 본다(REPEATABLE READ 스냅샷 문제 없음). 그래도 커밋 전 경쟁 TX의 행은 보이지 않으므로 결정 판정의 권위는 비잠금 조회가 아니라 **UNIQUE + 잠금 뒤 재조회 + 재시도**다(§4.4.3).
15. **F-15 기존 ADMIN 단일 TX 경로**: `ApprovalService.approve`는 종류 구분 없이 `AdminOwnedCommandExecutor.execute`로 간다. 소유 서비스 종류 행이 들어오면 지금 코드로는 계정 명령으로 오인될 수 있다 → 실행 모델 분기·fail-closed(§4.10, M-6).

### 3.3 brief 대조

| ID | brief | 코드 확인 |
|---|---|---|
| C-1 | `oct1:` golden 없음(CANCEL-001 §5.2가 만들었다고 적음) | **맞음** — harness·contracts에 0건. S1 선행 |
| C-2 | clamp :40 | 맞음 |
| C-3 | MALFORMED 감사·approvalId 조회가 stale 앞 | 맞음(:200-203, :214). stale은 domain `CancelStart.kt:117-121` |
| C-4 | approvalId 멱등 → nonce 불필요 | 시작 결과만 멱등이었음(F-12) → D-OPS-005로 해소 |
| C-5 | 대상 GET 필드 | 지금 없음(F-3) |
| C-6 | ADMIN-BOOT §4.7.2 `APPROVED`+`EXECUTING` | 코드·DB CHECK는 소유 서비스 상태 금지. 이 명세는 `APPROVED` 미사용(Δ-1) |
| C-7 | ArchUnit | 조회·`startOperator`·명령 생성 제한 없음 → §4.8 |
| C-8 | 대상 GET 404 | 승인 없는 Payment도 404(F-2) |
| C-9 | 줄 번호 | 위 표의 `43ecdc3` 값 기준 |

---

## 4. 설계

### 4.1 모듈·호출 경로

```
[ADMIN React] --(쿠키 세션·CSRF·JSON)--> internal-admin
   approval.adapter.web ──> approval.application
        ├ ApprovalService(기존 단일 TX — ADMIN_OWNED만, OWNER_SERVICE fail-closed)
        ├ OwnerServiceApprovalService(요청·승인 2-TX·결과 TX)
        ├ OwnerExecutionRecoveryService(회복 job)
        └ OperatorDecisionReconciliationService(대사 job)
          │ (TX 밖에서만)                         │ (TX 안)
          ▼                                       ▼
   paymentops.application.provided(PaymentOpsClient)   ADMIN DB(paycore_admin)
   paymentops.adapter.integration(HttpPaymentOpsGateway + AdminCallSigner)
          │  PayCore-Admin-Signature(HMAC, ADMIN 전용 키)
          ▼  내부 connector
payment-api ApiEntryFilter → AdminChannelSecurityConfiguration(/internal/v1/admin/**, 서명 → ADMIN_SERVICE)
   payment.adapter.web.admin(OperatorCancelTargetController, AdminOperatorCancellationController, OperatorDecisionController)
        → provided OperatorCancelTargetQuery / OperatorCorrectionCancelUseCase / OperatorCancelMalformedRecorder / OperatorDecisionQuery
        → CancelStartService(TX1 + 결정 행) → CancellationDispatchService(TXd + 기관 전송 TX 밖) → TX2(기존)
```

- 새 internal-admin 모듈 `paymentops`(PAYMENT 클라이언트·서명·응답 분류·대상 조회 Use Case/web). 의존 방향 `approval → paymentops.provided`, `approval → account.provided`(순환 없음).
- PAYMENT 새 패키지 `payment.adapter.web.admin`. PAYMENT는 ADMIN을 동기 호출하지 않는다(D-CANCEL1-001).

### 4.2 서비스 간 인증(D-OPS-001)

| 항목 | 값 |
|---|---|
| 헤더 | `PayCore-Admin-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 정규식 `^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$` |
| canonical | `"paycore.admin-call.v1\n" + METHOD + "\n" + 정규화 경로 + "\n" + t + "\n" + hex(SHA-256(본문 바이트))`(UTF-8, 끝 개행 없음, GET 본문 = 빈 바이트) |
| 정규화 경로 | PAYMENT `servletPath + (pathInfo ?: "")`(= `ApiEntryFilter.isInternalPath` 값). **context path 금지**(L-3): 두 앱 모두 `server.servlet.context-path`가 비어 있지 않으면 기동 거부(`AdminChannelContextPathGuard`·ADMIN `PaymentOpsSettings` 검사) — 서명 경로 = 요청 URI 경로가 되도록 |
| ADMIN 경로 생성 | 경로 변수는 형식(`pay_`·`apr_` + ULID, watermark는 10진 숫자) 검사 뒤에만 — 인코딩이 필요한 문자가 생기지 않는다 |
| query 문자열 | 있으면 서명 검사 전 400 `QUERY_NOT_ALLOWED`(미인증이라 감사 없음). 결정 목록의 watermark도 경로 변수로 받는다 |
| 본문 상한 | PAYMENT 16 KiB(서명 전, 초과 413 — `Content-Length` 확인 + 스트림을 상한+1바이트까지만 읽음, L-4) |
| 시각 창·비교 | `|now − t| ≤ 300초`(Demo, 경계 포함), `MessageDigest.isEqual` |
| 판정 순서 | 크기 → query → 헤더 문법 → 시각 창 → kid → 서명 |
| 실패 | 401 `UNAUTHENTICATED`(원인 비구별) + 지표 `paycore.payment.admin_channel.auth{result,reason}` + WARN(분류만). PAYMENT 감사 없음 |
| nonce | 없음(D-OPS-001) — 명령은 approvalId 결정 기록(D-OPS-005)으로 멱등, 조회는 부작용 없음 |
| 응답 | 서명 없음(Demo), `Cache-Control: no-store` |

**키 공간**
- PAYMENT `paycore.payment.admin-channel.signing-keys[].{key-id,secret}` — 1..2개, Base64 ≥ 32바이트, 기본값 없음, `toString` 마스킹(`AdminChannelSettings`). Demo 값: `.env.example`에 빈 변수(`PAYCORE_PAYMENT_ADMIN_CHANNEL_KEY_ID`·`…_KEY_BASE64`), 시험 키는 `apps/payment-api/src/test/resources/config/application.yml`(golden 시험 키).
- ADMIN `paycore.admin.payment-ops.{base-url,key-id,signing-key}` — 활성 키 1개, 기본값 없음, LIVE 배치 모드에서 Demo 키면 기동 실패. Demo 값: `.env.example`(`PAYCORE_ADMIN_PAYMENT_OPS_*`), 시험은 `apps/internal-admin/src/test/resources/config/application.yml`.
- **키 분리 기동 검사**(PAYMENT `AdminChannelKeySeparation`): admin-channel 키 바이트·kid가 entitlement inbox 키와 같으면 기동 거부. entitlement 키는 `Binder`로 속성만 읽음(F-11).

**보안 체인(PAYMENT, M-3)**
- `AdminChannelSecurityConfiguration`: `@Order(HIGHEST_PRECEDENCE + 1)`, `securityMatcher("/internal/v1/admin/**")`, STATELESS, CSRF 끔, Bearer 필터 없음, 서명 필터, `anyRequest().hasAuthority("ADMIN_SERVICE")`.
- 기본 체인(`PaymentApiSecurityConfiguration:47` 교체): `requestMatchers("/internal/v1/entitlement-snapshots").permitAll()`(정확 경로 — 컨트롤러 자체 HMAC 유지), **`requestMatchers("/internal/**").denyAll()`**, `/actuator/health` permitAll, 나머지 authenticated. 유효한 가맹점 Bearer로도 그 밖 내부 경로는 거부된다(denyAll은 인증 여부와 무관 — `authenticated()`였다면 Bearer로 통과).
- 공개 포트의 `/internal/**`는 `ApiEntryFilter`가 보안 체인보다 먼저 404.

### 4.3 PAYMENT 내부 API(인증된 ADMIN만)

| endpoint | 내용 | 응답 |
|---|---|---|
| `GET /internal/v1/admin/payments/{paymentId}/operator-cancel-target` | `OperatorCancelTargetQuery`(jOOQ, 비권위) | 200 `{paymentId, paymentStatus, cancellable, currency, authorizedMinor, cancellableMinor(≥0), balanceRevision, onHold, inFlightCancel}` / 404 `PAYMENT_NOT_FOUND`(형식 오류 포함, 감사 없음) / 503 `ADMIN_READ_BUSY`·`TEMPORARILY_UNAVAILABLE` |
| `POST /internal/v1/admin/operator-cancellations` | 실행(§4.4) | §4.5 표 |
| `GET /internal/v1/admin/operator-cancellations/{approvalId}` | 기록 결정 조회(L-5) | 200 `{decision:"STARTED", cancellationId, cancellationStatus, …공통}` / `{decision:"REJECTED", code, …공통}` / `{decision:"NONE"}` / 503. **공통 필드(rev 0.3 L-b)**: `paymentId, amountMinor, currency, requestedBy, approvedBy, approvedTargetFingerprint, contentHash, decidedAt, legacy`(`legacy=true` = 결정 행 없이 옛 취소 행에서 만든 STARTED — I-29) |
| `POST /internal/v1/admin/operator-cancellations/{approvalId}/deadline-confirmation` | 기한 확인 전용(D-OPS-009, §4.4.5) — 본문 = 그 승인의 **저장된 실행 명령 본문 그대로** | 200 결정 조회와 같은 모양(호출 뒤의 기록 결정) + `confirmed: true|false` / 400 형식(경로 approvalId ≠ 본문 approvalId 포함) / 503 |
| `GET /internal/v1/admin/operator-decisions/after/{watermark}` | 대사용 결정 목록(D-OPS-008, §4.17) | 200 `{items:[…≤200], nextWatermark, serverTime}` / 503 |

실행 POST 본문(엄격 JSON — L-1: 모든 필드 필수, 모르는 필드·중복 key·뒤따르는 토큰·자료형 강제 변환(문자열 숫자·소수 금액·null) 거부, 금액은 정수만):

```json
{"approvalId":"apr_…","paymentId":"pay_…","amountMinor":7000,"currency":"KRW","requestedBy":"adm_…","approvedBy":"adm_…",
 "reason":"…","purpose":{"kind":"INCIDENT_OR_RECON_CORRECTION","reference":"RECON-2026-0001"},
 "approvedTargetFingerprint":"oct1:…","approvedAt":"2026-10-27T01:02:03.456Z","executeBefore":"2026-10-28T01:02:03.456Z","correlationId":"…"}
```

Jackson 3 설정(PAYMENT admin adapter 전용 `JsonMapper`): `FAIL_ON_UNKNOWN_PROPERTIES`, `STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`, 스칼라 강제 변환 끔, `FAIL_ON_NULL_FOR_PRIMITIVES`, 정수 필드에 소수 거부. 시각은 `…T…Z` ms 3자리 형식만.

### 4.4 실행과 approvalId별 최초 확정 결정(D-OPS-005, H-1)

#### 4.4.1 저장하는 결정과 저장하지 않는 결과

| 결과 | 결정 행 | HTTP | 비고 |
|---|---|---|---|
| 시작(`Started`, existing=false) | **STARTED**(cancellationId) | 200 | 취소 행과 같은 TX1 |
| 업무 거절 전부: `OPERATOR_TARGET_STALE`, `CANCEL_IN_PROGRESS`, `CURRENCY_MISMATCH`, `ALREADY_FULLY_CANCELLED`, `AMOUNT_EXCEEDS_BALANCE`, `PARTIAL_CANCEL_NOT_SUPPORTED`, `PAYMENT_NOT_CANCELLABLE`, `OPERATOR_SAME_PERSON`, `OPERATOR_REASON_MISSING`·`_INVALID`, `OPERATOR_BASIS_MISSING`·`_INVALID`, `APPROVAL_EXECUTION_EXPIRED`, `APPROVAL_WINDOW_INVALID` | **REJECTED**(code) + 감사 1행 | 409 | 일시 거절도 승인을 소진(D-OPS-005) |
| `PAYMENT_NOT_FOUND` | **REJECTED(PAYMENT_NOT_FOUND)** + 감사 | 404 | **저장한다**(결정): paymentId는 PAYMENT가 발급하고 ADMIN이 PAYMENT 조회에서 얻은 값이라 "나중에 생기는" 경우가 없다. 저장해야 404도 불변식(커밋된 결정 뒷받침)을 만족 |
| 같은 approvalId 재요청, 내용 hash 같음 | 새 행 없음(기존 재생) | 기존 결정과 같은 status·본문(STARTED → 200 `existing:true` + 현재 취소 상태, REJECTED → 같은 409/404 code) | REJECTED 재생도 감사 1행(요청마다 — D-CANCEL1-008) |
| 같은 approvalId, 내용 hash 다름 | 새 행 없음 | 409 `APPROVAL_CONFLICT` 또는 `APPROVAL_IDENTITY_MISMATCH` | **파생 결과** — 커밋된 기존 결정 행이 뒷받침하지만 그 결정 자체가 아님. 감사 1행. ADMIN은 비종료(D-OPS-007) |
| 형식 오류(§4.6) | 없음 | 400 | 같은 바이트면 항상 같은 결과(결정 불필요). 감사 1행 |
| 저장 없는 결과: TX rollback 신호(Capability 없음·Route 불일치), 재시도 소진(1213·version·UNIQUE), commit 불명확, TX 불가, 저장소 일시 실패, busy | 없음 | 503 | 재전송하면 그때 판정 |

**불변식**: 409·404는 **커밋된 결정 행**(새로 쓴 것이든 재생한 것이든)이 뒷받침할 때만, 400은 결정적 형식 판정일 때만, 그 밖은 503. 시험 `AdminOperatorCancellationHttpMappingSpec`이 결과·예외 전수(§4.5)와 이 불변식을 함께 고정한다.

- 기존 코드의 `PAYMENT_ID_MALFORMED → NotFound` 경로(`operatorTx` :200-203)는 admin adapter가 paymentId 형식을 먼저 400으로 거르므로 HTTP로는 도달하지 않는다(Use Case 직접 호출 호환을 위해 남김, 결정 행 없음).

#### 4.4.2 내용 hash

- `contentHash = "opd1:" + hex(SHA-256(길이 접두 canonical))`, 요소마다 `<UTF-8 바이트 수>:<값>`(CC-06·`oct1:`과 같은 방식) 순서: tag `paycore.operator-decision-content`, 버전 `"1"`, approvalId, paymentId, amountMinor(10진), currency, requestedBy, approvedBy, purpose kind(`INCIDENT_OR_RECON_CORRECTION`·`MERCHANT_TERMINATED_OBLIGATION`), purpose reference, reason, approvedTargetFingerprint, approvedAt(`yyyy-MM-ddTHH:mm:ss.SSSZ`), executeBefore(같은 형식).
- **correlationId 제외**: 추적용이고 업무 의미가 없다(재전송이 다른 상관 ID를 써도 같은 명령).
- 버전 태그를 바꾸면 기존 행과 비교 불가 → 버전이 다른 저장 hash를 만나면 fail-closed 503(`UNSUPPORTED_DECISION_HASH_VERSION`, 가맹점 fingerprint 관례 `:146`과 같음).
- **기존 `compareRequest`와 관계**: 결정 행이 생긴 뒤에는 결정 hash 비교가 권위다. hash가 다를 때 code만 기존 규칙으로 고른다 — paymentId·금액이 같고 요청자·승인자만 다르면 `APPROVAL_IDENTITY_MISMATCH`(CN-32), 그 밖은 `APPROVAL_CONFLICT`. 결정 행 없이 취소 행만 있는 경우(이 작업 전 시험 데이터)는 기존 `compareRequest` 경로로 판정하고 STARTED 결정 행을 보충하지 않는다(개발 데이터만 — 운영 배포 전 운영자 취소 행 0건, §13 배포 메모).
- **옛 행 충돌의 영구 UNKNOWN(rev 0.3 L-d)**: 결정 행 없는 옛 취소 행과 approvalId가 같고 내용이 다른 ADMIN 명령은 `APPROVAL_CONFLICT`(파생) → ADMIN `EXECUTION_UNKNOWN` + 무결성 플래그(D-OPS-007). 결정 조회는 그 옛 취소 행으로 `STARTED, legacy=true`를 돌려주고, ADMIN은 §4.16 내용 대조에서 불일치를 확인해 **무결성 플래그를 유지한 채** 그 결정을 반영한다(`EXECUTED` + 플래그 + 경보 + 대사 발견 — 조용한 EXECUTED 아님). 기한 확인 호출(D-OPS-009)은 취소 행이 있으므로 **EXPIRED를 기록하지 않고** 같은 legacy STARTED를 돌려준다(취소가 실제로 있는데 거절을 기록하면 모순) — 그래서 이 경우는 D-OPS-009로 "거절 종결"할 수 없고, 플래그 달린 EXECUTED로 끝나 조사 대상이 된다. 같은 approvalId에 ADMIN·PAYMENT 내용이 다른 경우는 개발 데이터에서만 생길 수 있고(ADMIN approvalId는 ULID, 옛 행은 시험 fixture), 운영 배포 전 운영자 취소 행 0건 확인(§13)으로 운영에서는 생기지 않는다.
- PAYMENT 내부 값이라 golden 대상이 아니다. 단위 시험이 요소 누락·순서를 고정한다.

#### 4.4.3 TX1 판정 순서(`operatorTx` 교체 — L-6·D-OPS-002 포함)

1. 형식(어댑터에서 끝남) → 2. **결정 행 조회**(approvalId, 비잠금 — READ COMMITTED 최신 커밋. 있으면 4.4.2 비교 후 재생/파생 거절로 끝) → 3. Payment 존재 — 없으면 REJECTED(PAYMENT_NOT_FOUND) 결정 행 + 감사 INSERT → 4. `lockTarget`(control → payment) → 5. **결정 행 재조회**(잠금 뒤 — 같은 Payment의 경쟁 TX가 먼저 커밋했으면 여기서 보인다) → 6. 기존 취소 행 조회(approvalId, 기존 :214 — 결정 행 없는 옛 데이터 호환) → 7. **기한**(D-OPS-002: `executeBefore − approvedAt ≤ max-execution-window`·`approvedAt ≤ now + 시각 창` 아니면 `APPROVAL_WINDOW_INVALID`, `now ≥ executeBefore`면 `APPROVAL_EXECUTION_EXPIRED`) → 8. `startCancel`(기존) → 9. **Started·Rejected 두 가지 모두 같은 TX에서 결정 행 INSERT**(Started는 취소·예약·일정 INSERT 뒤, Rejected는 감사 행과 함께) → 커밋.

- 2단계의 비잠금 조회는 빠른 경로일 뿐 권위가 아니다. 권위는 **UNIQUE(approval_id) + 재시도**: 다른 Payment로 같은 approvalId가 동시에 오거나(공유 잠금 없음) 5단계 뒤 경쟁이 남으면 두 번째 INSERT가 첫 TX 커밋까지 대기 후 1062 → `PersistenceFailure.UniqueConflict` → 기존 재시도(새 TX, 최대 3회)의 2단계에서 커밋된 결정을 재생. 재시도 소진은 503.
- `CancelStartService.kt:82`의 KDoc("approvalId 업무 키로 재요청을 판정")은 "결정 기록으로 판정"으로 현행화(F-13).
- 이미 시작된 approvalId는 2·5단계에서 끝나므로 기한(7)과 무관(D-OPS-002).
- 명령 타입 `OperatorCorrectionCancelCommand`에 `approvedAt`·`executeBefore` 추가. `oct1:` canonical은 그대로(기한은 HMAC·결정 hash가 보호).

#### 4.4.4 결정 표(V27, V28)

- `V27__operator_approval_decision.sql`: `operator_approval_decision`(`seq BIGINT AUTO_INCREMENT PK`, `approval_id VARCHAR(64)` **UNIQUE**, `payment_id VARCHAR(64)`(FK 없음 — NOT_FOUND도 기록), `decision VARCHAR(16)` CHECK('STARTED','REJECTED'), `rejection_code VARCHAR(48)` NULL CHECK 정규식, `cancellation_id VARCHAR(64)` NULL FK → `payment_cancellation`, CHECK `(decision='STARTED' AND cancellation_id IS NOT NULL AND rejection_code IS NULL) OR (decision='REJECTED' AND cancellation_id IS NULL AND rejection_code IS NOT NULL)`, `content_hash CHAR(69)`, `amount_minor`, `currency`, `requested_by`, `approved_by`, `approved_target_fingerprint VARCHAR(69)`, `approved_at`, `execute_before`, `decided_at DATETIME(3)`(TX 업무 시각 — `PaymentClock`), **`recorded_at DATETIME(3) NOT NULL DEFAULT (NOW(3))`(rev 0.3 L-a — DB가 INSERT 문 실행 시각으로 채움, Entity는 `insertable=false, updatable=false`, 세션 time_zone UTC)**, `origin VARCHAR(24)` CHECK('EXECUTION_COMMAND','DEADLINE_CONFIRMATION'), 색인 `(recorded_at, seq)`). 사유·근거 문구는 넣지 않는다(내용 hash만).
- `V28__operator_approval_decision_privileges.sql`: runtime `SELECT, INSERT`만(UPDATE·DELETE 1142).
- JPA: `JpaOperatorApprovalDecisionEntity`(`@Immutable`, `persist`만 — `save`/merge 금지), Port `OperatorApprovalDecisionStore`(application.required — Store ③ 프로토콜 기록: `find(approvalId)`, `append(decision)`; 쓰기 끝 `flush()`로 1062 분류), 구현 `JpaOperatorApprovalDecisionStore`. 값 `OperatorApprovalDecision`(application.required DTO, Rejection Store와 같은 위치 관례).

#### 4.4.5 기한 확인 전용 호출(D-OPS-009, rev 0.3 M-b)

- endpoint `POST /internal/v1/admin/operator-cancellations/{approvalId}/deadline-confirmation`. 서명 canonical은 §4.2 그대로(method `POST` + 이 경로 + 본문 hash) — 경로가 다르므로 같은 본문의 실행 명령 서명과 **바꿔 쓸 수 없다**(golden ② 사례로 고정).
- 본문 = ADMIN이 승인 TX1에서 저장한 실행 명령 본문 바이트 그대로(§4.3 엄격 JSON·형식 검사 동일, 경로 approvalId = 본문 approvalId). 그래서 기록되는 결정의 내용 hash는 같은 승인의 실행 명령과 같다 — 늦게 도착한 실행 명령은 같은 결정을 재생받는다.
- PAYMENT TX(READ COMMITTED). "`CancelStartService`와 같은 재시도 규칙"은 **같은 `LocalTransactions` 유틸리티(READ COMMITTED, Deadlock·OptimisticConflict·UniqueConflict 재판정 최대 3회)를 `OperatorDeadlineConfirmationService`가 자기 인스턴스로 쓴다**는 뜻이며 `CancelStartService`를 호출·재사용하지 않는다(rev 0.4 L-2): ① 결정 행 조회 → 있으면 그 결정을 그대로 반환(`confirmed=false`, hash가 다르면 결정은 그대로 돌려주고 감사 `APPROVAL_CONFLICT` 1행 — ADMIN은 §4.16 내용 대조로 무결성 처리) → ② approvalId의 옛 취소 행(I-29)이 있으면 legacy STARTED 반환, 기록 없음 → ③ PAYMENT 시각 `now < executeBefore`면 **아무것도 기록하지 않고** `{decision:"NONE", confirmed:false}`(감사 없음 — 업무 판정이 아님) → ④ `now ≥ executeBefore`면 `REJECTED(APPROVAL_EXECUTION_EXPIRED)` 결정 행 INSERT(`origin=DEADLINE_CONFIRMATION`) + 거절 감사 1행 → 커밋 → `{decision:"REJECTED", code:"APPROVAL_EXECUTION_EXPIRED", confirmed:true, …}`.
- **취소를 시작하지 않는다**: 이 경로는 `startCancel`·`lockTarget`을 부르지 않는다(ArchUnit `deadlineConfirmationNeverStartsCancel` — §4.8). Payment가 없어도 같다(결정은 approvalId에 대한 것).
- 진입 adapter는 정확 FQN `com.paycore.payment.payment.adapter.web.admin.OperatorDeadlineConfirmationController`, provided `ConfirmOperatorApprovalDeadlineUseCase` → `OperatorDeadlineConfirmationService`.
- **구현 주의(rev 0.4 — 검토 지적)**: InnoDB의 1062는 **그 문장만** rollback하고 TX는 살아 있다. 결정 행 INSERT(실행 TX·기한 확인 TX 모두)의 1062·그 밖 쓰기 실패는 Port가 `PersistenceFailure.UniqueConflict` 등으로 던지고 **절대 잡아서 같은 TX를 계속하지 않는다** — TX 전체 rollback 후 `LocalTransactions` 재시도가 새 TX에서 커밋된 결정을 재생한다. 실행 TX에서 결정 INSERT가 실패한 채 커밋되면 "결정 없는 취소"가 남으므로 이것은 안전 불변식이다. 시험: 결정 Store 대역이 1062를 던질 때 취소·예약·일정·감사 행 0(커밋 없음)을 확인, 변이 "1062를 잡고 계속" → RED.
- **늦은 실행 명령과 경합**: 실행 TX가 기한 전에 시작해(`now` = TX 시작 시각) 시작을 판정하는 동안 기한 확인 TX가 EXPIRED를 쓰면, 둘 다 결정 행을 INSERT하므로 **UNIQUE(approval_id)가 정한다**. 확인이 먼저 커밋되면 실행 TX의 결정 INSERT가 1062 → 실행 TX 전체 rollback(취소·예약도 함께 사라짐 — 결정과 같은 TX라 결정 없는 취소는 남지 않는다) → 재시도에서 EXPIRED 재생(409). 실행이 먼저 커밋되면 확인 TX가 1062 → 재시도에서 STARTED를 돌려준다(ADMIN `EXECUTED`). 어느 쪽이든 결정 1행, 결과 일치.
- 실행 명령 재전송이 아니므로 D-OPS-006(참여자 정지 시 재전송 중단)과 충돌하지 않는다.

### 4.5 HTTP 매핑 전수(PAYMENT, M-4)

| 원인 | HTTP | 본문 |
|---|---|---|
| Accepted(새·재생) | 200 | `{cancellationId, status, existing}` |
| Rejected(업무 거절·재생) | 409 | `{error: code}` |
| Rejected(`APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH` 파생) | 409 | `{error: code}` |
| NotFound(`PAYMENT_NOT_FOUND` 결정) | 404 | `{error: "PAYMENT_NOT_FOUND"}` |
| 형식 오류(§4.6) | 400 | `{error: code}` |
| `CancelPaymentFailure.TemporarilyUnavailable`(모든 reason) | 503 | `{error: "TEMPORARILY_UNAVAILABLE"}` |
| `CancelPaymentFailure.OutcomeUnknown` | 503 | `{error: "OUTCOME_UNKNOWN"}` |
| 명령 permit 없음(실행·기한 확인 공용) | 503 | `{error: "ADMIN_COMMAND_BUSY"}` |
| 형식 오류 감사 저장 실패 | 503 | `{error: "TEMPORARILY_UNAVAILABLE"}` |
| 예상 못한 예외 | 503 | `{error: "TEMPORARILY_UNAVAILABLE"}`(500으로 내보내지 않음 — ADMIN은 UNKNOWN으로 재전송·조회) |
| 기한 확인: 기록 결정(새 EXPIRED·기존 결정·legacy) 또는 NONE | 200 | 결정 조회 모양 + `confirmed`(§4.4.5) — 이 endpoint는 409/404를 쓰지 않는다 |
| 기한 확인: 형식 오류 | 400 | `{error: code}` + 감사(§4.6과 같은 표) |
| 기한 확인: 저장 실패·재시도 소진·permit 없음 | 503 | 위와 같은 code |

- 시험 `AdminOperatorCancellationHttpMappingSpec`: 위 모든 행 + `OperatorCancelRejectionCode` 전 값이 409(또는 NOT_FOUND 404)로 가는지 enum 전수 반복, 매핑 누락 enum 추가 시 실패. 계약 golden(§4.19 ③)의 PAYMENT 응답 예와 바이트·필드 대조.

### 4.6 형식 오류 감사(AC4)

- 대상: 서명 통과한 실행 POST의 값 타입·본문 단계 거절. 미인증은 감사 없음.
- admin adapter 형식(ADMIN 계약 — 값 타입보다 엄격): approvalId `^apr_[0-9A-HJKMNP-TV-Z]{26}$`, OperatorId `^adm_[0-9A-HJKMNP-TV-Z]{26}$`, paymentId `^pay_[0-9A-HJKMNP-TV-Z]{26}$`, 지문 `^oct1:[0-9a-f]{64}$`, 통화 `^[A-Z]{3}$`, 금액 정수 1..Long.MAX, 시각 형식, 목적 종류 2종, 근거·사유 형식(§4.18 — **앞뒤 공백은 잘라 내지 않고 거부**, Unicode 정규화 없음 — L-2).
- 기록: provided `OperatorCancelMalformedRecorder` → `OperatorCancelMalformedAuditService`(자기 로컬 TX) → `OperatorCancelRejectionStore.append`. 원문 미저장·미로그.

| 상황 | rejection_reason | approval_id | payment_id | requested_by / approved_by | HTTP |
|---|---|---|---|---|---|
| JSON·구조·자료형·지문·통화·금액·시각·목적·근거·사유 형식 | `REQUEST_MALFORMED` | 형식 맞으면 값, 아니면 `MALFORMED` | 같음 | 같음 | 400 |
| approvalId만 형식 아님 | `APPROVAL_ID_MALFORMED` | `MALFORMED` | 값 | 값 | 400 |
| paymentId만 형식 아님 | `PAYMENT_ID_MALFORMED` | 값 | `MALFORMED` | 값 | 400 |
| requestedBy·approvedBy 형식 아님 | `OPERATOR_ID_MALFORMED` | 값 | 값 | 아닌 쪽 `MALFORMED` | 400 |
| 둘 이상 | `REQUEST_MALFORMED` | 위 규칙 | 위 규칙 | 위 규칙 | 400 |
| 감사 저장 실패 | — | — | — | — | 503 |

- 형식 오류는 결정 행을 만들지 않는다(§4.4.1). ADMIN은 고정 본문을 보내므로 400은 ADMIN 결함 → `OWNER_REJECTED` + 무결성 경보(§4.15).
- 새 거절 code: `REQUEST_MALFORMED`, `APPROVAL_ID_MALFORMED`, `OPERATOR_ID_MALFORMED`, `APPROVAL_EXECUTION_EXPIRED`, `APPROVAL_WINDOW_INVALID`(모두 ≤ 48자, migration 없음).

### 4.7 장애 비전파(AC1, M-7)

PAYMENT:
- 관리 조회(대상·결정·결정 목록): 공용 `Semaphore`(Demo 4, 대기 0 → 503 `ADMIN_READ_BUSY`). 실행 POST **와 기한 확인 POST**: 같은 명령 `Semaphore`(Demo 2, 대기 0 → 503 `ADMIN_COMMAND_BUSY`) — 기한 확인도 쓰기 TX(결정·감사 INSERT)라 명령 permit을 쓴다(rev 0.4 L-3).
- **질의 시간 상한 방식**: MySQL 옵티마이저 hint `/*+ MAX_EXECUTION_TIME(2000) */`를 관리 조회 SELECT에 jOOQ `.hint(...)`로 붙인다(서버가 강제, 초과 시 ER 3024 → 503). S3에서 실제 MySQL 8.4.11로 (a) hint가 적용됨(`SELECT SLEEP(3)`을 같은 질의 형태로 감싼 시험 질의가 2초에 3024), (b) 연결이 풀에 정상 반환됨을 실측해 기록한다. 실패하면 대안 = JDBC `Statement.setQueryTimeout`(jOOQ `queryTimeout`, Connector/J가 `KILL QUERY`)을 같은 시험으로 검증 후 채택(구현 결정 I-09).
- **풀 대비 permit 기동 검사**(`AdminChannelPoolBudget`): `readPermits + commandPermits ≤ hikari.maximumPoolSize − 공개 경로 예약(Demo 4)`(commandPermits는 실행 POST·기한 확인 공용 — 별도 permit을 만들지 않으므로 합계식에 빠지는 쓰기 경로가 없다, rev 0.4 L-3) 아니면 기동 거부. 풀 크기는 실행 중 `HikariDataSource.getMaximumPoolSize()`로 읽는다(설정 미지정 기본 10). 시험 `AdminChannelPoolBudgetSpec`.
- 내부 connector `maxThreads`·`acceptCount`(Demo 16·16)를 `PaymentApiSettings`에 추가하고 connector에 직접 설정(Boot customizer 적용 여부는 S3에서 실측해 기록 — F-8). 관리 permit 합(6) < 16이라 Entitlement inbox 스레드를 굶기지 않는다.
- 시험 `AdminReadIsolationSpec`(실제 HTTP 두 connector, 실제 연결 점유): 시험 전용 `@Primary` Reader 위임이 관리 조회마다 **실제 DB 연결에서 `SELECT SLEEP(1.5)`를 읽기 TX 안에서 실행**해 permit 4개가 연결 4개를 실제로 붙잡게 만든다(대안: 시험 연결이 대상 행을 `FOR UPDATE`로 잡고 관리 조회가 그 행을 잠금 읽기 — 사용하지 않으면 기록). 그 상태에서 공개 포트 CreatePayment·confirm을 각 20건 동시 실행 → 전부 성공(5xx·timeout 0건), 지연 p50·p95를 기준선(포화 없음)과 함께 보고서에 기록(합격 기준: 성공률 100%, p95가 시험 클라이언트 timeout의 50% 미만). 추가 관리 조회는 즉시 503(대기 0 확인: 응답 시간 < 200ms).

ADMIN:
- PAYMENT 호출은 DB TX 밖에서만: `HttpPaymentOpsGateway`가 `TransactionSynchronizationManager.isActualTransactionActive()`면 `IllegalStateException`(시험 `PaymentCallOutsideTransactionSpec`). ArchUnit: `paymentops.provided` 클라이언트 Port를 부르는 클래스는 `approval.application`의 지정 서비스 4개·`paymentops.application`뿐.
- 외부 호출 permit을 **용도별로 분리**(L-8): 승인 A 단계·요청 TX 전 조회용 `approval` pool(Demo 4), 화면 대상 조회용 `lookup` pool(Demo 4) + actor별 조회 속도 제한(Demo 30회/분, 초과 429), 실행 POST·회복·대사용 `execution` pool(Demo 4). VIEWER 조회 폭주가 승인 A 단계를 막지 못한다. 초과 503.
- timeout: connect 2초, read 30초(Demo, F-7), 응답 본문 읽기 상한 64 KiB(초과 = 응답 해석 불가 → UNKNOWN, L-4). 로그인·세션·감사 경로는 PAYMENT를 부르지 않는다(시험: PAYMENT 대역 정지 중 로그인 200).
- HTTP 클라이언트 `RestClient` + `JdkClientHttpRequestFactory`(`proxy(NO_PROXY)`, redirect `NEVER`).

### 4.8 ArchUnit(AC3, M-2)

| 규칙 | 허용 의존(그 밖 main 클래스 금지) |
|---|---|
| `operatorCorrectionCancelOnlyFromAdminEntry` | `OperatorCorrectionCancelUseCase` ← 자신, `OperatorCorrectionCancelService`, 정확 FQN `com.paycore.payment.payment.adapter.web.admin.AdminOperatorCancellationController` |
| `operatorCancelTargetQueryOnlyFromAdminEntry` | `OperatorCancelTargetQuery` ← 자신, 서비스, `…adapter.web.admin.OperatorCancelTargetController` |
| `operatorDecisionQueryOnlyFromAdminEntry` | `OperatorDecisionQuery` ← 자신, 구현 서비스, `…adapter.web.admin.OperatorDecisionController` |
| `operatorCancelMalformedRecorderOnlyFromAdminEntry` | recorder ← 자신, 구현 서비스, `AdminOperatorCancellationController`, `OperatorDeadlineConfirmationController`(기한 확인 형식 오류 감사) |
| `deadlineConfirmationOnlyFromAdminEntry`(rev 0.4 L-1) | `ConfirmOperatorApprovalDeadlineUseCase` ← 자신, `OperatorDeadlineConfirmationService`, 정확 FQN `…adapter.web.admin.OperatorDeadlineConfirmationController` |
| `deadlineConfirmationNeverStartsCancel`(rev 0.4 L-2) | 대상 = **정확히 `OperatorDeadlineConfirmationService`**(+ 합성 중첩 클래스). 금지 호출(메서드 수준): `PaymentCancellationRepository`의 `insertNew`·`lock`·`update`(쓰기·잠금 계열 — 조회 `findIdByApprovalId`·`find`는 허용), `ReturnBalanceRepository`의 `insertNew`·`update`·`lockByPaymentId`, `CancellationRecoveryScheduleStore.insert`, `PaymentCancellation.open`, `ReturnBalance.open`·`reserve`. 금지 의존(클래스 수준): `CancelStart`, `CancelStartService`, `CancellationDispatchService`, 대상 잠금·Payment·승인 저장소 `OrderPaymentControlRepository`·`PaymentRepository`·`PaymentAuthorizationRepository`(S3 review L-1 — fixture `DeadlineConfirmationLockingTarget.kt`) |
| `startOperatorOnlyFromOperatorService` | `CancelStartService.startOperator` 호출 ← `OperatorCorrectionCancelService`만 |
| `operatorServiceConcreteOnlyFromSpringConfig` | 구체 클래스 `OperatorCorrectionCancelService`·`OperatorCancelMalformedAuditService`에 의존 ← 자신과 Spring 구성(`@Configuration` 클래스)뿐(다른 클래스는 provided interface로만) |
| `operatorCommandConstructedOnlyAtEntry` | `OperatorCorrectionCancelCommand`·`CancelInitiator.Operator`를 **만드는 모든 호출** — 생성자, data class `copy`·`copy$default`, companion·top-level 팩토리, 그 타입을 반환하는 그 밖의 메서드(rev 0.3 L-c: `callMethodWhere`로 "owner가 두 타입이고 이름이 `copy`·`copy$default`이거나 반환 타입이 두 타입" + `callConstructorWhere` owner 두 타입) ← `AdminOperatorCancellationController`, `OperatorCorrectionCancelService`·`CancelStartService`(기존 :204), 두 타입 자신(합성 `copy` 구현), domain 내부, persistence Mapper(`reconstitute` 경로 — 기존). fixture `OperatorCommandFactory.kt`(생성자)·`OperatorCommandCopier.kt`(`copy`) 둘 다 위반 |
| `adminEntryControllersNotReferenced` | 진입 컨트롤러 4개(`OperatorCancelTargetController`·`AdminOperatorCancellationController`·`OperatorDecisionController`·`OperatorDeadlineConfirmationController`)에 의존하는 main 클래스 0개 |
| `adminEntryMappedUnderAdminPath` | 같은 4개 컨트롤러는 `@RestController`, 클래스 `@RequestMapping` 값이 모두 `/internal/v1/admin/`로 시작 |

- 정확 FQN 비교는 Kotlin이 만드는 **합성 중첩 클래스**(`$Companion`, `$WhenMappings`, 람다 `$…$1` 등 — 이름이 `<FQN>$`로 시작하는 클래스)를 같은 허용으로 본다(`haveNameMatching("^" + quote(FQN) + "(\\$.*)?$")`).
- 위반 fixture(각 규칙 최소 1개): 기존 `archfixture/operatorcancel/OperatorCancelEntryPoint.kt` 유지(여전히 실패), `…/operatorcancel/web/admin/AdminOperatorCancellationController.kt`(같은 단순 이름·다른 FQN), `…/PublicPathOperatorCancelController.kt`(`/v1/…`), `…/TargetQueryCaller.kt`, `…/DecisionQueryCaller.kt`, `…/RecorderCaller.kt`, `…/DirectStartOperatorCaller.kt`, `…/ConcreteServiceCaller.kt`, `…/OperatorCommandFactory.kt`, `…/OperatorCommandCopier.kt`, `…/ControllerReferrer.kt`, `…/DeadlineConfirmationCaller.kt`(L-1), `…/DeadlineConfirmationStartingCancel.kt`(L-2 — 같은 FQN이 아니므로 fixture는 **규칙 대상 이름을 fixture 클래스로 바꾼 변형 규칙**으로 검사: 규칙 함수가 대상 FQN을 인자로 받는다). 각 fixture가 해당 규칙에서만 위반이 나는지 확인.
- 규칙 수는 **11개**(위 표 행 수 — §2·§7.1과 같은 수, rev 0.4 L-1·L-2: 기한 확인 규칙 2개를 표 안으로 옮김).
- 기대값 변경 기록: 기존 시험 이름 "nothing in main calls … (no entry point)" → "only the authenticated admin entry adapter calls …". 허용은 정확 FQN 1개씩만 늘었다(Reviewer diff 대상).

### 4.9 ADMIN 승인 일반화(domain)

- `ApprovalKind`에 `executionModel`(`ADMIN_OWNED`·`OWNER_SERVICE`)·`requesterRole`·`targetKind`. 6종 = `ADMIN_OWNED`·SECURITY_ADMIN·`ADMIN_ACCOUNT`, `OPERATOR_CORRECTION_CANCEL` = `OWNER_SERVICE`·OPERATOR·`PAYMENT`. 승인자 APPROVER.
- sealed `ApprovalTarget`: `AdminAccount(actorId, securityVersion)` / `Payment(paymentId, balanceRevision)`. 대상 버전은 기존 `target_security_version` 열(Payment는 balanceRevision).
- `ApprovalRequest` 불변식: `status ∈ OWNER_SERVICE_ONLY ⇒ executionModel == OWNER_SERVICE`, `targetKind == kind.targetKind`, 요청자≠대상은 `AdminAccount`만. 소유 서비스 전이: `beginExecution`(PENDING → EXECUTING), `executed`·`ownerRejected`(EXECUTING·EXECUTION_UNKNOWN → 종료), `executionUnknown`(EXECUTING → EXECUTION_UNKNOWN). EXECUTING 이후 거절·철회·만료·무효 불가. 종료 상태는 `ALREADY_DECIDED`. `APPROVED` 미사용.
- `ApprovalParticipants`: 요청자 역할 = `kind.requesterRole`, 2인 규칙(Payment 대상은 요청자≠승인자).
- 새 순수 domain: `OperatorCancelRequestSnapshot`, `OwnerExecution`(본문·지문·approvedAt·executeBefore·시도 수·다음 시도·마지막 시도·마지막 응답 시각·마지막 실패 분류·무결성 플래그·재전송 중단 플래그·소유 결과 code·소유 참조).
- `oct1:` 계산 `paymentops.domain.OperatorCancelTargetFingerprint`(PAYMENT와 독립 구현, UTF-16 유효성 선검사).

### 4.10 기존 단일 TX 경로의 실행 모델 분기(M-6)

| 경로 | ADMIN_OWNED | OWNER_SERVICE(`OPERATOR_CORRECTION_CANCEL`) |
|---|---|---|
| `ApprovalService.request` | 기존 | **거부** `WRONG_EXECUTION_MODEL`(400, 감사 없음 — 종류 해석 단계) — 새 endpoint만 생성 |
| `ApprovalService.approve`(`POST /api/approvals/{id}/approve`) | 기존 단일 TX | 컨트롤러가 0단계 비잠금 불변 열(종류)로 `OwnerServiceApprovalService.approve`로 보낸다. **`ApprovalService.approveInTransaction` 안에서 잠근 행의 종류가 OWNER_SERVICE면 rollback·500 `INTERNAL_INTEGRITY`·별도 TX 감사**(fail-closed — 계정 명령 실행기로 절대 가지 않음) |
| `reject`·`withdraw` | 기존 | 같은 TX 구조, PENDING만, 요청자 역할 = `kind.requesterRole`(OPERATOR). EXECUTING 이후 `ALREADY_DECIDED` |
| `expireDue` | 기존 | PENDING만(EXECUTING·UNKNOWN은 만료 대상 아님 — 질의 조건에 status=PENDING 유지 확인) |
| 감사 행 | target_actor_id | **target_ref = paymentId**(요청·거부·거절·철회·만료·무효·실행 중·실행·소유 거절·불명·재전송·재전송 중단·대사 발견 — 모든 행) |

- 시험 `ApprovalExecutionModelGuardSpec`: OWNER_SERVICE 행을 기존 승인 경로에 강제 주입(root 연결로 0단계 뒤 종류 변경 포함) → 상태·계정·TOTP 불변 + 감사 1행 + 500. 변이: 분기 제거 → 시험 RED.
- `AuditTargetRefSpec`: 새 종류의 모든 감사 행에 target_ref 존재, target_actor_id NULL.

### 4.11 ADMIN DB(V5 schema, V6 권한)

**V5__owner_service_approval.sql**
- `admin_approval_request`: `target_kind VARCHAR(16) NOT NULL DEFAULT 'ADMIN_ACCOUNT'` → 채움 → `DROP DEFAULT`; `target_ref VARCHAR(64) NULL`; `target_actor_id` NULL 허용; kind CHECK에 `OPERATOR_CORRECTION_CANCEL`; status CHECK에 `EXECUTING`·`EXECUTION_UNKNOWN`; `ck_…_target`(대상 종류별 열 조합 + 종류 일치); `ck_…_owner_states`(`status NOT IN ('EXECUTING','EXECUTION_UNKNOWN') OR kind='OPERATOR_CORRECTION_CANCEL'`); 생성 열 `unresolved_key = CASE WHEN target_kind='PAYMENT' AND status IN ('PENDING','EXECUTING','EXECUTION_UNKNOWN') THEN CONCAT('PAYMENT:', target_ref) END STORED` + UNIQUE; 색인 `(target_kind, target_ref)`. `pending_key` 그대로(F-10).
- `admin_operator_cancel_command`(INSERT-only): `approval_id` PK·FK, `payment_id`, `amount_minor`(>0), `currency`, `purpose_kind`, `purpose_ref`, `reason`, **`pinned_target JSON`(권위 고정 값 — 승인 TX1 비교의 기준)**, `created_at`. 승인 행 `target_snapshot`에는 같은 JSON을 목록 표시용 사본으로 넣는다(권위 아님 — 시험이 두 값 같음 확인, nit).
- `admin_owner_execution`: `approval_id` PK·FK; 불변 `request_body MEDIUMBLOB`, `fingerprint`, `approved_at`, `execute_before`, `executor_actor_id`; 변경 `attempt_count`, `next_attempt_at`, `last_attempt_at`, `last_response_at`, `last_failure VARCHAR(48)`, `integrity_flag VARCHAR(48)`, `resend_halted_reason VARCHAR(48)`, `owner_result_code`, `owner_reference`, `version`(L-4). 색인 `(next_attempt_at)`.
- `admin_ops_reconciliation_checkpoint`(단일 행 id=1, CHECK): `watermark BIGINT`, `last_run_at`, `last_full_scan_at`, `version`.
- `admin_ops_reconciliation_finding`(INSERT-only): `finding_id` PK, `kind`(CHECK §4.17), `approval_id`, `payment_ref`, `detail_code`, `found_at`, UNIQUE `(kind, approval_id)`(같은 발견 반복 INSERT 없음 — 경보 폭주 방지).
- `admin_audit_event`: `target_ref VARCHAR(64) NULL`.
- MySQL 8.4에서 생성 열 추가·NULL 변경은 표 재작성 — 개발 볼륨 작음, 운영 전 확인(R-4).

**V6__owner_service_approval_privileges.sql**: 명령·발견 표 `SELECT, INSERT`; 실행 표 `SELECT, INSERT` + 열 단위 UPDATE(변경 열 목록 = Hibernate updatable 열); checkpoint `SELECT` + 열 단위 UPDATE(`watermark, last_run_at, last_full_scan_at, version`) — 행은 V5 migration이 INSERT; 기존 표 권한 변경 없음(관례 §19).

### 4.12 ADMIN 요청(OPERATOR) — `POST /api/approvals/operator-correction-cancel`

1. 사전 검사(TX 없음, 400 `INVALID_INPUT`): paymentId 형식, 금액 > 0, 목적 종류, 근거(§4.18), 사유 1~500자·제어 문자 없음(줄바꿈 허용)·카드 번호 패턴 거부(I-17). 앞뒤 공백 있으면 거부(서버는 자르지 않음 — 화면이 입력 시 정리). 세션 역할 OPERATOR 아니면 403.
2. TX 밖 대상 조회(`approval` pool). 실패 → 503 `PAYMENT_UNAVAILABLE`(저장 없음). 404 → 400 `PAYMENT_NOT_FOUND` + 별도 TX 감사.
3. advisory: `cancellable`, 금액 ≤ `cancellableMinor`, **`inFlightCancel=false`**(L-7 — 진행 중 취소가 있으면 409 `CANCEL_IN_PROGRESS_ADVISORY`, 감사 별도 TX). D-OPS-005로 일시 거절이 승인을 소진하므로 미리 막는다.
4. TX: 승인 행 INSERT(`unresolved_key` 1062 → 409 `DUPLICATE_UNRESOLVED`; **1213 → 409 `RETRY_LATER`**, L-9) → 요청자 계정 `FOR UPDATE`(ACTIVE·OPERATOR·세대) → 명령 행 INSERT → 감사 → 커밋. 거부 감사는 별도 TX.

### 4.13 ADMIN 승인(APPROVER + TOTP) — 2-TX

| 단계 | 위치 | 내용 | 실패 시 |
|---|---|---|---|
| A0 | TX 없음, **비권위** | 세션 역할 APPROVER 확인, 승인 행 비잠금 읽기(종류·상태 PENDING). 상태를 바꾸지 않으며 권위 판정은 B에서 다시(nit) | 403 / 404 / `ALREADY_DECIDED` |
| A | TX 밖 | PAYMENT 대상 재조회(`approval` pool). `inFlightCancel=true`면 **409 `TARGET_BUSY`, 승인 PENDING 유지, TOTP 검증·소비 없음**(L-7) | 503 — 상태 변경·TOTP 소비 없음 |
| B(TX1) | 로컬 TX | 승인 행 `FOR UPDATE` → 판정표 → 요청자·승인자 계정 오름차순 `FOR UPDATE` → 승인자 재검사(403·상태 불변) → TOTP 검증·소비(실패는 별도 TX 계수) → 요청자 재검사(상실 → `INVALIDATED(REQUESTER_NOT_ELIGIBLE)`) → **명령 행 `pinned_target` vs A 값**: revision·통화 다름, `cancellable=false`, 금액 > `cancellableMinor` → `INVALIDATED(TARGET_CHANGED)`(TOTP 소비 함께 커밋) → `oct1:` 계산 → 본문 생성(approvedAt = TX 시각, executeBefore = + 기한) → 실행 행 INSERT(`next_attempt_at` = now + stale 기준) → 승인 행 `EXECUTING` → 감사 → 커밋 | 1213·1205 → 409 `RETRY_LATER`. commit 불명확 → 재조회 수렴 |
| C | TX 밖 | 저장 본문 + 새 `t`로 POST(`execution` pool) | — |
| D(TX2) | 로컬 TX | 승인 행 `FOR UPDATE` → 상태 EXECUTING·UNKNOWN일 때만 §4.15 적용(이미 종료면 무변경, 결과 다르면 `outcome_divergence`) → 실행 행 갱신(`last_response_at`) → 감사 → 커밋 | TX2 실패 → EXECUTING 유지, job 이어받음. 응답 `{status: EXECUTING}` |

- `EXECUTED` = PAYMENT가 그 승인으로 취소를 내구 시작했다는 기록 결정(STARTED). 금융 결과는 PAYMENT가 추적하고 화면은 결정 조회의 `cancellationStatus`로 보인다.

### 4.14 ADMIN 잠금 순서

**승인 요청 행 → 계정 행(actor ID 오름차순) → 실행 행 → (대사) checkpoint 행**. 요청: 승인 행 INSERT → 요청자. TX1: 승인 → 계정 → 실행 INSERT. TX2·회복: 승인 → 실행. 회복 선점: 승인·실행(`SKIP LOCKED`). 대사: checkpoint만(승인 행 잠금 없음 — 비잠금 읽기, 발견 INSERT). 1213·1205 → 전체 rollback, 409 또는 다음 주기. 외부 호출 중 잠금 없음.

### 4.15 결과 매핑(ADMIN — D-OPS-003·007)

| PAYMENT 응답 | ADMIN 상태 | 추가 |
|---|---|---|
| 200 실행 POST / 결정 조회 `STARTED` | `EXECUTED`(owner_reference = cancellationId) | |
| 409 업무 거절 code(재생 포함) / 결정 조회 `REJECTED(code)` / 404 `PAYMENT_NOT_FOUND` | `OWNER_REJECTED(code)` | `APPROVAL_WINDOW_INVALID`는 무결성 경보(설정 순서 위반 — L-6) |
| 409 `APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH` | **`EXECUTION_UNKNOWN` 유지**(EXECUTING이면 UNKNOWN으로) + `integrity_flag` + 무결성 경보 | 회복 job은 이 승인에 POST를 다시 보내지 않고 결정 조회만 해 **PAYMENT 기록 결정을 반영**(D-OPS-007 — 아래 내용 대조 규칙). 대사가 지문 불일치를 따로 경보 |
| 400 형식 code | `OWNER_REJECTED(code)` + 무결성 경보 | 결정적 형식 판정(같은 바이트 = 같은 결과) |
| 결정 조회 `NONE` | 상태 유지 | 회복 job이 재전송 또는 기한 확인 대상으로 둠(§4.16) |
| 기한 확인 `REJECTED(APPROVAL_EXECUTION_EXPIRED)`(`confirmed=true` 또는 재생) | `OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)` → `unresolved_key` NULL(종료 상태) → **그 Payment의 새 승인 요청 차단 해제** | D-OPS-009 |
| 기한 확인 `NONE`(`confirmed=false` — PAYMENT 시각상 기한 전) | 상태 유지 | 다음 주기에 다시 확인(ADMIN·PAYMENT 시각 차이) |
| 기한 확인이 `STARTED`·다른 `REJECTED`를 돌려줌 | 결정 조회와 같은 규칙으로 반영 | 늦은 실행 명령이 이긴 경우(§4.4.5) |
| 401·413·415·503·5xx·timeout·연결 실패·응답 해석 불가·본문 상한 초과 | `EXECUTION_UNKNOWN` | 401은 경보 |

**기록 결정 반영 전 내용 대조(rev 0.3 L-b)**: 결정 조회·기한 확인 응답의 `paymentId·amountMinor·currency·requestedBy·approvedBy·approvedTargetFingerprint`를 ADMIN 명령 행·승인 행·실행 행(`fingerprint`)과 비교한다.
- 모두 같으면 반영(위 표).
- 하나라도 다르면 `integrity_flag = DECISION_CONTENT_MISMATCH`를 **먼저 설정**하고 무결성 경보·감사(`APPROVAL_INTEGRITY_MISMATCH`, target_ref)를 남긴 뒤 기록 결정을 반영한다(D-OPS-007 "PAYMENT 기록 결정 그대로 반영" — 그 approvalId로 PAYMENT가 실제 한 일이 ADMIN 종료 상태가 된다). 종료 상태에도 플래그가 남아 화면·대사(`FINGERPRINT_MISMATCH` 발견)에 계속 보인다 — **조용한 EXECUTED 없음**. 반영을 보류하면 그 Payment의 미해결 차단이 영원히 풀리지 않으므로 보류하지 않는다(구현 결정 I-35).

### 4.16 회복 job(D-OPS-003·006·007)

- `approval.adapter.batch.OwnerExecutionRecoveryScheduler`(`@Scheduled`) → provided `RecoverOwnerExecutionsUseCase` → `OwnerExecutionRecoveryService`.
- 선점(짧은 TX): Store `claimDue(now, batch)` native `FOR UPDATE SKIP LOCKED`(승인 행 status ∈ {EXECUTING, EXECUTION_UNKNOWN} ⋈ 실행 행 `next_attempt_at ≤ now`) → `attempt_count+1`, `last_attempt_at`, `next_attempt_at = now + backoff`.
- 처리(TX 밖) 순서:
  1. **결정 조회**(서명 GET). `STARTED`·`REJECTED` → 내용 대조(§4.15) 뒤 TX2 반영. `NONE` → 2로.
  2. **기한 경과(D-OPS-009)**: ADMIN 시각 `now ≥ executeBefore + 시각 여유(Demo 5분 — PAYMENT 시각이 늦을 때 헛호출 줄임)`이면 실행 명령을 보내지 않고 **기한 확인 호출**(저장 본문 그대로, §4.4.5) → 응답을 §4.15대로 반영. 재전송 중단(3단계)·무결성 플래그(4단계)와 무관하게 이 단계가 먼저다(실행 명령 재전송이 아니므로 D-OPS-006과 충돌 없음). `NONE`(PAYMENT 시각상 기한 전)이면 다음 주기.
  3. **참여자 재검사(D-OPS-006)**: 요청자(ACTIVE·OPERATOR)·승인자(ACTIVE·APPROVER). 정지·역할 상실이면 POST 안 함 → `EXECUTION_UNKNOWN` 유지(EXECUTING이면 UNKNOWN으로), `resend_halted_reason` 기록, 경보·감사 `APPROVAL_RESEND_HALTED`(SYSTEM_JOB) 1회. 이후 주기에도 1단계 조회는 계속하고, 기한이 지나면 2단계 기한 확인으로 `OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)` 종결·Payment 차단 해제. 참여자가 다시 자격을 얻어도 재전송을 재개하지 않는다(I-26).
  4. `integrity_flag`가 있으면 POST 안 함(D-OPS-007) → 다음 주기 조회.
  5. 그 밖 → 같은 본문 POST → TX2 반영.
- 결과적으로 결정이 없는 미해결 승인은 **기한 + 시각 여유 + 한 backoff 주기 안에 반드시 종료**된다(PAYMENT가 응답하는 한). 남는 장기 UNKNOWN은 PAYMENT 장애·401(키·시각)·옛 행 충돌(§4.4.2 L-d — 그 경우도 플래그 달린 EXECUTED로 종료) 뿐이다.

**TX2 갱신 규칙(rev 0.3 L-e — 웹 스레드 TX2·회복 TX2 공통)**
- `attempt_count`는 **"명령 시도 기회 수"**다(I-105): 승인 TX1이 1로 INSERT하고, 회복 선점 TX만 +1 한다(그 주기에 결정 조회만 하고 끝나도, permit 부족으로 POST를 못 보내도 +1). **TX2는 바꾸지 않는다.** 실제로 보낸 명령은 `paycore.admin.ops.resend{result}`(`EXECUTE_*`·`DEADLINE_*`)와 `last_failure`로 구별한다.
- `last_response_at` = TX2 시각(응답을 받았거나 실패 분류를 정한 시각), `last_failure` = UNKNOWN 분류 code(종료면 NULL로 두지 않고 마지막 값 유지 — 열 단위 UPDATE 대상).
- `next_attempt_at`: 종료 반영이면 바꾸지 않는다(선점 조건이 상태로 걸러짐). UNKNOWN이면 `max(현재 값, TX2 시각 + backoff(attempt_count))`로 **앞으로만** 민다(웹 TX2가 job의 예정 시각을 당기지 않게, 늦은 TX2가 이미 민 값을 되돌리지 않게).
- 상태 갱신은 승인 행 잠금 아래 `EXECUTING`·`EXECUTION_UNKNOWN`일 때만(§4.13 D).
- backoff Demo 30초×2·상한 10분·시도 상한 없음. stale 기준 = connect + read timeout + 30초. 감사 actor_kind `SYSTEM_JOB`, 실행자 = 원 승인자.
- 설정 순서 기동 검사(L-6, ADMIN): `stale 기준 < backoff 상한 < execution-window`, `execution-window ≤ paycore.admin.payment-ops.payment-max-execution-window`(PAYMENT 설정값을 운영자가 같이 적는 거울 값 — 다르면 PAYMENT가 `APPROVAL_WINDOW_INVALID`로 거절하고 경보). PAYMENT: `max-execution-window ≥ 1분`, 시각 창 < `max-execution-window`.
- 지표: `paycore.admin.ops.unresolved{status}`, `paycore.admin.ops.unknown.oldest_age_seconds`(**가장 오래된 EXECUTION_UNKNOWN 승인의 승인 시각(`decided_at`) 이후 경과** — 마지막 응답·마지막 시도 기준이 아니다, S6 리뷰 L-4. 경보 Demo 15분), `paycore.admin.ops.resend{result}`, `paycore.admin.ops.resend_halted{reason}`, `paycore.admin.ops.integrity_alert{code}`, `paycore.admin.ops.outcome_divergence`.

### 4.17 대사 job(D-OPS-008, M-5)

- `approval.adapter.batch.OperatorDecisionReconciliationScheduler`(Demo 5분) → provided `ReconcileOperatorDecisionsUseCase` → `OperatorDecisionReconciliationService`.
- **PAYMENT 결정 목록**: `GET /internal/v1/admin/operator-decisions/after/{watermark}` — `seq > watermark` 오름차순 최대 200행, 항목 `{seq, approvalId, paymentId, decision, code, cancellationId, amountMinor, currency, requestedBy, approvedBy, approvedTargetFingerprint, contentHash, decidedAt, recordedAt, origin}`(사유·근거 문구 없음). 조회 permit 공유(§4.7).
- **안정 watermark(rev 0.3 L-a)**: `serverTime`은 같은 질의에서 읽은 **DB 시각**(`SELECT NOW(3)` — 앱 시계 아님), 판정 기준 열은 DB가 INSERT 때 채운 `recorded_at`(업무 시각 `decided_at` 아님). `nextWatermark` = 반환 행을 seq 순으로 볼 때 `recorded_at ≤ serverTime − 안정 지연(Demo 2분)`인 앞쪽 연속 구간의 마지막 seq(없으면 요청 watermark 그대로). AUTO_INCREMENT는 커밋 순서와 다를 수 있고 커밋 전 행은 보이지 않으므로, INSERT 뒤 안정 지연보다 늦게 커밋된 행은 증분 대사에서 건너뛸 수 있다 — 이것은 **일 1회 전체 스캔이 최종 안전망**으로 잡는다(아래). TX1은 짧은 로컬 TX라 2분 초과 커밋 지연은 장애 상황뿐이다.
- ADMIN 처리: checkpoint 행 잠금 없이 읽기 → 목록 페이지 반복(실행당 최대 10페이지) → 항목마다 ADMIN 승인 행·명령·실행 행 비잠금 조회 → 판정 → 발견 INSERT(UNIQUE(kind, approval_id) **1062는 "이미 기록된 발견"으로 정상 무시** — 오류·재시도 아님, 지표 `recon.finding_duplicate`만, nit) → checkpoint `watermark` 갱신(열 단위 UPDATE, 짧은 TX).
- 판정(발견 kind):

| kind | 조건 |
|---|---|
| `ORPHAN_DECISION` | PAYMENT 결정의 approvalId에 해당하는 ADMIN 승인 없음, 또는 종류가 `OPERATOR_CORRECTION_CANCEL` 아님 |
| `FINGERPRINT_MISMATCH` | ADMIN 실행 행 `fingerprint` ≠ PAYMENT `approvedTargetFingerprint`, 또는 paymentId·금액·통화·요청자·승인자 불일치(지문이 맞는 승인 없는 실행) |
| `STATE_DIVERGENCE` | ADMIN 종료 상태와 PAYMENT 결정이 다름(EXECUTED vs REJECTED, OWNER_REJECTED vs STARTED, OWNER_REJECTED code ≠ PAYMENT code, EXECUTED owner_reference ≠ cancellationId) |
| `MISSING_DECISION` | ADMIN `EXECUTED`·`OWNER_REJECTED`(PAYMENT **400 응답으로 끝난 승인 제외** — 결정 행이 없는 것이 정상, I-120)이고 실행 행 **마지막 응답 시각**(`last_response_at`, decided_at 아님)이 `전체 스캔 시작 − (안정 지연 + 시각 여유 deadline-skew)`보다 이른데, 해당 approvalId의 PAYMENT 결정이 전체 스캔에서 없음(S7 리뷰 M-1) |
| `UNRESOLVED_WITH_DECISION` | ADMIN EXECUTING·UNKNOWN인데 PAYMENT 결정이 안정 지연 + 회복 backoff 상한보다 오래됨(회복이 반영하지 못함) |
| `DECISION_FOR_UNAPPROVED`(rev 0.3 M-a) | ADMIN 승인 상태 ∉ {EXECUTING, EXECUTION_UNKNOWN, EXECUTED, OWNER_REJECTED}(= PENDING·REJECTED·WITHDRAWN·EXPIRED·INVALIDATED — 승인되어 실행되지 않은 승인)인데 PAYMENT 결정이 있음. 실행 행이 없으므로 지문 대신 **승인·명령 행의 paymentId·amountMinor·currency·requestedBy**를 PAYMENT 결정과 비교해 `detail_code`에 불일치 열 이름(`PAYMENT_ID`·`AMOUNT`·`CURRENCY`·`REQUESTER`, 모두 같으면 `CONTENT_MATCH`)을 남긴다. 어느 경우든 발견이다(ADMIN이 승인하지 않은 approvalId로 PAYMENT 결정이 생김 — 키 유출·ADMIN 결함 의심). 승인자는 실행 행이 없으므로 비교 불가 |

- 판정 우선순위: `ORPHAN_DECISION` → `DECISION_FOR_UNAPPROVED` → `FINGERPRINT_MISMATCH` → `STATE_DIVERGENCE` → `UNRESOLVED_WITH_DECISION`(한 항목에 여러 발견이 맞으면 앞의 것 하나 + 나머지는 같은 행 detail에).
- **전체 스캔(최종 안전망)**: 하루 1회(Demo) seq 0부터 끝까지 별도 순회(다음 페이지 기준 = 읽은 페이지의 **최대 seq** — 전체 스캔은 watermark를 저장하지 않으므로 안정 watermark에 묶이지 않는다, S7 리뷰 M-1(b)) — **checkpoint watermark를 되돌리거나 바꾸지 않는다**(`last_full_scan_at`만 갱신). 증분이 건너뛴 늦은 커밋 행과 `MISSING_DECISION`을 잡는다. Demo 데이터 규모 전제(운영 주기·규모는 LIVE Gate).
- 대사는 **상태를 바꾸지 않는다**(경보·발견·감사 `OPS_RECON_FINDING` SYSTEM_JOB + target_ref). ADMIN 상태 수렴은 회복 job의 기록 결정 반영만.
- 지표: `paycore.admin.ops.recon.run{result}`, `paycore.admin.ops.recon.finding{kind}`, `paycore.admin.ops.recon.lag_seconds`(마지막 **완전 성공** 실행 — PAYMENT 장애·항목 판정 오류·미완료 전체 스캔이 없는 실행 — 이후 경과, `last_run_at`도 그때만 갱신(S7 리뷰 L-4, I-123). 경보 Demo 30분), `paycore.admin.ops.recon.open_findings{kind}`.
- 화면: 승인 화면 상단에 열린 발견 수(SECURITY_ADMIN·APPROVER), 발견 목록 조회(jOOQ Reader).

### 4.18 목적 근거·사유 형식(D-OPS-004, L-2)

- 근거: 1~200자(UTF-16 단위), 올바른 UTF-16, 허용 = Unicode 문자·숫자·U+0020·`- _ . : / #`, **첫·끝 문자가 공백이면 거부(자르지 않음)**, 줄바꿈·제어·형식·사설·미할당 문자 거부, **Unicode 정규화 하지 않음**(양쪽 — 받은 코드 포인트 그대로 저장·지문). ADMIN 요청 검사와 PAYMENT admin adapter 검사가 같다(위반 시 PAYMENT 400 `REQUEST_MALFORMED` + 감사).
- 사유: 1~500자, 줄바꿈 허용, 그 밖 제어 문자 거부, 앞뒤 공백 거부, 카드 번호 패턴 거부(ADMIN), 정규화 없음.
- PAYMENT domain `approvalDefect`(:69-76)는 그대로(기존 행 `reconstitute` 영향 회피).
- 원천 대조는 LIVE Gate 차단 조건. 화면에 "근거 원천 미대조(Demo)".

### 4.19 계약 golden 3종(공유 코드 없음)

① **`operator-cancel-target-oct1.golden.json`**(S1 선행) — `harness/tests/test_operator_cancel_target_golden.py`. 벡터: ASCII, 한글 + 보조 평면 근거, `MERCHANT_TERMINATED_OBLIGATION`, revision 0·`Long.MAX_VALUE`, USD, 요청자·승인자 교환, 금액 1, 다른 approvalId. payment `OperatorCancelTargetGoldenSpec`(S1), admin `OperatorCancelFingerprintGoldenSpec`(S4).

② **`admin-call-signature-v1.golden.json`**(S2) — `test_admin_call_signature_golden.py`. 서명 벡터(대상 GET·실행 POST·결정 GET·목록 GET·**기한 확인 POST**), 검증 사례(±300/±301, 모르는 kid, 본문·경로·method 변경, 문법 오류, query 문자열, 두 번째 키, **같은 본문의 실행 POST 서명을 기한 확인 경로에 붙임 → SIGNATURE 거절, 그 반대도**). payment `AdminCallSignatureGoldenSpec`, admin `AdminCallSignerGoldenSpec`.

③ **`admin-payment-ops-api-v1.golden.json`**(S3 — M-4) — `test_admin_payment_ops_api_golden.py`. 내용: 실행 POST 요청 본문 예(정상·한글 사유·부분 금액), 엄격 JSON 거부 예(중복 key·뒤 토큰·문자열 금액·소수·모르는 필드 → 400 `REQUEST_MALFORMED`), **응답 사례마다 `{httpStatus, body, expectedAdminState, expectedIntegrityAlert}`**: 200 새/재생 → EXECUTED, 409 각 거절 code → OWNER_REJECTED, 409 CONFLICT·IDENTITY_MISMATCH → EXECUTION_UNKNOWN+경보, 404 → OWNER_REJECTED, 400 → OWNER_REJECTED+경보, 401·503 각 code → EXECUTION_UNKNOWN; 결정 조회 응답 3종(공통 필드 포함 — L-b, 내용 불일치 사례 → 반영 + `DECISION_CONTENT_MISMATCH` 플래그); **기한 확인 응답 4종**(`confirmed=true` EXPIRED → OWNER_REJECTED·차단 해제, NONE → 유지, 기존 STARTED → EXECUTED, 기존 REJECTED 재생 → OWNER_REJECTED); 결정 목록 응답 1페이지.
  **실행마다 달라지는 값은 자리표시**(nit): `cancellationId`·`decidedAt`·`recordedAt`·`seq`·`serverTime`·`nextWatermark`·서명 `t`는 golden에 `{"$placeholder":"CANCELLATION_ID"}` 형태로 두고 각 자리표시마다 정규식(예: `^pcn_[0-9A-HJKMNP-TV-Z]{26}$`, ISO-8601 ms Z, 양의 정수)을 golden에 함께 적는다. payment 시험은 실제 응답의 해당 값이 정규식에 맞는지 보고 나머지 필드는 값 일치, admin 시험은 자리표시를 고정 예시 값으로 채워 재생한다. 두 앱이 읽는다: payment `AdminOpsApiContractSpec`(실제 응답 본문이 golden과 필드·값 일치 — 시나리오별 실제 실행), admin `PaymentOpsResponseMappingGoldenSpec`(각 사례를 대역 서버로 재생 → 기대 ADMIN 상태·경보).

- 모두 `--write`는 검토된 계약 변경일 때만, 기본 실행은 두 앱 파일과 바이트 비교.

### 4.20 React 화면(D-ADMIN-008)

- **PaymentOpsScreen**: 조회(모든 역할), 대상 표시(상태·통화·원 승인액·반환 가능액·revision·hold·진행 중 취소 — 진행 중이면 요청 버튼 비활성 + 이유), 요청 양식(OPERATOR만 — 금액 기본값 = 반환 가능액 전액, 부분 허용, 목적·근거, 사유, 카드 정보 경고, 입력 앞뒤 공백 정리 후 전송).
- **ApprovalsScreen 확장**: 고정 값 vs 현재 값, 다르면 "승인 시 무효" 경고, `TARGET_BUSY` 안내, TOTP 필수, 결과(`EXECUTED` + 취소 상태, `OWNER_REJECTED` + code 문구, `EXECUTION_UNKNOWN` "자동 확인 중" / 재전송 중단·무결성 경보는 "조사 필요 — 수동 종료 없음" 표시), 열린 대사 발견 수·목록.
- Vitest: 역할별 버튼, 금액 기본값, 진행 중 취소 비활성, TOTP 필수, 결과·중단·경보 문구, CSRF 헤더.
- 완료 후 Demo 시연·캡처(Demo fixture만).

---

## 5. JPA·jOOQ(이번 변경에서 하는 일 → 예 → 이유 → 주의)

- **결정 행 `@Immutable` + `persist` + UNIQUE(PAYMENT)**: 하는 일 — approvalId별 첫 결정을 한 번만 쓴다. 예 — 같은 approvalId로 다른 Payment 두 요청이 동시에 오면 둘 다 INSERT를 시도하고, 두 번째는 첫 TX 커밋까지 기다렸다가 1062 → 새 TX에서 커밋된 결정을 재생해 `APPROVAL_CONFLICT`. 이유 — 두 TX는 서로 다른 Payment 행을 잠가 행 잠금으로는 직렬화되지 않는다. UNIQUE만이 둘 사이의 불변식을 지킨다. 주의 — READ COMMITTED의 비잠금 조회는 커밋 전 행을 못 보므로 판정 근거가 아니다(F-14). flush 실패 뒤 TX는 rollback 전용 → 재시도는 새 TX.
- **순수 domain + `Jpa*Entity`(ADMIN)**: `ApprovalRequest`·`OwnerExecution`이 전이를 갖고 Entity는 행 모양만. Mapper `reconstitute`가 "ADMIN 소유 종류인데 EXECUTING" 거부. 주의 — data class 금지, equals/hashCode는 ID만, toString에 본문·사유 없음.
- **`@Immutable` 명령 행**: 고정 값(금액·사유·`pinned_target`)은 DB 권한(1142)과 Hibernate 둘 다 막는다. `save`(merge) 금지.
- **dirty checking + 열 단위 권한(실행 행·checkpoint)**: 회복 job이 시도 수를 올리면 flush 때 updatable 열만 SET. `request_body`를 바꾸는 코드는 `updatable=false`·1143 이중 차단. 열 권한 목록 = updatable 열(대조 시험).
- **비관적 잠금 + 순서(§4.14)**: 두 APPROVER 동시 승인 → 승인 행에서 줄 서고 두 번째는 `ALREADY_DECIDED`(TOTP 미소비). 외부 호출 중 잠금 없음, `@Lock` JPQL 재조회 금지.
- **`@Version`(실행 행·checkpoint)**: `SKIP LOCKED` 선점 뒤 두 번째 방어. 대상 버전(balanceRevision)에 JPA version 사용 금지.
- **native `FOR UPDATE SKIP LOCKED`는 persistence에**(관례 §16) — query adapter는 읽기 전용.
- **flush/commit**: Port 끝 `flush()`로 1062(`unresolved_key`·결정 UNIQUE) 분류, commit 불명확은 재조회 수렴. 생성 열은 Entity에 매핑하지 않는다.
- **jOOQ(PAYMENT)**: `findTarget`을 `payment`에서 시작(F-2), 반환 가능액은 domain 식(F-1), `MAX_EXECUTION_TIME` hint(§4.7). `OperatorDecisionReader`(새 — 결정 조회·목록 keyset `seq > ?`). 비권위 — 실행 판정은 TX1. ArchUnit: Reader 사용처는 조회 서비스뿐.
- **jOOQ(ADMIN)**: 승인 목록·상세(명령·실행 LEFT JOIN), 대사 발견 목록, 대사 항목별 승인 조회. 명령 판정에 쓰지 않는다. JPA와 같은 TX라고 가정하지 않는다.
- **Kafka/Redis**: 결정 유일성·미해결 하나·선점·대사 watermark는 소유 DB 제약·잠금이 지킨다.

**S4에서 실제로 한 일(ADMIN — 쉽게 설명, 이번 변경에서 하는 일 → 예 → 이유 → 주의)**

1. **순수 domain과 `Jpa*Entity` 분리** — 하는 일: 승인 규칙(`ApprovalRequest`·`OwnerExecution`·`OperatorCancelRequestSnapshot`)은 JPA를 모르는 Kotlin 클래스이고, `JpaApprovalRequestEntity`·`JpaOwnerExecutionEntity`·`JpaOperatorCancelCommandEntity`는 "표의 한 행 모양"만 가진다. Mapper(Repository 안 `toDomain`)가 둘을 옮긴다. 예: DB의 `target_kind='PAYMENT'`·`target_ref='pay_…'` 행은 `ApprovalTarget.Payment(paymentId, balanceRevision)`로 바뀐다. 이유: 상태 전이 규칙(EXECUTING 이후 거절 불가 등)을 DB 없이 시험하고(ApprovalDecisionTableSpec), Hibernate 동작(지연 로딩·dirty checking)이 업무 규칙을 몰래 바꾸지 못하게 한다. 주의: Entity는 data class가 아니고 equals/hashCode는 ID만, toString에 본문·사유가 없다. 잘못된 행(ADMIN 소유 종류인데 EXECUTING)은 Mapper가 domain `reconstitute`를 부를 때 거부된다.
2. **`@Immutable` 명령 행** — 하는 일: `admin_operator_cancel_command`는 INSERT만 한다. 예: 승인 TX1이 고정 금액·`pinned_target`을 읽기만 하고, 누가 실수로 Entity 값을 바꿔도 Hibernate가 UPDATE를 만들지 않는다. 이유: 승인자가 본 고정 값이 실행 때 바뀌면 지문(`oct1:`)·대사가 무의미해진다. DB 권한(SELECT, INSERT만 — UPDATE 1142)과 Hibernate 두 겹으로 막는다. 주의: `merge`(save) 대신 `persist`만 쓴다. MySQL JSON은 키 순서를 바꿔 저장하므로 읽을 때 문자열이 아니라 필드로 해석한다.
3. **열 단위 UPDATE 권한 = Entity의 updatable 열** — 하는 일: 실행 행의 저장 본문·지문·기한·실행자는 `@Column(updatable = false)`이고 DB 열 권한도 없다(1143). 시도 수·다음 시도 등 변경 열만 runtime이 UPDATE할 수 있다. 예: TX2가 `unknownResponse`로 마지막 실패를 바꾸면 Hibernate dirty checking이 flush 때 변경 가능한 열만 SET한다. 이유: 재전송 본문이 바뀌면 같은 approvalId로 다른 명령이 나갈 수 있다(D-OPS-005 재생 동일성 붕괴). 주의: Hibernate는 updatable 열 **전체**를 SET에 넣으므로 열 권한 목록과 updatable 목록이 다르면 런타임 1143 — `AdminPersistenceMappingSpec`이 두 목록을 대조한다.
4. **생성 열 `unresolved_key` + UNIQUE** — 하는 일: Payment 하나에 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN) 승인 하나. Entity에는 매핑하지 않고 DB가 상태로 계산한다. 예: 두 운영자가 같은 Payment로 동시에 요청하면 두 번째 INSERT는 첫 TX 커밋까지 UNIQUE 검사에서 기다렸다가 1062 → `DuplicateUnresolved`(UnresolvedApprovalRaceSpec이 실제 두 연결로 확인). 이유: 서로 다른 승인 행이라 행 잠금으로는 직렬화되지 않는다 — UNIQUE만이 둘 사이 불변식을 지킨다. 주의: flush 실패 뒤 그 TX는 쓸 수 없다(새 TX로 재시도). READ COMMITTED 비잠금 조회로 "없음"을 판단하지 않는다.
5. **`@Version`(실행 행)** — 하는 일: 잠근 행의 기대 version과 다르면 `OptimisticConflict`. 예: 회복 job이 선점해 시도 수를 올린 뒤, 늦게 도착한 옛 값으로 갱신하려 하면 거부된다(OwnerServicePersistenceSpec). 이유: S6 `SKIP LOCKED` 선점 뒤 두 번째 방어. 주의: 대상 버전(balanceRevision)은 JPA version이 아니다 — `target_security_version` 열에 업무 값으로 둔다(I-13).
6. **잠금은 `find` + `refresh(PESSIMISTIC_WRITE)`** — 하는 일: TX2·회복은 승인 행 → 실행 행 순서로 잠근다(§4.14). 이유: 기존 승인 모듈 관례와 같고, 영속성 컨텍스트의 옛 값 대신 최신 커밋 값을 다시 읽는다. 주의: 외부 호출 중에는 어떤 잠금도 쥐지 않는다(S5).

**S5에서 실제로 한 일(쉽게 설명)**

- **jOOQ 조회(CQRS) — 하는 일**: 승인 목록·상세 Reader가 `target_kind`·`target_actor_id`·`target_ref`를 읽고 계정 열을 nullable로 다룬다. 예: Payment 대상 행이 섞여도 목록이 500이 되지 않는다(S4 리뷰 4 — 이전에는 `TARGET_ACTOR_ID!!`). 이유: 조회 모델은 쓰기 규칙을 모르고 행 모양만 옮긴다. 주의: 승인 명령의 0단계 경로 선택(`isOwnerService`)도 이 Reader의 비잠금 값이라 **비권위**다 — 각 서비스가 잠근 행으로 다시 판정한다(기존 경로는 소유 서비스 행이면 fail-closed).
- **비관적 잠금 순서 + TX 밖 호출 — 하는 일**: 승인 TX1은 승인 행 → 계정 행(오름차순) → 실행 행 INSERT, TX2는 승인 행 → 실행 행. PAYMENT 호출(A 조회·C 실행 POST)은 두 TX 사이에서만 한다. 예: TARGET_BUSY면 TX1을 열지 않아 TOTP가 소비되지 않는다. 이유: 외부 호출 중 잠금을 쥐면 PAYMENT 지연이 ADMIN 승인 행을 붙잡는다. 주의: gateway가 TX 안 호출을 즉시 거부한다(`PaymentCallOutsideTransactionSpec`).
- **dirty checking — 하는 일**: TX2는 잠근 실행 행의 변경 열(마지막 응답·실패·플래그·소유 결과·다음 시도)만 바꾸고 `attempt_count`는 그대로 둔다(L-e). 주의: 저장 본문은 `updatable = false` + 열 권한 1143 — 재전송은 TX1이 저장한 바이트 그대로다.
- **Kafka/Redis 없음**: Payment 하나에 미해결 하나는 DB `unresolved_key` UNIQUE, 화면 조회 속도 제한은 인스턴스 메모리(합산 한도는 LIVE Gate)다.

**S6에서 실제로 한 일(쉽게 설명)**

- **native `FOR UPDATE SKIP LOCKED` 선점 — 하는 일**: 회복 job이 "기한이 된 미해결 승인"을 잠그되, 다른 job 인스턴스가 이미 잡은 행은 기다리지 않고 건너뛴다(Port `lockDue`, persistence에만 — 관례 §16). 예: 두 인스턴스가 동시에 돌아도 한 승인은 한 번만 조회·재전송된다(`OwnerExecutionClaimRaceSpec`). 이유: JPQL `@Lock`에는 SKIP LOCKED가 없고, 기다리면 job 둘이 줄을 서 처리량이 반으로 준다. 주의: 잠근 행은 같은 TX에서 `find` + `refresh`로 다시 읽어 시도 수를 올리고 바로 커밋한다 — 외부 호출은 그 뒤(잠금 없이).
- **dirty checking + `@Version` — 하는 일**: 선점 TX가 시도 수·다음 시도만 바꾸고, 결과 반영 TX가 마지막 응답·실패·플래그·소유 결과만 바꾼다(열 단위 권한과 같은 열). 예: 웹 TX2가 늦게 와도 다음 시도를 앞당기지 못한다(도메인 `max`) — `Tx2AttemptFieldsSpec`. 주의: 저장 본문은 절대 바뀌지 않아(updatable=false + 1143) 재전송은 승인 때 바이트 그대로다.
- **JPQL 집계(비잠금) — 하는 일**: 미해결 수·가장 오래된 UNKNOWN 시각을 회복 실행마다 읽어 gauge로 낸다. 주의: 비권위 관측값이며 상태 판정에 쓰지 않는다.
- **Kafka/Redis 없음**: 선점 유일성은 행 잠금, 미해결 하나는 UNIQUE, 결정 유일성은 PAYMENT 결정 행 UNIQUE가 지킨다 — 큐·분산 lock이 대신하지 않는다.

**S7에서 실제로 한 일(쉽게 설명)**

- **단일 행 checkpoint + 열 단위 UPDATE — 하는 일**: 대사 진행 위치(watermark)를 한 행에 둔다. 갱신할 때만 `refresh(PESSIMISTIC_WRITE)`로 짧게 잠그고 `max(현재, 새 값)`으로만 바꾼다. 예: 두 인스턴스가 같은 페이지를 동시에 읽어도 watermark는 앞선 값으로 끝나고 뒤로 가지 않는다(`ReconciliationCheckpointRaceSpec`). 이유: 대사는 승인 행을 잠그지 않으므로(§4.14) 잠금 대상이 checkpoint 하나뿐이다. 주의: runtime 권한은 SELECT + 4개 열 UPDATE뿐이라 Entity의 updatable 열이 그 4개와 같아야 한다(`AdminPersistenceMappingSpec` 대조), 행 생성은 V5 migration.
- **`@Immutable` 발견 + UNIQUE 1062 정상 처리 — 하는 일**: 같은 (종류, approvalId) 발견은 한 번만 쓴다. 예: 같은 페이지를 다시 읽으면 INSERT가 1062 → 그 짧은 TX만 rollback하고 "이미 기록됨"으로 센다. 이유: 경보 폭주 방지, 여러 인스턴스 안전. 주의: flush 실패 뒤 TX는 rollback 전용이라 발견마다 별도 TX다.
- **행별 선점(native `FOR UPDATE SKIP LOCKED` + `LIMIT 1`) — 하는 일**(S7 리뷰 L-1): 회복 job이 처리 직전에 한 행만 짧은 TX로 잠가 시도 수·다음 시도 시각을 바꾸고 커밋한 뒤 처리한다. 예: 앞 행의 PAYMENT 응답이 늦어도 뒤 행은 아직 선점되지 않아 시도 수가 그대로다(`OwnerExecutionRecoverySpec`). 이유: 묶음 선점은 뒤 행의 backoff를 처리 전에 소비한다. 주의: 행마다 짧은 TX가 하나씩 늘지만 잠금 시간은 더 짧다.
- **JPQL 세타 조인으로 MISSING 후보 — 하는 일**(S7 리뷰 M-1): `JpaApprovalRequestEntity r, JpaOwnerExecutionEntity e WHERE e.approvalId = r.approvalId AND e.lastResponseAt < :cutoff` — 연관 매핑 없이 같은 키로 조인해 "오래전에 종료된 승인"만 읽는다. 이유: 두 Entity는 Aggregate가 달라 연관을 두지 않았다(§5 위). 주의: 비잠금 읽기, Demo 규모 전제(LIVE Gate).
- **비잠금 `find`로 대조 — 하는 일**: 대사는 승인·명령·실행 행을 읽기만 하고 절대 상태를 바꾸지 않는다(변이 "대사가 상태 변경"이 RED). 주의: 읽은 값은 비권위다 — ADMIN 상태 수렴은 회복 job의 기록 결정 반영뿐이다.

---

## 6. 비노출·로그·관측

- 비노출: 서명 키·헤더 원문·요청 본문·사유·근거(로그·지표·결정 목록·대사 발견에 없음 — 저장은 ADMIN 명령 행·PAYMENT 취소 행만)·TOTP. 대상·결정 응답에 카드·기관 정보 없음. 형식 오류 원문 미저장. 오류는 고정 code. 시험 `AdminChannelNonDisclosureSpec`·`PaymentOpsNonDisclosureSpec`.
- 로그: `operation·correlationId·approvalId·result·failure`. paymentId는 로그만(지표 label 금지).
- 지표: PAYMENT `paycore.payment.admin_channel.auth{result,reason}`, `…request{endpoint,result}`, `…busy{endpoint}`, `…decision{decision,replayed}`(I-134 — STARTED·REJECTED × true·false·unknown), `…query_timeout{endpoint}`. ADMIN §4.16·§4.17 목록 + `paycore.admin.ops.call{operation,result}`, `paycore.admin.ops.lookup_rate_limited`, `paycore.admin.ops.recon.open_findings{kind}`, `paycore.admin.approval.justification_rate_limited`, 승인 `paycore.admin.approval{operation,kind,result}`·`…retry_later{operation}`·`…integrity_violation{check}`. 이름·label 키는 `AdminChannelMetricsContractSpec`(PAYMENT)·`AdminOpsMetricsContractSpec`(ADMIN)가 고정한다(S9).
- 비노출 canary 묶음(S9): 기존 `PaymentOpsNonDisclosureSpec`·`AdminChannelNonDisclosureSpec`에 S6~S8 경로(회복 job·거부된 근거 조회·발견 조회·감사 행·발견 행)를 더했고, 화면은 근거·사유가 브라우저 저장소·URL·제목에 남지 않음을 Vitest로 확인한다. 발견 detail은 code 정규식만 받는다(I-128).

---

## 7. 시험 계획(실제 MySQL 8.4.11·실제 HTTP)

### 7.1 PAYMENT

- golden·단위: `OperatorCancelTargetGoldenSpec`(S1), `AdminCallSignatureSpec`·golden, `AdminChannelSettingsSpec`, `AdminChannelKeySeparationSpec`, `AdminChannelContextPathGuardSpec`, `AdminChannelPoolBudgetSpec`, `OperatorDecisionContentHashSpec`(요소 누락·순서·correlationId 무관·버전).
- 보안 체인: `AdminChannelSecurityChainSpec`(handler mapping 전수 무서명 401, 공개 포트 404, query 400, 본문 초과 413), `InternalPathDenySpec`(M-3 — 실서버 내부 포트: 무인증·**유효한 가맹점 Bearer** 모두 모르는 `/internal/x` → **401 `UNAUTHENTICATED`**(Bearer 필터가 내부 경로를 검사하지 않아 익명, 기존 `UnauthenticatedEntryPoint` 본문), **인증 주체가 있으면 403**(실제 보안 필터 체인을 붙인 MockMvc — denyAll ↔ `authenticated()` 변이를 잡는 곳), Entitlement 정확 경로는 컨트롤러 HMAC 판정 유지 — checkpoint L-2).
- 엄격 JSON(L-1): 중복 key·뒤 토큰·문자열 숫자·소수 금액·null·모르는 필드 → 400 + 감사.
- 실행·결정(H-1): `AdminOperatorCancellationApiSpec` — 전액·부분, hold 중 시작, P1, 같은 사람·사유·근거·stale 거절, 재전송 `existing:true`(기한 뒤 포함), 기한 초과 `APPROVAL_EXECUTION_EXPIRED`, `APPROVAL_WINDOW_INVALID` 2종, 통화·초과. `OperatorDecisionRecordSpec`:
  - **일시 거절 → 조건 해소 → 재전송 = 같은 거절**: 가맹점 취소 진행 중 `CANCEL_IN_PROGRESS` 거절 → 그 취소 완료 → 같은 본문 재전송 → 409 `CANCEL_IN_PROGRESS`, 취소 행 0, 결정 행 1, 감사 행 2.
  - 같은 approvalId 다른 내용 → `APPROVAL_CONFLICT`/`IDENTITY_MISMATCH`, 결정 행 불변.
  - `PAYMENT_NOT_FOUND` 결정 저장·재생.
  - 결정 행 없는 옛 취소 행 호환 경로.
- 경합(실제 두 연결, 시작 장벽):
  - **같은 approvalId, 거절 TX vs 시작 TX**(같은 Payment — 한쪽은 stale 지문, 한쪽은 유효 — 두 요청이 같은 approvalId라 내용 hash가 다르게 구성): 결정 행 정확히 1개, 두 번째는 재생/충돌, 취소 행 ≤ 1.
  - 같은 approvalId·다른 Payment 동시 → 결정 1, 한쪽 `APPROVAL_CONFLICT`.
  - 같은 approvalId 같은 본문 동시 → 결정 1·취소 1, 다른 쪽 `existing:true`.
  - TX1 중간 실패(일정 INSERT 실패) → 취소·예약·결정·감사 0, 503.
  - commit 불명확 → 503 `OUTCOME_UNKNOWN` → 재전송 재생.
  - 응답 유실(응답 전 연결 종료) → 재전송 재생.
- 매핑: `AdminOperatorCancellationHttpMappingSpec`(§4.5 전수·불변식), `AdminOpsApiContractSpec`(golden ③).
- 조회: `OperatorCancelTargetApiSpec`(F-1·F-2·F-3), `OperatorDecisionApiSpec`(STARTED·REJECTED·NONE, 공통 필드 L-b, legacy STARTED), `OperatorDecisionListApiSpec`(keyset·200행 상한·`recorded_at`이 DB 시각으로 채워짐·`serverTime` = DB 시각·안정 watermark: 시험 연결이 결정 행 INSERT 후 커밋을 안정 지연보다 오래 미룸(시험 설정 안정 지연 2초, 커밋 지연 3초) → 증분 목록에서는 건너뛰고 nextWatermark가 그 seq를 지나감을 확인 — 회수는 ADMIN 전체 스캔 시험이 확인).
- 기한 확인(D-OPS-009): `DeadlineConfirmationApiSpec` — PAYMENT 시각 기한 전 → `NONE`·결정 행 0·감사 0; 기한 후·결정 없음 → `REJECTED(APPROVAL_EXECUTION_EXPIRED)`·`confirmed=true`·결정 행 1(`origin=DEADLINE_CONFIRMATION`)·감사 1·**취소·예약 행 0**; 결정 있음(STARTED·REJECTED) → 그 결정 그대로·`confirmed=false`·새 결정 행 0; 같은 호출 반복 → 같은 결과; 그 뒤 같은 본문 실행 명령 → 409 EXPIRED 재생(취소 0); legacy 취소 행 있음 → legacy STARTED·기록 없음; Payment 없음 → EXPIRED 기록; 경로·본문 approvalId 불일치 → 400. `DeadlineConfirmationRaceSpec`(실제 두 연결, 시작 장벽): 기한 직전에 TX를 연 실행 명령 vs 기한 확인 → 결정 행 정확히 1개, 확인이 이기면 취소·예약 행 0(실행 TX 전체 rollback), 실행이 이기면 확인 응답이 STARTED.
- 형식 오류: `AdminOperatorCancelMalformedAuditSpec`(§4.6 전 행, 감사 실패 503).
- 격리: `AdminReadIsolationSpec`(§4.7 — 실제 연결 점유·지연 측정), `AdminQueryTimeoutSpec`(hint 3024 → 503·연결 반환).
- ArchUnit: §4.8 11개, 각 fixture(`copy` 우회 fixture 포함).
- 결정 INSERT 실패 전파(rev 0.4 구현 주의): `DecisionInsertFailureAbortsTxSpec` — 실행 TX·기한 확인 TX 각각 결정 Store 대역 1062 → 커밋된 행 0, 재시도 후 재생. **구현(S3 review L-3)**: 실행 TX는 `AdminOperatorCancellationApiSpec`(1062 주입 → 취소·예약·일정·결정·감사 0, 재전송 시작), 기한 확인 TX는 별도 대역 주입 대신 `DeadlineConfirmationRaceSpec` 실행 선승 경합(실제 1062 → 재시도 STARTED, 확인 TX의 감사 행 rollback 0)으로 대체한다.

### 7.2 ADMIN

- domain: `ApprovalDecisionTableSpec`(7종 전수, ADMIN 소유 6종 소유 서비스 상태 불가), `ApprovalTargetSpec`, `OwnerExecutionSpec`, golden 2종(①·②), `PaymentOpsResponseMappingGoldenSpec`(③), `PurposeReferenceFormatSpec`(앞뒤 공백 거부·정규화 없음), `ReasonCardDataSpec`, `OpsSettingsOrderingSpec`(L-6).
- DB: `AdminDatabaseAccountSpec`(새 표 권한 정확 비교·1142·1143·열 권한 = updatable), `AdminSchemaSpec`(CHECK 위반 주입 3819).
- 기존 경로: `ApprovalExecutionModelGuardSpec`(M-6), `AuditTargetRefSpec`.
- 요청·승인: `OwnerApprovalRequestSpec`(PAYMENT 장애 → 503·행 0, advisory·`CANCEL_IN_PROGRESS_ADVISORY`), `UnresolvedApprovalRaceSpec`(동시 INSERT → 1062 한 건, **1213 주입 → 409 `RETRY_LATER`·상태 불변**, L-9), `OwnerApprovalTargetChangedSpec`, `OwnerApprovalTargetBusySpec`(L-7 — PENDING 유지·TOTP 미소비·POST 0), `OwnerApprovalStepUpSpec`, `OwnerApprovalConcurrencySpec`, `OwnerExecutionResultSpec`(§4.15 전 행 — 실제 HTTP 대역), `OwnerExecutionTx2FailureSpec`, `PaymentCallOutsideTransactionSpec`, `LookupPermitIsolationSpec`(L-8 — `lookup` pool 포화·속도 제한 중 승인 A 단계 성공).
- 회복: `OwnerExecutionRecoverySpec`(결정 조회 우선, NONE·기한 전 → 같은 본문 POST, backoff·상한 없음), `IntegrityFlagNoResendSpec`(D-OPS-007 — CONFLICT 뒤 POST 0회, 결정 조회로 반영), `ResendHaltOnIneligibleParticipantSpec`(D-OPS-006 — 요청자 정지·승인자 역할 회수 각각: POST 0회, UNKNOWN 유지, 경보·감사 1회, 조회 계속, 자격 회복 뒤에도 재개 없음), `OwnerExecutionClaimRaceSpec`, `OutcomeDivergenceSpec`, `Tx2AttemptFieldsSpec`(L-e — 웹 TX2는 `attempt_count` 불변·`next_attempt_at` 앞으로만·`last_response_at` 기록, 늦은 TX2가 job이 민 값을 되돌리지 않음).
- 기한 확인(D-OPS-009): `DeadlineConfirmationRecoverySpec` — ① **재전송 중단 + 결정 NONE + 기한 경과 → 기한 확인 호출 → `OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)`, `unresolved_key` NULL, 같은 Payment 새 승인 요청 성공**(차단 해제), 실행 POST 0회; ② 기한 전(ADMIN 시각 + 여유 미만) → 기한 확인 호출 0회; ③ ADMIN 시각은 지났지만 PAYMENT가 `NONE`(시각 차이) → 상태 유지·다음 주기 재확인; ④ 기한 확인이 기존 STARTED를 돌려줌 → EXECUTED; ⑤ 무결성 플래그 승인도 기한 경과 시 기한 확인으로 종결(결정 있으면 그 결정).
- 내용 대조(L-b): `DecisionContentCheckSpec` — 결정의 paymentId·금액·통화·요청자·승인자·지문 중 하나씩 다르게 한 대역 응답 → `DECISION_CONTENT_MISMATCH` 플래그 설정 + 경보·감사 + 기록 결정 반영(EXECUTED/OWNER_REJECTED에 플래그 유지), 모두 같으면 플래그 없음.
- 대사: `OperatorDecisionReconciliationSpec`(발견 kind 6종 각각 주입 — **`DECISION_FOR_UNAPPROVED`는 PENDING·REJECTED·WITHDRAWN·EXPIRED·INVALIDATED 각각, detail 열 이름(`PAYMENT_ID`·`AMOUNT`·`CURRENCY`·`REQUESTER`·`CONTENT_MATCH`) 확인**, 발견 1062 정상 무시(오류·재시도 없음, 지표만), 상태 불변, watermark 전진·안정 지연, PAYMENT 장애 시 watermark 불변·lag 경보), `ReconciliationFullScanSpec`(**2분 초과 늦은 커밋 행**: 대역 결정 목록에서 증분이 건너뛴 seq를 전체 스캔이 찾아 발견 기록, 전체 스캔 뒤 checkpoint watermark 불변), `ReconciliationCheckpointRaceSpec`.
- HTTP·보안: 새 endpoint CSRF·역할, `PaymentOpsNonDisclosureSpec`.

### 7.3 harness — golden 3종 생성기·바이트 비교.

### 7.4 프론트 — §4.20.

### 7.5 핵심 변이

| 단계 | 변이 | 잡아야 할 시험 |
|---|---|---|
| S1 | clamp 제거, canonical 순서 교환, 길이 접두 = 문자 수 | `OperatorCorrectionCancelSpec`, golden |
| S2 | 경로·본문 hash 제외, 창 `<`↔`≤`, 문자열 비교, 키 분리 제거, **기본 체인 `/internal/**` denyAll → `authenticated()`**, context path 검사 제거 | `AdminCallSignatureSpec`, `AdminChannelKeySeparationSpec`, `InternalPathDenySpec`, guard spec |
| S3 | **Rejected 분기 결정 INSERT 제거**, 잠금 뒤 결정 재조회 제거(→ UNIQUE 재시도 경로만으로 통과하는지 확인), 기한을 결정 조회 앞으로, 내용 hash에서 reason 제외, 형식 오류 감사 제거, 503을 409로(커밋 결정 없는 거절), bulkhead 제거, hint 제거, ArchUnit 허용을 패키지 전체로, 엄격 JSON 옵션 하나씩 끔 | `OperatorDecisionRecordSpec`, 경합 시험, `AdminOperatorCancellationHttpMappingSpec`, `AdminReadIsolationSpec`, `AdminQueryTimeoutSpec`, `ArchitectureSpec`, 엄격 JSON 시험 |
| S4 | `owner_states` CHECK 제거, `unresolved_key`에서 UNKNOWN 제외, 실행 행 열 권한에 `request_body`, ADMIN 지문 요소 누락 | `AdminSchemaSpec`, `UnresolvedApprovalRaceSpec`, `AdminDatabaseAccountSpec`, golden |
| S5 | revision 비교 제거, TARGET_CHANGED 시 TOTP 소비 rollback, timeout → EXECUTED, CONFLICT → OWNER_REJECTED, TX 안 PAYMENT 호출, 기존 approve 경로 분기 제거, target_ref 누락, `inFlightCancel` advisory 제거 | 해당 spec |
| S3(기한 확인) | 기한 확인에서 `now ≥ executeBefore` 검사 제거, 결정 존재 검사 제거(덮어쓰기 시도), 기한 확인이 `startCancel` 호출, 기한 확인 서명 경로를 실행 경로와 같게 | `DeadlineConfirmationApiSpec`, `DeadlineConfirmationRaceSpec`, `ArchitectureSpec`, golden ② |
| S6 | `SKIP LOCKED` 제거, 시도 상한 추가, 본문 재생성, 결정 조회 생략, 참여자 재검사 제거, integrity_flag 무시, **기한 경과 시 기한 확인 대신 실행 POST**, **정지 승인에 기한 확인 생략**, 내용 대조 제거(불일치 시 플래그 없이 반영), 웹 TX2가 `next_attempt_at` 당김 | 회복 spec, `DeadlineConfirmationRecoverySpec`, `DecisionContentCheckSpec`, `Tx2AttemptFieldsSpec` |
| S7(대사) | 안정 watermark 제거(반환 max seq로 전진), `recorded_at` 대신 `decided_at`·앱 시각 사용, 전체 스캔 제거·전체 스캔이 watermark 되돌림, 발견 UNIQUE 제거, 1062를 오류로 처리, **`DECISION_FOR_UNAPPROVED` 판정 제거**, 대사가 상태 변경 | `OperatorDecisionReconciliationSpec`, `ReconciliationFullScanSpec`, `OperatorDecisionListApiSpec` |

### 7.6 Test Fixture Policy

- PAYMENT `AdminChannelFixture`(golden 시험 키), 대상 Payment는 기존 `CancellationFixture`·`ConfirmFixture`(공식 경로). ADMIN `OwnerApprovalFixture`는 요청·승인 Use Case 경로로만(직접 INSERT 금지 — 핵심 규칙 시험). PAYMENT 대역 `StubPaymentOpsServer`(실제 HTTP, golden ②·③ 사용). 대사 발견 주입은 대역의 결정 목록 응답으로. Fixture Monkey는 목록 jOOQ 대량 행만. Fixture 코드 `src/test`만.

---

## 8. 8대 원칙 완료 증거(S9 — 실제 시험·변이 이름)

| 원칙 | 적용 | 시험(실제 이름) | 핵심 변이(RED 확인) |
|---|---|---|---|
| Financial Consistency | 금액·통화 요청 고정 → `oct1:` → PAYMENT 재계산, 반환 가능액 domain 식(F-1), 금융 사실은 PAYMENT만, 화면 금액 안전 정수(I-133) | `OperatorCancelFingerprintGoldenSpec`·`PaymentOpsContractGoldenSpec`(ADMIN golden ①③), `OperatorCancelTargetGoldenSpec`·`OperatorCorrectionCancelSpec`(F-1 FORCED)(PAYMENT), `OwnerServiceApprovalFlowSpec`(TARGET_CHANGED), Vitest `OwnerApprovalDetail.compare`·`PaymentOpsScreen` | S1 clamp 제거·canonical 순서, S5 `revision_compare`, S8b `b_compare_revision`·`b_compare_amount`·`b_safe_amount`·`b_safe_response` |
| Transaction Safety | 외부 호출 TX 밖(양쪽), PAYMENT TX1 = 시작·예약·결정 행·감사 원자, ADMIN 요청·TX1·TX2 로컬 TX, 감사 실패 = 행위 실패 | `PaymentOpsGatewaySpec`(TX 안 호출 거부), `OwnerApprovalFailureInjectionSpec`, `ApprovalFailureInjectionSpec`, `AuditFailureClosesActionSpec`, `OperatorCorrectionCancelSpec`(감사·거절 같은 TX) | S5 `tx_guard`·`timeout_executed`·`target_changed_rollback` |
| Idempotency | approvalId별 결정 기록·재생(D-OPS-005), 내용 hash, 저장 본문 재전송, 미해결 승인 UNIQUE, 대사 발견 UNIQUE | `AdminOperatorCancellationApiSpec`·`OperatorDecisionContentHashSpec`·`OperatorDecisionLegacySpec`(PAYMENT), `OwnerExecutionRecoverySpec`(같은 바이트), `UnresolvedApprovalRaceSpec`, `OperatorDecisionReconciliationRunSpec`(1062 정상) | S3 결정 INSERT 제거, S6 `s6_body_regen`, S7 `s7_unique_removed`·`s7b_duplicate_as_error` |
| Concurrency Control | 결정 UNIQUE + 잠금 뒤 재조회 + 재시도, ADMIN 잠금 순서·`SKIP LOCKED`·행별 선점·`@Version`, checkpoint 단일 행 max | `DeadlineConfirmationRaceSpec`, `OwnerApprovalConcurrencySpec`, `ApprovalLockOrderSpec`, `OwnerExecutionRecoverySpec`(두 인스턴스·SKIP LOCKED·행별 선점), `OperatorDecisionReconciliationRunSpec`(checkpoint 경합) | S6 `s6_skip_locked`, S7b `s7b_batch_claim`·`s7b_no_claim` |
| Recoverability | 회복 job(결정 조회 우선·상한 없음·참여자 정지 시 조회만), 기한 확인으로 결정 없는 승인 종결(D-OPS-009), PAYMENT 본문 읽기 실패 503(I-124) | `OwnerExecutionRecoverySpec`(DeadlineConfirmationRecovery·ResendHalt·IntegrityFlag·L-1·L-2), `AdminChannelAuthenticationFilterSpec`(503) | S6 `s6_decision_skip`·`s6_deadline_post`·`s6_halted_skip_deadline`·`s6_flag_ignore`, S8 `m8_lb_payment_400` |
| Reconciliation | 결정 목록(안정 watermark + 전체 스캔) ↔ ADMIN 승인, 발견 6종, 상태 불변, 판정 오류 직전 seq까지(I-119), MISSING 후보 cutoff(I-120) | `OperatorDecisionReconciliationSpec`(domain), `OperatorDecisionReconciliationRunSpec`(14), `OperatorDecisionListApiSpec`(PAYMENT), `ReconciliationFindingHttpSpec` | S7 `s7_stable_wm`·`s7_full_scan_*`·`s7_state_change`·`s7_unapproved_removed`, S7b `s7b_missing_cutoff`·`s7b_full_scan_cursor`·`s7b_item_error_advance`, S8 `m8_lc_mark` |
| Auditability | ADMIN 새 감사 행 target_ref, 실행자 = 승인자, 회복·대사 SYSTEM_JOB, 근거 조회 시도마다 감사(I-126), 감사 증가 정책(I-112), PAYMENT 결정·거절·형식 오류 행 | `OwnerExecutionRecoverySpec`(L-1 감사 수), `OperatorCancelJustificationHttpSpec`, `AuditFailureClosesActionSpec`, `OperatorCorrectionCancelSpec` | S6 `s6_l1_audit_every_lookup`·`s6_l2_integrity_silent`, S8 `m8_justification_no_audit`·`m8_justification_role` |
| Observability | §6 지표·경보(UNKNOWN 연령·재전송 중단·무결성·대사 lag·open_findings·서명 거절·busy·결정·질의 시간 초과), 비노출 | `AdminChannelMetricsContractSpec`·`AdminOpsMetricsContractSpec`(이름·label), `OwnerOpsMetricsSpec`, `PaymentOpsNonDisclosureSpec`·`AdminChannelNonDisclosureSpec`(canary) | S8 `m8_open_gauge`, S9 지표·비노출 변이(구현자 보고) |

---

## 9. BOM·의존성·Kafka/Redis

- **새 의존성 없음(빌드 파일·카탈로그 읽기로 확인 — 해석된 의존성 트리는 S8 GRADLE-DEPS에서)**: PAYMENT JCA·Spring Security·jOOQ(`hint` 포함)·JPA·Jackson 3(기존). ADMIN `RestClient`·`JdkClientHttpRequestFactory`(spring-web 7.0.9), Jackson 3, `@Scheduled`. 프론트 새 npm 없음. Boot 4.1.1 BOM override 없음.
- Kafka/Redis 도입 안 함 — 결정 유일성·선점·대사는 소유 DB가 담당하고 큐·분산 lock은 이를 대신하지 않는다.

---

## 10. 단계별 구현 계획(시험 먼저 → RED 기록 → 최소 구현 → 핵심 변이)

| 단계 | 내용 | 선행 | 예상 시험 | allowedWritePaths(제안) |
|---|---|---|---|---|
| S1 | `oct1:` golden·PAYMENT golden spec·F-1 clamp | S0 | 8~12 + harness 3~5 | `harness/tests/test_operator_cancel_target_golden.py`, `apps/{payment-api,internal-admin}/src/test/resources/contracts/operator-cancel-target-oct1.golden.json`, `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/domain/OperatorCancelTargetGoldenSpec.kt`, `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/OperatorCorrectionCancelService.kt`, `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/application/OperatorCorrectionCancelSpec.kt`, 이 문서 |
| S2 | 서명·설정·키 분리·context path 금지·전용 체인·기본 체인 denyAll·서명 golden | S1 | 28~38 + harness 4~6 | `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/web/admin/**`, `…/adapter/web/PaymentApiSecurityConfiguration.kt`, `…/adapter/web/PaymentApiSettings.kt`, `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/adapter/web/**`, `apps/payment-api/src/main/resources/application.yml`, `apps/payment-api/src/test/resources/config/application.yml`, `.env.example`, `harness/tests/test_admin_call_signature_golden.py`, `apps/{payment-api,internal-admin}/src/test/resources/contracts/admin-call-signature-v1.golden.json`, 이 문서 |
| S3 | 결정 기록(V27·V28)·endpoint 5종(기한 확인 포함)·엄격 JSON·기한·형식 오류 감사·대상 view·HTTP 매핑·API golden·bulkhead·hint·풀 검사·connector 상한·ArchUnit·KDoc | S2 | 60~85 + harness 4~6 | S2 web·test 경로 + `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/{OperatorCorrectionCancelService,CancelStartService,OperatorCancelMalformedAuditService,OperatorDecisionQueryService}.kt`, `…/application/OperatorDeadlineConfirmationService.kt`, `…/application/provided/{OperatorCorrectionCancelUseCase,OperatorCancelTargetQuery,OperatorCancelMalformedRecorder,OperatorDecisionQuery,ConfirmOperatorApprovalDeadlineUseCase}.kt`, `…/application/required/{CancellationViewReader,OperatorApprovalDecisionStore,OperatorDecisionReader}.kt`, `…/adapter/query/{JooqCancellationViewReader,JooqOperatorDecisionReader}.kt`, `…/adapter/persistence/{JpaOperatorApprovalDecisionEntity,JpaOperatorApprovalDecisionStore,OperatorApprovalDecisionJpaRepository}.kt`, `…/adapter/web/InternalConnectorConfiguration.kt`, `apps/payment-api/src/main/resources/db/migration/payment/V27__operator_approval_decision.sql`, `…/db/runtime-privilege/payment/V28__operator_approval_decision_privileges.sql`, `apps/payment-api/src/test/kotlin/com/paycore/payment/architecture/ArchitectureSpec.kt`, `apps/payment-api/src/test/kotlin/com/paycore/archfixture/operatorcancel/**`, `apps/payment-api/src/test/kotlin/com/paycore/payment/payment/{application,adapter}/**`(관련 spec), `harness/tests/test_admin_payment_ops_api_golden.py`, `apps/{payment-api,internal-admin}/src/test/resources/contracts/admin-payment-ops-api-v1.golden.json`, 이 문서 |
| S4 | ADMIN domain 일반화·`paymentops` domain 지문·V5/V6·persistence·internal-admin ArchUnit | S1 | 42~58 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/{domain,adapter/persistence,application/required}/**`, `apps/internal-admin/src/main/kotlin/com/paycore/admin/paymentops/domain/**`, `apps/internal-admin/src/main/java/com/paycore/admin/paymentops/**/package-info.java`, `apps/internal-admin/src/main/resources/db/migration/admin/V5__owner_service_approval.sql`, `…/db/runtime-privilege/admin/V6__owner_service_approval_privileges.sql`, `apps/internal-admin/src/test/kotlin/com/paycore/admin/**`, 이 문서 |
| S5 | `paymentops` 클라이언트·permit 분리·조회 API, `approval` 요청·승인 2-TX·결과 TX·기존 경로 분기(M-6)·web, 설정·설정 순서 검사 | S2·S3·S4 | 50~68 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/paymentops/**`, `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/{application,adapter/web}/**`, `apps/internal-admin/src/main/resources/application.yml`, `apps/internal-admin/src/test/resources/config/application.yml`, `.env.example`, `apps/internal-admin/src/test/**`, 이 문서 |
| S6 | 회복 job(D-OPS-006·007 포함)·선점 Store·지표 | S5 | 18~26 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/{adapter/batch,adapter/persistence,adapter/integration,application}/**`, `apps/internal-admin/src/test/**`, 이 문서 |
| S7 | **대사 job(D-OPS-008)**·checkpoint·발견·조회 Reader·지표 | S3·S6 | 16~24 | 위 S6 경로 + `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/adapter/query/**`, 이 문서 |
| S8 | React 화면 | S5·S7 | Vitest 24~40 | `apps/internal-admin/frontend/src/**`, 이 문서 |
| S9 | 관측·비노출 묶음, 문서(관례 새 절, CANCEL-001 §5 현행화 주석, HVC §7 배포 메모), 전체 check·GRADLE-DEPS·CVE-SCAN, Demo 시연 준비 | S1~S8 | 8~15 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`, `docs/repository/CANCEL-001-CANCELLATION.md`(주석만), `docs/repository/HARNESS-VERIFICATION-CONTRACT.md`, 관련 spec, 이 문서. `harness/commands.json` 하한은 Coordinator |

- 단계마다 실제 명령·exit code·시험 개수·보고서·시각·fingerprint 기록(자기 완료 판정 아님). WSL 메모리: 모듈 단위·분리 실행.

---

## 11. 구현 결정 목록(가역적)

| ID | 결정 | 이유 |
|---|---|---|
| I-01 | `payment.adapter.web.admin`, ADMIN 새 모듈 `paymentops`, 조정은 `approval` | 순환 없음 |
| I-02 | 서명은 전용 체인 필터, 기본 체인 `/internal/**` = Entitlement 정확 permitAll + denyAll | F-6, M-3 |
| I-03 | 본문 상한 PAYMENT 16 KiB(서명 전), ADMIN 응답 읽기 상한 64 KiB | L-4 |
| I-04 | 키 분리 = `Binder`, 바이트·kid 모두 | F-11 |
| I-05 | endpoint 5종(기한 확인 포함), 결정 조회 STARTED/REJECTED/NONE | L-5, D-OPS-008·009 |
| I-06 | 기한: 결정 조회 뒤·시작 앞, PAYMENT 기간 상한·미래 approvedAt 검사 | D-OPS-002 양쪽 검사 |
| I-07 | admin adapter ID 형식 `apr_`·`adm_`·`pay_` + ULID | AC4 판정 기준 |
| I-08 | 형식 오류 감사 = recorder + 자기 TX, placeholder `MALFORMED`, 결정 행 없음 | AC4, §4.4.1 |
| I-09 | 질의 상한 = `MAX_EXECUTION_TIME` hint(S3 실측, 실패 시 JDBC queryTimeout 검증 후 대체), 풀 대비 permit 기동 검사 | M-7 |
| I-10 | ADMIN permit 용도별 분리 + 조회 속도 제한(Demo 30/분) | L-8 |
| I-11 | ADMIN HTTP `RestClient` + `JdkClientHttpRequestFactory`(NO_PROXY·redirect NEVER) | 시스템 속성 프록시 미사용 |
| I-12 | `ApprovalKind` 실행 모델·요청자 역할·대상 종류, sealed 대상 | F-9 |
| I-13 | 대상 버전 = `target_security_version` 열 | migration 최소 |
| I-14 | `unresolved_key`(PENDING 포함), `DUPLICATE_UNRESOLVED`, 1213 → `RETRY_LATER` | n-10, L-9 |
| I-15 | 감사 표 `target_ref`, 새 종류의 모든 감사 행에 기록 | M-6 |
| I-16 | 명령 행 INSERT-only(`pinned_target` 권위), 실행 행 열 단위 UPDATE, 본문 바이트 저장 | 고정 값·재전송 동일성 |
| I-17 | 사유 카드 번호 패턴 거부 | 비노출 |
| I-18 | 근거 허용 문자 집합(§4.18), 앞뒤 공백 거부, 정규화 없음 | D-OPS-004 구체화, L-2 |
| I-19 | PAYMENT 근거 형식은 admin adapter에서(domain 불변) | `reconstitute` 영향 회피 |
| I-20 | `APPROVED` 미사용 | Δ-1 |
| I-21 | 401·413·415 → UNKNOWN, 400 → OWNER_REJECTED+경보, CONFLICT·IDENTITY_MISMATCH → UNKNOWN+플래그(D-OPS-007) | §4.15 |
| I-22 | 회복 선점 `SKIP LOCKED`, backoff 30초×2·상한 10분·무제한, stale = timeout 합 + 30초, UNKNOWN 경보 15분 | 관례 §16, D-OPS-003 |
| I-23 | 대상 조회 모든 역할·요청 OPERATOR·조회 감사 없음 | ADMIN-BOOT §4.5.1·§4.6 |
| I-24 | Demo 값(LIVE Gate): ±300초, 기한 24시간, timeout·permit·backoff·대사 주기 5분·안정 지연 2분·전체 스캔 1일·lag 경보 30분 | D-OPS-001·002·008 |
| I-25 | `operator_cancel_rejection` 새 code 5종(migration 없음) | F-4 |
| I-26 | 참여자 정지로 멈춘 재전송은 자격 회복 뒤에도 재개하지 않음(조회만 계속). 기한이 지나면 기한 확인 호출(D-OPS-009)로 `OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)` 종결·Payment 차단 해제 — rev 0.3 | D-OPS-006 안전 방향, D-OPS-009 |
| I-27 | `PAYMENT_NOT_FOUND`를 결정으로 저장, 형식 오류는 미저장 | §4.4.1 |
| I-28 | 결정 내용 hash `opd1:`(correlationId 제외, 버전 불일치 fail-closed 503) | H-1(b) |
| I-29 | 결정 행 없는 옛 취소 행은 기존 `compareRequest`로 판정, 결정 보충 안 함 | 개발 데이터 호환, 운영 0건 |
| I-30 | 대사 = 안정 watermark(커밋 지연 대비) + 일 1회 전체 스캔, 상태 변경 없음, 발견 UNIQUE | D-OPS-008 |
| I-31 | 두 앱 context path 금지(기동 거부) | L-3 |
| I-32 | `inFlightCancel` advisory: 요청 409, 승인 A 409 `TARGET_BUSY`(PENDING 유지·TOTP 미소비) | L-7, D-OPS-005 소진 회피 |
| I-33 | 기한 확인 본문 = 저장된 실행 명령 본문 그대로(같은 내용 hash), 경로가 달라 서명 교차 사용 불가, 기한 전 응답은 200 `NONE`(409 아님 — 결정 없음 불변식 유지) | D-OPS-009, §4.4.5 |
| I-34 | ADMIN 기한 확인 시작 = `executeBefore + 시각 여유(Demo 5분)`, 그 뒤 매 backoff 주기 | 시각 차이 헛호출 감소 |
| I-35 | 기록 결정 내용 불일치 시 플래그 설정 후 반영(보류하지 않음) — 반영 보류는 Payment 차단 영구화 | D-OPS-007 "그대로 반영" + L-b "조용한 EXECUTED 없음" |
| I-36 | 대사 안정 watermark = DB `recorded_at`·DB `NOW(3)`, 전체 스캔은 watermark 불변, 발견 1062 정상 무시 | L-a, nit |
| I-37 | 결정 행 `origin`(실행 명령·기한 확인) 기록 | 대사·감사 구분 |
| I-38 | (S1) F-1 clamp는 새 식을 만들지 않고 domain `ReturnBalance.cancellableMinor`(D-CPQ-005, CP-QUERY-001 F-1과 같은 식)를 재사용. `oct1:` golden 9벡터는 기존 `OperatorCancelTarget.fingerprint()`와 수정 없이 일치 | 식 한 곳 |
| I-39 | (S2) HMAC 키 = 설정 비밀의 Base64 디코드 바이트(양쪽 같음), canonical의 t = 헤더 숫자 문자열 그대로(golden ②에 `hmacKey` 명시) | 계약 모호성 제거 |
| I-40 | (S2) 서명 시각 창은 코드 상수가 아니라 필수 설정 `paycore.payment.admin-channel.clock-window`(1s..10m, 시험·Demo PT300S). 기본값 없음 | Demo 값을 코드에 고정하지 않음(I-24 LIVE Gate) |
| I-41 | (S2) 판정 시각 = 서버 시계(UTC epoch 초). `PaymentClock`(application.required)은 web adapter가 쓸 수 없어(ArchitectureSpec "web adapter does not use … application.required") 필터에 시계 함수를 주입(시험만 고정) | ArchUnit 경계 유지 |
| I-42 | (S2) ADMIN 채널 오류 본문 `{"error":"<CODE>"}`(requestId는 헤더만, `WWW-Authenticate` 없음): 401 `UNAUTHENTICATED`, 400 `QUERY_NOT_ALLOWED`(빈 query `?`도 거부), 413 `REQUEST_TOO_LARGE`. 서명 헤더가 2개 이상이면 MALFORMED. 지표 `paycore.payment.admin_channel.auth{result=ACCEPTED\|REJECTED, reason=NONE\|BODY_TOO_LARGE\|QUERY_NOT_ALLOWED\|MALFORMED\|STALE\|UNKNOWN_KID\|SIGNATURE}`, WARN은 분류만(kid·헤더·서명 없음) | §4.2·§4.5·§6 구체화 |
| I-43 | (S2) 키 분리 = admin 키 각각에 대해 inbox 키와 **kid가 같거나 바이트가 같으면** 거부(OR). 메시지에 kid·키 없음 | I-04 "바이트·kid 모두" = 둘 다 검사 |
| I-44 | (S2) context path는 루트 `/`도 거부(비어 있어야만 기동) | fail-closed |
| I-45 | (S2) main `application.yml`은 다른 `paycore.*` 설정처럼 주석 목록만(자리표시 바인딩 없음), `.env.example`은 빈 변수 2개 + `SPRING_APPLICATION_JSON` 모양 예시 | 기존 관례·시험 설정 덮어쓰기와 충돌 없음 |
| I-46 | (S2) ADMIN 체인은 anonymous 끔, 서명 필터를 거치지 않은 경우의 entry point도 같은 401. `Cache-Control: no-store`는 기본 보안 헤더 유지로 충족 | §4.2 응답 |
| I-47 | (S2) S2에는 업무 endpoint가 없어 체인 통과 판정은 "MVC 라우팅(404/405)까지 도달"로 본다. 무서명 401 전수는 `RequestMappingHandlerMapping`의 `/internal/v1/admin/` handler 전부 + §4.3 5개 경로라 S3 endpoint가 생기면 자동으로 덮는다 | 단계 경계 |
| I-48 | (S2) `InternalPathDenySpec`: 실서버 내부 포트에서는 Bearer 필터가 내부 경로를 검사하지 않아 유효한 가맹점 키도 익명 → **401**(명세 §7.1의 "403"은 인증 주체가 있을 때). denyAll ↔ `authenticated()` 변이는 실제 `springSecurityFilterChain`을 붙인 MockMvc에 인증 주체를 넣어 403으로 잡는다 | 변이를 실제로 잡는 시험 |
| I-49 | (S3) 결정 UNIQUE 1062는 `StoreConstraint.OTHER`로 분류된다(`PersistenceFailure.kt`·`PersistenceFailures.kt`는 허용 경로 밖이라 전용 enum 값을 더하지 않음). 재시도 판정은 UniqueConflict 종류만 보므로 동작 같음 | 허용 경로 |
| I-50 | (S3) D-OPS-002 기한 값(`OperatorApprovalWindow` — 상한 `paycore.payment.admin-ops.max-execution-window`, 미래 approvedAt 허용 = `admin-channel.clock-window`)·목록 안정 지연(`OperatorDecisionListSettings`)·풀 대비 permit 검사(`AdminChannelPoolBudget`)는 web adapter가 required 타입·DataSource에 의존할 수 없어(ArchitectureSpec) persistence 구성 `adapter/persistence/OperatorDecisionPolicyConfiguration.kt`에서 Binder로 만든다. adapter 쪽 타입은 각자 파일(`AdminChannelPoolBudget.kt`·`OperatorDecisionPolicyConfiguration.kt` — S3 마무리에서 분리). required 값 타입 2개(`OperatorApprovalWindow`·`OperatorDecisionListSettings`)는 application.required 패키지에 있어야 해서(Port 값 — adapter로 옮기면 의존 방향이 뒤집힌다) Store·Reader Port 파일에 함께 둔다(관례 예외, required 새 파일 허용 시 분리) | ArchUnit·허용 경로 |
| I-51 | (S3) ADMIN 업무 설정 `paycore.payment.admin-ops.{max-execution-window,decision-stable-delay,read-permits,command-permits,public-pool-reserve}` 기본값 없음(Demo PT24H·PT2M·4·2·4, 시험 안정 지연 PT2S), 내부 connector `paycore.payment.api.{internal-max-threads,internal-accept-count}`(Demo 16·16)을 connector에 직접 설정 | §4.7 F-8 |
| I-52 | (S3) 질의 시간 상한 = `MAX_EXECUTION_TIME(2000)` hint로 확정(I-09 실측 `AdminQueryTimeoutSpec` — 2초 근처 중단·연결 반환·다음 질의 성공). 3024 분류는 Spring `QueryTimeoutException` 또는 서버 문구("maximum statement execution time exceeded") — query adapter는 `java.sql`에 의존하지 않는다(ArchitectureSpec) | M-7 |
| I-53 | (S3) 결정 내용 hash는 명령 타입 메서드 `OperatorCorrectionCancelCommand.contentHash()`(실행 TX·기한 확인 TX 공용 — 기한 확인 서비스는 CancelStartService에 의존하지 않는다) | L-2 |
| I-54 | (S3) 결정 조회의 형식 아닌 approvalId·목록의 형식 아닌 watermark(`^(0|[1-9][0-9]{0,18})$` 밖)는 400 `REQUEST_MALFORMED`(감사 없음 — 조회), 대상 조회의 형식 아닌 paymentId는 명세대로 404 | 결정적 판정 |
| I-55 | (S3) correlationId 형식 `^[!-~]{1,128}$`(명세 미지정 — 로그 안전). 본문 Content-Type은 검사하지 않는다(415 없음 — 본문 바이트를 엄격 JSON으로만 해석) | 가역 |
| I-56 | (S3) 형식 오류 분류: approvalId·paymentId·운영자 ID(요청자·승인자 한쪽 또는 둘 다 = 한 범주) 중 하나의 범주만 틀리면 그 code, 둘 이상이거나 그 밖 필드·JSON 오류면 `REQUEST_MALFORMED`. 기한 확인의 경로 approvalId ≠ 본문 approvalId도 `REQUEST_MALFORMED` | §4.6 표 |
| I-57 | (S3) `operatorCommandConstructedOnlyAtEntry` 허용에 명세 목록 외 정확 FQN 2개 추가: `AdminOperatorCancellationRequestParser`(명령을 실제로 만드는 엄격 해석기)·`OperatorDeadlineConfirmationController`(같은 명령 타입을 해석기에서 받아 기한 확인 Use Case에 넘김). persistence 허용은 패키지(`…adapter.persistence`) 단위(기존 Mapper 경로) | Reviewer 확인 대상 |
| I-58 | (S3) API golden ③의 PAYMENT 대조(`AdminOpsApiContractSpec`)는 실제 컨트롤러·응답 작성기에 Use Case 결과를 넣어 본문을 만들고 golden과 필드·값·자리표시 정규식으로 비교한다. 시나리오별 실제 DB 실행은 `AdminOperatorCancellationApiSpec`(20개)이 같은 본문 모양을 확인 | 범위 조정 |
| I-59 | (S3) 결정 지표 `…decision{decision,replayed}`는 S3에 없었다 — **S9에서 구현(I-134)** | 반영됨 |
| I-60 | (S3 checkpoint n-1·n-3·n-4) clock-window는 정수 초만, 필터는 context path가 있는 요청을 401(`CONTEXT_PATH_PRESENT`), 본문 읽기 I/O 실패는 400 `REQUEST_UNREADABLE`(**S7 리뷰 L-B로 503 — I-124**)(지표 reason `BODY_UNREADABLE`) | 방어 |
| I-61 | (S3) `AdminReadIsolationSpec`: 연결 점유는 시험 hook(대상 조회 직후, 요청 스레드·permit 안)이 실제 풀 연결에서 `SELECT SLEEP(6)` — @Primary Reader 대역 대신 기존 FaultInjection 대역을 써 새 Spring context를 만들지 않음(메모리). 공개 부하는 CreatePayment 20건만(confirm 20건은 결제창 인증 준비가 필요해 제외 — 남은 일). 기준선 첫 측정에는 기동 직후 지연이 섞인다 | 메모리·범위 |
| I-62 | (S3) `DeadlineConfirmationRaceSpec`의 두 경합은 장벽 위치로 순서를 강제한다: 확인 선승 = 실행 TX가 취소 INSERT 직후 대기(`CANCELLATION_INSERT`), 실행 선승 = 확인 TX가 거절 감사 INSERT 직후 대기(`OPERATOR_REJECTION_APPEND`) — 둘 다 결정 INSERT가 UNIQUE에서 만난다 | 결정적 재현 |
| I-63 | (S3 review M-1 정정) 이전 보고의 "잠금 뒤 결정 재조회 제거 변이는 UNIQUE 재시도로 성립(생존 정상)"은 **틀렸다**: 같은 Payment·같은 approvalId·다른 내용 B가 잠금 대기 뒤 재조회 없이 옛 행 호환 `compareRequest`(Payment·금액만 비교)로 가면 200 existing:true로 잘못 재생된다. 경합 시험 2개 추가(`AdminOperatorCancellationApiSpec` — 유효 시작 선커밋 → 다른 내용 409 APPROVAL_CONFLICT, stale 선거절 → 유효 409 APPROVAL_CONFLICT), 재조회는 필수 단계 | 정정 |
| I-64 | (S3 review M-2) 질의 시간 상한 시험은 상수가 아니라 **운영 Reader 메서드마다 실제 실행 SQL**(시험 jOOQ ExecuteListener `SqlCapture`)에 `MAX_EXECUTION_TIME(2000)`이 있는지 보고, 시간 시험에는 kotest timeout 15초(blockingTest)를 걸어 hint 회귀가 환경 timeout이 아니라 FAILED로 끝난다 | 시험 강화 |
| I-65 | (S3 review L-2) 기동 검사 `readPermits + commandPermits < internal-max-threads`(AdminOpsConfiguration), 기동 뒤 실제 protocol handler maxThreads·acceptCount 단언 | §4.7 F-8 |
| I-66 | (S3 review n-1) 기한 확인도 모르는 결정 hash 버전이면 fail-closed 503. 시험은 없음 — V27 CHECK(`^opd1:`)가 다른 버전 행 저장을 막아 재현 불가(실행 경로와 같은 방어 코드) | 방어 |
| I-67 | (S3 review n-4) `max-execution-window`·`decision-stable-delay`의 바인딩·범위 검증은 persistence `OperatorDecisionPolicyConfiguration` 한 곳(AdminOpsSettings에서 제거 — 출처 하나). 범위 1m..7d·0..1h | 출처 하나 |
| I-68 | (S3 review L-4) commit 불명확 시험 기대값을 `OUTCOME_UNKNOWN` 하나로 고정 — 결정 INSERT(마지막 쓰기) 직후 연결을 끊으면 블록에 남은 쓰기가 없어 첫 실패는 commit이다 | 결정성 |
| I-69 | (S4) 소유 서비스 승인의 결정자·결정 시각 = 승인(`beginExecution` — 승인자·TX1 시각). `executionUnknown`·`executed()`·`ownerRejected(code)`는 그 값을 유지하고 상태·결과 code만 바꾼다(결과 시각은 실행 행 `last_response_at`) | 실행자 = 승인자(D-ADMIN1-006), 승인 시각 한 곳 |
| I-70 | (S4 — M-6 domain 부분) 기존 단일 TX 경로용 계정 접근자 `ApprovalRequest.targetActorId`·`targetSecurityVersion`, `ImmutableApprovalColumns.targetActorId`는 Payment 대상이면 `IllegalStateException`. 계정 대상 `create(…targetActorId…)` 오버로드는 유지하고 대상 종류 불일치로 소유 서비스 종류를 거부. 단일 TX 종료 전이 `executed(by…)`·`ownerRejected(by…)`는 ADMIN 소유만 | fail-closed, 기존 호출부 무변경 |
| I-71 | (S4) Port 이름: 실행 행 = `OwnerExecutionRepository`(→ `JpaOwnerExecutionRepository` — §14 Aggregate 판정: 승인 ID로 load/save, 변경 가능 `Versioned`), 명령 행 = `OperatorCancelCommandStore`(→ `JpaOperatorCancelCommandStore` — §14 Store ③, 승인의 생성 불변 부속 기록). §4.16의 "Store `claimDue`"는 S6에서 `OwnerExecutionRepository`에 더한다 | §14 |
| I-72 | (S4) `OwnerExecution.start`: 시도 1·`last_attempt_at` = 승인 시각. 무결성 플래그·재전송 중단 사유는 **처음 값 유지**(바꾸거나 지우지 않음 — 이후 원인은 감사 행), 소유 결과는 한 번만·code(거절)와 reference(`pcn_` 취소 ID) 중 정확히 하나. 본문 1~16 KiB(I-03과 같은 상한) | L-e, D-OPS-006·007, I-26 |
| I-73 | (S4) `PinnedPaymentTarget` = paymentId·상태·통화·원 승인액·반환 가능액·revision·hold(진행 중 취소는 advisory라 고정하지 않음). JSON은 domain이 필드 순서 고정으로 직접 만든다(값 형식이 제한돼 이스케이프 불필요). MySQL JSON은 키 순서·공백을 정규화하므로 adapter는 문자열 비교가 아니라 필드로 해석하고, 사본 일치는 DB JSON 비교(`pinned_target = target_snapshot`)로 확인 | 결정성·nit |
| I-74 | (S4) `OperatorCancelRequestSnapshot` domain 검사는 금액 > 0·통화·길이(근거 1~200, 사유 1~500 UTF-16 단위)·고정 대상 Payment 일치만. 문자 집합·앞뒤 공백·카드 번호 패턴(§4.18, I-17·I-18)은 요청 입력 검사(S5) | 단계 경계 |
| I-75 | (S4 V5) 열 크기·CHECK: 명령 `payment_id VARCHAR(64)`·`currency CHAR(3)`(+ 대문자 3자 CHECK)·`purpose_kind`(2종 CHECK)·`purpose_ref VARCHAR(200)`·`reason VARCHAR(500)`, 실행 `fingerprint VARCHAR(69)`(+ `^oct1:[0-9a-f]{64}$` CHECK)·`attempt_count ≥ 1`·`execute_before > approved_at`, 발견 `approval_id VARCHAR(64)`(PAYMENT approvalId 폭, 승인 FK 없음 — ORPHAN)·`detail_code VARCHAR(200)`, checkpoint `watermark ≥ 0`. 명령·실행 → 승인 FK(같은 TX가 이미 승인 행을 쥐고 있어 S 잠금 경합 없음) | DB 이중 방어 |
| I-76 | (S4) checkpoint·발견 표의 JPA Entity·Store는 S7(사용처와 함께). V6 checkpoint 열 권한은 명세 목록(`watermark, last_run_at, last_full_scan_at, version`) 그대로이고, Entity updatable 대조 시험은 S7에서 더한다 | 단계 경계 |
| I-77 | (S4) V5가 `admin_audit_event.target_ref`를 더하지만 감사 Entity(audit 모듈 — S4 허용 경로 밖)는 S5에서 매핑한다. `AdminPersistenceMappingSpec`은 이 한 열만 `NOT_YET_MAPPED`로 명시하고, 그 목록이 정확히 이 열이며 Entity가 아직 쓰지 않음을 단언한다(S5에서 매핑하면 실패 → 예외 삭제) | 허용 경로, 조용한 영구 예외 방지 |
| I-78 | (S4) `ApprovalStatus.APPROVED` 값을 없앴다(I-20 — DB status CHECK에도 없음). 소유 서비스 전용 상태 = `EXECUTING`·`EXECUTION_UNKNOWN` | I-20 |
| I-79 | (S4) approval 저장 Port 구현 3개의 기술 실패 분류를 `adapter.persistence.ApprovalPersistenceTranslation`(internal object) 한 곳으로 모았다. 1062는 제약 이름으로 `DuplicatePending`(`pending_key`)·`DuplicateUnresolved`(`unresolved_key`) | 분류 한 곳 |
| I-80 | (S4) `oct1:` 계산 `paymentops.domain.OperatorCancelTargetFingerprint`의 목적 종류는 계약 문자열(2종 집합 검사) — approval domain enum에 의존하지 않는다(Modulith 모듈 간 domain 의존 없음). 입력 검사: 금액 > 0, revision ≥ 0, 빈 요소 없음, 올바른 UTF-16. `Input.toString`에 근거 없음 | 모듈 경계, 비노출 |
| I-81 | (S4) **허용 경로 밖 필요 변경(미반영 — Coordinator 판단)**: `approval.application.ApprovalService`의 `eligibility`가 `ApprovalKind`에 대한 exhaustive `when`이라 7번째 종류를 더하면 컴파일되지 않는다. 최소 변경 = 그 `when`에 `OPERATOR_CORRECTION_CANCEL` 분기(도달 불가 `error`) + 요청 단계 `executionModel != ADMIN_OWNED → Rejected("WRONG_EXECUTION_MODEL")`(§4.10 1행). S4 시험은 이 최소 변경을 적용한 shadow 사본에서 실행했다. **S5: Coordinator가 권장안으로 결정해 저장소에 반영(Q-S4-PATH)** | §10 S4 경로 |
| I-82 | (S5) paymentops 구성: provided `PaymentOpsClient`·`OperatorCancelContract`(oct1·실행 본문)·`PaymentOpsPolicy`(기한·stale·backoff)·`PaymentTargetLookupUseCase`(+ `@NamedInterface` package-info), domain `OperatorCancelTargetFingerprint`·`OperatorCancelExecutionBody`(golden ③ 본문 바이트)·`OwnerResponseClassifier`(§4.15), required `PaymentOpsGateway`·`PaymentOpsTimings`, adapter.integration `PaymentOpsSettings`·`AdminCallSigner`(javax.crypto는 domain 허용 목록 밖이라 adapter)·`HttpPaymentOpsGateway`·`PaymentOpsConfiguration`, adapter.web `PaymentTargetController`(`GET /api/payment-ops/payments/{paymentId}/target`) | §4.1, S4 리뷰 L-1 |
| I-83 | (S5, 갱신) 무결성·운영 경보는 **전용 지표** `paycore.admin.ops.integrity_alert{code}`(Port `approval.application.required.OwnerOpsMetrics` → `MicrometerOwnerOpsMetrics`, C 직후 TX와 무관하게 1회), PAYMENT 호출은 `paycore.admin.ops.call{operation=TARGET\|EXECUTE,result}`·`paycore.admin.ops.lookup_rate_limited`(Port `paymentops.application.required.PaymentOpsMetrics`). 첫 보고의 `integrity_violation{check}` 임시 재사용은 제거(ADMIN 내부 무결성 위반에만 남음). `unresolved{status}`·`unknown.oldest_age_seconds`·`resend*`·`outcome_divergence`는 S6 회복 job | §6 |
| I-84 | (S5) 요청 advisory code: `CANCEL_IN_PROGRESS_ADVISORY`(명세), `PAYMENT_NOT_CANCELLABLE_ADVISORY`(cancellable=false 또는 통화 없음), `AMOUNT_EXCEEDS_CANCELLABLE_ADVISORY`(금액 > 반환 가능액) — 409 + 별도 TX 감사, 저장 없음 | 명세가 code 이름을 정하지 않음 |
| I-85 | (S5) 승인 A 단계: PAYMENT 404 → 409 `TARGET_NOT_FOUND`(상태·TOTP 불변), 장애·permit 없음 → 503 `PAYMENT_UNAVAILABLE`. 요청 단계 404 → 400 `PAYMENT_NOT_FOUND` + 감사(§4.12 2) | 상태 불변 쪽 |
| I-86 | (S5) 응답 분류 세부: 200은 `pcn_` 취소 ID가 있을 때만 STARTED(아니면 UNKNOWN `RESPONSE_UNREADABLE`), 404는 `PAYMENT_NOT_FOUND` code일 때만 종료, 5xx 실패 분류 `UNAVAILABLE_<code>`·`HTTP_nnn`, 호출 실패 `TIMEOUT`·`CONNECT_FAILED`·`CALL_FAILED`, permit 없음 `PAYMENT_OPS_BUSY`(POST 안 보냄 → UNKNOWN, 회복 job이 보냄 — **S5 리뷰 L-4: 이때도 `attempt_count`는 TX1의 1 그대로다**. 시도 수는 "보낸 명령 수"가 아니라 "명령 시도 기회 수"(TX1 1 + 회복 선점마다 +1)로 읽는다 — 대사·화면은 `last_failure = PAYMENT_OPS_BUSY`로 실제 미송신을 구별한다, 동작 변경 없음), 64 KiB 초과 `RESPONSE_UNREADABLE` | §4.15 fail-safe |
| I-87 | (S5) 감사 행위: 요청 `APPROVAL_REQUESTED`, TX1 `APPROVAL_EXECUTING`, TX2 `APPROVAL_EXECUTED`·`APPROVAL_OWNER_REJECTED`·`APPROVAL_EXECUTION_UNKNOWN`(행위자·실행자 = 승인자), 거부 `APPROVAL_DENIED`(별도 TX), 무결성 `APPROVAL_INTEGRITY_VIOLATION`. 모두 `target_ref = paymentId` | §4.10 감사 행 |
| I-88 | (S5) HTTP: `POST /api/approvals/operator-correction-cancel`(OPERATOR, 201), 승인은 공통 `POST /api/approvals/{id}/approve` — 컨트롤러가 `ApprovalQuery.isOwnerService`(jOOQ 비잠금, 비권위)로 0단계 분기. 실행 완료 200 `{approvalId,status:EXECUTED}`, 불명·TX2 실패 200 `{approvalId,status}`, PAYMENT 불가 503, `WRONG_EXECUTION_MODEL`·`PAYMENT_NOT_FOUND`·`INVALID_INPUT` 400 | M-6, §4.12 |
| I-89 | (S5) TX1 commit 불명확 → 새 TX 재조회: 실행 행이 있고 EXECUTING이면 저장 본문으로 C(POST)를 이어 가고, 없으면 원래 예외(500) | §4.13 B "재조회 수렴" |
| I-90 | (S5, S4 리뷰 6) 철회 요청자 재검사: 소유 서비스 종류는 종류 요청자 역할(OPERATOR) 보유, ADMIN 소유 6종은 기존대로 ACTIVE만(ADMIN-BOOT 동작 불변) | §4.10 |
| I-91 | (S5, S4 리뷰 1) 만료 job 행 단위 격리: 행 처리 중 `RuntimeException`은 그 행만 rollback·건너뜀 + 지표 `paycore.admin.approval{operation=EXPIRE_JOB,kind=UNKNOWN,result=ERROR}` + WARN(승인 ID만), 다음 실행에서 다시 | 영구 정지 방지 |
| I-92 | (S5) 입력 규칙(domain `OperatorCancelInputRules`): 근거 = L*·N*·U+0020·`- _ . : / #`(ASCII 기호만), 사유 = LF만 허용(CR 거부), 앞뒤 공백(유니코드 공백 포함) 거부, 카드 번호 = 13~19자리 숫자(숫자 사이 공백·하이픈 1개 허용, Luhn 없음 — 보수적). **S5 대조(PAYMENT `AdminOperatorCancellationRequestParser`)로 정렬**: 근거 숫자는 십진(Nd — `isDigit`)만, 사유는 사설 영역·미할당 거부를 더했다. ADMIN이 더 엄격한 곳(사유 CR·NBSP 앞뒤·카드 번호)은 유지 — ADMIN ⊆ PAYMENT여야 정상 승인이 PAYMENT 400이 되지 않는다 | §4.18, I-17·I-18 |
| I-93 | (S5) 화면 조회 속도 제한 = 인스턴스 메모리의 actor별 분 단위 고정 창(Demo 30/분, 시험 5/분). 다중 인스턴스 합산 한도는 LIVE Gate. 형식 오류는 세지 않는다 | L-8 |
| I-94 | (S5) PAYMENT 시험 대역 = JDK `HttpServer` 고정 포트 18493(시험 설정 base-url — 새 Spring 컨텍스트·MySQL 컨테이너를 만들지 않게), 서명은 대역이 앱과 독립(javax.crypto 직접)으로 golden ② 키 검증 | §7.6, 메모리 |
| I-95 | (S5) LIVE 기동 거부 Demo 키 목록 = 계약 golden ② 시험 키 2개(공개 값). base-url은 경로·query·user info 없음(서명 경로 = 요청 경로) | §4.2 키 공간 |
| I-97 | (S5) 경보 두 종류를 구별: **무결성 경보** = golden ③ `expectedIntegrityAlert`(파생 409 2종·400 형식 — 계약이 정한 값, 바꾸지 않음), **운영 경보** = 401(키·시각)·`APPROVAL_WINDOW_INVALID`(설정 순서 위반 — §4.15 표의 "경보"). 둘 다 전용 지표 `paycore.admin.ops.integrity_alert{code}`(I-83 — code = 파생 409 code·400 형식 code·`UNAUTHENTICATED`·`APPROVAL_WINDOW_INVALID`)로 1건부터 경보. 첫 구현이 둘을 섞어 golden 대조가 RED였다(시험 기대값은 golden 그대로, 구현을 고침) | golden 우선 |
| I-96 | (S5) 감사 `targetRef` 형식 `^[a-z]{3}_[0-9A-HJKMNP-TV-Z]{26}$`, `targetActorId`와 동시에 두지 않는다(domain `AdminAuditEvent` 불변식). 감사 조회 행에 `targetRef` 추가 | §4.10, I-77 해소 |
| I-98 | (S5) 승인 상세(`GET /api/approvals/{id}`)에 소유 서비스 `owner` 객체 — 명령 행(금액·통화·목적 종류·고정 대상 JSON) LEFT JOIN 실행 행(승인·기한·시도·다음 시도·마지막 응답·실패·무결성 플래그·재전송 중단·소유 결과·소유 참조), jOOQ 비권위 조회. **사유·근거 문구는 조회 모델에 넣지 않는다**(§6 비노출 — 승인 화면에 사유를 보일지는 S8 화면 결정, 필요하면 별도 권한 조회로) | S8 화면용, 비노출 |
| I-99 | (S5 마무리) 시험 전용 실패 주입은 실제 JPA Port를 감싼 `@Primary` 대역(`OwnerFailureInjectionConfig` — 승인 INSERT 1213 분류, TX2 실행 행 저장 실패). 이미 구현된 동작의 RED는 해당 동작을 잠시 무력화한 실행으로 기록(원복 후 hash 대조) | 결정적 재현 |
| I-100 | (S5 리뷰 M-2) 무결성 위반 감사 행(별도 TX)에 승인 ID와 대상을 남긴다: 기존 단일 TX 경로는 0단계 비잠금 값의 `target_kind`·`target_ref`(계정이면 target_actor_id), 2-TX 경로는 승인 대상 paymentId(target_ref). 요청 TX의 무결성 위반(고정 대상 단언)도 같은 열 | 감사 추적성 |
| I-101 | (S5 리뷰 L-1·L-3) 카드 번호 패턴은 사유와 **근거 모두**에 적용하고 숫자는 `\p{Nd}`(전각·아랍 인도 숫자 포함)로 본다 — ADMIN이 PAYMENT보다 엄격한 쪽이라 ADMIN ⊆ PAYMENT 유지 | I-17 우회 차단 |
| I-102 | (S5 리뷰 L-2 — **S9에서 양쪽 동시 반영, I-135**) 사유·근거의 Unicode 형식 문자(Cf — RLO U+202E·zero-width U+200B 등) 거부. 한쪽만 바꾸면 ADMIN ⊆ PAYMENT 관계가 깨지거나(PAYMENT만 거부) 효과가 없다(ADMIN만 — 근거는 이미 허용 집합 밖이라 ADMIN이 거부, 사유는 양쪽 허용) | 양쪽 동시 변경 필요 |
| I-103 | (§7.6 예외 기록) `OwnerServiceOnLegacyPathSpec.storedPaymentApproval`·`ApprovalExpiryIsolationSpec`은 승인 행을 요청 Use Case가 아니라 domain Factory(`OwnerServiceApprovalFixture.pending`) + 저장 Port `ApprovalRequestRepository.insert`로 만든다(직접 SQL 아님). 이유: 기존 단일 TX 경로·만료 job의 방어(M-6·S4 리뷰 1)는 소유 서비스 요청 Use Case가 생기기 **전** 단계의 결함을 고정하는 시험이고, 만료 시험은 과거 요청 시각이 필요하다(Use Case는 현재 시각만) | §7.6 |
| I-104 | (S6) 회복 job 구조: provided `RecoverOwnerExecutionsUseCase` ← `adapter.batch.OwnerExecutionRecoveryScheduler`(`paycore.admin.payment-ops.recovery.{enabled,fixed-delay}` — Demo 30초, 시험은 끔) → `OwnerExecutionRecoveryService`. 선점 = Port `OwnerExecutionRepository.lockDue`(native `FOR UPDATE SKIP LOCKED`, 승인 행 ⋈ 실행 행) + `claimed`(시도 +1, 다음 시도 = now + backoff(새 시도 수)) 한 TX, 처리는 행마다 TX 밖·실패는 그 행만 건너뜀. 1회 상한 `recovery-batch-size`(Demo 50) | §4.16, 관례 §16 |
| I-105 | (S6) 시도 수 의미(L-4와 같은 읽기): 회복 선점은 결정 조회만 하고 끝나도 +1 한다 — "명령 시도 기회 수". 실제 송신 여부는 `paycore.admin.ops.resend{result}`(`EXECUTE_*`·`DEADLINE_*`)와 `last_failure`로 구별 | §4.16 L-e |
| I-106 | (S6) 결과 반영 공용화: 웹 TX2와 회복 job이 같은 `OwnerOutcomeApplier`(승인 행 → 실행 행 잠금). 이미 종료된 승인에 다른 결과가 오면 상태 불변 + `paycore.admin.ops.outcome_divergence`(다름 = EXECUTED인데 REJECTED, OWNER_REJECTED인데 STARTED, code 다름, 소유 참조 다름 — UNKNOWN은 비교하지 않음). 내부 무결성 위반은 지표 + 별도 TX 감사(승인 ID·target_ref, S5 리뷰 L-A) 후 `EXECUTING` 응답 | §4.13 D |
| I-107 | (S6) 조회·기한 확인 실패 기록: 결정 조회 불가는 UNKNOWN 반영(`last_failure = DECISION_<분류>`), 기한 확인 불가는 `DEADLINE_<분류>` — 둘 다 상태 유지·다음 주기. 회복 감사 행위자 SYSTEM_JOB(실행자 = 원 승인자, 상관 ID `job-recovery-<approvalId>`) | §4.16, D-OPS-003 |
| I-108 | (S6) 처리 순서 고정: 결정 조회 → 기한 확인(재전송 중단·무결성 플래그와 무관하게 먼저 — D-OPS-009) → 참여자 재검사(중단은 승인당 1회 기록·경보·감사 `APPROVAL_RESEND_HALTED`, EXECUTING이면 UNKNOWN으로, 이미 중단이면 재검사 생략 — I-26 재개 없음) → 무결성 플래그면 POST 안 함 → 저장 본문 재전송. 기한 확인 NONE은 상태 유지(다음 주기) | §4.16 1~5 |
| I-109 | (S6) 지표: `paycore.admin.ops.resend{result}`·`resend_halted{reason}`·`outcome_divergence`(counter), `unresolved{status=EXECUTING\|EXECUTION_UNKNOWN}`·`unknown.oldest_age_seconds`(gauge — 회복 실행마다 갱신, 나이 기준 = UNKNOWN 승인의 결정(승인) 시각). 경보 임계(운영 설정, Demo): oldest_age 900초 초과, resend_halted·outcome_divergence·integrity_alert 1건 이상 | §4.16·§6 |
| I-110 | (S6 기록 — S5 리뷰 nit) **역할 회수는 항상 세션 세대를 올린다**(ADMIN-BOOT D-ADMIN1-004 "회수 즉시 세션 무효")는 전제에서 승인 TX1의 승인자 검사는 역할 재검사와 세션 세대 비교 두 겹이다. S5 리뷰 무력화 실행에서 승인자 역할 재검사만 지운 결과는 `DENIED`가 아니라 **`SESSION_REPLACED`**였다(세대 검사가 실행을 막음 — 실행 없음). 역할 재검사 단독 효과는 세대를 올리지 않는 역할 변경 경로가 생길 때 별도 시험이 필요 | 증거 기록 |
| I-111 | (S6 — S8 문구용, S5 리뷰 L-B) 근거·사유 형식 거절은 지금 400 `INVALID_INPUT` 하나다. S8 화면이 이유를 보이려면 요청 응답에 필드·사유 분류(예: `{"error":"INVALID_INPUT","field":"purposeReference","reason":"CARD_NUMBER_PATTERN"}` — 값 원문 없음)를 더해야 한다. S8에서 결정·구현(가역, 비노출 유지) | S8 |
| I-112 | (S6 리뷰 L-1 — Coordinator 결정) 회복 job 감사 범위: **외부 효과가 있는 행위**(실행 POST 재전송·기한 확인 호출)와 **상태 전이·실패 분류 변화**는 매번 감사, **효과 없는 결정 조회 실패가 같은 분류로 반복**되면(이미 EXECUTION_UNKNOWN이고 `last_failure`가 같음) 감사 행 없이 `last_failure`·다음 시도 갱신 + 지표 `resend{result=LOOKUP_UNAVAILABLE}`만. 시험: 같은 분류 반복 감사 1행, 분류 변화 +1행, 재전송은 매번 1행 | 감사 증가 억제 |
| I-113 | (S6 리뷰 L-3 → **S7 리뷰 L-1 Coordinator 결정**) 기동 검사 `backoff-initial > connect-timeout + read-timeout`(= `2 × initial > 2 × (connect + read)` — 첫 backoff가 호출 한 번의 최대 시간보다 길어 늦은 응답과 다음 재전송이 겹치지 않게, 여유 2배). 이에 맞춰 **Demo `backoff-initial` 30초 → 40초**(connect 2초 + read 30초 = 32초 < 40초. Demo 값, 운영값 아님 — LIVE Gate). L-6 순서(stale 62초 < backoff-max 10분 < 기한 24시간 ≤ PAYMENT 거울)는 그대로이고 main `application.yml` 값으로 기동 검사를 통과함을 시험이 확인(`PaymentOpsGatewaySpec`). Demo backoff 수열은 40·80·160·320·600초(상한) | Coordinator 결정 |
| I-114 | (S6 리뷰 L-2) 회복 job 행 단위 실패: 처리 중 예외는 그 행만 건너뛰고 `resend{result=ERROR}`(경보 — 대사 `UNRESOLVED_WITH_DECISION`과 함께 조사), 선점된 실행 행에 승인·명령 행이 없으면 내부 무결성 위반 — `integrity_violation{check=approval\|command}` + 별도 TX 감사(SYSTEM_JOB, 승인 ID·target_ref). 다른 행 처리는 계속 | 조용한 누락 방지 |
| I-115 | (S7) 대사 구조: provided `ReconcileOperatorDecisionsUseCase` ← `adapter.batch.OperatorDecisionReconciliationScheduler`(`paycore.admin.payment-ops.reconciliation.{enabled,fixed-delay}` — Demo 5분) → `OperatorDecisionReconciliationService`. 증분은 페이지마다 checkpoint를 PAYMENT `nextWatermark`(안정 watermark — 받은 최대 seq가 아님)로 전진(잠금 짧은 TX, `max` — 뒤로 가지 않음), 실행당 `reconciliation-max-pages`(Demo 10). 성공 실행은 `last_run_at` 갱신. 전체 스캔은 `last_full_scan_at`이 `reconciliation-full-scan-interval`(Demo 1일)보다 오래되면 같은 실행에서 seq 0부터 진전이 없을 때까지, watermark 불변(`last_full_scan_at`만) | §4.17, I-30·I-36 |
| I-116 | (S7) ADMIN 대조 재료는 jOOQ Reader가 아니라 승인·명령·실행 행의 JPA 비잠금 `find`(대사는 상태를 바꾸지 않고, ArchitectureSpec Reader 규칙 — Reader는 조회 서비스 전용 — 에 예외를 늘리지 않으려고). 화면용 발견 목록 jOOQ Reader는 S8(화면과 함께) | §4.17 가역 |
| I-117 | (S7) 판정 세부: `UNRESOLVED_WITH_DECISION` 임계 = PAYMENT 안정 지연 거울 값 `decision-stable-delay`(Demo 2분) + `backoff-max`, `MISSING_DECISION`은 ADMIN 종료(EXECUTED·OWNER_REJECTED) 소유 서비스 승인 중 전체 스캔에 없는 것(400 형식 거절 code 4종 제외 — PAYMENT 결정이 없는 것이 정상), `STATE_DIVERGENCE`의 취소 ID 비교는 ADMIN 소유 참조가 있을 때만. 결정 목록 한 행이라도 형식이 어긋나면 그 페이지 전체를 해석 불가로 보고 watermark를 옮기지 않는다(fail-safe) | §4.17 |
| I-118 | (S7) 발견 기록: 발견마다 짧은 TX(발견 INSERT + 감사 `OPS_RECON_FINDING` SYSTEM_JOB·target_ref — 형식이 맞는 approvalId·paymentId만 감사 열에), UNIQUE 1062는 `DuplicateFinding`으로 분류해 그 TX만 rollback하고 지표 `recon.finding_duplicate`(오류·재시도 아님). 항목 판정 중 예외는 I-119(S7 리뷰 L-3에서 바뀜). 지표 `recon.run{result}`·`recon.finding{kind}`·`recon.lag_seconds`(마지막 성공 이후, 성공 실행이면 0 — 경보 Demo 30분). `recon.open_findings{kind}` gauge는 S8 발견 목록 조회와 함께 | §4.17·§6 |
| I-119 | (S7 리뷰 L-3) 대사 항목 판정 오류: 그 항목만 건너뛰어 다른 항목의 발견은 기록하되, checkpoint는 **오류 항목 직전 seq까지만** 전진하고 그 실행의 증분을 멈춘다(`min(nextWatermark, 오류 seq − 1)`). 다음 실행이 오류 항목부터 다시 읽는다(이미 기록된 발견은 UNIQUE로 중복 없음). 오류가 고쳐지지 않으면 watermark가 그 앞에 머문다 — **정책**: 건너뛰지 않는다(빠뜨린 결정을 조용히 넘기지 않음). 막힘은 경보로 드러난다: 실행 결과 `recon.run{result=ITEM_ERROR}`(실행당 1 — 경보 기준 Demo: 1회 이상이면 경보, 운영 임계는 LIVE Gate), `last_run_at`이 갱신되지 않아 `recon.lag_seconds`가 커진다(경보 Demo 30분, I-123). 조사·수리는 운영 절차(LIVE Gate). 전체 스캔 중 판정 오류도 같은 ITEM_ERROR— **S7 리뷰 L-C(Coordinator)**: 전체 스캔은 그 스캔의 항목 오류가 0일 때만 `last_full_scan_at`을 갱신한다(오류가 있으면 다음 실행이 다시 전체 스캔 — 수리 전까지 실행마다 전체 스캔이 반복되므로 ITEM_ERROR 경보로 빨리 드러내야 한다, 규모 영향은 §13 LIVE Gate) | 빠뜨림 방지 |
| I-120 | (S7 리뷰 M-1) 전체 스캔 `MISSING_DECISION`: (a) 후보 = 종료 승인 중 실행 행 `last_response_at` < `스캔 시작 − (decision-stable-delay + deadline-skew)`(Demo 2분 + 5분 — **deadline-skew를 여유로 쓰는 이유**: `last_response_at`은 ADMIN 앱 시각, PAYMENT 결정 `recorded_at`·안정 watermark는 PAYMENT DB 시각이라 두 시계 차이 허용치(I-34와 같은 값)가 필요하다) — 커밋이 늦은 결정·스캔 도중 종료된 승인을 오탐하지 않는다(JPQL 조인, 실행 행 없는 승인은 명령을 보낸 적이 없으므로 제외). (b) 다음 페이지 기준 = 페이지 최대 seq. (c) 제외 = PAYMENT 400 code 집합 `OwnerResponseClassifier.PAYMENT_BAD_REQUEST_CODES`(형식 4종 golden ③ + `QUERY_NOT_ALLOWED` golden ② + `REQUEST_UNREADABLE` I-60 — golden과 일치 시험), 대사는 `PaymentDecisionListQuery.decisionlessRejectionCodes`로 받는다(분류기 400 경로와 같은 집합). 실행 행에 "400 종료" 표지 열을 두는 안은 migration이 필요해 택하지 않음 — 집합 밖 400 code로 끝난 승인은 `MISSING_DECISION`으로 드러난다(안전 쪽 오탐). 끝까지 읽지 못한 전체 스캔(페이지 상한)은 MISSING을 판정하지 않고 `FULL_SCAN_INCOMPLETE` | 오탐 방지 |
| I-121 | (S7 리뷰 L-1) 회복 job 선점을 **행마다 처리 직전**으로: `lockDue(limit 1)` SKIP LOCKED 짧은 TX → 처리 → 반복(실행당 최대 `recovery-batch-size` 행, 선점 시각 = 그 행의 now). 묶음 선점은 앞 행 처리 시간만큼 뒤 행의 backoff 시계·시도 수를 미리 소비했다. `OwnerExecutionClaimRaceSpec`(두 인스턴스)·SKIP LOCKED 시험은 그대로 의미가 있고(잡힌 행은 건너뛰고 다음 반복에서도 다시 건너뜀), "앞 행 처리 중 뒤 행 미선점" 시험 추가 | 선점 정확성 |
| I-122 | (S7 리뷰 L-2) 대사 서비스는 provided `PaymentDecisionListQuery`(목록 조회 + 결정 없는 400 code 집합)만 의존하고 `PaymentOpsClient`(실행 POST·기한 확인 포함)는 의존하지 않는다. ArchUnit: PaymentOpsClient 허용 목록에서 대사 제외 + 이름에 `Reconciliation`이 든 클래스는 `PaymentOpsClient.execute`·`confirmDeadline`을 호출하지 않음(위반 fixture `DecisionReconciliationResendFixture`) | 대사 무효과 보장 |
| I-123 | (S7 리뷰 L-4) checkpoint `advance`는 watermark만, `last_run_at`은 `markRun` — 완전 성공 실행에서만. 부분 진전 뒤 PAYMENT 장애면 watermark 진전은 남고 `last_run_at`·lag 기준은 그대로. **S7 리뷰 L-A**: 완전 성공이 아직 없으면(`last_run_at` NULL) lag는 이 인스턴스 기동 시각부터 센다(checkpoint 행에 생성 시각 열이 없고 migration은 이번 허용 경로 밖) — 0으로 숨기지 않는다 | lag 정의 |
| I-124 | (S7 리뷰 L-B — Coordinator 결정 (a)) PAYMENT admin 채널의 `REQUEST_UNREADABLE`은 `AdminChannelAuthenticationFilter`가 요청 본문 스트림을 읽다 `IOException`이 날 때만 생긴다(연결 끊김 등 — 같은 바이트를 다시 보내면 성공할 수 있다). "400은 같은 바이트면 항상 같은 결과일 때만"(§0·§4.4) 불변식에 맞게 **503 `REQUEST_UNREADABLE`**로 바꿨다(결정·감사 없음 — 미인증 단계, 지표 reason `BODY_UNREADABLE` 그대로). ADMIN은 5xx라 결과 불명확 → 재전송. ADMIN의 결정 없는 400 집합에서 제거(golden ③은 이 사례가 없어 그대로) — 그 code의 400으로 끝난 옛 승인은 대사가 `MISSING_DECISION`으로 드러낸다 | 불변식 정합 |
| I-125 | (S8 — I-111 결정) 입력 형식 거절 응답 `{"error":"INVALID_INPUT","field":<paymentId·amountMinor·purposeKind·purposeReference·reason>,"reason":<FORMAT·NOT_POSITIVE·UNKNOWN_KIND·LENGTH·MALFORMED_TEXT·EDGE_WHITESPACE·CHARACTER_NOT_ALLOWED·CARD_NUMBER_PATTERN>}` — 첫 거절 필드 하나, 값 원문 없음. 판정은 `OperatorCancelInputRules.referenceProblem·reasonProblem`(기존 valid = 문제 없음, 판정 집합 불변). 화면은 아는 필드·이유만 받아 고정 문구로 보이고, `CARD_NUMBER_PATTERN`은 날짜·일련번호형 참조도 걸린다고 안내(§13 L-B 메모 — 동작은 보수적 거부 그대로) | 화면 오류 이유 |
| I-126 | (S8 — D-OPS-010) `GET /api/approvals/{id}/justification`: 조회자 계정 현재 행이 ACTIVE + APPROVER 역할이거나 그 승인의 요청자 본인이면 200 `{approvalId,purposeReference,reason}`, 그 밖 403 `FORBIDDEN`, 없는 승인·다른 종류 404(감사 없음), 감사를 못 쓰면 503 `AUDIT_UNAVAILABLE`(문구 미반환). 허용·거부 시도마다 감사 1행 `APPROVAL_JUSTIFICATION_VIEWED`(결과 OK·FORBIDDEN, target_ref = paymentId, detail 없음 — 문구 미포함), 같은 짧은 TX. 목록·상세(I-98)에는 계속 문구 없음. 화면은 버튼 표시만 같은 규칙으로 하고 성공 응답일 때만 문구를 화면 상태에 둔다. **판정 순서(S8 리뷰 L-3 기록)**: 속도 제한(I-131) → ID 형식 → 승인 존재·종류(없으면 404, 감사 없음) → 권한(403, 감사). 그래서 로그인한 계정은 403/404 차이로 승인 존재를 알 수 있지만, 승인 ID는 ULID(80비트 난수)라 추측이 어렵고 조회는 로그인 계정·속도 제한 안에서만 가능해 현재 동작을 유지한다 | D-OPS-010 |
| I-127 | (S8) 대사 발견 조회 `GET /api/reconciliation/findings`(SECURITY_ADMIN·APPROVER, 그 밖 403): jOOQ `ReconciliationFindingListReader`(adapter/query) → `ReconciliationFindingQueryService` — `openCounts`(종류별)·`openTotal`·최근 200행(code 열만)·`truncated`·checkpoint `lastRunAt`·`lastFullScanAt`·`lastResult`. 발견 해결 절차·열이 없으므로 **기록된 발견 = 열린 발견**(해결 운영 절차는 LIVE Gate). `lastResult`는 이 인스턴스 메모리(`ReconciliationRunMemory` — 재기동 뒤 없음, 권위 신호는 지표). 화면에 "(이 인스턴스 기준)"으로 표기(S8 리뷰 nit). 화면은 `ITEM_ERROR`·`FULL_SCAN_INCOMPLETE`·`PAYMENT_UNAVAILABLE`을 "조사 필요 — 수동 종료 없음"으로, 해결·종료 버튼 없음 | §4.17·§4.20 |
| I-128 | (S8 — I-118) `paycore.admin.ops.recon.open_findings{kind}` gauge: 6종 모두 등록(없으면 0), 대사 실행 끝에 JPA 집계(`SELECT kind, COUNT(*) … GROUP BY kind`)로 갱신(실패해도 실행 결과는 그대로, 로그 분류만). 발견 detail은 `ReconciliationFindingRecord`가 `^[A-Z_]+(,[A-Z_]+)*$`만 받는다(문구가 들어갈 수 없게 — 비노출) | I-118 |
| I-129 | (S8 화면) 메뉴: 결제 보정 취소(모든 조회 역할 — 양식은 OPERATOR만), 내 요청(SECURITY_ADMIN·OPERATOR), 대사 발견(SECURITY_ADMIN·APPROVER) — 표시만, 권한 판정은 서버. 상세 패널: 상태 문구(EXECUTING·EXECUTION_UNKNOWN 자동 확인, EXECUTED 취소 참조, OWNER_REJECTED code), 재전송 중단·무결성 플래그 "조사 필요 — 수동 종료 없음", 시도 수 "명령 시도 기회 수(결정 조회만 한 주기 포함)"(I-105), 수동 종료 버튼 없음. 요청 양식: 금액 기본값 = 반환 가능액, 진행 중 취소·보류·취소 불가면 요청 비활성 + 이유, 근거·사유 앞뒤 공백 정리 후 전송, 카드 정보 경고. 고정 값 대 현재 값 비교와 `TARGET_BUSY` 안내는 I-132 | §4.20 |
| I-130 | (S8 리뷰 L-1 — Coordinator 판단, 확정 결정 **D-CANCEL-001** 근거) 보류(onHold)가 걸린 Payment는 가맹점 취소가 거절되고 **운영자 보정 취소만 허용**되므로, 요청 화면은 보류 건을 막지 않고 "보류 중 — 가맹점 취소는 막혀 있고 운영자 보정 취소로만 처리합니다"만 안내한다(진행 중 취소·취소 불가만 요청 비활성). 서버 판정은 그대로(§4.12) | D-CANCEL-001 |
| I-131 | (S8 리뷰 L-3) 근거·사유 권한 조회 속도 제한: 조회자별 분 단위 고정 창(I-93 방식, 인스턴스 메모리), `paycore.admin.approval.justification-per-minute`(Demo 20, 시험 5 — 운영값 LIVE Gate). 형식·존재·권한 판정 **전에** 센다(없는 ID 탐색도 포함). 초과는 429 `RATE_LIMITED`, **감사 행 없음**(데이터를 읽지 않았고, 거부 감사가 대량으로 쌓이는 것을 막는 것이 목적) — 지표 `paycore.admin.approval.justification_rate_limited`와 WARN 없이 카운터로만 드러낸다. 다중 인스턴스 합산 한도는 LIVE Gate | 감사 폭주 억제 |
| I-132 | (S8 리뷰 M-1, §4.20) PENDING 소유 서비스 승인 상세에 고정 값(`pinned_target` — 상태·통화·원 승인액·반환 가능액·revision·보류)과 현재 값(`GET /api/payment-ops/payments/{id}/target`, 비권위)을 나란히 표시. revision·통화 불일치, 요청 금액 > 현재 반환 가능액, 취소 불가면 "승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다", 진행 중 취소면 TARGET_BUSY 안내(TOTP 미소비), 현재 값 조회 실패면 "현재 값 확인 불가". 승인 버튼은 막지 않는다(권위 판정은 승인 TX1의 잠근 행) | §4.20 |
| I-133 | (S8 리뷰 L-2) 화면 금액: 입력 금액은 정규식 + `Number.isSafeInteger`(2^53 미만)여야 전송, 넘으면 "금액이 허용 범위를 넘습니다". 대상 조회 응답의 금액·revision이 안전 정수가 아니면 표시 오류로 조회 결과를 쓰지 않는다(JSON 수 정밀도 손실로 다른 금액을 보이거나 보내지 않게) | 금액 정확성 |
| I-134 | (S9 — I-59) PAYMENT 결정 지표 `paycore.payment.admin_channel.decision{decision,replayed}`: 실행 200 → STARTED(재생 = `existing`), 업무 거절 409 → REJECTED(재생 = 기록 결정 재생 여부 — `Rejected.replayed`, 동등성 밖 속성), 404 PAYMENT_NOT_FOUND → REJECTED·`unknown`(provided 결과 `NotFound`가 재생 여부를 구분하지 않는다 — 구분하려면 provided 계약 변경이 필요해 후속 후보), 파생 거절(APPROVAL_CONFLICT·IDENTITY_MISMATCH)은 결정이 아니라 세지 않음. 기한 확인 200 → 이번 기록(`confirmed`)이면 재생 아님, 기존 결정이면 재생, NONE 미집계. payment-api 변경: `CancelStartService`·`OperatorCorrectionCancelService`·provided `OperatorCorrectionCancelResult.Rejected`(속성 추가 — 응답·동등성 불변), adapter `AdminChannelDecisionMetric` | 관측 |
| I-135 | (S9 — I-102) 사유의 Unicode 형식 문자(Cf — RLO U+202E, zero-width U+200B·U+200D, BOM U+FEFF, isolate U+2066, tag 문자)를 ADMIN(`OperatorCancelInputRules.reasonProblem` → `CHARACTER_NOT_ALLOWED`)·PAYMENT(`AdminOperatorCancellationRequestParser.validReason`) 양쪽에서 같은 변경으로 거부(근거는 양쪽 다 이미 허용 집합 밖). ADMIN ⊆ PAYMENT 유지(같은 6종 입력 시험 양쪽). golden ③의 예시·엄격 거절 사례에 Cf가 없어 golden·harness 생성기는 바뀌지 않는다(sha 불변 확인) | 비노출·표시 위조 방지 |
| I-136 | (S9 nit) 근거·사유 조회 속도 제한 창은 승인 모듈 시계 `ApprovalEnvironment.now()`로 센다(시험 시계 대역과 같은 기준). 초과한 첫 회도 감사하지 않는다(지표만 — I-131 유지: 상한 안의 조회는 모두 감사되므로 "누가 많이 조회했는가"는 감사 행 수로 이미 보이고, 초과분 감사는 감사 폭주 억제 목적과 충돌) | 결정 기록 |
| I-137 | (S9) 운영 runbook 위치 = **이 명세 부록 R**(절차가 이 작업의 I-번호·지표·감사 action에 직접 묶여 있어 명세와 함께 바뀌어야 한다). 관례 문서(§21)에는 재사용할 구조 규칙만 두고 절차는 두지 않는다. Demo 시연 준비는 부록 D | 문서 위치 |
| I-138 | (S9) 처음부터 통과한 보호 시험(정직 기록): `AdminOpsMetricsContractSpec`(ADMIN 지표는 S4~S8에 이미 있음)·비노출 canary 묶음 S9 절(누출 없음 확인)·화면 저장소/URL 시험 — 구현 변경 없이 현재 동작을 고정한다. 의미는 변이(지표 이름 바꾸기·로그에 사유 쓰기 등)로 확인했다(구현자 보고 §S9 변이) | TDD 기록 |

---

## 12. 질문

### 12.1 확정(답변됨 — 재질문 금지)

| 결정 | 요지 | 반영 |
|---|---|---|
| D-OPS-001 | HMAC 서명·헤더·query 금지·ADMIN 전용 키·키 분리·2키·±300초(Demo)·nonce 없음·응답 서명 없음·mTLS LIVE Gate | §4.2 |
| D-OPS-002 | 승인 시각 + 기한(Demo 24시간) 안에서만 새 시작, 서명 본문 기한, `APPROVAL_EXECUTION_EXPIRED`, 시작된 approvalId는 기한 무관 | §4.4.3 |
| D-OPS-003 | UNKNOWN 출구는 PAYMENT 확정 응답뿐, 상한 없는 재전송·연령 경보, 기한 뒤 자동 종결, 수동 미실행 확정 없음 | §4.15·§4.16 |
| D-OPS-004 | 근거 형식 검사 + 감사, 원천 대조 LIVE Gate | §4.18 |
| D-OPS-005 | approvalId별 최초 확정 결정 INSERT-only UNIQUE 기록·재생, 거절 감사 요청마다, 일시 거절도 승인 소진 | §4.4 |
| D-OPS-006 | 참여자 정지·역할 상실 시 재전송 중단, UNKNOWN 유지·경보, 결정 조회로 반영, 수동 종료 없음 | §4.16 |
| D-OPS-007 | `APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`는 비확정 — UNKNOWN 유지·무결성 경보·기록 결정 반영 | §4.15·§4.16 |
| D-OPS-008 | 이 작업에 PAYMENT 결정 목록(서명·watermark) ↔ ADMIN 승인 주기 대사 포함, 지문 맞는 승인 없는 실행·상태 불일치 경보 | §4.17 |
| D-OPS-009 | 재전송 중단·결정 없는 미해결 승인은 기한 경과 후 서명된 기한 확인 전용 호출로 정리 — PAYMENT는 자기 시각 `now ≥ executeBefore`이고 결정이 없을 때만 `REJECTED(APPROVAL_EXECUTION_EXPIRED)` 기록, 취소 시작 없음, ADMIN `OWNER_REJECTED`·Payment 차단 해제, D-OPS-006과 충돌 없음 | §4.4.5·§4.15·§4.16 |
| D-CANCEL-005·D-ADMIN-003·007·D-ADMIN1-004·006·D-CANCEL1-001·006·008·D-CANCEL-001·003·011 | 목적 제한·2인·양쪽 검사·승인 = 실행·실행자 = 승인자·동기 콜백 없음·거절 감사·hold 중 허용·진행 중 1건·P1 | §4 전반 |
| Q-ADMIN-APPROVER-STAFF·Q-ADMIN-SEC-VALUES | PROPOSED — Demo 진행, LIVE Gate. 보정 취소 1건에 서로 다른 계정 2개(OPERATOR + APPROVER) | §13 |

### 12.2 새 질문 후보

없음. Q-OPS-05~09는 D-OPS-005~009로 확정됐다.
- 참고(질문 아님, I-35): D-OPS-007 "기록 결정 그대로 반영"과 검토 L-b "조용한 EXECUTED 없음"을 함께 만족하도록 "플래그 설정 후 반영"으로 정했다. Coordinator가 "불일치면 반영 보류"를 원하면 그 경우 Payment 미해결 차단이 풀리지 않는다는 운영 영향이 있어 사용자 질문 대상이 된다.

### 12.3 검토했으나 질문으로 올리지 않은 것

- `PAYMENT_NOT_FOUND` 결정 저장(I-27): paymentId 발급 주체가 PAYMENT라 의미 변화 없음, 불변식 충족을 위한 구현 선택.
- 형식 오류 미저장(I-27): 같은 바이트 = 같은 결과, ADMIN은 고정 본문만 보냄.
- 정지 뒤 재전송 미재개(I-26): D-OPS-006보다 엄격한 쪽.
- PAYMENT 기간 상한·미래 approvedAt(I-06), ID 엄격 형식(I-07), 카드 번호 거부(I-17), `inFlightCancel` advisory(I-32), 조회 속도 제한(I-10): 모두 엄격한 쪽·운영 영향만.
- 근거 허용 문자 집합(I-18): 가역 구현 세부.
- 대사의 Demo 주기·전체 스캔 빈도(I-30): 운영값은 LIVE Gate.

---

## 13. LIVE Gate 항목

| 항목 | 근거 |
|---|---|
| 운영 mTLS, 내부 connector 운영망 노출 제한 | D-OPS-001 |
| 시각 창 운영값, NTP·시각 차이 감시 | D-OPS-001 |
| 키 KMS 주입·2키 교체 절차, LIVE에서 Demo 키 거부 | D-OPS-001 |
| PAYMENT 환경(TEST·LIVE 배치)마다 admin-channel 키가 서로 다름 확인(한 환경의 ADMIN 서명이 다른 환경 PAYMENT에서 통과하지 않음) | D-OPS-001, checkpoint n-8 |
| 실행 기한 운영값(ADMIN·PAYMENT 일치 절차) | D-OPS-002, L-6 |
| 근거 원천 대조 — 차단 조건. **함께 확인(S5 리뷰 L-B)**: ADMIN 근거 검사의 카드 번호 패턴(I-101 — 13~19자리 연속 숫자)이 `RECON-20261028-000001`처럼 날짜·일련번호가 이어진 정상 참조를 거부할 수 있다(14자리 이상 연속). 운영 참조 형식(RECON Case·MASTER 종료 기록 ID)을 원천 대조 때 확정하고 패턴 예외 또는 형식 고정을 정한다. 동작은 지금 그대로(보수적 거부) | D-OPS-004, I-17·I-101 |
| APPROVER 인원, 관리자 보안 수치 | Q-ADMIN-APPROVER-STAFF·SEC-VALUES |
| 경보 임계·backoff·permit·timeout·속도 제한 운영값 — **현재 Demo 값(S9 점검)**: connect 2s·read 30s, backoff-initial 40s(I-113)·backoff-max 10m, permit approval·lookup·execution 각 4, 화면 조회 30회/분, 근거 조회 20회/분, 회복 30s·batch 50, 기한 여유 5m, 실행 기한 24h, 대사 5m·10페이지·안정 지연 2m·전체 스캔 1d, 경보 UNKNOWN 연령 15m·대사 lag 30m | I-09·I-10·I-22·I-24 |
| 다중 인스턴스 속도 제한 합산: 화면 대상 조회(I-93)·근거 조회(I-131)는 인스턴스 메모리 창이라 인스턴스 수만큼 합산 한도가 늘어난다 — 공유 저장소 또는 앞단 제한 | I-93·I-131 |
| 대사 `lastResult` 공유: 화면의 마지막 실행 결과는 인스턴스 메모리(I-127) — 여러 인스턴스면 화면마다 다를 수 있다(권위 신호는 지표) | I-127 |
| 대사 발견 해결 절차·해결 열(현재 기록된 발견 = 열린 발견, 해결·종료 endpoint 없음) | I-127 |
| 대사 주기·안정 지연·전체 스캔 방식(운영 규모), 발견 처리 운영 절차(누가·언제·어떻게) | D-OPS-008 |
| 대사 전체 스캔 규모·주기: 코드 상한 `MAX_FULL_SCAN_PAGES` = 100,000페이지 × 200행(Demo 안전장치 — 넘으면 `FULL_SCAN_INCOMPLETE`, MISSING 판정 안 함), `MISSING_DECISION` 후보의 종료 승인 전체 적재(Demo 규모 전제). 운영 규모에서 분할 스캔·후보 페이지 처리·주기를 정한다 | I-120, D-OPS-008 |
| UNKNOWN·무결성 경보·`DECISION_CONTENT_MISMATCH` 플래그·대사 발견의 조사 절차(수동 종료 없음 전제). 재전송 중단 승인은 기한 확인(D-OPS-009)으로 자동 종결되므로 절차 대상은 기한 전 구간과 PAYMENT 장애·401 지속 | D-OPS-003·006·007·009 |
| 기한 확인 시각 여유·PAYMENT/ADMIN 시각 차이 운영값 | D-OPS-009, I-34 |
| 응답 서명 필요 여부 | D-OPS-001 |
| V5 표 재작성 영향, 운영 배포 전 운영자 취소 행 0건 확인(결정 행 없는 옛 행 호환 I-29) | R-4 |
| 질의 시간 상한 방식 운영 DB 검증 | I-09 |

---

## 14. brief와 다르게 정한 것

| ID | brief | 이 명세 | 이유 |
|---|---|---|---|
| Δ-1 | `APPROVED`+`EXECUTING` | TX1에서 바로 `EXECUTING` | 중간 상태 불필요 |
| Δ-2 | 생성 열 변경 | `unresolved_key`만 추가 | F-10 |
| Δ-3 | target_kind/ref | + balanceRevision은 기존 열, 감사 target_ref, DB CHECK로 ADMIN 소유 불변식 유지 | 열 변경 최소, R-3 |
| Δ-4 | 키 분리 방식 미지정 | `Binder` | F-11 |
| Δ-5 | 전용 체인 | + 기본 체인 denyAll | M-3 |
| Δ-6 | 대상 GET | `payment` 기준 조회 | F-2 |
| Δ-7 | 본문 기한만 | + PAYMENT 기간 상한·미래 approvedAt | 양쪽 검사 |
| Δ-8 | 모듈 미지정 | `paymentops` + `approval` | 순환 없음 |
| Δ-9 | 결과 매핑 | 경계 사례·D-OPS-007 반영 | §4.15 |
| Δ-10 | GET 상태 by approvalId | 기록 결정 조회(STARTED/REJECTED/NONE) + 결정 목록 | D-OPS-005·008 |
| Δ-11 | HTTP 클라이언트 미지정 | `JdkClientHttpRequestFactory` + NO_PROXY | 프록시 미사용 |
| Δ-12 | 회복 = 재전송 | 결정 조회 우선 → NONE이면 재전송 | 불필요한 POST 감소, D-OPS-006·007 |

---

## 15. 위험

| ID | 위험 | 완화 |
|---|---|---|
| R-1 | 내부 경로 노출 | 전용 체인 + denyAll + 전수 시험 |
| R-2 | 동기 VAN 전송 → UNKNOWN 증가 | read timeout > 전송 예산, 결정 조회 수렴 |
| R-3 | 일반화가 ADMIN 소유 불변식 훼손 | 조건부 불변식 + DB CHECK + 기존 경로 fail-closed(M-6) + 변이 |
| R-4 | V5 표 재작성 | 개발 볼륨 작음, LIVE Gate 확인 |
| R-5 | `oct1:` 표류 | golden ①·양쪽 대조 |
| R-6 | 키 재사용 | 기동 거부 |
| R-7 | 시각 차이 401 | 401 → UNKNOWN 재전송, NTP LIVE Gate |
| R-8 | 결정 경합(다른 Payment 같은 approvalId) | UNIQUE + 재시도 + 경합 시험 |
| R-9 | WSL 메모리 | 모듈 단위·분리 실행 |
| R-10 | connector customizer 미적용 | 직접 설정, S3 실측 |
| R-11 | 승인 화면 값과 승인 시점 값 차이 | A 단계 재조회가 권위 |
| R-12 | 사유 개인정보 | 경고 + 카드 번호 거부 |
| R-13 | `MAX_EXECUTION_TIME` hint가 jOOQ·드라이버 경로에서 적용 안 됨 | S3 실측, 대안 queryTimeout |
| R-14 | 대사 watermark가 늦은 커밋 행을 건너뜀 | 안정 지연 + 일 1회 전체 스캔 + 시험 |
| R-15 | UNKNOWN 장기 체류 — 수동 종료 없음 | 결정 없는 승인(참여자 정지 포함)은 기한 + 여유 뒤 기한 확인(D-OPS-009)으로 종결·차단 해제. 남는 것은 PAYMENT 장애·401 지속(경보)뿐이고, 내용 불일치·옛 행 충돌은 플래그 달린 종료 상태로 끝남(I-35, §4.4.2 L-d). 경보·대사 발견·운영 절차(LIVE Gate) |
| R-16 | 기한 확인과 늦은 실행 명령 경합 | UNIQUE(approval_id) + 결정과 취소 같은 TX — `DeadlineConfirmationRaceSpec` |

---

## 16. 규모 추정

- payment-api Kotlin +115~155(결정 기록·기한 확인·경합·HTTP 매핑·엄격 JSON·격리 실측·ArchUnit 10규칙), internal-admin Kotlin +155~210(기존 경로 분기·회복·기한 확인·내용 대조·대사 6종·전체 스캔), Vitest +24~40, harness +12~18 → 약 **+305~420건, WEBHOOK-001의 0.95~1.3배**. rev 0.2: +280~390건 — D-OPS-009·검토 2차로 증가.
- 하한은 실측으로만(Coordinator).

---

## 17. rev 0.2 변경 기록(2026-10-27 — 명세 검토 CHANGES_REQUIRED, D-OPS-005~008)

| 검토 | 반영 |
|---|---|
| H-1(a) 저장/미저장 결과·불변식 | §4.4.1(표·불변식, `PAYMENT_NOT_FOUND` 저장 결정 I-27) |
| H-1(b) 내용 hash | §4.4.2(요소·correlationId 제외·버전 태그·`compareRequest` 관계) |
| H-1(c) 같은 TX INSERT·UNIQUE 재시도·비잠금 조회 비의존·격리 수준 | §4.4.3, F-14(READ COMMITTED 확인 — `LocalTransactions.kt:36`) |
| H-1(d) 판정 순서 | §4.4.3(결정 조회 → … → 기한 → startCancel) |
| H-1(e) V27·V28·권한·`@Immutable`·S3 경로 | §4.4.4, §10 S3 |
| H-1(f) 시험·변이 | §7.1 `OperatorDecisionRecordSpec`·경합, §7.5 S3 |
| M-1 D-OPS-007 | §4.15, §4.16 2단계, `IntegrityFlagNoResendSpec` |
| M-2 ArchUnit 확장 | §4.8(규칙 9개·합성 중첩 클래스·fixture) |
| M-3 기본 체인 denyAll | §4.2, `InternalPathDenySpec`, §7.5 S2 변이 |
| M-4 API 계약 golden·HTTP 매핑 전수 | §4.19 ③, §4.5, `AdminOperatorCancellationHttpMappingSpec` |
| M-5 D-OPS-008 대사 | §4.17, §10 S7, 시험·지표 |
| M-6 기존 단일 TX 경로 | §4.10, F-15, `ApprovalExecutionModelGuardSpec`·`AuditTargetRefSpec` |
| M-7 격리 시험·질의 상한·풀 검사 | §4.7, `AdminReadIsolationSpec`·`AdminQueryTimeoutSpec`·`AdminChannelPoolBudgetSpec` |
| L-1 엄격 JSON | §4.3 |
| L-2 공백 거부·정규화 없음 | §4.6, §4.18 |
| L-3 context path | §4.2(금지, I-31) |
| L-4 시각 열·본문 상한 | §4.11 실행 행, §4.2, §4.7 |
| L-5 결정 조회 | §4.3 |
| L-6 설정 순서·경보 | §4.16, §4.15 |
| L-7 `inFlightCancel` advisory | §4.12-3, §4.13 A |
| L-8 승인 A 단계 보호 | §4.7(permit 분리·속도 제한) |
| L-9 1213 | §4.12-4, `UnresolvedApprovalRaceSpec` |
| nit 권위 스냅샷 열 | §4.11(`pinned_target` 권위, `target_snapshot` 사본) |
| nit A0 | §4.13(비권위 명시) |
| nit 단계 경로 | §10(`PaymentApiSettings.kt`, 결정 Store·Entity·Reader, `.env.example`·시험 config) |
| nit F-13 | §3.2 F-13(`CancelStartService.kt:82`·`OperatorCorrectionCancelService.kt:13` 추가) |
| D-OPS-006 | §4.16 3단계, `ResendHaltOnIneligibleParticipantSpec`, I-26 |
| §12·§8·§10·§13·§16 | 갱신 |

### 17.1 rev 0.3 변경 기록(2026-10-28 — 검토 2차 CHANGES_REQUIRED, D-OPS-009)

| 검토 | 반영 |
|---|---|
| M-a 승인되지 않은 approvalId의 PAYMENT 결정 | §4.17 발견 kind `DECISION_FOR_UNAPPROVED`(실행 행 없으면 승인·명령 행 paymentId·금액·통화·요청자 비교, detail 열 이름), 판정 우선순위, §7.2 시험(상태 5종), §7.5 S7 변이 |
| M-b D-OPS-009 기한 확인 | §4.3 endpoint, §4.4.5(본문 = 저장 실행 본문, PAYMENT 판정 순서, 취소 미시작, 늦은 실행 명령과 UNIQUE 경합·재생), §4.5 HTTP, §4.15 매핑(차단 해제), §4.16 2단계(정지 승인 포함), §4.19 golden ②(경로 교차 서명 거절)·③(응답 4종), §7.1 `DeadlineConfirmationApiSpec`·`DeadlineConfirmationRaceSpec`, §7.2 `DeadlineConfirmationRecoverySpec`(정지 + NONE + 기한 경과 → OWNER_REJECTED·차단 해제 / 기한 전 거부·결정 0 / 결정 있음 → 기존 반환), §7.5 변이, ArchUnit `deadlineConfirmationNeverStartsCancel`, I-26·I-33·I-34·R-15·R-16·§13 문구 |
| L-a 안정 watermark | §4.4.4 `recorded_at DEFAULT (NOW(3))`·`origin`, §4.17(DB 시각 `serverTime`, 전체 스캔 = 최종 안전망·watermark 불변), §7.1·§7.2 늦은 커밋(>안정 지연) 회수 시험, I-36 |
| L-b 결정 조회 내용·대조 | §4.3 공통 필드(지문·paymentId·금액·통화·요청자·승인자·legacy), §4.15 내용 대조(불일치 → `DECISION_CONTENT_MISMATCH` 플래그 후 반영, 조용한 EXECUTED 없음), `DecisionContentCheckSpec`, I-35, §12.2 참고 |
| L-c 생성 규칙·규칙 수 | §4.8 `operatorCommandConstructedOnlyAtEntry`에 `copy`·`copy$default`·팩토리·반환 타입 메서드, `OperatorCommandCopier.kt` fixture, 규칙 수 9개로 §2·§4.8·§7.1 통일(+ §4.4.5 1개 별도 표기) |
| L-d 옛 행 충돌 | §4.4.2(CONFLICT → UNKNOWN+플래그 → 결정 조회 legacy STARTED → 플래그 달린 EXECUTED, 기한 확인도 legacy STARTED 반환·EXPIRED 미기록, 개발 데이터만) |
| L-e TX2 갱신 규칙 | §4.16 TX2 갱신 규칙(`attempt_count`는 보낸 명령 수·TX2 불변, `next_attempt_at` 앞으로만, `last_response_at`·`last_failure`), `Tx2AttemptFieldsSpec`, §7.5 S6 변이 |
| nit golden ③ 자리표시 | §4.19 ③ 자리표시 + 정규식 |
| nit 발견 1062 | §4.17 정상 무시(지표만), §7.2·§7.5 |
| §12·§17 | D-OPS-001~009, 이 표 |

### 17.2 rev 0.4 변경 기록(2026-10-28 — rev 0.3 REVIEW_PASS의 Low 3건)

| 검토 | 반영 |
|---|---|
| L-1 기한 확인 진입 ArchUnit | §4.8 `deadlineConfirmationOnlyFromAdminEntry`(정확 FQN `OperatorDeadlineConfirmationController`, fixture `DeadlineConfirmationCaller.kt`), 진입 컨트롤러 4개로 `adminEntryControllersNotReferenced`·`adminEntryMappedUnderAdminPath` 확장, 규칙 수 11개로 §2·§4.8·§7.1 통일 |
| L-2 취소 미시작 규칙 | §4.8 `deadlineConfirmationNeverStartsCancel` 대상 = 정확히 `OperatorDeadlineConfirmationService`, 메서드 수준 금지 목록(실제 타입 `PaymentCancellationRepository` 등), §4.4.5 "같은 재시도 규칙" = `LocalTransactions` 공유(CancelStartService 재사용 아님) |
| L-3 기한 확인 permit | §4.7 명령 permit 공용, 풀 대비 기동 검사 식에 포함, §4.5 문구 |
| 구현 주의 1062 | §4.4.5 결정 INSERT 실패는 TX 전체 중단(잡고 계속 금지), `DecisionInsertFailureAbortsTxSpec`·변이 |

---

## 부록 R. 운영 runbook(S9 — I-137, Demo 기준 — 운영 절차·담당은 LIVE Gate)

공통 원칙: **수동 종료·수동 상태 변경 없음**(D-OPS-003). ADMIN 상태는 회복 job이 PAYMENT 기록 결정을 반영할 때만 바뀌고, 대사는 상태를 바꾸지 않는다. 로그에는 사유·근거가 없다 — 원인 추적은 `approvalId`·`seq`·고정 분류 code로 한다.

**R-1 대사 `ITEM_ERROR`가 계속될 때**(`paycore.admin.ops.recon.run{result=ITEM_ERROR}` 증가 + `recon.lag_seconds` 증가)
1. WARN 로그 `operation=ops-reconciliation-item approvalId=<id> seq=<n> result=error failure=<예외 분류>`에서 막힌 항목을 찾는다(checkpoint는 그 seq 직전에 멈춰 있다 — I-119).
2. 그 approvalId의 ADMIN 승인·명령·실행 행이 읽히는지 확인한다(행 손상·스키마 불일치가 흔한 원인). `operation=ops-reconciliation-missing`은 전체 스캔 MISSING 기록 실패다.
3. 원인을 고치면 다음 실행이 같은 seq부터 다시 읽고 전진한다. 건너뛰는 수단은 없다(빠뜨림 방지). 전체 스캔 중 오류도 같은 경보이며 오류가 0이 될 때까지 전체 스캔이 반복된다(I-119·L-C — PAYMENT 부하 주의).

**R-2 `FULL_SCAN_INCOMPLETE`**: 전체 스캔이 페이지 상한(`MAX_FULL_SCAN_PAGES`)을 넘었다 — MISSING 판정을 하지 않았다. 결정 수가 Demo 규모를 넘은 것이므로 §13 "전체 스캔 규모·주기" 결정(분할 스캔 등) 전까지 증분 대사와 다른 발견으로 운영하고 MISSING은 미확인으로 본다.

**R-3 재전송 중단(`resend_halted{reason}`)·무결성 플래그(`integrity_alert{code}`, 상세 화면 "조사 필요 — 수동 종료 없음")**
1. 승인 상세에서 상태·플래그·시도 수(명령 시도 기회 수)·마지막 실패 분류를 본다. 근거·사유가 필요하면 APPROVER가 권한 조회(감사됨).
2. 재전송 중단(참여자 자격 상실): 조회만 계속되고, 실행 기한 + 여유가 지나면 기한 확인(D-OPS-009)으로 `OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)`가 되어 Payment 차단이 풀린다 — 기다린다.
3. 무결성 플래그(APPROVAL_CONFLICT·IDENTITY_MISMATCH·DECISION_CONTENT_MISMATCH): POST 없이 조회로만 수렴한다. PAYMENT 결정 행(결정 조회 API)과 ADMIN 명령 행을 대조해 원인(키 유출·ADMIN 결함 의심)을 조사하고 보안 담당에 올린다. 상태를 손으로 바꾸지 않는다.
4. UNKNOWN 장기 체류(`unknown.oldest_age_seconds` > 15분): PAYMENT 가용성·401(키·시각) 지속 여부를 먼저 본다.

**R-4 감사 증가 정책(I-112)**: 외부 효과(재전송·기한 확인)와 상태·분류 변화는 매번 감사, 같은 분류의 결정 조회 실패 반복은 `last_failure` + 지표만. 감사 행이 갑자기 늘면 재전송이 반복되는 승인(같은 approvalId의 `APPROVAL_EXECUTION_UNKNOWN` SYSTEM_JOB 행)을 찾는다.

**R-5 근거·사유 조회 속도 제한(I-131)**: 조회자별 20회/분(Demo) 초과는 429·감사 없음, `paycore.admin.approval.justification_rate_limited` 증가. 한 계정의 반복 초과는 감사 행 `APPROVAL_JUSTIFICATION_VIEWED`(결과 FORBIDDEN 다수)와 함께 계정 탈취·내부 오용 신호로 본다. 다중 인스턴스 합산은 §13.

---

## 부록 D. Demo 시연 준비(S9 — 운영 키·운영 endpoint 금지, Simulator·Demo 값만. 화면 캡처는 Coordinator)

로컬 기동(저장소 기존 구성 — 명령은 각 앱 문서와 `.env.example`의 설명을 따른다. 아래는 순서 요약이며 확인되지 않은 세부는 "확인 필요"로 둔다):
1. `.env`를 `.env.example`에서 만든다. DB 비밀번호는 로컬 값. `PAYCORE_ADMIN_TOTP_CIPHER_KEY_V1`·`PAYCORE_ADMIN_LOGIN_NAME_HMAC_KEY`는 `openssl rand -base64 32`. **ADMIN→PAYMENT 서명 키는 로컬에서 새로 만든 한 쌍**(`openssl rand -base64 32`)을 PAYMENT `PAYCORE_PAYMENT_ADMIN_CHANNEL_KEY_ID/BASE64`와 ADMIN `PAYCORE_ADMIN_PAYMENT_OPS_KEY_ID/SIGNING_KEY`에 같은 값으로 넣는다(golden 시험 키 재사용 금지 — LIVE 기동 거부 대상이며 Demo에서도 저장소 공개 값이다). `PAYCORE_ADMIN_DEPLOYMENT_MODE=DEMO`, `PAYCORE_ADMIN_IP_ALLOWLIST=127.0.0.1,::1`, `PAYCORE_ADMIN_PAYMENT_OPS_BASE_URL=http://127.0.0.1:<payment-api 내부 connector 포트>`(payment-api `paycore.payment.api.internal-port` — 공개 포트와 달라야 한다; PAYMENT 쪽 `admin-channel.*`·`admin-ops.*` 값은 payment-api `application.yml` 64~66행 주석과 `.env.example` 117행 JSON 예시대로 넣는다 — Demo `admin-ops` read 4·command 2·reserve 4).
2. `docker compose up -d mysql`과 `docker compose --profile simulator up -d simulator-mysql`.
3. institution-simulator·payment-api·internal-admin을 각각 기동(`./gradlew :apps:<app>:bootRun` 또는 빌드한 jar — 환경 변수는 `.env`에서 내보낸다; 확인 필요: 각 앱의 기동 명령은 해당 작업 명세의 로컬 실행 절).
4. ADMIN 첫 계정: `--paycore.admin.command=bootstrap --output=tty`(ADMIN-BOOT-001 §4.9 — 웹 서버 없이 1회)로 SECURITY_ADMIN·APPROVER 등록 코드를 받고 화면에서 등록(비밀번호 → TOTP). OPERATOR는 SECURITY_ADMIN이 계정을 만들고 역할 부여(ROLE_GRANT OPERATOR)를 요청 → APPROVER가 TOTP로 승인.

Demo 데이터(운영자 보정 취소 흐름):
1. 결제창 Demo 흐름(Simulator 가상 인증기관·가상 VAN)으로 승인된 Payment를 하나 만들고 paymentId를 적어 둔다(기존 결제 E2E 절차).
2. OPERATOR: 메뉴 "결제 보정 취소" → paymentId로 대상 조회(반환 가능액 기본값) → 목적 종류·근거(예 `RECON-2026-0001` — 날짜형 연속 숫자는 카드 패턴으로 거부됨)·사유 입력 → 승인 요청.
3. APPROVER: "승인함" → 상세(고정 값 대 현재 값, 경고 없음 확인) → TOTP → 승인 → `EXECUTED`(취소 참조 `pcn_…`).
4. 변형 시연: (a) 요청 뒤 같은 Payment에 가맹점 부분 취소를 하나 더 하면 revision이 바뀌어 상세에 "승인 시 무효(TARGET_CHANGED)" 경고, (b) payment-api를 잠시 내린 채 승인하면 `EXECUTION_UNKNOWN` → 다시 올리면 회복 job이 결정 조회·재전송으로 수렴, (c) SECURITY_ADMIN·APPROVER로 "대사 발견" 화면(열린 발견 수·이 인스턴스 기준 마지막 실행).
5. 정리: Demo DB는 `docker compose down -v`로 지운다(운영 데이터와 섞지 않는다).
