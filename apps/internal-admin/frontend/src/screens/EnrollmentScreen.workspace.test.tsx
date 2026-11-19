import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { EnrollmentScreen } from './EnrollmentScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 WS9 계정 등록("모양만"): 로그인과 같은 시안 카드(.card > .cardtop h2 + .cardbody)·버튼(.btn.primary)·보조 글자(.subtle).
 * 설정 키는 지금처럼 TOTP 단계에서 한 번만, 한 곳에만 보이고(시안 .notice 상자 + 본문 글자 token), 완료·재시작이면 지운다.
 * 등록 흐름(코드 → 비밀번호 → TOTP → 완료, 저장소·URL 없음, 오류 문구)은 EnrollmentScreen.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture: 시험용 고정 otpauth URI(시험 전용 base32 값 — 운영 비밀 아님), 등록 코드 'ABCD EFGH'.
 */
const KEY = 'JBSWY3DPEHPK3PXP'
const URI = `otpauth://totp/PayCore%20Admin:new?secret=${KEY}&issuer=PayCore%20Admin`
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))
const card = () => screen.getByRole('main').querySelector(':scope > section.card')!
const body = () => card().querySelector(':scope > .cardbody') as HTMLElement

describe('ADMIN-UI-WORKSPACE-001 WS9: 계정 등록 in the reference card layout', () => {
  it('opened from login: section.card(.cardtop h2 계정 등록 → .cardbody); each step keeps its fields, attributes and texts; the setup key shows once in .notice; done returns to login', async () => {
    const api = fakeApi({
      me: vi.fn(async () => { throw apiError(401, 'UNAUTHENTICATED') }),
      enrollStart: vi.fn(async () => ({ purpose: 'ENROLL', needsPassword: true, needsTotp: true })),
      enrollPassword: vi.fn(async () => undefined),
      enrollTotp: vi.fn(async () => ({ otpauthUri: URI })),
      enrollComplete: vi.fn(async () => ({ status: 'COMPLETED' })),
    })
    render(<App api={api} />)
    await userEvent.click(await screen.findByRole('button', { name: '등록 코드로 계정 등록' }))

    // ① 등록 코드
    const code = await screen.findByLabelText('등록 코드')
    expect(shape(card())).toEqual(['div.cardtop', 'div.cardbody'])
    expect(card().querySelector('.cardtop > h2')).toHaveTextContent('계정 등록')
    expect(card().querySelectorAll('[data-slot^="card"]')).toHaveLength(0)
    expect(body().firstElementChild).toBe(body().querySelector(':scope > p.subtle'))
    expect(body().firstElementChild).toHaveTextContent('보안 관리자에게 따로 받은 등록 코드를 입력하세요.')
    expect(code.closest('label')!.querySelector('span')).toHaveTextContent('등록 코드')
    expect(code).toHaveAttribute('autocomplete', 'off')
    expect(code).toBeRequired()
    const start = within(body()).getByRole('button', { name: '시작' })
    expect(start).toHaveClass('btn', 'primary', 'w-full')
    expect(start).toHaveAttribute('type', 'submit')
    await userEvent.type(code, 'ABCD EFGH')
    await userEvent.click(start)

    // ② 새 비밀번호
    const password = await screen.findByLabelText('새 비밀번호')
    expect(body().firstElementChild).toHaveTextContent('새로 쓸 비밀번호를 정하세요.')
    expect(password.closest('label')!.querySelector('span')).toHaveTextContent('새 비밀번호')
    expect(password).toHaveAttribute('type', 'password')
    expect(password).toHaveAttribute('autocomplete', 'new-password')
    expect(within(body()).getByRole('button', { name: '비밀번호 설정' })).toHaveClass('btn', 'primary', 'w-full')
    expect(domOccurrences(document.body, 'ABCD')).toEqual([])
    await userEvent.type(password, 'new-password-123')
    await userEvent.click(within(body()).getByRole('button', { name: '비밀번호 설정' }))

    // ③ TOTP 설정 키(한 번, 한 곳) + 인증 코드
    const keyBox = await screen.findByText(KEY)
    expect(body().firstElementChild).toHaveTextContent('OTP 앱에 설정 키를 등록한 뒤, 앱의 6자리 코드를 입력하세요.')
    expect(keyBox.tagName).toBe('P')
    expect(keyBox).toHaveClass('notice', 'text-foreground', 'font-mono', 'break-all')
    // reviewFix1 MINOR-1: .notice 12px → 손으로 옮겨 적기 쉽게 14px(text-sm utility)
    expect(keyBox).toHaveClass('text-sm')
    expect(keyBox).toHaveAttribute('translate', 'no')
    expect(keyBox.previousElementSibling).toHaveClass('subtle')
    expect(keyBox.previousElementSibling).toHaveTextContent('인증 앱에 아래 설정 키를 직접 입력하세요(이 화면에서 한 번만 보입니다).')
    expect(domOccurrences(document.body, KEY)).toHaveLength(2) // 키 상자 텍스트 노드 + 전체 텍스트(같은 한 곳)
    expect(domOccurrences(document.body, 'otpauth')).toEqual([])
    const totp = screen.getByLabelText('인증 코드(6자리)')
    expect(totp.closest('label')!.querySelector('span')).toHaveTextContent('인증 코드(6자리)')
    expect(totp).toHaveAttribute('inputmode', 'numeric')
    expect(totp).toHaveAttribute('maxlength', '6')
    expect(totp).toHaveAttribute('autocomplete', 'one-time-code')
    expect(within(body()).getByRole('button', { name: '등록 완료' })).toHaveClass('btn', 'primary', 'w-full')
    await userEvent.type(totp, '654321')
    await userEvent.click(within(body()).getByRole('button', { name: '등록 완료' }))

    // ④ 완료 — 설정 키 지움, 로그인 화면으로
    expect(await within(body()).findByRole('status')).toHaveTextContent('등록이 끝났습니다. 새 자격으로 로그인해 주세요.')
    expect(domOccurrences(document.body, KEY)).toEqual([])
    const back = within(body()).getByRole('button', { name: '로그인 화면으로' })
    expect(back).toHaveClass('btn', 'primary', 'w-full')
    expect(back).toHaveAttribute('type', 'button')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    expect(storageIsEmpty()).toBe(true)
    await userEvent.click(back)
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
    expect(card().querySelector('.cardtop > h2')).toHaveTextContent('로그인')
  })

  it('a restart error shows the fixed alert in .cardbody, drops the setup key and returns to the code step', async () => {
    const api = fakeApi({
      enrollStart: vi.fn(async () => ({ purpose: 'TOTP_REENROLL', needsPassword: false, needsTotp: true })),
      enrollTotp: vi.fn(async () => ({ otpauthUri: URI })),
      enrollComplete: vi.fn(async () => { throw apiError(400, 'ENROLLMENT_STEP_INVALID') }),
    })
    render(
      <main>
        <EnrollmentScreen api={api} onDone={vi.fn()} />
      </main>,
    )
    await userEvent.type(screen.getByLabelText('등록 코드'), 'CODE')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(await screen.findByText(KEY)).toHaveClass('notice')
    await userEvent.type(screen.getByLabelText('인증 코드(6자리)'), '111111')
    await userEvent.click(screen.getByRole('button', { name: '등록 완료' }))
    expect(await within(body()).findByRole('alert')).toHaveTextContent('처음부터 다시')
    expect(domOccurrences(document.body, KEY)).toEqual([])
    expect(screen.getByLabelText('등록 코드')).toHaveValue('')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })
})
