import { ApiError, apiFetch, type ApiDeps } from './client'

export interface Me {
  actorId: string
  displayName: string
  roles: string[]
  /**
   * ADMIN-DEMO-LOGIN-001 결정 ③: 데모 간편 로그인 세션이고 서버에서 켜져 있으면 true(그 밖에는 필드 없음). 화면은 승인·반려의 TOTP 입력 대신 "데모: 재인증 생략" 안내를 보인다
   * — 생략 판정은 서버.
   */
  demoQuickLogin?: boolean
}

/**
 * 데모 간편 로그인 버튼 하나(ADMIN-DEMO-LOGIN-001 §3 — Demo 전용 임시 기능). 비밀 없음. [available]이 false면(잠김·정지·등록 미완료) 버튼을 막는다.
 */
export interface DemoQuickLoginAccount {
  loginName: string
  displayName: string
  roles: string[]
  available: boolean
}

export interface EnrollStart {
  purpose: string
  needsPassword: boolean
  needsTotp: boolean
}

export interface AccountView {
  actorId: string
  /** SECURITY_ADMIN 조회에만 있다(D-ADMIN1-017 — 서버가 그 밖 역할에는 보내지 않는다). */
  loginName: string | null
  displayName: string
  status: string
  activeRoles: string[]
  suspendedRoles: string[]
  pendingPurpose: string | null
}

/** 소유 서비스 승인 상세(I-98 — 사유·근거 문구 없음). [attemptCount]는 "명령 시도 기회 수"(I-105). */
export interface OwnerDetail {
  amountMinor: number
  currency: string
  purposeKind: string
  pinnedTarget: unknown
  approvedAt: string | null
  executeBefore: string | null
  attemptCount: number | null
  nextAttemptAt: string | null
  lastResponseAt: string | null
  lastFailure: string | null
  integrityFlag: string | null
  resendHaltedReason: string | null
  ownerResultCode: string | null
  ownerReference: string | null
}

export interface ApprovalView {
  approvalId: string
  kind: string
  /** ADMIN_ACCOUNT·PAYMENT(없으면 계정 대상). */
  targetKind?: string
  targetActorId: string | null
  targetRef?: string | null
  /** MERCHANT-CODE-001 §5.6.4(MC-3a): 가맹점 대상의 내부 키(`mer_` — 언제나), 그 밖 null. 라벨 대응에만 쓰고 화면에 그리지 않는다(D-ADMIN-UI-004 ③). */
  targetMerchantId?: string | null
  targetSnapshot: string
  grantedRole: string | null
  causeApprovalId: string | null
  requestedBy: string
  requestedByDisplayName: string
  requestedAt: string
  expiresAt: string
  status: string
  decidedBy: string | null
  decidedByDisplayName: string | null
  decidedAt: string | null
  resultCode: string | null
  owner?: OwnerDetail
  /** 재매입 승인 상세(ACQ-ADMIN-001 B1-A — 상세 조회에만, 사유 문구·근거 없음). */
  acquiring?: AcquiringDetail
  /** 가맹점 계약 승인 상세(MERCHANT-OPS-002 B2-2 — 상세 조회에만, 근거 참조 없음 — APPROVER·요청자만 [AdminApi.merchantApprovalEvidence]). */
  merchant?: MerchantApprovalDetail
}

/** 계약 승인 고정 대상(pinned_target — 요청 시점 MASTER 상세, D-MOPS-008 ③ registeredBy 포함). 사업자등록번호·채널 식별값 없음. */
export interface PinnedContractApproval {
  merchantId: string
  merchantName: string
  /** MERCHANT-CODE-001 §5.3·§5.6.4(MC-3a): 요청 시점 가맹점 코드(표시용) — 새 행에만 있고 기존 행에는 없다(선택 키). */
  merchantCode?: string
  contractId: string
  revisionNo: number
  scopeKind: string
  scopeSiteIds: string[]
  effectiveFrom: string
  effectiveTo: string | null
  approved: boolean
  registeredBy: string
}

/** 가맹점 승인 상세(MERCHANT-OPS-002 B2-2). [ownerReference] = 승인된 Revision `ctr_…#n`, [ownerResultCode] = MASTER 거절 code. */
export interface MerchantApprovalDetail {
  commandKind: string
  merchantId: string
  pinnedTarget: unknown
  approvedAt: string | null
  executeBefore: string | null
  attemptCount: number | null
  nextAttemptAt: string | null
  lastResponseAt: string | null
  lastFailure: string | null
  integrityFlag: string | null
  resendHaltedReason: string | null
  ownerResultCode: string | null
  ownerReference: string | null
}

/** 가맹점 계약 승인 요청 입력(OPERATOR — 근거 참조 필수, 자유 문구 불가). */
export interface MerchantContractApprovalInput {
  merchantId: string
  contractId: string
  revisionNo: number
  evidenceRef: string
}

/** 상세의 미해결 승인 한 건(C-24 — 승인 목록 권한 역할에게만, 그 밖 빈 배열). */
export interface PendingMerchantApproval {
  approvalId: string
  kind: string
  status: string
  /** 계약 승인의 대상 Revision(첫 MID 발급은 null — Demo 개선 C-2). */
  revisionNo?: number | null
}

/** 첫 Default MID 발급 요청 입력(B2-3 — OPERATOR, 근거 참조 필수). */
export interface MerchantFirstMidInput {
  merchantId: string
  contractId: string
  agreementId: string
  revisionNo: number
  primarySiteId: string
  evidenceRef: string
}

/** 재매입 요청 고정 레코드 하나(pinned_target — 요청 시점, 금액은 표시용). */
export interface PinnedReacquisitionItem {
  seq: number
  transactionId: string
  recordType: string
  generation: number
  status: string
  approvedAmountMinor: number
  cancelledAmountMinor: number
  expectedNetMinor: number
}

/** 재매입 승인 상세(ACQ-ADMIN-001 B1-A §5.2). [ownerReference] = 새 재매입 파일 ID, [ownerResultCode] = NOT_REQUIRED 또는 FINANCIAL 거절 code. */
export interface AcquiringDetail {
  originalFileId: string
  reasonCode: string
  /** 근거 참조·사유 문구는 상세 응답에 없다(D-ACQA-005 — 근거 참조는 APPROVER·요청자 본인만 [AdminApi.reacquisitionEvidence] 별도 조회). */
  pinnedTarget: unknown
  approvedAt: string | null
  executeBefore: string | null
  attemptCount: number | null
  nextAttemptAt: string | null
  lastResponseAt: string | null
  lastFailure: string | null
  integrityFlag: string | null
  resendHaltedReason: string | null
  ownerResultCode: string | null
  ownerReference: string | null
}

/** 재매입 대상 미리 보기 항목(FINANCIAL 계산 — 비권위, I-8: 버튼·승인 판단은 이 값만). eligible이 아니면 refusal에 사유 code. */
export interface ReacquisitionTargetItem {
  seq: number
  transactionId: string | null
  recordType: string | null
  generation: number | null
  status: string | null
  eligible: boolean
  refusal: string | null
  approvedAmountMinor: number | null
  cancelledAmountMinor: number | null
  expectedNetMinor: number | null
}

export interface ReacquisitionTargets {
  fileId: string
  readAt: string
  items: ReacquisitionTargetItem[]
}

/** 재매입 요청 입력(OPERATOR — D-ACQA-002·003: 같은 원 파일의 레코드 1~50건). */
export interface ReacquisitionRequestInput {
  fileId: string
  seqs: number[]
  reasonCode: string
  evidenceRef: string
  reason: string
}

/** 화면 대상 조회(§4.3 — 비권위 표시값). 금액은 최소 단위 정수. */
export interface PaymentTargetView {
  paymentId: string
  paymentStatus: string
  cancellable: boolean
  currency: string | null
  authorizedMinor: number | null
  cancellableMinor: number
  balanceRevision: number
  onHold: boolean
  inFlightCancel: boolean
}

export interface OperatorCancelInput {
  paymentId: string
  amountMinor: number
  purposeKind: string
  purposeReference: string
  reason: string
}

/** 근거·사유(D-OPS-010 — APPROVER·요청자 본인만 서버가 허용, 조회마다 감사). 화면 상태에만 잠깐 둔다. */
export interface Justification {
  approvalId: string
  purposeReference: string
  reason: string
}

/** 재매입 근거 참조(ACQ-ADMIN-001 D-ACQA-005 — D-OPS-010과 같은 endpoint·판정·조회 감사, 사유 문구 없음). 화면 상태에만 잠깐 둔다. */
export interface ReacquisitionEvidence {
  approvalId: string
  evidenceRef: string
}

export interface ReconciliationFinding {
  findingId: number
  kind: string
  approvalId: string
  paymentRef: string | null
  /** 열 이름·code만(문구 없음). */
  detailCode: string | null
  foundAt: string
}

export interface ReconciliationOverview {
  openCounts: Record<string, number>
  openTotal: number
  findings: ReconciliationFinding[]
  truncated: boolean
  lastRunAt: string | null
  lastFullScanAt: string | null
  lastResult: string | null
}

/** `ROLE_REACTIVATE` 원인 후보(Q-S7-2 — 서버가 역할 이벤트로 정한다). 화면은 이 목록에서만 고른다(자유 입력 없음). */
export interface ReactivationCause {
  cause: string
  roles: string[]
}

export interface ApprovalRequestInput {
  kind: string
  targetActorId: string
  grantedRole?: string
  causeApprovalId?: string
}

export interface AuditEntry {
  eventId: number
  occurredAt: string
  actorKind: string
  actorId: string | null
  actorDisplayName: string | null
  executorActorId: string | null
  action: string
  targetActorId: string | null
  resultCode: string
  approvalId: string | null
  correlationId: string
  detail: string | null
  reason: string | null
  /** 계정이 아닌 대상(가맹점·Payment·매입 파일 등 — L-13). 이전 서버 응답에는 없을 수 있다. */
  targetRef?: string | null
  /** MERCHANT-CODE-001 §5.6.4(MC-3a): 가맹점 관련 행의 내부 키(`target_merchant_key` — 기존 `mer_` 행 포함), 그 밖 null. 라벨 대응에만 쓰고 그리지 않는다. */
  targetMerchantId?: string | null
}

export interface AuditFilterInput {
  actorId?: string
  targetActorId?: string
  action?: string
  /** MERCHANT-CODE-001 §5.6.4 감사 필터(`^mer_<ULID>$` — 서버가 형식 위반 400). 화면은 코드 입력 → 코드 검색으로 해석한 값만 보낸다. */
  targetMerchantId?: string
}

/** `POST /api/merchant-labels` 응답(MERCHANT-CODE-001 §5.6.3 MC-3b): merchantId 오름차순(대응용 — 화면 미표시), UNAVAILABLE이면 items `[]`(HTTP 200). */
export interface MerchantTargetLabelPage {
  items: { merchantId: string; name: string; code: string }[]
  labels: MerchantLabelsState
}

/**
 * 가맹점 검색 조건(MERCHANT-OPS-001 §3.1·§4.1 — 모두 선택). [registrationNumber]는 ASCII 숫자 10자리이며 POST 본문으로만 보낸다(URL·저장소·로그에 남기지 않음, R-3).
 * [after]는 다음 쪽 cursor(merchantId), [limit] 1..50(없으면 서버 기본 20).
 */
export interface MerchantSearchInput {
  merchantId?: string
  businessId?: string
  lifecycle?: 'ACTIVE' | 'TERMINATED'
  registrationNumber?: string
  after?: string
  limit?: number
  /**
   * MERCHANT-CODE-001 MC-2 코드 검색(정확 일치 — 현재·은퇴 코드, §3.1 형식). 단일 기준: [limit] 말고 다른 조건과 함께 보내지 않는다(서버도 400).
   */
  merchantCode?: string
  /**
   * MERCHANT-NAME-SEARCH-001 가맹점명 앞부분 일치(영문 대소문자 구분, 입력 원문 그대로 — trim·정규화 없음, 형식 위반은 서버 400).
   * [merchantCode]와 함께 보내지 않는다(서버도 400). 다른 조건과는 AND.
   */
  displayNamePrefix?: string
}

/** 목록 항목. [registrationNumber]는 사업자등록번호 원문 10자리(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①, 표시는 `displayRegistrationNumber` 하이픈). [merchantId]는 내부 키(화면에 그리지 않음 — 상세 호출용, MC-2), 화면 표시는 [merchantCode]. */
export interface MerchantListItem {
  merchantId: string
  /** MERCHANT-CODE-001 가맹점 코드(현재 코드). */
  merchantCode: string
  /** 코드 검색 응답에만 있다 — 은퇴 코드로 찾았으면 true(표시는 MC-1b 뒤). */
  matchedRetiredCode?: boolean
  merchantName: string
  businessId: string
  businessName: string
  registrationNumber: string
  lifecycle: string
  entitlementRevision: number
  midCount: number
  createdAt: string
}

export interface MerchantSearchPage {
  items: MerchantListItem[]
  nextCursor: string | null
}

/** Site 적용 확인 한 건(MERCHANT-OPS-002 B3-1 — 개통 승인 때 PRIMARY에 함께 기록, D-MD-016). */
export interface SiteConfirmation {
  confirmationId: string
  merchantSiteId: string
  paymentMethod: string
  activationId: string
  confirmedAt: string
  revoked: boolean
}

/** B3-1 1인 결제수단 개통 등록(OPERATOR — UNDER_REVIEW로 남는다, 성공 reference `act_…`). */
export interface PaymentMethodActivationInput {
  submissionId: string
  merchantAccountId: string
  paymentMethod: 'CARD'
}

/** B3-1 1인 Site 심사 거절(OPERATOR — 되돌릴 수 없음, 재신청은 새 Site. 사유 코드 C-37, 근거 참조 선택). 성공 reference `site_…#n`. */
export interface SiteReviewRejectionInput {
  submissionId: string
  merchantSiteId: string
  reasonCode: string
  evidenceRef?: string
}

/** B3-1 2인 결제수단 개통 승인 요청(근거 = 기관(카드사/VAN) 회신 참조 필수). */
export interface MerchantActivationApprovalInput {
  merchantId: string
  merchantAccountId: string
  activationId: string
  evidenceRef: string
}

/** B3-1 2인 Site 심사 승인 요청(근거 = PG 심사 근거 필수, 사유 REVIEW_PASSED). */
export interface MerchantSiteReviewApprovalInput {
  merchantId: string
  merchantSiteId: string
  reasonCode: 'REVIEW_PASSED'
  evidenceRef: string
}

/**
 * B3-2 2인 통화 활성화 요청(MERCHANT-OPS-002 §9.16·§9.26 — 근거 = 기관(카드사/VAN) 회신 참조 필수, KRW만 C-41). 효력 시작 `AT_EXECUTION`(MASTER 실행 시각으로 확정) 또는
 * `DATE`(`effectiveFromDate` = KST `YYYY-MM-DD`, 내일 이후). `effectiveToDate`(선택)는 그 날 0시 종료(미포함 — C-7).
 */
/**
 * MERCHANT-CODE-001 MC-1b 가맹점 코드 변경 요청(§5.5 — OPERATOR, 계약 승인 전 1회만): [merchantId]는 내부 키(호출에만 — 화면에 그리지 않는다), [newCode] §3.1 형식,
 * [reasonCode] C-12 목록(Demo 값), [evidenceRef] 필수.
 */
export interface MerchantCodeChangeInput {
  merchantId: string
  newCode: string
  reasonCode: 'TYPO_CORRECTION' | 'MERCHANT_REQUEST' | 'OTHER_POLICY'
  evidenceRef: string
}

export interface MerchantCurrencyEnablementInput {
  merchantId: string
  merchantAccountId: string
  currency: 'KRW'
  effectiveFromMode: 'AT_EXECUTION' | 'DATE'
  effectiveFromDate?: string
  effectiveToDate?: string
  evidenceRef: string
}

/** 상세(§3.2 — 한 번의 일관 읽기). 카드 Route는 인수사 코드·Route 참조·상태 등만(기관 가맹점 번호 없음 — D-MS2-005). 시각은 UTC ISO. */
export interface MerchantDetail {
  /** [merchantCode] = 현재 가맹점 코드(MERCHANT-CODE-001 — 화면 표시용, 내부 키 merchantId는 호출에만). */
  merchant: { merchantId: string; merchantCode: string; name: string; lifecycle: string; entitlementRevision: number; createdAt: string }
  business: { businessId: string; name: string; registrationNumber: string; lifecycle: string }
  sites: {
    merchantSiteId: string
    channelType: string
    channelIdentifier: string
    lifecycle: string
    review: { decision: string; decidedAt: string } | null
    /** Site 등록자(MERCHANT-OPS-002 B3-1 — `merchant_site.created_by` — 표시 + 등록자 ≠ 승인자 판정 근거, D-MOPS-009 ③). B3-1 전 서버 응답에는 없다. */
    registeredBy?: string
  }[]
  contracts: {
    contractId: string
    createdAt: string
    /** [registeredBy] = Revision 등록자(MERCHANT-OPS-002 B2-2 — 정규 actor `admin:adm_…`). */
    revisions: { revisionNo: number; scopeKind: string; effectiveFrom: string; effectiveTo: string | null; approvedAt: string | null; scopeSiteIds: string[]; registeredBy: string }[]
    agreements: { agreementId: string; serviceKind: string; addedAt: string }[]
  }[]
  accounts: {
    merchantAccountId: string
    lifecycle: string
    isDefault: boolean
    issuingContractId: string
    issuingAgreementId: string
    issuingRevisionNo: number
    issuedAt: string
    assignments: { assignmentId: string; merchantSiteId: string; role: string; status: string; effectiveFrom: string; effectiveTo: string | null }[]
    /** [registeredBy] = 개통 등록자(B3-1 — 표시용). */
    paymentMethods: { activationId: string; paymentMethod: string; status: string | null; registeredAt: string; registeredBy?: string }[]
    currencies: { enablementId: string; currencyCode: string; effectiveFrom: string; effectiveTo: string | null; endedAt: string | null }[]
    cardRoutes: { activationId: string; paymentMethod: string; acquirerCode: string; routeRef: string; status: string; registeredAt: string }[]
    /** Site 적용 확인(B3-1 C-43 — 근거·사유 없음). B3-1 전 서버 응답에는 없다. */
    siteConfirmations?: SiteConfirmation[]
  }[]
  /** 한도(설정값 — Demo 값은 운영값 아님). 금액은 통화 최소 단위 정수, 무제한이면 null. */
  limits: {
    policyId: string
    scopeKind: string
    agreementId: string | null
    merchantAccountId: string | null
    currencyCode: string
    perTxKind: string
    perTxAmount: number | null
    dailyKind: string
    dailyAmount: number | null
    effectiveFrom: string
    effectiveTo: string | null
    endedAt: string | null
  }[]
  propagation: {
    latestRevision: number
    latestRevisionIssuedAt: string
    latestAckRevision: number | null
    latestAckAt: string | null
    effectConfirmedAt: string | null
    effectBasis: 'ACK' | 'LEASE_EXPIRY' | null
  } | null
  /** ADMIN DB 미해결 승인(MERCHANT-OPS-002 C-24 — "승인 대기 중" 표시). 이전 서버 응답에는 없을 수 있다. */
  pendingApprovals?: PendingMerchantApproval[]
  /**
   * MERCHANT-CODE-001 §3.8: 코드 배정 이력(seq 1 = 첫 배정, seq 2 = 유일한 변경 — 은퇴 코드는 seq 1의 code)·변경 가능 여부(advisory — seq 2 없음 ∧ 계약 승인 사실 없음 ∧ ACTIVE).
   * MASTER는 늘 보내지만 시험 Fixture가 생략할 수 있어 선택 필드다(없으면 변경 불가로 본다).
   */
  merchantCodeHistory?: { code: string; seq: number; source: string; replacesCode: string | null; assignedAt: string; assignedBy: string; reasonCode: string | null }[]
  merchantCodeChangeable?: boolean
  /**
   * MERCHANT-FLOW-001 FLOW-3(BR-BIZ-337): 등록 관문 근거(사업자 계약 순번·적용 중이던 버전 번호 — 등록 때 기록, 뒤의 변경·해지에 바뀌지 않음). null = 관문 전 등록.
   * MASTER는 늘 보내지만 시험 Fixture가 생략할 수 있어 선택 필드다(없으면 줄을 그리지 않는다).
   */
  registrationBasis?: { contractSeq: number; versionNo: number } | null
}

/** 변경 이력 항목(§3.3 — 감사 항목 투영, `detail` 없음). */
export interface MerchantHistoryEntry {
  seq: number
  occurredAt: string
  operation: string
  aggregateType: string
  aggregateId: string
  changeType: string
  actorRef: string
}

/**
 * 가맹점 등록(MERCHANT-OPS-002 §4.3 — B1-1, OPERATOR만). [submissionId] = 폼 제출 단위 UUID v4 소문자 hex 32자(결과가 확정될 때까지 재전송에 같은 값).
 * 응답은 저장된 판정만 200 — 성공은 reference, 거절은 rejection(기존 ID 없음 — D-MOPS-006 ③).
 * BUSINESS-PROFILE-001 BIZ-4(C-27): 옛 사업자 등록(`BusinessRegistrationInput` — 번호·상호만)은 제거됐다. 사업자는 [BusinessCreateInput]으로 만든다.
 */
export interface MerchantRegistrationInput {
  submissionId: string
  businessId: string
  displayName: string
  /** MERCHANT-CODE-001 MC-1: 가맹점 코드(필수 — `^[a-z][a-z0-9]{5,13}$`, 정규화 없음). 예약어·중복은 MASTER 거절. */
  merchantCode: string
}

export interface SiteRegistrationInput {
  submissionId: string
  merchantId: string
  channelType: 'WEB' | 'APP'
  channelIdentifier: string
}

/**
 * MERCHANT-OPS-002 §4.3 B1-2 계약 초안(Revision 1 — 미승인). [scopeSiteIds]가 비면 가맹점 전체(MERCHANT Scope).
 * 날짜는 Asia/Seoul 날짜 `YYYY-MM-DD`(C-7 — 서버가 그 날 00:00 KST로 바꾼다), [effectiveToDate]는 그 날 0시 종료(미포함), 없으면 종료 없음.
 */
export interface ContractRegistrationInput {
  submissionId: string
  partyBusinessId: string
  scopeMerchantId: string
  scopeSiteIds: string[]
  effectiveFromDate: string
  effectiveToDate?: string
}

/**
 * MERCHANT-OPS-002 §9.2 B2-1 계약 Revision 등록(1인 — 미승인으로 남는다). 범위·날짜 규칙은 계약 초안과 같다(C-7). 성공 reference는 `ctr_…#<n>`.
 */
export interface ContractRevisionInput {
  submissionId: string
  contractId: string
  scopeMerchantId: string
  scopeSiteIds: string[]
  effectiveFromDate: string
  effectiveToDate?: string
}

/** B1-2 서비스 계약(CSA) — 현재 카드 PG 결제 하나(D-MD-002). */
export interface ServiceAgreementInput {
  submissionId: string
  contractId: string
  serviceKind: 'CARD_PG_PAYMENT'
}

export interface RegistrationOutcome {
  outcome: 'SUCCEEDED' | 'REJECTED'
  reference?: string
  rejection?: string
  replayed: boolean
}

/**
 * 사업자 관리(BUSINESS-PROFILE-001 §6·§7 — BIZ-4 화면). 응답은 MASTER 문서 그대로(ADMIN 모양 검사 통과분).
 * [businessId](biz_…)는 **화면에 그리지 않는** 내부 키(D-ADMIN-UI-004 ③·X-4) — 상세·명령 호출 경로에만 쓴다. 사업자등록번호는 원문 10자리 `registrationNumber`(BRN-UNMASK-001 — D-BIZ-007, 화면은 하이픈 표시 — URL·저장소에 두지 않는다).
 */
export type BusinessType = 'INDIVIDUAL' | 'CORPORATION'
export type BusinessRegistrationStatus = 'DRAFT' | 'REGISTERED' | 'INACTIVE'

/** 목록 검색 조건(§6.2 — 모두 선택). **빈 값은 보내지 않는다**(키 생략 — JSON null은 ADMIN·MASTER 모두 400, BIZ-3 리뷰 1). 번호 검색 필드는 없다(C-6 — 번호 조회만). */
export interface BusinessSearchInput {
  displayNamePrefix?: string
  businessType?: BusinessType
  registrationStatus?: BusinessRegistrationStatus
  /** KST 날짜 `yyyy-MM-dd`(from ≤ to, to는 그 날 끝 포함 — 서버 변환). */
  createdFrom?: string
  createdTo?: string
  after?: string
  limit?: number
}

export interface BusinessListItem {
  businessId: string
  businessName: string
  registrationNumber: string
  businessType: BusinessType | null
  registrationStatus: BusinessRegistrationStatus
  verificationStatus: string
  representativeNameMasked: string | null
  representativeCount: number
  merchantCount: number
  updatedAt: string
}

export interface BusinessSearchPage {
  items: BusinessListItem[]
  next: string | null
}

/** 번호 정확 일치 조회 결과 0/1건(§6.3 — 합친 MERCHANT-BIZ-LOOKUP-001, D-BIZ-006 ③ registrationStatus). */
export interface BusinessLookupItem {
  businessId: string
  businessName: string
  registrationNumber: string
  registrationStatus: BusinessRegistrationStatus
}

export interface BusinessDetail {
  business: {
    businessId: string
    businessName: string
    registrationNumber: string
    businessType: BusinessType | null
    registrationStatus: BusinessRegistrationStatus
    verificationStatus: string
    lifecycle: string
    version: number
    createdAt: string
    updatedAt: string
    merchantCount: number
  }
  registration: {
    corporateRegistrationNumber: string | null
    openingDate: string | null
    countryCode: string
    englishName: string | null
    phoneNumber: string | null
    email: string | null
  }
  representatives: { name: string; representativeType: string }[]
  categories: { categoryType: string; name: string }[]
  addresses: { addressType: string; postalCode: string; baseAddress: string; detailAddress: string | null }[]
  contacts: { name: string; email: string; phoneNumber: string; primary: boolean }[]
  /**
   * MERCHANT-FLOW-001 FLOW-1 '계약 진행' 섹션(ADMIN이 상세 열람 안에서 MASTER 섹션을 함께 읽어 붙인다 — 감사는 상세 열람 1행). 서버는 늘 보낸다. 없으면(옛 문서) 불러오지 못함으로 그린다.
   */
  contractProgress?: ContractProgress
}

/**
 * 계약 접수 건 상태(MERCHANT-FLOW-001 §4A.3·§4B.6). '계약 완료'는 영업 기록 — 가맹점 계약 승인(결제 허용)과 다르다(R-1). FLOW-2b: `TERMINATED`(계약 해지) 없음 — 해지는 사업자 계약 명령(D-GOV-018 ②ⓑ).
 */
export type ContractIntakeStatus = 'CONSULTING' | 'DOCUMENT_RECEIVED' | 'COMPLETED' | 'REJECTED'
/** 접수 건 종류(BR-BIZ-324·325 — 생성 때 서버가 사업자 계약 상태로 판정, 화면은 판정한 종류를 expectedKind로 보낸다). */
export type ContractIntakeKind = 'NEW' | 'CHANGE'
/** 사업자 계약 상태(§4B.4 — 서버 계산 값). */
export type BusinessContractStatus = 'NOT_YET_EFFECTIVE' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED' | 'EXPIRED'

/**
 * 계약 진행 섹션. `available=false` = 섹션 조회 실패(fail-safe — 상세는 그대로, today·businessContract 없음).
 * [today] = ADMIN 시계의 KST 날짜(접수일·시작일 기본값 — 브라우저 날짜를 쓰지 않는다, §4A.6 n-4). [businessContract] = 최신 사업자 계약(없으면 null — FLOW-2b).
 */
export interface ContractProgress {
  available: boolean
  today?: string
  businessContract?: BusinessContractView | null
  intakes: ContractIntakeView[]
}

/** 사업자 계약 칸(§4B.11 1). 상태·적용 중·예정 버전 번호는 서버 계산 값. 처리자 표시명은 ADMIN이 채운다(m-11 — 계정 키·행위자 참조 없음). */
export interface BusinessContractView {
  contractSeq: number
  migrated: boolean
  status: BusinessContractStatus
  applyingVersionNo: number | null
  scheduledVersionNo: number | null
  linkedMerchantCount: number
  createdAt: string
  versions: BusinessContractVersionView[]
  events: BusinessContractEventView[]
}

/** 버전(번호 오름차순, 불변). 이관 버전은 시작일·종료일·근거 없음. [createdAt] = 기록 시각(MINOR-4 — 시작일과 함께 보인다). */
export interface BusinessContractVersionView {
  versionNo: number
  migrated: boolean
  startsAt: string
  startDate: string | null
  endDate: string | null
  services: string[]
  documentRef: string | null
  closedAt: string | null
  createdAt: string
  actorKind: 'ADMIN' | 'SYSTEM'
  actorName: string | null
}

/** 정지·정지 해제·해지 기록(FLOW-2c에서 생긴다 — 표시만). 사유 원문은 이력 탭. */
export interface BusinessContractEventView {
  eventSeq: number
  kind: 'SUSPEND' | 'RESUME' | 'TERMINATE'
  evidenceRef: string | null
  actorKind: 'ADMIN' | 'SYSTEM'
  actorName: string | null
  occurredAt: string
}

/** 접수 건 생성(§4B.11 — 모든 key 필수, 선택 값 null). [contractManagerId] = 계약 담당자 계정 키(화면에 그리지 않는다 — 본문에만). */
export interface ContractIntakeOpenInput {
  submissionId: string
  expectedKind: ContractIntakeKind
  receivedOn: string
  intakeChannel: string
  contractManagerId: string
  desiredServices: string[]
  reason: string | null
}

/** 전이(계약서 접수·되돌리기·계약 불가). [expectedVersion] = 접수 건 version(낙관적 잠금). */
export interface ContractIntakeMoveInput {
  submissionId: string
  expectedVersion: number
  target: 'DOCUMENT_RECEIVED' | 'CONSULTING' | 'REJECTED'
  reason: string | null
}

/** 계약 완료(근거 필수, 시작일 KST 날짜, 종료일 선택, 범위 1개 이상 — 희망 서비스와 별개). */
export interface ContractIntakeCompleteInput {
  submissionId: string
  expectedVersion: number
  documentRef: string
  startDate: string
  endDate: string | null
  services: string[]
  reason: string | null
}

/** FLOW-2c 사업자 계약 정지·정지 해제·해지(§4B.7): '사유 및 비고' 필수, 근거 참조 선택(D-GOV-022), 기대 사건 수 = 화면이 본 사건 수(다르면 MASTER `BUSINESS_CONTRACT_STATE_CONFLICT`). */
export interface BusinessContractEventInput {
  submissionId: string
  expectedEventCount: number
  reason: string
  evidenceRef: string | null
}

/** 접수 건(최신부터). 이관 건([migrated])은 접수일·경로·담당자 없음(R-6 — 화면은 '이관'). [version]은 FLOW-2 전이 명령의 기대값(화면에 그리지 않는다). */
export interface ContractIntakeView {
  intakeSeq: number
  status: ContractIntakeStatus
  kind: ContractIntakeKind
  /** 변경 접수의 대상 사업자 계약 순번(신규는 null). */
  targetContractSeq: number | null
  migrated: boolean
  receivedOn: string | null
  intakeChannel: string | null
  /** ADMIN이 채운 계약 담당자 표시명(m-11 — 로그인 이름·계정 키 없음). 이관 건이거나 계정을 찾지 못하면 null. */
  contractManagerName: string | null
  desiredServices: string[]
  version: number
  createdAt: string
  updatedAt: string
  transitions: ContractTransitionView[]
}

/** 전이 이력(순번 오름차순). 근거 참조는 원문(사업자 조회 역할 — D-FLOW-004 ④). 처리자는 ADMIN 표시명 또는 SYSTEM(이름 없음). '사유 및 비고'는 없다(이력 탭). */
export interface ContractTransitionView {
  transitionSeq: number
  fromStatus: ContractIntakeStatus | null
  toStatus: ContractIntakeStatus
  migrated: boolean
  documentRef: string | null
  actorKind: 'ADMIN' | 'SYSTEM'
  actorName: string | null
  occurredAt: string
}

/** 이력 항목의 바뀐 값(저장 시점 마스킹 값 그대로 — §9). 행 목록 항목은 `ended`·`added`. */
export interface BusinessHistoryChange {
  field: string
  before?: unknown
  after?: unknown
  ended?: Record<string, unknown>[]
  added?: Record<string, unknown>[]
}

export interface BusinessHistoryEntry {
  seq: number
  changeType: string
  actorRef: string
  occurredAt: string
  reason: string | null
  changes: BusinessHistoryChange[]
}

export interface BusinessHistoryPage {
  items: BusinessHistoryEntry[]
  nextAfter: number | null
}

/**
 * 명령 내용(§6.6 — **모든 key 필수, 선택 값은 null**, BIZ-3 리뷰 2). `registration.countryCode`는 항상 "KR"(TBD-10).
 * 사업자등록번호·상태는 생성([BusinessCreateInput])에만 있다(수정·등록 완료 본문에 없음 — TBD-04).
 */
export interface BusinessProfileContent {
  businessType: string
  displayName: string
  registration: {
    corporateRegistrationNumber: string | null
    openingDate: string | null
    countryCode: 'KR'
    englishName: string | null
    phoneNumber: string | null
    email: string | null
  }
  representatives: { name: string; representativeType: string }[]
  categories: { categoryType: string; name: string }[]
  addresses: { addressType: string; postalCode: string; baseAddress: string; detailAddress: string | null }[]
  contacts: { name: string; email: string; phoneNumber: string; primary: boolean }[]
}

export interface BusinessCreateInput extends BusinessProfileContent {
  submissionId: string
  registrationStatus: 'DRAFT' | 'REGISTERED'
  registrationNumber: string
}

/** 수정·등록 완료(같은 본문 — 대상 businessId는 경로). [version] = 상세 `business.version`(낙관적 잠금 기대값). */
export interface BusinessChangeInput extends BusinessProfileContent {
  submissionId: string
  version: number
  reason: string | null
}

/** 명령 결과(§7.3): [existing]은 중복 거절 + 조회 권한일 때만(D-BIZ-003 — 기존 사업자 상호·원문 번호(D-BIZ-007)·상세 링크용 내부 키). */
export interface BusinessCommandOutcome extends RegistrationOutcome {
  existing?: BusinessLookupItem
}

export interface MerchantHistoryPage {
  items: MerchantHistoryEntry[]
  nextCursor: number | null
}

/**
 * 거래 검색 조건(TXN-OPS-001 §3.1·§4.1 — POST 본문). 모드: [paymentId] 정확 조회(다른 조건 없음) / [merchantOrderId](+ [merchantAccountId] 필수) / 그 밖은 기간 필수
 * ([createdFrom], [createdTo] — UTC ISO ms, `[from, to)`, 최대 31일 Demo 값). [after]는 다음 쪽 cursor, [limit] 1..50(없으면 서버 기본 20). 서버가 다시 검사한다.
 */
export interface TransactionSearchInput {
  createdFrom?: string
  createdTo?: string
  environment?: 'TEST' | 'LIVE'
  merchantAccountId?: string
  merchantOrderId?: string
  paymentId?: string
  status?: string
  after?: TransactionCursor
  limit?: number
}

export interface TransactionCursor {
  createdAt: string
  paymentId: string
}

/** 목록 항목. 주문명은 앞 10자만(D-TXOPS-003 — [orderNameTruncated]면 더 있다). 승인번호·기관 거래번호는 목록에 없다. */
export interface TransactionListItem {
  paymentId: string
  environment: string
  merchantAccountId: string
  merchantOrderId: string
  orderNamePreview: string | null
  orderNameTruncated: boolean
  amountMinor: number
  currency: string
  /** 결제수단 코드(TXN-LIST-ENRICH-001 — PAYMENT 소유, 지금은 `CARD` = 신용 + 체크). 화면은 `labelOf`, 모르는 코드는 원문. */
  paymentMethod: string
  status: string
  createdAt: string
  /** 가맹점 라벨(상호·현재 가맹점 코드 — MASTER). 라벨이 없거나 불러오지 못하면 null. 내부 키(`mer_`·`biz_`)는 없다(D-MCODE-003). */
  merchant: TransactionMerchant | null
}

/** 가맹점 표시 라벨 — 화면 표기 "<상호> · <가맹점 코드>"(D-ADMIN-UI-004 ③). */
export interface TransactionMerchant {
  name: string
  code: string
}

/** 이 쪽의 라벨 상태(TXN-LIST-ENRICH-001 §3.3): OK(못 찾은 MID만 null) / UNAVAILABLE(MASTER 불가 — 모든 항목 null, 목록은 그대로). */
export type MerchantLabelsState = 'OK' | 'UNAVAILABLE'

export interface TransactionSearchPage {
  items: TransactionListItem[]
  nextCursor: TransactionCursor | null
  merchantLabels: MerchantLabelsState
}

/**
 * 거래 상세(TXN-OPS-001 §3.4 — 한 번의 일관 읽기, 서버가 PAYMENT_VIEWED 감사 후 반환). 주문명 전체·승인번호·기관 거래번호 포함. 기관 내부 계정 값·운영자 취소 사유는 없다.
 * 시각은 UTC ISO, 날짜는 `YYYY-MM-DD`.
 */
export interface TransactionDetail {
  payment: {
    paymentId: string; environment: string; merchantAccountId: string; merchantSiteId: string; merchantOrderId: string; orderName: string | null
    amountMinor: number; currency: string; paymentMethod: string; status: string; createdAt: string
    /** TXN-LIST-ENRICH-001 E-4(§4.5): 가맹점 라벨(없거나 불러오지 못하면 null). 선택 필드 — 이 화면 단계는 상세에 라벨을 아직 표시하지 않는다. */
    merchant?: TransactionMerchant | null
  }
  /** TXN-LIST-ENRICH-001 E-4(§4.5): 상세 라벨 상태. */
  merchantLabels?: MerchantLabelsState
  onHold: boolean
  terminationEvidence: string[]
  authorization: {
    authorizationId: string; status: string; failureEvidence: string | null; planKind: string; installmentMonths: number | null; cardType: string; openedAt: string
  } | null
  transactions: {
    transactionId: string; type: string; amountMinor: number; currency: string; planKind: string | null; installmentMonths: number | null; cardType: string | null
    approvalNumber: string | null; providerTransactionId: string; cancellationId: string | null; originalTransactionId: string | null; cancelScope: string | null
    institutionTransactionDate: string; institutionBusinessDate: string | null; confirmedAt: string; recordedBy: string
  }[]
  cancellations: {
    cancellationId: string; initiatorKind: string; merchantCancelId: string | null; scope: string; status: string; failureEvidence: string | null
    amountMinor: number; currency: string; openedAt: string
  }[]
  returnBalance: { currency: string; authorizedMinor: number; completedMinor: number; reservedMinor: number; forcedMinor: number; revision: number } | null
}

/**
 * 매입 파일 검색 조건(ACQ-VIEW-001 §3.3·§4.6 — POST 본문, FINANCIAL 본문과 같은 키). 모드: [fileId] 정확 조회(다른 조건 없음) / 그 밖은 업무일 기간
 * [businessDateFrom]·[businessDateTo](`YYYY-MM-DD` KST 달력일, 양끝 포함, 최대 31일 Demo 값) 필수. [needsAttention]은 true만 보낸다. 서버가 다시 검사한다.
 */
export interface AcquisitionSearchInput {
  businessDateFrom?: string
  businessDateTo?: string
  fileId?: string
  acquirerCode?: string
  environment?: 'TEST' | 'LIVE'
  transmissionState?: string
  receiptState?: string
  resultState?: string
  needsAttention?: true
  after?: AcquisitionCursor
  limit?: number
}

export interface AcquisitionCursor {
  businessDate: string
  fileId: string
}

/** 레코드 현재 상태별 건수(FINANCIAL `RecordResultRule.status`와 같은 정의). */
export interface AcquisitionRecordStatusCounts {
  awaiting: number; accepted: number; rejected: number; fileRejected: number; unknown: number; conflict: number
}

/**
 * 매입 파일 목록 항목(ACQ-VIEW-001 §4.1). 3축 상태(전송·수신·결과)는 저장 값 그대로, [attention]은 서버가 계산한 "확인 필요" 사유 code, [reacquisitionCandidate]는 재매입 검토 대상
 * (거절이 있음 — 실행은 별도 승인 화면). 승인번호·기관 거래번호·발급사·카드 종류·할부는 없다(D-ACQV-002).
 */
export interface AcquisitionFileItem {
  fileId: string
  acquirerCode: string
  environment: string
  businessDate: string
  fileVersion: number
  originalFileId: string | null
  recordCount: number
  saleCount: number
  saleAmountMinor: number
  cancelCount: number
  cancelAmountMinor: number
  netAmountMinor: number
  currency: string
  transmissionState: string
  receiptState: string
  resultState: string
  sealedAt: string
  transmittedAt: string | null
  recordStatusCounts: AcquisitionRecordStatusCounts
  attention: string[]
  reacquisitionCandidate: boolean
  /** ACQ-ADMIN-001 B2(§5.1 3상태): 검토 대상은 아니고 새 세대 재매입이 진행 중. 둘 다 false = 배지 없음(새 세대 매입 확정 뒤 포함). */
  reacquisitionInProgress: boolean
}

export interface AcquisitionSearchPage {
  items: AcquisitionFileItem[]
  nextCursor: AcquisitionCursor | null
}

/** 레코드 결과 행(append-only). 응답은 종류 + 순번으로만 가리킨다(결과 마감 행은 null). */
export interface AcquisitionRecordResult {
  entryNo: number
  result: string
  observedResult: string | null
  reasonCode: string | null
  source: string
  responseRef: { kind: string; sequenceNo: number } | null
  recognizedAt: string
}

export interface AcquisitionRecord {
  seq: number
  recordType: string
  transactionId: string
  originalTransactionId: string | null
  merchantAccountId: string
  institutionTransactionDate: string
  eventAt: string
  currency: string
  approvedAmountMinor: number
  nettedCancelAmountMinor: number
  recordAmountMinor: number
  generation: number
  reacquisitionOf: string | null
  status: string
  results: AcquisitionRecordResult[]
}

export interface AcquisitionRecordPage {
  items: AcquisitionRecord[]
  nextCursor: number | null
}

export interface AcquisitionRecordSearchInput {
  status?: string
  afterSeq?: number
  limit?: number
}

/** 매입 파일 상세(ACQ-VIEW-001 §4.2 — 한 번의 일관 읽기, 서버가 ACQUISITION_FILE_VIEWED 감사 후 반환). 봉인·응답 바이트·응답 파일명·송신자 ID는 없다. */
export interface AcquisitionFileDetail {
  file: AcquisitionFileItem & {
    cutoffPolicyVersion: string; cutoffAt: string; contentSha256: string; contentBytes: number; transmissionLeaseUntil: string | null; updatedAt: string
  }
  attempts: {
    total: number
    latest: {
      attemptNo: number; resumeState: string; claimedAt: string; leaseUntil: string; outcome: string | null; observed: string | null; evidence: string | null
      reason: string | null; resultingState: string | null; decidedAt: string | null
    }[]
  }
  responses: {
    total: number
    quarantined: number
    latest: {
      kind: string; sequenceNo: number; disposition: string; quarantineReason: string | null; ackOutcome: string | null; ackReasonCode: string | null
      evidence: string | null; institutionAt: string | null; observedAt: string
    }[]
  }
  records: AcquisitionRecordPage
  readAt: string
}

/**
 * 화면이 쓰는 서버 API(같은 출처 `/api/` — [apiFetch] 하나로). 등록 코드·TOTP 시크릿은 응답을 받은 화면 상태에만 잠깐 두고 저장소·URL에 쓰지 않는다.
 * 권한 판정은 서버가 한다.
 */
/**
 * 운영 홈 KPI 한 행(ADMIN-HOME-KPI-001 §4.1·§4.2 — 행 키 (environment, currency), LIVE·TEST 별도 행·통화 간 합산 없음 C-1·D-CP-002).
 * 금액은 모두 **minor unit 정수의 10진 문자열**(`^(0|-?[1-9][0-9]{0,39})$` — 부동소수 변환 금지, 2^53 초과 가능).
 * [approvedAmountMinor]·[cancelledAmountMinor] ≥ 0, [netApprovedAmountMinor] = approved − cancelled(음수 가능).
 * [approvedCount] — 구간 안 승인 중 asOf까지 전액취소되지 않은 건수(부분취소 포함, §3.3).
 */
export interface HomeKpiItem {
  environment: string
  currency: string
  approvedAmountMinor: string
  cancelledAmountMinor: string
  netApprovedAmountMinor: string
  approvedCount: number
}

/**
 * 운영 홈 오늘 KPI 문서(ADMIN-HOME-KPI-001 §4.2 `GET /api/home-kpis`) — 구간 [[from], [asOf]), 시각 원천 PG 확정 시각 `confirmed_at`(D-ADMIN-HOME-002 ③).
 * 비권위 표시값(정산 금액 아님). 빈 날 = 빈 [items](C-3).
 */
export interface HomeKpis {
  asOf: string
  businessDate: string
  timeZone: string
  from: string
  items: HomeKpiItem[]
}

export interface AdminApi {
  csrf(): Promise<void>
  /** 운영 홈 오늘 KPI([HomeKpis]). 조회 전용. */
  getHomeKpis(): Promise<HomeKpis>
  me(): Promise<Me>
  password(loginName: string, password: string): Promise<{ next: string }>
  totp(code: string): Promise<Me>
  logout(): Promise<void>
  /**
   * ADMIN-DEMO-LOGIN-001: 데모 간편 로그인 계정 목록. 서버가 꺼져 있으면(엔드포인트 없음 — 404) null. 선택 메서드 — 없으면 꺼짐으로 본다(시험 대역 기본).
   * 켜짐 판정·권한은 서버, 화면은 이 결과로 버튼·표시만 한다.
   */
  demoQuickLoginOptions?(): Promise<DemoQuickLoginAccount[] | null>
  /** ADMIN-DEMO-LOGIN-001: 그 계정의 정상 세션 발급(CSRF 세션 필요 — 호출 전에 csrf()). 성공 본문은 me()와 같다. */
  demoQuickLogin?(loginName: string): Promise<Me>
  enrollStart(code: string): Promise<EnrollStart>
  enrollPassword(password: string): Promise<void>
  enrollTotp(): Promise<{ otpauthUri: string }>
  enrollComplete(totpCode: string | undefined): Promise<{ status: string }>
  /** [truncated]면 서버 상한(500행)에서 잘렸다(S8 리뷰 nit-5). */
  accounts(): Promise<{ accounts: AccountView[]; truncated?: boolean }>
  createAccount(loginName: string, displayName: string): Promise<{ actorId: string; enrollmentCode: string }>
  suspend(actorId: string): Promise<{ status: string }>
  reissue(actorId: string): Promise<{ actorId: string; enrollmentCode: string }>
  revokeRole(actorId: string, role: string): Promise<{ status: string }>
  reactivationCauses(actorId: string): Promise<{ causes: ReactivationCause[] }>
  approvals(view: 'pending' | 'mine'): Promise<{ approvals: ApprovalView[] }>
  requestApproval(input: ApprovalRequestInput): Promise<{ approvalId: string }>
  approve(approvalId: string, totpCode: string): Promise<{ approvalId: string; status: string; enrollmentCode?: string }>
  reject(approvalId: string, totpCode: string): Promise<{ status: string }>
  withdraw(approvalId: string): Promise<{ status: string }>
  audit(filter: AuditFilterInput): Promise<{ entries: AuditEntry[] }>
  /** S8(§4.20): 대상 조회(모든 조회 역할), 보정 취소 요청(OPERATOR), 승인 상세, 근거·사유 권한 조회(D-OPS-010), 대사 발견(SECURITY_ADMIN·APPROVER). */
  paymentTarget(paymentId: string): Promise<PaymentTargetView>
  requestOperatorCancel(input: OperatorCancelInput): Promise<{ approvalId: string }>
  approvalDetail(approvalId: string): Promise<ApprovalView>
  justification(approvalId: string): Promise<Justification>
  /** 재매입 승인 근거 참조(D-ACQA-005 — 같은 `/justification` endpoint, 서버가 APPROVER·요청자 본인만 허용하고 조회마다 감사). */
  reacquisitionEvidence(approvalId: string): Promise<ReacquisitionEvidence>
  reconciliation(): Promise<ReconciliationOverview>
  /** MERCHANT-OPS-001 §4.1(조회 4역할, 등록번호 검색은 서버가 OPERATOR·APPROVER만 허용). 검색은 POST 본문(R-3), 상세는 서버가 감사(D-MOPS-002). */
  merchantSearch(input: MerchantSearchInput): Promise<MerchantSearchPage>
  merchantDetail(merchantId: string): Promise<MerchantDetail>
  /** [after] 없으면 첫 쪽. */
  merchantHistory(merchantId: string, after?: number): Promise<MerchantHistoryPage>
  /**
   * BUSINESS-PROFILE-001 §7.1(BIZ-4 화면): 목록·번호 조회·상세·이력 = 조회 역할(OPERATOR·APPROVER — D-BIZ-006 ①), 명령 = OPERATOR. 판정·감사·속도 제한은 서버.
   * 번호 조회는 POST 본문(번호가 URL·로그에 없음 — C-6), 상세는 서버가 열람마다 감사(BUSINESS_VIEWED).
   */
  searchBusinesses(input: BusinessSearchInput): Promise<BusinessSearchPage>
  lookupBusinessByRegistrationNumber(registrationNumber: string): Promise<{ items: BusinessLookupItem[] }>
  businessDetail(businessId: string): Promise<BusinessDetail>
  /** [after] 없으면 첫 쪽(`?after=<seq>`). */
  businessHistory(businessId: string, after?: number): Promise<BusinessHistoryPage>
  createBusiness(input: BusinessCreateInput): Promise<BusinessCommandOutcome>
  updateBusiness(businessId: string, input: BusinessChangeInput): Promise<BusinessCommandOutcome>
  completeBusinessRegistration(businessId: string, input: BusinessChangeInput): Promise<BusinessCommandOutcome>
  /** MERCHANT-FLOW-001 FLOW-2b(§4B.11 표 2b 행): 접수 건 생성·전이·계약 완료(OPERATOR — 서버 판정, 결과 불명은 같은 submissionId 재전송). */
  openContractIntake(businessId: string, input: ContractIntakeOpenInput): Promise<RegistrationOutcome>
  moveContractIntake(businessId: string, intakeSeq: number, input: ContractIntakeMoveInput): Promise<RegistrationOutcome>
  completeContractIntake(businessId: string, intakeSeq: number, input: ContractIntakeCompleteInput): Promise<RegistrationOutcome>
  /** MERCHANT-FLOW-001 FLOW-2c(§4B.11 3): 사업자 계약 정지·정지 해제·해지(OPERATOR — 즉시 효력, 새 가맹점 등록 자격만 바뀜). */
  suspendBusinessContract(businessId: string, contractSeq: number, input: BusinessContractEventInput): Promise<RegistrationOutcome>
  resumeBusinessContract(businessId: string, contractSeq: number, input: BusinessContractEventInput): Promise<RegistrationOutcome>
  terminateBusinessContract(businessId: string, contractSeq: number, input: BusinessContractEventInput): Promise<RegistrationOutcome>
  registerMerchant(input: MerchantRegistrationInput): Promise<RegistrationOutcome>
  registerMerchantSite(input: SiteRegistrationInput): Promise<RegistrationOutcome>
  /** MERCHANT-OPS-002 §4.3 B1-2(OPERATOR만). 시작일 소급 판정은 MASTER(§3.4 — 화면 최솟값은 안내일 뿐). */
  registerContract(input: ContractRegistrationInput): Promise<RegistrationOutcome>
  addServiceAgreement(input: ServiceAgreementInput): Promise<RegistrationOutcome>
  registerContractRevision(input: ContractRevisionInput): Promise<RegistrationOutcome>
  /** TXN-OPS-001 §4.1(조회 4역할): 검색은 POST 본문(R-2, 감사 없음), 상세는 서버가 감사(D-TXOPS-002). */
  transactionSearch(input: TransactionSearchInput): Promise<TransactionSearchPage>
  transactionDetail(paymentId: string): Promise<TransactionDetail>
  /** ACQ-VIEW-001 §4.6(조회 4역할 — D-ACQV-001): 검색은 POST 본문(R-4, 감사 없음), 파일 상세·레코드 쪽은 서버가 감사 후 반환. */
  acquisitionSearch(input: AcquisitionSearchInput): Promise<AcquisitionSearchPage>
  acquisitionDetail(fileId: string): Promise<AcquisitionFileDetail>
  acquisitionRecords(fileId: string, input: AcquisitionRecordSearchInput): Promise<AcquisitionRecordPage>
  /** ACQ-ADMIN-001 B1-A: 재매입 대상 미리 보기(조회 키 — 서버가 감사 후 반환), 재매입 요청(OPERATOR → 승인함). */
  reacquisitionTargets(fileId: string, seqs: number[]): Promise<ReacquisitionTargets>
  requestReacquisition(input: ReacquisitionRequestInput): Promise<{ approvalId: string }>
  /** MERCHANT-OPS-002 B2-2: 계약 승인 요청(OPERATOR → 승인함), 근거 참조 권한 조회(같은 `/justification` — APPROVER·요청자만, 조회마다 감사). */
  requestMerchantContractApproval(input: MerchantContractApprovalInput): Promise<{ approvalId: string }>
  merchantApprovalEvidence(approvalId: string): Promise<ReacquisitionEvidence>
  /** B2-3: 첫 Default MID 발급 요청(OPERATOR → 승인함). */
  requestMerchantFirstMid(input: MerchantFirstMidInput): Promise<{ approvalId: string }>
  /** B3-1(§9.16): 1인 개통 등록·Site 심사 거절(OPERATOR — 서버가 판정·감사), 2인 개통 승인·Site 심사 승인 요청(OPERATOR → 승인함). */
  registerPaymentMethodActivation(input: PaymentMethodActivationInput): Promise<RegistrationOutcome>
  rejectSiteReview(input: SiteReviewRejectionInput): Promise<RegistrationOutcome>
  requestMerchantPaymentMethodActivation(input: MerchantActivationApprovalInput): Promise<{ approvalId: string }>
  requestMerchantSiteReviewApproval(input: MerchantSiteReviewApprovalInput): Promise<{ approvalId: string }>
  requestMerchantCurrencyEnablement(input: MerchantCurrencyEnablementInput): Promise<{ approvalId: string }>
  /** MERCHANT-CODE-001 MC-1b 가맹점 코드 변경 요청(201 승인 ID). */
  requestMerchantCodeChange(input: MerchantCodeChangeInput): Promise<{ approvalId: string }>
  /** MERCHANT-CODE-001 §5.6.3 MC-3b: 가맹점 대상 라벨 묶음(1..50 — 화면이 고유 키를 정렬해 50개씩 순차, 0개면 부르지 않는다). 429는 UNAVAILABLE과 같게 다룬다. */
  merchantLabels(merchantIds: string[]): Promise<MerchantTargetLabelPage>
}

const segment = (value: string) => encodeURIComponent(value)

/** 필터만 쿼리로(빈 값 생략). 범위·역할은 보내지 않는다 — 서버가 세션 계정 현재 행으로 정한다. */
export function auditQuery(filter: AuditFilterInput): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filter)) if (value) params.set(key, value)
  const text = params.toString()
  return text ? `?${text}` : ''
}

/**
 * 사업자 목록 검색 본문(BIZ-3 리뷰 1 — 인계 ④): 값이 없거나 빈 문자열인 필드는 **키를 생략**한다(null·빈 문자열을 보내지 않는다 — 서버는 null을 400으로 거부).
 */
export function businessSearchBody(input: BusinessSearchInput): Record<string, string | number> {
  const body: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(input) as [string, unknown][]) {
    if (typeof value === 'string' && value !== '') body[key] = value
    else if (typeof value === 'number' && Number.isInteger(value)) body[key] = value
  }
  return body
}

export function createAdminApi(deps?: ApiDeps): AdminApi {
  const call = <T,>(path: string, method = 'GET', body?: unknown) => apiFetch<T>(path, { method, body }, deps)
  return {
    csrf: () => call<void>('/api/auth/csrf'),
    // ADMIN-HOME-KPI-001 §4.2. 조회 전용(polling 없음 — 진입·새로고침 때만).
    getHomeKpis: () => call<HomeKpis>('/api/home-kpis'),
    me: () => call<Me>('/api/auth/me'),
    password: (loginName, password) => call('/api/auth/password', 'POST', { loginName, password }),
    totp: (code) => call<Me>('/api/auth/totp', 'POST', { code }),
    logout: () => call<void>('/api/auth/logout', 'POST'),
    demoQuickLoginOptions: () =>
      call<{ accounts: DemoQuickLoginAccount[] }>('/api/auth/demo-quick-login').then(
        (body) => body.accounts,
        (e: unknown) => {
          if (e instanceof ApiError && e.status === 404) return null
          throw e
        },
      ),
    demoQuickLogin: (loginName) => call<Me>('/api/auth/demo-quick-login', 'POST', { loginName }),
    enrollStart: (code) => call<EnrollStart>('/api/enroll/start', 'POST', { code }),
    enrollPassword: (password) => call<void>('/api/enroll/password', 'POST', { password }),
    enrollTotp: () => call('/api/enroll/totp', 'POST'),
    enrollComplete: (totpCode) => call('/api/enroll/complete', 'POST', totpCode === undefined ? {} : { totpCode }),
    accounts: () => call('/api/accounts'),
    createAccount: (loginName, displayName) => call('/api/accounts', 'POST', { loginName, displayName }),
    suspend: (actorId) => call(`/api/accounts/${segment(actorId)}/suspend`, 'POST'),
    reissue: (actorId) => call(`/api/accounts/${segment(actorId)}/enrollment-code`, 'POST'),
    revokeRole: (actorId, role) => call(`/api/accounts/${segment(actorId)}/roles/${segment(role)}/revoke`, 'POST'),
    reactivationCauses: (actorId) => call(`/api/accounts/${segment(actorId)}/reactivation-causes`),
    approvals: (view) => call(`/api/approvals?view=${view}`),
    requestApproval: (input) => call('/api/approvals', 'POST', input),
    approve: (approvalId, totpCode) => call(`/api/approvals/${segment(approvalId)}/approve`, 'POST', { totpCode }),
    reject: (approvalId, totpCode) => call(`/api/approvals/${segment(approvalId)}/reject`, 'POST', { totpCode }),
    withdraw: (approvalId) => call(`/api/approvals/${segment(approvalId)}/withdraw`, 'POST'),
    audit: (filter) => call(`/api/audit${auditQuery(filter)}`),
    paymentTarget: (paymentId) => call(`/api/payment-ops/payments/${segment(paymentId)}/target`),
    requestOperatorCancel: (input) => call('/api/approvals/operator-correction-cancel', 'POST', input),
    approvalDetail: (approvalId) => call(`/api/approvals/${segment(approvalId)}`),
    justification: (approvalId) => call(`/api/approvals/${segment(approvalId)}/justification`),
    reacquisitionEvidence: (approvalId) => call(`/api/approvals/${segment(approvalId)}/justification`),
    reconciliation: () => call('/api/reconciliation/findings'),
    merchantSearch: (input) => call('/api/merchant-searches', 'POST', input),
    merchantDetail: (merchantId) => call(`/api/merchants/${segment(merchantId)}`),
    merchantHistory: (merchantId, after) => call(`/api/merchants/${segment(merchantId)}/history${after === undefined ? '' : `?after=${after}`}`),
    searchBusinesses: (input) => call('/api/businesses/searches', 'POST', businessSearchBody(input)),
    lookupBusinessByRegistrationNumber: (registrationNumber) => call('/api/businesses/registration-number-lookups', 'POST', { registrationNumber }),
    businessDetail: (businessId) => call(`/api/businesses/${segment(businessId)}`),
    businessHistory: (businessId, after) => call(`/api/businesses/${segment(businessId)}/history${after === undefined ? '' : `?after=${after}`}`),
    createBusiness: (input) => call('/api/businesses', 'POST', input),
    updateBusiness: (businessId, input) => call(`/api/businesses/${segment(businessId)}`, 'PUT', input),
    completeBusinessRegistration: (businessId, input) => call(`/api/businesses/${segment(businessId)}/registration`, 'POST', input),
    openContractIntake: (businessId, input) => call(`/api/businesses/${segment(businessId)}/contract-intakes`, 'POST', input),
    moveContractIntake: (businessId, intakeSeq, input) =>
      call(`/api/businesses/${segment(businessId)}/contract-intakes/${segment(String(intakeSeq))}/transitions`, 'POST', input),
    completeContractIntake: (businessId, intakeSeq, input) =>
      call(`/api/businesses/${segment(businessId)}/contract-intakes/${segment(String(intakeSeq))}/completion`, 'POST', input),
    suspendBusinessContract: (businessId, contractSeq, input) =>
      call(`/api/businesses/${segment(businessId)}/business-contracts/${segment(String(contractSeq))}/suspensions`, 'POST', input),
    resumeBusinessContract: (businessId, contractSeq, input) =>
      call(`/api/businesses/${segment(businessId)}/business-contracts/${segment(String(contractSeq))}/resumptions`, 'POST', input),
    terminateBusinessContract: (businessId, contractSeq, input) =>
      call(`/api/businesses/${segment(businessId)}/business-contracts/${segment(String(contractSeq))}/termination`, 'POST', input),
    registerMerchant: (input) => call('/api/merchant-registrations/merchants', 'POST', input),
    registerMerchantSite: (input) => call('/api/merchant-registrations/merchant-sites', 'POST', input),
    registerContract: (input) => call('/api/merchant-registrations/contracts', 'POST', input),
    addServiceAgreement: (input) => call('/api/merchant-registrations/service-agreements', 'POST', input),
    registerContractRevision: (input) => call('/api/merchant-registrations/contract-revisions', 'POST', input),
    transactionSearch: (input) => call('/api/payment-searches', 'POST', input),
    transactionDetail: (paymentId) => call(`/api/payments/${segment(paymentId)}`),
    acquisitionSearch: (input) => call('/api/acquisition-file-searches', 'POST', input),
    acquisitionDetail: (fileId) => call(`/api/acquisition-files/${segment(fileId)}`),
    acquisitionRecords: (fileId, input) => call(`/api/acquisition-files/${segment(fileId)}/record-searches`, 'POST', input),
    reacquisitionTargets: (fileId, seqs) => call(`/api/acquisition-files/${segment(fileId)}/reacquisition-targets`, 'POST', { seqs }),
    requestReacquisition: (input) => call('/api/approvals/acq-reacquisition', 'POST', input),
    requestMerchantContractApproval: (input) => call('/api/approvals/merchant-contract-approval', 'POST', input),
    merchantApprovalEvidence: (approvalId) => call(`/api/approvals/${segment(approvalId)}/justification`),
    requestMerchantFirstMid: (input) => call('/api/approvals/merchant-first-mid', 'POST', input),
    registerPaymentMethodActivation: (input) => call('/api/merchant-registrations/payment-method-activations', 'POST', input),
    rejectSiteReview: (input) => call('/api/merchant-registrations/site-review-rejections', 'POST', input),
    requestMerchantPaymentMethodActivation: (input) => call('/api/approvals/merchant-payment-method-activation', 'POST', input),
    requestMerchantSiteReviewApproval: (input) => call('/api/approvals/merchant-site-review-approval', 'POST', input),
    requestMerchantCurrencyEnablement: (input) => call('/api/approvals/merchant-currency-enablement', 'POST', input),
    requestMerchantCodeChange: (input) => call('/api/approvals/merchant-code-change', 'POST', input),
    merchantLabels: (merchantIds) => call('/api/merchant-labels', 'POST', { merchantIds }),
  }
}
