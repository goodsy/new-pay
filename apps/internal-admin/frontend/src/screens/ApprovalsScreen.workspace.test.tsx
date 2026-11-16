import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { ApprovalsScreen } from './ApprovalsScreen'
import type { AdminApi, ApprovalView, PaymentTargetView } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 승인함·내 요청("모양만"): 거래 조회(16cc494)·매입·대사(96608d3)와 같이 참고 시안 moduleView(카드 → .mapnote → .tablewrap → .tablebottom)와
 * detail(eyebrow·제목·카드)을 실제 승인함·내 요청에 입힌다. 기능·API 호출·TOTP·2인 규칙 표시(서버가 판정)·근거 조회 규칙·오류·세션 종료는
 * ApprovalsScreen.*.test.tsx·OwnerApprovalDetail.compare.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture(업무 의미 없는 고정 식별자): 보정 취소 승인 1건(PAYMENT 대상 — 요청 금액 7,000원 = 고정 반환 가능액), 재매입 승인 1건(원 파일 레코드 3 — 세대 0 거절).
 */
const PAY = 'pay_01JC0000000000000000000001'
const pinned = { paymentId: PAY, paymentStatus: 'AUTHORIZED', currency: 'KRW', authorizedMinor: 10000, cancellableMinor: 7000, balanceRevision: 3, onHold: false }
const current: PaymentTargetView = { ...pinned, cancellable: true, inFlightCancel: false }
const ownerApproval: ApprovalView = {
  approvalId: 'apr_P', kind: 'OPERATOR_CORRECTION_CANCEL', targetKind: 'PAYMENT', targetActorId: null, targetRef: PAY, targetSnapshot: '{}', grantedRole: null,
  causeApprovalId: null, requestedBy: 'adm_R', requestedByDisplayName: '운영자', requestedAt: '2026-10-28T00:00:00Z', expiresAt: '2026-10-29T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
  owner: {
    amountMinor: 7000, currency: 'KRW', purposeKind: 'INCIDENT_OR_RECON_CORRECTION', pinnedTarget: pinned, approvedAt: null, executeBefore: null, attemptCount: 1,
    nextAttemptAt: null, lastResponseAt: null, lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
  },
}
const F1 = 'ACQ.SIMACQA.TEST.20261105.001'
const pinnedItem = { seq: 3, transactionId: 'ptx_3', recordType: 'SALE', generation: 0, status: 'REJECTED', approvedAmountMinor: 5000, cancelledAmountMinor: 0, expectedNetMinor: 5000 }
const acquiringApproval: ApprovalView = {
  approvalId: 'apr_ACQ', kind: 'ACQ_REACQUISITION', targetKind: 'ACQUISITION_FILE', targetActorId: null, targetRef: F1, targetSnapshot: '{}', grantedRole: null,
  causeApprovalId: null, requestedBy: 'adm_R', requestedByDisplayName: '운영자', requestedAt: '2026-11-05T00:00:00Z', expiresAt: '2026-11-06T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
  acquiring: {
    originalFileId: F1, reasonCode: 'INSTITUTION_REQUESTED', pinnedTarget: { items: [pinnedItem] }, approvedAt: null, executeBefore: null, attemptCount: null,
    nextAttemptAt: null, lastResponseAt: null, lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
  },
}
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_A', displayName: '승인 관리자', roles: ['APPROVER'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
    ...overrides,
  })
  render(<App api={api} />)
  return api
}

describe('ADMIN-UI-WORKSPACE-001: 승인함 · 내 요청 in the reference list layout', () => {
  it('승인함 flush in the menu card: section.card > .mapnote(h2) → .tablewrap → .tablebottom; the row keeps the TOTP field with .btn decisions; the detail is a .detailarea with a reference card', async () => {
    signedIn({
      approvals: vi.fn(async () => ({ approvals: [ownerApproval] })), approvalDetail: vi.fn(async () => ownerApproval), paymentTarget: vi.fn(async () => current),
    })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '승인함' }))
    const row = await screen.findByRole('row', { name: /OPERATOR_CORRECTION_CANCEL/ })
    const card = screen.getByRole('main').querySelector(':scope > section.card')!
    expect(shape(card)).toEqual(['div.mapnote', 'div.tablewrap', 'div.tablebottom'])
    expect(within(card.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '승인함' })).toBeInTheDocument()

    const wrap = card.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('승인 대기 목록')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['종류', '역할·원인', '대상', '요청자', '상태', '작업'])
    // 종류는 시안 표 칸 모양(strong 이름 + small code), 상태는 .tag(title = 원래 code)
    expect(row.querySelector('td > strong')).toHaveTextContent('운영자 보정 취소')
    expect(row.querySelector('td > small')).toHaveTextContent('OPERATOR_CORRECTION_CANCEL')
    expect(row.querySelector('td .tag')).toHaveAttribute('title', 'PENDING')
    // 결정 양식: 인증 코드 입력은 그대로(도움말 aria-describedby), 승인은 .btn.primary, 거절·상세는 .btn — 6자리 전에는 막혀 있다
    expect(within(row).getByLabelText('인증 코드(6자리)')).toHaveAccessibleDescription('6자리를 입력하면 승인·거절할 수 있습니다.')
    expect(within(row).getByRole('button', { name: '승인' })).toHaveClass('btn', 'primary')
    expect(within(row).getByRole('button', { name: '승인' })).toBeDisabled()
    expect(within(row).getByRole('button', { name: '거절' })).toHaveClass('btn')
    expect(within(row).getByRole('button', { name: '상세' })).toHaveClass('btn')
    // reviewFix1 MINOR-1: 거절은 기존 파괴 동작 버튼 token(destructive-solid 바탕·선 + 흰 글자). NIT-3: 승인·거절 사이 세로 구분선 없음.
    expect(within(row).getByRole('button', { name: '거절' })).toHaveClass('border-destructive-solid', 'bg-destructive-solid', 'text-white')
    expect(row.querySelector('[aria-hidden="true"].bg-border')).toBeNull()
    // reviewFix1 MINOR-2(1101px 캡처 — 작업 칸 잘림): 결정 칸은 세로로 쌓는다 — 인증 코드 칸(도움말 줄바꿈 whitespace-normal) 위, 버튼 줄(승인·거절·상세) 아래.
    const decision = within(row).getByLabelText('인증 코드(6자리)').closest('td > div')!
    expect(decision).toHaveClass('flex-col')
    // 기대값 변경(ADMIN-UI-WORKSPACE-001 승인함, Coordinator 1101·1024px 캡처): 인증 코드 칸 w-44 → w-36(표가 감싸는 칸보다 넓어 작업 열이 가려지던 것) — 구조 단언은 그대로.
    expect(shape(decision)).toEqual(['div.flex.flex-col.gap-1.5.w-36.whitespace-normal.text-xs', 'div.flex.flex-wrap.items-center.gap-2'])
    expect(Array.from(decision.lastElementChild!.querySelectorAll('button')).map((b) => b.textContent)).toEqual(['승인', '거절', '상세'])
    expect(row).not.toHaveClass('selected')
    expect(card.querySelector('.tablebottom')).toHaveTextContent('표시 1건')

    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '소유 서비스 실행 상세' })
    expect(shape(card)).toEqual(['div.mapnote', 'div.tablewrap', 'div.tablebottom', 'div.detailarea'])
    // reviewFix1 NIT-1: 상세를 연 행은 기존 tr.selected(거래 조회와 같음)
    expect(screen.getByRole('row', { name: /OPERATOR_CORRECTION_CANCEL/ })).toHaveClass('selected')
    const area = card.querySelector('.detailarea')!
    expect(area.querySelector(':scope > div > .eyebrow')).toHaveTextContent('승인함 / 상세')
    expect(within(area as HTMLElement).getByRole('heading', { level: 3 })).toHaveTextContent('승인 상세 apr_P')
    expect(panel.parentElement).toBe(area)
    expect(panel).toHaveClass('card')
    expect(shape(panel)).toEqual(['div.cardtop', 'div.cardbody'])
    expect(within(panel.querySelector('.cardtop') as HTMLElement).getByRole('heading', { level: 4 })).toHaveTextContent('운영자 보정 취소')
    expect(panel.querySelector('.cardbody .tag')).toHaveAttribute('title', 'PENDING')
    const compare = await within(panel).findByRole('table', { name: '고정 값과 현재 값' })
    expect(compare.parentElement).toHaveClass('tablewrap')
    expect(within(panel).getByRole('button', { name: '근거·사유 보기' })).toHaveClass('btn')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('내 요청: .mapnote(h2 내 요청) and withdraw is a .btn; an empty list is one .empty line in the table wrapper', async () => {
    const withdraw = vi.fn(async () => ({ status: 'WITHDRAWN' }))
    const approvals = vi.fn(async () => ({ approvals: [ownerApproval] }))
    const { container } = render(<ApprovalsScreen api={fakeApi({ approvals, withdraw })} view="mine" canDecide={false} />)
    const row = await screen.findByRole('row', { name: /OPERATOR_CORRECTION_CANCEL/ })
    expect(shape(container)).toEqual(['div.mapnote', 'div.tablewrap', 'div.tablebottom'])
    expect(within(container.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '내 요청' })).toBeInTheDocument()
    expect(container.querySelector('table')).toHaveAccessibleName('내 승인 요청 목록')
    expect(within(row).getByRole('button', { name: '철회' })).toHaveClass('btn')
    expect(within(row).queryByLabelText('인증 코드(6자리)')).toBeNull()

    approvals.mockResolvedValue({ approvals: [] })
    await userEvent.click(within(row).getByRole('button', { name: '철회' }))
    expect(await screen.findByText('내가 만든 승인 요청이 없습니다.')).toHaveClass('empty')
    expect(container.querySelector('.tablewrap > .empty')).toBeInTheDocument()
    expect(container.querySelector('.tablebottom')).toHaveTextContent('표시 0건')
  })

  it('an error is one alert in a .cardnote under the map note (the list area stays as before)', async () => {
    const { container } = render(<ApprovalsScreen api={fakeApi({ approvals: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }) })} view="pending" canDecide />)
    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(shape(container)).toEqual(['div.mapnote', 'div.cardnote', 'div.tablewrap', 'div.tablebottom'])
  })

  it('the reacquisition detail is a reference card: pinned/current records in a .tablewrap, the evidence after "근거 보기" in a .notice', async () => {
    const reacquisitionEvidence = vi.fn(async () => ({ approvalId: 'apr_ACQ', evidenceRef: 'INQ-2026-0001' }))
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [acquiringApproval] })), approvalDetail: vi.fn(async () => acquiringApproval), reacquisitionEvidence,
      reacquisitionTargets: vi.fn(async () => ({ fileId: F1, readAt: '2026-11-05T01:00:00Z', items: [{ ...pinnedItem, eligible: true, refusal: null }] })),
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
    const row = await screen.findByRole('row', { name: /ACQ_REACQUISITION/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(panel).toHaveClass('card')
    expect(panel.parentElement).toHaveClass('detailarea')
    expect(within(panel.querySelector('.cardtop') as HTMLElement).getByRole('heading', { level: 4 })).toHaveTextContent('재매입 승인')
    expect(within(panel).getByRole('table', { name: '재매입 레코드 고정 값과 현재 값' }).parentElement).toHaveClass('tablewrap')
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByText('INQ-2026-0001')).toBeInTheDocument()
    expect(within(panel).getByText('INQ-2026-0001').closest('dl')).toHaveClass('notice')
  })
})
