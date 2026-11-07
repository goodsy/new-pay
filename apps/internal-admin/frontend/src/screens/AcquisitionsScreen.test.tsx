import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AcquisitionsScreen } from './AcquisitionsScreen'
import type { AcquisitionFileDetail, AcquisitionFileItem, AcquisitionRecord } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/** 시험 시각: 2026-11-06 10:00 KST(= 01:00 UTC). 기본 기간은 KST 10-31 ~ 11-06(7일). */
const NOW = Date.parse('2026-11-06T01:00:00.000Z')
const F1 = 'ACQ.SFTPV9.TEST.20261020.001'
const F2 = 'ACQ.SFTPV7.TEST.20261020.001'
const F3 = 'ACQ.SFTPV3.TEST.20261019.001'

/** B1 시험이 만든 실제 응답 모양(ACQ-VIEW-SAMPLE-*)을 따른 Named Fixture — 정상 종결 파일에서 한 가지씩 바꾼다. */
const completed = (fileId: string): AcquisitionFileItem => ({
  fileId, acquirerCode: fileId.split('.')[1] ?? '', environment: 'TEST', businessDate: '2026-10-20', fileVersion: 1, originalFileId: null, recordCount: 2, saleCount: 2,
  saleAmountMinor: 30000, cancelCount: 0, cancelAmountMinor: 0, netAmountMinor: 30000, currency: 'KRW', transmissionState: 'TRANSMITTED', receiptState: 'RECEIVED',
  resultState: 'COMPLETE', sealedAt: '2026-10-20T14:05:00.000Z', transmittedAt: '2026-10-20T14:06:00.000Z',
  recordStatusCounts: { awaiting: 0, accepted: 2, rejected: 0, fileRejected: 0, unknown: 0, conflict: 0 }, attention: [], reacquisitionCandidate: false, reacquisitionInProgress: false,
})
const overdue: AcquisitionFileItem = {
  ...completed(F1), resultState: 'AWAITING', recordStatusCounts: { awaiting: 1, accepted: 0, rejected: 0, fileRejected: 0, unknown: 0, conflict: 0 }, recordCount: 1,
  saleCount: 1, netAmountMinor: 10000, attention: ['RESULT_OVERDUE'],
}
const conflict: AcquisitionFileItem = {
  ...completed(F2), receiptState: 'RECEIPT_CONFLICT', resultState: 'AWAITING', attention: ['RECEIPT_CONFLICT', 'RESPONSE_QUARANTINED', 'RESULT_OVERDUE'],
}
const rejected: AcquisitionFileItem = {
  ...completed(F3), businessDate: '2026-10-19', recordStatusCounts: { awaiting: 0, accepted: 1, rejected: 1, fileRejected: 0, unknown: 0, conflict: 0 },
  reacquisitionCandidate: true,
}

const record = (seq: number, status = 'ACCEPTED', results: AcquisitionRecord['results'] = [
  { entryNo: 1, result: 'ACCEPTED', observedResult: null, reasonCode: null, source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 1 }, recognizedAt: '2026-10-20T15:00:00.000Z' },
]): AcquisitionRecord => ({
  seq, recordType: 'SALE', transactionId: `ptx_auth_pay_fx_${seq}`, originalTransactionId: null, merchantAccountId: 'demo-mid-001', institutionTransactionDate: '2026-11-05',
  eventAt: '2026-10-20T01:02:03.456Z', currency: 'KRW', approvedAmountMinor: 10000, nettedCancelAmountMinor: 0, recordAmountMinor: 10000, generation: 0,
  reacquisitionOf: null, status, results,
})

const detailOf = (file: AcquisitionFileItem, records = [record(1), record(2)], nextCursor: number | null = null): AcquisitionFileDetail => ({
  file: { ...file, cutoffPolicyVersion: 'acq-cutoff-demo-1', cutoffAt: '2026-10-20T14:00:00.000Z', contentSha256: '5c8b8097075cc97df5fad51505e48d67d666319c20f43049e1b641c1bfabdd62', contentBytes: 495, transmissionLeaseUntil: null, updatedAt: '2026-10-20T15:00:00.000Z' },
  attempts: { total: 1, latest: [{ attemptNo: 1, resumeState: 'SEALED', claimedAt: '2026-10-20T14:06:00.000Z', leaseUntil: '2026-10-20T14:08:00.000Z', outcome: 'DELIVERED', observed: null, evidence: 'RENAMED_AND_VERIFIED', reason: null, resultingState: 'TRANSMITTED', decidedAt: '2026-10-20T14:06:00.000Z' }] },
  responses: {
    total: 2, quarantined: file.attention.includes('RESPONSE_QUARANTINED') ? 1 : 0,
    latest: [
      { kind: 'RESULT', sequenceNo: 1, disposition: 'APPLIED', quarantineReason: null, ackOutcome: null, ackReasonCode: null, evidence: 'RESULT_HASH_MATCH', institutionAt: '2026-10-20T14:59:50.000Z', observedAt: '2026-10-20T15:00:00.000Z' },
      { kind: 'ACK', sequenceNo: 1, disposition: file.attention.includes('RECEIPT_CONFLICT') ? 'QUARANTINED' : 'APPLIED', quarantineReason: file.attention.includes('RECEIPT_CONFLICT') ? 'HASH_MISMATCH' : null, ackOutcome: 'RECEIVED', ackReasonCode: null, evidence: null, institutionAt: '2026-10-20T14:59:30.000Z', observedAt: '2026-10-20T15:00:00.000Z' },
    ],
  },
  records: { items: records, nextCursor },
  readAt: '2026-11-06T01:00:00.000Z',
})

const search = async () => userEvent.click(screen.getByRole('button', { name: '검색' }))
const choose = async (label: string, value: string) => userEvent.selectOptions(screen.getByLabelText(label), value)

describe('AcquisitionsScreen (ACQ-VIEW-001 §4.6 — read-only acquisition status)', () => {
  it('a period search sends KST business days with the filters (trimmed acquirer, needsAttention only when checked), shows the 3 axes, reasons and the re-acquisition mark, and appends the next page', async () => {
    const acquisitionSearch = vi.fn()
      .mockResolvedValueOnce({ items: [overdue, conflict], nextCursor: { businessDate: '2026-10-20', fileId: F2 } })
      .mockResolvedValueOnce({ items: [rejected], nextCursor: null })
    render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch })} now={() => NOW} />)
    expect(screen.getByLabelText('업무일 시작(KST)')).toHaveValue('2026-10-31')
    expect(screen.getByLabelText('업무일 종료(KST)')).toHaveValue('2026-11-06')
    fireEvent.change(screen.getByLabelText('업무일 시작(KST)'), { target: { value: '2026-10-19' } })
    await userEvent.type(screen.getByLabelText('매입사 코드'), '  SFTPV9 ')
    await choose('환경', 'TEST')
    await choose('결과 상태', 'AWAITING')
    await userEvent.click(screen.getByLabelText('확인 필요만'))
    await search()
    const sent = { businessDateFrom: '2026-10-19', businessDateTo: '2026-11-06', acquirerCode: 'SFTPV9', environment: 'TEST', resultState: 'AWAITING', needsAttention: true }
    expect(acquisitionSearch).toHaveBeenLastCalledWith(sent)
    const first = await screen.findByRole('row', { name: F1 })
    expect(within(first).getByText('전송 완료')).toBeInTheDocument()
    expect(within(first).getByText('수신 확인')).toBeInTheDocument()
    expect(within(first).getByText('결과 대기', { selector: '[data-slot="badge"]' })).toBeInTheDocument()
    expect(within(first).getByText('확인 필요: 결과 지연')).toBeInTheDocument()
    expect(within(first).getByText('10,000원')).toBeInTheDocument()
    const second = screen.getByRole('row', { name: F2 })
    expect(within(second).getByText('수신 내용 불일치')).toBeInTheDocument()
    expect(within(second).getByText('확인 필요: 수신 불일치')).toBeInTheDocument()
    expect(within(second).getByText('확인 필요: 격리된 응답')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(acquisitionSearch).toHaveBeenLastCalledWith({ ...sent, after: { businessDate: '2026-10-20', fileId: F2 } })
    const third = await screen.findByRole('row', { name: F3 })
    expect(within(third).getByText('재매입 검토 대상')).toBeInTheDocument()
    expect(within(third).getByText('매입 확정 1 · 거절 1')).toBeInTheDocument()
    expect(screen.getByRole('row', { name: F1 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  it('an unchecked needsAttention is not sent; the file id mode sends the id alone; input problems are explained without a call', async () => {
    const acquisitionSearch = vi.fn(async () => ({ items: [], nextCursor: null }))
    render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch })} now={() => NOW} />)
    await search()
    expect(acquisitionSearch).toHaveBeenLastCalledWith({ businessDateFrom: '2026-10-31', businessDateTo: '2026-11-06' })
    expect(await screen.findByText('조건에 맞는 매입 파일이 없습니다.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('업무일 시작(KST)'), { target: { value: '2026-10-05' } })
    await search()
    expect(screen.getByRole('alert')).toHaveTextContent('조회 기간은 최대 31일입니다.')
    await choose('조회 방식', 'file')
    await search()
    expect(screen.getByRole('alert')).toHaveTextContent('파일 ID를 입력해 주세요.')
    expect(acquisitionSearch).toHaveBeenCalledTimes(1)
    await userEvent.type(screen.getByLabelText('파일 ID'), ` ${F1} `)
    await search()
    expect(acquisitionSearch).toHaveBeenLastCalledWith({ fileId: F1 })
  })

  it('the detail shows the summary, the three axes, every attention reason in words, records with their result history, attempts and responses — and no card-company values or action buttons', async () => {
    const acquisitionDetail = vi.fn(async () => detailOf(conflict, [
      record(1, 'CONFLICT', [
        { entryNo: 1, result: 'ACCEPTED', observedResult: null, reasonCode: null, source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 1 }, recognizedAt: '2026-10-20T15:00:00.000Z' },
        { entryNo: 2, result: 'CONFLICT', observedResult: 'REJECTED', reasonCode: 'AMOUNT_MISMATCH', source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 2 }, recognizedAt: '2026-10-20T15:01:00.000Z' },
      ]),
      record(2, 'UNKNOWN', [{ entryNo: 1, result: 'UNKNOWN', observedResult: null, reasonCode: null, source: 'DEADLINE', responseRef: null, recognizedAt: '2026-10-20T17:06:00.000Z' }]),
    ]))
    render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch: vi.fn(async () => ({ items: [conflict], nextCursor: null })), acquisitionDetail })} now={() => NOW} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: F2 })).getByRole('button', { name: '상세' }))
    expect(acquisitionDetail).toHaveBeenCalledWith(F2)
    const summary = await screen.findByRole('region', { name: '파일 요약' })
    expect(within(summary).getByText('acq-cutoff-demo-1')).toBeInTheDocument()
    expect(within(summary).getByText('5c8b8097075cc97d… (495바이트)')).toBeInTheDocument()
    const reasons = within(screen.getByRole('region', { name: '상태' })).getByRole('list', { name: '확인 필요 사유' })
    expect(reasons).toHaveTextContent('기관이 받은 내용이 봉인한 파일과 다릅니다. 격리했습니다.')
    expect(reasons).toHaveTextContent('자동으로 반영하지 않고 격리한 기관 응답이 있습니다. (1건)')
    expect(reasons).toHaveTextContent('결과 마감 시각이 지났지만 아직 마감 처리 전입니다.')
    const records = screen.getByRole('region', { name: '레코드별 결과' })
    const r1 = within(records).getByRole('row', { name: '레코드 1' })
    expect(within(r1).getByText('상충', { selector: 'td:nth-child(6) *' })).toBeInTheDocument()
    expect(within(r1).getByText('(관찰 REJECTED)')).toBeInTheDocument()
    expect(within(r1).getByText('AMOUNT_MISMATCH')).toBeInTheDocument()
    expect(within(r1).getByText('RESULT 2회')).toBeInTheDocument()
    expect(within(within(records).getByRole('row', { name: '레코드 2' })).getByText('결과 마감')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '전송 시도' })).getByText('RENAMED_AND_VERIFIED')).toBeInTheDocument()
    const responses = screen.getByRole('region', { name: '기관 응답 기록' })
    expect(within(within(responses).getByRole('row', { name: 'ACK 1' })).getByText('격리')).toBeInTheDocument()
    expect(within(responses).getByText('HASH_MISMATCH')).toBeInTheDocument()
    // D-ACQV-002: 상세 패널 어디에도 카드사 쪽 값의 칸이 없다(목록 아래 안내 문구 하나만 그 이름을 말한다).
    for (const region of ['파일 요약', '상태', '레코드별 결과', '전송 시도', '기관 응답 기록']) {
      for (const hidden of ['승인번호', '기관 거래번호', '발급사', '카드 종류', '할부']) expect(screen.getByRole('region', { name: region })).not.toHaveTextContent(hidden)
    }
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['검색', '상세'])
  })

  it('the record status filter and the next record page call the record search (each audited by the server) and append', async () => {
    const acquisitionRecords = vi.fn()
      .mockResolvedValueOnce({ items: [record(3, 'REJECTED')], nextCursor: 3 })
      .mockResolvedValueOnce({ items: [record(9, 'REJECTED')], nextCursor: null })
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [rejected], nextCursor: null })),
      acquisitionDetail: vi.fn(async () => detailOf(rejected, [record(1)], 1)),
      acquisitionRecords,
    })
    render(<AcquisitionsScreen api={api} now={() => NOW} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: F3 })).getByRole('button', { name: '상세' }))
    await screen.findByRole('region', { name: '레코드별 결과' })
    // ACQ-ADMIN-001 B1-A 리뷰 m-5: 재매입 요청 패널이 생겨 "조회 전용" 문구를 역할별 문구로 바꿨다(이 시험은 OPERATOR 아님 — 조회 역할 문구).
    expect(screen.getByText('거절된 레코드가 있습니다. 재매입은 운영자(OPERATOR)가 이 화면에서 요청하고, 다른 관리자(APPROVER)가 승인함에서 승인합니다.')).toBeInTheDocument()
    await choose('레코드 상태', 'REJECTED')
    expect(acquisitionRecords).toHaveBeenLastCalledWith(F3, { status: 'REJECTED' })
    expect(await screen.findByRole('row', { name: '레코드 3' })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: '레코드 1' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '레코드 더 보기' }))
    expect(acquisitionRecords).toHaveBeenLastCalledWith(F3, { status: 'REJECTED', afterSeq: 3 })
    expect(await screen.findByRole('row', { name: '레코드 9' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: '레코드 3' })).toBeInTheDocument()
  })

  it('a record page still loading for a previous file neither lands in nor ends the busy state of the file opened after it (review NIT)', async () => {
    let finishOld: (page: { items: AcquisitionRecord[]; nextCursor: number | null }) => void = () => {}
    let finishNew: (page: { items: AcquisitionRecord[]; nextCursor: number | null }) => void = () => {}
    const acquisitionRecords = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishNew = resolve }))
    const api = fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [overdue, rejected], nextCursor: null })),
      acquisitionDetail: vi.fn(async (id: string) => detailOf(id === F3 ? rejected : overdue, [record(1)], 1)),
      acquisitionRecords,
    })
    render(<AcquisitionsScreen api={api} now={() => NOW} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: F3 })).getByRole('button', { name: '상세' }))
    await userEvent.click(await screen.findByRole('button', { name: '레코드 더 보기' }))
    expect(acquisitionRecords).toHaveBeenLastCalledWith(F3, { afterSeq: 1 })
    await userEvent.click(within(screen.getByRole('row', { name: F1 })).getByRole('button', { name: '상세' }))
    await screen.findByRole('heading', { name: `매입 파일 상세 ${F1}` })
    await userEvent.click(screen.getByRole('button', { name: '레코드 더 보기' }))
    expect(acquisitionRecords).toHaveBeenLastCalledWith(F1, { afterSeq: 1 })
    expect(screen.getByRole('button', { name: '레코드 더 보기' })).toHaveAttribute('aria-busy', 'true')
    finishOld({ items: [record(7, 'REJECTED')], nextCursor: null })
    await act(async () => { await Promise.resolve() })
    expect(screen.getByRole('button', { name: '레코드 더 보기' })).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByRole('row', { name: '레코드 7' })).toBeNull()
    finishNew({ items: [record(2)], nextCursor: null })
    expect(await screen.findByRole('row', { name: '레코드 2' })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: '레코드 7' })).toBeNull()
  })

  it('FINANCIAL unavailable, an audit failure on the detail and a not-found file show fixed messages; a session end leaves the screen', async () => {
    const onSessionEnded = vi.fn()
    const acquisitionSearch = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'FINANCIAL_UNAVAILABLE'))
      .mockResolvedValue({ items: [overdue], nextCursor: null })
    const acquisitionDetail = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE'))
      .mockRejectedValueOnce(apiError(404, 'ACQUISITION_FILE_NOT_FOUND'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch, acquisitionDetail })} onSessionEnded={onSessionEnded} now={() => NOW} />)
    await search()
    expect(await screen.findByText('매입 정보 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
    await search()
    const open = async () => userEvent.click(within(await screen.findByRole('row', { name: F1 })).getByRole('button', { name: '상세' }))
    await open()
    expect(await screen.findByText('감사 기록을 남길 수 없어 조회하지 않았습니다. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '파일 요약' })).toBeNull()
    await open()
    expect(await screen.findByText('매입 파일을 찾을 수 없습니다.')).toBeInTheDocument()
    await open()
    await vi.waitFor(() => expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })
})
