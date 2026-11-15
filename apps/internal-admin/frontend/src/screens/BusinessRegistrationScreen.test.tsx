import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessRegistrationScreen } from './BusinessRegistrationScreen'
import type { RegistrationOutcome } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { ERROR_MESSAGES } from '../errors/messages'
import { domOccurrences } from '../test/domScan'

/**
 * 사업자 등록 화면(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ④: 기존 마법사 ① 사업자 카드를 화면으로 분리).
 * 옮긴 단언(ADMIN-UI-MERCHANT-001 MerchantRegistrationScreen.test): 보내는 body·submissionId·결과 불명 잠금·같은 submissionId 재전송·중복 문구·마스킹·저장소 없음·감사 불가 문구·세션 종료.
 * 바뀐 단언: 사업자 ID(biz_…)는 화면에 보이지 않는다(D-ADMIN-UI-004 ③ — 반대 단언), 사업자등록번호는 하이픈 자동 표시(보내는 값은 숫자 10자리 그대로),
 * 형식 오류 문구에서 "(하이픈 없이)" 제거(하이픈은 화면이 넣는다 — 검사 자체는 그대로 숫자 10자리).
 */

const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const BIZ = id('biz', 7)
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). 화면에 평문으로 남으면 안 된다. */
const BRN = '7781234560'
const BRN_SHOWN = '778-12-34560'
const succeeded = (reference: string, replayed = false): RegistrationOutcome => ({ outcome: 'SUCCEEDED', reference, replayed })
const rejected = (rejection: string, replayed = false): RegistrationOutcome => ({ outcome: 'REJECTED', rejection, replayed })

function ids() {
  let n = 0
  return () => (++n).toString(16).padStart(32, '0')
}
const sid = (n: number) => n.toString(16).padStart(32, '0')

const user = userEvent.setup({ delay: null })
const card = () => screen.getByRole('region', { name: '사업자' })
const paste = async (label: string, value: string) => {
  await user.click(within(card()).getByLabelText(label))
  await user.paste(value)
}
async function fill(number = BRN) {
  await paste('사업자등록번호', number)
  await paste('사업자명', '데모 사업자')
}
const submit = async () => user.click(within(card()).getByRole('button', { name: '사업자 등록' }))

describe('BusinessRegistrationScreen (ADMIN-UI-MERCHANT-002 — business only; MERCHANT-OPS-002 §5 B1 save path unchanged)', () => {
  it('sends the same body as before (digits only), shows name + masked number but never the business id, and hands the business to 가맹점 등록', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => succeeded(BIZ)) })
    const onContinue = vi.fn()
    const { container } = render(<BusinessRegistrationScreen api={api} onContinue={onContinue} newSubmissionId={ids()} />)
    expect(screen.getByRole('heading', { name: '사업자 등록' })).toBeInTheDocument()
    await fill()
    expect(within(card()).getByLabelText('사업자등록번호')).toHaveValue(BRN_SHOWN)
    await submit()
    expect(api.registerBusiness).toHaveBeenCalledTimes(1)
    expect(api.registerBusiness).toHaveBeenCalledWith({ submissionId: sid(1), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(await within(card()).findByText('778*******')).toBeInTheDocument()
    expect(within(card()).getByText('데모 사업자')).toBeInTheDocument()
    // D-ADMIN-UI-004 ③: 사업자 ID는 화면에 없다(내부 키는 다음 화면 호출 본문에만).
    expect(container.textContent).not.toMatch(/biz_/)
    expect(container.innerHTML).not.toContain(BIZ)
    // 평문 번호는 하이픈 표시 형태로도 남지 않는다.
    expect(within(card()).getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.innerHTML).not.toContain(BRN)
    expect(container.innerHTML).not.toContain(BRN_SHOWN)
    expect(within(card()).getByLabelText('사업자명')).toBeDisabled()
    expect(within(card()).queryByRole('button', { name: '사업자 등록' })).toBeNull()

    await user.click(screen.getByRole('button', { name: '이 사업자로 가맹점 등록' }))
    expect(onContinue).toHaveBeenCalledWith({ businessId: BIZ, name: '데모 사업자', masked: '778*******' })
    expect(storageIsEmpty()).toBe(true)
  })

  it('MC-2b DOM (D-MCODE-003 ②): after success the whole DOM (text, attributes, input values) has no biz_ and no plaintext number; the business shows as "<상호> · <마스킹 번호>"', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => succeeded(BIZ)) })
    const { container } = render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} newSubmissionId={ids()} />)
    await fill()
    await submit()
    await within(card()).findByText('778*******')
    expect(screen.getByText('데모 사업자 · 778*******에 가맹점을 등록하려면', { exact: false })).toBeInTheDocument()
    expect(domOccurrences(container, 'biz_')).toEqual([])
    expect(domOccurrences(container, BRN)).toEqual([])
    expect(domOccurrences(container, BRN_SHOWN)).toEqual([])
  })

  it('the registration-number field keeps autocomplete off, is not a password field and is numeric', () => {
    render(<BusinessRegistrationScreen api={fakeApi()} onContinue={vi.fn()} newSubmissionId={ids()} />)
    const field = within(card()).getByLabelText('사업자등록번호')
    expect(field).toHaveAttribute('autocomplete', 'off')
    expect(field).not.toHaveAttribute('type', 'password')
    expect(field).toHaveAttribute('inputmode', 'numeric')
  })

  it('formats are checked on screen without sending: too few digits (typed hyphens are ignored) and an over-long number are rejected', async () => {
    const api = fakeApi()
    render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} newSubmissionId={ids()} />)
    await fill('778-12-345')
    await submit()
    expect(within(card()).getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    await user.clear(within(card()).getByLabelText('사업자등록번호'))
    await paste('사업자등록번호', '778-12-345601')
    await submit()
    expect(within(card()).getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    expect(api.registerBusiness).not.toHaveBeenCalled()
  })

  it('a duplicate registration number shows the fixed sentence only (no business id or link); a replayed decision says so', async () => {
    const api = fakeApi({ registerBusiness: vi.fn().mockResolvedValueOnce(rejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE')).mockResolvedValueOnce(rejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE', true)) })
    render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} newSubmissionId={ids()} />)
    await fill()
    await submit()
    const alert = await within(card()).findByRole('alert')
    expect(alert).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    expect(alert.textContent).not.toMatch(/biz_/)
    expect(within(card()).queryAllByRole('link')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: '이 사업자로 가맹점 등록' })).toBeNull()
    await paste('사업자등록번호', BRN)
    await submit()
    expect(await within(card()).findByText(/이미 등록된 사업자등록번호입니다 \(이전에 처리된 결과입니다\)/)).toBeInTheDocument()
  })

  it('§5.3: an unknown outcome locks the form and offers only "같은 내용 다시 보내기" with the same submissionId; a decided answer gives the read-only completion and "사업자 새로 입력" → new submissionId', async () => {
    const BIZ2 = id('biz', 8)
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_RETRY_SAME_KEY'))
      .mockResolvedValueOnce(succeeded(BIZ, true))
      .mockResolvedValueOnce(succeeded(BIZ2))
    const api = fakeApi({ registerBusiness: send })
    render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} newSubmissionId={ids()} />)
    await fill()
    await submit()
    const group = await within(card()).findByRole('group', { name: '결과 확인 필요' })
    expect(within(card()).getByLabelText('사업자명')).toBeDisabled()
    expect(within(card()).getByLabelText('사업자등록번호')).toBeDisabled()
    expect(within(card()).getByRole('button', { name: '사업자 등록' })).toBeDisabled()
    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    await within(card()).findByRole('group', { name: '결과 확인 필요' })
    await user.click(within(card()).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(await within(card()).findByText('778*******')).toBeInTheDocument()
    expect(within(card()).getByText(/이전에 처리된 결과입니다/)).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(3)
    expect(new Set(send.mock.calls.map(([input]) => JSON.stringify(input))).size).toBe(1)
    expect(send.mock.calls[0]![0]).toEqual({ submissionId: sid(1), registrationNumber: BRN, displayName: '데모 사업자' })

    await user.click(within(card()).getByRole('button', { name: '사업자 새로 입력' }))
    expect(within(card()).getByLabelText('사업자명')).toBeEnabled()
    expect(screen.queryByRole('button', { name: '이 사업자로 가맹점 등록' })).toBeNull()
    await fill()
    await submit()
    expect(await screen.findByRole('button', { name: '이 사업자로 가맹점 등록' })).toBeInTheDocument()
    expect(send.mock.calls[3]![0]).toEqual({ submissionId: sid(2), registrationNumber: BRN, displayName: '데모 사업자' })
    expect(storageIsEmpty()).toBe(true)
  })

  it('review m-2: AUDIT_UNAVAILABLE on registration says the result is uncertain (the lookup wording stays as is)', async () => {
    const api = fakeApi({ registerBusiness: vi.fn(async () => { throw apiError(503, 'AUDIT_UNAVAILABLE') }) })
    render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} newSubmissionId={ids()} />)
    await fill()
    await submit()
    const alert = within(await within(card()).findByRole('group', { name: '결과 확인 필요' })).getByRole('alert')
    expect(alert).toHaveTextContent('감사 기록을 남기지 못했습니다. 처리 여부가 확실하지 않으니 가맹점 조회로 확인하거나 같은 내용으로 다시 보내세요.')
    expect(ERROR_MESSAGES.AUDIT_UNAVAILABLE).toBe('감사 기록을 남길 수 없어 조회하지 않았습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('a session-ending answer hands control back to the app', async () => {
    const onSessionEnded = vi.fn()
    const api = fakeApi({ registerBusiness: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }) })
    render(<BusinessRegistrationScreen api={api} onContinue={vi.fn()} onSessionEnded={onSessionEnded} newSubmissionId={ids()} />)
    await fill()
    await submit()
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
  })
})
