# GOV-BIZ-001 — 사업자(Business) 등록·조회·수정 기능 정합성 확인

- 작업: GOV-BIZ-001(정상화 작업 1번, 분석만). 작성일 2026-11-25. **rev 0.1a**(같은 날 — 독립 리뷰 CHANGES_REQUIRED MAJOR 3·MINOR 7 반영, 개정 이력 §8).
- **rev 0.1b**(2026-11-25, BIZ-NORM-003 — 사실 정정 표시만): 진단 뒤 시행으로 사실이 바뀐 곳에 **'→ 현행(커밋 …)'** 표시를 달았다. 진단 본문(기준 `7d521fd`의 사실)은 지우지 않았다. §5 최소 수정의 시행 상태: M-1·M-2 = SEC-BIZ-001(`439fa1a`, DONE), M-3·M-6·M-7 = BIZ-NORM-002(`bdf4e42`, DONE), M-4·M-5·M-8 = BIZ-NORM-003(문서 — 리뷰·커밋 전).
- **기준 커밋 `7d521fd`**(main). 작업 트리의 `harness/tasks.json` 미커밋 변경은 이 분석의 대상이 아니다.
- 이 문서는 **진단**이다. 소스·DB·시험·결정·작업 상태·다른 문서는 바꾸지 않았고, 정책을 새로 정하거나 바꾸지 않았다.
- **시험은 실행하지 않았다.** 시험 파일이 있는지와 이름만 확인했다. "검증 방법"은 제안이다.
- 표기: **[사실]** = 파일:줄로 확인. **[해석]** = 사실에서 끌어낸 판단. **미확인** = 확인 못 함.

## 0. 범위·제외·참조

### 0.1 범위

| 구분 | 포함 |
|---|---|
| 등록 | ① 사업자등록번호 확인 먼저(1단계) ② 중복 안내 ③ 입력·저장 — DRAFT(임시저장) 또는 REGISTERED ④ DRAFT → REGISTERED(등록 완료) |
| 조회 | 목록 검색, 번호 정확 조회, 상세(열람 감사), 변경 이력, '연결 가맹점' 탭 |
| 수정 | 항상 편집 폼(D-ADMIN-UI-011 ②), 중요정보 사유, version 충돌, 결과 불명 잠금 |
| 연결 관계만 | 상세에 붙은 '계약 진행' 읽기 섹션(MERCHANT-FLOW-001 FLOW-1, `cf86c67`). 사업자 상세 → 가맹점 등록 마법사로 넘기는 경로 |

### 0.2 제외

- MERCHANT-FLOW-001 FLOW-2 이후: 접수 건 생성·전이, KYC, 담보, 리스크 표시, 영업대행사, 메모, 원문 조회.
- 사업자 생명주기 전이(TERMINATED·INACTIVE). D-GOV-005 ②로 생명주기 정비 순서 뒤로 미뤄졌다. 다만 **INACTIVE 행을 수정할 때 무슨 일이 생기는지**는 수정 기능의 동작이라 넣었다(§4 G-03).
- 가맹점·결제 등 다른 도메인. 사업자 기능을 직접 막는 선행 조건만 본다(새 가맹점 = REGISTERED 관문).

### 0.3 사용한 기존 진단(ID로만 참조, 내용 반복 없음)

| 문서(기준 `41139db`) | 이 문서에서 참조한 ID |
|---|---|
| [DOMAIN-CONSISTENCY-1-MASTER.md](DOMAIN-CONSISTENCY-1-MASTER.md) | DC-M-01, 03, 14, 16, 17, 21, 22, 23, 24, §1·§4.1(정정) |
| [ADMIN-FEATURE-INVENTORY.md](ADMIN-FEATURE-INVENTORY.md) | ADM-F-BIZ-01~06, ADM-F-MER-13, §5-3 #1, §5-4 #1 |
| [ADMIN-UI-DIAGNOSIS.md](ADMIN-UI-DIAGNOSIS.md) | UI-07, 10, 12, 16, 19, 26 |
| [SOURCE-CONVENTION-REVIEW.md](SOURCE-CONVENTION-REVIEW.md) | SC-07, SC-19 |
| [POLICY-CONFLICTS.md](POLICY-CONFLICTS.md) | PC-12, 13, 36, 47, 52, 55, 59, 60, 71 |
| [NORMALIZATION-PLAN.md](NORMALIZATION-PLAN.md) | NP-01, 04, 06, 68, 92, 102, 103, 104 / U-01, U-09(`:338` 권장안 원문), U-11, U-12 |
| [docs/rules/BIZ.md](../rules/BIZ.md) | BR-BIZ-001~049, 279, 304~306, 진단 (a)~(f) |

### 0.4 기존 진단 이후 달라진 사실(`41139db` → `7d521fd`)

| 기존 진단 | 지금 | 근거 |
|---|---|---|
| INV §5-4 #1 "FE 전체에 '접수' 0건", DC-M §2 "계약 접수 main 코드 없음", NORMALIZATION-PLAN §1 "문서만(main)" | **FLOW-1이 main에 병합됐다.** 사업자 상세에 읽기 전용 '계약 진행' 섹션이 생겼다. FLOW-2+는 여전히 없다 | `cf86c67`. A/`application/BusinessQueryService.kt:83-97`, M/`adapter/web/admin/BusinessQueryController.kt:53-58`, SQL/`V19__contract_intake.sql`, FE/`screens/BusinessContractProgressSection.tsx:16`, FE/`screens/BusinessesScreen.tsx:507` |
| PC-12·13(HMAC·카드번호 적용 시점), PC-36(INACTIVE), PC-52(마스킹 역참조 표기), PC-55(baseline 표시), PC-71(Coordinator 해석) | 정책 판단은 **D-GOV-001~006으로 결정됐다.** 남은 것은 시행이다 | `docs/DECISIONS.md:256-261` |

경로 약어

- `FE/` = `apps/internal-admin/frontend/src/`
- `A/` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/merchantops/`
- `M/` = `apps/master-api/src/main/kotlin/com/paycore/master/merchant/`
- `SQL/` = `apps/master-api/src/main/resources/db/migration/master/`
- `PRIV/` = `apps/master-api/src/main/resources/db/runtime-privilege/master/`
- `DEC` = `docs/DECISIONS.md`
- `BP1` = `docs/repository/BUSINESS-PROFILE-001-BUSINESS-PROFILE.md`

---

## 1. 현재 제공 기능과 화면별 업무 흐름

### 1.1 기능 목록(11개)

모두 **코드 있음 + 시험 파일 있음 + 시험 미실행** 상태다. "완성"은 실행 검증이 아니다.

| ID | 기능 | INV ID | 구현 근거 |
|---|---|---|---|
| F-01 | 등록 1단계: 사업자등록번호 확인(이미 있으면 기존 사업자 안내·이동) | BIZ-05 | FE/`screens/BusinessProfileFormScreen.tsx:262-301`(번호 조회 `:277`), 결과 `:366-378` |
| F-02 | 등록 2단계: 입력 → 임시저장(DRAFT) / 등록 완료(REGISTERED)로 생성 | BIZ-05 | FE/`BusinessProfileFormScreen.tsx:222-245`, 호출 `:185`. A/`application/BusinessProfileService.kt:53-109`. M/`application/BusinessProfileService.kt:54-74` |
| F-03 | 목록 검색(상호 앞부분·구분·등록 상태·등록일 기간, keyset 다음 쪽) | BIZ-01 | FE/`screens/BusinessesScreen.tsx:149`, A/`application/BusinessQueryService.kt:42-47` |
| F-04 | 번호 정확 조회(0·1건, 매 시도 감사) | BIZ-01 | FE/`BusinessesScreen.tsx:204`, A/`application/BusinessLookupService.kt:37-68` |
| F-05 | 상세 열람(개인정보 원문, 반환 전 감사) | BIZ-02 | FE/`BusinessesScreen.tsx:168`, A/`BusinessQueryService.kt:52-69` |
| F-06 | 상세 수정(항상 편집 폼 + 하단 저장, 중요정보 사유, version, 결과 불명 잠금) | BIZ-02 | FE/`BusinessesScreen.tsx:460-489`(OPERATOR만 편집 폼, 아니면 `DetailInfo`), FE/`BusinessProfileFormScreen.tsx:160-162,187,193,701-720`. M/`domain/Business.kt:49-67` |
| F-07 | 등록 완료(DRAFT → REGISTERED) | BIZ-03 | FE/`BusinessProfileFormScreen.tsx:187`, M/`domain/Business.kt:74-92` |
| F-08 | 변경 이력(마스킹 값, '더 보기') | BIZ-02 | FE/`BusinessesScreen.tsx:259`, A/`BusinessQueryService.kt:71-81` |
| F-09 | '연결 가맹점' 탭 → 가맹점 상세로 이동 | BIZ-04 | FE/`BusinessesScreen.tsx:277,491-502`, App 연결 FE/`App.tsx:302-305` |
| F-10 | '이 사업자로 가맹점 등록'(REGISTERED만 → 가맹점 등록 마법사 ①로 넘김) | BIZ-06 | FE/`BusinessesScreen.tsx:383-389`, FE/`App.tsx:194-196` |
| F-11 | '계약 진행' 읽기 섹션(**연결 관계만**, FLOW-1) | (INV 이후 추가) | A/`BusinessQueryService.kt:87-97`, FE/`BusinessesScreen.tsx:507` |

### 1.2 화면 → 단계 → 호출 API

메뉴는 '사업자·가맹점'이고 탭은 '가맹점'·'사업자' 두 개다(D-ADMIN-UI-006, FE/`navigation.ts:93-95`). 사업자 등록은 탭이 아니라 하위 화면이다. 상세 선택은 App 메모리에만 두고 URL·브라우저 저장소에는 두지 않는다(FE/`App.tsx:101-102`).

#### A. 사업자 목록 — 탭 '사업자'(`page = 'businesses'`, FE/`App.tsx:292-307`)

| 단계 | 화면 동작 | ADMIN API(FE 호출) | MASTER 내부 API(A/`adapter/integration/HttpMasterBusinessGateway.kt`) |
|---|---|---|---|
| 1 | 조건 입력 → 조회 / '더 보기' | `POST /api/businesses/searches`(FE/`api/adminApi.ts:1136`) | `POST /internal/v1/admin/businesses/searches`(`:60`) |
| 2 | 번호 칸 입력(다른 조건 비활성) → 조회. 보낸 즉시 입력란을 비운다(FE/`BusinessesScreen.tsx:199-200`) | `POST /api/businesses/registration-number-lookups`(`adminApi.ts:1137`) | `POST …/registration-number-lookups`(`:66`) |
| 3 | 행 '상세' → B | — | — |
| 4 | '＋ 사업자 등록' → C(OPERATOR만, FE/`BusinessesScreen.tsx:532`) | — | — |

#### B. 사업자 상세 — 같은 탭 안 화면 전환

| 단계 | 화면 동작 | ADMIN API | MASTER 내부 API |
|---|---|---|---|
| 1 | 상세 열기 | `GET /api/businesses/{id}`(`adminApi.ts:1138`) | `GET …/{id}`(`:71`) + 찾았을 때만 `GET …/{id}/contract-intakes`(`:76`, 같은 열람 처리 안에서 — A/`BusinessQueryService.kt:65,91`) |
| 2 | 기본정보 탭: OPERATOR = 편집 폼, 그 밖 = 읽기 표(FE/`BusinessesScreen.tsx:460-489`) | — | — |
| 3 | 저장(DRAFT 임시저장·REGISTERED 저장) | `PUT /api/businesses/{id}`(`adminApi.ts:1141`) | `POST …/commands/update`(`HttpMasterBusinessGateway.kt:91`) |
| 4 | 등록 완료(DRAFT만) | `POST /api/businesses/{id}/registration`(`adminApi.ts:1142`) | `POST …/commands/complete-registration` |
| 5 | '연결 가맹점 (n)' 탭 | `POST /api/merchant-searches` `{businessId}`(FE/`BusinessesScreen.tsx:277`) | 가맹점 검색(가맹점 Gateway) |
| 6 | '변경 이력' 탭 / 더 보기 | `GET /api/businesses/{id}/history?after=`(`adminApi.ts:1139`) | `GET …/{id}/history/after/{seq}`(`:81`) |
| 7 | '이 사업자로 가맹점 등록'(REGISTERED·OPERATOR) | 없음(메모리로 넘김) | — |
| 8 | '계약 진행' 섹션(읽기) | 1단계 응답의 `contractProgress` | 1단계와 같음 |

#### C. 사업자 등록 — 하위 화면(`page = 'businessRegistration'`, FE/`App.tsx:308-311`)

진입점은 목록의 '＋ 사업자 등록'과 운영 홈의 '＋ 사업자 등록'(FE/`screens/HomeScreen.tsx:296`) 두 곳이다.

| 단계 | 화면 동작 | ADMIN API | MASTER 내부 API |
|---|---|---|---|
| 1 | 번호 입력 → 확인. 보낸 즉시 입력란을 비운다(FE/`BusinessProfileFormScreen.tsx:272-273`) | `POST /api/businesses/registration-number-lookups` | `POST …/registration-number-lookups` |
| 1a | 이미 있음 → "이미 등록한 사업자입니다" + 상호·원문 번호·등록 상태 + '정보 보기'(`:366-375`) → B | — | — |
| 2 | 없음 → 입력 폼(확인한 번호는 화면 상태에만, `:288`) → 임시저장 / 등록 완료 | `POST /api/businesses`(`adminApi.ts:1140`) | `POST …/commands/create` |
| 2a | 저장 때 중복(확인과 저장 사이 경합 — BP1 L-12) → 거절 + 기존 사업자 요약·'기존 사업자 상세 보기'(`:694-699`) | (같은 응답의 `existing`) | MASTER가 중복 요약을 덧붙임(M/`adapter/web/admin/BusinessCommandController.kt:107-110`) |
| 3 | 성공 → 상세 B를 다시 읽는다(`onSaved` → FE/`App.tsx:189-192`) | `GET /api/businesses/{id}` | — |

### 1.3 역할별 차이

권한 판정은 서버 Use Case가 한다. 화면은 표시만 한다.

| 역할 | 메뉴(FE/`navigation.ts:57,60`) | 목록·이력 | 상세·번호 조회 | 생성·수정·등록 완료 |
|---|---|---|---|---|
| OPERATOR | 사업자 탭 + 등록 화면 | 허용 | 허용(감사) | 허용(A/`application/BusinessPermissions.kt:13-19`) |
| APPROVER | 사업자 탭만 | 허용 | 허용(감사). 상세는 읽기 표(편집 폼·명령 버튼 없음 — FE/`App.tsx:296` `canEdit`) | 403 + 감사 `FORBIDDEN`(A/`BusinessProfileService.kt:63`) |
| VIEWER·SECURITY_ADMIN | 사업자 메뉴 없음 | 403, **감사 없음**(A/`BusinessQueryService.kt:43,72,100`) | 403 + **감사 `FORBIDDEN`**(앞단 `FRONT` 통과 후 VIEW 불일치 — A/`BusinessPermissions.kt:25`, `BusinessQueryService.kt:58`, `BusinessLookupService.kt:45`) | 403 + 감사 |
| 업무 역할 없음·비활성 | — | 403, 감사 없음(A/`application/BusinessAudit.kt:22-23`) | 같음 | 같음 |

[사실] MASTER 내부 API에는 역할 판정이 없다. ADMIN_SERVICE 서명 채널을 신뢰한다(D-MOPS-005 — 키 유출 위험은 수용으로 기록, BP1 L-1).

---

## 2. 적용 정책

### 2.1 확정

"확정"은 근거가 사용자 답이거나 baseline 확정 문서라는 뜻이다(BIZ 대장 머리 정의).

| 규칙 | 내용(요약) | 근거 결정 |
|---|---|---|
| BR-BIZ-002·003·004 | 번호 10자리 숫자만(정규화 없음), 번호당 사업자 하나(동시 요청 포함), 저장 뒤 변경 불가 | D-MD-008, D-BIZ-005 |
| BR-BIZ-005·006·012 | 등록정보 1:1, 법인번호로 자동 병합 안 함, 국내(KR)만 | POL §1-3·§1-4·§1-6 |
| BR-BIZ-007·008 | 생명주기와 등록 상태는 별도 축. REGISTERED는 진위확인·AML/KYB·계약 승인이 아님 | D-BIZ-001 |
| BR-BIZ-009·010·011 | DRAFT 최소 = 구분·번호·상호. REGISTERED 필수 항목. 법인은 법인등록번호 필수 | D-BIZ-002, POL §5 |
| BR-BIZ-013 | 진위확인은 NOT_CHECKED만 | POL POL-002·005(TBD-06) |
| BR-BIZ-014·015 | 전이 = 생성 → DRAFT/REGISTERED, 같은 상태 수정, DRAFT → REGISTERED만. INACTIVE 전이·재활성화는 만들지 않음 | DEV §4, TBD-08 |
| BR-BIZ-016 | 중요정보 변경은 사유 필수 + 전후 이력 | POL POL-004 |
| BR-BIZ-019·020·021 | version 충돌 거절, 물리 삭제 없음, 사업자 명령은 가맹점·계약·MID·권리에 효과 없음 | POL POL-004·006, DEV §8 |
| BR-BIZ-022 | 새 가맹점은 REGISTERED 사업자만(기존 가맹점 후속 명령은 무영향) | D-BIZ-001 |
| BR-BIZ-030·032·033·034 | 관리자 화면·조회 응답은 번호 원문. 감사·이력·로그·지표는 마스킹. Outbox에 번호 없음. 번호를 식별자로 쓰지 않음(내부 키는 화면 미표시) | D-BIZ-007, D-PII-001 ①, D-MD-008, D-BIZLOOKUP-001 |
| BR-BIZ-035~038·040~044 | 번호 조회는 OPERATOR·APPROVER, 매 시도 감사, oracle 취급, 공유 버킷. 상세 열람 감사 + 속도 제한, 목록·이력은 감사 없음. 조회 = OPERATOR·APPROVER, 명령 = OPERATOR. 중복이면 조회 권한자에게 기존 사업자 안내 | D-MOPS-003, D-BIZ-003·006, D-PII-001 ② |
| BR-BIZ-048 | 사업자 명령 지문의 번호 자리는 MASTER 비밀 키 HMAC | D-MOPS-010 ②, **적용 시점·범위 확장 D-GOV-001 ①②** |
| BR-BIZ-049 | 번호 마스킹 해제 절차 없음 | D-PII-001 ③ |
| BR-BIZ-236·237 | 1인 등록 명령 감사 2회(시도·결과), 같은 키·같은 내용은 재생, 다른 내용은 충돌 | D-MOPS-006 ②④, CLAUDE.md |
| BR-BIZ-304·305 | 공통 '사유 및 비고' 칸 하나(필수 항목이 있을 때만 필수), **칸 전체 카드번호 패턴 거부(기존 사업자 중요정보 사유 포함, 새 저장부터)** | D-FLOW-006, D-FLOW-007 ①, **시점 D-GOV-001 ①** |
| (화면) | 등록은 번호 확인 먼저, 상세는 항상 편집 폼 + 하단 저장(저장 규칙 불변) | D-ADMIN-UI-011 ①② |
| (화면) | 사업자 상세 '연결 가맹점' 탭, 번호 조회 결과는 짧은 모양, 늘 같은 값(진위확인·생명주기) 숨김 | D-ADMIN-UI-010 ②⑦⑧ |
| (화면) | 탭 2개 + 목록의 '＋ 등록' 버튼(권한 불변) | D-ADMIN-UI-006 |
| (생명주기) | 생명주기 기능은 정비 후 차단 기록 → Site 종료 → 연결 종료/교체 → 철회 순. **사업자 사용중지는 그때까지 업무 거절**(답한 질문의 권장안 원문: "그 전 INACTIVE 수정은 업무 거절" — NORMALIZATION-PLAN.md:338 U-09) | D-GOV-005 ② |
| (관리체계) | 대장(`docs/rules`)이 현재 유효 규칙의 정본. baseline은 원문 보존 + BASELINE-INDEX 대체 표 | D-GOV-006 ②④ |

### 2.2 미확정(대장 '제안'·질문·TBD)

| ID | 내용 | 상태 | 현재 코드 동작 |
|---|---|---|---|
| BR-BIZ-017 | 사업체 구분 변경, REGISTERED 대표자 추가만 하는 변경도 중요정보로 본다 | **제안**(BP1 C-12·M-2). D-GOV-005 ④ 일괄 확인 대상 | 그렇게 동작한다(M/`domain/BusinessProfileChange.kt:47,77`) |
| BR-BIZ-018 | 중요정보 변경은 OPERATOR 1인 + 사유 + 이력(2인·재검증 없음) | **제안**(TBD-07 기본값). D-GOV-005 ④ 일괄 확인 대상 | 1인이다(A/`BusinessPermissions.kt:16`) |
| BR-BIZ-023 / Q-PII-7 | 상호(개인 이름 포함 가능)를 마스킹하지 않는다 | 미결. **questions.json 미등록**(DC-M-23, PC-47) **→ 현행(BIZ-NORM-003): questions.json PROPOSED 등록(답 없음)** | 마스킹하지 않는다 |
| BR-BIZ-024 / Q-PII-5 | 사업자 개인정보 보관·파기 기간 | 미결. **questions.json 미등록** **→ 현행(BIZ-NORM-003): questions.json PROPOSED 등록(답 없음)** | 파기 기능 없음, 무기한 보관(BP1 L-3) |
| TBD-01·03 | 본점·지점 연결, 복수 등록정보 | 후속 | 없음 |
| TBD-04 | 번호 정정·변경 | D-BIZ-005로 TBD 유지 | 변경 불가 |
| TBD-05 | DRAFT 만료·삭제 | 최소 입력만 D-BIZ-002로 해소, 나머지 TBD | 없음 |
| TBD-06 | 진위·영업상태 연동 | 후속 | NOT_CHECKED만 |
| TBD-08 / PC-36 | INACTIVE 전이·재활성화 | D-GOV-005 ②로 **시점만** 결정(생명주기 정비 뒤). 권한·재활성화 규칙은 그때 | 전이 없음. INACTIVE 행의 수정(update)은 결함 처리(§4 G-03), 등록 완료는 이미 저장된 거절 `BUSINESS_NOT_DRAFT`(`Business.kt:85`) **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): 수정은 저장된 업무 거절 `BUSINESS_INACTIVE`(M/`domain/Business.kt:61`, BR-BIZ-311 — REJECTED 저장·같은 키 재생, 이력·Outbox 0), 화면은 편집 폼 대신 읽기 표시 + 안내. 등록 완료 `BUSINESS_NOT_DRAFT`는 그대로. 전이 기능 없음도 그대로** |

---

## 3. 계층 책임과 연결

### 3.1 호출 연결(수정 명령 기준 — 생성·등록 완료도 같은 틀)

| 계층 | 책임 | 연결 file:line |
|---|---|---|
| 화면 | 입력 복제 검사(서버가 권위), 중요정보 감지 → 사유 칸, version 동봉, 결과 불명 잠금 | FE/`BusinessProfileFormScreen.tsx:160-162,222-245`, `lib/businessProfile.ts:205,378-389`, `lib/useUnknownLockedSubmission.ts:15` |
| ADMIN API | 415·413 읽기 전 거절(감사 없음), 엄격 해석 | A/`adapter/web/BusinessCommandController.kt:43-53`, `BusinessProfileBodyParser.kt` |
| ADMIN Application | 앞단 → 권한 → 형식(MASTER 규칙 복제) → 창·버킷 → 시도 감사 → MASTER(TX 밖) → 결과 감사. reference 불일치는 결과 불명 | A/`application/BusinessProfileService.kt:53-109,180-204`, `BusinessProfileInputRules.kt:56-73` |
| ADMIN → MASTER | 서명, 쓰기 permit, timeout·모양 위반은 Unknown, TX 안 호출 금지 | A/`HttpMasterBusinessGateway.kt:84-111` |
| MASTER API | permit, 엄격 해석(400 REQUEST_MALFORMED·BUSINESS_VALIDATION_FAILED), 중복 요약 덧붙이기, 기술 실패는 503 | M/`adapter/web/admin/BusinessCommandController.kt:61-124`, `BusinessProfileRequestParser.kt:93-121` |
| MASTER Application | 실행기 한 로컬 TX(멱등 `admin_request` + 업무 변경 + 이력 + Outbox), 번호 사전 조회, 행 잠금 | M/`application/BusinessProfileService.kt:54-117`, `AdminCommandExecutor.kt:133-150,420-428` |
| Domain | 생명주기 → 상태 → version → 변경 계산·사유 → 규칙 재검사. 번호 불변(전이 인자 없음) | M/`domain/Business.kt:49-106`, `BusinessProfileRules.kt:17-92`, `BusinessProfileChange.kt:32-96` |
| DB | UNIQUE·CHECK, `@Version`, runtime 권한(DELETE 없음) | SQL/`V1__master_core.sql:20-35`, SQL/`V15__business_profile.sql:29-35`, PRIV/`V16__business_profile_privileges.sql`, PRIV/`V2__master_runtime_privileges.sql:13` |

### 3.2 규칙별 강제 위치

○ = 강제(거절), △ = 안내만 또는 부분, — = 없음.

| 규칙 | 화면 | ADMIN App | MASTER App·API | Domain | DB |
|---|---|---|---|---|---|
| 조회 OP·AP / 명령 OP(BR-BIZ-041) | △ 표시(`navigation.ts:57,60`, `App.tsx:296`) | ○(`BusinessPermissions.kt:10-19`) | — 서명 채널 신뢰 | — | — |
| 번호 10자리(BR-BIZ-002) | △(`lib/businessProfile.ts:208`) | ○(`BusinessProfileInputRules.kt:62`, `BusinessAudit.kt:52`) | ○(`BusinessProfileRequestParser.kt:100`) | ○(`BusinessRegistrationNumber.kt:27`) | ○ `ck_business_registration_number`(V1:33) |
| 번호 유일(BR-BIZ-003) | △ 1단계 안내(`BusinessProfileFormScreen.tsx:277`) | 중복 결과 전달·감사(`BusinessProfileService.kt:93,195`) | ○ 사전 조회(`BusinessProfileService.kt:57-59`) + 1062 재판정 | — | ○ `uk_business_registration_number`(V1:31) — **이중** |
| 번호 불변(BR-BIZ-004) | △ 수정 모드 읽기 전용 | ○ 수정 본문에 번호 없음(`BusinessProfileService.kt:148-152`) | ○ UPDATE 키에 번호 없음(`BusinessProfileRequestParser.kt:79`) | ○ 전이 메서드에 번호 인자 없음(`Business.kt:49-92`) | **—** 표 단위 UPDATE 권한(PRIV/V2:13), 열 권한·trigger 없음. JPA `updatable = false`(`JpaBusinessEntity.kt:28`) + 시험(`JpaBusinessRepositorySpec.kt:159`)으로만 막음 |
| DRAFT 최소 / REGISTERED 필수(BR-BIZ-009·010) | △ `validateForm` | ○ 복제(`BusinessProfileInputRules.kt:117-160`) | ○ 해석 직후 400(C-16) | ○ 생성·전이 안 재검사(`BusinessProfileRules.kt:30-84,89-92`) | △ 구분만(`ck_business_type_required` V15:34), 등록정보 형식 CHECK(V15 `ck_breg_*`) |
| 전이 제한(BR-BIZ-014) | △ 버튼 | △ 목표 상태 계산(`BusinessProfileInputRules.kt:67-70`) — **수정(update)은 잠근 행 상태를 모르므로 REGISTERED 수정도 목표 DRAFT로 검사**(`:69`, 주석 `:14-15`) | — | ○ `BUSINESS_NOT_DRAFT`(`Business.kt:85`) | △ 값만(`ck_business_reg_status` V15:33) |
| INACTIVE 수정 불가(D-GOV-005 ②, 보조 BR-BIZ-015) | **— 편집 폼이 열린다**(`BusinessesScreen.tsx:322`) **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): △ 읽기 표시 + 안내(`BusinessesScreen.tsx:322-324,394`)** | — | — | 수정 = **결함 처리** `check()`(`Business.kt:60`) / 등록 완료 = ○ 저장된 거절 `BUSINESS_NOT_DRAFT`(`Business.kt:85`) **→ 현행: 수정 = ○ 저장된 거절 `BUSINESS_INACTIVE`(`Business.kt:61`, BR-BIZ-311)** | △ 값만 |
| 중요정보 사유(BR-BIZ-016) | △ 감지·칸 표시(`lib/businessProfile.ts:378-389`) | △ 길이·문자만(`BusinessProfileInputRules.kt:63`) | △ 형식만(`BusinessProfileRequestParser.kt:109,118`) | ○ `BUSINESS_CHANGE_REASON_REQUIRED`(`Business.kt:104-106`, 판정 `BusinessProfileChange.kt:46-79`) | — |
| 사유 카드번호 거부(BR-BIZ-305) | **—**(`lib/businessProfile.ts:205`) **→ 현행(커밋 `439fa1a`, SEC-BIZ-001): ○(`lib/businessProfile.ts:209,212`)** | **—**(`BusinessProfileInputRules.kt:163-168`) **→ 현행: ○ MASTER 호출 전 400 `CARD_NUMBER_PATTERN`(`:177`)** | **—** **→ 현행: ○ 400 `CARD_NUMBER_PATTERN`(`BusinessProfileRequestParser.kt:109,118`)** | **—**(`BusinessChangeReason.kt:18` → `BusinessTextRule.kt:8-14`) **→ 현행: ○(`BusinessChangeReason.kt:20`, `CardNumberPattern`)** | — |
| version 충돌(BR-BIZ-019) | △ 보낸 version·'다시 불러오기'(`BusinessProfileFormScreen.tsx:243,701`) | 전달 | ○ 행 잠금(`lockWithProfile`) | ○ `BUSINESS_VERSION_CONFLICT`(`Business.kt:100-102`, 저장된 거절) | ○ `@Version` + 명시 비교(`JpaBusinessRepository.kt:79,117-119`) — **이중** |
| 멱등·충돌(BR-BIZ-237) | ○ 같은 submissionId 재전송(`useUnknownLockedSubmission.ts`) | ○ 키 `mo1.<계정>.<submissionId>`(`BusinessProfileService.kt:141`) | ○ 지문 비교(`AdminCommandExecutor.kt:420-428`) | — | ○ `admin_request` |
| 지문 HMAC(BR-BIZ-048) | — | — | **—** 무염 SHA-256(`AdminCommandExecutor.kt:427-428`, 입력 `BusinessCommandFingerprintFields.kt:16-41`) **→ 현행(커밋 `439fa1a`, SEC-BIZ-001): ○ 지문 전체 `h1:` 비밀 키 HMAC(`AdminCommandExecutor.kt:72-75`), 기존 `m1:` 비교는 Demo 한정** | — | — |
| 결과 불명(외부 호출 TX 밖·불명 단정 금지) | ○ 잠금·재전송만(`BusinessProfileFormScreen.tsx:708-720`) | ○ timeout·모양 위반·reference 불일치 → 불명(`HttpMasterBusinessGateway.kt:93,103`, `BusinessProfileService.kt:180-183`), TX 안 호출 금지(`:85,139`) | ○ commit 불명 → OUTCOME_UNKNOWN(`AdminCommandExecutor.kt:139-142`) | — | — |
| 감사(BR-BIZ-036·040·236) | — | ○ 상세 반환 전(`BusinessQueryService.kt:55-67`), 번호 조회 매 시도(`BusinessLookupService.kt:40-67`), 명령 시도·결과(`BusinessProfileService.kt:81,94`), 감사 실패 = 결과 미반환 | 이력 `master_change_history`(M/`BusinessProfileService.kt:102-117`) | — | — |
| 번호 마스킹(감사·이력·로그)·Outbox 제외(BR-BIZ-032·033) | — | ○(`BusinessAudit.kt:56-57`) | ○ 이력 `registrationNumberMasked`, payload에 번호 없음(M/`BusinessProfileService.kt:104-112`) | ○ `masked()`·`toString`(`BusinessRegistrationNumber.kt:15-17`) | — |
| 번호 원문 응답(BR-BIZ-030) | ○ 하이픈 표시(`BusinessesScreen.tsx:597,606`) | 그대로 전달 | ○(`JooqBusinessDetailReader.kt:35`, `JooqBusinessLookupReader.kt:21`) | — | — |
| 속도 제한(BR-BIZ-038·042) | — | ○ 조회 창(`BusinessReadWindow.kt`), 공유 번호 버킷(`RegistrationNumberBudget.kt`), 등록 창(`BusinessProfileService.kt:51,67-78`) — 인스턴스 메모리(L-9) | — | — | — |
| 새 가맹점 = REGISTERED(BR-BIZ-022) | △ 선택 불가·버튼 숨김(`MerchantRegistrationScreen.tsx:346-350`, `BusinessesScreen.tsx:383`) | — | 사업자 행 잠금(`MerchantOnboardingService.kt:99`) | ○(`Merchant.kt:91-93`) | — |
| 물리 삭제 없음(BR-BIZ-020) | — | — | — | — | ○ DELETE 권한 없음(PRIV/V2:13, V16) |
| 진위확인 NOT_CHECKED(BR-BIZ-013) | — | — | — | ○(`Business.kt:152`) | ○ `ck_business_verification`(V15:35) — **이중** |

[해석] 업무 판정의 권위는 MASTER Domain이다. ADMIN·화면은 즉시 안내용 복제다(BP1 C-10). 복제가 어긋나면 MASTER 400 `BUSINESS_VALIDATION_FAILED` → 지표 `business_rule_drift`로 드러난다(A/`BusinessProfileService.kt:98-101`).

[사실] 위 "목표 DRAFT 검사" 때문에 REGISTERED 사업자 수정에서 필수값을 지운 요청은 ADMIN 형식 검사를 통과한다. 그 뒤 MASTER Domain 재검사가 400 `BUSINESS_VALIDATION_FAILED`를 낸다(M/`BusinessCommandController.kt:80-82`). 그러면 ADMIN이 이 400을 복제 규칙 어긋남으로 세어 `ruleDrift` 지표와 WARN 로그를 남긴다(A/`BusinessProfileService.kt:98-100`). 화면은 REGISTERED 수정을 목표 REGISTERED로 미리 막으므로(FE/`BusinessProfileFormScreen.tsx:227`) 이 경로는 화면을 우회한 요청에서만 생긴다.
- [사실] BP1 C-16 보충(BIZ-1 리뷰 m-1, BP1:666)은 이 400 경로 자체(효과 없음 확정)를 수용했다.
- **미확인**: 이 경우가 drift 지표에 잡히는 오탐을 함께 수용했는지는 확인하지 못했다. 지표 의미 문제라 집계 행으로 올리지 않았다.

---

## 4. 코드 ≠ 정책 불일치

기존 진단 ID가 있는 행은 **새로 확인한 사실만** 적는다. 우선순위는 지침 정의를 따른다(P0 금융·보안·데이터 손상, P1 정책 충돌·상태 전이, P2 경계·아키텍처, P3 관리자 기능·UI, P4 문서·명명).

| ID | P | 불일치 | 근거(정책 ↔ file:line) | 기존 ID | 영향 |
|---|---|---|---|---|---|
| G-01 | P0 | **사유 칸 카드번호 패턴 거부가 세 계층 모두에 없다.** 새로 확인한 사실 ① 화면·ADMIN·MASTER 세 복제본이 모두 길이·문자 규칙만 본다 ② 같은 사유 원문이 수정 명령 지문 입력에 들어간다 ③ 같은 원문이 감사 없는 이력 API로 조회 역할에 보인다 | BR-BIZ-305(D-FLOW-007 ①, 시점 D-GOV-001 ①) ↔ FE/`lib/businessProfile.ts:205`, A/`BusinessProfileInputRules.kt:63,163-168`, M/`domain/BusinessChangeReason.kt:18`. 지문 M/`application/BusinessCommandFingerprintFields.kt:20,23`. 이력 응답 M/`adapter/web/admin/BusinessProfileJson.kt:78`, 감사 없음 A/`BusinessQueryService.kt:71-81` | PC-13, NP-04, BIZ (e)-2 | 카드번호 **원문**이 MASTER `master_change_history`(이력 detail `reason`)에 저장되고, 감사 없는 이력 화면으로 다시 보인다. `admin_request`에는 원문이 아니라 사유를 포함한 **무염 해시**가 남는다. 그래서 다른 필드를 아는 DB 접근자가 카드번호 후보를 대입해 확인할 수 있다(G-02와 겹친다). Demo 한정 — 실데이터 없음 **→ 현행(커밋 `439fa1a`, SEC-BIZ-001): M-1로 해소(새 저장부터 거부 — 기존 이력 행은 그대로)** |
| G-02 | P0 | **관리 명령 지문이 무염 SHA-256이다.** 새로 확인한 사실: 수정·등록 완료 지문에는 번호·이름·전화·이메일 외에도 **사유 원문·법인등록번호·주소·상호·영문 상호·개업일**이 들어간다. 범위 판단은 결정으로 이미 끝났다 — D-MOPS-010 ②(`DEC:190` "무염 SHA-256을 … HMAC 지문으로 교체"), D-GOV-001 ②("개인정보 필드 전부 비밀 키 HMAC", U-01 ②에서 권장안 선택). Coordinator 구현 결정은 **지문 전체를 비밀 키 HMAC으로**다(가역 구현 세부 — M-2) | BR-BIZ-048(D-MOPS-010 ②, D-GOV-001 ①②) ↔ M/`AdminCommandExecutor.kt:420-428`, M/`BusinessCommandFingerprintFields.kt:16-41` | DC-M-01, PC-12, NP-01, BIZ (e)-1 | DB 접근자가 번호·개인정보를 대입해 확인할 수 있다(BP1 L-13). Demo 단계, LIVE Gate로 추적 중 **→ 현행(커밋 `439fa1a`, SEC-BIZ-001): M-2로 해소(새 행 `h1:`, 기존 `m1:` 행은 남음 — 비교는 Demo 한정, 제거 조건 MERCHANT-OPS-002 L-25)** |
| G-03 | P3 | **INACTIVE 사업자 수정(update)이 '결과 확인 필요' 잠금으로 끝나고 풀리지 않는다.** 등록 완료는 이미 `Business.kt:85`에서 저장된 거절 `BUSINESS_NOT_DRAFT`로 끝나므로 해당하지 않는다. DC-M-17 ②는 "500 계열"이라고 했다. 수정 경로를 따라가면 이렇다 ① 화면이 INACTIVE에도 편집 폼을 연다 ② 저장하면 Domain `check()`가 `IllegalStateException`을 던진다 ③ 실행기가 이 예외를 분류하지 않는다 ④ MASTER 컨트롤러가 503 `TEMPORARILY_UNAVAILABLE`로 답한다 ⑤ ADMIN이 `RetrySameKey` → 503 `MASTER_RETRY_SAME_KEY`로 바꾼다 ⑥ 화면이 결과 불명으로 잠근다 ⑦ TX가 rollback돼 `admin_request`가 0행이라 같은 내용 재전송도 같은 503이다. 효과가 없다고 확정된 실패를 결과 불명으로 보이는 셈이다. **P3 유지 근거**: 결과를 불명 쪽으로 다루는 fail-closed라 금융·데이터 손상이 없다. 앱 경로로는 INACTIVE 행이 생기지 않는다. 기존 DC-M-17(P3)·PC-36(P3)과 같은 등급이다 | D-GOV-005 ②(U-09 권장안 원문 NORMALIZATION-PLAN:338 "그 전 INACTIVE 수정은 업무 거절"), 보조 BR-BIZ-015 ↔ FE/`BusinessesScreen.tsx:322`, M/`domain/Business.kt:60`, M/`AdminCommandExecutor.kt:133-150`(ISE 미분류), M/`BusinessCommandController.kt:85-93`, A/`HttpMasterBusinessGateway.kt:100`, A/`BusinessProfileService.kt:202`, FE/`lib/useUnknownLockedSubmission.ts:15` | DC-M-17 ②(정밀화), PC-36, NP-68 | 이관·수동 데이터일 때만 해당한다. 운영자는 "그래도 새로 입력"으로만 빠져나온다. MASTER 경보에는 TEMPORARILY_UNAVAILABLE로 잡혀 원인 분류가 흐려진다 **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): M-3으로 해소 — 200 REJECTED `BUSINESS_INACTIVE`, 같은 요청 재전송 replayed=true(Demo 확인 — tasks.json BIZ-NORM-002)** |
| G-04 | P4 | 사유 칸 이름이 결정 문구가 아니다. 지금은 '변경 사유'·'변경 사유(선택)'이고, 결정 문구는 '사유 및 비고'·'사유 및 비고(선택)'이다 | D-FLOW-006(BR-BIZ-304) ↔ FE/`BusinessProfileFormScreen.tsx:389,394`. 시행 예정 = FLOW-2(MERCHANT-FLOW-001 §4A.3a `:211`) | 없음(새로 확인) | 결정과 화면 용어가 다르다. FLOW-2 새 섹션과 이름이 섞인다 **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): M-7로 해소 — '사유 및 비고'·'사유 및 비고(선택)'(FE/`BusinessProfileFormScreen.tsx:389,394`)** |
| G-05 | P4 | **BP1 명세 rev 0.2.9의 화면·노출 절이 뒤 결정을 반영하지 않았다.** §8.1은 탭 4개, §8.2는 표에 번호 마스킹·진위확인 열·상세는 읽기 + 수정 버튼, §8.3은 중복 확인이 선택이고 저장 버튼은 무관하다고 적었다. §9는 번호를 마스킹으로 적었다. 실제는 탭 2개(D-ADMIN-UI-006), 번호 확인 먼저·항상 편집 폼(D-ADMIN-UI-011 ①②), 번호 원문(D-BIZ-007·D-PII-001), 진위확인 숨김(D-ADMIN-UI-010 ②)이다. BP1 본문에 이 결정 ID들이 한 번도 나오지 않는다 | BP1:500-541 ↔ FE/`navigation.ts:93-95`, FE/`BusinessesScreen.tsx:460-489,597`, FE/`BusinessProfileFormScreen.tsx:262-301`. 현행 정본은 MERCHANT-IA-001 rev 0.3i §4.2·§4.2a, BRN-UNMASK-001, PII-MASKING-POLICY | PC-59(BP1 미포함) 보충 | 명세를 근거로 마스킹·선택형 중복 확인을 되살리는 구현·리뷰가 나올 수 있다 **→ BIZ-NORM-003(M-4): BP1 rev 0.2.12에 대체 표시 추가(리뷰·커밋 전)** |
| G-06 | P4 | MASTER 명령 컨트롤러 KDoc이 중복 요약을 "마스킹 번호"라고 적었다. 실제 응답은 원문이다 | D-BIZ-007 ↔ M/`adapter/web/admin/BusinessCommandController.kt:26`(주석) vs `BusinessProfileJson.kt:100-103`·`JooqBusinessDetailReader.kt:90` | 없음(새로 확인) | 주석을 근거로 한 잘못된 수정 **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): M-6으로 해소 — 주석이 '원문 번호(D-BIZ-007·D-PII-001 ① — 마스킹 아님)'** |
| G-07 | P4 | **기존 진단 표현 정정.** DC-M §1·§4.1은 "사업자번호 유일·불변이 코드와 DB에 이중"이라고 했다. **유일**은 DB UNIQUE로 이중 강제된다. 하지만 **불변**은 DB가 강제하지 않는다. `business`는 표 단위 UPDATE 권한이고 열 권한 procedure·trigger가 없다. 불변은 Domain·JPA `updatable = false`·시험으로만 지킨다. BP1이 설계로 받아들인 한계다(BP1:50,52, X-9 계열) | DC-M §1·§4.1 ↔ PRIV/`V2__master_runtime_privileges.sql:13`, M/`adapter/persistence/JpaBusinessEntity.kt:28`, `JpaBusinessRepositorySpec.kt:159` | DC-M §1(반박 — 일부) | runtime 계정으로 수동 SQL을 쓰면 번호를 바꿀 수 있다. 앱 경로는 막혀 있다 |

#### 이번 범위에서 정상화 필수가 아닌 관련 항목

참조만 하고 위 표의 집계에는 넣지 않는다.

- UI-07: 사업자 폼의 `workspace` boolean + `layout` 두 축.
- UI-12: 큰 파일(BusinessProfileFormScreen 841줄, BusinessesScreen 799줄).
- UI-16·26: 지역 `Row`.
- UI-19: 결과 불명 잠금 표시 두 모양.
- UI-10: 화면 메모리 리셋 세 벌.
- SC-07: `BusinessAudit.kt:36`이 `Instant.now()`를 직접 읽는다.
- DC-M-14: 사업자 명령의 REFERENCE Outbox 이벤트는 소비자가 없다.
- INV §5-3 #1: 번호 정확 조회가 세 화면에 따로 구현돼 있다.

모양·구조 정리 항목이며 사업자 업무 규칙과 어긋나지는 않는다.

#### 확인했고 일치하는 것(요약)

확정 규칙 중 위 표 밖의 것은 코드와 일치한다(§3.2 ○ 표시). 특히 다음을 확인했다.

- 중복 요약은 조회 권한자에게만 준다(A/`BusinessProfileService.kt:195`).
- 수정 성공 reference가 다르면 결과 불명으로 둔다(`:180-183`).
- 상세 감사 실패 시 결과를 돌려주지 않는다(A/`BusinessQueryService.kt:55-56`).
- '계약 진행' 섹션은 실패해도 상세를 막지 않고, 섹션 열람은 상세 열람 감사 1행에 포함된다(`:87-97`, D-FLOW-004 ④).
- 화면 중요정보 판정(FE/`lib/businessProfile.ts:378-389`)과 Domain 판정(M/`BusinessProfileChange.kt:46-79`)이 같은 결과를 낸다(DRAFT = 기존 대표자 행 종료가 있을 때, REGISTERED = 대표자 집합의 모든 변경).
- 등록 2단계 흐름은 번호 버킷을 두 번 쓴다(조회 1 + 생성 예약 1). MERCHANT-IA-001 `:259`가 이미 알고 받아들인 동작이다.

### 4.1 기존 진단 재확인(`7d521fd` 기준 유효 — 새 사실 없음, 집계 제외)

다음은 기존 진단과 같은 내용이며, `7d521fd`에서도 그대로 유효함을 확인했다. 시행은 각 NP 항목을 따른다.

| 기존 ID | 사업자 기능 관련 내용 | 확인 근거 |
|---|---|---|
| DC-M-03 / PC-52 / NP-06 | 번호 마스킹 옛 결정 4행에 변경 역참조 없음 **→ BIZ-NORM-003(M-5): 6행에 역참조 추가 — D-MCODE-003·D-MOPS-003·D-BIZ-003·D-BIZ-006 + 대장 BR-BIZ-045·047 원천인 D-MOPS-006 ③·D-ADMIN-UI-004 ⑦(리뷰·커밋 전)** | `DEC:177,183,226,229` |
| DC-M-21 / PC-55 / NP-92 | baseline 사업자 정책서·지침서 대체 표시 없음(방식은 D-GOV-006 ④로 결정) **→ BIZ-NORM-003(M-8): BASELINE-INDEX 'baseline 대체 표' 사업자 8행 BI-BIZ-1~8(리뷰·커밋 전). NP-92의 다른 baseline은 범위 밖** | POL §4·§5, 지침:86 |
| DC-M-22 / PC-59·60 / NP-102 | MERCHANT-BIZ-LOOKUP-001 카드 BACKLOG·수용 기준 옛 내용 **→ BIZ-NORM-003(M-8): 닫기 제안을 BIZ-NORM-003 openItems에 기록 — 카드 status 변경은 Coordinator** | `harness/tasks.json` 해당 카드 |
| DC-M-23 / PC-47 / NP-103 | Q-PII-5·7 질문 대장 미등록 **→ BIZ-NORM-003(M-8): Q-PII-3(남은 부분)·5·7·8·9·10 PROPOSED 등록(답 없음)** | `harness/questions.json` |
| DC-M-24 / SC-19 / NP-104 | 사업자 문자열 규칙 네 벌(MASTER `DisplayName`·`BusinessTextRule`, ADMIN `BusinessProfileInputRules.text`, 화면 `textError`) — M-1 주석 참조 | M/`BusinessTextRule.kt:8-14`, A/`BusinessProfileInputRules.kt:163-168`, FE/`lib/businessProfile.ts:205` |

### 4.2 집계

| 우선순위 | 건수 | ID |
|---|---|---|
| P0 | 2 | G-01, G-02(둘 다 결정 완료 — 시행 대기) **→ 현행: 둘 다 시행(커밋 `439fa1a`, SEC-BIZ-001)** |
| P1 | 0 | — |
| P2 | 0 | — |
| P3 | 1 | G-03 **→ 현행: 시행(커밋 `bdf4e42`, BIZ-NORM-002)** |
| P4 | 4 | G-04, G-05, G-06, G-07 **→ 현행: G-04·G-06 시행(커밋 `bdf4e42`, BIZ-NORM-002), G-05 BIZ-NORM-003(문서), G-07은 수정 대상 아님(§5 아래)** |
| 합계 | 7 | 새 사실 중심: G-03(정밀화)·G-04·G-05·G-06·G-07 / 기존 ID에 사실 보탬: G-01·G-02. 재확인만 한 기존 진단은 §4.1(집계 제외) |

---

## 5. 최소 수정 사항

사업자 등록·조회·수정 정상화에 꼭 필요한 것만 적는다. "결정 완료 — 시행만"은 새 사용자 결정이 필요 없다는 뜻이다.

| # | 구분 | 수정 | 근거 | 결정 상태 | 검증 방법(제안) | 난이도 |
|---|---|---|---|---|---|---|
| M-1 | 코드 + 시험 | 사업자 '사유' 칸에 카드번호 패턴 거부를 넣는다. 위치: 화면 `reasonError`, ADMIN `BusinessProfileInputRules`, MASTER `BusinessChangeReason.errorOf`. **패턴 출처는 기존 구현**이다: ADMIN `approval/domain/MerchantOpsInputRules.kt`·`ReacquisitionInputRules.kt`·`OperatorCancelInputRules.kt`, FINANCIAL `acquiring/domain/CardNumberPattern.kt`. 오류 code는 MERCHANT-FLOW-001:221의 고정 code `CARD_NUMBER_PATTERN`이다. **모듈 경계를 넘는 코드 공유 없이** 각 모듈에 복제하고, 공통 시험 벡터로 복제본들의 결과를 대조한다. 새 저장부터 적용한다(기존 이력 행은 바꾸지 않는다). [주석] 사업자 문자열 규칙이 이미 네 벌이다(DC-M-24·SC-19 — §4.1). 공통 벡터가 판정 drift 위험을 덮는다 | G-01, BR-BIZ-305 | **결정 완료 — 시행만**(D-FLOW-007 ①, 시점 D-GOV-001 ① "정비 첫 작업, 판정 복제본 공통 벡터") **→ 시행 완료(커밋 `439fa1a`, SEC-BIZ-001 — DONE, SEC-gate1 PASS)** | 같은 벡터로 MASTER `BusinessProfileRequestParserSpec`·`BusinessProfileRulesSpec`, ADMIN `BusinessHttpSpec`·`BusinessProfileServiceSpec`(MASTER 미호출 400 `CARD_NUMBER_PATTERN`), FE `lib/businessProfile.test.ts`. 벡터 정본은 ADMIN `OperatorCancelInputRules.containsCardNumber`(오류 code `CARD_NUMBER_PATTERN`) 하나로 지정하고 새 구현은 그 벡터 결과와 같아야 함 — FINANCIAL `CardNumberPattern.kt`와의 일치는 미확인이라 별도 대조(다르면 SC-05로 보고, 기준 완화 금지). 거절 시 `admin_request`·이력 0행. 카드번호가 아닌 숫자열 사유는 통과 | 하~중 |
| M-2 | 코드 + 시험 | 사업자 명령 3종(create·update·complete-registration)의 지문을 **지문 전체(canonical 문자열 전체) MASTER 비밀 키 HMAC**으로 바꾼다. 같은 키·다른 내용 = 충돌은 그대로다. 기존 행 호환 비교는 Demo 한정으로 하고 제거 조건을 기록한다. 제약: 다른 설계(필드 단위)를 쓰더라도 **개인정보 필드를 무염 해시로 남길 수 없다**(D-GOV-001 ②) | G-02, BR-BIZ-048 | **결정 완료 — 시행만**(D-MOPS-010 ②, D-GOV-001 ①②. 전체 HMAC은 Coordinator 가역 구현 결정) **→ 시행 완료(커밋 `439fa1a`, SEC-BIZ-001 — DONE, SEC-gate1 PASS)** | 지문 단위 시험(키 없이 재현 불가), `BusinessProfileApiSpec` 같은 키·다른 본문 409 회귀, `BusinessProfileConcurrencySpec` 무변경 통과, 캔어리 시험(`admin_request`에 원문·무염 해시 0) | 중(키 설정. LIVE 키 관리 L-23 별도) |
| M-3 | 코드 + 시험 + 대장 | ① INACTIVE 사업자의 **수정(updateProfile)**을 저장된 업무 거절로 바꾼다. 지금은 `check()` 결함이다(`Business.kt:60`). ② 화면은 INACTIVE 상세에서 편집 폼 대신 읽기 표 + 안내를 보인다(`BusinessesScreen.tsx:322` — 가역 UI 세부). ③ **등록 완료는 현행 유지**: 이미 저장된 거절 `BUSINESS_NOT_DRAFT`다(`Business.kt:85`). ④ `MasterRejection`은 영속 이름이라 새 값을 더하면 기존 저장 행과 호환을 확인한다. ⑤ D-GOV-006 ②(결정과 대장은 같은 커밋)에 따라 대장에 반영한다 — BR-BIZ-015 보강 또는 새 행 | G-03 | **결정 완료 — 시행만**(D-GOV-005 ②, U-09 권장안 원문 NORMALIZATION-PLAN:338 "그 전 INACTIVE 수정은 업무 거절". 보조 BR-BIZ-015). 거절 code 이름은 구현 세부 **→ 시행 완료(커밋 `bdf4e42`, BIZ-NORM-002 — DONE, NORM2-gate1 PASS). 거절 code `BUSINESS_INACTIVE`, 대장 새 행 BR-BIZ-311** | Domain: INACTIVE 재구성 → `updateProfile` → `MasterRuleViolation`, **`completeRegistration` → `BUSINESS_NOT_DRAFT`(기대값 고정)**. API: 두 경우 모두 200 REJECTED + 같은 키 재생(`replayed=true`). ADMIN: 결과 불명이 아님. FE: INACTIVE 상세에 편집 폼 없음 | 하 |
| M-4 | 문서 | BP1 §8.1~§8.3·§9 머리에 "현행은 MERCHANT-IA-001 rev 0.3i §4.2·§4.2a / BRN-UNMASK-001 / PII-MASKING-POLICY" 대체 표시를 단다. 본문은 보존한다 | G-05 | 결정 불필요(Coordinator — S3 명세 stale 정리) **→ BIZ-NORM-003에서 시행(리뷰·커밋 전)** | 문서 대조: BP1에서 D-ADMIN-UI-006·011, D-BIZ-007, D-PII-001이 검색되는지 | 하 |
| M-5 | 문서 | NP-06 시행(번호 마스킹 옛 결정 역참조) | §4.1 | 결정 불필요(Coordinator, S3) **→ BIZ-NORM-003에서 시행(리뷰·커밋 전)** | NP-06 검증 방법 | 하 |
| M-6 | 코드 주석 | M/`BusinessCommandController.kt:26` "마스킹 번호" → "원문 번호(D-BIZ-007)" | G-06 | 결정 불필요 **→ 시행 완료(커밋 `bdf4e42`, BIZ-NORM-002)** | 리뷰 | 하 |
| M-7 | 화면 문구 | 사유 칸 이름을 '사유 및 비고'·'사유 및 비고(선택)'로 바꾼다. 같은 칸이라 M-1과 같은 배치를 권장한다 | G-04 | **결정 범위 안 — 시행만.** D-FLOW-006(`DEC:252`): "현재 사업자 편집 폼은 이미 공통 사유 칸 하나 … 구조이며 칸 이름을 '사유 및 비고'로 맞춘다". 배치(FLOW-2 예정을 앞당길지)만 Coordinator 판단 **→ 시행 완료(커밋 `bdf4e42`, BIZ-NORM-002 — FLOW-2 전에 앞당겨 시행)** | FE `BusinessProfileFormScreen.test.tsx` 접근 가능한 이름 | 하 |
| M-8 | 문서·대장 | NP-102·NP-103·NP-92 시행(MERCHANT-BIZ-LOOKUP-001 카드 닫기, Q-PII-5·7 PROPOSED 등록, baseline 대체 표) | §4.1 | 결정 불필요(Coordinator. baseline 방식은 D-GOV-006 ④) **→ BIZ-NORM-003에서 시행(리뷰·커밋 전). 카드 닫기는 Coordinator 처리로 제안만** | 각 NP 검증 방법 | 하 |

- 시험 공백(BIZ (d)) 중 이 기능에 직접 걸리는 것은 **INACTIVE 수정·등록 완료 경로**(M-3)와 **사유 카드번호 벡터**(M-1)다. 둘 다 위 행에 넣었다. BR-BIZ-008·015 직접 단언과 BR-ID 연결은 RULE-TRACE(S4)에서 한다. 최소 수정에는 넣지 않았다.
- G-07(번호 불변의 DB 미강제)은 수정 대상으로 넣지 않았다. BP1이 설계로 받아들인 한계다(X-9·L-10 계열).
- §3.2 끝의 drift 지표 오탐은 수용 여부가 미확인이라 수정 대상으로 넣지 않았다(Coordinator 확인 대상).

---

## 6. 사용자 결정 사항

확정 정책은 다시 묻지 않는다. 아래 "질문하지 않음" 목록에 근거를 적었다. **이번 범위에 새 사용자 질문은 없다.** 아래 두 항목은 기존 결정이 정한 절차에 올리는 표시다.

### Q-2 (일괄 확인 대상 표시 — 새 질문 아님) BR-BIZ-017·018

- 내용
  - BR-BIZ-017: 사업체 구분 변경과 REGISTERED 대표자 추가만 하는 변경을 중요정보로 본다.
  - BR-BIZ-018: 중요정보 변경을 2인 승인·재검증 없이 처리한다(TBD-07 기본값).
- **수정 권한이 OPERATOR라는 점은 사용자 결정 D-BIZ-006 ①이다**(확정). BR-BIZ-018에서 '제안'인 부분은 **"2인·재검증 없음"뿐**이다.
- 둘 다 코드에 이미 들어간 '제안' 규칙이다. D-GOV-005 ④("119개 일괄 확인 + 금융 영향 있는 것만 개별 질문")에 따라 **일괄 확인 목록에 넣는다.** 금융 영향이 없으니 개별 질문은 하지 않는다.
- 권장안: 현행 유지로 일괄 확인.
- 대안: 중요정보 변경을 2인 승인으로 올린다. 사업자 수정 명령이 2-TX 승인 흐름으로 바뀌어 화면·ADMIN·MASTER 근거 저장이 모두 늘어난다.
- 영향: 감사·승인 수준. 막히는 수정은 없다(현행이 곧 권장안).

### Q-3 (개인정보 — 등록만, 지금 답 불필요) Q-PII-5 보관·파기, Q-PII-7 상호 마스킹

- 현재 동작은 "결정 전까지 파기 기능 없음·상호 비마스킹"이다. 대장 BR-BIZ-023·024의 미결 상태와 같다.
- 사업자 기능 정상화를 막지 않는다.
- 권장안: PROPOSED로 등록하고(NP-103 — M-8), KYC 보관 기간(D-FLOW-002 ④ "LIVE 전 결정")과 묶어 LIVE Gate 질문으로 제시한다. **→ 현행(BIZ-NORM-003): PROPOSED 등록됨(답 없음). LIVE Gate 제시는 그대로 남음**
- 막히는 수정: 없음(LIVE Gate).

(rev 0.1의 Q-1 "지문 HMAC 범위"는 삭제했다. D-MOPS-010 ②·D-GOV-001 ②로 이미 답이 나왔고, 전체 HMAC은 Coordinator 구현 결정이다 — M-2.)

### 질문하지 않음(확정·결정 완료 — 근거)

| 항목 | 근거 |
|---|---|
| 번호 원문 표시 범위, 목록 감사 없음 | D-BIZ-007, D-PII-001 ①② |
| 조회·명령 역할(수정 = OPERATOR 포함), 상세 개인정보 원문 | D-BIZ-006 ①② |
| 중복 시 기존 사업자 안내 | D-BIZ-003 |
| DRAFT 최소 입력, 번호 오입력 정정 없음 | D-BIZ-002, D-BIZ-005(TBD-04·05는 결정으로 유지) |
| 등록 번호 확인 먼저, 항상 편집 폼 | D-ADMIN-UI-011 ①② |
| 사유 칸 공통·이름·카드번호 거부·적용 시점 | D-FLOW-006, D-FLOW-007 ①, D-GOV-001 ① |
| 지문 HMAC 교체·개인정보 필드 전부·적용 시점 | D-MOPS-010 ②, D-GOV-001 ①②(전체 HMAC = Coordinator 구현 결정) |
| INACTIVE 전이 시점·그 전 수정 업무 거절 | D-GOV-005 ② |
| 대장 정본·baseline 대체 표시 방식 | D-GOV-006 ②④ |
| 새 가맹점 관문의 "최신 접수 건"(PC-45) | FLOW-3 범위(이 문서 제외). D-GOV-005 ④로 FLOW-3 전 개별 확인이 이미 예정됨 |

---

## 7. 미확인

- **시험을 하나도 실행하지 않았다.** 위 시험 파일 이름은 존재만 확인했다. 예: MASTER `BusinessProfileServiceSpec`·`BusinessProfileConcurrencySpec`·`BusinessProfileApiSpec`·`JpaBusinessRepositorySpec`·`MerchantRegistrationGate*Spec`·`ContractIntakeSectionSpec`, ADMIN `BusinessHttpSpec`·`BusinessQueryServiceSpec`·`BusinessProfileServiceSpec`·`BusinessContractProgressSpec`·`MasterBusinessGatewaySpec`, FE `BusinessesScreen*.test.tsx`·`BusinessProfileFormScreen*.test.tsx`·`lib/businessProfile.test.ts`·`App.businessLinkedMerchants.test.tsx`.
- G-03은 코드 판독으로 재구성한 경로다. INACTIVE 행을 넣고 수정했을 때 화면이 실제로 '결과 확인 필요'를 보이는지는 실행하지 않았다. **→ 현행(커밋 `bdf4e42`, BIZ-NORM-002): 옛 경로는 없어졌다. Demo에서 시험 행을 INACTIVE로 바꾸고 수정 PUT → 200 REJECTED `BUSINESS_INACTIVE`, 재전송 replayed=true 확인(tasks.json BIZ-NORM-002 openItems)**
- 화면 `validateForm`(FE/`lib/businessProfile.ts`)과 ADMIN·MASTER 규칙이 모든 벡터에서 같은지는 전수 대조하지 않았다(대표 규칙만 봤다).
- `BusinessProfileFormScreen`의 DRAFT 등록 완료 버튼이 사유 필요 판정을 수정 저장과 똑같이 하는지는 `showReason` 식(`:160-162`)까지만 확인했다.
- MASTER `BusinessQueryController`의 search·lookup 해석 규칙과 ADMIN 복제(A/`BusinessQueryService.kt:113-123`)가 같은지는 등록 상태 값 집합만 확인했다.

## 8. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(불일치 12건, 결정 사항 Q-1~Q-3) |
| 0.1a | 2026-11-25 | 독립 리뷰 CHANGES_REQUIRED 반영. **MAJOR-1** M-3 대상을 수정(update)·화면 편집 폼으로 좁힘. 등록 완료는 이미 저장된 거절 `BUSINESS_NOT_DRAFT`(`Business.kt:85`)라 현행 유지로 두고 검증 기대값을 고정. **MAJOR-2** Q-1 삭제(D-MOPS-010 ②·D-GOV-001 ②로 이미 답). M-2 = 지문 전체 비밀 키 HMAC(Coordinator 가역 구현 결정) + 개인정보 필드를 무염으로 남길 수 없다는 제약. **MAJOR-3** G-08~G-11을 §4.1 기존 진단 재확인으로 옮기고 집계에서 뺌. G-12는 M-1 주석으로 옮김. M-5·M-8은 NP 시행 참조로만. **MINOR** M-3 근거를 NORMALIZATION-PLAN:338 원문으로 인용하고 대장 반영 필요 기록. M-7 근거를 D-FLOW-006 원문으로 인용. M-1 패턴 출처·오류 code 지정. G-01 영향 문구 정정(원문 = `master_change_history`, `admin_request` = 무염 해시). Q-2에 D-BIZ-006 ① 명시. G-03 P3 근거 명시. §3.2에 ADMIN 목표 DRAFT 검사 → drift 오탐 가능 사실 추가(수용 여부 미확인) |
| 0.1b | 2026-11-25 | BIZ-NORM-003(문서만 — 진단 본문 보존, 사실 정정 표시만). 시행으로 바뀐 사실에 '→ 현행(커밋 …)' 표시: §2.2 TBD-08 행(INACTIVE 수정 = 업무 거절 `BUSINESS_INACTIVE` — `bdf4e42`)·BR-BIZ-023/024 행(질문 등록), §3.2 INACTIVE·사유 카드번호(MASTER App·API 열 포함 — 리뷰 MINOR-1)·지문 HMAC 행(`bdf4e42`·`439fa1a`), §4 G-01~G-06 영향 칸, §4.1 재확인 4행, §4.2 집계, §5 M-1·M-2(`439fa1a`)·M-3·M-6·M-7(`bdf4e42`) 시행 완료, M-4·M-5·M-8 BIZ-NORM-003 시행(리뷰·커밋 전), §6 Q-3, §7 G-03 미확인 |
