import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ApprovalView } from '../api/adminApi'
import { OneTimeCode } from '../components/OneTimeCode'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

const TOTP = /^[0-9]{6}$/

interface Props {
  api: AdminApi
  view: 'pending' | 'mine'
  /** 승인·거절 버튼 표시(APPROVER — 표시만, 권한 판정은 서버). */
  canDecide: boolean
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

function Row({ item, canDecide, mine, onApprove, onReject, onWithdraw }: {
  item: ApprovalView
  canDecide: boolean
  mine: boolean
  onApprove: (id: string, totp: string) => Promise<void>
  onReject: (id: string, totp: string) => Promise<void>
  onWithdraw: (id: string) => Promise<void>
}) {
  const [totp, setTotp] = useState('')
  const valid = TOTP.test(totp)
  const decide = async (action: (id: string, totp: string) => Promise<void>) => {
    const code = totp
    setTotp('')
    await action(item.approvalId, code)
  }
  return (
    <tr aria-label={`${item.kind} ${item.targetActorId} ${item.status}`}>
      <td>{item.kind}</td>
      <td>{item.grantedRole ?? item.causeApprovalId ?? ''}</td>
      <td>{item.targetActorId}</td>
      <td>{item.requestedByDisplayName}</td>
      <td>{item.status}</td>
      <td>
        {canDecide && item.status === 'PENDING' && (
          <>
            <label>
              인증 코드(6자리)
              <input inputMode="numeric" maxLength={6} value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" />
            </label>
            <button type="button" disabled={!valid} onClick={() => decide(onApprove)}>
              승인
            </button>
            <button type="button" disabled={!valid} onClick={() => decide(onReject)}>
              거절
            </button>
          </>
        )}
        {mine && item.status === 'PENDING' && (
          <button type="button" onClick={() => onWithdraw(item.approvalId)}>
            철회
          </button>
        )}
      </td>
    </tr>
  )
}

/** 승인함(PENDING — 승인·거절 + 직전 TOTP)과 내 요청(철회). 재설정 승인 응답의 코드는 [OneTimeCode]로 1회만 보이고 닫으면 상태에서 지운다. */
export function ApprovalsScreen({ api, view, canDecide, onSessionEnded }: Props) {
  const [items, setItems] = useState<ApprovalView[]>([])
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [oneTimeCode, setOneTimeCode] = useState<string | null>(null)

  const fail = useCallback((e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError(code)
  }, [onSessionEnded])

  const load = useCallback(async () => {
    try {
      setItems((await api.approvals(view)).approvals)
    } catch (e) {
      fail(e)
    }
  }, [api, view, fail])

  useEffect(() => {
    void load()
  }, [load])

  const act = async (action: () => Promise<{ enrollmentCode?: string } | object>) => {
    setError(null)
    try {
      const result = await action()
      if ('enrollmentCode' in result && typeof result.enrollmentCode === 'string') setOneTimeCode(result.enrollmentCode)
      await load()
    } catch (e) {
      fail(e)
    }
  }

  return (
    <section>
      <h2>{view === 'pending' ? '승인함' : '내 요청'}</h2>
      {error && <p role="alert">{ERROR_MESSAGES[error]}</p>}
      {oneTimeCode && <OneTimeCode code={oneTimeCode} onClose={() => setOneTimeCode(null)} />}
      <table>
        <thead>
          <tr>
            <th>종류</th>
            <th>역할·원인</th>
            <th>대상</th>
            <th>요청자</th>
            <th>상태</th>
            <th>작업</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <Row
              key={item.approvalId}
              item={item}
              canDecide={canDecide && view === 'pending'}
              mine={view === 'mine'}
              onApprove={(id, totp) => act(() => api.approve(id, totp))}
              onReject={(id, totp) => act(() => api.reject(id, totp))}
              onWithdraw={(id) => act(() => api.withdraw(id))}
            />
          ))}
        </tbody>
      </table>
    </section>
  )
}
