import { describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditScreen } from './AuditScreen'
import { apiError, fakeApi } from '../test/fakeApi'
import { settle } from '../test/settle'
import { domOccurrences } from '../test/domScan'
import type { AdminApi, AuditEntry, MerchantListItem, MerchantTargetLabelPage } from '../api/adminApi'

/**
 * MERCHANT-CODE-001 §8 MC-3-19 감사 화면(§5.6.2·§5.6.4): 가맹점 행 = "<현재 상호> · <현재 코드>", 당시 표기 `(당시 <옛 코드>)`(코드 형식 target_ref이고 현재 코드와 다를 때만),
 * `biz_` 행 = "사업자", detail 안 `(mer|biz)_<ULID>` 토큰 → "—"(`title` 포함), 라벨 UNAVAILABLE·429 → "—" + 안내 1줄,
 * "가맹점 코드" 조회: 형식 위반 → 화면 오류·호출 0, 코드 검색 0건 → "없는 가맹점 코드"·감사 호출 0, 1건(은퇴 코드 일치 포함) → `audit({targetMerchantId})` 1회, 검색 실패 → 감사 호출 0.
 * 모든 상태에서 DOM `mer_`·`biz_` 0건. 명시 Fixture: 가맹점 `MER`(현재 데모상점 · newcode01, 옛 코드 oldcode01), 사업자 `BIZ`.
 */
const ulid = (n: number) => `01JC${String(n).padStart(22, '0')}`
const MER = `mer_${ulid(1)}`
const BIZ = `biz_${ulid(1)}`
const NOTE = '가맹점 정보를 불러오지 못했어요'
const base: AuditEntry = {
  eventId: 1, occurredAt: '2026-11-17T01:00:00Z', actorKind: 'ADMIN', actorId: 'adm_A', actorDisplayName: '관리자', executorActorId: null, action: 'APPROVAL_REQUESTED',
  targetActorId: null, resultCode: 'SUCCESS', approvalId: 'apr_1', correlationId: 'c', detail: null, reason: null, targetRef: null, targetMerchantId: null,
}
/** MC-3a 전 행(target_ref mer_), 옛 코드 행, 새 코드 행, 사업자 행, 계정 행, Payment 행. */
const legacy: AuditEntry = { ...base, eventId: 11, action: 'MERCHANT_VIEWED', targetRef: MER, targetMerchantId: MER }
const oldCode: AuditEntry = { ...base, eventId: 12, action: 'APPROVAL_REQUESTED', targetRef: 'oldcode01', targetMerchantId: MER, detail: 'kind=MERCHANT_CODE_CHANGE;from=oldcode01;to=newcode01' }
const newCode: AuditEntry = { ...base, eventId: 13, action: 'APPROVAL_EXECUTED', targetRef: 'newcode01', targetMerchantId: MER }
const business: AuditEntry = { ...base, eventId: 14, action: 'BUSINESS_REGISTERED', targetRef: BIZ, detail: `business=${BIZ};merchant=${MER}` }
const account: AuditEntry = { ...base, eventId: 15, action: 'ROLE_GRANTED', targetActorId: 'adm_T' }
const payment: AuditEntry = { ...base, eventId: 16, action: 'PAYMENT_VIEWED', targetRef: `pay_${ulid(9)}` }
const entries = [legacy, oldCode, newCode, business, account, payment]
// FOLLOWUP-SMALL-001(FLAKY-FIX-001과 같은 방식): fake API는 이미 끝난 Promise(또는 시험이 직접 푼 Promise)로 답하므로 findBy*·vi.waitFor의 1초 벽시계 polling 대신
// settle()(act flush)로 응답 반영을 기다린 뒤 getBy*로 한 번만 질의한다 — 게이트 부하에서 polling 1초가 넘쳤다(단언·기대값 그대로). 입력은 지연 없이(TEST-PERF-003과 같은
// `delay: null`) 해 키 입력 사이 setTimeout(0)이 부하에서 5초 시험 제한을 넘지 않게 한다.
const user = userEvent.setup({ delay: null })
const rowOf = async (action: string) => {
  await settle()
  return screen.getByRole('row', { name: new RegExp(`^${action} `) })
}
const okLabels = vi.fn(async (ids: string[]): Promise<MerchantTargetLabelPage> => ({
  items: ids.includes(MER) ? [{ merchantId: MER, name: '데모상점', code: 'newcode01' }] : [], labels: 'OK',
}))
const listItem: MerchantListItem = {
  merchantId: MER, merchantCode: 'newcode01', matchedRetiredCode: true, merchantName: '데모상점', businessId: BIZ, businessName: '데모 사업자', registrationNumber: '1234567890',
  lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00Z',
}

function renderAudit(extra: Partial<AdminApi> = {}) {
  const onSessionEnded = vi.fn()
  const api = fakeApi({ audit: vi.fn(async () => ({ entries })), merchantLabels: okLabels, ...extra })
  render(<AuditScreen api={api} onSessionEnded={onSessionEnded} />)
  return { api, onSessionEnded }
}

const noInternalKeys = () => {
  expect(domOccurrences(document.body, 'mer_')).toEqual([])
  expect(domOccurrences(document.body, 'biz_')).toEqual([])
}

describe('MC-3-19 감사 화면 — 대상 칸', () => {
  it('merchant rows (mer_·old code·new code) show the current label, the old-code row adds "(당시 oldcode01)"; biz_ → "사업자"; account·payment unchanged; one label call', async () => {
    const { api } = renderAudit()
    const legacyRow = await rowOf('MERCHANT_VIEWED')
    expect(within(legacyRow).getByText('데모상점 · newcode01')).toBeInTheDocument()
    expect(within(legacyRow).queryByText(/당시/)).toBeNull()
    const old = await rowOf('APPROVAL_REQUESTED')
    expect(within(old).getByText('데모상점 · newcode01')).toBeInTheDocument()
    expect(within(old).getByText('(당시 oldcode01)')).toBeInTheDocument()
    const fresh = await rowOf('APPROVAL_EXECUTED')
    expect(within(fresh).getByText('데모상점 · newcode01')).toBeInTheDocument()
    expect(within(fresh).queryByText(/당시/)).toBeNull()
    expect(within(await rowOf('BUSINESS_REGISTERED')).getByText('사업자')).toBeInTheDocument()
    expect(within(await rowOf('ROLE_GRANTED')).getByText('adm_T')).toBeInTheDocument()
    expect(within(await rowOf('PAYMENT_VIEWED')).getByText(`pay_${ulid(9)}`)).toBeInTheDocument()
    expect(api.merchantLabels).toHaveBeenCalledTimes(1)
    expect(api.merchantLabels).toHaveBeenCalledWith([MER])
    noInternalKeys()
  })

  it('detail tokens (mer|biz)_<ULID> render as "—" in the text and the title', async () => {
    renderAudit()
    const row = await rowOf('BUSINESS_REGISTERED')
    const summary = within(row).getByText('business=—;merchant=—')
    expect(summary).toHaveAttribute('title', 'business=—;merchant=—')
    expect(within(await rowOf('APPROVAL_REQUESTED')).getByText('kind=MERCHANT_CODE_CHANGE;from=oldcode01;to=newcode01')).toBeInTheDocument()
    noInternalKeys()
  })

  it('reviewFix1 NIT-1: a lowercase / short internal token the pattern misses → the whole summary is "—" in the text and the title', async () => {
    const odd: AuditEntry = { ...base, eventId: 21, action: 'ODD_DETAIL', detail: `merchant=${MER.toLowerCase()};short=mer_AB;code=demoshop01` }
    renderAudit({ audit: vi.fn(async () => ({ entries: [odd] })) })
    const row = await rowOf('ODD_DETAIL')
    const cells = within(row).getAllByRole('cell')
    const summary = cells[cells.length - 1]!
    expect(summary).toHaveTextContent(/^—$/)
    expect(summary).toHaveAttribute('title', '—')
    noInternalKeys()
  })

  it('label UNAVAILABLE and 429 → merchant cells "—" + one note line; no alert, no session ending; business row still "사업자"', async () => {
    for (const merchantLabels of [
      vi.fn(async (): Promise<MerchantTargetLabelPage> => ({ items: [], labels: 'UNAVAILABLE' })),
      vi.fn(async (): Promise<MerchantTargetLabelPage> => { throw apiError(429, 'RATE_LIMITED') }),
    ]) {
      const { onSessionEnded } = renderAudit({ merchantLabels })
      await settle()
      expect(screen.getByText(NOTE)).toHaveAttribute('role', 'note')
      expect(within(await rowOf('MERCHANT_VIEWED')).getByText('—')).toBeInTheDocument()
      expect(within(await rowOf('APPROVAL_REQUESTED')).queryByText(/당시/)).toBeNull()
      expect(within(await rowOf('BUSINESS_REGISTERED')).getByText('사업자')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).toBeNull()
      expect(onSessionEnded).not.toHaveBeenCalled()
      noInternalKeys()
      cleanup()
    }
  })

  it('empty result and audit error states also carry no internal key; no label call without merchant rows', async () => {
    const merchantLabels = vi.fn(okLabels)
    renderAudit({ audit: vi.fn(async () => ({ entries: [] })), merchantLabels })
    await settle()
    expect(screen.getByText('조건에 맞는 감사 기록이 없습니다.')).toBeInTheDocument()
    expect(merchantLabels).not.toHaveBeenCalled()
    noInternalKeys()
    cleanup()
    renderAudit({ audit: vi.fn(async () => { throw apiError(503, 'AUDIT_UNAVAILABLE') }) })
    await settle()
    expect(screen.getByRole('alert')).toBeInTheDocument()
    noInternalKeys()
  })
})

describe('MC-3-19 감사 화면 — "가맹점 코드" 조회(§5.6.4 연결 조회)', () => {
  const searchWith = async (code: string) => {
    await user.clear(screen.getByLabelText('가맹점 코드'))
    // 고정 문자열은 붙여넣는다(TEST-PERF-003·FLAKY-FIX-001과 같음) — 판정은 제출 시 최종 입력값으로 하므로 확인 내용 그대로.
    await user.paste(code)
    await user.click(screen.getByRole('button', { name: '조회' }))
  }

  it('format violation → screen error, no search and no audit call', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [listItem], nextCursor: null }))
    const { api } = renderAudit({ merchantSearch })
    await rowOf('MERCHANT_VIEWED')
    const auditCalls = vi.mocked(api.audit).mock.calls.length
    for (const bad of ['Demo_Shop', 'abc', 'a'.repeat(15), '1shop0001']) {
      await searchWith(bad)
      await settle()
      expect(screen.getByText(/가맹점 코드 형식이 아닙니다/)).toBeInTheDocument()
    }
    expect(merchantSearch).not.toHaveBeenCalled()
    expect(api.audit).toHaveBeenCalledTimes(auditCalls)
    noInternalKeys()
  })

  it('0 hits → "없는 가맹점 코드" and no audit call', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [], nextCursor: null }))
    const { api } = renderAudit({ merchantSearch })
    await rowOf('MERCHANT_VIEWED')
    const auditCalls = vi.mocked(api.audit).mock.calls.length
    await searchWith('nosuchcode1')
    await settle()
    expect(screen.getByText('없는 가맹점 코드')).toBeInTheDocument()
    expect(merchantSearch).toHaveBeenCalledWith({ merchantCode: 'nosuchcode1' })
    expect(api.audit).toHaveBeenCalledTimes(auditCalls)
  })

  it('1 hit (a retired code match too) → one audit({targetMerchantId}) call with the internal key, never shown', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [listItem], nextCursor: null }))
    const { api } = renderAudit({ merchantSearch })
    await rowOf('MERCHANT_VIEWED')
    const auditCalls = vi.mocked(api.audit).mock.calls.length
    await searchWith('oldcode01')
    await settle()
    expect(api.audit).toHaveBeenCalledTimes(auditCalls + 1)
    expect(api.audit).toHaveBeenLastCalledWith({ targetMerchantId: MER })
    expect(merchantSearch).toHaveBeenCalledWith({ merchantCode: 'oldcode01' })
    noInternalKeys()
  })

  /** 감사 표의 기록 행(빈 표 안내 행 제외 — 기록 행만 aria-label이 있다). */
  const recordRows = () => screen.queryAllByRole('row').filter((row) => row.closest('tbody') !== null && row.hasAttribute('aria-label'))

  it('reviewFix1 MINOR-1: format error and 0 hits render as the field error (text-destructive), empty the table and call no audit', async () => {
    for (const [code, items, message] of [
      ['Demo_Shop', [listItem], /가맹점 코드 형식이 아닙니다/],
      ['nosuchcode1', [], /^없는 가맹점 코드$/],
    ] as const) {
      const merchantSearch = vi.fn(async () => ({ items: [...items], nextCursor: null }))
      const { api } = renderAudit({ merchantSearch })
      await rowOf('MERCHANT_VIEWED')
      expect(recordRows()).toHaveLength(entries.length)
      const auditCalls = vi.mocked(api.audit).mock.calls.length
      await searchWith(code)
      await settle()
      const error = screen.getByText(message)
      expect(error).toHaveClass('text-destructive')
      expect(screen.getByLabelText('가맹점 코드')).toHaveAttribute('aria-invalid', 'true')
      expect(screen.getByLabelText('가맹점 코드')).toHaveAttribute('aria-describedby', error.id)
      expect(recordRows()).toHaveLength(0)
      expect(api.audit).toHaveBeenCalledTimes(auditCalls)
      noInternalKeys()
      cleanup()
    }
  })

  it('reviewFix1 MINOR-2: a slow code lookup that lands after a newer (empty) search is discarded — the table keeps the full result; busy while searching', async () => {
    let releaseSearch: (page: { items: MerchantListItem[]; nextCursor: null }) => void = () => undefined
    const merchantSearch = vi.fn(() => new Promise<{ items: MerchantListItem[]; nextCursor: null }>((resolve) => { releaseSearch = resolve }))
    const audit = vi.fn(async (filter: { targetMerchantId?: string }) => ({ entries: filter.targetMerchantId ? [legacy] : entries }))
    renderAudit({ merchantSearch, audit })
    await rowOf('MERCHANT_VIEWED')
    await searchWith('slowcode01')
    expect(screen.getByRole('button', { name: '조회' })).toHaveAttribute('aria-busy', 'true')
    await user.clear(screen.getByLabelText('가맹점 코드'))
    await user.click(screen.getByRole('button', { name: '조회' }))
    await settle()
    expect(audit).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: '조회' })).toHaveAttribute('aria-busy', 'false')
    expect(recordRows()).toHaveLength(entries.length)
    releaseSearch({ items: [listItem], nextCursor: null })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(audit).toHaveBeenCalledTimes(2)
    expect(audit).not.toHaveBeenCalledWith({ targetMerchantId: MER })
    expect(recordRows()).toHaveLength(entries.length)
    expect(screen.getByRole('button', { name: '조회' })).toHaveAttribute('aria-busy', 'false')
    noInternalKeys()
  })

  it('reviewFix1 MINOR-2: a slow audit result that lands after a newer search is discarded', async () => {
    let releaseOld: (page: { entries: AuditEntry[] }) => void = () => undefined
    const audit = vi.fn((filter: { action?: string }) =>
      filter.action === 'SLOW' ? new Promise<{ entries: AuditEntry[] }>((resolve) => { releaseOld = resolve }) : Promise.resolve({ entries }))
    renderAudit({ audit })
    await rowOf('MERCHANT_VIEWED')
    await user.type(screen.getByLabelText('행위'), 'SLOW')
    await user.click(screen.getByRole('button', { name: '조회' }))
    await user.clear(screen.getByLabelText('행위'))
    await user.click(screen.getByRole('button', { name: '조회' }))
    await settle()
    expect(audit).toHaveBeenCalledTimes(3)
    expect(screen.getByRole('button', { name: '조회' })).toHaveAttribute('aria-busy', 'false')
    releaseOld({ entries: [payment] })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(recordRows()).toHaveLength(entries.length)
  })

  it('search failure → error notice and no audit call', async () => {
    const merchantSearch = vi.fn(async () => { throw apiError(503, 'MASTER_UNAVAILABLE') })
    const { api } = renderAudit({ merchantSearch })
    await rowOf('MERCHANT_VIEWED')
    const auditCalls = vi.mocked(api.audit).mock.calls.length
    await searchWith('demoshop01')
    await settle()
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(api.audit).toHaveBeenCalledTimes(auditCalls)
    noInternalKeys()
  })
})
