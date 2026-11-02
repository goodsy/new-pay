// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { menuOf, menusFor, pagesFor, searchMenus } from './navigation'

/** 역할별 메뉴 트리를 [메뉴 이름, [탭 이름…]] 꼴로 펼친다(운영 홈은 탭 없음). */
const tree = (roles: string[]) => menusFor(roles).map((m) => [m.label, m.tabs.map((t) => t.label)])

describe('ADMIN-UI-001 rev 0.4 (D-ADMIN-UI-003): role → sidebar menus and tabs (existing 6 screens only — display only, the server decides)', () => {
  it('the screens each role sees are unchanged (pagesFor)', () => {
    expect(pagesFor(['SECURITY_ADMIN'])).toEqual(['mine', 'paymentOps', 'accounts', 'reconciliation', 'audit'])
    expect(pagesFor(['APPROVER'])).toEqual(['approvals', 'paymentOps', 'accounts', 'reconciliation', 'audit'])
    expect(pagesFor(['OPERATOR'])).toEqual(['mine', 'paymentOps', 'accounts', 'audit'])
    expect(pagesFor(['VIEWER'])).toEqual(['paymentOps', 'accounts', 'audit'])
    expect(pagesFor(['UNMASK'])).toEqual(['audit'])
  })

  it('SECURITY_ADMIN: home, 내 요청, 결제 관리, 매입·대사, 계정·권한(계정 관리·접속·감사 이력)', () => {
    expect(tree(['SECURITY_ADMIN'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      ['결제 관리', ['결제 보정 취소']],
      ['매입·대사', ['대사 발견']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('APPROVER: home, 승인함, 결제 관리, 매입·대사, 계정·권한', () => {
    expect(tree(['APPROVER'])).toEqual([
      ['운영 홈', []],
      ['승인함', ['승인함']],
      ['결제 관리', ['결제 보정 취소']],
      ['매입·대사', ['대사 발견']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('OPERATOR: no 승인함 and no 매입·대사', () => {
    expect(tree(['OPERATOR'])).toEqual([
      ['운영 홈', []],
      ['내 요청', ['내 요청']],
      ['결제 관리', ['결제 보정 취소']],
      ['계정·권한', ['계정 관리', '접속·감사 이력']],
    ])
  })

  it('VIEWER: 결제 관리 and 계정·권한 only', () => {
    expect(tree(['VIEWER'])).toEqual([
      ['운영 홈', []],
      ['결제 관리', ['결제 보정 취소']],
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
    expect(tree(['SECURITY_ADMIN', 'APPROVER']).map(([label]) => label)).toEqual(['운영 홈', '승인함', '내 요청', '결제 관리', '매입·대사', '계정·권한'])
  })

  it('menus sit under the reference section labels; inbox menus have no tab bar, business menus do', () => {
    const menus = menusFor(['SECURITY_ADMIN', 'APPROVER'])
    expect(menus.map((m) => [m.id, m.section?.label ?? null, m.tabbed])).toEqual([
      ['home', null, false],
      ['approvals', '내 업무', false],
      ['mine', '내 업무', false],
      ['payments', 'PAYMENT OPERATIONS', true],
      ['recon', 'PAYMENT OPERATIONS', true],
      ['accounts', 'WORKSPACE', true],
    ])
  })

  it('menuOf maps a screen back to its menu', () => {
    expect(menuOf('audit')).toBe('accounts')
    expect(menuOf('reconciliation')).toBe('recon')
    expect(menuOf('home')).toBe('home')
  })

  it('menu search finds only menus and tabs the role can see (ignores spaces and "·")', () => {
    const operator = menusFor(['OPERATOR'])
    expect(searchMenus(operator, '감사').map((r) => r.path)).toEqual(['계정·권한 › 접속·감사 이력'])
    expect(searchMenus(operator, '대사')).toEqual([])
    expect(searchMenus(operator, '계정권한').map((r) => r.target)).toEqual(['accounts'])
    expect(searchMenus(operator, '  ')).toEqual([])
    expect(searchMenus(menusFor(['APPROVER']), '대사').map((r) => [r.path, r.target])).toEqual([
      ['매입·대사', 'reconciliation'],
      ['매입·대사 › 대사 발견', 'reconciliation'],
    ])
  })
})
