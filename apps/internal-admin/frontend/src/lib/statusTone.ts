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
}

export function statusTone(code: string): BadgeVariant {
  return TONES[code] ?? 'secondary'
}
