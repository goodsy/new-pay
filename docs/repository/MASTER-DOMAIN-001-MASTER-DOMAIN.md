# MASTER-DOMAIN-001 Master 도메인 명세 (1단계: 명세·질문 후보)

작성 2026-10-09 KST(1단계 명세), 같은 날 2단계 반영. 기준 commit `a123bab`. 1단계 질문 중 Q-MD-01·02·03·04·05·11은 사용자가 권장안으로 답했다(D-MD-003~008, `docs/DECISIONS.md`). 2단계 TDD 구현 기록은 §9다. 이 문서는 새 업무 정책을 채택하지 않는다 — 미결(Q-MD-06~10, Q-ADMIN-AUTH)은 구현하지 않았다.

표시: **확정(출처)** = 채택 문서 또는 D-* 결정, **관례** = 가역적 구현 선택(§6, 사용자 질문 대상 아님), **미결(Q-MD-xx)** = §5 질문 후보. **Demo** = Simulator/시험 전용 값(운영값 아님).

근거 약어(DOMAIN-DEFINITION §0과 같다):

| 약어 | 문서 | 채택 상태 |
|---|---|---|
| 구조 | `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md` (sha256 `9d237eb9…81fb`) | 확정. 본문 §1–§13 + "기존 결정 연결 및 구현 경계" §1–§5(이하 "구조 경계 §n") |
| 초기 | `docs/baseline/PayCore-초기-확정사항.md` | 확정 |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` | 확정(§4·§5) |
| 정산 | `docs/baseline/PayCore-Settlement-Control-Policy-v0.1.md` | 확정 |
| 정산지급 | `docs/baseline/PayCore-정산지급방식-확정과-상세권장안.md` | "확정 방향"만 확정, "상세 권장안"은 **채택 전** |
| 대안 | `docs/baseline/PayCore-금융통제-관계-자동대사-대안.md` | **이력 자료**(자동 채택 안 됨, 구조 경계 §2 마지막 문단) |
| 착수검토 | `docs/baseline/PayCore-실서비스-착수검토-2026-10-03.md` | 검토 자료 |
| A02·A05·A10 | `docs/baseline/architecture/02·05·10` | 아키텍처 자료 |
| CP계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md` | 관례 |

---

## 1. 범위 — MASTER-DOMAIN-001 / MASTER-SNAPSHOT-001 / 후속

### 1.1 제안하는 범위 자르기

| 작업 | 포함 | 제외 |
|---|---|---|
| **MASTER-DOMAIN-001 (이 작업)** | ① 순수 domain: Business·Merchant·MerchantSite·Contract(+Revision)·ContractServiceAgreement·MerchantAccount(MID)·DefaultDesignation·MerchantAccountSiteAssignment·PaymentMethodActivation의 식별자·불변식·상태 전이(확정 규칙만). ② MASTER migration `V1__master_core.sql` + 표 단위 runtime 권한 `V2`(관례 §9.2) + `application.yml` flyway.locations 추가 + `MainConfigurationSpec` 갱신. ③ JPA persistence adapter·Mapper·Store Port. ④ 최소 application use case(§3.1 표의 "이번" 행): 등록(Business·Merchant·Site), 계약 등록·Revision 승인·서비스 계약 추가, **최초 Default MID 발급(원자 TX)**, 관리 명령 멱등 기록, MASTER Outbox append(Relay 없음). ⑤ 로컬 compose MASTER DB에서 master-api 기동 복구 확인(표가 생기고 권한 migration이 runtime role에 표 권한을 주므로 1044 해소) | 아래 행들 |
| MASTER-SNAPSHOT-001 | 권리 Snapshot 계산·버전·lease·긴급 차단·ACK(허용 §4.2), Outbox Relay → PAYMENT Inbox, `PaymentEligibilityPort` 구현(CP계약 §13) | domain 원본 변경 |
| 후속(제안: MASTER-LIFECYCLE-001) | 질문 답변이 필요한 생명주기 명령: 중지/재개/종료, PRIMARY 교체, ADDITIONAL 공유, Default 교체·종료, 추가 MID 발급, 개통 상태 변경, 통화 활성화 | — |
| 후속(제안: MASTER-POLICY-001 / SETTLEMENT-CONTRACT-001) | FeeSchedule·SettlementPolicy·AdvancePayout·AutoCorrection 정책 정의·Version·Assignment, BusinessCalendar(구조 §1–§4, 구조 경계 §3–§4) | — |
| 후속(관리자 화면·API) | 관리자 web adapter·Thymeleaf 화면 | Q-ADMIN-AUTH(미답) 전까지 **만들지 않는다**. 이번 use case는 시험·향후 adapter가 부르는 `application.provided`까지만 |
| 후속(보안/연동) | Credential(API 키) 메타데이터, 기관 Route/프로파일, 지급계좌(허용 §4.1-10, A05 예시 흐름) | 민감정보·별도 보안 계약 필요 |

이유: 질문 없이 확정 규칙만으로 끝까지 검증할 수 있는 수직 기능은 "최초 Default MID 발급"이다(초기 §가맹점·계약·MID, 구조 경계 §1, 착수검토 §7-3). 생명주기 명령은 출처가 "상세 구현 계약에서 구체화"로 명시 보류했다(구조 경계 §2 셋째 문단, 초기 §v0.1 마지막 문단). 그래서 domain 모델은 확장 가능하게 두되, 보류된 명령은 답변 뒤 별도 카드로 만든다.

### 1.2 이 작업이 만들지 않는 것(명시)

- 운영 정책 숫자·Demo Seed 설치(정책 정의는 이번 범위 밖).
- PAYMENT/FINANCIAL DB 접근, cross-DB FK·JOIN(A02 금지사항).
- Kafka/Redis(ADR-001 미도입). 금융·관계 정합성은 MASTER DB 제약·로컬 TX·Outbox가 지킨다. 브로커/캐시 도입이 이를 대체한다고 보지 않는다(TECHNOLOGY §5).

---

## 2. Aggregate·Entity

모든 원본 소유자는 **MASTER**(A02 핵심 규칙 표, DOMAIN-DEFINITION §2.1). PAYMENT·FINANCIAL은 Snapshot/투영만 갖는다.

### 2.1 Aggregate 경계(제안, 관례)

| Aggregate Root | 포함 | 경계 이유 |
|---|---|---|
| Business | (속성만) | 법적 주체. Merchant 여러 개를 가질 수 있다 |
| Merchant | DefaultDesignation(현재 Default 하나) | Default는 "Merchant의 기본 계정 선택"이라 Merchant 불변식(최대 1개)이다. Merchant 행이 Merchant 안 변경의 직렬화 지점(§3.3) |
| MerchantSite | — | Merchant 1:N. 독립 생명주기 |
| Contract | ContractRevision(append-only), ContractServiceAgreement | D-MD-001: Contract(당사자·Scope·Revision·승인/효력) 1:N CSA. CSA는 Contract 없이는 의미가 없다 |
| MerchantAccount (MID) | 발급 근거(불변) | Merchant 1:N, 다른 Merchant 공유 금지 |
| MerchantAccountSiteAssignment | — | MID–Site N:M 연결 자체가 기간·역할·상태를 가진 Entity(구조 §9) |
| PaymentMethodActivation | — | 발급과 개통 분리(구조 §11), 독립 상태 |

Assignment·Activation을 MID Aggregate 안에 넣지 않는 이유: 이력이 계속 늘어나는 행을 MID 하나에 모두 적재하면 매 변경마다 전체 컬렉션을 읽어야 하고 JPA `@OneToMany` cascade가 append-only 이력을 지우는 경로가 생긴다(관례 §9.1 "연관 매핑·LAZY 미사용"). 대신 **MID 행 잠금**으로 같은 MID의 Assignment·Activation 변경을 직렬화한다(§3.3).

### 2.2 Entity별 명세

#### Business

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `BusinessId` (서버 생성, §6) | 관례 |
| 규칙 | 하나의 Business가 복수 Merchant. 같은 사업자의 모든 브랜드를 한 Merchant로 강제하지 않는다 | **확정** 초기 §가맹점·계약·MID 첫 줄·§23행 |
| 속성 | 표시 이름, 국내 사업자등록번호(10자리 숫자만, UNIQUE, 로그·이벤트 평문 금지·이력은 마스킹) | **확정 D-MD-008** |
| 상태 | 생명주기 ACTIVE/TERMINATED(비가역), 신규 결제 중지는 별도 차단 기록(사유·행위자, 여럿 독립, 신규 승인만 막음) | **확정 D-MD-006**. 차단 기록·종료 명령은 MASTER-LIFECYCLE-001 |
| 용어 | DOMAIN-DEFINITION "사업 주체 / Business" | — |

#### Merchant

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `MerchantId`, 소속 `businessId`(불변) | 관례 / 초기 |
| 규칙 | Merchant 1:N MerchantSite, 1:N MerchantAccount | **확정** 구조 §13 표 |
| Default | 최초 MID = Default MID. 최초 Default 발급은 MID·Default 지정·최초 SiteAssignment·요청 결과·Outbox를 한 MASTER 로컬 TX로 저장, **다른 키의 동시 최초 발급은 하나만 성공** | **확정** 초기 §가맹점·계약·MID(“최초 Default MID 하나를 발급받고”, 마지막 문단), 구조 경계 §1 |
| Default 의미 | 기본 계정 선택 용도. 계약·권한·정산 조건을 자동 상속하지 않는다. SiteAssignment PRIMARY와 다른 개념 | **확정** 초기, 구조 경계 §2 |
| Default 교체·종료 | 0개/1개 규칙, 교체 절차, 종료 Default의 자동 승격 여부 | **미결 Q-MD-07** (출처가 보류: 구조 경계 §2 셋째 문단, 초기 §v0.1 마지막 문단) |
| 상태 | 생명주기 ACTIVE/TERMINATED + 별도 차단 기록 | **확정 D-MD-006** |
| 용어 | 가맹점 / Merchant, Default MID | — |

#### MerchantSite

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `MerchantSiteId`(PG 발급, PAYMENT `MerchantSiteId`가 그대로 비교하는 값), 소속 `merchantId`(불변) | CP계약 §3·§12(길이 64 이하 후보), 관례 |
| 규칙 | 같은 Merchant 소속 MID와만 연결 | **확정** 구조 §8, 허용 §4.1-5 |
| 속성 | 채널 유형(WEB/APP), 채널 식별값(소문자 host / 소문자 앱 ID — 관례) | 활성 값 Merchant 안·Merchant 간 중복 거절 **확정 D-MD-008**(예외 승인 기록은 후속) |
| 상태 | 등록/종료. Site 심사 상태(허용 §4.1-5, 구조 경계 §1 "Site 심사")의 소유·의미 | **미결 Q-MD-08** |
| 용어 | 사이트 / MerchantSite | — |

#### Contract (+ ContractRevision)

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `ContractId`, Revision은 `(contractId, revisionNo)` 1부터 증가 | 관례 |
| 구조 | Contract(당사자·Scope·Revision·승인/효력) **1:N** ContractServiceAgreement. 'ServiceAgreement'·'Agreement'는 CSA의 줄임말 | **확정 D-MD-001** |
| Scope | 초기 Scope는 Merchant 또는 MerchantSite. GID에 계약·수수료·정산·접근 권한 자동 상속 없음 | **확정** 초기 §가맹점·계약·MID 둘째 문단 |
| 승인 ≠ 효력 | 계약 승인 후 효력 시작 전 MID 선발급 가능, 결제는 효력·개통 등 조건 충족 시만. 승인/효력 enum을 하나로 합치지 않는다. 출처의 "Contract ACTIVE"는 유효 계약 **조건 표현**이다(상태값이 아님) | **확정** 초기 셋째 문단, 구조 경계 §1 둘째 문단 |
| 철회 | 발급 이력 보존, 해당 계약의 신규 결제 차단, 재승인만으로 자동 재활성화하지 않음 | **확정** 초기 셋째 문단 |
| Revision | MID는 발급 근거 Revision을 보존 | **확정** 초기 둘째 문단("발급 시점의 계약 서비스와 근거 Revision을 보존") |
| 당사자·Revision·철회/종료·갱신 | 당사자 = Business, Scope MERCHANT/SITES(≥1)·Scope 변경 = 새 Revision, Revision 내용 불변·승인 사실 append-only·효력 [from, to), 철회 ≠ 종료·CSA 단위 철회 허용, 재활성화 = 새 Revision 승인 + 명시 재활성화 명령(사유·행위자), 같은 CSA 갱신은 재검증 후 MID 유지(발급 Revision 불변, 현재 Revision은 판정 시 계산) | **확정 D-MD-004**. 이번 구현: 당사자·Scope·Revision 등록·승인 사실. 철회·종료·재활성화 명령은 MASTER-LIFECYCLE-001 |
| 용어 | 계약 / Contract | — |

#### ContractServiceAgreement (CSA)

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `ContractServiceAgreementId`, 소속 `contractId`(불변) | 관례 |
| 단위 | 1건 = 서비스 종류 1개(예: 카드 PG 결제 서비스). 결제수단 세부는 PaymentMethodActivation, 통화는 Assignment 범위(CSA+MID+Currency) | **확정 D-MD-002** |
| 유일성 | 같은 Contract 안 같은 서비스 종류 CSA 중복 금지 — D-MD-002 "서비스 종류별"의 DB 표현(제안: UNIQUE(contract_id, service_kind)) | D-MD-002 해석(**관례**: 다르게 해석해야 하면 Coordinator 확인) |
| 서비스 종류 값 | 초기 `CARD_PG_PAYMENT`(국내 신용카드, 초기 §출시 범위) | 확정 범위의 이름 붙이기(관례). 체크카드·할부는 Q-AUTH-SCOPE |
| 역할 | MID 발급 근거, MID 공유 조건 "동일 CSA", 수수료·정산 정책 Assignment 기준 | **확정 D-MD-001**, 구조 §7·§13 |
| 상태 | CSA 단위 철회 허용 | **확정 D-MD-004**(철회 명령은 MASTER-LIFECYCLE-001 — 이번에는 등록만) |
| 용어 | 서비스 계약 / ContractServiceAgreement | — |

#### MerchantAccount (MID)

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `MerchantAccountId`(PG 발급, 외부 노출, §6). BusinessId·카드사 가맹점번호·GID와 다른 개념 | **확정** 초기 둘째 문단, DOMAIN-DEFINITION 용어사전 |
| 소속 | `merchantId` 하나(불변). 다른 Merchant 간 MID 공유 금지 | **확정** 구조 §8·§13 |
| 발급 근거 | `issuingAgreementId`(CSA) + `issuingContractRevisionNo`, **발급 후 불변** | **확정** 초기("MID는 발급 근거가 되는 계약 서비스 하나를 명시", "근거 Revision을 보존"), 구조 경계 §2 첫 문장 |
| 발급 전제 | 근거 Contract Revision이 승인됐고 끝나지 않았으며, 발급 시각에 적용되는 Revision이 **있으면** 그것만(D-MD-011·012), **없으면**(효력 시작 전) 자기 시작 시각에 적용될 승인 Revision만 — 선발급 허용(D-MD-015). Production MID는 계약 승인 후 발급. 발급 ≠ 개통·결제 허용(효력 전 결제 차단) | **확정** 초기 셋째 문단, A10 INV-17, D-MD-012·015 |
| 최초 Site | Production MID는 발급 시 같은 Merchant 소속이고 **Agreement Scope가 허용하는** Site를 적어도 하나 연결, 첫 연결은 PRIMARY | **확정** 구조 §7 "기본 규칙", 구조 경계 §1 첫 문단 |
| 원자성 | 최초 Default 발급의 MID·Default·최초 SiteAssignment·성공 요청 결과·Outbox는 같은 MASTER 로컬 TX. Site 연결을 포함한 중간 실패에도 잔존 MID/부분 지정 없음 | **확정** 구조 경계 §1 |
| 발급 ≠ 개통 ≠ 결제 허용 | MID ACTIVE(발급 완료)여도 모든 Site에서 사용 가능한 것이 아님. 런타임 판단 = MID ACTIVE ∧ SiteAssignment ACTIVE ∧ Activation ACTIVE ∧ Contract 유효 ∧ Credential 유효 ∧ Routing 가능 | **확정** 구조 §11, 허용 §4.1-3 |
| 상태 | 생명주기 `ACTIVE`(발급 완료)/`TERMINATED`(비가역). "운영 중지"(허용 §4.1-3)는 생명주기가 아니라 별도 차단 기록 | **확정 D-MD-006**. 최초 발급 결과는 `ACTIVE` |
| 종료 후 | 과거 MID·Assignment 보존 | **확정** 구조 경계 §1 셋째 문단 |
| 환경 축 | MID 하나 = 계약 계정, TEST/LIVE는 Credential·요청 축. 발급 MID는 모두 Production MID(계약 승인 필수), 계약 없는 체험용 식별자 없음 | **확정 D-MD-003** — MID 표에 environment 컬럼 없음 |
| 추가 MID | 필요 시 추가 MID 발급(초기). 승인자·근거·같은 CSA 아래 복수 MID 허용 여부 | 존재는 **확정**, 절차는 **미결 Q-MD-06** |
| 결제 중 MID 변경 금지 | 한 결제 시도 도중 다른 MID로 변경해 주문 중복 제어를 우회하지 않는다 | **확정** 구조 경계 §2 둘째 문단 — PAYMENT 규칙(OrderKey에 MID 포함)으로 이미 보장, MASTER는 해당 없음 |
| 용어 | MID / MerchantAccount, merchantAccountId | — |

#### MerchantAccountSiteAssignment (이하 SiteAssignment)

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `SiteAssignmentId` | 관례 |
| 필드 | merchantAccountId, merchantSiteId, role, effectivePeriod, status | **확정** 구조 §9 |
| 관계 | 논리 N:M 수용, 기본 1 MID : 1 Site | **확정** 구조 §6·§13 |
| 같은 Merchant | MID와 Site는 같은 Merchant(DB 복합 FK로 강제, §3.4) | **확정** 구조 §8, 허용 §4.1-5 |
| Scope | Site 전용 Scope 계약의 MID로 다른 Site를 추가하지 않는다. Merchant Scope 계약은 허용된 복수 Site에 사용 가능 | **확정** 구조 경계 §2 첫 문단 |
| Role | 첫 연결 `PRIMARY`, 추가 채널 `ADDITIONAL`. PRIMARY ≠ Default MID | **확정** 구조 §9, 구조 경계 §2 |
| 공유 조건 | 1 MID : N Site는 다섯 조건 **모두**: 동일 Merchant·동일 CSA·동일 Activation 적용 가능·동일 정산 정책·외부기관 심사상 동일 MID 사용 허용 | **확정** 구조 §7, 구조 경계 §2 |
| 개통 비상속 | 추가 Site의 기본 상태만으로 개통을 자동 상속하지 않는다. 기관 승인 근거가 해당 Site를 포함하는지 확인 | **확정** 구조 경계 §1 셋째 문단, 초기 §v0.1 "Activation" 줄 |
| 기간·이력 | effectiveFrom/effectiveTo, 기존 Mapping을 지우지 않고 이력 보존. 거래 당시 Site와 근거 보존 | **확정** 구조 §10, 초기 §v0.1 |
| 유효 연결 없음 | 신규 결제 불가, 연결 복구/승인 절차 요구 | **확정** 구조 경계 §1 셋째 문단 |
| PRIMARY 동시 유효 수·교체 직렬화 | MID당 유효 PRIMARY 최대 1, 교체 = 기존 종료 + 새 시작 한 TX, 자동 승격 없음, ADDITIONAL만 남으면 신규 결제 차단(fail-closed) | **확정 D-MD-007**. domain 규칙 + 열린 PRIMARY UNIQUE 구현, 교체 명령은 MASTER-LIFECYCLE-001 |
| 상태 전이·기간 중첩·시간 단위·소급 | UTC 시각 [from, to), 같은 MID–Site 중첩 금지·재연결은 새 행, 소급 시작·종료 금지, 미래 종료 예약 허용, 상태 ACTIVE/SUSPENDED/ENDED | **확정 D-MD-005** |
| 공유 증거·승인과 개통 범위 | | **미결 Q-MD-08** |
| 용어 | PRIMARY / ADDITIONAL, MerchantAccountSiteAssignment | — |

#### PaymentMethodActivation (개통)

| 항목 | 내용 | 근거 |
|---|---|---|
| 식별 | `PaymentMethodActivationId` | 관례 |
| 단위 | Phase 1은 MID 단위(MID + PaymentMethod), 향후 optional Site Scope 확장 경계 | **확정** 구조 §12, 초기 §v0.1 |
| 분리 | 발급과 개통은 별개. MID 발급만으로 결제 허용 안 함 | **확정** 구조 §11, 초기 §4 |
| 부분 개통 | 카드 일부 개통은 개통된 카드사/Route만 사용 | **확정** 허용 §4.1-6 |
| 결제수단 상세 | D-MD-002로 결제수단 세부는 Activation이 담당 | **확정 D-MD-002** |
| 상태·카드사 단위·심사 근거 기록 방식 | | **미결 Q-MD-09** |
| 용어 | 개통 / PaymentMethodActivation | — |

#### 통화 활성화

허용 통화는 MID·기관 계약별 기준정보로 활성화(**확정 D-CP-002**), 통화는 Assignment 범위 CSA+MID+Currency에서 다룸(**확정 D-MD-002**). 그러나 "통화 활성화 사실"이 별도 기록인지, 정책 Assignment 존재로 갈음하는지는 정해지지 않았다 → **미결 Q-MD-10**. CurrencyUnit(ISO 4217 minor unit: KRW 0, USD 2)은 표준 사실 기준정보이며 허용 여부와 별개다(관례: MASTER 기준정보 표, CP계약 §4).

### 2.3 상태와 전이(확정 부분만 코드화, 나머지는 답변 후)

| 대상 | 이번 작업에서 코드화 | 답변 후 |
|---|---|---|
| ContractRevision | 등록(내용 불변) → 승인 사실 기록(append-only, 행위자·시각) | 철회·종료·재활성화(D-MD-004, MASTER-LIFECYCLE-001) |
| MerchantAccount | (없음) → `ACTIVE`(최초 Default 발급, D-MD-006) | `TERMINATED`(비가역)·신규 결제 차단 기록(D-MD-006, MASTER-LIFECYCLE-001) |
| SiteAssignment | (없음) → PRIMARY `ACTIVE`, effectiveFrom = 발급 시각, effectiveTo 없음. 기간·PRIMARY 규칙(D-MD-005·007)은 domain `SiteAssignmentRules`로 전부 강제 | 종료·중지·교체(MASTER-LIFECYCLE-001), ADDITIONAL(Q-MD-08) |
| DefaultDesignation | (없음) → 최초 지정 | 교체·해제(Q-MD-07) |
| PaymentMethodActivation | 모델·저장만(상태 이름은 Q-MD-09 전까지 최소: 개통 전 기본값만 허용) | 심사·개통·중지(Q-MD-09) |

시험으로 막을 전이(확정): 발급된 MID의 merchantId·발급 근거 변경 불가 / 다른 Merchant Site와의 Assignment 생성 거절 / Scope 밖 Site로 최초 발급 거절 / 미승인 Revision 근거 발급 거절 / 이미 MID가 있는 Merchant의 "최초 Default" 발급 거절 / Site 없는 최초 발급 거절.

### 2.4 용어 매핑(DOMAIN-DEFINITION §5와의 대응)

| 용어사전 | 코드(제안) | 비고 |
|---|---|---|
| 사업 주체 / Business | `Business`, `BusinessId` | |
| 가맹점 / Merchant | `Merchant`, `MerchantId` | |
| 사이트 / MerchantSite | `MerchantSite`, `MerchantSiteId` | PAYMENT의 `MerchantSiteId`와 같은 값(별도 타입, 공유 코드 없음 — A10 "shared 우회 금지") |
| MID / MerchantAccount | `MerchantAccount`, `MerchantAccountId` | PAYMENT `MerchantAccountId`와 같은 값 |
| Default MID | `DefaultDesignation` | PRIMARY와 다름 |
| PRIMARY / ADDITIONAL | `SiteAssignmentRole` | |
| MerchantAccountSiteAssignment | `SiteAssignment` | 짧은 이름은 관례, 표 이름 `merchant_account_site_assignment` |
| 계약 / 서비스 계약 | `Contract`, `ContractRevision`, `ContractServiceAgreement` | D-MD-001 |
| 발급 / 개통 | `MerchantAccountIssuance`(domain service), `PaymentMethodActivation` | |
| GID | 모델 없음 | 상속 금지라 이번 범위에서 표현하지 않는다 |

DOMAIN-DEFINITION §3.1에 이 문서 포인터를 한 줄 추가했다(정의 내용은 바꾸지 않음).

---

## 3. 트랜잭션·동시성·멱등·append-only·Snapshot 계약

### 3.1 Use case와 로컬 TX 경계

모든 use case는 MASTER DB 한 로컬 TX(관례 §9 `LocalTransactions`, READ COMMITTED, Port `@Transactional(MANDATORY)`)이고 외부 호출이 없다. 관리 명령은 모두 `admin_request` 멱등 기록 → 업무 변경 → 변경 이력 → Outbox를 **같은 TX**로 커밋한다(A02 트랜잭션 경계 1: "MASTER 변경 + 설정 이벤트 Outbox는 MASTER의 한 로컬 트랜잭션").

| Use case | 이번/후속 | 같은 TX에 저장 | 확정 근거 |
|---|---|---|---|
| RegisterBusiness / RegisterMerchant / RegisterMerchantSite | 이번 | 멱등 기록 + 행 + 이력 + Outbox | 관계 확정(초기·구조 §13), 등록번호·활성 채널 중복 거절(D-MD-008) |
| RegisterContract(Scope, 첫 Revision) / AddServiceAgreement / ApproveContractRevision | 이번(최소) | 멱등 + Contract/Revision/CSA/승인 사실 + Outbox | D-MD-001·002, 초기 |
| **IssueFirstDefaultMerchantAccount**(merchantId, contractId, agreementId, revisionNo, primarySiteId) | 이번(핵심) | 멱등 기록(결과 SUCCESS + MID 참조) + MerchantAccount(ACTIVE) + DefaultDesignation + SiteAssignment(PRIMARY) + merchant.entitlement_revision + 이력 + Outbox(MerchantAccountIssued·DefaultMerchantAccountDesignated·SiteAssignmentStarted) | **확정** 구조 경계 §1, 초기 마지막 문단 |
| RegisterPaymentMethodActivation | 이번(모델만, 상태는 최소) | 멱등 + Activation + Outbox | 구조 §11–§12 |
| 중지/재개/종료, PRIMARY 교체, ADDITIONAL, Default 교체, 추가 MID, 통화 활성화 | 후속 | 같은 원칙 | MASTER-LIFECYCLE-001(D-MD-004~007 확정분), Q-MD-06~10 |

최초 Default 발급 TX의 순서(잠금 순서 §3.3과 같다):

```text
MASTER TX:
  1) admin_request 예약(IODKU 후 FOR UPDATE) — 같은 키·같은 내용 결과 있으면 재생, 다른 내용이면 충돌
  2) merchant FOR UPDATE — 상태 확인, "이미 MID 있음"이면 거절(DefaultAlreadyIssued, 기존 MID 참조)
  3) contract(+revision 승인 사실) 읽기 — 승인 Revision, Scope가 primarySite 허용, CSA가 같은 Contract 소속
  4) merchant_site 비잠금 읽기 — 같은 Merchant, 활성(소속은 불변이고 Site 종료 명령이 아직 없어 잠그지 않는다. 종료 명령이 생기면 MASTER-LIFECYCLE-001이 잠금 순서에 넣는다)
  5) INSERT merchant_account(ACTIVE) → default_designation → site_assignment(PRIMARY) → 이력 행들
  6) outbox append, admin_request 결과 SUCCESS 기록, merchant.entitlement_revision +1
COMMIT → 응답(커밋 후). 어느 단계 실패도 전체 rollback(잔존 MID/부분 지정 없음)
```

### 3.2 멱등(관리 명령)

| 항목 | 제안 | 성격 |
|---|---|---|
| 범위 | UNIQUE(`operation`, `idempotency_key`) — 관리 명령에는 가맹점 MID 컨텍스트가 없다. 운영자 신원은 Q-ADMIN-AUTH 이후 행위자 컬럼으로 기록(범위 키에는 넣지 않는다: 같은 키를 다른 운영자가 재사용하면 충돌로 드러나는 편이 안전) | 관례 |
| 비교 | 요청 fingerprint(`m1:` 접두, CP계약 §6과 같은 길이 접두 canonical + SHA-256)와 저장 결과 | 관례 |
| 같은 키·같은 내용 | 최초 결과 재생(D-CP-003의 "저장된 결과만" 방식을 관리 명령에도 같은 모양으로 적용 — 관리 명령은 그 결정의 대상 API는 아니므로 관례로 기록) | 관례 |
| 같은 키·다른 내용 | 충돌 거절 | CLAUDE.md 핵심 규칙 |
| 다른 키·같은 최초 Default 발급 | **하나만 성공**, 나머지는 거절(기존 MID 참조) | **확정** 초기 마지막 문단 |
| 키 형식 | D-CPC-002와 같은 1~64자 `[A-Za-z0-9-_.:]`, NO PAD binary | 관례(재사용) |
| 보존 | **삭제 경로를 만들지 않는다**(무기한 보존). 삭제 Job이 필요해지면 그때 보존 기간을 질문 | 관례(D-CPC-004 30일은 CreatePayment 결정이라 자동 적용하지 않음) |
| 업무 거절 저장 | 거절(예: DefaultAlreadyIssued)도 결과로 저장해 같은 키 재요청에 같은 거절을 재생 | 관례(D-CPC-007과 같은 모양) |

### 3.3 동시성·잠금 순서

잠금 순서(교착 방지, 관례 §9 "잠금 순서"와 같은 방식): `admin_request` → `business` → `merchant`(여러 개면 id 오름차순) → `contract` → `merchant_account` → `merchant_site` → `site_assignment`/`payment_method_activation`.

- **Merchant 행이 Merchant 안 모든 관계 변경의 직렬화 지점**이다: 최초 Default 발급, Default 교체, MID 추가, Assignment 변경 모두 merchant를 FOR UPDATE로 먼저 잡는다. 서로 다른 Assignment 행 사이의 경쟁(예: PRIMARY 둘 동시 생성)은 낙관적 version으로 막을 수 없기 때문이다(관례 §9.1 비관적 잠금 설명과 같은 이유).
- `merchant.entitlement_revision`(BIGINT, 단조 증가): 결제 허용 판정 입력을 바꾸는 TX에서 영향받는 Merchant 행을 +1한다. **현재 코드의 증가 지점은 셋**: Revision 승인(Scope Merchant), 최초 Default 발급, 개통 등록. Business·Merchant·Site 등록, 계약·Revision 등록(미승인), CSA 추가는 그 자체로 판정 결과를 바꾸지 않아 올리지 않는다(생명주기·차단·통화 명령이 생기면 각 작업이 증가 지점을 추가). MASTER-SNAPSHOT-001이 "과거 허용 이벤트로 차단 되돌림 금지"(허용 §4.2 둘째 줄)를 버전 비교로 구현할 수 있게 하는 **제안**이다(관례, Snapshot 작업이 다른 방식을 고르면 바꿀 수 있다). JPA `@Version`(기술 동시 수정 감지)과 별개의 업무 값이다.
- 교착은 InnoDB가 감지하고 서비스가 rollback 후 새 TX로 재실행(최대 3회, PAYMENT와 같은 기술 설정).

### 3.4 DB 제약으로 표현하는 규칙(MySQL 8.4, 모든 문자열 `utf8mb4_0900_bin`)

| 규칙 | DB 표현 | 근거 |
|---|---|---|
| MID는 한 Merchant 소속, 다른 Merchant 공유 금지 | `merchant_account.merchant_id NOT NULL`, UNIQUE(`merchant_account_id`, `merchant_id`)(복합 FK 대상) | 구조 §8 |
| Assignment의 MID·Site가 같은 Merchant | `site_assignment`에 `merchant_id` 컬럼 + 복합 FK (`merchant_account_id`, `merchant_id`) → merchant_account, (`merchant_site_id`, `merchant_id`) → merchant_site | 구조 §8, 허용 §4.1-5. domain 검사와 이중 방어 |
| Merchant당 현재 Default 최대 1 | `default_designation` PK(`merchant_id`) | 초기 |
| 최초 Default 발급 하나만 | merchant 행 잠금 + "MID 0개" 확인 + 위 PK 안전망. 다른 키 동시 요청은 잠금 대기 후 거절 | 초기 |
| 같은 MID–Site의 열린 연결 하나 | 생성 컬럼 `open_pair_key = IF(effective_to IS NULL AND status <> 'ENDED', CONCAT(mid,'|',site), NULL)` UNIQUE + CHECK(effective_from >= created_at, 소급 시작 금지) | D-MD-005(닫힌 기간 중첩은 merchant 잠금 아래 domain 검사) |
| MID당 열린 PRIMARY 하나 | 생성 컬럼 `open_primary_key = IF(role='PRIMARY' AND effective_to IS NULL AND status <> 'ENDED', mid, NULL)` UNIQUE | **확정 D-MD-007**(기간이 겹치는 PRIMARY 금지는 domain `SiteAssignmentRules`) |
| 기간 순서 | CHECK(`effective_to IS NULL OR effective_to > effective_from`) | 구조 §10 |
| 발급 근거 불변 | `merchant_account.issuing_agreement_id`·`issuing_contract_revision_no` NOT NULL, Entity `updatable=false` | 초기 |
| CSA 서비스 종류 중복 금지 | UNIQUE(`contract_id`, `service_kind`) | D-MD-002 해석 |
| Revision 번호 | PK(`contract_id`, `revision_no`), CHECK(revision_no ≥ 1) | 관례 |
| 상태·role·scope 값 | CHECK(IN …) | 관례 |
| 멱등 | UNIQUE(`operation`, `idempotency_key`) | §3.2 |
| Outbox | UNIQUE(`event_id`), UNIQUE(`effect_key`) | PAYMENT와 같은 모양(CP계약 §12) |

MySQL에는 기간 배제(exclusion) 제약이 없으므로 "닫힌 구간끼리의 중첩"은 merchant 행 잠금 아래 domain 검사로 막고 경합 시험으로 증명한다. Cross-DB FK는 두지 않는다.

### 3.5 append-only 표와 runtime 권한(관례 §9.2 적용 계획)

| 표 | 성격 | runtime 권한(계획) | 이유 |
|---|---|---|---|
| `business`, `merchant`, `contract` | Aggregate root(잠금 읽기·version 갱신) | SELECT, INSERT, UPDATE | `SELECT … FOR UPDATE`는 UPDATE 권한 필요, merchant는 entitlement_revision 갱신, contract는 자식 추가 시 version 증가. DELETE 경로 없음 |
| `merchant_site` | Site(잠금·갱신 경로 없음) | SELECT, INSERT | 종료는 MASTER-LIFECYCLE-001 |
| `contract_revision` | **append-only**(Revision 내용 불변) | SELECT, INSERT | 수정은 새 Revision |
| `contract_revision_approval` | **append-only 확정 사실**(승인자·시각) | SELECT, INSERT | 감사 증거 |
| `contract_service_agreement` | CSA(철회 명령은 후속) | SELECT, INSERT | 이번 코드에 갱신 경로 없음 |
| `merchant_account` | MID root | SELECT, INSERT, UPDATE | 잠금 읽기, 이후 상태 변경. 발급 근거 컬럼은 `updatable=false` |
| `default_designation` | 현재 Default(교체 시 UPDATE) | SELECT, INSERT (교체 작업이 UPDATE 추가) | 이번 경로는 INSERT만 |
| `merchant_account_site_assignment` | 기간 종료 시 UPDATE 필요(effective_to) | SELECT, INSERT (종료 작업이 UPDATE 추가) | 이번 경로는 INSERT만 |
| `payment_method_activation` | 상태 보유 | SELECT, INSERT | 상태 전이는 Q-MD-09 후 |
| `master_change_history` | **append-only 감사**(대상·변경 전후·행위자·사유·request_record_id) | SELECT, INSERT | Auditability(A05 "변경 전후 값·사유") |
| `admin_request` | 멱등 기록 | SELECT, INSERT, UPDATE | IODKU·결과 기록. DELETE 없음(§3.2) |
| `outbox_event` | Outbox | SELECT, INSERT | Relay(UPDATE)는 MASTER-SNAPSHOT-001 |
| (`currency_unit`) | 1단계 제안이었으나 **만들지 않았다** — 통화 활성화 위치(Q-MD-10) 답변·MASTER-SNAPSHOT-001에서 함께 정한다 | — | — |

append-only 표에 UPDATE/DELETE가 필요해지면 정책 질문으로 돌린다(관례 §9.2 표별 권한 도출).

### 3.6 Outbox 이벤트(MASTER → 후속 소비자)

- 같은 TX append, `PENDING`, Relay 없음. 이벤트 분류 `INTEGRATION_MASTER_REFERENCE`(금융 사실 아님). payload에 Secret·개인정보·등록번호 없음.
- 제안 이벤트(schemaVersion 1): `MerchantAccountIssued`(mid, merchantId, issuingAgreementId, revisionNo, issuedAt, entitlementRevision), `DefaultMerchantAccountDesignated`, `SiteAssignmentStarted`(assignmentId, mid, siteId, role, effectiveFrom), `ContractRevisionApproved`, `ServiceAgreementAdded`, `PaymentMethodActivationRegistered`, `MerchantRegistered`, `MerchantSiteRegistered`. `effect_key` = `<eventType>:<aggregateId>:<aggregateVersion>`.
- 소비 계약(Snapshot 이벤트·순서·watermark)은 MASTER-SNAPSHOT-001이 정한다. 여기 이벤트 이름은 가역적이다(소비자 없음).

### 3.7 MASTER-SNAPSHOT-001이 이 작업에서 가져갈 것

CP계약 §13 `PaymentEligibilityPort.evaluate(environment, authenticatedMid, merchantSiteId, currency, paymentMethod, evaluatedAt)`의 각 결과에 필요한 MASTER 원본:

| Eligibility 판단 | MASTER 원본(이 작업) | 공백 |
|---|---|---|
| NotFoundOrForbidden: Site 없음·다른 Merchant/MID·Assignment 무효 | merchant_site, merchant_account.merchant_id, site_assignment(기간·상태) | D-MD-005 확정. ADDITIONAL만 남은 MID는 차단(D-MD-007) |
| NotEligible CONTRACT_NOT_EFFECTIVE | contract_revision 승인 사실·효력 기간, CSA | D-MD-004 확정, 철회 기록은 MASTER-LIFECYCLE-001 |
| NotEligible METHOD_NOT_ENABLED | payment_method_activation | Q-MD-08·09 |
| NotEligible CURRENCY_NOT_ENABLED | 통화 활성화 | Q-MD-10 |
| NotEligible MERCHANT_SUSPENDED | business/merchant 생명주기 + 신규 결제 차단 기록 | D-MD-006 확정, 차단 기록 표는 MASTER-LIFECYCLE-001 |
| MID 운영 중지(허용 §4.1-3) | merchant_account.status | §13의 고정 4종 reason에 **대응 reason이 없다**(§7 충돌 2) |
| snapshotVersion·차단 우선 | `merchant.entitlement_revision` 제안 | Snapshot 작업 확정 |
| currencyUnit | (미구현) | Q-MD-10 |

---

## 4. 8대 원칙별 완료 증거(이 작업 범위)

| 원칙 | 적용 | 2단계 증거(시험) |
|---|---|---|
| Financial Consistency | 금액 없음. 대신 **관계 정합성**: MID 단일 Merchant, Assignment 같은 Merchant, 발급 근거 불변, 이력 append-only | domain 규칙 시험 + 복합 FK·CHECK 위반 INSERT가 실제 MySQL에서 실패 |
| Transaction Safety | 최초 Default 발급 6종 기록 한 로컬 TX, 외부 호출 없음 | 각 Port flush 직후 장애 주입 → 잔존 행 0(`IssueFirstDefaultRollbackSpec`), commit 단계 예외 → OutcomeUnknown 분류 |
| Idempotency | admin_request 범위·fingerprint, 같은 키 재생, 다른 내용 충돌, 거절 재생 | 같은 키 재요청 결과 동일·행 1건, 다른 내용 충돌 |
| Concurrency Control | merchant 잠금 직렬화, 열린 PRIMARY/연결 생성 컬럼 UNIQUE, Default PK | 다른 키 8개 동시 최초 발급 → 정확히 1 MID·1 Default·1 PRIMARY(실제 다중 연결) |
| Recoverability | 커밋 불명확은 같은 키 재요청으로 확인(새 키 금지 안내) | commit 중 연결 끊김 후 같은 키 재요청 → 재생 |
| Reconciliation | **해당 적음**: 금융 대사 대상 없음. Outbox ↔ 업무 행 대응(이벤트 누락 없음)을 시험으로 확인. MASTER↔PAYMENT Snapshot 대사는 MASTER-SNAPSHOT-001 | outbox 행 수 = 변경 수 |
| Auditability | master_change_history(행위자·사유·요청 ID·변경 전후), Revision 승인 사실, 발급 근거 보존 | 이력 행 내용 시험, append-only 권한(UPDATE/DELETE 1142) |
| Observability | 구조화 로그(requestId·operation, MID는 label로 쓰지 않음), Outbox PENDING 적체 지표는 Relay 작업 | 이번은 로그 필드 시험 최소. 지표는 MASTER-SNAPSHOT-001로 이월(이유: 소비자·Relay 없음) |

## 4.1 JPA·jOOQ·BOM 적용 계획

JPA(2단계 코드에 연결할 설명 — 역할 → 예 → 이유 → 주의):

- **순수 Domain과 JPA Entity 분리.** 역할: `JpaMerchantAccountEntity` 등은 `master.<module>.adapter.persistence`에만 두고 Mapper가 domain과 변환. 예: 저장된 `issuing_revision_no`는 `MerchantAccount.reconstitute`가 `IssuingBasis`로 다시 검증(1 이상), `SiteAssignment.reconstitute`는 소급 시작·종료 시각 없는 ENDED를 거부, `Contract.reconstitute`는 Revision들의 대상 Merchant 일치(D-MD-013)를 검증한다(`MasterReconstitutionSpec`). 이유: domain 순수성 ArchUnit 규칙(관례 §3)과 DB 제약이 같은 규칙을 이중으로 지킨다. 주의: Entity는 data class 금지, equals/hashCode는 업무 식별자만.
- **`updatable=false`(발급 근거·소속 불변).** 역할: dirty checking이 만드는 UPDATE의 SET에서 그 컬럼을 뺀다. 예: MID 상태를 바꾸는 flush에 `merchant_id`·`issuing_agreement_id`가 포함되지 않음. 이유: 발급 근거 보존(초기). 주의: 표 단위 UPDATE 권한은 열을 구분하지 못하므로 DB는 막지 못한다(관례 §9.2와 같은 한계) — Store의 check와 시험이 막는다.
- **비관적 잠금(`@Lock(PESSIMISTIC_WRITE)`)과 `@Version`의 역할 분담.** 역할: merchant FOR UPDATE가 "MID 0개 확인 → 발급"을 직렬화, `@Version`은 같은 행 동시 수정 감지. 예: 다른 키 두 요청이 동시에 최초 발급 → 두 번째는 잠금 대기 후 MID가 이미 있음을 보고 거절. 이유: 서로 다른 새 행(MID 두 개) 사이 경쟁은 version이 못 막는다. 주의: 잠금은 짧게, 고정 순서(§3.3).
- **연관 매핑 미사용·persist만.** 역할: Assignment·이력은 별도 Entity로 INSERT. 이유: cascade/orphanRemoval이 append-only 이력을 지우는 경로를 구조적으로 없앤다. 주의: Spring Data `save`(merge)는 append-only 표에서 1142를 낼 수 있어 `persist`만.
- **생성 컬럼 UNIQUE.** 역할: "열린 PRIMARY 하나"를 DB가 보장. 주의: 생성 컬럼은 Entity에서 `insertable=false, updatable=false`로 읽기만.

jOOQ(CQRS 조회): 2단계에서 master-api에 payment-api와 같은 codegen source set(Flyway `db/migration/master`만 적용한 MySQL 8.4 컨테이너, **권한 migration 위치는 적용하지 않음** — AC 마지막 항목)을 추가하고 조회 Port 하나(`MerchantStructureQuery`: Merchant → Site·MID·열린 Assignment·Default 조회 DTO)를 `adapter.query`에 둔다. 쓰기는 JPA만(`queryAdapterDoesNotWriteWithJooq`). 조회 Port는 권한 범위를 Q-ADMIN-AUTH 이후 web adapter가 강제한다.

BOM: 새 라이브러리 없음. jOOQ codegen·Fixture Monkey(테스트 전용)는 `gradle/libs.versions.toml`의 기존 항목(Boot 4.1.1 BOM 관리 jOOQ, fixture-monkey 1.2.3)을 재사용하고 GRADLE-DEPS 명령으로 resolved tree를 확인한다. Kafka/Redis는 도입하지 않는다.

---

## 5. 미결(질문 후보)

2026-10-09 상태: Q-MD-01~05·11은 답변됨(D-MD-003~008). **남은 미결은 Q-MD-06~10**과 기존 Q-ADMIN-AUTH이며 구현하지 않았다. 아래 원문은 이력으로 보존한다.

출처와 D-* 결정에 답이 없는 **금융·정책·보안 의미**만 적었다. 이미 결정된 것(D-MD-001·002, 관계 기본 구조, 최초 Default 원자성, 선발급 허용, 공유 금지 등)은 다시 묻지 않는다. 권장안은 질문 후보일 뿐 채택이 아니다. HARNESS-GUIDE §4-6(한 번에 3개 정도)에 따라 묶음을 나눴다.

### 묶음 A — 최초 Default 발급 schema·domain을 막는 것(지금 필요)

**Q-MD-01 MID와 환경(TEST/LIVE)의 관계** — **답변: 권장안 채택(D-MD-003, 2026-10-09)**
- 질문: MID 하나가 TEST와 LIVE 두 환경에서 함께 쓰이는가(환경은 Credential·요청의 축), 아니면 TEST용 식별자와 Production MID를 따로 발급하는가? A10 INV-17은 "Production MID는 계약 승인 후 발급하고 테스트 식별자와 구분", 구조 §7은 "Production MID 기준"이라고만 하고 D-CPD-001은 OrderKey의 environment를 TEST/LIVE로 정했다.
- 권장안: **MID는 계약 계정 하나이고 TEST/LIVE는 Credential·요청 축**(같은 MID에 TEST/LIVE 키가 따로 존재, OrderKey가 environment를 포함하므로 주문은 분리됨). MASTER-DOMAIN-001이 발급하는 MID는 모두 "Production MID"(계약 승인 필수). 계약 없는 체험용 식별자는 이번 범위에서 만들지 않는다.
- 대안: (a) MID에 environment 속성을 두고 TEST MID(계약 없이 발급 가능)와 LIVE MID를 별도 발급. (b) TEST 전용 가상 Merchant 공간을 따로 둠.
- 영향: `merchant_account` 키·UNIQUE에 environment 포함 여부, 발급 전제(계약 승인)가 TEST에도 적용되는지, Snapshot의 TEST 판정 조건.
- 막히는 부분: MID schema·발급 전제 시험(단계 S2–S4). 계속 가능: Business·Merchant·Site·Contract 모델.

**Q-MD-02 Contract 당사자·Revision·승인/효력·철회 모델과 갱신 시 MID 근거** — **답변: 권장안 채택(D-MD-004, 2026-10-09)**
- 질문: (1) 계약 당사자는 Business(법적 주체)이고 Scope가 그 Business 소속 Merchant 또는 Site인가? (2) Revision 하나가 승인 사실·효력 기간을 갖는가, 승인 후 내용 변경은 새 Revision인가? (3) "철회"와 "종료·만료"를 구분하는가, CSA 단위 철회가 있는가? (4) 철회 후 "재승인만으로 자동 재활성화 안 함"(초기)의 재활성화 절차는? (5) 같은 CSA의 새 Revision(갱신)이 승인되면 기존 MID는 유지되고 적용 Revision만 바뀌는가?
- 권장안: (1) 당사자 = Business, Scope = (MERCHANT, merchantId) 또는 (SITES, 1개 이상 siteId), Scope 변경은 새 Revision. (2) Revision 내용 불변·승인 사실 append-only·효력 기간 [from, to). (3) 철회(승인 취소, 신규 결제 즉시 차단)와 종료(효력 기간 만료)를 구분, CSA 단위 철회 허용. (4) 철회된 계약은 새 Revision 승인 + 별도 "재활성화" 명령(사유·행위자) 둘 다 있어야 유효. (5) 같은 CSA의 새 Revision은 재검증 후 MID 유지, MID의 **발급 근거 Revision은 불변**으로 두고 "현재 적용 Revision"은 판정 시 계산(대안 문서 §3의 제안과 같은 방향 — 미채택이므로 질문).
- 대안: 당사자 = Merchant / Revision마다 승인 없이 효력만 / 갱신 시 새 MID 발급.
- 영향: contract·revision·approval 표, 발급 전제, CONTRACT_NOT_EFFECTIVE 판정, 정산 정책 Assignment(SETTLEMENT-CONTRACT-001).
- 막히는 부분: Contract 표 확정(S3), 철회·재활성화 명령(후속). 계속 가능: 최소 "승인된 Revision만 발급 근거" 규칙(확정)으로 domain 시험.

**Q-MD-03 SiteAssignment 기간·상태 의미** — **답변: 권장안 채택(D-MD-005, 2026-10-09)**
- 질문: effectivePeriod의 시간 단위(UTC 시각 vs 영업일 날짜), 구간 형태, 같은 MID–Site의 기간 중첩/재연결, 과거 시점으로 시작·종료하는 소급 변경 허용 여부, 상태 집합(예: ACTIVE/SUSPENDED/ENDED)은?
- 권장안: UTC 시각 반열림 [from, to). 같은 MID–Site 기간 중첩 금지, 종료 후 재연결은 새 Assignment 행. **소급 금지**(from·to는 명령 시각 이상 — 과거 거래 당시 관계 근거를 바꾸지 않기 위해, 초기 §v0.1 "거래 당시 Site와 근거 보존"). 미래 종료 예약 허용. 상태 ACTIVE / SUSPENDED(일시 중지, 기간 유지) / ENDED(effectiveTo 확정).
- 대안: 날짜(KST) 단위 기간 / 소급 종료 허용(승인 필요) / 상태 없이 기간만.
- 영향: Assignment 컬럼 타입, 생성 컬럼 UNIQUE 설계, Snapshot의 기간 판정, 거래 당시 관계 증거.
- 막히는 부분: 최초 PRIMARY의 기간 컬럼 타입(S3). 잠정안으로 진행하면 schema 변경이 필요할 수 있어 묶음 A에 둔다.

### 묶음 B — 생명주기 명령(후속 카드) 전에 필요

**Q-MD-04 Business·Merchant·Site·MID 중지/종료 의미와 재개** — **답변: 권장안 채택(D-MD-006, 2026-10-09)**
- 질문: 각 대상의 "신규 결제 중지"와 "종료"를 어떻게 구분하며, 중지 해제(재개)는 누가 어떤 조건으로 하는가? MID 종료는 비가역인가? 착수검토 §2는 "단일 SUSPENDED로 모든 업무를 막으면 사후 의무도 처리 못한다"고 지적했고 허용 §4.3은 작업별 제한을 정했다.
- 권장안: 각 대상에 생명주기(ACTIVE/TERMINATED, 종료 비가역)와 별도의 **신규 결제 차단 기록**(사유·행위자, 여러 건 독립 — 하나 해제가 다른 차단을 지우지 않음, 정산 §13–14 Hold 방식과 같은 모양)을 둔다. 차단은 신규 승인만 막고 조회·취소·정산 의무는 허용 §4.3 표대로. 해제는 명시 명령 + 사유(2인 승인 여부는 Q-ADMIN-AUTH와 함께).
- 대안: 단일 상태 enum(ACTIVE/SUSPENDED/TERMINATED) / Business 상태 없이 Merchant만.
- 영향: MERCHANT_SUSPENDED 판정, MID 중지 reason 매핑(§7 충돌 2), 차단 우선 전파(Snapshot).
- 막히는 부분: 중지·재개·종료 명령. 계속 가능: 최초 발급(TERMINATED가 아닌 Merchant만 허용하는 보수적 전제로).

**Q-MD-05 PRIMARY 동시 유효 수·교체·자동 승격** — **답변: 권장안 채택(D-MD-007, 2026-10-09)**
- 질문: 한 MID에 동시에 유효한 PRIMARY는 몇 개인가? PRIMARY Site 변경(구조 §10 old-shop → new-shop 예)은 어떤 절차인가? PRIMARY가 끝나고 ADDITIONAL만 남으면 자동 승격하는가?
- 권장안: MID당 유효 PRIMARY 최대 1, 교체는 "기존 PRIMARY 종료 + 새 PRIMARY 시작"을 한 TX(merchant 잠금)로, **자동 승격 없음**(명시 명령). PRIMARY 없이 ADDITIONAL만 유효한 상태는 신규 결제 차단(fail-closed) — 또는 아래 대안.
- 대안: PRIMARY는 "최초 연결" 이력 표지일 뿐 교체 개념 없음 / PRIMARY 종료 시 ADDITIONAL 중 하나 자동 승격.
- 영향: 생성 컬럼 UNIQUE 유지 여부, 교체 명령, Snapshot.
- 막히는 부분: PRIMARY 교체 명령.

**Q-MD-06 추가 MID 발급 근거·승인**
- 질문: 추가 MID 발급에 필요한 근거와 승인자는? 같은 CSA 아래 복수 MID를 허용하는가?
- 권장안: 추가 MID는 근거 CSA·사유 코드(새 서비스 계약 / Site 격리 / 기관·정산 운영 필요)·요청자·승인자 기록 필수, 같은 CSA 아래 복수 MID 허용, 단순 정산주기 차이는 사유로 인정하지 않음(대안 §3 제안 — 미채택이라 질문). 요청자≠승인자 강제는 Q-ADMIN-AUTH 답변 후.
- 대안: CSA당 MID 1개 / 사유 없이 운영자 판단.
- 영향: 추가 발급 명령, 정산 경계(MID별 정산).
- 막히는 부분: 추가 MID 발급 명령.

**Q-MD-07 Default 교체·종료**
- 질문: Default MID를 바꾸거나 Default MID가 종료되면 어떻게 하는가?
- 권장안: Default는 발급 전·종료 후 0개, 그 밖 최대 1개. 교체는 명시 명령(같은 Merchant의 ISSUED MID만, merchant 잠금), Default MID 종료 시 다른 MID 자동 승격 없음(Default 0개 상태 허용). Default 변경은 결제 권리·정산 조건을 바꾸지 않음(초기 비상속 규칙 그대로).
- 대안: Default 종료 전 교체 강제 / 가장 오래된 MID 자동 승격.
- 영향: default_designation UPDATE 권한·이력, Default를 쓰는 소비자(현재 없음).
- 막히는 부분: Default 교체·해제 명령.

### 묶음 C — MASTER-SNAPSHOT-001(결제 허용 판정) 전에 필요

**Q-MD-08 ADDITIONAL 공유의 증거·승인과 개통·Site 심사 적용 범위** — **답변: 권장안 채택(D-MD-016, 2026-10-13)** — 구현 MASTER-SNAPSHOT-001(Site 적용 확인·철회 기록, Site 심사 상태 D-MS-002). ADDITIONAL 생성 명령은 MASTER-LIFECYCLE-001
- 질문: 구조 §12는 Phase 1 개통을 MID 단위로 두지만, 구조 경계 §1·초기 §v0.1은 "추가 Site는 개통을 자동 상속하지 않고 기관 승인 근거가 그 Site를 포함하는지 확인"을 요구한다. MID 단위 개통에서 ADDITIONAL Site의 결제 허용을 무엇으로 증명하는가? 다섯 공유 조건 중 "외부기관 심사 허용"·"동일 Activation 적용 가능"의 증거는 누가 기록하는가? "Site 심사"(구조 경계 §1)는 PG 자체 심사인가 기관 심사인가?
- 권장안: 개통은 MID 단위 유지, 대신 **Site별 개통 적용 확인 기록**(MID, Site, 결제수단, 기관 근거 참조, 행위자) append-only를 두고 판정은 "Assignment 유효 ∧ Activation 유효 ∧ 해당 Site 적용 확인 있음". PRIMARY는 개통 승인 시 함께 기록, ADDITIONAL은 기록 전 결제 차단(fail-closed). ADDITIONAL 생성 명령은 다섯 조건 증거(같은 CSA·정산 정책 동일 여부·기관 근거 참조)를 받는다. Site 심사는 이 기록과 별개인 PG 심사 상태로 MerchantSite에 둔다.
- 대안: (a) Site Scope 개통을 지금 도입(개통 키 = MID+수단+Site). (b) 1단계 운영은 ADDITIONAL 금지(Site별 전용 MID), 공유는 후속.
- 영향: 개통·Assignment 표, METHOD_NOT_ENABLED 판정, 공유 명령.
- 막히는 부분: ADDITIONAL 생성, Activation 판정.

**Q-MD-09 PaymentMethodActivation 단위·상태** — **답변: 권장안 채택(D-MD-017, 2026-10-13)** — 구현 MASTER-SNAPSHOT-001(상태 4종, 전이 D-MS-003, 잠정 UNIQUE m-5는 V3에서 "종료 아닌 행 하나" 생성 컬럼 UNIQUE로 대체)
- 질문: 개통 상태 집합(예: UNDER_REVIEW/ACTIVE/SUSPENDED/TERMINATED)과 "카드 일부 개통"(허용 §4.1-6)의 카드사 단위를 MASTER Activation에 둘지, AUTH의 Route 기준정보에 둘지?
- 권장안: MASTER Activation = (MID, 결제수단) + 상태 4종 + 근거 참조. 카드사/Route별 사용 가능 목록은 기관 기준정보(MASTER, 후속 카드)로 두고 승인 시 판정(AUTH-CONTRACT-001). CreatePayment eligibility는 수단 단위만 본다.
- 대안: Activation 키에 카드사 포함(MID+CARD+카드사).
- 영향: Activation 표, METHOD_NOT_ENABLED, AUTH Route 판정.
- 막히는 부분: Activation 상태 전이. Q-AUTH-SCOPE와 연결.

**Q-MD-10 통화 활성화 기록 위치** — **답변: 권장안 채택(D-MD-018, 2026-10-13)** — 구현 MASTER-SNAPSHOT-001(`currency_unit`·`currency_enablement`). (CSA, MID, 통화) 정책 Assignment 요구는 AUTH-001
- 질문: D-CP-002 "허용 통화는 MID·기관 계약별 기준정보로 활성화"와 D-MD-002 "통화는 Assignment 범위(CSA+MID+Currency)"를 합쳐, 통화 허용은 (a) 별도 통화 활성화 기록(MID, 통화, 기간, 근거)인가, (b) 해당 (CSA, MID, 통화)의 수수료·정산 정책 Assignment가 존재하면 허용인가?
- 권장안: (a) 별도 명시 기록(신규 결제 허용 판단 CURRENCY_NOT_ENABLED의 근거) + 승인 시작에는 (CSA, MID, 통화) 정책 Assignment 존재도 요구(허용 §4.1-9). 둘 중 하나만으로 허용하지 않음.
- 대안: (b) 정책 Assignment 존재로 갈음.
- 영향: 통화 표, Snapshot, 정책 작업 순서(정책 작업 전에도 CreatePayment 판정 가능 여부).
- 막히는 부분: 통화 활성화 명령, CURRENCY_NOT_ENABLED 판정.

**Q-MD-11 등록 중복 기준(Business·Site)** — **답변: 권장안 채택(D-MD-008, 2026-10-09)**
- 질문: 같은 사업자등록번호로 Business를 둘 만들 수 있는가? 같은 채널 식별값(도메인·앱 ID)의 Site가 다른 Merchant에 존재할 수 있는가? (착수검토 §2 "신규 등록 중복")
- 권장안: Business는 국내 사업자등록번호 기준 하나(UNIQUE), Site 채널 식별값은 같은 Merchant 안에서 활성 중복 금지, 다른 Merchant 간 같은 값은 등록 시 거절하고 예외는 승인 기록으로.
- 대안: 중복 허용 + 검토 큐 / Site 중복 검사 없음.
- 영향: UNIQUE 제약, 부정가맹점 위험, 등록번호 저장·노출 통제(A05).
- 막히는 부분: 중복 검사·UNIQUE. 계속 가능: 식별자만 UNIQUE로 등록 시험.

### 기존 질문과의 연결

- **Q-ADMIN-AUTH**(PROPOSED): 관리 명령의 행위자 신원·권한·2인 승인. 이번 작업은 web adapter를 만들지 않고 `actor`(불투명 운영자 참조)를 명령 입력으로 받아 기록만 한다.
- **Q-AUTH-SCOPE**: 서비스 종류 값(체크카드·할부)과 Q-MD-09.
- **Q-SETTLE-CURRENCY**: Q-MD-10과 별개(정산 통화).

---

## 6. 가역적 구현 관례(사용자 질문 아님)

| 항목 | 선택 | 이유 |
|---|---|---|
| 모듈 | `com.paycore.master.merchant` 하나(모든 Aggregate). 정책 정의는 후속 `com.paycore.master.policy` | 최초 Default 발급이 MID·Default·Assignment·Contract 확인을 한 TX에서 하며, 모듈을 나누면 Modulith가 domain 공유를 막아 provided DTO 복사가 필요해진다(payment 모듈 결정과 같은 이유, 관례 §7) |
| 계층 | `domain` / `application`(provided·required) / `adapter.persistence`·`adapter.query`. web 없음 | 관례 §2, Q-ADMIN-AUTH |
| 식별자 형식 | 서버 생성 `<prefix>_` + ULID 26자(Crockford 대문자): `biz_`, `mer_`, `site_`, `mid_`, `ctr_`, `csa_`, `asg_`, `act_`. 모두 ASCII 30자 이하, `utf8mb4_0900_bin`, VARCHAR(64) | CP계약 §12의 MID·Site 64자 후보와 PAYMENT 값 객체(빈 값·서로게이트 거부)에 맞음. 순번을 드러내지 않음. 형식은 **확장만** 허용(기존 키 복원). MID·Site는 가맹점에 노출되는 값이라 사람이 읽기 쉬운 형식을 원하면 Coordinator가 사용자 선호를 확인할 수 있다(금융 의미 없음) |
| ID 생성 | `application.required` `MasterIdGenerator` Port, 구현은 adapter | domain 순수성(시계·난수 Port 주입, 관례 §3 "강제하지 않는 것") |
| 시계 | application.required `MasterClock` Port(운영 `SystemMasterClock`, 시험 `MutableMasterClock`). 명령 시각은 요청이 아니라 이 Port에서 오고 ms로 자른다. 명령 입력 기간은 `EffectivePeriod.ofMillis`(ms 버림, 비면 EFFECTIVE_PERIOD_EMPTY) | review m-3·m-4. 시각은 fingerprint 대상이 아니라 요청 필드에서 뺐다 |
| Domain 스타일 | 정적 factory + private set + 전이 메서드, `reconstitute`는 Mapper 전용(저장 상태 검증), 업무 거절 예외 `MasterRuleViolation(MasterRejection, reference)`, 잘못된 인자는 `IllegalArgumentException` | 관례 §7 |
| migration | `db/migration/master/V1__master_core.sql`, 권한 `db/runtime-privilege/master/V2__master_runtime_privileges.sql`(`CALL ${flyway:database}_privilege.set_runtime_table_privileges`), 시험 전용 V900/V901 유지 | 관례 §9.2, AC 4 |
| TX | `LocalTransactions`(PlatformTransactionManager 직접), READ COMMITTED, 교착 재시도 3회 | 관례 §9 |
| 최초 insert 경쟁 | admin_request는 IODKU 후 FOR UPDATE | 관례 §9 |
| 시험 | Kotest BehaviorSpec, Named Fixture(`MerchantFixture`, `ContractFixture`, `MerchantAccountFixture` — src/test만), Fixture Monkey는 Repository 대량 시험에서만 프로젝트 Fixture 뒤에. 실제 MySQL은 `MasterMySqlContainerConfig`(runtime 계정·init 스크립트) | Test Fixture Policy, 관례 §9 |
| Demo 값 | 없음(정책 Seed 범위 밖). 시험 값(등록번호·host)은 시험 전용 무작위 값 | 구조 경계 §3 |

## 7. 출처 간 충돌·공백(Coordinator 확인용)

1. **개통 단위 긴장**: 구조 §12 "Phase 1은 MID 단위로 시작" ↔ 구조 경계 §1·초기 §v0.1 "추가 Site는 개통 자동 상속 없음, 기관 근거가 Site 포함 확인". MID 단위 개통만으로는 ADDITIONAL Site 허용을 증명할 수 없다 → Q-MD-08.
2. **Eligibility reason 공백**: CP계약 §13의 NotEligible reason은 고정 4종(D-CPC-008 연계, 한도 없음)인데 허용 §4.1-3 "MID 운영 중지"에 대응하는 reason이 없다(MERCHANT_SUSPENDED로 합칠지, NotFoundOrForbidden으로 볼지). MASTER-SNAPSHOT-001에서 계약 개정 또는 매핑 결정 필요(이번 작업은 MID 상태만 저장).
3. **통화 활성화 위치**: D-CP-002 "MID·기관 계약별 기준정보" ↔ D-MD-002 "Assignment 범위 CSA+MID+Currency" → Q-MD-10.
4. **선지급 정책 Scope 모양 차이**(이번 범위 밖, SETTLEMENT-CONTRACT-001 참고): 정산 §11 "CSA 또는 명시적 MerchantAccount Override" ↔ D-MD-002/정산지급 상세권장안(채택 전) "CSA+MID+Currency Assignment". 정산 결정 C는 Override와 Assignment가 하나로 수렴해야 한다고만 한다.
5. **"Production MID"와 TEST 환경**: A10 INV-17·구조 §7 ↔ D-CPD-001 environment 축 → Q-MD-01.
6. **"Contract ACTIVE"/"Contract valid" 표현**(구조 §11, 정산 §12): 구조 경계 §1이 상태 enum이 아니라 유효 조건 표현이라고 정리했으므로 충돌은 아니다. 코드에서 `ACTIVE` 계약 상태를 만들지 않고 "승인 ∧ 효력 기간 ∧ 철회 없음" 판정으로 구현한다.
7. **지급계좌 준비(허용 §4.1-10)**: MASTER 데이터(A05 예시 흐름)지만 보안·민감정보 계약이 없어 이번 범위 밖. LIVE 허용 전 별도 작업 필요.
8. **대안 문서의 Default/추가 MID/PRIMARY 규칙**은 구조 경계 §2가 자동 채택하지 않았다고 명시했다. 이 문서는 그 내용을 권장안으로만 인용했다(Q-MD-05~07).

## 8. 구현 단계(TDD 순서)와 시험 영역 추정

각 단계는 RED(실패 시험 먼저) → 최소 구현 → 관련 검증. "막힘"은 답이 필요한 질문.

| 단계 | 내용 | 막힘 | 시험 영역(추정 건수) |
|---|---|---|---|
| S0 | ArchitectureSpec의 master 앱 규칙 빈 대상 허용 끄기(첫 업무 클래스), Modulith 모듈 인식 | 없음 | 아키텍처 2–3 |
| S1 | 값 객체·식별자(형식 검사, 서로게이트 거부), Scope, Role, ServiceKind, Revision 번호 | 없음 | domain 15–20 |
| S2 | Aggregate·domain service: Business/Merchant/Site 등록, Contract+Revision 승인 사실, CSA 유일성, `MerchantAccountIssuance.issueFirstDefault` 확정 규칙 전부(§2.3 거절 목록) | Q-MD-01, Q-MD-02(당사자·Revision), Q-MD-03(기간 타입) | domain 35–50 |
| S3 | `V1__master_core.sql` + `V2` 권한 + flyway.locations + `MainConfigurationSpec`·`MasterDatabaseAccountSpec`(표 목록·권한 고정, append-only UPDATE/DELETE 1142) + schema 시험(collation, 복합 FK로 다른 Merchant Assignment INSERT 실패, 생성 컬럼 UNIQUE, CHECK) | S2와 같음 | DB 20–25 |
| S4 | persistence adapter·Mapper·Store(`reconstitute` 검증, version 비교, flush 내 충돌 분류) | 없음(S3 후) | adapter 10–15 |
| S5 | `IssueFirstDefaultMerchantAccountUseCase` + admin_request + Outbox: 재생·충돌·거절 재생, Port별 장애 주입 rollback, 다른 키 8개 동시 최초 발급 1건, commit 중 끊김 분류, 교착 재시도 | 없음(S4 후) | application·통합 15–20 |
| S6 | 나머지 "이번" use case(등록·계약·Activation 최소) + 이력·Outbox | Q-MD-09(Activation 상태는 최소만) | application 10–15 |
| S7 | jOOQ codegen(권한 위치 제외) + `MerchantStructureQuery` | 없음 | query 3–5 |
| S8 | 로컬 compose master-api 기동 복구: runtime 계정으로 컨텍스트 기동 smoke(표 권한 있음), 절차 기록 | 없음 | smoke 1–2 |

합계 추정 110–155건(현재 저장소 625건 기준 증가분). Verifier는 VERIFY-GATE(master-api 포함)·TEST-SUMMARY·GRADLE-DEPS(빌드 파일 변경 시)·CVE를 같은 source fingerprint로 실행한다. 이 1단계 문서에는 실행 증거가 없다.

### 답변이 없을 때 계속할 수 있는 것

S0·S1 전체, S2의 Business·Merchant·Site·CSA 유일성·"같은 Merchant"·"승인 Revision만 근거"·"MID 있으면 최초 발급 거절" 규칙(확정만). schema(S3)는 묶음 A 답변 뒤 작성하는 것을 권장한다(적용된 migration은 고치지 않는 관례라 잠정 schema는 되돌리기 비용이 크다).

---

## 9. 2단계 구현 기록(2026-10-09, Implementer — Verifier/Reviewer 판정 전)

반영한 결정: D-MD-001~014(review round 1에서 D-MD-009~014 추가). 미결 Q-MD-06~10·Q-MD-18·Q-ADMIN-AUTH는 구현하지 않았다(ADDITIONAL 생성·Default 교체·추가 MID·개통 상태·통화 활성화 명령 없음, 관리자 web/API 없음).

### 9.1 코드

| 영역 | 위치 | 내용 |
|---|---|---|
| migration | `apps/master-api/src/main/resources/db/migration/master/V1__master_core.sql` | 15개 표(§3.4 제약: 복합 FK·생성 컬럼 UNIQUE·형식 CHECK·소급 금지 CHECK). 모든 문자열 `utf8mb4_0900_bin` |
| 권한 | `db/runtime-privilege/master/V2__master_runtime_privileges.sql` | 표별 runtime 권한(§3.5, 실제 코드 쓰기 경로만, DELETE 없음) |
| domain | `com.paycore.master.merchant.domain` | 식별자·`DisplayName`·`BusinessRegistrationNumber`(마스킹)·`SiteChannel`(소문자 정규형), Business/Merchant/MerchantSite, Contract(Revision·Scope·승인·CSA), MerchantAccount·DefaultDesignation·SiteAssignment·`SiteAssignmentRules`(D-MD-005·007), PaymentMethodActivation(최소), `MerchantAccountIssuance.issueFirstDefault`, `MasterRejection`/`MasterRuleViolation` |
| application | `…merchant.application(.provided/.required)` | `MerchantOnboardingUseCase` 9개 명령, `AdminCommandExecutor`(멱등·판정 후 쓰기·재시도·이력+Outbox), `LocalTransactions`, `RandomMasterIdGenerator`, `MerchantStructureQuery` |
| persistence | `…merchant.adapter.persistence` | Entity 15개·Spring Data repository·Mapper·Store(`refresh(PESSIMISTIC_WRITE)` 잠금 읽기)·`PersistenceFailures`(제약 이름 → 의미) |
| query | `…merchant.adapter.query.JooqMerchantStructureReader` | jOOQ 조회(생성 코드 `com.paycore.master.jooq.generated`, 업무 schema만으로 codegen) |
| build | `apps/master-api/build.gradle.kts` | jOOQ codegen source set·`generateJooqMain`(권한 위치 제외), Fixture Monkey(test 전용, catalog 기존 버전), jqwik 엔진 제외. runtime 의존성 추가 없음 |

### 9.2 RED → GREEN

| 단계 | RED(실행 결과) | 이유 | GREEN |
|---|---|---|---|
| RED-1 schema·권한·설정·아키텍처 | `:apps:master-api:test` 69건 중 31건 실패(V1·V2를 일시적으로 빼고 실행) | 표·권한·flyway 위치·모듈 없음 | V1·V2·application.yml·모듈 추가 후 통과. 주의: RED-1에서 collation 검사·DELETE 1142 검사는 표가 없어도 통과했다(빈 집합·권한 없음) — 같은 spec의 "표 존재" 시험이 그 공허 통과를 막는다 |
| RED-2 domain | `compileTestKotlin` 실패(오류 370건: domain 타입 없음) | 컴파일 단계 RED(값 비교 RED 아님) | domain 구현 후 domain spec 44건 통과 |
| RED-3 조회 시각 | `MerchantStructureQuerySpec` 실패: 02:00Z가 11:00Z로 읽힘 | jOOQ 생성 `LocalDateTime`이 JVM 시간대(Asia/Seoul) 벽시계 — 기존 관례 문서의 "UTC 벽시계" 안내가 틀렸음 | `DATE_FORMAT` 문자열 → `Instant.parse`, ARCHITECTURE-CONVENTIONS §9.1 정정 |
| RED-4 설정 | `MainConfigurationSpec` 실패(운영 flyway 위치에 권한 위치 없음) → `MasterApiMainMigrationsBootSpec` 기동 실패 | application.yml 미갱신 | 위치 추가 후 통과(1044 해소 증거) |

최종 실행(전체 `--no-build-cache --rerun-tasks check`, not-before 2026-10-09T05:48:20Z): BUILD SUCCESSFUL, TEST-SUMMARY PASS 720건(실패·오류·skip 0). 모듈별 master-api 143 / payment-api 410 / financial-batch 26 / internal-admin 29 / libs/test-support 112. HARNESS-SELFTEST Ran 68 OK. GRADLE-DEPS-MASTER-API exit 0, runtime tree에 Fixture Monkey·jqwik·Testcontainers·Kotest 없음. `commands.json` 최소 시험 수 625 → 720(올리기만).

### 9.3 8대 원칙 증거

| 원칙 | 증거(시험) |
|---|---|
| Financial Consistency(관계 정합성) | `MasterSchemaSpec`(복합 FK 1452·UNIQUE 1062·CHECK 3819), domain spec(같은 Merchant·당사자·승인 Revision·Scope) |
| Transaction Safety | `FirstDefaultIssuanceAtomicitySpec`: 6개 저장 지점 flush 뒤 실패 + beforeCommit 실패 → MID·Default·PRIMARY·이력·Outbox·요청 기록·entitlement 모두 원상, 같은 키 재요청 정상 |
| Idempotency | 같은 키·같은 내용 재생(행 증가 없음), 다른 내용 IdempotencyKeyReused, 거절 저장·재생, fingerprint 경계 시험 |
| Concurrency Control | 다른 키 8개 동시 최초 발급 → 1건 + 7건 DEFAULT_ALREADY_ISSUED(잠금 대기 7건 관찰), 같은 키 8개 동시 → 1회 실행, 같은 등록번호 8개 동시 → 1건(UNIQUE 경합 → 재판정 수렴), 같은 키 잠금 timeout → RequestInProgress |
| Recoverability | commit 단계 실패 → OutcomeUnknown(같은 키로 확인), 재요청 성공 |
| Reconciliation | 해당 적음(금융 사실 없음). Outbox 행 수 = 변경 수 확인. MASTER↔PAYMENT 대사는 MASTER-SNAPSHOT-001 |
| Auditability | `master_change_history`(행위자·요청 ID·변경 후 값, 등록번호 마스킹만), 승인자 기록, append-only 권한 1142 |
| Observability | 이번 범위 지표 없음(Relay·소비자 없음) — MASTER-SNAPSHOT-001로 이월. 예외·toString에 등록번호·키 값 없음 |

### 9.4 이월·후속

- MASTER-SNAPSHOT-001: Outbox Relay(UPDATE 권한 추가), Snapshot 계약(`entitlement_revision` 사용 여부 확정), MID 운영 중지 reason 매핑(§7-2), 통화 단위 기준정보(Q-MD-10).
- MASTER-LIFECYCLE-001: 차단 기록 표·종료·PRIMARY 교체·철회/재활성화(D-MD-004~007) — 해당 표에 UPDATE 권한 migration 추가.
- Site 채널 중복의 "예외 승인 기록"(D-MD-008)은 표·명령이 없다(현재는 전부 거절 — fail-closed).
- SITES Scope는 한 Merchant의 Site만(D-MD-010), 한 계약의 모든 Revision도 같은 Merchant(D-MD-013) — 계약 행 `merchant_id`와 복합 FK로 강제.
- payment-api에 jOOQ 시각 조회가 생기면 §9.1 정정(DATE_FORMAT 방식)을 같이 적용.

### 9.5 review round 1 수정(2026-10-10, Implementer — Verifier/Reviewer 판정 전)

반영: D-MD-009~014(D-MD-009·010은 기존 구현과 같아 문서만), Q-MD-18은 MASTER-LIFECYCLE-001 대상이라 구현하지 않음.

| 지적 | 변경 | 시험 |
|---|---|---|
| M-1 | `Contract.applicableRevisionAt`(D-MD-011), `issuingBasis`는 발급 시각의 적용 Revision만(D-MD-012, REVISION_NOT_APPLICABLE), `Contract.merchantId`·`registerRevision` 대상 Merchant 고정(D-MD-013, CONTRACT_MERCHANT_MISMATCH, V1 `contract.merchant_id` + 복합 FK 2개), 끝난 Revision 승인 거절(D-MD-014, REVISION_ENDED), 발급 시 계약 대상 Merchant 확인(m-10) | ContractSpec(적용 Revision 표·리뷰 재현·D-MD-013·014·복원 가드), MerchantAccountIssuanceSpec(m-10), MerchantOnboardingServiceSpec(리뷰 재현 3건·D-MD-013·014), MasterSchemaSpec(FK 1452 2건) |
| M-2 | `application.yml` `logging.level.org.hibernate.orm.jdbc.error`·`SqlExceptionHelper` = OFF(Hibernate 7은 1062 원문 `Duplicate entry '<값>'`을 `org.hibernate.orm.jdbc.error`로 남겼다 — RED에서 확인) | 등록번호·채널·서버 ID 경합/충돌 중 로그 수집(Logback ListAppender, root) → 값 포함 줄 0건, MainConfigurationSpec 설정 고정 |
| M-3 | — (동작 변경 없음, 결정적 시험 추가) | 등록번호 경합: 첫 TX를 insert 직후 붙잡고 두 번째 TX가 data_lock_waits에 들어간 뒤 놓음 → 두 번째 INSERT의 1062가 UniqueConflict(BUSINESS_REGISTRATION_NUMBER)로 기록되고 재시도가 거절로 수렴. 다른 Merchant 같은 WEB 채널 경합(SITE_ACTIVE_CHANNEL) 같은 방식. Deadlock·OptimisticConflict 1회 주입 → 2회째 성공(시도 수 2). 매번 Deadlock → 3회 후 TemporarilyUnavailable·저장 없음. 서버 ID 강제 충돌 → IdentifierCollision("IDENTIFIER")·저장 없음·같은 키 재요청은 새 ID로 성공 |
| m-1 | — | MerchantStructureQuerySpec: JVM 기본 시간대 Asia/Seoul로 바꿔 조회 → 저장 UTC 그대로, 대조군(생성 LocalDateTime + ZoneOffset.UTC)은 +9시간 |
| m-2 | `JooqMerchantStructureReader.read`를 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`로 한 스냅샷에서 6개 문장 실행 | jOOQ ExecuteListener(시험 전용)로 6개 문장 모두 실제 TX·읽기 전용·REPEATABLE READ 확인 |
| m-3 | application.required `MasterClock`(운영 `SystemMasterClock`), `AdminRequest.requestedAt` 삭제 — 시각은 fingerprint 대상이 아니라 요청에서 받을 이유가 없다(결정·기록) | 시험은 `MutableMasterClock`(@Primary)로 시각을 옮겨 리뷰 재현·종료 Revision 승인 거절을 만든다 |
| m-4 | `EffectivePeriod.ofMillis`(ms 버림, 비면 EFFECTIVE_PERIOD_EMPTY 업무 거절), 기간 fingerprint를 저장 정밀도로 | 0.1~0.4ms 기간 → 저장된 거절, 같은 ms 안 다른 표기 같은 키 → 재생(replayed), ms 미만 입력은 ms로 버려 저장 |
| m-5 | V1·명세·관례에 UNIQUE(MID, 결제수단)·ACTIVATION_DUPLICATE가 Q-MD-09 전 **잠정**이며 답에 따라 새 버전 migration이 필요함을 명시 | — |
| m-6 | 문서·코드 정합(§3.1 비잠금 Site 읽기, §3.3 증가 지점 3개, §6 예외·Clock, §4.1 복원 검증, V1 헤더 D-MD-001~014, SiteChannel KDoc), `MerchantAccount`·`SiteAssignment`·`Contract.reconstitute` 검증 추가(소급·끝 없는 ENDED·Revision 1 미만·Revision Merchant 불일치), V1 CHECK(ENDED → 종료 시각) | MasterReconstitutionSpec, MasterSchemaSpec(3819) |
| m-8 | `AdminCommandExecutor` WARN 구조화 로그: 결과 불명확·재시도 소진·서버 ID 충돌(operation·correlationId·종류만) | 로그 줄 정확 일치 + 민감값 없음 |
| NIT | `MerchantStructureQueryService` 별도 파일, `IdentifierCollision(kind)`·충돌 종류별 분류(서버 ID만 IdentifierCollision, 그 밖은 TemporarilyUnavailable), WEB IPv4 리터럴 거절(domain + V1 CHECK) | MasterValuesSpec, MasterSchemaSpec |

RED: 새 규칙을 하나씩 되돌린 변이(mutation) 상태로 `:apps:master-api:test` 실행 → 172건 중 20건 실패(D-MD-011~014·m-10·ms 경계·IP 리터럴·복원 검증·읽기 TX). M-2는 로그 설정 전 실행에서 등록번호·채널·ID가 `org.hibernate.orm.jdbc.error` 로그로 나와 3건 실패. 변이를 되돌리고 로그 설정 후 1건(문장 수 5 → 실제 6, 시험 기대값을 실제 문장 수로 정정) 외 통과, 정정 후 전체 통과.

최종 실행(전체 `--no-build-cache --rerun-tasks check`, not-before 2026-10-10T07:06:54Z): BUILD SUCCESSFUL, TEST-SUMMARY PASS 750건(실패·오류·skip 0). 모듈별 master-api 173 / payment-api 410 / financial-batch 26 / internal-admin 29 / libs/test-support 112. HARNESS-SELFTEST Ran 68 OK. GRADLE-DEPS-MASTER-API exit 0(runtime에 시험 도구 없음). `commands.json` 하한 720 → 750.

**Q-MD-19 → D-MD-015(2026-10-11, 권장안 채택)**: 발급 시각에 적용 Revision이 있으면 그것만(D-MD-012), 없으면(효력 시작 전) 승인됐고 아직 시작 전이며
자기 시작 시각에 적용될 Revision(시작 시각을 포함하는 승인 Revision 중 번호 최대가 자기 자신)만 선발급 근거로 허용한다. 결제는 효력 전까지 계속 차단된다
(발급 ≠ 개통·결제 허용). 반영은 §9.6.

### 9.6 D-MD-015 반영(2026-10-11, Implementer — Verifier/Reviewer 판정 전)

- 코드: `Contract.issuingBasis` — 적용 Revision이 있으면 그 번호만, 없으면 `start > now && applicableRevisionAt(start) == 인용 Revision`. 거절은
  REVISION_NOT_APPLICABLE(미승인은 REVISION_NOT_APPROVED, 종료는 REVISION_ENDED가 먼저).
- 시험: 선발급 시험 2건을 "허용"으로 되돌림(ContractSpec·MerchantAccountIssuanceSpec, D-MD-015 이름), 추가 — (1) 시작 시각에 적용될 미래 Revision → 허용,
  (2) 같은/더 이른 시작의 더 큰 번호 승인 Revision에 가려진 미래 Revision → 거절(가리는 쪽은 허용), (3) 지금 적용 Revision이 있을 때 미래 Revision 인용 → 거절,
  (4) 미승인 미래 Revision → 거절, 서비스 통합(효력 하루 뒤 Revision만 있는 Merchant의 최초 Default 발급 → MID·Default·PRIMARY 각 1행, 근거 Revision 1).
- RED: 시험 먼저 실행(`:apps:master-api:test`) → 178건 중 5건 실패(허용 기대 4건 + 가리는 쪽 허용 1건, 기존 코드는 선발급을 모두 거절). (3)(4)는 기존 동작과 같아 통과.
- GREEN·최종 실행(전체 `--no-build-cache --rerun-tasks check`, not-before 2026-10-11T01:29:45Z): BUILD SUCCESSFUL, TEST-SUMMARY PASS 755건
  (실패·오류·skip 0). 모듈별 master-api 178 / payment-api 410 / financial-batch 26 / internal-admin 29 / libs/test-support 112. HARNESS-SELFTEST Ran 68 OK.
  GRADLE-DEPS-MASTER-API exit 0(runtime에 시험 도구 없음). `commands.json` 하한 750 → 755.
