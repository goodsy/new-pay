/**
 * 서버 code → 화면 한국어 이름(ADMIN-UI-001 "직관적" 요구 — 표시만). 서버 판정·요청 값·행 aria-label은 원래 code를 그대로 쓴다.
 * 출처(서버 enum·상수 — labels.test.ts가 집합 전부를 확인): internal-admin domain enum AccountStatus·AdminRole·ApprovalStatus·ApprovalKind·
 * OperatorCancelPurposeKind·ReconciliationFindingKind·AuditActorKind, 감사 행위(action)·결과(resultCode) 상수, payment-api PaymentStatus,
 * master-api 가맹점 enum(MERCHANT-OPS-001 가맹점 조회 표시).
 * 모르는 code는 원래 code를 그대로 보인다(숨기지 않는다). 같은 code는 도메인이 달라도 같은 뜻으로만 쓰인다.
 */

/** 감사 행위(action) — AdminLoginService·AuthenticationFailureService·AdminAccountCommandService·EnrollmentService·OneShotCommandService·ApprovalService·
 * OwnerServiceApprovalService·OwnerOutcomeApplier·OperatorCancelJustificationService·OperatorDecisionReconciliationService·UnmaskService·
 * AdminAuditQueryService·AdminSecurityEventService. */
export const AUDIT_ACTION_LABELS: Readonly<Record<string, string>> = {
  LOGIN_PASSWORD: '로그인 — 비밀번호 확인',
  LOGIN_TOTP: '로그인 — 인증 코드 확인',
  LOGIN_SUCCEEDED: '로그인 성공',
  LOGOUT: '로그아웃',
  AUTHENTICATION_FAILED: '인증 실패',
  ACCOUNT_LOCKED: '계정 잠김',
  ACCOUNT_CREATED: '계정 생성',
  ACCOUNT_SUSPENDED: '계정 정지',
  ROLE_REVOKED: '역할 회수',
  ENROLLMENT_CODE_ISSUED: '등록 코드 발급',
  ENROLLMENT_COMPLETED: '계정 등록 완료',
  BOOTSTRAP: '초기 관리자 설정',
  RECOVERY: '관리자 복구',
  RECOVERY_EPISODE_CLOSED: '복구 회차 종료',
  AUDIT_VIEWED: '감사 기록 조회',
  IP_DENIED: '허용되지 않은 IP 접근',
  UNMASK_REQUESTED: '마스킹 해제 요청',
  APPROVAL_REQUESTED: '승인 요청',
  APPROVAL_EXECUTING: '승인 — 실행 요청',
  APPROVAL_EXECUTED: '승인 — 실행됨',
  APPROVAL_EXECUTION_UNKNOWN: '승인 — 실행 결과 확인 중',
  APPROVAL_REJECTED: '승인 거절',
  APPROVAL_WITHDRAWN: '승인 요청 철회',
  APPROVAL_EXPIRED: '승인 요청 만료',
  APPROVAL_INVALIDATED: '승인 요청 무효',
  APPROVAL_DENIED: '승인 처리 거부',
  APPROVAL_OWNER_REJECTED: '소유 서비스 거절',
  APPROVAL_INTEGRITY_VIOLATION: '승인 무결성 위반',
  APPROVAL_INTEGRITY_MISMATCH: '결정 내용 불일치',
  APPROVAL_RESEND_HALTED: '재전송 중단',
  APPROVAL_JUSTIFICATION_VIEWED: '근거·사유 조회',
  OPS_RECON_FINDING: '대사 발견 기록',
  // MERCHANT-OPS-001 §5.1·§5.2(admin.merchantops)
  MERCHANT_VIEWED: '가맹점 상세 조회',
  MERCHANT_BRN_SEARCHED: '사업자등록번호 검색',
  // MERCHANT-OPS-002 §6(B1-1 — C-5 명령별 code)
  MERCHANT_BUSINESS_REGISTER: '사업자 등록',
  MERCHANT_REGISTER: '가맹점 등록',
  MERCHANT_SITE_REGISTER: 'Site 등록',
  MERCHANT_CONTRACT_REGISTER: '계약 초안 등록',
  MERCHANT_AGREEMENT_ADD: '서비스 계약 추가',
  // TXN-OPS-001 §5.1(admin.paymentops)
  PAYMENT_VIEWED: '거래 상세 조회',
  // ACQ-VIEW-001 §4.6(admin.financialops — D-ACQV-001)
  ACQUISITION_FILE_VIEWED: '매입 파일 조회',
}

export const CODE_LABELS: Readonly<Record<string, string>> = {
  // 계정 상태(AccountStatus)
  PENDING_ENROLLMENT: '등록 대기',
  ACTIVE: '활성',
  LOCKED: '잠김',
  SUSPENDED: '정지',
  // 역할(AdminRole)
  VIEWER: '조회자',
  OPERATOR: '운영자',
  APPROVER: '승인자',
  SECURITY_ADMIN: '보안 관리자',
  UNMASK: '마스킹 해제',
  // 승인 상태(ApprovalStatus) — 감사 결과에도 같은 code가 같은 뜻으로 쓰인다
  PENDING: '대기',
  EXECUTING: '실행 중',
  EXECUTION_UNKNOWN: '결과 확인 중',
  EXECUTED: '실행됨',
  REJECTED: '거절',
  WITHDRAWN: '철회',
  EXPIRED: '만료',
  INVALIDATED: '무효',
  OWNER_REJECTED: '소유 서비스 거절',
  // 승인 종류(ApprovalKind)
  ROLE_GRANT: '역할 부여',
  ACCOUNT_UNLOCK: '잠금 해제',
  ACCOUNT_REACTIVATE: '계정 재활성',
  TOTP_REENROLL: '인증 앱 재등록',
  PASSWORD_RESET: '비밀번호 재설정',
  ROLE_REACTIVATE: '역할 재활성',
  OPERATOR_CORRECTION_CANCEL: '운영자 보정 취소',
  ACQ_REACQUISITION: '재매입',
  // 보정 취소 목적(OperatorCancelPurposeKind)
  INCIDENT_OR_RECON_CORRECTION: '사고·대사 보정',
  MERCHANT_TERMINATED_OBLIGATION: '해지 가맹점 의무 이행',
  // 대사 발견 종류(ReconciliationFindingKind) — OPS_RECON_FINDING 감사 결과로도 쓰인다
  ORPHAN_DECISION: '승인 없는 결정',
  DECISION_FOR_UNAPPROVED: '미승인 건 결정',
  FINGERPRINT_MISMATCH: '요청 지문 불일치',
  STATE_DIVERGENCE: '상태 불일치',
  UNRESOLVED_WITH_DECISION: '결정 있는 미해결',
  MISSING_DECISION: '결정 누락',
  // 감사 결과(resultCode) — 위 승인 상태와 겹치는 code는 위 이름을 쓴다
  SUCCESS: '성공',
  OK: '성공',
  DENIED: '거부',
  REFUSED: '거부',
  FORBIDDEN: '권한 없음',
  FAILED: '실패',
  FAILURE: '실패',
  UNKNOWN: '결과 확인 중',
  FLAGGED: '무결성 경보(조사 필요)',
  HALTED: '재전송 중단(조사 필요)',
  COUNTED: '실패 횟수 반영',
  NOT_COUNTED: '실패 횟수 미반영',
  // 감사 행위자 종류(AuditActorKind)
  ADMIN: '관리자',
  ANONYMOUS: '익명',
  SYSTEM_BOOTSTRAP: '시스템(초기 설정)',
  SYSTEM_RECOVERY: '시스템(복구)',
  SYSTEM_JOB: '시스템 작업',
  // 결제 상태(payment-api PaymentStatus — 대상 조회 표시)
  REQUESTED: '요청됨',
  AUTHORIZING: '승인 진행 중',
  AUTHORIZED: '승인 완료',
  // 가맹점 조회(MERCHANT-OPS-001 — master-api Lifecycle·SiteReviewStatus·ActivationStatus·SiteAssignmentRole/Status·ChannelType·계약 Scope 종류·
  // LimitScopeKind·한도 값 종류·ServiceKind·PaymentMethod·EffectBasis). ACTIVE·SUSPENDED·PENDING·REJECTED는 위 이름과 같은 뜻.
  TERMINATED: '해지',
  APPROVED: '승인',
  UNDER_REVIEW: '심사 중',
  ENDED: '종료',
  PRIMARY: '대표',
  ADDITIONAL: '추가',
  WEB: '웹',
  APP: '앱',
  MERCHANT: '가맹점 전체',
  SITES: 'Site 지정',
  CSA_DEFAULT: 'CSA 기본',
  MID_OVERRIDE: 'MID 개별',
  UNLIMITED: '무제한',
  LIMITED: '한도 있음',
  CARD_PG_PAYMENT: '카드 PG 결제',
  CARD: '카드',
  ACK: '수신 확인',
  LEASE_EXPIRY: '임대 만료',
  // 가맹점 등록 감사 결과(MERCHANT-OPS-002 §6 — REQUESTED·OK·REJECTED·FORBIDDEN·INVALID_INPUT·RATE_LIMITED는 위·아래 기존 이름)
  KEY_REUSED: '요청키 재사용',
  BUSY: '처리 대기 없음(바쁨)',
  OUTCOME_UNKNOWN: '결과 불명',
  // 가맹점 조회 감사 결과(MERCHANT-OPS-001 §5.1·§5.2)
  NOT_FOUND: '찾지 못함',
  INVALID_INPUT: '입력 형식 오류',
  RATE_LIMITED: '속도 제한',
  UNAVAILABLE: '서비스 불가',
  ...AUDIT_ACTION_LABELS,
}

/** code의 한국어 이름. 모르는 code는 code 그대로. */
export function labelOf(code: string): string {
  return Object.prototype.hasOwnProperty.call(CODE_LABELS, code) ? CODE_LABELS[code]! : code
}
