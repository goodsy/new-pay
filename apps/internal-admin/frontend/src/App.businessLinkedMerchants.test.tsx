import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import type { AdminApi, MerchantDetail, Me } from './api/adminApi'
import { fakeApi } from './test/fakeApi'
import { bizId, individualRegisteredDetail, listItemOf } from './test/businessFixtures'

/**
 * MERCHANT-IA-001 B5(D-ADMIN-UI-010 ⑧ '연결 가맹점' 탭): 사업자 상세 → 연결 가맹점 → '상세'는 가맹점 조회 탭의 그 가맹점 상세를 연다(App 화면 전환 —
 * 가맹점 등록 "가맹점 조회에서 보기"와 같은 경로·같은 상세 조회 1회). 내부 키는 호출 인자로만. 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
const REG = individualRegisteredDetail(1)
const MER = `mer_${'0'.repeat(25)}9`
const MERCHANT: MerchantDetail = {
  merchant: { merchantId: MER, merchantCode: 'shop000009', name: '연결 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-18T00:00:00.000Z' },
  business: { businessId: bizId(1), name: '데모 사업자', registrationNumber: '7781234567', lifecycle: 'ACTIVE' },
  sites: [], contracts: [], accounts: [], limits: [], propagation: null, pendingApprovals: [],
}

describe('App — 사업자 상세 연결 가맹점 → 가맹점 상세(B5)', () => {
  it('"상세" opens the 가맹점 tab on that merchant detail (one detail read), the 가맹점 tab selected', async () => {
    const merchantDetail = vi.fn(async () => MERCHANT)
    const api = fakeApi({
      me: vi.fn(async (): Promise<Me> => ({ actorId: 'adm_01JC00000000000000000000V2', displayName: '승인자', roles: ['APPROVER'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
      searchBusinesses: vi.fn(async () => ({ items: [listItemOf(REG)], next: null })),
      businessDetail: vi.fn(async () => REG),
      merchantSearch: vi.fn(async () => ({ items: [{ merchantId: MER, merchantCode: 'shop000009', merchantName: '연결 가맹점', businessId: bizId(1), businessName: '데모 사업자', registrationNumber: '7781234567', lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 0, createdAt: '2026-11-18T00:00:00.000Z' }], nextCursor: null })),
      merchantDetail,
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    } as Partial<AdminApi>)
    render(<App api={api} />)
    await user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
    await user.click(screen.getByRole('tab', { name: '사업자' }))
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    await user.click(await screen.findByRole('tab', { name: '연결 가맹점 (1)' }))
    await user.click(await screen.findByRole('button', { name: '연결 가맹점 가맹점 상세' }))
    expect(await screen.findByRole('heading', { name: /가맹점 상세 shop000009/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '가맹점' })).toHaveAttribute('aria-selected', 'true')
    expect(merchantDetail).toHaveBeenCalledTimes(1)
    expect(merchantDetail).toHaveBeenCalledWith(MER)
    expect(document.body.innerHTML).not.toContain('mer_')
  })
})
