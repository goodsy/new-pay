import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { ReconciliationScreen } from './ReconciliationScreen'
import { apiError, fakeApi } from '../test/fakeApi'
import type { ReconciliationOverview } from '../api/adminApi'

const overview: ReconciliationOverview = {
  openCounts: { MISSING_DECISION: 2, ORPHAN_DECISION: 1 }, openTotal: 3, truncated: false, lastRunAt: '2026-10-28T00:00:00Z', lastFullScanAt: null, lastResult: 'OK',
  findings: [
    { findingId: 1, kind: 'MISSING_DECISION', approvalId: 'apr_1', paymentRef: 'pay_1', detailCode: null, foundAt: '2026-10-28T00:00:00Z' },
    { findingId: 2, kind: 'ORPHAN_DECISION', approvalId: 'apr_2', paymentRef: null, detailCode: 'AMOUNT,REQUESTER', foundAt: '2026-10-28T00:00:00Z' },
  ],
}

describe('ReconciliationScreen (findings — SECURITY_ADMIN·APPROVER, §4.17·§4.20)', () => {
  it('shows the open finding count on top, counts per kind and the rows; there is no resolve or close button', async () => {
    const api = fakeApi({ reconciliation: vi.fn(async () => overview) })
    render(<ReconciliationScreen api={api} />)
    expect(await screen.findByRole('heading', { name: '열린 대사 발견 3건' })).toBeInTheDocument()
    expect(screen.getByText('MISSING_DECISION: 2')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /apr_2/ })).getByText('AMOUNT,REQUESTER')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /해결|종료|닫기/ })).toBeNull()
  })

  it('ITEM_ERROR and FULL_SCAN_INCOMPLETE runs say "investigation needed — no manual close"', async () => {
    for (const lastResult of ['ITEM_ERROR', 'FULL_SCAN_INCOMPLETE']) {
      const api = fakeApi({ reconciliation: vi.fn(async () => ({ ...overview, lastResult })) })
      const view = render(<ReconciliationScreen api={api} />)
      // S8 리뷰 nit: lastResult는 이 인스턴스의 마지막 실행(I-127)이라 표기한다.
      expect(await screen.findByText(`마지막 대사 실행(이 인스턴스 기준): ${lastResult} — 조사 필요 — 수동 종료 없음`)).toBeInTheDocument()
      view.unmount()
    }
  })

  it('a 403 shows the fixed permission message and no rows', async () => {
    const api = fakeApi({ reconciliation: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }) })
    render(<ReconciliationScreen api={api} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(screen.queryByRole('row', { name: /apr_/ })).toBeNull()
  })
})
