import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LoginScreen } from './LoginScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

const me = { actorId: 'adm_01', displayName: '보안 관리자', roles: ['SECURITY_ADMIN'] }

describe('LoginScreen (password → TOTP)', () => {
  it('gets a CSRF session first, sends the password, then the TOTP code, and reports the signed-in account', async () => {
    const api = fakeApi({ password: vi.fn(async () => ({ next: 'TOTP' })), totp: vi.fn(async () => me) })
    const onAuthenticated = vi.fn()
    render(<LoginScreen api={api} onAuthenticated={onAuthenticated} onEnroll={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('로그인 이름'), 'sa-1')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'secret-password')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.csrf).toHaveBeenCalledOnce()
    expect(api.password).toHaveBeenCalledWith('sa-1', 'secret-password')
    await userEvent.type(await screen.findByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))
    expect(api.totp).toHaveBeenCalledWith('123456')
    expect(onAuthenticated).toHaveBeenCalledWith(me)
    expect(storageIsEmpty()).toBe(true)
  })

  it('shows one indistinct message for invalid credentials and clears the password field', async () => {
    const api = fakeApi({ password: vi.fn(async () => { throw apiError(401, 'INVALID_CREDENTIALS') }) })
    render(<LoginScreen api={api} onAuthenticated={vi.fn()} onEnroll={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('로그인 이름'), 'x')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('로그인 정보가 올바르지 않습니다')
    expect(screen.getByLabelText('비밀번호')).toHaveValue('')
  })

  it('explains an IP denial and a replaced session without exposing server text', async () => {
    const api = fakeApi({ password: vi.fn(async () => { throw apiError(403, 'IP_DENIED') }) })
    render(<LoginScreen api={api} onAuthenticated={vi.fn()} onEnroll={vi.fn()} notice="SESSION_REPLACED" />)
    expect(screen.getByRole('status')).toHaveTextContent('다른 곳에서 로그인')
    await userEvent.type(screen.getByLabelText('로그인 이름'), 'x')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'pw')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('허용되지 않은 네트워크')
  })
})
