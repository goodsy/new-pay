# PayCore Claude Code Harness Starter v0.2

2026-10-03 KST / 사용자 요청: 지금 시작하고 미결은 개발 중 질문해 확정

## 포함 내용과 현재 상태

이 팩은 Claude Code 프로젝트 지침·역할별 에이전트·작업/질문 카드·검증/재개 계약·시작 프롬프트·현재 설계 Snapshot이다. 실제 PayCore 소스나 자동 Claude CLI Runner는 포함하지 않는다. 실제 저장소 경로/테스트 명령을 BOOT-001에서 찾아 등록한 뒤 실행한다. 지금부터 Repository Discovery와 독립 기초 작업을 시작할 수 있으며 모든 금융 작업이 READY라는 뜻은 아니다.

`CLAUDE.md`에는 짧은 공통 지침을 두고 `docs`는 필요할 때 읽는다. 확정 원문과 후보/과거 지침을 구분한 인덱스가 있다. `.claude/agents`는 Implementer/Verifier/Reviewer 역할이다. Coordinator는 주 Claude Code 세션이다.

## 적용 방법

1. 압축을 별도 폴더에 해제한다. 기존 소스 저장소의 지침/변경을 확인한 뒤 적용한다.
2. 새 저장소라면 이 팩의 내용물을 프로젝트 루트에 놓는다. 기존 저장소라면 `docs` 내용을 `docs/paycore-harness` 등 충돌 없는 경로에 병합하고 지침의 경로를 맞춘다. 기존 CLAUDE.md를 덮어쓰지 말고 필요한 항목을 병합한다. 기존 `.claude/agents`, `harness` 이름 충돌도 확인한다.
3. 프로젝트 루트의 Claude Code에서 `claude --version`으로 설치 버전을 확인하고 지침/agents가 읽히는지 확인한다. 기본 권한 체계를 유지한다. 자동 무제한 실행/권한 우회 옵션을 켜지 않는다.
4. `docs/START-PROMPT.md` 내용을 첫 메시지로 전달한다.
5. BOOT-001은 현재 저장소를 조사해 실제 구조·명령·허용 경로·검증환경을 기록한다. 소스가 없으면 사용자 요청에 따른 기본 구조를 제안/준비하되 SDK 설치/외부 다운로드 등 환경 권한은 실제 도구 정책을 따른다.
6. 미결 금융/보안 질문은 작업별로 남기고 독립 작업을 계속한다. 모든 작업이 질문 대기인 경우 다음 답변 후 재개한다.

## 구성

- `CLAUDE.md`: 루트 지침.
- `.claude/agents/`: 역할 정의. 설치 버전에서 지원 여부 확인.
- `docs/HARNESS-GUIDE.md`: 상태·작업 점유·질문·검증·재개.
- `docs/START-PROMPT.md`: 바로 전달할 시작 프롬프트.
- `docs/BASELINE-INDEX.md`, `docs/baseline/`: 현재 정책/명세 Snapshot.
- `harness/tasks.json`, `questions.json`: 초기 작업·미결 등록부.
- `harness/commands.json`: 빈 실제 명령 등록부. BOOT 이후 등록.
- `harness/archive/`: 2026-11-20 이전 상세 작업 기록(tasks.json schemaVersion 1)과 progress.json 원본. 수정하지 않는다(D-PROC-002).
- `harness/EVIDENCE-TEMPLATE.json`: 검증 증거 양식.
- `harness/REVIEW-TEMPLATE.md`: 독립 리뷰 양식.
- `MANIFEST.json`: 배포 팩의 파일 hash. 설치 후 상태/로그는 변경되므로 각 검증에 적용한 명세 hash를 별도로 기록.

## 중요한 구분

도메인 단위시험에서 100,000 KRW, HALF_UP 같은 fixture를 쓰는 것은 가능하지만 운영 통화/반올림 계약을 자동 확정하지 않는다. first feature의 키 동일성·정확한 금액 범위·응답/오류·DB 제약을 선택해야 한다면 사용자에게 권장안을 질문하고 답변 전 의존 DDL/API를 고정하지 않는다. 필요 없는 후속 정산/기관 정책까지 한꺼번에 질문하지 않는다.

처음에는 한 작업을 순차 구현·검증·검토한다. 이후 독립 모듈 작업만 별도 worktree 등으로 분리할 수 있다. 대규모 팀 기능이나 특정 Claude 모델·유료 SDK·브로커가 이 팩의 필수 조건은 아니다.

## 확인한 공식 문서

- [프로젝트 CLAUDE.md](https://code.claude.com/docs/en/memory)
- [프로젝트 subagents](https://code.claude.com/docs/en/sub-agents)
- [권한 설정](https://code.claude.com/docs/en/permissions)

문서는 2026-10-03 확인했다. 설정을 실제 Claude Code에 설치/실행한 검증은 아직 하지 않았다. 문서 지침만으로 경로/네트워크/운영 Secret 접근이 차단된다고 보장하지 않는다.

## v0.2 추가

`docs/TECHNOLOGY-AND-PRINCIPLES.md`: 사용자 8대 필수 원칙, Kotlin/Spring/JPA/jOOQ/Thymeleaf/DDD·헥사고날, 버전 기준선, Kafka/Redis 적합성, JPA 설명 규칙. `TECH-001` 카드와 원칙별 검증 필드를 추가했다. 실제 기술 조합 실행은 아직 수행하지 않았다.
