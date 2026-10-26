import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EnrollmentScreen } from './EnrollmentScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

const URI = 'otpauth://totp/PayCore%20Admin:new?secret=JBSWY3DPEHPK3PXP&issuer=PayCore%20Admin'

describe('EnrollmentScreen (code → password → TOTP → complete)', () => {
  it('walks only the steps the code requires, shows the TOTP setup key once, and never stores the code', async () => {
    const api = fakeApi({
      enrollStart: vi.fn(async () => ({ purpose: 'ENROLL', needsPassword: true, needsTotp: true })),
      enrollPassword: vi.fn(async () => undefined),
      enrollTotp: vi.fn(async () => ({ otpauthUri: URI })),
      enrollComplete: vi.fn(async () => ({ status: 'COMPLETED' })),
    })
    const onDone = vi.fn()
    render(<EnrollmentScreen api={api} onDone={onDone} />)
    await userEvent.type(screen.getByLabelText('등록 코드'), 'ABCD EFGH')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(api.csrf).toHaveBeenCalledOnce()
    expect(api.enrollStart).toHaveBeenCalledWith('ABCD EFGH')
    await userEvent.type(await screen.findByLabelText('새 비밀번호'), 'new-password-123')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 설정' }))
    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('인증 코드(6자리)'), '654321')
    await userEvent.click(screen.getByRole('button', { name: '등록 완료' }))
    expect(api.enrollComplete).toHaveBeenCalledWith('654321')
    expect(await screen.findByRole('status')).toHaveTextContent('등록이 끝났습니다')
    expect(screen.queryByText('JBSWY3DPEHPK3PXP')).toBeNull()
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain('ABCD')
  })

  it('a PASSWORD_RESET code asks for the password only and completes without a TOTP code', async () => {
    const api = fakeApi({
      enrollStart: vi.fn(async () => ({ purpose: 'PASSWORD_RESET', needsPassword: true, needsTotp: false })),
      enrollPassword: vi.fn(async () => undefined),
      enrollComplete: vi.fn(async () => ({ status: 'COMPLETED' })),
    })
    render(<EnrollmentScreen api={api} onDone={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('등록 코드'), 'CODE')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    await userEvent.type(await screen.findByLabelText('새 비밀번호'), 'new-password-123')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 설정' }))
    await userEvent.click(await screen.findByRole('button', { name: '등록 완료' }))
    expect(api.enrollTotp).not.toHaveBeenCalled()
    expect(api.enrollComplete).toHaveBeenCalledWith(undefined)
  })

  it('S7 review m-3: a STEP_INVALID at completion resets to the code step and drops the setup key and plan', async () => {
    const api = fakeApi({
      enrollStart: vi.fn(async () => ({ purpose: 'TOTP_REENROLL', needsPassword: false, needsTotp: true })),
      enrollTotp: vi.fn(async () => ({ otpauthUri: URI })),
      enrollComplete: vi.fn(async () => { throw apiError(400, 'ENROLLMENT_STEP_INVALID') }),
    })
    render(<EnrollmentScreen api={api} onDone={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('등록 코드'), 'CODE')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('인증 코드(6자리)'), '111111')
    await userEvent.click(screen.getByRole('button', { name: '등록 완료' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('처음부터 다시')
    expect(screen.getByLabelText('등록 코드')).toHaveValue('')
    expect(screen.queryByText('JBSWY3DPEHPK3PXP')).toBeNull()
    expect(screen.queryByLabelText('인증 코드(6자리)')).toBeNull()
  })

  it('maps an invalid code and a password policy failure to fixed messages', async () => {
    const api = fakeApi({ enrollStart: vi.fn(async () => { throw apiError(400, 'ENROLLMENT_CODE_INVALID') }) })
    render(<EnrollmentScreen api={api} onDone={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('등록 코드'), 'BAD')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('등록 코드가 올바르지 않거나 만료되었습니다')
  })
})
