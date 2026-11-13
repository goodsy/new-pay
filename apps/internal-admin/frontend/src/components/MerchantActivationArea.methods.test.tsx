import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantActivationArea } from './MerchantActivationArea'
import type { MerchantDetail } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'

/**
 * ADMIN-UI-MERCHANT-002 리뷰 MINOR-2: 개통 등록 one-shot을 수단 버튼들이 공유하는 동안, 결과 불명이면 다른 수단 버튼이 같은 submissionId로 나가지 않아야 한다.
 * 실제 목록은 카드 하나뿐이라(정책 미채택) **시험 전용**으로 목록에 가상 수단 하나를 더 끼워 구조만 확인한다 — 운영 목록은 paymentMethods.test.ts가 고정.
 */
vi.mock('../lib/paymentMethods', () => ({
  PAYMENT_METHODS: [{ code: 'CARD', label: '카드' }, { code: 'TEST_ONLY_METHOD', label: '시험수단' }],
}))

const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const NOW = new Date('2026-11-11T03:00:00.000Z')
type Account = MerchantDetail['accounts'][number]
/** Named Fixture: MID 활성, 대표 Site 하나, 결제수단 개통 없음. */
const account: Account = {
  merchantAccountId: id('mid', 1), lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
  issuedAt: '2026-11-10T00:00:00.000Z',
  assignments: [{ assignmentId: id('asg', 1), merchantSiteId: id('site', 1), role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
  paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [],
}
const detail: MerchantDetail = {
  merchant: { merchantId: id('mer', 1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
  sites: [{ merchantSiteId: id('site', 1), channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null }],
  contracts: [], accounts: [account], limits: [], propagation: null, pendingApprovals: [],
}

describe('MerchantActivationArea — shared registration one-shot across payment-method buttons (review MINOR-2)', () => {
  it('while one method registration is unknown, only that button resends (same submissionId) and the other method is blocked', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: id('act', 1), replayed: true })
    const api = fakeApi({ registerPaymentMethodActivation: send })
    let n = 0
    render(
      <MerchantActivationArea api={api} detail={detail} account={account} canOperate now={NOW} onChanged={vi.fn()} onRequested={vi.fn()}
        newSubmissionId={() => (++n).toString(16).padStart(32, '0')} />,
    )
    await userEvent.click(screen.getByRole('button', { name: '개통 등록(카드)' }))
    const resend = await screen.findByRole('button', { name: '같은 개통 등록 다시 보내기' })
    expect(screen.getAllByRole('button', { name: '같은 개통 등록 다시 보내기' })).toHaveLength(1)
    expect(screen.getByRole('button', { name: '개통 등록(시험수단)' })).toBeDisabled()
    expect(resend).toBeEnabled()
    await userEvent.click(resend)
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(send.mock.calls.every(([input]) => input.paymentMethod === 'CARD')).toBe(true)
  })
})
