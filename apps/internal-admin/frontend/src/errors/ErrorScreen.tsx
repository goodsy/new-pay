import type { ConsoleErrorCode } from './errorCodes'
import { ERROR_MESSAGES } from './messages'

type Action = 'login' | 'retry' | 'none'

const ACTIONS: Partial<Record<ConsoleErrorCode, Action>> = {
  UNAUTHENTICATED: 'login',
  SESSION_REPLACED: 'login',
  SESSION_EXPIRED: 'login',
  CSRF_MISSING: 'login',
  RETRY_LATER: 'retry',
  SERVER_ERROR: 'retry',
  AUDIT_UNAVAILABLE: 'retry',
}

interface Props {
  code: ConsoleErrorCode
  onLogin: () => void
  onRetry: () => void
}

/** 오류 화면. 고정 문구만 보여 주고 서버 메시지·입력 값은 쓰지 않는다. */
export function ErrorScreen({ code, onLogin, onRetry }: Props) {
  const action = ACTIONS[code] ?? 'none'
  return (
    <section className="error-screen">
      <p role="alert">{ERROR_MESSAGES[code]}</p>
      {action === 'login' && (
        <button type="button" onClick={onLogin}>
          로그인 화면으로
        </button>
      )}
      {action === 'retry' && (
        <button type="button" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </section>
  )
}
