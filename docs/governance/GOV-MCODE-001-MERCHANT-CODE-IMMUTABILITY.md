# GOV-MCODE-001 — 가맹점 코드 영구 잠금(BR-BIZ-067) 일관성 검증

- 작성: 2026-11-26, 기준 HEAD `30c550c`(작업 트리에 대장·DECISIONS 미커밋 수정 있음 — 그 상태를 읽음)
- 근거 결정: D-GOV-011 ②(2026-11-26 사용자 확정), D-MCODE-001, D-MCODE-002, D-MCODE-003
- 성격: **검증 보고서**. 소스·시험·대장·DECISIONS·명세는 고치지 않았다. 충돌·결손은 §5에 적고 임의로 바꾸지 않는다.
- 증거 수준: 코드·시험 **존재와 내용을 읽어 확인**했다. 이번 작업에서 시험을 실행하지 않았다(실행 검증 아님 — §8).
- 표기: M/ = `apps/master-api/src/main/kotlin/com/paycore/master/merchant/`, MT/ = `apps/master-api/src/test/kotlin/com/paycore/master/`, A/ = `apps/internal-admin/src/main/kotlin/com/paycore/admin/`, AT/ = `apps/internal-admin/src/test/kotlin/com/paycore/admin/`, FE/ = `apps/internal-admin/frontend/src/`, SQL/ = `apps/master-api/src/main/resources/db/`, P/ = `apps/payment-api/src/main/kotlin/com/paycore/payment/`

## 1. 정책 7항목(사용자 확정 2026-11-26, D-GOV-011 ②)

merchantCode는 외부 연동 불변 식별자이며 최초 계약 승인 이후 영구히 잠긴다.

| # | 항목 |
|---|---|
| ① | 최초 승인 이전에만 변경 허용 |
| ② | 효력 시작 여부와 무관하게 최초 승인 시점부터 변경 금지 |
| ③ | 철회·종료·해지·재계약 뒤에도 변경 불허 |
| ④ | 종료된 가맹점의 코드를 다른 가맹점에 재사용하지 않음 |
| ⑤ | UI·API·도메인 계층 모두에서 제한 |
| ⑥ | 코드 유일성 검증, 계약 승인과 코드 변경 사이 동시성 검증 |
| ⑦ | 가맹점명 등 변경 가능한 속성과 식별자를 구분 |

이 정책과 함께 유효한 기존 확정 규칙(재질문 대상 아님):
- BR-BIZ-066(D-MCODE-002 Q-MC-7): 첫 계약 승인 전까지 **2인 승인으로 한 번만** 변경. ①보다 좁은 조건이다. 충돌이 아니라 ①을 더 좁게 적용한 것이다.
- BR-BIZ-067(C-14, 이번에 확정): "첫 계약 승인" = 그 가맹점이 대상인 계약의 어느 Revision이든 승인 사실이 하나라도 있는 상태. 효력 시작 전 승인을 포함하고, 철회·종료 뒤에도 "승인 뒤"로 본다.
- BR-BIZ-065·068: MASTER 전체에서 하나뿐인 코드. 은퇴 코드는 영구 예약된다.
- BR-BIZ-071·BR-WH-015·016: 코드는 출력 전용이다. 식별·인증은 MID·API 키로 한다.

## 2. 항목별 검증표

판정: **일치**(정책·구현·시험이 모두 맞음) / **불일치**(정책이나 명세와 다르게 동작하거나 명세의 완화책이 빠짐) / **시험 없음**(구현은 맞지만 그 조건을 고정하는 시험이 없음) / **확인 불가**(경로·기능이 아직 없어 판정할 수 없음)

| ID | 항목 | 정책 문서·대장 | 도메인 규칙(file:line) | API 검증 | 화면 제어 | 시험 | 판정 |
|---|---|---|---|---|---|---|---|
| V-01 | ① 승인 전 변경 허용(2인) | BR-BIZ-066, MERCHANT-CODE-001 §3.7 | M/`domain/Merchant.kt:54-72` `changeCodeWithEvidence`(근거 경로 전용, 근거 없는 변경 메서드 없음) | MASTER `POST …/merchant-code-changes`(M/`application/MerchantOnboardingService.kt:393-422` `executeApproved`), ADMIN 요청(OPERATOR)·승인(APPROVER+TOTP) A/`approval/application/MerchantOpsApprovalService.kt:390-410` | FE/`components/MerchantCodeChangeRequest.tsx:28-30` `mayRequestCodeChange`, FE/`screens/MerchantsScreen.tsx:208,863-867` | MT/`merchant/domain/MerchantCodeChangeSpec.kt`::"the change appends seq 2 CHANGE…", MT/`merchant/application/MerchantCodeChangeApprovalSpec.kt`::MC-1b-3 success, AT/`approval/MerchantCodeChangeApprovalServiceSpec.kt`::MC-1b-7, FE/`screens/MerchantsScreen.codeChange.test.tsx`::"OPERATOR + merchantCodeChangeable…" | 일치 |
| V-02 | ① 횟수(1회) | BR-BIZ-066 | `Merchant.kt:64` `ALREADY_CHANGED`, DB SQL/`migration/master/V13__merchant_code_assignment.sql:18,24` PK(merchant_id, seq) + CHECK seq ∈ {1,2} | ADMIN `changeCount != 0` → 409(`MerchantOpsApprovalService.kt:400-402`) | 버튼 숨김(`merchantCodeChangeable` = 변경 0회 포함) | `MerchantCodeChangeApprovalSpec`::MC-1b-3 ALREADY_CHANGED, MC-1b-5 DB seq 3 → 3819, `MerchantCodeChangeConcurrencySpec`::MC-1b-4 ② | 일치 |
| V-03 | ② 효력 시작과 무관하게 승인 시점부터 금지 | BR-BIZ-067, MERCHANT-CODE-001 §3.7.1 | 승인 사실 = `contract_revision_approval` 행 존재. M/`adapter/persistence/JpaContractRepository.kt:82-87` `hasApprovedRevisionFor`는 효력 기간을 보지 않는다 → `Merchant.kt:65` `LOCKED` | MASTER 판정 6·9. ADMIN advisory M/`adapter/query/JooqMerchantDetailReader.kt:79,95-98`(기간 조건 없음) | 버튼 숨김 | `MerchantCodeChangeApprovalSpec`::MC-1b-3 LOCKED는 `readyMerchant`(효력 시작 = 시험 시계 t0, MT/`merchant/application/OnboardingFixture.kt:78,150`)만 쓴다. **효력 시작 전(미래 T0) 승인 뒤 변경 시험 없음** | 시험 없음 |
| V-04 | ③ 계약 철회·종료 뒤 | BR-BIZ-067 "철회·종료 뒤에도 승인 뒤" | 승인 사실 append-only. runtime 권한 SQL/`runtime-privilege/master/V2__master_runtime_privileges.sql:28` `contract_revision_approval` = SELECT, INSERT. **계약 철회·종료 명령이 MASTER에 없다**(MASTER-LIFECYCLE-001 미구현 — `withdraw`·`terminate` 계약 경로 검색 결과 0) | 해당 경로 없음 | 해당 없음 | 없음 | 확인 불가 |
| V-05 | ③ 가맹점 해지(TERMINATED) 뒤 | 정책 ③ | `Merchant.kt:63` `requireActive()` → `MERCHANT_NOT_ACTIVE`(LOCKED보다 먼저 판정). 가맹점 종료 명령은 없다(TERMINATED는 저장 행 복원으로만 존재) | ADMIN advisory `merchantCodeChangeable`에 ACTIVE 조건(`JooqMerchantDetailReader.kt:79`) | 버튼 숨김(같은 advisory) | **TERMINATED 가맹점의 코드 변경 거절 시험 없음**(Domain·DB·ADMIN·FE 모두) | 시험 없음 |
| V-06 | ③ 재계약(새 계약·새 Revision) 뒤 | BR-BIZ-067 "어느 Revision이든" | `hasApprovedRevisionFor`는 가맹점의 **모든 계약**의 승인 행 수를 센다(`JpaContractRepository.kt:84-86`) | 같음 | 같음 | **둘째 계약·둘째 Revision 상황의 시험 없음**(MC-1b-3은 계약 1개·Revision 1) | 시험 없음 |
| V-07 | ④ 은퇴(바뀌기 전) 코드 재사용 금지 | BR-BIZ-068 | SQL/`V13…:19` `UNIQUE uk_mca_code(merchant_code)` — 현재·은퇴 코드 모두 점유. 사전 조회 M/`adapter/persistence/JpaMerchantRepository.kt:41-45` `findCodeOwner`(생명주기 조건 없음) | 등록 `MerchantOnboardingService.kt:106`, 변경 `:404` → `MERCHANT_CODE_TAKEN` | — | `MerchantCodeChangeApprovalSpec`::MC-1b-5 "a retired code is never reused", MT/`merchant/adapter/persistence/MerchantCodeSchemaSpec.kt`::MC-1-4 1062 | 일치 |
| V-08 | ④ 종료 가맹점의 현재 코드 재사용 금지 | 정책 ④ | 같은 UNIQUE. 배정 표에 UPDATE·DELETE 권한 없음(SQL/`runtime-privilege/master/V14…:3`), `merchant` 표에도 DELETE 권한 없음(V2:2,17), FK `fk_mca_merchant` NO ACTION. 종료로 코드를 푸는 경로가 없다 | 같음 | — | **TERMINATED 가맹점의 코드로 등록·변경 → TAKEN 시험 없음**(구조로는 성립) | 시험 없음 |
| V-09 | ⑤ 도메인 계층 | MERCHANT-CODE-001 §3.7.3·§3.7.4 | `Merchant.kt:63-68`(판정 7→8→9→10→11→자기 코드). 코드 변경 메서드는 하나뿐 | — | — | `MerchantCodeChangeSpec`::판정 순서·LOCKED, MT/`architecture/MerchantCodeArchitectureSpec.kt`::MC-1-7·MC-1b-10(메서드 하나, 호출자 = 관리 채널 컨트롤러뿐) | 일치 |
| V-10 | ⑤ MASTER API 계층 | §3.7.2 | 본문 parser가 등록자·actor 필드 거절, 근거 형식 검증 | `MerchantOnboardingService.kt:393-422` 잠금 뒤 판정, 거절은 저장된 거절 + 근거 REJECTED | — | `MerchantCodeChangeApprovalSpec`::MC-1b-3 rejections(LOCKED 포함, 업무 행 불변), MT/`merchant/adapter/web/admin/MerchantCodeChangeApiSpec.kt`, `MerchantCodeChangeParserSpec.kt` | 일치 |
| V-11 | ⑤ ADMIN API 계층 | §5.5 | A/`approval/domain/PinnedMerchantCodeChangeTarget.kt:25`(pinned는 승인 전·변경 0회만 표현) | 요청 시 advisory 409(`MerchantOpsApprovalService.kt:400-402`), 승인 시 MASTER 재조회로 바뀌었으면 TARGET_CHANGED(`:282-288`), MASTER LOCKED → OWNER_REJECTED | — | `MerchantCodeChangeApprovalServiceSpec`::"advisory 409 … after the first contract approval", "A-step change → INVALIDATED(TARGET_CHANGED) … (contract approved)", "MASTER TAKEN / LOCKED / ALREADY_CHANGED → OWNER_REJECTED" | 일치 |
| V-12 | ⑤ 화면 계층 | §5.5 | — | — | 버튼은 `merchantCodeChangeable === true`이고 같은 그룹 대기 없음일 때만 보인다(`MerchantCodeChangeRequest.tsx:28-30`). 안내 문구 `:22`. 화면은 표시만 하고 판정은 서버가 한다 | FE/`screens/MerchantsScreen.codeChange.test.tsx`::"no button: VIEWER; not changeable (after the first contract approval / already changed)…", FE/`screens/ApprovalsScreen.codeChange.test.tsx`::LOCKED 문구 | 일치 |
| V-13 | ⑤ 하류(PAYMENT 투영) — 참고 | MERCHANT-CODE-001 §6 | P/`entitlement/domain/SnapshotApplicationRule.kt:52`: MID가 있는 head의 코드가 바뀌면 **적용하고 경보**(`merchant_code_changed_with_mid`, P/`entitlement/application/EntitlementInboxService.kt:79-87`) | — | — | `apps/payment-api/src/test/kotlin/com/paycore/payment/entitlement/domain/EntitlementContentV4Spec.kt`::changed → `merchantCodeChanged = true` | 일치(탐지만 — §5 C-05) |
| V-14 | ⑥ 코드 유일성 | BR-BIZ-065 | `UNIQUE uk_mca_code` + 사전 조회 + 1062 → 새 TX 재판정 `TAKEN` | 등록·변경 공통 | 사전 중복 확인 endpoint 없음(C-4) — 화면은 서버 거절 문구만 | MT/`merchant/application/MerchantCodeRegistrationSpec.kt`::MC-1-3 동시 등록 1건만, `MerchantCodeChangeConcurrencySpec`::MC-1b-4 ③(두 가맹점 같은 새 코드)·④(등록 vs 변경) | 일치 |
| V-15 | ⑥ 계약 승인 vs 코드 변경 동시성 | §3.7.3 경합, L-6 | 두 경로 모두 merchant 행을 먼저 잠근다: 승인 `MerchantOnboardingService.kt:222-223`(merchant → contract), 변경 `:396-398`(merchant 잠금 → 승인 사실 읽기) | MASTER 단일 로컬 TX | — | `MerchantCodeChangeConcurrencySpec`::MC-1b-4 ①(두 순서 강제·실제 잠금 대기 관측, 3회), MC-1b-12(잠금 순서 기록), `MerchantCodeArchitectureSpec`::MC-1b-12(승인 호출자·`ContractRepository.update` 호출자 고정) | 일치 |
| V-16 | ⑥ ADMIN 수준 상호 배제 | §5.2 C-15 | 미해결 키 그룹 `MERCHANT:<mer_>` 공유 | 계약 승인 대기 중 코드 변경 요청 409 DUPLICATE_UNRESOLVED | 같은 그룹 대기 시 버튼 숨김 | `MerchantCodeChangeApprovalServiceSpec`::"same group: a pending contract approval makes the code change request 409", `MerchantsScreen.codeChange.test.tsx`::"…a pending contract approval or code change in the same group" | 일치 |
| V-17 | ⑥ 사후 탐지(R4 위반 대사) | §3.2 마지막 문단, L-6 "사후 대사 질의(MC-1b-6)로 탐지" | M/`application/MerchantCodeReconciliationService.kt:11`, M/`adapter/query/JooqMerchantCodeChangeAuditReader.kt:20` | **운영 호출 경로 없음** — 질의를 부르는 production 코드(스케줄·endpoint·경보)가 없다. 시험에서만 호출 | — | `MerchantCodeChangeApprovalSpec`::MC-1b-6 | 불일치 |
| V-18 | ⑦ 식별자와 변경 가능 속성 구분 | D-MCODE-003, BR-BIZ-250 | 코드는 별도 append-only 표(V13), 가맹점명은 `Merchant.displayName`(M/`domain/Merchant.kt:15`) — 저장소·형식·명령이 분리 | 등록 지문 `[businessId, displayName, merchantCode]`(`MerchantOnboardingService.kt:98`) — 값은 따로 둔다 | 화면은 "가맹점명 · 코드"로 따로 표시 | `MerchantCodeArchitectureSpec`::MC-1-7(배정 Entity `@Immutable`·setter 호출 없음, Merchant public 메서드 고정) | 일치 |
| V-19 | ⑦ 가맹점명은 변경 가능 | 정책 ⑦ 문구 "가맹점명 등 변경 가능한 속성" | **가맹점명 변경 기능이 없다**(GOV-MER-001 F-11: 화면·ADMIN API·MASTER 명령·Domain 메서드 모두 없음). 이름 변경 정책은 GOV-MER-001 QM-2로 미결 | 없음 | 없음 | 없음 | 확인 불가 |

**집계(19행)**: 일치 12 · 불일치 1 · 시험 없음 4 · 확인 불가 2. 이와 별개로 문서 표기 불일치가 2건 있다(§5 C-06·C-07).

## 3. 동시성 분석 — 승인 커밋과 코드 변경 커밋이 경합할 때

### 3.1 현재 보장 구조

| 요소 | 사실 |
|---|---|
| 트랜잭션 | 두 명령 모두 MASTER 로컬 TX 하나다. 격리 수준 READ COMMITTED(M/`application/LocalTransactions.kt:30`) |
| 직렬화 지점 | `merchant` 행 `SELECT … FOR UPDATE`. `lockRow`는 `refresh(PESSIMISTIC_WRITE)`라 앞선 비잠금 `find`로 영속성 컨텍스트에 있던 Entity도 최신 커밋 값으로 덮는다(M/`adapter/persistence/PessimisticRowLock.kt:13-21`) |
| 잠금 순서 | 계약 승인: merchant → contract(`MerchantOnboardingService.kt:222-223`). 코드 변경: merchant만 잠그고 contract는 읽기만 한다(`:396-398`). 순환이 없어 교착이 생기지 않는다 |
| 승인 사실 읽기 | 잠금 **뒤** JPQL `count`(`JpaContractRepository.kt:82-87`) — 영속성 컨텍스트 캐시를 쓰지 않고 READ COMMITTED 최신 커밋을 본다 |
| 승인 사실의 지속성 | `contract_revision_approval`은 runtime에 SELECT·INSERT만 허용된다(V2:28). 한 번 읽힌 승인은 사라지지 않는다 |
| 낙관적 보조 | 성공 시 `merchants.update(merchant, version)`로 version이 +1 된다. 계약 승인도 merchant version을 올린다(`recordEntitlementChange` + update) |
| DB 최종 방어 | 횟수(R3)·유일성(R1·R2)은 PK·UNIQUE가 지킨다. **"승인 전"(R4)은 DB 제약이 아니다**(명세 L-6) — 잠금 아래 앱 판정이다 |

### 3.2 경합 시나리오

| # | 상황 | 결과 | 시험 |
|---|---|---|---|
| S1 | 계약 승인 TX가 merchant 잠금을 먼저 쥔다 → 변경 TX 대기 → 승인 커밋 | 변경 TX가 잠금을 얻은 뒤 승인 행을 보고 `MERCHANT_CODE_LOCKED`. 배정·이력·Outbox 없음 | MC-1b-4 ① |
| S2 | 변경 TX가 먼저 쥔다 → 승인 TX 대기 → 변경 커밋 | 둘 다 성공. 변경 이력 id가 승인 이력 id보다 앞선다(정책상 "승인 전 변경"이므로 허용). 계약 승인은 코드를 보지 않는다 | MC-1b-4 ① |
| S3 | 같은 가맹점, 다른 approvalId의 변경 두 건 | 잠금으로 직렬화되어 둘째는 `ALREADY_CHANGED`. DB PK `(merchant_id, 2)`가 2차 방어 | MC-1b-4 ② |
| S4 | 두 가맹점이 같은 새 코드로 변경, 또는 등록과 변경이 같은 코드 | `uk_mca_code` 1062 → 새 TX 재판정에서 `TAKEN` | MC-1b-4 ③④, MC-1-3 |
| S5 | Demo seed 승인 경로(근거 없음) | 같은 `approveRevision`을 거쳐 merchant를 먼저 잠근다 | MC-1b-12 |
| S6 | ADMIN 계약 승인 요청과 코드 변경 요청이 동시에 대기 | 같은 미해결 키 그룹이라 둘째 요청이 409. MASTER 판정과 별개인 보조 방어 | MC-1b-7 same group |
| S7 | ADMIN 승인 시점(TX 밖 MASTER 재조회)과 MASTER 실행 사이에 계약 승인 | ADMIN 재조회가 늦으면 POST까지 간다. MASTER가 잠금 뒤 `LOCKED`로 거절하고 → OWNER_REJECTED | MC-1b-7 OWNER_REJECTED |

"계약 승인 커밋 **뒤** 코드 변경 성공"은 현재 구조에서 일어날 수 없고, MC-1b-4 ①이 실제 잠금 대기를 관측하며 이를 고정한다.

### 3.3 보장이 깨질 수 있는 조건(현재 결함 아님 — 변경 시 주의)

| # | 조건 | 결과 | 현재 방어 |
|---|---|---|---|
| F1 | merchant를 먼저 잠그지 않고 승인 사실을 쓰는 새 경로가 생긴다(예: FLOW-3 계약–가맹점 연결, 일괄 승인, 새 승인 표) | 변경 TX가 승인 커밋 직전의 상태를 보고 성공할 수 있다 → R4 위반 | ArchUnit MC-1b-12가 `Contract.approve*`·`ContractRepository.update` 호출자를 고정한다. 다만 **새 표나 새 Repository 메서드로 쓰는 경로는 잡지 못한다** |
| F2 | 계약이 여러 가맹점을 대상으로 하거나(D-GOV-011 ① "계약–가맹점 연결"), 가맹점 등록 전에 승인된 계약에 가맹점을 나중에 연결한다 | `hasApprovedRevisionFor`가 `contract.merchant_id` 하나만 보므로 승인을 놓칠 수 있다. 연결 경로가 merchant를 잠그지 않으면 F1도 함께 생긴다 | 없음 — §7 Q1 |
| F3 | 명령 TX 격리 수준을 REPEATABLE READ로 바꾼다 | 잠금 전 `find`에서 snapshot이 고정되어 잠금 뒤 JPQL이 오래된 값을 볼 수 있다 | MC-1b-4 ①이 실패로 드러낸다(격리 수준 자체를 고정하는 단위 시험은 없음) |
| F4 | DB 직접 INSERT나 결함 있는 배포로 승인 뒤 seq 2가 들어간다 | DB가 막지 않는다(L-6) | 사후 대사 질의가 있지만 **운영에서 호출되지 않는다**(V-17) |

## 4. 재사용 금지(④)의 현재 동작

| 질문 | 현재 동작 | 근거 |
|---|---|---|
| 유일 제약 범위 | `merchant_code` 단독 UNIQUE. 가맹점에는 환경 열이 없고(환경은 MID 단위), **한 MASTER DB 전체**에서 TEST·LIVE 구분 없이 하나다 | SQL/`V13…:19`, `MerchantCodeSchemaSpec`::MC-1-5 "the code UNIQUE covers merchant_code alone" |
| 배포가 다르면 | Demo 배포와 운영 배포가 MASTER DB를 따로 쓰면 이름공간도 따로다. 배포 사이의 유일성은 보장하지 않는다(대장 BR-BIZ-065 "TEST·LIVE·Demo 공통"은 한 DB 안의 뜻으로 읽힌다) | 배포 구성은 이 문서 범위 밖 — §5 C-08 |
| 가맹점 종료 뒤 코드 해제 | 해제되지 않는다. 가맹점 종료 명령 자체가 없고, 배정 행은 runtime이 UPDATE·DELETE할 수 없으며(V14), `merchant`도 DELETE 권한이 없고(V2:2), FK가 NO ACTION이라 연쇄 삭제도 없다 | SQL/`runtime-privilege/master/V14…:3`, V2:2·17, V13:22-23 |
| 사전 조회의 생명주기 조건 | `findCodeOwner`는 생명주기를 보지 않고 배정 행 전체에서 찾는다. 종료 가맹점의 코드도 `TAKEN`이다 | `JpaMerchantRepository.kt:41-45` |
| 은퇴 코드와 사용 중 코드 구별 | 구별하지 않는다(같은 `MERCHANT_CODE_TAKEN`, reference 없음 — C-18·C-12) | §3.3 |
| 같은 가맹점의 되돌리기 | `ALREADY_CHANGED`(1회 소진)로 막힌다. DB에서는 seq 3 → 3819 | MC-1b-5 |
| 이후 위험 | 가맹점 종료·삭제 기능(MASTER-LIFECYCLE)이나 운영 migration이 배정 행을 지우거나 사전 조회에 `ACTIVE` 조건을 넣으면 ④가 깨진다. 종료 가맹점 코드 재사용을 고정하는 시험이 없어 회귀를 잡지 못한다(V-08) | — |

결론: ④는 **구조상 현재 성립한다**(코드는 영구 점유). 시험으로 고정되어 있지는 않다.

## 5. 충돌·불일치·결손 목록(수정하지 않음)

심각도: P1 = 정책 위반 가능 경로 / P2 = 정책 보장의 완화책 결손 / P3 = 시험·문서 결손 / P4 = 표기·안내

| ID | 심각도 | 내용 | 근거 | 다음 동작(제안) |
|---|---|---|---|---|
| C-01 | P2 | 계약–가맹점 관계 재설계(D-GOV-011 ①, FLOW-3 — 사업자별 복수 계약, 계약–가맹점 연결)가 들어오면 "최초 계약 승인" 판정의 기준(`contract.merchant_id` 하나)과 잠금 순서 전제(F1·F2)가 바뀐다. 지금 코드에는 문제가 없고, 미래 설계와 충돌할 위험이다 | `JpaContractRepository.kt:84-86`, `MerchantOnboardingService.kt:218-223`, D-GOV-011 ① | FLOW-3 명세에 "승인 사실을 만드는 모든 경로는 대상 merchant를 먼저 잠근다"와 판정 기준을 적는다 — §7 Q1 |
| C-02 | P2 | 명세가 R4(승인 전) 완화책으로 둔 사후 대사 질의(MC-1b-6)가 운영에서 호출되지 않는다(스케줄·endpoint·경보 없음) | V-17, MERCHANT-CODE-001 §3.2·L-6 | 대사 실행 주기·경보 연결은 가역 구현 세부 → Coordinator가 결정하고 작업을 등록 |
| C-03 | P3 | ②③④의 경계 조건 시험이 없다: 효력 시작 전 승인(V-03), 가맹점 TERMINATED(V-05), 재계약·둘째 Revision(V-06), 종료 가맹점 코드 재사용(V-08) | §2 | §6 시나리오 추가(시험 기대값 변경 없음 — 새 시험) |
| C-04 | P3 | 계약 철회·종료 명령이 없어 ③의 "철회·종료 뒤"를 실행으로 검증할 수 없다. 그 기능을 만들 때 승인 행을 지우거나 고치지 않아야 하고(V2:28 권한 유지), 변경 판정이 계속 LOCKED여야 한다 | V-04 | MASTER-LIFECYCLE 명세에 불변 조건과 시험을 포함 |
| C-05 | P3 | PAYMENT는 MID가 있는 가맹점의 코드가 바뀐 Snapshot을 **적용하고 경보만** 낸다(차단·Hold 아님). MASTER가 보장하는 한 정상 경로에서는 일어나지 않는다. 정책 ⑤가 나열한 계층(UI·API·도메인) 밖이라 충돌은 아니다 | V-13 | §7 Q2 |
| C-06 | P4 | 대장 `docs/rules/BIZ.md` 검토 노트 6번(365행)이 아직 "BR-BIZ-067 … 확인 권장"이다. 같은 파일 107행은 D-GOV-011 ②로 '확정'이 됐다(작업 트리 미커밋 수정) | BIZ.md:107·365 | 대장 정리 때 노트를 '확정(D-GOV-011 ②)'으로 닫는다 |
| C-07 | P4 | MERCHANT-CODE-001 §3.7.1 제목이 아직 "해석 기록(C-14)"이고 D-GOV-011 ②를 인용하지 않는다. §11은 "PENDING 없음" | 명세 210행 | 명세 다음 rev에서 확정 근거 표기만 추가 |
| C-08 | P4 | BR-BIZ-065 "MASTER 전체(TEST·LIVE·Demo 공통)"는 한 MASTER DB 안의 유일성이다. Demo와 운영이 다른 DB면 배포 사이 유일성은 없다. Demo 코드가 외부로 나가지 않으면 영향이 없다 | §4 | 대장 문구 명확화(정책 변경 아님) |
| C-09 | P4 | 정책 ⑦은 가맹점명을 "변경 가능한 속성"이라 부르지만 가맹점명 변경 기능·정책이 없다(GOV-MER-001 F-11·QM-2 미결). 식별자와의 분리는 구조상 이미 되어 있다 | V-19 | QM-2 답이 날 때 같이 정리. 이 문서에서 이름 변경 정책을 추론하지 않는다 |
| C-10 | P4 | 정책 문구 "merchantCode = 외부 연동 불변 식별자"와 BR-BIZ-071(출력 전용, 식별·인증은 MID·API 키)이 함께 있다. 불변 **표시 식별자**로 읽으면 충돌이 없다. 코드를 요청 입력이나 인증 근거로 쓰자는 뜻은 아닌 것으로 본다(Q-MC-8 유지) | BIZ.md:111, D-MCODE-002 Q-MC-8 | 해석 유지. 요청 입력에 코드를 쓰는 변경은 새 결정이 필요 |
| C-11 | P4 | TERMINATED 가맹점에 코드 변경을 보내면 `LOCKED`가 아니라 `MERCHANT_NOT_ACTIVE`로 거절된다(판정 7이 9보다 앞섬). 차단은 되지만 화면 안내 문구 "계약 승인 뒤이거나 이미 한 번 바꿨습니다"(`MerchantCodeChangeRequest.tsx:33`, `MerchantsScreen.tsx:208`)에는 비활성 사유가 없다 | `Merchant.kt:63` | 안내 문구 보완 여부는 가역 세부 |

정책 위반이 지금 실제로 일어나는 경로(P1)는 찾지 못했다.

## 6. 필요한 시험 시나리오(Given / When / Then)

모두 **새 시험**이다. 기존 기대값은 바꾸지 않는다. 실제 MySQL이 필요한 것은 (DB)로 표시했다.

| ID | Given | When | Then | 대상 |
|---|---|---|---|---|
| T-01 (DB) | 효력 시작이 미래(T0 > 현재)인 계약 Revision이 승인된 가맹점(효력 시작 전) | 근거 있는 코드 변경 | `MERCHANT_CODE_LOCKED`, seq 2·이력·Outbox 없음. 상세 `merchantCodeChangeable = false` | V-03 |
| T-02 (DB) | 계약 A Revision 1 승인 + 같은 가맹점 계약 B 등록(미승인) | 코드 변경 | `LOCKED`(어느 계약이든 승인 하나면 잠금) | V-06 |
| T-03 (DB) | 계약 Revision 1 승인 + Revision 2 등록(미승인) | 코드 변경 | `LOCKED` | V-06 |
| T-04 (Domain) | TERMINATED로 복원된 가맹점(승인 사실 없음, 변경 0회) | `changeCodeWithEvidence` | `MERCHANT_NOT_ACTIVE`, 배정 불변 | V-05 |
| T-05 (DB) | 가맹점 X(코드 `c`)를 TERMINATED 행으로 만든 상태(시험 전용 관리 계정 UPDATE) | ① 새 가맹점 등록 코드 `c` ② 다른 가맹점이 `c`로 변경 | 둘 다 `MERCHANT_CODE_TAKEN`, 배정 행 수 불변. DB 직접 INSERT도 1062 | V-08 |
| T-06 (ADMIN·FE) | MASTER 상세 lifecycle = TERMINATED, `merchantCodeChangeable = false` | 코드 변경 요청 / 상세 화면 | ADMIN 409(advisory), 화면 버튼 없음 | V-05 |
| T-07 (DB, 기능 생길 때) | 계약 승인 뒤 계약 철회·종료 | 코드 변경 | `LOCKED`, `contract_revision_approval` 행 수·내용 불변 | V-04 |
| T-08 (DB, FLOW-3 때) | 가맹점 등록 전에 승인된 계약에 가맹점을 연결(또는 복수 가맹점 계약) | 연결 직후 코드 변경, 그리고 연결과 변경 동시 실행(두 순서 강제) | Q1 답에 따른 기대값. 연결 경로가 merchant를 먼저 잠근다는 순서 기록 시험 | F1·F2 |
| T-09 (Arch) | production 코드 | `JpaContractRevisionApprovalEntity`를 persist하거나 `contract_revision_approval`에 쓰는 호출자 검사 | `JpaContractRepository.update` 하나뿐(새 쓰기 경로가 생기면 RED) | F1 |
| T-10 (단위) | 명령 TX 정의 | 격리 수준 확인 | READ COMMITTED 고정 | F3 |
| T-11 (운영 연결, C-02 결정 뒤) | 승인 이력 뒤에 `MerchantCodeChanged` 이력이 있는 가맹점(시험 주입) | 대사 실행 주기 | 경보·지표가 나오고 코드 값은 로그에 없음 | V-17 |

## 7. 사용자 결정 필요 질문

확정된 정책 ①~⑦과 BR-BIZ-066·067은 다시 묻지 않는다. 적용 세부 가운데 키 동일성에 관한 것만 적는다.

### Q-MCODE-1 — 계약–가맹점 연결이 생길 때 "최초 계약 승인"의 시점(키 동일성)

- 배경: 지금은 계약 하나의 대상 가맹점이 하나이고(`contract.merchant_id`), 가맹점이 먼저 등록된 뒤 계약이 승인된다. D-GOV-011 ①(FLOW-3)은 사업자별 복수 계약, 계약–가맹점 연결, "유효 계약이 있어야 새 가맹점 등록"을 검토한다. 그렇게 되면 **가맹점을 등록하기 전에 이미 승인된 계약**이 생길 수 있다.
- 권장(a): 그 가맹점이 **대상으로 연결된** 계약 Revision의 승인 사실 기준(현 BR-BIZ-067 유지). 이미 승인된 계약에 새 가맹점을 연결하면 **연결 시점부터 잠금**이다. 결과적으로 이 경로로 등록한 가맹점은 코드를 바꿀 기회가 없고, 오타는 등록 전에 바로잡아야 한다. 연결 경로는 merchant를 먼저 잠근다.
- 대안(b): 가맹점이 연결된 **뒤에 일어난** 첫 승인만 센다(이미 승인된 계약에 연결하면 다음 승인 전까지 변경 허용). 외부에 MID가 노출된 뒤에도 코드가 바뀔 수 있어 ②의 취지와 어긋날 위험이 있다.
- 대안(c): 사업자의 계약 접수 COMPLETED(계약 완료)를 "승인"으로 본다. 등록 즉시 잠기는 것과 같다.
- 영향: 판정 질의, 잠금 순서, FLOW-3 연결 명령, 시험 T-08
- 막히는 작업: MERCHANT-FLOW-001 FLOW-3 중 계약–가맹점 연결 부분, GOV-MER-ELIG-001. 지금 코드에는 영향이 없다.

### Q-MCODE-2 — PAYMENT가 MID 있는 가맹점의 코드 변경 Snapshot을 받았을 때(외부 식별자 불변의 하류 방어)

- 현재: 적용하고 경보 `merchant_code_changed_with_mid`를 낸다(웹훅·결제 응답에 새 코드가 나간다). MASTER가 막으므로 정상 경로에서는 일어나지 않는다.
- 권장(a): 현행 유지. MASTER가 권위자이고 PAYMENT는 탐지만 한다. 결함이 생기면 경보를 보고 MASTER를 조사한다.
- 대안(b): PAYMENT가 이미 있던 코드를 유지하고 그 Snapshot의 코드 필드만 Hold·경보한다. 외부 노출 값은 절대 바뀌지 않지만 투영이 MASTER와 어긋나고 Hold를 해소하는 절차가 필요하다.
- 영향: PAYMENT Snapshot 적용 규칙, 웹훅·결제 응답 값
- 막히는 작업: 없음(결정 전까지 현행 동작 유지)

## 8. 하지 않은 것

- 소스·시험·Flyway migration·대장(`docs/rules/*`)·`docs/DECISIONS.md`·명세(MERCHANT-CODE-001 등)·`harness/*`를 수정하지 않았다. 커밋하지 않았다. 이 문서 하나만 새로 만들었다.
- 시험·게이트를 실행하지 않았다. §2의 시험 열은 **시험 코드가 있다**는 뜻이고, 이번 revision에서 통과했다는 증거가 아니다. 판정은 코드와 시험 본문을 읽어 내렸다.
- 정책·시험 기대값을 바꾸지 않았다. §5의 다음 동작은 제안일 뿐이다.
- 가맹점명 변경 정책(GOV-MER-001 QM-2), 사업자·계약 등록 자격 매트릭스(D-GOV-011 ①)는 판단하지 않았다. 관련 지점만 표시했다.
- 정산서 노출(BR-BIZ-072, 기능 없음)과 배포 사이 DB 구성(Demo·운영 분리 여부)은 확인하지 않았다.
- `questions.json`에 Q-MCODE-1·2를 등록하지 않았다(Coordinator 몫).
