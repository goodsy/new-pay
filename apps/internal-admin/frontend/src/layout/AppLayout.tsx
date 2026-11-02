import type { ReactNode } from 'react'
import { Logo } from '../components/Logo'

/**
 * 공통 레이아웃(ADMIN-UI-001 rev 0.4 — D-ADMIN-UI-003 참고 시안 'pg workspace').
 * 로그인 뒤: 왼쪽 흰 메뉴 영역(`aside` 236px — 로고, 콘솔 이름 상자, 구역별 `nav`, 아래 계정·로그아웃; 배경은 전체 높이, 내용만 sticky),
 * 상단 바(banner 76px: 경로 `워크스페이스 / 메뉴`, 메뉴 검색), 본문(`main` 최대 1500px). 로그인 전: 상단 바 + 가운데 카드.
 * 메뉴는 표시만 — 권한 판정은 서버.
 */
export function AppLayout({ children, sidebar, account, topbar }: { children: ReactNode; sidebar?: ReactNode; account?: ReactNode; topbar?: ReactNode }) {
  if (!sidebar) {
    return (
      <div className="min-h-screen bg-canvas">
        <header className="flex h-[76px] items-center gap-2.5 border-b bg-card px-4 md:px-[38px]">
          <Logo />
          <h1 className="text-sm font-bold">PayCore 관리자</h1>
        </header>
        <main className="mx-auto flex w-full max-w-md flex-col px-4 py-12">{children}</main>
      </div>
    )
  }
  return (
    // 본문 열은 min-w-0 — 넓은 표가 쪽 밖으로 넘치지 않고 표 안에서 가로 스크롤한다.
    // md 이상: aside 자체를 화면 높이로 고정(sticky top-0 h-screen, self-start) — 본문이 길어도 메뉴 배경·선이 늘 보이고,
    // 메뉴가 넘치면 메뉴 부분만 안에서 스크롤하며 계정·로그아웃은 아래에 붙어 있다(참고 시안 aside: fixed, height 100vh).
    <div className="flex min-h-screen flex-col bg-canvas md:flex-row">
      <aside aria-label="메뉴 영역" className="border-b border-sidebar-border bg-sidebar md:sticky md:top-0 md:h-screen md:w-[236px] md:shrink-0 md:self-start md:border-r md:border-b-0">
        <div className="flex flex-col px-[18px] py-4 md:h-full md:py-[30px]">
          <div className="flex shrink-0 items-center gap-2.5">
            <Logo />
            <span className="text-[21px] font-extrabold tracking-[-0.7px] text-foreground">PayCore</span>
          </div>
          <div className="mt-8 mb-5 hidden shrink-0 rounded-[9px] border bg-muted px-3 py-3.5 text-xs font-bold md:block">
            관리자 콘솔<small className="mx-2 text-[10px] font-normal text-muted-foreground">내부 운영</small>
          </div>
          <div className="md:min-h-0 md:flex-1 md:overflow-y-auto">{sidebar}</div>
          <div className="mt-4 shrink-0 md:pt-6">
            <hr className="mb-5 hidden border-sidebar-border md:block" />
            {account}
          </div>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-[76px] items-center gap-[22px] border-b bg-card px-4 text-[11px] text-muted-foreground md:px-[38px]">{topbar}</header>
        <main className="mx-auto flex w-full max-w-[1500px] min-w-0 flex-col gap-7 px-4 py-8 md:px-[38px] md:pt-9 md:pb-6">{children}</main>
      </div>
    </div>
  )
}
