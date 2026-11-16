import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { AcquisitionsScreen } from './AcquisitionsScreen'
import type { AcquisitionFileDetail, AcquisitionFileItem, AcquisitionRecord, AdminApi } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 매입·대사 › 매입 현황("모양만"): 거래 조회(16cc494)와 같은 참고 시안 moduleView(카드 안 탭 → .filters → .tablewrap → .tablebottom)와
 * detail(eyebrow·제목·카드)을 실제 매입 현황에 입힌다. 기능·API 호출·오류 처리·재매입 2인 승인 요청은 AcquisitionsScreen.test / .reacquisition / .badge 시험이 그대로 지킨다.
 * 명시 Fixture: 파일 001(SALE 3건 — 1·3 기관 거절, 2 매입 확정, 순액 30,000원 = 3 × 10,000원), 재매입 검토 대상.
 */
const user = userEvent.setup({ delay: null })
const NOW = Date.parse('2026-11-06T01:00:00.000Z')
const F1 = 'ACQ.SIMACQA.TEST.20261105.001'
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/

const file: AcquisitionFileItem = {
  fileId: F1, acquirerCode: 'SIMACQA', environment: 'TEST', businessDate: '2026-11-05', fileVersion: 1, originalFileId: null, recordCount: 3, saleCount: 3,
  saleAmountMinor: 30000, cancelCount: 0, cancelAmountMinor: 0, netAmountMinor: 30000, currency: 'KRW', transmissionState: 'TRANSMITTED', receiptState: 'RECEIVED',
  resultState: 'COMPLETE', sealedAt: '2026-11-05T14:05:00.000Z', transmittedAt: '2026-11-05T14:06:00.000Z',
  recordStatusCounts: { awaiting: 0, accepted: 1, rejected: 2, fileRejected: 0, unknown: 0, conflict: 0 }, attention: ['RESULT_OVERDUE'], reacquisitionCandidate: true, reacquisitionInProgress: false,
}
const record = (seq: number, status: string): AcquisitionRecord => ({
  seq, recordType: 'SALE', transactionId: `ptx_ex${seq}`, originalTransactionId: null, merchantAccountId: 'demo-mid-001', institutionTransactionDate: '2026-11-05',
  eventAt: '2026-11-05T01:02:03.456Z', currency: 'KRW', approvedAmountMinor: 10000, nettedCancelAmountMinor: 0, recordAmountMinor: 10000, generation: 0, reacquisitionOf: null,
  status, results: [{ entryNo: 1, result: status === 'ACCEPTED' ? 'ACCEPTED' : 'REJECTED', observedResult: null, reasonCode: status === 'ACCEPTED' ? null : '05', source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 1 }, recognizedAt: '2026-11-05T15:00:00.000Z' }],
})
const detail: AcquisitionFileDetail = {
  file: { ...file, cutoffPolicyVersion: 'acq-cutoff-demo-1', cutoffAt: '2026-11-05T14:00:00.000Z', contentSha256: 'a'.repeat(64), contentBytes: 600, transmissionLeaseUntil: null, updatedAt: '2026-11-05T15:00:00.000Z' },
  attempts: { total: 0, latest: [] },
  responses: { total: 0, quarantined: 0, latest: [] },
  records: { items: [record(1, 'REJECTED'), record(2, 'ACCEPTED'), record(3, 'REJECTED')], nextCursor: 3 },
  readAt: '2026-11-06T01:00:00.000Z',
}

function signedIn(roles: string[], overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '운영자', roles })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  return render(<App api={api} />)
}

const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

describe('ADMIN-UI-WORKSPACE-001: 매입·대사 › 매입 현황 in the reference list layout', () => {
  it('flush in the menu card: tabpanel > .mapnote(h2) → form.filters.many → .cardnote; the list is .tablewrap → .tablebottom; the detail is a .detailarea of cards', async () => {
    signedIn(['OPERATOR'], {
      acquisitionSearch: vi.fn(async () => ({ items: [file], nextCursor: { businessDate: '2026-11-05', fileId: F1 } })),
      acquisitionDetail: vi.fn(async () => detail),
    })
    await user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '매입·대사' }))
    // OPERATOR는 매입 현황 탭 하나(대사 발견은 SECURITY_ADMIN·APPROVER) — 탭이 하나여도 같은 카드 안에 그린다.
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]') ?? screen.getByRole('main').querySelector(':scope > section.card')!
    expect(shape(panel)).toEqual(['div.mapnote', 'form.filters.many', 'div.cardnote'])
    expect(within(panel.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '매입 현황' })).toBeInTheDocument()

    const form = panel.querySelector('form.filters') as HTMLElement
    for (const name of ['조회 방식', '업무일 시작(KST)', '업무일 종료(KST)', '매입사 코드', '환경', '전송 상태', '수신 상태', '결과 상태', '확인 필요만']) {
      expect(within(form).getByLabelText(name)).toBeInTheDocument()
    }
    const periodHint = '업무일 기간(시작일 ~ 종료일): KST 달력일 기준, 최대 31일(Demo 기준).'
    expect(within(form).getByLabelText('업무일 시작(KST)')).toHaveAccessibleDescription(periodHint)
    expect(within(form).getByLabelText('업무일 종료(KST)')).toHaveAccessibleDescription(periodHint)
    expect(within(form).getByRole('button', { name: '검색' })).toHaveClass('btn', 'primary')

    await user.click(within(form).getByRole('button', { name: '검색' }))
    const row = await screen.findByRole('row', { name: F1 })
    const wrap = panel.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('매입 파일 목록')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['파일 / 매입사', '업무일', '건수', '전송·수신·결과', '레코드 결과', '표시', '순액', '작업'])
    expect(wrap.querySelectorAll('th')[6]).toHaveClass('amount')
    const cells = row.querySelectorAll('td')
    expect(cells[0]!.querySelector('strong')).toHaveTextContent(F1)
    expect(cells[0]!.querySelector('small')).toHaveTextContent('SIMACQA · TEST · v1')
    expect(cells[3]!.querySelector('.tag.green')).toHaveTextContent('전송 완료')
    expect(within(cells[5]!).getByText('확인 필요: 결과 지연')).toHaveClass('tag', 'orange')
    expect(within(cells[5]!).getByText('재매입 검토 대상')).toHaveClass('tag')
    expect(cells[6]).toHaveClass('amount')
    expect(cells[6]).toHaveTextContent('30,000원')
    expect(within(cells[7]!).getByRole('button', { name: '상세' })).toHaveClass('btn')
    expect(wrap.nextElementSibling).toHaveClass('tablebottom')
    expect(within(wrap.nextElementSibling as HTMLElement).getByRole('button', { name: '더 보기' })).toHaveClass('btn')

    await user.click(within(row).getByRole('button', { name: '상세' }))
    const area = (await screen.findByRole('region', { name: '파일 요약' })).closest('.detailarea')!
    expect(area.parentElement).toBe(panel)
    expect(row).toHaveClass('selected')
    expect(within(area as HTMLElement).getByRole('heading', { level: 3 })).toHaveTextContent(`매입 파일 상세 ${F1}`)
    for (const name of ['파일 요약', '상태', '레코드별 결과', '재매입 요청', '전송 시도', '기관 응답 기록']) {
      const region = screen.getByRole('region', { name })
      expect(region).toHaveClass('card')
      expect(region.querySelector(':scope > .cardtop > h4')).toHaveTextContent(name)
      expect(region.querySelector(':scope > .cardbody')).not.toBeNull()
    }
    // 재매입 요청은 레코드 표 아래 따로 놓인 카드(레코드별 결과 카드 바로 다음).
    expect(screen.getByRole('region', { name: '레코드별 결과' }).nextElementSibling).toBe(screen.getByRole('region', { name: '재매입 요청' }))
    for (const table of ['레코드 목록', '전송 시도 목록', '응답 기록 목록']) expect(screen.getByRole('table', { name: table }).parentElement).toHaveClass('tablewrap')
    const records = screen.getByRole('region', { name: '레코드별 결과' })
    // 레코드 상태 선택은 시안 .rolebar(굵은 이름 + 선택 + 보조 글) — 이름은 aria-labelledby.
    expect(within(records).getByLabelText('레코드 상태').closest('.rolebar')).not.toBeNull()
    expect(within(records).getByRole('button', { name: '레코드 더 보기' })).toHaveClass('btn')
    expect(within(within(records).getByRole('row', { name: '레코드 1' })).getByText('거절', { selector: 'td:nth-child(6) *' })).toHaveClass('tag', 'red')
    expect(within(screen.getByRole('region', { name: '상태' })).getByRole('list', { name: '확인 필요 사유' }).querySelector('.tag.orange')).toHaveTextContent('결과 지연')
    expect(within(screen.getByRole('region', { name: '전송 시도' })).getByText('전송 시도가 없습니다.')).toHaveClass('empty')
    expect(within(screen.getByRole('region', { name: '재매입 요청' })).getByRole('button', { name: '대상 미리 보기' })).toHaveClass('btn')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('the file-id mode keeps the reference filter row (only the fields change) and draws no empty .cardnote; an input problem is one alert in the card note', async () => {
    const { container } = render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch: vi.fn() })} now={() => NOW} />)
    const form = container.querySelector('form.filters') as HTMLElement
    await user.selectOptions(within(form).getByLabelText('조회 방식'), 'file')
    expect(Array.from(form.querySelectorAll('input, select')).map((el) => el.getAttribute('aria-label'))).toEqual(['조회 방식', '파일 ID'])
    expect(container.querySelector('.cardnote')).toBeNull()
    await user.click(within(form).getByRole('button', { name: '검색' }))
    expect(container.querySelector('.cardnote [role="alert"]')).toHaveTextContent('파일 ID를 입력해 주세요.')
    expect(container.querySelector('.cardnote [role="alert"]')).toHaveClass('error')
  })

  it('an empty result is one .empty line inside the table wrapper, followed by the bottom row', async () => {
    const { container } = render(<AcquisitionsScreen api={fakeApi({ acquisitionSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })} now={() => NOW} />)
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByText('조건에 맞는 매입 파일이 없습니다.')).toHaveClass('empty')
    expect(container.querySelector('.tablewrap > .empty')).not.toBeNull()
    expect(container.querySelector('.tablebottom')).toHaveTextContent('목록 0건')
  })

  it('stylesheet: the rolebar-in-card rule lives in the PayCore adaptation block (no new color)', () => {
    const block = WORKSPACE_CSS.slice(WORKSPACE_CSS.indexOf('PayCore 적응 규칙'), WORKSPACE_CSS.indexOf('라이트 대비 보정'))
    expect(block).toMatch(/^\.cardbody \.rolebar\{padding:0;flex-wrap:wrap\}$/m)
    // 이번 화면에서 더한 규칙만(기존 초점 규칙 `:where(…,.rolebar,…) input:focus`의 색은 거래 조회 리뷰 MINOR-3 값이라 대상 아님).
    const added = block.split('\n').filter((line) => line.startsWith('.cardbody .rolebar') || line.startsWith('.cardtop h3'))
    expect(added).toHaveLength(2)
    expect(added[0]).toMatch(/^\.cardtop h3\{.*color:#0f172a\}$/) // 색은 기존 .cardtop h4·.detailarea h3 값과 같다(새 색 아님)
    expect(added[1]).not.toMatch(/#[0-9a-f]{3,8}\b/i)
  })
})
