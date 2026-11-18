import { useCallback, useEffect, useMemo, useState } from 'react'
import { createAdminApi, type AdminApi, type Me } from './api/adminApi'
import { MenuCard } from './components/MenuCard'
import { MenuSearch } from './components/MenuSearch'
import { ThemeToggle } from './components/ThemeToggle'
import { WorkspaceHeader } from './components/WorkspaceHeader'
import { labelOf } from './lib/labels'
import { createScreenMemory, hasUnresolvedRequest } from './lib/screenMemory'
import type { BusinessRef } from './lib/registration'
import { useTheme } from './lib/theme'
import type { ConsoleErrorCode } from './errors/errorCodes'
import { AppLayout } from './layout/AppLayout'
import { menuOf, menusFor, pagesOf, type Menu, type MenuId, type Page, type View } from './navigation'
import { AccountsScreen } from './screens/AccountsScreen'
import { AcquisitionsScreen } from './screens/AcquisitionsScreen'
import { ApprovalsScreen } from './screens/ApprovalsScreen'
import { AuditScreen } from './screens/AuditScreen'
import { EnrollmentScreen } from './screens/EnrollmentScreen'
import { HomeScreen } from './screens/HomeScreen'
import { LoginScreen } from './screens/LoginScreen'
import { BusinessProfileFormScreen } from './screens/BusinessProfileFormScreen'
import { BusinessesScreen, openBusinessIn } from './screens/BusinessesScreen'
import { MerchantRegistrationScreen, registrationHandoffState, startWithBusiness } from './screens/MerchantRegistrationScreen'
import { MerchantsScreen } from './screens/MerchantsScreen'
import { PaymentOpsScreen } from './screens/PaymentOpsScreen'
import { ReconciliationScreen } from './screens/ReconciliationScreen'
import { TransactionsScreen } from './screens/TransactionsScreen'

/**
 * 메뉴 아이콘(ADMIN-UI-WORKSPACE-001): 참고 시안 app.js menus의 SVG path만 쓴다(새 아이콘 없음). 시안과 같은 이름의 메뉴는 그 path,
 * 시안에 없는 내 업무 두 메뉴는 시안 path 중 가까운 것 — 승인함 = '리스크 관리'(방패·확인 표시), 내 요청 = '원천사·기준정보'(문서 목록).
 */
const ICON_PATHS: Record<MenuId, string> = {
  home: 'M3 10l9-7 9 7v11H3z M9 21v-8h6v8',
  approvals: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M12 7v6m0 3v1',
  mine: 'M5 3h14v18H5z M8 7h8M8 11h8M8 15h5',
  merchants: 'M4 21V4h16v17 M8 8h2m4 0h2M8 12h2m4 0h2M9 21v-5h6v5',
  payments: 'M3 5h18v14H3z M3 10h18M6 15h4',
  recon: 'M3 7h16m-4-4 4 4-4 4M21 17H5m4-4-4 4 4 4',
  accounts: 'M16 7a4 4 0 1 1-8 0 4 4 0 1 1 8 0M4 21v-3c0-6 16-6 16 0v3',
}

/**
 * 사이드바(참고 시안 `nav#nav` 1단 메뉴): 운영 홈, 그 아래 구역 이름(내 업무·PAYMENT OPERATIONS·WORKSPACE)별 메뉴. 선택 메뉴는 class `active` + `aria-current="page"`.
 * 구역은 `role="group"`(접근성 이름 = 구역 이름) 안에 시안의 `.label`(장식 — 스크린리더는 group 이름으로 읽는다)과 버튼을 둔다.
 */
function SideMenu({ menus, current, onOpen }: { menus: Menu[]; current: MenuId; onOpen: (menu: Menu) => void }) {
  const groups: { label: string | null; items: Menu[] }[] = []
  for (const menu of menus) {
    const label = menu.section?.label ?? null
    const last = groups[groups.length - 1]
    if (last && last.label === label) last.items.push(menu)
    else groups.push({ label, items: [menu] })
  }
  const item = (menu: Menu) => {
    const active = menu.id === current
    return (
      <button key={menu.id} type="button" className={active ? 'active' : undefined} aria-current={active ? 'page' : undefined} onClick={() => onOpen(menu)}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d={ICON_PATHS[menu.id]} />
        </svg>
        {menu.label}
      </button>
    )
  }
  return (
    <nav id="nav" aria-label="메뉴">
      {groups.map((group) =>
        group.label === null ? (
          group.items.map(item)
        ) : (
          <div key={group.label} role="group" aria-label={group.label}>
            <div className="label" aria-hidden="true">{group.label}</div>
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
  // D-ADMIN-HOME-001: 라이트·다크 — 로그인 전에도 `<html data-theme>`를 맞춘다(선택 버튼은 로그인 뒤 사이드바 아래).
  const [themePreference, setThemePreference] = useTheme()
  const [me, setMe] = useState<Me | null>(null)
  const [checked, setChecked] = useState(false)
  const [mode, setMode] = useState<'login' | 'enroll'>('login')
  const [notice, setNotice] = useState<ConsoleErrorCode | undefined>(undefined)
  const [view, setView] = useState<View>('home')
  // 가맹점 등록 화면 상태(결과 불명 잠금·submissionId·입력) — 탭을 바꿔도 유지, 메모리에만(MERCHANT-OPS-002 §5.3, 리뷰 m-1). 세션마다 새로.
  const [registrationMemory, setRegistrationMemory] = useState(createScreenMemory)
  // 사업자 등록 화면 상태(ADMIN-UI-MERCHANT-002 — 화면 분리, 같은 규칙: 메모리에만, 세션마다 새로). BIZ-4: 새 등록 폼(BusinessProfileFormScreen)이 쓴다.
  const [businessMemory, setBusinessMemory] = useState(createScreenMemory)
  // 사업자 조회 탭 상태(BUSINESS-PROFILE-001 §8.1 — 선택한 사업자 키·목록·상세·수정 폼). 키(biz_)는 이 메모리에만 — URL·브라우저 저장소 없음. 세션마다 새로.
  const [businessesMemory, setBusinessesMemory] = useState(createScreenMemory)
  // 조회 상세 Revision 폼 상태(B2-1 — 결과 불명 잠금·submissionId, 리뷰 C-M1). 등록 화면과 같이 세션마다 새로.
  const [revisionMemory, setRevisionMemory] = useState(createScreenMemory)
  // "가맹점 조회에서 보기"로 열 가맹점 — 조회 화면이 한 번 열면 비운다(리뷰 m-3).
  const [lookupMerchantId, setLookupMerchantId] = useState<string | null>(null)
  // B2-1 리뷰 n-a: 세션이 끝날 때 결과 확인이 필요한 등록 단계·Revision 폼이 있었으면 로그인 화면에 한 번 안내한다(다음 로그인에서 지운다 — 입력·submissionId는 남기지 않는다).
  const [unresolvedNotice, setUnresolvedNotice] = useState(false)

  const signedIn = useCallback((account: Me) => {
    setMe(account)
    setNotice(undefined)
    setUnresolvedNotice(false)
    setView('home')
    setRegistrationMemory(createScreenMemory())
    setBusinessMemory(createScreenMemory())
    setBusinessesMemory(createScreenMemory())
    setRevisionMemory(createScreenMemory())
    setLookupMerchantId(null)
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
    setUnresolvedNotice(hasUnresolvedRequest(registrationMemory, businessMemory, businessesMemory, revisionMemory))
    setRegistrationMemory(createScreenMemory())
    setBusinessMemory(createScreenMemory())
    setBusinessesMemory(createScreenMemory())
    setRevisionMemory(createScreenMemory())
    setLookupMerchantId(null)
  }, [registrationMemory, businessMemory, businessesMemory, revisionMemory])

  const logout = async () => {
    try {
      await api.logout()
    } finally {
      setMe(null)
      setMode('login')
      setUnresolvedNotice(hasUnresolvedRequest(registrationMemory, businessMemory, businessesMemory, revisionMemory))
      setRegistrationMemory(createScreenMemory())
      setBusinessMemory(createScreenMemory())
      setBusinessesMemory(createScreenMemory())
      setRevisionMemory(createScreenMemory())
      setLookupMerchantId(null)
    }
  }

  const menus = useMemo(() => (me ? menusFor(me.roles) : []), [me])

  if (!me) {
    return (
      <AppLayout>
        {!checked ? null : mode === 'enroll' ? (
          <EnrollmentScreen api={api} onDone={() => setMode('login')} />
        ) : (
          <LoginScreen api={api} onAuthenticated={signedIn} onEnroll={() => setMode('enroll')} notice={notice} unresolvedNotice={unresolvedNotice} />
        )}
      </AppLayout>
    )
  }

  // 보이지 않는 화면으로는 가지 않는다(표시 규칙 — 서버도 따로 거부한다).
  const visible = (target: View) => target === 'home' || menus.some((m) => pagesOf(m).some((t) => t.page === target))
  const open = (target: View) => setView(visible(target) ? target : 'home')
  const menu = menus.find((m) => m.id === menuOf(view)) ?? menus[0]!
  /** 사업자 상세 링크(중복 안내 D-BIZ-003·가맹점 등록 ① DRAFT D-BIZ-006 ③·등록 성공 뒤 재조회 C-18): 사업자 조회 탭 + 메모리 선택(URL 없음). */
  const openBusiness = (businessId: string) => {
    openBusinessIn(businessesMemory, businessId)
    open('businesses')
  }
  /** "이 사업자로 가맹점 등록"(REGISTERED만 — D-BIZ-001): 내부 키는 가맹점 등록 메모리로만(D-ADMIN-UI-004 ③), 잠긴 카드가 있으면 넘기지 않는다(리뷰 MINOR-1). */
  const continueToMerchantRegistration = (business: BusinessRef) => {
    if (startWithBusiness(registrationMemory, business)) open('merchantRegistration')
  }
  const page: Page | null = view === 'home' ? null : view

  return (
    <AppLayout
      sidebar={<SideMenu menus={menus} current={menu.id} onOpen={(m) => open(m.tabs[0]?.page ?? 'home')} />}
      topbar={
        <>
          {/* 참고 시안 `#crumb`(워크스페이스　/　메뉴) — 경로 목록, 항목 사이 간격은 CSS(workspace.css). */}
          <nav id="crumb" aria-label="경로">
            <ol>
              <li>워크스페이스</li>
              <li aria-hidden="true">/</li>
              <li aria-current="page">{menu.label}</li>
            </ol>
          </nav>
          <MenuSearch menus={menus} onOpen={open} />
        </>
      }
      account={
        // 참고 시안 `.bottom`: <b>이름</b><small>역할</small> — 그 아래 로그아웃(시안의 샘플 사용자·메뉴 개편 지도는 넣지 않는다).
        <>
          <b>{me.displayName}</b>
          <small title={me.roles.join(', ')}>{`역할: ${me.roles.map(labelOf).join(', ')}`}</small>
          <ThemeToggle value={themePreference} onChange={setThemePreference} />
          <button type="button" className="btn" onClick={logout}>
            로그아웃
          </button>
        </>
      }
    >
      {page === null ? (
        <HomeScreen api={api} me={me} menus={menus} onOpen={open} onSessionEnded={sessionEnded} />
      ) : (
        <>
          <WorkspaceHeader
            eyebrow={menu.section?.eyebrow ?? ''}
            title={menu.label}
            description={menu.description}
            action={
              // ADMIN-UI-MERCHANT-001: 쪽 머리 주요 버튼(이 역할에게 보일 때만 — menusFor). 그 화면을 연 뒤에는 첫 탭으로 돌아가는 버튼.
              menu.action === undefined ? undefined : page === menu.action.page ? (
                <button type="button" className="btn" onClick={() => open(menu.tabs[0]?.page ?? 'home')}>{`${menu.tabs[0]?.label ?? '운영 홈'}로`}</button>
              ) : (
                <button type="button" className="btn primary" onClick={() => open(menu.action!.page)}>
                  {`＋ ${menu.action.label}`}
                </button>
              )
            }
          />
          {/* ADMIN-UI-WORKSPACE-001: 시안 목록 구조(.filters·.tablewrap)로 옮긴 화면(1단계 가맹점 조회, 거래 조회, 결제 보정 취소, 매입·대사, 승인함·내 요청, 계정 관리, 사업자 등록·가맹점 등록)은 카드 가장자리까지 그린다. 나머지 화면은 다음 단계. */}
          <MenuCard label={menu.label} tabs={menu.tabs} tabbed={menu.tabbed} active={page} onSelect={open} flush={page === 'merchants' || page === 'transactions' || page === 'acquisitions' || page === 'reconciliation' || page === 'approvals' || page === 'mine' || page === 'accounts' || page === 'paymentOps' || page === 'businessRegistration' || page === 'merchantRegistration'}>
            {page === 'approvals' && <ApprovalsScreen api={api} view="pending" canDecide viewer={me} onSessionEnded={sessionEnded} />}
            {page === 'mine' && <ApprovalsScreen api={api} view="mine" canDecide={false} viewer={me} onSessionEnded={sessionEnded} />}
            {page === 'merchants' && (
              <MerchantsScreen
                api={api}
                canSearchRegistrationNumber={me.roles.includes('OPERATOR') || me.roles.includes('APPROVER')}
                canRegisterRevision={me.roles.includes('OPERATOR')}
                canRequestApproval={me.roles.includes('OPERATOR')}
                onOpenApprovals={() => open('mine')}
                revisionMemory={revisionMemory}
                onSessionEnded={sessionEnded}
                openMerchantId={lookupMerchantId}
                onOpenedMerchant={() => setLookupMerchantId(null)}
              />
            )}
            {page === 'businesses' && (
              <BusinessesScreen
                api={api}
                memory={businessesMemory}
                canEdit={me.roles.includes('OPERATOR')}
                handoffState={() => registrationHandoffState(registrationMemory)}
                onContinue={continueToMerchantRegistration}
                onSessionEnded={sessionEnded}
              />
            )}
            {page === 'businessRegistration' && (
              // BUSINESS-PROFILE-001 BIZ-4(C-27): 옛 번호·상호 두 칸 화면을 새 등록 폼으로 교체. 성공하면 사업자 조회 상세로(다시 읽기 — C-18).
              <BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={businessMemory} workspace onSaved={openBusiness} onOpenBusiness={openBusiness} onSessionEnded={sessionEnded} />
            )}
            {page === 'merchantRegistration' && (
              <MerchantRegistrationScreen
                api={api}
                memory={registrationMemory}
                onOpenLookup={(merchantId) => {
                  setLookupMerchantId(merchantId)
                  open('merchants')
                }}
                onOpenBusiness={openBusiness}
                onSessionEnded={sessionEnded}
              />
            )}
            {page === 'paymentOps' && <PaymentOpsScreen api={api} canRequest={me.roles.includes('OPERATOR')} onSessionEnded={sessionEnded} />}
            {page === 'transactions' && <TransactionsScreen api={api} onSessionEnded={sessionEnded} />}
            {page === 'acquisitions' && <AcquisitionsScreen api={api} canRequest={me.roles.includes('OPERATOR')} onSessionEnded={sessionEnded} />}
            {page === 'reconciliation' && <ReconciliationScreen api={api} onSessionEnded={sessionEnded} />}
            {page === 'accounts' && <AccountsScreen api={api} canManage={me.roles.includes('SECURITY_ADMIN')} onSessionEnded={sessionEnded} />}
            {page === 'audit' && <AuditScreen api={api} onSessionEnded={sessionEnded} />}
          </MenuCard>
        </>
      )}
    </AppLayout>
  )
}
