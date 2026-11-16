# PG 관리자 화면 React 재현 지침

## 목적과 기준
URL: https://pg-ops-workspace-kim.packedchili2.chatgpt.site
기준 소스 커밋: f429e4e70892b12cd96171995f29c36323937669 (글자 선명도 개선본)
목적은 새로운 디자인 제안이 아니라 기존 화면의 시각적 재현이다. 프로젝트는 React이며 기존 빌드 도구, 라우터, TypeScript 사용 여부를 먼저 확인하고 그대로 따른다.

reference/index.html은 공통 골격, reference/style.css는 정확한 시각 기준, reference/app.js는 화면별 마크업·샘플 데이터·동작 기준, reference/legacy.json은 기존 메뉴 대응 데이터다. URL에 접속할 수 없어도 이 네 파일로 기준 화면을 실행할 수 있다. reference 디렉터리에서 `python3 -m http.server 8080` 실행 후 브라우저로 확인한다. 파일 더블클릭은 JSON fetch가 실패할 수 있으므로 HTTP 서버를 사용한다.

## 가장 중요한 구현 규칙
1. 먼저 원본 화면을 실행해서 확인한다. URL을 보지 못했다면 봤다고 보고하지 않는다.
2. 원본 CSS 전체를 순서 그대로 적용한다. 파일 끝 Readable operations typography 구역은 최종 개선값이다. 앞쪽 선언만 복사하면 흐린 이전 화면이 된다.
3. 원본 DOM 계층과 class 이름을 보존하며 JSX로 옮긴다. 불필요한 wrapper div를 추가하지 않는다. flex/grid 직계 자식 관계가 달라지면 배치도 달라진다.
4. 기존 UI 라이브러리의 기본 Card, Table, Sidebar 스타일로 대체하지 않는다. 프로젝트가 Tailwind/MUI/Ant Design/shadcn을 사용해도 이 화면은 기준 CSS 값이 최종 적용되어야 한다. reset/preflight와 전역 h1, button, table, svg 스타일 충돌을 확인한다.
5. 새로운 폰트, 아이콘, 그림자, 간격, 문구를 임의로 도입하지 않는다. 원본 SVG path를 사용한다. Pretendard 등으로 교체하는 작업은 별도 승인된 변경으로 분리한다.
6. 기능과 데이터 연동 전에 원본 샘플 데이터로 화면을 맞춘다. 데이터 길이가 바뀌면 레이아웃 비교가 불가능해진다.
7. 원본 app.js를 React에서 그대로 실행하거나 innerHTML로 삽입하지 않는다. 템플릿은 JSX, 상태는 React state, onclick은 onClick, class는 className으로 변환한다. 사용자 입력에 dangerouslySetInnerHTML을 사용하지 않는다.
8. 재현 과정에서 사이트를 새로 디자인하거나 도메인 메뉴를 재편하지 않는다. 원본의 레거시 용어는 시각 비교용으로 보존하고, PayCore Business/MerchantAccount/AgentSettlement 용어 적용은 재현 완료 후 별도 변경한다. Business와 MID를 하나의 모델로 합치지 않는다.

## 화면 골격과 순서
공통: aside → shell(header, main, footer), shell 바깥에 overlay와 toast.
사이드바: 브랜드 → 워크스페이스 표시 → 운영 홈 → PAYMENT OPERATIONS 그룹 → WORKSPACE 그룹 → 하단 메뉴 개편 지도 및 사용자.
메뉴 순서: 운영 홈 / 사업자·가맹점 / 원천사·기준정보 / 결제 관리 / 매입·대사 / 정산·지급 / 리스크 관리 / 통계·리포트 / 운영·고객지원 / 계정·권한.
왼쪽 메뉴는 1단 구조다. 화면별 하위 기능은 본문 탭으로 표현하며 2~3단 트리 메뉴로 변경하지 않는다.

운영 홈 main 내부 순서:
1. eyebrow, 제목, 설명 / 오른쪽 날짜 버튼과 사업자 등록 버튼
2. 정산 승인 안내 배너
3. KPI 4개: 오늘 승인 금액, 오늘 승인 건수, 지급 예정액, 확인이 필요한 업무
4. 2열: 지금 처리할 업무 / 오늘의 정산 흐름
5. 전체 폭 최근 거래 표
6. 바로가기 버튼 4개
7. shell의 footer
다른 화면은 app.js의 conf 및 화면 생성 함수에 정의된 탭, 컬럼, 순서를 그대로 옮긴다. 홈 화면만 만들고 완료로 보고하지 않는다.

## 고정 배치값 (px)
| 항목 | 데스크톱 기본값 |
|---|---|
| aside | fixed, 폭 236, 높이 100vh, padding 30px 18px |
| shell | margin-left 236 |
| header | 높이 76, 좌우 padding 38, flex gap 22; 원본처럼 일반 문서 흐름 |
| 검색 버튼 | 폭 275 |
| main | max-width 1500, margin auto, padding 36px 38px 25px; border-box |
| heading | 아래 간격 27, 좌우 justify-between |
| banner | padding 18px 21px, gap 15, 아래 간격 24, radius 11 |
| stats | repeat(4,1fr), gap 16, 아래 간격 26 |
| stat | padding 21, radius 12 |
| split | 1.55fr 1fr, gap 22, 아래 간격 24 |
| card | radius 13, border 1px, overflow hidden |
| cardtop | padding 22px 23px 17px |
| task | padding 16px 23px, gap 13 |
| tabs | gap 26, 좌우 padding 23 |
| filters | padding 20px 23px, gap 9 |
| th | padding 13px 20px |
| td | padding 17px 20px |
| drawer | 오른쪽, 폭 550, max-width 100%, padding 32 |
| modal | 폭 650, max-width 95%, margin-top 90, max-height 75vh |

카드/테이블 행의 높이를 임의로 고정하지 않는다. 원본 폰트와 padding, 내용이 결정하도록 한다. 카드에 새 box-shadow를 넣지 않는다.

## 최종 타이포그래피와 색상
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans KR', 'Malgun Gothic', sans-serif.
body 14px / line-height 1.5. 문단은 원본 p의 1.7 적용.
| 항목 | 크기 / 굵기 / 색 |
|---|---|
| h1 | 28px / 700 / #0f172a, letter-spacing -1px |
| 카드 제목 | 17px / 700 / #0f172a |
| KPI 숫자 | 29px / 700 / #0f172a |
| 메뉴 | 14px / 500 / #475569 |
| 활성 메뉴 | 14px / 700 / #1d4ed8, 배경 #edf4ff |
| 표 제목 | 13px / 600 / #475569 |
| 표 데이터 | 14px / 기본 400 / #334155 |
| 표 강조 및 금액 | 600, 금액은 우측 정렬 |
| 보조 설명 | 12px / #64748b |
| 버튼 | 13px / 600 |
| 태그 | 12px / 600 |
| 탭 | 14px / 500, 활성 700 |

페이지 배경 #f7f9fc, 카드/사이드바 #fff, 기본 선 #e2e8f0, 주요 버튼 #3182f6 + 흰 글자. 상태별 색은 CSS의 최종 .tag 규칙을 따른다. 숫자는 font-variant-numeric:tabular-nums. 표는 white-space:nowrap, tablewrap에서 가로 스크롤.
폰트 스택은 OS별 실제 폰트가 다를 수 있다. 정확한 픽셀 비교는 같은 OS, 브라우저, 확대율, 폰트 로딩 조건에서 수행한다.

## 반응형 (원본 조건 보존)
- 1100px 이하: aside 205, shell margin-left 205, header/main 좌우 padding 23, split 1열, KPI 숫자 24, stats gap 10, stat padding 16.
- 720px 이하: aside relative, 폭 100%, 높이 auto, padding 16. workspace/bottom/nav label 숨김. nav 가로 flex 및 overflow. 메뉴 아이콘과 알림 숫자 숨김. shell margin-left 0.
- 720px 이하 header 높이 60, padding 10px 15px, breadcrumb/헤더 태그 숨김, 검색 폭 100%.
- 720px 이하 main padding 25px 16px, h1 24, actions 세로, KPI 2열, banner 설명 숨김, filters 줄바꿈, quick 버튼 flex-basis 40%.
- 기본값을 768/1024로 임의 변경하지 않는다. 원본은 모바일 햄버거 메뉴가 아니라 가로 메뉴다.

## React 구성 권장안
AppShell: Sidebar, Header, main children, Footer, OverlayHost, Toast.
공통 컴포넌트: PageHeading, ActionButton, StatusBadge, StatCard, Card, Tabs, Filters, DataTable, Drawer, Modal.
페이지: DashboardPage, MerchantPage, SourcePage, PaymentPage, AcquisitionPage, SettlementPage, RiskPage, ReportPage, OperationsPage, AccessPage, MenuMapPage.
페이지마다 기본 태그/클래스 계층을 그대로 유지한다. React Fragment를 이용하면 불필요한 div를 피할 수 있다.
page/tab/query/status/form/wizardStep/overlay/toast를 적절한 컴포넌트 상태로 옮긴다. 기존 라우터가 있으면 활용하고 활성 메뉴를 URL과 동기화한다. 화면 이동 시 탭/조회조건/패널 초기화는 원본 go 함수 기준이다.
legacy.json은 프로젝트 자산 경로에 맞춰 읽고 로딩/실패 상태를 처리한다. 원본 SVG와 샘플 데이터도 추출해서 재사용한다. 새 상태관리 라이브러리를 이 작업만을 위해 추가하지 않는다.
원본에 없는 실제 지급/채번/API 요청은 구현하지 않는다. 일부 탭은 설계 수준이라는 사실을 유지하고, 데모 버튼을 운영 기능처럼 표시하지 않는다. 키보드 포커스·닫기·접근성 레이블을 보완하되 배치는 보존한다.

## 작업 순서와 검증
1. 기존 React 프로젝트와 스타일 초기화/라우팅 구조를 읽는다.
2. reference를 별도 로컬 서버에서 실행한다.
3. 공통 Shell과 원본 CSS → 홈 → 공통 목록 → 각 메뉴 → drawer/modal → 반응형 순서로 옮긴다.
4. 원본과 React를 같은 viewport에서 캡처해 비교한다: 1440×900, 1280×800, 1024×768, 390×844. 경계값 1100/1101, 720/721도 확인한다.
5. 데스크톱 사이드바 경계 x=236, header 아래 y=76, main 시작 x=274(최대폭 중앙정렬 전)를 확인한다. 화면이 넓어 max-width가 적용되면 중앙정렬 오프셋을 반영한다.
6. 비교 순서: 바깥 골격 → 섹션 시작 위치 → 카드 너비/간격 → 폰트/줄바꿈 → 표 정렬 → 아이콘/색상.
7. 같은 샘플 데이터로 홈, 결제 목록, 거래 drawer, 등록 wizard, 정산, 권한, 메뉴 지도 및 모바일 홈을 비교한다. 자동 이미지 diff/반투명 겹치기를 사용할 수 있으면 사용한다. 폰트 안티앨리어싱 차이를 레이아웃 오류와 구분한다.
8. 메뉴/탭 전환, 필터, 검색, 상세 열기/닫기, 등록 단계, 권한 데모, 메뉴 지도 JSON 로딩을 확인한다. console 오류와 페이지 전체의 의도치 않은 가로 넘침이 없어야 한다. 표 내부 가로 스크롤은 허용한다.
9. 빌드/타입 검사를 실행한다. 확인하지 못한 화면은 명시한다. 재현을 완료했다고 단정하지 말고 비교 결과와 남은 차이를 보고한다.

## 완료 기준
원본 섹션 순서와 DOM 계층, CSS 수치, 최종 타이포그래피, 메뉴/탭 순서가 일치한다. 임의 테마 변경이 없다. 주요 화면과 반응형의 비교 검증이 완료된다. 샘플 데모와 실제 기능의 경계가 유지된다. 이 묶음은 검증된 원본 참고자료와 구현 지침이며, 완성된 React 앱은 아니다.
