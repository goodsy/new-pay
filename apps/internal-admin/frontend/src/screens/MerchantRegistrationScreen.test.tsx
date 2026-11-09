import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { kstDate, MerchantRegistrationScreen } from './MerchantRegistrationScreen'
import { App } from '../App'
import type { MerchantDetail, Me, RegistrationOutcome } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { ERROR_MESSAGES } from '../errors/messages'

/** Demo 식별자(MASTER 형식 — 접두사 + Crockford base32 26자). 업무 의미 없는 고정값. */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const BIZ = id('biz', 7)
const MER = id('mer', 7)
const SITE = id('site', 7)
const CTR = id('ctr', 7)
const CSA = id('csa', 7)
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). 화면에 평문으로 남으면 안 된다. */
const BRN = '7781234560'
const succeeded = (reference: string, replayed = false): RegistrationOutcome => ({ outcome: 'SUCCEEDED', reference, replayed })
const rejected = (rejection: string, replayed = false): RegistrationOutcome => ({ outcome: 'REJECTED', rejection, replayed })

/** 제출마다 다른 고정 submissionId(32 hex). */
function ids() {
  let n = 0
  return () => (++n).toString(16).padStart(32, '0')
}

// 게이트 부하(Demo·Kotlin 시험 동시 실행)에서 5초 제한을 넘지 않도록 입력 지연을 없앤다(TEST-PERF-003과 같은 방식 — 한 자씩 입력·확인 내용은 그대로).
const user = userEvent.setup({ delay: null })
const step = (name: string) => screen.getByRole('region', { name })
// 5단계 흐름 시험이 게이트 부하에서 5초를 넘지 않도록 고정 문자열은 붙여넣는다(TEST-PERF-003과 같은 방식). 날짜 입력은 붙여넣기를 받지 않아 입력을 유지한다.
const typeIn = async (region: HTMLElement, label: string, value: string) => {
  const field = within(region).getByLabelText(label)
  if ((field as HTMLInputElement).type === 'date') return user.type(field, value)
  await user.click(field)
  await user.paste(value)
}
const click = async (region: HTMLElement, name: string) => user.click(within(region).getByRole('button', { name }))

async function registerBusiness() {
  const region = step('① 사업자')
  await typeIn(region, '사업자등록번호', BRN)
  await typeIn(region, '사업자명', '데모 사업자')
  await click(region, '사업자 등록')
  return region
}

describe('MerchantRegistrationScreen (MERCHANT-OPS-002 §5·§8.1 B1-1 — ① 사업자 → ② 가맹점 → ③ Site)', () => {
  it('①→②→③: success ids fill the next step automatically, the sent bodies carry one submissionId each, and the completion panel opens the lookup tab', async () => {
    const api = fakeApi({
      registerBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
    })
    const onOpenLookup = vi.fn()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} />)

    const business = await registerBusiness()
    expect(api.registerBusiness).toHaveBeenCalledWith({ submissionId: '1'.padStart(32, '0'), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(await within(business).findByText(BIZ)).toBeInTheDocument()
    expect(within(business).getByText('778*******')).toBeInTheDocument()

    const merchant = step('② 가맹점')
    expect(within(merchant).getByLabelText('사업자 ID')).toHaveValue(BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: '2'.padStart(32, '0'), businessId: BIZ, displayName: '데모 가맹점' })
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()

    const site = step('③ Site')
    expect(within(site).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: '3'.padStart(32, '0'), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
    expect(await within(site).findByText(SITE)).toBeInTheDocument()

    const done = step('등록 결과')
    expect(within(done).getByText(BIZ)).toBeInTheDocument()
    expect(within(done).getByText(MER)).toBeInTheDocument()
    expect(within(done).getByText(SITE)).toBeInTheDocument()
    await click(done, '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledTimes(1)
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
    expect(storageIsEmpty()).toBe(true)
  })

  it('the registration-number input is emptied after submit and the plain number never appears on screen (masked value only)', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => succeeded(BIZ)) })
    const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = await registerBusiness()
    expect(within(business).getByLabelText('사업자등록번호')).toHaveValue('')
    await within(business).findByText(BIZ)
    expect(container.textContent).not.toContain(BRN)
    expect(container.innerHTML).not.toContain(BRN)
    expect(within(business).getByLabelText('사업자등록번호')).toHaveAttribute('autocomplete', 'off')
    expect(within(business).getByLabelText('사업자등록번호')).not.toHaveAttribute('type', 'password')
  })

  it('a duplicate registration number shows the fixed sentence only (no business id or link); a replayed decision says so', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => rejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE')) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = await registerBusiness()
    const alert = await within(business).findByRole('alert')
    expect(alert).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    expect(alert.textContent).not.toMatch(/biz_/)
    expect(within(business).queryAllByRole('link')).toHaveLength(0)
    expect(within(step('② 가맹점')).getByLabelText('사업자 ID')).toHaveValue('')

    const replay = fakeApi({ registerMerchantSite: vi.fn(async () => rejected('SITE_CHANNEL_DUPLICATE', true)) })
    render(<MerchantRegistrationScreen api={replay} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const site = screen.getAllByRole('region', { name: '③ Site' })[1]!
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('이미 다른 Site가 쓰는 채널 식별값입니다 (이전에 처리된 결과입니다)')
  })

  it('§5.3: an unknown outcome locks the form and offers only "같은 내용 다시 보내기" with the same submissionId; a decided answer unlocks it', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_RETRY_SAME_KEY'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
    const api = fakeApi({ registerBusiness: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = await registerBusiness()

    const group = await within(business).findByRole('group', { name: '결과 확인 필요' })
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    expect(within(business).getByLabelText('사업자등록번호')).toBeDisabled()
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeDisabled()

    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    await within(business).findByRole('group', { name: '결과 확인 필요' })
    await user.click(within(business).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await within(business).findByText(BIZ)).toBeInTheDocument()
    expect(within(business).getByText(/이전에 처리된 결과입니다/)).toBeInTheDocument()

    expect(send).toHaveBeenCalledTimes(3)
    const bodies = send.mock.calls.map(([input]) => input)
    expect(new Set(bodies.map((b) => JSON.stringify(b))).size).toBe(1)
    expect(bodies[0]).toEqual({ submissionId: '1'.padStart(32, '0'), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(within(business).getByLabelText('사업자명')).toBeEnabled()
    expect(storageIsEmpty()).toBe(true)
  })

  it('a network failure and AUDIT_UNAVAILABLE are treated as unknown too; "취소하고 새로 입력" warns first and the next submit uses a new submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE'))
      .mockResolvedValueOnce(succeeded(MER))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const merchant = step('② 가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await within(merchant).findByRole('group', { name: '결과 확인 필요' })
    await click(merchant, '같은 내용 다시 보내기')
    await within(merchant).findByRole('group', { name: '결과 확인 필요' })
    await click(merchant, '취소하고 새로 입력')
    expect(within(merchant).getByText(/앞 요청이 이미 처리됐을 수 있습니다 — 가맹점 조회로 먼저 확인하세요/)).toBeInTheDocument()
    await click(merchant, '그래도 새로 입력')
    expect(within(merchant).getByLabelText('가맹점명')).toBeEnabled()
    await click(merchant, '가맹점 등록')
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual(['1'.padStart(32, '0'), '1'.padStart(32, '0'), '2'.padStart(32, '0')])
  })

  it('§5.3 (review M-1): while a resend is pending, MASTER_BUSY / 429 / 403 keep the form locked with the same submissionId and show that error', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_BUSY'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockResolvedValueOnce(succeeded(MER, true))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const merchant = step('② 가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await within(merchant).findByRole('group', { name: '결과 확인 필요' })

    for (const message of ['가맹점 정보 서비스가 바빠', '너무 잦습니다', '권한이 없습니다']) {
      await click(merchant, '같은 내용 다시 보내기')
      const group = await within(merchant).findByRole('group', { name: '결과 확인 필요' })
      expect(within(group).getByRole('alert')).toHaveTextContent(message)
      expect(within(group).getByRole('alert')).toHaveTextContent('결과 확인 필요')
      expect(within(merchant).getByLabelText('가맹점명')).toBeDisabled()
      expect(within(merchant).getByRole('button', { name: '가맹점 등록' })).toBeDisabled()
      expect(within(group).getByRole('button', { name: '취소하고 새로 입력' })).toBeInTheDocument()
    }
    await click(merchant, '같은 내용 다시 보내기')
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(5)
    expect(new Set(send.mock.calls.map(([input]) => JSON.stringify(input))).size).toBe(1)
    expect(send.mock.calls[0]![0].submissionId).toBe('1'.padStart(32, '0'))
  })

  it('§5.3 (review M-1): a 409 on a resend releases the lock and the next submit uses a new submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockResolvedValueOnce(succeeded(MER))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const merchant = step('② 가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await click(await within(merchant).findByRole('group', { name: '결과 확인 필요' }), '같은 내용 다시 보내기')
    expect(await within(merchant).findByRole('alert')).toHaveTextContent('화면 결함 신호')
    expect(within(merchant).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(within(merchant).getByLabelText('가맹점명')).toBeEnabled()
    await click(merchant, '가맹점 등록')
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual(['1', '1', '2'].map((n) => n.padStart(32, '0')))
  })

  it('review m-2: AUDIT_UNAVAILABLE on registration says the result is uncertain (not "조회하지 않았습니다"); the lookup wording stays as is', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => { throw apiError(503, 'AUDIT_UNAVAILABLE') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = await registerBusiness()
    const group = await within(business).findByRole('group', { name: '결과 확인 필요' })
    const alert = within(group).getByRole('alert')
    expect(alert).toHaveTextContent('감사 기록을 남기지 못했습니다. 처리 여부가 확실하지 않으니 가맹점 조회로 확인하거나 같은 내용으로 다시 보내세요.')
    expect(alert.textContent).not.toContain('조회하지 않았습니다')
    expect(ERROR_MESSAGES.AUDIT_UNAVAILABLE).toBe('감사 기록을 남길 수 없어 조회하지 않았습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('review m-3: "가맹점 조회에서 보기" after a Site on an existing merchant passes that merchant id', async () => {
    const onOpenLookup = vi.fn()
    const api = fakeApi({ registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} />)
    const site = step('③ Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    await click(await screen.findByRole('region', { name: '등록 결과' }), '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
  })

  it('409 IDEMPOTENCY_KEY_REUSED is shown as a defect and allows a new submission (new submissionId); 403 and 429 show their fixed messages', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
    const api = fakeApi({ registerMerchantSite: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const site = step('③ Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('화면 결함 신호')
    expect(within(site).getByLabelText('채널 식별값')).toBeEnabled()
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('권한이 없습니다')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('너무 잦습니다')
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual(['1', '2', '3'].map((n) => n.padStart(32, '0')))
  })

  it('formats are checked on screen without sending (no automatic normalization): hyphenated number, upper-case host, malformed ids', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = step('① 사업자')
    await typeIn(business, '사업자등록번호', '778-12-345')
    await typeIn(business, '사업자명', '데모')
    await click(business, '사업자 등록')
    expect(within(business).getByText('사업자등록번호는 숫자 10자리입니다(하이픈 없이).')).toBeInTheDocument()

    const site = step('③ Site')
    await typeIn(site, '가맹점 ID', 'mer_1')
    await typeIn(site, '채널 식별값', 'Shop.Example.test')
    await click(site, 'Site 등록')
    expect(within(site).getByText('가맹점 ID 형식(mer_…)이 아닙니다.')).toBeInTheDocument()
    expect(within(site).getByLabelText('채널 식별값')).toHaveValue('Shop.Example.test')
    expect(api.registerBusiness).not.toHaveBeenCalled()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('B1-2 ④→⑤: party/merchant auto-filled, ③ site offered as a SITES scope checkbox, KST dates sent as typed, ⑤ auto-filled, completion panel opens the merchant detail', async () => {
    const api = fakeApi({
      registerBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
      registerContract: vi.fn(async () => succeeded(CTR)),
      addServiceAgreement: vi.fn(async () => succeeded(CSA)),
    })
    const onOpenLookup = vi.fn()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await registerBusiness()
    await within(step('① 사업자')).findByText(BIZ)
    await typeIn(step('② 가맹점'), '가맹점명', '데모 가맹점')
    await click(step('② 가맹점'), '가맹점 등록')
    await within(step('② 가맹점')).findByText(MER)
    await typeIn(step('③ Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('③ Site'), 'Site 등록')
    await within(step('③ Site')).findByText(SITE)

    const contract = step('④ 계약 초안')
    expect(within(contract).getByLabelText('사업자 ID')).toHaveValue(BIZ)
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    expect(within(contract).getByLabelText('시작일')).toHaveAttribute('min', '2026-11-08')
    expect(within(contract).getByText('이 날 0시 종료(미포함)')).toBeInTheDocument()
    expect(within(contract).getByLabelText('가맹점 전체')).toBeChecked()
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await typeIn(contract, '종료일', '2027-01-01')
    await click(contract, '계약 초안 등록')
    expect(api.registerContract).toHaveBeenCalledWith({
      submissionId: '4'.padStart(32, '0'), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [SITE], effectiveFromDate: '2026-11-09', effectiveToDate: '2027-01-01',
    })
    expect(await within(contract).findByText(CTR)).toBeInTheDocument()
    expect(within(contract).getByText(/미승인/)).toBeInTheDocument()

    const agreement = step('⑤ 서비스 계약')
    expect(within(agreement).getByLabelText('계약 ID')).toHaveValue(CTR)
    expect(within(agreement).getByLabelText('서비스 종류')).toHaveValue('CARD_PG_PAYMENT')
    expect(within(agreement).getByRole('option', { name: '카드 PG 결제' })).toBeInTheDocument()
    await click(agreement, '서비스 계약 추가')
    expect(api.addServiceAgreement).toHaveBeenCalledWith({ submissionId: '5'.padStart(32, '0'), contractId: CTR, serviceKind: 'CARD_PG_PAYMENT' })
    expect(await within(agreement).findByText(CSA)).toBeInTheDocument()

    const done = step('등록 결과')
    for (const created of [BIZ, MER, SITE, CTR, CSA]) expect(within(done).getByText(created)).toBeInTheDocument()
    expect(within(done).getByText(/결제는 아직 할 수 없습니다/)).toBeInTheDocument()
    await click(done, '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
    expect(storageIsEmpty()).toBe(true)
  })

  it('B1-2 ④: a MERCHANT scope sends an empty site list and no end date; a start before the picker minimum is still sent (MASTER decides) and CONTRACT_START_RETROACTIVE shows its fixed sentence', async () => {
    const send = vi.fn(async () => rejected('CONTRACT_START_RETROACTIVE'))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-07')
    await click(contract, '계약 초안 등록')
    expect(send).toHaveBeenCalledWith({ submissionId: '1'.padStart(32, '0'), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-07' })
    expect(await within(contract).findByRole('alert')).toHaveTextContent('시작일이 오늘보다 앞입니다')
    expect(within(contract).getByLabelText('시작일')).toBeEnabled()
  })

  it('B1-2 ④: screen format checks without sending — SITES scope with no site, malformed typed site id, end not after start, missing start', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await click(contract, '계약 초안 등록')
    expect(within(contract).getByText('시작일을 고르세요.')).toBeInTheDocument()
    await typeIn(contract, '시작일', '2026-11-09')
    await typeIn(contract, '종료일', '2026-11-09')
    await user.click(within(contract).getByLabelText('선택 Site'))
    await click(contract, '계약 초안 등록')
    expect(within(contract).getByText('종료일은 시작일보다 뒤여야 합니다.')).toBeInTheDocument()
    expect(within(contract).getByText('Site를 하나 이상 고르거나 입력하세요.')).toBeInTheDocument()
    await typeIn(contract, 'Site ID 직접 입력', 'site_1')
    await click(contract, '계약 초안 등록')
    expect(within(contract).getByText('Site ID 형식(site_…)이 아닙니다.')).toBeInTheDocument()
    expect(api.registerContract).not.toHaveBeenCalled()
  })

  it('B1-2 ④ (§5.3): an unknown outcome locks the contract form; the resend reuses the same submissionId and the same dates; a decided answer unlocks it', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(CTR, true))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    const group = await within(contract).findByRole('group', { name: '결과 확인 필요' })
    expect(within(contract).getByLabelText('시작일')).toBeDisabled()
    expect(within(contract).getByLabelText('가맹점 ID')).toBeDisabled()
    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await within(contract).findByText(CTR)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(within(step('⑤ 서비스 계약')).getByLabelText('계약 ID')).toHaveValue(CTR)
  })

  it('B1-2 ⑤: a duplicate service kind shows its fixed sentence without any agreement id; "가맹점 조회에서 보기" after ④ on an existing merchant opens that merchant', async () => {
    const onOpenLookup = vi.fn()
    const api = fakeApi({
      registerContract: vi.fn(async () => succeeded(CTR)),
      addServiceAgreement: vi.fn(async () => rejected('SERVICE_KIND_DUPLICATE')),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-08')
    await click(contract, '계약 초안 등록')
    await within(contract).findByText(CTR)
    const agreement = step('⑤ 서비스 계약')
    await click(agreement, '서비스 계약 추가')
    const alert = await within(agreement).findByRole('alert')
    expect(alert).toHaveTextContent('이미 같은 서비스 종류의 계약이 있습니다')
    expect(alert.textContent).not.toMatch(/csa_/)
    await click(await screen.findByRole('region', { name: '등록 결과' }), '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
  })

  it('review m-1 (auto-fill vs lock): ④ locked by MASTER_OUTCOME_UNKNOWN keeps its 가맹점 ID and offered sites when ③ succeeds for another merchant; the resend body equals the first', async () => {
    const MER2 = id('mer', 8)
    const SITE2 = id('site', 8)
    const contractSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(CTR, true))
    const api = fakeApi({
      registerMerchantSite: vi.fn().mockResolvedValueOnce(succeeded(SITE)).mockResolvedValueOnce(succeeded(SITE2)),
      registerContract: contractSend,
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const site = step('③ Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    await within(site).findByText(SITE)

    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByRole('group', { name: '결과 확인 필요' })

    // ③ 다른 가맹점으로 성공 — 잠긴 ④에는 채우지 않는다.
    await user.clear(within(site).getByLabelText('가맹점 ID'))
    await typeIn(site, '가맹점 ID', MER2)
    await user.clear(within(site).getByLabelText('채널 식별값'))
    await typeIn(site, '채널 식별값', 'demo-shop3.paycore.test')
    await click(site, 'Site 등록')
    await within(site).findByText(SITE2)

    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    expect(within(contract).getAllByRole('checkbox').map((c) => c.closest('label')?.textContent)).toEqual([SITE])
    expect(within(contract).getByRole('checkbox', { name: SITE })).toBeChecked()

    await click(contract, '같은 내용 다시 보내기')
    expect(await within(contract).findByText(CTR)).toBeInTheDocument()
    expect(contractSend).toHaveBeenCalledTimes(2)
    expect(contractSend.mock.calls[1]![0]).toEqual(contractSend.mock.calls[0]![0])
    expect(contractSend.mock.calls[0]![0]).toEqual({
      submissionId: '2'.padStart(32, '0'), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [SITE], effectiveFromDate: '2026-11-09',
    })
  })

  it('review m-1 (auto-fill vs lock): ⑤ locked keeps its 계약 ID when ④ succeeds; the resend body equals the first', async () => {
    const CTR2 = id('ctr', 8)
    const agreementSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(CSA, true))
    const api = fakeApi({ registerContract: vi.fn(async () => succeeded(CTR2)), addServiceAgreement: agreementSend })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    const agreement = step('⑤ 서비스 계약')
    await typeIn(agreement, '계약 ID', CTR)
    await click(agreement, '서비스 계약 추가')
    await within(agreement).findByRole('group', { name: '결과 확인 필요' })

    const contract = step('④ 계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByText(CTR2)

    expect(within(agreement).getByLabelText('계약 ID')).toHaveValue(CTR)
    await click(agreement, '같은 내용 다시 보내기')
    expect(await within(agreement).findByText(CSA)).toBeInTheDocument()
    expect(agreementSend.mock.calls[1]![0]).toEqual(agreementSend.mock.calls[0]![0])
    expect(agreementSend.mock.calls[0]![0]).toEqual({ submissionId: '1'.padStart(32, '0'), contractId: CTR, serviceKind: 'CARD_PG_PAYMENT' })
  })

  it('review m-1 (auto-fill vs lock, B1-1): ② locked keeps its 사업자 ID when ① succeeds; ③ locked keeps its 가맹점 ID when ② succeeds, while unlocked ④ is still filled', async () => {
    const BIZ2 = id('biz', 8)
    const MER2 = id('mer', 8)
    const merchantSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(MER2))
    const siteSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(SITE, true))
    const api = fakeApi({ registerBusiness: vi.fn(async () => succeeded(BIZ2)), registerMerchant: merchantSend, registerMerchantSite: siteSend })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)

    const site = step('③ Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    await within(site).findByRole('group', { name: '결과 확인 필요' })

    const merchant = step('② 가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await within(merchant).findByRole('group', { name: '결과 확인 필요' })

    await registerBusiness()
    await within(step('① 사업자')).findByText(BIZ2)
    expect(within(merchant).getByLabelText('사업자 ID')).toHaveValue(BIZ)

    await click(merchant, '같은 내용 다시 보내기')
    await within(merchant).findByText(MER2)
    expect(merchantSend.mock.calls[1]![0]).toEqual(merchantSend.mock.calls[0]![0])
    expect(within(site).getByLabelText('가맹점 ID')).toHaveValue(MER)
    const contract = step('④ 계약 초안')
    expect(within(contract).getByLabelText('사업자 ID')).toHaveValue(BIZ)
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER2)

    await click(site, '같은 내용 다시 보내기')
    expect(await within(site).findByText(SITE)).toBeInTheDocument()
    expect(siteSend.mock.calls[1]![0]).toEqual(siteSend.mock.calls[0]![0])
    expect(siteSend.mock.calls[0]![0]).toEqual({ submissionId: '1'.padStart(32, '0'), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
  })

  it('kstDate: the Asia/Seoul calendar date of an instant (UTC 15:00 boundary)', () => {
    expect(kstDate(new Date('2026-11-08T14:59:59.999Z'))).toBe('2026-11-08')
    expect(kstDate(new Date('2026-11-08T15:00:00.000Z'))).toBe('2026-11-09')
    expect(kstDate(new Date('2026-12-31T15:00:00.000Z'))).toBe('2027-01-01')
  })

  it('a session-ending answer hands control back to the app', async () => {
    const onSessionEnded = vi.fn()
    const api = fakeApi({ registerBusiness: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} onSessionEnded={onSessionEnded} newSubmissionId={ids()} />)
    await registerBusiness()
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
  })
})

describe('App wiring (MERCHANT-OPS-002 §5.1 — OPERATOR only, display only)', () => {
  function signedInWithApi(roles: string[], overrides: Parameters<typeof fakeApi>[0] = {}) {
    const api = fakeApi({
      me: vi.fn(async (): Promise<Me> => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles })),
      approvals: vi.fn(async () => ({ approvals: [] })),
      reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
      accounts: vi.fn(async () => ({ accounts: [] })),
      audit: vi.fn(async () => ({ entries: [] })),
      ...overrides,
    })
    return { view: render(<App api={api} />), api }
  }
  const signedIn = (roles: string[], overrides: Parameters<typeof fakeApi>[0] = {}) => signedInWithApi(roles, overrides).view
  const openMerchants = async () =>
    user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '가맹점 관리' }))

  it('an OPERATOR sees the 가맹점 등록 tab next to 가맹점 조회 and it opens the registration steps', async () => {
    signedIn(['OPERATOR'])
    await openMerchants()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점 조회', '가맹점 등록'])
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    expect(await screen.findByRole('region', { name: '① 사업자' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '② 가맹점' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '③ Site' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '④ 계약 초안' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '⑤ 서비스 계약' })).toBeInTheDocument()
  })

  it('review m-1: an unknown outcome survives switching to 가맹점 조회 and back — still locked, the resend reuses the same submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
    signedIn(['OPERATOR'], { registerBusiness: send })
    await openMerchants()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    await registerBusiness()
    await within(step('① 사업자')).findByRole('group', { name: '결과 확인 필요' })

    await user.click(screen.getByRole('tab', { name: '가맹점 조회' }))
    expect(screen.queryByRole('region', { name: '① 사업자' })).toBeNull()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))

    const business = step('① 사업자')
    expect(within(business).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeDisabled()
    await click(business, '같은 내용 다시 보내기')
    expect(await within(step('① 사업자')).findByText(BIZ)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(storageIsEmpty()).toBe(true)
  })

  it('review m-3: "가맹점 조회에서 보기" opens the 가맹점 조회 tab on the created merchant detail (existing detail path, once)', async () => {
    const detail: MerchantDetail = {
      merchant: { merchantId: MER, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-10-01T00:00:00.000Z' },
      business: { businessId: BIZ, name: '데모 사업자', registrationNumberMasked: '778*******', lifecycle: 'ACTIVE' },
      sites: [], contracts: [], accounts: [], limits: [], propagation: null,
    }
    const { api } = signedInWithApi(['OPERATOR'], {
      registerMerchant: vi.fn(async () => succeeded(MER)),
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    await openMerchants()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    const merchant = step('② 가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await click(await screen.findByRole('region', { name: '등록 결과' }), '가맹점 조회에서 보기')

    expect(screen.getByRole('tab', { name: '가맹점 조회' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${MER}`) })).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantDetail).toHaveBeenCalledWith(MER)
    expect(api.merchantSearch).not.toHaveBeenCalled()

    // 탭을 다시 바꿔 돌아와도 상세를 다시 열지 않는다(상세 감사는 한 번).
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    await user.click(screen.getByRole('tab', { name: '가맹점 조회' }))
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  /** ① 사업자를 결과 불명(잠금) 상태로 만든다 — 사업자명 '데모 사업자'가 입력된 채로 잠긴다. */
  const lockBusinessStep = async () => {
    await openMerchants()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    await registerBusiness()
    const business = step('① 사업자')
    await within(business).findByRole('group', { name: '결과 확인 필요' })
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
  }
  /** 로그인 화면에서 비밀번호 → TOTP로 다시 들어온다(totp가 돌려줄 계정은 시험이 정한다). */
  const signInAgain = async () => {
    await user.type(await screen.findByLabelText('로그인 이름'), 'op-demo')
    await user.type(screen.getByLabelText('비밀번호'), 'demo-password')
    await user.click(screen.getByRole('button', { name: '다음' }))
    await user.type(await screen.findByLabelText('인증 코드(6자리)'), '123456')
    await user.click(screen.getByRole('button', { name: '로그인' }))
  }
  /** 다시 들어온 뒤 가맹점 등록: 잠금·재전송·입력이 남아 있지 않다. */
  const expectFreshRegistration = async () => {
    await openMerchants()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    const business = await screen.findByRole('region', { name: '① 사업자' })
    expect(within(business).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(screen.queryByRole('button', { name: '같은 내용 다시 보내기' })).toBeNull()
    expect(within(business).getByLabelText('사업자명')).toHaveValue('')
    expect(within(business).getByLabelText('사업자명')).toBeEnabled()
    expect(within(business).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeEnabled()
    expect(storageIsEmpty()).toBe(true)
  }
  const OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] }
  const OTHER_OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V2', displayName: '다른 운영자', roles: ['OPERATOR'] }
  const loginStubs = (account: Me) => ({ password: vi.fn(async () => ({ next: 'TOTP' as const })), totp: vi.fn(async () => account) })

  it('review MINOR-1: logout resets the registration memory — after signing in again there is no lock, no resend and no typed input', async () => {
    signedIn(['OPERATOR'], { registerBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await signInAgain()
    await expectFreshRegistration()
  })

  it('review MINOR-1: a session end (SESSION_EXPIRED on the resend) resets the registration memory', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    signedIn(['OPERATOR'], { registerBusiness: send, ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await click(step('① 사업자'), '같은 내용 다시 보내기')
    expect(await screen.findByRole('status')).toHaveTextContent(ERROR_MESSAGES.SESSION_EXPIRED)
    await signInAgain()
    await expectFreshRegistration()
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('review MINOR-1: signing in as another account starts with fresh registration memory', async () => {
    signedIn(['OPERATOR'], { registerBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), ...loginStubs(OTHER_OPERATOR) })
    await lockBusinessStep()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await signInAgain()
    expect(await screen.findByText('다른 운영자')).toBeInTheDocument()
    await expectFreshRegistration()
  })

  it('review MINOR-2: an unsubmitted registration number is not kept across a tab switch (other inputs may be)', async () => {
    signedIn(['OPERATOR'])
    await openMerchants()
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    await typeIn(step('① 사업자'), '사업자등록번호', BRN)
    await typeIn(step('① 사업자'), '사업자명', '데모 사업자')
    expect(within(step('① 사업자')).getByLabelText('사업자등록번호')).toHaveValue(BRN)

    await user.click(screen.getByRole('tab', { name: '가맹점 조회' }))
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    const business = step('① 사업자')
    expect(within(business).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(storageIsEmpty()).toBe(true)
  })

  it('VIEWER, APPROVER and SECURITY_ADMIN have no 가맹점 등록 tab', async () => {
    for (const role of ['VIEWER', 'APPROVER', 'SECURITY_ADMIN']) {
      const view = signedIn([role])
      await openMerchants()
      expect(screen.queryByRole('tab', { name: '가맹점 등록' })).toBeNull()
      view.unmount()
    }
  })
})
