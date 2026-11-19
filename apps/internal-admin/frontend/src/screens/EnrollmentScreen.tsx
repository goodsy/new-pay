import { useState, type FormEvent } from 'react'
import type { AdminApi, EnrollStart } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { ErrorNotice, StatusNotice } from '../components/Notice'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'

/** `otpauth://totp/…?secret=XXXX&…`에서 수동 입력용 시크릿(base32)만 읽는다. */
export function setupKeyOf(otpauthUri: string): string | null {
  const query = otpauthUri.split('?')[1]
  if (!otpauthUri.startsWith('otpauth://totp/') || !query) return null
  return new URLSearchParams(query).get('secret')
}

type Step = 'code' | 'password' | 'totp' | 'complete' | 'done'

const RESTART: readonly ConsoleErrorCode[] = ['ENROLLMENT_CODE_INVALID', 'ENROLLMENT_NOT_APPLICABLE', 'ENROLLMENT_STEP_INVALID']

interface Props {
  api: AdminApi
  onDone: () => void
}

/**
 * 등록 코드 흐름(§4.3.6): 코드 → (목적이 요구하면) 비밀번호 → (요구하면) TOTP 설정 키 표시·확인 → 완료. 코드·비밀번호·설정 키는 이 컴포넌트 상태에만 있고
 * 다음 단계로 가거나 끝나면 지운다(저장소·URL 없음). 설정 키는 QR 대신 수동 입력용 문자열로 보여 준다(npm 의존성 추가 없음 — §24 질문 후보).
 * 모양(ADMIN-UI-WORKSPACE-001 WS9): 로그인과 같은 시안 카드 — section.card > .cardtop h2 → .cardbody(단계 안내 .subtle·오류·단계 form). 설정 키는 한 곳(.notice + 본문 글자 token)에만.
 */
export function EnrollmentScreen({ api, onDone }: Props) {
  const [step, setStep] = useState<Step>('code')
  const [plan, setPlan] = useState<EnrollStart | null>(null)
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [setupKey, setSetupKey] = useState<string | null>(null)
  const [totp, setTotp] = useState('')
  const [error, setError] = useState<ConsoleErrorCode | null>(null)

  async function run(action: () => Promise<void>) {
    setError(null)
    try {
      await action()
    } catch (e) {
      const failure = codeOf(e)
      setError(failure)
      // S7 리뷰 m-3: 코드 무효·적용 불가·단계 오류면 서버도 진행 상태를 버렸다 — 처음 단계로 돌아가고 설정 키·계획·입력을 지운다.
      if (RESTART.includes(failure)) {
        setStep('code')
        setPlan(null)
        setSetupKey(null)
        setTotp('')
        setPassword('')
      }
    }
  }

  async function afterPassword(next: EnrollStart) {
    if (next.needsTotp) {
      const { otpauthUri } = await api.enrollTotp()
      setSetupKey(setupKeyOf(otpauthUri))
      setStep('totp')
    } else {
      setStep('complete')
    }
  }

  const start = (event: FormEvent) => {
    event.preventDefault()
    return run(async () => {
      await api.csrf()
      const next = await api.enrollStart(code)
      setCode('')
      setPlan(next)
      if (next.needsPassword) setStep('password')
      else await afterPassword(next)
    })
  }

  const providePassword = (event: FormEvent) => {
    event.preventDefault()
    return run(async () => {
      try {
        await api.enrollPassword(password)
      } finally {
        setPassword('')
      }
      if (plan) await afterPassword(plan)
    })
  }

  const complete = (event: FormEvent) => {
    event.preventDefault()
    return run(async () => {
      await api.enrollComplete(plan?.needsTotp ? totp : undefined)
      setTotp('')
      setSetupKey(null)
      setStep('done')
    })
  }

  return (
    <section className="card">
      <div className="cardtop">
        <h2>계정 등록</h2>
      </div>
      <div className="cardbody">
        <p className="subtle">
          {step === 'code' && '보안 관리자에게 따로 받은 등록 코드를 입력하세요.'}
          {step === 'password' && '새로 쓸 비밀번호를 정하세요.'}
          {step === 'totp' && 'OTP 앱에 설정 키를 등록한 뒤, 앱의 6자리 코드를 입력하세요.'}
          {step === 'complete' && '마지막 단계입니다. 등록 완료를 눌러 주세요.'}
          {step === 'done' && '등록이 끝났습니다.'}
        </p>
        {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
        {step === 'code' && (
          <form onSubmit={start} className="flex flex-col gap-4">
            <Label>
              <span>등록 코드</span>
              <Input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" required className="font-mono" />
            </Label>
            <button type="submit" className="btn primary w-full">시작</button>
          </form>
        )}
        {step === 'password' && (
          <form onSubmit={providePassword} className="flex flex-col gap-4">
            <Label>
              <span>새 비밀번호</span>
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
            </Label>
            <button type="submit" className="btn primary w-full">비밀번호 설정</button>
          </form>
        )}
        {(step === 'totp' || step === 'complete') && (
          <form onSubmit={complete} className="flex flex-col gap-4">
            {step === 'totp' && (
              <>
                <p className="subtle">인증 앱에 아래 설정 키를 직접 입력하세요(이 화면에서 한 번만 보입니다).</p>
                <p className="notice text-foreground text-center font-mono text-sm tracking-wider break-all" translate="no">{setupKey}</p>
                <Label>
                  <span>인증 코드(6자리)</span>
                  <Input inputMode="numeric" maxLength={6} value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" required className="font-mono tracking-widest" />
                </Label>
              </>
            )}
            <button type="submit" className="btn primary w-full">등록 완료</button>
          </form>
        )}
        {step === 'done' && (
          <>
            <StatusNotice tone="success">등록이 끝났습니다. 새 자격으로 로그인해 주세요.</StatusNotice>
            <button type="button" onClick={onDone} className="btn primary w-full">
              로그인 화면으로
            </button>
          </>
        )}
      </div>
    </section>
  )
}
