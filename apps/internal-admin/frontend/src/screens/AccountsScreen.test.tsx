import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AccountsScreen } from './AccountsScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

const accounts = [
  { actorId: 'adm_A', loginName: 'ops-a', displayName: '운영 A', status: 'ACTIVE', activeRoles: ['OPERATOR'], suspendedRoles: [], pendingPurpose: null },
]

describe('AccountsScreen (SECURITY_ADMIN — list, create, 1-person commands, approval requests)', () => {
  it('creates an account and shows its ENROLL code once', async () => {
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts })),
      createAccount: vi.fn(async () => ({ actorId: 'adm_N', enrollmentCode: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567' })),
    })
    render(<AccountsScreen api={api} canManage />)
    expect(await screen.findByRole('row', { name: /ops-a/ })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('새 로그인 이름'), 'new-one')
    await userEvent.type(screen.getByLabelText('표시명'), '신규')
    await userEvent.click(screen.getByRole('button', { name: '계정 만들기' }))
    expect(api.createAccount).toHaveBeenCalledWith('new-one', '신규')
    expect(await screen.findByRole('dialog')).toHaveTextContent('ABCD EFGH')
    await userEvent.click(screen.getByRole('button', { name: '확인했습니다 — 닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  it('suspends, revokes a role and requests a 2-person ROLE_GRANT; a CREDENTIAL_ISSUER refusal is explained', async () => {
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts })),
      suspend: vi.fn(async () => ({ status: 'SUSPENDED' })),
      revokeRole: vi.fn(async () => ({ status: 'REVOKED' })),
      requestApproval: vi.fn(async () => { throw apiError(409, 'CREDENTIAL_ISSUER') }),
    })
    render(<AccountsScreen api={api} canManage />)
    const row = await screen.findByRole('row', { name: /ops-a/ })
    await userEvent.click(within(row).getByRole('button', { name: '정지' }))
    expect(api.suspend).toHaveBeenCalledWith('adm_A')
    await userEvent.click(within(row).getByRole('button', { name: 'OPERATOR 회수' }))
    expect(api.revokeRole).toHaveBeenCalledWith('adm_A', 'OPERATOR')
    await userEvent.selectOptions(within(row).getByLabelText('승인 요청 종류'), 'ROLE_GRANT:APPROVER')
    await userEvent.click(within(row).getByRole('button', { name: '승인 요청' }))
    expect(api.requestApproval).toHaveBeenCalledWith({ kind: 'ROLE_GRANT', targetActorId: 'adm_A', grantedRole: 'APPROVER' })
    expect(await screen.findByRole('alert')).toHaveTextContent('이 계정의 자격을 발급한 관리자')
  })

  it('Q-S7-2: ROLE_REACTIVATE takes its cause from the server candidates (no free text) and sends the chosen one', async () => {
    const suspended = { ...accounts[0]!, actorId: 'adm_S', loginName: 'ops-s', displayName: '정지 S', suspendedRoles: ['APPROVER', 'OPERATOR'] }
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts: [accounts[0]!, suspended] })),
      reactivationCauses: vi.fn(async () => ({ causes: [{ cause: 'apr_01RESET', roles: ['APPROVER'] }, { cause: 'episode:3', roles: ['OPERATOR'] }] })),
      requestApproval: vi.fn(async () => ({ approvalId: 'apr_NEW' })),
    })
    render(<AccountsScreen api={api} canManage />)
    const plain = await screen.findByRole('row', { name: /ops-a/ })
    expect(within(plain).queryByRole('button', { name: '재활성 원인 조회' })).toBeNull()
    const row = screen.getByRole('row', { name: /ops-s/ })
    expect(within(row).queryByLabelText('재활성 원인')).toBeNull()
    await userEvent.click(within(row).getByRole('button', { name: '재활성 원인 조회' }))
    expect(api.reactivationCauses).toHaveBeenCalledWith('adm_S')
    const select = await within(row).findByLabelText('재활성 원인')
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['apr_01RESET (APPROVER)', 'episode:3 (OPERATOR)'])
    await userEvent.selectOptions(select, 'episode:3')
    await userEvent.click(within(row).getByRole('button', { name: '재활성 승인 요청' }))
    expect(api.requestApproval).toHaveBeenCalledWith({ kind: 'ROLE_REACTIVATE', targetActorId: 'adm_S', causeApprovalId: 'episode:3' })
  })

  it('S8 review nit-5: a list truncated at the server limit says so', async () => {
    const api = fakeApi({ accounts: vi.fn(async () => ({ accounts, truncated: true })) })
    render(<AccountsScreen api={api} canManage />)
    expect(await screen.findByRole('status')).toHaveTextContent('앞의 500개만')
  })

  it('Q-S7-2: no candidate cause → explained and nothing can be requested', async () => {
    const suspended = { ...accounts[0]!, actorId: 'adm_S', loginName: 'ops-s', suspendedRoles: ['APPROVER'] }
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts: [suspended] })),
      reactivationCauses: vi.fn(async () => ({ causes: [] })),
    })
    render(<AccountsScreen api={api} canManage />)
    const row = await screen.findByRole('row', { name: /ops-s/ })
    await userEvent.click(within(row).getByRole('button', { name: '재활성 원인 조회' }))
    expect(await within(row).findByText('재활성할 원인이 없습니다')).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: '재활성 승인 요청' })).toBeNull()
  })

  it('without the manage permission shows the list read-only and without login names (D-ADMIN1-017 — the server omits them)', async () => {
    const api = fakeApi({ accounts: vi.fn(async () => ({ accounts: [{ ...accounts[0]!, loginName: null }] })) })
    render(<AccountsScreen api={api} canManage={false} />)
    const row = await screen.findByRole('row', { name: /운영 A/ })
    expect(screen.queryByRole('columnheader', { name: '로그인 이름' })).toBeNull()
    expect(within(row).queryByRole('button')).toBeNull()
    expect(screen.queryByLabelText('새 로그인 이름')).toBeNull()
  })
})
