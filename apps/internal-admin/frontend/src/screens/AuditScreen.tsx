import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { AdminApi, AuditEntry, AuditFilterInput } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { SearchIcon } from '../components/icons'
import { EmptyRow, Field, KindLabel, KstTime, Spinner, StatusBadge } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { labelOf } from '../lib/labels'

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
  const [loading, setLoading] = useState(false)

  const search = useCallback(async (filter: AuditFilterInput) => {
    setError(null)
    setLoading(true)
    try {
      setEntries((await api.audit(filter)).entries)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    } finally {
      setLoading(false)
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
    <section className="flex flex-col gap-6">
      <PageHeader title="감사 기록" description="누가 언제 무엇을 했는지 확인합니다. 볼 수 있는 범위는 서버가 내 권한으로 정하고, 이 조회도 기록됩니다." />
      {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
      <form onSubmit={submit} className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <Field label="행위" hint="행위 code로 거릅니다. 비우면 전체." className="flex-1">
          {(describedBy) => (
            <Input value={action} onChange={(e) => setAction(e.target.value)} placeholder="예: LOGIN_SUCCEEDED" aria-describedby={describedBy} className="bg-background font-mono" />
          )}
        </Field>
        <Field label="대상 actor ID" hint="대상 관리자 ID(adm_…). 비우면 전체." className="flex-1">
          {(describedBy) => (
            <Input value={target} onChange={(e) => setTarget(e.target.value)} placeholder="adm_…" aria-describedby={describedBy} className="bg-background font-mono" />
          )}
        </Field>
        <Button type="submit" aria-busy={loading} className="sm:mt-6">
          {loading ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
          조회
        </Button>
      </form>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>시각</TableHead>
            <TableHead>행위자</TableHead>
            <TableHead>행위</TableHead>
            <TableHead>대상</TableHead>
            <TableHead>결과</TableHead>
            <TableHead>요약</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((e) => (
            <TableRow key={e.eventId} aria-label={`${e.action} ${e.resultCode}`}>
              <TableCell className="align-top text-muted-foreground"><KstTime iso={e.occurredAt} /></TableCell>
              <TableCell className="align-top whitespace-nowrap" title={e.actorKind}>{e.actorDisplayName ?? labelOf(e.actorKind)}</TableCell>
              <TableCell className="align-top whitespace-nowrap"><KindLabel code={e.action} /></TableCell>
              <TableCell className="align-top font-mono text-xs">{e.targetActorId ?? ''}</TableCell>
              <TableCell className="align-top"><StatusBadge code={e.resultCode} /></TableCell>
              {/* 요약은 길 수 있다 — 칸 너비를 두고 줄바꿈(긴 토큰도 끊음), 표가 쪽 밖으로 넘치지 않게. */}
              <TableCell className="max-w-80 min-w-48 align-top font-mono text-xs break-all whitespace-normal text-muted-foreground" title={e.detail ?? undefined}>
                {e.detail ?? ''}
              </TableCell>
            </TableRow>
          ))}
          {entries.length === 0 && <EmptyRow columns={6} message={loading ? '불러오는 중…' : '조건에 맞는 감사 기록이 없습니다.'} />}
        </TableBody>
      </Table>
    </section>
  )
}
