import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'

/**
 * MERCHANT-DETAIL-VIEW-001 2단계 — D-ADMIN-UI-009 ②(사용자 결정 2026-11-24 "기술 정보 접기"):
 * - 가맹점 목록에 '권리 revision' 열이 없다.
 * - 상세의 '권리 revision'과 권리 전파(발행·결제 서비스 수신·효력)는 기본 화면(기본정보 등)에 없고, 상세 패널 끝(변경 이력 위)의 기본 접힘 "기술 정보"(native details) 안에 있다.
 *   열면 값·문구·기록 없음 안내가 예전 그대로 보인다(삭제 아님). summary는 .btn(초점선 summary:focus-visible), style 속성 0.
 * Demo 식별자.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const CODE1 = 'shop000001'
const ITEM: MerchantListItem = {
  merchantId: MER1, merchantCode: CODE1, merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 7, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
/** Named Fixture: 권리 revision 7 발행, 결제 서비스 수신 6, 효력 확인 전. */
const DETAIL: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: CODE1, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 7, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
  sites: [], contracts: [], accounts: [], limits: [],
  propagation: {
    latestRevision: 7, latestRevisionIssuedAt: '2026-11-02T00:00:00.000Z', latestAckRevision: 6, latestAckAt: '2026-11-02T00:01:00.000Z',
    effectConfirmedAt: null, effectBasis: null,
  },
  pendingApprovals: [],
}

async function openDetail(detail: MerchantDetail = DETAIL) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [ITEM], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  const row = await screen.findByRole('row', { name: CODE1 })
  return { row, open: async () => { await userEvent.click(within(row).getByRole('button', { name: '상세' })); return screen.findByRole('group', { name: '기술 정보' }) } }
}

describe('MerchantsScreen — technical info folded (D-ADMIN-UI-009 ②)', () => {
  it('the list has no 권리 revision column and the row does not show the revision', async () => {
    const { row } = await openDetail()
    const table = screen.getByRole('table', { name: '가맹점 목록' })
    // 기대값 변경(MERCHANT-IA-001 B2 — 사용자 결정 D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다", 명세 §7 B2): 늘 '활성'이던 상태 열을 뺐다.
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['가맹점', '사업자', '사업자등록번호', 'MID 수', '등록일', '작업'])
    expect(row.querySelector('[data-col="entitlementRevision"]')).toBeNull()
    expect(within(row).queryByText('7')).toBeNull()
  })

  it('the detail starts with "기술 정보" closed (native details, .btn summary) after the panels and before 변경 이력; 기본정보 has no 권리 revision', async () => {
    const techInfo = await (await openDetail()).open()
    expect(techInfo.tagName).toBe('DETAILS')
    expect(techInfo).not.toHaveAttribute('open')
    const summary = techInfo.querySelector(':scope > summary')!
    expect(summary).toHaveTextContent('기술 정보')
    expect(summary).toHaveClass('btn')
    expect(within(screen.getByRole('region', { name: '기본정보' })).queryByText('권리 revision')).toBeNull()
    // 기술 정보 밖에는 권리 revision·권리 전파가 없다.
    const outside = Array.from(document.querySelectorAll('section[aria-label]')).filter((s) => !techInfo.contains(s)).map((s) => s.getAttribute('aria-label'))
    expect(outside).not.toContain('권리 전파 상태')
    expect(outside).not.toContain('가맹점 권리')
    // 자리: 한도 Panel 뒤, 변경 이력 Panel 앞(권리 전파 패널이 있던 자리).
    const limits = screen.getByRole('region', { name: '한도' })
    const history = screen.getByRole('region', { name: '변경 이력' })
    expect(limits.compareDocumentPosition(techInfo) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(techInfo.compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('opening it shows the revision and the propagation values unchanged (발행·결제 서비스 수신·효력); clicking again folds it', async () => {
    const techInfo = await (await openDetail()).open()
    const summary = techInfo.querySelector(':scope > summary') as HTMLElement
    await userEvent.click(summary)
    expect(techInfo).toHaveAttribute('open')
    const rights = within(techInfo).getByRole('region', { name: '가맹점 권리' })
    expect(within(rights).getByText('권리 revision')).toBeInTheDocument()
    expect(within(rights).getByText('7')).toHaveClass('font-mono')
    const propagation = within(techInfo).getByRole('region', { name: '권리 전파 상태' })
    expect(propagation).toHaveTextContent('발행')
    expect(propagation).toHaveTextContent('최신 revision 7')
    expect(propagation).toHaveTextContent('결제 서비스 수신')
    expect(propagation).toHaveTextContent('수신 확인 revision 6')
    expect(propagation).toHaveTextContent('효력')
    expect(propagation).toHaveTextContent('효력 확인 전')
    await userEvent.click(summary)
    expect(techInfo).not.toHaveAttribute('open')
  })

  it('no propagation record: the opened tech info still shows the revision and the unchanged "기록이 없습니다" notice', async () => {
    const techInfo = await (await openDetail({ ...DETAIL, propagation: null })).open()
    await userEvent.click(techInfo.querySelector(':scope > summary') as HTMLElement)
    expect(within(techInfo).getByRole('region', { name: '가맹점 권리' })).toHaveTextContent('7')
    expect(within(techInfo).getByRole('region', { name: '권리 전파 상태' })).toHaveTextContent('권리 전파 기록이 없습니다.')
  })
})
