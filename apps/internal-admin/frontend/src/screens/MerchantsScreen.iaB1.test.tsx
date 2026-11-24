import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantHistoryEntry, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'
import { changeTypeLabel, MERCHANT_CHANGE_TYPE_LABELS } from '../lib/merchantChangeTypes'

/**
 * MERCHANT-IA-001 B1(명세 docs/repository/MERCHANT-IA-001-SCREEN-ITEMS.md rev 0.2 §7 B1 — 결정 불필요한 잡음 제거, 사용자 결정 D-ADMIN-UI-010 배경):
 * ① 같은 MID 통화 표가 두 번 그려지지 않는다(DetailPanels 중복 표 삭제 — '종료 처리'는 개통 영역 통화 표의 열로)
 * ② Site 심사 표는 MID마다 반복하지 않고 가맹점에서 한 번(Site 패널 안) — 2인 요청·1인 거절·결과 불명 잠금 동작은 MerchantsScreen.activation·activationMemory 시험이 지킨다
 * ③ 변경 이력: '연산'(영문 원문) 열 없음, '변경' 열은 한국어 라벨만(영문 코드 줄 없음)
 * ④ 개통 점검표·서비스 개통 현황에 작업 배치 이름(B3-3·B4·이후 배치·L-17) 없음 — "준비 전", 중복 줄 "승인 시작 조건: …" 없음
 * ⑤ 목록·상세 안내문: 사실과 다른 "조회 전용"·"개통 상태를 조회" 없음, 감사 안내("상세 조회는 감사 기록에 남습니다")는 그대로
 * Demo 식별자, 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MID1 = id('mid', 1)
const MID2 = id('mid', 2)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 2, createdAt: '2026-11-01T00:00:00.000Z',
}
type Account = MerchantDetail['accounts'][number]
const account = (merchantAccountId: string, patch: Partial<Account> = {}): Account => ({
  merchantAccountId, lifecycle: 'ACTIVE', isDefault: merchantAccountId === MID1, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
  issuedAt: '2026-11-10T00:00:00.000Z',
  assignments: [{ assignmentId: id('asg', merchantAccountId === MID1 ? 1 : 2), merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
  paymentMethods: [], cardRoutes: [], siteConfirmations: [],
  // 종료된 KRW 기록 하나(효력 종료 2026-11-11 00:00Z, 종료 처리 2026-11-10 05:00Z) — '종료 처리' 시각이 개통 영역 통화 표에 보여야 한다.
  currencies: [{ enablementId: id('cen', merchantAccountId === MID1 ? 1 : 2), currencyCode: 'KRW', effectiveFrom: '2026-11-10T01:00:00.000Z', effectiveTo: '2026-11-11T00:00:00.000Z', endedAt: '2026-11-10T05:00:00.000Z' }],
  ...patch,
})
/** Named Fixture: 계약 승인·MID 두 개(둘 다 PRIMARY Site1)·Site 두 곳 심사 결정 없음 — MID마다 반복되던 Site 심사 표가 한 번만 보이는지 본다. */
function detail(patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
      { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'second.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
    ],
    contracts: [{
      contractId: id('ctr', 1), createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-10T16:00:00.000Z', scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
      agreements: [{ agreementId: id('csa', 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [account(MID1), account(MID2)], limits: [],
    propagation: { latestRevision: 3, latestRevisionIssuedAt: '2026-11-11T00:00:00.000Z', latestAckRevision: 3, latestAckAt: '2026-11-11T00:00:01.000Z', effectConfirmedAt: '2026-11-11T00:00:01.000Z', effectBasis: 'ACK' },
    pendingApprovals: [],
    ...patch,
  }
}
const historyEntry = (seq: number, operation: string, changeType: string): MerchantHistoryEntry => ({
  seq, occurredAt: '2026-11-10T00:00:00.000Z', operation, aggregateType: 'Merchant', aggregateId: MER1, changeType, actorRef: 'admin:adm_1',
})

async function openDetail(d: MerchantDetail, history: MerchantHistoryEntry[] = [], canRequestApproval = true) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => d),
    merchantHistory: vi.fn(async () => ({ items: history, nextCursor: null })),
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} now={NOW} newSubmissionId={() => 'f'.repeat(32)} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  await screen.findByRole('region', { name: 'MID·개통' })
  return api
}

describe('MERCHANT-IA-001 B1 — 가맹점 상세 잡음 제거', () => {
  it('① 통화 표는 MID마다 한 번(개통 영역) — 중복 표 없음, 종료 처리 시각은 그 표의 열', async () => {
    await openDetail(detail())
    const mids = screen.getByRole('region', { name: 'MID·개통' })
    expect(within(mids).getAllByRole('table', { name: '통화' })).toHaveLength(2)
    for (const [mid, cen] of [[MID1, id('cen', 1)], [MID2, id('cen', 2)]] as const) {
      const area = screen.getByRole('region', { name: `개통 ${mid}` })
      const table = within(area).getByRole('table', { name: '통화' })
      expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['통화', '효력 시작', '종료', '종료 처리', '상태'])
      const row = within(table).getByRole('row', { name: cen })
      expect(row).toHaveTextContent('2026-11-10 14:00:00')
      expect(row).toHaveTextContent('종료')
    }
  })

  it('② Site 심사 표는 가맹점에서 한 번(Site 패널 안) — MID 개통 영역에는 없다, 버튼·등록자는 그대로', async () => {
    await openDetail(detail())
    const tables = screen.getAllByRole('table', { name: 'Site 심사' })
    expect(tables).toHaveLength(1)
    expect(within(screen.getByRole('region', { name: 'Site' })).getByRole('table', { name: 'Site 심사' })).toBe(tables[0])
    for (const mid of [MID1, MID2]) expect(within(screen.getByRole('region', { name: `개통 ${mid}` })).queryByRole('table', { name: 'Site 심사' })).toBeNull()
    const row = within(tables[0]!).getByRole('row', { name: `심사 ${SITE1}` })
    expect(row).toHaveTextContent('대표(PRIMARY)')
    expect(row).toHaveTextContent('admin:adm_SR')
    expect(within(row).getByRole('button', { name: '심사 승인 요청' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: '심사 거절' })).toBeInTheDocument()
    expect(within(tables[0]!).getAllByRole('button', { name: '심사 거절' })).toHaveLength(2)
  })

  it('② MID가 없으면 Site 심사 표도 없다(이전 표시 조건 그대로 — 개통 영역이 있던 때만)', async () => {
    await openDetail(detail({ accounts: [] }))
    expect(screen.queryByRole('table', { name: 'Site 심사' })).toBeNull()
  })

  it('③ 변경 이력: 열은 순번·시각·대상·변경·행위자 — 연산(영문 원문) 없음, 변경은 한국어 라벨만', async () => {
    await openDetail(detail(), [historyEntry(1, 'RegisterMerchant', 'MerchantRegistered'), historyEntry(2, 'ChangeMerchantCode', 'MerchantCodeChanged')])
    const history = screen.getByRole('region', { name: '변경 이력' })
    const table = await within(history).findByRole('table', { name: '변경 이력 목록' })
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['순번', '시각', '대상', '변경', '행위자'])
    const first = await within(table).findByRole('row', { name: '1' })
    expect(first).toHaveTextContent('가맹점 등록')
    expect(first).not.toHaveTextContent('MerchantRegistered')
    expect(first).not.toHaveTextContent('RegisterMerchant')
    // '변경' 열 셀(넷째 칸)에 한국어 이름만.
    expect(within(first).getAllByRole('cell')[3]).toHaveTextContent(/^가맹점 등록$/)
    const second = within(table).getByRole('row', { name: '2' })
    expect(second).toHaveTextContent('가맹점 코드 변경')
    expect(second).not.toHaveTextContent('MerchantCodeChanged')
    expect(second).not.toHaveTextContent('ChangeMerchantCode')
  })

  it('④ 개통 점검표: 작업 배치 이름 없음, ⑦은 "준비 전", 중복 줄 "승인 시작 조건: …" 없음; 참고용 안내는 그대로', async () => {
    await openDetail(detail())
    const list = within(screen.getByRole('region', { name: `개통 ${MID1}` })).getByRole('region', { name: '개통 점검표' })
    for (const word of ['B3-3', 'B4', '이후 배치', 'L-17', '승인 시작 조건:']) expect(list).not.toHaveTextContent(word)
    const item7 = within(list).getByRole('listitem', { name: /^⑦ / })
    expect(item7.getAttribute('data-state')).toBe('LATER')
    expect(item7).toHaveTextContent('준비 전')
    expect(within(list).getByRole('listitem', { name: /^⑥ / })).toHaveTextContent('준비 전')
    expect(list).toHaveTextContent('이 표는 참고용입니다. 실제 판정은 결제 시점에 PAYMENT가 합니다')
    expect(list).toHaveTextContent('결제 생성 조건: 미충족(참고)')
    // L-17(Demo 한계)은 환경 구분 없이 일반 문구로.
    expect(list).toHaveTextContent('가맹점의 결제 호출에는 API 키가 따로 필요하며, 이 화면에서 발급하지 않습니다.')
    // 상세 전체에도 작업 배치 이름이 없다(서비스 개통 현황 포함).
    const detailArea = document.querySelector('.detailarea')!
    for (const word of ['B3-3', 'B4 이후', '이후 배치', '(B3-3·B4)', 'L-17']) expect(detailArea).not.toHaveTextContent(word)
    expect(within(screen.getByRole('region', { name: '서비스 개통 현황' })).getByText('준비 전')).toBeInTheDocument()
  })

  it('⑤ 목록·상세 안내문: "조회 전용"·"개통 상태를 조회" 없음, 감사 안내는 그대로', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
      merchantDetail: vi.fn(async () => detail()),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} now={NOW} />)
    const listNote = screen.getByRole('heading', { name: '가맹점 조회' }).closest('.mapnote') ?? document.body
    expect(listNote).toHaveTextContent('상세 조회는 감사 기록에 남습니다.')
    expect(document.body).not.toHaveTextContent('조회 전용')
    expect(document.body).not.toHaveTextContent('개통 상태를 조회')
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    await screen.findByRole('region', { name: 'MID·개통' })
    expect(document.body).toHaveTextContent('상세 조회는 감사 기록에 남습니다.')
    expect(document.body).not.toHaveTextContent('조회 전용')
    expect(document.body).not.toHaveTextContent('가맹점 상세입니다')
  })
})

describe('MERCHANT-IA-001 B1 — 변경 이력 종류 한국어 라벨', () => {
  /** MASTER가 남기는 변경 종류 전부(apps/master-api 변경 기록 changeType, 2026-11-24 기준) — 새 종류가 생기면 라벨을 먼저 더한다. */
  const MASTER_CHANGE_TYPES = [
    'BusinessCreated', 'BusinessProfileUpdated', 'BusinessRegistrationCompleted', 'BusinessRegistered',
    'MerchantRegistered', 'MerchantCodeChanged', 'MerchantSiteRegistered', 'MerchantSiteReviewDecided',
    'ContractRegistered', 'ContractRevisionRegistered', 'ContractRevisionApproved', 'ServiceAgreementAdded',
    'MerchantAccountIssued', 'DefaultMerchantAccountDesignated', 'SiteAssignmentStarted',
    'PaymentMethodActivationRegistered', 'PaymentMethodActivationApproved', 'PaymentMethodActivationSuspended', 'PaymentMethodActivationResumed', 'PaymentMethodActivationTerminated',
    'SiteActivationConfirmed', 'SiteActivationConfirmationRevoked', 'CurrencyEnabled', 'CurrencyEnablementEnded',
    'CardRouteActivationRegistered', 'CardRouteActivationApproved', 'CardRouteActivationSuspended', 'CardRouteActivationResumed', 'CardRouteActivationTerminated',
    'TransactionLimitPolicyRegistered', 'TransactionLimitPolicyEnded', 'PolicyAssigned', 'PolicyAssignmentEnded', 'PolicyVersionRegistered',
  ]

  it('every MASTER change type has a Korean label (no Latin letters except fixed terms Site·MID·Revision·Route)', () => {
    for (const code of MASTER_CHANGE_TYPES) {
      const label = MERCHANT_CHANGE_TYPE_LABELS[code]
      expect(label, code).toBeDefined()
      expect(label!.replace(/Site|MID|Revision|Route/g, ''), code).not.toMatch(/[A-Za-z]/)
      expect(changeTypeLabel(code)).toBe(label)
    }
  })

  // 리뷰 MINOR-1: 이름표에 없는 종류(서버에 새로 생긴 값)는 숨기지 않고 원문 그대로 — 함수와 화면 '변경' 열 셀 모두.
  it('an unknown change type falls back to its code (function and the history "변경" cell)', async () => {
    expect(changeTypeLabel('SomethingNewHappened')).toBe('SomethingNewHappened')
    expect(changeTypeLabel('toString')).toBe('toString')
    await openDetail(detail(), [historyEntry(7, 'DoSomethingNew', 'SomethingNewHappened')])
    const row = await within(screen.getByRole('region', { name: '변경 이력' })).findByRole('row', { name: '7' })
    expect(within(row).getAllByRole('cell')[3]).toHaveTextContent(/^SomethingNewHappened$/)
    expect(row).not.toHaveTextContent('DoSomethingNew')
  })
})
