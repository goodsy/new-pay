# CP-QUERY-001 GetPayment(결제 현재 상태 조회) API 명세 (rev 0.3 — 중간 검토 #1 대응·D-CPQ-005·S3·S4)

> **rev 0.3 갱신(2026-10-20)**: 중간 검토 #1(PASS_WITH_FIXES, H 없음)의 m-1~m-4·n-1·n-4~n-8 대응과 사용자 답변 **D-CPQ-005**(U-1 — 반환 가능액은 FORCED를 뺀 권위 값, FORCED 추론 가능성은 허용된 추론)를 §18.6에,
> S3(Controller·API 시험·P-1)을 §18.7에, S4(문서·전체 check)를 §18.8에 기록했다. "한 SELECT" 문구는 "두 문장, 한 REPEATABLE READ 읽기 전용 TX"로 정정(m-4). n-2·n-3은 RECON-CONTRACT-001 인계(기록만).

> **rev 0.2 갱신(2026-10-20)**: §14 질문 4개가 답변됐다 — 모두 권장안. **D-CPQ-001**(그 Payment 자신의 활성 hold만 boolean `onHold`, 사유·회차·관찰·FORCED/초과·대사 진행 비노출),
> **D-CPQ-002**(AUTHORIZED Payment의 운영자 보정 취소는 이력에 `initiator: OPERATOR`만 — 신원·사유·approvalId·목적 없음. 가맹점에 FAILED/EXPIRED로 알려진 P1의 늦은 승인 거래와 그 운영자 취소는 숨기고 FAILED와 분류만),
> **D-CPQ-003**(paymentId 조회만), **D-CPQ-004**(조회 기간 제한 없음, 삭제 경로 없음 — 보관·파기는 LIVE Gate 별도 정책). UNKNOWN 표현은 Coordinator가 확정 규칙으로 확인(질문 없음, 현행 유지).
> Coordinator §15 판정: (1) F-1을 이 작업에서 수정 — `max(0, …)` 반환 가능액 함수 하나를 `CancellationViews`와 GetPayment가 공유, 취소 시험에 기대 행 **추가**(완화 아님) (2) AC 3 = §9.1 교차 참조 + m-7 종료 기록 + Asia/Seoul JVM 시험
> (3) 취소 단건 GET 없음 (4) 목록·검색 API는 범위 밖 — 후속 후보 **CP-QUERY-002** (5) 취소 이력 전체 반환 (6) rate limit 범위 밖 (7) `Location` 헤더 없음 (8) 단계 모듈 30분·VERIFY-GATE 90분, `taskTimeoutSeconds` 5400,
> dependsOn에 CANCEL-001 추가(Coordinator 적용) (9) timeout 합 자기 시험을 S0에 포함 — HARNESS-SELFTEST 하한은 Coordinator가 실측으로 올린다(구현자는 commands.json 불변) (10) allowedWritePaths 그대로
> (11) F-7: `declineCategory`를 추가하지 않고 CANCEL-001 문서(CN-02·CC §18 행)를 구현에 맞춰 정정(v1 비노출, Port 수준만). S0 VERIFY-GATE `timeoutSeconds` 1800 → 5400은 Coordinator가 적용했다.
> 아래 rev 0.1 문장 중 "Q-CPQ-xx"·"권장"·"미결"·"판정 요청"은 이 결정이 우선한다. 바뀐 곳: §0·§4.4·§5.1·§5.2·§6.2(CQ-07)·§12·§14.0·§15·§18.

rev 0.1 작성 2026-10-20 KST(2026-10-20T04:22Z 조사), 기준 commit `b82e5d1`(CANCEL-001 DONE, branch `feat/foundation-setup`, push 완료). **1단계 명세이며 코드·시험 변경 없음.**
작업 트리에는 Coordinator 소유 `harness/tasks.json`·`harness/progress.json` 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 금융·보안 정책을 채택하지 않는다 —
§14 질문 후보는 답변 전 해당 부분의 운영 동작을 구현하지 않는다. 2단계 TDD는 Coordinator가 질문·범위 관찰(§15)을 처리한 뒤 시작한다.

표시(CANCEL-001과 같다): **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — 의심되면 Coordinator가 질문으로
올린다), **구현 결정(CQ-xx)** = 금융·보안 의미가 없는 가역적 선택(§13), **미결(Q-CPQ-xx)** = §14 질문 후보, **Demo** = Simulator·시험 전용 값(운영값 아님),
**범위 관찰** = 작업 경계 확인 사항(§15), **발견(F-x)** = 이번 조사에서 찾은 기존 코드·문서의 공백(§3.2).

권위 계약: 상태 의미는 `AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(이하 **AC1**) §5·§10, 취소 의미는 `CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(이하 **CC**) §4·§8, 생성 결과 분리는
D-CP-003·`CP-CONTRACT-001`(이하 **CPC**) §5·§9.1. 이 명세는 그 계약을 다시 정하지 않고 조회 표현·실행 구조(API·조회 adapter·시험)만 정한다.

입력 문서(sha256 앞 8자…끝 4자, 2026-10-20 읽은 시점 작업 트리 — 전체 hash는 Verifier가 같은 파일에 `sha256sum`으로 재현):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 지침 | `CLAUDE.md` | 지침 | `829b5a44…d813` |
| 결정 | `docs/DECISIONS.md`(D-CP-001~003, D-CPC-001~009, D-CPD-001~004, D-CPA-001, D-MD-003·006, D-AUTH-001~013, D-AUTH1-001~007, D-CANCEL-001~013, D-CANCEL1-001~008, D-ADMIN-002·004) | 확정 | `6e132155…dce9` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md`(§1 8대 원칙, §3 JPA, §4 CQRS, §5 Kafka/Redis) | 사용자 지정 | `a053e150…8c1f` |
| 하네스 | `docs/HARNESS-GUIDE.md` / `docs/BASELINE-INDEX.md` | 기준 | `3f2220c4…06c8` / `f2461472…6251` |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md`(§3-8 "UNKNOWN은 확인 중으로 표시" — D-AUTH-001이 §1~§3 채택) | 확정 | `3cf08d32…11be` |
| 보안 | `docs/baseline/architecture/05-security-architecture.md`(13행 "인증 후 모든 거래 ID 조회·변경에서 소유 MID를 검증", 39행 금지 "다른 MID의 거래를 ID만으로 조회") | 기준 | `e9dcabd0…b956` |
| 작업 | `harness/tasks.json` CP-QUERY-001(AC 3개·allowedWritePaths·coordinatorDecisions) | Coordinator 소유(미커밋) | `78ec6df9…4e52` |
| 진행 | `harness/progress.json`(activeTaskId CP-QUERY-001, 다음 WEBHOOK-001) | Coordinator 소유(미커밋) | `b898e83c…fc2b` |
| 질문 | `harness/questions.json`(열린 질문 10개 — 이 작업을 막는 것 없음, §14.3) | Coordinator 소유 | `23211d50…6b9b` |
| 명령 | `harness/commands.json`(VERIFY-GATE 1800s, GRADLE-CHECK 2400s·하한 2092, HARNESS-SELFTEST 91) | 하네스 | `54c9199e…c0bd` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§5.1 Payment 요약 상태, §10.1 confirm 응답 분류, §10.2 조회 인계) | 확정 계약 | `d0802270…6498` |
| AU | `docs/repository/AUTH-001-AUTHORIZATION.md`(§1.2 인계, §7.6 jOOQ) | 구현 반영 명세 | `b611ebc7…bb19` |
| CC | `docs/repository/CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(§8 응답, §14-6 인계) | 확정 계약 | `a1582328…d838` |
| CN | `docs/repository/CANCEL-001-CANCELLATION.md`(구조 선례, §1.2·§8.5·§15-6 인계) | 구현 반영 명세 | `721130ac…97c3` |
| CPA | `docs/repository/CP-API-001-CREATE-PAYMENT-API.md`(§4.5·§4.6·§5·§7.5·§11.4) | 구현 반영 명세 | `4469e8e0…78e7` |
| CPC | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md`(§5·§9.1) | 확정 계약 | `03eecede…ae83` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9·§9.1·§11·§13·§14·§16·§17) | 관례 | `c52da377…e8ca` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례 | `4bb8712b…73fc` |
| TZ | `docs/repository/TZ-FIX-001-JOOQ-UTC.md` | 결함 수정 기록 | `35f0a7aa…05aa` |
| HVC | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md`(§2·§7·§8) | 하네스 계약 | `d291b5b8…92df` |
| 코드(query) | `payment/adapter/query/` `UtcWallClock.kt` `fa6e5d8f…8e41`, `JooqAuthorizationViewReader.kt` `34046afe…53f6`, `JooqCancellationViewReader.kt` `92434ce7…b469` | `b82e5d1` | — |
| 코드(web) | `payment/adapter/web/` `ApiErrorCode.kt` `950f6b62…9a36`, `MerchantBearerAuthenticationFilter.kt` `953569fe…67ee`, `PaymentApiSecurityConfiguration.kt` `575c88d7…8185`, `CancelPaymentController.kt` `eff999de…7650`, `ConfirmPaymentController.kt` `e01785ef…d8e2` | `b82e5d1` | — |
| 코드(application) | `payment/application/CancellationViews.kt` `85e9190a…df03`, `application/provided/AuthorizationView.kt` `a018053a…3695` | `b82e5d1` | — |
| 코드(schema) | PAYMENT `V1__payment_core.sql` `9e2b9dca…6545`, `V9__authorization.sql` `0211c432…2962`, `V15__cancellation.sql` `36182cba…ef0a`, `V17__cancellation_existing_tables.sql` `bc1a71af…7e69` | `b82e5d1` | — |

주: Coordinator 소유 파일(작업·진행·질문·명령)은 이후 바뀔 수 있다 — 2단계 S0에서 입력 hash 표를 다시 고정한다(§18). 이 문서 자신의 hash는 Verifier가 계산한다.

---

## 0. 요약

- **API(도출 — D-CP-003 + AC1 §10.2 + CC §14-6)**: `GET /v1/payments/{paymentId}`(공개 포트, 기존 Bearer 필터). 응답 = Payment 현재 상태 + 승인 요약 + 반환(취소) 요약 + 취소 이력. 생성 응답(`POST /v1/payments`)은
  바뀌지 않는다(생성 당시 값만 — D-CP-003, `CreatePaymentApiSpec` 기존 시험).
- **소유 확인(확정 K-20·CPC §9.1·보안 13행)**: 조회 Port가 (인증 environment, 인증 MID, paymentId)를 SQL WHERE로 강제한다. 없음·다른 MID·다른 환경·paymentId 형식 오류는 모두 같은 404
  `PAYMENT_NOT_FOUND`(같은 본문·같은 코드 경로).
- **상태 표현(확정 AC1 §5.1·§10.1, 범위 §3-8)**: Payment 요약 상태 6개를 confirm 응답 어휘로 노출 — `REQUESTED`·`IN_PROGRESS`(AUTHORIZING)·`UNKNOWN`(확인 중 안내)·`AUTHORIZED`·`FAILED`(분류 DECLINED·NOT_SENT·NO_EFFECT)·`EXPIRED`.
- **조회(구현 결정 CQ-05·06)**: `PaymentStatusReader` → `JooqPaymentStatusReader`(`adapter.query`, 쓰기 없음). (rev 0.2 m-4) **두 문장(본 행 + 이력), 한 REPEATABLE READ 읽기 전용 TX**로 Payment·Authorization·
  승인 거래·반환 잔액·hold 여부와 취소·CANCEL 거래를 한 스냅샷에서 읽는다(결정적 증거 `PaymentStatusReaderSpec` m-3 — 문장 사이 다른 연결의 커밋이 보이지 않음)(잠금 없음, 비권위 — 판정에 쓰지 않음 ArchUnit). 시각은 `UtcWallClock` 문자열 읽기, 시각 비교 없음(§6.3).
- **비노출(확정)**: 카드번호·인증 증거·기관 원문·기관 계약계정·내부 정책/권리/Route 버전·운영자 신원/사유·paymentKey(CQ-08) 없음. 캐시 금지(`Cache-Control: no-store`, CQ-10), ETag 없음.
- **발견 7건**(§3.2): 취소 응답 `cancellableAmount`가 FORCED 초과 시 음수가 될 수 있음(F-1 — domain은 `max(0, …)`), AC 3의 §9.1 정정은 이미 반영됨(F-2), VERIFY-GATE timeout 역전(F-3),
  취소 응답에 CN-02 `declineCategory`가 구현되지 않음(F-7) 등.
- **질문 후보 4개**(§14): hold 노출(Q-CPQ-01), 운영자 보정 취소·늦은 승인 사실 노출(Q-CPQ-02), merchantOrderId 조회(Q-CPQ-03), 조회 가능 기간(Q-CPQ-04). UNKNOWN 표현은 확정 규칙으로 판단해 질문에서 뺐다(§14.3 — Coordinator 확인 요청).
- **규모**: AUTH-001의 약 0.15~0.25배(새 표·migration 없음, 시험 +45~75건 추정, §17).

---

## 1. 범위와 범위 밖

### 1.1 범위

| 구분 | 내용 | 근거 |
|---|---|---|
| API | `GET /v1/payments/{paymentId}` — 인증·소유 확인·존재 비노출·응답 본문·오류 매핑·헤더 | AC 1·2, D-CP-003, AC1 §10.2 |
| Use Case | provided `PaymentStatusQuery`(조회 전용, TX 소유 안 함) → `PaymentStatusQueryService` — 행 → 공개 view 변환(상태 매핑·실패 분류·반환 요약) | 관례 §14 "DTO·판정 조회 Port는 …Query" |
| 조회 adapter | required `PaymentStatusReader` → `JooqPaymentStatusReader`(MID·환경 범위 WHERE 강제, 한 스냅샷, UTC 문자열 읽기) | 원칙 §4, 관례 §9.1·§13·§14 |
| 반환 요약·취소 이력 | 승인액·완료 반환액·진행 중 반환액·취소 가능액·ReturnStatus, 취소 operation 목록(상태·금액·완료 시각·실패 분류) | CC §14-6, CN §15-6 판정(6) |
| 문서 | 관례 §9.1 교차 참조·새 절(§18 CP-QUERY-001), AC1 §10.2·CPA §4.6 교차 참조, HVC §7 하한 기록 | AC 3, CPA §11.4 |
| 하네스(S0 — Coordinator 적용) | `commands.json` VERIFY-GATE `timeoutSeconds` 1800 → 내부 단계 합보다 큰 값(§12 S0) | F-3, Coordinator 지시 |
| 시험 | 실제 MySQL·실서버 HTTP: 소유·비노출·환경 분리·Credential 폐기·스냅샷 일관성·비UTC JVM·로그/본문 비노출 | CLAUDE.md, §9 |

### 1.2 범위 밖

| 항목 | 소유 | 근거 |
|---|---|---|
| 거래 목록·검색 API(기간·상태 필터, 페이지) | 후속 작업 후보(범위 관찰 §15-4) | tasks.json AC에 없음. 관례 §17 문장 "조회 API(GetPayment·거래 목록)"·CN §8.5 "거래 목록"은 인계 표현 |
| 취소 단건 조회 전용 GET | 후속(필요 시) — 범위 관찰 §15-3 | CN §15 Coordinator 판정 (6)은 "이력·잔액 요약"만 CP-QUERY-001에 넘겼다. 202 뒤 확인은 같은 키 재요청(CC-05) |
| merchantOrderId 기준 조회 | Q-CPQ-03 답 전 미구현 | §14 |
| 가맹점 Webhook·운영자 보정 취소의 가맹점 통지 | WEBHOOK-001 | CN §15 판정 (6) |
| 운영자(내부) 조회 화면·마스킹 해제 | ADMIN-BOOT-001 | D-ADMIN-002 |
| rate limit·조회 남용 차단 | 후속(CP-API-001 §19-18 "인증 실패 rate limit — fail-open 금지") | 범위 관찰 §15-6, Redis 미도입(ADR-001) |
| 생성 응답 `Location` 헤더 추가 | 범위 밖(생성 응답 계약 불변) — 범위 관찰 §15-7 | CPA §4.5 |
| 조회 projection·replica·캐시·Kafka | 도입 안 함 | 원칙 §4·§5, ADR-001 |

### 1.3 인계 항목 전수 대조(문서 전체 grep "CP-QUERY-001" — 2026-10-20, `build/` 제외)

| 출처 | 항목 | 처리 |
|---|---|---|
| tasks.json CP-QUERY-001 AC 1 | (D-CP-003) 생성 응답과 분리된 현재 상태 조회, 인증 MID·환경 권한 확인, 존재 여부 비노출 | §4.1·§4.2·§4.3, §9 |
| tasks.json CP-QUERY-001 AC 2 | 현재 상태 표현은 AC1 상태 계약을 따름 | §5 |
| tasks.json CP-QUERY-001 AC 3 | (MASTER-DOMAIN-001 review m-7) jOOQ 시각 조회 도입 시 관례 §9.1 정정 | F-2, §6.3, §12 S4 |
| tasks.json CP-QUERY-001 coordinatorDecisions | S0에서 `commands.json` VERIFY-GATE timeoutSeconds 정리 | F-3, §12 S0 |
| tasks.json 1659행(MASTER-DOMAIN-001 AC) | "payment-api에 jOOQ 시각 조회가 생기면 §9.1 정정(LocalDateTime=JVM 시간대 벽시계) 적용" — AC 3의 원문 | F-2 |
| tasks.json 1767행(CP-API-001 결정 2) | GetPayment endpoint는 CP-QUERY-001, m-7 N/A | 이 작업 전체 |
| tasks.json 1778행(CP-API-001 후속) | "(기존 후속) … CP-QUERY-001(GetPayment), 인증 실패 rate limit(fail-open 금지)" | 이 작업 / rate limit은 §15-6 |
| tasks.json 2415행(CANCEL-001 판정 6) | 취소 GET 없음 — 이력·잔액 요약은 CP-QUERY-001, 통지 여부는 WEBHOOK-001 | §4.4 반환 요약·취소 이력 |
| CPC 252행 | "GetPayment는 CP-QUERY-001" | 이 작업 |
| CPA §4.6(211행)·§19-2(593행) | endpoint 미생성 이유: 현재 상태 표현이 AC1 상태 계약에 묶임, jOOQ CQRS 조회 adapter로 별도 설계 | §5, §6 |
| CPA §11.4(454행) | m-7 해당 없음, 바인딩 방향의 비UTC JVM 증거 확인 가치 | F-2(TZ-FIX-001이 바인딩 방향 해소) |
| CPA §19-18(626행) | 후속: CP-QUERY-001(GetPayment, jOOQ), 인증 실패 rate limit(fail-open 금지) | §15-6 |
| CPA `CreatePaymentApiSpec.kt` 114행 주석 | 생성 응답은 생성 당시 값, 현재 상태는 조회 API | §9 회귀 시험(생성 재생 본문 불변 + GET은 현재 상태) |
| AC1 §10.2(384행) | GetPayment가 Payment·Authorization 현재 상태를 MID·환경 범위 강제로 제공, UNKNOWN은 "확인 중" | §4.2, §5 |
| AU §1.2(105행)·§7.6(726행) | GetPayment(Authorization 현재 상태 조회) — 시각 `DATE_FORMAT`·UTC 리터럴, jOOQ 쓰기 금지 | §6 |
| AN(AUTHN-001) 52행 | 범위 밖: 결제 조회 API(GetPayment) | 이 작업 |
| CC §14-6(767행)·§14 판정(758행) | GetPayment에 반환 요약·취소 이력 | §4.4 |
| CN 11·99·626·853·863행 | 취소 GET 없음, 이력·반환 요약(·취소 단건 조회) CP-QUERY-001, 거래 목록·반환 요약 조회 API | §4.4 / 단건·목록은 §15-3·§15-4 |
| CN 33·34행 | 입력 표(작업·agreedOrder: CANCEL-001 → CP-QUERY-001 → WEBHOOK-001) | 순서 확인만 |
| 관례 101행(§7) | GetPayment 조회는 CP-QUERY-001 | 이 작업 |
| 관례 268행(§12) | 조회 API(GetPayment·거래 목록)는 `adapter.query` jOOQ DTO 조회, MID·environment 범위 강제, 시각은 `DATE_FORMAT` 문자열 | §6 / 거래 목록은 §15-4 |
| progress.json 4·10·209·488·499행 | 진행 상태(다음 재개 동작: 1단계 명세 → 질문 → review → TDD → VERIFY-GATE·DEPS/CVE·review → DONE, ADMIN-BOOT-001 전 사용자 알림) | 진행 순서 |

---

## 2. AC 대응표(tasks.json CP-QUERY-001 → 세부 → 절 → 계획 시험)

| # | AC·세부 | 출처 | 기존 코드 | 공백 → 이 작업 | 절 | 계획 시험(이름 가역적) |
|---|---|---|---|---|---|---|
| 1-a | 생성 응답과 분리된 현재 상태 조회 | D-CP-003, CPA §4.6 | 생성 응답 고정 필드 시험 | GET endpoint·view | §4 | `GetPaymentApiSpec`(생성 → 승인 → GET은 AUTHORIZED, 같은 키 생성 재생은 여전히 `lifecycleAtCreation=REQUESTED` 바이트 동일) |
| 1-b | 인증 MID·환경 권한 확인 | K-20, D-CPA-001, D-MD-003, 보안 13행 | Bearer 필터, 취소·confirm의 사후 비교 | Reader WHERE 강제(사후 비교 아님) | §4.2, §6.1 | `GetPaymentOwnershipSpec`(다른 MID·같은 MID 다른 환경·폐기 키 401), `PaymentStatusReaderScopeSpec`(실 MySQL) |
| 1-c | 존재 여부 비노출 | CPC §9.1, CPA §7.5 | `PAYMENT_NOT_FOUND` | 없음·남의 것·형식 오류 같은 404 바이트 | §4.3 | `GetPaymentNonDisclosureSpec`(세 경우 상태·헤더 집합·본문 바이트 동일, 본문·로그에 제시한 paymentId 없음) |
| 2 | 현재 상태 표현 = AC1 상태 계약 | AC1 §5.1·§10.1·§10.2, 범위 §3-8 | `AuthorizationViewStatus`·`FailureCategory` | Payment 6상태 × 승인 요약 매핑 표 | §5 | `PaymentStatusViewsSpec`(표 §5.1 전 행 — 순수 단위), `GetPaymentStateApiSpec`(실 흐름: REQUESTED·IN_PROGRESS·UNKNOWN·AUTHORIZED·FAILED 3분류·EXPIRED) |
| 2-b | (CC §14-6) 반환 요약·취소 이력 | CC §8·§14-6, CN 판정 6 | `CancellationViews` | 목록·요약 | §4.4 | `GetPaymentCancellationApiSpec`(부분 → 진행 중 → UNKNOWN → 완료·실패 이력, 요약 합계) |
| 3 | (m-7) jOOQ 시각 조회 시 §9.1 정정 | 관례 §9.1, TZ-FIX-001 | §9.1 이미 정정·`UtcWallClock` 사용 중 | 교차 참조 보강·m-7 종료 기록(F-2) + 비UTC JVM 시험 | §6.3, §12 S4 | `PaymentStatusReaderTimeZoneSpec`(`withJvmTimeZone("Asia/Seoul")`) |
| S0 | VERIFY-GATE timeout 정리(Coordinator 적용) | coordinatorDecisions | 1800 < GRADLE-CHECK 2400 | 값 변경 + (선택) 자기 시험 | §12 S0 | (선택) `test_command_registry`의 timeout 합 검사 |

---

## 3. 현황 조사(2026-10-20, `b82e5d1`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것(이 작업) |
|---|---|---|
| web | 공개 포트 Bearer 체인(`PaymentApiSecurityConfiguration` — 무인증은 `/internal/**`·`/actuator/health`만, 401 원인 비구분), `ApiEntryFilter`(requestId·connector 격리), `ApiResponses`(바이트 응답·고정 문구), `ApiErrorCode.PAYMENT_NOT_FOUND`, `ConfirmPaymentRequestParser.isPaymentIdLike`(`^[!-~]{1,64}$`), `ApiRoutingAdvice`(없는 경로 404·405). 현재 `GET /v1/payments/{id}`는 매핑이 없어 라우팅 404 `NOT_FOUND` | GET Controller·응답 본문 |
| application | `AuthorizationView`(`AuthorizationViewStatus` AUTHORIZED·FAILED·IN_PROGRESS·UNKNOWN, `FailureCategory` DECLINED·NOT_SENT·NO_EFFECT), `CancellationView`·`CancellationViews`(취소 상태·실패 분류·반환 후 요약), confirm·cancel 서비스의 사후 소유 비교(`orderKey.environment/merchantAccountId` 비교 → NotFound) | Payment 단위 view·조회 서비스 |
| query(jOOQ) | `JooqAuthorizationViewReader`(Authorization + 승인 거래 1줄), `JooqCancellationViewReader`(취소 1건 + CANCEL 거래 + 잔액), `UtcWallClock`(`iso`·`literal`·`parse`), ArchUnit `queryAdapterDoesNotWriteWithJooq` — 모두 `@Transactional(readOnly = true)`, **범위 조건 없이 ID로 조회**(소유 비교는 호출자) | MID·환경을 WHERE로 강제하는 Reader, Payment 행 읽기 |
| schema | `payment`(PK payment_id, `uk_payment_order_generation`(env, MID, orderId, generation)), `payment_authorization`(UNIQUE payment_id), `payment_transaction`(AUTHORIZATION·CANCEL, 생성 열 UNIQUE), `return_balance`(PK payment_id), `payment_cancellation`(`ix_payment_cancellation_payment(payment_id, opened_at)`), `incident_hold`·`incident_hold_release` | 새 표·열·인덱스 불필요(§6.4) |
| 설정 | `spring.jpa.open-in-view: false`, driver `connectionTimeZone=UTC`, 세션 time_zone 기동 검사 | — |

### 3.2 발견(F-x)

1. **F-1 취소 응답 `cancellableAmount`가 음수일 수 있음**: domain `ReturnBalance.cancellableMinor = max(0, authorized − completed − reserved − forced)`(INV-C1 주석)인데 `CancellationViews.of`는
   `authorizedMinor − completedMinor − reservedMinor − forcedMinor`를 그대로 쓴다. FORCED 초과(D-CANCEL-006 "승인액 초과 가능")가 생긴 Payment에서 취소 키 재생 응답이 음수를 보일 수 있다.
   GetPayment가 같은 계산을 재사용하면 결함이 퍼진다 → **도출(domain 정의가 권위)**: 조회 쪽 계산을 domain 식과 같게(`max(0, …)`) 하고 취소 응답도 같은 함수로 고친다(§15-1 — CANCEL-001 DONE 경로 변경이므로
   Coordinator 판정. FORCED는 hold와 함께만 생기므로 Q-CPQ-01과도 닿는다).
2. **F-2 AC 3(m-7)의 §9.1 정정은 이미 들어가 있다**: 관례 §9.1 마지막 문단은 "jOOQ 생성 `DATETIME` 필드(`LocalDateTime`)는 UTC 벽시계가 아니다 … `DATE_FORMAT` 문자열로 읽어 `Instant.parse`"로
   정정돼 있고(MASTER-DOMAIN-001 RED), 바인딩 방향은 §13 표 "jOOQ 시각 바인딩" 행과 TZ-FIX-001이 다룬다. payment-api에는 AUTHN-001부터 jOOQ 시각 조회가 있다(`UtcWallClock`). 남은 것은
   (a) §9.1에서 바인딩 방향(§13 행)·payment 참조 구현(`UtcWallClock`)·TZ-FIX-001로 가는 교차 참조가 없고, (b) m-7 인계가 tasks.json에 열린 채 남은 것이다 → S4에서 §9.1에 교차 참조 한 문장을 더하고
   이 명세에 종료 근거를 기록한다(동작 변경 없음 — §15-2).
3. **F-3 VERIFY-GATE timeout 역전**: `commands.json` VERIFY-GATE `timeoutSeconds` 1800 < 내부 단계 GRADLE-CHECK 2400. 내부 단계 상한 합 = FINGERPRINT 60 + HARNESS-SELFTEST 120 + GRADLE-CHECK 2400 + TEST-SUMMARY 60
   + IMAGE-DIGESTS 60 + FINGERPRINT 60 = **2760초**(+ 단계별 SIGKILL 유예 최대 10초·XML 복사). 바깥 실행자가 1800초에 끊으면 GRADLE-CHECK가 자기 timeout 전에 잘려 판정이 FAIL/환경 중 무엇인지 흐려진다.
   CANCEL-001 §15-10 Coordinator 판정 "VERIFY-GATE 90분"과도 다르다 → §12 S0.
4. **F-4 HVC §2 하한 문구 오래됨**: HVC §2 표의 HARNESS-SELFTEST "minTests(68)"은 현재 91이다(문서 표기만 — 판정은 commands.json이 한다). S4 문서 정리에서 "commands.json 값"으로 바꾼다(가역, 동작 무관).
5. **F-5 기존 Reader는 범위 없이 ID로 조회한다**: `JooqAuthorizationViewReader.find`·`JooqCancellationViewReader.find`는 소유 비교를 호출자에 맡긴다(취소·confirm 서비스는 TX 안 잠금 읽기 뒤 비교). 원칙 §4
   "조회 Port도 권한/MID/environment 범위를 강제한다"를 GetPayment에서는 Reader 계약으로 강제한다(§6.1 — 기존 Reader는 바꾸지 않음, 비권위 내부 용도).
6. **F-6 취소 이력 상한 없음**: 부분취소는 금액 단위(최소 1 minor)로 나뉠 수 있어 이론상 Payment당 취소 행 수가 크다(진행 중 1건 제한은 동시성만 막는다 — D-CANCEL-003). 응답 크기 상한이 필요한지 §15-5.
7. **F-7 취소 응답에 `declineCategory` 없음**: CN CN-02(구현 결정)는 FAILED(DECLINED) 취소에 고정 `declineCategory`(`CANCEL_PERIOD_EXPIRED` 등)를 노출한다고 적었지만 `CancelPaymentController.body`에는
   `failureCategory`만 있다. `declineCategory`는 기관 메시지·required Port 결과(`CancellationCallResult.Declined`·`CancelInquiryCallResult.Found`)까지만 오고 persistence·query·web에는 없다
   (main grep 15건 전부 `CancellationPort`·`CancelInquiryPort`·`SimVanMessages`·`SimulatorVanCancellationAdapter`) — 영속되지 않으므로 조회가 읽을 원천도 없다. CANCEL-001 명세와 구현의 차이다. GetPayment는 취소 응답과 같은 필드 집합을 쓰므로(CQ-15) 이 차이를 그대로 따른다 — 추가할지는 §15-11.

---

## 4. API

### 4.1 endpoint

```text
GET /v1/payments/{paymentId}           (공개 포트, Authorization: Bearer <pc_test_sk_…|pc_live_sk_…>)
  본문 없음, Idempotency-Key 없음(조회 — 상태를 바꾸지 않는다)
```

- **도출(D-CP-003 "현재 상태는 별도 조회 API" + AC1 §10.2 + REST 관례)**: 경로는 생성 응답의 `paymentId`로 지목한다. HEAD는 Spring이 GET에 매핑한다(본문 없음, 같은 상태 코드 — 시험으로 고정).
  `POST`·`PUT` 등은 기존 `ApiRoutingAdvice` 405.
- **구현 결정 CQ-01**: 쿼리 파라미터는 받지 않는다(알 수 없는 쿼리 파라미터는 무시하지 않고 400 `INVALID_REQUEST` — 엄격 파서 관례, 미래 필터와 혼동 방지). 본문이 있으면 400.

### 4.2 인증·소유 확인(MID·환경)

| 순서 | 단계 | 결과 | 근거 |
|---|---|---|---|
| 1 | Bearer 필터(`MerchantBearerAuthenticationFilter` → `MerchantAuthenticator`, 투영 비잠금 읽기·캐시 없음) | 실패 401 `UNAUTHENTICATED`(원인 비구분), 저장소 일시 실패 503 | **확정** D-CPA-001, 관례 §11 |
| 2 | paymentId 형식(`isPaymentIdLike`) | 형식 밖 → 404 `PAYMENT_NOT_FOUND`(4.3과 같은 응답) | **확정** CN §4.1·AU §4.1 관례 |
| 3 | `PaymentStatusQuery.find(environment, merchantAccountId, paymentId)` → Reader가 `WHERE payment_id = ? AND environment = ? AND merchant_account_id = ?` | 행 없음 → `NotFound` → 404 | **확정** K-20·보안 13·39행, 원칙 §4. **구현 결정 CQ-02**: 범위를 사후 비교가 아니라 SQL 조건으로(남의 행이 application까지 올라오지 않는다) |
| 4 | view 조립 → 200 | §4.4 | §5 |

- **확정(D-MD-003·D-CPD-001)**: environment는 Credential 축이다 — 같은 MID의 TEST 키로 LIVE Payment를 조회하면 404. **확정(D-CPA-001)**: v1 키 권한 = 공개 가맹점 API 전체(operation 축 없음) → 조회 전용 권한 검사 없음.
- **확정(D-MD-006)**: MID 신규 결제 차단은 조회를 막지 않는다("차단은 신규 승인만 막고 조회·취소·정산은 허용"). Credential 폐기·만료는 1단계에서 401(조회도 불가).
- 행위자(Auditability): 조회는 상태를 바꾸지 않으므로 감사 행을 쓰지 않고 구조화 로그(`operation=GetPayment correlationId keyId접두 result`)만 남긴다(§10 — 질문 아님, §14.3).

### 4.3 존재 비노출

- **확정(CPC §9.1, CPA §7.5)**: 없음·다른 MID·다른 환경·형식 오류를 구분하지 않는다. 같은 HTTP 상태(404)·같은 `code`(`PAYMENT_NOT_FOUND`)·같은 고정 문구·같은 헤더 집합(`PayCore-Request-Id`만 값이 다름).
  본문·헤더·로그에 제시된 paymentId 원문을 echo하지 않는다(로그는 `result=NOT_FOUND`만 — CQ-09).
- **도출(구조적 동일 경로)**: 2·3 두 경우 모두 Reader가 `null`을 돌려 같은 분기로 응답한다 — "남의 것"만을 위한 분기가 코드에 없다(시간 차이 측정 시험은 신뢰도가 낮아 두지 않고, 구조 시험으로 고정: Reader
  계약 시험 + Controller 분기 1개). 지표 `paycore.payment.query{result}`의 `NOT_FOUND`는 세 경우를 합친 하나의 값이다.
- 기존 라우팅 404 `NOT_FOUND`와 업무 404 `PAYMENT_NOT_FOUND`의 구분: `GET /v1/payments/{id}`가 매핑된 뒤에는 이 경로의 모든 거절이 `PAYMENT_NOT_FOUND`다. `GET /v1/payments`(ID 없음)는 라우팅 404
  또는 405(경로가 POST만 있음)로 남는다 — 시험으로 고정(정보 노출 없음).

### 4.4 응답(200)

```json
HTTP/1.1 200 OK
PayCore-Request-Id: 6f1c…
Cache-Control: no-store
{
  "paymentId": "pay_01J…", "merchantOrderId": "order-0001", "amount": 10000, "currency": "KRW",
  "paymentCreatedAt": "2026-10-20T01:02:03.456Z",
  "status": "AUTHORIZED",
  "authorization": {
    "authorizationId": "pau_…", "status": "AUTHORIZED",
    "approvedAmount": 10000, "currency": "KRW", "plan": {"kind": "LUMP_SUM"}, "cardType": "CREDIT",
    "approvalNumber": "12345678", "approvedAt": "2026-10-20T01:03:00.000Z"
  },
  "returnSummary": {
    "authorizedAmount": 10000, "cancelledAmount": 3000, "cancelInProgressAmount": 0,
    "cancellableAmount": 7000, "returnStatus": "PARTIALLY_CANCELLED"
  },
  "cancellations": [
    {"cancellationId": "pcn_…", "merchantCancelId": "c-1", "status": "COMPLETED", "amount": 3000, "currency": "KRW",
     "requestedAt": "…Z", "cancelTransactionId": "ptx_…", "cancelApprovalNumber": "…", "cancelledAt": "…Z"}
  ]
}
```

| 필드 | 규칙 | 표시 |
|---|---|---|
| `paymentId`·`merchantOrderId`·`amount`·`currency`·`paymentCreatedAt` | 생성 응답 echo 집합과 같음(amount JSON 정수 — D-CPC-009, 시각 UTC ms `Z` — CPA §4.5) | 확정(CPA §4.5) + CQ-03 |
| `status` | §5.1 매핑(6값) | 확정(AC1 §5.1·§10.1) |
| `failureCategory` | `status=FAILED`일 때만 DECLINED·NOT_SENT·NO_EFFECT | 확정(AC1 §10.1) |
| `notice` | `IN_PROGRESS`·`UNKNOWN`일 때 고정 문구("결과 확인 중 — 같은 Payment로 다시 조회하라. 새 키·새 Payment로 재시도하지 마라") | 확정(AC1 §10.1 "새 키·새 Payment로 재시도 금지 안내", 범위 §3-8) |
| `authorization` | Authorization이 없으면(REQUESTED·EXPIRED) `null`. 있으면 `authorizationId`·`status`(confirm과 같은 4값), AUTHORIZED면 승인 거래 terms(금액·통화·Plan·cardType·승인번호·`approvedAt` = 거래 `confirmed_at`) | 확정(AC1 §10.1 응답 본문 = confirm과 같은 필드) |
| `returnSummary` | `return_balance` 행이 없으면(취소 시도 없음 — CC-02 지연 개설) AUTHORIZED Payment는 승인액 기준 `cancelled=0, inProgress=0, cancellable=승인액, NONE`, 그 밖 상태는 `null` | 도출(CC §4.3·CC-02) |
| `returnSummary.cancellableAmount` | domain 식 `max(0, authorized − completed − reserved − forced)`(F-1) | 확정(CC §4.3 INV-C1) |
| `cancellations` | 취소 operation 목록, `opened_at`·`cancellation_id` 오름차순. 항목 = `cancellationId`·`initiator`(MERCHANT·OPERATOR)·`merchantCancelId`(가맹점 취소만)·`status`(IN_PROGRESS·UNKNOWN·COMPLETED·FAILED)·`amount`·`currency`·`requestedAt`, COMPLETED면 `cancelTransactionId`·`cancelApprovalNumber`(있으면)·`cancelledAt`, FAILED면 `failureCategory`(취소 API 응답과 같은 필드 — `declineCategory` 없음, F-7 판정 11) | 확정(CC §8 응답 본문, **D-CPQ-002**) + CQ-04 |
| (rev 0.2) `onHold` | 그 Payment 자신에 활성 hold(해제 기록 없는 `incident_hold` 행)가 있으면 `true`, 없으면 `false` — 모든 상태에서 항상 포함. 사유·회차·FORCED/초과 없음 | **확정 D-CPQ-001** |
| (rev 0.2) 표시 범위 | `returnSummary`·`cancellations`·승인 terms는 공개 `status = AUTHORIZED`일 때만. FAILED/EXPIRED(P1 포함)는 `returnSummary = null`, `cancellations = []`, `authorization`은 상태·분류만 | **확정 D-CPQ-002**(D-CANCEL-013과 일치) |
| (노출 안 함) | 카드번호(저장 안 함), 인증 증거·`evidence_ref`, 기관 원문·PTX·기관 거래일/업무일, 기관 계약계정(D-MS2-005), 정책/권리/Route/Capability 버전(CPC §9.1), 운영자 신원·사유·approvalId·목적(D-ADMIN-004, CN §4.10), JPA version, `merchantSiteId`·`orderName`(생성 응답 관례), `paymentKey`(CQ-08), hold·FORCED·excess(**Q-CPQ-01**) | 확정(CPC §9.1·AC1 §10.1·CC §8) / CQ-08 / 미결 |

- **구현 결정 CQ-03**: 필드 집합 고정(시험이 키 집합을 정확히 비교 — CPA §4.5 관례). 없는 값은 키를 빼지 않고 `null`로 둘지 vs 키를 생략할지: confirm·cancel 응답은 **생략** 관례이므로 같게 생략한다
  (`authorization`·`returnSummary`만 상위 객체 `null` 허용 — 위 표).
- 마스킹: 이 응답에는 마스킹 대상 원문(카드번호·계좌·개인정보)이 애초에 없다(PAYMENT는 PAN을 저장하지 않는다 — 가상 카드 참조만, AC1 §5.3). 승인번호는 confirm 응답이 이미 노출하는 가맹점 정산 대조 값이다(확정 AC1 §10.1).

### 4.5 오류 매핑

| 판정 | HTTP | code | 비고 |
|---|---|---|---|
| 인증 실패 | 401 | `UNAUTHENTICATED` | 기존 필터, `WWW-Authenticate` |
| Credential 저장소 일시 실패 | 503 | `TEMPORARILY_UNAVAILABLE` | 기존 필터, `Retry-After` |
| 없음·다른 MID·다른 환경·형식 오류 | 404 | `PAYMENT_NOT_FOUND` | §4.3 |
| 쿼리 파라미터·본문 있음 | 400 | `INVALID_REQUEST` | CQ-01 |
| 조회 저장소 일시 실패(`PersistenceFailure.Unavailable`) | 503 | `TEMPORARILY_UNAVAILABLE` | `Retry-After`(문구의 "same Idempotency-Key"는 조회에 맞지 않음 → **CQ-11** 조회 전용 고정 문구 code 하나 추가 여부: 새 code `QUERY_UNAVAILABLE`보다 기존 code + 문구 분리(`ApiErrorCode` 항목 `QUERY_TEMPORARILY_UNAVAILABLE`, wireCode = `TEMPORARILY_UNAVAILABLE`) — OUTCOME_UNCONFIRMED와 같은 방식) |
| 행 사이 불일치(§6.2 불변식 위반) | 500 | `INTERNAL_ERROR` | 결함(일관된 스냅샷에서는 생길 수 없음), ERROR 로그(값 원문 없음) — CQ-07 |
| 결함 | 500 | `INTERNAL_ERROR` | `SafeThrowable` 요약만 |

- 409·422 업무 거절은 없다(조회는 판정을 하지 않는다). hold 중·UNKNOWN·전액 취소 완료도 200.

---

## 5. 상태 표현(AC 2)

### 5.1 Payment 요약 상태 → 공개 `status`(확정 AC1 §5.1·§10.1)

| Payment(`payment.status`) | Authorization | 공개 `status` | `authorization` | 그 밖 |
|---|---|---|---|---|
| REQUESTED | 없음(승인 시작 거절도 REQUESTED 유지 — D-AUTH-005) | `REQUESTED` | `null` | — |
| AUTHORIZING | OPEN | `IN_PROGRESS` | `{status: IN_PROGRESS}` | `notice` |
| UNKNOWN | UNKNOWN(또는 상충 hold로 UNKNOWN 유지 — D-AUTH-008) | `UNKNOWN` | `{status: UNKNOWN}` | `notice`("확인 중") |
| AUTHORIZED | AUTHORIZED + AUTHORIZATION 거래 | `AUTHORIZED` | 승인 terms | `returnSummary`·`cancellations` |
| FAILED | FAILED(근거 3종) | `FAILED` + `failureCategory` | `{status: FAILED}`(terms 없음) | 늦은 승인 거래(P1)·그 운영자 취소는 숨김(**D-CPQ-002**) — `returnSummary = null`, `cancellations = []` |
| EXPIRED | 없음 | `EXPIRED` | `null` | — |

- `failureCategory`(확정 AC1 §10.1, 기존 `FailureCategory`): `INSTITUTION_CONFIRMED_DECLINE` → DECLINED, `PRE_TRANSMISSION_FAILURE_CONFIRMED` → NOT_SENT, `INSTITUTION_CONFIRMED_NO_EFFECT` → NO_EFFECT.
  근거는 Authorization의 `failure_evidence`(V9 CHECK가 이 3종만 허용). EXPIRED는 분류 없음(근거 `NOT_TRANSMITTED_AND_SESSION_DISCARDED`는 공개하지 않는다 — 생성 응답·만료 이벤트 관례).
- **도출(AC1 §5.2 "요약 일치 불변식")**: Payment AUTHORIZING ⇔ Authorization OPEN, UNKNOWN ⇔ UNKNOWN, AUTHORIZED ⇔ AUTHORIZED(+ 거래), FAILED(Authorization 있으면) ⇔ FAILED. 한 스냅샷(§6.2)에서 어긋나면
  결함(CQ-07). 예외: D-CANCEL-011 P1(Payment FAILED + RECON이 기록한 늦은 승인 거래)은 RECON 기록 Use Case가 생기기 전에는 운영 경로로 만들어지지 않는다 — 표현은 Q-CPQ-02.
- **UNKNOWN 그대로 노출(확정 — §14.3)**: 범위 §3-8(D-AUTH-001 채택) "UNKNOWN은 확인 중으로 표시", AC1 §10.1 confirm 202 `UNKNOWN`(확인 중)이 이미 공개 어휘다. GetPayment가 다른 어휘(예: PROCESSING으로 합침)를
  쓰면 같은 Payment에 두 API가 다른 상태명을 주게 된다.
- **구현 결정 CQ-12**: `IN_PROGRESS`(AUTHORIZING)와 `UNKNOWN`을 합치지 않는다 — confirm 응답(AC1 §10.1)과 같은 구분. 가맹점 행동은 둘 다 "기다리고 같은 Payment로 조회"로 같다(notice 동일).

### 5.2 hold 중 Payment의 표현

- 현재 확정 규칙: hold는 새 Payment·승인 시작·가맹점 취소를 막고(D-CPD-004·D-CANCEL-001), 막힌 요청에 `ORDER_ON_HOLD`(고정 문구 "The order is on hold for investigation.")를 이미 가맹점에 돌려준다. Payment 상태는
  hold로 바뀌지 않는다(D-AUTH-002 "Payment 상태를 덮어쓰지 않는다").
- 따라서 hold 정보 없이도 `status`는 위 표대로 정확하다. (rev 0.2) **확정 D-CPQ-001**: `onHold`(boolean)만 더한다 — 활성 = 해제 기록 없는 hold(domain `OrderPaymentControl.activeIncidentHolds`와 같은 정의,
  `JooqAuthorizationBacklogReader`의 `NOT EXISTS incident_hold_release` 관례). 그 Payment의 `payment_id` hold만 보며 같은 주문의 다른 Payment hold는 보지 않는다. 사유·회차·FORCED/초과는 노출하지 않는다.

---

## 6. CQRS 조회 설계(jOOQ)

### 6.1 Port와 adapter

| 위치 | 타입 | 책임 |
|---|---|---|
| provided | `PaymentStatusQuery.find(environment, merchantAccountId, paymentId: String): PaymentStatusResult`(`Found(PaymentStatusView)` / `NotFound`) | 조회 Use Case. TX를 열지 않는다(Reader가 읽기 전용 TX 소유 — 기존 Reader 관례) |
| application | `PaymentStatusQueryService`(+ `PaymentStatusViews` 변환 — 순수 함수) | 형식 밖 paymentId → NotFound, 행 → view, 불변식 검사(CQ-07) |
| required | `PaymentStatusReader.find(environment, merchantAccountId, paymentId): PaymentStatusRow?` | **범위 인자 필수**(Port 시그니처가 강제 — 범위 없는 메서드 없음) |
| adapter.query | `JooqPaymentStatusReader` | 아래 SQL, `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, `DataAccessException` → `PersistenceFailure.Unavailable` |
| web | `GetPaymentController` | §4 |

- **구현 결정 CQ-05(명명 — 관례 §14)**: 조회 DTO Port는 `…Query`, jOOQ 조회 Port는 `…Reader` → `Jooq…Reader`, 서비스 `<업무><책임>Service`. 같은 `payment` Modulith 모듈(`@NamedInterface` 위치 불변).
- **구현 결정 CQ-06(비권위 경계 ArchUnit)**: `PaymentStatusReader`의 main 코드 사용처는 `PaymentStatusQueryService`뿐(위반 fixture 포함). 원칙 §4 "승인 가능 여부·한도·지급 결정에는 권위 있는 상태" — 조회 view가
  명령 판정에 쓰이는 경로를 구조적으로 막는다. 기존 `queryAdapterDoesNotWriteWithJooq`는 새 Reader에도 그대로 적용된다.

### 6.2 SQL과 일관성(잠금 없는 읽기)

```text
SELECT p.payment_id, p.merchant_order_id, p.amount_minor, p.currency, p.status, DATE_FORMAT(p.created_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
       a.authorization_id, a.status, a.failure_evidence,
       t.amount_minor, t.currency, t.plan_kind, t.installment_months, t.card_type, t.approval_number, DATE_FORMAT(t.confirmed_at, …),
       b.authorized_minor, b.completed_minor, b.reserved_minor, b.forced_minor,
       MULTISET( SELECT c.cancellation_id, c.merchant_cancel_id, c.initiator_kind, c.status, c.amount_minor, c.currency, c.failure_evidence,
                        DATE_FORMAT(c.opened_at, …), ct.transaction_id, ct.approval_number, DATE_FORMAT(ct.confirmed_at, …)
                 FROM payment_cancellation c LEFT JOIN payment_transaction ct ON ct.cancellation_id = c.cancellation_id AND ct.type = 'CANCEL'
                 WHERE c.payment_id = p.payment_id ORDER BY c.opened_at, c.cancellation_id )
FROM payment p
LEFT JOIN payment_authorization a ON a.payment_id = p.payment_id
LEFT JOIN payment_transaction t ON t.authorization_id = a.authorization_id AND t.type = 'AUTHORIZATION'
LEFT JOIN return_balance b ON b.payment_id = p.payment_id
WHERE p.payment_id = ? AND p.environment = ? AND p.merchant_account_id = ?
```

- **도출(쓰기 TX 구조 — AU §4.6·CN §4.6)**: Payment↔Authorization↔승인 거래는 같은 TX2에서, 취소↔CANCEL 거래↔`return_balance`(합계 열·예약)는 같은 취소 TX2에서 함께 커밋된다. 그러므로 **한 시점의 일관된 읽기**면
  요약 불변식(§5.1)과 "반환 요약 합 = 이력 상태 합"(완료 = COMPLETED 취소 금액 합, 진행 중 = OPEN·UNKNOWN 취소 금액 합 — FORCED 제외)이 성립한다. 서로 다른 시점에 읽으면(문장 여러 개 + READ COMMITTED) "Payment
  AUTHORIZED인데 Authorization OPEN" 같은 존재할 수 없는 조합을 보일 수 있다.
- (rev 0.2 m-4 — 실제 구현) **두 문장, 한 REPEATABLE READ 읽기 전용 TX**: 아래 (1)은 S2에서 거부됐다(§18 D-S2-1). 실제 일관성 근거는 (2) — InnoDB 일관 읽기 스냅샷이 TX 첫 읽기에 고정되어 문장 2가 문장 1과 같은 시점을 본다.
  결정적 증거: `PaymentStatusReaderSpec` "a cancel TX2 committed between the reader's two statements (m-3)"(문장 1 직후 다른 연결의 취소 TX2 커밋을 동기 대기 → 문장 2는 커밋 전 이력, TX 안 `@@transaction_isolation` = REPEATABLE-READ·`@@transaction_read_only` = 1; READ_COMMITTED 변이는 죽는다).
- (rev 0.1 원문) **구현 결정 CQ-05b(일관성 확보 방법, 겹 둘)**: (1) jOOQ `multiset`(MySQL에서 JSON 집계로 렌더링)으로 **한 SELECT 문** — InnoDB 일관 읽기는 문장 단위라 격리 수준과 무관하게 한 시점이다. (2) 그래도 Reader TX를
  `REPEATABLE READ` 읽기 전용으로 **명시**한다(여러 문장으로 나눠야 할 때도 첫 읽기 시점 스냅샷 하나 — runtime 연결 기본도 RR이지만 기본값 의존을 없앤다, 관례 §9 "Use Case TX는 READ COMMITTED"와 구분).
  `multiset` 안 시각도 `DATE_FORMAT` 문자열(§6.3)이라 JSON 직렬화 시간대 해석이 끼지 않는다. jOOQ `multiset`이 생성 코드·MySQL 8.4.11에서 기대대로 동작하지 않으면(S2 RED로 확인) (2)만으로 문장 둘(본 행 + 이력)로 낸다.
- **잠금 없음(확정 원칙 §3·§4)**: `FOR UPDATE`/`FOR SHARE`를 쓰지 않는다 — 조회가 명령 경로의 잠금 순서(관례 §17)에 끼어들지 않고, runtime 권한상 append-only 표 잠금 읽기는 1142다. 결과는 비권위
  표시값이며 응답 직후 바뀔 수 있다(조회 시점 이후 변화는 다음 조회로 — 202 안내와 같은 의미).
- (rev 0.2) 운영자 보정 취소도 이력에 들어가므로(D-CPQ-002) 아래 합계는 개시자 구분 없이 모든 취소로 계산한다. 반환·이력 합계 검사는 공개 `status = AUTHORIZED`일 때만(P1은 숨기므로 검사하지 않는다).
  AUTHORIZED·FAILED가 아닌 Payment에 취소 행이 있으면 위반이다(취소는 AUTHORIZED 또는 P1에만 생긴다 — D-CANCEL-011).
- **불변식 검사(CQ-07)**: view 조립 시 §5.1 요약 일치, `authorized_minor = 승인 거래 금액`, `completed_minor = Σ COMPLETED 취소 금액`, `reserved_minor = Σ OPEN·UNKNOWN 취소 금액` 중 하나라도 어긋나면
  view를 만들지 않고 500 + ERROR 로그(paymentId·위반 이름만). 일관된 스냅샷에서는 결함일 때만 생긴다(가짜 상태를 가맹점에 보이지 않는다 — fail-closed). 단, FORCED는 hold와 함께만 생기므로 검사식에서 뺀다.
- 성능·인덱스: PK 단건 + `uk_payment_authorization_payment`·`uk_payment_transaction_authorization`(생성 열)·`pk_return_balance`·`ix_payment_cancellation_payment(payment_id, opened_at)`·
  `uk_payment_transaction_cancellation`(생성 열)로 모두 인덱스 탐색이다 → **새 migration 없음**(§6.4). 실행 계획은 S2 시험에서 `EXPLAIN`으로 확인(전 표 `type ∈ {const, eq_ref, ref}`).

### 6.3 시각 규칙(UTC 리터럴 규칙 — 관례 §9.1·§13, TZ-FIX-001)

- **확정(관례 §9.1)**: 시각 열은 `UtcWallClock.iso(field)`(`DATE_FORMAT(col, '%Y-%m-%dT%H:%i:%s.%fZ')`) → `Instant.parse`. 생성 `LocalDateTime` 필드를 직접 읽지 않는다.
- **확정(관례 §13 "jOOQ 시각 바인딩")**: 시각 비교가 생기면 `UtcWallClock.literal(at)`(`CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))`, 바인딩 없음). **이 작업의 기본 SQL에는 시각 비교가 없다** — Q-CPQ-04가
  조회 기간 창을 채택할 때만 생긴다(그때 이 규칙과 비UTC JVM 시험을 함께 둔다).
- 날짜 열(기관 거래일 등)은 노출하지 않는다(§4.4). 내부 사용 시 `DATE_FORMAT(col, '%Y-%m-%d')`(AU 관례).
- **AC 3 처리(F-2)**: §9.1 정정 내용은 이미 있다. S4에서 §9.1 마지막 문단 끝에 "비교 값 바인딩은 §13 'jOOQ 시각 바인딩' 행(UTC 리터럴), payment 참조 구현 `payment.adapter.query.UtcWallClock`,
  전수 점검 TZ-FIX-001" 교차 참조를 더하고, m-7을 이 명세 §18에 종료 근거(어느 문단·어느 시험)로 기록한다. 증거 시험: `PaymentStatusReaderTimeZoneSpec`(JVM Asia/Seoul — 저장 `01:02:03.456` UTC가
  응답 `…T01:02:03.456Z`, 9시간 어긋나지 않음 / 대조로 생성 응답·confirm 응답 시각과 바이트 동일).

### 6.4 JPA와의 연결(원칙 §3 — 이번 변경에서 하는 일 → 예 → 이유 → 주의)

이 작업은 **JPA Entity·migration·쓰기 경로를 추가하지 않는다.** 조회가 JPA 쓰기 쪽과 만나는 지점만 설명한다.

- **Entity/Domain 분리와 조회 DTO**: 역할 — `JpaPaymentEntity`·`JpaPaymentAuthorizationEntity`·`JpaPaymentCancellationEntity` 등은 명령 쪽 adapter에만 있고, 조회는 Entity를 거치지 않고 jOOQ가 행을
  `PaymentStatusRow`(DTO)로 읽는다. 예 — Controller는 `PaymentStatusView`만 직렬화하고 Entity를 모른다(OSIV off — `open-in-view: false`). 이유 — LAZY 연관·영속성 컨텍스트·N+1·Entity 직렬화 위험이
  조회 경로에 없다(원칙 §3 "OSIV를 끄고 화면/API에는 DTO"). 주의 — 상태 의미(§5 매핑)는 domain enum 값에서 가져오되 Entity·Mapper를 재사용하지 않는다(조회가 `reconstitute` 검증 실패로 500이 나는 결합을 피함).
- **flush ≠ commit과 조회 가시성**: 역할 — TX2가 `flush()`로 SQL을 보낸 행도 commit 전에는 다른 연결(조회 TX)에 보이지 않는다. 예 — TX2 flush 직후 GET은 `IN_PROGRESS`, commit 뒤 GET은 `AUTHORIZED`이며 그 사이
  "AUTHORIZED인데 거래 없음"은 보이지 않는다(§9 C-1 시험이 `FaultInjectionConfig` hook으로 flush 뒤·commit 전 붙잡아 확인). 이유 — 업무 효과·요약·Outbox를 한 로컬 TX로 묶은 원칙(CLAUDE.md)이 읽는 쪽에서도
  원자적으로 보인다. 주의 — commit 불명확(`OutcomeUnknown`) 동안 조회는 이전 상태를 보일 수 있다 — 가맹점에게는 `IN_PROGRESS`·`UNKNOWN`의 "다시 조회" 안내로 충분하다.
- **`@Version`은 조회 계약이 아니다**: 역할 — `version` 열은 낙관적 잠금 기술 값이다. 예 — 응답·ETag에 쓰지 않는다(CQ-10). 이유 — 자식 행만 바뀌어도 `PESSIMISTIC_FORCE_INCREMENT`로 오르는 등 의미가 업무
  변경과 1:1이 아니다(관례 §9.1). 주의 — `return_balance.revision`(업무 revision)도 운영자 승인 대상 대조용이라 가맹점에 노출하지 않는다.
- **JPA와 jOOQ는 같은 TX가 아니다(원칙 §4)**: 조회 서비스는 명령 TX 안에서 호출되지 않는다(`LocalTransactions`는 바깥 TX를 거부, 조회는 자기 읽기 전용 TX). 명령 서비스가 이 Reader를 쓰지 못하게 CQ-06.
- **Flyway가 schema 기준**: 새 열·인덱스가 없으므로 migration·권한 migration·`DatabaseAccountSeparationSpec` 기대값 변화 없음. runtime 계정은 모든 업무 표 SELECT가 있다(관례 §9.2) — 조회에 새 권한 불필요.
  jOOQ 생성 코드는 기존 migration에서 재현된다.

### 6.5 캐시·ETag

- **구현 결정 CQ-10**: 서버 캐시 없음(원칙 §5 Redis 미도입, 무기한 로컬 캐시 금지 정신 — CPA §5.3). 응답 헤더 `Cache-Control: no-store`(금융 상태·승인번호가 중간 캐시·브라우저에 남지 않게). ETag·
  `If-None-Match`·304 없음(JPA version·업무 revision이 조회 표현과 1:1이 아니고, 조건부 요청이 주는 이득보다 오판 위험이 크다). 필요해지면 응답 본문 hash ETag로 후속 추가(가역).

### 6.6 rate limit

- **범위 밖(§15-6)**: 조회 남용(폴링 폭주) 제한은 기존 후속 "인증 실패 rate limit(fail-open 금지)"과 같은 작업에서 다룬다. Redis 없이 시작하며(ADR-001), 도입 시 장애 때 무조건 허용(fail-open)으로 바꾸지 않는다
  (원칙 §5). 이 작업은 202·조회 안내에 폴링 간격을 숫자로 적지 않는다(운영값 없음 — Demo 값도 문서에 넣지 않음).

---

## 7. 비노출·로그·관측

- 응답(§4.4 노출 안 함 행), 오류 본문(고정 문구 — CPA §7.5), 로그(`operation=GetPayment correlationId keyId접두 result` — paymentId는 **FOUND일 때만**(자기 MID 소유가 확인된 값) 상관용으로, NOT_FOUND는
  제시 값 원문 없음 — CQ-09), 지표 label(ID 없음).
- 지표(구현 결정 CQ-13): counter `paycore.payment.query{result}`(`FOUND`·`NOT_FOUND`·`UNAVAILABLE`·`INTERNAL_ERROR`), timer `paycore.payment.query.duration`(label 없음). 새 gauge 없음(UNKNOWN·hold
  적체 gauge는 기존 `paycore.payment.*`가 이미 센다).

---

## 8. 실행 구조 요약

```text
[가맹점 서버] GET /v1/payments/{paymentId} (Bearer)
  → ApiEntryFilter(requestId, connector 격리) → MerchantBearerAuthenticationFilter(401/503)
  → GetPaymentController: 쿼리·본문 없음 확인(400), 형식(404)
  → PaymentStatusQuery.find(env, mid, id)  ── TX 없음
       → PaymentStatusReader.find(env, mid, id)  ── 읽기 전용 RR TX 1개, 문장 2개(본 행 + 이력, 잠금 없음 — rev 0.2 m-4)
       → PaymentStatusViews.of(row) (불변식 CQ-07)
  → 200 JSON(no-store) / 404 / 503 / 500
```

외부 호출 없음, 쓰기 없음, Outbox 없음, 멱등 기록 없음(조회는 상태를 바꾸지 않는다 — 같은 요청 반복이 결과를 바꾸는 것은 상태가 실제로 바뀐 경우뿐).

---

## 9. 시험 계획(실제 MySQL 8.4.11 Testcontainers·실서버 HTTP — Mock 단독 금지)

| # | 시험 | 계층 | 기대 |
|---|---|---|---|
| T1 | 소유·환경 분리 | `@PaymentApiTest` | MID A 키로 A의 Payment 200 / MID B 키로 A의 paymentId 404 / 같은 MID TEST 키로 LIVE Payment 404 / 폐기·만료 키 401(본문에 paymentId 없음) |
| T2 | 존재 비노출 | `@PaymentApiTest` | (없는 ID, 남의 ID, 형식 밖 ID, 다른 환경 ID) → 상태·code·문구·헤더 이름 집합 동일, 본문 바이트는 requestId 외 동일. 본문·헤더·캡처 로그에 제시 ID 문자열 없음(`CapturedLogs`) |
| T3 | Reader 범위 계약 | `@PaymentDbTest` | `find(env, 다른 MID, id)`·`find(다른 env, mid, id)` → null. 시그니처에 범위 없는 메서드 없음(리플렉션 시험) |
| T4 | 상태 표 §5.1 | 단위 `PaymentStatusViewsSpec` + 실흐름 `GetPaymentStateApiSpec` | REQUESTED·IN_PROGRESS·UNKNOWN(stub VAN 응답 유실)·AUTHORIZED(Plan·cardType 조합)·FAILED 3분류(DECLINED·게이트 거절 NOT_SENT·무효과 NO_EFFECT)·EXPIRED(만료 Use Case). 명시 Fixture(`ConfirmFixture`·`CancelFixture`) |
| T5 | 생성 응답 분리(D-CP-003) | `@PaymentApiTest` | 생성 → 승인 → 같은 키 생성 재요청 본문 바이트 불변(`lifecycleAtCreation=REQUESTED`), GET은 `AUTHORIZED` |
| T6 | 반환 요약·이력 | `@PaymentApiTest` | 부분 3,000 COMPLETED → 진행 중 2,000(202 상태에서 GET: `cancelInProgressAmount=2000`, 이력 IN_PROGRESS) → UNKNOWN → 회복 COMPLETED → FAILED(DECLINED) 한 건: 요약 합 = 이력 합, `returnStatus` 전이, 전액 뒤 `cancellable=0`·`FULLY_CANCELLED` |
| T7 | F-1 음수 방지 | 단위(+ CANCEL-001 회귀) | FORCED 초과 행에서 `cancellableAmount = 0`(domain 식과 같음). 취소 응답 쪽은 §15-1 판정에 따름 |
| C-1 | 스냅샷 일관성(승인) | `@PaymentDbTest` 다중 연결 | TX2를 `FaultInjectionConfig`로 flush 뒤·commit 전 붙잡고 GET → 전부 이전 상태(IN_PROGRESS, 거래 없음), 놓은 뒤 GET → 전부 이후 상태. 반복 N=50 동안 불변식 위반 0(CQ-07 500 0건) |
| C-2 | 스냅샷 일관성(취소) | 같음 | 취소 TX2 commit과 GET 경합 N=50: `returnSummary`와 `cancellations` 합이 항상 일치(500 0건) |
| C-3 | 잠금 없음 | `@PaymentDbTest` | 다른 연결이 control·payment·payment_cancellation `FOR UPDATE`를 쥔 동안 GET이 `innodb_lock_wait_timeout` 없이 즉시 응답(`performance_schema.data_lock_waits` 0) — 명령 경로를 막지도 막히지도 않음 |
| Z-1 | 비UTC JVM | `@PaymentDbTest` + `withJvmTimeZone("Asia/Seoul")` | `paymentCreatedAt`·`approvedAt`·`requestedAt`·`cancelledAt`이 저장 UTC 벽시계와 같고 생성·confirm·cancel 응답의 같은 시각과 바이트 동일. 대조: 생성 `LocalDateTime` 필드로 읽는 변이 구현은 9시간 어긋나 RED |
| A-1 | ArchUnit | `ArchitectureSpec` | `PaymentStatusReader` 사용처 = `PaymentStatusQueryService`만(위반 fixture), `queryAdapterDoesNotWriteWithJooq` 유지, web은 Entity 미참조 |
| H-1 | 헤더·메서드 | `@PaymentApiTest` | `Cache-Control: no-store`, ETag 없음, HEAD 같은 상태·본문 없음, POST/PUT 405, 쿼리 파라미터·본문 400 |
| E-1 | 저장소 장애 | `@PaymentApiTest` | 조회 연결 실패 주입 → 503 `TEMPORARILY_UNAVAILABLE`(조회 문구)·`Retry-After`, 로그에 SQL 원문 없음 |
| P-1 | 실행 계획 | `@PaymentDbTest` | `EXPLAIN` 전 표 인덱스 탐색(전체 scan 없음) |
| R-1 | 회귀 | 기존 전체 | confirm·cancel·create 응답 바이트·시험 기대값 불변(F-1 판정이 취소 응답 변경을 허용한 경우만 그 1행) |

- Fixture(Test Fixture Policy): 핵심 상태는 공식 경로(생성 → 결제창 인증 → confirm → 취소, stub VAN 시나리오)로 만드는 Named Fixture를 쓴다. Fixture Monkey는 쓰지 않는다(상태 조합은 업무 의미가 드러나야 함).
  불변식 위반 행(CQ-07 시험)은 root 연결 우회 INSERT/UPDATE로 만드는 **Invalid Fixture**로 의도를 이름에 적는다(`inconsistentSnapshotForDefectDetection`).
- RED 기록: 단계마다 RED-C(컴파일)·RED-A(실행)·핵심 규칙 변이 RED(범위 WHERE 제거 → T1/T3 실패, 문장 분리 + READ COMMITTED → C-1 실패 관찰 시도, `max(0)` 제거 → T7, 생성 필드 직접 읽기 → Z-1).
  C-1의 변이 RED는 경합 타이밍상 재현이 불확실할 수 있다 — 재현 못 하면 "변이 RED 미확보"로 정직하게 기록한다(통과로 주장하지 않음).

---

## 10. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거(시험·근거) |
|---|---|---|
| Financial Consistency | 적용(표시) | 금액·통화·승인 terms를 원 거래 행에서 그대로(재계산 없음), 반환 요약 = domain 식(F-1 `max(0)`), 요약 합 = 이력 합 불변식(CQ-07·T6·C-2). 상태를 바꾸지 않음 |
| Transaction Safety | 적용(읽기 측) | 두 문장·한 RR 읽기 전용 TX 스냅샷(C-1·C-2·m-3 결정적 시험), 잠금·쓰기 없음(C-3, ArchUnit jOOQ 쓰기 금지), 명령 TX 안 호출 없음(CQ-06) |
| Idempotency | 해당 적음 | 조회는 안전·멱등(상태 불변, 멱등 기록 없음). D-CP-003 생성 재생 불변(T5). 이유: 쓰기 경로 없음 |
| Concurrency Control | 적용(비간섭) | 명령 잠금과 경합 없음(C-3), 진행 중 쓰기와 동시 읽기 일관성(C-1·C-2) — 실제 MySQL 다중 연결 |
| Recoverability | 간접 | UNKNOWN을 "확인 중"으로 정확히 표시해 가맹점이 새 결제·새 취소로 우회하지 않게(notice — 범위 §3-8, AC1 §10.1), 저장소 장애 503(E-1), 재시작 영향 없음(상태 없음) |
| Reconciliation | 간접 | 가맹점 대사용 값(승인번호·거래 ID·완료 시각·반환 합)을 원 거래 행에서 제공. hold는 boolean `onHold`만(**D-CPQ-001**), 운영자 보정 취소는 `initiator: OPERATOR`만·P1 숨김(**D-CPQ-002**), 반환 가능액은 FORCED를 뺀 권위 값(**D-CPQ-005**) — RECON·WEBHOOK과 연결 |
| Auditability | 해당 적음 | 조회는 감사 행을 남기지 않는다(상태 변경 없음 — §14.3). 응답 출처(어느 거래 행)는 결정적이고 구조화 로그에 correlationId·keyId 접두·result |
| Observability | 적용 | `paycore.payment.query{result}`·duration timer(ID label 없음), 로그 비노출(T2 캡처), 결함 불변식 위반 ERROR 로그(CQ-07) |

---

## 11. BOM·Kafka/Redis

- **BOM**: 새 의존성 없음. jOOQ(Boot 4.1.1 BOM 관리 버전 — `gradle/libs.versions.toml` 그대로) `multiset`은 MySQL 렌더링이 다중 문장이라 쓰지 않았다(rev 0.2 — §18 D-S2-1, CQ-05b (2) 적용). 추가 의존 없음. 빌드 파일 불변 계획 — 바뀌면 GRADLE-DEPS×5·CVE-SCAN.
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 조회 일관성은 소유 DB 한 스냅샷이 보장하며 projection·캐시를 두지 않는다. 브로커·캐시가 상태의 권위나 금융 안전성을 대체한다고 주장하지 않는다(원칙 §5) —
  나중에 조회 부하로 캐시·projection을 두더라도 승인·취소·한도 판정은 권위 있는 PAYMENT 상태를 쓴다(원칙 §4).

---

## 12. 단계별 구현 계획(2단계 TDD — 각 단계 시험 먼저 → RED-C/RED-A 기록 → 최소 구현 → 핵심 규칙 변이 RED)

| 단계 | 내용 | 의존·막힘 | 시험(추정) |
|---|---|---|---|
| S0 | **(Coordinator 적용)** `harness/commands.json` VERIFY-GATE `timeoutSeconds` 1800 → **5400**(CANCEL-001 §15-10 판정 "VERIFY-GATE 90분"과 같은 값, 내부 상한 합 2760초 + 여유). 선택: HARNESS-SELFTEST에 "VERIFY-GATE timeout ≥ 내부 단계 timeout 합 + 단계 수×10초" 검사 1건(`harness/tests` — 하한 91 → 92). 기준 시험 수 측정(모듈별), 질문 답·§15 판정 반영(rev 0.2), §18 기록 절 개설 | Coordinator | (0~1) |
| S1 | application: `PaymentStatusQuery`·결과·`PaymentStatusView`, `PaymentStatusViews`(§5.1 표·실패 분류·반환 요약 `max(0)`·CQ-07 불변식), `PaymentStatusReader` Port(범위 인자 필수), `PaymentStatusQueryService` | 없음(hold·운영자 필드는 Q 답 전 미구현) | 20~30 |
| S2 | `JooqPaymentStatusReader`(두 문장·RR 읽기 전용·UTC 문자열 — rev 0.2), T3·C-1·C-2·C-3·Z-1·P-1, ArchUnit A-1 | S1 | 12~20 |
| **검토 #1** | S1·S2(상태 표·스냅샷 증거·범위 WHERE) | — | — |
| S3 | `GetPaymentController`(쿼리·본문 거절, 형식 404, no-store, 오류 매핑·CQ-11 문구), T1·T2·T4(실흐름)·T5·T6·H-1·E-1, 지표·로그 | S2 | 15~25 |
| S4 | 문서: 관례 §9.1 교차 참조(AC 3·F-2)·새 절 §18(CP-QUERY-001 관례), HVC §2 하한 문구(F-4)·§7 기록, AC1 §10.2·CPA §4.6에 "구현됨 → 이 명세" 교차 참조, `DOMAIN-DEFINITION`(조회 행 있으면), F-1 판정에 따른 취소 응답 수정(판정 시), 하한 갱신은 실측으로만(Coordinator), 전체 check → VERIFY-GATE 보고 | 전부 | — |

- TDD 편차 방지: view 매핑 표는 기대값을 이 명세 §5.1에서 독립 기술하고 시험이 표 전 행을 돈다. 변이 RED 예: 범위 WHERE 제거, `UNKNOWN`을 `IN_PROGRESS`로 합침, `max(0)` 제거, `LocalDateTime` 직접 읽기,
  `@Transactional` isolation 제거 + 문장 분리.
- timeout 제안(§15-8): 단계 모듈 시험 30분, VERIFY-GATE 90분(S0 값과 일치), 수정 반복 실패당 3회. 호스트 메모리 부족 중단은 FAIL/환경 문제로 기록(TZ-FIX-001 §6.3 선례).

---

## 13. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| CQ-01 | GET에 쿼리 파라미터·본문 받지 않음(있으면 400) | 엄격 파서 관례, 미래 필터와 혼동 방지 |
| CQ-02 | 소유 범위를 Reader SQL WHERE로 강제(사후 비교 아님), Port 시그니처에 범위 필수 | 원칙 §4, 남의 행이 application에 오지 않음, 비노출 경로 단일화 |
| CQ-03 | echo 필드 = 생성 응답 집합(`paymentId`·`merchantOrderId`·`amount`·`currency`·`paymentCreatedAt`), 없는 값은 생략(상위 객체만 null) | CPA §4.5·confirm/cancel 응답 관례 |
| CQ-04 | 취소 이력은 `opened_at`·`cancellation_id` 오름차순 전체(상한은 §15-5 판정) | 결정적 순서, 기존 인덱스 |
| CQ-05 | 명명 `PaymentStatusQuery`/`PaymentStatusQueryService`/`PaymentStatusReader`/`JooqPaymentStatusReader`/`GetPaymentController`/`PaymentStatusViews` | 관례 §14 |
| CQ-05b | (rev 0.2) 두 문장(본 행 + 이력, 각자 범위 조건) + 한 `REPEATABLE READ` 읽기 전용 TX, 잠금 없음 — `multiset` 안은 거부(D-S2-1) | §6.2 스냅샷 일관성 |
| CQ-06 | ArchUnit: `PaymentStatusReader` 사용처는 조회 서비스뿐 | 비권위 view가 명령 판정에 쓰이지 않게(원칙 §4) |
| CQ-07 | 스냅샷 불변식 위반 시 view를 만들지 않고 500(결함) | 존재할 수 없는 금융 상태를 가맹점에 보이지 않음 |
| CQ-08 | `paymentKey` 미노출 | 조회 목적에 불필요, 통합 이벤트도 제외(CPC §10·D-AUTHN-008) — 노출 최소화 |
| CQ-09 | NOT_FOUND 로그에 제시 paymentId 원문 없음, FOUND만 paymentId 상관 | 입력 echo 금지(CPA §7.5) |
| CQ-10 | `Cache-Control: no-store`, 서버 캐시·ETag 없음 | §6.5 |
| CQ-11 | 조회 저장소 장애 503은 기존 code `TEMPORARILY_UNAVAILABLE` + 조회용 고정 문구(새 `ApiErrorCode` 항목, wireCode 공유) | 기존 문구의 "same Idempotency-Key"가 조회에 맞지 않음, 계약 code 집합 불변 |
| CQ-12 | `IN_PROGRESS`·`UNKNOWN` 구분 유지(confirm과 같은 어휘) | AC1 §10.1 공개 어휘 일치 |
| CQ-13 | 지표 `paycore.payment.query{result}`·duration timer | Observability, label에 ID 없음 |
| CQ-14 | REQUESTED의 결제창 만료 시각(`checkout_expires_at`)은 v1 미노출 | 필요 근거 없음 — 추가는 가역 |
| CQ-15 | 취소 이력 항목의 필드 = 취소 API 응답 본문과 같은 집합(현재 `declineCategory` 없음 — F-7, 추가 여부는 §15-11) | 두 API 어휘 일치 |
| CQ-16 | (최종 검토 m-1) 결과 로그 줄의 keyId는 앞 6자만(`keyIdPrefix`) — keyId는 비밀이 아니지만(키 원문·secret 아님) 로그에는 상관에 충분한 접두만. 기존 관례가 없어 이 작업이 정함 | CQ-09 결과 줄, 가역 |

---

## 14. 질문 후보(Coordinator → 사용자) — 답변됨(rev 0.2)

### 14.0 답변 대응표

| 질문 | 결정 | 답변 요지 | 반영 |
|---|---|---|---|
| Q-CPQ-01 | **D-CPQ-001** | 권장안: 그 Payment 자신의 활성 hold만 `onHold` boolean | §4.4, §5.2, §6.2 |
| Q-CPQ-02 | **D-CPQ-002** | 권장안: AUTHORIZED의 운영자 보정 취소는 `initiator: OPERATOR`만, P1(FAILED/EXPIRED)의 늦은 승인·운영자 취소는 숨김 | §4.4, §5.1, CQ-07 |
| Q-CPQ-03 | **D-CPQ-003** | 권장안: paymentId 조회만 | §1.2 |
| Q-CPQ-04 | **D-CPQ-004** | 권장안: 조회 기간 제한 없음, 삭제 경로 없음 | §6.3(시각 비교 없음 유지) |

아래 원문(rev 0.1)은 질문 당시 기록이다.

### Q-CPQ-01 결제 조회에 사고 보류(hold)·초과 반환 가능성(FORCED/excess)을 보여 줄지

- **배경**: hold는 늦은 승인(FAILED 뒤 성공 — `SUCCESS_AFTER_TERMINATION`), 상충 승인(`CONFLICTING_INSTITUTION_RESULT`), 취소 결과 상충(`CANCEL_RESULT_CONFLICT`)에서 열리고, 2인 승인 + 재대사 MATCH 전에는 풀리지 않는다
  (D-AUTH-002·D-CANCEL-006). Payment 상태는 바뀌지 않는다. 가맹점은 지금도 막힌 요청(생성 재요청·confirm·취소)에 `ORDER_ON_HOLD`("조사 중 보류") 오류로 보류 사실을 알 수 있다(D-CPD-004·D-CANCEL-001). 조회에
  보류 사실·사유·FORCED 금액(실제 반환이 있었을 수 있는 금액)을 넣을지는 정하지 않았다. 특히 FAILED Payment에 보류가 있으면 "실패했지만 카드 승인이 실제로 났을 수 있음"을 뜻한다.
- **권장안**: **보류 사실만 불리언으로 노출**(`onHold: true` — 그 Payment 자신에 활성 hold가 있을 때). 사유·발생 순번·관찰·FORCED/excess 금액·대사 진행은 노출하지 않는다. 이유 — 가맹점이 이미 `ORDER_ON_HOLD`로 알 수 있는
  사실의 범위를 넘지 않으면서, 조회만 하는 가맹점 CS가 "FAILED이니 다른 수단으로 환불/재결제"하지 않게 한다(CN §4.11 운영 안내의 취지와 같은 방향). 사유는 조사 중 사실이라 확정 전에 외부에 보이면 오해·분쟁 근거가 된다.
- **대안**: (b) **노출하지 않음**(현재 공개 범위 그대로 — 조치 요청 때만 `ORDER_ON_HOLD`). 가장 보수적이나 조회만 보는 가맹점은 FAILED를 최종 무결제로 오해할 수 있다. (c) 사유 분류까지 노출(`LATE_SUCCESS`·
  `CONFLICTING_RESULT`·`CANCEL_CONFLICT`) — 가맹점 대응이 쉬우나 조사 중 사실을 외부 확정처럼 보이게 한다. (d) FORCED 금액까지 노출 — 반환 가능성 금액을 알리지만 확정 전 금액이다.
- **영향**: 응답 필드 1개(권장)·hold 활성 판정 SQL(`incident_hold` − `incident_hold_release`, occurrence 단위 — domain 규칙을 SQL로 복제하는 위험은 시험으로 고정), WEBHOOK-001의 보류 통지 여부와 일관성,
  CS 안내 문구, F-1(FORCED와 `cancellableAmount`).
- **막히는 작업**: hold 필드와 그 SQL만(S2·S3의 일부). 나머지는 진행 가능(답 전 = 필드 없음).

### Q-CPQ-02 운영자 보정 취소와 늦은 승인 사실(P1)을 가맹점 조회에 보여 줄지

- **배경**: 운영자 보정 취소(D-CANCEL-005, 2인 승인)는 (i) AUTHORIZED Payment의 사고·대사 보정, 가맹점 키 폐기 뒤 남은 취소 의무, (ii) FAILED/EXPIRED로 종료된 P1의 늦은 승인을 RECON이 거래로 기록한 뒤의 환불
  (D-CANCEL-010·011·013 — "가맹점은 원래 '실패'를 통보받음")에 쓰인다. (i)은 가맹점 정산(반환 차감)에 영향이 있고 `cancellableAmount`에도 이미 반영된다. 가맹점 통지 여부는 WEBHOOK-001에 넘어가 있다(CN 판정 6).
- **권장안**: **(i) AUTHORIZED Payment의 운영자 보정 취소는 이력에 포함**하고 `initiator: "OPERATOR"`만 붙인다(`merchantCancelId` 없음, 운영자 신원·사유·approvalId·목적 없음 — D-ADMIN-004·CN §4.10). **(ii) 가맹점에
  FAILED/EXPIRED로 보이는 Payment에는 늦은 승인 거래·그 운영자 취소를 보이지 않는다**(상태 `FAILED`·`failureCategory`만 — D-CANCEL-013 "가맹점 취소 경로 열지 않음·가맹점은 실패를 통보받음"과 일치). 이유 — (i)을 숨기면
  `cancellableAmount`(승인액 − 반환)가 가맹점이 아는 취소 합과 맞지 않아 가맹점 대사가 깨진다. (ii)는 가맹점 계약 상태를 바꾸지 않기로 한 결정의 연장이다.
- **대안**: (b) 운영자 취소는 모두 숨김 — 가맹점 대사 불일치(요약과 이력 합이 다름), 이를 설명할 필드가 필요. (c) 모두 노출(P1 포함) — FAILED Payment에 승인·취소 이력이 붙어 "실패" 통보와 충돌.
  (d) (i)만 노출하되 `initiator`를 빼고 일반 취소처럼 — 가맹점이 자기가 요청하지 않은 취소를 자기 요청으로 오인.
- **영향**: Reader의 `initiator_kind` 사용, P1 판정(Payment FAILED ∧ Authorization 거래 있음)의 표시 규칙, CQ-07 불변식의 P1 예외, WEBHOOK-001 통지 계약과 일관성, RECON 기록 Use Case가 생긴 뒤의 표시.
- **막히는 작업**: 이력의 운영자 행 표시·`initiator` 필드·P1 표시 규칙만. 가맹점 취소 이력·요약은 진행 가능. 운영자 진입점이 아직 없으므로(D-CANCEL1-001 — ADMIN-BOOT-001이 연다) 운영 경로에서 운영자 취소
  행은 생기지 않는다. 답 전에는 운영자 행을 이력에 넣지 않고, 운영자 행이 있는 Payment(시험 fixture로만 생김)는 요약·이력 불일치를 숨기지 않도록 CQ-07에 따라 500으로 막는다(fail-closed — 잘못된 합계를 보이지 않음).

### Q-CPQ-03 merchantOrderId(주문 ID)로도 조회할지

- **배경**: 주문 1:N Payment 이력이다(활성 하나, CLAUDE.md). 가맹점은 생성 응답·생성 재요청(30일 — D-CPC-004)·`ORDER_*` 거절의 `referencePaymentId`로 paymentId를 얻는다. 응답 유실이 30일 넘게 방치되면
  같은 키 재생은 새 요청이 되지만, 같은 주문 입력 재요청이 `EXISTING_ACTIVE_PAYMENT`·`ORDER_ALREADY_AUTHORIZED`(+ referencePaymentId)로 수렴해 복구 경로가 있다.
- **권장안**: **v1은 paymentId 조회만.** 이유 — 복구 경로가 이미 있고, 주문 조회는 "어느 Payment를 돌려줄지(현재·승인된 것·전체 이력)"라는 표현 규칙을 새로 정해야 한다(FAILED 이력 노출 범위가 Q-CPQ-02와 얽힘).
- **대안**: (b) `GET /v1/payments?merchantOrderId=` → 그 주문의 Payment 전체 이력(최신순, 같은 MID·환경 범위) — 가맹점 편의, 응답 크기·페이지 규칙 필요. (c) 주문의 현재(활성 또는 승인된) Payment 하나만 — 단순하나
  "현재" 정의(`order_payment_control.current_payment_id`/`authorized_payment_id`)를 공개 계약으로 고정.
- **영향**: 새 경로·인덱스(기존 `uk_payment_order_generation` 접두로 충분), 비노출 규칙(남의 주문 = 빈 목록 vs 404), 쿼리 파라미터 정책(CQ-01).
- **막히는 작업**: 주문 조회 경로만(권장안이면 없음).

### Q-CPQ-04 결제 조회 가능 기간(오래된 Payment의 조회·보존)

- **배경**: 멱등 기록 보존 30일(D-CPC-004)은 요청 재생에만 해당하고, Payment·거래·관찰은 영구(append-only 금융 사실)다. 가맹점 조회에 기간 제한을 둘지, 보존·파기 정책은 정하지 않았다(법정 보존 기준 확인 포함).
- **권장안**: **v1은 조회 기간 제한 없음**(인증된 그 MID·환경의 Payment면 생성 시점과 무관하게 조회). 보존·파기는 별도 정책(LIVE 준비 Gate 검토 항목)으로 남기고 이 작업은 삭제 경로를 만들지 않는다. 이유 — 현재 삭제 경로가
  없어 기간 창은 "데이터가 있는데 404"를 만들 뿐이고, 가맹점 대사·분쟁 대응에 오래된 거래 확인이 필요하다.
- **대안**: (b) 생성 후 N일(Demo) 이내만 조회, 이후 404 — 운영값 결정 필요, 시각 비교가 생겨 UTC 리터럴 규칙(§6.3) 적용. (c) N일 이후 요약만(이력 생략).
- **영향**: §6.3 시각 비교 유무, 비노출 규칙(기간 밖 = 없음과 같은 404), 보존·파기 정책 작업 등록.
- **막히는 작업**: 없음(권장안이면 만들 것이 없다). (b)(c)면 S2에 조건·시험 추가.

### 14.3 검토했으나 질문으로 올리지 않은 것

| 항목 | 처리 근거 |
|---|---|
| UNKNOWN을 그대로 노출 vs 가맹점용 "처리 중"으로 합침 | **확정**: 범위 §3-8(D-AUTH-001 채택) "UNKNOWN은 확인 중으로 표시", AC1 §10.1 confirm 202 `UNKNOWN`(확인 중)·§10.2. 이미 공개 어휘라 GetPayment만 다르게 하면 같은 Payment에 두 상태명 — CQ-12. **Coordinator 확인 요청**: 사용자에게 다시 물을 필요가 있다고 보면 질문으로 올린다(권장안 = 현행 유지) |
| 404 vs 403(남의 자원) | 확정 K-20·CPC §9.1(403/404 통일, 존재 비노출) |
| TEST 키로 LIVE 조회 | 확정 D-MD-003·D-CPD-001(환경 = Credential 축) → 404 |
| MID 신규 결제 차단 중 조회 | 확정 D-MD-006(조회 허용) |
| 조회 전용 권한(operation 축) | 확정 D-CPA-001(v1 = 공개 API 전체) |
| 카드번호·인증 증거·기관 원문·계약계정·내부 버전 비노출 | 확정 AC1 §10.1·CC §8·CPC §9.1·D-MS2-005 |
| 승인번호 노출 | 확정 AC1 §10.1(confirm 응답이 이미 노출) |
| 운영자 신원·사유 비노출 | 확정 D-ADMIN-004·CN §4.10(payload에도 없음) |
| 조회 감사 기록 | 상태 변경이 아닌 읽기 — 확정 원칙 §1(Audit은 업무 증거, Observability는 운영 진단)에 따라 구조화 로그로 충분. 가맹점 조회 접근 감사가 컴플라이언스상 필요하면 LIVE Gate 보안 검토에서 별도 결정 |
| 캐시·ETag | CQ-10(가역, 금융 의미 없음 — 없는 쪽이 보수적) |
| rate limit | 기존 후속(fail-open 금지) — §15-6 |
| 열린 질문(Q-CALENDAR·Q-CORRECTION·Q-SETTLE-CURRENCY·Q-BATCH-*·Q-MD-06/07/18·Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF) | 이 작업을 막지 않음(조회는 정산일·환율·관리자 값을 노출하지 않는다) |

---

## 15. 범위 관찰(Coordinator 판정 — rev 0.2에서 판정됨, 머리말 참조)

판정 요약: 1 이 작업에서 F-1 수정(공유 함수·취소 시험 기대 행 추가), 2 AC 3 충족 방식 승인, 3 단건 GET 없음, 4 목록·검색은 후속 후보 CP-QUERY-002, 5 이력 전체, 6 rate limit 범위 밖, 7 `Location` 없음,
8 timeout 30분/90분·taskTimeoutSeconds 5400·dependsOn CANCEL-001, 9 timeout 합 자기 시험 S0 포함(하한은 Coordinator), 10 경로 그대로, 11 `declineCategory` 추가 안 함·CANCEL-001 문서 정정. 아래 원문은 기록이다.

1. **F-1 취소 응답 음수 `cancellableAmount`**: CANCEL-001(DONE) 경로의 `CancellationViews`를 domain 식(`max(0, …)`)과 같게 고칠지 — 이 작업에서 같은 함수를 공유하며 함께 고치는 것을 제안(allowedWritePaths 안,
   취소 응답 시험 기대값 1행 추가 — 기대값 완화가 아니라 domain 정의 일치). 별도 결함 작업으로 분리할 수도 있다.
2. **AC 3(m-7) 처리 방식**: 정정 내용은 이미 있으므로 "교차 참조 보강 + 종료 기록 + 비UTC JVM 시험"으로 AC 3을 충족한 것으로 볼지(F-2).
3. **취소 단건 조회 GET**: CN §15-6(rev 0.1 원문)은 "취소 단건 조회"도 CP-QUERY-001로 적었지만 Coordinator 판정 (6)은 "이력·잔액 요약"만 넘겼다. 이 명세는 단건 GET을 만들지 않는다(GetPayment 이력으로 대체,
   202 뒤 확인은 같은 키 재요청 — CC-05). 확인 필요.
4. **거래 목록·검색 API**: 관례 §12(268행)·CN §8.5가 "거래 목록"을 CP-QUERY-001과 함께 적었다. AC에 없으므로 범위 밖(후속 작업 등록 여부 — 예: CP-QUERY-002, 가맹점용, 페이지·기간 필터·비노출 규칙).
5. **취소 이력 상한(F-6)**: 응답에 이력 전체를 싣는다(CQ-04). 상한(예: 최근 N건 + `historyTruncated`)이 필요하면 지금 정하는 것이 공개 계약상 쉽다 — 권장: v1 전체(부분취소는 한 번에 하나라 실제 건수가 작고,
   상한 수치는 운영값이 필요). 판정 요청.
6. **rate limit**: 범위 밖(기존 후속, fail-open 금지) 확인.
7. **생성 응답 `Location` 헤더**: CPA §4.5는 GetPayment 경로가 없어서 `Location`을 두지 않았다. 생성 응답 계약(필드·헤더) 불변을 위해 이 작업에서도 추가하지 않는 것을 제안.
8. **timeout·의존**: tasks.json `taskTimeoutSeconds` null → 단계 모듈 시험 30분·VERIFY-GATE 90분 제안. `dependsOn`에 CANCEL-001(취소 이력·반환 요약 표) 추가 제안(기록 정합성).
9. **S0 harness 변경**: VERIFY-GATE 5400(F-3)은 Coordinator 적용. 선택 자기 시험(timeout 합 검사)을 넣으면 HARNESS-SELFTEST 하한 +1 — 넣을지 판정.
10. **허용 경로**: tasks.json allowedWritePaths로 충분(`apps/payment-api/**`·`docs/repository/**`·`harness/commands.json`·`harness/tests/**`). `apps/institution-simulator/**`·`libs/**`·`docker/**`·`compose.yaml`·`.env.example`은
    쓸 일이 없을 것으로 본다(ArchUnit 규칙을 `libs/test-support`의 공용 규칙으로 둘 경우만 `libs/**`).
11. **F-7 `declineCategory`**: CANCEL-001 CN-02와 구현이 다르다. (a) 명세를 구현에 맞춰 정정(노출 안 함)할지, (b) 취소 응답·GetPayment 둘 다에 추가할지(기관 거절 분류 노출 — D-CANCEL-004·007 안내 목적,
    영속 열 추가 migration 필요 — 이 작업 규모 +α) 판정 요청. 이 작업은 판정 전에는 추가하지 않는다.

---

## 16. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 조회 view가 명령 판정에 재사용되어 비권위 값으로 금융 결정 | CQ-06 ArchUnit, Port 이름·주석 |
| R2 | 여러 문장 읽기로 존재할 수 없는 조합 노출 | 한 RR 읽기 전용 TX(CQ-05b (2)), CQ-07 불변식, C-1·C-2·m-3(READ_COMMITTED 변이 죽음) |
| R3 | 남의 MID·환경 데이터 노출·존재 추론 | Reader WHERE 강제(CQ-02), T1~T3, 단일 NotFound 분기 |
| R4 | 비UTC JVM 시각 9시간 어긋남 | `UtcWallClock` 문자열 읽기, Z-1 + 변이 RED |
| R5 | jOOQ `multiset`의 MySQL 렌더링 | **현실화**(다중 문장 거부) → CQ-05b (2) 적용(§18 D-S2-1) |
| R6 | hold·운영자 취소 노출 결정 전 부분 구현이 의미를 선점 | Q 답 전 필드·행 없음(fail-closed — 현재 공개 범위 유지) |
| R7 | 폴링 폭주가 명령 경로 DB 부하로 | 잠금 없음·인덱스 탐색(C-3·P-1), rate limit 후속(§15-6) |
| R8 | 경합 일관성 변이 RED 재현 불확실 | m-3 결정적 시험(문장 사이 동기 커밋)으로 해소 — READ_COMMITTED 변이 죽음(§18.6) |
| R9 | 호스트 메모리 부족으로 전체 check 중단(선례) | 모듈 단위 실행 기록, 중단은 FAIL/환경, VERIFY-GATE로 최종 확인 |

---

## 17. 규모 추정

- 기준: AUTH-001(payment-api +~313건), CANCEL-001(AUTH-001의 0.8~1.0배). 이 작업은 새 표·migration·외부 호출·회복이 없고 조회 하나 + 시험 대역 재사용.
- **AUTH-001의 약 0.15~0.25배**. 시험 수 추정: payment-api +45~75(단위 20~30, DB 12~20, API 15~25), harness +0~1. GRADLE-CHECK 하한 2092 → 실측(약 2,140~2,170), HARNESS-SELFTEST 91 → 91~92. 하한은 실측으로만 올린다.
- 일정 감(구현자 1 + 검토 1~2회): S1~S2 0.5일, S3 0.3일, S4·gate·최종 검토 0.3일 — 약 1~1.5일. 질문 답 지연은 hold 필드(Q-CPQ-01)·운영자 행(Q-CPQ-02)·주문 조회(Q-CPQ-03)만 막는다.

---

## 18. 2단계 실행 기록(구현 중 갱신 — 구현자 보고, Verifier/Reviewer 판정 아님)

실행 환경(공통): WSL2 Ubuntu, Docker Desktop 29.8.0, MySQL 8.4.11 Testcontainers, 호스트 메모리 7GiB. Gradle 실행은 모두 `./gradlew --no-daemon --console=plain :apps:payment-api:test --tests …`(대상 spec만,
foreground, 한 번에 하나 — 스크립트 `/tmp/cpq_run.sh`가 시작·종료 UTC·exit·JUnit XML 합계를 출력). 전체 `check`·VERIFY-GATE는 아직 실행하지 않았다(검토 #1 뒤 S3·S4).

### 18.1 S0 — 판정 반영·문서 정정·하네스 자기 시험

- rev 0.2 머리말(D-CPQ-001~004·§15 판정). 시작 시 `git status`: Coordinator 소유 `docs/DECISIONS.md`·`harness/{commands,progress,questions,tasks}.json` 수정 상태(읽기만), commands.json VERIFY-GATE 5400(Coordinator 적용 확인).
- **F-7 정정(판정 11)**: `CANCEL-001-CANCELLATION.md` §4.1 CN-02 문단에 정정 주석, §13 CN-02 행 정정, `CANCEL-CONTRACT-001` §18 "§8 응답 본문" 행을 "계약 그대로(추가 없음) — v1 비노출, Port 수준만"으로 정정. 코드 변경 없음.
- **timeout 합 자기 시험(판정 9)**: `harness/tests/test_module_registry.py`에 `gate_timeout_problems(registry)`(VERIFY-GATE ≥ Σ GATE 단계 timeout + 단계마다 SIGKILL 뒤 대기 2×`POST_KILL_WAIT_SECONDS` —
  `verify-gate.py`의 `GATE`·상수를 importlib로 읽음)와 `test_verify_gate_timeout_covers_its_stages`(대조: 1800으로 줄인 사본은 문제로 잡힘 + 실제 registry는 문제 없음). 현재 값: 내부 최악 2760 + 6×20 = 2880 ≤ 5400.
  - RED-A(2026-10-20T05:54:16Z, `python3 -B -m unittest harness.tests.test_module_registry -v`): stub(`return []`) → `test_verify_gate_timeout_covers_its_stages` FAIL(`[] is not true`).
  - GREEN(05:54:22Z): 그 시험 ok. 전체 HARNESS-SELFTEST `discover`: **Ran 92, FAILED(failures=1)** — `test_selftest_minimum_equals_number_of_harness_tests`가 `91 != 92`(시험 하나 추가로 하한 불일치 —
    **Coordinator가 commands.json HARNESS-SELFTEST minTests를 실측 92로 올려야 통과**. 구현자는 commands.json을 바꾸지 않았다 — 판정 9).
  - 변이: stub 반환(위 RED)만 확인. `2×POST_KILL_WAIT` 항 제거 변이는 현재 값(5400)에서 살아남는다(대조 1800이 GRADLE-CHECK 2400보다 작아 어느 식이든 잡힘) — 여유 항의 정밀 경계 시험은 두지 않았다(기록).

### 18.2 S1 — F-1 공유 함수·application view·서비스

- **F-1(판정 1)**: domain `ReturnBalance` companion에 `cancellableMinor(authorized, completed, reserved, forced) = max(0, authorized − (completed + reserved + forced))`(addExact)·`returnStatus(authorized, completed)`를 두고
  인스턴스 getter와 `CancellationViews.of`가 모두 이 함수를 쓴다(식 하나). `CancellationViews`에 `status()`·`failureCategory()`를 함수로 꺼내 GetPayment 이력이 재사용.
  - **추가 기대 행**(완화 아님): `CancelFlowSpec` "a late success of a declined cancel (FORCED)"의 두 번째 경우 끝에 "같은 키 재생 → `replayed = true`, `cancellableAmountMinor = 0`". 기존 단언 불변(취소 명령을 변수로 꺼낸 것뿐).
  - RED-A(05:55:50Z–05:56:53Z, `CancelFlowSpec`, exit 1): 16건 중 1 실패 `expected:<0L> but was:<-4000L>` — 결함 재현. GREEN(05:57:31Z–05:58:53Z, `CancelFlowSpec`+`ReturnBalanceSpec`+`ArchitectureSpec`, exit 0): 78건 실패 0.
- **새 타입(명명 CQ-05)**: provided `PaymentStatusQuery`(+ `PaymentStatusResult`·`PaymentStatusInconsistent`), `PaymentStatusView`(+ `PaymentViewStatus`·`CancellationInitiator`·`ReturnSummaryView`·`CancellationHistoryItem`),
  required `PaymentStatusReader`(+ 행 4종, 범위 인자 필수), application `PaymentStatusViews`(internal object)·`PaymentStatusQueryService`(@Service, TX 없음).
- 시험: `PaymentStatusViewsSpec`(§5.1 전 행, D-CPQ-001 onHold, D-CPQ-002 운영자 `initiator`·P1 숨김, F-1 FORCED 초과 → 0, CQ-07 Invalid Fixture 7규칙), `PaymentStatusQueryServiceSpec`(범위 전달, 다른 MID/환경/없음/형식 밖 = NotFound, 형식 밖은 Reader 미호출).
  Named Fixture `PaymentStatusRowFixture`(Invalid는 `inconsistent…` 이름). Fixture Monkey 미사용.
  - RED-A(06:01:12Z, stub `TODO()`, exit 1): 14/14 실패(NotImplementedError). GREEN(06:02:20Z, exit 0): 16건 실패 0.
  - 변이 RED(모두 죽음): M1 UNKNOWN→IN_PROGRESS 합침(06:02:58Z, 1 실패), M2 FAILED에도 이력·요약 표시(06:03:29Z, 2 실패), M3 `maxOf` 제거(06:03:53Z, FORCED 행 실패), M4 서비스가 environment를 TEST로 고정(06:04:24Z, NotFound 행 실패),
    M5 완료 합계 검사 제거(06:05:00Z, CQ-07 행 실패). 각 변이 뒤 원본 복원(cmp 확인).

### 18.3 S2 — jOOQ Reader·실 MySQL·ArchUnit

- `JooqPaymentStatusReader`(@Component, `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`): Payment + 승인 + 승인 거래 + 반환 잔액 + 활성 hold(`EXISTS … NOT EXISTS incident_hold_release`)를
  범위 WHERE(payment_id·environment·MID)로 한 문장, 취소 이력(+ CANCEL 거래)을 같은 TX의 두 번째 문장(`ORDER BY opened_at, cancellation_id`)으로. 시각 `UtcWallClock.iso`, 날짜 `DATE_FORMAT('%Y-%m-%d')`, 시각 비교 없음.
- **편차 D-S2-1(CQ-05b (1) → (2))**: jOOQ `multiset` 한 문장안은 MySQL 렌더링이 `set @t = @@group_concat_max_len; set @@group_concat_max_len = 4294967295; select …` 다중 문장을 만들어 driver가 문법 오류로 거부했다
  (06:09:52Z 실행, `SQLSyntaxErrorException … near 'set @@group_concat_max_len'`). `allowMultiQueries`를 켜거나 세션 변수 렌더링을 끄는(1024자 잘림 위험) 대신 명세 §6.2 대안 (2) — **한 RR 읽기 전용 TX의 두 문장**으로 바꿨다.
  InnoDB 일관 읽기 스냅샷은 TX 첫 읽기에 고정되므로 두 문장이 같은 시점을 본다(C-1·C-2 시험). R5 위험이 현실화된 것이며 의미 변경은 없다.
- 시험 `PaymentStatusReaderSpec`(@PaymentApiTest — 운영 confirm/cancel 경로, stub VAN, runtime 계정): T3 범위(다른 MID·다른 환경·없음·남의 Payment = null), 상태별(REQUESTED·DECLINED FAILED·PENDING UNKNOWN),
  운영자·가맹점·거절 취소 이력과 잔액 합계(D-CPQ-002), 활성 hold(D-CPQ-001), C-1(승인 TX2·취소 TX2의 flush 뒤 commit 전 다른 스레드 읽기 = 이전 상태 전체, 커밋 뒤 = 새 상태 전체), C-2(취소 4회 커밋과 경합하는
  연속 조회 — 불일치 예외 0), C-3(다른 연결이 control·payment·payment_authorization·return_balance·payment_cancellation `FOR UPDATE` 보유 중 조회 즉시 반환 < 2초), Z-1(JVM Asia/Seoul — 생성·승인·취소 요청·취소 완료 시각 =
  root `CAST(col AS CHAR)` UTC 원문, 취소 API `cancelledAt`과 같음).
  - RED-A(06:07:49Z, stub, exit 1): 9/9 실패. 1차 구현(multiset) 9/9 실패(위 편차). 2차(06:11:45Z) 2 실패 — **시험 결함 2건 수정**(기대값 완화 아님): (a) 시험 시계가 고정이라 세 취소의 `opened_at`이 같아
    순서가 `cancellationId` 순이 됨 → 시험이 취소마다 `MutablePaymentClock`을 1초씩 밀어 요청 순서를 시각으로 만든다(계약 "opened_at, cancellation_id 순" 그대로), (b) C-1에서 "그 시점 view"를 커밋 **뒤**에 다시 조회하고 있었음
    → 그 시점에 읽은 행으로 `PaymentStatusViews.of`를 만들어 IN_PROGRESS 확인. GREEN(06:13:31Z–06:14:47Z, exit 0): 9건 실패 0.
  - 변이 RED: MS1 범위 WHERE 제거(06:15:25Z, T3 실패 — 죽음), MS5 승인 거래 join의 `type = 'AUTHORIZATION'` 제거(06:17:49Z, 취소 있는 5개 시험 TooManyRows — 죽음), MS2b 생성 `LocalDateTime` 필드를 UTC로 해석
    (06:19:22Z, Z-1 실패 `01:00:03.456Z` vs `10:00:03.456Z` — 9시간 어긋남, 죽음). **MS2(LocalDateTime을 JVM 기본 시간대로 되돌리는 변이)는 살아남았다**(06:16:27Z) — driver(UTC 순간) → jOOQ(JVM 시간대 벽시계) →
    같은 시간대로 되돌림이 상쇄되어 실제로 올바른 값이다(결함 변이가 아님 — 관례 §9.1의 설명과 일치). 시험 부족이 아니라 무효 변이로 기록.
  - 확보 못 한 변이: RR isolation 제거(연결 기본도 RR이라 차이 없음), hold 해제 조건 제거(해제 쓰기 경로가 아직 없음 — RECON), 같은 주문의 다른 Payment hold(그 상태를 공식 경로로 만들 수 없음). 기록만.
- **ArchUnit CQ-06**: `ArchitectureSpec` "only PaymentStatusQueryService uses PaymentStatusReader" + 위반 fixture `archfixture.statusreader.CommandServiceUsingStatusReader`.
  RED-A(06:20:57Z, 아무것도 막지 않는 stub 규칙 → fixture 위반 없음, 44건 중 1 실패) → GREEN(06:22:21Z, 44건 실패 0).
- 회귀(대상 묶음): reg1(06:23:30Z–06:25:26Z: architecture 전부·컨텍스트 smoke·MainConfiguration·ReturnBalanceSpec·PaymentStatus*·adapter.query 전부·CancelPaymentApiSpec) 116건 실패 0, reg2(06:25:31Z–06:27:31Z:
  application `Cancel*`·OperatorCorrectionCancelSpec·CancellationRecoverySpec·CancellationPersistenceSpec) 91건 실패 0. 전체 모듈 실행은 아직 안 했다.
- P-1(EXPLAIN)은 S3로 미룬다(편차 — 계획 §9는 S2).

### 18.4 바뀐 기대값·편차 요약(검토 #1 대상)

| 항목 | 내용 | 성격 |
|---|---|---|
| `CancelFlowSpec` FORCED 경우 | 재생 응답 `cancellableAmountMinor = 0` 행 **추가** | 기대 추가(판정 1), 기존 단언 불변 |
| `harness/tests/test_module_registry.py` | 시험 1건 추가 → SELFTEST 하한 불일치(91 ≠ 92) | Coordinator가 하한 갱신 필요 |
| D-S2-1 | multiset → RR TX 두 문장 | 명세 대안 (2) 적용 |
| `ReturnBalance` | companion 식 2개 추가, getter가 사용(동작 동일) | domain 식 공유 |
| CANCEL-001·CC 문서 | CN-02 `declineCategory` 정정 | 판정 11 |
| (검토 #1) C-2 N | 4 → **50**(계획값, 100원 취소 50회 커밋과 경합, 조회 ≥ 50회) — 축소 없음 | m-3 |
| (검토 #1) P-1 | S2 → **S3**로 이동(§18.7 — 실행 계획 시험은 문장 캡처 listener가 필요해 m-3 probe와 함께 만들었다) | 편차 기록 |
| (검토 #1) `CancelFlowSpec` 재생 행 | `returnStatus = FULLY_CANCELLED` 단언 **추가** | n-7, 기대 추가 |
| (검토 #1) harness | `test_verify_gate_timeout_boundary` 추가 → 시험 수 **93**(Coordinator가 하한 92 → 93) | n-8 |

### 18.6 중간 검토 #1(PASS_WITH_FIXES — H 없음) 대응·D-CPQ-005

모두 시험 먼저(RED-A) → 수정 → 변이. Gradle은 대상 spec만 foreground.

- **D-CPQ-005**(U-1 답변 — 반환 가능액은 FORCED를 뺀 권위 값, 다른 필드로 FORCED를 추론할 수 있음은 허용된 추론): `PaymentStatusViewsSpec` "a FORCED return within the balance" 행 추가(승인 10,000·FORCED 4,000 → 6,000, NONE).
  현재 코드가 이미 맞아 RED-A는 없다(특성 시험) — 변이 MF3(식에서 forced 항 제거, 07:33:57Z)가 이 행으로 죽는다.
- **m-1**: 이력 문장이 스스로 범위를 건다(payment와 payment_id·environment·MID join). 이력 문장을 `cancellationHistory(...)`(같은 RR 읽기 전용 TX 규칙)로 꺼내 단독 시험
  ("the cancellation-history statement on its own"). RED-A(07:26:32Z, 범위 없는 추출본): 다른 MID에 1행 반환 → 실패. GREEN 07:29:49Z. 변이 MF1(이력 범위 join 제거 — 본 행 gate만 남김, 07:31:03Z) 죽음.
- **m-2**: `PaymentStatusQueryService`가 `CannotCreateTransactionException`·`TransactionSystemException`·`UnexpectedRollbackException` → `PersistenceFailure.Unavailable`. RED-A(07:25:28Z): 원 예외 그대로 → 실패.
  변이 MF4(첫 catch 제거, 07:34:41Z) 죽음. API E-1은 **TX 시작(연결 획득) 단계**에서 주입한다(`CommitFaults.armBeginFailure("JooqPaymentStatusReader")` — 시험 TM 위임의 `getTransaction` 앞, §18.7).
- **m-3**: 결정적 스냅샷 시험 — 시험 전용 jOOQ `ExecuteListener` `SnapshotProbe`(FaultInjectionConfig bean, 무장 전 무동작)가 문장 1(`active_hold` 별칭) 직후 같은 TX 연결로 `@@transaction_isolation`·`@@transaction_read_only`를
  읽고, 결제 취소 TX2(Outbox append 지점에서 붙잡아 둔 다른 스레드·연결)를 놓아 **커밋 완료까지 동기 대기**한다. 기대: 관측 `REPEATABLE-READ`·`1`, 문장 2의 이력 = 커밋 전(OPEN), 잔액 = 문장 1(예약 3,000), view 불일치 없음, 다음 조회는 COMPLETED.
  현재 코드로 처음부터 통과(RED-A 없음 — 증거 시험). 변이 MF2(`Isolation.READ_COMMITTED`, 07:32:39Z) → 이 시험과 C-2가 함께 죽는다(READ COMMITTED면 문장 2가 커밋 뒤 이력을 봐 CQ-07 위반). C-2 N = 50(§18.4).
- **m-4**: "한 SELECT" 문구를 "두 문장, 한 REPEATABLE READ 읽기 전용 TX"로 정정(§0, §6.2, §8, §10, §11, §12, §13 CQ-05b, §16 R2·R5·R8 — m-3 시험 인용).
- **n-1**: 형식 술어를 domain `PaymentId.isPresentable`(printable ASCII 1..64)로 옮겨 web(`ConfirmPaymentRequestParser.isPaymentIdLike` — 위임)과 조회 서비스가 같은 함수를 쓴다. 서비스는 `IllegalArgumentException`만 잡는다.
  RED-A(07:25:28Z): `"pay 01"`·`"pay_한글"`이 Reader까지 감(호출 수 4 → 6). 변이 MF6(서비스의 술어 검사 제거, 07:35:39Z) 죽음. `ConfirmPaymentRequestParserSpec` 회귀 통과(07:28:45Z).
- **n-4**: 이력 짝 규칙을 이름 있는 CQ-07 규칙으로 — `CANCELLATION_TRANSACTION`(COMPLETED ⇔ CANCEL 거래·완료 시각), `CANCELLATION_FAILURE_EVIDENCE`(FAILED ⇔ 근거), `CANCELLATION_INITIATOR_KEY`(가맹점 ⇔ merchantCancelId).
  숨겨지는 행도 검사한다. RED-A(07:25:28Z): `IllegalArgumentException`(view init) → 실패. 변이 MF5(FAILED 근거 검사 제거, 07:35:12Z) 죽음.
- **n-5**: ArchUnit `only adapter.web calls PaymentStatusQuery` + 위반 fixture `archfixture.statusquery.SchedulerUsingStatusQuery`. RED-A(stub 규칙, 07:25:28Z) → GREEN(07:28:45Z).
- **n-6**: `PaymentStatusView` init의 중복 단언 정리 — 반환 요약 조건은 `AUTHORIZED ⇔ returnSummary` 한 줄, 이력 조건만 따로(해석: 첫 require의 `returnSummary == null` 부분이 둘째 줄과 겹침). 다른 곳을 뜻했다면 Reviewer 지적 바람.
- **n-7**: `CancelFlowSpec` 재생 행에 `returnStatus = FULLY_CANCELLED` 추가(07:29:49Z GREEN — 특성 단언).
- **n-8**: `test_verify_gate_timeout_boundary`(합 = 통과, 합 − 1 = 문제, 합은 시험이 독립 계산). 07:23:35Z 통과(하한 불일치 1건만 — 92 ≠ 93). 변이(헬퍼에서 kill 대기 항 제거, 07:23:41Z) → 경계 시험 실패(죽음). **harness 시험 수 93**.
- **n-2·n-3**(EXPIRED P1, legacy REQUESTED→FAILED 근거 없음 → CQ-07 500): 기록만 — Coordinator가 RECON-CONTRACT-001로 인계.

### 18.7 S3 — GetPayment Controller·API 시험·P-1

- `GetPaymentController`(`GET /v1/payments/{paymentId}`): 쿼리 파라미터·본문 400, `PaymentStatusQuery` 결과 → 200(§4.4 필드)·404 `PAYMENT_NOT_FOUND`, provided `PaymentStatusResult.Unavailable(FailureKind)` → 503 `QUERY_TEMPORARILY_UNAVAILABLE`(최종 검토 n-2 정정 — §18.8 ArchUnit 수정 뒤 web은 `PersistenceFailure`를 모른다)
  (새 `ApiErrorCode` 항목 — wire code `TEMPORARILY_UNAVAILABLE`, 문구에 Idempotency-Key 없음, `Retry-After`), `PaymentStatusInconsistent` → 500(로그 `rule=`만), 그 밖 500(`SafeThrowable`). 모든 응답 `Cache-Control: no-store`.
  notice(IN_PROGRESS·UNKNOWN): "The payment result is being confirmed. Query this payment again later; do not retry with a new key or create a new payment." 지표 `paycore.payment.query{result=FOUND|NOT_FOUND|INVALID_REQUEST|UNAVAILABLE|INTERNAL_ERROR|UNAUTHENTICATED}`·
  `paycore.payment.query.duration`. 로그는 실패만(제시 paymentId 없음) — **최종 검토 m-1로 바뀜: 결과마다 INFO 한 줄(§18.9)**.
- 시험 `GetPaymentApiSpec`(10건): 필드 집합·확인 승인 모양·전액 반환 요약·no-store·ETag 없음·비노출(paymentKey·기관 계약계정·PTX), 존재 비노출 5경우(다른 MID·같은 MID 다른 환경·없음·비ASCII·65자) 같은 404·같은 헤더 이름 집합·
  본문/헤더/로그 echo 없음·NOT_FOUND 지표 +5, 폐기 Credential 401, 상태별(REQUESTED·FAILED(DECLINED)·UNKNOWN + notice), 운영자·가맹점·거절 이력과 요약(운영자 신원·approvalId·사유 없음), hold → `onHold` true(사유 없음),
  T5(생성 → GET REQUESTED → 만료 Use Case → GET EXPIRED, 같은 키 생성 재생 본문 바이트 동일), H-1(HEAD 200 본문 없음, POST 405, 쿼리·본문 400), E-1(TX 시작 실패 → 503 조회 문구·Retry-After, 로그에 주입 메시지 없음, 다음 조회 200),
  Invalid Fixture(root로 payment.status를 AUTHORIZING으로 바꿈 → 500, 로그 `rule=PAYMENT_AUTHORIZATION_SUMMARY`).
  - RED-A(07:39:10Z, 501 stub): 10건 중 9 실패(폐기 401은 필터라 통과). GREEN: 1차(07:41:42Z) 1 실패 — **시험 결함**: Jackson 3 `JsonNode.map`이 Kotlin `Iterable.map`이 아니어서 null(본문은 정상 — 원소를 색인으로 꺼내도록 수정, 기대값 불변).
    최종 GREEN 07:45:12Z 10건 실패 0.
  - 변이: MW1 쿼리·본문 검사 제거(07:46:37Z, H-1 죽음), MW2 404에 제시 ID echo(07:47:55Z, 비노출 시험 죽음), MW4 notice 제거(07:50:36Z, 죽음), MW5 503 → 500(07:51:49Z, E-1 죽음).
    **MW3(Found 응답의 `Cache-Control: no-store` 제거)는 살아남았다**(07:49:12Z) — Spring Security 기본 header writer가 `no-cache, no-store, max-age=0, must-revalidate`를 이미 쓴다(두 겹 방어 중 한 겹 제거라 관찰 가능한 차이 없음). 시험은 "no-store 포함"을 계약으로 보므로 그대로 둔다.
- **P-1(EXPLAIN — S2에서 이동)**: `SnapshotProbe.capture()`가 두 문장을 값 넣은(inlined) SQL로 모으고 runtime 연결에서 `EXPLAIN`: 모든 행 `type ≠ ALL`, `Extra`에 filesort 없음, 첫 문장 계획에 `incident_hold`·`incident_hold_release`(EXISTS·NOT EXISTS) 존재.
  07:53:29Z 통과(특성 시험). 변이 MP1(이력 ORDER BY를 `amount_minor`로, 07:54:56Z) → filesort로 P-1 실패 + 순서 시험 실패(죽음).

### 18.8 S4 — 문서·전체 `:apps:payment-api:check`

- 문서: 관례 §9.1 교차 참조(AC 3 — m-7 종료: §13 바인딩 행·`UtcWallClock`·TZ-FIX-001·Z-1 시험), 관례 새 §18(CP-QUERY-001 관례 + JPA 연결), HVC §2 하한 문구(F-4 — 숫자 대신 commands.json 원본)·§7 CP-QUERY-001 항목,
  AUTH-CONTRACT-001 §10.2·CP-API-001 §4.6 교차 참조. (Edit 도구가 AUTH-CONTRACT-001 전체를 CRLF로 바꾼 것을 LF로 되돌림 — git diff는 삽입 3줄뿐.)
- **1차 전체 check(07:56Z 시작, `--no-build-cache --rerun-tasks`)**: 호스트 쪽 대기 client가 Claude Code 메모리 회수로 끊겼지만 WSL 안 Gradle은 끝까지 돌았다 — **BUILD FAILED 12m12s, 1480건 중 1 실패**:
  `ArchitectureSpec > web adapter does not use persistence/query/integration adapters or application.required`(규칙 `webAdapterUsesOnlyApplication`) — `GetPaymentController`가 503 매핑을 위해
  application.required `PersistenceFailure`를 catch하고 있었다(대상 spec만 돌린 S3 회귀 묶음에 ArchitectureSpec이 S3 Controller 추가 **뒤** 포함되지 않아 놓친 결함 — 기록).
- **수정(규칙 완화 없음)**: provided `PaymentStatusResult.Unavailable(reason: FailureKind)` 추가. `PaymentStatusQueryService`가 `PersistenceFailure`(분류 유지)와 TX 시작·종료 실패(`DB_UNAVAILABLE`)를 이 결과로 돌려주고,
  Controller는 provided 결과만 보고 503(조회 문구·Retry-After)을 쓴다(cancel·confirm의 provided 실패 타입과 같은 방식). `PaymentStatusQueryServiceSpec` m-2 기대는 "throw `PersistenceFailure.Unavailable`" →
  "provided `Unavailable(DB_UNAVAILABLE)` 결과"로 **모양이 바뀌었다**(분류 503 의미는 같음 — Reviewer 확인 대상), 저장소 실패 분류 유지 행 1건 추가.
  RED-A(08:32:09Z): 2 실패(원 예외 그대로). GREEN(08:33:03Z, PaymentStatus* + ArchitectureSpec + GetPaymentApiSpec): 75건 실패 0(E-1 TX 시작 실패 503 포함).
- **2차 전체 check(분리 실행 — `setsid nohup … < /dev/null &`, `disown`, pgrep 확인)**: 2026-10-20T08:34:36Z–08:45:53Z, `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks :apps:payment-api:check`,
  **exit 0, BUILD SUCCESSFUL in 11m 15s**. `> Task :apps:payment-api:test`·`:check` 실행 줄 있음, `test`에 FROM-CACHE/UP-TO-DATE 없음(UP-TO-DATE는 `:libs:test-support:classes`·`jooqCodegenClasses` 생명주기 task 2줄뿐).
  JUnit XML: **보고서 138개, tests 1481, failures 0, errors 0, skipped 0**, timestamp 08:37:52Z–08:45:40Z(모두 시작 뒤). 이 작업 spec: GetPaymentApiSpec 10, PaymentStatusReaderSpec 12, PaymentStatusViewsSpec 15,
  PaymentStatusQueryServiceSpec 5, ArchitectureSpec 45, CancelFlowSpec 16 — 모두 실패 0. (대기 client 하나가 다시 메모리 회수로 끊겼고, 결과는 분리 실행의 meta·로그·XML에서 읽었다.)
- HARNESS-SELFTEST: Ran 93(07:59:09Z — 하한 92일 때 하한 일치 시험 1건만 실패). Coordinator가 하한을 93으로 올린다고 통지.
- 다른 모듈은 이 작업이 바꾸지 않아 실행하지 않았다(VERIFY-GATE가 전체를 실행). payment-api 전체 실측 1481은 GRADLE-CHECK 하한 갱신 근거(Coordinator 판정)다. HEAD `b82e5d1` + 미커밋 작업 트리.

### 18.9 최종 검토(PASS_WITH_FIXES — H 없음) 대응

- **m-1(Coordinator 판정 — 문서를 낮추지 않고 구현)**: `GetPaymentController`가 결과마다 INFO 한 줄 `operation=GetPayment correlationId=<requestId> keyIdPrefix=<keyId 앞 6자> result=<…>`을 남긴다 —
  FOUND(+ `paymentId=` 조회된 자기 Payment ID만)·NOT_FOUND·INVALID_REQUEST·UNAVAILABLE·INTERNAL_ERROR. 제시된 paymentId는 FOUND가 아닌 줄에 없다. 진단 줄(WARN/ERROR — 분류·규칙·클래스 이름)은 그대로.
  keyId 접두 길이는 기존 관례가 없어 CQ-16으로 정했다(6자). §7·CQ-09·§10 Auditability/Observability 문장이 이제 구현과 맞다.
  - 시험(`GetPaymentApiSpec` 확장 — 시험 수 불변 10): FOUND 줄 1개(result·keyIdPrefix·전체 keyId 없음·paymentId), 404 다섯 경우마다 NOT_FOUND 줄 1개(요청 키 접두, `paymentId=` 없음), INVALID_REQUEST 줄(제시 ID 없음),
    UNAVAILABLE·INTERNAL_ERROR 줄(제시 ID 없음). 결과 줄은 `keyIdPrefix=`가 있는 `operation=GetPayment` 줄로 진단 줄과 구별한다.
  - RED-A(08:53:02Z): 10건 중 5 실패(결과 줄 없음). GREEN(08:54:40Z — GetPaymentApiSpec·ArchitectureSpec·PaymentStatus*·PaymentStatusReaderSpec): **87건 실패 0**.
  - 변이(지시된 두 개 모두 죽음): ML1 오류 결과 줄에 제시 paymentId 추가(08:56:32Z — NOT_FOUND·INVALID·UNAVAILABLE·INTERNAL 4개 시험 실패), ML2 FOUND 줄 제거(08:57:54Z — FOUND 시험 실패).
- **n-1**: `PaymentStatusReaderSpec` Z-1의 중복 단언(`confirmedAt shouldBe c.cancelledAt` 두 번) 하나 제거. `PaymentStatusView` init 정리는 유지(Coordinator 확인).
- **n-2**: §18.7의 "`PersistenceFailure` → 503"을 provided `Unavailable` → 503으로, §10 Reconciliation의 Q-CPQ-01/02를 D-CPQ-001·002·005로 정정.
- **n-4**: `GetPaymentApiSpec` no-store 단언 앞에 주석 — no-store는 Controller header와 Spring Security 기본 header writer의 두 겹(MW3 생존 이유).
- **n-5(기록)**: API 수준의 **P1**(FAILED/EXPIRED + RECON 기록 늦은 승인·운영자 취소) 시험과 **IN_PROGRESS**(AUTHORIZING이 정지된 상태) 시험은 RECON 이후로 미룬다 — P1은 늦은 승인 거래 기록 Use Case(RECON-CONTRACT-001)가 생겨야
  공식 경로로 만들 수 있고, AUTHORIZING은 confirm이 TX2까지 한 호출에서 진행해 API 시험에서 멈춘 상태를 공식 경로로 만들 수 없다(회복·대사 시나리오와 함께). 현재 두 경우는 순수 단위 시험(`PaymentStatusViewsSpec` P1·IN_PROGRESS 행)과
  Reader C-1(TX2 commit 전 AUTHORIZING 스냅샷)로만 덮는다. n-2·n-3(§18.6)과 함께 RECON-CONTRACT-001 인계.
- **시험 수**: 시험 추가·삭제 없음(단언만 추가·1개 제거) → payment-api 시험 수 **변화 0**(§18.8 실측 1481 그대로). 전체 check는 다시 돌리지 않았다(대상 spec 87건 foreground 통과) — VERIFY-GATE가 새 fingerprint로 전체를 확인한다.

### 18.5 다음(검토 #1 뒤 — 원래 계획, §18.6~§18.8로 수행)

S3: `GetPaymentController`(`GET /v1/payments/{paymentId}`, 쿼리·본문 400, 형식 404, `Cache-Control: no-store`, CQ-11 조회용 503 문구, 지표·로그), API 시험 T1·T2·T4·T5·T6·H-1·E-1·P-1.
S4: 관례 §9.1 교차 참조·§18 새 절, HVC §2 문구(F-4)·§7, AC1 §10.2·CPA §4.6 교차 참조, 전체 check → VERIFY-GATE.
