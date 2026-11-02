import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ApprovalView } from '../api/adminApi'
import { OneTimeCode } from '../components/OneTimeCode'
import { OwnerApprovalDetail } from '../components/OwnerApprovalDetail'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { EmptyRow, Field, KindLabel, RoleBadges, StatusBadge } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'

const TOTP = /^[0-9]{6}$/

interface Props {
  api: AdminApi
  view: 'pending' | 'mine'
  /** 승인·거절 버튼 표시(APPROVER — 표시만, 권한 판정은 서버). */
  canDecide: boolean
  /** 조회자(근거·사유 버튼 표시 판단용 — 표시만, 권한 판정은 서버). */
  viewer?: { actorId: string; roles: string[] }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

function Row({ item, canDecide, mine, onApprove, onReject, onWithdraw, onDetail }: {
  item: ApprovalView
  canDecide: boolean
  mine: boolean
  onApprove: (id: string, totp: string) => Promise<void>
  onReject: (id: string, totp: string) => Promise<void>
  onWithdraw: (id: string) => Promise<void>
  onDetail: (id: string) => Promise<void>
}) {
  const target = item.targetActorId ?? item.targetRef ?? ''
  const [totp, setTotp] = useState('')
  const valid = TOTP.test(totp)
  const decide = async (action: (id: string, totp: string) => Promise<void>) => {
    const code = totp
    setTotp('')
    await action(item.approvalId, code)
  }
  return (
    <TableRow aria-label={`${item.kind} ${target} ${item.status}`}>
      <TableCell><KindLabel code={item.kind} /></TableCell>
      <TableCell>
        {item.grantedRole ? <RoleBadges roles={[item.grantedRole]} /> : <span className="font-mono text-xs">{item.causeApprovalId ?? ''}</span>}
      </TableCell>
      <TableCell className="font-mono text-xs">{target}</TableCell>
      <TableCell>{item.requestedByDisplayName}</TableCell>
      <TableCell><StatusBadge code={item.status} /></TableCell>
      <TableCell>
        <div className="flex flex-wrap items-start gap-2">
          {canDecide && item.status === 'PENDING' && (
            <>
              <Field label="인증 코드(6자리)" hint={valid ? undefined : '6자리를 입력하면 승인·거절할 수 있습니다.'} className="w-44 text-xs">
                {(describedBy) => (
                  <Input inputMode="numeric" maxLength={6} value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" aria-describedby={describedBy} className="h-8 font-mono tracking-widest" />
                )}
              </Field>
              <div className="flex items-center gap-2 pt-6">
                <Button type="button" size="sm" disabled={!valid} onClick={() => decide(onApprove)}>
                  승인
                </Button>
                <span aria-hidden="true" className="h-5 w-px bg-border" />
                <Button type="button" size="sm" variant="destructive" disabled={!valid} onClick={() => decide(onReject)}>
                  거절
                </Button>
              </div>
            </>
          )}
          {mine && item.status === 'PENDING' && (
            <Button type="button" size="sm" variant="outline" onClick={() => onWithdraw(item.approvalId)}>
              철회
            </Button>
          )}
          {item.targetKind === 'PAYMENT' && (
            <Button type="button" size="sm" variant="outline" onClick={() => onDetail(item.approvalId)}>
              상세
            </Button>
          )}
        </div>
      </TableCell>
    </TableRow>
  )
}

/** 승인함(PENDING — 승인·거절 + 직전 TOTP)과 내 요청(철회). 재설정 승인 응답의 코드는 [OneTimeCode]로 1회만 보이고 닫으면 상태에서 지운다. */
export function ApprovalsScreen({ api, view, canDecide, viewer, onSessionEnded }: Props) {
  const [items, setItems] = useState<ApprovalView[]>([])
  const [detail, setDetail] = useState<ApprovalView | null>(null)
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
    <section className="flex flex-col gap-6">
      <PageHeader
        title={view === 'pending' ? '승인함' : '내 요청'}
        description={view === 'pending' ? '다른 관리자가 요청한 작업을 검토하고, 인증 코드로 승인하거나 거절합니다.' : '내가 만든 승인 요청의 진행 상태를 확인하고, 대기 중이면 철회합니다.'}
      />
      {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
      {oneTimeCode && <OneTimeCode code={oneTimeCode} onClose={() => setOneTimeCode(null)} />}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>종류</TableHead>
            <TableHead>역할·원인</TableHead>
            <TableHead>대상</TableHead>
            <TableHead>요청자</TableHead>
            <TableHead>상태</TableHead>
            <TableHead>작업</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((item) => (
            <Row
              key={item.approvalId}
              item={item}
              canDecide={canDecide && view === 'pending'}
              mine={view === 'mine'}
              onApprove={(id, totp) => act(() => api.approve(id, totp))}
              onReject={(id, totp) => act(() => api.reject(id, totp))}
              onWithdraw={(id) => act(() => api.withdraw(id))}
              onDetail={async (id) => {
                setError(null)
                try {
                  setDetail(await api.approvalDetail(id))
                } catch (e) {
                  fail(e)
                }
              }}
            />
          ))}
          {items.length === 0 && <EmptyRow columns={6} message={view === 'pending' ? '승인 대기 중인 요청이 없습니다.' : '내가 만든 승인 요청이 없습니다.'} />}
        </TableBody>
      </Table>
      {detail && <OwnerApprovalDetail key={detail.approvalId} api={api} item={detail} viewer={viewer} onSessionEnded={onSessionEnded} />}
    </section>
  )
}
