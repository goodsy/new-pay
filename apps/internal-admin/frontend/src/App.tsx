import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeftRightIcon, CreditCardIcon, HouseIcon, InboxIcon, LogOutIcon, SendIcon, StoreIcon, UserRoundIcon, type LucideIcon } from './components/icons'
import { createAdminApi, type AdminApi, type Me } from './api/adminApi'
import { MenuCard } from './components/MenuCard'
import { MenuSearch } from './components/MenuSearch'
import { WorkspaceHeader } from './components/WorkspaceHeader'
import { Button } from './components/ui/button'
import { labelOf } from './lib/labels'
import { cn } from './lib/utils'
import type { ConsoleErrorCode } from './errors/errorCodes'
import { AppLayout } from './layout/AppLayout'
import { menuOf, menusFor, type Menu, type MenuId, type Page, type View } from './navigation'
import { AccountsScreen } from './screens/AccountsScreen'
import { ApprovalsScreen } from './screens/ApprovalsScreen'
import { AuditScreen } from './screens/AuditScreen'
import { EnrollmentScreen } from './screens/EnrollmentScreen'
import { HomeScreen } from './screens/HomeScreen'
import { LoginScreen } from './screens/LoginScreen'
import { MerchantsScreen } from './screens/MerchantsScreen'
import { PaymentOpsScreen } from './screens/PaymentOpsScreen'
import { ReconciliationScreen } from './screens/ReconciliationScreen'

const ICONS: Record<MenuId, LucideIcon> = {
  home: HouseIcon, approvals: InboxIcon, mine: SendIcon, merchants: StoreIcon, payments: CreditCardIcon, recon: ArrowLeftRightIcon, accounts: UserRoundIcon,
}

/** 사이드바(rev 0.4): 운영 홈, 그 아래 구역 이름(내 업무·PAYMENT OPERATIONS·WORKSPACE)별 메뉴. 선택 메뉴는 `aria-current="page"`. */
function SideMenu({ menus, current, onOpen }: { menus: Menu[]; current: MenuId; onOpen: (menu: Menu) => void }) {
  const groups: { label: string | null; items: Menu[] }[] = []
  for (const menu of menus) {
    const label = menu.section?.label ?? null
    const last = groups[groups.length - 1]
    if (last && last.label === label) last.items.push(menu)
    else groups.push({ label, items: [menu] })
  }
  const item = (menu: Menu) => {
    const Icon = ICONS[menu.id]
    const active = menu.id === current
    return (
      <button
        key={menu.id}
        type="button"
        aria-current={active ? 'page' : undefined}
        onClick={() => onOpen(menu)}
        className={cn(
          'my-[3px] flex w-full shrink-0 items-center gap-3 rounded-lg px-3.5 py-3 text-left text-[13px] whitespace-nowrap text-sidebar-foreground transition-colors outline-none hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50',
          active && 'bg-sidebar-accent font-bold text-sidebar-accent-foreground hover:bg-sidebar-accent',
        )}
      >
        <Icon aria-hidden="true" className="size-[18px] stroke-[1.6]" />
        {menu.label}
      </button>
    )
  }
  return (
    <nav aria-label="메뉴" className="flex gap-1 overflow-x-auto md:flex-col md:gap-0 md:overflow-visible">
      {groups.map((group) =>
        group.label === null ? (
          group.items.map(item)
        ) : (
          <div key={group.label} role="group" aria-label={group.label} className="flex gap-1 md:flex-col md:gap-0">
            <div aria-hidden="true" className="hidden px-3.5 pt-5 pb-2 text-[10px] tracking-[1px] text-sidebar-label md:block">{group.label}</div>
            {group.items.map(item)}
          </div>
        ),
      )}
    </nav>
  )
}

/**
 * 관리자 콘솔(S7 — 같은 출처 정적 번들, 라우터·저장소 없음: 화면 상태는 메모리에만, URL에 비밀·코드 없음).
 * rev 0.4(D-ADMIN-UI-003): 첫 화면은 운영 홈, 기존 6개 화면은 업무 메뉴의 탭. 메뉴·탭 표시는 [menusFor] — 권한 판정은 서버.
 * 세션이 끝난 응답(SESSION_REPLACED·SESSION_EXPIRED·UNAUTHENTICATED·CSRF_MISSING)은 로그인 화면으로 돌아가 이유를 안내한다.
 */
export function App({ api: injected }: { api?: AdminApi } = {}) {
  const api = useMemo(() => injected ?? createAdminApi(), [injected])
  const [me, setMe] = useState<Me | null>(null)
  const [checked, setChecked] = useState(false)
  const [mode, setMode] = useState<'login' | 'enroll'>('login')
  const [notice, setNotice] = useState<ConsoleErrorCode | undefined>(undefined)
  const [view, setView] = useState<View>('home')

  const signedIn = useCallback((account: Me) => {
    setMe(account)
    setNotice(undefined)
    setView('home')
  }, [])

  useEffect(() => {
    let alive = true
    api.me().then((account) => alive && signedIn(account)).catch(() => undefined).finally(() => alive && setChecked(true))
    return () => {
      alive = false
    }
  }, [api, signedIn])

  const sessionEnded = useCallback((code: ConsoleErrorCode) => {
    setMe(null)
    setMode('login')
    setNotice(code)
  }, [])

  const logout = async () => {
    try {
      await api.logout()
    } finally {
      setMe(null)
      setMode('login')
    }
  }

  const menus = useMemo(() => (me ? menusFor(me.roles) : []), [me])

  if (!me) {
    return (
      <AppLayout>
        {!checked ? null : mode === 'enroll' ? (
          <EnrollmentScreen api={api} onDone={() => setMode('login')} />
        ) : (
          <LoginScreen api={api} onAuthenticated={signedIn} onEnroll={() => setMode('enroll')} notice={notice} />
        )}
      </AppLayout>
    )
  }

  // 보이지 않는 화면으로는 가지 않는다(표시 규칙 — 서버도 따로 거부한다).
  const visible = (target: View) => target === 'home' || menus.some((m) => m.tabs.some((t) => t.page === target))
  const open = (target: View) => setView(visible(target) ? target : 'home')
  const menu = menus.find((m) => m.id === menuOf(view)) ?? menus[0]!
  const page: Page | null = view === 'home' ? null : view

  return (
    <AppLayout
      sidebar={<SideMenu menus={menus} current={menu.id} onOpen={(m) => open(m.tabs[0]?.page ?? 'home')} />}
      topbar={
        <>
          <nav aria-label="경로">
            <ol className="flex items-center gap-2.5">
              <li>워크스페이스</li>
              <li aria-hidden="true">/</li>
              <li aria-current="page" className="text-foreground">{menu.label}</li>
            </ol>
          </nav>
          <MenuSearch menus={menus} onOpen={open} />
        </>
      }
      account={
        <div className="flex items-center justify-between gap-3 md:flex-col md:items-stretch">
          <span className="flex min-w-0 flex-col leading-tight">
            <span className="truncate text-xs font-medium text-foreground">{me.displayName}</span>
            <span className="mt-1.5 truncate text-[10px] text-muted-foreground" title={me.roles.join(', ')}>
              {`역할: ${me.roles.map(labelOf).join(', ')}`}
            </span>
          </span>
          <Button type="button" variant="outline" size="sm" onClick={logout}>
            <LogOutIcon aria-hidden="true" />
            로그아웃
          </Button>
        </div>
      }
    >
      {page === null ? (
        <HomeScreen api={api} me={me} menus={menus} onOpen={open} onSessionEnded={sessionEnded} />
      ) : (
        <>
          <WorkspaceHeader eyebrow={menu.section?.eyebrow ?? ''} title={menu.label} description={menu.description} />
          <MenuCard label={menu.label} tabs={menu.tabs} tabbed={menu.tabbed} active={page} onSelect={open}>
            {page === 'approvals' && <ApprovalsScreen api={api} view="pending" canDecide viewer={me} onSessionEnded={sessionEnded} />}
            {page === 'mine' && <ApprovalsScreen api={api} view="mine" canDecide={false} viewer={me} onSessionEnded={sessionEnded} />}
            {page === 'merchants' && (
              <MerchantsScreen api={api} canSearchRegistrationNumber={me.roles.includes('OPERATOR') || me.roles.includes('APPROVER')} onSessionEnded={sessionEnded} />
            )}
            {page === 'paymentOps' && <PaymentOpsScreen api={api} canRequest={me.roles.includes('OPERATOR')} onSessionEnded={sessionEnded} />}
            {page === 'reconciliation' && <ReconciliationScreen api={api} onSessionEnded={sessionEnded} />}
            {page === 'accounts' && <AccountsScreen api={api} canManage={me.roles.includes('SECURITY_ADMIN')} onSessionEnded={sessionEnded} />}
            {page === 'audit' && <AuditScreen api={api} onSessionEnded={sessionEnded} />}
          </MenuCard>
        </>
      )}
    </AppLayout>
  )
}
