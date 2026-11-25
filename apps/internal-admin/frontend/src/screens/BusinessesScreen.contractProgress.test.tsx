import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen } from './BusinessesScreen'
import type { BusinessDetail, ContractProgress } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { fakeApi } from '../test/fakeApi'
import { individualRegisteredDetail, listItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-FLOW-001 FLOW-1 사업자 상세 '계약 진행' 섹션(§6 FLOW-1 — 읽기 전용, 명시 Demo fixture):
 * - 접수 건(최신부터)·상태·전이 이력. 이관 건은 "계약 완료(이관) · 근거: 이관", 담당자·처리자 '이관'(R-6).
 * - R-1 용어 분리: 섹션 제목 '계약 진행' + 안내(결제 허용·MID 발급은 가맹점 계약 승인). '계약 승인'이라는 말을 상태로 쓰지 않는다.
 * - 근거 참조 원문은 사업자 조회 역할 전체(OPERATOR·APPROVER — D-FLOW-004 ④)에 보인다. 쓰기 동작(생성·전이)은 FLOW-2 — 버튼·입력 없음.
 * - 계약 담당자·처리자는 ADMIN이 채운 표시명(m-11). 내부 키(biz_·adm_)·로그인 이름·version은 DOM에 없다.
 * - 섹션을 불러오지 못하면 섹션만 '불러오지 못함'(fail-safe), 상세는 그대로.
 */
const user = userEvent.setup({ delay: null })

const PROGRESS: ContractProgress = {
  available: true,
  intakes: [
    {
      intakeSeq: 2, status: 'COMPLETED', migrated: false, receivedOn: '2026-11-20', intakeChannel: 'PHONE', contractManagerName: '영업 담당',
      desiredServices: ['CARD_AUTH', 'CARD_KEYIN'], version: 2, createdAt: '2026-11-20T01:00:00.000Z', updatedAt: '2026-11-22T01:00:00.000Z',
      transitions: [
        { transitionSeq: 1, fromStatus: null, toStatus: 'CONSULTING', migrated: false, documentRef: null, actorKind: 'ADMIN', actorName: '영업 담당', occurredAt: '2026-11-20T01:00:00.000Z' },
        { transitionSeq: 2, fromStatus: 'CONSULTING', toStatus: 'DOCUMENT_RECEIVED', migrated: false, documentRef: null, actorKind: 'ADMIN', actorName: '영업 담당', occurredAt: '2026-11-21T01:00:00.000Z' },
        { transitionSeq: 3, fromStatus: 'DOCUMENT_RECEIVED', toStatus: 'COMPLETED', migrated: false, documentRef: 'DEMO-CONTRACT-0002', actorKind: 'ADMIN', actorName: null, occurredAt: '2026-11-22T01:00:00.000Z' },
      ],
    },
    {
      intakeSeq: 1, status: 'TERMINATED', migrated: true, receivedOn: null, intakeChannel: null, contractManagerName: null, desiredServices: [], version: 1,
      createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-11-19T01:00:00.000Z',
      transitions: [
        { transitionSeq: 1, fromStatus: null, toStatus: 'COMPLETED', migrated: true, documentRef: null, actorKind: 'SYSTEM', actorName: null, occurredAt: '2026-10-01T00:00:00.000Z' },
        { transitionSeq: 2, fromStatus: 'COMPLETED', toStatus: 'TERMINATED', migrated: false, documentRef: 'DEMO-TERM-0001', actorKind: 'ADMIN', actorName: '조회 담당', occurredAt: '2026-11-19T01:00:00.000Z' },
      ],
    },
  ],
}
const MIGRATED_ONLY: ContractProgress = {
  available: true,
  intakes: [{
    intakeSeq: 1, status: 'COMPLETED', migrated: true, receivedOn: null, intakeChannel: null, contractManagerName: null, desiredServices: [], version: 0,
    createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-10-01T00:00:00.000Z',
    transitions: [{ transitionSeq: 1, fromStatus: null, toStatus: 'COMPLETED', migrated: true, documentRef: null, actorKind: 'SYSTEM', actorName: null, occurredAt: '2026-10-01T00:00:00.000Z' }],
  }],
}

const detailWith = (contractProgress: ContractProgress): BusinessDetail => ({ ...individualRegisteredDetail(1), contractProgress })

function renderScreen(detail: BusinessDetail, canEdit: boolean) {
  const api = fakeApi({ searchBusinesses: vi.fn(async () => ({ items: [listItemOf(detail)], next: null })), businessDetail: vi.fn(async () => detail) })
  const view = render(<BusinessesScreen api={api} memory={createScreenMemory()} canEdit={canEdit} onContinue={vi.fn()} handoffState={() => 'empty'} />)
  return { api, view }
}
const openSection = async () => {
  await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
  await screen.findByRole('region', { name: '사업자 상세' })
  return screen.findByRole('region', { name: '계약 진행' })
}

describe('MERCHANT-FLOW-001 FLOW-1 — 사업자 상세 계약 진행 섹션(읽기 전용)', () => {
  it('접수 건 최신부터: 상태·접수일·경로·담당자(표시명)·희망 서비스(참고용 문구)·근거, 전이 이력 표; R-1 안내', async () => {
    renderScreen(detailWith(PROGRESS), true)
    const section = await openSection()
    expect(within(section).getByRole('heading', { name: '계약 진행' })).toBeInTheDocument()
    expect(section).toHaveTextContent('결제 허용·MID 발급은 가맹점 계약 승인으로 정합니다')
    const intakes = within(section).getAllByRole('group')
    expect(intakes.map((g) => g.getAttribute('aria-label'))).toEqual(['계약 접수 2', '계약 접수 1'])
    const latest = intakes[0]!
    expect(within(latest).getByText('계약 완료 · 근거: DEMO-CONTRACT-0002')).toBeInTheDocument()
    expect(latest).toHaveTextContent('2026-11-20')
    expect(latest).toHaveTextContent('전화')
    expect(latest).toHaveTextContent('영업 담당')
    expect(latest).toHaveTextContent('카드 인증 결제, 카드 키인 결제')
    expect(latest).toHaveTextContent('희망 서비스 — 참고용, 실제 서비스는 가맹점 계약 승인·개통에서 정합니다')
    const rows = within(within(latest).getByRole('table', { name: '계약 접수 2 진행 이력' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => within(r).getAllByRole('cell').slice(0, 4).map((c) => c.textContent))).toEqual([
      ['1', '접수 → 상담 중', '-', '영업 담당'],
      ['2', '상담 중 → 계약서 접수', '-', '영업 담당'],
      ['3', '계약서 접수 → 계약 완료', 'DEMO-CONTRACT-0002', '확인 불가'],
    ])
  })

  it('R-6 이관 건: "계약 완료(이관) · 근거: 이관", 담당자·처리자 이관; 이관 뒤 해지는 해지 근거 원문', async () => {
    renderScreen(detailWith(PROGRESS), false)
    const section = await openSection()
    const migrated = within(section).getByRole('group', { name: '계약 접수 1' })
    expect(within(migrated).getByText('계약 해지 · 근거: 이관')).toBeInTheDocument()
    const rows = within(within(migrated).getByRole('table', { name: '계약 접수 1 진행 이력' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => within(r).getAllByRole('cell').slice(0, 4).map((c) => c.textContent))).toEqual([
      ['1', '이관 → 계약 완료', '이관', '이관'],
      ['2', '계약 완료 → 계약 해지', 'DEMO-TERM-0001', '조회 담당'],
    ])
    expect(migrated).toHaveTextContent('계약 담당자이관')
  })

  it('이관 완료 건만 있는 사업자: 섹션에 "계약 완료(이관) · 근거: 이관"', async () => {
    renderScreen(detailWith(MIGRATED_ONLY), true)
    const section = await openSection()
    expect(within(section).getByText('계약 완료(이관) · 근거: 이관')).toBeInTheDocument()
  })

  it.each([
    ['OPERATOR', true],
    ['APPROVER(조회 역할)', false],
  ])('%s: 근거 참조 원문이 보이고(D-FLOW-004 ④) 쓰기 동작(버튼·입력)이 섹션에 없다', async (_role, canEdit) => {
    renderScreen(detailWith(PROGRESS), canEdit)
    const section = await openSection()
    expect(section).toHaveTextContent('DEMO-CONTRACT-0002')
    expect(section).toHaveTextContent('DEMO-TERM-0001')
    expect(within(section).queryAllByRole('button')).toHaveLength(0)
    expect(section.querySelectorAll('input, select, textarea')).toHaveLength(0)
  })

  it('접수 건 없음: 빈 안내', async () => {
    renderScreen(detailWith({ available: true, intakes: [] }), true)
    const section = await openSection()
    expect(section).toHaveTextContent('계약 접수 건이 없습니다.')
    expect(within(section).queryAllByRole('group')).toHaveLength(0)
  })

  it('fail-safe: 섹션을 불러오지 못하면 섹션만 안내, 상세(기본정보)는 그대로', async () => {
    renderScreen(detailWith({ available: false, intakes: [] }), true)
    const section = await openSection()
    expect(section).toHaveTextContent('계약 진행을 불러오지 못했습니다')
    expect(await screen.findByRole('region', { name: '사업자 정보 편집' })).toBeInTheDocument()
  })

  it('내부 키(biz_·adm_)·MASTER 행위자 참조(admin:·system:)·style 속성이 DOM에 없다', async () => {
    const { view } = renderScreen(detailWith(PROGRESS), true)
    await openSection()
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    expect(domOccurrences(view.container, 'adm_')).toEqual([])
    expect(domOccurrences(view.container, 'admin:')).toEqual([])
    expect(domOccurrences(view.container, 'system:')).toEqual([])
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
  })
})
