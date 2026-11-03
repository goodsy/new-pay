# PayCore 기술 버전 기준선 (TECH-001)

확인일 2026-10-03 KST. 상태: BOOT-001 skeleton은 독립 검증 완료(harness/tasks.json BOOT-001). TECH-001의 기술 조합 smoke(§5)와 CVE 검사(§1.1)는 Implementer/Coordinator 실행 통과, **독립 Verifier/Reviewer 결과는 harness/tasks.json TECH-001 evidence 참조**.

## 1. 고정 버전

| 구분 | 버전 | 결정 근거 |
|---|---|---|
| JDK | OpenJDK 25.0.4.1 (로컬 Ubuntu 패키지) | 사용자 확정. Boot 4.1 지원 |
| Gradle Wrapper | 9.8.0, SHA-256 `bafd5ce9…8e6c` | 2026-09-24 current 릴리스. JDK 25 실행 지원, Boot 4.x 지원 범위(8.14+/9.x) |
| Spring Boot | 4.1.1 | D-TECH-001(사용자 답변). Maven Central 최신 stable |
| Kotlin (plugin jvm/spring/jpa) | 2.3.21 | Boot 4.1.1 BOM의 `kotlin.version`과 동일 |
| Spring Modulith | 2.1.1 (BOM import) | Boot 4.1 라인 최신 stable |
| Kotest | 6.2.5 | 최신 stable. Kotlin stdlib 2.2.21 기반 → 2.3.21과 호환 |
| MockK / springmockk | 1.14.11 / 5.0.1 | springmockk 5.x는 Spring Framework 7 기준 |
| ArchUnit | 1.5.1 | 최신 stable, Java 25 class 분석 통과 |
| Fixture Monkey (`fixture-monkey-kotlin`, 테스트 전용) | 1.2.3 | CP-DB-001(2026-10-07 Maven Central 최신 GA). Boot BOM 밖이라 catalog 고정. kotlin-reflect/stdlib는 BOM 2.3.21로 정렬. 전이 의존 jqwik 1.7.3·rgxgen 1.4는 testRuntimeClasspath에만 있고 값 생성에 jqwik 구현 모듈이 필요하다(엔진 모듈 제외 시 NoClassDefFoundError). jqwik JUnit 엔진은 payment-api test에서 `excludeEngines("jqwik")`. runtimeClasspath 유입은 `verifyRuntimeHasNoTestTools`가 check에서 차단, Instancio는 `verifyNoInstancio`가 차단 |
| MySQL (Testcontainers/compose) | 8.4.11 | 사용자 확정 8.4 LTS 중 Docker Hub 최신 patch |
| CycloneDX Gradle plugin | 3.4.1 | 실행 단위별 runtime SBOM(`cyclonedxDirectBom`, runtimeClasspath만) |
| osv-scanner (저장소 밖 도구) | 2.6.0, SHA-256 `ca69b3d3…b108` | `~/.local/bin`, 공식 SHA256SUMS로 검증 설치. `harness/scripts/cve-scan.py`가 SBOM을 검사 |

Modulith는 runtime에 `spring-modulith-api`만 둔다. 구조 검증용 `spring-modulith-core`(ArchUnit 포함)는 테스트(`spring-modulith-starter-test`)에만 있다. 이전 `starter-core`는 ArchUnit 1.4.2를 운영 runtime에 넣었다(TECH-001 Verifier 지적).

Boot 4.1.1 BOM 관리(override 없음): Spring Framework 7.0.9, Hibernate 7.4.5.Final, jOOQ 3.21.7(codegen 포함), Flyway 12.4.0, Spring Batch 6.0.5, Spring Security 7.x, Testcontainers 2.0.5, MySQL Connector/J 9.7.0, JUnit Jupiter 6.0.3, Thymeleaf 3.1.5.RELEASE. 예외는 §1.1의 보안 patch뿐이다.

BOM 밖 최신값과의 차이(의도적으로 override 안 함): jOOQ 3.21.9, Flyway 13.9.0, Connector/J 26.7.0, Kotlin 2.4.20. 보안 패치 필요 시에만 근거를 남기고 override한다.

### 1.1 보안 patch override

2026-10-03 CVE-SCAN에서 Boot 4.1.1 BOM 버전에 알려진 취약점이 있었다. 같은 minor 안의 patch로만 올렸다. 위치: 루트 `build.gradle.kts`(`resolutionStrategy.eachDependency`), 버전: `gradle/libs.versions.toml`의 `security-patch-*`.

| 구성요소 | BOM 버전 | 적용 버전 | 해소된 권고(OSV) | 제거 조건 |
|---|---|---|---|---|
| Tomcat embed (core/el/websocket) | 11.0.24 | 11.0.26 | GHSA-9xv2-5v5q-p794(CVE-2026-65905, DIGEST 인증 우회, 9.8), GHSA-gcx9-497g-6cp6(CVE-2026-65182, 9.1), GHSA-h3x4-894j-xpx5(CVE-2026-68525, FORM 인증, 9.1). 모두 11.0.25에서 수정 | Boot BOM의 `tomcat.version` ≥ 11.0.25 |
| Jackson 3 (`tools.jackson.*`) | 3.1.5 | 3.1.7 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54(3.1.7 수정), GHSA-q4xh-88c3-wmh7, GHSA-gx83-3vf8-gh7j, GHSA-wjgm-6hv5-3cvf(3.1.6 수정). 주로 DoS/검증 우회 | Boot BOM의 `jackson-bom.version` ≥ 3.1.7 |

- 적용 후 CVE-SCAN 결과(2026-10-03, ArchUnit runtime 제거 후): payment-api 106개, financial-batch 53개, internal-admin 58개 runtime 구성요소를 osv가 **모두 스캔**(스캔 수 = SBOM 구성요소 수)했고 알려진 취약점 0건. 양성 대조(canary)는 매 모듈 검사 안에서 검출된다(§1.1 CVE-SCAN 판정). SBOM에서 tomcat 11.0.26, jackson 3.1.7 실제 해석을 확인했다.
- 영향 평가: 현재 앱은 Tomcat DIGEST/FORM 인증을 쓰지 않는다(internal-admin은 Spring Security, 인증 방식 미결). 그래도 운영 진입 전 취약 버전을 남기지 않기 위해 지금 올린다.
- 주의: patch override는 Boot가 시험한 조합을 벗어난다. 전체 GRADLE-CHECK(27 tests)로 회귀를 확인했다. override는 Boot 4.1.1에만 묶여 있어 Boot를 올리면 빌드가 멈추고 재평가를 요구한다. 또한 올리기만 한다: 다른 의존성이 숫자만으로 된 더 새 GA 버전을 명시하면 그대로 둔다(오래된 patch로 내리지 않음). pre-release(예: 3.2.0-rc1, 11.1.0-M1)는 숫자가 더 커도 "더 새 버전"으로 보지 않고 GA patch로 강제한다. 따라서 pre-release가 꼭 필요한 업그레이드는 GRADLE-DEPS 검토에서 드러나며 override를 다시 판단해야 한다.
- 범위: SBOM은 앱 runtime만 담는다. jOOQ 생성기 classpath(Testcontainers 등)·Gradle plugin·테스트 의존성은 스캔하지 않는다(운영 배포물이 아님).
- CVE-SCAN은 OSV API 네트워크를 쓰므로 VERIFY-GATE에 넣지 않는다. 의존성·빌드 파일이 바뀌는 작업과 릴리스 전 별도로 실행한다. 새 권고는 매일 생기므로 "0건"은 스캔 시각 기준이다.
- CVE-SCAN 판정: osv 바이너리 SHA-256 대조, 이전 결과·SBOM 삭제 후 재생성(`--no-build-cache --rerun-tasks`로 SBOM task를 build cache·up-to-date 결과에서 복원하지 않고 실제 실행한다 — FOUNDATION-001 round3 O-1: 이전 argv는 cache 복원을 허용했다), 고정 설정(`harness/osv-scanner.toml`, 무시 목록 없음). canary는 PayCore runtime에 나타날 수 없는 알려진 취약 artifact(log4j-core 2.14.1, Log4Shell; 앱은 Logback 사용)다. osv 결과는 group 없이 이름·버전만 주므로, 모듈 SBOM에 canary와 같은 이름·버전이 있으면 합치기 전에 FAIL(exit 1)한다. 그렇지 않으면 모듈 SBOM+canary를 **한 번의 osv 호출**로 검사하고, canary 취약점 검출·스캔 수 = 모듈+canary 수·osv exit 1을 모두 요구한다(모듈별 조회가 실제로 동작했다는 증거). 어긋나면 exit 3(ENVIRONMENT), SBOM 빌드 실패는 exit 1(FAIL). 판정 로직은 `test_cve_scan.py`로 고정.

## 2. 출처

- Boot 3.5 OSS 종료: <https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/> — "This is the last OSS release of the 3.5.x generation."
- Boot 3.5 시스템 요건(Java 17–25, Gradle 7.6.4+/8.4+): <https://docs.spring.io/spring-boot/3.5/system-requirements.html>
- Boot 4.1.1 BOM: `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom`
- 라이브러리 버전: 각 Maven Central `maven-metadata.xml` (2026-10-03 조회)
- Gradle: <https://services.gradle.org/versions/current>, 배포 `.sha256`
- MySQL 이미지 tag: Docker Hub `library/mysql` tags API

## 3. 이번 skeleton의 JPA 선택 설명

**`open-in-view: false`**
- 하는 일: HTTP 요청이 끝날 때까지 DB 세션(영속성 컨텍스트)을 열어 두는 기능을 끈다.
- 예: Controller가 응답을 만들다가 `payment.items`를 처음 읽어도 그 시점에 SQL이 나가지 않는다. 필요한 데이터는 Application/Query adapter가 미리 DTO로 만든다.
- 이유: 요청 내내 커넥션을 쥐지 않아 승인 경로 커넥션 고갈을 막고, 어떤 SQL이 언제 나가는지 TX 경계 안에서 통제한다.
- 주의: 끄면 TX 밖에서 LAZY 연관을 읽을 때 `LazyInitializationException`이 난다. 이것은 버그 신호다(DTO 조회를 query adapter에 추가해 해결).

**`ddl-auto: validate`**
- 하는 일: Hibernate가 테이블을 만들거나 바꾸지 않고, Entity 매핑이 실제 schema와 맞는지만 기동 시 검사한다.
- 예: Entity가 매핑한 `status` 컬럼이 Flyway로 만든 테이블에 없거나 타입이 호환되지 않으면 기동이 실패한다. Entity가 매핑하지 않은 DB 컬럼이 더 있는 것은 실패 사유가 아니다.
- 이유: schema 기준은 Flyway migration 하나다(운영 `create/update` 금지 원칙).
- 주의: validate는 컬럼 존재·타입만 본다. UNIQUE/CHECK 제약이나 collation은 검사하지 않으므로 DB 제약 테스트를 별도로 둔다.

**Kotlin `allOpen`(Entity/Embeddable/MappedSuperclass)과 `plugin.jpa`(no-arg)**
- 하는 일: Kotlin 클래스는 기본 final이라 Hibernate가 지연 로딩 프록시(하위 클래스)를 만들 수 없다. 이 plugin이 JPA annotation이 붙은 클래스만 open으로, 인자 없는 생성자를 추가한다.
- 예: adapter의 `JpaPaymentEntity`는 프록시 가능, 순수 Domain `Payment`에는 JPA annotation이 없으므로 영향 없음.
- 주의: Entity에 `data class` equals/hashCode를 쓰지 않는다(§4에서 시험 코드와 함께 설명).

## 4. TECH-001 smoke의 JPA 설명 (시험 코드 기준)

**`@Version`과 낙관적 잠금** (`TechSmokeItemEntity.version`, `TechSmokeJpaSpec`의 optimistic lock 시험)
- 하는 일: UPDATE마다 Hibernate가 `WHERE id=? AND version=?`을 붙이고 version을 1 올린다.
- 예: 거래 A가 id=2를 version 0으로 읽는다. 거래 B(별도 연결)가 이름을 바꿔 commit해 version이 1이 된다. A가 commit하면 `UPDATE … WHERE version=0`이 0행에 맞아 `OptimisticLockingFailureException`이 나고, 행은 B의 값(version 1)을 유지한다.
- 이유: 사용자·외부 지연 동안 행 잠금을 쥐지 않고도 오래된 쓰기를 감지한다.
- 주의: `@Version`을 빼면 A가 B를 조용히 덮어쓴다(Implementer 자체 mutation 확인, 저장소의 재현 시험은 없음). 충돌 시 금융 요청을 무작정 재시도하지 말고 현재 상태·기존 요청 결과를 다시 읽는다. Version은 새 행 삽입 경쟁을 막지 못하므로 UNIQUE가 따로 필요하다.

**영속성 컨텍스트·변경 감지, flush와 commit의 차이** (`TechSmokeJpaSpec`)
- 하는 일: TX 안에서 읽은 Entity는 관리 상태다. `findById(2).name = "…"`처럼 속성만 바꿔도 `save()` 없이 flush 때 UPDATE가 나간다. commit이 flush를 일으킨다.
- 예: rollback 시험에서 `saveAndFlush`는 TX 안에서 INSERT를 DB로 보낸다. 같은 TX의 JdbcTemplate은 그 행을 본다. 이후 예외로 TX가 rollback되면 행은 사라진다.
- 이유/의미: flush는 "SQL 실행", commit은 "영구·다른 연결에 보임". 금융 사실은 commit만 사실로 친다.
- 주의: 분리된 객체나 순수 Domain 객체의 변경은 추적되지 않는다. Mapper가 상태와 version을 명시적으로 옮겨야 한다.

**Entity를 `data class`로 만들지 않는 이유** (`TechSmokeItemEntity` KDoc)
- data class는 모든 필드(바뀌는 `name`, `version` 포함)로 equals/hashCode를 만든다. 같은 행이 값이 바뀌면 "다른 객체"가 되어 Set/Map에서 사라진다. 자동 toString/copy는 연관이 생기면 LAZY 로딩·순환을 일으킬 수 있다.
- 여기서는 `Hibernate.getClass()`를 쓴 id 기반 equals로 프록시와 실제 객체를 같게 본다. id가 생성 시 정해지므로 hashCode가 안정적이다.
- 주의: DB가 id를 생성하는 Entity는 persist 전 id가 null이라 이 규칙을 그대로 쓸 수 없다. 첫 업무 Entity의 ID 전략(미결)과 함께 다시 정한다.

## 5. TECH-001 기술 조합 smoke 결과

| 영역 | 위치 | 확인한 것 | 확인하지 않은 것 |
|---|---|---|---|
| JPA + MySQL | payment-api 테스트 `techsmoke` | 저장·조회, `@Version` 충돌 후 값 보존, TX rollback 후 행 없음 | 다중 스레드 경합, 비관적 잠금·deadlock·lock timeout, LAZY/N+1 |
| jOOQ codegen | payment-api `jooqCodegen` source set, `generateJooqMain/Test` | Flyway migration을 실제 MySQL 8.4.11 컨테이너에 적용한 live schema에서 생성(DDLDatabase/H2 아님), 생성 클래스로 JPA가 쓴 행 조회 | main schema는 아직 비어 있어 생성 클래스 0개. JPA·jOOQ가 같은 TX에 참여하는지는 미검증(같은 commit 데이터를 본다는 것만 확인) |
| Spring Batch 6 | financial-batch(신규), JDBC JobRepository, 시험용 metadata migration(PROVISIONAL) | chunk 실패 시 그 chunk rollback, 같은 파라미터 재시작 시 checkpoint부터 중복 없이 완료(인스턴스 1, 실행 2) | 프로세스 강제 종료, 동시 실행, 외부 효과 exactly-once, skip/retry 정책 |
| Thymeleaf + Security | internal-admin(신규), DB 없음 | 기본 거부(미인증 401/403), CSRF hidden input 렌더, CSRF 없는 POST 403, Boot 기본 사용자/비밀번호 미생성 | 실제 인증 방식(미결), 보안 헤더·CSP |
| 공급망 | CycloneDX SBOM + osv-scanner | §1.1 | 비밀 탐지(gitleaks 등)는 HARNESS-002/CI |

설계 메모:
- jOOQ 생성기는 별도 source set이라 Testcontainers/codegen이 앱 runtime classpath에 들어가지 않는다(SBOM에서 확인). 생성 코드는 `build/`에만 두고 커밋하지 않는다. **컴파일에 Docker가 필요**하다(입력이 같으면 up-to-date로 건너뜀).
- Batch metadata(BATCH_*) DDL(spring-batch-core 6.0.5 `schema-mysql.sql` 원문, Apache-2.0, 출처·hash를 파일 머리에 기록)은 **시험용 migration 위치에만** 있다(PROVISIONAL). 운영 위치·권한 분리·보존 정책(Q-BATCH-META)이 답변되기 전에는 `db/migration/financial`에 두지 않는다. 따라서 지금 financial-batch를 실제 DB로 실행하면 JobRepository 테이블이 없다(실제 Job도 없음).
- 시험용 테이블은 `src/test/resources/db/testmigration/**`의 `V900__tech_smoke.sql`에만 있다. 운영 migration 위치에는 업무/시험 DDL이 없다.
- JPA smoke Entity는 `com.paycore.techsmoke.payment` 패키지(테스트 소스)에 있다. 앱 패키지 아래에 두면 다른 테스트 컨텍스트가 그 Entity를 스캔해 `ddl-auto: validate`가 실패하기 때문이다. 해당 smoke 컨텍스트에서만 `@AutoConfigurationPackage`로 추가한다.

## 6. 아직 검증하지 않은 것

- jOOQ 생성 코드의 Modulith 배치와 공통 빌드/시험 기반은 FOUNDATION-001 stage2에서 구현했다(docs/repository/ARCHITECTURE-CONVENTIONS.md). 생성 패키지 제외는 fixture로 증명했고, 실제 main 생성 클래스는 첫 PAYMENT migration 이후 생긴다.
- 비밀 탐지, CI 연동 → HARNESS-002.

## 7. 실행 단위 모듈과 빌드 메모리 관례 (MASTER-BOOT-001, 2026-10-08)

### 7.1 모듈 목록

| Gradle 모듈 | 종류 | 소유 DB | runtime SBOM/CVE·GRADLE-DEPS |
|---|---|---|---|
| `:apps:master-api` | 실행 단위(`com.paycore.master`) | MASTER | 대상 |
| `:apps:payment-api` | 실행 단위(`com.paycore.payment`) | PAYMENT | 대상 |
| `:apps:financial-batch` | 실행 단위(`com.paycore.financial`) | FINANCIAL | 대상 |
| `:apps:internal-admin` | 실행 단위(`com.paycore.admin`) | 없음 | 대상 |
| `:libs:test-support` | 시험 라이브러리(testImplementation 전용) | 없음 | 대상 아님 |

master-api 기술 구성(버전은 모두 Boot 4.1.1 BOM, 새 catalog 항목·override 없음): webmvc, data-jpa, jooq, flyway(+flyway-mysql), actuator(health만 노출), modulith-api, kotlin-reflect, jackson-module-kotlin, mysql-connector-j(runtimeOnly). 시험: test-support, Kotest, ArchUnit, Testcontainers MySQL 8.4.11, modulith-starter-test. MockK·Fixture Monkey는 아직 쓸 시험이 없어 넣지 않았다(첫 Domain/Application 시험에서 추가).

- jOOQ: `DSLContext`(MySQL dialect)만 기동한다. codegen은 생성할 MASTER 테이블이 없어 **이월**(MASTER-DOMAIN-001에서 payment-api와 같은 방식 — Flyway migration → MySQL 8.4.11 컨테이너 → 생성 코드 `build/`만, `jooqOnlyInQueryAdapter`·생성 패키지 제외 설정 포함). 빈 schema 생성은 컴파일마다 컨테이너 하나를 더 띄우는 비용만 있고 검증 가치가 없다.
- Flyway: 운영 위치 `classpath:db/migration/master`는 비어 있다(업무 테이블은 MASTER-DOMAIN-001). 시험은 시험 전용 위치(`db/testmigration/master/V900__tech_session_probe.sql`)만 추가한다.
- 계정 분리(CP-DB-001 결정 적용 → PAYMENT-DBACCOUNT-001 표 단위 권한): 앱 DataSource = runtime 계정(`paycore_master`/`paycore_payment`, DB 단위 권한 없이 기본 role `<db>_runtime`의 표 단위 권한만, DDL 불가), Flyway = migration 계정(`<db>_migration`, 자기 DB ALL·GRANT OPTION 없음, `spring.flyway.user/password` — payment-api도 PAYMENT-DBACCOUNT-001에서 같은 바인딩·`init-sqls` UTC 적용). 표 단위 권한은 권한 migration(`db/runtime-privilege/<owner>`)이 초기화 스크립트의 procedure `<db>_privilege.set_runtime_table_privileges`로 준다(받는 쪽 role 고정, append-only 표는 SELECT·INSERT만) — 규칙·PAYMENT 표별 권한은 ARCHITECTURE-CONVENTIONS §9.2. MASTER는 아직 업무 표가 없어 runtime role에 표 권한이 없고, 그래서 master-api는 시험(probe 표 권한 V901) 밖에서는 MASTER DB에 접속하지 못한다(1044) — 첫 표와 권한 migration은 MASTER-DOMAIN-001. FINANCIAL은 Q-BATCH-META 답변 후. 버전 영향 없음: 새 의존성·BOM override 없이 MySQL 8.4.11 기능(role, `REVOKE IF EXISTS`, SQL SECURITY DEFINER procedure)과 Flyway 12.4.0 기본 placeholder `${flyway:database}`만 쓴다.
- UTC: payment-api와 같은 driver 속성(`connectionTimeZone=UTC`, `forceConnectionTimeZoneToSession=true`)과 `hibernate.jdbc.time_zone=UTC`. 계정 분리로 Flyway가 별도 연결을 만들면 이 driver 속성이 넘어가지 않으므로 `spring.flyway.init-sqls: SET time_zone = '+00:00'`을 추가했다(시험 컨테이너 OS 시간대 Asia/Seoul에서 이 줄을 빼면 migration 세션이 `SYSTEM`(+09:00)으로 남는 것을 확인 — MasterUtcSessionSpec).

master-api의 JPA 설정(이번 변경과 연결):
- `ddl-auto: validate` — 하는 일: 기동 시 Entity 매핑과 실제 schema를 비교만 한다. 예: 지금 master-api는 Entity가 0개라 검사 대상이 없고(smoke가 `metamodel.entities.size == 0` 확인), MASTER-DOMAIN-001에서 Merchant Entity를 추가하면 그 컬럼이 Flyway migration에 없을 때 기동이 실패한다. 이유: schema 기준은 Flyway 하나. 주의: 게다가 runtime 계정은 DDL 권한이 없어 `update/create`로 바꿔도 운영에서는 실패한다 — 권한과 설정이 같은 방향을 가리킨다.
- `open-in-view: false` — 요청 내내 영속성 컨텍스트·연결을 쥐지 않는다. Master 조회 API는 jOOQ query adapter가 DTO를 만든다(§3과 같은 이유).
- `hibernate.jdbc.time_zone: UTC` — Hibernate가 시각 파라미터를 UTC Calendar로 바인딩한다. 예: JVM 기본 시간대가 Asia/Seoul이어도 정책 유효 시작 시각 `2026-10-08T01:02:03Z`가 DB에 `2026-10-08 01:02:03`으로 저장된다. 주의: Hibernate 밖 경로(jOOQ·JDBC)는 driver 속성이 맡는다.
- 순수 Domain과 JPA Entity 분리는 payment-api 관례 그대로다(Entity·Spring Data repository는 `<module>.adapter.persistence`만, ArchitectureSpec이 master-api에도 모든 규칙을 적용).

### 7.2 빌드 메모리 관례와 측정

4번째 실행 단위로 전체 check가 더 무거워져(직전 전체 check에서 WSL 가용 메모리 최저 약 800–900MB, Claude Code 호스트가 Gradle을 두 번 중단) 판정 기준(`--no-build-cache --rerun-tasks`, 모든 test 실행)은 그대로 두고 최대 메모리만 낮췄다. 가역 관례다.

| 설정 | 위치 | 값 | 이유 |
|---|---|---|---|
| Kotlin 컴파일 실행 방식 | `gradle.properties`, `build-logic/gradle.properties` | `kotlin.compiler.execution.strategy=in-process` | 이전에는 Kotlin compile daemon이 두 개(build-logic Kotlin DSL용 2.4.x, 앱용 2.3.21) 떠서 각각 RSS 약 1.0GB·1.2GB(`-Xmx2g`)를 쓰고, `--no-daemon` 빌드가 끝난 뒤에도 2시간 idle 동안 남았다(고아 프로세스의 원인). in-process는 Gradle daemon heap(-Xmx2g) 안에서 컴파일한다 |
| test JVM heap | `build-logic` `paycore.kotlin-base`(모든 Test task) | `maxHeapSize = "1g"` | 기본값은 JVM 기본(물리 메모리의 1/4 ≈ 1.9GB). payment-api 395 시험이 1g에서 통과 |
| test fork 수 | 같은 곳 | `maxParallelForks = 1`(기본값 명시) | DB 시험은 Spring 컨텍스트마다 MySQL 컨테이너를 띄운다. fork를 늘리면 컨테이너·JVM이 같이 늘어난다 |
| Gradle daemon heap | `gradle.properties` | `-Xmx2g` 유지 | in-process 컴파일을 담는다. 낮추는 것은 측정 없이 하지 않는다. TEST-PERF-001에서 상한은 그대로 두고 G1 주기 GC·여유 비율만 추가(§7.3) |
| `org.gradle.parallel`/`workers.max` | `gradle.properties` | ~~기본값 유지(parallel 꺼짐)~~ → TEST-PERF-001: `parallel=true`, `workers.max=4` | §7.3. test JVM과 in-process Kotlin 컴파일은 여전히 각각 한 번에 하나(`ExclusiveSlots`) — 모듈 간 test task는 여전히 동시에 돌지 않는다 |
| Testcontainers 재사용 | — | 쓰지 않음(`withReuse` 없음) | 매 실행 새 컨테이너가 판정 기준이다 |

측정(같은 명령 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks :apps:payment-api:test`, 가장 무거운 모듈, WSL 7.8GB, 2초 간격 `/proc/meminfo` MemAvailable·java RSS 합·컨테이너 수 표본, 사용자 개발 MySQL 컨테이너 1개가 계속 떠 있음):

| | 변경 전(09:28–09:37Z) | 변경 후(09:55–10:02Z) |
|---|---|---|
| MemAvailable 최저 | 987MB | 2,063MB |
| java 프로세스 RSS 합 최대 | 4,508MB(Gradle daemon + Kotlin daemon 2개 + test JVM) | 3,334MB(Gradle daemon + test JVM) |
| 동시 컨테이너 최대(사용자 1 + ryuk 1 포함) | 5 | 5 |
| 빌드 후 남은 Kotlin daemon | 2개(약 2.2GB, 수동 종료) | 0 |
| 소요/결과 | 8m43s, BUILD SUCCESSFUL | 7m32s, BUILD SUCCESSFUL(395 tests) |

남은 큰 항목은 payment-api의 Spring 테스트 컨텍스트별 MySQL 컨테이너(동시 3개)다. 컨텍스트 캐시 크기 제한(`spring.test.context.cache.maxSize`)은 컨텍스트 재생성·컨테이너 재기동 시간과 GRADLE-CHECK timeout(1200초) 영향을 측정한 뒤 결정한다(후속, 이번에는 적용하지 않음). 전체 check 측정 결과는 harness/tasks.json MASTER-BOOT-001 implementation에 기록한다. (후속: TEST-PERF-001 §7.3 — 캐시 크기는 그대로 두고 컨테이너 기동 시점·옵션으로 대응)

### 7.3 GRADLE-CHECK 소요 시간 단축 (TEST-PERF-001, 2026-11-01)

GRADLE-CHECK가 timeout 2400초에 걸려(같은 기계 기준 측정 42m59s) 판정 명령·timeout·시험은 그대로 두고 스케줄과 시험 컨테이너 기동만 바꿨다.
상세·실행별 수치: [TEST-PERF-001-GATE-DURATION.md](TEST-PERF-001-GATE-DURATION.md). 가역 관례다.

| 설정 | 위치 | 값 | 이유 |
|---|---|---|---|
| 모듈 병렬 실행 | `gradle.properties` | `org.gradle.parallel=true`, `org.gradle.workers.max=4` | 컴파일·jOOQ codegen·프론트(직렬 약 6분)가 시험과 겹친다 |
| DB test JVM 동시 수 | `build-logic` `paycore.kotlin-app` + `ExclusiveSlots` | 1 | test JVM 2개 + 그 MySQL 컨테이너를 겹치면 가용 메모리가 바닥난다(기준 최저 324MB). 직렬 순서만 유지. DB 없는 libs 시험은 제외 |
| in-process Kotlin 컴파일 동시 수 | `build-logic` `paycore.kotlin-base` + `ExclusiveSlots` | 1 | daemon heap(-Xmx2g) 하나를 나눠 쓴다 |
| Gradle daemon GC | `gradle.properties` `org.gradle.jvmargs` | `-XX:+UseG1GC -XX:G1PeriodicGCInterval=30000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` | 컴파일 뒤 쓰지 않는 heap을 OS에 돌려준다(상한 -Xmx2g 그대로) |
| 시험 MySQL 기동 옵션 | `libs/test-support` `MySqlTestContainerConfig.newTestMySqlContainer()` | `MYSQL_INITDB_SKIP_TZINFO=1`, `--innodb-flush-log-at-trx-commit=2 --sync-binlog=0 --innodb-doublewrite=OFF`(binlog는 켬) | 기동 16~20초 → 8~13초. crash 복구에만 관계(원자성·잠금·격리 동일). Coordinator 확인 대상 |
| 시험 MySQL 미리 시작 | `libs/test-support` `PrefetchedStarts` — payment·admin·simulator `startedContainer()` | 예비 1개 | 앞 컨텍스트 시험 중 다음 **새** 컨테이너를 기동(재사용 아님, `withReuse` 없음). 예비 MySQL 약 0.4GB |

측정(전체 GRADLE-CHECK, 같은 기계, 5초 간격 MemAvailable·java RSS 합·컨테이너 수 표본):

| | 변경 전 | 변경 후(최종) |
|---|---|---|
| 소요 | 42m59s | 34m13s(같은 구성 3회 32m37s–34m13s) |
| MemAvailable 최저 | 324MB | 792MB |
| java RSS 합 최대 | 5,159MB | 4,190MB |
| 동시 컨테이너 최대(ryuk·codegen 포함) | 6 | 9 |
| 시험 수(Kotlin/Vitest) | 3,212 / 66 | 3,220 / 66 |

### 7.4 GRADLE-CHECK 소요 시간 2차 (TEST-PERF-002, 2026-11-03)

목표: timeout 2400초·gate 기준(minTests 3329 / Vitest 132·출력 규칙)·시험·단언은 그대로 두고 30분 이하(여유 25%). **결과: 목표 미달.**
사용자 결정(2026-11-03): "관리자 개선만 남기고 마무리" — internal-admin 개선(초기화 사본 + spec 묶음 순서)만 유지하고 최적화를 멈춘다. 이후 gate는 Demo MySQL·Demo JVM을 모두 멈춘 상태에서 실행한다(약 35분 / 한도 40분). D-TEST-PERF-002.

| 설정 | 위치 | 적용 범위 | 내용 |
|---|---|---|---|
| spec 묶음 순서 | `libs/test-support` `SpringContextGroupedSpecOrder`(Kotest `SpecExecutionOrderExtension`, `ProjectConfig` 등록) | 모든 모듈 | 이름순 정렬 뒤 같은 Spring `MergedContextConfiguration`(컨텍스트 캐시 key)의 spec을 붙인다. 순서만 바뀐다(시험·컨텍스트 설정·캐시 상한 `maxSize=4` 동일). 순서 의존 실패는 발생하지 않았다 |
| 초기화 MySQL 사본 | `libs/test-support` `InitializedMySqlSnapshot` — internal-admin `AdminDbAccounts.startedContainer()` | internal-admin만 | 시험 JVM당 1회 실제 초기화(같은 이미지 8.4.11·초기화 스크립트·환경변수·OS 시간대·서버 옵션, datadir만 `/var/lib/paycore-mysql`) → 정상 종료 → `docker commit`(세션 label). 이후 컨텍스트마다 그 이미지로 **새** 컨테이너(공유·reuse 없음, Flyway·데이터 처음부터). JVM 종료 hook이 예비 컨테이너 정지 뒤 이미지 삭제(남으면 Ryuk). 증거: `InitializedMySqlSnapshotIsolationSpec`(사본 2개 = 새 초기화 서버와 계정·권한·role·procedure·schema 동일, 서로 쓰기 격리) |
| 매번 새 초기화 유지 | internal-admin `@AdminFreshInitDbTest`(AdminDatabaseAccountSpec), payment-api·simulator·master-api·financial-batch 전체 | — | 초기화 스크립트 실행 결과 자체를 보는 계정·권한 시험은 빈 MySQL에서 매번 초기화(사용자 결정) |
| 귀속 로그 | `PrefetchedStarts`(`waited Nms`), `InitializedMySqlSnapshot`(`committed … in Nms`), `SpringContextGroupedSpecOrder`(`sorted in Nms`) | — | 받는 쪽 기동 대기·사본 생성·정렬 시간. 한 줄씩(시험 XML system-out) |

payment-api·simulator 사본은 시험해 보고 되돌렸다(after1): 사본 1회 생성 약 45~50초에 비해 재사용 컨테이너가 적고(simulator 사본 3회), 새 초기화 유지 컨텍스트(payment `PaymentDbTest`·`EntitlementDbTest`, simulator 2 spec)가 미리 시작 없이 동기 기동이 돼 task가 늘었다(payment 10m56 → 11m56, simulator 4m24 → 5m16).

측정(전체 GRADLE-CHECK + `--profile`, 같은 기계, Demo MySQL stop·Demo JVM 없음, 5초 표본):

| | 기준(변경 전) | after1(전 모듈 사본 + 순서) | after2(최종: admin 사본 + 순서) |
|---|---|---|---|
| 소요(exit) | 2108s = 35m08s (0) | 2043s = 34m03s (0) | 2097s = 34m56s (0) |
| internal-admin:test | 12m15 | 8m02 | 10m15 |
| payment-api:test | 10m56 | 11m56 | 11m23 |
| institution-simulator:test | 4m24 | 5m16 | 5m03 |
| master-api:test | 3m24 | 3m37 | 3m29 |
| financial-batch:test | 2m08 | 2m10 | 1m59 |
| libs/test-support:test | 1m27 | 1m09 | 1m33 |
| 시험 수 Kotlin / Vitest | (기준 run) | 3,336 / 132, 실패 0 | 3,336 / 132, 실패 0 |
| MemAvailable 최저 | 686MB | 673MB | 678MB |
| 빌드 뒤 남은 사본 이미지 | — | 0 | 0 |

internal-admin 단독 A/B(묶음 순서 유지, `:apps:internal-admin:test --rerun-tasks`): 사본 ON 587s(591 통과) / OFF 744s(590 통과 — 실패 1건은 사본 이미지 이름을 단언하는 isolation spec으로 OFF 구성에서 예상된 실패) → 사본 효과 약 −157s. 묶음 순서만의 효과는 작다(기준 12m15 ≈ OFF 12m22). 컨텍스트 기동 30 → 23.

주의: 같은 구성의 run 사이 편차가 모듈당 ±1분 정도다(변경 없는 master-api도 run마다 움직였다). 벽시계 시간은 시험 전 단계(약 130~195초) + 직렬 DB test task(ExclusiveSlots) 합이라 test task 시간만 줄여야 줄어든다. 전체 2108 → 2097s는 편차 안이다.

하지 않은 방안(사용자 결정으로 중단, 재개 시 질문 대상):
- (a) `@AdminFreshInitDbTest` 컨테이너 미리 시작 — 약 −10s, 새 초기화 전용 예비 컨테이너가 더 떠 메모리 증가(최저 678MB).
- (c) payment-api ArchitectureSpec(42s)의 ClassFileImporter/Modulith import 공유 — 약 −10~15s 추정, import 범위(시험 클래스 포함 여부)가 바뀌어 검증 강도 영향 가능.
- (d) 첫 DB 시험 앞 대기(130~195s)를 Gradle task 순서로 줄이기 — 빌드 스케줄 변경.
- DB test JVM 동시 실행·prefetch depth 2 — 메모리 한계로 제외(TEST-PERF-001 §7.3과 같은 이유).

## 8. payment-api 배포 규칙 (CP-API-001, 2026-10-14)

배포 환경이 지켜야 하는 규칙이다(운영 수치 결정이 아니다). 어기면 기동 단계에서 실패한다(fail-closed).

| 규칙 | 내용 | 강제 |
|---|---|---|
| DB URL 시간대 파라미터 금지(L-10) | `PAYCORE_PAYMENT_DB_URL`에 `connectionTimeZone`·`serverTimezone`·`forceConnectionTimeZoneToSession`·`preserveInstants`·`sessionVariables`의 `time_zone`을 넣지 않는다. 세션 UTC(`+00:00`)는 `application.yml`의 Hikari driver 속성이 유일한 출처다(관례 §9.1) | `PaymentDbSessionTimeZoneCheck`: URL에 있으면 기동 실패, 풀 연결의 `@@session.time_zone`이 `+00:00`이 아니면 기동 실패, health `paymentDbSessionTimeZone` DOWN |
| 내부 connector 포트 | `paycore.payment.api.internal-port`(필수, 기본값 없음)는 공개 포트와 다른 포트. `/internal` 아래 경로(권리 Snapshot Inbox)는 이 포트에서만 응답하고 공개 포트에서는 404. 운영망에서는 이 포트를 MASTER 망에만 열고 LB/프록시는 공개 포트만 외부에 노출한다(mTLS는 D-MS-004대로 LIVE 전 별도) | 필수 설정 누락 시 기동 실패, `ApiEntryFilter` |
| Retry-After 설정 | `paycore.payment.api.retry-after.{eligibility-unavailable,temporarily-unavailable,request-in-progress}`(필수, 1s..1h). 재시도 권고값이며 lease·전파 시간을 약속하지 않는다 | 필수 설정 누락·범위 밖이면 기동 실패 |
| 가맹점 Credential | 운영 Credential 투영(`merchant_api_credential`)은 MASTER-CREDENTIAL-001 전까지 비어 있다 — 모든 가맹점 요청이 401(D-CPA-001 fail-closed). 시험 키는 저장소에 없다(시험이 실행마다 생성) | 투영 runtime 권한 SELECT만(V6) |
| Health | 공개 포트의 `/actuator/health`는 상태만(`show-details: never`) | `MainConfigurationSpec` |
| binlog | `idempotency_request` IODKU는 statement binlog에서 unsafe — 운영 `binlog_format=ROW` 전제(CP-DB-001 review L-2) | 운영 작업 |

## 9. 프론트 빌드 도구 (ADMIN-BOOT-001 S1, 2026-10-24)

Boot BOM 밖·**빌드 전용**이다(앱 runtime·runtime SBOM에 들어가지 않는다). 선택 근거·실측은 `docs/repository/ADMIN-BOOT-001-ADMIN-FOUNDATION.md` §17.
기본 규칙: 게시 14일 이상 안정판(선택일 2026-10-24 → cutoff 2026-10-10), 직접·전이 의존 모두(`--before`). 예외는 아래 §9.1 보안 patch뿐이다.

| 구분 | 버전 | 근거 |
|---|---|---|
| node-gradle plugin `com.github.node-gradle.node` | 7.1.0 | 2024-09-27 최신 GA. Gradle 9.8.0에서 deprecation 경고 0건 실측. Node 배포판 내려받기·압축 해제만 맡긴다(npm 실행은 격리 Exec task) |
| Node.js | 24.22.1 (Krypton LTS) | §9.1 보안 patch 예외. `node-v24.22.1-linux-x64.tar.gz` SHA-256 `eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c`(SHASUMS256.txt — GPG Good signature, 키 `5BE8A3F6C8A5C01D106C0AD820B1A390B168D356`, nodejs/release-keys). 동봉 npm 11.19.1 |
| react / react-dom | 19.3.0 | 2026-09-09 |
| vite | 8.3.4 | 2026-10-08 (번들러 rolldown — 네이티브 binding은 optionalDependencies, `--ignore-scripts`로 동작) |
| vitest | 5.0.3 | 2026-09-30 |
| typescript | 7.0.2 | 2026-07-08 (네이티브 컴파일러) |
| @vitejs/plugin-react | 6.1.2 | 2026-10-05 |
| @types/react / @types/react-dom | 19.3.0 | 2026-09-09 |
| jsdom | 30.1.2 | 2026-10-04 |
| @testing-library/react / jest-dom / user-event | 16.3.3 / 7.0.1 / 14.6.7 | 2026-08-27 / 2026-08-09 / 2026-09-02 |

- 출처: `https://nodejs.org/dist/index.json`·`SHASUMS256.txt(.asc)`, `https://registry.npmjs.org/<pkg>`의 `time`, plugins.gradle.org `maven-metadata.xml`·GitHub releases(모두 2026-10-24 조회).
- 저장 위치: catalog(`node-gradle`·`node`·`node-sha256-linux-x64`), 루트 settings `nodejs-dist` ivy 저장소(`exclusiveContent` — FAIL_ON_PROJECT_REPOS), `apps/internal-admin/frontend/package.json`(정확 버전)·`package-lock.json`(v3, registry 고정·sha512)·`lockfile-policy.json`(cutoff·보안 override 근거)·`.npmrc`.
- 실행 경로: `paycore.frontend` convention plugin — `frontendNodeVerify`(압축 SHA-256) → `nodeSetup` → `frontendLockfileCheck`(오프라인 공급망 규칙) → `frontendInstall`(`npm ci --ignore-scripts`, 격리 환경: 상속 환경 버림·`npm_config_userconfig` 빈 파일·고정 PATH/HOME — S0 §17.7) → `frontendTypecheck`·`frontendTest`(Vitest JUnit)·`frontendBuild`(static/). lockfile 재생성은 `frontendLockfileRegenerate`(2단계, 네트워크, check 밖).
- CVE: `cve-scan.py`가 프론트 lockfile을 npm canary(dependencies lodash 4.17.20 + devDependencies minimist 1.2.5)와 한 번의 osv 호출로 검사(D-ADMIN1-007 — dev 의존 취약점도 FAIL).

- 화면 runtime 의존성은 `react`·`react-dom`뿐이다(라우터·상태 관리·HTTP 클라이언트·QR 라이브러리 없음 — §9.2).

### 9.1 보안 patch 예외 (14일 규칙 예외 — Coordinator 판정 2026-10-24)

| 구성요소 | 규칙상 버전 | 적용 버전(게시일) | 해소 권고 | 판정 | 제거 조건 |
|---|---|---|---|---|---|
| Node.js | 24.21.0 (2026-09-07) | **24.22.1** (2026-10-20) | CVE-2026-106069·CVE-2026-106072(tls 비정규 IP), CVE-2026-105482(dns null byte), CVE-2026-105483(tls IP 정규화 회귀), CVE-2026-111497(brotli) — High, 그 밖 Medium 2·Low 1(`https://nodejs.org/en/blog/release/v24.22.1`) | Q-S0-1 B(같은 24.x LTS 라인, 빌드 도구) | 게시 14일 경과(2026-11-03) 뒤 재평가 — 그 시점의 규칙상 최신 24.x로 대체하고 이 행 삭제 |
| undici (jsdom 30.1.2 경유 devDependency) | 8.11.2 (`--before` 해석) | **8.12.2** (2026-10-15) — `package.json` `overrides` | GHSA-xjj6-f33q-929c(7.5), GHSA-4qv8-2p9j-2685(5.9), GHSA-f72v-6w45-q472(5.9) — 8.11.x patch 없음 | Q-S0-2 A | jsdom이 undici ≥ 8.12.2를 직접 요구하거나, cutoff `--before`가 override 없이 ≥ 8.12.2를 해석하면 override와 `lockfile-policy.json` 항목 삭제 |

### 9.2 화면 runtime 관례·변경 기록 (ADMIN-BOOT-001 S7·S8, 2026-10-26)

- 의존성: S1 이후 npm 의존성 변화 없음 — `package-lock.json` SHA-256 `15a4ed3e42e8ac259bd534967817ee7f95c67d5e2651df7ab6526d506cb60b23`(S8 CVE-SCAN 재확인, S7과 같음). 화면(S7·S8)은 react 19.3.0 표준 API만 쓴다.
- 서빙: 같은 출처 정적 번들(`static/` — CSP `'self'`, 인라인 script·style 없음 `SpaServingSpec`), 브라우저 저장소 미사용(Vitest `storageIsEmpty`), 오류는 서버 고정 code를 HTTP 상태와 함께 신뢰할 때만.
- 시험: Vitest 39(`TEST-SUMMARY-FRONTEND`, JUnit 보고서 신선도 `--not-before`).
- **LIVE 전 검토(후속 — Q-S7-1)**: TOTP 등록 QR. 현재는 설정 키 수동 입력만이며, QR 라이브러리를 넣으면 이 표의 14일 규칙·CVE(dev 포함)·lockfile·라이선스 검토를 거친다(자체 인코더는 시험 부담 — 대안).
- actuator(Boot BOM, S2): management 포트 분리(D-15 — ADMIN-BOOT-001 §25), health 응답은 상세 없이 probe 그룹 이름만(Boot 4.1.1 실측 `{"groups":["liveness","readiness"],"status":"UP"}`).
