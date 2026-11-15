// @vitest-environment node
// 순수 함수 시험 — jsdom 환경을 만들지 않는다(시험 파일마다 드는 환경 비용 절약).
import { describe, expect, it } from 'vitest'
import { AUDIT_ACTION_LABELS, CODE_LABELS, labelOf, REVISION_AWAITING_APPROVAL } from './labels'
import { formatKst, formatMinor } from './format'
import { statusTone } from './statusTone'

/** 서버 enum·상수 전부(apps/internal-admin/src/main — 리뷰 m-3). 새 code가 생기면 여기와 labels.ts를 함께 고친다. */
const SERVER_CODES = [
  // AccountStatus
  'PENDING_ENROLLMENT', 'ACTIVE', 'LOCKED', 'SUSPENDED',
  // AdminRole
  'VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK',
  // ApprovalStatus
  'PENDING', 'EXECUTING', 'EXECUTION_UNKNOWN', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'INVALIDATED', 'OWNER_REJECTED',
  // ApprovalKind
  'ROLE_GRANT', 'ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'TOTP_REENROLL', 'PASSWORD_RESET', 'ROLE_REACTIVATE', 'OPERATOR_CORRECTION_CANCEL',
  // ACQ-ADMIN-001 B1-A 재매입(ApprovalKind ACQ_REACQUISITION)
  'ACQ_REACQUISITION',
  // 기대값 변경(B2-2 MERCHANT-OPS-002): 가맹점 계약 승인 종류, 대사 발견 OWNER_UNRESOLVABLE(C-28).
  'MERCHANT_CONTRACT_APPROVAL', 'OWNER_UNRESOLVABLE',
  // 기대값 변경(B2-3): 첫 Default MID 발급 종류.
  'MERCHANT_FIRST_MID_ISSUANCE',
  // 목록 추가(B3-1 MERCHANT-OPS-002 §9.16): 결제수단 개통 승인·Site 심사 승인 종류(앱이 만드는 B3 종류만 — B3-2~4는 앱이 만들지 않는다).
  'MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL',
  // 목록 추가(B3-2 MERCHANT-OPS-002 §9.16): 통화 활성화 종류.
  'MERCHANT_CURRENCY_ENABLEMENT',
  // MERCHANT-CODE-001 MC-1b 코드 변경 승인 종류(목록 확장)
  'MERCHANT_CODE_CHANGE',
  // OperatorCancelPurposeKind
  'INCIDENT_OR_RECON_CORRECTION', 'MERCHANT_TERMINATED_OBLIGATION',
  // ReconciliationFindingKind
  'ORPHAN_DECISION', 'DECISION_FOR_UNAPPROVED', 'FINGERPRINT_MISMATCH', 'STATE_DIVERGENCE', 'UNRESOLVED_WITH_DECISION', 'MISSING_DECISION',
  // AuditActorKind
  'ADMIN', 'ANONYMOUS', 'SYSTEM_BOOTSTRAP', 'SYSTEM_RECOVERY', 'SYSTEM_JOB',
  // 감사 resultCode
  'SUCCESS', 'OK', 'DENIED', 'REFUSED', 'FORBIDDEN', 'FAILED', 'FAILURE', 'UNKNOWN', 'FLAGGED', 'HALTED', 'COUNTED', 'NOT_COUNTED',
  // payment-api PaymentStatus(REQUESTED·AUTHORIZING·UNKNOWN·AUTHORIZED·FAILED·EXPIRED)
  'REQUESTED', 'AUTHORIZING', 'AUTHORIZED',
  // MERCHANT-OPS-001 가맹점 조회(master-api domain): Lifecycle(ACTIVE·TERMINATED), SiteReviewStatus(PENDING·APPROVED·REJECTED·SUSPENDED),
  // ActivationStatus(UNDER_REVIEW·ACTIVE·SUSPENDED·TERMINATED), SiteAssignmentRole, SiteAssignmentStatus(ACTIVE·SUSPENDED·ENDED), ChannelType,
  // 계약 Scope 종류(MasterPersistenceMapper SCOPE_*), LimitScopeKind, 한도 값 종류(AuthorizationTermsMapper), ServiceKind, PaymentMethod, EffectBasis
  'TERMINATED', 'APPROVED', 'UNDER_REVIEW', 'PRIMARY', 'ADDITIONAL', 'ENDED', 'WEB', 'APP', 'MERCHANT', 'SITES', 'CSA_DEFAULT', 'MID_OVERRIDE',
  'UNLIMITED', 'LIMITED', 'CARD_PG_PAYMENT', 'CARD', 'ACK', 'LEASE_EXPIRY',
  // MERCHANT-OPS-001 §5.1·§5.2 감사 resultCode(MERCHANT_VIEWED·MERCHANT_BRN_SEARCHED)
  'NOT_FOUND', 'INVALID_INPUT', 'RATE_LIMITED', 'UNAVAILABLE',
  // MERCHANT-OPS-002 §6 등록 감사 resultCode(REQUESTED·OK·REJECTED·FORBIDDEN·INVALID_INPUT·RATE_LIMITED는 위에 있음)
  'KEY_REUSED', 'BUSY', 'OUTCOME_UNKNOWN',
]

const SERVER_AUDIT_ACTIONS = [
  'LOGIN_PASSWORD', 'LOGIN_TOTP', 'LOGIN_SUCCEEDED', 'LOGOUT', 'AUTHENTICATION_FAILED', 'ACCOUNT_LOCKED', 'ACCOUNT_CREATED', 'ACCOUNT_SUSPENDED',
  'ROLE_REVOKED', 'ENROLLMENT_CODE_ISSUED', 'ENROLLMENT_COMPLETED', 'BOOTSTRAP', 'RECOVERY', 'RECOVERY_EPISODE_CLOSED', 'AUDIT_VIEWED', 'IP_DENIED',
  'UNMASK_REQUESTED', 'APPROVAL_REQUESTED', 'APPROVAL_EXECUTING', 'APPROVAL_EXECUTED', 'APPROVAL_EXECUTION_UNKNOWN', 'APPROVAL_REJECTED',
  'APPROVAL_WITHDRAWN', 'APPROVAL_EXPIRED', 'APPROVAL_INVALIDATED', 'APPROVAL_DENIED', 'APPROVAL_OWNER_REJECTED', 'APPROVAL_INTEGRITY_VIOLATION',
  'APPROVAL_INTEGRITY_MISMATCH', 'APPROVAL_RESEND_HALTED', 'APPROVAL_JUSTIFICATION_VIEWED', 'OPS_RECON_FINDING',
  // MERCHANT-OPS-001 §5.1·§5.2
  'MERCHANT_VIEWED', 'MERCHANT_BRN_SEARCHED',
  // MERCHANT-OPS-002 §6(B1-1·B1-2)
  'MERCHANT_BUSINESS_REGISTER', 'MERCHANT_REGISTER', 'MERCHANT_SITE_REGISTER', 'MERCHANT_CONTRACT_REGISTER', 'MERCHANT_AGREEMENT_ADD',
  // MERCHANT-OPS-002 §9.7(B2-1)
  'MERCHANT_CONTRACT_REVISION_REGISTER',
  // MERCHANT-OPS-002 §9.18(B3-1 — 1인 명령 감사 code 2개)
  'MERCHANT_PAYMENT_METHOD_ACTIVATION_REGISTER', 'MERCHANT_SITE_REVIEW_REJECT',
  // TXN-OPS-001 §5.1
  'PAYMENT_VIEWED',
  // ACQ-VIEW-001 §4.6
  'ACQUISITION_FILE_VIEWED',
]

describe('ADMIN-UI-001 Korean labels (display only)', () => {
  it('names statuses, roles and kinds in Korean', () => {
    expect(labelOf('ACTIVE')).toBe('활성')
    expect(labelOf('SUSPENDED')).toBe('정지')
    expect(labelOf('PENDING')).toBe('대기')
    expect(labelOf('EXECUTED')).toBe('실행됨')
    expect(labelOf('EXECUTION_UNKNOWN')).toBe('결과 확인 중')
    expect(labelOf('REJECTED')).toBe('거절')
    expect(labelOf('SECURITY_ADMIN')).toBe('보안 관리자')
    expect(labelOf('APPROVER')).toBe('승인자')
    expect(labelOf('OPERATOR')).toBe('운영자')
    expect(labelOf('VIEWER')).toBe('조회자')
    expect(labelOf('ROLE_GRANT')).toBe('역할 부여')
    expect(labelOf('OPERATOR_CORRECTION_CANCEL')).toBe('운영자 보정 취소')
    expect(labelOf('ACQ_REACQUISITION')).toBe('재매입')
    expect(labelOf('AUTHORIZED')).toBe('승인 완료')
  })

  it('covers every server enum and constant the console shows, and nothing else (no dead labels)', () => {
    expect(SERVER_CODES.filter((code) => !(code in CODE_LABELS))).toEqual([])
    const known = new Set([...SERVER_CODES, ...SERVER_AUDIT_ACTIONS])
    expect(Object.keys(CODE_LABELS).filter((code) => !known.has(code))).toEqual([])
  })

  it('names every server audit action in Korean', () => {
    expect(SERVER_AUDIT_ACTIONS.filter((code) => !(code in AUDIT_ACTION_LABELS))).toEqual([])
    expect(Object.keys(AUDIT_ACTION_LABELS).filter((code) => !SERVER_AUDIT_ACTIONS.includes(code))).toEqual([])
    expect(labelOf('LOGIN_SUCCEEDED')).toBe('로그인 성공')
    expect(labelOf('AUDIT_VIEWED')).toBe('감사 기록 조회')
    expect(labelOf('MERCHANT_VIEWED')).toBe('가맹점 상세 조회')
    expect(labelOf('MERCHANT_BRN_SEARCHED')).toBe('사업자등록번호 검색')
    expect(labelOf('MERCHANT_CONTRACT_REGISTER')).toBe('계약 초안 등록')
    expect(labelOf('MERCHANT_AGREEMENT_ADD')).toBe('서비스 계약 추가')
    expect(labelOf('MERCHANT_CONTRACT_REVISION_REGISTER')).toBe('계약 Revision 등록')
    // C-32: 계약 Revision 승인 상태 표기는 한 곳(상세 표·등록 성공 문구 공통).
    expect(REVISION_AWAITING_APPROVAL).toBe('승인 전')
    expect(labelOf('PAYMENT_VIEWED')).toBe('거래 상세 조회')
  })

  it('MERCHANT-OPS-001: merchant statuses, activation statuses and limit kinds in Korean with semantic tones', () => {
    expect([labelOf('TERMINATED'), statusTone('TERMINATED')]).toEqual(['해지', 'outline'])
    expect([labelOf('UNDER_REVIEW'), statusTone('UNDER_REVIEW')]).toEqual(['심사 중', 'warning'])
    expect([labelOf('APPROVED'), statusTone('APPROVED')]).toEqual(['승인', 'success'])
    expect([labelOf('ENDED'), statusTone('ENDED')]).toEqual(['종료', 'outline'])
    expect(labelOf('UNLIMITED')).toBe('무제한')
    expect(labelOf('SITES')).toBe('Site 지정')
    expect([labelOf('UNAVAILABLE'), statusTone('UNAVAILABLE')]).toEqual(['서비스 불가', 'destructive'])
  })

  it('shows an unknown code as is (never hides it)', () => {
    expect(labelOf('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(labelOf('constructor')).toBe('constructor')
  })

  it('status tones are semantic: success green, waiting amber, failure red, ended gray', () => {
    expect(statusTone('EXECUTED')).toBe('success')
    expect(statusTone('PENDING')).toBe('warning')
    expect(statusTone('REJECTED')).toBe('destructive')
    expect(statusTone('EXPIRED')).toBe('outline')
  })

  it('review m-3: investigation results are not shown as plain success or failure', () => {
    expect([labelOf('UNKNOWN'), statusTone('UNKNOWN')]).toEqual(['결과 확인 중', 'warning'])
    expect([labelOf('FLAGGED'), statusTone('FLAGGED')]).toEqual(['무결성 경보(조사 필요)', 'destructive'])
    expect([labelOf('HALTED'), statusTone('HALTED')]).toEqual(['재전송 중단(조사 필요)', 'warning'])
    expect([labelOf('REFUSED'), statusTone('REFUSED')]).toEqual(['거부', 'destructive'])
  })
})

describe('ADMIN-UI-001 display formats', () => {
  it('shows ISO instants in KST as YYYY-MM-DD HH:mm:ss and leaves unparsable text as is', () => {
    expect(formatKst('2026-10-28T00:00:00Z')).toBe('2026-10-28 09:00:00')
    expect(formatKst('2026-12-31T15:30:05Z')).toBe('2027-01-01 00:30:05')
    expect(formatKst('not-a-date')).toBe('not-a-date')
  })

  it('formats minor-unit amounts with thousands separators (KRW in 원)', () => {
    expect(formatMinor(7000, 'KRW')).toBe('7,000원')
    expect(formatMinor(1234567, 'KRW')).toBe('1,234,567원')
    expect(formatMinor(1050, 'USD')).toBe('1,050 USD(최소 단위)')
  })

  it('review m-2: an unknown currency is never shown as 원', () => {
    expect(formatMinor(7000, null)).toBe('7,000(통화 미상)')
    expect(formatMinor(7000, undefined)).toBe('7,000(통화 미상)')
  })
})
