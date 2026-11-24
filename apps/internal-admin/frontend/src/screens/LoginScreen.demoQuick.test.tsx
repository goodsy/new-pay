import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LoginScreen } from './LoginScreen'
import type { DemoQuickLoginAccount } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { settle } from '../test/settle'

/** ADMIN-DEMO-LOGIN-001 §7 — T-9: 데모 간편 로그인 카드(서버 옵션이 있을 때만). */
const accounts: DemoQuickLoginAccount[] = [
  { loginName: 'demo-operator', displayName: '데모 운영자', roles: ['OPERATOR'], available: true },
  { loginName: 'demo-approver', displayName: '데모 승인자', roles: ['APPROVER'], available: true },
  { loginName: 'demo-locked', displayName: '데모 잠김', roles: [], available: false },
]
const me = { actorId: 'adm_01', displayName: '데모 승인자', roles: ['APPROVER'] }

describe('LoginScreen — demo quick login (ADMIN-DEMO-LOGIN-001)', () => {
  it('shows the demo card above the normal login card with one button per account (display name · role), and a demo-only notice', async () => {
    const api = fakeApi({ demoQuickLoginOptions: vi.fn(async () => accounts), demoQuickLogin: vi.fn(async () => me) })
    render(<LoginScreen api={api} onAuthenticated={vi.fn()} onEnroll={vi.fn()} />)
    const card = await screen.findByRole('region', { name: '데모 간편 로그인' })
    expect(within(card).getByRole('note', { name: '데모 전용' })).toHaveTextContent('비밀번호·OTP 없이')
    const buttons = within(within(card).getByRole('group', { name: '데모 계정' })).getAllByRole('button')
    expect(buttons.map((b) => b.textContent)).toEqual(['데모 운영자 · 운영자', '데모 승인자 · 승인자', '데모 잠김 · 역할 없음 (사용 불가)'])
    expect(buttons[2]).toBeDisabled()
    const loginCard = screen.getByText('로그인', { selector: 'h2' }).closest('section')!
    expect(card.compareDocumentPosition(loginCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 일반 로그인은 그대로.
    expect(screen.getByLabelText('로그인 이름')).toBeInTheDocument()
  })

  it('a button gets a CSRF session first, then signs in as that account and reports it like a normal login; nothing is stored', async () => {
    const api = fakeApi({ demoQuickLoginOptions: vi.fn(async () => accounts), demoQuickLogin: vi.fn(async () => me) })
    const onAuthenticated = vi.fn()
    render(<LoginScreen api={api} onAuthenticated={onAuthenticated} onEnroll={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '데모 승인자 · 승인자' }))
    expect(api.csrf).toHaveBeenCalledOnce()
    expect(api.demoQuickLogin).toHaveBeenCalledWith('demo-approver')
    expect(onAuthenticated).toHaveBeenCalledWith(me)
    expect(api.password).not.toHaveBeenCalled()
    expect(storageIsEmpty()).toBe(true)
  })

  it('a refused quick login shows the same indistinct message as the normal login', async () => {
    const api = fakeApi({
      demoQuickLoginOptions: vi.fn(async () => accounts),
      demoQuickLogin: vi.fn(async () => { throw apiError(401, 'INVALID_CREDENTIALS') }),
    })
    const onAuthenticated = vi.fn()
    render(<LoginScreen api={api} onAuthenticated={onAuthenticated} onEnroll={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '데모 운영자 · 운영자' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('로그인 정보가 올바르지 않습니다')
    expect(onAuthenticated).not.toHaveBeenCalled()
  })

  it('no demo card when the server has it off (null = 404), the options call fails, the list is empty, or the API has no such method', async () => {
    const cases = [
      fakeApi({ demoQuickLoginOptions: vi.fn(async () => null), demoQuickLogin: vi.fn() }),
      fakeApi({ demoQuickLoginOptions: vi.fn(async () => { throw apiError(403, 'IP_DENIED') }), demoQuickLogin: vi.fn() }),
      fakeApi({ demoQuickLoginOptions: vi.fn(async () => []), demoQuickLogin: vi.fn() }),
      fakeApi(),
    ]
    for (const api of cases) {
      const { unmount } = render(<LoginScreen api={api} onAuthenticated={vi.fn()} onEnroll={vi.fn()} />)
      await settle()
      if (api.demoQuickLoginOptions) expect(api.demoQuickLoginOptions).toHaveBeenCalledOnce()
      expect(screen.getByText('로그인', { selector: 'h2' })).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: '데모 간편 로그인' })).toBeNull()
      expect(document.querySelectorAll('section.card')).toHaveLength(1)
      unmount()
    }
  })
})
