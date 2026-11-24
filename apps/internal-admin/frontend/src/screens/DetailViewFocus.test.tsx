import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen } from './BusinessesScreen'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { fakeApi } from '../test/fakeApi'
import { individualRegisteredDetail, listItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-DETAIL-VIEW-001 리뷰 MINOR-2(접근성): 화면 안 버튼 전환(목록 '상세' → 상세 화면, '목록으로' → 목록 화면) 뒤 초점이 body로 떨어지지 않고
 * 새 화면 제목(h2, tabIndex -1 — 초점 링 규칙 `h2[tabindex="-1"]:focus-visible` 대상)으로 간다. App 버튼 전환(reviewFix1 MINOR-3)과 같은 관례.
 * 가맹점 조회·사업자 조회 두 화면, 두 방향. 처음 열 때는 옮기지 않는다. Demo 식별자.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const CODE1 = 'shop000001'
const ITEM: MerchantListItem = {
  merchantId: MER1, merchantCode: CODE1, merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
/** Named Fixture: Site·계약·MID 없는 가맹점(화면 전환만 본다). */
const DETAIL: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: CODE1, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
  sites: [], contracts: [], accounts: [], limits: [], propagation: null, pendingApprovals: [],
}
const REG = individualRegisteredDetail(1)

/** 초점 링 규칙(workspace.css `h2[tabindex="-1"]:focus-visible`)이 고르는 요소인지. */
const expectFocusedTitle = (name: string | RegExp) => {
  const focused = document.activeElement as HTMLElement
  expect(focused).not.toBe(document.body)
  expect(focused.matches('h2[tabindex="-1"]')).toBe(true)
  expect(focused).toBe(screen.getByRole('heading', { level: 2, name }))
}

describe('detail screen switch moves focus to the new screen title (review MINOR-2)', () => {
  it('가맹점 조회: "상세" → the detail screen title; "← 목록으로" → the list title; the first render moves nothing', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [ITEM], nextCursor: null })),
      merchantDetail: vi.fn(async () => DETAIL),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} memory={createScreenMemory()} />)
    expect(document.activeElement).toBe(document.body)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    expect(document.activeElement?.tagName).not.toBe('H2')

    await userEvent.click(within(await screen.findByRole('row', { name: CODE1 })).getByRole('button', { name: '상세' }))
    await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${CODE1}`) })
    expect(screen.queryByRole('table', { name: '가맹점 목록' })).toBeNull()
    expectFocusedTitle('가맹점 조회')
    expect(document.activeElement?.closest('.mapnote')?.querySelector('button')).toHaveTextContent('목록으로')

    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    expect(screen.getByRole('table', { name: '가맹점 목록' })).toBeInTheDocument()
    expectFocusedTitle('가맹점 조회')
    expect(document.activeElement?.closest('.mapnote')?.querySelector('button')).toBeNull()
  })

  it('사업자 조회: "상세" → the business name title once the detail arrives; "목록으로" → the 사업자 조회 title; the first render moves nothing', async () => {
    const api = fakeApi({
      searchBusinesses: vi.fn(async () => ({ items: [listItemOf(REG)], next: null })),
      businessDetail: vi.fn(async () => REG),
    })
    render(<BusinessesScreen api={api} memory={createScreenMemory()} canEdit onContinue={vi.fn()} handoffState={() => 'empty'} />)
    await screen.findByRole('table', { name: '사업자 목록' })
    expect(document.activeElement).toBe(document.body)

    await userEvent.click(screen.getByRole('button', { name: `${REG.business.businessName} 상세` }))
    await screen.findByRole('region', { name: '사업자 상세' })
    await screen.findByRole('heading', { level: 2, name: REG.business.businessName })
    expectFocusedTitle(REG.business.businessName)

    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    expect(await screen.findByRole('table', { name: '사업자 목록' })).toBeInTheDocument()
    expectFocusedTitle('사업자 조회')
  })
})
