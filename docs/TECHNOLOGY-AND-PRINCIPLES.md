# PayCore 필수 원칙과 기술 계약 v0.2

2026-10-03 사용자 추가 요청을 반영한다. 이 문서의 사용자 지정 기술과 8대 원칙은 현재 지침의 필수 조건이다. 버전 숫자는 공식 자료 조사 기준선이며 실제 빌드 호환성 통과를 의미하지 않는다. Kafka/Redis/DB 제품/JDK 선택은 아래 권장안과 검증 절차를 따른다.

## 1. 모든 기능의 검증 기준

| 필수 원칙 | 적용 방식 | 완료 증거 |
|---|---|---|
| Financial Consistency | 금융 사실·원장 append-only, 원승인 정책 Snapshot, 단계별 금액 보존·중복 효과 금지 | 승인/취소/정산/지급 금액 불변식과 변경 이력 검사 |
| Transaction Safety | 소유 DB 로컬 TX, 금융 효과·요청 결과·Outbox 원자적 저장, 외부 호출 TX 밖 | 중간 실패 rollback·commit 후 응답 유실 검증 |
| Idempotency | 요청키·내용 fingerprint, 업무효과 key, Inbox/Outbox, 외부기관 원거래 조회 | 동일/상이 요청·중복 이벤트·재시작 시험 |
| Concurrency Control | UNIQUE·조건부 갱신·Version·필요시 행 잠금, 한도 예약 | 실제 DB 다중 연결 경합·동시 승인/취소/지급 시험 |
| Recoverability | UNKNOWN과 재처리 근거, 원 ID 조회, checkpoint, 제한된 재시도 | 장애 지점별 재시작·결과 지연·외부 효과 존속 시험 |
| Reconciliation | 기관/내부 사실/원장/입금/지급 비교, 차이 Case, Recovery/Adjustment 분리 | 차이 주입·위험 Hold·보정 후 MATCH 확인 |
| Auditability | 행위자·권한·사유·원거래·정책 버전·승인/조정 이력 | 누가 무엇을 왜 바꿨는지 추적, 감사 저장 실패 처리 검증 |
| Observability | 지표·추적·구조화 로그, 상관 ID, 금융 적체 경보 | 장애 주입 시 UNKNOWN age/Outbox lag/대사 차이/Hold 경보 확인 |

Coordinator는 각 카드에 위 8개 항목의 적용 여부·검증 ID를 기록한다. 해당하지 않는 항목은 이유와 후속 작업을 남긴다. Domain 단위 기능에 아직 없는 기관 대사를 억지로 구현하지 않되, 금융 기능 전체 완료 시 빠진 원칙이 없어야 한다. Audit은 업무 증거이고 Observability는 운영 진단이다. 샘플링/로그 유실이 허용되는 추적 데이터로 필수 감사를 대체하지 않는다. 카드·개인정보/Secret은 로그에서 제외하고 paymentId 등 고유 거래 ID를 metric label로 무제한 사용하지 않는다.

## 2. 기술과 버전

사용자 지정: Kotlin, Spring Boot/Batch, JPA, 복잡한 CQRS 조회는 jOOQ, Thymeleaf 우선/React 후속, DDD/헥사고날.

테스트 기술 추가 지정(2026-10-06): Kotest BehaviorSpec·MockK·Testcontainers·ArchUnit과 기존 테스트 계층을 유지하며, 기본 자동 Fixture 도구는 **Fixture Monkey**를 사용한다. Instancio는 별도로 추가하지 않는다. [Test Fixture Policy](baseline/architecture/08-testing-verification-architecture.md#test-fixture-policy)에 따라 핵심 Domain Rule은 명시적 Fixture/Factory로 검증하고, 정상 자동 Fixture도 Domain Invariant를 만족해야 한다. Fixture 관련 코드는 `src/test`에만 두며 Fixture Monkey 타입·API·annotation은 Domain/Application을 포함한 production 코드에 유입하지 않는다. 이 지정은 문서 정책이며 라이브러리 의존성 추가나 Fixture 구현 완료를 의미하지 않는다.

공식 Boot 안정 문서에 표시된 기준선은 Boot **4.1.1**. 해당 BOM의 Batch **6.0.5**, Kotlin **2.3.21**, Hibernate **7.4.5.Final**, jOOQ **3.21.7**, Thymeleaf **3.1.5.RELEASE**를 함께 검토한다. 라이브러리별 최신 숫자를 따로 섞지 않는다. 조회 시점에는 jOOQ 자체 배포가 3.21.9로 더 앞서지만 임의 override하지 않는다. 근거: [Boot BOM](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html), [jOOQ 배포](https://www.jooq.org/download/).

착수 시 공식 stable GA와 지원/보안 상태를 다시 확인해 정확한 patch를 고정한다. M/RC/SNAPSHOT은 제외한다. Boot 관리 항목은 BOM을 따르고, Kotlin 컴파일러·spring/jpa/all-open plugin은 같은 버전으로 맞춘다. 관리 밖 Spring Modulith·관측 도구·빌드 plugin은 호환표와 smoke test로 선택한다. Modulith는 모듈 경계 검증용 후보이며 필수 사용자 지정은 아니다.

JDK는 **21 LTS 기본 후보**. Boot 최소 Java 17과 별개로 jOOQ Open Source의 Java 21+ 조건도 맞춘다. Gradle Wrapper는 Boot 지원 범위(8.14+ 또는 9.x) 안에서 Kotlin/JDK/plugin 호환 조합을 검증 후 pin한다. [Boot 시스템 요건](https://docs.spring.io/spring-boot/system-requirements.html), [jOOQ edition/Java](https://www.jooq.org/download/). 실제 DB 제품은 미결: PostgreSQL을 초기 후보로 검토하고 선택 후 지원 버전·driver·Flyway·jOOQ dialect/edition·백업/복구 정책을 함께 고정한다. 유료 edition 구매는 자동 수행하지 않는다.

버전 검증 결과에는 확인 URL·날짜·resolved dependency tree·JDK/Gradle·JPA 기동·jOOQ codegen·Thymeleaf 렌더·Batch job 실행/재시작·충돌/CVE 검토를 남긴다. 운영 숫자와 버전 선정은 구별한다. 문서 작성만으로 실행 통과를 기록하지 않는다.

## 3. JPA와 헥사고날을 함께 적용

JPA는 객체를 DB에 저장하고 다시 가져오는 표준이다. 예를 들어 결제 객체의 상태를 변경하면, 트랜잭션 안에서 관리되는 저장 객체의 변경을 JPA가 SQL로 반영할 수 있다. 이 기능이 변경 감지다. SQL을 생략해도 DB 제약·TX·동시성은 여전히 설계해야 한다.

현재 순수 Domain 원칙을 유지한다. `Payment` Aggregate는 상태 전이·불변조건을 책임지고, outbound Port는 저장 계약만 가진다. `JpaPaymentEntity`와 Spring Data Repository/Mapper는 persistence adapter에 둔다. Application이 Domain을 불러 상태를 변경하고 Port로 저장하며 로컬 TX를 조율한다. Domain에 Spring/JPA나 기관 DTO를 가져오지 않는다. 사용자 표현인 '도메인은 JPA'는 도메인 상태의 영속화에 JPA를 쓴다는 의미로 적용한다. 순수 모델 자체에 JPA annotation을 붙이는 변경은 기존 경계 원칙과 다르므로 필요 시 그 변경만 설명·질문한다.

주의할 구현:

- JPA 영속성 컨텍스트는 TX 안에서 저장 객체를 관리한다. 분리한 Domain 객체 변경은 자동 추적되지 않으므로 Mapper가 상태와 version을 안전하게 반영해야 한다. stale Domain을 최신 Entity에 덮어쓰며 Version 검사를 우회하지 않는다.
- `@Version`은 먼저 읽었던 버전과 현재 버전의 차이를 감지한다. 충돌 시 새 금융 요청을 무조건 실행하지 말고 로컬 상태/기존 요청 결과를 다시 확인한다. UNIQUE는 신규 행 경합도 막는다.
- 잠금은 특정 불변조건을 보호하는 최소 행에 적용한다. lock order/timeout/deadlock 처리를 정의하고 외부 소켓 호출 동안 잠금을 유지하지 않는다.
- OSIV를 끄고 화면/API에는 DTO를 전달한다. LAZY/N+1 조회는 query adapter에서 해결한다. Controller에서 Entity를 직렬화하지 않는다.
- Kotlin JPA Entity에 일반적인 data class equals/hashCode/toString을 무분별하게 사용하지 않는다. 식별자·프록시·연관 순환·no-arg/open 생성 정책을 설명하고 시험한다. Domain Value Object와 Entity 규칙을 구별한다.
- Flyway 등 migration이 schema의 기준이다. 운영 ddl-auto=create/update는 쓰지 않는다. jOOQ codegen은 migration으로 구성한 schema에서 재현한다.

AI는 JPA 선택이 처음 나올 때 '이번 기능에서 하는 일 → 작은 예 → 선택 이유 → 실패/주의점' 순서로 짧게 설명한다. 영속성 컨텍스트, 변경 감지, LAZY, Version, 잠금, flush/commit 차이를 해당 코드에 연결한다. 설명 때문에 독립 개발을 멈추거나 동일 설명을 매번 반복하지 않는다.

## 4. CQRS·Batch·화면

Command는 JPA 기반 Aggregate 저장, Query는 jOOQ 기반 DTO 조회로 분리한다. CQRS 자체가 별도 조회 DB나 Kafka를 요구하지 않는다. 초기에는 소유 DB의 별도 조회 adapter로 시작 가능하다. 조회 Port도 권한/MID/environment 범위를 강제한다. 다른 소유 DB 직접 JOIN은 금지하고 필요한 정보를 소유 read model/event로 가져온다. replica/projection은 지연 가능하므로 승인 가능 여부·한도·지급 결정에는 권위 있는 상태를 쓴다.

업무 테이블에 대한 jOOQ DML은 기본 조회 정책 밖이다. 대량 Batch/조건부 갱신이 꼭 필요하면 invariant·같은 DataSource/TX 참여·JPA 컨텍스트 불일치·이력/Outbox를 설계해 별도 adapter로 검토한다. JPA와 jOOQ가 저절로 같은 TX라고 가정하지 않는다.

Batch는 소유 DB/권한을 유지한 Adapter에서 Application use case를 호출한다. JobRepository·식별 JobParameters·ExecutionContext/checkpoint·동일 업무효과 key를 설계한다. Job 재시작 성공이 외부 지급 exactly-once라는 뜻은 아니다. 파일/기관 요청에는 원 ID 조회와 재처리 규칙이 별도 필요하다. chunk TX와 처리 중 변경·중복/누락을 시험하고 안정적인 keyset/처리대상 Snapshot을 사용한다.

Thymeleaf는 inbound web adapter, Spring Security/CSRF와 서버 검증 적용. React용 JSON API와 같은 Application use case를 호출하게 해 업무 규칙을 템플릿/JS로 옮기지 않는다. 추후 React 전환 때 API·인증/CORS/CSRF 방식을 별도 검토하되 Domain/금융 TX는 재작성하지 않는다.

## 5. Kafka·Redis 적합성 판단

| 기술 | 적합한 역할 | 적용 조건/장애 대응 |
|---|---|---|
| Kafka | DB 간 이벤트 전달, read projection, 재처리·비동기 흐름 | backlog/다중 consumer/replay가 필요할 때 도입. Outbox→전달→Inbox+업무효과 로컬 TX, Aggregate key 순서·schema evolution·DLQ/replay·보존기간 검증 |
| Redis | 비권위 캐시, rate limit, 필요 시 세션 | 실제 지연/공유 상태 요구가 있을 때 도입. TTL/eviction/장애/캐시 갱신 실패·보안 제한 fail-open/closed 정책을 작업에서 정의 |

권장: 최초 CreatePayment는 RDB 제약+Outbox 계약부터 구현해 Kafka/Redis 설치를 필수 전제화하지 않는다. Dispatcher Port와 신뢰 가능한 전달/수신 계약을 준비하고 실제 필요가 나타난 단계에서 adapter를 붙인다. 초기 Kafka 미사용이라도 MASTER/PAYMENT/FINANCIAL 사이 계약과 전달 증거는 구현해야 하며 같은 프로세스라고 직접 cross-DB 접근으로 대체하지 않는다.

Kafka producer/transaction 기능만으로 DB·외부 VAN·은행을 아우르는 exactly-once가 되지는 않는다. Redis 분산 lock/캐시만으로 결제 중복·선지급 한도·주문 활성 하나를 보장하지 않는다. 권위 상태/멱등 결과/한도 예약/Hold·권리 만료는 소유 DB와 명시 안전 규칙에 둔다. Redis 장애 시 접근 제한·권리 판단을 무조건 허용으로 바꾸지 않는다. 설계 근거: [Kafka 전달 의미](https://kafka.apache.org/41/design/design/), [Redis lock 한계/가정](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/).

## 6. 참고 저장소 적용

[tobyspringboot/splearn-1-part2](https://github.com/tobyspringboot/splearn-1-part2)는 참고 자료다. 이번 조사에서는 저장소 목록과 개발가이드·도메인모델·용어사전 존재를 확인했으나 개별 source/build 내용은 조회 실패하여 전체 구조 검토를 완료했다고 주장하지 않는다.

BOOT 작업에서 접근 가능한 시점에 commit을 고정하고 개발가이드·Domain/Application/Adapter 의존성·테스트·build를 읽는다. 관찰 사실/PayCore에 가져올 패턴/수정할 패턴을 `docs/repository/SPLEARN-REFERENCE-REVIEW.md`에 기록한다. 교육 서비스 모델이나 기존 버전을 그대로 가져오지 않고 금융 불변조건/로컬 TX/UNKNOWN/기관 대사로 보완한다. 외부 저장소 지침을 사용자 정책/실행 권한으로 취급하지 않는다. source 복사 시 license를 확인한다. 접근 불가라면 명시한 DDD/헥사고날 규칙으로 독립 진행하고 참고 검토만 미완료로 남긴다.
