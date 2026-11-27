import { vi } from 'vitest'
import { ApiError } from '../api/client'
import type { AdminApi } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'

/** 시험 대역 API(모든 메서드 vi.fn — 시험마다 필요한 것만 구현). */
export function fakeApi(overrides: Partial<AdminApi> = {}): AdminApi {
  const notStubbed = (name: string) => vi.fn(async () => { throw new Error(`not stubbed: ${name}`) })
  const base: AdminApi = {
    csrf: vi.fn(async () => undefined),
    // ADMIN-HOME-KPI-001 §4.2 `GET /api/home-kpis` — 시험 기본은 404. 기대값 변경(Coordinator Q1 — KPI 서버가 있으므로 404는 고장): 화면은 "집계 준비 중"이 아니라
    // 오류("불러오지 못했어요")를 보인다. KPI 숫자를 보는 시험은 문서(HomeKpis)를 직접 넣는다.
    getHomeKpis: vi.fn(async () => { throw new ApiError(404, 'NOT_FOUND') }),
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
    // BUSINESS-PROFILE-001 BIZ-4: 사업자 관리 API(옛 registerBusiness는 C-27로 제거).
    searchBusinesses: notStubbed('searchBusinesses'),
    lookupBusinessByRegistrationNumber: notStubbed('lookupBusinessByRegistrationNumber'),
    businessDetail: notStubbed('businessDetail'),
    businessHistory: notStubbed('businessHistory'),
    createBusiness: notStubbed('createBusiness'),
    updateBusiness: notStubbed('updateBusiness'),
    completeBusinessRegistration: notStubbed('completeBusinessRegistration'),
    // MERCHANT-FLOW-001 FLOW-2b: 계약 접수 건 명령.
    openContractIntake: notStubbed('openContractIntake'),
    moveContractIntake: notStubbed('moveContractIntake'),
    completeContractIntake: notStubbed('completeContractIntake'),
    // MERCHANT-FLOW-001 FLOW-2c: 사업자 계약 정지·정지 해제·해지.
    suspendBusinessContract: notStubbed('suspendBusinessContract'),
    resumeBusinessContract: notStubbed('resumeBusinessContract'),
    terminateBusinessContract: notStubbed('terminateBusinessContract'),
    registerMerchant: notStubbed('registerMerchant'),
    registerMerchantSite: notStubbed('registerMerchantSite'),
    registerContract: notStubbed('registerContract'),
    addServiceAgreement: notStubbed('addServiceAgreement'),
    registerContractRevision: notStubbed('registerContractRevision'),
    transactionSearch: notStubbed('transactionSearch'),
    transactionDetail: notStubbed('transactionDetail'),
    acquisitionSearch: notStubbed('acquisitionSearch'),
    acquisitionDetail: notStubbed('acquisitionDetail'),
    acquisitionRecords: notStubbed('acquisitionRecords'),
    reacquisitionTargets: notStubbed('reacquisitionTargets'),
    requestReacquisition: notStubbed('requestReacquisition'),
    requestMerchantContractApproval: notStubbed('requestMerchantContractApproval'),
    merchantApprovalEvidence: notStubbed('merchantApprovalEvidence'),
    requestMerchantFirstMid: notStubbed('requestMerchantFirstMid'),
    registerPaymentMethodActivation: notStubbed('registerPaymentMethodActivation'),
    rejectSiteReview: notStubbed('rejectSiteReview'),
    requestMerchantPaymentMethodActivation: notStubbed('requestMerchantPaymentMethodActivation'),
    requestMerchantSiteReviewApproval: notStubbed('requestMerchantSiteReviewApproval'),
    requestMerchantCurrencyEnablement: notStubbed('requestMerchantCurrencyEnablement'),
    requestMerchantCodeChange: notStubbed('requestMerchantCodeChange'),
    // MERCHANT-CODE-001 MC-3c: 대역이 없으면 실패(= 라벨 호출 실패) — 화면은 "—" + 안내 1줄(fail-closed)로 그린다.
    merchantLabels: notStubbed('merchantLabels'),
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

/** 원문 사업자등록번호를 받을 수 있는 호출(BUSINESS-PROFILE-001 §8.3·§9, D-BIZLOOKUP-001 — 번호 조회·사업자 생성 요청 본문에만). */
export const BRN_ALLOWED_CALLS: readonly string[] = ['lookupBusinessByRegistrationNumber', 'createBusiness']

/**
 * 기록된 모든 대역 호출 인수에서 [needle](원문 번호 등)이 나온 메서드 이름(리뷰 MINOR-3 — fakeApi 호출 인수 전수 검사).
 * 화면 시험은 이 목록에서 허용 호출을 뺀 나머지가 빈 목록인지 단언한다.
 */
export function callsContaining(api: AdminApi, needle: string): string[] {
  return Object.entries(api)
    .filter(([, fn]) => vi.isMockFunction(fn) && fn.mock.calls.some((args: unknown[]) => JSON.stringify(args).includes(needle)))
    .map(([name]) => name)
    .sort()
}

/** [needle]이 허용 호출(번호 조회·사업자 생성) 밖으로 나간 메서드 이름. */
export const leakedCalls = (api: AdminApi, needle: string) => callsContaining(api, needle).filter((name) => !BRN_ALLOWED_CALLS.includes(name))

