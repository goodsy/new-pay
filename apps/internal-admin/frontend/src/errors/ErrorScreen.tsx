import type { ConsoleErrorCode } from './errorCodes'
import { ERROR_MESSAGES } from './messages'
import { ErrorNotice } from '../components/Notice'
import { Button } from '../components/ui/button'

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
    <section className="flex flex-col items-start gap-3">
      <ErrorNotice>{ERROR_MESSAGES[code]}</ErrorNotice>
      {action === 'login' && (
        <Button type="button" onClick={onLogin}>
          로그인 화면으로
        </Button>
      )}
      {action === 'retry' && (
        <Button type="button" variant="outline" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </section>
  )
}
