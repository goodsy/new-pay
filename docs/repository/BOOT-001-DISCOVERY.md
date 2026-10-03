# BOOT-001 저장소·개발환경 조사 결과

조사 일시: 2026-10-03 KST / 수행: Coordinator(주 세션)

## 1. 저장소 상태 (조사 시작 시점)

| 항목 | 결과 |
|---|---|
| 경로 | WSL2 `/home/sykim/workspace/paycore` (Windows `\\wsl.localhost\Ubuntu\...`) |
| git | `main`, commit `bac1bbe` "chore: bootstrap PayCore Claude Code harness" 1건, working tree clean |
| 내용 | 하네스 팩만 존재(CLAUDE.md, docs, harness, .claude/agents, MANIFEST.json, .gitignore, .idea). **소스·빌드 파일·기존 CI 없음** |
| 기존 지침 | 루트 `CLAUDE.md`(하네스 팩 것) 하나. 다른 AGENTS.md/settings.json 없음. `.claude/settings.local.json`은 gitignore 대상 |
| 사용자 변경 | 미커밋 변경 없음 → 덮어쓸 기존 사용자 작업 없음 |
| `.claude/agents` | paycore-implementer / verifier / reviewer 3종이 Agent 도구의 subagent_type으로 인식됨 |

참고: Windows 측 Git(Git Bash)은 UNC 경로 소유자 불일치로 `dubious ownership` 오류를 낸다. git/빌드 명령은 **WSL 안에서 실행**한다(`wsl.exe -d Ubuntu --cd /home/sykim/workspace/paycore -- <argv>`). 전역 safe.directory 변경은 하지 않았다.

## 2. 개발환경 (실측)

| 항목 | 실측 값 |
|---|---|
| OS | Ubuntu 26.04.1 LTS on WSL2 (kernel 6.18.33.2-microsoft-standard-WSL2), 8 CPU, 7 GiB RAM |
| JDK | OpenJDK 25.0.4.1 (Ubuntu 패키지, `/usr/lib/jvm/java-25-openjdk-amd64`). 다른 JDK 없음 |
| Gradle | 시스템 설치 없음 → Wrapper 9.8.0 생성 (배포 zip SHA-256 검증, wrapper에 `distributionSha256Sum` 고정) |
| Docker | Docker Desktop 연동, Engine 29.8.0, Compose v5.5.1. Testcontainers 2.0.5가 정상 기동 확인 |
| Python | python3 사용 가능 (보고서 요약 스크립트용) |
| 네트워크 | Maven Central, services.gradle.org, Docker Hub, GitHub 접근 가능 |

Gradle problems report 경고 1건: `/usr/lib/jvm/openjdk-25`(심볼릭 디렉터리)에 java 실행 파일이 없다는 toolchain 자동 탐지 경고. 실제 toolchain은 `java-25-openjdk-amd64`로 해결되며 빌드에 영향 없음.

## 3. 사용자 확정 기준과 하네스 문서 차이

사용자가 2026-10-03 확정한 개발환경 기준을 하네스 문서보다 우선한다. 차이는 아래와 같다.

| 항목 | 하네스 문서(v0.2) | 사용자 확정 / 적용 | 비고 |
|---|---|---|---|
| Spring Boot | 4.1.1 기준선 | 사용자 지정 "3.x" → **질문 후 4.1.1로 변경 확정** | 3.5.16(2026-06-25)이 3.x 마지막 OSS 릴리스. 3.5는 Gradle 7.6.4+/8.4+만 지원하고 Gradle 8은 JDK 25에서 실행 불가. D-TECH-001 |
| JDK | 21 LTS 후보 | **Java 25** | Boot 4.1 지원, Kotlin 2.3.21 JVM 25 target 컴파일 확인 |
| DB | PostgreSQL 후보 | **MySQL 8.4 LTS** (8.4.11 고정) | jOOQ OSS edition의 MYSQL dialect 사용 |
| 아키텍처 | DDD/헥사고날, Modulith는 후보 | **DDD + 헥사고날 + Spring Modulith** | Modulith 2.1.1 (Boot 4.1 라인) |
| Migration | Flyway(아키텍처 문서) | Flyway | Boot 4는 `spring-boot-starter-flyway` 필요 |
| Test | 미지정 | **Kotest + MockK + Testcontainers + ArchUnit** | Mockito는 starter-test에서 제외 |
| Kafka/Redis | 적합성 검토 후 도입 | **초기 미도입** | TECH-001 ADR에 도입 trigger 기록 예정 |
| Thymeleaf | 우선 | 사용자 목록에 없음 | 기존 사용자 지정(TECHNOLOGY 문서)을 유지. 화면 실행 단위 생성 시 적용, 현재 payment-api에는 미포함 |
| Spring Batch | 지정 | 지정 | financial-batch 실행 단위에서 도입. 현재 skeleton에는 미포함 |

## 4. 결정한 skeleton (가역적 구현 세부, 사용자 업무 정책 아님)

- Gradle 멀티 프로젝트 + version catalog(`gradle/libs.versions.toml`). Boot 관리 항목은 `platform(spring-boot-dependencies)`로 BOM 버전 사용, BOM 밖 항목만 catalog에 고정.
- 실행 단위별 모듈 `apps/<unit>`. 아키텍처 01 문서의 실행 단위 중 첫 기능(CreatePayment)이 속한 **`apps/payment-api`만 생성**. master-api / financial-* 은 해당 작업에서 추가.
- 루트 패키지 `com.paycore.payment` (payment-api). 하위 패키지가 Modulith application module이 된다.
- Kotlin 컴파일 경고를 오류로 처리(`allWarningsAsErrors`), `-Xjsr305=strict`.
- JDK toolchain 자동 다운로드 금지(`org.gradle.java.installations.auto-download=false`).
- 테스트 0건 발견 시 실패(`failOnNoDiscoveredTests = true`).
- 설정: DB 접속은 환경변수만(기본값/비밀번호 없음), `open-in-view: false`, `ddl-auto: validate`, Flyway 위치 `db/migration/payment`.
- 로컬 개발용 `compose.yaml`: MySQL 8.4.11 단일 인스턴스에 master/payment/financial DB와 DB별 계정. 개발 편의일 뿐 운영 격리 증거가 아님. **collation은 지정하지 않음**(키 동일성 Q-CP-KEY 미결). 단, 미지정은 중립이 아니다: MySQL 8.4 서버 기본값 `utf8mb4_0900_ai_ci`(대소문자·악센트 무시)가 compose와 Testcontainers 모두에 적용된다. 그래서 CP-DB-001 수용 기준에 "모든 키 컬럼의 COLLATE를 명시하고 information_schema로 검사"를 추가했다.
- **업무 코드·DDL·API 없음.** Flyway migration은 비어 있다.

### 허용 경로 조정

BOOT-001 카드의 최초 허용 경로는 `harness/**`, `docs/repository/**`였다. 사용자가 이번 요청에서 skeleton 확정을 명시했으므로 Coordinator가 아래 경로를 추가로 변경했다: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/**`, `gradlew`, `gradlew.bat`, `apps/payment-api/**`, `compose.yaml`, `docker/**`, `.env.example`, `docs/DECISIONS.md`. 또한 Reviewer 지적(F4)에 따라 `.gitignore`의 `data/`, `docker-data/`를 루트 고정(`/data/`, `/docker-data/`)으로 바꿨다. 소스 하위의 `data/` 디렉터리가 fingerprint에서 빠지지 않게 하려는 것이다. tasks.json에 `originalAllowedWritePaths`를 보존했다.

## 5. 검증 명령 (harness/commands.json에 argv로 등록)

| commandId | argv (WSL, cwd=repo root) | 용도 |
|---|---|---|
| GRADLE-CHECK | `./gradlew --no-daemon --no-build-cache --rerun-tasks check` | 컴파일+전체 테스트(Testcontainers MySQL 포함). 검증은 cache/up-to-date 재사용 금지 |
| TEST-SUMMARY | `python3 harness/scripts/test-summary.py --not-before <check 시작 UTC> <minTests> apps/payment-api/build/test-results/test` | 보고서 존재·개수·실패/skip·실행시각 신선도 판정, 보고서 SHA-256 |
| FINGERPRINT | `harness/scripts/fingerprint.sh` | 추적+미추적 파일 SHA-256 manifest 기반 code fingerprint. 진행 상태 JSON(tasks/progress/questions)만 제외, commands.json 포함 |
| GRADLE-DEPS | `./gradlew --no-daemon -q :apps:payment-api:dependencies --configuration runtimeClasspath` | resolved dependency tree |

1차 Verifier 실행에서 `org.gradle.caching=true` 때문에 test task가 FROM-CACHE로 복원되어 실제 테스트가 실행되지 않은 사실이 발견됐다(INSUFFICIENT_EVIDENCE). GRADLE-CHECK에 `--no-build-cache --rerun-tasks`, TEST-SUMMARY에 필수 `--not-before` 신선도 검사를 추가했다. 개발 중 일반 빌드는 cache를 계속 사용한다.

Reviewer 지적으로 FINGERPRINT가 gate 정의(commands.json)를 포함하도록 수정했다. `wsl.exe`는 argv를 WSL shell을 거쳐 실행하므로 shell 메타문자가 든 argv는 등록하지 않는다.

## 6. 현재 테스트 범위 (정직한 한계)

| Spec | 검사 | 한계 |
|---|---|---|
| PaymentApiContextSmokeSpec (4) | 실제 MySQL 8.4 연결/버전, Flyway 위치·pending 0, JPA EMF 기동, jOOQ MYSQL dialect 쿼리 | 업무 테이블 없음. JPA Entity 저장·Version·TX rollback은 미검증(TECH-001/CP-DB-001) |
| ArchitectureSpec (2) | Modulith 구조 verify, domain 패키지 기술 의존 금지 규칙 | 아직 모듈/domain 패키지가 없어 **두 규칙 모두 대상이 비어 있다**(vacuous). FOUNDATION-001에서 실제 대상이 생겨야 의미 있음 |

미검증: jOOQ codegen(migration schema 기반), Thymeleaf 렌더, Batch job 실행/재시작, CVE 검사 → TECH-001.

## 7. 다음 독립 작업

1. HARNESS-001: commands.json 등록분 검토·보고서 계약 확정(이번에 초안 등록).
2. TECH-001: Batch/Thymeleaf/jOOQ codegen smoke, JPA Entity+Version+rollback smoke, CVE 검사, Kafka/Redis 미도입 ADR.
3. FOUNDATION-001: payment-api 내부 Modulith 모듈·헥사고날 패키지, ArchUnit 규칙을 실제 대상으로 강화.
4. CP-DOMAIN-001: 확정된 Order/Payment 활성 규칙 단위 테스트(fixture 동일성).

질문 대기: Q-CP-KEY / Q-CP-MONEY / Q-CP-RESULT는 CP-CONTRACT-001 착수 시 제출(지금 일괄 질문하지 않음).
