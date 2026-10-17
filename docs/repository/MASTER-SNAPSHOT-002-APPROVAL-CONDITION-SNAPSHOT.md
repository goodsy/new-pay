# MASTER-SNAPSHOT-002 승인 시작 조건 데이터(한도·정책 Assignment·카드사/Route) Snapshot 전파 명세 (1단계 명세 → 2단계 구현 반영)

> **2단계 갱신(2026-10-15)**: §12 질문 5개는 모두 권장안으로 답변됐다(**D-MS2-001~005**, `docs/DECISIONS.md`). 도출 3건(기간 소급 금지, Assignment CSA =
> MID 발급 CSA, 전송 게이트는 카드사 개통만 재판정)은 Coordinator가 확인했다. 범위: §13.1 S0~S8, schemaVersion 2, MASTER V5/V6, PAYMENT migration 없음,
> 허용 경로에 `harness/tests/**` 추가, 단일 VERIFY-GATE. 아래 1단계 문장의 "미결"·"권장" 표현은 §12.1 결정과 §16이 우선한다. 2단계 편차는 §16.

작성 2026-10-15 KST. **1단계 명세이며 코드 변경 없음.** 기준 commit `3709caa`(AUTH-CONTRACT-001 DONE, push 완료). 작업 트리에는 Coordinator 소유 상태
파일(`harness/tasks.json`·`harness/progress.json`) 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 업무 정책을 채택하지 않는다 —
§12 질문 후보(Q-MS2-01~05)는 답변 전 해당 운영 동작을 구현하지 않는다. 2단계 TDD는 Coordinator가 질문을 처리한 뒤 시작한다.

표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — Coordinator가
의심되면 질문으로 올린다), **관례** = 금융 의미 없는 가역적 구현 선택(§11), **미결(Q-MS2-xx)** = §12 질문 후보, **Demo** = Simulator·시험 전용
값(운영값 아님, §10), **범위 관찰** = 작업 경계·허용 경로에 관한 Coordinator 확인 사항(§14).

입력 문서(sha256, 2026-10-15 읽은 시점):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001~013, D-CPC-005·008, D-CPD-001, D-MD-001~019, D-MS-001~005, D-CPA-001) | 확정 | `071f97cc…4651` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 작업 | `harness/tasks.json` MASTER-SNAPSHOT-002 AC 2개, AUTH-001 AC(n-2 등) | Coordinator 소유(미커밋) | `99c7ccaf…ad19` |
| MS1 | `docs/repository/MASTER-SNAPSHOT-001-ENTITLEMENT-SNAPSHOT.md` | 구현 반영 명세 | `42d61b76…27e6` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§3·§4.5·§4.6·§17-10·§18-3) | 구현 반영 명세 | `563b0180…cefd` |
| MD | `docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md` | 구현 반영 명세 | `b058fb15…f30f` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §4.1·§4.2·§5.1·§5.5 | 확정 | `a8b02b77…37ae5` |
| 구조 | `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md` §1(정책 구조)·§3(정책 원본과 Demo Seed) | 확정 | `9d237eb9…81fb` |
| 정산 | `docs/baseline/PayCore-Settlement-Control-Policy-v0.1.md` §8, 부록 C(정책 해석) | 확정 | `68ca892b…bd69` |
| 코드 | PAYMENT `TransactionLimit.kt` `17b0b93c…1882`, `AuthorizationStart.kt` `0a5141c5…acc5`, `PaymentAuthorization.kt` `82d64689…c2309`, `entitlement/domain/EntitlementSnapshot.kt` `e3631d58…0f26`, `EntitlementEvaluator.kt` `c5f03cc2…75cb`; MASTER `merchant/domain/EntitlementSnapshot.kt` `1eb22951…52cf` | `3709caa` | — |

---

## 1. AC 대응표(tasks.json MASTER-SNAPSHOT-002 → 명세 절 → 계획 시험)

| # | AC(요약) | 절 | 계획 시험(이름은 가역적) |
|---|---|---|---|
| 1 | (D-AUTH-006) MASTER 버전 TransactionLimitPolicy(CSA 기본 + MID Override, 효력 기간, 적용 1개) 원본·전파, 미전파/모호 시 승인 시작 fail-closed | §3.1, §4, §5.3 A4, §6 | MASTER `TransactionLimitPolicySpec`(중첩 거절·소급 거절·명시 무제한·불변), `AuthorizationTermsAssemblySpec`; PAYMENT `AuthorizationTermsEvaluatorSpec`(0개 Missing·2개 Ambiguous·Override 우선 — Q-MS2-01 답 뒤), `AuthorizationAdmissionStoreSpec`(실DB, v1 head·만료·막힘 → Unavailable) |
| 2 | (AUTH-CONTRACT-001 §17-10) D-MD-017 카드사/Route, D-MD-018 정책 Assignment, 수수료 정책 버전 전파 | §3.2~§3.4, §4, §5.3 A2·A3 | MASTER `CardRouteActivationSpec`, `PolicyAssignmentSpec`, `EntitlementRevisionMatrixSpec` 확장; PAYMENT `AuthorizationTermsEvaluatorSpec`, 계약 golden v2(`EntitlementContractGoldenSpec` 확장) |

AUTH-001이 이 작업에 의존하는 부분(AUTH-001 AC 아님, 이 작업이 제공): 승인 시작 허용 판정 Port(§6)와 그 결과의 `AuthorizationAdmission`·
`LimitPolicyResolution` 매핑(§6.3).

---

## 2. 현황 조사(2026-10-15, `3709caa`)

### 2.1 MASTER-SNAPSHOT-001이 이미 전파하는 것

| 영역 | 있는 것 | 근거 |
|---|---|---|
| 봉투·서명·순서 | 봉투 v1(eventId·eventType·schemaVersion·aggregateId=merchantId·aggregateVersion=revision), HMAC-SHA256(`PayCore-Signature`, 2키 교체), Outbox relay(push, priority HIGH/LOW, claim·backoff·QUARANTINED), PAYMENT Inbox (consumer, eventId) UNIQUE, ACK = 내구 커밋 | MS1 §3.4·§4.7·§5.1·§7, D-MS-004 |
| 버전·lease | `merchant.entitlement_revision`(Merchant 단위 단조), 같은 revision 갱신은 contentHash 같아야 함(다르면 INTEGRITY_CONFLICT로 head 막음), lease·clockSkewMargin, `validUntil = min(lease − skew, 구간 경계)` | MS1 §3.2·§3.3·§3.5·§6.3 |
| 내용(schemaVersion 1) | party, sites(lifecycle·reviewStatus), accounts(mid·lifecycle·blocked·**issuingAgreementId**·serviceKind·assignments·contractWindows·activations(수단 단위)·siteConfirmations·currencies(통화·minorUnit·기간)) | MS1 §3.1, 코드 `EntitlementSnapshotAssembler` |
| PAYMENT 저장 | `entitlement_head`(FOR SHARE/FOR UPDATE), `entitlement_snapshot`(revision별 정규 JSON, schema_version 컬럼 있음, hash CHECK `^ec1:`), `entitlement_mid_owner`, `inbox_event` | `V3__entitlement_projection.sql` |
| PAYMENT 판정 | `EntitlementEvaluator`(U1~U3·F1·E1~E5, CreatePayment eligibility) — **`schemaVersion == 1`만 지원**, 해석기는 **모르는 필드를 거절**(`requireOnly`) | `EntitlementEvaluator.kt`, `EntitlementSnapshot.kt` |

### 2.2 승인 시작(AUTH-CONTRACT-001)이 요구하는데 없는 입력

| AUTH-CONTRACT-001 입력 | 현재 | 이 작업 |
|---|---|---|
| `LimitPolicyResolution`(적용 TransactionLimitPolicy 정확히 1개 — D-AUTH-006) | 원본 없음(MASTER 표 없음), 전파 없음. PAYMENT domain `TransactionLimitPolicies.resolve(candidates, mid, currency)`는 시각·출처를 모름 | §3.1 원본, §5 전파, §6 해석 |
| `AuthorizationAdmission.policyAssignmentVersion`(허용 §4.1-9, D-MD-018 (CSA, MID, 통화) 정책 Assignment) | 원본·전파 없음 | §3.2·§3.3 |
| `AuthorizationAdmission.routeProfile`(허용 §4.1-6 카드사/Route 개통, D-MD-017) | Activation은 수단 단위만. 카드사 단위 개통 기록 없음 | §3.4 |
| `AuthorizationAdmission.entitlementVersion`·권리 재평가(허용 §4.1 2~5·12) | CreatePayment eligibility 판정으로 가능(`es1:<merchant>:<revision>`) | §6 A1에서 재사용 |
| `protocolVersion`, Route Health(허용 §4.1-8) | 없음 | **이 작업 아님** — PAYMENT 로컬 기관 프로파일(AUTH-001 `InstitutionRouteResolver`) |
| 승인 시작 허용 판정 Port(`AuthorizationAdmissionPort`, AC1 §4.6) | 없음(AC1은 이름만) | §6 |

### 2.3 이번 조사에서 발견한 제약(설계에 영향)

1. **해석기가 모르는 필드를 거절**한다 → v1 내용에 필드를 추가하면 현재 PAYMENT는 계약 위반(4xx·격리)으로 받는다. 확장은 **schemaVersion 2**로 해야 한다(§5.1).
2. **같은 revision·다른 hash = INTEGRITY_CONFLICT** → MASTER가 v2로 올라간 뒤 같은 revision의 lease 갱신을 v2로 다시 조립하면 PAYMENT head가 막힌다. **스키마 승격은 revision을 올려야 한다**(§5.2).
3. **미지원 schemaVersion이 더 큰 revision으로 오면 head를 VERSION_UNSUPPORTED로 막는다** → PAYMENT가 v2를 읽기 전에 MASTER가 v2를 보내면 CreatePayment까지 fail-closed. 배포 순서 규칙이 필요하다(§5.4).
4. `entitlement_snapshot_issue`(MASTER)에 schema_version 컬럼이 없다 → 승격 판정에 필요(§8.1).
5. contentHash 접두 `ec1:`은 **정규 JSON 규칙**의 버전이지 내용 스키마 버전이 아니다(MS1 §10) → v2도 같은 규칙이면 `ec1:` 유지, PAYMENT DB CHECK 변경 없음(관례 §11).
6. `AuthorizationAdmission`은 `policyAssignmentVersion` 문자열 하나만 가진다 → 수수료·정산 Assignment 둘을 담는 정규 문자열 관례가 필요(§6.3).
7. MASTER에 수수료·정산 정책 정의 표가 없다(구조 §3: 원본은 MASTER, 계산은 FINANCIAL). 정책 **내용 모델**(요율·반올림·주기)은 SETTLEMENT 쪽 미결과 얽혀 있다 → Q-MS2-03.
8. 인증 세션은 `cardCompany`(고객 선택), `issuerCode`·`acquirerCode`(서버 검증)를 가진다(`AuthenticationSession.kt`). 카드사 개통 판정 키를 무엇으로 할지 정해야 한다 → Q-MS2-04.

---

## 3. MASTER 원본 모델

모두 MASTER DB 소유, `com.paycore.master.merchant`(기존 모듈 — 같은 merchant 잠금·같은 Snapshot 조립 TX를 쓰므로, 관례 §11). Domain은 순수 Kotlin,
JPA Entity는 adapter.persistence(원칙 §3).

### 3.1 TransactionLimitPolicy(D-AUTH-006)

| 항목 | 규칙 | 성격 |
|---|---|---|
| 범위 | `CSA_DEFAULT`(키: CSA + 통화) 또는 `MID_OVERRIDE`(키: MID + 통화). CSA 기본은 그 CSA를 **발급 근거로 가진 MID**들에 적용 | **확정 D-AUTH-006**(CSA 기본 + MID Override, MID+통화 범위), 발급 근거 = `merchant_account.issuing_agreement_id`(MD) |
| 값 | `perTransaction`, `daily` 각각 `LIMITED(amountMinor ≥ 0)` 또는 `UNLIMITED`를 **반드시 명시**. 금액은 통화 minor unit 정수(BIGINT) | **확정 D-AUTH-006**("무제한은 명시 값만"), D-CPC-005(코드에 운영 상한 없음) |
| 정책 TEST/LIVE | 공통(환경 축 없음). 사용량은 PAYMENT가 환경별로 나눔 | **확정 D-AUTH-006** |
| 버전 | 정책 행 하나 = 버전 하나(`tlp_` + ULID). 값(범위·통화·한도)은 **불변**, 바꾸려면 기존 행 종료 + 새 행 | 구조 §3 "활성 정책의 수치는 덮어쓰지 않는다. 새 Version과 적용 기간" — **확정**. ID = 버전은 관례 |
| 효력 기간 | UTC [from, to). `from ≥ 명령 시각`(소급 시작 금지), `to`는 한 번만 설정(NULL → 값), `to ≥ 명령 시각`(소급 종료 금지), 미래 종료 예약 허용 | **도출**(D-MD-005 "거래 당시 근거를 바꾸지 않는다"를 같은 모양의 기간 기록에 적용 — MS1에서 통화 활성화에 같은 해석을 Coordinator가 채택) |
| 중첩 | 같은 키(범위 종류 + CSA/MID + 통화)의 기간 중첩 금지. 열린 행은 생성 컬럼 UNIQUE, 닫힌 구간은 merchant 잠금 아래 domain 검사 | **확정 D-AUTH-006**("정확히 1개") + 기존 패턴(`currency_enablement`) |
| CSA 기본 vs MID Override 관계 | Override가 t에 유효하면 CSA 기본을 정책 **전체** 단위로 대체(필드별 병합 없음) | **미결 Q-MS2-01**(권장안 기술) |
| 효력 경계와 일 한도 날짜 | 경계를 Asia/Seoul 자정에 맞출지 | **미결 Q-MS2-02** |
| 통화 | `currency_unit` FK(KRW·USD만 있음). 통화 활성화 없이도 등록 가능(판정은 PAYMENT가 둘 다 요구) | 도출(D-MD-018: 통화 활성화와 정책은 별개 기록) |
| 감사 | `created_by`·`reason`(필수)·`request_record_id`, 종료 `ended_by`·`ended_at`. 2인 승인 여부는 Q-ADMIN-AUTH(기존 미결) | 원칙 Auditability, 기존 미결 |
| 소속 | CSA 기본: CSA의 계약이 그 Merchant 것(D-MD-013). MID Override: MID가 그 Merchant 것. 둘 다 `merchant_id` 컬럼 + 복합 FK | MD 패턴 |

명령(관리 use case, `AdminOperation` 추가): `RegisterTransactionLimitPolicy`, `EndTransactionLimitPolicy`, `ReplaceTransactionLimitPolicy`(같은 TX에서 기존 행
`to = T`, 새 행 `from = T` — 공백 없는 교체, 관례: 공백은 Missing → fail-closed라 안전하지만 운영 실수를 줄인다).

### 3.2 수수료·정산 정책 버전 등록부(허용 §4.1-9, 구조 §3)

| 항목 | 규칙 | 성격 |
|---|---|---|
| 의미 | FeeSchedule·SettlementPolicy의 **버전 식별자**를 MASTER가 원본으로 등록한다. PAYMENT는 승인 당시 참조만 보존하고 계산하지 않는다 | **확정** 허용 §4.1-9("최종 수수료 계산 불필요, 근거 버전은 남긴다"), 구조 §3("원본 MASTER, 계산 FINANCIAL, PAYMENT는 근거 버전 보존") |
| 모양 | `policy_version`(`pv_` + ULID, append-only): `policy_kind`(FEE_SCHEDULE / SETTLEMENT_POLICY), `policy_code`, `version_no`(≥1, (kind, code) 안 단조), `content_hash`(NULL 허용 — Q-MS2-03), `content_status`, `registered_at/by`, `reason` | 관례(모양) + **미결 Q-MS2-03**(내용 모델 범위) |
| 불변 | 등록 뒤 수정·삭제 없음. 내용이 바뀌면 새 version_no | **확정** 구조 §3 "덮어쓰지 않는다" |
| Demo | 시험·Simulator 설치 경로에만 Demo 행(예: `FEE-DEMO v1`). 운영 migration에 Seed 금지 | **확정** 구조 §3 "샘플 정책을 운영 마이그레이션의 자동 기본값으로 넣지 않는다" |

### 3.3 정책 Assignment(D-MD-018)

| 항목 | 규칙 | 성격 |
|---|---|---|
| 키 | (CSA, MID, 통화, 정책 종류). 종류별(FEE_SCHEDULE·SETTLEMENT_POLICY) 별도 Assignment | **확정** D-MD-002·018("(CSA, MID, 통화) 정책 Assignment"), 구조 §1 그림(`FeeScheduleAssignment`·`SettlementPolicyAssignment` 분리) |
| CSA | **MID의 발급 근거 CSA와 같아야 한다**(다르면 등록 거절) | **도출**: MID는 발급 CSA 하나에 귀속(MD, D-MD-004 "발급 근거 Revision 불변") — 다른 CSA Assignment는 해석이 모호해진다 |
| 대상 | `policy_version_id`(같은 종류) | 관례 |
| 효력 기간·중첩 | UTC [from, to), 소급 금지, 같은 키 중첩 금지(열린 행 UNIQUE + domain 검사). 같은 시각에 종류마다 정확히 1개여야 승인 시작 가능 | **확정** 정산 부록 C("두 후보 동시 적용 모호함은 실패") + 도출(§3.1과 같은 기간 해석) |
| 불변 | 대상 정책 버전은 불변, 바꾸려면 종료 + 새 Assignment(Replace 명령) | **확정** 구조 §3 |
| 외화 | USD Assignment 존재는 외화 정산 방식(Q-SETTLE-CURRENCY, 미결)을 정하지 않는다. 지금은 Demo로만 | 기존 미결 유지 |

명령: `AssignPolicy`, `EndPolicyAssignment`, `ReplacePolicyAssignment`, (등록부) `RegisterPolicyVersion`.

### 3.4 카드사/Route 개통(D-MD-017, 허용 §4.1-6)

| 항목 | 규칙 | 성격 |
|---|---|---|
| 단위 | `card_route_activation`(`cra_` + ULID): (MID, 결제수단 CARD, **카드사 키**) + `route_ref`(PAYMENT 로컬 기관 프로파일 ID) + `institution_merchant_ref`(기관 계약계정 — 기관 가맹점번호 등, Secret 아님) + 상태 + 근거 참조 | **확정** D-MD-017("카드사/Route별 사용 가능 목록은 기관 기준정보, 승인 시 판정"), 허용 §4.1-6("카드 일부 개통은 개통된 카드사/Route만"), §5.2(namespace = 기관 + 환경 + 기관 계약계정) |
| 카드사 키 | 매입사(acquirer) 코드 vs 발급사(issuer) 코드 | **미결 Q-MS2-04** |
| Route 수 | 같은 (MID, 수단, 카드사)에 **종료 아닌 행 하나**(생성 컬럼 UNIQUE) → 승인 시 Route 선택 규칙 없음(0이면 거절, 1이면 그것) | **미결 Q-MS2-04**(권장안 기술) |
| 상태 전이 | UNDER_REVIEW → ACTIVE(기관 근거 필수) / → TERMINATED, ACTIVE ↔ SUSPENDED(재개는 새 근거 — 이력 전체와 달라야 함), ACTIVE·SUSPENDED → TERMINATED 비가역, 재개통은 새 행. 이력 `card_route_activation_event` append-only | **미결 Q-MS2-04**(권장: D-MS-003 PaymentMethodActivation 전이를 그대로 준용) |
| 수단 개통과의 관계 | 카드사 개통 ACTIVE여도 수단 Activation(MID, CARD)이 ACTIVE가 아니면 결제 불가(E4가 먼저 막음). 카드사 개통 등록은 수단 Activation이 TERMINATED가 아닐 때만 | 도출(D-MD-016·017: 수단 개통이 상위 조건) |
| REAL 차단 | Snapshot은 `route_ref`만 싣는다. SIMULATOR/REAL backend의 권위는 PAYMENT 로컬 프로파일 등록부(AUTH-001)이고 MASTER 데이터로 REAL을 열 수 없다. 모르는 `route_ref`는 PAYMENT가 fail-closed | **확정** D-CPD-001, 허용 §5.5, AC1 §9.4 |
| 비밀 | 기관 Credential·키·인증서는 MASTER 표·Snapshot에 없다(Secret 저장소 참조는 AUTH-001 프로파일) | **확정** 허용 §5.1 |

명령: `RegisterCardRouteActivation`, `ApproveCardRouteActivation`, `SuspendCardRouteActivation`, `ResumeCardRouteActivation`, `TerminateCardRouteActivation`.

### 3.5 공통 불변식(모든 새 명령)

1. 기존 `AdminCommandExecutor` 흐름 그대로: admin_request 예약 → merchant FOR UPDATE → (MID FOR UPDATE) → 판정 → 업무 행 + 이력 → `merchant.entitlement_revision + 1` → 같은 TX Snapshot 조립·issue·Outbox(HIGH) → 요청 결과 SUCCESS(MS1 §4.6). 시각은 `Work.lockedNow()`.
2. 업무 거절(중첩·소급·소속 불일치·상태 전이 불가)은 기존 관례대로 거절 결과 저장·재생, revision 불변.
3. append-only 표(`policy_version`, `card_route_activation_event`)는 runtime SELECT·INSERT만(UPDATE·DELETE 1142). UPDATE 권한이 있는 표는 셋이다: `transaction_limit_policy`·`policy_assignment`는 종료(`effective_to`·`ended_*`·`version`)만, `card_route_activation`은 상태 전이(`status`·`evidence_ref`·`status_changed_at`·`version`)만 바뀐다. **"종료는 NULL에서 한 번"은 domain(`end` — ALREADY_ENDED 거절)과 Store(`update`가 저장 행의 시작·통화/버전 불변을 확인)가 강제한다 — DB CHECK가 강제하는 것은 구간 순서(`effective_to > effective_from`), 소급 시작 금지(`effective_from ≥ created_at`), 종료 행위자 쌍, 소급 종료 금지(`effective_to ≥ ended_at`)뿐이다**(review round1 n-4 정정: 1단계 문구는 DB CHECK가 "한 번"까지 강제하는 것처럼 읽혔다). 범위·값 컬럼은 Entity `updatable = false`로 SQL에 나가지 않지만 표 단위 권한은 열을 구분하지 못한다.
4. 운영 수치(한도 금액, 요율)를 코드·migration에 두지 않는다(D-CPC-005, 구조 §3).

---

## 4. Snapshot 내용 확장(schemaVersion 2)

### 4.1 추가 필드(`accounts[]` 아래 — MID 단위, 전부 시각 구간을 원본 그대로)

| 필드 | 원소 | 출처 |
|---|---|---|
| `limitPolicies[]` | `policyId`, `scope`(CSA_DEFAULT/MID_OVERRIDE), `currency`, `perTransaction`({`kind`: LIMITED/UNLIMITED, `amountMinor`?}), `daily`(같은 모양), `from`, `to?` | §3.1 — 그 MID의 Override 전부 + 그 MID 발급 CSA의 CSA 기본 전부 |
| `policyAssignments[]` | `assignmentId`, `kind`(FEE_SCHEDULE/SETTLEMENT_POLICY), `agreementId`, `currency`, `policyVersionId`, `policyCode`, `versionNo`, `from`, `to?` | §3.3·§3.2 |
| `cardRoutes[]` | `activationId`, `paymentMethod`, `cardCompanyKey`, `routeRef`, `institutionMerchantRef`, `status` | §3.4 — 종료 아닌 행만(종료 행은 판정에 쓸 일이 없다 — MS1 Activation과 같은 관례) |

- 지난 구간도 싣는다(MS1 §15-1: 내용은 원본 상태만의 함수 — 발행 시각으로 거르면 같은 revision 갱신이 hash drift).
- 정렬·정규화는 MS1 §10 규칙(키 정렬, UTC ms, 배열은 id 오름차순). 금액은 JSON 정수(D-CPC-009와 같은 정수 직렬화).
- **해석(PAYMENT)이 MASTER 결과를 믿지 않고 다시 확인할 구조 불변식**: 같은 (scope 키, 통화)의 limit 구간 중첩 없음, 같은 (종류, 통화) Assignment 중첩 없음, `agreementId == issuingAgreementId`, 같은 (수단, 카드사) cardRoute 하나. 위반은 `SnapshotContractViolation`(4xx·저장 없음 → MASTER 격리 + 경보). 그래서 "모호"는 판정 단계가 아니라 수신 단계에서 먼저 막힌다(이중 방어 — §5.3 A3·A4는 그래도 2개 이상을 Ambiguous로 판정).

### 4.2 확장 방식 결정 — 기존 Snapshot 확장(권장, 관례) vs 별도 stream

| 후보 | 장점 | 단점 | 판정 |
|---|---|---|---|
| (a) **같은 Merchant Snapshot의 schemaVersion 2** | 하나의 revision·lease·서명·ACK·차단 우선 전파 재사용. 권리와 승인 조건이 같은 MASTER TX·같은 revision이라 "권리는 새것, 조건은 옛것" 조합이 생기지 않는다. 판정 근거 버전이 `es1:<merchant>:<revision>` 하나로 Payment·Authorization에 남는다 | 배포 순서(§5.4)와 스키마 승격 시 revision 증가(§5.2) 필요. 내용이 커진다(MID·통화 수에 비례 — 초기 무시 가능) | **채택(관례, 가역적)** |
| (b) 별도 `AuthorizationTerms` stream(별도 revision·lease) | v1 소비자 영향 없음 | 두 버전의 교차 정합성(권리 r, 조건 q), 두 lease 중 하나만 만료되는 부분 전파, ACK·효력 확인 두 벌, Authorization에 버전 둘 | 기각 — 부분 전파 상태를 새로 만든다 |
| (c) 승인 시 MASTER 동기 조회 | 최신값 | 허용 §4.2 첫째 줄 위반(평상시 동기 조회 금지), MASTER 장애가 승인 장애 | 금지 |
| (d) Kafka/Redis | — | ADR-001 trigger 미충족. 도입해도 Outbox·Inbox·revision·lease가 그대로 필요하고 broker가 권리·한도 정합성을 대신하지 않는다(원칙 §5) | 보류 |

정책 정의(§3.2)가 바뀌어도 Assignment는 특정 버전을 가리키므로 fan-out이 없다. CSA는 한 Merchant 소속(D-MD-013)이라 CSA 기본 한도 변경도 Merchant 하나의
revision만 올린다. 카드사 개통은 MID 단위다. 그래서 Merchant 단위 full-state Snapshot에 담아도 변경 하나가 여러 Merchant 재발행을 부르지 않는다.
(Route 자체의 전역 상태 — 기관 장애·Health — 는 MASTER 데이터가 아니라 PAYMENT 전송 게이트 문제다. AUTH-001.)

---

## 5. 전파 계약

### 5.1 봉투·서명·순서

- 봉투 v1·HMAC·Outbox relay·Inbox·ACK는 **변경 없음**. `schemaVersion` = 2, eventType 동일(`ENTITLEMENT_SNAPSHOT`), aggregateVersion = revision.
- contentHash 규칙 `ec1:` 그대로(정규 JSON 규칙 불변 — §2.3-5). snapshotVersion 문자열 `es1:<merchantId>:<revision>` 그대로(revision이 schemaVersion과 무관하게 Merchant 안에서 유일 — 관례).
- PAYMENT 해석기는 schemaVersion {1, 2}를 지원한다. v1 내용에 v2 필드가 있거나 v2 내용에 v2 필드가 없으면 계약 위반(모르는/빠진 필드 거절 규칙 유지).

### 5.2 스키마 승격과 revision(§2.3-2 해결)

| 경우 | 규칙 | 근거 |
|---|---|---|
| 변경 발행(명령) | 언제나 현재 schemaVersion(2)으로 조립, revision +1(기존) | MS1 §4.6 |
| lease 갱신, 최근 issue의 schema_version = 현재 | 기존대로 같은 revision·leaseSeq+1·LOW | MS1 §3.3 |
| lease 갱신, 최근 issue의 schema_version < 현재 | **갱신이 아니라 승격 발행**: 같은 merchant 잠금 TX에서 `entitlement_revision + 1`, leaseSeq 0, priority HIGH, 지표 `entitlement.schema.promotion` | 같은 revision을 다른 내용으로 재발행하면 PAYMENT가 INTEGRITY_CONFLICT로 막는다(MS1 §3.5) — 결함 아닌 정상 경로를 차단으로 만들지 않는다 |
| hash drift 감시 | 승격 발행은 drift가 아니다(revision이 다름). 같은 schemaVersion·같은 revision의 hash 차이만 기존 ERROR·지표 | MS1 §15-2 |

### 5.3 PAYMENT 저장 형태와 적용 규칙

- **새 표 없음.** 기존 `entitlement_snapshot`(revision별 정규 JSON + `schema_version`)에 v2 내용을 그대로 저장한다. head·mid_owner·inbox도 그대로. 승인 조건은 판정 시 revision 내용에서 읽는다(MS1 §5.2 "판정 로직을 domain에, 받은 내용과 판정 근거 1:1" 관례 유지).
- 적용 규칙(MS1 §3.5 `SnapshotApplicationRule`)은 **변경 없음**: 더 큰 revision만 교체, 같은 revision은 같은 hash일 때 lease만, 작은 revision은 STALE, 미지원 버전이 더 큰 revision이면 head 막음.
- v1 → v2 전환 동안: head가 v1 revision이면 CreatePayment eligibility는 계속 동작하고(v1 판정 그대로), 승인 시작은 `Unavailable(TERMS_NOT_PROPAGATED)`(§6.2 A0) — fail-closed.

### 5.4 배포 순서(운영 절차 — 범위 관찰 §14-4)

1. PAYMENT(v1·v2 해석 지원) 먼저 배포. 2. MASTER(v2 조립·승격 발행) 배포. 역순이면 PAYMENT가 v2를 UNSUPPORTED로 받아 head를 막고 CreatePayment도 503이 된다(fail-closed이지만 가용성 사고). 시험 `SchemaRolloutOrderSpec`이 두 경우를 모두 재현한다(역순 = head 막힘 확인, 정순 = 승격 revision APPLIED). 막힌 head는 MS1 §3.5대로 PAYMENT 업그레이드 뒤 다음 큰 revision(다음 갱신 = 승격 발행)으로 풀린다.

### 5.5 부분·미전파·모호·만료·재전송·순서 역전 — 승인 시작 결과

| 상황 | 승인 시작 결과(§6 Port) | 근거 |
|---|---|---|
| MID 미전파·head 없음 | Unavailable(SNAPSHOT_MISSING) | MS1 U1 |
| head 막힘·미지원 버전 | Unavailable(VERSION_UNSUPPORTED) | MS1 U2 |
| lease 만료(`t ≥ leaseUntil − skew` 또는 잠금 대기 뒤 now) | Unavailable(SNAPSHOT_EXPIRED) | MS1 U3·§15-13 |
| head가 v1(승인 조건 미전파) | Unavailable(TERMS_NOT_PROPAGATED) | D-AUTH-006 fail-closed, AC1 §17-10 |
| (MID, 통화)에 t 적용 한도 정책 0개 / 2개 이상 | Admitted이되 `limit = Missing / Ambiguous` → domain `AuthorizationStart`가 `LimitPolicyUnavailable` 거절(이미 구현) | **확정** D-AUTH-006, 코드 `AuthorizationStart` |
| (종류별) t 적용 정책 Assignment 0개 / 2개 이상 | Unavailable(POLICY_ASSIGNMENT_MISSING / _AMBIGUOUS) | **확정** D-MD-018("둘 중 하나만으로 허용하지 않음"), 정산 부록 C |
| 카드사 개통 없음·ACTIVE 아님 | Refused(CARD_COMPANY_NOT_ENABLED) | **확정** 허용 §4.1-6 |
| 카드사 개통 2개 이상(수신 불변식이 막지만 방어) | Unavailable(ROUTE_AMBIGUOUS) | fail-closed |
| 같은 eventId 재전송 | DUPLICATE(기존), 판정 영향 없음 | MS1 §3.5 |
| 순서 역전(r+1 뒤 r) | STALE, head·판정 불변(조건 축소를 과거 허용이 되돌리지 못함) | **확정** 허용 §4.2 R2 |

HTTP·가맹점 응답 매핑(503 vs 업무 거절, 특히 `LimitPolicyUnavailable`)은 **AUTH-001 AC(n-2)** 소관이다. 이 Port는 "판정 불가(데이터 없음·만료)"와
"업무 거절(카드사 미개통)"을 **구분된 타입**으로 돌려줄 뿐 응답 코드를 정하지 않는다.

---

## 6. PAYMENT 조회 Port(승인 시작 허용 판정)

### 6.1 위치와 모양

- entitlement 모듈 `application.provided.AuthorizationTermsQuery`(공개 타입만 — `EntitlementEvaluationQuery`와 같은 관례, TX **MANDATORY**: 호출자 TX1에 참여, head `FOR SHARE`, 쓰기·외부 호출·캐시 없음).
  입력 `AuthorizationTermsRequest(mid, siteId, paymentMethod, currency, cardCompanyKey)` + `evaluatedAt`. environment는 받지 않는다(정책 TEST/LIVE 공통 D-AUTH-006, 권리 D-MS-001. Simulator/REAL 격리는 AUTH-001 프로파일).
- payment 모듈 `application.required.AuthorizationAdmissionPort`(AC1 §4.6의 이름) + `adapter.integration.EntitlementAuthorizationAdmissionAdapter`가 provided만 호출해 payment domain 타입으로 옮긴다(MS1 §5.4 `EntitlementEligibilityAdapter`와 같은 구조).

### 6.2 판정 순서(entitlement domain `AuthorizationTermsEvaluator`, 앞 단계가 결정하면 멈춤)

| 단계 | 조건 | 결과 |
|---|---|---|
| A0 | MS1 U1~U3(누락·막힘·만료) / head schemaVersion = 1 | Unavailable(SNAPSHOT_MISSING·VERSION_UNSUPPORTED·SNAPSHOT_EXPIRED·TERMS_NOT_PROPAGATED) |
| A1 | 기존 `EntitlementEvaluator` F1·E1~E5를 같은 t·같은 내용으로 재평가(paymentMethod = CARD) — Allowed가 아니면 | Refused(ENTITLEMENT, 기존 reason) 또는 NotFoundOrForbidden — 생성 시 판정을 대신하지 않는다(AC1 §4.6 2~5·12) |
| A2 | `cardRoutes`에서 (수단, cardCompanyKey) 행: 없음 또는 status ≠ ACTIVE | Refused(CARD_COMPANY_NOT_ENABLED) |
| A3 | 종류별 `policyAssignments` 중 `agreementId = 발급 CSA ∧ 통화 ∧ t ∈ [from,to)`: 0 / ≥2 | Unavailable(POLICY_ASSIGNMENT_MISSING / _AMBIGUOUS) |
| A4 | `limitPolicies` 해석(Q-MS2-01 답 규칙 — 권장: t에 유효한 MID_OVERRIDE가 있으면 그것들, 없으면 CSA_DEFAULT들을 후보로): 0 → Missing, 1 → Resolved, ≥2 → Ambiguous | Admitted에 포함(거절은 domain `AuthorizationStart`) |
| OK | — | Admitted(entitlementVersion, route{activationId, routeRef, institutionMerchantRef, cardCompanyKey}, fee·settlement Assignment{assignmentId, policyVersionId, code, versionNo}, limit(Resolved/Missing/Ambiguous), validUntil, evaluatedAt) |

`validUntil = min(A1 eligibility validUntil, A3·A4에서 쓴 구간 끝, A3·A4 후보 전체 중 t 이후 시작하는 구간의 시작)`. 뒤 항목이 중요하다 — t 이후에
시작하는 Override는 해석 결과를 바꾼다. `now ≥ validUntil`이면 Unavailable(SNAPSHOT_EXPIRED)(MS1 §6.3과 같은 규칙: t에서 허용이고 now에서도 같은
판정일 때만).

### 6.3 payment domain으로 옮기기(adapter)

| Port 결과 | payment domain |
|---|---|
| `entitlementVersion` | `AuthorizationAdmission.entitlementVersion`(`es1:…`) |
| fee·settlement Assignment | `AuthorizationAdmission.policyAssignmentVersion` = 정규 문자열 `pa1:fee=<pas_id>/<pv_id>;stl=<pas_id>/<pv_id>`(관례 — 기존 domain 타입 변경 없이 두 참조를 고정. 길이 ≤ 160, 컬럼 크기는 AUTH-001) |
| route | `AuthorizationAdmission.routeProfile` = `routeRef`. `institutionMerchantRef`·`protocolVersion`은 AUTH-001이 로컬 프로파일과 함께 Attempt Route Snapshot에 넣는다 |
| limit Resolved | `LimitPolicyResolution.Resolved(TransactionLimitPolicy(version = policyId, merchantAccountId = MID, currency, perTransaction, daily))` — 출처(CSA/MID)와 무관하게 범위는 MID+통화(D-AUTH-006). `TransactionLimitPolicies.resolve`(기존)로 다시 걸러 MID·통화 불일치를 결함으로 잡는다 |
| limit Missing/Ambiguous | `LimitPolicyResolution.Missing/Ambiguous` |
| Unavailable·Refused | AUTH-001 confirm Use Case가 저장 거절/503으로 매핑(n-2) |

### 6.4 Asia/Seoul 시작일(D-AUTH-007)과의 관계

- **같은 시각 하나**: 승인 시작 TX1의 시각 `at`(마지막 잠금 뒤 서버 시각)을 Port `evaluatedAt`과 `AuthorizationStart.at`에 **같은 값**으로 넘긴다. 정책 선택은 UTC 시각 `at`으로, 일 한도 날짜는 `LimitBusinessDate.of(at)`(Asia/Seoul)으로 정한다. 같은 `at`을 쓰는지는 AUTH-001 confirm Use Case 시험 기준으로 이관(§13-2).
- **점유 고정**: 점유 뒤 정책이 바뀌어도 그 점유·Authorization에 고정된 `limitPolicyVersion`은 바뀌지 않는다(D-AUTH-007 "점유 시 고정", 허용 §4.1 끝). 새 승인만 새 정책으로 판정한다.
- **하루 안 정책 교체**: 사용량 행은 (환경, MID, 통화, Asia/Seoul 날짜)이고 정책과 무관하다. 효력 경계가 Asia/Seoul 자정이 아니면 같은 날 앞 정책 아래 쌓인 사용량에 새 정책의 일 한도가 적용된다 → **Q-MS2-02**.
- FORCED 재점유(D-AUTH-010)의 초과 표시용 정책: 늦은 승인 처리 시각이 아니라 **원 Authorization에 고정된 정책 버전**을 쓰는 것이 D-AUTH-010("승인 시작일 날짜")과 맞다(도출). 그 버전의 값은 revision별 Snapshot 내용에서 다시 읽을 수 있다(`entitlement_snapshot` 보존). 구현은 AUTH-001(§13-2).

---

## 7. 전송 게이트·진행 중 승인과의 관계(도출 — AUTH-001 구현, Coordinator 확인)

| 변경 | 승인 시작(TX1) 뒤·전송(TXd) 전 | 근거 |
|---|---|---|
| 카드사 개통 SUSPENDED/TERMINATED, 권리 차단 | 전송 게이트가 head(S)를 다시 읽어 A0~A2 재판정 → 거절이면 Attempt ABANDONED_BEFORE_DISPATCH(GATE_REFUSED) | **확정** 허용 §4.2 "진행 중 미전송 작업도 포함해 전송 게이트 통제", AC1 §4.1 TXd |
| 한도 정책·정책 Assignment 변경 | 재판정하지 않음 — TX1에 고정한 버전으로 전송 | D-AUTH-007(점유 고정), 허용 §4.1 끝(판정 버전 고정) |
| 이미 전송·전송 불명확 | 원 결과 복구, 철회를 근거로 실패 처리하지 않음 | **확정** 허용 §4.2 |

---

## 8. Migration 계획(버전 확인: MASTER 현재 V1·V2(권한)·V3·V4(권한), PAYMENT V1~V6)

### 8.1 MASTER `db/migration/master/V5__authorization_terms.sql`

| 표 | 핵심 제약 |
|---|---|
| `transaction_limit_policy` | PK `tlp_`ULID CHECK, `scope_kind` CHECK 2종, `agreement_id`/`merchant_account_id` 중 정확히 하나(scope와 일치 CHECK), `merchant_id` + 복합 FK(CSA→계약→Merchant, MID→Merchant), `currency_code` FK `currency_unit`, `per_tx_kind`·`daily_kind` CHECK(LIMITED/UNLIMITED) ∧ (LIMITED ⇔ amount NOT NULL ∧ ≥ 0), `effective_to > effective_from`, `effective_from ≥ created_at`, 종료 행위자 쌍 CHECK, `version`, 생성 컬럼 `open_scope_key`(열린 행만) UNIQUE |
| `policy_version` | PK `pv_`, UNIQUE(kind, code, version_no), kind CHECK, version_no ≥ 1, content_hash 형식 CHECK(NULL 허용 — Q-MS2-03) |
| `policy_assignment` | PK `pas_`, (agreement_id, merchant_account_id) 복합 FK로 **MID의 발급 CSA와 같음**을 DB가 강제(`merchant_account (merchant_account_id, issuing_agreement_id)` UNIQUE 추가 필요 — 같은 V5에서 ALTER), `policy_version_id` FK + kind 일치(복합 FK (policy_version_id, policy_kind)), 기간 CHECK, 열린 행 UNIQUE(agreement, MID, currency, kind) |
| `card_route_activation` | PK `cra_`, MID FK, payment_method CHECK, `card_company_key`·`route_ref` 형식 CHECK(printable ASCII, 길이), `status` CHECK 4종, `evidence_ref`, `version`, 생성 컬럼 `open_route_key`(TERMINATED 아님) UNIQUE |
| `card_route_activation_event` | append-only(activation, seq, from, to, evidence_ref, reason, actor, request_record_id, occurred_at), PK(activation, seq) |
| `entitlement_snapshot_issue` ALTER | `schema_version INT NOT NULL DEFAULT 1` 추가 → 기존 행 1 → 같은 migration에서 DEFAULT 제거(새 행은 명시 값). CHECK schema_version ≥ 1 |

`db/runtime-privilege/master/V6__authorization_terms_runtime_privileges.sql`: `transaction_limit_policy`·`policy_assignment`·`card_route_activation` →
SELECT, INSERT, UPDATE / `policy_version`·`card_route_activation_event` → SELECT, INSERT / `entitlement_snapshot_issue` 기존 유지. `MasterDatabaseAccountSpec`
고정 기대값·`MasterSchemaSpec` 갱신(새 표 추가이지 기대값 완화 아님). jOOQ codegen은 `db/migration/master`만(기존).

### 8.2 PAYMENT

**새 migration 없음**(§5.3). 만약 2단계 구현 중 PAYMENT 표 변경이 필요해지면 V7/V8을 이 작업이 쓰고 AUTH-001에 번호를 알린다(범위 관찰 §14-3).

> **번호 갱신(AUTHN-001, 2026-10-17)**: 이 작업은 PAYMENT migration을 쓰지 않았다. 그 뒤 AUTHN-001이 **V7**(`db/migration/payment/V7__checkout_authentication.sql`),
> **V8**(runtime 권한 `db/runtime-privilege/payment/V8__checkout_authentication_runtime_privileges.sql`), **V8_1**(`V8_1__payment_requested_checkout_window_check.sql`
> — 기동 전략이 V8 뒤 채우기를 끝낸 다음 적용)을 썼다. 따라서 **AUTH-001은 V9부터** 쓴다(AUTHN-001 명세 §10.1·§22-10). 위 문단의 "AUTH-001이 V7 이후"는 이 갱신으로 대체한다.

### 8.3 Demo 데이터

운영 migration에 정책·한도·Route 행을 넣지 않는다(구조 §3). 시험은 Named Fixture, 로컬 Simulator는 별도 설치 경로(Simulator 작업)의 Demo Seed.

---

## 9. 8대 원칙별 검증 계획

| 원칙 | 적용 | 증거(시험) |
|---|---|---|
| Financial Consistency | 적용 — 한도 금액은 통화 minor 정수·명시 무제한, 정책·Assignment·버전 불변(새 행으로만 변경), 승인 근거 버전 고정 | 정책 값 UPDATE 1142(권한) + domain 불변, 같은 키 중첩 거절(경계값: 끝 = 다음 시작 허용, 1ms 겹침 거절), 금액 음수·한쪽 누락 CHECK, golden v2로 MASTER 내용 = PAYMENT 해석 |
| Transaction Safety | 적용 — 명령 = 업무 행 + 이력 + revision + Snapshot issue + Outbox 한 MASTER TX, Inbox 적용 한 PAYMENT TX(기존), 판정 Port는 호출자 TX 참여 | 새 명령 각 저장 지점(정책·이력·revision·issue·outbox·요청 결과) flush 뒤 장애 주입 → 잔존 0, 승격 발행 TX 중단 → revision·issue 잔존 0 |
| Idempotency | 적용 — admin_request 재생, Inbox eventId, 승격 발행 반복 무해 | 같은 명령 키 재요청 재생·다른 내용 충돌, 같은 eventId 동시 8회(v2) → APPLIED 1·DUPLICATE 7, 갱신 작업 2개 동시 승격 → revision +1 한 번 |
| Concurrency Control | 적용 — merchant 잠금 직렬화 + 열린 행 UNIQUE, head FOR SHARE | 실MySQL: 같은 키 정책 동시 등록 2건 → 1건 성공·1건 중첩 거절, Replace 동시 2건, 카드사 개통 승인·정지 동시, 정책 종료 Snapshot 적용 vs 진행 중 판정(FOR SHARE 대기 관찰 `data_lock_waits`) |
| Recoverability | 적용 — at-least-once 재전송, 승격 재발행으로 v1 head 자동 회복, lease 만료 fail-closed | 역순 배포 → head 막힘 → PAYMENT 업그레이드 → 다음 승격 revision으로 회복, 응답 유실 후 DUPLICATE |
| Reconciliation | 적용(권리 범위) — contentHash drift 감지, 승인 조건 누락 점검 조회 | jOOQ `AuthorizationTermsCoverageQuery`: 통화 활성화가 있는데 t에 한도 정책/Assignment(종류별)가 없는 MID·통화 수, 활성 수단 Activation이 있는데 ACTIVE 카드사 개통이 0인 MID 수. 차이 주입 시험 |
| Auditability | 적용 — 정책·Assignment·개통 행위자·사유·근거 참조·요청 ID, 개통 이력 append-only, PAYMENT revision별 내용 보존으로 Authorization 근거 역추적 | 명령마다 이력 행 검사, Authorization 근거 문자열(`es1`·`pa1`·routeRef·tlp) → `entitlement_snapshot` 내용 역추적 시험 |
| Observability | 적용 — MASTER: 승격 발행 counter, 조건 누락 gauge(위 조회), 기존 drift 지표. PAYMENT: 승인 조건 판정 결과·reason counter(`result`, `reason` label만, MID·merchantId·policyId label 금지) | 지표 값·로그 줄 정확 일치, 로그에 `institutionMerchantRef`·근거 참조 원문 없음 |

Kafka/Redis 도입은 위 어느 원칙의 증거도 대신하지 않는다(원칙 §5, ADR-001). 이 작업은 도입하지 않는다.

---

## 10. Demo fixture(운영값 아님)

| 값 | Demo(시험 Named Fixture) | 비고 |
|---|---|---|
| CSA 기본 한도 KRW | 건당 1,000,000 / 일 5,000,000 | Demo. 실제 한도는 계약·위험 정책 확정 후 MASTER 기준정보로 입력 |
| MID Override KRW | 건당 300,000 / 일 UNLIMITED | 명시 무제한 사례(Demo) |
| USD 한도 | 건당 100000(= 1,000.00 USD) / 일 500000 | Demo, 외화 정산은 Q-SETTLE-CURRENCY |
| 정책 버전 | `FEE-DEMO` v1, `STL-DEMO` v1(content_status = DEMO) | 요율 2.8%·D+2 등 내용은 싣지 않음(Q-MS2-03) |
| 카드사 키·Route | `SIMCARD01`, route `SIM-VAN-01`, 기관 계약계정 `SIMMERCH0001` | Simulator 전용 가상 값 |

모든 수치는 test classpath Fixture에만 둔다. `application.yml` 기본값·migration Seed 금지.

---

## 11. 가역적 구현 결정(질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| MS2-01 | 같은 Merchant Snapshot의 schemaVersion 2로 확장(§4.2) | 부분 전파 상태를 만들지 않음 |
| MS2-02 | `ec1:` hash 접두·`es1:` 버전 문자열 유지 | 정규화 규칙 불변, revision 유일 |
| MS2-03 | 스키마 승격 = revision +1 HIGH 발행 | INTEGRITY_CONFLICT 오탐 방지 |
| MS2-04 | MASTER 표는 기존 `merchant` 모듈, 새 명령은 `AdminOperation` 항목 추가 | 같은 merchant 잠금·조립 TX |
| MS2-05 | ID 접두 `tlp_`·`pv_`·`pas_`·`cra_`(ULID 26자) | 기존 접두(`act_ asg_ biz_ csa_ ctr_ cur_ mer_ mid_ sac_ site_`)와 충돌 없음 |
| MS2-06 | 한도 정책 행 = 버전(별도 version 번호 없음) | 행 불변 |
| MS2-07 | 종료는 `effective_to` 1회 UPDATE(+`ended_*`, `@Version`), Replace 명령 제공 | `currency_enablement` 선례 |
| MS2-08 | PAYMENT 새 표 없음, revision별 JSON에서 판정 | MS1 §5.2 관례 |
| MS2-09 | 판정 Port는 entitlement provided `AuthorizationTermsQuery` + payment required `AuthorizationAdmissionPort` + integration adapter | MS1 §5.4 구조, Modulith provided만 |
| MS2-10 | `policyAssignmentVersion` 정규 문자열 `pa1:fee=…;stl=…` | domain 타입 변경 없이 두 참조 고정 |
| MS2-11 | 해석기에서 중첩·CSA 불일치·Route 중복을 계약 위반으로 거절 + 판정에서도 Ambiguous 방어 | 이중 방어 |
| MS2-12 | 카드사 개통 행 종료(TERMINATED)는 Snapshot에서 생략 | MS1 Activation 관례 |
| MS2-13 | jOOQ는 조회 전용(`AuthorizationTermsCoverageQuery`), Command·조립은 JPA | 원칙 §4 |

### 11.1 JPA·jOOQ·BOM(이번 변경에 연결 — 역할 → 예 → 이유 → 주의)

- **Domain–Entity 분리**: 역할 — `TransactionLimitPolicy`·`PolicyAssignment`·`CardRouteActivation`(MASTER domain)은 JPA를 모르고 `Jpa*Entity`·Mapper가 adapter.persistence에만 있다. 예 — `ReplaceTransactionLimitPolicy`는 domain이 "기존 끝 = T, 새 시작 = T, 중첩 없음"을 판정하고 Store가 두 Entity를 반영한다. 이유 — 중첩·소급 규칙을 Spring 없이 단위 시험. 주의 — Mapper가 `version`을 그대로 옮겨 stale domain이 최신 행을 덮지 않게 한다(원칙 §3).
- **변경 감지 + `@Version`**: 역할 — 정책 종료(`effective_to` NULL → T)와 카드사 개통 상태 전이는 잠가 읽은 Entity의 필드 변경이 flush 때 `UPDATE … WHERE version = ?`가 된다. 예 — 같은 개통에 Suspend 두 건이 merchant 잠금으로 줄을 서고, 두 번째는 이미 SUSPENDED를 보고 거절. 주의 — 표 단위 UPDATE 권한(V6)이 없으면 1142, 값 컬럼까지 바꾸는 merge(`save`)는 쓰지 않는다.
- **append-only는 persist만**: `policy_version`·`card_route_activation_event`는 `@Immutable` insert-only. cascade·orphanRemoval 없음(사실을 지우는 경로 차단).
- **영속성 컨텍스트와 Snapshot 조립**: 역할 — 명령이 바꾼 행을 같은 TX·같은 컨텍스트에서 조립 읽기 Port가 다시 읽는다(Store가 Port 끝에서 flush). 예 — 정책 등록 직후 조립된 Snapshot에 새 정책이 실린다. 주의 — flush는 커밋이 아니다. 조립 뒤 장애면 정책·issue·Outbox가 함께 rollback(시험).
- **비관적 잠금**: merchant `FOR UPDATE`(기존 순서). 열린 행 경합은 생성 컬럼 UNIQUE가 최종 방어(1062 → 업무 거절 재판정, 기존 관례).
- **jOOQ**: 조건 누락 점검·전파 조회만. 결과는 비권위(승인 판정은 PAYMENT head + JPA 읽기).
- **BOM**: 새 의존성 없음(Boot 4.1.1 BOM, Kotlin 2.3.21, Hibernate 7.4.5.Final, jOOQ 3.21.7, Modulith 2.1.1, Fixture Monkey 1.2.3 — `gradle/libs.versions.toml` 기존). 빌드 파일 변경이 없으면 GRADLE-DEPS는 회귀 확인용으로만 실행.

---

## 12. 질문 후보(Coordinator → 사용자)

금융·보안 의미가 미결인 것만 적었다. D-AUTH-006(MID+통화·CSA 기본+Override·정확히 1개·없음 차단·명시 무제한·TEST/LIVE 공통), D-AUTH-007(Asia/Seoul
시작일 고정), D-MD-017·018, D-CPD-001은 다시 묻지 않는다. 2인 승인은 기존 Q-ADMIN-AUTH, 외화 정산은 기존 Q-SETTLE-CURRENCY에 남긴다.

**Q-MS2-01 CSA 기본 한도와 MID Override가 함께 있을 때의 해석**
- 질문: 같은 (MID, 통화)·같은 시각에 CSA 기본 정책과 MID Override가 둘 다 유효하면 어느 것을 적용하는가? Override는 한도 항목 일부만 바꿀 수 있는가?
- 권장안: **Override 우선·정책 전체 대체**. t에 유효한 MID Override가 있으면 그 정책 하나가 적용(CSA 기본은 무시), 없으면 CSA 기본. Override는 건당·일 한도를 모두 명시(필드별 병합 없음). 같은 수준(Override끼리·기본끼리) 기간 중첩은 MASTER 등록 거절, 그래도 둘 이상이면 Ambiguous(차단).
- 대안: (a) 둘 다 유효하면 모호로 보고 차단(Override 등록 시 기본 기간과 겹치지 않게 운영자가 나눔). (b) 필드별 병합(Override에 없는 항목은 기본값). (c) 더 엄격한 값(min) 자동 적용.
- 영향: PAYMENT 해석 규칙(§6.2 A4), MASTER 등록 검사, 운영 절차(가맹점별 예외 한도 부여 방식), 감사에 남는 정책 버전 수(대안 b는 한 승인의 근거가 둘).
- 막히는 부분: A4 해석 구현과 해당 시험(MASTER 원본 표·명령·전파·A0~A3은 답 없이 진행 가능).

**Q-MS2-02 한도 정책 효력 경계와 Asia/Seoul 일 한도 날짜**
- 질문: 일 한도 사용량은 Asia/Seoul 날짜 단위(D-AUTH-007)인데, 한도 정책의 시작·종료 시각을 하루 중간(예: 15:00 KST)으로 둘 수 있는가? 그날 이미 쌓인 사용량에 새 정책의 일 한도를 적용하는가?
- 권장안: **한도 정책의 시작·종료는 Asia/Seoul 자정(00:00 KST)만 허용**(MASTER 등록 거절). 하루 = 정책 하나라 일 한도 의미가 분명하다. 긴급 축소가 필요하면 한도가 아니라 차단(신규 결제 중지·카드사 개통 정지)을 쓴다. 건당 한도도 같은 경계를 따른다.
- 대안: (a) 임의 시각 허용, 승인 시작 시각에 유효한 정책의 일 한도를 그날 전체 사용량(이전 정책 아래 점유 포함)에 적용. (b) 임의 시각 허용, 정책 버전별로 사용량을 따로 셈(같은 날 한도가 사실상 늘어날 수 있음).
- 영향: 한도 정책 등록 검증, `validUntil` 경계, 일 중 한도 축소 시 즉시성, 한도 사용량 키(대안 b는 AUTH-001 사용량 키 변경 — D-AUTH-006·007과 충돌 가능).
- 막히는 부분: 한도 정책 등록 검사(경계 규칙)와 그 시험. 저장 구조·전파는 진행 가능.

**Q-MS2-03 수수료·정산 정책 버전의 내용 범위**
- 질문: 승인 시작은 (CSA, MID, 통화) 정책 Assignment가 가리키는 수수료·정산 정책 **버전**을 고정한다. 이번 작업에서 그 정책의 계산 내용(요율·VAT·반올림·주기)까지 MASTER에 모델링해야 하는가, 아니면 버전 식별 등록부만 두는가?
- 권장안: **버전 식별 등록부만**(종류·코드·버전 번호·상태·내용 hash 자리). 계산 내용 모델·검증은 SETTLEMENT-CONTRACT-001(정산 소유 FINANCIAL 작업)에서 정하고, 그때 같은 버전 ID에 내용을 연결한다. 내용이 없는 버전(content_status = DEMO/UNDEFINED)은 Simulator·TEST에서만 쓰고 **LIVE 허용 Gate에서 차단 조건**으로 등록한다.
- 대안: (a) 지금 FeeSchedule·SettlementPolicy 내용 모델까지 MASTER에 구현(반올림·Calendar 등 미결 Q-CALENDAR와 얽힘). (b) 정책 Assignment 요구(D-MD-018)를 내용 모델이 생길 때까지 승인 시작에서 생략(D-MD-018 위반이라 권장하지 않음).
- 영향: 승인된 거래가 가리키는 정책 버전에 나중에 내용이 붙는 순서(FINANCIAL 계산 시점에 내용 필수), LIVE 준비 Gate 항목, 이번 작업 크기.
- 막히는 부분: `policy_version` 표 모양과 등록 명령. Assignment·전파·판정은 버전 ID만 쓰므로 진행 가능.

**Q-MS2-04 카드사 개통의 판정 키와 Route 단위**
- 질문: 허용 §4.1-6 "개통된 카드사/Route만 사용"에서 카드사는 **매입사(가맹점 계약 상대, acquirer)**인가 **발급사(issuer)**인가? 같은 (MID, 카드사)에 Route를 여럿 둘 수 있는가? 개통 상태 전이는 수단 개통(D-MS-003)과 같은가?
- 권장안: **매입사 코드 기준**(카드사 가맹점 계약·기관 가맹점번호가 매입사 단위라서). 발급사 지원 여부는 기관 프로파일 allowlist(D-AUTH-001)가 판정. (MID, CARD, 매입사)당 **종료 아닌 개통 하나 = Route 하나**(승인 시 Route 선택 규칙 없음). 상태 전이는 **D-MS-003 수단 개통과 같은 규칙**(승인·재개에 기관 근거 필수, 종료 비가역, 재개통은 새 행).
- 대안: (a) 발급사 코드 기준. (b) 고객이 고른 카드사(`cardCompany`, 결제창 선택값)를 키로 쓰고 서버 검증값은 대조만. (c) 카드사당 Route 여러 개 + 우선순위·장애 시 대체 Route(라우팅 정책 필요).
- 영향: Snapshot `cardRoutes` 키, 승인 시 대조할 인증 결과 필드(`acquirerCode`/`issuerCode`), 기관 계약계정(namespace) 출처, 장애 시 다른 Route 사용 가능성(대안 c는 같은 승인 operation의 Route 변경 금지 규칙 추가 필요).
- 막히는 부분: `card_route_activation` 표·명령·A2 판정. 한도·정책 Assignment 부분은 진행 가능.

**Q-MS2-05 (보안) Snapshot이 싣는 기관 계약계정(기관 가맹점번호)의 취급**
- 질문: 승인 요청 namespace(허용 §5.2: 기관 + 환경 + 기관 계약계정 + operation + 요청 ID)에 필요한 기관 계약계정 값을 MASTER Snapshot으로 PAYMENT에 보내도 되는가? 이 값의 노출 등급은?
- 권장안: **Snapshot으로 전달(HMAC 서명 채널 D-MS-004)**, Secret은 아니지만 **내부 식별자 등급**: 로그·지표·가맹점 응답·오류 메시지에 원문 금지(내부 조회 화면은 Q-ADMIN-AUTH 권한 뒤 마스킹), 기관 Credential·키는 계속 Secret 저장소만.
- 대안: (a) PAYMENT 로컬 기관 프로파일 설정에 MID별로 둠(MASTER 원본과 이중 관리 — 불일치 위험). (b) Secret 등급으로 취급해 Secret 저장소 참조만 전달.
- 영향: Snapshot 필드·로그 마스킹 시험, 운영 설정 절차, 개통 데이터의 원본 위치.
- 막히는 부분: `institutionMerchantRef` 필드 포함 여부(없으면 cardRoutes 나머지는 진행 가능, AUTH-001 Attempt namespace 구성이 이 답에 의존).

### 12.1 결정 반영(2026-10-15, 사용자 답변 → D-MS2)

| 질문 | 답변(모두 권장안) | 결정 | 반영 위치(코드) |
|---|---|---|---|
| Q-MS2-01 CSA 기본 vs MID Override | Override 우선·정책 전체 대체, 같은 수준 기간 중첩은 MASTER 등록 거절, PAYMENT에서 2개 이상이면 Ambiguous(fail-closed) | **D-MS2-001** | MASTER `TransactionLimitPolicy.register`(같은 `scope.key`+통화 중첩 → LIMIT_POLICY_OVERLAP) + V5 열린 행 UNIQUE, PAYMENT `AccountEntry.checkTerms`(수신 거절)·`AuthorizationTermsEvaluator` A4 |
| Q-MS2-02 효력 경계와 Asia/Seoul 일 한도 | 시작·종료는 Asia/Seoul 자정만 | **D-MS2-002** | `SeoulMidnight`, `TransactionLimitPolicy.register/end`(LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT), V5 CHECK `TIME(...) = '15:00:00'` |
| Q-MS2-03 정책 버전 내용 범위 | 식별 등록부만, 내용 없는 버전은 TEST/Simulator 전용·LIVE Gate 차단 | **D-MS2-003** | `PolicyVersion`(UNDEFINED/DEMO), V5 `policy_version`, PAYMENT `PolicyTerms.contentStatus`·`AdmissionResult.Admitted.policyContentStatus`(전달만 — Gate 판정은 LIVE 준비) |
| Q-MS2-04 카드사 개통 키·Route | 매입사 코드 키, (MID, CARD, 매입사)당 종료 아닌 개통 1개 = Route 1개, 발급사는 allowlist, 전이 D-MS-003 | **D-MS2-004** | `CardRouteActivation`(전이·CARD_ROUTE_*), V5 `card_route_activation` 생성 컬럼 UNIQUE, PAYMENT A2 |
| Q-MS2-05 기관 계약계정 취급 | 서명 Snapshot으로 전달, 내부 식별자 등급(로그·지표·응답·예외 원문 금지, 관리 조회 마스킹) | **D-MS2-005** | `InstitutionMerchantRef.toString` 가림, `CardRouteEntry`·`RouteTerms`·`AdmittedRoute` toString 가림, 통합 이벤트 payload 제외·감사 이력에만, 로그 미기록 시험 |

(검토했으나 질문으로 올리지 않은 것: 정책·Assignment 기간의 소급 금지 — MS1에서 통화 활성화에 Coordinator가 채택한 D-MD-005 유추와 같다. 정책 Assignment CSA = MID 발급 CSA — MID가 발급 CSA 하나에 귀속하는 확정 구조에서 도출. 승인 시작 뒤 전송 전 변경 처리(§7) — 허용 §4.2·D-AUTH-007에서 도출. Snapshot 확장 방식 — 금융 의미 없는 전파 방식이라 관례 §11.)

---

## 13. 이번 작업 범위와 이관

### 13.1 이번 작업(2단계 TDD) 범위 제안

| 단계 | 내용 | 막힘 | 시험(추정) |
|---|---|---|---|
| S0 | ArchUnit: MASTER 새 domain 순수성(기존 규칙 대상 자동 포함 확인), PAYMENT `AuthorizationAdmissionPort` 구현은 `adapter.integration`만, entitlement provided 노출 범위(`ModulithProvidedExposureSpec`), 캐시 금지 규칙이 새 Query에 적용 | 없음 | 3–5 |
| S1 | MASTER domain: TransactionLimitPolicy(값·기간·중첩·소급·Replace), PolicyVersion, PolicyAssignment(CSA 일치·중첩), CardRouteActivation 전이 | Q-MS2-02(경계 검사), Q-MS2-03(등록부 모양), Q-MS2-04(카드사·전이) | 40–55 |
| S2 | MASTER V5·V6 + `MasterSchemaSpec`·`MasterDatabaseAccountSpec` | Q-MS2-03·04 답 뒤 권장(적용된 migration은 고치지 않는다) | 20–28 |
| S3 | MASTER 명령 + 같은 TX Snapshot 발행, `EntitlementRevisionMatrixSpec` 확장(새 AdminOperation 전부 — 기존 entries 비교 규칙이 누락을 잡는다), rollback·경쟁 | S1·S2 | 30–40 |
| S4 | Snapshot v2 조립(§4) + 승격 발행(§5.2) + issue schema_version | Q-MS2-05(필드) | 15–20 |
| S5 | PAYMENT v1·v2 해석(구조 불변식 §4.1), `EntitlementEvaluator` v2 지원, 적용 규칙 회귀, 역순·정순 배포 시험 | 없음 | 20–28 |
| S6 | PAYMENT `AuthorizationTermsEvaluator`(A0~A4, validUntil 경계) + Query(FOR SHARE, 실DB) + Port adapter·매핑(§6.3) | Q-MS2-01(A4), Q-MS2-04(A2) | 40–55 |
| S7 | 계약 golden v2(두 앱 시험 자원 같은 파일, 서명 vector 포함) | §14-1 경로 | 6–10 |
| S8 | Observability(승격·판정 counter, 조건 누락 gauge) + jOOQ `AuthorizationTermsCoverageQuery` + 문서(ARCHITECTURE-CONVENTIONS 새 절, MS1 §13-4 이관 표시, 이 명세 2단계 편차) | 없음 | 10–15 |

합계 추정 184–256건(현재 GRADLE-CHECK 하한 1103, 최근 VERIFY 1142 기준 증가분). 하한 갱신은 `harness/commands.json`(기대값 완화 금지).

답 없이 계속 가능: S0, S1의 한도 값·중첩·소급·Assignment·등록부 식별 부분, S3 해당 명령, S4(Q-MS2-05 필드 제외), S5 전부, S6의 A0·A1·A3, S8.

시험 목록(실DB 필수 — Mock 단독 금지):

| 영역 | 시험 |
|---|---|
| 경쟁 | 같은 키 한도 정책 동시 등록 → 1 성공·1 중첩 거절 / Replace 동시 2 / 카드사 개통 승인·정지 동시 / 승격 발행 갱신 작업 2개 동시 → revision +1 한 번 / 조건 축소 Snapshot 적용(X) vs 진행 중 판정(S) 직렬화 |
| rollback | MASTER 새 명령 각 저장 지점 뒤 실패 → 정책·Assignment·개통·이력·revision·issue·outbox·요청 결과 잔존 0 / 승격 발행 중단 → 잔존 0 |
| 재전송 | v2 같은 eventId 동시 8 → APPLIED 1·DUPLICATE 7, 응답 유실 후 DUPLICATE·ACK |
| 순서 역전 | 정책 종료(r+1) 먼저·정책 유효(r) 나중 → STALE, 승인 조건 Missing 유지 / v1 r이 v2 r+1 뒤 → STALE |
| 배포 순서 | 역순(PAYMENT v1 전용) → UNSUPPORTED·head 막힘·CreatePayment 503 / 정순 → 승격 APPLIED |
| 시각 경계 | 정책 to 경계·Override 시작 경계·Assignment 경계 전후, validUntil = 경계, 1ms 전/정확히 경계 |
| 모호·누락 | 한도 0·2(수신 불변식 우회 fixture로 판정 방어 확인), Assignment 종류 하나만 있음 → Unavailable, 카드사 미개통·SUSPENDED → Refused, v1 head → TERMS_NOT_PROPAGATED |
| 권한 | V6 SHOW GRANTS 기대값, append-only UPDATE/DELETE 1142, 정책 값 컬럼 UPDATE는 domain·Store가 막음(권한은 표 단위라 컬럼 보호는 시험으로) |
| 보안 | 로그·예외 메시지에 기관 계약계정·근거 참조 원문 없음, 모르는 routeRef는 판정 결과에 그대로 싣고 AUTH-001 프로파일이 fail-closed(이 작업은 REAL 표현 자체가 없음을 확인) |

Fixture(Test Fixture Policy): Named Fixture `TransactionLimitPolicyFixture`(`csaDefaultKrw()`, `midOverrideUnlimitedDaily()`, Invalid 의도 이름 `overlappingWith(...)`), `PolicyAssignmentFixture`, `CardRouteActivationFixture`, `EntitlementSnapshotV2Fixture`. 상태는 Domain Factory·전이로만, 기대값은 명세 표에서 독립 기술, Fixture Monkey는 대량 Store 시험의 프로젝트 Fixture 안에서만(seed 고정), `src/test`에만.

### 13.2 이관

| 항목 | 대상 | 이유 |
|---|---|---|
| confirm Use Case가 Port `evaluatedAt`과 `AuthorizationStart.at`에 같은 시각을 넘기는지 시험 | AUTH-001 | §6.4 |
| Unavailable/Refused/LimitPolicyUnavailable의 HTTP·저장 거절 매핑 | AUTH-001(n-2) | §5.5 |
| 전송 게이트에서 A0~A2 재판정(카드사 개통 정지 반영), 한도·정책 재판정 안 함 | AUTH-001 | §7 |
| FORCED 재점유 초과 표시에 원 Authorization 고정 정책 사용 | AUTH-001 | §6.4 |
| Route 프로파일 등록부(protocolVersion·backend SIMULATOR·Capability·Secret 참조), 모르는 routeRef fail-closed, Attempt Route Snapshot에 `institutionMerchantRef` | AUTH-001 | §3.4 |
| `policyAssignmentVersion`·`routeProfile` 컬럼 크기 | AUTH-001 | §6.3 |
| 정책·한도·카드사 개통 관리 화면·web 명령, 2인 승인 | MASTER-LIFECYCLE-001 / internal-admin(Q-ADMIN-AUTH 후) | 관리 인증 미결 |
| **관리 조회·화면에서 기관 계약계정(`institution_merchant_ref`, `master_change_history.detail.institutionMerchantRef`) 마스킹**(D-MS2-005 "관리 조회 마스킹" — review round1 조건부 판정) | MASTER-LIFECYCLE-001 / internal-admin(관리 조회 API·화면을 만드는 작업이 함께 구현·시험) | 이번 작업에는 관리 조회 경로가 없다(값 객체·Entry·Terms toString 가림, Outbox 제외, 로그 미기록만 구현) |
| review round1 n-3·n-5(선택 항목, 이번에 처리하지 않음) | Coordinator 판단(후속 작업 또는 AUTH-001) | 선택 항목 — 미처리 사실을 남긴다 |
| 수수료·정산 정책 내용 모델·계산·FINANCIAL 전파 | SETTLEMENT-CONTRACT-001 | Q-MS2-03 |
| 내용 없는 정책 버전·Simulator Route로 LIVE 허용 금지 Gate 항목 | LIVE 준비 Gate | Q-MS2-03, D-CPD-001 |
| 환경별 Route 분리(LIVE→REAL, TEST→SIMULATOR) | LIVE 준비 Gate | 지금 REAL 없음(D-CPD-001) |
| V3 이전 Merchant bootstrap 발행(MS1 §13-9) — 이 작업의 승격 발행은 issue가 있는 Merchant만 | LIVE 준비 Gate(기존 이월) | MS1 n-7 |
| 지급계좌 준비(허용 §4.1-10) | 별도 작업(MS1 §13-1) | 범위 밖 |

---

## 14. 범위 관찰(Coordinator 확인)

1. **계약 golden 생성 절차 경로**: MS1 S7 golden은 `harness/tests/test_entitlement_golden.py`가 독립 생성·비교한다. v2 golden을 같은 방식으로 하려면 `harness/tests/**`가 허용 경로에 필요하다(현재 allowedWritePaths: `docs/repository/**`, `apps/master-api/**`, `apps/payment-api/**`, `libs/**`, `harness/commands.json`). 추가하지 않으면 v2 golden은 두 앱 시험 자원 바이트 비교까지만 하고 독립 생성은 이월.
2. **PAYMENT `payment` 모듈 변경**: §6.1 `AuthorizationAdmissionPort`(application.required)·adapter는 `apps/payment-api/**` 안이라 허용 경로 안. AUTH-CONTRACT-001이 고정한 domain 타입(`AuthorizationAdmission`·`TransactionLimitPolicy`)은 **바꾸지 않는다**(§6.3 정규 문자열).
3. **PAYMENT migration 번호**: 이 작업은 PAYMENT migration 없음(§8.2). ~~AUTH-001이 V7부터 쓴다고 보고~~ — AUTHN-001이 V7·V8·V8_1을 써서 AUTH-001은 V9부터다(§8.2 번호 갱신). 2단계에서 필요해지면 Coordinator에 먼저 알린다.
4. **배포 순서(§5.4)**는 운영 절차다. 현재 배포 파이프라인이 없으므로 시험으로만 고정하고 runbook 항목으로 남긴다. **runbook 초안(review round1 n-6)**:
   1. PAYMENT(v1·v2 해석)를 모든 인스턴스에 먼저 배포하고 `paycore.payment.entitlement.inbox.outcomes{outcome=UNSUPPORTED}`가 늘지 않는지 확인한다.
   2. 그 뒤 MASTER(v2 조립·승격 발행)를 배포한다. 역순이면 v2 변경 발행이 UNSUPPORTED로 head를 막아 CreatePayment까지 503이 된다(fail-closed, 가용성 사고).
   3. **승격 지연**: MASTER 배포 뒤 v1 issue만 있는 Merchant는 다음 권리 입력 변경(즉시) 또는 **다음 lease 갱신 시점**(잔여 lease < renewBefore가 될 때 — 최대 약 leaseDuration − renewBefore + 갱신 스캔 주기)에야 v2 revision이 나간다. 그동안 그 Merchant의 승인 시작은 TERMS_NOT_PROPAGATED(fail-closed)이고 CreatePayment는 v1로 계속 동작한다. 지연을 없애려면 배포 직후 전 Merchant 1회 재발행 운영 명령(MS1 §13-9 bootstrap 발행과 같은 명령 — 감사·멱등)이 필요하다(LIVE 준비 Gate).
   4. 확인: MASTER `paycore.master.entitlement.schema.promotions` 증가가 멈추고, PAYMENT 승인 조건 판정 `reason=TERMS_NOT_PROPAGATED`가 0이 될 때 전환 완료.
5. **MS1 §13-4**("정책 Assignment·카드사/Route Snapshot 확장")는 이 작업으로 대체된다. MS1 문서 갱신은 2단계 S8에서 이관 표시만 한다.
6. 시험 수 추정이 커서(§13.1) task timeout을 정할 때 S1~S3(MASTER)과 S4~S7(전파·PAYMENT)을 두 묶음으로 나눠 검증하는 것을 제안한다(같은 파일·migration을 두 구현자가 동시에 고치지 않음).

## 15. 기존 결정과의 충돌·공백

1. ~~D-AUTH-006 두 수준의 관계 미정~~ → **해소 D-MS2-001**(Override 우선·전체 대체, 같은 수준 중첩 거절, 2개 이상 Ambiguous).
2. ~~D-AUTH-007과 시각 단위 정책 효력의 결합~~ → **해소 D-MS2-002**(Seoul 자정 경계만 — 일 한도 날짜당 정책 하나).
3. ~~D-MD-017 카드사 키·단위 미정~~ → **해소 D-MS2-004**(매입사 코드, Route 하나).
4. ~~허용 §4.1-9 정책 버전 내용 모델 부재~~ → **해소 D-MS2-003**(식별 등록부, 내용은 SETTLEMENT-CONTRACT-001, LIVE Gate 차단).
5. MS1 해석기의 모르는 필드 거절 + 같은 revision hash 비교 → schemaVersion 2·승격 revision(§5.1·§5.2)으로 해결, 새 정책 아님.
6. AUTH-CONTRACT-001 §4.6 "정책 없음 = Unavailable 503"과 2단계 domain `LimitPolicyUnavailable`(업무 거절 결과)의 차이는 AUTH-001 n-2로 이미 이관됐다 — 이 작업은 구분된 타입만 제공한다.

## 16. 2단계 구현 편차·결정 기록(2026-10-15)

### 16.1 S별 상태(구현자 보고 — Verifier/Reviewer 판정 아님)

| 단계 | 상태 | 코드 | 시험 |
|---|---|---|---|
| S0 ArchUnit | 구현 | — | payment `ArchitectureSpec`: 캐시 금지 규칙에 `EntitlementAuthorizationAdmissionAdapter` 추가, 새 규칙 "AuthorizationAdmissionPort는 adapter.integration만 구현·entitlement provided만 사용". MASTER 새 domain은 기존 순수성 규칙 대상(패키지 `merchant.domain`) |
| S1 MASTER domain | 구현 | `merchant/domain/AuthorizationTerms.kt`(ID 4종, `AcquirerCode`·`RouteRef`·`InstitutionMerchantRef`·`PolicyCode`, `SeoulMidnight`, `LimitValue`, `LimitScope`, `LimitTarget`, `TransactionLimitPolicy`, `PolicyVersion`, `PolicyAssignment`, `CardRouteActivation`), `MasterRules.kt` 거절 18종 | `AuthorizationTermsSpec` |
| S2 V5·V6 | 구현 | `V5__authorization_terms.sql`, `V6__authorization_terms_runtime_privileges.sql` | `AuthorizationTermsSchemaSpec`, `MasterSchemaSpec`·`MasterDatabaseAccountSpec` 기대값(새 표 추가), smoke 2종 버전 목록 |
| S3 명령·같은 TX 발행 | 구현 | `MerchantAuthorizationTermsUseCase`·`MerchantAuthorizationTermsService`, `AuthorizationTermsPorts.kt`, `JpaAuthorizationTermsEntities.kt`·`JpaAuthorizationTermsStores.kt`, `AdminOperation` 12종 | `MerchantAuthorizationTermsServiceSpec`(revision·HIGH·거절 무변경·장애 주입 rollback 5지점·동시 등록·D-MS2-005), `EntitlementRevisionMatrixSpec` 단계 14개 추가 |
| S4 v2 조립·승격 발행 | 구현 | MASTER `EntitlementSnapshot.kt`(schema 2, `LimitPolicyState`·`PolicyAssignmentState`·`CardRouteState`), `EntitlementSnapshotPublisher`(issue schemaVersion), `EntitlementLeaseService.renew`(승격 발행), issue Entity·지표 `schema.promotions` | `AuthorizationTermsAssemblySpec`, 서비스 spec 승격 시험, `EntitlementContractGoldenSpec`(v2) |
| S5 PAYMENT v1·v2 해석 | 구현 | PAYMENT `EntitlementSnapshot.kt`(지원 {1,2}, terms 해석·정규화·수신 불변식), `EntitlementEvaluator`·Inbox controller·투영 Store의 버전 처리 | `AuthorizationTermsContentSpec`, 기존 Inbox·적용 규칙 회귀 |
| S6 판정·Port | 구현 | `AuthorizationTermsEvaluator`(A0~A4), `AuthorizationTermsQuery`(provided)·`AuthorizationTermsService`, `AuthorizationAdmissionPort`(payment required)·`EntitlementAuthorizationAdmissionAdapter` | `AuthorizationTermsEvaluatorSpec`, `AuthorizationAdmissionSpec`(실DB — 배선 1개·MANDATORY·v1→v2·순서 역전·DUPLICATE·lease 만료·FOR SHARE 직렬화·로그), `EntitlementAuthorizationAdmissionAdapterSpec` |
| S7 golden v2 | 구현 | `harness/tests/test_entitlement_golden.py`(v1: PAYMENT만, v2: 두 앱), `contracts/entitlement-snapshot-v2.golden.json` ×2 | harness 2건, MASTER·PAYMENT golden spec |
| S8 관측·조회·문서 | 구현 | `JooqAuthorizationTermsCoverageReader`, `AuthorizationTermsCoverageQuery`·Service, gauge `paycore.master.authorization.terms.missing{kind}`, PAYMENT counter `paycore.payment.entitlement.authorization.terms{result,reason}` | `AuthorizationTermsCoverageQuerySpec` |

### 16.2 명세와 다른 점·구현 결정

1. **MASTER 시험 자원의 v1 golden 제거**: MASTER는 v2만 조립하므로 `apps/master-api/.../entitlement-snapshot-v1.golden.json`을 v2 golden으로 대체했다. v1 golden은 PAYMENT 시험 자원에 그대로 남고(바이트 동일 — harness 재생성으로 확인), PAYMENT가 v1을 계속 해석한다. harness 생성 절차는 v1(PAYMENT만)·v2(두 앱)를 각각 바이트 비교한다(시험 2건, HARNESS-SELFTEST 하한 71 → 72 — 시험 수 = 하한 규칙).
2. **issue `schema_version` 기본값 없음**: V5가 기존 행을 1로 채운 뒤 DEFAULT를 지웠다. 그래서 기존 `EntitlementSchemaSpec`의 원시 INSERT 1곳에 `schema_version`을 명시했다(fixture SQL 변경, 기대값 불변).
3. **PAYMENT "미지원 버전" 시험 값 2 → 3**: 2가 지원 버전이 되었으므로 `SnapshotFixture.unsupported`·`EntitlementInboxSpec`의 미지원 대표 값을 지원 집합 밖 첫 값 3(`UNSUPPORTED_SCHEMA_VERSION`)으로 바꿨다(시험 의도 "더 새 미지원 버전은 head를 막는다" 유지).
4. **`AdmissionResult.Admitted`는 `AuthorizationAdmission`을 바로 만들지 않는다**: domain `AuthorizationAdmission`은 `protocolVersion`이 필수인데 그 값은 PAYMENT 로컬 기관 프로파일(AUTH-001)에서 온다. 그래서 `admission(protocolVersion)` 함수로 고정한다(domain 타입 변경 없음 — §14-2).
5. **Port 입력에 environment 없음**: 정책은 TEST/LIVE 공통(D-AUTH-006), 권리는 환경 축 없음(D-MS-001). Simulator/REAL은 AUTH-001 프로파일.
6. **한도 Ambiguous·Route 2개 이상은 수신 불변식이 먼저 막는다**: v2 내용 구성 시 같은 수준 중첩·같은 (수단, 매입사) 중복을 계약 위반으로 거절하므로 판정의 Ambiguous/ROUTE_AMBIGUOUS 분기는 방어용이며 정상 내용으로는 도달하지 않는다(수신 거절 시험이 대신 증명).
7. **정책 버전 동시 등록**: 전역 등록부라 merchant 같은 직렬화 지점이 없다. 같은 코드 동시 등록은 UNIQUE 충돌 → 새 TX 재판정(기술 재시도 상한 3회)으로 다음 번호를 받는다. 경합이 상한을 넘으면 `TemporarilyUnavailable`(같은 키 재요청으로 안전 재시도) — 시험은 동시 2건(둘 다 성공, 번호 1·2).
8. **jOOQ 시각 비교는 UTC 벽시계 문자열 리터럴**: `LocalDateTime` 바인딩은 드라이버가 JVM 기본 시간대로 해석해 `connectionTimeZone=UTC`로 옮긴다 — 이 환경(JVM 기본 시간대 ≠ UTC)에서 9시간 어긋나 누락 점검 시험이 실제로 틀렸다(0 vs 1). `JooqAuthorizationTermsCoverageReader`는 `CAST('…' AS DATETIME(3))` 리터럴로 비교한다. **관찰(범위 밖, 변경 안 함)**: 기존 `JooqEntitlementPropagationReader.overview`의 `utc(now)` LocalDateTime 바인딩(`LEASE_UNTIL.gt(...)`)도 같은 위험이 있다 — Coordinator 확인·후속 작업 후보.
9. **append-only Entity에 `@Immutable`**: `policy_version`·`card_route_activation_event` Entity는 Hibernate `@Immutable`로 dirty checking UPDATE 경로를 막는다(권한 1142와 이중 방어).
10. **기관 계약계정 감사 위치**: 통합 이벤트(Outbox `INTEGRATION_MASTER_REFERENCE`) payload에서 빼고 `master_change_history.detail`(내부 감사 DB)에만 남긴다(D-MS2-005 "로그·지표·응답·예외 금지"의 해석 — 감사 이력은 그 목록에 없다. 관리 조회 마스킹은 관리 화면 작업).
11. **카드사 개통 Snapshot 필드 이름**: 1단계 §4.1 `cardCompanyKey` → `acquirerCode`(D-MS2-004가 매입사 코드로 확정).

12. **Ambiguous·ROUTE_AMBIGUOUS 판정 분기는 시험이 없다**(review round1 n-2): 수신 불변식(`AccountEntry.checkTerms`)이 같은 수준 한도 중첩·같은 (수단, 매입사) Route 중복을 `SnapshotContractViolation`으로 먼저 거절하므로 정상·위조 내용 모두 판정 단계에 도달할 수 없다(도달 불가 = 방어 코드). 대신 수신 거절 시험(`AuthorizationTermsContentSpec` "receive-time structural invariants")과 그 변이 RED(§16.3)가 모호성 차단을 증명한다. 정책 Assignment AMBIGUOUS도 같은 이유로 도달 불가다.
13. **limitPolicies[].agreementId 필드 추가**(review round1 n-7, 1단계 §4.1 대비 편차): 1단계 §4.1 한도 원소에는 `agreementId`가 없었다. 구현은 CSA_DEFAULT에 발급 CSA ID, MID_OVERRIDE에 `null`을 싣는다 — PAYMENT가 "CSA 기본은 그 MID의 발급 CSA 것"을 수신 시 다시 확인하기 위해서다(MASTER를 믿지 않는 이중 방어). 계약 golden v2에 포함됐다.
14. **승격 지표 순서**(review round1 m-1 보강 중 수정): `schemaPromotion()` 지표·로그를 발행 기록 뒤로 옮겼다 — 발행 전 단계 장애로 rollback된 승격을 세지 않는다(장애 주입 시험이 확인).

### 16.3 RED 확인(구현자 보고)

**공정 편차(review round1)**: 2단계는 test-first가 아니었다 — production 코드를 먼저 쓰고 시험을 뒤에 썼다. 그래서 사전 RED 대신 **변이 RED**(규칙을 깨는 변이 → 해당 spec 실패 확인 → 원복 → sha 일치 확인)로 시험이 규칙을 실제로 잡는지 보완했다. round1에서 3종을 더 했다(아래 표 아래쪽 3행).

이번 2단계는 production 코드를 시험보다 먼저 썼다(엄격한 test-first 아님). 그래서 핵심 규칙마다 **변이(mutation) RED**로 시험이 규칙을 실제로 잡는지 확인했다(변이 적용 → 해당 spec 실행 → 실패 확인 → 원본 복원, 복원 sha 일치 확인):

| 변이 | 실패한 시험 |
|---|---|
| PAYMENT A4 Override 우선을 CSA 기본 우선으로 | `AuthorizationTermsEvaluatorSpec` "while the override is effective it replaces the default entirely" |
| MASTER 한도 등록의 Seoul 자정 검사 제거 | `AuthorizationTermsSpec` "boundaries off Asia/Seoul midnight are refused" |
| lease 갱신의 스키마 승격 분기 무력화 | `MerchantAuthorizationTermsServiceSpec` "the lease renewal promotes it" |
| PAYMENT 수신 불변식의 같은 수준 한도 중첩 검사 제거 | `AuthorizationTermsContentSpec` "receive-time structural invariants" |
| 카드사 개통 통합 이벤트 payload에 기관 계약계정 포함 | `MerchantAuthorizationTermsServiceSpec` "not in the integration event payload nor in any log line" |
| (round1) A0: v1 head 검사를 `schemaVersion < 1`로 무력화 | `AuthorizationTermsEvaluatorSpec` "A0 comes first … v1 head → TERMS_NOT_PROPAGATED" |
| (round1) A3: Assignment 0개를 MISSING이 아니라 통과로 | `AuthorizationTermsEvaluatorSpec` "A3: each of fee and settlement needs exactly one assignment", "an assignment of another currency does not count…" |
| (round1) validUntil 절단 제거(권리 validUntil보다 이른 경계를 버림) | `AuthorizationTermsEvaluatorSpec` 5건(Override 시작 경계, Override 기간·종료 경계, 잠금 대기 SNAPSHOT_EXPIRED, CSA 기본 종료 경계, Assignment 종료 경계) |

자연 RED(구현 중 실제 실패): jOOQ LocalDateTime 바인딩(§16.2-8), JsonNode.map 오용(시험 코드), 정책 버전 4건 동시 등록의 재시도 소진(§16.2-7).

### 16.4 review round1(REVIEW_PASS `fd74b3fe…`, 시험 증거 공백 보강 — 수정 시도 1/3, 2026-10-16)

| 항목 | 대응 | 시험(실제 MySQL) |
|---|---|---|
| m-1 승격 분기 rollback | `MERCHANT_UPDATE`·`ISSUE_RECORD` 직후 장애 → revision 증가·issue·Snapshot Outbox 0, 승격 지표 미증가, 다음 갱신이 승격 | `MerchantAuthorizationTermsServiceSpec` "a schema promotion … with a failure injected" ×2 |
| m-1 승격 동시 실행 | v1 latest에서 `renewDueLeases` 2개 동시 → revision +1 한 번, schema 2 issue 1건(merchant 잠금 직렬화) | "two lease renewal runs at once" |
| m-1 카드사 개통 등록·Replace 장애 | `ISSUE_RECORD` 직후 장애 → 개통 행·이력·revision 불변 / 옛 정책 열린 채·새 정책 없음, 같은 키 재요청 성공 | "a card route registration and a limit policy replacement …" ×2 |
| m-2 v2 같은 eventId 동시 8 | APPLIED 1·DUPLICATE 7, inbox 1행, revision 내용 1행, 판정은 그 revision | `AuthorizationAdmissionSpec` "the same v2 event delivered 8 times at once" |
| m-2 Replace 동시 2 | 1건 성공·1건 LIMIT_POLICY_ALREADY_ENDED, 새 정책 1개·열린 정책 1개, revision +1 | "two replacements of the same policy at once" |
| m-2 카드사 개통 승인·정지 동시 | 이력이 유효한 전이 사슬, 성공 수 = revision 증가 = 이력 증가, 첫 전이는 UNDER_REVIEW → ACTIVE | "approvals and suspensions of one card route at once" |
| m-3 정책 버전 재시도 소진 | (종류, 코드) 직렬화 잠금은 **넣지 않았다**(가역 선택 — 등록부는 Merchant 같은 잠금 행이 없고, RC/RR gap 잠금은 같은 빈 구간에서 서로 호환돼 교착→재시도로 돌아가 재시도 의존을 없애지 못한다). 그래서 소진 경로를 시험으로 고정했다: 매 시도 UNIQUE 충돌 → `TemporarilyUnavailable`·`retries_exhausted` 지표 +1, 버전 행·요청 기록 0, 같은 키 재요청 성공. 동시 2건 시험(번호 1·2)은 그대로 둔다. **사실 기록**: 2단계 첫 작성 때 이 시험은 동시 4건·번호 1~4를 기대했고, 실행에서 재시도 소진(TemporarilyUnavailable)으로 실패해 동시 2건으로 줄였다(§16.2-7). 잠금을 넣지 않았으므로 4건 전원 성공은 이 설계가 보장하지 않는 성질이고, 대신 상한 초과 시의 안전한 결과(부분 행 0·같은 키 재시도 성공)를 이번 시험이 결정적으로 고정한다. 4건 전원 성공이 요구사항이면 직렬화 잠금(예: 등록부 코드별 잠금 행)이 필요하다 — Coordinator 판단 사항 | "a policy version registration whose unique conflict persists on every attempt" |
| 변이 RED 3종 | §16.3 아래 3행 | — |
| n-1 KDoc 위치 | `AuthorizationTermsCoverageService`가 `MerchantAuthorizationTermsService` KDoc과 클래스 사이에 끼어 KDoc이 잘못 붙어 있던 것을 바로잡음 | — |
| n-2·n-4·n-6·n-7·조건부·공정 편차 | §16.2-12·13, §3.5-3, §14-4, §13.2 이관 행, §16.3 | — |
| m-4 기존 jOOQ reader 시간대 | 손대지 않음(Coordinator 후속 작업) | — |
