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
})
