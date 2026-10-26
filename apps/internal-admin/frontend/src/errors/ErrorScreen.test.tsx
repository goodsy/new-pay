import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ErrorScreen } from './ErrorScreen'

describe('ErrorScreen', () => {
  it('explains a replaced session and offers the login screen', async () => {
    const onLogin = vi.fn()
    render(<ErrorScreen code="SESSION_REPLACED" onLogin={onLogin} onRetry={vi.fn()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('다른 곳에서 로그인')
    await userEvent.click(screen.getByRole('button', { name: '로그인 화면으로' }))
    expect(onLogin).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('offers retry for RETRY_LATER (409) without a login action', async () => {
    const onRetry = vi.fn()
    render(<ErrorScreen code="RETRY_LATER" onLogin={vi.fn()} onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('잠시 후 다시 시도')
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: '로그인 화면으로' })).toBeNull()
  })

  it('shows a fixed forbidden message with no actions', () => {
    render(<ErrorScreen code="FORBIDDEN" onLogin={vi.fn()} onRetry={vi.fn()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  })

  it('has a fixed message for every error code', () => {
    for (const code of ['UNAUTHENTICATED', 'SESSION_EXPIRED', 'NOT_FOUND', 'SERVER_ERROR', 'CSRF_MISSING', 'REQUEST_REJECTED'] as const) {
      const { unmount } = render(<ErrorScreen code={code} onLogin={vi.fn()} onRetry={vi.fn()} />)
      expect(screen.getByRole('alert').textContent?.trim().length).toBeGreaterThan(0)
      unmount()
    }
  })
})
