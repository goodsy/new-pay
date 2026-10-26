import type { ReactNode } from 'react'

/** 공통 레이아웃. 머리글에는 제목과 (로그인 뒤) 표시명·역할별 메뉴·로그아웃 — 메뉴는 표시만이고 권한 판정은 서버. */
export function AppLayout({ children, header }: { children: ReactNode; header?: ReactNode }) {
  return (
    <div className="app">
      <header className="app-header">
        <h1>PayCore 관리자</h1>
        {header}
      </header>
      <main className="app-main">{children}</main>
    </div>
  )
}
