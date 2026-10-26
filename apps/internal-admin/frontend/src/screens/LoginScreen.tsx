import { useState, type FormEvent } from 'react'
import type { AdminApi, Me } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

interface Props {
  api: AdminApi
  onAuthenticated: (me: Me) => void
  onEnroll: () => void
  /** 앞 화면에서 세션이 끝난 이유(SESSION_REPLACED 등) — 안내만. */
  notice?: ConsoleErrorCode
}

/** 2단계 로그인(§4.3.2): CSRF 세션 → 비밀번호 → TOTP. 입력 값은 컴포넌트 상태에만, 실패하면 비밀 입력을 지운다. */
export function LoginScreen({ api, onAuthenticated, onEnroll, notice }: Props) {
  const [step, setStep] = useState<'password' | 'totp'>('password')
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [busy, setBusy] = useState(false)

  async function submitPassword(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.csrf()
      await api.password(loginName, password)
      setStep('totp')
    } catch (e) {
      setError(codeOf(e))
    } finally {
      setPassword('')
      setBusy(false)
    }
  }

  async function submitTotp(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onAuthenticated(await api.totp(code))
    } catch (e) {
      setError(codeOf(e))
      setStep('password')
    } finally {
      setCode('')
      setBusy(false)
    }
  }

  return (
    <section className="login">
      <h2>로그인</h2>
      {notice && <p role="status">{ERROR_MESSAGES[notice]}</p>}
      {error && <p role="alert">{ERROR_MESSAGES[error]}</p>}
      {step === 'password' ? (
        <form onSubmit={submitPassword}>
          <label>
            로그인 이름
            <input value={loginName} onChange={(e) => setLoginName(e.target.value)} autoComplete="username" required />
          </label>
          <label>
            비밀번호
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </label>
          <button type="submit" disabled={busy}>
            다음
          </button>
        </form>
      ) : (
        <form onSubmit={submitTotp}>
          <label>
            인증 코드(6자리)
            <input inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required />
          </label>
          <button type="submit" disabled={busy}>
            로그인
          </button>
        </form>
      )}
      <button type="button" className="link" onClick={onEnroll}>
        등록 코드로 계정 등록
      </button>
    </section>
  )
}
