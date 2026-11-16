import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { ReconciliationScreen } from './ReconciliationScreen'
import type { AdminApi, ReconciliationOverview } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 매입·대사 › 대사 발견("모양만"): 거래 조회(16cc494)와 같이 참고 시안 moduleView(카드 안 탭 → .mapnote → 카드 제목 → 표 → .tablebottom)를
 * 실제 대사 발견 화면에 입힌다. 기능·문구·오류 처리·"해결·종료 버튼 없음"은 ReconciliationScreen.test.tsx가 그대로 지킨다.
 * Demo 식별자는 업무 의미 없는 고정값이다(발견 2건 — 종류별 수의 합 3은 열린 발견 수와 같다).
 */
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/

const overview: ReconciliationOverview = {
  openCounts: { MISSING_DECISION: 2, ORPHAN_DECISION: 1 }, openTotal: 3, truncated: false, lastRunAt: '2026-10-28T00:00:00Z', lastFullScanAt: null, lastResult: 'OK',
  findings: [
    { findingId: 1, kind: 'MISSING_DECISION', approvalId: 'apr_1', paymentRef: 'pay_1', detailCode: null, foundAt: '2026-10-28T00:00:00Z' },
    { findingId: 2, kind: 'ORPHAN_DECISION', approvalId: 'apr_2', paymentRef: null, detailCode: 'AMOUNT,REQUESTER', foundAt: '2026-10-28T00:00:00Z' },
  ],
}

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '보안 관리자', roles: ['SECURITY_ADMIN'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  return render(<App api={api} />)
}

describe('ADMIN-UI-WORKSPACE-001: 매입·대사 › 대사 발견 in the reference list layout', () => {
  it('flush in the menu card: tabpanel > .mapnote(h2) → .cardtop(h3 open count) → .cardbody(kinds as tags, last run) → .tablewrap → .tablebottom', async () => {
    signedIn({ reconciliation: vi.fn(async () => overview) })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '매입·대사' }))
    expect(screen.getByRole('tab', { name: '대사 발견' })).toHaveAttribute('aria-selected', 'true')
    await screen.findByRole('heading', { name: '열린 대사 발견 3건' })
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    expect(Array.from(panel.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))).toEqual(
      ['div.mapnote', 'div.cardtop', 'div.cardbody', 'div.tablewrap', 'div.tablebottom'],
    )
    expect(within(panel.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '대사 발견' })).toBeInTheDocument()
    expect(panel.querySelector('.cardtop > h3')).toHaveTextContent('열린 대사 발견 3건')

    const kinds = within(panel.querySelector('.cardbody') as HTMLElement).getByRole('list', { name: '종류별 열린 발견' })
    const missing = within(kinds).getByText('MISSING_DECISION: 2').closest('li')!
    expect(missing.querySelector('.tag.orange')).toHaveAttribute('title', 'MISSING_DECISION')

    const wrap = panel.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('대사 발견 목록')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['종류', '승인 ID', '결제', '세부(열 이름·code)', '발견 시각'])
    const row = screen.getByRole('row', { name: /apr_2/ })
    expect(row.querySelector('td .tag.orange')).toHaveAttribute('title', 'ORPHAN_DECISION')
    expect(panel.querySelector('.tablebottom')).toHaveTextContent('표시 2건')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('no findings: the table wrapper shows one .empty line; an investigate run is a warning in the card body', async () => {
    const { container } = render(<ReconciliationScreen api={fakeApi({ reconciliation: vi.fn(async () => ({ ...overview, openCounts: {}, openTotal: 0, findings: [], lastResult: 'ITEM_ERROR' })) })} />)
    await screen.findByRole('heading', { name: '열린 대사 발견 0건' })
    expect(container.querySelector('.tablewrap > .empty')).toHaveTextContent('열린 대사 발견이 없습니다.')
    expect(container.querySelector('.cardbody')).toHaveTextContent('ITEM_ERROR — 조사 필요 — 수동 종료 없음')
  })

  it('an error is one alert in a .cardnote under the map note, and nothing else is drawn', async () => {
    const { container } = render(<ReconciliationScreen api={fakeApi({ reconciliation: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }) })} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(Array.from(container.children).map((c) => c.className)).toEqual(['mapnote', 'cardnote'])
  })

  it('stylesheet: the card-title h3 rule lives in the PayCore adaptation block with existing values (no new color)', () => {
    const block = WORKSPACE_CSS.slice(WORKSPACE_CSS.indexOf('PayCore 적응 규칙'), WORKSPACE_CSS.indexOf('라이트 대비 보정'))
    expect(block).toMatch(/^\.cardtop h3\{margin:0;font-size:17px;font-weight:700;color:#0f172a\}$/m)
    expect(WORKSPACE_CSS).toMatch(/:root\[data-theme=dark\] \.cardtop h3[,{]/)
  })
})
