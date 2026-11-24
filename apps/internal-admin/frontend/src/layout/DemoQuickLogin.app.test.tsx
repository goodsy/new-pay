import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import type { DemoQuickLoginAccount, Me } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { settle } from '../test/settle'

/**
 * ADMIN-DEMO-LOGIN-001 §7 — T-10: 로그인 뒤 계정 영역의 "데모 간편 로그인 켜짐"·"계정 바꾸기"(로그아웃 → 로그인 화면 데모 계정 버튼 → 다른 계정).
 * 2인 승인 흐름(운영자 요청 → 승인자 승인)을 혼자 시험하는 경로. 꺼져 있으면 아무 표시 없음.
 */
const operator: Me = { actorId: 'adm_op', displayName: '데모 운영자', roles: ['OPERATOR'] }
const approver: Me = { actorId: 'adm_ap', displayName: '데모 승인자', roles: ['APPROVER'] }
const accounts: DemoQuickLoginAccount[] = [
  { loginName: 'demo-operator', displayName: '데모 운영자', roles: ['OPERATOR'], available: true },
  { loginName: 'demo-approver', displayName: '데모 승인자', roles: ['APPROVER'], available: true },
]
const bottom = () => within(document.querySelector('aside .bottom') as HTMLElement)

function api(options: () => Promise<DemoQuickLoginAccount[] | null>) {
  return fakeApi({
    me: vi.fn(async () => operator),
    approvals: vi.fn(async () => ({ approvals: [] })),
    demoQuickLoginOptions: vi.fn(options),
    demoQuickLogin: vi.fn(async (loginName: string) => (loginName === 'demo-approver' ? approver : operator)),
  })
}

describe('App — demo quick login status and account switch (ADMIN-DEMO-LOGIN-001)', () => {
  it('shows "데모 간편 로그인 켜짐" in the account area; 계정 바꾸기 logs out and the demo buttons sign in as another account', async () => {
    const a = api(async () => accounts)
    render(<App api={a} />)
    await screen.findByRole('button', { name: '로그아웃' })
    expect(await bottom().findByRole('status', { name: '데모 간편 로그인 켜짐' })).toHaveTextContent('데모 간편 로그인 켜짐')
    expect(bottom().getByText('데모 운영자')).toBeInTheDocument()
    await userEvent.click(bottom().getByRole('button', { name: '계정 바꾸기' }))
    expect(a.logout).toHaveBeenCalledOnce()
    await userEvent.click(await screen.findByRole('button', { name: '데모 승인자 · 승인자' }))
    expect(a.demoQuickLogin).toHaveBeenCalledWith('demo-approver')
    await screen.findByRole('button', { name: '로그아웃' })
    expect(await bottom().findByText('데모 승인자')).toBeInTheDocument()
    expect(bottom().getByText('역할: 승인자')).toBeInTheDocument()
  })

  it('nothing extra in the account area when the server has it off (404 = null) or the call fails', async () => {
    for (const options of [async () => null, async () => { throw apiError(500, 'SERVER_ERROR') }]) {
      const a = api(options)
      const { unmount } = render(<App api={a} />)
      expect(await screen.findByRole('button', { name: '로그아웃' })).toBeInTheDocument()
      await settle()
      expect(a.demoQuickLoginOptions).toHaveBeenCalled()
      expect(bottom().queryByText('데모 간편 로그인 켜짐')).toBeNull()
      expect(bottom().queryByRole('button', { name: '계정 바꾸기' })).toBeNull()
      unmount()
    }
  })
})
