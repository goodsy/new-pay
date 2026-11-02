import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ReconciliationOverview } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { EmptyRow, KstTime } from '../components/DataBits'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Badge } from '../components/ui/badge'
import { Card, CardContent } from '../components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'

/** 사람이 조사해야 하는 대사 실행 결과(자동 정정·수동 종료 없음 — §4.17, I-119·I-120). */
const INVESTIGATE = new Set(['ITEM_ERROR', 'FULL_SCAN_INCOMPLETE', 'PAYMENT_UNAVAILABLE'])

/**
 * 대사 발견(ADMIN-PAYMENT-OPS-001 §4.17·§4.20 — SECURITY_ADMIN·APPROVER): 상단에 열린 발견 수, 종류별 수, 최근 발견(code 열만). 해결·종료 버튼은 없다(운영 절차 LIVE Gate).
 */
export function ReconciliationScreen({ api, onSessionEnded }: { api: AdminApi; onSessionEnded?: (code: ConsoleErrorCode) => void }) {
  const [overview, setOverview] = useState<ReconciliationOverview | null>(null)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)

  const load = useCallback(async () => {
    try {
      setOverview(await api.reconciliation())
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    }
  }, [api, onSessionEnded])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="대사 발견" description="승인 기록과 결제 서비스 결과가 서로 맞지 않는 건을 보여 줍니다. 자동으로 고치거나 닫지 않으니 조사해 주세요." />
      {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
      {overview && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Card className="gap-2 py-5">
              <CardContent className="flex flex-col gap-2 px-5">
                <span className="text-xs text-muted-foreground">열린 발견</span>
                <h2 className="text-xl font-bold">{`열린 대사 발견 ${overview.openTotal}건`}</h2>
              </CardContent>
            </Card>
            <Card className="gap-2 py-5 sm:col-span-1 lg:col-span-3">
              <CardContent className="flex flex-col gap-2 px-5">
                <span className="text-xs text-muted-foreground">종류별</span>
                <ul className="flex flex-wrap gap-2">
                  {Object.entries(overview.openCounts).map(([kind, count]) => (
                    <li key={kind} className="flex flex-col gap-0.5 rounded-md border bg-muted/50 px-3 py-1.5">
                      <span className="text-sm font-medium">{labelOf(kind)}</span>
                      <span className="font-mono text-[11px] text-muted-foreground">{`${kind}: ${count}`}</span>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          </div>
          {overview.lastResult && INVESTIGATE.has(overview.lastResult) ? (
            <WarningNote>{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult} — 조사 필요 — 수동 종료 없음`}</WarningNote>
          ) : (
            <p className="text-sm text-muted-foreground">{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult ?? '기록 없음'} (완전 성공 ${overview.lastRunAt ? formatKst(overview.lastRunAt) : '-'}, 전체 스캔 ${overview.lastFullScanAt ? formatKst(overview.lastFullScanAt) : '-'})`}</p>
          )}
          {overview.truncated && <p className="text-xs text-muted-foreground">최근 200건만 표시합니다.</p>}
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>종류</TableHead>
                <TableHead>승인 ID</TableHead>
                <TableHead>결제</TableHead>
                <TableHead>세부(열 이름·code)</TableHead>
                <TableHead>발견 시각</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {overview.findings.map((f) => (
                <TableRow key={f.findingId} aria-label={`${f.kind} ${f.approvalId}`}>
                  <TableCell><Badge variant="warning" title={f.kind}>{labelOf(f.kind)}</Badge></TableCell>
                  <TableCell className="font-mono text-xs">{f.approvalId}</TableCell>
                  <TableCell className="font-mono text-xs">{f.paymentRef ?? ''}</TableCell>
                  <TableCell className="font-mono text-xs">{f.detailCode ?? ''}</TableCell>
                  <TableCell className="text-muted-foreground"><KstTime iso={f.foundAt} /></TableCell>
                </TableRow>
              ))}
              {overview.findings.length === 0 && <EmptyRow columns={5} message="열린 대사 발견이 없습니다." />}
            </TableBody>
          </Table>
        </>
      )}
    </section>
  )
}
