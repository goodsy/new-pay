import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AcquisitionsScreen } from './AcquisitionsScreen'
import type { AcquisitionFileDetail, AcquisitionFileItem } from '../api/adminApi'
import { fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * ACQ-ADMIN-001 B2 — 재매입 배지 3상태(§5.1 L-9 배지 규칙, AC9, MINOR-6)와 전송 보류(§15 L-9). 화면은 서버 계산 값(reacquisitionCandidate·reacquisitionInProgress·attention)만 보이고
 * 스스로 계산하지 않는다(I-8). 원 레코드 상태(거절)는 그대로 보인다 — 배지는 표시 분류다.
 */
const NOW = Date.parse('2026-11-06T01:00:00.000Z')
const F1 = 'ACQ.SFTPW1.TEST.20261105.001'
const F2 = 'ACQ.SFTPW1.TEST.20261105.002'

const rejectedOriginal = (patch: Partial<AcquisitionFileItem> = {}): AcquisitionFileItem => ({
  fileId: F1, acquirerCode: 'SFTPW1', environment: 'TEST', businessDate: '2026-11-05', fileVersion: 1, originalFileId: null, recordCount: 1, saleCount: 1,
  saleAmountMinor: 10000, cancelCount: 0, cancelAmountMinor: 0, netAmountMinor: 10000, currency: 'KRW', transmissionState: 'TRANSMITTED', receiptState: 'RECEIVED',
  resultState: 'COMPLETE', sealedAt: '2026-11-05T14:05:00.000Z', transmittedAt: '2026-11-05T14:06:00.000Z',
  recordStatusCounts: { awaiting: 0, accepted: 0, rejected: 1, fileRejected: 0, unknown: 0, conflict: 0 }, attention: [], reacquisitionCandidate: true,
  reacquisitionInProgress: false, ...patch,
})
const withheld: AcquisitionFileItem = {
  ...rejectedOriginal(), fileId: F2, fileVersion: 2, originalFileId: F1, transmissionState: 'TRANSMISSION_WITHHELD', receiptState: 'NONE', resultState: 'AWAITING',
  transmittedAt: null, recordStatusCounts: { awaiting: 1, accepted: 0, rejected: 0, fileRejected: 0, unknown: 0, conflict: 0 }, attention: ['TRANSMISSION_WITHHELD'],
  reacquisitionCandidate: false, reacquisitionInProgress: false,
}

const detailOf = (file: AcquisitionFileItem): AcquisitionFileDetail => ({
  file: { ...file, cutoffPolicyVersion: 'acq-cutoff-demo-1', cutoffAt: '2026-11-05T14:00:00.000Z', contentSha256: '5c8b8097075cc97df5fad51505e48d67d666319c20f43049e1b641c1bfabdd62', contentBytes: 295, transmissionLeaseUntil: null, updatedAt: '2026-11-06T15:00:00.000Z' },
  attempts: { total: 0, latest: [] },
  responses: { total: 0, quarantined: 0, latest: [] },
  records: { items: [], nextCursor: null },
  readAt: '2026-11-06T01:00:00.000Z',
})

const search = async () => userEvent.click(screen.getByRole('button', { name: '검색' }))

describe('AcquisitionsScreen — re-acquisition badge, three states (ACQ-ADMIN-001 §5.1, AC9)', () => {
  it('candidate → "재매입 검토 대상", a newer generation in progress → "재매입 진행 중", accepted in the new generation → no badge (the record count still shows the rejection)', async () => {
    const acquisitionSearch = vi.fn()
      .mockResolvedValueOnce({ items: [rejectedOriginal()], nextCursor: null })
      .mockResolvedValueOnce({ items: [rejectedOriginal({ reacquisitionCandidate: false, reacquisitionInProgress: true })], nextCursor: null })
      .mockResolvedValueOnce({ items: [rejectedOriginal({ reacquisitionCandidate: false, reacquisitionInProgress: false })], nextCursor: null })
    render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch })} now={() => NOW} />)

    await search()
    let row = await screen.findByRole('row', { name: F1 })
    expect(within(row).getByText('재매입 검토 대상')).toBeInTheDocument()
    expect(within(row).queryByText('재매입 진행 중')).toBeNull()

    await search()
    row = await screen.findByRole('row', { name: F1 })
    await vi.waitFor(() => expect(within(screen.getByRole('row', { name: F1 })).getByText('재매입 진행 중')).toBeInTheDocument())
    row = screen.getByRole('row', { name: F1 })
    expect(within(row).queryByText('재매입 검토 대상')).toBeNull()

    await search()
    await vi.waitFor(() => expect(within(screen.getByRole('row', { name: F1 })).queryByText('재매입 진행 중')).toBeNull())
    row = screen.getByRole('row', { name: F1 })
    expect(within(row).queryByText('재매입 검토 대상')).toBeNull()
    expect(within(row).getByText('거절 1')).toBeInTheDocument()
    expect(storageIsEmpty()).toBe(true)
  })

  it('the detail explains the in-progress state in words and offers no request for it', async () => {
    const inProgress = rejectedOriginal({ reacquisitionCandidate: false, reacquisitionInProgress: true })
    render(<AcquisitionsScreen api={fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [inProgress], nextCursor: null })),
      acquisitionDetail: vi.fn(async () => detailOf(inProgress)),
    })} now={() => NOW} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: F1 })).getByRole('button', { name: '상세' }))
    const reasons = within(await screen.findByRole('region', { name: '상태' })).getByRole('list', { name: '확인 필요 사유' })
    expect(reasons).toHaveTextContent('재매입 진행 중')
    expect(reasons).toHaveTextContent('새 버전 파일로 재매입이 진행 중입니다. 매입사 결과가 매입 확정이면 이 표시는 사라집니다.')
    expect(reasons).not.toHaveTextContent('재매입 검토 대상')
  })

  it('a withheld re-acquisition file (§15 L-9) shows its transmission state and the reconciliation reason, not a re-acquisition badge', async () => {
    render(<AcquisitionsScreen api={fakeApi({
      acquisitionSearch: vi.fn(async () => ({ items: [withheld], nextCursor: null })),
      acquisitionDetail: vi.fn(async () => detailOf(withheld)),
    })} now={() => NOW} />)
    await search()
    const row = await screen.findByRole('row', { name: F2 })
    expect(within(row).getByText('전송 보류(대사)')).toBeInTheDocument()
    expect(within(row).getByText('확인 필요: 전송 보류')).toBeInTheDocument()
    expect(within(row).queryByText('재매입 검토 대상')).toBeNull()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const reasons = within(await screen.findByRole('region', { name: '상태' })).getByRole('list', { name: '확인 필요 사유' })
    expect(reasons).toHaveTextContent('이 재매입 파일의 거래가 다른 세대에서 이미 매입 확정되어 보내지 않고 보류했습니다. 대사 대상입니다.')
  })
})
