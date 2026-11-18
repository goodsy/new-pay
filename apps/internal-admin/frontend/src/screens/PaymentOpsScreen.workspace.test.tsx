import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { PaymentOpsScreen } from './PaymentOpsScreen'
import type { AdminApi, PaymentTargetView } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

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
  it('flush in the menu card: .mapnote(h2) → .wizard → lookup .cardtop/.cardbody(.rolebar) → after lookup .detailgrid and the request .cardtop/.cardbody', async () => {
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

    // 대상 조회 카드: 제목 줄 h3 + 본문(안내 .subtle · 시안 .rolebar 줄 = 보이는 이름 b + 입력 aria-labelledby + 버튼 · 도움말 aria-describedby)
    expect(panel.querySelector(':scope > .cardtop > h3')).toHaveTextContent('1. 대상 조회')
    const lookupBody = panel.querySelectorAll(':scope > .cardbody')[0] as HTMLElement
    expect(lookupBody.querySelector(':scope > p.subtle')).toHaveTextContent('조회 값은 참고용이며, 실제 취소 가능 여부는 승인 시점에 결제 서비스가 다시 판정합니다.')
    const bar = lookupBody.querySelector(':scope > .rolebar') as HTMLElement
    // reviewFix1 MINOR-1: 버튼 뒤 진행 표시 칸(span.subtle aria-live=polite — 평소 비어 있음). 기대값 변경(이번 화면 새 시험만).
    expect(Array.from(bar.children).map((c) => c.tagName.toLowerCase())).toEqual(['b', 'input', 'button', 'span'])
    const input = within(bar).getByLabelText('결제 ID')
    expect(document.getElementById(input.getAttribute('aria-labelledby')!)).toBe(input.previousElementSibling)
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
    expect(lookupBody.querySelector('label:not(:has(input, select, textarea))')).toBeNull()
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
    const live = container.querySelector('.rolebar > span.subtle')!
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
