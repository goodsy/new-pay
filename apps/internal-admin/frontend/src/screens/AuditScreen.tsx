import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
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
import { MERCHANT_CODE, MERCHANT_CODE_HINT } from '../lib/merchantCode'
import { MERCHANT_LABELS_UNAVAILABLE } from '../lib/merchantLabel'
import { anyLabelUnavailable, merchantTargetText, recordedCodeNote, sanitizeAuditDetail } from '../lib/merchantTarget'
import { useMerchantTargetLabels } from '../lib/useMerchantTargetLabels'

interface Props {
  api: AdminApi
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

/** "가맹점 코드" 조회칸 화면 오류(MERCHANT-CODE-001 §8 MC-3-19 — 서버 호출 전 판정). */
export const AUDIT_CODE_FORMAT_ERROR = `가맹점 코드 형식이 아닙니다(${MERCHANT_CODE_HINT}).`
export const AUDIT_CODE_NOT_FOUND = '없는 가맹점 코드'

/**
 * 감사 조회(D-ADMIN1-008): 화면은 필터만 보낸다 — 범위(전체·자기 기록)는 서버가 세션 계정 현재 행으로 정한다. 조회 자체도 서버가 감사한다.
 * MERCHANT-CODE-001 §5.6(MC-3c): 대상 칸은 [merchantTargetText](가맹점 = 현재 상호·코드 라벨, 사업자 = "사업자", 내부 키 없음) + 코드 표기 행의 `(당시 <코드>)`,
 * 요약은 `(mer|biz)_<ULID>` 토큰을 "—"로 바꿔 그린다(`title`도 같은 값). "가맹점 코드" 조회 = 코드 검색(MC-2 — 은퇴 코드 일치 포함·감사 없음)으로 내부 키를 해석해
 * `targetMerchantId` 필터로 보낸다(해석은 언제나 MASTER — C-30, 내부 키는 화면에 그리지 않는다).
 */
export function AuditScreen({ api, onSessionEnded }: Props) {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [action, setAction] = useState('')
  const [target, setTarget] = useState('')
  const [merchantCode, setMerchantCode] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [loading, setLoading] = useState(false)
  /** reviewFix1 MINOR-2: 조회 순번 — 더 새 조회가 시작되면 옛 코드 검색·감사 응답은 버린다(늦게 온 응답이 새 결과를 덮지 않게). */
  const seq = useRef(0)
  const labels = useMerchantTargetLabels(api, entries)

  const fail = useCallback((e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError(code)
  }, [onSessionEnded])

  /** [mine] 순번의 감사 조회. 그 사이 새 조회가 시작됐으면 결과·오류·loading을 건드리지 않는다. */
  const search = useCallback(async (filter: AuditFilterInput, mine: number) => {
    setError(null)
    setLoading(true)
    try {
      const page = await api.audit(filter)
      if (mine === seq.current) setEntries(page.entries)
    } catch (e) {
      if (mine === seq.current) fail(e)
    } finally {
      if (mine === seq.current) setLoading(false)
    }
  }, [api, fail])

  useEffect(() => {
    void search({}, ++seq.current)
  }, [search])

  /** 코드 조회 실패(형식·없음·검색 실패): 표를 비워 이전 결과가 그 코드의 결과로 읽히지 않게 한다(reviewFix1 MINOR-1). */
  const codeFailed = (message: string | null) => {
    setCodeError(message)
    setEntries([])
    setLoading(false)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const mine = ++seq.current
    setCodeError(null)
    const filter: AuditFilterInput = {}
    if (action) filter.action = action
    if (target) filter.targetActorId = target
    if (merchantCode) {
      // §3.1 형식(정규화 없음) — 위반이면 화면 오류, 검색·감사 호출 0.
      if (!MERCHANT_CODE.test(merchantCode)) {
        codeFailed(AUDIT_CODE_FORMAT_ERROR)
        return
      }
      setError(null)
      setLoading(true)
      let merchantId: string
      try {
        const page = await api.merchantSearch({ merchantCode })
        if (mine !== seq.current) return
        // 코드는 전역 고유라 정확 일치는 하나뿐이다. 0건 또는 둘 이상이면 어느 가맹점인지 정할 수 없으므로 조회하지 않는다(fail-closed).
        if (page.items.length !== 1) {
          codeFailed(AUDIT_CODE_NOT_FOUND)
          return
        }
        merchantId = page.items[0]!.merchantId
      } catch (e) {
        if (mine !== seq.current) return
        codeFailed(null)
        fail(e)
        return
      }
      filter.targetMerchantId = merchantId
    }
    void search(filter, mine)
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
        <Field label="가맹점 코드" hint="현재·이전 코드 모두 같은 가맹점으로 찾습니다. 비우면 전체." error={codeError ?? undefined} className="flex-1">
          {(describedBy) => (
            <Input
              value={merchantCode}
              onChange={(e) => setMerchantCode(e.target.value)}
              placeholder="예: demoshop01"
              aria-describedby={describedBy}
              aria-invalid={codeError !== null}
              className="bg-background font-mono"
            />
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
          {entries.map((e) => {
            const note = recordedCodeNote(e, labels)
            const detail = e.detail === null ? '' : sanitizeAuditDetail(e.detail)
            return (
              <TableRow key={e.eventId} aria-label={`${e.action} ${e.resultCode}`}>
                <TableCell className="align-top text-muted-foreground"><KstTime iso={e.occurredAt} /></TableCell>
                <TableCell className="align-top whitespace-nowrap" title={e.actorKind}>{e.actorDisplayName ?? labelOf(e.actorKind)}</TableCell>
                <TableCell className="align-top"><KindLabel code={e.action} /></TableCell>
                <TableCell className="align-top font-mono text-xs break-all">
                  <span>{merchantTargetText(e, labels)}</span>
                  {note !== null && <small className="block text-muted-foreground">{note}</small>}
                </TableCell>
                <TableCell className="align-top"><StatusBadge code={e.resultCode} /></TableCell>
                {/* 요약은 길 수 있다 — 칸 너비를 두고 줄바꿈(긴 토큰도 끊음), 표가 쪽 밖으로 넘치지 않게. */}
                <TableCell className="max-w-64 min-w-40 align-top font-mono text-xs break-all whitespace-normal text-muted-foreground" title={e.detail === null ? undefined : detail}>
                  {detail}
                </TableCell>
              </TableRow>
            )
          })}
          {entries.length === 0 && <EmptyRow columns={6} message={loading ? '불러오는 중…' : '조건에 맞는 감사 기록이 없습니다.'} />}
        </TableBody>
      </Table>
      {anyLabelUnavailable(entries, labels) && <p role="note" className="mapnote">{MERCHANT_LABELS_UNAVAILABLE}</p>}
    </section>
  )
}
