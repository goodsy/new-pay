import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { ContractRevisionInput, MerchantDetail, MerchantListItem, RegistrationOutcome } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { createScreenMemory } from '../lib/screenMemory'

/** MERCHANT-OPS-002 §8.2 B2-1-6 — 조회 상세 "새 Revision 등록"(OPERATOR, 1인, 미승인). Demo 식별자(업무 의미 없는 고정값). */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const CTR1 = id('ctr', 1)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const SUB = '0123456789abcdef0123456789abcdef'

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}

/** Named Fixture: 계약 하나(Revision 1 미승인), Site 둘(하나는 종료 — 범위 후보에서 빠진다), MID 없음. */
function detailWithRevisions(revisions: MerchantDetail['contracts'][number]['revisions']): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null },
      { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'old.example.test', lifecycle: 'TERMINATED', review: null },
    ],
    contracts: [{ contractId: CTR1, createdAt: '2026-11-01T00:00:00.000Z', revisions, agreements: [] }],
    accounts: [],
    limits: [],
    propagation: null,
  }
}
// 기대값 변경(B2-2 MERCHANT-OPS-002 D-MOPS-008 ③): MASTER 상세 Revision에 등록자 registeredBy가 더해졌다(Fixture 모양만 — 단언 불변).
const REV1 = { revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: null, scopeSiteIds: [], registeredBy: 'admin:adm_01JCRG0000000000000000RG01' }
const REV2 = { revisionNo: 2, scopeKind: 'SITES', effectiveFrom: '2026-11-11T15:00:00.000Z', effectiveTo: null, approvedAt: null, scopeSiteIds: [SITE1], registeredBy: 'admin:adm_01JCRG0000000000000000RG01' }

async function openDetail(canRegisterRevision: boolean, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detailWithRevisions([REV1])),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRegisterRevision={canRegisterRevision} newSubmissionId={() => SUB} todayKst={() => '2026-11-10'} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  await screen.findByRole('region', { name: '계약·CSA' })
  return api
}

// 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 폼 제목·계약 표 이름·결과는 계약 키 대신 "계약 n"·"Revision n", Site 후보는 채널 식별값 — 잠금·재전송·호출 단언은 그대로.
const revisionForm = () => screen.getByRole('region', { name: '새 Revision 등록 — 계약 1' })

describe('MerchantsScreen — B2-1 new contract revision (MERCHANT-OPS-002 §9.6)', () => {
  it('the button is shown only when allowed (OPERATOR — display only, the server decides)', async () => {
    await openDetail(false)
    expect(screen.queryByRole('button', { name: '새 Revision 등록' })).toBeNull()
  })

  it('MERCHANT scope: sends contract, fixed merchant, empty sites and KST dates; shows the unapproved revision and re-reads the detail', async () => {
    const registered: RegistrationOutcome = { outcome: 'SUCCEEDED', reference: `${CTR1}#2`, replayed: false }
    const detailCalls = vi.fn()
      .mockResolvedValueOnce(detailWithRevisions([REV1]))
      .mockResolvedValueOnce(detailWithRevisions([REV1, { ...REV2, scopeKind: 'MERCHANT', scopeSiteIds: [] }]))
    const api = await openDetail(true, { merchantDetail: detailCalls, registerContractRevision: vi.fn(async () => registered) })
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    const form = revisionForm()
    expect(within(form).getByLabelText('시작일')).toHaveAttribute('min', '2026-11-10')
    expect(within(form).getByText('이 날 0시 종료(미포함)')).toBeInTheDocument()
    await userEvent.type(within(form).getByLabelText('시작일'), '2026-11-12')
    await userEvent.type(within(form).getByLabelText('종료일'), '2027-01-01')
    await userEvent.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    expect(api.registerContractRevision).toHaveBeenCalledWith({
      submissionId: SUB, contractId: CTR1, scopeMerchantId: MER1, scopeSiteIds: [], effectiveFromDate: '2026-11-12', effectiveToDate: '2027-01-01',
    })
    // C-32 표기 통일: 상세 표와 같은 "승인 전".
    expect(await within(revisionForm()).findByText(/승인 전/)).toBeInTheDocument()
    expect(within(revisionForm()).queryByText(/미승인/)).toBeNull()
    expect(within(revisionForm()).getByText('Revision 2')).toBeInTheDocument()
    expect(detailCalls).toHaveBeenCalledTimes(2)
    expect(await screen.findByRole('table', { name: '계약 1 revision' })).toHaveTextContent('2')
  })

  it('SITES scope offers only ACTIVE sites of this merchant and requires one', async () => {
    const api = await openDetail(true, { registerContractRevision: vi.fn(async () => ({ outcome: 'SUCCEEDED', reference: `${CTR1}#2`, replayed: false }) as RegistrationOutcome) })
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    const form = revisionForm()
    await userEvent.click(within(form).getByLabelText('선택 Site'))
    expect(within(form).getByText('웹 · shop.example.test')).toBeInTheDocument()
    expect(within(form).queryByText('웹 · old.example.test')).toBeNull()
    await userEvent.type(within(form).getByLabelText('시작일'), '2026-11-12')
    await userEvent.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    expect(within(form).getByText('Site를 하나 이상 고르세요.')).toBeInTheDocument()
    expect(api.registerContractRevision).not.toHaveBeenCalled()
    await userEvent.click(within(form).getByRole('checkbox'))
    await userEvent.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    expect(api.registerContractRevision).toHaveBeenCalledWith(expect.objectContaining({ scopeSiteIds: [SITE1] }))
  })

  it('a past start is not blocked on screen (MASTER decides — D-MOPS-007 ②) and the retroactive rejection shows the fixed message', async () => {
    const api = await openDetail(true, {
      registerContractRevision: vi.fn(async () => ({ outcome: 'REJECTED', rejection: 'CONTRACT_START_RETROACTIVE', replayed: false }) as RegistrationOutcome),
    })
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    await userEvent.type(within(revisionForm()).getByLabelText('시작일'), '2026-11-09')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: 'Revision 등록' }))
    expect(api.registerContractRevision).toHaveBeenCalledWith(expect.objectContaining({ effectiveFromDate: '2026-11-09' }))
    expect(await within(revisionForm()).findByText(/시작일이 오늘보다 앞입니다/)).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  it('an unknown outcome locks the form and "같은 내용 다시 보내기" resends the same submissionId and payload (§5.3)', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: `${CTR1}#2`, replayed: true } as RegistrationOutcome)
    let n = 0
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
      merchantDetail: vi.fn(async () => detailWithRevisions([REV1])),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
      registerContractRevision: send,
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRegisterRevision newSubmissionId={() => `${SUB.slice(0, 31)}${n++}`} todayKst={() => '2026-11-10'} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    await userEvent.click(await screen.findByRole('button', { name: '새 Revision 등록' }))
    await userEvent.type(within(revisionForm()).getByLabelText('시작일'), '2026-11-12')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: 'Revision 등록' }))
    expect(await within(revisionForm()).findByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(revisionForm()).getByLabelText('시작일')).toBeDisabled()
    await userEvent.click(within(revisionForm()).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0])
    expect(send.mock.calls[0]?.[0]).toEqual(expect.objectContaining({ submissionId: `${SUB.slice(0, 31)}0`, effectiveFromDate: '2026-11-12' }))
    expect(await within(revisionForm()).findByText(/이전에 처리된 결과입니다/)).toBeInTheDocument()
  })
})

/**
 * 리뷰 C-M1: 결과 불명 잠금(같은 submissionId·보낸 입력)은 폼을 닫거나 다른 가맹점을 열어도 사라지지 않는다 — 새 submissionId로 다시 보내 Revision이 중복 생성되지 않게(§5.3).
 * 잠금은 계약별 메모리(화면 또는 App 수준 — B1과 같이 세션 경계에서만 새로 만든다)에 있다.
 */
describe('MerchantsScreen — B2-1 result-unknown lock survives closing the form and switching merchants (review C-M1)', () => {
  const MER2 = id('mer', 2)
  const CTR2 = id('ctr', 2)
  const item2: MerchantListItem = { ...item, merchantId: MER2, merchantCode: codeOf(MER2), merchantName: '다른 가맹점' }
  const detail2: MerchantDetail = { ...detailWithRevisions([REV1]), merchant: { ...detailWithRevisions([REV1]).merchant, merchantId: MER2, merchantCode: codeOf(MER2), name: '다른 가맹점' } }
  detail2.contracts = [{ ...detail2.contracts[0]!, contractId: CTR2 }]

  function setup(memory?: ReturnType<typeof createScreenMemory>) {
    let n = 0
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValue({ outcome: 'SUCCEEDED', reference: `${CTR1}#2`, replayed: true } as RegistrationOutcome)
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item, item2], nextCursor: null })),
      merchantDetail: vi.fn(async (merchantId: string) => (merchantId === MER2 ? detail2 : detailWithRevisions([REV1]))),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
      registerContractRevision: send,
    })
    const view = render(
      <MerchantsScreen
        api={api}
        canSearchRegistrationNumber={false}
        canRegisterRevision
        revisionMemory={memory}
        newSubmissionId={() => `${SUB.slice(0, 31)}${n++}`}
        todayKst={() => '2026-11-10'}
      />,
    )
    return { api, send, view }
  }
  const openMerchant = async (merchantId: string) =>
    userEvent.click(within(await screen.findByRole('row', { name: codeOf(merchantId) })).getByRole('button', { name: '상세' }))
  const openRevisionForm = async () => userEvent.click(await screen.findByRole('button', { name: /새 Revision 등록/ }))
  const lockFirstMerchant = async () => {
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openMerchant(MER1)
    await openRevisionForm()
    await userEvent.type(within(revisionForm()).getByLabelText('시작일'), '2026-11-12')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: 'Revision 등록' }))
    expect(await within(revisionForm()).findByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
  }
  const expectStillLockedAndResendSame = async (send: ReturnType<typeof vi.fn>) => {
    expect(within(revisionForm()).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(revisionForm()).getByLabelText('시작일')).toBeDisabled()
    expect(within(revisionForm()).getByLabelText('시작일')).toHaveValue('2026-11-12')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0])
    expect(send.mock.calls[1]?.[0]).toEqual(expect.objectContaining({ submissionId: `${SUB.slice(0, 31)}0` }))
  }

  it('closing the form and reopening it keeps the lock and resends the same submissionId and payload', async () => {
    const { send } = setup()
    await lockFirstMerchant()
    await userEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('region', { name: '새 Revision 등록 — 계약 1' })).toBeNull()
    // 닫힌 상태에서도 잠금이 남아 있음을 버튼이 알린다.
    expect(screen.getByRole('button', { name: /새 Revision 등록/ })).toHaveTextContent('결과 확인 필요')
    await openRevisionForm()
    await expectStillLockedAndResendSame(send)
  })

  it('opening another merchant and coming back keeps the lock and resends the same submissionId and payload', async () => {
    const { send } = setup()
    await lockFirstMerchant()
    // MERCHANT-DETAIL-VIEW-001: 상세는 목록과 다른 화면이다 — 다른 가맹점은 '목록으로'로 목록에 돌아가 연다(목록 아래 상세 시절의 "목록에서 바로 다른 행 열기"와 같은 경로·같은 단언).
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    await openMerchant(MER2)
    // MERCHANT-IA-001 B3: MER2의 유일한 계약도 "계약 1" — 목록에서 연 뒤 그 상세가 그려질 때까지 기다리는 용도(단언 의미 같음).
    await screen.findByRole('table', { name: '계약 1 revision' })
    // B3 리뷰 MINOR-3(단언 추가만): 계약 표 이름이 두 가맹점에서 같아졌으므로, 지금 보이는 상세가 MER2인지 머리의 가맹점 코드로 확인한다.
    expect(screen.getByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(MER2)}`) })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    await openMerchant(MER1)
    await screen.findByRole('table', { name: '계약 1 revision' })
    expect(screen.getByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(MER1)}`) })).toBeInTheDocument()
    await openRevisionForm()
    await expectStillLockedAndResendSame(send)
  })

  it('the lock lives in the injected (App-level) memory: a remounted screen with the same memory is still locked; a new memory (session boundary) is not', async () => {
    const memory = createScreenMemory()
    const first = setup(memory)
    await lockFirstMerchant()
    first.view.unmount()

    const again = setup(memory)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openMerchant(MER1)
    await openRevisionForm()
    expect(within(revisionForm()).getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    again.view.unmount()

    setup(createScreenMemory())
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openMerchant(MER1)
    await openRevisionForm()
    expect(within(revisionForm()).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(within(revisionForm()).getByLabelText('시작일')).not.toBeDisabled()
  })

  it('"취소하고 새로 입력" asks first with the §5.3 warning; only the confirmation clears the lock', async () => {
    setup()
    await lockFirstMerchant()
    await userEvent.click(within(revisionForm()).getByRole('button', { name: '취소하고 새로 입력' }))
    expect(within(revisionForm()).getByText(/앞 요청이 이미 처리됐을 수 있습니다/)).toBeInTheDocument()
    expect(within(revisionForm()).getByLabelText('시작일')).toBeDisabled()
    await userEvent.click(within(revisionForm()).getByRole('button', { name: '그래도 새로 입력' }))
    expect(within(revisionForm()).queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
  })
})

/** C-32(Demo 시연 지적): 성공 뒤 같은 폼을 다시 눌러 새 submissionId로 Revision n+1이 중복 생성되지 않게 — 성공하면 완료 상태(입력 읽기 전용·제출 버튼 없음), "다른 Revision 새로 입력"만 새 폼을 준다. */
describe('MerchantsScreen — B2-1 completed revision form (C-32)', () => {
  it('right after success there is no submit button, the inputs are read-only and nothing is resent; "다른 Revision 새로 입력" gives an empty form and a new submissionId', async () => {
    let n = 0
    const send = vi.fn(async (_input: ContractRevisionInput) => ({ outcome: 'SUCCEEDED', reference: `${CTR1}#2`, replayed: false }) as RegistrationOutcome)
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
      merchantDetail: vi.fn(async () => detailWithRevisions([REV1])),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
      registerContractRevision: send,
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRegisterRevision newSubmissionId={() => `${SUB.slice(0, 31)}${n++}`} todayKst={() => '2026-11-10'} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    await userEvent.click(await screen.findByRole('button', { name: '새 Revision 등록' }))
    await userEvent.type(within(revisionForm()).getByLabelText('시작일'), '2026-11-12')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: 'Revision 등록' }))
    expect(await within(revisionForm()).findByText('Revision 2')).toBeInTheDocument()

    expect(within(revisionForm()).queryByRole('button', { name: 'Revision 등록' })).toBeNull()
    expect(within(revisionForm()).getByLabelText('시작일')).toBeDisabled()
    expect(within(revisionForm()).getByLabelText('시작일')).toHaveValue('2026-11-12')
    expect(send).toHaveBeenCalledTimes(1)

    await userEvent.click(within(revisionForm()).getByRole('button', { name: '다른 Revision 새로 입력' }))
    expect(within(revisionForm()).getByLabelText('시작일')).not.toBeDisabled()
    expect(within(revisionForm()).getByLabelText('시작일')).toHaveValue('')
    expect(within(revisionForm()).queryByText('Revision 2')).toBeNull()
    await userEvent.type(within(revisionForm()).getByLabelText('시작일'), '2026-11-13')
    await userEvent.click(within(revisionForm()).getByRole('button', { name: 'Revision 등록' }))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]?.[0]).toEqual(expect.objectContaining({ submissionId: `${SUB.slice(0, 31)}1`, effectiveFromDate: '2026-11-13' }))
  })
})
