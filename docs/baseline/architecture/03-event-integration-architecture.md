# 03. Event Integration Architecture

## 목적

분리된 DB 사이에서 금융 사건을 유실 없이 전달하고 중복 전달에도 업무 효과가 한 번만 반영되게 한다.

## 범위

Outbox/Inbox, 이벤트 계약, 순서, 재전달, 초기 적재, Projection 및 대사 연계를 다룬다.

## 핵심 규칙

- 전체 보장은 Local Transaction + Outbox/Inbox + Idempotency + Reconciliation으로 구성한다. 전송은 at-least-once를 전제로 한다.
- Outbox는 해당 업무 DB에 두고 업무 변경과 함께 커밋한다. Relay는 제한된 크기의 묶음을 lease로 점유하여 전달한다.
- 전송 성공은 수신 측의 내구성 있는 저장 확인을 뜻한다. Kafka를 쓰면 내구성 ACK 이후 전송 완료를 표시한다. 응답 유실 시 같은 eventId로 다시 보낸다.
- Kafka 없이 시작할 때도 Relay → 수신 API의 내구성 Inbox 저장을 제공한다. 메모리 큐·단순 HTTP 성공 응답은 대체가 아니다.
- Inbox 원본을 먼저 저장하는 방식에서는 업무 반영과 완료 상태를 한 로컬 트랜잭션으로 묶는다. 저장 즉시 ACK하더라도 미처리 항목을 재개할 내구성 Worker가 필요하다.
- 이벤트는 Domain Entity 직렬화가 아니라 명시적 Integration Contract다. 외부기관 DTO·비밀정보·불필요한 개인정보를 포함하지 않는다.

### 이벤트 예시

```json
{
  "eventId": "evt-001",
  "eventType": "PaymentAuthorized",
  "schemaVersion": 1,
  "producer": "payment",
  "aggregateId": "pay-001",
  "aggregateVersion": 3,
  "occurredAt": "2026-10-03T01:00:00Z",
  "correlationId": "corr-001",
  "causationId": "cmd-001",
  "payload": {
    "mid": "mid-001",
    "authorizationId": "auth-001",
    "amountMinor": 10000,
    "currency": "KRW",
    "policyVersion": "fee-v7"
  }
}
```

필드는 계약 예시이며 금액 의미, 버전 스트림 범위, 거래일·기관일·취소 관계는 계약에서 추가로 확정한다. 이벤트에 없는 필드를 얻기 위해 Financial이 PAYMENT DB에 접근하지 않는다.

### 순서·버전·오류

- Payment 단위 전달 순서를 유지한다. 소비자가 모든 버전을 받을 때만 연속 aggregateVersion 검사를 사용할 수 있다. 필터된 스트림은 별도 연속 sequence 또는 명시적 선행 eventId를 정의한다.
- 선행 승인보다 취소가 먼저 도착하면 보류하고 누락을 복구한다. 시간순 정렬만으로 상태 순서를 확정하지 않는다.
- 이벤트 ID 중복과 새로운 ID로 온 동일 업무 중복을 각각 Inbox 및 업무 키로 막는다.
- 일시 오류는 backoff·jitter·예산 있는 재시도를 적용한다. 계약 오류는 격리 큐로 보내고 소유자에게 알린다. DLQ는 완료 상태가 아니다.
- 스키마는 추가적 변경을 우선하고 과도기 동안 구·신 소비자와 호환한다. 의미 변경은 새 버전/이벤트를 사용한다.
- 정산에 필요한 설정 버전이 없으면 Financial에서 대기한다. MASTER의 최신 정책으로 대체하지 않는다.

### 초기 적재와 재생

소유 플랫폼이 일관된 스냅샷과 watermark를 내보낸다. 소비자는 staging에 적재·검증하고 watermark 이후 변경을 이어받는다. 겹치는 이벤트는 멱등하게 처리한다. Outbox 자동증가 ID의 최댓값만으로 커밋 순서가 보장된다고 가정하지 않는다. 이행 구간의 쓰기 제어 또는 검증된 변경 로그 offset을 사용한다.

## 예시 흐름

```text
Payment 커밋 → Relay 발행 → Relay 종료(전송 완료 기록 전)
 → 같은 eventId 재발행
 → Financial Inbox 중복 확인 → 기존 결과 유지
 → 기간별 건수·금액·누락 sequence 대사
```

## 금지사항

- 업무 커밋 후 메모리 이벤트만 발행하는 이중 쓰기.
- 브로커의 exactly-once 설정으로 은행 지급까지 한 번 실행됨을 주장하는 것.
- DLQ 삭제·이벤트 재생으로 승인·지급을 새로 실행하는 것.
- 소비 완료·보존기간 확인 없이 Outbox/Inbox 중복 제거 기록을 삭제하는 것.

## 구현 체크리스트

- [ ] 이벤트 필드 의미·소유자·호환 정책·업무 키를 정의했다.
- [ ] 발행 전후·커밋 전후 종료를 주입해 유실·중복 효과를 검증했다.
- [ ] 순서 역전·누락·미지원 버전·정책 미수신을 처리한다.
- [ ] 격리 큐 조회·수정 후 재처리·감사를 제공한다.
- [ ] 보존기간과 최대 재생 기간, 초기 적재 watermark를 검증했다.
