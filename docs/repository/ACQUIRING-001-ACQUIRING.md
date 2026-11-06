# ACQUIRING-001 — 가상 SFTP 매입과 전송/확정 상태

- **rev 0.2** (2026-11-05, Implementer). rev 0.1(1단계 명세 초안) 뒤 사용자 결정 D-ACQ-008~012(Q-ACQ-07~11)와 Coordinator 결정(sshd 2.20.1 허용·jOOQ codegen 미도입·허용 경로 추가·배치 분할)을 반영했다.
- **rev 0.3** (2026-11-05, Implementer): 배치1 리뷰 REVIEW_FAIL(MAJOR 2·MINOR 4·NIT) 반영 — MJ-1 표현 불가 값은 거래 단위 대기(UNREPRESENTABLE), MJ-2 Payment 단위 보류 대기(PAYMENT_HELD — **Coordinator 결정**), m-1 SWEEP 같은 Payment 조건, m-2 잠금 뒤 판정 재확인 + lane 잠금 시험(AQ-C4), m-3 AlreadySealed 우선 + 마감 정책 소급 변경 거부, m-4 isolation mutation, NIT(파일 분리·바이트 행 hash 대조·후속 항목). §17.1에 실행 기록.
- **rev 0.4** (2026-11-05, Implementer): 배치1 재리뷰 REVIEW_PASS 뒤 **배치2 중 2-D(SFTP 전송 상태기계·회복·sshd 2.20.1 고정)만 구현**. NIT-1(전송은 저장 봉인 바이트를 직접 읽고 봉인을 다시 부르지 않음) 반영. 2-E(Simulator acquirer)·2-F(결과 수신·마감·CANCEL 연결·지표·스케줄러)·2-G(재매입 기록)·E2E는 **미착수**(§16). 새 해석 I-22~I-27(§3), §4.5, §17.2 실행 기록.
- **rev 0.5** (2026-11-05, Implementer): **배치2 중 2-E(가상 기관 Simulator 매입사 `acquirer`)만 구현** — 내장 SFTP 서버(공개키만·host key 파일·chroot inbox/outbox·접근 규칙), 수신 원장·레코드 판정(VAN 원장 대조)·게시 계획·Worker(ACK·결과 임시명 → rename), 결정적 시나리오 10종, simulator DB V10~V12·열 권한 procedure·계정 spec, ACK·결과 계약 v1 golden(harness 독립 생성 ↔ simulator 렌더러) + 매입 파일 golden simulator 사본. financial-batch 코드는 바꾸지 않았다. 새 해석 I-28~I-33·I-38~I-40(§3 — rev 0.7에서 번호 이동: rev 0.6(2-D)이 I-34~I-36을 따로 썼다), §5 ACK·결과, §7, §9·§10·§11·§12 2-E 행, §17.3 실행 기록, §16 2-F 인계 계약.
- **rev 0.6** (2026-11-05, Implementer): 2-D 리뷰 REVIEW_FAIL(MAJOR 2·MINOR 6·NIT) 반영 — MJ-1 rename 직전 남은 lease 검사(LEASE_EXHAUSTED)·lease 식(접속 2회·요청 6회·rename 여유·시계 차이), **시도별 임시명은 서버 규칙(I-32) 변경이 필요해 미적용 — Coordinator 보고**; MJ-2 rename 응답 없음 + 재확인 없음 → TRANSMISSION_UNKNOWN(I-24 판정표 수정); m-1 키 기동 파싱·채널 밖 예외 결과화·sweep 계속; m-2 SshClient 명시 구성; m-3 LIVE 전송 거부; m-4 실제 claim 경쟁·분기 시험; m-5 SUPERSEDED 관찰 감사 행(V9); m-6 lock read 단언; NIT(connect 취소·결과 TX BLOB 미조회·활성 TX 가드·ArchUnit sshd 규칙). §17.4 실행 기록. apps/institution-simulator는 바꾸지 않았다.
- **rev 0.7** (2026-11-05, Implementer): 2-E 리뷰 REVIEW_FAIL(MAJOR 1·MINOR 7·NIT 6) 반영 + **Coordinator 결정 (9) Q-2D-1 시도별 임시명**(C-9). MJ-1 이중 매입 방어를 기관 근거(VAN 원장 행) 기준으로 — 같은 VAN 승인·취소 행의 두 번째 ACCEPTED, 원승인별 취소 소비 누적 한도, CANCEL 원거래 참조 = 수락 SALE 참조(V13, 사유 코드 재사용, I-31 갱신); m-1 서버 알고리즘 allowlist·idle/auth timeout·인증 시도·동시 세션 상한·업로드 16 MiB 상한·임시 파일 상한/정리·재hash 생략; m-2 열린 쓰기 핸들 rename 거부; m-3 SFTP 경로로는 RECEIPT_CONFLICT가 생기지 않음 정정·rename 거부 warn; m-4 Worker 파일·작업 단위 실패 격리; m-5 기동 검사가 진행 표 모든 열 탐침; m-6 시험 추가; m-7 공개키 파일·root-dir 그룹·기타 쓰기 거부; NIT n-1~n-6. 2-E 해석 번호 이동(I-34~I-36 → I-38~I-40, rev 0.6과 충돌), 새 해석 I-41~I-44. §17.5 실행 기록. apps/financial-batch는 바꾸지 않았다.
- **rev 0.8** (2026-11-05, Implementer): Coordinator 결정 C-9(Q-2D-1) FINANCIAL 쪽 적용 — 업로드 임시명을 **시도별 `.{fileId}.dat.{attemptNo}.part`**로 바꿈(옛 공용 임시명 미사용), 쓰기 핸들을 닫은 뒤 rename(Write·Create·Truncate, Append 없음), 시험 SFTP 서버가 rev 0.7 서버 계약(임시명 문법·옛 이름 거부·짝 rename·열린 핸들 rename 거부·NO_SUCH_FILE)을 흉내 냄. AQ-T20(늦게 깨어난 시도는 자기 임시 파일만 rename)·AQ-T21(NO_SUCH_FILE·계약 밖 이름). I-34 남은 위험 갱신. §17.5. apps/institution-simulator는 바꾸지 않았다.
- **rev 0.9** (2026-11-05, Implementer): 2-E 재리뷰 REVIEW_PASS(조건부 — MINOR 6·NIT) 반영. m-1 V13을 V10에 합침(2-E 미커밋 — V10 이상이 적용된 볼륨 없음, 있으면 Simulator 볼륨 재생성; 백필은 상계액을 되살릴 값이 없어 택하지 않음), m-2 업로드 16 MiB 상한을 열린 쓰기 채널(`BoundedUploadChannel`)에서 지킴(copy-data 우회 차단), m-3 I-31 "원승인 단위 금액 상한" 표현·상계 식별 책임(FINANCIAL `acquisition_netting`)·계약 v2 후보, m-4 기동 검사가 append-only 4표 모든 열 탐침, m-5 VAN client 공유 명시, m-6 임시 파일 상한과 FINANCIAL 재시도(상한 도달 시 같은 파일 ID의 닫힌 이전 시도 정리), n-4 CANCEL 근거의 원승인 행 = 수락 SALE 근거 행 대조, n-6 문서 정리, 재리뷰 NIT n-1·n-2·n-3·n-5·n-7 기록(§16). §17.6 실행 기록. apps/financial-batch는 바꾸지 않았다.
- **rev 1.0** (2026-11-05, Implementer): **배치2 2-F·2-G 구현 + E2E 스크립트 작성(실행 안 함)**. 2-F: ACK·결과 수신(§5.1 golden financial 사본·TARGETS 추가, (매입사, 이름, SHA-256) 처리 기록, 격리), 수신 축·레코드 결과 축(append-only), 결과 마감 UNKNOWN·늦은 결과(D-ACQ-010), ACK·결과 hash 일치 = 전송 완료 근거(I-9), D-ACQ-009 CANCEL 봉인 연결(+ NIT-3 거래 단위 대기·저장 행 계약 위반 INTEGRITY_VIOLATION, NIT-2 카드 종류 열거값, 상계 금액 DB 대조), JPA native count 지표(Micrometer), 스케줄러(기본 비활성). 2-G: 재매입 2인 승인 기록·검사(입력 경로 없음 — 서명 채널 운영 구현 언제나 거부 + runtime INSERT 1142 + 실행 Use Case 언제나 거절). 2-E 재리뷰 NIT n-1·n-2·n-3·n-5·n-7 원문 기록(§16). 새 해석 I-45~I-53(§3), §4.6·§4.7, §9·§10·§11·§12·§14·§16·§17.7. apps/institution-simulator는 바꾸지 않았다(golden은 financial-batch에 바이트 복사).
- **rev 1.1** (2026-11-06, Implementer): 2-F·2-G 리뷰 REVIEW_PASS(MINOR 5·NIT 7) + **사용자 결정 D-ACQ-013(Q-2G-1)·D-ACQ-014(Q-2G-2)** 반영. D-ACQ-013: 재매입 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(`ReacquisitionRule.netAmountMinor` — 승인 기록에는 금액을 넣지 않음, I-54). D-ACQ-014: UNKNOWN 대상은 대사 미매입 확정 근거 필요 → 대사 기능 전에는 거절(UNKNOWN_NOT_RECONCILED), 세대를 넘는 ACCEPTED 하나 = 앱 규칙(다른 세대 ACCEPTED 뒤 ACCEPTED → CONFLICT) + DB(V14 `acquisition_accepted_record` PK(거래, 종류)), 승인 무효 재검사 `revalidate`(TARGET_CHANGED). m-1 불요 취소의 원 SALE CONFLICT 지표·error 로그, m-2 전송 축 격리 파일 응답 격리(TRANSMISSION_QUARANTINED), m-3 "같은 세대" 서술 정정, m-4 B2를 증거에서 한계로, m-5 다음 봉인일 결과 구분(NO_CUTOFF_POLICY 로그·지표), n-1 B2 근거 정정, n-2 FILE_REJECTED 뒤 결과, n-3 합계 오버플로 = FORMAT_INVALID, n-4·n-6 메모·체류 시계, n-5 재전송 비교. 새 해석 I-54~I-58. §17.8 실행 기록.
- 상태: 배치1 구현·재리뷰 REVIEW_PASS(Verifier 판정 전). 배치2-D rev 0.8(C-9 반영). 배치2-E rev 0.9(재리뷰 MINOR 반영). 배치2-F·2-G rev 1.0 리뷰 REVIEW_PASS(조건부) → **rev 1.1 리뷰 반영 — 대상 spec·mutation 실행 완료, 모듈 전체 실행은 Coordinator 별도 프로세스(지시), Verifier/Reviewer 판정 전**(DONE 아님). **E2E는 스크립트만(실행은 Coordinator — C-6)**.
- 기준: branch `feat/foundation-setup`, HEAD `12c6b5e` + 작업 트리(미커밋 — 이 작업 변경 + Coordinator의 harness 상태 파일 변경).
- 표기: "사용자 결정"은 `docs/DECISIONS.md`의 결정만이다. **I-n = Implementer 해석**(가역 구현 선택 또는 결정에서 끌어낸 보수적 해석)이며 사용자 승인이 아니다. **C-n = Coordinator 결정**(가역, 사용자 결정 아님).
- 근거 문서: DECISIONS D-ACQ-001~012·D-FFR-002/003·D-CPD-001·D-MS2-005·D-ADMIN-003/007·D-INST-CODE-001, `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §5.2·§5.4·§5.5, `docs/baseline/PayCore-정산지급방식-확정과-상세권장안.md`(매입 요청 = 외부기관 전송 완료), architecture 04·10(INV-05·06·09·11·12·13·16·18), FIN-FACT-RELAY-001 rev 0.2, FIN-DBACCOUNT-001 rev 0.1, ARCHITECTURE-CONVENTIONS §2·§3·§9.2·§14·§23, TECHNOLOGY-AND-PRINCIPLES §1~§5.

## 1. 범위와 제외

| 포함 | 배치 | 제외(후속·다른 작업) |
|---|---|---|
| `paymentfact`: D-FFR-003 ORIGINAL_UNKNOWN 자동 해제(Inbox TX 안 + 일괄 처리 SWEEP) | 1(2-B) **구현** | 정합성 보류 REVIEW_REQUIRED의 운영자 해소 입력(ADMIN — "강제 Hold 해제" 2인 승인, 후속) |
| 파일 계약 v1 golden + 매입 Domain(후보·상계·봉인·CANCEL 판정 규칙·마감 정책) | 1(2-A) **구현** | 정산·지급 보류·회수(D-ACQ-005 → SETTLEMENT-CONTRACT-001) |
| FINANCIAL 매입 표·runtime 권한·lane·membership·상계·제외·봉인 + 봉인까지의 Spring Batch Job | 1(2-C) **구현** | 실제 카드사 전문·인증서·운영 host key(LIVE Gate §15) |
| SFTP 전송 상태기계·회복(같은 ID 재업로드·stat 확인), sshd 2.20.1 고정 | 2(2-D) | 기관 입금(ProviderFunding)·가상 은행 |
| Simulator `acquirer`(내장 SFTP·수신 원장·ACK/결과 Worker·시나리오) | 2(2-E) | 대사 Case 생성·Adjustment(RECON) |
| ACK·결과 수신, 결과 마감 UNKNOWN, 늦은 결과(D-ACQ-010), CANCEL 레코드(D-ACQ-009), 매입 지표 | 2(2-F) | Batch metadata 정리 Job(D-ACQ-007 운영 정책) |
| 재매입 요청·2인 승인 기록·검사(FINANCIAL만 — 입력 경로 없음 = 실행 불가) | 2(2-G) | 재매입 입력 ADMIN 화면·호출(ACQ-ADMIN-001 — D-ACQ-011) |

Kafka/Redis는 쓰지 않는다(ADR-001). 매입의 중복 방지·순서·재시작 안전성은 FINANCIAL 로컬 TX, 행 잠금(lane·결제별 직렬화 행), UNIQUE(membership·상계·제외·해제), 봉인 바이트 보존과 저장 바이트 대조가 지킨다. 브로커나 분산 잠금이 이 불변식을 대신한다고 주장하지 않는다.

## 2. 사용자 결정과 적용

| 결정 | 적용 |
|---|---|
| D-ACQ-001 | 매입 후보의 원천 = FINANCIAL 투영. `acquiring`은 `paymentfact.application.provided.PaymentFactAcquisitionSource`(DTO)로만 읽는다(PAYMENT DB·결제 사실 표 직접 SQL 없음 — INV-02·INV-18) |
| D-ACQ-002 | SALE = 승인 투영만. 전송 전(= membership 확정 전, D-ACQ-012) 전액 취소 → `acquisition_exclusion`(FULLY_CANCELLED_BEFORE_SUBMISSION) + 상계 기록, 부분 취소 → 순액 SALE + 상계 기록. 원거래 모르는 취소 → 보류(후보 아님) |
| D-ACQ-003 | (매입사, 환경, 업무일)당 정기 파일 1개(버전 001). 마감 = 버전 관리 설정 `paycore.financial.acquiring.cutoff-policies`(Demo 값), 업무일 = Asia/Seoul 달력일(Demo 한정, Q-CALENDAR 전) |
| D-ACQ-004 | 파일 상태 3축 분리(전송·수신 ACK·결과), REJECTED 종결, UNKNOWN → 대사, 자동 재전송 없음 — 상태 열·CHECK는 배치1, 전송 축 전이 2-D, 수신·결과 축 전이·결과 마감 UNKNOWN 2-F(§4.6) |
| D-ACQ-005 | 매입 모듈은 지급·보류 효과를 만들지 않는다(기록·알림만) |
| D-ACQ-006 | 클라이언트 키 인증 + 호스트 키 고정, 키는 환경변수/파일로만(배치2) |
| D-ACQ-007 | Job은 FINANCIAL DB BATCH_* JobRepository(V1)를 쓴다 |
| **D-ACQ-008** (Q-ACQ-07) | 레코드 `merchantRef` = PayCore MID(`merchant_account_id`) + 기관 거래 참조(providerTransactionId·승인번호·매입사·거래일)로 대조. 실제 계약번호는 후속 PaymentAuthorized v2. **LIVE 매입 차단**: 봉인 Use Case가 LIVE lane을 `LIVE_BLOCKED`로 거부, 후보 규칙도 LIVE를 대기로 둔다(이중 방어). payment-api 변경 없음 |
| **D-ACQ-009** (Q-ACQ-08) | 전송 후 취소의 CANCEL 레코드는 원 SALE이 ACCEPTED일 때만. 결과 대기 → 자동 대기, UNKNOWN → 대사·경보, REJECTED → "취소 매입 불요" 기록. 배치1은 `CancelRecordRule`(순수 규칙)만 두었고, **2-F(rev 1.0)가 봉인에 연결했다**(`CancelCandidateRule` — §4.7, `acquisition_cancel_not_required`, AQ-X1·X2) |
| **D-ACQ-010** (Q-ACQ-09) | 늦게 온 유효 결과는 append-only로 자동 반영, UNKNOWN 행 보존, ACCEPTED 인정 시각 = 수신 시각, 상충은 격리 — **2-F 구현**(`RecordResultRule`, `acquisition_record_result`, AQ-R5·R6) |
| **D-ACQ-011** (Q-ACQ-10) | 재매입(파일 전체 거절 뒤 재매입 포함)은 2인 승인. 이번 작업은 FINANCIAL 기록·검사까지, ADMIN 입력은 ACQ-ADMIN-001, 그 전 재매입 실행 불가 — **2-G 구현**(`ReacquisitionRule`·승인 기록 표, 실행 불가 3겹 — I-51, AQ-G1..G3) |
| **D-ACQ-012** (Q-ACQ-11) | 상계 경계 = membership 확정 시점(rev 0.1 I-1을 사용자 결정으로 승격). 배치1 구현: membership 확정 = **봉인 TX 커밋**. 그 전 받은 취소만 상계, 이후 취소는 별도 CANCEL |
| **D-ACQ-013** (Q-2G-1) | 재매입 레코드 금액 = 원승인액 − 그때까지 받은 모든 취소(순액, 각 취소는 한 번만), "취소 매입 불요" 기록은 유지. 실행은 ACQ-ADMIN-001 — 이번 작업은 규칙 함수 `ReacquisitionRule.netAmountMinor`(RQ-9)와 명세. 승인 기록에는 금액을 넣지 않는다(I-54) |
| **D-ACQ-014** (Q-2G-2) | UNKNOWN은 대사로 미매입이 확정된 경우에만 재매입 대상(대사 기능 전 → 거절 UNKNOWN_NOT_RECONCILED, I-55), 재매입 뒤 앞 세대 ACCEPTED → 승인 무효(TARGET_CHANGED — `revalidate`), 거래·종류당 세대를 넘는 ACCEPTED 하나(앱 규칙 + V14 PK — I-56, AQ-R14) |
| D-FFR-002 | 새 표 7개마다 runtime 최소 권한(V6) + FinancialDatabaseAccountSpec 기대값 추가 |
| D-FFR-003 | 원승인 도착 + MATCH → `payment_fact_hold_release` RELEASED(append-only), 불일치 → REVIEW_REQUIRED. 보류 원본 행 불변 |
| 기준선(정산지급방식) | "매입 요청" = 외부기관 전송 완료(전송 축 TRANSMITTED만, 배치2). "매입 확정" = 레코드 ACCEPTED |

### 2.1 Coordinator 결정(가역 — 사용자 결정 아님)

- C-1: `org.apache.sshd:sshd-sftp` 2.20.1 catalog 고정 허용(배치2에서 추가 — supplementary osv 0건 조건), spring-integration-sftp 미사용. **rev 0.4 적용**(OSV 0건 확인 — §6·TECH-BASELINE §10).
- C-2: financial-batch에 jOOQ codegen 미도입. backlog 지표는 JPA/native 읽기 전용 count(배치2).
- C-3: 허용 경로 추가 — `docs/repository/TECH-BASELINE.md`, `harness/technology-baseline.json`, `docs/repository/ARCHITECTURE-CONVENTIONS.md`. internal-admin 제외.
- C-4: 배치 분할(배치1 = 2-B → 2-A → 2-C, 봉인까지).
- C-9: (Coordinator 결정 (9) Q-2D-1, 2026-11-05) 업로드 임시명을 **시도별** `.{id}.dat.{n}.part`로 바꾼다. 서버 규칙 I-32를 넓혀 그 이름 쓰기와 짝 최종명 rename을 허용한다. FINANCIAL은 앞으로 `.{id}.dat.{attemptNo}.part`만 쓴다(2-D 후속 — Coordinator가 따로 맡김). 옛 공용 임시명 허용 여부·n 문법·잔여 임시 파일 정리는 Implementer가 근거와 함께 정한다(→ I-32·I-41).
- C-6: (harness/tasks.json coordinatorDecision (6)) FINANCIAL ↔ Simulator 실제 프로세스 E2E는 GRADLE-CHECK 밖 Coordinator 실행 스크립트(`harness/e2e/**`)로 2-F 뒤 Demo 스택에서 실행. financial-batch 시험은 시험 전용 SSHD(I-27).

## 3. Implementer 해석 목록(사용자 결정 아님)

| ID | 해석 | 근거·대안 |
|---|---|---|
| ~~I-1~~ | → **D-ACQ-012**(사용자 결정으로 승격) | |
| I-2 | 업무일 D 정기 파일 대상 = `approved_at ≤ cutoffInstant(D)`이고 봉인 시점에 FINANCIAL이 이미 받은, 아직 판정 안 된 승인. 마감 뒤 늦게 relay된 승인은 다음 업무일 파일로 간다. **마감 전 봉인 거부**(`BEFORE_CUTOFF`) | 승인일 기준 수수료·정산일과 무관(금액 의미 변화 없음). 매입 기한은 LIVE Gate |
| I-3 | 정기 파일은 업무일 순서로만(lane 마지막 봉인일 + 1만 허용 — `OUT_OF_ORDER`). 대상 0건이어도 헤더 + 0 트레일러 파일을 봉인 | 가역. 대안: 빈 날 생략 |
| I-4 → **C-5** | **Coordinator 결정(리뷰 MJ-2, 가역 — 사용자 결정 아님)**: 같은 Payment에 미해제 보류(RELEASED 해제 기록 없는 보류)가 하나라도 있으면 그 Payment의 **모든** 후보를 대기(`PAYMENT_HELD`, 기록 없음, 다음 봉인에서 다시 판정). 승인 자신·딸린 취소의 보류는 더 구체적인 사유(`AUTHORIZATION_HELD`·`CANCELLATION_HELD`)로 표시한다. rev 0.2는 승인·딸린 취소의 보류만 봐서, 다른 원거래를 가리키는 이른 취소나 같은 Payment의 두 번째 승인(AUTHORIZATION_CONFLICT)이 보류된 채 A1을 총액으로 매입했다(AQ-DB7a·b) | fail-closed 쪽으로 좁힘 |
| I-5 | KRW 외 통화 승인은 **대기**(`UNSUPPORTED_CURRENCY` — rev 0.1의 "제외 기록"에서 바꿈: 통화 범위 채택 전 종결 판정을 기록하지 않는다). 파일·레코드·상계·제외 표 CHECK는 KRW만 | 사용자 지정 "원화 정수", CLAUDE.md 통화 범위 미채택 |
| I-6 | TEST 거래 → Simulator 매입사(D-CPD-001). LIVE는 D-ACQ-008로 차단 | TEST 매입분의 정산 포함은 SETTLEMENT 판단 |
| I-7 | 파일 전체 거절(ACK FILE_REJECTED)도 재매입 절차(D-ACQ-011이 명시적으로 포함 — 이제 사용자 결정) | |
| I-8 | 유효 결과 파일은 ACK가 없어도 수신 근거(RESULT_IMPLIED). ACK hash ≠ 봉인 hash → 격리 | 배치2 |
| I-9 | 전송 완료 근거 3종(RENAMED·VERIFIED_FINAL_PRESENT·ACK_HASH_MATCH), 없으면 TRANSMISSION_UNKNOWN | 배치2 |
| I-10 | 같은 파일 ID 재업로드 허용(같은 봉인 바이트), 새 ID 재전송 없음 | 배치2 |
| I-11 | 봉인 바이트는 `acquisition_file_content`(불변)에 보관. 같은 봉인을 다시 부르면(재시작) 저장 헤더·레코드로 다시 렌더링해 저장 바이트·hash·합계와 대조 → 다르면 `INTEGRITY_VIOLATION`(전송 금지·error 로그) | Demo 크기 전제(MEDIUMBLOB). 대용량은 LIVE Gate |
| I-12 | 재매입 파일 = 원 업무일 다음 버전(002~), 레코드는 원 파일·순번 참조(`reacquisitionOf = {fileId}#{seq}`), generation ≥ 1 | 배치2. golden에 형태만 고정 |
| I-13 | D-FFR-003 MATCH = 원승인을 이력에 넣고 `PaymentFactProjectionRule`을 다시 적용해 보류가 하나도 없음(신원·승인 ID·Payment·통화·승인액·기관 원거래번호 일치, 같은 원승인 반환 순번 유일, 누적 취소 ≤ 승인액). 원승인 자체가 보류면 REVIEW_REQUIRED(`ORIGINAL_HELD`) | Q-FFR-03 권장안 문구("신원·통화·승인액·합계")의 구체화 |
| I-14 | 파일 렌더·해석·hash는 순수 Domain(`AcquisitionFileFormat`), SFTP는 adapter.integration(배치2). Simulator는 golden으로만 맞춘다 | 기존 관례 |
| I-15 | 인코딩 US-ASCII(UTF-8 부분집합, BOM 없음), LF, 마지막 줄 뒤 LF, 빈 줄 금지. 필드 문법 `[A-Za-z0-9._:#-]{1,64}`(ID)·`[A-Z0-9]{1,32}`(기관 코드)·`[A-Z0-9_]{1,32}`(토큰) | 가역 Demo 계약 |
| I-16 | 봉인은 **단일 로컬 TX**(BUILDING 상태·chunk 없음 — rev 0.1의 keyset chunk 설계를 바꿈): 전부 아니면 전무라 재시작이 단순하고 membership 확정 시점이 한 커밋이다. 대량 lane의 chunk 봉인·잠금 시간은 LIVE Gate | Demo 규모 전제. 대안: BUILDING + keyset chunk(재시작 checkpoint) |
| I-17 | 봉인·SWEEP Use Case는 **REQUIRES_NEW + READ COMMITTED** 자기 TX. Batch Tasklet step TX(metadata)와 업무 효과가 따로 커밋되며, 업무 커밋 뒤 step 실패는 같은 JobParameters 재시작이 멱등하게 수렴(SWEEP = UNIQUE(hold_id), 봉인 = AlreadySealed) | READ COMMITTED 필수: REPEATABLE READ snapshot이면 결제별 행 잠금 직전에 커밋된 취소를 놓쳐 D-ACQ-012가 깨진다. 대안: step TX에 isolation 지정 참여 |
| I-19 | (리뷰 MJ-1) PAYMENT가 확정해 FINANCIAL이 받은 사실 값이 파일 v1 문법으로 표현되지 않으면(공백 있는 승인번호, 소문자 발급사 코드, MID의 구분자, 밀리초 아래 시각 등) **그 거래만** 대기(`UNREPRESENTABLE`)하고 error 로그(거래 ID만, 문자 정리 — 값 없음). 수신 계약(FactText)은 바꾸지 않는다(PAYMENT 확정 사실을 거절하지 않음). 판정은 레코드를 실제로 만들어 레코드 불변식(= 파일 문법)으로 한다. rev 0.2는 봉인 TX 전체가 계약 위반으로 rollback되어 lane이 영구히 멈췄다 | 대안: 수신 때 거절(PAYMENT 확정 사실을 잃음 — 택하지 않음) |
| I-20 | (리뷰 m-2) 결제별 행 잠금 뒤 "판정 끝남"을 다시 확인한다(같은 승인을 판정하는 TX는 모두 그 행을 잠그므로 잠금 뒤 READ COMMITTED 값이 최종). lane 잠금과 독립인 이중 방어 | 공통 판정 PK(SALE gen0·제외 통합 표)는 택하지 않음 — 표 구조 변경 없이 같은 보장. lane 잠금이 있는 동안 이 재확인 제거는 동등 변형(§17.1 E) |
| I-21 | (리뷰 m-3) 같은 날 재봉인은 마감 정책 조회보다 **먼저** 저장 파일을 확인하고, 저장된 (정책 버전, 마감 시각)이 지금 설정의 그 업무일 값과 다르거나 그 날 정책이 없으면 `CUTOFF_POLICY_CHANGED`로 거부(D-ACQ-003 버전 관리: 시행된 버전을 소급 변경·삭제하면 과거 파일 근거가 사라진다 — 새 값은 새 버전·새 시행일로) | 기동 시 전수 검사 대신 봉인 시 검사(설정 변경이 그 lane의 재시작 때 드러남). 기동 검사는 배치2 후보 |
| I-22 | (2-D) 전송은 `acquisitionSubmissionJob`의 step이 **아니다**. `TransmitAcquisitionFileUseCase`(`transmit(fileId)`·`transmitPending(lane)`)를 스케줄러·운영 절차가 부른다(스케줄러는 2-F — 미구현). 재시작 근거는 Batch metadata가 아니라 파일 행 전송 상태·lease·@Version과 시도 기록이다 | 봉인 Job 재시작이 SFTP 가용성에 묶이지 않고, 회복(결과 모름·lease 만료)과 첫 전송이 같은 경로다. 배치1 AQ-J1·J2 기대값 불변. 대안: Job 세 번째 step(rev 0.3 §4.4 계획) |
| I-23 | (2-D) 같은 파일 ID 최종명에 다른 바이트가 있으면 전송 축 **TRANSMISSION_QUARANTINED**(5번째 상태, CHECK 확장)로 격리, 덮어쓰지 않고 자동 처리 없음(다시 점유하지 않음). 해소 입력은 후속(ACQ-ADMIN-001 범위 후보) | 기준선 §5.4 "같은 ID 다른 내용 격리". 대안: TRANSMISSION_UNKNOWN에 사유만 기록(다음 회차마다 다시 확인·행 누적 — 택하지 않음) |
| I-24 | (2-D, **rev 0.6 수정 — 리뷰 MJ-2**) 절차 = 최종명 확인(크기 → 있으면 **되읽기 SHA-256** 대조) → 없으면 [남은 lease 검사] `.{id}.dat.{attemptNo}.part` 쓰기(rev 0.8 C-9 — 시도별, 핸들 닫은 뒤 rename) → [남은 lease 검사] rename → **새 접속**에서 다시 확인. stat 크기만으로 전달을 인정하지 않는다. 판정표(rename 결과 × 재확인): 일치 → TRANSMITTED(rename 성공이면 RENAMED_AND_VERIFIED, 아니면 VERIFIED_FINAL_PRESENT) / 다른 바이트 → 격리 / **없음**: rename 성공 → TRANSMISSION_UNKNOWN(RENAMED_BUT_ABSENT, error), rename 명시 거절(Refused) → NOT_DELIVERED(RENAME_REFUSED), **rename 응답 없음(Interrupted) → TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN)**(rev 0.4는 NOT_DELIVERED → SEALED — NOT_FOUND만으로 미수신 단정, CLAUDE.md 위반) / 재확인 실패: rename 성공 → TRANSMITTED(RENAMED), 그 밖 → TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN). Simulator가 최종 파일을 보존한다는 전제(§7) | I-9의 구체화. 실제 기관이 수신 뒤 파일을 옮기면 "없음"이 미수신 근거가 아니다 → I-25 복귀 규칙 + ACK(2-F) 근거 |
| I-25 | (2-D) 점유 때 복귀 상태를 정한다: SEALED에서 점유 → 진전 없음·전달 안 됨이면 SEALED, 결과 모름·lease 만료에서 점유 → **TRANSMISSION_UNKNOWN**(조회 NOT_FOUND만으로 미수신 단정 금지 — CLAUDE.md). 같은 ID·같은 바이트 재업로드는 기관 쪽 중복 판정으로 안전(I-10). lease 만료 뒤 늦게 끝난 시도는 @Version이 달라 결과를 버린다(SUPERSEDED — 시도 행만 남고 결과 행 없음) | lease = 설정(Demo PT2M 시험·운영값 LIVE Gate), `connect-timeout + 6 × io-timeout`보다 길어야 기동 |
| I-26 | (2-D) 키: 개인키 = 설정 **파일 경로**(암호 없는 OpenSSH 형식, 소유자만 읽기 — POSIX 권한 검사, Demo), host key = SHA-256 지문 1~2개. 인증은 공개키만(비밀번호·keyboard-interactive 끔). 매입사 항목이 없으면 NOT_CONFIGURED(fail-closed), 항목이 있으면 모든 값 필수. host key 불일치 → NOT_DELIVERED(HOST_KEY_REJECTED) + error 로그(지문·키 원문 없음), 같은 시도 안 재시도 없음. 알고리즘은 JDK ECDSA P-256 | D-ACQ-006 적용. 암호화 키·비밀 저장소는 LIVE Gate |
| I-27 | (2-D) financial-batch 시험의 SFTP 상대는 시험 전용 SSHD 서버(`TestAcquirerSftpServer`, 같은 sshd 2.20.1)다. Simulator 코드를 쓰지 않는다(harness `test_simulator_isolation` — 앱 간 project 의존 금지). FINANCIAL ↔ Simulator 실제 프로세스 간 E2E는 2-E 뒤 별도 실행 위치가 필요하다(§16 — Coordinator 확인) | 격리 규칙 유지 |
| I-18 | 후보 키 조회는 매 봉인마다 lane의 마감 이하 승인 전체를 읽고 판정 끝난 것을 뺀다(watermark 없음) | Demo 규모. 비용이 이력에 비례 — LIVE Gate(watermark·색인 설계) |
| I-28 | (2-E) ACK·결과 계약 v1(§5.1 — rev 0.1 §5.6 초안을 바꿈): ACK 이름에 순번을 붙인다 `ACK.{id}.{nn}.dat`(초안 `ACK.{id}.dat`). outbox 파일은 덮어쓰지 않으므로 같은 ID 다른 내용(RECEIPT_CONFLICT)의 ACK가 별도 이름을 가져야 한다. ACK·결과 모두 트레일러 bodySha256(부분 다운로드 검출), 결과 트레일러는 ACCEPTED 금액을 SALE·CANCEL로 나눈다(초안 `acceptedAmountTotal` 한 칸 — 부호 의미가 모호). 레코드 0건 파일은 R 줄 없는 결과 파일 하나(결과 완료 근거). 게시 임시명은 점으로 시작(`.{name}.tmp`) — 서버는 그 이름의 SFTP 읽기를 거부하고, 받는 쪽은 목록에 보여도 무시한다(리뷰 n-1) | 가역 Demo 계약(golden). 2-F가 같은 golden으로 읽는다. 실제 기관 규격은 LIVE Gate |
| I-29 | (2-E) 수신 판정: 그 파일 ID의 **첫 수신이 결정**(RECEIVED 또는 FILE_REJECTED, ACK 01). 같은 ID·같은 내용은 새 기록·새 ACK 없이 멱등(앞선 ACK가 답 — 최종 파일이 보존되므로 Worker가 회차마다 다시 보아도 기록이 늘지 않는다), 다른 내용은 RECEIPT_CONFLICT(ACK 순번 +1, 레코드 처리 없음, error 로그). **SFTP 경로로는 RECEIPT_CONFLICT가 생기지 않는다**(rev 0.7 정정 — 리뷰 m-3): 최종명 쓰기·덮어쓰기 rename·삭제가 모두 거부되므로 같은 ID 최종 파일은 SFTP로 한 번만 생긴다. 이 판정은 저장된 파일이 SFTP 밖에서 바뀐 경우(저장소 교체·운영자 조작 — AS-4가 호스트에서 지우고 다시 올려 흉내 낸다)의 방어다. 순번 99 초과는 기록·ACK 없이 error 로그. "봉인 hash 검증"은 받은 바이트 전체 SHA-256을 계산해 수신 원장·ACK `receivedSha256`·결과 `fileSha256`에 싣는 것이다 — 가상 매입사는 FINANCIAL 봉인 hash를 미리 받지 않으므로 대조는 FINANCIAL이 ACK·결과로 한다(I-8). 파일 안 검증은 트레일러 bodySha256·건수·합계·순액과 레코드 모양 | 기준선 §5.4. 대안: 같은 내용 재확인마다 DUPLICATE 행(최종 파일 보존 때문에 회차마다 행이 쌓임 — 택하지 않음) |
| I-30 | (2-E) 게시 계획은 수신 TX에서 한 번 정해 기록한다(결과 줄·작업 행). 시각 = 수신 시각 + 설정 지연(Demo), 결과 파일 producedAt = 계획 시각 → 재시작·재게시가 같은 바이트. 회차 번호 = (시각, 원본 → 반복) 순서. 게시는 TX 밖 파일 rename, 그 뒤 게시 기록 TX(@Version). rename 뒤 커밋 유실은 다음 회차가 최종명의 같은 바이트를 확인해 기록만 하고, 다른 바이트면 덮어쓰지 않고 PENDING + error(운영자 확인) | TECH §3 외부 효과 TX 밖. 대안: 게시 시점에 계획(재시작 때 다른 결과 — 택하지 않음) |
| I-31 | (2-E) 레코드 판정(가상 매입사 기관 규칙 — PayCore 정책 아님): SALE = VAN 원장(설정 `van-client`의 TEST APPROVED 승인, 기관 거래 참조로 찾음)과 승인번호·기관 거래일·통화·승인액 일치 + 상계액 ≤ VAN이 승인한 그 원승인 취소 합. CANCEL = 원 SALE이 이 매입사에서 ACCEPTED(D-ACQ-009와 같은 조건) + VAN 승인 취소와 원거래 참조·기관 거래일·통화·취소액 일치. **이중 매입 방어 = 기관 근거 기준(rev 0.7 — 리뷰 MJ-1)**: 같은 VAN 승인 행(SALE)·같은 VAN 취소 행(CANCEL)의 두 번째 ACCEPTED는 거래 ID가 달라도 DUPLICATE_RECORD(DB UNIQUE(매입사, 종류, accepted_evidence_entry_id) — V13). 원승인별 취소 소비(수락 SALE 상계액 + 그 원승인으로 수락된 CANCEL 금액) ≤ VAN이 승인한 그 원승인 취소 합 — 넘으면 NETTED_CANCEL_MISMATCH(**원승인 단위 금액 상한** — 상계·CANCEL로 쓴 취소 금액의 합이 VAN이 승인한 그 원승인 취소 합을 넘지 못한다. 어느 VAN 취소가 상계됐는지는 가상 매입사가 알 수 없다 — 파일 v1 SALE 레코드는 상계 **금액**만 싣는다. 상계된 취소의 식별 책임은 FINANCIAL `acquisition_netting`(취소 → (파일, 순번))에 있고, 레코드에 상계 취소 참조를 싣는 것은 계약 v2 후보·LIVE Gate 15다(재리뷰 m-3)). CANCEL의 원거래 참조 ≠ 수락된 원 SALE의 기관 거래 참조, 또는 VAN 취소가 가리키는 원승인 행 ≠ 수락 SALE의 근거 행(재리뷰 n-4 — 참조 문자열이 같아도 행으로 다시 대조) → CANCELLATION_MISMATCH. 같은 거래 ID 두 번째 ACCEPTED도 계속 DUPLICATE_RECORD(V10 UNIQUE — 이중 방어). 사유 코드는 모두 기존 코드(§5.1 계약 그대로). 누적 한도는 Worker 회차 직렬화(한 JVM) 아래 앱 판정이다 — 여러 Simulator 인스턴스가 같은 원승인의 다른 근거를 동시에 수락하는 경합은 DB가 막지 않는다(Demo 한 인스턴스 전제, §14-15). 대조하지 않는 것: merchantRef(VAN 원장에 없음 — D-ACQ-008은 MID를 싣는 것까지), CANCEL 승인번호(VAN이 주지 않을 수 있음), 카드종류·할부·발급사. 같은 참조가 둘 이상이면 "찾지 못함"(근거를 골라 쓰지 않음) | rev 0.1 §7.3의 "recordAmount ≤ 승인액 − 기관이 아는 취소 합"은 D-ACQ-012 이후 취소(별도 CANCEL)가 먼저 VAN에 있으면 정상 SALE을 거절하므로 바꿨다 |
| I-32 | (2-E, **rev 0.7 — C-9·리뷰 m-1·m-2**) SFTP 접근 규칙: 쓰기 열기 = inbox **시도별 임시명 `.{id}.dat.{n}.part`**(n = `[1-9][0-9]{0,5}` — 1~999999, 10진·앞 0 없음)만. **옛 공용 임시명 `.{id}.dat.part`는 거부**: 시도별 이름의 목적(늦게 깨어난 시도가 다른 시도의 바이트를 쓰거나 rename하지 못하게)이 공용 이름을 함께 허용하면 깨지고, FINANCIAL은 앞으로 시도별 이름만 쓴다(C-9) — 남겨 둘 소비자가 없다. rename = 시도별 임시명 → 그 짝 최종명만, 대상이 있으면 거부(덮어쓰기 없음 — 두 시도 중 먼저 rename한 쪽만 최종명이 된다), **그 임시 파일의 쓰기 핸들이 열려 있으면 거부**(m-2 — 쓰는 중인 바이트를 확정하지 않는다). 쓰기 끝이 16 MiB − 1을 넘으면 거부(m-1) — rev 0.9부터 쓰기 이벤트가 아니라 **열린 쓰기 채널**(`BoundedUploadChannel`)에서 지킨다(재리뷰 m-2: copy-data 같은 서버 안 복사 확장은 쓰기 이벤트를 거치지 않았다 — AS-17 RED로 확인). 새 임시 파일이 상한(32)에 닿으면 같은 파일 ID의 닫힌(쓰기 핸들 없는) 이전 시도 임시 파일을 먼저 지우고 다시 센다(재리뷰 m-6). 새 임시 파일은 inbox 한 곳에 32개까지(넘으면 거부 — 디스크 상한), 마지막 수정이 `part-retention`(Demo PT1H 시험·설정 필수)보다 오래된 임시 파일은 Worker가 지운다. 삭제·디렉터리·링크·복사·속성 변경(SETSTAT·chmod) 거부, outbox는 읽기만(점 임시명 읽기 거부), inbox는 읽기 허용(FINANCIAL 되읽기 확인 I-24). 목록은 `/inbox`·`/outbox`만. 계정 하나 = 매입사 하나, chroot = `{root}/{code}`. rename 거부는 warn 로그(파일 ID만 — m-3) | 경로 이탈은 RootedFileSystem + 정확히 두 단계 위치 규칙(이중 방어). 임시 파일 정리의 나이는 파일 수정 시각·시스템 시계(디스크 위생 — 업무 시각 아님) |
| I-33 | (2-E) 결정적 시나리오는 설정 규칙이다(가상 VAN의 카드별 동작처럼 난수 없음): 레코드 = 거래 ID 또는 승인액(Demo에서 금액으로 고른다), 파일·SFTP = 파일 ID. SFTP 장애 `times`는 프로세스 안 계수(재기동하면 다시 센다 — Demo). 시각은 Simulator 시계(시험 = 가상 시계). 같은 종류 규칙은 처음 맞는 하나만 | 카드 카탈로그(인증 모듈 golden)를 바꾸지 않으려고 카드 참조 방식은 택하지 않음 |
| I-34 | (2-D rev 0.6 — 리뷰 MJ-1, **rev 0.8 갱신 — C-9**) 업로드 임시명은 시도별 `.{id}.dat.{attemptNo}.part`(n = attemptNo, 1..999999 — 넘으면 접속하지 않고 NOT_DELIVERED(INTERNAL_ERROR)). 늦게 깨어난 시도는 자기 n의 임시 파일(자기가 끝까지 쓰고 핸들을 닫은 봉인 바이트)만 rename할 수 있어, 이어받은 시도가 쓰는 중인 바이트를 게시할 수 없다(AQ-T20). 보조 장치로 .part 쓰기·rename 직전 `now + 여유 < lease 만료`가 아니면 rename하지 않는다(NOT_DELIVERED(LEASE_EXHAUSTED)) — 여유 = io-timeout + `clock-skew-allowance`(필수, 0..PT1M), lease 하한 = `AcquiringTransportSettings.minimumLease`(접속 2회 × (connect + auth + 채널 열기) + 요청 6회 + 여유 + 시계 차이). **남은 위험(rev 0.8)**: (1) 두 시도가 각자 rename하면 먼저 rename한 쪽이 이기고 다른 쪽은 대상 있음으로 거절된 뒤 되읽기 hash로 판정한다 — 두 바이트 모두 같은 봉인 바이트이므로 금융 의미 차이는 없다. (2) 서버가 part-retention으로 임시 파일을 지운 뒤 rename하면 NO_SUCH_FILE → rename 거절(NOT_DELIVERED(RENAME_REFUSED) — 복귀 상태 규칙 I-25 그대로). (3) 버려진 임시 파일(앞 시도)은 서버 정리에 맡긴다 — inbox 임시 파일 32개 상한에 닿으면 새 쓰기가 거절되어 UPLOAD_FAILED가 반복된다(경보는 2-F 지표 후보). (4) Demo 세션 상한(계정당 4·idle 60s·인증 30s·인증 시도 3회)과의 정합: 한 시도는 접속 2회를 **순서대로**(앞 접속을 닫은 뒤) 쓰고, 인증은 설정 키 하나(시도 1회), io-timeout은 idle 60s보다, connect-timeout(인증 대기)은 30s보다 짧게 둬야 한다 — 서버 고유 값이라 앱이 검사하지 않고 배포 설정(시험: connect 5s·io 10s)으로 지킨다(LIVE Gate §14-13) | 대안: lease 검사만(rev 0.6 — 남은 위험이 커서 C-9로 대체) |
| I-35 | (2-D rev 0.6 — 리뷰 m-5) lease가 지나 이어받힌 시도의 결과 TX는 상태를 바꾸지 않고 결과 표에 `outcome = SUPERSEDED`, `observed` = 그 시도가 본 결과, `resulting_state` = 그때 현재 상태(TRANSMITTING 허용)를 append한다(V9). 같은 순간 flush 경합(OptimisticLock)이면 새 TX에서 같은 행을 남기고 실패해도 경고만 | 감사 증거 |
| I-36 | (2-D rev 0.6 — 리뷰 m-1·m-3·NIT) 채널 밖 예외는 접속 전이면 NOT_DELIVERED(CONNECT_FAILED), 접속 뒤면 TRANSMISSION_UNKNOWN(INTERNAL_ERROR). `transmitPending`은 파일별 예외를 ERROR 결과로 바꾸고 계속한다. 점유·결과 커밋 결과 모름은 ERROR(CLAIM_COMMIT_OUTCOME_UNKNOWN)·OUTCOME_UNKNOWN(COMMIT_OUTCOME_UNKNOWN) — 상태는 DB가 말하고 TRANSMITTING이면 lease 만료 뒤 확인부터 회복(AQ-T18). LIVE 파일 ID는 조회·접속 전에 LIVE_BLOCKED(D-ACQ-008 이중 방어 — 접속 설정 키는 매입사 코드 단위 유지). 활성 TX 안 호출은 IllegalStateException | fail-closed |
| I-37 | (2-D rev 0.6 — 리뷰 m-2) SshClient 명시 구성: `HostConfigEntryResolver.EMPTY`(~/.ssh/config 무시), `KeyIdentityProvider.EMPTY_KEYS_PROVIDER`(기본 키 위치 무시 — 설정 키 하나만), KEX ECDH P-256/384/521, 암호 AES-GCM/CTR, MAC SHA-2(ETM 우선), host key 서명 ECDSA·RSA-SHA2, SFTP 채널 열기 timeout = io-timeout, `org.apache.sshd` 로그 WARN. 키 파일은 기동 때 파싱(형식 오류·암호 키·0개/2개 이상 → 기동 실패) | Demo allowlist — 운영 정책은 LIVE Gate |
| I-38 | (2-E, 옛 I-34) 설정 fail-closed: 매입사 0개 = SFTP 포트를 열지 않고 Worker도 시작하지 않는다(기본). 매입사가 있으면 SFTP·root·주기·지연이 모두 필수, host 개인키는 소유자만 읽기, 허용 공개키 1~2개. `.env.example`은 변수 이름을 **주석**으로만 둔다(빈 값이 "설정 있음"으로 읽혀 기동이 거부되지 않게) | D-ACQ-006 |
| I-39 | (2-E, 옛 I-35) FINANCIAL ↔ Simulator 실제 프로세스 왕복(E2E)은 이번 범위가 아니다(C-6: 2-F 뒤 harness/e2e). 대신 Simulator 시험(AS-3)이 FINANCIAL 매입 파일 golden 바이트를 실제 SFTP로 받아 ACK·결과 golden과 같은 바이트를 게시함을 확인한다(양쪽이 같은 golden에 묶임) | 격리 규칙(I-27) |
| I-40 | (2-E, 옛 I-36) VAN 원장 대조 읽기 색인(V11: `van_ledger_entry`·`van_cancel_entry`의 (client_id, environment, provider_transaction_id)) — 기존 VAN 표 ALTER(색인만, 데이터·제약 변경 없음)를 새 표 migration과 분리 | V3 관례 |
| I-41 | (2-E rev 0.7 — 리뷰 m-1) SFTP 서버 강화: 알고리즘 allowlist를 FINANCIAL 클라이언트(I-37)와 같은 집합으로 고정(KEX ECDH P-256/384/521, 암호 AES-GCM/CTR, MAC SHA-2 ETM 우선, host key 서명 ECDSA·RSA-SHA2 — chacha20·CBC·SHA-1·DSA 없음). idle 60초, 인증 30초, 인증 요청 3회, 계정당 동시 세션 4개(Demo 상수 — 운영값 LIVE Gate) | 두 쪽 목록이 같아야 협상이 결정적이다 |
| I-42 | (2-E rev 0.7 — 리뷰 m-1) 이미 판정한 inbox 파일은 같은 (이름, 크기, 수정 시각)이면 다시 읽어 hash하지 않는다(프로세스 안 표 — 재기동 뒤 한 번 다시 hash, 같은 내용이면 기록 없음). 크기·수정 시각을 그대로 둔 SFTP 밖 내용 바꿔치기는 재기동 전까지 늦게 드러난다(받는 쪽 근거는 ACK hash — 한계 기록) | 16 MiB 파일을 회차마다 다시 읽지 않는다 |
| I-43 | (2-E rev 0.7 — 리뷰 m-4·n-3) Worker 실패 격리: 파일 하나의 수신 실패·작업 하나의 게시 실패는 그것만 다음 회차로 넘기고(error — 매입사·파일 ID·예외 종류만) 나머지 수신·게시를 계속한다. 덮어쓰기를 거부한 게시 작업은 10분(가상 시계) 동안 묶음에서 빼고 error 로그는 처음 한 번만 | 한 파일이 매입사 전체·게시를 멈추지 않게 |
| I-44 | (2-E rev 0.7 — 리뷰 m-7·m-6) 허용 공개키 파일과 root-dir은 그룹·기타 쓰기 권한이 없어야 기동한다(남이 허용 키를 더하거나 chroot를 바꿔치우지 못하게). 허용 공개키는 매입사마다 파일 1~2개, 그 안 키도 모두 합쳐 1~2개(SFTP 서버 기동 때 셈) | D-ACQ-006 |
| I-45 | (2-F) 수신 축 RECEIPT_CONFLICT = "기관이 이 파일 ID로 받은 내용이 봉인과 다르다"는 **격리 요약**: RECEIPT_CONFLICT ACK, hash·크기가 다른 ACK·결과(I-8), RECEIVED ACK의 건수 ≠ 봉인 건수, 서로 모순된 ACK(RECEIVED ↔ FILE_REJECTED)가 만든다. 한 번 격리되면 그 파일의 뒤 응답은 자동 반영하지 않고 격리 기록만(AFTER_CONFLICT — 응답 바이트 보존, 해소 입력은 ACQ-ADMIN-001 후보). 결과 줄이 봉인 레코드(순번·거래 ID·종류·금액)와 다르면 그 결과 파일 전체를 격리하고 수신 축은 그대로(RECORD_MISMATCH). 문법·트레일러 hash·합계 위반 = FORMAT_INVALID, 이름 ↔ 내용 파일 ID·순번 불일치 = NAME_MISMATCH, FINANCIAL에 없는 파일 = UNKNOWN_FILE(file_id NULL), 같은 이름 다른 hash = NAME_REUSED. 같은 파일 안 처리 순서 = ACK(순번) → 결과(회차) — 같은 회차에 ACK 02(충돌)와 결과가 함께 보이면 결과도 격리된다(순서 의존 — SFTP 경로로는 RECEIPT_CONFLICT가 생기지 않으므로 Demo에서는 도달하지 않는다, I-29) | 기준선 §5.4 "같은 ID 다른 내용 격리", fail-closed. 대안: 격리 뒤에도 봉인 hash 일치 결과는 반영(택하지 않음 — 운영자 확인 전 자동 확정 없음) |
| I-46 | (2-F) 레코드 결과 "같은 결과" = (결과, 사유 코드, 출처 종류)가 모두 같다 → 중복(행 없음). 결과가 같아도 REJECTED 사유가 다르면 상충(CONFLICT 행 — fail-closed). CONFLICT 뒤에는 행을 더 쓰지 않는다(격리 유지, 응답 기록이 근거). 파일 전체 거절(ACK FILE_REJECTED)의 레코드 종결은 REJECTED 행(출처 FILE_REJECTED, 사유 = ACK 사유)으로 쓴다 — 레코드 결과 값 집합은 사용자 지정 4값(ACCEPTED·REJECTED·UNKNOWN·CONFLICT) 그대로. ACCEPTED 인정 시각(recognized_at) = FINANCIAL이 그 결과 파일을 반영한 시각(D-ACQ-010 "수신 시각"), 기관 시각(producedAt·receivedAt)은 응답 기록 institution_at에 따로 둔다. 늦은 결과로 모든 레코드가 결정되면 결과 축 DEADLINE_PASSED → COMPLETE(UNKNOWN 행은 보존) | D-ACQ-010 "상충 격리"의 구체화 |
| I-47 | (2-F — 리뷰 NIT-3) 전송 후 취소의 CANCEL 판정에도 거래 단위 대기를 적용한다: LIVE·마감 뒤·보류(취소 자신 CANCELLATION_HELD, 같은 Payment PAYMENT_HELD)·KRW 외·원거래와 어긋난 값(통화·원 기관 거래번호·MID·원 SALE 레코드 모양·누적 금액: 원 SALE 상계액 + 이미 봉인한 CANCEL + 이 취소 ≤ 원승인액)·파일 v1 표현 불가(UNREPRESENTABLE)는 그 취소만 대기(기록 없음, error 로그 — 거래 ID만), lane은 계속한다. 보류·비정합은 원 SALE 결과보다 **먼저** 본다(보류 중인 취소를 "취소 매입 불요"로 종결하지 않는다). 원 SALE이 UNKNOWN·CONFLICT면 대사(error 로그 + 지표 `cancel.reconcile`, 기록 없음 — 늦은 결과로 ACCEPTED가 되면 다음 봉인이 CANCEL을 넣는다) | D-ACQ-009 + NIT-3 |
| I-48 | (2-F) CANCEL 레코드 값: 승인번호 칸 = 원승인 승인번호(취소 승인번호는 VAN이 주지 않을 수 있다 — 투영 cancel_approval_number NULL 허용, golden vector 3과 같은 값, Simulator는 대조하지 않음 — I-31), 승인액 칸 = 원승인액, 상계 0, 레코드 금액 = 취소액, 기관 거래일·사건 시각 = 취소 값, 할부·카드종류·발급사·MID = 원승인 값, generation 0. 파일 안 순서 = SALE(승인 시각, 거래 ID) 뒤 CANCEL(취소 시각, 거래 ID). 후보 = 원승인이 이 lane이고 `cancelled_at ≤ 마감`이며 상계·CANCEL 레코드·"취소 매입 불요" 기록이 없는 취소 중 원승인의 첫 매입 SALE membership이 있는 것 | 가역 Demo 계약(파일 v1 — golden 불변) |
| I-49 | (2-F) 결과 마감 기준 = 전송 완료 시각(transmitted_at) + `results.deadline`(Demo — LIVE Gate). 전송이 끝나지 않은 파일(SEALED·TRANSMISSION_UNKNOWN·격리)은 결과 마감 대상이 아니다(전송 축 지표·대사가 맡는다). ACK·결과 hash 일치로 TRANSMITTED가 된 파일은 그 응답 반영 시각이 전송 완료 시각이다. 마감 설정이 없으면 UNKNOWN을 만들지 않는다(fail-closed) | 대안: 봉인 시각 기준(전송 지연이 결과 마감을 잡아먹는다 — 택하지 않음) |
| I-50 | (2-G, **rev 1.1 — D-ACQ-014 반영**) 재매입 승인 대상 = 현재 상태 REJECTED·FILE_REJECTED 레코드의 **가장 높은 generation**(그 거래·종류의 레코드·승인 기록 모두에서)이고, 그 거래·종류의 **어느 세대도 ACCEPTED가 아닐 것**(ALREADY_ACCEPTED). UNKNOWN은 대사 미매입 확정 근거가 필요하다(I-55 — 지금은 거절). 대상 버전 = (generation, 상태) — 명령이 본 값과 지금 값이 다르면 TARGET_CHANGED(D-ADMIN-003). 승인 뒤 재검사 `revalidate`: 어느 세대든 ACCEPTED가 됐거나(늦은 ACCEPTED), 상태·세대가 승인 때와 다르거나, 이 승인 위에 다른 세대가 생기면 TARGET_CHANGED — ACQ-ADMIN-001 실행 직전에 부른다. **정정(리뷰 m-3)**: rev 1.0은 UNIQUE(거래, 종류, 새 세대)가 "레코드 표 UNIQUE와 함께 이중 매입을 막는다"고 적었으나, 두 UNIQUE는 **같은 세대**의 중복만 막는다 — 서로 다른 세대가 둘 다 ACCEPTED되는 것은 V14 PK(거래, 종류)와 앱 규칙(I-56)이 막는다. 요청자 ≠ 승인자는 Domain 규칙 + DB CHECK 이중. 근거·사유 코드 필수, 채널은 ADMIN 서명 채널만. 같은 승인 ID 재전송은 대상·사람·대상 버전·사유·근거가 모두 같을 때만 같은 명령(리뷰 n-5) | D-ACQ-011·013·014·D-ADMIN-007 |
| I-51 | (2-G) "입력 경로 전 재매입 실행 불가"를 세 겹으로 고정: ① 서명 채널 운영 구현 `UnavailableReacquisitionApprovalVerifier`는 언제나 false(CHANNEL_NOT_VERIFIED — 아무것도 쓰지 않음), ② runtime 계정은 승인 표에 **SELECT만**(V13 — 검증을 우회해도 INSERT 1142), ③ 실행 Use Case `ExecuteReacquisitionUseCase`는 언제나 INPUT_PATH_NOT_AVAILABLE이고 봉인 서비스에는 generation ≥ 1 레코드를 만드는 경로가 없다. 기록·검사 코드(규칙·Store·서비스)는 있으나 운영에서 실행되지 않는다 — ACQ-ADMIN-001이 채널·INSERT 권한·실행을 함께 연다 | 최소 권한(D-FFR-002). 대안: INSERT 권한을 지금 열고 앱 검증만(운영 경로 없는 권한 — 택하지 않음) |
| I-52 | (2-F) 스케줄러 = `@Scheduled`(fixed-delay, 첫 회차도 같은 지연) + 회차마다 lane별 봉인(Job — 마감 지난 다음 업무일만, 회차당 상한) → `transmitPending`(저장 봉인 바이트 — NIT-1) → 결과 수신 → 결과 마감 → 지표. 기본 비활성(`schedule.enabled` 없으면 Bean 없음), 켜면 모든 값 필수·LIVE lane 기동 거부. 다중 인스턴스 안전성은 분산 잠금이 아니라 각 Use Case의 DB 장치(lane 잠금·AlreadySealed, lease·@Version, 파일 행 FOR UPDATE·UNIQUE, JobRepository의 실행 중 인스턴스 거부)가 지킨다 — 실측 AQ-S3에서 둘째 인스턴스 Job 시작이 `CannotAcquireLockException`으로 끝나고 다음 회차가 수렴 | payment-api 회복 스케줄러 관례. Kafka/Redis 없음(ADR-001) |
| I-53 | (2-F) 대기 사유별 체류 지표는 봉인이 판정한 대기(기록하지 않는 판정)를 프로세스 안 표(`AcquisitionWaitingBoard`)에 lane별로 통째로 바꿔 두고 gauge가 읽는다(건수·가장 오래된 승인·취소 시각부터의 초). 커밋 뒤에만 바꾼다. 한계: 재기동 뒤 첫 봉인 전 비어 있고 인스턴스마다 자기가 봉인한 lane만 안다 — 영속 체류 기록·경보 임계는 LIVE Gate §14-12. DB count 지표는 읽기 TX 하나(JPA native — C-2)로 스케줄러 회차마다 새로 고친다(새로 고치기 전 NaN) | 대안: 판정 결과 표(대기 기록) — 대기를 기록하지 않는 배치1 설계(I-16)를 바꾸는 일이라 택하지 않음 |
| I-54 | (rev 1.1 — D-ACQ-013) 재매입 승인 기록에 금액을 넣지 않는다. 금액은 실행(봉인) TX가 결제별 행 잠금 아래에서 `netAmountMinor(원승인액, 실행 시점까지 받은 모든 취소 — 상계·취소 매입 불요·전송 후 취소, 각 한 번)`로 정한다. 이유: 승인과 실행 사이에 취소가 더 올 수 있어 승인 시점 금액은 결정의 "그때까지"와 어긋날 수 있고, 같은 금액 규칙을 두 번 저장하면 대사 입력이 둘이 된다. 순액 0이면 보낼 금액이 없다 — 처리(제외 기록 등)는 ACQ-ADMIN-001 실행 설계 몫 | 대안: 승인 때 금액 기록 + 실행 때 재대조(택하지 않음) |
| I-55 | (rev 1.1 — D-ACQ-014) UNKNOWN 대상 재매입은 "대사로 미매입 확정" 근거가 있어야 하는데 대사(RECON) 기능·기록이 아직 없어 그 근거를 받을 수단이 없다 → 규칙은 UNKNOWN 대상을 거절한다(UNKNOWN_NOT_RECONCILED). 대상 상태 값 UNKNOWN과 DB CHECK 값은 남긴다(대사 기능 뒤 근거 참조와 함께 연다) | fail-closed |
| I-56 | (rev 1.1 — D-ACQ-014) "거래·종류당 세대를 넘는 ACCEPTED 하나": 결정 ACCEPTED 행을 쓰는 응답 TX가 같은 TX에서 매입 확정 표지(`acquisition_accepted_record` PK(거래, 종류), FK 그 ACCEPTED 결과 행)를 INSERT한다. 결과 반영 때 같은 거래·종류의 **다른** 레코드에 표지가 있으면 들어온 ACCEPTED는 CONFLICT 행(관찰 결과 ACCEPTED — 격리)이다. 동시 경합은 PK 위반 → 재시도 → CONFLICT로 수렴. REJECTED는 영향 없다 | 앱 규칙이 먼저, DB가 이중 방어 |
| I-57 | (rev 1.1 — 리뷰 m-2) 전송 축이 TRANSMISSION_QUARANTINED인 파일의 ACK·결과는 hash가 맞아도 자동 반영하지 않는다 — 격리 사유 TRANSMISSION_QUARANTINED(V14 CHECK), 수신·전송·결과 축·레코드 결과 불변(Aggregate도 그 경우 축 변경을 거부). 다른 격리 판정보다 먼저 본다 | 운영자 확인 전 자동 확정 없음(I-23) |
| I-58 | (rev 1.1 — 리뷰 m-5) 다음 봉인일 조회 결과를 Due·NotDue(마감 전)·NoCutoffPolicy(그 날 정책 버전 없음)·Blocked(LIVE·형식 밖)로 나눈다. 스케줄러는 NoCutoffPolicy를 error 로그 + `seal{outcome=NO_CUTOFF_POLICY}` counter로 드러내고 그 lane 봉인을 멈춘다(정책 추가는 D-ACQ-003 버전 관리 설정 — 소급 변경 금지 I-21) | 조용히 멈추지 않는다 |

## 4. FINANCIAL 매입 모듈 상태 모델

모듈 `com.paycore.financial.acquiring`(ARCHITECTURE-CONVENTIONS §23).

### 4.1 상태 축(INV-12 — 서로 추정하지 않음)

```
매입 대상(원승인 1건 = transaction_id)
  (판정 전) ──봉인 TX: 보류 없음·KRW·TEST·approved ≤ cutoff──▶ SUBMITTED_IN(fileId, seq)   UNIQUE(transaction_id, record_type, generation)
            ├─봉인 TX: 상계 후 순액 0──▶ EXCLUDED(FULLY_CANCELLED_BEFORE_SUBMISSION)   [종결]   PK(transaction_id)
            └─보류·통화·LIVE·마감 후──▶ (기록 없음 — 다음 봉인에서 다시 판정)
  보류 ──해제 기록 RELEASED(D-FFR-003)──▶ 판정 대상 / REVIEW_REQUIRED는 운영자 해소 전까지 대기

파일(AcquisitionFile Aggregate, fileId) — 봉인은 SEALED/NONE/AWAITING으로 INSERT, 2-D부터 전송 축 전이(@Version)
  전송 축:  SEALED ─claim(lease)─▶ TRANSMITTING ─확인 일치/rename OK─▶ TRANSMITTED      [= "매입 요청(전송 완료)" 근거]   (2-D 구현)
                                         ├─rename 응답 유실 + 확인 실패─▶ TRANSMISSION_UNKNOWN ─claim(확인 먼저)─▶ TRANSMITTING
                                         ├─rename 전 실패(확실)─▶ 복귀 상태(SEALED 또는 TRANSMISSION_UNKNOWN — I-25)
                                         ├─최종명 다른 바이트─▶ TRANSMISSION_QUARANTINED [격리 — I-23]
                                         └─lease 만료─▶ (다른 시도가 claim, 복귀 = TRANSMISSION_UNKNOWN; 원래 시도 결과는 SUPERSEDED로 버림)
            SEALED·TRANSMISSION_UNKNOWN ─hash 일치 ACK·결과(ACK_HASH_MATCH·RESULT_HASH_MATCH — I-9, 2-F 구현)─▶ TRANSMITTED (TRANSMITTING이면 응답 반영을 미룸)
  수신 축:  NONE ─ACK(hash·크기·건수 일치)·결과만(RESULT_IMPLIED)─▶ RECEIVED | ─FILE_REJECTED ACK─▶ FILE_REJECTED[레코드 모두 종결 — 재매입 절차]
            어느 상태에서든 ─hash 불일치·RECEIPT_CONFLICT ACK·모순 ACK─▶ RECEIPT_CONFLICT[격리 요약 — I-45, 뒤 응답 자동 반영 없음]   (2-F 구현)
  결과 축:  AWAITING ─모든 레코드 결정─▶ COMPLETE | ─결과 마감(전송 완료 + deadline)─▶ DEADLINE_PASSED(미결 레코드 UNKNOWN) ─늦은 결과로 모두 결정─▶ COMPLETE   (2-F 구현)

레코드 결과(append-only, 2-F 구현 — 레코드마다 행 1..n): ACCEPTED(매입 확정, 인정 시각 = 수신 시각) | REJECTED(그 파일 종결, 출처 결과 파일·파일 거절) | UNKNOWN(결과 마감 — 대사)
   | 늦은 결과 = UNKNOWN 뒤 새 행(D-ACQ-010) | 같은 결과 반복 = 행 없음 | 다른 결과 = CONFLICT 행(격리)
전송 후 취소(membership 확정 뒤 받은 취소 — 상계 기록 없음): CancelCandidateRule(D-ACQ-009, 2-F 봉인 연결) — 거래 단위 대기(I-47) 뒤 원 SALE ACCEPTED → CANCEL 레코드,
   결과 대기 → WAIT, UNKNOWN·CONFLICT → RECONCILE(기록 없음·경보), REJECTED·파일 거절 → "취소 매입 불요" 기록(종결)
재매입(2-G): ADMIN 서명 채널 승인 명령 ─검증·검사(요청자 ≠ 승인자, 대상 REJECTED·UNKNOWN·FILE_REJECTED 최신 generation, 대상 버전)─▶ 승인 기록(generation+1, 원 레코드 참조)
   ─실행(새 버전 파일)─▶ (ACQ-ADMIN-001). 지금은 채널 없음 + runtime INSERT 1142 + 실행 Use Case 거절 → 실행 불가(I-51)
```

### 4.2 봉인 TX(배치1 — `AcquisitionSealService`)

```
REQUIRES_NEW, READ COMMITTED
lane IODKU + FOR UPDATE(같은 lane 봉인 직렬화)
  → lane 마지막 봉인일 + 1 아니면 OUT_OF_ORDER, now < cutoff면 BEFORE_CUTOFF
  → 같은 fileId 있음: 무결성 대조(파일 행·바이트 행 hash·실제 SHA-256·재렌더링) + 저장 (정책 버전, 마감 시각) = 지금 설정? → AlreadySealed | INTEGRITY_VIOLATION | CUTOFF_POLICY_CHANGED
  → (없을 때만) 마감 정책 조회 → NO_CUTOFF_POLICY
  → 후보 키(approved ≤ cutoff, 판정 전) → 결제별 직렬화 행 FOR UPDATE(payment_id 순 한 문장) → 승인·취소·미해제 보류 읽기 → 판정 끝남 재확인(I-20)
  → AcquisitionCandidateRule(Include·Exclude·Wait — PAYMENT_HELD·UNREPRESENTABLE 포함) → 파일 행 + 바이트 + SALE 레코드 + 상계 + 제외 + lane 마지막 봉인일
COMMIT = membership 확정(D-ACQ-012)
```
- 잠금 순서: lane → 결제별 행(payment_id 오름차순). 취소 Inbox TX는 결제별 행 하나만 잠그고 lane을 잡지 않아 순환 없음. 외부 호출 없음.
- 경합 결과(실측 AQ-C2·C3): 취소 TX가 먼저 행을 잡으면 봉인이 기다렸다가 그 취소를 상계, 봉인이 먼저 잡으면 취소가 membership 커밋을 기다렸다가 반영되고 상계되지 않는다(→ 배치2 CANCEL 레코드, 최종 순액 동일).
- 거부(`AcquisitionSealRefused`): LIVE_BLOCKED·OUT_OF_ORDER·BEFORE_CUTOFF·NO_CUTOFF_POLICY·INVALID_LANE·INTEGRITY_VIOLATION·CUTOFF_POLICY_CHANGED.
- lane 잠금 실측(AQ-C4): 첫 봉인이 lane을 잡은 동안 같은 lane 둘째 봉인은 lane 행에서 기다린다. lane IODKU가 기존 행에 이미 배타 행 잠금을 잡으므로 `FOR UPDATE`만 지우는 변형은 동등 변형이고(§17.1 E), lane 잠금 전체(IODKU + FOR UPDATE)를 없애는 변형은 AQ-C4·AQ-C1이 잡는다(§17.1 D). 거부는 아무것도 기록하지 않는다(rollback).

### 4.3 D-FFR-003 자동 해제(배치1 — `paymentfact`)

1. 위치: `PaymentFactInboxService` 원승인 사실 TX(결제별 행 `FOR UPDATE` 아래), 투영·보류 INSERT 뒤 직렬화 행 갱신 전. 같은 Payment에서 이 원승인을 가리키는 ORIGINAL_UNKNOWN 보류 중 해제 기록 없는 것을 보류 id 순으로 모은다.
2. 판정: 순수 `HoldReleaseRule.decide(owner, authorization, authorizationHeld, cancellations, heldIds)` — I-13.
3. 기록: `payment_fact_hold_release` INSERT(RELEASED / REVIEW_REQUIRED + 불일치 이름 목록, decided_by INBOX). 보류 원본 행·취소 투영·`original_known_at_receipt` 불변. 다른 사유 보류는 남아 매입 대기 유지.
4. 계약 영향 없음: 원승인 Inbox outcome·ACK 불변(golden 불변). TX 실패 → 해제 기록도 rollback → PAYMENT 재전송이 처음부터 재판정(HR-3).
5. SWEEP(`SweepOriginalUnknownHoldsUseCase`, 매입 Job 첫 step): 기능 도입 전에 원승인이 도착한 보류를 Payment마다 새 TX로 같은 규칙 판정(decided_by SWEEP). UNIQUE(hold_id)로 멱등.
6. REVIEW_REQUIRED 해소는 "강제 Hold 해제"(D-ADMIN-003 2인 승인)로 보고 경로를 만들지 않았다(후속 ADMIN 작업).

### 4.4 Job(`acquisitionSubmissionJob` — `acquiring.adapter.batch`)

| Step | 호출 | 비고 |
|---|---|---|
| `holdReleaseSweep` | `paymentfact` provided `SweepOriginalUnknownHoldsUseCase.sweep(500)` | 회차당 500 Payment, 남으면 다음 회차 |
| `seal` | `acquiring` provided `SealAcquisitionFileUseCase.seal(acquirerCode, environment, businessDate)` | step context에 fileId·contentSha256·outcome |
| ~~(배치2) `transmit`~~ | → I-22: Job step이 아니라 `TransmitAcquisitionFileUseCase`(§4.5) | |

식별 JobParameters: `acquirerCode`·`environment`·`businessDate`. `spring.batch.job.enabled=false` 유지. (2-F) 스케줄러 `AcquisitionScheduler`가 마감이 지난 다음 업무일로 이 Job을 시작한다(기본 비활성 — I-52). 재시작: 실패 step부터 같은 JobInstance로 재실행(AQ-J2 실측 — OUT_OF_ORDER로 실패한 Job이 앞 업무일 봉인 뒤 재시작으로 완료).

### 4.5 SFTP 전송(2-D — `AcquisitionTransmissionService`)

```
TX1 (REQUIRES_NEW, READ COMMITTED): 파일 읽기 → 저장 바이트 무결성(재렌더링 대조 — 봉인 재호출 없음, NIT-1) → claimTransmission(now, now+lease)
     → update(@Version: SEALED|UNKNOWN|만료 TRANSMITTING → TRANSMITTING) + acquisition_transmission_attempt INSERT(복귀 상태·hash·lease)
SFTP (TX 밖, 잠금 없음): 접속(host key 지문 대조 → 공개키 인증) → 최종명 크기 → 있으면 되읽기 hash 대조(일치 = 전달, 다름 = 격리)
     → 없으면 .{id}.dat.{attemptNo}.part 쓰기(핸들 닫음) → rename(그 임시명 → {id}.dat — rev 0.8 C-9) → 새 접속에서 최종명 되읽기 hash 대조(I-24 판정)
TX2: 파일 다시 읽기 → version ≠ 점유 뒤 version 또는 TRANSMITTING 아님 → SUPERSEDED(아무것도 쓰지 않음)
     → completeTransmission → update(@Version) + acquisition_transmission_outcome INSERT(결과·근거·사유·결과 상태)
```
- 결과(`TransmissionResult.Kind`): TRANSMITTED·ALREADY_TRANSMITTED·NOT_DELIVERED·OUTCOME_UNKNOWN·QUARANTINED·IN_FLIGHT·SUPERSEDED·INTEGRITY_VIOLATION·NOT_CONFIGURED·NOT_FOUND.
- 회복: `transmitPending(lane)`가 SEALED·TRANSMISSION_UNKNOWN·lease 지난 TRANSMITTING을 업무일·버전 순으로 다시 부른다(확인이 먼저라 이미 rename된 파일은 다시 쓰지 않는다 — AQ-T4).
- 전송 바이트는 언제나 `acquisition_file_content`의 봉인 바이트이며 파일 ID는 저장 행의 ID다(새 ID 재전송 경로 없음 — I-10).
- (2-F) 저장 행이 계약(문법·불변식·열거값)을 어겨 복원할 수 없으면 점유하지 않고 INTEGRITY_VIOLATION(리뷰 NIT-3 — AQ-I1).

### 4.6 ACK·결과 수신과 결과 마감(2-F — `AcquirerResponseService`·`AcquisitionResultCloseService`)

```
수신: SFTP(TX 밖) outbox 목록 → 계약 이름(ACK.{id}.{nn}.dat·RES.{id}.{nn}.dat, 이 lane만, 점 임시명 무시) → 기록 안 된 (이름, 크기)만 읽기(읽기 중 끊김 = 그 뒤 파일은 다음 회차)
      응답마다 TX(REQUIRES_NEW, READ COMMITTED): 파일 행 FOR UPDATE → (매입사, 이름, SHA-256) 기록 있음? → 이미 처리(멱등) / 같은 이름 다른 hash → NAME_REUSED 격리
        → 파일 없음 → UNKNOWN_FILE 격리 / TRANSMITTING → 미룸(기록 없음) → 해석(문법·트레일러 hash·합계 — 위반 FORMAT_INVALID) → 이름 ↔ 내용 대조
        → AcquirerResponseRule(봉인 hash·크기·건수·레코드 대조) → 격리(수신 축 RECEIPT_CONFLICT 또는 그대로) | 반영(수신 축 + 전송 완료 근거 + RecordResultRule 행 + 결과 축 요약)
        → 응답 처리 기록(바이트 보존)과 함께 커밋
마감: 결과 축 AWAITING·TRANSMITTED·transmitted_at ≤ now − deadline 파일 → 파일마다 TX: FOR UPDATE → 다시 확인 → 결과 없는 레코드 UNKNOWN 행 → DEADLINE_PASSED(모두 결정이면 COMPLETE)
```
- 동시 처리: 파일 행 잠금(유지)이 같은 파일 반영을 직렬화한다. 실측 AQ-R10. **잠금만 지운 변형(§17.7 B2)이 같은 결과에 수렴하는 근거(rev 1.1 정정 — 리뷰 n-1)**: (1) 처리 순서가 결정적이다(파일 ID → ACK → 결과, 회차 순), (2) 같은 (매입사, 이름, hash) 둘째 INSERT는 UNIQUE 색인 잠금에서 첫 TX 커밋을 기다렸다가 1062 → 새 TX 재시도가 처리 기록을 보고 "이미 처리", (3) 같은 레코드에 같은 행 번호를 계산한 두 TX는 결과 행 PK(파일, 순번, 행 번호)에서 충돌 → 재시도가 다시 읽어 판정한다. `@Version`은 이 수렴의 근거가 아니다(rev 1.0 서술 정정). 그래서 B2는 증거가 아니라 한계(잠금이 1차 장치임을 시험이 따로 증명하지 못함 — §10 한계)다.
- (리뷰 n-2) FILE_REJECTED 뒤 결과 파일이 오면: 수신 축은 FILE_REJECTED 그대로, 결과 줄마다 기존 FILE_REJECTED 결정과 다른 출처라 CONFLICT 행(격리)이고 응답 기록은 APPLIED다(RR-5).
- (rev 1.1 — D-ACQ-014·리뷰 m-1) 결정 ACCEPTED와 같은 TX에 매입 확정 표지를 쓰고(I-56), CONFLICT 행을 쓸 때 그 레코드를 원 SALE로 "취소 매입 불요" 판정한 취소가 있으면 error 로그(지표 `cancel.not_required_original_conflict`). 전송 축 격리 파일의 응답은 격리(I-57).
- 실패 격리: 응답 하나의 예기치 않은 실패는 그 응답만 rollback하고 다음 회차(AQ-R11). 자동 재전송은 어디에도 없다(D-ACQ-004 — UNKNOWN은 대사).

### 4.7 전송 후 취소 CANCEL 연결(2-F — `AcquisitionSealService`, D-ACQ-009)

봉인 TX 안(§4.2 잠금 순서 그대로): 후보 키에 전송 후 취소 키(paymentfact provided `cancellationKeys` — 원승인이 이 lane, `cancelled_at ≤ 마감`)를 더하고 판정 끝난 취소(상계·CANCEL 레코드·취소 매입 불요)를 뺀 뒤, 승인 후보와 **같은 한 문장**으로 결제별 행을 잠그고(payment_id 순) 잠금 뒤 판정 끝남을 다시 확인한다. 원 SALE membership과 그 레코드 결과 행으로 원 SALE 상태를 계산해 `CancelCandidateRule`(I-47·I-48)로 판정 → CANCEL 레코드(파일 SALE 뒤)·"취소 매입 불요" 기록(`acquisition_cancel_not_required`)·대사(로그·지표)·대기. 같은 원승인의 여러 취소는 (취소 시각, 거래 ID) 순으로 누적 금액을 더해 본다. 봉인 끝에 상계 금액 DB 대조(`nettingMismatches` — 레코드 상계액 = 상계 기록 합, 제외 승인액 = 상계 합)가 0이 아니면 전체 rollback(INTEGRITY_VIOLATION). 재봉인(AlreadySealed 경로)도 같은 대조를 한다(AQ-I2).

## 5. 파일 계약 v1 (가상 규격 — 실제 카드사 규격 추측 아님)

golden: `apps/financial-batch/src/test/resources/contracts/acquiring-file-v1.golden.json`(harness `test_acquiring_file_golden.py`가 Python 표준 라이브러리로 독립 생성, 3 vector — 정기 2건·빈 날·재매입 버전 + CANCEL 형태). Simulator 자원은 배치2.

| 항목 | 결정에서 바로 나오는 것 | Implementer 해석(가역) | 미결 |
|---|---|---|---|
| 파일명·ID·버전 | 매입사·환경·업무일당 정기 1개(D-ACQ-003), 재매입 = 새 버전(D-ACQ-004·011) | `ACQ.{acquirerCode}.{env}.{yyyyMMdd}.{vvv}`, 최종명 `{id}.dat`, 임시명 `.{id}.dat.{n}.part`(n = 시도 번호 — rev 0.7·0.8 C-9, §5.1), ACK `ACK.{id}.{nn}.dat`(nn = 수신 순번 — I-28), 결과 `RES.{id}.{nn}.dat` | 실제 기관 명명(LIVE Gate) |
| 헤더 | — | `H|1|fileId|senderId|acquirer|env|yyyy-MM-dd|vvv|originalFileId|sealedAt|cutoffPolicyVersion` (11필드) | senderId 실제 값(LIVE Gate) |
| 레코드 | SALE 순액·CANCEL 원거래 참조(D-ACQ-002), merchantRef = MID(D-ACQ-008) | `D|seq|type|txn|origTxn|merchantRef|approvalNo|ptx|origPtx|instDate|eventAt|KRW|approved|netted|record|plan|months|cardType|issuer|generation|reacquisitionOf` (21필드 — rev 0.1에 없던 generation 추가: 해석 시 손실 없음) | 실제 계약번호(PaymentAuthorized v2 — D-ACQ-008 후속) |
| 트레일러 | — | `T|count|saleCount|saleTotal|cancelCount|cancelTotal|net|bodySha256` (8필드) | — |
| 인코딩 | — | I-15 | 실제 기관 인코딩(LIVE Gate) |
| 금액 | 원화 정수(사용자 지정), 순액(D-ACQ-002) | 부호 없는 10진, 앞 0 없음, net만 음수 가능, KRW만(I-5) | — |
| hash·봉인 | 봉인 후 불변·같은 ID 다른 내용 격리(기준선 §5.4) | 봉인 hash = 파일 전체 SHA-256, bodySha256 = H+D 줄. 재시작 대조(I-11) | — |
| ACK·결과 | 수신·결과 분리, UNKNOWN, 늦은 결과 자동 반영·상충 격리(D-ACQ-004·010) | **§5.1 v1(2-E golden — I-28)** | 실제 기관 규격(LIVE Gate) |

### 5.1 ACK·결과 파일 계약 v1 (2-E — 가상 매입사가 게시, 2-F가 읽는다)

golden: `apps/institution-simulator/src/test/resources/contracts/acquiring-ack-result-v1.golden.json`(harness `test_acquiring_ack_result_golden.py`가 Python 표준 라이브러리로 독립 생성한 **독립 형식 vector** — 앱 판정 결과의 사본이 아니라 형식·바이트 계약 예시다(리뷰 n-5). ACK 4·결과 4 vector, 받은 파일 hash·크기는 매입 파일 golden vector에서 계산). financial-batch 사본은 2-F가 TARGETS에 더한다. 매입 파일 golden의 simulator 사본(`acquiring-file-v1.golden.json`)은 같은 바이트이고 `test_acquiring_file_golden.py`가 두 사본을 대조한다.

```
업로드(chroot 안 /inbox): .{fileId}.dat.{n}.part 쓰기(n = 1~999999, 시도 번호, 앞 0 없음 — C-9) → rename {fileId}.dat(짝인 최종명만, 대상 있으면 거부,
      쓰기 핸들이 열려 있으면 거부). 옛 공용 임시명 .{fileId}.dat.part·최종명 쓰기·삭제는 PERMISSION_DENIED. 파일 16 MiB − 1 상한.
게시(chroot 안 /outbox): .{name}.tmp 쓰기 → rename(덮어쓰기 없음). 점으로 시작하는 이름은 게시 중 임시 파일 — 서버는 읽기 거부, 받는 쪽은 무시한다.
ACK   ACK.{fileId}.{nn}.dat     nn = 그 파일 ID의 수신 순번(01 = 첫 수신, 02.. = 같은 ID 다른 내용)
      A|1|fileId|ackSeq|outcome|reasonCode|receivedSha256|receivedBytes|recordCount|receivedAt      (10필드)
      T|bodySha256                                                                                 (A 줄 LF 포함 SHA-256)
      outcome = RECEIVED | FILE_REJECTED | RECEIPT_CONFLICT. reasonCode는 RECEIVED에서만 빈 값, recordCount는 RECEIVED에서만.
      FILE_REJECTED reasonCode = FORMAT_INVALID | RECORD_INVALID | BODY_HASH_MISMATCH | TOTALS_MISMATCH | FILE_NAME_MISMATCH | ACQUIRER_MISMATCH |
                                 ENVIRONMENT_NOT_SUPPORTED | FILE_TOO_LARGE | SCENARIO_FILE_REJECTED,  RECEIPT_CONFLICT reasonCode = CONTENT_MISMATCH
결과  RES.{fileId}.{nn}.dat     nn = 결과 회차 01..99(한 수신의 결과가 여러 파일로 나뉠 수 있다 — 지연·중복·상충)
      H|1|resultFileId|fileId|fileSha256|resultSeq|producedAt                                     (7필드, resultFileId = RES.{fileId}.{nn})
      R|recordSeq|transactionId|SALE/CANCEL|ACCEPTED/REJECTED|reasonCode|recordAmount               (7필드, recordSeq 오름차순, 한 파일 안 중복 없음)
      T|lineCount|acceptedCount|rejectedCount|acceptedSaleTotal|acceptedCancelTotal|bodySha256     (7필드, H·R 줄 SHA-256)
      REJECTED reasonCode(가상 코드, 해석 없이 기록·노출): NO_MATCHING_AUTHORIZATION | AUTHORIZATION_MISMATCH | CURRENCY_MISMATCH | AMOUNT_MISMATCH |
      NETTED_CANCEL_MISMATCH | DUPLICATE_RECORD | ORIGINAL_NOT_ACQUIRED | NO_MATCHING_CANCELLATION | CANCELLATION_MISMATCH | SCENARIO_CONFLICT | 설정 시나리오 사유
공통  US-ASCII, LF, 마지막 LF, BOM·CR·빈 줄 없음, 시각 UTC yyyy-MM-ddTHH:mm:ss.SSSZ, 금액 KRW minor 부호 없는 10진
```
- receivedSha256·fileSha256 = 받은 매입 파일 전체 SHA-256 → FINANCIAL은 봉인 hash와 대조(다르면 I-8 격리). ACK 없이 결과만 와도 수신 근거(I-8 RESULT_IMPLIED — ACK 유실 시나리오).
- 같은 레코드가 두 결과 파일에 같은 결과로 나오면 중복(멱등), 다른 결과로 나오면 상충(D-ACQ-010 격리) — Simulator 시나리오 DUPLICATE_RESULT·CONFLICTING_RESULT가 만든다.
- 레코드 0건 파일 = R 줄 없는 결과 파일 하나. 레코드가 있는데 결과가 끝내 오지 않는 레코드는 결과 마감 뒤 UNKNOWN(2-F).

## 6. JPA·jOOQ·버전 (TECHNOLOGY-AND-PRINCIPLES §2~§5 — 이번 코드에 연결)

- **JPA가 이번 변경에서 하는 일 → 예 → 이유 → 주의**
  - lane 잠금(`JpaAcquisitionLaneStore`): native IODKU로 자리 확보 → `find` + `refresh(PESSIMISTIC_WRITE)`(= `SELECT … FOR UPDATE`) → 변경 감지로 `last_sealed_business_date`·`updated_at` + `@Version`. 예: 같은 날 봉인 두 개가 동시에 오면 둘째는 lane에서 기다렸다가 첫째가 만든 파일을 보고 AlreadySealed(AQ-C1). 이유: 봉인 판정은 여러 표에 걸친 조건(판정 끝남·업무일 순서)이라 행 `@Version`만으로 못 지킨다. 주의: IODKU는 영속성 컨텍스트를 거치지 않으므로 `refresh`로 최신 값을 읽는다. runtime 권한은 그 세 열 UPDATE만(식별 열 1143).
  - 결제별 직렬화 행 잠금(`JpaPaymentFactAcquisitionReadStore`): payment_id 순 native `FOR UPDATE` 한 문장 뒤 JPQL로 처음 읽는다 — 이 TX에서 같은 Entity를 먼저 읽지 않아 1차 캐시 stale 값이 없다(READ COMMITTED 최신 커밋). mutation M2(잠금 제거)를 AQ-C2·C3가 잡는다.
  - 기록 Entity(`JpaAcquisitionFileEntity`(배치1)·`…FileContent`·`…FileRecord`·`…Netting`·`…Exclusion`·`JpaPaymentFactHoldReleaseEntity`): `@Immutable` + 모든 열 updatable=false, `persist` + 즉시 `flush`(UNIQUE·CHECK·FK 위반을 Port 안에서 분류 — flush ≠ commit). 연관(`@ManyToOne`·`@OneToMany`)·cascade 없음 → N+1·지연 로딩·지우는 경로 없음. 레코드 복합 키는 `@IdClass`, lane 키는 `@EmbeddedId`(값 equals).
  - 봉인 바이트는 별도 Entity·표: `@Lob @Basic(LAZY)`는 bytecode enhancement 없이 지켜지지 않아 한 표에 두면 파일 상태를 읽을 때마다 BLOB를 읽는다.
  - **2-D 적용 — 파일 행 `@Immutable` 해제 근거**: 전송 축 전이(D-ACQ-004 3축은 파일 행 열 — V5)는 같은 행을 바꿔야 한다. 그래서 `JpaAcquisitionFileEntity`에서 `@Immutable`을 떼고 **전송 축 열(transmission_state·transmission_lease_until·transmitted_at)·updated_at·version만** updatable로 두었다(식별·봉인·합계·hash·수신·결과 축 열은 updatable=false 유지). runtime 권한도 같은 다섯 열만 UPDATE(V8 — 나머지 1143, DELETE 1142, `FinancialDatabaseAccountSpec` 새 시험). 시도·결과는 별도 `@Immutable` append-only Entity 두 개.
  - **`@Version`(2-D) — 하는 일 → 예 → 이유 → 주의**: 점유 TX가 읽은 version을 결과 TX의 기대 version으로 쓴다. 예: 시도 A가 lease를 잡고 SFTP에서 멈춘 사이 lease가 지나 시도 B가 이어받아 version이 오르면, 늦게 끝난 A의 결과 TX는 다시 읽은 version이 달라 아무것도 쓰지 않는다(SUPERSEDED — AQ-T8). 같은 순간 두 flush가 겹치면 `UPDATE … WHERE version = ?`가 0행 → OptimisticLock → `OptimisticConflict`. 이유: SFTP는 TX 밖 외부 호출이라 행 잠금을 들고 기다릴 수 없다(TECH §3). 주의: 변경 감지는 같은 TX에서 읽은 Entity만 추적하므로 Domain 객체를 다른 TX의 Entity에 덮어쓰지 않는다 — `JpaAcquisitionFileRepository.update`는 이 TX에서 다시 읽은 행의 version이 Domain version과 다르면 저장을 거부한다(stale 덮어쓰기 방지). `@Version` 열은 INSERT 때 0, 전이마다 +1.
  - 순수 Domain(`acquiring.domain`·`paymentfact.domain.HoldReleaseRule`)과 JPA Entity adapter 분리: ArchitectureSpec 19건 통과(`domainIsPure`·계층·영속 위치·Batch 위치·named interface).
- **2-E 적용(institution-simulator `acquirer`) — 하는 일 → 예 → 이유 → 주의**
  - 기관 사실 Entity 4개(`JpaAcquirerReceivedFileEntity`·`…RecordDecision…`·`…ResultLine…`·`…OutboundFile…`): `@Immutable` + updatable=false, `persist` + 즉시 `flush`(`AcquirerPersistenceFailures.flushing` — UNIQUE 1062·version 충돌을 Port 안에서 `DuplicateAcquirerRecord`로 분류, flush ≠ commit). 예: 두 Worker가 같은 파일을 동시에 받으면 UNIQUE(매입사, 파일 ID, 내용 hash)에서 둘째가 1062 → TX를 버리고 다음 회차에 "이미 받음". 이유: 기관이 받은 사실은 PG rollback과 무관하게 남아야 한다(append-only, DB 권한도 SELECT·INSERT). 주의: 연관 매핑 없음(id 값만) — 결과 줄의 거래 ID·금액은 JPQL join으로 읽는다(N+1·cascade 없음). 수신 행 PK는 IDENTITY라 persist 즉시 INSERT되어 같은 TX의 판정·작업 행이 그 id를 FK로 쓴다.
  - Worker 진행 표(`JpaAcquirerWorkItemEntity`): **변경 감지 + `@Version`**. 게시 TX가 같은 TX에서 다시 읽은 행의 version이 호출자가 본 version과 같고 PENDING일 때만 `markPublished` → flush가 `state·published_at·updated_at·version`만 UPDATE(`WHERE version = ?`). 예: 다른 Worker가 먼저 PUBLISHED로 바꿨으면 아무것도 쓰지 않는다(파일은 같은 바이트라 무해). 이유: 파일 rename은 TX 밖이라 행 잠금을 들고 기다리지 않는다. 주의: 식별·계획 열 updatable=false + runtime 열 권한 4열(V12 — 나머지 1143)로 이중 고정.
  - 순수 Domain(`acquirer.domain` — 파일 읽기·판정·계획·렌더)과 JPA Entity adapter 분리: SimulatorArchitectureSpec `domainIsPure`·계층 규칙 + 2-E 새 규칙 2건(acquirer → van은 provided만, 원장 Port 메서드 집합·Entity `@Immutable`).
  - jOOQ: Simulator에는 jOOQ가 없다(CQRS 조회 요구 없음 — 원장 대조는 JPQL 읽기). 버전: 새 의존성은 이미 catalog에 고정된 `apache-sshd-sftp` 2.20.1 재사용(C-1), resolved tree는 TECH-BASELINE §10.
- **2-F·2-G 적용(financial-batch) — 하는 일 → 예 → 이유 → 주의**
  - **파일 행 수신·결과 축 열 updatable**(`JpaAcquisitionFileEntity.applyAxes`): 응답·결과 마감 TX가 같은 Aggregate 행의 receipt_state·result_state를 변경 감지 + `@Version`으로 바꾼다. 예: 결과 파일이 마지막 결정을 넣으면 result_state = COMPLETE, version + 1. 이유: 3축은 파일 행 열(V5 — D-ACQ-004). 주의: 아무 축도 바뀌지 않으면 손대지 않는다(version을 올리지 않아 진행 중 전송 시도의 기대 version을 불필요하게 깨지 않음). runtime 권한도 두 열만 더했다(V11 — 식별·봉인·합계·hash 열 1143 유지).
  - **`lockFile` = `find(…, PESSIMISTIC_WRITE)`**(`SELECT … FOR UPDATE`): 응답 TX가 처리 기록을 읽기 **전에** 파일 행을 잠근다. 예: 두 인스턴스가 같은 결과 파일을 동시에 반영하면 둘째는 잠금에서 기다렸다가 첫째가 커밋한 (이름, hash) 기록을 보고 "이미 처리"(AQ-R10). 이유: 처리 여부 확인과 기록 사이의 경합을 짧은 행 잠금으로 직렬화(외부 호출 없음). 주의: 전송 TX는 잠그지 않고 `@Version`이라, 잠금 중 전송 점유는 version 비교에서 IN_FLIGHT로 끝난다. 열 단위 UPDATE 권한이 lock read를 허용한다(Account spec "lock read null").
  - **기록 Entity 4개**(`JpaAcquisitionResponseEntity`·`…RecordResult…`·`…CancelNotRequired…`·`…ReacquisitionApproval…`): `@Immutable` + updatable=false, persist + 즉시 flush, 연관 매핑 없음. 응답 PK는 IDENTITY(persist 즉시 INSERT → 결과 행이 FK로 씀). 응답 바이트(MEDIUMBLOB)는 같은 행에 두지만 운영 경로가 Entity를 다시 읽지 않는다(처리 여부는 native 스칼라 — BLOB 미조회). 결과 행 복합 키 `@IdClass`(파일, 순번, 행 번호) — 같은 행 번호 두 번째 INSERT는 PK 위반(동시 반영 DB 방어).
  - **저장 행 → Domain 복원 실패의 분류**(`JpaAcquisitionFileRepository.mapped`): 저장 값이 Domain 불변식(문법·열거값)을 어기면 `AcquisitionContractViolation`으로 통일 → 봉인·전송이 INTEGRITY_VIOLATION으로 거부(리뷰 NIT-3, AQ-I1). 주의: `@Transactional(MANDATORY)` Port를 지난 예외는 TX를 rollback-only로 만든다 — 전송 점유는 TX 밖에서 이 예외를 잡아 결과로 바꾼다(커밋 시도 없음).
  - **지표 count**(`JpaAcquisitionBacklogReader`): native 읽기 전용 count(C-2). 레코드 현재 상태를 SQL GROUP BY로 계산 — Domain `RecordResultRule.status`와 같은 정의(UNKNOWN = UNKNOWN 행만 있고 결정·상충 행 없음).
  - 순수 Domain(`AcquirerResponseFormat`·`AcquirerResponseRule`·`RecordResultRule`·`CancelCandidateRule`·`ReacquisitionRule`)과 Entity adapter 분리: ArchitectureSpec 20건 그대로 통과(`domainIsPure`·계층·영속 위치·Batch 위치·sshd 위치).
- **jOOQ(CQRS)**: C-2에 따라 financial-batch에 jOOQ codegen을 도입하지 않았다. 매입 판정은 권위 상태를 잠금 아래 JPA/native로 읽고(TECH §4), 운영 적체 지표는 배치2에서 JPA/native 읽기 전용 count로 만든다. 운영 조회 화면이 생기면 그 작업에서 jOOQ Reader를 다시 검토한다.
- **버전**: 배치1은 새 의존성·버전 없음(Boot 4.1.1 BOM의 Spring Batch 6.0.5·Hibernate 7.4.5·Flyway 그대로). **2-D: `org.apache.sshd:sshd-sftp` 2.20.1 catalog 고정(C-1)** — Boot BOM 미관리, resolved tree `sshd-sftp → sshd-core → sshd-common 2.20.1`(+ slf4j BOM 2.0.18), spring-integration-sftp 미사용(7.1.1 → sshd 2.18.0), 2.20.1 = 2026-10-15 게시 21일 경과, OSV API 0건(양성 대조 2.9.0 3건 검출). 기록: TECH-BASELINE §10, `harness/technology-baseline.json` `nonBomLibraries.apacheSshdSftp`.
- **Kafka/Redis**: 미도입. 매입 효과의 정확성은 로컬 TX·잠금·UNIQUE·봉인 바이트가 지킨다.

## 7. 가상 기관(simulator) 매입사 (2-E 구현 — rev 0.5)

모듈 `com.paycore.simulator.acquirer`(van·authentication·merchant와 같은 계층, PayCore 앱 project 의존 없음 — harness `test_simulator_isolation`, 계약은 golden).

```
FINANCIAL ──SFTP(공개키, host key 고정)──▶ AcquirerSftpServer(sshd 2.20.1)  chroot {root}/{code}: /inbox(.part 쓰기 → rename) /outbox(읽기만)
                                              AcquirerSftpPolicy(SftpFileSystemAccessor) · AcquirerSftpFaults(SftpEventListener — 장애 주입)
AcquirerWorkerScheduler(poll-interval) → RunAcquirerWorkerUseCase.runOnce()  [AcquirerWorkerService, 직렬화]
  수신(파일마다): inbox 최종명 목록·읽기·SHA-256(TX 밖) → TX{ 수신 기록 대조(ReceiptRule) → v1 읽기(AcquisitionFileV1Reader) → 레코드 판정
                 (RecordJudgementRule ← van.application.provided.VanAcquiringEvidenceQuery) → 게시 계획(PublicationPlanner) → 수신·판정·결과 줄·작업 INSERT }
  게시(작업마다): 계획에서 바이트 재생성 → outbox .{name}.tmp + fsync → rename(대상 없을 때만) → TX{ 게시 기록 INSERT + 작업 PUBLISHED(@Version) }
```
- 설정 `paycore.simulator.acquirer`(I-38): `sftp.{bind-host,port,host-key-path}`, `root-dir`, `poll-interval`, `ack-delay`, `result-delay`, `acquirers[]{code, username, authorized-key-paths[], van-client, scenarios[]}`. 키는 파일 경로로만, 로그에 키·지문·경로 없음.
- 결정적 시나리오(I-33): REJECT·WITHHOLD_RESULT·DELAY_RESULT·DUPLICATE_RESULT·CONFLICTING_RESULT(레코드), FILE_REJECT·DROP_ACK·DELAY_ACK(파일), DISCONNECT_DURING_UPLOAD·DROP_RENAME_RESPONSE(SFTP).
- merchantRef는 D-ACQ-008에 따라 MID이고, 기관 측 대조는 VAN 원장의 기관 거래 참조로 한다(I-31).
- Simulator 재시작: 수신·판정·계획·게시 기록은 Simulator DB에, 받은 파일·게시 파일은 root-dir에 남는다 → 재기동 뒤 Worker가 PENDING 작업만 같은 바이트로 게시(AS-9가 "커밋 유실 뒤 같은 바이트 확인"을 본다). SFTP 장애 계수만 프로세스 안 값(I-33).

## 8. 키·host key (D-ACQ-006 — 배치2, rev 0.1 §8.2 유지)

환경변수/파일 경로로만, 기본값 없음, 지문 1~2개(교체 중), 불일치 연결 거부(재시도 안 함·경보), 로그에 키·지문 원문 없음, 시험은 임시 키쌍. 키 알고리즘은 JDK 기본 `ecdsa-sha2-nistp256`부터(Ed25519 추가 의존성 여부는 배치2 확인).

## 9. 실패·회복 표

| 상황 | 동작 | 배치 | 시험 |
|---|---|---|---|
| 봉인 중간 실패(파일·레코드 기록 뒤 커밋 전) | 전체 rollback(파일·바이트·레코드·상계·제외·lane) → 재실행이 한 번 봉인 | 1 | AQ-DB4 |
| 봉인 커밋 뒤 step/Job 실패·재시작 | 같은 봉인 재호출 → 저장 바이트 대조 → AlreadySealed(새 기록 없음) | 1 | AQ-DB2, AQ-J2 |
| 저장 바이트 변조 | INTEGRITY_VIOLATION(전송 금지) | 1 | AQ-DB5 |
| 같은 날 봉인 동시 실행 | lane 잠금 직렬화 → Sealed 1 + AlreadySealed 1 | 1 | AQ-C1 |
| 같은 승인 두 번째 membership | DB UNIQUE 1062(runtime 계정도) | 1 | AQ-C1 And |
| 취소와 봉인 경합 | 결제별 행 잠금 순서대로 상계/비상계(D-ACQ-012) | 1 | AQ-C2·C3 |
| 업무일 건너뜀·마감 전·LIVE | OUT_OF_ORDER·BEFORE_CUTOFF·LIVE_BLOCKED, 기록 없음 | 1 | AQ-DB1 And, AQ-DB3 |
| 원승인 TX 실패(해제 기록 뒤 커밋 전) | 해제 기록도 rollback → 재전송이 한 번 해제 | 1 | HR-3 |
| 원승인 재전송·SWEEP 재실행 | 두 번째 판정 없음(UNIQUE hold_id, 잠금 아래 재조회) | 1 | HR-1 And, HR-6 |
| 원승인·취소 동시 도착 | 결제별 행 직렬화, 수신 시 보류된 취소만 해제 기록 1개 | 1 | HR-7 |
| 파일 v1로 표현할 수 없는 확정 사실 값 | 그 거래만 UNREPRESENTABLE 대기 + error 로그(거래 ID만), lane·다음 날 봉인 계속 | 1 | AQ-D9, AQ-DB6 |
| 같은 Payment의 다른 거래에 미해제 보류 | 그 Payment 모든 후보 PAYMENT_HELD 대기 | 1 | AQ-D4, AQ-DB7a·b |
| 다른 Payment 원승인을 가리키는 취소 보류 | SWEEP 대상 아님(같은 Payment 조건) — 다른 보류 처리를 막지 않음 | 1 | HR-8 |
| 봉인된 날의 마감 정책 소급 변경·삭제 | 재봉인 CUTOFF_POLICY_CHANGED(AlreadySealed 확인이 정책 조회보다 먼저) | 1 | AQ-DB8 |
| 같은 lane 둘째 봉인 | lane 행에서 대기 후 AlreadySealed | 1 | AQ-C4 |
| 정상 전송 | `.part` → rename → 새 접속 되읽기 hash 일치 → TRANSMITTED(RENAMED_AND_VERIFIED), 다시 부르면 ALREADY_TRANSMITTED(새 시도·쓰기 없음) | 2-D | AQ-T1 |
| 업로드 중 끊김(rename 전) | NOT_DELIVERED(UPLOAD_FAILED) → SEALED, 최종명 없음 → 재시도가 같은 ID·같은 바이트로 TRANSMITTED | 2-D | AQ-T2 |
| rename 응답 유실 | 새 접속 확인 일치 → TRANSMITTED(VERIFIED_FINAL_PRESENT), rename 1회 | 2-D | AQ-T3 |
| rename 응답 유실 + 재접속 확인 실패 | TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN) → 회복 sweep이 확인 먼저 → TRANSMITTED, 다시 쓰지 않음 | 2-D | AQ-T4 |
| 같은 ID 다른 내용(기관 최종명) | TRANSMISSION_QUARANTINED, 덮어쓰지 않음, 다시 점유 안 함 | 2-D | AQ-T5 |
| host key 불일치 | 인증 전 거부, NOT_DELIVERED(HOST_KEY_REJECTED) → SEALED, 쓰기 없음 | 2-D | AQ-T6 |
| 전송 설정 없음 | NOT_CONFIGURED, 점유·시도 행 없음(fail-closed) | 2-D | AQ-T7 |
| 시도 중단(lease 보유 중 멈춤) | lease 안 다른 시도 IN_FLIGHT, 만료 뒤 이어받음(복귀 UNKNOWN), 늦은 원래 시도 SUPERSEDED | 2-D | AQ-T8 |
| 저장 봉인 바이트 변조 | 점유 전 INTEGRITY_VIOLATION(시도 행·업로드 없음) | 2-D | AQ-T9 |
| 마감 정책 소급 변경 뒤 전송 | 전송은 봉인을 다시 부르지 않는다(구조 검사 — 서비스 의존에 봉인 Use Case·마감 정책 없음) | 2-D | AQ-T10 |
| 커밋 유실(결과 TX commit 실패·응답 유실) | OUTCOME_UNKNOWN(COMMIT_OUTCOME_UNKNOWN). rollback이면 TRANSMITTING(lease) → 만료 뒤 sweep이 확인 먼저 → TRANSMITTED(다시 쓰지 않음), 커밋됐으면 다음 호출 ALREADY_TRANSMITTED | 2-D | AQ-T18 |
| rename 미수행 + 응답 유실(재확인 없음) | TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN) — SEALED로 돌아가지 않음, UNKNOWN 복귀 시도의 쓰기 실패는 UNKNOWN 유지 | 2-D | AQ-T11 |
| rename 명시 거절 / rename 뒤 없음 / rename 성공 + 재확인 실패 / 키 인증 거부 | NOT_DELIVERED(RENAME_REFUSED) / UNKNOWN(RENAMED_BUT_ABSENT) / TRANSMITTED(RENAMED) / NOT_DELIVERED(AUTHENTICATION_FAILED) | 2-D | AQ-T12..T15 |
| 쓰기 끝낸 시도가 rename 전 멈춤 + 이어받은 시도가 같은 .part 쓰는 중 | 멈춘 시도는 남은 lease 부족으로 rename하지 않음(SUPERSEDED, observed NOT_DELIVERED), 이어받은 시도가 봉인 바이트 게시 | 2-D | AQ-T16 |
| (C-9) 남은 lease 검사를 지난 시도가 rename 안에서 멈춤 + 이어받은 시도가 자기 임시명에 쓰는 중 | 늦은 시도는 자기 `.1.part`(봉인 바이트 전부)만 게시, 이어받은 시도의 rename은 대상 있음 거절 → 되읽기 일치 TRANSMITTED(VERIFIED_FINAL_PRESENT) | 2-D | AQ-T20 |
| (C-9) 임시 파일 정리 뒤 rename(NO_SUCH_FILE)·계약 밖 이름 | NOT_DELIVERED(RENAME_REFUSED) → SEALED, 재시도는 n = 2. 옛 공용 임시명·n 0·앞 0·1000000·최종명 쓰기는 서버가 거부 | 2-D | AQ-T21 |
| 같은 SEALED 파일 동시 점유(실제 DB 경쟁) | 시도 행 1개, 한쪽 TRANSMITTED·한쪽 IN_FLIGHT | 2-D | AQ-T17 |
| LIVE 파일 전송·활성 TX 안 호출 | LIVE_BLOCKED / IllegalStateException | 2-D | AQ-T19 |
| 키 형식 오류·암호 키·권한 열린 키·짧은 lease·시계 차이 미설정 | 기동 실패 | 2-D | AQ-S1..S3 |
| (Simulator) `.part`만 있는 업로드 | 처리하지 않음(rename된 최종명만) | 2-E | AS-3 |
| (Simulator) 같은 파일 재확인·같은 바이트 재업로드 | 새 기록·새 ACK 없음(멱등) | 2-E | AS-4, RC-1 |
| (Simulator) 같은 ID 다른 내용(저장 파일이 SFTP 밖에서 바뀐 경우만 — SFTP로는 생기지 않음, I-29) | RECEIPT_CONFLICT + ACK 02(레코드 처리 없음) | 2-E | AS-4 |
| (Simulator) 시도별 임시명 두 개 | 먼저 rename한 쪽만 최종명, 다른 쪽 rename 거부(대상 있음), 짝 아닌 이름·옛 공용 임시명·잘못된 n 거부 | 2-E rev 0.7 | AS-11, R-6 |
| (Simulator) 쓰기 핸들 열린 채 rename·16 MiB 넘는 쓰기 | 거부 | 2-E rev 0.7 | AS-12 |
| (Simulator) copy-data 확장으로 16 MiB 넘기기 | 거부(쓰기 채널 상한) | 2-E rev 0.9 | AS-17 |
| (Simulator) 임시 파일 상한에서 FINANCIAL 재시도 | 같은 파일 ID의 닫힌 이전 시도 정리 뒤 허용, 다른 파일 ID 임시 파일은 그대로(그래도 상한이면 거부) | 2-E rev 0.9 | AS-18, AS-16 |
| (Simulator) 같은 VAN 승인·취소를 다른 거래 ID로, 상계된 취소의 CANCEL, 다른 원거래 참조의 CANCEL | DUPLICATE_RECORD / NETTED_CANCEL_MISMATCH / CANCELLATION_MISMATCH(+ DB UNIQUE 1062) | 2-E rev 0.7 | AS-10b, RJ-4, Account |
| (Simulator) 파일 하나 읽기 실패 | 그 파일만 다음 회차, 다른 파일 수신·ACK 게시 계속 | 2-E rev 0.7 | AS-13 |
| (Simulator) 크기 초과 저장 파일·순번 99 소진 | FILE_TOO_LARGE ACK / 기록·ACK 없음 | 2-E rev 0.7 | AS-14 |
| (Simulator) 끊긴 업로드 임시 파일 잔여 | inbox당 32개 상한, 보존 시간 지나면 Worker가 삭제 | 2-E rev 0.7 | AS-16 |
| (Simulator) 목록 밖 암호·동시 세션 초과 | 접속·로그인 거부 | 2-E rev 0.7 | AS-1 |
| (Simulator) 형식·hash·합계 위반 | ACK FILE_REJECTED(사유), 판정·결과 없음 | 2-E | AS-5, R-1..R-5 |
| (Simulator) 시나리오: 거절·결과 누락·지연·중복·상충 | 판정 기록은 남기고 게시 계획만 바꿈(누락 = 어느 결과에도 없음, 지연 = 별도 회차, 중복·상충 = 다음 회차) | 2-E | AS-6, PP-1 |
| (Simulator) 시나리오: ACK 유실·지연·파일 전체 거절 | ACK 작업 없음 / 늦은 ACK / FILE_REJECTED(SCENARIO_FILE_REJECTED) | 2-E | AS-7, PP-2 |
| (Simulator) 업로드 중 끊김·rename 응답 유실 | 최종명 없음(재업로드 성공) / 최종명 있음(재접속 확인 가능, 한 번 수신) | 2-E | AS-8 |
| (Simulator) 게시 rename 뒤 DB 커밋 유실 | 다음 회차: 최종명 같은 바이트 → 기록만, 다른 바이트 → 덮어쓰지 않고 PENDING + error | 2-E | AS-9 |
| (Simulator) 같은 거래 두 번째 ACCEPTED(동시 수신 포함) | DUPLICATE_RECORD + DB UNIQUE 1062 | 2-E | AS-10, Account 시험 |
| (Simulator) 권한 확장(원장 UPDATE/DELETE·진행 표 허용 4열 밖 모든 열) | 기동 거부(AcquirerLedgerPrivilegeGuard — information_schema 열 목록 탐침: 진행 표 허용 4열 밖 모든 열 + append-only 4표 모든 열, m-5·재리뷰 m-4) | 2-E | Account 기동 시험 |
| 정상 ACK·결과(ACCEPTED·REJECTED) | 수신 RECEIVED, 결과 행 2, 결과 축 COMPLETE, 응답 기록 2(바이트 보존), 다시 읽어도 새 행 없음 | 2-F | AQ-R1 |
| ACK 유실 + 전송 결과 모름 | 결과 파일만으로 RECEIVED(RESULT_IMPLIED), hash 일치로 TRANSMITTED | 2-F | AQ-R2, RS-4·RS-6 |
| ACK hash ≠ 봉인 hash | 격리(RECEIPT_CONFLICT), 결과 행 없음, 뒤 결과도 격리(AFTER_CONFLICT) | 2-F | AQ-R3, RS-2 |
| 파일 전체 거절(FILE_REJECTED) | 모든 레코드 REJECTED(출처 FILE_REJECTED), 결과 축 COMPLETE, 재매입은 2인 승인만 | 2-F | AQ-R4, RS-3, RR-5 |
| 중복 결과·상충 결과 | 중복 = 행 없음, 상충 = CONFLICT 행(격리), 앞 결정 유지 | 2-F | AQ-R5, RR-2·RR-3 |
| 결과 누락 → 마감 → 늦은 결과 | 마감 전·설정 없음 = 아무것도 안 함, 마감 뒤 UNKNOWN 1회·DEADLINE_PASSED·재전송 없음, 늦은 결과 = 새 행(UNKNOWN 보존)·인정 시각 = 수신 시각·COMPLETE | 2-F | AQ-R6, RR-4 |
| 부분 다운로드·손상·같은 이름 다른 바이트 | FORMAT_INVALID 격리, NAME_REUSED 격리, 반영 없음 | 2-F | AQ-R7, AR-F3 |
| 모르는 파일·점 임시명 | UNKNOWN_FILE 격리(file_id NULL), 임시명 무시 | 2-F | AQ-R8, AR-F1 |
| 전송 시도 진행 중 응답 도착 | 미룸(기록·version 변화 없음), 시도 끝난 뒤 다음 회차 반영 | 2-F | AQ-R9, mutation B |
| 두 인스턴스 동시 수신(실제 DB 경쟁) | 응답마다 한 번만 반영, 다른 쪽은 이미 처리, 오류 0 | 2-F | AQ-R10 |
| 반영 TX 중간 실패(rollback) | 응답 기록·결과 행·수신 축 모두 rollback, 다음 회차 한 번 반영 | 2-F | AQ-R11 |
| outbox 읽기 중 연결 끊김(응답 유실) | 다 읽은 파일만 반영(부분 바이트 없음), 나머지 다음 회차 | 2-F | AQ-R12 |
| 전송 후 취소: 원 SALE ACCEPTED / REJECTED / UNKNOWN / 결과 대기 | CANCEL 레코드 / 취소 매입 불요 기록 / 대사(기록 없음) / 대기. 늦은 결과로 UNKNOWN → ACCEPTED면 다음 봉인이 CANCEL, 같은 취소 두 번 봉인 없음, 재봉인 AlreadySealed | 2-F | AQ-X1, CC-1·CC-2 |
| 전송 후 취소 값이 파일 v1 밖·보류·비정합 | 그 취소만 대기(UNREPRESENTABLE 등), 다른 취소·lane 계속 | 2-F | AQ-X2, CC-3..CC-5 |
| 저장 레코드 행 문법 위반(재시작) | 재봉인·전송 모두 INTEGRITY_VIOLATION(시도 행 없음) | 2-F | AQ-I1 |
| 상계 기록 합 ≠ 레코드 상계액 | 재봉인 INTEGRITY_VIOLATION(바이트는 일치) | 2-F | AQ-I2 |
| 카드 종류가 DB 열 폭 밖(9자 이상) | 그 거래만 UNREPRESENTABLE(봉인 INSERT 1406으로 lane 전체 실패 없음 — NIT-2) | 2-F | CC-7 |
| 스케줄러 기본 설정 / 켬 | Bean 없음 / 봉인(Job) → 전송 → 수신 → 마감 → 지표, 단계 실패 0 | 2-F | AQ-S1·S2 |
| 스케줄러 두 인스턴스 동시 실행 | 업무일당 파일 1, membership 1, 전달 1(둘째 Job 시작은 잠금 경합으로 실패 → 다음 회차 수렴) | 2-F | AQ-S3 |
| 재매입 승인: 서명 채널 없음 / 같은 사람 / 채널 우회 | CHANNEL_NOT_VERIFIED(기록 없음) / SAME_PERSON / runtime INSERT 1142 | 2-G | AQ-G1, RQ-3 |
| 재매입 승인 표 제약·실행 | CHECK(같은 사람·세대 건너뜀·서명 채널 아님·참조 불일치) 3819, 같은 세대 둘째 1062 / 실행 거절, 새 버전·세대 없음 | 2-G | AQ-G2·G3 |
| (rev 1.1) 전송 축 격리 파일에 hash 일치 ACK·결과 | 둘 다 격리(TRANSMISSION_QUARANTINED), 모든 축·결과 불변 | 1.1 | AQ-R13, RS-8 |
| (rev 1.1) 재매입 세대 ACCEPTED 뒤 앞 세대 늦은 ACCEPTED | 앞 세대 = CONFLICT 행(격리), 매입 확정 표지는 하나, DB 둘째 표지 1062 | 1.1 | AQ-R14, RR-8 |
| (rev 1.1) 취소 매입 불요의 원 SALE이 나중에 CONFLICT | backlog count +1(gauge), error 로그 | 1.1 | AQ-X3 |
| (rev 1.1) 다음 업무일에 마감 정책 없음 | 스케줄러 NO_CUTOFF_POLICY counter + error, 봉인 없음 / 마감 전 = NotDue(조용히 대기) | 1.1 | AQ-S4 |
| (rev 1.1) 합계가 long을 넘는 결과 파일 | FORMAT_INVALID(계약 위반 — 산술 예외로 회차마다 실패하지 않음) | 1.1 | AR-F5 |
| (rev 1.1) 재매입: UNKNOWN 대상 / 어느 세대 ACCEPTED / 승인 뒤 늦은 ACCEPTED / 같은 승인 ID 다른 근거 | UNKNOWN_NOT_RECONCILED / ALREADY_ACCEPTED / revalidate TARGET_CHANGED / APPROVAL_ID_CONFLICT | 1.1 | RQ-2·RQ-7·RQ-8, AQ-G2 |

## 10. 8대 원칙별 증거(배치1)

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | SALE 순액 = 승인 − 상계 취소(Domain·DB CHECK 이중), 같은 승인 첫 매입 1회(UNIQUE), 트레일러 합계 = 레코드 합(CHECK·렌더 검증), 제외는 순액 0만(CHECK), 보류·KRW 외·LIVE는 대기, 해제·보류 기록 append-only | AQ-D1..D6, AQ-F1·F3·F4, AQ-DB1, AQ-C1 And, HR-1, PRIV(1142), mutation M3·M4 |
| Transaction Safety | 봉인 = 로컬 TX 하나(전부/전무), 해제 = 원승인 Inbox TX 안, 외부 호출 없음(전송은 배치2에서 TX 밖) | AQ-DB4, HR-3, mutation M1 |
| Idempotency | 봉인 재호출 AlreadySealed(저장 바이트 대조), UNIQUE membership·상계·제외·해제, Job 재시작 수렴 | AQ-DB2, AQ-J2, HR-1 And, HR-6 |
| Concurrency Control | lane `FOR UPDATE` + 결제별 행 `FOR UPDATE`(고정 순서), READ COMMITTED, `@Version`(lane) | AQ-C1..C3, HR-7, mutation M2 |
| Recoverability | 실패 지점별 rollback·재실행, 재시작 무결성 대조, 기능 도입 전 보류 SWEEP | AQ-DB4·DB5, AQ-J2, HR-6 |
| Reconciliation | 판정 기록(membership·상계·제외·해제 REVIEW_REQUIRED)이 대사 입력. 전송·결과·UNKNOWN 대사는 배치2 | AQ-DB1(상계·제외 행), HR-2·4·5 |
| Auditability | 봉인 바이트·hash·마감 정책 버전·sealed_at, 상계 대상(file, seq), 제외 사유·금액, 해제 판정·불일치·경로(INBOX/SWEEP)·시각 | AQ-DB1, HR-1·6 |
| Observability | 구조화 로그: 봉인(fileId·건수·대기 사유별 수·hash), 무결성 위반 error, 대기 warn, REVIEW_REQUIRED warn(불일치 이름만, 금액·키 없음). 지표(gauge·counter)는 배치2(2-F, C-2 JPA count) | 로그 문구 코드 리뷰 대상 — 지표 시험은 배치2 |

**2-D 추가 증거**(전송 축만 — 수신·결과·지표는 2-F 전이라 해당 원칙의 매입 전체 증거는 아직 미완):

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | 전송 바이트 = 저장 봉인 바이트(hash 대조), 같은 ID 다른 내용 격리, "전송 완료"는 되읽기 일치·rename 응답만 | AQ-T1·T5·T9, mutation T-2·T-4 |
| Transaction Safety | SFTP는 TX 밖(점유 TX·결과 TX 분리), 잠금 미보유 | AQ-T8(멈춘 시도 동안 다른 TX 진행), §4.5 |
| Idempotency | 같은 ID 재업로드·확인 먼저, TRANSMITTED 재호출 무시, 시도 PK | AQ-T1 And·T2·T4 |
| Concurrency Control | lease + `@Version` 기대 version, 늦은 시도 SUPERSEDED | AQ-T8, AQ-TD3, mutation T-3 |
| Recoverability | 결과 모름 → 확인 먼저 회복, 복귀 상태 규칙(NOT_FOUND만으로 미수신 단정 안 함) | AQ-T2·T4, AQ-TD4, mutation T-1 |
| Reconciliation | TRANSMISSION_UNKNOWN·RENAMED_BUT_ABSENT·격리는 대사 입력(지표·Case는 2-F/RECON) | AQ-T4·T5 |
| Auditability | append-only 시도(복귀 상태·hash·lease)·결과(근거·사유·결과 상태·시각) | AQ-T1..T8 history 단언, 권한 1142 |
| Observability | error: host key 거부·인증 실패·격리·무결성·RENAMED_BUT_ABSENT, warn: 접속 실패·SUPERSEDED, info: 시도 결과(키·지문·경로 원문 없음) | 코드 리뷰 대상 — 지표 시험은 2-F |

**2-E 추가 증거**(가상 기관 Simulator — PayCore 금융 효과는 없다. 기관 대역의 사실이 결정적·재현 가능하고 PG 회복 시험의 기준이 되는지가 대상):

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | 받은 파일 트레일러 건수·합계·순액·bodySha256 검증, SALE은 VAN 승인액·상계 ≤ VAN 승인 취소 합, CANCEL은 원 SALE ACCEPTED + VAN 취소액, 같은 거래 이중 ACCEPTED 금지(앱 + DB UNIQUE) | R-1..R-5, RJ-1..RJ-3, AS-3·AS-10, Account 1062, mutation M1·M5·M7 |
| Transaction Safety | 수신 = 로컬 TX 하나(수신·판정·결과 줄·작업 전부/전무), 파일 IO는 TX 밖, 게시 기록은 rename 뒤 별도 TX | AS-9(커밋 유실 회복), §7 그림 |
| Idempotency | 같은 ID 같은 내용 무기록, 게시 바이트 결정적(계획 기록), 최종명 같은 바이트면 기록만 | AS-4, AS-9, mutation M4·M6·M11 |
| Concurrency Control | UNIQUE(매입사, 파일, hash)·(매입사, 파일, 순번)·(매입사, 종류, 수락 거래)·(이름), 진행 표 @Version, Worker 회차 직렬화 | Account 1062, mutation M7 |
| Recoverability | 끊긴 업로드 재시도, rename 응답 유실 뒤 최종 파일 확인, 재기동 뒤 PENDING 게시, 덮어쓰기 거부 | AS-8, AS-9 |
| Reconciliation | 결과 누락(UNKNOWN 유도)·지연·중복·상충·RECEIPT_CONFLICT를 결정적으로 만들어 FINANCIAL 대사 경로(2-F)의 입력을 준다 | AS-4·AS-6·AS-7 |
| Auditability | 수신(hash·크기·사유·시각)·판정(사유·VAN 근거 entry id·시나리오)·결과 줄·게시 기록(hash·시각) append-only, runtime UPDATE/DELETE 1142·1143, 권한 확장 기동 거부 | Account 시험, 기동 거부 시험, mutation M10 |
| Observability | info: 수신(파일 ID·판정 수)·게시(이름), warn: 시나리오 장애 주입·이름 밖 파일(1회), error: RECEIPT_CONFLICT·다른 바이트 게시 거부·순번 소진·Worker 회차 실패(예외 클래스만). 키·경로 원문 없음 | 코드 리뷰 대상(지표 없음 — Simulator) |

**2-F·2-G 추가 증거(rev 1.0 — 매입 전체 경로: 봉인 → 전송 → 수신 → 결과·마감 → CANCEL·재매입 기록. 앞 배치 표와 합쳐 8대 원칙 증거가 완성된다)**:

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | "매입 확정" = 레코드 ACCEPTED만(수신 RECEIVED는 확정 아님 — 축 분리 INV-12), 결과 줄은 봉인 레코드(순번·거래 ID·종류·금액)와 같아야 반영, CANCEL은 원 SALE ACCEPTED일 때만이고 누적(상계 + CANCEL) ≤ 원승인액, 상계 기록 합 = 레코드 상계액(DB 대조), 재매입 = generation + 1·원 참조(UNIQUE(거래, 종류, 세대)는 **같은 세대** 중복만 막는다 — rev 1.1 정정), 거래·종류당 세대를 넘는 ACCEPTED 하나(앱 규칙 + V14 PK — D-ACQ-014), 재매입 금액 = 순액(D-ACQ-013 규칙 함수) | AQ-R1·R4·R5, RS-5, CC-1·CC-4, AQ-X1, AQ-I2, RQ-1·RQ-9, AQ-G2, AQ-R14, RR-8, mutation A·B(M-B4·B5) |
| Transaction Safety | 응답 하나 = 로컬 TX 하나(응답 기록·수신·결과 축·결과 행 전부/전무), 결과 마감 = 파일마다 TX, CANCEL·취소 매입 불요 = 봉인 TX 안, SFTP는 TX 밖 | AQ-R11(rollback), AQ-R12(읽기 끊김), §4.6 |
| Idempotency | (매입사, 이름, SHA-256) 처리 기록 UNIQUE, 같은 결과 = 행 없음, 결과 마감 1회, CANCEL·취소 매입 불요 판정 끝남 재확인 + UNIQUE(거래, 종류, 세대)·PK, 재봉인 AlreadySealed(CANCEL 포함) | AQ-R1 And, AQ-R5, AQ-R6, AQ-X1 And, mutation B(M-B1) |
| Concurrency Control | 파일 행 FOR UPDATE(응답·마감), UNIQUE(이름, hash)·결과 행 PK·매입 확정 표지 PK(재시도로 수렴), 전송 중 파일 응답 미룸, 결제별 행 잠금(CANCEL 판정), 스케줄러 다중 인스턴스 = lane 잠금·lease·JobRepository | AQ-R9·R10·R14, AQ-S3, mutation B(M-B2). **한계(rev 1.1 — 리뷰 m-4)**: 잠금 제거 변형 B2는 잡히지 않는다 — 근거는 §4.6(정렬·UNIQUE 대기·행 번호 PK), 잠금의 고유 효과(재시도 없는 직렬화)는 시험이 따로 증명하지 않는다. **매입 확정 표지 PK 경합(서로 다른 세대 파일의 동시 ACCEPTED → 1062 → 재시도 → CONFLICT 수렴)은 코드 경로·순차 시험(AQ-R14)만 있다 — 세대 ≥ 1은 재매입 실행으로만 생겨 지금 도달 불가, 실제 경합 시험은 ACQ-ADMIN-001 완료 조건(재리뷰 R-m1, Coordinator)** |
| Recoverability | 읽기 끊김·반영 실패는 그 응답만 다음 회차, 늦은 결과 자동 반영(UNKNOWN 보존), 전송 결과 모름 → hash 일치 응답으로 TRANSMITTED, 저장 행 계약 위반 = 분류된 거부(INTEGRITY_VIOLATION) | AQ-R2·R6·R11·R12, AQ-I1 |
| Reconciliation | UNKNOWN(결과 마감)·CONFLICT·격리 응답·RECEIPT_CONFLICT·대사 전송 후 취소·상계 불일치를 기록·지표로 노출(자동 해결·재전송 없음), 응답 바이트 보존(재현), 취소 매입 불요 기록이 원 SALE을 가리킴 | AQ-R3·R6·R7, AQ-X1, AQ-M1, AQ-I2 |
| Auditability | 응답 처리 기록(이름·hash·바이트·처분·사유·기관 시각·근거·관찰 시각), 결과 행(출처·응답 id·인정 시각), 취소 매입 불요(원 레코드·업무일), 재매입 승인(요청자·승인자·근거·채널·세대), 모두 append-only + runtime UPDATE/DELETE 1142·승인 표 INSERT 1142 | AQ-R1(바이트 hash), Account spec(append-only 4표·승인 INSERT 1142·SHOW GRANTS), AQ-G1·G2 |
| Observability | Micrometer gauge(전송 결과 모름·격리·UPLOAD_FAILED 반복·수신 충돌·레코드 UNKNOWN·CONFLICT·결과 대기·격리 응답·상계 불일치·대기 사유별 건수·체류 초·대사 취소)·counter(봉인·전송·응답·레코드 결과·마감), label에 ID·금액 없음. error 로그: 격리·UNKNOWN·상충·대사 취소·무결성(파일 ID·이름만) | AQ-M1(delta 정확 일치·label 키 허용 목록), AQ-S2(counter), mutation C(C2·C3) |

Kafka/Redis는 이 축의 어떤 불변식도 대신하지 않는다(미도입 — ADR-001). 위 증거는 모두 FINANCIAL 로컬 TX·행 잠금·UNIQUE·append-only 권한에서 나온다.

## 11. 표·migration·runtime 권한

| 파일 | 표 | runtime 권한(V6) |
|---|---|---|
| `db/migration/financial/V4__payment_fact_hold_release.sql` | `payment_fact_hold_release`(UNIQUE hold_id, FK hold·stream·authorization·cancellation, decision·reasons·decided_by CHECK) | SELECT, INSERT |
| `db/migration/financial/V5__acquisition_submission.sql` | `acquisition_lane` | SELECT, INSERT + 열 UPDATE(last_sealed_business_date, updated_at, version) |
| 〃 | `acquisition_file`(UNIQUE lane·업무일·버전, 합계·상태 CHECK, 재매입 원본 FK) | SELECT, INSERT(상태 열 UPDATE는 배치2) |
| 〃 | `acquisition_file_content`(MEDIUMBLOB + sha256) | SELECT, INSERT |
| 〃 | `acquisition_file_record`(PK file·seq, UNIQUE transaction·type·generation, SALE/CANCEL 모양 CHECK, KRW) | SELECT, INSERT |
| 〃 | `acquisition_netting`(PK 취소, FK (file, seq), 대상 쌍 CHECK) | SELECT, INSERT |
| 〃 | `acquisition_exclusion`(PK 승인, 순액 0 CHECK) | SELECT, INSERT |
| `db/runtime-privilege/financial/V6__acquiring_runtime_privileges.sql` | 위 7표 | DELETE 없음, procedure로만 |
| `db/migration/financial/V7__acquisition_transmission.sql`(2-D) | `acquisition_file` 열 추가(transmission_lease_until·transmitted_at) + 전송 상태 CHECK 5값 + lease·전송 시각 모양 CHECK | (V8) 열 UPDATE: transmission_state·transmission_lease_until·transmitted_at·updated_at·version |
| 〃 | `acquisition_transmission_attempt`(PK file·attempt_no, 복귀 상태·hash·lease CHECK) | SELECT, INSERT |
| 〃 | `acquisition_transmission_outcome`(PK·FK 시도, 결과·근거·사유 모양 CHECK) | SELECT, INSERT |
| `db/runtime-privilege/financial/V8__acquiring_transmission_runtime_privileges.sql`(2-D) | 위 3표 | DELETE 없음. `acquisition_file`에 set_runtime_table_privileges를 다시 부르지 않음(열 권한 회수 방지) |
| `db/migration/financial/V9__acquisition_transmission_superseded.sql`(2-D rev 0.6) | `acquisition_transmission_outcome`: observed 열, outcome SUPERSEDED, 사유 LEASE_EXHAUSTED·INTERNAL_ERROR, SUPERSEDED만 resulting_state TRANSMITTING 허용(CHECK 재정의) | 권한 변화 없음(SELECT, INSERT — V8) |
| `db/migration/financial/V10__acquisition_response.sql`(2-F) | `acquisition_response`(PK id, UNIQUE (매입사, 이름, hash), FK 파일(격리만 NULL 허용), 종류·처분·사유·ACK·근거 모양 CHECK, `content_bytes = LENGTH(content)`) | SELECT, INSERT(V11) |
| 〃 | `acquisition_record_result`(PK (파일, 순번, 행 번호), FK 레코드·응답, 결과·출처·사유 모양 CHECK — DEADLINE ⇔ 응답 없음) | SELECT, INSERT(V11) |
| 〃 | `acquisition_cancel_not_required`(PK 취소, FK 원 SALE 레코드, 금액·KRW CHECK) | SELECT, INSERT(V11) |
| `db/runtime-privilege/financial/V11__acquiring_response_runtime_privileges.sql`(2-F) | `acquisition_file` 열 UPDATE 추가: receipt_state·result_state(V8 다섯 열과 합쳐 일곱 열 — 표 단위 helper 재호출 없음) + 위 3표 | DELETE 없음 |
| `db/migration/financial/V12__acquisition_reacquisition_approval.sql`(2-G) | `acquisition_reacquisition_approval`(PK 승인 ID, UNIQUE (거래, 종류, 새 세대), FK 레코드, CHECK 요청자 ≠ 승인자·새 세대 = 대상 + 1·대상 상태·참조 = `{file}#{seq}`·채널 ADMIN_SIGNED) | **SELECT만**(V13 — I-51). rev 1.1 정정: 이 UNIQUE는 같은 세대 승인 중복만 막는다(세대를 넘는 ACCEPTED는 V14) |
| `db/runtime-privilege/financial/V13__acquiring_reacquisition_runtime_privileges.sql`(2-G) | 위 1표 | INSERT 없음(입력 경로 ACQ-ADMIN-001이 연다), DELETE 없음 |
| `db/migration/financial/V14__acquisition_accepted_record.sql`(rev 1.1) | `acquisition_response` 격리 사유 CHECK에 TRANSMISSION_QUARANTINED 추가(재정의), `acquisition_accepted_record`(PK (거래, 종류) — 세대를 넘는 ACCEPTED 하나, FK ACCEPTED 결과 행, 종류·세대 CHECK) | SELECT, INSERT(V15) |
| `db/runtime-privilege/financial/V15__acquiring_accepted_runtime_privileges.sql`(rev 1.1) | 위 1표 | DELETE 없음 |

V9 이전 Demo 볼륨에는 V10~V13이 처음 적용된다(기존 표 변경은 V11의 열 권한 추가뿐 — 데이터 변경 없음).

**2-E Simulator DB(paycore_simulator — Simulator 전용 MySQL)**:

| 파일 | 표 | runtime 권한(V12) |
|---|---|---|
| `db/migration/simulator/V10__acquirer.sql` | `acquirer_received_file`(UNIQUE (매입사, 파일, 순번)·(매입사, 파일, hash), 결과·순번·모양 CHECK) | SELECT, INSERT |
| 〃 | `acquirer_record_decision`(PK (수신, 순번), FK 수신, UNIQUE (매입사, 종류, accepted_transaction_id) — 이중 ACCEPTED 방어) | SELECT, INSERT |
| 〃 | `acquirer_result_line`(PK (수신, 회차, 레코드), FK 판정) | SELECT, INSERT |
| 〃 | `acquirer_work_item`(UNIQUE (매입사, 이름)·(수신, 종류, 순번), 상태·이름 CHECK) | SELECT, INSERT + 열 UPDATE(state, published_at, updated_at, version) |
| 〃 | `acquirer_outbound_file`(PK (매입사, 이름), UNIQUE 작업) | SELECT, INSERT |
| `db/migration/simulator/V11__van_provider_transaction_index.sql` | VAN 원장·취소 원장 색인(I-40) | (기존 V4·V7 그대로) |
| ~~`V13__acquirer_evidence_acceptance.sql`~~(rev 0.7) → **rev 0.9에서 V10에 합침**(재리뷰 m-1 (b)) | `acquirer_record_decision`의 authorization_entry_id·accepted_evidence_entry_id·netted_cancel_amount_minor(NOT NULL) + UNIQUE(매입사, 종류, accepted_evidence_entry_id) + 근거·상계 모양 CHECK + 원승인 색인은 이제 V10 CREATE TABLE 안에 있다. 근거: 2-E는 미커밋이고 V10~V13이 적용된 볼륨이 없다(Demo Simulator 볼륨 V9 — 2-E 이전 정지, 시험은 새 컨테이너). V10 이상이 적용된 로컬 볼륨이 있으면 Flyway checksum 검증이 기동을 거부하므로 그 Simulator 볼륨을 다시 만든다(.env.example). (a) 백필은 택하지 않았다 — 옛 판정 행에는 승인액이 없어 상계액을 정확히 되살릴 수 없다 | 표 단위 SELECT, INSERT(V12) 그대로 |
| `db/runtime-privilege/simulator/V12__acquirer_runtime_privileges.sql` | 위 5표. DELETE 없음 | `docker/simulator-mysql/init/01-simulator-database.sh`에 열 권한 procedure `grant_runtime_column_update` 추가(PayCore helper와 같은 방어) |

## 12. 시험(배치1 실행 — §17)

| Spec | 건수 | 내용 |
|---|---|---|
| `paymentfact.domain.HoldReleaseRuleSpec` | 7 | HR-D1..D7: MATCH 해제, ORIGINAL_MISMATCH·합계 초과·순번 충돌·원승인 보류·신원 불일치 검토, 프로그래밍 오류 거부 |
| `paymentfact.application.HoldReleaseDbSpec` | 10 → 11 | HR-1..8(HR-8 다른 Payment 원승인 보류는 SWEEP 대상 아님 — m-1): Inbox 해제·보류 원본 불변·재전송/SWEEP 멱등, 불일치 검토, TX rollback 원자성, 합계 초과, 원승인 보류, 기능 이전 보류 SWEEP, 동시 도착 |
| `acquiring.domain.AcquisitionCandidateRuleSpec` | 8 → 11 | AQ-D1..D9: SALE·상계·전액 제외·보류 대기(+PAYMENT_HELD·입력 불변식)·LIVE/마감/통화 대기·비정합 대기·CANCEL 판정(D-ACQ-009)·마감 정책 버전·표현 불가 4종(MJ-1) |
| `acquiring.domain.AcquisitionFileFormatSpec` | 8 | AQ-F1..F6: 순번·합계·hash·왕복·재시작 대조, 빈 날, 변조·CRLF·비ASCII 거부, 레코드 불변식, 봉인 가드, 파일 ID |
| `acquiring.domain.AcquisitionFileGoldenSpec` | 2 | AQ-G1·G2: golden 3 vector 바이트·hash·크기 일치, 해석 왕복 |
| `acquiring.application.AcquisitionSealDbSpec` | 12 → 17 | AQ-DB1..DB5(봉인 경로·바이트·lane·AlreadySealed·다음 날·OUT_OF_ORDER·LIVE, 마감 전, 중간 실패 rollback, 무결성 위반), AQ-DB6(표현 불가 1건 대기·정상 봉인·다음 날 계속), AQ-DB7a·b(PAYMENT_HELD 두 경우), AQ-DB8(정책 소급 변경 3종 거부·같은 정책 AlreadySealed), AQ-C1..C4(동시 봉인 수렴 — 이름을 "수렴"으로 바로잡음·UNIQUE 1062·취소 경합 두 순서·**lane 대기**) |
| `acquiring.adapter.batch.AcquisitionSubmissionJobSpec` | 2 | AQ-J1(두 step 완료·step context), AQ-J2(실패 Job 같은 파라미터 재시작) |
| `FinancialDatabaseAccountSpec` | 19 → 21 | 기대값 **추가**: 새 7표 표·열 권한(SHOW GRANTS·tables_priv·columns_priv 정확 일치), Flyway 버전 1..6, T2 업무 경로 해제 기록 1건, 새 시험 2건(append-only 6표 UPDATE/DELETE/잠금/TRUNCATE 1142, lane 열 1143·IODKU·잠금·DELETE 1142) |
| `MainMigrationLocationSpec` | 3 → 4 | 버전 1..6, payment_fact 표에 hold_release 추가, acquisition 6표 새 시험 |
| `architecture.ArchitectureSpec` | 19 | `batchAdapterUsesOnlyApplication`·`namedInterfacesOnlyOnProvidedPackages` 빈 대상 허용 **끔**(강화) |
| harness `test_acquiring_file_golden.py` | 4 | golden 독립 생성·필드 수·순액/원거래·합계/hash |
| (2-D) `acquiring.domain.AcquisitionTransmissionRuleSpec` | 11 | AQ-TD1..TD5: 점유·lease·복귀 상태, 결과별 상태, lease 만료 이어받기, UNKNOWN 복귀 규칙, 종결 상태 재점유 없음, 모순 복원 거부 |
| (2-D) `acquiring.application.AcquisitionTransmissionDbSpec` | 14 → 26 → 29 | AQ-T1..T21(§9 표) — 실제 MySQL runtime 계정 + 실제 SFTP(시험 SSHD, 임시 ECDSA 키쌍, 장애 주입: 쓰기 중 끊김·rename 뒤 응답 전 끊김·rename 전 끊김·rename 거절·rename 뒤 삭제·로그인 거부·다른 host key, commit 실패·응답 유실 TM)
| (2-D rev 0.6) `acquiring.adapter.integration.AcquiringTransportSettingsSpec` | 7 | AQ-S1..S3: 정상 설정·비밀 미출력·설정 없음, 키 형식 오류·암호 키·권한 열린 키 기동 실패, lease 식·짧은 lease·누락 |
| (2-D) `FinancialDatabaseAccountSpec` | 21 → 22(rev 0.6: Flyway 1..9, acquisition_file lock read 허용 단언 추가) | 기대값 **추가**: acquisition_file 열 UPDATE 5개, 새 2표 SELECT·INSERT, Flyway 1..8, 새 시험(acquisition_file 열 1143·DELETE/TRUNCATE 1142). append-only 시험 대상에서 acquisition_file을 빼고 새 2표를 넣음 — acquisition_file은 새 열 시험이 같은 거부(전송 축 밖 모든 열 1143·DELETE 1142)를 본다 |
| (2-D) `MainMigrationLocationSpec` | 4 | 버전 1..8, 새 2표 |
| (2-E) simulator `acquirer.domain.AcquirerGoldenSpec` | 4 | AG-1 FINANCIAL 매입 파일 golden 3 vector를 simulator 읽기가 그대로 받음, AG-2 시험 작성기 = golden 바이트, AG-3 ACK 4·AG-4 결과 4 vector 이름·바이트 일치 |
| (2-E) simulator `acquirer.domain.AcquisitionFileV1ReaderSpec` | 6 | R-1..R-6: 정상, FORMAT_INVALID 13종, RECORD_INVALID 5종, BODY_HASH·TOTALS, 이름·매입사·환경, 파일명 규칙(rev 0.7: 시도별 임시명·옛 공용 임시명 거부 — C-9에 따른 기대값 변경) |
| (2-E) simulator `acquirer.domain.AcquirerRulesSpec` | 8 → 9 | rev 0.9: RJ-4에 VAN 취소 원승인 행 불일치 사례(n-4);  rev 0.7: RJ-1..3 새 입력(기관 근거·소비), **RJ-4** 기관 근거 이중 매입 6사례;  RC-1·2 수신 멱등·충돌·순번 소진·불변식, RJ-1..3 SALE·CANCEL 판정 순서, PP-1·2 게시 계획(시나리오별 회차·시각, ACK 유실·거절·0건·전부 누락), SC-1 시나리오 모양 |
| (2-E) simulator `acquirer.adapter.integration.AcquirerSettingsSpec` | 4 → 5 | rev 0.7: ST-2에 part-retention 2종, **ST-5** 그룹·기타 쓰기 root·공개키 거부, 공개키 3개(경로·한 파일) 거부;  ST-1 매입사 없음 = 포트 안 엶, ST-2 필수 값 14종 fail-closed, ST-3 host key 파일 오류 메시지에 내용·경로 없음, ST-4 toString에 키 경로 없음 |
| (2-E) simulator `acquirer.AcquirerSftpWorkerSpec` | 10 → 17 → 19 | rev 0.9: **AS-17** copy-data 상한, **AS-18** 상한에서 같은 파일 ID 이전 시도 정리;  rev 0.7: AS-1 암호 allowlist·동시 세션, AS-2 outbox `.part` 쓰기·outbox tmp 읽기·SETSTAT 거부, 시도별 임시명으로 갱신, **AS-10b**(MJ-1 4경우), **AS-11** 시도별 임시명, **AS-12** 열린 핸들 rename·16 MiB, **AS-13** 실패 격리, **AS-14** FILE_TOO_LARGE·Exhausted, **AS-15** 재hash 생략, **AS-16** 임시 파일 상한·정리;  AS-1..AS-10(§9 표) — Testcontainers MySQL runtime 계정 + 실제 SFTP 왕복(시험 `SshClient`·`SftpClient`, 임시 ECDSA 키, 가상 시계). AS-3은 FINANCIAL 매입 파일 golden 바이트를 SFTP로 올려 ACK·결과 golden과 같은 바이트를 게시함을 본다 |
| (2-E) simulator `SimulatorArchitectureSpec` | 16 → 18 | acquirer → van은 provided만·다른 모듈은 acquirer를 모름, 원장 Port 메서드 집합·Entity `@Immutable`·VAN 대조 Port 읽기만 |
| (2-E) simulator `SimulatorDatabaseAccountSpec` | 4 → 5 | rev 0.9: 기동 거부 대상에 append-only 표 열 2개(`acquirer_record_decision.accepted_evidence_entry_id`·`acquirer_received_file.content_bytes`);  rev 0.7: 기동 거부 대상에 진행 표 `receipt_id`·`created_at` 열(m-5), 기관 근거 UNIQUE 1062(MJ-1), 권한 되돌리기를 finally로(연쇄 실패 방지);  기대값 **추가**: 새 5표 SHOW GRANTS 정확 일치(열 UPDATE 4열), 1142 대상 9문장, 기동 거부 4권한, 새 시험(진행 표 열 1143·DELETE 1142·이중 ACCEPTED 1062) |
| (2-E) harness `test_acquiring_ack_result_golden.py` | 4 | ACK·결과 golden 독립 생성·필드 수·모양·합계/hash·받은 파일 hash |
| (2-E) harness `test_acquiring_file_golden.py` | 4 | TARGETS에 simulator 사본 추가(시험 수 그대로) |
| (2-F) `acquiring.domain.AcquirerResponseGoldenSpec` | 3 | AR-G1·G2: ACK 4·결과 4 vector(서로 독립된 형식 예시)를 FINANCIAL 파서가 받고 같은 이름·바이트·hash로 다시 만든다, AR-G3: ACK 받은 hash = 매입 파일 golden 봉인 hash(교차 계약 일관 — 한 흐름 아님) |
| (2-F) `acquiring.domain.AcquirerResponseFormatSpec` | 4 → 5 | AR-F1 이름(점 임시명·순번·문법), AR-F2 ACK 모양·결합 규칙, AR-F3 부분 다운로드·CRLF·BOM·빈 줄, AR-F4 결과 헤더 ID·오름차순·건수·합계 |
| (2-F) `acquiring.domain.AcquirerResponseRuleSpec` | 7 → 8 | RS-1..RS-7: RECEIVED(확정 아님), hash·크기·건수 격리, FILE_REJECTED·모순·격리 뒤, RESULT_IMPLIED, 결과 대조 격리, hash 일치 전송 완료 근거, 축 전이 |
| (2-F) `acquiring.domain.RecordResultRuleSpec` | 8 → 9 | RR-1..RR-7: 결정·인정 시각, 중복(사유 포함), 상충 CONFLICT, 마감 UNKNOWN·늦은 결과(보존), 파일 거절 종결, 결과 축 요약, 불가능한 행 |
| (2-F) `acquiring.domain.CancelCandidateRuleSpec` | 7 | CC-1..CC-7: CANCEL 레코드 값(I-48), 원 SALE 상태별 판정(D-ACQ-009), 거래 단위 대기(NIT-3), 비정합·누적 경계, 표현 불가, SALE 뒤 CANCEL 순서·합계·해석 왕복, 카드 종류 열거값(NIT-2) |
| (2-G) `acquiring.domain.ReacquisitionRuleSpec` | 6 → 9 | RQ-1..RQ-6: 세대 + 1·참조, 대상 상태 3종, 같은 사람(규칙·값 객체), 재매입 불가 상태, 대상 변경·최신 세대, 서명 채널·근거 |
| (2-F) `acquiring.application.AcquirerResponseDbSpec` | 21 → 24 | AQ-R1..R12, AQ-X1·X2, AQ-I1·I2(§9 표) — 실제 MySQL runtime 계정 + 실제 SFTP(시험 SSHD outbox), 실제 경쟁(2 스레드)·rollback(결과 Store 주입 실패)·응답 유실(읽기 끊김 주입) |
| (2-F·2-G) `acquiring.adapter.batch.AcquiringOperationsDbSpec` | 8 → 9 | AQ-M1(지표 delta·label 키), AQ-S1..S3(기본 비활성·설정 검사·한 회차 왕복·두 인스턴스 동시), AQ-G1..G3(서명 채널 없음·같은 사람·INSERT 1142·DB CHECK·실행 거절) |
| (2-F·2-G) `FinancialDatabaseAccountSpec` | 22 → 23 | 기대값 **추가**: acquisition_file 열 UPDATE 7개(receipt_state·result_state 추가), 새 3표 SELECT·INSERT, 승인 표 SELECT, append-only 시험 대상 +4표, Flyway 1..13(rev 1.1: 1..15·`acquisition_accepted_record` SELECT·INSERT·append-only 대상 추가 — 시험 수 그대로), 새 시험(승인 표 INSERT 1142·SELECT 가능) |
| (2-F·2-G) `MainMigrationLocationSpec` | 4 | 버전 1..13, 새 4표(rev 1.1: 1..15, + accepted_record) |
| (rev 1.1) 추가 사례 | +10 | AR-F5(합계 오버플로), RS-8(전송 축 격리), RR-8(세대를 넘는 ACCEPTED), RQ-7..RQ-9(이미 ACCEPTED·재검사·순액) + RQ-2 기대 변경(UNKNOWN 거절 — D-ACQ-014), AQ-R13·R14·X3, AQ-S4(+ AQ-G2 재전송 비교 단언 추가) |
| (2-F) harness `test_acquiring_ack_result_golden.py` | 4 | TARGETS에 financial-batch 사본 추가(시험 수 그대로 — 선행 RED: 사본 없음 FileNotFoundError) |
| (2-F) harness `test_acquiring_e2e_script.py` | 4 | E2E 관찰 스크립트(실행 없음): argv에 비밀 값 없음, 입력 검증, SQL 따옴표 안 값 고정, 시나리오 기대 판정 |

기대값 변경 근거(완화 아님): 위 두 spec은 사용자 결정 D-FFR-002(새 표마다 권한 migration·권한 시험)·D-FFR-003·D-ACQ-002/003/008/012에 따라 표·버전·권한 줄이 **늘었고**, 기존 단언(기존 표 권한·1142/1143·SHOW GRANTS 정확 일치·DELETE 없음)은 그대로다. ArchitectureSpec은 빈 대상 허용을 끄는 강화다. 다른 기존 시험의 단언은 바꾸지 않았다(`PaymentFactFixture`에 기본값 있는 인자 추가만).

Fixture: `PaymentFactFixture`(취소는 원승인에서 파생, `nextAcquirerCode`로 시험별 lane), `AcquisitionFixture`(취소는 후보에서 파생). Fixture Monkey 미사용(Test Fixture Policy — 핵심 규칙 명시 Fixture).

## 13. 사용자 질문 — rev 0.2: 모두 답변됨

| ID | 답(결정) |
|---|---|
| Q-ACQ-07 | D-ACQ-008 — Demo MID + 기관 거래 참조, 실제 계약번호 후속(PaymentAuthorized v2), 그 전 LIVE 매입 차단 |
| Q-ACQ-08 | D-ACQ-009 — 원 SALE ACCEPTED일 때만 CANCEL |
| Q-ACQ-09 | D-ACQ-010 — 늦은 유효 결과 자동 반영·UNKNOWN 보존·수신 시각·상충 격리 |
| Q-ACQ-10 | D-ACQ-011 — 재매입 2인 승인, 입력은 ACQ-ADMIN-001, 그 전 실행 불가 |
| Q-ACQ-11 | D-ACQ-012 — 상계 경계 = membership 확정 시점 |

**rev 1.0 질문 후보 → rev 1.1 답변됨**: Q-2G-1 → **D-ACQ-013**(권장안 채택 — 재매입 순액, 불요 기록 유지), Q-2G-2 → **D-ACQ-014**(대사로 미매입 확인 뒤만, 늦은 ACCEPTED → 승인 무효, 세대를 넘는 ACCEPTED 하나). 아래 표는 당시 질문 원문이다.

| ID | 질문 | 권장안 | 대안 | 영향·막히는 작업 |
|---|---|---|---|---|
| Q-2G-1 | 원 SALE이 거절(REJECTED·파일 거절)돼 전송 후 취소를 "취소 매입 불요"로 종결한 뒤, 그 원 SALE이 **재매입(generation ≥ 1)으로 ACCEPTED**되면 그 취소를 어떻게 다루나 | 재매입 레코드의 금액을 "원승인 − 그때까지 받은 모든 취소(상계 + 취소 매입 불요)"인 순액으로 만들고(재매입 시점 상계), 불요 기록은 그대로 둔다 — 별도 CANCEL 없이 최종 순액이 맞는다 | (b) 재매입 ACCEPTED 뒤 불요 기록을 append-only "재개" 기록으로 무효화하고 CANCEL 레코드를 보낸다 / (c) 재매입은 원 레코드 금액 그대로, 불요 취소는 대사로만 | 재매입 금액·상계 의미(금액 확정성). **2-F·2-G 구현은 막지 않는다**(실행 경로가 없다) — ACQ-ADMIN-001 재매입 실행 구현이 막힌다 |

배치1에서 새 금융·보안 미결은 발견하지 않았다. 확인 권장(사용자 결정 아님, 가역): I-5 변경(KRW 외 통화를 "제외"에서 "대기"로 — 종결 기록을 남기지 않는 보수 쪽), I-16 단일 TX 봉인(Demo 규모 전제).

> Coordinator 기록(rev 1.1 재리뷰 NIT, 2026-11-06): N-1 앞으로 ACCEPTED를 쓰는 모든 경로(ACQ-ADMIN-001·RECON)는 같은 TX에서 `markAccepted`를 써야 한다(표 사이 제약 없음). N-2 표지 FK는 대상 행이 ACCEPTED·같은 세대임을 보장하지 않는다(앱 require). N-3 V14는 rev 1.0 적용 볼륨이 없다는 전제(있으면 표지 backfill 필요). N-4 ACCEPTED 줄마다 표지 PK 조회 — LIVE Gate 5. N-5 불요 충돌 gauge는 관찰 REJECTED CONFLICT도 셈(fail-closed), NO_CUTOFF_POLICY 로그 빈도 제한 미적용. N-6 TRANSMISSION_QUARANTINED로 격리된 응답은 격리 해소 뒤 자동 재반영되지 않음 — 보존 바이트 재반영은 ACQ-ADMIN-001.

## 14. LIVE Gate 항목

1. 실제 매입사 파일 규격·명명·게시 방식 원자성 기관 확인.
2. 실제 기관 계약번호 전달(PaymentAuthorized v2 — D-ACQ-008)과 LIVE 매입 차단 해제 조건, PAN/토큰 요구 시 PCI 검토.
3. 운영 SFTP 키·host key 배포·교체·비밀 저장소·내부망 allowlist·REAL backend 격리.
4. 운영값: 마감 시각·영업일 달력(Q-CALENDAR)·ACK 기한·결과 마감·재시도·lease·보존·경보 임계.
5. 대량 lane(rev 1.1 — 리뷰 n-6: 전송 후 취소 키 `cancellationKeys`도 매 봉인마다 lane의 마감 이하 취소 전체를 읽는다 — watermark·색인 설계 대상): 단일 TX 봉인(I-16)의 잠금 시간·MEDIUMBLOB 한도(I-11)·후보 키 전체 조회(I-18) → chunk 봉인·객체 저장소·watermark.
6. 매입 기한과 늦은 relay.
7. TEST 매입분의 정산 포함 여부, LIVE 경로 개방.
8. 재매입·REVIEW_REQUIRED 해소 ADMIN 경로와 2인 승인(ACQ-ADMIN-001).
9. sshd CVE 재확인·SBOM.
10. 원승인 영구 미도착 보류(ORIGINAL_UNKNOWN 장기 잔존) 대사 경보 기준.
11. (리뷰 NIT 기록) REVIEW_REQUIRED는 `payment_fact_hold_release`의 UNIQUE(hold_id) 때문에 그 보류의 **자동 판정을 종결**한다(같은 보류에 RELEASED를 더 쓸 수 없다). ACQ-ADMIN-001의 운영자 해소는 별도 append-only 해소 기록 표가 필요하고, 그때 매입 봉인 쪽 "미해제 보류" 조회(`JpaPaymentFactAcquisitionReadStore` — 지금은 RELEASED 해제 기록만 본다)도 그 해소 기록을 보도록 바꿔야 한다.
12. 대기(PAYMENT_HELD·UNREPRESENTABLE·UNSUPPORTED_CURRENCY 등) 장기 체류는 지금 로그로만 드러난다 — 체류 지표·경보 임계값.
13. (2-D) TRANSMISSION_QUARANTINED·RENAMED_BUT_ABSENT 해소 절차(운영자 확인·기관 문의 — ADMIN 경로 없음), 실제 기관이 수신 파일을 옮기는 경우의 전송 확인 근거(I-24 전제 — 최종 파일 보존), 암호화 개인키·비밀 저장소 연동, lease·timeout 운영값. (rev 0.6 NIT) 키 파일 **소유자 계정** 일치 검사는 배포 환경 몫(앱은 권한 비트만). TRANSMISSION_UNKNOWN에서 같은 ID·같은 바이트로 재업로드하는 회복은 **기관이 같은 파일 ID 재수신을 중복으로 판정한다는 전제**(Simulator I-29)에 기대며 실제 기관 확인 대상이다. 시도별 임시명(I-34) 서버 규칙 확장.
14. (2-E) 가상 매입사는 Demo 대역이다: ACK·결과 계약 v1(I-28)·레코드 판정 규칙(I-31)·거절 사유 코드는 실제 매입사 규격이 아니다. 실제 기관의 ACK·결과 형식·명명·게시 방식(outbox 보존·이동·삭제)·결과 회차 규칙·재수신(같은 ID) 처리는 기관 확인 대상. Simulator SFTP의 `.part` 잔여 파일 정리·보존 기간, 결과 파일 상한(99회차), Simulator 수신 파일 상한(16 MiB − 1)도 Demo 값.
15. (2-E rev 0.7 — 리뷰 n-6, rev 0.9 보강 — 재리뷰 m-3·m-5) 상계된 취소의 식별(FINANCIAL `acquisition_netting`만 안다 — 레코드에 상계 취소 참조를 싣는 계약 v2 후보), 매입사 간 VAN client 공유, 매입사 귀속 대조 없음: 가상 매입사는 레코드의 VAN 승인이 **그 매입사 몫인지**(승인 때 인증·Route의 acquirerCode) 대조하지 않는다 — VAN 원장에 매입사 코드가 없다. 다른 매입사 몫 승인을 이 매입사가 수락할 수 있다(Demo는 매입사 하나). 실제 기관은 자기 가맹·승인만 받으므로 LIVE에서는 기관 확인. 누적 취소 한도의 다중 인스턴스 경합(I-31)도 같은 항목.
16. (2-F) 결과 마감 시간(`results.deadline` — Demo PT1H 시험·예시 PT3M), 스케줄 주기·lane·첫 업무일·회차 상한, 지표 경보 임계(UNKNOWN·CONFLICT·격리·UPLOAD_FAILED 반복·대기 체류)는 운영값 아님. 실제 기관의 결과 마감·늦은 결과 수용 기간·결과 재발행 규칙 확인.
17. (2-F) 격리(RECEIPT_CONFLICT·RECORD_MISMATCH·NAME_REUSED·UNKNOWN_FILE)·CONFLICT 레코드·대사로 넘긴 전송 후 취소의 해소 입력은 ADMIN 경로 없음(ACQ-ADMIN-001 범위 후보 — 운영자 확인·기관 문의 기록). 응답 바이트는 보존한다.
18. (2-F) outbox 처리 여부는 (이름, 크기)로 다시 읽을지 정한다 — 크기를 그대로 둔 SFTP 밖 내용 바꿔치기는 다시 읽지 않아 드러나지 않는다(받는 쪽 근거 = 처음 받은 바이트 hash 기록 — I-42와 같은 한계). 응답 BLOB 보관량(MEDIUMBLOB, 회차당 한 행)·보존 기간은 LIVE Gate.
19. (2-G, rev 1.1 갱신) 재매입 실행(새 버전 파일 봉인)·ADMIN 서명 채널·runtime INSERT 권한은 ACQ-ADMIN-001. 금액은 D-ACQ-013 순액(`netAmountMinor` — I-54), 실행 직전 `revalidate`(D-ACQ-014 — TARGET_CHANGED), UNKNOWN 대상은 대사 미매입 확정 근거와 함께(I-55). **메모(리뷰 n-4)**: 승인 표는 지금 레코드 FK·(거래, 종류, 새 세대) UNIQUE만 있다 — ACQ-ADMIN-001에서 ADMIN 승인 요청 ID 참조·(파일, 순번, 새 세대) 복합 UNIQUE 등 입력 경로 키를 함께 설계한다.
20. (2-F) 대기 사유별 체류 지표는 프로세스 안 값(I-53) — 다중 인스턴스 합산·영속 체류 기록은 운영 관측 설계. rev 1.1: 체류 초는 새로 고침 때 매입 시계(`AcquiringClock`)로 계산한다(리뷰 n-6).

## 15. 허용 경로 확인

배치1 변경은 모두 `apps/financial-batch/**`, `harness/commands.json`, `harness/tests/**`, `.env.example`, `docs/repository/ACQUIRING-001-*.md`, `docs/repository/ARCHITECTURE-CONVENTIONS.md` 안이다. `docker/mysql/init`(FINANCIAL 권한 procedure)는 바꿀 필요가 없었다(기존 procedure 사용). `docs/repository/TECH-BASELINE.md`·`harness/technology-baseline.json`은 새 의존성이 배치2에 생기므로 배치1에서 바꾸지 않았다.

## 16. 배치2 준비 상태

**rev 0.5 진행(2026-11-05)**: 2-E **구현 완료**(§5.1·§7·§9·§10·§11·§12·§17.3 — Verifier/Reviewer 판정 전). financial-batch 코드는 바꾸지 않았다.
- **rev 0.6**: 2-D 리뷰 반영 완료(§17.4). `TransmissionClaim`·`TransmissionOutcome`은 `domain/TransmissionOutcome.kt` 한 파일에 둔다(작은 sealed 결과 — CONVENTIONS §14 "그 타입만 쓰는 작은 sealed 결과는 같은 파일", NIT 기록). 남은 위험: 시도별 임시명 미적용(I-34).

**재리뷰 NIT 기록(rev 0.9 — Coordinator 지시: n-1·n-2·n-3·n-5·n-7은 기록만, 코드 변경 없음)**: n-4·n-6은 반영(위 I-31·§5). 아래 다섯 항목의 원문은 rev 1.0에서 Coordinator가 전달했다 — **기록만 하고 apps/institution-simulator 코드는 바꾸지 않았다**(처리 계획 = Simulator 후속 작업 후보, 금융 의미 영향 없음 — Demo 대역의 방어 깊이 항목):
- **n-1**: 쓰기 핸들 검사(`AcquirerSftpPolicy`)와 move 사이에 TOCTOU 창이 있다. 같은 계정이 같은 임시명에 동시 쓰기 핸들을 여는 경우만 해당하며, 시도별 이름(C-9)에서는 클라이언트 결함일 때만 발생한다. (FINANCIAL 쪽: 한 시도는 자기 n 임시명만 쓰고 핸들을 닫은 뒤 rename한다 — AQ-T20.)
- **n-2**: 세션 단절 뒤 핸들 정리 시험이 없다. closed 이벤트가 오지 않으면 writers 맵에 Handle 참조가 남는다(메모리 소량). isOpen이 거짓이면 rename을 막지 않는다.
- **n-3**: sweepTemps는 열린 쓰기 핸들을 고려하지 않는다(fail-safe — 지워진 임시 파일 rename은 NO_SUCH_FILE → FINANCIAL NOT_DELIVERED(RENAME_REFUSED) → 다음 시도). 정리 기준이 시스템 시계라 가상 시계 시험과 다르다.
- **n-5**: 인증 전 연결 수에 상한이 없다. 인증 30초 timeout이 완화한다.
- **n-7**: 인증·idle 값, 10분 게시 제외, 디렉터리 fsync는 mutation이나 시험이 없다. 코드 리뷰로만 확인했다.

**2-F에 넘기는 계약(가역 Demo 계약 — golden이 기준)**:
1. 파일 형식: §5.1(ACK·결과 v1). golden `acquiring-ack-result-v1.golden.json`을 financial-batch `src/test/resources/contracts/`에 같은 바이트로 복사하고 harness `test_acquiring_ack_result_golden.py`의 TARGETS에 financial-batch를 더한다(relay golden 관례).
2. outbox 경로·이름: chroot 안 `/outbox`(FINANCIAL `outbox-dir`), 최종명 `ACK.{fileId}.{nn}.dat`·`RES.{fileId}.{nn}.dat`만 읽는다. 점으로 시작하는 이름(`.{name}.tmp` — 게시 중)은 목록에 보일 수 있으나 서버가 읽기를 거부하므로 무시한다. 파일은 지워지지 않고(FINANCIAL은 SFTP로 지울 수도 없다 — 읽기만) 같은 이름은 다시 쓰이지 않는다(덮어쓰기 없음) → 처리 여부는 FINANCIAL이 (이름, hash) 수신 기록으로 판정해야 한다.
3. 판정 근거: ACK `receivedSha256`·결과 `fileSha256` ≠ 봉인 hash → 격리(I-8). ACK 없이 결과만 와도 수신 근거(RESULT_IMPLIED). 같은 레코드가 다른 결과 파일에 같은 결과 = 중복(멱등), 다른 결과 = 상충 격리(D-ACQ-010). ACK `RECEIPT_CONFLICT`(nn ≥ 02)는 같은 파일 ID에 다른 내용이 도착했다는 기관 쪽 사실이다 — SFTP 경로로는 생기지 않는다(최종명 덮어쓰기·삭제 거부 — I-29 rev 0.7 정정). 저장 파일이 SFTP 밖에서 바뀐 경우만이며, 받으면 격리·경보 대상이다.
4. 0건 파일 = R 줄 없는 결과 파일 하나. 결과 회차는 01..99, 회차마다 트레일러 bodySha256으로 부분 다운로드를 검출한다.
5. inbox 경로: `/inbox`, 업로드 임시명 **`.{id}.dat.{attemptNo}.part`**(C-9 — 아래 FINANCIAL 임시명 계약) → rename `{id}.dat`. 되읽기(`read`)·`stat` 허용, 최종명 쓰기·덮어쓰기·삭제·옛 공용 임시명 쓰기는 PERMISSION_DENIED.

**FINANCIAL(2-D 후속)에 넘기는 임시명 계약(C-9 — 서버 쪽은 rev 0.7 구현·시험 AS-11·AS-12·AS-16·R-6)**:
- 이름: `.{fileId}.dat.{n}.part`, n = 시도 번호 `[1-9][0-9]{0,5}`(1~999999, 10진, 앞 0 없음). FINANCIAL `attemptNo`가 999999를 넘으면 서버가 거부한다. 옛 `.{fileId}.dat.part`는 PERMISSION_DENIED — **현재 작업 트리의 FINANCIAL 2-D 코드(`.$finalName.part`)는 이 서버와 맞지 않으므로 E2E 전에 바뀌어야 한다.**
- 쓰기: 쓰기 열기(Write/Create/Truncate, Append 금지) 뒤 요청 끝(offset + 길이) ≤ 16 MiB − 1. 같은 n 임시 파일 다시 쓰기(덮어쓰기)는 허용.
- rename: 그 n 임시명 → 짝 `{fileId}.dat`만. 대상이 이미 있으면 실패(FileAlreadyExists — 다른 시도가 먼저 최종명을 만들었다 → FINANCIAL은 되읽기 hash로 판정, I-24), 같은 계정이 그 임시 파일 쓰기 핸들을 열어 둔 채면 PERMISSION_DENIED(핸들을 닫은 뒤 rename).
- 상한·정리: inbox 한 곳에 임시 파일 32개까지(새 이름 쓰기 거부), 마지막 수정 뒤 `part-retention`(Demo 설정) 지난 임시 파일은 Simulator가 지운다 — rename은 쓰기를 마친 뒤 곧바로 해야 한다(지워진 임시 파일 rename = NO_SUCH_FILE → FINANCIAL 판정표의 "rename 거절").
- 상한과 재시도의 맞물림(재리뷰 m-6): 시도마다 새 n 임시 파일이 생기므로 끊긴 시도가 잦으면 `part-retention` 전에 상한(32)에 닿을 수 있다. 그때 새 시도의 임시 파일 쓰기 열기는 **같은 파일 ID의 닫힌 이전 시도 임시 파일을 먼저 지우고** 다시 센다(늦게 깨어난 그 시도의 rename은 NO_SUCH_FILE — 이미 새 시도가 이어받았다). 열린 쓰기 핸들이 있는 임시 파일·다른 파일 ID의 임시 파일은 지우지 않으며, 그래도 상한이면 PERMISSION_DENIED → FINANCIAL은 그 시도를 NOT_DELIVERED로 끝내고 다음 회차에 다시 시도한다(Worker 정리 뒤 풀린다). 검토한 대안 "열린 핸들 없는 임시 파일을 상한 계산에서 뺀다"는 택하지 않았다 — 닫힌 임시 파일이 디스크를 제한 없이 차지할 수 있다.
- VAN client 공유(재리뷰 m-5): 설정의 매입사들은 같은 `van-client`(가상 VAN 원장 namespace)를 쓸 수 있고 Demo 설정은 모두 같은 PG client다 — 매입사별로 승인을 나누지 않으며 귀속 대조도 없다(LIVE Gate 15). 2-F·E2E의 기대 결과는 "어느 매입사로 보내도 같은 VAN 원장으로 판정"을 전제로 한다.
- 세션: 계정당 동시 세션 4개, idle 60초, 인증 30초·3회, 알고리즘 allowlist = FINANCIAL 클라이언트 목록(I-37·I-41).

남은 범위:
- 2-D 잔여: 결과 TX commit 유실 전용 시험(현재 동작 = TRANSMITTING으로 남아 lease 만료 뒤 확인 회복 — 코드 경로는 있으나 시험 없음), 정식 CVE-SCAN(SBOM + osv-scanner — Verifier). **rev 1.0에서도 미착수**(이번 지시 범위 밖).
- ~~2-E 전체~~ → rev 0.5 구현.
- ~~2-F 전체~~ → **rev 1.0 구현**(§4.6·§4.7·§9·§10·§17.7). Simulator 쪽 2-F 계약 변경은 필요 없었다(golden 바이트 복사만).
- ~~2-G 전체~~ → **rev 1.0 구현**(기록·검사만 — 실행 불가 3겹, I-51). 재매입 실행·ADMIN 채널은 ACQ-ADMIN-001(+ 질문 후보 Q-2G-1).
- E2E: **스크립트 작성 완료, 실행 안 함**(C-6 — Coordinator가 Demo 스택에서 실행). 아래 "E2E 실행 방법".

**E2E 실행 방법(rev 1.0 — Coordinator 실행, GRADLE-CHECK 밖)**: `harness/e2e/acquiring_e2e.py`는 프로세스를 띄우지 않고 띄워진 Demo 스택의 FINANCIAL DB를 runtime 계정으로 **읽기만** 해서 기대 상태가 될 때까지 기다린다(argv 고정 list, DB 비밀번호는 `PAYCORE_FINANCIAL_DB_PASSWORD` → `docker exec -e MYSQL_PWD` — 값은 argv·출력에 없다, 실행 없는 단위 시험 `harness/tests/test_acquiring_e2e_script.py`).
1. Demo MySQL 두 개(`paycore-demo-mysql-1`, `paycore-demo-simulator-mysql-1`)를 켠다. V10~V13(FINANCIAL)·V10~V12(Simulator)가 처음 적용된다(Simulator 볼륨은 `.env.example`의 init 재실행 안내 먼저).
2. 키(저장소 밖, `.env.example` 2-E 절): Simulator host 키·FINANCIAL 클라이언트 키쌍 생성, host key 지문 확인.
3. institution-simulator bootRun: `PAYCORE_SIMULATOR_ACQUIRER_*`(매입사 SIMACQA, van-client = PG client, result-delay 예 PT30S) + 시나리오 두 개 — `SCENARIOS_0`: ACTION=REJECT, APPROVEDAMOUNTMINOR=13013, REASON=DEMO_REJECTED / `SCENARIOS_1`: ACTION=DELAY_RESULT, APPROVEDAMOUNTMINOR=17017, DELAY=PT6M(FINANCIAL 결과 마감보다 길게).
4. financial-batch bootRun: `PAYCORE_FINANCIAL_INBOX_JSON`의 `acquiring`에 `transport`(SIMACQA → Simulator SFTP), `results.deadline`=PT3M, `schedule`(enabled, fixed-delay PT10S, lane SIMACQA/TEST, first-business-date = 시험 업무일) — `.env.example` 예시. payment-api·master-api는 Demo 설정(Relay 켬).
5. 업무일에 SIMACQA로 승인되는 TEST 결제 세 건(승인액 12000·13013·17017)을 Demo 흐름(DEMO-SEED-001 가맹점·결제창)으로 만든다 — VAN 원장에 있어야 ACCEPTED가 난다. 마감(23:00 KST) 뒤 스케줄러가 봉인한다(과거 업무일이면 곧바로).
6. 실행(각각 종료 코드 0 = PASS, 1 = 시간 안에 미도달 — 관찰 요약 JSON, 2 = 전제 미충족):
   - `python3 harness/e2e/acquiring_e2e.py --scenario normal --acquirer SIMACQA --business-date <업무일> --amount 12000`
   - `python3 harness/e2e/acquiring_e2e.py --scenario reject --acquirer SIMACQA --business-date <업무일> --amount 13013`
   - `python3 harness/e2e/acquiring_e2e.py --scenario missing-result --acquirer SIMACQA --business-date <업무일> --amount 17017 --timeout-seconds 1200`
   기대: normal = 전송 TRANSMITTED·수신 RECEIVED·레코드 ACCEPTED 1행 / reject = REJECTED(DEMO_REJECTED) / missing-result = UNKNOWN(DEADLINE) 뒤 ACCEPTED(늦은 결과 — UNKNOWN 행 보존).
7. 끝나면 Demo 컨테이너를 Coordinator 판단으로 정리한다(이 스크립트는 아무것도 쓰거나 지우지 않는다).

이전 계획(rev 0.3):
- 2-D: sshd 2.20.1 catalog 추가(C-1) → dependency tree·supplementary osv 0건 확인 → TECH-BASELINE·technology-baseline.json 기록. 파일 행 상태 전이(`@Immutable` 제거·`@Version`·상태 열 권한 migration), 전송 시도 표.
- 2-E: Simulator `acquirer`, simulator DB V10·V11(열 단위 helper가 simulator init에 없으면 `docker/simulator-mysql/init` 추가), golden 대상에 Simulator 자원 추가.
- 2-F 추가(리뷰 NIT): 대기 사유별 체류 건수·최고 연령 지표(JPA count — C-2)와 경보, 상계 금액 DB 대조(상계 기록 합 = 레코드 netted 금액, 제외 = 승인액 — 대사 입력 무결성 검사).
- 2-F: 수신 파일·레코드 결과 표, D-ACQ-010 늦은 결과, D-ACQ-009 CANCEL 레코드를 봉인에 연결(`CancelRecordRule` 사용), "취소 매입 불요" 기록, JPA count 지표(C-2), 스케줄러.
- 2-G: 재매입 요청·2인 승인 기록 표·검사(D-ACQ-011, D-ADMIN-007 형식) — 입력 경로 없음.
- 선행 결함 없음. 리뷰 지적이 있으면 배치1을 먼저 고친다.

## 17. 실행 기록(Implementer — Verifier 재검증 대상)

환경: WSL Ubuntu(7.6 GiB), JDK 25 toolchain, Gradle wrapper(9.8.0), Docker 29.8.0(Testcontainers mysql:8.4.11). 운영 DB·키·endpoint 미사용. 명령마다 `./gradlew --stop`.

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 07:0x | `./gradlew :apps:financial-batch:compileTestKotlin` | exit 1 — 시험 코드 컴파일 오류 1건(JsonNode `map` 오버로드, AcquisitionFileGoldenSpec) → `toList().map` 수정. main 컴파일은 통과 |
| 07:0x | `:apps:financial-batch:test --tests 'acquiring.domain.*' 'paymentfact.domain.*' ArchitectureSpec` | exit 0, 57건(Candidate 8·Format 8·Golden 2·HoldReleaseRule 7·ProjectionRule 13·Architecture 19), 실패·skip 0 |
| 07:08 | `:apps:financial-batch:test --tests HoldReleaseDbSpec 'acquiring.*' FinancialDatabaseAccountSpec MainMigrationLocationSpec` | exit 0, 69건(Account 21·MainMigration 4·Job 2·SealDb 12·Candidate 8·Format 8·Golden 2·HoldReleaseDb 10), 실패·skip 0 |
| 07:1x | mutation A(M1 Inbox 해제 호출 삭제 + M3 Include 상계액 0 + M5 file_record에 UPDATE·DELETE 부여) → HoldReleaseDb·CandidateRule·SealDb·Account | exit 1, 51건 중 **18 실패**(HR-1·2·3 And·4·5·7, AQ-D2, AQ-DB1 2건·AQ-C2, Account T2·append-only·procedure 2·SHOW GRANTS·표 집합·수렴 2). 복원 후 sha256 일치 |
| 07:1x | mutation B(M2 결제별 행 FOR UPDATE 제거 + M4 UNIQUE(transaction, type, generation) 삭제 + M6 해제 판정이 불일치 무시 + M8 마감 전 거부 삭제) → HoldReleaseRule·SealDb | exit 1, 19건 중 **11 실패**(HR-D2..D6, AQ-DB1 2건·DB3·C1 And(1062)·C2·C3). 복원 후 sha256 일치 |
| 07:13 | `./gradlew :apps:financial-batch:test`(모듈 전체, 복원 뒤) | exit 0, **141건**, 실패·오류·skip 0, 보고서 17개 |
| 07:1x | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran **174**, OK(module-registry 하한·selftest 수 일치 포함) |

- RED: 시험을 구현과 함께 작성했으므로 선행 RED 대신 위 mutation 두 묶음(7개 변형)으로 시험이 결함을 잡는 것을 확인했다(업무 실패 — 컨텍스트·컨테이너 정상 기동, 환경 오류 아님). 잡히지 않은 변형 없음. 시험하지 않은 변형: lane `FOR UPDATE` 제거(파일 PK·UNIQUE가 두 번째 방어라 동등 변형 가능성 — AQ-C1은 재시도 뒤 AlreadySealed로 수렴할 수 있다).
- harness 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3590 → **3642**(+52 = HoldReleaseRule 7 + HoldReleaseDb 10 + CandidateRule 8 + Format 8 + Golden 2 + SealDb 12 + Job 2 + Account +2 + MainMigration +1; financial-batch 89 → 141 실측과 일치). HARNESS-SELFTEST 170 → **174**(+4 golden 시험).
- 전체 GRADLE-CHECK·다른 앱 모듈은 실행하지 않았다(지시 — 메모리). 다른 앱은 financial-batch 코드·FINANCIAL migration을 쓰지 않는다(payment-api는 golden relay 계약만 — 변경 없음). Verifier 몫.

### 17.1 rev 0.3 실행 기록(리뷰 반영)

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 07:3x | `:apps:financial-batch:test --tests 'acquiring.*' HoldReleaseDbSpec 'paymentfact.domain.*' ArchitectureSpec` | exit 1 — 시험 컴파일 경고 1건(`!!` 불필요, -Werror) → 수정 |
| 07:3x | 같은 명령 재실행 | exit 0, 90건(Job 2·SealDb 17·Candidate 11·Format 8·Golden 2·Architecture 19·HoldReleaseDb 11·HoldReleaseRule 7·ProjectionRule 13), 실패·skip 0 |
| 07:3x | mutation C(MJ-1 표현 검사 제거 + MJ-2 PAYMENT_HELD 제거 + m-1 같은 Payment 조건 제거 + m-3 정책 대조 제거) → CandidateRule·SealDb·HoldReleaseDb | exit 1, 39건 중 **7 실패**: AQ-DB6(봉인이 계약 위반으로 실패 — 원래 결함 재현), AQ-DB7a·b, AQ-DB8, HR-8, AQ-D4 PAYMENT_HELD, AQ-D9. 복원 sha256 일치 |
| 07:4x | mutation D(M9 isolation REPEATABLE READ + M7 lane 잠금 전체 제거(기존 행이면 IODKU·FOR UPDATE 없이 읽기)) → SealDb | exit 1, 16건 중 **3 실패**: AQ-C2, AQ-C4, AQ-C1(Given). 복원 일치 |
| 07:4x | mutation F(M9 isolation만) → SealDb | exit 1, 17건 중 **1 실패 = AQ-C2**(REPEATABLE READ snapshot이 잠금 직전 커밋된 취소를 못 봐 상계 누락). 따라서 D의 AQ-C4·AQ-C1 실패는 M7 몫. 복원 일치 |
| 07:4x | mutation E(lane `FOR UPDATE`만 제거 + 잠금 뒤 판정 재확인 제거) → SealDb | exit 0, 17건 통과 — **동등 변형**(기존 행 IODKU가 배타 행 잠금을 이미 잡고, lane 잠금 아래에서는 재확인이 중복 방어). 복원 일치 |
| 07:46 | `./gradlew :apps:financial-batch:test`(모듈 전체) | exit 0, **150건**, 실패·오류·skip 0, 보고서 17개 |
| 07:4x | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran 174, OK |

- harness 하한: GRADLE-CHECK·TEST-SUMMARY 3642 → **3651**(+9 = CandidateRule +3, SealDb +5, HoldReleaseDb +1; financial-batch 141 → 150 실측과 일치). HARNESS-SELFTEST 174 그대로.
- 기대값 변경: 기존 시험 단언은 완화하지 않았다. AQ-D4의 보류 입력에 `paymentHeld = true`를 더한 것은 새 입력 불변식(승인·취소 보류면 Payment도 보류) 때문이며 기대 결과는 같다. AQ-C1은 주장을 실제 증거에 맞게 이름만 바로잡았고(수렴), lane 직렬화 증거는 새 AQ-C4가 맡는다.

### 17.2 rev 0.4 실행 기록(배치2 2-D — Implementer, Verifier 재검증 대상)

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 07:5x | `./gradlew -q :apps:financial-batch:dependencies --configuration runtimeClasspath` | exit 0 — `sshd-sftp:2.20.1 → sshd-core:2.20.1 → sshd-common:2.20.1`, slf4j 1.7.36 → 2.0.18(BOM) |
| 07:59 | OSV API `POST https://api.osv.dev/v1/query`(purl 3개 @2.20.1, 양성 대조 sshd-common@2.9.0) | 2.20.1 세 artifact `{}`(0건), 2.9.0 → 3건 |
| 08:0x | `:apps:financial-batch:compileKotlin` | exit 1(-Werror: `Integer` 사용 경고 2건) → `Int::class.javaObjectType` 수정 → exit 0 |
| 08:1x | `:apps:financial-batch:test --tests '*AcquisitionTransmissionRuleSpec' '*AcquisitionTransmissionDbSpec' '*FinancialDatabaseAccountSpec' '*MainMigrationLocationSpec'` | exit 1 — 시험 컴파일 경고(`!!`) → 수정. 재실행 exit 1 — **환경 아닌 구현 결함**: V8 파일의 `${flyway:database}`가 작성 셸에서 빈 값으로 치환돼 Flyway 1370(`_privilege.grant_runtime_column_update`) → 파일 재작성 |
| 08:1x | 같은 명령 | exit 1, 51건 중 2 실패: AQ-T4 — 장애 주입이 **첫** 로그인을 거부(시험 결함: 기대 `UNKNOWN`, 실제 `NOT_DELIVERED(AUTHENTICATION_FAILED)` — 이 자체는 올바른 동작). 주입을 "rename 응답을 끊은 뒤 로그인 거부"로 고침(기대값 불변) |
| 08:1x | `--tests '*AcquisitionTransmissionDbSpec'` | exit 0, 14건, 실패·skip 0 |
| 08:1x | mutation T(5개 동시: T-1 Unknown → SEALED, T-2 rename 뒤 재접속 확인 제거, T-3 결과 TX version 비교 제거, T-4 점유 전 무결성 검사 제거, T-5 V8에 receipt_state UPDATE 추가) → RuleSpec·TransmissionDb·Account | exit 1, 47건 중 **15 실패**: AQ-TD2·TD4(T-1), AQ-T1·T3(T-2), AQ-T4 2건(T-1·T-2), AQ-T8(T-3), AQ-T9(T-4), Account 7건(T-5 — 새 acquisition_file 열 시험·SHOW GRANTS·표 집합 등). 잡히지 않은 변형 없음. 복원 후 sha256 앞 16자 일치(Service `b386db783bec354c`, AcquisitionFile `7d4ba14430f2b0fb`, V8 `b08372147f8f798a`) |
| 08:22–08:24 | `./gradlew :apps:financial-batch:test`(모듈 전체) | exit 0, **176건**(보고서 19개), 실패·오류·skip 0 |
| 08:2x | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran **174**, OK |

- RED: 시험을 구현과 함께 썼으므로 선행 RED 대신 mutation T(5개 변형 — 모두 업무 실패로 잡힘, 컨텍스트·컨테이너 정상 기동)로 대체했다. 실행 중 생긴 두 실패(V8 치환·AQ-T4 주입 시점)는 각각 구현 결함·시험 장애 주입 결함이었고 기대값은 바꾸지 않았다.
- harness 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3651 → **3677**(+26 = TransmissionRule 11 + TransmissionDb 14 + Account +1; financial-batch 150 → 176 실측과 일치). HARNESS-SELFTEST 174 그대로(harness 시험 추가 없음 — technology-baseline.json 형식 검사 시험은 저장소에 없다).
- 기대값 변경(완화 아님): Account append-only 시험 대상에서 `acquisition_file`을 뺀 것은 2-D 상태 전이(D-ACQ-004) 때문이며, 같은 표의 거부는 새 열 단위 시험이 더 세밀하게(전송 축 5열 밖 모든 열 1143, DELETE·TRUNCATE 1142) 본다. **rev 0.6 정정(리뷰 m-6)**: append-only 시험이 보던 잠금 읽기 1142는 acquisition_file에 더는 성립하지 않는다 — 열 단위 UPDATE 권한이 생기면 MySQL은 SELECT … FOR UPDATE를 허용한다. 새 시험은 이를 "lock read 허용(의도된 부수 효과 — 운영 코드는 잠그지 않고 @Version으로 전이)"으로 명시 단언한다. 나머지는 추가만.
- 실행하지 않은 것: 전체 GRADLE-CHECK·institution-simulator 모듈·다른 앱(지시 — 메모리, 2-E 미착수라 simulator 변경 없음), 정식 CVE-SCAN.

### 17.3 rev 0.5 실행 기록(배치2 2-E — Implementer, Verifier 재검증 대상)

환경: 같은 WSL(7.6 GiB)·JDK 25 toolchain·Gradle 9.8.0·Docker(Testcontainers mysql:8.4.11). 명령마다 `./gradlew --stop`. 운영 DB·키·endpoint 미사용, Demo MySQL 컨테이너·볼륨 미접촉. 시험 키는 시험마다 새로 만든 임시 ECDSA 키.

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 08:43 | `python3 -B -m unittest harness.tests.test_acquiring_file_golden harness.tests.test_acquiring_ack_result_golden`(새 TARGETS·새 생성기, simulator 사본 없음) | **RED** exit 1, Ran 8, errors 2 — simulator 사본 `acquiring-file-v1.golden.json`·`acquiring-ack-result-v1.golden.json` 없음(FileNotFoundError) |
| 08:4x | simulator 사본만 생성(financial-batch 사본은 쓰지 않음 — 바이트 `cmp` 동일) 후 같은 명령 | exit 0, Ran 8, OK |
| 08:59 | `:apps:institution-simulator:compileKotlin` | exit 1 — 구현 결함 2: build에 sshd 의존 누락, KDoc 안 `/inbox/*`가 중첩 주석을 염 → 수정. 재실행 exit 1(-Werror 불필요한 safe call) → 수정 → BUILD SUCCESSFUL |
| 09:0x | `:apps:institution-simulator:compileTestKotlin` | BUILD SUCCESSFUL |
| 09:12 | `:apps:institution-simulator:test --tests 'com.paycore.simulator.acquirer.domain.*' '*AcquirerSettingsSpec' '*SimulatorArchitectureSpec'` | exit 0, 40건(Settings 4·Golden 4·Rules 8·Reader 6·Architecture 18), 실패·skip 0 |
| 09:13 | `--tests '*AcquirerSftpWorkerSpec'` | exit 1, 10건 중 1 실패(AS-2) — **시험 결함**: `SftpClient.readDir`는 반복할 때 디렉터리를 연다(호출만으로는 예외 없음). 목록 helper로 반복하도록 고침(기대 = 거부 그대로) |
| 09:14 | 같은 명령 | exit 1, AS-2 — **시험 결함**: 반복 중 SFTP 오류가 `UncheckedIOException`으로 감싸짐 → helper가 원인 예외로 푼다(기대 그대로) |
| 09:16 | 같은 명령 | exit 0, 10건, 실패·skip 0 |
| 09:18 | `--tests '*SimulatorDatabaseAccountSpec'` | exit 1, 5건 중 1 실패(새 시험) — **시험 결함**: 편집 셸 인용 때문에 SQL 문자열의 작은따옴표가 빠짐(`Unknown column`) → 복원(기대 그대로) |
| 09:20 | 같은 명령 | exit 0, 5건, 실패·skip 0 |
| 09:23 | mutation A(M1 bodySha 검사 끔, M4 다른 내용을 이미 받음으로, M5 SALE 승인액 대조 끔, M9 상충이 같은 결과 게시) → domain spec | exit 1, 18건 중 **4 실패**: R-4(M1), RC-1(M4), RJ-2(M5), PP-1(M9). 복원 sha256 앞 16자 Reader `5ace093716b10a3d`·ReceiptRule `b59cf076f0844c66`·RecordJudgementRule `10f9e471dcc22982`·PublicationPlanner `5da8e617841608f7` |
| 09:24 | mutation B(M2 최종명 쓰기 허용, M6 다른 바이트 게시 파일 덮어쓰기, M7 이중 ACCEPTED UNIQUE 제거, M8 rename 응답 유실 주입 끔) → AcquirerSftpWorkerSpec + Account | exit 1, 15건 중 **4 실패**: AS-2(M2), AS-9(M6), Account 새 시험 1062(M7), AS-8(M8). 복원 Policy `607c454a9d388d60`·LocalAcquirerFileArea `ebf574deaf27f141`·V10 `ba55d2f3cf6f9f1a`·Faults `86e743c067b75722` |
| 09:27 | mutation C(M3 rename 덮어쓰기 허용 + M10 진행 표 file_name 열 UPDATE 부여 + M11 RECEIPT_CONFLICT 기록 안 함) → 같은 두 spec | exit 1 — M10이 **기동 검사(AcquirerLedgerPrivilegeGuard)로 두 spec의 Spring 컨텍스트 기동 자체를 거부**(BeanCreationException·IllegalStateException). M3·M11이 가려졌으므로 아래 D로 다시 실행. (그 전 09:26 시도는 mutation 코드 타입 오류로 컴파일 실패 — 시험 결과 아님) |
| 09:28 | mutation D(M3 + M11) → AcquirerSftpWorkerSpec | exit 1, 10건 중 **2 실패**: AS-2(M3), AS-4(M11). 복원 Policy `607c454a9d388d60`·V12 `006a99227b5d77a4`·AcquirerWorkerService `b02ee4d933de1c5f` |
| 09:30 | `./gradlew --no-daemon -q :apps:institution-simulator:dependencies --configuration runtimeClasspath`(GRADLE-DEPS-INSTITUTION-SIMULATOR) | exit 0 — `sshd-sftp:2.20.1 → sshd-core → sshd-common 2.20.1`, slf4j-api·jcl-over-slf4j 1.7.36 → 2.0.18(TECH-BASELINE §10) |
| 09:32–09:35 | `./gradlew :apps:institution-simulator:test`(모듈 전체 — 복원 뒤, 마지막 1회) | exit 0, **248건**(보고서 25개), 실패·오류·skip 0 |
| 09:3x | `python3 -B -m unittest discover -s harness/tests` | exit 1, Ran 178 — module-registry: HARNESS-SELFTEST 하한 174 ≠ 178(새 시험 4) → commands.json 갱신 |
| 09:3x | 같은 명령 | exit 0, Ran **178**, OK |

- RED: harness golden은 선행 RED(사본 없음 → 2 errors)를 남겼다. Kotlin 시험은 구현과 함께 썼으므로 선행 RED 대신 mutation 11개(A·B·D 업무 실패 10 + C의 M10 기동 거부 1)로 대체했다 — 모두 잡혔고, 실행 중 생긴 실패 3건(AS-2 두 번·Account 1번)은 시험 helper/편집 결함이었으며 기대값은 바꾸지 않았다. 잡히지 않은 변형 없음. 시험하지 않은 변형: `.part`·점 이름을 inbox 목록에서 빼는 필터 제거(파일 ID 문법이 다시 거르므로 동등 변형으로 판단 — 이중 방어).
- harness 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3677 → **3712**(+35 = AcquirerGolden 4 + Reader 6 + Rules 8 + Settings 4 + SftpWorker 10 + Architecture +2 + Account +1; institution-simulator 213 → 248 실측과 일치). HARNESS-SELFTEST 174 → **178**(+4 = test_acquiring_ack_result_golden).
- 기대값 변경(완화 아님): SimulatorDatabaseAccountSpec·SimulatorArchitectureSpec은 표·규칙이 **늘어난** 추가만(기존 단언 그대로). `MutableSimulatorClock`에 `AcquirerClock` 구현 추가(시험 시계 하나가 모든 모듈 시각원).
- 실행하지 않은 것: 전체 GRADLE-CHECK·다른 앱 모듈(지시 — 메모리), financial-batch(쓰기 금지·리뷰 중), 정식 CVE-SCAN(새 artifact 없음 — 같은 2.20.1 재사용, Verifier 몫), FINANCIAL ↔ Simulator 프로세스 E2E(C-6 — 2-F 뒤).

### 17.4 rev 0.6 실행 기록(2-D 리뷰 반영 — Implementer, Verifier 재검증 대상)

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 09:2x | :apps:financial-batch:compileKotlin | exit 0 |
| 09:3x | :apps:financial-batch:test --tests AcquiringTransportSettingsSpec, AcquisitionTransmissionRuleSpec, AcquisitionTransmissionDbSpec, FinancialDatabaseAccountSpec, MainMigrationLocationSpec, ArchitectureSpec | exit 0, **90건**(Settings 7·RuleSpec 11·TransmissionDb 26·Account 22·MainMigration 4·Architecture 20), 실패·skip 0 |
| 09:4x | mutation R(6개 동시: R-1 rename 직전 lease 검사 제거, R-2 rename 응답 없음 + 없음 → NOT_DELIVERED(rev 0.4 동작), R-3 SUPERSEDED 관찰 행 제거, R-4 LIVE 거부 제거, R-5 키 파싱 실패를 빈 목록으로 삼키고 개수 검사 제거, R-6 활성 TX 가드 제거) → Settings·TransmissionDb | exit 1, 33건 중 **8 실패**: AQ-T16(R-1 — 덜 쓰인 바이트 게시), AQ-T11 2건(R-2), AQ-T8(R-3), AQ-T19 LIVE(R-4), AQ-S2 2건(R-5), AQ-T19 TX(R-6). 잡히지 않은 변형 없음. 복원 후 sha256 앞 16자 일치(Service 83b2428165a29af4, Settings 28e5aedbe8acddcb) |
| 09:59 | :apps:financial-batch:test(모듈 전체) | **완료 못 함 — Claude Code가 WSL 메모리 부족으로 백그라운드 실행을 중단**(시험 실패 아님, 결과 없음). 지시에 따라 다시 띄우지 않았다 → Verifier/Coordinator 재실행 필요 |
| 10:0x | python3 -B -m unittest discover -s harness/tests | exit 0, Ran **178**, OK |

- RED: 리뷰 지적별 시험을 구현과 함께 썼으므로 mutation R로 대체했다(모두 업무 실패로 잡힘). 첫 대상 실행이 통과해 실행 중 실패는 없었다.
- harness 하한: GRADLE-CHECK minTests·TEST-SUMMARY 인자 3712(2-E 반영 값) → **3732**(+20 = Settings 7 + TransmissionDb +12(14 → 26) + Architecture +1(sshd 규칙); 다른 financial spec 수 불변 — Account 22·MainMigration 4·RuleSpec 11). financial-batch 기대 총수 176 → 196. **모듈 전체 실측은 메모리 중단으로 없다** — 변경 spec의 보고서 수 실측으로 계산했다. HARNESS-SELFTEST 178 그대로.
- 기대값 변경(완화 아님): AQ-T8 첫 시도의 결과가 null(결과 행 없음)에서 SUPERSEDED(observed DELIVERED) 감사 행으로 바뀌었다(m-5 — 기록 추가, 더 엄격). Account·MainMigration은 Flyway 1..9와 acquisition_file lock read 허용 단언 추가.
- 확인만(정리는 별도 작업): financial-batch runtimeClasspath에 commons-logging 1.3.6(spring-core 7.0.9 → Boot BOM)과 jcl-over-slf4j 2.0.18(sshd-common 1.7.36 → BOM 2.0.18)이 **함께** 들어온다(같은 org.apache.commons.logging 패키지 중복).

### 17.5 rev 0.7 실행 기록(2-E 리뷰 반영 — Implementer, Verifier 재검증 대상)

환경: 같은 WSL·JDK 25·Gradle 9.8.0·Docker(Testcontainers mysql:8.4.11). Demo MySQL 두 개는 Coordinator가 꺼 두었고 켜지 않았다. 명령마다 `./gradlew --stop`. apps/financial-batch는 읽기만.

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 10:10–10:12 | **RED**: 새·바꾼 시험만 먼저 쓰고(구현 전) `:apps:institution-simulator:test --tests '*AcquirerSftpWorkerSpec' '*AcquirerSettingsSpec' '*SimulatorDatabaseAccountSpec'` | exit 1, 25건 중 **17 실패**: ST-5(그룹·기타 쓰기·공개키 3개 — IAE/ISE 없음), AS-1(목록 밖 암호·다섯째 세션 허용), AS-2..AS-13(시도별 임시명 `.{id}.dat.{n}.part` 쓰기가 PERMISSION_DENIED — C-9 미구현), Account 기동 시험(진행 표 `receipt_id` UPDATE 부여에도 기동 — m-5)과 그 연쇄(work item 열 시험 `[1143, null]`). AS-14(이미 있던 동작)는 통과. MJ-1 통합 단언(AS-10b)은 임시명 거부에 가려 도달하지 못했다 → 아래 domain RJ-4·mutation으로 증명 |
| 10:1x | 구현 후 `compileTestKotlin` | BUILD SUCCESSFUL |
| 10:20 | `--tests 'com.paycore.simulator.acquirer.domain.*' '*AcquirerSettingsSpec' '*SimulatorArchitectureSpec'` | exit 0, **42**건(Settings 5·Golden 4·Rules 9·Reader 6·Architecture 18) |
| 10:20 | `--tests '*AcquirerSftpWorkerSpec'` | exit 0, **17**건 |
| 10:21 | `--tests '*SimulatorDatabaseAccountSpec'` | exit 0, **5**건 |
| 10:24 | mutation X(R1 SALE가 이미 수락된 VAN 승인 무시, R2 CANCEL 누적 한도 제거, R3 CANCEL 원거래 = 수락 SALE 참조 비교 제거, Q1 옛 공용 임시명 다시 허용) → domain | exit 1, 19건 중 2 실패: RJ-4(R1·R2 — "same VAN approval", "netted cancel sent again" 값 차이), R-6(Q1). **R3는 잡히지 않음** — RJ-4의 "다른 원거래" 사례가 취소 참조도 달라 다른 사유로 거절되던 **시험 결함**. 사례 입력을 고침(기대값 그대로) |
| 10:25 | mutation X2(R3만) → domain | exit 1, 1 실패: RJ-4 "CANCEL refers to another original" |
| 10:26 | mutation Y(R4 기관 근거 UNIQUE 제거, R5 파일 안 근거 수락 추적 제거, P1 열린 핸들 rename 허용, P2 파일 실패 격리 제거, P3 재hash 생략 제거, P4 임시 파일 정리 제거, P5 기동 검사 고정 2열로, P6 동시 세션 상한 제거, P7 그룹·기타 쓰기 허용) → Worker·Account·Settings | exit 1, 27건 중 **9 실패**: AS-10b(R5), AS-12(P1), AS-13(P2), AS-15(P3), AS-16(P4), Account 기동(P5), AS-1(P6), ST-5(P7), Account work item — 이 마지막은 P5 기동 시험이 GRANT를 되돌리지 못한 **연쇄**(`[1143, null]`)라 R4 증거가 아님 → 기동 시험의 REVOKE를 finally로 옮김(시험 견고성 — 기대값 그대로) |
| 10:28 | mutation Y2(R4만) → Account | exit 1, 1 실패: "Expected 1062 but actual was null" |
| 10:30 | mutation Z(P8 업로드 16 MiB 상한 제거, P9 암호 allowlist 제거 = sshd 기본) → Worker | exit 1, 17건 중 2 실패: AS-12(P8), AS-1(P9) |
| 10:34–10:3x | `./gradlew :apps:institution-simulator:test`(모듈 전체, 모든 복원 뒤 마지막 1회) | **실행 중 시스템 메모리 부족으로 강제 중단**(Claude Code 백그라운드 회수 — 시험 실패 아님, 결과 보고서 없음). 지시대로 다시 띄우지 않았다 → **모듈 전체 실행 미완료(Verifier 몫)**. 남은 Testcontainers 컨테이너는 ryuk가 회수, Demo MySQL 두 개는 꺼진 그대로 |
| 10:35 | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran **178**, OK |

- 복원: 모든 mutation 파일을 sha256 대조로 복원했다(X: RecordJudgementRule `a6dd7df202509ec4`·AcquisitionFileRef `ebac57936b5637d3`, Y: V13 `54063e6ef20d254e`·AcquirerWorkerService `38c2f2d66e054c2e`·AcquirerSftpPolicy `d66aa4c4b16b8f8f`·AcquirerLedgerPrivilegeGuard `b82187a00a38408a`·AcquirerSftpServer `3dd01a6cc67d8f4e`·AcquirerSettings `d8e45d403c1e4ef8`, Z: AcquirerSftpUploads `e6696d4be243f8d3`·AcquirerSftpServer `3dd01a6cc67d8f4e`). 그 뒤 KDoc 한 줄(AcquirerSftpFaults)만 바뀌었고 모듈 전체 실행이 그 뒤다.
- 잡히지 않은 변형: 없음(R3는 시험 결함을 고친 뒤 잡힘). 시험하지 않은 변형: 디렉터리 fsync 제거(관찰 수단 없음 — best effort), 인증 시도·idle·인증 timeout 값(시험 시간 비용 — 코드 리뷰 대상), 거부 게시 작업 10분 제외(n-3 — 단위 관찰 수단 없음, 코드 리뷰 대상).
- 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3732 → **3741**(+9 = Rules +1, Settings +1, SftpWorker +7; 대상 spec 실측 합: Rules 9 + Settings 5 + SftpWorker 17 — 이번 회차에 바뀐 spec 전부를 개별 실행으로 측정. 모듈 전체 257(= 248 + 9)은 모듈 실행이 메모리로 중단돼 **실측 확인 전**). HARNESS-SELFTEST 178 그대로(harness 시험 수 변화 없음 — docstring만).
- 기대값 변경(완화 아님): R-6의 임시명 기대는 C-9(시도별 임시명, 옛 공용 임시명 거부)에 따른 계약 변경이다. RJ-1·RJ-3은 같은 기대에 새 입력(기관 근거·소비)을 넣었다. AS-2·AS-3·AS-8은 업로드 임시명만 시도별로 바꿨다(기대 그대로). Account 시험의 판정 INSERT는 V13 새 열을 채우도록 바꿨고(같은 1062 기대), 기동 시험 REVOKE를 finally로 옮겼다. ArchitectureSpec의 `AcquirerReceiptStore` 메서드 집합은 읽기 메서드가 늘어 바뀌었다(수정·삭제 메서드 없음 단언은 그대로).
- 실행하지 않은 것: 전체 GRADLE-CHECK·다른 앱 모듈·financial-batch(지시), CVE-SCAN(새 artifact 없음), FINANCIAL ↔ Simulator E2E(C-6 — FINANCIAL 시도별 임시명 전환 뒤).


### 17.5 rev 0.8 실행 기록(C-9 시도별 임시명 — Implementer, Verifier 재검증 대상)

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 10:40–10:42 | **RED**: 시험(시험 서버 계약 흉내·AQ-T1·T2 임시명 단언·AQ-T20·AQ-T21)만 먼저 바꾸고 `:apps:financial-batch:test --tests '*AcquisitionTransmissionDbSpec'` | exit 1, 29건 중 **18 실패** — 업무 실패(옛 공용 임시명 `.{id}.dat.part` 쓰기가 PERMISSION_DENIED → UPLOAD_FAILED). 컨텍스트·컨테이너 정상. AQ-T21의 계약 밖 이름 시험(시험 서버 흉내 검증)은 이때도 통과 |
| 10:43–10:44 | 구현(시도별 임시명·Write/Create/Truncate 명시) 뒤 같은 명령 | exit 0, **29건**, 실패·skip 0 |
| 10:4x | mutation C(C-1 임시명 n을 1로 고정(시도 공용) + C-2 쓰기 핸들을 닫지 않고 rename) | exit 1, 29건 중 18 실패(C-2가 모든 rename을 PERMISSION_DENIED로 만들어 C-1을 가림). 복원 sha256 앞 16자 일치(Service 59def9f41e21c76a, Channel 6d46756d470a4acf) |
| 10:5x | mutation C-1만 | exit 1, 29건 중 **3 실패**: AQ-T20(늦은 시도가 이어받은 시도의 반쯤 쓴 바이트를 게시 — published == sealed false), AQ-T2·AQ-T21(재시도 임시명 n = 2 아님). 복원 일치 |

- harness 하한: GRADLE-CHECK minTests·TEST-SUMMARY 인자 3741(2-E rev 0.7 반영 값) → **3744**(+3 = TransmissionDb 26 → 29). financial-batch 기대 총수 196 → 199. 모듈 전체는 실행하지 않았다(지시 — Coordinator가 별도 실행). HARNESS-SELFTEST 하한 불변.
- 기대값 변경(완화 아님): AQ-T1·T2에 임시명 단언 추가, AQ-T16 기대값 불변(남은 lease 검사가 여전히 rename을 막는다).
- 실행하지 않은 것: financial-batch 모듈 전체(메모리 — 지시), Settings·Rule·Account·Architecture spec(이번 변경이 닿지 않음 — 서비스·채널·시험 서버만 바뀜), institution-simulator와의 실제 E2E.

### 17.6 rev 0.9 실행 기록(2-E 재리뷰 MINOR 반영 — Implementer, Verifier 재검증 대상)

환경: 같은 WSL·JDK 25·Gradle 9.8.0·Docker(Testcontainers mysql:8.4.11). Demo MySQL은 꺼진 그대로. 명령마다 `./gradlew --stop`. 컨테이너 spec은 하나씩 실행했고(지시), 컨테이너 없는 domain·Settings·Architecture spec은 한 명령으로 묶었다. **모듈 전체 실행은 하지 않았다**(Coordinator 별도 프로세스).

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 10:50 | **RED**: 새 시험(AS-17·AS-18)만 넣고 `--tests '*AcquirerSftpWorkerSpec'` | exit 1, 19건 중 2 실패: AS-17 "Expected IOException but no exception was thrown"(copy-data가 16 MiB 상한을 넘어 썼다 — 쓰기 이벤트 우회 확인), AS-18 PERMISSION_DENIED(상한에서 같은 파일 ID 재시도 거부) |
| 10:51 | **RED**: 기동 거부 대상에 append-only 열 권한 2개를 더하고 `--tests '*SimulatorDatabaseAccountSpec'` | exit 1, 5건 중 1 실패: 기동 시험 "Expected exception but no exception was thrown"(고정 탐침 열 밖 열 권한을 못 봄). finally REVOKE 덕분에 연쇄 실패 없음 |
| 10:5x | 구현 후 `compileTestKotlin` | BUILD SUCCESSFUL |
| 10:55 | `--tests 'com.paycore.simulator.acquirer.domain.*' '*AcquirerSettingsSpec' '*SimulatorArchitectureSpec'` | exit 0, **42**건(Settings 5·Golden 4·Rules 9·Reader 6·Architecture 18) |
| 10:56 | `--tests '*AcquirerSftpWorkerSpec'` | exit 0, **19**건 |
| 10:57 | `--tests '*SimulatorDatabaseAccountSpec'` | exit 0, **5**건 |
| 10:59 | mutation D(S4 VAN 취소 원승인 행 대조 제거) → `*AcquirerRulesSpec` | exit 1, 1 실패: RJ-4 "VAN cancellation of another authorization row". 복원 RecordJudgementRule `997863a396cfb7e0` |
| 10:59 | mutation W(S1 쓰기 채널 상한 제거 — 이벤트만, S2 상한에서 같은 파일 ID 정리 제거) → `*AcquirerSftpWorkerSpec` | exit 1, 2 실패: AS-17(S1), AS-18(S2). 복원 AcquirerSftpPolicy `5edb375c577befe3` |
| 11:01 | mutation A(S3 기동 검사가 append-only 열 다시 건너뜀, S5 합친 V10에서 근거 UNIQUE 제거) → `*SimulatorDatabaseAccountSpec` | exit 1, 2 실패: 기동 시험(S3), work item 시험 "Expected 1062 but actual was null"(S5 — 연쇄 아님). 복원 Guard `e212f02bb7419b42`·V10 `34e2c99728b690b8` |
| 11:04 | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran **178**, OK(module-registry 하한 대조 포함) |

- 잡히지 않은 변형: 없음. 시험하지 않은 변형: 디렉터리 fsync·거부 작업 10분 제외(관찰 수단 없음 — 코드 리뷰 대상, rev 0.7과 같음).
- 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3744 → **3746**(+2 = SftpWorker AS-17·AS-18; Rules·Account·Settings는 기존 시험 안 사례 추가라 수 그대로). HARNESS-SELFTEST 178 그대로.
- 기대값 변경(완화 아님): 기동 거부 대상·RJ-4 사례·시나리오 추가뿐이다. V13 합침으로 Account 시험 기대는 바뀌지 않았다(같은 열·제약).
- 실행하지 않은 것: institution-simulator 모듈 전체(Coordinator 별도 실행 — 지시), 다른 앱, CVE-SCAN, E2E.

### 17.7 rev 1.0 실행 기록(배치2 2-F·2-G — Implementer, Verifier 재검증 대상)

환경: 같은 WSL(7.6 GiB)·JDK 25 toolchain·Gradle 9.8.0·Docker(Testcontainers mysql:8.4.11). Demo MySQL 두 개는 꺼진 그대로(켜지 않음). 명령마다 `./gradlew --stop`, 컨테이너 spec은 하나씩(지시), 컨테이너 없는 domain·Architecture spec은 한 명령. **모듈 전체 실행은 하지 않았다**(Coordinator 별도 프로세스). 운영 DB·키·endpoint 미사용, 시험 키는 시험마다 새 임시 ECDSA 키. apps/institution-simulator는 바꾸지 않았다.

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 12:06 | **RED**: harness `test_acquiring_ack_result_golden.py` TARGETS에 financial-batch 추가 후 `python3 -B -m unittest harness.tests.test_acquiring_ack_result_golden` | exit 1(errors 1) — FileNotFoundError: financial-batch 사본 없음 |
| 12:07 | simulator 사본을 financial-batch로 바이트 복사(`cmp` 동일) 후 같은 명령 | exit 0, Ran 4, OK |
| 12:27 | `:apps:financial-batch:compileKotlin` | exit 0 |
| 12:31 | `:apps:financial-batch:test --tests 'com.paycore.financial.acquiring.domain.*' --tests '*ArchitectureSpec'` | exit 1, 87건 중 1 실패 — **시험 결함**: CC-6이 다음 업무일 승인을 전날 마감으로 판정하는 Fixture 함수(`included`)를 써 ClassCastException → 다음 날 마감으로 판정하도록 고침(기대값 그대로) |
| 12:32 | 같은 명령 | exit 0, **87**건(Architecture 20·ReacquisitionRule 6·TransmissionRule 11·CandidateRule 11·ResponseFormat 4·FileGolden 2·ResponseRule 7·ResponseGolden 3·FileFormat 8·RecordResultRule 8·CancelCandidateRule 7), 실패·skip 0 |
| 12:37 | `--tests '*AcquirerResponseDbSpec'` | exit 1 — compileTestKotlin -Werror(불필요한 `!!`) → 수정 |
| 12:38 | 같은 명령 | exit 0, **21**건, 실패·skip 0(And 블록 본문 실행은 봉인 로그 — 11-07·11-08·11-09 파일 — 로 확인) |
| 12:40 | `--tests '*FinancialDatabaseAccountSpec'` | exit 0, **23**건 |
| 12:41 | `--tests '*MainMigrationLocationSpec'` | exit 0, **4**건 |
| 12:44 | `--tests '*AcquiringOperationsDbSpec'` | exit 0, **8**건(AQ-S3 로그: 둘째 인스턴스 봉인 단계 `CannotAcquireLockException` 1회 — JobRepository 경합, 다음 회차 수렴) |
| 12:46–12:48 | `--tests '*AcquisitionSealDbSpec'`, `'*AcquisitionTransmissionDbSpec'`, `'*AcquisitionSubmissionJobSpec'`(하나씩 — 바뀐 생성자·Port·시험 설정 영향 확인) | 각각 exit 0, **17**·**29**·**2**건 |
| 12:49 | mutation A(8개 동시: MA1 중복 판정이 사유·출처 무시, MA2 CONFLICT 상태 계산 제거, MA3 ACK hash·크기 대조 제거, MA4 CANCEL 자신 보류 대기 제거, MA5 카드 종류 열거값 검사 제거(NIT-2), MA6 같은 사람 거절 제거, MA7 결과 파일 정규형(트레일러) 검사 제거, MA8 hash 일치 응답의 전송 완료 승격 제거) → domain | exit 1, 67건 중 **10 실패**: RR-2(MA1), RR-3·RR-5(MA2), RS-2(MA3), CC-3(MA4), CC-7(MA5), RQ-3(MA6), AR-F3·AR-F4(MA7), RS-6(MA8). 복원 sha256 앞 16자 일치(ResponseFormat `2f2201aa0482f853`·ResponseRule `c4d764103235bf75`·AcquisitionFile `efd2e3fb8947074f`·AcquisitionRecord `4dfd2c520f845986`·CancelCandidateRule `f8c825691b69426f`·ReacquisitionRule `927ecf444648710d`·RecordResultRule `eb6db018deefc184`) |
| 12:50 | mutation B(6개 동작: MB1 (이름, hash) 처리 확인 제거, MB2 전송 중 미룸 제거, MB3 결과 마감 시간 무시, MB4 취소 매입 불요 기록 생략, MB5 재봉인 상계 DB 대조 제거, MB7 파일 거절 레코드 종결 생략) → `*AcquirerResponseDbSpec` | exit 1, 21건 중 **6 실패**: AQ-R10(MB1), AQ-R9(MB2), AQ-R6(MB3), AQ-X1(MB4), AQ-I2(MB5), AQ-R4(MB7). 복원 일치(ResponseService `b9a7b5adebc4cbb5`·CloseService `108fbedd3ab975c7`·SealService `6333df3bcb07f3c7`) |
| 12:52 | mutation B2(파일 행 `FOR UPDATE` 제거 — `lockFile` = 잠금 없는 find) → `*AcquirerResponseDbSpec` | exit 0, 21건 통과 — **동등 변형(잡히지 않음)**: 잠금이 없어도 같은 결과에 수렴한다 — **근거 rev 1.1 정정(리뷰 n-1)**: 결정적 처리 순서, UNIQUE(매입사, 이름, hash) 색인 대기 뒤 1062 → 재시도, 결과 행 번호 PK 충돌 → 재시도(`@Version`이 아님)(AQ-R10 응답마다 한 번 반영 유지). 잠금은 경합 시 재시도 비용을 없애는 1차 장치, DB 제약이 2차 방어(§4.6). 복원 일치(Repository `629915a0a96a1878`) |
| 12:54 | mutation C(6개 동시: MC1 V13 승인 표 INSERT 부여, MC2 UPLOAD_FAILED 반복 기준 4회, MC3 대기 gauge 값 갱신 제거, MC4 서명 채널 운영 구현이 true, MC5 스케줄러 결과 수신 단계 생략, MC6 LIVE lane 허용) → `*AcquiringOperationsDbSpec` | exit 1, 8건 중 **5 실패**: AQ-M1(MC2·MC3), AQ-S1(MC6), AQ-S2(MC5), AQ-G1 Then(MC4)·And(MC1/MC4). 복원 일치 |
| 12:55–12:58 | 동시 변형 분리: MC1만 → `*FinancialDatabaseAccountSpec`, MC2만·MC3만 → `*AcquiringOperationsDbSpec`(하나씩, 명령 하나 — 셸이 180초 뒤 배경 실행으로 넘김, 끝날 때까지 다른 Gradle 실행 없음) | MC1: exit 1 — 새 시험 "runtime INSERT 1142"·SHOW GRANTS 정확 일치·표 권한 집합 등 실패(연쇄 포함). MC2: exit 1, AQ-M1. MC3: exit 1, AQ-M1. 모두 복원 일치(V13 `68775c40909c49e4`·BacklogReader `744570ae75d20e5d`·Metrics `e93ffe80834aad83`) |
| 13:00 | `python3 -B -m unittest harness.tests.test_acquiring_e2e_script` | exit 1(errors 1) — **시험 결함**: 기본 인자 목록에 없는 선택 인자 치환에서 `list.index` 오류 → 없으면 덧붙이도록 고침(기대 그대로) → exit 0, Ran 4 |
| 13:00 | `python3 -B -m unittest discover -s harness/tests` | exit 1, Ran 182 — module-registry: HARNESS-SELFTEST 하한 178 ≠ 182(새 시험 4) → commands.json 갱신 |
| 13:01 | 같은 명령 | exit 0, Ran **182**, OK |
| 13:07 | `--tests '*MainMigrationLocationSpec'`(운영 application.yml 주석 추가 뒤 컨텍스트 기동 재확인) | exit 0, 4건 |

- RED: harness golden은 선행 RED(사본 없음)를 남겼다. Kotlin 시험은 구현과 함께 썼으므로 선행 RED 대신 mutation 4묶음(A 8개·B 6개·C 6개 + 분리 3회)으로 대체했다 — 모두 업무 실패로 잡혔다(컨텍스트·컨테이너 정상 기동, 환경 오류 아님). **잡히지 않은 변형 1개**: B2(파일 행 잠금 제거 — 위 동등 변형 근거). 시험하지 않은 변형: CANCEL 판정의 잠금 뒤 "판정 끝남" 재확인 제거(lane 잠금 아래 동등 — I-20과 같은 이유), 스케줄러 첫 회차 지연(시험은 `runOnce` 직접 호출), 대기 체류 초의 시스템 시계(관찰 값 > 0만 단언).
- 실행 중 실패 3건(CC-6 Fixture, `!!` 경고, e2e 시험 helper)은 모두 시험 코드 결함이었고 기대값은 바꾸지 않았다.
- 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3746 → **3811**(+65 = domain 35(ResponseGolden 3 + ResponseFormat 4 + ResponseRule 7 + RecordResultRule 8 + CancelCandidateRule 7 + ReacquisitionRule 6) + AcquirerResponseDb 21 + AcquiringOperationsDb 8 + Account +1; 다른 spec 수 불변 — 위 개별 실측). financial-batch 기대 총수 199 → **264**(모듈 전체 실측은 Coordinator 별도 실행 몫). HARNESS-SELFTEST 178 → **182**(+4 = test_acquiring_e2e_script).
- 기대값 변경(완화 아님): FinancialDatabaseAccountSpec·MainMigrationLocationSpec은 사용자 결정 D-FFR-002(새 표·새 전이마다 권한 migration·권한 시험)에 따라 표·버전·열 권한이 **늘었고**(acquisition_file 열 UPDATE 5 → 7, 새 4표, Flyway 1..13), append-only 거부 대상이 4표 늘었으며, 새 시험(승인 표 INSERT 1142)이 더해졌다. acquisition_file 열 시험의 이름에서 "receipt, result 1143"을 뺐다 — 2-F가 그 두 열을 연다(이전 rev가 "2-F가 연다"고 적어 둔 전이). 기존 단언(SHOW GRANTS 정확 일치·DELETE 없음·1142/1143)은 그대로다. AcquisitionSealDbSpec·AcquisitionTransmissionDbSpec은 봉인 서비스 생성자 인자 두 개(`results`, `board`)만 더했다(기대값 불변). `TestAcquirerSftpConfig`에 2-F 시험 lane(SFTPR1..40)을 더했다(전송 시험 lane과 겹치지 않게).
- 실행하지 않은 것: financial-batch·institution-simulator 모듈 전체(Coordinator 별도 프로세스 — 지시), 전체 GRADLE-CHECK·다른 앱, CVE-SCAN(새 의존성 없음 — Micrometer는 actuator로 이미 있음), **E2E 스크립트 실행**(C-6 — Coordinator가 Demo MySQL을 켜고 실행).

### 17.8 rev 1.1 실행 기록(2-F·2-G 리뷰 반영 + D-ACQ-013·014 — Implementer, Verifier 재검증 대상)

환경: 같은 WSL·JDK 25·Gradle 9.8.0·Docker(Testcontainers mysql:8.4.11). Demo MySQL은 꺼진 그대로. 명령마다 `./gradlew --stop`, 컨테이너 spec은 하나씩, **모듈 전체 실행 없음**(Coordinator 별도 — rev 1.0 기준 Coordinator 실측 264건 실패 0). 시각은 WSL `date -u`(UTC).

| 시각(UTC) | 명령 | 결과 |
|---|---|---|
| 21:29 | `:apps:financial-batch:compileKotlin` | exit 0 |
| 21:31 | `--tests 'com.paycore.financial.acquiring.domain.*' --tests '*ArchitectureSpec'` | exit 0, **93**건(ResponseFormat 5·ResponseGolden 3·ResponseRule 8·CandidateRule 11·FileFormat 8·FileGolden 2·TransmissionRule 11·CancelCandidateRule 7·ReacquisitionRule 9·RecordResultRule 9·Architecture 20), 실패·skip 0 |
| 21:32 | `--tests '*AcquirerResponseDbSpec'` | exit 0, **24**건 |
| 21:33 | `--tests '*AcquiringOperationsDbSpec'` | exit 0, **9**건 |
| 21:34 | `--tests '*FinancialDatabaseAccountSpec'` | exit 0, **23**건(SHOW GRANTS·표 집합·append-only에 `acquisition_accepted_record`, Flyway 1..15) |
| 21:35 | `--tests '*MainMigrationLocationSpec'` | exit 0, **4**건 |
| 21:37 | mutation D(5개 동시: 다른 세대 ACCEPTED 무시, 전송 축 격리 검사 제거, 합계 오버플로 분류 제거, UNKNOWN 대상 승인(rev 1.0 동작), 재검사가 ACCEPTED 무시) → domain | exit 1, 73건 중 **5 실패**: RR-8, RS-8, AR-F5, RQ-2, RQ-8. 복원 sha256 일치(ResponseFormat `661e873f3d083e5c`·ResponseRule `f1f96766e652d128`·ReacquisitionRule `9cf1a4896c82201e`·RecordResultRule `9c173255517c9588`) |
| 21:37 | mutation E(매입 확정 표지 기록 생략, 불요 원 SALE 충돌 count가 UNKNOWN을 셈) → `*AcquirerResponseDbSpec` | exit 1, 24건 중 **2 실패**: AQ-R14, AQ-X3. 복원 일치(BacklogReader `86f66c16e3f63880`·ResponseService `82f24060a4d1eb57`) |
| 21:39 | mutation F(재전송 비교에서 사유·근거 제거, NO_CUTOFF_POLICY 지표 생략) → `*AcquiringOperationsDbSpec` | exit 1, 9건 중 **2 실패**: AQ-G2(And), AQ-S4. 복원 일치(Scheduler `e50ff267d0e46f2f`·ApprovalService `5e243da4b7262448`) |
| 21:40 | `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran 182, OK |

- RED: 새 사례를 구현과 함께 썼으므로 mutation D·E·F(9개 — 모두 잡힘)로 대체. 첫 실행부터 통과(실행 중 실패 없음).
- 기대값 변경(완화 아님): RQ-2의 UNKNOWN 대상 기대가 "승인"에서 "UNKNOWN_NOT_RECONCILED 거절"로 바뀌었다 — **사용자 결정 D-ACQ-014**(더 엄격). Account·MainMigration은 새 표·버전 추가뿐.
- 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3811 → **3821**(+10 = ResponseFormat +1, ResponseRule +1, RecordResultRule +1, ReacquisitionRule +3, ResponseDb +3, OperationsDb +1). financial-batch 기대 총수 264 → **274**(모듈 전체 실측은 Coordinator). HARNESS-SELFTEST 182 그대로.
- 실행하지 않은 것: AcquisitionSealDbSpec·TransmissionDbSpec·SubmissionJobSpec(이번 변경은 `nextBusinessDateToSeal` 반환형·응답 서비스·재매입 규칙·지표만 — 봉인·전송 경로와 그 생성자는 바뀌지 않음, 모듈 전체 실행에서 확인 필요), 모듈 전체, E2E(C-6).
