# ADMIN-UI-001 관리자 콘솔 shadcn/ui 적용 명세 (rev 0.4)

작성 2026-11-02 KST, branch `feat/foundation-setup`. 근거: **D-ADMIN-UI-001**(사용자 지시 — shadcn/ui), D-ADMIN-008·D-FE-001(같은 출처 정적 번들), 관리자 CSP(`apps/internal-admin/src/main/kotlin/com/paycore/admin/security/SecurityConfig.kt`).
Implementer 작성. 자기 완료 판정 아님(Verifier·Reviewer 별도).

rev 0.2(같은 날): 사용자 참고 화면(v0 'PG 백오피스', https://payledger-sooty.vercel.app/ 캡처)·브랜드 색 한 가지·"직관적이여야해" 요구(Coordinator 전달)를 반영했다. 참고 사이트의 대시보드·거래내역·정산·가맹점 화면과 가짜 데이터는 만들지 않는다(백엔드 없음 — 범위 밖).

rev 0.3(같은 날): 리뷰 REVIEW_FAIL 반영 — M-1(build-logic 시험이 등록 명령에서 실행되지 않음 → 루트 `check` 의존 + harness 등록, §8.1), m-2(통화 미상 금액), m-3(감사 결과·행위 전체 한국어·색), m-4(OneTimeCode 배경 `inert`), n-1~n-6, Coordinator 관찰(감사 화면 행위 code·요약 칸 넘침·행위자 줄바꿈, 메뉴 배경 높이).

rev 0.4(2026-11-03): **D-ADMIN-UI-003**(Q-ADMIN-UI-2 사용자 답변 "디자인·메뉴 구조만 적용") — 새 참고 시안 'pg workspace'(https://pg-ops-workspace-kim.packedchili2.chatgpt.site, 샘플 데이터 시안)의 레이아웃·색·사이드바 구역·상단 경로·탭·카드 모양을 적용하고 기존 6개 화면을 업무 메뉴의 탭으로 재배치했다. §11이 rev 0.3의 §1 `AppLayout` 행·§4 색 값을 대체한다. 서버 기능이 없는 메뉴·탭은 숨긴다(별도 작업). 권한·API·payload·CSP 불변.

## 1. 범위

| 대상 | 변경 |
|---|---|
| `AppLayout` | 로그인 뒤: 왼쪽 메뉴(`aside` — 배경은 레이아웃 전체 높이, 내용만 `sticky top-0`; 본문 열 `min-w-0`로 넓은 표가 쪽 밖으로 넘치지 않음. `aside` 안 `nav`, 로고 "PG" + "PG 백오피스", 역할별 메뉴 그대로 `<button aria-pressed>` + 아이콘, 선택 항목 = 브랜드 색 10% 배경 + 브랜드 글자색) · 상단 바(banner: `PayCore 관리자`, 표시명·`역할: 한국어 역할명`·로그아웃) · 본문(`main`, 옅은 회색 배경). 로그인 전: 상단 바 + 가운데 카드 |
| 로그인·계정 등록 | 가운데 Card, 로고, 단계별 안내 문구(예: "2단계 — OTP 앱의 6자리 코드를 입력하세요."), 입력 아래 도움말, 제출 중 표시 |
| 계정·승인함·내 요청·감사·결제 보정 취소·대사 발견 | 쪽 머리(제목 + 한 줄 설명) + 테두리 둥근 Table(옅은 머리 행, ID는 mono) + 한국어 Badge + 빈 목록 안내 |
| 계정 행 작업 | native `<details>`("작업")로 접음: 2인 승인 요청(주 동작) → 역할 재활성 → 구분선 아래 위험 작업(정지·회수, 빨강) |
| 결제 보정 취소 | 단계 표시(1 대상 조회 → 2 요청 작성 → 3 승인 대기, 현재 단계 `aria-current="step"`), 금액 천 단위 + `원`, 입력 형식 도움말, 요청 버튼이 막힌 이유 표시 |
| `OwnerApprovalDetail` | Card region, 상태 Badge, 비교 표(달라진 행 강조), 경고는 주의 Alert |
| `OneTimeCode` | `document.body`에 portal로 붙는 overlay(배경 + 카드, `role="dialog" aria-modal="true"`), 열려 있는 동안 overlay 밖 body 자식에 `inert`(초점·클릭·보조 기술 차단 — 리뷰 m-4, 닫으면 붙인 것만 제거), 닫기 버튼에 처음 focus(`autoFocus`) |
| 감사 | 행위 = 한국어 + 작은 mono 원래 code, 행위자 줄바꿈 없음(원래 종류는 `title`), 요약 칸은 너비 제한 + 줄바꿈(긴 토큰도 끊음, 원문 `title`) — 표가 쪽 밖으로 넘치지 않고 필요하면 표 안에서 가로 스크롤 |
| `ErrorScreen` | 오류 Alert + 버튼 |

**범위 밖**: 새 화면·새 API·메뉴 규칙·권한 판정·오류 문구(`errors/messages.ts`)·입력 검증 규칙·요청 값 변경, 테마 전환 버튼(선택을 저장소에 남겨야 해 `storageIsEmpty` 불변식과 충돌 — OS `prefers-color-scheme`만 따름), shadcn CLI·`components.json`, Radix 컴포넌트, 웹 글꼴.

## 2. 불변 조건

- API 호출 순서·인자, `pagesFor`, 첫 화면 선택, 세션 종료 시 로그인 복귀, 화면 상태 메모리 보관은 그대로.
- 접근성 이름 유지: 모든 label 문구(`로그인 이름`·`비밀번호`·`인증 코드(6자리)` 등), 버튼 이름(`다음`·`로그인`·`로그아웃`·메뉴 `LABELS`·`OPERATOR 회수` 등), 표 행 `aria-label`(원래 code), region·표 이름, role `alert`/`status`/`dialog` 위치·개수.
- label은 입력 요소를 감싼다(`<label><span>문구</span><input/></label>`). 도움말은 label 밖(`aria-describedby`) — label 이름이 바뀌지 않는다.
- 시험이 읽는 문장은 한 요소 안에 그대로 둔다(`반환 가능액: 7000 KRW`, `MISSING_DECISION: 2`, 대사 마지막 실행 문장, `revision 3 3` 비교 행 등). 읽기 쉬운 값(한국어·천 단위)은 옆 요소에 **추가**한다.

## 3. UX 규칙("직관적")

1. **code → 한국어**: `src/lib/labels.ts` 한 곳(`CODE_LABELS`, `AUDIT_ACTION_LABELS`, `labelOf`). 출처(서버 코드에서 직접 모음 — `labels.test.ts`가 집합 전부와 "서버에 없는 이름 없음"을 함께 확인):
   - internal-admin domain enum: AccountStatus·AdminRole·ApprovalStatus·ApprovalKind·OperatorCancelPurposeKind·ReconciliationFindingKind·AuditActorKind.
   - 감사 resultCode: SUCCESS·OK·DENIED·REFUSED·FORBIDDEN·REJECTED·EXPIRED·INVALIDATED·OWNER_REJECTED·WITHDRAWN·EXECUTING·FAILED·FAILURE·UNKNOWN·FLAGGED·HALTED·LOCKED·COUNTED·NOT_COUNTED(+ OPS_RECON_FINDING의 결과는 대사 발견 종류). 조사가 필요한 결과는 성공·실패로 보이지 않게: UNKNOWN "결과 확인 중"(주황), FLAGGED "무결성 경보(조사 필요)"(빨강), HALTED "재전송 중단(조사 필요)"(주황), REFUSED "거부"(빨강).
   - 감사 행위(action) 32개: AdminLoginService·AuthenticationFailureService·AdminAccountCommandService·EnrollmentService·OneShotCommandService·ApprovalService·OwnerServiceApprovalService·OwnerOutcomeApplier·OperatorCancelJustificationService·OperatorDecisionReconciliationService·UnmaskService·AdminAuditQueryService·AdminSecurityEventService의 상수.
   - payment-api PaymentStatus 중 화면에 오는 값(REQUESTED·AUTHORIZING·AUTHORIZED "승인 완료"; UNKNOWN·FAILED·EXPIRED는 위와 같은 이름).
   - 같은 code는 도메인이 달라도 같은 뜻으로만 쓰인다. 모르는 code는 그대로 보인다. Badge는 한국어 + `title`에 원래 code, 종류·행위 열은 한국어 + 작은 mono 원래 code.
2. **쪽 머리**: 제목 + 무엇을 하는 화면인지 한 줄(예: 승인함 "다른 관리자가 요청한 작업을 검토하고, 인증 코드로 승인하거나 거절합니다.").
3. **빈 목록**: 표 안에 안내 행(예: "승인 대기 중인 요청이 없습니다.", "조건에 맞는 감사 기록이 없습니다.").
4. **동작 위계**: 영역마다 주 동작 하나만 브랜드 색(Button default), 보조는 outline/ghost, 파괴 동작(정지·회수·거절)은 빨강이고 구분선·간격으로 떨어뜨린다.
5. **상태 색은 의미만**: 성공·실행됨·활성 = 초록, 대기·진행·결과 확인 중 = 주황, 거절·실패·정지 = 빨강, 만료·철회·무효 = 회색(`src/lib/statusTone.ts`). 브랜드 색은 상태에 쓰지 않는다.
6. **양식**: label 위, 도움말·오류는 칸 바로 아래, 버튼이 막혔으면 이유를 아래에 쓴다, 제출 중이면 버튼에 회전 아이콘(`aria-busy`). 진행 표시는 버튼을 새로 막지 않는다(기존 동작 유지 — 로그인은 원래 막음).
7. **시각**: ISO → KST `YYYY-MM-DD HH:mm:ss`(`src/lib/format.ts`, 원문 ISO는 `title`/`dateTime`). 감사 시각·대사 발견 시각·승인 상세 다음 확인/실행 기한·대사 마지막 성공 시각에 적용. 시험이 원문을 읽는 곳은 없었다.
8. **금액**: 최소 단위 → 천 단위 구분 + `원`(KRW만). 다른 통화는 `1,050 USD(최소 단위)`, 통화를 모르면(null) `7,000(통화 미상)` — 원으로 짐작하지 않는다(리뷰 m-2). 서버로 보내는 값은 그대로.
9. **입력 규칙 불변**(리뷰 n-6): `maxLength`(근거 200·사유 500·인증 코드 6)·`pattern`·`inputMode`·`required`·`autoComplete`는 HEAD와 화면별로 같다(비교 확인). 새로 붙인 것은 `aria-invalid`·`aria-describedby`(접근성 표시)뿐.

## 4. 디자인 토큰(브랜드 색 한 곳)

- `src/styles.css` 맨 위 `:root { --brand: #2563eb; --brand-dark: #3b82f6; --brand-foreground: #ffffff }` — **이 값만 바꾸면** 로고·주 버튼·선택 메뉴·focus ring·링크·단계 표시가 함께 바뀐다(`--primary`·`--ring`이 `--brand`를 가리킴, 다크 모드는 `--brand-dark`).
- 나머지는 shadcn neutral(라이트 배경 흰색·muted `oklch(0.97 0 0)`·border `oklch(0.922 0 0)`, 다크 배경 `oklch(0.145 0 0)`), `--radius: 0.625rem`, 상태 색 `--success`·`--warning`·`--destructive`.
- 글꼴: 시스템 글꼴 스택(`system-ui, -apple-system, "Segoe UI", "Apple SD Gothic Neo", "Malgun Gothic"`). 참고 화면의 Geist·Noto Sans KR은 번들에 넣지 않았다(한국어 subset 크기·라이선스·고정 버전 검토가 필요 — 이번 작업에서 추가하지 않음).

## 5. 파일 배치

```
frontend/src/
  lib/utils.ts          cn() = twMerge(clsx(...))
  lib/labels.ts         code → 한국어(한 곳)
  lib/format.ts         KST 시각, 천 단위 금액
  lib/statusTone.ts     상태 → Badge 색
  components/ui/        shadcn 소스 복사·조정: button input textarea native-select label card table badge alert
  components/icons.ts   lucide 아이콘 개별 모듈 import(+ lucide-icons.d.ts 타입)
  components/{DataBits,Notice,PageHeader,Logo}.tsx  화면 공용 조각(Badge·빈 행·Field·Spinner·Alert 묶음)
  styles.css            @import "tailwindcss" + 토큰
```
경로 alias(`@/…`)는 쓰지 않는다(상대 경로).

## 6. CSP

관리자 CSP `default-src 'self'; script-src 'self'; style-src 'self'; …`(`'unsafe-inline'`·nonce 없음)는 바꾸지 않는다.

| 위험 | 대응 |
|---|---|
| 런타임 `<style>` 주입(react-remove-scroll — Radix Dialog/Select/DropdownMenu/Popover, CSS-in-JS) | Radix 미사용. Dialog = 일반 요소 overlay, Select = shadcn Native Select, Label = native, 행 작업 메뉴 = native `<details>` |
| inline `style` 속성 | 쓰지 않음(Tailwind class만) — 시험으로 확인 |
| 외부 CDN·원격 글꼴 | 없음 — 시험으로 `styles.css` 확인 |
| Tailwind 산출물 | `@tailwindcss/vite`가 빌드 때 정적 `assets/*.css` → `index.html`은 `<link rel="stylesheet">`만(빌드 산출 확인). 개발 서버(vite dev)는 `<style>`을 쓰지만 운영 경로 아님 |
| 아이콘 | lucide — React가 만드는 inline SVG 요소(스타일 주입 없음) |

shadcn 원본과 다른 점: Button·Badge `asChild` 없음, Alert 기본 `role="alert"` 없음(호출자가 `alert`/`status`/없음을 정함), Card `as="section"` 허용, Badge에 `success`·`warning` tone.

## 7. 의존성(정확한 버전, `lockfile-policy.json` before=2026-10-10T23:59:59Z 이전 게시본)

| 패키지 | 버전 | 구분 | 게시일 |
|---|---|---|---|
| tailwindcss | 4.3.3 | dev | 2026-07-16 |
| @tailwindcss/vite | 4.3.3 | dev(peer vite ^8 허용) | 2026-07-16 |
| class-variance-authority | 0.7.1 | runtime | 2024-11-26 |
| clsx | 2.1.1 | runtime | 2024-04-23 |
| tailwind-merge | 3.7.0 | runtime | 2026-09-12 |
| lucide-react | 1.55.0 | runtime | 2026-10-10 06:12Z |

- lockfile은 `./gradlew :apps:internal-admin:frontendLockfileRegenerate`(2단계 절차, before 그대로)로 다시 만들었다. 기존 패키지 버전 변화 없음(hoisting만 바뀜: lightningcss 1.33.0·magic-string 1.4.3이 vite·vitest 아래로, 최상위는 Tailwind가 고정한 lightningcss 1.32.0·magic-string 0.30.21).
- Tailwind native 부분(@tailwindcss/oxide, lightningcss)은 플랫폼별 optionalDependencies — install script 불필요(`ignore-scripts=true` 유지).
- `@tailwindcss/oxide-wasm32-wasi`(wasm32 전용 optional, linux-x64에는 설치되지 않음)가 `bundleDependencies` 6개를 갖고, lockfile의 그 항목(`inBundle: true`)에는 `resolved`·`integrity`가 없다. 처음에는 `frontendLockfileCheck`가 12건 위반으로 실패했고, **D-ADMIN-UI-002**(§9)에 따라 검사를 좁게 보완했다.
- CVE-SCAN은 Coordinator VERIFY-GATE에서 확인.
- **lucide 개별 경로 import 위험(리뷰 n-4)**: `src/components/icons.ts`는 `lucide-react/dist/esm/icons/<name>.mjs`를 직접 import한다(진입점은 아이콘 4천여 개를 시험 파일마다 적재해 Vitest 시간 여유를 깎았다). 이 경로는 패키지의 공개 `exports`가 아니라 배포 파일 구조다(1.55.0에는 `exports` 필드가 없어 허용됨). lucide-react를 올릴 때 경로·파일 이름이 바뀌면 tsc·빌드가 실패하므로 조용히 깨지지는 않는다 — 올릴 때 `icons.ts`·`lucide-icons.d.ts`를 함께 확인한다.

## 8. 시험

- 기존 66건 기대값 그대로(선택자 변경 없음). 기존 시험 파일 중 수정한 것은 `src/layout/AppLayout.test.tsx`·`src/components/OneTimeCode.test.tsx`·`src/screens/AuditScreen.test.tsx`(모두 사례 **추가**만, 기존 사례 그대로).
- 신규(19건 → 합계 85):
  - `layout/AppLayout.test.tsx` +1: 로그인 뒤 메뉴(`navigation`)는 왼쪽 영역(`complementary`) 안, 표시명·로그아웃은 banner 안, 첫 화면 `aria-pressed`.
  - `components/OneTimeCode.test.tsx` +1(리뷰 m-4): 열려 있는 동안 뒤 화면은 `inert` 안, dialog는 밖, 처음 focus는 닫기 버튼, 닫으면 `inert` 0개.
  - `screens/AuditScreen.test.tsx` +1: 행위 한국어 + 원래 code, 시각 KST + ISO `title`, 결과 Badge 한국어 + code `title`.
  - `test/noRuntimeStyles.test.tsx` 6: 각 화면·로그인 뒤 App을 그린 뒤 `<style>`·`[style]`·`link`/`script[src]` 0개, `styles.css`는 `tailwindcss`만 `@import`·`url(`·`@font-face`·원격 URL 없음.
  - `lib/utils.test.ts` 1(`cn`), `lib/labels.test.ts` 9(한국어 이름, 서버 enum·상수 전부 포함 + 서버에 없는 이름 없음, 감사 행위 32개 전부, 모르는 code 그대로, 상태 색, 조사 필요 결과 색(m-3), KST, 금액, 통화 미상(m-2)). 두 파일은 `@vitest-environment node`.
- `vitest.config.ts`에 `maxWorkers: '50%'` 추가: 코어 수만큼 jsdom 파일을 동시에 돌리면 첫 시험이 5초 timeout·`findBy` 1초 대기에 걸리는 일이 약 5회 중 1회 있었다(8코어 WSL 실측). timeout·기대값은 바꾸지 않았고, worker 4개(설정·명령 인자 포함) 12회 연속 통과·가장 긴 시험 약 2초·전체 시간 ~20초(이전과 비슷).
- 빌드 확인: `frontendBuild` 산출 `index.html`에 inline `<style>`·inline script 없음, CSS는 `assets/index-*.css` 한 파일, JS 번들에 `createElement("style")`·`insertRule`·`adoptedStyleSheets` 없음. 실제 브라우저 CSP 위반 0 확인은 Demo 재기동 뒤 Coordinator 단계.

### 8.1 build-logic 시험의 등록(리뷰 M-1)

- 문제: 루트 `./gradlew check`는 included build(`build-logic`) task를 고르지 않아 `FrontendLockfileCheckSpec`이 GRADLE-CHECK에서 실행되지 않았다.
- 조치: 루트 `build.gradle.kts`에 `tasks.register("check") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }`(루트에는 원래 `check`가 없었다 — 하위 프로젝트 check는 task selector로 그대로 실행; `./gradlew -m check`로 `:build-logic:test`와 각 `:apps:*:test`·`:libs:test-support:test`가 함께 그래프에 있음을 확인).
- `harness/commands.json`: GRADLE-CHECK `expectedReports`·`archiveGlobs`에 `build-logic/build/test-results/test/TEST-*.xml`, `outputMustMatch`에 `^> Task :build-logic:test$`, TEST-SUMMARY argv에 `build-logic/build/test-results/test`. `minTests`(3313)·HARNESS-SELFTEST 하한은 바꾸지 않았다(Coordinator가 게이트 뒤 정함 — 합계는 build-logic 시험 수만큼 늘어난다).
- `harness/tests/test_module_registry.py`: settings의 `includeBuild("x")` 중 `src/test`가 있는 build를 같은 등록 규칙(보고서 기대·보관·task 줄·TEST-SUMMARY·루트 check 의존)으로 검사하도록 **기존 시험 3개를 넓혔다**(시험 메서드 수 불변 → HARNESS-SELFTEST 하한 불변). 수정 전 RED 3건 확인.

## 9. 결정 — Q-ADMIN-UI-1 → D-ADMIN-UI-002(사용자 답변: A "검사를 좁게 보완")

**Q-ADMIN-UI-1 — Tailwind v4 wasm optional 패키지의 bundled 항목과 lockfile 검사**
- 채택 A: `build-logic/src/main/kotlin/com/paycore/buildlogic/frontend/FrontendLockfileCheckTask.kt`의 `bundledEntryViolations`.
  `resolved`·`integrity`가 없는 항목은 계속 거부한다. 예외는 `inBundle: true` 항목뿐이고, 가장 가까운 부모 패키지 항목(경로의 마지막 `/node_modules/` 앞)이 (a) 그 이름을 `bundleDependencies`(또는 `bundledDependencies`)에 선언하고 (b) registry(`https://registry.npmjs.org/`) `resolved`와 `sha512-` `integrity`를 가질 때만 허용한다. 묶인 파일은 부모 tarball 해시로 보호된다.
  위반으로 남는 경우: 부모 항목 없음, 최상위 `inBundle`(부모 패키지 없음), 부모가 이름을 선언하지 않음(`bundleDependencies: true` 같은 boolean도 선언으로 보지 않음), 부모에 sha512 integrity 없음, 부모 `resolved`가 registry 밖, 부모도 묶인 항목(자기 tarball 해시 없음), **`inBundle` 항목이 자기 `resolved`·`integrity`를 가짐**(npm이 쓰지 않는 모양 — fail closed, 리뷰 n-2).
- 시험: `build-logic/src/test/kotlin/com/paycore/buildlogic/frontend/FrontendLockfileCheckSpec.kt` 11건(허용 2, 기존 거부 유지 1, 새 거부 8). build-logic에 Kotest(catalog `kotest-runner-junit5`·`kotest-assertions-core`) 시험 의존과 `useJUnitPlatform()`을 추가했다(build-logic의 첫 시험).
- 기각 B: Tailwind v3.4.19로 내리기.

## 10. 8대 원칙 해당성

화면 표현만 바꾼다(rev 0.4도 같음 — 운영 홈의 수는 기존 읽기 조회 결과를 세기만 하고 쓰기·새 API 없음). Financial Consistency·Transaction Safety·Idempotency·Concurrency·Recoverability·Reconciliation: 해당 없음(API·서버 판정·요청 값 불변, 대사 화면에 해결·종료 버튼 없음 유지, 진행 표시가 버튼을 새로 막지 않음). Auditability: 감사 화면은 필터만 보냄 유지. Observability: 해당 없음. JPA·jOOQ·Kafka/Redis 변경 없음.

## 11. rev 0.4 — 'pg workspace' 정보 구조와 모양(D-ADMIN-UI-003)

참고 자료: Coordinator 캡처 `/tmp/ref-pgws/`(화면 PNG, `all.css` 원본 CSS, 메뉴·탭 목록). `all.css` 값을 Tailwind/shadcn 토큰으로 옮겼다(원본 CSS·이미지·글꼴은 번들에 넣지 않음).

### 11.1 메뉴 구조(`src/navigation.ts` — 표시만, 권한 판정은 서버)

화면 표시 규칙 `pagesFor`(역할 → 6개 화면)는 rev 0.3 그대로다. 메뉴는 그 역할이 볼 수 있는 탭이 하나라도 있을 때만 나온다(`menusFor`). 고정 순서:

| 사이드바 구역 | 메뉴 | 탭(기존 화면) | 탭 막대 |
|---|---|---|---|
| (없음) | 운영 홈 | — (새 홈, §11.3) | — |
| 내 업무 | 승인함 | 승인함(`approvals`) | 없음(카드만) |
| 내 업무 | 내 요청 | 내 요청(`mine`) | 없음(카드만) |
| PAYMENT OPERATIONS | 결제 관리 | 결제 보정 취소(`paymentOps`) | 있음 |
| PAYMENT OPERATIONS | 매입·대사 | 대사 발견(`reconciliation`) | 있음 |
| WORKSPACE | 계정·권한 | 계정 관리(`accounts`), 접속·감사 이력(`audit`) | 있음 |

- **승인함·내 요청은 메뉴를 따로 둔다**(한 메뉴 "승인·요청"의 탭으로 묶지 않음): 둘은 역할이 다른 사람이 매일 여는 받은함이라 한 번에 바로 가는 편이 직관적이고, 두 역할을 함께 가진 계정만 둘 다 본다. 화면이 하나라 탭 막대 없이 카드만 그린다.
- 역할별 결과(시험 `navigation.test.ts`·`App.test.tsx`가 정확히 확인):
  - SECURITY_ADMIN: 운영 홈 · 내 요청 · 결제 관리[결제 보정 취소] · 매입·대사[대사 발견] · 계정·권한[계정 관리, 접속·감사 이력]
  - APPROVER: 운영 홈 · 승인함 · 결제 관리 · 매입·대사 · 계정·권한[계정 관리, 접속·감사 이력]
  - OPERATOR: 운영 홈 · 내 요청 · 결제 관리 · 계정·권한[계정 관리, 접속·감사 이력]
  - VIEWER: 운영 홈 · 결제 관리 · 계정·권한[계정 관리, 접속·감사 이력]
  - UNMASK만: 운영 홈 · 계정·권한[접속·감사 이력]
- **숨긴 메뉴·탭**(서버 기능 없음 — 메뉴 개편 지도를 근거로 명세를 써서 별도 작업): 사업자·가맹점, 원천사·기준정보, 정산·지급, 리스크 관리, 통계·리포트, 운영·고객지원 메뉴 전체와, 결제 관리의 전체 거래·실패·취소요청·가상계좌·환불 등, 매입·대사의 매입 현황·거래대사·입금대사 등, 계정·권한의 역할·권한 탭. 시안의 "메뉴 개편 지도" 링크·"프로토타입" 표시·작업공간 선택 드롭다운도 넣지 않았다.

### 11.2 레이아웃·접근성

- `aside`(236px 흰 바탕, 오른쪽 선; md 이상 `sticky top-0 h-screen self-start` — 본문이 길어도 화면 높이 전체에 고정, 메뉴가 넘치면 메뉴 부분만 안에서 스크롤, 계정·로그아웃은 아래 고정. Coordinator 실제 브라우저 확인(긴 감사 이력 쪽에서 메뉴 배경·로그아웃이 스크롤로 사라짐) 반영): 로고(파란 둥근 사각형 "P") + `PayCore`, 콘솔 이름 상자(`관리자 콘솔 · 내부 운영` — 고정 문구, 환경 값 아님), `nav aria-label="메뉴"`(구역은 `role="group"` + `aria-label`, 보이는 구역 이름은 `aria-hidden`), 아래에 표시명·`역할: …`·로그아웃. 메뉴 항목은 `<button>`이고 선택 항목에 `aria-current="page"`(rev 0.3의 `aria-pressed` 대신 — 이동 메뉴의 표준 표시; 시험도 이 속성으로 바꿔 선택/비선택을 모두 확인).
- 상단 바(banner 76px): `nav aria-label="경로"`(`워크스페이스 / 메뉴`, 현재 항목 `aria-current="page"`)와 **메뉴 검색**(`role="search"`, `searchbox` "메뉴 검색"): 이 역할에 보이는 메뉴·탭 이름에서만 찾는다(공백·`·` 무시). Enter = 첫 결과로 이동, Esc = 지움, 결과 없으면 `role="status"` "찾는 메뉴가 없어요.". 검색어는 서버로 보내지 않고 저장소에 남기지 않는다. 거래·가맹점·TID 검색은 없다.
- 본문(`main` 최대 1500px): 쪽 머리 `WorkspaceHeader`(eyebrow = 구역 영문 이름, h1 = 메뉴 이름, 한 줄 설명). 쪽 머리 오른쪽 주 동작 버튼은 두지 않았다 — 기존 동작(계정 만들기·대상 조회 등)은 탭 안 양식에 그대로 있고 중복 버튼은 혼동을 준다.
- 메뉴 카드 `MenuCard`: 13px 모서리·옅은 선. 업무 메뉴는 카드 위 `role="tablist"`(이름 = 메뉴 이름), 탭 `role="tab"` `aria-selected`·`aria-controls`, 선택 탭만 Tab 순서(roving tabindex), ←/→/Home/End로 이동·선택, 선택 탭 = 파란 2px 밑줄·굵게. 패널 `role="tabpanel"` `aria-labelledby`. 기존 화면의 `PageHeader`(h2 — 이름 그대로)는 카드 제목 크기(15px)로 줄였다.
- 메뉴를 누르면 그 메뉴의 첫 탭. 보이지 않는 화면으로의 이동 요청은 운영 홈으로 돌린다(표시 규칙 — 서버는 따로 거부).
- 로그인 전 화면: 흰 상단 바(로고 + `PayCore 관리자`) + 가운데 카드(rev 0.3과 같은 구조).

### 11.3 운영 홈(`src/screens/HomeScreen.tsx`)

- 머리: eyebrow `OVERVIEW`, h1 `안녕하세요, <표시명>님`.
- 현황 카드는 **그 화면이 이미 부르는 조회**로만, 그 화면이 보이는 역할에게만 만든다(새 API·새 호출 권한 없음, 표본·가짜 숫자·차트 없음):

| 카드 | 보이는 역할 | 호출(기존) | 값 |
|---|---|---|---|
| 승인 대기 | APPROVER | `GET /api/approvals?view=pending` | 상태 PENDING 수 |
| 내 요청 대기 | SECURITY_ADMIN·OPERATOR | `GET /api/approvals?view=mine` | 내 요청 중 상태 PENDING 수 |
| 열린 대사 발견 | SECURITY_ADMIN·APPROVER | `GET /api/reconciliation/findings` | `openTotal`(서버 종류별 수 합) |

- 승인 목록은 서버 상한 200행(`ApprovalQueryService.LIMIT`)에서 잘리고 총계가 없다. 목록이 200행이면 "200건 이상"처럼 **"이상"**을 붙인다(정확한 수로 보이지 않게).
- 실패한 카드는 숫자 대신 고정 오류 문구(`ERROR_MESSAGES`)만, 세션 종료 응답은 로그인 화면으로(기존 규칙). 실패가 있으면 "지금 처리할 업무"는 "없어요"라고 하지 않는다.
- "지금 처리할 업무": 값이 1 이상인 카드만 행으로 보이고 누르면 해당 탭으로 간다. 모두 0이면 "지금 처리할 업무가 없어요.".
- "바로가기": 이 역할에 보이는 모든 탭(`<탭> 열기`). VIEWER·UNMASK는 숫자 카드·호출 없이 인사 + 바로가기만.
- 이 조회들은 서버에서 감사 기록 대상이 아니다(승인 목록·대사 발견 조회 서비스에 감사 기록 없음 — 확인함). APPROVER는 rev 0.3에서도 첫 화면(승인함)이 같은 `pending` 조회를 했다. SECURITY_ADMIN·OPERATOR의 `mine`, SECURITY_ADMIN·APPROVER의 대사 발견 조회는 이제 로그인 직후에도 한 번 일어난다(같은 권한 범위의 읽기).

### 11.4 색(§4 대체)

`src/styles.css`: `--brand #3182f6`(다크 `#4c95f7`), 쪽 바탕 `--canvas #f7f9fc`, 글자 `#202938`, 선 `--border #edf0f4`, 입력 선 `#e3e8ef`, 옅은 바탕 `--muted #f5f7fa`, 선택 메뉴 `--sidebar-accent #edf4ff` / `--sidebar-accent-foreground #1f6fd9`, `--radius 0.75rem`. 시안의 흐린 글자(#8b95a1·#869dbb·#a5afbb·#687485)는 흰 바탕에서 WCAG AA 4.5:1 미만이라 같은 색조로 진하게 했다(`--muted-foreground #6b7684`, `--eyebrow #5f7696`, `--sidebar-label #6f7b8c`, `--sidebar-foreground #5f6b7c`, 선택 글자 #1f6fd9). 다크 모드는 같은 이름의 토큰을 shadcn neutral 다크 값 기준으로 정의(선택 메뉴 = 브랜드 20% `color-mix`). `Card`는 그림자 없이 13px 모서리. 상태 Badge 색은 그대로. 원격 글꼴·`url()` 없음.

### 11.5 파일·시험

- 새 파일: `src/navigation.ts`, `src/components/{MenuCard,MenuSearch,WorkspaceHeader}.tsx`, `src/screens/HomeScreen.tsx`, 시험 `src/navigation.test.ts`(10), `src/screens/HomeScreen.test.tsx`(7).
- 바꾼 파일: `App.tsx`(메뉴·탭·홈·경로·검색 배선 — `pagesFor`는 `navigation.ts`로 이동, 규칙 불변), `layout/AppLayout.tsx`, `components/{Logo,PageHeader}.tsx`, `components/ui/card.tsx`, `components/icons.ts`(house·arrow-left-right·arrow-right·chevron-right·user-round), `styles.css`.
- 시험: Vitest 85 → 107. `App.test.tsx` 5 → 10(기존 5건은 새 구조 선택자로 바꾸되 역할별 보임/안 보임 확인을 유지·확대: 메뉴 목록을 정확히 비교, 탭 선택 상태·호출 여부까지 확인), `layout/AppLayout.test.tsx` 3건(로그인 뒤 사례를 새 배치로: 계정·로그아웃은 `aside` 안·`nav` 밖, 경로·검색은 banner 안, 첫 화면 운영 홈 `aria-current`). 다른 시험 파일·기대값 불변(`noRuntimeStyles.test.tsx` 그대로 통과).
- 의존성 추가 없음(lockfile 불변).
