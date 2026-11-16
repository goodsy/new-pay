import type { ReactNode } from 'react'
import { Logo } from '../components/Logo'

/**
 * 공통 레이아웃(ADMIN-UI-WORKSPACE-001 — 참고 시안 'pg-ops-workspace' 골격과 class 이름 그대로, 값은 workspace.css).
 * 로그인 뒤: `aside`(브랜드 → 워크스페이스 표시 → 1단 메뉴 `nav#nav` → 아래 계정·로그아웃) 다음 `.shell`(header 76px: 경로·메뉴 검색 / main 최대 1500px / footer).
 * 원본처럼 wrapper div 없이 두 형제로 낸다(aside는 fixed, shell은 margin-left 236px). 로그인 전: 상단 바(브랜드) + 가운데 카드.
 * 메뉴는 표시만 — 권한 판정은 서버.
 */
export function AppLayout({ children, sidebar, account, topbar }: { children: ReactNode; sidebar?: ReactNode; account?: ReactNode; topbar?: ReactNode }) {
  if (!sidebar) {
    return (
      <>
        <header>
          <div className="brand">
            <Logo plain />
            <h1>PayCore 관리자</h1>
          </div>
        </header>
        <main className="mx-auto flex w-full max-w-md flex-col px-4 py-12">{children}</main>
      </>
    )
  }
  return (
    <>
      <aside aria-label="메뉴 영역">
        <div className="brand">
          <Logo plain />
          <span>PayCore</span>
        </div>
        <div className="workspace">
          관리자 콘솔 <small>내부 운영</small>
        </div>
        {sidebar}
        <div className="bottom">
          <hr />
          {account}
        </div>
      </aside>
      <div className="shell">
        <header>{topbar}</header>
        <main>{children}</main>
        <footer>{'PAYCORE　　내부 관리자 콘솔'}</footer>
      </div>
    </>
  )
}
