import { vi } from 'vitest'
import { ApiError } from '../api/client'
import type { AdminApi } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'

/** 시험 대역 API(모든 메서드 vi.fn — 시험마다 필요한 것만 구현). */
export function fakeApi(overrides: Partial<AdminApi> = {}): AdminApi {
  const notStubbed = (name: string) => vi.fn(async () => { throw new Error(`not stubbed: ${name}`) })
  const base: AdminApi = {
    csrf: vi.fn(async () => undefined),
    me: notStubbed('me'),
    password: notStubbed('password'),
    totp: notStubbed('totp'),
    logout: vi.fn(async () => undefined),
    enrollStart: notStubbed('enrollStart'),
    enrollPassword: notStubbed('enrollPassword'),
    enrollTotp: notStubbed('enrollTotp'),
    enrollComplete: notStubbed('enrollComplete'),
    accounts: notStubbed('accounts'),
    createAccount: notStubbed('createAccount'),
    suspend: notStubbed('suspend'),
    reissue: notStubbed('reissue'),
    revokeRole: notStubbed('revokeRole'),
    reactivationCauses: notStubbed('reactivationCauses'),
    approvals: notStubbed('approvals'),
    requestApproval: notStubbed('requestApproval'),
    approve: notStubbed('approve'),
    reject: notStubbed('reject'),
    withdraw: notStubbed('withdraw'),
    audit: notStubbed('audit'),
    paymentTarget: notStubbed('paymentTarget'),
    requestOperatorCancel: notStubbed('requestOperatorCancel'),
    approvalDetail: notStubbed('approvalDetail'),
    justification: notStubbed('justification'),
    reacquisitionEvidence: notStubbed('reacquisitionEvidence'),
    reconciliation: notStubbed('reconciliation'),
    merchantSearch: notStubbed('merchantSearch'),
    merchantDetail: notStubbed('merchantDetail'),
    merchantHistory: notStubbed('merchantHistory'),
    transactionSearch: notStubbed('transactionSearch'),
    transactionDetail: notStubbed('transactionDetail'),
    acquisitionSearch: notStubbed('acquisitionSearch'),
    acquisitionDetail: notStubbed('acquisitionDetail'),
    acquisitionRecords: notStubbed('acquisitionRecords'),
    reacquisitionTargets: notStubbed('reacquisitionTargets'),
    requestReacquisition: notStubbed('requestReacquisition'),
  } as AdminApi
  return { ...base, ...overrides }
}

export function apiError(status: number, code: ConsoleErrorCode): ApiError {
  return new ApiError(status, code)
}

/** 화면이 브라우저 저장소에 아무것도 남기지 않았는지(코드·비밀 비보관). */
export function storageIsEmpty(): boolean {
  return localStorage.length === 0 && sessionStorage.length === 0
}
