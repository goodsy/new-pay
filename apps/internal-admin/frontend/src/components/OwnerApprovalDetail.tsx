import { useEffect, useState } from 'react'
import type { AdminApi, ApprovalView, Justification, PaymentTargetView } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst, formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'
import { tagClass } from '../lib/statusTone'
import { ErrorNotice, WarningNote } from './Notice'

interface Props {
  api: AdminApi
  item: ApprovalView
  viewer?: { actorId: string; roles: string[] }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

/** 요청 시점 고정 대상(`pinned_target` — I-98). 모양이 다르면 null(비교하지 않는다). */
interface Pinned {
  paymentStatus: string
  currency: string | null
  authorizedMinor: number | null
  cancellableMinor: number
  balanceRevision: number
  onHold: boolean
}

function pinnedOf(value: unknown): Pinned | null {
  if (typeof value !== 'object' || value === null) return null
  const v = value as Record<string, unknown>
  if (typeof v.paymentStatus !== 'string' || typeof v.cancellableMinor !== 'number' || typeof v.balanceRevision !== 'number' || typeof v.onHold !== 'boolean') return null
  return {
    paymentStatus: v.paymentStatus, currency: typeof v.currency === 'string' ? v.currency : null,
    authorizedMinor: typeof v.authorizedMinor === 'number' ? v.authorizedMinor : null, cancellableMinor: v.cancellableMinor,
    balanceRevision: v.balanceRevision, onHold: v.onHold,
  }
}

const show = (value: string | number | boolean | null | undefined) => (value === null || value === undefined ? '-' : String(value))

/**
 * 고정 값(요청 시점) 대 현재 값(§4.20 — S8 리뷰 M-1, I-132): 현재 값은 화면 대상 조회로 읽는다(비권위). revision·통화가 다르거나 요청 금액 > 현재 반환 가능액이거나 취소 불가면
 * "승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다", 진행 중 취소면 TARGET_BUSY 안내. 승인 버튼은 막지 않는다(서버가 잠근 행으로 다시 판정).
 */
function TargetComparison({ api, paymentId, amountMinor, pinned }: { api: AdminApi; paymentId: string; amountMinor: number; pinned: Pinned }) {
  const [current, setCurrent] = useState<PaymentTargetView | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    api.paymentTarget(paymentId).then((t) => alive && setCurrent(t)).catch(() => alive && setFailed(true))
    return () => {
      alive = false
    }
  }, [api, paymentId])

  const changed = current !== null && (
    current.balanceRevision !== pinned.balanceRevision || current.currency !== pinned.currency || amountMinor > current.cancellableMinor || !current.cancellable
  )
  const rows: [string, string, string][] = [
    ['결제 상태', show(pinned.paymentStatus), show(current?.paymentStatus)],
    ['통화', show(pinned.currency), show(current?.currency)],
    ['원 승인액', show(pinned.authorizedMinor), show(current?.authorizedMinor)],
    ['반환 가능액', show(pinned.cancellableMinor), show(current?.cancellableMinor)],
    ['revision', show(pinned.balanceRevision), show(current?.balanceRevision)],
    ['보류', show(pinned.onHold), show(current?.onHold)],
  ]
  return (
    <div className="flex flex-col gap-3">
      {/* ADMIN-UI-WORKSPACE-001(승인함): 시안 표(.tablewrap). 다른 값 행 강조(bg-warning/10)는 그대로 — 행 머리 칸(th)은 시안 머리 배경이 덮지 않게 같은 강조를 받는다. */}
      <div className="tablewrap">
        <table aria-label="고정 값과 현재 값">
          <thead>
            <tr>
              <th>항목</th><th>고정 값(요청 시점)</th><th>현재 값</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([label, fixed, now]) => {
              const differs = fixed !== now && current !== null ? 'bg-warning/10' : undefined
              return (
                <tr key={label} className={differs}>
                  <th scope="row" className={differs}>{label}</th>
                  <td className="font-mono">{fixed}</td>
                  <td className="font-mono">{now}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {failed && <WarningNote>현재 값 확인 불가</WarningNote>}
      {changed && <WarningNote>승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다</WarningNote>}
      {current?.inFlightCancel && <WarningNote>진행 중인 취소가 있어 지금 승인하면 TARGET_BUSY로 거절됩니다(TOTP는 소비되지 않음).</WarningNote>}
    </div>
  )
}

function statusText(item: ApprovalView): string {
  const owner = item.owner
  switch (item.status) {
    case 'EXECUTING':
      return '실행 요청 중 — 결과를 자동으로 확인합니다.'
    case 'EXECUTION_UNKNOWN':
      return '결과 확인 중 — 자동으로 다시 확인합니다(수동 종료 없음).'
    case 'EXECUTED':
      return `실행됨 — 취소 참조 ${owner?.ownerReference ?? '-'}`
    case 'OWNER_REJECTED':
      return `소유 서비스가 거절했습니다: ${owner?.ownerResultCode ?? item.resultCode ?? '-'}`
    default:
      return `상태: ${item.status}`
  }
}

/**
 * 소유 서비스 승인 상세(ADMIN-PAYMENT-OPS-001 §4.20, I-98·I-105): 상태 문구, 재전송 중단·무결성 경보는 "조사 필요 — 수동 종료 없음"(수동 종료 버튼 없음 — D-OPS-003),
 * 시도 수는 "명령 시도 기회 수". 근거·사유는 APPROVER·요청자 본인에게만 버튼을 보이고(표시만 — 서버가 판정, D-OPS-010) 권한 조회가 성공했을 때만 화면 상태에 둔다.
 */
export function OwnerApprovalDetail({ api, item, viewer, onSessionEnded }: Props) {
  const [justification, setJustification] = useState<Justification | null>(null)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const owner = item.owner
  const flags = [owner?.resendHaltedReason, owner?.integrityFlag].filter((v): v is string => typeof v === 'string' && v !== '')
  const mayAsk = viewer !== undefined && (viewer.roles.includes('APPROVER') || viewer.actorId === item.requestedBy)

  const reveal = async () => {
    setError(null)
    setJustification(null)
    try {
      setJustification(await api.justification(item.approvalId))
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    }
  }

  return (
    // ADMIN-UI-WORKSPACE-001(승인함): 시안 상세 카드(.card > .cardtop h4 → .cardbody). 승인 ID는 승인함 .detailarea 제목(h3 small)에 있다. 문구·조건·버튼 표시 규칙은 그대로.
    <section role="region" aria-label="소유 서비스 실행 상세" className="card">
      <div className="cardtop">
        <h4>{labelOf(item.kind)}</h4>
      </div>
      <div className="cardbody">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className={tagClass(item.status)} title={item.status}>{labelOf(item.status)}</span>
          <p>{statusText(item)}</p>
        </div>
        {flags.length > 0 && <WarningNote>{`조사 필요 — 수동 종료 없음 (${flags.join(', ')})`}</WarningNote>}
        {owner && item.status === 'PENDING' && item.targetRef && pinnedOf(owner.pinnedTarget) && (
          <TargetComparison api={api} paymentId={item.targetRef} amountMinor={owner.amountMinor} pinned={pinnedOf(owner.pinnedTarget) as Pinned} />
        )}
        {owner && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <li>{`금액: ${formatMinor(owner.amountMinor, owner.currency)}`}</li>
            <li title={owner.purposeKind}>{`목적 종류: ${labelOf(owner.purposeKind)}`}</li>
            <li>{`명령 시도 기회 수(결정 조회만 한 주기 포함): ${owner.attemptCount ?? '-'}`}</li>
            <li title={owner.nextAttemptAt ?? undefined}>{`다음 자동 확인: ${owner.nextAttemptAt ? formatKst(owner.nextAttemptAt) : '-'}`}</li>
            <li>{`마지막 실패 분류: ${owner.lastFailure ?? '-'}`}</li>
            <li title={owner.executeBefore ?? undefined}>{`실행 기한: ${owner.executeBefore ? formatKst(owner.executeBefore) : '-'}`}</li>
          </ul>
        )}
        {mayAsk && (
          <button type="button" className="btn self-start" onClick={reveal}>
            근거·사유 보기
          </button>
        )}
        {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
        {/* 조회한 근거·사유는 시안 .notice 상자 — 글자는 본문 색(text-foreground: 원본 .notice 글자 #64748b는 #f6f8fb 바탕에서 4.47:1로 AA 미달). */}
        {justification && (
          <dl className="notice grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-foreground">
            <dt className="font-medium">근거 참조</dt>
            <dd>{justification.purposeReference}</dd>
            <dt className="font-medium">사유</dt>
            <dd>{justification.reason}</dd>
          </dl>
        )}
      </div>
    </section>
  )
}
