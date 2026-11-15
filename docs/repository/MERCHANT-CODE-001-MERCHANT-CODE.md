# MERCHANT-CODE-001 사람이 읽는 가맹점 코드(토스식) 명세 (rev 0.4.4)

- **rev 0.1** (2026-11-13, Implementer — 명세 초안. 코드·migration·빌드·커밋 없음). D-MCODE-001(Q-MC-1~6) 적용, 세부 질문 Q-MC-7~9 PENDING, C-1~C-8 권장. (0.1a: 사용자 답 D-MCODE-002 Q-MC-7~9 기록.)
- **rev 0.2** (2026-11-14, Implementer — Q-MC-7 답(계약 승인 전 1회 변경)에 따른 재설계. 코드·migration·빌드·커밋 없음). 저장을 append-only 배정 이력 표로(§3.2), 코드 변경 2인 명령(§3.7·§5.5), 미해결 키 `mer_` 기준(§5.2), 외부 노출 불변 논증(§6), 배치 MC-1b, 새 질문 Q-MC-10.
- **rev 0.3** (2026-11-14, Implementer — 사용자 답 **D-MCODE-002 Q-MC-10 = "요청자≠승인자만"**(권장 반대) 적용 + 저장소 대조. 코드·migration·빌드·커밋 없음, 자기 완료 판정 아님 — Reviewer 별도).
  기준: main, HEAD `7a50459`(MERCHANT-OPS-002 B3-2 이후). 원 가맹점 등록자 규칙 제거(§3.7.3·§3.8·§5.3·§5.5·§8·§9), 결정 고정 시험 추가, 모든 파일:줄 참조·migration 번호 재확인(§1.4·§13).
- **rev 0.4** (2026-11-14, Implementer — rev 0.3 독립 리뷰 **REVIEW_FAIL**(MAJOR 2·minor 7·NIT 2) 반영 + Coordinator 가역 결정 C-22~C-26. 코드·migration·빌드·커밋 없음, 정책 변경 없음).
  MAJOR-1 MC-1을 MASTER + ADMIN 응답 모양·등록 본문·최소 코드 입력의 **한 배포 단위**로(§1.5·§3.8·§4·§7, 교차 배치 시험 MC-1-9), MAJOR-2 seed 재실행 충돌 정정(§1.5·§3.5 — 기록된 성공 결과 선확인, MC-1-10), m-1~m-7·NIT(§3.2·§3.5·§3.7.3·§5.1·§6·§8). §13 기록.
- **rev 0.4.1** (2026-11-14, Coordinator — MC-1 구현 질문 Q-MC1-1 결정 C-27: seed 선확인(C-23)에서 T0 단계 제외. 정책 변경 없음).
- **rev 0.4.2** (2026-11-14, Implementer — §13 정리(MC-1 Demo E2E·커밋 기록, 0.4.1 행 순서)와 MC-2 구현 기록. 본문 규칙·정책 변경 없음, 자기 완료 판정 아님).
- **rev 0.4.3** (2026-11-14, Coordinator 결정 C-28 기록 + Implementer MC-2 수정 라운드(MC-2 리뷰 REVIEW_FAIL 반영). MC-2-3을 MC-2(표시)·MC-2b(마법사 가맹점 입력칸)로 분리, §3.4 꼬리 첫 글자 규칙 한 줄. 정책 변경 없음).
- **rev 0.4.4** (2026-11-14, Implementer — MC-1b 구현 기록(§13 `0.4.4-impl MC-1b`). 본문 규칙·정책 변경 없음, 자기 완료 판정 아님 — Verifier·Reviewer 별도).
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-MC-n** = 사용자 질문(모두 답변됨 — §11). **C-n** = Coordinator 가역 선택(권장안). **L-n** = 알려진 위험·한계.
- 근거: D-MCODE-001(Q-MC-1~6), D-MCODE-002(Q-MC-7~10), D-ADMIN-UI-004 ③, D-MOPS-002·003·005·006·007·008·009 ③·010, D-CPA-001, D-ACQ-008, D-CP-001(ID 정규화 없음), D-FFR-001(열 단위 UPDATE 선례),
  MERCHANT-OPS-001·002(§3.3 응답·§9.3 승인 근거 공통 계약·§9.5 OWNER_SERVICE 2-TX·§9.27 등록자 ≠ 승인자·C-31 seed 구조·C-35 미해결 키 그룹·C-42 migration 한 번에·C-49 판정 순서), MASTER-SNAPSHOT-001, CP-API-001, WEBHOOK-001, ADMIN-UI-MERCHANT-002,
  ARCHITECTURE-CONVENTIONS §14, 테스트 아키텍처 Test Fixture Policy.

## 0. 한눈에

| 항목 | 결정·제안 |
|---|---|
| 무엇 | 가맹점(Merchant)마다 사람이 읽는 **가맹점 코드** 1개. MID(`mid_…`)·사업자(`biz_…`)와 별개 |
| 형식 | 영문 소문자+숫자, 첫 글자 영문, **6~14자** `^[a-z][a-z0-9]{5,13}$`(D-MCODE-001 "14자 이내" + C-1) |
| 고유·재사용 | MASTER 전체(TEST·LIVE 공통) UNIQUE. **한 번이라도 배정된 코드는 영구 점유**(변경으로 은퇴한 코드 포함 — 누구도 재사용 불가, D-MCODE-002) |
| 변경 | **첫 계약 승인 전 1회만**, 2인 승인(새 ADMIN 승인 종류 `MERCHANT_CODE_CHANGE` → MASTER `ChangeMerchantCode` 근거 경로). 첫 계약 승인 뒤 불변(D-MCODE-002) |
| 2인 규칙 | **요청자 ≠ 승인자만**(D-MCODE-002 Q-MC-10). 원 가맹점 등록자도 승인할 수 있다 — D-MOPS-009 ③(1인 등록자 ≠ 2인 승인자)을 이 종류로 **확장하지 않는다** |
| 저장 | **append-only `merchant_code_assignment` 이력 표**(현재 코드 = 그 가맹점의 마지막 행). `merchant`에 코드 열을 두지 않는다. UNIQUE(code)가 은퇴 코드 예약을, `assignment_seq ∈ {1,2}`가 "최대 1회 변경"을 DB로 강제 |
| 발급 | B1 가맹점 등록 시 필수. 화면이 "상호 영문 + 랜덤 4자" 미리보기, 운영자 수정·직접 입력, 중복이면 MASTER 거절 |
| 내부 키 | `mer_` ULID는 **내부 키 유지**(FK·Snapshot 키·MASTER 명령 본문·`mop1`·**ADMIN 미해결 키**). 화면 표시는 코드 |
| 감사·승인 | **MC-3부터 새 행**의 가맹점 대상 `target_ref` = 그 시점의 코드(Q-MC-4). 기존 행 불변·대응표로 연결(Q-MC-9). 변경 뒤에도 옛 코드 행은 옛 코드 그대로 — 코드 재사용이 없으므로 영원히 같은 가맹점 하나를 가리킨다 |
| 미해결 키 | 코드가 바뀔 수 있으므로 **`MERCHANT:…` 미해결 키는 `mer_` 기준**(새 열 `target_merchant_id`) — §5.2 |
| 외부 노출 | 웹훅·결제 응답 `merchantCode` **출력 전용**(Q-MC-8 — 식별·인증은 MID·API 키). 외부 노출은 MID 개통 뒤 = 계약 승인 뒤라 **외부에 나간 코드는 바뀌지 않는다**(§6) |
| 배치 | MC-1 MASTER 모델·등록 **+ ADMIN 응답 모양·등록 본문·최소 코드 입력(한 배포 단위 — C-22)** → MC-2 ADMIN 미리보기·표시 교체·코드 검색 → MC-1b 코드 변경 2인 명령 → MC-3 감사·승인 대상 표기 전환 → MC-4 PAYMENT 투영·외부 필드 (→ SETTLEMENT-001). 어느 배치 경계에서도 ADMIN이 깨지지 않는다(§1.5). **막힌 배치 없음**(질문 모두 답변됨) |

## 1. 현재 상태(코드 확인 — HEAD `7a50459`)

### 1.1 생성·저장·노출
- ID 형식 `MasterIdFormat.kt:3-18`(`<prefix>_` + ULID 26자, DB CHECK와 같음), 발급은 실행기 `work.newId(MerchantId.PREFIX)`(`MerchantOnboardingService.kt:125`).
  가맹점 등록 `MerchantOnboardingService.kt:120-137`(요청 지문 입력 `[businessId, displayName]` L121, 이력 `MerchantRegistered` detail L129-135), seed 값 `DemoMerchantSeedValues.kt:33-37`(가맹점 이름 L36).
- `merchant` 표 `V1__master_core.sql:38-54`(코드 열 없음, `created_by` L45), runtime 권한 `V2__master_runtime_privileges.sql:17`의 `merchant` = `SELECT, INSERT, UPDATE`(표 단위 — 잠금 읽기 `FOR UPDATE`와 `entitlement_revision`·`version` 갱신, L15-16 주석).
- 열 단위 UPDATE 절차 `grant_runtime_column_update`(`docker/mysql/init/01-owned-databases.sh:191-227`), 사용 선례: ADMIN `V2__admin_runtime_privileges.sql:22-24`(`admin_approval_request` — 표 `SELECT, INSERT` + 결정 열만 UPDATE, 열 권한만으로 TX1 `FOR UPDATE` 잠금이 동작하는 선례), PAYMENT `V34`·FINANCIAL `V3`·`V8`.
- ADMIN 감사 `admin_audit_event.target_ref`(`V5__owner_service_approval.sql:113`) 형식 = Domain `AdminAuditEvent.kt:54` `TARGET_REF` 정규식(`^([a-z]{3,4}_<ULID>|ACQ.…)$`), 가맹점 대상 = merchantId(상세 조회 감사 `MerchantLookupService.kt:110-111`).
- 외부에 `mer_`는 나가지 않는다 — 외부 식별은 MID 단위(결제·웹훅·정산 모두 MID).

### 1.2 "첫 계약 승인"의 현재 모델(§3.7 판정 근거)
- 계약 Aggregate `Contract.kt:16-33`: 대상 Merchant는 계약당 하나(D-MD-013, `contract.merchant_id` `V1__master_core.sql:87`, 모든 Revision Scope가 그 Merchant — `Contract.kt:31`).
- 승인 사실 = **`contract_revision_approval` 행**(`V1__master_core.sql:138-146`, PK `(contract_id, revision_no)`, runtime 권한 `SELECT, INSERT` — append-only, `V2__master_runtime_privileges.sql:25-28`). Domain `ContractRevision.isApproved = approval != null`(`ContractRevision.kt:24`).
  승인 취소·삭제 경로 없음 → 한 번 생긴 승인 사실은 사라지지 않는다(계약 철회·종료는 MASTER-LIFECYCLE-001 — 승인 사실을 지우지 않는 append-only 원칙).
- 승인 경로 두 개: 근거 있는 `approveContractRevisionWithEvidence`(관리 채널, `MerchantOnboardingService.kt:218-224`)와 seed 전용 `approveContractRevision`(L208-216, `requireDemoSeedPath`). 둘 다 `approveRevision`(L230-255):
  **잠금 순서 merchant → contract**(L236-241: 잠그지 않고 Scope Merchant를 읽은 뒤 `merchants.lock` L240 → `contracts.lock` L241) → `lockedNow()` L242 → 승인 → revision +1·Snapshot 같은 TX(L245-253).
- 명령 TX 격리 수준 **READ COMMITTED**(`master/merchant/application/LocalTransactions.kt:30`) — 잠금 뒤 일반 SELECT도 그 시점의 최신 커밋을 본다(REPEATABLE READ의 오래된 snapshot 문제 없음).
- `ContractRepository`에 Merchant 기준 조회 없음(`insertNew`·`find`·`lock`·`update`만 — `ContractRepository.kt:6-17`) → §3.7.4에 읽기 메서드 1개 추가.
- Repository 자식 행 처리 방식(§3.7.4 결정 근거): `JpaContractRepository`는 `@OneToMany` 없이 자식 Spring Data Repository(`ContractRevisionJpaRepository` 등 — L17-18)로 따로 읽고, 새 Revision·승인 행만 `persist`(L46-67·L81-91 — "revisions are append-only" 검사 L55).

### 1.3 ADMIN 미해결 키(§5.2 근거)
- `unresolved_key`는 `target_ref`에서 계산하는 생성 열(ADMIN `V11__merchant_ops_activation_approval.sql:40-61`): B2 두 종류 `MERCHANT:<target_ref>`(ELSE 분기 L58), B3 그룹 `MERCHANT:PM|SITE_REVIEW|CURRENCY|ROUTE|SITE_CONFIRM:<target_ref>`(L51-57, C-35 — 그룹별 가맹점당 미해결 하나).
- 가맹점 대상 CHECK: `target_ref`는 `^mer_<ULID>$`만(`V11…sql:28-33`) — **MC-3 migration 전에는 ADMIN 승인 행 `target_ref`에 코드를 쓸 수 없다**(§5.5·§7).
- 미해결 승인 목록 조회 `JooqMerchantPendingApprovalReader.kt:23`도 `TARGET_REF.eq(merchantId)`.
- 계약 승인 **전**에도 열릴 수 있는 그룹: `MERCHANT:`(계약 승인·첫 MID), `SITE_REVIEW`(Site는 계약 전 등록·심사 가능). 나머지(PM·CURRENCY·ROUTE·SITE_CONFIRM)는 MID가 필요해 계약 승인 뒤에만.
- ADMIN 2인 승인의 등록자 비교 분기 `MerchantOpsApprovalService.kt:600-607` `registrantOf(pinned)`: 봉인 타입별 `when` — 계약·개통·Site 심사는 등록자, 첫 MID·**통화 활성화(B3-2)는 `null`**(요청자 ≠ 승인자만). 코드 변경은 같은 `null` 분기(§5.5 — Q-MC-10).
- ADMIN 결함 거절 집합 `MerchantOutcomes.kt:40-43` `DEFECT_REJECTIONS`(MASTER에서 나오면 경보).

### 1.4 migration 번호(재확인 — HEAD `7a50459`, B3-2는 migration 없음)
- **MASTER**: 최신 schema `V11__admin_approval_evidence_b3_operations.sql`, privilege `V10`, **V12 비움**(MERCHANT-OPS-002 C-42 — 이후 V12 추가 금지, outOfOrder 없음) → MC-1 **V13(schema)·V14(privilege)**.
  `ChangeMerchantCode` operation CHECK 값도 MC-1의 V13에 넣는다(**C-20** — C-42 선례: 앱은 MC-1b 전까지 그 operation 경로를 만들지 않는다) → MC-1b는 MASTER migration 없음.
- **ADMIN**: 최신 schema `V11__merchant_ops_activation_approval.sql`, privilege `V10`, V12 비움 → **MC-1b = V13**(종류·대상·명령 종류 CHECK, `target_merchant_id`, 미해결 키 — `admin_approval_request`는 표 단위 `INSERT`라 새 열 권한 불필요 → **V14 비움**), **MC-3 = V15**(가맹점 대상 `target_ref` CHECK에 코드 허용 — V16 비움).
- **PAYMENT**: 최신 `V34__outbox_publish_status_privileges.sql`(V33 `financial_fact_relay_privileges` — rev 0.1의 "V33"은 이미 쓰인 번호) → MC-4 **V35(schema)**. `entitlement_head` 권한은 표 단위 `SELECT, INSERT, UPDATE`(`runtime-privilege/payment/V4__entitlement_runtime_privileges.sql:8`) → **privilege migration 불필요**.
- 다른 배치(MERCHANT-OPS-002 B3-3a·B3-3 — migration 없음 §8.4·§9.29)가 먼저 번호를 쓰면 착수 시 다시 확인한다(MERCHANT-OPS-002 C-21 관례 — 홀수 schema·짝수 privilege, 빈 번호 뒤 추가 금지).

### 1.5 배포 경계·재실행·충돌 분류의 현재 동작(rev 0.4 추가 — 리뷰 MAJOR-1·2, m-4·m-5 근거)
- **ADMIN 응답 모양은 키 집합 정확 일치**: `MasterResponseShape.Obj.read`가 `node.propertyNames().toSet() == fields.keys`를 요구한다(`MasterResponseShape.kt:15-20`, L18). 목록·검색 공용 `DIRECTORY`(L78-87), 상세 `DETAIL`(L184-250).
  → MASTER 응답에 `merchantCode` 등 키가 하나라도 늘면 ADMIN은 해석 불가 = fail-closed 503. **MASTER 응답 변경과 ADMIN 모양 변경은 같은 배포 단위여야 한다**(C-22).
- **ADMIN 등록 명령 결과 매핑**: MASTER 400 등 매핑에 없는 상태는 `MasterCommandResult.Unknown("HTTP_<status>")`(`HttpMasterOpsGateway.kt:84-89`, L89) → 결과 불명. MASTER가 `merchantCode`를 필수로 받는데 ADMIN이 보내지 않으면 모든 가맹점 등록이 Unknown이 된다 → 등록 본문 필드도 같은 배포 단위.
- **seed 재실행**: `DemoMerchantSeedService.step`(L125-136)은 매번 Use Case를 호출하고, 실행기는 기록된 요청 지문과 새 지문이 다르면 `IdempotencyKeyReused`(`AdminCommandExecutor.kt:196-205`, L204) → seed는 `ContentConflict`로 멈춘다(L134).
  `REGISTER_MERCHANT` 지문에 코드가 더해지면(§3.6) MC-1 전에 seed된 DB에서 재실행 시 두 번째 단계(L147)에서 충돌한다. rev 0.3의 "seed 재실행은 기록된 결과를 재생한다(L83-87·126-133)"는 **틀린 서술**이었다(L83-87은 효력 시작일 단계의 미완료 판정일 뿐) → §3.5 C-23로 해결.
- **1062 분류**: `PersistenceFailures.constraintOf`가 `'<table>.<index>'` 이름으로 `StoreConstraint`를 고르고(`PersistenceFailures.kt:58-61`, 표 L72-119), 매핑에 없으면 `OTHER`. 실행기는 `businessKey = true`만 새 TX 재판정(`AdminCommandExecutor.kt:311`, enum `PersistenceFailure.kt:22-23`). → 새 키마다 분류를 정해야 한다(§3.2).
- **PAYMENT 같은 revision 규칙**: `SnapshotApplicationRule.kt:40-42` — 같은 revision·다른 contentHash면 `INTEGRITY_CONFLICT`로 head를 막는다. 스키마 승격은 revision +1로만(MASTER-SNAPSHOT-002 §5.2, AUTHN-001 §9.2 — `EntitlementLeaseService` 경로), 배포는 PAYMENT 해석 지원 먼저(MASTER-SNAPSHOT-002 §5.4) → §6.
## 2. 사용자 결정 적용(재질문 없음)

| 결정 | 해석·적용 |
|---|---|
| Q-MC-5 토스식 = 가맹점 코드 | MID 형식 불변, 코드 단위 = Merchant |
| Q-MC-2·5 형식 | §3.1. 최소 길이·첫 글자는 C-1 |
| Q-MC-6 미리보기 + 직접 입력 | 미리보기는 화면 편의, 권위는 MASTER UNIQUE, 사전 예약 없음 |
| Q-MC-3 전체 하나, Demo 같은 형식 | MASTER DB 안 TEST·LIVE 공통 UNIQUE. Demo·운영 DB 간 중복은 막을 수 없음(L-1) |
| Q-MC-1 외부·정산서 노출(권장 반대) | 외부 계약 필드 추가(§6). 코드는 비밀 아님·인증 근거 아님 |
| Q-MC-4 감사·승인 대상 새 코드(권장 반대) | §5. "감사에 보이는 코드는 영원히 한 가맹점" = 재사용 금지를 DB로 강제(§3.2) |
| **Q-MC-7 = "계약 승인 전 1회 변경"(권장 반대, D-MCODE-002)** | ① 그 가맹점의 **첫 계약 승인 전**에만(정의 §3.7.1) ② **한 번만** ③ **2인 승인**(요청 OPERATOR, 승인 APPROVER + 직전 TOTP — D-MOPS-004 해석과 같은 틀) ④ 옛 코드는 **영구 예약**(누구도 재사용 불가 — 같은 가맹점의 되돌리기 포함) ⑤ 감사에 old→new ⑥ 이전 감사 행은 옛 코드 그대로 ⑦ 첫 계약 승인 뒤 **불변** → §3.2·§3.7·§5 |
| **Q-MC-8 = 출력 전용(권장)** | 웹훅·결제 응답에 `merchantCode` 출력만, 요청 식별·인증은 MID + API 키 그대로. 요청 본문에 `merchantCode`가 오면 모르는 필드(기존 엄격 해석 규칙) |
| **Q-MC-9 = 고치지 않고 연결(권장)** | 기존 감사·승인 행 수정 0(append-only). 코드로 찾으면 MASTER 대응표로 그 가맹점의 `mer_`·모든 코드(현재·은퇴)를 얻어 **세 값 모두**로 조회(§5.4) |
| **Q-MC-10 = "요청자≠승인자만"(권장 반대, D-MCODE-002, 2026-11-14)** | 코드 변경 2인 승인은 **요청자 ≠ 승인자**(기존 참여자 규칙·MASTER `APPROVAL_SAME_ACTOR`)만 강제. **원 가맹점 등록자(`merchant.created_by`)도 승인 가능**. D-MOPS-009 ③(Route·결제수단 개통·Site)·D-MOPS-010 ②(재개)의 등록자 규칙을 이 종류로 확장하지 않는다 → 새 거절 code·pinned 등록자·ADMIN 등록자 분기 **없음**, 결정 고정 시험(§8 MC-1b-1·3·7) |
| D-ADMIN-UI-004 ③ | 사업자 ID 화면 제외(완료), 내부 키 유지 |

## 3. 모델(MASTER)

### 3.1 형식
- `^[a-z][a-z0-9]{5,13}$`(binary collation). 정규화 없음 — 대문자·공백·하이픈·밑줄 400(D-CP-001 원칙).
- 첫 글자 영문(C-1). `_` 없음 → 기존 ID(`xxx_<ULID>`)·ACQ 참조와 형식 불충돌(감사 `target_ref` 이름공간 안전 — §5.1 정규식).
- 예약어(C-2) `MerchantCodeReservedWords`(paycore·admin·system·test·live·demo·api·root·null·undefined) 완전·접두 일치 → `MERCHANT_CODE_RESERVED`. Demo seed 고정 코드는 예외 목록.
- 미리보기 랜덤 4자는 `0 o 1 l i` 제외 31자(C-3).

### 3.2 저장·고유·1회 변경·불변(DB 강제) — 두 안 비교와 권장

요구: (R1) 코드 전역 UNIQUE, (R2) 은퇴 코드 영구 예약, (R3) 변경 최대 1회, (R4) 첫 계약 승인 뒤 불변, (R5) 감사에 보인 코드가 영원히 한 가맹점을 가리킴, (R6) 변경 사실(old→new·누가·근거)이 소유 DB에 남음.

| | (a) `merchant.merchant_code` 열 + 열 UPDATE 권한 + Domain/트리거 가드 | **(b) `merchant_code_assignment` append-only 이력 표(권장)** |
|---|---|---|
| R1·R2 | `merchant.merchant_code` UNIQUE는 **현재 코드만** 막는다. 은퇴 코드는 별도 `merchant_code_retired` 표가 필요하고, 두 표를 아우르는 UNIQUE는 MySQL에 없다 → "가맹점 A가 X로 바꾸는 중 B가 X로 등록" 경합을 앱 잠금으로만 막아야 한다(전역 잠금 대상이 없다 — 결국 통합 코드 등록부가 필요 = (b)) | 표 하나의 `UNIQUE(merchant_code)`가 현재·은퇴 코드를 **한꺼번에** 막는다. 등록·변경 경합은 1062 → 저장된 거절 `MERCHANT_CODE_TAKEN`. 별도 은퇴 표 불필요 |
| R3 | DB로 표현 불가(열 권한은 횟수를 모른다). 트리거로 막을 수 있으나 저장소에 트리거 선례가 없고(binlog 환경 `log_bin_trust_function_creators`·DEFINER 권한 문제), 마이그레이션 계정 권한 추가가 필요 | `PRIMARY KEY(merchant_id, assignment_seq)` + `CHECK(assignment_seq IN (1, 2))` → **두 번째 변경 INSERT는 3819**(DB가 횟수를 지킨다) |
| R4 | runtime 계정이 `UPDATE(merchant_code)`를 **영구 보유** → 승인 뒤 불변은 Domain 판정뿐(결함·키 유출 시 DB가 막지 못함) | runtime 권한 `SELECT, INSERT`만(UPDATE·DELETE 없음). 승인 뒤 변경 = seq 2 INSERT인데, 이미 seq 2가 있으면 PK·CHECK가 막고, 없으면 "계약 승인 전" 판정은 merchant 잠금 아래 Domain(§3.7.3) — (a)와 같은 수준 + R3는 DB |
| R5 | 은퇴 표 기록 누락 결함이 있으면 옛 코드가 재사용 가능 → 과거 감사가 다른 가맹점을 가리킬 수 있다 | 코드 → 가맹점 대응이 행으로 영구 보존(UPDATE·DELETE 권한 없음). 감사 행의 코드 → `merchant_code_assignment` 한 행 → 가맹점 하나(수학적으로 함수) |
| R6 | `merchant` 행 UPDATE는 이전 값을 지운다 — 변경 이력은 `master_change_history` detail에만 | 행 자체가 이력: seq 2 행에 `replaces_code`·`approval_id`·`reason_code`·`assigned_by`·`assigned_at` |
| 읽기 비용 | 현재 코드 = 열 하나 | 현재 코드 = 가맹점당 최대 2행 중 seq 최대(PK 색인 범위 읽기). jOOQ 목록은 상관 부분 질의 1개 |
| JPA | Entity 열 `updatable=true` 필요 → 다른 갱신 경로(entitlement revision)가 실수로 코드를 덮어쓸 위험 | 자식 행 append(계약 Revision과 같은 모양 — §1.2 `JpaContractRepository` 선례) |

**권장 (b)**(C-9). 이유(Auditability 중심): 감사·승인 대상이 코드가 된 이상(Q-MC-4) "감사에 보인 코드 → 한 가맹점" 대응이 **DB 권한·제약만으로** 영구 보장돼야 한다. (a)는 R2·R3·R4 모두 앱 판정에 기대고 runtime이 코드 UPDATE 권한을 영구히 갖는다.
(b)는 R1·R2·R3·R5·R6을 DB가 지키고, 앱 판정이 남는 것은 R4("계약 승인 전")뿐이며 그것도 merchant 잠금 직렬화·경합 시험·사후 대사 질의(§8 MC-1b-6)로 탐지한다.
**변경이 허용돼도 과거 감사 행의 옛 코드가 한 가맹점만 가리키는 이유**: 옛 코드는 seq 1 행으로 영구히 남고(UPDATE·DELETE 권한 없음) `UNIQUE(merchant_code)`가 다른 어떤 행(다른 가맹점의 seq 1·2, 같은 가맹점의 seq 2)도 그 값을 갖지 못하게 한다 — 재사용 불가가 대응의 유일성을 만든다.

**V13 표**(MASTER, collation `utf8mb4_0900_bin`):

| 열 | 형식 | 의미 |
|---|---|---|
| `merchant_id` | VARCHAR(64) FK → `merchant` | 가맹점 |
| `assignment_seq` | TINYINT, CHECK `IN (1, 2)` | 1 = 첫 배정(등록·채우기), 2 = 유일한 변경 |
| `merchant_code` | VARCHAR(14), CHECK `^[a-z][a-z0-9]{5,13}$`(`'c'`) | 배정된 코드 |
| `replaces_code` | VARCHAR(14) NULL | seq 2만 — 바뀌기 전 코드 |
| `source` | VARCHAR(16) CHECK `IN ('REGISTRATION','BACKFILL','CHANGE')` | 배정 경로(L-2 채우기 구별) |
| `approval_id` | VARCHAR(30) NULL, `UNIQUE uk_mca_approval(approval_id)` | seq 2만 — 2인 승인 ID(`apr_…`) |
| `reason_code` | VARCHAR(32) NULL | seq 2만 — 고정 목록(C-12) |
| `assigned_at`·`assigned_by` | DATETIME(3)·VARCHAR(64) | 잠금 뒤 시각·행위자(seq 2 = 승인자, C-17) |

제약: `PRIMARY KEY(merchant_id, assignment_seq)`, `UNIQUE uk_mca_code(merchant_code)`, `UNIQUE uk_mca_merchant_code(merchant_id, merchant_code)`(아래 자기 FK 대상),
`FOREIGN KEY fk_mca_merchant(merchant_id) REFERENCES merchant(merchant_id)`, `FOREIGN KEY fk_mca_replaces(merchant_id, replaces_code) REFERENCES merchant_code_assignment(merchant_id, merchant_code)`(바뀌기 전 코드는 **같은 가맹점의** 배정 코드여야 한다).
두 FK 모두 **`ON DELETE`·`ON UPDATE` 동작을 지정하지 않는다**(기본 `NO ACTION` = 즉시 거부 — 저장소 V1의 FK 관례와 같음. runtime은 UPDATE·DELETE 권한도 없어 연쇄 변경 경로가 없다),
CHECK `(assignment_seq = 1 AND replaces_code IS NULL AND approval_id IS NULL AND reason_code IS NULL AND source IN ('REGISTRATION','BACKFILL')) OR (assignment_seq = 2 AND replaces_code IS NOT NULL AND approval_id IS NOT NULL AND reason_code IS NOT NULL AND source = 'CHANGE' AND replaces_code <> merchant_code)`.
→ seq 2의 `replaces_code`는 같은 가맹점 seq 1 코드일 수밖에 없다(그 가맹점의 다른 배정 행은 seq 2 자신뿐이고 자기 값과 달라야 하므로).
- `approval_id`는 FK 없이 **UNIQUE만**(C-10 — 실행기 쓰기 순서에서 근거 행 INSERT가 업무 행 뒤일 수 있다. MASTER `V9__admin_approval_evidence.sql:4-5·24` `request_record_id` "FK 없이 UNIQUE" 선례). 대신 불변식 시험: seq 2 행 ↔ `admin_approval_evidence` EXECUTED(operation `ChangeMerchantCode`) 1:1.
- 같은 V13 끝에서 `ck_admin_approval_evidence_operation`을 **한 ALTER 문**(DROP + ADD — MASTER `V11__admin_approval_evidence_b3_operations.sql:3-6` nit-3 선례)으로 재생성해 `ChangeMerchantCode`를 더한다(C-20).
- **V14 권한**: `merchant_code_assignment` = `SELECT, INSERT`(UPDATE·DELETE 없음). `merchant` 권한 불변(코드 열이 없으므로 열 권한 재설계 불필요 — rev 0.1의 "merchant 열 단위 전환"은 **철회** 상태 유지).
- "모든 가맹점에 seq 1 행"은 자식 존재라 DB 제약으로 표현할 수 없다 → 등록 TX가 `merchant`·seq 1을 같은 TX에 INSERT(§3.3), 채우기는 V13 안의 `INSERT … SELECT … FROM merchant` 한 문장이라 빠지는 행이 없다.
  rev 0.3의 V13 끝 `SIGNAL` 검사는 **삭제**(리뷰 m-1 — schema migration 안에 절차형 검사를 두지 않고, 같은 문장 결과를 다시 세는 일은 migration 시험이 고정한다) → **MC-1 migration 시험**이 "seq 1 없는 `merchant` 행 수 = 0"을 단언(MC-1-5). 앱 읽기는 seq 1 없음 = 결함(IllegalState·경보).
- **1062 분류(리뷰 m-5 — `PersistenceFailures.kt:72-119` 표에 추가)**:

  | 키(`<table>.<index>`) | `StoreConstraint`(새 값) | businessKey | 새 TX 재판정 결과 |
  |---|---|---|---|
  | `merchant_code_assignment.uk_mca_code` | `MERCHANT_CODE` | true | 사전 조회가 찾아 `MERCHANT_CODE_TAKEN`(등록·변경 공통) |
  | `merchant_code_assignment.uk_mca_merchant_code` | `MERCHANT_CODE`(같은 값) | true | 같은 가맹점·같은 코드 행은 `uk_mca_code`도 위반하므로 어느 이름이 보고돼도 같은 분류 → `TAKEN` 또는 (seq 2 경합) `ALREADY_CHANGED` |
  | `merchant_code_assignment.PRIMARY`(merchant_id, seq) | `MERCHANT_CODE_SEQ` | true | seq 2 경합 → 판정 8 `MERCHANT_CODE_ALREADY_CHANGED`. seq 1 충돌은 새 ULID라 불가능(재판정에서도 같으면 결함 — 실행기 기존 경로) |
  | `merchant_code_assignment.uk_mca_approval` | `MERCHANT_CODE_APPROVAL` | true | 같은 approvalId 재사용 → 판정 3 `APPROVAL_ID_ALREADY_USED`(근거 표 PK `APPROVAL_EVIDENCE`와 같은 수렴) |
  | FK 1452(`fk_mca_*`) | — (UniqueConflict 아님) | — | 결함(정상 경로에서 불가능) — 기존 분류대로 실패 |
  분류 시험: 각 키 이름 → 위 값(`PersistenceFailuresSpec` 표 시험), 매핑 누락 시 `OTHER`(대조).
- Domain: 값 객체 `MerchantCode`(형식·예약어), `MerchantCodeAssignment`(seq·code·replaces·source·approvalId·reason·at·by), `Merchant`가 배정 목록(≤ 2)을 갖고 `currentCode`(= seq 최대) 제공.
  변경 메서드는 **`Merchant.changeCodeWithEvidence(…)` 하나**(§3.7.4). 근거 없는 변경 메서드는 두지 않는다(seed 경로 불필요 — C-11).

### 3.3 발급 지점(B1 등록 — MC-1)
- B1 `REGISTER_MERCHANT`(OPERATOR 1인)에서 필수 — 나중 부여면 대상 키가 두 가지가 된다.
- MASTER 순서: 형식(parser 400) → 예약어(Domain `MERCHANT_CODE_RESERVED`) → business 잠금(`MerchantOnboardingService.kt:122`) → `lockedNow()`(L123) → **배정 존재 조회**(`merchant_code_assignment` by code — 현재·은퇴 모두) → 있으면 저장된 거절 `MERCHANT_CODE_TAKEN` →
  `Merchant.register(…, code, …)`(seq 1 `REGISTRATION`) → INSERT `merchant` + seq 1 → 경합 1062는 `StoreConstraint(businessKey = true)` → 실행기 새 TX 재판정 → 사전 조회가 찾아 `MERCHANT_CODE_TAKEN`(MERCHANT-OPS-002 §9.3 review m-1과 같은 수렴 경로).
- 거절 reference 없음(C-12 — 어느 가맹점이 그 코드를 쓰는지 응답에 없음). 은퇴 코드와 사용 중 코드를 **구별하지 않는다**(같은 `MERCHANT_CODE_TAKEN`, C-18).
- 사전 중복 조회 endpoint 없음(C-4).

### 3.4 미리보기(ADMIN 화면만)
"영문 상호" 보조칸(저장 안 함, 음역 없음 — C-5) → 영문·숫자만 소문자화 → 첫 글자 영문까지 앞 자름 → 최대 10자 + 랜덤 4자, 6자 미만이면 랜덤 보충. `crypto.getRandomValues`, "다시 제안" 버튼, 직접 수정값 그대로 전송.
앞부분(상호 영문)이 비면 코드 첫 글자가 영문이어야 하므로 랜덤 첫 글자만 영문 23자(C-3 31자 중 영문)에서 뽑는다 — 앞부분이 있으면 랜덤 문자는 모두 31자 중(rev 0.4.3, MC-2 리뷰 NIT-1). 추출은 편향 없는 거부 표본.

### 3.5 기존 행 채우기(Demo — V13)
- 채우기(C-26 — 리뷰 m-7, 열 목록 명시 — 리뷰 m-1):
  `INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) SELECT merchant_id, 1, CONCAT('m', LEFT(SHA2(merchant_id, 256), 13)), NULL, 'BACKFILL', NULL, NULL, created_at, 'system:migration' FROM merchant`.
  `SHA2`는 소문자 16진 문자열을 돌려주므로 결과는 `m` + `[0-9a-f]{13}` = 14자, §3.1 정규식·예약어(모두 `m`으로 시작하지 않음)와 충돌 없음. 결정적(같은 `merchant_id` → 같은 코드, 시각도 `merchant.created_at`).
  ULID 꼬리 대신 해시를 쓰는 이유: ULID 뒤 13자는 무작위지만 대문자 Crockford 문자를 소문자로 접은 값이라 의미 없는 내부 키 일부가 공개 코드(외부 노출 — Q-MC-1)에 그대로 드러난다. 해시는 `mer_`를 역산할 수 없다(내부 키 비노출 — D-ADMIN-UI-004 ③).
  두 가맹점이 같은 13자 접두를 가지면 `uk_mca_code`에서 migration 실패(fail-closed) — migration 시험이 Demo 행 전체에 대해 충돌 0을 단언(MC-1-5).
- seed 새 DB: `merchantCode="demoshop01"`(C-6 — `DemoMerchantSeedValues.kt:33-37` 값 묶음에 추가).
- **이미 seed된 DB의 seed 재실행(C-23 — 리뷰 MAJOR-2, Coordinator 결정 (a))**: `DemoMerchantSeedService.step`(L125-136)이 Use Case를 부르기 **전에** 그 단계의 키(`AdminRequestScope(step.operation, step.key(prefix))`)로 기록을 찾고(`requests.find` — L83-87과 같은 조회),
  기록된 결과가 **SUCCEEDED**면 저장된 reference를 그대로 쓰고 호출하지 않는다(replayed 취급). 기록이 없거나 SUCCEEDED가 아니면 지금처럼 호출한다. 지문 버전 관리는 하지 않는다.
  **예외(C-27, rev 0.4.1)**: T0(효력 시작)를 싣는 단계(`carriesEffectiveFrom`)는 선확인 없이 지금처럼 호출한다 — 같은 키 공간을 다른 T0로 재실행하면 기존 DEMO-SEED-001 I-7대로 `ContentConflict`(선확인하면 저장되지 않은 새 T0가 출력에 찍힘). 이 단계들은 `REGISTER_MERCHANT` 지문 변경과 무관하므로 MAJOR-2 해결(MC-1-10)은 그대로다.
  효과: MC-1 전에 seed된 DB에서 재실행해도 `REGISTER_MERCHANT`가 다시 지문 비교(`AdminCommandExecutor.kt:204`)를 거치지 않아 `ContentConflict`가 나지 않고, 그 가맹점의 코드는 V13 채우기 값 그대로다(`demoshop01`로 바뀌지 않음).
  안전성: seed는 `requireDemoSeedPath`·Demo 모드 관문 안에서만 돌고, 성공 기록 재사용은 그 키로 이미 확정된 효과를 다시 쓰는 것뿐이다(새 효과 0). 관리 채널 명령의 지문 비교 규칙은 바꾸지 않는다(seed 서비스 안의 선조회만).
- 채우기 행은 `source = 'BACKFILL'`로 구별(L-2 완화 — 이력 이벤트는 없음).
- 채우기 가맹점도 계약 승인 전이면 1회 변경 가능(seed 가맹점은 seed가 계약을 승인하므로 이미 불변 — C-13).

### 3.6 지문·멱등 키
- `REGISTER_MERCHANT` 요청 지문 `[businessId, displayName, merchantCode]`(지금 `MerchantOnboardingService.kt:121`의 두 값 + 코드). 같은 키·다른 코드 409.
  배포 경계 재전송(L-3, Demo 한정): MC-1 배포 **전**에 ADMIN이 보낸 등록(코드 없는 본문)을 배포 **뒤** "같은 내용 다시 보내기"로 재전송하면 MASTER가 본문 형식 400 → ADMIN `Unknown("HTTP_400")`(`HttpMasterOpsGateway.kt:89`) — 운영자는 가맹점 조회로 결과를 확인한다(MASTER에 그 키 기록이 있으면 이미 등록된 것).
- 변경 명령은 §3.7.2 `mop1`(새 operation — 기존 operation의 `mop1` 벡터 불변, C-7).

### 3.7 코드 변경 명령(MC-1b — Q-MC-7·Q-MC-10 답)

#### 3.7.1 "첫 계약 승인"의 정의(C-14 — 해석 기록, 사용자 결정 원문 "계약 승인 전")
그 가맹점을 대상으로 하는 계약(`contract.merchant_id` = 가맹점) 중 **어느 Revision이든 `contract_revision_approval` 행이 하나라도 있으면** "첫 계약 승인 뒤"다.
- 효력 시작 전(미래 시작) 승인도 포함 — 승인 사실이 생긴 순간 첫 MID 선발급이 가능해지고(D-MD-015), MID가 생기면 외부 노출 경로가 열린다.
- 계약이 나중에 철회·종료돼도 "승인 뒤"로 남는다(승인 사실은 append-only — 되돌아가지 않는다).
- ADMIN에 승인 **요청**만 있는 상태(미해결 `MERCHANT_CONTRACT_APPROVAL`)는 "승인 전"이지만, 같은 미해결 키 그룹이라 변경 요청과 공존하지 않는다(§5.2).

#### 3.7.2 MASTER endpoint
- `POST /internal/v1/admin/merchant-operations/merchant-code-changes` — 서명 v1·`ADMIN_SERVICE`(D-MOPS-005), 본문 16 KiB, query 금지, **기존 근거 경로 endpoint와 같은 쓰기 permit**(`AdminChannelPermits.write`), 응답 MERCHANT-OPS-002 §3.3 그대로(거절 reference 없음).
- 본문 = 승인 근거 공통 필드(MERCHANT-OPS-002 §9.3 표 그대로 — `idempotencyKey` = `mop1.<approvalId>`, `requestedBy`·`approvedBy`·`approvedAt`·`executeBefore`·`evidenceRef`·`approvalFingerprint`, `actor` 없음) + 명령 필드:
  `merchantId`(`mer_`), `currentCode`(형식 §3.1), `newCode`(형식 §3.1), `reasonCode`(C-12 목록). **등록자 관련 필드 없음**(Q-MC-10).
- `mop1` 줄 순서: `mop1`, `ChangeMerchantCode`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, **merchantId, currentCode, newCode, reasonCode**. 두 앱 독립 벡터(acd1 선례).
- operation `ChangeMerchantCode`: `admin_approval_evidence.operation` CHECK 값은 MC-1 V13에서 미리 추가(C-20 — §1.4). 성공 reference = **`mer_…`**(merchantId — 기존 reference 형식, ADMIN `conformsTo` 문법 `^mer_<ULID>$` + pinned merchantId와 같음).
- 기한 확인·결정 조회(`approval-deadline-confirmations`·`approval-decisions`)는 기존 endpoint에 operation 분기만 추가. handler 전수 집합 + `merchant-code-changes`.
- **근거 없는 경로 없음**(C-11): seed가 코드를 바꿀 일이 없으므로 `requireDemoSeedPath`로 감싼 근거 없는 메서드를 만들지 않는다. ArchUnit: `changeMerchantCodeWithEvidence` 호출자는 관리 채널 컨트롤러뿐, `DemoMerchantSeedService`는 호출하지 않음.

#### 3.7.3 판정 순서(MASTER 로컬 TX 하나 — `executeApproved`, C-49 방향)

| # | 판정 | 거절(`MasterRejection` 끝에 추가 — 영속 이름) | 위치 |
|---|---|---|---|
| 1~3 | 근거 공통(**같은 행위자 = 요청자 ≠ 승인자**·창·approvalId 1회) | 기존 `APPROVAL_SAME_ACTOR`·`APPROVAL_WINDOW_INVALID`·`APPROVAL_ID_ALREADY_USED`(`MasterRejection.kt:80-82`) | 기존 |
| 4 | 가맹점 없음(잠금 전 `find`) | 기존 `MERCHANT_NOT_FOUND` | Application |
| 5 | **merchant 잠금**(`merchants.lock` — `FOR UPDATE`) → `lockedNow()` 만료 훅 | 기존 `APPROVAL_EXPIRED` | 실행기 훅 |
| 6 | (잠금 뒤 읽기) `contracts.hasApprovedRevisionFor(merchantId)` — 새 읽기 메서드(JPQL/네이티브 `EXISTS`, 영속성 컨텍스트 캐시 미사용, READ COMMITTED라 잠금 뒤 최신 커밋) | — (사실만 Domain에 전달) | Application |
| 7 | 가맹점 비활성 | 기존 `MERCHANT_NOT_ACTIVE` | Domain |
| 8 | 이미 1회 변경(seq 2 있음) | **`MERCHANT_CODE_ALREADY_CHANGED`** | Domain |
| 9 | 계약 승인 사실 있음(6의 결과) | **`MERCHANT_CODE_LOCKED`**("계약 승인 뒤에는 바꿀 수 없음") | Domain |
| 10 | `currentCode` ≠ 현재 코드(8이 아니면 정상 경로에서 불가능 — 결함 신호) | **`MERCHANT_CODE_MISMATCH`**(경보 — ADMIN `DEFECT_REJECTIONS`에 추가) | Domain |
| 11 | `newCode` 예약어 | 기존(§3.3) `MERCHANT_CODE_RESERVED` | Domain(값 객체) |
| 12 | `newCode` 배정 이력 존재(현재·은퇴·자기 코드 모두) | 기존(§3.3) `MERCHANT_CODE_TAKEN` | Application(사전 조회) + 1062 재시도 수렴 |
| — | 성공: `beginWrites()` → seq 2 INSERT(`CHANGE`, replaces = 옛 코드, approval_id, reason, 잠금 뒤 시각, 승인자) → `merchants.update`(`@Version` +1 — 같은 가맹점 다른 쓰기와 직렬화 증거) → 변경 이력 `MerchantCodeChanged` detail `{merchantId, oldCode, newCode, reasonCode, approvalId}` → Outbox `MerchantCodeChanged` → (MC-4 이후) `recordEntitlementChange()`·Snapshot 발행 → 근거 EXECUTED 행 | — | 같은 TX |

- **원 가맹점 등록자 판정 없음**(D-MCODE-002 Q-MC-10): `merchant.created_by` = 승인자여도 위 판정만 통과하면 성공한다. 등록자 거절 code(rev 0.2 후보 `APPROVER_IS_MERCHANT_REGISTRANT`)는 만들지 않는다 — 기존 `APPROVER_IS_REVISION_REGISTRANT`·`…_ACTIVATION_REGISTRANT`·`…_SITE_REGISTRANT`(`MasterRejection.kt:84·90-91`) 적용 범위도 넓히지 않는다.
- 모든 거절은 저장된 거절 + 근거 REJECTED 행(MERCHANT-OPS-002 §9.3 그대로), 업무 행·이력·Outbox 불변.
- **경합(계약 승인 vs 코드 변경, 같은 가맹점)**: 둘 다 merchant 행을 먼저 잠근다(계약 승인 `approveRevision` `MerchantOnboardingService.kt:240`, 변경 판정 5) → 직렬화. 변경이 먼저 커밋하면 계약 승인은 새 코드와 무관하게 성공(계약 승인은 코드를 보지 않는다),
  계약 승인이 먼저 커밋하면 변경은 판정 6에서 승인 사실을 보고 `MERCHANT_CODE_LOCKED`. "계약 승인 커밋 뒤 변경 성공"은 불가능 — 시험 §8 MC-1b-4.
  잠금 순서 merchant → (contract는 읽기만 — 잠그지 않음) 이므로 계약 승인(merchant → contract)과 교착 없음.
  **전제 고정(리뷰 m-6)**: 이 직렬화는 "`contract_revision_approval`을 쓰는 모든 경로가 대상 merchant를 먼저 잠근다"에 기댄다. 지금 쓰는 곳은 `JpaContractRepository.update`(L64-67) 하나이고 호출 경로는 `approveRevision`(L230-255 — L240 잠금 뒤 L247) 하나다.
  시험으로 고정: (a) ArchUnit — `Contract.approve`·`Contract.approveWithEvidence` 호출자는 `MerchantOnboardingService.approveRevision`뿐, `ContractRepository.update` 호출자 집합 고정(새 호출자가 생기면 RED → 잠금 순서 검토 강제),
  (b) 순서 기록 대역 Repository 시험 — 두 승인 경로(근거·seed) 모두 `merchants.lock`이 `contracts.update`보다 먼저 호출됨(MC-1b-12).
- **경합(변경 vs 등록/다른 변경, 같은 새 코드)**: `UNIQUE(merchant_code)`가 하나만 통과시킨다 → 진 쪽 `MERCHANT_CODE_TAKEN`.
- **경합(같은 가맹점 변경 둘 — 다른 approvalId, ADMIN 우회·결함)**: merchant 잠금 직렬화 → 두 번째는 판정 8 `MERCHANT_CODE_ALREADY_CHANGED`. DB 방어선: PK `(merchant_id, 2)` 1062.

#### 3.7.4 Domain·Application 구조(ARCHITECTURE-CONVENTIONS §14)
- Domain `Merchant.changeCodeWithEvidence(newCode: MerchantCode, expectedCurrent: MerchantCode, contractApproved: Boolean, reason: MerchantCodeChangeReason, approvalId: ApprovalId, at: Instant, approver: ActorRef): MerchantCodeAssignment` — 순서 7→8→9→10, 11은 `MerchantCode` 생성 시.
  `approver`는 `assigned_by` 기록용이며 **판정 입력이 아니다**(등록자 비교 없음 — Q-MC-10). 요청자 ≠ 승인자는 실행기 공통 판정 1이 이미 지킨다.
  Domain은 계약 Aggregate를 모른다 — "승인 사실"은 Application이 잠금 뒤 읽어 Boolean으로 넘긴다(교차 Aggregate 판정 — Contract 잠금 없이 읽기만, 승인 사실은 append-only라 읽은 뒤 사라지지 않음).
- Application: 명령 `ApprovedMerchantCodeChangeCommand(request, evidence, window, merchantId, currentCode, newCode, reasonCode)`, Use Case `MerchantOnboardingUseCase.changeMerchantCodeWithEvidence`.
  required Port 추가: `ContractRepository.hasApprovedRevisionFor(merchantId): Boolean`(Aggregate 저장 Port의 존재 조회 — 새 Reader를 만들지 않는 이유: 명령 TX 안 판정 입력이라 CQRS 조회가 아님), `MerchantRepository`는 기존 `update`(`MerchantRepository.kt:16`)가 새 배정 행을 append(계약 Revision 선례).
  코드 → 가맹점 조회 `MerchantRepository.findCodeOwner(code): MerchantId?`(배정 표 PK가 아닌 UNIQUE 색인 한 행).
- adapter/persistence: `JpaMerchantCodeAssignmentEntity`(모든 열 `updatable = false`, `@Immutable`) + `MerchantCodeAssignmentJpaRepository`. `JpaMerchantEntity`에 `@OneToMany`를 두지 않고 **`JpaMerchantRepository`가 따로 읽어 Mapper로 조립**한다
  (확인됨 — `JpaContractRepository.kt:17-18·46-67·81-91`이 Revision·승인 행을 같은 방식으로 다룬다. rev 0.2의 "확인 후"를 확정).

### 3.8 MASTER 조회 변화
응답 모양은 목록·코드 검색·상세를 **따로** 정한다(리뷰 MAJOR-1 — 지금은 목록과 모든 검색이 같은 `merchant-searches` 응답·ADMIN `DIRECTORY` 모양을 공유하므로, 코드 검색 전용 필드를 목록에 섞지 않는다):

| 응답 | 언제 | 항목 키(지금 `DIRECTORY` 항목 9키 `MasterResponseShape.kt:81-82` 기준) | ADMIN 모양 |
|---|---|---|---|
| 목록·기존 검색 | `merchant-searches` 본문에 `merchantCode`가 **없을 때**(lifecycle·사업자·등록번호·merchantId 검색 포함) | 9키 + `merchantCode`(현재) = 10키 | `DIRECTORY`(항목에 `merchantCode` 추가) |
| 코드 검색 | 본문에 `merchantCode`(정확 일치, §3.1 형식 — 위반 400)가 **있을 때**. 다른 필터와 함께 오면 400(단일 기준 — 은퇴 코드 일치 의미를 섞지 않음) | 10키 + `matchedRetiredCode: true|false` = 11키, 0개 또는 1개 | 새 `DIRECTORY_BY_CODE` — ADMIN이 보낸 기준으로 모양을 고른다(응답을 보고 고르지 않음) |
| 상세 | `GET /merchants/{merchantId}` | `merchant` 객체에 `merchantCode` 추가, 최상위에 `merchantCodeHistory[]`(최대 2, `{code, seq, source, replacesCode?, assignedAt, assignedBy, reasonCode?}` — approval_id 없음, 근거 조회는 ADMIN 근거 보기 경로)·`merchantCodeChangeable`(= seq 2 없음 ∧ 승인 사실 없음 ∧ ACTIVE — **advisory**, 권위는 §3.7.3) | `DETAIL`(L184-250)에 같은 키 추가 |

- 코드 검색은 **현재·은퇴 코드 모두** 일치(은퇴 코드면 `matchedRetiredCode: true`). 대문자 등 형식 위반 400.
- `matchedRetiredCode`는 코드 검색 응답에만 있다 — 목록 응답에 이 키가 오면 ADMIN `DIRECTORY`가 해석 불가(503, fail-closed — 시험 MC-1-9 대조군).
- rev 0.2의 상세 `registeredBy` 추가는 **철회**(Q-MC-10 — ADMIN TX1 비교 대상이 없다). 상세 응답 필드는 위 셋만 늘어난다.

## 4. MASTER·ADMIN API 변화(요약)
- 등록 본문 `merchantCode` 필수. 응답 reference는 `mer_`(내부용).
- 조회 §3.8. 이력 detail·Outbox `MerchantRegistered`에 코드.
- **MC-1 배포 단위에 같이 들어가는 ADMIN 변경(C-22)**: ① `MasterResponseShape` — `DIRECTORY` 항목 `merchantCode`, 새 `DIRECTORY_BY_CODE`, `DETAIL`의 세 키(§3.8). 코드 문법은 §3.1 정규식, `merchantCodeHistory` 배열 최대 2.
  ② 가맹점 등록 화면 API·Use Case 본문에 `merchantCode` 필수(ADMIN 형식 검사 §3.1 → 400 `INVALID_INPUT`, ADMIN 요청 지문에 포함), MASTER 등록 호출 본문에 전달. ③ 가맹점 등록 ② 단계에 **코드 입력칸**(직접 입력만, 형식 안내). 미리보기·"다시 제안"·표시 교체·코드 검색 화면은 MC-2.
  배포 순서는 MASTER·ADMIN 동시(같은 Demo 재기동) — 한쪽만 올라간 상태를 지원하지 않는다(L-3·C-22).
- 코드 변경: MASTER §3.7.2, ADMIN 승인 요청 §5.5.
- ADMIN 화면: `mer_` 대신 코드 표시, 내부 호출은 merchantId.

## 5. 감사·승인 대상 전환(Q-MC-4·Q-MC-9) + 변경 승인 종류(Q-MC-7·Q-MC-10)

### 5.1 바뀌는 것(새 행부터 — MC-3)
| 위치 | 지금 | 뒤 |
|---|---|---|
| `admin_audit_event.target_ref` — `MERCHANT_VIEWED`·가맹점 등록 성공·가맹점 대상 승인 감사 | `mer_` | **그 시점의 현재 코드**(MASTER 응답에서 받은 값). MASTER가 NOT_FOUND면 코드가 없으므로 `mer_` 그대로 |
| 같은 표 — 사업자·계약·CSA·Site·MID 대상 | 각 키 | 불변 |
| Domain `AdminAuditEvent.TARGET_REF`(`AdminAuditEvent.kt:54`) | `^([a-z]{3,4}_<ULID>|ACQ.…)$` | + `|[a-z][a-z0-9]{5,13}` (`_` 없음 → 기존 형식과 겹치지 않음 — 시험으로 고정) |
| `admin_approval_request.target_ref`(MERCHANT) | `mer_` | 요청 시점 코드 |
| `admin_approval_request.target_merchant_id`(새 열 `VARCHAR(30)` NULL 허용 — **MC-1b V13**) | — | MERCHANT 대상 새 행은 `mer_` 필수. CHECK(리뷰 m-2 — 아래 넷 모두): ① 형식 `target_merchant_id IS NULL OR REGEXP_LIKE(target_merchant_id, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c')` ② `target_kind <> 'MERCHANT'`이면 `target_merchant_id IS NULL` ③ MERCHANT이면 `target_merchant_id IS NOT NULL OR REGEXP_LIKE(target_ref, '^mer_…$')`(기존 행 허용) ④ `target_ref`가 `mer_` 형식이면 `target_merchant_id IS NULL OR target_merchant_id = target_ref`(두 값이 다른 가맹점을 가리킬 수 없음) |
| `unresolved_key`(생성 열 — **MC-1b V13**) | `MERCHANT[:그룹]:<target_ref>` | `MERCHANT[:그룹]:<COALESCE(target_merchant_id, target_ref)>` — **기존 행은 target_ref = `mer_`라 같은 키**(행 수정 0) |
| `admin_merchant_ops_command` | `merchant_id` | 그대로(이미 `mer_`) |
| `pinned_target` | … | + `merchantCode`(표시용 — 아래 §5.3) |
| 미해결 목록 `JooqMerchantPendingApprovalReader.kt:23`(**MC-1b**) | `TARGET_REF.eq(merchantId)` | `COALESCE(TARGET_MERCHANT_ID, TARGET_REF).eq(merchantId)` |

**`target_merchant_id`를 쓰는 생성자(리뷰 m-3 — MC-1b, 열이 생기는 배치)**: 기존 가맹점 대상 9종류(`MERCHANT_CONTRACT_APPROVAL`·`MERCHANT_FIRST_MID_ISSUANCE`·`MERCHANT_PAYMENT_METHOD_ACTIVATION`·`MERCHANT_SITE_REVIEW_APPROVAL`·`MERCHANT_CURRENCY_ENABLEMENT`·`MERCHANT_CARD_ROUTE_ACTIVATION`·`MERCHANT_PAYMENT_METHOD_RESUME`·`MERCHANT_CARD_ROUTE_RESUME`·`MERCHANT_SITE_ACTIVATION_CONFIRMATION` — ADMIN `V11…sql:36-38`)와 새 `MERCHANT_CODE_CHANGE`의 **모든 요청 생성 경로**가 새 행에 `target_merchant_id = merchantId`를 쓴다.
구현은 승인 요청 Domain 생성(가맹점 대상 팩토리) 한 곳에서 채워 종류별 누락을 막고, 종류마다 시험 1개(10개 — 생성된 행의 `target_merchant_id` = 요청 merchantId, MC-1b-8). MC-1b 시점에는 `target_ref`도 `mer_`이므로 CHECK ④에 따라 두 값이 같다.
ADMIN migration: MC-1b **V13**(위 새 열·생성 열 재작성 — V11과 같은 표 재작성 주의, 종류 CHECK·명령 종류 CHECK에 코드 변경 추가), MC-3 **V15**(가맹점 대상 CHECK `target_ref` `^(mer_<ULID>|[a-z][a-z0-9]{5,13})$` — `V11…sql:28-33` 재생성, 한 ALTER 문). 기존 행 수정 0(Q-MC-9).
**rev 0.1 §5.2 사전 조건("미해결 MERCHANT 승인 있으면 SIGNAL")은 철회 상태 유지**: 미해결 키가 `mer_` 기준으로 계산되므로 전환 전 행과 뒤 행이 같은 키를 만들어 두 미해결 키 공존이 생기지 않는다.

### 5.2 미해결 키를 `mer_`에 두는 이유(권장 — 대안 비교)
- 문제: 키를 코드에서 계산하면, 코드 변경 전에 열린 `SITE_REVIEW` 승인(계약 전에도 열림 — §1.3)은 `MERCHANT:SITE_REVIEW:<옛 코드>`, 변경 뒤 새 요청은 `…:<새 코드>` → **같은 가맹점·같은 그룹에 미해결 둘**(C-35 위반).
- (권장) **키는 `mer_`, 표시·감사는 코드**: 코드 변경이 미해결 유일성에 영향을 주지 않는다. Q-MC-4("승인 대상을 코드로")는 `target_ref`·감사·화면에서 지키고, 키는 내부 유일성 장치일 뿐이다(내부 키 유지 원칙 — D-ADMIN-UI-004 ③).
- (대안) 변경 명령이 "그 가맹점의 모든 그룹에 미해결 없음"을 확인: ADMIN에는 가맹점 행이 없어 요청 TX끼리 직렬화할 잠금 대상이 없다(요청 TX의 미해결 검사와 변경 승인 TX1이 경합) → 앱 판정만으로는 틈이 남는다. 채택하지 않음.
- 변경 종류의 그룹: `MERCHANT_CODE_CHANGE`는 **`MERCHANT:<mer_>` 그룹 공유**(계약 승인·첫 MID와 같은 그룹 — C-15, 생성 식의 ELSE 분기). 효과: 변경 요청과 계약 승인 요청이 ADMIN에서 동시에 미해결일 수 없다(MASTER 경합 판정 §3.7.3 앞의 1차 차단).
  `SITE_REVIEW` 그룹은 공존 가능 — Site 심사 승인은 코드를 보지 않으므로 무해(§5.3).

### 5.3 pinned의 코드
- 변경 종류 외(계약 승인·Site 심사 등, MC-3부터): pinned `merchantCode`는 **표시용**이며 A 단계 비교 대상에서 뺀다(대상 동일성은 `merchantId`로 비교 — 코드 변경 때문에 Site 심사 승인이 INVALIDATED되지 않게, C-19). 승인 상세는 "요청 시 코드 → 현재 코드"가 다르면 둘 다 표시.
  rev 0.1의 "pinned 불일치 → `APPROVAL_INTEGRITY_VIOLATION`"은 철회 상태 유지(변경이 정상 경로가 되었으므로 결함 신호가 아님).
- 변경 종류: 새 봉인 타입 `PinnedMerchantCodeChangeTarget` `{merchantId, merchantName, currentCode, newCode, reasonCode, contractApproved:false, codeChangeCount:0}` — **등록자 필드 없음**(Q-MC-10, rev 0.2의 `merchantRegisteredBy` 제거).
  A 단계 MASTER 재조회 `currentCode`·`merchantCodeChangeable`가 다르면 `INVALIDATED(TARGET_CHANGED)`(MASTER 미호출).
  `newCode` 사용 가능 여부는 A 단계에서 보지 않는다(사전 조회 없음 — C-4, 권위는 MASTER 판정 12).

### 5.4 기존 기록 연결(Q-MC-9)
- 기존 행 수정 0. 감사 검색에서 입력이 코드면 MASTER 검색(§3.8 — 은퇴 코드 포함)으로 그 가맹점의 `mer_`와 **모든 코드**를 얻어 `target_ref IN (mer_, code1, code2)`로 조회, 각 행 옆에 "당시 대상 표기"(mer_·옛 코드·현재 코드)와 현재 코드를 함께 표시.
- `mer_`로 찾아도 같은 집합. 대응표는 MASTER 하나(ADMIN에 사본 없음 — 소유권).

### 5.5 ADMIN 변경 승인 종류(MC-1b — MERCHANT-OPS-002 §9.5 틀 그대로)
- `ApprovalKind.MERCHANT_CODE_CHANGE(OWNER_SERVICE, OPERATOR, MERCHANT)` — 승인자 APPROVER + 직전 TOTP, `mayActivatePrivilegedRole` false. `OwnerExecution` 지문 `mop1`, 소유 참조 문법 `^mer_<ULID>$`.
- 요청 `POST /api/approvals/merchant-code-change` 본문 `merchantId, newCode, reasonCode, evidenceRef`(필수) → 201 `{approvalId}`. 세션·CSRF·no-store·16 KiB·JSON만.
  순서: 형식(newCode §3.1, 현재 코드와 같으면 400 `INVALID_INPUT`) → 세션 역할 OPERATOR → MASTER 상세 조회(TX 밖) → advisory: `merchantCodeChangeable` false면 409 `TARGET_NOT_ELIGIBLE_ADVISORY` →
  TX(승인 행 INSERT(`target_merchant_id` = `mer_`) — 미해결 UNIQUE `MERCHANT:<mer_>` → 요청자 재검사 → 명령 행(pinned) → 감사 `APPROVAL_REQUESTED`, detail `from=<현재>;to=<새>;reason=<code>`).
- **target_ref(배치 경계)**: MC-1b 시점에는 ADMIN 가맹점 대상 CHECK(`V11…sql:28-33`)와 Domain `TARGET_REF`가 `mer_`만 받으므로 승인 행·감사 행 `target_ref` = **`mer_`**(다른 가맹점 종류와 같음). MC-3 배포 뒤 새 요청부터 **요청 시점(옛) 코드**.
  D-MCODE-002 ⑤ old→new는 두 시기 모두 detail `from=;to=`로 남는다(rev 0.2의 "MC-1b에서 target_ref = 코드"는 CHECK와 충돌해 정정).
- 승인(`/api/approvals/{id}/approve` 기존 2-TX): A 재조회(§5.3) → TX1 참여자(**요청자 ≠ 승인자 — 기존 참여자 규칙만**) → TOTP → 본문 바이트·`mop1` → C 실행 → TX2.
  **등록자 분기 없음**(D-MCODE-002 Q-MC-10): `MerchantOpsApprovalService.registrantOf`(`MerchantOpsApprovalService.kt:600-607`)에 `is PinnedMerchantCodeChangeTarget -> null`을 더한다(B3-2 `PinnedCurrencyEnablementTarget -> null` 선례 — 봉인 `when`이라 새 타입은 분기 선택을 강제받는다. 주석에 결정 ID).
  결과 불명·회복·응답 매핑은 §9.5 표 그대로(종류 분기만).
- **감사 old→new(D-MCODE-002 ⑤)**: 이 승인 건의 모든 감사 행(`APPROVAL_REQUESTED`·`EXECUTING`·`EXECUTED`·`OWNER_REJECTED`…)의 target_ref = 승인 행의 target_ref(위 배치 경계), detail에 `from=<옛>;to=<새>`. MC-3 뒤 실행 뒤 새 감사 행부터 새 코드. 이전 감사 행은 그대로(D-MCODE-002 ⑥).
- MASTER 거절 → 화면 문구: `MERCHANT_CODE_TAKEN` "이미 사용 중이거나 사용했던 코드입니다", `MERCHANT_CODE_LOCKED` "계약 승인 뒤에는 코드를 바꿀 수 없습니다", `MERCHANT_CODE_ALREADY_CHANGED` "코드는 한 번만 바꿀 수 있습니다",
  `MERCHANT_CODE_RESERVED` "사용할 수 없는 코드입니다", `MERCHANT_CODE_MISMATCH` "시스템 점검 필요(관리자에게 알림)"(ADMIN 경로에서는 결함 — `MerchantOutcomes.kt:40-43` `DEFECT_REJECTIONS`에 추가해 경보).
- 화면: 가맹점 상세 "코드" 옆 "코드 변경 요청"(OPERATOR, `merchantCodeChangeable`일 때만 — 표시만), 대화상자 "계약 승인 전 1회만, 옛 코드는 다시 쓸 수 없습니다", 새 코드 입력 + 미리보기 재사용 + 사유 선택 + 근거 참조.
  승인함 라벨 "가맹점 코드 변경", 상세 "현재 → 새 코드". 상세 패널 "코드 이력"(seq 1·2). **등록자 안내 없음**(rev 0.2 화면 항목 제거).

## 6. 외부 노출(Q-MC-8 — 출력 전용)
| 지점 | 필드 | 비고 |
|---|---|---|
| 웹훅(WEBHOOK-001) | `merchantCode` | schemaVersion 올림·호환 시험 |
| 결제 생성·조회 응답 | `merchantCode` | 출력만. 요청 본문의 `merchantCode`는 모르는 필드(기존 엄격 해석 — 400) |
| 정산서(SETTLEMENT-001) | 코드 열 | 수용 기준만, 경로 C-8 |
| 내부 유지 | `mer_`·`biz_`·Snapshot 키 | 외부 본문 0건 시험 |
| Simulator/REAL | 없음 | 기관 전송 0건 시험 |

- **외부에 나간 코드는 바뀌지 않는다**: 외부 노출은 MID가 있어야 생긴다(결제·웹훅·정산 모두 MID 단위) → 첫 MID는 승인된 Revision이 있어야 발급(`Contract.issuingBasis` `Contract.kt:96-99` — `REVISION_NOT_APPROVED`) → 그 시점부터 코드는 불변(§3.7.1). 따라서 가맹점 연동 측이 받은 코드가 바뀌는 경우는 없다(시험 MC-4-4).
- PAYMENT 경로: Snapshot v4(+`merchantCode` — 지금 v3, golden `entitlement-snapshot-v3*.golden.json` 두 앱) → `entitlement_head.merchant_code`(**PAYMENT V35** NULL 허용, 권한 변경 없음 §1.4) → 웹훅·응답. 코드 없는 MID는 필드 생략.
- **v3 → v4 승격(리뷰 m-4)**: 기존 승격 규칙 그대로 — lease 갱신 때 최근 issue의 schema_version(3) < 4이면 갱신이 아니라 **승격 발행**(같은 merchant 잠금 TX에서 `entitlement_revision + 1`, leaseSeq 0, HIGH — MASTER-SNAPSHOT-002 §5.2, AUTHN-001 §9.2, `EntitlementLeaseService` 경로).
  **같은 revision을 v4 내용으로 재발행하지 않는다** — PAYMENT `SnapshotApplicationRule.kt:40-42`가 같은 revision·다른 contentHash를 `INTEGRITY_CONFLICT`로 막아 head를 차단한다(코드 필드가 생기면 contentHash가 바뀐다).
  배포 순서(MASTER-SNAPSHOT-002 §5.4): ① PAYMENT(v1~v4 해석 + V35) 먼저 ② MASTER(v4 조립·승격). 역순이면 PAYMENT가 v4를 `VERSION_UNSUPPORTED`로 받아 head가 막힌다(fail-closed이나 가용성 사고) → MC-4 시험 `SchemaRolloutOrderV4Spec`.
- 변경 명령은 MC-4 뒤 `recordEntitlementChange()`·Snapshot 발행(같은 TX — 다른 코드를 같은 revision으로 내보내지 않는다, MERCHANT-OPS-002 review M-2 원칙). 같은 revision·다른 코드는 기존 무결성 규칙대로 `INTEGRITY_CONFLICT`.
  revision이 오르며 코드가 바뀐 Snapshot은 정상 갱신이지만, 그 가맹점에 MID가 있으면 불가능한 상태 → PAYMENT 경보 지표(`merchant_code_changed_with_mid`).

## 7. 배치(전달 순서 = Demo 순서, C-16)
- **MC-1 MASTER 모델·등록 + ADMIN 최소 연결(한 배포 단위 — C-22, 리뷰 MAJOR-1)**: MASTER V13(배정 표·해시 채우기 + 근거 operation CHECK `ChangeMerchantCode` — C-20)·V14(권한), `MerchantCode`·`MerchantCodeAssignment`, 거절 `MERCHANT_CODE_RESERVED`·`MERCHANT_CODE_TAKEN`, 1062 분류, 등록 지문, 조회·코드 검색(은퇴 포함)·이력·Outbox, seed `demoshop01`·seed 성공 기록 선확인(C-23)
  **+ ADMIN** `MasterResponseShape`(`DIRECTORY`·`DIRECTORY_BY_CODE`·`DETAIL`), 등록 본문·요청 지문 `merchantCode`, 가맹점 등록 ② 코드 입력칸(직접 입력). MASTER·ADMIN 동시 배포. 이 배치 뒤 ADMIN의 목록·상세·검색·등록이 모두 정상 동작해야 한다(MC-1-9).
  Demo(ADMIN 화면으로): seed 상세가 열림(코드는 MC-2부터 화면 표시 — 응답에는 있음), 가맹점 등록에서 코드 직접 입력 → 성공, 같은 코드 재등록 → "이미 사용 중이거나 사용했던 코드", 기존 seed DB에서 seed 재실행 → 충돌 없음.
- **MC-2 ADMIN 미리보기·표시**: 미리보기(상호 영문 + 랜덤 4자)·"다시 제안", 표시 교체(`mer_` → 코드), 코드 검색 화면·ADMIN 검색 parser `merchantCode`(ADMIN만 — MASTER는 MC-1에서 이미 지원). ADMIN migration 없음. Demo: 미리보기 → 수정 → 등록 → 목록·상세에 코드, 코드 검색(은퇴 코드 표시는 MC-1b 뒤).
  표시 교체 범위(C-28): **표시 텍스트·접근 이름·목록·상세·결과·검토 화면**. 등록 마법사 Site·계약 초안 카드의 가맹점 입력칸은 MC-2b.
- **MC-2b 마법사 가맹점 입력칸(C-28 — D-ADMIN-UI-004 ③ 완결)**: 등록 마법사 Site·계약 초안 카드의 "가맹점 ID"(내부 키 `mer_`) 입력칸을 없앤다. 방식은 둘 중 하나 —
  (가) **코드 입력** + ADMIN 코드 검색(§3.8 — MC-2에서 생긴 경로)으로 내부 키를 해석하고 가맹점 이름·코드를 확인 표시한 뒤 저장 본문에는 내부 키, (나) 앞 카드(가맹점) 결과를 **자동 선택**하고 칸을 숨김(다른 가맹점은 (가) 경로).
  **방식 선택은 사용자 화면 검토 때 결정**(미결 — 시점: MC-2 Demo 화면 검토. 그 전에는 MC-2b를 구현하지 않는다). ADMIN·MASTER API·migration 변화 없음(화면만). 막히는 다른 배치 없음(MC-1b·MC-3·MC-4와 독립).
- **MC-1b 코드 변경 2인 명령**(Q-MC-7·Q-MC-10 — 답변됨, 막힘 없음): MASTER `ChangeMerchantCode`(migration 없음 — V13에 CHECK 선반영), ADMIN 종류·요청·승인·회복 분기·화면,
  ADMIN **V13**(종류·대상·명령 종류 CHECK, `target_merchant_id`, 미해결 키 `COALESCE` — **MC-3의 키 변경을 이 배치로 당긴다**: 변경이 가능해지는 순간부터 키가 `mer_` 기준이어야 하므로. MC-3은 target_ref 표기 전환만 남는다), V14 비움.
  Demo(계정 둘 — A OPERATOR 요청, B APPROVER 승인; **B가 그 가맹점 등록자여도 성공**): 계약 전 가맹점 코드 변경 → 상세 코드 이력 → 옛 코드로 새 가맹점 등록 시도 `TAKEN` → 계약 승인 뒤 "코드 변경" 버튼 없음·API 우회 시 `MERCHANT_CODE_LOCKED`.
- **MC-3 감사·승인 대상 표기 전환**(Q-MC-4·Q-MC-9): ADMIN V15, target_ref = 코드(감사·승인 요청), `TARGET_REF` 정규식, pinned 표시, 감사 연결 조회.
- **MC-4 PAYMENT 투영·외부 필드**(Q-MC-8): Snapshot v4(승격 = revision +1, §6), PAYMENT V35, 웹훅·응답, golden, 변경 명령의 revision +1. 배포는 PAYMENT 먼저, MASTER 뒤.
- SETTLEMENT-001: 수용 기준만.

## 8. 시험·수용 기준(배치별 — MERCHANT-OPS-002 §8.4 형식, Verifier 확인 항목)

공통 증거·Fixture 규칙은 MERCHANT-OPS-002 §8.1~§8.4와 같다(exit code·시험 개수·0건/skip 실패·보고서·source fingerprint·시각·대조군 단언). **RED 먼저**.
Fixture: `MerchantCodeFixtures.VALID = "shopabcd12"`, 경계 `"abcdef"`(6)·`"a" + "b".repeat(13)`(14) 통과, 5·15자 거절, `MerchantCodeFixtures.retiredOf(merchant)`(변경 이력 있는 가맹점 — 업무 조건이 이름에 드러나게),
`MerchantFixtures.beforeContractApproval(code)`·`afterContractApproval(code)`(승인 사실 유무가 이름에 드러남), `MerchantFixtures.registeredBy(actor)`(등록자가 이름에 드러남 — Q-MC-10 결정 고정 시험용). Fixture Monkey는 ID 형식 조합에만, `src/test`에만.

**MC-1 MASTER 모델·등록**
1. **Domain**: `MerchantCode` 형식·예약어(완전·접두), 대문자·공백·하이픈·밑줄 거절(정규화 없음), `Merchant.register`가 seq 1 `REGISTRATION` 하나를 만든다, `currentCode` = seq 최대.
2. **Application(MySQL)**: 등록 → `merchant` + seq 1 같은 TX·이력·Outbox 코드 포함, 같은 코드 두 번째 등록 → 저장된 `MERCHANT_CODE_TAKEN`(reference 없음)·행 +0, 같은 키·다른 코드 409, 같은 바이트 재전송 재생.
3. **DB 경합**: 두 연결이 같은 코드로 다른 가맹점 등록 → SUCCEEDED 1·TAKEN 1(3회), 배정 행 1.
4. **DB 증명(runtime 계정)**: `merchant_code_assignment` UPDATE → 1142, DELETE → 1142, seq 3 INSERT → 3819, seq 1에 `replaces_code` → 3819, 같은 코드 → 1062, 다른 가맹점 코드를 `replaces_code`로 → 1452. `merchant` 권한 불변(V2 그대로) 단언.
5. **migration**(MASTER V12 시점 DB에 Demo 가맹점 Fixture를 넣고 V13·V14 적용): 채우기 값 = 각 행의 `'m' + LEFT(SHA2(merchant_id,256),13)`(시험이 Kotlin으로 독립 계산해 비교 — 결정성, 두 번 만든 새 DB의 값 동일), 모든 값이 §3.1 정규식 통과·`mer_` 문자열 미포함,
   **seq 1 없는 `merchant` 행 수 = 0**(rev 0.3 SIGNAL 대체), Demo Fixture 전체에서 채우기 코드 충돌 0(해시는 의도적으로 충돌을 만들 수 없으므로 실패 경로는 "채우기 INSERT가 `uk_mca_code` 아래에서 실행됨"을 스키마로 단언하고, 같은 코드 행 INSERT 1062는 MC-1-4가 증명 — 충돌 시 migration 실패 = fail-closed), 채우기 행 `source = BACKFILL`, 근거 operation CHECK가 `ChangeMerchantCode`를 받고(대조: 모르는 값 3819) 기존 값 집합 유지, FK 두 개의 `DELETE_RULE`·`UPDATE_RULE` = `NO ACTION`(information_schema).
6. **조회 API(MASTER)**: 목록 항목 키 = 10키(§3.8, `matchedRetiredCode` 없음), 코드 검색 항목 키 = 11키·은퇴 코드 → `matchedRetiredCode: true`(대조군: 현재 코드 → false), 코드 + 다른 필터 → 400, 대문자 400, 상세 세 키. 상세 키 집합에 `registeredBy` 없음(rev 0.3 철회 고정).
7. **ArchUnit**: 배정 Entity의 setter·UPDATE 경로 0, `changeCodeWithEvidence` 외 변경 메서드 0.
8. 변이: (a) `uk_mca_code` 제거 → 3·4 RED, (b) 배정 표 UPDATE 권한 부여 → 4 RED, (c) 소문자 정규화 → 1 RED, (d) 예약어 검사 제거 → 1 RED, (e) 지문에서 코드 제외 → 2(409) RED, (f) 채우기 `RAND()` 또는 ULID 꼬리 → 5 RED, (g) 사전 조회 제거(1062만 의존) → 재시도 수렴 시험 RED(저장된 거절 대신 503),
   (h) `uk_mca_code`를 `StoreConstraint` 표에서 빼기(`OTHER`) → 11 RED, (i) 목록 응답에 `matchedRetiredCode` 포함 → 9 RED, (j) seed 선확인 제거 → 10 RED.
9. **교차 배치 시험(리뷰 MAJOR-1 — MC-1 배포 단위가 ADMIN을 깨지 않음)**: ADMIN `MasterResponseShape` 시험이 **MASTER가 실제로 만든 응답 바이트**(MASTER `MerchantOpsApiSpec`이 golden 파일로 내보내고 ADMIN 시험이 같은 파일을 읽음 — 계약 golden 선례 `entitlement-snapshot-v3*.golden.json`)를 목록·코드 검색·상세 세 가지 모두 해석 성공,
   대조군: 목록 golden에 `matchedRetiredCode`를 넣으면 `DIRECTORY` 해석 실패, 키 하나 빠지면 실패. ADMIN 등록 HTTP 시험(stub MASTER): 본문 `merchantCode` 전달·형식 위반 400 `INVALID_INPUT`(MASTER 미호출).
   **Demo 단계 시험(실제 두 앱 — `MerchantOpsEndToEndSpec` 또는 Demo 절차 자동화)**: ADMIN 로그인 → 가맹점 등록(코드 입력) 성공 → ADMIN 목록·상세 200 → 같은 코드 재등록 → `TAKEN` 문구. 어느 단계도 503·Unknown 0건.
10. **seed 재실행(리뷰 MAJOR-2)**: MC-1 전 지문(`[businessId, displayName]`)으로 seed가 끝난 DB(시험이 V12 시점 seed 결과를 재현 → V13 적용)에서 seed 재실행 → `ContentConflict` 0, 모든 단계 replayed, 그 가맹점 코드 = 채우기 값(`demoshop01` 아님)·배정 행 1, `admin_request` 행 +0.
    대조군: 새 DB에서 seed → `demoshop01`. 기록이 SUCCEEDED가 아닌 단계(예: 대역으로 만든 미완료 기록)는 지금처럼 호출.
11. **1062 분류**(`PersistenceFailuresSpec`): §3.2 표의 키 이름 4개 → 지정 `StoreConstraint`·businessKey true, 분류 뒤 실행기 재판정 결과(TAKEN·ALREADY_CHANGED·APPROVAL_ID_ALREADY_USED)를 MySQL 경합 시험(3·MC-1b-4)이 확인.

**MC-1b 코드 변경 2인 명령**
1. **Domain**(`MerchantCodeChangeSpec`): 계약 전·1회 전·ACTIVE → seq 2(`replaces` = 옛 코드), 판정 순서 고정 표(§3.7.3 7→8→9→10: 예 — 이미 변경 + 계약 승인 둘 다면 `ALREADY_CHANGED`), 예약어 새 코드 → `RESERVED`, 현재 코드와 다른 `expectedCurrent` → `MISMATCH`. Domain에 `"system:"` 문자열 0건.
   **결정 고정(D-MCODE-002 Q-MC-10 — 시험 이름·주석에 결정 ID)**: `MerchantFixtures.registeredBy(B)`의 가맹점을 승인자 B로 변경 → **성공**(seq 2 `assigned_by` = B). `MasterRejection`에 등록자 계열 새 값 0개(enum 집합 단언 — `APPROVER_IS_MERCHANT_REGISTRANT` 부재).
2. **parser·mop1**: 본문 엄격 해석(모르는 필드·`actor`·`registeredBy` 400), `mop1` 독립 벡터 2개(두 앱 같은 hex), 값 한 글자 변경 → 다른 지문.
3. **Application(MySQL — 원자성)**: 성공 → seq 2·`merchant.version` +1·이력 `MerchantCodeChanged`(old·new·reason)·Outbox·근거 EXECUTED 행 같은 TX, 거절 각 code → 근거 REJECTED 행 + 업무 행·이력·Outbox 불변,
   같은 바이트 재전송 → `replayed=true`·행 1, 만료 → `APPROVAL_EXPIRED`(잠금 뒤 훅), 기한 확인 경로는 본문 미호출, 불변식(seq 2 ↔ 근거 EXECUTED 1:1 — `approval_id`로).
   **결정 고정(Q-MC-10)**: `approvedBy` = `merchant.created_by`(≠ `requestedBy`) → EXECUTED·seq 2 1행. 대조군: `approvedBy` = `requestedBy` → 기존 `APPROVAL_SAME_ACTOR`(요청자 ≠ 승인자 규칙은 그대로).
4. **DB 경합(실제 MySQL 두 연결, 각 3회)**: ① 계약 승인(근거 경로) vs 코드 변경 → 결과 집합 {둘 다 성공 ∧ 이력 seq에서 변경이 승인보다 앞} 또는 {승인 성공, 변경 `MERCHANT_CODE_LOCKED`} 둘 중 하나만 — "승인 커밋 뒤 변경 성공" 0회(대역 지연으로 두 순서 모두 강제 재현),
   진 쪽 무효과(배정 행·이력·Outbox +0). ② 같은 가맹점 변경 둘(다른 approvalId) → 1 성공·1 `ALREADY_CHANGED`. ③ 두 가맹점이 같은 새 코드로 → 1 성공·1 `TAKEN`. ④ 등록(코드 X) vs 변경(→ X) → 1 성공·1 `TAKEN`. 교착 0(잠금 순서 merchant 먼저).
5. **은퇴 코드 재사용 불가**: 변경 뒤 옛 코드로 새 가맹점 등록 → `TAKEN`, 다른 가맹점이 옛 코드로 변경 → `TAKEN`, 같은 가맹점이 옛 코드로 되돌리기 → `ALREADY_CHANGED`(1회 소진 — 대조: seq CHECK가 DB에서도 3819).
6. **사후 대사 질의**(Reconciliation — 리뷰 NIT: 벽시계 비교 대신 직렬화 순서): 가맹점마다 `master_change_history`의 `MerchantCodeChanged` 행 `id`가 그 가맹점 대상 계약(`contract.merchant_id`)의 첫 `ContractRevisionApproved` 행 `id`보다 **큰** 경우 = 0건.
   두 쓰기 모두 같은 merchant 행 잠금을 쥔 채 이력을 INSERT하므로(§3.7.3) AUTO_INCREMENT `id` 순서 = 직렬화 순서다(시계 오차·같은 ms 영향 없음). MC-4 이후에는 이력 detail의 `entitlementRevision` 비교로 교차 확인. jOOQ 점검 질의 — 운영 대사 항목, 시험은 위반 순서의 이력 행을 직접 INSERT해 검출 단언.
7. **ADMIN**(`MerchantCodeChangeApprovalServiceSpec`): 요청 advisory 409(계약 승인 뒤·이미 변경), 같은 그룹 미해결(계약 승인 요청 있음) → 409 `DUPLICATE_UNRESOLVED`(대조: `SITE_REVIEW` 미해결 있음 → 201),
   A 단계 `currentCode`·`merchantCodeChangeable` 변화 → `INVALIDATED(TARGET_CHANGED)`·MASTER 미호출, 응답 매핑·회복·응답 유실 행렬 = §9.5 그대로, 감사 detail `from=;to=;reason=`·target_ref = `mer_`(MC-1b — §5.5 배치 경계), 새 행 `target_merchant_id` = `mer_`.
   **결정 고정(Q-MC-10)**: 승인자 = 그 가맹점 등록자(요청자와 다름) → TX1 통과·TOTP 소비·MASTER 1회 호출·EXECUTED(403 `APPROVER_IS_REGISTRANT` 0건), `registrantOf(PinnedMerchantCodeChangeTarget) == null`, pinned JSON 키 집합 = §5.3 목록(등록자 키 없음).
   대조군: 요청자 = 승인자 → 기존 참여자 거절(TOTP 미소비).
8. **미해결 키(`mer_` 기준)**: 코드 변경 실행 뒤 같은 가맹점 `SITE_REVIEW` 새 요청 → 기존 미해결 `SITE_REVIEW`(변경 전 요청)와 **같은 키 → 409**(대조군: 키를 코드로 계산하는 변이에서는 201 — 두 미해결 공존), 기존 `mer_` target_ref 행의 키 값 불변(행 해시 전후 동일). V13 스키마 시험(AdminSchemaSpec): 종류 CHECK, `target_merchant_id` CHECK ①~④ 각각 위반 3819(형식 위반·MERCHANT 아닌 대상에 값·`target_ref` `mer_`와 다른 값·MERCHANT인데 둘 다 코드 형식/NULL), 기존 키 불변.
   **생성자 10종 시험(리뷰 m-3)**: 기존 9종류 + `MERCHANT_CODE_CHANGE` 각각의 요청 경로로 만든 행의 `target_merchant_id` = 요청 merchantId(종류별 1개).
9. **UI(Vitest)**: 버튼 노출 조건(`merchantCodeChangeable`), 대화상자 문구, 거절 문구 매핑(`MISMATCH` = 점검 문구), 승인함 "현재 → 새 코드", 등록자 안내 문구 0건.
10. **ArchUnit**: `changeMerchantCodeWithEvidence` 호출자 = 관리 채널 컨트롤러뿐, 근거 없는 변경 메서드 0, handler 전수 집합 + `merchant-code-changes`.
11. 변이 12종: (a) 승인 사실 조회 제거 → 4①·Domain RED, (b) 승인 사실을 잠금 **전**에 읽기 → 4① RED(대역 지연 순서에서 승인 뒤 변경 성공), (c) seq CHECK 제거 → 5 DB 대조 RED, (d) `ALREADY_CHANGED` 판정 제거 → 4② RED(PK 1062 → 503),
    (e) 미해결 키 `COALESCE` → `target_ref` → 8 RED, (f) pinned `currentCode` 비교 제거 → 7 RED, (g) **등록자 검사 추가**(ADMIN `registrantOf`가 등록자 반환 또는 MASTER Domain 등록자 비교) → 1·3·7 결정 고정 시험 RED, (h) 이력 detail에서 oldCode 제거 → 3 RED,
    (i) 거절을 400으로(저장 안 함) → 3 RED, (j) `DEFECT_REJECTIONS`에서 `MISMATCH` 제거 → 경보 시험 RED, (k) 한 종류 생성자에서 `target_merchant_id` 누락 → 8 RED(CHECK ③은 기존 행 허용 때문에 막지 못함 — 시험이 막는다),
    (l) 계약 승인 경로에서 merchant 잠금을 contract 잠금 뒤로 → 12 RED.
12. **잠금 순서 전제(리뷰 m-6)**: ArchUnit — `Contract.approve`·`approveWithEvidence` 호출자 = `MerchantOnboardingService.approveRevision`뿐, `ContractRepository.update` 호출자 집합 고정. 순서 기록 대역 시험 — 근거·seed 두 승인 경로 모두 `merchants.lock` → `contracts.lock` → `contracts.update` 순서.

**MC-2 ADMIN 미리보기·표시**(ADMIN 응답 모양·등록 본문은 MC-1에서 이미 시험됨 — MC-1-9)
1. Vitest 미리보기(속성 시험 — 항상 §3.1 형식, 혼동 문자 없음, 6~14자). 2. ADMIN 사전 거절(형식 400·감사 없음은 기존 등록 규칙대로)·`TAKEN` 문구·감사 detail에 코드(MC-1에서 본문 전달이 생기므로 감사 detail은 MC-1에 포함해도 됨 — 가역). 3. 화면 `mer_` 0건(DOM 스냅샷 — `MerchantRegistrationScreen.tsx:31` KDoc의 "가맹점 ID는 그대로 보인다" 문장 갱신), 코드 검색 정확 일치. 4. 변이: 미리보기 랜덤 소스 `Math.random` → 1 RED(소스 검사).
   (rev 0.4.3 — C-28) 3의 "화면 `mer_` 0건"은 MC-2 범위(표시 텍스트·접근 이름·목록·상세·결과·검토 화면)에 적용한다. 등록 마법사는 DOM 전체(텍스트·모든 속성·입력 value)에서 `mer_`가 나오는 곳이 Site·계약 초안 "가맹점 ID" 두 입력칸의 value·placeholder와 그 형식 오류 문구뿐임을 나열 단언(목록 밖 = RED), 조회 화면은 DOM 전체 0건.

**MC-2b 마법사 가맹점 입력칸**(C-28 — 방식 미결, 사용자 화면 검토 때 결정)
1. 등록 마법사 DOM 전체(텍스트·모든 속성·입력 value·placeholder·오류 문구)에서 `mer_` 0건(MC-2의 허용 목록 제거). 2. 저장 본문 `merchantId`·`scopeMerchantId`는 내부 키 그대로(API 불변 — 기존 본문 단언 유지).
3. (가) 코드 입력: 형식 위반 400 전 화면 오류, 코드 검색 0건 → "없는 가맹점 코드"·저장 호출 0, 1건 → 이름·코드 확인 표시 후 저장, 은퇴 코드 일치(`matchedRetiredCode`)면 현재 코드로 확인 표시. 검색 실패(503 등) → 저장 호출 0.
   (나) 자동 선택: 앞 카드 성공 가맹점이 선택되어 칸 없음, 잠김·완료 카드는 바꾸지 않음(기존 리뷰 m-1 규칙). 4. 결과 불명 재전송은 처음 해석한 내부 키 그대로(같은 body). 5. 변이: 저장 본문에 코드를 그대로 넣기 → 2 RED, 해석 0건에도 저장 → 3 RED.

**MC-3 감사·승인 대상 표기 전환**
1. migration V15 CHECK(`mer_`·코드 둘 다 허용, 그 밖 3819). 2. 새 target_ref = 코드, 기존 행 해시 불변(전환 전후 전 행 `SHA2(CONCAT_WS…)` 비교). 3. 감사 target_ref 코드·대조군(사업자·계약 대상 불변), 코드 변경 승인 건은 요청 시점(옛) 코드. 4. 연결 조회: 옛 코드·새 코드·`mer_` 어느 것으로 찾아도 같은 행 집합. 5. MASTER 본문·`mop1` 불변(기존 벡터 그대로).
6. `TARGET_REF` 정규식: 코드와 `xxx_<ULID>`·ACQ가 서로 오인되지 않음(표 시험). 7. 변이: (a) 감사 target_ref 되돌림 → 3 RED, (b) 연결 조회가 은퇴 코드 제외 → 4 RED.

**MC-4 PAYMENT 투영·외부 필드**
1. Snapshot v4 golden(두 앱 + `harness/tests/test_entitlement_golden.py`)·재발행·같은 revision 다른 코드 → `INTEGRITY_CONFLICT`. 2. 웹훅·응답 필드·생략·`mer_`/`biz_` 0건, 요청 본문 `merchantCode` → 400(출력 전용). 3. Simulator 0건.
4. 변경 명령 → revision +1·Snapshot 발행(같은 TX), MID 있는 가맹점에서는 변경이 MASTER에서 이미 `LOCKED`(대조: MID 발급 Fixture는 반드시 승인 사실을 가짐 — Fixture 생성기가 위반 조합을 만들지 못함 단언).
5. **승격(리뷰 m-4)**: 최근 issue v3인 가맹점의 lease 갱신 → v4 승격 발행 = `entitlement_revision + 1`·HIGH, **같은 revision의 v4 발행 0건**(issue 표 `(merchant, revision)`별 schema_version 하나 단언), PAYMENT가 그 승격을 APPLIED(INTEGRITY_CONFLICT 0 — `SnapshotApplicationRule.kt:40-42` 대조: 같은 revision·v4 내용을 직접 넣으면 INTEGRITY_CONFLICT로 head 막힘을 재현).
   `SchemaRolloutOrderV4Spec`: 역순(PAYMENT v4 미지원) = head 막힘 확인, 정순 = 승격 APPLIED(V3 선례 `SchemaRolloutOrderV3Spec`).
6. 변이: 웹훅 코드 누락 → 2 RED, 변경 명령의 revision +1 제거 → 4 RED, 승격을 같은 revision 재발행으로 → 5 RED.

## 9. 8대 원칙

| 원칙 | 의미 | 증거 |
|---|---|---|
| Financial Consistency | 금액·권리 의미 변화 없음. 외부에 나간 코드는 불변(MID ⊂ 계약 승인 뒤) | MC-4-4, MC-1b-4 |
| Transaction Safety | 배정 행·merchant version·이력·Outbox·근거 행(·Snapshot)이 MASTER 로컬 TX 하나, 거절 시 업무 행 +0. ADMIN 참여자 거절은 TX1 TOTP 전. MASTER 호출은 DB TX 밖(§9.5 2-TX) | MC-1-2, MC-1b-3·7 |
| Idempotency | 등록 지문에 코드, 변경은 `mop1.<approvalId>` 하나 = 명령 하나, 재전송 재생 | MC-1-2, MC-1b-3 |
| Concurrency Control | merchant 잠금으로 계약 승인·변경 직렬화(READ COMMITTED 잠금 뒤 읽기 — 승인 사실 쓰기 경로의 잠금 순서를 시험으로 고정), UNIQUE(code)·PK(seq) DB 방어선 | MC-1-3, MC-1b-4·12 |
| Recoverability | 응답 유실 → 결정 조회·기한 확인(§9.5 그대로), 1062 → 새 키별 분류 후 새 TX 재판정으로 저장된 거절 수렴, migration fail-closed(해시 충돌), 기존 seed DB 재실행 충돌 없음, 배포 경계 재전송은 Unknown → 조회 확인(L-3) | MC-1b-3·7, MC-1-5·10·11 |
| Reconciliation | 코드 ↔ `mer_` 대응이 배정 행으로 영구, seq 2 ↔ 근거 행 1:1, "승인 뒤 변경" 사후 대사 질의 0건 | MC-1b-3·6, MC-3-4 |
| Auditability | 은퇴 코드 영구 점유(감사 코드 → 가맹점 하나), 변경 old→new가 MASTER 배정 행·이력·ADMIN 감사 detail 세 곳, 기존 감사 행 수정 0. 2인 통제 = 요청자 ≠ 승인자(Q-MC-10 — 등록자 승인 허용도 시험으로 고정해 규칙이 몰래 바뀌지 않게) | MC-1-4, MC-1b-1·3·5·7, MC-3-2 |
| Observability | `approval_rejected{operation=ChangeMerchantCode, rejection}`, `MISMATCH` 경보(ADMIN `DEFECT_REJECTIONS`), PAYMENT `merchant_code_changed_with_mid` 경보. 코드 값은 label·로그에 넣지 않는다(비밀은 아니나 카디널리티) | MC-1b-7·11(j) |

**기술 연결(이번 변경에 한함)**
- **JPA**: 배정 표는 `@Immutable` Entity + 모든 열 `updatable = false` — 하는 일: 등록·변경 TX가 새 행을 `persist` 하고 즉시 `flush`해 1062를 `StoreConstraint(businessKey = true)`로 번역(실행기 재시도 경로).
  예: 같은 코드 경합의 진 쪽은 flush에서 1062 → TX rollback → 새 TX에서 사전 조회가 찾아 `TAKEN` 저장. 주의: `Merchant` Entity에 컬렉션 매핑을 두면 `merchant` 갱신 때 자식 재저장 위험이 있어 Repository가 따로 읽어 Mapper로 조립한다(§3.7.4 — `JpaContractRepository` 방식 확인됨).
  잠금은 기존 `PessimisticRowLock`(refresh `PESSIMISTIC_WRITE`) — 판정 입력은 잠금 **뒤** 다시 읽은 값. ADMIN `admin_approval_request`의 `target_merchant_id`는 Entity에서 `updatable = false`(표 단위 INSERT, 결정 열만 UPDATE 권한 — §1.1).
- **jOOQ(CQRS)**: 목록·상세·검색의 현재 코드(seq 최대 상관 부분 질의)·은퇴 코드 검색·코드 이력·사후 대사 질의, ADMIN 미해결 목록 `COALESCE`. 명령 판정 입력(승인 사실 존재)은 JPA Repository(명령 TX 안 — 조회 모델이 아님).
- **Kafka/Redis**: 도입하지 않는다. 코드 유일성·1회 변경은 소유 DB UNIQUE·PK·CHECK, 직렬화는 merchant 행 잠금. 분산 lock·캐시는 영구 예약을 대신할 수 없다.

## 10. Coordinator 결정(가역)
C-1 최소 6자·첫 글자 영문 / C-2 예약어 코드 상수 / C-3 혼동 문자 제외 / C-4 사전 조회 없음(변경 승인 A 단계도) / C-5 영문 상호 보조칸 / C-6 seed `demoshop01` / C-7 기존 operation `mop1` 불변 / C-8 정산서 경로는 SETTLEMENT-001 /
**C-9(rev 0.2)** 저장 = append-only 배정 이력 표 (b), `merchant`에 코드 열 없음(§3.2) / **C-10** `approval_id` FK 없이 UNIQUE + 1:1 불변식 시험 / **C-11** 근거 없는 변경 경로 없음(seed 관문 대신 메서드 부재 + ArchUnit) /
**C-12** 변경 사유 고정 목록(Demo: `TYPO_CORRECTION`·`MERCHANT_REQUEST`·`OTHER_POLICY` — Demo 값) / **C-13** 채우기 가맹점도 계약 승인 전이면 1회 변경 가능 / **C-14** "첫 계약 승인" = 그 가맹점 대상 계약의 승인 사실 행 존재(§3.7.1) /
**C-15** 변경 종류는 `MERCHANT:<mer_>` 그룹 공유, 미해결 키는 `COALESCE(target_merchant_id, target_ref)`(§5.2) / **C-16** 배치 순서 MC-1 → MC-2 → MC-1b → MC-3 → MC-4, 미해결 키 변경은 MC-1b로 당김 /
**C-17** 변경 행위자·`assigned_by` = 승인자(MERCHANT-OPS-002 C-17과 같음) / **C-18** 은퇴·사용 중 코드를 거절 code로 구별하지 않음(`TAKEN` 하나) / **C-19** 변경 외 종류의 pinned 코드는 표시용(비교 제외) /
**C-20(rev 0.3)** MASTER 근거 operation CHECK `ChangeMerchantCode`를 MC-1 V13에 선반영(MERCHANT-OPS-002 C-42 선례 — MC-1b MASTER migration 없음, 대안: MC-1b에서 V15·V16) /
**C-21(rev 0.3)** MC-1b 동안 ADMIN 승인·감사 `target_ref` = `mer_`, 코드 표기는 MC-3부터(ADMIN CHECK·Domain 정규식 순서 — §5.5) /
**C-22(rev 0.4 — Coordinator 결정, 리뷰 MAJOR-1)** MC-1 = MASTER 모델 + ADMIN 응답 모양·등록 본문·최소 코드 입력칸의 한 배포 단위(미리보기·표시 교체·코드 검색 화면은 MC-2 — Implementer 선택), 목록/코드 검색 응답 모양 분리(§3.8) /
**C-23(rev 0.4 — Coordinator 결정 (a), 리뷰 MAJOR-2)** seed는 단계 호출 전 그 키의 SUCCEEDED 기록을 확인해 재사용(일반 규칙, 지문 버전 관리 없음) /
**C-24(rev 0.4 — 리뷰 m-1)** V13 `SIGNAL` 삭제, seq 1 존재는 migration 시험, 채우기 INSERT 열 목록 명시 /
**C-25(rev 0.4 — 리뷰 m-2·m-5)** `target_merchant_id` CHECK ①~④, 새 키별 `StoreConstraint` 분류(§3.2 표) /
**C-26(rev 0.4 — Coordinator 결정, 리뷰 m-7)** 채우기 코드 = `'m'` + `SHA2(merchant_id,256)` 소문자 16진 앞 13자(ULID 꼬리 아님 — 충돌은 migration 실패) /
**C-27(rev 0.4.1 — Coordinator 결정, 구현 질문 Q-MC1-1)** C-23 선확인에서 T0를 싣는 단계 제외(기존 시험 DEMO-SEED-001 I-7 기대 유지 — 대안 "I-7 기대 변경"은 기존 기대 완화라 기각). Demo seed 한정·금융 효과 없음·가역.
**C-28(rev 0.4.3 — Coordinator 결정, MC-2 리뷰 MAJOR-1)** MC-2-3 "화면 `mer_` 0건"을 둘로 나눈다: **MC-2** = 표시 텍스트·접근 이름·목록·상세·결과·검토 화면, **MC-2b** = 등록 마법사 Site·계약 카드의 가맹점 입력칸을 코드 입력 + ADMIN 코드 검색으로 내부 키 해석·이름/코드 확인 표시(또는 앞 카드 결과 자동 선택으로 칸 숨김). 방식은 사용자 화면 검토 때 결정(D-ADMIN-UI-004 ③ 완결 작업, §7·§8 MC-2b). 해석 축소가 아니라 범위 분리 — MC-2의 시험은 남는 자리를 목록으로 고정한다.

## 11. 사용자 질문(모두 답변됨 — PENDING 없음)

| ID | 답 | 결정 | 적용 |
|---|---|---|---|
| Q-MC-1 | 외부 연동·정산서에도 노출(권장 반대) | D-MCODE-001 | §6·MC-4 |
| Q-MC-2·Q-MC-5 | 토스식 — 가맹점 코드, 영문 소문자+숫자 14자 이내 | D-MCODE-001 | §3.1 |
| Q-MC-3 | 전체에서 하나, Demo도 같은 형식(권장) | D-MCODE-001 | §3.2 |
| Q-MC-4 | 감사·승인 대상도 새 코드로(권장 반대) | D-MCODE-001 | §5·MC-3 |
| Q-MC-6 | 미리보기(상호 영문 + 랜덤 4자) + 직접 입력 | D-MCODE-001 | §3.4·MC-2 |
| Q-MC-7 | 계약 승인 전 1회 변경(권장 반대) | D-MCODE-002 | §3.2·§3.7·§5.5·MC-1b |
| Q-MC-8 | 출력 전용(권장) | D-MCODE-002 | §6·MC-4 |
| Q-MC-9 | 고치지 않고 연결(권장) | D-MCODE-002 | §5.1·§5.4·MC-3 |
| **Q-MC-10** | **요청자≠승인자만(권장 반대 — 권장은 원 가맹점 등록자 승인 금지), 2026-11-14** | D-MCODE-002 | §2·§3.7.3·§3.8·§5.3·§5.5, 결정 고정 시험 MC-1b-1·3·7, 변이 MC-1b-11(g). D-MOPS-009 ③·D-MOPS-010 ② 확장 없음 |

새 질문 없음(rev 0.3 저장소 대조에서 금융·보안·키 동일성 미결을 새로 찾지 못함 — §5.5 target_ref 배치 경계는 기존 결정 Q-MC-4의 배포 순서 문제라 가역 C-21로 기록).

## 12. 위험
- L-1 Demo·운영 DB 간 중복 불가피(별도 DB). L-2 채우기 행은 이력 이벤트 없음(`source = BACKFILL`로 구별). L-3(rev 0.4 정정) 배포 경계 재전송: MC-1 배포 전 ADMIN 등록을 배포 뒤 재전송하면 MASTER 400 → ADMIN `Unknown`(`HttpMasterOpsGateway.kt:89`, rev 0.3의 "409"는 틀림) — Demo 한정, 운영자가 가맹점 조회로 확인. MASTER·ADMIN 한쪽만 배포된 상태는 지원하지 않는다(C-22). L-4 ADMIN CHECK가 `mer_`를 계속 허용 — 새 행 코드 강제는 앱·시험. L-5 코드는 공개값, 인증 근거 아님.
- **L-6** "첫 계약 승인 전" 조건(R4)은 DB 제약이 아니라 merchant 잠금 아래 Domain 판정이다. 완화: 잠금 순서 공유(계약 승인도 merchant 먼저)·경합 시험·사후 대사 질의(MC-1b-6). 키 유출로 MASTER에 직접 변경을 보내도 판정 6·9가 막는다(D-MOPS-005 위험은 근거 형식까지만 — 업무 판정은 MASTER 권위).
- **L-7** 은퇴 코드가 쌓여 코드 공간을 소모한다(가맹점당 최대 2개 — 14자 공간에서 무시 가능, 단 인기 상호 코드는 오타 변경으로 영구 소진).
- **L-8** 변경 승인과 계약 승인 요청이 같은 미해결 그룹 → 변경 대기 중에는 계약 승인 요청을 낼 수 없다(의도된 직렬화 — 화면 안내 "코드 변경 승인 대기 중").
- **L-9** 변경 직후 짧은 시간 ADMIN 화면 캐시·열린 탭이 옛 코드를 보일 수 있다(표시만 — 명령은 `mer_`로 가므로 오작동 없음, 상세 재조회로 갱신).
- **L-10(rev 0.3)** Q-MC-10 답에 따라 가맹점을 등록한 사람이 다른 OPERATOR의 코드 변경 요청을 승인할 수 있다(자기가 정한 코드를 바꾸는 승인). 통제는 요청자 ≠ 승인자·직전 TOTP·감사 old→new·1회 한도·계약 승인 전 한정이며, 사용자가 이 범위를 선택했다(D-MCODE-002). 등록자 승인 건은 감사(`APPROVAL_*` 행위자 vs MASTER 상세 등록자)로 사후 조회 가능.

## 13. 기록
| rev | 내용 |
|---|---|
| 0.1 | 초안. Q-MC-7~9 PENDING, C-1~C-8 권장 |
| 0.1a | 사용자 답(D-MCODE-002): Q-MC-7 = 계약 승인 전 1회 변경(권장 반대), Q-MC-8 = 출력 전용, Q-MC-9 = 고치지 않고 연결 |
| 0.2 | D-MCODE-002 반영 재설계: 저장 (a) 열 권한 vs (b) append-only 배정 이력 비교 → (b) 권장(C-9 — rev 0.1a의 `merchant_code_retired` 표·`merchant.merchant_code` 열·V14 열 권한 전환 **철회**), 변경 명령 §3.7(MASTER `ChangeMerchantCode` 근거 경로·판정 순서·경합·"첫 계약 승인" 정의 C-14), ADMIN `MERCHANT_CODE_CHANGE` 종류·pinned·A 단계(§5.5), 미해결 키 `mer_` 기준 `COALESCE`(§5.2 — rev 0.1 사전 조건 SIGNAL·pinned 불일치 INTEGRITY 철회), 외부 노출 불변 논증(§6), 배치 MC-1b 추가·순서 C-16, 시험 §8 재작성, PAYMENT migration 번호 정정(V33 → V35), C-9~C-19, Q-MC-10 신규 |
| 0.3 | **Q-MC-10 답 적용(D-MCODE-002 "요청자≠승인자만", 권장 반대)**: MASTER 판정 8(원 가맹점 등록자)·거절 `APPROVER_IS_MERCHANT_REGISTRANT`·pinned `merchantRegisteredBy`·상세 `registeredBy`·ADMIN TX1 등록자 403·화면 등록자 안내·변이 (g) 삭제 → 판정 번호 재정렬(8~12), 결정 고정 시험(MC-1b-1·3·7 — 등록자 승인 성공, `registrantOf` null, enum·pinned 키 집합)·역변이 (g) 추가, D-MOPS-009 ③ 비확장 명시, L-10. MC-1b 막힘 해제. **저장소 대조(HEAD `7a50459`)**: migration 번호 재확인(MASTER V13·V14 — 근거 CHECK 선반영 C-20, ADMIN MC-1b V13(V14 비움)·MC-3 V15, PAYMENT V35 권한 불필요), MC-1b `target_ref`는 ADMIN CHECK `^mer_` 때문에 MC-3 전까지 `mer_`(C-21 — rev 0.2 정정), 참조 정정(`MasterIdFormat.kt:3-18` = 형식·발급은 `work.newId` L125, `DemoMerchantSeedValues.kt:33-37`, `DemoMerchantSeedService.kt:83-87·126-133`, `V1:38-54`, `Contract.kt:16-33`·`96-99`, `ContractRepository.kt:6-17`, ADMIN `V2:22-24`·`V11:40-61`·`V11:28-33`, `MasterRejection.kt:80-91`, `MerchantOpsApprovalService.kt:600-607`, `MerchantOutcomes.kt:40-43`), `JpaContractRepository` 매핑 방식 확인(§3.7.4 "확인 후" 확정), §3.7.2 쓰기 permit 근거의 잘못된 "C-1" 표기 정정 |
| 0.4 | rev 0.3 독립 리뷰 **REVIEW_FAIL** 반영(정책 변경 없음). **MAJOR-1**: ADMIN 응답 모양 키 집합 정확 일치(`MasterResponseShape.kt:18`)·등록 400 → Unknown(`HttpMasterOpsGateway.kt:89`) 때문에 MC-1과 MC-2 사이에서 ADMIN이 깨짐 → MC-1을 MASTER + ADMIN 모양·등록 본문·코드 입력칸의 한 배포 단위로(C-22), 목록/코드 검색 응답 모양 분리(`matchedRetiredCode`는 코드 검색만), 교차 배치 golden·Demo 단계 시험 MC-1-9. **MAJOR-2**: seed 재실행이 기록 재생한다는 서술은 틀림(지문 비교 `AdminCommandExecutor.kt:204` → `ContentConflict` `DemoMerchantSeedService.kt:134`) → seed 성공 기록 선확인(C-23), 시험 MC-1-10, L-3 정정(409 → MASTER 400 → Unknown). m-1 SIGNAL 삭제·열 목록(C-24), m-2 `target_merchant_id` CHECK ①~④, m-3 생성자 10종 시험, m-4 v4 승격 = revision +1·PAYMENT 먼저 배포·같은 revision v4 발행 금지 시험(MASTER-SNAPSHOT-002 §5.2·§5.4, AUTHN-001 §9.2, `SnapshotApplicationRule.kt:40-42`), m-5 1062 분류 표(C-25), m-6 승인 사실 쓰기 경로 잠금 순서 고정 시험 MC-1b-12, m-7 해시 채우기(C-26). NIT: FK 동작 없음(NO ACTION) 명시, 사후 대사를 이력 `id` 순서로. 새 §1.5 |
| 0.4-impl MC-1 | **구현 기록(Implementer, 2026-11-14 — 명세 rev 0.4 sha256 `8b2eb4d6…10b1b`, 기준 HEAD `7a50459`; 자기 완료 판정 아님 — Verifier·Reviewer 별도, 정책 변경 없음).** MASTER **V13**(`merchant_code_assignment` + 해시 채우기 + 근거 operation CHECK `ChangeMerchantCode` — C-20)·**V14**(SELECT, INSERT). Domain `MerchantCode`(형식만 — 예약어는 `requireAssignable()` 업무 거절)·`MerchantCodeReservedWords`·`MerchantCodeAssignment`·`MerchantCodeSource`, `Merchant` 배정 목록·`currentCode`(seq 1 없음 = IllegalArgument). JPA `JpaMerchantCodeAssignmentEntity`(`@Immutable`, 모든 열 `updatable = false`, setter는 **protected** — Kotlin JPA 클래스가 open이라 private setter 불가, 호출자 0은 ArchUnit MC-1-7), `JpaMerchantRepository`가 따로 읽어 조립, `MerchantRepository.findCodeOwner`(명령 TX 사전 조회). `JpaEntitlementSourceStore`는 Merchant를 Repository `find`로 조립(Snapshot 내용 불변 — MC-4 전 코드 없음). 1062 분류 4키(§3.2 표). 조회: 목록 10키·코드 검색 11키(요청 기준 `byCode`)·상세 3키, jOOQ 상관 부분 질의 `MasterSqlText.currentMerchantCode`. 검색 parser: `merchantCode`는 단일 기준 — **`limit`만 함께 허용**(다른 필터·`after` 400 — 가역 구현 결정). seed C-23: **구현 변형(질문 Q-MC1-1)** — T0를 싣는 단계(`carriesEffectiveFrom`)는 선확인 없이 호출(DEMO-SEED-001 I-7 "같은 키 공간·다른 T0 = ContentConflict" 기존 시험 유지), 그 밖 단계는 SUCCEEDED 기록 재사용. MC-1-5 "V12 시점"은 Flyway target 11(V12 빈 번호). MC-1-10 "MC-1 전 seed DB"는 공유 시험 DB 안에서 재현(요청 지문을 MC-1 전 입력 지문으로, 배정 행을 V13 채우기 행으로 — root). ADMIN: `MasterResponseShape` DIRECTORY(+merchantCode)·DIRECTORY_BY_CODE(새, 최대 1)·DETAIL(+3키), 등록 본문·입력 규칙·MASTER 명령 필드 `merchantCode`, 화면 가맹점 카드 "가맹점 코드" 직접 입력(형식 안내·오류), 거절 문구 TAKEN·RESERVED. golden `merchant-ops-response-shapes-v1.golden.json`(MASTER 생성기 바이트 = golden, ADMIN 사본 바이트 동일 단언 — harness 교차 시험 추가는 허용 경로 밖이라 미수행). **미수행**: 실제 두 앱 Demo 단계 시험(MC-1-9 후반 — 두 앱 E2E 하네스 없음), MC-1b·MC-2·MC-3·MC-4. 증거: `~/gate-logs/mcode-mc1/`. |
| 0.4.1 | Coordinator 결정 C-27(구현 질문 Q-MC1-1): C-23 seed 선확인에서 T0를 싣는 단계 제외 — DEMO-SEED-001 I-7 기존 기대 유지, MC-1-10 그대로. 정책 변경 없음 |
| 0.4.1-impl 수정 | **MC-1 수정 라운드(Implementer, 2026-11-14 — 명세 rev 0.4.1 sha256 `3abdcab5…`, 자기 완료 판정 아님).** A. 모듈 check 실패 4건 = 목록 증가만: `MasterApiContextSmokeSpec`(migration 13·14, Entity `JpaMerchantCodeAssignmentEntity`), `MasterApiMainMigrationsBootSpec`(13·14), `MasterDatabaseAccountSpec`(`merchant_code_assignment` SELECT, INSERT). B. m-1 RESERVED 문구를 새 submissionId 재제출로 단언, m-2 MC-1-3 경합을 장애 주입 대역(새 지점 `MERCHANT_CODE_OWNER`·`MERCHANT_INSERT`, CyclicBarrier)으로 결정화 — 두 TX 모두 사전 조회 빈 결과 통과 → 정확히 한 번 1062(`StoreConstraint.MERCHANT_CODE`) → TAKEN 단언, 변이 (h)·(g) 재확인, m-3 seed 재실행의 Use Case 호출을 멱등 예약 횟수로 계수(비성공 기록 단계 01 = 1회 재실행, SUCCEEDED 단계 0회·T0 단계 04·11·12·15·16만 1회 — C-27), n-1 golden 시험 KDoc(압축 JSON 문자열 비교·별도 mapper 객체), n-2 C-27 표기·등록 화면 KDoc, n-3 완전 수식 이름 → import, n-4 `Merchant` 배정 불변식 위반 = IllegalState(check — §3.2 결함 신호). |
| 0.4.1-demo MC-1 | **MC-1 Demo E2E·커밋 기록(Implementer가 Coordinator 기록을 옮김, 2026-11-14 — 이력 행 0.4-impl·0.4.1-impl은 고치지 않는다).** Coordinator가 실제 Demo 두 앱(ADMIN → MASTER)에서 MC-1-9 Demo 단계 시험을 수행: **2026-11-14 11:58 KST**, 게이트 fingerprint `39569556…`, **10단계 PASS**(로그인·사업자 등록·코드 입력 가맹점 등록·같은 submissionId 재전송 replayed·목록·상세·TAKEN·RESERVED·형식 위반 400·seed 목록 코드, 503·Unknown 0건), 증거 `~/gate-logs/mcode-mc1/demo`(절차 `mc1-demo-e2e.py`·결과 `e2e-result.json`). 화면 단계는 같은 ADMIN HTTP API 호출로 대체(브라우저 조작 없음). 정정 기록: 0.4-impl 행의 "seq 1 없음 = IllegalArgument"는 0.4.1-impl 수정 n-4로 **IllegalState**(`check` — §3.2 결함 신호)가 되었다. **MC-1 커밋 `c71a49a`**(main). |
| 0.4.2-impl MC-2 | **MC-2 구현 기록(Implementer, 2026-11-14 — 명세 rev 0.4.1 기준, 기준 HEAD `c71a49a`; 자기 완료 판정 아님 — Verifier·Reviewer 별도, 정책 변경 없음, ADMIN만 — MASTER·PAYMENT·FINANCIAL main 변경 0, ADMIN migration 없음).** ADMIN 코드 검색: 화면 검색 parser `merchantCode`(JSON 문자열만), Use Case 형식 §3.1(정규화 없음)·**단일 기준 — `limit`만 함께 허용**(MASTER `MerchantSearchRequestParser`와 같음, 다른 필터·cursor·등록번호와 함께면 MASTER 호출 전 400; 등록번호와 함께면 기존 등록번호 감사 경로 INVALID_INPUT), 감사 없음(목록 검색과 같음 — D-MOPS-002), gateway가 **보낸 기준**으로 `DIRECTORY_BY_CODE`를 고름(MC-1에서 사용처 없던 모양의 첫 사용처). 감사 detail: 가맹점 등록 `code=<코드>`(MC-2-2 — 공개값, 형식이 맞을 때만). 화면: 미리보기 라이브러리 `lib/merchantCode.ts`(§3.4 — 영문·숫자 소문자화 → 첫 영문까지 자름 → 최대 10자 + 랜덤 4자, 6자 미만 랜덤 보충, 상호에 영문이 없으면 첫 글자도 랜덤 영문, C-3 31자, `crypto.getRandomValues` 거부 표본), 등록 화면 "영문 상호(코드 제안용)"(저장 안 함)·상호를 고치면 같은 꼬리로 다시 제안(운영자가 코드를 고쳤으면 덮어쓰지 않음)·"다시 제안"(꼬리 새로)·직접 수정값 그대로 전송, 결과·검토·Site 행 표시 = 코드. 가맹점 조회: 목록·상세 제목·기본정보·이력 대상(그 가맹점 자신) 표시 = 코드, 목록 행 접근 이름 = 코드, **"가맹점 ID" 검색란 → "가맹점 코드"**(가역 구현 결정 — D-ADMIN-UI-004 ③ 방향, merchantId 검색 API는 서버에 그대로). 은퇴 코드 표시는 MC-1b 뒤(필드는 받음). **미수행·한계**: 등록 마법사 Site·계약 초안 카드의 "가맹점 ID" 입력란(내부 키 입력·자동 채움)은 그대로 — 화면 텍스트에는 mer_ 0건이지만 입력값에는 남는다(코드 입력으로 바꾸려면 코드 → 내부 키 해석 조회 필요, Coordinator 판단 대상). 승인함 pinned 표시의 mer_는 MC-3(§5.3). 재리뷰 NIT: master-api `MerchantOpsShapeGoldenSpec` 시험 이름 "compact JSON equals", 등록 화면 RESERVED 재제출 뒤 TAKEN 문구 부재 단언. 변이(§8 MC-2-4) (a) 랜덤 소스 `Math.random` → 소스 검사 RED, 추가 (b)~(e). 두 앱 Demo E2E 절차를 저장소 `harness/e2e/merchant_code_e2e.py`로 이관(+ MC-2 코드 검색 단계, 정적 시험 `harness/tests/test_merchant_code_e2e_script.py` 8개 — HARNESS-SELFTEST minTests 184 → 192). 증거: `~/gate-logs/mcode-mc2/`. |
| 0.4.2 | §13 정리(0.4.1 결정 행을 그 구현 행 앞으로, MC-1 Demo E2E·커밋 기록 행) + MC-2 구현 기록. 본문 규칙·정책 변경 없음 |
| 0.4.3 | **C-28(Coordinator 결정)** MC-2-3을 MC-2(표시 텍스트·접근 이름·목록·상세·결과·검토)와 MC-2b(마법사 가맹점 입력칸 — 방식은 사용자 화면 검토 때)로 분리, §7·§8 MC-2b 행. **MC-2 구현 기록(수정 라운드, Implementer — MC-2 리뷰 REVIEW_FAIL 반영, 자기 완료 판정 아님): MC-2-3 부분 충족, 나머지는 MC-2b.** MAJOR-1 등록 화면 시험을 DOM 전체(텍스트·모든 속성·입력 value) 기준으로 — `mer_`가 나오는 곳이 Site·계약 초안 "가맹점 ID" 두 입력칸의 value·placeholder·형식 오류 문구뿐임을 상태별로 나열 단언(목록 밖 RED), 조회 화면은 DOM 전체(innerHTML·모든 속성·입력 value) 0건. MINOR-1 거부 표본 경계 시험(23자 자리 [253,254,255,k], 31자 자리 [248..255,k], 경계 바로 아래 252·247 수용) + 변이 (f) `byte % n`. MINOR-2 E2E 정적 시험: `demo_opener` — 환경 프록시가 있어도 ProxyHandler 0개(urllib은 빈 `ProxyHandler({})`를 등록하지 않고 대신 기본 환경 프록시 handler를 만들지 않음)·`_NoRedirect` 있음·기본 redirect handler 없음, `Session` 기본 opener = `demo_opener`, loopback 302 대역 → 상태 302 기록(따라가지 않음). MINOR-3 로그인 뒤 예외 → verdict INCOMPLETE(그때까지의 steps·알게 된 값 결과 파일에 기록, exit 1), 판정 함수는 객체가 아닌 응답·항목을 어긋남으로. NIT-1 꼬리 첫 글자(영문 23자)는 앞부분이 빈 경우에만(§3.4 한 줄), NIT-2 `loopback_base_url`이 urlunsplit 정규 값 반환·공백/제어 문자·IPv6 zone 거부(표 시험), NIT-3 코드 검색 `nextCursor` null 단언. HARNESS-SELFTEST minTests 192 → 198. 증거 `~/gate-logs/mcode-mc2/`(r2-*). |
| 0.4.4-impl MC-1b | **MC-1b 구현 기록(Implementer, 2026-11-14 — 명세 rev 0.4.3 기준, 기준 HEAD `150868a`; 자기 완료 판정 아님 — Verifier·Reviewer 별도, 정책 변경 없음, 금융 의미 변화 없음).** **MASTER(migration 없음 — §1.4)**: Domain `Merchant.changeCodeWithEvidence`(판정 7→8→9→10→11 + 자기 배정 코드 = `TAKEN`; `approver`는 `assigned_by` 기록용 — 등록자 비교 없음 Q-MC-10), `MerchantCodeAssignment.change`(internal), `MerchantCodeChangeReason`(C-12 Demo 3값), 거절 `MERCHANT_CODE_ALREADY_CHANGED`·`MERCHANT_CODE_LOCKED`·`MERCHANT_CODE_MISMATCH`(끝에 추가 — 등록자 계열 새 값 0). Application `AdminOperation.CHANGE_MERCHANT_CODE`, `ApprovedMerchantCodeChangeCommand`, `MerchantOnboardingUseCase.changeMerchantCodeWithEvidence`(근거 1~3 → find → **merchant 잠금** → lockedNow(만료 훅) → 잠금 뒤 `ContractRepository.hasApprovedRevisionFor`(JPQL count) → Domain → `findCodeOwner` → seq 2 + merchant version +1(`PESSIMISTIC_FORCE_INCREMENT` — 자식 행만 늘어도 version 증가) → 이력·Outbox `MerchantCodeChanged{merchantId, oldCode, newCode, reasonCode, approvalId}` → 근거 EXECUTED), `JpaMerchantRepository.update`가 배정 행 append(저장 행 접두 일치 검사), 경보 후보 거절에 `MISMATCH`. Web `POST …/merchant-code-changes`(`MerchantApprovalController` — 쓰기 permit, 유일한 호출자), parser `merchantCodeChange`(기한 확인 parser 목록에 추가), handler 전수 18 → 19. 사후 대사 질의(MC-1b-6) `MerchantCodeChangeAuditReader`/`JooqMerchantCodeChangeAuditReader` + `MerchantCodeReconciliationQuery`(이력 `id` 순서, 운영 노출 경로 없음 — 조회 Use Case만). **ADMIN V13**(V14 비움): 종류·대상·소유 상태 CHECK에 `MERCHANT_CODE_CHANGE`, 열 `target_merchant_id` + CHECK ①~④, `unresolved_key` 재작성(가맹점 대상 `COALESCE(target_merchant_id, target_ref)` — 기존 행 키 불변), 명령 종류 CHECK. `ApprovalKind.MERCHANT_CODE_CHANGE`, **`ApprovalTarget.merchantKey`**(가맹점 대상 한 곳 — 요청 생성 팩토리 → Repository가 `target_merchant_id`에 씀, 재구성은 `target_merchant_id ?: target_ref`), `PinnedMerchantCodeChangeTarget`(7키, 등록자 키 없음), `registrantOf → null`(주석 D-MCODE-002), `MerchantCodeChangeCommandBody`(독립 벡터 두 앱 같은 hex), `MasterResponseShape` `ChangeMerchantCode → mer_`, `DEFECT_REJECTIONS + MERCHANT_CODE_MISMATCH`, `JooqMerchantPendingApprovalReader` COALESCE, 요청 `POST /api/approvals/merchant-code-change`, 감사 detail `from=;to=;reason=`(요청·실행 중·무효·만료·EXECUTED·OWNER_REJECTED — 승인 행 스냅샷에서, target_ref = mer_ C-21). **화면**: 상세 "코드 변경 요청"(OPERATOR·`merchantCodeChangeable`·CONTRACT 그룹 대기 없음), 대화상자(안내 문구·새 코드·"영문 상호" 제안 — MC-2 라이브러리 재사용·사유·근거), 기본정보 "코드 이력"(은퇴 코드 "다시 쓸 수 없음"), 승인함 "가맹점 코드 변경"·"현재 → 새 코드"(mer_ 미표시), 거절 문구 5종(§5.5). **가역 구현 결정**: (1) MASTER 본문에 `operation` = `ChangeMerchantCode` 필드를 둔다(§3.7.2 표는 "공통 + 명령 필드"만 적었지만 B3 본문 관례 C-39·ADMIN `ApprovalBodyRules.json`이 늘 쓰고 기한 확인 판별을 단순하게 한다 — `mop1` 줄은 §3.7.2 그대로); (2) "새 코드 = 현재 코드" 400은 MASTER 상세를 읽은 뒤(`newCode`·`SAME_AS_CURRENT` — 현재 코드는 MASTER만 안다), 그 전 형식 400은 `FORMAT`/`NOT_ALLOWED`; (3) advisory 409 감사 reason `MERCHANT_CODE_NOT_CHANGEABLE`; (4) 판정 4는 spec대로 잠금 전 `find`; (5) 사유 화면 라벨 오타 수정·가맹점 요청·그 밖의 정책 사유(Demo); (6) seq CHECK 변이(c)는 `ck_mca_assignment`가 seq 3도 막아 **행동상 등가 변이** → seq CHECK 존재를 information_schema로 단언(MySQL은 `ck_mca_assignment`를 보고); (7) 미해결 키 변이(e)는 MC-1b에서 target_ref = mer_라 행동이 같다 → 생성 식에 `coalesce(target_merchant_id,target_ref)` 8회를 information_schema로 단언(행동 차이는 MC-3에서 생긴다). **한계·미수행**: 생성자 10종 중 앱에 요청 경로가 있는 6종(계약 승인·첫 MID·개통·Site 심사·통화·코드 변경)만 경로 시험 — 나머지 4종(Route 승인·수단 재개·Route 재개·Site 적용 재확인)은 `ApprovalKind`에 아직 없다(B3-3·B3-4) → 단일 팩토리 시험이 "가맹점 대상 종류 전부 merchantKey = merchantId"를 단언(종류가 생기면 자동 포함). 요청자 = 승인자 대조군은 TOTP 미소비를 직접 단언하지 않는다(기존 판정표 `SELF_APPROVAL`이 TOTP 전 — 기존 시험 소관). 변이 (g)는 MASTER Domain 변형만(ADMIN pinned에는 등록자 값이 없어 그 변형을 만들 수 없다). MC-4(Snapshot·revision +1) 없음 — 변경 명령은 권리 revision을 올리지 않는다(MC-4 범위). 실제 두 앱 Demo E2E는 미실행 — `harness/e2e/merchant_code_e2e.py`에 MC-1b 9단계(승인자 계정 `--approver-login`, 별도 세션, 로그인 TOTP 칸 뒤 승인 TOTP) 추가, 시험 5개 추가(HARNESS-SELFTEST minTests 198 → 203). 증거: `~/gate-logs/mcode-mc1b/`(red-*·green-*·mut-*·muts.json). |
| 0.4.4-impl MC-1b r2 | **MC-1b 리뷰(REVIEW_PASS, MAJOR 0) 후속 — 시험 강화만(정책·기대값 완화 없음, Implementer).** MINOR-1 `AdminSchemaSpec` V13 CHECK ①~④를 하나씩 증명: ②·④는 그 CHECK만 어기는 행으로 3819 + 메시지의 제약 이름(`…_target_merchant_kind`·`…_same`), ① 행은 ④도 어기지만 MySQL이 보고하는 이름 `…_target_merchant_format`을 단언하고 본문도 고정, ③은 MC-3 전까지 대상 CHECK(`target_ref` mer_만)가 함의해 단독 위반 행이 불가능 → `information_schema.CHECK_CONSTRAINTS` 본문 고정. 변이 ① 제거·③ 제거 각각 RED·원복 동일(`~/gate-logs/mcode-mc1b/muts-r2.json`). MINOR-2 요청자 = 승인자 대조군: `SELF_APPROVAL` 뒤 같은 계정이 **같은 TOTP 코드**로 다른 운영자의 코드 변경 요청을 승인 → EXECUTED(거절이 TOTP 소비 전임을 증명). NIT-4 `Contract.approve`·`approveWithEvidence` 호출자 고정을 클래스 → 메서드(`approveContractRevision`·`approveContractRevisionWithEvidence` — `approveRevision`에 넘기는 lambda)로 좁힘. Coordinator Demo E2E 21단계 PASS(fingerprint `20d3ef85…`, `~/gate-logs/mcode-mc1b/demo/e2e-result.json`) 기록. |
