# 맥북(macOS)으로 개발 환경 옮기기

- 작성: 2026-11-22. 기준 커밋 main `8370c09`(BUILD-MAC-001 반영은 main `de12ab3` 위).
- 지금까지는 Windows + WSL Ubuntu(linux-x64)에서 개발했다.

## 1. 이어서 할 작업 위치

| 위치 | 내용 |
|---|---|
| `main` | 재정비 1~5단계 완료(5단계 = ADMIN-DS-001 기준 문서) |
| `wip/lock-unify` | 재정비 6단계 첫 작업 LOCK-UNIFY-001(RECOVERY-ROADMAP 2-1, 결과 불명 잠금 3벌을 공용 hook 하나로). 구현과 독립 리뷰(REVIEW_PASS)는 끝났다. **SCOPED 게이트는 중단돼 판정이 없다.** main에 병합하지 않았다 |
| `wip/scode-1` | SITE-CONTRACT-CODE-001 미완성 변경 보존본(D-PROC-006). 미검증, 병합 금지 |

맥에서 재개하는 순서:

1. 아래 §2 환경을 준비한다.
2. §3의 빌드 차단을 해결한다(BUILD-MAC-001, 완료).
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

## 3. 프런트엔드 빌드 플랫폼 (BUILD-MAC-001로 해결)

- 처음에는 `paycore.frontend`가 linux-x64만 허용해 Apple Silicon 맥에서 관리자 화면·결제창 빌드와 게이트가 실패했다.
- BUILD-MAC-001에서 darwin-arm64를 추가했다. 지원 호스트는 catalog에 `node-sha256-<platform>`이 있는 것뿐이다(`linux-x64`, `darwin-arm64`).
- darwin-arm64 SHA-256은 nodejs.org `SHASUMS256.txt.asc`(clearsigned) GPG 검증(Good signature, 키 `5BE8A3F6…B168D356`) 뒤 넣었다. 검증 규칙(SHA-256 대조, 버전 고정)은 그대로다.
- lockfile은 바꾸지 않았다. 두 플랫폼의 네이티브 binding이 이미 들어 있다.
- 다른 호스트(Intel 맥, Linux arm64 등)는 `frontendNodeVerify`에서 실패한다. 추가하려면 같은 절차로 catalog 키와 분기를 넣는다.

## 3-1. 맥 Docker(OrbStack) 주의

- OrbStack이 CLI 링크(`/usr/local/bin/docker`)와 `/var/run/docker.sock`을 만들지 않은 상태면 다음을 지정해 게이트를 실행한다.
  - `PATH=/Applications/OrbStack.app/Contents/MacOS/xbin:$PATH`(IMAGE-DIGESTS 단계가 `docker`를 쓴다)
  - `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`(Testcontainers)
- OrbStack 설정에서 명령줄 도구를 설치하면 이 지정은 필요 없다.
- `docker compose` 플러그인이 잡히지 않으면 `docker-compose` 단독 실행 파일을 쓴다(Demo `db.sh`가 자동 선택).

## 3-2. 게이트 병렬도 올리기 (BUILD-PERF-MAC-001)

- 저장소 기본값(DB test JVM 1개씩 직렬, workers 4, daemon `-Xmx2g`)은 메모리 작은 PC 기준이다. 이 맥(32GB, OrbStack VM 16GB)에서는 저장소 밖
  `~/.gradle/gradle.properties`에 아래를 둬 FULL 게이트 GRADLE-CHECK를 약 11분 40초 → 약 6분 10초로 줄였다. 측정·근거: [TECH-BASELINE §7.3](repository/TECH-BASELINE.md) "호스트별 병렬도 속성".
- 이 파일은 프로젝트 `gradle.properties`보다 우선한다. 사용자 홈 `org.gradle.jvmargs`는 저장소 값을 통째로 대체하므로 나머지 옵션도 함께 적는다.
  저장소 `gradle.properties`의 jvmargs를 바꾸는 작업은 TECH-BASELINE §7.3 표와 각 호스트의 `~/.gradle/gradle.properties`를 함께 갱신한다.
- 적용되면 빌드 시작 때 `paycore concurrency: dbTestJvmSlots=3 kotlinCompileSlots=2 testMaxHeap=1g` 한 줄이 찍힌다.
- 잘못된 값(0, 숫자 아님)은 빌드가 실패한다. 파일을 지우면 저장소 기본값으로 돌아간다.

```properties
paycore.dbTestJvmSlots=3
paycore.kotlinCompileSlots=2
org.gradle.workers.max=6
org.gradle.jvmargs=-Xmx3g -XX:+UseG1GC -XX:G1PeriodicGCInterval=30000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Dfile.encoding=UTF-8
```

- 선택: `paycore.testMaxHeap`(기본 `1g`, 예 `1536m`)은 이 맥에서도 올리지 않았다.
- 측정할 때는 Demo 앱을 멈춘다(`bash ~/paycore-demo/app.sh stop all`).

## 4. 저장소 밖에 있던 것 (git에 없음)

| WSL 위치 | 내용 | 옮기는 방법 |
|---|---|---|
| `~/paycore-demo/` | Demo 실행 스크립트(app.sh, db.sh, compose-override.yaml), Demo 계정·API 키·bootstrap 코드 파일 | **직접 복사**한다. 계정·키 파일이 있으므로 git, 메신저, 클라우드에 올리지 않는다. 스크립트 안 경로(`~/workspace/paycore` 등)는 맥 경로에 맞춘다 |
| `~/gate-logs/` | 게이트 실행 로그와 실행 스크립트 | 필요하면 복사한다. 판정 증거는 저장소 `build/harness-evidence`(git 제외)에 있다 |
| Claude Code 메모리 `C:\Users\<사용자>\.claude\projects\<프로젝트>\memory\` | 진행 상태, 작업 규칙(한국어 답변, main 직접 커밋, STUDY.md 손대지 않기, 화면 우선 등) | 맥의 `~/.claude/projects/<맥 경로에 맞는 이름>/memory/`로 복사한다. 폴더 이름은 맥에서 Claude Code를 한 번 실행하면 생긴다 |
| 저장소 루트 `STUDY.md`, `GUIDE.md`, `SCREENS.md`, `pg-workspace-react-kit.zip` | 사용자 개인 파일(커밋 대상 아님) | 필요하면 직접 복사한다 |

## 5. 경로 차이 주의

- 메모리와 스크립트에 WSL 경로 `/home/sykim/workspace/paycore`와 Windows 경유 실행(`powershell.exe … wsl.exe bash`)이 적혀 있다. 맥에서는 일반 셸에서 바로 실행한다.
- 프런트 시험을 직접 실행할 때 쓰는 Node: 맥 `apps/payment-api/.gradle/nodejs/node-v24.22.1-darwin-arm64/bin/node`(WSL에서는 `…-linux-x64/bin/node`).
- Demo 스크립트(`~/paycore-demo`)는 맥용으로 고쳤다: `setsid` 대신 perl `POSIX::setsid`, `/proc/meminfo` 대신 `vm_stat`, JDK 25 고정(`/usr/libexec/java_home -v 25`), bash 3.2 빈 배열 처리, `stat`/`sha256sum` 이식. WSL에서 가져온 `run/*.pid`는 맥 프로세스 pid와 겹칠 수 있어 치웠다.
