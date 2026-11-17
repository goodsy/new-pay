/**
 * 관리자 콘솔 메뉴 구조(ADMIN-UI-001 rev 0.4, D-ADMIN-UI-003 — 참고 시안 'pg workspace'의 사이드바 그룹·탭).
 * 서버 기능이 있는 화면([Page])만 업무 메뉴의 탭으로 둔다(기존 6개 + MERCHANT-OPS-001 가맹점 조회 + TXN-OPS-001 거래 조회). 서버 기능이 없는 메뉴·탭은 만들지 않는다(별도 작업).
 * 표시만 정한다 — 권한 판정은 서버가 매 요청 계정 현재 행으로 한다(§4.5.1).
 */
export type Page = 'approvals' | 'mine' | 'merchants' | 'businesses' | 'businessRegistration' | 'merchantRegistration' | 'paymentOps' | 'transactions' | 'acquisitions' | 'accounts' | 'reconciliation' | 'audit'
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
  /**
   * 쪽 머리 주요 버튼(ADMIN-UI-MERCHANT-001에서 도입): 탭 막대에 두지 않는 화면 하나를 연다. 이 역할이 볼 수 있을 때만 남는다(표시만 — 판정은 서버).
   * ADMIN-UI-MERCHANT-002부터 쓰는 메뉴가 없다(사업자 등록·가맹점 등록은 탭) — 공용 장치로만 남긴다.
   */
  action?: Tab
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
  // BUSINESS-PROFILE-001 §8.1(D-BIZ-006 ①): 사업자 조회(목록·상세·이력·번호 조회)는 OPERATOR·APPROVER — 표시만, 서버가 판정(BusinessPermissions.VIEW).
  if (has('OPERATOR') || has('APPROVER')) pages.push('businesses')
  // MERCHANT-OPS-002 §5.1(D-MOPS-006 ①): 가맹점 등록은 OPERATOR에게만 보인다(서버 기능이 생겨 화면을 연다 — D-ADMIN-UI-003, 판정은 서버).
  // ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ④ "화면만 분리, 권한은 그대로"): 사업자 등록·가맹점 등록을 두 화면(탭)으로 나눈다 — 둘 다 OPERATOR만.
  if (has('OPERATOR')) pages.push('businessRegistration', 'merchantRegistration')
  if (roles.some((r) => VIEW_ROLES.includes(r))) pages.push('paymentOps')
  // TXN-OPS-001 §4.1: 거래 조회도 VIEW_MASKED(조회 4역할 — I-1 Implementer 해석, 기존 결제 대상 조회와 같다).
  if (roles.some((r) => VIEW_ROLES.includes(r))) pages.push('transactions')
  // ACQ-VIEW-001 §4.6: 매입 현황도 VIEW_MASKED(조회 4역할 — 사용자 결정 D-ACQV-001).
  if (roles.some((r) => VIEW_ROLES.includes(r))) pages.push('acquisitions')
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
    // ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ④, 사용자 Demo 피드백 2026-11-13 "담당자별 업무가 다름"): 쪽 머리 '＋ 사업자 등록' 버튼(한 마법사) → 사업자 등록·가맹점 등록 두 탭. 표시 규칙(pagesFor)은 OPERATOR만 그대로.
    id: 'merchants', label: '사업자·가맹점', section: PAYMENT_OPERATIONS, tabbed: true,
    // BUSINESS-PROFILE-001 §8.1(C-26): 가맹점 조회 · 사업자 조회(새) · 사업자 등록(새 폼으로 교체 — C-27) · 가맹점 등록.
    tabs: [
      { page: 'merchants', label: '가맹점 조회' }, { page: 'businesses', label: '사업자 조회' }, { page: 'businessRegistration', label: '사업자 등록' },
      { page: 'merchantRegistration', label: '가맹점 등록' },
    ],
    description: '사업자 등록부터 서비스 개통까지, 가맹점의 전체 여정을 관리하세요(조회·등록 — 계약 승인·MID 발급·개통 요청은 가맹점 상세에서).',
  },
  {
    id: 'payments', label: '결제 관리', section: PAYMENT_OPERATIONS, tabbed: true, tabs: [{ page: 'paymentOps', label: '결제 보정 취소' }, { page: 'transactions', label: '거래 조회' }],
    description: '결제를 조회하고, 다른 관리자의 승인을 거쳐 실행되는 보정 취소를 요청하세요.',
  },
  {
    id: 'recon', label: '매입·대사', section: PAYMENT_OPERATIONS, tabbed: true,
    // ACQ-VIEW-001: 기존 대사 발견 탭을 첫 탭으로 유지(SECURITY_ADMIN·APPROVER의 메뉴 진입 화면 불변), 매입 현황은 그 뒤 — 조회 4역할만 보면 매입 현황 하나.
    tabs: [{ page: 'reconciliation', label: '대사 발견' }, { page: 'acquisitions', label: '매입 현황' }],
    description: '매입 파일의 전송·수신·결과 상태와, 승인 기록과 결제 서비스 결과가 서로 맞지 않는 건을 확인하세요.',
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
  return MENUS.map((m) => {
    const { action, ...rest } = m
    const shown = { ...rest, tabs: m.tabs.filter((t) => pages.includes(t.page)) }
    return action && pages.includes(action.page) ? { ...shown, action } : shown
  }).filter((m) => m.id === 'home' || m.tabs.length > 0)
}

/** 메뉴가 여는 모든 화면(탭 + 쪽 머리 주요 버튼). */
export const pagesOf = (menu: Menu): Tab[] => (menu.action ? [...menu.tabs, menu.action] : menu.tabs)

/** 화면 → 그 화면이 속한 메뉴. */
export function menuOf(view: View): MenuId {
  if (view === 'home') return 'home'
  return MENUS.find((m) => pagesOf(m).some((t) => t.page === view))?.id ?? 'home'
}

export interface MenuSearchResult {
  /** 화면에 보이는 경로(예: `계정·권한 › 접속·감사 이력`). */
  path: string
  target: View
}

const normalize = (text: string) => text.replace(/[\s·]/g, '').toLowerCase()

/** 상단 메뉴 검색(D-ADMIN-UI-003 — 거래·가맹점 검색 아님): 보이는 메뉴 이름과 탭 이름(쪽 머리 주요 버튼 포함)에서만 찾는다. */
export function searchMenus(menus: Menu[], query: string): MenuSearchResult[] {
  const q = normalize(query)
  if (!q) return []
  const results: MenuSearchResult[] = []
  for (const menu of menus) {
    if (normalize(menu.label).includes(q)) results.push({ path: menu.label, target: menu.tabs[0]?.page ?? 'home' })
    if (!menu.tabbed) continue
    for (const tab of menu.tabs) if (normalize(tab.label).includes(q)) results.push({ path: `${menu.label} › ${tab.label}`, target: tab.page })
    if (menu.action && normalize(menu.action.label).includes(q)) results.push({ path: `${menu.label} › ${menu.action.label}`, target: menu.action.page })
  }
  return results
}
