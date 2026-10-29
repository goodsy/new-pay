import type { ConsoleErrorCode } from './errorCodes'

/** 오류 code → 고정 문구(서버 메시지·입력 값은 쓰지 않는다). */
export const ERROR_MESSAGES: Record<ConsoleErrorCode, string> = {
  UNAUTHENTICATED: '로그인이 필요합니다.',
  SESSION_REPLACED: '다른 곳에서 로그인하여 이 화면의 세션이 종료되었습니다.',
  SESSION_EXPIRED: '세션이 만료되었습니다. 다시 로그인해 주세요.',
  INVALID_CREDENTIALS: '로그인 정보가 올바르지 않습니다.',
  FORBIDDEN: '이 작업을 할 권한이 없습니다.',
  IP_DENIED: '허용되지 않은 네트워크에서 접속했습니다.',
  STEP_UP_FAILED: '인증 코드가 올바르지 않습니다. 새 코드로 다시 시도해 주세요.',
  SELF_APPROVAL: '자기가 요청한 건은 승인할 수 없습니다.',
  APPROVER_IS_TARGET: '자기 계정에 대한 요청은 승인할 수 없습니다.',
  CAUSE_PARTICIPANT: '원인이 된 재설정의 요청자·승인자는 이 재활성에 참여할 수 없습니다.',
  CREDENTIAL_ISSUER: '이 계정의 자격을 발급한 관리자는 이 계정의 권한 상승을 요청·승인할 수 없습니다.',
  NOT_FOUND: '요청한 화면이나 대상을 찾을 수 없습니다.',
  RETRY_LATER: '다른 작업과 겹쳤습니다. 잠시 후 다시 시도해 주세요.',
  ALREADY_DECIDED: '이미 처리된 요청입니다.',
  EXPIRED: '유효 기간이 지난 요청입니다.',
  INVALIDATED: '요청 뒤 대상이나 요청자가 바뀌어 요청이 무효가 되었습니다.',
  OWNER_REJECTED: '계정 상태가 맞지 않아 실행되지 않았습니다.',
  DUPLICATE_PENDING: '같은 대상에 같은 종류의 요청이 이미 대기 중입니다.',
  DUPLICATE_UNRESOLVED: '이 결제에 아직 끝나지 않은 보정 취소 승인이 있습니다.',
  TARGET_BUSY: '진행 중인 취소가 있어 지금은 실행할 수 없습니다.',
  PAYMENT_NOT_FOUND: '결제를 찾을 수 없습니다.',
  RATE_LIMITED: '조회가 너무 잦습니다. 잠시 후 다시 시도해 주세요.',
  PAYMENT_UNAVAILABLE: '결제 서비스에 연결할 수 없어 저장하지 않았습니다. 잠시 후 다시 시도해 주세요.',
  INVALID_INPUT: '입력 형식이 올바르지 않습니다.',
  BAD_REQUEST: '입력 형식이 올바르지 않습니다.',
  ENROLLMENT_CODE_INVALID: '등록 코드가 올바르지 않거나 만료되었습니다.',
  ENROLLMENT_NOT_APPLICABLE: '이 등록 코드로는 지금 계정을 등록할 수 없습니다. 관리자에게 문의해 주세요.',
  ENROLLMENT_STEP_INVALID: '등록 단계가 맞지 않거나 시간이 지났습니다. 처음부터 다시 진행해 주세요.',
  PASSWORD_POLICY: '비밀번호 길이 규칙에 맞지 않습니다.',
  INTERNAL_INTEGRITY: '내부 무결성 검사에 실패했습니다. 관리자에게 알려 주세요.',
  AUDIT_UNAVAILABLE: '감사 기록을 남길 수 없어 조회하지 않았습니다. 잠시 후 다시 시도해 주세요.',
  CSRF_MISSING: '보안 토큰이 없어 요청을 보내지 않았습니다. 다시 로그인해 주세요.',
  REQUEST_REJECTED: '요청이 거부되었습니다.',
  SERVER_ERROR: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
}

/** 알 수 없는 예외(네트워크 등)까지 포함해 code로. */
export function codeOf(error: unknown): ConsoleErrorCode {
  if (typeof error === 'object' && error !== null && 'code' in error && typeof (error as { code: unknown }).code === 'string') {
    const code = (error as { code: string }).code
    if (code in ERROR_MESSAGES) return code as ConsoleErrorCode
  }
  return 'SERVER_ERROR'
}
