import { ApiError } from '../api/client'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf } from '../errors/messages'
import { useRemembered, type ScreenMemory } from './screenMemory'

/**
 * 결과 불명 잠금 제출(MERCHANT-OPS-002 §4.2·§5.3 — LOCK-UNIFY-001: 가맹점 등록 단계·사업자 폼·개통 1인 명령이 같이 쓴다).
 * - 결과가 200(결정) 또는 409로 정해질 때까지 같은 submissionId. 결과 불명이면 입력을 잠그고 같은 내용 재전송만 허용한다.
 * - 재전송 중에는 409(IDEMPOTENCY_KEY_REUSED — 결함 신호)만 확정 실패 — 그 밖의 오류(503 MASTER_BUSY·429·403 등)는 앞 요청 결과를 여전히 모르므로 잠금·submissionId 유지.
 * - 상태·보낸 내용은 [ScreenMemory]의 `${key}.status`·`${key}.pending`에 둔다(App 메모리 — 탭 전환·폼 닫기에도 유지, 브라우저 저장소 아님).
 *   `.status`가 busy·unknown이면 세션 경계의 [hasUnresolvedRequest]가 본다.
 */

/** 결과를 확정하지 못한 응답 — 같은 submissionId로 다시 보내 확인한다. */
export const UNKNOWN_CODES: readonly ConsoleErrorCode[] = ['MASTER_OUTCOME_UNKNOWN', 'MASTER_RETRY_SAME_KEY', 'AUDIT_UNAVAILABLE', 'SERVER_ERROR']

export type SubmissionStatus<O> =
  | { kind: 'idle' }
  | { kind: 'busy' }
  | { kind: 'decided'; outcome: O }
  | { kind: 'failed'; code: ConsoleErrorCode }
  | { kind: 'unknown'; code: ConsoleErrorCode }

/** 보낸 제출(재전송은 이 값 그대로 — 같은 submissionId·같은 본문). */
export type PendingSubmission = { submissionId: string }

export type SendResult<O> =
  | { kind: 'decided'; outcome: O }
  | { kind: 'session' }
  | { kind: 'unknown'; code: ConsoleErrorCode }
  | { kind: 'failed'; code: ConsoleErrorCode; error: unknown }

/** 기억 저장소 초기값(매 렌더 같은 참조). */
const IDLE: SubmissionStatus<never> = Object.freeze({ kind: 'idle' })

export const submissionStatusOf = <O>(memory: ScreenMemory, key: string): SubmissionStatus<O> =>
  (memory.read(`${key}.status`) as SubmissionStatus<O> | undefined) ?? IDLE
export const pendingSubmissionOf = <Pd extends PendingSubmission>(memory: ScreenMemory, key: string): Pd | null =>
  (memory.read(`${key}.pending`) as Pd | null | undefined) ?? null
/** 보내는 중이거나 결과 불명(같은 submissionId 재전송만 허용). */
export const isLocked = (status: { kind: string }) => status.kind === 'busy' || status.kind === 'unknown'

/**
 * [pending]을 보내고 결과를 [memory]에 쓴다. [retrying] — 결과 불명으로 잠긴 뒤 같은 submissionId로 다시 보내는 중.
 * 세션 종료 응답은 [onSessionEnded]가 있으면 넘기고 상태를 그대로 둔다(보내는 중 — 세션 경계에서 결과 확인 안내).
 */
export async function sendUnknownLocked<Pd extends PendingSubmission, O>(
  memory: ScreenMemory,
  key: string,
  pending: Pd,
  retrying: boolean,
  call: (pending: Pd) => Promise<O>,
  onSessionEnded?: (code: ConsoleErrorCode) => void,
  releaseOnInvalidInput = false,
): Promise<SendResult<O>> {
  const setPending = (value: Pd | null) => memory.write(`${key}.pending`, value)
  const setStatus = (value: SubmissionStatus<O>) => memory.write(`${key}.status`, value)
  setPending(pending)
  setStatus({ kind: 'busy' })
  try {
    const outcome = await call(pending)
    setPending(null)
    setStatus({ kind: 'decided', outcome })
    return { kind: 'decided', outcome }
  } catch (e) {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) {
      onSessionEnded(code)
      return { kind: 'session' }
    }
    const conflict = e instanceof ApiError && e.status === 409
    // [releaseOnInvalidInput](MERCHANT-FLOW-001 FLOW-2c 리뷰 NIT-B — 기대값 보호 명령 전용): 재전송이 400 INVALID_INPUT(ADMIN 1차 검사 — 이번엔 MASTER 미호출)이면 확정 실패로 잠금을 푼다.
    // 앞 시도가 MASTER에서 처리됐더라도 그 명령의 중복은 서버가 막는다(전이·완료 = 기대 version, 정지·해지 = 기대 사건 수, 접수 생성 = 열린 건 UNIQUE uk_cin_open·expectedKind).
    // 호출자는 상세를 다시 읽어 실제 상태를 보여 준다. 남은 결과 불명 감사 행의 회수 정본은 MASTER 변경 이력이다. 기본값(false)은 기존 동작(잠금 유지) 그대로.
    const confirmedInvalid = releaseOnInvalidInput && retrying && code === 'INVALID_INPUT'
    if (UNKNOWN_CODES.includes(code) || (retrying && !conflict && !confirmedInvalid)) {
      // 결과 불명 — 같은 submissionId·같은 본문을 메모리에 그대로 둔다(잠금).
      setStatus({ kind: 'unknown', code })
      return { kind: 'unknown', code }
    }
    // 확정된 실패(첫 제출의 MASTER 미호출 또는 409 결함 신호) — 다음 제출은 새 submissionId.
    setPending(null)
    setStatus({ kind: 'failed', code })
    return { kind: 'failed', code, error: e }
  }
}

/**
 * 1인 명령 하나의 결과 불명 잠금(상태는 [memory]의 [key]에). [run]은 잠겨 있으면 앞서 보낸 내용을 같은 submissionId로 다시 보내고([payload] 무시),
 * 아니면 [payload]를 새 submissionId로 보낸다. 보내는 중에는 두 번째 요청을 내지 않는다.
 */
export function useUnknownLockedSubmission<P, O>(
  memory: ScreenMemory,
  key: string,
  newSubmissionId: () => string,
  onSessionEnded?: (code: ConsoleErrorCode) => void,
  releaseOnInvalidInput = false,
) {
  type Pending = PendingSubmission & { payload: P }
  const [status] = useRemembered<SubmissionStatus<O>>(memory, `${key}.status`, IDLE)
  const [pending] = useRemembered<Pending | null>(memory, `${key}.pending`, null)
  const run = async (payload: P, send: (submissionId: string, payload: P) => Promise<O>, onDecided?: (outcome: O) => void) => {
    const current = submissionStatusOf<O>(memory, key)
    if (current.kind === 'busy') return
    const sent = pendingSubmissionOf<Pending>(memory, key)
    const retrying = current.kind === 'unknown' && sent !== null
    const next: Pending = retrying ? sent : { submissionId: newSubmissionId(), payload }
    const result = await sendUnknownLocked(memory, key, next, retrying, (p) => send(p.submissionId, p.payload), onSessionEnded, releaseOnInvalidInput)
    if (result.kind === 'decided') onDecided?.(result.outcome)
  }
  /** 잠기지 않은 지난 결과를 지운다(새로 여는 폼이 이전 결정·실패 표시를 이어받지 않게). 잠겨 있으면 그대로. */
  const resetIfUnlocked = () => {
    if (isLocked(submissionStatusOf(memory, key))) return
    memory.remove(`${key}.status`)
    memory.remove(`${key}.pending`)
  }
  return { status, pending, locked: isLocked(status), run, resetIfUnlocked }
}
