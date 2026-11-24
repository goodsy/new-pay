import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantRegistrationScreen, offerBusiness, offerMerchant } from './MerchantRegistrationScreen'
import { kstDate, type BusinessRef, type MerchantRef } from '../lib/registration'
import { domOccurrences } from '../test/domScan'
import { App } from '../App'
import type { MerchantDetail, Me, RegistrationOutcome } from '../api/adminApi'
import { apiError, callsContaining, fakeApi, leakedCalls, storageIsEmpty } from '../test/fakeApi'
import { ERROR_MESSAGES } from '../errors/messages'
import { createScreenMemory } from '../lib/screenMemory'
import { draftMinimalDetail, individualRegisteredDetail } from '../test/businessFixtures'

/**
 * 가맹점 등록 마법사(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ④ 화면 분리: ① 사업자 선택 → ② 가맹점·사이트 → ③ 계약 초안·서비스 계약 → ④ 검토·완료).
 * 기대값 변경 근거(ADMIN-UI-MERCHANT-001 시험에서):
 * - ① 사업자 정보(사업자 등록 카드)는 사업자 등록 화면으로 옮겼다(BusinessRegistrationScreen.test — 같은 단언). 이 화면 ①은 저장 없는 "사업자 선택"(기존 사업자등록번호 가맹점 검색 재사용).
 * - 사업자 ID 입력란(biz_…)이 없어졌다(D-ADMIN-UI-004 ③): 가맹점·계약 초안의 사업자는 고른 사업자(상호·마스킹 번호 표시)이고, 보내는 body의 businessId·partyBusinessId는 그대로 같은 값.
 *   "biz_1 형식 오류" 시험은 "사업자를 고르지 않으면 보내지 않음" 시험으로 바뀌었다. 사업자 ID가 화면에 보인다는 단언은 보이지 않는다는 단언으로 뒤집었다.
 * - "새 사업자 등록 시작" → "새 가맹점 등록 시작", "기존 사업자로 이어서" 버튼 없음(사업자 선택이 그 역할).
 * - 보내는 body·submissionId·잠금/결과 불명·거절 문구·자동 채움 vs 잠금·저장소 없음 단언은 그대로.
 * 기대값 변경 근거(MERCHANT-CODE-001 MC-2b — D-MCODE-003 ①): Site·계약 초안 카드의 "가맹점 ID"(mer_) 입력칸이 없어졌다 — 가맹점은 맥락(이 마법사 가맹점 카드 성공 또는
 *   [offerMerchant]로 준 가맹점)에서 정해지고 읽기 전용 "소속 가맹점: <상호> · <코드>"로 보인다. 칸에 mer_를 입력하던 시험은 [withMerchant]로 맥락 가맹점을 준다(보내는 body의
 *   merchantId·scopeMerchantId는 같은 내부 키 그대로 — 본문 단언 불변). "가맹점 ID 형식 오류" 시험은 "소속 가맹점이 없으면 보내지 않음"으로 바뀌었다(입력칸이 없어 형식 오류가 생길 수 없다).
 *   잠긴 계약 초안의 "가맹점 ID 칸 disabled" 단언은 "소속 가맹점 표시가 그대로"로 바뀌었다. MC-2의 허용 목록(MC2B_ALLOWED 6곳)은 빈 목록이 되었다.
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
const BUSINESS: BusinessRef = { businessId: BIZ, name: '데모 사업자', registrationNumber: '7781234567' }
const succeeded = (reference: string, replayed = false): RegistrationOutcome => ({ outcome: 'SUCCEEDED', reference, replayed })
const rejected = (rejection: string, replayed = false): RegistrationOutcome => ({ outcome: 'REJECTED', rejection, replayed })
/**
 * BUSINESS-PROFILE-001 BIZ-4(§8.4 — 합친 MERCHANT-BIZ-LOOKUP-001, D-BIZ-004): ① 사업자 선택은 사업자등록번호 조회 API(0/1건 + registrationStatus — D-BIZ-006 ③)를 쓴다.
 * 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 §8.4): ① 시험의 대역을 가맹점 검색(merchantSearch — 가맹점 0개 사업자를 못 찾음)에서 번호 조회로 바꿨다. 보내는 body·선택 뒤 흐름 단언은 그대로.
 */
const lookupOf = (businessId: string, businessName = '데모 사업자', registrationStatus: 'DRAFT' | 'REGISTERED' = 'REGISTERED') => ({
  items: [{ businessId, businessName, registrationNumber: '7781234567', registrationStatus }],
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

/** MC-2b: 맥락 가맹점(이 마법사 밖에서 정해진 가맹점 — 명시 Demo fixture, 업무 의미 없는 고정값). */
const CONTEXT_MERCHANT: MerchantRef = { merchantId: MER, businessId: BIZ, name: '기존 가맹점', code: codeOf(MER) }
/** 맥락 가맹점을 준 메모리(사업자도 골라 둠). Site·계약 초안 카드의 소속 가맹점이 이 가맹점이 된다. */
function withMerchant(merchant: MerchantRef = CONTEXT_MERCHANT, memory = withBusiness()) {
  offerMerchant(memory, merchant)
  return memory
}
/** MC-2b: Site·계약 초안 카드의 읽기 전용 소속 가맹점 표시. */
const affiliation = (region: string) => within(step(region)).getByRole('group', { name: '소속 가맹점' })
// LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
// 읽기 전용 소속 가맹점 줄은 이름 열(aria-hidden span) = '소속 가맹점', 값 상자(.fieldcontrol) = '<상호> · <코드>' 또는 '아직 없음'으로 나눠 단언한다.
const expectAffiliation = (region: string, value: string) => {
  const group = affiliation(region)
  expect(group.firstElementChild).toHaveTextContent(/^소속 가맹점$/)
  expect(group.querySelector(':scope > .fieldcontrol')).toHaveTextContent(value)
}
/** typeMerchant 기본값으로 이 마법사에서 등록한 가맹점의 소속 표시(값 상자). */
const OWN_AFFILIATION = `데모 가맹점 · ${CODE}` // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
const CONTEXT_AFFILIATION = `기존 가맹점 · ${codeOf(MER)}` // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)

// 실행 시간 상한만 20초(BIZ4-gate2에서 ①→④ 전체 흐름이 gate 부하로 5.1초 — App wiring·ApprovalsScreen 선례). 단언·findBy 대기는 그대로.
describe('MerchantRegistrationScreen wizard (ADMIN-UI-MERCHANT-002 — ① 사업자 선택 → ② 가맹점·사이트 → ③ 계약 초안·서비스 계약 → ④ 검토·완료)', { timeout: 20_000 }, () => {
  it('① BIZ-4-4 (D-BIZ-004): finds the business with the registration-number lookup (merchantSearch not called — a business without merchants is found), number cleared and never rendered; name + number only (D-BIZ-007); "선택" moves to ②', async () => {
    const api = fakeApi({
      lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
    })
    const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    expect(screen.getByRole('heading', { name: '가맹점 등록' })).toBeInTheDocument()
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(screen.queryByRole('button', { name: '이전' })).toBeNull()

    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', BRN)
    expect(within(picker).getByLabelText('사업자등록번호')).toHaveValue('778-12-34560')
    await click(picker, '찾기')
    // 합친 번호 조회(정확 일치, POST 본문에만 — 숫자 10자리). 가맹점 검색은 쓰지 않는다(가맹점 0개 사업자도 찾는다 — D-BIZ-004).
    expect(api.lookupBusinessByRegistrationNumber).toHaveBeenCalledWith(BRN)
    expect(api.merchantSearch).not.toHaveBeenCalled()
    const found = await within(picker).findByRole('list', { name: '찾은 사업자' })
    expect(within(found).getAllByRole('listitem')).toHaveLength(1)
    expect(found).toHaveTextContent('데모 사업자')
    expect(found).toHaveTextContent('778-12-34567')
    expect(found).toHaveTextContent('등록 완료')
    expect(within(picker).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.textContent).not.toMatch(/biz_/)
    expect(container.innerHTML).not.toContain(BRN)
    expect(container.innerHTML).not.toContain('778-12-34560')

    await user.click(within(found).getByRole('button', { name: '선택' }))
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).toContain('778-12-34567')
    expect(domOccurrences(container, 'biz_')).toEqual([])
    expect(storageIsEmpty()).toBe(true)
    // 리뷰 MINOR-3: 원문 번호는 번호 조회 요청에만 — 가맹점 등록 본문 등 다른 호출에 없다.
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await next()
    await screen.findByRole('region', { name: '계약 초안' })
    expect(callsContaining(api, BRN)).toEqual(['lookupBusinessByRegistrationNumber'])
    expect(leakedCalls(api, BRN)).toEqual([])
  })

  it('① BIZ-4-4 (D-BIZ-006 ③ 결정 고정): a DRAFT business cannot be selected — "사업자 등록 완료 후 가맹점 등록 가능" + 사업자 상세 보기 (memory link, no biz_ in the DOM)', async () => {
    const onOpenBusiness = vi.fn()
    const api = fakeApi({ lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ, '임시 사업자', 'DRAFT')) })
    const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} onOpenBusiness={onOpenBusiness} newSubmissionId={ids()} />)
    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    const found = await within(picker).findByRole('list', { name: '찾은 사업자' })
    expect(found).toHaveTextContent('임시 사업자')
    expect(found).toHaveTextContent('사업자 등록 완료 후 가맹점 등록 가능')
    expect(within(found).queryByRole('button', { name: '선택' })).toBeNull()
    await user.click(within(found).getByRole('button', { name: '사업자 상세 보기' }))
    expect(onOpenBusiness).toHaveBeenCalledWith(BIZ)
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(picker).queryByRole('group', { name: '선택한 사업자' })).toBeNull()
    expect(domOccurrences(container, 'biz_')).toEqual([])
    await next()
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(api.registerMerchant).not.toHaveBeenCalled()
  })

  it('① "다음 단계 →" without a selected business stays on ① and sends nothing; no business for the number says to register it first; a malformed number is not sent', async () => {
    const api = fakeApi({ lookupBusinessByRegistrationNumber: vi.fn(async () => ({ items: [] })) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await next()
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(step('사업자 선택')).getByRole('alert')).toHaveTextContent('사업자를 선택하세요.')
    const picker = step('사업자 선택')
    await typeIn(picker, '사업자등록번호', '778-12-345')
    await click(picker, '찾기')
    expect(within(picker).getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    expect(api.lookupBusinessByRegistrationNumber).not.toHaveBeenCalled()
    await user.clear(within(picker).getByLabelText('사업자등록번호'))
    await typeIn(picker, '사업자등록번호', BRN)
    await click(picker, '찾기')
    expect(await within(picker).findByText('이 번호로 등록된 사업자가 없습니다. 사업자 등록에서 먼저 등록하세요.')).toBeInTheDocument()
    expect(api.registerMerchant).not.toHaveBeenCalled()
  })

  it('① a refused or failed lookup shows the fixed message (429 = shared BRN budget, Demo value) and still clears the number; a session end hands control back', async () => {
    const onSessionEnded = vi.fn()
    const api = fakeApi({
      lookupBusinessByRegistrationNumber: vi.fn()
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
    expect(await within(picker).findByText(/사업자등록번호 10회\/분/)).toHaveTextContent('Demo 값')
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

  it('①→④: the whole flow sends each save once with the same bodies; ④ shows the business by name + hyphenated number (no business id — D-BIZ-007), points to the real next steps and opens the lookup', async () => {
    const api = fakeApi({
      lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ)),
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
    expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    expect(within(contract).queryByLabelText('가맹점 ID')).toBeNull()
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
    expect(within(done).getByText('778-12-34567')).toBeInTheDocument()
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
    // 리뷰 NIT-2: 오류는 묶음 설명으로 연결되고, 접근 이름은 한 번만(보이는 제목은 aria-hidden).
    expect(within(merchant).getByRole('group', { name: '사업자' })).toHaveAccessibleDescription('사업자를 먼저 선택하세요(1단계).')
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
    expectAffiliation('Site', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withMerchant()} />)
    const site = step('Site')
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
    expectAffiliation('Site', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
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

  it('review m-3: "가맹점 조회에서 보기" after a Site on a context merchant (not registered in this wizard) passes that merchant id; the Site body carries its internal key', async () => {
    const onOpenLookup = vi.fn()
    const api = fakeApi({ registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} memory={withMerchant()} />)
    const site = step('Site')
    expectAffiliation('Site', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await typeIn(site, '채널 식별값', 'demo-shop2.paycore.test')
    await click(site, 'Site 등록')
    await within(site).findByText(SITE)
    expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: sid(1), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
    await goTo('4. 검토·완료')
    // ④ Site 행의 소유 가맹점도 코드(맥락 가맹점은 코드를 안다).
    expect(within(step('등록 결과')).getByRole('list', { name: '저장된 Site' })).toHaveTextContent(`가맹점 ${codeOf(MER)}`)
    await click(step('등록 결과'), '가맹점 조회에서 보기')
    expect(onOpenLookup).toHaveBeenCalledWith(MER)
  })

  it('409 IDEMPOTENCY_KEY_REUSED is shown as a defect and allows a new submission (new submissionId); 403 and 429 show their fixed messages', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
    const api = fakeApi({ registerMerchantSite: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withMerchant()} />)
    const site = step('Site')
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

  it('formats are checked on screen without sending (no automatic normalization): upper-case host; without an affiliated merchant (MC-2b — replaces the old mer_ format check) the Site is not sent', async () => {
    const api = fakeApi()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} />)
    await goTo('2. 가맹점·사이트')
    const site = step('Site')
    // MC-2b(D-MCODE-003 ①): 가맹점 ID 입력칸이 없다 — 소속 가맹점은 맥락으로 정해지고, 없으면 그렇게 보인다.
    expect(within(site).queryByLabelText('가맹점 ID')).toBeNull()
    expectAffiliation('Site', '아직 없음') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await typeIn(site, '채널 식별값', 'Shop.Example.test')
    await click(site, 'Site 등록')
    expect(within(site).getByText('가맹점을 먼저 등록하세요(가맹점 카드).')).toBeInTheDocument()
    expect(affiliation('Site')).toHaveAccessibleDescription('가맹점을 먼저 등록하세요(가맹점 카드).')
    expect(within(site).getByText('형식이 맞지 않습니다(소문자 정규형만 — 자동으로 바꾸지 않습니다).')).toBeInTheDocument()
    expect(within(site).getByLabelText('채널 식별값')).toHaveValue('Shop.Example.test')
    expect(api.registerMerchantSite).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: a MERCHANT scope sends an empty site list and no end date; a start before the picker minimum is still sent (MASTER decides) and CONTRACT_START_RETROACTIVE shows its fixed sentence', async () => {
    const send = vi.fn(async () => rejected('CONTRACT_START_RETROACTIVE'))
    const api = fakeApi({ registerContract: send })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    expect(partyText()).toContain('데모 사업자')
    expectAffiliation('계약 초안', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await typeIn(contract, '시작일', '2026-11-07')
    await next()
    expect(send).toHaveBeenCalledWith({ submissionId: sid(1), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-07' })
    expect(await within(contract).findByRole('alert')).toHaveTextContent('시작일이 오늘보다 앞입니다')
    expect(within(contract).getByLabelText('시작일')).toBeEnabled()
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(api.addServiceAgreement).not.toHaveBeenCalled()
  })

  it('B1-2 계약 초안: screen checks without sending — no selected business, no affiliated merchant (MC-2b), SITES scope with no site, malformed typed site id, end not after start, missing start', async () => {
    const api = fakeApi()
    const noBusiness = createScreenMemory()
    offerMerchant(noBusiness, CONTEXT_MERCHANT)
    const first = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={noBusiness} />)
    await goTo('3. 계약 초안·서비스 계약')
    await typeIn(step('계약 초안'), '시작일', '2026-11-09')
    await click(step('계약 초안'), '계약 초안 등록')
    expect(within(step('계약 초안')).getByText('사업자를 먼저 선택하세요(1단계).')).toBeInTheDocument()
    expect(within(step('계약 초안')).getByRole('group', { name: '계약 당사자' })).toHaveAccessibleDescription('사업자를 먼저 선택하세요(1단계).')
    first.unmount()

    // MC-2b: 사업자는 골랐지만 소속 가맹점이 없으면 보내지 않는다(가맹점 ID 입력칸 없음).
    const second = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
    await goTo('3. 계약 초안·서비스 계약')
    expect(within(step('계약 초안')).queryByLabelText('가맹점 ID')).toBeNull()
    expectAffiliation('계약 초안', '아직 없음') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await typeIn(step('계약 초안'), '시작일', '2026-11-09')
    await click(step('계약 초안'), '계약 초안 등록')
    expect(within(step('계약 초안')).getByText('가맹점을 먼저 등록하세요(2단계 가맹점 카드).')).toBeInTheDocument()
    second.unmount()

    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    await typeIn(contract, '시작일', '2026-11-09')
    await next()
    const group = await within(contract).findByRole('group', { name: '결과 확인 필요' })
    expect(within(contract).getByLabelText('시작일')).toBeDisabled()
    // MC-2b: 가맹점은 입력칸이 아니라 읽기 전용 표시 — 잠긴 동안에도 처음 보낸 소속 가맹점 그대로.
    expectAffiliation('계약 초안', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={onOpenLookup} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
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
    const memory = withMerchant()
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={memory} />)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE)

    await goTo('3. 계약 초안·서비스 계약')
    const contract = step('계약 초안')
    expectAffiliation('계약 초안', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await user.click(within(contract).getByLabelText('선택 Site'))
    await user.click(within(contract).getByRole('checkbox', { name: SITE }))
    await typeIn(contract, '시작일', '2026-11-09')
    await click(contract, '계약 초안 등록')
    await within(contract).findByRole('group', { name: '결과 확인 필요' })

    await goTo('2. 가맹점·사이트')
    await click(step('Site'), '다른 Site 추가 입력')
    // MC-2b: 다른 가맹점은 입력칸이 아니라 맥락으로 정해진다(그 가맹점을 맥락으로 준다 — 잠기지 않은 Site에만 채워진다).
    act(() => offerMerchant(memory, { merchantId: MER2, businessId: BIZ, name: '둘째 가맹점', code: codeOf(MER2) }))
    expectAffiliation('Site', `둘째 가맹점 · ${codeOf(MER2)}`) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await user.clear(within(step('Site')).getByLabelText('채널 식별값'))
    await typeIn(step('Site'), '채널 식별값', 'demo-shop3.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE2)
    expect(vi.mocked(api.registerMerchantSite).mock.calls[1]![0]).toMatchObject({ merchantId: MER2 })

    await goTo('3. 계약 초안·서비스 계약')
    expectAffiliation('계약 초안', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
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
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
    await goTo('3. 계약 초안·서비스 계약')
    const agreement = step('서비스 계약')
    await typeIn(agreement, '계약 ID', CTR)
    await click(agreement, '서비스 계약 추가')
    await within(agreement).findByRole('group', { name: '결과 확인 필요' })

    const contract = step('계약 초안')
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
      lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ2, '다른 사업자')),
      registerMerchant: merchantSend,
      registerMerchantSite: siteSend,
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withMerchant()} />)

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
    // MC-2b: 잠긴 Site의 소속 가맹점은 처음 그대로(맥락 가맹점).
    expectAffiliation('Site', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await goTo('3. 계약 초안·서비스 계약')
    // 가맹점 성공 → 계약 당사자 = 그 가맹점의 사업자(잠기지 않은 카드), 대상 = 그 가맹점.
    expect(partyText()).toContain('데모 사업자')
    expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)

    await goTo('2. 가맹점·사이트')
    await click(step('Site'), '같은 내용 다시 보내기')
    expect(await within(step('Site')).findByText(SITE)).toBeInTheDocument()
    expect(siteSend.mock.calls[1]![0]).toEqual(siteSend.mock.calls[0]![0])
    expect(siteSend.mock.calls[0]![0]).toEqual({ submissionId: sid(1), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
    // 리뷰 MINOR-1: 늦게 온 Site 성공(가맹점 A = 맥락 가맹점 MER, 지금 맥락은 MER2)은 잠기지 않은 계약 초안의 소속 가맹점을 A로 되돌리지 않는다.
    await goTo('3. 계약 초안·서비스 계약')
    expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
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

/**
 * 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 — C-27 옛 사업자 등록 경로 제거, §8.1·§8.3): "사업자 등록" 탭의 화면이 번호·상호 두 칸 카드(BusinessRegistrationScreen)에서
 * 새 사업자 등록 폼(BusinessProfileFormScreen — 섹션 1~6·임시저장/등록 완료)으로 바뀌었고, 새 "사업자 조회" 탭이 생겼다(OPERATOR·APPROVER — D-BIZ-006 ①).
 * "이 사업자로 가맹점 등록"은 등록 완료 뒤 사업자 조회 상세(성공 뒤 상세 재조회 — C-18)에서 누른다(REGISTERED만 — D-BIZ-001). 같은 동작 단언(넘겨주기·잠금 유지·
 * 같은 submissionId 재전송·세션 경계 초기화·평문 번호 비보관·탭 표시)은 새 경로에서 그대로 유지한다.
 */
// 부하 대응(BIZ-4): 새 등록 폼은 REGISTERED 필수 칸을 모두 채우므로(사업자 두 개를 만드는 시험 포함) 전체 게이트 부하에서 기본 5초를 넘을 수 있다 — 시간 상한만 넓힌다(단언 불변,
// ApprovalsScreen.targetLabels 20초 선례).
describe('App wiring (MERCHANT-OPS-002 §5.1 + BUSINESS-PROFILE-001 §8.1 — 가맹점 · 사업자 tabs, 등록 screens as sub-pages (D-ADMIN-UI-006), display only)', { timeout: 20_000 }, () => {
  const BIZ2 = id('biz', 8)
  const detailFor = (businessId: string) =>
    businessId === BIZ2
      ? { ...individualRegisteredDetail(8, '두번째 사업자'), business: { ...individualRegisteredDetail(8, '두번째 사업자').business, businessId: BIZ2, registrationNumber: '1234567890' } }
      : { ...individualRegisteredDetail(7, '데모 사업자'), business: { ...individualRegisteredDetail(7, '데모 사업자').business, businessId } }
  function signedInWithApi(roles: string[], overrides: Parameters<typeof fakeApi>[0] = {}) {
    const api = fakeApi({
      me: vi.fn(async (): Promise<Me> => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles })),
      approvals: vi.fn(async () => ({ approvals: [] })),
      reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
      accounts: vi.fn(async () => ({ accounts: [] })),
      audit: vi.fn(async () => ({ entries: [] })),
      businessDetail: vi.fn(async (businessId: string) => detailFor(businessId)),
      ...overrides,
    })
    return { view: render(<App api={api} />), api }
  }
  const signedIn = (roles: string[], overrides: Parameters<typeof fakeApi>[0] = {}) => signedInWithApi(roles, overrides).view
  const openMerchants = async () =>
    user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
  // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 4개 → '가맹점'·'사업자' 2개. 조회 화면은 그 탭, 등록 화면은 탭을 누른 뒤 목록 제목 줄의 '＋ … 등록' 버튼(같은 탭 안 하위 화면).
  // 이 도우미가 두 동작을 흡수한다 — 아래 시험의 화면 전환 순서·업무 단언은 그대로. 사업자 탭이 기억한 상세(사업자 조회 메모리 — 기존 동작)가 열려 있으면
  // 등록 버튼은 목록에만 있으므로(D-ADMIN-UI-006 "목록 위 버튼") 상세의 기존 '목록으로'를 먼저 누른다.
  const TAB_OF = { '가맹점 조회': '가맹점', '사업자 조회': '사업자', '사업자 등록': '사업자', '가맹점 등록': '가맹점' } as const
  const openTab = async (name: keyof typeof TAB_OF) => {
    await user.click(screen.getByRole('tab', { name: TAB_OF[name] }))
    if (name !== '사업자 등록' && name !== '가맹점 등록') return
    const toList = screen.queryByRole('button', { name: '목록으로' })
    if (toList) await user.click(toList)
    await user.click(await screen.findByRole('button', { name: `＋ ${name}` }))
  }
  const page = () => document.body
  const formCard = () => screen.getByRole('region', { name: '사업자 기본' })
  /** 사업자 등록 폼의 최소(임시저장) 칸. */
  async function fillDraftBusiness(number = BRN, name = '데모 사업자') {
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await typeIn(page(), '사업자등록번호', number)
    await typeIn(page(), '상호', name)
  }
  /** 사업자 등록 폼에서 REGISTERED 사업자를 만든다(등록 완료 → 사업자 조회 상세 — C-18 재조회). */
  async function registerBusinessOnScreen(number = BRN, name = '데모 사업자') {
    await fillDraftBusiness(number, name)
    for (const [label, value] of [
      ['개업일', '2026-01-01'], ['업태 1', '도소매'], ['종목 1', '전자상거래'], ['사업장 우편번호', '04524'], ['사업장 기본주소', '서울특별시 중구 세종대로 110'],
      ['대표자 1 이름', '홍길동'], ['사업체 전화', '0212345678'], ['담당자 1 이름', '김담당'], ['담당자 1 이메일', 'contact.demo@example.test'], ['담당자 1 전화', '01012345678'],
    ] as const) await typeIn(page(), label, value)
    await user.click(screen.getByRole('button', { name: '등록 완료' }))
  }
  const detailRegion = () => screen.findByRole('region', { name: '사업자 상세' })

  // D-ADMIN-UI-006(LAYOUT-ROLLOUT B1 reviewFix1 NIT): 제목만 새 구조로 — 탭 2개 + 목록 버튼이 연 등록 화면.
  it('an OPERATOR sees 가맹점 · 사업자 tabs (no header action); each list opens its 등록 screen inside the same tab', async () => {
    signedIn(['OPERATOR'], { searchBusinesses: vi.fn(async () => ({ items: [], next: null })) })
    await openMerchants()
    expect(screen.getByRole('heading', { level: 1, name: '사업자·가맹점' })).toBeInTheDocument()
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 4개 → 2개, 등록 화면에서는 부모 탭이 선택.
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점', '사업자'])
    await openTab('사업자 등록')
    expect(screen.getByRole('tab', { name: '사업자' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('region', { name: '사업자 기본' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: '등록 단계' })).toBeNull()
    expect(screen.getAllByRole('button', { name: '등록 완료' })).toHaveLength(1)
    await openTab('사업자 조회')
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    await openTab('가맹점 등록')
    expect(within(stepper()).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([...STEPS])
    expect(screen.getByRole('region', { name: '사업자 선택' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '가맹점 조회로' })).toBeNull()
  })

  it('등록 완료 opens the 사업자 조회 detail (re-read); "이 사업자로 가맹점 등록" opens 가맹점 등록 on ② with that business (name + number only (D-BIZ-007)) and the merchant body carries its business id', async () => {
    const { api, view } = signedInWithApi(['OPERATOR'], {
      createBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    expect(await detailRegion()).toHaveTextContent('등록 완료')
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '사업자 조회' → '사업자'.
    expect(screen.getByRole('tab', { name: '사업자' })).toHaveAttribute('aria-selected', 'true')
    expect(api.businessDetail).toHaveBeenCalledWith(BIZ)
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 가맹점 등록은 '가맹점' 탭의 하위 화면 — 부모 탭이 선택.
    expect(screen.getByRole('tab', { name: '가맹점' })).toHaveAttribute('aria-selected', 'true')
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).toContain('778-12-34567')
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    expect(api.registerMerchant).toHaveBeenCalledWith({ submissionId: expect.stringMatching(/^[0-9a-f]{32}$/), businessId: BIZ, displayName: '데모 가맹점', merchantCode: 'shopabcd12' })
    expect(window.location.href).not.toContain('biz_')
    expect(storageIsEmpty()).toBe(true)
  })

  it('BIZ-4-4 (D-BIZ-006 ③): a DRAFT business found in 가맹점 등록 ① links to its 사업자 조회 detail (memory only — no biz_ in the URL or DOM)', async () => {
    const draft = { ...draftMinimalDetail(7, '임시 사업자'), business: { ...draftMinimalDetail(7, '임시 사업자').business, businessId: BIZ } }
    const { api, view } = signedInWithApi(['OPERATOR'], {
      lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ, '임시 사업자', 'DRAFT')),
      businessDetail: vi.fn(async () => draft),
    })
    await openMerchants()
    await openTab('가맹점 등록')
    await typeIn(step('사업자 선택'), '사업자등록번호', BRN)
    await click(step('사업자 선택'), '찾기')
    await user.click(await within(step('사업자 선택')).findByRole('button', { name: '사업자 상세 보기' }))
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '사업자 조회' → '사업자'.
    expect(screen.getByRole('tab', { name: '사업자' })).toHaveAttribute('aria-selected', 'true')
    expect(await detailRegion()).toHaveTextContent('임시저장 사업자에는 새 가맹점을 등록할 수 없습니다')
    expect(api.businessDetail).toHaveBeenCalledWith(BIZ)
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    expect(window.location.href).not.toContain('biz_')
  })

  /** 사업자 등록 탭에서 두 번째 사업자(B)를 등록하고 그 상세로 간다. */
  async function registerSecondBusiness() {
    await openTab('사업자 등록')
    await registerBusinessOnScreen('1234567890', '두번째 사업자')
    expect(await detailRegion()).toHaveTextContent('두번째 사업자')
  }

  it('review MINOR-1: handing a second business to a 가맹점 등록 in progress asks first; 취소 keeps the wizard as is, 새로 시작 clears it and opens ② with only the new business', async () => {
    const { api } = signedInWithApi(['OPERATOR'], {
      createBusiness: vi.fn().mockResolvedValueOnce(succeeded(BIZ)).mockResolvedValueOnce(succeeded(BIZ2)),
      registerMerchant: vi.fn().mockResolvedValueOnce(succeeded(MER)).mockResolvedValueOnce(succeeded(id('mer', 8))),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await detailRegion()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    await goTo('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('데모 사업자')

    await registerSecondBusiness()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    const confirm = screen.getByRole('group', { name: '가맹점 등록 새로 시작 확인' })
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '사업자 조회' → '사업자'.
    expect(screen.getByRole('tab', { name: '사업자' })).toHaveAttribute('aria-selected', 'true')
    await click(confirm, '취소')
    expect(screen.queryByRole('group', { name: '가맹점 등록 새로 시작 확인' })).toBeNull()
    await openTab('가맹점 등록')
    expect(currentStep()).toBe('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('데모 사업자')
    expect(partyText()).not.toContain('두번째 사업자')

    await openTab('사업자 조회')
    await user.click(await screen.findByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await click(screen.getByRole('group', { name: '가맹점 등록 새로 시작 확인' }), '새로 시작')
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 가맹점 등록은 '가맹점' 탭의 하위 화면 — 부모 탭이 선택.
    expect(screen.getByRole('tab', { name: '가맹점' })).toHaveAttribute('aria-selected', 'true')
    expect(currentStep()).toBe('2. 가맹점·사이트')
    expect(merchantBusinessText()).toContain('두번째 사업자')
    expect(merchantBusinessText()).not.toContain('데모 사업자')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toHaveValue('')
    expect(within(step('가맹점')).getByLabelText('가맹점명')).toBeEnabled()
    await goTo('3. 계약 초안·서비스 계약')
    expect(partyText()).toContain('두번째 사업자')
    expectAffiliation('계약 초안', '아직 없음') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    await goTo('2. 가맹점·사이트')
    await typeMerchant(step('가맹점'), '둘째 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    expect(vi.mocked(api.registerMerchant).mock.calls[1]![0]).toMatchObject({ businessId: BIZ2, displayName: '둘째 가맹점' })
  })

  it('review MINOR-1: while a 가맹점 등록 card waits for a result check, "이 사업자로 가맹점 등록" warns and hands nothing over', async () => {
    signedIn(['OPERATOR'], {
      createBusiness: vi.fn().mockResolvedValueOnce(succeeded(BIZ)).mockResolvedValueOnce(succeeded(BIZ2)),
      registerMerchant: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await detailRegion()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByRole('group', { name: '결과 확인 필요' })

    await registerSecondBusiness()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    expect(screen.getByText(/가맹점 등록에 결과 확인이 필요한 단계가 있습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: '가맹점 등록 새로 시작 확인' })).toBeNull()
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '사업자 조회' → '사업자'.
    expect(screen.getByRole('tab', { name: '사업자' })).toHaveAttribute('aria-selected', 'true')
    await openTab('가맹점 등록')
    expect(within(step('가맹점')).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(merchantBusinessText()).toContain('데모 사업자')
    expect(merchantBusinessText()).not.toContain('두번째 사업자')
  })

  it('review m-1: an unknown outcome on 사업자 등록 survives switching to 가맹점 조회 and back — still locked, the resend reuses the same submissionId and body', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
    signedIn(['OPERATOR'], { createBusiness: send })
    await openMerchants()
    await openTab('사업자 등록')
    await fillDraftBusiness()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    await screen.findByRole('group', { name: '결과 확인 필요' })

    await openTab('가맹점 조회')
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    await openTab('사업자 등록')

    expect(screen.getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(formCard()).getByLabelText('상호')).toBeDisabled()
    expect(within(formCard()).getByLabelText('상호')).toHaveValue('데모 사업자')
    expect(screen.getByRole('button', { name: '임시저장' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await detailRegion()).toHaveTextContent('데모 사업자')
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(storageIsEmpty()).toBe(true)
  })

  it('review m-3: "가맹점 조회에서 보기" opens the 가맹점 조회 tab on the created merchant detail (existing detail path, once)', async () => {
    const detail: MerchantDetail = {
      merchant: { merchantId: MER, merchantCode: codeOf(MER), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-10-01T00:00:00.000Z' },
      business: { businessId: BIZ, name: '데모 사업자', registrationNumber: '7781234567', lifecycle: 'ACTIVE' },
      sites: [], contracts: [], accounts: [], limits: [], propagation: null,
    }
    const { api } = signedInWithApi(['OPERATOR'], {
      createBusiness: vi.fn(async () => succeeded(BIZ)),
      registerMerchant: vi.fn(async () => succeeded(MER)),
      registerMerchantSite: vi.fn(async () => succeeded(SITE)),
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await detailRegion()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByText(SITE)
    await goTo('4. 검토·완료')
    await click(step('등록 결과'), '가맹점 조회에서 보기')

    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회' → '가맹점'.
    expect(screen.getByRole('tab', { name: '가맹점' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(MER)}`) })).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantDetail).toHaveBeenCalledWith(MER)
    expect(api.merchantSearch).not.toHaveBeenCalled()

    await openTab('가맹점 등록')
    await openTab('가맹점 조회')
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  /** 사업자 등록을 결과 불명(잠금) 상태로 만든다 — 상호 '데모 사업자'가 입력된 채로 잠긴다. */
  const lockBusinessStep = async () => {
    await openMerchants()
    await openTab('사업자 등록')
    await fillDraftBusiness()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(within(formCard()).getByLabelText('상호')).toHaveValue('데모 사업자')
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
    const form = await screen.findByRole('region', { name: '사업자 기본' })
    expect(screen.queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(screen.queryByRole('button', { name: '같은 내용 다시 보내기' })).toBeNull()
    expect(within(form).getByLabelText('상호')).toHaveValue('')
    expect(within(form).getByLabelText('상호')).toBeEnabled()
    expect(within(form).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(screen.getByRole('button', { name: '임시저장' })).toBeEnabled()
    await openTab('가맹점 등록')
    expect(currentStep()).toBe('1. 사업자 선택')
    expect(within(step('사업자 선택')).queryByRole('group', { name: '선택한 사업자' })).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  }
  const OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] }
  const OTHER_OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V2', displayName: '다른 운영자', roles: ['OPERATOR'] }
  const loginStubs = (account: Me) => ({ password: vi.fn(async () => ({ next: 'TOTP' as const })), totp: vi.fn(async () => account) })

  it('review MINOR-1: logout resets the registration memories — after signing in again there is no lock, no resend, no typed input and no selected business', async () => {
    signedIn(['OPERATOR'], { createBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await signInAgain()
    await expectFreshRegistration()
  })

  it('review MINOR-1: a session end (SESSION_EXPIRED on the resend) resets the registration memories and the login screen notes the unresolved request', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    signedIn(['OPERATOR'], { createBusiness: send, ...loginStubs(OPERATOR) })
    await lockBusinessStep()
    await user.click(screen.getByRole('button', { name: '같은 내용 다시 보내기' }))
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
    signedIn(['OPERATOR'], {
      createBusiness: vi.fn(async () => succeeded(BIZ)), registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: send, ...loginStubs(OPERATOR),
    })
    await openMerchants()
    await openTab('사업자 등록')
    await registerBusinessOnScreen()
    await detailRegion()
    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    await typeMerchant(step('가맹점'), '데모 가맹점')
    await click(step('가맹점'), '가맹점 등록')
    await within(step('가맹점')).findByText(CODE)
    await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
    await click(step('Site'), 'Site 등록')
    await within(step('Site')).findByRole('group', { name: '결과 확인 필요' })
    await click(step('Site'), '같은 내용 다시 보내기')
    expect(await screen.findByRole('note', { name: '결과 확인 필요' })).toBeInTheDocument()
  })

  it('review MINOR-1: signing in as another account starts with fresh registration memories', async () => {
    signedIn(['OPERATOR'], { createBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), ...loginStubs(OTHER_OPERATOR) })
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
    await typeIn(page(), '사업자등록번호', BRN)
    await typeIn(page(), '상호', '데모 사업자')
    expect(within(formCard()).getByLabelText('사업자등록번호')).toHaveValue('778-12-34560')

    await openTab('가맹점 조회')
    await openTab('사업자 등록')
    expect(within(formCard()).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(within(formCard()).getByLabelText('상호')).toHaveValue('데모 사업자')
    expect(storageIsEmpty()).toBe(true)
  })

  it('D-BIZ-006 ①: VIEWER and SECURITY_ADMIN see only the 가맹점 tab; APPROVER also sees 사업자 (view only); none of them has a 사업자 등록 / 가맹점 등록 entry', async () => {
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회'·'사업자 조회' → '가맹점'·'사업자'. 등록 화면이 탭이 아니게 되어 등록 진입 버튼도 없음을 함께 본다.
    for (const [role, tabs] of [['VIEWER', ['가맹점']], ['APPROVER', ['가맹점', '사업자']], ['SECURITY_ADMIN', ['가맹점']]] as const) {
      const view = signedIn([role])
      await openMerchants()
      expect(screen.queryByRole('tab', { name: '사업자 등록' })).toBeNull()
      expect(screen.queryByRole('tab', { name: '가맹점 등록' })).toBeNull()
      expect(screen.queryByRole('button', { name: '＋ 가맹점 등록' })).toBeNull()
      expect(screen.queryByRole('button', { name: '＋ 사업자 등록' })).toBeNull()
      expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([...tabs])
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
     * MC-2b(D-MCODE-003 ① — C-28 완결): MC-2가 마법사 Site·계약 초안 "가맹점 ID" 입력칸에 남겨 둔 6곳(MC2B_ALLOWED)은 없어졌다 — 허용 목록은 비었다.
     * DOM 전체(텍스트 노드·모든 속성·입력 value)에서 mer_가 나오는 곳 목록은 어느 단계·오류 상태에서도 빈 목록이다(목록에 하나라도 있으면 RED).
     */
    const MC2B_ALLOWED: readonly string[] = []
    const merOccurrences = (root: HTMLElement) => domOccurrences(root, 'mer_').filter((place) => !MC2B_ALLOWED.includes(place))

    it('MC-2-3/MC-2b DOM (C-28 + D-MCODE-003 ①): in the whole DOM (text, every attribute, input values) mer_ appears nowhere — ② before/after, ③, ④ — and the allow-list is empty', async () => {
      const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)), registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={withBusiness()} />)
      expect(MC2B_ALLOWED).toEqual([])
      expect(domOccurrences(container, 'mer_')).toEqual([])
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await next()
      // ③: 계약 초안 소속 가맹점은 상호·코드(내부 키 없음).
      await screen.findByRole('region', { name: '계약 초안' })
      expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      expect(domOccurrences(container, 'mer_')).toEqual([])
      // ④ 검토: 가맹점·Site 행 모두 코드.
      await goTo('4. 검토·완료')
      expect(step('등록 결과')).toHaveTextContent(`가맹점 코드 ${CODE}`)
      expect(domOccurrences(container, 'mer_')).toEqual([])
      // ② 결과: 가맹점 카드 결과는 코드, Site 카드 소속 가맹점도 상호·코드.
      await goTo('2. 가맹점·사이트')
      expect(step('가맹점')).toHaveTextContent(`가맹점 코드 ${CODE}`)
      expectAffiliation('Site', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      expect(merOccurrences(container)).toEqual([])
      expect(domOccurrences(container, 'mer_')).toEqual([])
    })

    it('MC-2b DOM: the "no affiliated merchant" errors, a locked (unknown) Site and a context merchant render no mer_ either', async () => {
      const api = fakeApi({ registerMerchantSite: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }), registerContract: vi.fn(async () => rejected('MERCHANT_NOT_FOUND')) })
      const empty = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await click(step('Site'), 'Site 등록')
      expect(within(step('Site')).getByText('가맹점을 먼저 등록하세요(가맹점 카드).')).toBeInTheDocument()
      expect(domOccurrences(empty.container, 'mer_')).toEqual([])
      await goTo('3. 계약 초안·서비스 계약')
      await click(step('계약 초안'), '계약 초안 등록')
      expect(within(step('계약 초안')).getByText('가맹점을 먼저 등록하세요(2단계 가맹점 카드).')).toBeInTheDocument()
      expect(domOccurrences(empty.container, 'mer_')).toEqual([])
      expect(api.registerMerchantSite).not.toHaveBeenCalled()
      expect(api.registerContract).not.toHaveBeenCalled()
      empty.unmount()

      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withMerchant()} />)
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await click(step('Site'), 'Site 등록')
      await within(step('Site')).findByRole('group', { name: '결과 확인 필요' })
      expectAffiliation('Site', CONTEXT_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      expect(domOccurrences(container, 'mer_')).toEqual([])
      await goTo('3. 계약 초안·서비스 계약')
      await typeIn(step('계약 초안'), '시작일', '2026-11-09')
      await click(step('계약 초안'), '계약 초안 등록')
      expect(await within(step('계약 초안')).findByRole('alert')).toHaveTextContent('가맹점을 찾을 수 없습니다')
      expect(domOccurrences(container, 'mer_')).toEqual([])
      expect(api.registerContract).toHaveBeenCalledWith({ submissionId: sid(2), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-09' })
    })
  })

  describe('MERCHANT-CODE-001 MC-2b affiliated merchant (D-MCODE-003 ① — no merchant-ID input) and business display (② — no biz_, hyphenated number — D-BIZ-007)', () => {
    it('MC-2b-1 Site and 계약 초안 have no merchant-ID field; after the merchant card succeeds both show "소속 가맹점: <상호> · <코드>" and the bodies carry the internal key unchanged', async () => {
      const api = fakeApi({
        registerMerchant: vi.fn(async () => succeeded(MER)),
        registerMerchantSite: vi.fn(async () => succeeded(SITE)),
        registerContract: vi.fn(async () => succeeded(CTR)),
      })
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
      expect(within(step('Site')).queryByLabelText('가맹점 ID')).toBeNull()
      expect(within(step('Site')).queryByRole('textbox', { name: /가맹점/ })).toBeNull()
      expectAffiliation('Site', '아직 없음') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await click(step('가맹점'), '가맹점 등록')
      await within(step('가맹점')).findByText(CODE)
      expectAffiliation('Site', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await next()
      await screen.findByRole('region', { name: '계약 초안' })
      expect(within(step('계약 초안')).queryByLabelText('가맹점 ID')).toBeNull()
      expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await typeIn(step('계약 초안'), '시작일', '2026-11-09')
      await click(step('계약 초안'), '계약 초안 등록')
      await within(step('계약 초안')).findByText(CTR)
      // API 불변: 저장 본문은 내부 키(mer_) 그대로 — 화면의 코드가 본문에 들어가지 않는다.
      expect(api.registerMerchantSite).toHaveBeenCalledWith({ submissionId: sid(2), merchantId: MER, channelType: 'WEB', channelIdentifier: 'demo-shop2.paycore.test' })
      expect(api.registerContract).toHaveBeenCalledWith({ submissionId: sid(3), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-09' })
    })

    it('MC-2b-2 a done Site keeps its merchant when another merchant is registered, and "다른 Site 추가 입력" takes the current wizard merchant; "계약 초안 새로 입력" does the same', async () => {
      const MER2 = id('mer', 8)
      const api = fakeApi({
        registerMerchant: vi.fn().mockResolvedValueOnce(succeeded(MER)).mockResolvedValueOnce(succeeded(MER2)),
        registerMerchantSite: vi.fn(async () => succeeded(SITE)),
        registerContract: vi.fn(async () => succeeded(CTR)),
      })
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={withBusiness()} />)
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await next()
      await typeIn(step('계약 초안'), '시작일', '2026-11-09')
      await click(step('계약 초안'), '계약 초안 등록')
      await within(step('계약 초안')).findByText(CTR)
      await goTo('2. 가맹점·사이트')
      await click(step('가맹점'), '가맹점 새로 입력')
      await typeMerchant(step('가맹점'), '둘째 가맹점', 'secondshop2')
      await click(step('가맹점'), '가맹점 등록')
      await within(step('가맹점')).findByText('secondshop2')
      // 완료된 Site는 보낸 가맹점 그대로(읽기 전용 완료 — C-32).
      expectAffiliation('Site', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await click(step('Site'), '다른 Site 추가 입력')
      expectAffiliation('Site', '둘째 가맹점 · secondshop2') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await goTo('3. 계약 초안·서비스 계약')
      expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await click(step('계약 초안'), '계약 초안 새로 입력')
      expectAffiliation('계약 초안', '둘째 가맹점 · secondshop2') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
    })

    it('MC-2b-4 (review MINOR-2 — screen guard, MASTER still decides): when the contract party business differs from the business of the affiliated merchant, the 계약 초안 shows an error and is not sent', async () => {
      const BIZ2 = id('biz', 8)
      const api = fakeApi({
        lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ2, '다른 사업자')),
        registerMerchant: vi.fn(async () => succeeded(MER)),
        registerContract: vi.fn(async () => succeeded(CTR)),
      })
      const memory = withBusiness()
      render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} memory={memory} />)
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await click(step('가맹점'), '가맹점 등록')
      await within(step('가맹점')).findByText(CODE)
      // ①에서 다른 사업자를 고른다 → 잠기지 않은 계약 초안의 당사자는 다른 사업자, 소속 가맹점은 그대로 MER(사업자 BIZ).
      await goTo('1. 사업자 선택')
      await pickBusiness()
      await goTo('3. 계약 초안·서비스 계약')
      expect(partyText()).toContain('다른 사업자')
      expectAffiliation('계약 초안', OWN_AFFILIATION) // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await typeIn(step('계약 초안'), '시작일', '2026-11-09')
      await click(step('계약 초안'), '계약 초안 등록')
      const message = '계약 당사자와 소속 가맹점의 사업자가 다릅니다 — 소속 가맹점의 사업자를 1단계에서 다시 선택하세요.'
      expect(within(step('계약 초안')).getByText(message)).toBeInTheDocument()
      expect(within(step('계약 초안')).getByRole('group', { name: '계약 당사자' })).toHaveAccessibleDescription(message)
      expect(api.registerContract).not.toHaveBeenCalled()
      // 같은 사업자를 다시 고르면 보낸다(본문은 내부 키 그대로).
      act(() => offerBusiness(memory, BUSINESS))
      expect(partyText()).toContain('데모 사업자')
      await click(step('계약 초안'), '계약 초안 등록')
      await within(step('계약 초안')).findByText(CTR)
      expect(api.registerContract).toHaveBeenCalledWith({ submissionId: sid(2), partyBusinessId: BIZ, scopeMerchantId: MER, scopeSiteIds: [], effectiveFromDate: '2026-11-09' })
    })

    it('MC-2b-5 (review NIT-3): an internal merchant key without a remembered name/code is shown as "아직 없음" (never the key) and is not sent', async () => {
      const api = fakeApi({ registerMerchantSite: vi.fn(async () => succeeded(SITE)) })
      const memory = withBusiness()
      memory.write('siteMerchantId', MER)
      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} memory={memory} />)
      expectAffiliation('Site', '아직 없음') // LAYOUT-ROLLOUT B2 기대값 변경(사용자 결정 2026-11-19 폼 줄 — '소속 가맹점: ' 접두는 이름 열로 표현, D-MCODE-003 내용 무변경)
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await click(step('Site'), 'Site 등록')
      expect(within(step('Site')).getByText('가맹점을 먼저 등록하세요(가맹점 카드).')).toBeInTheDocument()
      expect(domOccurrences(container, 'mer_')).toEqual([])
      expect(api.registerMerchantSite).not.toHaveBeenCalled()
    })

    it('MC-2b-3 business (② — D-MCODE-003): the wizard shows "<상호> · <마스킹 번호>" for the business and no biz_ anywhere in the DOM at any step; the plaintext number never appears', async () => {
      const api = fakeApi({
        lookupBusinessByRegistrationNumber: vi.fn(async () => lookupOf(BIZ)),
        registerMerchant: vi.fn(async () => succeeded(MER)),
        registerMerchantSite: vi.fn(async () => succeeded(SITE)),
        registerContract: vi.fn(async () => succeeded(CTR)),
        addServiceAgreement: vi.fn(async () => succeeded(CSA)),
      })
      const { container } = render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} newSubmissionId={ids()} todayKst={() => '2026-11-08'} />)
      const clean = () => {
        expect(domOccurrences(container, 'biz_')).toEqual([])
        expect(domOccurrences(container, BRN)).toEqual([])
        expect(domOccurrences(container, '778-12-34560')).toEqual([])
      }
      clean()
      await typeIn(step('사업자 선택'), '사업자등록번호', BRN)
      await click(step('사업자 선택'), '찾기')
      const found = await within(step('사업자 선택')).findByRole('list', { name: '찾은 사업자' })
      expect(found).toHaveTextContent('데모 사업자 · 778-12-34567')
      clean()
      await user.click(within(found).getByRole('button', { name: '선택' }))
      expect(merchantBusinessText()).toContain('데모 사업자 · 778-12-34567')
      clean()
      await typeMerchant(step('가맹점'), '데모 가맹점')
      await typeIn(step('Site'), '채널 식별값', 'demo-shop2.paycore.test')
      await next()
      await screen.findByRole('region', { name: '계약 초안' })
      expect(partyText()).toContain('데모 사업자 · 778-12-34567')
      clean()
      await typeIn(step('계약 초안'), '시작일', '2026-11-09')
      await next()
      await screen.findByRole('region', { name: '등록 결과' })
      expect(step('등록 결과')).toHaveTextContent('데모 사업자 · 778-12-34567')
      clean()
      // 내부 키는 본문에만.
      expect(api.registerMerchant).toHaveBeenCalledWith(expect.objectContaining({ businessId: BIZ }))
      expect(api.registerContract).toHaveBeenCalledWith(expect.objectContaining({ partyBusinessId: BIZ }))
    })
  })
})
