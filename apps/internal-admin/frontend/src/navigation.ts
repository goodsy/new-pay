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

/** 탭이 아닌 하위 화면(D-ADMIN-UI-006): 부모 탭 목록의 버튼이 같은 탭 안에서 연다 — 열려 있는 동안 부모 탭이 선택으로 보인다. */
export interface Subpage extends Tab {
  parent: Page
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
  /** 이 역할이 볼 수 있는 하위 화면만(부모 탭도 보일 때). 탭 막대에는 없고 부모 탭 목록의 버튼으로 연다(D-ADMIN-UI-006). */
  subpages?: Subpage[]
  /**
   * 쪽 머리 주요 버튼(ADMIN-UI-MERCHANT-001에서 도입): 탭 막대에 두지 않는 화면 하나를 연다. 이 역할이 볼 수 있을 때만 남는다(표시만 — 판정은 서버).
   * ADMIN-UI-MERCHANT-002부터 쓰는 메뉴가 없다(사업자 등록·가맹점 등록은 탭 → D-ADMIN-UI-006부터 하위 화면) — 공용 장치로만 남긴다.
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
    // LAYOUT-ROLLOUT B1(사용자 결정 D-ADMIN-UI-006, Demo 피드백 2026-11-19): 탭 4개 → '가맹점'·'사업자' 2개(가맹점이 첫 화면 그대로). 등록 두 화면은 탭이 아니라
    // 각 목록 제목 줄의 '＋ … 등록' 버튼이 같은 탭 안에서 여는 하위 화면 — page id·표시 규칙(pagesFor, OPERATOR만)은 그대로.
    tabs: [{ page: 'merchants', label: '가맹점' }, { page: 'businesses', label: '사업자' }],
    subpages: [
      { page: 'businessRegistration', label: '사업자 등록', parent: 'businesses' },
      { page: 'merchantRegistration', label: '가맹점 등록', parent: 'merchants' },
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
    const { action, subpages, ...rest } = m
    const tabs = m.tabs.filter((t) => pages.includes(t.page))
    const shown: Menu = { ...rest, tabs }
    // 하위 화면은 자기 화면과 부모 탭이 모두 보일 때만(D-ADMIN-UI-006 — 표시만, 판정은 서버).
    const subs = (subpages ?? []).filter((s) => pages.includes(s.page) && tabs.some((t) => t.page === s.parent))
    if (subs.length > 0) shown.subpages = subs
    return action && pages.includes(action.page) ? { ...shown, action } : shown
  }).filter((m) => m.id === 'home' || m.tabs.length > 0)
}

/** 메뉴가 여는 모든 화면(탭 + 하위 화면 + 쪽 머리 주요 버튼). */
export const pagesOf = (menu: Menu): Tab[] => [...menu.tabs, ...(menu.subpages ?? []), ...(menu.action ? [menu.action] : [])]

/** 탭 막대에서 선택으로 보일 탭: 그 화면의 탭, 하위 화면이면 부모 탭(D-ADMIN-UI-006). 없으면 undefined. */
export function selectedTabOf(tabs: Tab[], subpages: Subpage[] | undefined, page: Page): Tab | undefined {
  const parent = subpages?.find((s) => s.page === page)?.parent ?? page
  return tabs.find((t) => t.page === parent)
}

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

/** 상단 메뉴 검색(D-ADMIN-UI-003 — 거래·가맹점 검색 아님): 보이는 메뉴 이름과 탭 이름(하위 화면·쪽 머리 주요 버튼 포함)에서만 찾는다. */
export function searchMenus(menus: Menu[], query: string): MenuSearchResult[] {
  const q = normalize(query)
  if (!q) return []
  const results: MenuSearchResult[] = []
  for (const menu of menus) {
    if (normalize(menu.label).includes(q)) results.push({ path: menu.label, target: menu.tabs[0]?.page ?? 'home' })
    if (!menu.tabbed) continue
    for (const tab of menu.tabs) if (normalize(tab.label).includes(q)) results.push({ path: `${menu.label} › ${tab.label}`, target: tab.page })
    for (const sub of menu.subpages ?? []) if (normalize(sub.label).includes(q)) results.push({ path: `${menu.label} › ${sub.label}`, target: sub.page })
    if (menu.action && normalize(menu.action.label).includes(q)) results.push({ path: `${menu.label} › ${menu.action.label}`, target: menu.action.page })
  }
  return results
}
