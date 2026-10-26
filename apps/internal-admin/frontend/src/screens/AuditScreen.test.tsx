import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditScreen } from './AuditScreen'
import { apiError, fakeApi } from '../test/fakeApi'

const entry = {
  eventId: 7, occurredAt: '2026-10-26T01:00:00Z', actorKind: 'ADMIN', actorId: 'adm_A', actorDisplayName: '관리자', executorActorId: null, action: 'APPROVAL_EXECUTED',
  targetActorId: 'adm_T', resultCode: 'SUCCESS', approvalId: 'apr_1', correlationId: 'c', detail: 'kind=ROLE_GRANT', reason: null,
}

describe('AuditScreen', () => {
  it('lists entries and filters by action (only filters are sent — the scope is the server’s)', async () => {
    const api = fakeApi({ audit: vi.fn(async () => ({ entries: [entry] })) })
    render(<AuditScreen api={api} />)
    expect(await screen.findByRole('row', { name: /APPROVAL_EXECUTED/ })).toBeInTheDocument()
    expect(api.audit).toHaveBeenLastCalledWith({})
    await userEvent.type(screen.getByLabelText('행위'), 'LOGIN_SUCCEEDED')
    await userEvent.click(screen.getByRole('button', { name: '조회' }))
    expect(api.audit).toHaveBeenLastCalledWith({ action: 'LOGIN_SUCCEEDED' })
  })

  it('shows AUDIT_UNAVAILABLE as a fixed message (no results without the view record)', async () => {
    const api = fakeApi({ audit: vi.fn(async () => { throw apiError(503, 'AUDIT_UNAVAILABLE') }) })
    render(<AuditScreen api={api} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('감사 기록을 남길 수 없어 조회하지 않았습니다')
  })
})
