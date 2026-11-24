import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { AdminApi, MerchantDetail, MerchantListItem } from '../api/adminApi'
import { createScreenMemory, hasUnresolvedRequest, type ScreenMemory } from '../lib/screenMemory'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * LOCK-UNIFY-001(RECOVERY-ROADMAP 2-1, ONESHOT-LOCK-001 미룬 항목): 개통 영역 1인 명령("개통 등록"·"심사 거절")의 결과 불명 잠금과 submissionId는
 * App 메모리(가맹점 조회 메모리)에 둔다 — 상세를 닫거나(다시 검색) 탭을 바꿔(화면 unmount) 다시 열어도 같은 submissionId·같은 본문 재전송만 허용하고,
 * 세션 경계의 [hasUnresolvedRequest]가 그 잠금을 본다(MERCHANT-OPS-002 §4.2·§5.3). Demo 식별자, 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MID1 = id('mid', 1)
const ACT1 = id('act', 1)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 1, createdAt: '2026-11-01T00:00:00.000Z',
}
/** Named Fixture: MID 활성 + PRIMARY Site1, 결제수단 개통 없음, Site1·Site2 심사 결정 없음(개통 등록·심사 거절 모두 가능). */
const detail: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
  sites: [
    { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null },
    { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'second.example.test', lifecycle: 'ACTIVE', review: null },
  ],
  contracts: [],
  accounts: [{
    merchantAccountId: MID1, lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
    issuedAt: '2026-11-10T00:00:00.000Z',
    assignments: [{ assignmentId: id('asg', 1), merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
    paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [],
  }],
  limits: [], propagation: null, pendingApprovals: [],
}

function setup(overrides: Partial<AdminApi>) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  const memory = createScreenMemory()
  let n = 0
  // 매번 다른 값 — 재전송이 새 submissionId를 만들면 단언이 실패한다.
  const newSubmissionId = () => `${++n}`.padStart(32, 'e')
  const show = () => render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval now={NOW} newSubmissionId={newSubmissionId} revisionMemory={memory} />)
  return { memory, show }
}

async function openDetail() {
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  return screen.findByRole('region', { name: `개통 ${MID1}` })
}

/** 탭을 바꿨다 돌아오기(화면 unmount → 같은 App 메모리로 다시 그림) + 상세 다시 열기. */
async function leaveAndReturn(unmount: () => void, memory: ScreenMemory, show: () => { unmount: () => void }) {
  unmount()
  expect(hasUnresolvedRequest(memory)).toBe(true)
  const again = show()
  return { area: await openDetail(), unmount: again.unmount }
}

describe('MerchantsScreen — activation one-shot lock survives closing the detail / switching tabs (LOCK-UNIFY-001)', () => {
  it('"개통 등록" unknown: closing the detail and switching tabs keep the lock and submissionId; hasUnresolvedRequest sees it; only the same request resends', async () => {
    const one = '1'.padStart(32, 'e')
    const registerPaymentMethodActivation = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: ACT1, replayed: true })
    const { memory, show } = setup({ registerPaymentMethodActivation })
    const first = show()
    let area = await openDetail()
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' })
    expect(hasUnresolvedRequest(memory)).toBe(true)

    // 상세 닫기('목록으로' → 다시 검색 — 상세가 사라진다) → 다시 열기.
    // MERCHANT-DETAIL-VIEW-001: 상세가 목록과 다른 화면이 되어 상세 화면에는 검색 버튼이 없다 — 먼저 '목록으로'(닫기 경로만 바뀜, 잠금 단언 그대로).
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    area = await openDetail()
    expect(within(area).getByRole('button', { name: '같은 개통 등록 다시 보내기' })).toBeEnabled()
    expect(within(area).queryByRole('button', { name: '개통 등록(카드)' })).toBeNull()

    // 탭 전환(화면 unmount) → 돌아와 다시 열기.
    const back = await leaveAndReturn(first.unmount, memory, show)
    area = back.area
    await userEvent.click(within(area).getByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    expect(registerPaymentMethodActivation.mock.calls.map((c) => c[0])).toEqual([
      { submissionId: one, merchantAccountId: MID1, paymentMethod: 'CARD' },
      { submissionId: one, merchantAccountId: MID1, paymentMethod: 'CARD' },
    ])
    // 200으로 결정되면 잠금이 풀린다.
    expect(hasUnresolvedRequest(memory)).toBe(false)
    expect(storageIsEmpty()).toBe(true)
  })

  it('"심사 거절" unknown: after closing the detail / switching tabs the form reopens locked with the sent reason, other sites stay blocked, and the resend carries the same submissionId and body', async () => {
    const one = '1'.padStart(32, 'e')
    const rejectSiteReview = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: `${SITE2}#1`, replayed: true })
    const { memory, show } = setup({ rejectSiteReview })
    const first = show()
    let area = await openDetail()
    // MERCHANT-IA-001 B1(명세 rev 0.2 §7 B1): Site 심사 표가 MID 개통 영역 밖(가맹점에서 한 번 — Site 패널)으로 옮겨졌다. 찾는 곳만 바꾼다 — a(개통 영역)는 상세가
    // 다시 그려진 뒤를 기다리는 데만 쓰이고, 아래 잠금·재전송 단언은 한 글자도 바꾸지 않는다.
    // MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③): 행 이름이 Site 키 대신 채널 식별값이 되어 찾는 이름만 바꾼다(아래 잠금·재전송 단언은 그대로).
    const channelOf: Record<string, string> = { [SITE1]: '웹 · shop.example.test', [SITE2]: '웹 · second.example.test' }
    const rowOf = (a: HTMLElement, site: string) => within(within(a.closest('.detailarea') as HTMLElement).getByRole('table', { name: 'Site 심사' })).getByRole('row', { name: `심사 ${channelOf[site]}` })
    await userEvent.click(within(rowOf(area, SITE2)).getByRole('button', { name: '심사 거절' }))
    await userEvent.selectOptions(within(rowOf(area, SITE2)).getByLabelText('거절 사유'), 'SITE_UNREACHABLE')
    await userEvent.type(within(rowOf(area, SITE2)).getByLabelText('PG 심사 근거(선택)'), 'PG-REVIEW/2026-0002')
    await userEvent.click(within(rowOf(area, SITE2)).getByRole('button', { name: '심사 거절' }))
    await userEvent.click(within(within(rowOf(area, SITE2)).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    await within(rowOf(area, SITE2)).findByRole('button', { name: '같은 거절 다시 보내기' })
    expect(hasUnresolvedRequest(memory)).toBe(true)

    // 상세 닫기 → 다시 열기: 잠긴 거절 폼이 다시 열려 있고 보낸 사유·근거를 보인다.
    // MERCHANT-DETAIL-VIEW-001: 상세 화면에는 검색 버튼이 없다 — 먼저 '목록으로'(닫기 경로만 바뀜, 잠금 단언 그대로).
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    area = await openDetail()
    expect(within(rowOf(area, SITE2)).getByRole('button', { name: '같은 거절 다시 보내기' })).toBeInTheDocument()
    expect(within(rowOf(area, SITE2)).getByLabelText('거절 사유')).toBeDisabled()
    expect(within(rowOf(area, SITE2)).getByLabelText('거절 사유')).toHaveValue('SITE_UNREACHABLE')
    expect(within(rowOf(area, SITE2)).getByLabelText('PG 심사 근거(선택)')).toHaveValue('PG-REVIEW/2026-0002')
    expect(within(rowOf(area, SITE1)).getByRole('button', { name: '심사 거절' })).toBeDisabled()

    // 탭 전환 → 돌아와 다시 열기 → 같은 거절 재전송.
    const back = await leaveAndReturn(first.unmount, memory, show)
    area = back.area
    expect(within(rowOf(area, SITE1)).getByRole('button', { name: '심사 거절' })).toBeDisabled()
    await userEvent.click(within(rowOf(area, SITE2)).getByRole('button', { name: '같은 거절 다시 보내기' }))
    await userEvent.click(within(within(rowOf(area, SITE2)).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    expect(rejectSiteReview.mock.calls.map((c) => c[0])).toEqual(
      Array(2).fill({ submissionId: one, merchantSiteId: SITE2, reasonCode: 'SITE_UNREACHABLE', evidenceRef: 'PG-REVIEW/2026-0002' }),
    )
    expect(hasUnresolvedRequest(memory)).toBe(false)
    expect(storageIsEmpty()).toBe(true)
  })
})
