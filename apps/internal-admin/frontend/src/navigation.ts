/**
 * 관리자 콘솔 메뉴 구조(ADMIN-UI-001 rev 0.4, D-ADMIN-UI-003 — 참고 시안 'pg workspace'의 사이드바 그룹·탭).
 * 서버 기능이 있는 화면([Page])만 업무 메뉴의 탭으로 둔다(기존 6개 + MERCHANT-OPS-001 가맹점 조회). 서버 기능이 없는 메뉴·탭은 만들지 않는다(별도 작업).
 * 표시만 정한다 — 권한 판정은 서버가 매 요청 계정 현재 행으로 한다(§4.5.1).
 */
export type Page = 'approvals' | 'mine' | 'merchants' | 'paymentOps' | 'accounts' | 'reconciliation' | 'audit'
export type View = 'home' | Page
export type MenuId = 'home' | 'approvals' | 'mine' | 'merchants' | 'payments' | 'recon' | 'accounts'

export interface Section {
  /** 사이드바 구역 이름. */
  label: string
  /** 쪽 머리 위 작은 글씨(참고 시안 eyebrow). */
  eyebrow: string
}

export interface Tab {
  page: Page
  label: string
}

export interface Menu {
  id: MenuId
  label: string
  section: Section | null
  /** 쪽 머리 한 줄 설명. */
  description: string
  /** 카드 안 탭 막대 표시(업무 메뉴). 내 업무(승인함·내 요청)는 화면 하나라 탭 막대 없이 카드만. */
  tabbed: boolean
  /** 이 역할이 볼 수 있는 탭만(운영 홈은 빈 목록). */
  tabs: Tab[]
}

const VIEW_ROLES = ['VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN']

/** 역할 → 보이는 화면(rev 0.3 규칙 그대로 — 표시만, 서버가 판정). */
export function pagesFor(roles: string[]): Page[] {
  const has = (role: string) => roles.includes(role)
  const pages: Page[] = []
  if (has('APPROVER')) pages.push('approvals')
  if (has('SECURITY_ADMIN') || has('OPERATOR')) pages.push('mine')
  // MERCHANT-OPS-001 §4.1: 가맹점 조회는 VIEW_MASKED(조회 4역할). 서버 기능이 생겨 메뉴를 연다(D-ADMIN-UI-003).
  if (roles.some((r) => VIEW_ROLES.includes(r))) pages.push('merchants')
  if (roles.some((r) => VIEW_ROLES.includes(r))) pages.push('paymentOps')
  if (roles.some((r) => r !== 'UNMASK')) pages.push('accounts')
  if (has('SECURITY_ADMIN') || has('APPROVER')) pages.push('reconciliation')
  pages.push('audit')
  return pages
}

const MY_WORK: Section = { label: '내 업무', eyebrow: 'MY WORK' }
const PAYMENT_OPERATIONS: Section = { label: 'PAYMENT OPERATIONS', eyebrow: 'PAYMENT OPERATIONS' }
const WORKSPACE: Section = { label: 'WORKSPACE', eyebrow: 'WORKSPACE' }

/** 고정 순서. 탭 이름은 참고 시안의 탭 이름을 따르되 기존 화면 기능만 가리킨다. */
const MENUS: Menu[] = [
  { id: 'home', label: '운영 홈', section: null, tabbed: false, tabs: [], description: '지금 처리할 업무와 내가 쓸 수 있는 메뉴를 한눈에 확인하세요.' },
  {
    id: 'approvals', label: '승인함', section: MY_WORK, tabbed: false, tabs: [{ page: 'approvals', label: '승인함' }],
    description: '다른 관리자가 요청한 작업을 검토하고, 인증 코드로 승인하거나 거절하세요.',
  },
  {
    id: 'mine', label: '내 요청', section: MY_WORK, tabbed: false, tabs: [{ page: 'mine', label: '내 요청' }],
    description: '내가 만든 승인 요청의 진행 상태를 확인하고, 대기 중이면 철회하세요.',
  },
  {
    id: 'merchants', label: '가맹점 관리', section: PAYMENT_OPERATIONS, tabbed: true, tabs: [{ page: 'merchants', label: '가맹점 조회' }],
    description: '가맹점·사업자·MID 개통 상태와 변경 이력을 조회하세요(조회 전용 — 등록·변경은 별도 작업).',
  },
  {
    id: 'payments', label: '결제 관리', section: PAYMENT_OPERATIONS, tabbed: true, tabs: [{ page: 'paymentOps', label: '결제 보정 취소' }],
    description: '결제를 조회하고, 다른 관리자의 승인을 거쳐 실행되는 보정 취소를 요청하세요.',
  },
  {
    id: 'recon', label: '매입·대사', section: PAYMENT_OPERATIONS, tabbed: true, tabs: [{ page: 'reconciliation', label: '대사 발견' }],
    description: '승인 기록과 결제 서비스 결과가 서로 맞지 않는 건을 확인하세요.',
  },
  {
    id: 'accounts', label: '계정·권한', section: WORKSPACE, tabbed: true,
    tabs: [{ page: 'accounts', label: '계정 관리' }, { page: 'audit', label: '접속·감사 이력' }],
    description: '관리자 계정과 역할, 접속·감사 이력을 확인하세요.',
  },
]

/** 역할이 볼 수 있는 탭이 하나라도 있는 메뉴만(운영 홈은 항상). */
export function menusFor(roles: string[]): Menu[] {
  const pages = pagesFor(roles)
  return MENUS.map((m) => ({ ...m, tabs: m.tabs.filter((t) => pages.includes(t.page)) })).filter((m) => m.id === 'home' || m.tabs.length > 0)
}

/** 화면 → 그 화면이 속한 메뉴. */
export function menuOf(view: View): MenuId {
  if (view === 'home') return 'home'
  return MENUS.find((m) => m.tabs.some((t) => t.page === view))?.id ?? 'home'
}

export interface MenuSearchResult {
  /** 화면에 보이는 경로(예: `계정·권한 › 접속·감사 이력`). */
  path: string
  target: View
}

const normalize = (text: string) => text.replace(/[\s·]/g, '').toLowerCase()

/** 상단 메뉴 검색(D-ADMIN-UI-003 — 거래·가맹점 검색 아님): 보이는 메뉴 이름과 탭 이름에서만 찾는다. */
export function searchMenus(menus: Menu[], query: string): MenuSearchResult[] {
  const q = normalize(query)
  if (!q) return []
  const results: MenuSearchResult[] = []
  for (const menu of menus) {
    if (normalize(menu.label).includes(q)) results.push({ path: menu.label, target: menu.tabs[0]?.page ?? 'home' })
    if (!menu.tabbed) continue
    for (const tab of menu.tabs) if (normalize(tab.label).includes(q)) results.push({ path: `${menu.label} › ${tab.label}`, target: tab.page })
  }
  return results
}
