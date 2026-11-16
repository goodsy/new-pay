# PG Workspace → React 이식 지침서

> 대상 원본: `https://pg-ops-workspace-kim.packedchili2.chatgpt.site/` (정적 HTML 1 + CSS 1 + JS 1 + legacy.json)
> 원본 소스는 `reference/original/` 에 **바이트 단위 동일**하게 보존 (SHA-256 검증 완료).

## 0. 결론

- **CSS는 원본 파일을 그대로 전역 import** 하고, 마크업은 원본과 **같은 태그·클래스명**으로 React 컴포넌트화한다. 이 방식이 원본과 픽셀 동일성을 보장하는 가장 싼 방법이다.
- 참조 구현 `react-starter/` 로 11개 화면 + 7종 오버레이를 이식했고, 원본과 비교 검증 결과는 아래와 같다.
  - 스크린샷 비교(1440px, 18장): 차이 0~222px, 전부 글자 안티앨리어싱 수준 (레이아웃·색상 차이 0)
  - 동작 비교(Playwright 7개 시나리오, 37단계): `main` 텍스트 · 오버레이 텍스트 · 토스트 **전부 일치**
- 기존 React 프로젝트에 넣을 때 가장 큰 리스크는 **전역 요소 선택자 충돌**(`input`, `table`, `header`, `main`, `aside`, `nav`, `p`, `h1`). §3.3 대응 방안 중 하나를 반드시 선택할 것.

---

## 1. 패키지 구성

```
pg-workspace-react-kit/
├─ GUIDE.md                  ← 이 문서 (디자인 시스템·레이아웃·컴포넌트·이식 규칙)
├─ SCREENS.md                ← 화면별 명세 (11개 화면 + 오버레이 + 반응형)
├─ CLAUDE.md                 ← AI 코딩 에이전트용 작업 규칙 (프로젝트 루트에 복사해 사용)
├─ reference/
│  ├─ original/              ← 원본 index.html / style.css / app.js / legacy.json (수정 금지)
│  └─ screenshots/           ← 원본 화면 캡처 (1440px 기준 + 1000px/600px 홈)
└─ react-starter/            ← 참조 구현 (Vite 8 + React 19 + TS + react-router-dom 7)
   └─ src/
      ├─ styles/pg-workspace.css   원본 CSS (포맷만 정리, 내용 무변경)
      ├─ styles/overrides.css      React 이식용 보정 (현재 1개 규칙)
      ├─ data/        types, menus, modules(샘플 데이터·탭 필터), legacy(165개 메뉴)
      ├─ lib/         status(상태→색상), csv(내보내기)
      ├─ store/       WorkspaceProvider (rows · overlay · toast · go)
      ├─ components/layout/  AppLayout, Sidebar, Header
      ├─ components/ui/      Button, Tag/StatusBadge, PageHeading, Card/CardTop, Banner,
      │                      StatGrid, Tabs, DataTable, Pipeline, Panel(Timeline/DetailGrid/Notice/FormRow/WizardSteps)
      ├─ features/    DetailPanel, MerchantWizard, SourceForm, GlobalSearch, AccessPanels
      └─ pages/       HomePage, ModulePage(8개 업무 공용), AccessPage, MenuMapPage
```

실행:

```bash
cd react-starter
npm install
npm run dev      # http://localhost:5173/home
npm run build    # tsc -b && vite build (검증 완료)
```

---

## 2. 디자인 토큰 (원본에서 추출한 **최종 적용값**)

원본 CSS는 상단 기본값 → 하단 `/* Readable operations typography */` 블록이 덮어쓰는 2단 구조다. 아래 표는 **덮어쓴 뒤 실제로 화면에 적용되는 값**이다.

### 2.1 색상

| 용도 | 값 | 사용처 |
|---|---|---|
| Primary | `#3182f6` (`--blue`) | primary 버튼 배경, 탭 밑줄, 위저드 진행선, 체크박스 accent, 브랜드 아이콘 |
| Primary Text | `#1d4ed8` | 활성 메뉴 글자, 활성 탭 글자, `.tag.blue`, `.count`, `.positive` |
| Primary Soft | `#edf4ff` | 활성 메뉴 배경, 배너 배경, `.tag.blue` 배경, `.count` 배경 |
| Banner Border | `#e0ebfd` | `.banner` 테두리 |
| Page BG | `#f7f9fc` | `body` |
| Surface | `#ffffff` | aside, header, card, stat, drawer, modal |
| Surface Subtle | `#f6f8fb` / `#f5f7fa` / `#f9fafc` | 워크스페이스 선택·notice / 검색 버튼 / 테이블 헤더 |
| Row Hover | `#f8fbff` | `.task:hover`, `tr.clickable:hover` |
| Line | `#e2e8f0` (`--line`, 덮어쓴 값) | 카드·aside·header 경계, 탭 하단선 |
| Line Soft | `#f1f4f7` / `#f3f5f8` | 테이블 행 구분 / task 구분 |
| Control Border | `#e3e8ef`(btn) / `#e2e8ef`(input, select) | |
| Heading | `#0f172a` | h1, h2, 브랜드, stat 값, td strong |
| Body | `#334155` | 본문, td, input |
| Secondary | `#475569` | 메뉴, 탭, th, 링크 버튼, label |
| Muted | `#64748b` (`--muted`) | 설명, hint, small, footer, placeholder |
| Eyebrow | `#526984` | `.eyebrow` |
| Focus Ring | `#aecfff` 3px (button/a) · `#bcd5ff` 2px (input) | |
| Overlay Dim | `#18263d55` + `backdrop-filter: blur(2px)` | |
| Drawer Shadow | `-10px 0 40px #17325122` | |
| Toast BG | `#293c53` | |

### 2.2 상태 태그 (`.tag`)

| tone | 배경 | 글자 | 자동 매핑 규칙 (`lib/status.ts`) |
|---|---|---|---|
| green | `#ebf9f2` | `#16734f` | 상태에 `완료` 또는 `운영 중` 포함 |
| orange | `#fff3e8` | `#9a4c0c` | `보류` · `반송` · `차이` · `필요` 포함 |
| blue | `#edf4ff` | `#1d4ed8` | 그 외 전부 (심사 중, 승인 대기, 입금 대기 …) |
| red | `#fff0f1` | `#b42339` | 정의만 존재, 자동 매핑 없음 |
| default | `#f1f4f8` | `#475569` | 클래스 `tag` 단독 |

> 판정 순서가 의미를 가진다. 예: `지급 완료`는 green, `검증 완료`도 green. 새 상태값 추가 시 이 규칙을 먼저 확인할 것.

기타 고정 색: 사이드바 뱃지 `em` `#fff0ed`/`#b42339`, task 아이콘 기본 `#fff3ea`/`#e69758`, 파이프라인 번호 `#ebf6f0`/`#39a177`.

### 2.3 타이포그래피

- Font: `-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans KR', 'Malgun Gothic', sans-serif` / 기본 `14px`, `line-height: 1.5`
- 숫자: stat 값, `.amount` 는 `font-variant-numeric: tabular-nums` (금액 자릿수 정렬 필수)

| 요소 | size / weight | 비고 |
|---|---|---|
| 브랜드 | 21px / 700 | letter-spacing -.7px |
| h1 (페이지 제목) | 28px / 700 | letter-spacing -1px (≤720px: 24px) |
| Drawer h2 | 24px / 700 | |
| Stat 값 | 29px / 700 | letter-spacing -1px (≤1100px: 24px) |
| Card 제목 (`.cardtop h2`) | 17px / 700 | |
| 메뉴 · 탭 · td · input | 14px / 500(메뉴·탭), 400 | 활성 시 700 |
| Banner · task · pipe 제목, `.stitle` | 14px / 600 | |
| th · 버튼 · label | 13px / 600 (label 500) | |
| Tag · count | 12px / 600 | |
| 설명 · hint · small · notice | 12px / 400 | color Muted |
| Eyebrow | 11px / 600 | letter-spacing 1.6px, 대문자 |
| Footer | 11px | |

### 2.4 Radius · 간격

| radius | 대상 |
|---|---|
| 4–5px | `.count`, `.tag`, 메뉴 뱃지 |
| 7px | input, select, 검색 버튼 |
| 8px | `.btn`, 메뉴 버튼 |
| 9px | 워크스페이스 선택, `.symbol`, `.taskicon`, `.notice`, quick 버튼 |
| 10px / 11px | toast / banner |
| 12px / 13px | `.stat` / `.card` |
| 15px | `.modal` |

주요 패딩: main `36px 38px 25px` · header 좌우 38px · cardtop `22px 23px 17px` · filters `20px 23px` · th `13px 20px` · td `17px 20px` · drawer 32px.

---

## 3. 레이아웃

### 3.1 셸 구조

```
<aside>  position:fixed; width:236px; height:100vh
<div.shell>  margin-left:236px
   <header>  height:76px; padding 0 38px; flex(crumb · search(margin-left:auto, 275px) · tag)
   <main>    max-width:1500px; margin:auto; padding:36px 38px 25px
   <footer>
<div#overlay.overlay>   (오버레이 열렸을 때만 렌더)
<div#toast>
```

- 원본 CSS 가 **요소 선택자**(`aside`, `header`, `main`, `footer`, `nav`)를 쓰므로 이 태그를 `div` 로 바꾸면 스타일이 깨진다.
- `#crumb`, `#toast`, `#overlay` 는 **id 선택자**로 스타일·반응형 규칙이 걸려 있어 id 를 유지해야 한다.

### 3.2 반응형

| 구간 | 변경 사항 |
|---|---|
| > 1100px | 기본 (aside 236px, stats 4열, split 2열 1.55fr : 1fr) |
| ≤ 1100px | aside 205px, header/main 좌우 패딩 23px, **split 1열**, stat 값 24px, stats gap 10px |
| ≤ 720px | aside 가 상단 가로 바로 전환(relative, 메뉴 가로 스크롤, 아이콘·뱃지·그룹라벨·워크스페이스·하단 숨김), header 60px, crumb·환경태그 숨김, 검색 100%, h1 24px, `.actions` 세로, **stats 2열**, banner 설명 숨김, filters·quick 줄바꿈 |

### 3.3 기존 React 프로젝트에 넣을 때 CSS 전략 (트레이드오프)

| 방식 | 원본 동일성 | 이식 비용 | 충돌 위험 | 유지보수 | 비고 |
|---|---|---|---|---|---|
| **A. 원본 CSS 전역 import (참조 구현 채택)** | 100% | 최소 (파일 복사) | **높음** — `input`, `table`, `p`, `h1` 등 전역 요소 규칙 | 낮음 — 하드코딩 색상 다수 | 신규 프로젝트 / 이 화면 전용 앱이면 최선 |
| B. 루트 prefix 스코프 (`.pgw input {…}`) | 100% | 낮음 — `postcss-prefix-selector` 로 빌드 시 변환 | 낮음 | A 와 동일 | 기존 프로젝트에 끼워 넣을 때 권장. `:root`, `body`, `@media` 내부 선택자 처리 규칙 확인 필요 |
| C. CSS Modules | 100% 가능 | 중간 — 클래스 → `styles.x` 치환, 요소 선택자는 클래스로 재작성 필요 | 없음 | 중간 | Vite 기본 지원, 추가 라이브러리 없음 |
| D. Tailwind 재작성 | 근사치 | **높음** — 원본 값이 임의값(13px, #8b95a1 등)이라 `[]` arbitrary value 다수 | 없음 | 토큰화되면 높음 | 러닝커브 존재. 팀이 이미 Tailwind 를 쓰는 경우만 검토 |
| E. 런타임 CSS-in-JS (styled-components, emotion) | 근사치 | 높음 | 없음 | 중간 | 런타임 스타일 주입 비용이 있어 **성능 우선 기준에서 비권장** |

**권장 순서**: 신규 앱이면 A → 안정화 후 하드코딩 색상을 §2 토큰으로 `:root` 변수화. 기존 앱에 병합이면 B.

### 3.4 B 방식 적용 예 (Vite)

```bash
npm i -D postcss-prefix-selector
```

```js
// postcss.config.js
import prefixer from 'postcss-prefix-selector';
export default {
  plugins: [
    prefixer({
      prefix: '.pgw',
      includeFiles: [/pg-workspace\.css$/, /overrides\.css$/],
      transform(prefix, selector, prefixed) {
        if (selector === ':root' || selector === 'body') return prefix; // 루트 래퍼로 대체
        if (selector === '*') return `${prefix}, ${prefix} *`;            // box-sizing 하위 전체 유지
        return prefixed;
      },
    }),
  ],
};
```

```tsx
<div className="pgw">{/* AppLayout */}</div>
```

위 설정은 postcss 로 변환 결과를 확인했다(`:root`/`body` → `.pgw`, `@media` 내부 선택자 prefix 적용, `#toast`·`#overlay` 는 래퍼 내부에 렌더되므로 정상 매칭). 단, 변환 후 화면 스크린샷 비교는 하지 않았다.

주의: `body { background; font }` 가 `.pgw` 로 이동하므로 `.pgw { min-height: 100vh }` 를 overrides.css 에 추가해야 배경이 화면 전체를 덮는다. `aside { position: fixed }` 는 그대로 동작한다.

---

## 4. 컴포넌트 매핑 (원본 함수 → React)

| 원본 (app.js) | React | 루트 마크업 / 클래스 | 핵심 규칙 |
|---|---|---|---|
| `drawNav()` + index.html aside | `layout/Sidebar` | `aside > a.brand, .workspace, nav, .bottom` | 그룹 라벨은 index 1 앞 `PAYMENT OPERATIONS`, index 7 앞 `WORKSPACE`. 활성은 `button.active` |
| index.html header | `layout/Header` | `header > #crumb, button.search, .tag.blue` | crumb 형식 `워크스페이스　/　{메뉴명}` (전각 공백 U+3000) |
| `render()` | `App` + `AppLayout` | `aside + .shell` | URL `/:pageId` |
| `head()` | `ui/PageHeading` | `.heading` | eyebrow 기본 `PAYMENT OPERATIONS` |
| `stats()` | `ui/StatGrid` | `.stats > .stat` | 값+단위 `<strong>값<small>단위</small></strong>` |
| `.banner` 마크업 | `ui/Banner` | `.banner > .symbol, div, button` | |
| `.card/.cardtop` | `ui/Card`, `ui/CardTop` | `section.card > .cardtop` | 카운트 칩 `.count` |
| 탭 마크업 | `ui/Tabs` | `.tabs > button(.on)` | |
| `table()` | `ui/DataTable` | `.tablewrap > table` + `.tablebottom` | 6칸 튜플 → 5컬럼 + `›` (§5) |
| `badge()` | `ui/StatusBadge` | `span.tag.{tone}` | §2.2 |
| `pipeline()` | `ui/Pipeline` | `.pipeline > .pipe` | |
| `.timeline / .detailgrid / .notice / .formrow / .wizard` | `ui/Panel` 내 각 컴포넌트 | 동일 | |
| `panel()` / `openSearch()` / `closePanel()` | `WorkspaceProvider.openDrawer / openModal / closeOverlay` + `AppLayout.OverlayHost` | `.overlay > section.drawer \| section.modal > button.close` | 배경 클릭·ESC 닫힘, 오버레이 1개만 존재 |
| `toast()` | `WorkspaceProvider.toast` | `#toast(.show)` | 2.8초 |
| `go()` | `WorkspaceProvider.go` | — | 오버레이 닫기 + navigate + scrollTo(0,0) |
| `detail()` | `features/DetailPanel` | drawer | 모듈별 본문 분기 (SCREENS.md §3) |
| `wizard()` | `features/MerchantWizard` | drawer | 단계별 `<form key={step}>` 로 네이티브 검증 유지 |
| `sourceForm()` | `features/SourceForm` | drawer | |
| `searchAll()` | `features/GlobalSearch` | modal | 최대 20건 |
| `exportRows()` | `lib/csv.downloadRowsCsv` | — | BOM + CRLF (Excel 한글 대응) |
| `destination()` | `data/legacy.legacyDestination` | — | **판정 순서 유지 필수** |

---

## 5. 데이터 계약

```ts
// 목록 1행 = 6칸 고정 튜플
type Row = [대표명, 식별키, 컬럼1, 컬럼2, 상태, 마지막컬럼];
// 화면 컬럼(cols) 5개: cols[0] = 대표명(strong) + 식별키(small) 2줄 셀
//                     cols[1..3] = 컬럼1, 컬럼2, 상태(태그)   cols[4] = 마지막컬럼(.amount 우측정렬)
```

- `식별키(r[1])` 가 상세 Drawer 조회 키이자 React `key`. **중복 불가**.
- 실제 API 연동 시: 서버 DTO → `Row` 로 변환하는 어댑터를 `data/` 에 두고 화면 컴포넌트는 그대로 둔다. 튜플 대신 객체 타입으로 바꾸려면 `DataTable` 의 `columns` 정의(접근자 배열)로 리팩터링하는 것이 정석이다(§7).

---

## 6. 상태 · 라우팅

### 6.1 라우팅

| 원본 | React |
|---|---|
| `#home` … `#map` | `/home` … `/map` (`BrowserRouter`) |
| 잘못된 해시 → home | `<Navigate to="/home" replace />` |
| 페이지 이동 시 tab/검색/상태 초기화 | `<ModulePage key={moduleId} />` 재마운트 |

트레이드오프:

- **BrowserRouter (채택)**: URL 깔끔. 대신 서버가 모든 경로를 `index.html` 로 forward 해야 한다. Spring Boot 정적 서빙 예:

  ```kotlin
  @Controller
  class SpaForwardController {
      @GetMapping("/{path:^(?!api|assets)[^\\.]*}")
      fun forward(): String = "forward:/index.html"
  }
  ```

- **HashRouter**: 서버 설정 불필요, 원본 URL(`#merchant`) 그대로. 대신 서버 로그·접근 통제에 경로가 남지 않는다. `main.tsx` 의 `BrowserRouter` → `HashRouter` 한 줄 교체로 전환 가능.

### 6.2 상태

| 종류 | 현재 (참조 구현) | 실서비스 권장 | 트레이드오프 |
|---|---|---|---|
| 목록 데이터 | Context `rows` (메모리) | **TanStack Query** (서버 상태 캐시) | 러닝커브 중간, 레퍼런스 매우 많음. 캐시·재조회·로딩 상태를 직접 구현하지 않아도 됨. Context 유지 시 의존성 0 이지만 캐시/동시성 직접 구현 필요 |
| 오버레이·토스트 | Context | Context 유지 | 전역 UI 상태가 2개뿐이라 Zustand 등 도입 이득 작음 |
| 화면 로컬 (탭, 검색어, 상태 필터) | `useState` | URL 쿼리(`?tab=1&q=`)로 승격 검토 | 새로고침·링크 공유 가능해지나 코드량 증가 |
| 폼 | 비제어 + FormData + 네이티브 검증 | 필드 많아지면 react-hook-form | 원본 동작과 동일 / 검증 규칙 복잡해지면 RHF 가 유리 |

참고: 프런트엔드 라이브러리이므로 Spring 공식 지원 여부는 해당 없음.

---

## 7. 고도화 시 트레이드오프

| 항목 | 선택지 | 성능 | 복잡도 | 비고 |
|---|---|---|---|---|
| 대량 목록 | (a) 서버 페이지네이션 (b) 클라이언트 가상 스크롤(TanStack Virtual) | (a) 네트워크·메모리 최소 (b) 수천 행 렌더 가능 | (a) API 계약 필요 (b) 행 높이 고정 전제 | 정산·거래 데이터는 (a) 기본, 운영자 엑셀식 탐색이 필요하면 (b) 병행 |
| 테이블 추상화 | 현재 튜플 기반 `DataTable` 유지 vs 컬럼 정의 기반(`columns: {header, accessor, align}[]`) | 동일 | 후자가 초기 작업↑ | 모듈마다 컬럼 수가 달라지면 후자로 전환 |
| 하드코딩 색상 → 토큰 | `:root` 변수화 | 동일 | 일회성 작업 | 다크모드·브랜드 변경 계획 있으면 우선 진행 |
| CSV 내보내기 | 현재 클라이언트 생성 | 화면 데이터만 | 낮음 | 전체 기간 대량 다운로드는 서버 생성(스트리밍)으로 분리해야 함 |
| 권한 매트릭스 | 현재 UI 시연 | — | — | **실제 접근 통제는 반드시 서버**(원본 고지 문구와 동일) |

---

## 8. 원본과의 차이 (의도적)

| 항목 | 원본 | React | 시각 영향 |
|---|---|---|---|
| 페이지 진입 | `#merchant` | `/merchant` | 없음 |
| 탭 접근성 | 속성 없음 | `role="tablist/tab"`, `aria-selected` 추가 | 없음 |
| 사이드바 현재 메뉴 | class 만 | `aria-current="page"` 추가 | 없음 |
| 오버레이 | 항상 DOM 존재 + `hidden` | 열릴 때만 렌더 | 없음 |
| 토스트 | `style.display` 직접 조작 | `.show` 클래스 (overrides.css) | 없음 |
| 데모 데이터 추가 | 전역 객체 `unshift` | Context `addRow` (새로고침 시 초기화, 원본과 동일) | 없음 |

정적 요소(원본도 동작 없음): 날짜 버튼(`2026. 11. 02 ⌄`, 클릭 시 통계로 이동만), 워크스페이스 선택, 페이지네이션 `1 / 1`.

---

## 9. 검증 체크리스트 (화면 추가·수정 시)

1. `npm run build` 통과 (tsc strict)
2. `reference/screenshots/` 의 같은 화면과 1440px 에서 육안 비교 → 1100px, 720px 이하에서 §3.2 동작 확인
3. 상태 태그 색이 §2.2 규칙대로 나오는지
4. 행 클릭 / Enter 로 Drawer 열림, ESC·배경 클릭으로 닫힘, ⌘K(Ctrl+K) 검색 열림
5. 금액 컬럼 우측정렬 + tabular-nums
6. `pg-workspace.css` 를 직접 수정하지 않았는지 (변경은 `overrides.css`)
