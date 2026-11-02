import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { OneTimeCode } from './OneTimeCode'
import { storageIsEmpty } from '../test/fakeApi'

describe('OneTimeCode (shown once — no-store, not kept after leaving)', () => {
  it('shows the code with the "cannot be shown again" warning; closing calls back so the owner drops it', async () => {
    const onClose = vi.fn()
    render(<OneTimeCode code="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" onClose={onClose} />)
    expect(screen.getByRole('dialog')).toHaveTextContent('ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567')
    expect(screen.getByRole('dialog')).toHaveTextContent('닫으면 다시 볼 수 없습니다')
    await userEvent.click(screen.getByRole('button', { name: '확인했습니다 — 닫기' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(storageIsEmpty()).toBe(true)
  })

  it('ADMIN-UI-001 review m-4: while open the rest of the page is inert (aria-modal is real) and focus starts on the close button; closing restores it', () => {
    const { unmount } = render(
      <>
        <button type="button">뒤 화면 버튼</button>
        <OneTimeCode code="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" onClose={vi.fn()} />
      </>,
    )
    const dialog = screen.getByRole('dialog')
    const behind = screen.getByText('뒤 화면 버튼')
    expect(behind.closest('[inert]')).not.toBeNull()
    expect(dialog.closest('[inert]')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '확인했습니다 — 닫기' }))
    unmount()
    expect(document.querySelectorAll('[inert]')).toHaveLength(0)
  })
})
