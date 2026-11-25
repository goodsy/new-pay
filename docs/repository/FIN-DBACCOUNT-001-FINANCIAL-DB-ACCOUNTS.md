# FIN-DBACCOUNT-001 — FINANCIAL DB migration/runtime 계정 분리

- rev 0.1 (2026-11-05, Implementer). 상태: 구현·대상 시험 실행 완료, **Verifier/Reviewer 판정 전**(DONE 아님). **→ 현행화(S3-DOC-001 2026-11-25 — 문서 정합 표시만, NP-100·PC-60·FIN 대장 진단 (c)-4): `harness/archive/tasks-done-2026-11-22.json`에서 FIN-DBACCOUNT-001 = DONE**
- 근거 결정: **D-FFR-002**(Q-FFR-02 사용자 답 "매입 전에 별도 작업", 2026-11-05). 선행: FIN-FACT-RELAY-001 §9 **L-1**(FINANCIAL 단일 계정 — append-only를 DB가 강제하지 못함).
- 참고 패턴: PAYMENT-DBACCOUNT-001(payment-api `DatabaseAccountSeparationSpec`), ADMIN-BOOT-001 §4.2.1(열 단위 helper), master-api `MasterDatabaseAccountSpec`.
- 표기: "사용자 결정"은 DECISIONS.md의 결정만이다. 이 문서의 나머지 선택은 **Implementer 해석(I-n)** 이며 사용자 승인이 아니다.

## 1. 범위

| 포함 | 제외 |
|---|---|
| init 스크립트에 FINANCIAL migration 계정·runtime role·잠긴 grantor·표/열 단위 privilege procedure | 운영 DB 설치(DBA 절차 — 같은 SQL을 쓴다는 안내만) |
| financial-batch: Flyway = migration 계정, 앱 pool = runtime 계정, `db/runtime-privilege/financial/V3` | Batch metadata 보존·정리(DELETE) 정책 — D-ACQ-007이 운영 정책으로 분리 |
| 결제 사실 표·Spring Batch metadata 표별 최소 권한 | 보류 해제 기록 표(D-FFR-003 — ACQUIRING-001) |
| Testcontainers 권한 시험, 기존 financial-batch 시험을 runtime 계정으로 전환 | 다른 앱 시험 파일 수정(허용 경로 밖 — §9 Q-1) |
| compose.yaml·.env.example 변수 이름과 기존 volume 수렴 절차 | 비밀번호 값 |

## 2. 계정·role 모델

MASTER·PAYMENT·ADMIN과 같은 구조다(`<db>` = `paycore_financial`).

| 주체 | 권한 | 쓰는 곳 |
|---|---|---|
| migration 계정 `paycore_financial_migration`@`%` | `paycore_financial.*` ALL(**GRANT OPTION 없음**) + 두 procedure EXECUTE | Flyway(`spring.flyway.user/password`)만 |
| runtime role `paycore_financial_runtime`@`%` | 표·열 단위 권한만(§4). DB 단위 권한 없음 | runtime 계정의 기본 role |
| runtime 계정 `paycore_financial`@`%` | role만(`SET DEFAULT ROLE`). DB 단위 권한 0 — 재실행마다 DB 단위 ALL·GRANT OPTION REVOKE | 앱 DataSource(Hikari) — JPA·Spring Batch JobRepository·incrementer |
| grantor `paycore_financial_grantor`@`localhost` | `paycore_financial.*` SELECT/INSERT/UPDATE/DELETE WITH GRANT OPTION, **ACCOUNT LOCK**(접속 불가) | procedure의 DEFINER |
| `paycore_financial_privilege.set_runtime_table_privileges(table, set)` | SQL SECURITY DEFINER. 받는 쪽 = runtime role 고정. 허용 집합 4개만, grant 후 여집합 revoke(재실행 안전) | V3 |
| `paycore_financial_privilege.grant_runtime_column_update(table, columns)` | 열 단위 UPDATE 추가(1~8개, 그 표에 실제로 있는 열) | V3 |

**I-1 (Implementer 해석) — FINANCIAL procedure의 이름 정규식.** Spring Batch metadata 표는 공식 schema 그대로 대문자(`BATCH_JOB_INSTANCE` 등, D-ACQ-007 같은 schema)이고 Linux MySQL은 표 이름을 구분한다. 기존 procedure는 `^[a-z][a-z0-9_]{0,63}$`만 받는다. FINANCIAL procedure만 표 `^([a-z][a-z0-9_]{0,63}|BATCH_[A-Z0-9_]{1,58})$`, 열 `[a-z][a-z0-9_]{0,63}` 또는 `[A-Z][A-Z0-9_]{0,63}`을 받는다. init 함수에 정규식 인자를 더했고 기본값으로 만든 MASTER·PAYMENT·ADMIN SQL은 **바이트 그대로**다(§8 E-1: 변경 전후 생성 SQL sha256 `6d873e44…1639587` 동일). 대소문자 섞인 이름(`Batch_job_instance`, `Fact_count`)·대문자 비BATCH 표(`PAYMENT_FACT_INBOX`)는 1644로 거부한다(시험 T10·T11).

**I-2 (Implementer 해석) — migration 비밀번호가 없을 때.** compose.yaml은 `PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD`를 `:?`로 요구한다(fail-closed). compose 밖(다른 앱의 시험 컨테이너 — payment/master/admin helper는 이 변수를 넣지 않는다)에서는 ADMIN과 같은 규칙을 쓴다: **FINANCIAL이 전혀 쓰이지 않은 새 인스턴스**(paycore_financial 표 0, `paycore_financial_privilege` schema 없음, migration·grantor·role 계정 없음)일 때만 migration 계정·role·procedure 설치를 건너뛰고 runtime 계정의 DB 단위 권한을 모두 회수한다(financial-batch는 기동 불가 — fail-closed). 하나라도 있으면 exit 1(기존 volume 수렴에서 변수 누락 방지, 시험 T19). 이전 동작(runtime에 DB ALL)으로 돌아가는 경로는 없다. 대안: 변수를 필수로 하고 세 앱 helper에 변수를 추가 — 허용 경로 밖이라 택하지 않았다(§9 Q-2).

## 3. 기존 volume 수렴

`.env.example` financial-batch 절에 적었다. 요약: financial-batch 정지 → `.env`에 `PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD` 추가 → `docker compose up -d mysql`(새 변수 반영) → `docker compose exec mysql bash /docker-entrypoint-initdb.d/01-owned-databases.sh`(exit 0, "not set" 안내 없음 확인) → financial-batch 기동(Flyway V3가 runtime 권한 부여). 재실행은 안전하다: `IF NOT EXISTS`, runtime의 DB 단위 ALL·GRANT OPTION과 migration의 GRANT OPTION `REVOKE IF EXISTS`, grantor `ACCOUNT LOCK` 재적용, procedure 재생성, 이미 준 표·열 권한 유지(T18). Flyway history의 기존 V1·V2 `installed_by`는 이전 단일 계정 이름으로 남는다(수렴 기록 — 바꾸지 않는다). 수렴 중 PAYMENT Relay는 연결 실패를 재시도로 남기고(격리는 400·409·422뿐 — FIN-FACT-RELAY-001 §4), 재전송은 Inbox 멱등으로 수렴한다.

## 4. 표별 runtime 권한

근거는 main 코드의 실제 경로와 Spring Batch 6.0.5 JDBC DAO SQL(javap로 `spring-batch-core-6.0.5.jar` 상수 확인), spring-jdbc 7.0.9 `MySQLMaxValueIncrementer`(`update <seq> set ID = last_insert_id(ID + ?) limit 1`, `select last_insert_id()`)다. **DELETE는 어느 표에도 없다.**

| 표 | 표 단위 | 열 단위 UPDATE | 근거 경로 | 거부(시험) |
|---|---|---|---|---|
| payment_fact_inbox | SELECT, INSERT | — | JpaPaymentFactInboxStore persist(@Immutable) · findByEvent/findByEffect | UPDATE·DELETE·FOR UPDATE·TRUNCATE 1142 (T3) |
| payment_fact_authorization | SELECT, INSERT | — | insertAuthorization · findByPayment | 같음 (T3) |
| payment_fact_cancellation | SELECT, INSERT | — | insertCancellation · findByPayment | 같음 (T3) |
| payment_fact_hold | SELECT, INSERT | — | insertHolds | 같음 (T3) |
| payment_fact_stream | SELECT, INSERT | fact_count, updated_at, version | insertIfAbsent IODKU(`ON DUPLICATE KEY UPDATE version = version` — INSERT + UPDATE(version) 둘 다 필요), refresh(PESSIMISTIC_WRITE) = FOR UPDATE, recordFact 변경 감지 | 신원 열 UPDATE 1143, IODKU로 신원 변경 1143, DELETE 1142 (T4) |
| BATCH_JOB_INSTANCE | SELECT, INSERT | — | createJobInstance(UPDATE SQL 없음) | UPDATE 1142, DELETE 1142 (T5) |
| BATCH_JOB_EXECUTION | SELECT, INSERT | CREATE_TIME, END_TIME, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED, START_TIME, STATUS, VERSION | UPDATE_JOB_EXECUTION SET 열 | JOB_EXECUTION_ID·JOB_INSTANCE_ID 1143, DELETE 1142 |
| BATCH_JOB_EXECUTION_PARAMS | SELECT, INSERT | — | insertJobParameters | UPDATE 1142, DELETE 1142 |
| BATCH_STEP_EXECUTION | SELECT, INSERT | 15열(UPDATE_STEP_EXECUTION SET 열 — helper 상한 8이라 두 번 호출) | updateStepExecution | STEP_EXECUTION_ID·STEP_NAME·JOB_EXECUTION_ID·CREATE_TIME 1143, DELETE 1142 |
| BATCH_JOB_EXECUTION_CONTEXT, BATCH_STEP_EXECUTION_CONTEXT | SELECT, INSERT | SHORT_CONTEXT, SERIALIZED_CONTEXT | saveExecutionContext·updateExecutionContext | PK 열 1143, DELETE 1142 |
| BATCH_JOB_INSTANCE_SEQ, BATCH_JOB_EXECUTION_SEQ, BATCH_STEP_EXECUTION_SEQ | SELECT | ID | MySQLMaxValueIncrementer(행은 V1이 하나 만든다) | INSERT 1142, UNIQUE_KEY 1143, DELETE 1142 |

- **I-3 (Implementer 해석)**: Batch 실행 표는 표 단위 UPDATE 대신 DAO가 쓰는 열만 연다(식별 열 변조로 재시작 상태를 섞는 것을 막는다). Batch를 올리면 새 DAO SQL로 V3 근거를 다시 확인한다(Batch 업그레이드는 어차피 새 metadata migration이 필요 — V1 머리말).
- **I-4 (Implementer 해석)**: Batch `delete*` DAO(JobRepository.deleteJobInstance/deleteJobExecution)는 명시 호출에서만 쓰이고 이 저장소 main 코드는 부르지 않는다. 정리 Job이 생기면 그 작업이 보존 정책과 함께 DELETE 권한 migration을 추가한다.
- 시험 전용 표 `tech_smoke_batch_out`(V900)은 시험 전용 V901(`db/testprivilege/financial`)이 전체 DML을 준다(운영 jar 밖, master-api V901 관례).
- 열 권한 주의(PAYMENT V24 측정): 표 단위 helper를 다시 부르면 여집합 REVOKE가 열 단위 UPDATE도 회수한다. V3는 표마다 표 helper → 열 helper 순서다.

## 5. JPA·CQRS·버전 (TECHNOLOGY-AND-PRINCIPLES §2·§3)

- **JPA가 이번 변경에서 하는 일 → 예 → 이유 → 주의**:
  - 변경 감지: `recordFact`는 잠가 읽은 `JpaPaymentFactStreamEntity`의 `factCount`·`updatedAt`만 바꾸고 flush하면 Hibernate가 `UPDATE payment_fact_stream SET fact_count=?, updated_at=?, version=? WHERE payment_id=? AND version=?`를 만든다. 그래서 runtime에는 그 세 열의 UPDATE만 주면 된다. 신원 열은 `updatable=false`라 SQL에 나오지 않고, DB도 1143으로 막는다(이중 방어). 주의: 나중에 Entity에 updatable 열을 더하면 V-next 권한 migration이 없으면 1143으로 실패한다(fail-closed — T4가 잡는다).
  - `@Version`: 같은 UPDATE의 `version` 증가와 WHERE 조건이다. 열 권한 목록에 `version`이 빠지면 모든 사실 수신이 실패한다(mutation M3에서 확인).
  - 잠금: `refresh(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`. MySQL은 잠금 읽기에 UPDATE 권한을 요구하며 열 단위 UPDATE로 충분하다(T4 `lock read` null). append-only 표는 UPDATE가 없어 잠금 읽기도 1142다 — 직렬화는 stream 행에서만 한다.
  - `@Immutable` 사실 Entity: Hibernate가 UPDATE를 만들지 않는다. 이제 DB 권한도 같은 경계를 강제한다(FIN-FACT-RELAY-001 FR-I11 + 이 작업 T3).
  - 순수 Domain과 JPA Entity adapter 분리는 바뀌지 않았다(코드 변경 없음, ArchitectureSpec 19건 통과).
- **jOOQ CQRS**: financial-batch에는 jOOQ 조회 adapter가 없다(해당 없음). 앞으로 jOOQ Reader가 생기면 같은 runtime 계정·표 SELECT 권한 안에서 돈다.
- **BOM**: 새 의존성·버전 없음. Boot 4.1.1 BOM의 Spring Batch 6.0.5·spring-jdbc 7.0.9·Flyway, MySQL 이미지 8.4.11 그대로. Kafka/Redis는 도입하지 않았고 금융 안전성은 로컬 TX·UNIQUE·DB 권한이 지킨다(그것들을 대체한다고 주장하지 않는다).

## 6. 시험 목록

`FinancialDatabaseAccountSpec`(신규, 19건 — PaymentFactInboxDbSpec과 같은 Spring 컨텍스트·컨테이너):

| ID | 내용 |
|---|---|
| T1 | 앱 pool = `paycore_financial@%`, CURRENT_ROLE = runtime role, mysql.db 행 0 |
| T2 | 업무 경로(원승인 전 취소 HELD → 원승인 → 후속 취소 → 재전송 DUPLICATE)가 runtime 권한 안에서 성공, 건수·version |
| T3 | inbox·authorization·cancellation·hold UPDATE/DELETE/FOR UPDATE/TRUNCATE 전부 1142, 행 불변 |
| T4 | stream 열별 UPDATE(3열 허용, 나머지 1143), DELETE 1142, IODKU 자리 확보 성공·IODKU 신원 변경 1143, 잠금 읽기 성공 |
| T5 | BATCH_* 9표: DELETE 1142, 열별 UPDATE = DAO 열만, 권한 없는 표 1142 / 일부 열 표 1143, SEQ INSERT 1142 |
| T6 | DDL(CREATE/ALTER/DROP/TRUNCATE) 1142, flyway_schema_history 1142, 두 procedure 호출 1370 |
| T7 | PAYMENT·MASTER 읽기 1142/1044, USE 1044 |
| T8 | Flyway installed_by = migration 계정, 버전 1·2·3 |
| T9 | migration 계정 직접 GRANT 1142, role 부여·CREATE USER 1227, DB 단위 GRANT 1044/1142 |
| T10 | 표 procedure 거부 12종(허용 밖 집합·주입·소문자 집합·history·없는 표·view·따옴표·LF·CR·대문자 비BATCH·혼합 대소문자·소문자 batch) 1644, 권한 불변 |
| T11 | 열 procedure 거부 8종(없는 열·일부 없음·주입·없는 표·history·혼합 대소문자·9열·빈 값) 1644, 같은 열 재부여 멱등 |
| T12 | procedure가 소문자 표·BATCH_ 표 모두 권한을 정확히 교체(넓힘·좁힘) + 열 부여 |
| T13 | 다른 DB USE 1044, PAYMENT procedure 1370, 자기 procedure DROP 1370, privilege schema CREATE 1142 |
| T14 | SHOW GRANTS 정확 일치(runtime·role·migration·grantor) + 잠금 상태 |
| T15 | 모든 base table에 명시 권한(tables_priv·columns_priv 집합 일치 — 권한 migration 없는 새 표는 여기서 실패) |
| T16 | FINANCIAL DB에 trigger·routine·event·view 0 |
| T17 | 같은 서버의 MASTER·PAYMENT 계정에 FINANCIAL 권한 없음 |
| T18 | 이전 volume(runtime·migration DB ALL WITH GRANT OPTION, grantor unlock) → 스크립트 재실행 → DB 단위 권한 0·GRANT OPTION 0·grantor 잠금·SHOW GRANTS 일치·DELETE/DROP 1142 (사전 상태에서 DELETE가 실제로 성공함을 대조) |
| T19 | FINANCIAL이 쓰이는 volume에서 migration 변수 없이 재실행 → exit 1·안내 문구·권한 불변 |

기존 시험의 runtime 계정 전환(기대값 완화 없음):

- `PaymentFactInboxDbSpec`(16)·`PaymentFactInboxHttpSpec`(9): `@Import`만 `FinancialMySqlContainerConfig`로 — 단언 변경 없음. 동시성(FR-I7·I8·I12)·rollback(FR-I9)·commit 응답 유실(FR-I10)이 runtime 권한에서 통과.
- `TechSmokeBatchRestartSpec`(3, **Batch restart**): JobRepository·writer가 runtime 계정. Flyway 위치에 V3·V901 추가. history 단언은 같은 내용을 migration 계정 연결로 읽는다(runtime은 history 1142 — 설계상 의도, D-FFR-002).
- `MainMigrationLocationSpec`(3): 위치 `[migration]` → `[migration, runtime-privilege]`, 버전 `[1,2]` → `[1,2,3]`. **완화 아님** — D-FFR-002에 따른 위치·버전 추가이고 표 집합 단언은 그대로다.

## 7. 8대 원칙 증거

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | 확정 사실(승인·취소·보류·Inbox)을 DB 권한으로 append-only 강제 | T3·T15·T18, mutation M1 |
| Transaction Safety | 로컬 TX 구조 불변. 권한 변경이 TX 경로(Inbox + 투영 같은 TX, IODKU 자리, 잠금)를 막지 않음 | T2·T4, 기존 FR-I9·I10 runtime 통과, M3 |
| Idempotency | Inbox UNIQUE·재전송 DUPLICATE가 runtime에서 동작 | T2(재전송), FR-I1·I3·I7 |
| Concurrency Control | stream FOR UPDATE에 필요한 열 UPDATE만, 경합 시험 runtime 통과 | T4 lock read, FR-I7·I8·I12 |
| Recoverability | Batch 재시작이 DELETE·식별 열 UPDATE 없이 완료, 수렴 절차 재실행 안전 | TechSmoke 3건, T18·T19, M4 |
| Reconciliation | 해당 없음(이 작업은 대사 기능 아님). 대사 입력인 보류·사실이 지워지지 않도록 보장 | T3 |
| Auditability | 권한은 procedure·migration으로만(누가: migration 계정 installed_by), 수동 GRANT 불가 | T8·T9·T10·T14 |
| Observability | 해당 없음(지표 변경 없음). 권한 오류는 기존 영속 실패 분류(Unavailable 등)로 드러남. 수렴 스크립트 stderr 안내 | T19 |

## 8. 한계

- **L-1 (해소, Coordinator)** master-api `MasterDatabaseAccountSpec`의 FINANCIAL 기대값을 DB ALL → `emptySet()`(강화)으로 Coordinator가 고쳤다(허용 경로 확장 기록: harness/tasks.json). 수정 뒤 10/0.
- **L-2** `V2__payment_fact_inbox.sql` 5행 주석("아직 단일 계정")은 낡았다. 적용된 migration이라 checksum 때문에 고치지 않는다(이 문서와 V3 머리말이 대신한다).
- **L-3 (부분 해소)** FIN-FACT-RELAY-001 §9 L-1에 해소 표시(Coordinator). ARCHITECTURE-CONVENTIONS §9 권한 표는 미갱신(후속).
- **L-7 (재검토 MINOR-2)** T18은 새 구조 위에 DB ALL을 덧붙여 재현한다. 실제 이전 볼륨(migration·grantor·role·`_privilege` 없음, V1·V2 installed_by=paycore_financial, V3 미적용)에서 스크립트 재실행 → Flyway V3 → runtime 업무 성공까지의 전체 수렴 경로는 시험하지 않았다(개별 SQL은 IF NOT EXISTS).
- **L-8 (재검토 MINOR-3)** 건너뛰기 경로의 REVOKE가 실제로 DB ALL을 회수하는 상황(FINANCIAL 표 0 + runtime DB ALL + 변수 누락)은 시험하지 않았다(활성 경로와 같은 SQL).
- **L-9 (재검토 NIT-3·NIT-4)** I-1은 `lower_case_table_names=0`을 전제한다(1이면 V3가 1644로 fail-closed). FINANCIAL 표가 있는 볼륨에서는 다른 DB 수렴을 위한 재실행에도 FINANCIAL migration 비밀번호가 필요하다(없으면 SQL 전 exit 1).
- **L-4** 같은 MySQL 인스턴스의 DB 분리는 개발 편의다(운영 격리 증거 아님 — compose.yaml 머리말). 운영 설치는 DBA가 같은 SQL로 하고 결과를 T14 기대값과 대조해야 한다.
- **L-5** Batch metadata 정리(DELETE)는 권한이 없다. 정리 Job·보존 정책은 별도 작업.
- **L-6** root 계정은 여전히 모든 권한을 가진다(관찰·설치 전용). 운영 root 관리는 범위 밖.

## 9. Coordinator에 반환하는 질문

- **Q-1 → 처리됨(Coordinator, 2026-11-05: 권장안대로 수정, 허용 경로 확장 기록)** (허용 경로 밖 변경 요청, 금융 의미 없음) `apps/master-api/src/test/kotlin/com/paycore/master/MasterDatabaseAccountSpec.kt` 154행 기대값 `"paycore_financial" to setOf("GRANT ALL PRIVILEGES ON `paycore_financial`.* TO `paycore_financial`@`%`")` → `"paycore_financial" to emptySet()`, 114행 제목 "FINANCIAL unchanged" → "FINANCIAL runtime has no DB-level privilege without its migration password". 권장안: 이대로 Coordinator가 수정(강화 — 이전 기대값이 바로 D-FFR-002가 없애기로 한 위험 상태). 대안: I-2를 바꿔 변수를 필수로 하고 세 helper(PaymentDbAccounts·MasterDbTestConfig·AdminDbAccounts)에 변수를 추가(그때 기대값은 role 한 줄). 영향: 고치기 전 GRADLE-CHECK FAIL. 막히는 작업: FIN-DBACCOUNT-001 VERIFY-GATE.
- **Q-2 → 처리됨(Coordinator 유지 결정 — 가역 구현 세부, 사용자 결정 아님)** (구현 세부 확인, 가역적) I-2(변수 없는 새 인스턴스 건너뛰기) 유지 여부. 권장안: 유지(ADMIN과 같은 규칙, 다른 앱 helper 무변경). 대안: 필수화. 영향·막힘: Q-1과 같다.

## 10. 실행 기록

- 기준: HEAD `e6471f9`(feat/foundation-setup), 작업 트리 미커밋. 환경: WSL Ubuntu, Docker(Testcontainers mysql:8.4.11), Gradle wrapper, JDK 25(javap 확인용), Python 3. 명령마다 `./gradlew --stop`.
- E-1 init 공용 함수 생성 SQL 비교(MASTER·PAYMENT·ADMIN × 표·열 함수): 변경 전후 sha256 `6d873e4451a22d9606383d8513d45d02f14f0018ee8f313307d82c15e1639587` 동일.
- RED(2026-11-05T03:45Z): init 스크립트를 HEAD 내용으로 되돌리고 application.yml·V3 없이 `./gradlew :apps:financial-batch:test --tests com.paycore.financial.FinancialDatabaseAccountSpec` → exit 1, 19건 중 15 실패. 대표 실패 이유: CURRENT_ROLE `NONE`, append-only UPDATE/DELETE 성공(null)·FK 1452, Flyway installed_by `paycore_financial`, migration 계정 접속 거부, 재실행 exit 0(변수 없는데 통과). 업무 RED다(환경 오류 아님 — 컨텍스트·컨테이너는 떴다).
- GREEN(03:49Z): `./gradlew :apps:financial-batch:test` → exit 0, 89건/실패 0/skip 0(FinancialDatabaseAccountSpec 19, PaymentFactInboxDbSpec 16, PaymentFactInboxHttpSpec 9, TechSmokeBatchRestartSpec 3, MainMigrationLocationSpec 3, ArchitectureSpec 19, PaymentFactProjectionRuleSpec 13, TestInfrastructureSpec 3, PaymentFactRelayGoldenSpec 3, PaymentFactRepositorySurfaceSpec 1).
- Mutation A(03:51Z, M1 inbox에 UPDATE·DELETE + M2 FINANCIAL 블록에 runtime DB ALL 재부여): 대상 spec exit 1, 19건 중 11 실패(T1 mysql.db 1행, T3, T14/T15 inbox 권한 줄, T18 runtime db rows 1 …). 되돌림 sha256 확인.
- Mutation B(03:52Z, M3 stream 열에서 version 제외 + M4 BATCH_STEP_EXECUTION 두 번째 열 묶음 제외): 대상 2 spec exit 1, 22건 중 12 실패 — T2 "UPDATE command denied … column 'version' … payment_fact_stream"(IODKU에 UPDATE 필요 실증), TechSmoke 재시작 2건 실패(step UPDATE 불가). 되돌림 sha256 확인.
- 다른 앱 init 의존 시험(새 init 스크립트): `:apps:payment-api:test --tests …DatabaseAccountSeparationSpec` exit 0, 17/0. `:apps:internal-admin:test --tests com.paycore.admin.AdminDatabaseAccountSpec -x frontendTest` exit 0, 24/0(새 인스턴스 건너뛰기 경로 포함). `:apps:master-api:test --tests com.paycore.master.MasterDatabaseAccountSpec` exit 1, 10/1 — 실패는 `paycore_financial` 키 하나(L-1, Q-1).
- `python3 -B -m unittest discover -s harness/tests` → exit 0, Ran 170, OK.
- harness 하한(정적 계산): GRADLE-CHECK·TEST-SUMMARY 3571 → **3590**(+19 = FinancialDatabaseAccountSpec 19건. 기존 spec은 이름·수 변화 없음: MainMigrationLocationSpec 3→3, TechSmoke 3→3, 그 밖 무변경). HARNESS-SELFTEST 170 그대로(harness 시험 추가 없음). 전체 GRADLE-CHECK는 실행하지 않았다(메모리 제약·Q-1 미해결) — Verifier 몫.
- 검사 후 변경: compose.yaml·.env.example·이 문서는 Gradle 시험 뒤에 편집했다(시험 입력 아님 — harness selftest는 편집 뒤 실행).
