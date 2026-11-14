import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantRegistrationScreen, offerBusiness } from './MerchantRegistrationScreen'
import { kstDate, type BusinessRef } from '../lib/registration'
import { App } from '../App'
import type { MerchantDetail, MerchantListItem, Me, RegistrationOutcome } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { ERROR_MESSAGES } from '../errors/messages'
import { createScreenMemory } from '../lib/screenMemory'

/**
 * 가맹점 등록 마법사(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ④ 화면 분리: ① 사업자 선택 → ② 가맹점·사이트 → ③ 계약 초안·서비스 계약 → ④ 검토·완료).
 * 기대값 변경 근거(ADMIN-UI-MERCHANT-001 시험에서):
 * - ① 사업자 정보(사업자 등록 카드)는 사업자 등록 화면으로 옮겼다(BusinessRegistrationScreen.test — 같은 단언). 이 화면 ①은 저장 없는 "사업자 선택"(기존 사업자등록번호 가맹점 검색 재사용).
 * - 사업자 ID 입력란(biz_…)이 없어졌다(D-ADMIN-UI-004 ③): 가맹점·계약 초안의 사업자는 고른 사업자(상호·마스킹 번호 표시)이고, 보내는 body의 businessId·partyBusinessId는 그대로 같은 값.
 *   "biz_1 형식 오류" 시험은 "사업자를 고르지 않으면 보내지 않음" 시험으로 바뀌었다. 사업자 ID가 화면에 보인다는 단언은 보이지 않는다는 단언으로 뒤집었다.
 * - "새 사업자 등록 시작" → "새 가맹점 등록 시작", "기존 사업자로 이어서" 버튼 없음(사업자 선택이 그 역할).
 * - 보내는 body·submissionId·잠금/결과 불명·거절 문구·자동 채움 vs 잠금·저장소 없음 단언은 그대로.
 */

/** Demo 식별자(MASTER 형식 — 접두사 + Crockford base32 26자). 업무 의미 없는 고정값. */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const BIZ = id('biz', 7)
const MER = id('mer', 7)
/** typeMerchant 기본 가맹점 코드(MC-2 — 결과·검토에는 mer_ 대신 이 코드가 보인다). */
const CODE = 'shopabcd12'
const SITE = id('site', 7)
const CTR = id('ctr', 7)
const CSA = id('csa', 7)
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). 화면에 평문으로 남으면 안 된다. */
const BRN = '7781234560'
const BUSINESS: BusinessRef = { businessId: BIZ, name: '데모 사업자', masked: '778*******' }
const succeeded = (reference: string, replayed = false): RegistrationOutcome => ({ outcome: 'SUCCEEDED', reference, replayed })
const rejected = (rejection: string, replayed = false): RegistrationOutcome => ({ outcome: 'REJECTED', rejection, replayed })
const listItem = (merchantId: string, businessId = BIZ, businessName = '데모 사업자'): MerchantListItem => ({
  merchantId, merchantCode: codeOf(merchantId), merchantName: '기존 가맹점', businessId, businessName, registrationNumberMasked: '778*******', lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 0,
  createdAt: '2026-10-01T00:00:00.000Z',
})

/** 제출마다 다른 고정 submissionId(32 hex). */
function ids() {
  let n = 0
  return () => (++n).toString(16).padStart(32, '0')
}
const sid = (n: number) => n.toString(16).padStart(32, '0')

// 게이트 부하에서 5초 제한을 넘지 않도록 입력 지연을 없앤다(TEST-PERF-003과 같은 방식).
const user = userEvent.setup({ delay: null })
const step = (name: string) => screen.getByRole('region', { name })
// 고정 문자열은 붙여넣는다(TEST-PERF-003). 날짜 입력은 붙여넣기를 받지 않아 입력을 유지한다.
const typeIn = async (region: HTMLElement, label: string, value: string) => {
  const field = within(region).getByLabelText(label)
  if ((field as HTMLInputElement).type === 'date') return user.type(field, value)
  await user.click(field)
  await user.paste(value)
}
/** MERCHANT-CODE-001 MC-1: 가맹점 카드는 가맹점명 + 가맹점 코드(필수 — 직접 입력)를 받는다. */
const typeMerchant = async (region: HTMLElement, name: string, code = 'shopabcd12') => {
  await typeIn(region, '가맹점명', name)
  await typeIn(region, '가맹점 코드', code)
}
const click = async (region: HTMLElement, name: string) => user.click(within(region).getByRole('button', { name }))

/** 단계 표시(1~4). */
const STEPS = ['1. 사업자 선택', '2. 가맹점·사이트', '3. 계약 초안·서비스 계약', '4. 검토·완료'] as const
const stepper = () => screen.getByRole('list', { name: '등록 단계' })
const currentStep = () => within(stepper()).getByRole('button', { current: 'step' }).getAttribute('aria-label')
const goTo = async (name: (typeof STEPS)[number]) => user.click(within(stepper()).getByRole('button', { name }))
const next = async () => user.click(screen.getByRole('button', { name: '다음 단계 →' }))
const previous = async () => user.click(screen.getByRole('button', { name: '이전' }))

/** 사업자를 이미 고른 메모리(사업자 등록 화면 "이 사업자로 가맹점 등록"과 같은 경로) — ②부터 시작한다. */
function withBusiness(business: BusinessRef = BUSINESS) {
  const memory = createScreenMemory()
  offerBusiness(memory, business)
  return memory
}
/** ① 사업자등록번호로 찾아 고른다. */
async function pickBusiness() {
  const picker = step('사업자 선택')
  await typeIn(picker, '사업자등록번호', BRN)
  await click(picker, '찾기')
  const found = await within(picker).findByRole('list', { name: '찾은 사업자' })
  await user.click(within(found).getByRole('button', { name: '선택' }))
}
const merchantBusinessText = () => within(step('가맹점')).getByRole('group', { name: '사업자' }).textContent
const partyText = () => within(step('계약 초안')).getByRole('group', { name: '계약 당사자' }).textContent

describe('MerchantRegistrationScreen wizard (ADMIN-UI-MERCHANT-002 — ① 사업자 선택 → ② 가맹점·사이트 → ③ 계약 초안·서비스 계약 → ④ 검토·완료)', () => {
  it('① finds the business with the existing registration-number search (number cleared, never rendered), shows name + masked number only, and "선택" moves to ② with the business filled', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [listItem(MER), listItem(id('mer', 8))], nextCursor: null })) })
    const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    expect(screen.getByRole('heading', { name: '가맹점 등록' })).toBeInTheDocument()
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(screen.queryByRole('button', { name: '이전' })).toBeNull()

    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', BRN)
    expect(within(picker).getByLabelText('사업자등록번호')).toHaveValue('778-12-34560')
    await click(picker, '찾기')
    // 기존 조회 API 그대로(정확 일치, POST 본문에만 — 숫자 10자리).
    expect(api.merchantSearch).toHaveBeenCalledWith({ registrationNumber: BRN })
    const found = await within(picker).findByRole('list', { name: '찾은 사업자' })
    // 같은 사업자의 가맹점 두 건은 사업자 하나로 묶인다.
    expect(within(found).getAllByRole('listitem')).toHaveLength(1)
    expect(found).toHaveTextContent('데모 사업자')
    expect(found).toHaveTextContent('778*******')
    expect(within(picker).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.textContent).not.toMatch(/biz_/)
    expect(container.innerHTML).not.toContain(BRN)
    expect(container.innerHTML).not.toContain('778-12-34560')

    await user.click(within(found).getByRole('button', { name: '선택' }))
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).toContain('778*******')
    expect(container.textContent).not.toMatch(/biz_/)
    expect(storageIsEmpty()).toBe(true)
  })

  it('① "다음 단계 →" without a selected business stays on ① and sends nothing; an empty search says so; a malformed number is not sent', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await next()
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(step('사업자 선택')).getByRole('alert')).toHaveTextContent('사업자를 선택하세요.')
    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', '778-12-345')
    await click(picker, '찾기')
    expect(within(picker).getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    expect(api.merchantSearch).not.toHaveBeenCalled()
    await user.clear(within(picker).getByLabelText('사업자등록번호'))
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    expect(await within(picker).findByText('찾은 사업자가 없습니다(가맹점이 있는 사업자만 찾습니다).')).toBeInTheDocument()
    expect(api.registerMerchant).not.toHaveBeenCalled()
  })

  it('① a refused or failed search shows the fixed message and still clears the number; a session end hands control back', async () => {
    const onSessionEnded = vi.fn()
    const api = fakeApi({
      merchantSearch: vi.fn()
        .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
        .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
        .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED')),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} onSessionEnded={onSessionEnded} newSubmissionId={ids()} />)
    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    expect(await within(picker).findByRole('alert')).toHaveTextContent('이 작업을 할 권한이 없습니다.')
    expect(within(picker).getByLabelText('사업자등록번호')).toHaveValue('')
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    expect(await within(picker).findByText(/너무 잦습니다/)).toBeInTheDocument()
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    await vi.waitFor(() => expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })

  it('② "다음 단계 →" sends the merchant (with the selected business id) then the Site once each; "이전" then "다음 단계 →" resends neither', async () => {
    const api = fakeApi({
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    expect(currentStep()).toBe('2. 가맹점·사이트')
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    expect(await screen.findByRole('region', { name: '계약 초안' })).toBeInTheDocument()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'shopabcd12' })
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
    expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })

    await previous()
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByText(CODE)).toBeInTheDocument()
    expect(within(step('Site')).getByText(SITE)).toBeInTheDocument()
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toBeDisabled()
    expect(within(step('가맹점')).queryByRole('button', { name: '가맹점 등록' })).toBeNull()
    await next()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
  })

  it('①→④: the whole flow sends each save once with the same bodies; ④ shows the business by name + masked number (no business id), points to the real next steps and opens the lookup', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [listItem(id('mer', 9))], nextCursor: null })),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
      registerContract: vi.fn(async () => succeeded(CTR)),
      addServiceAgreement: vi.fn(async () => succeeded(CSA)),
    })
    const onOpenLookup = vi.fn()
    const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await pickBusiness()
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()

    const contract = await screen.findByRole('region', { name: '계약 초안' })
    expect(partyText()).toContain('데모 사업자')
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    expect(within(contract).getByLabelText('시작일')).toHaveAttribute('min', '2026-11-08')
    expect(within(contract).getByText('이 날 0시 종료(미포함)')).toBeInTheDocument()
    expect(within(contract).getByLabelText('가맹점 전체')).toBeChecked()
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await typeIn(contract, '종료일(선택)', '2027-01-01')
    const agreement = step('서비스 계약')
    expect(within(agreement).getByLabelText('서비스 종류')).toHaveValue('CARD_PG_PAYMENT')
    // D-ADMIN-UI-004 ⑤: 서비스 종류는 목록으로 그린다 — 지금은 하나뿐.
    expect(within(agreement).getAllByRole('option').map((o) => o.textContent)).toEqual(['카드 PG 결제'])
    await next()

    const done = await screen.findByRole('region', { name: '등록 결과' })
    expect(currentStep()).toBe('4. 검토·완료')
    expect(api.registerContract).toHaveBeenCalledTimes(1)
    expect(api.registerContract).toHaveBeenCalledWith({
      submissionId: sid(3), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [SITE], effectiveFromDate: '2026-11-09', effectiveToDate: '2027-01-01',
    })
    expect(api.addServiceAgreement).toHaveBeenCalledTimes(1)
    expect(api.addServiceAgreement).toHaveBeenCalledWith({ submissionId: sid(4), contractId: CTR, serviceKind: 'CARD_PG_PAYMENT' })

    for (const created of [SITE, CTR, CSA]) expect(within(done).getByText(created)).toBeInTheDocument()
    // 리뷰 NIT-4: 가맹점은 가맹점 행과 Site 행(소유 가맹점)에 한 번씩 — MC-2부터 코드로(mer_ 0건).
    expect(within(done).getAllByText(CODE)).toHaveLength(2)
    expect(done.textContent).not.toContain('mer_')
    expect(within(done).getByText('778*******')).toBeInTheDocument()
    expect(within(done).getByText('데모 사업자')).toBeInTheDocument()
    expect(within(done).getByText('데모 가맹점')).toBeInTheDocument()
    expect(within(done).getByText('demo-shop2.paycore.test')).toBeInTheDocument()
    expect(within(done).getByText('카드 PG 결제')).toBeInTheDocument()
    // D-ADMIN-UI-004 ③: 사업자 ID는 어디에도 보이지 않는다.
    expect(container.textContent).not.toMatch(/biz_/)
    expect(within(done).getByText(/Revision 1, 승인 전/)).toBeInTheDocument()
    expect(done).toHaveTextContent('다음: 가맹점 조회 상세에서 계약 승인 → 첫 MID 발급을 요청하세요. 결제수단·Route 개통 전까지 결제는 아직 할 수 없습니다.')
    expect(within(within(done).getByRole('list', { name: '다음 단계' })).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '가맹점 조회 상세', '계약 승인 요청 (2인)', '첫 MID 발급 (2인)', '개통',
    ])
    expect(screen.queryByRole('button', { name: /MID/ })).toBeNull()
    expect(screen.queryByRole('button', { name: '다음 단계 →' })).toBeNull()
    await click(done, '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledTimes(1)
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
    expect(storageIsEmpty()).toBe(true)
  })

  it('② without a selected business the merchant is not sent (replaces the old biz_ format check — no business id field any more)', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const merchant = step('가맹점')
    expect(merchantBusinessText()).toContain('선택한 사업자 없음')
    expect(within(merchant).queryByLabelText('사업자 ID')).toBeNull()
    await typeMerchant(merchant, '데모 가맹점')
    await click(merchant, '가맹점 등록')
    expect(within(merchant).getByText('사업자를 먼저 선택하세요(1단계).')).toBeInTheDocument()
    expect(api.registerMerchant).not.toHaveBeenCalled()
  })

  it('② with the merchant saved but the Site rejected: the next "다음 단계 →" sends only the Site (the merchant is not resent)', async () => {
    const api = fakeApi({
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn().mockResolvedValueOnce(rejected('SITE_CHANNEL_DUPLICATE')).mockResolvedValueOnce(succeeded(SITE)),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await typeMerchant(step('가맹점'), '데모 가맹점')
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeDisabled()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    expect(currentStep()).toBe('2. 가맹점·사이트')

    await click(step('가맹점'), '같은 내용 다시 보내기')
    expect(await within(step('가맹점')).findByText(CODE)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(screen.getByRole('button', { name: '다음 단계 →' })).toBeEnabled()
    expect(within(step('Site')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(send).toHaveBeenCalledTimes(2)
    expect(api.registerMerchantSite).toHaveBeenCalledTimes(1)
  })

  it('the wizard step, selected business and saved results survive leaving the screen (memory) and "새 가맹점 등록 시작" starts over only when nothing is unresolved', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) })
    const memory = withBusiness()
    const first = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    first.unmount()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByText(CODE)).toBeInTheDocument()
    expect(merchantBusinessText()).toContain('데모 사업자')

    await goTo('4. 검토·완료')
    const done = step('등록 결과')
    expect(within(done).getByText(CODE)).toBeInTheDocument()
    await click(done, '새 가맹점 등록 시작')
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(step('사업자 선택')).queryByRole('group', { name: '선택한 사업자' })).toBeNull()
    await goTo('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toHaveValue('')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toBeEnabled()
    expect(merchantBusinessText()).toContain('선택한 사업자 없음')
    expect(api.registerMerchant).toHaveBeenCalledTimes(1)
  })

  it('"새 가맹점 등록 시작" is disabled while any step is waiting for a result check', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })
    await goTo('4. 검토·완료')
    expect(within(step('등록 결과')).getByRole('button', { name: '새 가맹점 등록 시작' })).toBeDisabled()
    expect(step('등록 결과')).toHaveTextContent('결과 확인이 필요한 단계가 있습니다')
  })

  it('a replayed Site rejection says so', async () => {
    const api = fakeApi({ registerMerchantSite: vi.fn(async () => rejected('SITE_CHANNEL_DUPLICATE', true)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', MER)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    expect(await within(site).findByRole('alert')).toHaveTextContent('이미 다른 Site가 쓰는 채널 식별값입니다 (이전에 처리된 결과입니다)')
  })

  /** 응답을 시험이 정할 때까지 붙잡는 API 대역. */
  function deferred() {
    let resolve: (outcome: RegistrationOutcome) => void = () => undefined
    const send = vi.fn(() => new Promise<RegistrationOutcome>((r) => { resolve = r }))
    return { send, resolve: (outcome: RegistrationOutcome) => act(async () => resolve(outcome)) }
  }
  async function fillMerchantAndSite(channel = 'demo-shop2.paycore.test') {
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', channel)
  }

  it('review MINOR-1: when the screen unmounts while the first card is in flight, its result is still recorded but the next card is never sent', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    const memory = withBusiness()
    const view = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    await fillMerchantAndSite()
    await next()
    expect(merchant.send).toHaveBeenCalledTimes(1)
    view.unmount()
    await merchant.resolve(succeeded(MER))
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(within(step('가맹점')).getByText(CODE)).toBeInTheDocument()
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('review MINOR-1: when the memory is swapped (session boundary) while the first card is in flight, the next card is never sent', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    const view = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await fillMerchantAndSite()
    await next()
    view.rerender(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={createScreenMemory()} />)
    await merchant.resolve(succeeded(MER))
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
    expect(currentStep()).toBe('1. 사업자 선택')
  })

  it('review: a double click or re-click on "다음 단계 →" while it is in flight sends once', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send, registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await fillMerchantAndSite()
    const button = screen.getByRole('button', { name: '다음 단계 →' })
    await user.dblClick(button)
    await user.click(button)
    expect(merchant.send).toHaveBeenCalledTimes(1)
    expect(button).toBeDisabled()
    await merchant.resolve(succeeded(MER))
    expect(await screen.findByRole('region', { name: '계약 초안' })).toBeInTheDocument()
    expect(merchant.send).toHaveBeenCalledTimes(1)
  })

  it('review NIT-3: card submit buttons are disabled while "다음 단계 →" is sending', async () => {
    const merchant = deferred()
    const api = fakeApi({ registerMerchant: merchant.send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await fillMerchantAndSite()
    await next()
    expect(within(step('Site')).getByRole('button', { name: 'Site 등록' })).toBeDisabled()
    await merchant.resolve(rejected('BUSINESS_NOT_FOUND'))
    expect(within(step('Site')).getByRole('button', { name: 'Site 등록' })).toBeEnabled()
    expect(within(step('가맹점')).getByRole('button', { name: '가맹점 등록' })).toBeEnabled()
  })

  it('review: in ② a merchant success followed by a Site format error stays on ② and the next "다음 단계 →" does not resend the merchant', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    await fillMerchantAndSite('Shop.Example.test')
    await next()
    expect(await within(step('가맹점')).findByText(CODE)).toBeInTheDocument()
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
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
    expect(rows[0]).toHaveTextContent(`가맹점 ${CODE}`)
    expect(rows[1]).toHaveTextContent(SITE2)
    expect(rows[1]).toHaveTextContent(`가맹점 ${CODE}`)
  })

  it('a network failure and AUDIT_UNAVAILABLE are treated as unknown too; "취소하고 새로 입력" warns first and the next submit uses a new submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE'))
      .mockResolvedValueOnce(succeeded(MER))
    const api = fakeApi({ registerMerchant: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    const merchant = step('가맹점')
    await typeMerchant(merchant, '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await within(merchant).findByRole('group', { name: '결과 확인 필요' })
    await click(merchant, '같은 내용 다시 보내기')
    const group = await within(merchant).findByRole('group', { name: '결과 확인 필요' })
    // review m-2: 등록 화면의 감사 불가 문구는 "결과가 확실하지 않음"(조회 문구 아님).
    expect(within(group).getByRole('alert')).toHaveTextContent('감사 기록을 남기지 못했습니다. 처리 여부가 확실하지 않으니 가맹점 조회로 확인하거나 같은 내용으로 다시 보내세요.')
    await click(merchant, '취소하고 새로 입력')
    expect(within(merchant).getByText(/앞 요청이 이미 처리됐을 수 있습니다 — 가맹점 조회로 먼저 확인하세요/)).toBeInTheDocument()
    await click(merchant, '그래도 새로 입력')
    expect(within(merchant).getByLabelText('가맹점명')).toBeEnabled()
    await click(merchant, '가맹점 등록')
    expect(await within(merchant).findByText(CODE)).toBeInTheDocument()
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    const merchant = step('가맹점')
    await typeMerchant(merchant, '데모 가맹점')
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
    expect(await within(merchant).findByText(CODE)).toBeInTheDocument()
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
    const merchant = step('가맹점')
    await typeMerchant(merchant, '데모 가맹점')
    await click(merchant, '가맹점 등록')
    await click(await within(merchant).findByRole('group', { name: '결과 확인 필요' }), '같은 내용 다시 보내기')
    expect(await within(merchant).findByRole('alert')).toHaveTextContent('화면 결함 신호')
    expect(within(merchant).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(within(merchant).getByLabelText('가맹점명')).toBeEnabled()
    await click(merchant, '가맹점 등록')
    expect(await within(merchant).findByText(CODE)).toBeInTheDocument()
    expect(send.mock.calls.map(([input]) => input.submissionId)).toEqual([sid(1), sid(1), sid(2)])
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

  it('formats are checked on screen without sending (no automatic normalization): upper-case host, malformed merchant id', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    await typeIn(site, '가맹점 ID', 'mer_1')
    await typeIn(site, '채널 식별값', 'Shop.Example.test')
    await click(site, 'Site 등록')
    expect(within(site).getByText('가맹점 ID 형식(mer_…)이 아닙니다.')).toBeInTheDocument()
    expect(within(site).getByLabelText('채널 식별값')).toHaveValue('Shop.Example.test')
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: a MERCHANT scope sends an empty site list and no end date; a start before the picker minimum is still sent (MASTER decides) and CONTRACT_START_RETROACTIVE shows its fixed sentence', async () => {
    const send = vi.fn(async () => rejected('CONTRACT_START_RETROACTIVE'))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    expect(partyText()).toContain('데모 사업자')
    await typeIn(contract, '가맹점 ID', MER)
    await typeIn(contract, '시작일', '2026-11-07')
    await next()
    expect(send).toHaveBeenCalledWith({ submissionId: sid(1), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-07' })
    expect(await within(contract).findByRole('alert')).toHaveTextContent('시작일이 오늘보다 앞입니다')
    expect(within(contract).getByLabelText('시작일')).toBeEnabled()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.addServiceAgreement).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: screen checks without sending — no selected business, SITES scope with no site, malformed typed site id, end not after start, missing start', async () => {
    const api = fakeApi()
    const first = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
    await goTo('3. 계약 초안·서비스 계약')
    await typeIn(step('계약 초안'), '가맹점 ID', MER)
    await typeIn(step('계약 초안'), '시작일', '2026-11-09')
    await click(step('계약 초안'), '계약 초안 등록')
    expect(within(step('계약 초안')).getByText('사업자를 먼저 선택하세요(1단계).')).toBeInTheDocument()
    first.unmount()

    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '가맹점 ID', MER)
    await click(contract, '계약 초안 등록')
    expect(within(contract).getByText('시작일을 고르세요.')).toBeInTheDocument()
    await typeIn(contract, '시작일', '2026-11-09')
    await typeIn(contract, '종료일(선택)', '2026-11-09')
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE)

    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    expect(within(contract).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByRole('group', { name: '결과 확인 필요' })

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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const agreement = step('서비스 계약')
    await typeIn(agreement, '계약 ID', CTR)
    await click(agreement, '서비스 계약 추가')
    await within(agreement).findByRole('group', { name: '결과 확인 필요' })

    const contract = step('계약 초안')
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

  it('review m-1 (auto-fill vs lock): 가맹점 locked keeps its business when another business is selected; Site locked keeps its 가맹점 ID when 가맹점 succeeds, while unlocked 계약 초안 is still filled', async () => {
    const BIZ2 = id('biz', 8)
    const MER2 = id('mer', 8)
    const merchantSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(MER2))
    const siteSend = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(SITE, true))
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [listItem(id('mer', 9), BIZ2, '다른 사업자')], nextCursor: null })),
      registerMerchant: merchantSend,
      registerMerchantSite: siteSend,
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)

    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByRole('group', { name: '결과 확인 필요' })

    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })

    // ①에서 다른 사업자를 고른다 — 잠긴 가맹점 카드는 그대로, 잠기지 않은 계약 초안에는 채워진다.
    await goTo('1. 사업자 선택')
    await pickBusiness()
    await goTo('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).not.toContain('다른 사업자')
    await goTo('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('다른 사업자')

    await goTo('2. 가맹점·사이트')
    await click(step('가맹점'), '같은 내용 다시 보내기')
    await within(step('가맹점')).findByText(CODE)
    expect(merchantSend.mock.calls[1]![0]).toEqual(merchantSend.mock.calls[0]![0])
    expect(merchantSend.mock.calls[0]![0]).toEqual({ submissionId: sid(2), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'shopabcd12' })
    expect(within(step('Site')).getByLabelText('가맹점 ID')).toHaveValue(MER)
    await goTo('3. 계약 초안·서비스 계약')
    // 가맹점 성공 → 계약 당사자 = 그 가맹점의 사업자(잠기지 않은 카드), 대상 = 그 가맹점.
    expect(partyText()).toContain('데모 사업자')
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
    const api = fakeApi({ registerMerchant: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} onSessionEnded={onSessionEnded} newSubmissionId={ids()} memory={withBusiness()} />)
    await fillMerchantAndSite()
    await next()
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
  })
})

describe('App wiring (MERCHANT-OPS-002 §5.1 — OPERATOR only, display only; ADMIN-UI-MERCHANT-002 separate 사업자 등록 / 가맹점 등록 tabs)', () => {
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
  const openTab = async (name: '가맹점 조회' | '사업자 등록' | '가맹점 등록') => user.click(screen.getByRole('tab', { name }))
  const businessCard = () => screen.getByRole('region', { name: '사업자' })
  async function registerBusinessOnScreen() {
    await typeIn(businessCard(), '사업자등록번호', BRN)
    await typeIn(businessCard(), '사업자명', '데모 사업자')
    await click(businessCard(), '사업자 등록')
  }

  it('an OPERATOR sees the 가맹점 조회, 사업자 등록 and 가맹점 등록 tabs (no header action); each tab opens its own screen', async () => {
    signedIn(['OPERATOR'])
    await openMerchants()
    expect(screen.getByRole('heading', { level: 1, name: '사업자·가맹점' })).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점 조회', '사업자 등록', '가맹점 등록'])
    await openTab('사업자 등록')
    expect(screen.getByRole('tab', { name: '사업자 등록' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('region', { name: '사업자' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: '등록 단계' })).toBeNull()
    expect(screen.getAllByRole('button', { name: '사업자 등록' })).toHaveLength(1)
    await openTab('가맹점 등록')
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(screen.getByRole('region', { name: '사업자 선택' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자' })).toBeNull()
    expect(screen.queryByRole('button', { name: '가맹점 조회로' })).toBeNull()
  })

  it('"이 사업자로 가맹점 등록" opens 가맹점 등록 on ② with that business (name + masked only) and the merchant body carries its business id', async () => {
    const { api, view } = signedInWithApi(['OPERATOR'], {
      registerBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await user.click(await screen.findByRole('button', { name: '이 사업자로 가맹점 등록' }))
    expect(screen.getByRole('tab', { name: '가맹점 등록' })).toHaveAttribute('aria-selected', 'true')
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).toContain('778*******')
    expect(view.container.textContent).not.toMatch(/biz_/)
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: expect.stringMatching(/^[0-9a-f]{32}$/), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'shopabcd12' })
    expect(storageIsEmpty()).toBe(true)
  })

  /** 사업자 등록 화면에서 두 번째 사업자(B)를 등록한다(앞 사업자 완료 카드를 "사업자 새로 입력"으로 비운 뒤). */
  async function registerSecondBusiness() {
    await openTab('사업자 등록')
    await click(businessCard(), '사업자 새로 입력')
    await typeIn(businessCard(), '사업자등록번호', '1234567890')
    await typeIn(businessCard(), '사업자명', '두번째 사업자')
    await click(businessCard(), '사업자 등록')
    await within(businessCard()).findByText('123*******')
  }

  it('review MINOR-1: handing a second business to a 가맹점 등록 in progress asks first; 취소 keeps the wizard as is, 새로 시작 clears it and opens ② with only the new business', async () => {
    const BIZ2 = id('biz', 8)
    const { api } = signedInWithApi(['OPERATOR'], {
      registerBusiness: vi.fn().mockResolvedValueOnce(succeeded(BIZ)).mockResolvedValueOnce(succeeded(BIZ2)),
      registerMerchant: vi.fn().mockResolvedValueOnce(succeeded(MER)).mockResolvedValueOnce(succeeded(id('mer', 8))),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await user.click(await screen.findByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    await goTo('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('데모 사업자')

    await registerSecondBusiness()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    const confirm = screen.getByRole('group', { name: '가맹점 등록 새로 시작 확인' })
    expect(screen.getByRole('tab', { name: '사업자 등록' })).toHaveAttribute('aria-selected', 'true')
    await click(confirm, '취소')
    expect(screen.queryByRole('group', { name: '가맹점 등록 새로 시작 확인' })).toBeNull()
    // 취소: 진행 중인 마법사는 그대로(계약 초안 당사자가 조용히 바뀌지 않는다).
    await openTab('가맹점 등록')
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('데모 사업자')
    expect(partyText()).not.toContain('두번째 사업자')

    await openTab('사업자 등록')
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await click(screen.getByRole('group', { name: '가맹점 등록 새로 시작 확인' }), '새로 시작')
    expect(screen.getByRole('tab', { name: '가맹점 등록' })).toHaveAttribute('aria-selected', 'true')
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('두번째 사업자')
    expect(merchantBusinessText()).not.toContain('데모 사업자')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toHaveValue('')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toBeEnabled()
    expect(within(step('가맹점')).queryByText(CODE)).toBeNull()
    await goTo('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('두번째 사업자')
    expect(within(step('계약 초안')).getByLabelText('가맹점 ID')).toHaveValue('')
    await goTo('2. 가맹점·사이트')
    await typeMerchant(step('가맹점'), '둘째 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    expect(vi.mocked(api.registerMerchant).mock.calls[1]![0]).toMatchObject({ businessId: BIZ2, displayName: '둘째 가맹점' })
  })

  it('review MINOR-1: while a 가맹점 등록 card waits for a result check, "이 사업자로 가맹점 등록" warns and hands nothing over', async () => {
    const BIZ2 = id('biz', 8)
    signedIn(['OPERATOR'], {
      registerBusiness: vi.fn().mockResolvedValueOnce(succeeded(BIZ)).mockResolvedValueOnce(succeeded(BIZ2)),
      registerMerchant: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await user.click(await screen.findByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })

    await registerSecondBusiness()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    expect(screen.getByText(/가맹점 등록에 결과 확인이 필요한 단계가 있습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: '가맹점 등록 새로 시작 확인' })).toBeNull()
    expect(screen.getByRole('tab', { name: '사업자 등록' })).toHaveAttribute('aria-selected', 'true')
    await openTab('가맹점 등록')
    expect(within(step('가맹점')).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).not.toContain('두번째 사업자')
  })

  it('review m-1: an unknown outcome on 사업자 등록 survives switching to 가맹점 조회 and back — still locked, the resend reuses the same submissionId', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
    signedIn(['OPERATOR'], { registerBusiness: send })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await within(businessCard()).findByRole('group', { name: '결과 확인 필요' })

    await openTab('가맹점 조회')
    expect(screen.queryByRole('region', { name: '사업자' })).toBeNull()
    await openTab('사업자 등록')

    const business = businessCard()
    expect(within(business).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(business).getByLabelText('사업자명')).toBeDisabled()
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeDisabled()
    await click(business, '같은 내용 다시 보내기')
    expect(await within(businessCard()).findByText('778*******')).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(storageIsEmpty()).toBe(true)
  })

  it('review m-3: "가맹점 조회에서 보기" opens the 가맹점 조회 tab on the created merchant detail (existing detail path, once)', async () => {
    const detail: MerchantDetail = {
      merchant: { merchantId: MER, merchantCode: codeOf(MER), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-10-01T00:00:00.000Z' },
      business: { businessId: BIZ, name: '데모 사업자', registrationNumberMasked: '778*******', lifecycle: 'ACTIVE' },
      sites: [], contracts: [], accounts: [], limits: [], propagation: null,
    }
    const { api } = signedInWithApi(['OPERATOR'], {
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    await openMerchants()
    await openTab('가맹점 등록')
    await goTo('2. 가맹점·사이트')
    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE)
    await goTo('4. 검토·완료')
    await click(step('등록 결과'), '가맹점 조회에서 보기')

    expect(screen.getByRole('tab', { name: '가맹점 조회' })).toHaveAttribute('aria-selected', 'true')
    // MC-2: 상세 제목은 가맹점 코드(조회 호출은 내부 키 그대로 — 아래 merchantDetail 인자).
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(MER)}`) })).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantDetail).toHaveBeenCalledWith(MER)
    expect(api.merchantSearch).not.toHaveBeenCalled()

    await openTab('가맹점 등록')
    await openTab('가맹점 조회')
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  /** 사업자 등록을 결과 불명(잠금) 상태로 만든다 — 사업자명 '데모 사업자'가 입력된 채로 잠긴다. */
  const lockBusinessStep = async () => {
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    const business = businessCard()
    await within(business).findByRole('group', { name: '결과 확인 필요' })
    expect(within(business).getByLabelText('사업자명')).toHaveValue('데모 사업자')
  }
  const signInAgain = async () => {
    await user.type(await screen.findByLabelText('로그인 이름'), 'op-demo')
    await user.type(screen.getByLabelText('비밀번호'), 'demo-password')
    await user.click(screen.getByRole('button', { name: '다음' }))
    await user.type(await screen.findByLabelText('인증 코드(6자리)'), '123456')
    await user.click(screen.getByRole('button', { name: '로그인' }))
  }
  /** 다시 들어온 뒤: 사업자 등록·가맹점 등록 모두 잠금·재전송·입력·고른 사업자가 남아 있지 않다. */
  const expectFreshRegistration = async () => {
    await openMerchants()
    await openTab('사업자 등록')
    const business = await screen.findByRole('region', { name: '사업자' })
    expect(within(business).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(screen.queryByRole('button', { name: '같은 내용 다시 보내기' })).toBeNull()
    expect(within(business).getByLabelText('사업자명')).toHaveValue('')
    expect(within(business).getByLabelText('사업자명')).toBeEnabled()
    expect(within(business).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(business).getByRole('button', { name: '사업자 등록' })).toBeEnabled()
    await openTab('가맹점 등록')
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(step('사업자 선택')).queryByRole('group', { name: '선택한 사업자' })).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  }
  const OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] }
  const OTHER_OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V2', displayName: '다른 운영자', roles: ['OPERATOR'] }
  const loginStubs = (account: Me) => ({ password: vi.fn(async () => ({ next: 'TOTP' as const })), totp: vi.fn(async () => account) })

  it('review MINOR-1: logout resets the registration memories — after signing in again there is no lock, no resend, no typed input and no selected business', async () => {
    signedIn(['OPERATOR'], { registerBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await signInAgain()
    await expectFreshRegistration()
  })

  it('review MINOR-1: a session end (SESSION_EXPIRED on the resend) resets the registration memories and the login screen notes the unresolved request', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    signedIn(['OPERATOR'], { registerBusiness: send, ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await click(businessCard(), '같은 내용 다시 보내기')
    expect(await screen.findByRole('status')).toHaveTextContent(ERROR_MESSAGES.SESSION_EXPIRED)
    expect(screen.getByRole('note', { name: '결과 확인 필요' })).toHaveTextContent('결과 확인이 필요한 요청이 있습니다 — 가맹점 조회로 확인하세요')
    await signInAgain()
    await expectFreshRegistration()
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('review MINOR-1: a session end while 가맹점 등록 has a locked card also notes the unresolved request', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    signedIn(['OPERATOR'], { registerMerchantSite: send, ...loginStubs(OPERATOR) })
    await openMerchants()
    await openTab('가맹점 등록')
    await goTo('2. 가맹점·사이트')
    await typeIn(step('Site'), '가맹점 ID', MER)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByRole('group', { name: '결과 확인 필요' })
    await click(step('Site'), '같은 내용 다시 보내기')
    expect(await screen.findByRole('note', { name: '결과 확인 필요' })).toBeInTheDocument()
  })

  it('review MINOR-1: signing in as another account starts with fresh registration memories', async () => {
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
    await openTab('사업자 등록')
    await typeIn(businessCard(), '사업자등록번호', BRN)
    await typeIn(businessCard(), '사업자명', '데모 사업자')
    expect(within(businessCard()).getByLabelText('사업자등록번호')).toHaveValue('778-12-34560')

    await openTab('가맹점 조회')
    await openTab('사업자 등록')
    expect(within(businessCard()).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(businessCard()).getByLabelText('사업자명')).toHaveValue('데모 사업자')
    expect(storageIsEmpty()).toBe(true)
  })

  it('VIEWER, APPROVER and SECURITY_ADMIN have no 사업자 등록 / 가맹점 등록 tab (and no header action)', async () => {
    for (const role of ['VIEWER', 'APPROVER', 'SECURITY_ADMIN']) {
      const view = signedIn([role])
      await openMerchants()
      expect(screen.queryByRole('button', { name: '사업자 등록' })).toBeNull()
      expect(screen.queryByRole('button', { name: '가맹점 등록' })).toBeNull()
      expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점 조회'])
      view.unmount()
    }
  })

  describe('MERCHANT-CODE-001 MC-1 merchant code input (direct input — preview/suggest below in MC-2)', () => {
    it('MC-1-9 the merchant card has a code field with the format hint; the code is sent as typed', async () => {
      const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) })
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      const merchant = step('가맹점')
      expect(within(merchant).getByLabelText('가맹점 코드')).toBeInTheDocument()
      expect(merchant).toHaveTextContent('영문 소문자로 시작, 영문 소문자·숫자 6~14자')
      await typeMerchant(merchant, '데모 가맹점', 'myshop2026')
      await click(merchant, '가맹점 등록')
      expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'myshop2026' })
    })

    it('MC-1-9 a missing or malformed code (upper case, hyphen, too short, leading digit) shows the format error and sends nothing — no normalization', async () => {
      const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) })
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      const merchant = step('가맹점')
      await typeIn(merchant, '가맹점명', '데모 가맹점')
      await click(merchant, '가맹점 등록')
      expect(within(merchant).getByText('영문 소문자로 시작, 영문 소문자·숫자 6~14자로 입력하세요.')).toBeInTheDocument()
      for (const bad of ['ShopAbcd12', 'shop-abcd', 'abcde', '1shopabc']) {
        const field = within(merchant).getByLabelText('가맹점 코드')
        await user.clear(field)
        await typeIn(merchant, '가맹점 코드', bad)
        await click(merchant, '가맹점 등록')
        expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue(bad)
      }
      expect(api.registerMerchant).not.toHaveBeenCalled()
    })

    it('MC-1-9 MASTER MERCHANT_CODE_TAKEN / MERCHANT_CODE_RESERVED show their fixed messages', async () => {
      const api = fakeApi({
        registerMerchant: vi.fn().mockResolvedValueOnce(rejected('MERCHANT_CODE_TAKEN')).mockResolvedValueOnce(rejected('MERCHANT_CODE_RESERVED')),
      })
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      const merchant = step('가맹점')
      await typeMerchant(merchant, '데모 가맹점', 'shopabcd12')
      await click(merchant, '가맹점 등록')
      expect(await within(merchant).findByText(/이미 사용 중이거나 사용했던 코드입니다/)).toBeInTheDocument()
      // 리뷰 m-1: 거절은 확정 결과 — 코드를 고쳐 다시 제출하면 새 submissionId로 보내고 RESERVED 문구를 보인다.
      const field = within(merchant).getByLabelText('가맹점 코드')
      await user.clear(field)
      await typeIn(merchant, '가맹점 코드', 'adminshop1')
      await click(merchant, '가맹점 등록')
      expect(await within(merchant).findByText(/사용할 수 없는 코드입니다/)).toBeInTheDocument()
      // 재리뷰 NIT: RESERVED 재제출 뒤 이전 TAKEN 문구는 남지 않는다(결과는 마지막 제출 하나).
      expect(within(merchant).queryByText(/이미 사용 중이거나 사용했던 코드입니다/)).toBeNull()
      expect(vi.mocked(api.registerMerchant).mock.calls.map(([input]) => input)).toEqual([
        { submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'shopabcd12' },
        { submissionId: sid(2), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'adminshop1' },
      ])
    })
  })

  describe('MERCHANT-CODE-001 MC-2 code preview (§3.4) and display (mer_ → code)', () => {
    /** 고정 난수 바이트(첫 꼬리 'xk3m9p'·두 번째 꼬리 'ab2c3d'가 나오게 — 알파벳 순번을 바이트로). */
    const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'
    const bytesFor = (...tails: string[]) => {
      const queue = tails.flatMap((t) => Array.from(t).map((c) => ALPHABET.indexOf(c)))
      return (length: number) => Uint8Array.from({ length }, () => queue.shift() ?? 0)
    }
    const render2 = (api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) }), randomBytes = bytesFor('xk3m9p', 'ab2c3d')) => {
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} randomBytes={randomBytes} />)
      return api
    }

    it('MC-2-1 typing the English trade name fills a §3.1 preview (name letters/digits + 4 random); editing the name keeps the random tail; Korean-only names give no preview', async () => {
      render2()
      const merchant = step('가맹점')
      await typeIn(merchant, '영문 상호(코드 제안용)', 'Paycore Coffee')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('paycorecof' + 'k3m9')
      await user.clear(within(merchant).getByLabelText('영문 상호(코드 제안용)'))
      await typeIn(merchant, '영문 상호(코드 제안용)', 'Q')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('q' + 'k3m9p')
      await user.clear(within(merchant).getByLabelText('영문 상호(코드 제안용)'))
      await typeIn(merchant, '영문 상호(코드 제안용)', '페이코어')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('')
      expect(merchant).toHaveTextContent('제안은 참고용이며 사용 가능 여부는 등록할 때 확인합니다')
    })

    it('MC-2-1 "다시 제안" draws a new random tail; an operator edit is kept when the name changes and is sent exactly as typed (the server decides)', async () => {
      const api = render2()
      const merchant = step('가맹점')
      await typeIn(merchant, '영문 상호(코드 제안용)', 'Demo Shop')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('demoshop' + 'k3m9')
      await click(merchant, '다시 제안')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('demoshop' + 'b2c3')
      await user.clear(within(merchant).getByLabelText('가맹점 코드'))
      await typeIn(merchant, '가맹점 코드', 'mydemo77')
      await typeIn(merchant, '영문 상호(코드 제안용)', ' Seoul')
      expect(within(merchant).getByLabelText('가맹점 코드')).toHaveValue('mydemo77')
      await typeIn(merchant, '가맹점명', '데모 가맹점')
      await click(merchant, '가맹점 등록')
      expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: sid(1), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'mydemo77' })
      expect(vi.mocked(api.registerMerchant).mock.calls[0]![0]).not.toHaveProperty('englishName')
    })

    it('MC-2-1 the preview is only a suggestion: MASTER TAKEN on a suggested code shows the fixed message and nothing is reserved in advance', async () => {
      const api = render2(fakeApi({ registerMerchant: vi.fn(async () => rejected('MERCHANT_CODE_TAKEN')) }))
      const merchant = step('가맹점')
      await typeIn(merchant, '영문 상호(코드 제안용)', 'Demo Shop')
      await typeIn(merchant, '가맹점명', '데모 가맹점')
      await click(merchant, '가맹점 등록')
      expect(await within(merchant).findByText(/이미 사용 중이거나 사용했던 코드입니다/)).toBeInTheDocument()
      expect(api.registerMerchant).toHaveBeenCalledTimes(1)
      expect(api.merchantSearch).not.toHaveBeenCalled()
    })

    /**
     * DOM 전체에서 mer_가 나오는 곳 목록(텍스트 노드, 모든 요소의 모든 속성, 입력란 value 속성/프로퍼티). 위치 = `<region>/<label 또는 tag>/<종류>`.
     * C-28: MC-2는 표시 텍스트·접근 이름·목록·상세·결과·검토 화면만 — 마법사 Site·계약 초안 카드의 "가맹점 ID" 입력칸(value·placeholder·그 오류 문구)은 MC-2b까지 남는다.
     */
    const merOccurrences = (root: HTMLElement): string[] => {
      const found = new Set<string>()
      const where = (el: Element) => `${el.closest('section[aria-label]')?.getAttribute('aria-label') ?? '-'}/${el.closest('label')?.querySelector('span')?.textContent ?? el.tagName}`
      for (const el of [root, ...Array.from(root.querySelectorAll('*'))]) {
        for (const attr of Array.from(el.attributes)) if (attr.value.includes('mer_')) found.add(`${where(el)}/${attr.name === 'value' ? 'value' : `@${attr.name}`}`)
        if (el instanceof HTMLInputElement && el.value.includes('mer_')) found.add(`${where(el)}/value`)
        for (const node of Array.from(el.childNodes)) {
          if (node.nodeType === Node.TEXT_NODE && node.textContent?.includes('mer_')) found.add(`${where(el)}/text:${node.textContent}`)
        }
      }
      return [...found].sort()
    }
    /** MC-2b로 미룬 자리 전체(이 밖에서 mer_가 나오면 RED). */
    const MC2B_ALLOWED = [
      'Site/가맹점 ID/@placeholder', 'Site/가맹점 ID/value', 'Site/P/text:가맹점 ID 형식(mer_…)이 아닙니다.',
      '계약 초안/가맹점 ID/@placeholder', '계약 초안/가맹점 ID/value', '계약 초안/P/text:가맹점 ID 형식(mer_…)이 아닙니다.',
    ]

    it('MC-2-3 DOM (C-28 — MC-2 part): in the whole DOM (text, every attribute, input values) mer_ appears only in the two wizard merchant-ID inputs deferred to MC-2b — result, review and Site rows show the code', async () => {
      const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      // ② 입력 전: 두 칸 중 화면에 있는 Site 칸의 placeholder뿐.
      expect(merOccurrences(container)).toEqual(['Site/가맹점 ID/@placeholder'])
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await next()
      // ③: 자동 채움된 계약 초안 칸(value·placeholder)만.
      await screen.findByRole('region', { name: '계약 초안' })
      expect(merOccurrences(container)).toEqual(['계약 초안/가맹점 ID/@placeholder', '계약 초안/가맹점 ID/value'])
      // ④ 검토: 0건(가맹점·Site 행 모두 코드).
      await goTo('4. 검토·완료')
      expect(step('등록 결과')).toHaveTextContent(`가맹점 코드 ${CODE}`)
      expect(merOccurrences(container)).toEqual([])
      // ② 결과: 가맹점 카드 결과는 코드, mer_는 Site 칸(value·placeholder)만.
      await goTo('2. 가맹점·사이트')
      expect(step('가맹점')).toHaveTextContent(`가맹점 코드 ${CODE}`)
      expect(merOccurrences(container)).toEqual(['Site/가맹점 ID/@placeholder', 'Site/가맹점 ID/value'])
      for (const place of merOccurrences(container)) expect(MC2B_ALLOWED).toContain(place)
    })

    it('MC-2-3 DOM (C-28): the merchant-ID format error is the only other mer_ text and is inside the MC-2b list', async () => {
      const api = fakeApi({ registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      await typeIn(step('Site'), '가맹점 ID', 'mer_1')
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await click(step('Site'), 'Site 등록')
      expect(merOccurrences(container)).toEqual(['Site/P/text:가맹점 ID 형식(mer_…)이 아닙니다.', 'Site/가맹점 ID/@placeholder', 'Site/가맹점 ID/value'])
      for (const place of merOccurrences(container)) expect(MC2B_ALLOWED).toContain(place)
      expect(api.registerMerchantSite).not.toHaveBeenCalled()
    })
  })
})
