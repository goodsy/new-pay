# PayCore 현재 구현·아키텍처 현황 (CURRENT-STATE)

- 작성: 2026-11-20, 기준 커밋 `378aff7`(main). 분석 단계 — 소스·DB·기존 문서 무변경.
- 표기: **[사실]** = 파일·명령으로 확인, **[추정]** = 해석·제안.
- 주의: 작업 트리에 **커밋 안 된 SITE-CONTRACT-CODE-001 SCODE-1 부분 구현**(apps/master-api 아래 tracked 12개 수정 +171/−3, 새 파일 15개 1,540줄 — V19/V20 migration, SiteCode·ContractCode, Jpa*CodeEntity, 시험 8개)이 있다. 사용자 요청으로 중단된 미검증 상태이며, 이 문서의 수치·판단에서 제외했다(HEAD 기준).

## 1. 목표와 제약 (지침 문서 요지) [사실]

| 문서 | 요지 |
|---|---|
| CLAUDE.md | 실서비스용 1차 PG를 명세·TDD·검증 하네스로 개발. MASTER/PAYMENT/FINANCIAL 소유권·로컬 TX·cross-DB 직접 SQL 금지, 주문 key = env+MID+merchantOrderId, UNKNOWN은 조회·대사 우선, 외부 호출은 TX 밖, Outbox/Inbox는 같은 로컬 TX, 확정 금융 사실 append-only. Implementer·Verifier·Reviewer 분리 |
| docs/TECHNOLOGY-AND-PRINCIPLES.md | 8대 원칙별 완료 증거. Kotlin + Spring Boot 4.1.1 BOM, JPA=Command, jOOQ=Query, React(관리자·결제창), Simulator는 Thymeleaf. Kotest·MockK·Testcontainers·ArchUnit·Fixture Monkey. Kafka/Redis는 필요 시에만. "같은 프로세스라고 cross-DB 직접 접근으로 대체하지 않는다"(§5) |
| docs/baseline/architecture/01-runtime-deployment-architecture.md | 실행 단위 9종 설계(master-api, payment-api, payment-relay, financial-worker/-batch/-api, payment-window, merchant-api, internal-admin). "승인과 정산 Batch 동일 프로세스 금지" |
| docs/HARNESS-GUIDE.md | 기능별 Gate, 작업 상태 기계(BACKLOG→…→DONE, WAITING_DECISION 등), 질문 상태(PROPOSED→ASKED→ANSWERED→APPLIED) |
| README.md | 아직 "Harness Starter v0.2" 팩 설명 그대로 — 실제 시스템 설명 아님 [사실] |

## 2. 빌드·모듈 구조 [사실]

- `settings.gradle.kts`: `includeBuild("build-logic")`(convention plugin 4종: kotlin-base·app·library·frontend), 프로젝트 6개 — 앱 5개 + `libs:test-support`.
- 버전: Spring Boot 4.1.1(BOM, 다른 값이면 빌드 중단), Kotlin 2.3.21, Spring Modulith 2.1.1, Kotest 6.2.5, MockK 1.14.11, ArchUnit 1.5.1, Fixture Monkey 1.2.3, Apache SSHD 2.20.1, Node 24.22.1(SHA 고정), 보안 patch override(tomcat·jackson), 앱별 CycloneDX SBOM.
- **앱 간 Gradle project 의존 0건.** 모든 앱은 test-support(테스트)와 jOOQ codegen(compileOnly)만 의존.
- 불일치: `settings.gradle.kts:36` 주석 "internal-admin DB 없음" ↔ 실제 ADMIN DB 소유.

### 2.1 규모 (HEAD, `git ls-files` + `wc`)

| 모듈 | main kt / 줄 | test kt / 줄 | 업무 migration (+권한) | 게이트 시험 수 |
|---|---|---|---|---|
| payment-api | 563 / 35,824 (+결제창 FE 17 / 716) | 346 / 41,144 | 22 (+15) | 2,104 (+FE 25) |
| internal-admin | 371 / 24,536 (+관리자 FE 193 / 27,782) | 180 / 27,179 | 8 (+5) | 1,179 (+FE 669) |
| master-api | 344 / 19,130 | 138 / 21,873 | 9 (+6) | 964 |
| financial-batch | 158 / 12,119 | 49 / 9,105 | 12 (+8) | 430 |
| institution-simulator | 173 / 8,722 | 34 / 4,754 | 8 (+4) | 259 |
| libs/test-support + build-logic | 7 / 788 (+11 kt) | 166 / 2,051 | — | 149 + 11 |
| **합계** | **1,616 / 약 101K** (FE 약 28.5K 별도) | **913 / 약 106K** | **59 (+38)** | **Kotlin 5,096 / FE 694 / 하네스 ≥206** |

시험 코드 줄 수가 main보다 많다(약 1.05배).

## 3. 실행 서비스 [사실]

| 서비스 | 소유 DB | 하는 일(진입점 → 주요 유스케이스) |
|---|---|---|
| payment-api | PAYMENT | `/v1/payments`(생성·확인·취소·결제창), 카드 인증, UNKNOWN 재조회(Authorization/Cancellation Recovery), REQUESTED 만료, 결제 사실 Relay(→FINANCIAL), 가맹점 Webhook, 운영자 보정 취소, 권리 Snapshot 수신(←MASTER), 관리자용 조회 API. React 결제창 정적 번들 |
| master-api | MASTER | 사업자·가맹점·Site·계약·Revision·서비스 계약·결제수단 개통·통화 활성·MID·가맹점 코드 — 관리자 명령·조회, 2인 승인 결정 실행, 권리 Snapshot 발행(Outbox→PAYMENT), Demo seed |
| financial-batch | FINANCIAL (+Spring Batch 메타) | 결제 사실 수신(Inbox, HMAC)·원승인 UNKNOWN Hold sweep, 매입 파일 봉인·SFTP 전송·응답 수신·결과 마감·재매입, 관리자 조회 |
| internal-admin | ADMIN | 관리자 로그인·TOTP·계정 등록, 계정·역할, 2인 승인(maker-checker), 감사·마스킹 해제, 가맹점·사업자 등록/조회, 거래 조회, 결제 보정 취소, 매입·대사 운영 결정. React 관리자 콘솔 정적 번들. `/api/*` 약 60개 경로 |
| institution-simulator | simulator (별도 MySQL) | 가상 VAN(TCP)·카드 인증(HTTP)·매입사(SFTP)·가맹점 Webhook 수신, Thymeleaf 화면 |

- 설계 9종 중 payment-relay·financial-worker/-api·payment-window·merchant-api는 **별도 프로세스로 만들지 않고** 각각 payment-api·financial-batch 안에 들어 있다(설계 대비 이미 통합됨).
- 포트는 env 변수(`.env`, 미확인). compose.yaml에는 MySQL 2개(3306 PayCore 4 DB, 3307 simulator)만 있고 앱 컨테이너는 없다.

## 4. DB 분리와 서비스 간 통신 [사실]

| DB | 표 수 | 소유 |
|---|---|---|
| PAYMENT | 42 | payment-api |
| MASTER | 35 (+ db/recovery V15 점검·되돌림 SQL 3) | master-api |
| FINANCIAL | 22 (+ BATCH_* 9) | financial-batch |
| ADMIN | 15 | internal-admin |
| SIMULATOR | 13 | institution-simulator |

| 방향 | 방식 |
|---|---|
| MASTER → PAYMENT | 권리 Snapshot: MASTER outbox → 서명 HTTP → PAYMENT inbox |
| PAYMENT → FINANCIAL | 결제 사실: relay 표 → HMAC HTTP → FINANCIAL inbox |
| ADMIN → 각 서비스 | 서명 HTTP Gateway 4종(PaymentOps·MasterOps·MasterBusiness·FinancialOps) → `/internal/v1/admin/*` |
| PAYMENT/FINANCIAL → Simulator | TCP(VAN), HTTP(인증·Webhook), SFTP(매입) |

- Kafka·Redis 없음. 관리자 세션은 Spring Session JDBC.
- **cross-DB 참조 0건**(앱별 다른 DB 이름·소유 표 이름 grep, 5×4 조합).
- DB마다 runtime·migration·grantor 계정을 나누고 표·열 단위 DML만 부여(`docker/mysql/init/01-owned-databases.sh`) → append-only 표 UPDATE/DELETE·다른 DB 접근이 **실행 계정 수준에서 불가능**.
- 로컬 개발은 이미 MySQL 인스턴스 하나(3306)에 PayCore DB 4개.

### 4.1 분리를 줄였을 때 (판단 근거)

- 깨지는 것 [사실 근거]: ① `outbox_event` 이름이 MASTER·PAYMENT에 모두 있어 단일 스키마면 충돌(다중 스키마면 회피) ② 계정을 합치면 표 단위 권한·append-only 보호가 실행 계정 기준으로 사라짐 ③ 단일 앱 전제 ArchUnit/Modulith 규칙 재작성 ④ 서명 HTTP 채널과 golden 시험(entitlement·payment-fact·admin call) 의미 축소 ⑤ baseline 01 "승인·정산 동일 프로세스 금지", TECHNOLOGY §5와 정면 충돌 → **사용자 정책 변경 필요**.
- 얻는 것 [추정, 미측정]: Spring 컨텍스트·Testcontainers 기동 수 감소로 게이트 시간 단축 가능, Demo 기동·env 단순화. Outbox/Inbox 계약을 유지하면 코드량은 크게 줄지 않음.

## 5. 기능별 구현 상태 [사실]

- `harness/tasks.json` 70개: **DONE 51 / IN_PROGRESS 2 / BACKLOG 17 / WAITING_DECISION 0**.
- `harness/questions.json` 270개: ANSWERED 253, APPLIED 2, **미결 15**(PROPOSED 10 — 달력·정정·배치 파라미터·정산 통화·관리자 보안 값 등, PENDING 5 — Q-ACQA-05~08·10).

| 영역 | 코드 | 시험 | 게이트 기록 | Demo 기록 |
|---|---|---|---|---|
| 결제 생성·승인·확인(멱등·주문 활성 하나) | 있음 | 있음 | 있음 | 결제창 |
| 취소(부분 포함, 반환 잔액) | 있음 | 있음 | 있음 | — |
| UNKNOWN 재조회·Recovery | 있음 | 있음 | 있음 | — |
| 카드 인증(가상 기관) | 있음 | 있음 | 있음 | 있음 |
| 가맹점 Webhook | 있음 | 있음 | 있음 | — |
| MASTER→PAYMENT 권리 Snapshot | 있음 | 있음 | 있음 | — |
| PAYMENT→FINANCIAL 결제 사실·Hold | 있음 | 있음 | 있음 | — |
| 매입(봉인·SFTP·응답·재매입) | 있음 | 있음 | 있음 | 있음 |
| **정산·입금·선지급·지급** | **없음** | — | BACKLOG | — |
| **기관·원장 대사(RECON)** | **없음**(관리자 운영 결정 대사·매입 결과 대조만) | 일부 | BACKLOG | — |
| 가맹점·사업자 온보딩·계약·Site·MID·개통·가맹점 코드·가맹점명 검색 | 있음 | 있음 | 있음 | 있음 |
| 가맹점 생명주기(중지·해지·PRIMARY 교체) | 없음 | — | BACKLOG | — |
| 관리자 2인 승인·감사·마스킹 해제·TOTP | 있음 | 있음 | 있음 | 있음 |
| 가상 기관 Simulator | 있음 | 있음 | 있음 | — |

**요약 [추정]:** 결제(승인·취소·불명 회복)·권리 전파·매입·가맹점 온보딩·관리자 통제는 구현·검증되어 있다. **PG 사업의 돈 흐름 후반부(정산·지급·대사)와 가맹점 생명주기는 아직 없다.** 남은 금융 핵심 기능이 크다는 점이 재개발/재구성 판단의 중요한 전제다.

## 6. 테스트 자산 [사실]

- 최근 게이트(MERCHANT-NAME-SEARCH-001, 2026-11-19 22:05Z): PASS — Kotlin 5,096 / FE 694(관리자 669 + 결제창 25) / 하네스 self-test ≥206, 실패·skip 0.
- 모듈별: payment 2,104 · admin 1,179 · master 964 · financial 430 · simulator 259 · test-support 149 · build-logic 11.
- 종류: Kotest Spec 539 파일, `@SpringBootTest` 111, Testcontainers MySQL 152, ArchUnit 16(공통 규칙 PayCoreArchitectureRules: domainIsPure·applicationDependsOnlyOnPorts·jooqOnlyInQueryAdapter·modulesAreFreeOfCycles 등), Modulith 시험 앱별, Golden 29(+하네스 golden), 동시성·경쟁 이름 시험 88건, Fixture Monkey 4. 변이 시험 도구(pitest)는 없음(변이는 수동 1회 실행 관행).
- **게이트 1회 GRADLE-CHECK 51~56분**(최근 12회), `--no-daemon --no-build-cache --rerun-tasks check`, timeout 4,500초(2026-11-12 사용자 결정으로 상향).

## 7. 현황 한 줄 요약 [추정]

규칙을 잘 지키는 5-서비스·5-DB 구조와 매우 두꺼운 시험·하네스 자산을 갖췄고, 서비스 경계 위반은 발견되지 않았다. 반면 **시험 줄 수 > 운영 줄 수, 게이트 1회 약 1시간, 기능 하나당 리뷰·게이트 반복**으로 변경 비용이 높고, 정산·지급·대사라는 큰 미구현 영역이 남아 있다. 상세 진단은 DOMAIN-·FRONTEND-·COMPLEXITY-ASSESSMENT, 비교는 REBUILD-VS-REFACTOR, 순서는 RECOVERY-ROADMAP 참조.
