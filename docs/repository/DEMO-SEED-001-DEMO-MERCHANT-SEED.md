# DEMO-SEED-001 — DEMO 배포 모드 전용 Demo 가맹점 seed 명령(Simulator 결제 → 운영자 보정 취소 시연)

- 상태: 구현 명세 **rev 0.3**(2026-10-31, Implementer). rev 0.1 리뷰 CHANGES_REQUIRED(M1~M3, m1~m8, Nit 2) → rev 0.2, rev 0.2 재리뷰 Minor 4건 → rev 0.3 — 변경 기록 §15. DONE 판정은 Verifier·Reviewer·Coordinator.
- 근거 결정: **D-DEMO-001**(Q-DEMO-MERCHANT — DEMO 전용 seed 명령), **D-DEMO-002**(Q-DS-01 → payment-api DEMO 전용 `demo-credential`, D-CPA-001의 Demo 한정 예외, MASTER-CREDENTIAL-001 도입 시 대체), **D-DEMO-003**(Q-DS-02 → KRW CSA 기본 한도 1건 1,000,000원·1일 5,000,000원, 운영값 아님), **D-DEMO-004**(Q-DS-03 → Demo 키 유효 7일, 갱신·재발급 없음, 만료 시 Demo DB 초기화 후 재seed), **D-DEMO-005**(Q-DS-05 → 카드 경로 승인·한도 설정을 시스템 행위자 1명으로 처리하는 것을 D-ADMIN-003의 Demo 한정 예외로 허용; Q-DS-04 확인 — D-MS2-002 그대로, 전날 seed·다음 날 시연). 모두 2026-10-31.
- 유지 결정(재질문 안 함): D-MD-004·005·012·015·016, D-MS-001, D-MS-004, D-MS2-002, D-MS2-003, D-AUTH-006, D-AUTH1-002, D-CPA-001(형식·keyId+SHA-256 저장 — 발급 주체만 D-DEMO-002 예외), D-ADMIN-003(Demo 예외 범위는 §4.12), D-ADMIN1-022.
- Coordinator 결정(2026-10-31, 리뷰 반영): 별도 DB 계정 **채택 안 함** — runtime 계정 하나 유지 + 코드 측 완화(§4.5), LIVE Gate 검토 항목(§11). 주기 job = 가드 제거 + 진입점 끔 인자(m3).
- 기준: HEAD `406ebbf`(feat/foundation-setup). 선행: [ADMIN-BOOT-001 §4.8.2·§4.9](ADMIN-BOOT-001-ADMIN-FOUNDATION.md), [ADMIN-BOOT-002 I-11·I-12](ADMIN-BOOT-002-BOOTSTRAP-SECOND-SA.md), [MASTER-SNAPSHOT-001](MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md)·[002](MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md), [CP-API-001 §5](CP-API-001-CREATE-PAYMENT-API.md), [ADMIN-PAYMENT-OPS-001 부록 D](ADMIN-PAYMENT-OPS-001-PAYMENT-OPS.md), 관례 [ARCHITECTURE-CONVENTIONS §14](ARCHITECTURE-CONVENTIONS.md).
- 참고: MASTER-LIFECYCLE-001·MASTER-CREDENTIAL-001은 명세 없이 BACKLOG 카드뿐이다.

## 1. 문제

부록 D 운영자 보정 취소 시연은 승인된 Payment가 필요하다. PAYMENT에는 ① 그 MID의 권리 Snapshot(승인 시작 판정 A0~A4 조건 전부)과 ② 가맹점 API Credential 투영 행이 있어야 한다. ①을 만드는 MASTER provided Use Case는 있으나 부르는 inbound adapter가 없고, ②는 발급·전파 경로가 없다. D-DEMO-001·002가 DEMO 배포 모드 전용 명령 두 개로 이 공백을 메운다.

## 2. 현황 조사(`406ebbf`)

| 항목 | 현재 | 의미 |
|---|---|---|
| MASTER 관리 명령 | provided `MerchantOnboardingUseCase`·`MerchantEntitlementUseCase`·`MerchantAuthorizationTermsUseCase`. 명령마다 MASTER 로컬 TX 하나(`AdminCommandExecutor`): `admin_request` insert-first 잠금(범위 (operation, key), fingerprint) → 업무 행·`master_change_history` → `entitlement_revision`+1 → Snapshot 발행(Outbox `ENTITLEMENT_SNAPSHOT`). 같은 키·같은 내용 = 저장 결과 재생(성공·업무 거절 모두), 다른 내용 = `IdempotencyKeyReused` | seed는 이 Use Case만 순서대로 부른다 — 새 MASTER 업무 코드·migration 없음 |
| `AdminRequestStore` | `reserveOrLock`(없으면 PENDING 행 **INSERT** 후 잠금)·`complete`뿐. **읽기 메서드 없음** | 진행 판정용 비잠금 읽기 `find`를 추가한다(§4.3, m2). `reserveOrLock`을 판정에 쓰면 PENDING 행이 생긴다 → 금지 |
| Snapshot 발행 | `publishEntitlement`를 부르는 명령 중 seed가 쓰는 것: `registerMerchantSite`·`approveContractRevision`·`issueFirstDefaultMerchantAccount`·`registerPaymentMethodActivation`·`approvePaymentMethodActivation`·`decideSiteReview`·`enableCurrency`·`registerTransactionLimitPolicy`·`assignPolicy`·`registerCardRouteActivation`·`approveCardRouteActivation`(`routeTransition`). `EntitlementSnapshotPublisher.publishChange`는 MID가 없으면(`sources.load` null) 발행하지 않는다. `registerBusiness`·`registerMerchant`·`registerContract`·`addServiceAgreement`·`registerPolicyVersion`은 발행 호출 없음 | 코드 분석상 seed 1회 발행 수 = **10**(07~12·15~18 단계; 03·05는 MID 전이라 발행 없음). 시험은 이 값을 독립 리터럴로 단언(Nit 2 — §6) |
| 권리 전파 | Outbox → relay(`paycore.master.entitlement.*`, HMAC) → payment-api 내부 connector(`paycore.payment.api.internal-port`) `POST /internal/v1/entitlement-snapshots` → Inbox+투영 로컬 TX → ACK. renew job이 lease 재발행 | seed는 전달 코드를 건드리지 않는다. 웹 master-api가 Demo 내내 떠 있어야 한다(재기동은 무방 — renew가 lease 재발행) |
| PAYMENT 승인 시작 판정 | `AuthorizationTermsEvaluator` A0~A4(권리 → (CARD, 매입사) 카드사 개통 → FEE·SETTLEMENT Assignment 각 1 → 한도 정책 1, 없음·모호 = 차단) | seed 최소 집합 확정(§4.2) |
| 한도 정책 경계 | Asia/Seoul 00:00만(D-MS2-002) + 소급 금지 | 결제 가능 = 다음 KST 자정 이후(D-DEMO-005 확인 — 전날 seed) |
| Credential 투영 | `merchant_api_credential`(V5), runtime **SELECT만**(V6). `JpaMerchantCredentialEntity`는 `@Immutable` + 값 열 `insertable=false`·`updatable=false`, `MerchantCredentialStore`는 `find`만(인증 경로) | **기존 Entity·Port는 건드리지 않는다**(M3). 별도 insert 전용 Entity·Port를 명령 모드에서만 등록(§4.5) |
| 배치 모드 | internal-admin `paycore.admin.deployment-mode`만. master/payment에는 없음 | 명령 전용 속성 + **환경 준비 단계 게이트**(§4.6, M2) |
| 1회성 명령 관례 | internal-admin `account.adapter.batch` `OneShot*`·`CodeOutput`·`OneShotModePeriodicJobGuard`(I-11·I-12)·`effectiveArguments`. 현행 runner 모드 검사는 컨텍스트 refresh 뒤(= Flyway·`ddl-auto=validate` 뒤)라 DB에 이미 닿는다 | 같은 구조를 앱마다 두되 **모드 게이트는 컨텍스트 생성 전**으로 옮긴다(M2) |
| 주기 job | master: `EntitlementSchedules`(relay·renew, `paycore.master.entitlement.scheduling-enabled`). payment: `AuthorizationRecoveryScheduler`(`paycore.authorization.timing.recovery.enabled`)·`CancellationRecoveryScheduler`(`paycore.cancellation.timing.recovery.enabled`)·`MerchantNotificationFanoutScheduler`(`paycore.webhook.fanout.enabled`)·`MerchantWebhookDeliveryScheduler`(`paycore.webhook.delivery.job.enabled`)·`RequestedPaymentExpiryScheduler`(`paycore.payment.checkout-expiry.job.enabled`) | 진입점이 끔 인자를 덧붙이고 가드가 빈을 지운다(m3) |
| 지표 이름 | MASTER `paycore.master.entitlement.outbox.pending`·`.outbox.oldest.pending.age`·`.relay.results`·`.snapshots.issued`; PAYMENT `paycore.payment.entitlement.inbox.outcomes`·`.inbox.rejected`·`.lease.min.remaining`·`.heads.expired` | 부록 D 전달 확인에 쓴다(m8) |
| golden | `harness/tests/test_entitlement_golden.py`가 앱 코드와 독립으로 v3 golden을 만들어 두 앱 `src/test/resources/contracts/`에 쓰고, 각 앱 `EntitlementContractGoldenSpec`이 바이트 비교 | 같은 방식으로 Demo seed Snapshot golden(m7) |
| Simulator·반환 URL | 가상 카드 전부 acquirer `SIMACQA`(INST-CODE-001 `df3a1c4` 이후 — 이전 `SIMACQ-A`), VAN `merchantRef` 형식만. Route `SIMVAN01`(이전 `SIM-VAN-01`). 반환 URL `https://` + WEB host(라벨 둘 이상) | (CARD, `SIMACQA`, `SIMVAN01`), host `.test` — 기관 코드 공통 형식 `^[A-Z0-9]{1,32}$`(D-INST-CODE-001) |

## 3. 범위

### 3.1 범위
1. master-api `demo-seed`(DEMO 전용, MASTER provided Use Case 18단계, 단계별 로컬 TX, 결정적 멱등키).
2. 전파는 기존 Outbox → relay → 서명 Inbox만.
3. payment-api `demo-credential`(D-DEMO-002): TEST 키 1개를 PAYMENT 투영에 기록 + 고정 1회성·감사 행. 유효 `P7D`, 갱신·재발급 없음(D-DEMO-004).
4. 두 앱의 환경 단계 모드 게이트·웹 컨텍스트 fail-closed·주기 job 제거.
5. 부록 D·`.env.example` 갱신(§12).

### 3.2 범위 밖
- 운영 가맹점·생명주기·운영 Credential(MASTER-LIFECYCLE-001·MASTER-CREDENTIAL-001 — D-DEMO-001·002).
- MASTER 관리자 web·2인 승인 계층.
- 정책 내용(요율·주기 숫자) — DEMO 내용 없는 버전만.
- Seoul 자정·소급 금지·한도 필수 규칙 완화(D-DEMO-005: 정책 변경 없음).
- Credential 갱신·재발급·폐기 명령(D-DEMO-004), seed 삭제 명령(정리 = Demo DB 폐기).
- 별도 DB 계정(Coordinator 결정 — 채택 안 함).
- 프로세스 간 자동 E2E, Kafka/Redis.

## 4. 설계

### 4.1 소유 앱과 경계

| 단계 | 앱 | DB | Port |
|---|---|---|---|
| 가맹점·MID·권리 입력 | master-api `demo-seed` | MASTER | 기존 provided Use Case 3종 + `AdminRequestStore.find`(새 읽기 메서드) |
| Snapshot 전달 | 웹 master-api relay·renew → payment-api Inbox | MASTER Outbox → PAYMENT Inbox·투영 | 기존(수정 없음) |
| Demo API Credential | payment-api `demo-credential` | PAYMENT | 새 provided `DemoCredentialIssueUseCase`, 새 required `DemoCredentialProjectionWriter`·`DemoCredentialIssuanceStore`(명령 모드에서만 빈 등록), entitlement 새 provided `MidPolicyProfileQuery`(읽기) |

- cross-DB SQL·공유 Entity 없음. master-api는 PAYMENT 접속 정보가 없고 payment 명령은 MASTER에 닿지 않는다.
- seed 조정자는 application `DemoMerchantSeedService`(provided `DemoMerchantSeedUseCase`), **TX를 열지 않는다**(각 Use Case가 자기 TX 소유). batch adapter는 argv·출력만.

### 4.2 MASTER seed 단계(멱등키 `demo-seed-v1:<NN>-<step>`)

T0 = argv `--effective-from`(Asia/Seoul 00:00). 효력 구간은 모두 `[T0, 종료 없음)`. 값 상수 `DemoMerchantSeedValues`(production adapter 상수, "Fixture" 이름 금지, KDoc "Demo — 운영값 아님").

| NN | Use Case | 입력(Demo 값) | 결과 | T0 | Snapshot 발행 |
|---|---|---|---|---|---|
| 01 | `registerBusiness` | 등록번호 `0000000000`, `DEMO 사업자 (운영값 아님)` | businessId | | |
| 02 | `registerMerchant` | `DEMO 가맹점 (운영값 아님)` | merchantId | | |
| 03 | `registerMerchantSite` | WEB `demo-shop.paycore.test` | siteId | | (MID 없음 — 없음) |
| 04 | `registerContract` | 당사자 business, Scope MERCHANT, `[T0,∞)` | contractId(Revision 1) | ● | |
| 05 | `approveContractRevision` | contractId, 1 | `contractId#1` | | (MID 없음 — 없음) |
| 06 | `addServiceAgreement` | `CARD_PG_PAYMENT` | agreementId | | |
| 07 | `issueFirstDefaultMerchantAccount` | merchant, agreement, contract, 1, PRIMARY site(D-MD-015 선발급) | **MID** | | ● |
| 08 | `registerPaymentMethodActivation` | MID, CARD | activationId | | ● |
| 09 | `approvePaymentMethodActivation` | 근거(PRIMARY Site 적용 확인 동반 — D-MD-016) | activationId | | ● |
| 10 | `decideSiteReview` | APPROVED, 사유 `DEMO seed (not operational)`, 근거 `DEMO-SEED-V1:NON-OPERATIONAL` | siteId | | ● |
| 11 | `enableCurrency` | KRW, `[T0,∞)` | enablementId | ● | ● |
| 12 | `registerTransactionLimitPolicy` | CSA 기본(contract, agreement), KRW, **건당 `Limited(1_000_000)`·일 `Limited(5_000_000)`**(D-DEMO-003), `[T0,∞)`, 사유 | limitPolicyId | ● | ● |
| 13 | `registerPolicyVersion` | FEE_SCHEDULE `DEMO-FEE`, **DEMO**, hash 없음 | feeVersionId | | |
| 14 | `registerPolicyVersion` | SETTLEMENT_POLICY `DEMO-SETTLEMENT`, **DEMO**, hash 없음 | settlementVersionId | | |
| 15 | `assignPolicy` | MID, agreement, KRW, fee 버전, `[T0,∞)` | assignmentId | ● | ● |
| 16 | `assignPolicy` | MID, agreement, KRW, settlement 버전, `[T0,∞)` | assignmentId | ● | ● |
| 17 | `registerCardRouteActivation` | MID, CARD, `SIMACQA`, `SIMVAN01`(D-INST-CODE-001), 기관 가맹점 참조 `SIMVAN-DEMO-0001` | routeActivationId | | ● |
| 18 | `approveCardRouteActivation` | 근거 | routeActivationId | | ● |

- `AdminRequest`: actor `system:demo-seed`, correlationId 실행마다 `demo-seed-<uuid13>`(fingerprint 대상 아님 — 실행 추적용).
- 단계 순서는 2단계 첫 RED에서 domain 선행 조건에 맞게 조정 가능(첫 배포 전까지만 — 키 번호가 키의 일부).
- 결과 해석: `Succeeded` → 다음 입력. `Rejected` → 중단 exit 10. `IdempotencyKeyReused` → exit 9. `AdminCommandFailure.*` → exit 8(같은 argv 재실행).
- 발행 수: 07~12·15~18 = 10건(코드 분석). PAYMENT는 최신 revision만 적용한다.

### 4.3 멱등·1회성·T0(m1·m2)

- **1회성 = 결정적 키 공간 `demo-seed-v1:*`**: 같은 argv 재실행 = 전 단계 재생(새 행 0). 다른 내용 = 첫 다른 단계에서 exit 9, 새 Business·Merchant 없음. 동시 실행은 executor 규칙으로 하나만 효과.
- **진행 판정 Port(m2)**: `AdminRequestStore`에 비잠금 읽기 `fun find(scope: AdminRequestScope): StoredAdminRequest?` 추가, `JpaAdminRequestStore`가 기존 Spring Data 저장소로 구현(**migration 없음**, 기존 SELECT 권한). 조정자는 읽기 전용 로컬 TX(`LocalTransactions` 읽기 전용)에서 18개 키를 `find`로만 본다. **`reserveOrLock`을 진행 판정에 쓰지 않는다**(PENDING 행 INSERT — ArchUnit/단위 시험으로 금지, I-20). 판정은 사전 검사용이며 권위는 각 단계 executor.
- **T0 사전 검사**(모든 단계 호출 전, DB 무변경으로 거부):
  1. 형식: Asia/Seoul 00:00(ms 0)이 아니면 exit 2 `USAGE`.
  2. T0가 들어가는 단계(04·11·12·15·16) 중 **기록 없는 것이 하나라도 있으면**: `T0 < 지금` → exit 4 `EFFECTIVE_FROM_PASSED`, `지금 ≤ T0 < 지금 + 10분` → exit 4 `EFFECTIVE_FROM_TOO_SOON`. (새 실행은 다섯 단계 모두 미기록인 경우.)
  3. 다섯 단계가 모두 기록돼 있으면 검사 생략(나머지는 재생 또는 T0 무관 단계) — T0가 지난 뒤 같은 argv 재실행도 재생으로 exit 0.
  근거: 소급 거절이 중간 단계에 저장되면 같은 키가 영구히 거절을 재생해 seed가 고착된다.
- 결과: 결제 가능 시각 = T0 이후. T0 전 PAYMENT 판정은 `Refused(CONTRACT_NOT_EFFECTIVE)`.

### 4.4 전파 경로

`demo-seed` 단계 커밋 → MASTER Outbox(HIGH) → 웹 master-api relay(TX 밖, HMAC `paycore.master.entitlement.dispatch.*`) → payment-api 내부 connector `/internal/v1/entitlement-snapshots`(키 `paycore.payment.entitlement.inbox.signing-keys`) → Inbox+투영 → ACK. 명령 프로세스는 relay를 부르지 않는다(R-13). 확인 지표는 §4.10.

### 4.5 Demo API Credential(D-DEMO-002·004, M3·m5)

- 명령: `--paycore.payment.command=demo-credential --output=tty|file:<path> --merchant-account-id=<MID>`. payment-api 웹 프로세스와 **같은 전체 환경**(DB·authorization·webhook·entitlement·admin-channel 설정 — 기동 검사가 그대로 돈다)이 필요하다.
- 사전 조건(모두 DB 쓰기 전): 환경 단계 모드 게이트(§4.6) → 출력 준비(§4.7) → entitlement 새 provided `MidPolicyProfileQuery.find(mid)`(비잠금 읽기 TX, 기존 `EntitlementProjectionStore` 읽기 재사용):
  - head 없음 또는 그 MID가 최신 Snapshot에 없음 → exit 4 `MID_UNKNOWN`.
  - MID의 정책 Assignment가 0개이거나 **하나라도 contentStatus ≠ DEMO**(UNDEFINED 포함)면 → exit 4 `NOT_DEMO_MERCHANT`(m5). 운영 내용이 생길 미래 버전·다른 가맹점 MID에 Demo 키를 붙이지 않는다.
  - T0 전이어도 발급 허용(결제는 권리 판정이 막는다).
- 한 PAYMENT 로컬 TX(`DemoCredentialIssueService` — 유일한 사용자, I-22):
  1. `DemoCredentialIssuanceStore.insertOnce` — `JpaDemoCredentialIssuanceEntity`(`demo_credential_issuance`, `id` 고정 1, `CHECK (id = 1)`, `@Immutable`)를 **`EntityManager.persist` + `flush`**. PK 1062 → `LOST`(exit 5, 동시 실행 패자) / 이미 있던 행(재실행) → 사전 `find`로 `ALREADY_ISSUED`(exit 4, 원문 다시 보이지 않음).
  2. `DemoCredentialProjectionWriter.insert` — 새 `JpaDemoCredentialProjectionEntity`(같은 표 `merchant_api_credential`, `@Immutable`, 모든 열 `insertable=true`·`updatable=false`)를 `persist` + `flush`. PK(key_id) 1062 → **`KEY_ID_COLLISION`**(exit 11, TX 전체 rollback — 발급 행도 없음, 재실행 시 새 keyId). LOST와 구별(rev 0.3): 새 adapter는 MySQL 예외를 직접 해석하지 않고 기존 `PersistenceFailures.translate`(CP-DB-001 관례)로 감싼다 — `PersistenceFailure.UniqueConflict(constraint)`의 `StoreConstraint.DEMO_CREDENTIAL_ISSUANCE`(`demo_credential_issuance.PRIMARY`) → LOST, `StoreConstraint.MERCHANT_API_CREDENTIAL_KEY_ID`(`merchant_api_credential.PRIMARY`) → KEY_ID_COLLISION. enum 값 2개와 이름 매핑 2줄만 기존 파일에 추가한다.
  3. 값: environment `TEST`(상수), status `ACTIVE`, `valid_from` = 발급 시각(ms), **`valid_until` = `lease_until` = `valid_from` + `P7D`**(D-DEMO-004), revision 1, `updated_at` = 발급 시각, `secret_sha256` = 원문 전체 UTF-8 SHA-256 hex.
  4. 커밋 → 원문 출력.
- **merge 금지**: 두 Entity는 `Persistable`을 구현하지 않고 Spring Data `save`를 쓰지 않는다(`save`는 할당 ID면 `merge` → 기존 고정 행을 읽고 `@Immutable`이라 조용히 넘어가 1회성 PK 검사가 사라진다 — 변이 C5).
- **기존 읽기 경로 불변**: `JpaMerchantCredentialEntity`·`MerchantCredentialStore`·`JpaMerchantCredentialStore`는 수정하지 않는다(인증은 기존 `find`로 새 행을 읽는다).
- **INSERT 가능 빈은 명령 프로세스에만**: 두 writer adapter·`DemoCredentialIssueService`·runner는 `@ConditionalOnProperty(prefix = "paycore.payment", name = ["command"], havingValue = "demo-credential")`. 웹 프로세스에는 `merchant_api_credential`에 쓰는 빈이 없다(I-23). DB 권한은 모든 배치에 생기므로(D-DEMO-002 영향) 이 조건이 코드 측 완화다.
- 키: `pc_test_sk_<keyId>.<secret>`, keyId = `demo` + 영숫자 16자(`[A-Za-z0-9]{8,32}` 안), secret = `SecureRandom` 256bit base64url 무패딩 43자. 원문은 출력에만.
- migration(2단계 착수 시 최신 번호 재확인): `db/migration/payment/V29__demo_credential_issuance.sql`(표: `id TINYINT PK CHECK(id=1)`, `key_id`, `merchant_account_id`, `environment CHECK = 'TEST'`, `issued_at`, `actor`, `correlation_id`), `db/runtime-privilege/payment/V30__demo_credential_privileges.sql`: `merchant_api_credential` → `SELECT, INSERT`(**UPDATE·DELETE 없음**), `demo_credential_issuance` → `SELECT, INSERT`. 시험이 UPDATE·DELETE 1142를 확인(I-24).

### 4.6 모드 게이트 — 컨텍스트 생성 전(M2)·웹 fail-closed·주기 job(m3)

- **게이트 위치**: 앱마다 `EnvironmentPostProcessor`(`merchant.adapter.batch.DemoCommandModeGate`, `payment.adapter.batch.DemoCommandModeGate` — 루트 패키지는 진입점 전용) 를 `META-INF/spring.factories`에 등록(Boot 4.1의 등록 키·인터페이스 패키지는 착수 시 확인 — 4.x에서 `org.springframework.boot.EnvironmentPostProcessor`로 이동). `SpringApplication.run`의 환경 준비 단계에서 돌므로 **ApplicationContext·DataSource·Flyway·Hibernate validate 전**이다. (rev 0.3) 게이트는 `Ordered`를 구현하고 order = `ConfigDataEnvironmentPostProcessor.ORDER + 1` — 설정 파일·`spring.config.additional-location`·profile 파일이 환경에 들어온 **뒤** 판정한다(그 전이면 파일로 준 `LIVE`를 못 보고 "없음"으로, 파일로 준 명령 속성을 못 보고 통과로 오판한다). runner도 시작 시 같은 판정을 다시 한다(중복 방어 — exit 3).
  - 명령 속성(`paycore.master.command` / `paycore.payment.command`)이 없으면 아무것도 하지 않는다(웹 프로세스 영향 없음).
  - 있으면 `paycore.<app>.deployment-mode`가 정확히 `DEMO`일 때만 통과. `LIVE`·없음·빈 값·`demo`·`TEST` → `DemoModeRefusedException`(고정 메시지 `DEMO_MODE_REQUIRED`, 값 없음, `ExitCodeGenerator` 구현 → 3).
- **exit 3 경로**: 이 단계에는 컨텍스트가 없어 빈 기반 `ExitCodeExceptionMapper`가 동작하지 않는다. 따라서 ① 예외 자체가 `ExitCodeGenerator`(Boot가 예외에서 직접 읽는 경로), ② 진입점의 순수 함수 `runCommand(args): Int`가 원인 사슬에서 `DemoModeRefusedException`을 찾아 3으로 매핑(ExitCodeExceptionMapper 역할)하고 stderr 고정 한 줄 `demo command refused: DEMO_MODE_REQUIRED` → `exitProcess(3)`. 컨텍스트 이후 단계 예외용으로 `ExitCodeExceptionMapper` 빈도 같은 매핑으로 둔다(중복 방어).
- **웹 컨텍스트**: 명령 속성이 `WebApplicationContext`에 닿으면 `OneShotModeGuard`(BeanDefinitionRegistryPostProcessor)가 `ONE_SHOT_COMMAND_IN_WEB_CONTEXT`로 기동 실패(ADMIN I-12와 같은 code). 순서상 모드 게이트가 먼저 — DEMO가 아니면 exit 3, DEMO면 웹 가드 실패.
- **주기 job(m3, Coordinator 결정)**: ① 진입점이 명령 모드면 인자 앞에 `--spring.main.web-application-type=none`과 끔 인자를 덧붙인다 — master `--paycore.master.entitlement.scheduling-enabled=false`, payment `--paycore.authorization.timing.recovery.enabled=false --paycore.cancellation.timing.recovery.enabled=false --paycore.webhook.fanout.enabled=false --paycore.webhook.delivery.job.enabled=false --paycore.payment.checkout-expiry.job.enabled=false`. ② 가드가 비웹 명령 컨텍스트에서 앱 패키지의 `@Scheduled` 빈을 지운다. runner의 "스케줄러 켬 → 거부" 분기는 **두지 않는다**(rev 0.1의 그 사유 삭제).
- 배치 모드 속성은 명령만 읽고 웹 기동 필수가 아니다(R-3). 한계: 운영에 `DEMO`를 주면 우회 — LIVE Gate(§11).
- 추가 방어(payment): 발급 environment 상수 TEST, TEST backend는 기존 기동 검사로 SIMULATOR만.

### 4.7 argv·출력

```
java -jar master-api.jar --paycore.master.command=demo-seed --paycore.master.deployment-mode=DEMO \
  --output=file:<dir>/demo-merchant.tsv --effective-from=2026-11-02T00:00:00+09:00
java -jar payment-api.jar --paycore.payment.command=demo-credential --paycore.payment.deployment-mode=DEMO \
  --output=file:<dir>/demo-api-key.tsv --merchant-account-id=<MID>
```

- 출력 규칙은 ADMIN `CodeOutput`과 같다(TTY 또는 `CREATE_NEW`+`NOFOLLOW_LINKS`+0600, 상위 디렉터리 공유 쓰기 거부, 생성 직후 권한 재확인, DB 변경 전 준비, 커밋 뒤 같은 채널에 쓰기). **쓰기 실패 시(m4) 명령은 자기가 만든 그 파일(같은 file key·symlink 아님)을 지운다** — 일부만 쓰인 키 원문이 남지 않게. 그 뒤 exit 7.
- 형식(탭, 첫 줄 주석):

```
# PayCore DEMO merchant seed — NOT OPERATIONAL VALUES (D-DEMO-001). DEMO deployment only. Payments possible from effectiveFrom.
merchantId	<id>
merchantAccountId	<MID>
siteId	<id>
siteHost	demo-shop.paycore.test
effectiveFrom	2026-11-01T15:00:00Z
replayed	false
```

```
# PayCore DEMO API key — NOT OPERATIONAL (environment TEST, DEMO deployment only, valid 7 days, no reissue). Use locally, then delete this file.
merchantAccountId	<MID>
environment	TEST
keyId	demo…
apiKey	pc_test_sk_demo….<secret>
validUntil	<UTC>
```

- logger·표준 출력에는 쓰지 않는다. 등록번호·기관 가맹점 참조는 출력하지 않는다.
- (리뷰 N-5 정정) 원문 키·secret·SHA-256은 어떤 로그에도 없다(I-17). 다만 keyId PK 충돌(exit 11)·발급 행 경합(exit 5) 때는 Hibernate `SqlExceptionHelper`가 MySQL 중복 오류 원문(`Duplicate entry '<keyId 또는 1>' for key …`)을 ERROR로 남긴다 — keyId는 비밀이 아닌 식별자(출력 파일·감사 행에도 있다)이고, 앱 코드의 고정 code 로그(`operation=demo-credential …`)는 값을 담지 않는다. 억제하려면 로깅 설정 전역 변경이 필요해 하지 않았다.

### 4.8 종료 코드

| exit | 이름 | 언제 | DB |
|---|---|---|---|
| 0 | SUCCESS | 성공(재생 포함) | 커밋 |
| 1 | FAILED | 분류 안 된 예외(class 이름만 로그) | 앞 단계 커밋 가능 |
| 2 | USAGE | argv 누락·형식(T0 자정 아님, MID 형식) | 무변경 |
| 3 | MODE_REFUSED | DEMO 아님(환경 단계 — DB 연결 전) / 중복 방어 웹 컨텍스트 | 무변경·DB 미접속 |
| 4 | CONDITION_REFUSED | `EFFECTIVE_FROM_PASSED`·`EFFECTIVE_FROM_TOO_SOON` / `MID_UNKNOWN`·`NOT_DEMO_MERCHANT`·`ALREADY_ISSUED` | 무변경 |
| 5 | LOST | 발급 1회성 행 경합 패배(1062 또는 그 INSERT의 교착 희생 — Coordinator 승인 2026-11-01). exit 5 → 다른 실행이 끝난 뒤 같은 명령을 다시 실행한다: `ALREADY_ISSUED`(exit 4) = 다른 실행이 발급했다(그 실행의 출력 파일을 쓴다), exit 0 Issued = 지금 발급됐다. exit 5 자체는 다른 실행의 발급 완료를 보장하지 않는다(교착 희생 패자 포함 — 리뷰 m-3). | rollback |
| 6 | OUTPUT_REFUSED | 출력 준비 거부(ADMIN과 같은 6 code) | 무변경 |
| 7 | OUTPUT_FAILED | 커밋 뒤 쓰기 실패 — 파일 삭제. seed는 같은 argv 재실행으로 재생 출력, credential은 원문 상실 → Demo DB 초기화 후 재seed(D-DEMO-004 재발급 없음) | 커밋 |
| 8 | RETRY_LATER | `AdminCommandFailure.*` | 일부 커밋 — 같은 argv |
| 9 | CONTENT_CONFLICT | `IdempotencyKeyReused` | 무변경 |
| 10 | STEP_REJECTED | 업무 거절(저장·재생됨) | Demo DB 초기화 |
| 11 | KEY_ID_COLLISION | 투영 keyId PK 충돌 | rollback — 재실행 가능 |

로그(값·비밀 없음): `operation=demo-seed result=<exit 이름> step=<NN> reason=<code> correlationId=<id>` / `operation=demo-credential result=… reason=…`. 모드 거부는 logger 초기화 전이라 stderr 고정 한 줄.

### 4.9 감사
- MASTER: 단계마다 `admin_request` + `master_change_history`(actor `system:demo-seed`, correlationId), 업무 변경과 같은 TX. 모드·출력·T0 거부는 DB 전 — 로그만(R-11).
- PAYMENT: `demo_credential_issuance`(keyId·MID·TEST·issued_at·actor `system:demo-credential`·correlationId), 투영 행과 같은 TX. 원문·SHA-256 없음.

### 4.10 관측
- 1회성 프로세스에는 새 지표를 등록하지 않는다(수집 전 종료). exit code·로그·감사 행이 증거.
- 전달 확인(부록 D): MASTER `paycore.master.entitlement.outbox.pending`=0·`.relay.results`, PAYMENT `paycore.payment.entitlement.inbox.outcomes`(APPLIED 증가)·`.lease.min.remaining`>0. label에 MID·keyId 없음.

### 4.11 Demo 표지
contentStatus DEMO(Snapshot으로 PAYMENT까지, LIVE Gate 차단 조건), 정책 코드 `DEMO-*`, 근거 `DEMO-SEED-V1:NON-OPERATIONAL`, actor `system:demo-seed`·`system:demo-credential`, 표시명 `DEMO … (운영값 아님)`, 등록번호 `0000000000`, host `.test`, keyId 접두 `demo`, 출력 머리말 `NOT OPERATIONAL`, 한도 상수 KDoc "D-DEMO-003 Demo 값 — 운영값 아님".

### 4.12 D-ADMIN-003 Demo 예외 범위(D-DEMO-005)
- 예외 대상은 **12단계(한도 설정)와 18단계(카드사 개통 승인)** 두 개뿐 — 시스템 행위자 1명, DEMO 배포만, LIVE 거부, 감사.
- 15단계(수수료 정책 Assignment)는 D-ADMIN-003 "수수료율 변경"이 아니다: 할당하는 버전이 요율 내용이 없는 DEMO 식별 등록부 항목(D-MS2-003 — 내용 hash 없음, 계산 모델은 SETTLEMENT-CONTRACT-001)이고, 기존 가맹점의 요율을 바꾸는 것이 아니라 새 Demo MID의 첫 할당이다. 16단계(정산 정책)도 같은 이유.
- 그 밖의 단계는 D-ADMIN-003 2인 승인 대상이 아니다(LIVE API key 발급은 TEST 키라 해당 없음).

## 5. 불변식(→ 시험)

| ID | 불변식 | 시험 |
|---|---|---|
| I-1 | master 명령은 `deployment-mode=DEMO`일 때만. 6가지 다른 값 → exit 3이 **컨텍스트 생성 전**에 결정: DataSource URL이 도달 불가여도 exit 3(연결 오류 1 아님), 빈 schema에 `flyway_schema_history` 생기지 않음. (rev 0.3) 명령 속성 + `LIVE`를 **설정 파일**(`spring.config.additional-location`)로 줘도 exit 3. runner 재판정 단위 시험 | `DemoSeedModeGateSpec` |
| I-2 | 명령 속성 + 웹 컨텍스트(인자·설정·환경변수) + DEMO → 기동 실패 `ONE_SHOT_COMMAND_IN_WEB_CONTEXT`(두 앱) | `MasterOneShotWebContextSpec`·`PaymentOneShotWebContextSpec` |
| I-3 | 명령 프로세스 예약 작업 0(job 켬 설정이어도), 진입점 인자에 끔 인자 포함. 명령 속성 없는 웹은 기존 job 유지 | `MasterOneShotPeriodicJobsSpec`·`PaymentOneShotPeriodicJobsSpec`, 진입점 `effectiveArguments` 단위 시험 |
| I-4 | 조정자는 provided Use Case + `AdminRequestStore.find`만. 직접 SQL·EntityManager·DSLContext 없음 | ArchUnit `demoSeedUsesOnlyProvidedUseCases` + 위반 fixture `archfixture.demoseed` |
| I-5 | 단계 k 실패 → 1..k-1 커밋 유지, 같은 argv가 이어서 완료, 최종 수 = 무실패 실행 | `DemoSeedResumeSpec`(`MasterFaultInjection` — 05·12·18) |
| I-6 | 같은 argv 재실행: 업무 행·이력·revision·Outbox·`admin_request` 수 불변, 출력 동일, `replayed true`, exit 0 | `DemoSeedIdempotencySpec` ① |
| I-7 | T0 변경 재실행 → exit 9, 새 Business·Merchant·MID 0 | ② |
| I-8 | 동시 2실행 → 각 1개, 결과 ∈ {0, 8}, 하나 이상 0, 같은 MID | `DemoSeedConcurrencySpec` |
| I-9 | 최신 Snapshot에 A0~A4 조건 전부(§4.2 — CSA 한도 1·건당 1,000,000·일 5,000,000, FEE/SETTLEMENT 각 1 DEMO, (CARD, SIMACQA, SIMVAN01) ACTIVE). seed 1회 발행 Snapshot 수 = 10(독립 리터럴 — `entitlement_snapshot_issue` 행 수, 또는 `outbox_event`의 `event_type='ENTITLEMENT_SNAPSHOT'` 행만; 전체 Outbox 행 수가 아님) | `DemoSeedSnapshotContentSpec` |
| I-10 | golden Snapshot → PAYMENT 판정 T0−1ms `Refused(CONTRACT_NOT_EFFECTIVE)`, T0 `Admitted`(한도 Resolved 1,000,000/5,000,000, route SIMVAN01) | MASTER `DemoSeedSnapshotGoldenSpec` + PAYMENT `DemoSeedSnapshotAdmissionSpec` |
| I-11 | T0 자정 아님 → exit 2. T0 단계 미기록 + T0 < 지금 → exit 4 `EFFECTIVE_FROM_PASSED`, 지금 ≤ T0 < 지금+10분 → exit 4 `EFFECTIVE_FROM_TOO_SOON`, 모두 DB 무변경. T0 단계 전부 기록 + T0 경과 → 재생 exit 0 | `DemoSeedEffectiveFromSpec` |
| I-12 | 업무 거절 → exit 10, 로그에 rejection code만 | ③ |
| I-13 | Demo 표지(§4.11), 정책 contentStatus DEMO·hash 없음 | `DemoSeedSnapshotContentSpec` ② |
| I-14 | 감사: 18단계 각 `admin_request` 1 + 이력 actor `system:demo-seed`·같은 correlationId, 재실행은 늘지 않음 | `DemoSeedIdempotencySpec` ③ |
| I-15 | 출력: 0600·`CREATE_NEW`·공유 디렉터리·기존 파일·symlink 거부(DB 전), 비주석 6줄 순서 고정, 쓰기 실패 → 파일 삭제·exit 7 | `DemoSeedOutputSpec`·`DemoCredentialOutputSpec` |
| I-16 | payment 명령: DEMO만(I-1과 같은 표 — 도달 불가 DB URL에서도 exit 3, 빈 schema 무변경, 설정 파일로 준 `LIVE`도 exit 3), MID 투영 없음 → exit 4 `MID_UNKNOWN`, environment TEST만, 재실행 exit 4 `ALREADY_ISSUED`·행 1, 동시 2실행 → 행 1·패자 exit 5 | `DemoCredentialModeGateSpec`·`DemoCredentialCommandSpec`·`DemoCredentialConcurrencySpec` |
| I-17 | 원문 비노출: 저장은 keyId+SHA-256만(원문이 어떤 열에도 없음), 로그·예외·toString·감사 행·표준 출력에 원문·secret 없음 | `DemoCredentialSecretExposureSpec`(`CapturedLogs` 관례) |
| I-18 | 발급 키를 기존 `MerchantAuthenticator`가 MID·TEST로 인증, secret 변조 → 거절, 만료 뒤 거절. `valid_until − valid_from = PT168H`(리터럴 `Duration.ofDays(7)`), `valid_until == lease_until` — 구현 상수와 독립 리터럴로 단언 | `DemoCredentialAuthenticationSpec` |
| I-19 | cross-DB 없음(master 구성에 PAYMENT DataSource 없음, payment 명령은 MASTER 접속 정보 없음) | 기존 구성 시험 확장 |
| I-20 | 진행 판정은 `find`만 — 사전 검사 후 `admin_request` 행 수 불변(거부 경로 포함), 조정자에서 `reserveOrLock` 호출 없음 | `DemoSeedEffectiveFromSpec`(행 수) + ArchUnit |
| I-21 | `MID_UNKNOWN`·`NOT_DEMO_MERCHANT`: 정책 Assignment가 UNDEFINED인 MID(MASTER 시험 Snapshot을 Inbox로 적용)·Snapshot 없는 MID → exit 4, 투영·발급 행 무변경 | `DemoCredentialCommandSpec` ② |
| I-22 | `DemoCredentialProjectionWriter`·`DemoCredentialIssuanceStore`에 의존하는 타입은 `DemoCredentialIssueService`(와 그 adapter 구현)뿐. `JpaMerchantCredentialEntity`·`MerchantCredentialStore`·`JpaMerchantCredentialStore` 무변경 | ArchUnit `demoCredentialWritersOnlyFromIssueService` + 위반 fixture, fingerprint 비교 |
| I-23 | 명령 속성 없는 웹 컨텍스트에 `merchant_api_credential`·`demo_credential_issuance`에 쓰는 빈 0(두 writer·서비스·runner 빈 없음) | `PaymentWebContextNoCredentialWriterSpec` |
| I-24 | runtime 계정: `merchant_api_credential` UPDATE·DELETE → 1142, `demo_credential_issuance` UPDATE·DELETE → 1142, INSERT 가능 | `DemoCredentialPrivilegeSpec`(실제 MySQL runtime 계정) |
| I-25 | keyId 충돌(같은 key_id 행을 미리 둔 DB + 고정 생성기) → exit 11, 발급 행 없음(LOST와 다른 code). 재실행(다른 keyId) 성공 | `DemoCredentialCommandSpec` ③ |

## 6. 시험 계획(실제 MySQL 8.4.11 Testcontainers, runtime 계정)

| 시험 | 앱 | 내용 |
|---|---|---|
| `DemoSeedModeGateSpec` | master | `runCommand(args)`를 ① 도달 불가 URL(`jdbc:mysql://127.0.0.1:1/none`)로 6 모드 값 → 모두 3, ② 빈 schema 컨테이너 + LIVE → 3, `flyway_schema_history` 없음·표 0 |
| `MasterOneShotWebContextSpec` | master | 서블릿 웹 + DEMO + 명령 속성 3경로 → 기동 실패 code |
| `MasterOneShotPeriodicJobsSpec` | master | scheduling-enabled=true + 명령 모드 → `EntitlementSchedules` 없음, 예약 0 |
| `DemoSeedCommandSpec` | master | 실제 argv → 18단계, 출력 6줄 |
| `DemoSeedIdempotencySpec` | master | I-6·I-7·I-14 |
| `DemoSeedResumeSpec`·`DemoSeedConcurrencySpec` | master | I-5·I-8 |
| `DemoSeedEffectiveFromSpec` | master | `MutableMasterClock` — I-11·I-12·I-20 |
| `DemoSeedSnapshotContentSpec` | master | I-9·I-13. 발행 수는 Snapshot 발행 행(`entitlement_snapshot_issue` — 표 이름은 V3에서 확인)만 seed 전후로 세어 **10**과 비교(전체 Outbox 행 아님) |
| `DemoSeedSnapshotGoldenSpec` | master | 고정 시계·고정 ID 생성기로 seed → 최신 Snapshot 봉투를 `contracts/demo-seed-snapshot-v3.golden.json`과 바이트 비교 |
| `harness/tests/test_demo_seed_golden.py` | harness | **앱 코드와 독립**으로 golden 생성(`test_entitlement_golden.py`와 같은 정규 JSON·서명 규칙, 값은 §4.2 리터럴), `--write`로 `apps/master-api/src/test/resources/contracts/`·`apps/payment-api/src/test/resources/contracts/`에 같은 바이트(m7) |
| `DemoSeedSnapshotAdmissionSpec` | payment | golden을 실제 Inbox Use Case로 적용 → T0−1ms·T0 판정(I-10) |
| `PaymentOneShotWebContextSpec`·`PaymentOneShotPeriodicJobsSpec`·`PaymentWebContextNoCredentialWriterSpec` | payment | I-2·I-3(다섯 job 켬)·I-23 |
| `DemoCredentialModeGateSpec`·`CommandSpec`·`ConcurrencySpec`·`SecretExposureSpec`·`AuthenticationSpec`·`PrivilegeSpec`·`OutputSpec` | payment | I-15~I-18·I-21·I-24·I-25 |
| ArchitectureSpec | 두 앱 | I-4·I-20·I-22 규칙 + 위반 fixture, 기존 규칙 유지 |

핵심 변이(하나씩 → RED → 원복): **S1** 모드 검사 제거 **S2** `DEMO` 대소문자 무시 **S3** 웹 컨텍스트 판정 제거 **S4** 주기 job 제거 중단(끔 인자도 제거) **S5** 멱등키에 UUID 추가 **S6** `IdempotencyKeyReused`를 성공 처리 **S7** 12단계 생략 **S8** acquirer `SIMACQB`(형식 안 drift — `SIMACQ-B`는 D-INST-CODE-001 이후 생성 시 거부라 판별력이 없다) **S9** T0 사전 검사 제거 **S10** 정책 contentStatus DEMO → UNDEFINED **S11** 모드 게이트를 runner(컨텍스트 이후)로 되돌림 — I-1 도달 불가 URL 사례가 exit 1·빈 schema 사례가 migration 생성으로 RED / **C1** 원문 로그 **C2** 1회성 PK 제거 **C3** environment LIVE 허용 **C4** MID·DEMO 확인 제거 **C5** `persist` → Spring Data `save`(merge) **C6** 유효기간 7일 → 다른 값(예 8일) **C7** writer 빈 조건 제거(웹 컨텍스트에 빈 생김 — I-23).

Test Fixture Policy: production 상수와 독립 리터럴로 단언(구현식 복제 금지), Fixture Monkey 미사용, 시험 코드는 `src/test`만.

## 7. 8대 원칙

| 원칙 | 적용·증거 |
|---|---|
| Financial Consistency | 금융 사실 생성 없음. 한도(D-DEMO-003)·정책은 Snapshot을 거쳐 원승인에 고정되는 기존 규칙. I-9·I-10 |
| Transaction Safety | 단계별 MASTER 로컬 TX(업무·이력·멱등·Outbox 원자), 외부 호출 없음. Credential 한 PAYMENT TX(발급 행+투영). I-5·I-16·I-25 |
| Idempotency | 결정적 키 18개, 고정 발급 PK. I-6·I-7·I-16 |
| Concurrency Control | 같은 키 insert-first 잠금, PK 1062(merge 금지). I-8·I-16·C5 |
| Recoverability | 단계 커밋 + 같은 argv 이어 가기, 결과 불명확은 같은 키 재확인, T0 사전 검사로 고착 예방. I-5·I-11 |
| Reconciliation | N/A — 기관·원장 금융 사실 없음. MASTER→PAYMENT 정합은 기존 ack·gauge. 후속: MASTER-CREDENTIAL-001이 원천·투영 대사를 정의할 때 Demo 행(keyId `demo`·발급 행)은 "원천 없는 Demo 행"으로 식별·제외 |
| Auditability | `admin_request`+`master_change_history`, `demo_credential_issuance`. I-14 |
| Observability | 새 지표 없음(근거 §4.10), 고정 code 로그·exit, 기존 전달 지표. I-17 |

## 8. JPA·jOOQ·BOM·Kafka/Redis

- **MASTER**: 새 Entity 없음. `AdminRequestStore.find`만 추가 — **하는 일** 비잠금 단건 읽기. **예** 01 키가 있으면 재실행으로 본다. **이유** `reserveOrLock`은 insert-first라 읽기만 하려 해도 PENDING 행을 만든다. **주의** 읽기 결과는 판정 보조이고 같은 키 경합의 권위는 executor의 잠금. 조정자는 18 단계를 하나의 `@Transactional`로 감싸지 않는다(하나의 영속성 컨텍스트·잠금 공유, 실패 시 전부 rollback되어 재생 결과와 어긋남).
- **PAYMENT**: 같은 표에 Entity가 둘 — 기존 읽기용(`insertable=false`)과 새 insert 전용. **하는 일** 새 행을 `persist`+`flush`로 커밋 전에 PK 충돌을 드러낸다. **예** 고정 발급 행 `id=1`을 `save`(merge)하면 Hibernate가 기존 행을 SELECT해 영속 상태로 만들고 `@Immutable`이라 UPDATE도 하지 않아 "성공"처럼 끝난다 → 1회성이 사라진다; `persist`는 INSERT를 내 1062로 실패한다. **이유** Credential·발급 기록을 조용히 덮는 경로를 없앤다. **주의** flush 실패 뒤 TX는 rollback 전용, 재시도는 새 TX·새 keyId. 두 Entity가 같은 표를 매핑해도 `ddl-auto=validate`는 각 열 정의만 검사한다(열 정의는 V5와 일치시킨다). 2차 캐시 없음(기존 관례).
- **jOOQ**: 목록·검색 조회 없음 → 사용 안 함(진행 판정·MID 확인은 단건 JPA 읽기). payment jOOQ codegen은 새 표(V29)를 생성 대상에 포함(빌드 자동).
- **BOM·의존성**: 변경 없음. **Kafka/Redis**: 무관 — 1회성·멱등은 UNIQUE·PK가 보장(ADR-001).

## 9. 질문과 결정

### 9.1 결정됨(2026-10-31 — 재질문 금지)

| 질문 | 결정 | 반영 |
|---|---|---|
| Q-DS-01 | D-DEMO-002: payment-api DEMO 전용 `demo-credential`, D-CPA-001 Demo 예외, MASTER-CREDENTIAL-001 도입 시 대체. 영향: runtime INSERT 권한 모든 배치 — LIVE Gate | §4.5, §11 |
| Q-DS-02 | D-DEMO-003: KRW CSA 기본 1건 1,000,000원·1일 5,000,000원(운영값 아님) | §4.2 12단계, I-9·I-10, 부록 D 금액 |
| Q-DS-03 | D-DEMO-004: 7일(`P7D`, `valid_until = lease_until`), 갱신·재발급 없음, 만료 시 Demo DB 초기화 후 재seed | §4.5, I-18, C6 |
| Q-DS-04 | D-DEMO-005 내 확인: D-MS2-002 그대로, 전날 seed·다음 날 시연 | §4.3, §12 |
| Q-DS-05 | D-DEMO-005: 시스템 행위자 1명 허용(DEMO 전용, D-ADMIN-003 Demo 예외) — 범위 12·18단계 | §4.12 |
| (리뷰) 별도 DB 계정 | Coordinator: 채택 안 함, 코드 측 완화 + LIVE Gate 검토 | §4.5, §11 |
| (리뷰 m3) 주기 job | Coordinator: 가드 제거 + 진입점 끔 인자, runner 스케줄러 거부 사유 삭제 | §4.6 |

### 9.2 남은 질문
없음. 남은 선택은 모두 가역 구현 세부(§10)다.

## 10. 가역적 구현 결정

| ID | 결정 |
|---|---|
| R-1 | 명령 모드 구조를 앱마다 복제(앱 간 production 공유 없음) |
| R-2 | 가맹점 = master-api, Credential = payment-api |
| R-3 | `paycore.<app>.deployment-mode`는 명령만 읽음, 정확히 `DEMO`/`LIVE` |
| R-4 | 1회성 = `demo-seed-v1:*` 키 공간(MASTER migration 없음) |
| R-5 | 진행 판정 = `AdminRequestStore.find` 읽기 전용 TX(m2) |
| R-6 | Demo 값 상수 `DemoMerchantSeedValues`(Fixture 이름 금지) |
| R-7 | host `demo-shop.paycore.test`, 반환 URL `https://demo-shop.paycore.test/return` |
| R-8 | 등록번호 `0000000000` |
| R-9 | T0 여유 10분, 판정 대상 = T0 포함 단계(04·11·12·15·16) 미기록 여부 |
| R-10 | keyId = `demo` + 영숫자 16자 |
| R-11 | MASTER 거부 감사 표 없음(DB 전 거부는 로그) |
| R-12 | `Rejected` 즉시 중단 |
| R-13 | 명령은 relay를 부르지 않음 |
| R-14 | 모드 게이트 = `EnvironmentPostProcessor`(spring.factories) — `SpringApplicationBuilder`로 만든 시험 컨텍스트에도 적용됨. 대안 `addListeners(ApplicationEnvironmentPreparedEvent)`는 진입점 밖 경로(시험)에서 빠질 수 있어 기각. (rev 0.3) `Ordered` — `ConfigDataEnvironmentPostProcessor.ORDER + 1`(설정 파일 반영 뒤), runner가 같은 판정을 재검사 |
| R-15 | keyId 충돌 exit 11 `KEY_ID_COLLISION`(LOST 5와 구별) |
| R-16 | `MidPolicyProfileQuery`는 entitlement provided(payment → entitlement는 provided만 — §16 관례), adapter `payment.adapter.integration.EntitlementMidPolicyProfileAdapter` |

## 11. LIVE Gate 항목

- LIVE 배포 구성에 `paycore.master.deployment-mode=DEMO`·`paycore.payment.deployment-mode=DEMO`·`PAYCORE_MASTER_COMMAND`·`PAYCORE_PAYMENT_COMMAND`가 없음을 배포 검사로 확인(게이트 우회 한계).
- LIVE MASTER에 actor `system:demo-seed` 이력·`demo-seed-v1:*` 키·contentStatus DEMO 정책 Assignment 없음(D-MS2-003 차단과 함께).
- LIVE PAYMENT `demo_credential_issuance` 비어 있음, keyId 접두 `demo` 행 없음.
- **검토 항목(D-DEMO-002·Coordinator)**: runtime 계정의 `merchant_api_credential` INSERT 권한(모든 배치). 현재 완화 = 웹 프로세스에 쓰기 빈 없음(I-23)·UPDATE/DELETE 없음(I-24)·쓰기 Port 사용처 제한(I-22). 별도 DB 계정은 채택하지 않았고, MASTER-CREDENTIAL-001 수신 경로 도입 시 권한 구성을 재검토.
- D-DEMO-002 대체: MASTER-CREDENTIAL-001 도입 시 `demo-credential` 명령·Demo 행 처리 방침 확정.

## 12. 부록 D 갱신안(2단계에서 ADMIN-PAYMENT-OPS-001 부록 D·`.env.example` 반영)

로컬 기동(운영 키·endpoint 금지):
1. `.env`(기존 1항) + **권리 채널 키 한 쌍**(`openssl rand -base64 32`, ADMIN 채널 키와 다른 값): master-api `paycore.master.entitlement.dispatch.signing-key-id`·`.signing-key` = payment-api `paycore.payment.entitlement.inbox.signing-keys[0].key-id`·`.secret`. master-api Demo 설정(`.env.example`에 JSON 예시 추가): `paycore.master.entitlement.{lease-duration, renew-before, scheduling-enabled=true, renew-scan-interval, relay.{poll-interval, batch-size, claim-lease, backoff-initial, backoff-max, backoff-jitter-ratio}, dispatch.{endpoint=http://127.0.0.1:<payment-api paycore.payment.api.internal-port>/internal/v1/entitlement-snapshots, connect-timeout, read-timeout}}`, payment-api `paycore.payment.entitlement.clock-skew-margin`(Demo 2초). 값 이름은 `EntitlementSettings`(master·payment)·`EntitlementInboxSettings` 기준.
2. `docker compose up -d mysql`, `docker compose --profile simulator up -d simulator-mysql`.
3. institution-simulator·payment-api·master-api(웹 모드 — Demo 동안 유지. 재기동은 무방: renew job이 lease를 재발행, 오래 멈추면 PAYMENT가 `SNAPSHOT_EXPIRED`로 fail-closed였다가 재기동 뒤 회복)·internal-admin 기동.
4. **시연 전날 — Demo 가맹점 seed**: `mkdir -m 700 ~/paycore-demo`(ext4 — `/mnt/c` 불가) → master-api 웹 프로세스와 **같은 전체 환경**(DB 계정·migration 계정, `paycore.master.entitlement.*` 전부 — lease-duration·relay·dispatch 포함, 설정 검증이 명령 프로세스에서도 그대로 돈다)으로 `java -jar master-api.jar --paycore.master.command=demo-seed --paycore.master.deployment-mode=DEMO --output=file:$HOME/paycore-demo/demo-merchant.tsv --effective-from=<다음 KST 자정>`. exit 0 확인. 8이면 같은 명령 재실행, 4(`EFFECTIVE_FROM_*`)면 다음 자정으로(첫 실행일 때만 — 이미 일부 기록됐다면 Demo DB 초기화), 9·10이면 Demo DB 초기화 후 처음부터.
5. 전달 확인: MASTER `paycore.master.entitlement.outbox.pending` = 0, PAYMENT `paycore.payment.entitlement.inbox.outcomes` 증가·`paycore.payment.entitlement.lease.min.remaining` > 0(관리 포트).
6. **Demo API key**: payment-api 웹과 **같은 전체 환경 변수**(DB·authorization JSON·webhook·entitlement·admin-channel — 기동 검사가 그대로 돈다)로 `java -jar payment-api.jar --paycore.payment.command=demo-credential --paycore.payment.deployment-mode=DEMO --output=file:$HOME/paycore-demo/demo-api-key.tsv --merchant-account-id=<MID>`. 키는 7일 유효, 재발급 없음(만료·분실 시 Demo DB 초기화 후 4항부터).
7. ADMIN 첫 계정(기존 4항 그대로).

Demo 데이터(**T0 이후 — 시연 당일**):
1. 가맹점 API로 Payment 생성: `Authorization: Bearer <apiKey>`, environment TEST, MID·siteId = seed 출력, KRW, **금액 ≤ 1,000,000원**(D-DEMO-003 건당 한도, 일 합계 5,000,000원), 반환 URL `https://demo-shop.paycore.test/return`. 결제창 URL → 가상 카드 `VC-CREDIT-OK` → 승인. 반환 페이지는 열리지 않는다(예약 도메인) — paymentId·상태는 조회 API(CP-QUERY-001)로 확인.
2~4. 기존 2~4항 그대로.
5. 정리: `~/paycore-demo` 파일 삭제, `docker compose down -v`.

## 13. 2단계 allowedWritePaths(최종안 — Coordinator 확정)

master-api:
- `apps/master-api/src/main/kotlin/com/paycore/master/MasterApiApplication.kt`
- `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/DemoMerchantSeedService.kt`
- `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/provided/DemoMerchantSeedUseCase.kt`
- `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/required/AdminRequestStore.kt`(`find` 추가만)
- `apps/master-api/src/main/kotlin/com/paycore/master/merchant/adapter/persistence/JpaAdminRequestStore.kt`, `AdminRequestJpaRepository.kt`(조회 메서드 필요 시만)
- `apps/master-api/src/main/kotlin/com/paycore/master/merchant/adapter/batch/` 새 파일: `DemoSeedCommandRunner.kt`, `DemoSeedCommandLine.kt`, `DemoSeedOutput.kt`, `DemoSeedExit.kt`, `DemoCommandModeGate.kt`, `DemoModeRefusedException.kt`, `OneShotModeGuard.kt`, `DemoMerchantSeedValues.kt`, `ConsoleAccess.kt`, `SystemConsoleAccess.kt`
- `apps/master-api/src/main/resources/META-INF/spring.factories`
- `apps/master-api/src/test/**`(새 Spec·`archfixture/demoseed/**`·`resources/contracts/demo-seed-snapshot-v3.golden.json`·`ArchitectureSpec.kt`·시험 설정)

payment-api:
- `apps/payment-api/src/main/kotlin/com/paycore/payment/PaymentApiApplication.kt`
- `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/DemoCredentialIssueService.kt`
- `.../payment/application/provided/DemoCredentialIssueUseCase.kt`
- `.../payment/application/required/DemoCredentialProjectionWriter.kt`, `DemoCredentialIssuanceStore.kt`, `MidPolicyProfilePort.kt`
- `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/required/PersistenceFailure.kt`(**`StoreConstraint` enum 값 2개 추가만**), `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/adapter/persistence/PersistenceFailures.kt`(**제약 이름 → enum 매핑 2줄 추가만**) — rev 0.3
- `.../payment/adapter/persistence/JpaDemoCredentialProjectionEntity.kt`, `JpaDemoCredentialProjectionWriter.kt`, `JpaDemoCredentialIssuanceEntity.kt`, `JpaDemoCredentialIssuanceStore.kt`
- `.../payment/adapter/integration/EntitlementMidPolicyProfileAdapter.kt`
- `.../payment/adapter/batch/` 새 파일: `DemoCredentialCommandRunner.kt`, `DemoCredentialCommandLine.kt`, `DemoCredentialOutput.kt`, `DemoCredentialExit.kt`, `DemoCommandModeGate.kt`, `DemoModeRefusedException.kt`, `OneShotModeGuard.kt`, `ConsoleAccess.kt`, `SystemConsoleAccess.kt`
- `apps/payment-api/src/main/kotlin/com/paycore/payment/entitlement/application/provided/MidPolicyProfileQuery.kt`, `.../entitlement/application/MidPolicyProfileService.kt`
- `apps/payment-api/src/main/resources/db/migration/payment/V29__demo_credential_issuance.sql`, `apps/payment-api/src/main/resources/db/runtime-privilege/payment/V30__demo_credential_privileges.sql`(번호는 착수 시 최신 확인)
- `apps/payment-api/src/main/resources/META-INF/spring.factories`
- `apps/payment-api/src/test/**`

harness·문서:
- `harness/tests/test_demo_seed_golden.py`
- `docs/repository/DEMO-SEED-001-DEMO-MERCHANT-SEED.md`, `docs/repository/ADMIN-PAYMENT-OPS-001-PAYMENT-OPS.md`(부록 D만), `docs/repository/ARCHITECTURE-CONVENTIONS.md`(명령 모드 관례 새 절), `.env.example`

수정 금지(명시): `JpaMerchantCredentialEntity.kt`, `MerchantCredentialStore.kt`, `JpaMerchantCredentialStore.kt`, 기존 migration V1~V28(payment)·V1~V6(master), 기존 MASTER Use Case·서비스, `STUDY.md`, `harness/{tasks,questions,progress}.json`, `docs/DECISIONS.md`.

## 14. 위험

| 위험 | 대응 |
|---|---|
| 운영에 DEMO 모드 값 유입 | 게이트 우회 가능 → LIVE Gate 배포 검사·Demo 표지 탐지(§11) |
| LIVE runtime 계정 INSERT 권한(D-DEMO-002) | 쓰기 빈 명령 모드 전용(I-23)·UPDATE/DELETE 없음(I-24)·사용처 제한(I-22), LIVE Gate 검토 |
| 중간 거절 고착 | T0 사전 검사(I-11), exit 10 → Demo DB 초기화 |
| master-api 정지 → lease 만료 | 부록 D 3항, `lease.min.remaining` 지표, 재기동 시 회복 |
| 키 파일 유출 | 0600·ext4·쓰기 실패 시 삭제·TEST·Simulator만·7일(D-DEMO-004) |
| Demo 값 운영 오해 | §4.11 표지, 한도는 D-DEMO-003 값만 |
| Boot 4.1 `EnvironmentPostProcessor` 등록 키 차이 | 2단계 첫 RED(`DemoSeedModeGateSpec`)에서 실제 동작으로 확인 |
| 단계 순서·발행 수가 코드와 다름 | 첫 RED에서 확인·조정, 발행 수 리터럴은 코드 재확인 뒤 고정 |

## 15. 변경 기록

- **rev 0.1**(2026-10-31): 최초 명세. 질문 Q-DS-01~05.
- **rev 0.2**(2026-10-31, 리뷰 CHANGES_REQUIRED + D-DEMO-002~005):
  - M1: D-DEMO-002~005를 머리말·§2·§3·§4.2(12단계 1,000,000/5,000,000)·§4.3·§4.5(`P7D`, `valid_until = lease_until`)·§6·§9(결정 표로)·§11·§12(금액 ≤ 1,000,000원)·§13·§14에 반영. D-ADMIN-003 Demo 예외 범위 = 12·18단계, 15단계가 "수수료율 변경"이 아닌 이유(§4.12).
  - M2: 모드 게이트를 `EnvironmentPostProcessor`(컨텍스트·DataSource·Flyway 전)로 이동, `DemoModeRefusedException`(`ExitCodeGenerator`) + 진입점 매핑(+ 빈 `ExitCodeExceptionMapper` 중복 방어) → exit 3. 시험: 도달 불가 URL → 3, 빈 schema에 `flyway_schema_history` 없음. 변이 S11.
  - M3: 기존 `JpaMerchantCredentialEntity`·`MerchantCredentialStore` 무변경. 새 insert 전용 Entity·`DemoCredentialProjectionWriter`·`DemoCredentialIssuanceStore`, `persist`(merge 금지, 변이 C5), 명령 모드 조건부 빈, ArchUnit 사용처 제한, INSERT만 권한, keyId 충돌 exit 11(LOST와 구별). 불변식 I-22~I-25. 별도 DB 계정 미채택 → LIVE Gate 검토.
  - m1 `EFFECTIVE_FROM_PASSED`(I-11), m2 `AdminRequestStore.find` + `reserveOrLock` 금지(I-20), m3 진입점 끔 인자·runner 스케줄러 거부 삭제, m4 쓰기 실패 시 파일 삭제, m5 `NOT_DEMO_MERCHANT`(I-21), m6 I-18 독립 리터럴·I-23·C5·C6·S11, m7 harness 독립 golden 생성기, m8 부록 D "확인 필요" 해소(설정 이름·지표 이름·payment 전체 환경·master 재기동).
  - Nit: S10 = DEMO → UNDEFINED, 발행 수 10(코드 분석)을 시험이 독립 계수로 단언.
- **rev 0.3**(2026-10-31, 재리뷰 Minor 4건): ① §4.5·§13 — LOST/KEY_ID_COLLISION 분류는 `PersistenceFailure.UniqueConflict` + `StoreConstraint` 2값(기존 파일에 enum·매핑만 추가), 새 adapter에서 MySQL 예외 해석 금지. ② §4.6 — 게이트 EPP order = `ConfigDataEnvironmentPostProcessor.ORDER + 1`, runner 재판정, I-1·I-16에 설정 파일 `LIVE` 사례. ③ I-9·§6 — 발행 수는 Snapshot 발행 행만 계수. ④ §12-4 — `demo-seed`도 웹 프로세스와 같은 전체 환경.

### 15.1 2단계 구현 기록(master-api — 진행 중, DONE 아님)

- **단계 순서 조정**(§4.2 허용 범위, 첫 배포 전): 07 MID 발급 → **08 수단 개통 등록 → 09 개통 승인 → 10 Site 심사 APPROVED**(기존 시험 Fixture `EntitlementCommandFixture.entitled`와 같은 순서). 키 slug: `07-issue-mid`, `08-register-activation`, `09-approve-activation`, `10-approve-site-review`. T0 포함 단계(04·11·12·15·16)·발행 수 10은 그대로.
- **진입점**: Modulith가 기본 패키지 루트 → `merchant` 모듈 비공개 타입 참조를 거부해 `DemoSeedLauncher`를 두지 않고 `MasterApiApplication.kt` 최상위 함수 `isCommandMode`·`effectiveArguments`·`runCommand`·`exitCodeOf`로 옮겼다(다른 모듈 타입 import 없음 — 예외는 `ExitCodeGenerator`로만 판정). 컨텍스트 이후 단계용 `ExitCodeExceptionMapper` 빈은 두지 않았다 — runner가 같은 모드 판정을 다시 하고(중복 방어) exit 3을 직접 낸다.
- **멱등키 공간 매개변수화**: Use Case 명령이 `keyPrefix`·값을 받는다(운영 adapter는 `demo-seed-v1`·`DemoMerchantSeedValues` 고정). 공유 시험 DB에서 시험마다 새 키 공간을 쓰기 위함. 접두 1~33자(키 64자 상한).
- ~~차단 발견~~ → **해소(INST-CODE-001 `df3a1c4`, D-INST-CODE-001)**: 기관 코드 공통 형식 `^[A-Z0-9]{1,32}$`, Simulator 카탈로그 `SIMACQA`·Route `SIMVAN01`. `DemoMerchantSeedValues`를 `SIMACQA`·`SIMVAN01`로 바꾸고 drift 방지 교차 확인을 두었다(harness `test_demo_seed_golden.py` — 카탈로그 전 카드 매입사 = seed 매입사, `.env.example` Route 등록부에 seed Route(TEST), production 상수 = golden 리터럴; master `DemoMerchantSeedValuesSpec` 독립 리터럴).
- **시험 격리**: seed 시험 두 개(`DemoMerchantSeedServiceSpec`·`DemoSeedCommandSpec`)는 공유 `MutableMasterClock`·장애 주입을 `afterAny`(컨테이너 본문 실패 포함)·`beforeSpec`·`afterSpec`에서 되돌린다(이전 `afterEach`는 Given/When 본문이 실패하면 돌지 않아 다른 Spec이 옮겨진 시계로 연쇄 실패).
- **golden(m7)**: harness가 §4.2 리터럴로 `contracts/demo-seed-snapshot-v3.golden.json`(두 앱 같은 바이트)을 만든다. revision 12 = 코드 분석(03·05·07~12·15~18이 각각 entitlement revision +1). MASTER `DemoSeedSnapshotGoldenSpec`은 고정 시계·고정 ID(`ScriptedIdGenerator.enqueue` — 시험 대역 확장)로 운영 Demo 값 seed(등록번호만 새 값 — Snapshot에 없다) 뒤 최신 Snapshot의 contentHash(= MASTER 정규 바이트의 SHA-256)·revision·발행 수 10을 golden과 비교한다. 봉투(eventId·lease)는 비교하지 않는다(PAYMENT 판정 입력은 내용이다 — 봉투 규칙은 기존 `entitlement-snapshot-v3` golden이 이미 고정). PAYMENT `DemoSeedSnapshotAdmissionSpec`이 같은 golden을 실제 서명 Inbox로 적용해 T0−1ms `CONTRACT_NOT_EFFECTIVE`·T0 Admitted를 본다.

- **§6 시험 이름 → 실제 master 시험 파일(리뷰 N-6)**:

| §6 이름 | 실제 파일(apps/master-api/src/test/kotlin/com/paycore/master/merchant/…) | 불변식 |
|---|---|---|
| `DemoSeedModeGateSpec` | `adapter/batch/DemoSeedModeGateSpec.kt` | I-1, I-2(웹 3경로), S1·S2·S3·S11 |
| `MasterOneShotWebContextSpec` | `DemoSeedModeGateSpec.kt`의 웹 컨텍스트 3시험에 합침 | I-2 |
| `MasterOneShotPeriodicJobsSpec` | `adapter/batch/DemoSeedCommandSpec.kt`(비웹 명령 컨텍스트·진입점 인자) | I-3, S4 |
| `DemoSeedCommandSpec` | `adapter/batch/DemoSeedCommandSpec.kt` | I-1(빈 schema), I-6·I-7(실제 argv), I-15 |
| `DemoSeedIdempotencySpec`·`DemoSeedResumeSpec`·`DemoSeedConcurrencySpec`·`DemoSeedEffectiveFromSpec`·`DemoSeedSnapshotContentSpec` | `application/DemoMerchantSeedServiceSpec.kt`(Use Case 수준으로 합침) | I-5~I-14, I-20 |
| `DemoSeedSnapshotGoldenSpec` | `application/DemoSeedSnapshotGoldenSpec.kt` | I-9·I-10(MASTER), S8·S10 |
| (추가) Demo 값 독립 리터럴 | `adapter/batch/DemoMerchantSeedValuesSpec.kt` | §4.11, S8 |
| (추가, 리뷰 m-1) 일반 웹 컨텍스트에 seed 빈 없음 | `adapter/batch/MasterWebContextNoSeedBeanSpec.kt` | m-1 |
| ArchitectureSpec | `apps/master-api/.../architecture/ArchitectureSpec.kt` + 위반 fixture `archfixture/demoseed` | I-4, I-20 |

- **seed 빈 등록(리뷰 m-1)**: `DemoMerchantSeedService`는 stereotype 없이 두고 master `OneShotModeGuard`가 `paycore.master.command=demo-seed`일 때만 등록한다. `DemoSeedCommandLine`·`SystemConsoleAccess`는 명령 속성 조건(`@ConditionalOnProperty(prefix = "paycore.master", name = ["command"])`), CommandLine은 Use Case를 `ObjectProvider`로 받는다. 시험은 `DemoSeedBeansTestConfig`(시험 전용)로 같은 클래스를 빈으로 만든다.

### 15.3 변이 결과(하나씩 적용 → RED 확인 → 원복, 원복 뒤 sha256 동일 — 기록 `~/gate-logs/ds-mut.jsonl`, 2026-11-01)

| 변이 | 내용(실제 적용) | 결과 | 잡은 시험(실패 수/시험 수) |
|---|---|---|---|
| S1 | master 게이트의 DEMO 검사 제거 | RED | DemoSeedModeGateSpec 7/12 |
| S2 | DEMO 대소문자·공백 무시 | RED | DemoSeedModeGateSpec 2/12 |
| S3 | 가드의 웹 컨텍스트 거부 제거 | RED(재실행 2026-11-01 m-1 이후 코드 — 같은 결과) | DemoSeedModeGateSpec 3/12 |
| S4 | 진입점 job 끔 인자 제거 + 가드의 `@Scheduled` 빈 제거 중단 | RED(재실행 m-1 이후 코드) | DemoSeedCommandSpec 2/12 |
| G1 | (리뷰 m-2) 가드의 `demo-seed` seed 서비스 등록 제거 | RED | DemoSeedCommandSpec 1/12(I-3 "운영 배선만으로 Use Case 정확히 하나 — `demoMerchantSeedService`") |
| S5 | 멱등키에 UUID 추가 | RED | DemoMerchantSeedServiceSpec 6/15 |
| S6 | `IdempotencyKeyReused`를 성공 처리 | RED | DemoMerchantSeedServiceSpec 1/15 |
| S7 | 12단계(한도) 생략 | RED | DemoMerchantSeedServiceSpec 7/15 |
| S8 | 매입사 `SIMACQB`(형식 안 drift) | RED | DemoMerchantSeedValuesSpec 1/3, DemoSeedSnapshotGoldenSpec 1/1, harness test_demo_seed_golden exit 1 |
| S9 | (요청 범위 밖 — 미실행) | — | — |
| S10 | 수수료 정책 contentStatus DEMO → UNDEFINED | RED | DemoMerchantSeedServiceSpec 1/15, DemoSeedSnapshotGoldenSpec 1/1 |
| S11 | `spring.factories` 게이트 등록 제거(게이트가 runner 단계로) | RED | DemoSeedModeGateSpec 7/12 |
| C1~C4 | (요청 범위 밖 — 미실행) | — | — |
| C5 | 발급 행 `persist` → `merge` | 1차 **생존** → adapter 수준 시험 추가 후 RED | DemoCredentialCommandSpec 1/14 |
| C6 | 유효기간 7일 → 8일 | RED | DemoCredentialCommandSpec 1/13 |
| C7 | 투영 writer의 명령 조건 제거 | RED | PaymentWebContextNoCredentialWriterSpec 1/2 |

S1·S2·S5~S11·C5~C7은 리뷰 m-1·Q1 변경 **이전** 코드 기준이다(그 변경은 해당 대상 줄을 바꾸지 않았다). 가드·진입점을 건드린 m-1 뒤 S3·S4·G1을 다시 돌렸다(2026-11-01, 원복 sha256 동일 — `~/gate-logs/ds-mut.jsonl`, 1차 기록은 `ds-mut.round1.jsonl`). 시험 전용 seed 빈 구성(`com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig`)은 앱 scan 기준 밖 패키지라 I-3 명령 컨텍스트에 섞이지 않는다(m-2).

### 15.2 2단계 구현 기록(payment-api `demo-credential` — DONE 아님)

- **빈 등록 — I-23 메커니즘(Coordinator 승인 2026-11-01, Q2)**: application 계층은 Boot 조건 annotation을 쓸 수 없다(ArchUnit `applicationDependsOnlyOnPorts` allowlist에 `org.springframework.boot..` 없음). 그래서 `DemoCredentialIssueService`·`SecureRandomKeySource`(중첩 타입)는 stereotype 없이 두고, 명령 가드 `OneShotModeGuard`(명령 속성 조건)가 `paycore.payment.command=demo-credential`일 때만 빈 정의를 등록한다. 두 JPA writer는 §4.5대로 `@ConditionalOnProperty(havingValue = "demo-credential")`. 웹 컨텍스트에는 빈 정의가 없다(I-23). 키 생성기는 서비스 중첩 `fun interface KeySource`(시험은 @Primary 고정 생성기 — I-25·동시성). 별도 파일을 만들지 않아 §13 허용 경로 안에 있다. ArchUnit application allowlist는 넓히지 않는다(Coordinator 결정).
- **비웹 명령 컨텍스트의 웹 보안 구성(발견 → Coordinator 결정 2026-11-01, Q1)**: payment-api의 `SecurityFilterChain` `@Bean`(가맹점 API·결제창·ADMIN 채널)은 `HttpSecurity`가 필요한데 비웹 컨텍스트에는 없어 `--spring.main.web-application-type=none` 기동이 실패했다. 결정: 세 보안 구성 클래스(`PaymentApiSecurityConfiguration`·`CheckoutSecurityConfiguration`·`AdminChannelSecurityConfiguration`)에 `@ConditionalOnWebApplication(type = SERVLET)`(허용 경로 확장). 가드가 filter chain 빈을 지우는 방식은 웹/비웹 분류가 틀리면 fail-open이라 쓰지 않는다(첫 구현에서 제거). 시험: 웹 컨텍스트에 세 filter chain 정확히 존재(`PaymentWebContextNoCredentialWriterSpec`), 비웹 명령 컨텍스트에 `HttpSecurity`·`SecurityFilterChain`·세 구성 빈 없음(`DemoCredentialCommandSpec`). master-api에는 Spring Security 구성이 없다(같은 문제 없음).
- **MID 확인 TX(구현 결정)**: `MidPolicyProfileQuery`는 호출자 TX 참여(MANDATORY — `SiteWebHostQuery`와 같은 관례)이고 `DemoCredentialIssueService`의 한 PAYMENT 로컬 TX 안에서 MID 확인 → 발급 행 사전 조회 → 키 생성 → 발급 행 INSERT → 투영 INSERT 순으로 돈다(모두 쓰기 전 판정, 거부면 쓰기 없음). 정책 Assignment 0개·UNDEFINED 포함 → `NOT_DEMO_MERCHANT`.
- **MID 형식(USAGE)**: `^mid_[0-9A-Z]{26}$`(MASTER `mid_` + ULID). 
- **migration**: V29 `demo_credential_issuance`(+ `ck_demo_credential_issuance_key_id` `^demo[A-Za-z0-9]{4,28}$` 추가 방어), V30 권한. 기존 시험 기대값 갱신(D-DEMO-002 결정 반영 — 완화 아님): `DatabaseAccountSeparationSpec`(표 권한·SHOW GRANTS·적용 버전), `PaymentApiContextSmokeSpec`·`CheckoutWindowCheckMigrationStrategySpec`(적용 버전 목록에 29·30).
- **시험 묶음(명세 §6 이름과 차이)**: `DemoCredentialCommandSpec` 하나가 I-3(payment)·I-15·I-16·I-17·I-18·I-21·I-24·I-25를 같은 비웹 명령 컨텍스트·DB로 확인한다(발급 행이 DB당 하나라 순서 의존). `DemoCredentialModeGateSpec`(I-16 첫 줄·I-2·진입점 인자), `DemoCredentialOutputSpec`(I-15 단위), `PaymentWebContextNoCredentialWriterSpec`(I-23), ArchitectureSpec `demoCredentialWritersOnlyFromIssueService` + 위반 fixture `archfixture.democredential`(I-22).
- **동시 실행 교착(리뷰 M-1 조사, 2026-11-01)**: `DemoCredentialCommandSpec` 동시 2실행을 `--rerun` 20회 돌린 결과 18회 통과, 2회는 패자가 1062가 아니라 **InnoDB 교착 희생**(1213 — 고정 발급 행 `demo_credential_issuance` INSERT)으로 끝났다(분류 밖 → exit 1). 두 TX가 만난 순간(barrier action)의 `performance_schema.data_locks`에는 entitlement_head의 IS·S(REC_NOT_GAP) 잠금만 있고 X 잠금·잠금 대기는 없었다 — "head S 잠금 + 다른 컨텍스트의 X 대기" 가설은 아니다(이 Spec은 자기 컨테이너 DB, 주기 job 빈 없음). 조치(구현 결정, 가역): 1회성 행 INSERT 중 교착 희생은 아무것도 쓰지 않은 경합 패배이므로 **LOST(exit 5)**로 분류한다(재시도 없음 — 상대 완료 여부는 재실행의 ALREADY_ISSUED로 확인). 다른 단계의 교착은 분류하지 않는다. 시험: 동시성 시험에 barrier 시점 잠금 스냅샷 단언(head X·대기 없음) 추가, `DemoCredentialIssueServiceSpec`(단위 — 발급 INSERT 교착 → Lost, 투영 INSERT 교착 → 전파, 둘 다 rollback).
- **exit 5 이후 절차(리뷰 m-3)**: exit 5 → 다른 실행이 끝난 뒤 같은 명령을 다시 실행한다: `ALREADY_ISSUED`(exit 4) = 다른 실행이 발급했다(그 실행의 출력 파일을 쓴다), exit 0 Issued = 지금 발급됐다. exit 5 자체는 다른 실행의 발급 완료를 보장하지 않는다(교착 희생 패자 포함 — 리뷰 m-3).
- **변이 C5 판별**: 동시 실행·재실행 시험만으로는 `persist`→`merge`가 살아남았다(사전 조회가 재실행을 먼저 잡고, 동시 경합은 둘 다 SELECT 0행 뒤 INSERT라 여전히 1062). adapter 수준 시험(이미 있는 고정 행·keyId에 직접 INSERT → `UniqueConflict(DEMO_CREDENTIAL_ISSUANCE / MERCHANT_API_CREDENTIAL_KEY_ID)`, 쓰기 0)을 추가해 C5를 RED로 만들었다.
