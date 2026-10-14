# ADR-001 Kafka·Redis 초기 미도입과 도입 기준

- 상태: **미도입은 채택**(사용자 결정 D-ENV-001, 2026-10-03: "Kafka/Redis는 초기 단계에 도입하지 않는다" — 변경 없음). **§1 relay 방식은 MASTER-SNAPSHOT-001에서 push relay → 수신 플랫폼 내부 Inbox API로 결정**(Coordinator 관례 결정, 가역적, 2026-10-13). §3 trigger는 여전히 Coordinator 제안이다.
- 범위: TECH-001. 이 ADR은 미도입 결정을 기록하고, 언제 무엇을 근거로 다시 판단할지 제안한다. 업무 정책을 새로 정하지 않는다.
- 근거 문서: docs/TECHNOLOGY-AND-PRINCIPLES.md §5, docs/baseline/architecture/00, 01, 03

## 1. 결정

초기 PayCore는 Kafka와 Redis 없이 구현한다. 플랫폼 간 전달과 정합성은 다음으로 보장한다.

| 필요 | 초기 수단 | 금융 안전 근거 |
|---|---|---|
| 주문 활성 하나·멱등 요청 | 소유 DB의 UNIQUE·조건부 갱신·Version·필요 시 행 잠금 | 권위 상태가 DB 한 곳에 있고 로컬 TX로 원자적 |
| PAYMENT → FINANCIAL 전달 | 소유 DB Outbox(업무 효과와 같은 로컬 TX) → Dispatcher Port → 수신측 Inbox(업무 효과와 같은 로컬 TX) | 유실 없는 재전송 + 수신측 업무 키 멱등 |
| MASTER 설정 투영 | 버전 있는 설정 스냅샷 이벤트를 같은 Outbox/Inbox 계약으로 전달 | 거래 시점 정책 버전 보존 |
| 조회 성능 | 소유 DB의 jOOQ 조회 adapter | 권위 상태와 같은 DB |

결정(MASTER-SNAPSHOT-001, 2026-10-13, 관례·가역적): Dispatcher Port의 첫 adapter는 소유 DB의 Outbox를 폴링하는 **push relay**이며, 수신측 Inbox로 **수신 플랫폼의 내부 HTTP API**(MASTER→PAYMENT: `POST /internal/v1/entitlement-snapshots`)를 호출해 전달한다. 행별 상태(PENDING/PUBLISHED/QUARANTINED)·`FOR UPDATE SKIP LOCKED` claim·재시도 상한 없음, 수신측은 Inbox를 업무 반영과 같은 로컬 TX로 내구 커밋한 뒤에만 2xx(ACK)를 준다. 커서 기반 pull은 Outbox id가 커밋 순서가 아니라서 기각했다(A03). 채널 인증은 봉투 HMAC-SHA256 + 운영망 mTLS(D-MS-004 — 이번 구현은 시험 키 HMAC만, 운영 키·mTLS는 LIVE 허용 전 별도). 어떤 방식이든 같은 프로세스여도 다른 소유 DB를 직접 SQL/Entity로 읽거나 쓰지 않는다. Kafka를 나중에 도입해도 Outbox·Inbox·업무 키·로컬 TX는 그대로 필요하고 broker가 권리 정합성을 대체하지 않는다.

## 2. 이유

- Kafka의 producer/transaction 기능은 DB·외부 VAN·은행을 아우르는 exactly-once를 만들지 않는다. 어차피 Outbox(보내는 쪽)와 Inbox+업무 키(받는 쪽)가 필요하다. 초기에는 브로커 없이 이 계약만으로 정합성을 증명할 수 있다.
- Redis 분산 lock·캐시는 결제 중복, 주문 활성 하나, 선지급 한도를 보장하지 못한다. 이 불변식은 소유 DB 제약과 명시적 안전 규칙에 둔다.
- 운영 구성요소가 늘면 장애 지점·보안 경계·운영 역량 요구가 함께 늘어난다. 실제 필요가 증명되기 전에는 승인 경로의 가용성에 기여하지 않는다.

## 3. 다시 판단하는 조건 (trigger, 제안)

어느 하나라도 측정·요구로 확인되면 해당 작업에서 ADR을 갱신하고 도입을 검토한다. 측정값의 구체 임계치는 운영 목표가 정해질 때 사용자와 정한다.

| 기술 | trigger |
|---|---|
| Kafka | DB 폴링 relay의 Outbox 적체·지연이 목표를 넘음 / 같은 이벤트를 여러 독립 consumer가 각자 속도로 소비해야 함 / 장기 보존 이벤트 replay로 read model 재구성이 필요함 / 외부 시스템과 이벤트 스트림 연동 요구 |
| Redis | 권위 상태가 아닌 조회의 DB 부하·지연이 목표를 넘음 / 여러 인스턴스가 공유하는 rate limit이 필요함 / 세션 공유가 필요한 화면 구성 |

## 4. 도입 시 반드시 지킬 것

- Kafka: Outbox → broker → Inbox + 업무 효과 같은 로컬 TX 유지. Aggregate key 기반 partition 순서, schema evolution 호환, DLQ/replay 절차, 보존 기간, consumer 재시작·중복 시험. broker 장애가 승인 응답을 막지 않음(Outbox에 내구 보존).
- Redis: 비권위 데이터만. TTL/eviction/장애 시 동작을 작업마다 정의. 보안·접근 제한·권리 판단은 Redis 장애 시 fail-open 금지. 결제 중복·한도·주문 활성 판단에 쓰지 않는다.
- 공통: Testcontainers 기반 장애 주입 시험(브로커/캐시 중단·지연·재시작)과 운영 경보를 같은 작업의 완료 증거로 요구한다.

## 5. 미도입이 막지 않는 것

CreatePayment, Outbox/Inbox 계약, 가상 VAN/매입/은행 연동, 정산·대사는 모두 Kafka/Redis 없이 구현·시험한다. 미도입을 이유로 전달/재처리/장애 시험을 생략하지 않는다.
