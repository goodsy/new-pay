# BIZ-CRIT-APPROVAL-001 사업자 중요정보 변경 2인 승인 명세 (초안 rev 0.3)

- rev 0.1(2026-11-28): 첫 초안, 질문 Q-BCA-01~03.
- rev 0.2(2026-11-29): 사용자 답 D-GOV-025(Q-BCA-01~04) + 1차 리뷰(MAJOR M-1~M-6, MINOR m-1~m-10) 반영. 질문 Q-BCA-05·06.
- rev 0.3(2026-11-29): 사용자 답 D-GOV-026(Q-BCA-05·06 통합 기준) + 재리뷰(R2-M1, R2-m1~m4) 반영. 남은 사용자 질문 없음.
- **rev 0.3a(2026-11-29)**: 재리뷰 APPROVED 뒤 MINOR R3-m1~m3만 반영(§12). 문서만 — 소스·시험·대장·DECISIONS·questions 변경 없음, 커밋 없음. 자기 완료 판정 아님.
- 기준 revision: `main` `f1adf60`. 아래 file:line은 이 revision 기준이다.
- 근거 결정: **D-GOV-016 ③**(정책 원문), **D-GOV-025 ①~④**, **D-GOV-026 ①~⑦**(이 명세 질문 답).
- 용어(R2-m2): **계약·가맹점 이력 있음** = 그 사업자에 사업자 계약(상태 무관 — 효력 전·유효·정지·해지·만료) 또는 가맹점(종료 포함)이 **한 번이라도 있었음**. 계약 접수 건만 있으면 이력 없음(D-GOV-026 ②). BR-BIZ-325의 '사업자 계약 있음'(효력 전·유효·정지 — 접수 종류 판정용)과 다른 말이다. 관련: D-ADMIN-003, D-MOPS-004·005·007·008, D-GOV-008 ⑦, D-BIZ-005, D-BIZ-006 ①②, D-ADMIN-005, D-GOV-003 ③④, D-GOV-014·018·021, D-PII-001.
- 표기: **[사실]** 코드·문서로 확인, **[결정]** 사용자 결정 원문, **[해석]** Coordinator 해석(Reviewer 확인·대장에서는 '제안' 행), **[구현 관례]** 가역적인 구현 세부, **[질문]** §10.

---

## 1. 정책 문장 (원문 근거)

| # | 정책 | 원문 근거 | 이 명세의 처리 |
|---|---|---|---|
| P-1 | 일반정보 변경은 OPERATOR 1인 + 사유(선택)·이력 | D-GOV-016 ③ "일반정보 변경 = 운영자 1인 + 사유·이력" | 현행 유지(BR-BIZ-016·304) |
| P-2 | **등록 완료(REGISTERED) 사업자**의 중요정보 변경은 2인 승인(요청자 ≠ 승인자) | D-GOV-016 ③ "중요정보 변경 = 2인 승인", D-GOV-025 ④ "등록 완료 뒤 중요정보는 2인 승인" | 신규. 중요정보 범위 = BR-BIZ-016·017·310 그대로. **[해석]** "법인명" = 법인 사업자의 상호(`displayName`) — 별도 필드 없음 |
| P-3 | **임시저장(DRAFT) 사업자**의 수정은 '정정'이 아니라 **등록 정보 수정** — 구분·법인등록번호를 포함해 1인 + 사유·이력. 정정·승인 정책은 등록 완료 이후부터 | D-GOV-026 ①③ | 신규 정의. 단 **계약·가맹점 이력 있는 DRAFT**는 아래 P-3a·P-7a |
| P-3a | DRAFT라도 사업자 계약이 있으면 중요정보(상호·대표자 등) 변경은 2인 | D-GOV-025 ④ "단 사업자 계약이 있으면 2인", D-GOV-026 ② | **[해석]**(D-GOV-026 Coordinator 해석 기록): '사업자 계약이 있으면' = D-GOV-026 ② 전체 이력 기준 '계약·가맹점 이력 있음' — **가맹점 이력만 있는 DRAFT**(이관 행 §2.5)의 상호·대표자 변경도 2인. 대장 '제안' 행 BR-BIZ-394 |
| P-4 | 2인 승인 경로의 모든 중요정보 변경 요청에 기존 형식 업무 근거 참조가 필수 | D-GOV-025 ① "모든 중요정보 변경에 기존 형식 근거 참조 필수" | 신규. **DRAFT 1인 등록 정보 수정에서는 근거 참조 선택**(필수 아님 — 사유·이력 규칙은 그대로, 대장 제안 행 BR-BIZ-391) — D-GOV-026 ① '등록 정보 수정' 정의에 따른 Coordinator 해석(D-GOV-026 원문에 기록, R2-M1) |
| P-5 | 재검증 = 승인자가 증빙과 변경 후 대표자·상호·사업자등록번호를 대조했다는 필수 확인(승인자·시각 기록). 진위확인 상태 불변, 외부 진위확인 연동은 LIVE 전 조건 | D-GOV-025 ① | 신규. 2인 승인 경로의 **모든** 중요정보 승인에 필수 **[해석]**(대조 대상에 상호·번호가 들어 있어 대표자 건만으로 좁히지 않음 — 대장 '제안' 행 BR-BIZ-392). DRAFT 1인 수정에는 승인자가 없으므로 해당 없음(P-4와 같은 근거) |
| P-6 | 사업자등록번호 원칙적 변경 불가 | D-GOV-016 ③, D-BIZ-005 | 현행 유지(§2.4) |
| P-7 | 계약·가맹점 이력 **없는 등록 완료** 사업자의 기존 값 있는 사업체 구분 변경·법인등록번호 변경·삭제는 **2인 승인 정정** | D-GOV-025 ②, D-GOV-026 ③ | 신규(§3.6). 첫 입력은 일반(허용) |
| P-7a | 계약·가맹점 이력이 **있으면 DRAFT 여부와 관계없이** 구분·법인등록번호 직접 변경 차단 | D-GOV-026 ④ | 신규(§3.6). 1인·2인 경로 모두 거절 |
| P-7b | 실제 사업자 주체 변경은 신규 사업자 등록 + 계약 절차 | D-GOV-026 ⑥ (D-GOV-025 ② '신규 사업자 등록 또는 계약 재심사' 안내의 구체화) | 차단 안내 문구(§3.6) |
| P-7c | 이관 데이터의 식별정보 오류는 별도 데이터 정정 절차 | D-GOV-026 ⑤ | **범위 밖** — 후속 카드 MIGRATED-ID-CORRECTION-001(§11) |
| P-8 | 중요·일반이 섞인 한 저장은 통째로 승인 뒤 반영. 승인 대기 중 그 사업자의 다른 수정·등록 완료 차단, 철회·반려·만료 뒤 허용 | D-GOV-025 ③ | 신규(§3.4·§6) |
| P-9 | 지급 계좌 변경은 기존 2인 유지 | D-GOV-016 ③ | 변경 없음 [사실] 기능 없음(BR-FIN-105 검증 `없음`) — 범위 밖 |
| P-10 | 승인 완료 전까지 기존 정보 유지 | D-GOV-016 ③ | MASTER가 근거 실행 전까지 사업자를 바꾸지 않는다(§3.3) |
| P-11 | 변경 전후·요청·승인·반려 이력 보존 | D-GOV-016 ③ | §3.7 |
| P-12 | 기존 거래·정산·계약 이력 소급 변경 금지 | D-GOV-016 ③ | §3.8 |
| P-13 | 기존 승인 기능 재사용, 과도한 범용 승인 프레임워크 금지 | D-GOV-016 ③ | §3.1 |
| P-14 | Demo·TEST·LIVE 승인 정책 동일 | D-GOV-016 ③ | §3.9 |
| P-15 | TBD-07 확정 전환, 업무 규칙·화면·API·도메인·시험 반영 | D-GOV-016 ③ | §7·§9 |

### 1.1 인원 판정표 (D-GOV-016 ③·D-GOV-025·D-GOV-026 통합 — MASTER가 사업자 행 잠금 뒤 판정)

| 등록 상태 | 계약·가맹점 이력 | 일반정보만 | 중요정보 — 상호·대표자·개업일 등(구분·법인번호 외) | 기존 값 있는 사업체 구분·법인등록번호 변경·삭제 |
|---|---|---|---|---|
| DRAFT | 없음 | 1인(사유 선택) | 1인 + 사유·이력(등록 정보 수정 — 근거 선택·대조 없음) | 1인 + 사유·이력(등록 정보 수정 — 근거 선택) — D-GOV-026 ①③ |
| DRAFT | 있음 | 1인(사유 선택) | **2인**(근거·대조 필수) — D-GOV-025 ④ + [해석] 가맹점 이력 포함 | **차단** — D-GOV-026 ④ |
| REGISTERED | 없음 | 1인(사유 선택) | **2인**(근거·대조 필수) | **2인 정정**(근거·대조 필수) — D-GOV-026 ③ |
| REGISTERED | 있음 | 1인(사유 선택) | **2인**(근거·대조 필수) | **차단** — D-GOV-026 ④ |
| INACTIVE | — | 거절 `BUSINESS_INACTIVE`(BR-BIZ-311) | 거절 | 거절 |
| TERMINATED | — | 거절 `BUSINESS_NOT_ACTIVE` | 거절 | 거절 |

- 첫 입력(이관 행 구분 NULL → 첫 값, 법인등록번호 첫 입력, 개업일 첫 입력)은 일반정보다(BR-BIZ-310, `BusinessProfileChange.kt:47`). 차단 칸에서도 첫 입력은 막지 않는다.
- 한 저장에 여러 칸이 섞이면 가장 강한 칸을 따른다: 차단 > 2인 > 1인 + 사유 > 1인(P-8 — 섞인 저장은 통째로).
- '중요정보' 분류 자체(어느 항목이 중요인지)는 BR-BIZ-016·017·310과 `BusinessProfileChange`(§2.2) 그대로다. DRAFT 대표자는 기존 행 종료가 있을 때만 중요(`:73-79`).
- 이력은 늘기만 한다(계약·가맹점은 물리 삭제 없음). 그래서 한 사업자의 판정은 시간이 지나며 **약해지지 않는다**(1인 → 2인 → 차단 방향만). 승인 대기 중에는 등록 완료가 막혀(P-8) DRAFT → REGISTERED 변화도 없다.

---

## 2. 현재 구현 사실 (HEAD `f1adf60`)

### 2.1 사업자 수정 명령 흐름 [사실]

1. 화면: `apps/internal-admin/frontend/src/screens/BusinessProfileFormScreen.tsx:161` — `importantChange(...)`로 '사유 및 비고' 필수/선택을 바꾼다. 하단 저장 한 번 = 수정 요청 하나.
2. 화면 판정: `apps/internal-admin/frontend/src/lib/businessProfile.ts:386-397` — 서버 규칙 복제(비권위).
3. ADMIN: `apps/internal-admin/src/main/kotlin/com/paycore/admin/merchantops/application/BusinessProfileService.kt:53` — 권한(`BusinessPermissions.UPDATE = {OPERATOR}`, `BusinessPermissions.kt:16`) → MASTER 본문(`:138-157`) → MASTER 호출(TX 밖) → 결과 분류(`:192`). 감사 detail `version=`·`reason=PRESENT|ABSENT`(`:226-227`). **ADMIN 로컬 업무 TX·승인 행 없음.**
4. MASTER: `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/BusinessProfileService.kt:79-90` — `AdminCommandExecutor.execute`(`:55`) 안에서 `lockWithProfile` → `lockedNow` → `Business.updateProfile` → `beginWrites` → diff 저장 → 이력·Outbox `BusinessProfileUpdated`.
5. 요청 지문: `BusinessCommandFingerprintFields.kt:20-21` → `h1:` HMAC-SHA256(`AdminCommandExecutor.kt:72`, `:457-474`).
6. Domain 판정 순서(`Business.kt:49-68`): 생명주기 → INACTIVE(`BUSINESS_INACTIVE`) → version(`:101-103`) → 변경 계산 → 변경 없음 → 중요 + 사유 없음 `BUSINESS_CHANGE_REASON_REQUIRED`(`:105-107`) → 등록 상태 규칙 → version + 1. 등록 완료(`:75-93`)도 같은 변경 계산·사유 규칙.
7. 잠금: 사업자 계약 생성(`BusinessContractCommandService.kt:71`), 접수(`ContractIntakeCommandService.kt:144`), 가맹점 등록·계약 당사자(`MerchantOnboardingService.kt:105`, `:171`, `:190`, `:314`)는 모두 **사업자 행을 먼저 잠근다** — 사업자 잠금 뒤 계약·가맹점 존재 확인은 이들과 직렬화된다(§3.6 판정 근거).

### 2.2 중요정보 판정 코드 [사실]

`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessProfileChange.kt`

| 항목 | 중요 판정 | 위치 |
|---|---|---|
| 상호 | 변경이면 중요 | `:46` |
| 사업체 구분 | 이전 값이 있을 때 변경이면 중요 | `:47` |
| 법인등록번호 | 이전 값이 있을 때 변경·삭제면 중요 | `:50-53` |
| 개업일 | 위와 같음 | `:54-57` |
| 대표자 | REGISTERED면 모든 변경, DRAFT면 기존 행 종료가 있을 때만 | `:73-79` |
| 그 밖 | 일반 | `:59-67`, `:80-94` |

결과는 `important: Boolean` 하나(항목별 분류 없음).

### 2.3 기존 2인 승인 흐름 구조 [사실]

공통 Aggregate `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/domain/ApprovalRequest.kt`: 생성 불변 값(`:7-8`), 승인·거절 판정(`:61-73` — PENDING만, 만료면 EXPIRED, `SELF_APPROVAL`), 철회 요청자 본인(`:76-81`), 2-TX 전이(`:101-132`). 상태 `ApprovalStatus.kt`(`APPROVED` 미사용).

| 흐름 | 종류·모델 | 요청 | 승인 | 실행 | 만료 | TOTP | 요청자≠승인자 | 결과 불명 |
|---|---|---|---|---|---|---|---|---|
| ① 운영자 보정 취소 `OwnerServiceApprovalService.kt:51-58` | `OPERATOR_CORRECTION_CANCEL`(`ApprovalKind.kt:25`), 대상 PAYMENT | OPERATOR | APPROVER | PAYMENT 재검사(TX 밖) | 요청 24h(`application.yml:98`), 실행 창 24h(`:119`) | 승인·거절 직전 TOTP(`ApprovalService.kt:189`, `:260`) | ADMIN TX1 + 소유 서비스 | 회복 job(`OwnerExecutionRecoveryService.kt:93-95`) |
| ② 가맹점 코드 변경 `MerchantOpsApprovalService.kt:390-410` | `MERCHANT_CODE_CHANGE`(`ApprovalKind.kt:65`), 대상 MERCHANT | 입력 규칙 → 세션 역할 → MASTER 상세(advisory) → 요청 TX(승인 행 INSERT·미해결 키 UNIQUE → 요청자 잠금·재검사 → 명령 행 INSERT → 감사)(`:415-443`) | A 재조회(TX 밖, 장애 503) → TX1(잠금·판정표·참여자·TOTP·요청자 재검사·pinned vs 현재 다르면 INVALIDATED(TARGET_CHANGED), MASTER 미호출 → 본문·`mop1`·EXECUTING)(`:488-517`, `:567-657`) | C POST → TX2 `OwnerOutcomeApplier`. MASTER `executeApproved`(`MerchantOnboardingService.kt:407-433`) + `ApprovalEvidence.admit`(`ApprovalEvidence.kt:32-43`) | 24h + 실행 창 1h(`application.yml:167`, Demo 값) | 승인 직전 | ADMIN TX1 + MASTER(D-MOPS-007 ①). 등록자 규칙 없음(Q-MC-10, `:663-672`) | `recover`(`:700-760`) — 결정 조회 → 기한 뒤 기한 확인만 → 같은 바이트 재전송, 확정 불가면 `OWNER_UNRESOLVABLE` finding·경보 |
| ③ 계약 승인 등 B2·B3 | `ApprovalKind.kt:34-58` | ② + 업무 근거 참조 필수(BR-BIZ-232, `MerchantOpsInputRules.kt:85-89`, 형식 `ReacquisitionInputRules.kt:10`) | ② + 승인자 ≠ 등록자(TOTP 전, `:605-607`) | ② | ② | ② | ② | ② |

- 거절·철회·만료: 공통 `ApprovalService`(`:229-320`)·`ApprovalExpiryJob`. 승인 라우팅 `ApprovalController.kt:194` → `OwnerServiceApprovalService.approve`(`:152-157`).
- 대기 표시 선례 `MerchantPendingApprovalQueryService`(조회 4역할, 내용 없음).
- ADMIN DB: 대상 CHECK·미해결 키 `V13__merchant_code_change_approval.sql:9-64`, `V15__merchant_code_target_ref.sql`. 명령 표 `admin_merchant_ops_command.merchant_id` `^mer_` NOT NULL(`V9__merchant_ops_approval.sql:57-69`) — 사업자 대상에 못 쓴다. ADMIN runtime 권한 파일 마지막 `runtime-privilege/V10__merchant_ops_approval_privileges.sql`.
- MASTER 근거 표 operation CHECK: `V9__admin_approval_evidence.sql:26` → `V11`·`V13__merchant_code_assignment.sql:45`에서 확장(새 operation마다 migration 필요).

### 2.4 사업자등록번호 불변 [사실]

| 계층 | 장치 | 위치 |
|---|---|---|
| ADMIN 입력 | 수정 본문에 `registrationNumber` 오면 400 | `BusinessProfileBodyParser.kt:30`, `BusinessHttpSpec`(A):297·312 |
| MASTER 입력 | 생성 필드에만 | `BusinessProfileRequestParser.kt:78` |
| Domain | `val`, `next()` 복사 | `Business.kt:17`, `:109-111` |
| JPA | `updatable = false` | `JpaBusinessEntity.kt:28` |
| Repository | 다르면 `check` 실패 | `JpaBusinessRepository.kt:80` |
| DB | UNIQUE·형식 CHECK(`V1__master_core.sql:22-31`). 표 단위 UPDATE 권한(`V2__master_runtime_privileges.sql:13`) — 열 단위 금지·trigger 없음 | — |

앱 경로로는 저장 뒤 바뀌지 않는다. DB 수준 강제는 없음(§11).

### 2.5 사업자 계약·가맹점과의 관계 [사실]

- 사업자 계약 표(`V21__business_contract.sql:85-170`)는 `business_id`만 참조, 상호·대표자·구분 스냅샷 없음.
- DRAFT 사업자도 계약 완료로 사업자 계약을 가질 수 있다(BR-BIZ-332). 이관 행(V15)은 모두 DRAFT로 바뀌었고(`V15__business_profile.sql:23`) 그 전부터 가맹점이 있을 수 있다(BR-BIZ-022 "이미 있는 가맹점") — **DRAFT + 가맹점 이력 있음 + 사업자 계약 없음** 조합이 실제로 있다. 이 행들의 구분·법인번호 오류는 D-GOV-026 ⑤에 따라 별도 데이터 정정 절차(MIGRATED-ID-CORRECTION-001)로 간다.
- 이력 조회 대상 표: 사업자 계약 `business_contract`(`V21__business_contract.sql:85` — 상태 무관 행 존재), 가맹점 `merchant`(`V1__master_core.sql:38` — `business_id`, 종료 포함). 둘 다 runtime 계정에 DELETE 권한이 없다(`runtime-privilege/master/V2__master_runtime_privileges.sql:17` merchant SELECT·INSERT·UPDATE, `V22__business_contract_privileges.sql:5` SELECT·INSERT) — 이력은 늘기만 한다. 계약 접수 `contract_intake`(`V19__contract_intake.sql:19`)는 대상이 아니다(D-GOV-026 ②).
- 사업자 계약이 있으면 새 접수는 변경 접수만(BR-BIZ-318·325).
- 권리 Snapshot에 사업자 정보 없음(BR-BIZ-021). KYC 미구현. 진위확인 `NOT_CHECKED`만(BR-BIZ-013).

---

## 3. 설계

### 3.1 재사용과 신규 (최소 확장 — P-13)

| 구분 | 재사용 | 신규(최소) |
|---|---|---|
| ADMIN Domain | `ApprovalRequest`·`ApprovalStatus`·`ApprovalDecision`·`ApprovalParticipants`·`OwnerExecution` 판정표 | `ApprovalKind.BUSINESS_CRITICAL_CHANGE`(OWNER_SERVICE, 요청 OPERATOR, 승인 APPROVER), `ApprovalTargetKind.BUSINESS` + `ApprovalTarget.Business(businessId)` — **`target_security_version` 열은 0 고정**(가맹점·매입 파일 선례 C-16), 기준 version은 명령 행 `base_version`이 권위. pinned 값 `PinnedBusinessChangeTarget` |
| ADMIN Application | 거절·철회·만료, `OwnerOutcomeApplier`, `OwnerExecutionRecoveryService`(종류 분기 1줄 — **결제(PAYMENT) 경로 분기보다 앞**에 둔다: 사업자 승인 행이 PAYMENT client에 닿지 않게), MASTER 서명 채널·`mop1` 규칙·permit, finding Store, 승인 라우팅 분기 1줄 | `BusinessChangeApprovalService`(요청·승인 A/TX1/C·회복 한 행 — `MerchantOpsApprovalService`와 같은 단계 순서. 가맹점 서비스에 종류를 더하지 않는다: `merchantKey`·`mer_` 전제가 곳곳에 있음), `BusinessChangeCommandStore` → `JpaBusinessChangeCommandStore`(프로토콜 기록 Store ③ — §14), `BusinessPendingApprovals`(대기 표시 SPI — merchantops가 모르는 방향, 가맹점 선례) |
| ADMIN DB | `admin_approval_request` | **V17**: kind·target·owner_states CHECK 확장, `target_kind = 'BUSINESS'`(`target_ref` = `biz_<ULID>`, version 0), 미해결 키 `BUSINESS:<biz_>`, 표 `admin_business_change_command`(approval_id PK·FK, business_id, base_version, proposal_id UNIQUE, content_digest, changed_fields, representative_changed, subject_correction, evidence_ref, created_at — INSERT 전용, **상호·개인정보 없음**). **V18**: runtime 권한 SELECT·INSERT |
| MASTER | `AdminCommandExecutor.execute/executeApproved`, `ApprovalDecisionService`, `ApprovalEvidenceStore`, `BusinessRepository.lockWithProfile/update`, 이력·Outbox, `BusinessProfileChange` | **V26**: 표 `business_change_proposal` + 표 `business_change_evidence`(1인 경로 선택 근거 원문 — R3-m2: business_id, change_history_id, admin_request_id, evidence_ref, recorded_by, recorded_at, INSERT 전용, 화면·이력 응답에 나가지 않음) + 근거 표 operation CHECK에 `ProposeBusinessCriticalChange`는 넣지 않고(1인 명령 — 근거 없음) `ApplyBusinessCriticalChange` 추가. **V27**: runtime 권한 proposal·evidence SELECT·INSERT(UPDATE·DELETE 없음). Store `BusinessChangeProposalStore` → `JpaBusinessChangeProposalStore` + `JpaBusinessChangeProposalEntity`(제안은 사업자 Aggregate가 아닌 프로토콜 기록 — Store ③), 승인 상세 조회 `BusinessChangeProposalReader` → `JooqBusinessChangeProposalReader`(CQRS, 비권위) |
| 화면 | 승인함·승인 상세 틀·TOTP 대화상자, 편집 폼·'사유 및 비고' | 버튼 '승인 요청', 근거 참조 칸, 대기 배지·철회, 승인 상세 전후 표·원문 보기·대조 확인 체크 |

만들지 않는 것: 범용 변경 요청 엔진, 승인 단계 설정, 규칙 DSL, 여러 Aggregate 공통 제안 표. 의존성(build.gradle) 변경 없음.

**JPA 선택 설명** [구현 관례]:
- 역할: `JpaBusinessChangeProposalEntity`는 제안 한 건을 담는 INSERT 전용 Entity다(순수 Domain 값 `BusinessChangeProposal`과 분리, Mapper는 persistence adapter).
- 예: 승인 실행 TX는 제안을 ID로 읽기만 하고 사업자 변경은 기존 `JpaBusinessRepository.update`가 한다.
- 이유: 제안을 사업자 Aggregate 안에 넣으면 사업자 load마다 제안 목록이 따라오고, 대기 중 제안이 사업자 version과 섞인다.
- 주의: `@Immutable` + 모든 열 `updatable = false`로 변경 감지 UPDATE가 나가지 않게 한다(DB 권한도 UPDATE 없음 — 위반은 1142로 드러남). 하위 행은 JSON 열 하나(정규형)로 두어 별도 자식 Entity를 만들지 않는다.

### 3.2 제안 원문은 MASTER에만, ADMIN은 식별자·digest만 (M-5, D-ADMIN-005)

- MASTER `business_change_proposal`: 제안 ID `bcp_<ULID>`, business_id, base_version, requested_by, requested_at, reason(원문), evidence_ref, content(정규형 JSON — 구분·상호·등록정보·하위 행 전체), content_digest, changes(마스킹 — 기존 §9 규칙), representative_changed, subject_correction.
- **content_digest = `h1:` HMAC-SHA256(MASTER fingerprint 키, 정규형 content + business_id + base_version)** — 요청 지문과 같은 키·형식(`AdminCommandExecutor.kt:457-474`). 키 없이 ADMIN DB 값으로 원문을 대입 확인할 수 없다(SEC-BIZ-001 L-13 해소 방식).
- ADMIN pinned = {businessId, baseVersion, proposalId, contentDigest, changedFields(항목 이름), representativeChanged, subjectCorrection}. **상호·개인정보 원문 없음**. 승인함·상세의 상호 표시는 MASTER 라벨·제안 조회로 읽는다(ADMIN-HISTORY-KEYS-001 라벨 읽기 방식).
- 실행 본문(`mop1` 지문 대상) = 근거 공통 필드 + {businessId, baseVersion, proposalId, contentDigest}.

### 3.3 데이터 흐름

```
[화면] 하단 저장 ─(화면 판정 advisory: 2인 필요)─▶ '승인 요청'
  ▼ ADMIN 요청 (OPERATOR, 세션 역할, 입력 규칙: 사유 필수·카드번호 패턴·근거 참조 필수·형식)
  ├─ 사전 검사(advisory, ADMIN DB): 그 사업자 미해결 승인 있으면 409 DUPLICATE_UNRESOLVED — MASTER 미호출(고아 제안 줄이기, m-5)
  ├─(TX 밖) MASTER ProposeBusinessCriticalChange — admin_request 멱등(키 = submissionId 유래), 요청 지문 h1
  │     MASTER TX: 사업자 잠금 → lockedNow → 판정(생명주기·INACTIVE·version·변경 계산·변경 없음·계약·가맹점 이력(잠금 뒤)
  │       ·차단 칸·인원 판정(§1.1: 2인 칸이 아니면 BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED — 제안 때만)·사유·규칙)
  │     → 제안 행 INSERT  ※ 사업자·하위 행·version·변경 이력 불변(이력은 실행 때만 — m-5)
  │     ◀ {proposalId, baseVersion, contentDigest, changedFields, representativeChanged, subjectCorrection}
  ├─ ADMIN 요청 TX: 승인 행 INSERT(미해결 키 UNIQUE — 권위) → 요청자 잠금·재검사 → 명령 행 INSERT → 감사 APPROVAL_REQUESTED
  ▼
[승인함] APPROVER: 상세(사람용 값, 항목별 전후 마스킹) + '변경 후 원문 보기'(§5.1 — 열람마다 감사·속도 제한) + '근거 보기'(BR-BIZ-233)
  → 승인(직전 TOTP + 대조 확인 체크 필수) / 거절(직전 TOTP)
  ├─ A (TX 밖): MASTER 사업자 version·생명주기·등록 상태만 조회(m-1 — 상세 전체가 아님)
  ├─ TX1: 잠금·판정표·참여자(요청자≠승인자)·확인 체크(없으면 400, TOTP 소비 전)·TOTP·요청자 재검사
  │        ·대상 비교(version ≠ base_version 이거나 종료·INACTIVE면 INVALIDATED(TARGET_CHANGED), MASTER 미호출)
  │        → 본문·mop1·OwnerExecution → EXECUTING
  ├─ C (TX 밖): MASTER ApplyBusinessCriticalChange(저장 바이트)
  │     executeApproved: 근거 1~3 → 사업자 잠금 → lockedNow(근거 4) → 제안 읽기
  │     → proposal.businessId == 명령 businessId, digest 재계산 일치, **proposal.requestedBy == evidence.requestedBy**(M-6)
  │     → Domain 재판정(생명주기·INACTIVE·version == base_version·계약·가맹점 이력(잠금 뒤)·**차단 칸만**·규칙) — 인원 재판정 없음(R2-m1)
  │     → diff 저장(version +1) → 이력 BusinessProfileUpdated(changes·사유·approvalId·proposalId·revalidatedBy·revalidatedAt) + Outbox → 근거 EXECUTED
  │     거절은 저장된 거절 + 근거 REJECTED(같은 TX)
  └─ TX2: EXECUTED / OWNER_REJECTED(code) / EXECUTION_UNKNOWN(회복)
```

- **권위 강제(P-10)**: MASTER 1인 `update`·`completeRegistration`은 §1.1에서 2인 칸에 해당하면 저장 거절 `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`, 차단 칸이면 `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`. 판정 순서(1인 경로): 생명주기 → INACTIVE → version → 변경 계산 → 변경 없음 → **정정 차단 → 2인 필요** → 사유 → 규칙. 규칙 위반(400)보다 승인 필요(저장 거절)가 먼저다.
- **실행 때 인원을 다시 판정하지 않는다(R2-m1)**: 승인된 변경을 "이제 2인이 아니다"라는 이유로 거절하지 않는다. 실행 TX의 업무 재판정은 ① 생명주기·INACTIVE ② version = base_version ③ **차단 칸**(대기 중 계약·가맹점 이력이 생겨 구분·법인번호 변경이 차단 칸이 된 경우 — `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`) ④ 등록 상태 규칙뿐이다. §1.1 판정은 약해지지 않으므로(이력은 늘기만, 대기 중 등록 완료 차단) 2인 요청이 실행 시점에 1인 칸이 되는 일도 없다 — 그런 상태가 보이면 무결성 결함(예외·경보)으로 다루고 업무 거절로 닫지 않는다.
- **근거 실행 시각의 대조 확인**: 승인 본문 `revalidated: true`는 TX1이 검사하고, 근거 행 승인자·승인 시각이 곧 대조 확인자·시각이다(별도 열 없이 이력 detail에 `revalidatedBy`·`revalidatedAt` = 승인자·승인 시각을 명시). 진위확인 `verificationStatus`는 바꾸지 않는다(P-5).
- **M-6 근거**: 제안을 만든 사람과 승인 요청자가 다르면, 남이 만든 제안에 자기 이름으로 요청을 붙여 요청자≠승인자 검사를 우회할 수 있다. MASTER는 실행 TX에서 `proposal.requested_by ≠ evidence.requestedBy`이면 저장 거절 `PROPOSAL_REQUESTER_MISMATCH`(근거 REJECTED). ADMIN도 요청 TX에서 MASTER 응답의 요청자 = 세션 요청자를 확인(이중).
- **등록 완료**: 2인 칸의 중요 변경이 섞인 `completeRegistration`은 `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`(DRAFT + 계약·가맹점 이력 있음 — §1.1), 차단 칸이 섞이면 `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`. 근거 경로는 `update`만 실행한다 [구현 관례]. 이력 없는 DRAFT는 지금처럼 1인 + 사유로 등록 완료할 수 있다(구분·법인번호 포함 — 등록 정보 수정, D-GOV-026 ①).
- **고아 제안**: 제안 성공 뒤 ADMIN 요청 TX가 실패하면 제안 행이 남는다. 근거 없이는 적용되지 않고 이력에도 남지 않는다(실행 때만 기록). 같은 키 재시도는 같은 제안 재생. 보관은 Q-PII-5 결정 전 보관(BR-BIZ-024).

### 3.4 혼합 변경·대기 중 수정 (P-8 — D-GOV-025 ③)

- Domain `BusinessProfileChange`에 파생값 `importantFields`·`representativeChanged`·`subjectCorrection` 추가. 기존 `important` 판정 규칙은 불변.
- 한 저장 = 한 승인 단위: 2인 칸이면 일반 항목까지 제안에 담아 승인 뒤 함께 반영. 승인 상세는 항목마다 중요/일반 표시.
- 대기 중 차단: ADMIN 1인 수정·등록 완료 요청은 그 사업자 미해결 승인이 있으면 MASTER 호출 전 409 `BUSINESS_CHANGE_PENDING`. 검사와 MASTER 호출 사이 경합은 version이 막는다(§6). 철회·반려·만료·무효·소유 거절 뒤 허용.

### 3.5 근거·재검증 (P-4·P-5 — D-GOV-025 ①)

- 2인 경로의 모든 요청에 근거 참조 필수(형식 BR-BIZ-232와 같음, 카드번호 모양 거부, 감사·로그·목록에 원문 없음, APPROVER·요청자만 별도 조회·조회마다 감사 — BR-BIZ-233). 1인 경로(이력 없는 DRAFT의 등록 정보 수정)에서는 근거 참조가 **선택**이다 — D-GOV-026 ① '등록 정보 수정' 정의에 따른 해석(R2-M1, D-GOV-026 원문에 기록, 대장 제안 행 BR-BIZ-391). 넣으면 같은 형식 검사(카드번호 모양 거부)를 하고 MASTER 1인 수정 본문 선택 필드 `evidenceRef`로 보낸다. **이력 detail에는 `evidence=PRESENT|ABSENT`만** 남기고 원문은 이력 탭·사업자 상세에 표시하지 않는다(R3-m2 — Coordinator 결정, 가역 — 2인 경로 BR-BIZ-233과 같은 규칙). 원문은 같은 MASTER TX에서 `business_change_evidence`(표시하지 않는 근거 표 — 이력 행 ID에 연결)에 INSERT하고, 조회는 2인 경로와 같게 APPROVER와 그 저장의 요청자(저장한 OPERATOR)만 별도 조회·조회마다 ADMIN 감사로 본다. ADMIN 감사·로그·지표에는 `evidence=PRESENT|ABSENT`만. 승인자 대조 확인은 승인자가 없으므로 없다.
- 승인 대화상자 필수 체크: "근거와 변경 후 대표자·상호·사업자등록번호를 대조했습니다". 체크 없이 승인하면 400(TOTP 소비 전 — 상태·TOTP 불변).
- 승인자가 실제로 대조할 수 있게 **변경 후 원문 조회**를 둔다(M-1, §5.1).
- LIVE 조건: 근거 원천 대조(BR-BIZ-234 준용) + 외부 진위확인 연동.

### 3.6 사업자등록번호 불변 · 구분·법인번호 변경 (P-3·P-6·P-7·P-7a~c — D-GOV-025 ②, D-GOV-026)

- 번호: 새 경로(제안·근거 실행)에도 번호 필드를 두지 않고, 본문에 오면 400, 실행이 번호를 바꾸지 않음을 시험으로 고정.
- 대상 항목(`subjectCorrection` 파생값): 기존 값 있는 사업체 구분 변경, 기존 법인등록번호 변경·삭제. 첫 입력은 일반.
- 처리(§1.1): 이력 없는 DRAFT = 1인 + 사유·이력(**정정이 아니라 등록 정보 수정** — D-GOV-026 ①, 근거 선택·대조 없음). 이력 없는 REGISTERED = **2인 정정**(D-GOV-026 ③). 이력 있음 = DRAFT 여부와 관계없이 **차단**(D-GOV-026 ④).
- **판정 시점·잠금**: 계약·가맹점 이력(용어 머리말 — 상태 무관 행 존재, 접수 제외)은 MASTER가 **사업자 행 잠금 뒤**, 제안 TX와 실행 TX에서 **두 번** 판정한다. 사업자 계약 생성·가맹점 등록도 사업자 행을 먼저 잠그므로(§2.1 7) 판정과 직렬화된다. 대기 중 계약·가맹점 이력이 생기면 사업자 version은 바뀌지 않지만 실행 TX 재판정이 `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED` 저장 거절로 막는다(ADMIN A 단계는 이것을 보지 않는다 — 권위는 MASTER). 대기 중 계약 완료·가맹점 등록 자체는 막지 않는다(그쪽 업무를 이 승인에 묶지 않음 — [구현 관례]).
- **정정 승인 흐름 = 중요정보 승인과 같은 틀**: 종류 `BUSINESS_CRITICAL_CHANGE` 하나, 명령 행 `subject_correction = true`. 근거·대조 확인 필수(구분은 BR-BIZ-017로 중요정보). 등록 완료 사업자에게만 생긴다(DRAFT는 1인 등록 정보 수정). 승인 상세에 경고 "사업체 구분·법인등록번호 정정입니다. 이 사업자에 사업자 계약·가맹점 이력이 생기면 실행되지 않습니다." 별도 종류를 만들지 않는다(P-13).
- 차단 안내(이력 있음 — D-GOV-026 ④⑥): "이 사업자에는 사업자 계약 또는 가맹점 이력이 있어 사업체 구분·법인등록번호를 바꿀 수 없습니다. 사업자가 실제로 바뀌었다면 새 사업자로 등록하고 계약 절차를 진행하세요. 저장된 정보가 잘못 입력된 경우는 별도 데이터 정정 절차로 처리합니다." **[해석]** D-GOV-025 ②의 '계약 재심사'는 D-GOV-026 ⑥ '신규 사업자 등록 + 계약 절차'로 구체화되었다고 본다(새 재심사 기능 없음 — 대장 제안 행 BR-BIZ-393). 같은 번호로는 새 사업자를 등록할 수 없다(BR-BIZ-003) — 실제 주체 변경(개인 → 법인 전환 등)은 보통 새 사업자등록번호를 받으므로 신규 등록이 가능하고, 같은 번호의 입력 오류는 이관 데이터면 MIGRATED-ID-CORRECTION-001(D-GOV-026 ⑤), 그 밖은 번호 정정 정책(TBD-04)과 함께 후속(§11).
- 화면: MASTER 상세의 advisory 값 `subjectCorrection: ALLOWED_SINGLE(이력 없는 DRAFT) | ALLOWED_WITH_APPROVAL(이력 없는 REGISTERED) | BLOCKED(이력 있음)`로 구분 라디오·법인등록번호 칸을 열거나 읽기 전용 + 안내.
- 사업자 계약(FLOW-2)과의 관계: 사업자 정보 변경은 계약·버전·접수 건을 만들거나 바꾸지 않는다(BR-BIZ-021). 계약 화면은 현재 상호를 읽는다 — 기록 소급 변경이 아니라 표시 값 **[해석]**. "계약 당시 상호" 표시는 §11.

### 3.7 이력 (P-11)

| 사건 | 기록 | 내용 |
|---|---|---|
| 요청 | ADMIN 승인 행(불변 열)·명령 행·감사 `APPROVAL_REQUESTED` + MASTER 제안 행 | 요청자·시각·항목 이름, 제안 원문·사유는 MASTER 제안 행만 |
| 승인(실행 시작) | ADMIN 결정 값·감사 `APPROVAL_EXECUTING` | 승인자·시각(= 대조 확인자·시각) |
| 실행 | MASTER 근거 EXECUTED + 이력 `BusinessProfileUpdated`(changes 마스킹·사유·approvalId·proposalId·revalidatedBy·revalidatedAt) + ADMIN EXECUTED | 변경 전후 |
| 반려·철회·만료·무효 | ADMIN 상태·감사 | MASTER 기록 없음(실행 시도 없음) [구현 관례] |
| 소유 거절 | MASTER 근거 REJECTED + ADMIN OWNER_REJECTED(code) | code |

사업자 상세 '변경 이력' 탭은 실행된 변경만(MASTER 이력). 요청·반려 등은 승인함(요청자·APPROVER — D-GOV-003 ④).

### 3.8 소급 변경 금지 (P-12)

실행 TX가 쓰는 표: `business`·`business_registration`·하위 4표·`master_change_history`·`outbox_event`·`admin_request`·근거 표(제안 표는 읽기만). 계약·접수·가맹점·MID·권리·정산·결제 표 불변(시험). 권리 Snapshot 발행 없음. ArchUnit: `BusinessRepository.update` 호출자는 `BusinessProfileService`(1인 경로)와 새 근거 실행 Use Case 구현만.

### 3.9 Demo·TEST·LIVE 동일 (P-14)

환경·프로파일 분기 없음. Demo seed(D-DEMO-005)는 사업자 생성만 — 이 종류에 예외 없음(seed가 근거 실행 Use Case를 부르지 않음 — ArchUnit). Demo 빠른 로그인은 계정 전환일 뿐. 시간 값(요청 24h, 실행 창 1h)은 기존 Demo 값, 운영값 LIVE Gate. LIVE 조건: 근거 원천 대조, 외부 진위확인.

---

## 4. 상태 전이

### 4.1 ADMIN 승인 요청 (기존 OWNER_SERVICE 판정표 — 새 상태 없음)

| 현재 | 사건 | 다음 |
|---|---|---|
| (없음) | 요청 TX 성공 | PENDING(미해결 키 `BUSINESS:<biz_>`) |
| PENDING | APPROVER 거절(TOTP) | REJECTED |
| PENDING | 요청자 철회 | WITHDRAWN |
| PENDING | 유효 시각 경과 | EXPIRED |
| PENDING | TX1 요청자 자격 상실 / 대상 변경(version·종료·INACTIVE) | INVALIDATED(`REQUESTER_NOT_ELIGIBLE`·`TARGET_CHANGED`), MASTER 미호출 |
| PENDING | TX1 통과 | EXECUTING |
| EXECUTING | MASTER 성공 | EXECUTED |
| EXECUTING | MASTER 저장 거절(`BUSINESS_VERSION_CONFLICT`·`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`(차단 칸만 — R2-m1)·`PROPOSAL_REQUESTER_MISMATCH`·`PROPOSAL_CONTENT_MISMATCH`·`APPROVAL_EXPIRED`·`BUSINESS_INACTIVE`·`BUSINESS_NOT_ACTIVE`·규칙 위반 등. 인원 재판정 거절 없음) | OWNER_REJECTED(code) |
| EXECUTING | 응답 유실·timeout·5xx | EXECUTION_UNKNOWN(반영 여부 단정 안 함) |
| EXECUTION_UNKNOWN | 회복 결정 조회 DECIDED | EXECUTED / OWNER_REJECTED |
| EXECUTION_UNKNOWN | 기한 뒤 확정 불가 | 그대로 + `OWNER_UNRESOLVABLE` finding·경보(자동 종결 없음) |

- **MASTER 실행 중 무결성 결함**(S2 리뷰 MINOR-2 — 재계산 변경 요약 ≠ 제안 요약, 승인 뒤 1인 칸, 승인 내용의 규칙 위반 등 정상 경로에서 불가능한 상태): 업무 거절로 저장하지 않는다. MASTER는 값 없는 WARN·지표
  `paycore.master.admin.integrity_defect{operation}`을 남기고 TX를 rollback(근거 행 없음) → 503. ADMIN은 **EXECUTION_UNKNOWN**으로 받고 회복이 결정 조회 NONE을 보다가 **기한 뒤 기한 확인**으로
  `APPROVAL_EXPIRED`가 저장되어 OWNER_REJECTED로 닫힌다(UNKNOWN → 만료 경로 — 자동 성공·자동 미실행 확정 없음). 결함 원인은 경보로 조사한다.

### 4.2 MASTER

제안 행: 상태 없음(INSERT 전용). '적용됨' = 근거 EXECUTED 행이 그 제안을 가리킴. '대기' 권위 = ADMIN 승인 행 + MASTER version·이력(차단 칸) 재판정. 사업자: 실행 성공 때만 version +1.

---

## 5. API·화면

### 5.1 MASTER (ADMIN 서명 채널 — ADMIN_SERVICE 키, D-MOPS-005)

| endpoint(이름은 구현 때 관례로) | 성격 | 응답·거절 |
|---|---|---|
| `POST …/businesses/{biz}/critical-change-proposals` | 1인 기록 명령(`admin_request` 멱등, h1) — 본문 = 수정 본문 + version + 사유 + 근거 참조 + 요청자 | 200 {proposalId, baseVersion, contentDigest, changedFields, representativeChanged, subjectCorrection, requestedBy} / 저장 거절 `BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED`·`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`·`BUSINESS_VERSION_CONFLICT`·`BUSINESS_INACTIVE`·`BUSINESS_NOT_ACTIVE`·`BUSINESS_CHANGE_REASON_REQUIRED`·`BUSINESS_PROFILE_UNCHANGED` / 400 규칙 |
| `GET …/critical-change-proposals/{bcp}` | 조회(Reader) | 마스킹 전후 항목·상호·요청 시각 |
| `GET …/critical-change-proposals/{bcp}/original` | **원문 조회(M-1)** — ADMIN이 호출자 = APPROVER 또는 그 승인 요청의 요청자이고 **승인 요청이 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN)일 때만** 부른다(R2-m4 — 끝난 요청은 ADMIN이 MASTER 호출 전 거부, Coordinator 결정·가역·개인정보 최소화) | 변경 후 원문(대표자 이름·주소·연락처 등). ADMIN 감사 열람마다 1행(`APPROVAL_PROPOSAL_UNMASKED` — 값 없음), 속도 제한(D-BIZ-006 ② 사업자 상세 원문과 같은 창·한도 [구현 관례]). 로그·지표에 원문 없음 |
| `GET …/businesses/{biz}/approval-state` | A 단계 조회(m-1) | {version, lifecycle, registrationStatus}만 |
| `POST …/business-critical-changes` | 근거 경로(`executeApproved`, 키 `mop1.<approvalId>`) | EXECUTED reference = businessId / 저장 거절 |
| 결정 조회·기한 확인 | 재사용 | — |
| 기존 1인 수정·등록 완료 | 변경 | §3.3 판정 순서. 본문에 선택 필드 `evidenceRef`(BR-BIZ-391 — 형식 검사, 요청 지문 필드에 추가, 원문은 `business_change_evidence`, 이력 detail은 PRESENT/ABSENT만 — R3-m2) |
| `GET …/business-change-evidence/{historyId}` | 1인 경로 근거 원문 조회(R3-m2) — ADMIN이 호출자 = APPROVER 또는 그 저장의 요청자일 때만 부른다 | 원문 1건, ADMIN 감사 조회마다 1행(BR-BIZ-233과 같음), 로그·지표에 원문 없음 |
| 사업자 상세 | 필드 추가 | `criticalChangeMode: SINGLE | APPROVAL`, `subjectCorrection` advisory |

### 5.2 ADMIN

- `POST /api/admin/businesses/{biz}/critical-change-requests`(OPERATOR): 수정 본문 + version + reason + evidenceRef(필수) + submissionId → 201 {approvalId} / 409 `DUPLICATE_UNRESOLVED`·`BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED`·`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED` / 400 / 503(결과 불명 — 같은 키 재시도).
- 기존 1인 수정·등록 완료: 대기 중이면 409 `BUSINESS_CHANGE_PENDING`(MASTER 미호출). MASTER `…APPROVAL_REQUIRED`·`…SUBJECT_CHANGE_NOT_ALLOWED`는 409로 전달.
- 승인·거절·철회: 기존 승인함 API. 승인 본문에 `revalidated: true` 필수(이 종류만).
- 원문 보기: `GET /api/admin/approvals/{apr}/proposal-original` — APPROVER와 그 요청 요청자만(D-GOV-003 ④), **미해결 상태(PENDING·EXECUTING·EXECUTION_UNKNOWN)에서만**(종료 상태 REJECTED·WITHDRAWN·EXPIRED·INVALIDATED·OWNER_REJECTED·EXECUTED는 409 `APPROVAL_NOT_OPEN` — 실행된 변경의 현재 원문은 기존 사업자 상세 원문 조회 경로 D-BIZ-006 ②), 감사(거부 시도 포함)·속도 제한.
- 사업자 상세 `pendingCriticalChange: {status, requestedAt} | null` — **OPERATOR·APPROVER만**(사업자 조회 권한 `BusinessPermissions.VIEW`과 같음, m-2). 내용·요청자 없음.
- 감사 detail: `fields=<항목 이름>;rep=Y|N;subject=Y|N;evidence=PRESENT`(원문·사유 없음).

### 5.3 화면

- 편집 폼: MASTER 상세 `criticalChangeMode`와 화면 판정으로 2인 칸이면 버튼 '저장' → **'승인 요청'**, 안내 "상호·대표자 등 중요정보는 다른 승인자가 승인해야 반영됩니다. 승인 전까지 지금 정보가 유지됩니다." + '근거 참조' 칸(필수) + '사유 및 비고'(필수). 1인 칸(이력 없는 DRAFT — 구분·법인번호 포함 등록 정보 수정)은 지금과 같고 '근거 참조(선택)' 칸만 더한다.
- 성공: "승인 요청을 보냈습니다", 폼은 기존 정보로. 결과 불명: 폼 유지·같은 키 재시도.
- 사업자 상세: 대기 배지('중요정보 변경 승인 대기 중', 요청 시각), 요청자에게 '요청 철회', 대기 중 편집·등록 완료 버튼 비활성 + 안내.
- 구분·법인등록번호: `subjectCorrection`에 따라 열림(정정은 승인 요청 경고 표시)/읽기 전용 + 차단 안내(§3.6).
- 승인함: 종류 "사업자 중요정보 변경"(정정 건은 "사업자 중요정보 변경 — 구분·법인번호 정정"), 대상 = 상호 + 사업자등록번호(MASTER 라벨 읽기). 상세 = 항목별 전 → 후(마스킹, 중요/일반), 사유, '변경 후 원문 보기', '근거 보기', 접힌 기술 정보(D-GOV-003 ③). 승인 대화상자 필수 체크 + TOTP.

---

## 6. 동시성·멱등

| 경합 | 처리 |
|---|---|
| 같은 사업자 2건 요청 | ADMIN 사전 검사(advisory) + 미해결 키 UNIQUE(권위) → 409 |
| 대기 중 1인 수정 | ADMIN 409 `BUSINESS_CHANGE_PENDING`. 검사-호출 사이 경합은 version: 수정이 먼저 커밋되면 A/TX1 INVALIDATED, 실행 TX `BUSINESS_VERSION_CONFLICT` |
| 대기 중 계약 완료·가맹점 등록 | 막지 않음. 정정 건(구분·법인번호)이면 실행 TX가 사업자 잠금 뒤 이력을 다시 보고 차단 칸이 되었으면 `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`. 상호·대표자 건은 그대로 실행(인원 재판정 없음 — R2-m1) |
| 동시 승인 | 승인 행 잠금 — 하나만 EXECUTING |
| 재전송·회복 중복 | MASTER `admin_request` UNIQUE(`mop1.<approvalId>`)·근거 PK |
| 같은 제안 두 번 적용 | version +1 뒤 두 번째 `BUSINESS_VERSION_CONFLICT` + ADMIN 명령 행 `proposal_id` UNIQUE |
| 남의 제안에 요청 붙이기 | MASTER `PROPOSAL_REQUESTER_MISMATCH`(M-6) + ADMIN 요청 TX 확인 |
| 잠금 순서 | MASTER: admin_request → business → 하위 행 → (계약·가맹점·제안 읽기) → 근거 행. ADMIN: 승인 행 → 계정 행 → 실행 행 — 기존 부분열, 교착 없음 |

---

## 7. 시험 계획 · 8대 원칙 증거

### 7.1 기대값이 바뀌거나 옮겨지는 기존 시험 (M-3)

판정: **변경**(같은 시험, 기대값 교체 — 정책 변경 근거) / **이동**(같은 단언을 근거 경로 시험으로 옮김) / **Fixture 변경**(업무 의미 유지, 기대값 불변) / **불변**.

| 시험 | 현재 단언 | 판정 | 새 단언 | 근거 |
|---|---|---|---|---|
| `master-api/.../domain/BusinessProfileChangeSpec.kt:153` | REGISTERED 상호 변경·사유 없음 → `BUSINESS_CHANGE_REASON_REQUIRED` | 변경 | `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`(판정 순서: version → 변경 없음 → 2인 필요). 같은 Then에서 version·변경 없음 순서 단언(`:151-152`)은 불변 | D-GOV-016 ③, D-GOV-025 ④ |
| 같은 파일 `:154-156` | 사유 있으면 1인 성공(version 1, 새 상호) | 이동 | 근거 실행 Domain 함수(`applyApprovedChange`) 성공 단언으로 | D-GOV-025 ④ |
| 같은 파일 `:173` | DRAFT 등록 완료 + 상호 변경·사유 없음 → `REASON_REQUIRED` | 불변 | (이력 없는 DRAFT = 1인 + 사유) + 추가: 계약 이력 있는 DRAFT·가맹점 이력만 있는 DRAFT → `…APPROVAL_REQUIRED` | D-GOV-025 ④, D-GOV-026 ②·[해석] |
| `master-api/.../application/BusinessProfileServiceSpec.kt:230-233` | DRAFT 등록 완료 + 중요·사유 없음 → `REASON_REQUIRED`, DRAFT 유지 | 불변 | + 계약·가맹점 이력 있는 DRAFT 변형(`…APPROVAL_REQUIRED`, DRAFT 유지) 추가 | D-GOV-025 ④, D-GOV-026 ② |
| 같은 파일 `:294-300` TC-13 | REGISTERED 상호 변경·대표자 추가만 사유 없음 → `REASON_REQUIRED`, 이력 1 | 변경 | 둘 다 `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`, 이력 1·version 0 불변 | D-GOV-016 ③ |
| 같은 파일 `:300-` | 사유 있으면 성공 + 이력 사유·마스킹 대표자 ended/added | 이동 | 근거 실행 서비스 시험(MySQL)으로 — 이력에 approvalId·proposalId 추가 | D-GOV-025 ④ |
| `master-api/.../adapter/web/admin/BusinessProfileApiSpec.kt:706-709` | 사유 있음 + REGISTERED 대표자 0명 → 400 `REQUIRED` | 이동 | 제안 endpoint 시험(2인 경로에서 규칙 위반 400). 1인 경로에서는 `…APPROVAL_REQUIRED` 저장 거절(200 REJECTED) | 판정 순서(§3.3) |
| 같은 파일 `:712-716` | 중요·사유 없음 + 필수값 제거 → 저장 거절 `REASON_REQUIRED`, admin_request 1행 | 변경 | 저장 거절 `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`, admin_request 1행 | D-GOV-016 ③ |
| `master-api/.../application/BusinessProfileConcurrencySpec.kt:103-140`(`:25`, `:39`) | 같은 version 두 수정(상호 "새 상호 $winner") → 1성공·1충돌 | Fixture 변경 | 수정 내용을 일반정보(전화번호 등)로 바꿔 1인 경로 유지 — 기대값(1성공·1 `VERSION_CONFLICT`·이력 1·잠금 대기) 불변. 중요 변경 동시성은 새 시험(근거 실행 vs 1인 수정) | 업무 의미 유지 |
| `master-api/.../adapter/persistence/JpaBusinessRepositorySpec.kt:125`, `:176` | Domain `updateProfile`(대표자 교체·순서 정정, 사유 있음)로 diff 저장 검증 | Fixture 변경(**S2 정정 — Coordinator 승인 2026-11-29**) | 같은 법인 내용을 **이력 없는 DRAFT**로 만들어 1인 + 사유 경로로 저장(근거 실행 함수는 갱신 행위자 = 승인자라 `ended_by` 기대값이 바뀌므로 쓰지 않음) — 행 diff 기대값 불변 | Repository 단언 대상은 저장 방식 |
| `master-api/.../application/BusinessProfileServiceSpec.kt` m-4 단조 시각(서비스판) | REGISTERED 대표자 교체 + 사유 1인 수정 | Fixture 변경(표 밖 — Coordinator 승인 2026-11-29) | 이력 없는 DRAFT 사업자로(1인 + 사유) — 시각·종료 행 기대값 불변 | 업무 의미 유지 |
| 같은 파일 BIZ-1-7 "update failing after the diff" | REGISTERED 대표자 교체 + 사유 → diff 뒤 실패 rollback | Fixture 변경(표 밖 — Coordinator 승인 2026-11-29) | 이력 없는 DRAFT 사업자로 — rollback 기대값 불변 | 업무 의미 유지 |
| `master-api/.../application/AdminRequestFingerprintHmacSpec.kt` ③ update m1 재생 | REGISTERED 상호 변경 + 사유 1인 수정의 m1 행 재생 | Fixture 변경(표 밖 — Coordinator 승인 2026-11-29) | 일반정보(사업체 전화번호) 수정 — 재생 기대값 불변 | 단언 대상은 지문 호환 |
| `master-api/.../adapter/query/BusinessReaderSpec.kt` BIZ-2-7 상세 | REGISTERED 대표자 교체 1인 수정 뒤 상세 | Fixture 변경(표 밖 — Coordinator 승인 2026-11-29) | 제안 → 근거 실행으로 같은 변경(version 1·현재 행 동일) — 상세 기대값 불변 | 단언 대상은 조회 모양 |
| `master-api/.../domain/BusinessProfileChangeSpec.kt:186-194` m-4(S1 리뷰 뒤 추가 — Coordinator 승인) | REGISTERED 상호 변경 + 사유 1인 수정 3회로 갱신 시각 단조 확인 | Fixture 변경 | 수정 내용을 일반정보(사업체 전화번호, 사유 없음)로 — 시각·version 기대값 불변 | 업무 의미 유지(단언 대상은 시각) |
| `master-api/.../domain/BusinessProfileChangeSpec.kt:39` | 상호·구분 변경은 두 상태 모두 중요, 이관 행 첫 구분은 일반(분류만) | 불변 | 분류 규칙 불변(인원·차단은 새 판정 함수 시험) | BR-BIZ-017·310 |
| `internal-admin/.../BusinessHttpSpec.kt:297`, `:312` | 수정 본문 `registrationNumber` 400 | 불변 | + 새 승인 요청 본문에도 같은 400 | P-6 |
| `frontend/src/screens/BusinessProfileFormScreen.test.tsx:355-362` | REGISTERED 상호 변경 → 사유 필수, 사유 넣으면 update 전송 | 변경 | 버튼 '승인 요청'·근거 칸 필수, 승인 요청 API 전송(update 미호출) | D-GOV-016 ③ |
| 같은 파일 `:398-403` | 서버 `REASON_REQUIRED` → 안내 표시 | 불변 | (fixture를 DRAFT로 — 의미 유지) + 서버 `…APPROVAL_REQUIRED` → 승인 요청 전환 안내 새 시험 | D-GOV-025 ④ |
| 같은 파일 `:343`, `:370-395`, `:454-471` | 일반 변경 사유 선택·칸 표시 규칙 | 불변 | — | P-1 |
| `frontend/src/lib/businessProfile.test.ts:12-46` | `importantChange` 분류 | 불변 | + 새 함수(인원 판정·정정 판정) 시험 | 분류 규칙 불변 |
| `frontend/src/screens/BusinessesScreen.iaB5.test.tsx:82-93` | 상호 변경 사유 필요, 넣으면 사유·version과 함께 update 전송 | 변경 | 등록 완료 사업자: 승인 요청 전송 후 상세 다시 읽기·대기 배지. DRAFT 변형은 기존 단언 유지 | D-GOV-016 ③, D-GOV-025 ④ |

기대값 변경은 정책 변경(D-GOV-016 ③·D-GOV-025·D-GOV-026)에 따른 것이며 검사 통과를 위한 완화가 아니다. 각 변경 시험은 RED(현재 코드에서 실패) 기록 후 구현한다.

### 7.2 새 시험

| 계층 | 시험 | 단언 |
|---|---|---|
| MASTER Domain | BusinessCriticalChangeSpec | §1.1 판정표 전 칸(DRAFT·REGISTERED·INACTIVE·TERMINATED × 이력 없음/계약 이력만/가맹점 이력만/둘 다 × 일반/상호·대표자/구분·법인번호) — 특히 ① 이력 없는 DRAFT 구분 변경 1인 + 사유 성공(근거 없음) ② 이력 있는 DRAFT 구분 변경 차단 ③ 해지 계약만 있는 REGISTERED 구분 변경 차단 ④ 접수 건만 있는 REGISTERED 구분 변경 = 2인 ⑤ 종료 가맹점만 있는 DRAFT 상호 변경 = 2인; 섞인 저장은 가장 강한 칸; 첫 입력 일반(차단 칸 포함); 근거 실행: version 불일치·digest 불일치·요청자 불일치(M-6) 거절, **인원 재판정 거절 없음**(R2-m1 — 승인된 상호 변경은 대기 중 계약 이력이 생겨도 실행), 대기 중 이력이 생긴 구분 변경은 차단 거절; 번호 불변 |
| MASTER 통합(MySQL) | BusinessCriticalChangeServiceSpec | 제안 → 사업자·하위 행·version·이력 불변; 실행 성공 → diff·이력(approvalId·proposalId·revalidatedBy)·Outbox 같은 TX; 실행 rollback 시 사업자·근거 행 없음; 계약·가맹점·MID·권리·정산 행 수 불변; 제안 표 UPDATE·DELETE 1142 |
| MASTER 동시성 | BusinessCriticalChangeConcurrencySpec | 실행 vs 1인 일반 수정 → 하나만; 같은 approvalId 동시 재전송 → 1회 반영; **정정 실행 vs 사업자 계약 생성 동시 → 계약이 먼저면 정정 거절, 정정이 먼저면 계약 생성이 정정 뒤 값으로**(사업자 잠금 직렬화) |
| MASTER API | 근거 경로·원문 조회 | 무서명·다른 키 401, `APPROVAL_SAME_ACTOR`, 창 위반, 만료, 본문 번호 400 |
| ADMIN Domain | ApprovalRequest 판정표 | 새 종류 OWNER_SERVICE 상태만, 대상 BUSINESS 형식·version 0 |
| ADMIN 서비스 | BusinessChangeApprovalServiceSpec — **기존 가맹점 서비스와 같은 순서 표**(m-7): ① 입력 규칙 ② 세션 역할 ③ 사전 미해결 검사(MASTER 미호출) ④ MASTER 제안(TX 밖) ⑤ 요청 TX(승인 행 → 요청자 잠금 → 명령 행 → 감사) / 승인 ① 세션 역할 ② A(TX 밖, 장애 503·TOTP 불변) ③ TX1 판정표 ④ 참여자 ⑤ 확인 체크(TOTP 전) ⑥ TOTP ⑦ 요청자 재검사 ⑧ 대상 비교(INVALIDATED·MASTER 미호출) ⑨ EXECUTING ⑩ C ⑪ TX2 | 각 단계 실패의 결과·상태·감사·MASTER 호출 여부. 요청 OPERATOR만(VIEWER·APPROVER·SECURITY_ADMIN 403), 근거 없음 400, 응답 요청자 ≠ 세션 요청자 → 무결성 |
| ADMIN 회복 | BusinessChangeRecoverySpec | EXECUTION_UNKNOWN → 결정 조회 EXECUTED, 확정 불가 finding 1회, **사업자 승인 행 회복이 PAYMENT·FINANCIAL client를 호출하지 않음**(m-7) |
| ADMIN DB | AdminSchemaSpec(V17·V18) | CHECK 3819, 미해결 키 `BUSINESS:`, 기존 키 불변, 명령 행에 상호·개인정보 열 없음, runtime UPDATE·DELETE 1142 |
| 보안 | 감사·로그·지표에 사유·근거·원문 없음; **1인 경로 선택 근거: 이력 detail·이력 탭 응답에 `evidence=PRESENT|ABSENT`만(원문 문자열 부재 단언), 원문은 `business_change_evidence`에 1행·같은 TX(rollback 시 없음), 조회는 APPROVER·그 저장 요청자만(다른 OPERATOR·VIEWER 403)·조회마다 감사**(R3-m2); 원문 조회 APPROVER·요청자만·**미해결 상태에서만**(종료 상태 6종 각각 409·MASTER 미호출 — R2-m4)·열람마다 감사·속도 제한; `pendingCriticalChange` VIEWER·SECURITY_ADMIN에 null |
| 화면 | 편집 폼·상세·승인함 | §5.3 전부, 결과 불명 폼 유지 |
| ArchUnit | `BusinessRepository.update` 호출자 제한(§3.8), 근거 실행 Use Case 호출자 = 근거 controller 하나, seed 미사용 |

Fixture: `aDraftBusinessWithoutHistory()`·`aDraftBusinessWithContractHistory()`·`aDraftBusinessWithMerchantOnly()`(이관 행)·`aRegisteredBusinessWithoutHistory()`·`aRegisteredBusinessWithTerminatedContract()`·`aRegisteredBusinessWithIntakeOnly()`·`aCriticalChangeProposal(requestedBy = …)` — 업무 조건이 이름에 드러나는 Named Fixture. Fixture Monkey는 일반정보 문자열 채우기에만(인원 판정 입력·version·요청자는 명시). 모두 `src/test`.

### 7.3 8대 원칙 증거표

| 원칙 | 위험 | 증거 |
|---|---|---|
| Financial Consistency | 승인 없이 상호·대표자·계약 주체가 바뀜 | 1인 경로 거절·정정 차단 시험(§1.1 전 칸) |
| Transaction Safety | 부분 반영 | 실행 TX 하나, rollback 시험, 외부 호출 TX 밖 |
| Idempotency | 재전송 이중 반영 | `mop1` 재생·다른 바이트 409, 제안 키 재생 |
| Concurrency Control | 대기 중 수정·계약 생성과 섞임 | version 무효화, 잠금 뒤 존재 재판정, 동시성 시험(MySQL) |
| Recoverability | 응답 유실 | EXECUTION_UNKNOWN 회복 시험, 자동 미실행 확정 없음 |
| Reconciliation | 확정 불가 방치 | `OWNER_UNRESOLVABLE` finding·경보 |
| Auditability | 누가 요청·승인·대조·열람 | ADMIN 불변 행·감사(원문 열람 포함), MASTER 근거·이력 |
| Observability | 대기·불명 누적 | 승인 지표 종류 label, 회복 지표, 로그 원문 없음 |

---

### 7.4 S3 확인 메모 (S2 리뷰)

- **S2 리뷰 NIT-5 (S3 확인 항목)**: MASTER 요청 지문에 행위자가 없어, 같은 멱등 키·같은 내용이면 다른 OPERATOR에게도 남의 제안이 재생된다(기존 관례). S3 ADMIN은 제안 응답의 `requestedBy`를 세션 요청자와 대조해 다르면 그 제안에 승인 요청을 붙이지 않는다(§3.3 M-6과 같은 원칙) — 시험 포함.
- S3로 넘긴 구현 관례: 사업자 상세 advisory(`criticalChangeMode`·`subjectCorrection`)는 ADMIN 상세 해석과 같은 배포 단위로, 원문 조회 속도 제한은 ADMIN 프록시가 맡는다.

## 8. 단계 분할

| 단계 | 범위 | 게이트 | 선행 |
|---|---|---|---|
| S0 | rev 0.3 재리뷰(D-GOV-026 반영 확인 — 사용자 질문 없음) | APPROVED | — |
| S1 | MASTER Domain(분류 파생값·인원 판정·정정 판정·근거 실행 함수) + §7.1 Domain 시험 변경 | master 단위(SCOPED) | S0 |
| S2 | MASTER V26·V27, 제안·원문·상태 조회·근거 실행 API, 1인 경로 판정 전환, §7.1 MASTER 시험 | master 통합·동시성(SCOPED) | S1 |
| S3 | ADMIN V17·V18, 종류·서비스·Store·회복 분기·대기 SPI·원문 조회 프록시 | admin + 연동(SCOPED) | S2 |
| S4 | 화면 + §7.1 화면 시험 | vitest + 공유 FULL | S3 |
| S5 | 대장·명세 반영(§9), Demo(OPERATOR 요청 → APPROVER 원문 보기·대조·승인/거절, 이력 없는 DRAFT 1인 등록 정보 수정, 이력 없는 등록 완료 2인 정정, 이력 있음 차단 안내) | FULL PASS 후 DONE(D-PROC-007) | S4 |

**배포**: S2 이후 MASTER는 등록 완료 사업자의 중요 변경을 1인 경로로 받지 않는다. 그래서 **S2·S3 커밋은 main에 들어가도 Demo에 배포하지 않고, S4까지 끝난 뒤 MASTER·ADMIN을 함께 배포**한다(FLOW-2b 선례). S2~S4 사이 Demo는 이전 배포본으로 둔다.

---

## 9. 대장 변경안 (BIZ.md)

번호: 현재 최대 BR-BIZ-347. **348~372는 SITE-CHANNEL-POL-001 예약**(`docs/governance/SITE-CHANNEL-POL-001-SITE-REVIEW-CHANNEL.md` §5 — 대장 미반영 상태라 다른 작업이 쓰지 않도록 이 명세에 기록). 이 명세는 **373~400**을 쓴다. 이후 추가는 401부터(대장 반영 S5 때 재확인).

### 9.1 확정 행 (사용자 결정 원문)

| ID | 규칙 | 상태 | 출처 | 근거 |
|---|---|---|---|---|
| BR-BIZ-018 | (기존) 중요정보 1인 + 사유 + 이력 | `대체됨→BR-BIZ-373·374·375` | — | D-GOV-016 ③, D-GOV-025 ④, D-GOV-026 ① |
| BR-BIZ-373 | 등록 완료 사업자의 중요정보(범위 BR-BIZ-016·017·310) 변경은 OPERATOR 요청 + APPROVER 승인(직전 TOTP)의 2인이고 요청자 ≠ 승인자이며 MASTER도 근거를 재판정한다 | 확정 | 사용자 | D-GOV-016 ③, D-GOV-025 ④, D-ADMIN-003, D-MOPS-007 ① |
| BR-BIZ-374 | 임시저장 사업자의 수정은 정정이 아니라 등록 정보 수정이며, 계약·가맹점 이력이 없으면 사업체 구분·법인등록번호를 포함해 OPERATOR 1인 + 사유·이력으로 수정한다. 정정·승인 정책은 등록 완료 이후부터 적용한다 | 확정 | 사용자 | D-GOV-026 ①③, D-GOV-025 ④ |
| BR-BIZ-375 | 임시저장 사업자라도 사업자 계약이 있으면 중요정보 변경은 2인이다(범위 해석은 BR-BIZ-394) | 확정 | 사용자 | D-GOV-025 ④ **[유지: D-GOV-026 Coordinator 해석]** — 사용자 보고 — 이의 시 변경 대상(BR-BIZ-391과 함께) |
| BR-BIZ-376 | 일반정보 변경은 OPERATOR 1인 + 사유 선택 + 이력이다 | 확정 | 사용자 | D-GOV-016 ③ |
| BR-BIZ-377 | 승인 실행 전까지 사업자 정보는 바뀌지 않는다 | 확정 | 사용자 | D-GOV-016 ③ |
| BR-BIZ-378 | 승인 요청 뒤 사업자가 바뀌면 그 요청은 무효다 | 확정 | 사용자 | D-ADMIN-003 |
| BR-BIZ-379 | 중요정보 변경의 요청·승인·반려와 변경 전후를 보존한다 | 확정 | 사용자 | D-GOV-016 ③ |
| BR-BIZ-380 | 사업자 정보 변경은 기존 거래·정산·계약 이력을 소급 변경하지 않는다 | 확정 | 사용자 | D-GOV-016 ③ |
| BR-BIZ-381 | 사업자 중요정보 승인 정책은 Demo·TEST·LIVE가 같다 | 확정 | 사용자 | D-GOV-016 ③ |
| BR-BIZ-382 | 모든 중요정보 변경에 기존 형식 업무 근거 참조가 필수다(임시저장 1인 등록 정보 수정은 BR-BIZ-391 참조) | 확정 | 사용자 | D-GOV-025 ① |
| BR-BIZ-383 | 재검증은 승인자가 근거와 변경 후 대표자·상호·사업자등록번호를 대조했다는 필수 확인이고 승인자·시각을 기록하며, 진위확인 상태는 바꾸지 않고 외부 진위확인 연동은 LIVE 전 조건이다 | 확정 | 사용자 | D-GOV-025 ① |
| BR-BIZ-384 | 계약·가맹점 이력이 없는 등록 완료 사업자의 기존 값 있는 사업체 구분 변경·법인등록번호 변경·삭제는 2인 승인 정정이며, 첫 입력은 허용한다 | 확정 | 사용자 | D-GOV-025 ②, D-GOV-026 ③ |
| BR-BIZ-385 | 계약·가맹점 이력이 있으면 임시저장 여부와 관계없이 사업체 구분·법인등록번호를 직접 변경할 수 없다(첫 입력은 허용) | 확정 | 사용자 | D-GOV-026 ④ |
| BR-BIZ-386 | '계약·가맹점 이력 있음'은 사업자 계약(해지·만료 포함)이나 가맹점(종료 포함)이 한 번이라도 있었음을 뜻하고, 계약 접수 건만 있으면 이력 없음이다. BR-BIZ-325의 '사업자 계약 있음'과 다른 기준이다 | 확정 | 사용자 | D-GOV-026 ② |
| BR-BIZ-387 | 실제 사업자 주체 변경은 신규 사업자 등록 + 계약 절차로 처리한다 | 확정 | 사용자 | D-GOV-026 ⑥, D-GOV-016 ③ |
| BR-BIZ-388 | 이관 데이터의 식별정보 오류는 사업자 수정이 아니라 별도 데이터 정정 절차로 처리한다 | 확정 | 사용자 | D-GOV-026 ⑤ — MIGRATED-ID-CORRECTION-001(이 명세 범위 밖) |
| BR-BIZ-389 | 중요·일반이 섞인 한 저장은 통째로 승인 뒤 반영하고, 승인 대기 중 그 사업자의 다른 수정·등록 완료는 막으며 철회·반려·만료 뒤 허용한다 | 확정 | 사용자 | D-GOV-025 ③ |

사업자등록번호 불변은 새 행 없이 BR-BIZ-004 근거 열에 D-GOV-016 ③을 더한다(§9.3).

### 9.2 제안 행 (Coordinator 해석·구현 관례 — D-GOV-005 ④ 일괄 확인 대상)

| ID | 규칙 | 상태 | 출처 | 근거 |
|---|---|---|---|---|
| BR-BIZ-390 | '법인명'은 법인 사업자의 상호다 | 제안 | 적용 | [해석] D-GOV-016 ③ |
| BR-BIZ-391 | 임시저장 1인 등록 정보 수정에서는 업무 근거 참조가 선택이고(사유·이력 규칙은 BR-BIZ-016 그대로) 승인자 대조 확인은 없다 | 제안 | 적용 | [해석] D-GOV-026 ① '등록 정보 수정' 정의(D-GOV-026 원문의 Coordinator 해석, R2-M1) — 사용자 보고 — 이의 시 변경 대상(BR-BIZ-375와 함께). 선택 근거 원문 저장·조회는 2인 경로와 같음(R3-m2) |
| BR-BIZ-392 | 대조 확인은 대표자 건만이 아니라 2인 경로의 모든 사업자 중요정보 승인에 필수다 | 제안 | 적용 | [해석] D-GOV-025 ① |
| BR-BIZ-393 | 구분·법인번호 차단 안내는 '신규 사업자 등록 + 계약 절차'와 '입력 오류는 별도 데이터 정정 절차'로 표시하고, 새 재심사 기능은 만들지 않는다(D-GOV-025 ② '계약 재심사'를 D-GOV-026 ⑥으로 구체화) | 제안 | 적용 | [해석] D-GOV-025 ②, D-GOV-026 ⑤⑥ |
| BR-BIZ-394 | BR-BIZ-375의 '사업자 계약이 있으면'은 BR-BIZ-386의 계약·가맹점 이력 기준으로 읽어, 가맹점 이력만 있는 임시저장 사업자의 중요정보 변경도 2인이다 | 제안 | 적용 | [해석] D-GOV-025 ④ + D-GOV-026 ②(D-GOV-026 원문의 Coordinator 해석) |
| BR-BIZ-395 | 2인 대상 중요 변경과 등록 완료는 한 요청으로 할 수 없다 | 제안 | 적용 | [구현 관례] §3.3 |
| BR-BIZ-396 | 사업자 중요정보 승인에는 등록자 규칙이 없다(요청자 ≠ 승인자만) | 제안 | 적용 | D-ADMIN-003, 선례 Q-MC-10 |
| BR-BIZ-397 | 변경 제안 원문은 MASTER에만 두고 ADMIN 승인 기록에는 상호·개인정보 원문을 두지 않으며, 원문은 APPROVER와 그 요청 요청자만, 승인 요청이 미해결일 때만, 열람마다 감사·속도 제한으로 본다 | 제안 | 적용 | D-ADMIN-005, D-BIZ-006 ②, D-GOV-003 ④, Coordinator 결정(R2-m4) |
| BR-BIZ-398 | 제안을 만든 요청자와 승인 요청자가 다르면 실행을 거절한다 | 제안 | 적용 | [구현 관례] M-6 |
| BR-BIZ-399 | 승인 실행 때 인원은 다시 판정하지 않고, 대기 중 계약·가맹점 이력이 생겨 차단 대상이 된 구분·법인번호 변경만 거절한다(대기 중 계약·가맹점 등록은 막지 않음) | 제안 | 적용 | [해석] D-GOV-026 ④, R2-m1 |
| BR-BIZ-400 | 승인 대기 표시는 사업자 조회 권한(OPERATOR·APPROVER)에게 상태·요청 시각만 보인다 | 제안 | 적용 | [구현 관례] 가맹점 선례 |

### 9.3 그 밖

- BR-BIZ-017: "구분 변경은 중요정보" 유지 + "기존 값 구분 변경의 처리는 BR-BIZ-374·384·385" 참조 추가.
- BR-BIZ-004 근거 열 D-GOV-016 ③ 추가. BR-BIZ-016 검증 열 TC-13 변경 기록.
- BIZ.md 진단 (f)-8·PC-72 '중요정보 1인 처리(018)' → 해소. BUSINESS-PROFILE-001 §3.3 TBD-07 행 → "확정 — D-GOV-016 ③·D-GOV-025·D-GOV-026, BIZ-CRIT-APPROVAL-001", TC 매핑(:611) 갱신. BASELINE-INDEX 대체 표 행(TBD-07).
- BR-FIN-105 변경 없음. 미결 행 없음.

---

## 10. 사용자 결정 필요 질문

**없음.** Q-BCA-01~04는 D-GOV-025, Q-BCA-05·06은 D-GOV-026으로 답을 받았다. 남은 해석·구현 관례는 §9.2 제안 행(일괄 확인 대상)이다.

Coordinator 참고(질문 아님 — 후속 카드 착수 때 확인): 이관 행이 아닌 사업자에서 같은 사업자등록번호로 구분·법인번호가 잘못 저장되고 이력까지 생긴 경우의 정정 경로는 지금 없다(BR-BIZ-385 차단, BR-BIZ-003 때문에 같은 번호 신규 등록 불가). MIGRATED-ID-CORRECTION-001 범위에 넣을지, TBD-04(번호 정정)와 묶을지는 그 카드 명세에서 정한다.

---

## 11. 하지 않은 것

- 소스·시험·migration·대장·DECISIONS·questions·tasks 수정, 커밋 — 이 문서만 고쳤다.
- 지급 계좌 변경 기능(MASTER-PAYOUT-ACCOUNT-001).
- 외부 진위확인·KYC·첨부 파일(TBD-06·09, FLOW-4 이후).
- 이관 데이터 식별정보 정정 절차 — 후속 카드 **MIGRATED-ID-CORRECTION-001**(D-GOV-026 ⑤). 이 명세는 이관 행도 §1.1 판정표 그대로 적용한다(이력 있으면 차단).
- 사업자등록번호 정정(TBD-04), 같은 번호 재등록 운영 처리, DB 수준 열 갱신 금지(trigger·열 권한).
- "계약 당시 상호" 표시.
- 범용 승인 프레임워크·가맹점 승인 서비스 리팩터링.
- 승인 유효 시간·실행 창·원문 조회 속도 제한의 운영값(LIVE Gate).
- 리뷰·게이트·Demo 실행.

---

## 12. 리뷰 대응표

| 지적 | 반영 위치 |
|---|---|
| M-1 승인자 원문 대조 수단 | §3.5, §5.1 원문 조회, §5.2 프록시, §7.2 보안 |
| M-2 DRAFT 처리 | D-GOV-025 ④·D-GOV-026 → §1 P-3·P-3a, §1.1, §9.1 BR-BIZ-374·375 |
| M-3 기대값 바뀌는 시험 | §7.1 표 전부 |
| M-4 근거 필수 범위 통일 | P-4, §3.5, §5.2, BR-BIZ-382(모든 중요정보 — DRAFT 1인은 BR-BIZ-391)·391(DRAFT 1인 선택) |
| M-5 ADMIN 상호 저장 금지·digest HMAC | §3.2, §3.1 V17 열 목록 |
| M-6 제안 요청자 = 근거 요청자 | §3.3, §6, §7.2, BR-BIZ-398 |
| m-1 A 단계 version·상태만 | §3.3, §5.1 `approval-state` |
| m-2 대기 표시 OPERATOR·APPROVER만 | §5.2, §7.2 보안 |
| m-3 target_security_version 0·base_version 명령 행 | §3.1 |
| m-4 MASTER operation CHECK·ADMIN 명령 표 권한 | §2.3, §3.1(V26·V27·V17·V18) |
| m-5 제안 이력은 실행 시에만·미해결 사전 검사 | §3.3, §3.7 |
| m-6 대장 확정/제안 분리(미결 행은 D-GOV-026으로 없어짐) | §9.1·9.2 |
| m-7 같은 순서 시험 표·회복 분기 위치·PAYMENT 미도달 | §3.1, §7.2 |
| m-8 아키텍처 관례(Store·Entity 분리·INSERT 전용·Reader·의존성·ArchUnit) | §3.1, §3.8 |
| m-9 S2~S4 Demo 미배포 | §8 |
| m-10 348~372 예약 기록·이후 번호 | §9 머리말(373~400, 다음 401) |
| (Coordinator 요청 — 1차 리뷰 번호 아님) Q-BCA-02 '2인 정정' 판정 시점·잠금·같은 틀 | §3.6, §6, §7.2 동시성 |

**재리뷰(rev 0.2 → 0.3)**

| 지적 | 반영 위치 |
|---|---|
| R2-M1 DRAFT 1인 수정에 근거·대조 적용 여부 | P-3·P-4·P-5, §1.1, §3.5, §5.3, BR-BIZ-391(근거 선택·대조 없음 — D-GOV-026 ① 근거 해석). D-GOV-025 ④는 BR-BIZ-375 유지, 가맹점 이력 포함은 BR-BIZ-394 [해석] |
| R2-m1 실행 때 인원 재판정 금지 | §3.3(흐름·실행 규칙), §4.1 소유 거절 code, §6, §7.2, BR-BIZ-399 |
| R2-m2 용어 '계약·가맹점 이력 있음' vs BR-BIZ-325 '계약 있음' | 머리말 용어, §1.1, §2.5, BR-BIZ-386 |
| R2-m3 §12 대응표 번호 | 이 표(1차 리뷰 MINOR는 m-1~m-10, Q-BCA-02 설계 행은 Coordinator 요청으로 구분). 작업 카드 allowedWritePaths는 Coordinator가 수정 완료 |
| R2-m4 원문 조회는 미해결 상태만 | §5.1·§5.2, §7.2 보안, BR-BIZ-397 |
| D-GOV-026 통합 기준 | P-3·P-3a·P-7·P-7a~c, §1.1 판정표, §2.5, §3.6, §9.1 BR-BIZ-374·384~388, §10 질문 없음, §11 MIGRATED-ID-CORRECTION-001 |

**rev 0.3a (재리뷰 APPROVED 뒤 MINOR)**

| 지적 | 반영 위치 |
|---|---|
| R3-m1 BR-BIZ-375·391 상태 | §9.1 BR-BIZ-375 근거 열 '[유지: D-GOV-026 Coordinator 해석]', BR-BIZ-375·391 모두 **사용자 보고 — 이의 시 변경 대상** |
| R3-m2 1인 경로 선택 근거 원문 비표시 | §3.1 V26·V27(`business_change_evidence`), §3.5, §5.1 조회 endpoint, §7.2 보안 시험, BR-BIZ-391 근거 열 — Coordinator 결정(가역) |
| R3-m3 BR-BIZ-382 원문 문장 | §9.1 BR-BIZ-382 '모든 중요정보 변경에 근거 참조 필수' + BR-BIZ-391 참조만 |
