# PayCore 기술 버전 기준선 (빌드 검증 1차)

확인일 2026-10-03 KST. 상태: **payment-api skeleton 빌드·smoke — Coordinator 실행 통과, 독립 Verifier 최종 증거는 harness/tasks.json BOOT-001 evidence 참조**. Batch/Thymeleaf/jOOQ codegen/CVE는 TECH-001에서 검증 전.

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
| MySQL (Testcontainers/compose) | 8.4.11 | 사용자 확정 8.4 LTS 중 Docker Hub 최신 patch |

Boot 4.1.1 BOM 관리(override 없음): Spring Framework 7.0.9, Hibernate 7.4.5.Final, jOOQ 3.21.7, Flyway 12.4.0, Spring Batch 6.0.5, Testcontainers 2.0.5, MySQL Connector/J 9.7.0, Jackson 3.1.5, JUnit Jupiter 6.0.3, Thymeleaf 3.1.5.RELEASE.

BOM 밖 최신값과의 차이(의도적으로 override 안 함): jOOQ 3.21.9, Flyway 13.9.0, Connector/J 26.7.0, Kotlin 2.4.20. 보안 패치 필요 시에만 근거를 남기고 override한다.

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
- 주의: Entity에 `data class` equals/hashCode를 쓰지 않는다(식별자·프록시 규칙은 첫 Entity 작성 시 시험과 함께 설명).

## 4. 아직 검증하지 않은 것

TECH-001 범위: jOOQ codegen(Flyway schema→Testcontainers→codegen 재현), Spring Batch job 실행/재시작, Thymeleaf 렌더, JPA Entity 저장·`@Version` 충돌·rollback, 의존성 CVE 검사, Kafka/Redis 미도입 ADR.
