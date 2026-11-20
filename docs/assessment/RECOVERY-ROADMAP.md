# PayCore 권장 재정비 순서와 완료 기준 (RECOVERY-ROADMAP)

- 작성: 2026-11-20, 기준 커밋 `378aff7`. **제안 문서다. 실제 소스 수정은 사용자 승인 뒤에 시작한다.**
- 근거: REBUILD-VS-REFACTOR.md 권장안 "A + 선별된 B 요소", COMPLEXITY·DOMAIN·FRONTEND 진단.
- 원칙
  - 기존 금융 업무 규칙과 시험 자산은 보존한다.
  - 새 기능·새 기술은 넣지 않는다.
  - 단계마다 작게 커밋하고, 기존 시험으로 회귀를 확인한다.
  - 사용자 결정이 필요한 단계는 결정 전에 시작하지 않는다.

## 0. 시작 전 정리 (사용자 결정 필요)

| 항목 | 내용 | 완료 기준 |
|---|---|---|
| 0-1 | REBUILD-VS-REFACTOR §6의 결정 R-1~R-6에 답을 받는다 | `questions.json` 기록, `DECISIONS.md` 반영 |
| 0-2 | 중단된 SCODE-1 미커밋 변경을 처리한다(보관 브랜치로 옮기거나 폐기 — R-6) | `git status`에 프로젝트 소스 변경 0건 |

## 1단계 — 프로세스 단순화 (예상 3~5 작업일)

| 작업 | 내용 | 완료 기준 |
|---|---|---|
| 1-1 게이트 범위 (R-1) | 배치 단계에는 "영향 모듈 검사" 명령을 둔다. 예: 프런트만 바꿨으면 tsc+vitest, Kotlin 모듈은 그 모듈의 `test`. 전체 게이트는 기능 종료나 묶음당 1회. commands.json에 등록하고 argv 고정·fingerprint·시험 하한 규칙은 유지 | 하네스 self-test 통과. 작은 기능 1개를 새 방식으로 처리해 소요 시간을 기록(목표: 기존 대비 50% 이상 단축) |
| 1-2 fingerprint 범위 | 빌드 입력(소스·빌드 스크립트·migration)과 문서를 따로 hash한다. 문서만 바꾸면 전체 게이트를 다시 돌리지 않는다 | 문서만 바꿨을 때 재게이트가 필요 없음을 시험으로 증명 |
| 1-3 기록 스키마 (R-2) | tasks.json task를 고정 키(id·status·spec·specHash·gateRuns 요약·openItems·nextResumeAction)로 바꾸고 상한(예: 100줄)을 둔다. 상세 기록은 `build/harness-evidence` 또는 git으로 옮기고, 저장소 밖 `~/gate-logs` 참조는 정리. progress.json은 자동 생성하거나 폐기 | tasks.json 크기 1.1MB → 목표 100KB 이하. 모든 task에 nextResumeAction. 하네스 self-test 통과 |
| 1-4 명세·결정 정본화 | 명세 본문은 "현재 규칙"만 담고 개정 이력은 파일 끝이나 git으로. DECISIONS에 `SUPERSEDED BY` 열 또는 "현재 유효 규칙 색인" 1장. 공통 규칙(주문 key·2인 승인 대상·수수료 Snapshot·UNKNOWN·데이터 소유)은 정본 1곳에 두고 나머지는 ID로만 참조 | 공통 규칙 5종마다 정본 위치가 하나. 다른 문서의 같은 서술은 참조로 대체 |
| 1-5 낡은 서술 정합화 | README(스타터 팩 설명), HARNESS-GUIDE:56, settings.gradle.kts:36 "ADMIN DB 없음", D-ENV-001, workspace.css:12, agents 파일 머리말, 2-1에서 고칠 progress.json | 목록의 각 항목을 고친 커밋 |
| 1-6 커밋 메시지 규칙 | 결정 전문과 검증 수치를 반복하지 않고 task ID·증거 경로·한 줄 요약만 쓴다 | CLAUDE.md 또는 HARNESS-GUIDE에 규칙 한 줄 |

## 2단계 — 금융 안전 정비 (예상 2~3 작업일)

| 작업 | 내용 | 완료 기준 |
|---|---|---|
| 2-1 화면 결과 불명 잠금 통합 | RegistrationStep·BusinessProfileForm·MerchantActivationArea의 잠금 3벌을 공통 hook 1개(ScreenMemory 기반)와 UNKNOWN_CODES 1개로 합친다 | 기존 동작 시험(같은 submissionId 재전송 등)이 바꾸지 않고 통과. 잠금 상태가 탭 이동 뒤에도 남는 것을 시험으로 증명 |
| 2-2 개통 명령 규칙 차이 확인 | `useOneShot`이 재전송 중 409가 아닌 오류에서 잠금을 푸는 동작이 명세상 허용되는지 확인. 어긋나면 2-1 규칙으로 맞춤 | 명세 근거 기록. 정렬했다면 RED→GREEN 시험 |
| 2-3 불안정 시험 정비 | timeout을 올리지 않고 fixture를 결정적으로 고친다(기록된 약 10건 유형) | 기록된 flaky 유형마다 원인 수정 커밋. 전체 게이트 3회 연속 PASS |

## 3단계 — 같은 모듈 안 중복 제거 (예상 2~4 작업일)

| 작업 | 규모 | 완료 기준 |
|---|---|---|
| 3-1 admin 승인 서비스 4종 공통 helper 추출 | 300~400줄 감소 | 승인 동시성·잠금 순서·결정표 시험이 바꾸지 않고 통과 |
| 3-2 순수 Clock Port 11개를 `java.time.Clock` 주입으로 | 약 22파일 | ArchUnit 규칙 통과, 시간 의존 시험 통과 |
| 3-3 master DisplayName·BusinessTextRule 통합, admin 내부 UTF-16·카드번호 검사 공통화 | 약 8파일 | 입력 규칙 벡터 시험 통과(서비스 간 의도된 복제는 유지) |
| 3-4 아키텍처 규칙 틈 보강 | simulator에 Modulith·순환 규칙 추가, financial `acquiring`에 provided package-info 추가 | 새 규칙 시험 통과 |
| 3-5 master domain 하위 패키지 분리(ARCH-SUBPKG-MASTER) | import 수백 줄 | 순환 0, 전체 시험 통과 |

## 4단계 — 관리자 화면 통합 (예상 3~5 작업일)

| 작업 | 완료 기준 |
|---|---|
| 4-1 스타일 체계를 하나로 정하고 `workspace` 플래그 제거(R-3 결과에 따름) | 플래그 분기 0. 두 체계를 함께 쓰는 파일 0 또는 문서화된 예외만 |
| 4-2 구조 단언을 동작 단언으로 전환 | `*.workspace.test`의 DOM 경로·class 단언을 역할·이름·접근성·동작 단언으로 바꾼다. CSP·내부 키·noRuntimeStyles 시험은 유지. 바꾸기 전과 같은 행동을 보장하는지 리뷰 확인 |
| 4-3 줄 컴포넌트 통합 | 입력은 Field → FormRow 하나로. 상세 행은 DetailRow, .detailgrid, 지역 Row, 승인 상세 dt/dd를 하나로 |
| 4-4 CSS 정리 | 다크 구역을 token 참조로 바꾸고 낡은 주석을 정리한다. 사용하지 않는 components/ui는 제거 |
| 4-5 adminApi.ts 분할 | 타입을 도메인별 파일로 나누고 justification 3중 메서드를 정리 |
| 4-6 App 정리 | 세션 리셋 한 곳, flush는 메뉴 메타데이터로 |
| 공통 완료 기준 | 브라우저 캡처(5폭 × 라이트·다크)에서 넘침·대비 미달·style 속성·콘솔 오류 0. 금융 안전 시험(잠금·내부 키·CSRF) 무변경 통과 |

## 5단계 — 큰 화면 분할 (예상 3~5 작업일)

| 작업 | 완료 기준 |
|---|---|
| MerchantsScreen(849), MerchantRegistrationScreen(978), AcquisitionsScreen(615), MerchantActivationArea(554)를 목록 / 상세 패널 / 명령 영역 컴포넌트로 나눈다 | 동작 시험(예: MerchantRegistrationScreen.test 59개)이 바꾸지 않고 통과. 파일당 400줄 이하 목표 |

## 6단계 (선택, 사용자 결정 R-4 이후) — persistence 계층 단순화

| 작업 | 완료 기준 |
|---|---|
| 기능 하나(예: master의 작은 Aggregate)로 시범 구현: Domain↔JPA 이중 모델 축소 방식, 또는 단일 구현 Port 정리 방식 | 시범의 파일 수·줄 수·시험 영향을 측정하고 사용자가 확대 여부를 결정. 확대하더라도 금융 Aggregate(payment)는 마지막에, 동시성·rollback 시험 재통과를 조건으로 |

## 7단계 — 기능 개발 재개

- 재정비 뒤 첫 기능은 중단된 SITE-CONTRACT-CODE-001(명세 rev 0.3 리뷰 반영 완료)이나 정산 계열(SETTLEMENT-CONTRACT-001 → SETTLEMENT-001) 중에서 사용자가 고른다.
- 완료 기준: 새 프로세스(1단계)로 처리한 기능 1개의 소요 시간·게이트 횟수·기록 크기를 측정해 이 로드맵의 추정을 보정한다.

## 전체 완료 기준 (재정비 종료 판정)

| 지표 | 현재 [사실] | 목표 [추정 기준값] |
|---|---|---|
| 작은 기능 1개 처리 시간 | 약 0.5~1일, 게이트 2.7시간 | 절반 이하, 전체 게이트 1회 |
| tasks.json 크기 | 1.1MB | ≤ 100KB |
| 공통 규칙 정본 | 여러 곳에 흩어짐 | 규칙마다 1곳 |
| 화면 결과 불명 잠금 구현 | 3벌(1벌 규칙 다름) | 1벌 |
| 스타일 체계 / workspace 분기 | 2 / 17 | 1 / 0 |
| 레이아웃 변경 시 시험 순증 비율 | main의 3.6배 | 1배 이하 |
| 금융 시험(동시성·golden·rollback·FaultInjection) | 전부 통과 | **바꾸지 않고 전부 통과** |
| 전체 게이트 | PASS(Kotlin 5,096 / FE 694) | PASS. 시험 수는 구조 단언 정리로 줄 수 있으나 금융 시험 수는 유지 |

## 예상 일정 요약 [추정]

| 단계 | 작업일 |
|---|---|
| 0 결정 | 사용자 응답 대기 |
| 1 프로세스 | 3~5 |
| 2 금융 안전 | 2~3 |
| 3 중복 제거 | 2~4 |
| 4 화면 통합 | 3~5 |
| 5 화면 분할 | 3~5 |
| **1~5 합계** | **13~22** (1단계 이후 처리 속도가 빨라지는 효과를 반영하면 10~18) |
| 6 (선택) | 15~25 |

**진행 방식**: 단계 1이 끝나면 처리 속도를 측정해 나머지 단계의 추정을 고친다. 각 단계는 작은 커밋 단위로 독립 리뷰와 영향 모듈 검사를 거치고, 단계 종료 때 전체 게이트를 1회 돌린다.
