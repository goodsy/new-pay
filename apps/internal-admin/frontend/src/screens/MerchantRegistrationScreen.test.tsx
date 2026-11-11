import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantRegistrationScreen } from './MerchantRegistrationScreen'
import { kstDate } from '../lib/registration'
import { App } from '../App'
import type { MerchantDetail, Me, RegistrationOutcome } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { ERROR_MESSAGES } from '../errors/messages'
import { createScreenMemory } from '../lib/screenMemory'

/**
 * 사업자 등록 마법사(ADMIN-UI-MERCHANT-001 — MERCHANT-OPS-002 §5 B1 등록 화면을 4단계로 재배치, 사용자 지시 2026-11-11).
 * 기대값 변경 근거: 화면 구조만 바뀌었다(한 쪽 5개 카드 → 단계 표시 + 단계별 카드). 보내는 body·submissionId·잠금/결과 불명·거절 문구·마스킹·저장소 없음 단언은 그대로 옮겼다.
 * 구조 변경에 따른 상호작용 변경: 단계 이동(단계 표시·"다음 단계 →"·"이전"), 성공한 카드는 읽기 전용(C-32 완료 상태 — "… 새로 입력"으로 다시 쓴다), 카드 이름에서 원 숫자 제거.
 */

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
const sid = (n: number) => n.toString(16).padStart(32, '0')

// 게이트 부하(Demo·Kotlin 시험 동시 실행)에서 5초 제한을 넘지 않도록 입력 지연을 없앤다(TEST-PERF-003과 같은 방식 — 한 자씩 입력·확인 내용은 그대로).
const user = userEvent.setup({ delay: null })
const step = (name: string) => screen.getByRole('region', { name })
// 흐름 시험이 게이트 부하에서 5초를 넘지 않도록 고정 문자열은 붙여넣는다(TEST-PERF-003과 같은 방식). 날짜 입력은 붙여넣기를 받지 않아 입력을 유지한다.
const typeIn = async (region: HTMLElement, label: string, value: string) => {
  const field = within(region).getByLabelText(label)
  if ((field as HTMLInputElement).type === 'date') return user.type(field, value)
  await user.click(field)
  await user.paste(value)
}
const click = async (region: HTMLElement, name: string) => user.click(within(region).getByRole('button', { name }))

/** 단계 표시(1~4). */
const STEPS = ['1. 사업자 정보', '2. 가맹점·사이트', '3. 계약 초안·서비스 계약', '4. 검토·완료'] as const
const stepper = () => screen.getByRole('list', { name: '등록 단계' })
const currentStep = () => within(stepper()).getByRole('button', { current: 'step' }).getAttribute('aria-label')
const goTo = async (name: (typeof STEPS)[number]) => user.click(within(stepper()).getByRole('button', { name }))
const next = async () => user.click(screen.getByRole('button', { name: '다음 단계 →' }))
const previous = async () => user.click(screen.getByRole('button', { name: '이전' }))

async function fillBusiness() {
  const region = step('사업자')
  await typeIn(region, '사업자등록번호', BRN)
  await typeIn(region, '사업자명', '데모 사업자')
  return region
}
async function registerBusiness() {
  const region = await fillBusiness()
  await click(region, '사업자 등록')
  return region
}

describe('MerchantRegistrationScreen wizard (ADMIN-UI-MERCHANT-001 — ① 사업자 정보 → ② 가맹점·사이트 → ③ 계약 초안·서비스 계약 → ④ 검토·완료)', () => {
  it('① "다음 단계 →" runs the existing business save once and fills ②; "이전" only navigates and going forward again does not resend', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => succeeded(BIZ)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    expect(screen.getByRole('heading', { name: '사업자 등록' })).toBeInTheDocument()
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(currentStep()).toBe('1. 사업자 정보')
    expect(screen.queryByRole('button', { name: '이전' })).toBeNull()

    await fillBusiness()
    await next()
    expect(await screen.findByRole('region', { name: '가맹점' })).toBeInTheDocument()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(api.registerBusiness).toHaveBeenCalledTimes(1)
    expect(api.registerBusiness).toHaveBeenCalledWith({ submissionId: sid(1), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(within(step('가맹점')).getByLabelText('사업자 ID')).toHaveValue(BIZ)

    // "이전"은 이동만 — 완료한 카드는 읽기 전용으로 저장 결과를 보인다.
    await previous()
    expect(currentStep()).toBe('1. 사업자 정보')
    expect(within(step('사업자')).getByText(BIZ)).toBeInTheDocument()
    expect(within(step('사업자')).getByText('778*******')).toBeInTheDocument()
    expect(within(step('사업자')).getByLabelText('사업자명')).toBeDisabled()
    expect(within(step('사업자')).queryByRole('button', { name: '사업자 등록' })).toBeNull()
    await next()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(api.registerBusiness).toHaveBeenCalledTimes(1)
    expect(storageIsEmpty()).toBe(true)
  })

  it('② "다음 단계 →" sends the merchant then the Site once each (the Site gets the new merchant id); "이전" then "다음 단계 →" resends neither', async () => {
    const api = fakeApi({
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    expect(await screen.findByRole('region', { name: '계약 초안' })).toBeInTheDocument()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점' })
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
    expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })

    await previous()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByText(MER)).toBeInTheDocument()
    expect(within(step('Site')).getByText(SITE)).toBeInTheDocument()
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toBeDisabled()
    expect(within(step('가맹점')).queryByRole('button', { name: '가맹점 등록' })).toBeNull()
    await next()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
  })

  it('③→④: contract and service agreement are sent once each with the same bodies; ④ summarises the saved values, points to the real next steps and opens the lookup (no MID issuance here)', async () => {
    const api = fakeApi({
      registerBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
      registerContract: vi.fn(async () => succeeded(CTR)),
      addServiceAgreement: vi.fn(async () => succeeded(CSA)),
    })
    const onOpenLookup = vi.fn()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await fillBusiness()
    await next()
    await typeIn(await screen.findByRole('region', { name: '가맹점' }), '가맹점명', '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()

    const contract = await screen.findByRole('region', { name: '계약 초안' })
    expect(within(contract).getByLabelText('사업자 ID')).toHaveValue(BIZ)
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    expect(within(contract).getByLabelText('시작일')).toHaveAttribute('min', '2026-11-08')
    expect(within(contract).getByText('이 날 0시 종료(미포함)')).toBeInTheDocument()
    expect(within(contract).getByLabelText('가맹점 전체')).toBeChecked()
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await typeIn(contract, '종료일', '2027-01-01')
    const agreement = step('서비스 계약')
    expect(within(agreement).getByLabelText('서비스 종류')).toHaveValue('CARD_PG_PAYMENT')
    expect(within(agreement).getByRole('option', { name: '카드 PG 결제' })).toBeInTheDocument()
    await next()

    const done = await screen.findByRole('region', { name: '등록 결과' })
    expect(currentStep()).toBe('4. 검토·완료')
    expect(api.registerContract).toHaveBeenCalledTimes(1)
    expect(api.registerContract).toHaveBeenCalledWith({
      submissionId: sid(4), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [SITE], effectiveFromDate: '2026-11-09', effectiveToDate: '2027-01-01',
    })
    expect(api.addServiceAgreement).toHaveBeenCalledTimes(1)
    expect(api.addServiceAgreement).toHaveBeenCalledWith({ submissionId: sid(5), contractId: CTR, serviceKind: 'CARD_PG_PAYMENT' })

    for (const created of [BIZ, SITE, CTR, CSA]) expect(within(done).getByText(created)).toBeInTheDocument()
    // 리뷰 NIT-4: 가맹점 ID는 가맹점 행과 Site 행(소유 가맹점)에 한 번씩.
    expect(within(done).getAllByText(MER)).toHaveLength(2)
    expect(within(done).getByText('778*******')).toBeInTheDocument()
    expect(within(done).getByText('데모 가맹점')).toBeInTheDocument()
    expect(within(done).getByText('demo-shop2.paycore.test')).toBeInTheDocument()
    // C-32 표기 통일(상세 표와 같은 "승인 전").
    expect(within(done).getByText(/Revision 1, 승인 전/)).toBeInTheDocument()
    // B2-3 Demo 지적 2: 다음 단계 안내 = 조회 상세에서 계약 승인 → 첫 MID 발급, 개통 전까지 결제 불가.
    expect(done).toHaveTextContent('다음: 가맹점 조회 상세에서 계약 승인 → 첫 MID 발급을 요청하세요. 결제수단·Route 개통 전까지 결제는 아직 할 수 없습니다.')
    expect(within(within(done).getByRole('list', { name: '다음 단계' })).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '가맹점 조회 상세', '계약 승인 요청 (2인)', '첫 MID 발급 (2인)', '개통',
    ])
    // MID 발급·개통은 마법사에서 하지 않는다(계약 승인 2인 → MID 발급 2인 — 확정 규칙).
    expect(screen.queryByRole('button', { name: /MID/ })).toBeNull()
    expect(screen.queryByRole('button', { name: '다음 단계 →' })).toBeNull()
    await click(done, '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledTimes(1)
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
    expect(storageIsEmpty()).toBe(true)
  })

  it('② with the merchant saved but the Site rejected: the next "다음 단계 →" sends only the Site (the merchant is not resent)', async () => {
    const api = fakeApi({
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn().mockResolvedValueOnce(rejected('SITE_CHANNEL_DUPLICATE')).mockResolvedValueOnce(succeeded(SITE)),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    await typeIn(step('가맹점'), '사업자 ID', BIZ)
    await typeIn(step('가맹점'), '가맹점명', '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    expect(await within(step('Site')).findByRole('alert')).toHaveTextContent('이미 다른 Site가 쓰는 채널 식별값입니다')
    expect(currentStep()).toBe('2. 가맹점·사이트')
    await user.clear(within(step('Site')).getByLabelText('채널 식별값'))
    await typeIn(step('Site'), '채널 식별값', 'demo-shop3.paycore.test')
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.registerMerchantSite).mock.calls.map(([input]) => input)).toEqual([
      { submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' },
      { submissionId: sid(3), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop3.paycore.test' },
    ])
  })

  it('② while the merchant result is unknown: "다음 단계 →" is disabled and the Site is not sent; the resend uses the same submissionId', async () => {
    const send = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN')).mockResolvedValueOnce(succeeded(MER, true))
    const api = fakeApi({ registerMerchant: send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    await typeIn(step('가맹점'), '사업자 ID', BIZ)
    await typeIn(step('가맹점'), '가맹점명', '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeDisabled()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    expect(currentStep()).toBe('2. 가맹점·사이트')

    await click(step('가맹점'), '같은 내용 다시 보내기')
    expect(await within(step('가맹점')).findByText(MER)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeEnabled()
    // 같은 내용 재전송으로 확정된 가맹점은 Site 카드에 채워진다(잠기지 않은 단계).
    expect(within(step('Site')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(send).toHaveBeenCalledTimes(2)
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
  })

  it('① "기존 사업자로 이어서" moves to ② without registering a business; the existing business id is typed there (format check only)', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await user.click(screen.getByRole('button', { name: '기존 사업자로 이어서' }))
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(api.registerBusiness).not.toHaveBeenCalled()
    const merchant = step('가맹점')
    expect(within(merchant).getByLabelText('사업자 ID')).toHaveValue('')
    await typeIn(merchant, '사업자 ID', 'biz_1')
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await next()
    expect(within(merchant).getByText('사업자 ID 형식(biz_…)이 아닙니다.')).toBeInTheDocument()
    expect(api.registerMerchant).not.toHaveBeenCalled()
    await user.clear(within(merchant).getByLabelText('사업자 ID'))
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점' })
    expect(within(step('계약 초안')).getByLabelText('사업자 ID')).toHaveValue(BIZ)
  })

  it('the wizard step and saved results survive leaving the screen (memory) and "새 사업자 등록 시작" starts over only when nothing is unresolved', async () => {
    const api = fakeApi({
      registerBusiness: vi.fn(async () => succeeded(BIZ)),
    })
    // 같은 메모리를 주입해 다시 그린다(App 화면 전환과 같다).
    const memory = createScreenMemory()
    const first = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    await fillBusiness()
    await next()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    first.unmount()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByLabelText('사업자 ID')).toHaveValue(BIZ)

    await goTo('4. 검토·완료')
    const done = step('등록 결과')
    expect(within(done).getByText(BIZ)).toBeInTheDocument()
    await click(done, '새 사업자 등록 시작')
    expect(currentStep()).toBe('1. 사업자 정보')
    expect(within(step('사업자')).getByLabelText('사업자명')).toHaveValue('')
    expect(within(step('사업자')).getByLabelText('사업자명')).toBeEnabled()
    expect(within(step('사업자')).queryByText(BIZ)).toBeNull()
    expect(api.registerBusiness).toHaveBeenCalledTimes(1)
  })

  it('"새 사업자 등록 시작" is disabled while any step is waiting for a result check', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await registerBusiness()
    await within(step('사업자')).findByRole('group', { name: '결과 확인 필요' })
    await goTo('4. 검토·완료')
    expect(within(step('등록 결과')).getByRole('button', { name: '새 사업자 등록 시작' })).toBeDisabled()
    expect(step('등록 결과')).toHaveTextContent('결과 확인이 필요한 단계가 있습니다')
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
    await goTo('4. 검토·완료')
    expect(container.innerHTML).not.toContain(BRN)
  })

  it('a duplicate registration number shows the fixed sentence only (no business id or link) and stays on ①; a replayed decision says so', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => rejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE')) })
    const first = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillBusiness()
    await next()
    const business = step('사업자')
    const alert = await within(business).findByRole('alert')
    expect(alert).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    expect(alert.textContent).not.toMatch(/biz_/)
    expect(within(business).queryAllByRole('link')).toHaveLength(0)
    expect(currentStep()).toBe('1. 사업자 정보')
    await goTo('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByLabelText('사업자 ID')).toHaveValue('')
    first.unmount()

    const replay = fakeApi({ registerMerchantSite: vi.fn(async () => rejected('SITE_CHANNEL_DUPLICATE', true)) })
    render(<MerchantRegistrationScreen api={replay} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('이미 다른 Site가 쓰는 채널 식별값입니다 (이전에 처리된 결과입니다)')
  })

  it('§5.3: an unknown outcome locks the form and offers only "같은 내용 다시 보내기" with the same submissionId; a decided answer releases the lock into the read-only completion ("사업자 새로 입력" → new submissionId)', async () => {
    const BIZ2 = id('biz', 8)
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_RETRY_SAME_KEY'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
      .mockResolvedValueOnce(succeeded(BIZ2))
    const api = fakeApi({ registerBusiness: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillBusiness()
    await next()
    const business = step('사업자')

    const group = await within(business).findByRole('group', { name: '결과 확인 필요' })
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    expect(within(business).getByLabelText('사업자등록번호')).toBeDisabled()
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeDisabled()
    expect(currentStep()).toBe('1. 사업자 정보')

    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    await within(business).findByRole('group', { name: '결과 확인 필요' })
    await user.click(within(business).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await within(business).findByText(BIZ)).toBeInTheDocument()
    expect(within(business).getByText(/이전에 처리된 결과입니다/)).toBeInTheDocument()

    expect(send).toHaveBeenCalledTimes(3)
    const bodies = send.mock.calls.map(([input]) => input)
    expect(new Set(bodies.map((b) => JSON.stringify(b))).size).toBe(1)
    expect(bodies[0]).toEqual({ submissionId: sid(1), registrationNumber: BRN, displayName: '데모 사업자' })
    // 확정된 결과는 잠금을 풀고 읽기 전용 완료 상태(C-32)로 — 결과 확인 그룹은 사라지고 "사업자 새로 입력"만 남는다.
    expect(within(business).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(within(business).getByRole('button', { name: '사업자 새로 입력' })).toBeInTheDocument()
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    // "다음 단계 →"는 다시 보내지 않고 이동만 한다.
    await next()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(send).toHaveBeenCalledTimes(3)
    // 돌아가 "사업자 새로 입력" 뒤의 다음 제출은 새 submissionId.
    await previous()
    await click(step('사업자'), '사업자 새로 입력')
    expect(within(step('사업자')).getByLabelText('사업자명')).toBeEnabled()
    await fillBusiness()
    await click(step('사업자'), '사업자 등록')
    expect(await within(step('사업자')).findByText(BIZ2)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(4)
    expect(send.mock.calls[3]![0]).toEqual({ submissionId: sid(2), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(storageIsEmpty()).toBe(true)
  })

  /** 응답을 시험이 정할 때까지 붙잡는 API 대역. */
  function deferred() {
    let resolve: (outcome: RegistrationOutcome) => void = () => undefined
    const send = vi.fn(() => new Promise<RegistrationOutcome>((r) => { resolve = r }))
    return { send, resolve: (outcome: RegistrationOutcome) => act(async () => resolve(outcome)) }
  }
  async function fillMerchantAndSite(channel = 'demo-shop2.paycore.test') {
    await goTo('2. 가맹점·사이트')
    await typeIn(step('가맹점'), '사업자 ID', BIZ)
    await typeIn(step('가맹점'), '가맹점명', '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', channel)
  }

  it('review MINOR-1: when the screen unmounts while the first card is in flight, its result is still recorded but the next card is never sent', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    const memory = createScreenMemory()
    const view = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    await fillMerchantAndSite()
    await next()
    expect(merchant.send).toHaveBeenCalledTimes(1)
    view.unmount()
    await merchant.resolve(succeeded(MER))
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    // 이미 보낸 카드의 결과는 그대로 기록된다(같은 메모리로 다시 그리면 성공이 보인다).
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByText(MER)).toBeInTheDocument()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('review MINOR-1: when the memory is swapped (session boundary) while the first card is in flight, the next card is never sent', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    const view = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={createScreenMemory()} />)
    await fillMerchantAndSite()
    await next()
    view.rerender(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={createScreenMemory()} />)
    await merchant.resolve(succeeded(MER))
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    expect(currentStep()).toBe('1. 사업자 정보')
  })

  it('review: a double click or re-click on "다음 단계 →" while it is in flight sends once', async () => {
    const business = deferred()
    const api = fakeApi({ registerBusiness: business.send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillBusiness()
    const button = screen.getByRole('button', { name: '다음 단계 →' })
    await user.dblClick(button)
    await user.click(button)
    expect(business.send).toHaveBeenCalledTimes(1)
    expect(button).toBeDisabled()
    await business.resolve(succeeded(BIZ))
    expect(await screen.findByRole('region', { name: '가맹점' })).toBeInTheDocument()
    expect(business.send).toHaveBeenCalledTimes(1)
  })

  it('review NIT-3: card submit buttons are disabled while "다음 단계 →" is sending', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillMerchantAndSite()
    await next()
    expect(within(step('Site')).getByRole('button', { name: 'Site 등록' })).toBeDisabled()
    await merchant.resolve(rejected('BUSINESS_NOT_FOUND'))
    expect(within(step('Site')).getByRole('button', { name: 'Site 등록' })).toBeEnabled()
    expect(within(step('가맹점')).getByRole('button', { name: '가맹점 등록' })).toBeEnabled()
  })

  it('review: in ② a merchant success followed by a Site format error stays on ② and the next "다음 단계 →" does not resend the merchant', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillMerchantAndSite('Shop.Example.test')
    await next()
    expect(await within(step('가맹점')).findByText(MER)).toBeInTheDocument()
    expect(within(step('Site')).getByText('형식이 맞지 않습니다(소문자 정규형만 — 자동으로 바꾸지 않습니다).')).toBeInTheDocument()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    await user.clear(within(step('Site')).getByLabelText('채널 식별값'))
    await typeIn(step('Site'), '채널 식별값', 'shop.example.test')
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'shop.example.test' })
  })

  it('review: after "다른 Site 추가 입력", "다음 단계 →" sends only the Site with a new submissionId; ④ shows each Site with its merchant id', async () => {
    const SITE2 = id('site', 8)
    const api = fakeApi({
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn().mockResolvedValueOnce(succeeded(SITE)).mockResolvedValueOnce(succeeded(SITE2)),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await fillMerchantAndSite()
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    await previous()
    await click(step('Site'), '다른 Site 추가 입력')
    expect(within(step('Site')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop3.paycore.test')
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.registerMerchantSite).mock.calls.map(([input]) => input)).toEqual([
      { submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' },
      { submissionId: sid(3), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop3.paycore.test' },
    ])
    await goTo('4. 검토·완료')
    const rows = within(within(step('등록 결과')).getByRole('list', { name: '저장된 Site' })).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent(SITE)
    expect(rows[0]).toHaveTextContent(`가맹점 ${MER}`)
    expect(rows[1]).toHaveTextContent(SITE2)
    expect(rows[1]).toHaveTextContent(`가맹점 ${MER}`)
  })

  it('a network failure and AUDIT_UNAVAILABLE are treated as unknown too; "취소하고 새로 입력" warns first and the next submit uses a new submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE'))
      .mockResolvedValueOnce(succeeded(MER))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
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
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual([sid(1), sid(1), sid(2)])
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
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
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
      expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeDisabled()
    }
    await click(merchant, '같은 내용 다시 보내기')
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(5)
    expect(new Set(send.mock.calls.map(([input]) => JSON.stringify(input))).size).toBe(1)
    expect(send.mock.calls[0]![0].submissionId).toBe(sid(1))
  })

  it('§5.3 (review M-1): a 409 on a resend releases the lock and the next submit uses a new submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockResolvedValueOnce(succeeded(MER))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await click(await within(merchant).findByRole('group', { name: '결과 확인 필요' }), '같은 내용 다시 보내기')
    expect(await within(merchant).findByRole('alert')).toHaveTextContent('화면 결함 신호')
    expect(within(merchant).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(within(merchant).getByLabelText('가맹점명')).toBeEnabled()
    await click(merchant, '가맹점 등록')
    expect(await within(merchant).findByText(MER)).toBeInTheDocument()
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual([sid(1), sid(1), sid(2)])
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
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    await within(site).findByText(SITE)
    await goTo('4. 검토·완료')
    await click(step('등록 결과'), '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
  })

  it('409 IDEMPOTENCY_KEY_REUSED is shown as a defect and allows a new submission (new submissionId); 403 and 429 show their fixed messages', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
    const api = fakeApi({ registerMerchantSite: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('화면 결함 신호')
    expect(within(site).getByLabelText('채널 식별값')).toBeEnabled()
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('권한이 없습니다')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('너무 잦습니다')
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual([sid(1), sid(2), sid(3)])
  })

  it('formats are checked on screen without sending (no automatic normalization): hyphenated number, upper-case host, malformed ids', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    const business = step('사업자')
    await typeIn(business, '사업자등록번호', '778-12-345')
    await typeIn(business, '사업자명', '데모')
    await next()
    expect(within(business).getByText('사업자등록번호는 숫자 10자리입니다(하이픈 없이).')).toBeInTheDocument()
    expect(currentStep()).toBe('1. 사업자 정보')

    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', 'mer_1')
    await typeIn(site, '채널 식별값', 'Shop.Example.test')
    await click(site, 'Site 등록')
    expect(within(site).getByText('가맹점 ID 형식(mer_…)이 아닙니다.')).toBeInTheDocument()
    expect(within(site).getByLabelText('채널 식별값')).toHaveValue('Shop.Example.test')
    expect(api.registerBusiness).not.toHaveBeenCalled()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: a MERCHANT scope sends an empty site list and no end date; a start before the picker minimum is still sent (MASTER decides) and CONTRACT_START_RETROACTIVE shows its fixed sentence', async () => {
    const send = vi.fn(async () => rejected('CONTRACT_START_RETROACTIVE'))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-07')
    await next()
    expect(send).toHaveBeenCalledWith({ submissionId: sid(1), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-07' })
    expect(await within(contract).findByRole('alert')).toHaveTextContent('시작일이 오늘보다 앞입니다')
    expect(within(contract).getByLabelText('시작일')).toBeEnabled()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.addServiceAgreement).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: screen format checks without sending — SITES scope with no site, malformed typed site id, end not after start, missing start', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
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
    await next()
    expect(within(contract).getByText('Site ID 형식(site_…)이 아닙니다.')).toBeInTheDocument()
    expect(api.registerContract).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안 (§5.3): an unknown outcome locks the contract form; the resend reuses the same submissionId and the same dates; a decided answer unlocks it and fills 서비스 계약', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(CTR, true))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-09')
    await next()
    const group = await within(contract).findByRole('group', { name: '결과 확인 필요' })
    expect(within(contract).getByLabelText('시작일')).toBeDisabled()
    expect(within(contract).getByLabelText('가맹점 ID')).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeDisabled()
    expect(api.addServiceAgreement).not.toHaveBeenCalled()
    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await within(contract).findByText(CTR)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(within(step('서비스 계약')).getByLabelText('계약 ID')).toHaveValue(CTR)
  })

  it('B1-2 서비스 계약: a duplicate service kind shows its fixed sentence without any agreement id; "가맹점 조회에서 보기" after the contract on an existing merchant opens that merchant', async () => {
    const onOpenLookup = vi.fn()
    const api = fakeApi({
      registerContract: vi.fn(async () => succeeded(CTR)),
      addServiceAgreement: vi.fn(async () => rejected('SERVICE_KIND_DUPLICATE')),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-08')
    await next()
    await within(contract).findByText(CTR)
    const agreement = step('서비스 계약')
    const alert = await within(agreement).findByRole('alert')
    expect(alert).toHaveTextContent('이미 같은 서비스 종류의 계약이 있습니다')
    expect(alert.textContent).not.toMatch(/csa_/)
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.registerContract).toHaveBeenCalledTimes(1)
    await goTo('4. 검토·완료')
    await click(step('등록 결과'), '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
  })

  it('review m-1 (auto-fill vs lock): 계약 초안 locked by MASTER_OUTCOME_UNKNOWN keeps its 가맹점 ID and offered sites when a Site succeeds for another merchant; the resend body equals the first', async () => {
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
    await goTo('2. 가맹점·사이트')
    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE)

    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByRole('group', { name: '결과 확인 필요' })

    // Site 다른 가맹점으로 성공 — 잠긴 계약 초안에는 채우지 않는다.
    await goTo('2. 가맹점·사이트')
    await click(step('Site'), '다른 Site 추가 입력')
    await user.clear(within(step('Site')).getByLabelText('가맹점 ID'))
    await typeIn(step('Site'), '가맹점 ID', MER2)
    await user.clear(within(step('Site')).getByLabelText('채널 식별값'))
    await typeIn(step('Site'), '채널 식별값', 'demo-shop3.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE2)

    await goTo('3. 계약 초안·서비스 계약')
    expect(within(step('계약 초안')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    expect(within(step('계약 초안')).getAllByRole('checkbox').map((c) => c.closest('label')?.textContent)).toEqual([SITE])
    expect(within(step('계약 초안')).getByRole('checkbox', { name: SITE })).toBeChecked()

    await click(step('계약 초안'), '같은 내용 다시 보내기')
    expect(await within(step('계약 초안')).findByText(CTR)).toBeInTheDocument()
    expect(contractSend).toHaveBeenCalledTimes(2)
    expect(contractSend.mock.calls[1]![0]).toEqual(contractSend.mock.calls[0]![0])
    expect(contractSend.mock.calls[0]![0]).toEqual({
      submissionId: sid(2), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [SITE], effectiveFromDate: '2026-11-09',
    })
  })

  it('review m-1 (auto-fill vs lock): 서비스 계약 locked keeps its 계약 ID when 계약 초안 succeeds; the resend body equals the first', async () => {
    const CTR2 = id('ctr', 8)
    const agreementSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(CSA, true))
    const api = fakeApi({ registerContract: vi.fn(async () => succeeded(CTR2)), addServiceAgreement: agreementSend })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    const agreement = step('서비스 계약')
    await typeIn(agreement, '계약 ID', CTR)
    await click(agreement, '서비스 계약 추가')
    await within(agreement).findByRole('group', { name: '결과 확인 필요' })

    const contract = step('계약 초안')
    await typeIn(contract, '사업자 ID', BIZ)
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByText(CTR2)

    expect(within(agreement).getByLabelText('계약 ID')).toHaveValue(CTR)
    await click(agreement, '같은 내용 다시 보내기')
    expect(await within(agreement).findByText(CSA)).toBeInTheDocument()
    expect(agreementSend.mock.calls[1]![0]).toEqual(agreementSend.mock.calls[0]![0])
    expect(agreementSend.mock.calls[0]![0]).toEqual({ submissionId: sid(1), contractId: CTR, serviceKind: 'CARD_PG_PAYMENT' })
  })

  it('review m-1 (auto-fill vs lock, B1-1): 가맹점 locked keeps its 사업자 ID when 사업자 succeeds; Site locked keeps its 가맹점 ID when 가맹점 succeeds, while unlocked 계약 초안 is still filled', async () => {
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

    await goTo('2. 가맹점·사이트')
    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByRole('group', { name: '결과 확인 필요' })

    await typeIn(step('가맹점'), '사업자 ID', BIZ)
    await typeIn(step('가맹점'), '가맹점명', '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })

    await goTo('1. 사업자 정보')
    await registerBusiness()
    await within(step('사업자')).findByText(BIZ2)
    await goTo('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByLabelText('사업자 ID')).toHaveValue(BIZ)

    await click(step('가맹점'), '같은 내용 다시 보내기')
    await within(step('가맹점')).findByText(MER2)
    expect(merchantSend.mock.calls[1]![0]).toEqual(merchantSend.mock.calls[0]![0])
    expect(within(step('Site')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await goTo('3. 계약 초안·서비스 계약')
    expect(within(step('계약 초안')).getByLabelText('사업자 ID')).toHaveValue(BIZ)
    expect(within(step('계약 초안')).getByLabelText('가맹점 ID')).toHaveValue(MER2)

    await goTo('2. 가맹점·사이트')
    await click(step('Site'), '같은 내용 다시 보내기')
    expect(await within(step('Site')).findByText(SITE)).toBeInTheDocument()
    expect(siteSend.mock.calls[1]![0]).toEqual(siteSend.mock.calls[0]![0])
    expect(siteSend.mock.calls[0]![0]).toEqual({ submissionId: sid(1), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
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
    await fillBusiness()
    await next()
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
  })
})

describe('App wiring (MERCHANT-OPS-002 §5.1 — OPERATOR only, display only; ADMIN-UI-MERCHANT-001 header action)', () => {
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
    user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
  /** 쪽 머리 '＋ 사업자 등록'(OPERATOR만). */
  const openRegistration = async () => user.click(screen.getByRole('button', { name: '사업자 등록' }))
  /** 마법사에서 가맹점 조회 탭으로 돌아간다(쪽 머리 버튼). */
  const backToLookup = async () => user.click(screen.getByRole('button', { name: '가맹점 조회로' }))

  it('an OPERATOR sees only the 가맹점 조회 tab plus the header "사업자 등록" action, which opens the registration wizard', async () => {
    signedIn(['OPERATOR'])
    await openMerchants()
    expect(screen.getByRole('heading', { level: 1, name: '사업자·가맹점' })).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점 조회'])
    await openRegistration()
    expect(await screen.findByRole('region', { name: '사업자' })).toBeInTheDocument()
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(screen.queryByRole('tab')).toBeNull()
    // 마법사 화면에서 쪽 머리 버튼은 '가맹점 조회로' — '사업자 등록' 버튼은 사업자 카드의 저장 버튼 하나뿐.
    expect(screen.getAllByRole('button', { name: '사업자 등록' })).toHaveLength(1)
    await backToLookup()
    expect(screen.getByRole('tab', { name: '가맹점 조회' })).toHaveAttribute('aria-selected', 'true')
  })

  it('review m-1: an unknown outcome survives switching to 가맹점 조회 and back — still locked on the same step, the resend reuses the same submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
    signedIn(['OPERATOR'], { registerBusiness: send })
    await openMerchants()
    await openRegistration()
    await registerBusiness()
    await within(step('사업자')).findByRole('group', { name: '결과 확인 필요' })

    await backToLookup()
    expect(screen.queryByRole('region', { name: '사업자' })).toBeNull()
    await openRegistration()

    const business = step('사업자')
    expect(within(business).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeDisabled()
    await click(business, '같은 내용 다시 보내기')
    expect(await within(step('사업자')).findByText(BIZ)).toBeInTheDocument()
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
    await openRegistration()
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
    await typeIn(merchant, '사업자 ID', BIZ)
    await typeIn(merchant, '가맹점명', '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await within(merchant).findByText(MER)
    await goTo('4. 검토·완료')
    await click(step('등록 결과'), '가맹점 조회에서 보기')

    expect(screen.getByRole('tab', { name: '가맹점 조회' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${MER}`) })).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantDetail).toHaveBeenCalledWith(MER)
    expect(api.merchantSearch).not.toHaveBeenCalled()

    // 화면을 다시 바꿔 돌아와도 상세를 다시 열지 않는다(상세 감사는 한 번).
    await openRegistration()
    await backToLookup()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  /** ① 사업자를 결과 불명(잠금) 상태로 만든다 — 사업자명 '데모 사업자'가 입력된 채로 잠긴다. */
  const lockBusinessStep = async () => {
    await openMerchants()
    await openRegistration()
    await registerBusiness()
    const business = step('사업자')
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
  /** 다시 들어온 뒤 사업자 등록: 잠금·재전송·입력이 남아 있지 않다(첫 단계부터). */
  const expectFreshRegistration = async () => {
    await openMerchants()
    await openRegistration()
    const business = await screen.findByRole('region', { name: '사업자' })
    expect(currentStep()).toBe('1. 사업자 정보')
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
    await click(step('사업자'), '같은 내용 다시 보내기')
    expect(await screen.findByRole('status')).toHaveTextContent(ERROR_MESSAGES.SESSION_EXPIRED)
    // B2-1 리뷰 n-a(B2-2): 결과 불명으로 잠긴 단계가 있던 세션이 끝나면 로그인 화면이 한 번 안내한다(추가 단언 — 기존 기대 불변).
    expect(screen.getByRole('note', { name: '결과 확인 필요' })).toHaveTextContent('결과 확인이 필요한 요청이 있습니다 — 가맹점 조회로 확인하세요')
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

  it('review MINOR-2: an unsubmitted registration number is not kept across a screen switch (other inputs may be)', async () => {
    signedIn(['OPERATOR'])
    await openMerchants()
    await openRegistration()
    await typeIn(step('사업자'), '사업자등록번호', BRN)
    await typeIn(step('사업자'), '사업자명', '데모 사업자')
    expect(within(step('사업자')).getByLabelText('사업자등록번호')).toHaveValue(BRN)

    await backToLookup()
    await openRegistration()
    const business = step('사업자')
    expect(within(business).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(storageIsEmpty()).toBe(true)
  })

  it('VIEWER, APPROVER and SECURITY_ADMIN have no 사업자 등록 action (and no registration tab)', async () => {
    for (const role of ['VIEWER', 'APPROVER', 'SECURITY_ADMIN']) {
      const view = signedIn([role])
      await openMerchants()
      expect(screen.queryByRole('button', { name: '사업자 등록' })).toBeNull()
      expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점 조회'])
      view.unmount()
    }
  })
})
