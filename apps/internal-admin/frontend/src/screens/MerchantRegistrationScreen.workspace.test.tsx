import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { MerchantRegistrationScreen, offerBusiness } from './MerchantRegistrationScreen'
import { RegistrationStep } from '../components/RegistrationStep'
import type { AdminApi, RegistrationOutcome } from '../api/adminApi'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { createScreenMemory } from '../lib/screenMemory'
import type { BusinessRef } from '../lib/registration'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 사업자·가맹점 › 가맹점 등록("모양만"): 참고 시안 사업자 등록 wizard(.wizard 단계 띠 · 카드 제목 줄 · .detailgrid 검토 · .notice · .actions)를
 * 실제 4단계 가맹점 등록 마법사에 입힌다. 동작(저장 호출·submissionId·결과 불명 잠금·자동 채움·문구)은 MerchantRegistrationScreen.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture(업무 의미 없는 고정 식별자): 등록 완료 사업자 1곳, 가맹점 1곳(코드 shopabcd12).
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const BIZ = id('biz', 7)
const MER = id('mer', 7)
const BRN = '7781234560'
const BUSINESS: BusinessRef = { businessId: BIZ, name: '데모 사업자', masked: '778*******' }
const succeeded = (reference: string): RegistrationOutcome => ({ outcome: 'SUCCEEDED', reference, replayed: false })
const lookup = { items: [{ businessId: BIZ, businessName: '데모 사업자', registrationNumberMasked: '778*******', registrationStatus: 'REGISTERED' as const }] }
const user = userEvent.setup({ delay: null })
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))
const region = (name: string) => screen.getByRole('region', { name })
const paste = async (scope: HTMLElement, label: string, value: string) => {
  await user.click(within(scope).getByLabelText(label))
  await user.paste(value)
}
const withBusiness = () => {
  const memory = createScreenMemory()
  offerBusiness(memory, BUSINESS)
  return memory
}
const stepItems = () => within(screen.getByRole('list', { name: '등록 단계' })).getAllByRole('listitem')

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  render(<App api={api} />)
  return api
}

describe('ADMIN-UI-WORKSPACE-001: 사업자·가맹점 › 가맹점 등록 wizard in the reference layout', { timeout: 20_000 }, () => {
  it('flush in the menu card: .mapnote(h2) → .wizard(step buttons) → ① 사업자 선택 section(.cardtop h3 + .cardbody) → .cardnote(안내 + .actions)', async () => {
    signedIn({ lookupBusinessByRegistrationNumber: vi.fn(async () => lookup) })
    await user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
    await user.click(screen.getByRole('tab', { name: '가맹점 등록' }))
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    expect(shape(panel)).toEqual(['div.mapnote', 'div.wizard', 'section', 'div.cardnote'])
    const mapnote = panel.querySelector('.mapnote') as HTMLElement
    expect(within(mapnote).getByRole('heading', { level: 2, name: '가맹점 등록' })).toBeInTheDocument()
    expect(mapnote).toHaveTextContent('등록된 사업자를 골라 가맹점·사이트·계약 초안을 등록합니다. 계약 승인·첫 MID 발급은 가맹점 조회 상세에서 요청합니다(2인 승인).')

    // 단계 띠: 시안 .wizard(span 4개 — 현재 .current). span 안 단계 버튼(이동 기능·aria-label·aria-current 그대로), 보이는 글자 = 이름.
    const wizard = panel.querySelector('.wizard')!
    expect(wizard).toHaveAccessibleName('등록 단계')
    expect(shape(wizard)).toEqual(['span.current', 'span', 'span', 'span'])
    expect(stepItems().map((s) => s.textContent)).toEqual(['1. 사업자 선택', '2. 가맹점·사이트', '3. 계약 초안·서비스 계약', '4. 검토·완료'])
    expect(stepItems().map((s) => Array.from(s.children).map((c) => c.tagName.toLowerCase()))).toEqual(Array(4).fill(['button']))
    expect(within(stepItems()[0]!).getByRole('button')).toHaveAttribute('aria-current', 'step')
    // reviewFix1 캡처(b)·NIT-1: 현재 단계 tint(MINOR-6 class 그대로)가 글자 폭이 아니라 단계 칸 전체 — button block·w-full, 누르는 칸 py-1.
    expect(within(stepItems()[0]!).getByRole('button')).toHaveClass('block', 'w-full', 'py-1', 'text-left', 'bg-primary/5', 'text-chip-info-fg')
    expect(within(stepItems()[1]!).getByRole('button')).toHaveClass('block', 'w-full', 'py-1', 'text-left')
    expect(within(stepItems()[1]!).getByRole('button')).not.toHaveClass('bg-primary/5')

    // ① 사업자 선택: section(region 이름 그대로) > .cardtop h3 + .cardbody. 찾기는 보조 버튼 .btn.
    const picker = region('사업자 선택')
    expect(shape(picker)).toEqual(['div.cardtop', 'div.cardbody'])
    expect(picker.querySelector(':scope > .cardtop > h3')).toHaveTextContent('사업자 선택')
    expect(within(picker).getByRole('button', { name: '찾기' })).toHaveClass('btn')
    expect(within(picker).getByRole('button', { name: '찾기' })).not.toHaveClass('primary')

    // 아래 줄: 안내 p.subtle + 시안 .actions(다음 단계 → .btn.primary). ①에는 이전이 없다(기존 그대로).
    const bottom = panel.querySelector(':scope > .cardnote')!
    expect(bottom.querySelector(':scope > p.subtle')).toHaveTextContent('단계 표시를 누르면 저장하지 않고 이동합니다.')
    const actions = bottom.querySelector(':scope > .actions') as HTMLElement
    expect(within(actions).getAllByRole('button').map((b) => b.textContent)).toEqual(['다음 단계 →'])
    expect(within(actions).getByRole('button', { name: '다음 단계 →' })).toHaveClass('btn', 'primary')

    await paste(picker, '사업자등록번호', BRN)
    await user.click(within(picker).getByRole('button', { name: '찾기' }))
    const found = await within(picker).findByRole('list', { name: '찾은 사업자' })
    expect(within(found).getByRole('button', { name: '선택' })).toHaveClass('btn', 'primary')
    await user.click(within(found).getByRole('button', { name: '선택' }))

    // ②: 두 카드(가맹점·Site) 모두 section > .cardtop h3 + .cardbody, 등록 버튼 .btn.primary. 끝난 ①은 기존 MINOR-6 info 글자 token.
    expect(shape(panel)).toEqual(['div.mapnote', 'div.wizard', 'section', 'section', 'div.cardnote'])
    for (const name of ['가맹점', 'Site']) {
      expect(shape(region(name))).toEqual(['div.cardtop', 'div.cardbody'])
      expect(region(name).querySelector(':scope > .cardtop > h3')).toHaveTextContent(name)
    }
    expect(within(region('가맹점')).getByRole('button', { name: '가맹점 등록' })).toHaveClass('btn', 'primary')
    expect(within(region('가맹점')).getByRole('button', { name: '다시 제안' })).toHaveClass('btn')
    expect(within(region('Site')).getByRole('button', { name: 'Site 등록' })).toHaveClass('btn', 'primary')
    expect(stepItems()[0]).toHaveClass('border-primary', 'text-chip-info-fg')
    expect(stepItems()[1]).toHaveClass('current')
    // reviewFix1 MINOR-1(WCAG 1.4.1): 끝난 단계는 색만이 아니라 기존 체크 아이콘(aria-hidden, size-3.5)을 이름 앞에 — 접근 가능한 이름·글자 그대로.
    const doneButton = within(stepItems()[0]!).getByRole('button')
    expect(doneButton.firstElementChild?.tagName.toLowerCase()).toBe('svg')
    expect(doneButton.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(doneButton.firstElementChild).toHaveClass('size-3.5')
    expect(doneButton).toHaveTextContent('1. 사업자 선택')
    expect(stepItems().slice(1).map((s) => s.querySelector('svg'))).toEqual([null, null, null])
    expect(within(actions).getAllByRole('button').map((b) => b.textContent)).toEqual(['이전', '다음 단계 →'])
    expect(within(actions).getByRole('button', { name: '이전' })).toHaveClass('btn')
    expect(within(actions).getByRole('button', { name: '이전' })).not.toHaveClass('primary')

    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, BRN)).toEqual([])
  })

  it('visible busy cues: 찾기 shows "찾는 중…", a card submit shows "보내는 중…" (polite live text; button names unchanged); success → reset button .btn', async () => {
    let finishLookup: (v: typeof lookup) => void = () => {}
    let finishMerchant: (v: RegistrationOutcome) => void = () => {}
    const api = fakeApi({
      lookupBusinessByRegistrationNumber: vi.fn(() => new Promise<typeof lookup>((resolve) => { finishLookup = resolve })),
      registerMerchant: vi.fn(() => new Promise<RegistrationOutcome>((resolve) => { finishMerchant = resolve })),
    })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} />)
    const picker = region('사업자 선택')
    const pickLive = within(picker).getByRole('button', { name: '찾기' }).parentElement!.querySelector(':scope > span.subtle')!
    // reviewFix1 캡처(a): 찾기 버튼은 입력란과 같은 줄(items-end — 입력 아래끝 맞춤), 도움말·형식 오류는 그 줄 아래(aria-describedby 그대로).
    const row = within(picker).getByRole('button', { name: '찾기' }).parentElement!
    const brnInput = within(picker).getByLabelText('사업자등록번호')
    expect(row).toHaveClass('items-end')
    expect(row.contains(brnInput)).toBe(true)
    expect(brnInput).toHaveAccessibleDescription('조회는 감사 기록에 남습니다.')
    expect(row.contains(document.getElementById(brnInput.getAttribute('aria-describedby')!))).toBe(false)
    expect(pickLive).toHaveAttribute('aria-live', 'polite')
    expect(pickLive).toBeEmptyDOMElement()
    await paste(picker, '사업자등록번호', BRN)
    await user.click(within(picker).getByRole('button', { name: '찾기' }))
    expect(pickLive).toHaveTextContent('찾는 중…')
    expect(within(picker).getByRole('button', { name: '찾기' })).toHaveAttribute('aria-busy', 'true')
    finishLookup(lookup)
    await user.click(await within(picker).findByRole('button', { name: '선택' }))
    expect(pickLive.isConnected).toBe(false)

    const merchant = region('가맹점')
    await paste(merchant, '가맹점명', '데모 가맹점')
    await paste(merchant, '가맹점 코드', 'shopabcd12')
    const submit = within(merchant).getByRole('button', { name: '가맹점 등록' })
    const live = submit.parentElement!.querySelector(':scope > span.subtle')!
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live).toBeEmptyDOMElement()
    await user.click(submit)
    expect(live).toHaveTextContent('보내는 중…')
    expect(within(merchant).getByRole('button', { name: '가맹점 등록' })).toHaveAttribute('aria-busy', 'true')
    finishMerchant(succeeded(MER))
    expect(await within(merchant).findByRole('button', { name: '가맹점 새로 입력' })).toHaveClass('btn')
    expect(within(merchant).getByRole('button', { name: '가맹점 새로 입력' })).not.toHaveClass('primary')
    expect(within(merchant).getByRole('status')).toHaveTextContent('shopabcd12')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('result unknown: 같은 내용 다시 보내기 .btn.primary · 취소하고 새로 입력 .btn · 그래도 새로 입력 = existing destructive-solid tokens, all inside the card body', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} memory={withBusiness()} />)
    const merchant = region('가맹점')
    await paste(merchant, '가맹점명', '데모 가맹점')
    await paste(merchant, '가맹점 코드', 'shopabcd12')
    await user.click(within(merchant).getByRole('button', { name: '가맹점 등록' }))
    const group = await within(merchant).findByRole('group', { name: '결과 확인 필요' })
    expect(group.closest('.cardbody')).toBe(merchant.querySelector(':scope > .cardbody'))
    expect(within(group).getByRole('button', { name: '같은 내용 다시 보내기' })).toHaveClass('btn', 'primary')
    expect(within(group).getByRole('button', { name: '취소하고 새로 입력' })).toHaveClass('btn')
    expect(within(group).getByRole('button', { name: '취소하고 새로 입력' })).not.toHaveClass('primary')
    await user.click(within(group).getByRole('button', { name: '취소하고 새로 입력' }))
    expect(within(group).getByRole('button', { name: '그래도 새로 입력' })).toHaveClass('btn', 'border-destructive-solid', 'bg-destructive-solid', 'text-white')
  })

  it('④ 검토: section > .cardtop h3 + .cardbody(dl.detailgrid div > dt + dd · .notice 다음 단계 · 버튼 .btn/.btn.primary)', async () => {
    const api = fakeApi({ registerMerchant: vi.fn(async () => succeeded(MER)) })
    render(<MerchantRegistrationScreen api={api} onOpenLookup={vi.fn()} memory={withBusiness()} />)
    const merchant = region('가맹점')
    await paste(merchant, '가맹점명', '데모 가맹점')
    await paste(merchant, '가맹점 코드', 'shopabcd12')
    await user.click(within(merchant).getByRole('button', { name: '가맹점 등록' }))
    await within(merchant).findByRole('button', { name: '가맹점 새로 입력' })
    await user.click(within(screen.getByRole('list', { name: '등록 단계' })).getByRole('button', { name: '4. 검토·완료' }))

    const review = region('등록 결과')
    expect(shape(review)).toEqual(['div.cardtop', 'div.cardbody'])
    expect(review.querySelector(':scope > .cardtop > h3')).toHaveTextContent('검토 — 저장된 내용')
    const body = review.querySelector(':scope > .cardbody')!
    const grid = body.querySelector(':scope > dl.detailgrid')!
    expect(Array.from(grid.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.children).map((x) => x.tagName.toLowerCase())].join(' '))).toEqual(Array(5).fill('div dt dd'))
    expect(Array.from(grid.querySelectorAll('dt')).map((t) => t.textContent)).toEqual(['사업자', '가맹점', 'Site', `계약 초안(Revision 1, ${REVISION_AWAITING_APPROVAL})`, '서비스 계약'])
    expect(grid.querySelectorAll('dd')[1]).toHaveTextContent('데모 가맹점가맹점 코드 shopabcd12')
    expect(grid.querySelectorAll('dd')[2]).toHaveTextContent('이 마법사에서 저장하지 않음')
    const notice = body.querySelector(':scope > .notice') as HTMLElement
    expect(notice).toHaveTextContent('다음: 가맹점 조회 상세에서 계약 승인 → 첫 MID 발급을 요청하세요.')
    // 대비: 시안 .notice 글자는 그 바탕에서 4.47:1 — 기존 본문 글자 token으로 올린다(새 색 없음).
    expect(notice).toHaveClass('text-foreground')
    expect(within(notice).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['가맹점 조회 상세', '계약 승인 요청 (2인)', '첫 MID 발급 (2인)', '개통'])
    expect(within(review).getByRole('button', { name: '새 가맹점 등록 시작' })).toHaveClass('btn')
    expect(within(review).getByRole('button', { name: '새 가맹점 등록 시작' })).not.toHaveClass('primary')
    expect(within(review).getByRole('button', { name: '가맹점 조회에서 보기' })).toHaveClass('btn', 'primary')
    expect(document.querySelector('.cardnote > p.subtle')).toHaveTextContent('이 마법사는 MID를 발급하지 않습니다.')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('RegistrationStep without the workspace flag keeps the shared card look (계약 Revision 폼 — 가맹점 조회 상세는 이번 범위 밖)', () => {
    const { container } = render(
      <RegistrationStep memory={createScreenMemory()} memoryKey="x" api={{ send: vi.fn() }} newSubmissionId={() => 'a'.repeat(32)} collect={() => null} onSucceeded={vi.fn()} title="계약 Revision" submitLabel="등록">
        {() => null}
      </RegistrationStep>,
    )
    expect(container.querySelector('[data-slot="card"]')).not.toBeNull()
    expect(container.querySelector('.cardtop')).toBeNull()
  })
})
