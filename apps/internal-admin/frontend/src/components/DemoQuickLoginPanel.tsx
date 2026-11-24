import { useState } from 'react'
import type { AdminApi, Me } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { labelOf } from '../lib/labels'
import { useDemoQuickLoginAccounts } from '../lib/useDemoQuickLoginAccounts'
import { CircleAlertIcon, InfoIcon } from './icons'
import { Alert, AlertDescription } from './ui/alert'

/**
 * 데모 간편 로그인 카드(ADMIN-DEMO-LOGIN-001 §7 — D-ADMIN-AUTH-DEMO-001, Demo 전용 임시 기능). 서버 옵션 조회가 계정 목록을 돌려줄 때만 그린다
 * (404·오류·빈 목록·메서드 없음이면 아무것도 그리지 않는다 — 일반 로그인 화면 그대로). 버튼 → CSRF 세션 → 간편 로그인 → 일반 로그인 성공과 같은 [onAuthenticated].
 * 켜짐 판정·권한은 서버. 비밀번호·OTP 입력이 없다. 브라우저 저장소를 쓰지 않는다.
 */
export function DemoQuickLoginPanel({ api, onAuthenticated }: { api: AdminApi; onAuthenticated: (me: Me) => void }) {
  const accounts = useDemoQuickLoginAccounts(api)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [busy, setBusy] = useState(false)

  if (accounts.length === 0 || !api.demoQuickLogin) return null

  async function signIn(loginName: string) {
    setBusy(true)
    setError(null)
    try {
      await api.csrf()
      onAuthenticated(await api.demoQuickLogin!(loginName))
    } catch (e) {
      setError(codeOf(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card mb-4" aria-labelledby="demo-quick-login-title">
      <div className="cardtop">
        <h2 id="demo-quick-login-title">데모 간편 로그인</h2>
      </div>
      <div className="cardbody">
        <Alert role="note" variant="warning" aria-label="데모 전용">
          <InfoIcon aria-hidden="true" />
          <AlertDescription>데모 전용 — 비밀번호·OTP 없이 고른 계정으로 들어갑니다. 이 세션의 승인·거절도 인증 코드 없이 진행되며 감사에 남습니다.</AlertDescription>
        </Alert>
        {error && (
          <Alert role="alert" variant="destructive">
            <CircleAlertIcon aria-hidden="true" />
            <AlertDescription>{ERROR_MESSAGES[error]}</AlertDescription>
          </Alert>
        )}
        <div className="flex flex-col gap-2" role="group" aria-label="데모 계정">
          {accounts.map((account) => {
            const roles = account.roles.length === 0 ? '역할 없음' : account.roles.map(labelOf).join(', ')
            return (
              <button
                key={account.loginName}
                type="button"
                className="btn w-full"
                disabled={busy || !account.available}
                aria-busy={busy}
                onClick={() => void signIn(account.loginName)}
              >
                {`${account.displayName} · ${roles}${account.available ? '' : ' (사용 불가)'}`}
              </button>
            )
          })}
        </div>
        <span className="subtle block" aria-live="polite">{busy ? '들어가는 중…' : ''}</span>
      </div>
    </section>
  )
}
