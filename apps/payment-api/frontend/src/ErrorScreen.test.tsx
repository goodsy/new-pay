import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { App } from './App'
import { FALLBACK } from './checkoutData'
import { errorData, HANDLE } from './test/fixtures'

describe('checkout error and result screens (AUTHN-001 §6.3 — fixed text, server-built links only)', () => {
  it('shows the fixed message with the retry and merchant links the server built', () => {
    render(<App data={errorData()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('인증이 완료되지 않았습니다.')
    expect(screen.getByRole('link', { name: '다시 시도' })).toHaveAttribute('href', `/checkout/s/${HANDLE}`)
    expect(screen.getByRole('link', { name: '가맹점으로 돌아가기' })).toHaveAttribute(
      'href',
      'https://shop.example.test/fail?paymentId=pay_1&code=AUTHENTICATION_FAILED',
    )
  })

  it('without links there is only the message (no invented navigation)', () => {
    render(<App data={errorData({ message: '결제창 세션이 만료되었습니다.', retryUrl: null, failRedirect: null })} />)
    expect(screen.getByRole('alert')).toHaveTextContent('결제창 세션이 만료되었습니다.')
    expect(screen.queryAllByRole('link').filter((a) => a.closest('main'))).toHaveLength(0)
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('form')).toBeNull()
  })

  it('the fallback screen has the fixed temporary-error text and no links or forms', () => {
    const { container } = render(<App data={FALLBACK} />)
    expect(screen.getByRole('alert')).toHaveTextContent('일시적인 오류입니다.')
    expect(container.querySelectorAll('main a, form')).toHaveLength(0)
  })
})
