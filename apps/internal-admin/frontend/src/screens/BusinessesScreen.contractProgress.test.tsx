import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen } from './BusinessesScreen'
import type { BusinessContractView, BusinessDetail, ContractIntakeView, ContractProgress } from '../api/adminApi'
import { CONTRACT_STATUS_LABELS } from '../lib/contractProgress'
import { createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { bizId, individualRegisteredDetail, listItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-FLOW-001 사업자 상세 '계약 진행' 섹션(FLOW-1 읽기 → FLOW-2b 사업자 계약 칸·접수 건 생성·전이·계약 완료 — §4B.11 화면 1·2, 명시 Demo fixture, 운영값 아님):
 * - 사업자 계약 칸: 상태 배지(효력 전·유효·정지·해지·만료), 적용 중 버전(번호·범위·시작일~종료일, 이관은 "이관"), 예정 버전, 버전 이력 표(기록 시각 병기 — MINOR-4), R-1 문구.
 *   계약 없음 → "사업자 계약 없음 — 신규 접수 후 계약 완료가 필요합니다".
 * - 접수 건: 종류 배지(신규·변경), 상태, 그 상태에서 허용되는 동작 버튼만(OPERATOR — APPROVER는 버튼 없음), '새 접수' 버튼 이름 = 화면이 판정한 종류(열린 건 있으면 없음).
 * - 계약 완료 대화상자: 근거 참조 필수·시작일 기본 = 서버(ADMIN) KST 오늘·과거 불가·종료일 선택(≥ 시작일)·범위 카드 PG 결제(희망 서비스와 별개)·'사유 및 비고'(선택)·오타 정정 안내.
 * - 변경 접수가 열려 있고 계약이 해지·만료면 계약 완료 버튼 대신 안내. 되돌리기·계약 불가는 '사유 및 비고' 필수. `TERMINATED` 접수 상태 표시 없음.
 * - FLOW-2c: 사업자 계약 칸의 정지·정지 해제·해지(허용되는 것만 — D-GOV-022 사유 필수·근거 선택), 해지 확인 단계·정지 대화상자의 연결 가맹점 n개 안내, 연 때의 계약 순번·사건 수 고정(MAJOR-1).
 * - 내부 키(biz_·adm_)·MASTER 행위자 참조·계정 키는 DOM·URL·저장소에 없다(근거 참조 원문만 — D-FLOW-004 ④). 결과 불명은 같은 submissionId 재전송.
 * 기대값 변경(FLOW-2b — 보고서 목록): 옛 PROGRESS의 TERMINATED 이관 건·해지 전이 행 삭제(D-GOV-018 ②ⓑ), OPERATOR의 '버튼·입력 없음' → 허용 버튼만(§4B.11 2).
 */
const user = userEvent.setup({ delay: null })
const TODAY = '2026-11-26'
const ME = { actorId: 'adm_01JC00000000000000000000V1', actorName: '운영 담당' }

const transition = (seq: number, from: ContractIntakeView['status'] | null, to: ContractIntakeView['status'], documentRef: string | null = null, actorName: string | null = '영업 담당') =>
  ({ transitionSeq: seq, fromStatus: from, toStatus: to, migrated: false, documentRef, actorKind: 'ADMIN' as const, actorName, occurredAt: '2026-11-21T01:00:00.000Z' })

const MIGRATED_INTAKE: ContractIntakeView = {
  intakeSeq: 1, status: 'COMPLETED', kind: 'NEW', targetContractSeq: null, migrated: true, receivedOn: null, intakeChannel: null, contractManagerName: null,
  desiredServices: [], version: 0, createdAt: '2026-10-01T00:00:00.000Z', updatedAt: '2026-10-01T00:00:00.000Z',
  transitions: [{ transitionSeq: 1, fromStatus: null, toStatus: 'COMPLETED', migrated: true, documentRef: null, actorKind: 'SYSTEM', actorName: null, occurredAt: '2026-10-01T00:00:00.000Z' }],
}
const COMPLETED_CHANGE: ContractIntakeView = {
  intakeSeq: 2, status: 'COMPLETED', kind: 'CHANGE', targetContractSeq: 1, migrated: false, receivedOn: '2026-11-20', intakeChannel: 'PHONE', contractManagerName: '영업 담당',
  desiredServices: ['CARD_AUTH', 'CARD_KEYIN'], version: 2, createdAt: '2026-11-20T01:00:00.000Z', updatedAt: '2026-11-22T01:00:00.000Z',
  transitions: [
    transition(1, null, 'CONSULTING'),
    transition(2, 'CONSULTING', 'DOCUMENT_RECEIVED'),
    transition(3, 'DOCUMENT_RECEIVED', 'COMPLETED', 'DEMO-CONTRACT-0002', null),
  ],
}
const openIntake = (status: 'CONSULTING' | 'DOCUMENT_RECEIVED', kind: 'NEW' | 'CHANGE' = 'CHANGE', seq = 3): ContractIntakeView => ({
  intakeSeq: seq, status, kind, targetContractSeq: kind === 'CHANGE' ? 1 : null, migrated: false, receivedOn: '2026-11-25', intakeChannel: 'DIRECT_SALES',
  contractManagerName: '운영 담당', desiredServices: ['CARD_AUTH'], version: status === 'CONSULTING' ? 0 : 1, createdAt: '2026-11-25T01:00:00.000Z',
  updatedAt: '2026-11-25T02:00:00.000Z',
  transitions: status === 'CONSULTING' ? [transition(1, null, 'CONSULTING')] : [transition(1, null, 'CONSULTING'), transition(2, 'CONSULTING', 'DOCUMENT_RECEIVED')],
})

/** 이관 v1(닫힘) + v2(예정 — 2026-11-27 시작, 2026-11-26 10:00 KST 기록) — golden v2와 같은 모양. */
const contract = (status: BusinessContractView['status'] = 'ACTIVE', overrides: Partial<BusinessContractView> = {}): BusinessContractView => ({
  contractSeq: 1, migrated: true, status, applyingVersionNo: status === 'ACTIVE' || status === 'SUSPENDED' ? 1 : null, scheduledVersionNo: 2, linkedMerchantCount: 2,
  createdAt: '2026-11-21T03:00:00.000Z',
  versions: [
    {
      versionNo: 1, migrated: true, startsAt: '2026-11-21T03:00:00.000Z', startDate: null, endDate: null, services: ['CARD_PG_PAYMENT'], documentRef: null,
      closedAt: '2026-11-26T15:00:00.000Z', createdAt: '2026-11-21T03:00:00.000Z', actorKind: 'SYSTEM', actorName: null,
    },
    {
      versionNo: 2, migrated: false, startsAt: '2026-11-26T15:00:00.000Z', startDate: '2026-11-27', endDate: '2027-11-26', services: ['CARD_PG_PAYMENT'],
      documentRef: 'DEMO-DOC-0001', closedAt: null, createdAt: '2026-11-26T01:00:00.000Z', actorKind: 'ADMIN', actorName: '영업 담당',
    },
  ],
  events: [],
  ...overrides,
})

const progress = (businessContract: BusinessContractView | null, intakes: ContractIntakeView[]): ContractProgress =>
  ({ available: true, today: TODAY, businessContract, intakes })
const PROGRESS = progress(contract(), [COMPLETED_CHANGE, MIGRATED_INTAKE])

const detailWith = (contractProgress: ContractProgress): BusinessDetail => ({ ...individualRegisteredDetail(1), contractProgress })

function renderScreen(detail: BusinessDetail | (() => BusinessDetail), canEdit: boolean, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const read = typeof detail === 'function' ? detail : () => detail
  let submissionSeq = 0
  const api = fakeApi({
    searchBusinesses: vi.fn(async () => ({ items: [listItemOf(read())], next: null })),
    businessDetail: vi.fn(async () => read()),
    ...overrides,
  })
  const view = render(
    <BusinessesScreen api={api} memory={createScreenMemory()} canEdit={canEdit} onContinue={vi.fn()} handoffState={() => 'empty'}
      actorId={ME.actorId} actorName={ME.actorName} newSubmissionId={() => `${'0'.repeat(31)}${++submissionSeq}`} />,
  )
  return { api, view }
}
const openSection = async () => {
  await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
  await screen.findByRole('region', { name: '사업자 상세' })
  return screen.findByRole('region', { name: '계약 진행' })
}
const buttonNames = (el: HTMLElement) => within(el).queryAllByRole('button').map((b) => b.textContent)
const contractColumn = (section: HTMLElement) => within(section).getByRole('group', { name: '사업자 계약' })
const intakeGroup = (section: HTMLElement, seq: number) => within(section).getByRole('group', { name: `계약 접수 ${seq}` })

describe('MERCHANT-FLOW-001 FLOW-1 — 사업자 상세 계약 진행 섹션(접수 건 표시)', () => {
  it('접수 건 최신부터: 상태·접수일·경로·담당자(표시명)·희망 서비스(참고용 문구)·근거, 전이 이력 표; R-1 안내', async () => {
    renderScreen(detailWith(PROGRESS), true)
    const section = await openSection()
    expect(within(section).getByRole('heading', { name: '계약 진행' })).toBeInTheDocument()
    expect(section).toHaveTextContent('결제 허용·MID 발급은 가맹점 계약 승인으로 정합니다')
    const intakes = within(section).getAllByRole('group').filter((g) => g.getAttribute('aria-label')?.startsWith('계약 접수'))
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

  // 기대값 변경(FLOW-2b — D-GOV-018 ②ⓑ): 옛 시험은 이관 건이 '계약 해지'로 바뀐 행(계약 완료 → 계약 해지, DEMO-TERM-0001)을 보였다. 접수 건 해지 전이는 없어졌다.
  it('R-6 이관 건: "계약 완료(이관) · 근거: 이관", 담당자·처리자 이관; 해지 전이 행 없음', async () => {
    renderScreen(detailWith(PROGRESS), false)
    const section = await openSection()
    const migrated = intakeGroup(section, 1)
    expect(within(migrated).getByText('계약 완료(이관) · 근거: 이관')).toBeInTheDocument()
    const rows = within(within(migrated).getByRole('table', { name: '계약 접수 1 진행 이력' })).getAllByRole('row').slice(1)
    expect(rows.map((r) => within(r).getAllByRole('cell').slice(0, 4).map((c) => c.textContent))).toEqual([['1', '이관 → 계약 완료', '이관', '이관']])
    expect(migrated).toHaveTextContent('계약 담당자이관')
  })

  it('이관 완료 건만 있는 사업자: 섹션에 "계약 완료(이관) · 근거: 이관"', async () => {
    renderScreen(detailWith(progress(contract(), [MIGRATED_INTAKE])), true)
    const section = await openSection()
    expect(within(section).getByText('계약 완료(이관) · 근거: 이관')).toBeInTheDocument()
  })

  // 기대값 변경(FLOW-2b §4B.11 2): 옛 시험은 OPERATOR·APPROVER 모두 '버튼·입력 없음'(FLOW-1 읽기 전용). 이제 APPROVER만 버튼·입력이 없다(OPERATOR는 허용 버튼 — 아래 시험).
  it('APPROVER(조회 역할): 근거 참조 원문이 보이고(D-FLOW-004 ④) 쓰기 동작(버튼·입력)이 섹션에 없다', async () => {
    renderScreen(detailWith(progress(contract(), [openIntake('DOCUMENT_RECEIVED'), COMPLETED_CHANGE, MIGRATED_INTAKE])), false)
    const section = await openSection()
    expect(section).toHaveTextContent('DEMO-CONTRACT-0002')
    expect(section).toHaveTextContent('DEMO-DOC-0001')
    expect(within(section).queryAllByRole('button', { name: /접수|되돌리기|계약 완료|계약 불가/ })).toHaveLength(0)
    expect(section.querySelectorAll('input, select, textarea')).toHaveLength(0)
  })

  it('접수 건 없음: 빈 안내', async () => {
    renderScreen(detailWith(progress(null, [])), true)
    const section = await openSection()
    expect(section).toHaveTextContent('계약 접수 건이 없습니다.')
    expect(within(section).queryAllByRole('group', { name: /계약 접수/ })).toHaveLength(0)
  })

  it('fail-safe: 섹션을 불러오지 못하면 섹션만 안내, 상세(기본정보)는 그대로', async () => {
    renderScreen(detailWith({ available: false, intakes: [] }), true)
    const section = await openSection()
    expect(section).toHaveTextContent('계약 진행을 불러오지 못했습니다')
    expect(within(section).queryAllByRole('button')).toHaveLength(0)
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

describe('MERCHANT-FLOW-001 FLOW-2b — 사업자 계약 칸(§4B.11 1)', () => {
  it('유효 계약: 상태 배지 "유효", 적용 중 버전 1(이관 — 카드 PG 결제), 예정 버전 2(시작일~종료일), 버전 이력 표에 기록 시각 병기, R-1 문구', async () => {
    renderScreen(detailWith(PROGRESS), true)
    const column = contractColumn(await openSection())
    expect(within(column).getByText('유효', { selector: '[data-slot="badge"]' })).toBeInTheDocument()
    expect(column).toHaveTextContent('사업자 계약은 새 가맹점 등록 자격입니다 — 결제 허용은 가맹점 계약 승인에서 정합니다')
    expect(column).toHaveTextContent('적용 중 버전버전 1 · 카드 PG 결제 · 이관')
    expect(column).toHaveTextContent('예정 버전버전 2 · 카드 PG 결제 · 2026-11-27 ~ 2027-11-26')
    expect(column).toHaveTextContent('연결 가맹점2개')
    const rows = within(within(column).getByRole('table', { name: '사업자 계약 버전 이력' })).getAllByRole('row')
    expect(within(rows[0]!).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['버전', '기간', '범위', '근거 참조', '기록', '기록 시각'])
    expect(rows.slice(1).map((r) => within(r).getAllByRole('cell').map((c) => c.textContent))).toEqual([
      ['1', '이관', '카드 PG 결제', '이관', '이관', '2026-11-21 12:00:00'],
      ['2', '2026-11-27 ~ 2027-11-26', '카드 PG 결제', 'DEMO-DOC-0001', '영업 담당', '2026-11-26 10:00:00'],
    ])
    // 기대값 변경(FLOW-2c §4B.11 3 — FLOW-2b 때 "해지·정지 버튼은 FLOW-2c(없음)"로 둔 자리): OPERATOR + 유효 계약 → 정지·해지 버튼(허용되는 것만 — FLOW-2c 시험이 상태별로 고정).
    expect(within(column).queryAllByRole('button').map((b) => b.textContent)).toEqual(['정지', '해지'])
  })

  it.each([
    ['NOT_YET_EFFECTIVE', '효력 전'],
    ['ACTIVE', '유효'],
    ['SUSPENDED', '정지'],
    ['TERMINATED', '해지'],
    ['EXPIRED', '만료'],
  ] as const)('상태 %s → 배지 "%s"', async (status, label) => {
    renderScreen(detailWith(progress(contract(status), [MIGRATED_INTAKE])), true)
    const column = contractColumn(await openSection())
    expect(within(column).getByText(label, { selector: '[data-slot="badge"]' })).toBeInTheDocument()
  })

  it('종료일 없는 일반 버전은 "시작일 ~ 종료일 없음", 적용 중 버전이 없으면 "-"; 정지·해지 기록 표(근거 원문, 사유 원문은 이력 탭)', async () => {
    const c = contract('TERMINATED', {
      applyingVersionNo: null, scheduledVersionNo: null,
      versions: [{
        versionNo: 1, migrated: false, startsAt: '2026-11-25T15:00:00.000Z', startDate: '2026-11-26', endDate: null, services: ['CARD_PG_PAYMENT'], documentRef: 'DEMO-DOC-0009',
        closedAt: null, createdAt: '2026-11-26T01:00:00.000Z', actorKind: 'ADMIN', actorName: '영업 담당',
      }],
      events: [{ eventSeq: 1, kind: 'TERMINATE', evidenceRef: 'DEMO-TERM-0001', actorKind: 'ADMIN', actorName: '조회 담당', occurredAt: '2026-11-26T03:00:00.000Z' }],
    })
    renderScreen(detailWith(progress(c, [])), true)
    const column = contractColumn(await openSection())
    expect(column).toHaveTextContent('적용 중 버전-')
    const versions = within(within(column).getByRole('table', { name: '사업자 계약 버전 이력' })).getAllByRole('row').slice(1)
    expect(within(versions[0]!).getAllByRole('cell')[1]!.textContent).toBe('2026-11-26 ~ 종료일 없음')
    const events = within(within(column).getByRole('table', { name: '사업자 계약 정지·해지 기록' })).getAllByRole('row').slice(1)
    expect(events.map((r) => within(r).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([['1', '해지', 'DEMO-TERM-0001', '조회 담당', '2026-11-26 12:00:00']])
  })

  it('계약 없음 → "사업자 계약 없음 — 신규 접수 후 계약 완료가 필요합니다"', async () => {
    renderScreen(detailWith(progress(null, [])), true)
    const column = contractColumn(await openSection())
    expect(column).toHaveTextContent('사업자 계약 없음 — 신규 접수 후 계약 완료가 필요합니다')
  })
})

describe('MERCHANT-FLOW-001 FLOW-2b — 접수 건 종류·허용 버튼(§4B.11 2)', () => {
  it('종류 배지: 신규·변경', async () => {
    renderScreen(detailWith(PROGRESS), true)
    const section = await openSection()
    expect(within(intakeGroup(section, 2)).getByText('변경', { selector: '[data-slot="badge"]' })).toBeInTheDocument()
    expect(within(intakeGroup(section, 1)).getByText('신규', { selector: '[data-slot="badge"]' })).toBeInTheDocument()
  })

  it('TERMINATED 접수 상태 표시 없음: 라벨 표에 없고 화면에 "계약 해지"가 없다', async () => {
    expect(Object.keys(CONTRACT_STATUS_LABELS)).toEqual(['CONSULTING', 'DOCUMENT_RECEIVED', 'COMPLETED', 'REJECTED'])
    renderScreen(detailWith(PROGRESS), true)
    const section = await openSection()
    expect(section).not.toHaveTextContent('계약 해지')
  })

  it('OPERATOR: 상담 중 → [계약서 접수, 계약 불가], 계약서 접수 → [되돌리기, 계약 완료, 계약 불가], 종결 건 → 버튼 없음; 열린 건이 있으면 새 접수 버튼 없음', async () => {
    renderScreen(detailWith(progress(contract(), [openIntake('CONSULTING', 'CHANGE', 4), COMPLETED_CHANGE, MIGRATED_INTAKE])), true)
    const section = await openSection()
    expect(buttonNames(intakeGroup(section, 4))).toEqual(['계약서 접수', '계약 불가'])
    expect(buttonNames(intakeGroup(section, 2))).toEqual([])
    expect(buttonNames(intakeGroup(section, 1))).toEqual([])
    expect(within(section).queryByRole('button', { name: /신규 접수|변경 접수/ })).toBeNull()
  })

  it('OPERATOR: 계약서 접수 건 → [되돌리기, 계약 완료, 계약 불가]', async () => {
    renderScreen(detailWith(progress(null, [openIntake('DOCUMENT_RECEIVED', 'NEW')])), true)
    const section = await openSection()
    expect(buttonNames(intakeGroup(section, 3))).toEqual(['되돌리기', '계약 완료', '계약 불가'])
  })

  it.each([
    ['계약 없음', '신규 접수', null],
    ['효력 전', '변경 접수', contract('NOT_YET_EFFECTIVE')],
    ['유효', '변경 접수', contract('ACTIVE')],
    ['정지', '변경 접수', contract('SUSPENDED')],
    ['해지', '신규 접수', contract('TERMINATED')],
    ['만료', '신규 접수', contract('EXPIRED')],
  ] as const)('열린 건 없음 + %s → 새 접수 버튼 이름 "%s"', async (_name, button, c) => {
    renderScreen(detailWith(progress(c, [MIGRATED_INTAKE])), true)
    const section = await openSection()
    expect(within(section).getByRole('button', { name: button })).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: button === '신규 접수' ? '변경 접수' : '신규 접수' })).toBeNull()
  })

  it.each([['TERMINATED'], ['EXPIRED']] as const)('변경 접수가 열려 있고 계약이 %s → 계약 완료 버튼 대신 안내, 계약 불가는 남는다(BR-BIZ-333)', async (status) => {
    renderScreen(detailWith(progress(contract(status), [openIntake('DOCUMENT_RECEIVED', 'CHANGE')])), true)
    const group = intakeGroup(await openSection(), 3)
    expect(buttonNames(group)).toEqual(['되돌리기', '계약 불가'])
    expect(group).toHaveTextContent('계약이 해지·만료되어 계약 불가로만 닫을 수 있습니다')
  })
})

describe('MERCHANT-FLOW-001 FLOW-2b — 명령 대화상자', () => {
  const success = { outcome: 'SUCCEEDED' as const, reference: bizId(1), replayed: false }

  it('신규 접수: 접수일 기본 = 서버 KST 오늘(미래 불가), 경로(영업대행사 선택 불가), 담당자 = 본인 표시명, 희망 서비스 참고용(가상계좌 준비 전), 저장 → API 본문 → 상세 다시 읽기', async () => {
    const openContractIntake = vi.fn(async () => success)
    const { api } = renderScreen(detailWith(progress(null, [])), true, { openContractIntake })
    const section = await openSection()
    await user.click(within(section).getByRole('button', { name: '신규 접수' }))
    const dialog = within(section).getByRole('dialog', { name: '신규 접수' })
    const received = within(dialog).getByLabelText('접수일') as HTMLInputElement
    expect(received.value).toBe(TODAY)
    expect(received.max).toBe(TODAY)
    const channel = within(dialog).getByLabelText('접수 경로') as HTMLSelectElement
    expect([...channel.options].map((o) => `${o.textContent}${o.disabled ? '(disabled)' : ''}`)).toEqual([
      '고르세요', '직접 영업', '전화', '홈페이지', '영업대행사 — 준비 전(disabled)', '기타',
    ])
    expect(dialog).toHaveTextContent('계약 담당자운영 담당(본인)')
    expect((within(dialog).getByLabelText('가상계좌 — 준비 전') as HTMLInputElement).disabled).toBe(true)
    expect(dialog).toHaveTextContent('희망 서비스 — 참고용, 실제 서비스는 가맹점 계약 승인·개통에서 정합니다')
    await user.click(within(dialog).getByRole('button', { name: '접수 저장' }))
    expect(dialog).toHaveTextContent('접수 경로를 고르세요.')
    await user.clear(received)
    await user.type(received, '2026-11-27')
    await user.selectOptions(channel, '직접 영업')
    await user.click(within(dialog).getByRole('button', { name: '접수 저장' }))
    expect(dialog).toHaveTextContent('접수일은 오늘(한국 시간) 이후로 고를 수 없습니다.')
    expect(openContractIntake).not.toHaveBeenCalled()
    await user.clear(received)
    await user.type(received, '2026-11-25')
    await user.click(within(dialog).getByLabelText('카드 인증 결제'))
    await user.click(within(dialog).getByRole('button', { name: '접수 저장' }))
    expect(openContractIntake).toHaveBeenCalledWith(bizId(1), {
      submissionId: `${'0'.repeat(31)}1`, expectedKind: 'NEW', receivedOn: '2026-11-25', intakeChannel: 'DIRECT_SALES', contractManagerId: ME.actorId,
      desiredServices: ['CARD_AUTH'], reason: null,
    })
    await screen.findByText('저장했습니다. 계약 진행을 다시 불러왔습니다.')
    expect(api.businessDetail).toHaveBeenCalledTimes(2)
  })

  it('계약 완료 대화상자: 근거 필수·형식·카드번호, 시작일 기본 오늘·과거 불가, 종료일 < 시작일 불가, 범위(카드 PG 결제 — 희망 서비스와 별개), 오타 정정 안내 → 본문', async () => {
    const completeContractIntake = vi.fn(async () => success)
    renderScreen(detailWith(progress(contract(), [openIntake('DOCUMENT_RECEIVED', 'CHANGE')])), true, { completeContractIntake })
    const group = intakeGroup(await openSection(), 3)
    await user.click(within(group).getByRole('button', { name: '계약 완료' }))
    const dialog = within(group).getByRole('dialog', { name: '계약 완료' })
    expect(dialog).toHaveTextContent('저장 뒤에는 버전 내용(기간·범위)을 고칠 수 없습니다. 같은 날 만든 버전의 오타는 다음 날 이후 시작하는 새 버전(변경 접수 → 계약 완료)으로만 정정합니다')
    const start = within(dialog).getByLabelText('시작일') as HTMLInputElement
    expect(start.value).toBe(TODAY)
    expect(start.min).toBe(TODAY)
    const scope = within(dialog).getByRole('checkbox', { name: '카드 PG 결제' }) as HTMLInputElement
    expect(scope.checked).toBe(true)
    expect(within(dialog).getByRole('group', { name: '계약 범위' })).not.toHaveTextContent('카드 인증 결제')
    expect(within(dialog).getByLabelText('사유 및 비고(선택)')).toBeInTheDocument()
    const save = () => user.click(within(dialog).getByRole('button', { name: '계약 완료 저장' }))
    await save()
    expect(dialog).toHaveTextContent('계약서 수령 근거 참조를 입력하세요.')
    const ref = within(dialog).getByLabelText('계약서 수령 근거 참조')
    await user.type(ref, '계약서 받음')
    await save()
    expect(dialog).toHaveTextContent('근거 참조는 영문·숫자와 . _ : # / - 만 1~128자로 입력하세요.')
    await user.clear(ref)
    await user.type(ref, 'DOC-4111111111111111')
    await save()
    expect(dialog).toHaveTextContent('근거 참조에 카드번호 모양의 숫자를 넣을 수 없습니다.')
    await user.clear(ref)
    await user.type(ref, 'DEMO-DOC-0003')
    await user.clear(start)
    await user.type(start, '2026-11-25')
    await save()
    expect(dialog).toHaveTextContent('시작일은 오늘(한국 시간) 이전으로 고를 수 없습니다.')
    await user.clear(start)
    await user.type(start, '2026-12-01')
    await user.type(within(dialog).getByLabelText('종료일(선택)'), '2026-11-30')
    await save()
    expect(dialog).toHaveTextContent('종료일은 시작일과 같거나 뒤여야 합니다.')
    await user.click(scope)
    await user.clear(within(dialog).getByLabelText('종료일(선택)'))
    await save()
    expect(dialog).toHaveTextContent('계약 범위를 하나 이상 고르세요.')
    expect(completeContractIntake).not.toHaveBeenCalled()
    await user.click(scope)
    await save()
    expect(completeContractIntake).toHaveBeenCalledWith(bizId(1), 3, {
      submissionId: `${'0'.repeat(31)}1`, expectedVersion: 1, documentRef: 'DEMO-DOC-0003', startDate: '2026-12-01', endDate: null, services: ['CARD_PG_PAYMENT'], reason: null,
    })
  })

  it('되돌리기·계약 불가는 "사유 및 비고" 필수(카드번호 패턴 거부), 계약서 접수는 선택 → 전이 본문(expectedVersion = 접수 건 version)', async () => {
    const moveContractIntake = vi.fn(async () => success)
    renderScreen(detailWith(progress(contract(), [openIntake('DOCUMENT_RECEIVED', 'CHANGE')])), true, { moveContractIntake })
    const group = intakeGroup(await openSection(), 3)
    await user.click(within(group).getByRole('button', { name: '계약 불가' }))
    const dialog = within(group).getByRole('dialog', { name: '계약 불가' })
    const reason = within(dialog).getByLabelText('사유 및 비고')
    await user.click(within(dialog).getByRole('button', { name: '확인' }))
    expect(dialog).toHaveTextContent("'사유 및 비고'를 입력하세요.")
    await user.type(reason, '카드 4111 1111 1111 1111')
    await user.click(within(dialog).getByRole('button', { name: '확인' }))
    expect(dialog).toHaveTextContent("'사유 및 비고'에 카드번호 모양의 숫자를 넣을 수 없습니다.")
    expect(moveContractIntake).not.toHaveBeenCalled()
    await user.clear(reason)
    await user.type(reason, '고객 사정으로 진행하지 않음')
    await user.click(within(dialog).getByRole('button', { name: '확인' }))
    expect(moveContractIntake).toHaveBeenCalledWith(bizId(1), 3, { submissionId: `${'0'.repeat(31)}1`, expectedVersion: 1, target: 'REJECTED', reason: '고객 사정으로 진행하지 않음' })
  })

  it('계약서 접수(상담 중 건)는 사유 선택 — 빈 칸이면 null로 보낸다', async () => {
    const moveContractIntake = vi.fn(async () => success)
    renderScreen(detailWith(progress(contract(), [openIntake('CONSULTING', 'CHANGE')])), true, { moveContractIntake })
    const group = intakeGroup(await openSection(), 3)
    await user.click(within(group).getByRole('button', { name: '계약서 접수' }))
    const dialog = within(group).getByRole('dialog', { name: '계약서 접수' })
    expect(within(dialog).getByLabelText('사유 및 비고(선택)')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '확인' }))
    expect(moveContractIntake).toHaveBeenCalledWith(bizId(1), 3, { submissionId: `${'0'.repeat(31)}1`, expectedVersion: 0, target: 'DOCUMENT_RECEIVED', reason: null })
  })

  it('저장된 거절은 한국어 안내(코드 원문 없음), 결과 불명은 잠금 + 같은 submissionId로 다시 보내기', async () => {
    const completeContractIntake = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'REJECTED' as const, rejection: 'BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS', replayed: true })
    renderScreen(detailWith(progress(contract(), [openIntake('DOCUMENT_RECEIVED', 'CHANGE')])), true, { completeContractIntake })
    const group = intakeGroup(await openSection(), 3)
    await user.click(within(group).getByRole('button', { name: '계약 완료' }))
    const dialog = within(group).getByRole('dialog', { name: '계약 완료' })
    await user.type(within(dialog).getByLabelText('계약서 수령 근거 참조'), 'DEMO-DOC-0004')
    await user.click(within(dialog).getByRole('button', { name: '계약 완료 저장' }))
    await within(dialog).findByText('처리되었는지 알 수 없습니다. 같은 내용으로 다시 보내 확인해 주세요.')
    expect(within(dialog).getByLabelText('계약서 수령 근거 참조')).toBeDisabled()
    expect(within(dialog).queryByRole('button', { name: '취소' })).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: '같은 내용으로 다시 보내기' }))
    expect(completeContractIntake).toHaveBeenCalledTimes(2)
    expect(completeContractIntake.mock.calls[1]).toEqual(completeContractIntake.mock.calls[0])
    await within(dialog).findByText('이미 예정 버전이 있어 새 버전을 만들 수 없습니다.')
    expect(dialog).not.toHaveTextContent('BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS')
  })

  it('DOM·URL·저장소: 명령 뒤에도 내부 키(biz_·adm_)·계정 키·근거 원문 외 값이 없다(근거 참조 원문은 상세 표시만)', async () => {
    const before = window.location.href
    const openContractIntake = vi.fn(async () => success)
    const { view } = renderScreen(detailWith(progress(contract(), [COMPLETED_CHANGE, MIGRATED_INTAKE])), true, { openContractIntake })
    const section = await openSection()
    await user.click(within(section).getByRole('button', { name: '변경 접수' }))
    const dialog = within(section).getByRole('dialog', { name: '변경 접수' })
    await user.selectOptions(within(dialog).getByLabelText('접수 경로'), '전화')
    await user.click(within(dialog).getByRole('button', { name: '접수 저장' }))
    expect(openContractIntake).toHaveBeenCalledWith(bizId(1), expect.objectContaining({ expectedKind: 'CHANGE', contractManagerId: ME.actorId }))
    for (const needle of ['biz_', 'adm_', 'admin:', 'system:', ME.actorId]) expect(domOccurrences(view.container, needle)).toEqual([])
    expect(window.location.href).toBe(before)
    expect(window.location.href).not.toContain('biz_')
    expect(storageIsEmpty()).toBe(true)
  })
})

describe('MERCHANT-FLOW-001 FLOW-2c — 사업자 계약 정지·정지 해제·해지(§4B.11 3, D-GOV-022)', () => {
  const contractButtons = (section: HTMLElement) => buttonNames(contractColumn(section))

  it('OPERATOR: 허용되는 버튼만 — 효력 전·유효 [정지, 해지], 정지 [정지 해제, 해지], 해지·만료 없음; APPROVER는 버튼 없음', async () => {
    const cases: [BusinessContractView['status'], string[]][] = [
      ['NOT_YET_EFFECTIVE', ['정지', '해지']], ['ACTIVE', ['정지', '해지']], ['SUSPENDED', ['정지 해제', '해지']], ['TERMINATED', []], ['EXPIRED', []],
    ]
    for (const [status, expected] of cases) {
      const { view } = renderScreen(detailWith(progress(contract(status), [MIGRATED_INTAKE])), true)
      expect(contractButtons(await openSection())).toEqual(expected)
      view.unmount()
    }
    renderScreen(detailWith(progress(contract('ACTIVE'), [MIGRATED_INTAKE])), false)
    expect(contractButtons(await openSection())).toEqual([])
  })

  it('정지: "사유 및 비고" 필수(카드번호 패턴 거부)·근거 참조 선택(형식 검사) → 본문(expectedEventCount = 본 사건 수) → 상세 다시 읽기', async () => {
    const suspendBusinessContract = vi.fn(async () => ({ outcome: 'SUCCEEDED' as const, reference: 'biz_x', replayed: false }))
    const withEvent = contract('ACTIVE', {
      events: [{ eventSeq: 1, kind: 'SUSPEND', evidenceRef: null, occurredAt: '2026-11-25T01:00:00.000Z', actorKind: 'ADMIN', actorName: '운영 담당' },
        { eventSeq: 2, kind: 'RESUME', evidenceRef: null, occurredAt: '2026-11-25T02:00:00.000Z', actorKind: 'ADMIN', actorName: '운영 담당' }],
    })
    const { api } = renderScreen(detailWith(progress(withEvent, [MIGRATED_INTAKE])), true, { suspendBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '정지' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 정지' })
    await user.click(within(dialog).getByRole('button', { name: '정지 저장' }))
    expect(dialog).toHaveTextContent("'사유 및 비고'를 입력하세요.")
    await user.type(within(dialog).getByLabelText('사유 및 비고'), 'card 4111 1111 1111 1111')
    await user.type(within(dialog).getByLabelText('근거 참조(선택)'), 'bad ref')
    await user.click(within(dialog).getByRole('button', { name: '정지 저장' }))
    expect(suspendBusinessContract).not.toHaveBeenCalled()
    await user.clear(within(dialog).getByLabelText('사유 및 비고'))
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '거래 확인 필요')
    await user.clear(within(dialog).getByLabelText('근거 참조(선택)'))
    await user.type(within(dialog).getByLabelText('근거 참조(선택)'), 'CASE/2026-11:77')
    const reads = (api.businessDetail as ReturnType<typeof vi.fn>).mock.calls.length
    await user.click(within(dialog).getByRole('button', { name: '정지 저장' }))
    expect(suspendBusinessContract).toHaveBeenCalledWith(expect.any(String), 1, {
      submissionId: expect.any(String), expectedEventCount: 2, reason: '거래 확인 필요', evidenceRef: 'CASE/2026-11:77',
    })
    await waitFor(() => expect((api.businessDetail as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(reads))
  })

  it('해지: 입력 뒤 확인 단계에 "연결 가맹점 n개(종료 제외) — 기존 가맹점의 결제·정산은 바뀌지 않습니다" 안내 → 해지 확정에서만 API 호출(근거 없음 = null)', async () => {
    const terminateBusinessContract = vi.fn(async () => ({ outcome: 'SUCCEEDED' as const, reference: 'biz_x', replayed: false }))
    renderScreen(detailWith(progress(contract('SUSPENDED'), [MIGRATED_INTAKE])), true, { terminateBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '해지' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 해지' })
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '계약 종료 합의')
    await user.click(within(dialog).getByRole('button', { name: '다음' }))
    expect(terminateBusinessContract).not.toHaveBeenCalled()
    expect(dialog).toHaveTextContent('연결 가맹점 2개(종료 제외) — 기존 가맹점의 결제·정산은 바뀌지 않습니다. 필요하면 가맹점마다 중지/철회하세요.')
    expect(dialog).toHaveTextContent('해지는 되돌릴 수 없습니다')
    await user.click(within(dialog).getByRole('button', { name: '해지 확정' }))
    expect(terminateBusinessContract).toHaveBeenCalledTimes(1)
    expect(terminateBusinessContract).toHaveBeenCalledWith(expect.any(String), 1, {
      submissionId: expect.any(String), expectedEventCount: 0, reason: '계약 종료 합의', evidenceRef: null,
    })
  })

  it('정지 해제: 저장된 거절(기대 사건 수 불일치)은 한국어 안내(code 원문 없음)', async () => {
    const resumeBusinessContract = vi.fn(async () => ({ outcome: 'REJECTED' as const, rejection: 'BUSINESS_CONTRACT_STATE_CONFLICT', replayed: false }))
    renderScreen(detailWith(progress(contract('SUSPENDED'), [MIGRATED_INTAKE])), true, { resumeBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '정지 해제' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 정지 해제' })
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '확인 완료')
    await user.click(within(dialog).getByRole('button', { name: '정지 해제 저장' }))
    await within(dialog).findByText('다른 사람이 먼저 사업자 계약을 바꿨습니다. 다시 불러온 내용을 확인하세요.')
    expect(dialog).not.toHaveTextContent('BUSINESS_CONTRACT_STATE_CONFLICT')
  })

  it('리뷰 NIT-B: 결과 불명 잠금 뒤 같은 submissionId 재전송이 400(INVALID_INPUT)이면 잠금이 풀리고(입력·취소 가능) 상세를 다시 읽는다', async () => {
    const suspendBusinessContract = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(400, 'INVALID_INPUT'))
    const { api } = renderScreen(detailWith(progress(contract('ACTIVE'), [MIGRATED_INTAKE])), true, { suspendBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '정지' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 정지' })
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '거래 확인 필요')
    await user.click(within(dialog).getByRole('button', { name: '정지 저장' }))
    await within(dialog).findByText('처리되었는지 알 수 없습니다. 같은 내용으로 다시 보내 확인해 주세요.')
    expect(within(dialog).getByLabelText('사유 및 비고')).toBeDisabled()
    const reads = (api.businessDetail as ReturnType<typeof vi.fn>).mock.calls.length
    await user.click(within(dialog).getByRole('button', { name: '같은 내용으로 다시 보내기' }))
    expect(suspendBusinessContract.mock.calls[1]).toEqual(suspendBusinessContract.mock.calls[0])
    await waitFor(() => expect(within(dialog).getByLabelText('사유 및 비고')).not.toBeDisabled())
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeInTheDocument()
    await waitFor(() => expect((api.businessDetail as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(reads))
  })

  it('리뷰 MINOR-2: 정지 대화상자에도 연결 가맹점 n개(종료 제외 — 대화상자 직전 섹션 조회 값) 안내', async () => {
    renderScreen(detailWith(progress(contract('ACTIVE'), [MIGRATED_INTAKE])), true)
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '정지' }))
    expect(within(column).getByRole('dialog', { name: '사업자 계약 정지' })).toHaveTextContent(
      '연결 가맹점 2개(종료 제외) — 기존 가맹점의 결제·정산은 바뀌지 않습니다. 필요하면 가맹점마다 중지/철회하세요.',
    )
  })

  it('리뷰 MAJOR-1: 대화상자를 연 때의 계약 순번·사건 수로만 보낸다 — 거절 뒤 재조회에서 다른 계약(#2)이 보이면 대화상자를 닫고 다시 열라고 안내(전송 없음)', async () => {
    // 명령을 보낸 뒤(거절)부터 재조회는 새 계약 #2를 돌려준다(그 사이 다른 사람이 해지 → 신규 계약 완료).
    let changed = false
    const second = contract('ACTIVE', { contractSeq: 2, migrated: false })
    const terminateBusinessContract = vi.fn(async () => {
      changed = true
      return { outcome: 'REJECTED' as const, rejection: 'BUSINESS_CONTRACT_STATE_CONFLICT', replayed: false }
    })
    renderScreen(() => detailWith(progress(changed ? second : contract('ACTIVE'), [MIGRATED_INTAKE])), true, { terminateBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '해지' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 해지' })
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '계약 종료 합의')
    await user.click(within(dialog).getByRole('button', { name: '다음' }))
    await user.click(within(dialog).getByRole('button', { name: '해지 확정' }))
    expect(terminateBusinessContract).toHaveBeenCalledTimes(1)
    expect(terminateBusinessContract).toHaveBeenCalledWith(expect.any(String), 1, expect.objectContaining({ expectedEventCount: 0 }))
    const section = await screen.findByRole('region', { name: '계약 진행' })
    await within(section).findByText('사업자 계약이 바뀌었습니다. 다시 불러온 내용을 확인하고 새로 여세요.')
    expect(within(section).queryByRole('dialog', { name: '사업자 계약 해지' })).toBeNull()
    expect(terminateBusinessContract).toHaveBeenCalledTimes(1)
  })

  it('리뷰 MAJOR-1: 같은 계약이라도 재조회에서 사건 수가 달라지면(다른 사람이 정지) 대화상자를 닫는다 — 옛 사건 수로 다시 보낼 수 없다', async () => {
    let changed = false
    const suspendedByOther = contract('SUSPENDED', {
      events: [{ eventSeq: 1, kind: 'SUSPEND', evidenceRef: null, occurredAt: '2026-11-26T01:00:00.000Z', actorKind: 'ADMIN', actorName: '다른 운영자' }],
    })
    const suspendBusinessContract = vi.fn(async () => {
      changed = true
      return { outcome: 'REJECTED' as const, rejection: 'BUSINESS_CONTRACT_STATE_CONFLICT', replayed: false }
    })
    renderScreen(() => detailWith(progress(changed ? suspendedByOther : contract('ACTIVE'), [MIGRATED_INTAKE])), true, { suspendBusinessContract })
    const column = contractColumn(await openSection())
    await user.click(within(column).getByRole('button', { name: '정지' }))
    const dialog = within(column).getByRole('dialog', { name: '사업자 계약 정지' })
    await user.type(within(dialog).getByLabelText('사유 및 비고'), '확인 필요')
    await user.click(within(dialog).getByRole('button', { name: '정지 저장' }))
    const section = await screen.findByRole('region', { name: '계약 진행' })
    await within(section).findByText('사업자 계약이 바뀌었습니다. 다시 불러온 내용을 확인하고 새로 여세요.')
    expect(within(section).queryByRole('dialog', { name: '사업자 계약 정지' })).toBeNull()
    expect(suspendBusinessContract).toHaveBeenCalledTimes(1)
  })
})
