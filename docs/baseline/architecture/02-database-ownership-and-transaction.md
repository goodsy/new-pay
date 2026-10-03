# 02. Database Ownership / Transaction

## 목적

기존의 “같은 DB 안의 트랜잭션” 원칙을 **단일 소유 DB의 로컬 트랜잭션**으로 한정하고, 경계 간 정합성 보장을 정의한다.

## 범위

MASTER/PAYMENT/FINANCIAL 소유권, 로컬 원자성, 스냅샷, 금융 반영의 멱등성을 다룬다.

## 핵심 규칙

| DB | 원본 | 로컬 복제·기술 데이터 |
|---|---|---|
| MASTER | Business, 역할, Site, Contract, MID, 개통, 정책·기관 매핑, Credential 메타데이터 | Outbox, 관리 요청 멱등성 |
| PAYMENT | Payment, Attempt, Authorization, Cancellation, Refund 거래 상태, 기관 거래 식별자 | 설정 Snapshot, Idempotency, Inbox, Outbox |
| FINANCIAL | Acquisition, Settlement, AgentSettlement, Payable, Payout, Adjustment, 대사 결과 | Payment Projection, 정책 Snapshot, Inbox, Outbox, Job 실행 정보 |

로컬 투영은 원본 Aggregate가 아니다. 수정하려면 원본 플랫폼에 명령을 보내고 변경 이벤트로 갱신한다. 개인·계좌 정보 복제는 목적에 필요한 최소 범위로 제한한다.

### 트랜잭션 경계

1. MASTER 변경 + 설정 이벤트 Outbox는 MASTER의 한 로컬 트랜잭션이다.
2. 승인 확정 시 Payment 변경 + Authorization + 요청 멱등성 결과 + Outbox는 **같은 PAYMENT DB 연결·트랜잭션**으로 커밋한다.
3. Financial 소비 시 Inbox 완료 기록 + Projection/업무 반영 + 후속 Outbox는 **별도 FINANCIAL 로컬 트랜잭션**으로 커밋한다.
4. FINANCIAL 내부의 정산·Payable 변경도 해당 Use Case의 일관성 범위만 로컬 트랜잭션으로 묶는다. 독립 모듈의 Repository를 임의로 호출하지 않는다.
5. Payment와 Financial 커밋 시점은 다르다. 사이의 지연은 이벤트 적체·대사로 관리하며 Cross-DB Transaction, XA/2PC, 복수 DataSource를 묶는 원자성 가정을 금지한다.

### 외부 호출과 로컬 원자성

외부 승인은 DB 트랜잭션에 포함할 수 없다. 짧은 로컬 트랜잭션으로 Attempt 및 안정적인 기관 요청 ID를 기록한 후 호출한다. 응답 또는 조회 결과를 받은 뒤 승인 확정 로컬 트랜잭션을 실행한다. 기관 성공 후 DB 저장 실패는 조회·대사로 복구해야 하며 DB Rollback이 기관 승인을 취소하지 않는다.

### 제약과 정책

- 요청 멱등성 키는 `(MID, operation, key)` 등 업무 범위를 포함하고 payload hash와 결과 참조를 저장한다. 같은 키·다른 내용은 거절한다.
- Inbox는 `(consumer, eventId)` Unique Constraint를 갖는다. 정산·지급 효과에는 별도의 업무 Unique Key를 적용한다.
- 부분취소 누적금액, 정산 반영, 지급 예약에는 낙관적 버전 또는 명시적 잠금을 사용한다. 조회 후 무보호 쓰기를 금지한다.
- 금액은 통화·소수 단위·반올림 규칙을 명시하고 Float/Double을 사용하지 않는다. 지급·조정은 추적 가능한 기록으로 남긴다.
- 정책에는 version, effectiveFrom, 발행 시각을 둔다. 거래가 참조한 버전과 판정 입력을 보존한다. 사후 정책 변경으로 과거 금액을 조용히 덮어쓰지 않는다.
- 통신 오류로 설정이 오래되면 정책 유형별 최대 허용 지연·차단 조건을 적용한다. 긴급 MID 중지·Credential 폐기는 별도 우선 전달과 로컬 만료·차단 규칙을 둔다.

## 예시 흐름

```text
PAYMENT TX1: Attempt + Idempotency 요청 예약 커밋
 → 기관 호출(트랜잭션 밖)
PAYMENT TX2: Payment Authorized + Authorization + Outbox 커밋
 → 이벤트 전달
FINANCIAL TX: Inbox 처리 + 거래 Projection + 정산 후보 생성 커밋
 → 소비 ACK
```

TX2 실패 시 Attempt를 근거로 기관 결과를 조회한다. Financial 커밋 후 ACK 실패 시 재전달을 Inbox로 중복 제거한다. 기관 조회를 수행하는 동안 DB 잠금을 잡지 않는다.

## 금지사항

- PAYMENT ↔ FINANCIAL 직접 SELECT/JOIN/UPDATE, DB Link, 다른 DB Replica를 우회 조회하는 것.
- 다른 플랫폼의 MASTER 테이블 직접 JOIN 또는 공용 JPA Entity 공유.
- DB와 이벤트 브로커 양쪽 커밋을 하나의 성공으로 가정하는 것.
- 외부 호출 전체 시간 동안 DB 트랜잭션을 유지하는 것.

## 구현 체크리스트

- [ ] 계정·네트워크·설정으로 소유 DB 밖 접근을 차단했다.
- [ ] Payment/Authorization/Outbox의 동일 트랜잭션을 장애 주입으로 증명했다.
- [ ] Inbox/업무 키 Unique Constraint와 동시성 제어가 있다.
- [ ] 정책 버전·금액·시간 기준과 과거 재현 가능성을 검증했다.
- [ ] 외부 성공 후 로컬 실패의 복구 절차가 있다.
