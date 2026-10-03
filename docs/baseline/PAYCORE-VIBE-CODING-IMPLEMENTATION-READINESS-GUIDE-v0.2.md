# PayCore Vibe Coding Implementation Readiness Guide v0.2

2026-10-03 KST / 검토 수정안 / 전체 정책 새 승인 또는 구현 완료 아님

## 1. 목적과 적용 기준

Business Rule → Aggregate/Invariant → 상태 전이 → 로컬 TX → Application API → DB 제약/경합 → UNKNOWN/Recovery → TDD를 확정한 기능부터 구현한다. AI는 구현 세부를 선택할 수 있지만 금융 의미/보안/상태·키의 계약은 추측하지 않는다.

현재 기준은 초기 확정사항, 결제 시도 규칙, 실제 결제 허용/기관 계약, Settlement Control Policy, 도메인 구조/Seed/MID–Site 확정본이다. 과거 문서와 충돌하면 현재 적용 기준·결정 ID/근거를 확인한다. 본 수정안의 기술 후보는 검토 후 첫 기능 명세로 고정한다.

## 2. Feature Specification

기능마다 다음을 한 묶음으로 작성한다.

- 목표/지원·미지원 범위, 사용자·service actor, 현재 정책 근거.
- 소유 플랫폼/DB, Aggregate Root·별도 금융 record·제어 행, 불변조건.
- 상태별 허용/금지/동일 결과 전이와 실패·경합·늦은 결과 처리.
- Command/Query의 신뢰 범위·입력·결과·오류·멱등/현재 상태 조회 의미.
- 각 로컬 TX의 함께 저장할 항목과 외부 호출 전후 간극.
- 요청키/업무키/기관키의 namespace·payload 동일성·보존/재조회.
- Unique/Check/FK/Index·잠금 순서·경합 제어·migration 검증.
- UNKNOWN/commit 불명확·Recovery·Replay·수동 해소의 근거.
- 보안·감사·민감정보·TEST/LIVE/SIMULATOR 격리.
- 테스트 입력·독립 기대값·장애 주입·완료 증거.

## 3. 공통 경계

- MASTER/PAYMENT/FINANCIAL의 소유권 유지, cross-DB 직접 SQL/공용 Entity/복수 DB 원자성 금지.
- 다른 모듈은 공개 API/Port/Event로 접근한다. Application에 JpaRepository/DSLContext/기관 SDK 누수 금지, Domain에 Spring/JPA/jOOQ/Batch/기관 DTO 의존 금지.
- 외부 승인/송금은 DB TX 밖. 미전송 또는 확정 무효과 근거 없는 재전송/Failover 금지.
- 업무 변경+Outbox는 같은 소유 DB TX. 수신 Inbox 완료+업무 효과+후속 Outbox도 같은 대상 DB TX.
- 요청 중복과 금융 효과 중복은 다른 키로 방지한다. eventId만으로 금융 중복 방지를 증명하지 않는다.
- 확정 금융 사실은 append-only. 조정은 별도 기록, 재생은 기관 금융 요청을 무조건 재실행하지 않는다.
- Seed 숫자는 DEMO DATA. 운영값은 versioned policy·기간·승인·적용 근거로 관리한다.

## 4. Payment 업무 기준

OrderKey=(environment,MID,merchantOrderId), 주문 1:N Payment 이력, REQUESTED 포함 활성 Payment 하나. 같은 내용은 기존 시도를 반환하고 다른 내용은 충돌이다. AUTHORIZING/UNKNOWN/AUTHORIZED에서 새 시도 금지. FAILED/EXPIRED는 외부 무효과/안전한 만료 근거 확인 후 재시도 가능하다. 승인 성공 주문은 전액 반환 후에도 다시 승인하지 않고 재구매는 새 주문 ID다.

Payment.lifecycle와 ReturnStatus를 구분한다. PaymentTransaction은 append-only 별도 record이며 무제한 Aggregate 컬렉션으로 적재하지 않는다. 반환 완료액+예약액≤승인액을 Payment의 공통 경합 제어로 검사한다.

기관 UNKNOWN과 DB 생성 결과 확인 중은 구분한다. 통신 timeout만으로 기관 실패/만료를 확정하지 않는다. 기관 요청 ID는 확정된 namespace를 포함한다. ID 하나의 전역 UNIQUE를 기본값으로 삼지 않는다.

## 5. Definition of Ready

READY는 **이번 기능**에 필요한 조건이 모두 고정됐을 때다. 다른 독립 기능의 미결 정책 때문에 전체 개발을 중단하지 않는다.

- [ ] 정책 근거·명세 revision/hash 및 허용 변경 경로 확인.
- [ ] Aggregate/record/조정 행 소유와 정확한 불변조건 확정.
- [ ] 모든 상태별 Command 허용/거절/재사용·안전한 종료 근거 확정.
- [ ] API·신뢰된 인증 범위·Site/환경·금액/통화·오류 계약 확정.
- [ ] 멱등키/업무키·fingerprint 정규화·보존/조회 의미 확정.
- [ ] 실제 적용할 DB 제약/잠금/TX 경계와 경쟁 알고리즘 검토 완료. 후보 목록만 있으면 미완료.
- [ ] Outbox/Inbox/계약 버전·순서·복구가 해당 범위에서 정의됨.
- [ ] 장애·UNKNOWN·commit 응답 유실·재생·권한 위반의 기대 결과 작성.
- [ ] 테스트 fixture·시계·기관 Simulator/명시 stub 경계와 실행 명령 확인.
- [ ] 구현/검증/review 작업 카드와 정확한 코드 revision·보고서 저장 위치 지정.
- [ ] 금융·보안에 영향을 주는 해당 기능 미결 없음. 미적용 항목은 사유와 후속 Gate 명시.

실제 저장소/JDK/DB/Gradle/Runner가 없거나 명령이 비어 있으면 실행 READY가 아니다. 문서 채택은 테스트 통과 증거가 아니다.

## 6. Definition of Done

- [ ] 정확한 통합 revision의 관련 Domain/Application/DB/동시성/경계/권한/장애 테스트 통과.
- [ ] 테스트 개수·보고서·exit code·환경·seed·명세 hash 기록. 환경 오류/테스트 0건/skip로 통과 금지.
- [ ] Unique/잠금/마이그레이션·원자성·payload 충돌 검증.
- [ ] 해당 기능의 금융 record/Outbox/감사 및 중복 effect 제어 검증.
- [ ] Domain/Application 기술 의존과 소유 DB 밖 접근 금지 확인.
- [ ] Simulator 상태/재시작/응답 유실을 해당 범위에서 검증.
- [ ] 리뷰와 미결/후속 통합 Gate 기록. 코드 변경 후 이전 검증 증거는 무효.

CreatePayment 자체는 기관 호출이 없어 provider timeout Recovery/금융 원장 append가 미적용일 수 있다. 미적용을 PASS로 위장하지 않고 관련 AuthorizePayment Gate에 연결한다. 승인/정산/지급 실서비스 인증·부하·복원은 별도 Go-live Gate다.

## 7. AI Agent 체크리스트

1. 어떤 명세/hash와 승인 정책으로 어느 기능을 구현하는가?
2. 소유 DB·Aggregate·허용 파일·공개 계약은 무엇인가?
3. 상태별 허용/거절/재사용·동시성·원자성은 무엇인가?
4. 동일 키/다른 키/다른 payload·응답 유실을 어떻게 처리하는가?
5. 기관/DB 불명확 결과와 복구 근거는 무엇인가?
6. 금융 effect와 Outbox/Inbox/감사는 어떻게 함께 저장하는가?
7. 어떤 실제 DB 테스트·실패 주입으로 증명하는가?
8. 민감정보·MID/환경 격리를 어떻게 검사하는가?
9. 현재 코드 revision의 실행 보고서/review가 존재하는가?
10. 완료 범위와 남은 통합/운영 Gate를 구별했는가?

금지: 임의 상태/금융 키 추가, 기준정보 없는 운영 숫자 생성, 기존 테스트 기대값 완화, 멱등 기록 삭제, UNKNOWN 실패 처리, 금융 재시도 자동 추측, 직접 cross-DB 조회, 무조건 SQL 원장 UPDATE.

충돌은 DESIGN-CONFLICT로 정책/문서·영향·가능 대안·필요 결정·독립 진행 가능한 범위를 보고한다. 해당 작업만 중단하며 정책을 자체 승인하지 않는다.

## 8. 첫 기능

CreatePayment + OrderPaymentControl을 첫 PAYMENT 기능으로 구현할 수 있다. MASTER 유효 준비 Snapshot fixture가 선행한다. 최초 MID 발급과 MASTER→PAYMENT 설정 전달은 후속 통합에서 증명한다. 관련 계약: [CreatePayment v0.1](PAYCORE-CREATE-PAYMENT-IMPLEMENTATION-CONTRACT-v0.1.md).

Domain Red/Green → Application/Port → 실제 DB/migration·경합·원자성 → API/권한·Outbox → 경계·장애 → review/DONE을 작은 기능마다 수행한다. 이후 Authorization/Simulator/Recovery를 연결한다. Domain 전체 구현 후 마지막에 DB 검증을 몰아서 하지 않는다.
