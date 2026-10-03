# Claude Code 첫 메시지

아래 내용을 프로젝트의 Claude Code에 전달한다.

---

PayCore를 하네스 방식으로 개발 시작해줘. CLAUDE.md와 docs/HARNESS-GUIDE.md, docs/BASELINE-INDEX.md, harness 작업/질문/진행 기록을 읽고 실제 저장소부터 점검해.

docs/TECHNOLOGY-AND-PRINCIPLES.md의 8대 필수 원칙과 Kotlin/Spring Boot·Batch/JPA/jOOQ/Thymeleaf/DDD·헥사고날 구성을 적용해. 안정 GA와 Boot BOM을 확인해 호환 버전을 고정하고 실제 smoke test를 남겨. JPA 선택은 관련 코드에 연결해 쉽게 설명해. Kafka/Redis는 적합성·장애 영향 검토 후 도입해. splearn-1-part2 참고는 commit 기준으로 검토하고 확인하지 못한 내용을 확인했다고 말하지 마.

모든 정책이 확정될 때까지 전체 작업을 멈추지 마. 저장소 조사, 확정된 도메인 규칙 테스트, 기초 구조처럼 독립적으로 할 수 있는 작업부터 진행해. 클래스/Mapper/내부 패키지 같은 구현 세부는 관례로 결정하고 기록해.

금융·보안 의미, 키 동일성, 금액/통화 범위, 상태/복구, 외부기관 결과 해석처럼 필요한 미결은 구현 직전에 나에게 권장안과 대안을 질문해. 질문 대기 작업만 WAITING_DECISION으로 두고 독립 작업을 계속해. 답변이 없다고 권장안을 확정하거나 운영 정책을 추측하지 마.

현재 처음 구현할 PAYMENT 기능은 CreatePayment + OrderPaymentControl이야. REQUESTED 포함 주문 활성 하나, 같은 요청/주문 같은 내용 재사용, 다른 내용 충돌, 안전한 FAILED/EXPIRED만 새 시도를 허용하는 확정 규칙을 따라. 기술 계약 초안의 READY 미충족 항목은 해당 작업에만 적용해.

BOOT-001로 기존 파일과 사용자 변경, 지침, 실제 빌드/DB/테스트 명령을 먼저 확인해. 기존 프로젝트가 있으면 구조를 갈아엎지 말고 맞춰. 없으면 설계 자료의 Kotlin/Spring/Modulith/Hexagonal 방향을 기본안으로 검토하고 기술 선택을 기록하며 최소 구조를 준비해. 금융 의미가 미결인 DDL/API는 먼저 질문해.

Coordinator가 작업/질문 로그를 소유하고 Implementer→Verifier→읽기 전용 Reviewer를 순서대로 사용해. 지원하지 않는 역할 기능은 별도 세션/수동 검토로 대체하고 차이를 알려줘. 같은 파일을 여러 구현자가 동시에 수정하지 마.

작업당 관련 TDD·실제 DB 경합/rollback·권한·복구 테스트와 정확한 코드 fingerprint의 증거를 남겨. 테스트 0건/환경 오류를 PASS로 취급하지 마. 실패 수정은 기본 3회까지 하고 이후 원인과 다음 조치를 보고해.

현재 외부기관은 가상 VAN TCP·매입 SFTP·가상 은행을 사용해. 실제 승인·송금·운영 DB/Secret·배포·병합은 하지 마. 정책 예시 숫자는 DEMO DATA로 분리해.

먼저 조사 결과와 지금 진행할 작업, 필요한 첫 질문만 짧게 알려주고 바로 독립 작업을 진행해. 세션 끝에는 변경/검증/현재 상태/미결/재개 동작을 기록해.
