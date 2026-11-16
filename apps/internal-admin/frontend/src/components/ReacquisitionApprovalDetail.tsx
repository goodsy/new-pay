import { useEffect, useState } from 'react'
import type { AdminApi, ApprovalView, PinnedReacquisitionItem, ReacquisitionEvidence, ReacquisitionTargets } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst, formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'
import { tagClass } from '../lib/statusTone'
import { ErrorNotice, WarningNote } from './Notice'
import { REACQUISITION_REASONS, REFUSALS } from './ReacquisitionRequest'

/** 고정 레코드(`pinned_target.items`) 모양이 다르면 null(비교하지 않는다). */
function pinnedOf(value: unknown): PinnedReacquisitionItem[] | null {
  if (typeof value !== 'object' || value === null) return null
  const items = (value as { items?: unknown }).items
  if (!Array.isArray(items)) return null
  const ok = items.every((i) => typeof i === 'object' && i !== null && typeof (i as PinnedReacquisitionItem).seq === 'number' &&
    typeof (i as PinnedReacquisitionItem).generation === 'number' && typeof (i as PinnedReacquisitionItem).status === 'string')
  return ok ? (items as PinnedReacquisitionItem[]) : null
}

function statusText(item: ApprovalView): string {
  const a = item.acquiring
  switch (item.status) {
    case 'EXECUTING':
      return '실행 요청 중 — 결과를 자동으로 확인합니다.'
    case 'EXECUTION_UNKNOWN':
      return '결과 확인 중 — 자동으로 다시 확인합니다(수동 종료 없음).'
    case 'EXECUTED':
      return a?.ownerResultCode === 'NOT_REQUIRED'
        ? '실행됨 — 실행 시점 순액이 0원이라 파일 없이 "재매입 불요"로 종결했습니다.'
        : `실행됨 — 재매입 파일 ${a?.ownerReference ?? '-'} 봉인(전송·결과는 매입 현황에서 확인)`
    case 'OWNER_REJECTED':
      return `매입 정보 서비스가 거절했습니다: ${REFUSALS[a?.ownerResultCode ?? item.resultCode ?? ''] ?? a?.ownerResultCode ?? item.resultCode ?? '-'}`
    default:
      return `상태: ${item.status}`
  }
}

/**
 * 재매입 승인 상세(ACQ-ADMIN-001 B1-A §5.2): 원 파일·사유 코드, 레코드별 고정 값(요청 시점 세대·상태) 대 현재 값(미리 보기 — 비권위)과 예상 순액(표시용 — 실행 TX 값이 권위, I-10).
 * 하나라도 다르거나 지금 불가면 "승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다"(승인 버튼은 막지 않는다 — 서버가 다시 판정).
 * 근거 참조(D-ACQA-005 — 운영자 보정 취소 D-OPS-010과 같게): 상세 응답에 없고, APPROVER·요청자 본인에게만 "근거 보기" 버튼을 보인다(표시만 — 서버가 판정·조회 감사). 권한 조회가 성공했을 때만
 * 화면 상태에 둔다(저장소·URL에 남기지 않는다). 사유 문구는 보이지 않는다.
 */
export function ReacquisitionApprovalDetail({ api, item, viewer, onSessionEnded }: {
  api: AdminApi
  item: ApprovalView
  viewer?: { actorId: string; roles: string[] }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const a = item.acquiring
  const pinned = a ? pinnedOf(a.pinnedTarget) : null
  const [current, setCurrent] = useState<ReacquisitionTargets | null>(null)
  const [failed, setFailed] = useState(false)
  const [evidence, setEvidence] = useState<ReacquisitionEvidence | null>(null)
  const [evidenceError, setEvidenceError] = useState<ConsoleErrorCode | null>(null)
  const mayAsk = viewer !== undefined && (viewer.roles.includes('APPROVER') || viewer.actorId === item.requestedBy)

  const reveal = async () => {
    setEvidenceError(null)
    setEvidence(null)
    try {
      setEvidence(await api.reacquisitionEvidence(item.approvalId))
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setEvidenceError(code)
    }
  }
  const compare = item.status === 'PENDING' && a !== undefined && pinned !== null

  useEffect(() => {
    if (!compare || a === undefined || pinned === null) return
    let alive = true
    api.reacquisitionTargets(a.originalFileId, pinned.map((p) => p.seq)).then((t) => alive && setCurrent(t)).catch(() => alive && setFailed(true))
    return () => {
      alive = false
    }
    // 승인 하나에 한 번만 읽는다(같은 승인 ID면 고정 값이 바뀌지 않는다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, item.approvalId])

  const now = (seq: number) => current?.items.find((c) => c.seq === seq)
  const changed = current !== null && pinned !== null && pinned.some((p) => {
    const c = now(p.seq)
    return c === undefined || !c.eligible || c.generation !== p.generation || c.status !== p.status
  })
  const flags = [a?.resendHaltedReason, a?.integrityFlag].filter((v): v is string => typeof v === 'string' && v !== '')

  return (
    // ADMIN-UI-WORKSPACE-001(승인함): 시안 상세 카드(.card > .cardtop h4 → .cardbody), 레코드 비교 표 .tablewrap, 근거 .notice. 승인 ID는 승인함 .detailarea 제목에 있다.
    <section role="region" aria-label="재매입 승인 상세" className="card">
      <div className="cardtop">
        <h4>재매입 승인</h4>
      </div>
      <div className="cardbody">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className={tagClass(item.status)} title={item.status}>{labelOf(item.status)}</span>
          <p>{statusText(item)}</p>
        </div>
        {flags.length > 0 && <WarningNote>{`조사 필요 — 수동 종료 없음 (${flags.join(', ')})`}</WarningNote>}
        {a && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <li>원 파일: <span className="font-mono text-xs">{a.originalFileId}</span></li>
            <li title={a.reasonCode}>{`사유 코드: ${REACQUISITION_REASONS[a.reasonCode] ?? a.reasonCode}`}</li>
            <li title={a.executeBefore ?? undefined}>{`실행 기한: ${a.executeBefore ? formatKst(a.executeBefore) : '-'}`}</li>
            <li>{`마지막 실패 분류: ${a.lastFailure ?? '-'}`}</li>
          </ul>
        )}
        {mayAsk && (
          <button type="button" className="btn self-start" onClick={reveal}>
            근거 보기
          </button>
        )}
        {evidenceError && <ErrorNotice>{ERROR_MESSAGES[evidenceError]}</ErrorNotice>}
        {/* 조회한 근거는 시안 .notice 상자 — 글자는 본문 색(text-foreground: 원본 .notice 글자 #64748b는 #f6f8fb 바탕에서 4.47:1로 AA 미달). */}
        {evidence && (
          <dl className="notice grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-foreground">
            <dt className="font-medium">근거 참조</dt>
            <dd className="font-mono">{evidence.evidenceRef}</dd>
          </dl>
        )}
        {pinned && (
          <div className="tablewrap">
            <table aria-label="재매입 레코드 고정 값과 현재 값">
              <thead>
                <tr>
                  <th>순번</th><th>거래</th><th>고정 값(요청 시점)</th><th>현재 값</th><th className="amount">원승인액</th>
                  <th className="amount">받은 취소</th><th className="amount">예상 순액(고정 · 현재, 표시용)</th>
                </tr>
              </thead>
              <tbody>
                {pinned.map((p) => {
                  const c = now(p.seq)
                  const nowText = !compare ? '-' : c === undefined ? (failed ? '확인 불가' : '…') : `${c.generation ?? '-'} · ${c.status ?? '-'}${c.eligible ? '' : ` (불가: ${REFUSALS[c.refusal ?? ''] ?? c.refusal ?? '-'})`}`
                  const differs = c !== undefined && (!c.eligible || c.generation !== p.generation || c.status !== p.status)
                  return (
                    <tr key={p.seq} aria-label={`고정 레코드 ${p.seq}`} className={differs ? 'bg-warning/10' : undefined}>
                      <td className="font-mono">{p.seq}</td>
                      <td className="font-mono">{p.transactionId}</td>
                      <td className="font-mono">{`${p.generation} · ${p.status}`}</td>
                      <td className="font-mono">{nowText}</td>
                      <td className="amount">{formatMinor(p.approvedAmountMinor, 'KRW')}</td>
                      <td className="amount">{formatMinor(p.cancelledAmountMinor, 'KRW')}</td>
                      <td className="amount">
                        {/* B1-A 리뷰 n-5: 고정 값(요청 시점)을 그대로 두고 현재 값은 따로 적는다 — 현재 값으로 조용히 바꾸지 않는다. */}
                        <span>{formatMinor(p.expectedNetMinor, 'KRW')}</span>
                        {compare && (
                          <small>
                            {c === undefined
                              ? (failed ? '현재 확인 불가' : '')
                              : c.expectedNetMinor === null
                                ? '현재 -'
                                : c.expectedNetMinor === p.expectedNetMinor ? '현재 같음' : `현재 ${formatMinor(c.expectedNetMinor, 'KRW')}`}
                          </small>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        {failed && <WarningNote>현재 값 확인 불가</WarningNote>}
        {changed && <WarningNote>승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다</WarningNote>}
        {compare && <p className="subtle">예상 순액은 표시용입니다. 금액은 실행 시점에 매입 정보 서비스가 받은 취소를 모두 반영해 다시 계산합니다.</p>}
      </div>
    </section>
  )
}
