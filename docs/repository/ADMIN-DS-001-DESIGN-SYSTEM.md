# ADMIN-DS-001 관리자 공통 디자인 시스템·컴포넌트 관리·회귀 검증 기준

- rev 0.2 (2026-11-22), 기준 커밋 `e9e4d1c`.
- 근거 결정
  - D-PROC-003: 시안 원본 수정 금지를 해제하되, 그 전에 공통 디자인 시스템과 회귀 검증 기준을 정의한다.
  - D-ADMIN-UI-008 (Q-DS-1~4, 2026-11-22):
    - ① 정본은 시안 class 하나다.
    - ② 원본 CSS를 디자인 시스템으로 편입한다.
    - ③ 시각 회귀는 정해진 폭 캡처 체크리스트로 확인한다.
    - ④ Tailwind 반응형 경계 폭을 캡처에 추가한다.
- 근거 진단: `docs/assessment/FRONTEND-ASSESSMENT.md` §4·§6, `RECOVERY-ROADMAP.md` 4단계.
- 이 문서가 확정되면(독립 리뷰 통과 후 커밋) D-PROC-003의 해제 조건이 충족된다. 그 뒤 §8 순서대로 원본 대조 시험을 전환할 수 있다.

## 1. 범위

| 포함 | 제외 |
|---|---|
| 관리자 콘솔 `apps/internal-admin/frontend`: 스타일, 토큰, 공용 컴포넌트, 화면 모양 시험 | 결제창 `apps/payment-api/frontend`(Tailwind 없음, 현행 유지) |
| | Simulator Thymeleaf 화면 |
| | 기능·API·권한·금융 규칙·DB 변경 |
| | 메뉴·탭 구조 재배치(D-ADMIN-UI-003·006 그대로) |
| | 새 런타임·개발 의존성 |

- **참고자료로 보존하고 수정하지 않는 것**
  - `frontend/docs/ui-reference/`(CLAUDE_IMPLEMENTATION_GUIDE.md, reference/style.css·app.js·index.html·legacy.json)
  - `docs/pg-workspace-react-kit/`
  - 로컬 미리보기 `src/uiref`(격리 시험 포함)
- 위 자료에 있는 "원본 CSS 수정 금지"와 "원본 DOM 계층 보존" 규칙은 관리자 콘솔에 더 이상 적용하지 않는다. 이 문서가 그 규칙을 대신한다.

## 2. 현재 상태 [사실, `e9e4d1c` 기준 grep]

| 항목 | 값 |
|---|---|
| 시안 class 사용 파일 | 22 |
| `components/ui/*` import 파일 | 26. 이 중 2개(lib/statusTone.ts, AcquisitionsScreen)는 `BadgeVariant` 타입만 쓴다. 실행 코드 기준 24 |
| 컴포넌트별 사용 | button 10, input 17, native-select 9, table 5, badge 5(타입 2 포함), label 3, textarea 3, alert 2, card 1 |
| 두 체계를 함께 쓰는 main 파일 | 10 |
| `workspace` 모양 분기 prop | 3개 컴포넌트(ActionButton, RegistrationStep, BusinessProfileFormScreen) |
| workspace.css 394줄 구성 | 원본 블록 14-205줄, 적응 규칙 약 100줄 |
| workspace.css의 `data-theme=dark` | 64줄(주석 1줄 포함), 규칙 63개, 선택자 102개 |
| 원문·구조 단언이 있는 시험 | `*.workspace.test.tsx` 10개, WorkspaceShell, ContractRevisionForm.layout, theme. 13파일 |
| 미디어 쿼리 경계: workspace.css | `max-width:1100px` 2곳, `max-width:720px` 8곳 |
| 미디어 쿼리 경계: Tailwind | `sm:`(640)·`md:`(768)·`lg:`(1024) utility가 main 코드에 약 36곳 |

## 3. 정본

### 3.1 스타일 체계

- **정본은 시안 class 체계 하나다.** workspace.css를 PayCore 디자인 시스템 CSS로 편입한다. 파일 이름을 바꿀지는 B1에서 정한다(가역 세부).
- **정본 class**(시안 이름 그대로). 새 class는 §6.4 절차로만 추가한다.

  | 구분 | class |
  |---|---|
  | 골격 | `heading`, `eyebrow`, `card`, `cardtop`, `cardbody`, `cardnote`, `split`, `stats`/`stat`, `banner`, `mapnote` |
  | 탭·목록 | `tabs`/`on`, `filters`, `tablewrap`, `tablebottom`, `task`, `empty`, `amount` |
  | 상태·문서 | `tag`+tone, `notice`, `timeline`, `detailgrid`, `formrow`, `wizard`/`current`, `rolebar`, `matrix`, `quick`, `subtle` |
  | 동작 | `btn`/`primary` |

- **Tailwind**
  - 빌드 도구로 남긴다. utility는 **배치·간격 보조로만** 쓴다: display, flex, grid, gap, margin, padding, width/height, position, overflow, `sr-only`, 반응형 접두사.
  - 색, 글자 크기, 굵기, 줄 간격, 테두리 색, 그림자, radius utility는 쓰지 않는다. 이 값은 토큰과 정본 class로 정한다.
- **shadcn 복사본** `components/ui/*`(9파일)
  - §5의 정본으로 대체가 끝나면 지운다. 새 shadcn 컴포넌트는 들이지 않는다.
  - `BadgeVariant` 타입(statusTone.ts·AcquisitionsScreen)은 지우기 전에 StatusBadge tone 타입으로 옮긴다.
  - 같은 폴더는 시안 class를 그리는 원자 컴포넌트 자리로 쓴다(§6.1).
- **모양 분기 prop(`workspace` 등)은 두지 않는다.** 한 컴포넌트는 한 모양을 그린다. 변형이 필요하면 §6.3을 따른다.

### 3.2 바뀌지 않는 보안·CSP 규칙

- main 코드에 `style=` 속성, inline `<style>`·`<script>`, `dangerouslySetInnerHTML`을 쓰지 않는다.
- CSS에는 외부 URL, `@import`, 원격 글꼴을 쓰지 않는다. `assetsInlineLimit: 0`을 유지한다.
- 테마는 `<html data-theme>`로만 바꾼다(lib/theme.ts).
- 화면에 표시하지 않는 것
  - 내부 키(`mer_`·`biz_`·`site_`·`ctr_`·`csa_`). 근거 D-ADMIN-UI-004 ③·005
  - 샘플 브랜드·샘플 데이터·시안 전용 메뉴. 근거 D-ADMIN-UI-003

## 4. 디자인 토큰

### 4.1 위치와 형식

- **정의 위치**: 토큰은 디자인 시스템 CSS 맨 앞에서만 정의한다.
  - 라이트는 `:root`, 다크는 `:root[data-theme=dark]` 재정의로 둔다.
  - B1부터 B4까지는 예외가 있다. styles.css에 shadcn용 token(`--brand`, `--chip-*`, `--primary` 등)이 남는다. 이 token은 B5에서 정리한다. 그 전까지 두 곳에 같은 값이 있으면 같은 값인지 시험으로 확인한다(현행 WorkspaceShell:294의 brand 일치 단언을 이어받는다).
- **리터럴 규칙**: 컴포넌트·화면 규칙은 `var(--…)`만 쓴다. 색 hex와 글자·간격 px 리터럴은 토큰 정의부 밖에 쓰지 않는다.
- **상수 예외**: 미디어 쿼리 경계와 시안 고정 배치값은 CSS 변수를 쓸 수 없거나 한 곳에서만 쓰는 값이다. §4.2 표에 상수로 적는다.
- **다크 테마**: 토큰 재정의만으로 바뀌어야 한다. 선택자별 다크 규칙은 없앤다.
  - 토큰으로 표현할 수 없는 예외만 이유 주석을 달고 남긴다.
  - 라이트 값 하나가 다크에서 용도별로 나뉘면 나뉜 용도마다 토큰을 따로 둔다. 예: `#475569` → 다크 `#cbd5e1`(메뉴·태그·표 머리) / `#9aa8bc`(대기 지표), `#1d4ed8` → 다크 `#7cb4ff`(활성) / `#93c5fd`(정보 태그·초점).

### 4.2 토큰 분류

토큰 이름은 B1에서 확정한다.

| 분류 | 토큰 | 값 출처 |
|---|---|---|
| 바탕 | canvas, surface, surface-subtle, row-hover, overlay-dim | 라이트는 시안 최종값과 대비 보정, 다크는 D-ADMIN-HOME-001 slate. 현재 workspace.css·styles.css에 적용된 값 |
| 선 | line(장식 구분선), control-border(입력·버튼 경계) | 같음 |
| 글자 | text-heading, text-body, text-secondary, text-muted, eyebrow와 용도별 다크 분기 | 같음 |
| 브랜드 | brand(`#116ef5`), brand-text, brand-soft, focus-ring | styles.css `--brand` 현행값 |
| 상태 tone | info / success / warning / danger / neutral 각각 bg·fg | 시안 `.tag`, styles.css `--chip-*` |
| 위험 동작 | destructive-solid(다크에서 흰 글자 4.5:1) | styles.css 현행값 |
| 글자 크기·간격·radius·그림자 | 시안에 있는 값 집합. 그림자는 drawer·overlay·toast에만 | 시안 |
| 상수 | 사이드바 236px, header 76px, 경계 1100·720px, Tailwind 경계 640·768·1024px | 시안, Tailwind 기본 |

### 4.3 값 규칙

- **B1(편입)은 값을 바꾸지 않는다.** 지금 적용된 최종값을 그대로 토큰으로 옮긴다. 화면 차이는 0이어야 한다(측정 방법은 §7.4).
- 값을 바꾸는 일은 별도 "값 변경 배치"로 한다. 사용자에게 Demo로 보여 준다.
- **대비 기준(WCAG 2.1 AA, 라이트·다크 모두)**

  | 대상 | 기준 |
  |---|---|
  | 본문 글자 (SC 1.4.3) | 4.5:1 이상 |
  | 큰 글자: 24px 이상, 또는 18.66px(14pt) 이상이면서 굵기 700 이상 | 3:1 이상 |
  | 식별에 필요한 UI 경계(control-border)와 초점 링 (SC 1.4.11) | 3:1 이상 |

  - 장식 구분선(line)과 카드 경계는 SC 1.4.11 대상이 아니다.
- **알려진 미달**: control-border가 흰 바탕에서 약 1.2:1이고 다크에서 약 1.8:1이다.
  - B1은 이 값을 바꾸지 않는다. 대신 대비 시험에 **이름 붙은 미달 목록**(값·대비비·후속 배치 V1)으로 명시한다.
  - V1에서 이 값을 보정한 뒤 목록을 비운다. 목록에 새 항목을 더하는 것은 리뷰 지적 대상이다.
- **새 토큰을 추가하는 조건**: 아래 셋을 모두 만족해야 한다.
  1. 기존 토큰으로 표현할 수 없다.
  2. 라이트·다크 값이 둘 다 있다.
  3. 그 글자/바탕 쌍이 대비 계산 시험(§7.3)에 들어간다.

## 5. 컴포넌트 카탈로그 (역할 하나에 정본 하나)

| 역할 | 정본 | 정리 대상(현재 중복) |
|---|---|---|
| 버튼 | `Button` 원자(`.btn`, `.btn.primary`, 위험 동작 variant). ActionButton을 흡수 | shadcn Button 10파일, ActionButton `workspace` 분기, 직접 쓴 `.btn` |
| 입력 원자 | `Input`·`Select`·`Textarea` 원자 + 업무 입력 `DigitsInput`·`RegistrationNumberInput`·`OneTimeCode` | shadcn Input 17·NativeSelect 9·Textarea 3·Label 3 |
| 입력 줄 | `FormRow`(왼쪽 140px 이름 열) | `Field`(위에 label) 30곳, BusinessProfileForm 지역 `Row`(hint·error·폭) |
| 상세 한 줄 | `DetailRow`(`.detailgrid`) | 등록 화면 지역 `Row`, PaymentOps 직접 `.detailgrid`, 승인 상세 3종의 dt/dd |
| 섹션 카드 | `InfoCard`(`card`·`cardtop`) | BusinessProfileForm `Section`, shadcn Card |
| 상세 패널 | 공용 `DetailPanels`(승격) | Merchants·Transactions·Acquisitions 지역 `DetailPanels`·`Panel` |
| 표 | `.tablewrap` + `EmptyRow` + `.amount` | shadcn Table 5파일 |
| 상태 태그 | `StatusBadge`(`tag`+tone). 상태→tone 규칙은 한 곳 | shadcn Badge, `BadgeVariant` 타입 |
| 알림 | `ErrorNotice`·`StatusNotice`·`WarningNote`(`.notice`·`.banner`) | shadcn Alert 2 |
| 제목 줄 | `MapNote`(목록·등록 전환 버튼 포함), `WorkspaceHeader` | `PageHeader`: 사용처를 확인한 뒤 하나로 |
| 등록 단계 | `RegistrationStep`(모양 하나) | `workspace`·`rows` 플래그로 그린 세 모양 |
| 로딩·빈 상태 | `Spinner`, `EmptyRow` | — |
| 셸 | `AppLayout`, 사이드바, 상단 경로 | — |

- **입력 줄 범위**: FormRow는 등록·수정·요청 폼 입력에 쓴다.
  - 근거: D-ADMIN-UI-005 ④ "모든 입력 화면"과 D-ADMIN-UI-006 140px 이름 열.
  - 예외(가역 세부, Coordinator 결정)는 아래와 같다. 다른 곳에서 쓰면 §6.4 절차를 거친다.
    - 조회 조건 줄(`.filters`): 시안 가로 배치
    - 로그인 화면 칸
    - 승인 확인의 일회용 코드 칸
- **이름 규칙 적용 대상**: ApprovalsScreen의 지역 `Row`도 §6.2 대상이다.
- **금융 안전 장치는 모양 정리 때 바꾸지 않는다.**
  - 대상: 결과 불명 잠금("같은 내용 다시 보내기", "그래도 새로 입력", 같은 submissionId), 감사 안내 문구, 세션 종료 처리
  - B2에서 ActionButton을 교체할 때 이 버튼들의 동작 시험은 바꾸지 않고 통과해야 한다.
  - 잠금 로직 통합은 RECOVERY-ROADMAP 2-1의 별도 작업이다.

## 6. 컴포넌트 관리 규칙

### 6.1 위치

| 위치 | 담는 것 |
|---|---|
| `src/components/ui/` | 업무 뜻이 없는 원자(Button, Input, Select, Textarea, Tag 등). 시안 class를 그리는 얇은 컴포넌트 |
| `src/components/` | 두 화면 이상이 쓰는 업무 공용 구성요소 |
| `src/screens/<Screen>.tsx` 안 지역 컴포넌트 | 그 화면에서만 쓰는 것 |

### 6.2 승격과 이름

- 같은 역할의 마크업이 두 번째 화면에 필요하면 복사하지 않고 공용으로 승격한다. 승격하면 §5 표에 적는다.
- 지역 컴포넌트에 일반 이름(`Row`, `Panel`, `Section`, `Wrap`)을 쓰지 않는다. 역할이 드러나는 이름을 쓴다. 예: `ActivationSiteRow`
- 파일 하나에는 주요 공용 컴포넌트 하나를 둔다. 작은 짝 컴포넌트는 예외다(InfoCard·DetailRow처럼).

### 6.3 props

- boolean으로 다른 모양을 그리지 않는다.
- 변형이 꼭 필요하면 열거형 prop 하나(`variant`)를 쓰고, 값마다 §5 표에 적는다.
- 접근성 이름·역할·상태(`aria-*`, `disabled`)는 원자가 책임진다.

### 6.4 바꾸는 절차

새 공용 컴포넌트·variant·class·토큰을 추가하거나 바꿀 때는 한 배치 안에서 아래를 모두 한다.

1. §5 표, §3.1 class 목록, §4.2 토큰 표를 갱신한다.
2. 동작·접근성 시험을 추가한다.
3. 사용처 화면을 §7.4로 캡처한다.

## 7. 회귀 검증 기준

### 7.1 그대로 유지하는 자동 시험 (삭제·완화 금지)

파일을 옮기거나 단언 방식을 바꾸면 리뷰가 전후를 대조한다. 지키는 행동은 줄이지 않는다.

| 분류 | 시험 |
|---|---|
| CSP·자원 | `test/noRuntimeStyles.test.tsx` 전체(11개) |
| | `WorkspaceShell.test.tsx` "workspace.css is self-contained"(:287) — B1 뒤 디자인 시스템 CSS 전체에 적용 |
| | `theme.test.tsx` index.html inline script 금지(:106) |
| | `vite.config.ts:21` `assetsInlineLimit: 0` — 지금은 시험이 없다. B1에서 이 설정을 읽는 Vitest 단언을 추가한다(§7.3) |
| 보안·결정 | `test/domScan.ts` 내부 키 비노출(21파일) |
| | `WorkspaceShell.test.tsx` 샘플 브랜드·데이터·시안 전용 메뉴 비노출(:76), main.tsx가 uiref를 불러오지 않음(:281 — 불러오는 CSS 이름은 B1에서 바뀔 수 있다) |
| | `uiref/UiRefIsolation.test.ts` 미리보기 격리·kit 사본 sha256 |
| | client CSRF·같은 출처, 세션 종료 처리 |
| 금융 화면 동작 | 결과 불명 잠금·같은 submissionId 재전송, 다른 사업자로 넘기기 차단, 감사 안내·AUDIT_UNAVAILABLE 처리 |
| 접근성 대비 | `theme.test.tsx` 대비 단언 전부(:148 MINOR-1 다크 destructive, :159 MINOR-2, :165 MINOR-6, :229 라이트 표 칸, :238 다크 표 칸) |
| | 같은 파일·WorkspaceShell:294의 라이트 대비 보정값 |
| | 이 쌍들은 §7.3 토큰 대비 시험에 **모두** 들어가야 한다. MINOR-1은 새 Button 원자의 위험 variant로 옮긴다 |
| 테마·동작 | `theme.test.tsx`의 data-theme 전환·저장·깜박임 방지(:37~:86) |
| | `theme.test.tsx` `prefers-color-scheme` 없음 단언(:113 — 테마는 data-theme로만) |
| | WorkspaceShell의 검색 overlay·초점 가두기·Ctrl+K(:131-202), 검색 busy·stale 응답(:237-254). 가맹점 목록 시험(:214-235)의 DOM 구조 부분은 §7.2 전환 대상이고 그 안 내부 키 단언(:215)만 보안 행에 속한다 |
| | Tailwind가 남아 있는 동안 cascade layer 순서(:275) |

### 7.2 전환 대상 (원문·모양 대조 → 계산·행동·접근성 단언)

| 지금 단언 | 바꿀 단언 |
|---|---|
| 원본 CSS 선언 블록 순서·문자열 대조(WorkspaceShell:312) | 토큰·리터럴·다크 시험(§7.3)과 캡처(§7.4) |
| workspace.css 원문 정규식(구역 표식 "PayCore 적응 규칙"·"라이트 대비 보정", 선택자별 hex). 위치: workspace 시험 7개(PaymentOps:195-201, Login:164, Accounts:131-134, Reconciliation:76-78, Transactions:147-157, BusinessProfileForm:289, Acquisitions:137), theme:109(:113 제외)·:178, WorkspaceShell:294 | 같은 값 쌍을 토큰 대비 계산·리터럴 시험으로. 행동 의미가 있으면 렌더 단언으로 |
| DOM 경로 배열(`shape(panel)`), `:scope > .cardnote > p.subtle` 같은 선택자 | 역할·접근성 이름 질의(`getByRole(..., { name })`), landmark·heading 단계 |
| 배치 목적의 `toHaveClass` | 사용자 동작 결과·상태(`aria-selected`, `aria-expanded`, `disabled`, 초점 이동) |

- **전환은 삭제가 아니라 대체다.** 바꾸는 시험 파일마다 아래 **보장 행동 매핑표**를 만든다. 리뷰는 이 표를 diff와 대조한다.

  | 기존 시험(파일:줄) | 지키던 행동 | 대체 시험 또는 체크리스트 항목 |
  |---|---|---|

- **지울 수 있는 단언**: 매핑표에서 "모양 전용"으로 분류된 것만 지운다. "모양 전용"은 행동·접근성·보안·결정 의미가 없고 §7.4 캡처 항목으로 대체되는 단언이다.
- **전환 대상 아님**: §7.1의 시험.
- **남기는 구조 단언**: 화면 구조가 업무 의미를 가지면 role 기반 구조 단언으로 남긴다. 예: 상세가 목록 옆 영역에 열린다, 등록 단계 순서.
- **매핑표 보존**: 매핑표는 커밋되는 이 문서 §11 배치 기록에 남긴다. `build/`는 커밋되지 않으므로 거기에만 두지 않는다.

### 7.3 새로 추가하는 자동 시험

| 시험 | 판정 | 추가 배치 |
|---|---|---|
| 토큰 대비 계산 | §4.3 표의 쌍을 라이트·다크로 계산한다. §7.1 대비 쌍을 모두 포함한다. 미달은 "알려진 미달 목록"에 있는 것만 허용한다 | B1 |
| 리터럴 위치 | 디자인 시스템 CSS에서 토큰 정의부·§4.2 상수 밖의 hex 색 0 | B1 |
| 자산 인라인 금지 | vite.config의 `assetsInlineLimit: 0` 유지 | B1 |
| 다크 선택자 | 토큰 재정의 밖의 `data-theme=dark` 선택자는 이유 주석이 달린 예외만 | B1 |
| 모양 분기 prop | `workspace` 등 모양 boolean prop 0 | B2 |
| shadcn 잔존 | `components/ui`의 shadcn 복사본 import 0 | B2 |
| 금지 utility | main TSX className의 색·글자·그림자·radius utility 0(§3.1) | B5 |

- 모두 Vitest 안에서 파일을 읽어 검사한다. 새 도구는 쓰지 않는다.

### 7.4 시각 확인 체크리스트 (캡처)

- **언제**: 화면 모양을 바꾸는 배치마다 한다. CSS·마크업·공용 컴포넌트·토큰 변경이 해당된다. 로직만 바꾼 배치는 제외한다.
- **폭**
  - 기본: 1440, 1101, 1100, 721, 720, 390px. workspace.css 경계(1100·720)의 양쪽이다.
  - 추가(Q-DS-4): 1024, 1023, 768, 767, 640, 639px. Tailwind 반응형 경계(`lg`·`md`·`sm`)의 양쪽이다. main 코드에 반응형 utility가 하나라도 남아 있는 동안 적용한다. B5에서 반응형 utility가 사라지면 기본 폭만 본다.
  - 높이: 900px. 390px만 844px.
- **테마**: 라이트, 다크.
- **대상**
  - 바뀐 화면
  - 공용 컴포넌트나 토큰을 바꿨으면 §5 표의 사용처 화면 전부
  - 셸이나 토큰 전체를 바꿨으면 메뉴의 모든 화면
- **판정**: 아래 항목이 모두 0이어야 통과다.
  1. 가로 넘침(문서 `scrollWidth` > viewport 폭)
  2. 잘림·겹침(사람이 눈으로 확인)
  3. 대비 미달(렌더된 글자/바탕 색 계산, §4.3 기준, 알려진 미달 목록은 제외)
  4. `style` 속성을 가진 요소
  5. 콘솔 오류·CSP 위반
- **비교**: 변경 전 캡처와 나란히 놓고 의도한 차이만 있는지 본다.
- **"차이 0"을 재는 방법**: 값을 바꾸지 않는 배치(B1 등)에 적용한다. 두 가지를 모두 남긴다.
  - 같은 화면·폭·테마에서 전후 캡처의 **픽셀 비교**. 다른 픽셀 0이 기준이며, 글자 안티앨리어싱처럼 설명된 차이만 허용한다.
  - 보이는 모든 요소의 **computed style 비교**. 색, 배경, 테두리, 글자 크기·굵기, 여백, 크기를 본다. 차이 0이 기준이다.
- **증거**
  - 캡처 이미지와 판정 JSON(화면, 폭, 테마, 검사 결과, 사용 명령, 시각)을 `build/harness-evidence/<TASK>-capture-<UTC 시각>/`에 둔다.
  - 판정 요약(대상 수, 항목별 결과, 차이 수)은 §11 배치 기록에 남겨 커밋한다.
- **도구**: 기존처럼 저장소 밖 로컬 캡처 스크립트로 한다. 저장소에 새 의존성을 넣지 않는다. 판정 항목은 이 절이 정하고, 실행 명령은 증거에 남긴다.
- **Demo**: 배치가 끝나면 기존 화면 우선 방식대로 Demo에서 사용자에게 보여 준다.

### 7.5 게이트

- 배치: SCOPED 게이트(ADMIN-FRONTEND·INTERNAL-ADMIN 단위)와 §7.4 캡처.
- 작업 DONE: FULL PASS가 필요하다(D-PROC-001, HARNESS-GUIDE §11).

## 8. 이행 순서 (RECOVERY-ROADMAP 4단계 구체화)

- 각 배치는 독립 커밋이다.
- 배치마다 독립 리뷰, SCOPED 게이트, §7.4 캡처를 거친다.
- 배치가 건드리는 시험의 원문·구조 단언은 그 배치에서 §7.2대로 매핑표와 함께 전환한다.

| 배치 | 내용 | 완료 기준 |
|---|---|---|
| B1 토큰 편입 (D-PROC-003 해제가 실제로 적용됨) | 원본 블록과 적응 규칙의 값을 §4 토큰 `var()`로 바꾼다. 다크 선택자를 토큰 재정의로 줄인다(용도별 토큰 허용). 낡은 주석(workspace.css:12 "시안에는 다크 모드가 없어")을 고친다. §7.2 둘째 행의 원문 정규식 단언을 모두 전환하고, WorkspaceShell은 :294·:312만 전환한다 | 값 변경 0(§7.4 픽셀·computed style 차이 0), §7.3 B1 시험 통과, §7.1 무변경 통과, 매핑표 |
| V1 경계 대비 보정 (값 변경) | control-border를 SC 1.4.11 3:1로 보정한다. 알려진 미달 목록을 비운다 | 미달 목록 0, Demo 확인 |
| B2 원자 컴포넌트 | Button·Input·Select·Textarea·Tag 원자를 만들고 shadcn 사용 파일을 교체한다. `BadgeVariant`를 옮긴다. `workspace` 분기와 shadcn 복사본을 삭제한다 | 모양 분기 0, 두 체계 함께 쓰는 파일 0, §7.3 B2 시험 통과 |
| B3 줄·카드·패널 통합 | FormRow, DetailRow, InfoCard, DetailPanels로 합치고 지역 일반 이름을 정리한다 | §5 정리 대상 0(문서화된 예외만) |
| B4 남은 구조 단언 전환 | B1~B3에서 건드리지 않은 DOM 경로·배치 class 단언을 전환한다 | 해당 단언 0, 매핑표 |
| B5 Tailwind·CSS 정리 | 금지 utility를 정리하고 styles.css의 shadcn 전용 token을 정리한다(§4.1 예외 종료) | §7.3 전부 통과 |

**재정비 지표**(RECOVERY-ROADMAP 전체 완료 기준과 같음)

| 지표 | 목표 |
|---|---|
| 스타일 체계 수 | 1 |
| `workspace` 분기 | 0 |
| 토큰 재정의 밖의 다크 hex | 0(이유 주석 예외만) |
| 금융·보안·접근성 시험(§7.1) | 바꾸지 않고 전부 통과 |
| 이후 화면 모양 변경 1건의 시험 순증 줄 수 | 같은 변경의 main 순증 줄 수 이하. 측정: `git show --numstat`의 src 시험 파일 합계 대 main 합계, FRONTEND-ASSESSMENT §6 방식 |

## 9. 원칙별 증거 (8대 원칙)

| 원칙 | 판정 |
|---|---|
| Financial Consistency, Transaction Safety, Concurrency Control, Reconciliation | N/A. 화면 모양만 바꾸며 서버·DB·금액 계산을 바꾸지 않는다 |
| Idempotency, Recoverability | 화면 결과 불명 잠금 동작 시험을 무변경 유지한다(§7.1). 잠금 통합은 별도 작업(RECOVERY-ROADMAP 2-1) |
| Auditability | 감사 안내 문구·AUDIT_UNAVAILABLE 처리 시험을 유지한다(§7.1) |
| Observability | 콘솔 오류·CSP 위반 0을 캡처 판정에 포함한다(§7.4) |

## 10. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-22 | 최초 작성(D-PROC-003 해제 조건, D-ADMIN-UI-008 ①~③) |
| 0.2 | 2026-11-22 | 독립 리뷰(CHANGES_REQUIRED) 반영. 재리뷰 REVIEW_PASS 뒤 MINOR 3건(줄 범위, §7.1 WorkspaceShell 범위, assetsInlineLimit 시험 부재) 반영 |
| 0.2 | 2026-11-23 | 본문 규칙 변경 없음. §11.1 B1 배치 기록(토큰 이름 확정·결정·매핑표·캡처 요약·미결) 추가 |

**0.2 반영 내용**

- 대비·보안·결정 시험을 §7.1에 추가했다.
- B1 범위를 원문 정규식 단언 전체로 넓혔다.
- 경계 대비 미달은 V1 배치로 분리했다.
- 용도별 다크 토큰을 허용했다.
- Tailwind 경계 캡처 폭을 추가했다(Q-DS-4).
- §2 숫자를 정정했다.
- 차이 0 측정 방법과 커밋되는 증거 위치를 정했다.
- 입력 줄 예외를 정했다.

## 11. 배치 기록

각 배치의 보장 행동 매핑표와 캡처 판정 요약을 여기에 더한다.

### 11.1 B1 토큰 편입 (ADMIN-DS-B1, 2026-11-23, 기준 `7024796`)

**가역 세부 결정**(문서 관례로 구현자가 정함, 리뷰 대상)

| 항목 | 결정 | 이유 |
|---|---|---|
| 디자인 시스템 CSS 파일 | `src/workspace.css` 이름 유지(§3.1 "B1에서 정한다") | 이름만 바꾸면 import·시험·주석만 늘어난다. §7.1 "self-contained" 시험이 이 파일 전체에 적용된다 |
| 토큰 이름 | 모두 `--ds-` 접두 | styles.css shadcn token은 cascade layer 밖이라 같은 이름이면 이 층 값을 이긴다(예: `--eyebrow` #64748b ≠ 시안 eyebrow #526984). B5에서 shadcn token을 정리할 때 다시 본다 |
| 토큰 범위 | 색, 글자 크기(`--ds-fs-<px>`), radius(`--ds-radius-<px>`), 그림자(`--ds-shadow-drawer`) | 간격·크기 px(padding·margin·gap·width·height·letter-spacing)와 §4.2 상수는 B1에서 리터럴로 두었다 — 아래 미결 |
| 덮인 원본 색 | 시안 블록 안에서 뒤 규칙에 덮여 보이지 않던 원본 색은 최종값과 같은 토큰으로 바꿨다 | 렌더 결과가 같다(아래 캡처). 원래 시안 값은 `docs/ui-reference` 원본에 남는다 |
| 시안 CSS 변수 | `--blue`·`--line`·`--muted` 삭제 → `--ds-logo-bg`·`--ds-line`. `--muted`는 styles.css 값이 이미 이기고 있었고 이 파일은 쓰지 않았다 | 토큰 정의 위치를 맨 앞 한 곳으로(§4.1) |
| 다크 예외 | 1건: `:where(:root[data-theme=dark]) .btn{color:var(--ds-text-body)}` | 라이트 `.btn`은 글자색을 정하지 않고 놓인 곳의 색을 이어받는다(예: 로그아웃 = 계정 영역 색). "이어받음"은 토큰 값으로 적을 수 없다. `:where()`로 명시도를 `.btn`과 같게 둬 `.cardtop button`·`.btn.primary`가 지금처럼 이긴다 |
| 용도별 다크 토큰 | `#475569` → `--ds-text-secondary`(다크 #cbd5e1)·`--ds-text-workspace`(#dbe4f0)·`--ds-text-quiet`(#9aa8bc), `#64748b` → `--ds-text-muted`·`--ds-placeholder`(#94a3b8), `#1d4ed8` → `--ds-brand-text`(#7cb4ff)·`--ds-tone-info-fg`·`--ds-focus-ring`(#93c5fd), `#3182f6` → `--ds-brand-line`(#7cb4ff)·`--ds-logo-bg`(#3b82f6)·`--ds-tone-info-glyph`(#93c5fd)·`--ds-accent-control`(그대로) | §4.1 용도별 토큰 허용. 다크 값을 따로 정한 적 없는 `.matrix` accent·`nav em` 배지는 라이트와 같은 다크 값으로 옮겼다(값 변경 0) |
| 시험 안 토큰 이름 | 시험 소스에는 전체 이름(`--ds-text-muted`)만 쓴다 | Tailwind가 소스의 짧은 이름(`text-muted`·`text-secondary`)을 utility 후보로 읽어 번들에 쓰지 않는 class를 더했다(B1 중 실측). 전체 이름으로 바꾼 뒤 번들 CSS가 측정본과 같다 |
| 대비 보정 블록 | "라이트 대비 보정" 블록은 원본 순서(같은 명시도, 뒤에서 이김)를 지키려고 남기고 값은 토큰으로 | 보정값 자체는 토큰 정의에 있다 |

**토큰 이름(§4.2 확정)**

| 분류 | 토큰 |
|---|---|
| 바탕 | `canvas`, `surface`, `surface-subtle`, `surface-muted`, `table-head`, `row-hover`, `input-bg`, `overlay-dim`, `toast-bg`/`toast-fg` |
| 선 | `line`(장식), `line-row`, `line-soft`, `line-timeline`, `line-step`, `banner-border`, `control-border`(입력), `control-border-btn`(버튼) |
| 글자 | `text-heading`, `text-body`, `text-secondary`, `text-workspace`, `text-quiet`, `text-muted`, `text-muted-strong`, `placeholder`, `eyebrow` |
| 브랜드 | `brand`, `brand-fg`, `brand-text`, `brand-soft`, `brand-line`, `banner-action`, `logo-bg`/`logo-fg`, `focus-ring`, `accent-control` |
| 상태 tone | `tone-{neutral,info,warning,success,danger}-{bg,fg}`, `tone-info-glyph`, `tone-warning-glyph`/`-glyph-bg`, `tone-success-step-{bg,fg}`, `nav-badge-{bg,fg}` |
| 위험 동작 | styles.css `--destructive-solid` 그대로(B2에서 Button 원자 위험 variant로) |
| 크기 | `fs-9`…`fs-30`(15단계), `radius-4`…`radius-15`(10단계), `shadow-drawer` |

**알려진 미달 목록**(designTokens.test.ts `KNOWN_SHORTFALLS`)

| 쌍 | 라이트 | 다크 | 후속 |
|---|---|---|---|
| `control-border` / `input-bg`·`surface` | 1.23:1 | 1.81:1·1.68:1 | V1 |
| `control-border-btn` / `surface` | 1.23:1 | 1.68:1 | V1 |
| `text-muted` / `surface-subtle`(`.notice` 12px 글자) | 4.47:1 | 통과 | B1에서 새로 드러난 기존 값. Coordinator 결정(2026-11-23): 목록 추가 수용, **V1 범위에 포함(값 변경, Demo 사용자 확인)** |

**보장 행동 매핑표**(§7.2. "방식만 변경"은 §7.1 시험 — 같은 쌍·같은 기준, 색을 읽는 곳만 토큰으로)

| 기존 시험(파일:줄, `e9e4d1c`) | 지키던 행동 | 대체 시험 또는 체크리스트 항목 | 분류 |
|---|---|---|---|
| WorkspaceShell.test.tsx:294 | 라이트 대비 보정값(주 버튼·안내 띠 단추·보조 글자·단계 번호·주황 글리프)이 원본 값을 이기고, styles.css `--brand`와 같다 | designTokens.test.ts "every pair meets…"(`brand-fg/brand`, `banner-action/brand-soft`, `text-muted-strong` 3쌍, `tone-success-step`, `tone-warning-glyph` 3:1), "values defined in both places"(`--brand`↔`--ds-brand`). 이기는 값은 §7.4 computed style 차이 0 | 계산 |
| WorkspaceShell.test.tsx:312 | 시안 원본 선언 블록이 같은 순서로 모두 있다(원본 수정 금지 — D-PROC-003으로 해제) | §7.4 픽셀·computed style 차이 0, designTokens.test.ts 리터럴 위치·다크 선택자 | 모양 전용 |
| theme.test.tsx:109(:112·:113 유지) | 다크 색은 `data-theme=dark` 아래에만, 다크 구역 규칙은 모두 `:root[data-theme=dark]` | designTokens.test.ts "only the token override block and the listed exceptions mention data-theme", "token definitions … hold the only literal colours", "every colour token has light and dark values" | 계산 |
| theme.test.tsx:178 | 긴 KPI 값이 카드 안에서 줄을 바꾼다 | 같은 it — 규칙 모델 `declOf('.stat strong','overflow-wrap') = anywhere`(Demo로 긴 KPI 값을 만들 수 없어 캡처로 대체 불가) | 규칙 모델 |
| PaymentOps.workspace:195-201 | 진행 단계가 카드 좌우 23px에 맞는다. dt/dd가 label/strong 최종값. 다크 dt/dd 값 | 대비 `text-secondary/surface`·`text-heading/surface`(라이트·다크), 리터럴 위치, 캡처 `payment-ops` | 모양 전용 + 계산 |
| Login.workspace:164 | 로그인 화면이 쓰는 class(body·header·.brand·h2·.card·.subtle·.btn·.btn.primary·.notice)에 다크 색이 있다 | 같은 it의 테마 전환 단언은 유지. 다크 색 = 토큰 규칙(모든 색 토큰 다크 값)·리터럴 위치·대비. 캡처 `login`·`login-totp`·`enroll` 다크 12폭 | 계산 + 캡처 |
| Accounts.workspace:131-134 | 행 작업 summary(.btn)도 버튼과 같은 초점 링(라이트 보정색·다크) | designTokens.test.ts "focus ring": button·a·summary·h2[tabindex=-1] 각각 `@media` 밖에 `outline:3px solid var(--ds-focus-ring)` 규칙이 있고, 파일 순서로 접은 최종 굵기·모양·색이 `3px`·`solid`·`var(--ds-focus-ring)`(입력 focus는 `2px solid`). 대비 `focus-ring` 3:1(surface·canvas·input-bg·brand-soft, 라이트·다크). 키트 비교 focus 상태 | 접근성 계산 |
| Reconciliation.workspace:76-78 | `.cardtop h3` = h4 값, 다크 값이 있다 | 대비 `text-heading/surface`, 토큰 다크 값 규칙, 캡처 `reconciliation` | 모양 전용 + 계산 |
| Transactions.workspace:147-157 | 필터 줄바꿈(1101~1300px 날짜·MID 잘림 방지), 표 아래 줄 간격, 새 색 없음 | 캡처 `transactions` 12폭(가로 넘침 0, 잘림 눈 확인 — 1440·1101), 리터럴 위치 | 모양 전용 |
| BusinessProfileForm.workspace:289 | 폼 줄 140px 이름 열·폭 단계·최소 16ch·720px 이하 쌓임, 색 없음 | 캡처 `business-registration` 1024(12자리 번호 다 보임)·721/720(쌓임), 리터럴 위치 | 모양 전용 |
| Acquisitions.workspace:137 | 카드 안 레코드 상태 막대 여백 0·줄바꿈, `.cardtop h3` 기존 색, 새 색 없음 | 같은 파일 새 it(규칙 모델: padding 0·flex-wrap wrap — 매입 파일 상세는 Demo 데이터가 없어 캡처 불가), 대비 `text-heading/surface`, 리터럴 위치 | 규칙 모델 + 계산 |
| theme.test.tsx:159 (§7.1 MINOR-2) | 다크 `.detailarea h3 small` ≥ 4.5:1 on #111a2b | 같은 it: 규칙의 토큰이 다크 재정의를 가진다 + 같은 기준(4.5, #111a2b). designTokens `text-muted/surface`에도 | 방식만 변경 |
| theme.test.tsx:229 (§7.1) | 라이트 표 칸 4쌍 ≥ 4.5:1, 카드 #ffffff | 같은 it: 같은 선택자 4개, 색은 규칙의 토큰 라이트 값 | 방식만 변경 |
| theme.test.tsx:238 (§7.1) | 다크 표 칸 4쌍 ≥ 4.5:1 | 같은 it: 같은 선택자 4개의 토큰 다크 값, 카드 #111a2b 단언 추가 | 방식만 변경 |

- 바뀌지 않은 §7.1 시험: noRuntimeStyles 전체, WorkspaceShell:287(self-contained)·:281·:76·:131-254·:275, theme :37~:106·:113·:148(MINOR-1)·:165(MINOR-6), domScan, UiRefIsolation, 금융 화면 동작 시험.
- 새 시험(§7.3 B1): `src/lib/designTokens.test.ts` 13개(토큰 대비 5, 리터럴 위치 3, 다크 선택자 2, 초점 링 2, 자산 인라인 금지 1). 리터럴 시험은 hex·색 함수와 CSS 이름 색 전체(transparent·currentColor 제외)를 잡는다와 도우미 `src/test/cssModel.ts`(규칙 모델·토큰 해석·WCAG 대비).
- 변이 확인(저장소 밖 사본): 컴포넌트 규칙의 hex·`white`·글자 크기 px, 주석 없는 다크 선택자, 이유 주석과 떨어진 예외, 다크 값 없는 토큰, 다크 보조 글자 대비 저하, 고친 뒤 남은 미달 항목, summary 초점 링 다른 토큰, `assetsInlineLimit: 4096`, `--brand` 불일치, 쓰지 않는 토큰, 이름 색(`rebeccapurple`), 빈 주석(`/**/`)만 붙은 예외, summary 초점선 1px dotted, button·a 초점선 삭제·1px dotted, 파일 끝에서 닫히지 않은 주석 — 18건 모두 실패로 잡혔다(명령·결과 `~/gate-logs/admin-ds-b1/mutations.log`, 리뷰 반영 후 재실행).

**캡처 판정 요약**(§7.4. 증거 `build/harness-evidence/ADMIN-DS-B1-capture-20261123T133503Z/` — `verdict.json`, 실행별 `shots.json`·`compare-*.json`·이미지, 도구 사본 `tool/`)

- 대상: 메뉴의 모든 화면 20상태(로그인·인증 코드·계정 등록, 운영 홈·메뉴 검색 열림, 내 요청, 가맹점 목록·검색 결과·상세·등록, 사업자 목록·상세·등록, 결제 보정 취소, 거래 조회, 매입 현황, 계정 관리, 접속·감사 이력, 승인함, 대사 발견) × 12폭(1440·1101·1100·1024·1023·768·767·721·720·640·639·390, 높이 900·390은 844) × 라이트·다크 = 480장. Demo 데이터는 seed 가맹점 1개·결제 0건(새로 만든 데이터 없음).
- 방법: "차이 0"은 같은 화면 DOM 안에서 번들 CSS만 바꿔(HEAD CSS ↔ 새 CSS) 두 번 찍어 잰다 — 데이터·시각 차이가 섞이지 않는다. Demo 재빌드 전(HEAD 제공, 새 CSS 끼움)과 후(새 CSS 제공, HEAD CSS 끼움) 두 번. 재빌드한 Demo가 내는 CSS는 측정한 CSS와 sha256이 같다(JS 번들 무변경).

| 항목 | 재빌드 전 쌍 | 재빌드 후 쌍 |
|---|---|---|
| computed style 차이(보이는 모든 요소, 색·배경·테두리·글자·여백·크기·위치) | 0 | 0 |
| 픽셀 차이 | 5장 27픽셀 | 11장 41픽셀 |
| 가로 넘침 / `style` 속성 / CSP 위반 | 0 / 0 / 0 | 0 / 0 / 0 |
| 콘솔 오류 | 로그인 전 `GET /api/auth/me` 401 6건(인증 전 확인 요청 — 기존 동작) | 같음 |

- 픽셀 차이는 모두 pixelmatch가 안티앨리어싱으로 분류한 픽셀이다(채널 차 최댓값은 기록하지 않았다). 위치는 선택 메뉴 둥근 모서리(x=17·186)와 아이콘 글리프 가장자리이고, 같은 장의 computed style은 같다.
- 별도 세션 비교(재빌드 전 화면 대 재빌드 후 화면)의 차이는 데이터·시각뿐이다: 접속·감사 이력 행 증가(캡처 로그인 기록), 운영 홈 "기준" 시각, 가맹점 상세 시각, 대사 마지막 실행 시각.
- 화면에 없는 class(drawer·modal·toast·matrix·timeline·clickable 행)와 :hover·:focus-visible·:focus는 정적 키트 페이지로 HEAD CSS 대 새 CSS를 비교했다: 207요소 × 2테마 × 4폭 + hover 2 + 초점 9, 차이 0(1단위 토큰 변이로 검출력 확인 — 104건 검출).
- 잘림·겹침(눈 확인): computed style·위치가 전부 같아 전후 모양이 같다. 기존 모양 자체의 잘림·겹침은 이번 배치 범위 밖이며 새로 보이는 것은 없다.

**미결**

- 간격·크기 px 토큰화 범위: §4.1 리터럴 규칙은 "글자·간격 px"를 말하지만 §7.3 리터럴 시험은 hex만 정한다. B1은 색·글자 크기·radius·그림자까지 했다. 간격을 토큰으로 옮길지(값 집합 약 30개), 언제 할지 Coordinator 판단.
- `.notice` 글자 대비 4.47:1(라이트) — 결정됨: V1 범위에 포함(값 변경, Demo 사용자 확인). 위 미달 목록.
- Tailwind가 시험 파일 문자열을 utility 후보로 읽는다(위 "시험 안 토큰 이름"). B5에서 `@source not` 등으로 시험 파일을 소스 검색에서 빼는 방법을 검토한다.
- §4.1 "두 곳에 같은 값" 시험(designTokens.test.ts `same` 목록)의 적용 범위: 지금은 styles.css shadcn token과 이름이 1:1로 대응하는 15쌍만 본다(`--sidebar-*`·`--chip-success-*`처럼 대응 토큰이 없거나 값이 다른 것은 대상 아님). 범위를 문서에 명시할지 B5 전까지 정한다.
