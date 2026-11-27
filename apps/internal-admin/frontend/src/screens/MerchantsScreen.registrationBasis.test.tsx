import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'

/**
 * MERCHANT-FLOW-001 FLOW-3(BR-BIZ-337) — 가맹점 상세 기본정보의 '등록 근거': 등록 관문을 통과할 때 기록한 사업자 계약 순번·버전 번호(기록 시점 사실).
 * 근거 행이 없는(관문 전에 등록된) 가맹점은 "관문 전 등록 — 근거 기록 없음". 내부 키(mer_·biz_)는 DOM에 없다. Demo 식별자.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const NOW = () => new Date('2026-11-27T03:00:00.000Z')
const item: MerchantListItem = {
  merchantId: MER1, merchantCode: 'shopabcd12', merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890',
  lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
function detail(patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: 'shopabcd12', name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
    sites: [], contracts: [], accounts: [], limits: [],
    propagation: { latestRevision: 1, latestRevisionIssuedAt: '2026-11-01T00:00:00.000Z', latestAckRevision: null, latestAckAt: null, effectConfirmedAt: null, effectBasis: null },
    pendingApprovals: [], merchantCodeChangeable: false,
    ...patch,
  }
}

async function openDetail(d: MerchantDetail) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => d),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
  })
  const view = render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={false} now={NOW} newSubmissionId={() => 'c'.repeat(32)} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: 'shopabcd12' })).getByRole('button', { name: '상세' }))
  await screen.findByRole('heading', { name: /가맹점 상세/ })
  return view
}
const basicPanel = () => screen.getByRole('region', { name: '기본정보' })

describe('MERCHANT-FLOW-001 FLOW-3 — 가맹점 상세 등록 근거', () => {
  it('근거 있음: 기본정보에 "등록 근거 — 사업자 계약 2 · 버전 3"(등록 때 기록)', async () => {
    const view = await openDetail(detail({ registrationBasis: { contractSeq: 2, versionNo: 3 } }))
    expect(basicPanel()).toHaveTextContent('등록 근거')
    expect(basicPanel()).toHaveTextContent('사업자 계약 2 · 버전 3')
    expect(basicPanel()).toHaveTextContent('등록 때 기록 — 뒤의 계약 변경·해지로 바뀌지 않습니다')
    expect(view.container.innerHTML).not.toContain('mer_')
    expect(view.container.innerHTML).not.toContain('biz_')
  })

  it('근거 없음(null — 관문 전에 등록된 가맹점): "관문 전 등록 — 근거 기록 없음"', async () => {
    await openDetail(detail({ registrationBasis: null }))
    expect(basicPanel()).toHaveTextContent('등록 근거')
    expect(basicPanel()).toHaveTextContent('관문 전 등록 — 근거 기록 없음')
    expect(basicPanel()).not.toHaveTextContent('사업자 계약 ')
  })
})
