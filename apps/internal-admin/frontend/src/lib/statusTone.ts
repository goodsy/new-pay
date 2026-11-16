import type { BadgeVariant } from '../components/ui/badge'

/**
 * 상태·결과 code → Badge 색(표시만 — 판정·문구는 바꾸지 않는다). 성공 초록, 대기·진행·확인 중·조사 필요(재전송 중단) 주황, 거절·거부·실패·정지·경보 빨강,
 * 종료(만료·철회·무효) 회색. 모르는 값은 secondary. 서버 enum·감사 결과에 있는 code만 둔다(labels.test.ts).
 */
const TONES: Record<string, BadgeVariant> = {
  ACTIVE: 'success', EXECUTED: 'success', SUCCESS: 'success', OK: 'success', AUTHORIZED: 'success',
  PENDING: 'warning', PENDING_ENROLLMENT: 'warning', EXECUTING: 'warning', EXECUTION_UNKNOWN: 'warning', LOCKED: 'warning', UNKNOWN: 'warning',
  HALTED: 'warning', REQUESTED: 'warning', AUTHORIZING: 'warning', COUNTED: 'warning',
  SUSPENDED: 'destructive', REJECTED: 'destructive', OWNER_REJECTED: 'destructive', FAILED: 'destructive', FAILURE: 'destructive',
  DENIED: 'destructive', REFUSED: 'destructive', FORBIDDEN: 'destructive', FLAGGED: 'destructive',
  EXPIRED: 'outline', WITHDRAWN: 'outline', INVALIDATED: 'outline',
  // MERCHANT-OPS-001: 심사 승인 초록, 심사 중 주황, 해지·종료 회색, 감사 결과 서비스 불가·속도 제한 빨강/주황.
  APPROVED: 'success', UNDER_REVIEW: 'warning', TERMINATED: 'outline', ENDED: 'outline', UNAVAILABLE: 'destructive', RATE_LIMITED: 'warning',
}

export function statusTone(code: string): BadgeVariant {
  return TONES[code] ?? 'secondary'
}

/**
 * 참고 시안 `.tag` 색 class(ADMIN-UI-WORKSPACE-001 — 시안 구조로 옮긴 화면용). 의미는 [statusTone]과 같다:
 * 성공 green, 대기·진행 orange, 거절·실패·정지 red, 정보 blue, 종료·모름은 기본 회색(class 없음).
 */
const TAG_CLASS: Record<BadgeVariant, string> = { success: 'green', warning: 'orange', destructive: 'red', default: 'blue', secondary: '', outline: '' }

export function tagClass(code: string): string {
  return toneTagClass(statusTone(code))
}

/** 화면 고유 이름표(색 tone을 직접 정한 표 — 예: 매입 현황 3축 상태)의 tone → 시안 `.tag` class. 의미는 [tagClass]와 같다. */
export function toneTagClass(tone: BadgeVariant): string {
  const color = TAG_CLASS[tone]
  return color === '' ? 'tag' : `tag ${color}`
}
