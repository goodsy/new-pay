# BUSINESS-PROFILE-001 사업자 기본정보 관리 명세 (rev 0.2.9)

- **rev 0.2.9** (2026-11-18, Implementer — BIZ-4 독립 리뷰 REVIEW_PASS 조건부(BLOCKER·MAJOR 없음)의 MINOR-1~3·NIT-1~6을 Coordinator 지시로 반영. MINOR-2는 Coordinator 선택 (a). 정책·결정·기대 완화 없음 — §18).
- **rev 0.2.8** (2026-11-18, Implementer — BIZ-4(화면 + C-27 전환) 구현 중 정한 구현 세부·정정 E-28~E-45 기록. 기준 HEAD `1f8e530`. 업무 정책·사용자 결정·시험 기대 완화 없음 — §18. E-41은 Coordinator 결정).
- **rev 0.2.7** (2026-11-18, Implementer — BIZ-3 독립 리뷰 REVIEW_PASS 조건부(BLOCKER·MAJOR 없음, E-17~E-27 수용)의 MINOR-1·2·NIT-1~5를 Coordinator 지시로 반영. 정책·결정·기대 완화 없음 — §18).
- **rev 0.2.6** (2026-11-18, Implementer — BIZ-3 구현 중 정한 구현 세부·정정 E-17~E-27 기록. 기준 HEAD `5dab2e7`. 업무 정책·사용자 결정·시험 기대 완화 없음 — §18).
- **rev 0.2.5** (2026-11-17, Implementer — BIZ-2 독립 리뷰 REVIEW_PASS 조건부(BLOCKER·MAJOR 없음)의 MINOR-1~5·NIT-1~3을 Coordinator 지시로 반영. 정책·결정·기대 완화 없음 — §18).
- **rev 0.2.4** (2026-11-17, Implementer — BIZ-2 구현 중 정한 구현 세부·정정 E-9~E-16 기록. 기준 HEAD `2821dc6`. 업무 정책·사용자 결정·시험 기대 완화 없음 — 변경 목록은 §18).
- **rev 0.2.3** (2026-11-17, Implementer — BIZ-1 독립 리뷰 REVIEW_PASS 조건부의 MINOR m-1~m-4·NIT n-1~n-5를 Coordinator 지시로 반영. m-1은 Coordinator 결정(E-8 처리). 변경 목록은 §18).
- **rev 0.2.2** (2026-11-17, Implementer — BIZ-1 구현 중 발견한 정정 E-1~E-8 반영. 기준 HEAD `cbfed46`. 업무 정책·사용자 결정 변경 없음 — 변경 목록은 §18).
- **rev 0.2.1** (2026-11-17, Implementer — rev 0.2 재리뷰 REVIEW_PASS 조건부의 조건 r-1~r-7을 Coordinator 지시로 반영. 기준 HEAD `6ccb310`(MC-4 커밋). 변경 목록은 §18).
- **rev 0.2** (2026-11-17, Implementer — 독립 리뷰(rev 0.1) MAJOR M-1~M-7·MINOR m-1~m-10·NIT N-1~N-4 반영 + 사용자 답 Q-BIZ-5·Q-BIZ-6 → **D-BIZ-005·D-BIZ-006** 적용. 코드·migration·빌드·커밋 없음, 자기 완료 판정 아님 — Reviewer 재확인 대상). 변경 목록은 §18.
- rev 0.1 (2026-11-17, Implementer — 명세 초안).
  기준(rev 0.2.1 — r-5): main, HEAD **`6ccb310`**(MERCHANT-CODE-001 MC-4 커밋 — MASTER migration 없음, PAYMENT V36). rev 0.1·0.2의 줄 번호는 `373ca6d` + MC-4 작업 트리 기준으로 조사했고 그 트리가 `6ccb310`으로 커밋됐다. BIZ-1 착수 시 줄 참조를 다시 확인한다.
  사용자 결정 **D-BIZ-001~006**(Q-BIZ-1~6, 2026-11-17) 적용, D-MOPS-006 ③ 변경 반영(D-BIZ-003), MERCHANT-BIZ-LOOKUP-001 수용 기준 "권한 OPERATOR"·응답 범위(D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001) 변경 반영(D-BIZ-006), **MERCHANT-BIZ-LOOKUP-001을 이 작업에 합침**(D-BIZ-004), C-1~C-34 권장. 열린 사용자 질문 없음.
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-BIZ-n** = 사용자 질문. **C-n** = Coordinator 가역 선택(권장안 — Reviewer 확인 대상). **L-n** = 알려진 위험·한계. **TBD-nn** = PC-BIZ-POL-001 §10 미확정(구현하지 않음).
- 근거(Source of truth):
  - `docs/baseline/PayCore-사업자관리-업무정의-정책서-v1.1.md`(PC-BIZ-POL-001, sha256 `be4876ed7bcb11161f4beede1912a1ade83cd246450980b4137910bfc34e70aa`) — **[확정]은 구속**, [제안]은 설계 권장, [TBD]는 구현 금지.
  - `docs/baseline/PayCore-사업자관리-개발지침서-v1.1.md`(PC-BIZ-DEV-001, sha256 `abc047d916ec085bf664abf4992eb7511d31557458226dcc8726cb534a0fb751`) — 경로·DTO·표 이름은 "기존 PayCore 규칙 우선"(지침서 §5·§6 머리).
  - CLAUDE.md, `docs/TECHNOLOGY-AND-PRINCIPLES.md`(8대 원칙·JPA/jOOQ/BOM/Kafka·Redis), ARCHITECTURE-CONVENTIONS §9.1·§14, 테스트 아키텍처 Test Fixture Policy.
  - 결정: D-MD-004·006·008, D-MOPS-002·003·004·005·006·010, D-ADMIN-UI-003·004, D-BIZLOOKUP-001, D-MCODE-003, D-DEMO-001, **D-BIZ-001~006**.
  - 선행 명세: MERCHANT-OPS-001(조회·oracle 방어), MERCHANT-OPS-002(§3·§4 등록 명령 틀, §9.29 HMAC), MERCHANT-BIZ-LOOKUP-001 rev 0.3(합침 대상 — §3~§6 설계를 이 문서 §7.3이 대체), MERCHANT-CODE-001(§1.5 배포 경계 교훈).
- **기존 IMS는 분석·참조·연동·이관하지 않는다**(PC-BIZ-POL-001 §1-1 [확정]). 이 문서는 PayCore 저장소만 조사했다.

## 0. 한눈에

| 항목 | 결정·제안 |
|---|---|
| 무엇 | 사업자(Business)를 한 번 등록하고 목록·검색·상세·수정·중복확인·변경 이력을 관리하는 MASTER 기준정보. 임시저장(`DRAFT`)과 등록 완료(`REGISTERED`) |
| 상태 축 | **두 축**(D-BIZ-001): 기존 `lifecycle` ACTIVE/TERMINATED(D-MD-006, 불변) + 새 `registration_status` DRAFT/REGISTERED/INACTIVE. 진위확인 `verification_status`는 별도 열, 이번에는 **`NOT_CHECKED`만**(DB CHECK) |
| 기존 데이터 | 기존 사업자(번호+상호만)는 migration으로 **DRAFT**, `business_type` NULL(D-BIZ-001, C-3). BIZ-1~BIZ-4 사이 옛 등록 경로로 만든 사업자도 같은 형태(DRAFT·구분 NULL — D-BIZ-002 적용 시점 = BIZ-4 전환, M-7) |
| 가맹점 등록 | **새** 가맹점 등록은 `REGISTERED` 사업자만(MASTER 새 거절 `BUSINESS_NOT_REGISTERED`, BIZ-4 전환부터). 기존 가맹점·계약·MID·Snapshot 무영향(D-BIZ-001) |
| DRAFT 최소 | 사업체 구분 + 사업자등록번호(10자리) + 상호(D-BIZ-002 — 새 경로 즉시, 전체 강제는 BIZ-4 전환). 만료·삭제는 TBD-05(구현 없음). 번호 오입력 DRAFT도 그대로 두고 등록 화면에 "저장 후 번호 변경 불가" 안내(D-BIZ-005) |
| 중복 | DB UNIQUE가 권위. 중복이면 조회 권한자에게 **기존 사업자 상호·마스킹 번호·상세 링크**(D-BIZ-003 — D-MOPS-006 ③ 변경). 모든 시도 감사·공유 등록번호 버킷 |
| 하위 정보 | 등록정보 1:1(`business_registration`), 대표자·업태/종목·주소·등록 담당자는 **유효기간 행**(종료 = `effective_to` 기록, 물리 삭제 없음 — 교체 이력 보존) |
| 수정 | 낙관적 잠금(`business.version`), **중요정보 변경 시 사유 필수**(상호·대표자·개업일·법인등록번호·구분 — 기존 값이 있던 항목의 변경·삭제, REGISTERED 사업자는 현재 대표자 집합의 모든 변경), 변경 전후(마스킹) 이력 = MASTER `master_change_history`. 사업자등록번호는 생성 후 변경 불가(TBD-04·D-BIZ-005). 등록 완료는 상태 전이 자체가 변경(같은 내용도 성공 — M-1) |
| 권한 | 조회(목록·검색·상세·이력·번호 조회) = **OPERATOR·APPROVER**, 등록·수정·등록 완료 = **OPERATOR**(1인 — **D-BIZ-006 ①**, D-MOPS-004·006 ①). 비활성화(P1) 없음 |
| 개인정보 | 사업자등록번호는 목록·상세·감사·로그·이력 **모두 마스킹**(`123*******`). 대표자·담당자 이름·이메일·전화·주소·법인등록번호는 **상세·수정 화면에서 조회 역할에 원문**(**D-BIZ-006 ②** — 상세 열람마다 감사 `BUSINESS_VIEWED` + 조회 창), 목록·이력·감사·로그는 마스킹 또는 없음(§9) |
| API | ADMIN `/api/businesses…`, MASTER `/internal/v1/admin/businesses…`(서명 v1·`ADMIN_SERVICE`, 명령 ID는 서명 본문 — 기존 C-2). 목록 = keyset cursor(안정 정렬, limit ≤ 50). 번호 조회는 **POST 본문**(URL·로그에 번호 없음), 응답에 `registrationStatus`(D-BIZ-006 ③). 명령 지문 = 기존 `m1:` SHA-256(C-9 — HMAC 전환은 B3-3a 일괄, L-13) |
| 화면 | "사업자·가맹점" 메뉴에 **사업자 조회** 탭(목록·상세·이력·수정) + **사업자 등록** 탭(기존 화면을 새 폼으로 교체, 섹션 1~6·8). 가맹점 등록 ①은 새 번호 조회 사용 |
| 제외 | P1(증빙 F-009, 진위확인 F-010, 사용중지 F-011), AML/KYB·계약·MID·정산·본점/지점 판정, 기존 IMS 연동 일체 |
| migration | MASTER **V15**(schema)·**V16**(privilege). ADMIN·PAYMENT·FINANCIAL migration 없음 |
| 배치 | BIZ-1 MASTER Domain·DB·Application(Use Case `BusinessProfileService` — M-3) → BIZ-2 MASTER web(endpoint·parser·JSON·골든·Demo seed) → BIZ-3 ADMIN API → BIZ-4 화면 + **전환**(새 가맹점 등록 REGISTERED 강제·옛 사업자 등록 경로 제거 — 한 배포 단위, C-27). 선행: MERCHANT-CODE-001 MC-4 완료·커밋(D-BIZ-004). **막힌 배치 없음**(열린 사용자 질문 없음) |

## 1. 현재 상태(코드 확인 — HEAD `6ccb310`, MC-4 커밋 = 조사 당시 작업 트리)

### 1.1 MASTER 사업자 저장·Domain
- 표 `business`(`V1__master_core.sql:20-34`): `business_id`·`registration_number CHAR(10)` 평문(UNIQUE `uk_business_registration_number` L29, CHECK `ck_business_registration_number` L31)·`display_name VARCHAR(100)`·`lifecycle`·`created_at/by`·`version`. `merchant.business_id` FK(L49), 계약 당사자·Scope 복합 FK도 `business`를 가리킨다.
- Domain `Business.kt:6-34`: 필드 6개(번호·상호·생명주기·생성), `requireActive()` L14-16, `register(...)` L21-22(항상 `Lifecycle.ACTIVE`), `toString` 마스킹 L18. 값 객체 `BusinessRegistrationNumber.kt:9-22`(10 ASCII 숫자, 정규화 없음, 체크섬 없음, `masked()` 앞 3자리), `DisplayName.kt:4-26`(1~100 code point, 앞뒤 공백·제어문자 거절), `Lifecycle.kt:7`.
- JPA `JpaBusinessEntity.kt`: `registration_number` `updatable = false`(L28 — 번호 불변의 adapter 측 방어), `display_name`·`lifecycle` 갱신 가능, `@Version`(L48-51), 연관 매핑 금지 관례(L17). `JpaBusinessRepository.kt`: `insert`·`lock`(FOR UPDATE)·`findIdByRegistrationNumber`·`find`. Port `BusinessRepository.kt:6-15`. 기존 `lock` 호출처 = `MerchantOnboardingService` L129(가맹점 등록)·L185(계약 당사자)·L204·L328 — 모두 Root 필드(생명주기)만 쓴다. 권리 원천 `JpaEntitlementSourceStore.kt:42-43`도 `entityManager.find(JpaBusinessEntity)` → `MasterPersistenceMapper.toBusiness`로 Root만 만든다 → 하위 행을 읽지 않는 Root 전용 경로를 유지한다(m-3, §5.5).
- 1062 분류 체계: `StoreConstraint(businessKey)`(`application/required/PersistenceFailure.kt:23`) — `true` = 업무 유일키(실행기 새 TX 재판정), `false` = 서버 생성 ID·결함(`IDENTIFIER`·`OUTBOX_*`·`OTHER`). CHECK 위반(3819)은 분류하지 않고 결함으로 던진다(`PersistenceFailures.kt` KDoc).
- 권한 `V2__master_runtime_privileges.sql`: `business` = `SELECT, INSERT, UPDATE`(표 단위 — FOR UPDATE 잠금용). **MASTER DB에는 열 단위 UPDATE procedure가 없다**(`docker/mysql/init/01-owned-databases.sh:248·256·271` — payment·financial·admin만 `runtime_column_privilege_sql` 설치) → C-11.
- 1062 분류: `PersistenceFailures.kt:74` `business.uk_business_registration_number` → `StoreConstraint.BUSINESS_REGISTRATION_NUMBER`(업무 유일키 → 실행기 새 TX 재판정).

### 1.2 명령·멱등·이력 경로
- 사업자 등록 Use Case `MerchantOnboardingService.registerBusiness`(L93-119): 실행기 `AdminCommandExecutor.execute`(MASTER 로컬 TX 하나 — `admin_request` 멱등·업무 변경·`master_change_history`·Outbox 같은 TX), 지문 `[registrationNumber, displayName]` 원문(L95), 중복 → `BUSINESS_REGISTRATION_NUMBER_DUPLICATE` + reference = 기존 businessId(L99-101), 이력 detail 마스킹 번호(L111-116), Outbox `BusinessRegistered`(payload businessId·lifecycle).
- 지문 `AdminCommandExecutor.fingerprintOf(operation, fields: List<String?>)`(L415-424) = 무염 SHA-256(`m1:` — 길이 접두 `n:값|`, null `~|`). 현재 MASTER 관리 명령 전부가 이 지문을 쓴다. **D-MOPS-010 ②(사업자 등록 지문 HMAC)는 아직 구현되지 않았다**(MERCHANT-OPS-002 §9.29 B3-3a — hmac1 필드 단위 설계로 모든 명령을 함께 전환, tasks.json L7312 "MERCHANT-BIZ-LOOKUP-001 뒤") → C-9·L-13.
- Outbox: 실행기 `AdminCommandExecutor` L377이 모든 payload에 `occurredAt`을 자동 추가한다(N-1).
- 가맹점 등록 `registerMerchant`(L121-): `businesses.lock` L129 → `Merchant.register`(`Merchant.kt:87-91` — `business.requireActive()`만). → BIZ-4에서 `requireRegistered()` 추가(§4.4).
- MASTER 등록 채널 `MerchantOperationsController.kt`: `POST /internal/v1/admin/merchant-operations/businesses`(L43-46), **모든 거절에서 reference 키를 버림**(KDoc L22 — C-12, D-MOPS-006 ③). 해석 `MerchantOperationsRequestParser.kt:57·72-74`(필드 = 공통 3 + `registrationNumber`·`displayName`, 위반 400 `REQUEST_MALFORMED` — 필드·값 미반환).
- 조회 채널 `MerchantOpsController.kt:22-63`(`/internal/v1/admin` — `merchant-searches` POST, `merchants/{id}` GET, `merchants/{id}/history/after/{seq}` GET, 조회 permit `guarded`). 목록 keyset 선례 `JooqMerchantDirectoryReader.kt`(id 오름차순 `> after`, `limit + 1`, SQL 마스킹 `MasterSqlText.maskedRegistrationNumber()`). **가맹점 0개 사업자는 이 검색에 나오지 않는다**(`JOIN business`).
- `master_change_history`(`V1:254-266`): `detail JSON`, 색인 `(aggregate_type, aggregate_id)`. Outbox `BusinessRegistered` 소비자: 권리 Relay는 `entitlement_issue` 경로라 이 이벤트를 전달하지 않는다(확인 — `EntitlementSnapshotPublisher`·`EntitlementRelayService`).
- Demo seed `DemoMerchantSeedService.kt:160`(단계 1 `REGISTER_BUSINESS` → `registerBusiness`), `DemoSeedStep.REGISTER_BUSINESS`(`DemoMerchantSeedUseCase.kt:78`, operation `RegisterBusiness`), 값 `DemoMerchantSeedValues.kt:35-36`(번호 `0000000000`, 상호 "DEMO 사업자 (운영값 아님)"), 선확인 `recordedSuccess`(C-23 — 같은 operation·키의 SUCCEEDED 기록이면 호출하지 않음).

### 1.3 ADMIN·화면
- ADMIN 등록 `POST /api/merchant-registrations/businesses`(`MerchantRegistrationController.kt:29`) → `MerchantRegistrationService`(OPERATOR L61, 형식 L62-65, 등록 창 L66-69, **등록번호 버킷 예약** L70-77, 시도 감사 `REQUESTED` L80, MASTER L85, 중복·불명은 확정 L177-182, 결과 감사 L97-101), 감사 action `MERCHANT_BUSINESS_REGISTER`, detail `sub=…;brn=123*******`, 성공 targetRef = 만든 businessId, 중복 targetRef 없음(D-MOPS-006 ③). MASTER 키 `mo1.<계정 ULID>.<submissionId>`(L124).
- MASTER 명령 본문 `MasterCommand.kt` — 값은 **문자열·문자열 배열만**(`init` L15-18) → 중첩 객체(대표자 목록 등)를 실을 수 없다 → C-17.
- 등록번호 버킷 `RegistrationNumberBudget.kt:7-28`(조회·등록 공유, Demo 10/분, 인스턴스 메모리). 창 설정 `MerchantOpsLimits.kt`(`lookupPerMinute` 가맹점 목록·상세·이력 공용, `registrationNumberSearchesPerMinute`, `registrationsPerMinute`), 값 `application.yml:142-147`(Demo 30·10·20 — 운영값 아님, 기본값 없음), 공용 창 구현 `ActorMinuteWindows`.
- 감사 `AdminAuditEvent.kt`: detail = 고정 `key=value`(L68 — 값 문자 `[A-Za-z0-9_:.,*-]`, 한글·공백 불가), **자유 문구 `reason`은 `UNMASK_REQUESTED` 행만**(L45-47) → 변경 사유는 ADMIN 감사에 둘 수 없다(C-13). action 열 CHECK 없음 → ADMIN migration 불필요. `TARGET_REF`는 `biz_` 허용(L65).
- 역할 `AdminAction.kt:7-19`(`VIEW_MASKED` 4역할, `REQUEST_OPERATION` OPERATOR), `AdminRole`(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN·UNMASK).
- 화면: 메뉴 `navigation.ts:80-81`(사업자·가맹점 → 가맹점 조회·사업자 등록·가맹점 등록), 사업자 등록 탭 OPERATOR만(L51). `BusinessRegistrationScreen.tsx`(번호+상호 두 칸, 중복 고정 문구 KDoc L21, 성공 시 `BusinessRef`·"이 사업자로 가맹점 등록" L96-139), `RegistrationStep.tsx:21`(거절 문구 표), `RegistrationNumberInput.tsx`(하이픈 자동 표시·숫자만 반환). 가맹점 등록 ① `MerchantRegistrationScreen.tsx` `BusinessPicker` **L244**(MERCHANT-BIZ-LOOKUP-001 rev 0.3의 L173은 옛 위치), `api.merchantSearch` **L267**, KDoc 한계 문장 L28 근처. API `adminApi.ts:823`(`merchantSearch`)·`:828`·`:907`(`registerBusiness` → `/api/merchant-registrations/businesses`). CSP `SecurityConfig.kt:172` `style-src 'self'`(inline style 금지 — `test/noRuntimeStyles.test.tsx`), DOM 검사 도구 `test/domScan.ts`.

### 1.4 migration 번호(재확인 — 파일 목록)
- MASTER schema `V1·V3·V5·V7·V9·V11·V13`(최신 `V13__merchant_code_assignment.sql`), privilege `V2·V4·V6·V10·V14`(최신 `V14__merchant_code_assignment_privileges.sql`). **V8·V12는 의도적으로 비움**(MERCHANT-OPS-002 C-42 — 빈 번호 뒤 추가 금지, outOfOrder 없음). **V15·V16 미사용** → 이 작업 **V15(schema)·V16(privilege)**.
- MC-4 작업 트리는 PAYMENT V36만 추가(MASTER migration 없음 — 확인). BIZ-1 착수 시 다른 작업이 V15를 먼저 쓰면 다음 홀수/짝수 쌍으로 옮긴다(관례).
- ADMIN: 감사 action·targetRef 형식 변경 불필요 → **migration 없음**.

### 1.5 결정·지침과 기존 코드의 어긋남(이 명세가 정한 처리)

| # | 어긋남 | 처리 |
|---|---|---|
| X-1 | 기존 사업자에 `business_type`이 없다 ↔ D-BIZ-002 DRAFT 최소 = 구분 포함 | migration 행만 `business_type` NULL 허용(DRAFT 한정 CHECK). 새 생성·수정은 구분 필수. 그 행을 처음 수정할 때 구분 입력 강제(C-3) |
| X-2 | MASTER C-12(모든 거절 reference 제거)·화면 고정 문구(`BusinessRegistrationScreen.tsx` KDoc L21) ↔ D-BIZ-003 | 새 생성 endpoint의 **중복 거절만** 기존 사업자 요약 반환(C-14). 다른 거절의 reference 제거는 유지 |
| X-3 | MERCHANT-BIZ-LOOKUP-001: 수용 기준 "권한 OPERATOR"·응답 = 상호·마스킹·키만(D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001) ↔ 이번 조회 역할 OPERATOR·APPROVER·등록 상태 필요 | **사용자 결정 D-BIZ-006 ①③으로 해소**: 합친 번호 조회 = OPERATOR·APPROVER, 응답에 `registrationStatus` 추가(C-15) |
| X-4 | 지침서 목록 표시 "Business ID" ↔ D-ADMIN-UI-004 ③(`biz_` 화면 미표시) | 표시하지 않음. 행 선택은 화면 상태 라우팅(키는 메모리만) |
| X-5 | 지침서 `GET …/duplicates?businessNo=…`(번호가 URL·접근 로그에) ↔ D-MOPS-003 oracle 방어 | POST 본문 번호 조회 하나(C-6) |
| X-6 | 지침서 표 분리안(`BUSINESS_REGISTRATION`에 번호·상호) ↔ 기존 `business` 열·UNIQUE·FK·조회 | 번호·상호는 `business`에 유지, 그 밖 등록정보만 `business_registration`(C-8, §5.3) |
| X-7 | 변경 사유 저장 ↔ ADMIN 감사 `reason`은 UNMASK만 | 사유 원문은 MASTER 변경 이력에만, ADMIN 감사 detail은 `reason=PRESENT`(C-13) |
| X-8 | D-MOPS-010 ② HMAC 미구현(B3-3a 대기) ↔ 새 사업자 명령의 지문에 번호·개인정보가 들어감 | **Coordinator 결정 C-9**: 새 명령 3종도 기존 `m1:` SHA-256 지문(모든 현 MASTER 관리 명령과 같음). HMAC 전환은 MERCHANT-OPS-002 B3-3a(hmac1 필드 단위 설계)가 모든 명령에 함께 적용. 이 작업에 새 키·`.env` 없음. 알려진 한계 L-13 |
| X-9 | 하위 행 종료 UPDATE를 열 단위로 막을 procedure가 MASTER에 없음 | 표 단위 `SELECT, INSERT, UPDATE` + Entity `updatable = false`(기존 MASTER 관례 — V2 주석) + 시험(C-11). 대안: init 스크립트에 MASTER 열 procedure 추가(인프라 변경 — 채택 안 함) |
| X-10 | 옛 등록 경로(번호+상호만, ACTIVE)·Demo seed가 그 경로 사용 | 전환 전까지 옛 경로는 DRAFT·구분 NULL로 저장(과도기 — **D-BIZ-002 적용 시점 = BIZ-4 전환**, 그 사이 만든 행은 migration 행과 같은 형태, Coordinator가 사용자에게 알림 — M-7, L-6), Demo seed는 BIZ-2에서 새 생성(REGISTERED) 사용 + 기존 seed 기록 선확인(C-24), 옛 경로 제거는 BIZ-4 전환(C-27) |
| X-11 | 지침서 PUT "버전·변경사유 필수" ↔ 정책 POL-004(중요정보만 사유 필수)·Coordinator 기본값 | 사유 필수 = 중요정보 변경 시(§4.5, C-12). 일반정보는 선택 |
| X-12 | 지침서 `BUSINESS_AUDIT` 표 ↔ 기존 `master_change_history`·ADMIN 감사 | 새 감사 표를 만들지 않고 두 기존 기록을 쓴다(C-13) |
| X-13 | MERCHANT-BIZ-LOOKUP-001 rev 0.3 줄 참조(`BusinessPicker` L173·`merchantSearch` L197) | 현재 L244·L267 — 이 문서가 대체 |
| X-14 | 지침서 §7(L161) "사업자번호는 하이픈 없이 숫자 10자리로 **정규화**" ↔ D-MD-008·기존 관례(서버 정규화·암묵 trim 없음, 그대로 거절) | 서버는 정규화하지 않는다(10 ASCII 숫자만 허용, 그 밖 400). 하이픈 제거는 화면 입력 컴포넌트가 보내기 전에 한다(`RegistrationNumberInput` — 표시만 하이픈). 전화·법인등록번호도 같다(N-3) |
| X-15 | 지침서 §7(L182) "동일 사업자번호 동시 생성 — 다른 요청 **409** 등 충돌 반환" ↔ 기존 MASTER·ADMIN 명령 응답 관례(업무 거절 = 200 `outcome: REJECTED` + `rejection`, 409는 같은 키·다른 내용 `IDEMPOTENCY_KEY_REUSED`/`KEY_REUSED`만) | 기존 관례 유지: 중복은 200 `REJECTED`·`BUSINESS_REGISTRATION_NUMBER_DUPLICATE`("409 등"의 "충돌 반환" 의미를 저장된 업무 거절로 충족). 409로 바꾸면 멱등 재생 응답 모양·ADMIN 응답 해석·골든이 갈라진다(N-3) |

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| **D-BIZ-001**(Q-BIZ-1) | `lifecycle`(ACTIVE/TERMINATED, D-MD-006) 유지 + `registration_status`(DRAFT/REGISTERED/INACTIVE) 추가. 기존 사업자 → DRAFT. **새 가맹점 등록은 REGISTERED 사업자만**(MASTER `BUSINESS_NOT_REGISTERED`), 기존 가맹점·계약 무영향. (Demo seed 처리는 사용자 결정이 아니라 Coordinator 선택 C-24 — m-8) |
| **D-BIZ-002**(Q-BIZ-2, TBD-05 일부 해소) | DRAFT 최소 = 사업체 구분 + 사업자등록번호 10자리 + 상호. DRAFT 만료·삭제는 TBD(구현 없음). **적용 시점(M-7)**: 새 경로(BIZ-1 Use Case·BIZ-2 API)는 즉시, 전체 강제는 BIZ-4 전환(옛 경로 제거). BIZ-1~BIZ-4 사이 옛 등록 경로로 만든 사업자는 migration 행과 같은 DRAFT·`business_type` NULL(Coordinator가 사용자에게 알림, L-6) |
| **D-BIZ-003**(Q-BIZ-3, D-MOPS-006 ③ 변경) | 중복 번호면 조회 권한자에게 기존 사업자 상호·마스킹 번호·상세 링크. 모든 시도 감사·공유 등록번호 버킷 |
| **D-BIZ-004**(Q-BIZ-4) | MC-4 먼저. MERCHANT-BIZ-LOOKUP-001을 이 작업에 합침(가맹점 0개 사업자 목록·검색, 가맹점 등록 ① 사업자 선택) |
| **D-BIZ-005**(Q-BIZ-5) | DRAFT 사업자의 사업자등록번호 오입력: 이번 작업은 **그대로 둔다**(번호 변경·DRAFT 삭제 없음). 등록 화면에 "사업자등록번호는 저장 후 변경할 수 없습니다" 안내(§8.3). 정정 정책은 TBD-04/05 유지(별도 작업) |
| **D-BIZ-006**(Q-BIZ-6) | ① 사업자 목록·상세·이력 조회 = OPERATOR·APPROVER, 등록·수정·등록 완료 = OPERATOR. 합친 번호 조회(가맹점 등록 ① 사업자 선택)도 OPERATOR·APPROVER — **MERCHANT-BIZ-LOOKUP-001 수용 기준 "권한 OPERATOR"를 사용자 결정으로 변경**. ② 상세는 조회 역할에 개인정보(대표자·담당자 이름, 전화, 이메일, 주소, 법인등록번호) **원문** 표시, 상세 열람마다 감사 + 조회 창(속도 제한). 사업자등록번호는 어디서나 마스킹. 로그·감사·이력은 항상 마스킹. ③ 번호 조회 응답에 `registrationStatus` 포함(**D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001 응답 범위를 사용자 결정으로 확장**), DRAFT 선택 시 "사업자 등록 완료 후 가맹점 등록 가능" + 상세 링크 |
| D-MD-008 | 번호당 사업자 하나(UNIQUE 유지), 10자리 숫자만(정규화 없음 — 화면 하이픈은 표시만), 평문을 로그·이벤트·이력에 남기지 않음 |
| D-MD-006 | 생명주기 축 불변. INACTIVE ≠ TERMINATED(INACTIVE는 내부 관리 상태, POL-006 — 결제·정산 정지와 무관) |
| D-MD-004 | 계약 당사자 = Business 그대로. 계약 등록은 등록 상태를 보지 않는다(D-BIZ-001은 가맹점 등록만) |
| D-MOPS-002 | 목록·이력 미감사, **상세는 감사**(`BUSINESS_VIEWED` — D-BIZ-006 ② 확정) |
| D-MOPS-003 | 번호 정확 일치 조회 = OPERATOR·APPROVER(D-BIZ-006 ①과 같은 집합), 매 시도 감사, oracle 취급(같은 모양·버킷·형식 선검사·값 비노출), 응답 번호 마스킹 |
| D-MOPS-004·006 ① | 등록·수정·등록 완료 = OPERATOR 1인(D-BIZ-006 ①) |
| D-MOPS-006 ② | ADMIN 감사는 MASTER 호출 전 시도(`REQUESTED`) + 결과 두 행, ADMIN 명령 표 없음 |
| D-MOPS-006 ③ | **D-BIZ-003으로 변경**(위) |
| D-MOPS-006 ④ | 번호가 오간 모든 시도 감사(`brn=앞3*******`·`brn=INVALID`), 중복 결과는 등록번호 버킷 소모 |
| D-MOPS-005 | MASTER 채널 = 기존 `ADMIN_SERVICE` 키 하나 |
| D-MOPS-010 ② | 사업자 등록 지문 HMAC — **이 작업에서 구현하지 않는다**. 새 명령 3종도 기존 `m1:`(C-9), 전환은 MERCHANT-OPS-002 B3-3a가 모든 명령에 함께(L-13) |
| D-ADMIN-UI-003·004 ③④ | 서버 기능이 있는 화면만 메뉴에, `biz_` 화면 미표시, 사업자·가맹점 화면 분리(등록은 OPERATOR) |
| D-ADMIN-UI-004 ⑦ | 번호 조회 응답 = 상호·마스킹 번호(+키) — **D-BIZ-006 ③으로 `registrationStatus` 확장** |
| D-BIZLOOKUP-001 | 번호 조회 응답에 `businessId` 포함·화면 미표시, 원문 번호는 조회 요청에서만 전송(합친 조회에 그대로). 응답 필드는 D-BIZ-006 ③으로 `registrationStatus` 추가 |
| D-MCODE-003 ② | 사업자 표시 = "<상호> · <마스킹 번호>" |
| D-DEMO-001 | seed 값은 명시 Demo fixture(운영값 아님) |

## 3. 범위·제외·TBD 처리

### 3.1 이번 범위(P0 — 지침서 F-001~F-008)
F-001 목록/검색, F-002 신규등록/임시저장, F-003 등록 완료, F-004 상세·수정(낙관적 잠금·사유·이력), F-005 내부 중복확인, F-006 대표자/담당자, F-007 주소·업태·종목, F-008 인증·권한(비활성화 권한은 기능이 없어 해당 없음). + 합친 MERCHANT-BIZ-LOOKUP-001(가맹점 등록 ① 사업자 선택).

### 3.2 제외
- P1: F-009 증빙 파일(TBD-09), F-010 진위·영업상태(TBD-06 — `verification_status = NOT_CHECKED` 고정, 화면 "미검증"), F-011 사용중지(TBD-08 — `INACTIVE`로 가는 경로 없음).
- AML/KYB(생년월일·국적·실제소유자 등 수집 금지 — POL-003), Merchant 자동 생성, 계약, MID/TID, 수수료·정산·담보, 본점·지점 판정, 기존 IMS 연동·이관(PC-BIZ-POL-001 §1·§7).
- 지침서 §7 레거시 항목(접수경로·URL·세금계산서 이메일·등록비 등) — 받지 않는다.

### 3.3 TBD 처리표

| TBD | 내용 | 이 작업의 처리 |
|---|---|---|
| TBD-01 | 본점·지점 동일 주체 판정·연결·병합 | 구현 없음. 법인등록번호 UNIQUE 없음(같은 법인 여러 사업자 허용 — 자동 연결 없음) |
| TBD-02 | AML/KYB 연동·필수 수집항목 | 구현·필드 없음. `REGISTERED` ≠ 심사 승인(화면 문구 §8.4) |
| TBD-03 | 복수 BusinessRegistration | 1:1 생성만(UNIQUE `business_id`). 표는 별도라 후속 확장 가능, 번호·상호 위치는 L-5 |
| TBD-04 | 사업자등록번호 정정·변경 | **변경 불가**(API에 번호 수정 필드 없음, Entity `updatable = false`, 화면 읽기 전용 + "정책 미정" 안내). DRAFT 오입력도 같다(**D-BIZ-005** — 등록 화면 사전 안내) |
| TBD-05 | DRAFT 최소·만료·삭제 | 최소 = D-BIZ-002(해소). 만료·삭제 없음(물리 삭제 경로 없음, D-BIZ-005) |
| TBD-06 | 진위·상태조회 API | 연동 없음, `NOT_CHECKED`만(DB CHECK), 성공 위조 없음 |
| TBD-07 | 중요정보 변경 승인·재검증 | 2인 승인·재검증 없음 — OPERATOR 1인 + 사유 필수 + 전후 이력(Coordinator 기본값) |
| TBD-08 | INACTIVE 재활성화·권한 | INACTIVE 전이·명령 없음(enum·CHECK 값만 존재 — Lifecycle TERMINATED 선례) |
| TBD-09 | 첨부 크기·형식·보관·파기 | 업로드 기능·표 없음 |
| TBD-10 | 국외·비영리 확장 | `country_code = 'KR'` CHECK, 구분 INDIVIDUAL·CORPORATION만 |
| (POL-006) | 개인정보 보관·파기 | 파기 기능 없음, 임의 기간 없음 — 종료 행·이력의 개인정보는 무기한 보관(L-3) |

## 4. Domain 모델(MASTER — `master.merchant.domain`, 순수 Kotlin)

### 4.1 Aggregate 구성
`Business`(Aggregate Root, 기존 클래스 확장 — 식별자 `BusinessId` `biz_`):
- 기존: `registrationNumber`(불변), `displayName`(= 상호), `lifecycle`, `createdAt/By`.
- 새: `businessType: BusinessType?`(INDIVIDUAL·CORPORATION — null은 migration 행만), `registrationStatus: BusinessRegistrationStatus`(DRAFT·REGISTERED·INACTIVE), `verificationStatus: BusinessVerificationStatus`(**NOT_CHECKED 하나뿐** — P1이 값 추가), `version: Long`(낙관적 잠금 기대값), `updatedAt/By`, `profile: BusinessProfile`.
- `BusinessProfile`(값 묶음 — 수정 단위): `registration: BusinessRegistration`, `representatives: List<BusinessRepresentative>`, `categories: List<BusinessCategory>`, `addresses: List<BusinessAddress>`, `contacts: List<BusinessContact>`.
- `BusinessRegistration`(1:1 값): `corporateRegistrationNumber: CorporateRegistrationNumber?`, `openingDate: LocalDate?`, `countryCode`(= `KR` 고정), `englishName: EnglishBusinessName?`, `phoneNumber: PhoneNumber?`(사업체 연락처), `email: EmailAddress?`(사업체 이메일).
  사업자등록번호·상호는 Aggregate Root 필드로 둔다(저장 위치 §5.3과 같게 — Domain 개념상 등록정보지만 기존 불변·UNIQUE 근거를 그대로 쓰기 위해, C-8).
- `BusinessRepresentative(name: PersonName, type: RepresentativeType)` — SOLE(단독)·JOINT(공동)·EACH(각자).
- `BusinessCategory(type: BusinessCategoryType, name: CategoryName)` — BUSINESS_TYPE(업태)·BUSINESS_ITEM(종목).
- `BusinessAddress(type: BusinessAddressType, postalCode: PostalCode, baseAddress: AddressLine, detailAddress: AddressLine?)` — REGISTERED(사업장)·ACTUAL(실사업장). 상세주소 선택(C-31).
- `BusinessContact(name: PersonName, email: EmailAddress, phoneNumber: PhoneNumber, primary: Boolean)` — 역할 = 등록 담당자(사업자 측 담당자, 내부 PayCore 담당자 아님 — POL-003).
- 목록 순서 = 입력 순서(`display_order` 1부터). Domain은 순서를 값으로 갖는다(같은 내용·다른 순서 = 다른 profile — C-10의 비교 기준).
- **하위 행 동일성(M-4, C-10)**: 하위 행의 정체 = **(내용 전체 + `display_order`)**. 수정 diff는 이 키로 짝을 짓는다 — 같은 키 = 행 유지(행 ID·`effective_from` 불변), 그 밖 = 옛 행 종료 + 새 행 추가. 따라서 **순서만 바뀐 경우도 종료 + 추가**다(이력 `ended`/`added`에 남는다 — 순서 변경도 기록 대상). 담당자의 `primary`는 내용에 포함(기본 담당자 변경 = 종료 + 추가).

이력(종료된 대표자 등)은 Aggregate에 싣지 않는다 — Aggregate는 **현재 행만**(무제한 컬렉션 회피, ARCHITECTURE-CONVENTIONS §15 선례). 과거 행은 조회 Reader가 읽는다.

### 4.2 값 규칙(형식 — DRAFT·REGISTERED 공통, 서버 권위 + ADMIN·화면 복제)
모든 문자열: 정규화·암묵 trim 없음(그대로 거절 — 기존 관례), 제어문자·짝 없는 서로게이트 거절, 앞뒤 공백 거절.

| 값 객체 | 규칙 | 근거 |
|---|---|---|
| `BusinessRegistrationNumber` | 기존 그대로(ASCII 숫자 10자리, 체크섬 없음) | D-MD-008 |
| `DisplayName`(상호) | 기존 그대로(1~100 code point) | 기존 열 |
| `CorporateRegistrationNumber` | ASCII 숫자 13자리, 체크섬 없음, `toString`·`masked()` = 앞 6자리 + `*******` | 정책 §5 "13자리 형식" |
| `EnglishBusinessName` | 1~100자, 출력 가능 ASCII(`0x20..0x7E`), 앞뒤 공백 금지 | 정책 §5 "해외 연동 필요 시" |
| `PersonName`(대표자·담당자) | 1~50 code point, `DisplayName`과 같은 문자 규칙, `toString` 마스킹(§9) | C-19(길이 — 기술 상한) |
| `CategoryName` | 1~50 code point, 같은 문자 규칙 | C-19 |
| `PostalCode` | ASCII 숫자 5자리 | 국내 우편번호 |
| `AddressLine` | 1~200 code point, 같은 문자 규칙 | C-19 |
| `PhoneNumber` | ASCII 숫자 8~12자리, 첫 자리 `0` 또는 `1`(대표번호 15xx·16xx·18xx). 하이픈은 화면 표시만. 유선·휴대 구분 없음(정책 §5 "유선전화만 강제하지 않음") | C-20 |
| `EmailAddress` | 3~254자 ASCII, `local@domain` 하나의 `@`, local 1~64자 `[A-Za-z0-9._%+-]`, domain은 점으로 나뉜 라벨 `[A-Za-z0-9-]`(라벨 1~63, 마지막 라벨 문자 2자 이상). 대소문자 보존(정규화 없음) | C-20 |
| `openingDate` | ISO `yyyy-MM-dd` 실재 날짜(달력 날짜 — 시간대 없음). 미래·과거 범위 규칙은 두지 않는다(정책 근거 없음) | C-21 |
| `BusinessChangeReason` | 1~200 code point, 같은 문자 규칙(줄바꿈 불가) | C-13 |

목록 상한(기술 상한 — 업무 정책 아님, 서명 채널 본문 16 KiB 안에 최대 입력이 들어가게 C-19): 대표자 ≤ 5, 업태 ≤ 10, 종목 ≤ 10, 주소 ≤ 2(유형별 1), 등록 담당자 ≤ 5. 최대 크기 본문 ≤ 16 KiB를 시험으로 고정(BIZ-2-3).

### 4.3 행 단위·Aggregate 단위 규칙과 필수값

**행 단위(DRAFT·REGISTERED 공통 — C-10)**: 보낸 행은 그 행 안에서 완결돼야 한다 — 대표자(이름·유형), 업태/종목(유형·이름), 주소(우편번호·기본주소, 상세 선택), 담당자(이름·이메일·전화). 화면의 빈 행은 보내지 않는다. DRAFT의 "부분 입력"은 **항목·목록 단위**(목록 0개·선택 항목 미입력)로 허용한다.

**Aggregate 단위 공통(모든 상태)**:
- 대표자: SOLE이 있으면 현재 대표자는 정확히 1명. 2명 이상이면 SOLE 없음(JOINT·EACH 혼합은 막지 않는다 — 정책 근거 없음, C-22). **같은 이름·유형의 대표자 두 행은 거절하지 않는다**(동명이인 공동대표 가능 — 정책 근거 없는 거절 제거, m-5. 행은 `display_order`로 구별).
- 업태/종목: 같은 유형 안 같은 이름 중복 거절.
- 주소: 유형별 최대 1. ACTUAL이 REGISTERED와 (우편번호·기본·상세) 모두 같으면 거절(`ACTUAL_SAME_AS_REGISTERED` — 정책 §5 "다른 경우에만").
- 담당자: `primary = true`는 최대 1.
- 법인등록번호: `businessType = INDIVIDUAL`이면 **입력 금지**(`NOT_ALLOWED`).
- 국가: `KR`만.

**DRAFT 필수(D-BIZ-002)**: `businessType`, `registrationNumber`, `displayName`. 그 밖 모두 선택.

**REGISTERED 필수(지침서 §4, 정책 §5)**: `businessType`, `registrationNumber`, `displayName`, `openingDate`, REGISTERED 주소 1, `registration.phoneNumber`, CORPORATION이면 `corporateRegistrationNumber`, 현재 대표자 ≥ 1, 업태 ≥ 1·종목 ≥ 1, 등록 담당자 ≥ 1 **그리고 primary 정확히 1**(지침서 F-006 "기본 등록 담당자 1명"). 선택: 사업체 이메일·영문 상호·실사업장 주소·상세주소(C-31). 사업자등록증은 이번 범위 밖.

판정 함수 `BusinessProfileRules.validate(type, number?, name?, profile, target: DRAFT|REGISTERED): List<BusinessFieldError>`(순수 함수 — 모든 위반을 모아 돌려준다). 같은 함수를 ① MASTER 본문 해석 직후(TX 전, 400 + 필드 오류 — 저장 안 함)와 ② Aggregate 생성·전이 메서드 안(TX 안 — 위반이면 결함 예외)에서 쓴다(C-16).

### 4.4 상태 전이

| 전이 | 메서드 | 조건 |
|---|---|---|
| (없음) → DRAFT | `Business.createDraft(id, type, number, name, profile, at, by)` | DRAFT 규칙 |
| (없음) → REGISTERED | `Business.createRegistered(...)` | REGISTERED 규칙(지침서 §5 "DRAFT 또는 유효한 REGISTERED 생성") |
| DRAFT → DRAFT | `business.updateProfile(type, name, profile, reason?, expectedVersion, at, by)` | DRAFT 규칙 + §4.5 사유 + 버전 |
| DRAFT → REGISTERED | `business.completeRegistration(type, name, profile, reason?, expectedVersion, at, by)` | REGISTERED 규칙(보낸 전체 내용 기준) + 사유 + 버전 |
| REGISTERED → REGISTERED | `business.updateProfile(...)` | **REGISTERED 규칙 유지**(필수값을 지우는 수정 거절) + 사유 + 버전 |
| REGISTERED → INACTIVE | 없음(P1 F-011, TBD-08) | — |
| REGISTERED → DRAFT, INACTIVE → * | 없음 | — |

- `requireRegistered()` — `registrationStatus != REGISTERED`면 `MasterRuleViolation(BUSINESS_NOT_REGISTERED)`. `Merchant.register`가 `requireActive()` 다음에 부른다(**BIZ-4 전환에서 활성화**, C-27). 판정 순서(가맹점 등록 Use Case·API — rev 0.2.9 정정, 리뷰 MINOR-2 (a) MC-1 동작 유지): 코드 예약어 `MERCHANT_CODE_RESERVED`(MC-1 §3.3 `requireAssignable` — 사업자 잠금 전) → `BUSINESS_NOT_FOUND` → `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → 코드 중복 `MERCHANT_CODE_TAKEN`(E-38). Domain `Merchant.register` 안의 순서는 생명주기 → 등록 상태 → 예약어 재검사(방어 — Use Case가 먼저 거르므로 API에서는 보이지 않음).
- `completeRegistration`을 REGISTERED에 부르면 `BUSINESS_NOT_DRAFT`(재요청 TC-16 — 같은 키는 멱등 재생, 다른 키는 이 거절, 상태·데이터 변화 없음).
- **`completeRegistration`에는 `BUSINESS_PROFILE_UNCHANGED`를 적용하지 않는다(M-1)** — DRAFT → REGISTERED 전이 자체가 변경이다. 내용이 저장된 DRAFT와 같아도 성공(version +1, 이력 `BusinessRegistrationCompleted` 1건 — `changes`에 `registrationStatus` DRAFT → REGISTERED 항목 + 내용 변경이 있으면 그 항목). 내용 변경이 함께 있으면 §4.5 사유 규칙은 그대로 적용(중요 변경 + 사유 없음 → `BUSINESS_CHANGE_REASON_REQUIRED`).
- 생명주기 TERMINATED 사업자의 수정·등록 완료는 `BUSINESS_NOT_ACTIVE`(종료 명령은 아직 없음 — 방어만).
- `businessType` null(migration 행)은 `reconstitute`만 만들 수 있다. 수정·등록 완료는 구분 필수(X-1).

### 4.5 수정 규칙·중요정보·사유(POL-004)
- 수정 요청 = **전체 교체**(PUT 의미): 편집 가능한 모든 항목을 보낸다(선택 값은 `null`). 사업자등록번호·상태·검증 상태는 본문에 없다.
- **사업자등록번호 변경 불가**(TBD-04): 필드 자체가 없다(보내면 모르는 필드 400).
- **중요정보** = 상호, 대표자, 개업일, 법인등록번호(정책 POL-004 — 번호는 변경 불가라 제외) + 사업체 구분(C-12). `BusinessProfileChange.of(beforeStatus, before, after)`가 바뀐 항목 목록과 `important` 여부를 계산한다(하위 행 짝짓기는 §4.1 동일성 키):

| 항목 | 변경 형태 | 이전 상태 DRAFT | 이전 상태 REGISTERED |
|---|---|---|---|
| 상호 | 값이 다름 | 중요 | 중요 |
| 사업체 구분 | 값이 다름(migration 행의 NULL → 값 첫 입력 제외) | 중요 | 중요 |
| 개업일·법인등록번호 | 비어 있던 값 첫 입력 | 일반 | 일반(CORPORATION 필수라 법인등록번호는 실제로 없음) |
| 개업일·법인등록번호 | 기존 값 변경·삭제 | 중요 | 중요 |
| 대표자 | 이전 현재 대표자 **0명**에서 처음 추가 | 일반(유일한 면제) | 해당 없음(REGISTERED는 ≥ 1명) |
| 대표자 | 기존 행 종료(제거·이름/유형 변경 = 종료 + 추가) | 중요 | 중요 |
| 대표자 | 기존 행 유지 + 추가만(≥ 1명에서) | 일반 | **중요(M-2)** |
| 대표자 | 순서만 변경(종료 + 추가 — M-4) | 중요(종료 포함) | 중요 |
| 업태·종목·주소·사업체 연락처·영문 상호·등록 담당자 | 추가·종료·값 변경·순서 변경 | 일반 | 일반 |

  - 요약: **REGISTERED 사업자는 현재 대표자 집합의 모든 변경(추가·종료·유형 변경·순서 변경)이 중요**(M-2). DRAFT는 기존 행 종료가 있으면 중요, 0명에서 첫 입력은 면제. 대표자 변경은 언제나 이력에 전후(`ended`·`added`, 마스킹)가 남는다.
- 중요 변경이 있으면 `reason` 필수 — 없으면 저장된 거절 `BUSINESS_CHANGE_REASON_REQUIRED`(TX 안 판정 — 이전 값이 필요). 일반 변경만이면 사유 선택(있으면 기록).
- `updateProfile`에서 변경이 하나도 없으면(같은 내용 — 순서 포함) `BUSINESS_PROFILE_UNCHANGED` 거절(버전·이력 증가 없음 — 빈 이력 방지, C-12). **`completeRegistration`에는 적용하지 않는다**(§4.4, M-1).
- 낙관적 잠금: 요청의 `version` ≠ 잠근 행의 version → `BUSINESS_VERSION_CONFLICT`(덮어쓰기 거부 — TC-14). 화면은 다시 불러와 새 submissionId로 재시도.

### 4.6 필드 오류(지침서 §8 "필드별 오류 반환")
`BusinessFieldError(path, code)` — **값을 담지 않는다**.
- `path` 문법: `businessType`·`registrationNumber`·`displayName`·`registration.corporateRegistrationNumber`·`registration.openingDate`·`registration.countryCode`·`registration.englishName`·`registration.phoneNumber`·`registration.email`·`representatives`·`representatives[i].name|type`·`categories`·`categories[i].type|name`·`addresses`·`addresses[i].type|postalCode|baseAddress|detailAddress`·`contacts`·`contacts[i].name|email|phoneNumber|primary`·`reason`·`version`(i = 0부터, 상한 내 정수).
- `code`: `REQUIRED`·`FORMAT`·`TOO_LONG`·`TOO_MANY`·`DUPLICATE`(업태/종목 같은 유형·같은 이름만 — 대표자에는 쓰지 않음, m-5)·`NOT_ALLOWED`·`SOLE_WITH_OTHERS`·`PRIMARY_REQUIRED`·`PRIMARY_MULTIPLE`·`ACTUAL_SAME_AS_REGISTERED`·`BUSINESS_TYPE_ITEM_REQUIRED`(업태·종목 각 1 미만은 `categories` path + 이 code, C-16).
- 한 응답 최대 50개(그 이상은 앞 50개 — 결정적 순서: path 사전순).

## 5. DB(MASTER V15·V16)

공통: utf8mb4 + `utf8mb4_0900_bin`, 시각 UTC `DATETIME(3)`(DB 기본값 없음), 날짜(`opening_date`)는 `DATE`(달력 날짜), Cross-DB FK 없음. **MySQL CHECK 이름은 schema 전체에서 유일** → 새 표 CHECK는 표별 접두(`ck_breg_`·`ck_brep_`·`ck_bcat_`·`ck_badr_`·`ck_bcon_`)를 쓴다(기존 `ck_business_registration_number`와 충돌 방지).

### 5.1 V15 — `business` 변경(문장별 ALGORITHM — C-7)
```sql
-- (1) 새 열(NULL 허용, 끝에 추가) — 메타데이터만
ALTER TABLE business
    ADD COLUMN business_type       VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN registration_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN verification_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN updated_at          DATETIME(3) NULL,
    ADD COLUMN updated_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM = INSTANT;
-- (2) 기존 행 채우기(D-BIZ-001: DRAFT, 구분 NULL — X-1). 갱신 시각·행위자 = 생성 값(변경이 없었으므로)
UPDATE business SET registration_status = 'DRAFT', verification_status = 'NOT_CHECKED', updated_at = created_at, updated_by = created_by
 WHERE registration_status IS NULL;
-- (3) NOT NULL·CHECK — 표 재작성(작은 표, Demo 즉시. 운영 적용 창은 L-8)
ALTER TABLE business
    MODIFY registration_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY verification_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    MODIFY updated_by VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_business_type CHECK (business_type IS NULL OR business_type IN ('INDIVIDUAL', 'CORPORATION')),
    ADD CONSTRAINT ck_business_reg_status CHECK (registration_status IN ('DRAFT', 'REGISTERED', 'INACTIVE')),
    ADD CONSTRAINT ck_business_type_required CHECK (registration_status = 'DRAFT' OR business_type IS NOT NULL),
    ADD CONSTRAINT ck_business_verification CHECK (verification_status = 'NOT_CHECKED'),
    ADD CONSTRAINT ck_business_updated CHECK (updated_at >= created_at),
    ALGORITHM = COPY;
-- (4) 목록 색인(온라인)
ALTER TABLE business
    ADD INDEX ix_business_status_id (registration_status, business_id),
    ADD INDEX ix_business_name_id (display_name, business_id),
    ADD INDEX ix_business_created_id (created_at, business_id),
    ALGORITHM = INPLACE, LOCK = NONE;
```
- `ck_business_verification`이 `NOT_CHECKED`만 허용 → 어떤 경로도 VERIFIED를 저장할 수 없다(TC-21, P1이 실제 연동과 함께 CHECK를 넓힌다).
- `INACTIVE`는 CHECK 값만 있고 앱 경로 없음(TBD-08).
- `registration_number`·UNIQUE·`display_name`·`lifecycle`·`version` 불변.

### 5.2 V15 — 새 표

```sql
-- 등록정보(1:1 — TBD-03 확장 대비 별도 표, 1차 UNIQUE(business_id)). 번호·상호는 business에 있다(§5.3).
CREATE TABLE business_registration (
    registration_id               BIGINT       NOT NULL AUTO_INCREMENT,
    business_id                   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    corporate_registration_number CHAR(13)     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    opening_date                  DATE         NULL,
    country_code                  CHAR(2)      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    english_name                  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    phone_number                  VARCHAR(12)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    email                         VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    CONSTRAINT pk_business_registration PRIMARY KEY (registration_id),
    CONSTRAINT uk_breg_business UNIQUE (business_id),
    CONSTRAINT fk_breg_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    CONSTRAINT ck_breg_corporate_number CHECK (corporate_registration_number IS NULL OR REGEXP_LIKE(corporate_registration_number, '^[0-9]{13}$', 'c')),
    CONSTRAINT ck_breg_country CHECK (country_code = 'KR'),
    CONSTRAINT ck_breg_phone CHECK (phone_number IS NULL OR REGEXP_LIKE(phone_number, '^[01][0-9]{7,11}$', 'c')),
    CONSTRAINT ck_breg_email CHECK (email IS NULL OR REGEXP_LIKE(email, '^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+$', 'c')),
    CONSTRAINT ck_breg_english_name CHECK (english_name IS NULL OR REGEXP_LIKE(english_name, '^[ -~]+$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 기존 사업자마다 등록정보 1행(1:1 불변식 — 모든 business에 정확히 1행)
INSERT INTO business_registration (business_id, country_code) SELECT business_id, 'KR' FROM business;

-- 대표자(현재·과거 관계). effective_* = PayCore 기록 시각(UTC — 법적 취임일 아님, C-10). 종료 = effective_to·ended_by 기록, 물리 삭제 없음.
CREATE TABLE business_representative (
    representative_id   BIGINT      NOT NULL AUTO_INCREMENT,
    business_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    name                VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    representative_type VARCHAR(8)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    display_order       SMALLINT    NOT NULL,
    effective_from      DATETIME(3) NOT NULL,
    effective_to        DATETIME(3) NULL,
    created_by          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ended_by            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    current_order_key   VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (IF(effective_to IS NULL, CONCAT(business_id, '#', display_order), NULL)) STORED,
    CONSTRAINT pk_business_representative PRIMARY KEY (representative_id),
    CONSTRAINT uk_brep_current_order UNIQUE (current_order_key),
    CONSTRAINT fk_brep_business FOREIGN KEY (business_id) REFERENCES business (business_id),
    INDEX ix_brep_business (business_id, effective_to),
    CONSTRAINT ck_brep_type CHECK (representative_type IN ('SOLE', 'JOINT', 'EACH')),
    CONSTRAINT ck_brep_order CHECK (display_order BETWEEN 1 AND 5),
    CONSTRAINT ck_brep_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_brep_ended CHECK ((effective_to IS NULL) = (ended_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
```
같은 틀(`effective_from/to`·`created_by/ended_by`·`ck_…_period`·`ck_…_ended`·현재 순서 생성 열 UNIQUE)로:
- `business_category`(`category_id` PK, `registration_id` FK → `business_registration`(지침서 §6), `category_type VARCHAR(16)` IN ('BUSINESS_TYPE','BUSINESS_ITEM'), `name VARCHAR(50)`, `display_order` 1..10, 생성 열 `current_order_key` = `registration_id#type#order` UNIQUE, `current_name_key` = `registration_id#type#name` UNIQUE(현재 중복 이름 DB 방어)).
- `business_address`(`address_id` PK, `registration_id` FK, `address_type VARCHAR(16)` IN ('REGISTERED','ACTUAL'), `postal_code CHAR(5)` `^[0-9]{5}$`, `base_address VARCHAR(200)`, `detail_address VARCHAR(200) NULL`, 생성 열 `current_type_key` = `registration_id#type` UNIQUE — 유형별 현재 1).
- `business_contact`(`contact_id` PK, `business_id` FK, `contact_role VARCHAR(16)` IN ('REGISTRATION')(사업자 측 등록 담당자 — 후속 역할 확장 자리), `name VARCHAR(50)`, `email VARCHAR(254)`(같은 CHECK), `phone_number VARCHAR(12)`(같은 CHECK), `is_primary TINYINT(1)` IN (0,1), `display_order` 1..5, 생성 열 `current_order_key` UNIQUE, `current_primary_key` = `IF(effective_to IS NULL AND is_primary = 1, business_id, NULL)` UNIQUE — 현재 기본 담당자 최대 1).

새 표 이력 이관 없음(기존 사업자는 하위 행 0개 — 등록정보 1행만).

**새 UNIQUE의 1062 분류(M-4)**: `uk_breg_business`·`uk_brep_current_order`·`uk_bcat_current_order`·`uk_bcat_current_name`·`uk_badr_current_type`·`uk_bcon_current_order`·`uk_bcon_current_primary`는 **업무 유일키가 아니다** — Domain 규칙(§4.3)과 `business` FOR UPDATE 직렬화가 먼저 막으므로, 이 제약 위반은 diff·flush 순서 결함 또는 잠금 우회다. `PersistenceFailures.CONSTRAINTS`에 새 `StoreConstraint.BUSINESS_PROFILE_ROW(businessKey = false)`로 **명시 등록**한다(이름별 한 줄 + 주석 "결함 — 재시도·재판정 없음"). 실행기는 `businessKey = false`를 새 TX 재판정하지 않으므로 503 `TEMPORARILY_UNAVAILABLE` + 결함 로그(제약 이름만)로 끝나고, 같은 키 재전송도 같은 결함을 낸다(자동 재시도 없음). 미등록 시 `OTHER`로 떨어져 같은 처리지만, 등록으로 "의도된 결함 분류"를 시험이 고정한다(BIZ-1-5). 사업자 번호 UNIQUE(`uk_business_registration_number`)만 업무 키(기존).

### 5.3 번호·상호를 `business`에 두는 이유(지침서 §6 분리안과 비교 — C-8)

| | (권장) `business`에 유지 | (대안) `business_registration`으로 이동 |
|---|---|---|
| 유일성 | 기존 UNIQUE `uk_business_registration_number`·1062 분류(`PersistenceFailures.kt:74`) 그대로 | 새 표 UNIQUE·분류 이전, 기존 열 제거 migration |
| 참조 | `merchant`·계약 FK, 조회 Reader·MASTER 상세 `business` 블록·라벨 조회가 `business.display_name`·`registration_number`를 읽는다 — 변경 0. (권리 Snapshot 조립 `EntitlementSnapshotAssembler`는 사업자 **ID와 생명주기만** 쓴다 — `EntitlementSnapshotAssembler.kt:18·24·31`, N-2) | 모든 조회 SQL·Reader 변경, 골든(`merchant-ops-response-shapes-v1`) 영향 |
| 불변 | `registration_number` Entity `updatable = false` 그대로(TBD-04) | 새 Entity에서 다시 보장 |
| TBD-03(복수 등록정보) | 그때 번호·상호의 소유를 다시 정해야 한다(L-5) | 지금 구조가 1:N에 가깝다 |
결론: 1차는 1:1이고 기존 계약·시험을 바꾸지 않는 쪽을 택한다. 지침서 §6은 "DDL 확정 전 점검 필수"인 [제안] 표다.

### 5.4 V16 — runtime 권한(표 단위, DELETE 없음)
```sql
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_registration', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_representative', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_category', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_address', 'SELECT, INSERT, UPDATE');
CALL ${flyway:database}_privilege.set_runtime_table_privileges('business_contact', 'SELECT, INSERT, UPDATE');
```
- `business` 권한은 바꾸지 않는다(V2 `SELECT, INSERT, UPDATE` — 새 열 갱신 포함).
- 하위 4표의 UPDATE는 **종료 기록(`effective_to`·`ended_by`)만** 쓴다. MASTER에는 열 단위 procedure가 없으므로 내용 열은 Entity `updatable = false`로 막고(V2 주석의 기존 관례), 시험이 "종료 외 UPDATE 0"을 고정한다(C-11, BIZ-1-6). `business_registration`의 UPDATE는 등록정보 제자리 수정(전후 값은 이력에).
- 각 주석에 쓰는 코드 경로(`Jpa…Repository` 메서드)를 적는다(V2 관례).

### 5.5 JPA 매핑(TECHNOLOGY-AND-PRINCIPLES §3 — 이번 코드의 역할·예·이유·주의점)
- **순수 Domain ↔ Entity 분리**: `Business`·값 객체는 Spring/JPA 의존 없음. Entity `JpaBusinessEntity`(새 열 매핑 — `business_type`·`registration_status`·`verification_status`·`updated_at/by`), `JpaBusinessRegistrationEntity`, `JpaBusinessRepresentativeEntity`·`JpaBusinessCategoryEntity`·`JpaBusinessAddressEntity`·`JpaBusinessContactEntity`(adapter.persistence), Spring Data `BusinessRegistrationJpaRepository` 등, Mapper `BusinessPersistenceMapper`(현재 행 → `BusinessProfile`). Port 이름은 §14: `BusinessRepository`(Aggregate 저장 Port, 기존) → `JpaBusinessRepository`(기존 확장).
- **Root 전용 vs profile 포함 읽기 분리(m-3)**: Domain은 `Business`(Root — 번호·상호·구분·두 상태·생명주기·version·생성/갱신)와 `BusinessProfile`(하위 값 묶음)을 나눠 들고, Port는 두 종류 메서드를 둔다.
  - 기존 그대로(Root만, 하위 표 SELECT 0): `lock(id)`(FOR UPDATE)·`find(id)`·`findIdByRegistrationNumber`. 호출처 = 가맹점 등록·계약 당사자·`MerchantOnboardingService` L129·185·204·328, 권리 원천 `JpaEntitlementSourceStore`(`MasterPersistenceMapper.toBusiness` — 새 Root 열만 추가 매핑). 이 경로들의 SQL 수·잠금 범위가 바뀌지 않는다(시험 BIZ-1-3: Hibernate 통계로 하위 표 SELECT 0 단언).
  - 새(사업자 명령 전용): `lockWithProfile(id): BusinessWithProfile?`(business FOR UPDATE → 같은 TX에서 현재 하위 행 일반 SELECT — 하위 행은 business 잠금으로 직렬화되므로 별도 잠금 없음), `insert(business, profile)`, `update(business, before: BusinessProfile, after: BusinessProfile)`(diff 저장 — 종료 → flush → 추가, 등록정보 제자리, business 행 갱신). `BusinessWithProfile(business, profile)`는 Application 반환 묶음(Aggregate Root의 의미는 `Business` + 그 profile — 전이 메서드는 둘을 함께 받는다).
- **연관 매핑 없음**(@OneToMany 금지 — `JpaBusinessEntity.kt:17` 관례): *역할* 하위 행을 자식 Repository로 따로 읽고 쓴다. *예* 대표자 교체 = 기존 행 `effective_to` 기록 + 새 행 `persist`. *이유* cascade·orphanRemoval이 이력 행을 지우는 경로를 구조적으로 없앤다(종료 행은 보존 대상). *주의* 자식 저장을 빠뜨려도 JPA가 알려 주지 않으므로 저장 후 재조회 시험(BIZ-1-3)이 필요하다.
- **낙관적 잠금(`@Version`) + 행 잠금**: *역할* 두 운영자의 동시 수정 덮어쓰기 방지. *예* A·B가 version 3을 보고 수정 → A 커밋(version 4) → B는 `lock`(FOR UPDATE)으로 최신 행을 읽고 요청 version 3 ≠ 4 → `BUSINESS_VERSION_CONFLICT`. *이유* 화면에서 읽은 시점과 저장 시점 사이 변경은 `@Version`만으로는 잡을 수 없다(분리된 Domain 객체를 최신 Entity에 덮어쓰면 version 검사가 우회된다 — §3 주의). 요청 version을 잠근 행과 **명시 비교**한다. *주의* Root 열(상호·구분·상태·`updated_at`·`updated_by`)이 바뀌면 Hibernate 변경 감지가 business 행을 UPDATE하며 version이 **정확히 +1**된다. 같은 ms·같은 행위자의 하위 행만 바뀐 수정은 Root 열이 그대로라 변경 감지 UPDATE가 없으므로 그때만 `PESSIMISTIC_FORCE_INCREMENT`로 +1 한다(둘 다 하면 +2 — MERCHANT-CODE-001 MC-4 교훈, `JpaMerchantRepository`와 같은 방식, E-2). `OPTIMISTIC_FORCE_INCREMENT`는 쓰지 않는다(commit 시 증가라 같은 TX에서 확인할 수 없고 변경 감지와 겹치면 +2 — 시험 BIZ-1-4·변이 (a)).
- **flush 순서**: *역할* 현재 행 UNIQUE 생성 열(기본 담당자·주소 유형·순서)을 지키며 교체. *예* 기본 담당자를 다른 사람으로 바꾸면 옛 행 종료(UPDATE)와 새 행(INSERT)이 같은 TX에 생긴다. *주의* Hibernate는 flush 때 **INSERT를 UPDATE보다 먼저** 실행한다 → 새 기본 담당자 INSERT가 아직 닫히지 않은 옛 행과 `uk_bcon_current_primary`에서 충돌한다. 순서만 바꾼 경우(M-4 — 같은 `display_order`에 다른 행)도 `uk_…_current_order`에서 같다. **종료 UPDATE 뒤 명시 `flush()`, 그다음 새 행 `persist`**(Repository 안 고정 순서 — 시험 BIZ-1-5). 이 순서를 어기면 1062가 `BUSINESS_PROFILE_ROW`(결함, §5.2 끝)로 분류돼 503이 된다 — 업무 거절로 보이지 않는다.
- **`updatable = false`**: 하위 행 내용 열·`business_id`·`effective_from`·`created_by`, `business.registration_number`(기존). 종료 열만 갱신 가능 — 표 단위 UPDATE 권한의 adapter 측 방어(X-9).
- **영속성 컨텍스트·OSIV**: 조회 API는 jOOQ DTO(아래)만 — Entity를 직렬화하지 않는다(OSIV off 기존 설정 유지).
- **flush vs commit**: 1062(번호 중복)는 `insert` 안 `flush()`에서 드러나 `PersistenceFailures.translate`가 분류한다(기존 `JpaBusinessRepository.insert` 그대로) — commit 시점까지 미루지 않는다.

### 5.6 이력(MASTER `master_change_history`)·Outbox
- 명령마다 실행기 `work.record`(같은 TX) — aggregate_type `Business`, change_type: `BusinessCreated`(DRAFT·REGISTERED 생성), `BusinessProfileUpdated`, `BusinessRegistrationCompleted`.
- `detail` JSON(값은 §9 마스킹만): `{"businessId","registrationStatus","version","reason"(있으면 원문 — C-13),"changes":[{"field":"displayName","before":"…","after":"…"}, {"field":"representatives","ended":[{"name":"홍**","type":"SOLE"}],"added":[…]}, …]}`. 생성은 `changes` = 입력 항목 전부(before 없음). 사업자등록번호는 `registrationNumberMasked`만(기존 L111-116과 같음).
- Outbox payload = 명령이 넘기는 `{businessId, registrationStatus, version}` + 실행기가 자동 추가하는 `occurredAt`(`AdminCommandExecutor` L377 — N-1). 개인정보·번호·상호 없음, 기존 `BusinessRegistered`와 같은 범주 `INTEGRATION_MASTER_REFERENCE`, effectKey `<eventType>:<businessId>:<version>`. 현재 전달 소비자 없음(§1.2) — 권리 Snapshot·PAYMENT 변화 없음(TC-19).
- `BusinessRegistrationCompleted` 이력 `changes`는 첫 항목으로 `{"field":"registrationStatus","before":"DRAFT","after":"REGISTERED"}`를 갖는다(M-1 — 내용이 같아도 이력 1건).

## 6. MASTER API(web = BIZ-2 — `master.merchant.adapter.web.admin`; Use Case §6.7 = BIZ-1 — M-3)

**계층(r-2)**: web controller는 provided Use Case(`BusinessProfileQueryUseCase` — 목록·번호 조회·상세·이력·existingSummary, `BusinessProfileCommandUseCase` — 명령)만 호출한다. `JooqBusiness*Reader`는 required `…Reader` Port 구현이며 Application Query Service만 쓴다(ArchUnit `webAdapterUsesOnlyApplication` 유지).

채널 공통(기존 그대로): 서명 v1·`hasAuthority(ADMIN_SERVICE)`(D-MOPS-005), 본문 16 KiB, query 문자열 금지, `Cache-Control: no-store`, `PayCore-Request-Id`, 조회 = 조회 permit(`AdminChannelPermits.read`), 명령 = 쓰기 permit(`write`), 로그 = operation·결과 분류·상관 ID만(번호·이름·값·예외 문구 없음).

### 6.1 endpoint

| 메서드·경로 | 종류 | 본문/응답 |
|---|---|---|
| `POST /internal/v1/admin/businesses/searches` | 조회 | 목록(§6.2) |
| `POST /internal/v1/admin/businesses/registration-number-lookups` | 조회 | 번호 정확 일치 0/1(§6.3 — 합친 BIZ-LOOKUP) |
| `GET /internal/v1/admin/businesses/{businessId}` | 조회 | 상세(§6.4) — 없으면 404 `BUSINESS_NOT_FOUND` |
| `GET /internal/v1/admin/businesses/{businessId}/history/after/{seq}` | 조회 | 이력 쪽(§6.5 — 기존 가맹점 이력 경로 문법) |
| `POST /internal/v1/admin/businesses/commands/create` | 명령 | 생성(DRAFT 또는 REGISTERED) |
| `POST /internal/v1/admin/businesses/commands/update` | 명령 | 수정(상태 유지) |
| `POST /internal/v1/admin/businesses/commands/complete-registration` | 명령 | DRAFT → REGISTERED(전체 내용 포함) |
경로 ID 형식 위반은 400 `REQUEST_MALFORMED`(값 미반환). 명령의 대상 ID는 **서명 본문**(기존 C-2).

### 6.2 목록(`BusinessSearchRequestParser` — 엄격 해석, 모든 필드 선택)
- 필드: `displayNamePrefix`(1~100 code point, `DisplayName` 문자 규칙 — SQL `LIKE ? ESCAPE '!'`, `%`·`_`·`!` 이스케이프, **앞부분 일치**, C-5), `businessType`, `registrationStatus`, `createdFrom`·`createdTo`(KST 날짜 `yyyy-MM-dd`, from ≤ to, `createdTo`는 그 날 끝 포함 — 서버가 KST 00:00 경계의 UTC로 변환), `after`(`biz_` ULID), `limit`(정수 1..50, 기본 20). **`registrationNumber` 필드 없음** — 번호 검색은 §6.3만(목록을 감사 없이 유지, C-6).
- 정렬: `business_id` **내림차순**(ULID — 생성 순 최신 먼저, 유일 → 안정), keyset `business_id < after`, `limit + 1`행.
- 항목(jOOQ `JooqBusinessDirectoryReader`, `REPEATABLE_READ` 읽기 전용): `businessId`, `businessName`, `registrationNumberMasked`(SQL 마스킹), `businessType`(null 가능), `registrationStatus`, `verificationStatus`, `representativeNameMasked`(현재 대표자 첫 순서 이름 — SQL에서 첫 글자 + `*`, 없으면 null), `representativeCount`(현재), `merchantCount`, `updatedAt`. 응답 `{"items":[…],"next":"biz_…"|null}`.
- **원문 번호·대표자 원문·연락처는 SELECT 목록에 없다**(시험: select 필드 집합 단언).

### 6.3 번호 조회(합친 MERCHANT-BIZ-LOOKUP-001 — `BusinessLookupRequestParser`)
- 본문 정확히 `{"registrationNumber":"<10자리>"}`(중복 key·모르는 필드·비문자열·뒤따르는 토큰 → 400, 값 미반환).
- 응답 200 `{"items":[]}` 또는 `{"items":[{"businessId","businessName","registrationNumberMasked","registrationStatus"}]}`(UNIQUE — 0/1). 찾음·못 찾음 같은 모양(404 없음). `registrationStatus`는 **D-BIZ-006 ③**(사용자 결정 — D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001 응답 범위 확장, C-15는 구현 방식만).
- jOOQ `JooqBusinessLookupReader`: `WHERE registration_number = ?`(바인드), 원문 열 SELECT 없음. 명령 측 `findIdByRegistrationNumber` 재사용 안 함(CQRS).

### 6.4 상세(`JooqBusinessDetailReader`)
```json
{"business":{"businessId","businessName","registrationNumberMasked","businessType","registrationStatus","verificationStatus","lifecycle","version","createdAt","updatedAt","merchantCount"},
 "registration":{"corporateRegistrationNumber","openingDate","countryCode","englishName","phoneNumber","email"},
 "representatives":[{"name","representativeType"}],
 "categories":[{"categoryType","name"}],
 "addresses":[{"addressType","postalCode","baseAddress","detailAddress"}],
 "contacts":[{"name","email","phoneNumber","primary"}]}
```
- 현재 행만, `display_order` 순. 개인정보·법인등록번호 **원문**(**D-BIZ-006 ②** — 상세·수정 화면, 감사는 ADMIN `BUSINESS_VIEWED` + 조회 창). 사업자등록번호는 마스킹만(D-BIZ-006 ② "어디서나 마스킹" — 평문 열람은 Q-MOPS-04 UNMASK 대기 그대로).
- `version`은 수정 요청의 기대값.

### 6.5 이력
- `{"items":[{"seq","changeType","actorRef","occurredAt","reason","changes":[…]}],"nextAfter":seq|null}` — `master_change_history` `aggregate_type='Business' AND aggregate_id=?`, `id > seq` 오름차순, 쪽 20. 값은 저장된 마스킹 값 그대로(§9). `actorRef`는 `admin:adm_…`(기존 가맹점 이력과 같은 표시 규칙 — ADMIN이 표시명으로 바꾼다면 기존 이력 화면 규칙을 따른다).

### 6.6 명령 본문(`BusinessProfileRequestParser` — **모든 key 필수, 선택 값은 `null`**, 모르는 key·중복 key·자료형 강제 변환 거부)
- 공통: `idempotencyKey`·`actor`(`admin:` 접두)·`correlationId`(기존 `COMMON`).
- create: `registrationStatus`("DRAFT"|"REGISTERED"), `businessType`, `registrationNumber`, `displayName`, `registration{corporateRegistrationNumber, openingDate, countryCode, englishName, phoneNumber, email}`, `representatives[{name, representativeType}]`, `categories[{categoryType, name}]`, `addresses[{addressType, postalCode, baseAddress, detailAddress}]`, `contacts[{name, email, phoneNumber, primary}]`.
- update: `businessId`, `version`(JSON 정수 ≥ 0), `reason`(문자열|null), `businessType`, `displayName`, `registration{…}`, 네 목록(번호·상태 없음).
- complete-registration: update와 같은 필드(목표 = REGISTERED).
- 해석 결과: 구조 위반(JSON·key·자료형·목록 상한 초과) → 400 `REQUEST_MALFORMED`(기존, 필드 없음). 구조는 맞고 값 규칙 위반 → **400 `BUSINESS_VALIDATION_FAILED` + `fieldErrors`**(§4.6 — path·code만, 값 없음, **저장 안 함 — `admin_request` 0행**, C-16).

### 6.7 명령 판정(BIZ-1 Application — MASTER 로컬 TX 하나, `BusinessProfileService`(provided `BusinessProfileCommandUseCase`), 실행기 `AdminCommandExecutor`)
- 지문(C-9): **기존 `AdminCommandExecutor.fingerprintOf(operation, fields)` 그대로 — `m1:` 무염 SHA-256**(모든 현 MASTER 관리 명령과 같음, 새 Port·키·`.env` 없음). `fields`는 명령 필드의 정규 평탄화(`BusinessCommandFingerprintFields` — 고정 순서: 공통 제외 필드 → `registration` 6필드 → 목록마다 개수 + 행별 필드를 순서대로, null은 그대로 null → `~`). 같은 키·다른 내용 → 409 `IDEMPOTENCY_KEY_REUSED`. HMAC 전환은 MERCHANT-OPS-002 B3-3a가 모든 명령에 함께(L-13).
- create: `lockedNow()` → `findIdByRegistrationNumber` 있으면 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`(reference = 기존 ID — `admin_request`에 저장) → `Business.createDraft|createRegistered` → `beginWrites` → `insert`(business + 등록정보 + 하위 행, flush) → `record("BusinessCreated")`. 경합 진 쪽은 1062 → 새 TX 재판정 → 중복 거절로 수렴(기존 경로).
- update / complete-registration: `businesses.lockWithProfile(id)`(없으면 `BUSINESS_NOT_FOUND`) → `lockedNow()` → 생명주기 → 상태(`complete`는 DRAFT만 — `BUSINESS_NOT_DRAFT`) → version 비교(`BUSINESS_VERSION_CONFLICT`) → 변경 계산(**update만** `BUSINESS_PROFILE_UNCHANGED` — complete는 적용 안 함(M-1), 중요 변경·사유 없음 `BUSINESS_CHANGE_REASON_REQUIRED` — 둘 다) → Domain 전이(규칙 재검사 — 위반은 결함) → `beginWrites` → `update`(종료 → flush → 추가, 등록정보 제자리, business 행 version +1) → `record`.
- 잠금 순서: `admin_request` → `business` → (하위 행 — 같은 business 범위). 가맹점 등록(`business` → `merchant`)과 같은 앞 순서라 교착 없음.
- 응답(기존 틀 그대로): 성공 `{"outcome":"SUCCEEDED","reference":"biz_…","replayed"}` — 첫 응답·재생 응답 같은 모양(`admin_request`에는 reference만 저장되므로 상태·version을 응답에 싣지 않는다. 화면은 성공 뒤 상세를 다시 읽는다 — C-18).
  거절 `{"outcome":"REJECTED","rejection":"…","replayed"}` — **예외: `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`만** `"existing":{"businessId","businessName","registrationNumberMasked","registrationStatus"}`(저장 reference로 `BusinessProfileQueryUseCase.existingSummary` 조회, TX 밖 — D-BIZ-003, C-14, r-2). 다른 거절은 reference 제거(C-12 유지).
  - **existing 조회 실패(m-1)**: 거절은 이미 `admin_request`에 커밋됐다. 요약 조회(TX 밖 — **web은 provided `BusinessProfileQueryUseCase.existingSummary(businessId)`만 호출**하고 Use Case가 required `BusinessDetailReader`(jOOQ)를 쓴다. web adapter가 jOOQ Reader를 직접 쓰지 않는다 — ArchUnit `webAdapterUsesOnlyApplication`, r-2)가 실패하면 **503 `TEMPORARILY_UNAVAILABLE`**(기존 code, 본문에 값 없음, 로그 = operation·결과·상관 ID·예외 클래스만)로 응답하고 existing 없는 REJECTED를 내보내지 않는다(D-BIZ-003 안내 누락 방지). 같은 키 재전송 = 저장 거절 **재생 + 요약 재조회**(`replayed: true`, 새 업무 효과 0). 저장 reference의 사업자가 없는 경우(삭제 경로 없음 — 결함)도 같은 503. ADMIN은 이 503을 기존 `MASTER_RETRY_SAME_KEY`로 처리(버킷 확정 — 결과가 중복일 수 있으므로 환불하지 않는다).
- 새 거절(`MasterRejection` 끝에 추가 — 영속 이름): `BUSINESS_NOT_REGISTERED`, `BUSINESS_NOT_DRAFT`, `BUSINESS_VERSION_CONFLICT`, `BUSINESS_CHANGE_REASON_REQUIRED`, `BUSINESS_PROFILE_UNCHANGED`. 새 `AdminOperation`: `CREATE_BUSINESS("CreateBusiness")`, `UPDATE_BUSINESS("UpdateBusiness")`, `COMPLETE_BUSINESS_REGISTRATION("CompleteBusinessRegistration")`(기존 `REGISTER_BUSINESS`는 영속 값이라 enum 유지 — 경로만 BIZ-4에서 제거).
- 지표: 기존 `paycore.master.admin_channel.command{operation,outcome}` + 조회 `…{endpoint}` 기존 계열(값 label 없음).

### 6.8 응답 골든
`apps/master-api/src/test/resources/contracts/business-profile-response-shapes-v1.golden.json`(목록·번호 조회 찾음/못 찾음·상세·이력·명령 성공·재생·중복 거절(existing)·다른 거절·400 fieldErrors) — ADMIN에 **바이트 동일 사본**(`apps/internal-admin/src/test/resources/contracts/`), ADMIN은 `MasterResponseShape`로 읽는다(키 집합 정확 일치 — MERCHANT-CODE-001 §1.5 교훈). Demo 값만(운영값 아님).

## 7. ADMIN API·Use Case(BIZ-3 — `admin.merchantops`)

### 7.1 endpoint(세션 + CSRF, `Cache-Control: no-store`, 본문 `application/json`·16 KiB — 읽기 전 거절 415·413은 감사 없음(Q-MOPS-08))

| 메서드·경로 | 권한 상수(역할 — D-BIZ-006 ①) | 속도 제한 | 감사 |
|---|---|---|---|
| `POST /api/businesses/searches` | `BUSINESS_VIEW`(OPERATOR·APPROVER) | 사업자 조회 창 | 없음(D-MOPS-002) |
| `POST /api/businesses/registration-number-lookups` | `BUSINESS_VIEW` | 등록번호 버킷 + 사업자 조회 창 | **매 시도** `BUSINESS_BRN_LOOKUP` |
| `GET /api/businesses/{businessId}` | `BUSINESS_VIEW` | 사업자 조회 창 | `BUSINESS_VIEWED`(반환 전, 실패 → 503·결과 미반환 — D-BIZ-006 ②) |
| `GET /api/businesses/{businessId}/history?after=<seq>` | `BUSINESS_VIEW` | 사업자 조회 창 | 없음 |
| `POST /api/businesses` | `BUSINESS_CREATE`(OPERATOR) | 등록 창 + 등록번호 버킷 | `BUSINESS_CREATE`(REQUESTED + 결과) |
| `PUT /api/businesses/{businessId}` | `BUSINESS_UPDATE`(OPERATOR) | 등록 창 | `BUSINESS_UPDATE`(REQUESTED + 결과) |
| `POST /api/businesses/{businessId}/registration` | `BUSINESS_COMPLETE`(OPERATOR) | 등록 창 | `BUSINESS_REGISTRATION_COMPLETE`(REQUESTED + 결과) |
- **권한 상수 분리(m-6)**: `BusinessPermissions`(admin.merchantops application — `AdminAction` enum은 바꾸지 않음, 기존 merchantops Use Case 상수 방식)에 `VIEW = {OPERATOR, APPROVER}`·`CREATE = {OPERATOR}`·`UPDATE = {OPERATOR}`·`COMPLETE = {OPERATOR}` 네 상수를 두고 각 Use Case 메서드가 자기 상수만 쓴다(한 상수 공유 금지 — 이후 역할 변경이 한 동작에만 미치게). 판정 = 계정 현재 행 ACTIVE + 역할 집합 교집합(기존 `MerchantLookupService`·`MerchantRegistrationService` 방식). 세션 없음·비활성 → 403 감사 없음(앞단 — MERCHANT-OPS-001 §5.2), 역할 부족 → 403 + 감사 `FORBIDDEN`(감사 대상 action만). 시험은 상수별 역할 행렬(BIZ-3-2).
- **사업자 조회 창(m-2)**: 새 설정 `paycore.admin.merchant-ops.business-reads-per-minute`(새 required Port `BusinessReadLimits.businessReadsPerMinute` — 구현 `BusinessOpsSettings`, E-17, 필수·기본값 없음, 1..상한 검사 — 기존 설정 관례) — actor별 분 단위 고정 창(`ActorMinuteWindows`, 인스턴스 메모리). **목록·상세·이력·번호 조회(④단계)가 한 창을 공유**하고 가맹점 조회 창 `lookupPerMinute`·라벨 창과 별개. **Demo 값 30/분(운영값 아님 — LIVE Gate, L-14)**. 초과 → 429 `RATE_LIMITED`(MASTER 미호출). 상세는 초과 시 `BUSINESS_VIEWED` 감사 `RATE_LIMITED` 1행(개인정보 열람 시도 기록 — D-BIZ-006 ②), 목록·이력은 지표만.
- ADMIN 요청 본문: create = MASTER create 필드 + `submissionId`(32 hex — 기존), update·registration = 경로 businessId + `submissionId`·`version`·`reason` + 내용. 화면 → ADMIN 입력도 "모든 key 필수·선택 null"(같은 규칙).

### 7.2 Use Case
- `BusinessQueryService`(목록·상세·이력 — provided `BusinessQueryUseCase`): 순서 = 권한(`VIEW`) → 사업자 조회 창(§7.1 m-2) → (상세만) MASTER 조회 → 감사 `BUSINESS_VIEWED` → 반환. 목록·이력은 창 → MASTER → 반환.
- `BusinessLookupService`(번호 조회 — provided `BusinessLookupUseCase`): BIZ-LOOKUP rev 0.3 §4.2 순서 그대로 + 역할만 OPERATOR·APPROVER(D-BIZ-006 ①): ① 계정·조회 역할(없음 403 감사 없음 — 앞단) ② 형식/해석 실패 → 400 + `INVALID_INPUT`(`brn=INVALID`) — 단일 목적 endpoint라 413·415 뒤 모든 본문을 시도로 감사(BIZ-LOOKUP C-5) ③ **등록번호 버킷 공유**(`RegistrationNumberBudget.admit`) ④ 사업자 조회 창(§7.1 — 목록·상세·이력과 공유) ⑤ MASTER(TX 밖) ⑥ 감사 `OK`(`brn=123*******;results=0|1`, 찾으면 targetRef = businessId)/`UNAVAILABLE` — 감사 실패면 결과 미반환. action 이름 `BUSINESS_BRN_LOOKUP`(rev 0.3의 `MERCHANT_BUSINESS_BRN_LOOKUP` 대신 — 사업자 관리 계열, C-25).
- `BusinessProfileService`(ADMIN 명령 — provided `BusinessProfileUseCase`; MASTER의 같은 이름 Use Case와 모듈이 다르다): `MerchantRegistrationService` 순서 그대로(§1.3): 역할(`CREATE`·`UPDATE`·`COMPLETE` 상수) → 형식(`BusinessProfileInputRules` — MASTER §4.2·§4.3 규칙 복제, C-10 선례: 즉시 안내용, 권위 MASTER, 위반 400 + `fieldErrors`, MASTER 미호출) → 등록 창(`registrationsPerMinute`, 이 서비스 자체 창) → **create만 등록번호 버킷 예약** → 시도 감사 `REQUESTED` → MASTER(TX 밖, `mo1.<계정>.<submissionId>`) → 버킷 확정/환불(중복·불명·Busy 확정, 그 밖 환불 — 기존 C-13) → 결과 감사 → 응답.
  - 형식 위반 감사: create(번호가 오가는 시도)는 `INVALID_INPUT` 감사(D-MOPS-006 ④), update·registration은 지표만(기존 "다른 종류" 관례).
  - MASTER 400 `BUSINESS_VALIDATION_FAILED`(ADMIN 복제 규칙과 어긋난 경우) → ADMIN 400 + 같은 `fieldErrors`(모양 검사 — path 문법·code 집합 밖이면 503 fail-closed) + 결과 감사 `INVALID_INPUT` + 지표 `business_rule_drift`(복제 규칙 불일치 신호).
- Gateway: 새 required Port `MasterBusinessGateway` → `HttpMasterBusinessGateway`(adapter.integration — 기존 `MasterCallSigner`·`MasterOpsSettings`·redirect NEVER 재사용, 응답 상한: 조회 32 KiB·명령 8 KiB). `MasterCommand`(문자열 전용)를 확장하지 않는다(C-17).

### 7.3 응답(화면)
- 목록·번호 조회·상세·이력 = MASTER 문서 그대로(모양 검사 통과분), 화면은 `businessId`를 **그리지 않는다**.
- 명령: 200 `{"outcome":"SUCCEEDED","reference":"biz_…","replayed"}` / `{"outcome":"REJECTED","rejection","replayed","existing"?}` — `existing`은 **중복 거절 + 행위자가 조회 역할 보유**일 때만(D-BIZ-003; create는 OPERATOR라 항상 보유 — 판정은 코드로 남긴다). 400 `INVALID_INPUT`(+`fieldErrors`), 403, 409 `IDEMPOTENCY_KEY_REUSED`(기존 code — `KEY_REUSED`는 감사 결과 code, E-19), 429 `RATE_LIMITED`, 503 `MASTER_BUSY`·`MASTER_OUTCOME_UNKNOWN`·`MASTER_RETRY_SAME_KEY`·`AUDIT_UNAVAILABLE`(기존 code). 오류 본문에 입력 값 없음.
- 거절 문구는 화면 표(§8.5).

### 7.4 감사(ADMIN `admin_audit_event`, migration 없음)
| action | 결과 code | detail(고정 key=value) | targetRef |
|---|---|---|---|
| `BUSINESS_BRN_LOOKUP` | FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE | `brn=123*******;results=N` / `brn=INVALID` | 찾으면 businessId |
| `BUSINESS_VIEWED` | OK·FORBIDDEN·RATE_LIMITED·UNAVAILABLE·NOT_FOUND | 없음 | businessId |
| `BUSINESS_CREATE` | REQUESTED·OK·REJECTED·KEY_REUSED·BUSY·OUTCOME_UNKNOWN·FORBIDDEN·INVALID_INPUT·RATE_LIMITED | `sub=<8>;status=DRAFT|REGISTERED;brn=123*******`(+`replayed`·`rejection`) | 성공 = 만든 ID, **중복 = 기존 ID**(D-BIZ-003, C-14), 그 밖 없음 |
| `BUSINESS_UPDATE` | 같음 | `sub=<8>;version=<n>;reason=PRESENT|ABSENT`(+`replayed`·`rejection`) | businessId |
| `BUSINESS_REGISTRATION_COMPLETE` | 같음 | 같음 | businessId |
- action 길이 ≤ 30자(≤ 48). 사유 원문·이름·연락처·주소·상호는 ADMIN 감사·로그·지표에 없다(C-13). 변경 항목·전후 값은 MASTER 이력이 권위(같은 correlationId `mos.<submissionId>`로 대조).
- 기존 action(`MERCHANT_BUSINESS_REGISTER`·`MERCHANT_BRN_SEARCHED`)의 기존 행·의미 불변.

## 8. 화면(BIZ-4 — React, D-ADMIN-UI-003·004)

### 8.1 메뉴·라우팅
- "사업자·가맹점" 탭: **가맹점 조회 · 사업자 조회(새) · 사업자 등록(교체) · 가맹점 등록**(`navigation.ts:80-81`). `pagesFor`: 사업자 조회 = OPERATOR·APPROVER, 사업자 등록 = OPERATOR(표시만 — 판정 서버). 지침서 "사업자 관리 > 사업자 기본정보" 메뉴 이름 대신 기존 메뉴 구조를 따른다(C-26).
- 화면 상태 라우팅(App 메모리 — `screenMemory`): 사업자 조회 탭 안에서 목록 → 상세(선택한 `businessId`는 메모리만) → 이력·수정. **URL·`location`·브라우저 저장소에 `biz_`·원문 번호 없음**. 상세 링크(중복 안내·가맹점 등록 ①의 "사업자 상세 보기")는 App의 `openBusiness(ref)` 콜백으로 탭 전환 + 메모리 선택.

### 8.2 사업자 조회 탭(`BusinessesScreen.tsx`)
- 검색: 사업자등록번호(`RegistrationNumberInput` — 입력 시 다른 조건 비활성, "조회"는 번호 조회 API, 결과 0/1을 같은 표 모양으로), 상호(앞부분 일치 안내), 사업체 구분, 등록 상태, 등록일 기간. 서버 페이징("더 보기" — keyset).
- 표 열: 사업자등록번호(마스킹), 상호, 대표자(마스킹 + "외 N명"), 사업체 구분(미입력 = "미입력"), 등록 상태(임시저장/등록 완료), 진위확인("미검증"), 가맹점 수, 수정일. **Business ID 열 없음**(X-4).
- 상세: 섹션 1~6 읽기(원문), 상태 배지, "등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다." 한 줄(정책 §4), 버튼(OPERATOR): 수정, (DRAFT) 이어서 입력·등록 완료, (REGISTERED) "이 사업자로 가맹점 등록"(기존 handoff `startWithBusiness`·`registrationHandoffState` 재사용). 이력 탭: 시각·행위자·종류·사유·항목별 전후(마스킹).

### 8.3 사업자 등록 탭 / 수정(`BusinessProfileFormScreen.tsx` — 기존 `BusinessRegistrationScreen.tsx` 교체)
섹션(지침서 §3): 1 사업자 기본(구분 라디오, 번호 + "중복 확인", 상호, 법인등록번호(법인만 표시), 개업일, 국가 KR(읽기 전용), 영문 상호) · 2 사업 내용(업태·종목 각 추가/삭제 행) · 3 주소(사업장, "실사업장이 다름" 체크 시 별도 주소) · 4 대표자(1명 이상, 유형 단독/공동/각자, 행 추가) · 5 사업체 연락(전화 필수 표시, 이메일 선택) · 6 등록 담당자(이름·이메일·전화, 기본 담당자 선택) · 7 증빙 — **표시하지 않음**(P1) · 8 버튼: 임시저장 / 등록 완료 / 취소.
- 하이픈 자동: 사업자등록번호 `000-00-00000`(기존 컴포넌트), 전화 표시용 하이픈(보낼 때 숫자만), 법인등록번호 `000000-0000000` 표시(보낼 때 숫자만).
- **번호 변경 불가 사전 안내(D-BIZ-005)**: 등록(생성) 모드의 사업자등록번호 칸 아래 고정 문구 "사업자등록번호는 저장 후 변경할 수 없습니다. 저장 전에 번호를 확인하세요." — 임시저장·등록 완료 버튼 근처에도 같은 뜻 한 줄. 확인 대화상자는 두지 않는다(문구만 — 사용자 결정 범위).
- **중복 확인**: 번호 조회 API(감사·버킷 1회). 결과 "등록 가능" 또는 "이미 등록된 사업자: <상호> · <마스킹 번호> [상세 보기]"(D-BIZ-003). **번호가 바뀌면 결과 즉시 무효**(지침서 §3) — 저장 버튼은 중복 확인 여부와 무관하게 동작(권위는 DB — 확인은 안내), 저장 시 중복이면 같은 안내.
- 평문 번호: 입력 상태에만, 저장·조회 요청 본문에만, 보낸 뒤 입력란 유지 여부는 기존 규칙(제출 후 비움 — `BusinessRegistrationScreen.tsx:62`)을 따른다. 재전송 payload만 메모리(기존 리뷰 MINOR-2 규칙).
- 수정 모드: 번호 읽기 전용(마스킹) + "사업자등록번호 변경은 정책 미정으로 지원하지 않습니다"(TBD-04). 중요정보 변경 감지 시 "변경 사유"(필수) 칸 표시 — 서버 판정이 권위(`BUSINESS_CHANGE_REASON_REQUIRED` → 그 칸 오류). 사유 칸 안내 "개인정보를 적지 마세요"(L-4).
- 서버 `fieldErrors` → 해당 칸 오류 문구(path → 칸 매핑, code → 문구 표). 클라이언트 형식 검사는 같은 규칙 복제(`lib/businessProfile.ts`) — 서버가 권위.
- 등록 완료 성공 → 상세로 이동, `REGISTERED` 표시(지침서 §3). 결과 불명(503)·재전송은 기존 `RegistrationStep` 잠금 규칙("같은 내용 다시 보내기").
- CSP: inline `style` 없음(Tailwind 클래스만 — `noRuntimeStyles.test.tsx`), 외부 자원 없음.

### 8.4 가맹점 등록 ①(`MerchantRegistrationScreen.tsx` `BusinessPicker` L244)
- `api.merchantSearch`(L267) → `api.lookupBusinessByRegistrationNumber`. 결과: REGISTERED → "선택"(기존 `BusinessRef`·`offerBusiness` 불변), DRAFT → "사업자 등록 완료 후 가맹점 등록 가능" + [사업자 상세 보기] 링크(**D-BIZ-006 ③** 문구, 선택 불가 — MASTER가 어차피 거절. 상세 링크는 조회 역할에만 — OPERATOR는 항상 보유), 없음 → "이 번호로 등록된 사업자가 없습니다. 사업자 등록에서 먼저 등록하세요."
- KDoc 한계 문장(L28 근처 "가맹점이 하나도 없는 기존 사업자는…") 삭제·근거 갱신(D-BIZ-004).

### 8.5 문구(거절·오류 — `RegistrationStep.tsx:21` 표 확장, 새 파일 없이)
`BUSINESS_REGISTRATION_NUMBER_DUPLICATE` "이미 등록된 사업자등록번호입니다"(+ existing 요약), `BUSINESS_NOT_REGISTERED` "등록 완료된 사업자만 가맹점을 등록할 수 있습니다", `BUSINESS_NOT_DRAFT` "이미 등록 완료된 사업자입니다", `BUSINESS_VERSION_CONFLICT` "다른 사용자가 먼저 수정했습니다. 다시 불러온 뒤 수정하세요", `BUSINESS_CHANGE_REASON_REQUIRED` "중요정보를 바꾸려면 변경 사유가 필요합니다", `BUSINESS_PROFILE_UNCHANGED` "바뀐 내용이 없습니다". 403·429·503은 기존 `ERROR_MESSAGES`(BIZ-LOOKUP C-11).

## 9. 개인정보·식별번호 노출 규칙(D-BIZ-006 ② — 세부 마스킹 형식은 C-2)

| 값 | 목록 | 상세·수정(조회 역할, 감사) | MASTER 이력·화면 이력 | ADMIN 감사 | 로그·지표·오류 본문·Outbox |
|---|---|---|---|---|---|
| 사업자등록번호 | `123*******` | `123*******`(평문 없음 — D-BIZ-006 ②, Q-MOPS-04 대기) | `123*******` | `brn=123*******` | 없음 |
| 법인등록번호 | 없음 | 원문 | `110111*******` | 없음 | 없음 |
| 상호·영문 상호·업태·종목·개업일 | 상호만 | 원문 | 원문(비개인정보로 취급 — 개인사업자 상호에 이름이 들어갈 수 있음은 L-3) | 없음 | 없음 |
| 대표자·담당자 이름 | 대표자 첫 글자 + `*`(예 `홍**`) | 원문 | `홍**`(첫 code point + 나머지 `*`, 1글자면 `김*`) | 없음 | 없음 |
| 이메일(사업체·담당자) | 없음 | 원문 | `a***@e***.com`(local 첫 글자, domain 첫 라벨 첫 글자, 마지막 라벨 유지) | 없음 | 없음 |
| 전화(사업체·담당자) | 없음 | 원문 | `****5678`(끝 4자리) | 없음 | 없음 |
| 주소 | 없음 | 원문 | 우편번호 + 기본주소 앞 두 어절 + ` ***`, 상세주소 `***` | 없음 | 없음 |
| 변경 사유 | 없음 | 이력 탭 | 원문(C-13, L-4) | `reason=PRESENT` | 없음 |
- 마스킹 함수는 MASTER Domain `BusinessPiiMask`(순수 함수 — 값 객체 `toString`도 이것을 쓴다, 기존 `BusinessRegistrationNumber.toString` 관례). 이력에는 **저장 시점에 마스킹한 값만** 쓴다(원문 이력 없음).
- 캔어리 시험: 고정 대조 값(`BusinessFixtures.CANARY_*` — 번호 `7319024865`, 대표자 "캔어리대표", 이메일 `canary.person@example.test`, 전화 `01073190248`, 상세주소 "캔어리동 101호")이 ADMIN 전 표·MASTER `admin_request`·`master_change_history`·`outbox_event`·로그·지표에서 원문 0건(BIZ-2-8·BIZ-3-6).

## 10. 기존 기능 영향

| 대상 | 영향 | 처리 |
|---|---|---|
| `merchant-ops-response-shapes-v1.golden.json`(MASTER·ADMIN 사본) | 가맹점 상세 `business` 블록·목록 키 **변경 없음**(등록 상태를 가맹점 응답에 넣지 않음, C-23) | 골든 불변 단언 |
| 권리 Snapshot·`demo-seed-snapshot-v4`·`entitlement-snapshot-v4` 골든 | 조립기는 사업자 ID·생명주기만 읽고(N-2) 등록 상태는 권리 입력 아님 → 변화 없음. 권리 원천의 Business 읽기는 Root 전용 유지(m-3) | 불변 단언(TC-19) |
| 가맹점 BRN 검색(`MERCHANT_BRN_SEARCHED`, MerchantsScreen) | 불변(가맹점 단위 검색 유지) | 기존 시험 기대값 변경 0 |
| MASTER `registerMerchant` | BIZ-4 전환에서 `BUSINESS_NOT_REGISTERED` 추가 | 기존 등록 시험 Fixture를 REGISTERED 사업자로(`BusinessFixtures.registered()` — 근거 주석 D-BIZ-001), 거절 시험 추가 |
| 기존 사업자 등록 경로(MASTER `merchant-operations/businesses`, ADMIN `/api/merchant-registrations/businesses`, `RegistrationKind.BUSINESS`, `MasterCommandKind.REGISTER_BUSINESS`, 화면 `registerBusiness`) | BIZ-4 전환에서 제거(C-27). `AdminOperation.REGISTER_BUSINESS`·감사 action은 영속 값으로 유지 | 제거 시험(404·라우트 목록), 기존 시험은 제거 또는 새 경로로 이관(목록 보고) |
| Demo seed | 새 DB: 단계 1 = `CreateBusiness`(REGISTERED Demo fixture). 기존 Demo DB: `RegisterBusiness` 기록 선확인 → 재사용(그 사업자는 DRAFT로 남음 — 가맹점 이미 등록됨) | C-24, BIZ-2-9 |
| MERCHANT-BIZ-LOOKUP-001 | 이 작업에 합침. 수용 기준 "권한 OPERATOR" → OPERATOR·APPROVER, 응답에 `registrationStatus`(D-BIZ-006 ①③ — 사용자 결정) | tasks.json 상태 변경(§13 끝) |
| MERCHANT-OPS-002 B3-3a(HMAC) | 새 명령 3종도 `m1:` — B3-3a가 hmac1 필드 단위 설계로 **이 작업의 `CreateBusiness`·`UpdateBusiness`·`CompleteBusinessRegistration`을 포함한 모든 명령**을 함께 전환(C-9) | B3-3a 범위에 세 operation 추가 필요(Coordinator가 tasks.json B3-3a 메모에 기록, L-13) |
| harness 시험(`harness/tests/**`) | Demo E2E에 사업자 등록 단계가 있으면 새 화면·API로 갱신 | BIZ-4에서 대상 목록 확인·보고 |
| 운영 홈·거래·매입·승인 화면 | 없음 | — |

## 11. 8대 원칙 — 원칙별 증거

| 원칙 | 이 작업의 의미 | 증거(§14 시험 ID) |
|---|---|---|
| Financial Consistency | 금액·권리·정산 효과 없음. 사업자 등록이 KYB·계약·MID·정산·Snapshot을 만들지 않음. 새 가맹점 등록은 REGISTERED 사업자만(기준정보 일관성) | BIZ-1-14(TC-19 — merchant·contract·merchant_account·entitlement_issue 행 수 불변, Outbox는 `Business*`만), BIZ-4-1(`BUSINESS_NOT_REGISTERED`), 골든 불변 |
| Transaction Safety | Business·등록정보·하위 행·변경 이력·Outbox·멱등 결과가 MASTER 로컬 TX 하나. MASTER 호출은 ADMIN DB TX 밖, ADMIN 감사는 별도 로컬 TX | BIZ-1-7(TC-17 하위 행 실패 → 전체 rollback, 이력·Outbox·admin_request 0행), BIZ-3-4(감사 실패 → 결과 미반환·MASTER 미호출) |
| Idempotency | submissionId → `mo1.` 키, 기존 `m1:` 지문(같은 키·같은 내용 = 재생, 다른 내용 = 409 — C-9, HMAC 전환은 B3-3a·L-13), 더블클릭 = 같은 키, 중복 요약 조회 실패 → 같은 키 재전송 = 재생 + 재조회 | BIZ-1-11(재생·409·지문 `m1:`·원문 없음), BIZ-2-4(HTTP 재생·existing 실패 503 → 재전송), BIZ-4-2(화면 재전송 같은 submissionId) |
| Concurrency Control | 번호 UNIQUE(동시 생성 1건만), `business` FOR UPDATE + version 명시 비교(동시 수정 1건만), 현재 행 생성 열 UNIQUE(기본 담당자·주소 유형·순서 — 위반은 결함 분류, M-4), 버킷 원자 예약 | BIZ-1-8(TC-07 실제 MySQL 다중 연결), BIZ-1-9(TC-14 동시 수정), BIZ-1-5(flush 순서·결함 분류), BIZ-3-3(버킷 동시 20 → 정확히 한도) |
| Recoverability | 결과 불명(503·응답 유실) → 같은 submissionId 재전송이 저장 결과 재생, 1062 → 새 TX 재판정으로 중복 거절 수렴, 중복 요약 조회 실패 503 → 재전송이 재생 + 재조회(m-1), 등록 완료 재요청 무해 | BIZ-2-5(commit 후 응답 유실 재생), BIZ-2-4(existing 실패 → 재전송), BIZ-1-8, BIZ-1-12(TC-16), BIZ-3-5 |
| Reconciliation | 금융 대사 대상 아님(기관·원장 없음). 대응 기록 대조: ADMIN 감사 `mos.<sub>` ↔ MASTER `admin_request`·`master_change_history`(같은 correlation), `business` 1 : `business_registration` 1 불변식 점검 | BIZ-1-2(1:1 — TC-18, migration 뒤 전 행), BIZ-3-7(감사 ↔ 이력 상관 ID 대조) |
| Auditability | 누가·언제·무엇을(마스킹 전후)·왜(중요정보 사유 — REGISTERED 대표자 집합 변경 포함, M-2) — MASTER 이력, 모든 시도 — ADMIN 감사(번호 조회·생성·수정·등록 완료·상세 열람·상세 창 초과), 감사 실패 = 행위 실패 | BIZ-1-13(이력 내용·마스킹·변경 계산 표), BIZ-1-12(등록 완료 이력 1건), BIZ-3-2(감사 행렬), TC-12·TC-13 |
| Observability | 기존 명령·조회 지표 + `rate_limited{kind}`·`business_rule_drift`·`business_lookup{result}`(값 label 없음), 구조화 로그(operation·결과·상관 ID만), canary 비노출 | BIZ-3-6(지표·로그 canary), BIZ-2-8 |

## 12. 기술 연결(이번 변경에 한함)
- **JPA**: §5.5 — 순수 Domain + `Jpa*Entity` + Mapper 분리, 연관 매핑 없음, `@Version` + FOR UPDATE 명시 비교, flush 순서, `updatable = false`. 명령 전용.
- **jOOQ(CQRS)**: 목록·번호 조회·상세·이력 = `JooqBusinessDirectoryReader`·`JooqBusinessLookupReader`·`JooqBusinessDetailReader`·`JooqBusinessHistoryReader`(required `…Reader` Port, `adapter.query`, 읽기 전용 TX, SQL 마스킹, 시각 UTC 문자열 `MasterSqlText.instant` — TZ-FIX-001). 조회에 Aggregate를 로드하지 않는다. jOOQ codegen은 V15 schema에서 재생성(생성기는 `db/migration/master`만 적용 — 권한 migration 제외 기존 구조).
- **버전**: 새 의존성 없음. Boot BOM 관리(Hibernate·jOOQ·Jackson 3) 그대로 — 라이브러리 개별 override 금지(TECHNOLOGY §2). 프론트도 새 npm 패키지 없음(lockfile 검사 D-ADMIN-UI-002 불변).
- **멱등 지문**: 새 암호 키·라이브러리 없음 — 기존 `m1:` SHA-256 재사용(C-9, L-13).
- **Kafka/Redis**: 도입하지 않는다. 동시 생성 방지는 DB UNIQUE, 동시 수정은 version·행 잠금, 속도 제한은 기존 인스턴스 메모리 버킷(L-9 LIVE Gate). Redis 분산 잠금·캐시나 Kafka 이벤트가 이 정확성을 대신하지 않는다 — Outbox 기록은 기존 MASTER 참조 이벤트 범주에 쌓일 뿐 소비자 도입은 별도 판단.

## 13. 배치(전달 순서 = 이 순서, 각 배치 RED → 최소 구현 → 관련 검증 → Verifier → Reviewer)

선행: **MERCHANT-CODE-001 MC-4 완료·커밋**(D-BIZ-004). 착수 시 줄 참조·migration 번호 재확인.

| 배치 | 내용 | 완료 기준 |
|---|---|---|
| **BIZ-1** MASTER Domain·DB·Application | V15·V16, Domain(§4 — 값 객체·`BusinessProfile`·규칙·전이·변경 계산·`BusinessPiiMask`, `requireRegistered` 메서드(호출은 BIZ-4)), Entity·Repository(Root 전용 `lock`·`find` 유지 + `lockWithProfile`·`insert`·`update` — m-3)·Mapper, `PersistenceFailures` 새 UNIQUE 결함 분류(M-4), **Application Use Case `BusinessProfileService`(create·update·complete — 기존 `AdminCommandExecutor`·`m1:` 지문 필드 평탄화 C-9, 새 거절·`AdminOperation` 3종, 이력·Outbox — M-3)**, 옛 `registerBusiness`의 과도기 저장(DRAFT·구분 NULL·등록정보 1행 — X-10), jOOQ 재생성 | BIZ-1-1~14 통과, migration 빈 DB·기존 Demo 형태 DB 둘 다 적용, 기존 MASTER 시험 기대값 변경 0(목록 보고) |
| **BIZ-2** MASTER web | §6 endpoint·parser(`BusinessProfileRequestParser`·검색·번호 조회)·JSON·조회 Use Case(provided `BusinessProfileQueryUseCase` — 목록·번호 조회·상세·이력·existingSummary, Application Query Service) + required Reader(`JooqBusiness*Reader`) — web은 Use Case만 호출(r-2)·중복 existing 조회와 실패 503(C-14, m-1)·fieldErrors 400, Demo seed 단계 1 전환(C-24), 골든 | BIZ-2-1~10 통과, 옛 MASTER 경로 동작 유지(대조 시험) |
| **BIZ-3** ADMIN API | §7 endpoint·Use Case·권한 상수 4종(m-6)·사업자 조회 창 설정(m-2)·Gateway·응답 모양·감사·버킷, 골든 사본 | BIZ-3-1~8 통과, 기존 ADMIN 시험 기대값 변경 0(BIZ-LOOKUP 계획분 제외 — 해당 없음, 아직 구현 안 됨) |
| **BIZ-4** 화면 + 전환(한 배포 단위, C-27) | §8 화면, 가맹점 등록 ① 연결, **MASTER `Merchant.register`에 `requireRegistered()` 활성화**, 옛 사업자 등록 경로 제거(MASTER·ADMIN·화면), harness E2E 갱신 | BIZ-4-1~7 통과, Demo 시나리오(§14 끝) Coordinator 확인 |
**LIVE Gate(m-3)**: BIZ-1 배포(V15) 전 MASTER 모든 인스턴스 정지 — 이전 버전 동시 운용 금지(L-8).
BIZ-1~3 사이에는 화면 변화가 없고 옛 화면·경로가 그대로 동작한다(배포 경계에서 깨지지 않음 — MERCHANT-CODE-001 §1.5 교훈). 그 사이 옛 경로로 만든 사업자는 DRAFT·구분 NULL(D-BIZ-002 적용 시점 = BIZ-4 전환, M-7). Demo 확인은 BIZ-4 뒤(screen-first).
BIZ-1의 Application Use Case는 web 없이 Use Case 시험(실제 MySQL)으로 검증한다 — 동시성·rollback·멱등 시험(BIZ-1-7~9·11)이 `admin_request`를 쓰는 것은 이 배치 범위 안이다(M-3).

## 14. 시험·수용 기준(배치별 — Verifier 확인 항목)
공통: exit code·시험 개수·0건/skip 실패·보고서·source fingerprint·명세 hash·시각, 대조군 단언, **RED 먼저**(RED 실패 이유 기록). Fixture(Test Fixture Policy): `BusinessFixtures.individualRegistered()`·`corporationRegistered()`·`draftMinimal()`·`legacyDraft()`(구분 NULL — migration 행)·`withoutMerchants(number)`·`withMerchant(...)` — 업무 조건이 이름에 드러나는 Named Fixture, 정상 Fixture는 §4 불변식 만족(독립 random은 사업자등록번호·ID뿐 — 저장소 관례대로 `SecureRandom`(MasterFixture와 같음), Fixture 코드는 `src/test`에만 — rev 0.2.3 n-3). 실제 DB 시험은 Testcontainers MySQL(기존 초기화 사본 방식).

**지침서 TC 매핑**: TC-01 → BIZ-1-1·BIZ-2-2(개인), TC-02 → 같음(법인), TC-03 → BIZ-1-1·**BIZ-2-3**(법인번호 누락 REGISTERED 실패 — API fieldErrors, m-9), TC-04 → BIZ-1-1·BIZ-2-2(DRAFT 최소만 저장 성공), TC-05 → BIZ-1-1·BIZ-2-3(필드 오류 목록), TC-06 → BIZ-2-2·BIZ-3-1(중복 → existing·생성 0), TC-07 → BIZ-1-8, TC-08 → BIZ-1-3(JOINT 2명 저장·재조회), TC-09 → BIZ-1-1·**BIZ-2-3**(대표자 0명), TC-10 → BIZ-1-3, TC-11 → BIZ-1-1·**BIZ-2-3**(등록 담당자 누락), TC-12 → BIZ-1-13(일반정보 수정 이력·사유 선택), TC-13 → BIZ-1-6·BIZ-1-13·BIZ-2-3(중요정보 사유 필수·기록, 번호 변경 필드 없음 — 2인 승인은 TBD-07이라 범위 밖), TC-14 → BIZ-1-9, TC-15 → BIZ-3-2·BIZ-2-1(서버 인가 — 화면 숨김 무관), TC-16 → BIZ-1-12·BIZ-2-6, TC-17 → BIZ-1-7, TC-18 → BIZ-1-2, TC-19 → BIZ-1-14, TC-20 → BIZ-2-10, TC-21 → BIZ-1-10·BIZ-4-5, TC-22 → **범위 밖(P1, TBD-09)** — 시험 없음, 파일 endpoint 부재 단언만(BIZ-3-8).

**BIZ-1 MASTER Domain·DB·Application**
1. `BusinessProfileRulesSpec`(Kotest BehaviorSpec — 표 시험): §4.2 값 경계(길이·문자·형식 각 경계 ±1), §4.3 행 단위·Aggregate 단위·DRAFT/REGISTERED 필수 표 전부(TC-01~05·09·11), **같은 이름·유형 대표자 두 행 허용**(m-5 — 대조: 같은 유형·같은 이름 업태는 `DUPLICATE`), 필드 오류 path·code 결정적 순서·값 미포함·최대 50.
2. `BusinessSchemaMigrationSpec`(MySQL): 빈 DB와 **기존 행이 있는 V14 상태 DB**에 V15 적용 → 기존 행 DRAFT·구분 NULL·NOT_CHECKED·updated=created, 모든 business에 등록정보 정확히 1행(TC-18), CHECK 위반 삽입 거부(VERIFIED·REGISTERED+구분 NULL·국가 US·법인번호 12자리 등), 생성 열 UNIQUE(현재 기본 담당자 2 → 1062), CHECK 이름 충돌 없음. L-8 복구 절차(m-7): 문장 (1)~(4) 각 지점까지 부분 적용한 DB에서 점검 SQL이 적용 범위를 정확히 판정하고, 되돌림 SQL 후 V14 상태 확인 → `repair` → V15 재실행 성공. **전제 점검 거부(r-1)**: migration 밖 값이 있는 부분 적용 DB(하위 표 1행 / 등록정보 선택 열 값 / `registration_status` = 'REGISTERED' / `updated_at` ≠ `created_at` 각각) → 전제 점검이 실패를 보고하고 되돌림 스크립트가 **실행을 거부**(DROP 0, 행·열 보존).
3. `JpaBusinessRepositorySpec`(MySQL): 생성 → 재조회 동일(JOINT 2명·업태/종목 복수·주소 2·담당자 2 — TC-08·TC-10), 수정 diff: 같은 (내용 + 순서) 행 유지(행 ID·`effective_from` 불변)·바뀐 행 종료+추가·종료 행 `effective_to`·`ended_by` 기록, **순서만 바꾼 수정(대표자 2명·업태 2개 자리 교환) = 두 행 종료 + 두 행 추가**(M-4), 물리 삭제 0(행 수 단조 증가). **Root 전용 경로(m-3)**: `lock`·`find`·`JpaEntitlementSourceStore` 경유 Business 읽기에서 하위 4표·`business_registration` SELECT 0(Hibernate 통계/SQL 포획), `lockWithProfile`만 하위 행을 읽는다.
4. version: 수정 1회 → version 정확히 +1(하위 행만 바뀐 경우 포함 — 변이 (a) force increment 추가 → RED).
5. flush 순서·결함 분류: 기본 담당자 교체·주소 교체·**순서만 교환**이 1062 없이 성공(변이 (b) 명시 flush 제거 → RED). `PersistenceFailures.constraintOf`가 새 UNIQUE 7개 이름을 모두 `BUSINESS_PROFILE_ROW`(`businessKey = false`)로 분류(표 시험 — M-4), 실행기에서 이 위반을 주입하면 새 TX 재판정·재시도 0회·`TemporarilyUnavailable`(503)·로그에 제약 이름만.
6. 불변·권한: `registration_number`·하위 행 내용 열 UPDATE SQL이 생성되지 않음(Hibernate 통계/SQL 포획 — 종료 열만), runtime 계정으로 하위 표 DELETE → 1142.
7. **rollback(TC-17)**: 하위 행 저장 중 실패 주입(대역 Repository 예외·DB CHECK 위반) → business·등록정보·하위 행·이력·Outbox·`admin_request` 모두 0행, 같은 키 재시도 성공(Use Case 수준 — M-3).
8. **동시 생성(TC-07)**: 같은 번호 서로 다른 키 2~8 연결 동시 → 정확히 1 SUCCEEDED, 나머지 저장된 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`(503 아님, reference = 이긴 쪽 ID), business 1행.
9. **동시 수정(TC-14)**: 같은 version 두 수정 동시 → 1 성공·1 `BUSINESS_VERSION_CONFLICT`, 이력 1건. 수정 vs 가맹점 등록 동시 → 교착 없음(잠금 순서).
10. TC-21: Domain에 VERIFIED 생성 경로 없음(enum 값 1개 단언), DB CHECK가 다른 값 거부. ArchUnit: `master.merchant.domain`의 새 타입이 Spring/JPA/jOOQ 비의존(기존 규칙 대상 포함 확인).
11. **멱등(C-9 — BIZ-2-4에서 이동)**: 같은 키·같은 명령 재생(`replayed = true`, 새 행 0), 같은 키·다른 내용(목록 순서만 다른 경우 포함) → `IDEMPOTENCY_KEY_REUSED`, `admin_request` 지문 열이 `m1:`로 시작하고 `AdminCommandExecutor.fingerprintOf(operation, BusinessCommandFingerprintFields.of(command))`와 같음(평탄화 결정성 — 목록 개수 접두로 경계 모호성 없음: 대표자 `["a","b"]` 1행 vs 2행 다른 지문), 지문 열·로그에 canary 원문 0건. 새 설정 키·환경변수 없이 기동(키 기동 검사 없음 — 대조).
12. **TC-16 + 등록 완료(M-1)**: DRAFT에 **저장 내용과 같은 내용 + complete → SUCCEEDED, version +1, 이력 `BusinessRegistrationCompleted` 정확히 1건**(`changes` 첫 항목 registrationStatus DRAFT → REGISTERED), 같은 키 재요청 = 재생, 다른 키 재요청 = `BUSINESS_NOT_DRAFT`·데이터·version 불변. complete + 중요 변경 + 사유 없음 → `BUSINESS_CHANGE_REASON_REQUIRED`(상태 DRAFT 유지). 대조: update 같은 내용 → `BUSINESS_PROFILE_UNCHANGED`(version·이력 불변).
13. **변경 계산·이력(TC-12·TC-13 — BIZ-2-7에서 이동)**: `BusinessProfileChangeSpec` §4.5 표 전 행 — 첫 입력 비중요, 기존 값 변경·삭제 중요, 구분 변경 중요, **DRAFT 대표자 0 → 1 일반 / REGISTERED 대표자 추가만 중요 / REGISTERED 대표자 유형만 변경 중요 / REGISTERED 대표자 순서만 변경 중요(M-2·M-4)**, 업태 순서만 변경 일반(이력 ended/added 기록). Use Case: 일반정보만 수정(사유 없음) 성공 + 이력 항목·마스킹 전후, 중요정보 사유 없음 → `BUSINESS_CHANGE_REASON_REQUIRED`(이력 0), 사유 있음 → 이력 `reason` 원문·대표자 ended/added 마스킹, version 불일치 → `BUSINESS_VERSION_CONFLICT`.
14. **TC-19(BIZ-2-10에서 이동)**: create·update·complete 전후 `merchant`·`contract*`·`merchant_account`·`default_designation`·`entitlement_issue` 행 수 불변, Outbox 새 행 eventType ⊂ {BusinessCreated, BusinessProfileUpdated, BusinessRegistrationCompleted}, payload 키 집합 = {businessId, registrationStatus, version, occurredAt}(occurredAt은 실행기 자동 추가 — N-1).
변이: (a) Root 변경과 무관한 force increment 추가(`OPTIMISTIC_FORCE_INCREMENT` 무조건 / 첫 flush 전 `PESSIMISTIC_FORCE_INCREMENT` 무조건 — E-7) → 4 RED, (b) 위, (c) REGISTERED 필수에서 primary 검사 제거 → 1 RED, (d) 생성 열 UNIQUE 제거 migration → 2 RED, (e) 종료 대신 DELETE → 3·6 RED, (f) complete에 UNCHANGED 적용 → 12 RED(M-1), (g) REGISTERED 대표자 추가만을 일반으로 → 13 RED(M-2), (h) 하위 행 짝짓기에서 `display_order` 제외 → 3 RED(M-4), (i) `lock`이 하위 행 로드 → 3 RED(m-3), (j) 새 UNIQUE를 `businessKey = true`로 등록 → 5 RED, (k) Snapshot 발행 추가 → 14 RED.

**BIZ-2 MASTER web**
1. 채널: 서명 없음·창 밖 401, `ADMIN_SERVICE` 아님 403, query 400, 16 KiB 초과 413, 조회 permit 소진 503 `ADMIN_READ_BUSY`(쓰기 permit과 독립), 명령 permit 소진 503 `ADMIN_WRITE_BUSY`, 경로 ID 형식 위반 400.
2. create: DRAFT 최소(TC-04)·개인 REGISTERED(TC-01)·법인 REGISTERED(TC-02) 200 SUCCEEDED, 중복(TC-06) → REJECTED + `existing` 키 집합 정확(`businessId`·`businessName`·`registrationNumberMasked`·`registrationStatus`), **다른 거절에는 reference·existing 키 없음**(C-12 대조).
3. parser: 모르는 key·누락 key·null 불가 필드 null·자료형·목록 상한 초과 → 400 `REQUEST_MALFORMED`, 값 규칙 위반 → 400 `BUSINESS_VALIDATION_FAILED` + fieldErrors(값 미포함), 두 경우 `admin_request` 0행. **API 수준 fieldErrors 정확 단언(m-9)**: create REGISTERED CORPORATION 법인등록번호 null → `{"path":"registration.corporateRegistrationNumber","code":"REQUIRED"}`(TC-03), 대표자 0명 → `{"path":"representatives","code":"REQUIRED"}`(TC-09), 담당자 0명 → `{"path":"contacts","code":"REQUIRED"}`(TC-11) — complete-registration 본문에도 같은 세 경우. 번호 필드 포함 update 본문 → 400 `REQUEST_MALFORMED`(TC-13). **최대 크기 유효 본문(모든 목록 상한·최대 길이 다중바이트) ≤ 16 KiB** 단언.
4. 멱등(HTTP): 같은 키·같은 본문 재생 응답 모양 = 첫 응답 + `replayed: true`, 같은 키·다른 본문 409 `IDEMPOTENCY_KEY_REUSED`. **existing 조회 실패(m-1)**: 중복 거절 저장 후 요약 Reader 실패 주입 → 503 `TEMPORARILY_UNAVAILABLE`(본문에 `outcome`·`existing`·값 없음), `admin_request` 1행(거절 저장됨); 같은 키 재전송(Reader 복구) → 200 REJECTED + `existing` + `replayed: true`, `admin_request`·`business` 행 수 불변.
5. 응답 유실: commit 후 응답 차단(대역 필터) → 같은 키 재전송 = 저장 결과 재생.
6. complete-registration HTTP: 같은 내용 → 200 SUCCEEDED(M-1), 상세 재조회 REGISTERED·version +1, 다른 키 재요청 → REJECTED `BUSINESS_NOT_DRAFT`(reference 없음).
7. 조회 endpoint: 목록 keyset(`business_id` 내림차순, `limit + 1`, next)·필터(상호 앞부분 — `%`·`_`·`!` 이스케이프, 구분, 상태, KST 기간 경계)·가맹점 0개 사업자 포함, select 필드 집합 단언(원문 번호·대표자 원문·연락처 없음); 번호 조회 찾음/못 찾음 같은 모양 + `registrationStatus`(D-BIZ-006 ③); 상세 키 집합·원문(D-BIZ-006 ②)·사업자등록번호 마스킹; 이력 쪽 20·`nextAfter`; 없는 ID 404 `BUSINESS_NOT_FOUND`.
8. 비노출(MASTER): canary(§9)가 로그(appender)·400 본문·`admin_request`·`master_change_history`(원문)·`outbox_event`·목록/번호 조회 응답 바이트에 0건(상세 응답에만 원문 — 대조군).
9. Demo seed(C-24): 빈 DB seed → 사업자 REGISTERED·필수값 충족·단계 1 operation `CreateBusiness`, seed 재실행 재생, **옛 `RegisterBusiness` 기록이 있는 DB**(V14 상태에서 seed 후 V15 적용) 재실행 → 기록 재사용·`ContentConflict`·`StepRejected` 없음·사업자 DRAFT 유지, `demo-seed-snapshot-v4` 골든 불변.
10. TC-20: 저장소 검사 시험(`NoLegacyImsDependencySpec` — `settings.gradle.kts` 모듈, Gradle 의존성 좌표, `application*.yml` datasource URL·schema, 패키지 이름)에서 IMS·레거시 연동·이관 흔적 0건(검사 대상·패턴 목록을 보고서에 — 패턴 `(?i)\bims\b`, `legacy`·`migration` 중 사업자 경로 관련 이름).
11. **규칙 위반 경계(rev 0.2.3 — m-1, C-16 보충)**: REGISTERED 사업자 update에서 필수값 삭제(예: 대표자 0명·담당자 primary 없음) → 400 `BUSINESS_VALIDATION_FAILED` + `fieldErrors`(path·code만, 값 0) + `admin_request`·`business` 변화 0, 같은 본문 재전송도 같은 400(저장 없음). 순서: 같은 요청에 중요 변경 + 사유 없음이 함께 있으면 저장된 `BUSINESS_CHANGE_REASON_REQUIRED`(200 REJECTED). `BusinessProfileRuleViolation` 외 `IllegalArgumentException`(예: 다른 결함)은 400으로 바뀌지 않음(대조). ADMIN 쪽(BIZ-3-4)은 이 400을 효과 없음 확정으로 처리(OUTCOME_UNKNOWN 아님).
골든: `business-profile-response-shapes-v1.golden.json` 생성·키 집합 단언. 변이: (a) 중복 거절 existing 제거 → 2 RED, (b) 모든 거절에 existing → 2 RED, (c) fieldErrors에 값 포함 → 3·8 RED, (d) existing 조회 실패 시 existing 없는 REJECTED 반환 → 4 RED, (e) 목록 SELECT에 원문 번호 추가 → 7·8 RED.

**BIZ-3 ADMIN API**
1. 명령 판정 행렬(`BusinessProfileServiceSpec`): 비활성·역할 없음 403(감사 0) / APPROVER 단독 create 403 + `FORBIDDEN` / 형식 위반 create 400 + `INVALID_INPUT`(`brn=INVALID` 또는 마스킹) / update 형식 위반 400·감사 없음·지표 / 등록 창 초과 429 / 버킷 초과(create) 429 / 정상 → REQUESTED → MASTER 1회 → 결과. 각 행 MASTER 호출 수 단언. 중복 → `existing` 응답 + 감사 targetRef = 기존 businessId(D-BIZ-003 결정 고정 — 이름·주석에 결정 ID), 버킷 확정.
2. 권한·창(TC-15, D-BIZ-006 ①②): `BusinessPermissions` 상수 4종 값 단언(VIEW = {OPERATOR, APPROVER}, CREATE·UPDATE·COMPLETE = {OPERATOR} — 결정 ID 주석), 역할 행렬 — VIEWER·SECURITY_ADMIN·UNMASK 단독 → 목록·상세·이력·번호 조회 403, APPROVER → 조회 200·create/update/complete 각 403, OPERATOR → 모두. 상세 `BUSINESS_VIEWED` 감사 1행(반환 전), 감사 실패 → 503·본문 없음. **사업자 조회 창(m-2)**: Demo 설정 30에서 목록·상세·이력·번호 조회를 섞어 31번째 → 429(MASTER 미호출), 상세 초과는 `BUSINESS_VIEWED` `RATE_LIMITED` 1행, 같은 분 가맹점 조회 창(`lookupPerMinute`) 소모 0(별개 대조), 설정 키 누락·0 → 기동 실패.
3. 번호 조회(합친 BIZ-LOOKUP): rev 0.3 BL-2 시험 이관 — 매 시도 감사, 깨진 본문 전부 시도, **버킷 공유**(조회 10 → 11번째 429, 같은 분 가맹점 BRN 검색·사업자 생성도 버킷 소진), 동시 20 → 정확히 10, 자체 조회 창 분리, `businessId` 응답 포함·`registrationStatus` 포함·평문 키 없음(D-BIZLOOKUP-001 결정 고정).
4. 실패 경로: MASTER 503·timeout·모양 위반(모르는 키, 원문 `registrationNumber` 키, fieldErrors 문법 위반) → 503 + `UNAVAILABLE`/`OUTCOME_UNKNOWN` 감사, 시도 감사 실패 → MASTER 미호출·버킷 환불, 결과 감사 실패 → 503. create의 MASTER 503 `TEMPORARILY_UNAVAILABLE`(existing 조회 실패 포함 — m-1) → ADMIN 503 같은 키 재시도 안내·버킷 확정(환불 없음).
5. 재전송: 같은 submissionId → 같은 MASTER 키·재생 결과·감사 2행 추가(REQUESTED·결과 — 재시도도 감사).
6. 비노출(ADMIN): canary가 ADMIN 전 표 문자열 열(information_schema 전수)·로그·지표 label·오류 본문·Gateway 예외 메시지 0건, 감사 detail은 `brn=731*******`만.
7. 감사 ↔ MASTER 대조: 성공 create의 감사 correlationId `mos.<sub>` = MASTER 이력·Outbox correlation.
8. 범위 단언: `/api/businesses/**/documents`·`deactivate` 경로 없음(404 — TC-22·F-011 범위 밖), ADMIN migration 파일 추가 0.
변이: (a) 조회 역할에 VIEWER 추가 → 2 RED, (b) 별도 버킷 → 3 RED, (c) 감사 실패 시 결과 반환 → 2·4 RED, (d) 감사 detail에 상호 → 6 RED, (e) 중복 targetRef 제거 → 1 RED, (f) 상세에서 조회 창 생략 → 2 RED, (g) COMPLETE 상수에 APPROVER 추가 → 2 RED.

**BIZ-4 화면 + 전환**
1. MASTER 전환: `Merchant.register`가 DRAFT 사업자 → `BUSINESS_NOT_REGISTERED`(판정 순서 NOT_FOUND → NOT_ACTIVE → NOT_REGISTERED), REGISTERED → 성공, 기존 가맹점의 Site·계약·MID 명령은 DRAFT 사업자여도 성공(D-BIZ-001 "기존 무영향" 결정 고정). 옛 경로 제거: MASTER `merchant-operations/businesses`·ADMIN `/api/merchant-registrations/businesses` 404, 라우트 목록 단언.
2. Vitest `BusinessProfileFormScreen.test.tsx`: 섹션 1~6·버튼 8 렌더, 섹션 7 없음, **등록 모드에 "사업자등록번호는 저장 후 변경할 수 없습니다" 안내 표시·수정 모드 번호 읽기 전용(D-BIZ-005)**, 법인번호 칸은 법인만, 하이픈 표시·보내는 값 숫자만(번호·전화·법인번호), 중복 확인 결과가 번호 변경 즉시 무효, 중복 거절 시 기존 사업자 상호·마스킹·"상세 보기"(D-BIZ-003), fieldErrors → 칸 오류, 중요정보 변경 시 사유 칸·서버 사유 거절 표시, **화면 중요 변경 감지(§4.5 표와 같은 규칙 — r-6)**: REGISTERED 사업자에서 대표자 추가만 → 사유 칸 표시(필수), 대표자 순서만 변경 → 사유 칸 표시, DRAFT에서 대표자 0 → 1 첫 입력 → 사유 칸 없음, 업태 순서만 변경 → 사유 칸 없음, 상호 변경 → 사유 칸 표시(감지 규칙은 `lib/businessProfile.ts` 한 곳 — 서버 판정이 권위), 등록 완료 → 상세 이동·REGISTERED, 결과 불명 → 같은 내용 다시 보내기(같은 submissionId).
3. `BusinessesScreen.test.tsx`: 목록 열·마스킹·"미검증"·더 보기(cursor), 번호 검색은 번호 조회 API(목록 API에 번호 없음), 상세 원문 표시·REGISTERED만 "이 사업자로 가맹점 등록", 이력 마스킹 전후·사유.
4. `MerchantRegistrationScreen.test.tsx`: ① 번호 조회 사용(`merchantSearch` 미호출), REGISTERED 선택 가능·DRAFT 선택 불가 + 문구 "사업자 등록 완료 후 가맹점 등록 가능" + 상세 링크(D-BIZ-006 ③ — 이름·주석에 결정 ID), 없음 문구. BIZ-LOOKUP rev 0.3 BL-3-1 기대 반전 목록을 현재 줄로 다시 적어 보고(근거 D-BIZ-004 — 이름·주석에 결정 ID).
5. 결정 고정: **DOM(텍스트·모든 속성)에 `biz_` 0건**(모든 화면 상태 — `domScan`), 원문 번호는 번호 조회·생성 요청 본문에만(`fakeApi` 호출 인수 전수), `sessionStorage`·`localStorage`·`location.href`에 원문·`biz_` 0건, 진위확인은 항상 "미검증"(TC-21).
6. CSP: inline style 0(`noRuntimeStyles.test.tsx` 대상에 새 화면 포함), 새 npm 의존성 0.
7. `navigation.test.ts`: 사업자 조회 = OPERATOR·APPROVER, 사업자 등록 = OPERATOR, VIEWER에 두 탭 없음(D-BIZ-006 ①).
변이: (a) 상세 링크를 URL로 → 5 RED, (b) 목록에 Business ID 열 → 5 RED, (c) 중복 확인 무효화 제거 → 2 RED, (d) DRAFT 선택 허용 → 4 RED, (e) 번호 변경 불가 안내 제거 → 2 RED.

**Demo(Coordinator — Demo 값, 운영값 아님)**: OPERATOR가 사업자 등록 탭에서 번호·구분·상호만 임시저장 → 사업자 조회에서 "임시저장" 확인 → 수정으로 나머지 입력 → 등록 완료 → 상세 REGISTERED·미검증 → "이 사업자로 가맹점 등록" → 가맹점 등록 성공. 같은 번호로 다시 등록 → 기존 사업자 안내·상세 보기. DRAFT 사업자로 가맹점 등록 ① → "사업자 등록 완료 후 가맹점 등록 가능" + 상세 링크. 상호 변경(사유 없음 → 거절, 사유 입력 → 성공) → 이력 전후·사유. REGISTERED 사업자에 대표자 1명 추가(사유 없음 → 거절 — M-2). APPROVER 계정: 조회·상세 원문 가능·버튼 없음·명령 403. 감사 화면에서 `BUSINESS_*` 행 확인.

## 15. Coordinator 결정(가역 — 권장안, Reviewer 확인 대상)
- **C-1** 사업자 기능은 기존 `master.merchant`·`admin.merchantops` 모듈 안(새 Modulith 모듈 없음 — Business가 이미 그곳, 버킷·서명 재사용).
- **C-2** 개인정보 노출 표 §9의 **세부 마스킹 형식**(이름 첫 글자·이메일·전화 끝 4자리·주소 두 어절 등). 노출 범위 자체(상세 원문·사업자등록번호 전면 마스킹·로그/감사/이력 마스킹)는 사용자 결정 D-BIZ-006 ②.
- **C-3** migration 행만 `business_type` NULL(DRAFT 한정 CHECK), 수정 시 구분 필수(X-1).
- **C-4** 역할 집합은 사용자 결정 D-BIZ-006 ①. 구현 방식만 Coordinator 선택: `AdminAction` enum은 바꾸지 않고 `BusinessPermissions`의 **분리 상수 VIEW·CREATE·UPDATE·COMPLETE**(m-6 — 동작별 상수, 공유 금지)를 Use Case가 쓴다(기존 merchantops 방식).
- **C-5** 상호 검색은 앞부분 일치(색인 사용). 포함 검색은 운영 규모 확인 뒤(LIVE Gate).
- **C-6** 번호 검색은 POST 번호 조회 하나로만(목록 API에 번호 필드 없음 — 목록 미감사 유지, URL·로그에 번호 없음).
- **C-7** V15 문장별 ALGORITHM(INSTANT → UPDATE → COPY → INPLACE), 운영 적용 창은 L-8.
- **C-8** 번호·상호는 `business`에 유지(§5.3).
- **C-9**(Coordinator 결정, M-6) 새 명령 3종 지문 = **기존 `AdminCommandExecutor` `m1:` SHA-256**(모든 현 MASTER 관리 명령과 같음). 명령 필드는 `BusinessCommandFingerprintFields`로 `List<String?>` 평탄화(목록은 개수 접두 + 행별 필드). D-MOPS-010 ②의 키 HMAC 전환은 MERCHANT-OPS-002 B3-3a(hmac1 필드 단위 설계)가 이 세 operation을 포함한 모든 명령에 함께 적용한다. 이 작업에 새 키·`.env`·기동 검사 없음. **LIVE 사용 전 B3-3a 적용 필수(LIVE Gate)**, B3-3a Demo legacy 비교(C-47) 대상에 세 operation 포함(r-4). 알려진 한계 L-13.
- **C-10** 하위 행 = 유효기간 행(`effective_*` = PayCore 기록 시각, 법적 날짜 아님), 수정은 전체 교체 + 값 비교 diff(**행 동일성 = 내용 + `display_order`** — 같은 키 행 유지, 그 밖 종료 + 추가, 순서만 변경도 종료 + 추가 — M-4), 하위 행 ID는 API에 내보내지 않음(BIGINT 내부 키). 행 단위 완결 규칙(§4.3 — DRAFT의 부분 입력은 항목·목록 단위).
- **C-11** 하위 표 권한 표 단위 + `updatable = false` + 시험(X-9). 대안(MASTER 열 procedure 추가)은 인프라·DBA 스크립트 변경이라 채택하지 않음.
- **C-12** 사유 규칙 §4.5 표(중요정보 변경 시 필수 — "첫 입력은 변경 아님", 구분 변경은 중요, **REGISTERED 사업자의 현재 대표자 집합 변경은 추가·종료·유형·순서 모두 중요, 0명에서 첫 입력만 면제** — M-2). `BUSINESS_PROFILE_UNCHANGED`는 update에만(complete는 전이 자체가 변경 — M-1). 다른 거절의 reference 제거(MASTER C-12) 유지.
- **C-13** 사유 원문 저장 = MASTER `master_change_history.detail.reason`(1~200자), ADMIN 감사는 `reason=PRESENT|ABSENT`(AdminAuditEvent 자유 문구 금지 유지). 새 감사 표 없음(X-12).
- **C-14** 중복 거절 응답만 `existing` 요약(저장 reference로 조회), ADMIN 감사 targetRef = 기존 businessId(D-BIZ-003 — D-MOPS-003 조회 감사와 같은 수준).
- **C-15** 번호 조회 응답 `registrationStatus` 포함은 사용자 결정 D-BIZ-006 ③(D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001 확장). Coordinator 선택은 구현 방식만: MASTER Reader가 같은 행에서 읽고 ADMIN은 모양 검사 후 그대로 전달, 화면은 값으로 선택 가능 여부만 판단(권위는 MASTER `BUSINESS_NOT_REGISTERED`).
- **C-16** 필드 오류: 값 규칙은 TX 전 400 `BUSINESS_VALIDATION_FAILED`(저장 안 함), DB 의존 판정(중복·버전·사유·상태)만 저장된 거절. 같은 규칙 함수를 Domain 전이 안에서 재검사(위반 = 결함). **보충(rev 0.2.3 — BIZ-1 리뷰 m-1, Coordinator 결정)**: Domain 재검사 위반은 `BusinessProfileRuleViolation`(path·code만, 값 없음 — TX 전체 rollback, `admin_request` 0행)이다. 잠근 행 상태로만 알 수 있는 위반(예: REGISTERED 사업자 수정에서 필수값 삭제)은 parser가 미리 막지 못하므로, BIZ-2는 web/Use Case 경계에서 **`BusinessProfileRuleViolation`만** 잡아 400 `BUSINESS_VALIDATION_FAILED` + `fieldErrors`(path·code만)로 응답한다(`IllegalArgumentException` 전반을 잡지 않는다 — 다른 결함은 500/503 그대로). ADMIN은 이 400을 **효과 없음 확정**(OUTCOME_UNKNOWN 아님 — 버킷은 기존 400 처리 규칙)으로 다룬다. 판정 순서 유지: 저장된 거절(`BUSINESS_VERSION_CONFLICT`·`BUSINESS_PROFILE_UNCHANGED`·`BUSINESS_CHANGE_REASON_REQUIRED` 등)이 규칙 위반보다 먼저다 — "중요 변경 + 사유 없음"은 같은 요청에 필수값 삭제가 함께 있어도 저장된 `BUSINESS_CHANGE_REASON_REQUIRED`(BIZ-2-11이 시험).
- **C-17** ADMIN→MASTER 사업자 명령은 새 `MasterBusinessGateway`(중첩 JSON) — 문자열 전용 `MasterCommand` 불변.
- **C-18** 명령 재생 응답은 `outcome·reference·replayed`(+중복 existing)만 — 화면은 성공 뒤 상세를 다시 읽는다.
- **C-19** 길이·목록 상한(§4.2 — 기술 상한, 16 KiB 안).
- **C-20** 전화·이메일 형식(§4.2) — 형식만, 실재 확인 없음.
- **C-21** 개업일 범위 규칙 없음(정책 근거 없음).
- **C-22** 대표자 유형 규칙은 "SOLE이면 1명, 2명 이상이면 SOLE 없음"만(JOINT·EACH 혼합 허용).
- **C-23** 가맹점 응답(목록·상세)에 사업자 등록 상태를 넣지 않음(골든·ADMIN 모양 불변).
- **C-24**(m-8 — 사용자 결정 D-BIZ-001에서 분리한 Coordinator 선택) Demo seed: 새 DB는 `CreateBusiness` REGISTERED Demo fixture(번호 `0000000000`, 상호 "DEMO 사업자 (운영값 아님)", 개인, 개업일 `2026-01-01`, 업태 "DEMO 업태", 종목 "DEMO 종목", 주소 `00000` "DEMO 주소 (운영값 아님)", 사업체 전화 `0200000000`, 대표 "DEMO 대표" SOLE, 담당 "DEMO 담당" `demo@paycore.test` `01000000000` 기본) — 모두 운영값 아님. 기존 seed DB는 옛 `RegisterBusiness` 기록 선확인 후 재사용(DRAFT 유지 — 가맹점은 이미 등록됨).
- **C-25** 감사 action 이름 `BUSINESS_*`(번호 조회는 `BUSINESS_BRN_LOOKUP`).
- **C-26** 메뉴는 기존 "사업자·가맹점" 아래 탭(지침서 메뉴 이름 대신 D-ADMIN-UI-003 구조).
- **C-27** 전환(새 가맹점 등록 REGISTERED 강제 + 옛 사업자 등록 경로 제거)은 BIZ-4 화면과 한 배포 단위.
- **C-28** 목록 정렬 `business_id` 내림차순 keyset(ULID 생성 순 — 안정·유일), limit ≤ 50 기본 20.
- **C-29** 상세 열람 감사 `BUSINESS_VIEWED`(D-BIZ-006 ② — 상세 열람마다 감사, 결과 code 목록은 §7.4), 이력·목록 미감사(D-MOPS-002).
- **C-30** 법인등록번호 UNIQUE 없음(TBD-01 — 같은 법인의 지점 사업자 허용, 자동 연결 없음).
- **C-31**(m-4) 주소의 **상세주소는 선택**: 근거 — 정책 §5·지침서 §4 필수 목록은 "사업장 주소"(우편번호·기본주소)이고 상세주소를 필수로 적지 않으며, 지번·도로명 기본주소만으로 완결되는 사업장(건물 전체·단독 건물)이 있다. DB `detail_address NULL`, 화면 "(선택)" 표시. 필수화가 필요하면 정책 확정 후 CHECK·규칙 추가.
- **C-32**(m-2) 사업자 조회 창 `business-reads-per-minute`: 목록·상세·이력·번호 조회 공용, actor별 분 단위, **Demo 30**(운영값 아님 — LIVE Gate, 기본값 없음), 가맹점 조회 창과 별개. 상세 초과는 `BUSINESS_VIEWED` `RATE_LIMITED` 감사.
- **C-33**(m-1) 중복 거절의 existing 요약 조회 실패 → MASTER 503 `TEMPORARILY_UNAVAILABLE`(existing 없는 REJECTED를 내보내지 않음), 같은 키 재전송 = 저장 거절 재생 + 요약 재조회.
- **C-34**(M-4) 새 하위 표 UNIQUE 7개는 `StoreConstraint.BUSINESS_PROFILE_ROW(businessKey = false)`로 명시 등록 — 결함(재판정·재시도 없음, 503), 업무 키 아님.

## 16. 사용자 결정·질문

### 16.1 답변됨(2026-11-17, `harness/questions.json`)
| ID | 질문 | 답 | 결정 |
|---|---|---|---|
| Q-BIZ-1 | 사업자 상태 모델과 기존 데이터·가맹점 등록 관계 | 생명주기 별도 축 유지 + 등록 상태 추가, 기존 → DRAFT, 새 가맹점 등록은 REGISTERED만 (Recommended) | D-BIZ-001 |
| Q-BIZ-2 | DRAFT 최초 생성 최소 입력 | 구분 + 번호 + 상호 (Recommended) | D-BIZ-002 |
| Q-BIZ-3 | 중복 번호 안내 범위(D-MOPS-006 ③ 변경 여부) | 조회 권한자에게 상호·마스킹 번호·상세 링크, 매 시도 감사·버킷 (Recommended) | D-BIZ-003 |
| Q-BIZ-4 | 순서·MERCHANT-BIZ-LOOKUP-001과의 관계 | MC-4 먼저, BIZ-LOOKUP 합침 (Recommended) | D-BIZ-004 |
| Q-BIZ-5 | DRAFT 사업자의 사업자등록번호 오입력 처리 | 이번 작업은 그대로(변경·삭제 없음), 등록 화면에 저장 후 번호 변경 불가 안내, 정정 정책은 TBD-04/05 유지 | D-BIZ-005 |
| Q-BIZ-6 | 사업자 조회·명령 역할, 상세 개인정보 노출, 번호 조회 응답 범위 | ① 목록·상세·이력 = OPERATOR·APPROVER, 등록·수정·완료 = OPERATOR, 합친 번호 조회 = OPERATOR·APPROVER(BIZ-LOOKUP 수용 기준 변경) ② 상세 개인정보 원문(조회 역할), 상세마다 감사 + 속도 제한, 사업자등록번호 전면 마스킹, 로그·감사·이력 마스킹 ③ 번호 조회 응답 `registrationStatus` 포함, DRAFT 선택 시 "사업자 등록 완료 후 가맹점 등록 가능" + 상세 링크 | D-BIZ-006 |

### 16.2 열린 질문
없음. rev 0.1의 제안 질문 Q-BIZ-5와 Coordinator 승격 판단 대상(C-4 역할·C-15 응답 상태)은 Q-BIZ-5·Q-BIZ-6 답(D-BIZ-005·006)으로 닫혔다. 지문 HMAC(D-MOPS-010 ②) 적용 시점은 Coordinator 결정 C-9(B3-3a 일괄)이며 사용자 정책 변경이 아니다(L-13).

새 금융·보안 정책은 이 문서에서 정하지 않았다.

## 17. 위험·한계
- **L-1** 번호 조회는 가맹점 0개 사업자의 존재·상호·등록 상태를 드러내는 oracle(의도된 기능). 통제: 조회 역할·매 시도 감사·공유 버킷. MASTER 측은 `ADMIN_SERVICE` 키 유출 시 감사·버킷 없이 탐색 가능(BIZ-LOOKUP L-1 그대로 — LIVE Gate).
- **L-2** APPROVER도 상세에서 대표자·담당자 개인정보 원문을 본다(사용자 결정 D-BIZ-006 ② — 상세 열람 감사·조회 창으로 통제). 역할 축소는 사용자 결정 변경 사항.
- **L-3** 종료된 하위 행·이력의 개인정보(마스킹 이력은 일부, 하위 표는 원문)는 무기한 보관 — 보관·파기 기준 TBD(POL-006). 개인사업자 상호에 개인 이름이 들어갈 수 있으나 상호는 마스킹하지 않는다.
- **L-4** 변경 사유는 자유 문구라 운영자가 개인정보를 적을 수 있다(화면 안내만). 이력 원문은 조회 역할에 보인다.
- **L-5** 번호·상호가 `business`에 있어 TBD-03(복수 등록정보) 도입 시 소유 재설계 필요.
- **L-6** 과도기(BIZ-1~BIZ-4 사이) 옛 사업자 등록 경로는 구분 NULL DRAFT를 만든다 — **D-BIZ-002는 BIZ-4 전환부터 전면 적용**(M-7, D-BIZ-002 기록에 명시, Coordinator가 사용자에게 알림). 그 사이 만든 행은 migration 행과 같은 형태(DRAFT·`business_type` NULL)이며 처음 수정·등록 완료 시 구분 입력이 강제된다. Demo 한정 기간, BIZ-4 전환에서 경로 제거.
- **L-7** BIZ-4 전환 뒤 기존 Demo 사업자(DRAFT)에는 새 가맹점을 등록할 수 없다(D-BIZ-001 의도). Demo에서 그 사업자에 가맹점을 더 두려면 먼저 등록 완료(필수값 입력)가 필요하다.
- **L-8** V15 문장 (3)은 `ALGORITHM = COPY`(표 재작성·쓰기 차단). 운영 적용은 유지보수 창 필요(MERCHANT-CODE-001 L-14 관례).
  **부분 실패 복구(m-7, r-1)**: MySQL DDL은 문장마다 암묵 commit이라 V15는 원자적이지 않다(Flyway가 실패를 기록해도 앞 문장 효과는 남고, `ADD COLUMN`·`CREATE TABLE`은 재실행하면 실패한다). 기본 절차는 **되돌린 뒤 V15 전체 재실행**이며, 되돌림은 아래 전제 점검표를 **모두** 통과할 때만 실행한다(하나라도 다르면 중단 — 되돌리지 않고 원인 조사·별도 결정).
  - **전제 점검표(되돌림 SQL 전, 기록 필수)**
    1. MASTER web 프로세스 **전부** 정지 확인 — 현재 버전뿐 아니라 이전 버전 인스턴스(Flyway 검증 없이 기동했을 수 있음)·Demo seed 실행기 포함, MASTER DB에 runtime 계정 연결 0(`information_schema.PROCESSLIST` 확인).
    2. 하위 4표(`business_representative`·`business_category`·`business_address`·`business_contact`)가 있으면 각 행 수 = 0.
    3. `business_registration`이 있으면 행 수 ∈ {0, `business` 행 수}(0 = 문장 (5) 뒤·(6) 전 실패 — (6) `INSERT … SELECT`는 한 문장이라 일부 행만 있는 상태는 없다, E-1), 선택 열(`corporate_registration_number`·`opening_date`·`english_name`·`phone_number`·`email`) 전부 NULL, `country_code` = 'KR'.
    4. `business` 새 열이 있으면 모든 행이 백필 값과 같음 — `registration_status` = 'DRAFT'(또는 NULL — 문장 (2) 전), `verification_status` = 'NOT_CHECKED'(또는 NULL), `business_type` IS NULL, `updated_at` = `created_at`, `updated_by` = `created_by`(또는 NULL).
    5. Flyway 기록: `flyway_schema_history`에 version 15 행이 정확히 하나이고 `success = 0`, version 16 행 없음(rev 0.2.3 — m-2: 완료된 V15·V16 DB나 V15 미적용 DB는 되돌림 대상이 아니다).
    6. 1~5 중 하나라도 다르면 **되돌림 거부**(migration 밖에서 쓴 값이 있거나 되돌림 대상 상태가 아니다 — 업무 데이터 손실 위험). 1·5는 스크립트가 자동 판정(프로세스 목록·이전 버전 확인은 사람이 기록), 판정 블록은 precheck·revert 두 스크립트에 같은 내용으로 있다(n-4 — 함께 고친다).
  - **LIVE Gate(rev 0.2.3 — m-3)**: V15 적용 전 MASTER 모든 인스턴스 정지(이전 버전 동시 운용 금지). 이전 버전 앱은 새 NOT NULL 열(기본값 없음)을 모르므로 V15 뒤 옛 사업자 등록 INSERT가 실패하고, V15 중 쓰기는 COPY 문장 (3)과 경합한다.
  - 절차: ① 전제 점검표 통과·기록, ② 점검 SQL로 문장별 적용 여부 판정(`information_schema.COLUMNS` 새 열 5개, `CHECK_CONSTRAINTS` `ck_business_*`·`ck_breg_`·`ck_brep_`·`ck_bcat_`·`ck_badr_`·`ck_bcon_`, `STATISTICS` 색인 3개, `TABLES` 새 표 5개), ③ 적용된 것만 역순으로 제거하는 되돌림 SQL(새 표 DROP — 자식 → `business_registration`, 색인 DROP, CHECK DROP, 새 열 DROP — `business`의 기존 열·UNIQUE·FK·행은 건드리지 않음) — 문서화된 스크립트로 실행하고 결과를 기록, ④ 점검 SQL이 V14 상태를 확인, ⑤ `flyway repair`(실패 기록 제거) → `migrate`로 V15 재실행, ⑥ 점검 SQL로 V15 완료 확인 후 기동. Demo는 DB 재생성으로 대신할 수 있다. 전제 점검·점검 SQL·되돌림 SQL의 정확성은 BIZ-1-2가 고정한다. 운영 실행은 이 개발 요청 범위 밖(LIVE Gate).
- **L-9** 속도 제한 버킷·조회 창은 인스턴스 메모리(기존 L-9 — 다중 인스턴스 합산은 LIVE Gate). 값은 Demo 값.
- **L-10** 하위 표 내용 열 UPDATE를 DB 권한이 막지 못한다(C-11 — adapter·시험 방어).
- **L-11** 상호 앞부분 검색만 지원(C-5) — 중간 일치가 필요하면 색인 전략 재검토. **filesort(N-4)**: 상호 앞부분 조건은 `ix_business_name_id`(display_name, business_id) 범위 스캔이라 결과를 `business_id` 내림차순으로 다시 정렬한다(filesort — 일치 행 전체를 읽은 뒤 `limit + 1`). 상태 필터(`ix_business_status_id`)·기간 필터와 결합해도 같다. Demo 규모에서는 무시 가능, 운영 규모에서 짧은 접두어(1~2자)가 많은 행에 일치하면 비용이 커진다 — 최소 접두 길이·정렬 기준(상호 순 keyset) 재검토는 LIVE Gate(EXPLAIN 기록).
- **L-12** 중복 확인은 안내일 뿐 — 확인과 저장 사이 다른 운영자가 같은 번호를 저장할 수 있다(저장 시 DB 중복 거절이 권위 — 같은 안내).
- **L-13**(M-6) 새 명령 3종의 멱등 지문은 무염 SHA-256(`m1:`)이다. 지문 입력에 사업자등록번호(10자리 — 전수 대입 가능 공간)와 대표자·담당자 이름·전화·이메일이 들어가므로, `admin_request` 열람자가 다른 필드를 알면 번호·값을 대입으로 확인할 수 있다. **D-MOPS-010 ②(키 HMAC)는 이 작업에서 미적용** — MERCHANT-OPS-002 B3-3a(hmac1 필드 단위 설계)가 모든 MASTER 관리 명령과 함께 이 세 operation을 전환할 때까지 유지(Coordinator 결정 C-9). 통제: `admin_request`는 MASTER runtime 계정만 접근, 지문은 로그·응답·감사에 나가지 않음. **LIVE 사용 전 B3-3a 적용 필수(LIVE Gate — r-4)**. B3-3a의 Demo legacy 지문 비교(MERCHANT-OPS-002 C-47)는 `CreateBusiness`·`UpdateBusiness`·`CompleteBusinessRegistration`을 반드시 포함한다(이 세 operation의 기존 `m1:` 기록이 Demo DB에 남으므로 — 같은 키 재전송이 전환 뒤에도 재생돼야 한다). 이 메모는 `harness/tasks.json` MERCHANT-OPS-002 `followUps`에 기록.
- **L-14**(m-2) 사업자 조회 창 30/분·등록번호 버킷 10/분·등록 창 20/분은 Demo 값(운영값 아님 — LIVE Gate). 인스턴스 메모리 창이라 다중 인스턴스에서 합산되지 않는다(L-9). **이중 소모(r-7)**: create의 중복 요약 조회 실패 경로(m-1 — MASTER 503 → ADMIN 버킷 확정·환불 없음)에서 같은 submissionId 재전송은 ADMIN이 등록번호 버킷·등록 창을 **다시 예약**하므로 한 번의 등록 시도가 버킷을 2회 이상 소모한다(MASTER는 재생이라 업무 효과 0). Demo 한도 10/분에서 재전송이 반복되면 429가 먼저 날 수 있다 — 보수 쪽(oracle 방어) 오차로 수용, 재전송 예약 면제는 하지 않는다(결과가 중복 안내이므로 같은 정보 노출 비용).

## 18. 기록
| rev | 내용 |
|---|---|
| 0.1 | 초안(2026-11-17). D-BIZ-001~004 적용, D-MOPS-006 ③ 변경(D-BIZ-003), MERCHANT-BIZ-LOOKUP-001 합침(§6.3·§7.2·§8.4 — rev 0.3 설계 이관, 역할·응답 필드 차이 C-4·C-15), migration MASTER V15·V16, C-1~C-30, Q-BIZ-5 제안(비차단), 어긋남 X-1~X-13. 기준 HEAD `373ca6d` + MC-4 작업 트리 |
| 0.2 | 독립 리뷰(rev 0.1) 반영 + 사용자 답 Q-BIZ-5·6(2026-11-17). **M-1** 등록 완료에 `BUSINESS_PROFILE_UNCHANGED` 미적용(§4.4·§4.5·§6.7·C-12), 시험 "같은 내용 + complete → SUCCEEDED·version +1·이력 1"(BIZ-1-12·BIZ-2-6), 이력 첫 항목 registrationStatus(§5.6). **M-2** REGISTERED 사업자의 현재 대표자 집합 변경(추가·종료·유형·순서) = 중요 + 사유·전후 이력, 0명에서 첫 입력만 면제 — §4.5 변경 계산 표·BIZ-1-13·변이 (g). **M-3** Application Use Case(`BusinessProfileService`, 지문 필드 평탄화)를 BIZ-1로 이동, BIZ-2 = web·parser·JSON·Reader·골든·Demo seed — §13·§14(BIZ-1-11~14 신설, BIZ-2 재번호), tasks-entry doneCriteria 수정. **M-4** 하위 행 동일성 = 내용 + `display_order`, 순서만 변경 = 종료 + 추가(§4.1·C-10), 새 UNIQUE 7개를 `StoreConstraint.BUSINESS_PROFILE_ROW(businessKey = false)` 결함으로 등록(§5.2 끝·C-34), 순서 변경 시험(BIZ-1-3·5). **M-5** 사용자 답으로 해소 → D-BIZ-005·006(§2·§16). **M-6** Coordinator 결정 C-9: 새 명령도 기존 `m1:` 지문, `bh1:` HMAC·키·기동 검사 삭제, HMAC은 B3-3a 일괄(X-8·§10·L-13). **M-7** D-BIZ-002 적용 시점 = BIZ-4 전환, 과도기 행 DRAFT·구분 NULL(§0·§2·X-10·L-6·§13). **m-1** existing 조회 실패 → 503 `TEMPORARILY_UNAVAILABLE`, 같은 키 재전송 = 재생 + 재조회(§6.7·C-33·BIZ-2-4·BIZ-3-4). **m-2** 사업자 조회 창 `business-reads-per-minute`(목록·상세·이력·번호 조회 공용, Demo 30)·상세 초과 감사(§7.1·C-32·L-14·BIZ-3-2). **m-3** Root 전용 `lock`·`find` 유지 + `lockWithProfile` 분리(§1.1·§5.5·BIZ-1-3). **m-4** 상세주소 선택 근거 C-31. **m-5** 같은 이름·유형 대표자 거절 삭제(§4.3·§4.6·BIZ-1-1). **m-6** 권한 상수 VIEW·CREATE·UPDATE·COMPLETE 분리(§7.1·C-4·BIZ-3-2). **m-7** V15 부분 실패 복구 절차(L-8·BIZ-1-2). **m-8** Demo seed 세부를 D-BIZ-001에서 C-24로 이동. **m-9** BIZ-2-3 API 수준 fieldErrors 단언(TC-03·09·11). **m-10** tasks-entry status READY(MC-4 커밋 의존 메모 유지). **N-1** Outbox payload `occurredAt` 자동 추가 명시(§1.2·§5.6). **N-2** Snapshot 조립기는 사업자 ID·생명주기만 읽음으로 정정(§5.3·§10). **N-3** 정규화(X-14)·409 vs 200 REJECTED(X-15) 어긋남 기록. **N-4** 상호 검색 filesort 주석(L-11). **D-BIZ-005**(번호 오입력 DRAFT 그대로 + 등록 화면 안내 — §8.3·BIZ-4-2), **D-BIZ-006**(역할·상세 원문·번호 조회 `registrationStatus`·DRAFT 문구 — §0·§2·§6.3·§6.4·§7·§8.4·§9·BIZ-3-2·BIZ-4-4·7). C-1~C-34, 열린 질문 없음 |
| 0.2.1 | rev 0.2 재리뷰 REVIEW_PASS 조건부 → 조건 반영(Coordinator 지시, 2026-11-17). **r-1** L-8 되돌림 전제 점검표(MASTER web 전 프로세스·이전 버전·Demo seed 정지, 하위 4표 0행, 등록정보 = business 행 수·선택 열 NULL, 새 열 = 백필 값, 다르면 중단) + BIZ-1-2 "migration 밖 값 → 되돌림 거부" 시험. **r-2** existing 요약·모든 조회는 provided `BusinessProfileQueryUseCase`(existingSummary 포함) 경유 — web의 jOOQ Reader 직접 사용 금지(ArchUnit `webAdapterUsesOnlyApplication`, §6 머리·§6.7). **r-3** decisions.md D-BIZ-002 적용 시점 문구를 `[Coordinator 적용 메모 — 사용자 알림 2026-11-17]`로 분리. **r-4** L-13·C-9: LIVE 사용 전 B3-3a 적용 필수(LIVE Gate), B3-3a Demo legacy 비교(C-47)에 세 operation 포함 — tasks.json MERCHANT-OPS-002 `followUps`에 메모. **r-5** 기준 HEAD `6ccb310`. **r-6** BIZ-4-2 화면 중요 변경 감지 사례(REGISTERED 대표자 추가만·순서만 → 사유 칸). **r-7** L-14 m-1 재전송 경로 버킷 이중 소모 |
| 0.2.2 | BIZ-1 구현 중 발견한 정정(2026-11-17, Implementer — 기준 HEAD `cbfed46`, 업무 정책·사용자 결정·시험 기대 완화 없음). 줄 참조(§1.1·§1.2 — `Business.kt`·`JpaBusinessEntity.kt` L17·L28·L48-51·`PersistenceFailures.kt` L74·`MerchantOnboardingService` L93-119·`AdminCommandExecutor` L377·L415-424·V1 L20-34)를 `cbfed46`에서 재확인 — 일치, MASTER V15·V16 미사용 확인. **E-1** L-8 전제 점검 3: 등록정보 행 수 ∈ {0, business 행 수}(문장 (5) 뒤·(6) 전 실패를 되돌릴 수 있게 — (6)은 원자적 한 문장). **E-2** §5.5 version: 같은 ms·같은 행위자의 하위 행만 수정은 Root 열 변경이 없어 변경 감지 UPDATE가 없다 → 그때만 `PESSIMISTIC_FORCE_INCREMENT`(정확히 +1 유지). **E-3** 과도기 옛 경로(`Business.register` — X-10)도 구분 NULL을 만든다(§4.4 "null은 reconstitute만"의 예외 — 경로는 BIZ-4 전환에서 제거). **E-4** 업태·종목 순서는 유형 안 순서(DB `display_order` 유형별 — §5.2)라 유형 사이 끼워 넣기 순서는 저장되지 않는다 → Domain `BusinessProfile.canonical()`(업태 → 종목, 주소 REGISTERED → ACTUAL)로 저장·비교, 지문은 요청 순서 그대로. **E-5** 필드 오류 code 세부: 주소 유형 중복 = `addresses[i].type` `TOO_MANY`(`DUPLICATE`는 업태·종목만), 실사업장 = 사업장 = `addresses` `ACTUAL_SAME_AS_REGISTERED`. **E-6** 이력 행 항목에 `order`(표시 순서) 키 추가(순서만 바뀐 종료 + 추가를 이력에서 읽을 수 있게 — §5.6 예시 확장, BIZ-2 골든이 고정). **E-7** 변이 (a) 정의: 첫 flush 뒤의 무조건 `PESSIMISTIC_FORCE_INCREMENT`는 Hibernate가 이미 변경 감지로 올린 version을 다시 올리지 않아 +1로 남는다(관찰 — 위험 아님) → (a)는 무조건 `OPTIMISTIC_FORCE_INCREMENT`·첫 flush 전 무조건 `PESSIMISTIC_FORCE_INCREMENT` 두 변형으로 확인. **E-8** Domain 전이 안 규칙 재검사 위반은 `BusinessProfileRuleViolation`(값 없는 path·code 목록 — TX 전체 rollback·`admin_request` 0행, C-16 "결함"). update의 REGISTERED 필수값 삭제는 잠근 행 상태로만 판정되므로 parser가 미리 막지 못한다 → BIZ-2가 이 예외를 400 `BUSINESS_VALIDATION_FAILED`로 응답할지 결정(Coordinator 확인 항목 — 금융·보안 의미 아님). 실행기 `AdminCommandExecutor.classify`에 업무 키·서버 생성 ID가 아닌 UNIQUE 위반의 결함 WARN 로그(제약 분류 이름만) 추가(§5.2 끝 "결함 로그" 이행). 복구 스크립트: `apps/master-api/src/main/resources/db/recovery/master/business-profile-v15-{precheck,inspect,revert}.sql`(되돌림은 전제 점검 위반 시 오류 1146으로 첫 DROP 전 정지) |
| 0.2.3 | BIZ-1 독립 리뷰 REVIEW_PASS 조건부(BLOCKER·MAJOR 없음) → MINOR·NIT 반영(Coordinator 지시, 2026-11-17). **m-1**(Coordinator 결정 — E-8 처리): BIZ-2는 `BusinessProfileRuleViolation`만 잡아 400 `BUSINESS_VALIDATION_FAILED` + fieldErrors(path만, 값 없음), `IllegalArgumentException` 전반 금지, ADMIN은 효과 없음 확정(OUTCOME_UNKNOWN 아님), 저장된 거절(사유 필요 등)이 규칙 위반보다 먼저 — C-16 보충·BIZ-2-11 신설, BIZ-1 코드 변경 없음. **m-2** L-8 전제 점검 5(Flyway 기록 = V15 실패·V16 없음) — precheck·revert 스크립트에 추가, 완료된 V15+V16 DB 되돌림 거부 시험(강화). **m-3** LIVE Gate: V15 적용 전 MASTER 모든 인스턴스 정지(L-8·§13). **m-4** 전이 기록 시각 단조: `Business` 전이의 갱신 시각 = max(판정 시각, 저장된 updatedAt) — 시계가 뒤로 가도 하위 행 effective_to ≥ effective_from(시험: 저장 시각보다 이른 시계로 수정 성공·3819 없음). 이력·Outbox occurredAt은 판정 시각 그대로. **n-1** 부분 실패 지점 7·8 시험 추가·주석 정정. **n-2** 실행기 결함 WARN은 `businessKey == false`인 UNIQUE만. **n-3** §14 Fixture 문구를 저장소 관례(SecureRandom 번호)로 정렬. **n-4** 복구 스크립트에 runtime 계정 이름 환경 치환·판정 블록 이중 유지 주석. **n-5** TC-19 시험이 이 사업자 밖 새 Outbox 행 0도 단언 |
| 0.2.4 | BIZ-2 구현 중 정한 구현 세부·정정(2026-11-17, Implementer — 기준 HEAD `2821dc6`, 업무 정책·사용자 결정·시험 기대 완화 없음). **E-9** 명령 경로는 `/internal/v1/admin/businesses/commands/{create,update,complete-registration}`(§6.1 그대로), 컨트롤러는 조회 `BusinessQueryController`(조회 permit)·명령 `BusinessCommandController`(쓰기 permit) 둘로 나눔. **E-10** 해석 세부(§6.6): ① 값 형식 위반이 하나라도 있으면 형식 오류만 돌려주고 Aggregate 규칙(§4.3)은 판정하지 않는다(행을 만들 수 없음), ② 열거 값(구분·대표자·업태/종목·주소 유형)은 빈 문자열 REQUIRED·모르는 값 FORMAT(필드 오류), `registrationStatus`(create — DRAFT·REGISTERED 밖)·`businessId`·`version`·공통 필드 위반은 REQUEST_MALFORMED, ③ 목록 전체 상한은 대표자 5·업태+종목 20·주소 2·담당자 5(구조 — 유형별 10 초과는 규칙 `categories` TOO_MANY), ④ update 해석은 DRAFT 공통 규칙만(잠근 행 상태의 REGISTERED 유지 규칙은 Domain 재검사 → `BusinessProfileRuleViolation` → 400, BIZ-2-11), complete는 REGISTERED 규칙, ⑤ 대표자 유형 필드 오류 path는 §4.6 문법대로 `representatives[i].type`(본문 key는 `representativeType`). `DisplayName`·`BusinessRegistrationNumber`에 생성자와 같은 판정의 `errorOf` 추가(Domain). **E-11** 응답 key 순서: 중복 거절 `outcome·rejection·replayed·existing`, 400 `error·fieldErrors[path·code]`, 이력 `changes` 항목 key는 `field·before·after·ended·added`, 행 항목 `name·type·postalCode·baseAddress·detailAddress·email·phoneNumber·primary·order` 순으로 고정(MySQL JSON이 key를 길이·사전순으로 다시 저장하므로 Reader가 정렬 — 골든 결정성). 이력은 detail에서 `$.reason`·`$.changes`만 읽는다(옛 `BusinessRegistered` 행은 사유 null·changes 빈 목록). **E-12** 목록 `representativeNameMasked` SQL 마스킹 = `BusinessPiiMask.name`과 같은 모양(첫 code point + 나머지 `*`, 1글자 `김*` — §6.2 "첫 글자 + `*`"의 구체화, 시험이 두 결과 대조). **E-13** BIZ-2-9 "옛 `RegisterBusiness` 기록이 있는 DB"의 재현: 현재 코드는 V14 schema에 쓸 수 없으므로 V15 DB에서 같은 키 공간 단계 1 키로 옛 등록 경로(과도기 저장 = V15가 V14 행을 바꾼 모양 — BIZ-1 X-10 시험)를 먼저 실행한 상태로 시험한다. seed 단계 1은 enum 이름·키(`01-register-business`)를 유지하고 operation만 `CreateBusiness`, 옛 operation(`RegisterBusiness`) 성공 기록을 선확인(`DemoSeedStep.legacyOperation`). **E-14** BIZ-2-5 응답 유실 시험은 기존 실제 HTTP 컨텍스트(`MerchantOperationsLostResponseSpec`)에 사례를 더한다(메모리 — 새 컨테이너 없음, 원시 소켓으로 commit 뒤 응답 미수신). **E-15** Named Fixture `withoutMerchants`·`withMerchant`는 Use Case를 쓰므로 순수 Domain Fixture(`BusinessFixtures`)가 아니라 `application` 시험 패키지의 `BusinessCommandFixture`에 둔다. **E-16** §6.8 골든의 ADMIN 바이트 동일 사본은 BIZ-3(ADMIN `MasterResponseShape`)에서 추가한다(BIZ-2는 ADMIN 코드 변경 없음 — 골든 KDoc에 기록) |
| 0.2.5 | BIZ-2 독립 리뷰 REVIEW_PASS 조건부 → 반영(Coordinator 지시, 2026-11-17). **MINOR-1** BIZ-2-8에 SQL 포획 단언 추가(목록·번호 조회·상세·중복 요약 jOOQ SELECT는 `registration_number`를 `LEFT(` 안 또는 바인드 비교 `= ?`로만 — 변이 (e)가 BIZ-2-7·BIZ-2-8 둘 다 RED). **MINOR-2** E-10 ③ 동작 유지·고정: 업태 11 + 종목 9(전체 20 — 구조 상한 안)는 400 `BUSINESS_VALIDATION_FAILED` `[{"path":"categories","code":"TOO_MANY"}]`(§6.6 "목록 상한 초과 → REQUEST_MALFORMED"는 **전체 목록 상한(20)** 초과에만 적용 — 유형별 10 초과는 규칙 필드 오류). ParserSpec·ApiSpec 시험, BIZ-3 인계. **MINOR-3** BIZ-3 인계 항목(tasks.json BIZ-3 doneCriteria 추가). **MINOR-4 / E-13 갱신**: BIZ-2-9를 명세 문구대로 — V14까지 migrate한 새 DB에 옛 seed 단계 1 결과(business V14 행 + `admin_request` SUCCESS `RegisterBusiness`·키 `demo-seed-v1:01-register-business`)를 SQL로 넣고 앱 Flyway가 V15·V16을 적용한 DB에서 새 seed가 그 사업자를 재사용(DRAFT·구분 NULL 유지·`CreateBusiness` 기록 0)·재실행 재생(`BusinessLegacySeedMigrationSpec` — 이 Spec 전용 컨텍스트, 끝나면 닫음). 공유 DB의 옛 경로 재현 시험(BusinessDemoSeedSpec)은 대조로 유지. **MINOR-5** 중복 요약 `summary == null`(저장 reference의 사업자 없음 — 결함) → 503 `TEMPORARILY_UNAVAILABLE`(본문에 outcome·existing 없음) 시험. **NIT-1** 골든 최상위 key = §6.8의 10개 정확히(`_comment` 제거 — Demo 값 표기는 시험 KDoc). **NIT-2** update·complete 해석의 규칙 함수 번호 자리 값 `0009999999`(Demo 번호와 다름, 판정에 쓰이지 않음). **NIT-3** 인계 목록만 |
| 0.2.6 | BIZ-3 구현 중 정한 구현 세부·정정(2026-11-18, Implementer — 기준 HEAD `5dab2e7`, 업무 정책·사용자 결정·시험 기대 완화 없음). **E-17** 사업자 조회 창 설정 키는 명세대로 `paycore.admin.merchant-ops.business-reads-per-minute`(1..10000, 기본값 없음 — 누락·0·10001 기동 실패)이나 값 Port는 새 `BusinessReadLimits`(구현 `BusinessOpsSettings` — `@ConfigurationProperties("paycore.admin.merchant-ops")`): 기존 `MerchantOpsLimits`는 `paycore.admin.master-ops` 설정(`MasterOpsSettings`)이 구현하므로 거기에 두면 키 접두어가 달라진다. 창 구현 `BusinessReadWindow`(공유 빈 — 목록·상세·이력·번호 조회). **E-18** 앞단(403·감사 없음) = 세션 계정 ACTIVE + 업무 조회 역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN — MERCHANT-BIZ-LOOKUP-001 rev 0.3 §4.2 ①, BIZ-3-1 "비활성·역할 없음 403 감사 0"). 그 안에서 권한 상수 밖이면 403 + 감사 `FORBIDDEN`(감사 대상 action — 상세·번호 조회·명령). UNMASK 단독은 앞단에서 감사 없이 403. **E-19** ADMIN 409 응답 code는 기존 `IDEMPOTENCY_KEY_REUSED`(§7.3 "기존 code" — `KEY_REUSED`는 감사 결과 code). **E-20** 감사 detail 키 순서는 기존 관례(`sub;replayed;rejection;…`): create `sub;replayed;rejection;status;brn`, update·등록 완료 `sub;replayed;rejection;version;reason=PRESENT|ABSENT`. 형식 위반·FORBIDDEN 행에도 형식이 맞는 값만 남긴다(번호 형식 위반은 `brn=INVALID`). **E-21** MASTER 400 매핑: 모양 검사를 통과한 `BUSINESS_VALIDATION_FAILED`만 효과 없음 확정(400 + fieldErrors, 감사 `INVALID_INPUT`, 지표 `business_rule_drift{operation}`, create 버킷 환불). `REQUEST_MALFORMED`(ADMIN 구조 결함)·fieldErrors path/code 문법 위반은 기존 명령 관례대로 `OUTCOME_UNKNOWN`(fail-closed — BIZ-3-4 "모양 위반 → OUTCOME_UNKNOWN"). **E-22** update·등록 완료 성공 reference가 경로 businessId와 다르면 `OUTCOME_UNKNOWN`(`REFERENCE_MISMATCH` — 기존 `checkedReference` 규칙). **E-23** `HttpMasterBusinessGateway`는 기존 `MasterOpsSettings`·`MasterCallSigner`(같은 키)·redirect NEVER를 쓰되 permit Semaphore는 자체(크기 = `permits`·`write-permits`, 가맹점 gateway와 별개 — 가역 결정, MASTER 쪽 `AdminChannelPermits`가 최종 상한), 응답 JSON 중복 key 거부, 응답 상한 조회 32 KiB·명령 8 KiB. 기동 검사는 `@DependsOn("masterOpsGateway")`로 기존 검사 뒤에 생성. **E-24** 상세: businessId 형식 위반은 열람이 아니므로 400·감사 없음(권한 판정 뒤), MASTER 실패도 `BUSINESS_VIEWED` `UNAVAILABLE` 1행(§7.4 code 목록). 목록 검색 본문은 기존 `MerchantSearchBodyParser`와 같이 JSON null을 거부하고 MASTER `BusinessSearchRequestParser` 값 규칙(상호 접두 문자 규칙·enum·KST 날짜 from ≤ to·after `biz_`·limit 1..50)을 복제해 MASTER 호출 전 400 — 없는 필드는 보내지 않는다(인계 ④). **E-25** 명령 본문 구조 해석(`BusinessProfileBodyParser`)은 MASTER parser 1단계와 같다(모든 key 필수·선택 null, 목록 전체 상한 5·20·2·5, create 상태 DRAFT|REGISTERED, version 정수 ≥ 0) — 위반은 400 `INVALID_INPUT`(fieldErrors 없음), create만 감사 `brn=INVALID`. 값 규칙(`BusinessProfileInputRules`)은 MASTER 값 단계·`BusinessProfileRules` 복제(형식 위반이 있으면 형식 오류만 — E-10 ①, update = DRAFT 공통·등록 완료 = REGISTERED — E-10 ④). **E-26** 변이 (h)(중복 existing의 조회 권한 판정 제거)는 현재 상수에서 **동치 변이**다(CREATE ⊆ VIEW — 생성 권한자는 항상 조회 권한 보유). 판정은 코드로 유지(§7.3)하고 불변식 CREATE ⊆ VIEW를 시험으로 고정 — 불변식이 깨지면 그 역할로 미노출 시험을 추가한다. **E-27** BIZ-3-7(감사 ↔ MASTER 대조)은 ADMIN 시험에서 "성공 create 감사 correlationId = MASTER로 보낸 `correlationId`(`mos.<sub>`)"까지 고정하고, MASTER가 그 값을 Outbox correlation으로 저장함은 BIZ-2의 `MerchantOperationsLostResponseSpec`(CreateBusiness — outbox_event.correlation_id = 요청 correlationId)이 고정한다(ADMIN 시험에는 실제 MASTER가 없다). |
| 0.2.7 | BIZ-3 독립 리뷰 REVIEW_PASS 조건부 → 반영(Coordinator 지시, 2026-11-18). **MINOR-1** `BusinessHttpSpec` 사업자 조회 창 시험: 창이 벽시계 분 고정 창이라 분의 남은 시간이 20초 미만이면 다음 분 시작까지 기다린 뒤 시작(저장소 관례 — 기대값 불변). **MINOR-2** `BusinessOpsSettingsSpec`(ApplicationContextRunner — 실제 `@ConfigurationProperties` 바인딩): `business-reads-per-minute` 누락·0·10001 → 컨텍스트 기동 실패(메시지에 키 이름), 30 → 기동·바인딩(대조). **NIT-1(기록)** E-21의 결과로 MASTER 400 `REQUEST_MALFORMED`(ADMIN 구조 결함 — 결정적)는 화면에 `MASTER_OUTCOME_UNKNOWN`으로 보이고 같은 submissionId 재전송도 같은 결과를 반복한다 — BIZ-4 화면은 재전송 안내가 반복 실패하면 운영 문의로 안내(인계), 지표 `command_unknown{operation}`이 신호. **NIT-2(기록)** 번호 조회 판정 순서는 현행 유지: 형식(③) → 공유 등록번호 버킷(④) → 사업자 조회 창(⑤)(§7.2·BIZ-LOOKUP rev 0.3 §4.2 선례 — 깨진 본문은 버킷을 소모하지 않고 감사만, 버킷이 창보다 먼저라 정보 노출 통제가 우선). **NIT-3** `HttpMasterBusinessGateway.decidedOf`·`invalidOf`: 결과 값 생성 중 불변식 위반도 `Unknown(RESPONSE_UNREADABLE)`(예외가 새어 결과 감사 행이 빠지지 않게 — 현재 모양 검사로는 도달하지 않아 단위 시험으로 고정). **NIT-4** 400 `BUSINESS_VALIDATION_FAILED`의 `fieldErrors`는 1개 이상(`MasterResponseShape.Arr` min — 빈 목록은 모양 위반 → `OUTCOME_UNKNOWN`, fail-closed). **NIT-5** 개인정보·상호를 담는 결과 값 toString 가림(`BusinessQueryResult.Found`·`MasterCallResult.Found` → `Found(***)`, `BusinessProfileResult.Decided`·`MasterBusinessCommandResult.Decided`의 existing → `***`). BIZ-4 인계(리뷰 1~4): ① 목록 검색은 null 대신 필드 생략 ② 명령 본문 = 모든 key·선택 null·`countryCode` "KR"·`version`은 상세 값·update/등록 완료에 번호·상태 없음 ③ 503 `MASTER_RETRY_SAME_KEY`·`MASTER_OUTCOME_UNKNOWN`·`MASTER_BUSY`·`AUDIT_UNAVAILABLE` 문구 구분 ④ 공유 조회 창 30·번호 버킷 10의 429 문구와 Demo 값 표시(L-14). |
| 0.2.8 | BIZ-4 구현 중 정한 구현 세부·정정(2026-11-18, Implementer — 기준 HEAD `1f8e530`, 업무 정책·사용자 결정·시험 기대 완화 없음). **화면** — **E-28** 사업자 조회 탭 page `businesses`(OPERATOR·APPROVER), 생성 성공 뒤 사업자 조회 상세로 이동(재조회 — C-18). "이 사업자로 가맹점 등록"은 상세(REGISTERED·OPERATOR)에만 있다(생성 폼에는 없음). **E-29** 보내기 전 빈 행 제거(보낸 목록 색인 = 화면 행 색인 — fieldErrors path 매핑), 비어 버린 목록에는 입력 행 하나를 끝에 다시 둔다. 대표자 빈 행 = 이름이 빈 행(유형은 기본값), 업태·종목 path `categories[i]`는 업태 수 기준으로 업태/종목 행에 매핑. **E-30** 화면 중요 변경 감지(§4.5 — `lib/businessProfile.ts` `importantChange`) 시 "변경 사유" 칸 표시·비면 화면이 보내지 않음(필수), 일반 변경만이면 칸 없음·`reason: null`. 서버 `BUSINESS_CHANGE_REASON_REQUIRED` 거절이면 사유 칸 강제 표시(서버 권위). **E-31** 400 fieldErrors 화면 파서(`errors/fieldErrors.ts`): §4.6 path 문법·code 집합 밖 항목이 하나라도 있으면 전부 버림(일반 문구 — 값 비노출, 최대 50). **E-32** 429 문구: 번호 조회 429는 공유 번호 버킷(10/분)인지 사업자 조회 창(30/분)인지 응답이 구분하지 않으므로 두 한도를 함께 안내, 목록·상세·이력 429는 조회 창, 명령 429는 등록 창 20/분(+생성은 번호 버킷) — 모두 "Demo 값·운영값 아님"(L-14). **E-33** 명령 결과 처리 = 기존 RegistrationStep 규칙: `MASTER_OUTCOME_UNKNOWN`·`MASTER_RETRY_SAME_KEY`·`AUDIT_UNAVAILABLE`·`SERVER_ERROR` → 잠금 + 같은 submissionId·같은 본문 재전송만, 재전송 중에는 409만 확정(재전송 중 `MASTER_BUSY`·429는 잠금 유지), 첫 전송 `MASTER_BUSY`는 잠그지 않음. code별 문구 구분(BIZ-3 리뷰 3 — OUTCOME_UNKNOWN은 "반복되면 운영 담당에게 문의", NIT-1). **E-34** 상세 문서는 App 메모리에 보관(탭 전환마다 재열람·재감사하지 않음) — 다시 읽기는 상세 링크·저장 성공·"다시 불러오기"·목록 상세 버튼 때만. 세션 경계에서 버림(브라우저 저장소 없음). **E-35** 목록 번호 조회 결과(0/1)는 같은 표에 넣고 목록 전용 열(대표자·구분·진위확인·가맹점 수·수정일)은 "—". **E-36** 전화·법인등록번호 하이픈 표시는 공용 `DigitsInput`(값은 숫자만 — X-14), 전화 표시 규칙 02 지역번호·8자리 대표번호·3-3/4-4. **E-37** 가맹점 등록 ① 번호 조회 결과 기대 반전(BL-3-1 — D-BIZ-004, `MerchantRegistrationScreen.test.tsx` 현재 줄): L115(가맹점 검색 → 번호 조회, `merchantSearch` 미호출, "같은 사업자 가맹점 2건 묶기" 단언 삭제 — 0/1건이라 해당 없음), L148(DRAFT 선택 불가·문구·상세 링크 — 신설), L169("가맹점이 있는 사업자만 찾습니다" → "이 번호로 등록된 사업자가 없습니다. 사업자 등록에서 먼저 등록하세요."), L187(429 = 번호 버킷 문구), 그 밖 대역 4곳 `merchantSearch` → `lookupOf`. App wiring 12건은 옛 2칸 화면 → 새 폼 + 사업자 조회 상세 경유로 같은 단언 유지(옛 `BusinessRegistrationScreen(.test).tsx` 삭제 — C-27). 등록 폼·App wiring 시험 describe 시간 상한 20초(부하 — 단언 불변, ApprovalsScreen 20초 선례). **전환(Kotlin)** — **E-38**(rev 0.2.9 정정 — 리뷰 MINOR-2 (a)) 가맹점 등록 판정 순서(Use Case·API): 코드 예약어 `MERCHANT_CODE_RESERVED`(MC-1 §3.3 — `requireAssignable`, 사업자 잠금 전, MC-1 동작 유지) → `BUSINESS_NOT_FOUND` → `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → `MERCHANT_CODE_TAKEN`(Use Case 사전 조회 — `Merchant.register` 뒤로 옮김). Domain `Merchant.register` 안은 생명주기 → 등록 상태 → 예약어 재검사. 시험: `MerchantRegistrationGateUseCaseSpec`(DRAFT + 예약어 → RESERVED, DRAFT + 사용 중 코드 → NOT_REGISTERED), Domain 순서 `MerchantRegistrationGateSpec`, 변이 (b)(e). 부수: NOT_ACTIVE가 TAKEN보다 앞(TERMINATED 사업자 생성 명령이 없어 실제 영향 없음). **E-39** 과도기 `Business.register`(E-3)·Root 전용 `insert(business)` 삭제(옛 경로 전용), 영속 값 `AdminOperation.REGISTER_BUSINESS`·`DemoSeedStep.legacyOperation`·ADMIN 감사 action `MERCHANT_BUSINESS_REGISTER`(기존 행)는 유지. V16 주석은 checksum 때문에 그대로. **E-40** ADMIN 가맹점 등록 서비스(`MerchantRegistrationService`)는 등록번호 버킷을 쓰지 않는다(사업자 전용 분기 제거 — 버킷 사용처 = 번호 조회·가맹점 BRN 검색·사업자 생성), `Parsed.Malformed`는 object, 형식 위반은 감사 없는 400(번호가 오가지 않는 종류 — D-MOPS-006 ④ 범위 밖). **E-41(Coordinator 결정 2026-11-18 — BIZ-2-9 기대 갱신, L-7 의도)** 전환 뒤 단계 1(옛 `RegisterBusiness`)만 기록된 옛 Demo DB에서 seed는 가맹점 단계에서 `StepRejected(REGISTER_MERCHANT, BUSINESS_NOT_REGISTERED)`로 멈춘다. 가맹점 단계까지 기록된 DB는 사업자를 재사용하고 완료한다(재생 — 업무 효과 없음). `BusinessLegacySeedMigrationSpec`은 단계 2까지 기록해 완료를, 공유 DB `BusinessDemoSeedSpec`은 단계 1만 넣고 멈춤을 단언. **E-42** 시험 전용 `onboardingFixtureBinding`(MasterDbTestConfig) — `OnboardingFixture.readyMerchant`가 REGISTERED 사업자로 시작(호출처 시그니처 불변), `MasterFixture.business()`는 `createRegistered`, `BusinessFixtures.legacyDraft()`는 `reconstitute`(V15 백필 모양). **E-43** 기존 시험 이관(기대값 변경 주석 `BIZ-4 C-27`): MASTER 약 28개·ADMIN 6개 파일, 옛 경로 전용 시험은 BIZ-1~3 새 경로 시험으로 대체(목록은 tasks.json biz4). `BusinessProfileConcurrencySpec` 교착 시험은 REGISTERED 사업자 + 일반정보 수정으로(단언 OK·OK 불변), `MerchantOpsApiSpec` handler 수 28 → 27. **E-44** harness E2E `harness/e2e/merchant_code_e2e.py`는 BUSINESS-PROFILE-001 `allowedWritePaths`(`harness/tests/**`) 밖이지만 제거된 옛 경로를 따라가야 하므로 수정(새 `POST /api/businesses` REGISTERED Demo 본문) — **Coordinator가 C-27 근거로 수용(2026-11-18)**. **E-45** 새 Kotlin 시험: `MerchantRegistrationGateSpec`(domain)·`MerchantRegistrationGateUseCaseSpec`(application — D-BIZ-001 기존 가맹점 Site·계약 무영향 포함)·`MerchantRegistrationGateApiSpec`(MASTER HTTP·쓰기 경로 목록)·ADMIN `LegacyBusinessRouteRemovedHttpSpec`(옛 경로 404·라우트 목록) |
| 0.2.9 | BIZ-4 독립 리뷰 REVIEW_PASS 조건부 → 반영(Coordinator 지시, 2026-11-18). **MINOR-1(낙관적 잠금 우회 차단)**: 수정 화면은 편집 기준 상세(`edit.base` — 수정을 연 시점 문서)로만 그리고 보내는 `version`은 항상 기준 version(지금 다시 읽은 상세 version이 아님). 결과 불명 편집을 "그래도 새로 입력"으로 포기하면 편집 메모리를 비우고 상세를 다시 읽는다(옛 내용 + 새 version 조합 금지). 결과 확인이 필요한 편집(보내는 중·결과 불명)은 상세 링크(`openBusinessIn`)·다른 사업자 수정 시작으로 버려지지 않는다 — 같은 사업자는 그 편집으로 이어서 열고, 다른 사업자 수정은 막고 안내(같은 submissionId 재전송·`hasUnresolvedRequest` 유지). 회귀 시험(v3 결과 불명 → v4 재조회 → 재전송·새 저장 모두 version 3, 서버 `BUSINESS_VERSION_CONFLICT`, 다시 불러오기 뒤 v4 기준 저장)·변이 (o) 지금 상세 version 전송 → RED. **MINOR-2 (a)**: MC-1 동작 유지(`requireAssignable`이 사업자 잠금 전) — §4.4·E-38을 실제 순서(예약어 → NOT_FOUND → NOT_ACTIVE → NOT_REGISTERED → TAKEN)로 정정, Use Case 시험 "DRAFT + 예약어 → `MERCHANT_CODE_RESERVED`"(대조: 같은 DRAFT·일반 코드 → `BUSINESS_NOT_REGISTERED`) 추가, `MerchantRegistrationGateUseCaseSpec` KDoc 정정. **MINOR-3**: 시험 대역 호출 인수 전수 검사(`fakeApi.callsContaining`·`leakedCalls` — 원문 사업자등록번호는 `lookupBusinessByRegistrationNumber`·`createBusiness` 호출에만), 등록 폼·사업자 조회·가맹점 등록 ① 시험에 단언, 변이 (p) 목록 검색·(q) 상세 조회·(r) 가맹점 등록 본문으로 번호 유출 → 모두 RED. **NIT-1**: 칸이 없는 fieldErrors — `contacts[i].primary`는 등록 담당자 묶음 오류, `registration.countryCode`·`version`·숨은 칸은 일반 문구("화면 칸에 표시할 수 없는 오류 — 다시 불러온 뒤"), `reason`은 사유 칸 강제 표시. 사유 TOO_LONG 문구 200자. **NIT-2**: 일반 변경만 있으면 "변경 사유(선택)" 칸(비우면 null, 입력하면 전송 — §4.5 "사유 선택"). **NIT-3**: MERCHANT-OPS-002 §4.3 표(L180)·MASTER 명령 표(L110)에 옛 사업자 등록 경로 "제거됨(C-27)" 표기, `MerchantRegistrationService` KDoc 정정. 남은 언급은 역사 기록(MERCHANT-OPS-002 L48·L270·L514·L1050 — 설계 당시 서술, DEMO-SEED-001 L69 — allowedWritePaths 밖이라 미수정, Coordinator 확인 대상). **NIT-4**: INACTIVE 표시 "비활성(사용중지)"(전이 경로 없음 — TBD-08), 가맹점 등록 ①의 INACTIVE 문구는 "비활성(사용중지) 사업자 — 가맹점 등록 불가"(DRAFT 결정 문구와 구분). **NIT-5**: 등록 폼 "법인등록번호는 법인만" 시험이 제목대로 개인 전환 시 칸 없음을 단언. **NIT-6**: 첫 목록 읽기 실패는 메모리에 남겨 탭 재진입 시 자동 재조회하지 않고 "다시 시도" 버튼으로만 다시 읽는다 |
