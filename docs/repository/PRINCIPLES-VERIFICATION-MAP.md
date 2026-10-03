# 8대 원칙 검증 매핑 (TECH-001)

작성 2026-10-03 KST. 기준: docs/TECHNOLOGY-AND-PRINCIPLES.md §1. 이 문서는 원칙별로 **어떤 기술로, 어떤 시험으로, 어느 작업에서** 증거를 만들지 정한다. 업무 정책을 새로 정하지 않는다. 각 작업 카드의 `principleEvidence`는 이 표의 시험 유형을 따라 채운다.

## 1. 원칙별 검증 수단

| 원칙 | 구현 수단(확정 스택) | 시험 유형 | 실행 근거(Gate) | 첫 증거 작업 |
|---|---|---|---|---|
| Financial Consistency | 순수 Domain 불변식(Kotlin), 금융 사실 append-only 테이블, 원승인 정책 Snapshot 컬럼, 금액 Value Object | Domain 단위 시험(Kotest, 독립 기대값), DB 제약 시험(UPDATE/DELETE 거부 또는 이력 보존), 단계별 금액 보존 property 시험 | GRADLE-CHECK | CP-DOMAIN-001, CP-DB-001 |
| Transaction Safety | 소유 DB 로컬 TX(Spring `@Transactional`/TransactionTemplate), 업무 효과+요청 결과+Outbox 같은 TX, 외부 호출 TX 밖 | 중간 예외 rollback 시험(부분 성공 없음), commit 후 응답 유실 시험, 외부 호출 중 TX 미보유 검증 | GRADLE-CHECK(Testcontainers MySQL) | TECH-001(rollback smoke), CP-DB-001 |
| Idempotency | 요청키+내용 fingerprint UNIQUE, 업무 효과 key, Outbox event id, Inbox 처리 key, 기관 원거래 조회 | 동일 요청 재전송·상이 내용 충돌·중복 이벤트 수신·재시작 후 재처리 시험 | GRADLE-CHECK | CP-DB-001, CP-API-001 |
| Concurrency Control | UNIQUE, 조건부 UPDATE, JPA `@Version`, 필요 시 `SELECT … FOR UPDATE`(최소 행, lock timeout) | 실제 DB 다중 연결 경합 시험(동시 요청 N개 → 활성 하나), 낙관적 잠금 충돌 후 재확인 시험, deadlock/timeout 처리 시험 | GRADLE-CHECK(실제 MySQL, H2 금지) | TECH-001(@Version smoke), CP-DB-001 |
| Recoverability | UNKNOWN 상태, 원 Attempt ID 조회 Port, Batch JobRepository checkpoint, 제한된 재시도 | 장애 지점별 재시작 시험, 기관 응답 지연·유실 Simulator 시험, Batch 실패 후 재시작 중복 없음 시험 | GRADLE-CHECK | TECH-001(Batch restart smoke), AUTH-001 |
| Reconciliation | 기관/내부 사실/원장/입금/지급 비교 Job(Spring Batch), 차이 Case, Recovery/Adjustment 분리 | 차이 주입 → Case 생성·위험 Hold·보정 후 재대사 MATCH 시험 | GRADLE-CHECK | RECON-001 |
| Auditability | 행위자·권한·사유·원거래·정책 버전 기록(append-only), 감사 저장 실패 시 업무 중단 규칙 | 변경 이력 추적 시험, 감사 저장 실패 주입 시험 | GRADLE-CHECK | CP-API-001(요청 주체), 관리 기능 |
| Observability | Micrometer 지표, 구조화 로그, 상관 ID, Actuator | 장애 주입 시 UNKNOWN age/Outbox lag/대사 차이/Hold 지표 노출 시험, 로그에 카드·Secret 없음 시험 | GRADLE-CHECK | CP-API-001 이후 |

## 2. 공통 규칙

- **실제 DB**: 경합·잠금·collation·제약 시험은 Testcontainers MySQL 8.4.11에서만 한다. H2/mock 저장소 결과는 이 원칙들의 증거가 아니다.
- **독립 기대값**: 금액·상태 기대값은 구현 식을 복제하지 않고 명세/fixture에서 직접 적는다.
- **N/A 기록**: 해당 없는 원칙은 이유와 후속 Gate를 카드에 남긴다. 미평가는 통과가 아니다.
- **Audit ≠ Observability**: 샘플링·유실 가능한 trace/log로 필수 감사를 대체하지 않는다. paymentId 같은 고유 ID를 metric label로 쓰지 않는다.
- **Kafka/Redis 없음**: ADR-001. 전달·재처리·장애 시험은 Outbox/Inbox 기준으로 수행하며 생략하지 않는다.

## 3. TECH-001이 만드는 기반 증거 (기능 증거 아님)

| 원칙 | TECH-001 smoke | 의미와 한계 |
|---|---|---|
| Transaction Safety | TransactionTemplate 안 예외 → 행 없음 | Spring TX + JPA + MySQL rollback이 동작함. 업무 효과·Outbox 원자성은 CP-DB-001 |
| Concurrency Control | 거래 A가 먼저 읽고, 거래 B가 같은 행을 바꿔 먼저 commit → 오래된 version을 가진 A가 나중 commit에서 낙관적 잠금 실패, B의 값 보존 | `@Version` 충돌 감지가 실제 DB에서 동작함. 한 스레드에서 두 연결(REQUIRES_NEW)로 순서를 고정한 시험이며 동시 경합 시험이 아니다. 동시 요청 경합·UNIQUE 경쟁은 CP-DB-001 |
| Recoverability | Batch chunk 실패 → 실패 chunk rollback → 같은 파라미터 재시작 → checkpoint부터 중복 없이 완료 | JobRepository checkpoint가 실제 DB에서 동작함. 같은 JVM 안의 재시작이며 프로세스 강제 종료는 시험하지 않았다. 외부 지급 exactly-once가 아니며 원 ID 조회 규칙은 별도 |
| (CQRS 기반) | JPA로 쓴 행을 jOOQ 생성 클래스로 조회 | 같은 소유 DB에서 Command(JPA)/Query(jOOQ) 분리가 가능함. 권한 범위 강제는 기능 작업 |
| (보안 기반) | Thymeleaf + Security: 미인증 거부, CSRF 토큰 렌더, CSRF 없는 POST 거부 | 기본 거부·CSRF가 동작함. 인증 방식은 미결(사용자 결정 필요 시 질문) |
