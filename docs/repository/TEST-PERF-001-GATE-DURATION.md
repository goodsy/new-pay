# TEST-PERF-001 VERIFY-GATE GRADLE-CHECK 소요 시간 단축 명세

작성 2026-11-01 KST, 기준 commit `406ebbf`(worktree `~/wt-perf`, detached). 이 문서는 새 업무 정책을 채택하지 않는다. 운영 코드·API·스키마·업무
기대값 변경 없음. 바뀌는 것은 **빌드 스케줄·시험 JVM/Gradle daemon JVM 옵션·시험 MySQL 컨테이너의 기동 방식**뿐이다.

## 1. 목표와 불변 조건

- 목표: GRADLE-CHECK(`./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks check`)가 기존 `timeoutSeconds` 2400초(40분) 안에
  여유 있게 끝난다(HEAD 기준 목표 ≤ 32분 — INST-CODE-001 시험 추가분 수용). 사용자 선택: "40분 제한 유지, 시험부터 빠르게".
- 바꾸지 않은 것(검증 방법은 §5):
  - `harness/commands.json` 전체(argv·`timeoutSeconds`·`minTests`·`outputMustMatch`/`outputMustNotMatch`·`expectedReports`). **argv 변경 없음.**
  - 시험·단언: 삭제·skip·tag 제외·기대값 변경 없음. 시험 수는 늘기만 한다(새 test-support 시험 +8).
  - 매 실행 새 컨테이너: Testcontainers `withReuse` 없음. 컨테이너를 시험 사이에 공유·재사용하지 않는다(§3.3).
  - 메모리 상한 관례(MASTER-BOOT-001): test JVM `maxHeapSize=1g`·`maxParallelForks=1`, Gradle daemon `-Xmx2g`, Kotlin in-process 컴파일.

## 2. 측정 전(기준)

같은 기계(WSL 7.8GB·8 CPU, Docker Desktop, Testcontainers MySQL 8.4.11), HEAD `406ebbf`, `--profile`만 덧붙인 같은 명령, 5초 간격 표본
(`/proc/meminfo` MemAvailable·java RSS 합·`docker ps` 수):

| 항목 | 값 |
|---|---|
| 전체 | **42m59s**(2,581초) BUILD SUCCESSFUL — 이 세션의 기계 부하에서 2400초 초과(직전 기록 38m46s, 2026-10-30) |
| test task 합 | payment-api 870s · internal-admin 834s · master-api 217s · simulator 171s · financial-batch 51s · test-support 35s = **2,177s(36.3분)** |
| 나머지 주요 task | payment-api compileKotlin 111s·generateJooqTest 75s·generateJooqMain 56s·compileTestKotlin 67s, internal-admin compileTestKotlin 66s 등(전부 직렬) |
| MySQL 컨테이너 기동 | internal-admin 31회(541s), payment-api 13회(275s), simulator 5회(92s) 등 — 한 번 약 17~21초 |
| MemAvailable 최저 | **324MB**(payment-api 시험 중, MySQL 4개 + test JVM 1.8GB + Gradle daemon RSS 2.8GB) |

관찰: (1) 시험 시간의 약 40%가 MySQL 컨테이너 기동이다. 기동은 `mysqld --initialize`(약 8초)·시간대 표 적재(약 5초)·임시 서버 정지/재시작이다.
(2) 모듈 간 task가 모두 직렬이라 컴파일·jOOQ codegen(약 6분) 동안 시험이 놀고, 시험 동안 CPU가 남는다. (3) 메모리 여유가 거의 없어 test JVM
두 개를 동시에 돌릴 수 없다(측정 최저 324MB에 test JVM 하나 + 컨테이너가 더 붙으면 바닥).

## 3. 변경

### 3.1 모듈 병렬 실행 + 무거운 task 한 번에 하나 (스케줄만)

- `gradle.properties`: `org.gradle.parallel=true`, `org.gradle.workers.max=4`.
- `build-logic` `com.paycore.buildlogic.ExclusiveSlots`(상태 없는 BuildService) + `maxParallelUsages = 1`:
  - `paycore.kotlin-app`: 실행 단위(apps/*)의 모든 Test task가 `paycoreDbTestJvmSlots`를 쓴다 → DB test JVM은 이전처럼 한 번에 하나.
  - `paycore.kotlin-base`: 모든 `KotlinCompile`이 `paycoreKotlinCompileSlots`를 쓴다 → in-process 컴파일(daemon heap 2g)은 한 번에 하나.
  - `paycore.frontend`: `frontendTest`(Vitest)는 두 슬롯을 모두 쓴다 → 컴파일·DB test JVM과 겹치지 않는다. r6에서 컴파일과 겹친 Vitest 시험 1건이
    기본 시험 timeout 5초를 넘겼다. timeout은 바꾸지 않고 겹침만 없앴다.
  - 결과: 겹치는 것은 "DB test JVM 하나" ↔ "codegen·tsc·vite build·DB 없는 libs 시험", "Kotlin 컴파일" ↔ 같은 것들 사이뿐이다.
- 왜 test JVM 2개 동시가 아닌가: §2(3). 측정 없이 늘리지 않는다.

### 3.2 Gradle daemon heap 반환 (JVM 옵션만)

`org.gradle.jvmargs`에 `-XX:+UseG1GC -XX:G1PeriodicGCInterval=30000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`. 상한 `-Xmx2g`는 그대로.
컴파일이 끝난 시험 단계에서 daemon이 쓰지 않는 heap을 OS에 돌려준다(측정: daemon RSS 최대 2.8GB → 시험 단계 약 2.0~2.2GB).

### 3.3 시험 MySQL 컨테이너: 한 개 앞서 시작(prefetch) — 재사용 아님

- `libs/test-support` `PrefetchedStarts<T>`: `take()`는 언제나 **새로 만들어 시작된, 아무에게도 준 적 없는** 인스턴스를 돌려주고, 다음 인스턴스의
  시작을 백그라운드 thread에 건다. 바뀌는 것은 시작 **시점**뿐이다(앞 컨텍스트의 시험이 도는 동안 다음 컨테이너가 기동). 받은 컨테이너의 중지는
  이전과 같이 받은 쪽(Spring 컨텍스트 bean 소멸·spec `afterSpec`/`finally`)이 한다. 남은 예비는 JVM 종료 hook이 중지한다(Ryuk도 정리).
  백그라운드 시작 실패는 받는 쪽에 그대로 던진다. `depth`(기본 1)는 측정상 2가 이득이 없어(§4 r3) 쓰지 않는다.
- 사용처: `AdminDbAccounts.startedContainer()`(internal-admin `AdminDbTestConfig` bean, `NoGeneratedDefaultUserSpec`),
  `PaymentDbAccounts.startedContainer()`(payment-api `PaymentMySqlContainerConfig` bean, `OldVolumeStartSpec`, `OutboxNotificationMigrationSpec`,
  `ApiLifecycleSpec`, `CheckoutWindowCheckMigrationStrategySpec` 5곳), `SimulatorDb.startedContainer()`(simulator bean, `SimulatorDatabaseAccountSpec`,
  `SimulatorRestartDurabilitySpec`). 모두 기존에 `newContainer()` 직후 `start()`하거나 bean으로 넘기던 곳이다(시작 뒤 설정을 바꾸는 호출 없음 — 전수 확인).
  설정을 바꾸는 호출(`AdminDbAccounts.newContainer(withAdmin = false)`)은 그대로 `newContainer()`를 쓴다.
- 증거 로그: 받는 thread가 `[PrefetchedStarts:<name>] handing out a newly created, already started instance: mysql:8.4.11 <containerId>`를 출력한다.
  Testcontainers의 "Container mysql:8.4.11 started" 줄은 백그라운드 thread가 찍으므로 **앞 spec의 system-out**에 들어갈 수 있다(smoke 시험의
  MySQL 버전 assertion은 그대로 각 spec이 한다).
- 메모리: 예비 MySQL 1개(약 0.4GB)가 더 떠 있다.

### 3.4 시험 MySQL 서버 기동 옵션 (crash 복구 관련만) — Coordinator 확인 필요(§6)

`MySqlTestContainerConfig.newTestMySqlContainer()`가 모든 시험 컨테이너(payment·admin·master·simulator·batch)의 공통 시작점이 된다:

| 옵션 | 효과 | 시험 의미 영향 |
|---|---|---|
| `MYSQL_INITDB_SKIP_TZINFO=1` | 초기화 때 `mysql.time_zone*` 표 적재 생략(약 5초) | 저장소의 SQL·설정·driver 속성은 이름 있는 시간대를 쓰지 않는다(`'+00:00'` offset, OS 시간대 SYSTEM/KST만 — 전수 grep, MasterUtcSessionSpec·AdminSchemaSpec 통과) |
| `--innodb-flush-log-at-trx-commit=2` | commit마다 redo fsync 대신 초 단위 | MySQL 서버가 비정상 종료될 때만 차이. 원자성·rollback·잠금·격리·제약 동일. 시험은 MySQL 서버를 죽이지 않는다(앱 재시작 시험만 있음) |
| `--sync-binlog=0` | binlog fsync를 OS에 맡김 | 같음 |
| `--innodb-doublewrite=OFF` | doublewrite buffer 끔 | 같음(torn page 복구용) |
| binlog | **켜 둔다**(`--skip-log-bin` 금지, `MySqlTestContainerConfigSpec`이 고정) | 운영처럼 binlog가 켜진 서버의 함수/procedure 생성 제약을 그대로 받는다 |

측정(빈 기계, 같은 초기화 스크립트·환경변수, `docker run` → 3306 ready): 기본 16.3~20.0초, 시간대 생략만 14.7~15.1초, 위 조합 7.9~13.0초,
메모리 424MB → 387MB. 빌드 중(컴파일과 겹침) 평균은 약 17초 → 약 12~17초.

### 3.5 시도했지만 채택하지 않은 것

- prefetch `depth = 2`(internal-admin): r3에서 기동이 서로 겹쳐 느려지고(평균 17초 → 19초) 전체가 오히려 늘었다(35m06s). `depth` 인자는 남기되 쓰지 않는다.
- 시험 JVM `-XX:TieredStopAtLevel=1`(C1만): r4에서 모듈별 시험 합이 internal-admin 615→621s, payment-api 621→633s로 개선 없음. 제거.
- test JVM 2개 동시: 메모리(§2(3)). 시도하지 않았다.
- tmpfs datadir: 기동은 빨라지나(7.5초) 컨테이너당 메모리 +200MB(621MB). 메모리 최저 구간에서 위험해 채택 안 함.

## 4. 측정 후

| 실행 | 변경 | 전체 | MemAvailable 최저 | 시험 |
|---|---|---|---|---|
| 기준 | 없음(`--profile`) | 42m59s | 324MB | 3,212 |
| r1 | §3.1·§3.2·§3.3(admin·payment) | 41m38s | 742MB | 3,216 |
| r2 | + §3.4 | 33m35s | 864MB | 3,218 / Vitest 66 |
| r3 | + simulator prefetch, admin depth 2 | 35m06s | 835MB | 3,220 |
| r4 | depth 1로 되돌림 + C1 JIT(§3.5) | 32m37s | 899MB | 3,220 |
| r5 | (무효) 실행 중 build-logic 파일을 고쳐 `:build-logic:compileKotlin` 실패 — 측정 아님 | — | — | — |
| r6 | C1 제거 + libs 시험을 DB 슬롯에서 제외 | **FAIL** 2m09s | — | Vitest 1건 5초 timeout |
| 최종 r7 | r6 + frontendTest를 컴파일·DB test 슬롯과 겹치지 않게(§3.1) | **34m13s**(2,055초) BUILD SUCCESSFUL | **792MB** | 3,220 / Vitest 66 |

### 4.1 최종 실행(r7) 상세

- 명령: GRADLE-CHECK argv 그대로(`./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks check`), `~/wt-perf`(HEAD `406ebbf` + 이 변경,
  미commit), 시작 2026-10-31T17:01:28Z(UTC), exit 0.
- TEST-SUMMARY(`--not-before` 시작 시각, 하한 3212): total 3,220, failures/errors/skipped 0 → PASS. TEST-SUMMARY-FRONTEND(하한 66): 66 → PASS.
- `outputMustMatch` 12개 모두 일치, `outputMustNotMatch` 6개 모두 불일치(로그 대조).
- 시험 task 창(실행 시작 기준 초): financial-batch 137–246, simulator 265–507, internal-admin 522–1197(675s), test-support 585–656(병렬),
  master-api 1236–1401, payment-api 1412–2042(630s). Vitest는 internal-admin 시험 직후 약 19초.
- 메모리: MemAvailable 최저 792MB(기준 324MB), java RSS 합 최대 4,190MB(기준 5,159MB), 동시 컨테이너 최대 9(codegen·ryuk·예비 포함).

### 4.2 판정

- 40분 timeout 대비 여유: 최종 34m13s(약 5.8분 여유). 같은 구성의 3회(r2·r4·r7)는 33m35s·32m37s·34m13s.
- **목표 ≤ 32분은 달성하지 못했다.** 남은 시간의 대부분은 DB 시험 JVM 직렬 구간(internal-admin 약 620–675s: Spring 컨텍스트 30개 × 컨텍스트마다
  새 컨테이너·Flyway·Spring 기동, payment-api 약 620–630s)이다. 더 줄이려면 시험 구성 자체(컨텍스트 수 통합) 또는 test JVM 동시 실행(메모리
  증설)이 필요하다 — 이번 범위 밖(§6).

## 5. 불변 조건 확인 방법

- `git diff -- harness/` 없음(명령 정의·상태 파일 무변경).
- TEST-SUMMARY(`--not-before` 실행 시작 시각, 하한 3212)·TEST-SUMMARY-FRONTEND(하한 66) PASS, failures/errors/skipped 0.
- 바뀐 시험 파일의 diff는 컨테이너를 얻는 한 줄(`newContainer().also { it.start() }` → `startedContainer()`)뿐이다. 단언 변경 없음.
- 새 시험: `PrefetchedStartsSpec`(6 — 매번 다른·시작된 인스턴스, 백그라운드 선시작, depth, 실패 전달, close 시 예비 중지),
  `MySqlTestContainerConfigSpec`(2 — 단일 이미지, 옵션 목록 고정·`--skip-log-bin` 없음, 매 호출 새·미시작 객체). RED: 구현 전 컴파일 실패
  (`Unresolved reference 'PrefetchedStarts'`/`'newTestMySqlContainer'`/`No parameter with name 'depth'`).

## 6. 미결·위험

- §3.4 MySQL 서버 옵션은 시험 환경이 개발/운영 MySQL 설정과 달라지는 지점이다(crash 복구 관련만, binlog 유지). 금융 Transaction Safety 시험(원자성·
  rollback·잠금 경합)의 의미는 바뀌지 않는다고 판단했지만, 채택은 Coordinator 확인 대상이다. 채택하지 않으면 §4 r1 수준(약 41분)으로 돌아간다.
- 측정 편차: 같은 구성도 실행마다 ±1.5분(Windows 호스트 메모리 압박 — 측정 중 Claude Code가 저메모리로 백그라운드 명령을 여러 번 중단).
- 병렬 실행에서 시험이 컴파일·codegen과 CPU를 나눠 쓰므로 시간 의존 시험(잠금 대기 timeout, Vitest 5초 등)의 여유가 줄 수 있다. 전체 실행 6회
  (r1·r2·r3·r4·r6·r7) 중 Kotlin 시험 실패 0, Vitest timeout 1건(r6 — §3.1로 겹침 제거, r7 통과). 재발하면 FAIL로 기록하고 timeout을 늘리지 않는다.
- 메모리: 최저 MemAvailable은 기준보다 높다(324MB → 800MB대). test JVM 2개 동시는 여전히 불가.
