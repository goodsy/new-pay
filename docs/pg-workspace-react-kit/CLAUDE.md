# PG Workspace UI — AI 코딩 에이전트 작업 규칙

이 파일은 React 프로젝트 루트(또는 해당 프런트엔드 모듈 루트)에 복사해 사용한다.
상세 근거는 `GUIDE.md`(디자인 시스템·레이아웃·트레이드오프), `SCREENS.md`(화면별 명세)를 따른다.

## 참조 우선순위

1. `reference/original/` — 원본 소스(진실 원천). 동작·문구·마크업이 애매하면 `app.js` 의 해당 함수를 읽는다.
2. `reference/screenshots/` — 원본 화면 캡처. 시각 비교 기준.
3. `react-starter/src/` — 검증된 React 이식 구현. 새 화면은 여기 컴포넌트를 재사용한다.

## 필수 규칙

- `styles/pg-workspace.css` 는 **수정 금지**. 보정은 `styles/overrides.css` 에만 추가하고 이유를 주석으로 남긴다. import 순서는 pg-workspace.css → overrides.css.
- 원본 CSS 가 요소/ID 선택자를 사용하므로 다음을 바꾸지 않는다:
  `aside`, `nav`, `header`, `main`, `footer`, `table/th/td`, `#crumb`, `#overlay`, `#toast`, `#results`, `#maprows`, `#reason`, `#globalq`.
- 클래스명은 원본과 동일하게 쓴다 (`heading`, `eyebrow`, `card`, `cardtop`, `tabs`/`on`, `filters`, `tablewrap`, `tablebottom`, `tag`+tone, `btn`/`primary`, `banner`, `stats`/`stat`, `split`, `task`, `pipeline`/`pipe`, `drawer`, `modal`, `timeline`, `detailgrid`, `notice`, `formrow`, `wizard`/`current`, `mapnote`, `rolebar`, `matrix`, `quick`, `amount`, `subtle`, `empty`).
- 새 색상·크기 값을 임의로 만들지 않는다. GUIDE.md §2 토큰 표의 값만 사용한다.
- 상태 태그는 `StatusBadge` 로만 렌더링한다 (색상 규칙 `lib/status.ts` 단일화).
- 금액/수량 컬럼은 `.amount`(우측정렬 + tabular-nums).
- 오버레이는 `useWorkspace().openDrawer / openModal / closeOverlay` 만 사용한다. 동시에 1개만 열린다.
- 문구의 전각 공백(U+3000 `　`)은 원본 그대로 유지한다 (crumb, 버튼 라벨 등).
- 권한·승인·지급 관련 UI 는 시연용이다. 실제 접근 통제/검증 로직을 프런트에만 두지 않는다.

## 새 업무 화면 추가 절차

1. 기존 8개 모듈과 같은 형태(목록 + 탭 + 상세)면 `data/modules.ts` 에 설정만 추가하고 `ModuleId` 타입·`menus.ts` 에 메뉴를 등록한다. 페이지 컴포넌트는 만들지 않는다.
2. 형태가 다르면 `pages/` 에 새 페이지를 만들되 `PageHeading` → (`Banner`) → `Card` 순서의 원본 구성 규칙을 따른다.
3. 상세가 필요하면 `features/DetailPanel.tsx` 의 `ModuleBody` 에 case 를 추가한다.

## 완료 전 검증

- `npm run build` 통과 (TypeScript strict)
- 해당 화면을 1440 / 1000 / 600px 에서 확인하고 `reference/screenshots/` 와 비교
- 행 클릭·Enter → Drawer, ESC·배경 클릭 → 닫힘, ⌘K/Ctrl+K → 검색
- 변경 사항이 원본과 달라지면 GUIDE.md §8 표에 기록
