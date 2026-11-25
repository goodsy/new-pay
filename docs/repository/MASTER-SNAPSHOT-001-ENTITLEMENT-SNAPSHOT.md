# MASTER-SNAPSHOT-001 권리 Snapshot·lease·차단 전파 명세 (1단계 명세 → 2단계 구현 반영)

> **2단계 갱신(2026-10-13)**: Q-MS-01~04는 모두 권장안으로 답변됐다(D-MS-001~004). 아래 "답 전에는…" 임시 처리 문구는 확정 결정으로 바꿨고, 2단계 TDD 구현이 반영됐다. 구현 중 편차는 §15에 적었다.

작성 2026-10-13 KST(1단계 명세, 코드 변경 없음). 기준 commit `6e2a776`(MASTER-DOMAIN-001 DONE). 작업 트리에는 Coordinator 소유 상태 파일(`docs/DECISIONS.md`·`harness/*.json`) 미커밋 변경이 있으며 이 문서는 그것을 읽기만 했다. 이 문서는 새 업무 정책을 채택하지 않는다 — §9 질문(Q-MS-01~04)은 답변 전 해당 운영 동작을 구현하지 않는다. 2단계 TDD 구현은 Coordinator가 질문 답변을 받은 뒤 시작한다.

표시: **확정(출처)** = 채택 문서 또는 D-* 결정, **해석** = 확정 결정에서 직접 따라 나오는 적용(Coordinator 확인 대상, 질문 아님), **관례** = 가역적 구현 선택(§10), **미결(Q-MS-xx)** = §9 질문 후보, **Demo** = Simulator/시험 전용 값(운영값 아님, §8).

입력 문서 hash(sha256, 2026-10-13 읽은 시점):

| 약어 | 문서 | 채택 상태 | sha256 |
|---|---|---|---|
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` | 확정(§4·§5) | `a8b02b77…37ae5` |
| 구조 / 구조 경계 | `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md` 본문·"기존 결정 연결 및 구현 경계" | 확정 | (MASTER-DOMAIN-001 명세와 같음 `9d237eb9…81fb`) |
| 초기 | `docs/baseline/PayCore-초기-확정사항.md` | 확정 | — |
| CP계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 | `d1ab2b0c…b431` |
| MD명세 | `docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md` | 구현 완료 명세 | `d1a5d2e5…41c` |
| 결정 | `docs/DECISIONS.md`(D-MD-001~019, D-CP-*, D-CPC-*, D-CPD-*) | 확정 | `dd3be30a…795`(미커밋 작업 트리) |
| A02·A03·A06·A07·A10 | `docs/baseline/architecture/02·03·06·07·10` | 아키텍처 자료 | — |
| ADR-001 | `docs/repository/ADR-001-KAFKA-REDIS-DEFERRAL.md` | 미도입 채택, relay 방식은 제안 | — |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md` §3·§9·§9.1·§9.2·§10 | 관례 | — |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | — |

---

## 1. 근거 표(규칙 → 출처 → 상태)

| # | 규칙 | 출처 | 상태 |
|---|---|---|---|
| R1 | 평상시 승인 요청마다 MASTER 동기 조회 금지. PAYMENT 로컬 Snapshot + 만료 있는 lease 사용 | 허용 §4.2 첫째 줄, CP계약 §13("MASTER DB 직접 조회 금지") | 확정 |
| R2 | 권리 차단 버전은 늦게 도착한 과거 허용 이벤트로 되돌릴 수 없다. 재승인은 새 버전과 재검증 | 허용 §4.2 둘째 줄 | 확정 |
| R3 | 만료 권리·필수 Snapshot 누락·확인 불가 버전 = 신규 승인 차단. MASTER 단절은 유효 lease 동안만 제한 허용 | 허용 §4.2 셋째 줄, §4.1-12 | 확정 |
| R4 | lease 갱신은 MASTER 최신 권리와 연결, 철회 이후 기존 권리를 다시 갱신하지 않음 | 허용 §4.2 넷째 줄 | 확정 |
| R5 | 긴급 MID/키 차단 우선 전파, PAYMENT 각 처리 인스턴스의 적용 ACK 관측, 신규/재시작 인스턴스는 현재 차단 상태 적재 전 트래픽 금지 | 허용 §4.2 다섯째 줄 | 확정(키 차단은 Credential 작업 — §11) |
| R6 | 철회 접수 / MASTER 차단 커밋 / 전체 효력 확인을 구분, 전체 효력은 ACK 또는 기존 lease 만료 확인 후 기록. 이벤트 발행 ≠ 전역 차단 완료 | 허용 §4.2 여섯째 줄 | 확정 |
| R7 | 전파 지연·lease·시계 오차·갱신 주기·ACK timeout 수치 미확정, Simulator는 짧은 명시 fixture, 개발 fixture를 운영 기본값으로 쓰지 않음 | 허용 §4.2 마지막 문단, 구조 경계 §3 | 확정(수치는 미결) |
| R8 | 판정 소유자는 PAYMENT, MASTER는 원본과 버전 있는 권리 근거 발행, 판정 입력·권리 버전·판정 시각을 Attempt에 연결 | 허용 §4.1 마지막 문단, CP계약 §13(snapshotVersion 보존) | 확정 |
| R9 | Outbox(업무 효과와 같은 로컬 TX) → 전달(at-least-once) → Inbox(업무 반영과 같은 로컬 TX), 전송 성공 = 수신측 내구 저장 확인, 응답 유실 시 같은 eventId 재전송, 메모리 큐·단순 HTTP 성공은 대체 불가 | A03 핵심 규칙, A02 TX 경계, CLAUDE.md 핵심 규칙 | 확정 |
| R10 | cross-DB 직접 SQL/Entity 금지(같은 프로세스여도), Kafka/Redis 초기 미도입, 도입해도 금융 안전성을 대체하지 않음 | 관례 §1, D-ENV-001, ADR-001 §1·§2, 원칙 §5 | 확정 |
| R11 | EligibilityResult 모양: Eligible(snapshotVersion, currencyUnit, validUntil) / NotFoundOrForbidden / NotEligible 고정 4종 / Unavailable 3종(fail-closed), 한도 reason 없음 | CP계약 §13, D-CPC-006·008 | 확정 |
| R12 | Port의 validUntil == evaluatedAt 처리는 서비스 방어(만료 간주)와 같거나 더 엄격 | tasks.json AC(CP-DB-001), 관례 §9 eligibility 행 | 확정 |
| R13 | 결제 허용 = Assignment 유효 ∧ Activation 유효 ∧ 해당 Site 적용 확인 있음. Site 적용 확인(MID, Site, 결제수단, 기관 근거 참조, 행위자) append-only. PRIMARY는 개통 승인 시 함께 기록, ADDITIONAL은 기록 전 차단. Site 심사는 별개 PG 심사 상태로 MerchantSite에 | D-MD-016, D-MS-002(a) | 확정 |
| R14 | Activation = (MID, 결제수단) + UNDER_REVIEW/ACTIVE/SUSPENDED/TERMINATED + 근거 참조. 카드사/Route는 AUTH. CreatePayment eligibility는 수단 단위만 | D-MD-017, D-MS-003 | 확정 |
| R15 | 통화 활성화 기록(MID, 통화, 기간, 근거)이 CURRENCY_NOT_ENABLED 근거. 승인 시작엔 (CSA, MID, 통화) 정책 Assignment도 필요(AUTH) | D-MD-018, D-CP-002 | 확정 |
| R16 | MID 운영 중지 → NotEligible MERCHANT_SUSPENDED(계약 변경 없음), MID/Merchant 구분은 내부 로그·감사만 | D-MD-019, CP계약 §9 NOT_ELIGIBLE 행("MID 신규 결제 중지·차단") | 확정 |
| R17 | 적용 Revision은 t를 포함하는 승인 Revision 중 번호 최대(명령 없이 시각 경계에서 바뀜), 선발급은 효력 전 결제 차단 | D-MD-011·015, review n-5 | 확정 |
| R18 | Assignment 기간 UTC [from, to), 미래 종료 예약 허용, ACTIVE/SUSPENDED/ENDED. PRIMARY 없이 ADDITIONAL만 유효하면 신규 결제 차단 | D-MD-005·007 | 확정 |
| R19 | 생명주기 ACTIVE/TERMINATED + 별도 신규 결제 차단 기록(여럿 독립, 신규 승인만 막음) | D-MD-006 | 확정(차단 기록 표·명령은 MASTER-LIFECYCLE-001) |
| R20 | MID 하나 = 계약 계정, TEST/LIVE는 Credential·요청 축. TEST는 실기관 전송 안 함. TEST eligibility도 LIVE와 같은 조건 | D-MD-003, D-CPD-001, D-MS-001 | 확정 |
| R21 | append-only 확정 사실 표는 runtime SELECT·INSERT만, 새 쓰기 경로는 새 권한 migration, 적용된 migration 수정 금지 | 관례 §9.2 | 관례 |

---

## 2. AC 대응표(tasks.json MASTER-SNAPSHOT-001)

| AC | 이 명세의 대응 |
|---|---|
| 허용 §4.1–4.2: 버전 있는 Snapshot·만료 lease·차단 우선 전파·ACK, 과거 허용으로 차단 되돌림 금지 | §3.2 버전, §3.3 lease, §3.4 우선 전파·ACK·효력 확인, §3.5 적용 규칙(엄격 증가), §6 판정 |
| MASTER Outbox → PAYMENT Inbox 로컬 TX, 수치 Demo 명시 | §4.6 MASTER TX, §5.1 Inbox TX, §7 전송, §8 Demo 표 |
| PaymentEligibilityPort/EligibilityResult 제공 | §6 판정 순서·매핑, §5.4 Port 구현 위치 |
| (L-11) EligibilityTestConfig 없이 기동 smoke, WiringSpec '0개' → '정확히 1개' | §5.4, §12 S6 |
| validUntil == evaluatedAt 처리 | §6.3 |
| 착수 전 Q-MD-08·09·10 질문 | 답변 완료(D-MD-016~018) — §1 R13~R15. 남은 세부는 Q-MS-02·03 |
| MID 운영 중지 reason | D-MD-019 반영 — §6.2 |
| 지급계좌 준비(허용 §4.1-10) 별도 작업 등록 여부 | §13-1 권장: 별도 작업 등록 |
| outbox_event UPDATE 권한 migration | §4.5 V4 |
| entitlement_revision 채택 결정·구현 | §3.2 채택(근거 포함) |
| MASTER Observability 지표·구조화 로그(OutcomeUnknown·재시도 소진·식별자 충돌) | §11 Observability 행, §12 S8 |
| (n-5) 시각 기반 적용 판정 반영 | §3.1 시각 구간 필드, §3.2, §6.3 validUntil |

---

## 3. Snapshot 계약

### 3.1 단위와 내용

**단위: Merchant 하나의 전체 권리 상태(full-state) Snapshot.** (관례 — 근거: `merchant.entitlement_revision`이 Merchant 행 단위 단조 값이고(MD명세 §3.3), Merchant 행이 Merchant 안 모든 관계 변경의 직렬화 지점이다. 변경분(delta)이 아니라 전체 상태라서 중간 버전 누락이 판정에 영향을 주지 않는다 — §3.5.)

`EntitlementSnapshot` schemaVersion 1(통합 계약, Domain Entity 직렬화 아님 — A03):

| 필드 | 의미 | 근거 |
|---|---|---|
| `merchantId`, `businessId` | 대상 | 구조 §13 |
| `revision` | `merchant.entitlement_revision`(≥ 1) | §3.2 |
| `contentHash` | 권리 내용(아래 lease·발행 필드 제외)의 정규 JSON SHA-256(`ec1:` 접두) | §3.5 무결성 |
| `issuedAt`, `leaseSeq`, `leaseUntil` | 발행 시각(MASTER clock), 같은 revision 안 lease 발행 순번, lease 만료 | §3.3 |
| `party` | `businessLifecycle`, `merchantLifecycle`, `businessBlocked`, `merchantBlocked`(유효 차단 기록 존재 여부, 사유는 넣지 않음) | D-MD-006, R19(차단 기록 표가 생기기 전에는 항상 false) |
| `sites[]` | `siteId`, `lifecycle`, `reviewStatus` | D-MD-016(심사 상태 값은 Q-MS-02) |
| `accounts[]` | `mid`, `lifecycle`, `blocked`, `issuingAgreementId`, `serviceKind` | 초기(발급 근거), D-MD-006·019 |
| `accounts[].assignments[]` | `assignmentId`, `siteId`, `role`, `status`, `effectiveFrom`, `effectiveTo` | D-MD-005·007 |
| `accounts[].contractWindows[]` | `siteId`, `from`, `to?` — 발급 CSA의 계약에서 **적용 Revision(D-MD-011)이 그 Site를 Scope로 포함하는 시각 구간**(철회 기록이 생기면 제외). MASTER가 계산해 넣는다 | D-MD-004·011·015, 허용 §4.1-4·5, n-5 |
| `accounts[].activations[]` | `paymentMethod`, `status` | D-MD-017 |
| `accounts[].siteConfirmations[]` | `siteId`, `paymentMethod` — 유효한 Site 적용 확인 | D-MD-016(철회 표현은 Q-MS-03) |
| `accounts[].currencies[]` | `currency`, `minorUnit`, `from`, `to?` | D-MD-018, D-CP-002 |

넣지 않는 것: 사업자등록번호·표시 이름·채널 식별값·근거 참조 원문·행위자(감사는 MASTER 원본에 있다), Secret, 카드정보. 정책 Assignment(수수료·정산)는 AUTH 단계 schemaVersion 확장 대상(§13).

~~시각 구간은 발행 시각 이전에 끝난 구간을 제외한다(`to > issuedAt`만).~~ **구현 편차(§15-1)**: 지난 구간도 제외하지 않는다 — 발행 시각으로 거르면 같은 revision의 lease 갱신이 다른 내용·다른 contentHash가 되어 PAYMENT가 INTEGRITY_CONFLICT로 막는다. 내용은 MASTER 원본 상태만의 함수다. 미래 구간은 포함한다 — 그래서 적용 Revision·Assignment 종료 예약·통화 기간이 **명령 없이 시각 경계에서 바뀌어도** 재발행 없이 PAYMENT가 판정 시각으로 계산한다(n-5).

### 3.2 버전 — `entitlement_revision` 채택

**결정(관례, MD명세 §3.3 제안 확정): Snapshot 버전 = Merchant별 `entitlement_revision`.** snapshotVersion 문자열 = `es1:<merchantId>:<revision>`(최대 54자, `EligibilitySnapshotVersion` 1..64 printable ASCII에 맞음).

| 판단 | 근거 |
|---|---|
| 채택 | (1) 권리 입력을 바꾸는 MASTER TX 안에서 같은 행을 +1하므로 커밋 순서와 일치하는 단조 값이다(Outbox `id`는 커밋 순서를 보장하지 않는다 — A03). (2) merchant 행 잠금 아래 증가하므로 같은 Merchant의 두 변경이 같은 값을 갖지 않는다. (3) PAYMENT는 "더 큰 revision만 교체"로 R2를 구현한다 |
| 한계와 보완 | revision만으로는 **시각 경계 변화(n-5)**를 표현하지 못한다 → §3.1 시각 구간을 Snapshot에 담고 PAYMENT가 판정 시각으로 계산, Eligible `validUntil`을 다음 경계로 자른다(§6.3). 증가 누락(코드 결함) 위험 → lease 갱신 재발행의 `contentHash` 비교가 같은 revision·다른 내용을 잡아 fail-closed(§3.5) |
| 증가 지점(이 작업 후) | 기존 3곳(Revision 승인·최초 Default 발급·개통 등록) + 신규: **Site 등록**(review M-2 — Site는 `sites[]`·contractWindows에 실린다), 개통 상태 전이, Site 적용 확인(·철회), 통화 활성화·종료, Site 심사 결정. 조립 내용에 영향이 없어 올리지 않는 명령: Business·Merchant 등록(MID 없음), 계약·미승인 Revision 등록·CSA 추가(Snapshot은 MID 발급 계약의 **승인** Revision과 발급 CSA만 싣는다). "조립 hash가 바뀌면 revision도 바뀐다" 매트릭스 시험: `EntitlementRevisionMatrixSpec`. MASTER-LIFECYCLE-001의 차단·종료·연결 변경도 같은 TX에서 +1과 Snapshot 발행을 해야 한다(§13-3, Coordinator가 그 작업 AC에 추가 필요). Business 단위 변경은 그 Business의 모든 Merchant를 id 오름차순으로 잠가 각각 +1 |
| 발행 조건 | revision ≥ 1이고 MID가 1개 이상인 Merchant만(MID 없는 Merchant는 PAYMENT가 물을 일이 없다) |

### 3.3 lease와 validUntil

| 항목 | 규칙 | 성격 |
|---|---|---|
| lease 부여 | 모든 Snapshot 발행(변경·갱신)은 `leaseUntil = issuedAt + leaseDuration`을 담는다 | 관례(수치 Demo §8) |
| 갱신 | MASTER 갱신 작업이 lease 잔여가 `renewBefore`보다 짧은 Merchant마다 **현재 상태로 Snapshot을 다시 만들어** 같은 revision(변경 없으면)·`leaseSeq + 1`로 발행. merchant 행 잠금 + 최근 발행 기록 확인으로 여러 인스턴스의 중복 갱신을 막는다 | R4: 갱신은 언제나 최신 권리로 만든다. 철회·차단 후의 갱신은 차단 상태 Snapshot을 갱신할 뿐 과거 허용을 되살리지 않는다 |
| PAYMENT 적용 | 같은 revision이면 `contentHash`가 같고 `leaseUntil`이 더 길 때만 lease 연장. 더 작은 revision의 갱신은 무시 | R2·R4 |
| 만료 | PAYMENT는 `t ≥ leaseUntil − clockSkewMargin`이면 만료(Unavailable SNAPSHOT_EXPIRED) | R3, 시계 오차는 Demo margin |
| validUntil | Eligible의 `validUntil = min(leaseUntil − clockSkewMargin, 판정에 쓴 시각 구간의 evaluatedAt 이후 첫 경계)` | 관례(더 엄격한 쪽, §6.3) |
| MASTER 단절 | 갱신이 오지 않으면 lease 만료 시점부터 그 Merchant의 생성이 503(fail-closed) | R3 |

### 3.4 차단 우선 전파·ACK·전체 효력 확인

1. **우선순위**: revision이 바뀐 Snapshot(변경 발행)은 `priority = HIGH`, lease 갱신은 `LOW`. Relay는 HIGH를 먼저 집는다(§4.7). 차단만 골라내지 않고 모든 변경을 HIGH로 두는 이유: "권리 축소인지"를 판정하는 별도 로직이 틀릴 위험보다 변경 빈도가 낮다(관례). 커밋 직후 Relay를 깨우는 신호(afterCommit)는 지연 단축용이며 정확성 근거가 아니다.
2. **ACK**: PAYMENT Inbox 응답(내구 커밋 후)은 `outcome`과 `appliedRevision`(처리 뒤 PAYMENT head revision)을 준다. MASTER Relay는 2xx를 받은 뒤 별도 MASTER TX로 Outbox `PUBLISHED`와 `entitlement_snapshot_ack`(append-only)를 기록한다.
3. **인스턴스 ACK와의 관계(해석)**: PAYMENT eligibility는 **프로세스 내 캐시 없이** 매 평가 PAYMENT DB를 읽는다(§5.4, ArchUnit·시험으로 고정). 모든 PAYMENT 인스턴스가 같은 PAYMENT DB를 읽으므로 "Inbox 커밋 = 모든 인스턴스 적용"이고, 신규/재시작 인스턴스는 적재할 메모리 상태가 없어 R5의 "적재 전 트래픽 금지"를 구조적으로 만족한다. 캐시를 도입하는 변경은 인스턴스별 ACK·적재 게이트를 함께 설계해야 한다(불변식으로 기록).
4. **생성 TX와의 경계**: Port는 head 행을 `FOR SHARE`로 읽고 Inbox 적용은 같은 행을 `FOR UPDATE`로 잡는다. 그래서 차단 Snapshot 적용은 이전 권리로 평가 중인 CreatePayment TX가 끝날 때까지 기다리고, ACK가 나간 뒤에는 이전 권리로 평가된 생성이 커밋될 수 없다(§12 경쟁 시험). 승인 전송 게이트의 drain/fencing(허용 §4.2 일곱째 줄)은 AUTH-001 범위다.
5. **세 시점 구분(R6)**: 요청 접수 = `admin_request.created_at`(LIFECYCLE 명령), MASTER 차단 커밋 = 그 revision의 `entitlement_snapshot_issue.issued_at`, 전체 효력 확인 = (a) `appliedRevision ≥ r`(또는 `outcome = UNSUPPORTED`로 PAYMENT가 fail-closed가 된 경우)인 ACK 시각, 또는 (b) r 미만 revision으로 발행된 모든 lease의 `leaseUntil` 최댓값 경과 — 둘 중 이른 시각과 근거(ACK/LEASE_EXPIRY)를 jOOQ 조회로 계산한다(§4.8). 발행(PUBLISHED)만으로 효력 확인을 표시하지 않는다.

### 3.5 순서 역전·중복·누락·미지원 처리(PAYMENT 적용 규칙)

head(Merchant별 현재 적용 상태) 잠금 아래 한 PAYMENT 로컬 TX에서 판정한다.

| 수신 | 조건 | 결과(outcome) | head 변경 |
|---|---|---|---|
| 같은 eventId 재전송 | inbox에 (consumer, eventId) 있음 | `DUPLICATE`(최초 처리 결과 재응답) | 없음 |
| 더 큰 revision | `revision > head.revision`, schemaVersion 지원 | `APPLIED` | 내용 교체, lease 설정 |
| 같은 revision·같은 hash | lease 더 김 | `LEASE_EXTENDED` / 아니면 `STALE` | lease만 |
| 같은 revision·다른 hash | MASTER 증가 누락 또는 결함 | `INTEGRITY_CONFLICT` | head를 `VERSION_UNSUPPORTED` 상태로 막음(fail-closed) + 경보 |
| 더 작은 revision | 순서 역전·늦은 과거 허용 | `STALE`(기록만) | 없음 — **R2** |
| 지원하지 않는 schemaVersion | 봉투(envelope v1)는 해석 가능 | `UNSUPPORTED`(원문 보존) | `revision > head.revision`이면 head를 `VERSION_UNSUPPORTED`로 막음 — 더 새 권리(차단일 수 있음)를 못 읽으면서 옛 권리를 계속 쓰지 않는다 |
| 봉투 해석 불가·서명 불일치 | 계약 오류 | 4xx(저장 없음) → MASTER가 `QUARANTINED` + 경보 | 없음 |

누락: 전체 상태 Snapshot이므로 중간 revision 누락은 판정에 영향이 없다. 최신 revision이 끝내 오지 않으면 lease 만료로 fail-closed가 되고, 갱신 재발행이 최신 상태를 다시 보낸다(anti-entropy). 막힌 head는 PAYMENT 업그레이드 후 보존 원문 재처리 또는 다음 더 큰 revision 적용으로 풀린다. **보존 원문 재처리는 신뢰된 내부 운영 명령이어야 한다**(review n-4): `inbox_event.payload`는 MySQL JSON 정규화로 바이트가 바뀌어 HMAC을 다시 검증할 수 없으므로 외부 endpoint로 다시 받지 않고, 저장 원문(수신 시 서명 검증됨)을 관리자 인증(Q-ADMIN-AUTH)·감사 기록이 있는 명령으로 적용한다. MASTER 쪽에서는 같은 revision을 다시 발행(갱신 재발행)하는 것이 더 단순한 대안이다.

---

## 4. MASTER 측

### 4.1 PaymentMethodActivation 상태 전이(D-MD-017)

| 전이 | 명령 | 같은 TX | 상태 |
|---|---|---|---|
| (없음) → UNDER_REVIEW | 기존 `RegisterPaymentMethodActivation`(기존 행은 migration에서 UNDER_REVIEW) | 기존 + Snapshot 발행 | **해석**(기존 행은 "등록됨, 개통 아님" — MD명세 §10 개통 행) |
| UNDER_REVIEW → ACTIVE | `ApprovePaymentMethodActivation(activationId, evidenceRef)` | 상태·이벤트 + **그 MID의 유효 PRIMARY Site 적용 확인 자동 기록**(PRIMARY 없으면 확인 없음 → 결제 차단) + revision +1 + Snapshot | **확정 D-MD-016·017** |
| UNDER_REVIEW → TERMINATED | `TerminatePaymentMethodActivation(reason)`(심사 거절·철회) | 상태·이벤트 + Snapshot | **확정 D-MS-003** |
| ACTIVE → SUSPENDED | `SuspendPaymentMethodActivation(reason)` | 같음 | **확정 D-MS-003** |
| SUSPENDED → ACTIVE | `ResumePaymentMethodActivation(evidenceRef)` — 새 기관 근거 필수(지금 근거와 같은 참조는 ACTIVATION_EVIDENCE_NOT_NEW, 관례 해석), 기존 Site 확인 유지 | 같음 | **확정 D-MS-003** |
| ACTIVE/SUSPENDED → TERMINATED | `TerminatePaymentMethodActivation(reason)` 비가역 | 같음 | **확정 D-MS-003**(재개통은 새 Activation 행) |

이력: `payment_method_activation_event`(append-only: activation, seq, from, to, evidenceRef, reason, actor, request_record_id, occurred_at). 현재 상태는 `payment_method_activation.status`(UPDATE, `@Version`). 잠정 UNIQUE(MID, 결제수단)(MD명세 m-5)는 V3에서 "TERMINATED가 아닌 행 하나" 생성 컬럼 UNIQUE(`uk_payment_method_activation_open_method`)로 바꿨다(D-MS-003).

### 4.2 Site 적용 확인 기록(D-MD-016)

- 표 `site_activation_confirmation`(append-only): `confirmation_id`(`sac_`), `merchant_account_id`, `merchant_site_id`, `merchant_id`, `payment_method`, `activation_id`, `evidence_ref`(기관 근거 참조, 필수), `confirmed_at`, `confirmed_by`, `request_record_id`. 복합 FK로 MID·Site 같은 Merchant, Activation은 같은 MID·수단.
- 명령 `ConfirmSiteActivation(mid, siteId, method, evidenceRef)`: 그 (MID, Site) 열린 Assignment가 있고 Activation이 TERMINATED가 아닐 때만. 같은 (activation, site)의 유효 확인이 이미 있으면 거절(재생 아닌 업무 거절 저장). PRIMARY 확인은 §4.1 승인 명령이 함께 기록한다 — **Activation이 UNDER_REVIEW인 동안 PRIMARY Site의 별도 확인은 PRIMARY_CONFIRMED_BY_APPROVAL로 거절**하고, 승인은 그 Site의 유효 확인이 이미 있으면 새로 만들지 않는다(review M-1: 유효 확인 둘이면 하나를 철회해도 결제가 허용된 채 남는다). DB 이중 방어: `confirmation_seq` + UNIQUE(activation_id, merchant_site_id, confirmation_seq) — 다음 순번은 앞선 확인이 모두 철회됐을 때만 domain이 만든다.
- 철회(기관이 Site 적용을 거둔 경우, D-MS-003): append-only `site_activation_confirmation_revocation`(확인 참조 PK·사유·근거·행위자·요청 기록), 명령 `RevokeSiteActivationConfirmation(confirmationId, reason, evidenceRef)`. 재확인은 새 확인 행. Snapshot은 철회되지 않았고 **현재(종료 아닌) Activation에 묶인** 확인만 싣는다.
- ADDITIONAL 생성 명령(다섯 공유 조건 증거)은 MASTER-LIFECYCLE-001이다. 이 작업은 ADDITIONAL Assignment를 읽어 판정만 한다.

### 4.3 통화 활성화 기록(D-MD-018)

- 기준정보 `currency_unit`(`currency_code` CHAR(3) PK, `minor_unit` 0..4): V3에 KRW 0·USD 2를 넣는다. ISO 4217 표준 사실이며 **허용 통화가 아니다**(어떤 MID도 활성화하지 않음 — 관례, D-CP-002 "초기 구현·검증은 KRW·USD").
- 표 `currency_enablement`: `enablement_id`(`cur_`), `merchant_account_id`, `currency_code`(FK), `effective_from`, `effective_to?`, `evidence_ref`, `created_at`, `created_by`, `ended_at?`, `ended_by?`, `version`. 생성 컬럼 UNIQUE로 같은 (MID, 통화)의 열린 기록 하나, 닫힌 구간 중첩은 merchant 잠금 아래 domain 검사.
- 명령 `EnableCurrency(mid, currency, period, evidenceRef)`, `EndCurrencyEnablement(enablementId, effectiveTo)`.
- 소급 시작·소급 종료 금지(from·to ≥ 명령 시각), 미래 종료 예약 허용 — **해석**: D-MD-005의 "거래 당시 근거를 바꾸지 않는다" 원칙을 같은 모양의 기간 기록에 적용했다. Coordinator가 별도 확인을 원하면 질문으로 올린다(§9 끝 참고).
- 승인 시작의 (CSA, MID, 통화) 정책 Assignment 요구는 AUTH-001/정책 작업이다. CreatePayment eligibility는 통화 활성화 기록만 본다(D-MD-018).

### 4.4 Site 심사 상태(D-MD-016 "PG 심사 상태로 MerchantSite에")

**확정 D-MS-002(a)**: append-only `merchant_site_review_decision`(site, seq, decision APPROVED/REJECTED/SUSPENDED — PENDING은 결정이 아니라 초기 상태, reason, evidenceRef?, actor, at, request_record_id) + Snapshot `sites[].reviewStatus` = 최신 결정(없으면 PENDING). 명령 `DecideSiteReview`. 결제는 APPROVED만(E3 → METHOD_NOT_ENABLED, 내부 로그 SITE_REVIEW). 개별 전이는 출처에 없어 좁게 골랐다(관례): PENDING→APPROVED|REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED|REJECTED, REJECTED 종결(재심사 허용 여부는 새 질문 후보 Q-MS-05). → 답: D-MS-005(2026-10-13 — REJECTED는 종료 상태로 재심사·재승인하지 않고, 재신청은 새 Site 등록으로 처음부터 심사. 구현은 이미 REJECTED 종결이라 변경 없음, §9·§15-6). 추가 결정 D-GOV-004 ①(2026-11-25): 심사 거절 Site는 채널 점유를 해제(채널 중복 검사에서 제외, 거절 기록 유지)해 같은 주소로 새 Site 재신청 가능 — 현재 코드는 생명주기 ACTIVE Site 전부를 중복 검사 대상으로 본다(`MerchantOnboardingService.kt:131`, 질의 `MerchantSiteJpaRepository.kt:8-12` — 심사 상태 조건 없음) → 결정 미적용, 구현은 별도 작업. 현행 규칙 정본은 `docs/rules/BIZ.md` §4.

결정 기록이 없는 Site = PENDING = 결제 차단(구조 경계 §1 "Site 심사 … 준비되지 않으면 결제 차단"과 일치).

### 4.5 schema 변경(새 버전 migration — V1·V2 수정 금지)

| 파일 | 내용 |
|---|---|
| `db/migration/master/V3__entitlement_snapshot.sql` | ① `payment_method_activation`: `status`(NOT NULL, 기존 행 UNDER_REVIEW), `evidence_ref`, `status_changed_at`, `version` 추가, CHECK(status IN 4종), Q-MS-03 권장안이면 기존 UNIQUE 제거 + `open_method_key` 생성 컬럼 UNIQUE. ② `payment_method_activation_event`. ③ `site_activation_confirmation`. ④ `currency_unit`(+KRW 0, USD 2), `currency_enablement`. ⑤ (Q-MS-02 답 후) `merchant_site_review_decision`. ⑥ `entitlement_snapshot_issue`(append-only: merchant_id, revision, lease_seq, content_hash, issued_at, lease_until, priority, outbox_event_id; UNIQUE(merchant_id, revision, lease_seq)). ⑦ `entitlement_snapshot_ack`(append-only: outbox_event_id, consumer, outcome, applied_revision, acked_at). ⑧ `outbox_event` ALTER: `priority`, `claim_owner`, `claim_until`, `attempt_count`, `next_attempt_at`, `last_failure_code`, publish_status CHECK에 `QUARANTINED` 추가(CHECK 재정의), 인덱스 (`event_category`, `publish_status`, `priority`, `id`) |
| `db/runtime-privilege/master/V4__entitlement_runtime_privileges.sql` | `outbox_event` → SELECT, INSERT, UPDATE(Relay claim·PUBLISHED·QUARANTINED, AC m-7). `payment_method_activation` → SELECT, INSERT, UPDATE. `currency_enablement` → SELECT, INSERT, UPDATE. append-only(`payment_method_activation_event`, `site_activation_confirmation`, `merchant_site_review_decision`, `entitlement_snapshot_issue`, `entitlement_snapshot_ack`) → SELECT, INSERT. `currency_unit` → SELECT |

버전 공간: V3 업무 표 → V4 권한(관례 §9.2 "표를 만든 Vn 다음 버전"). jOOQ codegen은 `db/migration/master`만 적용(권한 위치 제외 유지). `MasterDatabaseAccountSpec` 고정 기대값·`MasterSchemaSpec`을 함께 갱신(기대값 완화가 아니라 새 표 추가).

### 4.6 Snapshot 발행 시점·TX 경계

```text
MASTER 로컬 TX(관리 명령, AdminCommandExecutor 그대로):
  admin_request 예약 → merchant FOR UPDATE → merchant_account FOR UPDATE(해당 시) → 판정 → beginWrites
  → 업무 행 변경 + 이벤트/이력 → merchant.entitlement_revision +1
  → EntitlementSnapshotAssembler(domain service)가 같은 TX의 JPA 읽기 Port로 현재 상태를 조립(Store가 각 Port 끝에서 flush)
  → entitlement_snapshot_issue INSERT + outbox_event(ENTITLEMENT_SNAPSHOT, priority HIGH) INSERT → 요청 결과 SUCCESS
COMMIT → (afterCommit) Relay 깨우기 신호

MASTER 로컬 TX(lease 갱신, 스케줄 adapter → application use case, Merchant마다 1 TX):
  merchant FOR UPDATE → 최근 issue의 lease_until 확인(충분하면 종료) → 조립 → issue(lease_seq+1) + outbox(priority LOW) → COMMIT
```

- 조립은 명령과 **같은 TX**에서 한다(변경과 Snapshot이 원자적, 두 번째 hop 없음 — 차단 전파를 늦추지 않는다). 조립 읽기는 JPA(같은 영속성 컨텍스트·같은 연결)로 한다. jOOQ는 Command 경로에 쓰지 않는다(원칙 §4 "JPA와 jOOQ가 저절로 같은 TX라고 가정하지 않는다").
- `effect_key` = `EntitlementSnapshot:<merchantId>:<revision>:<leaseSeq>`. 이벤트 분류 `ENTITLEMENT_SNAPSHOT`(기존 `INTEGRATION_MASTER_REFERENCE` 이벤트는 그대로 PENDING, 소비자 생길 때 처리).
- 시각은 `Work.lockedNow()`(마지막 잠금 뒤 시각, MD review n-1 규칙)를 쓴다.

### 4.7 Outbox Relay

```text
TX1(claim): SELECT id FROM outbox_event WHERE event_category='ENTITLEMENT_SNAPSHOT' AND publish_status='PENDING'
            AND next_attempt_at <= now AND (claim_until IS NULL OR claim_until < now)
            ORDER BY priority DESC, id LIMIT :batch FOR UPDATE SKIP LOCKED
            → UPDATE claim_owner=:me, claim_until=now+claimLease → COMMIT
(TX 밖) Dispatcher Port로 전달(HTTP, §7) — DB 잠금 없음
TX2(행마다): 2xx → publish_status=PUBLISHED, published_at, ack INSERT (이미 PUBLISHED여도 ack만 추가 — 멱등)
             재시도 가능(5xx·timeout·연결) → attempt_count+1, next_attempt_at=now+backoff, claim 해제
             계약 오류(4xx) → QUARANTINED, last_failure_code, 경보(완료 아님 — A03 "DLQ는 완료 상태가 아니다")
```

- 재시도 가능 오류에는 상한을 두지 않는다(차단 Snapshot을 포기하지 않음). 대신 최고 대기 연령 경보(§11).
- Relay crash(claim 후 전달 전/후): claim 만료 뒤 다른 인스턴스가 같은 eventId로 재전달 → PAYMENT `DUPLICATE`.
- `QUARANTINED` 재처리는 원인 수정 후 운영 명령(감사 기록)으로 PENDING 복귀 — 명령 자체는 관리자 인증(Q-ADMIN-AUTH) 후. 지금은 상태와 경보만.

### 4.8 조회(CQRS, jOOQ)

`EntitlementPropagationQuery`(master `adapter.query`, 읽기 전용 REPEATABLE READ 한 TX): Merchant별 최신 revision·커밋 시각, 최신 ACK revision·시각, r 미만 lease 최댓값, 전체 효력 확인 여부와 근거(§3.4-5), 미전달·격리 건수. 운영 화면(internal-admin)은 Q-ADMIN-AUTH 후. 시각 컬럼은 `DATE_FORMAT` 문자열로 읽는다(관례 §9.1 정정).

---

## 5. PAYMENT 측

### 5.1 Inbox 로컬 TX

```text
(TX 밖) 서명 검증(§7, Q-MS-04) → 봉투 v1 해석(eventId, eventType, schemaVersion, aggregateId=merchantId, aggregateVersion=revision)
PAYMENT 로컬 TX(READ COMMITTED):
  entitlement_head IODKU(no-op, 없으면 revision 0 = "없음" 자리) → head FOR UPDATE
  → inbox_event에서 (consumer, eventId) 조회(같은 Merchant 이벤트는 head 잠금으로 직렬화되므로 조회 후 INSERT가 경쟁 없음)
  → 있으면 DUPLICATE: 저장된 outcome·현재 head revision 응답
  → 없으면 §3.5 판정 → inbox_event INSERT(outcome, 원문, 원문 SHA-256)
     → APPLIED: entitlement_snapshot INSERT(merchant_id, revision, 정규 JSON) + entitlement_mid_owner INSERT(새 MID만)
       + head UPDATE(revision, content_hash, schema_version, issued_at, lease_until, source_event_id, blocked_reason=NULL)
COMMIT → 200 {outcome, appliedRevision}
```

- 업무 반영과 Inbox 완료가 한 로컬 TX(A03, CLAUDE.md). ACK는 커밋 뒤에만. 커밋 단계 예외는 응답하지 않음(5xx) → MASTER 재전송 → `DUPLICATE` 또는 `APPLIED`.
- 잠금 순서(PAYMENT 전체): `idempotency_request` → `entitlement_head`(S, CreatePayment) / `entitlement_head`(X, Inbox) → `order_payment_control` → `payment`. Inbox는 control·payment를 잡지 않아 순환이 없다. 관례 §9 잠금 순서 행을 구현 시 갱신.

### 5.2 Snapshot 저장 표(`db/migration/payment/V3__entitlement_projection.sql`, 권한 `db/runtime-privilege/payment/V4__…`)

| 표 | 내용 | runtime 권한 | 이유 |
|---|---|---|---|
| `inbox_event` | consumer, event_id(UNIQUE(consumer, event_id)), event_type, schema_version, aggregate_id, aggregate_version, outcome, payload JSON, payload_sha256, received_at | SELECT, INSERT | append-only 수신 증거·재처리 원문(A02 Inbox UNIQUE) |
| `entitlement_head` | merchant_id PK, revision, content_hash, schema_version, issued_at, lease_until, applied_at, source_event_id, blocked_reason(NULL/VERSION_UNSUPPORTED/INTEGRITY_CONFLICT), blocked_revision, version | SELECT, INSERT, UPDATE | IODKU·FOR UPDATE·적용 갱신 |
| `entitlement_snapshot` | (merchant_id, revision) PK, schema_version, content_hash, payload JSON(정규 권리 내용) | SELECT, INSERT | revision별 보존 — Payment `eligibility_snapshot_version`이 가리키는 내용을 그대로 남긴다(Auditability). 삭제 Job은 후속(§13) |
| `entitlement_mid_owner` | merchant_account_id PK, merchant_id | SELECT, INSERT | MID 소속은 불변(구조 §8). head 잠금 아래 INSERT |

PAYMENT는 이 투영을 원본으로 취급하지 않는다(DOMAIN-DEFINITION §2.1). 정규 테이블로 쪼개지 않고 revision별 JSON으로 두는 이유(관례): 판정 로직을 순수 domain `EntitlementSnapshot.evaluate`에 두어 SQL에 업무 규칙이 흩어지지 않게 하고, 받은 내용과 판정 근거가 1:1로 남는다.

### 5.3 판정 시점 읽기

Port는 `entitlement_mid_owner`(비잠금) → `entitlement_head` **FOR SHARE**(JPA `PESSIMISTIC_READ`, MySQL 8 dialect `for share` — 시험으로 SQL 확인) → `entitlement_snapshot`(비잠금, 불변 행) 순으로 읽어 domain으로 복원한다. 쓰기·외부 호출 없음(Port KDoc 규칙).

### 5.4 PaymentEligibilityPort 운영 구현 위치

- 새 Modulith 모듈 `com.paycore.payment.entitlement`: `domain`(EntitlementSnapshot·판정·적용 규칙), `application.provided`(`ReceiveEntitlementSnapshotUseCase`, `EntitlementEvaluationQuery` — TX MANDATORY로 호출자 TX에 참여), `application.required`(Store Port), `adapter.persistence`(JPA), `adapter.web`(내부 Inbox endpoint), `adapter.query`(jOOQ 신선도 조회).
- `payment.adapter.integration.EntitlementEligibilityAdapter : PaymentEligibilityPort` — `entitlement.application.provided`만 호출하고 결과를 `EligibilityResult`로 매핑(관례 §3 모듈 공개 범위: 다른 모듈은 provided만).
- 캐시 없음(§3.4-3). ArchUnit: entitlement 모듈에 `@Cacheable`·Caffeine 등 캐시 사용 금지 규칙 추가.
- 시험 배선: 운영 bean이 생기므로 `EligibilityTestConfig`의 `RecordingEligibilityPort`를 `@Primary`로 둔다. `EligibilityPortWiringSpec` "구현 0개" → "main 구현 정확히 1개(`EntitlementEligibilityAdapter`)", 기존 "bean 없으면 기동 실패" 시험은 유지. `EligibilityTestConfig` 없이 runtime 계정·실제 MySQL로 기동하는 smoke 추가(L-11).

---

## 6. 판정 순서와 EligibilityResult 매핑

### 6.1 순서(앞 단계가 결정하면 멈춤)

`t = evaluatedAt`(서비스가 준 서버 시각), `now` = Port 시계(같은 서버 clock Port).

| 단계 | 조건 | 결과 | 근거 |
|---|---|---|---|
| U1 | MID 소속(`entitlement_mid_owner`) 없음 또는 head 없음(revision 0) | Unavailable(SNAPSHOT_MISSING) | R3. 인증된 MID이므로 "없는 MID"가 아니라 "아직 전파 안 됨"으로 본다(관례 — 둘 다 차단, 응답 403/404 대신 503·재시도 가능. 자기 MID라 열거 위험 없음) |
| U2 | head `blocked_reason` 있음 또는 schemaVersion 미지원 | Unavailable(VERSION_UNSUPPORTED) | R3, §3.5 |
| U3 | `t ≥ leaseUntil − skew` | Unavailable(SNAPSHOT_EXPIRED) | R3, R12 |
| F1 | Site가 이 Merchant Snapshot에 없음(다른 Merchant·없는 Site) / Site TERMINATED / (MID, Site) Assignment가 t에 유효하지 않음(status ACTIVE ∧ from ≤ t < to) / MID에 t에 유효한 PRIMARY 없음 | NotFoundOrForbidden | CP계약 §13("Site 없음·다른 Merchant/MID·Assignment 무효"), D-MD-005·007, MD명세 §3.7 |
| E1 | Business·Merchant TERMINATED 또는 차단 / MID 차단 / MID TERMINATED | NotEligible(MERCHANT_SUSPENDED) | D-MD-006·019, D-MS-002(c) |
| E2 | (MID, Site) contractWindow가 t를 포함하지 않음(효력 전·종료·철회·Scope 밖) | NotEligible(CONTRACT_NOT_EFFECTIVE) | 허용 §4.1-4·5, D-MD-011·015, D-MS-002(b) |
| E3 | Site 심사 미승인 | NotEligible(METHOD_NOT_ENABLED), 내부 로그 SITE_REVIEW | D-MD-016, D-MS-002(a), 구조 경계 §1 |
| E4 | Activation(MID, 수단) ≠ ACTIVE 또는 (MID, Site, 수단) 적용 확인 없음 | NotEligible(METHOD_NOT_ENABLED) | D-MD-016·017 |
| E5 | t에 유효한 통화 활성화 기록 없음 | NotEligible(CURRENCY_NOT_ENABLED) | D-MD-018 |
| OK | 위 모두 통과 | Eligible(`es1:<merchantId>:<revision>`, CurrencyUnit(통화, minorUnit), validUntil) | CP계약 §13 |

순서 이유(관례): Unavailable을 먼저 — 오래되거나 없는 Snapshot으로는 소유·관계조차 판단하지 않는다. NotFoundOrForbidden을 NotEligible보다 먼저 — 관계가 없는 Site에 대해 계약·개통 상태를 드러내지 않는다(CP계약 §9.1). NotEligible 안의 순서(정지 → 계약 → 심사·개통 → 통화)는 가맹점에 보이는 사유가 "가장 근본적인 차단"이 되게 고른 것이다.

`environment`: TEST/LIVE 같은 판정(**확정 D-MS-001**). Snapshot에 환경 축은 없다(D-MD-003).

### 6.2 MID 운영 중지 매핑(D-MD-019)

MID 차단(D-MD-006 차단 기록, LIFECYCLE 작업에서 생성)·MID TERMINATED → `MERCHANT_SUSPENDED`. 응답은 계약 그대로, 내부 구조화 로그·감사에만 `suspendedScope = BUSINESS | MERCHANT | MID`를 남긴다(metric label 아님). MID TERMINATED도 같은 reason이다(**확정 D-MS-002(c)**).

### 6.3 validUntil과 evaluatedAt

- Port의 Eligible `validUntil = min(leaseUntil − skew, nextBoundary(t))`. nextBoundary = 판정에 쓴 Assignment·PRIMARY Assignment·contractWindow·통화 구간의 경계 중 t보다 큰 최솟값(없으면 lease만).
- U3 때문에 `validUntil > t`가 항상 성립하므로 서비스의 방어(`validUntil ≤ now`면 만료)와 같거나 더 엄격하다(R12). **validUntil == evaluatedAt인 Eligible은 만들지 않는다**(경계에서는 U3 또는 구간 판정이 먼저 막는다 — 시험 대상).
- 잠금 대기로 평가가 늦어지는 경우(head FOR SHARE 대기): 판정은 t로 하고, 추가로 `now ≥ validUntil`이면 Unavailable(SNAPSHOT_EXPIRED)로 바꾼다. 구간이 validUntil까지 상수이므로 "t에서 허용이고 now에서도 같은 판정"일 때만 Eligible이다(관례 — t를 늦은 시각으로 바꾸면 미래 시작 구간이 열리는 쪽으로 느슨해질 수 있어 쓰지 않는다).

---

## 7. 전송 수단(MASTER Outbox → PAYMENT Inbox)

| 후보 | 설명 | 판정 |
|---|---|---|
| (a) **push relay → PAYMENT 내부 Inbox API** | MASTER Relay가 행별 상태(PENDING/PUBLISHED)로 HTTP POST, PAYMENT는 내구 커밋 후 200 | **권장·결정(관례, 가역적)**: 행별 상태라 커밋 순서 역전으로 건너뛰는 행이 없고, ACK가 곧 내구 적용(A03 "Kafka 없이 Relay → 수신 API의 내구성 Inbox 저장") |
| (b) PAYMENT pull(커서 기반 MASTER feed API) | PAYMENT가 `after=<outbox id>`로 당겨감 | 기각: Outbox id는 커밋 순서가 아니어서(A03) 진행 중 TX의 낮은 id를 건너뛸 수 있다 — 차단 Snapshot이 다음 갱신까지 늦어질 위험 |
| (c) Kafka | Outbox → broker → Inbox | 보류(ADR-001 trigger 미충족). 도입해도 Outbox·Inbox·업무 키·로컬 TX는 그대로 필요하고 broker의 exactly-once가 권리 정합성을 대체하지 않는다 |
| (d) Redis pub/sub·캐시 | — | 기각: 비권위·유실 가능. 권리 판단에 Redis 장애 시 fail-open 금지(ADR-001 §4) |
| (e) 공유 DB/직접 SQL | MASTER가 PAYMENT 표에 쓰거나 PAYMENT가 MASTER 표를 읽음 | 금지(R10) |

세부(관례): `Dispatcher Port`(master `application.required`) + `adapter.integration` HTTP 구현(Spring `RestClient`, spring-web — 새 의존성 없음), PAYMENT `POST /internal/v1/entitlement-snapshots`(payment `entitlement.adapter.web`, 외부 공개 경로와 분리). 응답 2xx만 성공, 그 외는 §4.7 분류. URL·timeout은 환경 설정(기본값 없음).

**채널 인증(확정 D-MS-004)**: 봉투 HMAC-SHA256(헤더 `PayCore-Signature: keyId=<id>,v1=<hex>`, 환경별 키, 교체용 2키 동시 허용, 불일치·키 없음 401 거절) + 운영망 mTLS. 이번 구현은 시험 키 HMAC 검증만 — 운영 키·mTLS 구성은 LIVE 허용 전 별도 작업. 전파 지연이 결제 허용에 미치는 영향(MASTER 차단 커밋 후 PAYMENT 반영 전까지 이전 권리로 생성 가능, 상한 = lease)은 허용 §4.2가 이미 확정한 구조이므로 질문하지 않는다. 수치만 미결(§8).

---

## 8. Demo fixture 수치(운영값 아님)

모두 `@ConfigurationProperties`로 받고 **운영 기본값을 코드에 두지 않는다**(값이 없으면 기동 실패 — 허용 §4.2 마지막 문단, 구조 경계 §3). Demo 값은 시험·로컬 Simulator 설정에만 둔다.

| 항목 | Demo(로컬 Simulator) | 시험(MutableClock) | 비고 |
|---|---|---|---|
| leaseDuration | 60초 | 2초 | 운영 미결(R7) |
| renewBefore(잔여가 이보다 짧으면 갱신) | 30초 | 1초 | |
| 갱신 스캔 주기 | 10초 | 수동 호출 | |
| clockSkewMargin | 2초 | 100ms | |
| Relay 폴링 주기 / batch | 500ms / 50 | 수동 호출 / 10 | |
| claimLease | 30초 | 1초 | |
| 재시도 backoff | 1초 시작 ×2, 최대 30초(jitter) | 고정 10ms | |
| HTTP connect / read timeout | 1초 / 3초 | 200ms / 500ms(응답 유실 주입) | |
| 전파 지연 경보(커밋 → ACK) | 10초 | — | 운영 미결 |
| ACK timeout(효력 확인 대기 경보) | 30초 | — | 운영 미결 |

---

## 9. 질문 후보(Coordinator → 사용자)

금융·보안 의미·상태·권리 확정성 미결만 적었다. 확정된 D-MD-016~019는 다시 묻지 않는다. 가역적 구현 세부는 §10에 결정으로 적었다.

**Q-MS-01 TEST 환경의 결제 생성 허용 조건** — **답변: 권장안 채택(D-MS-001, 2026-10-13)**
- 질문: CreatePayment eligibility에서 TEST 환경(Simulator만 사용, D-CPD-001) 요청도 LIVE와 같은 조건(계약 효력·개통 ACTIVE·Site 적용 확인·Site 심사·통화 활성화)을 모두 요구하는가?
- 권장안: **같은 조건(구분 없음)**. Snapshot에 환경 축을 두지 않는다(D-MD-003: MID 하나, 환경은 Credential 축). 연동 시험이 개통 전에 필요하면 별도 결정으로 TEST 전용 완화를 추가한다.
- 대안: (a) TEST는 MID ACTIVE + 유효 Assignment만 요구(계약 효력·개통·통화 미요구, Simulator 전용이라 금융 효과 없음). (b) TEST는 계약 승인(효력 전 포함)까지만 요구.
- 영향: Snapshot 내용(환경별 판정 필드 필요 여부), 가맹점 연동 시험 시작 시점, TEST Credential 발급 정책.
- 막히는 부분: (해소) Port는 environment로 분기하지 않는다. TEST 완화는 별도 결정 전까지 없다.

**Q-MS-02 Site 심사 상태와 출처에 없는 거절 사유 3건** — **답변: 권장안 (a)(b)(c) 채택(D-MS-002, 2026-10-13)**
- 질문: (a) D-MD-016의 "PG 심사 상태"의 값 집합·전이와, 심사 미승인 Site 결제 생성 시 reason은? (b) 유효 Assignment는 있으나 MID 발급 계약의 현재 적용 Revision Scope가 그 Site를 포함하지 않을 때 reason은? (c) MID TERMINATED(종료)도 D-MD-019처럼 MERCHANT_SUSPENDED인가?
- 권장안: (a) 상태 PENDING/APPROVED/REJECTED/SUSPENDED, 결정은 append-only 기록(사유·행위자), 신규 Site는 PENDING, 결제는 APPROVED만, 미승인은 **METHOD_NOT_ENABLED**(가맹점에는 "결제수단을 아직 이 Site에서 쓸 수 없음", 내부 로그에 SITE_REVIEW 구분). (b) **CONTRACT_NOT_EFFECTIVE**(관계는 있으나 계약 권리가 그 Site에 미치지 않음). (c) **MERCHANT_SUSPENDED**(허용 §4.1-3 "중지/종료"를 한 reason으로, D-MD-019와 같은 방식).
- 대안: (a) 심사 미승인 → NotFoundOrForbidden / CP계약 개정으로 SITE_NOT_APPROVED 추가. (b) NotFoundOrForbidden(Scope 밖 Site는 "권한 없음"). (c) NotFoundOrForbidden.
- 영향: MerchantSite 심사 표·명령, Snapshot `sites[].reviewStatus`, CP계약 §9 응답 분류(계약 개정 여부), 가맹점 오류 안내.
- 막히는 부분: (해소) 심사 표·명령·E3 구현.

**Q-MS-03 개통 상태 전이와 Site 적용 확인의 철회** — **답변: 권장안 채택(D-MS-003, 2026-10-13)**
- 질문: D-MD-017 상태 4종 사이의 허용 전이와 근거 요구, D-MD-016 Site 적용 확인(append-only)을 기관이 거둘 때의 표현은?
- 권장안: UNDER_REVIEW→ACTIVE(기관 근거 참조 필수, PRIMARY 확인 동시 기록) / UNDER_REVIEW→TERMINATED(심사 거절) / ACTIVE→SUSPENDED(사유) / SUSPENDED→ACTIVE(**새 기관 근거 참조 필수**) / ACTIVE·SUSPENDED→TERMINATED(**비가역**, 재개통은 새 Activation 행 — 같은 (MID, 수단)의 "종료 아닌 행" 하나). Site 확인 철회는 **append-only 철회 기록**(확인 참조·사유·근거·행위자), 재확인은 새 확인 행. Activation 재개(SUSPENDED→ACTIVE) 시 기존 Site 확인은 유지.
- 대안: SUSPENDED→ACTIVE에 근거 불요 / TERMINATED에서 재활성화 허용(행 재사용) / 확인 철회 없음(Activation 정지·Assignment 종료로만 차단) / 재개 시 Site 확인 모두 재기록.
- 영향: Activation UNIQUE 재설계(MD명세 m-5 잠정 해소), 이벤트 표, Snapshot activations·siteConfirmations, METHOD_NOT_ENABLED 판정.
- 막히는 부분: (해소) V3 UNIQUE 변경·전이 명령·철회 명령 구현.

**Q-MS-05 REJECTED Site 재심사** — **답변: REJECTED는 종결, 재신청은 새 Site(D-MS-005, 2026-10-13)** — 2단계 구현 중 생긴 질문(§4.4·§15-6). 구현은 이미 REJECTED 종결(전이 없음)이라 코드 변경 없음.

**Q-MS-04 MASTER→PAYMENT 권리 전달 채널의 인증(보안)** — **답변: 권장안 채택(D-MS-004, 2026-10-13)**
- 질문: 위조·변조된 Snapshot은 결제 권리를 열 수 있다. PAYMENT 내부 Inbox endpoint가 MASTER 발행분만 받도록 하는 인증 방식은?
- 권장안: **봉투 HMAC-SHA256 서명**(환경별 키, 비밀 저장소에서 주입, 저장소에 키 없음, 서명 키 두 개 동시 허용으로 교체) + 운영망에서는 **mTLS**로 호출자 제한. 서명 불일치·키 없음은 거절(fail-closed). 로컬·시험은 시험 전용 키를 환경변수로.
- 대안: (a) mTLS만. (b) 사내망 격리만(앱 인증 없음). (c) OIDC client-credentials 토큰.
- 영향: Inbox adapter, 키 관리·교체 절차, 운영 배포 요건(LIVE 허용 전 필수), Simulator/REAL 격리 시험.
- 막히는 부분: 채널 운영 활성화(LIVE)만 — 운영 키 주입·mTLS는 LIVE 허용 전 별도 작업. 시험 키 HMAC 검증(기본 거절)은 구현됐다.

Coordinator 채택(2026-10-13, 질문으로 승격하지 않음): §4.3 통화 활성화 기록의 소급 금지·미래 종료 예약(D-MD-005 유추), §6.1 U1(MID 미전파 = SNAPSHOT_MISSING).

---

## 10. 가역적 구현 관례(사용자 질문 아님)

| 항목 | 선택 | 이유 |
|---|---|---|
| Snapshot 단위 | Merchant 전체 상태 | §3.1 |
| 버전 문자열 | `es1:<merchantId>:<revision>` | §3.2, 64자 이하 |
| contentHash | 정규 JSON(키 정렬, UTC ms 시각, 배열 id 정렬) SHA-256, `ec1:` 접두 | 같은 revision 무결성 비교 |
| 조립 위치 | MASTER domain service `EntitlementSnapshotAssembler` + JPA 읽기 Port, 명령과 같은 TX | §4.6 |
| contractWindow 계산 | MASTER domain(`Contract.applicableRevisionAt` 재사용, 경계 정렬로 구간 생성) | D-MD-011 로직을 한 곳에 |
| 우선순위 | 변경 HIGH, 갱신 LOW | §3.4 |
| 전송 | push relay + HTTP 내부 API | §7 |
| PAYMENT 모듈 | `com.paycore.payment.entitlement` 신설, Port 구현은 `payment.adapter.integration` | §5.4 |
| 저장 | revision별 JSON + head + mid_owner + inbox | §5.2 |
| 캐시 | 없음(ArchUnit) | §3.4-3 |
| 생성 TX 읽기 | head FOR SHARE | §3.4-4 |
| 수치 | 운영 기본값 없음, Demo는 시험·로컬 설정 | §8 |
| 스케줄 | Spring `@Scheduled` adapter → application use case(업무 규칙은 application) | 관례 §2 |
| 식별자 | `sac_`·`cur_`(+ 기존 형식, ULID 26자) | MD명세 §6 |
| 시험 | Kotest BehaviorSpec, Named Fixture(`EntitlementSnapshotFixture`·`ActivationFixture`), Fixture Monkey는 대량 Store 시험의 프로젝트 Fixture 안에서만, 실제 MySQL runtime 계정 | Test Fixture Policy |

### 10.1 JPA·jOOQ·BOM(이번 변경에 연결)

- **Domain–Entity 분리**: 역할 — `EntitlementSnapshot`(PAYMENT domain)·`SiteActivationConfirmation`·`CurrencyEnablement`(MASTER domain)는 JPA를 모르고, `Jpa*Entity`·Mapper가 adapter에만 있다. 예 — PAYMENT `entitlement_snapshot.payload` JSON을 Mapper가 domain으로 복원하며 revision ≥ 1·구간 순서를 재검증. 이유 — domain 순수성 ArchUnit + 판정 로직 단위 시험. 주의 — JSON은 `String` 컬럼(`@JdbcTypeCode(SqlTypes.JSON)`)으로 정규 문자열을 그대로 저장해 hash가 흔들리지 않게 한다.
- **변경 감지 + `@Version`**: 역할 — Activation 상태 전이는 잠가 읽은 Entity의 `status` 변경이 flush 때 `UPDATE … WHERE version=?`. 예 — Suspend 두 요청 동시 → merchant 잠금으로 직렬화, 두 번째는 이미 SUSPENDED를 보고 거절. 주의 — 표 단위 UPDATE 권한(V4)이 없으면 1142.
- **append-only는 persist만**: 이벤트·확인·issue·ack·inbox·snapshot 표. `save`(merge) 금지(1142 경로).
- **`PESSIMISTIC_READ` = `FOR SHARE`**: PAYMENT Port의 head 읽기. 주의 — Hibernate MySQL dialect가 실제로 `for share`를 내는지 SQL 캡처 시험으로 고정.
- **native SQL이 필요한 곳**: head IODKU(최초 경쟁, 관례 §9), Relay claim `FOR UPDATE SKIP LOCKED`(JPA 표준 API에 없는 조합 — 별도 TX, 관리 Entity 없이 실행해 영속성 컨텍스트 불일치를 피함).
- **jOOQ**: 조회 전용 — MASTER `EntitlementPropagationQuery`, PAYMENT Snapshot 신선도(lease 잔여·막힌 head 수) 조회. Command 경로·쓰기에 쓰지 않는다(`queryAdapterDoesNotWriteWithJooq`).
- **BOM**: 새 라이브러리 없음 — RestClient(spring-web, webmvc starter), Micrometer(actuator), Jackson 3(기존), HMAC은 JDK `javax.crypto`, 스케줄은 spring-context. Boot 4.1.1 BOM 그대로, GRADLE-DEPS로 resolved tree 확인. Kafka/Redis 도입 없음.

---

## 11. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거(시험) |
|---|---|---|
| Financial Consistency(권리 정합성) | 금액 없음. 권리 사실 append-only(확인·이벤트·issue·ack·inbox·snapshot), MASTER 원본 ↔ Snapshot 내용 일치, revision 단조 | 명령별 Snapshot 내용 = MASTER 상태(조립 golden), revision 감소 불가(PAYMENT 적용·MASTER Store check), append-only 표 UPDATE/DELETE 1142 |
| Transaction Safety | 변경+revision+Snapshot+Outbox 한 MASTER TX, Inbox+적용 한 PAYMENT TX, HTTP는 TX 밖 | 각 저장 지점 flush 뒤 장애 주입 → MASTER 잔존 0(issue·outbox 포함), PAYMENT inbox/head/snapshot 잔존 0, 커밋 단계 실패 → 응답 없음 → 재전송 수렴 |
| Idempotency | 관리 명령 admin_request, Inbox (consumer, eventId), 같은 revision 재적용 무해, Relay 중복 전달 | 같은 eventId 8회 동시 → APPLIED 1·DUPLICATE 7, 같은 명령 키 재생, ack 중복 무해 |
| Concurrency Control | merchant 잠금 직렬화, head FOR UPDATE / FOR SHARE, SKIP LOCKED claim, 생성 컬럼 UNIQUE | 실제 MySQL 다중 연결: r·r+1 동시 도착(역순 포함) → head = r+1, 차단 적용이 진행 중 생성 TX를 기다리고 ACK 후 이전 권리 생성 0, Relay 2개 동시 → 같은 행 동시 claim 0 |
| Recoverability | at-least-once 재전송, claim 만료 재전달, UNSUPPORTED 원문 재처리, lease 만료 fail-closed | Relay crash(전달 전·후) 주입 → 수렴, 응답 유실(read timeout 후 PAYMENT 커밋) → 재전송 DUPLICATE·ACK, PAYMENT 업그레이드 시뮬레이션 재처리 |
| Reconciliation | 갱신 재발행 = anti-entropy, contentHash로 증가 누락 검출, 전파 차이 조회 | revision 증가 누락 결함 주입 → INTEGRITY_CONFLICT·fail-closed·경보, 미ACK revision 목록 조회 |
| Auditability | MASTER 이력(행위자·근거 참조·요청 ID), issue·ack(발행·효력 시점), PAYMENT inbox 원문·revision별 snapshot, Payment `eligibility_snapshot_version` | 효력 확인 시각·근거(ACK/LEASE_EXPIRY) 계산 시험, Payment 버전 → snapshot 내용 역추적 |
| Observability | MASTER: outbox 대기 건수·최고 연령(분류별), 격리 건수, 전파 지연, 효력 미확인 차단 수, 기존 WARN(OutcomeUnknown·재시도 소진·식별자 충돌)의 counter(MD review m-7·m-8). PAYMENT: inbox outcome counter, eligibility 결과·reason counter, lease 최소 잔여·막힌 head 수 gauge. MID·merchantId·paymentId를 metric label로 쓰지 않음, 로그는 eventId·correlationId | 장애 주입 시 지표 값·로그 줄 정확 일치, 민감값 없음 |

---

## 12. 시험 계획과 구현 단계

### 12.1 실DB 시험(필수, Mock 단독 금지)

| 영역 | 시험 |
|---|---|
| 경쟁 | 같은 eventId 동시 8 / r·r+1 동시(양 순서) / 차단 적용 vs 진행 중 CreatePayment(`data_lock_waits` 관찰) / Relay 인스턴스 2 동시 claim / 갱신 작업 2 동시 → issue 1건 / 같은 MID 개통 승인·정지 동시 |
| rollback | MASTER 명령 각 저장 지점(업무·이벤트·revision·issue·outbox·요청 결과) 뒤 실패 / PAYMENT Inbox 각 지점(inbox·snapshot·mid_owner·head) 뒤 실패 |
| 응답 유실 | PAYMENT 커밋 후 응답 지연(read timeout) → MASTER 재시도 → DUPLICATE + ACK, Outbox PUBLISHED 1회 |
| 순서 역전 | r+1(차단) 먼저, r(허용) 나중 → STALE, 판정 차단 유지(R2) / 갱신 r이 r+1 뒤 도착 → 무시 |
| 만료 | lease 경계 `t = leaseUntil − skew` 정확히 → EXPIRED, 1ms 전 → Eligible(validUntil > t), 갱신 중단 시 만료 후 503, 갱신 재개 후 회복 |
| 시각 경계(n-5) | 미래 Revision 시작 경계 전후, Assignment 종료 예약 경계, 통화 기간 경계 — 재발행 없이 판정 변화, validUntil = 경계 |
| 미지원·무결성 | schemaVersion 2 수신 → VERSION_UNSUPPORTED, 같은 revision 다른 hash → INTEGRITY_CONFLICT |
| 권한 | V4 기대값 고정(SHOW GRANTS), append-only UPDATE/DELETE 1142, inbox FOR UPDATE 불가 확인 |
| 배선 | main 구현 정확히 1개, EligibilityTestConfig 없이 기동 smoke, 캐시 금지 ArchUnit |

### 12.2 단계(S0..S8)

| 단계 | 내용 | 막힘 | 시험(추정) |
|---|---|---|---|
| S0 | payment `entitlement` 모듈 인식·ArchUnit(캐시 금지 포함)·Modulith provided | 없음 | 3–5 |
| S1 | MASTER domain: Activation 전이(UNDER_REVIEW→ACTIVE 확정분), Site 확인, 통화 활성화·CurrencyUnit, contractWindow 계산(n-5), Snapshot 조립·정규 JSON·hash | Q-MS-02(심사), Q-MS-03(나머지 전이) | 45–60 |
| S2 | MASTER V3·V4 + schema·권한 시험 | Q-MS-03(Activation UNIQUE), Q-MS-02(심사 표) | 20–28 |
| S3 | MASTER 명령 + 같은 TX Snapshot 발행 + revision 증가 매트릭스 + rollback·경쟁 | S1·S2 | 30–40 |
| S4 | MASTER Relay(claim·backoff·격리·ack) + lease 갱신 작업 + Dispatcher HTTP adapter(HMAC 시험 키) | Q-MS-04(운영 활성화만) | 22–30 |
| S5 | PAYMENT V3·V4 + Inbox use case·endpoint + §3.5 적용 규칙 + 경쟁·rollback·응답 유실 | Q-MS-04(운영 키만) | 30–38 |
| S6 | PAYMENT domain 판정 결정표(§6, U·F·E·OK, 경계·validUntil) + Port adapter + FOR SHARE 경쟁 + WiringSpec 전환·smoke | Q-MS-01(TEST 분기), Q-MS-02(E3·매핑 b·c) | 45–60 |
| S7 | 계약 golden: MASTER 생성 Snapshot JSON·봉투·서명 vector를 PAYMENT가 같은 파일로 해석(앱 간 직접 의존 없이 양쪽 시험 자원 + hash 일치 검사) | S4·S5 | 8–12 |
| S8 | Observability(지표·로그) + jOOQ 조회(MASTER 전파·PAYMENT 신선도) + ARCHITECTURE-CONVENTIONS·MD명세 갱신 | 없음 | 10–15 |

합계 추정 213–288건(현재 VERIFY-GATE 757건 기준 증가분). Verifier는 VERIFY-GATE(master-api·payment-api)·TEST-SUMMARY·GRADLE-DEPS(빌드 파일 변경 시)·CVE를 같은 source fingerprint로 실행한다. 이 1단계 문서에는 실행 증거가 없다.

답변 없이 계속 가능: S0 전부, S1의 Site 확인·통화·contractWindow·조립(심사 필드는 비워 두지 않고 보류), S3·S4·S5의 질문 무관 부분, S6의 U·F·E1·E2·E4·E5 판정(TEST는 LIVE와 같게). schema(S2)는 Q-MS-02·03 답 뒤 작성을 권장한다(적용된 migration은 고치지 않으므로 잠정 schema의 되돌리기 비용이 크다).

---

## 13. 범위 밖·이월

1. **지급계좌 준비(허용 §4.1-10)**: **별도 작업 등록 권장**(예: MASTER-PAYOUT-ACCOUNT-001). 이유 — 신규 승인 전송 조건(§4.1)이지 CreatePayment eligibility(CP계약 §13 고정 reason)에 없는 항목이고, 계좌 정보는 민감정보 보관·마스킹·검증 방식의 보안 계약이 필요하며, "계좌 오류가 신규 승인까지 막는지 지급만 보류하는지"는 허용 §4.1-10이 별도 운영 정책으로 남겼다(그 작업 착수 시 질문). LIVE 허용 전 완료가 필요하다.
2. 신규 결제 차단 기록·종료·PRIMARY 교체·ADDITIONAL 생성(다섯 조건 증거)·계약/CSA 철회·재활성화 — MASTER-LIFECYCLE-001. **그 작업 AC에 "권리 입력 변경 TX마다 entitlement_revision +1과 Snapshot 발행(§4.6)" 추가 필요**, PRIMARY 교체 시 새 PRIMARY의 Site 적용 확인이 없으면 결제 차단(D-MD-016 — 확인 기록 명령은 이 작업의 `ConfirmSiteActivation`).
3. Credential(API 키) 긴급 차단 전파(R5 "키") — Credential 작업(미등록). 같은 우선 전파·ACK 경로 재사용 권장.
4. 승인 전송 게이트 drain/fencing, 정책 Assignment(수수료·정산, (CSA, MID, 통화)) Snapshot 확장, 카드사/Route 사용 가능 목록 — AUTH-001/AUTH-CONTRACT-001/정책 작업. **(이관 표시 2026-10-15)** 정책 Assignment·카드사/Route·거래 한도 Snapshot 확장은 MASTER-SNAPSHOT-002(schemaVersion 2)가 구현했다 — `MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md`. 전송 게이트 drain/fencing은 AUTH-001에 남는다.
5. 운영 화면(전파 상태·격리 재처리 명령) — Q-ADMIN-AUTH 후.
6. PAYMENT DB 복원 시 복원 시점의 유효 lease로 옛 권리가 허용될 위험 — 복원 runbook에 "모든 head lease 만료 처리 명령" 추가 권장(A06 복원 절차, 후속 작업).
7. inbox·revision별 snapshot·issue·ack 보존 기간과 삭제 Job — 보존 정책 결정 시(지금은 삭제 경로 없음).
8. CP-API-001: eligibility 503 `Retry-After`, 내부 endpoint 외부 노출 차단 구성.
9. (review n-7, 변경 없음 이월) **LIVE 전 bootstrap 발행 명령**: V3 이전에 MID가 있던 Merchant는 다음 권리 입력 변경 전까지 Snapshot 발행 기록이 없어 PAYMENT에서 SNAPSHOT_MISSING(fail-closed)이다. LIVE 허용 전 "현재 상태로 전 Merchant 1회 발행" 운영 명령(감사·멱등)이 필요하다.

## 14. 기존 결정과의 충돌·공백(Coordinator 확인용)

1. **Site 심사 상태 미정의**: D-MD-016은 위치만 정하고 값·판정 reason이 없다 → Q-MS-02.
2. **reason 매핑 공백**: Scope 밖 Site, MID TERMINATED, Site 심사 미승인 → Q-MS-02. ADDITIONAL만 남은 MID는 MD명세 §3.7대로 NotFoundOrForbidden.
3. **Activation 잠정 UNIQUE(m-5)**: D-MD-017로 단위는 확정됐으나 재개통·종료 의미에 따라 키가 달라진다 → Q-MS-03.
4. **차단 기록 부재**: D-MD-006 차단 기록 표가 LIFECYCLE 작업이라 그 전까지 MERCHANT_SUSPENDED는 TERMINATED 값에서만 나온다(현재 TERMINATED로 바꾸는 명령도 없음). Snapshot 필드는 지금 정의한다.
5. **revision 증가 지점**: MD명세 §3.3의 3곳 외 이 작업의 새 명령과 LIFECYCLE 명령이 추가해야 한다(§3.2, §13-2).
6. **ADR-001 relay 방식 "미정"**: 이 명세가 push relay → 내부 API로 결정(가역적). ADR-001 §1 "제안" 문구는 구현 시 갱신.
7. **허용 §4.2 인스턴스별 ACK**: 캐시 없는 DB 읽기 구조로 DB 커밋 ACK가 인스턴스 ACK를 대신한다는 해석(§3.4-3). 캐시 도입 시 재설계.
8. **CreatePayment 잠금 순서 변경**: head FOR SHARE가 `idempotency → control → payment` 사이에 들어간다(관례 §9 갱신 필요). 기존 CP-DB-001 경쟁 시험이 계속 통과해야 한다.
9. **evaluatedAt 시각**: CreatePaymentService가 잠금 대기 전 요청 시각을 넘긴다. Port는 §6.3처럼 now ≥ validUntil이면 만료로 바꿔 보완한다(서비스 변경 없이 더 엄격).
10. **통화 기록 소급 금지**: D-MD-005 유추(해석) — §9 끝.
11. **Demo 통화 단위**: `RecordingEligibilityPort`의 KRW 0·USD 2는 시험 전용으로 남고, 운영 통화 단위 출처는 MASTER `currency_unit` → Snapshot이다(D-CP-002).

## 15. 2단계 구현 편차·결정 기록(2026-10-13)

1. **§3.1 지난 구간 필터 제거**: 내용(content)은 발행 시각과 무관한 MASTER 원본 상태의 함수다. 발행 시각으로 지난 Assignment·계약 구간·통화 구간을 거르면 같은 revision의 lease 갱신이 다른 contentHash가 되어 PAYMENT가 INTEGRITY_CONFLICT로 막는다(정상 갱신이 차단으로 바뀜). 지난 구간을 싣는 비용(크기)만 있고 판정은 PAYMENT가 판정 시각으로 계산하므로 결과가 같다.
2. **lease 갱신의 hash drift**: 같은 revision인데 갱신 조립 내용이 다르면(revision 증가 누락 결함) MASTER는 ERROR 로그·`paycore.master.entitlement.revision.hash.drift` 지표를 남기고 그대로 발행한다 — PAYMENT가 INTEGRITY_CONFLICT로 fail-closed. 자동 revision 증가로 결함을 숨기지 않는다.
3. **PAYMENT 내용 hash 재계산**: PAYMENT는 받은 contentHash를 믿지 않고 해석한 내용으로 다시 계산해 다르면 422(저장 없음 → MASTER 격리). 모르는 필드·비정규 시각도 계약 위반(fail-closed).
4. **Inbox payload 저장**: `inbox_event.payload`는 JSON 컬럼(MySQL이 정규화) — 원문 바이트 증거는 `payload_sha256`. 같은 eventId·다른 본문은 409(EVENT_ID_CONFLICT).
5. **Relay ACK와 claim**: ACK는 claim이 다른 인스턴스로 넘어갔어도 PUBLISHED로 둔다(수신측 내구 커밋이 사실). 재시도·격리 표시는 자기 claim이 유지된 행만 바꾼다.
6. **Site 심사 전이**: §4.4의 좁은 전이 집합, REJECTED 종결 — **Q-MS-05 답변: REJECTED 종결, 재신청은 새 Site(D-MS-005, 2026-10-13)**.
11. **DUPLICATE ACK의 원래 결과**(review m-3, 구현 선택): PAYMENT는 같은 eventId 재전송에 `outcome=DUPLICATE`와 함께 처음 저장한 `originalOutcome`을 준다. MASTER는 `entitlement_snapshot_ack.original_outcome`에 남기고 효력 확인(§3.4-5)에서 `outcome = UNSUPPORTED` **또는** `original_outcome = UNSUPPORTED`를 fail-closed 확인으로 본다(처음 응답이 유실되고 재전송만 성공한 경우를 놓치지 않는다).
12. **Relay 행 격리**(review n-6): 한 행의 예상 밖 예외는 그 행만 실패로 세고(`relay.results{result=FAILED}`) 나머지 행을 계속 처리한다. 그 행은 claim 만료 뒤 다시 집힌다.
13. **판정 순서 보강**(review n-1): 잠금 대기 뒤 now가 lease 한도(leaseUntil − skew)를 넘었으면 관계·권리 판정 전에 SNAPSHOT_EXPIRED.
7. **Resume "새 근거"**(review m-2로 강화): 그 Activation의 전이 이력(`payment_method_activation_event`)에 남은 **모든** 근거 참조와 달라야 한다(지금 근거 포함, 관례 — fail-closed). 같으면 ACTIVATION_EVIDENCE_NOT_NEW.
8. **설정**: MASTER `paycore.master.entitlement.*`(lease·renew·relay·dispatch·서명 키·scheduling-enabled·주기), PAYMENT `paycore.payment.entitlement.clock-skew-margin`·`inbox.signing-keys[]` — 운영 기본값 없음(없으면 기동 실패, 시험 `EntitlementSettingsSpec`). 시험 값은 test classpath `config/application.yml`에만.
9. **스케줄 위치**: Relay·갱신 주기 실행은 `adapter.batch`(application.provided만 호출), 설정 바인딩·HTTP Dispatcher·Micrometer 지표는 `adapter.integration`.
10. **계약 golden(S7)**: `contracts/entitlement-snapshot-v1.golden.json`(두 앱 시험 자원에 같은 파일)은 두 앱 코드와 독립적으로 명세 §10 규칙으로 생성했다. 생성 절차는 저장소의 `harness/tests/test_entitlement_golden.py`(Python 표준 라이브러리 `json.dumps(sort_keys, separators)`·`hashlib`·`hmac`만 사용)이며, HARNESS-SELFTEST가 매번 같은 절차로 다시 만들어 두 파일과 바이트 비교한다(독립성·동일성 확인). MASTER는 같은 상태로 바이트 동일한 정규 내용·payload·봉투·서명을 만들고, PAYMENT는 그 봉투를 서명 검증·해석해 같은 contentHash를 다시 계산한다.

## 개정 이력

| 일자 | 내용 |
|---|---|
| 2026-11-25 | S3-DOC-001 갈래 A(문서 정합, NP-94·PC-58 일부): §4.4 "재심사 허용 여부는 새 질문 후보 Q-MS-05"에 '→ 답: D-MS-005' 표시, 관련 후속 결정 D-GOV-004 ①(심사 거절 Site 채널 점유 해제) 참조 추가. 원문 무변경, 정책 변경 없음 |
