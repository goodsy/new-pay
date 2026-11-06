# ACQ-VIEW-001 매입 현황 조회(read-only) 명세 (rev 0.6)

- rev 0.1(2026-11-06, Implementer): Phase 1 초안 — 코드 없음.
- **rev 0.2**(2026-11-06, Implementer): 사용자 결정 D-ACQV-001~003(Q-ACQV-01~03)·Coordinator 결정 R-1~R-4 반영, **B0(jOOQ codegen) + B1(FINANCIAL 채널·Reader·API 3개·V16) 구현**과 실행 기록(§13). B2(ADMIN 화면)·B3(Demo E2E)는 미착수.
- **rev 0.3**(2026-11-06, Implementer): Coordinator 모듈 전체 실행 1건 실패(MainMigrationLocationSpec 기대 버전 목록 — V16 추가 반영) + 독립 리뷰 CHANGES_REQUIRED(MAJOR 없음) 반영: MINOR-1 ADMIN 경로 405/404 고정 code(I-14), MINOR-2 `responseRef` 중첩, MINOR-3 codegen 대상 `acquisition_*`만, MINOR-4 단독 Fixture·다른 업무일 keyset, MINOR-5 실제 MySQL 3024 분류 시험, NIT(checksum 표 목록 단언). 기록만: MINOR-6(§11 L-9), D-ACQV-002 범위(§2 Coordinator 기록 C-ACQV-1), 나머지 NIT(§11 L-10). §13.8 실행 기록.
- **rev 0.4**(2026-11-06, Implementer): B1 재리뷰 REVIEW_PASS·Coordinator 모듈 전체 exit 0(366건) 뒤 **B2 구현** — ADMIN `financialops` 모듈(서명 클라이언트·채널 전용 키·응답 모양·요청-응답 대응·permit·속도 제한), 화면 API 3개(D-ACQV-001 역할·감사), React "매입·대사 > 매입 현황", Vitest, 시험 대역 화면 캡처. 새 해석 I-15(감사 대상 참조 형식)·I-16(탭 순서). §13.9 실행 기록. B3(Demo E2E)는 미착수.
- **rev 0.5**(2026-11-06, Implementer): B2 독립 리뷰 CHANGES_REQUIRED(MAJOR 1·MINOR 3·NIT) 반영 — MAJOR-1 ADMIN 응답 문법을 FINANCIAL 저장 문법과 정확히 맞춤(숫자 사유 코드 `05`·`1001` 영구 503 해소, §4.7 대조표 — L-7의 실제 사례), MINOR-1 감사 저장 실패 HTTP 시험, MINOR-2 형식 틀린 본문도 역할 판정이 먼저(`rejectMalformedSearch`), MINOR-3 쪽 순서·다음 cursor 대응 검사, NIT 늦은 레코드 응답의 진행 표시. 기록만: I-15 action–대상 종류 결합 미검사(L-11), 정지 계정 HTTP 경로는 세션 층 401(L-12). §13.10 실행 기록.
- **rev 0.6**(2026-11-06, Implementer): B2 재리뷰 REVIEW_PASS·Coordinator 모듈 전체 exit 0(internal-admin 699·financial-batch 367) 뒤 **B3 Demo E2E**(D-ACQV-003) — 실제 5개 프로세스·실제 SFTP 매입사로 E2E 3개 시나리오 PASS, 실제 관리자 화면 캡처 8장. 코드 변경 없음. 새 한계 L-13(감사 화면 대상 열에 파일 ID 미표시). §13.11 실행 기록.
- 기준: branch `feat/foundation-setup`, HEAD `4bd0105` + 작업 트리(미커밋). Implementer 작성 — 자기 완료 판정 아님(Verifier·Reviewer 별도).

근거: harness/tasks.json ACQ-VIEW-001(acceptanceCriteria·allowedWritePaths), ACQUIRING-001 rev 1.1(§4 상태 축·§5 파일 계약·§11 표·권한·C-2), TXN-OPS-001 rev 0.2(같은 모양의 직전 작업),
MERCHANT-OPS-001(새 소유 서비스에 ADMIN 서명 채널을 처음 여는 직전 사례 — master-api), ADMIN-PAYMENT-OPS-001 §4.2–4.3(서명 v1), FIN-FACT-RELAY-001 §4.1(financial-batch HTTP 진입 구조),
FIN-DBACCOUNT-001(FINANCIAL runtime 표 단위 권한), D-ADMIN-002·008, D-TXOPS-001~005, D-ACQ-001~014, ARCHITECTURE-CONVENTIONS §9·§14·§23, TECHNOLOGY-AND-PRINCIPLES §1~§5.

표기: **D-…** = 사용자 결정(DECISIONS.md). **Q-ACQV-n** = 사용자에게 물을 질문(§10). **I-n** = Implementer 해석(가역 — Reviewer 확인 대상, 사용자 승인 아님). **R-n** = Coordinator가 정할 가역 구현 선택(권장안 포함).

## 1. 범위

| 포함 | 제외(후속) |
|---|---|
| FINANCIAL(financial-batch)에 ADMIN 서명 채널 수신 + **읽기 endpoint 3개**(파일 검색·파일 상세·레코드 검색) | 재매입 승인·보류 REVIEW_REQUIRED 해소·격리 해소 입력(ACQ-ADMIN-001 — D-ACQ-011) |
| 파일 목록 검색(업무일 기간 필수·매입사·환경·3축 상태·"확인 필요만"·파일 ID 정확 조회, keyset) | 파일에 연결되지 않은 응답 격리(UNKNOWN_FILE — `file_id` NULL) 표시(L-4) |
| 파일 상세(파일 요약·레코드 결과 집계·전송 시도 이력·응답 처리 기록 요약·레코드 첫 쪽 — 한 일관 읽기) | 상계·제외·"취소 매입 불요" 기록 화면, 대기(PAYMENT_HELD 등) 체류 화면(I-53 지표만) |
| "확인 필요" 사유 계산(서버) | 파일 바이트·응답 바이트 내려받기, CSV(D-TXOPS-004와 같은 이유) |
| ADMIN 새 모듈(R-3) + 화면 "매입·대사 > 매입 현황"(목록·상세 패널) + Vitest | 다중 인스턴스 속도 제한 합산·mTLS(LIVE Gate — TXN-OPS-001 L-1·L-2와 같다) |
| Demo E2E(결제 → 봉인 → SFTP 전송 → 가상 매입사 결과) 실행과 화면 캡처(§9) | 실제 매입사 연동, LIVE 매입(D-ACQ-008 차단 유지) |

읽기 전용이다: FINANCIAL 업무 표에 쓰지 않는다(읽기 전용 TX). 스키마 변경은 목록 색인 하나(V16, §5.3 — 데이터·권한 변경 없음). ADMIN은 상세 조회 감사 행만 쓴다(ADMIN DB 로컬 TX).
매입 상태·금액·결과의 의미는 바꾸지 않는다(저장 값과 기존 규칙 `RecordResultRule.status`를 그대로 보여 준다).

## 2. 결정·해석·가역 선택

| ID | 내용 | 종류 |
|---|---|---|
| D-ACQ-004·009·010·014 | 3축(전송·수신·결과) 분리·레코드 결과 4값(ACCEPTED·REJECTED·UNKNOWN·CONFLICT)·UNKNOWN은 대사·늦은 결과 append-only·상충 격리 — 화면은 이 값을 **그대로** 표시하고 해석·추정하지 않는다 | 사용자 결정(적용) |
| D-ACQ-011 | 재매입·해소 입력은 ACQ-ADMIN-001. 이 화면은 버튼·링크를 두지 않는다 | 사용자 결정(적용) |
| **D-ACQV-001** (Q-ACQV-01) | "거래 조회와 같게": 조회 역할 = VIEW_MASKED 4역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN), **파일 상세·레코드 쪽 조회만** `ACQUISITION_FILE_VIEWED` 감사(targetRef = fileId, 반환 전 기록, 감사 실패 → 결과 미반환), 목록 미감사 | **사용자 결정**(AskUserQuestion 2026-11-06) — 적용은 ADMIN 쪽 B2(FINANCIAL은 역할·감사를 모른다) |
| **D-ACQV-002** (Q-ACQV-02) | "보여 주지 않음": 레코드의 승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부는 **SELECT하지 않는다**(§4.3·§4.5) | **사용자 결정** — B1 적용(Reader SQL에 열 없음 + 비노출 대조군 시험) |
| **D-ACQV-003** (Q-ACQV-03) | Demo 준비(§9.1) 허락 — B3에서 Coordinator와 함께 수행(B0·B1 동안 Demo를 건드리지 않음) | **사용자 결정** — B3 |
| I-3 | 기간 = 업무일(Asia/Seoul 달력일, DATE) `[businessDateFrom, businessDateTo]` 양끝 포함, 최대 31일(Demo — TXN과 같은 값), 파일 ID 정확 조회는 기간 면제. 화면 기본값 KST 오늘 포함 7일 | 해석(조회 모양 — 금융 의미 없음) |
| I-4 | "확인 필요" 사유는 FINANCIAL Reader가 코드 목록으로 계산한다(§4.4). 화면은 계산하지 않고 표시만 한다(규칙 중복 금지) | 해석 |
| I-5 | REJECTED·FILE_REJECTED는 "확인 필요"가 아니라 별도 표시 "재매입 검토 대상"(정보 배지 — 실행은 ACQ-ADMIN-001). 종결된 기관 판정이고 자동 처리가 없기 때문 | 해석(표시 분류 — Reviewer 확인) |
| I-6 | 레코드는 쪽 단위(seq 오름차순 keyset, 최대 50)로 읽는다. 상세 응답은 첫 쪽을 같은 TX에 포함하고, 다음 쪽은 별도 읽기(그 쪽 안은 일관, 쪽 사이에는 늦은 결과가 보일 수 있음 — 화면에 "조회 시각" 표시) | 해석 — 대량 파일에서 응답 크기 상한(ADMIN 해석 상한)을 넘지 않게 |
| I-7 | 전송 시도·응답 처리 기록은 상세에 **최근 20건 + 전체 건수**만(UPLOAD_FAILED 반복 파일에서 응답이 커지지 않게) | 해석 |
| R-1 = A | 조회 구현 = **jOOQ codegen 도입**(§5.1) — C-2의 JPA native count 지표는 그대로 | **Coordinator 결정**(tasks.json coordinatorDecisions — 사용자 결정 아님) |
| R-2 = A | financial-batch 채널 수신 = `spring-boot-starter-security` 체인 2개 + 서명 필터(§3.2). 기존 Inbox·relay golden 시험 기대값 불변 | **Coordinator 결정** |
| R-3 | ADMIN 모듈 = 새 `financialops`(FINANCIAL 채널 소유), 자체 permit pool·설정 — B2 | **Coordinator 결정** |
| R-4 | 검색은 본문 POST(FINANCIAL `POST …/acquisition-file-searches`·`…/record-searches`, ADMIN 화면도 같은 방식 — 서명 채널 query 금지) | **Coordinator 결정** |
| I-8 | FINANCIAL admin-channel 설정 **필수**(없으면 기동 실패 — MASTER와 같은 규칙) | 해석(B1 적용 — 시험 설정·`.env.example`에 키 추가) |
| I-10 | (B1) 기본 보안 체인의 거부 응답은 **404 빈 본문**(진입 필터와 같은 존재 비노출 — 진입 필터가 먼저 404로 막으므로 이중 방어). ERROR dispatch는 허용(Spring 오류 응답 415 등의 원래 상태 유지 — payment-api와 같다) | 해석(가역) |
| I-11 | (B1) RESULT_OVERDUE 기준점 = FINANCIAL 시계(`AcquiringClock`) − `results.deadline`, 경계 포함(`transmitted_at ≤ 기준점`). 결과 마감 설정이 없으면 계산하지 않는다(fail-closed) | 해석 |
| I-12 | (B1) 레코드 결과 행의 응답은 (종류, 순번)으로만 가리킨다(응답 PK·파일명 비노출). 응답 처리 기록 최신순 = 응답 id 내림차순 | 해석 |
| I-13 | (B1) 색인 고정: 매입사 없는 기간 검색 `FORCE INDEX(ix_acquisition_file_business_date)`, 매입사 있음 `FORCE INDEX(uk_acquisition_file_lane_date_version)` + 환경 없으면 `environment IN ('TEST','LIVE')`(payment-api 관리 조회와 같은 이유 — 계획 변동으로 전체 스캔이 되지 않게) | 해석 |
| I-14 | (rev 0.3 — 리뷰 MINOR-1) 서명된 요청이 ADMIN 채널의 없는 경로·다른 method에 닿으면 `{"error":"NOT_FOUND"}`(404)·`{"error":"METHOD_NOT_ALLOWED"}`(405)·`UNSUPPORTED_MEDIA_TYPE`(415)·`NOT_ACCEPTABLE`(406)만 돌려준다(Boot 기본 오류 본문·path 없음). 구현은 `AdminChannelErrorResolver`(HandlerExceptionResolver, Spring 기본 resolver보다 앞, 경로 `/internal/v1/admin/`만). `@RestControllerAdvice`를 택하지 않은 이유: 경로·method 불일치 예외는 handler가 정해지기 전에 나서 패키지 범위 advice가 적용되지 않고, 범위 없는 advice는 결제 사실 Inbox의 Spring 오류 응답(415 Boot 본문)까지 바꾼다 — Inbox 동작 불변 지시. 기본 체인의 ERROR dispatch 허용(I-10)은 Inbox를 위해 유지 | 해석(가역 — Coordinator 지시의 구현 방식만 바꿈, Reviewer 확인) |
| C-ACQV-1 | D-ACQV-002의 범위는 사용자 질문 문구 그대로 **카드사 쪽 값**(승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부)이다. `generation`·`reacquisitionOf`·`originalTransactionId`·`eventAt`은 PayCore 내부 참조·거래 시각이라 그 범위가 아니며 표시한다 | **Coordinator 기록**(2026-11-06 — 사용자 결정 아님) |
| I-15 | (B2) ADMIN 감사 대상 참조(`admin_audit_event.target_ref`) 형식을 넓혔다: 기존 `[a-z]{3}_ULID`에 더해 **매입 파일 ID**(파일 계약 v1 문법 `ACQ.{매입사}.{환경}.{yyyyMMdd}.{vvv}` — 54자 이하, 열 VARCHAR(64))만 받는다(`AdminAuditEvent.TARGET_REF`). 둘 다 고정 문법 식별자라 자유 문구가 감사 행에 들어가지 않는다. 대안(파일 ID를 detail에 두고 targetRef 없음)은 감사 대상 검색 일관성이 떨어져 택하지 않았다 | 해석(감사 형식 — Reviewer 확인) |
| I-16 | (B2) "매입·대사" 메뉴 탭 순서 = [대사 발견, 매입 현황]: SECURITY_ADMIN·APPROVER의 메뉴 진입 화면(대사 발견)을 바꾸지 않는다. 조회 4역할 중 대사 권한이 없는 VIEWER·OPERATOR는 매입 현황 탭 하나만 본다(이제 그 메뉴가 보인다 — 기존 메뉴·홈 바로가기 시험 기대값을 그 이유로 갱신) | 해석(표시) |
| I-17 | (B2) 화면 상태·사유 한국어 이름은 화면 고유 표(`AcquisitionsScreen`)에 둔다(공용 `labels`에 넣으면 같은 code의 다른 화면 뜻과 섞인다 — TransactionsScreen과 같은 관례). 감사 행위 `ACQUISITION_FILE_VIEWED`만 공용 `AUDIT_ACTION_LABELS`('매입 파일 조회') | 해석(표시) |

### 2.1 기술 선택 메모(TECHNOLOGY-AND-PRINCIPLES §3–§5 — 이번 변경에 연결)

- **JPA**: 이번 작업은 매입 JPA Entity·Repository·Mapper를 바꾸지 않는다. 역할 → 매입 파일의 상태 전이(전송 축 `@Version`, 응답 TX의 `lockFile` = `SELECT … FOR UPDATE`)는 기존 JPA 명령 경로만 한다.
  예 → 화면이 상세를 여는 동안 결과 TX가 같은 파일을 COMPLETE로 바꿔도, 조회는 Entity를 영속성 컨텍스트에 올리지 않으므로 변경 감지·`@Version` 충돌·잠금 대기에 끼지 않는다.
  이유 → `JpaAcquisitionFileRepository`로 읽으면 Aggregate 복원(불변식 재검사)과 행 잠금 경로를 조회가 공유하게 되고, 봉인 바이트 BLOB Entity를 실수로 읽을 길이 생긴다(ACQUIRING-001 §6 — `@Lob` LAZY 미보장).
  주의 → 조회 DTO는 `application.provided`, Port 구현은 `adapter.query`. Controller에서 Entity를 직렬화하지 않는다(OSIV 꺼짐 — financial-batch `open-in-view: false` 유지). 순수 Domain(`acquiring.domain`)은 조회에 쓰지 않는다(`RecordResultRule.status`는 SQL과의 **동치 시험 기준**으로만 쓴다 — §8).
- **jOOQ CQRS**: R-1 권장 시 `JooqAcquisitionFileViewReader`(adapter.query — SELECT만, 모든 문장에 질의 시간 상한 hint, 상세는 한 RO·RR TX). jOOQ는 Boot 4.1.1 BOM 관리 3.21.7 그대로(새 버전 고정 없음).
- **버전·의존성**: 새 버전 없음. financial-batch에 BOM 관리 starter 2개(`spring-boot-starter-jooq`·`spring-boot-starter-security`)와 codegen source set(카탈로그 기존 `jooq-codegen`·`jooq-meta`·`testcontainers-mysql`)을 더한다 — payment-api·master-api와 같은 조합. resolved tree·CVE는 Verifier 확인(§11 L-6).
- **Kafka/Redis**: 쓰지 않는다. 조회는 FINANCIAL 소유 DB만 읽고, 속도 제한은 인스턴스 메모리 창(TXN L-2와 같음)이다. 캐시·메시지가 매입 상태의 권위(소유 DB)·감사를 대신하지 않는다.

## 3. ADMIN → FINANCIAL 서명 채널

### 3.1 계약(기존 v1 재사용 + 채널 전용 키)

- 서명 v1 그대로: 헤더 `PayCore-Admin-Signature: t=,kid=,v1=`, canonical `paycore.admin-call.v1\nMETHOD\npath\nt\nhex(sha256(body))`, 판정 순서 크기 → query → 문법 → 시각 창(300초 Demo) → kid → 서명,
  query 400 `QUERY_NOT_ALLOWED`·그 밖 401 `UNAUTHENTICATED`(원인 비구별), 본문 16 KiB(서명 전 413), nonce 없음(조회는 부작용 없음), 응답 `Cache-Control: no-store`.
- golden `admin-call-signature-v1.golden.json`을 financial-batch `src/test/resources/contracts/`에 같은 바이트로 복사하고 harness `test_admin_call_signature_golden.py` TARGETS에 더한다(경로 무관 계산 — golden 파일 내용 불변).
- **키 분리**(채널별 별도 키): FINANCIAL `paycore.financial.admin-channel.signing-keys[1..2]{key-id, secret}`(Base64 ≥ 32바이트, 기본값 없음, toString 마스킹). 기동 거부: 그 kid 또는 바이트가
  결제 사실 Inbox 키(`payment-fact-inbox.signing-keys`)와 같음, context path 비어 있지 않음. ADMIN `paycore.admin.financial-ops.{base-url, key-id, signing-key, connect-timeout, read-timeout,
  permits, lookup-per-minute}` — 기동 거부: payment-ops·master-ops 키와 kid/바이트 같음, LIVE + Demo/golden 키. 두 쪽 모두 설정 없으면 기동 실패(MASTER와 같음 — I-8).
- I-8: FINANCIAL 수신 설정을 **필수**로 둔다(MASTER-OPS-001과 같은 규칙 — 빠진 설정이 조용히 "채널 꺼짐"이 되지 않게). 영향: financial-batch 시험·Demo·E2E 설정에 Demo 키 추가.

### 3.2 financial-batch 수신 구조(R-2)

현재 financial-batch에는 Spring Security가 없고, `PaymentFactInboxEntryFilter`(최우선 필터)가 `POST /internal/v1/payment-facts`와 `GET /actuator/health` 밖을 404로 막으며 Inbox 서명은 컨트롤러가 검사한다.

| 항목 | 권장(R-2 A) | 대안(B) |
|---|---|---|
| 방식 | `spring-boot-starter-security`(BOM) + 체인 2개: ① `/internal/v1/admin/**` STATELESS·CSRF/form/basic/anonymous 끔·서명 필터 → `ADMIN_SERVICE`, ② 그 밖: `POST /internal/v1/payment-facts` permitAll(정확 경로 — 기존 컨트롤러 HMAC 유지)·`GET /actuator/health` permitAll·나머지 denyAll. `UserDetailsServiceAutoConfiguration` 제외 | Spring Security 없이 서블릿 서명 필터 하나 |
| 근거 | payment-api·master-api 수신 측과 같은 구조(리뷰·시험 재사용, 권한 규칙 자체를 시험 가능) | 의존성 0. 그러나 세 번째 다른 방식이 생긴다 |
| 진입 필터 | `PaymentFactInboxEntryFilter` 허용 목록에 `/internal/v1/admin/` 접두(GET·POST)만 추가(크기·query 판정은 채널 필터 몫). 그 밖 404 유지 | 같음 |
| 회귀 위험 | 보안 기본 헤더가 Inbox 응답에 붙는다 — 기존 Inbox spec·relay golden `statusCases`를 그대로 다시 돌려 확인(기대값 변경 금지) | 없음 |

- 조회 permit `paycore.financial.admin-channel.read-permits`(1..16, Demo 2, 대기 0 — 없으면 503 `ADMIN_READ_BUSY` + `paycore.financial.admin_channel.busy{endpoint}`). Demo 값을 2로 둔 이유: Hikari pool(기본 10)을 Inbox·스케줄러와 나눠 쓰므로 관리 조회가 결제 사실 수신 연결을 빼앗지 않게.
- 질의 시간 상한: 모든 SELECT에 `/*+ MAX_EXECUTION_TIME(2000) */`(payment-api `AdminReadLimits`와 같은 값), 3024 → 503 `TEMPORARILY_UNAVAILABLE` + `…admin_channel.query_timeout{endpoint}`. 그 밖 저장소 실패도 503(로그는 분류만).
- 지표 label은 endpoint·result·reason 고정 분류만(fileId·MID·거래 ID 없음).
- 포트: 기존 내부 포트(`PAYCORE_FINANCIAL_INBOX_PORT`, 내부망 바인딩)를 같이 쓴다. 공개 노출 없음(mTLS는 LIVE Gate).

### 3.3 FINANCIAL 내부 API(서명 필요 — `acquiring.adapter.web.admin.AdminAcquisitionController`)

| 메서드·경로 | 응답 |
|---|---|
| `POST /internal/v1/admin/acquisition-file-searches` | 200 `{"items":[…],"nextCursor":{"businessDate","fileId"}\|null}` / 400 `REQUEST_MALFORMED` |
| `GET /internal/v1/admin/acquisition-files/{fileId}` | 200 상세(§4.2) / 404 `ACQUISITION_FILE_NOT_FOUND`(형식 아닌 ID 포함) |
| `POST /internal/v1/admin/acquisition-files/{fileId}/record-searches` | 200 `{"items":[레코드…],"nextCursor":seq\|null}` / 400 / 404 |

공통 503 `ADMIN_READ_BUSY`·`TEMPORARILY_UNAVAILABLE`. 오류 본문 `{"error":"<CODE>"}`만(요청 값 echo 없음). fileId 형식 `^ACQ\.[A-Z0-9]{1,32}\.(TEST|LIVE)\.[0-9]{8}\.[0-9]{3}$`(ADMIN은 이 검사 뒤에만 경로를 만든다).

검색 본문(엄격 — TXN §3.1과 같은 자료형 규칙: 알려진 키만, 중복 key·`null`·뒤따르는 토큰·강제 변환 거부):

| 키 | 형식 |
|---|---|
| `businessDateFrom`·`businessDateTo` | `yyyy-MM-dd`, from ≤ to ≤ from + 30일(31일 포함) — `fileId`가 없으면 필수 |
| `fileId` | 위 형식 — 있으면 다른 키 금지(정확 조회) |
| `acquirerCode` | `^[A-Z0-9]{1,32}$` |
| `environment` | `TEST`·`LIVE` |
| `transmissionState`·`receiptState`·`resultState` | 각 축 CHECK 값 집합(V5·V7) |
| `needsAttention` | JSON `true`만(있으면 §4.4 사유가 하나 이상인 파일만) |
| `after` | `{"businessDate","fileId"}` 정확히 두 키 |
| `limit` | 정수 1..50(없으면 20) |

레코드 검색 본문: `status`(AWAITING·ACCEPTED·REJECTED·FILE_REJECTED·UNKNOWN·CONFLICT, 선택)·`afterSeq`(정수 ≥ 0)·`limit`(1..50, 기본 20).
정렬: 파일 `ORDER BY business_date DESC, file_id DESC`(keyset `(business_date, file_id) < cursor`, `limit + 1`로 다음 쪽 판정), 레코드 `seq ASC`(`seq > afterSeq`).

## 4. 화면·노출

### 4.1 목록 항목

`fileId, acquirerCode, environment, businessDate, fileVersion, originalFileId|null, recordCount, saleCount, saleAmountMinor, cancelCount, cancelAmountMinor, netAmountMinor, currency("KRW"),
transmissionState, receiptState, resultState, sealedAt, transmittedAt|null, recordStatusCounts{awaiting, accepted, rejected, fileRejected, unknown, conflict}, attention[사유 코드], reacquisitionCandidate(bool — I-5)`.
시각은 `…T…Z` ms 3자리 UTC 문자열(`DATE_FORMAT` — TZ-FIX-001), 화면은 KST로 표시. 찾음/못 찾음 응답 모양 동일.

### 4.2 상세(한 번의 일관 읽기 — 한 RO·RR TX)

```
file {목록 항목 전부 + cutoffPolicyVersion, cutoffAt, contentSha256, contentBytes, transmissionLeaseUntil|null, updatedAt}
attempts {total, latest[≤20] {attemptNo, resumeState, claimedAt, leaseUntil, outcome|null, observed|null, evidence|null, reason|null, resultingState|null, decidedAt|null}}   // 결과 없음 = 진행 중·SUPERSEDED 전
responses {total, quarantined, latest[≤20] {kind(ACK|RESULT), sequenceNo, disposition, quarantineReason|null, ackOutcome|null, ackReasonCode|null, evidence|null, institutionAt|null, observedAt}}
records {첫 쪽 — §4.3, nextCursor}
readAt   // 조회 시각(I-6)
```

### 4.3 레코드 항목(Q-ACQV-02 권장안)

`seq, recordType(SALE|CANCEL), transactionId, originalTransactionId|null, merchantAccountId(= merchantRef, D-ACQ-008), institutionTransactionDate, eventAt, currency, approvedAmountMinor,
nettedCancelAmountMinor, recordAmountMinor, generation, reacquisitionOf|null, status, results[{entryNo, result, observedResult|null, reasonCode|null, source, responseRef{kind, sequenceNo}|null, recognizedAt}]`.
`status`는 SQL로 계산하되 `RecordResultRule.status`와 같은 정의다(CONFLICT 행 있음 → CONFLICT, 없으면 entry_no 순 첫 ACCEPTED/REJECTED 행 — 출처 FILE_REJECTED면 FILE_REJECTED, 결정 없이 UNKNOWN 행 → UNKNOWN, 행 없음 → AWAITING).

### 4.4 "확인 필요" 사유(서버 계산 — I-4)

| 코드 | 조건 | 화면 문구(예) |
|---|---|---|
| `TRANSMISSION_UNKNOWN` | 전송 축 = TRANSMISSION_UNKNOWN | 전송 결과를 알 수 없음 — 자동 확인 중, 대사 대상 |
| `TRANSMISSION_QUARANTINED` | 전송 축 = TRANSMISSION_QUARANTINED | 같은 파일 ID에 다른 내용 — 격리, 운영자 확인 |
| `RECEIPT_CONFLICT` | 수신 축 = RECEIPT_CONFLICT | 기관이 받은 내용이 봉인과 다름 — 격리 |
| `RESPONSE_QUARANTINED` | 이 파일의 응답 처리 기록 중 disposition = QUARANTINED가 있음(RECORD_MISMATCH 등 — 수신 축은 그대로인 격리) | 격리된 기관 응답 n건 |
| `RECORD_UNKNOWN` | status = UNKNOWN 레코드가 있음 | 결과 모름 n건 — 대사 대상(D-ACQ-004) |
| `RECORD_CONFLICT` | status = CONFLICT 레코드가 있음 | 상충 결과 n건 — 격리(D-ACQ-010) |
| `RESULT_DEADLINE_PASSED` | 결과 축 = DEADLINE_PASSED | 결과 마감 지남 — 늦은 결과 대기 |
| `RESULT_OVERDUE` | 결과 축 AWAITING·전송 완료·`transmitted_at + results.deadline ≤ now`(마감 처리 회차 전), deadline 설정이 없으면 계산하지 않음 | 결과 마감 시각이 지났으나 아직 마감 처리 전 |

`now`는 FINANCIAL 시계(`AcquiringClock`). 사유는 표시 분류일 뿐 상태를 바꾸지 않는다.

### 4.5 숨기는 값(읽는 SQL에 열 자체가 없다)

`acquisition_file_content.content`(봉인 바이트)·`acquisition_response.content`(응답 바이트)·`acquisition_response.file_name`(응답은 kind + sequenceNo로 표시)·`acquisition_file.sender_id`(기관 등록 송신자 ID),
레코드 `approval_number`·`provider_transaction_id`·`original_provider_transaction_id`·`issuer_code`·`card_type`·`plan_kind`·`installment_months`(Q-ACQV-02 권장), SFTP 호스트·포트·사용자·키 경로·지문·inbox/outbox 경로(DB에 없음 — 설정에도 노출 경로 없음), 카드번호(FINANCIAL에 열 없음).
로그·예외·지표 label에 fileId·MID·거래 ID·서명·키를 넣지 않는다.

### 4.6 ADMIN 호출 측·화면 API(R-3 `admin.financialops`)

- `HttpFinancialOpsGateway`: 같은 서명 v1(별도 `FinancialCallSigner`, 다른 키), 자체 permit(대기 0 — FINANCIAL 장애가 결제·가맹점 조회를 막지 않음), DB TX 안 호출 `IllegalStateException`, 응답 128 KiB + 1바이트까지만 읽음(I-6 상한 계산: 레코드 50 × 약 600바이트 + 기록 40건), 실패는 값(`Unavailable(reason)`).
- 응답 검증 `FinancialResponseShape`(허용 필드·정규식·값 집합·정수 범위·목록 상한 — 모르는 필드 fail-closed, **문법 = FINANCIAL 저장 문법**(§4.7)) + **요청-응답 대응 검사**(TXN MINOR-1과 같음: 항목 수 ≤ limit, 필터 일치, businessDate ∈ 기간, cursor보다 뒤, 상세 fileId = 요청;
  rev 0.5 — B2 리뷰 MINOR-3: 목록 (업무일, 파일 ID) 내림차순·`nextCursor`가 있으면 쪽이 가득(= limit)이고 마지막 항목과 같다, 레코드 쪽 seq 오름차순·`nextCursor`가 있으면 쪽이 가득이고 마지막 seq, 상세의 레코드 첫 쪽 = 기본 조건(afterSeq 0·상태 없음·20건)의 답).
- 해석되지 않은 검색·레코드 검색 본문(rev 0.5 — B2 리뷰 MINOR-2): 컨트롤러가 400으로 끊지 않고 Use Case `rejectMalformedSearch`로 넘긴다 — 조회 역할이 없거나 계정이 ACTIVE가 아니면 403, 있으면 400(감사·FINANCIAL 호출·속도 제한 계수 없음, TXN `rejectMalformedSearch`와 같은 규칙).
- 화면 API(세션, `Cache-Control: no-store`, POST는 CSRF): `POST /api/acquisition-file-searches`(VIEW_MASKED, 미감사) · `GET /api/acquisition-files/{fileId}`(VIEW_MASKED, 감사 후 반환) ·
  `POST /api/acquisition-files/{fileId}/record-searches`(VIEW_MASKED, 감사 후 반환). 오류: 400 `INVALID_INPUT`·403·404 `ACQUISITION_FILE_NOT_FOUND`·413(16 KiB, 해석 전)·415·429 `RATE_LIMITED`·503 `FINANCIAL_UNAVAILABLE`·`AUDIT_UNAVAILABLE`.
- 감사(Q-ACQV-01 권장): FINANCIAL이 답한 경우(FOUND → OK, NOT_FOUND → NOT_FOUND)만 `ACQUISITION_FILE_VIEWED`(actor, 시각, targetRef = fileId, detail 없음) — 감사 쓰기가 끝난 뒤에만 반환, 감사 실패 → 503 `AUDIT_UNAVAILABLE`. 감사 action 열은 자유 문자열(ADMIN DB migration 불필요).
- 화면: 메뉴 "매입·대사"에 탭 **"매입 현황"**(page `acquisitions`, VIEW_ROLES) 추가 — 기존 "대사 발견" 탭 권한 불변, 메뉴 설명 문구 갱신. 필터(업무일 기간 KST·매입사·환경·3축 상태·확인 필요만·파일 ID), 표(업무일·매입사·버전·건수·순액·3축 배지·레코드 결과 요약·확인 필요/재매입 검토 배지), 다음 쪽.
  상세 패널: 요약·3축 상태와 시각·확인 필요 사유 목록(§4.4 문구)·전송 시도 표·응답 처리 표·레코드 표(상태 필터·다음 쪽)·조회 시각. 조작 버튼 없음. 새 npm 의존성 없음.

### 4.7 응답 문법 대조표(rev 0.5 — B2 리뷰 MAJOR-1, FINANCIAL 저장 문법 = ADMIN 허용 문법)

ADMIN `FinancialResponseShape`가 FINANCIAL이 정상 저장할 수 있는 값보다 **좁으면** 그 파일은 화면에서 영구 503이 되고(재시도로 풀리지 않음), **넓으면** FINANCIAL이 저장할 수 없는 값(오류·다른 출처)을 받아 준다. 그래서 열마다 FINANCIAL 쪽 근거(Domain `AcquisitionText`·파일 헤더 문법·migration CHECK)와 같게 둔다.

| 응답 필드 | FINANCIAL 저장 문법(근거) | rev 0.4 ADMIN | rev 0.5 ADMIN | 판정 |
|---|---|---|---|---|
| 결과 행 `reasonCode` | `^[A-Z0-9_]{1,32}$`(Domain `TOKEN` — `AcquirerResultFile`, V10 `ck_acquisition_record_result_reason`) | `^[A-Z][A-Z0-9_]{0,47}$` | `^[A-Z0-9_]{1,32}$` | **좁음+넓음 → 수정**(숫자 시작 `05` 거부·33~48자 허용) |
| 응답 `ackReasonCode` | `^[A-Z0-9_]{1,32}$`(Domain `TOKEN` — `AcquirerAck`, 열 VARCHAR(32)·정규식 CHECK 없음) | 같은 CODE | `^[A-Z0-9_]{1,32}$` | **좁음+넓음 → 수정**(`1001` 거부) |
| `transactionId`·`originalTransactionId`·`merchantAccountId` | `^[A-Za-z0-9._:#-]{1,64}$`(Domain `ID`, 열 VARCHAR(64)) | `^[A-Za-z0-9._:-]{1,64}$` | `^[A-Za-z0-9._:#-]{1,64}$` | **좁음 → 수정**(`#` 거부) |
| `reacquisitionOf` | `^[A-Za-z0-9._:#-]{1,64}$`(Domain `ID`, VARCHAR(64), 값 = `fileId#seq` — V12) | `{1,80}` | `{1,64}` | **넓음 → 수정** |
| `acquirerCode` | `^[A-Z0-9]{1,32}$`(Domain `CODE`, V5·V10 CHECK) | 같음 | 같음 | 일치 |
| `fileId`·`originalFileId`·cursor `fileId` | 파일 계약 v1 `ACQ.{CODE}.{TEST 또는 LIVE}.{yyyyMMdd}.{3자리}` | 같음 | 같음 | 일치 |
| `cutoffPolicyVersion` | `^[A-Za-z0-9._-]{1,64}$`(`AcquisitionFileHeader.POLICY_VERSION`, VARCHAR(64)) | 같음 | 같음 | 일치 |
| `contentSha256` | `^[0-9a-f]{64}$`(V5 CHECK) | 같음 | 같음 | 일치 |
| `fileVersion` | 1..999(V5 `ck_acquisition_file_version`) | ≥ 1 | 1..999 | 넓음 → 수정 |
| 응답 `sequenceNo`·결과 행 `responseRef.sequenceNo` | 1..99(V10 `ck_acquisition_response_seq`) | ≥ 1 | 1..99 | 넓음 → 수정 |
| 시도 `resumeState` | SEALED·TRANSMISSION_UNKNOWN(V7) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 시도 `outcome` | DELIVERED·NOT_DELIVERED·UNKNOWN·CONTENT_MISMATCH·SUPERSEDED(V9) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 시도 `observed` | DELIVERED·NOT_DELIVERED·UNKNOWN·CONTENT_MISMATCH(V9) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 시도 `evidence` | RENAMED_AND_VERIFIED·VERIFIED_FINAL_PRESENT·RENAMED(V7) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 시도 `reason` | CONNECT_FAILED … RENAMED_BUT_ABSENT·LEASE_EXHAUSTED·INTERNAL_ERROR 10개(V9) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 시도 `resultingState` | SEALED·TRANSMITTING(SUPERSEDED만)·TRANSMITTED·TRANSMISSION_UNKNOWN·TRANSMISSION_QUARANTINED(V9) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 응답 `disposition`·`quarantineReason` | APPLIED·QUARANTINED / FORMAT_INVALID … TRANSMISSION_QUARANTINED 11개(V10 + V14) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 응답 `ackOutcome`·`evidence` | RECEIVED·FILE_REJECTED·RECEIPT_CONFLICT / ACK_HASH_MATCH·RESULT_HASH_MATCH(V10) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 결과 행 `source` | RESULT_FILE·FILE_REJECTED·DEADLINE(V10) | 일반 CODE | 값 집합 | 넓음 → 수정 |
| 결과 행 `result`·`observedResult`, 레코드 `recordType`·`status`, 파일 3축·환경·통화·`attention` | 각 CHECK·Domain 값 집합 | 값 집합 | 같음 | 일치 |
| 금액·건수·`seq`·`entryNo`·`attemptNo`·`generation` | 각 CHECK 하한(금액 ≥ 0·순액 부호 있음·seq/entry/attempt ≥ 1·generation ≥ 0) | 같음 | 같음 | 일치(상한은 JSON 정수 범위 검사) |

열 사이 조합 규칙(예: evidence는 DELIVERED일 때만, ackReasonCode는 RECEIVED가 아닐 때만)은 FINANCIAL CHECK의 몫이다 — ADMIN은 열마다 값 집합만 본다. 시험: `FinancialOpsGatewaySpec` "values inside the FINANCIAL storage grammar"(숫자 사유·`#` Found, 33자·소문자 사유·65자 ID 거부, 열마다 저장 가능한 값 전부 Found·집합 밖·범위 밖 거부), FINANCIAL 쪽은 `AcquisitionFileViewReaderSpec`이 숫자 사유(`05`·`1001`)를 실제 저장·조회한다. FINANCIAL이 값 집합을 늘리는 migration은 이 표와 ADMIN 모양을 함께 바꾼다(그 전까지 fail-closed 503 — L-7).

## 5. 조회 구현·DB

### 5.1 R-1 jOOQ vs JdbcClient/JPA native(Coordinator 결정)

| 안 | 내용 | 장점 | 비용·위험 |
|---|---|---|---|
| **A(권장) jOOQ codegen 도입** | payment-api·master-api와 같은 codegen source set(Testcontainers MySQL 8.4.11 + Flyway `db/migration/financial`만, 대상 `acquisition_.*` 표), `adapter.query.Jooq…Reader` | 사용자 지정 기술("복잡한 CQRS 조회 jOOQ")을 그대로 지킨다. 이번 조회는 필터 조합·keyset·레코드 상태 집계·여러 표 일관 읽기라 "복잡한 조회"에 해당. ArchUnit `queryAdapterDoesNotWriteWithJooq` 재사용. C-2 기록("운영 조회 화면이 생기면 다시 검토")의 그 시점 | 컴파일에 Docker 필요(codegen 컨테이너), 빌드 시간·메모리 증가(첫 빌드 수십 초, 이후 migration 미변경 시 up-to-date). Demo 실행 중 빌드 금지(§9.4) |
| B JdbcClient(또는 JPA native) 읽기 전용 Reader | `adapter.query.Jdbc…Reader`, 문자열 SQL + RowMapper | 의존성·codegen 없음, 빌드 가벼움 | 열 이름 오타·스키마 변경을 컴파일이 못 잡는다(시험만). 사용자 지정 기술과 다르므로 **B를 고르면 사용자에게 알리고 기록**해야 한다(C-2는 count 지표에 한정된 결정) |

어느 안이든 Port(`AcquisitionFileViewReader` — application.required, 제공 `AcquisitionFileViewQuery`)는 같아서 나중에 바꿀 수 있다. JPA Entity로 읽는 안은 택하지 않는다(§2.1).

### 5.2 runtime 권한

변경 없음. runtime role `paycore_financial_runtime`은 매입 표 전부에 이미 SELECT가 있다(V6: lane·file·content·record·netting·exclusion, V8: attempt·outcome, V11: response·record_result·cancel_not_required, V13: reacquisition_approval, V15: accepted_record).
읽기 전용 TX(`SET TRANSACTION READ ONLY` — 쓰기 시도 1792)와 Reader SELECT 전용 규칙이 쓰기를 막는다. 별도 조회 계정은 만들지 않는다(payment-api·master-api 관리 조회와 같음 — I-9).

### 5.3 색인 migration

`db/migration/financial/V16__acquisition_file_admin_index.sql`: `ALTER TABLE acquisition_file ADD INDEX ix_acquisition_file_business_date (business_date, file_id), ALGORITHM=INPLACE, LOCK=NONE`
(매입사 없는 기간 검색 — 매입사·환경이 있으면 기존 UNIQUE `(acquirer_code, environment, business_date, file_version)` 사용). 데이터·권한 변경 없음. 상세 하위 표는 기존 PK·`ix_acquisition_response_file`로 충분.
기대값 변경(정책 변경 아님): financial-batch migration 버전 목록을 단언하는 시험(`FinancialDatabaseAccountSpec` 등)에 16 추가. 상태·needsAttention 필터는 색인 없는 잔여 조건(31일 상한이 범위를 묶는다 — L-2).

## 6. 8대 원칙

| 원칙 | 이번 작업의 의미 | 증거(시험 계획 — §8 이름) |
|---|---|---|
| Financial Consistency | 매입 상태·금액을 바꾸지 않는다. 상세는 한 스냅샷(찢어진 상세 없음), 레코드 상태 계산 = Domain 규칙 | `AcquisitionFileViewReaderSpec` RO·RR 스냅샷 probe(첫 문장 뒤 다른 연결의 결과 반영 TX 커밋에도 뒤 문장이 이전 스냅샷), 상태 SQL ↔ `RecordResultRule.status` 동치 표 |
| Transaction Safety | FINANCIAL 호출은 ADMIN DB TX 밖, 감사는 ADMIN 로컬 TX, 감사 실패 시 결과 미반환 | admin `AcquisitionLookupServiceSpec`(TX 경계·감사 순서 — 변이로 확인), `FinancialOpsGatewaySpec` TX 안 호출 거부, `AcquisitionAuditFailureHttpSpec` |
| Idempotency | 조회는 부작용 없음 | `AdminAcquisitionApiSpec` 같은 요청 반복 = 같은 본문 + FINANCIAL 매입 표 `COUNT(*)`·`CHECKSUM TABLE` 전후 동일 |
| Concurrency Control | 대기 0 permit(FINANCIAL·ADMIN 각각), keyset 동률 처리 | `AdminAcquisitionControllerSpec` busy, Reader keyset 동률(같은 업무일 여러 파일) 누락·중복 없음, Gateway busy |
| Recoverability | FINANCIAL 장애·시간 초과 = 503 값, 재시도 안전 | Controller 3024·저장소 실패 503(예외 문구 로그 없음), Gateway timeout·연결 실패·비정상 응답 = Unavailable |
| Reconciliation | 해당 없음(대사 기능 아님 — RECON). UNKNOWN·CONFLICT·격리를 한 화면에 모아 대사 착수를 돕는다(표시만) | Reader "확인 필요" 사유별 Fixture 시험 |
| Auditability | 상세·레코드 쪽 조회 감사(반환 전), 목록 미감사(Q-ACQV-01) | admin Service·HTTP spec 감사 행(실제 MySQL) |
| Observability | 채널 인증·busy·질의 시간 초과·본문 거절·속도 제한 지표(고정 label), 로그 비노출 | API spec 로그 비노출(서명 v1·키·fileId·MID), 지표 단언. 매입 적체 지표(ACQUIRING-001 `AcquiringMetrics`)는 그대로 — 화면이 지표·경보를 대신하지 않는다 |

## 7. 수락 기준(시험으로 확인)

1. FINANCIAL: 새 경로 무서명·틀린 서명·오래된 t·다른 채널 키(결제 사실 키·PAYMENT ops 키) 401, query 400, 16 KiB 초과 413, 기존 결제 사실 Inbox 동작 불변(기존 spec 기대값 그대로), 그 밖 경로 404.
2. 기동 거부: admin-channel 설정 없음·키 형식·Inbox 키와 kid/바이트 같음·context path. ADMIN: financial-ops 설정 없음·payment/master ops 키 공유·LIVE + Demo 키.
3. 검색 본문 엄격성·모드(파일 ID 정확 조회 단독, 그 밖 기간 필수·31일 경계·역전) → 400(값 echo 없음).
4. 목록 필터 정확 일치·keyset(동률)·limit·`needsAttention`·찾음/못 찾음 같은 모양. 상세 §4.2 모양·한 RO·RR TX·Asia/Seoul JVM에서 UTC 그대로·없는 ID 404. 레코드 쪽 status 필터·keyset.
5. "확인 필요" 8사유 각각 Fixture에서 나타나고, 정상 파일(TRANSMITTED·RECEIVED·COMPLETE·모두 ACCEPTED)에는 없다.
6. **비노출**: 응답 바이트에 봉인·응답 바이트, sender_id, 승인번호·기관 거래번호·발급사(Q-ACQV-02 권장 시)가 없다 — 대조군으로 같은 DB에 그 값이 실제로 있음을 확인.
7. 읽기 전용: FINANCIAL 표 불변(checksum), 질의 시간 상한 hint가 모든 문장에 있음, 3024 → 503.
8. ADMIN: 서명 헤더 독립 검증(대역이 golden 시험 키로), 모양·대응 위반 fail-closed, 과대 응답·timeout → Unavailable, 역할(VIEW_MASKED 4역할 허용, UNMASK 단독·역할 없음·비활성 403 — FINANCIAL 미호출), 감사(§4.6), 413·415·429, CSRF.
9. 화면: 조회 역할에 "매입·대사 > 매입 현황", 필터·다음 쪽·상세 패널·확인 필요 표시·조작 버튼 없음, Vitest.
10. Demo: §9 세 시나리오 PASS(acquiring_e2e.py exit 0) + 목록·상세 캡처.

## 8. 시험 계획(TDD 순서 — 운영 코드는 `TODO()` stub으로 두고 시험 먼저 RED 기록)

1. (R-1 A이면) **B0 codegen**: financial-batch jOOQ codegen + 기존 시험 전부 통과(동작 변경 없음 — RED 없음, 특성화).
2. financial-batch(실제 MySQL·실제 봉인/전송/응답 서비스로 만든 Named Fixture — Fixture Monkey는 레코드 금액 연관 불변식 때문에 쓰지 않는다):
   `FinancialAdminCallSignatureGoldenSpec`(golden 대조) → `FinancialAdminChannelSettingsSpec`(기본값 없음·키 분리·마스킹·context path) → `FinancialAdminChannelSecuritySpec`(체인 규칙 자체·401·400·413·404·Inbox 회귀) →
   `AcquisitionFileSearchRequestParserSpec` → `AcquisitionFileViewReaderSpec`(필터·keyset·사유 8종·상태 동치·스냅샷·Asia/Seoul·EXPLAIN 색인·비노출 대조군) → `AdminAcquisitionControllerSpec`(busy·3024·저장소 실패·400/404) →
   `AdminAcquisitionApiSpec`(실제 체인 + 응답 모양·Idempotency checksum·로그 비노출) → `ArchitectureSpec`(adapter.query 규칙·진입 컨트롤러 목록) → migration 버전 기대값(16).
3. internal-admin: `FinancialOpsSettingsSpec` → `FinancialOpsGatewaySpec`(`StubFinancialOpsServer`가 golden 시험 키로 서명 검증, 모양·대응 fail-closed, permit·timeout·과대 응답·로그) →
   `AcquisitionLookupServiceSpec`(역할 행렬·형식 규칙·감사 순서·TX 경계·속도 제한) → `AcquisitionLookupHttpSpec`(실제 세션·MySQL 감사 행) → `AcquisitionSearchBodyLimitSpec`·`AcquisitionAuditFailureHttpSpec` → ArchitectureSpec.
4. 핵심 변이(각각 RED 확인 후 원복·sha 대조): 감사 전 반환, 레코드 status SQL의 CONFLICT 우선 제거, keyset `<` → `<=`, 숨김 열 SELECT 추가, `needsAttention`에서 RECORD_UNKNOWN 누락, VIEW_ROLES에 UNMASK 추가.
5. frontend: `AcquisitionsScreen.test.tsx`(목록·필터 본문·다음 쪽·상세·확인 필요 문구·버튼 없음·503 안내), `navigation.test.ts`(탭)·`labels.test.ts`(`ACQUISITION_FILE_VIEWED`)·`App.test.tsx`·`noRuntimeStyles.test.tsx` 기대값 추가.
6. harness: golden TARGETS 추가(`test_admin_call_signature_golden.py`), `harness/commands.json` minTests 하한 + 추가 수(낮추지 않음).
7. Demo 확인(§9) — 시험이 아니라 화면 증거. Simulator 성공은 운영 준비 완료가 아니다.

## 9. Demo E2E·화면 캡처 계획(Coordinator 실행 — 운영 키·운영 endpoint 금지)

### 9.1 준비(Q-ACQV-03 사용자 확인 뒤에만)
1. Demo 앱 정지 → 빌드(Gradle 데몬은 빌드 뒤 `--stop`) → 시작 순서대로 기동. 지금 Demo에는 financial-batch 실행 스크립트·설정이 없고 payment-api에 FINANCIAL Relay 설정이 없다.
2. **기존 데이터 삭제 없이 권한만 수렴**: (a) `paycore-demo-mysql-1`에서 `01-owned-databases.sh` 재실행(FIN-DBACCOUNT-001 §3 — FINANCIAL migration 계정·role·procedure 생성, PAYMENT·MASTER·ADMIN 권한은 같은 값으로 수렴),
   (b) `paycore-demo-simulator-mysql-1`에서 `01-simulator-database.sh` 재실행(2-E 열 권한 procedure). 재실행 안전(IF NOT EXISTS·REVOKE IF EXISTS). 실행 전 볼륨 이름·백업 여부를 확인하고, 볼륨 삭제는 하지 않는다.
3. Demo 키·설정(저장소 밖 `/tmp/paycore-demo`, 값은 출력하지 않음): 생성 스크립트가 `extra.env`에 덧붙인다 — FINANCIAL 결제 사실 채널 키, **ADMIN↔FINANCIAL 채널 키(새)**, financial-batch DB 비밀번호 변수 참조.
   SFTP 키: `/tmp/paycore-demo/acquirer/`(umask 077)에 Simulator host 키·FINANCIAL 클라이언트 키쌍(`ssh-keygen -t ecdsa -b 256 -N ""`), host 지문은 설정 파일로만 전달. 새 스크립트 `run-financial.sh`·`financial-json.py`, `payment-json.py`에 `financial-relay`, `run-sim.sh`에 매입사 변수.
4. Demo 마감 정책(Demo 값, 운영값 아님): 오늘 업무일에 새 정책 버전 `demo-e2e-<yyyyMMdd>`(effective-from = 오늘, cutoff-time = 결제 생성 예정 시각 + 10분 KST) — 빈 FINANCIAL DB라 소급 변경 거부(I-21)에 걸리지 않는다. 스케줄 `first-business-date` = 오늘, `results.deadline` PT3M.
   Simulator 시나리오: 13013원 REJECT(DEMO_REJECTED), 17017원 DELAY_RESULT PT6M.
5. `.env.example`(저장소)에는 새 변수 **이름**(`PAYCORE_FINANCIAL_ADMIN_CHANNEL_KEY_ID`·`…_KEY_BASE64`, `PAYCORE_ADMIN_FINANCIAL_OPS_BASE_URL`·`…_KEY_ID`·`…_SIGNING_KEY`)과 JSON 예시만 추가(값 없음).

### 9.2 실행
1. 결제창으로 TEST 결제 3건(12000·13013·17017원 — DEMO-SEED-001 가맹점) → payment-api Relay → FINANCIAL Inbox.
2. 마감 뒤 스케줄러가 봉인 → 전송 → 수신. 관찰: `python3 harness/e2e/acquiring_e2e.py --scenario normal|reject|missing-result …`(ACQUIRING-001 §16 명령 그대로, 각 exit 0).
3. 캡처(관리자 VIEWER 계정 — 비밀번호·TOTP는 출력하지 않음): ① 목록(필터 오늘), ② 정상 파일 상세(TRANSMITTED·RECEIVED·ACCEPTED), ③ REJECTED 레코드 + 재매입 검토 배지,
   ④ 결과 마감 직후 `RECORD_UNKNOWN`·`RESULT_DEADLINE_PASSED` 확인 필요 표시, ⑤ 늦은 결과 뒤 ACCEPTED(UNKNOWN 행 보존이 결과 이력에 보임), ⑥ 감사 화면의 `매입 파일 조회` 행.
4. 끝: Demo 정리는 Coordinator 판단(스크립트는 아무것도 지우지 않는다).

### 9.3 메모리(5 JVM — 호스트 7.8 GB)
- 상한: payment 512m·master 384m·admin 384m·simulator 256m(지금 Demo 스크립트 값) + financial 384m(새) `-Xmx` — 힙 합계 약 1.9 GB, JVM 비힙 포함 약 3 GB 예상 + `-XX:+UseSerialGC -XX:ReservedCodeCacheSize=64m -Xss512k`. MySQL 2개 + Docker Desktop 포함.
- Demo 실행 중 Gradle 빌드·시험 금지(데몬 1~2 GB). 기동 전 `free -m`으로 가용 1 GB 미만이면 중단. 결제 생성 뒤 master-api를 멈춰도 E2E는 돈다(가맹점 화면만 503) — 필요 시 선택.
- 한 번에 하나씩 기동하고 health 확인 뒤 다음 앱.

## 10. 사용자 질문(Coordinator 경유) — rev 0.2: 모두 답변됨(D-ACQV-001~003, 아래는 rev 0.1 원문)

| ID | 질문 | 권장안 | 대안 | 영향 | 막히는 부분 |
|---|---|---|---|---|---|
| Q-ACQV-01 | 매입 현황 조회 역할·감사 범위 | 거래 조회와 같게: VIEW_MASKED 4역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN), **파일 상세·레코드 쪽 조회만** 감사(`ACQUISITION_FILE_VIEWED`, 감사 실패 시 미반환), 목록 미감사 | (a) OPERATOR 이상만(VIEWER 제외) (b) 목록도 감사 (c) 감사 없음(MID·거래 ID·금액만이고 개인정보 없음) | 누가 매입 상태·가맹점별 금액을 보는지, 감사 행 양 | ADMIN Use Case 역할·감사(B2). FINANCIAL 쪽(B1)은 막지 않음 |
| Q-ACQV-02 | 레코드 상세에 기관 값(승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부) 표시 여부 | **표시 안 함** — 거래 ID·MID·금액·기관 거래일·결과·사유 코드만. 승인번호·기관 거래번호는 거래 ID로 기존 거래 조회 상세(감사 대상)에서 본다 | 감사 대상인 파일 상세에서 승인번호·기관 거래번호까지 표시(매입사 문의 때 화면 하나로 끝남), 발급사·카드 종류는 계속 미표시 | 노출 범위(D-TXOPS-003과 같은 종류의 결정) | 레코드 SQL·응답 모양·비노출 시험(B1 일부). 권장안으로 먼저 만들면 대안은 열 추가만 |
| Q-ACQV-03 | Demo 준비 작업 허락: Demo MySQL 2개에 init 스크립트 재실행(데이터 삭제 없음, 권한·procedure만), payment-api 재기동(Demo DB에 아직 없는 V31 이후 migration 적용·FINANCIAL Relay 켬), financial-batch·Simulator 매입사 기동, `/tmp/paycore-demo`에 Demo 키·SFTP 키 생성, 오늘 업무일 Demo 마감 정책 새 버전 | 허락(§9 절차, 볼륨 삭제 없음) | (a) 새 Demo 볼륨(기존 Demo 결제·관리자 계정 삭제) (b) E2E 생략 — Testcontainers 시험 + 화면은 시험 대역 데이터로만 확인 | 기존 Demo 데이터·실행 중 앱 | B3(Demo E2E·캡처)만. B0~B2는 막지 않음 |

질문하지 않은 것(해석으로 처리 — Reviewer 확인): 기간 31일·기본 7일(I-3), "확인 필요" 사유 목록과 문구(I-4), 거절 = 재매입 검토 배지(I-5), 레코드 쪽 나눔(I-6), 기록 최근 20건(I-7), 수신 설정 필수(I-8), 별도 조회 계정 없음(I-9), contentSha256 표시(봉인 hash — 비밀 아님, 기관 문의용).

## 11. 위험·한계

| ID | 내용 | 처리 |
|---|---|---|
| L-1 | FINANCIAL 수신 측도 LIVE에서 Demo·golden 키를 거부하지 않는다(MERCHANT L-1과 같은 빈틈 — 호출 측만 거부) | LIVE Gate(세 수신 서비스 같은 방식) |
| L-2 | 상태·needsAttention 필터는 색인 없는 잔여 조건(31일 상한이 범위를 묶음), 레코드 상태 집계는 쪽 단위 파일 ID IN 목록 | Demo 규모. LIVE Gate 부하 측정 |
| L-3 | 레코드 다음 쪽은 별도 스냅샷(I-6) — 쪽 사이 늦은 결과가 섞여 보일 수 있다(각 쪽은 일관) | 화면에 조회 시각 표시 |
| L-4 | 파일에 연결되지 않은 응답 격리(UNKNOWN_FILE)·NAME_REUSED(파일 없음)는 이 화면에 없다 — 매입 지표 `responsesQuarantined`로만 드러남 | 후속(ACQ-ADMIN-001 격리 화면 후보) |
| L-5 | 읽기 permit Demo 2·질의 상한 2초·limit 50·기간 31일은 Demo 값 | LIVE Gate |
| L-6 | R-1 A·R-2 A는 financial-batch에 BOM 관리 의존성(jOOQ·Security)을 더한다 — resolved tree·CVE 스캔·`TECH-BASELINE` 기록 필요 시 허용 경로 추가(현재 allowedWritePaths 밖) | Coordinator 확인 |
| L-7 | FINANCIAL 응답 모양과 ADMIN 허용 모양은 두 쪽 시험이 같은 필드 목록을 각각 단언(TXN L-7과 같음 — 한쪽만 바뀌면 ADMIN이 fail-closed 503). **rev 0.5 실제 사례**: B2의 사유 코드 문법이 FINANCIAL TOKEN보다 좁아 숫자 사유 코드 파일이 영구 503이었다(B2 리뷰 MAJOR-1) → §4.7 대조표로 열마다 맞추고, 두 쪽 시험이 같은 숫자 사유 값을 쓴다. 남는 결합: 모듈 사이 자동 대조 시험은 없다(ADMIN 시험이 FINANCIAL migration을 읽지 않는다 — 모듈 경계) | 대조표 유지 — 값 집합 migration 시 함께 갱신 |
| L-8 | V16 운영 적용은 온라인 DDL이지만 시작·끝에 MDL이 필요하다(TXN L-10과 같은 절차) | LIVE Gate |
| L-9 | (리뷰 MINOR-6, 기록만) "재매입 검토 대상" 표시는 REJECTED·FILE_REJECTED 레코드가 있으면 재매입이 실행된 뒤에도 남는다(재매입 실행·새 세대 파일이 아직 없다 — D-ACQ-011) | 후속: ACQ-ADMIN-001에서 "새 세대 레코드가 있는 거절"을 구분해 표시(Coordinator가 tasks.json에 기록) |
| L-10 | (리뷰 NIT, 기록만) ① provided DTO를 두 파일(`AcquisitionFileViewQuery.kt`·`AcquisitionFileDetail.kt`)에 모았다 — 그 Query만 쓰는 작은 DTO라 §14 "같은 파일" 관례로 두었으나 주요 타입당 파일 하나로 나눌지는 후속. ② 이름 관례(`…ViewQuery`·`…ViewReader`) 유지. ③ 스냅샷 probe 표지는 상세 첫 문장의 SELECT 별칭 `view_file_head`(값 의미 없음 — 운영 SQL에 시험 표지가 남는다). ④ 기본 보안 체인을 `paymentfact.adapter.web`에, ADMIN 체인을 `acquiring.adapter.web.admin`에 두었다 — 다른 모듈(정산)이 같은 채널을 쓰게 되면 공통 위치 재검토 | 후속(동작 영향 없음) |

| L-11 | (B2 리뷰 NIT, 기록만) I-15로 감사 대상 참조가 `[a-z]{3}_ULID` 또는 매입 파일 ID를 받는데, **action과 대상 종류의 결합은 검사하지 않는다**(예: `ACQUISITION_FILE_VIEWED`에 결제 ID, `PAYMENT_VIEWED`에 파일 ID를 넣어도 도메인이 거부하지 않음). 현재 쓰는 곳은 각 Use Case 상수 한 곳씩이다 | 후속: 감사 action별 대상 종류 표(ADMIN audit 모듈 작업) |
| L-12 | (rev 0.5 — B2 리뷰 MINOR-2) 로그인 뒤 **정지된** 계정의 요청은 세션 층(`AdminSecurityContextRepository` — 계정 현재 행이 ACTIVE가 아니면 세션 폐기)이 먼저 **401 `SESSION_REPLACED`**로 끊는다 — 컨트롤러·본문 해석에 닿지 않아 400도 403도 아니다. 세션 층을 지난 뒤 정지되는 경쟁 구간은 Use Case가 Forbidden(403)으로 막는다(`AcquisitionLookupServiceSpec` — SUSPENDED Fixture). 리뷰 요청 문구 "SUSPENDED + 형식 틀린 본문 → 403"은 HTTP에서는 실제 동작대로 401로 시험했다(형식 오류가 역할·상태 판정보다 먼저 드러나지 않는다는 요구는 같다) | Coordinator·Reviewer 확인 |

| L-13 | (rev 0.6 — B3 Demo에서 발견) 감사 화면(`접속·감사 이력`)의 "대상" 열은 대상 관리자(target actor)만 보여 준다. `ACQUISITION_FILE_VIEWED` 행은 DB `target_ref`에 파일 ID가 정확히 저장되지만(I-15, B3에서 4행 모두 `ACQ.SIMACQA.TEST.20261106.001` 확인) 화면에는 대상이 비어 보인다 | 후속: 감사 조회 API·화면에 targetRef 표시(ADMIN audit 작업 — 이번 수락 기준 밖, 코드 미변경) |

## 12. 구현 배치(각 배치 끝에 보일 것)

| 배치 | 내용 | 끝에 보이는 것 |
|---|---|---|
| B0(R-1 A — 구현) | financial-batch jOOQ codegen + 의존성 | 기존 financial-batch 시험 통과·resolved tree(화면 없음) |
| B1(구현) | FINANCIAL 채널(Security 체인·서명·설정·permit·질의 상한) + Reader + API 3개 + V16 | RED→GREEN 기록, 시험이 만든 실제 응답 JSON 예시(목록·상세 — 비노출 확인), 아직 화면 없음 |
| B2 | ADMIN `financialops` + 화면 API + React "매입 현황" + Vitest | Vitest·HTTP spec 결과, 시험 대역 데이터로 띄운 화면 캡처(Demo 전이라도 화면 모양 확인 가능 — 정식 Demo 확인은 B3) |
| B3 | Demo 준비(Q-ACQV-03) + E2E 3 시나리오 + 캡처 6장 | 실제 Demo 화면: 정상·거절·결과 모름 → 늦은 결과 ACCEPTED, 감사 행 |

Verifier: 배치마다 등록 명령(시험 수·exit code·보고서) 재실행. Reviewer: 명세·diff·비노출 증거. DONE 판정은 Implementer가 하지 않는다.

## 13. 실행 기록(B0·B1 — Implementer 실행, Verifier 재검증 대상, 2026-11-06 KST)

환경: WSL Ubuntu, 기준 HEAD `4bd0105` + 미커밋 변경, Testcontainers MySQL 8.4.11, 호스트 메모리 7.8 GB(Demo MySQL 2개 실행 중 — Coordinator 지시로 **spec 단위 foreground 실행만**, 모듈 전체 시험 미실행).
명령은 모두 `./gradlew :apps:financial-batch:test --tests '<spec FQN>'…`(argv 고정 스크립트), 결과는 `build/test-results/test/TEST-*.xml`의 tests/failures/errors/skipped.

### 13.1 B0 — jOOQ codegen(동작 변경 없음, 특성화)

| 명령 | 결과 |
|---|---|
| `--tests '…architecture.ArchitectureSpec'`(codegen 첫 실행 포함) | exit 0 — 21건(새 시험 "GENERATED_JOOQ_PACKAGE … acquiring tables exist (no BATCH_* tables)" 포함), 실패·skip 0 |
| `--tests '…MainMigrationLocationSpec'` | exit 0 — 4건 |

### 13.2 B1 — RED(운영 코드 `TODO()` stub, 시험 먼저)

| 명령 | 결과 |
|---|---|
| RED-1 `--tests` Parser·Service·Controller·Golden·Settings·Architecture | exit 1 — Parser 6건 중 5 실패, Service 9건 중 8 실패, Controller 3건 중 3 실패(`NotImplementedError`), Architecture 23건 중 1 실패(**시험 결함** — 인터페이스 기본 인자 구현 `AcquisitionFileViewQuery$DefaultImpls`를 호출자로 잡음 → 규칙에서 그 내부 클래스만 제외, 기준 완화 아님). Golden 38·Settings 9는 첫 실행 통과 — **특성화**(채널 진입 필터·서명·설정은 master-api의 검토된 구현을 이식했다, RED 없음) |
| RED-2 `--tests '…AcquisitionFileViewReaderSpec'` | exit 1 — 13건 중 12 실패(`NotImplementedError`). 통과 1건 = 질의 시간 초과 분류(`AcquisitionReadLimits` — stub이 아니었다). Fixture(실제 봉인·전송·수신·마감 서비스)는 모두 만들어졌다 |
| RED-3 `--tests '…AdminAcquisitionApiSpec'`(보안 체인 추가 후) | exit 1 — 9건 중 5 실패(서명 호출 500 — stub). 통과 4건: 무서명·틀린 키·오래된 서명 401, query 400·413·404·health, **결제 사실 Inbox 회귀 없음**, 체인 2개 |

### 13.3 B1 — GREEN·회귀

| 명령 | 결과 |
|---|---|
| GREEN-1 Parser·Service·Controller·Golden·Settings·Architecture | exit 0 — 6·9·3·38·9·23건, 실패·skip 0 |
| GREEN-2 `AcquisitionFileViewReaderSpec` | exit 0 — 13건(사유 8종·재매입 표시 11 Fixture, needsAttention ↔ Use Case 동치, 필터, keyset, 상태 SQL ↔ `RecordResultRule.status` 동치, 레코드 쪽, 상세, 비노출 대조군, RO·RR 스냅샷 probe, Asia/Seoul JVM, hint·EXPLAIN 색인, 3024 분류, 결과 마감 설정) |
| GREEN-3 `AdminAcquisitionApiSpec` | exit 0 — 9건 |
| 회귀(R-2) `PaymentFactInboxHttpSpec`·`PaymentFactRelayGoldenSpec` | exit 0 — 9·3건(기대값 변경 없음) |
| 회귀(V16) `FinancialDatabaseAccountSpec` | exit 0 — 23건(기대값 변경: 적용 migration 버전 목록에 16 추가 — 정책 변경 아님) |
| `python3 -B harness/tests/test_admin_call_signature_golden.py -v` / `python3 -B -m unittest discover -s harness/tests` | exit 0 — 6 OK(financial-batch 사본 포함, golden 파일 불변) / 182 OK |

### 13.4 핵심 변이(각 RED 확인 후 원복 — sha256 앞 12자 원복 전후 동일)

| 변이 | 결과 |
|---|---|
| M1 레코드 상태 SQL의 CONFLICT 우선 제거 | RED — Reader 13건 중 3 실패(사유 표 RECORD_CONFLICT 누락, needsAttention 동치, `RecordResultRule` 동치 `#1 expected CONFLICT but was ACCEPTED`). 원복 `8b3674606677` |
| M2 keyset `file_id <` → `<=` | RED — keyset 시험(중복 행). 원복 동일 |
| M3 숨김 열 `approval_number`를 레코드 SELECT에 추가 | RED — SQL 비노출 단언(응답 JSON 단언은 매핑하지 않은 열이라 통과 — SQL 단언이 잡는다). 원복 동일 |
| M4 needsAttention에서 CONFLICT 조건 제거 | RED — needsAttention ↔ Use Case 동치. 원복 동일 |
| M5 질의 시간 상한 hint 제거 | RED — hint 단언. 원복 동일 |
| M6 상세 TX 격리 수준 REPEATABLE READ → READ COMMITTED | RED — 스냅샷 probe `TxState(READ-COMMITTED, 1)`. 원복 동일 |
| M7 RESULT_OVERDUE 경계 포함(≤) → 미포함(<) | RED — Service 9건 중 1(경계 시험). 원복 `adcefaa08eff` |

**변이로 구별되지 않는 것(한계)**: needsAttention의 RECORD_UNKNOWN·RESPONSE_QUARANTINED 조건은 현재 Fixture에서 다른 조건(RESULT_DEADLINE_PASSED·RESULT_OVERDUE)과 겹친다 — UNKNOWN 행은 결과 마감만 만들고 그때 결과 축이 DEADLINE_PASSED라서, 그 조건 하나만 지운 변이는 동등 변형에 가깝다. 사유 계산(Use Case)은 각 사유를 따로 시험한다(Service spec).

### 13.5 8대 원칙 증거(B1 — FINANCIAL 쪽, §6의 ADMIN 쪽은 B2)

| 원칙 | 증거(실제 spec · `Then`) |
|---|---|
| Financial Consistency | `AcquisitionFileViewReaderSpec` "every statement of one detail read runs in one read-only REPEATABLE READ transaction (no torn detail)"·"the record status computed in SQL equals RecordResultRule.status…" (M1·M6) |
| Transaction Safety | 읽기 전용 TX(`@@transaction_read_only = 1` probe), 쓰기 없음(ArchUnit "adapter.query does not write with jOOQ") |
| Idempotency | `AdminAcquisitionApiSpec` "repeating the same search, detail and record search returns identical bodies … leaves every FINANCIAL table unchanged"(행 수·`CHECKSUM TABLE`) |
| Concurrency Control | `AdminAcquisitionControllerSpec` "Concurrency Control: … 503 ADMIN_READ_BUSY …", Reader keyset 동률(M2) |
| Recoverability | `AdminAcquisitionControllerSpec` "Recoverability: … TEMPORARILY_UNAVAILABLE; a timeout is counted…; the exception message is never logged", Reader "a MySQL query timeout (3024) is TimedOut…" |
| Reconciliation | 해당 없음(대사 기능 아님) — UNKNOWN·CONFLICT·격리를 "확인 필요"로 모은다: Reader 사유 표 시험 |
| Auditability | FINANCIAL 쪽 해당 없음(감사는 ADMIN — D-ACQV-001, B2). 채널 인증 지표 `paycore.financial.admin_channel.auth{result,reason}`(Golden spec) |
| Observability | busy·query_timeout·auth 지표(Controller·Golden·API spec), `AdminAcquisitionApiSpec` "no signature header or v1 value, no key, no file id and no MID appear in any log line…"(캡처 대조군 포함) |

### 13.6 변경 파일·지문·하한

- financial-batch main: `build.gradle.kts`(security·jooq starter + codegen), `src/jooqCodegen/…/JooqCodegen.kt`, `FinancialBatchApplication.kt`(UserDetailsServiceAutoConfiguration 제외), `application.yml`(jooq 방언·admin-channel 설명),
  `V16__acquisition_file_admin_index.sql`, `acquiring.application.provided.{AcquisitionFileViewQuery,AcquisitionFileDetail}`, `acquiring.application.required.AcquisitionFileViewReader`,
  `acquiring.application.AcquisitionFileViewService`, `acquiring.adapter.query.{JooqAcquisitionFileViewReader,AcquisitionReadLimits}`, `acquiring.adapter.web.admin.*`(13 파일), `paymentfact.adapter.web.{FinancialDefaultSecurityConfiguration(새),PaymentFactInboxEntryFilter(admin 접두 통과)}`.
- 시험: 새 spec 7개 + Fixture·probe, `ArchitectureSpec`(+3), `FinancialDatabaseAccountSpec`(버전 16), `TestAcquirerSftpServer`(VIEW_ACQUIRERS lane), 시험 `application.yml`(admin-channel 시험 키), golden 사본.
- 기타: `.env.example`(FINANCIAL admin-channel 변수·JSON), `harness/tests/test_admin_call_signature_golden.py`(TARGETS), `harness/commands.json` minTests 3821 → **3911**(새 Kotlin 시험 90건 = Golden 38 + Settings 9 + Parser 6 + Service 9 + Controller 3 + Reader 13 + API 9 + Architecture 3 — 하한 + 추가 수, 전체 측정 합계 아님), `ARCHITECTURE-CONVENTIONS.md` §23 두 행, `TECH-BASELINE.md` §11.
- 지문(sha256 앞 12자): `JooqAcquisitionFileViewReader.kt` 8b3674606677, `AcquisitionFileViewService.kt` adcefaa08eff, `AdminAcquisitionController.kt` 2067af0e980b, `AcquisitionSearchRequestParser.kt` e466966dbd7d,
  `AdminChannelAuthenticationFilter.kt` 8ef38f3f24d9, `FinancialDefaultSecurityConfiguration.kt` 8bd994db8aaa, `V16__…sql` ceb4ae1bd89e, `build.gradle.kts` 0b1a0d0e9e6e.

### 13.7 미실행(Verifier·Coordinator 대상 — rev 0.2 시점)

- financial-batch **모듈 전체 시험**(다른 DB spec — 봉인·전송·응답·Batch·스케줄러·TechSmoke — 에 보안 체인·jOOQ 자동 구성·admin-channel 필수 설정이 영향을 주지 않는지), 전체 GRADLE-CHECK, CVE-SCAN(새 BOM 의존성).
- Demo: financial-batch는 이제 admin-channel 설정이 없으면 기동하지 않는다(I-8) — B3 Demo 설정에 키 추가 필요(D-ACQV-003 범위).

### 13.8 rev 0.3 실행 기록(리뷰 반영 — Implementer 실행, Verifier 재검증 대상)

Coordinator 모듈 전체 실행(`:apps:financial-batch:check`, detached): 364건 중 1 실패 — `MainMigrationLocationSpec` 적용 버전 목록 기대값(1..15)이 V16을 몰랐다. 기대값을 V16까지 정확히 나열하고 시험 문구에 V16을 넣었다(기준 완화 아님).

| 명령 | 결과 |
|---|---|
| **RED** `--tests '…ArchitectureSpec' '…MainMigrationLocationSpec'` | exit 1 — Architecture 23건 중 1(MINOR-3: 생성 표가 `acquisition_*`만이 아니다 — payment_fact_* 생성), MainMigrationLocation 4건 통과(기대값 갱신) |
| **RED** `--tests '…AdminAcquisitionApiSpec'` | exit 1 — 10건 중 1(MINOR-1: 서명된 `GET …/acquisition-file-searches`가 Boot 기본 본문 `{"timestamp":…,"status":405,…,"path":"/internal/v1/admin/acquisition-file-searches"}`). checksum 표 목록 단언(NIT)·Inbox 415 Boot 모양 고정은 통과 |
| **RED** `--tests '…AcquisitionFileViewReaderSpec'` | exit 1 — 14건 중 1(MINOR-2: 결과 행 키 `responseKind`·`responseSequenceNo` ≠ `responseRef`). MINOR-4(a) 단독 Fixture·4(b) 두 업무일 keyset·MINOR-5 실제 3024는 **첫 실행 통과**(기존 구현의 특성화 — 아래 변이로 시험의 검출력을 확인) |
| 구현 | `JooqCodegen` `withIncludes("acquisition_.*")`, `AcquisitionRecordResultView.responseRef`(`AcquisitionResponseRef`) + JSON 중첩, `AdminChannelErrorResolver`(I-14) |
| GREEN `--tests` Architecture·MainMigrationLocation·Controller·Service | exit 0 — 23·4·3·9건 |
| GREEN `--tests '…AdminAcquisitionApiSpec'` | exit 0 — 10건 |
| GREEN `--tests '…AcquisitionFileViewReaderSpec'` | exit 0 — 14건(Fixture 13개: rev 0.2의 11개 + COMPLETE 뒤 격리 응답 + 하루 앞 업무일) |

MINOR-5 실제 3024: runtime 계정 연결에서 `select /*+ MAX_EXECUTION_TIME(50) */ file_id from acquisition_file where sleep(0.2) = 0`(행 10개 이상 — 상한을 반드시 넘는다)을 실행해 원인 사슬에 vendor code 3024가 있음을 먼저 확인(대조군)하고, 같은 문장을 `AcquisitionReadLimits.guard`로 감싸 `TimedOut`임을 확인한다. 지표(`query_timeout{endpoint}`) 증가는 컨트롤러 시험(대역 TimedOut)이 맡는다 — Reader의 실제 문장은 상한 2초 hint가 고정이라 시험에서 실제 3024를 그 경로로 일으키지 않았다(한계).

| 변이(rev 0.3) | 결과 |
|---|---|
| M8 needsAttention에서 RESPONSE_QUARANTINED 조건 제거(원복 sha `3f4b6c4eb358` 동일 — M9도) | RED — needsAttention ↔ Use Case 동치(단독 Fixture `quarantinedAfterComplete`가 빠짐). rev 0.2 §13.4의 "구별되지 않음" 한계 중 RESPONSE_QUARANTINED는 닫힘(RECORD_UNKNOWN은 여전히 결과 마감과 겹친다) |
| M9 목록 정렬 `business_date DESC` → `ASC` | RED — keyset 시험(하루 앞 업무일 파일 위치) |
| M10 `AdminChannelErrorResolver`의 경로 검사 제거(모든 경로 처리) | RED — API 10건 중 1: R-2 회귀 시험의 Inbox 415가 Boot 본문 모양이 아니게 됨(Inbox 동작 불변 단언이 잡는다). 원복 sha `8de8468c5011` 동일 |

minTests(rev 0.3): GRADLE-CHECK·TEST-SUMMARY 3911 → **3913**(새 Kotlin 시험 +2: API "review MINOR-1 …", Reader "a real MySQL 3024 …" — 하한 + 추가 수). 다른 변경은 기존 시험 안의 단언 추가다.
지문(sha256 앞 12자): `JooqAcquisitionFileViewReader.kt` 3f4b6c4eb358, `AdminChannelErrorResolver.kt` 8de8468c5011.
Coordinator 재실행 대상: `:apps:financial-batch:check`(모듈 전체 — 이번 수정은 spec 단위만 실행), `python3 -B -m unittest discover -s harness/tests`.

### 13.9 B2 실행 기록(ADMIN `financialops` + 화면 — Implementer 실행, Verifier 재검증 대상, 2026-11-06 KST)

구조: `com.paycore.admin.financialops` — application(`AcquisitionLookupService`·모듈 `LocalTransactions`), provided(`AcquisitionLookupUseCase` + 조건·결과 타입, `@NamedInterface`), required(`FinancialOpsGateway`·`FinancialOpsLimits`·`FinancialOpsMetrics`),
adapter.integration(`FinancialOpsSettings` `paycore.admin.financial-ops.*`·`FinancialOpsConfiguration`(기동 검사: context path, LIVE + Demo/시험 키, **PAYMENT·MASTER ops 키와 kid·바이트가 같으면 거부**)·`FinancialCallSigner`(golden ② 서명 v1)·`HttpFinancialOpsGateway`(TX 안 호출 거부, 전용 permit 대기 0, 응답 128 KiB 상한, 중복 key 거부, `FinancialResponseShape` 허용 필드 모양 — D-ACQV-002 값·평평한 응답 참조·모르는 사유 fail-closed, **요청-응답 대응** — 다른 파일·기간·필터·cursor·limit·needsAttention 위반 fail-closed)·`MicrometerFinancialOpsMetrics`),
adapter.web(`AcquisitionController` `POST /api/acquisition-file-searches`·`GET /api/acquisition-files/{fileId}`·`POST /api/acquisition-files/{fileId}/record-searches`, 413·415 읽기 전 거절, no-store — `AcquisitionSearchBodyParser` 엄격 해석). 감사: 상세·레코드 쪽만 `ACQUISITION_FILE_VIEWED`(OK·NOT_FOUND, targetRef = fileId — I-15), 반환 전, 실패 → 503 `AUDIT_UNAVAILABLE`, FINANCIAL 불가는 감사 없음, 목록 미감사(D-ACQV-001).
화면: `AcquisitionsScreen`(업무일 기간·매입사·환경·3축 상태·확인 필요만·파일 ID, keyset "더 보기" 이어 붙임, 확인 필요 사유·재매입 검토 배지, 상세 = 파일 요약·상태·사유 설명·레코드별 결과(상태 필터·레코드 더 보기)·전송 시도·기관 응답 기록, 조작 버튼 없음), 메뉴 "매입·대사 > 매입 현황"(I-16). 새 npm 의존성 없음.

| 명령 | 결과 |
|---|---|
| **RED** `./gradlew :apps:internal-admin:test --tests '…financialops.FinancialOpsGatewaySpec' --tests '…financialops.AcquisitionLookupServiceSpec'`(stub) | exit 1 — Service 9건 중 9, Gateway 13건 중 10 실패(`NotImplementedError`). Gateway 통과 3건 = golden 서명 벡터·설정/기동 검사(서명기·설정은 merchantops 구현 이식 — 특성화) |
| **RED** `--tests '…financialops.AcquisitionLookupHttpSpec'`(stub) | exit 1 — 10건 중 8 실패. 통과 2건 = 기존 보안(미인증 401·CSRF 없는 POST 403) |
| GREEN Gateway·Service | exit 0 — 13·9건 |
| GREEN-1 HTTP + ArchitectureSpec | exit 1 — HTTP 10건 중 4 실패: 상세·레코드 503 `AUDIT_UNAVAILABLE` — 감사 도메인 `AdminAuditEvent.TARGET_REF`가 파일 ID를 거부(`IllegalArgumentException`). **실제 결함 발견** → I-15로 형식을 넓히고 `AdminAuditEventTargetRefSpec`(2건 — 허용·거부 형식) 추가. ArchitectureSpec 33건 통과 |
| GREEN-2 HTTP + AdminAuditEventTargetRefSpec | exit 0 — 10·2건 |
| 변이 MA1(감사 실패 시 결과 반환) / MA2(VIEW_ROLES에 UNMASK) / MA3(요청-응답 대응 검사 제거) / MA4(응답 모양: 모르는 필드 허용) | 각각 RED — Service "an audit failure withholds the result…" / Service "UNMASK-only… are forbidden…" / Gateway "a response that does not answer the request is unreadable…" / Gateway "an unknown field, a D-ACQV-002 value…". 원복 sha `1abd6dc338e1`·`3915824f72bd`·`2c42ebab67b7` 동일 |
| Vitest(foreground, `node node_modules/vitest/vitest.mjs run`) 대상 6 파일 | exit 0 — 46건(AcquisitionsScreen 5·navigation 13·labels 10·errors·noRuntimeStyles 9) |
| Vitest 전체 1차 | exit 1 — 148건 중 4 실패: 새 탭이 바꾼 기존 기대값(VIEWER 홈 바로가기에 매입 현황, OPERATOR 메뉴 검색 '대사' 결과, 메뉴 클릭 시 첫 탭, 역할별 메뉴 목록). 탭 순서를 [대사 발견, 매입 현황]으로 두어(I-16) SECURITY_ADMIN·APPROVER 진입 화면은 그대로 두고, VIEWER·OPERATOR 메뉴·바로가기 기대값은 기능 변경으로 갱신(주석에 이유) |
| Vitest 전체 2차 / `tsc --noEmit` | exit 0 — 23 파일 148건 / exit 0 |
| 화면 변이 FE1(재매입 검토 배지 제거) / FE2(needsAttention 항상 전송) / FE3(결과 행 응답 참조 대신 '결과 마감') | 각각 RED — AcquisitionsScreen 5건 중 1. 원복 sha `6e3e9c4c4baa` 동일(이후 표 열 정리로 현재 `168e420a8373`, 다시 통과). **화면 코드는 시험보다 먼저 작성했다**(RED는 변이로만 확인) |
| `python3 -B -m unittest discover -s harness/tests` | exit 0 — 182 OK |

minTests: GRADLE-CHECK·TEST-SUMMARY 3913 → **3947**(새 Kotlin 34 = Gateway 13 + Service 9 + HTTP 10 + 감사 대상 형식 2), TEST-SUMMARY-FRONTEND 166 → **173**(새 Vitest 7 = AcquisitionsScreen 5 + navigation 1 + noRuntimeStyles 1 — 하한 + 추가 수).
지문(sha256 앞 12자): `AcquisitionLookupService.kt` 1abd6dc338e1, `HttpFinancialOpsGateway.kt` 3915824f72bd, `FinancialResponseShape.kt` 2c42ebab67b7, `FinancialOpsConfiguration.kt` d6568e8c3252, `AdminAuditEvent.kt` a03aaa566b23, `AcquisitionsScreen.tsx` 168e420a8373.
설정: internal-admin은 이제 `paycore.admin.financial-ops.*`(`PAYCORE_ADMIN_FINANCIAL_OPS_*` — `.env.example`에 이름만)가 없으면 기동하지 않는다 — B3 Demo 설정에 FINANCIAL 채널 키(같은 kid·Base64)를 함께 넣어야 한다. 1회성 명령 모드도 같은 설정을 요구한다(MERCHANT-OPS-001 L-4와 같은 한계).
화면 캡처(시험 대역 — Vite dev 서버 + Playwright가 `/api/` 응답을 B1 응답 모양 목으로 돌려줌, 목 코드는 저장소 밖 scratchpad): 목록·확인 필요 필터·정상 상세·수신 불일치 상세·결과 모름 상세·상충 상세·재매입 검토 상세 7장. **실제 FINANCIAL 연동 화면이 아니다**(B3 Demo에서 다시 캡처).
미실행(Coordinator·Verifier): internal-admin 모듈 전체 시험·`check`(기존 HTTP spec 컨텍스트가 새 필수 설정·감사 형식 변경과 함께 통과하는지), 프론트 빌드(`frontendTest`·`frontendTypecheck` Gradle task), 전체 GRADLE-CHECK·CVE-SCAN.

### 13.10 rev 0.5 실행 기록(B2 리뷰 반영 — Implementer 실행, Verifier 재검증 대상, 2026-11-06 KST)

| 항목 | 변경 |
|---|---|
| MAJOR-1 | `FinancialResponseShape` 문법을 §4.7 대조표대로 FINANCIAL 저장 문법과 같게: 사유 코드 TOKEN `^[A-Z0-9_]{1,32}$`, 식별자 `#` 포함 64자, `reacquisitionOf` 64자, 일반 CODE 대신 열마다 CHECK 값 집합, `fileVersion` 1..999·응답 순번 1..99(`Whole(min, max)`). 시험 대역 `StubFinancialOpsServer`에 결과 행 `result`·`reasonCode`·`transactionId`, ACK `ackOutcome`·`ackReasonCode` 인자 추가. B1 Reader Fixture의 사유 코드를 숫자(`rejectedRecord` 결과 줄 `05`, `fileRejected` ACK `1001`)로 바꾸고 저장·조회 단언 추가 |
| MINOR-1 | 새 `AcquisitionAuditFailureHttpSpec`(`TransactionAuditFailureHttpSpec`과 같은 컨텍스트 구성 — 새 Spring 컨텍스트 없음): 상세·레코드 쪽 각각 503 `AUDIT_UNAVAILABLE`, 본문에 FINANCIAL 값 없음, no-store, 감사 0행, FINANCIAL은 답함(호출 1회씩) → 저장 복구 뒤 200·감사 2행 |
| MINOR-2 | `AcquisitionLookupUseCase.rejectMalformedSearch(viewerActorId)` 추가(역할·ACTIVE 판정 → Forbidden, 아니면 InvalidInput — 감사·호출·속도 제한 계수 없음), `AcquisitionController` 두 POST가 해석 실패 본문을 이리로 넘김. 정지 계정의 HTTP 경로는 L-12 |
| MINOR-3 | `HttpFinancialOpsGateway`: 목록 내림차순·`nextCursor` = 가득 찬 쪽의 마지막 항목, 레코드 쪽(`answersRecords`) 오름차순·`nextCursor` = 가득 찬 쪽의 마지막 seq, 상세 레코드 첫 쪽 = 기본 조건의 답 |
| NIT | `AcquisitionsScreen.loadRecords`의 `setRecordsBusy(false)`를 `openRequest` 검사 뒤에만, 새 파일 열기 때 진행 표시 초기화. 나머지 NIT(I-15 결합)는 L-11 |

| 명령(모두 단일 spec foreground — `bash /tmp/acqv-test.sh`, Gradle `--tests`) | 결과 |
|---|---|
| **RED** `:apps:internal-admin:test --tests '…financialops.FinancialOpsGatewaySpec' --tests '…financialops.AcquisitionLookupServiceSpec'`(`rejectMalformedSearch`는 항상 InvalidInput인 컴파일용 골격) | exit 1 — Gateway 16건 중 3 실패: 숫자 사유·`#` → `Unavailable(RESPONSE_UNREADABLE)`(MAJOR-1 재현), 33자 사유 코드가 Found(옛 CODE 48자 — 넓음도 재현), MINOR-3 순서·cursor 위반이 Found. Service 10건 중 1 실패(`expected:<Forbidden> but was:<InvalidInput>`) |
| **RED** `--tests '…AcquisitionLookupHttpSpec' --tests '…AcquisitionAuditFailureHttpSpec'` | exit 1 — HTTP 12건 중 2 실패: UNMASK + 형식 틀린 본문 `expected:<403> but was:<400>`(MINOR-2 재현), 정지 계정 시험의 시험 설계 결함(첫 401 뒤 세션이 없어 다음 POST가 CSRF 403 — 본문마다 새 계정·세션으로 고침, L-12). AuditFailure 1건 통과(기존 동작의 특성화 — RED는 변이 MB10으로 확인) |
| **RED** Vitest `src/screens/AcquisitionsScreen.test.tsx` | exit 1 — 6건 중 1 실패: 앞 파일의 늦은 레코드 응답 뒤 `aria-busy="false"`(기대 `true`) |
| 열마다 값 집합 시험(문법 대조표 고정) | RED 단계 뒤 추가 — 변이 MB3으로 RED 확인 |
| GREEN Gateway·Service / HTTP·AuditFailure | exit 0 — 17·10건 / 12·1건 |
| GREEN `:apps:financial-batch:test --tests '…AcquisitionFileViewReaderSpec'` | exit 0 — 15건(새 "numeric reason codes are read back as stored" — 결과 줄 `05`, ACK `1001`, 그 ACK가 닫은 FILE_REJECTED 레코드의 결과 행 사유 `1001`) |
| Vitest 화면 / 전체 / `tsc --noEmit` | exit 0 — 6건 / 23 파일 149건 / exit 0 |
| `python3 -B -m unittest discover -s harness/tests` + golden | exit 0 — 182 OK / golden exit 0 |

| 변이(원복 sha 앞 12자 = 원복 전과 동일) | 결과 |
|---|---|
| MB1 사유 코드를 옛 CODE로 / MB2 식별자에서 `#` 제거 / MB3 resumeState 값 집합에서 TRANSMISSION_UNKNOWN 제거 | 각각 RED — Gateway 숫자 사유·바깥 문법 2건 / 숫자 사유·`#` / 열마다 값 집합(`"resumeState":"TRANSMISSION_UNKNOWN"`). 원복 `532042b060c4` |
| MB4 목록 내림차순 검사 제거 / MB5 목록 nextCursor 검사 제거 / MB6 레코드 nextCursor 검사 제거 / MB7 상세 레코드 첫 쪽 대응 제거 / MB8 레코드 오름차순 검사 제거 | 각각 RED — Gateway "review MINOR-3 …". 원복 `342fc168d70b` |
| MB9 `rejectMalformedSearch`의 역할 판정 제거 | RED — Service MINOR-2 건 + HTTP "UNMASK-only gets 403 … not 400". 원복 `628eebf878b5` |
| MB10 감사 실패 시 결과 반환 | RED — `AcquisitionAuditFailureHttpSpec`(`expected:<503> but was:<200>`). 원복 `628eebf878b5` |

minTests: GRADLE-CHECK·TEST-SUMMARY 3947 → **3956**(새 Kotlin 9 = Gateway 4 + Service 1 + HTTP 2 + AuditFailure 1 + Reader 1), TEST-SUMMARY-FRONTEND 173 → **174**(AcquisitionsScreen 1).
지문(sha256 앞 12자): `AcquisitionLookupService.kt` 628eebf878b5, `AcquisitionLookupUseCase.kt` 742ac215650a, `AcquisitionController.kt` 539ecf238308, `HttpFinancialOpsGateway.kt` 342fc168d70b, `FinancialResponseShape.kt` 532042b060c4, `AcquisitionsScreen.tsx` a3c4177a0aa7,
시험 `FinancialOpsGatewaySpec.kt` 6d420dfee1bd, `AcquisitionLookupServiceSpec.kt` d7002a3d61d1, `AcquisitionLookupHttpSpec.kt` 21f30581dcc6, `AcquisitionAuditFailureHttpSpec.kt` 7a33913ac0e8, `StubFinancialOpsServer.kt` 876417c31ef4, `AcquisitionFileViewReaderSpec.kt` 35d87a675bec, `AcquisitionsScreen.test.tsx` 23747725ba9e, `harness/commands.json` 756b7c07ac60.
8대 원칙 영향: Auditability — 감사 실패 시 값 미반환을 실제 세션·MySQL로 확인(MINOR-1)·역할 없는 계정에 형식 오류를 먼저 알리지 않음(MINOR-2), Observability·Reconciliation — FINANCIAL이 정상 저장한 숫자 사유 파일이 화면에서 영구 503이던 문제 해소(MAJOR-1, 문법 밖 값은 그대로 fail-closed), Financial Consistency — 이 요청의 답이 아닌 쪽(순서·cursor 위반)은 표시하지 않음(MINOR-3). 쓰기 경로·TX·금액 계산 변경 없음(읽기 전용 — Transaction Safety·Idempotency·Concurrency Control·Recoverability 영향 없음).
미실행(Coordinator·Verifier): internal-admin·financial-batch 모듈 전체 시험·`check`, 프론트 Gradle task, 전체 GRADLE-CHECK·CVE-SCAN, B3 Demo E2E.

### 13.11 B3 Demo E2E 실행 기록(D-ACQV-003 — Implementer 실행, Coordinator·Verifier 재확인 대상, 2026-11-06 KST)

환경: WSL Ubuntu(7.8 GB), Demo MySQL `paycore-demo-mysql-1`(127.0.0.1:13306)·`paycore-demo-simulator-mysql-1`(127.0.0.1:3307) 기존 컨테이너·볼륨 그대로(삭제·재생성 없음). 운영 DB·키·endpoint 미사용, 실제 승인·송금 없음(Simulator만). Gradle 시험 미실행(지시). **저장소 코드 변경 없음**(Demo 설정·스크립트는 저장소 밖 `/tmp/paycore-demo`).

| 단계(KST) | 명령·조치 | 결과 |
|---|---|---|
| 21:30 | `./gradlew :apps:{financial-batch,institution-simulator,payment-api,master-api,internal-admin}:bootJar`(앱 JVM 없음, foreground) → `--stop` | exit 0, 39초 |
| 21:32 | 키·설정 생성 `/tmp/paycore-demo/gen-b3.sh`(umask 077, 값 미출력) | `extra.env`(600)에 FINANCIAL migration 계정·Inbox 포트 8096·결제 사실 채널 kid `demo-fact-1`·ADMIN↔FINANCIAL 채널 kid `demo-fops-1`(internal-admin 호출 측은 같은 변수 참조)·가상 매입사 변수(SFTP 2225, 시나리오 13013 REJECT DEMO_REJECTED·17017 DELAY_RESULT PT6M, result-delay PT30S). SFTP 키 `/tmp/paycore-demo/acquirer/`(host·client 개인키 600, 공개키·지문 644, root 700). 기존 `extra.env`는 백업 후 덧붙임 |
| 21:33 | init 재실행: `docker exec -e PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD paycore-demo-mysql-1 bash /docker-entrypoint-initdb.d/01-owned-databases.sh`(값은 호출 환경에서만), `docker exec paycore-demo-simulator-mysql-1 bash …/01-simulator-database.sh`(컨테이너 안 스크립트 = 저장소 파일 sha256 동일) | 각 exit 0. **전후 행 수 동일 — 92개 표·1859행**(표마다 `COUNT(*)` diff 없음). 새로 생긴 것: `paycore_financial_migration`·`_runtime`·`_grantor` 계정, FINANCIAL procedure 2개, Simulator `grant_runtime_column_update`. FINANCIAL DB는 표가 없는 상태였다(→ 새 마감 정책이 소급 변경 거부에 걸리지 않음) |
| 21:34~21:40 | 한 번에 하나씩 기동·health(`-Xmx` sim 256m·payment 512m·master 384m·financial 384m·admin 384m, SerialGC·CodeCache 64m·Xss512k): sim 9초(Simulator V10~V12 적용, SFTP 2225 SIMACQA) → payment 20초(PAYMENT V32~V34 적용, Relay 켬) → master 20초 → financial 30초(FINANCIAL V1~V16 적용, 마감 정책 `demo-e2e-20261106` 21:52, 결과 마감 PT3M, 스케줄 PT10S) → admin 25초 | 가용 메모리 5.9 GB → 3.8 GB(최저 3.5 GB, 1 GB 기준 위) |
| 21:39 | TEST 결제 3건(`/tmp/paycore-demo/b3-pay.py` — 기존 `/tmp/paycore-demo-pay.py` 흐름, DEMO-SEED-001, 결제창 → Simulator 인증 → confirm) | 12000·13013·17017 모두 AUTHORIZED, 1~2초 안에 FINANCIAL Inbox·`payment_fact_authorization`(SIMACQA·TEST) 도착 |
| 21:52~21:59 | 스케줄러: 21:52:06 봉인·전송(DELIVERED·RENAMED_AND_VERIFIED) → 21:52:16 ACK RECEIVED → 21:52:46 RESULT 1회(ACCEPTED·REJECTED DEMO_REJECTED) → 21:55:07 결과 마감 UNKNOWN(DEADLINE) → 21:58:48 RESULT 2회 늦은 ACCEPTED(UNKNOWN 행 보존, 결과 COMPLETE) | 격리 응답 0, 오류 로그 없음 |

| E2E(`python3 harness/e2e/acquiring_e2e.py --acquirer SIMACQA --business-date 2026-11-06 …`, 21:51:29 동시 시작) | 결과 |
|---|---|
| `--scenario normal --amount 12000` | **exit 0 PASS**, 82초 — TRANSMITTED·RECEIVED, 12000원 레코드 2개(오늘 1건 + 아래 11-04 이월 1건) 모두 ACCEPTED 1행(RESULT_FILE) |
| `--scenario reject --amount 13013` | **exit 0 PASS**, 83초 — REJECTED·DEMO_REJECTED 1행 |
| `--scenario missing-result --amount 17017 --timeout-seconds 1500` | **exit 0 PASS**, 441초 — UNKNOWN(DEADLINE) 1행 → ACCEPTED(RESULT_FILE) 2행, 응답 RES 01·02·ACK 01 모두 APPLIED |

파일 내용 메모: `ACQ.SIMACQA.TEST.20261106.001`은 레코드 7개다 — 오늘 3건 + **11-04에 승인됐던 Demo 결제 4건**(12000·45000·189000·32000). 이 4건은 PAYMENT V32(Relay) 적용 뒤 21:39에 처음 FINANCIAL로 relay됐고, ACQUIRING-001 I-2("마감 뒤 늦게 relay된 승인은 다음 업무일 파일")대로 첫 봉인 파일에 들어갔다. 4건 모두 VAN 원장 대조 ACCEPTED. 기대값·판정 기준은 바꾸지 않았다.

실제 화면 캡처(internal-admin 8090, OPERATOR = VIEW 역할 계정 로그인 — 자격 증명은 캡처 프로세스 안에서만 읽음, scratchpad `demo/acqview/demo.mjs` — Playwright + 시스템 Chrome, 목 없음): 목록·정상 상세·거절/재매입 검토 배지·확인 필요 목록·결과 모름 상세·늦은 결과 목록/상세·감사 화면 8장.
감사: 화면 조회 4번(상세 3·레코드 쪽 1 — 목록 검색 4번은 감사 없음, D-ACQV-001) = `ACQUISITION_FILE_VIEWED|OK` 4행, `target_ref` = 파일 ID(I-15). 화면 "대상" 열은 비어 보인다 → L-13.
비밀 점검: 생성한 비밀 값(FINANCIAL migration 비밀번호·두 채널 키·SFTP 개인키 본문)이 앱·E2E 로그 어디에도 0회. 끝: 앱 JVM 5개 모두 정지, Demo MySQL 2개 실행·healthy 유지(가용 5.9 GB). 재기동: `bash /tmp/paycore-demo/up-all.sh`(앱 순서 sim → payment → master → financial → admin, 가용 1 GB 미만이면 중단).
