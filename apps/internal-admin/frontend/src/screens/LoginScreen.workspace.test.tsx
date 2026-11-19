import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { LoginScreen } from './LoginScreen'
import type { Me } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * ADMIN-UI-WORKSPACE-001 WS9 로그인("모양만"): 참고 시안에는 로그인 화면이 없어 시안 카드(.card > .cardtop h2 + .cardbody)·버튼(.btn·.btn.primary)·
 * 보조 글자(.subtle)로 구성한다. 브랜드는 로그인 전 상단 바의 시안 `.brand`(i + 콘솔 이름) 그대로.
 * 2단계 로그인 동작(CSRF → 비밀번호 → TOTP, 실패 시 비밀 입력 지움, 같은 오류 문구, 세션 종료 안내)은 LoginScreen.test.tsx·App.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture: 세션 없음(me = 401 UNAUTHENTICATED), 로그인 성공 계정 1명(보안 관리자).
 */
const me: Me = { actorId: 'adm_01', displayName: '보안 관리자', roles: ['SECURITY_ADMIN'] }
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

function stubMatchMedia(dark: boolean) {
  const list = { matches: dark, media: '(prefers-color-scheme: dark)', addEventListener: vi.fn(), removeEventListener: vi.fn() }
  vi.stubGlobal('matchMedia', vi.fn(() => list))
}

function signedOut(overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ me: vi.fn(async () => { throw apiError(401, 'UNAUTHENTICATED') }), ...overrides })
  render(<App api={api} />)
  return api
}

/** 바깥에서 끝낼 수 있는 요청(진행 중 표시 확인용). */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('ADMIN-UI-WORKSPACE-001 WS9: 로그인 in the reference card layout', () => {
  it('pre-login shell: header .brand (i + PayCore 관리자) then main > section.card(.cardtop h2 → .cardbody → .cardnote); no shadcn card, no inline style', async () => {
    signedOut()
    const name = await screen.findByLabelText('로그인 이름')
    const brand = screen.getByRole('banner').querySelector(':scope > .brand')!
    expect(shape(brand)).toEqual(['i', 'h1'])
    expect(brand.querySelector('i')).toHaveAttribute('aria-hidden', 'true')
    expect(within(brand as HTMLElement).getByRole('heading', { level: 1, name: 'PayCore 관리자' })).toBeInTheDocument()

    const card = screen.getByRole('main').querySelector(':scope > section.card')!
    expect(card).toContainElement(name)
    // .cardnote(flex-col)의 버튼은 늘이지 않고 가운데(items-center utility — 기존 카드 아래 링크 정렬)
    expect(shape(card)).toEqual(['div.cardtop', 'div.cardbody', 'div.cardnote.items-center'])
    expect(card.querySelector('.cardtop > h2')).toHaveTextContent('로그인')
    expect(card.querySelectorAll('[data-slot^="card"]')).toHaveLength(0)

    // 단계 안내는 .cardbody 첫 줄 .subtle(문구 그대로)
    const body = card.querySelector('.cardbody') as HTMLElement
    expect(body.firstElementChild).toBe(body.querySelector(':scope > p.subtle'))
    expect(body.firstElementChild).toHaveTextContent('1단계 — 관리자 로그인 이름과 비밀번호를 입력하세요.')

    // 보이는 label(접근 가능한 이름 그대로)·자동 완성 속성 그대로
    const password = within(body).getByLabelText('비밀번호')
    for (const [input, label] of [[name, '로그인 이름'], [password, '비밀번호']] as const) {
      expect(input.closest('label')!.querySelector('span')).toHaveTextContent(label)
    }
    expect(name).toHaveAttribute('autocomplete', 'username')
    expect(name).toBeRequired()
    expect(password).toHaveAttribute('type', 'password')
    expect(password).toHaveAttribute('autocomplete', 'current-password')
    expect(password).toBeRequired()

    const next = within(body).getByRole('button', { name: '다음' })
    expect(next).toHaveClass('btn', 'primary', 'w-full')
    expect(next).toHaveAttribute('type', 'submit')
    // 등록 코드 링크는 카드 아래 .cardnote의 .btn(로그인 입력 뒤 — 탭 순서 그대로)
    const enroll = within(card.querySelector('.cardnote') as HTMLElement).getByRole('button', { name: '등록 코드로 계정 등록' })
    expect(enroll).toHaveClass('btn')
    expect(enroll).not.toHaveClass('primary')
    expect(enroll).toHaveAttribute('type', 'button')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)

    await userEvent.click(enroll)
    expect(await screen.findByLabelText('등록 코드')).toBeInTheDocument()
    expect(screen.getByRole('main').querySelector(':scope > section.card > .cardtop > h2')).toHaveTextContent('계정 등록')
  })

  it('no spinner: a visible aria-live "확인 중…" while each step is pending (button disabled + aria-busy); the TOTP field keeps its attributes and hint', async () => {
    const pwd = deferred<{ next: string }>()
    const otp = deferred<Me>()
    const onAuthenticated = vi.fn()
    const api = fakeApi({ password: vi.fn(() => pwd.promise), totp: vi.fn(() => otp.promise) })
    render(<LoginScreen api={api} onAuthenticated={onAuthenticated} onEnroll={vi.fn()} />)
    // reviewFix1 NIT-1: 진행 글자는 버튼과 같은 줄 묶음(div) 안 block span — 비어 있어도 DOM에 남지만(live region) form gap을 하나 더 만들지 않는다.
    const live = () => document.querySelector('.cardbody form > div > span.subtle.block[aria-live="polite"]')!
    const row = () => live().parentElement!
    expect(live()).toBeInTheDocument()
    expect(live()).toHaveTextContent('')
    expect(row().parentElement!.lastElementChild).toBe(row())
    expect(row().firstElementChild).toBe(screen.getByRole('button', { name: '다음' }))
    await userEvent.type(screen.getByLabelText('로그인 이름'), 'sa-1')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'secret-password')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(live()).toHaveTextContent('확인 중…')
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음' })).toHaveAttribute('aria-busy', 'true')
    expect(document.querySelector('.animate-spin')).toBeNull()
    pwd.resolve({ next: 'TOTP' })

    const code = await screen.findByLabelText('인증 코드(6자리)')
    expect(document.querySelector('.cardbody > p.subtle')).toHaveTextContent('2단계 — OTP 앱의 6자리 코드를 입력하세요.')
    expect(code.closest('label')!.querySelector('span')).toHaveTextContent('인증 코드(6자리)')
    expect(code).toHaveAttribute('inputmode', 'numeric')
    expect(code).toHaveAttribute('pattern', '[0-9]{6}')
    expect(code).toHaveAttribute('maxlength', '6')
    expect(code).toHaveAttribute('autocomplete', 'one-time-code')
    expect(code).toHaveAccessibleDescription('인증 앱에 지금 보이는 코드를 입력하세요. 틀리면 비밀번호 단계부터 다시 합니다.')
    expect(live()).toBeInTheDocument()
    expect(live()).toHaveTextContent('')
    expect(row().firstElementChild).toBe(screen.getByRole('button', { name: '로그인' }))
    await userEvent.type(code, '123456')
    const login = screen.getByRole('button', { name: '로그인' })
    expect(login).toHaveClass('btn', 'primary', 'w-full')
    await userEvent.click(login)
    expect(live()).toHaveTextContent('확인 중…')
    expect(screen.getByRole('button', { name: '로그인' })).toHaveAttribute('aria-busy', 'true')
    otp.resolve(me)
    await vi.waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith(me))
    expect(storageIsEmpty()).toBe(true)
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('session-ended notice, unresolved note and the error alert stay in .cardbody above the form (same roles and texts)', async () => {
    const api = fakeApi({ password: vi.fn(async () => { throw apiError(401, 'INVALID_CREDENTIALS') }) })
    render(<LoginScreen api={api} onAuthenticated={vi.fn()} onEnroll={vi.fn()} notice="SESSION_REPLACED" unresolvedNotice />)
    const body = document.querySelector('section.card > .cardbody') as HTMLElement
    expect(within(body).getByRole('status')).toHaveTextContent('다른 곳에서 로그인')
    expect(within(body).getByRole('note', { name: '결과 확인 필요' })).toHaveTextContent('결과 확인이 필요한 요청이 있습니다 — 가맹점 조회로 확인하세요')
    await userEvent.type(screen.getByLabelText('로그인 이름'), 'x')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    const alert = await within(body).findByRole('alert')
    expect(alert).toHaveTextContent('로그인 정보가 올바르지 않습니다')
    expect(alert.compareDocumentPosition(body.querySelector('form')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByLabelText('비밀번호')).toHaveValue('')
  })

  it('theme works before login: data-theme follows the OS / stored choice on the login screen, and the dark block covers the classes it uses', async () => {
    stubMatchMedia(true)
    const unauthenticated = () => fakeApi({ me: vi.fn(async () => { throw apiError(401, 'UNAUTHENTICATED') }) })
    const first = render(<App api={unauthenticated()} />)
    await screen.findByLabelText('로그인 이름')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(localStorage.length).toBe(0)
    first.unmount()
    localStorage.setItem('paycore-admin-theme', 'light')
    render(<App api={unauthenticated()} />)
    await screen.findByLabelText('로그인 이름')
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    for (const selector of ['body', 'header', '.brand', 'h2', '.card', '.subtle', '.btn', '.btn.primary', '.notice']) {
      expect(WORKSPACE_CSS).toMatch(new RegExp(`:root\\[data-theme=dark\\] ${selector.replace(/\./g, '\\.')}[,{ ]`))
    }
  })
})
