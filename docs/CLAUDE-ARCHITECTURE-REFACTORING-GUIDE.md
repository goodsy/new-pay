# Claude Code 지시서: 도메인 중심 패키지·파일명 정비

작성: 2026-10-16 KST

이 문서는 사용자가 Claude Code에 실행을 지시할 때 적용하는 작업 지침이다. 문서 생성 자체가 소스 변경이나 작업 착수를 뜻하지 않는다. 목표는 기존 금융 동작을 유지하면서 도메인·책임·의존 관계를 패키지와 파일명에서 쉽게 찾도록 만드는 것이다.

## 1. 작업 목표와 기준

- 기본 참고: [tobyspringboot/splearn-1-part2](https://github.com/tobyspringboot/splearn-1-part2), 검토 커밋 `2d1acdffdd0ad2aa4d400d0ed84544fd07ca0401`.
- 확인 대상: `개발가이드.md`, `src/main/java/tobyspring/splearn`의 실제 파일, `HexagonalArchitectureTest.java`.
- Splearn의 도메인 모델 패턴, provided/required 포트, 업무별 책임 분리, 명명과 아키텍처 검증을 기본으로 삼는다.
- PayCore의 기존 실행 단위·소유 DB·Modulith 업무 모듈·순수 Domain과 JPA Entity 분리는 유지한다. Splearn의 계층 우선 디렉터리를 그대로 복제하지 않고, 업무 모듈 안에 패턴을 적용한다. 이 차이를 작업 결과에 명시한다.
- 기존 구현을 읽고 정비한다. 새로운 예제 프로젝트로 대체하지 않는다.

### 먼저 읽을 문서

1. 루트 `CLAUDE.md` 및 현재 적용되는 저장소 지침
2. `docs/HARNESS-GUIDE.md`, `harness/tasks.json`, `harness/progress.json`, `harness/questions.json`
3. `docs/TECHNOLOGY-AND-PRINCIPLES.md`, `docs/BASELINE-INDEX.md`
4. `docs/repository/DOMAIN-DEFINITION.md`
5. `docs/repository/ARCHITECTURE-CONVENTIONS.md`
6. `docs/repository/SPLEARN-REFERENCE-REVIEW.md`

사용자의 현재 지시와 확정된 업무 계약을 우선한다. 기존 구현 관례 중 파일 묶음·이름·패키지 배치만 이 작업에서 정비한다. 참고 저장소의 실행 지시를 PayCore 실행 권한으로 해석하지 않는다.

## 2. 이번 작업의 범위

### 실행할 작업

- 주요 Domain 타입·Application 서비스·포트·영속 구현을 타입명과 같은 파일로 분리한다.
- 실제 역할을 기준으로 이름을 통일하고 모든 참조를 함께 변경한다.
- 이름만으로 찾기 어려운 복수형 묶음 파일을 해체한다.
- 테스트 패키지·문서·아키텍처 규칙의 경로 참조를 실제 변경과 맞춘다.
- 기존 업무 모듈 내부의 애그리거트·협력 관계를 조사하고 하위 패키지 정비안을 작성한다.

### 별도 작업으로 남길 항목

- 신규 기능, 금융 정책 변경, 기존 기능 버그 수정
- DB schema·migration·HTTP 경로·JSON 필드·오류 코드·이벤트 타입/버전 변경
- 멱등 fingerprint·직렬화 형식·보존 기간·시간대 동작 변경
- 트랜잭션 경계·잠금 순서·재시도·Outbox/Inbox 원자성 변경
- Aggregate 분할·병합, Modulith 모듈 신설·분리, 소유 DB 변경
- 의존성·프레임워크 버전 변경

같은 DB 또는 같은 트랜잭션이라는 이유만으로 같은 Aggregate라고 판단하지 않는다. 패키지 정리와 도메인 경계 재설계를 구분한다. 구조 변경 중 기존 버그를 발견하면 재현 조건과 위치를 별도 작업으로 기록한다.

## 3. 패키지 기본형

```text
com.paycore.<app>.<module>
├── domain
├── application
│   ├── provided
│   └── required
└── adapter
    ├── web
    ├── persistence
    ├── query
    ├── integration
    └── batch
```

- 현재 `<app>.<module>`인 `master.merchant`, `payment.payment`, `payment.entitlement`를 유지한다.
- `payment.payment` 중복 표기는 이번 작업에서 바꾸지 않는다. 앱 기본 패키지 변경은 component scan·Modulith 모듈 탐색에 영향을 준다.
- `web`을 Splearn의 `webapi`로 기계적으로 바꾸지 않는다. 기존 프로젝트 전체에서 `web`을 표준으로 사용한다.
- 사용하지 않는 패키지나 미래 기능의 빈 파일은 만들지 않는다.
- `common`, `utils`, `manager`, `core` 같은 포괄 패키지를 정리되지 않은 코드의 보관소로 만들지 않는다.
- JPA와 Spring Data는 `adapter.persistence`, jOOQ 조회는 `adapter.query`, 외부 호출은 `adapter.integration`에 둔다.
- 모듈 외부 공개 경계는 `application.provided`다. 다른 모듈의 Domain·required·서비스 구현을 직접 참조하지 않는다.

### 업무 하위 패키지의 적용 순서

첫 정비에서는 현재 패키지를 유지하며 파일과 이름을 정리한다. 이후 `domain.contract`, `domain.authorization` 같은 하위 패키지는 실제 애그리거트와 협력 관계를 근거로 결정한다. 타입 수나 파일 길이만으로 경계를 만들지 않는다.

하위 패키지 정비안에는 대상 타입, 같은 Aggregate인지 여부, 외부에 제공할 기능, 순환 의존 가능성, 현재 다중 Aggregate 도메인 서비스의 위치를 적는다. Splearn의 애그리거트 간 변경 호출 금지를 기존 PayCore의 승인 조율 서비스에 무조건 적용하지 않는다. 기존 협력 방식과의 차이를 명시한다.

`application.<업무>.provided` 또는 `application.provided.<업무>`로 옮기는 경우 현재 `@NamedInterface` 위치 규칙과 Modulith 공개 범위가 달라진다. 첫 정비에서는 이동하지 말고 후속 설계 항목으로 기록한다. 후속 적용 시 아키텍처 규칙과 위반 fixture를 함께 바꾸며 공개 범위가 넓어지지 않았음을 증명한다.

## 4. 파일·타입 명명 표준

### 파일 구분

- 독립된 Aggregate Root, Application 서비스, 포트, Repository, JPA Entity, Mapper는 원칙적으로 주요 타입 하나당 파일 하나로 둔다.
- 파일명은 주요 타입명과 동일하게 한다: `Merchant.kt`, `PaymentCreator.kt`, `JpaPaymentEntity.kt`.
- 특정 타입에만 종속되는 작은 enum, sealed 결과의 하위 타입, private helper는 같은 파일에 둘 수 있다. 줄 수만으로 분리하지 않는다.
- 독립적으로 참조되는 주요 요청·결과 DTO는 이름에 맞는 파일로 분리한다.
- 서로 다른 도메인 식별자를 `MasterIdentifiers.kt` 같은 전역 묶음에 계속 추가하지 않는다.
- Domain 명사는 용어사전과 대조한다. 예를 들어 `MerchantAccount`를 편의상 `Account`로 축약하지 않는다. `SiteAssignment`와 용어사전의 긴 명칭처럼 차이가 있으면 대응을 기록하고 사용 맥락에 맞는 표준명을 정한다.

### 역할별 명명

| 역할 | 기본형·예시 | 주의 |
|---|---|---|
| Domain | `Payment`, `MerchantAccount`, `Contract` | 업무 용어를 그대로 사용 |
| 제공 포트: 생성·등록 | `PaymentCreator`, `MerchantRegister` | Splearn의 역할 명사 방식 |
| 제공 포트: 조회 | `PaymentFinder`, `MerchantFinder` | DTO 조회 Port와 책임 구별 |
| 제공 포트: 인증 | `MerchantAuthenticator` | API 키 인증과 고객 카드 인증 구별 |
| 서비스 구현 | `PaymentCreateService`, `MerchantQueryService` | 실제 책임이 드러나는 이름 |
| Aggregate 저장 포트 | `PaymentRepository`, `MerchantRepository` | Spring Data 상속 없음 |
| 조회 전용 required 포트 | `MerchantStructureReader` | CQRS 조회라는 의미 유지 |
| 외부 전달 포트 | `EntitlementDispatcher`, `EmailSender` | `Port` 접미사에만 역할을 의존하지 않음 |
| 프로토콜·작업 기록 저장 포트 | `IdempotencyRecordStore`, `OutboxStore` | Aggregate Repository로 강제 개명하지 않음 |
| JPA 저장 구현 | `JpaPaymentRepository` | required 포트를 구현 |
| Spring Data 인터페이스 | `PaymentJpaRepository` | adapter.persistence 내부 기술 타입 |
| 영속 Entity | `JpaPaymentEntity` | Domain과 명확히 구별 |
| Mapper | `PaymentPersistenceMapper` | 대상 Domain과 용도 표시 |
| HTTP 진입점 | `PaymentController` 또는 `CreatePaymentController` | 책임 범위에 맞게 선택, 이름 때문에 기능 합치지 않음 |

`UseCase`, `Port`, `Store`를 일괄 문자열 치환하지 않는다. 제공 기능·저장 대상·호출 관계를 조사한 뒤 위 규칙으로 결정하고 이유를 남긴다.

현재 `SubmitCreatePaymentService`는 인증 주체·시계·ID를 붙이는 진입 책임을, `CreatePaymentService`는 생성 트랜잭션을 소유한다. 이름을 정비해도 두 역할을 합치거나 트랜잭션을 이동하지 않는다. 두 포트를 함께 정리할 때 구별되는 이름과 역할 설명을 매핑표에 먼저 적는다.

## 5. 우선 정리 대상

아래는 탐색 출발점이다. 실제 파일과 타입을 다시 확인한다.

| 현재 파일 | 정리 방향 |
|---|---|
| MASTER `domain/Party.kt` | `Business.kt`, `Merchant.kt`, `MerchantSite.kt` 등 주요 타입 분리 |
| MASTER `domain/AuthorizationTerms.kt` | `TransactionLimitPolicy`, `PolicyVersion`, `PolicyAssignment`, `CardRouteActivation`별 파일 분리 |
| MASTER `application/required/MasterStores.kt` | 저장 포트별 파일 분리 후 Repository/Store 역할 구분 |
| MASTER `adapter/persistence/MasterJpaRepositories.kt` | Spring Data 인터페이스별 파일 분리 |
| MASTER `application/EntitlementDeliveryServices.kt` | Relay·Lease·PropagationQuery 서비스별 파일 분리 |
| PAYMENT `payment/adapter/persistence/JpaStores.kt` | Payment·주문 통제·멱등 기록·Outbox 구현별 파일 분리 |
| PAYMENT `entitlement/application/EntitlementServices.kt` | Inbox·Evaluation·Freshness·Clock 구현별 파일 분리 |
| `Jpa*Entities.kt`, `*Ports.kt`, `*UseCases.kt` | 독립 타입 분리, 종속된 작은 타입은 필요한 경우 함께 유지 |

## 6. 실행 절차

### 단계 A: 현황과 변경 계획

1. `git status`와 현재 작업자를 확인한다. 사용자 변경과 다른 진행 작업을 덮어쓰지 않는다.
2. 등록부에서 작업 ID 충돌을 확인하고 구조 정비 작업을 등록한다. 예: `ARCH-STRUCTURE-001`.
3. 소스 기준 revision/fingerprint와 기존 검증 증거를 확인한다. 이전 검증은 해당 코드 상태의 증거로만 사용한다.
4. `docs/repository/ARCH-STRUCTURE-001-MAPPING.md`에 매핑표를 만든다.
5. 매핑표 열: 현재 파일·타입·패키지 / 도메인 의미 / 목표 파일·타입·패키지 / 변경 이유 / 호출자 / 영향받는 검사·설정.
6. Domain 용어와 모델 경계를 바꾸지 않는 파일·명명 정비는 계획을 설명하고 계속 실행한다. 금융 의미나 모듈 경계를 바꿔야 하는 항목은 별도 작업으로 분리하고 독립적인 정비를 계속한다.

### 단계 B: 파일 분리

1. 한 업무 영역씩 현재 타입명·패키지를 유지하며 파일을 나눈다.
2. 각 파일의 import, 파일 annotation, 가시성을 확인한다. Kotlin의 top-level private 선언은 파일 이동으로 접근 범위가 달라진다.
3. `@file:JvmName`, 생성되는 `*Kt` facade와 top-level 함수의 호출자를 확인한다.
4. 관련 컴파일·테스트를 실행한다. 파일 수만 검사하는 테스트는 만들지 않는다.

### 단계 C: 역할별 이름 통일

1. 매핑표를 기준으로 타입명·파일명·import·테스트를 함께 변경한다.
2. Spring bean 이름·`@Qualifier`·문자열 클래스 참조·리플렉션·패키지 기반 스캔·ArchUnit 패턴을 점검한다.
3. Entity 타입명을 바꾸면 명시적 JPA entity name, JPQL, named query를 확인한다. 테이블명·컬럼명과 직렬화 계약은 보존한다.
4. 예전 이름이 남은 곳을 검색하되 과거 검증·리뷰 기록을 현재 이름으로 덮어쓰지 않는다.

### 단계 D: 문서·검증·완료

1. `ARCHITECTURE-CONVENTIONS.md`와 용어사전의 현행 설명을 맞추고 Splearn 대비 의도적 차이를 기록한다.
2. 루트 `CLAUDE.md`에는 확정된 명명·구조 규칙과 해당 문서 링크를 짧게 연결한다. 기존 지침 전체를 교체하지 않는다.
3. 향후 하위 패키지·애그리거트 경계 정비는 근거와 범위를 별도 후속 작업으로 남긴다. 첫 정비를 끝없이 확대하지 않는다.
4. 최종 소스·규칙·문서 변경이 끝난 뒤 등록된 전체 검증을 실행한다.
5. 하네스의 검증·리뷰 역할과 완료 계약을 따른다. 독립 검증·리뷰를 수행하지 못했다면 그 사실을 명시하고 DONE을 주장하지 않는다.

## 7. 검증 요구사항

- `harness/commands.json`의 현재 명령과 검사 하한을 확인하고 그대로 사용한다.
- 단계 중에는 영향받는 컴파일·도메인·서비스·어댑터·아키텍처 검사를 실행한다. 최종에는 새 fingerprint로 `VERIFY-GATE`를 실행한다.
- 명령 형태: `python3 harness/scripts/verify-gate.py ARCH-STRUCTURE-001 --expect-fingerprint <실제 fingerprint>`.
- 테스트 삭제·skip·하한 감소·검사 대상 제외로 통과시키지 않는다. 패키지 변경에 따른 검사 패턴 수정은 동일한 금지 동작을 검출하는 위반 fixture로 확인한다.
- 주요 동작 보존: 멱등 재요청, 주문 활성 슬롯, 트랜잭션 rollback, Outbox/Inbox 원자성, 스냅샷 전달, HTTP 인증·포트 격리.
- 모듈 공개 범위와 다른 모듈 Domain 직접 접근 금지를 유지한다. 모듈을 OPEN으로 바꿔 검사를 통과시키지 않는다.
- 직전 검토 당시 애플리케이션 테스트 1,220건·하네스 72건이 통과했다. 이는 과거 관측값이며, 실행 시점의 등록 기준과 실제 보고서가 우선이다.
- 구조만 정비하고 의존성이 바뀌지 않았다면 불필요한 버전 갱신을 하지 않는다. 의존성 변경 또는 릴리스 검증은 기존 CVE 검사 규칙을 따른다.

## 8. 완료 보고 형식

1. 변경 전·후 실제 패키지 트리
2. 주요 파일·타입명 매핑과 선택 이유
3. Splearn에서 따른 규칙과 PayCore에서 유지한 차이
4. 동작 보존 검증 결과, 명령·테스트 수·실패/skip·fingerprint·증거 위치
5. 남은 구조 항목과 별도 기능 버그
6. 다음 기능 개발에 적용할 규칙

파일 분리와 명명 정비만 끝났다면 그 범위의 완료로 보고한다. 애그리거트 경계까지 모두 검증됐다고 표현하지 않는다.

## 9. 적용 시점과 Claude Code에 보낼 문구

### 권장 시점

- 현재 기능 작업이 검증·리뷰를 마친 직후, 다음 기능의 코드 작성을 시작하기 전에 실행한다.
- 작성 시점 등록부는 `MASTER-SNAPSHOT-002` 완료, 활성 작업 없음이며 다음 후보는 `AUTHN-001` 등이다. 이 상태라면 **지금, AUTHN-001 또는 AUTH-001 착수 전**이 적절하다. 실행할 때 등록부와 실제 변경 상태를 다시 확인한다.
- 이미 다음 기능을 개발 중이면 해당 기능을 검증 가능한 단위로 마무리하고 구조 정비를 별도 작업으로 실행한다. 같은 파일을 동시에 이동·수정하지 않는다.
- 기존 기능 버그를 먼저 고칠 경우 버그 수정 검증을 마친 뒤 별도 변경 단위로 구조 정비를 진행한다.
- 이후 신규 기능은 확정된 구조·명명 규칙을 처음부터 적용한다. 전체 기능 완성 시점까지 정리를 미루지 않는다.

### 바로 보낼 실행 지시

```text
docs/CLAUDE-ARCHITECTURE-REFACTORING-GUIDE.md를 읽고,
현재 작업·변경 상태와 검증 증거를 먼저 확인해줘.

진행 중인 기능 변경이 없다면 ARCH-STRUCTURE-001을 등록하고 실행해줘.
진행 중인 기능 변경이 있다면 그것을 덮어쓰지 말고 검증 가능한 단위로
마무리한 뒤 별도 작업으로 진행해줘.

splearn-1-part2를 기본 참고로 삼고, 기존 Modulith 모듈과 순수 Domain/JPA
분리는 유지해줘. 먼저 변경 매핑표를 작성하고 파일 분리 → 역할별 이름 통일
→ 문서·아키텍처 검사 정비 → 전체 검증·리뷰까지 진행해줘.

첫 작업 범위는 기존 패키지 안에서의 파일·명명 정비야.
애그리거트·모듈 경계 변경은 근거가 있는 후속안으로 남겨줘.
금융 동작·DB·API·이벤트 계약과 트랜잭션·잠금 규칙은 유지하고,
단순 파일명 변경마다 확인을 요청하지 말고 진행해줘.
완료 후 변경 전후 구조, 검증 증거와 남은 항목을 알려줘.
```
