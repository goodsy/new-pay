# AUTH-001 가상 TCP 승인·조회·UNKNOWN 회복 구현 명세 (rev 0.8 — S8 gap 대응·D-AUTH1-007)

> **rev 0.8 갱신(2026-10-19, 구현자)**: §16.17 발견 1~4 대응(관찰 지표·로컬 Demo 승인 설정 예시·Simulator 할부 Demo 정렬·main yml 주석)과 사용자 결정 **D-AUTH1-007**(Q-S8-1 = A — 전송 때 Route
> profile version을 Attempt에 고정, 회복은 버전 일치 때만 무효과 종결 요청) 구현. 본문 변경: §5.8 끝, §6.1 ③, §9 Auditability·Observability, §20-1(V14)·§20-3·§20-6(임시 전제 대체). 기록 §16.18.

> **rev 0.7 갱신(2026-10-19, S8 — 문서만, 코드·시험·정책 변경 없음)**: §16.17(S8 기록), §20 배포 조건(신설 — rev 0.6 머리말이 예고한 절), §7.1·§7.3·§7.4 끝에 "S8 현행화" 주석(실제
> migration 번호·Port 이름 — 본문 계획 문장은 기록으로 둔다). rev 0.6이 적은 본문 변경(§4.11-2·§6.1·§6.2)은 코드와 대조해 이미 반영돼 있음을 확인했다(§16.17). 다른 문서:
> `ARCHITECTURE-CONVENTIONS.md` §16, `DOMAIN-DEFINITION.md`, `HARNESS-VERIFICATION-CONTRACT.md` §7, `AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md` §19.10. 미결 질문 후보 1건(§16.17 Q-S8-1).
>
> **rev 0.6 갱신(2026-10-18)**: 검토 #3(PASS_WITH_FIXES) 대응과 사용자 결정 **D-AUTH1-006**(Q-AUTH1-06 — NO_EFFECT 해제도 같은 재개설 규칙) 반영은 §16.16, S8(시험 하한·하네스 문서·배포 조건)은 §16.17.
> 본문 변경: §4.11-2(D-AUTH1-006), §6.1(구현 편차 D-S6-1·D-S6-2·D-S7-1을 본문에 반영 — n-9, lease 재판정 m-1, 보존기간 m-2, 설정 버전 m-4), §6.2(설정 버전 기록 — m-4), §20 배포 조건(신설).

> **rev 0.5 갱신(2026-10-18)**: S5(confirm API·TX1)·S6(TXd·기관 호출·TX2)·S7(회복 Worker) 구현 기록과 중간 검토 #2(PASS_WITH_FIXES) 대응은 §16.9~§16.13. 명세 본문 변경은
> §5.4 n-3 Demo 규칙(보존기간 지난 ULID의 새 AUTH 거부), §8 C21 기대값 정정(경합 승자 순서)뿐이다(m-B Route 환경 대조는 §16.10).
> 구현 편차(D-S6/S7-x)는 §16.13 표 — Reviewer 판정 대상. m-D는 사용자 답변 D-AUTH1-005로 구현(§4.11-2·§16.14).

> **rev 0.4 갱신(2026-10-18)**: U-1 사용자 답변 **D-AUTH1-004**(AU-26 미채택 — 게이트는 인증 증거 TTL을 보지 않고 VAN 재검증이 만료 증거를 확정 거절) 반영(§4.4·AU-26·§13.3).
> 중간 검토 #1(S1·S2, CHANGES_REQUIRED) 대응은 §16.5: H-1 hold 여는 관찰 = 관찰 키 + **variant**(`ObservationRef`, V11 `source_variant`), m-1 승인을 시작하지 않은 Payment는
> 결제창 만료 근거만 덧붙일 수 있음(`Payment.authorizationStarted`), m-2 기관 영업일 `institutionBusinessDate`(nullable, 의미는 Q-CALENDAR), n-1~n-6. S3·S4 실행 기록은 §16.6 이후.
>
> **rev 0.3 갱신(2026-10-18)**: rev 0.2 review(H-1~H-3, m-1~m-9, n-1~n-9)와 Coordinator 판정을 반영한다 — 대응표는 §17. 요지: H-1 해제 판정이 설명하는 승인은
> **그 hold를 연 관찰**뿐(hold → 여는 관찰 링크 필수), H-2 AU-26(게이트의 인증 증거 TTL 검사)은 사용자 질문 U-1 답 전까지 구현하지 않음, H-3 PENDING 요청은 무효과 종결 대상이
> 아님(조회 결과 PENDING 추가·종결과 결정 확정이 같은 UNIQUE에서 경합), m-1 `at`은 사용량 잠금 전에 정하고 날짜가 바뀌면 TX 재시작, m-9 head(S)는 provided
> `lockHeadForShare(merchantId)`로 먼저 잡는다(AU-04 예비 평가 삭제), Coordinator 판정: U-2 AU-17 유지, AU-22 채택, 게이트의 일시 Admission Unavailable은
> `preparedDeadline`까지 TXd 재시도 뒤 GATE_REFUSED(AU-33). AC는 tasks.json 기준 **24개**(§2).


> **rev 0.2 갱신(2026-10-18)**: §13 질문 3개가 답변됐다 — **D-AUTH1-001**(LimitPolicyUnavailable = 503, 저장 없음), **D-AUTH1-002**(승인은 KRW만),
> **D-AUTH1-003**(권장안 아님 — 신용 일시불·체크 일시불·일반 할부를 **모두 AUTH-001에서 개방**, D-AUTH-001의 개방 순서를 대체, 체크카드 할부는 계속 거절).
> Coordinator 판정(§14): AU-20 채택, 통보(push) 채널 없음(후속), TLS는 LIVE Gate, 제목의 "취소"는 CANCEL-001(AUTH-001 바로 다음), V11 배포 조건을
> 이 명세와 HARNESS-VERIFICATION-CONTRACT §7에 기록, MS2 n-3/n-5는 범위 밖(후속 후보), AU-17·AU-18·AU-27·AU-04 채택, F-2는 S1에서 재조사·기록,
> timeout 단계 모듈 시험 30분·VERIFY-GATE 90분. 아래 rev 0.1 문장 중 "Q-AUTH1-xx"·"권장"은 이 결정이 우선한다. 바뀐 곳: §0·§1.2·§2(AC 10·15·17)·§4.3·§4.8·§4.9·
> §5.3·§5.8·§7.4·§11·§12·§13·§14.

rev 0.1 작성 2026-10-18 KST, 기준 commit `481a9fa`(CANCEL-CONTRACT-001, branch `feat/foundation-setup`). **1단계 명세이며 코드·시험 변경 없음.** 작업 트리에는 Coordinator 소유
`harness/tasks.json`·`harness/progress.json` 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 금융 정책을 채택하지 않는다 — §13 질문 후보는 답변 전 해당
운영 동작을 구현하지 않는다. 2단계 TDD는 Coordinator가 질문을 처리한 뒤 시작한다.

표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 적용(새 정책 아님 — 의심되면 Coordinator가 질문으로
올린다), **구현 결정(AU-xx)** = 금융·보안 의미가 없는 가역적 선택(§12), **미결(Q-AUTH1-xx)** = §13 질문 후보, **Demo** = Simulator·시험 전용 값(운영값 아님),
**범위 관찰** = 작업 경계 확인 사항(§14), **발견(F-x)** = 이번 조사에서 찾은 기존 코드·문서의 공백(§3.2).

입력 문서(sha256 앞 8자…끝 4자, 2026-10-18 읽은 시점 작업 트리):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001~013, D-AUTHN-001~008, D-CANCEL-001~013, D-MS2-001~006, D-CPC-*, D-CPD-*, D-CP-*, D-MD-017·018) | 확정 | `1eeb57c3…f132` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 작업 | `harness/tasks.json` AUTH-001 AC **24개**(rev 0.3 — D-AUTH1 3행 추가)·allowedWritePaths, AUTH-CONTRACT-001 openItems, CANCEL-001 | Coordinator 소유(미커밋) | `c4e48744…f164` |
| 질문 | `harness/questions.json`(열린 질문 중 이 작업을 막는 것 없음 — §13.3) | Coordinator 소유 | `62f99629…4b1d` |
| AC1 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(권위 계약, §4~§11·§17~§19) | 구현 반영 명세 | `fbd5d4a0…abee` |
| AN | `docs/repository/AUTHN-001-PAYMENT-WINDOW.md`(§7.5·§10·§13·§14·§22 인계) | 구현 반영 명세 | `319f4402…63ef` |
| CC | `docs/repository/CANCEL-CONTRACT-001-CANCELLATION-CONTRACT.md`(§7·§9·§14.1 인계) | 구현 반영 명세 | `d16b0917…47c6` |
| MS2 | `docs/repository/MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md`(§5.5·§6·§7·§13.2 이관) | 구현 반영 명세 | `f8f694b5…4daf` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§9·§9.2·§12·§14·§15) | 관례 | `af98a40c…0609` |
| 매핑 | `docs/repository/ARCH-STRUCTURE-001-MAPPING.md` | 관례 | `4bb8712b…73fc` |
| 정의 | `docs/repository/DOMAIN-DEFINITION.md` | 색인 | `d32dc7dc…b4de` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §4·§5 | 확정 | `a8b02b77…7ae5` |
| 시도 | `docs/baseline/PayCore-결제시도-규칙-권장안.md` | 확정 | `9151261e…f5c1` |
| 초기 | `docs/baseline/PayCore-초기-확정사항.md`(가상 VAN = TCP 소켓) | 확정 | `818cc7a7…45c5` |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md` §1~§3(D-AUTH-001로 채택) | §1~§3 확정 | `3cf08d32…11be` |
| A04·A06 | `docs/baseline/architecture/04-external-institution-integration.md` `21ca0f04…baf8`, `06-reliability-recovery-architecture.md` `f0f57739…54c1` | 아키텍처 자료 | — |
| 코드 | `payment/domain/PaymentAuthorization.kt` `823d899e…9d55`, `AuthorizationResolution.kt` `93a27a20…0993`, `AuthorizationStart.kt` `0a5141c5…acc5`, `OrderPaymentControl.kt` `9066a41e…72f4`, `DailyLimitUsage.kt` `893cfc9e…7798`, `IncidentHoldSettlement.kt` `070134d7…83cc`, `Payment.kt` `ec499619…1f35`, `application/RequestedPaymentClosing.kt` `bb5f8982…9165`, `application/ReportSuccessAfterTerminationService.kt` `0d42614d…c745`, `adapter/persistence/CheckoutWindowCheckMigrationStrategy.kt` `381683e4…4f41`, `adapter/persistence/JpaOrderPaymentControlRepository.kt` `e653de21…945e`, `V1__payment_core.sql` `9e2b9dca…6545`, simulator `V1__authentication.sql` `8cf7792f…6615` | `481a9fa` | — |

---

## 0. 요약

- **흐름**: 가맹점 서버 `POST /v1/payments/{paymentId}/confirm`(Bearer, Idempotency-Key) → **TX1**(결제창 시각 경과면 인라인 만료 후 커밋·`PAYMENT_TERMINATED`; 아니면
  승인 시작 허용 판정·`AuthorizationStart`·일 한도 점유·Attempt PREPARED) → **TXd**(전송 게이트: A0~A2 재판정 → Attempt DISPATCHING) → **TX 밖** TCP 전송 →
  **TX2**(관찰 append + `AuthorizationResolution` + Outbox). 제한 시간 안 확정이면 200, 아니면 202, 남은 처리는 회복 Worker.
- **가상 VAN**: `apps/institution-simulator`에 새 모듈 `van` — **TCP**(초기 확정 §59, 허용 §5.1, D-AUTHN-004 "AUTH-001 가상 VAN(TCP)도 이후 여기"). 길이 접두 프레임 +
  HMAC 시험 키, 승인·조회(원 요청 ID), 기관 원장(append-only)·무효과 종결 표지(tombstone)·Capability 프로파일 2종(무효과 확정 있음/없음). 취소 전문은 CANCEL-001.
- **UNKNOWN 회복**: jOOQ 후보(비권위) → 건별 JPA 잠금 재판정. 자동 FAILED는 기관 확정 거절·기관 무효과 확정(Capability)·전송 전 실패 증명(게이트·fencing·connect 실패)뿐.
  timeout·NOT_FOUND·lease 만료는 UNKNOWN 유지, 예산 소진 시 `AuthorizationOutcomeUnresolved`(대사 이관) — 상태 불변.
- **영속화**: PAYMENT V9(새 표) → V10(권한) → V11(기존 표 ALTER) → V12(권한), 기존 기동 전략 순서(V8 → pin·채우기 → 재확인 → 나머지) 유지.
- **AC 21개 전부 대응**(§2). 질문 3개는 답변됨(§13): D-AUTH1-001 한도 정책 없음·모호 = 503 저장 없음, D-AUTH1-002 승인 KRW만, D-AUTH1-003 신용 일시불·체크 일시불·일반 할부 모두 개방
  (체크카드 할부 거절 유지, 체크·할부의 취소 검증은 CANCEL-001).
- **발견 7건**(§3.2): 사건 이벤트 effect_key 충돌(F-1), L-3 영향 시험 파일이 6개 → 11개 이상으로 늘어남(F-2), `ProviderRequestRef.toString`이 기관 계약계정 원문 노출(F-3) 등.
- **규모**: AUTHN-001의 약 1.2~1.5배(시험 +330~480건 추정, §11).

---

## 1. 범위와 범위 밖

### 1.1 범위

| 구분 | 내용 | 근거 |
|---|---|---|
| API | 가맹점 서버 confirm `POST /v1/payments/{paymentId}/confirm`(MID Bearer, Idempotency-Key), 오류·응답 매핑, 같은 키 현재 결과 재생 | AC1 §4.4·§10.1, D-AUTH-004·005, CA-18 |
| Use Case | 승인 시작(TX1, 인라인 만료 포함) → 전송 게이트(TXd) → 기관 호출(TX 밖) → 결과 반영(TX2), UNKNOWN 회복 Worker(조회·대사 이관) | AC1 §4·§7, AC 1·2·5·18 |
| domain 보강 | L-3 REQUESTED 종료 근거 출처 제한(S7)과 기존 시험 재설계, m-10·m-11, n-3, `PaymentAuthorization.reconstitute`(FAILED + 거래 포함 — CC nit 4), 승인 시작 사전 검사, 사건 신규 여부 판정 | AC 6·8·10·13, CC §14.1 |
| 영속화 | PAYMENT V9~V12: Authorization·Attempt·관찰·거래·일 한도 사용량·예약·해제 기록·회복 일정 표, `incident_hold` occurrence·reason 확장, 종료 근거 출처·시각·행위자, 멱등 CHECK 확장, 표 단위 권한 | AC 9·14·16·19 |
| 기관 연동 | 가상 VAN TCP Adapter(프레이밍·상관관계·무결성·connect/read/전체 deadline), Route 프로파일 등록부(모르는 routeRef fail-closed), Simulator/REAL 격리 | AC 3·15, AC1 §9 |
| Simulator | `apps/institution-simulator` 모듈 `van`: TCP 서버, 승인·조회, 기관 원장(append-only)·무효과 종결 표지, 시나리오, 인증 근거 재검증, 표 단위 권한 재검토, 재시작 내구성, 계약 golden | AC 1·17, 허용 §5.5 |
| Outbox | `PaymentAuthorized`(FINANCIAL_FACT)·`PaymentAuthorizationFailed`·`AuthorizationOutcomeUnresolved`·`AuthorizationIncidentDetected`(occurrence 포함) | AC1 §11, AC 12 |
| 시험 | 실제 MySQL 경합(C8·confirm vs 만료·동시 confirm·한도)·rollback·응답 유실·재시작, 8대 원칙 증거 | AC 1·2·20, CLAUDE.md |

### 1.2 범위 밖

| 항목 | 소유 | 근거 |
|---|---|---|
| 취소 API·영속화(`payment_cancellation`·`return_balance`)·가상 VAN 취소 전문·취소 UNKNOWN 회복 | CANCEL-001 | CC §14 Coordinator 판정(rev 1.1) — AUTH-001 제목의 "취소"는 이 판정으로 대체 |
| hold 해제 실행 Use Case·2인 승인 연동·재대사 MATCH·늦은 승인 거래 기록(`recordLateApprovalByReconciliation` 호출자) | RECON-CONTRACT-001 / ADMIN | D-AUTH-002·011, D-CANCEL-010~012 |
| 결제 조회 API(GetPayment) | CP-QUERY-001 | AC1 §10.2 |
| 서명 Webhook | WEBHOOK-001 | AC1 §10.2 |
| 매입·정산·대사 Case·FINANCIAL Inbox·Outbox Relay | ACQUIRING-001·SETTLEMENT·RECON·Relay 작업 | AC1 §11 |
| 기관 → PG 통보(push) 채널 | 범위 관찰 §14-1(권장: 후속) | — |
| 실제 VAN 전문·인증서·TLS·기관 인증 시험·REAL Adapter | LIVE 준비 Gate | 허용 §5.6, D-CPD-001 |
| ~~체크카드 일시불·일반 할부의 개방~~ → **범위 안으로 이동**(D-AUTH1-003: 신용 일시불·체크 일시불·일반 할부 모두 AUTH-001에서 개방, 체크카드 할부는 거절 유지) | AUTH-001 | D-AUTH1-003 |
| 체크카드·할부 승인 건의 **취소·환불 검증** | CANCEL-001(AUTH-001 바로 다음) | D-AUTH1-003 — CANCEL-001 전까지 체크·할부 승인의 취소 검증은 없다(가상 기관 TEST 한정, 사용자 재확인) |
| MS2 review round1 n-3·n-5(선택 항목) | 후속 후보 | Coordinator 판정 2026-10-18 |

### 1.3 CANCEL-001에 남겨 둘 준비(CC §7·§9·§14.1)

| 준비 | 이 작업의 처리 |
|---|---|
| `PaymentAuthorization` 영속 복원 | `reconstitute`가 **FAILED + 거래**(D-CANCEL-011, RECON 기록분)를 받아들인다(CC nit 4). 승인 거래는 공용 `payment_transaction` 표에 저장 |
| `payment_transaction` 표 | 공통 열(transaction_id·type·payment_id·authorization_id·amount·currency·confirmed_at·출처) + 승인 열. `type` CHECK는 지금 `AUTHORIZATION`만, CANCEL-001이 `CANCEL`과 취소 열을 ALTER로 추가(쓰지 않는 값·권한을 미리 두지 않음, 관례 §9.2) |
| 가상 VAN 전문 | 메시지 `type`·operation namespace(`AUTH`·`INQUIRY`, 취소는 `CANCEL`·`CANCEL_INQUIRY` 예약), 원장 표의 `operation` 열 — CANCEL-001이 같은 서버·같은 프레이밍에 메시지만 추가 |
| Route 프로파일 | operation별 Adapter ID·Capability 묶음(`authorize`·`inquire`), CANCEL-001이 `cancel`·`cancelInquire`·`partialCancelSupported` 등 추가 |
| 잠금 순서 | 승인 TX2·회복은 `order_payment_control`부터(§4.7), `return_balance`를 건드리지 않음(CC CC-02 지연 개설) → 승인·취소 경로 교착 없음 |
| `incident_hold` reason CHECK | 이 작업은 `CONFLICTING_INSTITUTION_RESULT`까지 추가. `CANCEL_RESULT_CONFLICT`는 CANCEL-001(CC §14.1) |
| 회복 Worker 골격 | 후보 Reader·건별 TX·회복 일정 Store 모양을 취소 회복이 재사용할 수 있게 operation 열을 둔다 |

---

## 2. AC 대응표(tasks.json AUTH-001 → 출처 → 기존 코드 → 공백·이 작업)

| # | AC(요약) | 출처 | 기존 코드가 지원하는 것 | 공백 → 이 작업 | 절 | 계획 시험(이름은 가역적) |
|---|---|---|---|---|---|---|
| 1 | 연결 단절·응답 유실·중복 요청·지연 결과 | 허용 §5.3·§5.5, AC1 §6·§7 | domain: timeout·NOT_FOUND → UNKNOWN 유지, 중복 관찰 DUPLICATE(`AuthorizationResolution`) | TCP Adapter·Simulator 시나리오·회복 Worker 전부 없음 | §5, §6, §8 | `VanFramingSpec`(부분 read·복수 프레임·끊김), `UnknownRecoveryInquirySpec`, `AuthorizationCommitFailureRecoverySpec`, `LateResponseSpec`, `DuplicateRequestSpec`(같은 요청 ID 재전송·다른 payload 충돌) |
| 2 | 외부 호출은 로컬 TX 밖 | CLAUDE.md, A02, AC1 §4.1 | `LocalTransactions`(TX 안 호출 거부), AUTHN `LookupOutsideTransactionSpec` 선례 | confirm 조정자 서비스가 TX를 열지 않음을 시험 | §4.2·§4.5 | `VanCallOutsideTransactionSpec`(호출 중 `@@in_transaction`=0·`data_locks` 0, 다른 confirm 대기 없음) |
| 3 | REAL endpoint 접근 없음 | D-CPD-001, AC1 §9.4 | 인증 쪽 `AuthenticationBackendIsolationSpec`·ArchUnit `cardAuthenticationPortImplementedOnlyBySimulatorAdapter` | 승인 Port의 같은 방어 | §5.6 | `VanBackendIsolationSpec`(REAL·LIVE→SIMULATOR·모르는 type 기동 거부, 전송 0), ArchUnit `authorizationPortsImplementedOnlyBySimulatorAdapter` + 위반 fixture `RealVanAuthorizationAdapter` |
| 4 | (L-2) 승인 시작 단일 연산 또는 강제 application 시험 | CP-DOMAIN-001 L-2, AC1 §4.2-3 | `AuthorizationStart.start` + ArchUnit `controlStartAuthorizationOnlyThroughAuthorizationStart`(위반 fixture 4) | application이 TX1에서 `AuthorizationStart`만 부르는지·rollback 원자성 | §4.3 | `ConfirmStartRollbackSpec`(TX1 각 Port flush 뒤 실패 주입 → 모든 행 없음), 기존 ArchUnit 유지 |
| 5 | (L-3) Attempt 생성 + AUTHORIZING 같은 TX1, 전송은 커밋 뒤 | L-3, 시도 §5 | domain `PaymentAuthorization.open`이 Attempt PREPARED 포함, `AuthorizationStart`가 같은 연산 | TX1 영속·TXd 분리 | §4.3·§4.4 | `ConfirmStartRollbackSpec`, `DispatchAfterCommitSpec`(TXd 전 Simulator 수신 0) |
| 6 | (L-3) 출처 제한 시 PaymentFixture·판정표 같은 변경에서 갱신 | L-3 | `PaymentFixture`의 REQUESTED 경로 fixture, `OrderPaymentDecisionTableSpec` | 출처 제한 구현 + fixture·판정표 갱신 | §4.11-1 | `PaymentSpec`·`TerminationEvidenceSpec`·`OrderPaymentDecisionTableSpec` 갱신 행 |
| 7 | (L-6) `ReportSuccessAfterTerminationService` 실패 분류·호출 경로(Inbox) | CP-DB-001 L-6, AC1 §17-8 | 서비스 존재(호출 경로 없음, 대상 없음 → `NoSuchElementException`) | **흡수·삭제 권장**(AU-18): 늦은 성공은 관찰 키를 가진 TX2 Resolution 경로로만. 통보 Inbox는 통보 채널 작업(§14-1) | §4.11-6 | `IncidentHoldConvergenceSpec`을 Resolution 경로로 재작성(같은 늦은 성공 8회 동시 → hold 1·사건 이벤트 1) |
| 8 | (S7·§17-3) REQUESTED에서 DECLINE·NO_EFFECT·PRE_TRANSMISSION·TIMEOUT·INQUIRY_NOT_FOUND·LEASE_EXPIRED 근거 종료 거부 + 기존 시험 재설계, legacy 행 복원이 완화 아님을 Reviewer 확인 | AC1 §19.3 | 없음(지금 REQUESTED는 근거를 그대로 받음, `Payment.kt` KDoc) | domain 제한 + **영향 파일 재조사(F-2: 6개 → 11개 이상)** | §4.11-1 | 재설계 시험 각 행, legacy 행은 `Payment.reconstitute`·직접 INSERT로 준비, Reviewer 확인 항목 |
| 9 | (§17-7·8) 멱등 result_code CHECK 확장, 종료 근거 출처·시각·행위자 열, RSAT 호출 경로·분류·사건 Outbox | AC1 §17-7·8 | V1 CHECK가 CreatePayment 코드만, `payment_termination_evidence`(seq·evidence만) | V11 ALTER, 사건 Outbox는 TX2 경로 | §4.8, §7.1 | `AuthorizationSchemaSpec`(CHECK 위반 INSERT), `TerminationEvidenceSourceSpec` |
| 10 | (n-2·n-3·n-4) LimitPolicyUnavailable 응답 통일, lease 회복·TX2 경합 시 markOutcomeUnknown, 해제 표 도입 전 Store 거부·reconstitute 기본값 제거 | AC1 review1 | domain `LimitPolicyUnavailable`(Rejected), `markOutcomeUnknown`은 최종 상태에서 예외, `reconstitute(…, incidentHoldReleases = emptyList())` | n-2 = **D-AUTH1-001**(503, 저장 없음 — Admission Unavailable과 같은 응답), n-3 = AU-25, n-4 = 해제 표 도입 + 기본값 제거 + Store는 저장 안 된 해제 거부(쓰기 경로 없음) | §4.9, §4.11-3·4 | `ConfirmErrorMappingSpec`, `DispatchLeaseRaceSpec`, `ControlStoreReleaseSpec` |
| 11 | (m-8·M-2) hold 해제/승인 Use Case는 Authorization 있으면 함께 전달(시험 기준), sourceResultId Adapter 계약 | AC1 review2 | `IncidentHoldSettlement.release(…, authorization?, usage?)` | 해제 Use Case는 RECON — 이 작업은 **시험 기준 문서화** + Authorization 있는 hold 복원 시 함께 로드되는지 Store 시험. sourceResultId 정의(§5.7.3) | §5.7.3, §4.11-5 | `VanResultMappingSpec`(sourceResultId 표), `AuthorizationRepositorySpec` |
| 12 | (m-9) 사건 effect_key에 occurrence, `Authorized.incident` 시 PaymentAuthorized + 사건 이벤트 같은 TX | AC1 review3 | domain `ResolutionOutcome.Authorized(transaction, incident)`, `IncidentHold.occurrence` | effect_key `AuthorizationIncidentDetected:{paymentId}:{reason}:{occurrence}`, **새 hold일 때만 발행(F-1)** | §4.10 | `AuthorizationOutboxSpec`(이중 승인 TX에 이벤트 2, 같은 활성 hold 재보고 이벤트 0·1062 없음, rollback 0) |
| 13 | (m-10·m-11) REAL/DOUBLE 해제 뒤 같은 terms 재보고 NoChange, 해제 기록 중 REAL/DOUBLE 있으면 FORCED→CONSUMED | D-AUTH-013·010 문구 | `openHold`는 해제 뒤 항상 새 hold, `IncidentHoldSettlement`는 이번 해제 판정만 봄 | domain 수정(§4.11-2) | §4.11-2 | `AuthorizationResolutionSpec`·`IncidentHoldReleaseSpec` 추가 행 + 변이 RED |
| 14 | (§19.9) incident_hold occurrence UNIQUE·해제 기록 표·FORCED/excess 열·Store append-only 검사 | AC1 §19.9 | V1 UNIQUE(OrderKey, payment_id, reason), Store append-only 검사가 (paymentId, reason) 쌍 | V11 ALTER + `incident_hold_release` + `limit_reservation`(state·excess) + Store 검사에 occurrence | §7.1 | `IncidentHoldSchemaSpec`(occurrence 2 INSERT 허용·같은 occurrence 1062), `ControlStoreAppendOnlySpec` |
| 15 | (MS2 이관) evaluatedAt = at, Unavailable/Refused/LimitPolicyUnavailable HTTP, 게이트 A0~A2 재판정, FORCED 초과 표시 = 원 Authorization 고정 정책, Route 프로파일 등록부(모르는 routeRef fail-closed, Attempt에 institutionMerchantRef — 마스킹) | MS2 §6.4·§7·§13.2 | `AuthorizationAdmissionPort`(evaluatedAt 인자), `AdmissionResult.admission(protocolVersion)` | 전부 application·adapter. HTTP: Unavailable·LimitPolicyUnavailable = 503 저장 없음(D-AUTH1-001), Refused·미지원 통화 = 422 `PAYMENT_NOT_ELIGIBLE` 저장(D-AUTH1-002) | §4.3·§4.4·§4.9·§5.8 | `ConfirmAdmissionTimeSpec`(Port 기록 `evaluatedAt == authorization.openedAt`), `DispatchGateSpec`, `ForcedExcessPolicySpec`, `RouteProfileRegistrySpec`, 로그·toString 마스킹 시험 |
| 16 | (AUTHN 인계) V9부터, authenticationRef로 세션 조회, bindingRejection(providerValidUntil)·bindTo, bound_authorization_id 쓰기 경로, C8 | AN §22-4·8 | `bindingRejection`·`bindTo`·`authentication_ref` UNIQUE·`bound_authorization_id` UNIQUE 열(V7) | 조회 Port 추가, 귀속 저장, C8 | §4.3, §8 C8 | `ConfirmAuthenticationSpec`, `CheckoutConfirmConcurrencySpec`(C8) |
| 17 | (AN §22-5) 가상 VAN 원장 append-only 표 권한(§9.2식) 재검토 | AN §13.1 AN-19 | Simulator runtime 계정 = DB 단위 DML | **재검토 결과: §9.2식 표 단위 권한을 Simulator에 적용(AU-20, Coordinator 채택 2026-10-18)** | §5.5 | `SimulatorDatabaseAccountSpec`(SHOW GRANTS 고정, 원장 UPDATE/DELETE 1142) |
| 18 | (AN §22-8) confirm TX1이 payment_authorization 잠금 전 결제창 시각 확인 → 공용 `RequestedPaymentClosing` 인라인 만료(PaymentExpired 같은 TX) → PAYMENT_TERMINATED | AN §7.5.3, D-AUTHN-008 | `RequestedPaymentClosing.closeIfElapsed`(커밋되는 결과 값) | confirm에서 호출 + 멱등 결과 저장 + 커밋 후 409 | §4.3 | `ConfirmInlineExpirySpec`(커밋된 EXPIRED·PaymentExpired 1·키 결과 PAYMENT_TERMINATED·재생 같은 409) |
| 19 | (AN §22-10·11) V9 이후도 기동 전략 순서 유지, V7→V8_1 배포 전제 유지 | AN §7.5.6·§20 R13 | `CheckoutWindowCheckMigrationStrategy`(V8 → pin·채우기 → 재확인 → 전부) | V9~V12가 이 순서에 얹힘을 시험, 전략 불변 | §7.4 | `OldVolumeStartSpec` 확장(V6 볼륨 → 한 번 기동에 V12까지), `CheckoutWindowCheckMigrationStrategySpec` 회귀 |
| 20 | (AN 최종 m-1) C8 외 confirm vs 만료 작업/인라인 만료 경합 실제 MySQL | AN review | AUTHN C7·C11~C16 시험 기반(`CheckoutExpiryConcurrencySpec`) | C18~C20 | §8 | `ConfirmExpiryConcurrencySpec` |
| 21 | (D-AUTH1-001) LimitPolicyUnavailable → 503, 미저장, REQUESTED 유지, 같은 키 재시도 성공 | D-AUTH1-001 | domain `LimitPolicyUnavailable` | confirm 매핑·저장 안 함 | §4.3-10, §4.9 | `ConfirmErrorMappingSpec`(정책 등록 전 503 → 등록 뒤 같은 키 202/200) |
| 22 | (D-AUTH1-002) KRW만, 비KRW 422 PAYMENT_NOT_ELIGIBLE 저장·전송 없음 | D-AUTH1-002 | 없음 | Route Capability `supportedCurrencies` | §4.3-9, §5.8 | `ConfirmCurrencyScopeSpec`(USD → 422 저장·재생, stub 수신 0) |
| 23 | (D-AUTH1-003) 신용 일시불·체크 일시불·일반 할부 개방, 체크 할부 거절, 카드 종류·할부별 시나리오·한도·시험 | D-AUTH1-003 | domain `PaymentPlanRule`(체크 할부 거절), AUTHN Plan Demo 정책 | VAN 시나리오·Capability·결제창 설정 일치(AU-31)·조합 행렬 | §5.3, §5.8, §11 S5 | 조합 행렬 시험, 한도 경계(정책은 MID+통화 하나), 할부 최소금액 경계 |
| 24 | 1단계 명세(이 문서) → 질문 → 2단계 TDD | tasks.json | — | 이 문서 | 전체 | — |

### 2.1 인계 항목 전수 대조(다른 작업 문서의 "AUTH-001" 표시)

| 출처 | 항목 | 처리 |
|---|---|---|
| AC1 openItems | m-9 effect_key occurrence | AC 12, §4.10 |
| AC1 openItems | m-10·m-11 | AC 13, §4.11-2 |
| AC1 openItems | incident_hold UNIQUE·append-only·해제 표·FORCED/excess·같은 TX append 시험 | AC 12·14, §7.1 |
| AC1 openItems | S7/L-3 + 시험 재설계, §17-7·8, n-2·n-3·n-4, m-8, sourceResultId | AC 8·9·10·11 |
| AC1 §12 | 실DB 경쟁·rollback·응답 유실, 관측 지표, 행위자 컬럼 | §8, §9 |
| AC1 §19.2-10 | `PaymentAuthorization.reconstitute`(Mapper와 함께) | §4.11-5, §7.3 |
| CC §14.1 nit 3 | 거래가 기록된 FAILED 승인에 새 관찰 키의 같은 조건 APPROVED → NoChange 여부 | **도출로 처리**(§4.11-2): D-AUTH-013은 "다른 기관 거래 식별자"일 때만 새 hold — 기록된 거래와 terms 전체(PTX·승인번호·기관 거래일 포함)가 같으면 NoChange. RECON 경로와 공통 domain 규칙 |
| CC §14.1 nit 4 | `PaymentAuthorization` reconstitute가 FAILED + 거래 수용 | §4.11-5 |
| CC §9.1 | TX2·회복은 control부터 | §4.7 |
| MS2 §13.2 | 6항목(evaluatedAt·HTTP·게이트·FORCED 정책·Route 등록부·컬럼 크기) | AC 15, §4.3·§4.4·§5.8, `policy_assignment_version` VARCHAR(160) |
| MS2 §13.2 | review round1 n-3·n-5(선택 항목, 미처리) | 범위 관찰 §14-6(이 작업 대상 아님 — 내용 확인 후 Coordinator 판단) |
| AN §22-5 | Simulator 원장 권한 | AC 17 |
| AN §22-8·10·11 | 인라인 만료·전략 순서·배포 전제 | AC 18·19 |
| AN §14 C8 | 인증 시작 vs confirm | AC 16, §8 |
| tasks.json CP-DOMAIN-001 | Recoverability·Reconciliation followUpGate AUTH-001 | §6, §9 |
| tasks.json FOUNDATION | 외부 효과 회복 followUpGate AUTH-001 | §6, §8 |
| tasks.json CP-DB-001 | "AUTH의 상태 변경은 control→Payment 잠금 순서 준수" | §4.7 |

---

## 3. 현황 조사(2026-10-18, `481a9fa`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것(이 작업) |
|---|---|---|
| domain `payment` | `PaymentAuthorization`(OPEN/UNKNOWN/AUTHORIZED/FAILED, Attempt·관찰·거래, `recordLateApprovalByReconciliation`), `AuthorizationAttempt`(PREPARED/DISPATCHING/ABANDONED_BEFORE_DISPATCH/OUTCOME_UNKNOWN/RESOLVED, `AbandonCause`·`UnknownCause`), `InstitutionObservation`(`sameInstitutionContent`), `AuthorizationResolution`(판정표·`abandonBeforeDispatch`·`markOutcomeUnknown`), `AuthorizationStart`, `DailyLimitUsage`(RESERVED/CONSUMED/RELEASED/FORCED, `LimitExcess`), `TransactionLimitPolicy`, `IncidentHold(occurrence)`, `IncidentHoldRelease`, `IncidentHoldSettlement`, `AuthenticationSession.bindingRejection/bindTo`, `RequestedPaymentExpiry.expireIfElapsed`, `PaymentTransaction`(sealed: 승인·취소) | L-3 출처 제한, m-10·m-11, `PaymentAuthorization.reconstitute`, n-3 처리, 승인 시작 사전 검사, `ProviderRequestRef` 마스킹 |
| application | `LocalTransactions`, `RequestedPaymentClosing`(control 보유 인라인 만료 공용 단계), `AuthorizationAdmissionPort`(+ `EntitlementAuthorizationAdmissionAdapter`), `IdempotencyRecordStore`(operation enum `CREATE_PAYMENT`만), `OutboxStore`(payload 2종), `ReportSuccessAfterTerminationService`(호출 경로 없음) | confirm·전송·결과 반영·회복 Use Case, `AuthorizationPort`·`AuthorizationInquiryPort`·`InstitutionRouteResolver`, 저장 Port 2개(Authorization·일 한도), 회복 Reader·Store |
| persistence | V1(payment·근거·control·hold·멱등·outbox), V3·V5(투영), V7·V8·V8_1(결제창·인증 세션, `bound_authorization_id` UNIQUE 열은 비어 있음), `CheckoutWindowCheckMigrationStrategy`, `PessimisticRowLock` | 승인 계열 표 전부, hold occurrence·reason 확장, 근거 출처 열, 멱등 CHECK 확장 |
| web | 공개 포트 Bearer 체인, 엄격 JSON 파서, `ApiErrorCode`(PAYMENT_TERMINATED·PAYMENT_NOT_FOUND·ORDER_ON_HOLD 등) | confirm Controller·파서, 새 code(`CONFIRM_CONTENT_CONFLICT`·`AUTHENTICATION_NOT_VERIFIED`·`PAYMENT_PLAN_NOT_ALLOWED`·`LIMIT_EXCEEDED`) |
| Simulator | 모듈 `authentication`(등록·고객 화면·결과 조회 HTTP, 가상 카드 카탈로그 12종), 별도 MySQL(runtime DB 단위 DML), `SimulatorArchitectureSpec`·재시작 내구성 시험 | 모듈 `van`(TCP·원장·조회·시나리오), 표 단위 권한 |
| 하네스 | GRADLE-CHECK minTests **1586**, HARNESS-SELFTEST **81**, GRADLE-DEPS 5모듈, CVE-SCAN에 institution-simulator 포함 | 가상 VAN 계약 golden, 하한 갱신 |

### 3.2 발견(F-x — 기존 코드·문서와 이 작업 계약 사이의 공백)

1. **F-1 사건 이벤트 effect_key 충돌**: `OrderPaymentControl.openHold`는 같은 (paymentId, reason) 활성 hold가 있으면 효과 없이 `true`를 돌려주고
   `AuthorizationResolution.incidentIfHeld`는 그때도 `ResolutionOutcome.Incident`를 돌려준다(예: FAILED에 새 관찰 키 승인이 두 번 오면 두 번째도 Incident). m-9의
   effect_key `{paymentId}:{reason}:{occurrence}`로 매번 발행하면 두 번째가 Outbox UNIQUE 1062 → TX2 rollback → 관찰도 사라지고 회복이 같은 관찰로 무한 반복한다.
   → **AU-17**: application은 TX2에서 control의 hold 목록이 늘었을 때만 사건 이벤트를 낸다(같은 활성 hold 재보고는 관찰만 기록). domain 결과를 바꿀지(새 hold 여부를
   결과 값에 싣기)는 S1 구현 결정 — 시험 기준은 "같은 활성 hold 재보고 → 이벤트 0, 1062 없음".
2. **F-2 L-3 영향 시험 파일 증가**: AC1 §19.3 표(2026-10-15)의 6개 파일 뒤에 AUTHN-001·CANCEL-CONTRACT-001이 REQUESTED Payment를 `fail(PRE_TRANSMISSION_FAILURE_CONFIRMED)` 등으로
   종료하는 준비를 더 넣었다. 2026-10-18 grep 결과(REQUESTED 직접 종료로 보이는 곳): `CreatePaymentServiceSpec`(274·287), `CreatePaymentRollbackSpec`(63), `CreatePaymentApiSpec`(180·209~212·215),
   `ApiFailureSpec`(376·382), `PaymentStoreAdapterSpec`(123·228), `CheckoutTokenIssuanceSpec`(206·213), `CardAuthenticationFlowSpec`(319), `CheckoutExchangeSpec`(153), `CheckoutErrorPageLeakSpec`(51),
   `CheckoutWebSpec`(167) 등. `UtcStorageSpec`은 지금 해당 호출이 없다(대신 `ReportSuccessAfterTermination` 사용 — AU-18과 함께 고침). → S1에서 **grep 목록을 다시 만들고**
   파일마다 "승인 시작 경유로 바꿈 / 도달 불가 상태라 legacy 행 복원" 중 하나를 표로 남긴다. 모두 `apps/payment-api/**`(허용 경로) 안이다.
3. **F-3 기관 계약계정 원문 노출 경로**: `ProviderRequestRef`는 data class라 기본 `toString`이 `institutionAccount`(= institutionMerchantRef, D-MS2-005 내부 식별자 등급)를 그대로 낸다.
   `AuthorizationAttempt.toString`은 이를 쓰지 않지만 예외·로그에 `ProviderRequestRef`가 들어가면 원문이 남는다. → AU-27: `toString` 마스킹 + 로그 캡처 시험.
4. **F-4 m-10 비교 대상 정보**(rev 0.3 H-1로 해결 방식 변경 — hold → 여는 관찰 링크를 domain·DB에 둔다, §4.11-2·§7.1): "해제 판정이 확인한 승인과 같은 terms"를 알려면 해제된 hold를 연 관찰의 terms가 필요하다. hold 행에는 관찰 참조가 없다. → (rev 0.2 문장, 대체됨) §4.11-2: 해제 시각 이전에
   이 Authorization에 기록된 APPROVED 관찰들의 terms와 기록 거래의 terms를 비교 집합으로 쓴다(새 열 없이 domain 안에서 판정). `incident_hold.source_observation_id`(감사용 선택 열)는
   §7.1에서 nullable로 추가한다.
5. **F-5 `markOutcomeUnknown`의 최종 상태 예외(n-3)**: 회복 Worker가 dispatch lease 만료로 OUTCOME_UNKNOWN 표시를 하려는 순간 TX2가 먼저 결과를 확정했으면 `InvalidAuthorizationTransitionException`.
   잠금으로 직렬화되므로 늦은 쪽은 재판정해야 한다 → AU-25: Attempt가 RESOLVED·ABANDONED이거나 Authorization이 최종이면 `NoChange`.
6. **F-6 `IncidentHoldSettlement` 판정 범위(m-11)**: 같은 Payment에 엇갈린 해제 판정 hold가 둘이면 마지막 해제의 판정만으로 FORCED를 정리한다 → §4.11-2.
7. **F-7 head(S) 잠금 위치**: 승인 시작 허용 판정은 인증 증거의 매입사 코드가 필요해 세션을 읽은 뒤에야 할 수 있지만, 잠금 순서는 head(S) → control이다. 마지막 잠금 뒤에 처음
   head S를 요청하면 Inbox(head X 대기)와 CreatePayment(head S 보유·control 대기) 사이에서 InnoDB 대기열 교착이 생길 수 있다(S 요청도 앞에서 기다리는 X 뒤에 선다) → AU-04.

---

## 4. confirm 흐름

### 4.1 API(가맹점 서버 — VERIFIED 인증 뒤 successUrl로 받은 값으로 호출)

```text
POST /v1/payments/{paymentId}/confirm           (공개 포트, Authorization: Bearer <pc_test_sk_…>, Idempotency-Key: <1..64 [A-Za-z0-9-_.:]>)
{ "authenticationRef": "aref_…", "merchantOrderId": "…", "amount": 50000, "currency": "KRW" }
```

- 경로·202는 CA-18, 본문 재진술(주문 ID·금액·통화)은 CA-08(서버 대조, 불일치 거절 — 더 엄격한 fail-closed), 닫힌 필드 4개·엄격 파서(CP-API 관례, AN-03과 같음).
  `authenticationRef`는 결제창 복귀 redirect의 불투명 참조(AN §4.1, `aref_` 접두 — AN-22)이며 형식 위반은 400.
- 인증·범위: 기존 Bearer 필터(D-CPA-001). Payment가 없거나 다른 MID·환경이면 404 `PAYMENT_NOT_FOUND`(존재 비노출, 저장 안 함). paymentId 형식 오류도 404(AN-01과 같음).
- 응답 본문(확정 AC1 §10.1): `paymentId`, `authorizationId`, `status`(AUTHORIZED·FAILED·IN_PROGRESS·UNKNOWN), AUTHORIZED면 `approvedAmount`·`currency`·`plan`·`cardType`·`approvalNumber`·
  `approvedAt`, FAILED면 `failureCategory`(DECLINED·NOT_SENT·NO_EFFECT). 카드번호·인증 증거·기관 원문·내부 버전·기관 계약계정 없음. 202에는 "새 키·새 Payment로 재시도 금지" 안내.

### 4.2 단계와 TX 경계

```text
[가맹점 서버] confirm(Idempotency-Key)
 TX1  멱등 예약/잠금 → head(S) → control(X) → payment(X)
      ├ 결제창 시각 경과(REQUESTED) → RequestedPaymentClosing.closeIfElapsed(세션 → 결제창 세션 → 토큰, Payment EXPIRED, PaymentExpired)
      │   + 멱등 결과 PAYMENT_TERMINATED ── 커밋 → 409 PAYMENT_TERMINATED
      ├ 진행 중·종료·재진술·hold·과거 승인 판정(§4.3 순서) → 재사용(ACCEPTED 참조) 또는 거절 저장 ── 커밋 → 응답
      └ 새 승인 시작: 세션(X, authenticationRef) → 일 한도 사용량(X, 없으면 생성) → at = now → AuthorizationAdmissionPort.evaluate(…, at)
          → AuthorizationStart.start(…, at) → payment UPDATE · payment_authorization INSERT · attempt PREPARED INSERT · session 귀속 UPDATE
          · 한도 예약 · 멱등 결과 ACCEPTED(authorizationId) ── 커밋
 TXd  head(S) → control(X) → payment(X) → payment_authorization(X) → attempt(X) → (session 읽기)
      게이트: A0~A2 재판정(now) + 인증 증거 유효(providerValidUntil) + 프로파일·Health
      ├ 통과 → Attempt PREPARED → DISPATCHING(조건부 갱신, dispatch lease) ── 커밋
      └ 거절 → abandonBeforeDispatch(GATE_REFUSED): Payment·Authorization FAILED(PRE_TRANSMISSION) + 한도 RELEASED + PaymentAuthorizationFailed ── 커밋 → 200 FAILED(NOT_SENT)
 [TX 밖] AuthorizationPort.authorize(기관 요청 ID, Route Snapshot, Money, Plan, 인증 증거 참조)  — 잠금·DB TX 없음, 자기 deadline 재확인 뒤에만 connect
 TX2  control(X) → payment(X) → payment_authorization(X) → attempt(X) → 한도 사용량(X)
      관찰 append(RESPONSE) + AuthorizationResolution + Outbox  /  NotSentConfirmed → abandonBeforeDispatch(CONNECT_FAILED)  /  OutcomeUnknown → markOutcomeUnknown(cause)
      ── 커밋
[응답] 제한 시간(Demo 설정) 안 확정 → 200 AUTHORIZED/FAILED, 아니면 202 IN_PROGRESS/UNKNOWN. 남은 처리는 회복 Worker(§6)
```

- **확정**: 외부 호출은 DB TX 밖, 업무 효과·요약·요청 결과·Outbox는 같은 PAYMENT 로컬 TX(CLAUDE.md, 시도 §5, 허용 §5.3). 관찰을 별도 선저장 TX로 두지 않는다(CA-16 — 증거 원본은 기관).
- **확정**: Attempt 생성과 AUTHORIZING은 같은 TX1, 전송은 커밋 뒤(L-3 AC 5). TXd 커밋 전에는 소켓을 열지 않는다(허용 §5.3-2, AC1 §5.2).
- confirm 조정자 서비스(`ConfirmPaymentService`)는 TX를 열지 않고 단계 서비스가 `LocalTransactions`로 TX를 소유한다(AUTHN 관례 — 외부 호출을 끼는 서비스는 TX 없는 조정자).
- TX2 실패(교착 재시도 소진·잠금 timeout·연결 오류·commit 불명확): confirm은 202 IN_PROGRESS(operation 참조는 TX1에 이미 커밋), 남은 반영은 회복 Worker가 원 Attempt 조회로 한다(AC1 §6-3).

### 4.3 TX1 상세(판정 순서 — 앞 단계가 결정하면 멈춤)

| 순서 | 단계 | 결과(저장 여부) | 근거 |
|---|---|---|---|
| 1 | 멱등 `reserveOrLock`(범위 = environment·인증 MID·`ConfirmPayment`·key) | 같은 키·같은 fingerprint → **operation 현재 결과 재생**(ACCEPTED면 Authorization 현재 상태, 거절이면 저장 거절) / 다른 fingerprint → 409 `IDEMPOTENCY_KEY_REUSED` / 대기 timeout → 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` | D-AUTH-004, CA-06·07 |
| 2 | head(S) 확보: entitlement provided `lockHeadForShare(merchantId)`(rev 0.3 m-9 — `entitlement_head`는 merchant_id 키, 인증 MID의 소유 Merchant는 비잠금 `mid_owner` 조회로 얻는다. TXd도 같은 연산) | 없음·만료는 이 단계에서 판정하지 않는다(8에서 같은 at으로) | F-7 |
| 3 | control(X) → payment(X) 잠금, Payment 소유 확인 | 다른 MID·환경·없음 → 404(저장 안 함, rollback) | §4.1 |
| 4 | **결제창 시각 먼저**: REQUESTED ∧ `isCheckoutWindowElapsed(now)` → `RequestedPaymentClosing.closeIfElapsed`(같은 TX, PaymentExpired append) | `PAYMENT_TERMINATED` 저장 → 커밋 → 409 | **확정** AN §7.5.3·§22-8, D-AUTHN-005·008. `payment_authorization`을 잠그기 전(REQUESTED에는 Authorization이 없다) |
| 5a | Payment가 AUTHORIZING·UNKNOWN·AUTHORIZED이고 이 Payment의 Authorization이 있음 | 요청 `authenticationRef`가 귀속 세션의 것 ∧ 재진술 일치 → **재사용**(ACCEPTED 참조 저장, 새 Attempt·귀속 없음) / 아니면 409 `CONFIRM_CONTENT_CONFLICT` 저장 | CLAUDE.md "다른 키/같은 주문 동일 내용 재사용", AC1 §4.4 |
| 5b | Payment FAILED·EXPIRED | 409 `PAYMENT_TERMINATED` 저장 | AC1 §10.1 |
| 5c | 재진술(주문 ID·금액·통화) ≠ Payment 생성 불변 값 | 409 `CONFIRM_CONTENT_CONFLICT` 저장 | CA-08 |
| 5d | 주문 hold / 과거 승인 성공 / stale(domain 사전 검사 AU-05) | 409 `ORDER_ON_HOLD` / 409 `ORDER_ALREADY_AUTHORIZED` / stale은 결함(현재 Payment만 REQUESTED일 수 있음) — 저장 | AC1 §10.1, 기존 code 재사용 |
| 6 | 세션: `authenticationRef`로 id 조회(비잠금, UNIQUE — 대체·만료 행도 찾힘) → `authentication_session`(X) | 없음·다른 Payment·증거 없음 → 409 `AUTHENTICATION_NOT_VERIFIED` 저장(세부 사유는 내부 로그만) | AN §22-8 |
| 7 | (rev 0.3 m-1) `at = now`(ms)를 **사용량 잠금 전에** 정하고 사용량 행 (environment, MID, 통화, `LimitBusinessDate.of(at)`) insert-if-absent → `FOR UPDATE`. 잠금 뒤 `LimitBusinessDate.of(now) ≠ LimitBusinessDate.of(at)`(대기 중 Asia/Seoul 자정 경과)이면 TX를 rollback하고 처음부터 재시작(AU-32) | — | AC1 §4.5, 관례 §9 최초 insert 경쟁, D-AUTH-007 |
| 8 | 같은 `at` → `AuthorizationAdmissionPort.evaluate(mid, site, currency, CARD, verified.acquirerCode, at)` | Refused → 422 `PAYMENT_NOT_ELIGIBLE`(고정 reason) 저장 / Unavailable → 503 저장 안 함(rollback) / NotFoundOrForbidden → 404 저장 안 함 | MS2 §6.4 같은 at, §5.5 |
| 9 | Route 프로파일 해석(`InstitutionRouteResolver`, admitted routeRef) + Capability 대조: 통화 ∉ `supportedCurrencies`(Demo [KRW]) → 422 `PAYMENT_NOT_ELIGIBLE` 저장(D-AUTH1-002), cardType ∉ `supportedCardTypes` 또는 Plan이 프로파일 할부 범위 밖 → 422 `PAYMENT_PLAN_NOT_ALLOWED` 저장 | 모르는 routeRef·REAL·LIVE→SIMULATOR → 503(fail-closed, 저장 안 함, 결함 로그) | MS2 §13.2, §5.8, D-AUTH1-002·003 |
| 10 | `AuthorizationStart.start(command(at))` | Rejected: AuthenticationNotVerified → 409 `AUTHENTICATION_NOT_VERIFIED` / PlanNotAllowed(체크카드 할부 포함) → 422 `PAYMENT_PLAN_NOT_ALLOWED` / LimitExceeded → 422 `LIMIT_EXCEEDED` 저장 / LimitPolicyUnavailable → **503 `TEMPORARILY_UNAVAILABLE`, 저장 안 함(rollback)**(D-AUTH1-001) — 모두 Payment REQUESTED 유지·세션 미귀속(D-AUTH-005) | AC1 §4.2, D-AUTH-005·006, D-AUTH1-001 |
| 11 | Started → 저장: payment UPDATE, `payment_authorization`·`authorization_attempt` INSERT, session UPDATE(`bound_authorization_id`), 예약 INSERT·사용량 version, 멱등 결과 ACCEPTED(authorizationId) | 커밋 → TXd | L-3, AN §22-8 |

- 기관 요청 ID: PG 생성 `ar_` + ULID(CA-22), namespace = (기관 = 프로파일의 institution ID, 환경, institutionMerchantRef, operation `AUTH`, 요청 ID) UNIQUE.
- 세션 바인딩 대조는 `bindingRejection(paymentId, money, at)`(VERIFIED·미귀속·`min(expiresAt, providerValidUntil)` 전·바인딩 금액)와 `bindTo` — 기존 domain 그대로(AN §22-8).
- 같은 `at`을 Port와 `AuthorizationStart`에 넘긴다 → 정책 선택(UTC)과 일 한도 날짜(Asia/Seoul)가 같은 시각에서 나온다(MS2 §6.4, 시험 `ConfirmAdmissionTimeSpec`).
- 교착(1213)·version 충돌은 새 TX로 처음부터 재실행(최대 3, `at`도 새로 — 관례 §9). 서버 생성 ID 충돌은 `IdentifierCollision`.

### 4.4 TXd(전송 게이트)

- 잠금: head(S) → control(X) → payment(X) → payment_authorization(X) → attempt(X)(→ 한도 사용량(X): 거절 때만). 게이트 거절이 Payment·한도를 바꾸므로 정순서 부분열을 처음부터 잡는다(AU-06).
- 재판정(확정 MS2 §7, 허용 §4.2): `AuthorizationAdmissionPort.evaluate(…, now)`의 A0~A2(누락·만료·막힘·권리·카드사 개통)만 본다. 한도·정책 Assignment는 **재판정하지 않는다**(TX1 고정 —
  D-AUTH-007, 허용 §4.1 끝). **인증 증거 유효기간은 게이트가 보지 않는다**(D-AUTH1-004, 사용자 결정 — AU-26 미채택): 시작된 승인은 보내고, 기관(가상 VAN)이 `validUntil`을
  재검증해 만료 증거면 기관 확정 거절(DECLINED `AUTHENTICATION_INVALID` → FAILED `INSTITUTION_CONFIRMED_DECLINE`)로 돌려준다(AUTH-CONTRACT-001 §4.3 "귀속 뒤 TTL은 승인을 바꾸지 않음"과 일치).
- 통과: `PaymentAuthorization.beginDispatch(attemptId)` + `dispatch_lease_until = now + dispatchLease`(Demo) + 같은 행 version 조건부 갱신 커밋. 회복 Worker의
  `abandon(FENCED_BY_RECOVERY)`와 같은 행에서 경쟁하므로 둘 중 먼저 커밋한 쪽만 이긴다(AC1 §13 JPA `@Version`과 fencing).
- 거절: `AuthorizationResolution.abandonBeforeDispatch(…, GATE_REFUSED)` → FAILED(`PRE_TRANSMISSION_FAILURE_CONFIRMED`), 한도 RELEASED, `PaymentAuthorizationFailed` Outbox.

### 4.5 기관 호출(TX 밖)

- 호출 직전 자기 deadline 확인: `now ≥ dispatch_lease_until − safetyMargin`(Demo)이면 **connect하지 않고** 끝낸다(상태 변경 없음 → 회복 Worker가 lease 만료로 OUTCOME_UNKNOWN 처리,
  AU-07). lease 만료를 "호출 안 함"으로 가정하지 않는 쪽(A06)과 짝을 이룬다.
- Port 결과(AC1 §9.1, 업무 예외 없음): `Approved`/`Declined`/`NoEffectConfirmed`(관찰) · `NotSentConfirmed`(connect 단계 실패를 같은 요청 ID로 예산 안 재연결해도 실패 — byte 미송신
  증명) · `OutcomeUnknown(TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY)`. write 시작 이후 실패는 전부 OutcomeUnknown(AC1 §5.2).

### 4.6 TX2(결과 반영)

- 잠금: control(X) → payment(X) → payment_authorization(X) → attempt(X) → 한도 사용량(X). Resolution이 hold를 열 수 있으므로 control부터(CC §9.1과 같은 규칙).
- `AuthorizationResolution.applyObservation(control, payment, authorization, usage, observation, newTransactionId, limitPolicy = 원 Authorization 고정 정책, at)`:
  `limitPolicy`는 늦은 승인 FORCED 초과 표시용이며 **원 Authorization에 고정된 `limitPolicyVersion`의 값**을 revision별 Snapshot에서 읽는다(MS2 §6.4 도출·AC 15). 읽을 수 없으면 null →
  UNDETERMINED(기존 domain). entitlement provided에 "정책 ID로 정책 값 조회" 연산을 추가한다(AU-29).
- 결과별 저장: 관찰 INSERT(`@Immutable`), Authorization·Attempt·Payment·control(hold)·예약 갱신, 거래 INSERT(승인 시), Outbox(§4.10). 멱등 행은 건드리지 않는다(결과 = Authorization 참조,
  D-AUTH-004 — TX2가 멱등 행을 잠그지 않아 잠금 순서가 단순해진다).
- `NotSentConfirmed` → `abandonBeforeDispatch(CONNECT_FAILED)`, `OutcomeUnknown` → `markOutcomeUnknown(cause)`(관찰 없음 — PG 쪽 사건이지 기관 결과가 아니다).

### 4.7 잠금 순서(AC1 CA-05 → AN §10.3 확장, 구현 결정 AU-06)

```text
멱등 기록 → entitlement_head(S) → order_payment_control → payment
  → [인라인 만료: authentication_session → checkout_session → checkout_token]   (REQUESTED 만료 경로 — 이 경로는 payment_authorization을 잠그지 않고 끝난다)
  → payment_authorization → authorization_attempt → authentication_session → daily_limit_usage(→ limit_reservation)
  → checkout_session → checkout_token → authorization_recovery_schedule
```

| Use Case | 부분열 |
|---|---|
| confirm TX1(새 시작) | 멱등 → head(S) → control → payment → (payment_authorization: REQUESTED라 없음 — UNIQUE(payment_id)가 방어) → session → 사용량 |
| confirm TX1(인라인 만료) | 멱등 → head(S) → control → payment → session → checkout_session → token |
| confirm TX1(재사용·거절) | 멱등 → head(S) → control → payment(→ payment_authorization 읽기) |
| TXd | head(S) → control → payment → payment_authorization → attempt(→ 사용량: 거절 때만) |
| TX2·회복 반영 | control → payment → payment_authorization → attempt → 사용량 |
| 회복 lease 표시·PREPARED fencing | control → payment → payment_authorization → attempt(→ 사용량: fencing 포기 때) → 회복 일정 |
| 회복 후보 선점 | 회복 일정(X, 단독 짧은 TX — 업무 행을 잡지 않음) |
| 기존(AUTHN) | 결제창 Use Case는 payment부터, control 미보유 경로는 control을 나중에 잡지 않는다(AN §7.5.3) — 변경 없음 |

- 같은 Payment의 confirm·결제창 작업·만료·회복은 payment 행에서 직렬화된다. 교착은 `LocalTransactions` 재시도(3회), 잠금 timeout(1205)은 confirm 503/회복 다음 주기.
- 승인 경로는 `return_balance`를 잡지 않는다(CC CC-02) → 취소 경로(control → payment → payment_authorization(읽기) → return_balance → payment_cancellation)와 순환 없음.

### 4.8 멱등·저장 결과

- 범위(시도 §5): (environment, 인증 MID, operation `ConfirmPayment`, Idempotency-Key). `IdempotentOperation.CONFIRM_PAYMENT("ConfirmPayment")` 추가.
- fingerprint `c1:` = 길이 접두 canonical(paymentId, authenticationRef, merchantOrderId, amount, currency)의 SHA-256(CA-06 — CP계약 §7 `r1`과 같은 방식). 보존 30일(CA-07, D-CPC-004).
- 저장 결과(V11에서 CHECK를 operation별로 다시 만든다 — §7.1):

| result_kind | result_code | 참조 | 재생 응답 |
|---|---|---|---|
| SUCCESS | `ACCEPTED`(새 시작) / `REUSED_ACTIVE_AUTHORIZATION`(다른 키·같은 내용) | `result_authorization_id` | Authorization **현재** 상태(D-AUTH-004 — AUTHORIZED/FAILED 확정 후 불변, 미확정은 진행 중) |
| REJECTED | `CONFIRM_CONTENT_CONFLICT`·`AUTHENTICATION_NOT_VERIFIED`·`PAYMENT_PLAN_NOT_ALLOWED`·`PAYMENT_NOT_ELIGIBLE`·`LIMIT_EXCEEDED`·`INCIDENT_HOLD`·`AUTHORIZED_NEW_ORDER_REQUIRED`·`PAYMENT_TERMINATED`(LimitPolicyUnavailable은 저장하지 않는다 — D-AUTH1-001) | `result_payment_id`, `result_detail`(고정 분류 — `PAYMENT_NOT_ELIGIBLE` reason 등) | 같은 거절(D-CPC-007 선례, CA-09) |

- 재생 시 현재 MID/환경 권한을 다시 확인한다(D-AUTH-004, D-CP-003 동일). 재생은 기관 호출을 새로 하지 않는다(시도 §5).

### 4.9 오류·응답 매핑(AC1 §10.1 구현 — MS2 §5.5 Port 결과 포함)

| 판정 | HTTP | code | 저장 |
|---|---|---|---|
| 인증 실패 / 형식 오류 | 401 / 400 | `UNAUTHENTICATED` / `INVALID_REQUEST`·`INVALID_IDEMPOTENCY_KEY` | 안 함 |
| Payment 없음·다른 MID/환경, Admission NotFoundOrForbidden | 404 | `PAYMENT_NOT_FOUND` | 안 함 |
| 같은 키·다른 내용 / 처리 중 | 409 | `IDEMPOTENCY_KEY_REUSED` / `IDEMPOTENCY_REQUEST_IN_PROGRESS` | — |
| 재진술 불일치·다른 키 다른 내용 | 409 | `CONFIRM_CONTENT_CONFLICT`(새) | 저장 |
| 인증 근거 무효 | 409 | `AUTHENTICATION_NOT_VERIFIED`(새) | 저장 |
| 체크카드 할부·Plan 정책 밖 개월·최소금액·프로파일 미지원 cardType | 422 | `PAYMENT_PLAN_NOT_ALLOWED`(새) | 저장 |
| Admission Refused(카드사 미개통·권리) / KRW 외 통화(D-AUTH1-002) | 422 | `PAYMENT_NOT_ELIGIBLE`(기존, 고정 reason) | 저장 |
| 한도 초과 | 422 | `LIMIT_EXCEEDED`(새, 한도값 미노출) | 저장 |
| 한도 정책 없음·모호(`LimitPolicyUnavailable`) | 503 | `TEMPORARILY_UNAVAILABLE`(D-AUTH1-001) | 안 함 — Payment REQUESTED, 정책 등록 뒤 같은 키 재시도로 승인 시작 |
| Admission Unavailable(Snapshot 누락·만료·막힘·v1·정책 Assignment 없음/모호·Route 모호), Route 프로파일 없음 | 503 | `TEMPORARILY_UNAVAILABLE` | 안 함 |
| hold / 과거 승인 / 종료(인라인 만료 포함) | 409 | `ORDER_ON_HOLD` / `ORDER_ALREADY_AUTHORIZED` / `PAYMENT_TERMINATED` | 저장 |
| 확정(제한 시간 안) | 200 | AUTHORIZED / FAILED(`failureCategory`) | (TX1에 ACCEPTED) |
| 진행 중 | 202 | IN_PROGRESS / UNKNOWN | (TX1에 ACCEPTED) |
| TX1 commit 불명확 | 503 | `TEMPORARILY_UNAVAILABLE`(`OUTCOME_UNCONFIRMED` 문구) | — 같은 키로 확인 |

### 4.10 Outbox와 effect_key(AC1 §11 확정, m-9)

| 이벤트 v1 | category | 시점(같은 TX) | effect_key | 비고 |
|---|---|---|---|---|
| `PaymentAuthorized` | `FINANCIAL_FACT` | TX2 Authorized | `PaymentAuthorized:{transactionId}` | D-AUTH-003. payload는 AC1 §11 목록(기관 계약계정 제외 — D-MS2-005, 카드번호·Secret 없음) |
| `PaymentAuthorizationFailed` | `INTEGRATION_NON_FINANCIAL` | TX2 Failed, TXd 게이트 거절, 회복 fencing 포기 | `PaymentAuthorizationFailed:{authorizationId}` | 금융 효과 없음 |
| `AuthorizationIncidentDetected` | `RISK_INCIDENT` | TX2에서 **새 hold가 append됐을 때만**(F-1, AU-17) | `AuthorizationIncidentDetected:{paymentId}:{reason}:{occurrence}` | m-9. `Authorized.incident`(이중 승인)이면 `PaymentAuthorized` + 이 이벤트를 같은 TX에(이 순서) |
| `AuthorizationOutcomeUnresolved` | `OPERATIONAL_RISK` | 회복 예산 소진·연령 초과 | `AuthorizationOutcomeUnresolved:{attemptId}` | 상태 불변(§6.3) |
| `PaymentExpired` | `INTEGRATION_NON_FINANCIAL` | confirm TX1 인라인 만료 | `PaymentExpired:{paymentId}` | AN §7.5.7 그대로. confirm 경로 인과 값: correlationId = requestId, causationId = 멱등 기록 ID |

- aggregateType `PaymentAuthorization`, aggregateVersion = Authorization JPA version(PaymentExpired는 Payment). 같은 TX 순서는 `id` 순서(AN n-e 관례).
- D-CANCEL-012 예외(hold 중 RECON의 PaymentAuthorized)는 RECON 작업 — 이 작업은 domain 경로를 막지 않는다.
- Kafka 미도입(ADR-001): 전달 보장은 Outbox/Inbox·effect_key UNIQUE·대사이며 브로커가 금융 안전성을 대체한다고 주장하지 않는다.

### 4.11 domain 보강(2단계 S1 — 모두 "검사 먼저, 통과 시 함께 변경")

1. **L-3 출처 제한(AC 6·8)**: REQUESTED에서 `INSTITUTION_CONFIRMED_DECLINE`·`INSTITUTION_CONFIRMED_NO_EFFECT`·`PRE_TRANSMISSION_FAILURE_CONFIRMED`·`TIMEOUT`·`INQUIRY_NOT_FOUND`·`LEASE_EXPIRED`로
   `fail`/`expire`를 거부(허용: `NOT_TRANSMITTED_AND_SESSION_DISCARDED`(EXPIRED)와 근거 없음). enum 값·V1 CHECK는 지우지 않는다(legacy 행 복원). `reconstitute`는 전이 경로를 검사하지 않는다(기존 KDoc) →
   legacy 행 시험이 가능하다. 시험 재설계 규칙: (a) 상태 준비가 목적이면 `AuthorizationFixture` 경유 AUTHORIZING → 해당 근거로 바꾼다, (b) "불충분 근거 종료 → 새 Payment 차단"처럼
   **정상 경로로 만들 수 없게 된 상태**의 판정 행은 legacy 행(`Payment.reconstitute` 또는 SQL INSERT)으로 준비하고 행 이름에 `legacy`를 넣는다. 기대값(차단/허용)은 바꾸지 않는다 —
   Reviewer가 "완화 아님"을 확인한다(AC 8). `PaymentFixture`·`OrderPaymentDecisionTableSpec`·`PaymentSpec`·`TerminationEvidenceSpec`을 같은 변경에서 갱신(AC 6).
2. **m-10·m-11·CC nit 3(AC 13, 도출 D-AUTH-010·013)**:
   - **(rev 0.3 H-1로 정정)** 새 관찰 키 APPROVED가 FAILED·AUTHORIZED 승인에 왔을 때 관찰 terms(금액·통화·Plan·cardType·PTX·승인번호·기관 거래일 전체)가
     **설명 집합** = {기록된 거래 terms} ∪ {REAL_APPROVAL_CONFIRMED·DOUBLE_APPROVAL_CORRECTED로 해제된 이 Payment hold를 **연 관찰**의 terms} 안에 있으면 `NoChange`(해제 판정이 이미 설명한 그 승인).
     hold가 활성인 동안 흡수된 다른 승인(같은 활성 hold라 새 hold·사건 없음 — AU-17)은 설명 집합 밖이다 — **(rev 0.5 D-AUTH1-005)** 그 hold는 재보고를 기다리지 않고 해제 연산에서 연다(아래 m-D 항).
     NO_EFFECT로 해제된 hold를 연 승인의 재보고도
     새 hold(D-AUTH-012). 이를 위해 domain `IncidentHold.sourceObservation`(여는 관찰 키)을 두고 승인 관찰 경로의 hold 개설 연산(`reportSuccessAfterTermination`·`reportConflictingResult`·
     `completeAuthorization`)은 non-null 키를 받는다. 출처 없는 legacy hold는 아무것도 설명하지 않는다(fail-closed). hold의 같음은 (paymentId, reason, occurrence)이고 출처는 속성이다.
     **RECON 인계**: 해제 Use Case(RECON)는 해제 기록에 "설명하는 관찰 키 목록"을 남긴다 — 이 작업의 domain은 hold의 여는 관찰로 판정한다(S1 구현).
   - 거래가 기록된 FAILED(D-CANCEL-011)에 같은 terms가 오면 위 첫 집합으로 `NoChange`(CC nit 3).
   - `IncidentHoldSettlement`: 같은 Payment의 해제 기록 중 REAL·DOUBLE이 하나라도 있으면 FORCED → CONSUMED(m-11, D-AUTH-010 "REAL이면 소비"). 다른 활성 hold가 남으면 정리하지 않는 기존 규칙 유지.
   - **(rev 0.5 검토 #2 m-D, 사용자 답변 P-1 → D-AUTH1-005, Q-AUTH1-05)** CONFLICTING_INSTITUTION_RESULT·SUCCESS_AFTER_TERMINATION hold를 REAL_APPROVAL_CONFIRMED·DOUBLE_APPROVAL_CORRECTED로 해제할 때,
     그 Payment의 승인 관찰 중 위 설명 집합(해제 뒤 기준) 밖이고 어느 hold의 여는 관찰도 아닌 것(= 활성 hold에 흡수됐던 승인, 같은 키의 다른 variant 포함)이 남으면 **해제와 같은 연산·같은 TX**에서
     같은 (paymentId, reason)의 hold occurrence + 1을 연다. fail-closed: 해제는 성공하고, 새 hold의 출처는 설명되지 않은 첫 관찰의 (key, variant). 새 hold가 활성이므로 FORCED 점유는 정리하지 않는다
     (활성 hold ⇔ FORCED). 남은 것이 없으면 새 hold 없음. **(rev 0.6 D-AUTH1-006, Q-AUTH1-06)** 해제 판정과 무관하게 같은 규칙이다 — NO_EFFECT_CONFIRMED 해제도 흡수된 승인 중
     설명되지 않은 것이 남으면 같은 연산에서 occurrence + 1을 연다. NO_EFFECT 판정은 어떤 승인도 설명하지 않으므로(설명 집합에 들어가지 않음 — D-AUTH-012) 흡수된 A의 재보고(다른 출처·같은 terms)도
     새 hold의 출처가 된다. 해제 Use Case(RECON-CONTRACT-001)는 이 연산이 append한 hold마다
     `AuthorizationIncidentDetected`를 같은 TX에 발행해야 한다(F-1 — 이 작업에는 해제 쓰기 경로가 없다).
3. **n-3(F-5, AU-25)**: `markOutcomeUnknown`은 Attempt가 RESOLVED·ABANDONED_BEFORE_DISPATCH이거나 Authorization이 AUTHORIZED·FAILED면 `NoChange`(예외 대신). DISPATCHING이 아닌 PREPARED는 결함(기존 그대로 예외).
4. **n-4(AC 10)**: `OrderPaymentControl.reconstitute`의 `incidentHoldReleases` 기본값 제거(Mapper가 해제 표에서 명시 전달). Store `update`는 domain의 해제 기록이 저장 행보다 많으면 거부한다
   (해제 쓰기 경로·INSERT 권한은 RECON 작업이 함께 추가 — 관례 §9.2 "쓰기 경로가 생길 때 권한").
5. **`PaymentAuthorization.reconstitute`(AC1 §19.2-10, CC nit 4)**: 상태·Attempt 목록·관찰(첫 관찰 + 키 상충 관찰)·거래·종료 근거를 받아 존재할 수 없는 조합을 거부한다:
   AUTHORIZED ⇔ 거래(단, FAILED + 거래는 D-CANCEL-011로 허용), OPEN이면 Attempt 중 PREPARED·DISPATCHING 하나, UNKNOWN이면 OUTCOME_UNKNOWN 또는 RESOLVED(상충 hold) 등.
   m-8 시험 기준(AC 11): hold 대상 Payment에 Authorization이 있으면 해제 Use Case는 그 Authorization·사용량을 반드시 넘긴다 — 이 작업은 Repository가 hold 대상 Payment의 Authorization을
   찾을 수 있는 조회(`findByPaymentId`)와 그 왕복 시험까지 제공한다(Use Case는 RECON).
6. **L-6(AC 7, AU-18)**: `ReportSuccessAfterTerminationService`·`ReportSuccessAfterTerminationUseCase` 삭제. 이유: 관찰 키 없이 hold를 열어 D-AUTH-012 이후 같은 늦은 성공의 재보고가 새 hold가 된다(AC1 §19.9 끝).
   늦은 성공은 응답·조회·(후속) 통보·대사 모두 관찰 키를 가진 `AuthorizationResolution` 경로로 들어온다. 실패 분류는 TX2 단계 서비스가 갖는다: Deadlock·OptimisticConflict → 재시도(3),
   LockTimeout·Unavailable → 그 건 rollback·다음 회복 주기, CommitOutcomeUnknown → 회복이 원 Attempt 조회로 확인, 그 밖은 결함. 이 서비스를 쓰는 시험(`IncidentHoldConvergenceSpec`·`UtcStorageSpec`·
   `CreatePaymentServiceSpec`·`CheckoutTokenIssuanceSpec`·`DatabaseAccountSeparationSpec`)은 control domain 연산 직접 준비 또는 Resolution 경로로 바꾼다(기대값 불변).
7. **승인 시작 사전 검사(AU-05)**: `AuthorizationStart.preflight(control, payment, session?, money, at)` — 읽기 전용(슬롯 → 세션 증거 유무)으로 Admission 호출 전에 거절 순서를 맞춘다.
   `start`는 지금처럼 모든 검사를 다시 한다(L-2 단일 연산 유지, ArchUnit 대상 목록 불변).
8. **`ProviderRequestRef.toString` 마스킹(F-3, AU-27)**: `institutionAccount`를 가린다. 예외 메시지에 넣지 않는다.

---

## 5. 가상 VAN(`apps/institution-simulator` 모듈 `van`)

### 5.1 프로토콜: TCP

- **확정**: "최초 가상 VAN은 TCP 소켓"(초기 확정 §59), 허용 §5.1 표(카드 승인·취소·결과 조회 = TCP 소켓), D-AUTHN-004("AUTH-001 가상 VAN(TCP)도 이후 여기[institution-simulator]").
  D-AUTHN-004의 "payment-api는 HTTP Adapter로만 연결"은 인증 Simulator 연결을 말한다(AN §13.5). 따라서 HTTP가 아니라 TCP이며, 질문하지 않는다.
- 구현: JDK `ServerSocket`(블로킹) + 연결당 virtual thread(Java 25), payment-api 쪽 JDK `Socket` — 새 의존성 없음(CA-20). Netty 등은 쓰지 않는다. Simulator 앱 안 별도 포트
  `paycore.simulator.van.port`(운영 기본값 없음 — 없으면 기동 실패, HTTP 포트와 같으면 거부).
- 계약 버전 `ProviderContractVersion = sim-van-1`(허용 §5.2 — 가상 규격이며 실제 VAN 규격을 추측한 것이 아님).

### 5.2 프레임과 메시지(sim-van-1)

```text
frame   = length:u32be | version:u8 (=1) | keyIdLen:u8 | keyId:ascii | mac:32 bytes | payload:UTF-8 JSON
length  = 이후 바이트 수(≤ 16 KiB, 초과 → FRAMING, 연결 종료)
mac     = HMAC-SHA256(key[keyId], version ‖ keyIdLen ‖ keyId ‖ payload)   — 시험·Demo 키는 환경변수, 저장소에 운영 키 없음
```

- 한 번의 read를 프레임 하나로 가정하지 않는다: 길이까지 누적 read, 부분 write 반복, 한 read에 프레임 둘 이상, 프레임 경계가 read 경계와 어긋나는 경우를 계약·시험한다(허용 §5.3).
- 메시지마다 `messageId`(송신자 생성 ULID)와 응답의 `inReplyTo`. 응답은 요청 `requestId`·`messageId`를 echo해야 하며 다르면 CORRELATION(→ OutcomeUnknown). 프로토콜은 한 연결 다중 요청(응답 순서 무관)을
  허용하지만 PG Adapter v1은 요청당 연결 1개를 쓴다(AU-08). MAC 불일치·JSON 스키마 위반 → INTEGRITY/FRAMING(→ OutcomeUnknown + 원문 hash만 보존).

| 메시지 | 필드(닫힌 집합, 엄격 파싱 — 모르는 필드·null·타입 오류는 FRAMING) |
|---|---|
| `AUTH_REQUEST` | (S3) 모든 메시지의 첫 필드 `type`(메시지 구분), `messageId`, `requestId`(`ar_`+ULID), `institutionAccount`(= institutionMerchantRef), (S3) `merchantRef`(MID — 인증 바인딩 echo 대조용, §5.3-3), `environment`(TEST만), `routeRef`, `amountMinor`(정수 ≥ 1), `currency`, `plan{kind: LUMP_SUM\|INSTALLMENT, months?}`, `authentication{evidenceRef, providerCode, issuerCode, acquirerCode}`, `requestedAt` |
| `AUTH_RESPONSE` | `inReplyTo`, `requestId`, `outcome`(APPROVED·DECLINED·PENDING·REQUEST_CONFLICT·REJECTED_CLOSED), `decisionId`(확정 결과일 때), `pendingId`(PENDING일 때 — rev 0.3 H-3), APPROVED면 `approvalNumber`·`providerTransactionId`·`approvedAmountMinor`·`currency`·`plan`·`cardType`·`institutionTransactionDate`·`institutionBusinessDate`, DECLINED면 `declineCategory`(고정 분류), `respondedAt` |
| `INQUIRY_REQUEST` | `messageId`, `inquiryId`, `originalRequestId`, `institutionAccount`, `environment`, `closeIfAbsent`(boolean — 무효과 종결 요청, Capability 있는 프로파일만) |
| `INQUIRY_RESPONSE` | `inReplyTo`, `inquiryId`, `originalRequestId`, `result`(FOUND·**PENDING**(rev 0.3 H-3, `pendingId` 포함)·NOT_FOUND·NO_EFFECT_CONFIRMED·UNAVAILABLE), FOUND면 원 결정의 `decisionId`와 AUTH_RESPONSE의 결정 필드 전부, NO_EFFECT_CONFIRMED면 `closureId` |

- 할부 표현(00/0 등)은 Adapter가 변환한다(범위 §2) — 이 가상 전문은 의미 필드(`plan.kind`·`months`)를 쓴다. domain에 전문 값 없음.
- 카드번호·CVC·인증 비밀 필드 없음. 카드 식별은 인증 증거 참조(`evidenceRef`)와 issuer/acquirer/provider 코드뿐(허용 §5.5, D-AUTH-001).

### 5.3 VAN 판정과 시나리오

판정 순서(Simulator domain `VanAuthorization`, 앞 단계가 결정하면 멈춤):

1. 같은 namespace(client·environment·institutionAccount·operation·requestId)의 원장 행이 있으면: payload hash 같음 → **기존 결정 그대로 재응답**(새 결정 없음), 다름 → `REQUEST_CONFLICT`(원장 불변).
   (rev 0.3 m-7) payload hash는 업무 필드의 canonical 형태로 계산하고 전송 필드(`messageId`·`requestedAt`)는 제외한다 — 재전송이 같은 요청으로 판정된다.
   (허용 §5.2 기본 가상 기관). 무효과 종결 표지가 있으면 `REJECTED_CLOSED`(원장 불변 — 종결 뒤 늦게 도착한 원 요청이 승인을 만들지 못하게 한다, §5.4).
2. environment ≠ TEST → 프레임 거절(연결 종료, 원장 불변 — 인증 쪽 §13.3과 같은 이중 방어).
3. 인증 근거 재검증(인증 모듈 provided 조회 — 같은 JVM·같은 Simulator DB, 모듈 경계는 provided만): evidenceRef 존재·AUTHENTICATED·바인딩 echo(금액·통화·merchantRef·Plan)·`validUntil` 안·다른 요청 ID에 쓰이지 않음 →
   아니면 DECLINED(`AUTHENTICATION_INVALID`). 같은 요청 ID 재전송은 1에서 걸러져 재사용 판정에 걸리지 않는다(AC1 §4.3 Capability `authenticationEvidenceReusableForResend` = 같은 ID만).
4. Plan 재검증: CHECK + INSTALLMENT, 프로파일 밖 개월 → DECLINED(`PLAN_NOT_SUPPORTED`)(범위 §2 "기관에도 재검증").
5. 가상 카드 시나리오(아래) → 결정 → **원장 INSERT(결정 확정) 후** 응답 송신. 원장 커밋이 응답보다 먼저다(성공 후 응답 유실 시나리오의 전제). (rev 0.3 m-7) 3·4의 DECLINED(`AUTHENTICATION_INVALID`·
   `PLAN_NOT_SUPPORTED`)도 같은 규칙으로 원장에 결정을 기록한 뒤 응답한다(같은 요청 ID 재전송이 같은 거절을 받는다). MAC 불일치 프레임은 원장 기록 없이 연결을 닫는다(n-4).

Demo 시나리오(가상 카드 카탈로그를 확장 — 인증 모듈 카드 정의에 VAN 동작 속성 추가, 결정 가능·seed 없음, 운영값 아님):

| 가상 카드 | VAN 동작 | PG가 겪는 것 |
|---|---|---|
| `VC-CREDIT-OK`(기존), `VC-SPLIT-OK` | 일시불·할부(프로파일 개월) APPROVED 즉시 | 200 AUTHORIZED(신용 일시불·신용 할부) |
| `VC-CHECK-OK`(기존) | 일시불 APPROVED(cardType CHECK) / 할부 요청은 DECLINED(`PLAN_NOT_SUPPORTED`) — PG가 먼저 거절하므로 VAN 거절은 이중 방어 시험(stub 직접 요청)으로만 | 체크 일시불 200 AUTHORIZED, 체크 할부 422 `PAYMENT_PLAN_NOT_ALLOWED`(전송 0) |
| `VC-VAN-MONTHS-MISMATCH`(S3 — 인증 거래 `card_ref` VARCHAR(32)에 맞춰 이름 단축) | 할부 APPROVED이나 승인 개월 ≠ 요청(요청 × 2, 99 초과면 − 1) | 상충 hold(D-AUTH-008 Plan 불일치) |
| `VC-VAN-PLAN-OUT-OF-PROFILE`(시험 절차) | 프로파일 밖 개월을 PG가 보냈다고 가정한 stub 직접 요청 → DECLINED(`PLAN_NOT_SUPPORTED`) | 기관 재검증(범위 §2) |
| `VC-VAN-DECLINE` | DECLINED(`ISSUER_DECLINED`) | 200 FAILED(DECLINED), 한도 해제 |
| `VC-VAN-LATE-RESPONSE` | 원장 APPROVED 커밋 → PG read timeout보다 늦게 응답 | UNKNOWN → 조회 FOUND → AUTHORIZED(지연 결과) |
| `VC-VAN-DROP-AFTER-APPROVE` | 원장 APPROVED 커밋 → 응답 없이 연결 종료 | OutcomeUnknown(CONNECTION_LOST) → 조회 → AUTHORIZED(응답 유실) |
| `VC-VAN-DROP-BEFORE-RECORD` | 요청을 읽고 원장 기록 없이 연결 종료 | UNKNOWN → 조회 NOT_FOUND 반복 → (프로파일 A) 종결 → FAILED(NO_EFFECT) / (프로파일 B) 예산 소진 → 대사 이관, UNKNOWN 유지 |
| `VC-VAN-PENDING-THEN-APPROVE` | PENDING 응답(원장 PENDING), 일정 시간 뒤 결정 APPROVED | UNKNOWN → 조회 FOUND |
| `VC-VAN-DUPLICATE-RESPONSE` | 같은 응답 프레임 2회 | 두 번째는 연결에서 무시(요청당 연결) 또는 관찰 DUPLICATE |
| `VC-VAN-PARTIAL-APPROVAL` | APPROVED, 승인 금액 = 요청 − 1 | UNKNOWN 유지 + CONFLICTING hold + 사건(D-AUTH-008) |
| `VC-VAN-CARDTYPE-MISMATCH` | APPROVED, cardType = CHECK(인증은 CREDIT) | 같음(D-AUTH-008) |
| `VC-VAN-FRAGMENTED` | 응답을 수 바이트씩 지연 분할 write | 정상 AUTHORIZED(부분 read 계약) |
| `VC-VAN-BAD-MAC` | MAC 틀린 응답(원장 APPROVED) | OutcomeUnknown(INTEGRITY) → 조회 → AUTHORIZED |
| `VC-VAN-INQUIRY-UNAVAILABLE-ONCE` | 첫 조회 UNAVAILABLE | 회복 다음 주기에 확정 |
| `VC-VAN-SECOND-APPROVAL`(시험 절차) | 같은 Payment 다른 요청 ID 승인을 원장에 주입(대사·통보 시뮬레이션 대체) | 이중 승인·늦은 승인 hold 경로 |

- connect 실패는 Simulator VAN 포트를 닫아 재현한다(NotSentConfirmed → CONNECT_FAILED → FAILED(PRE_TRANSMISSION)).
- **cardType·Plan 조합 시험 행렬(D-AUTH1-003)**: {신용 일시불, 신용 할부(Demo 개월 각 값·경계 밖), 체크 일시불, 체크 할부(거절)} × {승인, 거절, UNKNOWN 회복, 상충(cardType·개월 불일치)}의 대표 행과,
  각 조합의 건당·일 한도 경계(정책은 MID+통화 하나 — D-AUTH-006, cardType·Plan별 한도는 없다), 할부 최소금액(Demo Plan 정책) 경계. 결제창 Plan Demo 정책(AN §13.6
  `installment-months`·`minimum-installment-amount`)과 VAN 프로파일 할부 범위를 **같은 Demo 값**으로 맞추고, 둘이 갈라지면 기동 실패로 막는다(설정 일치 검사 — AU-31).
- **취소 검증 공백(D-AUTH1-003 사용자 재확인)**: 체크카드·할부 승인 건의 취소·부분취소·환불 검증은 CANCEL-001(AUTH-001 바로 다음)이 한다. AUTH-001이 끝난 시점에는 그 승인들의
  반환 경로가 없다 — TEST(가상 기관) 한정 상태로 기록한다.

### 5.4 조회(원 요청 ID)·무효과 종결·Capability 프로파일

- 조회는 원 기관 요청 ID 참조만 쓴다. **새 기관 요청 ID를 만들지 않는다**(AC1 §7). 조회는 읽기 전용이며 조회 로그(append-only)만 남는다 — 단, 아래 종결 요청은 예외.
- `closeIfAbsent = true`이고 원장에 그 요청 ID가 없으면, **Capability `confirmsNoEffect`가 있는 프로파일(A)** 은 그 namespace에 무효과 종결 표지(tombstone)를 원장에 INSERT하고
  `NO_EFFECT_CONFIRMED(closureId)`를 응답한다. 이후 같은 요청 ID의 원 요청이 늦게 도착하면 `REJECTED_CLOSED`(승인 없음). 이로써 "무효과 확정"이 기관 쪽에서 참이 된다 — PG가
  NOT_FOUND를 무효과로 해석하는 것이 아니라 기관이 종결을 확정한 것이다(D-CPD-002 "기관 무효과 종결 확인", 시도 §2). 원장에 결정이 있으면 종결하지 않고 FOUND.
- PG가 `closeIfAbsent`를 보내는 조건: Attempt 연령 ≥ 프로파일의 `closableAfter`(Demo)일 때만(그 전 NOT_FOUND는 기관 처리 지연일 수 있다 — 허용 §5.2).
- 프로파일 B(무효과 확정 없음): `closeIfAbsent`를 보내지 않고, NOT_FOUND는 관찰로만 남고 예산 소진 시 대사 이관(§6.3) — "해당 기능 없는 가상 기관 프로파일도 추가해 안전 차단을 시험"(**확정** 허용 §5.2).
- (rev 0.5 검토 #2 n-3, **Demo 규칙**) 요청 ID(ULID)의 생성 시각이 보존기간보다 오래된(또는 해석할 수 없는) **새** AUTH 요청은 기록 없이 거부한다(연결 종료 — PG는 UNKNOWN, 조회는
  보존기간 밖이라 UNAVAILABLE). 보존기간 밖 요청에 결정을 새로 만들면 조회로 확인할 수 없는 결정이 생기기 때문이다. 이미 기록된 같은 ID 재전송은 기존 결과. 운영 기관 규칙이 아니다.
- (rev 0.3 m-7 정정) 조회 보존기간(Demo Capability)이 지난 요청 ID의 조회는 `NOT_FOUND`가 아니라 `UNAVAILABLE`로 응답한다 — PG가 "없음"을 무효과 근거로 오해하지 않게 한다. 보존기간이 지난 요청의
  `closeIfAbsent`는 거부한다(종결 표지를 만들지 않는다 — 기관이 그 요청을 받았는지 더는 확인할 수 없다).
- **(rev 0.3 H-3) PENDING 요청은 종결 대상이 아니다**: 원장에 PENDING 예약(`van_pending_decision`)이 있으면 조회 결과는 `PENDING`이고 `closeIfAbsent`는 종결하지 않는다. PENDING의 결정 확정(DECISION 행)과
  종결 표지(CLOSURE 행)는 **같은 UNIQUE(namespace)** 원장 행을 Simulator 한 TX에서 INSERT하려고 경합하므로 둘 중 하나만 생긴다(진 쪽은 기존 행을 읽어 그 결과로 응답). 같은 요청 ID의 AUTH 요청과
  종결 요청이 동시에 와도 원장 행은 정확히 1개다(시험: PENDING vs closeIfAbsent 경합, AUTH vs 종결 동시 → 원장 1행).

### 5.5 기관 원장(금융 사실 원본)과 권한

- 표(Simulator V2): `van_ledger_entry`(append-only: entry_id PK, client_id, environment CHECK='TEST', institution_account, operation, request_id, kind(DECISION·CLOSURE), payload_hash, outcome,
  decision 필드, created_at, UNIQUE(client_id, environment, institution_account, operation, request_id)), `van_pending_decision`(PENDING 시나리오의 예약 결정 — 결정 확정 시 DECISION 행 INSERT,
  예약 행은 상태 UPDATE), `van_inquiry_log`(append-only). PENDING도 결정 확정은 새 행이므로 확정 사실은 수정되지 않는다.
- **원장 = 기관 금융 사실 원본**: PG 재시작·PG DB rollback과 무관하게 남는다(허용 §5.5, AC1 §6-1). 재시작 시험: Simulator 재시작 뒤 조회 FOUND 동일.
- **권한 재검토 결과(AC 17, AU-20 — Coordinator 확인 대상)**: AN-19는 Simulator가 PayCore 소유 DB가 아니라는 이유로 DB 단위 DML을 택했다. 이제 원장이 회복 증명(AC1 §6-5 "Simulator 승인 1, 재승인 없음")의
  기준이므로, 원장 행이 수정될 수 있으면 그 증거가 약해진다. 권장: Simulator DB에도 §9.2식 표 단위 권한(role·잠긴 grantor·`set_runtime_table_privileges` procedure, 권한 migration 위치
  `db/runtime-privilege/simulator`)을 적용 — `authentication_transaction` SELECT·INSERT·UPDATE, `lookup_log`·`van_ledger_entry`·`van_inquiry_log` SELECT·INSERT, `van_pending_decision` SELECT·INSERT·UPDATE.
  초기화 스크립트(`docker/simulator-mysql/init/01-simulator-database.sh`)의 DB 단위 GRANT를 REVOKE로 바꾸고 재실행 수렴을 시험(`SimulatorDatabaseAccountSpec`).
  (rev 0.3 m-4) **기존 Simulator 볼륨**: 초기화 스크립트는 빈 데이터 디렉터리 첫 기동에만 자동 실행되므로, (a) 스크립트를 재실행 안전하게 만들어 기존 볼륨에서 1회 실행으로 수렴(§9.2 관례와 같은
  grantor·procedure·REVOKE)하고 `.env.example`·README에 절차를 적으며, (b) Simulator 기동 검사가 runtime 계정에 원장 표 UPDATE/DELETE 권한이 남아 있으면(`SHOW GRANTS` 해석) **기동을 거부**한다 —
  옛 볼륨을 고치지 않은 채 원장 증거가 약한 상태로 돌지 않게 한다. 대안: DB 단위 DML 유지 + JPA `@Immutable`
  (구현 비용 낮음, 증거력 낮음). 금융 정책이 아닌 시험 대역의 기술 선택이라 사용자 질문으로 올리지 않는다.

### 5.6 격리(TEST 전용, REAL 없음)

| 방어 겹 | 내용 |
|---|---|
| 1 | REAL Adapter 클래스 없음 + ArchUnit `authorizationPortsImplementedOnlyBySimulatorAdapter`(`AuthorizationPort`·`AuthorizationInquiryPort` 구현은 `..adapter.integration.institution..`의 `Simulator*`만), 위반 fixture `archfixture.institution.RealVanAuthorizationAdapter` |
| 2 | 기동 검사: `paycore.authorization.backends`에 `TEST: {type: SIMULATOR, profile: …}`만 허용. `LIVE: SIMULATOR`·`type: REAL`·모르는 type·profile 없음·host가 loopback/허용 목록 밖 → 기동 거부(AN §13.6과 같은 표) |
| 3 | LIVE Payment: 결제창 단계에서 이미 인증 backend가 없어 VERIFIED 세션이 생길 수 없다(AN §13.6). confirm도 Route 해석에서 fail-closed(503) — 전송 0 시험 |
| 4 | Simulator VAN: environment ≠ TEST 프레임 거절, 원장 CHECK(environment='TEST') |
| 5 | 네트워크 egress 허용 목록(Simulator host만) — 배포 작업(기록만) |
| 6 | 시험·Demo 키만(HMAC 키·Bearer 키 환경변수), 운영 키·endpoint 없음. 로그·이벤트·관찰 증거에 카드 데이터·Secret·institutionMerchantRef 원문 없음 |

### 5.7 PG Adapter(`payment.adapter.integration.institution.SimulatorVanAuthorizationAdapter`)

#### 5.7.1 연결·시간

- connect timeout / read timeout / 전체 deadline 분리(설정, Demo). connect 실패(byte 미송신)만 같은 요청 ID로 예산 안 재연결(`connectRetryBudget`, Demo), 소진 시 `NotSentConfirmed`.
  write 시작 이후의 모든 실패 → `OutcomeUnknown`(AC1 §5.2). 리다이렉트 같은 개념 없음, TLS는 v1 미적용(AU-09, 범위 관찰 §14-3).
- 요청·응답 원문은 로그에 쓰지 않는다. 관찰 `evidenceHash` = SHA-256(payload)만 보존.

#### 5.7.2 결과 변환(Adapter는 domain 전이를 호출하지 않는다 — 기존 ArchUnit `institutionAdapterDoesNotMutateDomain`)

| VAN 응답 | Port 결과 | 관찰 outcome |
|---|---|---|
| AUTH_RESPONSE APPROVED(필수 필드 전부, echo 일치, MAC 정상) | `Approved(observation)` | APPROVED + `ApprovedTerms` |
| AUTH_RESPONSE DECLINED | `Declined(observation)` | DECLINED |
| AUTH_RESPONSE PENDING | `Pending(observation)`(rev 0.3 n-1: TX2는 PENDING 관찰을 `applyObservation`으로 반영 → OPEN이면 BecameUnknown, 이미 UNKNOWN이면 StillUnknown. `markOutcomeUnknown`은 관찰 없는 PG 쪽 실패에만) | PENDING |
| REQUEST_CONFLICT / REJECTED_CLOSED | `OutcomeUnknown(CORRELATION)`(PG payload와 기관 기록이 다르거나 종결됨 — 계약 위반, 결함 로그) | 없음(조회로 원 결정 확인) |
| MAC·스키마·echo 위반 | `OutcomeUnknown(INTEGRITY·FRAMING·CORRELATION)` | 없음 |
| INQUIRY FOUND | `Found(observation)` | 원 결정 |
| INQUIRY PENDING(rev 0.3 H-3) | `Found(observation)` — 확정 아님 | PENDING(종료 근거 아님, StillUnknown) |
| INQUIRY NOT_FOUND | `NotFound` | NOT_FOUND 관찰(append, 종료 근거 아님) |
| INQUIRY NO_EFFECT_CONFIRMED(프로파일 A만 — B 프로파일 응답이면 INTEGRITY) | `NoEffectConfirmed(observation)` | NO_EFFECT_CONFIRMED |
| INQUIRY UNAVAILABLE / 연결 실패 | `Unavailable` | 없음 |

#### 5.7.3 sourceResultId 계약(AC 11, AC1 round2 M-2)

| 출처 | sourceResultId | 성질 |
|---|---|---|
| RESPONSE·INQUIRY(확정 결과) | VAN `decisionId` | 같은 기관 결정이면 응답·재응답·조회 어디서 와도 같은 값 → 같은 출처 재수신은 DUPLICATE, 다른 출처는 별도 관찰이지만 AUTHORIZED 뒤 "같은 승인"이라 NoChange |
| INQUIRY NO_EFFECT_CONFIRMED | VAN `closureId` | 종결 1회 = 1값 |
| RESPONSE·INQUIRY PENDING(rev 0.3 H-3) | VAN `pendingId` | 같은 PENDING 예약이면 응답·조회 어디서 와도 같은 값 → 같은 출처 재수신 DUPLICATE |
| INQUIRY NOT_FOUND | VAN `inquiryId`의 응답 echo(조회마다 다름) | 조회 1회 = 관찰 1건(예산이 상한) |
| RECONCILIATION·NOTIFICATION | 후속 작업이 같은 규칙(기관 결과 식별자)으로 정의 | — |

- 같은 `decisionId`로 내용이 다르면 KEY_CONFLICT(상충 사건, 기존 domain). observedAt·evidenceHash는 비교하지 않는다(기존 `sameInstitutionContent`).

### 5.8 Route 프로파일 등록부(`InstitutionRouteResolver` → `adapter.integration.institution.ConfiguredInstitutionRouteRegistry`)

- PAYMENT 로컬 버전 설정(AN-21 관례): `routeRef` → {institution ID, backend(SIMULATOR), `protocolVersion`(= `sim-van-1`), host·port·HMAC keyId(Secret 참조), timeout·예산 참조,
  Capability{`confirmsNoEffect`, `closableAfter`, `inquiryRetention`, `sameIdResendSafe`, `authenticationEvidenceReusableForResend`, `supportedCurrencies`(Demo [KRW] — D-AUTH1-002), `supportedCardTypes`(Demo [CREDIT, CHECK])·`installment`(Demo: 신용만, 개월 = 결제창 Plan Demo 정책과 같은 값 — D-AUTH1-003)}, profile version}.
- 모르는 routeRef(Snapshot에는 있으나 등록부에 없음) → fail-closed(503, 저장 안 함, 결함 로그 — MS2 §13.2). Authorization `admission.protocolVersion`과 Attempt Route Snapshot(routeRef·profile version·institutionMerchantRef)에 고정.
- `institutionMerchantRef`는 Attempt namespace에 저장하되 toString·로그·지표·API·예외에 원문 금지(D-MS2-005, F-3). 관리 조회 마스킹은 관리 화면 작업.
- **(rev 0.8, D-AUTH1-007 — Q-S8-1 = A) profile version 고정과 회복 판정**: `profile-version`은 필수·기본값 없음, 1..64 printable ASCII(등록부 기동 검사). TXd가 PREPARED → DISPATCHING과 같은 TX에서
  `authorization_attempt.route_profile_version`(V14, PREPARED·V14 이전 행은 NULL, CHECK)에 고정한다. TXd 시점에 routeRef가 등록부에 없으면 보내지 않는다(GATE_REFUSED — 전송 전 거절).
  회복은 고정 버전 = 현재 등록부 버전일 때만 `closeIfAbsent`(무효과 종결 요청)를 보낸다. 다르거나 NULL이면 조회와 예산·연령 이관만 한다(fail-closed — 찾은 결정 반영은 그대로).
  **Capability(`confirms-no-effect`·`closable-after`·`inquiry-retention` 등)를 바꾸면 `profile-version`도 반드시 바꾼다**(§20-6).

---

## 6. UNKNOWN 회복

### 6.1 회복 Worker

| 항목 | 결정 |
|---|---|
| trigger | `payment.adapter.batch.AuthorizationRecoveryScheduler`(`@Scheduled`, Spring Batch 아님 — AN-28과 같은 관례) → provided `RecoverAuthorizationsUseCase` → `AuthorizationRecoveryService` |
| 회복 일정(rev 0.6 n-9 — 구현 D-S6-1·D-S6-2) | `authorization_recovery_schedule`(V12, Attempt당 한 행, bookkeeping — 판정 근거 아님)을 **TX1이 Attempt와 함께 만든다**(`next_check_at` = preparedAt + preparedDeadline, `recovery_config_version`). dispatch lease는 Attempt 열이 아니라 이 행의 `dispatch_lease_until`(TXd가 기록). 행 삭제 없음 — 결론나면 `closed_at` |
| 후보·선점(rev 0.6 n-9 — 구현 D-S7-1) | 별도 jOOQ 후보 Reader 없이, 짧은 단독 TX에서 `closed_at IS NULL ∧ next_check_at ≤ now`인 일정 행을 `FOR UPDATE SKIP LOCKED`로 잡고 `next_check_at`을 조회 간격(이관 뒤 느린 간격) 뒤로 민다(다중 인스턴스 분산 lock 불필요 — DB가 직렬화; Worker가 죽으면 간격 뒤 다시 후보). Attempt 정보는 jOOQ `AuthorizationViewReader.findAttempt`(비권위). 조회는 읽기 전용이라 겹쳐도 무해하고 반영은 관찰 키로 멱등이다 |
| ① 처리 | TX: control → payment → authorization(→ attempt) → … → **회복 일정 잠금**: 잠근 일정의 `dispatch_lease_until`이 있고 now ≥ lease일 때만 `markOutcomeUnknown(DISPATCH_LEASE_EXPIRED)`, 아니면 NoChange(rev 0.6 m-1 — 선점 시점 사본으로 판정하지 않는다; 이미 RESOLVED면 NoChange — AU-25). TXd도 preparedAt + preparedDeadline 이후에는 통과시키지 않는다(fencing에 맡김) |
| ② 처리 | TX: 재판정 → `abandonBeforeDispatch(FENCED_BY_RECOVERY)`(PREPARED일 때만 — TXd의 DISPATCHING과 같은 행 조건부 갱신이라 동시에 이길 수 없다) → FAILED(PRE_TRANSMISSION) + 한도 해제 + `PaymentAuthorizationFailed` |
| ③ 처리 | TX 밖 `AuthorizationInquiryPort.inquire(원 요청 ID, closeIfAbsent = Attempt 고정 profile version = 현재 버전(D-AUTH1-007, §5.8) ∧ 프로파일 A ∧ closableAfter ≤ 연령 < inquiryRetention)` → TX2와 같은 반영 서비스 → 회복 일정 갱신(조회 수·다음 시각 — 고정 간격, backoff·jitter·회로차단 없음: 수용된 기술 편차 D-S7-2). 이관 전 연령이 보존기간에 이르면 종결 요청 없이 먼저 이관(rev 0.6 m-2). 기동 검사: maxAge < min(inquiryRetention), closableAfter < inquiryRetention, closableAfter ≥ preparedDeadline + dispatchLease |
| 인과 값 | correlationId = 회차 ID, causationId = `authorization-recovery:<회차 ID>` |
| 실패 | 한 건 실패는 그 건만 rollback·지표, 다음 후보 계속(AN §7.5.4와 같은 규칙) |

### 6.2 조회 예산과 대사 이관(D-AUTH-009 — 수치는 버전 있는 설정, 코드 운영 기본값 없음, Demo만)

- 예산: operation별 최대 조회 수·backoff·jitter·UNKNOWN 연령 상한·회로차단(조회 Unavailable 연속). 설정 버전을 회복 일정 행에 기록(Auditability).
  (rev 0.6 m-4 구현) 필수 설정 `paycore.authorization.timing.recovery.version`(공백 불가, 기본값 없음) — TX1이 `recovery_config_version`, 이관이 `escalation_config_version`에 적고,
  `AuthorizationOutcomeUnresolved` payload에 `configVersion`·`escalationReason`(BUDGET | MAX_AGE)을 넣는다. backoff·jitter·회로차단은 Demo에서 구현하지 않는다(수용된 기술 편차 D-S7-2).
- 소진(횟수·연령·프로파일 B의 반복 NOT_FOUND) → 같은 TX에서 `AuthorizationOutcomeUnresolved` Outbox + 회복 일정 `handed_off_at` 기록. **Payment·Authorization은 UNKNOWN 그대로**(AC1 §7, D-AUTH-009).
- 이관 뒤: 프로파일의 조회 보존기간까지 느린 주기(Demo)로 조회를 계속한다(AU-22 — 조회는 읽기 전용이고 확정 결과 반영은 정확 일치·멱등이라 RECON Case와 경합해도 효과 하나. RECON은
  `PaymentAuthorized`/`PaymentAuthorizationFailed`로 Case를 닫는다 — RECON-CONTRACT-001 경계). 보존기간 뒤에는 조회를 멈추고 RECON exact-match Recovery만 남는다(정산 §16).

### 6.3 자동 종료 근거 표("never auto-fail")

| 사건 | 결과 | 근거 |
|---|---|---|
| 기관 DECLINED(응답·조회, 무결성 검증) | FAILED(`INSTITUTION_CONFIRMED_DECLINE`) | D-CPD-002 |
| 기관 NO_EFFECT_CONFIRMED(프로파일 A의 종결 확정) | FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`) | D-CPD-002, CA-11 |
| 게이트 거절·회복 fencing·connect 실패 예산 소진(PREPARED/DISPATCHING의 미송신 증명) | FAILED(`PRE_TRANSMISSION_FAILURE_CONFIRMED`) | AC1 §5.5 유일 출처 |
| read timeout·끊김·무결성 실패·dispatch lease 만료·TX2 장애 | UNKNOWN(근거 불요) | 허용 §5.3 |
| 조회 NOT_FOUND·PENDING·UNAVAILABLE·예산 소진·연령 초과 | UNKNOWN 유지 | 시도 §2·§4, INV-11, D-AUTH-009 |

- UNKNOWN 중 새 Payment·새 키·새 카드·다른 VAN 금지(기존 `decide` UseExistingPayment, Authorization당 dispatch Attempt 1, UNIQUE(payment_id), 기관 요청 ID UNIQUE, VAN 같은 ID 기존 결과) — 시험(시도 §6-5).

### 6.4 대사 연결(PAYMENT ↔ RECON 경계)

- PAYMENT는 사실 이벤트만: `AuthorizationIncidentDetected`(hold 개설), `AuthorizationOutcomeUnresolved`(이관), `PaymentAuthorized`·`PaymentAuthorizationFailed`(확정). Case·위험 등급·MATCH·해제·보정은 RECON(AC1 §8.2).
- 대사 결과 반영 경로(`ObservationSource.RECONCILIATION`)는 같은 `AuthorizationResolution`을 쓰도록 반영 서비스를 source 인자로 열어 둔다(구현은 RECON 작업).

---

## 7. 영속화

### 7.1 PAYMENT migration(V9부터 — AN §22-4)

공통: `utf8mb4_0900_bin`, UTC `DATETIME(3)`, `version BIGINT ≥ 0`, 금액 `≥ 1`·통화 `^[A-Z]{3}$`·환경 CHECK, 자식 → 부모 FK(V1 관례). 카드번호·CVC 열 없음(`NoCardDataSchemaSpec` 확장).

| 파일 | 내용 |
|---|---|
| `V9__authorization.sql`(새 표만 — DDL 실패 시 반쯤 적용될 ALTER를 섞지 않는다, AU-19) | `payment_authorization`(PK `authorization_id`, **UNIQUE `payment_id`**, FK payment, OrderKey 열, `amount_minor`·`currency`, `plan_kind`·`installment_months`, `authenticated_card_type`, `authentication_session_id` FK, `entitlement_version`·`policy_assignment_version VARCHAR(160)`·`route_profile`·`protocol_version`·`evaluated_at`, `plan_policy_version`·`limit_policy_version`, 사용량 키(`limit_environment`·`limit_mid`·`limit_currency`·`limit_business_date`), `status` CHECK 4, `failure_evidence`(FAILED ⇔ NOT NULL), `opened_at`, `version`) · `authorization_attempt`(PK `attempt_id`, FK authorization, namespace(`institution`·`environment`·`institution_account`·`operation`·`request_id`) **UNIQUE**, Route Snapshot(`route_ref`·`profile_version`), `state` CHECK 5, `abandon_cause`(⇔ ABANDONED)·`unknown_cause`(OUTCOME_UNKNOWN ⇒ NOT NULL), `prepared_at`·`dispatch_started_at`·`dispatch_lease_until`·`resolved_at`, `version`, 생성 열 `in_flight_authorization_id = IF(state IN ('PREPARED','DISPATCHING','OUTCOME_UNKNOWN'), authorization_id, NULL)` **UNIQUE**(동시 실행 Attempt 1 — 시도 §1), 인덱스 (state, dispatch_lease_until), (state, prepared_at)) · `institution_observation`(append-only: PK id, FK attempt·authorization, `source`·`source_result_id`·`variant`(0 = 첫 관찰, n ≥ 1 = 키 상충) UNIQUE(attempt_id, source, source_result_id, variant), `outcome` CHECK 6, 승인 terms 열(APPROVED ⇔ NOT NULL: 금액·통화·Plan·cardType·`approval_number`·`provider_transaction_id`·`institution_transaction_date`·`institution_business_date`), `evidence_hash`, `observed_at`) · `payment_transaction`(append-only: PK `transaction_id`, `type` CHECK('AUTHORIZATION')(CANCEL-001이 확장), FK payment·authorization, 금액·통화, 승인 terms, `source_observation_id` FK, `recorded_by`(RESOLUTION·RECONCILIATION), `confirmed_at`, 생성 열 `authorization_txn_key = IF(type='AUTHORIZATION', authorization_id, NULL)` **UNIQUE**) · `daily_limit_usage`(PK (environment, merchant_account_id, currency, business_date), `created_at`·`updated_at`·`version`) · `limit_reservation`(PK `authorization_id`, FK usage, `amount_minor`, `state` CHECK 4, `excess` CHECK 3(FORCED ⇔ NOT NULL), `updated_at`, `version`) · `incident_hold_release`(append-only: PK id, `incident_hold_id` FK **UNIQUE**(해제 1회), `requested_by`·`approved_by`(CHECK 서로 다름), `reconciliation_match_ref`, `finding` CHECK 3, `correction_ref`(⇔ DOUBLE), `reason`, `released_at`) · (rev 0.3 구현 결정) `authorization_recovery_schedule`(Store ③)은 쓰기 경로가 생기는 S7 migration으로 옮긴다(관례 §9.2 "쓰기 경로가 생길 때 표·권한") |
| `db/runtime-privilege/payment/V10__authorization_runtime_privileges.sql` | §7.2 표 |
| `V11__authorization_existing_tables.sql` | `incident_hold`: `occurrence INT NOT NULL DEFAULT 1` + CHECK ≥ 1 + 여는 관찰 열 `source_attempt_id`·`source_kind`·`source_result_id`·(검토 #1 H-1) `source_variant`(rev 0.3 H-1 — 넷 다 NULL(legacy·취소 사건 hold) 또는 넷 다 NOT NULL CHECK, FK 없음: 관찰 키는 `institution_observation` UNIQUE와 같은 자연 키라 조회 가능하고, 관찰 행보다 먼저/없이 복원돼도 domain 판정이 성립한다. 새 승인 관찰 hold의 NOT NULL은 domain이 강제) + **새 UNIQUE(OrderKey, payment_id, reason, occurrence)를 먼저 추가한 뒤** 옛 UNIQUE 삭제(옛 UNIQUE의 앞부분이 FK `fk_incident_hold_order`의 인덱스 역할 — 순서를 바꾸면 1553, S2에서 실행 확인) + reason CHECK에 `CONFLICTING_INSTITUTION_RESULT`(검토 #1 n-3: 한 ALTER에서 `ck_incident_hold_reason` 삭제 + `ck_incident_hold_reason_v2` 추가 — CHECK 없는 구간 없음) · `payment_termination_evidence`: `source_kind`(OBSERVATION·ATTEMPT_ABANDON·CHECKOUT_EXPIRY)·`source_ref`·`recorded_at`·`recorded_by`(NULL = V11 이전 legacy 행, CHECK: 넷 다 NULL 또는 넷 다 NOT NULL) — AUTHN 만료 경로(`RequestedPaymentClosing`)도 이후 값을 채운다 · `idempotency_request`: `result_authorization_id` NULL, `result_detail` NULL, `ck_idempotency_request_result`를 operation별(CreatePayment 기존 행 그대로 + ConfirmPayment 행 §4.8)로 다시 만든다 |
| `db/runtime-privilege/payment/V12__…`(필요 시) | V11로 새 쓰기 경로가 생긴 표가 없으면 생략(번호는 다음 작업이 쓴다) |

- **(rev 0.7 S8 현행화 — 실제 파일)** 업무 schema `V9__authorization.sql`·`V11__authorization_existing_tables.sql`·`V12__authorization_recovery_schedule.sql`(S6, 검토 #3 m-4·n-10으로 미배포 상태에서 제자리 수정),
  권한 `db/runtime-privilege/payment/V10__authorization_runtime_privileges.sql`·`V13__authorization_recovery_privileges.sql`(회복 일정 SELECT·INSERT·UPDATE). 위 표 V12 행("필요 시")은 이 번호 배치로 대체됐다.
  `authorization_attempt`에는 `dispatch_started_at`·`dispatch_lease_until`·`resolved_at`·Route Snapshot 열이 없다 — dispatch lease는 회복 일정 행(D-S6-1), Route는 `payment_authorization.route_profile`(routeRef)·`protocol_version`,
  상태 색인은 (state, prepared_at, attempt_id). Route profile version은 저장하지 않는다(§16.17 Q-S8-1).
- `payment_authorization`과 Payment 상태 일치(OPEN ⇔ AUTHORIZING 등)는 표 사이 관계라 CHECK로 표현할 수 없다 → domain `requireSameOperation`·`reconstitute`와 같은 TX 저장 시험이 지킨다.
- 보존: 관찰·거래·해제·원장은 영구(확정 사실). 회복 일정 행 정리 Job은 후속(openItems).

### 7.2 runtime 권한(관례 §9.2 — 현재 main 코드의 실제 쓰기 경로만)

| 표 | 권한 | 쓰기 경로 | 없는 권한 |
|---|---|---|---|
| `payment_authorization` | SELECT, INSERT, UPDATE | TX1 INSERT, TXd·TX2·회복 상태 UPDATE(`FOR UPDATE`) | DELETE |
| `authorization_attempt` | SELECT, INSERT, UPDATE | TX1 INSERT, 전송·회복 상태 UPDATE | DELETE |
| `institution_observation` | SELECT, INSERT | INSERT만(`@Immutable`) | UPDATE, DELETE, 잠금 읽기(1142) |
| `payment_transaction` | SELECT, INSERT | INSERT만 | UPDATE, DELETE |
| `daily_limit_usage` | SELECT, INSERT, UPDATE | insert-if-absent(IODKU) + `FOR UPDATE` + version | DELETE |
| `limit_reservation` | SELECT, INSERT, UPDATE | 예약 INSERT, 상태 UPDATE | DELETE |
| `incident_hold_release` | SELECT | 해제 쓰기 경로 없음(RECON 작업이 INSERT 추가) | INSERT, UPDATE, DELETE |
| `authentication_session`(기존) | 변경 없음(UPDATE 있음) | `bound_authorization_id` 귀속 UPDATE | — |
| `incident_hold`·`payment_termination_evidence`·`idempotency_request`·`outbox_event`(기존) | 변경 없음 | 열 추가만 | — |

`DatabaseAccountSeparationSpec` 고정 기대값·표 목록에 새 표 7개 추가(S2 — 회복 일정 표는 S7)(새 표 추가 — 기대값 완화 아님, Reviewer 확인 대상).

### 7.3 명명(관례 §14 — 판정 기준대로)

| 계층 | 이름 | 종류 |
|---|---|---|
| provided | `ConfirmPaymentUseCase`, `RecoverAuthorizationsUseCase` | 명령 Use Case |
| required | `PaymentAuthorizationRepository` → `JpaPaymentAuthorizationRepository` | **Aggregate 저장**(AC1 §9.1의 `PaymentAuthorizationStore`를 §14 기준으로 개명 — Attempt·관찰·거래는 Aggregate 내부라 별도 Port 없음), `Versioned` |
| required | `DailyLimitUsageRepository` → `JpaDailyLimitUsageRepository` | Aggregate 저장(키 = `LimitUsageKey`, 예약 포함), 행 잠금 |
| required | `AuthenticationSessionRepository`(기존)에 `findIdByAuthenticationRef` 추가 | 기존 |
| required | `OrderPaymentControlRepository`(기존) — hold occurrence·해제 표 Mapper | 기존 |
| required | `AuthorizationRecoveryStore` → `JpaAuthorizationRecoveryStore` | Store ③(작업 기록) |
| required | `AuthorizationRecoveryCandidateReader` → `JooqAuthorizationRecoveryCandidateReader`, `AuthorizationBacklogReader` → `JooqAuthorizationBacklogReader` | jOOQ 조회 |
| required | `AuthorizationPort`, `AuthorizationInquiryPort` → `SimulatorVanAuthorizationAdapter`, `InstitutionRouteResolver` → `ConfiguredInstitutionRouteRegistry`, `AuthorizationIdentifierGenerator`(기존 생성기 관례) | 외부 기관·설정 |
| entitlement provided | `AuthorizationTermsQuery`에 정책 ID 조회 추가(AU-29) | 판정 조회 |
| 서비스 | `ConfirmPaymentService`(조정자, TX 없음), `AuthorizationStartService`(TX1), `AuthorizationDispatchService`(TXd + 호출), `AuthorizationResultService`(TX2·회복 공용), `AuthorizationRecoveryService` | TX는 `LocalTransactions` |
| web | `ConfirmPaymentController`, `ConfirmPaymentRequestParser` | `adapter.web` — provided만 |
| batch | `AuthorizationRecoveryScheduler` | `adapter.batch` — provided만 |
| Simulator | 모듈 `van`: domain `VanAuthorization`·`VanLedgerEntry`·`VanScenario`, application `VanAuthorizationService`·`VanInquiryService`, required `VanLedgerRepository`(원장 행 = 자기 식별자로 생기는 기관 소유 Aggregate, 불변이라 `Versioned` 없음 — §14 SiteActivationConfirmation 예와 같은 판정)·`VanInquiryLogStore`, adapter `adapter.integration.VanTcpServer`(inbound 소켓 — 기존 계층 규칙의 알려진 adapter 하위) | Simulator 관례(AN §13.1) |

**(rev 0.7 S8 현행화 — 실제 이름, 위 계획과 다른 것만)**: 회복 Store는 `AuthorizationRecoveryScheduleStore` → `JpaAuthorizationRecoveryScheduleStore`(Store ③, 값 `RecoverySchedule`).
jOOQ는 `AuthorizationViewReader` → `JooqAuthorizationViewReader` 하나(`find`·`findAttempt`) — `AuthorizationRecoveryCandidateReader`는 D-S7-1로 만들지 않았고(선점은 Store의 `claimDue` `FOR UPDATE SKIP LOCKED`),
`AuthorizationBacklogReader`(gauge)는 구현되지 않았다(§16.17 발견 4). Repository 조회 이름은 `lockByPaymentId`(§4.11-5의 `findByPaymentId`). 추가된 required: `EntitlementHeadLockPort` →
`EntitlementHeadLockAdapter`(entitlement provided `EntitlementHeadLock`, m-9), `LimitPolicyHistoryPort` → `EntitlementLimitPolicyHistoryAdapter`(provided `LimitPolicyLookup`, AU-29), `AuthorizationTimings` →
`AuthorizationTimingSettings`. 서비스는 `ConfirmStartService`(TX1 — 위 `AuthorizationStartService` 자리)·`ConfirmPaymentService`·`AuthorizationDispatchService`·`AuthorizationResultService`·`AuthorizationRecoveryService`.
Simulator 원장 Port는 `VanLedgerRepository`가 아니라 **`VanLedgerStore`**(find·insert만, append-only 기록) — 그 밖 `VanPendingDecisionStore`·`VanEvidenceUseStore`·`VanInquiryLogStore`, provided `AuthorizeAtVanUseCase`·`InquireAtVanUseCase`.
원장 Port 이름이 이 표의 §14 판정(Repository)과 다른 점은 가역적 명명 편차로 기록한다(Reviewer 확인 대상 — 코드 변경 없음).

### 7.4 migration 적용 순서(AC 19 — AN §22-10·11)

- `CheckoutWindowCheckMigrationStrategy`는 바꾸지 않는다: V8_1이 pending이면 ① V8까지 → ② pin → ③ 채우기 → (재확인·재채우기 최대 3) → ④ **나머지 전부(V8_1, V9~V12)**. V9 이후는 결제창 시각 열·V8_1 CHECK를
  전제해도 된다(AN §22-10). V9~V12는 runtime 채우기에 기대지 않고 pin 표를 건드리지 않는다.
- 다른 `FlywayMigrationStrategy`를 두지 않는다. 배포 전제(V7→V8_1 업그레이드 동안 AUTHN-001 이전 인스턴스 없음, AN §22-11)는 그대로이며 AUTH-001 배포에는 새 rolling 제약이 없다
  (V9~V12는 새 표·nullable 열·CHECK 재정의 — 단, AUTH-001 이전 인스턴스가 V11 뒤에도 돌면 종료 근거 출처 열을 NULL로 쓰므로 CHECK(넷 다 NULL 또는 NOT NULL)는 통과하지만 출처가 비는 행이 생긴다 →
  **배포 조건(Coordinator 판정 2026-10-18)**: AUTH-001 배포(V9~V12)도 AUTH-001 이전 payment-api 인스턴스를 모두 내린 뒤 새 인스턴스를 올린다(rolling 혼재 금지).
  이 명세와 `docs/repository/HARNESS-VERIFICATION-CONTRACT.md` §7에 기록한다(S8, AUTHN-001 선례).)
- 시험(rev 0.3 m-5 추가): **V8_1까지 적용된 볼륨**(AUTHN-001 배포 상태 — REQUESTED·AUTHORIZING·EXPIRED·hold 행) → 한 번의 기동에 V12까지 성공(전략의 "V8_1 적용됨" 경로), 기존 hold occurrence = 1·출처 NULL.
- 시험: `OldVolumeStartSpec` 확장 — V6 schema 볼륨(REQUESTED·AUTHORIZING·EXPIRED·hold 행) → **한 번의 기동**에 V12까지 성공, 기존 hold occurrence = 1, 옛 UNIQUE 없음·새 UNIQUE 있음, legacy 근거 출처 NULL,
  CreatePayment 멱등 행 CHECK 통과. `CheckoutWindowCheckMigrationStrategySpec` 회귀(뒤 migration이 V8_1 보류에 묶이지 않음 — 기존 시험이 V9~V12까지 포함하도록 기대 버전 목록 갱신).
- **(rev 0.7 S8 현행화)** 실제 범위는 V9~**V13**(V12 업무 schema 회복 일정, V13 그 권한 — §7.1 끝). 위 문장의 "V9~V12"는 V9~V13으로 읽는다. 기대 버전 목록·history 수(12 → 14) 갱신은 §16.15. 배포 조건 전체는 §20.

### 7.5 JPA 선택(이번 코드에서 하는 일 → 예 → 이유 → 주의 — 원칙 §3)

- **Entity/Domain 분리 + 새 복원 factory**: `JpaPaymentAuthorizationEntity`·`JpaAuthorizationAttemptEntity`·`JpaInstitutionObservationEntity`·`JpaPaymentTransactionEntity`는 행 모양만, `PaymentAuthorizationPersistenceMapper`가
  `PaymentAuthorization.reconstitute`(존재할 수 없는 조합 거부)로 되돌린다. 예 — `status='AUTHORIZED'`인데 거래 행이 없으면 복원 거부. 이유 — 순수 domain + DB 이중 방어(관례 §9.1). 주의 — Entity는
  data class가 아니고 toString에 기관 계약계정·증거를 넣지 않는다.
- **`@Immutable` insert-only**: 관찰·거래·해제 기록. 예 — 관찰 Entity 필드를 실수로 바꿔도 변경 감지가 UPDATE를 만들지 않고, runtime 권한도 INSERT뿐(1142). 이유 — 확정 금융 사실 append-only를 두 겹으로.
  주의 — `@OneToMany(cascade)` 없음(cascade·orphanRemoval이 사실을 지우는 경로 제거), 목록은 Repository가 따로 조회.
- **(rev 0.3 n-2로 정정) 행 잠금 + `@Version` = fencing**: TXd와 회복 Worker는 둘 다 정순서로 control → payment → payment_authorization(`FOR UPDATE`)을 먼저 잡으므로 **실제 직렬화는 비관적
  X 잠금**이 한다 — 늦은 쪽은 잠금을 기다린 뒤 최신 행을 읽어 이미 DISPATCHING(또는 ABANDONED)임을 보고 업무 판정(전송 안 함·NoChange)을 내린다. `@Version`(`UPDATE … WHERE version = ?`)은 그
  위의 두 번째 방어로, 잠금 밖에서 읽은 오래된 domain 객체(이전 TX의 Versioned)를 그대로 저장하려는 결함을 `OptimisticConflict`로 막는다(S2 시험: 오래된 version 갱신 → 충돌). 이유 — "전송 전
  실패 확정"을 DB가 증명. 주의 — 충돌 뒤 무조건 재시도하지 않고 다시 잠가 재판정(원칙 §3 `@Version` 주의). 루트 상태가 그대로이고 Attempt·관찰만 바뀌어도 루트 version을 올린다
  (`PESSIMISTIC_FORCE_INCREMENT`).
- **비관적 잠금 + insert-if-absent**: `daily_limit_usage`는 `INSERT … ON DUPLICATE KEY UPDATE version = version` 뒤 `PessimisticRowLock.lockRow`. 예 — 같은 MID 동시 confirm 8건이 이 행에서 줄을 서고 합계가
  한도를 넘지 않는다. 이유 — 서로 다른 Authorization 사이 합계는 행별 version으로 못 지킨다. 주의 — 고정 순서(§4.7), JPQL `@Lock` 조회 금지(관례 §9 — 이미 잠근 행 재읽기 문제).
- **생성 열 UNIQUE**: `in_flight_authorization_id`·`authorization_txn_key`는 Entity에 매핑하지 않는다(읽기 전용). 예 — 회복 결함으로 두 번째 진행 Attempt를 INSERT하면 flush 1062 → `UniqueConflict`. 이유 — "동시 실행
  Attempt 1"·"승인 거래 1"은 행 사이 불변식. 주의 — flush 실패 뒤 TX는 rollback 전용.
- **flush vs commit**: TX2의 관찰·거래·Outbox를 Port 끝에서 flush해 effect_key 1062·CHECK 위반을 TX 안에서 드러내고, commit 단계 실패는 OutcomeUnknown(회복이 조회로 확인).
- OSIV off·Controller는 DTO만(기존). Kafka/Redis는 이 불변식을 대신하지 않는다(ADR-001).

### 7.6 jOOQ(CQRS)

- `JooqAuthorizationRecoveryCandidateReader`(회복 후보 keyset), `JooqAuthorizationBacklogReader`(UNKNOWN 수·최고 연령·이관 수·활성 hold 수 gauge). 둘 다 비권위 — 상태 변경은 JPA 잠금 재판정.
  시각은 `DATE_FORMAT` 문자열 읽기·UTC 리터럴 비교(관례 §9.1·§13, TZ-FIX-001). jOOQ 쓰기 금지 ArchUnit 그대로. GetPayment(Authorization 현재 상태 조회)는 CP-QUERY-001.

---

## 8. 경합·장애 시험 계획(실제 MySQL 다중 연결 — Mock 단독 금지)

두 앱 사이에는 Gradle project 의존이 없다(harness `test_simulator_isolation`). 그래서 payment-api 시험은 **시험 전용 TCP stub VAN**(`src/test`, sim-van-1 golden을 따르고 원장 의미 —
같은 ID 기존 결정·종결 표지·시나리오 — 를 메모리로 재현)을 실제 소켓으로 띄워 쓰고, 원장 내구성·재시작·권한은 Simulator 시험이 실제 MySQL로 증명한다(AN §17.4·R6과 같은 구조).
아래 "Simulator 원장"은 payment-api 시험에서는 stub 원장, Simulator 시험에서는 실제 원장이다.

| # | 경합·장애 | 직렬화 지점 | 기대 |
|---|---|---|---|
| C8 | 인증 시작(TX-A) vs confirm TX1(AN §14 이관) | payment X | confirm이 이기면 인증 시작 409 화면(Payment AUTHORIZING); 시작이 이기면 VERIFIED 세션 SUPERSEDED(증거 보존 EXPIRED) → confirm `AUTHENTICATION_NOT_VERIFIED` 저장, Payment REQUESTED |
| C18 | confirm TX1 vs 주기 만료 작업(시각 경과) | control X | 만료가 이기면 confirm `PAYMENT_TERMINATED`(PaymentExpired 1); confirm이 먼저 잡으면 confirm 자신이 인라인 만료 → 작업은 `NotRequested` NoChange. 어느 쪽이든 EXPIRED 1회·근거 1행·PaymentExpired 1건 |
| C19 | confirm TX1(시각 전) vs 만료 작업(시각 직후) | control X | confirm이 이기면 AUTHORIZING → 작업 NoChange; 작업이 이기면 confirm 409 |
| C20 | confirm 인라인 만료 2(다른 키) + 작업 2 + CreatePayment 인라인(AN C16 확장) | control X, Outbox effect_key | EXPIRED 1, PaymentExpired 1, 1062 없음, 각 키 결과 PAYMENT_TERMINATED |
| C21 | 같은 Payment 동시 confirm N=8(같은 키·다른 키·같은 내용·다른 authenticationRef 섞음) | 멱등 행 → control/payment X | Authorization 1·Attempt 1·**Simulator 원장 요청 1**, 같은 내용은 같은 authorizationId 참조, 다른 내용 `CONFIRM_CONTENT_CONFLICT`(rev 0.5: 다른 authenticationRef 요청이 Authorization보다 **먼저** 잠금을 잡으면 §4.3-6 `AUTHENTICATION_NOT_VERIFIED` — 승자 순서에 따른 두 결과 모두 거절·Authorization 1) |
| C22 | 같은 MID·날짜 다른 Payment 동시 confirm(한도 경계) | daily_limit_usage X | 예약 합 ≤ 일 한도, 초과분 `LIMIT_EXCEEDED`(Payment REQUESTED) |
| C23 | TXd vs 회복 PREPARED fencing | attempt version | 한쪽만 성공: DISPATCHING이면 전송 1, ABANDONED면 전송 0·FAILED(PRE_TRANSMISSION) |
| C24 | TX2 vs 회복 lease 만료 표시(n-3) | attempt X | 어느 순서든 예외 없음, 최종 상태 하나(RESOLVED 뒤 표시는 NoChange) |
| C25 | TX2 vs 회복 조회 반영(같은 결과) | 관찰 키 | AUTHORIZED 1, 거래 1, PaymentAuthorized 1 |
| C26 | 차단 Snapshot 적용(head X) vs TXd(head S) | head | ACK 뒤에는 이전 권리로 DISPATCHING이 커밋되지 않음(MS1·MS2 선례) |
| C27 | 같은 늦은 성공 동시 8회(응답·조회·주입) | control X, hold UNIQUE(occurrence) | hold 1, 사건 이벤트 1(F-1), 1062 없음. (rev 0.3 n-8) 결과 반영 서비스에 관찰을 직접 주입해 구동 |
| C28 | (rev 0.3 m-2) 전송자의 connect 재시도 중 lease 만료 → 회복이 OUTCOME_UNKNOWN 표시 → 전송자의 NotSent 보고 | attempt X | NotSent는 NoChange, UNKNOWN 유지, 조회로 확정. 전송자는 deadline 재확인으로 다음 connect를 하지 않음 |
| C29 | (rev 0.3 m-3) TXd commit 불명확(commit 중 연결 끊김 주입) | — | 전송 0, confirm 202, 회복이 실제 커밋 여부에 따라 fencing(PREPARED) 또는 lease 만료(DISPATCHING) 경로로 정리 |
| C30 | (rev 0.3 H-3, Simulator) PENDING 결정 확정 vs `closeIfAbsent` 동시 / AUTH 요청 vs 종결 동시 | 원장 UNIQUE(namespace) | 원장 1행, 진 쪽은 그 행 결과로 응답 |

장애 주입(`FaultInjectionConfig`):

| 지점 | 기대 |
|---|---|
| TX1 각 Port flush 직후(멱등·payment·authorization·attempt·session·예약·인라인 만료 단계) | 모든 행 rollback(Authorization·Attempt·귀속·예약·멱등·PaymentExpired 고아 0), Simulator 수신 0 |
| TXd flush 직후 | PREPARED 유지·전송 0 → 회복 fencing → FAILED(PRE_TRANSMISSION) |
| 기관 승인 뒤 TX2 flush 직후·commit 중(AC1 §6-5) | PG AUTHORIZING/UNKNOWN 유지, Simulator 원장 APPROVED 1 → 회복 조회 → AUTHORIZED 1·PaymentAuthorized 1·**원장 승인 1(재승인 없음)** |
| TX2 commit 불명확 | confirm 202, 같은 키 재요청이 현재 결과로 수렴 |
| 응답 유실(`VC-VAN-DROP-AFTER-APPROVE`)·지연(`VC-VAN-LATE-RESPONSE`)·끊김 전 미기록(`VC-VAN-DROP-BEFORE-RECORD`, 프로파일 A·B 각각) | §5.3 표대로 |
| PG 재시작(DISPATCHING 중) | lease 만료 → OUTCOME_UNKNOWN → 조회로 1건 확정 |
| Simulator 재시작 | 원장 존속, 조회 FOUND 동일(AC1 §6-1) |
| 같은 요청 ID 재전송(connect 재시도)·다른 payload | 기존 결정 재응답 / REQUEST_CONFLICT, 원장 행 1 |

---

## 9. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 증거(시험·근거) |
|---|---|---|
| Financial Consistency | 적용 | 승인 1건 = 거래 1건 = PaymentAuthorized 1건(금액·통화·Plan·cardType 보존), 부분 승인·조건 불일치는 거래 없음(D-AUTH-008), 관찰·거래·해제·원장 append-only(1142·`@Immutable`), AUTHORIZED 불가역, 한도 합 ≤ 일 한도(C22), m-10·m-11 판정 |
| Transaction Safety | 적용 | TX1·TXd·TX2 각 flush 뒤 실패 주입 rollback, 외부 호출 중 TX·잠금 없음(`VanCallOutsideTransactionSpec`), 인라인 만료가 커밋되는 결과 값, commit 불명확 처리 |
| Idempotency | 적용 | confirm 같은 키/다른 키/다른 내용·30일 재생(현재 결과), 같은 기관 요청 ID 재전송 = 기존 결정, 관찰 키 DUPLICATE, effect_key UNIQUE(사건 occurrence·F-1), 재시작 뒤 재요청 |
| Concurrency Control | 적용 | C8·C18~C27 실 MySQL, 생성 열 UNIQUE 2개 + UNIQUE(payment_id)·namespace·hold occurrence, attempt fencing, 사용량 행 잠금, 잠금 순서 §4.7 |
| Recoverability | 적용 | 응답 유실·끊김·부분 전문·지연·TX2 장애·PG/Simulator 재시작 → 원 Attempt 조회로 1건 확정, 예산 소진 → 이관(UNKNOWN 유지), 무효과 종결 표지로 늦은 원 요청 무효 |
| Reconciliation | 적용(경계) | `AuthorizationIncidentDetected`·`AuthorizationOutcomeUnresolved` Outbox 원자성·effect_key, 대사 반영 경로 열어 둠. Case·MATCH·해제 실행은 RECON-001(N/A 이유: FINANCIAL 소유) |
| Auditability | 적용 | 종료 근거 출처·시각·행위자(V11), 판정 근거 버전(권리·정책 Assignment·Route·프로토콜·Plan·한도)·evaluatedAt 고정, Attempt namespace·Route Snapshot(routeRef + **전송 때 profile version — V14, D-AUTH1-007**), 관찰 evidenceHash, 회복 예산 설정 버전, 멱등 결과·인과 값 |
| Observability | 적용 | counter `paycore.payment.confirm{result}`·`paycore.authorization.dispatch{outcome}`·`paycore.authorization.inquiry{result}`·`paycore.authorization.recovery{action}`, timer `paycore.authorization.call.duration`, gauge UNKNOWN 수·최고 연령·이관 수·활성 hold 수(label에 ID 금지), correlationId MDC, 로그에 카드·Secret·증거·institutionMerchantRef·전문 원문 없음(캡처 시험). **S8 구현(§16.18)**: dispatch·inquiry counter·call.duration timer{operation}·gauge 4종 구현. 회복 counter는 기존 이름 `paycore.payment.authorization_recovery{result}` 유지(이름 변경은 기존 시험 기대값 변경이라 하지 않음) |

---

## 10. BOM·Kafka/Redis

- **BOM**: 새 의존성 없음. Boot 4.1.1 BOM·Kotlin 2.3.21·Modulith 2.1.1(`gradle/libs.versions.toml`) 그대로. TCP는 JDK `Socket`/`ServerSocket`, HMAC은 JDK `javax.crypto.Mac`, JSON은 기존 Jackson(BOM 관리).
  GRADLE-DEPS 5모듈 트리 hash가 이전 회차와 같아야 하며(다르면 이유 기록), CVE-SCAN 대상 모듈 변화 없음.
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 중복 승인 차단(슬롯·Attempt 1·UNIQUE·기관 같은 ID)·한도 합·멱등·hold의 권위는 PAYMENT DB 제약·잠금·로컬 TX다. 회복 Worker 다중 인스턴스도 DB 행 잠금(SKIP LOCKED)으로
  직렬화하며 Redis 분산 lock이 필요하지 않다. Kafka 트랜잭션이 DB·가상 VAN을 아우르는 exactly-once를 만들지 않는다(원칙 §5).

---

## 11. 단계별 구현 계획(2단계 TDD — 각 단계 시험 먼저 → RED 확인 → 최소 구현)

| 단계 | 내용 | 의존·막힘 | 시험(추정) |
|---|---|---|---|
| S0 | 질문 답 반영(rev 0.2 — 완료), 2단계 기록 절 개설, 기준 시험 수 측정 | 없음 | — |
| S1 | domain: L-3 출처 제한 + F-2 재조사 표 + 영향 시험 재설계(legacy 행), m-10·m-11·nit 3, n-3, n-4, `PaymentAuthorization.reconstitute`, `preflight`, F-1 새 hold 판정, `ProviderRequestRef` 마스킹, RSAT 삭제(AU-18)·시험 이전 | 없음 | 50~70(+ 재설계 행 수정) |
| S2 | V9~V11(+V12)·JPA Repository/Store·Mapper·스키마/권한 시험·옛 볼륨 기동·전략 회귀 | S1 | 55~75 |
| **검토 #1** | S1·S2 Reviewer 중간 검토(특히 AC 8 "완화 아님", 권한 기대값 추가) | — | — |
| S3 | Simulator `van`: 프레임·HMAC·원장·판정·조회·종결 표지·시나리오·인증 근거 재검증·표 단위 권한(AU-20)·재시작 내구성·ArchitectureSpec·계약 golden(harness) | AU-20 Coordinator 확인 | 55~75 + harness 2~3 |
| S4 | PG TCP Adapter·Route 등록부·격리(기동 검사·ArchUnit·위반 fixture)·stub 서버 프레이밍 시험(부분 read·복수 프레임·상관관계·MAC·timeout 종류) | S3(golden) | 40~55 |
| **검토 #2** | S3·S4 | — | — |
| S5 | confirm API·파서·멱등(c1)·TX1(인라인 만료·Admission 같은 at·`AuthorizationStart`·거절 매핑 D-AUTH1-001·002)·cardType·Plan 행렬(D-AUTH1-003)·결제창 Plan Demo 설정 일치(AU-31)·C8·C18~C22·TX1 rollback | S2 | 75~100 |
| S6 | TXd·호출·TX2·Outbox(effect_key·F-1)·응답 유실·TX2 장애·commit 불명확·C23·C25·C27 | S4, S5 | 30~45 |
| S7 | 회복 Worker(lease·fencing·조회·예산·이관·느린 조회)·C24·C26·PG/Simulator 재시작·관측 지표·로그 비노출 | S6 | 30~45 |
| **검토 #3** | S5~S7 | — | — |
| S8 | 문서(관례 새 절 §16 AUTH-001, DOMAIN-DEFINITION, HARNESS-VERIFICATION-CONTRACT §7, AC1 §19 편차 표시), 하한 갱신, 전체 check → VERIFY-GATE·DEPS×5·CVE 보고 | 전부 | — |

- **시험 수 기대**(rev 0.2, D-AUTH1-003으로 +20~30): payment-api + Simulator 합계 약 +335~465, harness +2~3. GRADLE-CHECK minTests 1586 → 실측 합계(약 1,920~2,050), HARNESS-SELFTEST 81 → 실측(약 83~84). 하한은 실측으로만 올리고
  내리지 않는다(기대값 완화 금지).
- **하네스 등록**: `harness/commands.json`(GRADLE-CHECK·TEST-SUMMARY 하한, HARNESS-SELFTEST 하한), `harness/tests/test_van_contract_golden.py`(sim-van-1 요청·응답·프레임·MAC 벡터를 표준 라이브러리로 생성해 두 앱
  시험 자원과 바이트 비교 — AN §17.4 방식), `harness/tests/test_simulator_isolation.py` 확장(VAN 모듈도 PayCore 앱 패키지·project 의존 없음), `compose.yaml`·`.env.example`(VAN 포트·HMAC 시험 키·프로파일),
  `docker/simulator-mysql/init/`(AU-20 채택 시 grantor·procedure). 새 실행 단위 없음 → `cve-scan.py` MODULES 변화 없음.
- **TDD 편차 방지**: AC1·MS2·AUTHN 2단계는 일부 production 선작성으로 변이 RED를 대신했다. 이번에는 단계마다 RED-C(컴파일)·RED-A(실행)를 먼저 기록하고 핵심 규칙 변이 RED(예: 게이트 생략,
  인라인 만료 생략, timeout을 FAILED로, 같은 활성 hold에 사건 이벤트 발행, FORCED 마지막 판정만, 원장 UPDATE 허용)를 2단계 기록 절(§16 이후, 구현 중 추가)에 남긴다.
- timeout(Coordinator 판정 2026-10-18): 단계별 모듈 시험 30분, VERIFY-GATE 90분. 수정 반복은 실패당 3회.

---

## 12. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| AU-01 | confirm 경로·202(CA-18), 본문 4필드 닫힌 집합 | 기존 계약 |
| AU-02 | 재진술 불일치 = `CONFIRM_CONTENT_CONFLICT`(CA-08) | fail-closed |
| AU-03 | fingerprint `c1:`(CA-06), 보존 30일(CA-07) | 기존 방식 |
| AU-04 | (rev 0.3 m-9로 대체) head(S)를 멱등 뒤 먼저 entitlement provided `lockHeadForShare(merchantId)`로 확보(TX1·TXd 공통), 평가는 마지막 잠금 뒤 같은 `at`으로 1회. rev 0.2의 예비 평가는 삭제 | F-7 교착 회피, MS2 §6.4 같은 at |
| AU-05 | `AuthorizationStart.preflight` 읽기 전용 사전 검사, `start`는 전부 재검사 | 거절 순서·L-2 유지 |
| AU-06 | 잠금 순서 §4.7(TXd·TX2·회복은 control부터 정순서 부분열) | 교착 방지, CC §9.1 |
| AU-07 | dispatch lease + 전송자 자기 deadline 확인 뒤 connect | lease 만료 ≠ 호출 안 함(A06) 보완 |
| AU-08 | PG Adapter는 요청당 연결 1, 프로토콜은 다중 요청·상관관계 지원 | 단순성, 상관관계 계약은 Simulator 시험 |
| AU-09 | 프레임 HMAC-SHA256(시험 키), TLS는 v1 미적용(LIVE Gate) | AC1 §9.2 "HMAC 시험 키·TLS는 Simulator 프로파일" |
| AU-10 | 프레임 payload = UTF-8 JSON(기존 Jackson) | 새 의존성 없음 |
| AU-11 | Simulator 모듈 `van`, JDK ServerSocket + virtual thread, 별도 포트 | CA-20 |
| AU-12 | VAN 시나리오는 가상 카드 카탈로그 확장 | AUTHN 카탈로그와 같은 결정 가능 방식 |
| AU-13 | VAN이 인증 근거를 인증 모듈 provided로 재검증 | 기관 재검증(범위 §2), 모듈 분리 유지 |
| AU-14 | 무효과 확정 = 원장 종결 표지 + 늦은 원 요청 `REJECTED_CLOSED` | "무효과"가 기관 쪽에서 참 |
| AU-15 | Capability 프로파일 A(무효과 확정)·B(없음) 둘 다 | 허용 §5.2 |
| AU-16 | 회복 bookkeeping은 domain 밖 Store 표(`authorization_recovery_schedule`) | domain 순수성 |
| AU-17 | 사건 이벤트는 새 hold append 때만 | F-1 |
| AU-18 | `ReportSuccessAfterTerminationService` 삭제, 늦은 성공은 Resolution 경로 | L-6, D-AUTH-012 중복 hold 방지 |
| AU-19 | V9 새 표 / V10 권한 / V11 ALTER / V12 권한(필요 시) | MySQL DDL 비TX — 반쯤 적용 방지 |
| AU-20 | Simulator에 §9.2식 표 단위 권한 적용(Coordinator 채택) | AC 17, 원장 증거력 |
| AU-21 | confirm 동기 대기(Demo) 후 202(CA-21) | 회복 경로 존재 |
| AU-22 | 이관 뒤 보존기간까지 느린 조회 계속 | 자동 회복 기회, 반영 멱등 |
| AU-23 | 공용 `payment_transaction` 표, type CHECK는 AUTHORIZATION만 | CANCEL-001 준비, 쓰지 않는 값 없음 |
| AU-24 | 종료 근거 출처 열 nullable(legacy) + 쌍 CHECK | 기존 행 보존 |
| AU-25 | `markOutcomeUnknown` 최종 상태 NoChange. (rev 0.3 m-2) `abandonBeforeDispatch`도 늦게 도착한 포기는 NoChange: connect 실패인데 Attempt가 OUTCOME_UNKNOWN(lease 회복이 먼저)·이미 포기·결론난 Attempt. PREPARED의 connect 실패·DISPATCHING의 fencing은 결함 | n-3, m-2, n-6 |
| AU-26 | ~~게이트가 인증 증거 유효도 확인(만료면 GATE_REFUSED)~~ — **미채택(D-AUTH1-004, U-1 사용자 답변 권장안)**: 게이트는 인증 증거 TTL을 보지 않고 기관 재검증이 만료 증거를 확정 거절한다 | H-2, D-AUTH1-004 |
| AU-27 | `ProviderRequestRef.toString` 마스킹 | F-3, D-MS2-005 |
| AU-28 | TX 실패 분류: 교착·version → 재시도 3, timeout·연결 → 503(TX1)/다음 주기(TX2·회복), commit 불명확 → 503(TX1)·202(TX2). (rev 0.3 m-3) **TXd commit 불명확이면 전송하지 않는다**(DISPATCHING 커밋이 확인될 때만 connect) — confirm 202, 회복이 그 Attempt를 PREPARED(fencing)·DISPATCHING(lease 만료) 어느 쪽으로든 정리 | 관례 §9, m-3 |
| AU-29 | entitlement provided에 정책 ID로 한도 정책 값 조회(revision 내용) 추가 | FORCED 초과 표시 = 원 Authorization 고정 정책(AC 15) |
| AU-30 | 기관 요청 ID `ar_`+ULID(CA-22), 조회 ID `aq_`+ULID, VAN `decisionId`·`closureId`는 Simulator 생성 | 접두 관례 |
| AU-31 | 결제창 Plan Demo 정책(AN §13.6)과 VAN Route 프로파일 할부·cardType Capability가 다르면 payment-api 기동 실패. (rev 0.3 n-7) 비교는 통화별 허용 개월·할부 최소금액. 체크카드 할부 거절은 설정이 아니라 고정 domain 규칙(`PaymentPlanRule`). TX1의 `PaymentPlanPolicy`는 PAYMENT 로컬 인증 프로파일(AN-21)의 버전 있는 Demo 정책, 사전 검사는 통화를 `PaymentPlanRule`보다 먼저 본다 | D-AUTH1-003 "결제창 Demo 설정 일치" — 인증 뒤 거절 경로 방지 |
| AU-32 | (rev 0.3 m-1) TX1 `at`은 사용량 잠금 전에 정하고, 잠금 뒤 Asia/Seoul 업무일이 바뀌었으면 TX 재시작 | 같은 at(MS2 §6.4)과 사용량 행 키의 순환 제거 |
| AU-33 | (Coordinator 판정 rev 0.3) 게이트의 일시 Admission Unavailable(Snapshot lease 만료 등)은 `preparedDeadline`(Demo)까지 TXd를 재시도하고, 그래도 Unavailable이면 GATE_REFUSED → FAILED(PRE_TRANSMISSION). Refused(차단·카드사 정지)는 즉시 GATE_REFUSED | 짧은 장애가 종료 실패가 되지 않게 |
| AU-34 | (rev 0.3 m-2) 전송자는 connect 재시도마다 dispatch lease deadline을 다시 확인하고, 기동 시 `connectTimeout × connectRetryBudget < dispatchLease − safetyMargin`을 검증(위반 → 기동 실패) | lease 만료 뒤 늦은 첫 송신 방지 |

---

## 13. 질문 후보(Coordinator → 사용자) — 답변됨(rev 0.2)

### 13.0 답변 대응표

| 질문 | 답변 | 결정 | 반영 |
|---|---|---|---|
| Q-AUTH1-01 | 권장안: 503 `TEMPORARILY_UNAVAILABLE`, 결과 미저장, Payment REQUESTED, 정책 등록 뒤 같은 키 재시도 성공 | **D-AUTH1-001** | §2 AC 10·15, §4.3-10, §4.8, §4.9 |
| Q-AUTH1-02 | 권장안: KRW만, Demo `supportedCurrencies=[KRW]`, 다른 통화 422 `PAYMENT_NOT_ELIGIBLE`(저장, 전송 없음) | **D-AUTH1-002** | §4.3-9, §4.9, §5.8 |
| Q-AUTH1-06(검토 #3, NO_EFFECT 해제 시 흡수 승인) | NO_EFFECT 해제도 같은 규칙 — 남은 흡수 승인이 있으면 같은 연산에서 occurrence + 1 | **D-AUTH1-006** | §4.11-2, §16.16 |
| Q-AUTH1-05(검토 #2 m-D, P-1) | 흡수된 승인 중 REAL·DOUBLE 해제가 설명하지 못하는 것이 남으면 해제와 같은 TX에서 occurrence + 1 hold(fail-closed, 출처 = 그 관찰 (key, variant)) | **D-AUTH1-005** | §4.11-2, §16.14 |
| Q-AUTH1-03 | **권장안 아님** — 신용 일시불·체크 일시불·일반 할부 모두 AUTH-001에서 개방(D-AUTH-001 개방 순서 대체, 영향 설명 뒤 재확인). 체크카드 할부는 거절 유지. 체크·할부의 취소 검증은 CANCEL-001 | **D-AUTH1-003** | §1.2, §4.3-9, §4.9, §5.3, §5.8, §11 S5, AU-31 |

아래 원문(rev 0.1)은 기록으로 둔다.

### Q-AUTH1-01 한도 정책 없음·모호(`LimitPolicyUnavailable`)와 승인 조건 판정 불가의 confirm 응답(n-2)

- **배경**: domain `AuthorizationStart`는 적용 한도 정책 0개·2개 이상을 업무 거절 결과(`Rejected(LimitPolicyUnavailable)`)로 돌려준다(D-AUTH-006 fail-closed). 반면 AC1 §4.6 표(1단계 명세 문장)는
  "판정 불가(Snapshot 없음·만료·미지원 버전·정책 없음)는 503, 저장 없음"이고, MS2의 정책 Assignment 없음(A3)은 Port가 `Unavailable`로 준다. D-MS2-006은 신규 MID가 첫 정책 효력 전까지
  `LimitPolicyUnavailable`로 막히고 운영 절차(개통 전날 등록)로 해소한다고 정했지만 가맹점 응답은 정하지 않았다. AC 10(n-2)이 통일을 요구한다.
- **권장안**: **503 `TEMPORARILY_UNAVAILABLE`, 키 결과 저장 안 함**(정책 Assignment 없음·Snapshot 만료와 같은 응답). Payment REQUESTED 유지, 세션 미귀속. 이유: 원인이 가맹점 요청이 아니라 PG 기준정보 미비·전파 지연이라
  운영자가 정책을 등록·전파하면 **같은 키** 재시도로 승인이 시작돼야 한다. 저장 거절이면 30일 동안 같은 키가 같은 거절을 재생해 가맹점이 새 키·재인증을 해야 한다(D-CPC-007은 업무 거절용). CreatePayment
  eligibility Unavailable(저장 없음)과도 같은 관례다.
- **대안**: 422 `LIMIT_POLICY_UNAVAILABLE`(또는 `PAYMENT_NOT_ELIGIBLE`) 저장·30일 재생 — domain 분류(Rejected)와 이름이 맞고, 정책 공백 기간의 반복 요청을 줄인다. 재시도는 새 키로만.
- **영향**: 가맹점 재시도 방식, 멱등 CHECK 값 1개, 시험 기대값 3~5행. 전송 전이라 금융 효과 없음.
- **막히는 부분**: S5의 이 매핑 행만(나머지 confirm 경로는 진행).

### Q-AUTH1-02 KRW 외 통화(예: USD) Payment의 승인

- **배경**: D-CP-002는 CreatePayment가 ISO 통화를 일반적으로 다루고 초기 구현·검증을 KRW·USD로 하며 허용 통화는 MID·기관 계약별 기준정보로 연다고 정했다. 그 뒤 D-AUTH-001은 카드 승인 범위로 범위 문서 §1(KRW)을
  채택했다. 외화 정산 통화·환율은 미결(Q-SETTLE-CURRENCY). 지금 MID에 USD 통화 활성화·정책 Assignment·한도 정책이 있으면 USD Payment가 결제창·인증(Plan Demo 정책 통화 KRW)을 지나 confirm까지 올 수 있다.
- **권장안**: **승인은 KRW만 연다** — 가상 VAN Route 프로파일 Capability `supportedCurrencies = [KRW]`(Demo, 버전 있는 설정)로 KRW 외 confirm은 승인 시작 거절(422 `PAYMENT_NOT_ELIGIBLE`, 저장, 전송 없음,
  Payment REQUESTED 유지). 이유: D-AUTH-001이 카드 승인 범위를 더 뒤에·더 구체적으로 정했고, USD `PaymentAuthorized`가 나가면 FINANCIAL이 정산 통화·환율 기준 없이 받게 된다. 코드는 통화 일반(minor unit)을
  그대로 다루므로 나중에 프로파일 값만 바꾸면 열린다.
- **대안**: TEST 전용으로 가상 VAN이 USD도 승인(2자리 minor unit 종단 검증), `PaymentAuthorized`에 거래통화를 보존만 하고 정산은 SETTLEMENT 결정 대기.
- **영향**: 시험 범위(USD 행 4~8건), Route 프로파일 값, FINANCIAL 후속 작업의 입력.
- **막히는 부분**: KRW 외 confirm 시험 행만.

### Q-AUTH1-03 체크카드 일시불·일반 할부 승인의 개방 시점

- **배경**: D-AUTH-001은 계약에 PaymentPlan·cardType을 처음부터 넣고, 개발 순서를 "KRW 신용 일시불 수직 기능 → UNKNOWN·취소 검증 → 체크 일시불·일반 할부"로 정했다. domain(체크 할부 거절 포함)과
  AUTHN 결제창(카드사·Plan 선택, Demo 할부 개월 [2,3,6])은 이미 셋 다 다룬다. 취소 검증은 CANCEL-001(AUTH-001 뒤)이다. 개발 순서를 이 작업의 승인 허용 범위로 어떻게 적용할지는 정해지지 않았다.
- **권장안**: **AUTH-001은 신용 일시불만 승인을 연다.** 전문·domain·Simulator·영속은 Plan·cardType 전체를 담고(재작업 없음), 승인 허용은 버전 있는 Demo Capability(`supportedCardTypes = [CREDIT]`,
  `installment = 불가`)로 막는다 — 미개방 조합은 422 `PAYMENT_PLAN_NOT_ALLOWED`(저장, 전송 없음). 체크카드 할부 거절은 지금도 domain·VAN 이중 검증(확정). 결제창 Plan Demo 정책도 같은 값으로 맞춰
  고객이 인증 뒤 거절되는 경로를 줄인다. CANCEL-001 뒤 프로파일 값 변경 + 시나리오 시험으로 연다(가칭 AUTH-PLAN-001). 이유: 사용자 결정의 순서를 지키면서 개방은 설정 변경으로 끝난다.
- **대안**: AUTH-001에서 셋 다 연다(시나리오·시험 +20~30건, 결정 순서보다 앞섬).
- **영향**: AUTHN 결제창 Demo 설정 값(시험 설정 포함), 후속 작업 1개, 시험 범위.
- **막히는 부분**: 체크 일시불·할부 confirm 시험 행과 결제창 Demo 설정 변경만.

### 13.3 검토했으나 질문으로 올리지 않은 것

| 항목 | 처리 근거 |
|---|---|
| TCP vs HTTP | 확정: 초기 §59, 허용 §5.1, D-AUTHN-004 |
| timeout·조회 NOT_FOUND·lease 만료 종료 | 확정: D-CPD-002, 시도 §2·§4 — UNKNOWN 유지 |
| 조회 예산·이관 기준·confirm 대기 수치 | 확정: D-AUTH-009(버전 있는 설정, Demo) |
| 이관 뒤 조회 계속 여부 | 구현 결정 AU-22(조회 읽기 전용·반영 멱등 — 금융 의미 변화 없음) |
| 무효과 확정 Capability와 종결 표지 | 확정 허용 §5.2(가상 규격·Capability 프로파일 둘 다), D-CPD-002 — 가상 기관 규격 설계 |
| REAL/DOUBLE 해제 뒤 같은 terms·거래 기록 FAILED 같은 terms(m-10·nit 3) | 도출: D-AUTH-013 "다른 기관 거래 식별자" |
| FORCED 정리(m-11) | 도출: D-AUTH-010 "REAL이면 소비" |
| 게이트에서 인증 증거 만료 시 전송 안 함 | **답변됨 D-AUTH1-004**(U-1, 권장안): 게이트 검사 없음, 기관 재검증 → 확정 거절 |
| hold 중 confirm·UNKNOWN 중 새 시도 | 확정: D-CPD-004, 시도 §2·§6 |
| 늦은 성공 자동 보상취소 | 확정: D-AUTH-002(없음) |
| Simulator 원장 권한 | 기술 선택(AU-20) — Coordinator 확인 |
| 통보(push) 채널 | 범위(§14-1) — Coordinator |
| 열린 질문(Q-CALENDAR·Q-CORRECTION·Q-SETTLE-CURRENCY·Q-BATCH-*·Q-MD-06/07/18·Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF) | 이 작업을 막지 않음. 기관 업무일·거래일은 원장·관찰·이벤트에 보존만(정산 날짜 규칙은 Q-CALENDAR) |

---

## 14. 범위 관찰(Coordinator 확인)

**Coordinator 판정(2026-10-18)**: (1) 통보(push) 채널 없음 — 통보 Inbox는 후속. (2) AU-20 채택. (3) TLS는 LIVE Gate. (4) 제목의 "취소"는 CANCEL-001(사용자가 AUTH-001 바로 다음에 배치).
(5) V11 배포 조건을 이 명세 §7.4와 HARNESS-VERIFICATION-CONTRACT §7에 기록. (6) MS2 n-3/n-5는 AUTH-001 범위 밖 — 후속 후보. (7) D-AUTH1-003으로 결제창 Plan Demo 설정은 세 조합 모두 허용으로 일치(AU-31).
추가: AU-18(RSAT 삭제)·AU-17(F-1)·AU-27(F-3)·AU-04(F-7) 채택, F-2는 S1에서 재조사·기록. 아래 rev 0.1 원문은 기록으로 둔다.

1. **기관 통보(push) 채널**: L-6 AC의 "호출 경로(Inbox)"는 통보 채널을 전제한 표현이다. 권장: AUTH-001은 응답·조회만(가상 VAN 통보 없음), 통보 Inbox(서명·nonce·내구성 기록 뒤 응답 — A04)는 후속 작업.
   늦은 성공의 다른 경로(대사·이중 승인)는 Simulator 원장 주입 시험으로 Resolution 경로를 검증한다.
2. **AU-20 Simulator 표 단위 권한**: 채택 시 `docker/simulator-mysql/init/` 변경(허용 경로 안 `docker/**`)과 기존 Simulator 시험 계정 기대값 변경(새 기준 — 완화 아님).
3. **TLS**: v1 가상 VAN은 HMAC만(AU-09). TLS·mTLS는 실제 기관 규약과 함께 LIVE Gate 항목으로 openItems 기록 제안.
4. **취소 제목**: AUTH-001 제목 "승인·취소·조회"의 취소는 CC §14 판정으로 CANCEL-001 소유 — tasks.json 제목 정정은 Coordinator 판단.
5. **AUTH-001 배포 메모**: V11 뒤 구 인스턴스가 종료 근거를 출처 없이 쓰는 것을 막으려면 구 인스턴스를 먼저 내린다(§7.4) — 배포 runbook 기록.
6. **MS2 review round1 n-3·n-5(선택 항목)**: MS2 §13.2가 "Coordinator 판단(후속 또는 AUTH-001)"으로 남긴 미처리 항목. 이 명세는 내용을 다시 다루지 않았다 — Coordinator가 포함 여부를 정한다.
7. **결제창 Plan Demo 설정**: Q-AUTH1-03 권장안이면 AUTHN Demo 설정(시험 설정 포함)을 바꾼다 — AUTHN 시험의 Plan 관련 기대값이 설정에서 오는지 S5에서 확인하고, 바뀌는 행은 결정 반영으로 기록.

---

## 15. 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 규모(새 표 8·ALTER 3·Use Case 5·TCP 양쪽·Simulator 모듈·시험 재설계 11+파일) — 단일 gate 실패 시 재작업 큼 | 검토 #1~#3, 단계별 모듈 시험 |
| R2 | S7 시험 재설계가 기대값 완화로 보일 위험 | legacy 행 이름 규칙·Reviewer 확인 항목(AC 8), 기대값 표 불변 |
| R3 | TCP 시험의 시간 의존(지연·timeout) 불안정 | Simulator 가상 시계·짧은 Demo timeout·명시 동기화(latch), 재시도로 통과시키지 않음 |
| R4 | `incident_hold` UNIQUE 교체 DDL 순서(FK 인덱스) | ADD 뒤 DROP, 옛 볼륨 시험 |
| R5 | 일 한도 행 핫스팟 | 초기 허용(CA-10), 지표로 관찰 |
| R6 | 회복 Worker와 confirm 동기 경로의 이중 처리 | 관찰 키 멱등·attempt fencing·잠금 재판정(C24·C25) |
| R7 | 프로세스 간 자동 E2E가 gate 밖(앱 간 project 의존 없음) | 공유 golden + payment-api 시험 전용 TCP stub VAN(실제 소켓) + Simulator 자체 TCP·원장 시험 + compose 수동 smoke 기록(AN R6과 같음). stub과 Simulator가 갈라지지 않음은 golden 벡터로만 보장된다는 한계를 기록 |
| R8 | 무효과 종결 표지가 실제 VAN에도 있다고 오해 | 가상 규격 명시(허용 §5.6) — 실제 기관 전환 시 별도 Gate 재검증 |

---

## 16. 2단계 실행 기록(구현 중 갱신 — 구현자 보고, Verifier/Reviewer 판정 아님)

### 16.1 S0

- rev 0.2 반영(D-AUTH1-001~003, Coordinator 판정). 기준 시험 수·RED 기록은 아래 절에 단계별로 쌓는다.

### 16.2 S1 — domain 보강·L-3 시험 재설계·RSAT 삭제(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| L-3(AC 6·8) | `Payment.close`: REQUESTED에서 근거는 없음·EXPIRED + NOT_TRANSMITTED_AND_SESSION_DISCARDED만 | `PaymentSpec` REQUESTED × 근거 표(14행) + legacy 복원 행. **RED-A**: 13행 실패(제한 전) |
| m-10·nit 3 → H-1 | `AuthorizationResolution.explainedByRealRelease`: 설명 집합 = 기록 거래 terms ∪ REAL·DOUBLE로 해제된 hold를 **연 관찰** terms. `IncidentHold.sourceObservation`(같음 = paymentId·reason·occurrence), hold 개설 연산 3개가 non-null 관찰 키를 받음 | `AuthorizationResolutionSpec` m-10·nit 3·H-1·"REAL(X)→NO_EFFECT(Y)" 행. **RED-A**: m-10·nit 3 2행 실패(구현 전), H-1은 RED-C(`sourceObservation` 없음) 뒤 구현 — **변이 RED** h1(설명 집합을 모든 관찰로) → 4 실패 |
| m-11 | `IncidentHoldSettlement`: 해제 기록 중 REAL·DOUBLE이 있으면 FORCED → CONSUMED | `IncidentHoldReleaseSpec` 2 hold 행. **RED-A** 1 실패 |
| n-3 | `markOutcomeUnknown`: RESOLVED·ABANDONED·최종 상태면 NoChange | 4행. **RED-A** 3 실패 |
| m-2(domain) | `abandonBeforeDispatch(…, at)`: 늦은 connect 실패(OUTCOME_UNKNOWN)·재포기·결론난 Attempt는 NoChange, PREPARED의 connect 실패는 결함 | 3행(RED-C: `at` 인자) |
| m-6 | `TerminationEvidenceSource`(종류·참조·기록 시각·기록자 — 자동 경로 `system:payment-api`)·`TerminationEvidenceRecord`, Payment는 새 근거마다 출처 필수(종류 일치: 거절·무효과 ← 관찰, 전송 전 실패 ← Attempt, 미전송 ← 결제창 만료). Resolution·`RequestedPaymentExpiry`가 출처를 만든다 | `PaymentSpec`·`AuthorizationResolutionSpec`·`RequestedPaymentExpirySpec` 출처 행. RED-C 55 오류. **변이 RED** m6(출처 없음 허용) → 1 실패 |
| n-4 | `OrderPaymentControl.reconstitute`의 해제 기록 기본값 제거 | 컴파일(호출부 명시) |
| `PaymentAuthorization.reconstitute`(CC nit 4) | FAILED + RECON 거래 허용, 상태·거래·근거·관찰·Attempt 불변식 | `PaymentAuthorizationReconstitutionSpec` 8 왕복 + 6 거부. RED-C(미존재) 뒤 구현 — 첫 실행 GREEN(사전 RED-A 없음, 편차) |
| `AuthorizationStart.preflight`(AU-05) | 읽기 전용 사전 검사 | `AuthorizationStartSpec` 3행. RED-C |
| F-3(AU-27) | `ProviderRequestRef.toString` 마스킹 | **RED-A** 1 실패 |
| AU-18 | `ReportSuccessAfterTerminationService`·UseCase 삭제. 시험 상태 준비는 test 전용 `IncidentReporter`(control → payment 잠금, 같은 순서) | 기존 5개 spec 기대값 불변 |
| F-2 재조사 | 2026-10-18 전체 실행에서 L-3로 실패한 기존 시험 **24행 / 10파일**(`PaymentStoreAdapterSpec`·`ApiFailureSpec`·`CheckoutErrorPageLeakSpec`·`CheckoutWebSpec`·`CreatePaymentApiSpec`·`CardAuthenticationFlowSpec`·`CheckoutExchangeSpec`·`CheckoutTokenIssuanceSpec`·`CreatePaymentRollbackSpec`·`CreatePaymentServiceSpec`) — 처리는 아래 표 | 재실행 GREEN |

**바뀐 시험 기대값·준비(전/후 — Reviewer 확인 대상, AC 8 "완화 아님")**

| 시험 | 전 | 후 | 기대값 변화 |
|---|---|---|---|
| `PaymentSpec` "REQUESTED … evidence recorded as is" | REQUESTED `fail(TIMEOUT)` 허용·기록 | REQUESTED 근거 종료 거부 표 + legacy 행 복원(불충분, 안전 종료 아님) | **정책 변경(AC 8 L-3)** — 결정된 AC 반영 |
| `PaymentFixture.failedWithOnlyTimeout`·`expiredWithOnlyInquiryNotFound`·교차 조합 경로 | REQUESTED에서 전이 | 같은 ID의 legacy 저장 행(`Payment.reconstitute`) | 없음(판정표 기대값 동일) |
| `CreatePaymentServiceSpec`(TIMEOUT 행)·`CreatePaymentApiSpec`(TIMEOUT·NOT_FOUND·LEASE 4행, 이름에 "legacy row")·`ApiFailureSpec`(TIMEOUT 행) | domain `fail(TIMEOUT)` 등 | root로 legacy 행 준비(`DbAccess.terminateAsLegacy`) | 없음(차단 409·TERMINATION_EVIDENCE_INSUFFICIENT 그대로) |
| 위 파일과 Checkout 5개·`CreatePaymentRollbackSpec`·`PaymentStoreAdapterSpec`의 DECLINE·PRE_TRANSMISSION 준비 | REQUESTED에서 바로 `fail` | `startAuthorization()` 뒤 `fail`(승인 시작 경유) | 없음 |
| `AuthorizationResolutionSpec` "REAL then NO_EFFECT" | (이 작업에서 rev 0.2 기준으로 새로 쓴 행) 같은 terms → 새 hold | H-1: X(REAL로 설명) → NoChange, Y(NO_EFFECT) → 새 hold(occurrence 3) | rev 0.3 H-1 반영(기존 커밋 기대값 아님) |
| `PaymentSchemaSpec` hold 원시 INSERT·멱등 "OtherOperation" 행 | occurrence 열 없음 / `OtherOperation` SUCCESS 행 | `occurrence = 1` 명시 / `ConfirmPayment` PENDING 행 | 없음(검사 성질 — 같은 (주문, Payment, 사유) 1행, operation이 범위에 포함 — 동일). V11 occurrence 기본값 제거·operation CHECK 때문 |
| `DatabaseAccountSeparationSpec`·`OldVolumeStartSpec`·`CheckoutWindowCheckMigrationStrategySpec`·`PaymentApiContextSmokeSpec` | 표·버전 목록 V8_1까지 | 새 표 7개(권한·1142·DELETE 거부)·V9~V11 추가 | 추가만(완화 없음) |
| `ReconstitutionSpec`·`IncidentHoldReleaseSpec` reconstitute 호출 | 해제 기록 생략(기본값) | `emptyList()` 명시 | 없음 |

### 16.3 S2 — PAYMENT V9~V11·JPA Port(2026-10-18, 구현자 보고)

- 파일: `V9__authorization.sql`(새 표 7: payment_authorization·authorization_attempt·institution_observation·payment_transaction·daily_limit_usage·limit_reservation·incident_hold_release),
  `runtime-privilege/V10__authorization_runtime_privileges.sql`, `V11__authorization_existing_tables.sql`(incident_hold occurrence·여는 관찰 키·UNIQUE 교체·reason CHECK, 종료 근거 출처 열,
  멱등 operation CHECK·ConfirmPayment 결과 CHECK·`result_authorization_id`·`result_detail`). V12 없음. 회복 일정 표는 S7로 이동(쓰기 경로가 생길 때).
- Port: `PaymentAuthorizationRepository`→`JpaPaymentAuthorizationRepository`, `DailyLimitUsageRepository`→`JpaDailyLimitUsageRepository`, `AuthenticationSessionRepository.findIdByAuthenticationRef`.
  `OrderPaymentControlRepository`는 hold occurrence·여는 관찰 저장, 해제 기록 복원(`incident_hold_release`), 저장 안 된 해제 거부(n-4). `PaymentRepository`는 종료 근거 출처 저장·복원.
  `StoreConstraint` 10개 추가. 시험 장애 주입 지점 5개(AUTHORIZATION_*·LIMIT_USAGE_*).
- 구현 결정: 거래 `recorded_by`는 승인 상태로 정한다(AUTHORIZED → RESOLUTION, FAILED + 거래 → RECONCILIATION). hold 여는 관찰은 FK 없는 자연 키 열. Attempt `seq`·점유 `seq`로 순서 보존.
- 시험: `AuthorizationPersistenceSpec` 12건(왕복·UNIQUE·append-only·키 상충 variant·출처·포기·사용량 동시 생성·version·해제 복원·저장 안 된 해제 거부·DB CHECK/UNIQUE 우회 거부),
  `CheckoutWindowCheckMigrationStrategySpec` V8_1 볼륨 → V11(m-5) 1건 추가. **RED-C** 121 오류(Port 없음). **변이 RED** n4(해제 검사 제거) → 1 실패.
- **편차**: migration SQL(V9~V11)을 스키마·권한 시험보다 먼저 썼다(DDL은 test-first가 아님) — 기대값은 명세 §7.1·§7.2 표에서 독립 기술했고 우회 INSERT 시험(1062·3819)과 권한 1142 시험으로 확인했다.

## 17. rev 0.2 명세 review(SPEC_PASS_WITH_FIXES) 대응표(rev 0.3)

| 지적 | 반영 | 단계 |
|---|---|---|
| H-1 설명 집합 | §4.11-2·F-4·§7.1(hold 여는 관찰 열)·domain `IncidentHold.sourceObservation` 구현 | S1(domain)·S2(DDL) 완료 |
| H-2 AU-26 | **D-AUTH1-004(U-1 사용자 답변, 권장안)로 AU-26 미채택**: 게이트는 인증 증거 TTL을 보지 않는다. 만료 증거는 VAN 재검증이 DECLINED `AUTHENTICATION_INVALID`(원장 기록 뒤 응답) → FAILED `INSTITUTION_CONFIRMED_DECLINE`로 확정 거절한다(§4.4·§5.3-3·§13.3) | 구현할 게이트 검사 없음. VAN 재검증은 S3 |
| H-3 PENDING 종결 | §5.2·§5.4·§5.7.2·§5.7.3·C30 | S3·S4 |
| m-1 `at` 순환 | §4.3-7·AU-32 | S5 |
| m-2 늦은 NotSent·재시도 deadline | AU-25(domain 완료)·AU-34·C28 | S1 완료·S6 |
| m-3 TXd commit 불명확 | AU-28·C29 | S6 |
| m-4 기존 Simulator 볼륨 | §5.5 수렴 절차 + 기동 거부 검사 | S3 |
| m-5 V8_1 볼륨 | §7.4·`CheckoutWindowCheckMigrationStrategySpec` | S2 완료 |
| m-6 근거 출처 domain 기록 | `TerminationEvidenceSource`·Resolution·`RequestedPaymentExpiry`(CreatePayment·주기 작업·TX-I는 `RequestedPaymentClosing` 경유로 같은 출처) | S1·S2 완료 |
| m-7 VAN 해시·거절 원장·보존기간·문장 정정 | §5.3-1·5, §5.4 | S3 |
| m-8 AC 24개 | §2 행 21~24, 입력 표 | 완료 |
| m-9 head(S) | AU-04 대체(`lockHeadForShare`) | S5 |
| n-1 | §5.7.2 RESPONSE PENDING → `applyObservation` | S6 |
| n-2 | §7.5 행 잠금 + `@Version` 설명 정정 | 완료 |
| n-3 | 비잠금 MID 소유 확인을 control 잠금 전에 | S5 |
| n-4 | §5.3-5(MAC 불일치 원장 기록 없음·연결 종료), 중복 응답 기대값은 S3에서 결정적으로 고정 | S3 |
| n-5 | 결함은 저장 없음(§4.9 행: 형식·인증·404·503 저장 안 함), §4.8 저장 code ↔ §4.9 API code: `INCIDENT_HOLD`→`ORDER_ON_HOLD`, `AUTHORIZED_NEW_ORDER_REQUIRED`→`ORDER_ALREADY_AUTHORIZED`, 나머지 같은 이름 | 완료(문서) |
| n-6 | 회복 일정 insert-if-absent, ABANDONED 재포기 NoChange(domain 완료) | S1·S7 |
| n-7 | AU-31 | S5 |
| n-8 | C27 주입 구동, AU-20 확인 문구 삭제 | 완료 |
| Coordinator 판정(rev 0.3) U-2 | AU-17 유지(사건 이벤트는 새 hold append 때만) | S1 완료(F-1)·S6 Outbox |
| Coordinator 판정(rev 0.3) AU-22 | 채택: 대사 이관 뒤에도 조회 보존기간까지 느린 조회를 계속(반영은 관찰 키 멱등) | S7 |
| Coordinator 판정(rev 0.3) 게이트 일시 Unavailable | AU-33: Admission Unavailable(Snapshot lease 만료 등)은 `preparedDeadline`(Demo)까지 TXd 재시도, 그래도 Unavailable이면 GATE_REFUSED → FAILED(PRE_TRANSMISSION). Refused(차단·카드사 정지)는 즉시 GATE_REFUSED | S6 |
| n-9 | AU-07의 프로파일 B 절충: 전송자가 deadline으로 송신을 포기하면 프로파일 B에서는 NOT_FOUND만 남아 대사 이관까지 UNKNOWN(무효과 확정 수단 없음) — 금융 안전(이중 승인 없음)을 위한 가용성 손실로 기록 | 문서 |

### 16.4 S2 checkpoint 실행 기록(구현자 실행 — Verifier 판정 아님)

- 환경: WSL2 Ubuntu, Docker 29.8.0(Testcontainers MySQL 8.4.11), 기준 `481a9fa` + 미커밋 변경. VERIFY-GATE는 실행하지 않음(Coordinator 지시).
- `./gradlew :apps:payment-api:check` **exit 0**(2026-10-18T01:23:27Z → 01:29:47Z): JUnit XML 104파일 **1,135건, 실패 0·오류 0·skip 0**, verifyNoInstancio·verifyRuntimeHasNoTestTools(runtime 119 모듈) 통과.
  첫 실행(01:16Z)은 `PaymentApiContextSmokeSpec` 적용 버전 목록(V8_1까지 고정) 1건 실패 → 목록에 V9~V11 추가(추가만, 위 기대값 표와 같은 성격) 뒤 재실행 통과.
- payment-api 시험 수: 이 작업 전 마지막 보고서 1,069건 → S1 뒤 1,122 → S2 뒤 **1,135**(+66). 다른 모듈은 변경 없음(simulator 30·master 320·batch 26·admin 29, 이번 회차 미실행 — 기존 보고서).
- 변이 RED(`/tmp/auth001_mut1.sh`, 변이 → 대상 spec → 원복·cmp 동일 확인): n4 해제 검사 제거 → 12건 중 1 실패, h1 설명 집합 확대 → 49건 중 4 실패, m6 출처 생략 허용 → 66건 중 1 실패, l3 REQUESTED 제한 제거 → 66건 중 13 실패.
- `harness/commands.json` 하한은 바꾸지 않았다(S8에서 실측으로).

### 16.5 중간 검토 #1(S1·S2 CHANGES_REQUIRED) 대응(2026-10-18, 구현자 보고 — Reviewer 재확인 대상)

| 지적 | 변경 | 시험·RED |
|---|---|---|
| H-1(미완 — 상충 variant) | domain `ObservationRef(key, variant)`(variant 0 = 첫 관찰, n ≥ 1 = 같은 키 n번째 상충 증거 — 영속 variant와 같은 번호), `PaymentAuthorization.observationRefs`·`refOf`. hold 출처(`IncidentHold.sourceObservation`)·hold 개설 연산 3개·`realApprovalReleasedHoldSources`가 `ObservationRef`. `explainedByRealRelease`는 그 관찰 **한 건**(키 + variant)만 비교. V11 `incident_hold.source_variant`(출처 열 넷 함께 NULL/NOT NULL, ≥ 0), Mapper·저장. 관찰 복원은 variant 순서로 정렬 | `AuthorizationResolutionSpec` 검토자 재현 4단계(K v0 A → hold, K v1 B 흡수, REAL 해제, B를 새 키로 재보고 → 새 hold occurrence 2). **RED-A**: NoChange(검토자 보고와 같은 실패). **변이 RED** variant 비교 제거 → 50건 중 1 실패. `AuthorizationPersistenceSpec`: 상충 variant가 연 hold의 `source_variant = 1` 저장·복원 |
| m-1 L-3 우회 | `Payment.authorizationStarted`(`startAuthorization`이 true, 저장 열 없음). `addTerminationEvidence`: 시작하지 않은 Payment는 `NOT_TRANSMITTED_AND_SESSION_DISCARDED`(결제창 만료 출처)만. `Payment.reconstitute(…, authorizationStarted)` 불변식 3개(REQUESTED ⇒ false, AUTHORIZING/UNKNOWN/AUTHORIZED ⇒ true, 관찰·포기 Attempt 출처 근거 ⇒ true). 영속 도출: 상태 ∨ payment_authorization 행 존재 ∨ 관찰·포기 출처 근거(Payment 행 잠금 뒤 READ COMMITTED 읽기) | `PaymentSpec` 우회 표(근거 5종 × 종료 2경로). **RED-A** 1 실패(예외 없음). **변이 RED** 가드 제거 → 95건 중 1 실패, 복원 불변식 제거 → 1 실패. `ReconstitutionSpec` 모순 플래그 거부 |
| m-2 기관 영업일 | `ApprovedTerms.institutionBusinessDate: LocalDate?`(기본값 없음) — 관찰·거래 terms에 포함(같은 승인 비교에 포함). V9 `institution_observation`·`payment_transaction`에 `institution_business_date DATE NULL`(승인 조건 아닌 관찰은 NULL CHECK), Mapper. VAN 계약은 `institutionBusinessDate`를 싣는다(§5.2, S3). **시간대·출처 의미·정산 거래일 사용은 미결 Q-CALENDAR로 넘긴다** — 달력 규칙을 만들지 않음 | `AuthorizationResolutionSpec` 같은 승인 표에 영업일 다름·없음 2행, `AuthorizationPersistenceSpec` 값·NULL 왕복. **RED-C** 6 오류(속성 없음) |
| n-1 | `PaymentFixture.advance`는 전이로 만들 수 없는 조합을 legacy로 바꾸지 않고 `IllegalArgumentException`. `reachableTermination`·`OrderPaymentControlFixture.withCurrentLegacyPayment` 추가, 판정표가 legacy를 명시 | 판정표 기대값 불변 |
| n-2 | `explainedByRealRelease` KDoc을 코드와 맞춤(거래 terms 또는 REAL·DOUBLE 해제 hold를 연 관찰 한 건, 모든 hold 해제 여부는 보지 않음) | — |
| n-3 | V11 reason CHECK: `DROP CHECK ck_incident_hold_reason, ADD CONSTRAINT ck_incident_hold_reason_v2 …`를 한 ALTER로(CHECK 없는 구간 없음) | 스키마 시험 통과 |
| n-4 | `PaymentAuthorization.reconstitute` 거부 규칙 변이 7개(AUTHORIZED 거래 필수·거래 상태·FAILED 근거·거래 출처 관찰·시도 없음·키 상충 첫 관찰·상태별 시도) | 변이 6개는 첫 실행에서 각 1 실패. **시도 없음 변이는 처음에 생존**(AUTHORIZED 시나리오가 다른 규칙으로도 거부됨) → 관찰 없는 OPEN 시나리오로 시험 강화 뒤 1 실패 |
| n-5 | §17 H-2 = D-AUTH1-004, Coordinator 판정(U-2·AU-22·게이트 일시 Unavailable) 행 추가 | 문서 |
| n-6 | `IncidentHold` 생성자 private(기본값 없음), 생성은 `IncidentHold.opened`(internal, control의 hold 열기만)·`IncidentHold.reconstitute`(Mapper·시험 기대값) | 컴파일 |

**바뀐 시험 기대값·준비(전/후 — 검토 #1)**

| 시험 | 전 | 후 | 기대값 변화 |
|---|---|---|---|
| `AuthorizationPersistenceSpec` 키 상충 행 | hold 출처 = `ObservationKey(…, "dec-9")` | `ObservationRef(ObservationKey(…, "dec-9"), 1)` + `source_variant = 1` 행 확인 | **정정**: 그 hold를 연 것은 같은 키의 상충 증거(variant 1)다 — 키만 기대한 것이 H-1 결함을 가렸다 |
| `AuthorizationResolutionSpec` 기존 H-1 행 2곳 | `ObservationKey(…)` | `ObservationRef(ObservationKey(…), 0)` | 없음(같은 관찰, 타입만) |
| `PaymentSpec` "expired payment without evidence — evidence confirmed later" | 시작 안 한 EXPIRED에 INQUIRY_NOT_FOUND → NO_EFFECT 덧붙임 → 안전 | 시작 안 한 FAILED + 미전송 근거 → 불안전, 시작 안 한 EXPIRED + 미전송 근거 → 안전 | 입력 경로 교체(옛 입력이 m-1 우회 그 자체). 검사 성질(append-only, 충분 근거만 안전) 유지 |
| `CreatePaymentApiSpec` "without sufficient evidence … replays for 30 days" | REQUESTED `fail(null)` → 나중에 DECLINE 덧붙임 | REQUESTED `expire(null)` → 나중에 미전송·세션 폐기 근거 | 없음(409 `ORDER_RESULT_UNCONFIRMED`·같은 키 재생·새 키 201 그대로). 정상 경로로 준비 |
| `PaymentAuthorizationReconstitutionSpec` "no attempts …" | AUTHORIZED 시나리오로 시도 없음 거부 | 관찰 없는 OPEN 시나리오 추가(기존 단언 유지) | 강화만 |
| `IncidentHold(...)` 기대값 생성(7 spec) | 생성자(기본값 occurrence 1·출처 null) | `IncidentHold.reconstitute(…, occurrence, null)` 명시 | 없음(같음 = paymentId·reason·occurrence) |
| `ReconstitutionSpec`·`PaymentFixture` 복원 호출 | 인자 없음 | `authorizationStarted` 명시(legacy 행 = false) | 없음 |

- 검토 #1 반영 뒤 `:apps:payment-api:test` 전체 1,141건 실패 0(2026-10-18, 구현자 실행 — checkpoint 수치는 §16.8).

### 16.6 S3 — 가상 VAN 모듈(`apps/institution-simulator` `van`)(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| 계약 golden | `harness/tests/test_van_contract_golden.py` → `van-contract-sim-van-1.golden.json`(두 앱 시험 자원, 바이트 동일): 메시지 16종, payload hash 3개, 프레임 3개(시험 전용 HMAC 키), 계약 위반 응답 5개 | harness 4건. **RED**: golden 파일 없음(error) 뒤 `--write` |
| domain(순수) | `VanAuthRequest.payloadHash`(업무 필드 canonical — messageId·requestedAt 없음, m-7), `VanAuthorization`(§5.3 판정 순서), `VanInquiry`(§5.4), `VanLedgerEntry`(DECISION·CLOSURE), `VanPendingDecision`(H-3), `VanBehaviour`(카드 → 시나리오), `VanProfile`(A·B), `VanUlid`(요청 ID ULID 시각) | `VanAuthorizationSpec` 20·`VanInquirySpec` 9. **RED-C**(모듈 전체 367 오류) |
| 프레임·메시지 | `VanFrameCodec`(누적 read, 1..16 KiB, 버전, 상수 시간 MAC), `VanMessages`(첫 필드 `type`, 닫힌 필드 집합·null·타입·식별자 형식·중복 키·끝 뒤 토큰 → FRAMING, 응답은 golden 필드 순서 compact JSON) | `VanFrameCodecSpec` 11(golden 프레임 바이트 동일·1바이트씩 read·한 read에 프레임 둘·길이/버전/잘림 FRAMING·다른 키/모르는 키 INTEGRITY·Kotlin hash = Python golden hash·응답 바이트 동일) |
| 원장·권한(AU-20·m-4) | V2 `van_ledger_entry`(UNIQUE namespace, environment CHECK TEST, 결정/종결 모양 CHECK)·`van_pending_decision`·**`van_evidence_use`**(PK evidence_ref — 다른 요청 ID 동시 재사용 직렬화, S3 구현 결정)·`van_inquiry_log`, V3 증거 참조 색인, V4(`db/runtime-privilege/simulator`) 표 단위 권한. 초기화 스크립트를 §9.2식(role·잠긴 grantor·procedure·DB 단위 REVOKE)으로 재작성(재실행 안전). `VanLedgerPrivilegeGuard`: runtime 연결에서 `UPDATE/DELETE … WHERE 1 = 0`을 실제 실행해 1142가 아니면 **기동 거부** | `SimulatorDatabaseAccountSpec` 3(SHOW GRANTS 전체·1142 8문장·옛 볼륨 기동 거부 → 스크립트 재실행 2회 수렴 → 기동) |
| 서버·시나리오 | `VanTcpServer`(ServerSocket + virtual thread, 별도 포트·HTTP 포트와 같으면 거부, 연결당 다중 요청), `VanAuthorizationService`·`VanInquiryService`(한 로컬 TX, UNIQUE 경합 진 쪽은 새 TX 재판정 — 최대 3회), 인증 모듈 provided `AuthenticationEvidenceLookup`(AU-13) | `VanServerSpec` 25(실 TCP·실 MySQL): 승인·재전송 같은 결정(m-7)·REQUEST_CONFLICT·증거 다른 ID 재사용 거절·만료 증거 확정 거절(D-AUTH1-004)·체크 할부/프로파일 밖 개월 PLAN_NOT_SUPPORTED 기록·MAC 불일치/LIVE/스키마 위반/모르는 계정 = 기록 없이 종료(n-4)·DROP-AFTER-APPROVE·LATE·FRAGMENTED·DUPLICATE(결정적 2회, n-4)·BAD-MAC·재전송 정상 응답·INQUIRY-UNAVAILABLE-ONCE·DROP-BEFORE-RECORD → 종결 → REJECTED_CLOSED·프로파일 B 종결 없음·closableAfter 전 종결 없음·보존기간 경과 UNAVAILABLE·PENDING(H-3)·**C30** 경합 2종(AUTH vs 종결 6회, 기한 PENDING 확정 동시 조회 2) — 각 원장 1행 |
| 재시작·구조 | `SimulatorRestartDurabilitySpec`에 VAN 결정 재시작 뒤 FOUND 동일 1건, `SimulatorArchitectureSpec` 2건(van → authentication은 provided만, authentication → van 없음, 원장 Store 연산 find·insert만·Entity `@Immutable`), harness `test_simulator_isolation` 1건(Simulator 소스가 PayCore 앱 패키지를 import하지 않음 — **RED-A**: van 소스 없음) | ArchitectureSpec 2건은 test-after(가드 — RED 없음, 편차) |

- **변이 RED**(`mutrun.py` — 변이 하나씩 → 대상 spec → 원복·sha256 동일 확인): H-3 PENDING을 closeIfAbsent가 종결 → 4 실패, m-7 거절 원장 미기록 → 2 실패, n-4 MAC 미검사 → 3 실패, D-AUTH1-004 `validUntil` 미검사 → 2 실패, m-4 기동 검사 탐침 없음 → 1 실패(대상 68건).
- **S3 구현 결정(가역, 기록)**: (1) 모든 메시지에 첫 필드 `type`, AUTH_REQUEST에 `merchantRef`(MID) 추가 — §5.3-3 "바인딩 echo(…merchantRef…)"를 VAN이 대조하려면 필요하다. (2) VAN은 받은 적 없는 요청 ID의 연령을 요청 ID의 **ULID 시각**으로 판정한다(`closableAfter` 전 종결 거부, 보존기간 경과 UNAVAILABLE — 원장 행이 있어도). 형식이 아니면 종결하지 않는다. (3) 증거 재사용 차단을 `van_evidence_use` PK로 직렬화(같은 증거로 다른 요청 ID 동시 승인 방지). (4) 기관 거래일·영업일 = VAN 시계의 Asia/Seoul 날짜(Demo — 실제 영업일 규칙 아님, Q-CALENDAR). (5) 전송 시나리오는 새 결정의 첫 응답에만 적용, 재전송은 기록된 결정을 정상 응답. (6) PENDING 예약 생성과 무효과 종결이 같은 순간에 오면 둘 다 기록될 수 있으나 기관 사실은 원장 한 행이다 — 종결 표지가 이기면 예약은 확정되지 못하고(DECISION INSERT 1062 → 재판정) 조회는 NO_EFFECT_CONFIRMED, 승인은 생기지 않는다(무효과가 참). 예약 확정 vs 종결은 같은 UNIQUE(namespace)에서 경합(H-3).
- **바뀐 기대값(전/후)**: `SimulatorBootSpec` "runtime account has DML only" — 전: `GRANT SELECT, INSERT, UPDATE, DELETE ON paycore_simulator.*` 포함 / 후: DB 단위 권한 없음 + `van_ledger_entry` SELECT·INSERT 표 권한(AN-19 → AU-20, Coordinator 채택 결정 반영 — 강화). 그 밖 기존 Simulator 시험 기대값 불변.
- 데이터 경로: `harness/commands.json` HARNESS-SELFTEST 하한 81 → **86**(harness `test_module_registry`가 하한 = 실측 harness 시험 수를 요구 — 올리기만). GRADLE-CHECK 하한은 S8.

### 16.7 S4 — PG TCP Adapter·Route 등록부·격리(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| Port(application.required) | `AuthorizationPort`(`AuthorizationCall` — Attempt namespace·routeRef·MID·금액·Plan·증거 참조·connect deadline → Approved/Declined/Pending/NotSentConfirmed/OutcomeUnknown(cause)), `AuthorizationInquiryPort`(→ Found/NotFound/NoEffectConfirmed/Unavailable(reason)), `InstitutionRouteResolver`(→ `InstitutionRouteProfile`·`RouteCapabilities`) | **RED-C** 145 오류 |
| Route 등록부·격리(§5.6 겹 2·§5.8) | `ConfiguredInstitutionRouteRegistry`(`paycore.authorization`, 운영 기본값 없음): SIMULATOR·TEST만(REAL·모르는 type·LIVE SIMULATOR·backend 없는 환경 → 기동 거부), host는 loopback 또는 `allowed-hosts`, `sim-van-1`만, HMAC 키 ≥ 32바이트, read ≤ 전체 deadline, Capability Demo(KRW·CREDIT/CHECK·할부 신용 2/3/6 — 결제창 Plan Demo와 같은 값). 모르는 routeRef = null | `ConfiguredInstitutionRouteRegistrySpec` 4 |
| Adapter(§5.7) | `SimulatorVanAuthorizationAdapter`(요청당 연결 1, connect/read/전체 deadline 분리, connect 단계만 예산 안 재연결·재연결마다 deadline 재확인 → NotSentConfirmed, 쓰기 시작 뒤 모든 실패 → OutcomeUnknown(TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY)), `SimVanFrames`·`SimVanMessages`(Simulator 코드와 공유하지 않음 — golden으로만), `VanSocketConnector`. **H-3 PG 매핑**: AUTH PENDING → Pending(sourceResultId = pendingId), INQUIRY PENDING → Found(PENDING 관찰, sourceResultId = pendingId). sourceResultId 계약 decisionId·closureId·inquiryId(§5.7.3). 프로파일 B Route에는 `closeIfAbsent`를 보내지 않고 B의 NO_EFFECT_CONFIRMED는 INTEGRITY | `SimulatorVanAuthorizationAdapterSpec` 19(시험 전용 TCP `StubVan` — 실제 소켓): 요청 프레임 = golden 바이트, 승인 terms·evidenceHash·observedAt, 거절·PENDING(H-3), 할부 전송, 분할 응답·중복 응답 프레임, REQUEST_CONFLICT/REJECTED_CLOSED = CORRELATION, echo 불일치 = CORRELATION, MAC·모르는 키 = INTEGRITY, golden 위반 응답 5종 = FRAMING, 끊김 = CONNECTION_LOST, 무응답 = TIMEOUT(read timeout 안), 거부 connect 재시도 3회 → NotSent, deadline 경과 = connect 0회, 모르는 Route = 연결 0, 조회 FOUND·PENDING(H-3)·NOT_FOUND(inquiryId)·NO_EFFECT(A)/INTEGRITY(B)·UNAVAILABLE·연결 실패·timeout |
| 격리 겹 1 | ArchUnit `authorizationPortsImplementedOnlyBySimulatorAdapter` + 위반 fixture `archfixture.institution.RealVanAuthorizationAdapter`, 기존 `institutionAdapterDoesNotMutateDomain` 대상에 새 Adapter 포함 | `ArchitectureSpec` 1건 추가 |
| 설정 | payment-api 시험 설정 `paycore.authorization`(Demo·golden 시험 키), 운영 `application.yml`은 필요한 키 목록 주석만(기본값 없음) | — |

- **변이 RED**: H-3 조회 PENDING을 inquiryId로 키잉 → 1 실패, REQUEST_CONFLICT를 NotSent로 → 1 실패, timeout을 CONNECTION_LOST로 → 2 실패, connect deadline 무시 → 1 실패, 프로파일 B에 종결 요청 전송 → 1 실패, 상관관계 검사 제거 → 1 실패(대상 19건).
- **S6·S5로 넘긴 것**: AU-34 기동 검사(`connectTimeout × connectRetryBudget < dispatchLease − safetyMargin` — dispatch lease 설정이 S6), AU-31 결제창 Plan 정책 ↔ Route Capability 일치 기동 검사(S5, n-7), Adapter 호출 전 deadline 확인·TX 밖 호출 증명(`VanCallOutsideTransactionSpec`, S6).
- 바뀐 기존 기대값: 없음(새 시험만).

### 16.8 S4 checkpoint 실행 기록(구현자 실행 — Verifier 판정 아님)

- 환경: WSL2 Ubuntu, Docker 29.8.0(Testcontainers MySQL 8.4.11), 기준 `481a9fa` + 미커밋 변경. VERIFY-GATE·commit 없음.
- `./gradlew :apps:payment-api:check` **exit 0**(2026-10-18T03:29:33Z → 03:35:23Z): JUnit XML 106파일 **1,165건, 실패 0·오류 0·skip 0**(verifyNoInstancio·verifyRuntimeHasNoTestTools 포함 check 통과).
- `./gradlew :apps:institution-simulator:check` **exit 0**(03:37:29Z → 03:39:06Z): 10파일 **101건, 실패 0**(이전 30).
- `python3 -B -m unittest discover -s harness/tests`: **86건 OK**(이전 81).
- 시험 수: payment-api 1,135(S2) → 1,141(검토 #1) → 1,165(S4). Simulator 30 → 101. harness 81 → 86. 새 의존성 없음(JDK Socket·ServerSocket·`javax.crypto.Mac`, 기존 Jackson) — GRADLE-DEPS 변화 없음 예상(S8에서 확인).

### 16.9 S5 — confirm API·파서·멱등(c1)·TX1(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| provided·required | `ConfirmPaymentCommand`·`ConfirmOutcome`·`ConfirmRejection`·`ConfirmPaymentFailure`, `IdempotentOperation.CONFIRM_PAYMENT`·`StoredRequestResult.ConfirmAccepted/ConfirmRejected`(detail `^[A-Z_]{1,48}$`), `AuthorizationIdentifierGenerator`(pau_/pat_/ar_/aq_/ptx_), `EntitlementHeadLockPort`(entitlement provided `EntitlementHeadLock` — m-9 `lockHeadForShare`) | **RED-C 180 오류** |
| TX1(§4.3) | `ConfirmStartService`: head(S) → 생성 기록 소유 비잠금 확인(n-3) → control·payment X → 인라인 만료 → 재사용·충돌 → 재진술 → hold·승인됨 → 세션 → **m-1 `at`을 사용량 잠금 전에** 정하고 날짜가 바뀌면 재시작 → Admission(같은 at) → Route Capability(통화·cardType·할부·할부 최소금액) → `AuthorizationStart` → 저장 | `ConfirmStartSpec` 19, `ConfirmConcurrencySpec` 5(C8·C18·C19·C21·C22) |
| AU-31(n-7, S4 이월) | `AuthorizationProfileConsistency` 기동 검사: 결제창 Plan Demo 정책(할부 개월·최소금액) ↔ 모든 Route Capability | `AuthorizationProfileConsistencySpec` |
| API 파서 | `ConfirmPaymentRequestParser`(paymentId `^[!-~]{1,64}$`, `aref_`, 재진술 필드) | 파서·institution 단위 43 |
| m-1·rollback | 업무일 경계 재시작(사용량 잠금 대기 중 Asia/Seoul 자정 경과 주입), TX1 rollback(AUTHORIZATION_INSERT·LIMIT_USAGE_UPDATE 장애 주입) | ConfirmStartSpec 안 |

- **변이 RED**(19건 대상): m-1 업무일 재확인 제거 → 1 실패, 재진술 대조 제거 → 1, 통화 Capability 대조 제거 → 1, 한도 정책 버전 저장 누락 → 1, 인라인 만료 호출 제거 → 2.
  "인라인 만료 조건만 생략" 변이는 생존(동등 변이 — 같은 TX의 후속 판정이 같은 결과), 더 강한 "호출 안 함" 변이로 대체 기록.
- 바뀐 기존 기대값: 없음(S5 시점). `VanRouteFixture.capabilities`·시험 yml에 `installment-minimum-amount: 50000` 추가(새 필드 — 기존 기대값 변경 아님).

### 16.10 중간 검토 #2(S3·S4 PASS_WITH_FIXES) 대응(2026-10-18, 구현자 보고 — Reviewer 재확인 대상)

| 지적 | 대응 | 시험·RED |
|---|---|---|
| m-A 전체 deadline을 read 중에도 | Adapter 교환을 단조 시계 deadline으로: read 전마다 soTimeout 재설정 + watchdog(가상 스레드가 deadline에 소켓을 닫음) → TIMEOUT. `SimVanFrames.read`에 `beforeRead` | slow-drip StubVan 시험. **RED-A** 2건(m-A·m-B). read에 대해서는 두 장치가 중복 방어라 하나씩 제거한 변이는 각각 생존(rev 0.6 정정 문구: "read에서는 중복, 쓰기 막힘은 현재 프레임 크기로 시험 불가" — 동등 변이가 아님). RED-A는 둘 다 없는 상태로 기록 |
| m-B Route 환경 ≠ 요청 환경 | AUTH → 연결 없이 NotSentConfirmed, 조회 → Unavailable(UNKNOWN_ROUTE) | LIVE 요청·TEST Route → 바이트 0. 변이(환경 대조 제거) → 1 실패 |
| m-C PENDING-then-APPROVE vs closeIfAbsent 반복 경합 | Simulator 시험: 원장 ≤ 1행, 종결이 이기면 기한 뒤 조회 NO_EFFECT_CONFIRMED·재전송 AUTH REJECTED_CLOSED·DECISION 0행. domain: NO_EFFECT 실패 뒤 PENDING은 NoChange | VanServerSpec(기존 구현으로 통과 — 회귀 방지), AuthorizationResolutionSpec 1 |
| n-1 guard 대상 | `van_pending_decision` DELETE, `lookup_log` UPDATE/DELETE 탐침 추가 | SimulatorDatabaseAccountSpec — RED-A 1 |
| n-2 usedBy·재시도 소진·serve | usedBy를 전체 namespace로 비교(`VanEvidenceUseStore.namespaceOf`), DuplicateVanRecord 재시도 소진 → 기록 없이 연결 종료, `VanTcpServer.serve`가 어떤 예외로도 죽지 않음 | VanServerSpec — RED-A 1 |
| n-3 보존기간 지난 ULID AUTH | §5.4 Demo 규칙으로 거부(기록 없음) | VanAuthorizationSpec·VanServerSpec — RED-A 1 |
| n-4 | VAN 서버 idle read timeout(`PAYCORE_SIMULATOR_VAN_IDLE_TIMEOUT`, 시험 PT2S, `.env.example` PT30S), Adapter 쓰기도 deadline watchdog 안(쓰기 시작 뒤 실패 = OutcomeUnknown), guard가 autoCommit 복원 | VanServerSpec idle — RED-A 1 |
| n-5 | 쓰기 시작 뒤 마지막 `RuntimeException` catch → OutcomeUnknown | Adapter spec |
| n-6 | principleEvidence는 Coordinator 소유 — 구현자 변경 없음 | — |
| m-D | D-AUTH1-005로 구현 — §16.14 | IncidentHoldUnexplainedApprovalSpec |

- 결과: Simulator 101 → **107**(RED-A 4 실패 → 통과), institution Adapter spec 42/42.
- 바뀐 기존 기대값: `VanInquirySpec` 보존기간 시험의 **준비 단계** — 이전: 보존기간보다 오래된 ULID로 AUTH를 기록한 뒤 조회 → UNAVAILABLE. 이후: n-3 규칙으로 그런 AUTH는 기록되지 않으므로
  결정을 `now − retention` 경계에 기록하고 시계를 넘겨 조회 → UNAVAILABLE. **기대값(UNAVAILABLE)은 그대로**, 준비 방법만 규칙에 맞춤.

### 16.11 S6 — TXd·TX 밖 기관 호출·TX2·Outbox(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| 조정자 | `ConfirmPaymentService`(TX 없음): TX1 → 새 시작이면 `AuthorizationDispatchService` → `AuthorizationViewReader`(jOOQ) 현재 상태. 202 = IN_PROGRESS/UNKNOWN(새 키·새 Payment 재시도 금지 안내) | **RED-C 78 오류** |
| TXd(§4.4) | head(S) → control → payment → authorization → 세션 → (거절 때만 사용량) → 회복 일정. Admission 재평가(A0~A2), Unavailable은 preparedDeadline까지 재시도(AU-33) **+ 단조 시간 상한**, 통과 시 DISPATCHING + 일정에 lease. commit 불명확·실패면 보내지 않음(AU-28) | `ConfirmFlowSpec` |
| 호출(§4.5) | 호출 직전 `connectDeadline` 재확인(AU-07), 호출 중 TX·잠금 없음 | AC 2 잠금 탐침(FOR UPDATE NOWAIT로 payment·authorization·control 비잠금 확인) |
| TX2(§4.6) | `AuthorizationResultService`(applyCall·applyObservation·markLeaseExpired·fence — 회복과 같은 단계): control → payment → authorization → 세션 → 사용량 → 일정, 관찰·Aggregate·거래·Outbox 같은 TX. 사건 이벤트는 **새 hold append 때만**(F-1), 늦은 승인 FORCED 표시는 고정된 한도 정책 이력(AU-29, `LimitPolicyHistoryPort` → entitlement provided `LimitPolicyLookup`) | `AuthorizationResultConcurrencySpec`(C25·C27) |
| AU-34(S4 이월) | `AuthorizationTimingConsistency` 기동 검사 `connectTimeout × (budget+1) < lease − margin` | `AuthorizationTimingSettingsSpec` |
| 영속화 | V12 `authorization_recovery_schedule`, runtime-privilege V13(SELECT·INSERT·UPDATE — DELETE 없음), `JpaAuthorizationRecoveryScheduleStore`(claimDue `FOR UPDATE SKIP LOCKED`) | 스키마·권한 시험 |
| HTTP | `ConfirmPaymentController`, ApiErrorCode `CONFIRM_CONTENT_CONFLICT`·`AUTHENTICATION_NOT_VERIFIED`·`PAYMENT_PLAN_NOT_ALLOWED`·`LIMIT_EXCEEDED`(고정 문구) | `ConfirmPaymentApiSpec` |

- 흐름 시험(ConfirmFlowSpec + institution): 58/58. 승인·재생·거절·PENDING·응답 유실·무응답·부분 승인(CONFLICTING hold·사건 1)·connect 거부(NotSent)·게이트 거절·AU-33 일시 재시도/기한 초과·TXd 장애(전송 0)·TX2 장애(원장 승인 1·PG 사실 0).
- **변이 RED**: 게이트가 Refused/NotFound를 무시 → 1 실패(ConfirmFlowSpec), 흡수된 hold에도 사건 이벤트 발행 → 1 실패(C27), TX1 회복 일정 INSERT 누락 → 9 실패(회복 시험). "TXd 커밋 없이 전송" 변이는 호출 값을 지어내지 않고는 표현할 수 없어 수행하지 않음 — TXd 장애 시 전송 0 시험이 증거.
- 수정 1회: 고정 시험 시계에서 AU-33 재시도가 끝나지 않는 결함 → 업무 시각 기한 + 단조 경과 시간 상한(`giveUp`).
- 바뀐 기존 기대값: **C21**(ConfirmConcurrencySpec) — 이전: 거절 결과 집합 = {CONFIRM_CONTENT_CONFLICT}. 이후: 다른 authenticationRef 두 요청 각각 ∈ {CONFIRM_CONTENT_CONFLICT, AUTHENTICATION_NOT_VERIFIED}.
  이유: §4.3 판정 순서상 그 요청이 Authorization보다 먼저 잠금을 잡으면 5a(재사용·충돌)가 아니라 6(세션 없음)에서 멈춘다 — S6 실행에서 3회 중 1회 관찰. Authorization 1·Attempt 1·나머지 6건 같은 authorizationId 불변식은 그대로(완화 아님 — 원래 기대값이 경합 승자를 고정한 오류).
  `ConfirmPaymentApiSpec`의 잘못된 URI 구성(공백 → `%20`)은 시험 구성 결함 수정(기대 404 그대로).

### 16.12 S7 — 회복 Worker(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험 |
|---|---|---|
| provided | `RecoverAuthorizationsUseCase`·`RecoverAuthorizationsCommand`(runId·batchSize)·`RecoveryRunResult`(지표 건수, ID 없음) | **RED-C 51 오류**(새 S7 시험 3개) |
| 조회 측 | `AuthorizationViewReader.findAttempt` → `JooqAuthorizationViewReader`(authorization_attempt + Authorization Route, 읽기 전용·비권위) | — |
| Worker(§6.1) | `AuthorizationRecoveryService`: 짧은 TX 선점(`SKIP LOCKED` + 다음 점검 시각 +조회 간격) → PREPARED ∧ 기한 경과 → fencing / DISPATCHING ∧ lease 경과 → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED) / OUTCOME_UNKNOWN → (이관 전) 조회 수 ≥ 예산 ∨ 연령 ≥ 최대 연령 → `AuthorizationOutcomeUnresolved` + escalatedAt(상태 불변), (이관 뒤) 보존기간 경과 → 조회 중지, 그 밖 TX 밖 조회 1회(원 요청 ID, 새 inquiryId, closeIfAbsent = confirmsNoEffect ∧ 연령 ≥ closableAfter) → `AuthorizationResultService.applyInquiry`(관찰 반영 + 조회 수·다음 시각 같은 TX). Unavailable은 일정 행만 갱신. 한 건 실패는 그 건만 | `AuthorizationRecoverySpec` 11 |
| trigger·지표 | `adapter.batch.AuthorizationRecoveryScheduler`(`@Scheduled`, `enabled` false면 무동작) + `AuthorizationRecoveryJobSettings`(trigger 키만 — batch adapter는 required Port 의존 금지), counter `paycore.payment.authorization_recovery{result}` | `AuthorizationRecoverySchedulerSpec` 4 |
| 경합 | C23(TXd vs fencing 5회), C24(TX2 반영 vs lease 만료 표시 3회), 회복 Worker 2개 동시 | `AuthorizationRecoveryConcurrencySpec` 3 |
| 시험 Route | `SIM-VAN-B`(프로파일 B — confirms-no-effect false, 같은 stub 포트) | — |

- 증거 시험: 응답 유실 → 조회 FOUND → AUTHORIZED 1·거래 1·PaymentAuthorized 1·**AUTH_REQUEST 1(재승인 없음)**·같은 키 재요청 수렴(AC1 §6-5), 점검 시각 전 조회 없음, TX2 장애 → lease 전 불변(선점이 앞서도 불변) → lease 만료 표시(조회·효과 없음) → 다음 조회 AUTHORIZED,
  TXd 장애 → 기한 전 불변(선점이 앞서도) → fencing FAILED(NOT_SENT)·한도 RELEASED·실패 이벤트 1·전송 0, 프로파일 A 기록 전 끊김 → closableAfter 전 NOT_FOUND(UNKNOWN 유지) → 뒤 closeIfAbsent → NO_EFFECT → FAILED(NO_EFFECT),
  프로파일 B → 종결 요청 없음·예산 소진 → Unresolved 1·UNKNOWN 그대로·종료 근거 0 → 느린 주기 조회 계속, 최대 연령 이관 → 보존기간 뒤 조회 중지(UNKNOWN 유지), UNAVAILABLE 1회 → 다음 조회 확정,
  PENDING 유지(이벤트 없음) → 결정 뒤 AUTHORIZED, 결과 단계 장애 → 그 건만 rollback·간격 뒤 재시도, 로그에 기관 요청 ID·기관 계약계정·HMAC 키 없음.
- **변이 RED**(대상 AuthorizationRecoverySpec + 경합 spec): closableAfter 무시 → 1, 예산 이관 제거 → 1, 보존기간 중지 제거 → 1, 중복 이관 → 2, 조회 수 미기록 → 2, 일정 INSERT 누락 → 9.
  첫 실행에서 생존한 3개(lease 판정 제거·prepared 기한 판정 제거·선점 시각 미루기 제거)는 시험이 그 창을 만들지 않아서였다 — 선점이 Aggregate 판정보다 앞서는 창(일정 시각만 앞당김)과 결과 단계 장애 뒤 재시도 간격 시험을 추가하고 재실행(결과 §16.13).
- 바뀐 기존 기대값: 없음(새 시험·새 Route만).

### 16.13 S7 checkpoint — 편차·미시험·실행 기록(구현자 실행 — Verifier 판정 아님)

| ID | 편차(명세 → 구현) | 이유 |
|---|---|---|
| D-S6-1 | dispatch lease를 Attempt 열(§4.4)이 아니라 `authorization_recovery_schedule.dispatch_lease_until`에 둔다 | AU-16 bookkeeping을 domain 밖 한 곳에 — Attempt Aggregate에 운영 시각을 넣지 않음. 판정은 잠근 Attempt 상태 + 일정 시각 |
| D-S6-2 | 회복 일정을 TX1에서 만든다(§6.1 ①은 lease 만료 표시 때 생성) | PREPARED fencing 후보가 처음부터 있어야 TXd 실패·commit 불명확(C29)을 회복이 찾는다 |
| D-S6-3 | AU-33 재시도 상한에 단조 경과 시간 추가 | 시계가 멈추거나 되돌아가도 무한 재시도하지 않게 |
| D-S7-1 | 후보 선택을 jOOQ `AuthorizationRecoveryCandidateReader`(keyset) 대신 JPA native `FOR UPDATE SKIP LOCKED` 선점으로 | 선점은 잠금이 필요하고 query adapter는 읽기 전용(ArchUnit). Attempt 정보는 jOOQ `findAttempt`(CQRS 유지) |
| D-S7-2 | 조회 간격 고정(backoff·jitter 없음), Unavailable도 예산에 셈, 연속 Unavailable 회로차단 없음 | Demo 최소 구현. 예산 소진 → 이관이라 무한 조회 없음. **설정 버전을 일정 행에 기록(§6.2 Auditability)도 미구현** — S8 또는 후속에서 결정 필요 |
| D-S7-3 | Unresolved 이벤트의 `unknownSince` = Attempt `preparedAt` | UNKNOWN 진입 시각을 저장하지 않음 — 가장 이른 노출 시작으로 보수적 |
| D-S7-4 | 보존기간 뒤 조회 중지는 일정 `closed_at`(Aggregate 불변) | 행 삭제 없음, RECON exact-match만 남김(AU-22) |
| D-S7-5 | 시험 전용 stub VAN의 원장 의미는 메모리 재현 | 두 앱 project 의존 금지(§8) — 원장 내구성·경합은 Simulator 시험이 실제 MySQL로 |

- **미시험(보고)**: C26(차단 Snapshot head X vs TXd head S), head 잠금 단독 시험, C28(connect 재시도 중 lease 만료 → NotSent NoChange — domain 시험만), C29 commit 중 연결 끊김 주입(커밋 전 실패로 대체), PG 재시작 실험(DISPATCHING 방치 → lease 만료 경로로 대체), Simulator 재시작은 S3 원장 내구성 시험.
- AU-29 구현됨(S6). n-3 Demo 규칙은 §5.4. m-D는 §16.14(D-AUTH1-005).

### 16.14 검토 #2 m-D — D-AUTH1-005(흡수된 승인의 해제 뒤 hold)(2026-10-18, 구현자 보고)

| 항목 | 변경 | 시험·RED |
|---|---|---|
| domain | `IncidentHoldSettlement.release` → `reopenForUnexplainedApprovals`: REAL·DOUBLE ∧ (CONFLICTING ∨ SUCCESS_AFTER_TERMINATION)일 때 설명 집합(확정 거래 terms ∪ REAL·DOUBLE 해제 hold 여는 관찰 terms) 밖이고 hold 출처가 아닌 첫 승인 관찰 → `OrderPaymentControl.reopenForUnexplainedApproval`(internal, 해제된 hold만) → occurrence + 1, 출처 (key, variant). 판정 기준은 `AuthorizationResolution.explainedByRealRelease`와 같다 | `IncidentHoldUnexplainedApprovalSpec` 5. **RED-A 3 실패**(재현·재해제·복원 — 새 hold 없음), 2 통과(남은 것 없음 경계 — 회귀 방지) |

- 시험: A가 연 SUCCESS_AFTER_TERMINATION hold에 다른 승인 B(다른 PTX·승인번호) 흡수 → A에 대한 REAL 해제 성공 + hold 2(출처 B) + 주문 차단 + FORCED 유지; hold 2를 REAL로 해제하면 B도 설명 → 더 열지 않음·CONSUMED;
  저장된 hold·해제로 reconstitute(재시작·복원) → hold 2 활성·출처 B 유지; A의 재보고(같은 terms, 다른 출처)만 흡수됐으면 새 hold 없음·CONSUMED; 흡수 없는 hold의 REAL 해제는 새 hold 없음.
- **변이 RED**(대상 75건): 해제 뒤 재개설 호출 제거 → 5 실패, 설명 집합 무시(재보고에도 새 hold) → 2 실패, 새 hold를 FORCED 정리 판단에서 제외 → 1 실패.
- **바뀐 기존 기대값**(사용자 답변으로 바뀐 규칙의 직접 결과 — 완화 아님, 더 이른 hold):
  - `AuthorizationResolutionSpec` H-1 "흡수 뒤 REAL 해제": 이전 — 해제 직후 활성 hold 없음, 재보고(rcn-c)가 hold 2(출처 rcn-c)를 엶. 이후 — 해제 연산이 hold 2(출처 = 흡수된 ntf-c)를 열고 재보고는 그 hold에 흡수(hold 수 2, 출처 ntf-c 유지, 결과 Incident 그대로).
  - `AuthorizationResolutionSpec` 검토 #1 H-1 "variant 1 흡수": 이전 — 해제 직후 `isOnIncidentHold() == false`, 재보고(rcn-b)가 hold 2를 엶. 이후 — 해제 연산이 hold 2(출처 (inq-k, variant 1))를 열고, B 재보고는 흡수(occurrence 2), A 재보고는 설명됨(hold 수 2 그대로).
- 영속화: 해제 기록 쓰기 경로는 RECON(runtime에 `incident_hold_release` INSERT 권한 없음, control 저장소는 저장되지 않은 해제를 거부) — 같은 TX의 DB 증명은 RECON-CONTRACT-001 해제 Use Case에서. 새 hold 행 INSERT는 기존 hold 저장 경로와 같다.
- 질문 후보(Coordinator): NO_EFFECT 해제 시점에 다른 승인 B가 흡수돼 있던 경우(D-AUTH1-005는 REAL·DOUBLE만) — 현재는 B 재보고 때 D-AUTH-012로 새 hold. 해제 시점에도 열지 여부는 미결로 둔다(구현·시험 없음).

### 16.15 S7 checkpoint 실행 기록(구현자 실행 — Verifier 판정 아님)

- 환경: WSL2 Ubuntu, Docker(Testcontainers MySQL 8.4.11), 기준 `481a9fa` + 미커밋 변경. VERIFY-GATE·commit·`--stop` 없음.
- S7 변이 재실행(§16.12 생존 3개, 추가 시험 뒤): lease 판정 제거 → 2 실패, prepared 기한 판정 제거 → 1 실패, 선점 시각 미루기 제거 → 1 실패(모두 RED). 추가 시험의 첫 실행에서 fencing 시각을
  잘못 잡은 시험 결함 1회 수정(앞선 선점이 다음 점검을 조회 간격 뒤로 미는 것이 의도된 동작 — 시험의 회복 시각을 T0+2s → T0+11s).
- **바뀐 기존 기대값(새 migration V12·V13에 따른 추가 — 완화 아님)**: `PaymentApiContextSmokeSpec`·`CheckoutWindowCheckMigrationStrategySpec`(목록 2곳·문자열 2곳·history 수 12 → 14)·`OldVolumeStartSpec`·
  `DatabaseAccountSeparationSpec`(설치 버전 목록에 "12","13" 추가), `DatabaseAccountSeparationSpec` runtime 표 권한에 `authorization_recovery_schedule` SELECT·INSERT·UPDATE 추가(SHOW GRANTS 정확 일치·
  "새 표에 권한 migration 없으면 실패" 시험 대상) + DELETE 거부(1142) 탐침 추가.
- `./gradlew :apps:payment-api:check` **BUILD SUCCESSFUL**(2026-10-18 약 05:43:18Z → 05:49:54Z, 6m36s): JUnit XML 118파일 **1,246건, 실패 0·오류 0·skip 0**, verifyNoInstancio·verifyRuntimeHasNoTestTools 통과.
  (직전 실행 2회는 위 migration 기대값 추가 전 9건, history 수 1건 실패 — 수정 후 재실행.)
- `./gradlew :apps:institution-simulator:test --rerun :apps:institution-simulator:check` **BUILD SUCCESSFUL**(05:57:33Z → 05:58:59Z): 10파일 **107건, 실패 0**(재실행 강제 — up-to-date 결과를 쓰지 않음).
- `python3 -B -m unittest discover -s harness/tests`: **86건 OK**(변화 없음).
- 시험 수: payment-api 1,165(S4) → **1,246**(+81: S5·검토 #2 Adapter·S6·S7·m-D). Simulator 101 → **107**. harness 86. 새 의존성 없음.

### 16.16 검토 #3(S5~S7 PASS_WITH_FIXES)·D-AUTH1-006 대응(2026-10-18, 구현자 보고 — Reviewer 재확인 대상)

- **RED-C** 2 오류(m-4 설정 `version`·`recoveryConfigVersion` 없음) → 설정 형태만 추가(검증 없음)한 뒤 **RED-A 14 실패**(아래 표의 RED 열). 통과한 새 시험은 회귀 방지(guard)로 표시.

| 지적 | 대응 | 시험·RED |
|---|---|---|
| D-AUTH1-006(Q-AUTH1-06) | `IncidentHoldSettlement.reopenForUnexplainedApprovals`의 REAL·DOUBLE 조건 제거 — 해제 판정과 무관(§4.11-2) | `IncidentHoldUnexplainedApprovalSpec` +3: **RED-A 2**(NO_EFFECT 해제 + B 흡수 → hold 2·FORCED 유지; A 재보고 흡수도 출처), guard 1(흡수 없음 → 새 hold 없음·RELEASED·새 Payment 허용). 변이(NO_EFFECT면 건너뜀) → 2 실패 |
| m-1 lease 표시가 선점 사본 기준 | `markLeaseExpired` TX에서 회복 일정을 잠가(authorization → … → 일정) `lease != null ∧ now ≥ lease`일 때만 표시, 아니면 NoChange. 회복 Worker는 DISPATCHING이면 판정을 TX에 맡긴다. TXd는 preparedAt + preparedDeadline 이후 통과시키지 않음(NotApplicable → fencing) | `AuthorizationRecoverySpec`: 선점(PREPARED) → TXd 커밋(DISPATCHING, `RECOVERY_BEFORE_FIND_ATTEMPT` 주입점) → 처리 → DISPATCHING 유지 **RED-A**; 기한 도달 TXd → PREPARED·전송 0 → fencing **RED-A**. 변이: lease 재판정 제거 → 2, TXd 기한 검사 제거 → 1 |
| m-2 보존기간 뒤 closeIfAbsent | closeIfAbsent = confirmsNoEffect ∧ closableAfter ≤ 연령 < 보존기간, 이관 전 보존기간 도달 시 먼저 이관. 기동 검사(AuthorizationTimingConsistency): maxAge < min(retention), closableAfter < retention, closableAfter ≥ preparedDeadline + dispatchLease | `AuthorizationTimingSettingsSpec` +4: **RED-A 3**, 통과 1(경계 통과). 변이: 각 검사 제거 → 1. (보존기간 도달 시 먼저 이관하는 코드 경로는 기동 검사가 막아 시험으로 도달 불가 — 방어 코드로 기록) |
| m-3 C26 | 차단 Snapshot(head X 보유) 중 TXd(head S) 대기 → 커밋 뒤 새 권리로 재판정 → GATE_REFUSED, 전송 0. 시험 Admission 대역의 답을 X 보유 중 바꿔 Snapshot 효과를 재현(운영 Admission 배선은 `AuthorizationAdmissionSpec`). head 행은 root로 준비(`ConfirmFixture.entitlementHead`) | `AuthorizationDispatchSafetySpec` C26 — **RED-A**(n-5: 판정 시각이 잠금 전 시각). head 잠금 단독 시험은 C26으로 대체 |
| m-3 C29·AU-28 | 시험 전용 `CommitFaultingTransactionManager`(BeanPostProcessor로 `transactionManager` 위임 — 무장 시 새 읽기·쓰기 TX를 커밋한 뒤 예외) | C29: 전송 0·202 → lease 만료 → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED), 종료 근거 0 — 기존 구현으로 통과(guard) |
| m-4 설정 버전 | 필수 `paycore.authorization.timing.recovery.version`(공백 불가·≤32 printable, 기본값 없음). **V12를 제자리 수정**(미커밋·미배포): `recovery_config_version VARCHAR(32) NOT NULL`(TX1), `escalation_config_version NULL`(이관 시) + CHECK `(escalated_at IS NULL) = (escalation_config_version IS NULL)`. payload `configVersion`·`escalationReason`(BUDGET \| MAX_AGE). backoff·jitter·회로차단 없음은 수용된 기술 편차(D-S7-2) | **RED-A 3**(TX1 열·이관 열·MAX_AGE 사유) + 설정 검증 RED-A 1. 변이(사유 항상 BUDGET) → 1 |
| n-1 일정 행 없음 | TXd: `schedules.lock(...) ?: error` → rollback, 전송 0 | **RED-A**(이전엔 일정 없이 DISPATCHING·전송) |
| n-2 전송자 예외 | `institution.authorize` 예외 → false(202), 회복이 lease 만료로 정리. 쓰기 시작 뒤 실패는 Adapter의 OutcomeUnknown 그대로 | 시험 전용 `ObservedAuthorizationPort`(@Primary 위임) — **RED-A**. 변이(예외 전파) → 1 |
| n-3 거래 type 필터 | jOOQ join에 `type = 'AUTHORIZATION'` | 시험 없음 — V9 CHECK가 지금은 AUTHORIZATION만 허용해 다른 type 행을 만들 수 없다(관찰 불가 변이, 미래 대비) |
| n-4 Route 환경 | `InstitutionRouteProfile.environment` 추가, TX1에서 Route 환경 ≠ Payment 환경 → 503(Capability 대조 전, 저장 없음) | `ConfirmStartSpec` LIVE·USD Payment + TEST Route → 503·저장 0 **RED-A**(이전엔 PAYMENT_NOT_ELIGIBLE 저장). 변이 → 1 |
| n-5 `at` | TXd 판정 시각을 head(S) 획득 뒤로 | C26의 `evaluatedAt` 단언 **RED-A** |
| n-6 이관 일정 선점 | 선점 시 `interval(s)`(이관 뒤 느린 간격) | `AuthorizationRecoverySpec` **RED-A**. 변이 → 1 |
| n-7 | KDoc을 `releaseIncidentHold` 위로, `reopenForUnexplainedApproval`은 아래로 이동 | — |
| n-8 AC2 탐침 | entitlement_head(행 존재) X NOWAIT, idempotency_request, authorization_recovery_schedule 추가(행 없음 = 실패로 구분) + 호출 스레드 `isActualTransactionActive()` = false 기록 | `ConfirmFlowSpec` AC2 — guard(기존 구현으로 통과) |
| n-9 | §6.1을 D-S6-1·D-S6-2·D-S7-1에 맞춤 | 문서 |
| n-10 | V12 CHECK environment IN ('TEST','LIVE'), closed_at ≥ created_at | root INSERT가 3819로 거부 **RED-A** |
| C21 | 두 분기를 순차로 고정한 시험은 이미 있다: `ConfirmStartSpec` "another key with a different authenticationRef … CONFIRM_CONTENT_CONFLICT"(Authorization 뒤)·"… unknown authentication reference, is AUTHENTICATION_NOT_VERIFIED"(Authorization 전) | 변경 없음 |
| m-A 문구 | §16.10 정정: "read에서는 중복, 쓰기 막힘은 현재 프레임 크기로 시험 불가" | 문서 |

- **바뀐 기존 기대값·준비**: C23(`AuthorizationRecoveryConcurrencySpec`) 경합 시각 T0+2s → **T0+0.5s** — 이유: m-1로 TXd가 preparedAt + 1s 이후 통과하지 않으므로 T0+2s에서는 항상 fencing만 이겨 경합이 사라진다(기대 불변식 그대로). `AuthorizationTimingSettingsSpec`의 `timings()` 준비에 `version` 추가.
  `AuthorizationResolutionSpec`·기존 기대값 변경 없음(D-AUTH1-006은 새 시험만).
- 변경 파일(이번 대응): domain `IncidentHoldSettlement`·`OrderPaymentControl`; application `AuthorizationDispatchService`·`AuthorizationResultService`·`AuthorizationRecoveryService`·`ConfirmStartService`·`AuthorizationEvents`, required `AuthorizationTimings`·`AuthorizationRecoveryScheduleStore`(RecoverySchedule)·`InstitutionRouteResolver`·`OutboxStore`(EscalationReason); adapter `AuthorizationTimingSettings`(+Consistency)·`ConfiguredInstitutionRouteRegistry`·`JpaRecoveryScheduleEntity`·`JpaAuthorizationRecoveryScheduleStore`·`OutboxPayloadJson`·`JooqAuthorizationViewReader`; `V12__authorization_recovery_schedule.sql`(제자리), main `application.yml` 주석, 시험 yml `version`.

### 16.17 S8 — 문서·하한·배포 조건(2026-10-19, 구현자 보고 — Reviewer 재확인 대상)

문서만 바꿨다(`docs/repository/**`). 코드·시험·migration·harness·정책·기대값 변경 없음. 같은 시각 Coordinator의 전체 `./gradlew check` 실행과 겹치지 않게 Gradle 명령은 실행하지 않았다.

| 문서 | 변경 |
|---|---|
| 이 명세 | rev 0.7. §20 배포 조건 신설. §7.1·§7.3·§7.4 끝에 "S8 현행화" 주석(실제 V9~V13 배치·Port/서비스 이름·D-S7-1로 없는 Reader) — 계획 문장은 기록으로 남김 |
| 이 명세(대조만) | rev 0.6 머리말이 적은 본문 변경이 코드와 일치함을 확인(추가 수정 없음): §4.11-2 D-AUTH1-006 = `IncidentHoldSettlement.reopenForUnexplainedApprovals`(판정 조건 없음), §6.1 회복 일정 TX1 생성·lease 일정 행(D-S6-1·2)·`claimDue` SKIP LOCKED(D-S7-1)·lease 재판정은 잠근 일정 기준(`AuthorizationResultService.markLeaseExpired`, m-1)·TXd 기한 이후 NotApplicable(`AuthorizationDispatchService`)·closeIfAbsent = confirmsNoEffect ∧ closableAfter ≤ 연령 < 보존기간, 보존기간 도달 시 먼저 이관(m-2)·기동 검사 3개(`AuthorizationTimingConsistency`), §6.2 필수 `recovery.version`(1..32 printable, 기본값 없음)·`recovery_config_version`(TX1)·`escalation_config_version`(이관, CHECK 짝)·payload `configVersion`·`escalationReason`(BUDGET \| MAX_AGE) |
| `ARCHITECTURE-CONVENTIONS.md` | §16 AUTH-001 신설(Port·Store·Reader 명명, Adapter·batch 패키지, 회복 선점, 시험 TM 장애 주입, JPA 연결). §12의 "AUTH-001에서 할 일" 두 곳에 §16 참조 표시 |
| `DOMAIN-DEFINITION.md` | §3.2 행 추가(종료 근거 출처, 관찰 식별 `ObservationRef`·hold 출처, 회복 일정·이관), §5 용어 추가, §7 코드 이름 목록에 AUTH-001 |
| `HARNESS-VERIFICATION-CONTRACT.md` | §7 AUTH-001 항목(새 harness 시험 2종·HARNESS-SELFTEST 하한 86, V9~V13 배포 전제 — AUTH-001 이전 인스턴스 모두 내린 뒤, Simulator 볼륨 권한 수렴), GRADLE-CHECK 하한은 Coordinator 실측 기입 자리 |
| `AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md` | §19.10 AUTH-001 구현 편차·확정(이 명세가 AC1과 다르다고 적은 것만) |

- **시험 하한·실행 기록(Coordinator, 2026-10-19 — Verifier 판정 아님)**: precheck 1(검토 #3 대응 뒤, 13:43:08Z 시작, `./gradlew --no-daemon --console=plain check` BUILD SUCCESSFUL 12m52s) Gradle 1,857건(payment-api 1,263) 실패·오류·skip 0, HARNESS-SELFTEST 86 OK. precheck 2(§16.18 gap·D-AUTH1-007 뒤, 14:41:34Z 시작, 6m34s) BUILD SUCCESSFUL, master-api 320, payment-api 1,279, financial-batch 26, internal-admin 29, institution-simulator 107, test-support 112 = **1,873건**, 실패·오류·skip 0(payment-api만 새로 실행, 나머지 모듈은 up-to-date — VERIFY-GATE가 `--rerun-tasks`로 전부 재실행), HARNESS-SELFTEST 86 OK. `harness/commands.json` GRADLE-CHECK·TEST-SUMMARY 하한 1586 → **1875**(실측, 올리기만), HARNESS-SELFTEST 86 유지. VERIFY-GATE·DEPS×5·CVE 결과는 tasks.json evidence에 기록.
- **발견(보고만 — 코드는 허용 경로·이번 지시 밖이라 고치지 않음)**:
  1. payment-api main `application.yml` 주석의 Route `capabilities` 키 목록에 `installment-minimum-amount`가 없다(코드·시험 설정에는 있음, AU-31·n-7).
  2. Simulator Demo 신용 할부 개월(`.env.example`·Simulator 시험 설정 `2,3,6,12`)이 payment-api 시험 Route·결제창 Plan Demo(`[2, 3, 6]`)와 다르다. Simulator `application.yml` 주석은 "결제창 Plan Demo 정책과 같은 값(AU-31)"이라 적는다.
     AU-31 기동 검사는 payment-api 안(결제창 ↔ Route)만 보며 별도 프로세스인 Simulator 프로파일과의 일치는 검사하지 않는다(§20-6). 현재 값에서는 PG가 12개월을 TX1에서 먼저 거절하므로 전송되지 않는다. Demo 값 정렬 여부는 Coordinator 판단.
  3. `.env.example`에 payment-api 쪽 `paycore.authorization.*`(Route·timing·recovery version) 예시가 없다 — 운영 기본값 없음 원칙대로 값이 없으면 기동 실패한다. 로컬 compose Demo 절차 공백(§20-3).
  4. §9 Observability 계획 중 counter `paycore.authorization.dispatch{outcome}`·`paycore.authorization.inquiry{result}`, timer `paycore.authorization.call.duration`, gauge(UNKNOWN 수·최고 연령·이관 수·활성 hold 수)와
     `JooqAuthorizationBacklogReader`는 구현돼 있지 않다. 있는 것: `paycore.payment.confirm`, `paycore.payment.authorization_recovery{result}`, 구조화 로그. 8대 원칙 Observability 증거 판정은 Reviewer·Coordinator.
- **질문 후보(Coordinator로 반환 — 결정하지 않음)**:
  - **Q-S8-1 Route profile version 미고정과 회복의 Capability 판정 시점**: §5.8·§9는 "Attempt Route Snapshot(routeRef·profile version)에 고정"이라 적었지만 V9에 profile version 열이 없고, 회복 Worker는
    조회마다 **현재** 등록부의 `routes.resolve(routeRef).capabilities`(`confirmsNoEffect`·`closableAfter`·`inquiryRetention`)로 `closeIfAbsent`(기관 무효과 종결 요청 → FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`) 근거)와
    이관을 판정한다. 전송 뒤 설정이 바뀌면 전송 당시와 다른 Capability로 무효과 종결을 요청할 수 있다. 권장안: Attempt(또는 Authorization)에 전송 시 profile version을 고정하고, 회복은 고정된 버전과 등록부 버전이
    다르면 종결 요청 없이 조회만(또는 이관) — fail-closed. 대안: (a) 현재 설정 사용 유지 + 배포 조건으로 "UNKNOWN 건이 있는 동안 Route Capability 변경 금지"(§20-6에 이미 적음), (b) 등록부가 버전별 프로파일을 함께 보관.
    영향: 무효과 확정(새 Payment 허용 근거)의 출처 정합성·Auditability. 막히는 작업: 없음(현재 Demo 설정은 기동 중 바뀌지 않음) — LIVE 준비 Gate·Route 설정 변경 절차 전.
- **후속(2026-10-19)**: 발견 1~4 대응과 Q-S8-1 답변(**D-AUTH1-007** = 권장안 A) 구현은 §16.18.

### 16.18 S8 gap 대응·D-AUTH1-007(2026-10-19, 구현자 보고 — Verifier/Reviewer 판정 아님)

**1) 발견 4 — §9 Observability 지표(코드)**

| 지표 | 위치 | 값 |
|---|---|---|
| counter `paycore.authorization.dispatch{outcome}` | `SimulatorVanAuthorizationAdapter.authorize` | APPROVED·DECLINED·PENDING·UNKNOWN·NOT_SENT·SENDER_FAILED(§16.19 n-5 — 결과 전 예외) |
| counter `paycore.authorization.inquiry{result}` | `…inquire` | FOUND·PENDING·NOT_FOUND·NO_EFFECT_CONFIRMED·UNAVAILABLE·SENDER_FAILED(§16.19 n-5) |
| timer `paycore.authorization.call.duration{operation}` | 위 두 메서드(연결 시도 포함 호출 전체) | AUTHORIZE·INQUIRY |
| gauge `paycore.authorization.unknown` / `.unknown.oldest_age_seconds` | `AuthorizationBacklogGauges` ← provided `AuthorizationBacklogQuery`(`AuthorizationBacklogService`) ← required `AuthorizationBacklogReader`(`JooqAuthorizationBacklogReader`) | OUTCOME_UNKNOWN Attempt 수 / now − 가장 이른 prepared_at(초, 없으면 0, 음수 없음) |
| gauge `paycore.authorization.escalated` | 같음 | `escalated_at IS NOT NULL ∧ closed_at IS NULL` 회복 일정 수 |
| gauge `paycore.payment.incident_holds.active` | 같음 | 해제 기록(`incident_hold_release`) 없는 hold 수 |

label은 위 닫힌 값만(ID·MID·기관 계정·Route 이름 없음). Domain에 Micrometer 없음(Adapter·Gauge 컴포넌트만). 하지 않은 것: §9의 `paycore.authorization.recovery{action}` 이름 — 기존
`paycore.payment.authorization_recovery{result}`(S7, 시험 기대값 있음)를 바꾸지 않았다(이름 변경은 기대값 변경). 정책 결정이 필요한 §9 항목은 없었다.
- RED: `SimulatorVanAuthorizationAdapterSpec` 지표 3건 — counter 0.0(`APPROVED expected:<1.0> but was:<0.0>`, `FOUND …`, label 집합 `[]`), `AuthorizationObservabilitySpec` 3건 — gauge 미등록 NPE
  (`~/gate-logs/s8gap-red-20261019T230125.log`, 28 tests / 6 failed). GREEN: 같은 두 spec + `ArchitectureSpec` 24·4·40 tests, 0 failures(`s8gap-green-20261019T230431.log`, exit 0).
- 시험 준비 변경(기대값 아님): `SimulatorVanAuthorizationAdapterSpec.adapter()`에 `meters` 인자(Adapter 생성자에 `MeterRegistry` 추가).

**2) 발견 3 — 로컬 Demo 승인 설정(`.env.example`)**: compose는 MySQL만 띄우고 앱 환경을 매핑하지 않는다(compose.yaml 변경 없음). Route 이름(`SIM-VAN-01`)·backend 키(`TEST`)는 relaxed binding
환경변수로 줄 수 없어 `PAYCORE_PAYMENT_AUTHORIZATION_JSON`(payment-api 프로세스에만 `SPRING_APPLICATION_JSON`으로) 하나로 두고, host·port·key-id·키·closable-after·보존기간은 Simulator 변수를 `${…}`로
참조한다(§20-6 짝). 값 = payment-api 시험 yml Demo, 단 `recovery.enabled = true`(로컬 회복 Worker 동작 — 시험은 false). 검증 `LocalDemoAuthorizationConfigSpec`(실제 설정 클래스 바인딩 +
등록부·시간·AU-34·m-2 기동 검사 + Simulator 짝). **한계**: 공개 API·결제창·인증 backend 설정 예시는 여전히 없어 이것만으로 payment-api 로컬 기동이 완성되지 않는다(이번 범위 밖 — 보고).
- RED: `PAYCORE_PAYMENT_AUTHORIZATION_JSON … Expected value to not be null`, 할부 `expected:<[2, 3, 6]> but was:<[2, 3, 6, 12]>`(`s8gap-red2-20261019T230909.log`, 2/2 failed). GREEN: 2 tests 0 failures(`s8gap-green2-…233139.log`).

**3) 발견 2 — Simulator Demo 할부 개월**: `.env.example`·Simulator 시험 yml을 `2,3,6`으로(Simulator main yml 주석에 [2, 3, 6]·AU-31 검사 범위 명시). Spring 연결 Simulator 시험은 3·5개월만 쓴다.
domain 단위 `VanFixture`(자기 프로파일 {2,3,6,12})와 `VanAuthorizationSpec`의 12개월 승인 시험은 yml과 무관하게 의도적으로 12를 쓰므로 바꾸지 않았다(기대값 변경 없음).

**4) 발견 1**: payment-api main `application.yml` Route capability 키 목록에 `installment-minimum-amount` 추가(+ AU-31 짝 주석).

**5) D-AUTH1-007(Q-S8-1 = A)**
- domain: `AuthorizationAttempt.routeProfileVersion`(PREPARED면 null, 값은 1..64 printable ASCII), `PaymentAuthorization.beginDispatch(attemptId, routeProfileVersion)`(필수 인자). 복원은 PREPARED+버전·빈 버전 거부,
  전송한 Attempt의 null(V14 이전 행)은 복원.
- 영속: **V14** `authorization_attempt.route_profile_version` VARCHAR(64) NULL + CHECK(PREPARED면 NULL, `^[!-~]{1,64}$`). 권한 migration 없음(V10 표 단위 UPDATE). Entity·Mapper·Repository 변경 감지, jOOQ
  `findAttempt`가 버전을 읽는다(`AuthorizationAttemptViewRow.routeProfileVersion`).
- TXd(`AuthorizationDispatchService`): `routes.resolve(routeRef).profileVersion`을 DISPATCHING과 같은 TX에서 고정. TX1 뒤 Route가 등록부에서 사라졌으면 GATE_REFUSED(전송 전 거절 — 새 분기, 기존 경로 불변).
- 회복(`AuthorizationRecoveryService`): `closeIfAbsent`는 고정 버전 = 현재 버전일 때만. 다르거나 null이면 조회·예산/연령 이관만(찾은 결정 반영은 그대로). 종결 보류는 로그 `closure=WITHHELD reason=ROUTE_PROFILE_VERSION_MISMATCH`.
- 기동 검사: 등록부 `profile-version` 1..64 printable ASCII(기존 필수·기본값 없음은 그대로).
- 시험: domain(`AuthorizationResolutionSpec` 고정·빈 값 거부, `PaymentAuthorizationReconstitutionSpec` 거부·legacy 복원), 실 MySQL(`AuthorizationPersistenceSpec` V14 — runtime 계정으로 저장·복원, PREPARED에 값/빈 값 3819,
  NULL 허용, 열 `varchar(64)/YES/utf8mb4_0900_bin`), 회복(`AuthorizationRecoverySpec` — 같은 버전: 기존 종결 경로 `[false, true]` → FAILED; 다른 버전·NULL: 11s·180s·360s 조회 모두 closeIfAbsent=false, UNKNOWN 유지,
  540s BUDGET 이관 1회, VAN 종결 기록 없음, 종료 근거 없음; 다른 버전에서도 FOUND 승인은 AUTHORIZED), 등록부(`ConfiguredInstitutionRouteRegistrySpec` 버전 형식 3건).
- RED: compile RED(`s8gap-red3-20261019T232613.log` — `beginDispatch` 인자·`routeProfileVersion` 없음). **변이 RED**: domain·영속·TXd를 구현하고 회복의 버전 비교만 없는 상태에서 실행 →
  다른 버전·NULL 2건 `expected:<[false, false, false]> but was:<[false, true]>`(`s8gap-red4-20261019T232821.log`, 106 tests / 2 failed — 비교를 지우면 시험이 실패함을 확인). 비교 구현 뒤
  `AuthorizationRecoverySpec` 19 tests 0 failures, `DatabaseAccountSeparationSpec` 15·`OldVolumeStartSpec` 4·`PaymentApiContextSmokeSpec` 5·`AuthorizationObservabilitySpec` 4 — 0 failures(`s8gap-green2-20261019T233139.log`).
- **바뀐 기존 기대값(추가만)**: migration 목록에 "14" 추가 — `CheckoutWindowCheckMigrationStrategySpec`(목록·history 크기 14 → 15·다른 볼륨 history 문자열 2곳), `DatabaseAccountSeparationSpec`(설치 버전),
  `PaymentApiContextSmokeSpec`, `OldVolumeStartSpec`. `AuthorizationPersistenceSpec`·`PaymentAuthorizationReconstitutionSpec`의 `sameState`에 `routeProfileVersion` 비교 추가. 시험 준비: `beginDispatch` 호출부에 Demo 버전 인자
  (`AuthorizationFixture.ROUTE_PROFILE_VERSION`), `VanRouteFixture.route(profileVersion)` 인자. 정책·판정 기대값 완화 없음.

**6) 전체 모듈 실행** `./gradlew --no-daemon --console=plain :apps:payment-api:check :apps:institution-simulator:check`(2026-10-19T23:36:05+09:00 시작, `~/gate-logs/s8gap-check-20261019T233605.log`, HEAD 481a9fa + 미커밋 변경):
**완료되지 않음** — 호스트 메모리 부족으로 하네스가 실행을 중단했다(업무 실패 아님, PASS 주장 아님). 중단 전 `institution-simulator:test`는 끝났다: 10 reports, 107 tests, 0 failures/errors/skipped(새 보고서).
payment-api `test`는 시작 직후 중단 — payment-api 전체 수·`CheckoutWindowCheckMigrationStrategySpec` history 문자열 수정(위 5) 뒤 재실행·ktlint/detekt 등 나머지 check 하위 작업은 **미검증**. 재실행은 Coordinator 판단.

### 16.19 최종 검토(PASS_WITH_FIXES) 대응(2026-10-20, 구현자 보고 — Verifier/Reviewer 판정 아님)

코드 2곳·시험 2곳(+ 시험 대역 1)·문서. 정책·업무 기대값 변경 없음(지표 label 값 하나 추가만).

| 항목 | 대응 |
|---|---|
| m-1 TXd "Route 미등록 → GATE_REFUSED" 분기(D-AUTH1-007) 시험·진단 없음 | `AuthorizationDispatchService` 그 분기에 `log.warn("operation=AuthorizationDispatch result=GATE_REFUSED reason=ROUTE_NOT_REGISTERED authorizationId={}")`(내부 ID만). 시험 `AuthorizationDispatchSafetySpec` "final review m-1": 시험 전용 `@Primary` `WithdrawableRouteResolver`(운영 `ConfiguredInstitutionRouteRegistry` 위임 — `ObservedAuthorizationPort` 선례, `ProgrammableVanTestConfig` 등록)로 TX1 뒤 `SIM-VAN-01`을 null로 → VAN 수신 0, Attempt ABANDONED_BEFORE_DISPATCH(GATE_REFUSED)·`route_profile_version` NULL, Payment FAILED·`PRE_TRANSMISSION_FAILURE_CONFIRMED`, 한도 RELEASED, 회복 일정 `closed_at` 있음, 이 Authorization의 Outbox 1건(PaymentAuthorizationFailed), 진단 로그 1줄(기관 요청 ID·기관 계정 없음) |
| n-5 Adapter `socket.use` 밖 예외가 timer·counter를 건너뜀 | `authorize`·`inquire`를 try/finally로 — 예외로 끝난 호출도 timer를 멈추고 닫힌 값 `SENDER_FAILED`(dispatch `outcome`, inquiry `result`)로 센다. 예외는 그대로 전파(Dispatch는 기존대로 202·회복). §16.18 지표표에 값 추가. 시험 `SimulatorVanAuthorizationAdapterSpec`: connector가 `IllegalStateException` → 두 호출 모두 예외 전파, 연결 0, `SENDER_FAILED` 각 1, timer AUTHORIZE·INQUIRY 각 1, label 이름 집합 불변 |
| n-1 | `AUTH-CONTRACT-001` §19.10: 머리 rev 0.8, 17행(Attempt `route_profile_version`, 불일치·NULL → 종결 요청 없음, Route 소실 → GATE_REFUSED), "Q-S8-1(질문 후보)" 문장을 D-AUTH1-007 구현으로 교체 |
| n-2 | `DOMAIN-DEFINITION` 무효과 종결 요청 행에 버전 조건(고정 버전 = 현재, D-AUTH1-007), `ARCHITECTURE-CONVENTIONS` §16에 "Route 프로파일 버전" 행 |
| n-3 | `HARNESS-VERIFICATION-CONTRACT` §7 배포 메모 V9~V13 → V9~V14(같은 전제, §20-6), 이 명세 §20-1 ④·pin 문장 V9~V14 |
| n-4 | §16.17의 "1586 → (기입 대기)" 자리 3줄 삭제(위 줄에 실측 1873) |

- **RED**: `~/gate-logs/final-fix-red-20261019T1500Z.log`(2026-10-19T15:00Z, 30 tests / 2 failed): `SimulatorVanAuthorizationAdapterSpec` 새 시험 `expected:<1.0> but was:<0.0>`(SENDER_FAILED 미계수), `AuthorizationDispatchSafetySpec` 새 시험은
  업무 단언(전송 0·FAILED·RELEASED·일정 종결·Outbox 1)을 모두 통과하고 진단 로그 단언에서 `Collection should have size 1 but has size 0`(로그 없음). **변이 RED**(`~/gate-logs/final-fix-mutation-red-20261019T1502Z.log`, 5 tests / 1 failed): 분기 조건을 `if (refuse)`로 지우면
  (`profile!!`) `dispatch` `expected:<true> but was:<false>`(NPE → GATE_FAILED rollback) — 시험이 분기를 지킨다. 변이는 즉시 원복(원문 일치 확인).
- **GREEN**: `./gradlew --no-daemon --console=plain :apps:payment-api:test --tests …`(2026-10-19T15:04:04Z~15:05:29Z, exit 0, `~/gate-logs/final-fix-green-20261019T150404Z.log`, HEAD 481a9fa + 미커밋 변경):
  ArchitectureSpec 40, SimulatorVanAuthorizationAdapterSpec 25(+1), AuthorizationDispatchSafetySpec 5(+1), AuthorizationObservabilitySpec 4, AuthorizationRecoverySpec 19(시험 대역 Route 조회 위임 경유 확인), ConfirmFlowSpec 14 = 107, 실패·오류·skip 0.
  payment-api 시험 수 1,279 → 1,281(+2). `commands.json` 하한(1873)은 올리지 않았다(harness 수정 금지 — Coordinator 판단). 전체 check·ktlint/detekt는 이번에 실행하지 않음(미검증).
- **수용된 후속(코드 변경 없음)**:
  - **n-6** gauge(`paycore.authorization.unknown`·`.oldest_age_seconds`·`.escalated`·`paycore.payment.incident_holds.active`)는 scrape마다 jOOQ 집계 조회를 한다. 지금 Demo 규모·색인(`ix_authorization_recovery_schedule_due` 등)에서는
    허용, 대량 운영 전 캐시/주기 집계로 바꿀지 결정(Observability 비용 — LIVE 준비 Gate 전).
  - **n-7** 회복 이관 사유 `MAX_AGE`는 기동 검사(maxAge < inquiryRetention·이관 전 보존기간 도달 시 먼저 이관)로 현재 설정에서 도달하지 않는 경로다. 분기·payload 값은 유지(설정 변경 시 안전망), 전용 시험은 추가하지 않음.

---

## 20. 배포 조건(rev 0.6 예고, rev 0.7 작성 — 코드·migration 실제 내용 기준)

§18·§19 번호는 이 명세에서 쓰지 않는다(rev 0.6 머리말이 이 절을 §20으로 예고했다). 운영 배포는 이 개발 요청 범위 밖이다 — 아래는 Demo·시험 환경과 이후 배포 절차가 지켜야 할 전제이며,
배포 파이프라인이 생기면 그 절차의 검사로 옮긴다(HARNESS-VERIFICATION-CONTRACT §7, AUTHN-001 선례).

1. **PAYMENT migration 순서(AC 19 — 전략 불변)**: `CheckoutWindowCheckMigrationStrategy`가 그대로 적용한다. V8_1이 pending이면 ① V8까지 → ② pin(없을 때만) → ③ 결제창 시각 채우기 → V8_1 직전 재확인·재채우기(최대 3회,
   남으면 기동 거부) → ④ 나머지 전부(V8_1, **V9~V14**). V8_1이 이미 적용된 볼륨은 그대로 migrate. 배치:
   | 버전 | 위치 | 내용 |
   |---|---|---|
   | V9 | `db/migration/payment` | 새 표 7개(`payment_authorization`·`authorization_attempt`·`institution_observation`·`payment_transaction`·`daily_limit_usage`·`limit_reservation`·`incident_hold_release`) — ALTER 없음(AU-19) |
   | V10 | `db/runtime-privilege/payment` | 위 표의 runtime 표 단위 권한(§7.2 — 관찰·거래 SELECT·INSERT, 해제 기록 SELECT만, DELETE 없음) |
   | V11 | `db/migration/payment` | 기존 표 ALTER: `incident_hold` occurrence·여는 관찰 4열·새 UNIQUE 추가 후 옛 UNIQUE 삭제·reason CHECK 교체, `payment_termination_evidence` 출처 4열(넷 다 NULL 또는 NOT NULL), `idempotency_request` 결과 열·operation별 CHECK |
   | V12 | `db/migration/payment` | `authorization_recovery_schedule`(회복 일정, `recovery_config_version NOT NULL`, 이관 버전 CHECK 짝, environment·closed_at CHECK) |
   | V13 | `db/runtime-privilege/payment` | 회복 일정 SELECT·INSERT·UPDATE(DELETE 없음) |
   | V14 | `db/migration/payment` | (rev 0.8, D-AUTH1-007) `authorization_attempt.route_profile_version` VARCHAR(64) NULL + CHECK(PREPARED면 NULL, 값은 1..64 printable ASCII). ALTER 한 문장. 권한 migration 없음(기존 표 단위 UPDATE) |
   권한 migration은 PAYMENT 초기화 스크립트가 만든 procedure `<DB>_privilege.set_runtime_table_privileges`를 쓴다(관례 §9.2 — 새 procedure·새 계정 없음). V9~V14는 runtime 채우기에 기대지 않고 pin 표를 건드리지 않는다.
   시험 근거: `OldVolumeStartSpec`(V6 볼륨·V8_1 적용 볼륨 → 한 번의 기동에 V14까지 — rev 0.8), `CheckoutWindowCheckMigrationStrategySpec`(history 15 — rev 0.8), `DatabaseAccountSeparationSpec`(설치 버전 목록·SHOW GRANTS 정확 일치·1142).
2. **V11 배포 전제(Coordinator 판정 2026-10-18, §7.4·§14-5)**: AUTH-001(V9~V13) 배포는 **AUTH-001 이전 payment-api 인스턴스를 모두 내린 뒤** 새 인스턴스를 올린다(rolling 혼재 금지). 이유: V11 뒤에도 이전 인스턴스가 돌면
   종료 근거를 출처 열 NULL로 써서 CHECK(넷 다 NULL)는 통과하지만 출처가 비는 행이 생긴다(Auditability). AUTHN-001의 V7 → V8_1 전제(AUTHN-001 이전 인스턴스 없음, AN §22-11)도 그대로다.
   - V12는 미배포 상태에서 제자리 수정됐다(§16.16 m-4·n-10). 수정 전 V12를 적용한 로컬 개발 볼륨이 있으면 Flyway 검증(checksum)이 기동을 막을 수 있다 — 그런 볼륨은 이 작업의 개발 산출물이며 운영 볼륨은 없다.
     처리(볼륨 재생성 등)는 개발자 판단이고 `flyway repair`로 checksum을 덮지 않는다(Simulator 원장·PAYMENT 사실 보존 원칙과 같은 보수 쪽).
3. **payment-api 필수 설정(운영 기본값 없음 — 없거나 범위 밖이면 기동 실패)**:
   - `paycore.authorization.timing.dispatch.{lease,safety-margin,prepared-deadline,gate-retry-interval}`(양수, margin < lease).
   - `paycore.authorization.timing.recovery.{enabled,fixed-delay,batch-size(1..1000),inquiry-interval,inquiry-budget(≥ 1),max-age,slow-inquiry-interval,version}` — **`version`은 필수·기본값 없음**(1..32 printable ASCII),
     회복 일정 행(`recovery_config_version`·`escalation_config_version`)과 `AuthorizationOutcomeUnresolved.configVersion`에 기록된다. 수치를 바꾸면 버전도 바꾼다(D-AUTH-009 버전 있는 설정 — 값은 Demo).
   - `paycore.authorization.backends.<env>.type` = `SIMULATOR`, env = `TEST`만(REAL·모르는 type·LIVE SIMULATOR 거부), `paycore.authorization.allowed-hosts`(loopback 밖 Simulator host 허용 목록 — 배포 egress 목록과 같게, §5.6 겹 5).
   - `paycore.authorization.routes.<routeRef>.{environment,institution,protocol-version(sim-van-1),profile-version,host,port,key-id,key-base64,connect-timeout,read-timeout,total-deadline,connect-retry-budget,
     capabilities.{confirms-no-effect,closable-after,inquiry-retention,same-id-resend-safe,authentication-evidence-reusable-for-resend,supported-currencies,supported-card-types,installment-card-types,installment-months,
     installment-minimum-amount}}`. routeRef는 1..64자 `[A-Za-z0-9._-]`, Route 환경에 backend가 있어야 하고, host는 loopback 또는 허용 목록, HMAC 키(base64)는 **32바이트 이상**·환경변수로만(저장소에 키 없음),
     read timeout ≤ 전체 deadline. 권리 Snapshot의 routeRef가 등록부에 없으면 confirm은 503(fail-closed, 저장 없음). `profile-version`은 1..64 printable ASCII(D-AUTH1-007).
   - 로컬 Demo 예시(S8): `.env.example` `PAYCORE_PAYMENT_AUTHORIZATION_JSON`(payment-api 프로세스에만 `SPRING_APPLICATION_JSON`으로) — Simulator VAN 변수를 참조해 §20-6 짝을 맞춘다.
     검증 `LocalDemoAuthorizationConfigSpec`. 공개 API·결제창·인증 설정 예시는 아직 없다(§16.18).
4. **기동 일관성 검사(위반 → 기동 실패)**:
   - `AuthorizationTimingConsistency`(모든 Route): `connectTimeout × (connectRetryBudget + 1) < dispatchLease − safetyMargin`(AU-34), `maxAge < inquiryRetention`, `closableAfter < inquiryRetention`,
     `closableAfter ≥ preparedDeadline + dispatchLease`(m-2).
   - `AuthorizationProfileConsistency`(AU-31): 환경별 결제창 Plan Demo 정책과 같은 환경 Route Capability의 통화별 할부 개월·할부 최소금액이 같아야 한다(Route가 지원하지 않는 통화의 정책은 비교하지 않음).
   - `ConfiguredInstitutionRouteRegistry` 생성 검사(위 3의 격리·형식 조건).
5. **가상 VAN(Simulator) DB·권한(AU-20)**: Simulator migration V1(인증)·V2(`van_ledger_entry`·`van_pending_decision`·`van_evidence_use`·`van_inquiry_log`)·V3(증거 참조 색인)과 권한 V4(`db/runtime-privilege/simulator`:
   `authentication_transaction`·`van_pending_decision` SELECT·INSERT·UPDATE, `lookup_log`·`van_ledger_entry`·`van_evidence_use`·`van_inquiry_log` SELECT·INSERT, DELETE 없음). runtime 계정은 role의 표 단위 권한만
   (DB 단위 권한 REVOKE). **기존 Simulator 볼륨**은 초기화 스크립트 `docker/simulator-mysql/init/01-simulator-database.sh`를 컨테이너 안에서 한 번 다시 실행해 수렴시킨다(재실행 안전 — `.env.example` 절차).
   수렴 전에는 `VanLedgerPrivilegeGuard`가 runtime 연결에서 원장 표 UPDATE/DELETE가 1142로 거부되지 않음을 보고 **Simulator 기동을 거부**한다.
6. **가상 VAN 설정과 PG 설정의 짝(별도 프로세스 — 기동 검사로 서로 확인하지 않는 항목)**:
   - Simulator `PAYCORE_SIMULATOR_VAN_PORT`(HTTP 포트와 달라야 함 — Simulator가 거부)와 payment-api Route `host`·`port`, Simulator `PAYCORE_SIMULATOR_VAN_KEY_ID`·`PAYCORE_SIMULATOR_VAN_HMAC_KEY_BASE64`와 Route `key-id`·`key-base64`가 같아야 한다
     (다르면 VAN은 MAC 불일치 요청을 원장 기록 없이 끊고(§16.6 n-4) PG는 쓰기 시작 뒤 실패라 OutcomeUnknown — 기관 사실은 없지만 PG는 UNKNOWN을 유지하고, 조회도 같은 키로 실패해 Unavailable이 예산에 셈해진 뒤 이관된다(D-S7-2)).
   - Simulator 기관 계정 → 프로파일(A = 무효과 확정 있음, B = 없음)과 payment-api Route `capabilities.confirms-no-effect`·`closable-after`·`inquiry-retention`이 같은 기관 사실을 말해야 한다. PG는 B Route에 종결 요청을
     보내지 않고 B의 NO_EFFECT_CONFIRMED 응답은 INTEGRITY로 본다(§5.7.2). PG Route가 A인데 Simulator 계정이 B이거나 PG `closable-after`가 Simulator보다 짧으면 Simulator가 종결하지 않으므로(§16.6) UNKNOWN이
     유지된다(보수 쪽 — 그래도 설정 불일치는 배포 확인 항목).
   - Simulator 프로파일 신용 할부 개월과 PG Route·결제창 Plan Demo 값의 일치는 AU-31 검사 밖이다. Demo 값은 S8에서 [2, 3, 6]으로 맞췄다(§16.18 — `.env.example`·Simulator 시험 설정).
   - (rev 0.8, D-AUTH1-007 — 이전 임시 전제 "UNKNOWN 동안 Capability 변경 금지"를 대체) **Route Capability를 바꾸면 그 Route의 `profile-version`을 반드시 함께 바꾼다**(배포 확인 항목 — 기동 검사로는
     "Capability가 바뀌었는데 버전이 같다"를 알 수 없다). 버전이 바뀌면 그 전에 전송된 UNKNOWN Attempt는 무효과 종결을 요청하지 않고 조회·대사 이관만 한다(fail-closed). V14 이전에 전송된
     Attempt(버전 NULL)도 같다. 버전을 바꾸지 않고 Capability만 바꾸면 회복이 바뀐 Capability로 종결을 요청할 수 있으므로 이 규칙이 Auditability·무효과 확정 출처의 전제다.
   - V14(`authorization_attempt.route_profile_version` 추가, NULL 허용·CHECK, 권한 변경 없음 — 기존 표 단위 UPDATE)는 V9~V13과 같은 배포 전제(§20-2: 이전 인스턴스 모두 내린 뒤)를 따른다.
     V14 이전 인스턴스가 V14 볼륨에서 돌면 버전 없이 DISPATCHING을 써서(CHECK 통과) 그 Attempt는 회복에서 불일치로 처리된다(보수 쪽).
7. **격리(§5.6)**: REAL VAN Adapter 없음(ArchUnit `authorizationPortsImplementedOnlyBySimulatorAdapter`), LIVE 환경 backend 없음, Simulator 원장 environment CHECK 'TEST', 네트워크 egress 허용 목록은 배포 작업(기록만),
   TLS·mTLS·실제 기관 키는 LIVE 준비 Gate. 운영 DB·키·endpoint는 이 개발 요청에 포함되지 않는다.
8. **회복 Worker 운영**: `paycore.authorization.timing.recovery.enabled` = false면 Scheduler는 아무것도 하지 않는다 — 그 동안 PREPARED fencing·lease 만료 표시·조회·이관이 일어나지 않아 UNKNOWN·AUTHORIZING이 남는다
   (상태를 잘못 바꾸지는 않음). 다중 인스턴스는 `FOR UPDATE SKIP LOCKED` 선점으로 직렬화되며 Redis·분산 lock이 필요 없다(§10). 회복 일정 행은 삭제하지 않는다(정리 Job은 후속 — §7.1 보존).
