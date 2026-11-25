# 질문하면서 진행하는 하네스 실행 계약

## 1. 기능별 준비 Gate

전역 ALL_READY를 요구하지 않는다. 다음 세 종류를 구별한다.

| 작업 | 진행 기준 | 사례 |
|---|---|---|
| DISCOVERY | 읽기/환경 조사·문서 작성 | 저장소·빌드·설정·명령 발견 |
| SAFE_FOUNDATION | 확정된 의미만 다룸, 미결은 Port/fixture로 명시 | 경계·ID·확정 불변조건 Domain 시험 |
| FEATURE | 해당 기능 금융/보안 계약이 검토됐고 실행 증거 준비 | CreatePayment DB/API·승인·정산·송금 |

NOT READY인 계약 초안이 있어도 그에 의존하지 않는 발견/기초 작업은 수행한다. 반대로 fixture로 우회한 기능을 운영 READY로 표시하지 않는다. 기존 확정 정책은 재승인받지 않고 기술 세부 중 금융 의미에 영향 있는 것만 질문한다.

## 2. Coordinator

기준선 hash·저장소 변경·작업 dependencies·requiredDecisions·허용 경로를 확인한다. tasks를 실저장소 구조에 맞춰 소규모 카드로 쪼개고 하나를 점유한다. 작업/질문 상태 파일은 Coordinator 한 명만 변경한다. Implementer·Verifier·Reviewer는 결과를 반환하며 스스로 DONE을 기록하지 않는다.

실제 환경/명령/코드 변경이 없는 작업에 성공 test 증거를 꾸미지 않는다. 정책 질문과 OS/도구 권한 승인은 다른 절차다. 사용자의 개발 진행 요청은 기존 파일 파괴나 운영 금융 호출 승인이 아니다.

## 3. 상태

BACKLOG → READY → RUNNING → VERIFYING → REVIEW → DONE.

- WAITING_DECISION: 사용자 정책/명세 선택이 필요. 관련 카드만 대기.
- ENVIRONMENT_BLOCKED: 실행환경·의존성·DB/권한이 없어 검사할 수 없음.
- FAILED: 실제 검사가 실행되고 요구 결과를 만족하지 못함.
- PARTIAL: 기초 결과만 완료, 해당 기능 DoD/통합 Gate 미완료.

각 작업의 기록 키는 §12(D-PROC-002)의 고정 키를 따른다. 수용 기준·검증 명령·보고서·fingerprint는 명세와 증거 파일에 두고 tasks.json에는 위치만 남긴다. 빈 명령/허용 경로는 해당 실행이 미설정임을 뜻한다. discovery 카드의 최초 조사만 예외로 프로젝트 루트 읽기를 허용하며 쓰기는 별도 하네스 로그/문서 경로로 제한한다.

## 4. 질문 규칙

1. 현재 사용자 정책/설계에 이미 답이 있는지 먼저 확인한다.
2. 구현 관례/Mapper명/보조 index처럼 가역적 세부는 스스로 결정한다. 식별키 collation·금액 precision·실패 의미는 가역적 이름 선택이 아니다.
3. ID, 필요한 결정, 권장안, 1~2개 대안, 영향, 막히는 taskIds, 계속할 taskIds를 질문한다.
4. questions.json 상태 PROPOSED→ASKED→ANSWERED→APPLIED. 권장안 사전 선택·시간 경과·서브에이전트 의견은 사용자 답변이 아니다.
5. 답변 원문/시각·범위·근거를 기록하고 DECISIONS 기록으로 반영한다. 실제 메시지 시각을 확인할 수 없으면 불명으로 남긴다. 한국 시간 기준으로 표시한다.
6. 질문은 필요한 시점에 최대 3개 정도로 묶는다. 프로젝트 초기부터 전체 정책 목록을 일괄 답변 요구하지 않는다.
7. 답변이 들어오면 의존 명세/fixture/테스트 기대값을 갱신하고 impact를 검토한다. 정책을 바꿔 과거 금융 사실을 다시 생성하지 않는다.

후속 개발 질문 예: 주문번호의 대소문자 동일성, 지원 통화/금액 정밀도, CreatePayment의 원 응답과 현재 상태 표현, 키 보존. 첫 질문들은 `questions.json`에 있지만 해당 작업이 필요할 때만 제출한다.

## 5. 구현·검증·검토 루프

1. 독립 가능한 READY 카드 하나 점유. 명세와 성공/실패·경합 기대값 확인.
2. RED는 의도한 업무 규칙 위반인지 확인. dependency 누락/컴파일 실패는 업무 RED 증거가 아니다.
3. 최소 구현, 관련 단위→Application→실제 DB/migration·경합·권한/장애 검증.
4. Verifier는 commands.json의 승인된 실제 commandId만 실행하고 보고서를 확인한다. shell 문자열/무제한 retry 실행 금지.
5. Reviewer는 별도 context로 정책/diff/테스트·보고서·소스 fingerprint를 검토한다. 재무 수치 기대값은 구현식의 복제만으로 만들지 않는다.
6. failure당 최대 3회 수정 후 원인·마지막 성공·다음 동작 기록. 명시한 시간/turn/비용 한계를 넘어 자동 확장하지 않는다.
7. 검증·리뷰 통과 후 Coordinator가 DONE. 변경 후에는 관련 증거를 재생성한다. 테스트 삭제/skip·gate 완화는 별도 사용자 정책 변경 없이 금지.

## 6. 실행 명령 등록

commands.json은 의도적으로 빈 목록이다. BOOT에서 실제 Gradle task/JDK/DB/환경을 확인하고 argv 형태의 명령을 등록한다. 각 명령에 workingDirectory·timeoutSeconds·환경 변수의 비밀 없는 참조·expectedReports·minTests·용도·부작용 범위를 둔다.

검증 도구가 .bat/.cmd를 Windows에서 실행할 때는 알려진 고정 명령만 셸에 전달하고 모델 출력을 조합하지 않는다. 파일/환경값은 실제 쉘의 escaping 규칙을 사용한다. runner를 구축한다면 output과 exit code·timeout을 구조적으로 수집하고 결과가 같은 source fingerprint인지 검사한다. timeout은 자식 프로세스 정리와 영향 검증을 포함하며 검증 성공으로 취급하지 않는다.

기존 저장소 검증 도구/CI를 재사용할 수 있다. 별도 자동 Runner 구현은 HARNESS-002 후속 카드이며 독립 파일/명령 allowlist·보고서 파서·revision 검사·제한·중단 시험을 통과해야 한다. CLI -p 출력의 '완료' 문장만 파싱해 DONE을 만들지 않는다.

## 7. 증거

commit SHA만으로 미커밋 변경을 식별할 수 없다. git base/head와 함께 검사 대상 파일의 SHA-256 manifest 또는 전체 코드 fingerprint를 기록한다. 미추적 테스트/소스도 포함한다. 빌드 산출물·비밀은 제외하되 fingerprint 제외가 검사에 영향 없음을 검토한다.

증거: taskId, specHash, codeFingerprint, timestamp, environment, argv/commandId, exitCode, timedOut, collectedReportPaths/hash, testsExecuted/failures/skips, scope, assertion summary, reviewer verdict.

테스트 결과 0건/누락 보고서/오래된 보고서/환경 오류는 PASS가 아니다. verifier는 새 보고서의 run 범위·현재 코드 대응을 확인한다. N/A는 이유와 후속 Gate를 남긴다. read-only reviewer는 실제 실행자의 증거를 읽으며 검사 실행을 한 것처럼 주장하지 않는다.

## 8. 재개

세션 종료 전 작업별 nextResumeAction을 기록한다(progress.json은 2026-11-20부터 쓰지 않는다 — 원본은 harness/archive, D-PROC-002). 새로운 세션은 명세/코드/질문/검증 기록을 대조한다. 이미 실행한 가상 금융 요청은 Simulator의 원 ID 결과를 조회한다. 하네스 재시작을 기관 재전송 이유로 쓰지 않는다.

동일 작업을 여러 세션이 점유하면 하나만 owner로 유지하고 다른 세션은 변경하지 않는다. 구현 동시 작업은 독립 worktree/파일 범위와 공통 migration 단일 소유가 준비된 후 허용한다. 통합한 정확한 source fingerprint에서 다시 검증한다.

## 9. 적용 예

CP-DOMAIN-001: 양수·불변 금액, REQUESTED 점유, 기존 상태별 새 시도/재사용의 확정 의미를 시험. 통화 운영 지원과 DB key collation 미결이 있어도 명시 fixture/Port로 진행 가능.

CP-DB-001: 실제 control table UNIQUE/잠금/금액·key 동일성·request result·Outbox의 원자성. 키/금액/이벤트 계약 질문 답변 및 schema review 전 실행 보류.

AUTH-001: 소켓 Simulator와 안전 재시도/UNKNOWN. CreatePayment 관련 작업만 끝나도 시작에 필요한 Simulator/기관 규약을 먼저 고정해야 한다. 나중 정산주기 미결 때문에 AUTH가 자동 차단되지는 않는다.

FINANCE: 실제 운영 요율/한도 대신 DEMO DATA 사용 가능. 선정 fixture와 일관된 독립 기대값을 기록한다. 실거래 endpoint/운영값 활성화는 별도 Gate다.

## 10. 원칙별 검증과 기술 착수

TECHNOLOGY-AND-PRINCIPLES.md가 필수 기술/8대 원칙 기준이다. TECH-001에서 실제 버전·빌드 조합을 검증한다. 기능마다 8대 원칙을 ASSESSED 상태로 분류해 적용 증거 또는 N/A 이유·후속 Gate를 기록한다. 기록 위치는 그 기능 명세의 "원칙별 증거" 절이다(2026-11-20 이전 카드의 principleEvidence는 harness/archive에 있다 — D-PROC-002). Reviewer는 이 절을 증거 파일과 대조한다. 미평가 원칙을 묵시적 통과로 보지 않는다. 아직 Kafka/Redis가 없다는 이유로 독립 초기 기능을 막지 않되 운영 도입 단계의 전달/재처리/장애 시험은 생략하지 않는다.

## 11. 게이트 종류와 사용 시점 (D-PROC-001, PROC-GATE-001)

| 게이트 | 명령 | 언제 | 무엇을 |
|---|---|---|---|
| SCOPED | `verify-gate.py <TASK> --scoped [--base <rev>] --expect-fingerprint <fp>` (`VERIFY-GATE-SCOPED`) | 기능 안의 배치마다 | 바뀐 경로로 계산한 단위의 모듈 시험 전체 + HARNESS-SELFTEST |
| FULL | `verify-gate.py <TASK> --expect-fingerprint <fp>` (`VERIFY-GATE`) | 기능 완료(DONE) 전 1회, SCOPED가 FULL_GATE_REQUIRED를 낼 때 | 전체 check(모든 모듈·프론트·build-logic) |

- **실행 단위는 사람이 고르지 않는다.** `harness/commands.json`의 `scopedGate.pathRules`가 경로 → 단위를 정한다(위에서부터 첫 일치). 모듈 사이 입력도 규칙에 들어 있다. 예: 관리자 화면 → 관리자 화면 + 관리자 Kotlin(번들 검사), master 계약 golden → master + 관리자, simulator 소스 → simulator + payment(코드 노출 검사), 앱 설정·새 패키지 → master(레거시 검사).
- **분류할 수 없는 경로가 하나라도 있으면** 빌드 없이 `FULL_GATE_REQUIRED`(exit 4)다. libs·build-logic·gradle·docker·루트 빌드 파일·앱 build.gradle.kts·하네스 스크립트·commands.json이 여기에 든다. 검증 성공이 아니다.
- **단위는 그 모듈 시험 전체다.** 시험을 고르거나 줄이지 않는다. 단위별 하한(`minTests`)은 실제 시험 수이며, 낮추려면 사용자 결정이 필요하다. 캐시·skip·0건·오래된 보고서 거부 규칙은 FULL과 같다.
- **금융 핵심 검증은 FULL에서 빠지지 않는다.** 기능 DONE은 FULL PASS가 있어야 한다. SCOPED PASS만으로 DONE을 기록하지 않는다.
- **작은 작업은 묶어서 FULL 한 번(D-PROC-007).** 시험 전용·문서·하네스·화면 정리 같은 작은 작업은 각자 독립 리뷰 + SCOPED PASS 뒤 커밋하고 IN_PROGRESS("FULL 대기")로 둔다. 다음 FULL PASS 한 번(묶인 커밋을 모두 포함한 fingerprint)으로 함께 DONE을 기록한다. SCOPED가 `FULL_GATE_REQUIRED`를 내는 변경은 묶지 않고 바로 FULL을 돌린다. 묶음 FULL이 FAIL이면 묶인 작업 모두 원인 확인 전까지 DONE이 아니다.
- 문서·기록만 바뀌면 단위가 0개라 HARNESS-SELFTEST만 돈다. 이때 SCOPED 증거의 `buildFingerprint`(문서·기록·로컬 파일을 뺀 빌드 입력 hash)가 직전 FULL PASS 증거의 값과 같으면, 그 FULL PASS의 **시험 증거**는 그대로 쓴다. 명세 파일(hash)이 바뀌었으면 Reviewer가 바뀐 명세와 구현·시험을 다시 대조한 뒤에만 DONE으로 본다. 이 판단은 도구가 아니라 Coordinator·Reviewer가 증거 두 개를 비교해 한다.
- `--base`(기본 HEAD = 작업 트리의 미커밋 변경). **변경 경로가 0건이면 PASS가 아니라 FAIL이다.** 이미 커밋한 배치를 검사하려면 직전 게이트 증거의 `headRevision`(그 배치들 앞 커밋)을 준다. base는 HEAD의 조상이어야 하고(아니면 FAIL), 증거 `scope.baseRevision`에 남으므로 Reviewer가 직전 증거와 이어지는지 확인한다. 권장 순서는 지금처럼 "게이트 → 커밋"이라 대부분 HEAD 그대로 쓴다. 진행 기록(tasks/questions.json)만 바뀐 경우도 FAIL이다. 문서만 바뀐 범위는 `scope.classification = DOCS_ONLY`로 표시되며, 시험을 돌린 배치 증거로 쓰지 않는다(빌드 입력이 바뀐 배치는 `BUILD`).
- 증거: `build/harness-evidence/<TASK>-<UTC>.json`의 `mode`(FULL/SCOPED), `scope`(경로별 분류·단위·FULL 사유), `buildFingerprint`.

## 12. 기록 규칙 (D-PROC-002, PROC-RECORD-001)

- **한 사실은 한 곳에.** 사용자 결정 전문은 `harness/questions.json`(답변 원문)과 `docs/DECISIONS.md`(결정 행)에만 둔다. 명세·tasks.json·커밋 메시지는 결정 ID로 참조한다.
- **tasks.json(schemaVersion 2)** task 키: `id, title, status, phase, owner, dependsOn, spec, allowedWritePaths, maxRepairAttempts, lastGate, openItems, nextResumeAction, detail`. 다른 키를 추가하지 않는다. task 하나는 100줄 이하, 파일은 100KB 이하를 유지한다. 기록을 바꿀 때마다 `python3 harness/scripts/check-records.py harness/tasks.json`(`CHECK-RECORDS`)을 실행해 exit 0을 확인한다.
  - 인수 기준·8대 원칙 증거는 명세에 둔다. 명세가 아직 없는 task는 인수 기준을 `openItems`에 `[인수 기준]`으로 둔다.
  - 100줄을 넘으면 `openItems`를 잘라내지 말고 명세로 옮긴다(archive에 없는 2026-11-20 이후 항목은 잘라내면 사라진다).
  - 2026-11-20 이후 DONE으로 바꾸는 task는 `lastGate`에 FULL PASS 증거 경로(`verdict: PASS`)를 반드시 남긴다.
  - `lastGate`: 마지막 게이트의 `{tag, verdict, evidence}`만. 단계별 시간·시험 수·로그는 증거 파일에 있다.
  - `openItems`: 남은 일만 짧게. 끝난 일은 지운다(이력은 git).
  - `nextResumeAction`: 모든 task에 둔다. 다음 세션이 이것만 읽고 이어갈 수 있게 쓴다.
- **과거 기록은 지우지 않는다.** 2026-11-20 이전 tasks.json(schemaVersion 1)과 progress.json은 `harness/archive/`에 원문 그대로 있다(파일 hash는 tasks.json `archive`). 수정하지 않는다.
  - tasks.json이 100KB에 닿으면 DONE task를 `harness/archive/tasks-done-<날짜>.json`으로 원문 그대로 옮기고 hash를 `archive`에 더한다(첫 이동 2026-11-22, 53개). 옮긴 파일도 수정하지 않는다.
- **명세**: 본문은 현재 유효한 규칙만 쓴다. 개정 이력은 파일 끝의 짧은 표나 git으로 둔다.
- **커밋 메시지**: 첫 줄 한 줄 요약 + task ID. 본문은 바뀐 것 몇 줄과 결정 ID·증거 경로만 쓴다. 결정 전문·시험 수·단계별 시간을 반복하지 않는다.

## 13. 규칙 추적 검사 RULE-TRACE (경고 모드, RULE-TRACE-001)

- 명령: `python3 -B harness/scripts/rule-trace.py [--json <path>]` (저장소 루트 기준, 표준 라이브러리만·shell 없음). 설계: RULE-GOV-001 §2.3.
- 검사: ① 대장 형식(`docs/rules/{BIZ,PAY,CAN,FIN,WH,ADM}.md` 규칙 표 행, `## 진단` 이후 제외 — 열 7개·ID 중복·도메인 접두·상태·출처·대체 대상) ② 근거·미결 상태의 D-ID(`docs/DECISIONS.md`)·Q-ID(`harness/questions.json`) 존재, 파싱 불가 표기는 따로 ③ `harness/tasks.json` 카드 spec·openItems의 확정 아닌 BR-ID ④ 확정 규칙 검증 열이 `없음`이거나 적힌 시험 파일(`::` 앞 이름)을 시험 경로에서 찾을 수 없음(도메인별 건수) ⑤ 대체됨 BR-ID가 시험 소스에 남은 곳.
- **경고 모드**(D-GOV-006 ③): 대장 확정 전이라 항상 exit 0이고 게이트 단계에 넣지 않는다. exit 2는 대장·입력 파일을 못 읽거나 규칙 표를 파싱할 수 없을 때(규칙 행 0건, 표준 머리글 밖 규칙 행)와 사용법 오류.
- `--strict-domains BIZ,…`: 지정 도메인에 ④ 위반이 있으면 exit 1. 기본 비활성이며 **사용자 확인 후** 시험에 BR-ID 연결이 끝난 도메인부터 켠다.
- 현재 strict 판정 = ④(검증 열 시험 파일 존재)만. BR-ID 표기까지 실패로 볼지는 strict 활성 시 사용자 확인.
