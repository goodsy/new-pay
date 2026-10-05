# 08. Testing / Verification Architecture

## 목적

아키텍처 규칙을 문서 선언에서 실행 가능한 검증 증거로 전환한다.

## 범위

도메인부터 Repository·Module·Batch·기관·E2E·장애·성능·복원까지 검증 계층을 정의한다.

## 핵심 규칙

- 기능 개발은 가능한 한 TDD로 진행한다. Kotest BehaviorSpec의 Given/When/Then으로 업무 규칙을 검증한다.
- Domain 테스트는 수수료·반올림·정산일·부분취소 한도·상태 전이를 검증하며 Spring·DB·기관 DTO를 요구하지 않는다.
- Application 테스트는 Port를 통해 정책 선택·멱등 요청·실패 처리를 검사한다. MockK는 테스트 경계에서 사용하며 실제 DB 원자성 증거를 대체하지 않는다.
- Repository 통합 테스트는 Testcontainers와 실제 MySQL 계열 제약을 사용해 Unique Key, lock, migration, 금액·시간 변환을 검증한다.
- Spring Modulith/ArchUnit은 모듈 공개 API·의존 방향·Domain의 기술 의존 금지·Batch 도메인 로직 배제를 검사한다.
- DB 격리는 세 개의 독립 인스턴스·제한 계정·네트워크 접근 실패로 검증한다. 서로 다른 schema 이름만으로 통과시키지 않는다.
- Batch 테스트는 chunk rollback·restart·중복 실행·partition 충돌·동일 업무 키를 검사한다. 정책 계산은 Domain 테스트에 둔다.
- 기관 simulator는 응답 유실·성공 후 timeout·중복 통보·역순·파일 재수신을 재현한다. 실제 기관 인증 시험은 별도 증거다.

### Test Fixture Policy

2026-10-06 사용자 지정 정책. 기존 TDD·Domain First·Hexagonal 원칙과 위 테스트 계층을 유지하면서 테스트 데이터 생성에 다음 규칙을 적용한다. Fixture는 테스트의 업무 조건을 준비하는 객체·데이터와 생성 도우미를 뜻한다. 아래 코드는 설명용 예시이며 실제 Fixture 구현이나 운영 통화 정책의 확정을 뜻하지 않는다.

1. **핵심 Domain Rule 테스트는 명시적인 Fixture / Factory를 우선한다.** 테스트 본문에서 중요한 업무 조건과 금액을 바로 읽을 수 있어야 한다.

   ```kotlin
   val payment = PaymentFixture.authorized(
       amount = 100_000.won()
   )
   ```

2. **자동 생성하는 정상 Fixture는 반드시 Domain Invariant를 만족한다.** 예를 들어 Payment의 다음 불변조건을 깨는 랜덤 객체를 정상 Fixture로 생성하지 않는다.

   ```text
   cancelledAmount
   + refundedAmount
   + reservedReturnAmount
   <= authorizedAmount
   ```

   `authorizedAmount = 10,000`, `cancelledAmount = 50,000`, `refundedAmount = 20,000`과 같은 조합은 정상 Fixture가 아니다.

3. **관계가 있는 금액·상태 필드를 독립적으로 무작위 생성하지 않는다.** `authorizedAmount`, `cancelledAmount`, `refundedAmount`, `reservedReturnAmount`, `PaymentLifecycleStatus`는 업무적으로 연결되어 있다. 선택한 업무 시나리오와 허용된 상태 전이에 맞추어 함께 구성한다.

4. **Aggregate는 가능한 한 Domain의 공식 생성 경로로 만든다.** 생성 방식의 우선순위는 `Domain Factory → Named Test Fixture → Fixture Monkey`다. Named Test Fixture와 자동 생성 도구도 공식 생성·상태 전이 경로를 우선 사용한다. private field 강제 주입이나 reflection으로 도메인에서 존재할 수 없는 상태를 만드는 방식을 기본 Fixture 생성 방식으로 사용하지 않는다.

5. **기본 자동 Fixture 도구는 Fixture Monkey다.** Application Test, Repository Integration Test, Batch Test, 대량 테스트 데이터 및 복합 데이터셋 생성에 우선 사용한다. 핵심 Domain Behavior Test에서는 Fixture Monkey API를 테스트 본문에 직접 노출하기보다 `PaymentFixture`, `AuthorizationFixture`, `CancellationFixture`, `RefundFixture`, `MerchantAccountFixture`처럼 의미 있는 프로젝트 Fixture 뒤에 숨긴다.

6. **Fixture Monkey API를 테스트 코드에 직접 남발하지 않는다.** 위의 `PaymentFixture.authorized(amount = 100_000.won())`처럼 업무 조건을 표현하는 호출을 권장한다. 다음과 같은 생성 로직이 여러 테스트에서 반복되면 Fixture 클래스로 추출한다.

   ```text
   비권장 반복 패턴:
   fixtureMonkey
       .giveMeBuilder<Payment>()
       .set(...)
       .set(...)
       .set(...)
       .sample()
   ```

7. **Invalid Fixture는 실패 검증 의도를 명확히 한다.** 잘못된 금액이나 허용되지 않은 상태 전이를 검증할 때는 `InvalidPaymentFixture` 같은 이름 또는 명시적인 생성 코드로 목적을 드러낸다. 정상 Fixture 생성기에서 우연히 잘못된 상태가 나오게 하지 않는다. 공식 생성 경로가 잘못된 입력을 거부하는지 검증할 때는 그 입력과 거부 결과를 명시하며, Invalid Fixture라는 이름을 Domain Invariant 우회의 근거로 삼지 않는다.

8. **랜덤 데이터 생성과 Business Rule 검증을 구분한다.** Domain Business Rule Test는 명시적 값 중심으로 작성하고, Repository / Batch / 대량 데이터 Test는 자동 생성 Fixture를 적극 활용한다. Application Test에서도 검증 대상인 업무 조건은 명시한다. 자동 생성하더라도 중요한 경계값·기대 결과는 명세에서 정하며, 기존의 시드·시계·업무일 통제와 재현성 요구를 유지한다.

9. **Fixture 이름은 구현 세부사항보다 Business Meaning을 표현한다.** `authorizedPayment()`, `paymentWithUnknownAuthorization()`, `paymentWithReservedReturn()`, `completedCancellation()`, `unknownRefund()`처럼 업무 상황을 드러내는 이름을 우선한다.

10. **Fixture 변경으로 Domain Invariant를 우회하지 않는다.** Domain Rule이 변경되면 관련 Fixture도 함께 변경한다. 테스트를 통과시키기 위해 Fixture에서 Domain 검증을 건너뛰거나, 불가능한 상태를 정상 상태로 취급해서는 안 된다.

**테스트 도구와 코드 배치**

- 테스트 스택은 Kotest BehaviorSpec, MockK, Testcontainers, ArchUnit을 유지하고 자동 Fixture 도구로 Fixture Monkey를 사용한다. 기존 Spring Modulith 경계 검증도 유지한다.
- Instancio는 별도로 추가하지 않는다. 자동 Fixture 도구를 불필요하게 이중화하지 않는다.
- Fixture 클래스·Factory 도우미·Fixture Monkey 설정 등 Fixture 관련 코드는 `src/test` 영역에만 둔다. 공통 테스트 지원 라이브러리라도 `src/main`에 Fixture 코드를 두지 않는다. 기존 production Domain Factory는 그대로 사용하며, 테스트 편의를 위한 생성 API나 의존성을 production 코드에 추가하지 않는다.
- Fixture Monkey는 테스트 전용 의존성으로 제한한다. Domain/Application을 포함한 모든 production 코드에 Fixture Monkey 타입·API·annotation이 유입되지 않게 한다. Domain 테스트의 순수성과 Application의 Port 경계를 유지한다.

## 예시 흐름 및 필수 시나리오

| 시나리오 | 기대 결과 |
|---|---|
| 동일 키로 승인 동시 요청 | 한 업무 예약·정해진 결과 재사용, 다른 payload 거절 |
| 기관 승인 성공 후 DB 커밋 실패 | 불명확 Attempt로 추적, 조회 후 한 승인·한 업무 효과 복구 |
| Payment/Authorization/Outbox 중간 예외 | 세 기록이 로컬 원자성에 맞게 함께 rollback |
| Financial 커밋 후 ACK 유실 | 이벤트 재전달에도 중복 정산 없음 |
| 승인 전 취소 이벤트 수신 | 보류 후 선행 거래 복구, 한 번 반영 |
| 계약·정책 변경 후 과거 정산 재현 | 보존한 버전으로 기존 금액 재현 |
| Financial DB/Worker 중단·Batch 최대 부하 | 승인 SLO 유지, 용량 한계 경보 작동 |
| 같은 지급의 작업 중복·lease 만료 | 외부 지급 중복 방지, 불명확 결과 조회 |
| FINANCIAL 과거 지점 복원 | 은행 기지급 거래 재송신 없이 재동기화 |
| 다른 MID의 조회·취소·정산 접근 | 권한 거절, 민감정보 미노출 |

```text
Given: 기관이 승인했지만 응답이 유실됨
When: 클라이언트가 동일 멱등성 키로 재요청
Then: 새 승인을 보내지 않고 기존 Attempt 상태를 반환·조회로 확정
And: Financial 반영과 기관 대사에서 중복 효과가 없음
```

성능 시험은 목표 TPS·burst·기관 지연·데이터 크기·Batch 동시성·측정 기간을 함께 기록한다. PASS 기준은 합의된 SLO, 오류율, backlog 복구 시간, 금융 차이 0 등으로 사전에 정의한다. 테스트 결과에는 환경·버전·시드·장애 주입 지점·근거를 남긴다.

## 금지사항

- Mock 테스트만으로 트랜잭션·외부 멱등성을 보장한다고 주장하는 것.
- 정상 흐름 E2E만으로 실서비스 진입을 승인하는 것.
- 테스트를 위해 운영 개인정보·비밀을 복제하는 것.
- 테스트가 구현 계산식을 그대로 복사해 같은 오류를 재현하는 것.

## 구현 체크리스트

- [ ] [10](10-architecture-invariants.md)의 각 규칙에 검증 증거를 연결했다.
- [ ] 필수 시나리오에 거래·금액·상태의 기대 결과를 정의했다.
- [ ] 성능·격리·복원·보안 시험과 기관 인증 증거가 있다.
- [ ] 테스트 데이터·시계·업무일을 통제해 재현 가능하다.
- [ ] 실패한 검증을 배포 관문에서 차단한다.
