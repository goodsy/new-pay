# DOMAIN-CONSISTENCY-1 — MASTER 도메인 정합성 진단 (Business·Merchant·MerchantSite·Contract·MID)

- 작성: 2026-11-25, 기준 커밋 `41139db`(main). 진단 문서다. 소스·DB·결정·작업 상태는 바꾸지 않았고, 정책도 새로 정하지 않았다.
- 대상: Business, Merchant(+가맹점 코드), MerchantSite(+Site 심사), Contract(Revision·ContractServiceAgreement/CSA), MerchantAccount(MID). 관련된 SiteAssignment·DefaultDesignation·권리 Snapshot(Entitlement)도 함께 본다.
- 제외:
  - 개통(PaymentMethod/CardRoute Activation)·한도·정책 버전의 세부 규칙. 권리 Snapshot 조립과 관련된 부분만 본다.
  - main에 합쳐지지 않은 `wip/flow-1`(MERCHANT-FLOW-001 FLOW-1, 44파일)·`origin/wip/scode-1`(SCODE-1) 브랜치. "코드"는 main 기준이다.
  - 다른 에이전트가 작성 중인 `docs/rules/BIZ.md`(아직 없음. `docs/rules/README.md`만 있다). 의존하지 않았다.
- 표기
  - **[사실]**: 파일:줄이나 명령으로 확인했다.
  - **[해석]**: 사실에서 끌어낸 판단이다.
  - **미확인**: 확인하지 못했다.
  - 판정은 **일치 / 문서만 / 코드만 / 불일치** 넷 중 하나다.
- 경로 약어
  - `M/` = `apps/master-api/src/main/kotlin/com/paycore/master/merchant/`
  - `SQL/` = `apps/master-api/src/main/resources/db/migration/master/`
  - `PRIV/` = `apps/master-api/src/main/resources/db/runtime-privilege/master/`
  - `DEC` = `docs/DECISIONS.md`
  - `DD` = `docs/repository/DOMAIN-DEFINITION.md`
  - `MD1` = `docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md`
  - `MS1`·`MS2` = MASTER-SNAPSHOT-001·002
  - `BP1` = BUSINESS-PROFILE-001
  - `MOPS2` = MERCHANT-OPS-002
  - `FLOW1` = MERCHANT-FLOW-001
  - `구조` = `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md`
  - `POL` = `docs/baseline/PayCore-사업자관리-업무정의-정책서-v1.1.md`(PC-BIZ-POL-001)
  - `지침` = `docs/baseline/PayCore-사업자관리-개발지침서-v1.1.md`
- 시험은 **실행하지 않았다**. 시험 파일이 있는지만 확인했으므로 "검증 방법"은 제안이다.

---

## 1. 결론 요약

1. **확정된 핵심 불변식은 코드와 DB에 이중으로 들어가 있다.** [사실] 아래 규칙은 Domain과 DB 제약(복합 FK·생성 열 UNIQUE)·runtime 권한(INSERT 전용) 양쪽에서 확인된다. DOMAIN-ASSESSMENT §3.2·§7-6의 "보존 자산" 판단은 지금도 맞다.
   - 사업자번호 유일·불변
   - 한 계약 = 한 Merchant
   - MID 발급 근거 = 승인된 적용 Revision
   - 첫 연결 PRIMARY, 같은 MID–Site 중첩 금지, 소급 금지
   - 가맹점 코드 1회 변경
2. **불일치의 대부분은 "확정은 됐지만 아직 만들지 않은 생명주기"에서 나온다.** 아래 확정 정책 중 상당수가 코드에 없다. 모두 `MASTER-LIFECYCLE-001`(BACKLOG) 대기다. 그 결과 **D-MS-005 "거절된 Site는 새 Site로 재신청"이 같은 채널로는 실행되지 않는다**(DC-M-02, P1).
   - 종료(TERMINATED)·차단 기록(D-MD-006)
   - 연결 종료·교체(D-MD-005·007)
   - 계약 철회(D-MD-004)
   - 추가 MID·ADDITIONAL(D-MD-012·016)
3. **보안 결정 하나가 코드에 반영되지 않았다.** D-MOPS-010 ②는 관리 명령 멱등 지문을 HMAC으로 바꾸기로 했다. 코드는 아직 사업자등록번호·개인정보를 입력으로 하는 무염 SHA-256(`m1:`)이다(DC-M-01, P0 — 이미 LIVE Gate로 등록된 항목).
4. **문서끼리도 어긋난다.** 결정 원장(`DEC`)에서 사업자등록번호 마스킹 관련 옛 결정(D-BIZ-006 ②·D-MOPS-003·D-BIZ-003·D-MCODE-003 ②)에 "D-BIZ-007·D-PII-001로 변경" 표시가 없다. 그래서 확정 규칙 두 개가 서로 반대처럼 읽힌다(DC-M-03, P1). DD(2026-10-20 이후 미갱신)·MD1·POL은 그 뒤 결정을 반영하지 못했다(P4).
5. **사용자 결정이 필요한 공백도 있다.** "한 가맹점에 유효 계약(같은 서비스 종류 CSA)이 여럿일 수 있는가"를 정한 결정이나 문서를 찾지 못했다. 코드는 제한 없이 허용한다(DC-M-04, P1). CSA는 수수료·정산 정책 Assignment의 기준(D-MD-001)이라 금융 의미가 있다.
6. 문제는 **25건**이다. P0 1, P1 3, P2 11, P3 3, P4 7(§5).

---

## 2. 기존 진단(DOMAIN-ASSESSMENT·CURRENT-STATE, 2026-11-20 `378aff7`) 대비

그 뒤 커밋은 29개다. master-api 소스를 바꾼 커밋은 `cd9faa4`(BRN-UNMASK-001 — 조회 응답에 사업자등록번호 원문) 하나다. 문서로는 PII-MASKING-POLICY, MERCHANT-IA-001, MERCHANT-FLOW-001(D-FLOW-001~009)이 추가됐다. [사실: `git log 378aff7..HEAD`]

| 기존 진단 내용(절) | 이번 확인 | 판정 |
|---|---|---|
| DOMAIN-ASSESSMENT §3.2 "merchant.domain 88파일, 3,629줄, 하위 패키지 없음" | 88파일·3,629줄 그대로(`wc -l M/domain/*.kt`) | **확인** |
| DOMAIN-ASSESSMENT §3.2 끝 "domain Spec 28개" | `src/test/.../merchant/domain`에 파일은 28개다. 그중 `*Spec.kt`는 20개이고 나머지 8개는 Fixture다 | **정정** — Spec 20 + Fixture 8 |
| DOMAIN-ASSESSMENT §3.2 Business "2축 상태(lifecycle / DRAFT·REGISTERED·INACTIVE)" | 맞다. 다만 INACTIVE로 가는 경로가 없고, INACTIVE 행을 수정하면 `check()`로 결함 처리된다(`M/domain/Business.kt:60`) | **확인 + 보충**(DC-M-17) |
| DOMAIN-ASSESSMENT §3.2 MerchantAccount "첫 연결은 PRIMARY" | 맞다(`M/domain/MerchantAccountIssuance.kt:50-53`). 보충: Merchant당 MID는 **1개로 고정**이다(`:40-42` 기존 MID 있으면 거절, 추가 MID 경로 없음) | **확인 + 보충**(DC-M-11) |
| DOMAIN-ASSESSMENT §3.2 SiteAssignment "PRIMARY 1개" | 같은 MID 안에서만 판정한다(`M/domain/SiteAssignment.kt:84-95`). **다른 MID와의 Site 공유 검사는 없다** | **확인 + 보충**(DC-M-12) |
| DOMAIN-ASSESSMENT §4.3 "master는 모듈이 merchant 하나라 Modulith 경계 검사가 사실상 무의미" | 그대로다. `@NamedInterface("provided")` 하나(`apps/master-api/src/main/java/.../provided/package-info.java`) | **확인**(DC-M-15) |
| DOMAIN-ASSESSMENT §5 "DisplayName 생성자 vs BusinessTextRule 중복" | 그대로다. `M/domain/DisplayName.kt:6-21`이 생성자 검사를 직접 구현하고, `errorOf`만 `BusinessTextRule`을 쓴다(`:30`) | **확인**(DC-M-24) |
| DOMAIN-ASSESSMENT §3.4 "도메인 이벤트 타입 없음, Outbox 레코드로만" | master도 같다. `INTEGRATION_MASTER_REFERENCE` 이벤트는 **소비자가 없어 PENDING으로 영구히 남는다**(`M/application/AdminCommandExecutor.kt:356-398`, relay는 `ENTITLEMENT_SNAPSHOT`만 — `M/adapter/persistence/JpaEntitlementRelayStore.kt:27`) | **확인 + 보충**(DC-M-14) |
| CURRENT-STATE 머리 주의 "SCODE-1 부분 구현이 작업 트리에 미커밋" | 작업 트리에는 없다. `origin/wip/scode-1`(1a5be85)에만 있다. 작업 카드는 "로컬 브랜치 wip/scode-1"이라고 적었으나 로컬 브랜치는 없다 | **달라짐**(DC-M-22) |
| CURRENT-STATE §4 "MASTER 35표" | V1 15 + V3 8 + V5 5 + V9 1 + V13 1 + V15 5 = 35 | **확인** |
| CURRENT-STATE §5 "가맹점 생명주기 없음" | 그대로다(MASTER-LIFECYCLE-001 BACKLOG). 그 사이 FLOW1이 생명주기와 엮인 새 규칙을 추가했다: "계약 해지 → 연결 가맹점 개별 중지/철회 필요" 안내(D-FLOW-003 ②) | **확인 + 새 영향**(DC-M-07) |
| (새로 생김) 사업자 '계약 접수' 도메인 | D-FLOW-001~009로 확정됐다. main에 코드는 없다(wip/flow-1) | **문서만**(DC-M-16·18) |
| (새로 생김) 사업자등록번호 노출 범위 | D-BIZ-007·D-PII-001: 관리자 화면·조회 응답은 원문. 코드도 그렇게 바뀌었다(`M/adapter/query/MasterSqlText.kt:14`, `JooqBusinessDetailReader.kt:24`) | **일치**. 다만 옛 결정 행에 변경 표시가 없다(DC-M-03) |

---

## 3. 도메인별 대조

### 3.1 Business

| 축 | 내용 |
|---|---|
| Aggregate | 루트 `Business`(`M/domain/Business.kt:15-175`). 하위 정보 `BusinessProfile`(등록정보 1:1, 대표자·업태종목·주소·담당자는 효력 기간 이력 행)은 Root와 따로 들고 전이 메서드가 함께 받는다(`:11`) |
| 식별자 | `BusinessId`(`biz_`+ULID, `SQL/V1__master_core.sql:30`). 업무 키는 사업자등록번호 10자리이고 UNIQUE·불변이다(`:29,31`, `Business.kt:9`) |
| 책임 | 법적 사업 주체, 계약 당사자(D-MD-004), 새 가맹점 등록의 관문(D-BIZ-001) |
| 상태 | 생명주기 `ACTIVE/TERMINATED`(`M/domain/Lifecycle.kt:7`). 등록 상태 `DRAFT/REGISTERED/INACTIVE`(`BusinessRegistrationStatus.kt:7`). 진위확인 `NOT_CHECKED`만(`BusinessVerificationStatus.kt:7`) |
| 전이(코드) | 생성 DRAFT 또는 REGISTERED(`Business.kt:118-138`), DRAFT→DRAFT·REGISTERED→REGISTERED 수정(`:49-67`), DRAFT→REGISTERED(`:74-92`). **TERMINATED·INACTIVE로 가는 전이 없음** |
| 불변식 | 비DRAFT면 사업체 구분 필수(`:31`, DB `ck_business_type_required` `SQL/V15__business_profile.sql:34`). version 단조 +1. 갱신 시각 단조(`:98`). 진위확인은 NOT_CHECKED만(`V15:35`) |
| 연동 | Merchant.register가 `requireActive`·`requireRegistered`를 부른다(`M/domain/Merchant.kt:91-96`). Contract.register가 `requireActive`를 부른다(`Contract.kt:129`). Snapshot `PartyState`에는 생명주기만 실리고 등록 상태는 실리지 않는다(`EntitlementSnapshotAssembler.kt:31`). Outbox는 REFERENCE 이벤트(소비자 없음) |

| 항목 | 문서 정의(근거) | 코드·DB | 판정 |
|---|---|---|---|
| 번호당 하나·불변 | D-MD-008(`DEC:34`), D-BIZ-005(`DEC:228`), POL-001 | `uk_business_registration_number`(`V1:29`). 전이 메서드에 번호 인자 없음(`Business.kt:9`) | 일치 |
| 두 축 상태 | D-BIZ-001(`DEC:224`), BP1 §30 | 위 enum + `V15:33` | 일치 |
| 새 가맹점 = REGISTERED만 | D-BIZ-001 | `Merchant.kt:92-93`, `Business.kt:40-42` | 일치 |
| DRAFT 최소 입력 = 구분+번호+상호 | D-BIZ-002(`DEC:225`) | `createDraft`의 파라미터가 필수(`Business.kt:118-126`) | 일치 / **POL §5 표(`POL:54-71`, 셋 다 DRAFT "선택")와는 불일치** — 결정이 나중이라 POL이 옛 내용(DC-M-21) |
| REGISTERED→INACTIVE(권한·사유) | `지침:86` 전이 목록에 있음. POL-002 "처리 권한과 사유 필수"(`POL:84-89`) | 경로 없음. BP1이 P1 F-011로 미뤘다(`BP1:131,216`). TBD-08은 원래 "재활성화·권한"(`POL:154`) | **문서만**(DC-M-17) |
| 진위확인 4값 | POL-002 `NOT_CHECKED/VERIFIED/MISMATCH/FAILED` | NOT_CHECKED 하나(`V15:35`) | 문서만(TBD-06, 의도된 연기) |
| 생명주기 종료·차단 기록 | D-MD-006(`DEC:32`) | 전이·표 없음. Snapshot `businessBlocked=false` 고정(`EntitlementSnapshotAssembler.kt:31`) | **문서만**(DC-M-05·06) |
| Merchant/Agent/Seller 역할 | DD:93·211 "Business는 Merchant/Agent/Seller 역할을 가질 수 있다" | 역할 개념 없음 | 문서만(초기 문서 표현 — 과거 후보) |
| KYC(생년월일·실소유자) 수집 | D-FLOW-001 ④·D-FLOW-002가 수집으로 바꿈. POL-003(`POL:95`)·`지침:55`는 "받지 않는다" | main 코드 없음(wip/flow-1) | **문서 간 충돌**(결정이 baseline을 변경, baseline 미갱신 — DC-M-21) |
| 사업자 계약 접수 건(1:N) | D-FLOW-001 ①(`DEC:247`), FLOW1 §4A.1-4A.3(`FLOW1:151-204`) | main 없음 | 문서만(진행 중) |

### 3.2 Merchant (+ 가맹점 코드)

| 축 | 내용 |
|---|---|
| Aggregate | 루트 `Merchant`(`M/domain/Merchant.kt:12-110`). 포함: `MerchantCodeAssignment` 이력(최대 2행). **DefaultDesignation은 포함하지 않는다**(§3.5) |
| 식별자 | `MerchantId`(`mer_`+ULID). 사람용 `MerchantCode`(`^[a-z][a-z0-9]{5,13}$`, `MerchantCode.kt:24`)는 전역 UNIQUE이고 은퇴 코드도 점유한다(`SQL/V13__merchant_code_assignment.sql:19`) |
| 책임 | 가맹점 단위. **Merchant 안 관계 변경의 직렬화 지점**(행 잠금 — `Merchant.kt:6-7`, MD1 §3.3). 권리 버전 `entitlementRevision` 소유(`:9-10,79-81`) |
| 상태 | 생명주기 ACTIVE/TERMINATED. 종료 전이 없음 |
| 불변식 | seq 1 배정은 반드시 있고, 변경은 최대 1회이며 seq 1 코드를 바꾼다(`:34-39`, DB `ck_mca_seq` `V13:24`). 첫 계약 승인 뒤 코드 불변(`:65`). entitlementRevision ≥ 0·단조 |
| 연동 | Business(등록 관문). Contract(대상 Merchant, `hasApprovedRevisionFor` — `M/application/MerchantOnboardingService.kt:396`). Snapshot의 `merchantCode`·`revision`(`EntitlementSnapshotAssembler.kt:26-27`) |

| 항목 | 문서 정의(근거) | 코드·DB | 판정 |
|---|---|---|---|
| Business 1:N Merchant, 같은 사업자 아래 같은 이름 허용 | 구조 §13, D-MOPS-006 ⑥ | FK만 있고 이름 UNIQUE 없음(`V1:38-54`, 색인 V17) | 일치 |
| Merchant 1:N MID | 구조 §13(`구조:539-561`), DD:94 | **Merchant당 MID 1개**(`MerchantAccountIssuance.kt:40-42`). 추가 MID는 Q-MD-06(PROPOSED) | **불일치(의도된 미구현)**(DC-M-11) |
| 코드 형식·전역 유일·은퇴 코드 점유 | D-MCODE-001·002(`DEC:174-175`) | `MerchantCode.kt:12,24`, `V13:19-25` | 일치 |
| 첫 계약 승인 전 2인 1회 변경, 요청자≠승인자만 | D-MCODE-002 Q-MC-7·10 | `Merchant.kt:54-72`(근거 경로만), `MerchantOnboardingService.kt:391-417` | 일치 |
| 코드 변경 → revision +1·Snapshot 발행 | MERCHANT-CODE-001 MC-4 | `MerchantOnboardingService.kt:404,414` | 일치 |
| 생명주기·차단 기록 | D-MD-006 | 없음. Snapshot `merchantBlocked=false`(`:31`) | 문서만(DC-M-05) |
| entitlement_revision 증가 지점 | MD1 §3.3(`MD1:253`) "셋 — Revision 승인·최초 Default·개통 등록, Site 등록은 올리지 않음" | Site 등록(`MerchantOnboardingService.kt:133`)·코드 변경·Site 심사·통화·한도·정책 등에서도 올린다(`grep recordEntitlementChange` 30곳 이상) | **불일치(문서가 옛 내용)** — 코드는 MS1 review M-2를 따른다(DC-M-20) |
| 새 가맹점 = REGISTERED **+ 최신 접수 건 계약 완료** | D-FLOW-001 ①(`DEC:247`), FLOW1 §4A.5(`FLOW1:227-240`) | `Merchant.register`는 REGISTERED만 본다(`Merchant.kt:91-96`) | **문서만**(진행 중, DC-M-16) |

### 3.3 MerchantSite (+ Site 심사)

| 축 | 내용 |
|---|---|
| Aggregate | 루트 `MerchantSite`(`M/domain/MerchantSite.kt:6-35`). 심사는 Aggregate 밖의 결정 기록 `SiteReviewDecision` + Domain 서비스 `SiteReviewRules`(`SiteReviewDecision.kt:12-78`), 표는 `merchant_site_review_decision`(`SQL/V3__entitlement_snapshot.sql:141-156`) |
| 식별자 | `MerchantSiteId`(`site_`+ULID). 채널 `(type, identifier)`는 활성 중 전역 하나(`V1:66-70` 생성 열 `active_channel_key`) |
| 책임 | 결제 채널(WEB host/APP id). 계약 Scope·SiteAssignment의 대상 |
| 상태 | 생명주기 ACTIVE/TERMINATED(종료 전이 없음, runtime `SELECT, INSERT`만 — `PRIV/V2__master_runtime_privileges.sql:20`). 심사 PENDING→APPROVED/REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED/REJECTED, **REJECTED 종결**(`SiteReviewDecision.kt:33-38`) |
| 불변식 | 소문자 정규형만, IP 리터럴 거절(`SiteChannel.kt:10-33`). APPROVED는 활성 Site만(`:57`). Site 등록자 ≠ 2인 심사 승인자(`:75`). 결정 순번 연속(`:54`) |
| 연동 | Contract Scope(`ContractScope.kt:16-22`). SiteAssignment(같은 Merchant 복합 FK — `V1:226`). Snapshot `sites[].reviewStatus`·`contractWindows`(`EntitlementSnapshotAssembler.kt:32,53`). Site 등록이 revision +1·Snapshot 발행(`MerchantOnboardingService.kt:131-145`) |

| 항목 | 문서 정의(근거) | 코드·DB | 판정 |
|---|---|---|---|
| 같은 Merchant 소속 MID와만 연결 | 구조 §8, DD:95 | 복합 FK(`V1:224-226`), `SiteAssignment.kt:48` | 일치 |
| 활성 채널 중복 거절(같은 Merchant·다른 Merchant) | D-MD-008 | `MerchantOnboardingService.kt:129` + UNIQUE `V1:70` | 일치 |
| 채널 중복 예외 승인 기록 | D-MD-008 "예외는 승인 기록" | 없음(전부 거절) | 문서만(MASTER-LIFECYCLE-001 인수 기준) |
| 소문자 정규형·IP 리터럴 거절 | D-MD-009·014 | Domain `SiteChannel.kt:13-20`. DB CHECK는 더 느슨하다(`V1:74` `[a-z0-9._-]*`, WEB에 `_`·`..` 허용) | 일치(Domain이 권위) / DB 이중 방어는 부분적(DC-M-25) |
| 심사 상태 4종, 신규 PENDING, 결제는 APPROVED만 | D-MS-002 (a)(`DEC:47`) | `SiteReviewDecision.kt:9,41`, Snapshot 기본 PENDING(`EntitlementSnapshotAssembler.kt:32`) | 일치 |
| REJECTED 종결, **재신청은 새 Site** | D-MS-005(`DEC:50`), MOPS2 화면 문구 "새 Site로 재신청"(`MOPS2:435`) | REJECTED 뒤에도 Site 생명주기는 ACTIVE로 남는다 → 같은 채널로 새 Site를 등록하면 `SITE_CHANNEL_DUPLICATE`(`MerchantOnboardingService.kt:129`, `V1:66-70`). Site 종료 명령·UPDATE 권한도 없다 | **불일치**(DC-M-02, P1) |
| "Site 심사는 PG 심사 상태로 MerchantSite에 둔다" | D-MD-016(`DEC:42`) | 별도 append-only 표(Site FK). 현재 상태 = 마지막 결정 | 일치(저장 방식만 다름) |
| 종료된 채널 값 재사용 | Q-MD-18 PROPOSED(권장 "예외 승인 없으면 거절") | DB 유일성이 ACTIVE에만 걸려 있어, 종료 경로가 생기면 **다른 Merchant의 재등록이 DB 수준에서 허용되는 구조** | 미결(DC-M-13) |

### 3.4 Contract (Revision·CSA)

| 축 | 내용 |
|---|---|
| Aggregate | 루트 `Contract`(`M/domain/Contract.kt:16-147`). 포함: `ContractRevision`(append-only, 승인 사실 1회 — `ContractRevision.kt:9-44`), `ContractServiceAgreement`(`ContractServiceAgreement.kt:9-14`) |
| 식별자 | `ContractId`(`ctr_`), Revision은 `(contractId, revisionNo)` 1부터 연속(`Contract.kt:30`, PK `V1:112`). CSA는 `csa_`이고 `(contract, serviceKind)` UNIQUE(`V1:156`) |
| 책임 | 당사자(Business)·대상 Merchant·Scope·효력 기간·승인 사실. MID 발급 근거(`issuingBasis` — `Contract.kt:96-111`). 적용 Revision 판정(`:46-47`). Snapshot `contractWindows`(`ContractWindows.kt:8-27`) |
| 상태 | 상태 enum 없음. "승인 ∧ 효력 기간"으로 판정(MD1 §7-6 `MD1:468`). 철회·종료·재활성화 전이 없음(`Contract.kt:13-14`) |
| 불변식 | 모든 Revision은 같은 Merchant(D-MD-013 — `:31,56`, FK `V1:117-118`). Scope Merchant는 당사자 소속(`ContractScope.kt:31`). 소급 시작 금지(KST 오늘 00:00 기준 — `Contract.kt:57,130`). 끝난 Revision 승인 거절(`:64`). 재승인 금지(PK `V1:143`). 근거 경로는 Revision 등록자 ≠ 승인자(`:77`). 서비스 종류당 CSA 1건(`:32,82-84`) |
| 연동 | Merchant 행 잠금 → contract 잠금(`MerchantOnboardingService.kt:220-221`). 승인 시 Scope Merchant revision +1·Snapshot(`:225,233`). MID FK가 승인 행을 가리킨다(`V1:184-185`) |

| 항목 | 문서 정의(근거) | 코드·DB | 판정 |
|---|---|---|---|
| Contract 1:N CSA, CSA = 서비스 종류 1개 | D-MD-001·002(`DEC:27-28`) | `Contract.kt:32`, `V1:149-161`. 종류는 `CARD_PG_PAYMENT`만 | 일치 |
| 당사자 = Business, Scope MERCHANT/SITES(≥1), Scope 변경 = 새 Revision | D-MD-004(`DEC:30`), D-MD-010 | `ContractScope.kt:7-45`, `Contract.kt:55-59` | 일치 |
| 적용 Revision = 승인 ∧ 기간 포함 ∧ 번호 최대 | D-MD-011 | `Contract.kt:46-47` | 일치 |
| MID 근거 = 발급 시각 적용 Revision, 없으면 선발급 규칙 | D-MD-012·015 | `Contract.kt:96-111` + FK `V1:184-185` | 일치 |
| 소급 시작 금지(KST 오늘 이전 거절) | D-MOPS-006 ⑤·D-MOPS-007 ② | `Contract.kt:57,130`, `SeoulMidnight` | 일치 |
| 계약 승인 2인 + 근거 필수 + 등록자≠승인자 | D-MOPS-004·008 ①③ | `approveContractRevisionWithEvidence`(`MerchantOnboardingService.kt:198-207`). 근거 없는 경로는 Demo seed 전용(`:188-196,241-245`) | 일치 |
| 철회·종료·재활성화, CSA 단위 철회 | D-MD-004 | 없음. `ContractWindows.kt:6`에 "철회 기록이 생기면 확장"이라고만 적혀 있다 | 문서만(DC-M-06) |
| CSA 추가 권한 | D-MOPS-004 "등록(…계약 초안·CSA)은 OPERATOR 1인" | 1인. Revision 승인과 무관하게 언제든 추가할 수 있다(`MerchantOnboardingService.kt:247-265`, `Contract.kt:81-86` — 생명주기·승인 검사 없음) | 일치. 다만 **승인된 계약에 서비스 권리를 1인이 덧붙일 수 있는 구조**(DC-M-08) |
| 한 Merchant의 동시 유효 계약 수 | **결정·문서를 찾지 못함**(DEC·MD1·MOPS2·구조·FLOW1 검색) | 제한 없음. `registerContract`에 기존 계약 검사가 없다(`:149-165`) | **미결**(DC-M-04) |
| 승인·CSA 추가 때 당사자·대상 생명주기 | D-MD-006 "종료 비가역"(해석: 종료된 대상에 새 권리를 주면 안 됨) | `Contract.approve`·`approveWithEvidence`·`addAgreement`에 `requireActive`가 없다(`Contract.kt:62-86`). 등록·Revision 등록에는 있다(`:129`, `MerchantOnboardingService.kt:175`) | 코드만(종료 경로가 없어 지금은 드러나지 않음 — DC-M-09) |

### 3.5 MerchantAccount (MID) + DefaultDesignation + SiteAssignment

| 축 | 내용 |
|---|---|
| Aggregate | 루트 `MerchantAccount`(`M/domain/MerchantAccount.kt:10-43`). 발급 근거 `IssuingBasis`는 불변(`Contract.kt:6-10`). `DefaultDesignation`(`MerchantAccount.kt:45-51`)과 `SiteAssignment`(`SiteAssignment.kt:15-76`)는 별도 행이다. 최초 발급은 Domain 서비스 `MerchantAccountIssuance`(`MerchantAccountIssuance.kt:21-57`)가 셋을 한 결과로 만든다 |
| 식별자 | `mid_`+ULID. Assignment는 `asg_`. Default는 PK `merchant_id`(`V1:197`) |
| 책임 | 계약 단위 결제 계정. 발급 ≠ 개통 ≠ 결제 허용(`MerchantAccount.kt:8`). Production MID만 있고 TEST/LIVE는 Credential 축이다(D-MD-003) |
| 상태 | MID ACTIVE/TERMINATED(종료 전이 없음). Assignment ACTIVE/SUSPENDED/ENDED — 만들어지는 상태는 ACTIVE뿐(`SiteAssignment.kt:8-9`). runtime `merchant_account_site_assignment`·`default_designation`은 `SELECT, INSERT`(`PRIV/V2:38,41`) |
| 불변식 | MID는 한 Merchant·그 Business 소속(복합 FK `V1:178-185`). 근거 Revision은 승인된 것(FK → 승인 표). 최초 발급 시 기존 MID 0개(`MerchantAccountIssuance.kt:40-42`, merchant 잠금 아래 셈 — `MerchantOnboardingService.kt:299-302`). 첫 연결 PRIMARY·기간 발급 시각부터. 소급 금지(`SiteAssignment.kt:51`, DB `ck_assignment_not_retroactive` `V1:231`). 같은 MID–Site 열린 연결 하나·MID당 열린 PRIMARY 하나(생성 열 UNIQUE `V1:217-223`). 닫힌 기간 중첩은 Domain(`SiteAssignment.kt:84-95`) |
| 연동 | MID·Default·PRIMARY·revision·이력·Outbox·요청 결과가 한 MASTER 로컬 TX(`MerchantOnboardingService.kt:312-342`). Snapshot `accounts[]`(`EntitlementSnapshotAssembler.kt:37-80`) → PAYMENT |

| 항목 | 문서 정의(근거) | 코드·DB | 판정 |
|---|---|---|---|
| 최초 Default 발급 원자성(한 TX) | 구조 경계 §1(`구조:603-610`), MD1 `:83` | `MerchantOnboardingService.kt:312-342`, 시험 `FirstDefaultIssuanceAtomicitySpec.kt` 있음(미실행) | 일치 |
| 다른 키의 동시 최초 발급은 하나만 성공 | MD1 `:83` | merchant 잠금 + `default_designation` PK | 일치 |
| **DefaultDesignation 소속 Aggregate** | MD1 §2.1(`MD1:56`): "Merchant Aggregate에 포함(Merchant 불변식 최대 1개)" | MID 쪽 값으로 정의된다(`MerchantAccount.kt:45-51`). `MerchantAccountRepository.insertFirstDefault`가 저장하고(`application/required/MerchantAccountRepository.kt:15`, `JpaMerchantAccountRepository.kt:38`) Merchant에는 필드가 없다 | **불일치**(DC-M-10) |
| Merchant 1:N MID, 추가 MID | 구조 §13, D-MD-012 "최초·추가" | 추가 경로 없음. Q-MD-06 PROPOSED | 불일치(의도된 미구현 — DC-M-11) |
| Default 교체·종료 | Q-MD-07 PROPOSED, DD:97 "미결" | 없음(INSERT만) | 미결 |
| 기본 1:1, 조건부 N Site(5조건) | 구조 §7(`구조:306-356`), D-MD-016 "ADDITIONAL 생성은 다섯 공유 조건 증거" | ADDITIONAL 생성 경로 없음 | 문서만(DC-M-12) |
| 한 Site의 복수 MID | 구조 경계 §2(`구조:615`) "각각 별도 계약/기관 경로일 수 있음" | 다른 MID 사이의 같은 Site 연결을 막는 Domain·DB 규칙이 없다(open pair key가 `MID\|Site` — `V1:217-218`). MID가 1개라 지금은 드러나지 않음 | 문서만·조건 미정(DC-M-12) |
| PRIMARY 교체 한 TX·자동 승격 없음·ADDITIONAL만 남으면 차단 | D-MD-007 | 교체 경로 없음. 차단 판정은 PAYMENT 쪽 — **미확인**(이번 범위는 master만 봄) | 문서만 / 미확인 |
| 연결 종료·중지(미래 종료 예약) | D-MD-005 | 없음. 테이블 runtime UPDATE 권한 없음(`PRIV/V2:41`) — 종료하려면 권한 migration이나 다른 모델이 필요 | 문서만(DC-M-06) |
| 생명주기·차단 | D-MD-006, D-MD-019·D-MS-002 (c)(TERMINATED → MERCHANT_SUSPENDED) | 종료 없음. Snapshot `blocked=false`(`EntitlementSnapshotAssembler.kt:47`) | 문서만(DC-M-05) |

### 3.6 연동 — 권리 Snapshot(Entitlement)

| 항목 | 문서 정의 | 코드 | 판정 |
|---|---|---|---|
| Merchant 전체 상태 Snapshot, 버전 = `entitlement_revision` | MS1 §3.1-3.2(`MS1:77,100-102`) | `EntitlementSnapshotAssembler.kt:16-35` | 일치 |
| 권리 입력을 바꾼 TX = revision +1 + 같은 TX 발행 | MS1 §4.6, `MS1:471` | 등록·승인·발급·코드·심사·개통·통화·한도·정책 명령 30곳 이상(`recordEntitlementChange`/`publishEntitlement`) | 일치(MD1 §3.3 문구는 옛 내용 — DC-M-20) |
| 사업자 등록 상태·접수 건·KYC는 Snapshot 무영향 | POL-006(`POL:110`), FLOW1 §4A.2(`FLOW1:170`) | `PartyState`는 생명주기만(`:31`) | 일치 |
| 차단 기록 반영 | D-MD-006 | `false` 고정(`:31,47`) | 문서만(DC-M-05) |
| 철회된 Revision 제외 | D-MD-004, MS1 | `ContractWindows.kt:6`("확장 예정") | 문서만 |
| MASTER→FINANCIAL 정책·Assignment 이벤트 | DD §2.1(`DD:61`) | 경로 없음. REFERENCE 이벤트는 PENDING으로 남는다(`MS1:208` "소비자 생길 때 처리") | 문서만(DC-M-14) |

---

## 4. 정책 분류

### 4.1 확정 현행(문서 = 코드)

| 정책 | 근거 결정·문서 | 코드 근거 |
|---|---|---|
| 사업자번호 유일·불변, 로그·이력은 마스킹 | D-MD-008, D-BIZ-005 | `V1:29`, `BusinessRegistrationNumber.kt:15-17` |
| 사업자 두 축 상태, 새 가맹점은 REGISTERED만 | D-BIZ-001 | `Business.kt:40-42`, `Merchant.kt:92-93` |
| 관리자 화면·조회 응답은 사업자등록번호 원문 | D-BIZ-007, D-PII-001(`DEC:231,246`) | `MasterSqlText.kt:14` |
| Contract 2단(CSA), 한 계약 = 한 Merchant, Scope = 한 Merchant | D-MD-001·002·004·010·013 | §3.4 |
| 적용 Revision·발급 근거·선발급 | D-MD-011·012·014·015 | `Contract.kt:46-111` |
| 계약·Revision 소급 시작 금지(KST) | D-MOPS-006 ⑤·007 ② | `Contract.kt:57,130` |
| 2인 범위·근거 필수·등록자≠승인자(Revision·Site) | D-MOPS-004·007·008·009 ③·010 ① | `Contract.kt:74-79`, `SiteReviewDecision.kt:67-77`, 실행기 `executeApproved`(`AdminCommandExecutor.kt:95`) |
| Demo seed의 근거 없는 승인은 Demo 모드 + `system:demo-seed`만 | D-MOPS-008 ②, D-DEMO-005 | `MerchantOnboardingService.kt:241-245` |
| 최초 Default MID 원자 발급, 첫 연결 PRIMARY | 구조 경계 §1, MD1 | `MerchantAccountIssuance.kt`, `MerchantOnboardingService.kt:312-342` |
| UTC [from,to), 같은 MID–Site 중첩 금지, 소급 금지, PRIMARY ≤ 1 | D-MD-005·007 | `SiteAssignment.kt:51,84-95`, `V1:217-232` |
| Site 채널 정규형·전역 활성 유일 | D-MD-008·009·014 | `SiteChannel.kt`, `V1:70` |
| Site 심사 4상태·REJECTED 종결·등록자≠승인자 | D-MS-002 (a)·005, D-MOPS-009 ③ | `SiteReviewDecision.kt:33-77` |
| 가맹점 코드 형식·전역 유일·1회 변경·승인 뒤 불변·외부 노출(Snapshot v4) | D-MCODE-001·002 | `MerchantCode.kt`, `Merchant.kt:54-72`, `V13` |
| TEST도 LIVE와 같은 권리 조건(Snapshot에 환경 축 없음) | D-MS-001 | `EntitlementSnapshotAssembler`(환경 필드 없음) |

### 4.2 미결(사용자 답 없음)

| ID | 내용 | 근거 | 막히는 것 |
|---|---|---|---|
| Q-MD-06 | 추가 MID 발급 근거·승인(CSA당 복수 MID) | `harness/questions.json` PROPOSED | MASTER-LIFECYCLE-001, DC-M-11 |
| Q-MD-07 | Default 교체·종료 | PROPOSED | 같음 |
| Q-MD-18 | 종료된 Site 채널을 다른 Merchant가 재사용할 수 있는가 | PROPOSED | Site 종료 명령, DC-M-13 |
| (질문 없음) | 한 Merchant의 동시 유효 계약·같은 종류 CSA 복수 | 결정·문서를 찾지 못함 | 정책 Assignment(SETTLEMENT-CONTRACT-001), 추가 MID |
| (질문 없음) | 한 Site를 여러 MID가 쓰는 조건 | 구조 경계 §2는 "가능"이라고만 함, 조건 미정 | ADDITIONAL·추가 MID |
| TBD-04·05·08 | 사업자번호 정정, DRAFT 만료·삭제, INACTIVE 전이·재활성화 | `POL:150-154`, D-BIZ-002·005 | 사업자 정리, DC-M-17 |
| TBD-06 | 진위·영업상태 연동 | `POL:153` | verification 값 확장 |
| Q-PII-3·Q-PII-5 | UNMASK 첫 적용 데이터, 개인정보 보관·파기 기간 | `docs/repository/PII-MASKING-POLICY.md:212,214`. D-FLOW-002 ①④가 일부 해소·연결. **questions.json에는 없다** | KYC 원문 조회, LIVE Gate(DC-M-23) |

### 4.3 충돌(확정 규칙끼리 또는 문서 간) — 해결하지 않고 대안만 적는다

| 충돌 | 양쪽 근거 | 대안 |
|---|---|---|
| **거절 Site의 재신청 vs 채널 전역 유일** | D-MS-005 "재신청은 새 Site" ↔ D-MD-008 "활성 채널 중복 거절" + Site 종료 경로 없음(`PRIV/V2:20`) | ① 채널 유일성 판정에서 REJECTED Site를 제외(생성 열이 심사 상태를 보게 함) ② REJECTED 결정 시 Site 생명주기를 함께 종료(Site 종료 명령 선행 필요) ③ 같은 채널 재신청은 D-MD-008 "예외 승인 기록"으로만 허용 ④ 현행 유지 — 같은 채널은 재신청 불가로 문서화(D-MS-005 문구 축소) |
| 사업자등록번호 마스킹 | D-BIZ-006 ②("어디서나 마스킹", `DEC:229`)·D-MOPS-003("응답은 마스킹 값만", `DEC:183`)·D-BIZ-003(마스킹 번호, `DEC:226`)·D-MCODE-003 ②(마스킹 값, `DEC:177`) ↔ D-BIZ-007·D-PII-001(원문) | 내용상 나중 결정(D-PII-001)이 우선이라 실제 충돌은 아니다. 옛 행에 "[변경: D-BIZ-007/D-PII-001]" 역참조를 붙이는 표기 정리만 필요(DC-M-03) |
| KYC 수집 여부 | POL-003·§1-5(`POL:15,95`), `지침:55` "받지 않음" ↔ D-FLOW-001 ④·D-FLOW-002 "수집" | 결정이 baseline을 변경했다(FLOW1이 명시). POL·지침에 변경 주석을 달거나 BASELINE-INDEX에 "부분 대체" 표시 |
| DRAFT 최소 입력 | POL §5 표(DRAFT 모두 선택) ↔ D-BIZ-002(구분+번호+상호 필수) | 위와 같다(baseline 주석) |
| '계약' 용어 | FLOW1 사업자 '계약 완료/계약 해지'(결제 무영향, D-FLOW-003 ②) ↔ D-MD-004 가맹점 계약 '승인/철회'(철회 = 신규 결제 차단) | ① 사업자 쪽을 '계약 접수 완료/접수 해지' 등 다른 말로 ② 화면에 "결제 권리와 무관" 고정 표기(FLOW1이 일부 반영) ③ 현행 유지 + 용어사전(DD §5)에 두 개념 분리 등재(DC-M-18) |

### 4.4 코드 ≠ 문서

| 항목 | 문서 | 코드 | 성격 |
|---|---|---|---|
| DefaultDesignation 소속 | MD1 §2.1 Merchant 포함 | MID 쪽 저장(`MerchantAccountRepository.kt:15`) | 경계 설계 차이(DC-M-10) |
| entitlement_revision 증가 지점 | MD1 §3.3 "셋, Site 등록 제외" | 30곳 이상, Site 등록 포함 | 문서가 옛 내용(DC-M-20) |
| Merchant 1:N MID | 구조 §13, DD:94 | 1개 고정 | 의도된 미구현(DC-M-11) |
| 새 가맹점 관문(계약 완료) | D-FLOW-001 ① | 없음 | 구현 대기(DC-M-16) |
| 멱등 지문 HMAC | D-MOPS-010 ②(`DEC:190`) | `m1:` 무염 SHA-256(`AdminCommandExecutor.kt:417-428`) | 결정 미적용(DC-M-01) |
| REGISTERED→INACTIVE | `지침:86` | 없음(BP1 P1 F-011) | 범위 해석 차이(DC-M-17) |
| 생명주기·차단·철회·종료·ADDITIONAL | D-MD-004~007·016 | 없음 | 의도된 미구현(DC-M-05·06·12) |
| Q-MD-08 상태 | DD:98 "미결" | D-MD-016으로 답함(`DEC:42`) | 문서가 옛 내용(DC-M-19) |

### 4.5 과거·폐기 후보(대체됐는데 그대로 남은 문서)

| 문서·위치 | 대체한 것 | 비고 |
|---|---|---|
| `DD` 전반(마지막 수정 2026-10-20, `git log`) | D-BIZ-001~007, D-MS-002·005, D-MCODE, D-FLOW, D-PII | 색인 문서인데 그 뒤 결정을 반영하지 못함(DC-M-19) |
| MD1 제목 "1단계: 명세·질문 후보"(`MD1:1`), §1 "관리자 web adapter … 만들지 않는다"(`MD1:34`) | ADMIN-BOOT-001·MERCHANT-OPS-001/002 | DC-M-20 |
| POL §1-5·§3 "Merchant/KYB/Contract 이번 구현 제외", POL-003 KYC 미수집, §5 DRAFT 표 | BP1 구현, D-BIZ-002, D-FLOW-001 ④ | baseline이라 원문 보존 + 대체 표시가 필요(DC-M-21) |
| `지침:55,228` "Merchant 생성·계약·MID 제외" | 같음 | 사업자 1차 작업 범위 문장이라 그 작업에만 해당 |
| D-MOPS-006 ③ | D-BIZ-003(행에 변경 표시 있음 — `DEC:186`) | 처리됨(모범 사례) |
| D-BIZ-006 ②·D-MOPS-003 "응답 마스킹"·D-BIZ-003 "마스킹 번호"·D-MCODE-003 ② | D-BIZ-007·D-PII-001 | 표시 없음(DC-M-03) |
| MERCHANT-BIZ-LOOKUP-001 작업 카드 인수 기준 "권한 OPERATOR·평문 미노출" | D-BIZ-004(합침)·D-BIZ-006 ①·D-BIZ-007 | BACKLOG로 남아 있음(DC-M-22) |
| DD:93·211 "Business의 Merchant/Agent/Seller 역할" | 실제 모델에 역할 없음(초기 A00 표현) | 유지할지 삭제할지 사용자 확인 필요는 낮음 — 문서 정리 |

---

## 5. 문제 목록

열: ID | 우선순위 | 문제 | 근거 | 영향 범위 | 수정 난이도 | 예상 영향 | 검증 방법 | 개선 방향(대안 — 해결 아님)

| ID | P | 문제 | 근거(file:line·문서§) | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향(대안) |
|---|---|---|---|---|---|---|---|---|
| DC-M-01 | P0 | 관리 명령 멱등 지문이 **무염 SHA-256**이다. 입력에 사업자등록번호·대표자/담당자 이름·전화·이메일이 들어가 대입 확인이 가능하다. D-MOPS-010 ②(HMAC 교체) 결정이 반영되지 않았다 | `M/application/AdminCommandExecutor.kt:417-428`, `M/application/BusinessCommandFingerprintFields.kt:9-12`(L-13 주석), `SQL/V1:275`(`admin_request.request_fingerprint`), `DEC:190`, tasks MERCHANT-OPS-002 openItems "B3-3a … LIVE 사용 전 필수" | MASTER `admin_request` 전 행(사업자·가맹점 명령) | 중 | DB 접근자가 알려진 상호 + 번호 후보로 지문을 대조해 번호·개인정보를 확인할 수 있다. 특히 옛 `RegisterBusiness` 지문(번호 + 상호). Demo 단계이고 이미 LIVE Gate로 추적 중 | 지문 생성 단위 시험(키 없는 재현 불가), 같은 키·다른 내용 409 회귀, 기존 행 호환 비교(Demo 한정) 시험 | ① 결정대로 HMAC(MASTER 비밀 키) 전환 + 기존 행 이중 비교(Demo 한정, 제거 조건 기록) ② 개인정보 필드를 지문에서 빼고 별도 HMAC 필드 하나로 ③ LIVE 전까지 현행 유지, Gate 항목만 강화 |
| DC-M-02 | P1 | **REJECTED Site는 "새 Site로 재신청"(D-MS-005)인데 같은 채널로는 재신청할 수 없다.** 거절 뒤에도 Site 생명주기가 ACTIVE라 활성 채널 유일성(D-MD-008)에 걸린다. Site 종료 명령·UPDATE 권한이 없다 | `M/domain/SiteReviewDecision.kt:29-38`, `M/application/MerchantOnboardingService.kt:129`, `SQL/V1:66-70`, `PRIV/V2:20`, `DEC:34,50`, `MOPS2:435`(화면 문구 "새 Site로 재신청") | Site 등록·심사, 관리자 화면 안내, 가맹점 온보딩 | 중 | 같은 도메인을 다시 심사받을 수 없다. 화면 안내와 실제 동작이 다르다. 운영자가 우회(다른 표기·서브도메인)하면 D-MD-009 취지를 벗어난다 | 시험: Site 등록 → 1인 거절 → 같은 채널 새 Site 등록 → 현재 `SITE_CHANNEL_DUPLICATE`가 나오는지 확인(코드 판독 기준, **시험 미실행**) | §4.3 대안 ①~④(유일성에서 REJECTED 제외 / 거절 시 Site 종료 / 예외 승인 / 재신청 불가로 문서 축소) |
| DC-M-03 | P1 | 결정 원장에서 사업자등록번호 마스킹 관련 옛 결정에 **변경 역참조가 없다.** 같은 원장 안에 "어디서나 마스킹"과 "원문"이 둘 다 확정으로 읽힌다 | `DEC:177`(D-MCODE-003 ②), `DEC:183`(D-MOPS-003), `DEC:226`(D-BIZ-003), `DEC:229`(D-BIZ-006 ②) ↔ `DEC:231`(D-BIZ-007), `DEC:246`(D-PII-001). 모범: D-MOPS-006 ③ 행에는 변경 표시가 있다(`DEC:186`) | 결정 원장, 이후 명세·구현자·검증자 | 하 | 옛 결정을 근거로 마스킹을 되살리는 구현·리뷰 지적이 생길 수 있다 | 원장 grep: 대체된 결정 행마다 `[변경: …]` 표시가 있는지(하네스 문서 점검 스크립트 후보) | ① 옛 행에 역참조 주석 추가(결정 내용 변경 없음) ② 원장에 "대체 관계" 열 추가 ③ RULE-GOV-001 업무 규칙 대장에서 현행값 한 줄로만 관리 |
| DC-M-04 | P1 | **한 Merchant에 동시 유효 계약(같은 서비스 종류 CSA)이 여럿일 수 있는지** 정한 결정·문서를 찾지 못했다. 코드는 제한 없이 허용한다 | `M/application/MerchantOnboardingService.kt:149-165`(기존 계약 검사 없음), `SQL/V1:84-98`(merchant당 UNIQUE 없음), `SQL/V1:156`(UNIQUE는 계약 안에서만), D-MD-001(`DEC:27`, CSA = 수수료·정산 Assignment 기준) | 계약 등록, 추가 MID(Q-MD-06), SETTLEMENT-CONTRACT-001 정책 배정, Site 공유 조건 "같은 CSA" | 중(정책 결정 후 하~중) | 같은 가맹점·같은 서비스에 근거 계약이 둘이면 어느 CSA의 수수료·정산 정책을 쓰는지 모호해진다. 지금은 MID 1개라 MID가 가리키는 계약 하나만 쓰여서 실제 영향은 아직 없다 | 시험: 같은 Merchant에 MERCHANT Scope 계약 2개 등록·승인이 모두 성공하는지 확인(미실행) | ① 같은 Merchant·같은 서비스 종류의 기간 겹치는 유효 계약 금지(Revision으로만 갱신) ② 허용하되 MID가 근거 CSA 하나만 따르는 현행을 명시(기간 겹침 경고) ③ SETTLEMENT-CONTRACT-001에서 결정. **사용자 질문 필요** |
| DC-M-05 | P2 | D-MD-006 **신규 결제 차단 기록**(사유·행위자, 여럿 독립)이 없다. Snapshot의 `businessBlocked/merchantBlocked/blocked`는 `false` 고정이다 | `M/domain/EntitlementSnapshotAssembler.kt:10,31,47`, `DEC:32`, `MS1:471` | MASTER→PAYMENT 권리, 긴급 차단(허용 §4.2) | 상 | Business·Merchant·MID 단위의 즉시 신규 결제 차단 수단이 MASTER에 없다. 지금은 결제수단·Route 개통 정지, Site 심사 SUSPENDED로 대신할 수 있다(1인 안전 방향, D-MOPS-004) | MASTER-LIFECYCLE-001 인수 시험: 차단 2건 → 1건 해제 → 계속 차단, Snapshot 반영·PAYMENT NotEligible | ① MASTER-LIFECYCLE-001에서 차단 표·명령 ② LIVE 전까지 대체 수단(개통 정지)을 운영 절차서에 명시 ③ 차단만 먼저 떼어 작은 작업으로 |
| DC-M-06 | P2 | **생명주기 전이가 하나도 없다.** Business·Merchant·Site·MID 종료, 연결 종료·중지, 계약/CSA 철회·재활성화, PRIMARY 교체. runtime 권한도 INSERT 전용이다(`merchant_site`, `merchant_account_site_assignment`, `default_designation`, `contract_service_agreement`) | `M/domain/Lifecycle.kt:4-6`, `Contract.kt:13-14`, `SiteAssignment.kt:8`, `PRIV/V2:20,31,38,41`, MD1 §2.3(`MD1:181-183`), tasks MASTER-LIFECYCLE-001 BACKLOG | MASTER 전 도메인, Snapshot, FLOW1 계약 해지 후속 | 상 | 확정 정책 D-MD-004~007이 운영에서 실행되지 않는다. 종료가 생기면 DC-M-09·13 같은 잠재 결함이 드러난다 | 각 전이별 Domain·동시성·권한(1142) 시험 | ① MASTER-LIFECYCLE-001을 명령 묶음별로 쪼개 순서 결정(차단 → Site 종료 → 연결 종료/교체 → 철회) ② 권한 migration을 전이 작업마다 함께 ③ 현행 유지(LIVE 전 필수 목록에 등록) |
| DC-M-07 | P2 | FLOW1 "계약 해지 → 연결 가맹점 n개, 개별 중지/철회 필요" 안내가 가리키는 **가맹점 중지·계약 철회 명령이 없다** | `FLOW1:200`(§4A.3 계약 해지 = 기록만), D-FLOW-003 ②(`DEC:249`), DC-M-06 | 사업자 계약 해지 운영 절차 | 중 | 운영자가 안내를 따라도 MASTER에서 할 수 있는 일은 개통 정지 정도다. 해지된 사업자의 기존 가맹점 결제가 계속된다(결정상 의도이지만 후속 수단이 없음) | 운영 절차 검토: 해지 시나리오에서 실제 클릭 가능한 명령 목록 대조 | ① 안내 문구를 현재 가능한 수단(결제수단 개통 정지)으로 바꿈 ② FLOW 배치 전에 MASTER-LIFECYCLE-001의 중지 명령 선행 ③ 현행 유지 + "후속 기능" 표기 |
| DC-M-08 | P2 | **CSA(서비스 권리)는 Revision 승인 대상이 아니다.** 2인 승인된 계약에도 OPERATOR 1인이 언제든 CSA를 덧붙일 수 있고, 덧붙인 CSA는 바로 MID 발급 근거가 된다 | `M/domain/Contract.kt:81-86`(승인·생명주기 검사 없음), `:97`(근거는 CSA 존재만 확인), `M/application/MerchantOnboardingService.kt:247-265`, D-MOPS-004(`DEC:184`, CSA 등록 1인) | 계약 승인 통제, 향후 서비스 종류 확장 | 중 | 지금은 서비스 종류가 하나이고 계약당 종류 UNIQUE라 실효가 없다. 종류가 늘면 "승인된 계약 범위"가 승인 뒤 1인으로 넓어진다. MID 발급 자체는 2인이다 | 시험: 승인 뒤 CSA 추가 → 그 CSA로 MID 발급 성공 여부(미실행) | ① CSA를 Revision 내용에 포함(승인 대상) ② CSA 추가를 2인 명령으로 ③ 현행 유지 + "MID 발급 2인이 실질 통제"를 명시. **서비스 종류 확장 전 사용자 결정 필요** |
| DC-M-09 | P2 | Revision 승인(근거·seed 경로)과 CSA 추가가 **당사자 Business·대상 Merchant 생명주기를 검사하지 않는다.** 등록 계열은 검사한다 | `M/domain/Contract.kt:62-86` vs `:129`, `M/application/MerchantOnboardingService.kt:175,220`(merchant 잠금만 하고 `requireActive` 없음) | 계약 승인, MID 발급 근거 | 하 | 지금은 종료 경로가 없어 드러나지 않는다. 종료가 생기면 종료된 가맹점의 계약 승인이 revision +1·Snapshot 발행까지 이어질 수 있다 | Domain 시험: TERMINATED Merchant/Business 재구성 후 approve → 거절 기대(MASTER-LIFECYCLE-001과 함께) | ① 승인·CSA 추가에 `requireActive` 추가 ② 종료 명령 쪽에서 미승인 Revision을 함께 무효화 ③ 현행 유지 + LIFECYCLE 인수 기준에 등록 |
| DC-M-10 | P2 | **DefaultDesignation의 Aggregate 소속이 문서와 코드에서 다르다**(문서: Merchant 포함 / 코드: MID 쪽 값·MID 저장소) | `MD1:56` vs `M/domain/MerchantAccount.kt:45-51`, `M/application/required/MerchantAccountRepository.kt:12-15`, `JpaMerchantAccountRepository.kt:26,38` | Default 교체(Q-MD-07), 추가 MID 설계 | 하(문서) ~ 중(코드) | Default 교체를 설계할 때 불변식("Merchant당 최대 1")을 누가 지키는지 헷갈린다. 지금은 DB PK가 지킨다(`V1:197`) | 설계 리뷰 | ① 문서를 코드에 맞춤(Merchant 잠금 아래 MID 저장소가 관리) ② Merchant Aggregate로 옮김 ③ Q-MD-07 답할 때 함께 결정 |
| DC-M-11 | P2 | 구조 §13·DD의 "Merchant 1:N MID"와 달리 코드는 **Merchant당 MID 1개로 고정**이다. 추가 MID(Q-MD-06)·Default 교체(Q-MD-07)는 PROPOSED 상태로 묶여 있다 | `M/domain/MerchantAccountIssuance.kt:40-42`, `DD:94`, `구조:539-561`, `M/application/provided/MerchantOnboardingUseCase.kt:24` | MID 모델, 정산(MID별), Site 공유 | 상 | 확장 전까지 한 가맹점의 여러 계약·기관 경로를 표현할 수 없다 | — | ① Q-MD-06·07 질문을 다시 제시 ② 현행 1개 고정을 v0.1 범위로 명시(구조 문서에 주석) |
| DC-M-12 | P2 | ADDITIONAL·Site 공유 5조건(D-MD-016)이 없다. **서로 다른 MID가 같은 Site를 연결하는 것을 막거나 조건 짓는 규칙도 없다**(`SiteAssignmentRules`는 같은 MID만 봄) | `M/domain/SiteAssignment.kt:83-95`, `SQL/V1:217-218`, `구조:306-356,611-618`, `DEC:42` | 추가 MID 도입 시 Site 연결, 주문 중복 제어 | 중 | MID가 1개라 지금은 드러나지 않는다. 추가 MID가 생기면 "한 Site 복수 MID"가 조건 없이 열린다 | 추가 MID 작업의 동시성·조건 시험 | ① 추가 MID 작업에 Site 공유 규칙(같은 Merchant + 별도 계약/기관 경로 조건) 명시 ② 기본 금지 + 예외 승인 ③ 사용자 질문으로 조건 확정 |
| DC-M-13 | P2 | 종료된 Site 채널 재사용(Q-MD-18) 미결. DB 유일성이 ACTIVE 행에만 걸려, 종료가 생기면 **다른 Merchant가 같은 채널을 DB 제약 없이 등록**할 수 있는 구조다 | `SQL/V1:66-70`, questions Q-MD-18 PROPOSED, D-MD-008 | Site 종료 이후 | 하~중 | 도메인 이전·사칭 위험(Q-MD-18 권장안 근거) | Site 종료 구현 시 시험 | ① 권장안(예외 승인 없으면 거절) — 이력 조회 판정 추가 ② 유일성 키를 생명주기 무관으로 ③ 허용(재사용) |
| DC-M-14 | P2 | `INTEGRATION_MASTER_REFERENCE` Outbox 이벤트에 **소비자가 없어 PENDING으로 무기한 쌓인다**. 보존·정리 규칙이 없다. DD의 "MASTER→FINANCIAL 정책·Assignment 이벤트" 경로도 없다 | `M/application/AdminCommandExecutor.kt:356-398`, `M/adapter/persistence/JpaEntitlementRelayStore.kt:27`, `SQL/V1:294-318`(DELETE 권한 없음 `PRIV/V2:54`), `MS1:208`, `DD:61` | MASTER outbox 표 크기, 향후 소비자 첫 연결 시 백로그 | 중 | 표가 계속 커진다. 소비자가 생기면 과거 이벤트 전체를 처리할지 정해야 한다. FLOW1은 새 명령에서 이 경로를 쓰지 않기로 함(`FLOW1:171`) | 행 수 지표, PENDING 연령 지표가 REFERENCE를 포함하는지 확인(미확인) | ① 소비자 없는 범주는 기록하지 않음(FLOW1 방식 확대) ② 별도 상태 `NO_CONSUMER`로 구분 ③ SETTLEMENT 정책 이벤트 설계 때 일괄 결정 |
| DC-M-15 | P2 | master는 Modulith 모듈이 `merchant` 하나다. domain 88타입이 한 패키지에 있어 경계 검사가 사실상 의미가 없다(DOMAIN-ASSESSMENT §4.3 재확인) | `apps/master-api/src/main/java/.../provided/package-info.java`, `ls M/domain` 88, tasks ARCH-SUBPKG-MASTER BACKLOG | MASTER 구조, 새 도메인(계약 접수·KYC) 추가 시 | 중 | Business·계약 접수·MID 규칙이 한 패키지에서 서로 직접 참조한다. FLOW1이 같은 모듈에 더 넣는다(`FLOW1:374`) | ArchUnit 하위 패키지 의존 규칙 | ① ARCH-SUBPKG-MASTER를 FLOW 구현 전에 ② 하위 패키지만(모듈 분리 없이) ③ 현행 유지 |
| DC-M-16 | P3 | D-FLOW-001 ① 새 가맹점 관문("REGISTERED + 최신 접수 건 계약 완료")이 main에 없다. wip/flow-1 미병합 | `M/domain/Merchant.kt:91-96`, `DEC:247`, `FLOW1:227-240`, `git log main..wip/flow-1`(1커밋, 미검증) | 가맹점 등록 | 중 | 결정 뒤에도 계약 접수 없이 가맹점 등록이 된다(권리 효과는 없음) | FLOW-3 인수 시험(`MerchantRegistrationGate*Spec` 확장) | ① 계획대로 FLOW-1~3 ② RULE-GOV-001 관문 통과 후 재개(작업 카드 기록대로) |
| DC-M-17 | P3 | INACTIVE 처리가 불명확하다. ① REGISTERED→INACTIVE가 지침에는 전이로 있으나 BP1은 P1(F-011)로 미뤘다. 근거로 든 TBD-08은 원래 "재활성화"다 ② DB에 INACTIVE 행이 있으면 수정이 `check()` 결함(500 계열)으로 처리돼 업무 거절이 아니다 | `지침:86-87`, `POL:84-89,154`, `BP1:131,146,216`, `M/domain/Business.kt:60` | 사업자 관리 | 하 | 사용중지 기능을 기대하는 운영자 혼동. 이관·수동 데이터로 INACTIVE가 생기면 오류 응답 | 시험: INACTIVE 재구성 → updateProfile(미실행) | ① 사용중지 전이를 정책 질문으로 다시 제시 ② INACTIVE 수정 시 업무 거절 code로 ③ 현행 유지 + 지침에 "1차 미구현" 주석 |
| DC-M-18 | P3 | '계약' 용어가 겹친다. 사업자 계약 접수의 '계약 완료/계약 해지'(결제 무영향)와 가맹점 Contract의 '승인/철회'(결제 영향) | `FLOW1:151-156,175-181,200`, `DEC:30,249`, `M/domain/Contract.kt:12-14` | 관리자 화면, 운영 교육, 용어사전 | 하 | 운영자가 '계약 해지'로 결제가 멈췄다고 오해할 수 있다(FLOW1이 안내 문구로 일부 완화) | 화면 문구 검토, 용어사전 대조 | §4.3 '계약 용어' 대안 ①~③ |
| DC-M-19 | P4 | DD(도메인 정의서)가 2026-10-20 이후 갱신되지 않았다. Q-MD-08을 "미결"로 표기(이미 D-MD-016), Business 등록 상태·Site 심사·가맹점 코드·계약 접수가 빠져 있다 | `git log -- docs/repository/DOMAIN-DEFINITION.md`(b82e5d1, 2026-10-20), `DD:93-102,98` | 모든 신규 명세 작성자 | 하 | 색인 문서를 근거로 옛 규칙을 인용할 위험 | 문서 점검: 결정 ID 목록 대비 DD 등재 여부 | ① DD 갱신 작업 ② DD를 RULE-GOV-001 대장으로 대체 ③ DD 머리에 "기준일 2026-10-20" 경고 |
| DC-M-20 | P4 | MD1이 옛 내용이다. 제목 "1단계: 명세·질문 후보", §1 "관리자 web 만들지 않는다", §3.3 entitlement_revision 증가 지점 "셋" | `MD1:1,34,253` vs `M/application/MerchantOnboardingService.kt:133,404` 등 | MASTER 명세 독자 | 하 | 증가 지점을 문서대로 구현하면 Snapshot 무결성 충돌(MS1 review M-2가 막은 결함)이 다시 생길 수 있다 | 문서 대조 | ① MD1에 "현행은 MS1 §4.6" 주석 ② 증가 지점을 RULE 대장 한곳으로 |
| DC-M-21 | P4 | baseline 사업자 정책서·지침서가 그 뒤 결정(D-BIZ-002, D-FLOW-001 ④)으로 바뀐 부분을 표시하지 않는다 | `POL:12-16,39,54-71,93-95`, `지침:55,228` | 사업자 업무 명세 | 하 | baseline이 확정 정책 지위라 옛 문장이 권위로 인용될 수 있다 | BASELINE-INDEX 대체 표시 점검 | ① baseline 원문 보존 + BASELINE-INDEX에 "부분 대체" 표 ② baseline 말미에 변경 주석 |
| DC-M-22 | P4 | 작업 대장이 실제와 다르다. ① MERCHANT-BIZ-LOOKUP-001이 BACKLOG다. D-BIZ-004로 BUSINESS-PROFILE-001 DONE(archive 2026-11-22) 때 닫기로 했고, 인수 기준(권한 OPERATOR·평문 미노출)도 D-BIZ-006/007과 충돌한다 ② SITE-CONTRACT-CODE-001이 "로컬 브랜치 wip/scode-1"이라고 하나 로컬에는 없고 `origin/wip/scode-1`만 있다 | `harness/tasks.json`(두 카드), `harness/archive/tasks-done-2026-11-22.json`, `git branch -a` | 하네스 재개 판단 | 하 | 재개 때 닫힌 작업을 다시 하거나 브랜치를 못 찾는다 | tasks.json 상태 점검 | ① 카드 상태·경로 정정(Coordinator) ② 그대로 두고 RULE-GOV-001 정비 때 일괄 |
| DC-M-23 | P4 | Q-PII-3·Q-PII-5가 PII-MASKING-POLICY §8과 D-FLOW-002에서 참조되지만 `harness/questions.json`에 없다(Q-PII-1·4만 있음) | `docs/repository/PII-MASKING-POLICY.md:127,160,212,214`, `DEC:248` | 질문 추적·LIVE Gate | 하 | 미결 질문이 질문 대장에서 빠져 재개 때 놓친다 | questions.json ID 대조 | ① 질문 등록(PROPOSED) ② 문서 안 질문을 대장 기준으로 일원화 |
| DC-M-24 | P4 | `DisplayName` 생성자와 `BusinessTextRule`이 같은 규칙을 두 번 구현한다(DOMAIN-ASSESSMENT §5 재확인) | `M/domain/DisplayName.kt:6-21,30`, `BusinessTextRule.kt` | Business·Merchant 이름 | 하 | 한쪽만 고치면 parser 400과 Domain 거절이 어긋난다 | 같은 벡터로 두 경로 비교 시험 | ① 생성자가 `BusinessTextRule`을 쓰게 ② 현행 + 벡터 시험 |
| DC-M-25 | P4 | Site 채널 DB CHECK가 Domain 규칙보다 느슨하다(WEB에 `_`·연속 점 허용). 이중 방어가 부분적이다 | `SQL/V1:74-78` vs `M/domain/SiteChannel.kt:26-32` | Site 등록 | 하 | Domain을 우회한 쓰기(수동 SQL) 때만 문제. 앱 경로는 Domain이 막는다 | MasterSchemaSpec에 WEB `_` 삽입 시험(MASTER-LIFECYCLE-001 openItems nit-2와 같은 계열) | ① CHECK를 type별 정규식으로 강화(새 migration) ② 현행 유지(Domain 권위 명시) |

### 5.1 집계

| 우선순위 | 건수 | ID |
|---|---|---|
| P0 | 1 | DC-M-01 |
| P1 | 3 | DC-M-02, 03, 04 |
| P2 | 11 | DC-M-05 ~ 15 |
| P3 | 3 | DC-M-16, 17, 18 |
| P4 | 7 | DC-M-19 ~ 25 |
| 합계 | 25 | |

### 5.2 P0·P1 요약

- **DC-M-01(P0, 보안)**
  - 문제: 멱등 지문이 사업자등록번호·개인정보가 섞인 무염 SHA-256이다. 이미 결정된 HMAC 전환(D-MOPS-010 ②)이 적용되지 않았다.
  - 상태: LIVE Gate로 추적 중이다(MERCHANT-OPS-002 B3-3a). 새 결정은 필요 없고 **언제 적용할지**만 정하면 된다.
- **DC-M-02(P1, 상태 전이 충돌)**
  - 문제: 거절된 Site를 같은 채널로 새로 등록할 수 없다. D-MS-005와 D-MD-008이 맞물리고 Site 종료 경로가 없어서다.
  - 영향: 화면 안내("새 Site로 재신청")와 실제 동작이 다르다. **사용자 결정이 필요하다**(대안 4개, §4.3).
- **DC-M-03(P1, 결정 원장 충돌 표기)**
  - 문제: 마스킹 관련 옛 결정 4건에 변경 표시가 없다. 실제 정책은 D-PII-001(원문)로 하나로 정해져 있다.
  - 조치: 표기만 정리하면 된다(Coordinator 처리 가능, 결정 변경 없음).
- **DC-M-04(P1, 정책 공백)**
  - 문제: 한 가맹점에 유효 계약·같은 종류 CSA가 여럿일 수 있는지 정해지지 않았고, 코드는 무제한 허용한다.
  - 영향: CSA는 수수료·정산 정책 Assignment의 기준이라 금융 의미가 있다. **사용자 결정이 필요하다.**

---

## 6. 사용자 결정 필요 항목(Coordinator가 질문 등록 — 이 문서는 정하지 않는다)

| 후보 질문 | 관련 | 권장안 후보(제안일 뿐) | 막히는 작업 |
|---|---|---|---|
| 거절 Site를 같은 채널로 재신청하는 방법 | DC-M-02, D-MS-005·D-MD-008 | 거절 시 채널 점유 해제(유일성 판정에서 REJECTED 제외) 또는 Site 종료 명령 선행 | Site 심사 운영, MASTER-LIFECYCLE-001 |
| 한 Merchant의 동시 유효 계약·같은 종류 CSA 복수 허용 | DC-M-04 | 같은 Merchant·같은 서비스 종류의 기간 겹치는 유효 계약 금지(갱신은 Revision) | SETTLEMENT-CONTRACT-001, Q-MD-06 |
| CSA 추가를 승인 대상(2인 또는 Revision 내용)으로 올릴지 | DC-M-08 | 서비스 종류가 늘기 전까지 현행 유지, 확장 시 Revision 내용으로 | 서비스 종류 확장 |
| 한 Site를 여러 MID가 쓰는 조건 | DC-M-12, 구조 경계 §2 | 같은 Merchant + 별도 계약/기관 경로 증거 + 2인 | 추가 MID |
| 추가 MID·Default 교체(기존 Q-MD-06·07), 종료 채널 재사용(Q-MD-18) | DC-M-11·13 | 기존 질문 권장안 | MASTER-LIFECYCLE-001 |
| 사업자 사용중지(REGISTERED→INACTIVE) 시점·권한 | DC-M-17, TBD-08 | 별도 작업으로 분리, 그 전 INACTIVE 수정은 업무 거절 | 사업자 관리 |
| 사업자 '계약 해지'와 가맹점 계약 '철회' 용어 구분 | DC-M-18 | 화면 용어 분리 또는 고정 안내 | FLOW-2 화면 |
| MASTER-LIFECYCLE-001 착수 순서(차단 기록 먼저 여부) | DC-M-05·06·07 | 차단 기록 → Site 종료 → 연결 종료/교체 → 철회 | LIVE Gate |
| 멱등 지문 HMAC 전환 시점 | DC-M-01 | 결정 그대로, FLOW 구현 전 또는 LIVE 전 | LIVE Gate |

사용자 질문 없이 Coordinator가 처리할 수 있는 것(가역·문서): DC-M-03, 10, 14(기술 선택), 15, 19~25.

---

## 7. 미확인

- PAYMENT 쪽 판정: "PRIMARY 없이 ADDITIONAL만 → 신규 결제 차단"(D-MD-007), MID TERMINATED → MERCHANT_SUSPENDED(D-MS-002 (c)). 이번 범위는 master만이라 확인하지 않았다.
- PENDING 연령 지표(`EntitlementPropagationGauges`)가 `INTEGRATION_MASTER_REFERENCE`를 포함하는지 확인하지 않았다.
- DC-M-02·04·08·17 재현은 코드 판독 기준이다. **시험을 실행하지 않았다.**
- ADMIN(internal-admin) 쪽의 같은 규칙 이중 검사(사업자 관문·Site 재신청 화면 동작)는 보지 않았다.
- `wip/flow-1`·`origin/wip/scode-1`의 내용 정합성은 보지 않았다(main 미병합).
