import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ApprovalView, ReconciliationOverview } from '../api/adminApi'
import { menusFor } from '../navigation'
import { apiError, fakeApi } from '../test/fakeApi'
import { HomeScreen } from './HomeScreen'

/** 승인 요청 한 건(상태만 시험 조건 — 나머지는 표시용 고정값). */
const approval = (approvalId: string, status: string): ApprovalView => ({
  approvalId, kind: 'ROLE_GRANT', targetActorId: 'adm_T', targetSnapshot: '{}', grantedRole: 'VIEWER', causeApprovalId: null, requestedBy: 'adm_R',
  requestedByDisplayName: '요청자', requestedAt: '2026-11-01T00:00:00Z', expiresAt: '2026-11-02T00:00:00Z', status, decidedBy: null, decidedByDisplayName: null,
  decidedAt: null, resultCode: null,
})
const overview = (openTotal: number): ReconciliationOverview => ({
  openCounts: openTotal ? { MISSING_DECISION: openTotal } : {}, openTotal, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null,
})
const me = (roles: string[]) => ({ actorId: 'adm_1', displayName: '김 운영', roles })

function renderHome(roles: string[], api = fakeApi(), onOpen = vi.fn(), onSessionEnded = vi.fn()) {
  render(<HomeScreen api={api} me={me(roles)} menus={menusFor(roles)} onOpen={onOpen} onSessionEnded={onSessionEnded} />)
  return { api, onOpen, onSessionEnded }
}

describe('HomeScreen (운영 홈 — real counts only from calls the role already makes, D-ADMIN-UI-003)', () => {
  it('APPROVER: pending approvals (inbox call) and open findings; never calls "mine"', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING'), approval('apr_2', 'PENDING')] })),
      reconciliation: vi.fn(async () => overview(3)),
    })
    const { onOpen } = renderHome(['APPROVER'], api)
    expect(screen.getByRole('heading', { level: 1, name: '안녕하세요, 김 운영님' })).toBeInTheDocument()
    expect(await screen.findByRole('article', { name: '승인 대기' })).toHaveTextContent('2건')
    expect(await screen.findByRole('article', { name: '열린 대사 발견' })).toHaveTextContent('3건')
    expect(screen.queryByRole('article', { name: '내 요청 대기' })).toBeNull()
    expect(api.approvals).toHaveBeenCalledTimes(1)
    expect(api.approvals).toHaveBeenCalledWith('pending')
    const todo = screen.getByRole('region', { name: '지금 처리할 업무' })
    await userEvent.click(within(todo).getByRole('button', { name: /승인을 기다리는 요청/ }))
    expect(onOpen).toHaveBeenCalledWith('approvals')
    await userEvent.click(within(todo).getByRole('button', { name: /맞지 않는 대사 발견/ }))
    expect(onOpen).toHaveBeenCalledWith('reconciliation')
  })

  it('OPERATOR: counts only PENDING among my requests; no inbox and no findings call', async () => {
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING'), approval('apr_2', 'APPROVED'), approval('apr_3', 'WITHDRAWN')] })) })
    renderHome(['OPERATOR'], api)
    expect(await screen.findByRole('article', { name: '내 요청 대기' })).toHaveTextContent('1건')
    expect(api.approvals).toHaveBeenCalledTimes(1)
    expect(api.approvals).toHaveBeenCalledWith('mine')
    expect(api.reconciliation).not.toHaveBeenCalled()
    expect(screen.queryByRole('article', { name: '승인 대기' })).toBeNull()
    expect(screen.queryByRole('article', { name: '열린 대사 발견' })).toBeNull()
  })

  it('SECURITY_ADMIN: my requests and findings (not the inbox)', async () => {
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [] })), reconciliation: vi.fn(async () => overview(0)) })
    renderHome(['SECURITY_ADMIN'], api)
    expect(await screen.findByRole('article', { name: '내 요청 대기' })).toHaveTextContent('0건')
    expect(await screen.findByRole('article', { name: '열린 대사 발견' })).toHaveTextContent('0건')
    expect(api.approvals).toHaveBeenCalledWith('mine')
    expect(api.approvals).not.toHaveBeenCalledWith('pending')
    expect(await within(screen.getByRole('region', { name: '지금 처리할 업무' })).findByText('지금 처리할 업무가 없어요.')).toBeInTheDocument()
  })

  it('VIEWER: no data call, no count card — greeting and shortcuts to the menus it can use', async () => {
    const { api, onOpen } = renderHome(['VIEWER'])
    expect(screen.getByRole('heading', { level: 1, name: '안녕하세요, 김 운영님' })).toBeInTheDocument()
    expect(screen.queryAllByRole('article')).toHaveLength(0)
    expect(screen.queryByRole('region', { name: '지금 처리할 업무' })).toBeNull()
    const shortcuts = screen.getByRole('region', { name: '바로가기' })
    expect(within(shortcuts).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['가맹점 조회 열기', '결제 보정 취소 열기', '계정 관리 열기', '접속·감사 이력 열기'])
    await userEvent.click(within(shortcuts).getByRole('button', { name: '접속·감사 이력 열기' }))
    expect(onOpen).toHaveBeenCalledWith('audit')
    expect(api.approvals).not.toHaveBeenCalled()
    expect(api.reconciliation).not.toHaveBeenCalled()
  })

  it('UNMASK only: a single shortcut to the audit history', () => {
    renderHome(['UNMASK'])
    const shortcuts = screen.getByRole('region', { name: '바로가기' })
    expect(within(shortcuts).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['접속·감사 이력 열기'])
    expect(screen.queryAllByRole('article')).toHaveLength(0)
  })

  it('a list cut at the server limit (200 rows, no total) is shown as "200건 이상", not as an exact count', async () => {
    const many = Array.from({ length: 200 }, (_, i) => approval(`apr_${i}`, 'PENDING'))
    renderHome(['APPROVER'], fakeApi({ approvals: vi.fn(async () => ({ approvals: many })), reconciliation: vi.fn(async () => overview(0)) }))
    expect(await screen.findByRole('article', { name: '승인 대기' })).toHaveTextContent('200건 이상')
  })

  it('a failed count shows the fixed error in its card only (no number); a session-ending answer goes back to login', async () => {
    renderHome(['APPROVER'], fakeApi({
      approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING')] })),
      reconciliation: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }),
    }))
    const findings = await screen.findByRole('article', { name: '열린 대사 발견' })
    expect(await within(findings).findByText(/권한/)).toBeInTheDocument()
    expect(findings).not.toHaveTextContent(/\d+건/)
    expect(await screen.findByRole('article', { name: '승인 대기' })).toHaveTextContent('1건')

    const ended = renderHome(['OPERATOR'], fakeApi({ approvals: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }) }))
    await vi.waitFor(() => expect(ended.onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })
})
