# 맥북(macOS)으로 개발 환경 옮기기

- 작성: 2026-11-22. 기준 커밋 main `8370c09`.
- 지금까지는 Windows + WSL Ubuntu(linux-x64)에서 개발했다.

## 1. 이어서 할 작업 위치

| 위치 | 내용 |
|---|---|
| `main` | 재정비 1~5단계 완료(5단계 = ADMIN-DS-001 기준 문서) |
| `wip/lock-unify` | 재정비 6단계 첫 작업 LOCK-UNIFY-001(RECOVERY-ROADMAP 2-1, 결과 불명 잠금 3벌을 공용 hook 하나로). 구현과 독립 리뷰(REVIEW_PASS)는 끝났다. **SCOPED 게이트는 중단돼 판정이 없다.** main에 병합하지 않았다 |
| `wip/scode-1` | SITE-CONTRACT-CODE-001 미완성 변경 보존본(D-PROC-006). 미검증, 병합 금지 |

맥에서 재개하는 순서:

1. 아래 §2 환경을 준비한다.
2. §3의 빌드 차단을 해결한다.
3. `wip/lock-unify`를 main 위에서 SCOPED 게이트로 다시 검증한다.
4. 통과하면 main에 반영하고 재정비 6단계를 이어 간다. 이때 tasks.json의 LOCK-UNIFY-001 항목도 함께 반영한다.

## 2. 설치할 것

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | 25 | `jvmToolchain(25)`(build-logic `paycore.kotlin-base`). WSL에서는 OpenJDK 25.0.5.1을 썼다 |
| Gradle | 설치하지 않음 | `./gradlew`가 9.8.0을 받는다 |
| Node.js | 24.22.1 | 직접 설치하지 않는다. Gradle `paycore.frontend`가 받아서 검증한다. §3 참고 |
| Docker | Docker Desktop 또는 OrbStack | 시험(Testcontainers)과 Demo가 `mysql:8.4.11`을 쓴다. arm64 이미지가 있다. WSL에서는 Docker 29.8.0을 썼다 |
| Python | 3.x | 하네스 스크립트(`harness/scripts/verify-gate.py`, `check-records.py`, fingerprint). WSL에서는 3.14.4를 썼다 |
| 그 밖 | git, Claude Code | 선택: gh |

- 메모리: FULL 게이트는 Gradle 시험과 MySQL 컨테이너를 함께 돌린다. RAM 16GB 이상을 권한다. WSL 7.8GB에서는 메모리 부족으로 중단된 적이 있다.

## 3. 빌드 차단: 프런트엔드 빌드가 linux-x64만 허용

`build-logic/src/main/kotlin/paycore.frontend.gradle.kts`는 다음과 같이 되어 있다.

- `frontendNodeVerify`가 `os.name == Linux`이고 `os.arch`가 amd64/x86_64가 아니면 실패한다(53행).
- Node 배포본은 `node-v24.22.1-linux-x64` 하나로 고정되어 있다(18·22·42행).
- SHA-256은 `gradle/libs.versions.toml`의 `node-sha256-linux-x64` 하나뿐이다.

그래서 Apple Silicon 맥에서는 관리자 화면과 결제창 빌드, 그리고 게이트가 실패한다.

해결 방법 중 하나를 고른다.

1. **권장**: darwin-arm64 지원을 추가한다.
   - 플랫폼별 배포본 이름, catalog SHA-256(nodejs.org SHASUMS256.txt, GPG 서명 검증), 압축 형식을 넣는다.
   - 빌드 설정 변경이므로 하나의 작업으로 처리한다(독립 리뷰 → 게이트).
   - 검증 규칙(SHA-256 대조, 버전 고정)은 완화하지 않는다.
2. x86_64 Linux VM(에뮬레이션) 안에서 작업한다. 느리다.

## 4. 저장소 밖에 있던 것 (git에 없음)

| WSL 위치 | 내용 | 옮기는 방법 |
|---|---|---|
| `~/paycore-demo/` | Demo 실행 스크립트(app.sh, db.sh, compose-override.yaml), Demo 계정·API 키·bootstrap 코드 파일 | **직접 복사**한다. 계정·키 파일이 있으므로 git, 메신저, 클라우드에 올리지 않는다. 스크립트 안 경로(`~/workspace/paycore` 등)는 맥 경로에 맞춘다 |
| `~/gate-logs/` | 게이트 실행 로그와 실행 스크립트 | 필요하면 복사한다. 판정 증거는 저장소 `build/harness-evidence`(git 제외)에 있다 |
| Claude Code 메모리 `C:\Users\<사용자>\.claude\projects\<프로젝트>\memory\` | 진행 상태, 작업 규칙(한국어 답변, main 직접 커밋, STUDY.md 손대지 않기, 화면 우선 등) | 맥의 `~/.claude/projects/<맥 경로에 맞는 이름>/memory/`로 복사한다. 폴더 이름은 맥에서 Claude Code를 한 번 실행하면 생긴다 |
| 저장소 루트 `STUDY.md`, `GUIDE.md`, `SCREENS.md`, `pg-workspace-react-kit.zip` | 사용자 개인 파일(커밋 대상 아님) | 필요하면 직접 복사한다 |

## 5. 경로 차이 주의

- 메모리와 스크립트에 WSL 경로 `/home/sykim/workspace/paycore`와 Windows 경유 실행(`powershell.exe … wsl.exe bash`)이 적혀 있다. 맥에서는 일반 셸에서 바로 실행한다.
- 프런트 시험을 직접 실행할 때 쓰던 Node는 `apps/payment-api/.gradle/nodejs/node-v24.22.1-linux-x64/bin/node`였다. §3을 해결한 뒤에는 그 플랫폼의 Node 경로로 바뀐다.
