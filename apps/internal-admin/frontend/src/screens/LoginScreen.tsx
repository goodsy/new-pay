import { useState, type FormEvent } from 'react'
import type { AdminApi, Me } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { CircleAlertIcon, InfoIcon } from '../components/icons'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { Field } from '../components/DataBits'

interface Props {
  api: AdminApi
  onAuthenticated: (me: Me) => void
  onEnroll: () => void
  /** 앞 화면에서 세션이 끝난 이유(SESSION_REPLACED 등) — 안내만. */
  notice?: ConsoleErrorCode
  /** 세션이 끝날 때 결과 확인이 필요한 가맹점 등록·Revision 요청이 있었다(MERCHANT-OPS-002 B2-1 리뷰 n-a — 한 번 안내). */
  unresolvedNotice?: boolean
}

/**
 * 2단계 로그인(§4.3.2): CSRF 세션 → 비밀번호 → TOTP. 입력 값은 컴포넌트 상태에만, 실패하면 비밀 입력을 지운다.
 * 모양(ADMIN-UI-WORKSPACE-001 WS9 — 참고 시안에 로그인 화면이 없어 시안 카드로 구성): section.card > .cardtop h2 → .cardbody(단계 안내 .subtle·알림·form) → .cardnote(등록 코드 .btn).
 * 브랜드는 로그인 전 상단 바의 시안 `.brand`(AppLayout). 스피너 대신 버튼 아래 보이는 진행 글자(aria-live — 버튼과 한 묶음 div 안 block span이라 비어 있으면 높이 0, form gap을 더하지 않음). 입력 속성·문구·호출 순서는 그대로.
 */
export function LoginScreen({ api, onAuthenticated, onEnroll, notice, unresolvedNotice = false }: Props) {
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
    <section className="card">
      <div className="cardtop">
        <h2>로그인</h2>
      </div>
      <div className="cardbody">
        <p className="subtle">{step === 'password' ? '1단계 — 관리자 로그인 이름과 비밀번호를 입력하세요.' : '2단계 — OTP 앱의 6자리 코드를 입력하세요.'}</p>
        {notice && (
          <Alert role="status" variant="warning">
            <InfoIcon aria-hidden="true" />
            <AlertDescription>{ERROR_MESSAGES[notice]}</AlertDescription>
          </Alert>
        )}
        {unresolvedNotice && (
          <Alert role="note" variant="warning" aria-label="결과 확인 필요">
            <InfoIcon aria-hidden="true" />
            <AlertDescription>결과 확인이 필요한 요청이 있습니다 — 가맹점 조회로 확인하세요</AlertDescription>
          </Alert>
        )}
        {error && (
          <Alert role="alert" variant="destructive">
            <CircleAlertIcon aria-hidden="true" />
            <AlertDescription>{ERROR_MESSAGES[error]}</AlertDescription>
          </Alert>
        )}
        {step === 'password' ? (
          <form onSubmit={submitPassword} className="flex flex-col gap-4">
            <Label>
              <span>로그인 이름</span>
              <Input value={loginName} onChange={(e) => setLoginName(e.target.value)} autoComplete="username" required />
            </Label>
            <Label>
              <span>비밀번호</span>
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
            </Label>
            <div>
              <button type="submit" disabled={busy} aria-busy={busy} className="btn primary w-full">
                다음
              </button>
              <span className="subtle block" aria-live="polite">{busy ? '확인 중…' : ''}</span>
            </div>
          </form>
        ) : (
          <form onSubmit={submitTotp} className="flex flex-col gap-4">
            <Field label="인증 코드(6자리)" hint="인증 앱에 지금 보이는 코드를 입력하세요. 틀리면 비밀번호 단계부터 다시 합니다.">
              {(describedBy) => (
                <Input inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required aria-describedby={describedBy} className="text-center font-mono text-lg tracking-[0.5em]" />
              )}
            </Field>
            <div>
              <button type="submit" disabled={busy} aria-busy={busy} className="btn primary w-full">
                로그인
              </button>
              <span className="subtle block" aria-live="polite">{busy ? '확인 중…' : ''}</span>
            </div>
          </form>
        )}
      </div>
      <div className="cardnote items-center">
        <button type="button" className="btn" onClick={onEnroll}>
          등록 코드로 계정 등록
        </button>
      </div>
    </section>
  )
}
