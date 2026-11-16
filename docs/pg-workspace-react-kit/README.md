# PG Workspace React Kit

원본 프로토타입(`pg-ops-workspace-kim.packedchili2.chatgpt.site`)의 화면 구성·CSS·레이아웃을 React 프로젝트에 적용하기 위한 지침서와 참조 파일 묶음.

| 파일 | 용도 |
|---|---|
| `GUIDE.md` | 이식 지침서 — 디자인 토큰, 레이아웃/반응형, 컴포넌트 매핑, 데이터 계약, 라우팅·상태, CSS 전략 트레이드오프, 검증 체크리스트 |
| `SCREENS.md` | 화면별 명세 — 공통 프레임, 11개 화면, Drawer/Modal 7종, 반응형 |
| `CLAUDE.md` | AI 코딩 에이전트용 작업 규칙 (프로젝트 루트에 복사) |
| `reference/original/` | 원본 index.html · style.css · app.js · legacy.json (SHA-256 원본 일치) |
| `reference/screenshots/` | 원본 화면 캡처 18장 |
| `react-starter/` | 참조 구현 (Vite 8 · React 19 · TypeScript · react-router-dom 7) |

```bash
cd react-starter && npm install && npm run dev   # → http://localhost:5173/home
```

검증 결과: 원본 대비 스크린샷 18장 레이아웃·색상 차이 0(글자 안티앨리어싱 수준만 차이), 동작 시나리오 7개(37단계) 텍스트·오버레이·토스트 일치.
