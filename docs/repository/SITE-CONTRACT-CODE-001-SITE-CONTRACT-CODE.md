# SITE-CONTRACT-CODE-001 Site·계약 사람용 코드 명세 (rev 0.2)

- **rev 0.2** (2026-11-20, Implementer — 명세 확정본. 문서만: 코드·migration·빌드·커밋 없음, 자기 완료 판정 아님 — 독립 리뷰 별도). 기준 main HEAD `4cd6a07`.
  - **변경 요약**
    1. 사용자 질문 Q-SC-1~9 답(모두 ANSWERED, 권장안 채택)을 확정 문장으로 바꿨다(§2). 대안은 §14 "검토했으나 채택 안 함"으로 짧게 옮겼다.
    2. migration 번호 재지정: MASTER V17은 MERCHANT-NAME-SEARCH-001이 사용했고 V18은 의도적으로 비워 둔 번호(V17 주석 "이후 V18 추가 금지")라 → **schema V19, privilege V20**(§3.3·§1.5).
    3. 저장소 대조 정정(errata, §1.6): 초안 §3.4 "계약 등록에 Scope merchant 잠금 **추가**"는 사실과 다르다 — `registerContract`는 이미 `validScope`(MerchantOnboardingService.kt:423-424)에서 merchant 행을 잠근다(business → merchant 순서). 새 잠금은 없고, 기존 잠금 뒤에 순번을 읽는다. 초안 L-4(새 직렬화)는 삭제, 변이 시험 문구 정정.
    4. 순번 읽기의 격리 수준 전제 명시: 명령 TX는 READ COMMITTED(LocalTransactions.kt:28-31) — `MAX(code_seq)`는 merchant 잠금 **뒤** 읽어야 최신 커밋을 본다(§3.4, 변이 시험).
    5. 백필 규칙 점검(§3.6): LPAD 절단 방지 IF 식 유지, 현재 가맹점 코드 결합을 LEFT JOIN + NOT NULL로 fail-closed, collation 명시, 결정성·행 수 시험.
    6. 초안 L-6(Outbox 소비자 영향) 확인: 등록 명령 Outbox(`INTEGRATION_MASTER_REFERENCE`)는 현재 저장소에서 relay가 claim하지 않는다(relay는 `ENTITLEMENT_SNAPSHOT`만 — JpaEntitlementRelayStore.kt:27) → 가산 필드 영향 없음, 착수 시 재확인만.
    7. 8대 원칙 증거표(§7)·JPA/jOOQ 역할 설명·BOM 무변경, 배치 SCODE-1..5 순서·파일·시험(§8·§9), 알려진 한계 L-(§11), LIVE Gate(§12) 정리. 화면 줄 번호를 HEAD `4cd6a07` 기준으로 갱신.
  - **사용자 답 근거**: `harness/questions.json` Q-SC-1·2·4·8(2026-11-19 답 — D-ADMIN-UI-005 후속), Q-SC-3·5·6·7·9(2026-11-20 답 — `docs/DECISIONS.md` **D-ADMIN-UI-007**). 원 결정 **D-ADMIN-UI-005**("새 사람용 코드 발급으로 site_ ctr_ 이렇게 구분해서").
- **rev 0.1** (2026-11-19, Planner 초안, HEAD `0323648`) — 형식·저장·발급·노출 제안과 Q-SC-1~9 PENDING.
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-SC-n** = 사용자 질문(답 완료). **C-n** = Coordinator 가역 선택(저장소 관례). **L-n** = 알려진 위험·한계. **LG-n** = LIVE Gate 항목.
- 근거 문서: D-ADMIN-UI-005·007, D-ADMIN-UI-004 ③, D-MCODE-001·002·003, D-BIZLOOKUP-001("응답 포함, 화면 미표시"), D-MD-001·002·008·013, D-CP-001(정규화 없음), MERCHANT-CODE-001(rev 0.4.12 — 배정 이력 표·백필·응답 모양 같은 배포 단위 C-22·라벨 묶음 읽기 §5.6·C-34·C-35), MERCHANT-NAME-SEARCH-001(V17·V18 비움), ADMIN-HISTORY-KEYS-001, TXN-LIST-ENRICH-001, CP-CONTRACT-001, MASTER-SNAPSHOT-001·002.
- 금융 효과: **없음**. 금액·통화·결제 상태·정산·기관 송신·권리 Snapshot을 바꾸지 않는 MASTER 기준정보 표시 식별자다.

## 0. 한눈에(확정)

| 항목 | 확정 내용 | 근거 |
|---|---|---|
| 무엇 | Site(`merchant_site`)·계약(`contract`)마다 사람이 읽는 **코드** 1개를 새로 발급·저장. 내부 키 `site_<ULID>`·`ctr_<ULID>`는 그대로(FK·Snapshot·명령 본문·외부 API) | D-ADMIN-UI-005 |
| 형식 | **`site-<가맹점코드>-<NN>`**, **`ctr-<가맹점코드>-<NN>`** — 소문자, 하이픈 구분, 밑줄 없음. 예 `site-demoshop01-01`, `ctr-demoshop01-02` | Q-SC-1 |
| 가맹점 코드 변경 | 코드에 박힌 가맹점 코드는 **발급 당시 값 유지**(재발급 없음) | Q-SC-2 |
| 고유 범위 | **MASTER 전체에서 하나**(TEST·LIVE 공통, DB UNIQUE). 코드만으로 대상 하나 식별 | Q-SC-3 |
| 발급 주체 | **시스템 자동**(등록 TX 안, merchant 행 잠금 아래 다음 순번). 운영자 입력 없음 | Q-SC-4 |
| 불변·재사용 | **발급 후 불변, 재사용 금지**(종료 Site·철회 계약 번호 포함). DB 권한 `SELECT, INSERT`만 | Q-SC-5 |
| 서비스 계약(csa_) | **코드 없음**. 화면 = `<계약 코드> · <서비스 종류>`, `csa_`는 내부 요청·응답에만 | Q-SC-6 |
| 외부·정산서 노출 | **없음** — 관리자 화면·MASTER 전용. Snapshot·PAYMENT·FINANCIAL·웹훅·결제 API 불변. 외부 `merchantSiteId`는 계속 `site_<ULID>` | Q-SC-7 |
| `site_` 화면 노출 | 가맹점 상세 Site 패널 **한 곳**에만 "API 연동값(merchantSiteId)" 복사용. 그 밖 모든 화면에서 제거 | Q-SC-8 |
| 감사·승인 | 저장값(`target_ref`·pinned)은 **내부 키 유지**, 새 등록 감사 detail에 `code=<코드>`, 화면은 MASTER 라벨 읽기로 코드 표시(실패 "—"). **ADMIN migration 없음** | Q-SC-9 |
| 저장 | 새 append-only 표 `merchant_site_code`·`contract_code`(MASTER **V19**), runtime `SELECT, INSERT`(**V20**). 기존 표 열·권한 불변 | C-2, §1.5 |
| 기존 데이터 | V19 안에서 표마다 한 문장 백필(가맹점별 `created_at` 순서, 백필 시점 현재 가맹점 코드) | C-5·C-8 |
| 화면 입력 | "Site ID 직접 입력"·"계약 ID" 입력칸 제거 → 그 가맹점 Site·계약 **목록 선택** | D-ADMIN-UI-005 |
| 배치 | SCODE-1 MASTER 모델·발급·백필 + ADMIN 응답 모양(한 배포 단위) → SCODE-2 등록 마법사 → SCODE-3 가맹점 상세·구성요소 → SCODE-4 라벨 읽기(승인·감사·거래·이력) → (SCODE-5 코드 검색, 별도 승인) | §8 |
| 막힘 | 사용자 질문 없음(모두 답). 독립 명세 리뷰 → SCODE-1 착수 | — |

## 1. 현재 상태(HEAD `4cd6a07` 코드 확인)

### 1.1 ID 생성(MASTER)
- 형식 `MasterIdFormat.kt:3-18` — `<prefix>_` + Crockford ULID 26자 대문자. L6 "형식은 **확장만** 허용".
- 값 객체: `MerchantSiteId.kt`(PREFIX `site`, "PAYMENT의 merchantSiteId와 같은 값"), `ContractId.kt`(`ctr`), `ContractServiceAgreementId.kt`(`csa`).
- 발급 지점(모두 `MerchantOnboardingService.kt`, 0323648 이후 무변경):
  - Site `registerMerchantSite` L119-147 — **merchant 잠금 L125**, `lockedNow` L127, 활성 채널 중복 검사 L129, `work.newId(MerchantSiteId.PREFIX)` L130, entitlement revision +1 L133, `sites.insert` L135, 이력 `MerchantSiteRegistered` L137-144, Snapshot 발행 L145, reference = siteId L146. 지문 `[merchantId, channelType, channelIdentifier]` L122.
  - 계약 `registerContract` L149-165 — business 잠금 L155 → **`validScope` L156이 merchant 잠금**(L423-424 `merchants.lock(input.merchantId)`) → `lockedNow` L157, `newId(ContractId.PREFIX)` L159, `contracts.insertNew` L162, 이력 L163, reference = contractId L164.
  - Revision `registerContractRevision` L167-186 — reference `"<ctr_…>#<n>"` L185.
  - CSA `addServiceAgreement` L247-265 — contract 잠금 L249.
  - 잠금 순서 선례: 첫 MID 발급 business → merchant → contract(L298-303), 계약 승인 merchant → contract(L217-221), 가맹점 코드 변경 merchant(L394).
- 명령 TX 격리 수준 **READ COMMITTED**(`LocalTransactions.kt:28-31`, `AdminCommandExecutor.kt:82`). `lockRow` = `refresh(PESSIMISTIC_WRITE)`(`PessimisticRowLock.kt:17-19`).
- 표(MASTER `V1__master_core.sql`):
  - `merchant_site` L58-80: CHECK `^site_<ULID>$`, `uk_merchant_site_merchant(merchant_site_id, merchant_id)`, 활성 채널 UNIQUE(ACTIVE 사이에서만), lifecycle ACTIVE/TERMINATED, `created_at DATETIME(3)`.
  - `contract` L84-97: `merchant_id` 열 보유, `uk_contract_merchant(contract_id, merchant_id)`, CHECK `^ctr_<ULID>$`, `created_at`.
  - `contract_service_agreement`: `UNIQUE(contract_id, service_kind)`, 서비스 종류 `CARD_PG_PAYMENT` 하나.
- runtime 권한 `V2__master_runtime_privileges.sql`: `merchant_site` = `SELECT, INSERT`, `contract` = `SELECT, INSERT, UPDATE`.
- 선례 `V13__merchant_code_assignment.sql`(append-only 배정 표, `uk_mca_code(merchant_code)`·`uk_mca_merchant_code(merchant_id, merchant_code)`, 현재 코드 = 최대 `assignment_seq`, 백필 `INSERT … SELECT` 한 문장, CHECK 열과 NO ACTION FK 공존), `V14` 권한 `SELECT, INSERT`(FOR UPDATE도 1142).
- JPA 선례: `JpaMerchantRepository.insert`가 Merchant 행과 `JpaMerchantCodeAssignmentEntity`를 같은 flush로 persist(L25-29), `lock` 시 배정 이력을 함께 읽어 Domain 조립(L31-35).

### 1.2 ADMIN 서버 정규식·저장
- 등록 입력 `RegistrationInputRules.kt`(`CONTRACT_ID`·`SITE_ID`·`MERCHANT_SITE_ID`), 승인 고정 대상 `PinnedContractApprovalTarget.kt`·`PinnedFirstMidTarget.kt`, `MerchantOpsInputRules.kt`, `ContractApprovalCommandBody.kt` — 모두 내부 키 검증으로 **그대로 유지**.
- 등록 감사 `MerchantRegistrationService.kt` — 성공 `target_ref` = 만든 ID(`site_`·`ctr_`·`csa_`), Revision은 계약 ID + detail `rev=<n>`. detail 빌더에 `code=` 선례.
- 감사 형식 `AdminAuditEvent.kt:65` `TARGET_REF`(무변경), `DETAIL` L68 `[A-Za-z0-9_:.,*-]` → 하이픈 코드 기록 가능.
- MASTER 응답 해석 `MasterResponseShape.kt`: 키 집합 **정확 일치**(MERCHANT-CODE-001 C-22) → 응답 키 추가는 MASTER와 같은 배포 단위.

### 1.3 ADMIN 화면(내부 키 노출 지점 — HEAD `4cd6a07` 줄)
- `screens/MerchantRegistrationScreen.tsx`: 정규식 `SITE_ID`·`CONTRACT_ID` L101-102, `createdSites` L430·462·500·516, Site 성공 "Site ID" L704, 계약 성공 "계약 ID" L755, 계약 범위 Site 체크 목록(`offeredSites` — 이 마법사에서 만든 Site만) L787-794, **"Site ID 직접 입력"** L800-806(검증 L526), 서비스 계약 성공 "서비스 계약 ID" L857, **"계약 ID" 입력** L862-863(검증 L551), ④ 검토 Site 목록 L903-910·계약 초안 L921-·서비스 계약 L931-.
- `screens/MerchantsScreen.tsx`: Site 표 L175-176, 계약 카드 L200-202, Revision Scope Site L219, CSA L231-234, 배정 Site L266, 한도 대상 L341, Revision 폼 Site 후보 L752.
- 구성요소: `FirstMidRequest.tsx:102-119`(계약 #n·CSA·PRIMARY Site 선택지 문구), `MerchantActivationArea.tsx:451-454`, `ContractRevisionForm.tsx:67`(제목 "새 Revision 등록 — ctr_…"), `MerchantContractApprovalDetail.tsx`(실행 완료 ref·pinned 계약·CSA·Site).
- `screens/TransactionsScreen.tsx:84`(거래 상세 "Site" = `merchantSiteId` 원문).
- `lib/merchantTarget.ts`: L8 "그 밖(`ctr_` 등) 원문", `hasInternalKey` L29-31 = 부분 문자열 `includes('mer_') || includes('biz_')`.

### 1.4 외부 API·Snapshot·FINANCIAL·Demo
- `site_<ULID>`는 **외부 결제 API 필수 입력** `merchantSiteId`(CP-CONTRACT-001:28·39, 주문 지문 재료). 가맹점 포털이 없어 운영자가 관리자 화면에서 가맹점 개발자에게 전달해야 한다 → Q-SC-8 답(한 곳 표시).
- Snapshot(`EntitlementSnapshot.kt`)은 siteId·agreementId만, 계약 ID 없음. FINANCIAL `merchant_site_id` 투영. 모두 변경 없음(Q-SC-7).
- Demo seed: `DemoMerchantSeedService` 등록 순서 가맹점 → Site → 계약 → 승인 → CSA → 첫 MID, 가맹점 코드 `demoshop01`.
- 라벨 묶음 읽기 선례: MASTER `MerchantLabelController`(`/merchant-labels`), `MerchantAccountLabelController`(라벨 전용 permit — C-34), ADMIN `MerchantTargetLabelService`.

### 1.5 Flyway 최신 번호(HEAD `4cd6a07` 파일 목록, 홀수 schema·짝수 privilege 관례 — MERCHANT-OPS-002 C-21)
- **MASTER**: schema 최신 `V17__merchant_display_name_index.sql`, privilege 최신 `V16__business_profile_privileges.sql`. **V18은 의도적 빈 번호**(V17 주석 "이후 V18 추가 금지", V8·V12 선례) → 이 작업 **V19(schema, `migration/master/V19__site_contract_code.sql`)·V20(privilege, `runtime-privilege/master/V20__site_contract_code_privileges.sql`)**.
- **ADMIN**: schema `V15`, privilege `V10` → 변경 없음(Q-SC-9).
- **PAYMENT**: schema `V36`, privilege `V34` → 변경 없음. **FINANCIAL**: `V19`·`V20` → 변경 없음.
- outOfOrder 없음. 착수 시 다른 작업이 V19/V20을 먼저 쓰면 다음 홀수·짝수로 다시 매기고 명세를 갱신한다.
- migration 목록 기대값 회귀 2곳: `MasterApiMainMigrationsBootSpec`·`MasterApiContextSmokeSpec`(MNS-1 선례).

### 1.6 rev 0.1 대비 저장소 대조 정정(errata)
- E-1: 초안 §3.4 "계약 등록에 Scope merchant 잠금 추가" → **이미 잠금**(`validScope` L423-424). 잠금 순서 business → merchant 그대로, 새 교착 경로 없음. 초안 L-4 삭제.
- E-2: 초안 V17/V18 → V19/V20(§1.5).
- E-3: 초안 L-6 → 등록 Outbox 이벤트 범주 `INTEGRATION_MASTER_REFERENCE`(AdminCommandExecutor.kt:398)는 저장소 안 relay 대상이 아니다(relay claim = `ENTITLEMENT_SNAPSHOT`, EntitlementSnapshotPublisher.kt:89·JpaEntitlementRelayStore.kt:27). 가산 필드 영향 없음. 가맹점 코드 이력 detail 가산(L112-113) 선례 그대로.
- E-4: 화면 줄 번호를 HEAD `4cd6a07`(LAYOUT-ROLLOUT B2·B6·D-ADMIN-UI-006 반영 후)로 갱신.

## 2. 사용자 결정 적용(확정)

| 질문 | 사용자 답(원문) | 확정 적용 |
|---|---|---|
| Q-SC-1 형식 | "site-가맹점코드-01" | `site-<C>-<N>`·`ctr-<C>-<N>`(§3.1). 종류 접두어 구분(D-ADMIN-UI-005 의도) 유지, 구분자는 하이픈 — 내부 키 `site_`·`ctr_`와 판독·정규식 모두 구별 |
| Q-SC-2 가맹점 코드 변경 | "발급 당시 값 유지" | 코드 행에 `issued_merchant_code` 저장, 변경 시 재발급 없음. 옛 코드는 영구 예약(D-MCODE-002)이라 다른 가맹점을 가리키지 않는다. 가맹점 코드 변경 요청 화면에 "이미 발급된 Site·계약 코드 N개는 옛 코드를 유지" 안내(SCODE-3) |
| Q-SC-3 고유 범위 | "전체에서 하나" | `UNIQUE(site_code)`·`UNIQUE(contract_code)`(TEST·LIVE 공통). 두 종류는 접두어가 달라 교차 충돌 불가 |
| Q-SC-4 발급 주체 | "시스템 자동 발급" | 등록 TX 안 merchant 잠금 아래 `MAX(code_seq)+1`. 요청 지문 불변(seed 재실행 충돌 없음) |
| Q-SC-5 변경·재사용 | "불변·재사용 금지" | runtime `SELECT, INSERT`만(UPDATE·DELETE 1142), 삭제 경로 없음 → 빈 번호 재사용 불가 |
| Q-SC-6 csa_ | "발급 안 함" | 화면 `<계약 코드> · 카드 PG 결제`, 선택 칸 값은 내부 키 |
| Q-SC-7 외부·정산서 | "노출 안 함" | Snapshot·PAYMENT·FINANCIAL·웹훅·결제 응답·정산서 불변. SETTLEMENT-001도 이 답을 따른다 |
| Q-SC-8 `site_` 화면 | "가맹점 상세 한 곳에만" | 가맹점 상세 Site 표의 "API 연동값(merchantSiteId)" 복사 영역 하나(`data-integration-value`). 마법사·승인·감사·거래·이력은 코드만 |
| Q-SC-9 감사·승인 저장 | "내부 키 유지 + 화면은 코드" | `target_ref`·pinned·ownerReference 저장값 불변, 새 등록 감사 detail `code=<코드>`, 화면은 MASTER 라벨 묶음 읽기(실패 "—"). ADMIN migration 없음 |

## 3. 형식·저장(MASTER)

### 3.1 형식
- Site `site-<C>-<N>`, 계약 `ctr-<C>-<N>`.
  - `<C>` = **발급 시점의** 가맹점 코드(`^[a-z][a-z0-9]{5,13}$` — MerchantCode.kt).
  - `<N>` = 가맹점·종류별 순번 1..9999, **최소 2자리 0 채움**(01..99, 100..9999 — C-1).
- 정규식: `^site-[a-z][a-z0-9]{5,13}-(0[1-9]|[1-9][0-9]{1,3})$`, `^ctr-[a-z][a-z0-9]{5,13}-(0[1-9]|[1-9][0-9]{1,3})$`. 최대 길이 site 24자·ctr 23자 → `VARCHAR(32)`.
- 정규화 없음(대문자·공백·밑줄은 형식 위반 — D-CP-001). 마지막 `-` 기준 분리가 모호하지 않다(가맹점 코드에 `-` 없음).
- 9999 초과 → 저장된 거절 `SITE_CODE_EXHAUSTED`/`CONTRACT_CODE_EXHAUSTED`(C-1, INSERT 전 Domain 판정).
- 기존 정규식과의 불충돌(속성 시험 SCODE-1-1): `MasterIdFormat`(`<prefix>_`), `AdminAuditEvent.TARGET_REF`(내부 키·ACQ·가맹점 코드 — 코드에 `-`가 있어 가맹점 코드 분기에도 불일치 → V15 감사 CHECK 오인 없음), `MasterResponseShape.ID`, 채널 식별값(WEB host·APP 식별자는 `.` 필수), PolicyCode(대문자).

### 3.2 저장 방식 — append-only 코드 표 2개(C-2)
- 기존 표에 열을 더하지 않는다: `contract`는 runtime 표 단위 UPDATE 권한(V2)이 있어 열 불변을 권한으로 보장할 수 없다. 별도 표는 `SELECT, INSERT`만 주어 **불변·삭제 불가를 DB 권한이 보장**하고 기존 표 권한은 바꾸지 않는다.
- 박힌 가맹점 코드가 그 가맹점의 배정 코드(현재·은퇴)임을 FK로 DB가 강제한다(`uk_mca_merchant_code`).
- NOT NULL(코드 없는 Site·계약 0)은 자식 표라 DB로 표현할 수 없다 → 같은 TX INSERT + migration 시험 + 읽기 시 없음 = 결함 503(MERCHANT-CODE-001 V13 선례).

### 3.3 V19 표 정의(collation `utf8mb4_0900_bin`, ENGINE InnoDB)
**`merchant_site_code`**
- 열: `merchant_site_id` VARCHAR(64) PK, `merchant_id` VARCHAR(64) NOT NULL, `code_seq` SMALLINT NOT NULL, `issued_merchant_code` VARCHAR(14) NOT NULL, `site_code` VARCHAR(32) NOT NULL, `source` VARCHAR(16) NOT NULL, `assigned_at` DATETIME(3) NOT NULL, `assigned_by` VARCHAR(64) NOT NULL.
- 제약:
  - `uk_msc_code UNIQUE(site_code)`, `uk_msc_merchant_seq UNIQUE(merchant_id, code_seq)`.
  - `fk_msc_site FOREIGN KEY (merchant_site_id, merchant_id) REFERENCES merchant_site(merchant_site_id, merchant_id)`.
  - `fk_msc_merchant_code FOREIGN KEY (merchant_id, issued_merchant_code) REFERENCES merchant_code_assignment(merchant_id, merchant_code)`.
  - FK 동작 미지정(NO ACTION — V1·V13 관례, CHECK 열과 공존 가능).
  - `ck_msc_seq CHECK (code_seq BETWEEN 1 AND 9999)`, `ck_msc_source CHECK (source IN ('REGISTRATION','BACKFILL'))`.
  - `ck_msc_code_format CHECK (REGEXP_LIKE(site_code, '^site-[a-z][a-z0-9]{5,13}-(0[1-9]|[1-9][0-9]{1,3})$', 'c'))`.
  - `ck_msc_code_derived CHECK (site_code = CONCAT('site-', issued_merchant_code, '-', IF(code_seq < 100, LPAD(code_seq, 2, '0'), CAST(code_seq AS CHAR))) …)` — 코드 = f(가맹점 코드, 순번)을 DB가 강제. 숫자→문자 변환 결과에 `COLLATE utf8mb4_0900_bin`을 명시해 혼합 collation 오류(1267)를 막는다(정확한 SQL 표기는 SCODE-1 구현 — 시험이 CHECK 생성·위반 거절·백필 성공을 증명).
- **`contract_code`**: 같은 모양 — `contract_id` PK, `fk_cc_contract FOREIGN KEY (contract_id, merchant_id) REFERENCES contract(contract_id, merchant_id)`, 접두 `ctr-`, 제약 이름 `uk_cc_code`·`uk_cc_merchant_seq`·`fk_cc_merchant_code`·`ck_cc_*`.
- **V20 권한**: `set_runtime_table_privileges('merchant_site_code', 'SELECT, INSERT')`, `('contract_code', 'SELECT, INSERT')`(V14 선례). 기존 표 권한 불변.
- 1062 분류(`PersistenceFailures` 표에 추가): `uk_*_merchant_seq`·`uk_*_code`·PK = 잠금 아래에서는 일어나면 안 되는 **결함**(OTHER → 503·경보 — C-3). 업무 거절로 바꾸지 않는다.

### 3.4 발급(등록 TX 안 — 같은 MASTER 로컬 TX)
- **Site** `registerMerchantSite`: merchant 잠금(L125) **뒤**
  1. `nextSeq = MAX(code_seq)+1`(그 가맹점, 없으면 1 — 종료 Site 포함). 명령 TX가 READ COMMITTED라 잠금 뒤 읽는 일반 SELECT가 최신 커밋을 본다. FOR UPDATE는 권한상 불가(1142)이므로 쓰지 않는다 — **직렬화는 merchant 행 잠금이 맡는다**.
  2. `issued_merchant_code` = 잠근 Merchant의 현재 코드(`merchant.currentCode` — 같은 잠금으로 가맹점 코드 변경과 직렬화).
  3. 9999 초과면 저장된 거절, 아니면 Site INSERT와 같은 flush로 코드 행 INSERT.
- **계약** `registerContract`: business 잠금(L155) → `validScope`의 merchant 잠금(L424, 기존) **뒤** 같은 절차, 계약 INSERT와 같은 TX로 코드 행 INSERT. merchant `version`·entitlement revision은 바꾸지 않는다(잠금만).
- 가맹점 코드 변경(`changeMerchantCodeWithEvidence` L391-406)도 merchant를 잠그므로 Site·계약 발급과 직렬화 → 각 코드는 옛 코드 또는 새 코드 중 하나에 일관되게 박힌다(SCODE-1-9).
- 요청 지문 변화 없음(코드는 시스템 산출) → seed 재실행·같은 키 재전송 충돌 없음.
- 이력 detail·Outbox payload: `MerchantSiteRegistered`에 `siteCode`, `ContractRegistered`에 `contractCode`(가산, L112-113 선례).
- 명령 응답: `REGISTER_MERCHANT_SITE`·`REGISTER_CONTRACT` 성공 결과에 `code`(`{outcome, reference, code, replayed}`). 재생 시 저장된 reference(`admin_request.result_reference`)로 코드 표를 읽어 응답 — 코드가 불변이라 재생 결과가 항상 같다(`admin_request` 열 추가 없음, C-4). 읽기 실패·행 없음 = 결함 503.

### 3.5 불변·재사용 금지
- runtime UPDATE·DELETE 권한 없음, 순번 `MAX+1`(삭제 경로 없음), 코드 = f(가맹점 코드, 순번) CHECK, 가맹점 코드 영구 점유(`uk_mca_code`) → `site-<C>-<N>`은 영원히 한 Site만 가리킨다.
- Site 종료(MASTER-LIFECYCLE-001 후속)·재신청 = 새 Site·새 번호. 계약 철회도 코드 유지. Revision 번호 체계 불변, 표기 `<계약 코드> · Revision n`(C-10).

### 3.6 기존 데이터 백필(V19 안 — C-5·C-8)
표마다 한 문장 `INSERT … SELECT`(열 목록 명시). Site 예시(계약은 `contract`·`ctr-`로 동일):

```sql
-- 문서 설명용 스케치(실제 파일은 SCODE-1). 가맹점 코드 결합은 LEFT JOIN — 없으면 NULL → NOT NULL 위반으로 migration 실패(fail-closed).
INSERT INTO merchant_site_code (merchant_site_id, merchant_id, code_seq, issued_merchant_code, site_code, source, assigned_at, assigned_by)
SELECT s.merchant_site_id, s.merchant_id, s.seq, cur.merchant_code,
       CONCAT('site-', cur.merchant_code, '-', IF(s.seq < 100, LPAD(s.seq, 2, '0'), CAST(s.seq AS CHAR))),  -- + COLLATE 명시
       'BACKFILL', s.created_at, 'system:migration'
FROM (SELECT merchant_site_id, merchant_id, created_at,
             ROW_NUMBER() OVER (PARTITION BY merchant_id ORDER BY created_at, merchant_site_id) AS seq
      FROM merchant_site) s
LEFT JOIN merchant_code_assignment cur
       ON cur.merchant_id = s.merchant_id
      AND cur.assignment_seq = (SELECT MAX(a.assignment_seq) FROM merchant_code_assignment a WHERE a.merchant_id = s.merchant_id);
```

- 순번 = 가맹점별 `created_at` 오름차순, 동률은 내부 키 오름차순(결정적 — 같은 DB → 같은 코드).
- 가맹점 코드 = **백필 시점 현재 코드**(Q-SC-2의 "발급 당시"는 백필 행에서 migration 시점). 모든 가맹점은 V13 백필·등록 TX로 배정 행을 가진다 — 없으면 LEFT JOIN NULL이 NOT NULL·FK에서 실패(조용히 빠지는 행 없음, MySQL 8.4 기본 strict `sql_mode` 전제 — SCODE-1에서 Flyway 세션 `sql_mode` 확인).
- **LPAD만 쓰면 100 이상이 잘린다**(`LPAD('123',2,'0') = '12'`) → IF 필수. 같은 식을 CHECK·백필·앱(Kotlin `padStart(2,'0')` — 절단 없음) 세 곳에 동일 의미로 둔다(C-8).
- 한 가맹점에 9999 초과 행이면 `ck_*_seq` 위반으로 migration 실패(fail-closed — Demo 해당 없음).
- 충돌(같은 코드) 시 1062로 migration 실패(fail-closed).
- `source='BACKFILL'`, `assigned_at = created_at`, `assigned_by = 'system:migration'`.
- Demo 새 DB: seed가 등록 경로로 만들므로 `site-demoshop01-01`·`ctr-demoshop01-01`(결정적).

## 4. API 변경

### 4.1 MASTER(ADMIN 채널, 서명)
- 가맹점 상세(`JooqMerchantDetailReader` — jOOQ 조회 측): `sites[]`에 `siteCode`, `contracts[]`에 `contractCode`(같은 REPEATABLE READ 읽기 TX 안 JOIN). Scope Site·MID 근거 계약·배정 Site·CSA는 같은 가맹점 소속이 FK로 보장되므로 화면이 같은 응답 안 대응표로 코드를 찾는다 — 키 추가는 두 개만(C-6).
- 등록 명령 성공 응답 `code`(§3.4).
- 새 라벨 묶음 읽기(SCODE-4, jOOQ 조회 Port `SiteLabelReader`·`ContractLabelReader`):
  - `POST /internal/v1/admin/site-labels` `{"merchantSiteIds":[…≤50]}` → `{"items":[{"merchantSiteId","siteCode","channelType","merchantCode"}]}`.
  - `POST /internal/v1/admin/contract-labels` `{"contractIds":[…≤50], "agreementIds":[…≤50]}` → `{"items":[{"contractId","contractCode"}], "agreements":[{"agreementId","contractCode","serviceKind"}]}`.
  - 라벨 전용 permit 공유(C-34), 분당 창 `label-reads-per-minute` 공유(C-35 — 새 설정 키 없음), 없는 ID는 빠짐, 로그에 ID·코드 없음.
- 요청 본문은 계속 내부 키(코드로 명령하지 않는다 — C-7).

### 4.2 ADMIN 서버
- `MasterResponseShape` 상세 `siteCode`·`contractCode` 키 + 명령 성공 `code`(§3.1 정규식) — **MASTER와 같은 배포 단위**(C-22).
- `RegistrationOutcome`에 `code?` 전달.
- 등록 감사 detail에 `code=<코드>`(성공 행만, 형식 일치 시). `target_ref`는 내부 키 그대로(Q-SC-9).
- 라벨 클라이언트·Use Case(`MerchantTargetLabelService` 구조 재사용 — controller → Use Case → client, ArchUnit).

### 4.3 PAYMENT·FINANCIAL·외부
변경 없음(Q-SC-7, §6).

## 5. 화면(ADMIN frontend)

### 5.1 등록 마법사(SCODE-2)
- Site 성공(L704) → "Site 코드 `site-…-01`"(응답 `code`).
- 계약 성공(L755) → "계약 코드 `ctr-…-01` (Revision 1, 승인 대기)".
- 계약 범위 Site 체크 목록 → **그 가맹점의 MASTER 상세 Site 전체(ACTIVE)**, 항목 `site-…-NN · 웹 · shop.example.com`(이 마법사에서 만든 것 포함 — `createdSites` 의존 제거).
- **"Site ID 직접 입력"(L800-806)·검증(L526) 제거**.
- 서비스 계약 성공(L857) → "서비스 계약: `ctr-…-01 · 카드 PG 결제`".
- **"계약 ID" 입력(L862-863) → 계약 선택**(그 가맹점 계약 목록 — 코드·시작일·승인 여부, 방금 만든 계약 자동 선택 유지).
- ④ 검토(L903-) → 코드.
- `SITE_ID`·`CONTRACT_ID` 정규식은 선택값 내부 검증으로만 남긴다.

### 5.2 가맹점 상세·구성요소(SCODE-3)
- `MerchantsScreen.tsx` Site 표·계약 카드·Scope Site·CSA·배정 Site·한도 대상·Revision 폼 후보, `FirstMidRequest.tsx`, `MerchantActivationArea.tsx`, `ContractRevisionForm.tsx` 제목, `MerchantContractApprovalDetail.tsx` pinned 표시 → 코드(상세 응답 대응표). 대응 실패 = "—"(fail-closed, 내부 키 대체 표시 금지).
- Revision 표기 `<계약 코드> · Revision n`, CSA `<계약 코드> · 카드 PG 결제`.
- **API 연동값(Q-SC-8)**: Site 표에 "API 연동값(merchantSiteId)" 영역 하나 — 복사 버튼, 설명 "가맹점 결제 연동 요청에 쓰는 값", 요소 표식 `data-integration-value`. 그 밖 화면 금지. 볼 수 있는 역할은 현재 가맹점 상세 조회 역할 그대로(이미 `site_`가 보이는 화면 — 노출 확대 없음).
- **가맹점 코드 변경 안내(Q-SC-2)**: 가맹점 코드 변경 요청 화면에 "이미 발급된 Site·계약 코드 N개는 옛 가맹점 코드를 유지합니다" 1줄(N = 상세 응답 sites·contracts 수).

### 5.3 승인·감사·거래·이력(SCODE-4)
- 승인 실행 완료 ref: `ctr_…#n` → "계약 `<코드>` Revision n", `site_…#n` → "Site `<코드>` 심사 결정 n"(라벨 읽기). `mid_`·`act_`·`cur_`는 범위 밖(L-4).
- 감사 대상 열: `site_`·`ctr_`·`csa_` `target_ref` → 라벨(`merchantTarget.ts` L8 분기 확장).
- 내부 키 가드 `hasInternalKey`를 **정확 정규식** `(mer|biz|site|ctr|csa)_[0-9A-HJKMNP-TV-Z]{26}`로 교체(부분 문자열 판정 제거 — C-11).
- 거래 상세 `TransactionsScreen.tsx:84` → Site 코드 라벨(연동값 병기 없음 — Q-SC-8).
- 가맹점 변경 이력 대상 열(ADMIN-HISTORY-KEYS-001): `MerchantSite`·`Contract`·`ServiceAgreement` 행 → 코드(상세 대응표, 없으면 라벨 읽기).
- 정적 시험: 화면 DOM에 `/(site|ctr|csa)_[0-9A-HJKMNP-TV-Z]{26}/` 0건(`data-integration-value` 하나 예외).

## 6. 외부·Snapshot·정산 노출 없음(Q-SC-7)
- 코드는 운영자 판독용이며 결제 판정에 쓰이지 않는다 → Snapshot에 넣지 않는다(entitlement revision·contentHash·PAYMENT 해석 변경 없음).
- 외부 결제 API의 Site 식별은 계속 `merchantSiteId = site_<ULID>`(CP-CONTRACT-001 불변). 코드를 외부 입력으로 받지 않는다(주문 지문·멱등 비교 불변).
- FINANCIAL 결제 사실·정산서(SETTLEMENT-001) 불변.

## 7. 8대 원칙 증거·기술 선택

| 원칙 | 이번 기능의 증거(시험 ID) |
|---|---|
| Financial Consistency | 금액·통화·상태·정산·Snapshot 무변경(§6). 코드 = f(가맹점 코드, 순번) DB CHECK, 박힌 가맹점 코드는 FK로 그 가맹점 배정 코드(SCODE-1-5). 외부 계약 바이트 불변(SCODE-1-12) |
| Transaction Safety | Site·계약 INSERT, 코드 행 INSERT, 이력·Outbox·admin_request 결과가 MASTER 같은 로컬 TX(외부 호출 없음). 코드 INSERT 실패 시 Site·계약도 rollback(SCODE-1-13 장애 주입) |
| Idempotency | 같은 요청키 재전송 → 같은 reference·같은 code, 코드 행 1개(SCODE-1-3). 요청 지문 불변 → Demo seed 재실행 충돌 없음(SCODE-1-11) |
| Concurrency Control | merchant 행 잠금(PESSIMISTIC_WRITE) 아래 READ COMMITTED `MAX+1`, UNIQUE는 결함 감지 backstop. 8스레드 동시 등록 순번 {1..8}(SCODE-1-7), 계약·Site 혼합·가맹점 코드 변경 경합·교착 0(SCODE-1-8·9) |
| Recoverability | migration fail-closed(LEFT JOIN NULL·1062·CHECK 위반 시 실패 — 부분 백필 없음, InnoDB DDL 뒤 INSERT 실패 시 Flyway repair 후 재시도), 재생은 저장 reference로 코드 재조회(불변). 1062 = 결함 503(재시도 가능, 업무 거절 아님) |
| Reconciliation | 해당 금융 대사 없음. migration 정합 검사: Site·계약 수 = 코드 행 수, 가맹점별 순번 1..k 연속(SCODE-1-4). 라벨·대응 실패는 "—"로 드러냄(내부 키 대체 금지) |
| Auditability | 코드 append-only·불변(권한 1142 — SCODE-1-6), 이력 detail `siteCode`·`contractCode`, ADMIN 감사 detail `code=`, `target_ref` 내부 키 유지로 과거 행과 1:1 연결(Q-SC-9). `assigned_by`·`source` 기록 |
| Observability | 1062 결함 분류 경보(기존 `PersistenceFailures` OTHER 경로), 라벨 읽기 기존 지표(`merchant_target_labels` 류) 재사용, 로그·지표 label에 ID·코드 원문 없음(canary 시험) |

- **JPA(쓰기 모델 — 이번 변경의 역할)**: Domain `SiteCode`·`ContractCode` 값 객체(순수 Kotlin — 형식·`issue(merchantCode, seq)`·소진 판정)와 JPA Entity adapter `JpaMerchantSiteCodeEntity`·`JpaContractCodeEntity`(`adapter.persistence`, 모든 열 `updatable=false`)를 분리한다. 예: `JpaMerchantSiteRepository.insert`가 Site 행과 코드 행을 **같은 flush**로 persist한다(`JpaMerchantRepository`가 가맹점 + 코드 배정을 함께 persist하는 L25-29와 같은 모양). 이유: Site·계약 Aggregate와 코드의 생명주기가 같고(같은 TX에 생기고 바뀌지 않음) Port를 늘리지 않는다. 주의점: ① 자식 Entity를 연관(`@OneToMany`)으로 묶지 않고 독립 persist — cascade·dirty checking이 불변 행에 UPDATE를 만들 여지를 없앤다. ② 순번 `MAX` 조회는 merchant `lockRow` **뒤** 호출(호출 순서 = 직렬화 근거, 변이 시험). ③ flush 시점 1062는 `translate`가 결함으로 분류. 저장 Port 이름은 기존 `MerchantSiteRepository`·`ContractRepository`에 메서드(`nextSiteCodeSeq` 등)를 더하는 쪽을 권장(C-12 — ARCHITECTURE-CONVENTIONS §14, 새 Aggregate 아님).
- **jOOQ(CQRS 조회)**: 가맹점 상세(`JooqMerchantDetailReader`)와 라벨 Reader 2종은 `adapter.query` jOOQ — 읽기 전용 REPEATABLE READ, 쓰기 금지(ArchUnit). JPA Entity를 조회 측에서 쓰지 않는다.
- **버전·의존성**: 새 의존성 없음. `gradle/libs.versions.toml` Spring Boot `4.1.1` BOM·Kotlin `2.3.21`·jOOQ·Flyway(Boot BOM 관리) 조합 그대로 — 변경 없음(SCODE-1 게이트에서 의존성 잠금 무변경 확인).
- **Kafka/Redis**: 도입하지 않는다. 순번 직렬화·유일성·불변은 MySQL 행 잠금·UNIQUE·권한이 맡으며, 외부 브로커·캐시가 이 보장을 대체하지 않는다.

## 8. 배치(순서·파일·시험)

| 배치 | 범위·주요 파일(apps/ 아래) | 시험 | 선행 |
|---|---|---|---|
| **SCODE-1** | MASTER: `migration/master/V19__site_contract_code.sql`(두 표·백필), `runtime-privilege/master/V20__site_contract_code_privileges.sql`, domain `SiteCode.kt`·`ContractCode.kt`(+거절 코드), `MerchantSite`·`Contract` 조립에 코드, `JpaMerchantSiteCodeEntity`·`JpaContractCodeEntity`, `JpaMerchantSiteRepository`·`JpaContractRepository`, `MasterPersistenceMapper`, `PersistenceFailures`(1062 분류), `MerchantOnboardingService`(발급·이력 detail·응답 `code`·재생 조회), `JooqMerchantDetailReader`·상세 응답(`siteCode`·`contractCode`), Demo seed 기대값, migration 목록 기대값 2곳. **ADMIN 같은 배포 단위**: `MasterResponseShape`, `RegistrationOutcome`, `MerchantRegistrationService` 감사 detail `code=` | SCODE-1-1~13 | 명세 독립 리뷰 PASS |
| **SCODE-2** | frontend `screens/MerchantRegistrationScreen.tsx`(§5.1 — 목록 선택·직접 입력 제거·코드 표시), `api/adminApi.ts`(`RegistrationOutcome.code`, `MerchantDetail` 코드 필드) | SCODE-2-1~5 | SCODE-1 |
| **SCODE-3** | frontend `screens/MerchantsScreen.tsx`, `components/FirstMidRequest.tsx`·`MerchantActivationArea.tsx`·`ContractRevisionForm.tsx`·`MerchantContractApprovalDetail.tsx`(pinned 표시), API 연동값 한 곳, 가맹점 코드 변경 안내 | SCODE-3-1~5 | SCODE-1 |
| **SCODE-4** | MASTER 라벨 2종(`SiteLabelController`·`ContractLabelController`·jOOQ `SiteLabelReader`·`ContractLabelReader`), ADMIN 라벨 client·Use Case, frontend 승인 ref·감사 대상·거래 상세·이력 §5.3, `lib/merchantTarget.ts` 가드 정규식 교체 | SCODE-4-1~6 | SCODE-1 (SCODE-2·3과 독립) |
| SCODE-5(선택) | 가맹점 검색에서 `site-…`/`ctr-…` 입력 → 소속 가맹점 | 별도 | 별도 승인 |

- 배포 경계: SCODE-1 전 = 기존 화면, SCODE-1 후 = 응답에 코드가 있으나 화면 미사용, SCODE-2~4 = 화면만 순차 전환. 어느 경계에서도 ADMIN은 깨지지 않는다(MASTER·ADMIN 응답 모양은 SCODE-1 안에서 함께 배포).
- 동시 수정 금지: SCODE-1만 migration을 만든다. SCODE-2·3은 `MerchantsScreen`·`adminApi.ts`를 순서대로(병렬 구현자 금지).

## 9. 시험 목록

### SCODE-1(MASTER·ADMIN 모양 — MySQL 8.4 Testcontainers 포함)
- **SCODE-1-1 형식**: 정규식 경계(6/14자 가맹점 코드, 01·99·100·9999 통과, 00·1·0001·10000 거절), 대문자·밑줄·공백 거절. 속성 시험 — 생성 코드는 `MasterIdFormat`·`AdminAuditEvent.TARGET_REF`·`MasterResponseShape.ID`·가맹점 코드·채널 식별값 정규식 어느 것과도 불일치. Kotlin 포맷 = SQL 식(1·9·10·99·100·101·999·1000·9999).
- **SCODE-1-2 발급**: 첫 Site `-01`, 둘째 `-02`, 종료 Site가 있어도 다음 번호 증가(재사용 없음), Site·계약 순번 독립, 응답 `code` = 저장값, 9999 다음 저장된 거절.
- **SCODE-1-3 재생**: 같은 키 재전송 → 같은 reference·code, 코드 행 1개. 다른 내용 같은 키 → 기존 409 그대로.
- **SCODE-1-4 migration**: 백필 행 수 = Site·계약 수, 가맹점별 1..k 연속, 101번째 = `-101`(LPAD 절단 회귀), 동률 `created_at`은 내부 키 순, 결정성(두 번 생성 DB 비교), 가맹점 코드 변경 이력 가맹점은 현재 코드, 배정 행 없는 가맹점 고의 주입 시 migration 실패.
- **SCODE-1-5 DB 제약**: CHECK(코드 ≠ f(가맹점 코드, 순번) 거절, 형식 위반 거절), FK(다른 가맹점의 가맹점 코드·다른 가맹점 Site 거절), 순번·코드 UNIQUE.
- **SCODE-1-6 권한**: runtime UPDATE·DELETE·FOR UPDATE → 1142(두 표).
- **SCODE-1-7 동시성(같은 가맹점)**: 8스레드 동시 Site 등록(서로 다른 채널) → 모두 SUCCEEDED, 순번 {1..8} 정확히, 1062·Unknown·503 0, 교착 0.
- **SCODE-1-8 동시성(혼합)**: 같은 가맹점 계약 4 + Site 4 동시 → 종류별 1..4. 다른 가맹점은 서로 막지 않음(대기 시간 상한). 계약 등록 ↔ 첫 MID 발급·계약 승인 동시 → 교착 0.
- **SCODE-1-9 가맹점 코드 변경 경합**: 코드 변경 ∥ Site 등록 → Site 코드의 가맹점 코드가 변경 전 또는 후 하나, FK 만족, 이후 Site는 새 코드, 이전 코드 행 불변.
- **SCODE-1-10 교차 배포**: 새 ADMIN 모양이 새 MASTER 응답 수락, 옛 ADMIN 모양은 새 응답에 503(같은 배포 단위 근거 — MC-1-9 선례).
- **SCODE-1-11 Demo seed**: 새 DB `site-demoshop01-01`·`ctr-demoshop01-01`, 기존 seed DB 재실행 충돌 없음.
- **SCODE-1-12 비노출**: Snapshot payload·contentHash·PAYMENT 반영·외부 결제 응답 바이트 불변(코드 문자열 0건).
- **SCODE-1-13 rollback**: 코드 INSERT 단계 장애 주입(`MasterFaultInjection`) → Site·계약·이력·Outbox·admin_request 모두 없음, 같은 키 재시도 성공.
- 변이(RED 확인): 순번 MAX+1 → MAX, IF 제거(LPAD만), 순번 읽기를 merchant 잠금 **앞**으로 이동(SCODE-1-7·8에서 1062 결함 503), `validScope`의 `lock` → `find`(SCODE-1-8), 감사 detail `code=` 누락.

### SCODE-2·3(화면 — vitest)
- SCODE-2-1 Site·계약 성공 안내에 코드, 내부 키 0. SCODE-2-2 체크 목록에 기존 ACTIVE Site 포함·직접 입력칸 부재. SCODE-2-3 계약 선택(자동 선택 유지)·계약 ID 입력칸 부재. SCODE-2-4 ④ 검토 코드. SCODE-2-5 DOM 내부 키 정규식 0.
- SCODE-3-1 상세·구성요소 코드 표시, SCODE-3-2 대응 실패 "—", SCODE-3-3 연동값 영역 정확히 하나·복사 동작, SCODE-3-4 CSA·Revision 표기, SCODE-3-5 가맹점 코드 변경 안내 N.

### SCODE-4(라벨)
- SCODE-4-1 묶음 상한 50·permit busy 503·분당 창 429·없는 ID 빠짐. SCODE-4-2 승인 ref 변환(`ctr_…#2` → "계약 ctr-…-01 Revision 2"). SCODE-4-3 감사 대상 라벨(과거·새 행 모두). SCODE-4-4 거래 상세. SCODE-4-5 가드 정규식이 `site-…`를 가리지 않고 `site_<ULID>`는 가림(부분 문자열 회귀 변이 RED). SCODE-4-6 라벨 실패 시 원문 미표시·로그 canary.

## 10. Coordinator 가역 선택
- **C-1** 순번 1..9999, 최소 2자리 0 채움, 초과는 저장된 거절.
- **C-2** 저장 = append-only 코드 표 2개.
- **C-3** 순번·코드 1062 = 결함(잠금 아래 경합 불가) — 업무 거절 아님.
- **C-4** 재생 시 코드는 reference로 조회 — `admin_request` 열 추가 없음.
- **C-5** 백필 순서 = 가맹점별 `created_at`, 동률 내부 키 오름차순, 가맹점 코드 = 백필 시점 현재 코드.
- **C-6** 상세 응답 키는 `siteCode`·`contractCode` 두 개만.
- **C-7** 명령 본문은 내부 키 유지.
- **C-8** IF 식(LPAD 절단 방지)을 CHECK·백필·앱 세 곳 같은 의미로, 숫자→문자 변환 collation 명시.
- **C-9** 가맹점 코드 예약어에 `site`·`ctr`·`csa` 추가하지 않음(하이픈으로 구별, 기존 코드 소급 거절 위험).
- **C-10** Revision 번호 체계 불변, 표기 `· Revision n`.
- **C-11** 화면 내부 키 가드를 정확 정규식으로 교체.
- **C-12** 저장 Port는 기존 `MerchantSiteRepository`·`ContractRepository`에 메서드 추가(새 Aggregate·새 Port 없음), 조회 Port는 `SiteLabelReader`·`ContractLabelReader`(§14 명명).
- **C-13** 순번 직렬화 = merchant 행 잠금 + READ COMMITTED 일반 SELECT(코드 표 FOR UPDATE 미사용 — 권한 설계 유지).

## 11. 알려진 위험·한계
- **L-1** 가맹점 코드 변경(계약 승인 전 1회) 뒤, 그 전에 발급된 Site·계약 코드는 옛 가맹점 코드를 담는다 — 표시 불일치(유일성·감사 무결성 유지). 사용자 답(Q-SC-2)으로 허용, 변경 화면 안내로 완화.
- **L-2** Demo·운영 DB 간 코드 중복은 막을 수 없다(MERCHANT-CODE-001 L-1과 같음).
- **L-3** 가맹점이 코드를 `merchantSiteId`로 잘못 보내면 기존 권리 판정 거절 — 외부 계약 변경 없음, 연동값 안내로 완화.
- **L-4** `mid_`·`act_`·`cur_` 등 다른 내부 참조는 범위 밖(MID는 외부 식별자). 필요 시 별도 질문.
- **L-5** 순번은 가맹점 단위 Site·계약 개수를 드러낸다 — 내부 전용(Q-SC-7)이라 허용. 외부 노출로 바뀌면 재검토.
- **L-6** 코드 행 NOT NULL은 DB로 표현 불가(자식 표) — 같은 TX INSERT·migration 시험·읽기 시 결함 503으로 보완.
- **L-7** 백필 가맹점 코드는 migration 시점 현재 코드다 — 백필 이전에 코드를 바꾼 가맹점의 과거 Site도 새 코드로 표시(Demo만 해당 가능, 유일성 영향 없음).
- **L-8** V19 백필은 큰 `merchant_site`·`contract` 표에서 실행 시간·잠금 시간이 표 크기에 비례(새 표 INSERT — 원 표는 공유 읽기). 운영 적용은 LG-1.

## 12. LIVE Gate(이번 작업은 Demo·시험만)
- **LG-1** V19 운영 적용: 대상 표 행 수 측정·백필 소요 시간 리허설, 적용 창 공지, 실패 시 Flyway repair 후 재시도(새 표만 생기므로 되돌리기 = 새 표 DROP + schema history 정리, 원 표 데이터 무변경).
- **LG-2** 배포 순서: MASTER·ADMIN(SCODE-1)을 같은 배포 단위로 — 응답 키 정확 일치. 롤백은 둘 함께.
- **LG-3** 운영 MASTER Flyway 세션 `sql_mode` strict 확인(백필 fail-closed 전제).
- **LG-4** 라벨 permit·분당 창·timeout은 MERCHANT-CODE-001 Demo 값 공유 — 운영값은 그 작업의 LIVE Gate 항목을 따른다(새 값 없음).
- **LG-5** API 연동값 전달 절차(운영자 → 가맹점 개발자 경로·대상 확인)는 운영 절차 문서에서 정한다 — 화면은 표시만 제공.

## 13. 미결
- 금융/보안 의미의 새 미결 **없음**. Q-SC-1~9 모두 답, 남은 선택은 §10 가역 구현 세부.
- 착수 시 재확인(질문 아님): §1.5 migration 번호 선점 여부, §1.6 E-3 Outbox 소비자(새 relay가 생겼는지), Flyway 세션 `sql_mode`, ADMIN 감사 detail 열 길이에 `code=` 추가 여유.

## 14. 검토했으나 채택 안 함
- 형식: 원문 밑줄 `site_<C>_NN`(내부 키와 판독·가드 충돌), 대문자 짧은 형식·전역 순번(전체 건수 노출), 가맹점 무관 무작위(소속 판독 불가) — Q-SC-1 답으로 미채택.
- 가맹점 코드 변경 시 Site·계약 코드 재발급(배정 이력 표·당시 코드 표시 필요) — Q-SC-2.
- 가맹점 안에서만 고유(코드만으로 식별 불가) — Q-SC-3.
- 운영자 직접 입력·자동 제안 후 수정(지문 변경·중복 거절 필요) — Q-SC-4.
- 1회 변경 허용·종료 번호 재사용 — Q-SC-5.
- csa 코드 발급 — Q-SC-6.
- 웹훅·결제 응답·정산서 노출(Snapshot 승격·PAYMENT migration) — Q-SC-7.
- `site_` 완전 숨김·외부 API 코드 입력 확장·거래 상세 병기 — Q-SC-8.
- 새 감사 행 `target_ref` = 코드(ADMIN migration·표 복사 ALTER) — Q-SC-9.
- 기존 표에 코드 열 추가(`contract` UPDATE 권한으로 불변 보장 불가) — C-2.
- 코드 표 FOR UPDATE로 순번 잠금(UPDATE 권한 필요 — 불변 권한 설계와 충돌) — C-13.

## 15. 기록
- 0.1 초안(2026-11-19) — HEAD `0323648` 기준 코드 대조. 구현·migration·커밋 없음.
- 0.2 확정(2026-11-20) — HEAD `4cd6a07` 기준 재대조, 사용자 답 Q-SC-1~9 반영, errata E-1~4. 구현·migration·커밋 없음. 다음: 명세 독립 리뷰 → SCODE-1.
