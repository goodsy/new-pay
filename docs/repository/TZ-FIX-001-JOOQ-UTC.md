# TZ-FIX-001 jOOQ/JDBC 시각 바인딩 시간대 결함 전수 점검·수정 명세 (rev 1.0)

작성 2026-10-17 KST, 기준 commit `4e06ca7`(branch `feat/foundation-setup`). 이 문서는 새 업무 정책을 채택하지 않는다. 동작·API·스키마·의존성 변경 없음.

## 1. 범위

- **대상**: 모든 모듈(master-api, payment-api, financial-batch, internal-admin, institution-simulator, libs/test-support)의 `src/main`에서 jOOQ 또는 plain
  JDBC로 `DATETIME`/`TIMESTAMP` 컬럼을 읽거나 시각 값을 비교 값으로 묶는 곳(`LocalDateTime` 바인딩, 생성 `LocalDateTime` 필드 직접 읽기,
  `java.sql.Timestamp`, `DSL.val`/`DSL.inline` 시각, 시각 필드의 `.le/.lt/.gt/.ge`, `TIMESTAMPDIFF`).
- **규칙**: ARCHITECTURE-CONVENTIONS §9.1(시각 매핑) — 읽기는 `DATE_FORMAT(col, '%Y-%m-%dT%H:%i:%s.%fZ')` 문자열 → `Instant.parse`, 비교는
  `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))` UTC 리터럴(바인딩 없음). §13 표 "jOOQ 시각 바인딩" 행.
- **범위 밖**: JPA 경로(아래 표에 근거만 기록), 새 의존성, 정책 변경, 빌드 파일.
- **원인**: driver 속성 `connectionTimeZone=UTC`는 `java.sql.Timestamp`(순간)를 UTC로 옮긴다. jOOQ는 `LocalDateTime` 바인딩 값을 JVM 기본 시간대로
  `Timestamp`로 만들기 때문에 Asia/Seoul JVM에서 비교 값이 9시간 앞선 순간(UTC 벽시계 − 9h)이 된다. 읽기도 반대 방향으로 같은 영향을 받는다(§9.1 RED).

## 2. 전수 점검 표

판정: **SAFE** = JVM 기본 시간대와 무관, **DEFECT** = 비UTC JVM에서 값이 어긋남, **N/A** = DB 시각 코드 없음.

| # | 모듈 | 위치 | 시각 사용 | 판정 | 근거 |
|---|---|---|---|---|---|
| 1 | master-api | `merchant/adapter/query/JooqMerchantStructureReader` | `EFFECTIVE_FROM/TO` 읽기 | SAFE | private `instant()` = `DATE_FORMAT` 문자열 → `Instant.parse`. 시각 비교 없음. RED 근거 `MerchantStructureQuerySpec`(Asia/Seoul) |
| 2 | master-api | `merchant/adapter/query/JooqAuthorizationTermsCoverageReader` | `EFFECTIVE_FROM.le(at)`, `EFFECTIVE_TO.gt(at)` | SAFE | `at` = private `utcLiteral(now)`(`CAST('…' AS DATETIME(3))` inline). §13 참조 구현 |
| 3 | master-api | `JooqEntitlementPropagationReader.merchant` | `ISSUED_AT`·`ACKED_AT`·`MIN(ACKED_AT)`·`MAX(LEASE_UNTIL)` 읽기, `ORDER BY ACKED_AT` | SAFE | 읽기는 `DATE_FORMAT`. 정렬은 저장 값 그대로. `now` 비교는 Java에서 파싱된 `Instant`끼리(`isAfter`) |
| 4 | master-api | `JooqEntitlementPropagationReader.overview` | `MIN(o.CREATED_AT)` 읽기 | SAFE | `DATE_FORMAT` |
| 5 | master-api | `JooqEntitlementPropagationReader.overview` | `i3.LEASE_UNTIL.gt(utc(now))` | **DEFECT** | `utc()` = `LocalDateTime.ofInstant(now, UTC)` 바인딩. Asia/Seoul JVM에서 `now − 9h`와 비교 → 끝난 지 9시간 안의 이전 lease를 "열림"으로 봐 `unconfirmedLatestRevisions`(gauge `UNCONFIRMED`)가 과다. RED §6.1 |
| 6 | master-api | `adapter/persistence/AdminRequestJpaRepository`(native INSERT `:at`), `OutboxEventJpaRepository`(native claim `next_attempt_at <= :now`, `claim_until < :now`) | `Instant` 파라미터 | SAFE (JPA) | Hibernate 바인딩 + `hibernate.jdbc.time_zone=UTC`(§9.1 (1)) + driver `connectionTimeZone=UTC`. jOOQ 경로 아님 |
| 7 | master-api | JPA Entity 전부(`Jpa*Entity`, 시각 필드 `Instant`) | 읽기·쓰기 | SAFE (JPA) | `hibernate.jdbc.time_zone=UTC`. 근거 `MasterUtcSessionSpec` |
| 8 | payment-api | `payment/adapter/query/JooqCheckoutViewReader` | `EXPIRES_AT`·`CHECKOUT_EXPIRES_AT` 읽기 | SAFE | `UtcWallClock.iso`/`parse`(AUTHN-001 S11, RED `CheckoutWebSpec`) |
| 9 | payment-api | `payment/adapter/query/JooqElapsedRequestedPaymentReader` | `CHECKOUT_EXPIRES_AT.le(…)`, keyset `row(...).gt(row(literal, …))`, 읽기 | SAFE | `UtcWallClock.literal`/`iso` |
| 10 | payment-api | `payment/adapter/query/JooqAuthenticationBacklogReader` | `CHECKOUT_EXPIRES_AT`·`EXPIRES_AT` `.le(at)` | SAFE | `at` = `UtcWallClock.literal(now)` |
| 11 | payment-api | `entitlement/adapter/query/JooqEntitlementFreshnessReader` | `LEASE_UNTIL.le(at)`, `LEASE_UNTIL.gt(at)`(`at` = `LocalDateTime`) | **DEFECT** | `LocalDateTime` 바인딩. Asia/Seoul JVM에서 `now − 9h`와 비교 → 끝난 지 9시간 안의 lease가 `expiredHeads`에서 빠지고 `minLeaseRemainingMillis`에 음수로 들어감(gauge만 영향, 판정은 JPA `FOR SHARE`). RED §6.1 |
| 12 | payment-api | 같은 reader | `TIMESTAMPDIFF(MICROSECOND, DSL.inline(at), LEASE_UNTIL)` | SAFE (통일) | `DSL.inline(LocalDateTime)`은 SQL 리터럴 벽시계로 렌더링되어 시간대 변환이 없다(대조 시험이 Asia/Seoul에서 1초 잔여를 맞게 계산 — §6.1). #11 수정에서 같은 UTC 리터럴로 통일(값 동일) |
| 13 | payment-api | `payment/adapter/persistence/PaymentDbSessionTimeZoneCheck` | `Timestamp.from(PROBE_INSTANT)` 바인딩, `getTimestamp().toInstant()` 읽기 | SAFE (의도) | `Timestamp`는 순간이며 driver가 `connectionTimeZone`으로 옮긴다 — 이 검사는 바로 그 driver 변환을 검증하는 기동 probe다(JVM 시간대 무관). `LocalDateTime` 없음 |
| 14 | payment-api | `payment/adapter/persistence/CheckoutWindowCheckMigrationStrategy` | `UTC_TIMESTAMP(3)`, `DATE_ADD(created_at, INTERVAL ? MICROSECOND)`(long 바인딩) | SAFE | 시각 값 바인딩·읽기 없음(서버 UTC 함수, 간격만 바인딩) |
| 15 | payment-api | native `OrderPaymentControlJpaRepository`(`:at`), `IdempotencyRequestJpaRepository`(`:createdAt`·`:expiresAt`), 잠금 조회(`ORDER BY created_at`, 시각 파라미터 없음) | `Instant` 파라미터 | SAFE (JPA) | Hibernate + `hibernate.jdbc.time_zone=UTC`. 근거 `UtcStorageSpec`(Asia/Seoul, 멱등 `expires_at`) |
| 16 | payment-api | JPA Entity 전부 | 읽기·쓰기 | SAFE (JPA) | `hibernate.jdbc.time_zone=UTC`, `UtcStorageSpec` |
| 17 | institution-simulator | `authentication/adapter/persistence`(JPA Entity·Repository만) | 읽기·쓰기 | SAFE (JPA) | jOOQ·plain JDBC 없음. `application.yml`에 `hibernate.jdbc.time_zone: UTC` + driver UTC |
| 18 | financial-batch | `FinancialBatchApplication`만 | — | N/A | DB 시각 코드 없음 |
| 19 | internal-admin | `InternalAdminApplication`, `SecurityConfig` | — | N/A | DB 시각 코드 없음 |
| 20 | libs/test-support | `MySqlTestContainerConfig`, `PayCoreArchitectureRules`, kotest `ProjectConfig` | — | N/A | 시험 기반(컨테이너 이미지·아키텍처 규칙), 시각 바인딩 없음 |

합계(20행): SAFE 15(이 중 JPA 5 — #6·#7·#15·#16·#17), DEFECT 2(#5, #11), N/A 3. 금융 의미(한도·정산일·업무일 계산)에 닿는 결함은 없다 — 두 결함 모두
운영 지표(gauge)만 바꾼다. 업무 판정 경로(권리 판정 `EntitlementEvaluationService` head `FOR SHARE`, 결제창 만료 잠금 재판정)는 JPA다.

**JPA 경로가 안전한 이유(역할 → 예 → 이유 → 주의)**: 역할 — JPA Entity adapter(`Jpa*Entity`, 순수 Domain과 분리)는 `Instant`를 `DATETIME(3)`에 쓴다. 예 — payment
`JpaEntitlementHeadEntity.leaseUntil`(`Instant`)이 이 작업 시험에서 Inbox 적용으로 저장된 뒤 jOOQ reader가 읽는 바로 그 값이다. 이유 — `hibernate.jdbc.time_zone=UTC`가
JPA·native 파라미터를 UTC Calendar로 바인딩하고 driver `connectionTimeZone=UTC`가 겹으로 고정한다(§9.1). 주의 — 같은 컬럼을 jOOQ 생성 필드(`LocalDateTime`)로
바인딩·읽으면 Hibernate를 거치지 않으므로 이 보장이 없다(이번 결함 #5·#11).

## 3. 수정 방식

- #5 master `JooqEntitlementPropagationReader`: private `utc()`(LocalDateTime) 삭제, private `utcLiteral(Instant): Field<LocalDateTime?>`
  (`CAST({0} AS DATETIME(3))` + `DSL.inline(UTC 포맷 문자열)`) 추가 — 같은 패키지 `JooqAuthorizationTermsCoverageReader`와 같은 형태.
- #11·#12 payment `JooqEntitlementFreshnessReader`: `at`을 같은 private `utcLiteral(now)`로 바꾸고 `TIMESTAMPDIFF` 첫 인자도 그 필드를 쓴다.
- **구현 결정(가역)**: helper 공유 대신 reader별 private 함수. payment의 `UtcWallClock`은 `payment` Modulith 모듈 내부(`adapter.query`)라 `entitlement` 모듈이
  쓰면 모듈 경계 위반이고, master는 같은 패키지의 기존 reader 두 개가 private 함수 관례(`instant()`, `utcLiteral()`)를 쓴다. 공통 helper 승격은 별도 정리 작업.
- 동작·API·스키마·의존성·빌드 파일 변경 없음. 정상(UTC JVM) 결과는 이전과 같다.

## 4. 시험 계획

비UTC JVM 강제: `MerchantStructureQuerySpec`과 같은 `withJvmTimeZone("Asia/Seoul")`(`TimeZone.setDefault` + Hikari `softEvictConnections`, 끝나면 원복). 같은
컨텍스트의 다른 시험 행과 섞이지 않게 같은 `now`에서 자기 데이터를 넣기 전·후의 **차이**로 판정한다. 데이터는 공식 경로로 만든다(명시 Named Fixture).

- payment `entitlement/adapter/query/EntitlementFreshnessQuerySpec`(`@EntitlementDbTest`)
  - 1분 Demo lease(`SnapshotFixture.leaseAt()`)를 Inbox로 적용, `now = leaseUntil + 1h`: heads +1, expiredHeads +1, `minLeaseRemainingMillis`는 null 또는 > 0.
  - 대조: `now = leaseUntil − 1s`: heads +1, expiredHeads +0, `minLeaseRemainingMillis ∈ [1, 1000]`.
- master `merchant/adapter/query/EntitlementPropagationQuerySpec`(`@MasterDbTest`)
  - `EntitlementCommandFixture.entitled`(ACK 없음 — 시험 설정은 Relay 주기 실행 끔). 시험 시계 고정이라 이전 lease 최댓값 = 발행 시각 + `leaseDuration`(조회로 확인).
  - `now = priorLeaseEnd − 1ms`: `unconfirmedLatestRevisions` +1 / `now = priorLeaseEnd + 1h`: +0.

## 5. 원칙·기술 확인

| 원칙 | 적용 | 근거 |
|---|---|---|
| Financial Consistency | 해당 없음 | 금액·상태·확정 사실을 읽거나 바꾸지 않는다(지표 조회만) |
| Transaction Safety | 해당 없음(유지) | 두 reader 모두 기존 `@Transactional(readOnly, REPEATABLE_READ)` 한 TX 그대로, 쓰기 없음 |
| Idempotency | 해당 없음 | 요청 키·결과 저장 경로 무변경 |
| Concurrency Control | 해당 없음 | 잠금·판정 경로(JPA) 무변경 |
| Recoverability | 간접 | 전파 미확인 수가 과다하면 운영자가 불필요한 재전송·조사를 하게 된다 — 정확한 값 복원 |
| Reconciliation | 간접 | `unconfirmedLatestRevisions`(MASTER→PAYMENT 효력 확인 대조 지표)가 JVM 시간대와 무관해짐(§4 master 시험) |
| Auditability | 해당 없음 | 기록 표 무변경 |
| Observability | **적용** | gauge `EXPIRED_HEADS`·`MIN_LEASE_REMAINING`(payment), `UNCONFIRMED`(master)가 비UTC JVM에서 9시간 어긋나던 결함 수정. 시험 §4 |

기술: jOOQ는 CQRS 조회 측(`adapter.query`, 쓰기 없음)에만 쓰고 시각 규칙 §9.1을 따른다. 빌드 파일·의존성·Boot BOM 조합 변경 없음(GRADLE-DEPS/CVE 재실행 불필요 근거).
Kafka/Redis 무관(ADR-001 — 도입하지 않았고 이 수정의 안전성 근거도 아니다).

## 6. 실행 기록

### 6.1 RED (수정 전 코드, 기준 `4e06ca7` + 새 시험만)

- 시각 2026-10-17T06:08:05Z–06:09:37Z, 호스트 WSL Ubuntu(TZ KST+0900), Docker Desktop, MySQL 8.4.11 컨테이너.
- 명령: `./gradlew --no-daemon --console=plain :apps:payment-api:test --tests '…EntitlementFreshnessQuerySpec' :apps:master-api:test --tests '…EntitlementPropagationQuerySpec' --continue` → exit 1.
- master `EntitlementPropagationQuerySpec`(1건, 실패 1): `EntitlementPropagationQuerySpec.kt:70` `expected:<0L> but was:<1L>` — lease가 1시간 전에 끝났는데 미확인으로 셈.
  앞선 단언(이전 lease 최댓값 = 발행 + 2s, ACK 없음, lease 열림 시 +1)은 통과.
- payment `EntitlementFreshnessQuerySpec`(2건, 실패 1): `EntitlementFreshnessQuerySpec.kt:56` `Element differ at index: [1] expected:<[1L, 1L]> but was:<[1L, 0L]>` —
  1시간 전에 끝난 lease를 만료로 세지 않음. 대조 시험은 통과(#12 TIMESTAMPDIFF inline 리터럴은 정상).

### 6.2 GREEN (수정 후)

- 시각 2026-10-17T06:11:02Z–06:12:32Z, 같은 명령 → exit 0. master 1건 통과, payment 2건 통과(실패·skip 0).

### 6.3 전체 검사

- 2026-10-17T06:12:51Z 시작한 전체 `GRADLE-CHECK`(`./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks check`)와 이어서 실행할 HARNESS-SELFTEST는
  `:apps:master-api:test` 실행 중 **호스트 메모리 부족으로 실행 도구가 중단**했다(exit code 없음, 보고서 불완전). 업무 RED/PASS가 아닌 **환경 중단**이며 전체 수·SELFTEST
  결과는 **미확보**다. 재실행은 Coordinator 지시 후(VERIFY-GATE가 새 fingerprint로 전체 검사·SELFTEST·테스트 수 1482 이상을 확인해야 한다).
- 현재 확보한 실행 증거는 §6.1 RED·§6.2 GREEN(대상 시험 3건)뿐이다.
