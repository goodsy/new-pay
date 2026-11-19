// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { menuOf, menusFor, pagesFor, pagesOf, searchMenus } from './navigation'

/** 역할별 메뉴 트리를 [메뉴 이름, [탭 이름…]] 꼴로 펼친다(운영 홈은 탭 없음). */
const tree = (roles: string[]) => menusFor(roles).map((m) => [m.label, m.tabs.map((t) => t.label)])
/** 쪽 머리 주요 버튼(ADMIN-UI-MERCHANT-001)이 있는 메뉴만 [메뉴 이름, 버튼 이름]. */
const actions = (roles: string[]) => menusFor(roles).filter((m) => m.action).map((m) => [m.label, m.action!.label, m.action!.page])

describe('ADMIN-UI-001 rev 0.4 (D-ADMIN-UI-003): role → sidebar menus and tabs (existing 6 screens only — display only, the server decides)', () => {
  it('the screens each role sees are unchanged (pagesFor)', () => {
    // 기대값 변경(TXN-OPS-001): 조회 4역할에 거래 조회('transactions')가 더해졌다 — 나머지 화면은 그대로.
    // 기대값 변경(ACQ-VIEW-001, D-ACQV-001): 조회 4역할에 매입 현황('acquisitions')이 더해졌다 — 나머지 화면은 그대로.
    expect(pagesFor(['SECURITY_ADMIN'])).toEqual(['mine', 'merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'reconciliation', 'audit'])
    // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 §8.1, D-BIZ-006 ①): 사업자 조회('businesses')는 OPERATOR·APPROVER — 나머지 화면은 그대로.
    expect(pagesFor(['APPROVER'])).toEqual(['approvals', 'merchants', 'businesses', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'reconciliation', 'audit'])
    // 기대값 변경(MERCHANT-OPS-002 §5.1, D-MOPS-006 ①): OPERATOR에게 가맹점 등록('merchantRegistration')이 더해졌다 — 나머지 역할은 그대로.
    // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ④): 사업자 등록('businessRegistration')·가맹점 등록('merchantRegistration') 화면 분리 — 둘 다 OPERATOR만(권한 그대로).
    expect(pagesFor(['OPERATOR'])).toEqual(['mine', 'merchants', 'businesses', 'businessRegistration', 'merchantRegistration', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'audit'])
    expect(pagesFor(['VIEWER'])).toEqual(['merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'audit'])
    expect(pagesFor(['UNMASK'])).toEqual(['audit'])
  })

  it('SECURITY_ADMIN: home, 내 요청, 결제 관리, 매입·대사, 계정·권한(계정 관리·접속·감사 이력)', () => {
    expect(tree(['SECURITY_ADMIN'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회' → '가맹점'.
      ['사업자·가맹점', ['가맹점']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['대사 발견', '매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('APPROVER: home, 승인함, 결제 관리, 매입·대사, 계정·권한', () => {
    expect(tree(['APPROVER'])).toEqual([
      ['운영 홈', []],
      ['승인함', ['승인함']],
      // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 §8.1, D-BIZ-006 ①): 사업자 조회 탭(조회만 — 명령 버튼은 서버·화면 모두 OPERATOR).
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회'·'사업자 조회' → '가맹점'·'사업자'.
      ['사업자·가맹점', ['가맹점', '사업자']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['대사 발견', '매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('OPERATOR: no 승인함; 매입·대사 with 매입 현황 only (ACQ-VIEW-001 — 대사 발견 stays SECURITY_ADMIN·APPROVER)', () => {
    expect(tree(['OPERATOR'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ④): 쪽 머리 '＋ 사업자 등록' 버튼(한 마법사) → '사업자 등록'·'가맹점 등록' 탭으로 분리(담당자별 업무 — 둘 다 OPERATOR).
      // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 §8.1): 가맹점 조회 · 사업자 조회(새) · 사업자 등록(교체) · 가맹점 등록.
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 4개 → '가맹점'·'사업자' 2개. 등록 두 화면은 하위 화면(menu.subpages — 아래 LAYOUT-ROLLOUT B1 시험), 보이는 역할은 OPERATOR만 그대로.
      ['사업자·가맹점', ['가맹점', '사업자']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('VIEWER: 사업자·가맹점, 결제 관리, 매입·대사(매입 현황) and 계정·권한 only', () => {
    expect(tree(['VIEWER'])).toEqual([
      ['운영 홈', []],
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회' → '가맹점'.
      ['사업자·가맹점', ['가맹점']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('UNMASK only: 계정·권한 with the audit tab alone (no 계정 관리 tab, no 결제 관리)', () => {
    expect(tree(['UNMASK'])).toEqual([
      ['운영 홈', []],
      ['계정·권한', ['접속·감사 이력']],
    ])
  })

  it('combined roles get the union, in the fixed order', () => {
    expect(tree(['SECURITY_ADMIN', 'APPROVER']).map(([label]) => label)).toEqual(['운영 홈', '승인함', '내 요청', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'])
  })

  it('menus sit under the reference section labels; inbox menus have no tab bar, business menus do', () => {
    const menus = menusFor(['SECURITY_ADMIN', 'APPROVER'])
    expect(menus.map((m) => [m.id, m.section?.label ?? null, m.tabbed])).toEqual([
      ['home', null, false],
      ['approvals', '내 업무', false],
      ['mine', '내 업무', false],
      ['merchants', 'PAYMENT OPERATIONS', true],
      ['payments', 'PAYMENT OPERATIONS', true],
      ['recon', 'PAYMENT OPERATIONS', true],
      ['accounts', 'WORKSPACE', true],
    ])
  })

  it('MERCHANT-OPS-001 (AC 11): 사업자·가맹점 is shown to the four view roles only (server function exists now — D-ADMIN-UI-003)', () => {
    // 기대값 변경(MERCHANT-OPS-002 §5.1): OPERATOR의 가맹점 관리에는 가맹점 등록 화면이 더해졌다(다음 시험).
    // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ④): 등록 화면은 사업자 등록·가맹점 등록 두 탭(쪽 머리 버튼 없음) — OPERATOR만.
    // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 §8.1, D-BIZ-006 ①): APPROVER·OPERATOR에 사업자 조회('businesses') 탭.
    for (const role of ['VIEWER', 'SECURITY_ADMIN']) {
      expect(pagesOf(menusFor([role]).find((m) => m.id === 'merchants')!).map((t) => t.page)).toEqual(['merchants'])
    }
    expect(pagesOf(menusFor(['APPROVER']).find((m) => m.id === 'merchants')!).map((t) => t.page)).toEqual(['merchants', 'businesses'])
    expect(pagesOf(menusFor(['OPERATOR']).find((m) => m.id === 'merchants')!).map((t) => t.page)).toEqual(['merchants', 'businesses', 'businessRegistration', 'merchantRegistration'])
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭은 가맹점·사업자 둘 — 등록 두 화면은 pagesOf(위 줄, 순서 그대로)에 하위 화면으로 남는다.
    expect(menusFor(['OPERATOR']).find((m) => m.id === 'merchants')?.tabs.map((t) => t.page)).toEqual(['merchants', 'businesses'])
    expect(menusFor(['UNMASK']).some((m) => m.id === 'merchants')).toBe(false)
    expect(menusFor([]).some((m) => m.id === 'merchants')).toBe(false)
    expect(menuOf('merchants')).toBe('merchants')
    expect(searchMenus(menusFor(['VIEWER']), '가맹점').map((r) => [r.path, r.target])).toEqual([
      ['사업자·가맹점', 'merchants'],
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회' → '가맹점'.
      ['사업자·가맹점 › 가맹점', 'merchants'],
    ])
  })

  it('TXN-OPS-001 (AC 10): 거래 조회 is a 결제 관리 tab for the four view roles only', () => {
    for (const role of ['VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN']) {
      expect(menusFor([role]).find((m) => m.id === 'payments')?.tabs.map((t) => t.page)).toEqual(['paymentOps', 'transactions'])
    }
    expect(pagesFor(['UNMASK'])).not.toContain('transactions')
    expect(pagesFor([])).not.toContain('transactions')
    expect(menuOf('transactions')).toBe('payments')
    expect(searchMenus(menusFor(['VIEWER']), '거래').map((r) => [r.path, r.target])).toEqual([['결제 관리 › 거래 조회', 'transactions']])
  })

  it('MERCHANT-OPS-002 §5.1 (D-MOPS-006 ①) + ADMIN-UI-MERCHANT-002 (D-ADMIN-UI-004 ④): 사업자 등록 and 가맹점 등록 are separate 사업자·가맹점 screens (sub-pages since D-ADMIN-UI-006) for OPERATOR only; the menu description says 조회·등록', () => {
    // 기대값 변경(ADMIN-UI-MERCHANT-002, 사용자 Demo 피드백 2026-11-13): 쪽 머리 '＋ 사업자 등록' 버튼(한 마법사) → 사업자 등록·가맹점 등록 두 화면(탭). 보이는 역할은 OPERATOR만 그대로(D-MOPS-006 ① — 권한 불변).
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 4개 → 2개(등록 두 화면은 하위 화면 — OPERATOR만 그대로, 검색·menuOf 단언은 아래 그대로).
    expect(menusFor(['OPERATOR']).find((m) => m.id === 'merchants')?.tabs.map((t) => t.label)).toEqual(['가맹점', '사업자'])
    for (const roles of [['OPERATOR'], ['OPERATOR', 'APPROVER'], ['VIEWER'], ['APPROVER'], ['SECURITY_ADMIN'], ['UNMASK'], [], ['APPROVER', 'SECURITY_ADMIN', 'VIEWER']]) expect(actions(roles)).toEqual([])
    for (const roles of [['VIEWER'], ['APPROVER'], ['SECURITY_ADMIN'], ['UNMASK'], [], ['APPROVER', 'SECURITY_ADMIN', 'VIEWER']]) {
      expect(pagesFor(roles)).not.toContain('merchantRegistration')
      expect(pagesFor(roles)).not.toContain('businessRegistration')
    }
    expect(pagesFor(['OPERATOR', 'APPROVER'])).toContain('merchantRegistration')
    expect(pagesFor(['OPERATOR', 'APPROVER'])).toContain('businessRegistration')
    expect(menuOf('merchantRegistration')).toBe('merchants')
    expect(menuOf('businessRegistration')).toBe('merchants')
    const description = menusFor(['VIEWER']).find((m) => m.id === 'merchants')?.description ?? ''
    expect(description).toContain('조회·등록')
    expect(description).toContain('사업자 등록부터 서비스 개통까지')
    expect(description).not.toContain('조회 전용')
    expect(searchMenus(menusFor(['OPERATOR']), '사업자 등록').map((r) => [r.path, r.target])).toEqual([['사업자·가맹점 › 사업자 등록', 'businessRegistration']])
    expect(searchMenus(menusFor(['OPERATOR']), '가맹점 등록').map((r) => [r.path, r.target])).toEqual([['사업자·가맹점 › 가맹점 등록', 'merchantRegistration']])
    expect(searchMenus(menusFor(['VIEWER']), '사업자 등록')).toEqual([])
    expect(searchMenus(menusFor(['APPROVER', 'SECURITY_ADMIN']), '등록')).toEqual([])
  })

  it('menuOf maps a screen back to its menu', () => {
    expect(menuOf('audit')).toBe('accounts')
    expect(menuOf('reconciliation')).toBe('recon')
    expect(menuOf('home')).toBe('home')
  })

  it('menu search finds only menus and tabs the role can see (ignores spaces and "·")', () => {
    const operator = menusFor(['OPERATOR'])
    expect(searchMenus(operator, '감사').map((r) => r.path)).toEqual(['계정·권한 › 접속·감사 이력'])
    // 기대값 변경(ACQ-VIEW-001): OPERATOR도 매입·대사 메뉴(매입 현황 탭)를 본다 — 대사 발견 탭은 여전히 없다.
    expect(searchMenus(operator, '대사').map((r) => [r.path, r.target])).toEqual([['매입·대사', 'acquisitions']])
    expect(searchMenus(operator, '계정권한').map((r) => r.target)).toEqual(['accounts'])
    expect(searchMenus(operator, '  ')).toEqual([])
    expect(searchMenus(menusFor(['APPROVER']), '대사').map((r) => [r.path, r.target])).toEqual([
      ['매입·대사', 'reconciliation'],
      ['매입·대사 › 대사 발견', 'reconciliation'],
    ])
  })

  it('ACQ-VIEW-001 (D-ACQV-001): 매입 현황 is a 매입·대사 tab for the four view roles only', () => {
    for (const role of ['VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN']) {
      expect(menusFor([role]).find((m) => m.id === 'recon')?.tabs.map((t) => t.page)).toContain('acquisitions')
    }
    expect(pagesFor(['UNMASK'])).not.toContain('acquisitions')
    expect(pagesFor([])).not.toContain('acquisitions')
    expect(menuOf('acquisitions')).toBe('recon')
    expect(searchMenus(menusFor(['VIEWER']), '매입 현황').map((r) => [r.path, r.target])).toEqual([['매입·대사 › 매입 현황', 'acquisitions']])
  })
})

describe('LAYOUT-ROLLOUT B1 (D-ADMIN-UI-006): 사업자·가맹점 has two tabs (가맹점 · 사업자); 등록 screens are sub-pages of their list tab (display only — pagesFor unchanged)', () => {
  const merchantsMenu = (roles: string[]) => menusFor(roles).find((m) => m.id === 'merchants')
  const subpagesOf = (roles: string[]) => (merchantsMenu(roles)?.subpages ?? []).map((s) => [s.page, s.label, s.parent])

  it('tabs are 가맹점 → 사업자 for every role that sees them; the registration screens are not tabs', () => {
    expect(merchantsMenu(['OPERATOR'])?.tabs.map((t) => [t.page, t.label])).toEqual([['merchants', '가맹점'], ['businesses', '사업자']])
    expect(merchantsMenu(['APPROVER'])?.tabs.map((t) => t.label)).toEqual(['가맹점', '사업자'])
    expect(merchantsMenu(['VIEWER'])?.tabs.map((t) => t.label)).toEqual(['가맹점'])
    expect(merchantsMenu(['SECURITY_ADMIN'])?.tabs.map((t) => t.label)).toEqual(['가맹점'])
  })

  it('sub-pages (사업자 등록 under 사업자, 가맹점 등록 under 가맹점) are kept for OPERATOR only — same pagesFor rule', () => {
    expect(subpagesOf(['OPERATOR'])).toEqual([['businessRegistration', '사업자 등록', 'businesses'], ['merchantRegistration', '가맹점 등록', 'merchants']])
    expect(subpagesOf(['OPERATOR', 'APPROVER'])).toEqual(subpagesOf(['OPERATOR']))
    for (const roles of [['VIEWER'], ['APPROVER'], ['SECURITY_ADMIN'], ['APPROVER', 'SECURITY_ADMIN', 'VIEWER']]) expect(subpagesOf(roles)).toEqual([])
    expect(pagesFor(['OPERATOR'])).toEqual(['mine', 'merchants', 'businesses', 'businessRegistration', 'merchantRegistration', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'audit'])
  })

  it('pagesOf / menuOf / searchMenus still reach the sub-pages; no header action is used', () => {
    expect(pagesOf(merchantsMenu(['OPERATOR'])!).map((t) => t.page)).toEqual(['merchants', 'businesses', 'businessRegistration', 'merchantRegistration'])
    expect(menuOf('businessRegistration')).toBe('merchants')
    expect(menuOf('merchantRegistration')).toBe('merchants')
    expect(searchMenus(menusFor(['OPERATOR']), '등록').map((r) => [r.path, r.target])).toEqual([
      ['사업자·가맹점 › 사업자 등록', 'businessRegistration'],
      ['사업자·가맹점 › 가맹점 등록', 'merchantRegistration'],
    ])
    expect(searchMenus(menusFor(['APPROVER']), '사업자').map((r) => [r.path, r.target])).toEqual([
      ['사업자·가맹점', 'merchants'],
      ['사업자·가맹점 › 사업자', 'businesses'],
    ])
    expect(actions(['OPERATOR'])).toEqual([])
  })
})

describe('BUSINESS-PROFILE-001 BIZ-4-7 (D-BIZ-006 ①): 사업자 조회 = OPERATOR·APPROVER, 사업자 등록 = OPERATOR (display only — the server decides)', () => {
  it('pagesFor / menusFor / searchMenus', () => {
    expect(pagesFor(['OPERATOR'])).toContain('businesses')
    expect(pagesFor(['APPROVER'])).toContain('businesses')
    for (const roles of [['VIEWER'], ['SECURITY_ADMIN'], ['UNMASK'], []]) {
      expect(pagesFor(roles)).not.toContain('businesses')
      expect(pagesFor(roles)).not.toContain('businessRegistration')
    }
    expect(pagesFor(['APPROVER'])).not.toContain('businessRegistration')
    expect(menuOf('businesses')).toBe('merchants')
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '사업자 조회' → '사업자' — 검색어도 탭 이름으로(메뉴 이름 '사업자·가맹점'도 함께 찾힌다).
    expect(searchMenus(menusFor(['APPROVER']), '사업자').map((r) => [r.path, r.target])).toEqual([['사업자·가맹점', 'merchants'], ['사업자·가맹점 › 사업자', 'businesses']])
    // LAYOUT-ROLLOUT B1 reviewFix1 MINOR-2: 탭 이름이 '사업자'가 된 뒤 '사업자 조회' 검색은 누구에게나 빈 결과라 의미가 없다 — 같은 검색어 '사업자'로 VIEWER에게는
    // 메뉴 이름만 찾히고 사업자 탭('› 사업자', businesses)은 없음을 본다(VIEWER에게 사업자 탭이 보이면 실패).
    expect(searchMenus(menusFor(['VIEWER']), '사업자').map((r) => [r.path, r.target])).toEqual([['사업자·가맹점', 'merchants']])
  })
})
