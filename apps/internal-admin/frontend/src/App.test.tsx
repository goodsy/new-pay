import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { apiError, fakeApi } from './test/fakeApi'

describe('App (session, role menu, error mapping)', () => {
  it('starts at the login screen when there is no session', async () => {
    const api = fakeApi({ me: vi.fn(async () => { throw apiError(401, 'UNAUTHENTICATED') }) })
    render(<App api={api} />)
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
  })

  it('shows the display name and only the menus the server roles allow (display only — the server decides)', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '승인자', roles: ['APPROVER'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
    })
    render(<App api={api} />)
    expect(await screen.findByText('승인자')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '승인함' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '계정' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '내 요청' })).toBeNull()
  })

  it('S8: OPERATOR sees the correction cancel and "my requests" menus (no findings); SECURITY_ADMIN and APPROVER see the findings menu', async () => {
    const operator = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_O', displayName: '운영자', roles: ['OPERATOR'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
    })
    const { unmount } = render(<App api={operator} />)
    expect(await screen.findByRole('button', { name: '결제 보정 취소' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '내 요청' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '대사 발견' })).toBeNull()
    unmount()
    for (const role of ['SECURITY_ADMIN', 'APPROVER']) {
      const api = fakeApi({
        me: vi.fn(async () => ({ actorId: 'adm_S', displayName: role, roles: [role] })),
        approvals: vi.fn(async () => ({ approvals: [] })),
        audit: vi.fn(async () => ({ entries: [] })),
      })
      const view = render(<App api={api} />)
      expect(await screen.findByRole('button', { name: '대사 발견' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '결제 보정 취소' })).not.toBeNull()
      view.unmount()
    }
  })

  it('a SESSION_REPLACED answer during work returns to login with the explanation', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '승인자', roles: ['APPROVER'] })),
      approvals: vi.fn(async () => { throw apiError(401, 'SESSION_REPLACED') }),
    })
    render(<App api={api} />)
    expect(await screen.findByRole('status')).toHaveTextContent('다른 곳에서 로그인')
    expect(screen.getByLabelText('로그인 이름')).toBeInTheDocument()
  })

  it('logout calls the server and returns to login', async () => {
    const api = fakeApi({ me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '감사자', roles: ['VIEWER'] })), audit: vi.fn(async () => ({ entries: [] })) })
    render(<App api={api} />)
    await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
    expect(api.logout).toHaveBeenCalledOnce()
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
  })
})
