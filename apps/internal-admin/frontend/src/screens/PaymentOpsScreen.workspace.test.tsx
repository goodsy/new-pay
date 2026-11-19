import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { PaymentOpsScreen } from './PaymentOpsScreen'
import type { AdminApi, PaymentTargetView } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import { labelOf } from '../lib/labels'

/**
 * ADMIN-UI-WORKSPACE-001 결제 관리 › 결제 보정 취소("모양만"): 참고 시안의 카드 안 탭 구성(.mapnote → 카드 제목 줄 → 본문)과
 * 정산 상세의 요청 흐름(.wizard 단계 · .detailgrid 값 · .btn.primary 요청)을 실제 보정 취소 요청 화면에 입힌다.
 * 기능·API 호출·금액 기본값/안전 정수 검사·진행 중 취소/보류 안내·목적/근거/사유 정리·오류 문구는 PaymentOpsScreen.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture(업무 의미 없는 고정 식별자): 승인액 10000 KRW 중 7000 반환 가능, 잔액 revision 3인 AUTHORIZED 결제 1건.
 */
const PAY = 'pay_01JC0000000000000000000001'
const target: PaymentTargetView = {
  paymentId: PAY, paymentStatus: 'AUTHORIZED', cancellable: true, currency: 'KRW', authorizedMinor: 10000, cancellableMinor: 7000, balanceRevision: 3,
  onHold: false, inFlightCancel: false,
}
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '운영자', roles: ['OPERATOR'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  render(<App api={api} />)
  return api
}

async function lookUp() {
  await userEvent.type(screen.getByLabelText('결제 ID'), PAY)
  await userEvent.click(screen.getByRole('button', { name: '대상 조회' }))
}

const steps = () => within(screen.getByRole('list', { name: '진행 단계' })).getAllByRole('listitem')

describe('ADMIN-UI-WORKSPACE-001: 결제 관리 › 결제 보정 취소 in the reference layout', () => {
  it('flush in the menu card: .mapnote(h2) → .wizard → lookup .cardtop/.cardbody(.fieldrow — LAYOUT-ROLLOUT B3) → after lookup .detailgrid and the request .cardtop/.cardbody', async () => {
    const api = signedIn({
      paymentTarget: vi.fn(async () => target),
      requestOperatorCancel: vi.fn(async () => ({ approvalId: 'apr_NEW' })),
    })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '결제 관리' }))
    expect(await screen.findByRole('tab', { name: '결제 보정 취소' })).toHaveAttribute('aria-selected', 'true')
    // 시안 구조로 옮긴 화면은 탭 패널 바로 안에 그린다(안쪽 여백 칸 없음 — MenuCard flush).
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    expect(shape(panel)).toEqual(['div.mapnote', 'div.wizard', 'div.cardtop', 'div.cardbody'])
    expect(within(panel.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '결제 보정 취소' })).toBeInTheDocument()
    expect(panel.querySelector('.mapnote')).toHaveTextContent('사고·대사 보정이 필요한 결제를 조회하고, 다른 관리자의 승인을 거쳐 실행되는 취소를 요청합니다.')

    // 진행 단계: 시안 .wizard(span 3개 — 현재 단계 .current). 목록 의미(list/listitem·aria-current=step)는 그대로.
    const wizard = panel.querySelector('.wizard')!
    expect(wizard).toHaveAccessibleName('진행 단계')
    expect(shape(wizard)).toEqual(['span.current', 'span', 'span'])
    expect(steps().map((s) => s.textContent)).toEqual(['1. 대상 조회', '2. 요청 작성', '3. 승인 대기'])
    expect(steps()[0]).toHaveAttribute('aria-current', 'step')

    // 대상 조회 카드: 제목 줄 h3 + 본문(안내 .subtle · 결제 ID 폼 줄 = 이름 label + 입력 + 버튼 · 도움말 aria-describedby)
    expect(panel.querySelector(':scope > .cardtop > h3')).toHaveTextContent('1. 대상 조회')
    const lookupBody = panel.querySelectorAll(':scope > .cardbody')[0] as HTMLElement
    expect(lookupBody.querySelector(':scope > p.subtle')).toHaveTextContent('조회 값은 참고용이며, 실제 취소 가능 여부는 승인 시점에 결제 서비스가 다시 판정합니다.')
    // LAYOUT-ROLLOUT B3 기대값 변경(사용자 결정 폼 줄): 시안 .rolebar(b + input aria-labelledby + button + span) → FormRow 줄 —
    // 이름 label[for] = 입력 id(글자 그대로), 입력 칸 .fieldcontrol = 입력 + 버튼 + 진행 글자 span(aria-live polite — reviewFix1 MINOR-1 그대로).
    const row = lookupBody.querySelector(':scope > .fieldrow') as HTMLElement
    const bar = row.querySelector(':scope > .fieldcontrol') as HTMLElement
    expect(Array.from(bar.children).map((c) => c.tagName.toLowerCase())).toEqual(['input', 'button', 'span'])
    expect(bar.querySelector(':scope > span')).toHaveAttribute('aria-live', 'polite')
    const input = within(bar).getByLabelText('결제 ID')
    expect(row.querySelector(':scope > label')).toHaveTextContent('결제 ID')
    expect(row.querySelector(':scope > label')!.getAttribute('for')).toBe(input.id)
    expect(input).toHaveAccessibleDescription('결제 ID(pay_…)를 입력하면 조회할 수 있습니다.')
    expect(within(bar).getByRole('button', { name: '대상 조회' })).toHaveClass('btn', 'primary')
    expect(within(bar).getByRole('button', { name: '대상 조회' })).toBeDisabled()

    await lookUp()
    expect(await screen.findByText('반환 가능액: 7000 KRW')).toBeInTheDocument()
    expect(shape(panel)).toEqual(['div.mapnote', 'div.wizard', 'div.cardtop', 'div.cardbody', 'div.cardtop', 'div.cardbody'])
    // 조회한 뒤 조회 버튼은 보조 버튼(.btn), 조회 값은 시안 .detailgrid — reviewFix1 MINOR-2: 정의 목록 dl > div > dt(원래 문장) + dd(읽기 쉬운 값). 기대값 변경(label/strong → dt/dd, 문구 그대로).
    expect(within(bar).getByRole('button', { name: '대상 조회' })).toHaveClass('btn')
    expect(within(bar).getByRole('button', { name: '대상 조회' })).not.toHaveClass('primary')
    const grid = lookupBody.querySelector(':scope > dl.detailgrid')!
    expect(Array.from(grid.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.children).map((x) => x.tagName.toLowerCase())].join(' '))).toEqual(Array(4).fill('div dt dd'))
    expect(Array.from(grid.querySelectorAll('dt')).map((l) => l.textContent)).toEqual(['결제 상태: AUTHORIZED', '원 승인액: 10000 KRW', '반환 가능액: 7000 KRW', '잔액 revision: 3'])
    expect(Array.from(grid.querySelectorAll('dd')).map((l) => l.textContent)).toEqual(['승인 완료', '10,000원', '7,000원', '3'])
    // LAYOUT-ROLLOUT B3 기대값 변경(사용자 결정 폼 줄 — label[for] 연결, 의도 유지·강화; 코디네이터 승인)
    // 원래 의도 "입력과 연결되지 않은 label 없음": 본문 안 모든 label은 입력을 감싸거나, for가 같은 본문 안 input/select/textarea id를 가리킨다. label이 하나 이상 있어야 한다(빈 통과 방지).
    const labels = Array.from(lookupBody.querySelectorAll('label'))
    expect(labels.length).toBeGreaterThan(0)
    for (const label of labels) {
      const wrapped = label.querySelector('input, select, textarea')
      const target = label.htmlFor ? document.getElementById(label.htmlFor) : null
      const linked = target !== null && lookupBody.contains(target) && target.matches('input, select, textarea')
      expect(wrapped !== null || linked, label.outerHTML).toBe(true)
    }
    // 단계: 1은 끝남(기존 MINOR-6 info 글자 token), 2가 현재.
    expect(steps()[0]).not.toHaveAttribute('aria-current')
    expect(steps()[0]).toHaveClass('border-primary', 'text-chip-info-fg')
    expect(steps()[1]).toHaveAttribute('aria-current', 'step')
    expect(steps()[1]).toHaveClass('current')

    // 요청 작성 카드: 제목 줄 h3 + 본문(안내 .subtle · 입력 칸 Field 그대로 · 카드 정보 경고 .subtle · 승인 요청 .btn.primary)
    expect(panel.querySelectorAll(':scope > .cardtop > h3')[1]).toHaveTextContent('2. 요청 작성')
    const requestBody = panel.querySelectorAll(':scope > .cardbody')[1] as HTMLElement
    expect(requestBody.querySelector(':scope > p.subtle')).toHaveTextContent('요청은 다른 승인자가 인증 코드로 승인해야 실행됩니다.')
    expect(within(requestBody).getByText('카드 번호·유효기간 등 카드 정보는 입력하지 마세요.')).toHaveClass('subtle')
    const request = within(requestBody).getByRole('button', { name: '승인 요청' })
    expect(request).toHaveClass('btn', 'primary')
    expect(request).toBeDisabled()
    expect(within(requestBody).getByText('근거 참조와 사유를 입력하면 요청할 수 있습니다.')).toHaveClass('subtle')

    await userEvent.type(within(requestBody).getByLabelText('근거 참조'), 'RECON-2026-0001')
    await userEvent.type(within(requestBody).getByLabelText('사유'), 'duplicate charge')
    await userEvent.click(request)
    expect(api.requestOperatorCancel).toHaveBeenCalledWith({
      paymentId: PAY, amountMinor: 7000, purposeKind: 'INCIDENT_OR_RECON_CORRECTION', purposeReference: 'RECON-2026-0001', reason: 'duplicate charge',
    })
    // 결과 안내는 카드 아래 .cardnote 한 줄(안쪽 여백 23px — 다른 화면의 안내 줄과 같은 자리).
    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('승인 요청을 만들었습니다: apr_NEW')
    expect(status.parentElement).toBe(panel.lastElementChild)
    expect(panel.lastElementChild).toHaveClass('cardnote')
    expect(steps()[2]).toHaveAttribute('aria-current', 'step')

    // 내부 키: 이 화면은 원래 결제 ID(pay_…)를 조회 키로 입력받는다(기존 표시 그대로). 가맹점·사업자·관리자 키는 어디에도 없다(D-ADMIN-UI-004).
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, 'adm_')).toEqual([])
  })

  it('other roles: the target values, then the operator-only note in a .cardnote (no request card)', async () => {
    const { container } = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => target) })} canRequest={false} />)
    await lookUp()
    await screen.findByText('반환 가능액: 7000 KRW')
    expect(shape(container)).toEqual(['div.mapnote', 'div.wizard', 'div.cardtop', 'div.cardbody', 'div.cardnote'])
    expect(container.querySelector('.cardnote > p.subtle')).toHaveTextContent('보정 취소 요청은 운영자 역할만 만들 수 있습니다.')
  })

  it('hold and in-flight notes and a lookup error stay inside the lookup card body', async () => {
    const held = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => ({ ...target, onHold: true, inFlightCancel: true })) })} canRequest />)
    await lookUp()
    const body = (await screen.findByText('진행 중인 취소가 있어 지금은 요청할 수 없습니다.')).closest('.cardbody')!
    expect(body).toBe(held.container.querySelectorAll(':scope > .cardbody')[0])
    expect(within(body as HTMLElement).getByText('보류 중 — 가맹점 취소는 막혀 있고 운영자 보정 취소로만 처리합니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '승인 요청' })).toBeDisabled()
    held.unmount()

    const failing = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => { throw apiError(404, 'NOT_FOUND') }) })} canRequest />)
    await lookUp()
    const alert = await screen.findByRole('alert')
    expect(alert.closest('.cardbody')).toBe(failing.container.querySelectorAll(':scope > .cardbody')[0])
    expect(shape(failing.container)).toEqual(['div.mapnote', 'div.wizard', 'div.cardtop', 'div.cardbody'])
  })

  it('reviewFix1 MINOR-1: a visible busy cue next to 대상 조회 / 승인 요청 while the call is pending (polite live text; button names unchanged)', async () => {
    let finishLookup: (v: PaymentTargetView) => void = () => {}
    let finishRequest: (v: { approvalId: string }) => void = () => {}
    const api = fakeApi({
      paymentTarget: vi.fn(() => new Promise<PaymentTargetView>((resolve) => { finishLookup = resolve })),
      requestOperatorCancel: vi.fn(() => new Promise<{ approvalId: string }>((resolve) => { finishRequest = resolve })),
    })
    const { container } = render(<PaymentOpsScreen api={api} canRequest />)
    // LAYOUT-ROLLOUT B3 기대값 변경(사용자 결정 폼 줄): 진행 글자는 .rolebar 대신 결제 ID 줄의 입력 칸(.fieldcontrol) 안 — '대상 조회' 버튼과 같은 칸.
    const live = container.querySelector('.fieldrow > .fieldcontrol > span.subtle')!
    expect(live.parentElement).toBe(screen.getByRole('button', { name: '대상 조회' }).parentElement)
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live).toBeEmptyDOMElement()
    await lookUp()
    expect(live).toHaveTextContent('조회 중…')
    expect(screen.getByRole('button', { name: '대상 조회' })).toHaveAttribute('aria-busy', 'true')
    finishLookup(target)
    await screen.findByText('반환 가능액: 7000 KRW')
    expect(live).toBeEmptyDOMElement()

    await userEvent.type(screen.getByLabelText('근거 참조'), 'RECON-2026-0001')
    await userEvent.type(screen.getByLabelText('사유'), 'duplicate charge')
    const request = screen.getByRole('button', { name: '승인 요청' })
    const requestLive = request.parentElement!.querySelector(':scope > span.subtle')!
    expect(requestLive).toHaveAttribute('aria-live', 'polite')
    expect(requestLive).toBeEmptyDOMElement()
    await userEvent.click(request)
    expect(requestLive).toHaveTextContent('요청 중…')
    expect(screen.getByRole('button', { name: '승인 요청' })).toHaveAttribute('aria-busy', 'true')
    finishRequest({ approvalId: 'apr_NEW' })
    expect(await screen.findByRole('status')).toHaveTextContent('승인 요청을 만들었습니다: apr_NEW')
    expect(requestLive).toBeEmptyDOMElement()
  })

  it('CSS: the step bar sits under the map note with the card side margin; dl.detailgrid dt/dd take the reference label/value values (adaptation block only, no new value)', () => {
    const block = WORKSPACE_CSS.slice(WORKSPACE_CSS.indexOf('PayCore 적응 규칙'), WORKSPACE_CSS.indexOf('라이트 대비 보정'))
    expect(block).toMatch(/^\.mapnote \+ \.wizard\{margin:0 23px\}$/m)
    // reviewFix1 MINOR-2: 원본 최종값 — 이름 .detailgrid label(13px·500·#475569·아래 8px), 값 .detailgrid strong(14px·600·#0f172a). 다크는 기존 label·strong 다크 값.
    expect(block).toMatch(/^\.detailgrid dt\{margin-bottom:8px;font-size:13px;font-weight:500;color:#475569\}$/m)
    expect(block).toMatch(/^\.detailgrid dd\{margin:0;font-size:14px;font-weight:600;color:#0f172a\}$/m)
    expect(WORKSPACE_CSS).toMatch(/^:root\[data-theme=dark\] \.detailgrid dt\{color:#cbd5e1\}$/m)
    expect(WORKSPACE_CSS).toMatch(/^:root\[data-theme=dark\] \.detailgrid dd\{color:#f1f5f9\}$/m)
  })
})

/**
 * LAYOUT-ROLLOUT B3(사용자 결정 2026-11-19 폼 줄 — 사업자 등록 bab658b·가맹점 등록 71d0c48과 같은 FormRow): 결제 보정 취소 입력은 이름 왼쪽(140px)·입력 오른쪽 같은 줄,
 * 입력 폭은 DB 열 길이 단계(ADMIN V5 admin_operator_cancel_command — tasks.json layoutRollout.B3.widthTable). 모양만 — 이름·설명·업무 동작은 위·PaymentOpsScreen.test.tsx가 지킨다.
 */
/** 입력 하나의 폼 줄: .fieldrow 안 label[for] = 입력 id(이름 글자 그대로), 입력은 .fieldcontrol(줄의 자식) 안, 폭 class(null = full — fw-* 없음). */
const formRowOf = (scope: HTMLElement, label: string, width: 'xs' | 'sm' | 'md' | 'lg' | null) => {
  const input = within(scope).getByLabelText(label)
  const row = input.closest('.fieldrow') as HTMLElement
  expect(row, label).not.toBeNull()
  const name = row.querySelector(':scope > label')!
  expect(name.textContent).toBe(label)
  expect(input.id).not.toBe('')
  expect(name.getAttribute('for')).toBe(input.id)
  expect(input).not.toHaveAttribute('aria-labelledby')
  const control = input.closest('.fieldcontrol') as HTMLElement
  expect(control.parentElement).toBe(row)
  expect(Array.from(control.classList).filter((c) => c.startsWith('fw-')), label).toEqual(width ? [`fw-${width}`] : [])
  return { input, row, control }
}

describe('LAYOUT-ROLLOUT B3: 결제 보정 취소 입력 = 폼 줄(이름 왼쪽 · 입력 오른쪽 · DB 열 길이 폭)', () => {
  it('1. 대상 조회: 결제 ID 줄(md) — 대상 조회·진행 글자는 같은 .fieldcontrol(입력 오른쪽), 비어 있을 때만 도움말 .fieldnote(설명 연결)', async () => {
    const { container } = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => target) })} canRequest />)
    const lookupBody = container.querySelectorAll(':scope > .cardbody')[0] as HTMLElement
    expect(lookupBody.querySelector('.rolebar')).toBeNull()
    const { input, row, control } = formRowOf(lookupBody, '결제 ID', 'md')
    expect(row.parentElement).toBe(lookupBody)
    // 입력이 .fieldcontrol 첫 자식이어야 최소 폭(16ch)·폭 단계(max-width) 규칙이 입력에 걸린다.
    expect(control.firstElementChild).toBe(input)
    expect(input).toHaveClass('font-mono')
    const button = within(lookupBody).getByRole('button', { name: '대상 조회' })
    expect(button.parentElement).toBe(control)
    const live = control.querySelector(':scope > span.subtle')!
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live.previousElementSibling).toBe(button)
    const note = document.getElementById(input.getAttribute('aria-describedby')!)!
    expect(note).toHaveClass('fieldnote')
    expect(note.parentElement).toBe(row)
    expect(input).toHaveAccessibleDescription('결제 ID(pay_…)를 입력하면 조회할 수 있습니다.')
    expect(container.querySelectorAll('[style]')).toHaveLength(0)

    await userEvent.type(input, PAY)
    expect(input).not.toHaveAttribute('aria-describedby')
    expect(row.querySelector(':scope > .fieldnote')).toBeNull()
  })

  it('2. 요청 작성: 2열 grid 없이 세로 줄 — 취소 금액 sm · 목적 종류 md · 근거 참조 full · 사유 full(설명 그대로), 조회 값 dl.detailgrid는 그대로', async () => {
    const { container } = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => target) })} canRequest />)
    await lookUp()
    await screen.findByText('반환 가능액: 7000 KRW')
    expect(container.querySelector('.cardbody > dl.detailgrid')).not.toBeNull()
    const requestBody = container.querySelectorAll(':scope > .cardbody')[1] as HTMLElement
    expect(requestBody.querySelector('form [class*="grid"]')).toBeNull()
    const amount = formRowOf(requestBody, '취소 금액(최소 단위)', 'sm')
    expect(amount.input).toHaveAccessibleDescription('= 7,000원')
    expect(amount.row.querySelector(':scope > .fieldnote')).toHaveTextContent('= 7,000원')
    const purpose = formRowOf(requestBody, '목적 종류', 'md')
    expect(purpose.input.tagName.toLowerCase()).toBe('select')
    expect(purpose.input).toHaveAccessibleDescription(labelOf('INCIDENT_OR_RECON_CORRECTION'))
    const reference = formRowOf(requestBody, '근거 참조', null)
    expect(reference.input).toHaveAttribute('maxLength', '200')
    const reason = formRowOf(requestBody, '사유', null)
    expect(reason.input.tagName.toLowerCase()).toBe('textarea')
    expect(reason.input).toHaveAttribute('maxLength', '500')
    expect(reason.input).toHaveAccessibleDescription('1~500자. 승인자가 판단할 수 있게 구체적으로 적어 주세요.')
    // 네 줄은 같은 묶음 안 위에서 아래로(금액 → 목적 → 근거 → 사유).
    const rows = [amount.row, purpose.row, reference.row, reason.row]
    expect(new Set(rows.map((r) => r.parentElement)).size).toBe(1)
    expect(Array.from(amount.row.parentElement!.children)).toEqual(rows)
    expect(container.querySelectorAll('[style]')).toHaveLength(0)
  })
})
