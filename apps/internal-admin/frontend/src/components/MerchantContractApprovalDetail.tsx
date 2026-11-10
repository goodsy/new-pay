import { useState } from 'react'
import type { AdminApi, ApprovalView, PinnedContractApproval, ReacquisitionEvidence } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { StatusBadge } from './DataBits'
import { ErrorNotice, WarningNote } from './Notice'
import { Button } from './ui/button'
import { Card, CardContent, CardHeader } from './ui/card'

/** MASTER 거절 → 화면 문구(MERCHANT-OPS-002 §9.5 — 모르는 code는 일반 문구). */
export const MERCHANT_REJECTIONS: Readonly<Record<string, string>> = {
  REVISION_ALREADY_APPROVED: '이미 승인된 Revision입니다',
  REVISION_ENDED: '효력이 끝난 Revision입니다',
  REVISION_NOT_FOUND: 'Revision을 찾을 수 없습니다',
  CONTRACT_NOT_FOUND: '계약을 찾을 수 없습니다',
  MERCHANT_NOT_ACTIVE: '가맹점이 활성 상태가 아닙니다',
  APPROVAL_EXPIRED: '실행 기한이 지나 처리되지 않았습니다 — 다시 요청하세요',
  APPROVER_IS_REVISION_REGISTRANT: 'Revision 등록자는 승인할 수 없습니다',
  APPROVAL_SAME_ACTOR: '시스템 점검 필요(관리자에게 알림)',
  APPROVAL_WINDOW_INVALID: '시스템 점검 필요(관리자에게 알림)',
  APPROVAL_ID_ALREADY_USED: '시스템 점검 필요(관리자에게 알림)',
  // B2-3 첫 MID 발급(§9.5 문구)
  DEFAULT_ALREADY_ISSUED: '이미 기본 MID가 있습니다',
  REVISION_NOT_APPROVED: '승인되지 않은 Revision입니다',
  REVISION_NOT_APPLICABLE: '이 Revision으로는 발급할 수 없습니다',
  SITE_OUTSIDE_CONTRACT_SCOPE: 'Site가 계약 범위 밖입니다',
  SITE_NOT_ACTIVE: 'Site가 활성 상태가 아닙니다',
  AGREEMENT_NOT_FOUND: '서비스 계약(CSA)을 찾을 수 없습니다',
}

/** 고정 대상 모양이 다르면 null(표시하지 않는다). */
export function pinnedContractOf(value: unknown): PinnedContractApproval | null {
  if (typeof value === 'string') {
    try {
      return pinnedContractOf(JSON.parse(value))
    } catch {
      return null
    }
  }
  if (typeof value !== 'object' || value === null) return null
  const p = value as Partial<PinnedContractApproval>
  const ok = typeof p.merchantId === 'string' && typeof p.contractId === 'string' && typeof p.revisionNo === 'number' && typeof p.registeredBy === 'string' &&
    typeof p.effectiveFrom === 'string' && Array.isArray(p.scopeSiteIds)
  return ok ? (p as PinnedContractApproval) : null
}

/** 첫 MID 고정 대상(B2-3) — 모양이 다르면 null. */
export interface PinnedFirstMid {
  merchantId: string
  merchantName: string
  accountCount: number
  contractId: string
  agreementId: string
  revisionNo: number
  primarySiteId: string
  channelType: string
}

export function pinnedMidOf(value: unknown): PinnedFirstMid | null {
  if (typeof value === 'string') {
    try {
      return pinnedMidOf(JSON.parse(value))
    } catch {
      return null
    }
  }
  if (typeof value !== 'object' || value === null) return null
  const p = value as Partial<PinnedFirstMid>
  return typeof p.merchantId === 'string' && typeof p.agreementId === 'string' && typeof p.primarySiteId === 'string' && typeof p.revisionNo === 'number'
    ? (p as PinnedFirstMid) : null
}

/** 시청자가 그 Revision의 등록자인지(정규 actor `admin:<계정 ID>` 문자열 비교 — 표시만, 권위 판정은 승인 TX1·MASTER). */
export function isRegistrant(item: ApprovalView, viewerActorId: string | undefined): boolean {
  if (item.kind !== 'MERCHANT_CONTRACT_APPROVAL' || viewerActorId === undefined) return false
  const pinned = pinnedContractOf(item.merchant?.pinnedTarget ?? item.targetSnapshot)
  return pinned !== null && pinned.registeredBy === `admin:${viewerActorId}`
}

function statusText(item: ApprovalView): string {
  const g = item.merchant
  switch (item.status) {
    case 'PENDING':
      return '승인 대기 중'
    case 'EXECUTING':
      return '실행 요청 중 — 결과를 자동으로 확인합니다.'
    case 'EXECUTION_UNKNOWN':
      return '결과 확인 중(자동 재확인) — 수동 종료 없음'
    case 'EXECUTED':
      return item.kind === 'MERCHANT_FIRST_MID_ISSUANCE' ? `발급됨 — ${g?.ownerReference ?? '-'}` : `승인됨 — ${g?.ownerReference ?? '-'}`
    case 'OWNER_REJECTED': {
      const code = g?.ownerResultCode ?? item.resultCode ?? ''
      return `가맹점 정보 서비스가 거절했습니다: ${MERCHANT_REJECTIONS[code] ?? code ?? '-'}`
    }
    case 'INVALIDATED':
      return '요청 뒤 대상이 바뀌어 무효'
    default:
      return `상태: ${labelOf(item.status)}`
  }
}

/** Asia/Seoul 날짜(시각은 그 날 00:00 KST — 계약 기간 입력 규칙 C-7). */
const kstDate = (iso: string) => formatKst(iso).slice(0, 10)

/**
 * 가맹점 계약 승인 상세(MERCHANT-OPS-002 B2-2 §9.6): 고정 대상(가맹점·계약·Revision·Scope·Site ID·시작/종료 KST 날짜 + "이 날 0시 종료(미포함)")·요청자·**Revision 등록자**
 * (D-MOPS-008 ③ — 본인이 등록자면 "등록자는 승인할 수 없습니다"), 상태별 문구, 실행 기한·마지막 실패. 근거 참조는 상세 응답에 없고 APPROVER·요청자에게만 "근거 보기"(서버가 판정·감사).
 * 조회한 근거는 이 화면 상태에만 둔다(저장소·URL 없음).
 */
export function MerchantContractApprovalDetail({ api, item, viewer, onSessionEnded }: {
  api: AdminApi
  item: ApprovalView
  viewer?: { actorId: string; roles: string[] }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const g = item.merchant
  const isMid = item.kind === 'MERCHANT_FIRST_MID_ISSUANCE'
  const pinned = isMid ? null : pinnedContractOf(g?.pinnedTarget ?? item.targetSnapshot)
  const mid = isMid ? pinnedMidOf(g?.pinnedTarget ?? item.targetSnapshot) : null
  const title = isMid ? '첫 MID 발급' : '가맹점 계약 승인'
  const [evidence, setEvidence] = useState<ReacquisitionEvidence | null>(null)
  const [evidenceError, setEvidenceError] = useState<ConsoleErrorCode | null>(null)
  const mayAsk = viewer !== undefined && (viewer.roles.includes('APPROVER') || viewer.actorId === item.requestedBy)
  const registrant = isRegistrant(item, viewer?.actorId)
  const flags = [g?.resendHaltedReason, g?.integrityFlag].filter((v): v is string => typeof v === 'string' && v !== '')

  const reveal = async () => {
    setEvidenceError(null)
    setEvidence(null)
    try {
      setEvidence(await api.merchantApprovalEvidence(item.approvalId))
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setEvidenceError(code)
    }
  }

  return (
    <Card as="section" role="region" aria-label={isMid ? "첫 MID 발급 승인 상세" : "가맹점 계약 승인 상세"} className="gap-4 py-5">
      <CardHeader className="px-5">
        <h3 className="font-semibold">
          {title} <span className="font-mono text-sm">{item.approvalId}</span>
        </h3>
        <div className="flex items-center gap-2 text-sm">
          <StatusBadge code={item.status} />
          <p>{statusText(item)}</p>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-5">
        {flags.length > 0 && <WarningNote>{`조사 필요 — 수동 종료 없음 (${flags.join(', ')})`}</WarningNote>}
        {registrant && item.status === 'PENDING' && <WarningNote>등록자는 승인할 수 없습니다 — 다른 승인자가 처리해야 합니다.</WarningNote>}
        {pinned && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <li>{'가맹점: '}<span className="font-medium">{pinned.merchantName}</span> <span className="font-mono text-xs">{pinned.merchantId}</span></li>
            <li>{'계약 · Revision: '}<span className="font-mono text-xs">{`${pinned.contractId} #${pinned.revisionNo}`}</span></li>
            <li>{`범위: ${labelOf(pinned.scopeKind)}`}{pinned.scopeSiteIds.length > 0 && <span className="ml-1 font-mono text-xs">{pinned.scopeSiteIds.join(', ')}</span>}</li>
            <li title={pinned.effectiveFrom}>{`시작일(KST): ${kstDate(pinned.effectiveFrom)}`}</li>
            <li title={pinned.effectiveTo ?? undefined}>{pinned.effectiveTo === null ? '종료일: 없음' : `종료일(KST): ${kstDate(pinned.effectiveTo)} — 이 날 0시 종료(미포함)`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            <li>{'Revision 등록자: '}<span className="font-mono text-xs">{pinned.registeredBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {mid && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <li>{'가맹점: '}<span className="font-medium">{mid.merchantName}</span> <span className="font-mono text-xs">{mid.merchantId}</span></li>
            <li>{'계약 · Revision: '}<span className="font-mono text-xs">{`${mid.contractId} #${mid.revisionNo}`}</span></li>
            <li>{`CSA: ${mid.agreementId}`}</li>
            <li>{`PRIMARY Site: ${mid.primarySiteId} (${labelOf(mid.channelType)})`}</li>
            <li>{`요청 시점 MID 수: ${mid.accountCount}`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {mayAsk && (
          <Button type="button" variant="outline" size="sm" onClick={reveal} className="self-start">
            근거 보기
          </Button>
        )}
        {evidenceError && <ErrorNotice>{ERROR_MESSAGES[evidenceError]}</ErrorNotice>}
        {evidence && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-md border bg-muted/50 p-3 text-sm">
            <dt className="text-muted-foreground">근거 참조</dt>
            <dd className="font-mono text-xs">{evidence.evidenceRef}</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
