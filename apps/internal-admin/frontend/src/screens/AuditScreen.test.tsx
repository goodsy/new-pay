import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditScreen } from './AuditScreen'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

const entry = {
  eventId: 7, occurredAt: '2026-10-26T01:00:00Z', actorKind: 'ADMIN', actorId: 'adm_A', actorDisplayName: '관리자', executorActorId: null, action: 'APPROVAL_EXECUTED',
  targetActorId: 'adm_T', resultCode: 'SUCCESS', approvalId: 'apr_1', correlationId: 'c', detail: 'kind=ROLE_GRANT', reason: null,
}

describe('AuditScreen', () => {
  it('lists entries and filters by action (only filters are sent — the scope is the server’s)', async () => {
    const api = fakeApi({ audit: vi.fn(async () => ({ entries: [entry] })) })
    render(<AuditScreen api={api} />)
    expect(await screen.findByRole('row', { name: /APPROVAL_EXECUTED/ })).toBeInTheDocument()
    expect(api.audit).toHaveBeenLastCalledWith({})
    await userEvent.type(screen.getByLabelText('행위'), 'LOGIN_SUCCEEDED')
    await userEvent.click(screen.getByRole('button', { name: '조회' }))
    expect(api.audit).toHaveBeenLastCalledWith({ action: 'LOGIN_SUCCEEDED' })
  })

  it('shows AUDIT_UNAVAILABLE as a fixed message (no results without the view record)', async () => {
    const api = fakeApi({ audit: vi.fn(async () => { throw apiError(503, 'AUDIT_UNAVAILABLE') }) })
    render(<AuditScreen api={api} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('감사 기록을 남길 수 없어 조회하지 않았습니다')
  })

  it('L-13 (MERCHANT-OPS-002 B2-2 Demo C-3): a non-account target (merchant, payment, file) shows in the target column; the long summary wraps and keeps the full text as a tooltip', async () => {
    // 응답 Fixture: MC-3a 서버 응답의 targetMerchantId(= target_merchant_key, 내부 키 — 응답 포함·화면 미표시). 기대값 아님.
    const merchantRow = {
      ...entry, eventId: 8, action: 'APPROVAL_REQUESTED', targetActorId: null, targetRef: 'mer_01JCME00000000000000000001', targetMerchantId: 'mer_01JCME00000000000000000001',
      detail: 'kind=MERCHANT_CONTRACT_APPROVAL;reason=APPROVER_IS_REGISTRANT',
    }
    const merchantLabels = vi.fn(async (ids: string[]) => ({ items: ids.map((merchantId) => ({ merchantId, name: '데모상점', code: 'demoshop01' })), labels: 'OK' as const }))
    const api = fakeApi({ audit: vi.fn(async () => ({ entries: [merchantRow] })), merchantLabels })
    render(<AuditScreen api={api} />)
    const row = await screen.findByRole('row', { name: /APPROVAL_REQUESTED/ })
    // 기대값 변경(MERCHANT-CODE-001 §8 MC-3-20·§5.6.2): D-ADMIN-UI-004 ③·D-MCODE-003 ① — 화면에 내부 키 미표시(rev 0.4.6 §5.6, APPROVAL-TARGET-KEYS-001 합침).
    // getByText('mer_01JC…') → 라벨 텍스트. L-13 "비계정 대상이 대상 칸에 보인다" 의도는 유지 — Payment·파일 대상은 원문 그대로(MC-3-19 AuditScreen.targetLabels 시험).
    expect(await within(row).findByText('데모상점 · demoshop01')).toBeInTheDocument()
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    const summary = within(row).getByText(merchantRow.detail)
    expect(summary).toHaveAttribute('title', merchantRow.detail)
    expect(summary.className).toContain('whitespace-normal')
    expect(summary.className).toContain('break-all')
  })

  it('ADMIN-UI-001: the action shows its Korean name with the raw code beside it, the time in KST with the ISO in its title', async () => {
    const api = fakeApi({ audit: vi.fn(async () => ({ entries: [entry] })) })
    render(<AuditScreen api={api} />)
    const row = await screen.findByRole('row', { name: /APPROVAL_EXECUTED/ })
    expect(within(row).getByText('승인 — 실행됨')).toBeInTheDocument()
    expect(within(row).getByText('APPROVAL_EXECUTED')).toBeInTheDocument()
    expect(within(row).getByText('2026-10-26 10:00:00')).toHaveAttribute('title', '2026-10-26T01:00:00Z')
    expect(within(row).getByText('성공')).toHaveAttribute('title', 'SUCCESS')
  })
})
