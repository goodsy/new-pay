import { useState, type FormEvent } from 'react'
import type { AdminApi, Me } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { CircleAlertIcon, InfoIcon } from '../components/icons'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { Field, Spinner } from '../components/DataBits'
import { Logo } from '../components/Logo'

interface Props {
  api: AdminApi
  onAuthenticated: (me: Me) => void
  onEnroll: () => void
  /** 앞 화면에서 세션이 끝난 이유(SESSION_REPLACED 등) — 안내만. */
  notice?: ConsoleErrorCode
  /** 세션이 끝날 때 결과 확인이 필요한 가맹점 등록·Revision 요청이 있었다(MERCHANT-OPS-002 B2-1 리뷰 n-a — 한 번 안내). */
  unresolvedNotice?: boolean
}

/** 2단계 로그인(§4.3.2): CSRF 세션 → 비밀번호 → TOTP. 입력 값은 컴포넌트 상태에만, 실패하면 비밀 입력을 지운다. */
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
    <Card as="section">
      <CardHeader className="justify-items-center text-center">
        <Logo className="mb-2 size-10 text-sm" />
        <h2 className="text-xl font-bold tracking-tight">로그인</h2>
        <CardDescription>
          {step === 'password' ? '1단계 — 관리자 로그인 이름과 비밀번호를 입력하세요.' : '2단계 — OTP 앱의 6자리 코드를 입력하세요.'}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
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
            <Button type="submit" disabled={busy} aria-busy={busy} className="w-full">
              <Spinner active={busy} />
              다음
            </Button>
          </form>
        ) : (
          <form onSubmit={submitTotp} className="flex flex-col gap-4">
            <Field label="인증 코드(6자리)" hint="인증 앱에 지금 보이는 코드를 입력하세요. 틀리면 비밀번호 단계부터 다시 합니다.">
              {(describedBy) => (
                <Input inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required aria-describedby={describedBy} className="text-center font-mono text-lg tracking-[0.5em]" />
              )}
            </Field>
            <Button type="submit" disabled={busy} aria-busy={busy} className="w-full">
              <Spinner active={busy} />
              로그인
            </Button>
          </form>
        )}
      </CardContent>
      <CardFooter className="justify-center">
        <Button type="button" variant="link" size="sm" onClick={onEnroll}>
          등록 코드로 계정 등록
        </Button>
      </CardFooter>
    </Card>
  )
}
