# 추가 결정 기록

현재 기준은 BASELINE-INDEX.md의 채택 문서다. 이 팩 작성은 새 금융 정책 채택이 아니다.

사용자 답변을 받으면 다음 형식으로 추가한다. 질문을 먼저 PROPOSED → ASKED, 답변 근거를 받은 뒤 ANSWERED, 명세 반영·검토 후 APPLIED로 관리한다.

| 결정 ID | 질문 ID | 사용자 답변/근거 | 적용 명세·버전 | 영향 작업 | 적용일 |
|---|---|---|---|---|---|
| D-ENV-001 | (사용자 지시) | 개발환경 확정: WSL2 Ubuntu, Java 25, Kotlin, Gradle Kotlin DSL+Wrapper, Spring Boot 3.x, DDD+헥사고날+Spring Modulith, MySQL 8.4 LTS, JPA, jOOQ, Flyway, Spring Batch, Kotest+MockK+Testcontainers+ArchUnit, Docker Desktop+Compose, Kafka/Redis 초기 미도입. 하네스 문서와 충돌 시 이 기준 우선 | docs/repository/BOOT-001-DISCOVERY.md §3, harness/technology-baseline.json | BOOT-001, TECH-001, 전체 | 2026-10-03 |
| D-TECH-001 | Q-TECH-BOOT | "Boot 4.1.1 (Recommended)" 선택. 근거: Boot 3.5.16이 3.x 마지막 OSS 릴리스(2026-06-30 OSS 종료), Boot 3.5는 JDK 25에서 실행 가능한 Gradle(9.x)을 공식 지원하지 않음. D-ENV-001의 "3.x"를 대체 | docs/repository/TECH-BASELINE.md | BOOT-001, TECH-001 | 2026-10-03 |
| D-CPD-001 | Q-CPD-ENV | "TEST / LIVE (Recommended)". OrderKey environment는 TEST/LIVE 두 값. Simulator/실기관 구분은 environment가 아니라 기관 Route 정보로 별도 관리하며 TEST는 실기관으로 전송하지 않는다 | harness/questions.json | CP-DOMAIN-001 이후 전체 | 2026-10-06 |
| D-CPD-002 | Q-CPD-EVIDENCE | "아래 목록 (Recommended)". 새 Payment 허용 안전 종료 근거 — FAILED: 기관 확정 거절, 전송 전 실패 확정(미전송), 기관 무효과 종결 확인. EXPIRED: (미전송 그리고 인증/세션 폐기) 또는 기관 무효과 종결 확인. timeout·조회 NOT_FOUND·lease 만료·근거 없음은 불충분(새 시도 차단) | docs/baseline/PayCore-결제시도-규칙-권장안.md §2–3 | CP-DOMAIN-001, AUTH-001 | 2026-10-06 |
| D-CPD-003 | Q-CPD-EVIDENCE-CROSS | "불인정 유지 (Recommended)". D-CPD-002 표의 상태–근거 짝이 맞을 때만 새 Payment 근거로 인정하고, 표 밖 교차 조합(예: FAILED+미전송·세션 폐기, EXPIRED+전송 전 실패 확정)은 불충분으로 차단한다 | harness/questions.json | CP-DOMAIN-001 | 2026-10-06 |
| D-CPD-004 | Q-CPD-HOLD-REUSE | "차단 응답 (Recommended)". incident hold가 걸린 주문에 같은 입력 생성 재요청이 오면 Blocked(INCIDENT_HOLD)로 응답하고 기존 paymentId를 참조로 담는다. 새 Payment·승인 시작 모두 없음 | harness/questions.json | CP-DOMAIN-001, CP-CONTRACT-001 | 2026-10-06 |
| D-CP-001 | Q-CP-KEY | "입력 그대로 비교 (Recommended)". merchantOrderId·멱등키는 trim·대소문자 변환 없이 그대로 비교한다. 허용 문자·길이는 API 계약에 명시하고, DB는 후행 공백까지 구분하는 NO PAD binary collation(utf8mb4_0900_bin 계열)을 사용한다 | harness/questions.json | CP-CONTRACT-001, CP-DB-001 | 2026-10-06 |
| D-CP-002 | Q-CP-MONEY | "KRW + 외화 일부" + 상세 "기준정보로 활성화 (Recommended)". 코드는 ISO 4217 통화와 통화별 minor unit(소수 자릿수)을 일반적으로 다루고, 허용 통화는 MID·기관 계약별 기준정보로 활성화한다. 초기 구현·검증은 KRW(0자리)와 USD(2자리)로 한다. 외화 거래의 정산 통화·환율은 "나중에 결정"(Q-SETTLE-CURRENCY, SETTLEMENT-CONTRACT-001); 지금은 거래통화를 승인 거래에 보존만 한다 | harness/questions.json | CP-CONTRACT-001, CP-DB-001, SETTLEMENT-CONTRACT-001 | 2026-10-06 |
| D-CP-003 | Q-CP-RESULT | "저장된 생성 결과만 (Recommended)". 같은 멱등키 재요청은 최초 생성 결과(paymentId, paymentKey, 생성 당시 상태)를 반환하고, 현재 상태는 별도 조회 API로만 제공한다 | harness/questions.json | CP-CONTRACT-001, CP-API-001 | 2026-10-06 |
| D-CPC-001 | Q-CPC-INTENT | "Site + 금액 + 통화 (Recommended)". 같은 OrderKey의 고정 주문 입력은 merchantSiteId·amount·currency다. 결제수단과 주문명 등 표시 정보는 비교하지 않는다(최초 값 저장) | harness/questions.json | CP-CONTRACT-001 이후 | 2026-10-06 |
| D-CPC-002 | Q-CPC-IDFORMAT | "1~64자 ASCII 제한 (Recommended)". merchantOrderId·멱등키는 1~64자, 문자 집합 [A-Za-z0-9-_.:], 공백·비ASCII·제어문자 거절, 대소문자 구분(D-CP-001 그대로 비교) | harness/questions.json | CP-CONTRACT-001, CP-DB-001 | 2026-10-06 |
| D-CPC-003 | Q-CPC-OUTBOX | "발행 (Recommended)". 결제 생성 시 PaymentRequested v1 통합 이벤트를 같은 PAYMENT TX로 Outbox에 기록한다. 금융 효과 이벤트(승인 등)와 이름·의미를 구분한다 | harness/questions.json | CP-CONTRACT-001, CP-DB-001 | 2026-10-06 |
| D-CPC-004 | Q-CPC-RETENTION | "기간 한정" + 상세 "30일 고정". 멱등 요청 기록 보존 기간은 30일(운영값 확정). 경과 후 같은 키는 새 요청으로 처리하며, 주문 활성/성공 통제는 OrderPaymentControl이 영구히 유지한다 | harness/questions.json | CP-CONTRACT-001, CP-DB-001 | 2026-10-06 |
| D-CPC-005 | Q-CPC-AMOUNT | "기술 한계만 (Recommended)". 금액은 통화 minor unit 정수, 1 이상·BIGINT 이하만 계약에서 강제한다. 건당·일 한도 등 업무 상한은 MID·계약 기준정보 한도 정책으로 별도 관리하며 코드에 운영 상한을 두지 않는다 | harness/questions.json | CP-CONTRACT-001 | 2026-10-06 |
| D-CPC-006 | G-1 | "Master 먼저 구현". CP-API-001·AUTH-001 이전에 Master 실행 단위(MASTER DB 소유: Merchant·Site·MID·Agreement·Activation·권리 Snapshot 발행)를 구현한다. CP-CONTRACT-001·CP-DB-001은 Port 계약 기준으로 선행 가능 | harness/questions.json, docs/repository/DOMAIN-DEFINITION.md G-1 | MASTER-*, CP-API-001, AUTH-001 | 2026-10-06 |
| D-CPC-007 | Q-CPC-REJECT-REPLAY | "저장하고 30일 재생 (Recommended)". 409 업무 거절(ORDER_INTENT_CONFLICT·ORDER_ON_HOLD·ORDER_ALREADY_AUTHORIZED·ORDER_RESULT_UNCONFIRMED)도 그 멱등키의 결과로 저장해 보존 기간(30일) 동안 같은 키 재요청에 같은 거절을 재현한다. 새 고객 시도는 새 키로만 가능 | harness/questions.json | CP-DB-001, CP-API-001 | 2026-10-06 |
| D-CPC-008 | Q-CPC-LIMIT-STAGE | "승인 시작에서 최종 판정 (Recommended)". 기준정보 거래 한도의 권위 판정은 승인 시작(AUTH, 허용 확정본 §4.1-13)에서 한다. 생성 시에는 건당 한도만 선택적으로 사전 거절할 수 있고 일 한도는 생성만으로 점유하지 않는다 | harness/questions.json | MASTER-SNAPSHOT-001, AUTH-CONTRACT-001 | 2026-10-06 |
| D-CPC-009 | Q-CPC-AMOUNT-JSON | "JSON 정수 유지 (Recommended)". amount는 JSON 정수, 서버는 정확한 정수 파서로 검증한다. 2^53-1 초과 금액은 가맹점이 정확한 정수 직렬화를 써야 함을 API 문서에 명시 | harness/questions.json | CP-API-001 | 2026-10-06 |

구현 세부 선택(가역적, 사용자 업무 정책 아님): Gradle 멀티 프로젝트 `apps/<실행단위>`, 루트 패키지 `com.paycore.<unit>`, version catalog, MySQL 이미지 8.4.11 pin, 개발 compose의 DB/계정 분리. 근거: docs/repository/BOOT-001-DISCOVERY.md §4.

구현 세부 선택은 별도로 기록하며 사용자 업무 정책 승인으로 표현하지 않는다. 승인 없는 운영 숫자·기본 허용 정책은 추가하지 않는다.
