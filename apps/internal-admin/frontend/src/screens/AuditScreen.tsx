import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { AdminApi, AuditEntry, AuditFilterInput } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

interface Props {
  api: AdminApi
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

/** 감사 조회(D-ADMIN1-008): 화면은 필터만 보낸다 — 범위(전체·자기 기록)는 서버가 세션 계정 현재 행으로 정한다. 조회 자체도 서버가 감사한다. */
export function AuditScreen({ api, onSessionEnded }: Props) {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [action, setAction] = useState('')
  const [target, setTarget] = useState('')
  const [error, setError] = useState<ConsoleErrorCode | null>(null)

  const search = useCallback(async (filter: AuditFilterInput) => {
    setError(null)
    try {
      setEntries((await api.audit(filter)).entries)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    }
  }, [api, onSessionEnded])

  useEffect(() => {
    void search({})
  }, [search])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const filter: AuditFilterInput = {}
    if (action) filter.action = action
    if (target) filter.targetActorId = target
    void search(filter)
  }

  return (
    <section>
      <h2>감사 기록</h2>
      {error && <p role="alert">{ERROR_MESSAGES[error]}</p>}
      <form onSubmit={submit}>
        <label>
          행위
          <input value={action} onChange={(e) => setAction(e.target.value)} />
        </label>
        <label>
          대상 actor ID
          <input value={target} onChange={(e) => setTarget(e.target.value)} />
        </label>
        <button type="submit">조회</button>
      </form>
      <table>
        <thead>
          <tr>
            <th>시각</th>
            <th>행위자</th>
            <th>행위</th>
            <th>대상</th>
            <th>결과</th>
            <th>요약</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.eventId} aria-label={`${e.action} ${e.resultCode}`}>
              <td>{e.occurredAt}</td>
              <td>{e.actorDisplayName ?? e.actorKind}</td>
              <td>{e.action}</td>
              <td>{e.targetActorId ?? ''}</td>
              <td>{e.resultCode}</td>
              <td>{e.detail ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
