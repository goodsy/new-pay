import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AcquisitionsScreen } from './AcquisitionsScreen'
import type { AcquisitionFileDetail, AcquisitionFileItem, AcquisitionRecord, ReacquisitionTargets } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { settle } from '../test/settle'

/**
 * 매입 현황 재매입 요청(ACQ-ADMIN-001 B1-A §5.1 — OPERATOR, D-ACQA-002·003·I-8). 명시 Fixture: 원 파일 001의 SALE 1·3이 기관 거절(세대 0, 원승인 10,000원), 레코드 3은 취소 5,000원을 받아 예상
 * 순액 5,000원, 레코드 2는 매입 확정. 승인 뒤 같은 업무일 002(재매입 — 원 파일 표시)가 목록에 보인다.
 */
// 게이트 부하(Kotlin 시험 동시 실행)에서 5초 제한을 넘지 않도록 지연 없이 입력하고 고정 문자열은 붙여넣는다(TEST-PERF-003). FLAKY-FIX-001: 카드 번호 경고도 붙여넣기로 확인하고
// (경고·버튼 상태는 최종 입력값으로 정해진다 — 확인 내용 그대로), fake API 응답 반영은 findBy* polling 대신 settle()로 기다린 뒤 한 번만 질의한다.
const user = userEvent.setup({ delay: null })
const NOW = Date.parse('2026-11-06T01:00:00.000Z')
const F1 = 'ACQ.SIMACQA.TEST.20261105.001'
const F2 = 'ACQ.SIMACQA.TEST.20261105.002'

const file = (fileId: string, patch: Partial<AcquisitionFileItem> = {}): AcquisitionFileItem => ({
  fileId, acquirerCode: 'SIMACQA', environment: 'TEST', businessDate: '2026-11-05', fileVersion: 1, originalFileId: null, recordCount: 3, saleCount: 3,
  saleAmountMinor: 30000, cancelCount: 0, cancelAmountMinor: 0, netAmountMinor: 30000, currency: 'KRW', transmissionState: 'TRANSMITTED', receiptState: 'RECEIVED',
  resultState: 'COMPLETE', sealedAt: '2026-11-05T14:05:00.000Z', transmittedAt: '2026-11-05T14:06:00.000Z',
  recordStatusCounts: { awaiting: 0, accepted: 1, rejected: 2, fileRejected: 0, unknown: 0, conflict: 0 }, attention: [], reacquisitionCandidate: true, reacquisitionInProgress: false, ...patch,
})
const record = (seq: number, status: string, patch: Partial<AcquisitionRecord> = {}): AcquisitionRecord => ({
  seq, recordType: 'SALE', transactionId: `ptx_ex${seq}`, originalTransactionId: null, merchantAccountId: 'demo-mid-001', institutionTransactionDate: '2026-11-05',
  eventAt: '2026-11-05T01:02:03.456Z', currency: 'KRW', approvedAmountMinor: 10000, nettedCancelAmountMinor: 0, recordAmountMinor: 10000, generation: 0, reacquisitionOf: null,
  status, results: [{ entryNo: 1, result: status === 'ACCEPTED' ? 'ACCEPTED' : 'REJECTED', observedResult: null, reasonCode: status === 'ACCEPTED' ? null : '05', source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 1 }, recognizedAt: '2026-11-05T15:00:00.000Z' }],
  ...patch,
})
const detail = (records: AcquisitionRecord[]): AcquisitionFileDetail => ({
  file: { ...file(F1), cutoffPolicyVersion: 'acq-cutoff-demo-1', cutoffAt: '2026-11-05T14:00:00.000Z', contentSha256: 'a'.repeat(64), contentBytes: 600, transmissionLeaseUntil: null, updatedAt: '2026-11-05T15:00:00.000Z' },
  attempts: { total: 0, latest: [] },
  responses: { total: 0, quarantined: 0, latest: [] },
  records: { items: records, nextCursor: null },
  readAt: '2026-11-06T01:00:00.000Z',
})
const targets = (...items: ReacquisitionTargets['items']): ReacquisitionTargets => ({ fileId: F1, readAt: '2026-11-06T01:00:00.000Z', items })
const eligible = (seq: number, cancelled = 0) => ({
  seq, transactionId: `ptx_ex${seq}`, recordType: 'SALE', generation: 0, status: 'REJECTED', eligible: true, refusal: null, approvedAmountMinor: 10000,
  cancelledAmountMinor: cancelled, expectedNetMinor: 10000 - cancelled,
})

async function fill(field: HTMLElement, text: string) {
  await user.click(field)
  await user.paste(text)
}

async function openDetail(api: ReturnType<typeof fakeApi>, canRequest: boolean) {
  render(<AcquisitionsScreen api={api} canRequest={canRequest} now={() => NOW} />)
  await user.click(screen.getByRole('button', { name: '검색' }))
  await settle()
  await user.click(within(screen.getByRole('row', { name: F1 })).getByRole('button', { name: '상세' }))
  await settle()
}

describe('AcquisitionsScreen re-acquisition request (ACQ-ADMIN-001 B1-A §5.1)', () => {
  const rows = [record(1, 'REJECTED'), record(2, 'ACCEPTED'), record(3, 'REJECTED')]

  it('an OPERATOR selects rejected records, previews them with FINANCIAL-computed eligibility and expected net, then requests a two-person approval', async () => {
    const reacquisitionTargets = vi.fn(async () => targets(eligible(1), eligible(3, 5000)))
    const requestReacquisition = vi.fn(async () => ({ approvalId: 'apr_01JC0000000000000000000A01' }))
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)),
      reacquisitionTargets, requestReacquisition,
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    // 매입 확정 레코드 2는 후보가 아니다(표시 편의 — 권위는 미리 보기).
    expect(within(panel).queryByLabelText('레코드 2')).toBeNull()
    expect(within(panel).getByRole('button', { name: '대상 미리 보기' })).toBeDisabled()
    await user.click(within(panel).getByLabelText('레코드 1'))
    await user.click(within(panel).getByLabelText('레코드 3'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    expect(reacquisitionTargets).toHaveBeenCalledWith(F1, [1, 3])
    await settle()
    const preview = within(panel).getByRole('table', { name: '재매입 대상 미리 보기' })
    // 레코드 3: 원승인 10,000 · 받은 취소 5,000 · 예상 순액 5,000(표시용).
    expect(within(within(preview).getByRole('row', { name: '미리 보기 3' })).getAllByText('5,000원')).toHaveLength(2)
    const submit = within(panel).getByRole('button', { name: '재매입 승인 요청' })
    expect(submit).toBeDisabled()
    expect(within(panel).getByText(/금액은 실행 시점의 순액\(원승인 − 받은 취소\)으로 정해집니다/)).toBeInTheDocument()
    // ACQ-ADMIN-001 B2(B1 리뷰 m-A, Coordinator 결정 (b), I-24 갱신): 사유 문구는 ADMIN 명령 행에만 저장되고 승인 상세·근거 조회(D-ACQA-005 — 근거 참조만)·감사 어디에도
    // 보이지 않는다. 힌트가 그 사실대로 말한다("승인자와 감사에만 남습니다"는 사실과 달랐다).
    expect(within(panel).getByText(/ADMIN에만 저장되며 화면·감사에는 표시하지 않습니다/)).toBeInTheDocument()
    expect(within(panel).queryByText(/승인자와 감사에만 남습니다/)).toBeNull()
    await user.selectOptions(within(panel).getByLabelText('사유 코드'), 'INSTITUTION_REQUESTED')
    await fill(within(panel).getByLabelText('근거 참조'), 'INQ-2026-0001')
    await fill(within(panel).getByLabelText('사유'), 'acquirer asked to resend')
    await user.click(submit)
    expect(requestReacquisition).toHaveBeenCalledWith({
      fileId: F1, seqs: [1, 3], reasonCode: 'INSTITUTION_REQUESTED', evidenceRef: 'INQ-2026-0001', reason: 'acquirer asked to resend',
    })
    await settle()
    expect(within(panel).getByRole('status')).toHaveTextContent('apr_01JC0000000000000000000A01')
    expect(storageIsEmpty()).toBe(true)
  })

  it('one refused record (FINANCIAL refusal) blocks the whole bundle — no request button; a duplicate unresolved approval shows the file message', async () => {
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)),
      reacquisitionTargets: vi.fn()
        .mockResolvedValueOnce(targets(eligible(1), { ...eligible(3), eligible: false, refusal: 'CANCELLATION_UNDECIDED', expectedNetMinor: null }))
        .mockResolvedValueOnce(targets(eligible(1))),
      requestReacquisition: vi.fn(async () => { throw apiError(409, 'DUPLICATE_UNRESOLVED') }),
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    await user.click(within(panel).getByLabelText('레코드 1'))
    await user.click(within(panel).getByLabelText('레코드 3'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    await settle()
    expect(within(panel).getByText('불가: 판정 전 취소가 있음 — 다음 봉인 뒤 다시 요청')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: '재매입 승인 요청' })).toBeNull()
    await user.click(within(panel).getByLabelText('레코드 3'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    await settle()
    await user.selectOptions(within(panel).getByLabelText('사유 코드'), 'DATA_CORRECTED_AT_SOURCE')
    await fill(within(panel).getByLabelText('근거 참조'), 'INQ-2')
    await fill(within(panel).getByLabelText('사유'), 'fixed')
    await user.click(within(panel).getByRole('button', { name: '재매입 승인 요청' }))
    await settle()
    expect(within(panel).getByText('이 파일에 아직 끝나지 않은 재매입 승인이 있습니다.')).toBeInTheDocument()
  })

  it('B1-A review M-1: an evidence reference that looks like a card number keeps the request disabled with the card-number warning (server refuses it too)', async () => {
    const requestReacquisition = vi.fn(async () => ({ approvalId: 'apr_01JC0000000000000000000A01' }))
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)),
      reacquisitionTargets: vi.fn(async () => targets(eligible(1))), requestReacquisition,
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    await user.click(within(panel).getByLabelText('레코드 1'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    await settle()
    await user.selectOptions(within(panel).getByLabelText('사유 코드'), 'INSTITUTION_REQUESTED')
    expect(within(panel).getByText(/카드 번호 금지/)).toBeInTheDocument()
    await fill(within(panel).getByLabelText('사유'), 'acquirer asked to resend')
    const submit = within(panel).getByRole('button', { name: '재매입 승인 요청' })
    for (const card of ['4111111111111111', '4111-1111-1111-1111']) {
      await user.clear(within(panel).getByLabelText('근거 참조'))
      await fill(within(panel).getByLabelText('근거 참조'), card)
      expect(submit).toBeDisabled()
      expect(within(panel).getByRole('alert')).toHaveTextContent('카드 번호처럼 보이는')
    }
    await user.clear(within(panel).getByLabelText('근거 참조'))
    await fill(within(panel).getByLabelText('근거 참조'), 'INQ-2026-1105-0001')
    expect(within(panel).queryByRole('alert')).toBeNull()
    expect(submit).toBeEnabled()
    expect(requestReacquisition).not.toHaveBeenCalled()
  })

  it('B1-A review m-1: a missing original file on request (400 ACQUISITION_FILE_NOT_FOUND) shows the file message, not a generic error', async () => {
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)),
      reacquisitionTargets: vi.fn(async () => targets(eligible(1))),
      requestReacquisition: vi.fn(async () => { throw apiError(400, 'ACQUISITION_FILE_NOT_FOUND') }),
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    await user.click(within(panel).getByLabelText('레코드 1'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    await settle()
    await user.selectOptions(within(panel).getByLabelText('사유 코드'), 'INSTITUTION_REQUESTED')
    await fill(within(panel).getByLabelText('근거 참조'), 'INQ-1')
    await fill(within(panel).getByLabelText('사유'), 'resend')
    await user.click(within(panel).getByRole('button', { name: '재매입 승인 요청' }))
    await settle()
    expect(within(panel).getByText('매입 파일을 찾을 수 없습니다.')).toBeInTheDocument()
  })

  it('B1-A review n-4: while the preview request is in flight the record checkboxes are disabled (the selection cannot drift from the previewed set)', async () => {
    let resolve: (t: ReacquisitionTargets) => void = () => undefined
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)),
      reacquisitionTargets: vi.fn(() => new Promise<ReacquisitionTargets>((r) => { resolve = r })),
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    await user.click(within(panel).getByLabelText('레코드 1'))
    await user.click(within(panel).getByRole('button', { name: '대상 미리 보기' }))
    expect(within(panel).getByLabelText('레코드 1')).toBeDisabled()
    expect(within(panel).getByLabelText('레코드 3')).toBeDisabled()
    resolve(targets(eligible(1)))
    await settle()
    expect(within(panel).getByRole('table', { name: '재매입 대상 미리 보기' })).toBeInTheDocument()
    expect(within(panel).getByLabelText('레코드 3')).toBeEnabled()
  })

  it('B1-A review m-5: the page and badge texts follow the role — the OPERATOR is told to request below and another admin approves; nobody is told the screen is read-only', async () => {
    for (const canRequest of [true, false]) {
      const api = fakeApi({ acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail(rows)) })
      const { unmount } = render(<AcquisitionsScreen api={api} canRequest={canRequest} now={() => NOW} />)
      await user.click(screen.getByRole('button', { name: '검색' }))
      await settle()
      await user.click(within(screen.getByRole('row', { name: F1 })).getByRole('button', { name: '상세' }))
      await settle()
      screen.getByRole('table', { name: '레코드 목록' })
      if (canRequest) {
        expect(screen.getByText('매입 파일의 전송·수신·결과 상태를 조회하고, 거절된 레코드의 재매입을 요청합니다. 파일 상세 조회는 감사 기록에 남습니다.')).toBeInTheDocument()
        expect(screen.getByText('거절된 레코드가 있습니다. 아래에서 재매입을 요청하면 승인함에서 다른 관리자(APPROVER)가 승인합니다.')).toBeInTheDocument()
      } else {
        expect(screen.getByText('매입 파일의 전송·수신·결과 상태를 조회합니다. 파일 상세 조회는 감사 기록에 남습니다.')).toBeInTheDocument()
        expect(screen.getByText('거절된 레코드가 있습니다. 재매입은 운영자(OPERATOR)가 이 화면에서 요청하고, 다른 관리자(APPROVER)가 승인함에서 승인합니다.')).toBeInTheDocument()
      }
      expect(screen.queryByText(/조회 전용/)).toBeNull()
      unmount()
    }
  })

  it('UNKNOWN records are not selectable (D-ACQ-014 reconciliation first); a non-OPERATOR sees no request panel', async () => {
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [file(F1)], nextCursor: null })),
      acquisitionDetail: vi.fn(async () => detail([record(1, 'UNKNOWN'), record(3, 'REJECTED')])),
    })
    await openDetail(api, true)
    const panel = screen.getByRole('region', { name: '재매입 요청' })
    expect(within(panel).queryByLabelText('레코드 1')).toBeNull()
    expect(within(panel).getByText('결과 모름 — 대사로 미매입 확인 뒤 가능(D-ACQ-014)')).toBeInTheDocument()
    expect(within(panel).getByLabelText('레코드 3')).toBeInTheDocument()
  })

  it('a VIEWER (no OPERATOR) sees the detail without the request panel; after approval the re-acquisition file 002 is listed with its original file', async () => {
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({
        items: [file(F2, { fileVersion: 2, originalFileId: F1, recordCount: 2, saleCount: 2, netAmountMinor: 15000, transmissionState: 'SEALED', receiptState: 'NONE', resultState: 'AWAITING', transmittedAt: null, recordStatusCounts: { awaiting: 2, accepted: 0, rejected: 0, fileRejected: 0, unknown: 0, conflict: 0 }, reacquisitionCandidate: false }), file(F1)],
        nextCursor: null,
      })),
      acquisitionDetail: vi.fn(async () => detail(rows)),
    })
    await openDetail(api, false)
    expect(screen.getByRole('table', { name: '레코드 목록' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '재매입 요청' })).toBeNull()
    const row002 = screen.getByRole('row', { name: F2 })
    expect(within(row002).getByText(`재매입 · 원 파일 ${F1}`)).toBeInTheDocument()
    expect(within(row002).getByText('SIMACQA · TEST · v2')).toBeInTheDocument()
  })
})
