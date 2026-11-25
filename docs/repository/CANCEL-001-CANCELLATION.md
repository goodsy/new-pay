# CANCEL-001 전액/부분취소 구현 명세 — API·영속화·가상 기관 취소·UNKNOWN 회복 (rev 0.3 — 명세 review CHANGES_REQUIRED 반영)

> **S3-DOC-001 문서 정합 표시(2026-11-25 — 코드·시험·정책 변경 없음, 자기 완료 판정 아님)**: §5 머리 현행화 주석(2026-10-29)만 있던 §5.1~§5.3 본문의 해당 문장 옆에 `→ 대체` 표시를 붙였고(D-OPS-001·D-OPS-005 — 진입점·서비스 간 인증은 ADMIN-PAYMENT-OPS-001이 열었다), §4.1 CN-02 원문에 정정 우선 표시를 붙였다(원문 보존). 근거 NP-99·PC-59, CAN 대장 진단 (a)-1·(a)-2·(a)-8·(c)-4. 현재 유효 규칙 정본은 `docs/rules/CAN.md`(D-GOV-006 ②).

> **rev 0.3 갱신(2026-10-20)**: 명세 review(CHANGES_REQUIRED)의 H-1·m-1~m-10·n-2~n-9를 §19 대응표로 반영한다(본문 해당 절에도 표시). 보류했던 두 항목은 사용자 답변(모두 권장안)으로
> 확정됐다 — **D-CANCEL1-005**(H-1 사건 동일성: 사건 있는 진행 중 취소의 새 상충 확정 관찰도 +1, 같은 기관 결정의 다른 출처 재수신은 같은 사건 — CN-31), **D-CANCEL1-006**(m-9: 운영자 명령 거절을
> 같은 로컬 TX의 append-only 감사 행으로 — 표 `operator_cancel_rejection`, CN-30). 2단계 실행 기록은 §18(Coordinator 지시의 "§17 2단계 실행 기록"은 이 문서에서 §18 — §17은 규모 추정).

> **rev 0.2 갱신(2026-10-20)**: §14 질문 4개가 답변됐다 — 모두 권장안. **D-CANCEL1-001**(운영자 보정 취소 = Use Case + PAYMENT 재검사만, inbound 진입점 없음, ADMIN 승인 상태 동기 조회 없음),
> **D-CANCEL1-002**(사건마다 이벤트 `CancellationIncidentDetected:{cancellationId}:{incidentSeq}`, `incident_count` 영속, 활성 hold가 있으면 새 hold 없음), **D-CANCEL1-003**(이관된 취소 UNKNOWN의 임시 출구 없음 —
> fail-closed, LIVE Gate 조건), **D-CANCEL1-004**(rev 0.1 §15-5를 질문으로 올림 — 취소 Attempt에 Route 프로파일 버전 고정, 현재 버전과 같을 때만 종결 요청, 다르거나 NULL이면 조회·이관만).
> Coordinator 판정(§15): AC 문구 D-CANCEL-001~013 + D-CANCEL1 AC 행 추가, ADMIN-BOOT-001·RECON-CONTRACT-001 인계 AC 추가, 회복 선택 A(CN-24) 채택, `sim-van-2`(CN-20) 채택·배포 시 승인 UNKNOWN의 종결 경로 상실
> 수용(fail-closed), 취소 GET 없음(CP-QUERY-001·WEBHOOK-001 인계), 배포 전제(이전 payment-api 모두 중지)는 S8에 HVC §7·이 명세 §20, allowedWritePaths 불변, timeout 30분/90분·수정 3회, `.env.example`·
> `LocalDemoAuthorizationConfigSpec`은 S8. 아래 rev 0.1 문장 중 "Q-CANCEL1-xx"·"권장"·"미결"은 이 결정이 우선한다. 바뀐 곳: §0·§2·§3.2(F-2·F-4·F-5)·§4.10·§5·§7.1·§8.1·§8.6·§12·§13·§14·§15·§16.

rev 0.1 작성 2026-10-20 KST, 기준 commit `e2fdf35`(AUTH-001 DONE, branch `feat/foundation-setup`). **1단계 명세이며 코드·시험 변경 없음.** 작업 트리에는 Coordinator 소유
`harness/tasks.json`·`harness/progress.json` 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 금융 정책을 채택하지 않는다 — §14 질문 후보는 답변 전 해당 운영 동작을
구현하지 않는다. 2단계 TDD는 Coordinator가 질문·범위 관찰(§15)을 처리한 뒤 시작한다.

표시(AUTH-001과 같다): **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — 의심되면 Coordinator가 질문으로
올린다), **구현 결정(CN-xx)** = 금융·보안 의미가 없는 가역적 선택(§13), **미결(Q-CANCEL1-xx)** = §14 질문 후보, **Demo** = Simulator·시험 전용 값(운영값 아님),
**범위 관찰** = 작업 경계 확인 사항(§15), **발견(F-x)** = 이번 조사에서 찾은 기존 코드·문서의 공백(§3.2).

권위 계약은 `CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(이하 **CC**)다. 이 명세는 CC를 다시 정하지 않고 실행 구조(API·TX·영속·기관·회복·시험)만 정한다. CC와 다르게 읽히는 문장은
CC가 우선하며 그런 곳은 발견(F-x) 또는 범위 관찰로 올렸다.

입력 문서(sha256 앞 8자…끝 4자, 2026-10-20 읽은 시점 작업 트리 — 전체 hash는 Verifier가 같은 파일에 `sha256sum`으로 재현):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 지침 | `CLAUDE.md` | 지침 | `829b5a44…d813` |
| 결정 | `docs/DECISIONS.md`(rev 0.2: **D-CANCEL1-001~004** 추가 — hash는 rev 0.1 읽은 시점, D-CANCEL-001~013, D-AUTH-001~013, D-AUTH1-001~007, D-ADMIN-001~007, D-CPC-*, D-CP-*, D-CPD-*, D-CPA-001, D-MD-006, D-MS2-005) | 확정 | `c68f1947…0b32` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 하네스 | `docs/HARNESS-GUIDE.md` / `docs/BASELINE-INDEX.md` | 기준 | `3f2220c4…06c8` / `f2461472…6251` |
| 작업 | `harness/tasks.json` CANCEL-001(AC 2개·allowedWritePaths), ADMIN-BOOT-001·RECON-CONTRACT-001·SETTLEMENT-CONTRACT-001·CP-QUERY-001·WEBHOOK-001 AC | Coordinator 소유(미커밋) | `f0ace485…bc5d` |
| 진행 | `harness/progress.json`(agreedOrder: … AUTH-001 → **CANCEL-001** → CP-QUERY-001 → WEBHOOK-001 → ADMIN-BOOT-001 → … → RECON-001) | Coordinator 소유(미커밋) | `7a6bcd14…803c` |
| 질문 | `harness/questions.json`(열린 질문 10개 — 이 작업을 막는 것 없음, §14.3) | Coordinator 소유 | `8ace2ebc…c85d` |
| 명령 | `harness/commands.json`(GRADLE-CHECK·TEST-SUMMARY 하한 1875, HARNESS-SELFTEST 86) | 하네스 | `40cf35f8…0fbb` |
| CC | `docs/repository/CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(권위 계약 rev 1.3, §4·§7·§8·§9·§14.1 인계) | 확정 계약 + domain 구현 | `d16b0917…47c6` |
| AU | `docs/repository/AUTH-001-AUTHORIZATION.md`(구조 선례: §1.3 준비, §4 TX, §5 VAN, §6 회복, §7 영속, §16 기록, §20 배포 조건) | 구현 반영 명세 rev 0.8 | `b611ebc7…bb19` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md` | 확정 계약 | `d0802270…6498` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9·§12·§14·§15·§16) | 관례 | `8dbe5709…9afa` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례 | `4bb8712b…73fc` |
| HVC | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md`(§2·§7) | 하네스 계약 | `4e952c5a…cb0e` |
| 정의 | `docs/repository/DOMAIN-DEFINITION.md` | 색인 | `1cce1e6e…1257` |
| ADR | `docs/repository/ADR-001-KAFKA-REDIS-DEFERRAL.md` | 결정 | `445af4c1…453f` |
| 코드(domain) | `payment/domain/` `CancelStart.kt` `268fb9e6…b495`, `CancellationResolution.kt` `d018d8f3…6b4e`, `PaymentCancellation.kt` `072237b2…9eda`, `ReturnBalance.kt` `afc6403c…de2a`, `CancelAttempt.kt` `7fb8d00e…fe75`, `CancelObservation.kt` `94caa061…1553`, `CancelCapability.kt` `f969d3f1…9d5f`, `OperatorCancelTarget.kt` `bc4a135f…1d3d`, `PaymentTransaction.kt` `d18d9393…ddf4`, `OrderPaymentControl.kt` `418a757a…da71` | `e2fdf35` | — |
| 코드(schema) | PAYMENT `V9__authorization.sql` `0211c432…f962`, `V11__authorization_existing_tables.sql` `ed98a459…3f01`, `V12__authorization_recovery_schedule.sql` `85bb98ab…49c8`; Simulator `V2__van.sql` `aaa401d9…0ad2` | `e2fdf35` | — |

**S8 입력 hash 갱신(rev 0.3 n-2 — 2026-10-20 S8 시점 작업 트리, 같은 표기)**: 위 표 이후 바뀐 입력만 적는다. 결정 `docs/DECISIONS.md` `6e132155…dce9`(D-CANCEL1-001~008 추가 — Coordinator 소유),
작업 `harness/tasks.json` `d1b0cc1d…5026`, 진행 `harness/progress.json` `963f2ec1…0920`, 질문 `harness/questions.json` `23211d50…6b9b`, 명령 `harness/commands.json` `00e6b2d8…f06d`(모두 Coordinator 소유 — 읽기만).
이 작업이 S8에 고친 문서: CC `2c3d9177…e687`(§18 구현 편차 추가), 관례 `c52da377…e8ca`(§17 추가), HVC `d291b5b8…92df`(§7 CANCEL-001 항목), 정의 `bdcecdee…9ae0`. 바뀌지 않은 입력: 지침·원칙·하네스·색인·AU·AC1·매핑 — 위 표와 같다.
**정정**: ADR-001의 hash 끝 4자는 `753f`다(위 표의 `453f`는 rev 0.1 표기 오류 — 파일은 `e2fdf35` 이후 바뀌지 않았다). 이 문서 자신의 hash는 Verifier가 계산한다.

---

## 0. 요약

- **흐름(도출 — CC §4.4 + AU §4 동형)**: 가맹점 서버 `POST /v1/payments/{paymentId}/cancel`(Bearer, Idempotency-Key, 본문 `merchantCancelId`·`amount`·`currency`) → **TX1**(멱등 →
  control → payment → 업무 키 조회 → Authorization → 반환 잔액 → `CancelStart` → 취소 OPEN·Attempt PREPARED·예약 RESERVED·회복 일정·멱등 ACCEPTED) → **TXd**(전송 게이트 → Attempt
  DISPATCHING + Route 프로파일 버전 고정) → **TX 밖** 가상 VAN 취소(TCP) → **TX2**(관찰 append + `CancellationResolution` + CANCEL 거래 + Outbox). 제한 시간 안 확정이면 200, 아니면 202.
- **가상 VAN**: 같은 TCP 서버·프레임에 메시지 `CANCEL_REQUEST/RESPONSE`·`CANCEL_INQUIRY_REQUEST/RESPONSE` 추가. 계약 이름 **`sim-van-2`**(= sim-van-1 메시지 전부 바이트 동일 + 취소 4종, CN-20).
  기관 취소 원장은 새 append-only 표 `van_cancel_entry`, 기관 쪽 잔액 직렬화는 승인 반환 순번 UNIQUE(CN-22 — UPDATE 권한 없이).
- **UNKNOWN 회복**: AUTH-001 설계를 **복제·재사용**(같은 패턴, 별도 표·서비스·설정 — CN-24). 원 취소 요청 ID 조회만, 자동 FAILED는 기관 확정 거절·기관 무효과 확정·전송 전 실패 증명뿐,
  예산 소진 → `CancellationOutcomeUnresolved`(UNKNOWN·예약 유지).
- **영속화**: PAYMENT **V15**(새 표 5) → V16(권한) → V17(기존 표 3개 ALTER) → V18(취소 회복 일정) → V19(권한). Simulator V5(새 표·inquiry log 열) → V6(권한).
- **운영자 보정 취소(AC 2)**: CANCEL-001은 provided Use Case·domain·영속·회복 공용 경로와 PAYMENT 재검사까지 구현하고 **inbound 진입점은 열지 않는다**(**확정 D-CANCEL1-001**). ADMIN 승인 상태
  동기 조회도 없다. 진입점·서비스 간 인증·승인 발급·`oct1:` 지문 ADMIN 쪽 대조는 ADMIN-BOOT-001(§5, AC 추가됨).
- **사건 이벤트(확정 D-CANCEL1-002)**: 사건마다 `CancellationIncidentDetected:{cancellationId}:{incidentSeq}`, `payment_cancellation.incident_count` 영속, 활성 hold가 있으면 새 hold 없음.
- **회복 버전 고정(확정 D-CANCEL1-004)**: 취소 Attempt에 Route 프로파일 버전을 전송 때 고정, 종결 요청은 현재 버전과 같을 때만.
- **LIVE Gate 조건(확정 D-CANCEL1-003)**: 이관된 취소 UNKNOWN을 대사 근거로 확정하는 RECON Use Case가 생기기 전 LIVE 불가. CANCEL-001은 임시 출구를 만들지 않는다(fail-closed).
- **발견 15건**(§3.2): 취소 Aggregate 복원 factory 없음(F-1), 취소 사건 이벤트 effect_key 1062 반복 위험(F-5 — AU F-1 동형), `payment_transaction` 승인 열 NOT NULL(F-3), 회복 일정 표에
  operation 열 없음(F-4), VAN 원장 CHECK가 취소를 받을 수 없음(F-6), 늦은 포기·lease 표시 경합 NoChange 처리 없음(F-11) 등.
- **질문 4개 — 모두 답변됨**(§14, D-CANCEL1-001~004). **규모**: AUTH-001의 약 0.8~1.0배(시험 +300~430건 추정, §17).

---

## 1. 범위와 범위 밖

### 1.1 범위

| 구분 | 내용 | 근거 |
|---|---|---|
| API | 가맹점 취소 `POST /v1/payments/{paymentId}/cancel`, 오류·응답 매핑, 같은 키 현재 결과 재생, 업무 키(merchantCancelId) 재사용 | CC §2.2·§4.5·§8, D-CANCEL-002·003, CC-05 |
| Use Case | 취소 시작(TX1) → 전송 게이트(TXd) → 기관 호출(TX 밖) → 결과 반영(TX2), 취소 UNKNOWN 회복 Worker, 운영자 보정 취소 provided Use Case(진입점 제외 — §5) | CC §4.4·§4.7, AC 1·2 |
| domain 보강 | `PaymentCancellation.reconstitute`(incidentRecorded 포함 — CC nit 4), Attempt Route 프로파일 버전 고정, 사건 순번, 늦은 포기·lease 표시 NoChange, 기관 업무일 보존 | F-1·F-2·F-5·F-11·F-12 |
| 영속화 | `return_balance`·`return_reservation`·`payment_cancellation`·`cancel_attempt`·`cancel_observation`·`cancellation_recovery_schedule` 새 표, `payment_transaction`·`incident_hold`·`idempotency_request` ALTER, 표 단위 권한 | CC §9.2·§14.1, AU §1.3 |
| 기관 연동 | PG `CancellationPort`·`CancelInquiryPort` Adapter(같은 TCP 프레이밍·상관관계·MAC·deadline), Route 프로파일 취소 Capability·`sim-van-2`, Simulator/REAL 격리 | CC §7, AU §5.6·§5.8 |
| Simulator | `van` 모듈 취소·취소 조회·무효과 종결, 기관 취소 원장(append-only)·기관 잔액 검증·부분취소 순번·취소 기한(Demo)·시나리오, 재시작 내구성, 표 단위 권한 | CC §7 끝, 허용 §5.5 |
| Outbox | `PaymentCancelled`(FINANCIAL_FACT)·`PaymentCancelFailed`·`CancellationOutcomeUnresolved`·`CancellationIncidentDetected` | CC §9.3 |
| 시험 | 실제 MySQL 경합·rollback·응답 유실·재시작, 체크카드·할부 승인 건의 취소 검증(D-AUTH1-003 이월), 8대 원칙 증거 | AC 1, CLAUDE.md, D-AUTH1-003 |

### 1.2 범위 밖

| 항목 | 소유 | 근거 |
|---|---|---|
| 운영자 보정 취소의 inbound 진입점·관리자 화면·2인 승인 발급·역할(APPROVER) 검사·TOTP 재입력·서비스 간 인증 | ADMIN-BOOT-001 | D-ADMIN-003·004·007, D-CANCEL-005, CC §14-7 — §5 경계 |
| CANCEL_RESULT_CONFLICT hold 해제·FORCED 출구·사건 처리된 진행 중 취소의 확정·이관된 취소 UNKNOWN의 대사 확정·늦은 승인 거래 기록(P1)·DOUBLE 해제 | RECON-CONTRACT-001·RECON-001 | CC §14.1, D-CANCEL-006·010~013 |
| 수수료/VAT 반환 계산·반올림·정산 Entry·선지급 재원에서 진행 중 예약 차감 여부 | SETTLEMENT-CONTRACT-001·SETTLEMENT-001 | D-CANCEL-008·009, CC §14-3 |
| 매입 전/후 취소 파일 처리 | ACQUIRING-001 | CC §4.9 |
| 결제 조회 API에 반환 요약·취소 이력, 취소 조회 전용 GET | CP-QUERY-001 | CC §14-6, §15-6 범위 관찰 |
| 가맹점 Webhook(`PaymentCancelled`·`PaymentCancelFailed` 공개 계약) | WEBHOOK-001 | CC §14-6 |
| 계좌환불(Refund) | v1 범위 밖 | **확정 D-CANCEL-007** |
| 실제 VAN 전문·TLS·운영 키·REAL Adapter | LIVE 준비 Gate | AU §5.6, D-CPD-001 |
| 같은 기관 요청 ID 재전송(`sameIdResendSafe`)에 의한 취소 UNKNOWN 회복 | 후속(필요 시) | CN-27 — 조회만 구현 |

### 1.3 인계 항목 전수 대조(문서 전체 grep "CANCEL-001" — 2026-10-20)

| 출처 | 항목 | 처리 |
|---|---|---|
| CC §2.2 | 취소 API·오류 매핑·멱등 기록 저장 | §4.1·§4.8·§4.9 |
| CC §2.2 | 가상 VAN 취소 operation·TCP 전문·Adapter | §6 |
| CC §2.2 | DB migration·JPA Store·Use Case·실DB 경합 | §8·§9 |
| CC §4.7 | 회복 Worker: 후보 jOOQ keyset → 건별 JPA 재판정, lease fencing | §7(AU D-S7-1과 같이 `SKIP LOCKED` 선점으로 대체 — CN-24) |
| CC §4.11 | 역할·Scope 검증과 ADMIN 승인 상태 조회는 ADMIN 연동 Use Case(CANCEL-001 경계) | §5, D-CANCEL1-001 |
| CC §7 | Port 목록(`CancellationPort`·`CancelInquiryPort`·Route 확장·`PaymentCancellationRepository`·`ReturnBalanceRepository`·`CancellationReader`) | §8.3 |
| CC §7 끝 | Simulator 계약(원 승인 잔액 검증·같은 ID·부분취소 순번·기한 경과 거절·유실/지연/상충/늦은 성공·재시작 존속) | §6.3~§6.5 |
| CC §8 | 오류·응답 계약 | §4.9 |
| CC §9.1 | 잠금 순서, TX2·회복은 control부터 | §4.7 |
| CC §9.2 | DB 제약 전부(UNIQUE(payment_id, merchant_cancel_id)·UNIQUE(approval_id)·진행 중 1건 생성 열·CANCEL 거래 UNIQUE(cancellation_id)·return_balance 금액 열·CHECK·UNIQUE(payment_id)) | §8.1 V15·V17 |
| CC §9.3 | effect_key `{transactionId}` vs `{cancellationId}` 결정, payload(authorizedAmount·returnStatusAfter·순번·누적) | §4.10, CN-13 |
| CC §14.1 | `incident_hold` reason CHECK에 CANCEL_RESULT_CONFLICT | §8.1 V17 |
| CC §14.1 | ReturnBalance revision 영속(JPA version과 별개) | §8.1, CN-16 |
| CC §14.1 nit 4 | `PaymentCancellation.incidentRecorded` 영속·복원 | F-1, S1 |
| CC §14.1 nit 4 | `PaymentAuthorization.reconstitute`의 FAILED + 거래 | AUTH-001에서 완료(AU §16.2) — 회귀 시험만 |
| CC §14.1 ADMIN | 2인 승인 요청 ID 발급·`oct1:` 지문 고정·역할 검사 | §5(ADMIN-BOOT-001 인계) |
| CC §15 | 후속 증거 열(실DB rollback·flush 뒤 실패·TX 밖 호출·응답 유실·같은/다른 키·기관 ID 재전송·30일 재생·재시작·행 잠금 동시 취소·전액 2건 동시·fencing·감사·관측) | §9·§10 |
| CC §16 | JPA·jOOQ 선택 연결 | §8.4·§8.5 |
| CC §17.3-2 | 기관 업무일이 필요하면 Adapter 계약에서 추가 | F-12, CN-19 |
| CC §17.3-6 | `ReturnBalance.revision` 영속 | CN-16 |
| CC §17.5-2 | `returnBalanceRestoredOnlyByPersistence`의 실제 호출자(복원 Mapper) | S2(규칙이 대상 있음으로 평가되는지 확인) |
| AU §1.2 | 취소 API·영속·VAN 취소 전문·취소 UNKNOWN 회복 | 이 작업 전체 |
| AU §1.2·§5.3, D-AUTH1-003 | 체크카드 일시불·일반 할부 승인 건의 취소·부분취소 검증 | §9.3 조합 행렬, AC 1-h |
| AU §1.3 | `payment_transaction` type CHECK·취소 열 ALTER | §8.1 V17, F-3 |
| AU §1.3 | VAN 메시지·operation namespace에 CANCEL·CANCEL_INQUIRY | §6.1(실제 원장 operation 값은 `AUTHORIZE` — F-15) |
| AU §1.3 | Route 프로파일 취소 Capability(`cancel`·`cancelInquire`·`partialCancelSupported`) | §6.6 |
| AU §1.3 | 잠금 순서(승인 경로는 return_balance를 잡지 않음) | §4.7 |
| AU §1.3 | 회복 Worker 골격의 operation 열 | **실제 없음 — F-4**, CN-24 |
| AU §4.7 끝 | 승인 경로와 취소 경로 순환 없음 | §4.7 |
| 관례 §15 끝 | `JpaReturnBalanceEntity`·예약 Entity·`@Immutable` 거래·관찰, 행 잠금, Mapper가 revision·version 함께 | §8.4 |
| 정의 117·287행 | Cancellation 정의 | S8 문서 갱신 |
| tasks.json CANCEL-CONTRACT-001 | Transaction Safety "실제 DB rollback은 CANCEL-001", Concurrency "DB 경합은 CANCEL-001" | §9, §10 |
| AC1 §18-6 | `CancelTransaction` 구현은 CANCEL-001 | §8.1 V17 |

---

## 2. AC 대응표(tasks.json CANCEL-001 → 세부 → 절 → 계획 시험)

AC 1은 여러 산출물을 묶고 있어 세부(1-a~1-i)로 나눈다(나눔은 이 명세의 정리이며 AC 변경이 아니다). (rev 0.2) tasks.json AC 1은 Coordinator가 "D-CANCEL-001~013"으로 정정했고 D-CANCEL1 AC 행(아래 3)을 추가했다(§15-1 판정).

| # | AC·세부 | 출처 | 기존 코드 | 공백 → 이 작업 | 절 | 계획 시험(이름 가역적) |
|---|---|---|---|---|---|---|
| 1-a | 취소 API(가맹점) | CC §4.4·§4.5·§8, D-CANCEL-001~003 | domain `CancelStart`·`compareRequest` | Controller·파서·TX1·멱등(`x1:`)·업무 키 조회·오류 매핑 | §4 | `CancelPaymentApiSpec`, `CancelStartServiceSpec`, `CancelErrorMappingSpec` |
| 1-b | PAYMENT 영속화(반환 잔액 행 CHECK·UNIQUE) | CC §9.2·§14.1 | 없음(표 전부 없음) | V15~V19, Repository·Mapper·복원 | §8 | `CancellationSchemaSpec`(CHECK 3819·UNIQUE 1062·권한 1142), `CancellationPersistenceSpec`(왕복·복원 거부) |
| 1-c | 가상 기관 취소 | CC §7, AU §1.3 | VAN 승인·조회만 | `sim-van-2` 취소·취소 조회·종결·기관 잔액·시나리오 | §6 | `VanCancellationSpec`, `VanServerCancelSpec`, golden v2 |
| 1-d | UNKNOWN 조회 회복 | CC §4.7, D-AUTH-009 | domain `markOutcomeUnknown`·NOT_FOUND 유지 | 회복 일정·Worker·예산·이관·버전 고정 | §7 | `CancellationRecoverySpec`, `CancellationRecoveryConcurrencySpec` |
| 1-e | Outbox | CC §9.3 | `OutboxStore` | 이벤트 4종·effect_key·사건 순번 | §4.10 | `CancellationOutboxSpec` |
| 1-f | 실제 MySQL 경합 | CLAUDE.md, CC §9.2 | — | K1~K16 | §9.1 | `CancelConcurrencySpec`, Simulator `VanCancelConcurrencySpec` |
| 1-g | rollback·장애 | CLAUDE.md, CC §15 | — | TX1·TXd·TX2 장애 주입, commit 불명확 | §9.2 | `CancelStartRollbackSpec`, `CancelFlowFaultSpec` |
| 1-h | 체크카드·할부 승인 건 취소 검증(D-AUTH1-003 이월) | D-AUTH1-003, AU §5.3 | domain Capability 판정 | 조합 행렬(전액·부분·UNKNOWN·상충) | §9.3 | `CancelPlanCardMatrixSpec` |
| 1-i | D-CANCEL-008~013 PAYMENT 의무(payload·운영자 관문·P1) | D-CANCEL-008~013 | domain 구현됨 | payload·영속·Use Case 관문 | §4.10·§5 | `CancellationOutboxSpec`, `OperatorCorrectionCancelServiceSpec` |
| 1-j | (rev 0.3 m-8) D-CANCEL-007 계좌 환불 미지원의 API·운영 안내 | D-CANCEL-007 | — | API 문서·운영 안내 문구 | §4.11(S8 작성), §19 | 문서 검토(Reviewer) |
| 2 | 운영자 보정 취소 Use Case(2인 승인 양쪽 검사) — ADMIN 연동과 경계 명세 | D-CANCEL-005, D-ADMIN-007, **D-CANCEL1-001** | domain `CancelInitiator.Operator`·재검사 | provided Use Case + 경계 문서 + ADMIN 인계(진입점 없음) | §5 | `OperatorCorrectionCancelServiceSpec`(같은 사람·사유·근거·stale·approvalId 재사용·hold 중 시작), ArchUnit/구조 시험: 운영자 Use Case를 부르는 inbound adapter 없음 |
| 3 | (rev 0.2, D-CANCEL1-001~004) 진입점 없는 운영자 Use Case, 사건마다 이벤트·`incident_count`, 이관 UNKNOWN 출구 없음·LIVE Gate 기록, 취소 Attempt Route 버전 고정·일치 때만 종결 요청 | D-CANCEL1-001~004 | — | §5·§4.10·§7·§20(S8) | §5, §4.10, §7.1, §8.6 | `CancellationOutboxSpec`(사건 2회 → 이벤트 2·hold 1·1062 없음), `CancellationRecoverySpec`(버전 같음 → 종결 요청 / 다름·NULL → closeIfAbsent=false·이관), 이관 뒤 다음 취소 `CANCEL_IN_PROGRESS` 유지 |

---

## 3. 현황 조사(2026-10-20, `e2fdf35`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것(이 작업) |
|---|---|---|
| domain(취소) | `PaymentCancellation`(OPEN/UNKNOWN/COMPLETED/FAILED, Attempt 1·관찰·키 상충·거래·`incidentRecorded`, `compareRequest`, `CANCEL_OPERATION = "CANCEL"`), `CancelAttempt`(AttemptState 재사용), `CancelObservation`·`CancelConfirmedTerms`(기관 취소 거래일 필수, 업무일 없음), `ReturnBalance`(RESERVED/CONSUMED/RELEASED/FORCED, revision, `reconstitute`), `CancelStart`(검사 1~7), `CancellationResolution`(판정표·`abandonBeforeDispatch`·`markOutcomeUnknown`), `CancelCapability`(version·부분취소 집합), `OperatorCancelTarget`(`oct1:`), `OrderPaymentControl.reportCancellationResultConflict`(출처 null hold) | `PaymentCancellation.reconstitute`(F-1), Attempt Route 버전(F-2), 사건 순번(F-5), 늦은 포기 NoChange(F-11), 업무일(F-12) |
| domain(승인 — AUTH-001) | `PaymentAuthorization.reconstitute`(FAILED + 거래 수용), `lockByPaymentId`, Attempt `routeProfileVersion`(D-AUTH1-007) | — |
| application | 승인 계열 서비스·Port 전부, `LocalTransactions`, `IdempotencyRecordStore`(operation CreatePayment·ConfirmPayment), `OutboxStore`, `AuthorizationIdentifierGenerator`(pau_/pat_/ar_/aq_/ptx_) | 취소 Use Case·Port·Store·Reader 전부 |
| persistence | V1~V14, `PessimisticRowLock`, `CommitFaultingTransactionManager`(시험) | 취소 표 전부, `payment_transaction` 취소 열(F-3), 취소 회복 일정(F-4) |
| web | 공개 포트 Bearer 체인, 엄격 JSON 파서 관례, `ApiErrorCode`(PAYMENT_NOT_FOUND·ORDER_ON_HOLD 등), 내부 connector(`/internal` — 권리 Inbox HMAC) | 취소 Controller·파서·새 code |
| 기관 Adapter | `SimulatorVanAuthorizationAdapter`·`SimVanFrames`·`SimVanMessages`·`VanSocketConnector`, `ConfiguredInstitutionRouteRegistry`(`PROTOCOL = "sim-van-1"` 고정, 취소 Capability 없음) | 취소 Port 구현·Route 취소 Capability(F-8) |
| Simulator `van` | 승인·조회·종결 표지·PENDING·원장 `van_ledger_entry`(operation CHECK `AUTHORIZE`만)·`VanLedgerPrivilegeGuard` | 취소 판정·취소 원장·기관 잔액(F-6·F-7) |
| internal-admin | 기동·보안 기본 설정만(승인 틀·계정·서비스 간 인증 없음) | ADMIN-BOOT-001(agreedOrder상 CANCEL-001 뒤) — F-14 |
| 하네스 | GRADLE-CHECK 1875, SELFTEST 86, golden `van-contract-sim-van-1` | golden v2, 하한 갱신 |

### 3.2 발견(F-x)

1. **F-1 취소 Aggregate 복원 factory 없음**: `PaymentCancellation`에는 `open`만 있고 `reconstitute`가 없다(grep 0건). Attempt 목록·관찰·키 상충·거래·종료 근거·`incidentRecorded`·
   개시자(운영자 필드 포함)를 받아 존재할 수 없는 조합(COMPLETED ⇔ 거래, FAILED ⇔ 충분한 근거, OPEN ⇒ Attempt PREPARED/DISPATCHING, UNKNOWN ⇒ OUTCOME_UNKNOWN 또는 사건 RESOLVED 등)을
   거부하는 복원이 필요하다(CC nit 4). ArchUnit `paymentCancellationRestoredOnlyByPersistence`(관례 §15의 ReturnBalance 규칙과 같은 방식)를 함께 둔다 → S1.
2. **F-2 Route 프로파일·Capability 버전 미고정**: D-AUTH1-007은 승인 Attempt에 전송 때 Route 프로파일 버전을 고정하고 회복의 무효과 종결 요청을 버전 일치 때만 보낸다. `CancelAttempt`에는
   그 자리가 없고 `PaymentCancellation`은 `CancelCapability.version`도 저장하지 않는다. **도출(D-AUTH1-007과 같은 근거 — 무효과 확정 출처의 정합성, Auditability)**: 취소 Attempt에도
   전송 때 `routeProfileVersion`을 고정하고 회복은 같은 조건일 때만 `closeIfAbsent`. 시작 때 판정한 Capability 버전은 취소에 기록(Auditability). **(rev 0.2) 확정 D-CANCEL1-004**(질문으로 답변됨 — 다르거나 NULL이면 조회·이관만).
3. **F-3 `payment_transaction` 승인 열 NOT NULL**: V9의 `plan_kind`·`card_type`·`approval_number`·`provider_transaction_id`·`institution_transaction_date`는 NOT NULL이고
   `ck_payment_transaction_plan`·`ck_payment_transaction_card_type`도 type 조건이 없다. CANCEL 행을 넣으려면 승인 전용 열을 NULL 허용으로 바꾸고 type별 CHECK로 다시 묶어야 한다(V17 — AU-23 계획대로
   공용 표 유지, CN-12). 취소 승인번호는 기관이 주지 않을 수 있다(`cancelApprovalNumber: String?`).
4. **F-4 회복 일정 표에 operation 열 없음**: AU §1.3은 "회복 일정 Store 모양을 취소 회복이 재사용할 수 있게 operation 열을 둔다"고 적었지만 실제 V12 `authorization_recovery_schedule`은
   operation 열이 없고 `attempt_id`가 `authorization_attempt` FK다. 그 표를 일반화하려면 배포된 표 ALTER·FK 변경·AUTH-001 경로 회귀가 필요하다 → 별도 표(CN-24). **(rev 0.2) Coordinator 판정: 선택 A(별도 복제) 채택,
   AU §1.3의 "operation 열" 문장은 실제와 다름을 이 발견으로 기록**(AU 문서는 수정하지 않음 — S8 문서 갱신 때 교차 참조만).
5. **F-5 취소 사건 이벤트 effect_key 1062 반복 위험(AU F-1 동형)**: `OrderPaymentControl.openHold`는 같은 (paymentId, reason) 활성 hold가 있으면 새 hold 없이 `true`, `CancellationResolution.conflict`는
   그때도 `Incident(CANCEL_RESULT_CONFLICT)`를 돌려준다. CC §9.3 effect_key `CancellationIncidentDetected:{cancellationId}:{occurrence}`의 occurrence를 hold occurrence로 읽으면 (a) 같은 취소에 두 번째
   상충 결과(KEY_CONFLICT·FAILED 뒤 늦은 성공·COMPLETED 뒤 다른 PTX)가 오면 같은 키 → Outbox UNIQUE 1062 → TX2 rollback → 관찰도 사라지고 회복이 같은 관찰로 무한 반복한다. (b) 다른 취소
   operation(운영자 보정)의 사건은 키가 달라 문제는 없지만 hold occurrence가 같다. → 사건 순번(취소별 `incidentCount`)으로 키를 만든다(CN-14). **(rev 0.2) 확정 D-CANCEL1-002**: 사건마다 발행,
   `incident_count` 영속, 활성 hold가 있으면 새 hold 없음. domain은 `incidentRecorded: Boolean`을 `incidentCount: Int`(> 0 ⇔ 사건 기록됨)로 바꾸고 `conflict()`마다 +1 — 같은 관찰 재반영은 DUPLICATE라 증가하지 않는다.
6. **F-6 VAN 원장 CHECK가 취소 결정을 받을 수 없음**: `van_ledger_entry`는 `operation IN ('AUTHORIZE')`, DECISION은 `evidence_ref NOT NULL`·승인 조건 열 묶음·거절 분류 3종 CHECK다. 취소 결정
   (원 승인 참조·취소 금액·취소 PTX·반환 순번·취소 거절 분류)은 이 모양이 아니다 → 새 append-only 표 `van_cancel_entry`(CN-21).
7. **F-7 VAN 쪽 잔액 직렬화와 권한**: Simulator 원장 runtime 권한은 SELECT·INSERT뿐이고 MySQL `SELECT … FOR UPDATE`는 UPDATE(또는 DELETE·LOCK TABLES) 권한을 요구한다(AU §7.2 "잠금 읽기(1142)").
   같은 원 승인에 대한 동시 취소 두 건이 기관 잔액을 함께 읽고 둘 다 승인하면 기관 원장이 승인액을 넘는다 → 승인된 취소의 반환 순번 UNIQUE(원 승인 namespace, approved_sequence)로 진 쪽 1062 →
   새 TX 재판정(CN-22 — 원장 append-only 유지, UPDATE 권한 추가 없음).
8. **F-8 Route 등록부의 프로토콜 고정·취소 Capability 없음**: `ConfiguredInstitutionRouteRegistry.PROTOCOL = "sim-van-1"`, Capability에 취소 항목이 없다. 취소 Capability를 추가하면
   **D-AUTH1-007 §20-6에 따라 profile-version을 바꿔야 한다** → 배포 시점에 UNKNOWN인 승인 Attempt(옛 버전 고정)는 무효과 종결 요청을 더 하지 않고 조회·이관만 한다(fail-closed 쪽 — 배포 메모, §8.6).
9. **F-9 AC 문구의 결정 범위**: tasks.json CANCEL-001 AC 1은 "D-CANCEL-001~007"이지만 D-CANCEL-008~013도 이 작업의 PAYMENT 의무(payload·운영자 관문)를 만든다(§15-1). **(rev 0.2) 정정됨**(Coordinator — "D-CANCEL-001~013" + D-CANCEL1 AC 행).
10. **F-10 취소 hold의 출처 열**: `reportCancellationResultConflict`는 출처 null로 hold를 연다(V11 CHECK는 넷 다 NULL 허용). 취소 관찰 키를 승인 관찰 출처 열에 넣으면 승인 설명 집합(AU §4.11-2)과
    의미가 섞인다 → 출처 열은 NULL 유지, 사건 이벤트 payload에 cancellationId·관찰 키를 싣는다(CN-15). 승인 해제 판정은 취소 사건 hold를 설명 대상으로 쓰지 않으므로 영향 없음(취소 사건 hold 해제는 거절 — CC m-1).
11. **F-11 늦은 포기·lease 표시 경합 처리 없음(AU-25·m-2 동형)**: 취소 `CancellationResolution.markOutcomeUnknown`은 Attempt가 OUTCOME_UNKNOWN이 아니고 취소가 OPEN이 아니면 예외(예: TX2가 먼저
    COMPLETED로 확정한 뒤 회복의 lease 만료 표시), `abandonBeforeDispatch`는 취소가 OPEN이 아니면 예외(예: lease 회복이 UNKNOWN으로 바꾼 뒤 전송자의 connect 실패 보고). 승인 쪽은 AU-25로
    `NoChange`가 됐다. → 같은 규칙을 취소 domain에 둔다(S1, 승인과 같은 근거 — 도출).
12. **F-12 기관 업무일 미보존**: `CancelConfirmedTerms`에는 기관 취소 거래일만 있다(CC §17.3-2 "필요하면 CANCEL-001 Adapter 계약에서 추가"). 승인은 검토 #1 m-2로 nullable `institutionBusinessDate`를
    보존한다. 취소도 같은 방식으로 **보존만**(nullable, 의미·정산 적용은 Q-CALENDAR) — CN-19.
13. **F-13 원 경로 판정의 `single{RESOLVED}`**: `PaymentCancellation.requireOriginalRoute`는 RESOLVED 승인 Attempt가 정확히 하나라고 가정한다. 가맹점 경로(Payment·Authorization AUTHORIZED)에서는 항상
    성립하지만, RECON이 기록할 늦은 승인(P1, D-CANCEL-011)의 Attempt 상태는 RECON 기록 Use Case가 정한다(아직 없음). 이 작업은 가맹점 경로와 시험 fixture의 P1(RESOLVED)만 다루고 RECON 인계에 적는다(§15-7).
14. **F-14 ADMIN 기반 부재**: internal-admin에는 승인 요청·계정·역할·서비스 간 인증이 없고 ADMIN-BOOT-001은 agreedOrder상 이 작업 뒤다. 운영자 보정 취소 명령을 받을 수 있는 인증된 호출자가
    없다 → §5, Q-CANCEL1-01 → **D-CANCEL1-001**(진입점 없음, ADMIN-BOOT-001이 연다).
15. **F-15 VAN operation 값 표기**: AU §1.3·§5.2는 namespace operation을 `AUTH`·`INQUIRY`로 적었지만 실제 원장 값은 `AUTHORIZE`이고 PG 취소 domain 상수는 `CANCEL`이다. 이 작업은 실제 값
    (`AUTHORIZE`·`CANCEL`)을 쓴다(문서 정정만, 동작 영향 없음).

---

## 4. 가맹점 취소 흐름

### 4.1 API

```text
POST /v1/payments/{paymentId}/cancel          (공개 포트, Authorization: Bearer <pc_test_sk_…>, Idempotency-Key: <1..64 [A-Za-z0-9-_.:]>)
{ "merchantCancelId": "…", "amount": 3000, "currency": "KRW" }
```

- **확정(CC §2.2·D-CANCEL-002)**: 경로, `merchantCancelId` 필수(D-CPC-002 형식, D-CP-001 그대로 비교). 금액은 JSON 정수(D-CPC-009), minor unit ≥ 1.
- **구현 결정 CN-01**: 닫힌 필드 3개·엄격 파서(CP-API 관례 — 모르는 필드·null·타입 오류 400). 가맹점 취소 사유 문구는 v1에서 받지 않는다(표시 정보는 fingerprint 대상이 아니고 — CC CC-06 —
  자유 문구는 개인정보가 섞일 수 있어 저장 경로를 늘리지 않는다. 필요해지면 필드 추가로 연다). 주문 ID 재진술은 요구하지 않는다(paymentId + 인증 MID·환경 범위로 대상이 정해진다 — confirm의 CA-08은 승인
  조건 확인용).
- 인증·범위(**확정 K-20, D-CPA-001**): 기존 Bearer 필터. Payment가 없거나 다른 MID·환경이면 404 `PAYMENT_NOT_FOUND`(존재 비노출, 저장 안 함). paymentId 형식 오류도 404(AU §4.1과 같음).
- 응답 본문(**확정 CC §8**): `paymentId`, `cancellationId`, `status`(COMPLETED·FAILED·IN_PROGRESS·UNKNOWN), 취소 `amount`·`currency`, COMPLETED면 `cancelTransactionId`·`cancelApprovalNumber`(있으면)·
  `cancelledAt`(PG 확정 시각), FAILED면 `failureCategory`(DECLINED·NOT_SENT·NO_EFFECT), 반환 후 `cancellableAmount`·`returnStatus`. 카드번호·기관 원문·내부 정책 버전·기관 계약계정 없음.
  202에는 "새 키·새 merchantCancelId로 재시도 금지 — 같은 키로 확인" 안내.
- **구현 결정 CN-02** **(→ 아래 '정정'이 현행 — 가맹점 응답에 거절 분류 미노출. Coordinator 판정이며 사용자 결정 아님, 대장 BR-CAN-107 대체됨→BR-CAN-085, S3-DOC-001)**: FAILED(DECLINED)일 때 기관 거절 분류를 고정 enum으로만 노출(`declineCategory`: `CANCEL_PERIOD_EXPIRED`·`EXCEEDS_REMAINING`·`PARTIAL_NOT_SUPPORTED`·`ISSUER_DECLINED`·`OTHER`).
  기한 경과(D-CANCEL-004)를 가맹점이 알아야 계좌 환불 미지원 안내(D-CANCEL-007)를 따를 수 있다. 기관 원문 코드는 노출하지 않는다.
  **(정정 — CP-QUERY-001 F-7, 2026-10-20 Coordinator 판정 11)**: 실제 구현(S4 D-S4-5)은 고정 분류를 기관 메시지·required Port 결과(`CancellationCallResult.Declined`·`CancelInquiryCallResult.Found`)까지만
  싣고 **가맹점 응답에는 노출하지 않는다(v1, 영속도 하지 않음)**. 응답의 FAILED 분류는 `failureCategory`(DECLINED·NOT_SENT·NO_EFFECT)뿐이다. 노출이 필요해지면 영속 열과 함께 새 결정으로 추가한다.

### 4.2 단계와 TX 경계

```text
[가맹점 서버] cancel(Idempotency-Key)
 TX1  멱등 예약/잠금 → control(X) → payment(X) → 업무 키 조회(payment_cancellation, (payment_id, merchant_cancel_id))
      ├ 같은 키·같은 내용 → EXISTING_CANCELLATION 저장 → 커밋 → 그 취소의 현재 결과
      ├ 같은 키·다른 내용 → CANCEL_REQUEST_CONFLICT 저장 → 커밋 → 409
      └ payment_authorization(X, lockByPaymentId) → return_balance(X, 없으면 없음) → Route 프로파일·취소 Capability 해석
          → CancelStart.start(…, at)
          ├ Rejected(CAPABILITY_UNAVAILABLE) → rollback → 503(저장 안 함)
          ├ Rejected(그 밖) → 거절 저장 → 커밋 → 409/422
          └ Started → payment_cancellation·cancel_attempt(PREPARED) INSERT, return_balance INSERT 또는 UPDATE, return_reservation INSERT,
                      cancellation_recovery_schedule INSERT, 멱등 결과 ACCEPTED(cancellationId) ── 커밋
 TXd  control(X) → payment(X) → payment_authorization(읽기) → return_balance(X) → payment_cancellation(X) → cancel_attempt → 회복 일정(X)
      게이트: preparedDeadline 전 · Route 등록·환경 일치·프로토콜이 취소 지원(sim-van-2) · Route 프로파일 버전 고정
      ├ 통과 → Attempt PREPARED → DISPATCHING + route_profile_version + 일정 dispatch_lease_until ── 커밋
      └ 거절 → abandonBeforeDispatch(GATE_REFUSED): 취소 FAILED(PRE_TRANSMISSION) + 예약 RELEASED + PaymentCancelFailed ── 커밋 → 200 FAILED(NOT_SENT)
 [TX 밖] CancellationPort.cancel(기관 취소 요청 ID, 원 경로, 원 거래 참조, 취소 Money, 범위)  — 잠금·DB TX 없음, 자기 deadline 재확인 뒤에만 connect
 TX2  control(X) → payment(X) → payment_authorization(읽기) → return_balance(X) → payment_cancellation(X) → cancel_attempt → 회복 일정(X)
      관찰 append + CancellationResolution + CANCEL 거래 INSERT + Outbox  /  NotSentConfirmed → abandonBeforeDispatch(CONNECT_FAILED)  /  OutcomeUnknown → markOutcomeUnknown(cause)
      ── 커밋
[응답] 제한 시간(Demo) 안 확정 → 200 COMPLETED/FAILED, 아니면 202 IN_PROGRESS/UNKNOWN. 남은 처리는 회복 Worker(§7)
```

- **확정(K-13, CLAUDE.md)**: 외부 호출은 DB TX 밖, 업무 효과·요약·요청 결과·Outbox는 같은 PAYMENT 로컬 TX. TXd 커밋 전에는 소켓을 열지 않는다(AU §4.2와 같은 근거).
- **확정(K-3)**: 취소는 신규 승인 권리를 요구하지 않는다 → TX1·TXd 모두 entitlement head를 잠그지 않고 Admission을 평가하지 않는다(ArchUnit `cancelStartIndependentOfLimitAndEntitlement` 기존 — application
  쪽 확인 시험 추가). 일 한도 사용량을 읽지도 쓰지도 않는다(D-AUTH-007).
- 조정자 `CancelPaymentService`는 TX를 열지 않고 단계 서비스가 `LocalTransactions`로 TX를 소유한다(AU 관례). TX2 실패(교착 재시도 소진·잠금 timeout·commit 불명확): 응답 202, 남은 반영은 회복이
  원 취소 요청 ID 조회로 한다.

### 4.3 TX1 상세(판정 순서 — 앞 단계가 결정하면 멈춤)

| 순서 | 단계 | 결과(저장 여부) | 근거 |
|---|---|---|---|
| 1 | 멱등 `reserveOrLock`(범위 = environment·인증 MID·`CancelPayment`·key) | 같은 키·같은 fingerprint → **operation 현재 결과 재생**(ACCEPTED·EXISTING이면 취소 현재 상태, 거절이면 저장 거절) / 다른 fingerprint → 409 `IDEMPOTENCY_KEY_REUSED` / 대기 timeout → 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` | **확정** K-22, CC-05 |
| 2 | control(X) → payment(X), 소유 확인 | 다른 MID·환경·없음 → 404(저장 안 함, rollback) | K-20 |
| 3 | 업무 키 조회 `(payment_id, merchant_cancel_id)` → `compareRequest` | SAME → `EXISTING_CANCELLATION`(cancellationId 참조) 저장 / CONFLICT → `CANCEL_REQUEST_CONFLICT` 저장 / 없음 → 계속 | **확정** D-CANCEL-002, CC §4.4-4 "업무 키 조회가 먼저" |
| 4 | `payment_authorization`(X, `lockByPaymentId`) — 없을 수 있음(REQUESTED) | — | §4.7 |
| 5 | `return_balance`(X) — 없으면 null(CC-02 지연 개설) | — | CC §9.1 |
| 6 | Route 해석: Authorization의 routeRef → 등록부 프로파일 → `CancelCapabilityResolution`(없음·환경 불일치·프로토콜이 취소 미지원 → Unavailable) | 판정은 7에서(앞 검사가 먼저 결정할 수 있게 값으로 넘김) | CC §4.2, AU §5.8 |
| 7 | 식별자: `CancellationId`(`pcn_`)·`CancelAttemptId`(`pca_`)·기관 요청 ID `cr_`+ULID(CC-09), `ProviderRequestRef`(원 승인 Attempt의 기관·환경·기관 계정, operation `CANCEL`) | — | CN-07 |
| 8 | `CancelStart.start(command(at))` | Rejected → 표 §4.9(CAPABILITY_UNAVAILABLE만 503·저장 안 함) / Started → 9 | CC §4.4 검사 1~7 |
| 9 | 저장: 취소·Attempt INSERT, 잔액 INSERT(`balanceOpened`) 또는 UPDATE, 예약 INSERT, 회복 일정 INSERT(`next_check_at = preparedAt + preparedDeadline`, 설정 버전), 멱등 ACCEPTED | 커밋 → TXd | AU D-S6-2 동형 |

- 교착(1213)·version 충돌은 새 TX로 처음부터(최대 3, `at`도 새로 — 관례 §9). 서버 생성 ID 충돌은 `IdentifierCollision`. 첫 취소 두 건의 `return_balance` INSERT는 payment X에서 이미 직렬화되므로
  UNIQUE(payment_id) 1062는 결함 경로(우회 INSERT 시험으로만 확인).
- **도출(D-CANCEL-003 + CC §4.4-4)**: 같은 merchantCancelId·같은 내용이 진행 중 취소와 겹치면 3에서 EXISTING으로 멈춰 `CANCEL_IN_PROGRESS`가 나오지 않는다(재시도 = 같은 operation).

### 4.4 TXd(전송 게이트)

- 잠금: control → payment → payment_authorization(읽기) → return_balance(X) → payment_cancellation(X) → cancel_attempt → 회복 일정(X). 게이트 거절이 예약을 해제하므로 잔액을 처음부터 잡는다(AU-06 동형).
- 재판정: **신규 승인 권리·가맹점 Credential은 재판정하지 않는다**(확정 K-3 — 시작된 취소는 키 폐기·MID 중지와 무관). 보는 것: preparedDeadline 경과 여부(경과면 보내지 않음 — 회복 fencing에 맡김),
  Route 등록·환경·프로토콜(없으면 GATE_REFUSED — AU §16.18 D-AUTH1-007 분기와 같다).
- 통과: `PaymentCancellation.beginDispatch(attemptId, routeProfileVersion)`(F-2 — 시그니처 확장) + 일정 `dispatch_lease_until` + 같은 행 version 조건부 갱신 커밋. 회복 fencing과 같은 행에서 경쟁.
- **구현 결정 CN-08**: TXd commit 불명확이면 보내지 않는다(AU-28 m-3과 같다).

### 4.5 기관 호출(TX 밖)

- 호출 직전 `now ≥ dispatch_lease_until − safetyMargin`이면 connect하지 않는다(AU-07). connect 실패만 같은 요청 ID로 예산 안 재연결 → 소진 시 `NotSentConfirmed`. 쓰기 시작 이후 실패는 전부 `OutcomeUnknown`
  (TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY — **확정** CC §7, 허용 §5.3).

### 4.6 TX2(결과 반영)

- `CancellationResolution.applyObservation(control, payment, cancellation, balance, observation, newTransactionId, at)`. 결과별 저장: 관찰 INSERT(`@Immutable`), 취소·Attempt·잔액·예약·control(hold) 갱신,
  CANCEL 거래 INSERT(Completed), Outbox(§4.10). 멱등 행은 건드리지 않는다(결과 = 취소 참조 — CC-05·D-AUTH-004 선례).
- `NotSentConfirmed` → `abandonBeforeDispatch(CONNECT_FAILED)`, `OutcomeUnknown` → `markOutcomeUnknown(cause)`(관찰 없음). 늦게 도착한 포기·표시는 NoChange(F-11).
- **확정(K-12, INV-C5)**: Payment 상태·`authorizedPaymentId`·일 한도는 바뀌지 않는다(기존 domain — application 회귀 시험만).

### 4.7 잠금 순서(CC CC-10 → AU §4.7 확장, 구현 결정 CN-09)

```text
멱등 기록 → [entitlement_head(S): 승인 경로만] → order_payment_control → payment
  → payment_authorization → [authorization_attempt → authentication_session → daily_limit_usage → … : 승인 경로]
  → return_balance → payment_cancellation → cancel_attempt → cancellation_recovery_schedule
```

| Use Case | 부분열 |
|---|---|
| 취소 TX1 | 멱등 → control → payment → (payment_cancellation 업무 키 읽기 — 비잠금, payment X 아래) → payment_authorization → return_balance → (INSERT들) |
| 취소 TXd·TX2·회복 반영 | control → payment → payment_authorization(읽기) → return_balance → payment_cancellation → cancel_attempt → 회복 일정 |
| 회복 lease 표시·PREPARED fencing | 같은 부분열 |
| 회복 후보 선점 | 회복 일정(X, 단독 짧은 TX, `SKIP LOCKED`) |
| 승인 경로(기존) | return_balance·취소 표를 잡지 않는다(CC-02) — 순환 없음 |

- **실제 직렬화 지점은 control X**다(모든 취소·승인 쓰기 경로가 control부터). `return_balance` X는 같은 Payment 합계를 지키는 두 번째 방어이고 CHECK가 세 번째다(AU §7.5 n-2 정정과 같은 설명 — CC §9.2
  "return_balance가 직렬화 지점"을 이렇게 구체화). 업무 키 조회를 비잠금으로 해도 같은 Payment의 쓰기는 payment X 아래라 일관된다.
- 교착은 `LocalTransactions` 재시도(3회), 잠금 timeout(1205)은 TX1 503 / TX2·회복 다음 주기.

### 4.8 멱등·저장 결과

- 범위(**확정** K-22): (environment, 인증 MID, operation `CancelPayment`, Idempotency-Key). `IdempotentOperation.CANCEL_PAYMENT("CancelPayment")`. 보존 30일.
- fingerprint(**구현 결정** CC-06): `x1:` = 길이 접두 canonical(paymentId, merchantCancelId, amount, currency)의 SHA-256.
- 저장 결과(V17에서 CHECK를 operation별로 다시 만든다 — §8.1):

| result_kind | result_code | 참조 | 재생 응답 |
|---|---|---|---|
| SUCCESS | `ACCEPTED`(새 시작) / `EXISTING_CANCELLATION`(같은 merchantCancelId·같은 내용, 다른 키) | `result_cancellation_id`(새 열) | 취소 **현재** 상태(CC-05) |
| REJECTED | `PAYMENT_NOT_CANCELLABLE`·`ALREADY_FULLY_CANCELLED`·`CANCEL_AMOUNT_EXCEEDS_BALANCE`·`CURRENCY_MISMATCH`·`PARTIAL_CANCEL_NOT_SUPPORTED`·`CANCEL_IN_PROGRESS`·`ORDER_ON_HOLD`·`CANCEL_REQUEST_CONFLICT` | `result_payment_id` | 같은 거절(D-CPC-007 선례) |

- 재생 시 현재 MID/환경 권한을 다시 확인(D-AUTH-004 동일). 재생은 기관 호출을 새로 하지 않는다.
- **확정(D-CANCEL-003·CC §8)**: `CANCEL_IN_PROGRESS`도 저장·30일 재생 — 진행 중 취소가 끝난 뒤 다시 하려면 새 키(같은 merchantCancelId는 거절 결과에 행이 없어 재사용 가능, D-CANCEL-002).

### 4.9 오류·응답 매핑(CC §8 구현)

| 판정 | HTTP | code | 저장 |
|---|---|---|---|
| 인증 실패 / 형식 오류 | 401 / 400 | `UNAUTHENTICATED` / `INVALID_REQUEST`·`INVALID_IDEMPOTENCY_KEY` | 안 함 |
| Payment 없음·다른 MID/환경 | 404 | `PAYMENT_NOT_FOUND` | 안 함 |
| 같은 키·다른 내용 / 처리 중 | 409 | `IDEMPOTENCY_KEY_REUSED` / `IDEMPOTENCY_REQUEST_IN_PROGRESS` | — |
| 같은 merchantCancelId·다른 내용 | 409 | `CANCEL_REQUEST_CONFLICT`(새) | 저장 |
| 취소 불가 상태(REQUESTED·AUTHORIZING·UNKNOWN·FAILED·EXPIRED, 운영자 전용 P1) | 409 | `PAYMENT_NOT_CANCELLABLE`(새) | 저장 |
| 전액 반환 완료 | 409 | `ALREADY_FULLY_CANCELLED`(새) | 저장 |
| 진행 중 취소 있음 | 409 | `CANCEL_IN_PROGRESS`(새) | 저장 |
| 이 Payment 대상 hold | 409 | `ORDER_ON_HOLD`(기존) | 저장 |
| 금액 초과 / 통화 불일치 | 422 | `CANCEL_AMOUNT_EXCEEDS_BALANCE` / `CURRENCY_MISMATCH`(새) | 저장 |
| 부분취소 미지원(Capability) | 422 | `PARTIAL_CANCEL_NOT_SUPPORTED`(새) | 저장 |
| Route·Capability 판정 불가 | 503 | `TEMPORARILY_UNAVAILABLE` | 안 함(fail-closed) |
| TX1 commit 불명확 | 503 | `TEMPORARILY_UNAVAILABLE`(`OUTCOME_UNCONFIRMED` 문구) | — 같은 키로 확인 |
| 확정(제한 시간 안) | 200 | COMPLETED / FAILED(`failureCategory`) | (TX1에 ACCEPTED) |
| 진행 중 | 202 | IN_PROGRESS / UNKNOWN | (TX1에 ACCEPTED) |

### 4.10 Outbox와 effect_key(CC §9.3)

| 이벤트 v1 | category | 시점(같은 TX) | effect_key | 비고 |
|---|---|---|---|---|
| `PaymentCancelled` | `FINANCIAL_FACT` | TX2 Completed | `PaymentCancelled:{transactionId}`(**CN-13**) | payload = CC §9.3 목록 전부: paymentId·authorizationId·originalTransactionId·cancellationId·transactionId·MID·Site·주문 ID·취소 Money·범위·authorizedAmount·반환 순번·반환 후 누적·returnStatusAfter·기관 취소 승인번호·기관 PTX·기관 취소 거래일·(있으면) 기관 업무일·PG 확정 시각·원 Authorization의 policyAssignmentVersion·entitlementVersion·개시자 종류. 카드번호·Secret·운영자 개인정보·기관 계약계정 없음 |
| `PaymentCancelFailed` | `INTEGRATION_NON_FINANCIAL` | TX2 Failed, TXd 게이트 거절, 회복 fencing | `PaymentCancelFailed:{cancellationId}` | 금융 효과 없음 |
| `CancellationOutcomeUnresolved` | `OPERATIONAL_RISK` | 회복 예산 소진·연령 초과 | `CancellationOutcomeUnresolved:{attemptId}` | 상태 불변, payload `configVersion`·`escalationReason` |
| `CancellationIncidentDetected` | `RISK_INCIDENT` | TX2·회복 사건 — **사건마다**(확정 D-CANCEL1-002, 새 hold 여부와 무관) | `CancellationIncidentDetected:{cancellationId}:{incidentSeq}`(incidentSeq = 그 사건 뒤 `incident_count`, 1부터 — 확정 D-CANCEL1-002, F-5) | 위험 힌트 HIGH("Unexpected refund"), 잔액 `excess > 0`이면 CRITICAL(D-CANCEL-006). payload에 hold occurrence·새 hold 여부·관찰 키 |

- **구현 결정 CN-13(CC m-6 결정)**: `PaymentCancelled`의 키는 `{transactionId}` — D-AUTH-003 "FINANCIAL 업무 키 = transactionId"와 같은 축. CANCEL 거래 UNIQUE(cancellation_id)라 1:1이며 금융 의미 차이는 없다.
- aggregateType `PaymentCancellation`, aggregateVersion = 취소 JPA version. 같은 TX 순서는 `id` 순서.
- **확정(ADR-001)**: Kafka 미도입. 전달 보장은 Outbox/Inbox·effect_key UNIQUE·대사이며 브로커가 금융 안전성을 대체하지 않는다.

### 4.11 가맹점·운영 안내 — 계좌 환불 미지원(확정 D-CANCEL-007, AC 1-j, rev 0.3 m-8)

D-CANCEL-007("1차 범위 밖. 카드 반환은 카드 취소로만, API·운영 안내에 계좌 환불 미지원 표시")을 그대로 옮긴 안내 문구다. 새 정책을 더하지 않는다 — 대체 반환 수단·고객 응대 절차는 이 문서가 정하지 않는다.
문구는 Reviewer 문서 검토 대상(AC 1-j)이며, 가맹점 API 문서·운영 화면(ADMIN-BOOT-001)이 생기면 이 문구를 옮긴다.

**API 안내(가맹점 — `POST /v1/payments/{paymentId}/cancel` 설명에 싣는다)**

- 이 API는 카드 승인의 **전액·부분 취소**만 처리한다. **계좌 환불(계좌이체·현금 등 카드 취소가 아닌 반환)은 지원하지 않는다.** 카드 결제 금액의 반환은 이 취소 API로만 요청할 수 있다.
- 계좌 환불 API·필드·operation은 없다(멱등 operation은 `CreatePayment`·`ConfirmPayment`·`CancelPayment`뿐 — V20 CHECK). 별도 경로로 요청해도 PayCore는 계좌 환불을 실행하지 않는다.
- 취소 결과 `FAILED`(기관 거절·기관 무효과 확정·전송 전 실패)는 PayCore가 기관 근거로 반환 없음을 확정했다는 뜻이다. 드물게 기관의 늦은 성공이 나중에 확인되면 사건으로 처리하고 해당 Payment를 보류(hold)한다(D-CANCEL-006) — `FAILED`만 보고 다른 수단으로 반환하면 이중 반환이 될 수 있다. PayCore v1은 이 경우 다른 반환 수단으로 자동 전환하지 않는다. `UNKNOWN`은 결과 확인 중이며(새 취소 요청 차단 —
  `CANCEL_IN_PROGRESS`) 계좌 환불 대상이 되지 않는다.
- 통합 이벤트 `PaymentCancelled`의 반환 정보(`returnStatusAfter`·`cumulativeReturnedAmount`)는 카드 취소로 확정된 반환만 센다.

**운영 안내(운영자·CS)**

- PayCore v1에는 계좌 환불 기능이 없다. 운영자 보정 취소(D-CANCEL-005, ADMIN 2인 승인)도 **카드 취소**이며 계좌 환불을 대신하지 않는다.
- 카드 취소가 불가능하거나 실패한 건의 반환 요청을 PayCore 기능으로 처리할 수 없다는 사실을 안내한다. 취소 `FAILED`는 기관 근거로 반환 없음을 확정한 상태지만, 드물게 기관의 늦은 성공이 나중에 확인되면 사건·보류(hold)로 처리되므로(D-CANCEL-006) 이를 최종 무반환으로 단정해 다른 수단으로 반환하지 않도록 안내한다. 그 밖의 처리 방법은 이 작업의 범위가 아니다(새 정책이 필요하면 Coordinator 질문으로).
- 계좌 환불(Refund)의 거래 상태 원본·지급 실행 원본 정의는 DOMAIN-DEFINITION Refund 행(v1 범위 밖)을 따른다.

---

## 5. 운영자 보정 취소 — Use Case 경계(AC 2)

> **현행화 주석(2026-10-29, ADMIN-PAYMENT-OPS-001 — 본문은 CANCEL-001 당시 기준 그대로 둔다)**: 아래 §5.2·§5.3의 "진입점 없음·서비스 간 인증 없음(D-CANCEL1-001)"은 ADMIN-PAYMENT-OPS-001에서 열렸다. PAYMENT `POST /internal/v1/admin/operator-cancellations`(ADMIN 서명 채널 — HMAC, 운영 mTLS는 LIVE Gate) → `OperatorCorrectionCancelUseCase`, approvalId별 결정 기록·재생(D-OPS-005 — CN-10의 approvalId 업무 키를 결정 행 UNIQUE로 강화), 실행 기한(D-OPS-002), 기한 확인 전용 호출(D-OPS-009), 대상 조회 `GET /internal/v1/admin/payments/{id}/operator-cancel-target`(`OperatorCancelTargetQuery`), 결정 목록(대사). ADMIN 쪽은 2-TX 승인·회복·대사(ARCHITECTURE-CONVENTIONS §21). §5.3의 ArchUnit "adapter.web이 Use Case를 부르지 않음" 규칙은 정확한 진입점 FQN 허용으로 바뀌었다.

### 5.1 확정된 것

- **확정 D-CANCEL-005**: 목적은 사고·대사 보정(`IncidentOrReconCorrection(caseRef)`)과 가맹점 종료 뒤 취소 의무(`MerchantTerminatedObligation(terminationRef)`)만. 사유·행위자 기록, 2인 승인(D-ADMIN-003
  "Critical 해결" 등급), 양쪽 검사(D-ADMIN-007).
- **확정 D-ADMIN-007**: 승인 요청·상태는 ADMIN이 관리하고, 소유 서비스 명령은 요청자·승인자·승인 ID·대상 버전을 받아 **요청자 ≠ 승인자와 대상 버전 일치를 다시 검사**하고 자기 감사에 기록. 관리자
  앱 단독으로 실행할 수 없다.
- **확정 D-CANCEL-001·010·011·013**: 운영자 보정 취소는 이 Payment 대상 hold 중에도 시작, P1(FAILED + RECON 기록 거래)은 운영자만.
- **확정 CC-12·CC-13**(구현 결정, 계약 반영) **(→ 대체: CC-12의 '같은 승인 = 기존 결과' 판정은 D-OPS-005 — approvalId별 첫 확정 결정 INSERT 전용 기록·재생, S3-DOC-001)**: 영구 업무 키 = `approvalId` UNIQUE, 승인 대상 지문 `oct1:`(paymentId·금액·통화·잔액 revision·approvalId·요청자·승인자·목적 종류·근거 — 사유 제외).

### 5.2 책임 나눔(도출 — D-ADMIN-007 문언 + 현재 코드)

| 검사 | 어디서 | 이 작업 |
|---|---|---|
| 요청자 ≠ 승인자, 사유 있음, 목적 근거 있음, 승인 대상 지문 = 실행 시점 값 | PAYMENT(`CancelStart` 업무 거절 — 기존 domain) | Use Case·영속·감사 열·시험 |
| approvalId 영구 유일·같은 승인 다른 금액 충돌·다른 Payment 충돌 **→ 보완: 같은 내용 재요청 = 첫 확정 결정 재생(D-OPS-005), ADMIN의 충돌 응답 처리(D-OPS-007 — 비종결·경보). 다른 내용 충돌 판정 자체는 BR-CAN-052 `제안` — RULE-CONFIRM-001 Q10 대기** | PAYMENT(`compareRequest` + UNIQUE(approval_id)) | 영속·시험 |
| 승인자 역할 APPROVER·요청자 역할·TOTP 재입력·승인 요청 상태(승인됨·무효화 안 됨)·요청 뒤 대상 변경 시 무효 | ADMIN | ADMIN-BOOT-001 인계 |
| 호출자 인증(ADMIN 서비스만 이 명령을 부를 수 있음) | 서비스 간 인증(운영 mTLS는 LIVE Gate) | **ADMIN-BOOT-001 — 이 작업은 진입점을 열지 않는다(확정 D-CANCEL1-001)** **→ 대체: D-OPS-001(ADMIN→PAYMENT HMAC 서명 채널), 진입점 `POST /internal/v1/admin/operator-cancellations`(`AdminOperatorCancellationController.kt:22`)는 ADMIN-PAYMENT-OPS-001이 열었다** |
| 승인 상태(승인됨·무효화 안 됨) 실재 확인 | ADMIN(인증된 호출자 책임) — **PAYMENT는 ADMIN에 동기 조회하지 않는다**(확정 D-CANCEL1-001) | 콜백 Port 없음 |
| 승인 대상 조회(현재 cancellable·revision·통화) | PAYMENT 조회 API(내부) | ADMIN-BOOT-001(호출 경로와 함께) — 이 작업은 provided Query만(§5.3) **→ 현행: ADMIN-PAYMENT-OPS-001이 `GET /internal/v1/admin/payments/{id}/operator-cancel-target`(`OperatorCancelTargetController.kt:29`)로 열었다** |
| `oct1:` 지문 정규화가 두 서비스에서 같음 | 공유 계약 golden(harness 생성) | 이 작업에서 golden 생성·PAYMENT 쪽 바이트 비교 시험, ADMIN 쪽 대조는 ADMIN-BOOT-001(CN-11) |

### 5.3 이 작업이 만드는 것

- provided `OperatorCorrectionCancelUseCase`(명령: paymentId·금액·통화·`CancelInitiator.Operator` 필드 — 요청자·승인자·approvalId·사유·목적·승인 대상 지문) → `OperatorCorrectionCancelService`.
  TX1은 가맹점 경로와 같은 단계 서비스(`CancelStartService`)를 쓰고 멱등 기록 대신 **approvalId 업무 키**로 재요청을 판정한다(**구현 결정 CN-10** — 운영자 명령에는 (MID, 환경) 멱등 범위가 없다.
  approvalId는 영구 키라 30일 보존과 무관하게 같은 결과를 돌려준다). TXd·TX2·회복은 가맹점 취소와 공용.
- provided `OperatorCancelTargetQuery`(paymentId → 현재 `OperatorCancelTarget` 재료: 통화·cancellable·revision, 존재 여부) — 진입점 없이 Use Case 시험으로만 쓰인다.
- 감사: 취소 행에 initiator_kind·requested_by·approved_by·approval_id·operator_reason·purpose_kind·purpose_ref·approved_target_fingerprint(§8.1). 같은 로컬 TX라 감사 저장 실패 = 취소 시작 실패.
- 시험: hold 중 시작(D-CANCEL-001), P1 운영자 시작·가맹점 거절(D-CANCEL-011), 같은 사람·사유·근거·stale 거절과 거절 시 행 0, approvalId 재사용(같은 금액 = 기존 결과, 다른 금액·다른 Payment = 충돌),
  운영자 보정이 가맹점 hold에도 결과 반영(CC-16).
- **하지 않는 것(확정 D-CANCEL1-001)** **(→ 대체: 진입점·서비스 간 인증 부분은 D-OPS-001로 ADMIN-PAYMENT-OPS-001이 열었다 — 대장 BR-CAN-105 대체됨→BR-CAN-057. 'PAYMENT는 ADMIN에 승인 상태를 동기 조회하지 않는다'는 유지(BR-CAN-049). 아래 '운영 환경 실행 경로 없음'·ArchUnit 규칙 문장은 CANCEL-001 당시 기록, S3-DOC-001)**: HTTP/내부 진입점, 서비스 간 인증, ADMIN 승인 상태 조회(동기 콜백 없음). 그 전까지 운영 환경에서 운영자 보정 취소는 실행 경로가 없다(Demo·시험만) — AC 2의 완료 의미는
  "Use Case·재검사·영속·경계 문서까지"다. 구조 시험: `OperatorCorrectionCancelUseCase`를 부르는 `..adapter.web..`·`..adapter.batch..` 클래스가 없음(ArchUnit, 위반 fixture 포함 — ADMIN-BOOT-001이 진입점을 열 때 이 규칙을 그 adapter 허용으로 바꾼다).
- **ADMIN-BOOT-001 인계(Coordinator가 AC에 추가함 — §15-2)**: 진입점 + 서비스 간 인증, `OperatorCancelTargetQuery` 내부 조회, `oct1:` golden ADMIN 쪽 대조, 대상 revision 변경 시 승인 무효, APPROVER·TOTP 재입력, Q-ADMIN-APPROVER-STAFF.

---

## 6. 가상 VAN 취소(`sim-van-2`)

### 6.1 계약 버전(구현 결정 CN-20)

- sim-van-1은 그대로 둔다(golden 파일·바이트 불변). 새 계약 이름 **`sim-van-2`** = sim-van-1의 프레임(`version:u8 = 1`)·메시지 전부 + 취소 메시지 4종. 프레임 형식이 같으므로 Simulator는 메시지 `type`으로
  구분하고 두 계약을 함께 받는다. PG Route `protocol-version`은 `sim-van-1`(승인·조회만) 또는 `sim-van-2`(취소 포함)이며, 취소 시작은 원 경로 Route가 `sim-van-2`일 때만 Capability를 해석한다
  (아니면 Unavailable → 503).
- 원 Authorization의 `admission.protocolVersion`(승인 때 `sim-van-1`)은 감사용 고정값이며 취소 전송 프로토콜을 정하지 않는다 — 취소는 **현재** 원 경로 Route 프로파일로 보낸다(도출 — 기관 지원 범위는
  기관의 현재 규약, CC §4.2. 시작 때 Capability 버전·전송 때 프로파일 버전을 고정해 감사).
- 대안: sim-van-1에 메시지 추가(같은 버전 이름의 의미가 바뀌어 golden 계약의 "검토된 변경" 원칙과 충돌 — 택하지 않음).

### 6.2 메시지(닫힌 필드 집합, 엄격 파싱 — AU §5.2 규칙 그대로)

| 메시지 | 필드 |
|---|---|
| `CANCEL_REQUEST` | `type`, `messageId`, `requestId`(`cr_`+ULID), `institutionAccount`, `merchantRef`, `environment`(TEST만), `routeRef`, `originalRequestId`(원 승인 `ar_`), `originalProviderTransactionId`, `originalApprovalNumber`, `originalTransactionDate`, `cancelAmountMinor`(≥ 1), `currency`, `scope`(FULL\|PARTIAL), `requestedAt` |
| `CANCEL_RESPONSE` | `type`, `inReplyTo`, `requestId`, `outcome`(APPROVED·DECLINED·PENDING·REQUEST_CONFLICT·REJECTED_CLOSED), `decisionId`(확정일 때), `pendingId`(PENDING), APPROVED면 `cancelApprovalNumber`(nullable — 기관이 주지 않을 수 있음, null 허용은 이 필드만), `providerTransactionId`(취소 PTX), `originalProviderTransactionId`, `cancelledAmountMinor`, `currency`, `institutionTransactionDate`, `institutionBusinessDate`, DECLINED면 `declineCategory`, `respondedAt` |
| `CANCEL_INQUIRY_REQUEST` | `type`, `messageId`, `inquiryId`(`cq_`+ULID), `originalRequestId`(원 **취소** `cr_`), `institutionAccount`, `environment`, `closeIfAbsent` |
| `CANCEL_INQUIRY_RESPONSE` | `type`, `inReplyTo`, `inquiryId`, `originalRequestId`, `result`(FOUND·PENDING·NOT_FOUND·NO_EFFECT_CONFIRMED·UNAVAILABLE), FOUND면 `decisionId`와 CANCEL_RESPONSE 결정 필드 전부, NO_EFFECT_CONFIRMED면 `closureId` |

- payload hash는 업무 필드 canonical(전송 필드 `messageId`·`requestedAt` 제외 — AU m-7). 카드번호·인증 비밀 없음.
- 거절 분류(Demo 가상 규격): `ORIGINAL_NOT_FOUND`·`ORIGINAL_NOT_APPROVED`·`ORIGINAL_MISMATCH`·`CANCEL_PERIOD_EXPIRED`·`PARTIAL_NOT_SUPPORTED`·`EXCEEDS_REMAINING`·`ISSUER_DECLINED`.
- **CN-23 PENDING 범위**: PG는 CANCEL_RESPONSE PENDING·조회 PENDING을 처리한다(stub 시험). Simulator 취소 PENDING 시나리오는 이번에 만들지 않는다 — `van_pending_decision`은 승인 모양(증거·Plan NOT NULL)이라
  새 표가 필요하고, PG 판정은 PENDING = UNKNOWN 유지라 stub으로 충분하다(편차 기록, 대안: 취소 예약 표 추가).

### 6.3 VAN 판정(Simulator domain `VanCancellation`, 앞 단계가 결정하면 멈춤)

1. 같은 namespace(client·environment·institutionAccount·`CANCEL`·requestId) 행이 있으면: payload hash 같음 → 기존 결정 재응답, 다름 → `REQUEST_CONFLICT`, 종결 표지 → `REJECTED_CLOSED`(원장 불변).
2. environment ≠ TEST → 프레임 거절(원장 불변). 요청 ID ULID가 보존기간보다 오래됨 → 기록 없이 종료(AU n-3 Demo 규칙과 같다).
3. 원 승인: `van_ledger_entry`(client·environment·account·`AUTHORIZE`·`originalRequestId`) 없음·종결 표지 → DECLINED(`ORIGINAL_NOT_FOUND`), APPROVED 아님 → `ORIGINAL_NOT_APPROVED`, PTX·승인번호·통화 echo
   불일치 → `ORIGINAL_MISMATCH`.
4. **Demo** 취소 기한: VAN 시계 − 원 승인 기록 시각 > 프로파일 `cancelWindow` → DECLINED(`CANCEL_PERIOD_EXPIRED`)(D-CANCEL-004 — PG는 사전 판정하지 않음).
5. 부분: 금액 < 원 승인액이거나 이미 승인된 취소가 있으면 PARTIAL. 프로파일이 (원 Plan, cardType)의 부분취소를 지원하지 않으면 DECLINED(`PARTIAL_NOT_SUPPORTED`). `scope` echo가 이 판정과 다르면
   DECLINED(`ORIGINAL_MISMATCH` — 가상 규격 위반으로 분류).
6. 기관 잔액: 금액 > 원 승인액 − Σ(이 원 승인의 APPROVED 취소) → DECLINED(`EXCEEDS_REMAINING`).
7. 가상 카드 취소 시나리오(§6.4) → 결정 → **원장 INSERT 후** 응답(성공 후 응답 유실의 전제). APPROVED는 `approved_sequence = n + 1`로 INSERT — 같은 원 승인의 동시 취소가 같은 순번을 얻으면 UNIQUE 1062 →
   새 TX 재판정(최대 3, 소진 시 기록 없이 연결 종료 — AU n-2와 같다). 거절도 원장에 기록(같은 ID 재전송 = 같은 거절).

### 6.4 Demo 시나리오(취소 전용 가상 카드 — 승인은 정상, 취소만 다르게 동작, 결정 가능·seed 없음, 운영값 아님)

| 가상 카드(≤ 32자) | 취소 동작 | PG가 겪는 것 |
|---|---|---|
| 기존 카드 전부(`VC-CREDIT-OK`·`VC-CHECK-OK`·`VC-SPLIT-OK` 등) | 정상 취소 | 200 COMPLETED(전액·부분, 신용/체크·일시불/할부 — D-AUTH1-003 행렬) |
| `VC-CXL-DECLINE` | DECLINED(`ISSUER_DECLINED`) | 200 FAILED(DECLINED), 예약 RELEASED |
| `VC-CXL-LATE-RESPONSE` | 원장 APPROVED 커밋 → read timeout보다 늦게 응답 | UNKNOWN → 조회 FOUND → COMPLETED |
| `VC-CXL-DROP-AFTER` | 원장 APPROVED 커밋 → 응답 없이 종료 | OutcomeUnknown → 조회 → COMPLETED(응답 유실, 재전송 없음) |
| `VC-CXL-DROP-BEFORE` | 기록 없이 종료 | UNKNOWN → NOT_FOUND → 프로파일 A 종결 → FAILED(NO_EFFECT)·RELEASED / 프로파일 B 예산 소진 → 이관, UNKNOWN·RESERVED 유지·다음 취소 차단 |
| `VC-CXL-PARTIAL` | APPROVED, 취소 금액 = 요청 − 1 | UNKNOWN 유지 + CANCEL_RESULT_CONFLICT hold + 사건(D-CANCEL-006) |
| `VC-CXL-BAD-MAC` | MAC 틀린 응답(원장 APPROVED) | OutcomeUnknown(INTEGRITY) → 조회 → COMPLETED |
| `VC-CXL-FRAGMENTED` | 응답 분할 write | COMPLETED(부분 read) |
| `VC-CXL-INQUIRY-UNAVAILABLE-ONCE` | 첫 취소 조회 UNAVAILABLE | 다음 주기 확정 |
| (시험 절차) 늦은 성공·다른 PTX 성공 | 원장 주입 또는 결과 반영 서비스에 다른 출처 관찰 직접 주입(AU C27·`VC-VAN-SECOND-APPROVAL`과 같은 방식) | FAILED 뒤 늦은 성공 → FORCED + hold, COMPLETED 뒤 다른 PTX → 두 번째 거래 없음 + hold |
| (시험 절차) 기한 경과 | Simulator 시계를 `cancelWindow` 뒤로 | 200 FAILED(DECLINED, `CANCEL_PERIOD_EXPIRED`) |
| (시험 절차) 기관 잔액 부족 | 원장에 다른 요청 ID 취소 주입 | 200 FAILED(DECLINED, `EXCEEDS_REMAINING`) — **확정 CC §4.6 "기관 잔액 부족 → FAILED"**(RECON이 차이를 본다) |

- **구현 결정 CN-25**: 취소 시나리오는 원 승인 원장 행의 `card_ref`로 고른다(AU-12 카탈로그 확장 — 인증 모듈 카드 정의에 취소 동작 속성 추가). 새 카드는 승인 동작이 NORMAL.

### 6.5 조회·무효과 종결·원장·권한

- 조회·종결 규칙은 AU §5.4 그대로를 취소 namespace에 적용: 원 **취소** 요청 ID 참조만, `closeIfAbsent`는 프로파일 `cancelConfirmsNoEffect`가 있고 `cancelClosableAfter` ≤ 연령 < `cancelInquiryRetention`일 때만
  종결 표지 INSERT → 이후 같은 ID의 늦은 원 요청은 `REJECTED_CLOSED`. 보존기간 경과는 `UNAVAILABLE`. (취소 PENDING 예약이 없으므로 PENDING vs 종결 경합은 없다 — CN-23.)
- **Simulator V5**(`db/migration/simulator`): `van_cancel_entry`(append-only: entry_id PK, namespace UNIQUE, environment CHECK 'TEST', operation CHECK 'CANCEL', kind DECISION·CLOSURE, payload_hash,
  original_request_id, outcome, 취소 조건 열(APPROVED ⇔ 금액·통화·PTX·원 PTX·거래일·업무일·approved_sequence NOT NULL, cancel_approval_number는 NULL 허용), decline_category(DECLINED ⇔), 종결 표지 모양 CHECK,
  UNIQUE(client_id, environment, institution_account, original_request_id, approved_sequence)), `van_inquiry_log`에 `operation` 열(NULL = V5 이전 승인 조회, CHECK NULL 또는 IN ('AUTHORIZE','CANCEL') — CN-26).
- **Simulator V6**(`db/runtime-privilege/simulator`): `van_cancel_entry` SELECT·INSERT(UPDATE·DELETE 없음 — 1142). `VanLedgerPrivilegeGuard` 탐침에 새 표 추가.
- 재시작 내구성: Simulator 재시작 뒤 취소 조회 FOUND 동일(기존 `SimulatorRestartDurabilitySpec`에 1건).
- **Demo** 프로파일 값(운영값 아님): A(`cancelConfirmsNoEffect` true)·B(false), `cancelWindow` 예 P365D, 부분취소 지원 = 신용·체크 일시불, 신용 할부(A) / 신용 일시불만(B — CX-08 할부 부분취소 미지원 행 시험용).

### 6.6 PG Adapter·Route 프로파일

- `payment.adapter.integration.institution.SimulatorVanCancellationAdapter`(`CancellationPort`·`CancelInquiryPort` 구현 — 요청당 연결 1, connect/read/전체 deadline·watchdog, 지표). 프레임·MAC 코드는 기존
  `SimVanFrames`·`VanSocketConnector` 재사용, 메시지는 `SimVanMessages`에 취소 4종 추가(Simulator 코드와 공유하지 않음 — golden으로만).
- 결과 변환(Adapter는 domain 전이를 호출하지 않는다 — 기존 ArchUnit `institutionAdapterDoesNotMutateDomain`):

| VAN 응답 | Port 결과 | 관찰 |
|---|---|---|
| CANCEL_RESPONSE APPROVED(필수 필드·echo·MAC) | `Approved(observation)` | APPROVED + `CancelConfirmedTerms`(sourceResultId = decisionId) |
| DECLINED | `Declined(observation)` | DECLINED |
| PENDING | `Pending(observation)` | PENDING(sourceResultId = pendingId) |
| REQUEST_CONFLICT / REJECTED_CLOSED / MAC·스키마·echo 위반 | `OutcomeUnknown(CORRELATION·INTEGRITY·FRAMING)` | 없음 |
| 조회 FOUND / PENDING / NOT_FOUND / NO_EFFECT_CONFIRMED(A만 — B면 INTEGRITY) / UNAVAILABLE | `Found` / `Found(PENDING)` / `NotFound`(inquiryId) / `NoEffectConfirmed`(closureId) / `Unavailable` | 각각(AU §5.7.2·§5.7.3과 같은 sourceResultId 계약) |

- Route 등록부(**구현 결정 CN-28**): `paycore.authorization.routes.<routeRef>`에 `protocol-version: sim-van-2`(허용 값 집합 {sim-van-1, sim-van-2})와 `capabilities.cancel.{version, partial-lump-sum-card-types,
  partial-installment-card-types, confirms-no-effect, closable-after, inquiry-retention}` 추가(운영 기본값 없음). `sim-van-2`인데 cancel 블록이 없으면 기동 실패. **Capability를 바꾸므로 profile-version도 바꾼다**
  (D-AUTH1-007 §20-6 — F-8 배포 메모). 기동 일관성 검사에 `cancel.closable-after < cancel.inquiry-retention`, `cancel.closable-after ≥ preparedDeadline + dispatchLease`(취소 timing) 추가.
- 격리: ArchUnit `cancellationPortsImplementedOnlyBySimulatorAdapter` + 위반 fixture `archfixture.institution.RealVanCancellationAdapter`. LIVE 환경 Route 없음(기존 등록부 검사).

---

## 7. 취소 UNKNOWN 회복

### 7.1 재사용과 분리(구현 결정 CN-24)

**(rev 0.2) Coordinator 판정: 선택 A 채택**(F-4 기록 — AU §1.3의 회복 일정 operation 열은 실제로 없다).

| 선택 | 내용 | 판정 |
|---|---|---|
| A. **AUTH-001 설계 복제·재사용(권장)** | 새 표 `cancellation_recovery_schedule`(FK `cancel_attempt`), `JpaCancellationRecoveryScheduleStore.claimDue`(`FOR UPDATE SKIP LOCKED`), `CancellationRecoveryService`, `adapter.batch.CancellationRecoveryScheduler`, 설정 `paycore.cancellation.timing.{dispatch,recovery}.*`(필수 `recovery.version`, 운영 기본값 없음 — D-AUTH-009). 같은 판정 구조(① lease 만료 표시 ② PREPARED fencing ③ 조회·반영·예산·이관·느린 조회·보존기간 중지) | 채택 제안. 이미 DONE인 승인 경로·V12를 건드리지 않는다. 중복 코드는 작은 순수 helper(시간 계산·일정 갱신 값)만 공유 검토 |
| B. 승인 회복 일정 일반화 | V12 표에 operation·취소 Attempt FK 다형 열 ALTER, `AuthorizationRecoveryService`를 operation별 분기 | 기각 제안: 배포된 표 ALTER, FK 다형성(두 FK nullable + CHECK), DONE 경로 회귀 위험, 설정 버전이 두 operation에 섞임 |

- **확정(K-14, D-AUTH-009, CC §4.7)**: 원 취소 요청 ID로만 조회, 새 기관 요청 ID·새 취소 재전송 금지, 예산·연령 상한·보존기간은 버전 있는 설정(Demo). 소진 → `CancellationOutcomeUnresolved` + 이관 표시,
  **취소 UNKNOWN·예약 RESERVED 유지**, 자동 FAILED 없음. 이관 뒤 보존기간까지 느린 조회 계속(AU-22와 같은 근거 — 조회 읽기 전용·반영 멱등).
- **확정 D-CANCEL1-004(F-2)**: TXd가 DISPATCHING과 같은 TX에서 `cancel_attempt.route_profile_version`을 고정한다. `closeIfAbsent`는 고정 버전 = 현재 등록부 버전 ∧ `cancel.confirms-no-effect` ∧
  `closable-after ≤ 연령 < inquiry-retention`일 때만. 버전이 다르거나 NULL이면 종결 요청 없이 조회·예산/연령 이관만(찾은 결정 반영은 그대로) — 로그 `closure=WITHHELD reason=ROUTE_PROFILE_VERSION_MISMATCH`(AU §16.18과 같다).
- **확정 D-CANCEL1-003**: 이관된 취소 UNKNOWN에는 임시 출구가 없다(운영자 확정 Use Case·같은 ID 재전송 회복 모두 없음 — CN-27 유지). 예약 RESERVED·다음 취소 차단·이관 이벤트·gauge만. **LIVE Gate 조건**: 이관된 취소 UNKNOWN을
  대사 근거로 확정하는 RECON Use Case가 있기 전 LIVE 허용 불가(§20에 S8 기록, RECON-CONTRACT-001 AC 인계됨).
- 사건 처리된 진행 중 취소(`incidentRecorded`)의 조회 결과는 NoChange(CC-16) — 예산대로 이관한다. 그 출구는 RECON(§15-7).
- **확정(D-CANCEL-003)**: UNKNOWN·이관된 취소는 확정 전까지 같은 Payment의 운영자 보정을 포함한 모든 다음 취소를 막는다(CC §14.1 m-3). RECON 전 출구 없음(확정 D-CANCEL1-003).
- 인과 값: correlationId = 회차 ID, causationId = `cancellation-recovery:<회차 ID>`. 한 건 실패는 그 건만 rollback·지표, 다음 후보 계속.

### 7.2 자동 종료 근거("never auto-fail" — CC §4.6 그대로)

| 사건 | 결과 |
|---|---|
| 기관 DECLINED(응답·조회) | FAILED(`INSTITUTION_CONFIRMED_DECLINE`), RELEASED |
| 기관 NO_EFFECT_CONFIRMED(프로파일 A 종결 확정, 버전 일치) | FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`), RELEASED |
| 게이트 거절·회복 fencing·connect 실패 예산 소진 | FAILED(`PRE_TRANSMISSION_FAILURE_CONFIRMED`), RELEASED |
| read timeout·끊김·무결성·lease 만료·TX2 장애 | UNKNOWN, RESERVED 유지 |
| 조회 NOT_FOUND·PENDING·UNAVAILABLE·예산 소진·연령 초과 | UNKNOWN 유지 |

---

## 8. 영속화

### 8.1 PAYMENT migration(V15부터 — AU-19 배치 규칙: 새 표 / 권한 / ALTER를 섞지 않음)

공통: `utf8mb4_0900_bin`, UTC `DATETIME(3)`, `version BIGINT ≥ 0`, 금액 `≥ 1`·통화 `^[A-Z]{3}$`·환경 CHECK, 자식 → 부모 FK. 카드번호·CVC 열 없음(`NoCardDataSchemaSpec` 확장).

| 파일 | 내용 |
|---|---|
| `V15__cancellation.sql`(새 표만) | **`return_balance`**(PK `payment_id`, FK payment·payment_authorization·payment_transaction(원 승인 거래), `authorized_minor`·`currency`, `completed_minor`·`reserved_minor`·`forced_minor` ≥ 0, `revision` ≥ 0(업무 revision — CN-16), `version`, **CHECK(completed_minor + reserved_minor ≤ authorized_minor)**(forced 제외 — CC H-1)) · **`return_reservation`**(PK `cancellation_id`, FK return_balance(payment_id), `amount_minor` ≥ 1, `state` CHECK 4, `excess`(FORCED ⇔ NOT NULL, CHECK 2값), `sequence`(CONSUMED ⇔ NOT NULL, ≥ 1), UNIQUE(payment_id, sequence), 생성 열 `in_flight_payment_id = IF(state='RESERVED', payment_id, NULL)` **UNIQUE**, `version`) · **`payment_cancellation`**(PK `cancellation_id`, FK payment·payment_authorization·payment_transaction(원 거래), OrderKey 열, `amount_minor`·`currency`, `scope` CHECK 2, `status` CHECK 4, `failure_evidence`(FAILED ⇔ NOT NULL, IN ('INSTITUTION_CONFIRMED_DECLINE','INSTITUTION_CONFIRMED_NO_EFFECT','PRE_TRANSMISSION_FAILURE_CONFIRMED')), `incident_count` ≥ 0(확정 D-CANCEL1-002, CN-14), `initiator_kind` CHECK 2, `merchant_cancel_id`(MERCHANT ⇔ NOT NULL), 운영자 열 `approval_id`·`requested_by`·`approved_by`·`operator_reason`(VARCHAR 500)·`purpose_kind`·`purpose_ref`·`approved_target_fingerprint`(OPERATOR ⇔ 모두 NOT NULL, CHECK requested_by <> approved_by), `original_provider_transaction_id`, `policy_assignment_version` VARCHAR(160), `entitlement_version`, `cancel_capability_version`(F-2), `opened_at`, `version`, **UNIQUE(payment_id, merchant_cancel_id)**, **UNIQUE(approval_id)**, 생성 열 `in_flight_payment_id = IF(status IN ('OPEN','UNKNOWN'), payment_id, NULL)` **UNIQUE**) · **`cancel_attempt`**(PK `attempt_id`, FK payment_cancellation **UNIQUE**(취소당 Attempt 1 — CC-11), namespace(`institution`·`environment`·`institution_account`·`operation` CHECK 'CANCEL'·`request_id`) **UNIQUE**, `state` CHECK 5, `abandon_cause`(⇔ ABANDONED), `unknown_cause`(OUTCOME_UNKNOWN ⇒ NOT NULL), `route_profile_version`(PREPARED면 NULL, `^[!-~]{1,64}$` — F-2, 확정 D-CANCEL1-004), `prepared_at`, `version`) · **`cancel_observation`**(append-only: PK id, FK cancel_attempt·payment_cancellation, `source`·`source_result_id`·`variant` UNIQUE(attempt_id, source, source_result_id, variant), `outcome` CHECK 6, 취소 조건 열(APPROVED ⇔ 금액·통화·원 PTX·PTX·거래일 NOT NULL, 승인번호·업무일 NULL 허용), `evidence_hash`, `observed_at`) |
| `db/runtime-privilege/payment/V16__cancellation_runtime_privileges.sql` | §8.2 표 |
| `V17__cancellation_existing_tables.sql` | `payment_transaction`(F-3): 승인 전용 열 `plan_kind`·`card_type`·`approval_number` NULL 허용(`MODIFY`), `ck_payment_transaction_type` → `_v2` IN ('AUTHORIZATION','CANCEL'), plan·card_type CHECK를 `type = 'AUTHORIZATION'` 조건으로 다시 묶은 `_v2`(한 ALTER에서 DROP + ADD — CHECK 없는 구간 없음, AU n-3), 새 열 `cancellation_id`(FK payment_cancellation)·`original_transaction_id`(FK payment_transaction)·`cancel_scope`·`original_provider_transaction_id`(CANCEL ⇔ 모두 NOT NULL, AUTHORIZATION ⇔ 모두 NULL), 생성 열 `cancel_txn_key = IF(type='CANCEL', cancellation_id, NULL)` **UNIQUE**, `recorded_by`는 CANCEL이면 RESOLUTION. 기존 AUTHORIZATION 행은 CHECK 통과(시험) · `incident_hold`: `ck_incident_hold_reason_v2` → `_v3`(+ `CANCEL_RESULT_CONFLICT`, 한 ALTER) · `idempotency_request`: `result_cancellation_id` NULL 열, operation CHECK `_v2`(+ CancelPayment), `ck_idempotency_request_result` 재작성(CreatePayment·ConfirmPayment 행 그대로 + CancelPayment 행 §4.8) |
| `V18__cancellation_recovery_schedule.sql` | `cancellation_recovery_schedule`(V12와 같은 모양: PK `attempt_id` FK cancel_attempt, `cancellation_id`·`payment_id`·OrderKey 열(control부터 잠그려고), `dispatch_lease_until`, `next_check_at`, `inquiry_count`, `escalated_at`·`escalation_config_version` 짝, `closed_at`, `recovery_config_version` NOT NULL, `version`, 색인 (closed_at, next_check_at)) |
| `db/runtime-privilege/payment/V19__cancellation_recovery_privileges.sql` | 회복 일정 SELECT·INSERT·UPDATE |

- 표 사이 관계(COMPLETED ⇔ CANCEL 거래, 예약 합계 = 잔액 열, 취소 상태 ⇔ 예약 상태)는 CHECK로 표현할 수 없다 → domain `reconstitute`(F-1·`ReturnBalance.reconstitute`)와 같은 TX 저장 시험이 지킨다.
  **구현 결정 CN-17**: `return_balance`의 합계 열은 Mapper가 예약 행에서 계산해 쓰고(DB CHECK의 대상), 복원 때 행 합계와 열이 다르면 복원 거부(조용히 고치지 않음).
- 보존: 취소 관찰·거래·원장은 영구(확정 사실). 회복 일정 정리 Job은 후속.

### 8.2 runtime 권한(관례 §9.2 — 실제 쓰기 경로만)

| 표 | 권한 | 쓰기 경로 | 없는 권한 |
|---|---|---|---|
| `return_balance` | SELECT, INSERT, UPDATE | TX1 개설·예약, TXd·TX2·회복 상태(`FOR UPDATE`) | DELETE |
| `return_reservation` | SELECT, INSERT, UPDATE | TX1 INSERT, 소비·해제·FORCED UPDATE | DELETE |
| `payment_cancellation` | SELECT, INSERT, UPDATE | TX1 INSERT, 상태·사건 UPDATE(`FOR UPDATE`) | DELETE |
| `cancel_attempt` | SELECT, INSERT, UPDATE | TX1 INSERT, dispatch·회복 UPDATE | DELETE |
| `cancel_observation` | SELECT, INSERT | INSERT만(`@Immutable`) | UPDATE, DELETE, 잠금 읽기(1142) |
| `payment_transaction`(기존) | 변경 없음(SELECT, INSERT) | CANCEL 행 INSERT | — |
| `cancellation_recovery_schedule` | SELECT, INSERT, UPDATE | TX1 생성·lease·회복 갱신 | DELETE |
| `incident_hold`·`idempotency_request`·`outbox_event` | 변경 없음 | 열·CHECK만 | — |

`DatabaseAccountSeparationSpec` 기대값·표 목록에 새 표 6개 추가(추가만 — 완화 아님, Reviewer 확인 대상).

### 8.3 명명(관례 §14 판정 기준)

| 계층 | 이름 | 종류 |
|---|---|---|
| provided | `CancelPaymentUseCase`, `OperatorCorrectionCancelUseCase`, `RecoverCancellationsUseCase`, `OperatorCancelTargetQuery`, `CancellationBacklogQuery` | 명령·조회 |
| required | `PaymentCancellationRepository` → `JpaPaymentCancellationRepository`(Aggregate — Attempt·관찰·거래 포함, `Versioned`, `lock`·`findByMerchantKey`·`findByApprovalId`·`insertNew`·`update`) | Aggregate 저장 |
| required | `ReturnBalanceRepository` → `JpaReturnBalanceRepository`(Aggregate — 예약 포함, `lockByPaymentId`·`insertNew`·`update`) | Aggregate 저장 |
| required | `CancellationRecoveryScheduleStore` → `JpaCancellationRecoveryScheduleStore` | Store ③ |
| required | `CancellationViewReader` → `JooqCancellationViewReader`, `CancellationBacklogReader` → `JooqCancellationBacklogReader` | jOOQ 조회 |
| required | `CancellationPort`, `CancelInquiryPort` → `SimulatorVanCancellationAdapter`, `CancellationIdentifierGenerator` → `SecureRandomCancellationIdentifierGenerator`(pcn_/pca_/cr_/cq_ — CN-07), `CancellationTimings` → `CancellationTimingSettings` | 외부 기관·설정 |
| 서비스 | `CancelPaymentService`(조정자, TX 없음), `CancelStartService`(TX1 — 가맹점·운영자 공용 단계), `OperatorCorrectionCancelService`(조정자), `CancellationDispatchService`(TXd + 호출), `CancellationResultService`(TX2·회복 공용), `CancellationRecoveryService`, `CancellationBacklogService` | `LocalTransactions` |
| web·batch | `CancelPaymentController`·`CancelPaymentRequestParser`, `CancellationRecoveryScheduler`·`CancellationRecoveryJobSettings` | provided만 |
| Simulator | domain `VanCancellation`·`VanCancelRequest`·`VanCancelEntry`·`VanCancelInquiry`·`VanCancelBehaviour`, provided `CancelAtVanUseCase`·`InquireCancelAtVanUseCase`, required `VanCancelLedgerStore`(find·insert만) | AU 관례 |

### 8.4 JPA 선택(이번 코드 → 예 → 이유 → 주의 — 원칙 §3)

- **Entity/Domain 분리 + 복원 factory**: `JpaPaymentCancellationEntity`·`JpaCancelAttemptEntity`·`JpaCancelObservationEntity`·`JpaReturnBalanceEntity`·`JpaReturnReservationEntity`는 행 모양만,
  `PaymentCancellationPersistenceMapper`·`ReturnBalancePersistenceMapper`가 `PaymentCancellation.reconstitute`(F-1)·`ReturnBalance.reconstitute`로 되돌린다. 예 — `status='COMPLETED'`인데 CANCEL 거래 행이 없거나
  잔액 열 합계와 예약 행 합계가 다르면 복원 거부. 이유 — 순수 domain + DB 이중 방어. 주의 — Entity는 data class가 아니고 toString에 기관 계약계정·운영자 사유를 넣지 않는다.
- **`@Immutable` insert-only**: `cancel_observation`, 기존 `JpaPaymentTransactionEntity`(CANCEL 열 추가). 예 — 관찰 필드를 바꿔도 UPDATE SQL이 없고 권한도 INSERT뿐(1142). 주의 — `@OneToMany(cascade)` 없음, 목록은 Repository가 따로 조회.
- **행 잠금 + `@Version` = fencing**: TXd(`beginDispatch`)와 회복(`abandonBeforeDispatch(FENCED_BY_RECOVERY)`)은 둘 다 control → … → payment_cancellation `FOR UPDATE`를 먼저 잡으므로 **실제 직렬화는 비관적 X 잠금**이고,
  `@Version`(`UPDATE … WHERE version = ?`)은 잠금 밖에서 읽은 오래된 객체 저장을 막는 두 번째 방어다(AU §7.5 n-2와 같다). Attempt·관찰만 바뀌어도 루트 version을 올린다(`PESSIMISTIC_FORCE_INCREMENT`).
- **`PESSIMISTIC_WRITE`(return_balance)**: 예 — 3,000원·8,000원 취소가 동시에 와도 control X에서 먼저 줄을 서고, 잔액 행 X·CHECK가 두 번째·세 번째 방어다. 이유 — 서로 다른 취소 행 사이 합계는 행별 `@Version`으로
  못 지킨다. 주의 — JPQL `@Lock` 조회로 이미 잠근 행을 다시 읽지 않는다(관례 §9), 기관 호출 동안 잠금 없음.
- **업무 revision vs JPA version(CN-16)**: `return_balance.revision`은 운영자 승인 대상 버전(업무 변경마다 +1), `version`은 낙관적 잠금 — 섞지 않는다. 예 — 해제(RELEASED)는 revision +1이지만 같은 TX에서 다른 열 변경이 없으면
  JPA version도 +1(flush 1회). 운영자 지문 대조는 revision만 쓴다.
- **생성 열 UNIQUE**: `in_flight_payment_id`(두 표)·`cancel_txn_key`는 Entity에 매핑하지 않는다(읽기 전용). 예 — 결함으로 두 번째 진행 중 취소 INSERT → flush 1062 → `UniqueConflict`. 주의 — flush 실패 뒤 TX는 rollback 전용.
- **flush vs commit**: TX2의 관찰·거래·Outbox를 Port 끝에서 flush해 effect_key 1062·CHECK 위반을 TX 안에서 드러내고, commit 단계 실패는 OutcomeUnknown(회복이 조회로 확인).
- OSIV off·Controller는 DTO만(기존).

### 8.5 jOOQ(CQRS)

- `JooqCancellationViewReader`(`find` — 취소 API 응답·재생의 현재 상태, `findAttempt` — 회복 후보 정보), `JooqCancellationBacklogReader`(취소 UNKNOWN 수·최고 연령·이관 미종결 수). 비권위 — 상태 변경은 JPA 잠금 재판정.
  시각은 `DATE_FORMAT` 문자열 읽기·UTC 리터럴 비교(관례 §13, TZ-FIX-001). jOOQ 쓰기 금지 ArchUnit 그대로. 거래 목록·반환 요약 조회 API는 CP-QUERY-001.

### 8.6 migration 적용 순서와 배포 조건

- `CheckoutWindowCheckMigrationStrategy` 불변: V15~V19는 "나머지 전부" 단계에 얹힌다. 시험: `OldVolumeStartSpec`(V6·V8_1·**V14 적용 볼륨**(AUTH-001 배포 상태 — AUTHORIZED Payment·승인 거래 행) → 한 번 기동에 V19까지,
  기존 승인 거래 행이 V17 CHECK 통과), `CheckoutWindowCheckMigrationStrategySpec`(버전 목록·history 15 → 20 — 추가만), `DatabaseAccountSeparationSpec`.
- **배포 조건(rev 0.2 — Coordinator 판정 §15-4·§15-8, S8에 HVC §7·이 명세 §20으로 기록)**: (1) Route 설정을 `sim-van-2`·취소 Capability·**새 profile-version**으로 바꾸므로, CANCEL-001 이전 payment-api(등록부가 `sim-van-1`만 허용)는 새 설정으로 기동하지
  못한다 → 이전 인스턴스를 모두 내린 뒤 새 인스턴스(혼재 금지, AU §20-2와 같은 형태). (2) 승인 Route profile-version 변경으로 배포 시점 진행 중 UNKNOWN 승인 Attempt는 무효과 종결 경로를 잃고 조회·이관만 한다(F-8 — fail-closed, **Coordinator 수용**).
  (2a) **LIVE Gate 조건(D-CANCEL1-003)**: 이관된 취소 UNKNOWN을 확정하는 RECON Use Case 전 LIVE 불가. (3) Simulator는
  V5·V6 적용 뒤 같은 VAN 포트에서 두 계약을 받는다 — Simulator를 먼저 올려도 PG `sim-van-1` 경로는 그대로 동작. (4) 기존 Simulator 볼륨은 권한 procedure가 이미 있으므로 재실행 불필요(V6가 procedure 호출).

---

## 9. 경합·장애 시험 계획(실제 MySQL 다중 연결 — Mock 단독 금지)

AU §8과 같은 구조: payment-api 시험은 **시험 전용 TCP stub VAN**(`StubVan`·`ProgrammableVan` 확장 — golden v2 바이트, 원장 의미 메모리 재현)을 실제 소켓으로 쓰고, 원장 내구성·경합·권한은 Simulator 시험이 실제 MySQL로 증명한다.

### 9.1 경합

| # | 경합 | 직렬화 지점 | 기대 |
|---|---|---|---|
| K1 | 같은 Payment, 서로 다른 merchantCancelId N=8 동시(금액 합 > 승인액) | control X | 정확히 1건 Started, 나머지 `CANCEL_IN_PROGRESS`(저장), 예약 1, VAN 수신 1. 완료 뒤 완료 + 예약 ≤ 승인액 |
| K2 | 같은 Idempotency-Key N=8 | 멱등 행 → control X | 취소 1·Attempt 1·**VAN 취소 요청 1**, 모두 같은 cancellationId |
| K3 | 다른 키·같은 merchantCancelId·같은 내용 N=8 | control X + UNIQUE(payment_id, merchant_cancel_id) | 취소 1, 나머지 `EXISTING_CANCELLATION`, VAN 1 |
| K4 | 같은 merchantCancelId·다른 금액 섞음 | 같음 | 승자 1, 다른 내용은 `CANCEL_REQUEST_CONFLICT` |
| K5 | 전액취소 2건 동시(다른 ID) | control X | 1건만 Started |
| K6 | 취소 TX1 vs 같은 Payment 승인 상충 결과(승인 TX2가 CONFLICTING hold 개설) | control X | hold 먼저 → 가맹점 `ORDER_ON_HOLD`; 취소 먼저 → Started, 그 뒤 hold는 진행 중 취소의 결과 반영을 막지 않음(CC-16) |
| K7 | TXd vs 회복 PREPARED fencing | payment_cancellation X + version | 한쪽만: DISPATCHING이면 VAN 1, ABANDONED면 VAN 0·FAILED(PRE_TRANSMISSION)·RELEASED |
| K8 | TX2 반영 vs 회복 lease 만료 표시 | payment_cancellation X | 어느 순서든 예외 없음(F-11), 최종 상태 하나 |
| K9 | TX2 vs 회복 조회 반영(같은 결과) | 관찰 키 | COMPLETED 1, CANCEL 거래 1, `PaymentCancelled` 1 |
| K10 | 첫 취소 두 건의 return_balance 개설 | payment X(+ UNIQUE) | 잔액 1행. 우회 INSERT는 1062 |
| K11 | DB 이중 방어 우회(root 연결) | CHECK·UNIQUE·권한 | completed + reserved > authorized 3819, 두 번째 진행 중 예약·취소 1062, 같은 취소 두 번째 CANCEL 거래 1062, approval_id 중복 1062, runtime 관찰·거래 UPDATE/DELETE 1142 |
| K12 | 운영자 보정 vs 가맹점 취소 동시(같은 Payment, hold 있음/없음) | control X | hold 있음: 가맹점 `ORDER_ON_HOLD`, 운영자 Started. hold 없음: 하나만 Started, 다른 쪽 `CANCEL_IN_PROGRESS` |
| K13 | (rev 0.3 D-CANCEL1-005로 분할) K13a 진행 중 / K13b COMPLETED / K13c FAILED — 각각 같은 상충 결정이 응답·조회·주입으로 동시 8회 + 다른 결정 1회 | control X, 관찰 UNIQUE, 결정 동일성(CN-31) | 각 행 hold 1, 사건 수 = 서로 다른 결정 수(2), 사건 이벤트 = 사건 수, 1062 없음, 상태 불변(진행 중은 UNKNOWN·RESERVED). domain 판정은 S1(`CancellationResolutionSpec` K13 행), 실 DB 동시성은 S6 |
| K14 | 회복 Worker 2개 동시 | 일정 `SKIP LOCKED` | 건마다 처리 1회 |
| K15 | (Simulator) 같은 원 승인에 다른 요청 ID 취소 동시(합 > 승인액) | `van_cancel_entry` UNIQUE(원 승인, approved_sequence) | 기관 원장 승인 취소 합 ≤ 원 승인액, 진 쪽 재판정 → `EXCEEDS_REMAINING` |
| K16 | (Simulator) 같은 취소 요청 ID의 CANCEL vs `closeIfAbsent` 동시 | UNIQUE(namespace) | 원장 1행, 진 쪽은 그 행 결과로 응답 |

### 9.2 장애 주입(`FaultInjectionConfig`·`CommitFaultingTransactionManager` 확장)

| 지점 | 기대 |
|---|---|
| TX1 각 Port flush 직후(멱등·취소·Attempt·잔액·예약·일정) | 모든 행 rollback(고아 0), VAN 수신 0 |
| TXd flush 직후·commit 불명확 | PREPARED 유지·전송 0 → 회복 fencing → FAILED(PRE_TRANSMISSION)·RELEASED·`PaymentCancelFailed` 1 |
| 기관 취소 승인 뒤 TX2 flush 직후·commit 중 | PG OPEN/UNKNOWN 유지, VAN 원장 취소 APPROVED 1 → 회복 조회 → COMPLETED 1·거래 1·`PaymentCancelled` 1·**VAN 취소 요청 1(재전송 없음)** |
| TX2 commit 불명확 | 202, 같은 키 재요청이 현재 결과로 수렴 |
| 응답 유실·지연·기록 전 끊김(프로파일 A·B) | §6.4 표대로 |
| PG 재시작(DISPATCHING 방치) | lease 만료 → UNKNOWN → 조회로 1건 확정 |
| Simulator 재시작 | 취소 원장 존속, 조회 FOUND 동일 |
| 같은 기관 요청 ID 재전송·다른 payload | 기존 결정 / REQUEST_CONFLICT, 원장 1행 |
| 취소 사건 TX에서 Outbox flush 실패 | 관찰·hold·FORCED·사건 순번 모두 rollback, 다음 반영에서 같은 결과(K13과 함께) |

### 9.3 체크카드·할부 취소 검증(D-AUTH1-003 이월 — 1-h)

{신용 일시불, 신용 할부(Demo 개월 2·3·6), 체크 일시불} × {전액 COMPLETED, 부분 COMPLETED(또는 Capability 미지원 422·VAN `PARTIAL_NOT_SUPPORTED` 이중 방어 stub 직접 요청), 부분 후 마지막 잔액,
UNKNOWN → 조회 확정, 상충(금액 불일치)} 대표 행. 각 행에서 일 한도 사용량·`authorizedPaymentId`·Payment AUTHORIZED 불변(INV-C5)과 `PaymentCancelled` payload의 원 Plan 관련 값이 원 Authorization에서 온 것임을 확인.
체크카드 할부는 승인 단계에서 거절되므로 취소 대상이 없다(확정 D-AUTH-001).

---

## 10. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거(시험·근거) |
|---|---|---|
| Financial Consistency | 적용 | 취소 확정 1건 = CANCEL 거래 1건 = `PaymentCancelled` 1건(금액·통화·원 거래 ID·정책 버전 보존 — INV-C6), 완료 + 예약 ≤ 승인액(DB CHECK·K1·K11), 부분 성공·불일치는 거래 없음(CR-10 실DB 판), 관찰·거래·VAN 취소 원장 append-only(1142·`@Immutable`), 일 한도·Payment 상태 불변(§9.3), VAN 원장 승인 취소 합 ≤ 원 승인액(K15) |
| Transaction Safety | 적용 | TX1·TXd·TX2 flush 뒤 실패 주입 rollback, 외부 호출 중 TX·잠금 없음(`CancelCallOutsideTransactionSpec` — `FOR UPDATE NOWAIT` 탐침), commit 불명확 처리, 사건 TX 원자성 |
| Idempotency | 적용 | 같은 키/다른 키·같은 merchantCancelId/다른 내용·30일 재생(현재 결과)·`CANCEL_IN_PROGRESS` 저장 재생, approvalId 영구 키, 기관 취소 요청 ID 재전송 = 기존 결정, 관찰 키 DUPLICATE, effect_key UNIQUE(사건 순번 — F-5), 재시작 뒤 재요청 |
| Concurrency Control | 적용 | K1~K16 실 MySQL, 생성 열 UNIQUE 3개 + UNIQUE(payment_id, merchant_cancel_id)·approval_id·namespace·Attempt 1, fencing, 잠금 순서 §4.7 |
| Recoverability | 적용 | 응답 유실·끊김·지연·TX2 장애·PG/Simulator 재시작 → 원 취소 요청 ID 조회로 1건 확정(재전송 0), 예산 소진 → 이관(UNKNOWN·RESERVED 유지), 무효과 종결로 늦은 원 요청 무효, 버전 불일치 시 종결 요청 없음 |
| Reconciliation | 적용(경계) | `CancellationIncidentDetected`·`CancellationOutcomeUnresolved` Outbox 원자성·effect_key, FORCED·excess 기록, CANCEL_RESULT_CONFLICT hold 해제 fail-closed 유지. Case·MATCH·해제·FORCED 출구는 RECON(N/A 이유: FINANCIAL 소유) |
| Auditability | 적용 | 개시자·운영자 요청자/승인자/approvalId/사유/목적/승인 대상 지문, 원 거래 ID·정책/권리 버전, 취소 Capability 버전(시작)·Route 프로파일 버전(전송), 관찰 evidenceHash·출처, 종료 근거, 회복 설정 버전, 멱등 결과·인과 값 |
| Observability | 적용 | counter `paycore.payment.cancel{result}`·`paycore.cancellation.dispatch{outcome}`·`paycore.cancellation.inquiry{result}`·`paycore.payment.cancellation_recovery{result}`, timer `paycore.cancellation.call.duration{operation}`(CANCEL·CANCEL_INQUIRY), gauge `paycore.cancellation.unknown`·`.unknown.oldest_age_seconds`·`.escalated`(label에 ID 금지 — 기존 `paycore.payment.incident_holds.active`가 취소 사건 hold도 센다), correlationId MDC, 로그에 카드·Secret·기관 계약계정·원문·운영자 사유 없음(캡처 시험) |

---

## 11. BOM·Kafka/Redis

- **BOM**: 새 의존성 없음. Boot 4.1.1 BOM·Kotlin 2.3.21·Hibernate·jOOQ(BOM 관리)·Modulith 그대로(`gradle/libs.versions.toml`). TCP·HMAC·JSON은 JDK·기존 Jackson. 빌드 파일 불변 계획 — 바뀌면
  GRADLE-DEPS×5·CVE-SCAN 실행. 새 실행 단위 없음 → `cve-scan.py` MODULES 변화 없음.
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 이중 취소 차단(진행 중 1건·업무 키 UNIQUE·기관 같은 ID)·반환 합계·멱등·hold의 권위는 PAYMENT DB 제약·잠금·로컬 TX이고, 기관 쪽 합계는 Simulator 원장 UNIQUE다.
  회복 Worker 다중 인스턴스도 `SKIP LOCKED`로 직렬화한다. Redis 분산 lock·Kafka 트랜잭션이 DB·가상 VAN을 아우르는 exactly-once를 만든다고 주장하지 않는다(원칙 §5).

---

## 12. 단계별 구현 계획(2단계 TDD — 각 단계 시험 먼저 → RED-C/RED-A 기록 → 최소 구현 → 핵심 규칙 변이 RED)

| 단계 | 내용 | 의존·막힘 | 시험(추정) |
|---|---|---|---|
| S0 | 질문 답·범위 판정 반영(rev 0.2 — 완료), 기준 시험 수 측정, 2단계 기록 절 개설 | 없음 | — |
| S1 | domain: `PaymentCancellation.reconstitute`(F-1) + ArchUnit 복원 규칙, `CancelAttempt.routeProfileVersion`·`beginDispatch(attemptId, version)`(F-2, D-CANCEL1-004), `cancel_capability_version` 고정, `incidentRecorded` → `incidentCount`(F-5, D-CANCEL1-002 — 사건마다 +1, 결과 `Incident(reason, incidentSeq, holdOpened)`), 늦은 포기·lease 표시 NoChange(F-11), `CancelConfirmedTerms.institutionBusinessDate`(F-12) | 없음 | 35~50 |
| S2 | V15~V19(rev 0.3 m-1 — 회복 일정 표·권한도 S2)·JPA Repository 2개·Mapper·스키마/권한/우회 시험(K10·K11)·옛 볼륨 기동·전략 회귀 | S1 | 45~60 |
| **검토 #1** | S1·S2 중간 검토(복원 거부 조합, 권한 기대값 추가, V17 CHECK가 기존 승인 행을 받는지) | — | — |
| S3 | Simulator: golden v2(harness), `VanCancellation`·조회·종결·원장 V5/V6·권한 guard·시나리오·K15·K16·재시작 | — | 45~65 + harness 2~3 |
| S4 | PG Adapter·Route 등록부(sim-van-2·cancel Capability·기동 검사)·격리 ArchUnit·stub VAN 취소(프레이밍·상관관계·MAC·timeout 종류) | S3(golden) | 30~45 |
| **검토 #2** | S3·S4 | — | — |
| S5 | 취소 API·파서·멱등(x1)·TX1(업무 키·Capability·`CancelStart`)·오류 매핑·K1~K5·K12·TX1 rollback, 운영자 보정 provided Use Case·`OperatorCancelTargetQuery`·`oct1:` golden PAYMENT 쪽, 운영자 Use Case 진입점 없음 ArchUnit(D-CANCEL1-001) | S2 | 70~95 |
| S6 | TXd·호출·TX2·Outbox(effect_key·사건 순번)·응답 유실·TX2 장애·commit 불명확·K6·K9·K13·D-AUTH1-003 행렬(§9.3) | S4, S5 | 45~60 |
| S7 | 회복(V18·V19·일정·lease·fencing·조회·예산·이관·느린 조회·버전 고정)·K7·K8·K14·PG/Simulator 재시작·지표·gauge·로그 비노출 | S6 | 35~50 |
| **검토 #3** | S5~S7 | — | — |
| S8 | 문서(관례 새 절 §17 CANCEL-001, `DOMAIN-DEFINITION`, `HARNESS-VERIFICATION-CONTRACT` §7 배포 메모(이전 payment-api 모두 중지·V15~V19·승인 profile-version 변경 영향)·하한, CC §18 구현 편차, 이 명세 §20 배포 조건·LIVE Gate 조건(D-CANCEL1-003)), `.env.example`·`LocalDemoAuthorizationConfigSpec`(sim-van-2·cancel Capability·취소 timing), 하한 갱신(실측으로만 올림), 전체 check → VERIFY-GATE 보고 | 전부 | — |

- **TDD 편차 방지**: 단계마다 RED-C(컴파일)·RED-A(실행)를 먼저 남기고, 핵심 규칙 변이 RED를 기록한다(예: 진행 중 1건 검사 제거·업무 키 조회 생략·게이트 생략·timeout을 FAILED로·NOT_FOUND로 예약 해제·
  같은 활성 hold에 사건 순번 미증가로 1062·버전 불일치에도 종결 요청·VAN 순번 UNIQUE 제거·원장 UPDATE 허용·`PaymentCancelled`에 현재 정책 버전 사용).
- migration SQL은 test-first가 아니다(AU S2 편차와 같다) — 기대값은 §8.1·§8.2에서 독립 기술하고 우회 INSERT(1062·3819)·권한(1142) 시험으로 확인.
- timeout(**Coordinator 판정 rev 0.2**): 단계 모듈 시험 30분, VERIFY-GATE 90분, 수정 반복 실패당 3회. 호스트 메모리 부족 중단은 FAIL/환경 문제로 기록(AU §16.18-6 선례).

---

## 13. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| CN-01 | 요청 본문 닫힌 3필드, 가맹점 사유 문구 v1 미수용, 주문 ID 재진술 없음 | 표시 정보 비교 안 함(CC-06), 자유 문구 저장 경로 최소화 |
| CN-02 | ~~FAILED(DECLINED)에 고정 `declineCategory` 노출(원문 없음)~~ → **정정(CP-QUERY-001 F-7)**: v1 가맹점 응답에 노출하지 않음 — 고정 분류는 Port 결과까지만(영속·응답 없음) | 구현과 일치(Coordinator 판정 2026-10-20) |
| CN-03 | 202 안내: 같은 키로 확인, 새 키·새 merchantCancelId 재시도 금지 | 이중 반환 방지(D-CANCEL-002) |
| CN-04 | 동기 대기(Demo) 후 202 | AU-21과 같다 |
| CN-05 | 결과 재생 = 취소 현재 상태(CC-05), 멱등 행은 TX2가 건드리지 않음 | D-AUTH-004 선례 |
| CN-06 | 업무 키 조회는 payment X 아래 비잠금 읽기 | 같은 Payment 쓰기는 payment X로 직렬화 |
| CN-07 | 식별자 접두 `pcn_`(취소)·`pca_`(Attempt)·`cr_`(기관 취소 요청, CC-09)·`cq_`(취소 조회)·거래는 기존 `ptx_`, 새 Port `CancellationIdentifierGenerator` | 승인 생성기 이름·계약 불변 |
| CN-08 | TXd commit 불명확이면 전송 안 함 | AU-28 m-3 |
| CN-09 | 잠금 순서 §4.7(실제 직렬화 control X) | CC CC-10 구체화, 교착 방지 |
| CN-10 | 운영자 명령은 멱등 기록 없이 approvalId 업무 키로 재요청 판정 | 운영자에게 (MID, 환경) 범위 없음, approvalId 영구 |
| CN-11 | `oct1:` 지문 정규화를 harness golden으로 고정(PAYMENT 쪽 시험 이 작업, ADMIN 쪽 ADMIN-BOOT-001) | 두 서비스 지문 일치(D-ADMIN-007 대상 버전) |
| CN-12 | 공용 `payment_transaction` 유지 + 승인 열 NULL 허용·type별 CHECK(F-3) | AU-23, D-AUTH-003 한 개념 |
| CN-13 | `PaymentCancelled` effect_key `{transactionId}` | CC m-6 결정, D-AUTH-003 축 |
| CN-14 | 사건 이벤트 effect_key `{cancellationId}:{incidentSeq}`(취소별 사건 순번, `incident_count` 영속). 발행 단위 = 사건마다(**확정 D-CANCEL1-002**) | F-5 1062 반복 방지 |
| CN-15 | 취소 사건 hold의 출처 열 NULL 유지, 관찰 키는 이벤트 payload | F-10, 승인 설명 집합과 분리 |
| CN-16 | `return_balance.revision`(업무)·`version`(JPA) 별도 열 | CC §17.3-6 |
| CN-17 | 잔액 합계 열 = 예약 행 합계(Mapper 계산, 복원 시 불일치 거부) | DB CHECK 대상 + 조용한 수정 금지 |
| CN-18 | 회복 일정을 TX1에서 생성 | AU D-S6-2 |
| CN-19 | 기관 취소 업무일 nullable 보존(의미는 Q-CALENDAR) | F-12, 승인 m-2와 같은 모양 |
| CN-20 | 계약 `sim-van-2` = sim-van-1 + 취소 4종, sim-van-1 golden 불변(**Coordinator 채택 rev 0.2**) | 검토된 계약 변경만 재생성 |
| CN-21 | VAN 취소 원장 새 표 `van_cancel_entry` | F-6, 원장 CHECK 단순성 |
| CN-22 | VAN 잔액 직렬화 = 승인 취소 순번 UNIQUE + 재판정 | F-7, 원장 append-only·UPDATE 권한 없음 |
| CN-23 | Simulator 취소 PENDING 시나리오 없음(PG는 stub으로 PENDING 처리 시험) | 새 예약 표 회피, PENDING = UNKNOWN 유지 |
| CN-24 | 회복은 AUTH-001 설계 복제(별도 표·서비스·설정·trigger) — **Coordinator 채택 rev 0.2(선택 A)** | F-4, DONE 경로 회귀 회피 |
| CN-25 | 취소 시나리오는 원 승인 원장 `card_ref`의 새 가상 카드로 | AU-12 결정 가능 방식 |
| CN-26 | `van_inquiry_log.operation` 열 추가(NULL = 이전 승인 조회) | 조회 로그 구분, 기존 행 보존 |
| CN-27 | 같은 기관 요청 ID 재전송 회복은 구현하지 않음(조회만) | CC §4.7 예외는 허용일 뿐 — 보수 쪽, AUTH-001과 같음. D-CANCEL1-003(임시 출구 없음)과 일치 |
| CN-28 | Route 설정에 `protocol-version` 집합·`capabilities.cancel.*` 블록, 바꾸면 profile-version 변경 | F-8, D-AUTH1-007 §20-6 |
| CN-30 | ~~반복 거절 한 행(UNIQUE)~~ — **폐기(D-CANCEL1-008)**: 거절 한 번 = 감사 행 하나, 중복 제거 없음. Store `append`. payment_id FK 없음(없는 Payment 명령의 거절도 남김) | 사용자 결정 |
| CN-31 | (rev 0.3, D-CANCEL1-005 → **D-CANCEL1-007로 개정**) "같은 기관 결정" = 같은 Attempt ∧ 같은 결과 ∧ 확정 조건 전부 같음(금액·통화·원 승인 PTX·PTX·취소 승인번호·기관 거래일·업무일 — **무시하는 필드 없음**) ∧ (같은 결정 ID ∨ 승인·같은 PTX). PTX만 같고 조건이 다르면 새 사건(fail-closed, CC §4.6). 사건이 이미 있는 진행 중 취소에서는 확정 결과(승인·거절·무효과)만 새 사건이고 PENDING·UNKNOWN·NOT_FOUND는 아니다 | D-CANCEL1-005·007 |
| CN-29 | 취소 timing 설정 `paycore.cancellation.timing.*`(필수 version, 기동 일관성 검사 AU-34·m-2와 같은 식) | D-AUTH-009, Auditability |
| CN-32 | (검토 #3 n-3) 운영자 같은 요청 판정 = 같은 approvalId ∧ 같은 Payment ∧ 같은 금액 ∧ **같은 요청자·승인자**. 요청자·승인자가 다르면 `APPROVAL_IDENTITY_MISMATCH` 거절 + 감사 행(기존 결과를 돌려주지 않는다 — fail-closed). 판정은 비잠금 읽기(`PaymentCancellationRepository.find`) | 2인 승인 신원 위조·재사용 방지, 가역(Reviewer 확인 요청) |

---

## 14. 질문 후보(Coordinator → 사용자) — 답변됨(rev 0.2)

### 14.0 답변 대응표

| 질문 | 결정 | 답변 요지 | 반영 |
|---|---|---|---|
| Q-CANCEL1-01 | **D-CANCEL1-001** | 권장안: Use Case + PAYMENT 재검사만, inbound 진입점 없음(ADMIN-BOOT-001이 서비스 간 인증과 함께 연다), ADMIN 승인 상태 동기 조회 없음 | §0, §2 AC 2·3, §5.2·§5.3, §12 S5 |
| Q-CANCEL1-02 | **D-CANCEL1-002** | 권장안: 사건마다 `CancellationIncidentDetected:{cancellationId}:{incidentSeq}`, `incident_count` 영속, 활성 hold면 새 hold 없음 | §0, F-5, §4.10, §8.1, §12 S1, CN-14 |
| Q-CANCEL1-03 | **D-CANCEL1-003** | 권장안: 이관된 취소 UNKNOWN 임시 출구 없음(fail-closed), LIVE Gate 조건으로 기록 | §0, §7.1, §8.6, §12 S8, §16 R7 |
| Q-CANCEL1-04(rev 0.1 §15-5를 질문으로) | **D-CANCEL1-004** | 권장안: 취소 Attempt에 Route 프로파일 버전 고정, 같을 때만 종결 요청, 다르거나 NULL이면 조회·이관만 | §0, F-2, §7.1, §8.1, §12 S1 |

아래 원문(rev 0.1)은 질문 당시 기록이다. "권장안"·"막히는 작업"은 위 결정으로 해소됐다.

### Q-CANCEL1-01 운영자 보정 취소의 진입점과 승인 확인 방식(ADMIN-BOOT-001 이전) — 답변됨(D-CANCEL1-001)

- **배경**: D-CANCEL-005·D-ADMIN-007은 "ADMIN이 승인 요청·상태를 관리하고 소유 서비스가 요청자·승인자·승인 ID·대상 버전을 받아 다시 검사"를 정했다. 그러나 (a) 이 명령을 누가 어떤 인증으로 부르는지,
  (b) PAYMENT가 그 승인이 ADMIN에 실제로 존재·유효한지 되묻는지는 정하지 않았다. ADMIN-BOOT-001(계정·역할·2인 승인 틀·서비스 간 인증)은 agreedOrder상 CANCEL-001 뒤라 지금은 인증된 호출자가 없다(F-14).
- **권장안**: **CANCEL-001은 운영자 보정 취소의 provided Use Case·domain·영속·회복 공용 경로와 PAYMENT 쪽 재검사(요청자≠승인자·사유·근거·승인 대상 지문·approvalId 영구 유일)까지만 구현하고, 어떤 inbound 진입점
  (HTTP·내부 포트)도 열지 않는다.** 진입점은 ADMIN-BOOT-001이 서비스 간 인증과 함께 연다. 그때 PAYMENT는 **승인 상태를 ADMIN에 되묻지 않고**(콜백 없음) "인증된 ADMIN 호출자 + 자기 재검사 + approvalId UNIQUE"로 판정한다.
  이유 — 인증 없는 진입점은 2인 승인 없이 자금 반환을 실행할 수 있는 경로가 되고, 동기 콜백은 ADMIN 장애를 취소 경로에 전파한다(ADMIN-BOOT-001 AC "관리 조회 장애가 승인 경로로 전파되지 않음"). D-ADMIN-007 문언(소유
  서비스가 받은 값으로 재검사)과 맞는다.
- **대안**: (b) 이 작업에서 내부 포트에 HMAC 서명 진입점(권리 Inbox와 같은 방식, 시험 키)을 연다 — 시험·Demo로 종단 경로를 일찍 검증하지만, 서명 키 보유자가 ADMIN 2인 승인 없이 호출할 수 있다(키 = 권한). (c) 진입점은
  ADMIN-BOOT-001에서 열되 PAYMENT가 실행 직전 ADMIN 승인 상태를 동기 조회(콜백) — 승인 위조에 강하지만 ADMIN 가용성이 취소 실행 조건이 된다.
- **영향**: AC 2의 완료 의미(Use Case·경계 문서까지), ADMIN-BOOT-001 AC 추가(§15-2), D-CANCEL-010·013 보정 경로의 운영 실행 시점(어차피 RECON 뒤), 보안 경계(내부 API 인증 방식).
- **막히는 작업**: 운영자 진입점 adapter만(권장안이면 만들 것이 없음). S5의 Use Case·재검사·시험은 진행 가능.

### Q-CANCEL1-02 같은 취소에 두 번째 상충 결과가 올 때의 사건 이벤트(대사 Case) 발행 — 답변됨(D-CANCEL1-002)

- **배경**: D-CANCEL-006은 취소 상충·늦은 성공에 "관측 기록 + hold + `CancellationIncidentDetected`"를 정했다. 승인 쪽은 AU-17(Coordinator 채택)로 "새 hold가 append됐을 때만 사건 이벤트"다. 취소에서 같은 Payment에 이미
  CANCEL_RESULT_CONFLICT hold가 활성이면(해제는 RECON 전까지 불가 — 항상 활성) 새 hold는 생기지 않는다. 그래서 AU-17 규칙을 그대로 쓰면 **두 번째 취소 operation(운영자 보정)의 상충**이나 **같은 취소의 두 번째 상충(다른 PTX 성공 등)**은
  이벤트 없이 관찰로만 남아 RECON이 새 Case를 받지 못한다. 반대로 매번 발행하면 effect_key를 사건마다 다르게 만들어야 한다(F-5).
- **권장안**: **취소 operation별 사건 순번으로 매 사건 발행**(`CancellationIncidentDetected:{cancellationId}:{incidentSeq}`, payload에 새 hold 여부·hold occurrence·관찰 키). hold는 이미 활성이면 새로 열지 않는다(기존 domain).
  이유 — D-CANCEL-006 문언(사건마다 이벤트), 실제 반환이 있었을 수 있는 금액이 사건마다 다를 수 있다(FORCED·excess), 같은 관찰 재반영은 DUPLICATE라 중복 발행이 없다.
- **대안**: (b) AU-17과 같이 새 hold일 때만 발행 — 이벤트 수 최소, 이후 사건은 RECON이 관찰 조회로 찾아야 한다. (c) 취소 operation별 첫 사건만 발행 — 다른 취소의 사건은 보이고, 같은 취소의 추가 사건은 관찰로만.
- **영향**: RECON Case 수와 위험 등급 판정 입력, 이벤트 payload 계약, `incident_count` 열(권장·c), 시험 K13 기대값.
- **막히는 작업**: S1 사건 판정 값·S6 Outbox 발행 조건만. 다른 단계는 진행 가능(1062 방지 키 구조는 어느 안이든 같다).

### Q-CANCEL1-03 RECON 이전에 오래 풀리지 않는 취소 UNKNOWN(이관 뒤)의 운영 출구 — 답변됨(D-CANCEL1-003)

- **배경**: 확정 규칙상 이관된 취소 UNKNOWN은 예약 유지·자동 FAILED 금지이고(K-14, D-AUTH-009), 확정 전까지 같은 Payment의 운영자 보정을 포함한 모든 다음 취소를 막는다(D-CANCEL-003, CC §14.1 m-3). 이를 대사 근거로 확정하는
  Use Case는 RECON-CONTRACT-001·RECON-001이며 agreedOrder상 마지막이다. 무효과 확정 Capability가 없는 기관(프로파일 B)에서 기록 전 끊김이 나면 그 Payment는 RECON 전까지 반환 경로가 없다(고객 반환 지연). 지금 정하지 않은 것은
  RECON 전 임시 출구를 둘지다.
- **권장안**: **CANCEL-001은 임시 출구를 만들지 않는다**(fail-closed — 예약 유지·차단·이관 이벤트·연령 gauge·경보). 대신 "이관된 취소 UNKNOWN을 대사 근거로 확정하는 RECON Use Case가 있기 전에는 LIVE 허용 불가"를 LIVE 준비 Gate
  조건으로 기록한다. 이유 — 출구는 결과를 판정하는 금융 행위라 Recovery/Adjustment 구분(CLAUDE.md)과 2인·재대사 MATCH 규칙을 갖춘 RECON 계약이 먼저다. 현재 영향은 TEST(가상 기관) 한정이다.
- **대안**: (b) 운영자가 2인 승인으로 기관 근거 참조를 붙여 취소 UNKNOWN을 COMPLETED/FAILED로 확정하는 임시 Use Case를 이 작업에 추가(RECON 계약 전 Adjustment 성격 — 규칙 선점 위험). (c) 기관 Capability `sameIdResendSafe`가 있으면 같은 요청 ID 재전송으로
  결과를 끌어내는 회복을 추가(CC §4.7 예외 — 가상 기관에서만 의미, 실제 기관 규약 확인 전).
- **영향**: LIVE 준비 Gate 조건, RECON-CONTRACT-001 AC(취소 UNKNOWN 확정 Use Case 명시), 고객 반환 지연 위험의 운영 안내.
- **막히는 작업**: 없음(권장안이면 만들 것이 없다). 대안 b·c를 고르면 S7 뒤 별도 단계.

### Q-CANCEL1-04 취소 무효과 종결 요청의 Route 프로파일 버전 고정(D-AUTH1-007의 취소 확장) — 답변됨(D-CANCEL1-004)

- rev 0.1에서는 F-2·§15-5에 "도출(D-AUTH1-007 동형)"로 적었고 Coordinator가 질문으로 올렸다.
- **권장안(채택)**: 취소 Attempt에 전송 때 Route 프로파일 버전을 고정하고, 회복은 고정 버전 = 현재 버전일 때만 `closeIfAbsent`. 다르거나 NULL이면 조회·이관만(fail-closed).
- **대안**: (b) 현재 설정으로 판정 + 배포 조건 "UNKNOWN 취소가 있는 동안 Capability 변경 금지". (c) 등록부가 버전별 프로파일 보관.
- **영향**: 무효과 확정(예약 해제 근거)의 출처 정합성·Auditability, V15 `cancel_attempt.route_profile_version` 열.

### 14.3 검토했으나 질문으로 올리지 않은 것

| 항목 | 처리 근거 |
|---|---|
| 금액·통화(취소 통화 = 원 승인 통화, minor unit, 승인 KRW만) | 확정 CC §5.2, D-AUTH1-002 — USD 취소는 domain 행(CB-04)만, API는 승인이 KRW뿐이라 `CURRENCY_MISMATCH` 행 |
| 취소 가능 상태·UNKNOWN 승인 중 취소 | 확정 CC §4.8, N-2(Coordinator 도출 채택) — `PAYMENT_NOT_CANCELLABLE` |
| hold 중 취소·운영자 보정 | 확정 D-CANCEL-001·005·006·011·013 |
| 진행 중 1건·업무 키·FAILED 뒤 같은 ID | 확정 D-CANCEL-002·003 |
| 부분취소 규칙(할부 부분취소·최소 금액·횟수) | 도출 CC §4.2·N-1 — 기관 Capability(Demo). PG 자체 규칙 없음 |
| PG 취소 기한 | 확정 D-CANCEL-004(기관 거절 → FAILED) |
| 계좌환불 | 확정 D-CANCEL-007 |
| 기관 잔액 부족 거절 | 확정 CC §4.6 "기관 확정 거절(예: 기한 경과·기관 잔액 부족) → FAILED" — 차이는 RECON |
| 정산 거래일·시간대 | 확정 K-8, 미결은 기존 Q-CALENDAR(새 질문 아님) |
| 수수료/VAT 반환 | 확정 D-CANCEL-008·009 — PAYMENT는 payload 사실만(계산 SETTLEMENT) |
| 취소 무효과 종결의 Route 버전 고정 | (rev 0.2) 질문으로 올려 답변됨 — Q-CANCEL1-04 → D-CANCEL1-004 |
| 같은 기관 ID 재전송 회복 | CN-27(허용 예외를 쓰지 않는 보수 쪽) |
| 열린 질문(Q-CALENDAR·Q-CORRECTION·Q-SETTLE-CURRENCY·Q-BATCH-*·Q-MD-06/07/18·Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF) | 이 작업을 막지 않음. Q-ADMIN-APPROVER-STAFF는 운영자 보정 실행 가능성에 영향(ADMIN-BOOT-001) |

---

## 15. 범위 관찰(Coordinator 판정)

**Coordinator 판정(rev 0.2, 2026-10-20)**: (1) tasks.json AC 1 "D-CANCEL-001~013"으로 정정 + D-CANCEL1 AC 행 추가. (2) ADMIN-BOOT-001 인계 AC 추가(제안대로). (3) 회복 선택 A(CN-24) 채택, F-4 기록.
(4) `sim-van-2`(CN-20) 채택. 배포 때 승인 Route profile-version 변경으로 진행 중 승인 UNKNOWN이 무효과 종결 경로를 잃는 것은 fail-closed로 수용 — §20(S8)·§8.6 기록. (5) D-CANCEL1-004로 해소.
(6) 취소 GET 없음 — 이력·반환 요약은 CP-QUERY-001, 취소 이벤트와 운영자 보정 취소의 가맹점 통지 여부는 WEBHOOK-001. (7) RECON 인계 (a)~(e)를 RECON-CONTRACT-001 AC에 추가.
(8) 배포 전제(이전 payment-api 모두 중지)는 S8에 HVC §7·이 명세 §20. (9) allowedWritePaths 불변. (10) timeout 단계 모듈 시험 30분·VERIFY-GATE 90분·수정 실패당 3회.
(11) `.env.example`·`LocalDemoAuthorizationConfigSpec`은 S8. 아래 rev 0.1 원문은 기록으로 둔다.

1. **AC 문구**: CANCEL-001 AC 1의 "D-CANCEL-001~007"을 "D-CANCEL-001~013"으로 읽는다(D-CANCEL-008~013의 PAYMENT 의무 — F-9). tasks.json 정정은 Coordinator 소유.
2. **AC 2 경계와 ADMIN-BOOT-001 인계(Q-CANCEL1-01 권장안 기준)**: ADMIN-BOOT-001 AC에 추가 제안 — 운영자 보정 취소 내부 진입점 + 서비스 간 인증, `OperatorCancelTargetQuery` 내부 조회, `oct1:` 지문 golden 대조(ADMIN 쪽),
   승인 대상 = 실행 시점 revision(대상 변경 시 승인 무효 — D-ADMIN-003), APPROVER·TOTP 재입력, Q-ADMIN-APPROVER-STAFF.
3. **회복 재사용 방식**: CN-24(별도 표·서비스 복제) 채택 여부.
4. **VAN 계약 버전과 profile-version 변경**: `sim-van-2`(CN-20) 채택, 배포 시 UNKNOWN 승인 Attempt가 무효과 종결 요청을 잃는 결과(F-8 — fail-closed) 수용 여부.
5. **D-AUTH1-007의 취소 확장**: 취소 Attempt Route 버전 고정·버전 일치 때만 종결 요청(F-2)을 도출로 수용하는지, 사용자 질문으로 올릴지.
6. **조회·Webhook**: 이 작업은 취소 조회 GET을 만들지 않는다(202 뒤 확인은 같은 키 재요청). CP-QUERY-001에 반환 요약·취소 이력·취소 단건 조회, WEBHOOK-001에 `PaymentCancelled`·`PaymentCancelFailed`(운영자 보정 취소도 가맹점에 알릴지 포함) 인계.
7. **RECON 인계(추가)**: (a) 이관된 취소 UNKNOWN 확정 Use Case(Q-CANCEL1-03), (b) 사건 처리된 진행 중 취소 출구(CC §14.1 m-3), (c) CANCEL_RESULT_CONFLICT 해제·FORCED 출구(CC m-1), (d) P1 원 경로 판정이 RESOLVED 승인 Attempt를
   요구함(F-13) — 늦은 승인 기록 Use Case가 Attempt 상태를 그 전제에 맞추거나 domain 판정을 바꿔야 함, (e) 취소 사건 이벤트 payload 계약(Q-CANCEL1-02).
8. **배포 조건**(§8.6): CANCEL-001 이전 payment-api 모두 내린 뒤 새 인스턴스(Route 설정 `sim-van-2` 때문에 이전 인스턴스 기동 불가), V15~V19 — HVC §7·이 명세 §20(S8)에 기록.
9. **허용 경로**: tasks.json allowedWritePaths로 충분(`apps/payment-api/**`·`apps/institution-simulator/**`·`docs/repository/**`·`harness/commands.json`·`harness/tests/**`(golden v2 생성기)·`docker/**`·`.env.example`). `docker/simulator-mysql/init` 변경은 필요 없을 것으로 본다(procedure 기존).
10. **timeout**: 단계 모듈 시험 30분·VERIFY-GATE 90분(AUTH-001과 같은 값) 제안 — tasks.json `taskTimeoutSeconds`가 null.
11. **`.env.example`**: `PAYCORE_PAYMENT_AUTHORIZATION_JSON` Route 예시를 `sim-van-2`·cancel Capability·새 profile-version으로, 취소 timing 설정 예시 추가(AU §16.18-2 선례) — `LocalDemoAuthorizationConfigSpec` 확장.

---

## 16. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 규모(새 표 6·ALTER 3·Simulator 새 표·Use Case 6·회복 복제) — 단일 gate 실패 시 재작업 큼 | 검토 #1~#3, 단계별 모듈 시험 |
| R2 | V17 `payment_transaction` MODIFY가 기존 승인 행·CHECK와 충돌 | V14 적용 볼륨 기동 시험, 한 ALTER에서 CHECK DROP+ADD, 기존 행 CHECK 통과 확인 |
| R3 | 회복 복제 코드가 승인 회복과 어긋나며 진화 | 판정 표(§7.2)를 공유 시험 표로, 공통 순수 helper만 추출 검토(DONE 경로 동작 불변) |
| R4 | TCP 시험 시간 의존 | AU R3과 같다(가상 시계·latch·재시도로 통과시키지 않음) |
| R5 | stub VAN과 Simulator 취소 의미 분기 | golden v2 벡터, 기관 잔액·순번은 Simulator 실DB 시험(AU R7과 같은 한계 기록) |
| R6 | profile-version 변경으로 배포 시 UNKNOWN 승인이 종결 경로를 잃음 | fail-closed(조회·이관 계속) — Coordinator 수용(§15-4), §20(S8) 기록 |
| R7 | 이관된 취소 UNKNOWN이 RECON 전까지 반환을 막음 | D-CANCEL1-003(출구 없음), gauge·경보, LIVE Gate 조건 |
| R8 | 운영자 보정이 진입점 없이 끝나 "구현됨"으로 오해 | D-CANCEL1-001, AC 2 완료 의미를 §5.3에 명시, 진입점 없음 ArchUnit |
| R10 | 사건마다 이벤트(D-CANCEL1-002)인데 같은 관찰 재반영이나 **같은 결정의 다른 출처 재수신**이 순번을 올리면 중복 Case | DUPLICATE와 출처 간 같은 결정(CN-31) 모두 `conflict()`에 도달하지 않음을 시험(K13a~c)·변이 RED로 고정(D-CANCEL1-005) |
| R9 | 호스트 메모리 부족으로 전체 check 중단(AU 선례) | 모듈 단위 실행 기록, 중단은 FAIL/환경으로 기록, VERIFY-GATE로 최종 확인 |

---

## 17. 규모 추정

- 기준: AUTH-001(payment-api +~313건, Simulator +77건, harness +5, 새 표 8·ALTER 3·Simulator 새 모듈, 2026-10-18~19 2일·검토 3회+최종 2회).
- CANCEL-001: TCP 프레이밍·Adapter 골격·회복 패턴·시험 대역을 재사용하지만 새 표가 비슷하게 많고(PAYMENT 6·ALTER 3, Simulator 1) Simulator 취소 판정·잔액 경합이 새로 생긴다. **AUTH-001의 약 0.8~1.0배**.
- 시험 수 추정: payment-api +250~360, Simulator +45~65, harness +2~4(golden v2·`oct1:` golden). GRADLE-CHECK 하한 1875 → 실측(약 2,170~2,300), HARNESS-SELFTEST 86 → 실측(약 88~90). 하한은 실측으로만 올린다.
- 일정 감(구현자 1 + 검토 3회 기준): S1~S2 0.5일, S3~S4 0.5일, S5~S7 1일, S8·gate·최종 검토 0.5일 — 약 2.5~3일. 질문 답 지연은 S1(사건 발행 판정)·S5(진입점)의 일부만 막는다.

---

## 18. 2단계 실행 기록(구현 중 갱신 — 구현자 보고, Verifier/Reviewer 판정 아님)

로그는 WSL `~/gate-logs/`(저장소 밖). 시각은 UTC. Gradle은 모두 `--no-daemon --console=plain`, 한 번에 하나, wsl.exe 전경 실행(Coordinator 지시).

### 18.1 S0 — 기준

- 기준 commit `e2fdf35`(AUTH-001 DONE). 기준 시험 수는 다시 돌리지 않고 AUTH-001 VERIFY-GATE 증거 `build/harness-evidence/AUTH-001-2026-10-19T150655Z.json`(PASS, TEST-SUMMARY **1,875건** 실패·오류·skip 0, HARNESS-SELFTEST
  **86**)과 작업 전 `apps/payment-api/build/test-results` XML 121파일 **1,281건**(실패 0)을 기준으로 삼는다. 작업 트리의 Coordinator 상태 파일 변경은 읽기만 했다.

### 18.2 S1 — domain(F-1·F-2·F-5·F-11·F-12)

| 항목 | 변경 | 근거 |
|---|---|---|
| F-1 | `PaymentCancellation.reconstitute`(Attempt 1개·상태별 Attempt·원인·버전 짝·거래·근거·관찰·키 상충·사건 수·운영자 2인 입력 검사) + ArchUnit `paymentCancellationRestoredOnlyByPersistence` + 위반 fixture `archfixture.cancelstart.DirectCancellationReconstitute` | CC §14.1 nit 4 |
| F-2 | `CancelAttempt.routeProfileVersion`(PREPARED는 null, 1..64 printable), `PaymentCancellation.beginDispatch(attemptId, routeProfileVersion)`, `PaymentCancellation.capabilityVersion`(`CancelStart`가 판정한 Capability 버전, `open(..., capabilityVersion, ...)`) | D-CANCEL1-004 |
| F-5 | `incidentRecorded: Boolean` → `incidentCount: Int`(`incidentRecorded`는 계산 속성으로 유지), `recordIncident()`가 순번 반환, `CancelResolutionOutcome.Incident(reason, incidentSeq, holdOpened)` | D-CANCEL1-002 — **rev 0.3 H-1로 보류(HELD)·잠정 구현** |
| F-11 | `CancellationResolution.abandonBeforeDispatch`: 포기·결론난 Attempt, OPEN 아닌 취소, OUTCOME_UNKNOWN의 connect 실패 → NoChange(반환형 `CancelResolutionOutcome`). `markOutcomeUnknown`: RESOLVED·ABANDONED·종료 취소 → NoChange. PREPARED connect 실패·DISPATCHING fencing·PREPARED lease 표시는 결함 그대로 | AUTH AU-25·m-2 동형(도출) |
| F-12 | `CancelConfirmedTerms.institutionBusinessDate: LocalDate? = null`(보존만, 기관 내용 비교에 포함) | CN-19 |

- **RED-C**(2026-10-19T19:53:09Z, `s1-redc-20261019T195309Z.log`): 시험(새 `PaymentCancellationReconstitutionSpec`, 기존 Spec 행 추가, fixture·ArchUnit)을 main보다 먼저 → `compileTestKotlin` **exit 1, `e:` 69건**.
- **GREEN**(19:56:10Z, `s1-green-20261019T195610Z.log`): `:apps:payment-api:test --tests …*Cancel*·ReturnBalanceSpec·PaymentTransactionSpec·ArchitectureSpec` **exit 0** — XML 7파일 **156건**, 실패·오류·skip 0
  (ArchitectureSpec 40, CancelStartSpec 31, CancellationResolutionSpec 29(+6), PaymentCancellationReconstitutionSpec 9(새), PaymentCancellationSpec 23(+3), PaymentTransactionSpec 5, ReturnBalanceSpec 19).
- **RED-A**: 없음 — 행동 행은 컴파일 RED 직후 main과 함께 녹색(편차, AUTH·CC 선례와 같음). 대신 **변이 RED**(`/tmp/mut_s1.py`: main 한 곳 변이 → 대상 Spec → 원복·내용 일치 확인, `s1-mut-20261019T195743Z.log`):

| 변이 | 대상 | 결과 |
|---|---|---|
| M1 lease 표시 NoChange 가드 제거 | CancellationResolutionSpec | exit 1, 29건 중 1 실패 |
| M2 connect 실패(OUTCOME_UNKNOWN) NoChange 가드 제거 | CancellationResolutionSpec | **생존** — 동등 변이: OUTCOME_UNKNOWN Attempt는 항상 UNKNOWN 취소에 있어 앞의 `status != OPEN` 가드가 같은 경우를 먼저 잡는다(AUTH와 같은 모양 유지 위해 가드는 남김 — 판단은 Reviewer) |
| M3 사건 순번 증가 안 함 | CancellationResolutionSpec | exit 1, 2 실패 |
| M4 holdOpened 항상 true | CancellationResolutionSpec | exit 1, 3 실패 |
| M5 복원: 보낸 Attempt의 버전 짝 검사 제거 | PaymentCancellationReconstitutionSpec | exit 1, 9건 중 1 실패 |
| M6 복원: UNKNOWN + PREPARED에 사건 요구 제거 | 같음 | exit 1, 1 실패 |
| M7 복원: 거래 출처 관찰 검사 제거 | 같음 | exit 1, 1 실패 |
| M8 CancelStart가 Capability 버전을 기록하지 않음 | PaymentCancellationSpec | exit 1, 23건 중 1 실패 |
| M9 beginDispatch가 버전을 고정하지 않음 | 같음 | exit 1, 1 실패 |

- **바뀐 기존 기대값(Reviewer 확인 대상)**:
  1. `CancellationResolutionSpec` 사건 기대 10곳: `Incident(CANCEL_RESULT_CONFLICT)` → `Incident(CANCEL_RESULT_CONFLICT, incidentSeq = n, holdOpened = …)` — 결과 타입 확장으로 **값을 더 좁힌 것**(같은 사유 + 순번·hold 여부). CR-11의 두 번째(다른 출처) 늦은 성공은 `incidentSeq = 2, holdOpened = false`(D-CANCEL1-002 "사건마다, 활성 hold면 새 hold 없음"). H-1 답에 따라 다시 바뀔 수 있음(보류).
  2. `CancellationResolutionSpec` CR-05 끝 행: UNKNOWN 취소에 늦게 온 connect 실패 보고 — 전: `InvalidCancellationTransitionException` / 후: `NoChange` + 상태 UNKNOWN·종료 근거 없음·예약 RESERVED 단언 추가. 이유: F-11(AUTH AU-25·m-2와 같은 경합 규칙 — 회복 lease 표시 뒤의 늦은 보고는 미송신을 증명하지 못하므로 아무것도 바꾸지 않는다). 금융 효과(예약 해제·FAILED)를 허용하는 방향이 아니다 — 두 경우 모두 상태 불변이고, 결함(예외) 분류만 경합 정상 경로(NoChange)로 바뀌었다.
  3. fixture·호출부: `beginDispatch(attemptId)` → `beginDispatch(attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)`(준비 인자), `CancellationFixture.lateCancelSuccessAfterDecline` 확인식 `out == Incident(..)` → `out is Incident && out.holdOpened`(같은 의미).
  4. archfixture `DirectCancellationOpen`·`DirectCancellationTransitions`: 새 인자(위반 대상 불변).

### 18.2b S1 보강 — D-CANCEL1-005(사건 동일성, rev 0.3)

- 변경: `PaymentCancellation.repeatsRecordedDecision`(CN-31), `CancellationResolution` — 기록 전에 같은 결정 여부를 보고, 키 상충·COMPLETED·FAILED·PREPARED Attempt·사건 있는 진행 중 취소의 사건 판정에서 같은 결정은 NoChange,
  사건 있는 진행 중 취소의 새 확정 결과는 `conflict()`(+1, 상태 불변).
- **편차**: 이 보강은 main을 먼저 고친 뒤 시험을 썼다(Coordinator 전달이 구현 도중 도착 — test-first 아님). 그래서 아래 변이 RED로 시험이 규칙을 실제로 잡는지 확인했다.
- 시험: `CancellationResolutionSpec`에 K13a(진행 중)·K13b(COMPLETED)·K13c(FAILED) 행 — 각 행에 응답·조회·주입(대사) 출처 간 같은 결정 행 포함.
- **GREEN**(20:46:07Z, `s1b-green-20261019T204607Z.log`): 같은 대상 **exit 0**, 7파일 **158건**(CancellationResolutionSpec 29 → 31), 실패·오류·skip 0.
- **변이 RED**(`/tmp/mut_s1b.py`, `s1-mut-20261019T204838Z.log`): M10 같은 결정 판정 항상 false(출처 간 재수신을 셈) → 31건 중 5 실패, M11 사건 있는 진행 중 취소가 새 확정 결과를 무시(D-CANCEL1-005 이전 동작) → 1 실패,
  M12 PTX 같은 다른 결정 ID를 다른 결정으로 봄 → 4 실패. 원복·내용 일치 확인.
- **바뀐 기존 기대값(Reviewer 확인 대상)**:
  1. `CancellationResolutionSpec` CR-11: 같은 늦은 성공(같은 PTX)이 대사(RECONCILIATION "rec-1")로 다시 온 행 — 전(S1 잠정): `Incident(seq 2, holdOpened false)` / 후: `NoChange` + 사건 수 1·hold 1·FORCED 3,000 단언. 이유: **D-CANCEL1-005**(같은 기관 결정은 같은 사건). hold·FORCED 기대는 그대로(완화 없음).
  2. `PaymentCancellationReconstitutionSpec` 사건 수 2 왕복 준비: 두 번째 늦은 성공을 다른 PTX(`confirmedTerms(…, n = 9)`)로 — 같은 PTX면 D-CANCEL1-005로 사건이 아니다. 기대값(사건 수 2 왕복) 그대로.
  3. S1 잠정 K13 행(사건 1 → 거절 NoChange → 키 상충 2)은 위 K13a~c로 교체(H-1 보류 동안의 잠정 행이었다).

### 18.2c 중간 검토 #1(CHANGES_REQUIRED) 대응 — D-CANCEL1-007·008, H-1·m-2·m-3·n-1~n-5

Coordinator 전체 `:apps:payment-api:check`(S2 트리): BUILD SUCCESSFUL, **1,314건** 실패·오류·skip 0(Coordinator 실행 — 구현자 실행 아님).

| 지적 | 대응 | 시험 |
|---|---|---|
| H-1 / **D-CANCEL1-007** | `repeatsRecordedDecision` = 같은 Attempt ∧ 같은 결과 ∧ **확정 조건 전부 같음**(금액·통화·원 승인 PTX·PTX·취소 승인번호·기관 거래일·업무일 — 무시하는 필드 없음, CN-31 개정) ∧ (같은 결정 ID ∨ 승인·같은 PTX). PTX만 같고 조건이 다르면 새 사건 | `CancellationResolutionSpec` H-1 4행(사건 있는 진행 중·COMPLETED·FAILED·KEY_CONFLICT — 금액·원 승인 PTX 변형, hold·순번 단언), Fixture `CancellationFixture.samePtxDifferentTerms` |
| **D-CANCEL1-008** | 거절 한 번 = 감사 행 하나: V15 `operator_cancel_rejection`의 UNIQUE(approval_id, payment_id, rejection_reason) 제거(**미배포·미커밋 상태 제자리 수정** — §20 개발 볼륨 메모), 색인 (approval_id, id), Store `recordOnce` → `append`, `StoreConstraint.OPERATOR_CANCEL_REJECTION` 삭제. CN-30 폐기 | `CancellationPersistenceSpec`: 같은 승인·같은 사유 두 번 + 다른 사유 → 3행(기록 순서), 열 집합, 자유 문구 3819, runtime UPDATE/DELETE 1142 |
| m-2 | UNIQUE가 없어 1062 경합은 사라졌다. 값 타입 상한: `OperatorId`·`OperatorApprovalId`는 기존 `requireIdentifier`(≤ 64·올바른 UTF-16), **`PaymentId`에 ≤ 64·올바른 UTF-16 추가**(전에는 빈 값만 거부) — 감사 행에 1406·1366이 갈 수 없다 | `CancelStartSpec` 값 타입 행(65자·깨진 서로게이트·빈 값 거부, 64자 허용) |
| m-3 / F-16 | `CancelInitiator.Operator.approvalDefect`: 사유 > 500 또는 깨진 UTF-16 → 새 거절 `OPERATOR_REASON_INVALID`, 근거 참조 > 200 또는 깨짐 → `OPERATOR_BASIS_INVALID`(DB 열과 같은 상한, UTF-16 단위라 열보다 같거나 엄격). `open`·`reconstitute`도 같은 검사로 거부. S5에서 D-CANCEL1-008 감사 대상 | `CancelStartSpec`(501자·깨짐 거절, 500·200 경계 시작), `PaymentCancellationReconstitutionSpec`(501·201자 복원 거부) |
| n-2 | 복원: DECLINE ⇒ DECLINED 첫 관찰, NO_EFFECT ⇒ NO_EFFECT_CONFIRMED 첫 관찰 | 복원 거부 3행 + NO_EFFECT 왕복 |
| n-3 | `JpaPaymentCancellationRepository.insertNew`: 원 거래가 같은 Payment·같은 Authorization의 AUTHORIZATION 행인지 확인(FK는 다른 Payment 거래도 통과). update의 CANCEL 거래 INSERT 전 Payment·Authorization·원 거래 = 취소 행 확인 | `CancellationPersistenceSpec` n-3(다른 Payment의 승인 거래를 원 거래로 가진 취소 → IllegalStateException, 행 0) |
| n-4 | domain에서 처리(S6·S7로 미루지 않음): `CancellationResolution.applyObservation`·`abandonBeforeDispatch`가 진행 중 ⇔ RESERVED, COMPLETED ⇔ CONSUMED, FAILED ⇔ RELEASED·FORCED를 관찰 기록 전에 확인. `markOutcomeUnknown`은 잔액을 받지 않으므로 대상 아님(S7 회복 서비스가 반영 전 같은 확인을 부른다 — S7 계획) | `CancellationResolutionSpec` n-4 행 |
| n-5 | §20 개발 볼륨 메모(V15·V16 제자리 수정) | 문서 |
| n-1 | M2는 동등 변이로 수용(Coordinator) | 기록 |

- **RED-C**(21:55:22Z, `mr1-redc-20261019T215522Z.log`): exit 1, `e:` 7건(`OPERATOR_REASON_INVALID`·`OPERATOR_BASIS_INVALID`·`append` 없음).
- **RED-A**(21:56:44Z, `mr1-reda-20261019T215644Z.log`): API 표면만 추가(거절 enum 값 — 쓰지 않음, `append`가 옛 `recordOnce`에 위임)한 상태로 실행 → **exit 1, 92건 중 11 실패**: CancellationResolutionSpec 5(H-1 4·n-4 1),
  PaymentCancellationReconstitutionSpec 2(n-2·m-3), CancelStartSpec 2(m-3 문구·PaymentId 상한), CancellationPersistenceSpec 2(n-3·D-CANCEL1-008).
- **GREEN**: `mr1-green-20261019T220051Z.log`(DB 포함 9 Spec — persistence 13·계정 15 통과, domain 2건 실패: 아래 기대값 1·시험 결함 1) → 수정 뒤 `mr1-green2-20261019T220457Z.log` domain 7 Spec **166건**, 실패·오류·skip 0.
- **변이 RED**(`s1-mut-20261019T220558Z.log`): M13 같은 결정 판정이 조건을 무시(PTX만) → 36건 중 5 실패, M14 n-4 확인 제거 → 1 실패, M15 거절 근거–관찰 연결 제거 → 1 실패, M16 사유 상한 제거 → 1 실패.
- **바뀐 기존 기대값(Reviewer 확인 대상)**: `CancellationResolutionSpec` CR-10 끝 행 — 상충(2,000) 뒤 같은 PTX의 일치(3,000) 성공 "inq-ok": 전 `NoChange` / 후 `Incident(seq 2, holdOpened false)` + 상태 UNKNOWN·거래 없음·예약 RESERVED 3,000 단언 추가.
  이유: D-CANCEL1-005(사건 있는 진행 중 취소의 새 확정 결과는 사건) + D-CANCEL1-007(같은 PTX라도 조건이 다르면 다른 결정). 자동 확정 금지(CC-16)는 그대로다.
- **시험 결함 수정**: m-3 깨진 UTF-16 행이 Fixture의 승인 지문 계산(UTF-8 인코딩)에서 먼저 예외 → 지문을 임의 값으로 둔 운영자 입력을 직접 만들도록 고침(형식 검사가 대상 대조보다 먼저라 기대값 그대로).

### 18.3 S2 — PAYMENT V15~V19·JPA Port

| 항목 | 변경 |
|---|---|
| migration | `V15__cancellation.sql`(새 표 5: `payment_cancellation`·`cancel_attempt`·`cancel_observation`·`return_balance`·`return_reservation`), `runtime-privilege/V16`(표 단위 권한), `V17__cancellation_existing_tables.sql`(`payment_transaction` 승인 열 NULL 허용 + 취소 열·`cancel_txn_key` UNIQUE·FK(참조 동작 없음)·type CHECK v2·plan/card CHECK v2·**type별 짝 CHECK `ck_payment_transaction_kind`(명시적 IS NULL/IS NOT NULL — m-2)**, `incident_hold` reason CHECK v3), `V18__cancellation_recovery_schedule.sql`, `runtime-privilege/V19`(m-1로 S2) |
| Port | `PaymentCancellationRepository`(insertNew·lock·findIdByMerchantKey·findIdByApprovalId·update), `ReturnBalanceRepository`(insertNew·lockByPaymentId·update), `CancellationRecoveryScheduleStore`(+ 값 `CancellationRecoverySchedule`) |
| 구현 | `JpaPaymentCancellationRepository`, `JpaReturnBalanceRepository`, `JpaCancellationRecoveryScheduleStore`, Mapper `PaymentCancellationPersistenceMapper`, Entity 6(`JpaPaymentCancellationEntity`·`JpaCancelAttemptEntity`·`JpaCancelObservationEntity`(@Immutable)·`JpaReturnBalanceEntity`·`JpaReturnReservationEntity`·`JpaCancellationRecoveryScheduleEntity`), Spring Data 5, `JpaPaymentTransactionEntity` 승인 열 nullable + `newCancelRow`, `PaymentTransactionJpaRepository.findCancelTransaction`, `StoreConstraint` 14값 + 키 이름 매핑 |
| 시험 | 새 `CancellationPersistenceSpec`(실 MySQL·runtime 계정: 왕복·업무 키 조회·운영자 열, TX2 반영 왕복·stale version 충돌, CANCEL 거래가 승인 복원·승인 view에 영향 없음(m-2 회귀), 거절 → 늦은 성공(FORCED·키 상충 variant·사건 수·출처 NULL hold), 두 번째 부분취소 순번 2·revision 4, 진행 중·업무 키·approvalId UNIQUE 분류(m-3 제약), root 우회 K10·K11 15행(3819·1062) + 합계 불일치 복원 거부(CN-17) + 진행 중 예약 둘 1062, runtime 1142 8건, 회복 일정 Store) · `CheckoutWindowCheckMigrationStrategySpec`에 V14 볼륨(승인 거래 행) → V19 한 번 기동 행 |

- **RED-C**(20:11:12Z, `s2-redc-20261019T201112Z.log`): 시험 먼저 → `compileTestKotlin` **exit 1, `e:` 133건**(Port·Store 없음).
- **편차**: migration SQL은 test-first가 아니다(AUTH S2 편차와 같음) — 기대값은 §8.1·§8.2·§19(m-2·n-3)에서 독립 기술했고 우회 SQL(3819·1062)·권한(1142) 시험과 아래 변이 RED로 확인했다.
- **GREEN**(20:17:47Z, `s2-green1-20261019T201747Z.log`): `--tests CancellationPersistenceSpec·DatabaseAccountSeparationSpec·CheckoutWindowCheckMigrationStrategySpec·OldVolumeStartSpec·PaymentApiContextSmokeSpec·AuthorizationPersistenceSpec·PaymentSchemaSpec·ArchitectureSpec` **exit 0**(4m17s).
- **D-CANCEL1-006 추가(rev 0.3)**: V15에 `operator_cancel_rejection`(append-only, UNIQUE(approval_id, payment_id, rejection_reason), 사유 코드 CHECK, payment FK 없음), V16에 SELECT·INSERT 권한.
  Port `OperatorCancelRejectionStore`(`recordOnce`·`findByApproval`, 값 `OperatorCancelRejection`) → `JpaOperatorCancelRejectionStore`(@Immutable Entity, 확인 뒤 INSERT — CN-30), `StoreConstraint.OPERATOR_CANCEL_REJECTION`.
  시험 2건(같은 거절 한 행·다른 사유 새 행·열 집합에 자유 문구 없음·우회 1062/3819, runtime UPDATE/DELETE 1142). 호출(거절 판정과 같은 TX)은 S5 Use Case.
  **편차**: main·시험을 같은 차례에 썼다(test-first 아님) — 변이 RED로 확인.
- **변이 RED**(`/tmp/mut_s2.py`·`/tmp/mut_s2b.py`, 대상 `CancellationPersistenceSpec`, 원복·내용 일치 확인):

| 변이 | 결과 |
|---|---|
| Ma V17 AUTHORIZATION 행의 plan_kind NOT NULL 조건 제거 | 첫 실행 **생존**(`s2-mut-20261019T202236Z.log`) — 시험 결함: 우회 행이 세 열을 모두 비워 남은 card_type 조건에 걸렸다. 한 열씩 비운 행 3개를 추가(K11) 뒤 exit 1, 10건 중 1 실패(`s2-mut-20261019T203610Z.log`) |
| Mb V15 completed + reserved ≤ authorized CHECK 제거 | exit 1, 1 실패 |
| Mc V15 보낸 Attempt의 버전 필수 제거 | exit 1, 1 실패 |
| Md 복원이 합계 열 불일치를 무시 | exit 1, 1 실패 |
| Me update가 합계·revision을 쓰지 않음 | exit 1, 5 실패 |
| Mf 상충 관찰을 variant 0으로 저장 | exit 1, 1 실패 |
| Mg approvalId UNIQUE 분류 매핑 제거 | exit 1, 1 실패 |
| Mh update가 사건 수를 쓰지 않음 | exit 1, 1 실패 |
| Mi 반복 거절을 다시 INSERT(D-CANCEL1-006 멱등 제거) | exit 1, 12건 중 1 실패(`s2-mut-20261019T205449Z.log`) |
| Mj 거절 감사 표에 UPDATE 권한 | exit 1, 1 실패 |

- **GREEN(최종, rev 0.3 반영 뒤)**(20:50:28Z, `s2-green2-20261019T205028Z.log`): 같은 8개 Spec **exit 0**(4m11s) — XML 8파일 **112건**, 실패·오류·skip 0: PaymentApiContextSmokeSpec 5, ArchitectureSpec 40,
  AuthorizationPersistenceSpec 14, **CancellationPersistenceSpec 12(새)**, CheckoutWindowCheckMigrationStrategySpec 6(+1 V14 볼륨), DatabaseAccountSeparationSpec 15, OldVolumeStartSpec 4, PaymentSchemaSpec 16.
- **전체 모듈 실행은 미완료(PASS 주장 아님)**: `./gradlew --no-daemon :apps:payment-api:check`(20:37Z 시작, `s2-check-*.log`)를 Claude Code가 **호스트 메모리 부족으로 중지**했다(업무 실패 아님). WSL 안의
  Gradle은 고아로 남아 D-CANCEL1-005/006 수정 전 소스로 시험을 계속하고 있어 결과가 증거가 될 수 없으므로 직접 종료했다(AUTH §16.18-6 선례). 재실행은 하지 않았다(Coordinator 판단) — 그래서 payment-api 전체 수·ktlint/detekt 등
  나머지 check 하위 작업은 **미검증**. 대상 Spec 실행 합계: S1 158 + S2 112(ArchitectureSpec 40 중복) — 작업 전 대비 새 시험 +32(PaymentCancellationReconstitutionSpec 9, CancellationResolutionSpec +8, PaymentCancellationSpec +3,
  CancellationPersistenceSpec 12, CheckoutWindowCheckMigrationStrategySpec +1, 기존 spec 행 수정 외). `harness/commands.json` 하한은 바꾸지 않았다(Coordinator 소유).
- **바뀐 기존 기대값(추가만)**: migration 목록에 "15"~"19" 추가 — `CheckoutWindowCheckMigrationStrategySpec`(목록, history 크기 15 → 20, 다른 볼륨 history 문자열 2곳), `OldVolumeStartSpec`, `PaymentApiContextSmokeSpec`, `DatabaseAccountSeparationSpec`(설치 버전). `DatabaseAccountSeparationSpec` runtime 표 권한 기대 map·SHOW GRANTS 기대 집합에 새 표 6개 추가(새 표 — 기존 표 기대값 불변).
- **S2 편차(명세와 다른 점)**:
  - D-S2-1: `idempotency_request`의 CancelPayment 결과 CHECK·`result_cancellation_id` 열은 V17이 아니라 **쓰기 경로가 생기는 S5 migration**으로 옮긴다(관례 §9.2·AU §1.3 "쓰지 않는 값을 미리 두지 않는다"). 명세 §8.1 V17 행과 다름.
  - D-S2-2: `return_reservation`에 domain 예약 순서를 보존하는 `seq` 열(Payment 안 UNIQUE)을 두었다(복원 순서 = 기록 순서 — 왕복 같음 판정).
  - D-S2-3: `return_balance`에 UNIQUE(authorization_id) 추가(Payment 1:1·Authorization 1:1 — 이중 방어).
  - D-S2-4: 운영자 사유 열 VARCHAR(500)·목적 근거 VARCHAR(200) — domain은 길이 상한이 없다(발견 F-16 후보: 초과 입력은 1406 결함). S5 Use Case가 입력 검증(또는 domain 상한) — 보류 중인 m-9(운영자 명령 거절 감사)와 함께 처리.
  - D-S2-5: 사건 수 열 `incident_count`의 의미는 D-CANCEL1-005로 확정(서로 다른 기관 결정의 상충 수).
  - D-S2-6: D-CANCEL1-006 거절 감사 표를 새 migration 번호가 아니라 V15·V16에 넣었다(이 작업의 미배포 migration — 번호 배치 단순화). 명세 §8.1 표에는 rev 0.3 §19로 추가.
  - D-S2-7(발견 F-16): domain `CancelInitiator.Operator`의 사유·근거 참조에 길이 상한이 없다(DB 500·200) — S5에서 입력 검증 또는 domain 상한(가역 결정, 금융 의미 없음)으로 처리하고 초과는 운영자 명령 거절(D-CANCEL1-006 감사 대상)로 다룬다.

### 18.4 S3 — Simulator `sim-van-2` 취소(golden v2·판정·원장 V5~V7·K15·K16)

| 항목 | 변경 |
|---|---|
| golden(harness) | `harness/tests/test_van_contract_golden.py`: `golden_v2_text()`·`VanContractGoldenV2`(5) — sim-van-1 파일 sha256 고정 `0a7aaa20…ccf412`(m-7), v2 안 v1 벡터 바이트 동일, 취소 재전송 hash 같음, null 없음·승인번호 없으면 생략(n-5). `--write`는 v2만. 산출 `van-contract-sim-van-2.golden.json`(두 앱, sha `cceffc86…a1f791`) |
| domain | `VanCancelRequest`(+`VanCancelScope`, payload hash `sim-van-2/CANCEL_REQUEST`), `VanCancelEntry`(+`VanCancelTerms`·`VanCancelDeclineCategory`·`VanApprovedCancels`), `VanCancelProfile`(`VanProfile.cancel`), `VanCancelBehaviour`(VC-CXL-* 8), `VanCancellation`·`VanCancelJudgement`, `VanCancelInquiry`·`VanCancelInquiryJudgement`, `VanNamespace.CANCEL` |
| application | Port `VanCancelLedgerStore`(find·approvedTotals·insert), Use Case `CancelAtVanUseCase`·`InquireCancelAtVanUseCase`, 서비스 `VanCancellationService`·`VanCancelInquiryService`(1062 → 새 TX 재판정 최대 3) |
| adapter | `VanMessages` 취소 4종(엄격 파싱·golden 순서 응답), `VanTcpServer` 분기(취소 카드 전송 동작은 기존 전송 경로 재사용), `VanSettings` 프로파일 `cancel` 블록(운영 기본값 없음), `JpaVanCancelEntryEntity`(@Immutable)·`VanCancelEntryJpaRepository`·`JpaVanCancelLedgerStore`(n·Σ **한 JPQL SELECT** — m-6), `JpaVanInquiryLogEntity.operation`, `VanLedgerPrivilegeGuard` 탐침 +2, 인증 카드 카탈로그 VC-CXL-* 8(CN-25) |
| migration(n-6 분리) | `V5__van_cancel_entry.sql`(새 표: namespace UNIQUE·(원 승인, 승인 순번) UNIQUE·모양 CHECK 명시 IS NULL/IS NOT NULL), `V6__van_inquiry_log_operation.sql`(ALTER, NULL = 이전 행), `runtime-privilege/V7`(SELECT·INSERT) |
| 설정 | Simulator `application.yml`(main·test) 프로파일 A/B `cancel` 블록, `.env.example` `PAYCORE_SIMULATOR_VAN_CANCEL_{CLOSABLE_AFTER,INQUIRY_RETENTION,WINDOW}`(Demo) |
| 시험 | 새 `VanCancellationSpec` 26, `VanCancelInquirySpec` 6, `VanFrameCodecV2Spec` 10, `VanCancelServerSpec` 17(실 TCP·실 MySQL — K15·K16 각 6회), 기존 `SimulatorDatabaseAccountSpec`(권한·1142·기동 거부 행 추가), `SimulatorRestartDurabilitySpec`(취소 FOUND 재시작 행), `SimulatorArchitectureSpec`(+1 취소 원장 append-only), `VanFixture`(프로파일 cancel 인자 — 생성자 변경 적응) |

- **RED-C**(22:20:37Z, `s3-redc-20261019T222037Z.log`): 시험 먼저 → `:apps:institution-simulator:compileTestKotlin` **exit 1, `e:` 224건**(취소 타입·메시지 없음).
- **RED-A 편차**: S3 새 코드는 RED-C 뒤 한 번에 구현했다(stub로 실행 RED를 따로 만들지 않음) — 대신 아래 변이 RED 12건으로 규칙별 실행 실패를 확인했다. 첫 실행 실패 2건은 **시험 결함**(취소 기한 행의 취소 요청 ID가 판정 시각보다 1년 전이라 보존기간 밖 Refused) — 요청 ID를 판정 시각에 만들도록 시험 데이터만 고쳤다(기대값 불변). VanCancelServerSpec 첫 실행 7 실패는 구현 누락(카드 카탈로그에 VC-CXL-* 없음 → 인증 불가) — 카탈로그 추가로 해소, 카탈로그 일치 시험 1건 추가.
- **GREEN**: domain·codec·arch 7 Spec 96건(`s3-dom-*`·`s3-dom2-*`), DB 5 Spec 54건(`s3-db-*`·`s3-db2-*`), **Simulator 모듈 `check` 전체 exit 0 — XML 167건, 실패·오류·skip 0**(22:52:13Z `s3-full-sim-20261019T225213Z.log`; 줄끝 정규화 뒤 재실행 22:54:34Z `s3-full-sim2-*` exit 0 — 컴파일 산출물이 같아 test 작업 UP-TO-DATE). harness `test_van_contract_golden` 9 OK.
- **변이 RED**(`/tmp/mut_s3.py`, `s3-mut-20261019T223308Z.log`, 원복·내용 일치 확인) — **12/12 사멸**:

| 변이 | 대상 | 결과 |
|---|---|---|
| Ms1 V5 승인 순번 UNIQUE 제거(CN-22) | VanCancelServerSpec | exit 1, 1 실패(K15) |
| Ms2 1062 뒤 재판정 없음 | VanCancelServerSpec | exit 1, 2 실패 |
| Ms3 잔액이 승인된 취소 합 무시 | VanCancellationSpec | exit 1, 1 실패 |
| Ms4 n·Σ가 거절 행도 셈(outcome 조건 제거) | VanCancelServerSpec | exit 1, 2 실패 |
| Ms5 취소 기한 미검사 | VanCancellationSpec | exit 1, 2 실패 |
| Ms6 종결 표지 → REJECTED_CLOSED 아님 | VanCancellationSpec | exit 1, 1 실패 |
| Ms7 취소 조회가 승인 Capability로 종결 | VanCancelInquirySpec | exit 1, 1 실패 |
| Ms8 null 취소 승인번호를 씀(n-5) | VanFrameCodecV2Spec | exit 1, 1 실패 |
| Ms9 V7 UPDATE 권한 | SimulatorDatabaseAccountSpec | exit 1(guard가 기동 거부 → spec 2/2 실패) |
| Ms10 원 거래일 echo 미검사 | VanCancellationSpec | exit 1, 1 실패 |
| Ms11 scope echo 미검사 | VanCancellationSpec | exit 1, 2 실패 |
| Ms12 원장 INSERT 생략(응답만) | VanCancelServerSpec | exit 1, 8 실패 |

- **바뀐 기존 기대값(추가만)**: `SimulatorDatabaseAccountSpec` runtime 권한 집합에 `van_cancel_entry` SELECT, INSERT 1줄, 1142 행 2, 기동 거부 grant 2. `SimulatorRestartDurabilitySpec` 같은 test 안에 세 번째 기동·취소 FOUND 단언 추가(시험 이름에 "VAN cancel decision" 추가). `SimulatorArchitectureSpec` 새 test 1. 기존 승인 기대값 불변.
- **S3 편차·구현 결정(가역)**:
  - D-S3-1: 원 거래일(`originalTransactionDate`) echo 불일치도 ORIGINAL_MISMATCH(§6.3-3은 PTX·승인번호·통화만 적음 — 같은 echo 필드라 fail-closed로 포함).
  - D-S3-2: `van_cancel_entry.card_ref`(원 승인 카드 참조 사본)를 둔다 — 취소 조회의 `VC-CXL-INQUIRY-UNAVAILABLE-ONCE` 판정을 원 승인 원장 재조회 없이 한다. 종결 표지는 NULL.
  - D-S3-3: 취소 요청 ID 보존기간 판정은 프로파일 **취소** `inquiryRetention`(승인 값과 별개), 종결 표지의 `original_request_id`는 NULL(받은 적 없는 요청이라 모른다).
  - D-S3-4: `VC-CXL-PARTIAL`은 요청 금액 1이면 감액하지 않는다(취소 금액 ≥ 1 불변식) — Demo.
  - D-S3-5: 조회 기록 수(첫 조회 UNAVAILABLE)는 원 요청 ID로 센다(접두 `ar_`/`cr_`가 달라 섞이지 않음) — operation 열은 감사·구분용(CN-26).
  - D-S3-6: 취소 PENDING 시나리오 없음(CN-23 그대로) — `CANCEL_RESPONSE`·조회 PENDING 응답 작성기는 golden 대조용으로만 있다.

### 18.5 S4 — PG Adapter·Route 등록부(sim-van-2·cancel Capability·m-4)·격리

| 항목 | 변경 |
|---|---|
| Port(application.required) | `CancellationPort`(+`CancellationCall`·`OriginalAuthorizationRef`·`CancellationCallResult`), `CancelInquiryPort`(+`CancelInquiryCall`·`CancelInquiryCallResult`). `CancellationCall`은 취소 namespace = 원 승인과 같은 기관·환경·기관 계정이어야 만들어진다(m-4, 프로그래밍 오류) |
| Route | `RouteCapabilities.cancel: RouteCancelCapabilities?`(null = 이 Route로 취소 불가 — fail-closed, 운영 기본값 아님), `InstitutionRouteProfile.cancelCapability()`(sim-van-2 + 블록일 때만 Resolved), `servesOriginal(original)`(기관·환경 — m-4, S5 TX1·S6 TXd가 쓴다), `CANCEL_PROTOCOL = "sim-van-2"` |
| 등록부 | `ConfiguredInstitutionRouteRegistry`: 프로토콜 집합 {sim-van-1, sim-van-2}, sim-van-2 ⇔ `capabilities.cancel` 블록, 블록 검사(버전 1..64 printable, 기간 양수, closable-after < inquiry-retention, 부분취소 card type ⊂ 지원/할부 card type) |
| Adapter | 새 `SimulatorVanCancellationAdapter`(CancellationPort·CancelInquiryPort): sim-van-2·같은 환경·같은 기관 Route에만 연결, REQUEST_CONFLICT·REJECTED_CLOSED·원 PTX echo 불일치 = CORRELATION, 금액 등 조건 상충은 관찰로 넘김(domain 판정), 조회 closeIfAbsent는 취소 Capability가 있을 때만, 없는 Route의 NO_EFFECT = INTEGRITY, 지표 `paycore.cancellation.{dispatch,inquiry,call.duration}`. 메시지 `SimVanMessages` 취소 4종(엄격 파싱, null 승인번호 FRAMING). 연결·교환·메시지 ID는 승인 Adapter에서 `SimVanTransport`·`SimVanMessageIds`로 **옮겨 공유**(동작 변경 없음 — 기존 `SimulatorVanAuthorizationAdapterSpec` 25건 그대로 통과) |
| 시험 | 새 `SimulatorVanCancellationAdapterSpec` 16(golden v2 프레임 바이트·표 전 행·malformed 4+2·echo·MAC·끊김·timeout·분할·Route 거부·m-4·connect 예산·조회 표·지표·**m-7: sim-van-2 Route의 승인 프레임 = v1 golden 프레임**), `ConfiguredInstitutionRouteRegistrySpec` +4 test(취소 블록·Unavailable·m-4·기동 거부 9행), `ArchitectureSpec` +1(`cancellationPortsImplementedOnlyBySimulatorAdapter` + 위반 fixture `archfixture.institution.RealVanCancellationAdapter`), `StubVan` v2 golden helper, `VanRouteFixture` `cancelRoute`·`cancelCapabilities` |

- **RED-C**(22:41:31Z, `s4-redc-20261019T224131Z.log`): 시험(과 Port 2파일) 뒤 → `:apps:payment-api:compileTestKotlin` **exit 1, `e:` 16건**(Adapter·등록부 취소 블록·`cancelCapability`·`servesOriginal` 없음).
- **RED-A 편차**: S3와 같다 — 구현 뒤 첫 실행이 GREEN이었고 규칙별 실행 실패는 변이 RED로 확인했다.
- **GREEN**(22:43:12Z, `s4-green-20261019T224312Z.log`): 6 Spec **exit 0 — 95건**, 실패·오류·skip 0: ConfiguredInstitutionRouteRegistrySpec 8, ArchitectureSpec 41, AuthorizationProfileConsistencySpec 3, LocalDemoAuthorizationConfigSpec 2, SimulatorVanAuthorizationAdapterSpec 25, SimulatorVanCancellationAdapterSpec 16.
- **payment-api 모듈 `test` 전체**(줄끝 정규화 뒤 최종 소스, 22:54:52Z 시작, `s4-full-pay-20261019T225452Z.log`): **exit 0, XML 1344건, 실패·오류·skip 0**(7m24s). `check`의 나머지 하위 작업(정적 검사 등)은 실행하지 않았다 — 미검증.
- **변이 RED**(`/tmp/mut_s4.py`, `s4-mut-20261019T224450Z.log` + 재실행 `s4-mut-20261019T225130Z.log`) — **11/11 사멸**(Mp11은 첫 실행 생존 → 시험 추가 뒤 사멸):

| 변이 | 대상 | 결과 |
|---|---|---|
| Mp1 sim-van-1 Route로 취소 전송 | 취소 Adapter Spec | exit 1, 2 실패 |
| Mp2 Route 기관 비교 제거(m-4) | 〃 | exit 1, 1 실패 |
| Mp3 원 PTX echo 미검사 | 〃 | exit 1, 1 실패 |
| Mp4 Capability 없이 closeIfAbsent 전송 | 〃 | exit 1, 1 실패 |
| Mp5 Capability 없는 Route의 NO_EFFECT 수용 | 〃 | exit 1, 1 실패 |
| Mp6 취소 블록 없는 sim-van-2 허용 | 등록부 Spec | exit 1, 2 실패 |
| Mp7 할부 부분취소 card type 상한 제거 | 등록부 Spec | exit 1, 1 실패 |
| Mp8 null 취소 승인번호를 없음으로 읽음(n-5) | 취소 Adapter Spec | exit 1, 1 실패 |
| Mp9 취소 호출의 기관 계정 ≠ 원 승인 허용(m-4) | 〃 | exit 1, 1 실패 |
| Mp10 REQUEST_CONFLICT를 FRAMING으로 | 〃 | exit 1, 1 실패 |
| Mp11 sim-van-1 프로파일에도 취소 Capability 해석 | 등록부 Spec | 첫 실행 **생존**(등록부가 그 조합을 이미 거부 — 등록부 밖 프로파일 경로 시험 없음) → 프로파일 직접 행 추가 뒤 exit 1, 1 실패 |

- **바뀐 기존 기대값(추가만)**: `ArchitectureSpec` `institutionAdapterDoesNotMutateDomain`의 금지 대상에 `PaymentCancellation`·`CancelAttempt`·`ReturnBalance`·`CancelStart`·`CancellationResolution` 추가(규칙 강화). 등록부 기존 "protocol sim-van-2 거부" 행은 그대로 통과한다(이제 사유는 "sim-van-2인데 취소 블록 없음") — 기대값 불변, 다른 프로토콜 행은 새 행 `sim-van-3`으로 따로 둠.
- **S4 편차·구현 결정(가역)**:
  - D-S4-1: sim-van-1 Route에 `cancel` 블록이 있으면 기동 거부(명세는 sim-van-2의 블록 누락만 적음 — 보낼 수 없는 Route의 취소 Capability를 시작 판정이 믿지 않게 fail-closed).
  - D-S4-2: 기동 일관성 `cancel.closable-after ≥ preparedDeadline + dispatchLease`(§6.6)는 취소 timing 설정(`paycore.cancellation.timing.*`, CN-29)이 생기는 **S7**로 미룬다 — 지금은 비교할 값이 없다. `.env.example`·`LocalDemoAuthorizationConfigSpec`의 sim-van-2 전환도 계획대로 S8.
  - D-S4-3: 취소 응답의 금액·통화 등 조건이 요청과 달라도 Adapter는 관찰로 넘긴다(원 승인 PTX echo만 CORRELATION) — `VC-CXL-PARTIAL`의 "UNKNOWN 유지 + hold + 사건"(§6.4)은 domain 판정(S6)이 한다.
  - D-S4-4: m-4의 S4 범위 = Port 호출 불변식(같은 기관·환경·계정) + Adapter 연결 거부(Route 기관·환경) + `servesOriginal` 판정 함수. TX1 503·TXd GATE_REFUSED 연결은 S5·S6.
  - D-S4-5: `CancellationCallResult.Declined`·`CancelInquiryCallResult.Found`에 고정 거절 분류 문자열을 싣는다(CN-02 안내용, 원문 없음).

### 18.6 중간 검토 #2(PASS_WITH_FIXES) 대응 — m-1·n-3

- **m-1 줄끝 사고 기록**: S3·S4 중 Windows 쪽 Python(Git Bash의 `python3 -`를 `//wsl.localhost/...` 경로에 실행 — text 모드 쓰기)으로 기존 파일을 고쳐 **17파일이 CRLF**가 됐다:
  Simulator main `VirtualCard.kt`·`VanNamespace.kt`·`VanProfile.kt`·`VanCancelInquiry.kt`·`VanCancelInquiryJudgement.kt`, Simulator test `SimulatorDatabaseAccountSpec.kt`·`SimulatorRestartDurabilitySpec.kt`·
  `SimulatorArchitectureSpec.kt`·`VanTestClient.kt`·`VanFixture.kt`·`VanCancelServerSpec.kt`·`VanCancellationSpec.kt`, payment-api test `ArchitectureSpec.kt`·`ConfiguredInstitutionRouteRegistrySpec.kt`·`StubVan.kt`·
  `VanRouteFixture.kt`·`SimulatorVanCancellationAdapterSpec.kt`. golden JSON은 해당 없음(WSL 쪽 harness가 썼다). 발견: `git diff`의 CRLF 경고. 처리: HEAD가 LF인 변경 파일만 WSL 쪽에서 LF로 되돌렸다(`/tmp/fix_crlf.py`, 내용 동일)
  — 그 뒤 payment-api 모듈 test 1344건 재실행. **재발 방지**: `.gitattributes`에 `*.golden.json -text`·`apps/*/src/test/resources/contracts/** -text`(바이트 비교 계약 자료 — 줄끝 변환 없음) 추가, 파일 쓰기는
  Write/Edit 도구 또는 WSL 쪽 실행만, 단계마다 `git ls-files --eol -co --exclude-standard`(`/tmp/eolcheck.sh`) 확인. 23:11Z 확인: 변경 파일 118개 — 색인 `i/lf` 54·미추적 64, 작업 트리 전부 `w/lf`, crlf·mixed **없음**.
- **n-3 Simulator 격리 수준**: 새 `SimulatorTransactionIsolationSpec`(PAYMENT `TransactionIsolationSpec`과 같은 방식) — 연결 기본 REPEATABLE READ, 취소·취소 조회 TX는 REPEATABLE READ **명시 고정**
  (`VanCancellationService`·`VanCancelInquiryService`의 `TransactionTemplate.isolationLevel`). RED-A(23:08:25Z `n3-reda-*`): 관측 `[null, null]`(기본값 — 고정 아님) → 고정 뒤 GREEN(23:10:39Z `n3-green2-*`, 같은 Spec +
  VanCancelServerSpec 18건 exit 0). 승인 서비스(`VanAuthorizationService`·`VanInquiryService`, DONE 경로)는 바꾸지 않았다 — 기본값(= REPEATABLE READ)으로 동작(편차 D-R2-1).

### 18.7 S5 — 취소 API·파서·멱등(x1·V20)·TX1·운영자 Use Case·K1~K5·K12·m-3·m-4·m-5·K-3

| 항목 | 변경 |
|---|---|
| migration | `V20__cancel_payment_idempotency.sql`(D-S2-1 — ALTER만): `idempotency_request.result_cancellation_id`, operation CHECK `_v2`(+ CancelPayment), result CHECK `_v2`(CreatePayment·ConfirmPayment 행 그대로 + 다른 operation은 취소 참조 NULL, CancelPayment 성공 ⇔ 취소 참조, 거절은 Payment 참조만 — 명시 IS NULL/IS NOT NULL). 권한 변경 없음 |
| provided | `CancelPaymentUseCase`·`CancelPaymentCommand`·`CancelPaymentResult`·`CancelPaymentFailure`·`CancelRejection`·`CancelOutcome`·`CancellationView`(+상태·`CancelFailureCategory`), `OperatorCorrectionCancelUseCase`(+명령·결과·`OperatorCancelRejectionCode`), `OperatorCancelTargetQuery`(+view) |
| required | `IdempotentOperation.CANCEL_PAYMENT`·`StoredCancelAccepted`·`StoredCancelRejected`, `CancellationIdentifierGenerator`(pcn_/pca_/cr_/cq_ ULID), `CancellationTimings`, `CancellationViewReader`(+행 2) |
| 서비스 | `CancelStartService`(TX1 — 가맹점·운영자 공용, §4.3 순서, 503 = rollback·저장 없음), `CancelPaymentService`(조정자 — S5는 TX1 + 현재 상태 view), `OperatorCorrectionCancelService`(조정자 + 대상 조회), `CancellationViews`, `CancelFingerprints`(x1), `SecureRandomCancellationIdentifierGenerator` |
| adapter | `CancelPaymentController`·`CancelPaymentRequestParser`(닫힌 3필드, merchantCancelId 1..64 — m-3 상한 넘음은 400), `ApiErrorCode` 7개, `JooqCancellationViewReader`(한 읽기 TX — 취소·CANCEL 거래·반환 잔액), `JpaIdempotencyRecordStore`·Entity(취소 결과), `CancellationTimingSettings`(`paycore.cancellation.timing.*`, 운영 기본값 없음), `RequestFingerprint` 접두 `x` 허용 |
| 설정 | 시험 yml: sim-van-2 Route `SIM-VAN-C`(부분취소 신용·체크 일시불 + 신용 할부, 취소 무효과 확정)·`SIM-VAN-CB`(신용 일시불만, 확정 없음), `paycore.cancellation.timing`. `.env.example` JSON에 `paycore.cancellation.timing`(로컬 기동용 — Route sim-van-2 전환은 S8), main yml 설정 설명 |
| 시험 | 새 `CancelStartServiceSpec` 18, `CancelStartConcurrencySpec` 8(K1·K2·K3·K4·K5·K12 2행·m-3 강제 경합), `OperatorCorrectionCancelSpec` 9, `CancelPaymentApiSpec` 8, `CancelPaymentRequestParserSpec` 2, `CancelFingerprintsSpec` 2, `ArchitectureSpec` +2(K-3 취소 서비스가 Admission·head·eligibility·일 한도 Port에 의존 안 함, m-5 운영자 Use Case 호출자 없음 + 위반 fixture `archfixture.operatorcancel.OperatorCancelEntryPoint`), `CancellationPersistenceSpec`(+V20 CHECK 10행), 시험 지원(`CancelFixture` — 운영 confirm 경로로 승인, `WithdrawableRouteResolver.replace`, `FaultPoint` 취소 9종, `ConfirmFixture.cancelIdempotencyRows`) |

- **RED-C**(23:22:35Z, `s5-redc-20261019T232235Z.log`): 시험 먼저 → `compileTestKotlin` **exit 1, `e:` 346건**.
- **RED-A**(stub 먼저 — 판정 n-1, 23:27:40Z `s5-reda-20261019T232740Z.log`): Port·provided 타입·V20·Jpa·jOOQ는 실제, 서비스·파서·fingerprint는 `TODO()` stub → 4 Spec **31건 중 31 실패**(exit 1). 첫 RED-A 시도(23:26:31Z)는 `CreatePaymentService`의 when 누락으로 main 컴파일 실패(환경 아님 — 고친 뒤 재실행).
- **GREEN**: `s5-green1`~`s5-green7`(23:34~23:51Z). 구현 결함 1(RequestFingerprint 형식이 `r`/`c`만 허용 → `x` 추가), 시험 결함 4(체크카드 승인은 stub VAN이 cardType을 echo해야 함 — fixture에 대본, API 오류 본문은 `error` 아래, 진행 중 취소가 금액 검사보다 앞서는 CancelStart 순서를 시험이 잘못 가정 — 금액·통화 행을 진행 중 취소 없는 Payment로, m-3 경합을 실제 잠금 대기로 강제). 최종: CancelStartServiceSpec 18·CancelStartConcurrencySpec 8·OperatorCorrectionCancelSpec 9·CancelPaymentApiSpec 8·파서 2·fingerprint 2·ArchitectureSpec 43·CancellationPersistenceSpec 13 + 회귀(ContextSmoke 5·AuthorizationPersistence 14·CheckoutWindow 6·DatabaseAccountSeparation 15·OldVolume 4·PaymentSchema 16) **실패 0**.
- **변이 RED**(`/tmp/mut_s5.py`, `s5-mut-20261019T235206Z.log`) — **10/10 사멸**(모두 시험 실패 — 컴파일 실패 아님): Mu1 업무 키 조회 생략, Mu2 원 경로 비교 제거(m-4), Mu3 운영자 거절 감사 생략, Mu4 멱등 결과 미저장, Mu5 UNIQUE 충돌 재판정 제거(m-3), Mu6 V20 취소 참조 조건 제거, Mu7 fingerprint 금액 제외, Mu8 일정 기준 lease로, Mu9 merchantCancelId 상한 80, Mu10 approvalId 충돌을 기존 결과로.
- **바뀐 기존 기대값(추가만)**: migration 목록 "20" 추가(`CheckoutWindowCheckMigrationStrategySpec` 목록·history 20 → 21·문자열 3곳·시험 이름 "V19" → "the latest version (V20)", `OldVolumeStartSpec`, `PaymentApiContextSmokeSpec`, `DatabaseAccountSeparationSpec`). `ArchitectureSpec` 새 test 2. 기존 승인 기대값 불변(승인 Route SIM-VAN-01·B는 sim-van-1 그대로).
- **S5 편차·결정(가역)**:
  - D-S5-1: S5 조정자는 TX1 + view만(전송 없음 — S6). 그래서 API 성공 시험은 2xx·계약 필드까지, CANCEL_IN_PROGRESS API 행은 첫 취소가 진행 중일 때만 단언(S6에서 대본으로 진행 중을 고정).
  - D-S5-2(**운영자 경로는 검토 #3 n-3으로 대체** — 비잠금 `find`, 가맹점 경로는 그대로): 업무 키로 찾은 기존 취소는 `lock`(FOR UPDATE)으로 읽는다(Repository에 비잠금 Aggregate 조회가 없음) — payment X 아래라 잠금 순서(… → payment_cancellation)와 맞는다.
  - D-S5-3: Authorization이 없으면(REQUESTED) Route 해석 없이 PAYMENT_NOT_CANCELLABLE(CancelStart에 넘길 원 경로가 없다). 원 기관 요청 namespace = RESOLVED Attempt(없으면 첫 Attempt — 그때는 거래가 없어 CancelStart가 먼저 거절).
  - D-S5-4(**검토 #3 m-3으로 대체** — 형식 아님도 감사 행, payment_id `MALFORMED`): 운영자 명령의 paymentId가 형식이 아니면 감사 없이 NotFound(감사 행의 payment_id 값 타입이 형식을 강제) — 없는 Payment(형식은 맞음)는 감사 PAYMENT_NOT_FOUND(CN-30).
  - D-S5-5: 운영자 명령의 503(Capability·Route 불가)은 업무 거절이 아니라 저장 없음(감사 없음) — `CancelPaymentFailure.TemporarilyUnavailable`을 운영자 Use Case도 던진다.
  - D-S5-6: `OperatorCancelTargetQuery`는 Authorization이 없는 Payment도 null(대상 아님).
  - D-S5-7(**검토 #3 m-2로 해소** — K17 실 DB 행 §18.10): 진행 중(S5)인 K17(P1 늦은 성공 vs P2 취소)은 같은 주문 두 번째 Payment fixture가 필요해 S6로 넘긴다. K6·K18은 S6(승인 TX2와 함께).

### 18.8 S6 — TXd·TX 밖 호출·TX2·Outbox·사건(D-CANCEL1-002/005/007)·n-4·K6·K13·K18·§9.3

| 항목 | 변경 |
|---|---|
| 서비스 | 새 `CancellationDispatchService`(TXd: 잠금 §4.7, preparedDeadline 전·Route 등록·환경/기관 = 원 승인 Attempt(m-4)·취소 Capability 확인 → 통과면 `beginDispatch` + 프로파일 버전 고정(D-CANCEL1-004)·lease, 거절이면 GATE_REFUSED → FAILED(PRE_TRANSMISSION)·RELEASED·PaymentCancelFailed·일정 닫힘. commit 불명확이면 안 보냄(CN-08). TX 밖 호출 — Port 예외는 OutcomeUnknown(CONNECTION_LOST)(검토 #2 n-4)), 새 `CancellationResultService`(TX2·회복 공용: applyCall·applyObservation·applyInquiry·markLeaseExpired·fence, 잠금 §4.7, 관찰·Aggregate·거래·Outbox 같은 TX, 사건마다 이벤트), `CancellationEvents`(v1 4종), 조정자 2개가 새 시작만 전송 |
| Outbox | `PaymentCancelledV1Payload`(CC §9.3 목록 + 원 Plan·cardType — §9.3, 정책·권리 버전은 원 Authorization 값), `PaymentCancelFailedV1Payload`, `CancellationIncidentDetectedV1Payload`(사건 순번·hold 여부·여는 관찰·riskHint HIGH/CRITICAL(FORCED)), `CancellationOutcomeUnresolvedV1Payload`(S7) + JSON 직렬화(계약 이름 `amount`·`authorizedAmount`·`cumulativeReturnedAmount`·`originalPlan`) |
| 조회 | `CancellationViewRow.orderKey`(TX2·TXd가 control부터 잠그려고 — 비권위 조회 후 잠금 재판정, 승인 TX2와 같은 방식) |
| 시험 | 새 `CancelFlowSpec` 15(승인·재생·부분/잔액·거절·PENDING·응답 유실·n-4·GATE_REFUSED(Route 철회·m-4)·CN-08 TXd commit 불명확·preparedDeadline 경과·TX2 장애(재전송 없음)·상충(VC-CXL-PARTIAL 모양 — UNKNOWN·hold·사건 1·effect_key `…:1`)·운영자 경로·§9.3 행렬(신용 일시불·신용 할부 3·체크 일시불 × 전액·부분+잔액)), 새 `CancelResultConcurrencySpec` 5(K13a·b·c 실 DB — 같은 결정 8회 동시 혼합 출처 + 다른 결정 1회 → 사건 2·이벤트 2 = incident_count·hold 1·1062 없음·상태 불변, K6 3회, K18), `CancelPaymentApiSpec` +1(200 COMPLETED·FAILED 본문, PENDING 대본으로 202 UNKNOWN·notice → 409 CANCEL_IN_PROGRESS 확정 단언), 시험 지원 `ProgrammableVan` sim-van-2 취소 원장(취소 요청 ID별 결정·재전송 = 같은 결정·조회 FOUND/NOT_FOUND/종결 표지·MID별 대본), `ObservedCancellationPort`(호출 시 TX 여부·전송자 결함 주입) |

- **RED-C**(00:09:16Z, `s6-redc-20261020T000916Z.log`): exit 1, `e:` 7건(`CancellationResultService` 없음 — CancelFlowSpec은 기존 타입으로 컴파일).
- **RED-A**(stub — `CancellationResultService.applyObservation` = `TODO()`, 조정자 전송 없음, 00:10:27Z `s6-reda-20261020T001027Z.log`): 2 Spec **20건 중 18 실패**(통과 2: preparedDeadline 경과 행(전송 없음이 기대값)·K18(교착 없음 — 전송과 무관)). 첫 시도는 시험 컴파일 결함(ApprovedTerms 업무일 인자 누락) — 고친 뒤 재실행.
- **GREEN**(`s6-green1`~`s6-green3`, 00:16~00:20Z): CancelFlowSpec 15·CancelResultConcurrencySpec 5 첫 실행 통과, 회귀 OperatorCorrectionCancelSpec 9·CancelStartConcurrencySpec 8·CancelPaymentApiSpec 9·ArchitectureSpec 43·CancelStartServiceSpec 18 실패 0.
- **변이 RED**(`/tmp/mut_s6.py`, `s6-mut-20261020T002131Z.log`) — **8/8 사멸**: Mv1 다른 프로파일 버전 고정, Mv2 TXd 원 경로 비교 제거(m-4), Mv3 Port 예외 전파(n-4), Mv4 새 hold일 때만 사건 이벤트(D-CANCEL1-002 이전 동작 — K13 3행 실패), Mv6 PaymentCancelled 키를 cancellationId로, Mv7 preparedDeadline 미검사, Mv8 PaymentCancelFailed 누락, Mv9 TX2 잔액 미저장. CN-08(commit 불명확 → 안 보냄)은 구조상 게이트 값이 없으면 호출 객체가 없어 의미 있는 단일 변이를 만들지 못했다(행 시험으로만 확인 — 편차 기록).
- **바뀐 기존 기대값**: `OperatorCorrectionCancelSpec` 첫 행 `view.status` IN_PROGRESS → COMPLETED(S5는 전송 단계가 없어 IN_PROGRESS가 S5 동작이었다 — S6 전송이 생겨 계획대로 바뀐 값, 금융 의미 완화 아님). `CancelPaymentApiSpec`의 조건부 CANCEL_IN_PROGRESS 행을 대본으로 확정 단언으로 강화.
- **S6 편차·결정(가역)**:
  - D-S6-1(검토 #2 n-2 선택): 원 승인 PTX echo가 다른 승인 응답은 Adapter가 OutcomeUnknown(CORRELATION)으로 둔다(S4 그대로) — 기관 원장의 결정은 원 취소 요청 ID 조회(S7)가 FOUND로 가져와 domain이 원 거래 불일치로 상충 판정한다. 상충 관찰로 즉시 넘기는 대안은 상관관계가 깨진 응답을 결정으로 믿게 돼 택하지 않음.
  - D-S6-2(n-4): 전송자 결함의 UnknownCause는 CONNECTION_LOST(새 원인 값을 만들지 않음 — 보냈는지 모른다는 의미가 같다).
  - D-S6-3: TX2는 control·잔액을 항상 저장한다(바뀌지 않아도 — 승인 TX2와 같은 방식, version 증가). TXd 게이트 거절은 control을 저장하지 않는다(바꾸지 않음).
  - D-S6-4: 사건 이벤트의 hold occurrence = 이번 TX에서 열린 hold, 없으면 이 Payment의 활성 CANCEL_RESULT_CONFLICT hold(사건 hold 출처 열은 NULL — CN-15).
  - D-S6-5(**검토 #3 m-2로 해소** — K17 실 DB 행 §18.10): K17(같은 주문 P1 늦은 성공 vs P2 가맹점 취소)은 두 번째 Payment(같은 주문 새 generation) fixture가 없어 실 DB 행을 만들지 않았다 — 근거: 두 경로 모두 control X로 직렬화(K6·K18 실 DB 증거)이고 가맹점 취소는 같은 Payment의 hold만 본다(domain `CancelStartSpec`). Reviewer 판단 요청.

### 18.9 S7 — 회복 Worker(일정·lease·fencing·조회·예산·이관·느린 조회·버전 고정)·K7·K8·K9·K14·n-4 잔여·D-S4-2·지표

| 항목 | 변경 |
|---|---|
| domain | `CancellationResolution.markOutcomeUnknown(cancellation, balance, attemptId, cause)` — 예약 정합 검사 먼저(검토 #1 n-4의 남은 경로, 검토 #2 지시) |
| provided | `RecoverCancellationsUseCase`·`RecoverCancellationsCommand`(결과는 기존 `RecoveryRunResult`), `CancellationBacklogQuery`·`CancellationBacklog` |
| 서비스 | 새 `CancellationRecoveryService`(AUTH-001 설계 복제 — 선점 SKIP LOCKED, PREPARED fencing, lease 만료 표시, 원 취소 요청 ID 조회 TX 밖, closeIfAbsent = 고정 버전 = 현재 버전(D-CANCEL1-004) ∧ 취소 무효과 확정 ∧ closableAfter ≤ 연령 < 보존기간, 예산·최대 연령·보존기간 → `CancellationOutcomeUnresolved`(상태·예약 불변 — D-CANCEL1-003 임시 출구 없음), 이관 뒤 느린 조회·보존기간 경과 시 조회 중지, Route 없음 → 종결 보류 로그 `ROUTE_NOT_REGISTERED`(n-7), 사건 처리된 UNKNOWN도 조회·이관 경로), `CancellationBacklogService` |
| adapter | `CancellationRecoveryScheduler`(`@Scheduled`, counter `paycore.payment.cancellation_recovery{result}`)·`CancellationRecoveryJobSettings`, `CancellationBacklogGauges`(`paycore.cancellation.unknown`·`.unknown.oldest_age_seconds`·`.escalated`), `CancellationTimingConsistency`(D-S4-2 — sim-van-2 Route마다 connect 예산 < lease − margin, cancel.closableAfter ≥ preparedDeadline + lease, 회복 maxAge < cancel.inquiryRetention), `JooqCancellationViewReader.findAttempt`·`backlog` |
| 시험 | 새 `CancellationRecoverySpec` 15(응답 유실 → 조회 FOUND → COMPLETED·재전송 0, TX2 장애 → lease 만료 → UNKNOWN → 완료, PREPARED fencing, 기록 없음 → closableAfter 전 NOT_FOUND·뒤 종결 → FAILED(NO_EFFECT), D-CANCEL1-004 버전 불일치 → 종결 보류·로그(기관 요청 ID·계정 없음 확인), CB 예산 소진 → 이관 1·UNKNOWN·RESERVED·다음 취소 차단·느린 조회·보존기간 뒤 일정 닫힘, n-7, 사건 처리된 진행 중 취소, K14 Worker 2개, K9, K7 2행, K8, 결론과 이관 경합, 지표·gauge), 새 `CancellationTimingConsistencySpec` 2, `CancellationResolutionSpec` +1 단언(markOutcomeUnknown 정합), 시험 지원 `FaultPoint.CANCEL_VIEW_FIND`·`CancellationViewReader` 대역 |

- **RED-C**(00:35:30Z, `s7-redc-20261020T003530Z.log`): exit 1, `e:` 62건.
- **RED-A**(stub — markOutcomeUnknown은 새 인자만·검사 없음, 회복 서비스·scheduler `TODO()`, 시간 검사 빈 클래스, 00:36:28Z `s7-reda-20261020T003628Z.log`): 4 Spec **62건 중 14 실패**(회복 12 — 통과 2는 결과 서비스만 쓰는 K8·K9, 도메인 정합 1, 시간 검사 1).
- **GREEN**(`s7-green1`~`s7-green3`, 00:40~00:57Z): 시험 결함 1(n-7 행 — 시험 대역은 application Route 조회만 철회해 Adapter 연결은 남는다 — VAN 수신 0 단언을 "종결 요청 보류 + 로그"로 고침, 기대 의미는 명세 §19 n-7 그대로). 최종 CancellationRecoverySpec 15·CancellationTimingConsistencySpec 2·CancellationResolutionSpec 36·PaymentCancellationReconstitutionSpec 10 + 회귀(ArchitectureSpec 43·CancelFlowSpec 15·LocalDemo 2·ContextSmoke 5·CancellationPersistence 13) 실패 0.
- **변이 RED**(`/tmp/mut_s7.py`, `s7-mut-20261020T004601Z.log`·재실행 `s7-mut-20261020T005801Z.log`) — **7/8 사멸, 1 동등**: Mr1 버전 무관 종결 요청, Mr2 예산 이관 제거, Mr3 closableAfter 전 종결, Mr4 markOutcomeUnknown 정합 검사 제거, Mr5 시간 검사 closableAfter 제거, Mr6 lease를 잠근 일정으로 판정 안 함(첫 실행 **생존** — 회차가 lease 전에는 선점하지 않아 경로가 실행되지 않았다 → lease 전 직접 표시 행 추가 뒤 사멸), Mr7 보존기간 뒤 조회 중지 제거. **Mr8**(이관 TX의 취소 상태 검사 제거)은 행을 추가해도(회복 조회와 이관 사이에 결론 — `CANCEL_VIEW_FIND` 경합) 생존: 결론을 낸 TX2가 같은 TX에서 일정을 닫으므로(`closedAt` 검사) 상태 검사가 겹친 방어다 — **동등 변이로 기록**(Reviewer 판단 요청). 경합 행은 유지.
- **바뀐 기존 기대값**: 없음(기존 시험의 `markOutcomeUnknown` 호출 12곳은 새 인자(잔액)를 넘기도록 기계적으로 바꿈 — 기대값 불변: CancellationResolutionSpec 7·PaymentCancellationReconstitutionSpec 4·CancellationPersistenceSpec 1).
- **S7 편차·결정(가역)**:
  - D-S7-1: 결과 타입은 승인과 같은 `RecoveryRunResult`(지표 집계가 같다) — 명령은 `RecoverCancellationsCommand`로 따로.
  - D-S7-2: Attempt가 DISPATCHING·RESOLVED인데 취소가 UNKNOWN(사건 처리된 진행 중 취소 — CC-16)이면 lease 표시가 아니라 조회·이관 경로로 보낸다(출구는 RECON).
  - D-S7-3: Route가 없으면(n-7) 조회는 Adapter가 UNKNOWN_ROUTE로 불가 처리하고(예산 소진 → 이관) 종결 요청은 하지 않는다 — application은 `ROUTE_NOT_REGISTERED` 보류 로그.
  - D-S7-4(**검토 #3 m-2로 해소** — §18.10): K17은 S6 D-S6-5 그대로(미구현 — 근거 기록, Reviewer 판단 요청).
  - D-S7-5: 회복 회차 인과 값 causationId는 64자 열에 맞게 자른다(`cancellation-recovery:<UUID>` = 58자 — 실제로는 자르지 않음).
- **S7 체크포인트 전체 실행**(최종 소스): `:apps:payment-api:check` exit 0 — XML **1431건**, 실패·오류·skip 0(01:00:50Z 시작, 8m9s, `s7-full-check-20261020T010050Z.log`). `:apps:institution-simulator:check` exit 0 — **168건**(01:09:20Z, `s7-full-sim-*`).
  harness `test_van_contract_golden` 9 OK. 줄끝: 변경 파일 177개 전부 `w/lf`, 색인 `i/lf` 68·미추적 109, crlf·mixed 없음.

### 18.10 중간 검토 #3(PASS_WITH_FIXES — H 없음, Mr8 동등 수용) 대응 — m-1·m-2(K17)·m-3·n-1~n-6

| 지적 | 변경 | 시험(먼저) |
|---|---|---|
| m-1 | `CancellationRecoveryService.handle`: Attempt PREPARED ∧ 취소 UNKNOWN(보내지 않은 Attempt에 온 확정 결과 — 사건) → fencing(NoChange 영원) 대신 `inquireOrEscalate`. 고정 버전 NULL이라 `closeIfAbsent`는 늘 false(D-CANCEL1-004), 끝은 예산 이관(D-CANCEL1-003) | `CancellationRecoverySpec` 새 행: 관찰 주입 → UNKNOWN·Attempt PREPARED → 조회 3(전부 closeIfAbsent=false) → 이관 1·UNKNOWN 유지 |
| m-2 K17 | 운영 코드 변경 없음(가맹점 취소 hold 검사는 이미 그 Payment의 hold만 — D-CANCEL-001). 시험 지원 `ConfirmFixture.prepared(previous = …)`: 같은 주문의 다음 Payment를 공식 `decide → CreateNewPayment → occupy`로 만든다(두 번째 Payment fixture) | `CancelResultConcurrencySpec` K17: P1 기관 거절 → FAILED, P2 같은 주문 → AUTHORIZED, P1 늦은 승인 관찰(SUCCESS_AFTER_TERMINATION hold) vs P2 가맹점 취소 — hold 먼저·취소 먼저·동시 3회(control 잠금 대기 확인 뒤 진행), 실제 MySQL. 단언: P2 취소 Accepted·COMPLETED(ORDER_ON_HOLD 아님), P1 hold 1, P2 hold 0, P1 FAILED, 오류·교착 없음. **D-S6-5·D-S7-4(K17 미구현)는 이것으로 대체** |
| m-3 | `CancelStartService.operatorTx`: 형식이 아닌 paymentId도 감사 행 하나(payment_id 자리표시 `MALFORMED` — 원문 저장 안 함, 사유 `PAYMENT_ID_MALFORMED`) 후 NotFound(커밋). `OperatorCancelRejectionCode.PAYMENT_ID_MALFORMED` 추가. 형식이 아닌 OperatorId·approvalId는 ADMIN-BOOT-001 인계(Coordinator 기록) | `OperatorCorrectionCancelSpec` 새 행: 65자 paymentId → NotFound + 감사 1행 |
| n-1 | V20 `ck_idempotency_request_result_v2`: SUCCESS·REJECTED 가지 6개 모두 `result_code IS NOT NULL`(상속한 CreatePayment·ConfirmPayment 포함). 미커밋 V20 제자리 수정(§20-1과 같은 개발 볼륨 메모) | `CancellationPersistenceSpec` 3819 탐침 6행(operation 3 × SUCCESS·REJECTED, result_code NULL) |
| n-2 | 없음(운영 코드는 이미 준수) | `ArchitectureSpec` K-3 규칙 대상에 `CancellationDispatchService`·`CancellationResultService`·`CancellationRecoveryService` 추가 |
| n-3 | `PaymentCancellationRepository.find`(비잠금 — `entityManager.find`) 추가, 운영자 SAME_REQUEST 판정이 이를 쓴다(잠금 없음). 같은 approvalId·Payment·금액이어도 요청자·승인자가 기존 취소와 다르면 `APPROVAL_IDENTITY_MISMATCH` 거절 + 감사 행(**CN-32**, fail-closed — Reviewer 확인 요청) | `OperatorCorrectionCancelSpec` 새 행: 다른 requestedBy → Rejected(APPROVAL_IDENTITY_MISMATCH)·감사 1, 원래 명령 재요청 → existing·`CANCELLATION_LOCK` 호출 0 |
| n-4 | 위험 힌트 CRITICAL은 이 취소의 예약이 `ReturnExcess.EXCEEDED`일 때만(FORCED·WITHIN은 HIGH). payload에 `excessAmount`(정수 minor — 이 Payment의 누적 초과, 초과 아니면 0) 추가 — `CancellationIncidentDetectedV1Payload.excessAmountMinor`·`OutboxPayloadJson`. CANCEL-CONTRACT-001 §9 필드 추가는 S8 편차 표시 | `CancelFlowSpec` 새 행: 거절된 취소의 늦은 성공 잔액 안 → HIGH·0, 다른 취소로 전액 반환 뒤 → FORCED·EXCEEDED·CRITICAL·4000. 회복 조회 TX 밖 탐침: 시험 대역 `ObservedCancelInquiryPort`(@Primary) — 회복 조회 행에 `transactionActiveAtCall = false` |
| n-5 | 없음 | `CancellationRecoverySpec` CN-08 변형 행: TXd 커밋 성공을 불명확으로 보고(`CommitFaults`) → 보내지 않음(VAN 수신 0) → lease 만료 → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED) → 조회 NOT_FOUND → 프로필 A 종결 FAILED(INSTITUTION_CONFIRMED_NO_EFFECT)·RELEASED / 프로필 B 예산 이관·UNKNOWN·RESERVED |
| n-6 | 이관 뒤 Route가 없고 연령 ≥ maxAge면 느린 조회 중지(일정 closed, UNKNOWN 그대로), 로그 `action=STOP_INQUIRY result=MAX_AGE_ELAPSED reason=ROUTE_NOT_REGISTERED`(보존기간 중지 로그는 `result=RETENTION_ELAPSED reason=-`) | n-7 행 연장: t0+2h 회차 → 로그·일정 closed·UNKNOWN |

- **RED-C**(01:20:49Z, `r3-redc.log-20261020T012049Z.log`): exit 1, `e:` 1건(`APPROVAL_IDENTITY_MISMATCH` 없음).
- **RED-A**(stub — enum 값만 추가, 운영 코드 그대로, 01:22:24Z `r3-reda-20261020T012224Z.log`): 6 Spec **106건 중 7 실패** — m-1(이관 0), n-5(시험 결함 — 아래), n-6(로그 없음), n-4(CRITICAL ≠ HIGH), m-3(감사 0), n-3(Accepted), n-1(3819 아님 → null).
  통과: K17(운영 코드가 이미 준수 — RED는 변이 Mq2), n-2 ArchUnit(이미 준수 — 변이 Mq6), 회복 조회 TX 밖 탐침(이미 준수 — 변이 Mq11). 첫 실행은 `ConfirmFixture`의 불필요 `!!` 경고(-Werror)로 컴파일 실패 → 고친 뒤 재실행(01:21:39Z 로그는 무효).
- **GREEN**(01:29:46Z `r3-green-20261020T012946Z.log`): 8 Spec 133건 중 1 실패 = **시험 결함**: m-3 행의 "pay with space"는 `PaymentId` 형식(비어 있지 않음·64자 이하·올바른 UTF-16)을 만족해 PAYMENT_NOT_FOUND 경로로 갔다 → 65자 값으로 고침(기대 의미 불변).
  재실행 `r3-green-20261020T013358Z.log` OperatorCorrectionCancelSpec 실패 0. RED-A의 n-5 실패도 **시험 결함**: 두 프로필을 한 Then에서 돌려 프로필 B 취소가 시계 t0+3m에 만들어져 t0+31s 회차가 lease 전이었다 → 프로필마다 `clock.reset()`(기대 의미 불변).
- **변이 RED**(`/tmp/mut_r3.py`, `r3-mut-20261020T013627Z`·`…014021Z`·`…014621Z`·`…015029Z.log`) — **13/13 사멸**: Mq1 PREPARED+UNKNOWN fencing(m-1), Mq2 가맹점 취소를 주문의 아무 hold로 차단(K17 — K17 행만 실패), Mq3 형식 아님 감사 제거(m-3),
  Mq4 CancelPayment SUCCESS의 IS NOT NULL 제거·Mq5 상속 CreatePayment SUCCESS의 IS NOT NULL 제거(n-1), Mq6 회복 서비스가 일 한도 Port 의존(n-2 — K-3 규칙 실패), Mq7 요청자·승인자 무시·Mq8 SAME_REQUEST 잠금 읽기(n-3),
  Mq9 FORCED면 CRITICAL·Mq10 초과 금액 미보고(n-4), Mq11 회복 조회를 DB TX 안에서(n-4 탐침), Mq12 lease 만료를 미전송(fence)으로(n-5 — CN-08 변형 행 + 기존 TX2 장애 행), Mq13 Route 없을 때 조회 중지 제거(n-6).
- **바뀐 기존 기대값**: 없음. 추가만: 회복 n-7 행 단언 추가(n-6), 회복 K8 행 TX 밖 단언 추가(n-4), K-3 규칙 대상 3개 추가(n-2), `CancellationPersistenceSpec` 3819 행 6개 추가(n-1).
  `ProgrammableVanTestConfig`에 `ObservedCancelInquiryPort`(@Primary) 추가 — 운영 Adapter를 감싸 호출만 기록. `FaultInjectionConfig` 취소 Repository 대역에 `find` 위임 추가.
- **이벤트 계약 변경**: `CancellationIncidentDetected` v1 payload에 `excessAmount` 필드 추가(추가만 — 기존 필드·의미 불변, 소비자 없음). S8에서 CANCEL-CONTRACT-001 편차로 표시.

### 18.11 S8 — 문서·D-CANCEL-007 안내·`.env.example` sim-van-2·전체 check(구현자 보고 — Verifier/Reviewer 판정 아님)

| 항목 | 변경 |
|---|---|
| 관례 | `ARCHITECTURE-CONVENTIONS.md` 새 §17(CANCEL-001 — Port·Store·Reader 명명, Use Case·TX 단계 서비스, Route·Capability·시간 설정, 잠금 순서, 멱등, Outbox, 운영자 거절 감사, ArchUnit, 시험 대역, Simulator 취소, JPA 역할·예·이유·주의, Kafka/Redis 미도입). 명세 §12의 "§17"과 번호 같음 |
| 정의 | `DOMAIN-DEFINITION.md` Cancellation 행(실행 구조·D-CANCEL1), Refund 행(안내 위치 §4.11), G-5 남은 것(RECON·ADMIN-BOOT-001), 코드 타입 목록 |
| HVC | §7 CANCEL-001 항목(새 모듈·의존성 없음, harness golden 4 → 9, 하한은 Coordinator, `.gitattributes`) + 배포 메모(이전 인스턴스 중지·V15~V20·Simulator V5~V7·종결 경로 상실·LIVE Gate) |
| CC | `CANCEL-CONTRACT-001` 새 §18 구현 편차 표(14행 — 구체화·추가·fail-closed·미구현 구분). 명세 §12의 "CC §18"과 같음 |
| 이 명세 | §4.11 계좌 환불 미지원 API·운영 안내(D-CANCEL-007 — 새 정책 없음, AC 1-j 문서 검토), §20 배포 조건 9항목(LIVE Gate D-CANCEL1-003 포함), AC 1-j 행 위치, D-S5-2·D-S5-4·D-S5-7·D-S6-5·D-S7-4 대체 표시, 입력 hash 갱신(n-2 — ADR-001 끝 4자 표기 오류 정정) |
| 설정 예시 | `.env.example`: Route `SIM-VAN-01` → `sim-van-2`·profile-version `sim-van-a-demo-2`·`cancel` 블록(Simulator 프로파일 A 짝 — `PAYCORE_SIMULATOR_VAN_CANCEL_*`). 취소 timing 블록은 S5에 이미 있음 |
| 시험 | `LocalDemoAuthorizationConfigSpec`: 취소 timing·회복 trigger 바인딩, `CancellationTimingConsistency` 기동 검사, 프로토콜 sim-van-2, profile-version 변경, 취소 Capability = Simulator 짝(무효과 확정·closableAfter·보존기간·부분취소 카드 종류), Capability 판정 Resolved |

- **RED-A**(`.env.example` 그대로, 01:58:57Z `s8-localdemo-reda-20261020T015857Z.log`): 2건 중 1 실패(`expected:<sim-van-2> but was:<sim-van-1>` — 컨텍스트 기동은 성공). **GREEN**(02:00:28Z `s8-localdemo-green-…`) 2/0.
- **바뀐 기존 기대값**: 없음(LocalDemo 시험은 단언 추가만 — 기존 승인 단언 불변. `.env.example`의 승인 Route 값(호스트·키·승인 Capability)은 그대로).
- **전체 실행(최종 소스)**:
  - `:apps:payment-api:check` exit 0 — 02:04:13Z 시작, 7m48s, `s8-full-check-20261020T020413Z.log`, test task 실제 실행(캐시 아님). XML **1437건**, 실패·오류·skip 0(S7 체크포인트 1431 + 검토 #3 행 6: m-1·n-5·n-4·m-3·n-3·K17).
  - `:apps:institution-simulator:check` exit 0 — `cleanTest` + `--no-build-cache`(02:13:13Z, 2m24s, `s8-full-sim-20261020T021313Z.log`). **168건**, 실패 0. 같은 실행 앞의 두 번(`…021217Z`·`…021245Z`)은 test task가
    UP-TO-DATE·FROM-CACHE라 **증거로 쓰지 않는다**(Simulator 소스는 S7 이후 바뀌지 않음).
  - harness `python3 -B -m unittest harness.tests.test_van_contract_golden` 9 OK.
  - Coordinator 하한 참고(구현자는 `commands.json`을 바꾸지 않음): payment-api 1437, institution-simulator 168. 다른 모듈(master-api·financial-batch·internal-admin·libs/test-support)은 이 작업에서 실행하지 않았다 — VERIFY-GATE 전체 check가 센다.
- **남은 것**: VERIFY-GATE(Coordinator), Reviewer 최종 검토(CN-32·AC 1-j 문구·CC §18 편차·Mr8 동등 수용 기록), 커밋 없음.
- **시험 하한(Coordinator, 2026-10-20 — Verifier 판정 아님)**: `harness/commands.json` GRADLE-CHECK·TEST-SUMMARY 1875 → **2092**(payment-api 1,437·institution-simulator 168 = 위 S8 실측, master-api 320·financial-batch 26·internal-admin 29·test-support 112 = 이 작업에서 변경 없음, AUTH-001 VERIFY-GATE 150655Z 실측). HARNESS-SELFTEST 86 → **91**(S3 golden v2, 중간 검토 #2 때 반영). 올리기만 한다.

## 19. rev 0.3 명세 review 대응표(Coordinator 전달 2026-10-20 — 본문보다 이 표가 우선)

| 지적 | 반영(규범 문장) | 단계 |
|---|---|---|
| **H-1** 사건 동일성 | **확정 D-CANCEL1-005**(사용자 답변, 권장안): 사건이 이미 있는 진행 중(OPEN·UNKNOWN) 취소에 새 상충 확정 관찰이 오면 사건 수 +1·`CancellationIncidentDetected` — 상태 불변·자동 완료 없음(CC-16 유지). 같은 기관 결정(같은 Attempt + 같은 결정 ID 또는 같은 PTX)이 다른 출처(RESPONSE·INQUIRY·주입)로 다시 오면 같은 사건(증가 없음). K13을 진행 중·COMPLETED·FAILED 행으로 나누고 각각 출처 간 같은 결정 행 포함. R10은 DUPLICATE뿐 아니라 출처 간 중복 제거. 변이 RED 기록. **구현(S1 보강)**: `PaymentCancellation.repeatsRecordedDecision` + `CancellationResolution`의 세 상태 경로·키 상충 경로 — "같은 결정" 정의는 CN-31 | S1·S6 |
| m-1 | V18·V19(취소 회복 일정 표·권한)를 **S2로 이동**. S5는 S4에 의존. K1~K3의 "VAN 수신 1" 단언은 **S6**으로 | §12 |
| m-2 | V17 type별 CHECK는 MySQL CHECK가 NULL에서 통과함을 막도록 **명시적 IS NOT NULL / IS NULL**: AUTHORIZATION ⇒ `plan_kind`·`card_type`·`approval_number` IS NOT NULL, CANCEL ⇒ `plan_kind`·`installment_months`·`card_type` IS NULL(+ 취소 열 NOT NULL, 승인 행은 취소 열 NULL). CHECK 대상 열에 FK 참조 동작(ON DELETE/UPDATE) 없음. `JpaPaymentTransactionEntity` 승인 열 nullable. K11 행 추가: NULL 승인 열을 가진 AUTHORIZATION root INSERT → 3819. 회귀 시험: CANCEL 행이 승인 복원(`findAuthorizationTransaction`)·승인 view에 영향 없음 | S2 |
| m-3 | `uk_payment_cancellation_approval` 1062는 새 TX에서 재판정 → 기존 행과 `compareRequest` → CONFLICTING_CONTENT(다른 Payment 포함). 실 MySQL 경합 행: 같은 approvalId로 서로 다른 Payment 두 건 동시 → 한 건만 시작, 다른 쪽 CONFLICTING | S2(UNIQUE·변환)·S5(재판정·경합) |
| m-4 | TX1 6단계와 TXd는 현재 Route의 institution·environment·기관 계정 = 원 승인 Attempt 값인지 확인 — 다르면 TX1 503(저장 안 함) / TXd GATE_REFUSED. 시험 추가 | S5·S6 |
| m-5 | ArchUnit: `OperatorCorrectionCancelUseCase`를 서비스 구현·Spring 구성 밖의 어떤 main 클래스(모든 `..adapter..`·다른 모듈)도 부르지 않는다. 위반 fixture 유지 | S5 |
| m-6 | Simulator 잔액 판정은 승인된 취소 수 n과 합 Σ를 **한 SELECT(한 스냅샷)**으로 읽는다 — K15 기대에 포함 | S3 |
| m-7 | sim-van-1 golden sha256을 시험에 고정. v2 안의 sim-van-1 부분 = v1 바이트 동일, sim-van-2 Route로 보낸 승인·조회 프레임 = v1 벡터. 새 승인에 기록되는 protocolVersion "sim-van-2"는 감사 전용 | S3·S4 |
| m-8 | D-CANCEL-007 "계좌 환불 미지원" API·운영 안내를 S8 산출물과 AC 표(AC 1-j)에 추가 | S8 |
| m-9 | **확정 D-CANCEL1-006**(사용자 답변, 권장안): PAYMENT가 운영자 보정 취소 명령을 거절하면(요청자 = 승인자·대상 변경·사유/근거 없음·CANCEL_IN_PROGRESS 등) 판정과 **같은 로컬 TX**에서 append-only 거절 감사 행(approvalId·요청자·승인자 내부 ID·거절 사유 코드·시각, 개인정보 없음). 새 표 `operator_cancel_rejection`(V15)·권한 SELECT·INSERT만(V16, UPDATE/DELETE 1142 탐침). 반복 거절 멱등 = CN-30 | S2(표·Store)·S5(호출) |
| m-10 | 경합 행 추가: K17 같은 주문의 P1 늦은 성공(RECON/관찰) vs P2 가맹점 취소 — 어느 순서든 허용, K18 승인 TX2·회복 vs 취소 TX1(같은 주문) — 교착 없음. K-3 API 시험: 신규 결제 중지·계약 철회·Snapshot lease 만료 중에도 Credential이 유효하면 가맹점 취소 성공 | S5·S6 |
| n-2 | 입력 hash 갱신(§입력 표 — S8에 최종 갱신) | S8 |
| n-3 | `cancel_attempt` 버전 CHECK 엄격: state ∈ {DISPATCHING, OUTCOME_UNKNOWN, RESOLVED} ⇒ `route_profile_version` IS NOT NULL, PREPARED ⇒ NULL, ABANDONED ⇒ (CONNECT_FAILED ⇔ NOT NULL) | S2 |
| n-4 | 복원: OPEN ⇒ incident_count = 0. 불변식 "사건 이벤트 수 = incident_count"(S6 시험 — H-1 답 뒤) | S1·S6 |
| n-5 | golden: `cancelApprovalNumber`가 없으면 **필드 생략**(null 금지 규칙과 일치) | S3 |
| n-6 | Simulator V5 분리: V5 = 새 표 `van_cancel_entry`, V6 = `van_inquiry_log` ALTER, V7 = 권한(runtime-privilege) | S3 |
| n-7 | 회복 때 Route가 없으면 UNKNOWN 유지 + 예산·연령 이관, 종결 요청 보류(로그 `ROUTE_NOT_REGISTERED`) | S7 |
| n-8 | 시험: 행 없이 거절된 merchantCancelId를 새 키로 다시 쓰면 정상 시작 | S5 |
| 검토 #1 | D-CANCEL1-007(같은 결정 = 조건까지 같음), D-CANCEL1-008(거절 행마다 감사, UNIQUE 제거), m-2·m-3 값 상한, n-2·n-3·n-4·n-5, n-1 M2 동등 변이 수용 — §18.2c | S1·S2 |
| S3·S4 반영(검토 #2 대상) | m-6 = `JpaVanCancelLedgerStore.approvedTotals` 한 JPQL SELECT + K15(Ms1·Ms2·Ms4 사멸), m-7 = sim-van-1 sha256 고정(harness·`VanFrameCodecV2Spec`) + sim-van-2 Route 승인 프레임 = v1 벡터(`SimulatorVanCancellationAdapterSpec`), n-5 = 승인번호 없으면 생략·null은 FRAMING(Ms8·Mp8 사멸), n-6 = V5 표 / V6 ALTER / V7 권한, m-4 = `CancellationCall` 불변식 + Adapter Route 기관·환경 거부 + `servesOriginal`(TX1·TXd 연결은 S5·S6) — §18.4·§18.5 | S3·S4 |
| n-9 | `payment_transaction.recorded_by`: CANCEL은 RESOLUTION. RECON이 취소 거래를 기록하게 되면(FORCED 출구) RECONCILIATION을 쓴다 — RECON 인계 메모 | S2 |
| 검토 #3 | m-1 PREPARED+UNKNOWN → 조회·이관, m-2 K17 실제 MySQL 경합(두 번째 Payment fixture), m-3 형식 아닌 paymentId 감사(`MALFORMED`), n-1 V20 result_code IS NOT NULL, n-2 K-3 대상 확대, n-3 비잠금 SAME_REQUEST·신원 불일치 감사(CN-32), n-4 CRITICAL = EXCEEDED·`excessAmount`·회복 조회 TX 밖 탐침, n-5 CN-08 변형, n-6 Route 없음 maxAge 중지 — §18.10 | S5~S7 |

## 20. 배포 조건(S8 — Coordinator 판정 rev 0.2 §15-4·§15-8, HVC §7 배포 메모와 같은 내용)

이 절은 배포 절차의 **전제 조건**을 적는다. 이 작업은 배포·운영 DB·운영 키를 다루지 않으며(CLAUDE.md), 아래 값은 Demo다. 배포 파이프라인이 생기면 이 조건을 그 절차의 검사로 옮긴다.

1. **개발 볼륨 메모(mid-review #1 n-5·검토 #3 n-1)**: CANCEL-001의 migration 중 PAYMENT `V15`·`V16`·`V20`이 미커밋·미배포 상태에서 **제자리 수정**됐다(Simulator `V5`~`V7`은 제자리 수정 없음 — 최종 review n-1) —
   rev 0.3에서 `operator_cancel_rejection` 표·권한을 V15·V16에 넣었고(D-CANCEL1-006), 중간 검토 #1에서 그 표의 UNIQUE를 지웠으며(D-CANCEL1-008), 검토 #3에서 V20 CHECK에 `result_code IS NOT NULL`을 더했다(n-1).
   수정 전 파일을 적용한 로컬 개발 볼륨은 Flyway 검증(checksum)이 기동을 막을 수 있다 — 개발 산출물이며 운영 볼륨은 없다. 처리(볼륨 재생성 등)는 개발자 판단이고 `flyway repair`로 checksum을 덮지 않는다.
   시험 컨테이너는 매번 새로 만든다.
2. **이전 payment-api 인스턴스 모두 중지(혼재 금지)**: Route 설정을 `sim-van-2`·취소 Capability(`capabilities.cancel`)·**새 profile-version**으로 바꾸므로 CANCEL-001 이전 payment-api(등록부가 `sim-van-1`만 허용)는
   새 설정으로 기동하지 못하고, 이전 코드는 `CancelPayment` 멱등 행·`result_cancellation_id`·취소 표를 모른다. 이전 인스턴스를 모두 내린 뒤 새 인스턴스를 올린다(AU §20-2와 같은 형태).
3. **migration 순서**: `CheckoutWindowCheckMigrationStrategy` 불변 — V15~V20은 "나머지 전부" 단계(V15 새 표 → V16 권한 → V17 기존 표 ALTER(승인 거래 행이 새 CHECK 통과 — `OldVolumeStartSpec`) → V18 취소 회복 일정 →
   V19 권한 → V20 `idempotency_request` ALTER(DROP + ADD CHECK 한 문장)). Simulator는 V5(취소 원장 표) → V6(`van_inquiry_log.operation`) → V7(권한). 기존 Simulator 볼륨은 V7이 권한 procedure를 호출하므로
   초기화 스크립트 재실행이 필요 없다(§8.6 (4)). Simulator는 같은 VAN 포트에서 두 계약을 받으므로 먼저 올려도 PG `sim-van-1` 경로는 그대로 동작한다.
4. **필수 설정(기본값 없음 — 없으면 기동 실패)**: `paycore.cancellation.timing.{dispatch.*, recovery.*}`(recovery.version 포함 — CN-29), Route `capabilities.cancel.{version, partial-lump-sum-card-types,
   partial-installment-card-types, confirms-no-effect, closable-after, inquiry-retention}`(sim-van-2 Route만 — sim-van-1 Route에 블록이 있으면 기동 거부, D-S4-1). 기동 검사 `CancellationTimingConsistency`(connect 예산 <
   lease − margin, cancel.closableAfter ≥ preparedDeadline + lease, 회복 maxAge < cancel.inquiryRetention — D-S4-2). PG Route의 cancel 값은 Simulator 프로파일의 취소 값과 짝이어야 한다(`.env.example`
   `PAYCORE_SIMULATOR_VAN_CANCEL_*` — `LocalDemoAuthorizationConfigSpec`).
5. **승인 UNKNOWN의 종결 경로 상실 수용(F-8 — Coordinator 수용)**: 승인 Route profile-version 변경(예시 `sim-van-a-demo-1` → `sim-van-a-demo-2`)으로 배포 시점에 진행 중인 승인 UNKNOWN Attempt는 고정 버전이
   현재 버전과 달라 무효과 종결 요청 없이 조회·이관만 한다(D-AUTH1-007, fail-closed). 같은 이유로 배포 뒤 Route 모양을 바꿀 때마다 profile-version을 올리고, 그 시점의 진행 중 취소 UNKNOWN도 조회·이관만 한다(D-CANCEL1-004).
6. **LIVE Gate 조건(확정 D-CANCEL1-003)**: 이관된 취소 UNKNOWN(`CancellationOutcomeUnresolved`)에는 임시 출구가 없다 — 취소는 UNKNOWN·예약 RESERVED로 남고 그 Payment의 다음 취소가 막힌다.
   **이를 확정하는 RECON Use Case(RECON-CONTRACT-001/RECON-001) 전에는 LIVE 불가.** 같은 이유로 CANCEL_RESULT_CONFLICT hold 해제·FORCED 출구도 RECON 전까지 거절된다(D-CANCEL-006·011).
7. **운영자 보정 취소**: provided Use Case만 있고 진입점이 없다(D-CANCEL1-001) — ADMIN-BOOT-001 전에는 운영에서 호출할 수 없다. 형식이 아닌 OperatorId·approvalId의 거절 감사는 ADMIN-BOOT-001 인계(Coordinator 기록).
8. **Demo 값**: 취소·회복 시간(lease 30s·회복 예산 3·maxAge 1h·느린 조회 10m 등), 취소 Capability(부분취소 카드 종류·closableAfter 2m·보존기간 7d), Simulator 취소 시나리오·카드·취소 기한(P365D)은 Demo이며 운영값이 아니다.
9. **관측**: 배포 뒤 `paycore.cancellation.unknown`·`.unknown.oldest_age_seconds`·`.escalated` gauge와 `CancellationOutcomeUnresolved`·`CancellationIncidentDetected`(CRITICAL = 초과) 이벤트를 운영 경보 대상으로 둔다(경보 규칙은 운영 관측 작업).
