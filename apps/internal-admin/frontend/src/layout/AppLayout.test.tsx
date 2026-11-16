import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { AppLayout } from './AppLayout'
import { App } from '../App'
import { fakeApi } from '../test/fakeApi'

describe('AppLayout', () => {
  it('renders the console title and the main landmark with its content', () => {
    render(<AppLayout><p>본문</p></AppLayout>)
    expect(screen.getByRole('banner')).toHaveTextContent('PayCore 관리자')
    expect(screen.getByRole('main')).toHaveTextContent('본문')
  })

  it('App renders inside the layout', () => {
    render(<App />)
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('ADMIN-UI-001 rev 0.4 (D-ADMIN-UI-003): signed in, the side area holds the brand, the grouped menu and the account (name, roles, logout); the top bar holds the breadcrumb and the menu search', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '감사자', roles: ['VIEWER'] })),
      audit: vi.fn(async () => ({ entries: [] })),
    })
    render(<App api={api} />)
    expect(await screen.findByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText('PayCore')).toBeInTheDocument()
    expect(within(aside).getByText('감사자')).toBeInTheDocument()
    expect(within(aside).getByText('역할: 조회자')).toBeInTheDocument()
    expect(within(aside).getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    const nav = within(aside).getByRole('navigation', { name: '메뉴' })
    expect(within(nav).getByRole('button', { name: '운영 홈' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('button', { name: '계정·권한' })).not.toHaveAttribute('aria-current')
    expect(within(nav).queryByRole('button', { name: '로그아웃' })).toBeNull()
    const banner = screen.getByRole('banner')
    expect(within(banner).getByRole('navigation', { name: '경로' })).toHaveTextContent('워크스페이스/운영 홈')
    // 구조 단언 변경(ADMIN-UI-WORKSPACE-001): 메뉴 검색은 참고 시안처럼 상단 바의 검색 버튼이 여는 modal이다(검색란은 modal 안 — 기능 단언은 App.test·WorkspaceShell.test).
    expect(within(banner).getByRole('button', { name: '메뉴 검색' })).toBeInTheDocument()
    expect(within(banner).queryByRole('button', { name: '로그아웃' })).toBeNull()
    // 구조 단언 변경(ADMIN-UI-WORKSPACE-001 2단계): 운영 홈 인사는 참고 시안 문구 "좋은 하루예요, …님".
    expect(screen.getByRole('main')).toContainElement(screen.getByRole('heading', { level: 1, name: '좋은 하루예요, 감사자님' }))
  })
})
