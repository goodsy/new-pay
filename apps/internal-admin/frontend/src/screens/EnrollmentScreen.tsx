import { useState, type FormEvent } from 'react'
import type { AdminApi, EnrollStart } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

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
    <section className="enrollment">
      <h2>계정 등록</h2>
      {error && <p role="alert">{ERROR_MESSAGES[error]}</p>}
      {step === 'code' && (
        <form onSubmit={start}>
          <label>
            등록 코드
            <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" required />
          </label>
          <button type="submit">시작</button>
        </form>
      )}
      {step === 'password' && (
        <form onSubmit={providePassword}>
          <label>
            새 비밀번호
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
          </label>
          <button type="submit">비밀번호 설정</button>
        </form>
      )}
      {(step === 'totp' || step === 'complete') && (
        <form onSubmit={complete}>
          {step === 'totp' && (
            <>
              <p>인증 앱에 아래 설정 키를 직접 입력하세요(이 화면에서 한 번만 보입니다).</p>
              <p className="code" translate="no">{setupKey}</p>
              <label>
                인증 코드(6자리)
                <input inputMode="numeric" maxLength={6} value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" required />
              </label>
            </>
          )}
          <button type="submit">등록 완료</button>
        </form>
      )}
      {step === 'done' && (
        <>
          <p role="status">등록이 끝났습니다. 새 자격으로 로그인해 주세요.</p>
          <button type="button" onClick={onDone}>
            로그인 화면으로
          </button>
        </>
      )}
    </section>
  )
}
