// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { menuOf, menusFor, pagesFor, searchMenus } from './navigation'

/** 역할별 메뉴 트리를 [메뉴 이름, [탭 이름…]] 꼴로 펼친다(운영 홈은 탭 없음). */
const tree = (roles: string[]) => menusFor(roles).map((m) => [m.label, m.tabs.map((t) => t.label)])

describe('ADMIN-UI-001 rev 0.4 (D-ADMIN-UI-003): role → sidebar menus and tabs (existing 6 screens only — display only, the server decides)', () => {
  it('the screens each role sees are unchanged (pagesFor)', () => {
    // 기대값 변경(TXN-OPS-001): 조회 4역할에 거래 조회('transactions')가 더해졌다 — 나머지 화면은 그대로.
    // 기대값 변경(ACQ-VIEW-001, D-ACQV-001): 조회 4역할에 매입 현황('acquisitions')이 더해졌다 — 나머지 화면은 그대로.
    expect(pagesFor(['SECURITY_ADMIN'])).toEqual(['mine', 'merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'reconciliation', 'audit'])
    expect(pagesFor(['APPROVER'])).toEqual(['approvals', 'merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'reconciliation', 'audit'])
    expect(pagesFor(['OPERATOR'])).toEqual(['mine', 'merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'audit'])
    expect(pagesFor(['VIEWER'])).toEqual(['merchants', 'paymentOps', 'transactions', 'acquisitions', 'accounts', 'audit'])
    expect(pagesFor(['UNMASK'])).toEqual(['audit'])
  })

  it('SECURITY_ADMIN: home, 내 요청, 결제 관리, 매입·대사, 계정·권한(계정 관리·접속·감사 이력)', () => {
    expect(tree(['SECURITY_ADMIN'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      ['가맹점 관리', ['가맹점 조회']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['대사 발견', '매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('APPROVER: home, 승인함, 결제 관리, 매입·대사, 계정·권한', () => {
    expect(tree(['APPROVER'])).toEqual([
      ['운영 홈', []],
      ['승인함', ['승인함']],
      ['가맹점 관리', ['가맹점 조회']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['대사 발견', '매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('OPERATOR: no 승인함; 매입·대사 with 매입 현황 only (ACQ-VIEW-001 — 대사 발견 stays SECURITY_ADMIN·APPROVER)', () => {
    expect(tree(['OPERATOR'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      ['가맹점 관리', ['가맹점 조회']],
      ['결제 관리', ['결제 보정 취소', '거래 조회']],
      ['매입·대사', ['매입 현황']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('VIEWER: 가맹점 관리, 결제 관리, 매입·대사(매입 현황) and 계정·권한 only', () => {
    expect(tree(['VIEWER'])).toEqual([
      ['운영 홈', []],
      ['가맹점 관리', ['가맹점 조회']],
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
    expect(tree(['SECURITY_ADMIN', 'APPROVER']).map(([label]) => label)).toEqual(['운영 홈', '승인함', '내 요청', '가맹점 관리', '결제 관리', '매입·대사', '계정·권한'])
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

  it('MERCHANT-OPS-001 (AC 11): 가맹점 관리 is shown to the four view roles only (server function exists now — D-ADMIN-UI-003)', () => {
    for (const role of ['VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN']) {
      expect(menusFor([role]).find((m) => m.id === 'merchants')?.tabs.map((t) => t.page)).toEqual(['merchants'])
    }
    expect(menusFor(['UNMASK']).some((m) => m.id === 'merchants')).toBe(false)
    expect(menusFor([]).some((m) => m.id === 'merchants')).toBe(false)
    expect(menuOf('merchants')).toBe('merchants')
    expect(searchMenus(menusFor(['VIEWER']), '가맹점').map((r) => [r.path, r.target])).toEqual([
      ['가맹점 관리', 'merchants'],
      ['가맹점 관리 › 가맹점 조회', 'merchants'],
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
