# 추가 결정 기록

현재 기준은 BASELINE-INDEX.md의 채택 문서다. 이 팩 작성은 새 금융 정책 채택이 아니다.

사용자 답변을 받으면 다음 형식으로 추가한다. 질문을 먼저 PROPOSED → ASKED, 답변 근거를 받은 뒤 ANSWERED, 명세 반영·검토 후 APPLIED로 관리한다.

| 결정 ID | 질문 ID | 사용자 답변/근거 | 적용 명세·버전 | 영향 작업 | 적용일 |
|---|---|---|---|---|---|
| D-ENV-001 | (사용자 지시) | 개발환경 확정: WSL2 Ubuntu, Java 25, Kotlin, Gradle Kotlin DSL+Wrapper, Spring Boot 3.x, DDD+헥사고날+Spring Modulith, MySQL 8.4 LTS, JPA, jOOQ, Flyway, Spring Batch, Kotest+MockK+Testcontainers+ArchUnit, Docker Desktop+Compose, Kafka/Redis 초기 미도입. 하네스 문서와 충돌 시 이 기준 우선 | docs/repository/BOOT-001-DISCOVERY.md §3, harness/technology-baseline.json | BOOT-001, TECH-001, 전체 | 2026-10-03 |
| D-TECH-001 | Q-TECH-BOOT | "Boot 4.1.1 (Recommended)" 선택. 근거: Boot 3.5.16이 3.x 마지막 OSS 릴리스(2026-06-30 OSS 종료), Boot 3.5는 JDK 25에서 실행 가능한 Gradle(9.x)을 공식 지원하지 않음. D-ENV-001의 "3.x"를 대체 | docs/repository/TECH-BASELINE.md | BOOT-001, TECH-001 | 2026-10-03 |

구현 세부 선택(가역적, 사용자 업무 정책 아님): Gradle 멀티 프로젝트 `apps/<실행단위>`, 루트 패키지 `com.paycore.<unit>`, version catalog, MySQL 이미지 8.4.11 pin, 개발 compose의 DB/계정 분리. 근거: docs/repository/BOOT-001-DISCOVERY.md §4.

구현 세부 선택은 별도로 기록하며 사용자 업무 정책 승인으로 표현하지 않는다. 승인 없는 운영 숫자·기본 허용 정책은 추가하지 않는다.
