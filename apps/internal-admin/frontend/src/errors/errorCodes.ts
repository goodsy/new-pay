/**
 * 화면이 구분하는 오류 종류. 서버 응답은 JSON `{error: <고정 code>}`만 신뢰하고 메시지·본문은 화면에 쓰지 않는다.
 * 각 code는 서버가 그 code에 쓰는 HTTP 상태와 함께 올 때만 믿는다(어긋나면 일반 오류 — S1 리뷰 n5).
 */
const STATUS = {
  UNAUTHENTICATED: [401],
  SESSION_REPLACED: [401],
  SESSION_EXPIRED: [401],
  INVALID_CREDENTIALS: [401],
  FORBIDDEN: [403],
  IP_DENIED: [403],
  STEP_UP_FAILED: [403],
  SELF_APPROVAL: [403],
  APPROVER_IS_TARGET: [403],
  CAUSE_PARTICIPANT: [403, 409],
  CREDENTIAL_ISSUER: [403, 409],
  NOT_FOUND: [404],
  RETRY_LATER: [409],
  ALREADY_DECIDED: [409],
  EXPIRED: [409],
  INVALIDATED: [409],
  OWNER_REJECTED: [409],
  DUPLICATE_PENDING: [409],
  INVALID_INPUT: [400],
  BAD_REQUEST: [400],
  ENROLLMENT_CODE_INVALID: [400],
  ENROLLMENT_NOT_APPLICABLE: [400],
  ENROLLMENT_STEP_INVALID: [400],
  PASSWORD_POLICY: [400],
  INTERNAL_INTEGRITY: [500],
  AUDIT_UNAVAILABLE: [503],
} as const satisfies Record<string, readonly number[]>

/** 서버 응답으로 받는 code + 클라이언트 전용(CSRF_MISSING) + 분류용 일반 code. */
export const CONSOLE_ERROR_CODES = [...(Object.keys(STATUS) as (keyof typeof STATUS)[]), 'CSRF_MISSING', 'REQUEST_REJECTED', 'SERVER_ERROR'] as const

export type ConsoleErrorCode = (typeof CONSOLE_ERROR_CODES)[number]

function isServerCode(value: unknown): value is keyof typeof STATUS {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(STATUS, value)
}

function genericFor(status: number): ConsoleErrorCode {
  // S8 리뷰 nit-1: 401은 본문 code와 무관하게 세션 종료로 본다(알려진 401 code — INVALID_CREDENTIALS·SESSION_* — 는 위에서 그대로).
  if (status === 401) return 'UNAUTHENTICATED'
  return status >= 500 ? 'SERVER_ERROR' : 'REQUEST_REJECTED'
}

/**
 * 알려진 서버 code이고 HTTP 상태와 맞으면 그 code, 알려졌지만 상태와 어긋나면 일반 오류, 모르는 문자열·본문 없음이면 HTTP 상태로 분류한다.
 * 업무 거절의 그 밖 code(예: ROLE_NOT_HELD)는 REQUEST_REJECTED로 묶는다(문구는 고정).
 */
export function errorCodeFrom(status: number, body: unknown): ConsoleErrorCode {
  const serverCode = typeof body === 'object' && body !== null ? (body as { error?: unknown }).error : undefined
  if (isServerCode(serverCode)) {
    return (STATUS[serverCode] as readonly number[]).includes(status) ? serverCode : genericFor(status)
  }
  // S7 리뷰 m-1: 표에 없는 문자열 code(업무 거절 등)는 일반 거절 — HTTP 상태로 추정하는 것은 본문 code가 없을 때만.
  if (typeof serverCode === 'string') return genericFor(status)
  if (status === 401) return 'UNAUTHENTICATED'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status === 409) return 'RETRY_LATER'
  if (status >= 500) return 'SERVER_ERROR'
  return 'REQUEST_REJECTED'
}

/** 세션이 끝난 code(로그인 화면으로 돌아간다). */
export const SESSION_ENDING: readonly ConsoleErrorCode[] = ['UNAUTHENTICATED', 'SESSION_REPLACED', 'SESSION_EXPIRED', 'CSRF_MISSING']
