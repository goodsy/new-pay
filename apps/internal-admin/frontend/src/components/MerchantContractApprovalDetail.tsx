import { useState } from 'react'
import type { AdminApi, ApprovalView, PinnedContractApproval, ReacquisitionEvidence } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { ACTIVATION_REJECTION_MESSAGES, codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { MERCHANT_CODE } from '../lib/merchantCode'
import { hasInternalKey, TARGET_HIDDEN, type MerchantTargetLabel } from '../lib/merchantTarget'
import { tagClass } from '../lib/statusTone'
import { ErrorNotice, WarningNote } from './Notice'

/**
 * MERCHANT-CODE-001 §5.6.2 승인 상세 "요청 시 코드": pinned `merchantCode`(MC-3a부터 새 pinned에 있음) → 없으면 `targetRef`가 코드 형식일 때 그 값 → 없으면 없음(기존 행).
 */
export function requestCodeOf(pinnedCode: string | undefined, targetRef: string | null | undefined): string | undefined {
  if (typeof pinnedCode === 'string' && MERCHANT_CODE.test(pinnedCode)) return pinnedCode
  return typeof targetRef === 'string' && MERCHANT_CODE.test(targetRef) ? targetRef : undefined
}

/**
 * 가맹점 줄(§5.6.2): `가맹점: <pinned 상호> · <요청 시 코드 ?? 현재 코드 ?? "—">`, 요청 시 코드와 현재 코드가 둘 다 있고 다르면 ` → 현재 <현재 코드>`.
 * pinned 상호는 요청 시점 값(봉인 근거)이라 그대로 둔다. 내부 키(`mer_`)는 그리지 않는다(D-ADMIN-UI-004 ③·D-MCODE-003).
 */
function MerchantLine({ name, requestCode, current }: { name: string; requestCode: string | undefined; current: MerchantTargetLabel | undefined }) {
  const currentCode = current?.state === 'found' ? current.label.code : undefined
  const shown = requestCode ?? currentCode ?? TARGET_HIDDEN
  const safeName = hasInternalKey(name) ? TARGET_HIDDEN : name
  return (
    <li>
      {'가맹점: '}<span className="font-medium">{safeName}</span>{' · '}<span className="font-mono">{hasInternalKey(shown) ? TARGET_HIDDEN : shown}</span>
      {requestCode !== undefined && currentCode !== undefined && requestCode !== currentCode && !hasInternalKey(currentCode) && (
        <span className="font-medium">{` → 현재 ${currentCode}`}</span>
      )}
    </li>
  )
}

/** MASTER 거절 → 화면 문구(MERCHANT-OPS-002 §9.5 — 모르는 code는 일반 문구). */
export const MERCHANT_REJECTIONS: Readonly<Record<string, string>> = {
  REVISION_ALREADY_APPROVED: '이미 승인된 Revision입니다',
  REVISION_ENDED: '효력이 끝난 Revision입니다',
  REVISION_NOT_FOUND: 'Revision을 찾을 수 없습니다',
  CONTRACT_NOT_FOUND: '계약을 찾을 수 없습니다',
  MERCHANT_NOT_ACTIVE: '가맹점이 활성 상태가 아닙니다',
  APPROVAL_EXPIRED: '실행 기한이 지나 처리되지 않았습니다 — 다시 요청하세요',
  APPROVER_IS_REVISION_REGISTRANT: 'Revision 등록자는 승인할 수 없습니다',
  // B3-1F(D-MOPS-009 ③): ADMIN이 먼저 403으로 막으므로 보통 나오지 않는다(MASTER에서 나오면 경보 — 문구는 사람이 이해할 수 있게).
  APPROVER_IS_ACTIVATION_REGISTRANT: '개통 등록자는 승인할 수 없습니다',
  APPROVER_IS_SITE_REGISTRANT: 'Site 등록자는 심사 승인할 수 없습니다',
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

/**
 * B3-2 통화 활성화 고정 대상(근거 참조 없음 — §9.16·§9.26). [effectiveFromMode] `AT_EXECUTION`이면 [effectiveFrom] = null(MASTER 실행 시각으로 확정), `DATE`면 KST 날짜.
 * [effectiveTo]는 KST 날짜(그 날 0시 종료, 미포함) 또는 null. 등록자 없음(1인 등록 단계 없음).
 */
export interface PinnedCurrency {
  merchantId: string
  merchantName: string
  merchantCode?: string
  merchantAccountId: string
  accountLifecycle: string
  currency: string
  effectiveFromMode: 'AT_EXECUTION' | 'DATE'
  effectiveFrom: string | null
  effectiveTo: string | null
}

/** MERCHANT-CODE-001 MC-1b 코드 변경 고정 대상(§5.3 — 등록자 키 없음). */
export interface PinnedCodeChange {
  merchantId: string
  merchantName: string
  currentCode: string
  newCode: string
  reasonCode: string
}

export function pinnedCodeChangeOf(value: unknown): PinnedCodeChange | null {
  const p = parsed(value)
  return strings(p, ['merchantId', 'merchantName', 'currentCode', 'newCode', 'reasonCode']) ? (p as unknown as PinnedCodeChange) : null
}

const CODE_CHANGE_REASON_TEXT: Readonly<Record<string, string>> = { TYPO_CORRECTION: '오타 수정', MERCHANT_REQUEST: '가맹점 요청', OTHER_POLICY: '그 밖의 정책 사유' }

export function pinnedCurrencyOf(value: unknown): PinnedCurrency | null {
  const p = parsed(value)
  if (!strings(p, ['merchantId', 'merchantAccountId', 'currency', 'effectiveFromMode'])) return null
  const q = p as Record<string, unknown>
  const dateOrNull = (v: unknown) => v === null || typeof v === 'string'
  const modeOk = (q.effectiveFromMode === 'AT_EXECUTION' && q.effectiveFrom === null) || (q.effectiveFromMode === 'DATE' && typeof q.effectiveFrom === 'string')
  return modeOk && dateOrNull(q.effectiveTo) ? (q as unknown as PinnedCurrency) : null
}

/** 승인 화면의 효력 시작 문구(D-MOPS-009 ② "승인 화면에 표시"). */
export function currencyStartText(p: PinnedCurrency): string {
  return p.effectiveFromMode === 'AT_EXECUTION' ? '효력 시작: 승인 실행 즉시(MASTER 실행 시각으로 확정)' : `효력 시작: ${p.effectiveFrom} 00:00 KST부터`
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
  merchantCode?: string
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

/** B3-1 결제수단 개통 승인 고정 대상(사업자등록번호·채널 식별값·근거 참조 없음). [registeredBy]는 표시 + 등록자 안내 근거(D-MOPS-009 ③ — 권위 판정은 서버 TX1 403·MASTER). */
export interface PinnedActivation {
  merchantId: string
  merchantName: string
  merchantCode?: string
  merchantAccountId: string
  activationId: string
  paymentMethod: string
  status: string
  primarySiteId: string
  registeredBy: string
}

/** B3-1 Site 심사 승인 고정 대상(채널 식별값·근거 참조 없음). */
export interface PinnedSiteReview {
  merchantId: string
  merchantName: string
  merchantCode?: string
  merchantSiteId: string
  channelType: string
  reviewStatus: string
  reasonCode: string
  registeredBy: string
}

function parsed(value: unknown): Record<string, unknown> | null {
  if (typeof value === 'string') {
    try {
      return parsed(JSON.parse(value))
    } catch {
      return null
    }
  }
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

const strings = (p: Record<string, unknown> | null, keys: string[]) => p !== null && keys.every((k) => typeof p[k] === 'string')

export function pinnedActivationOf(value: unknown): PinnedActivation | null {
  const p = parsed(value)
  return strings(p, ['merchantId', 'merchantAccountId', 'activationId', 'paymentMethod', 'status', 'primarySiteId', 'registeredBy']) ? (p as unknown as PinnedActivation) : null
}

export function pinnedSiteReviewOf(value: unknown): PinnedSiteReview | null {
  const p = parsed(value)
  return strings(p, ['merchantId', 'merchantSiteId', 'channelType', 'reviewStatus', 'reasonCode', 'registeredBy']) ? (p as unknown as PinnedSiteReview) : null
}

/** 종류별 제목·근거 라벨(minor-5 — 같은 형식, 다른 의미). */
const KIND_TEXT: Readonly<Record<string, { title: string; region: string; executed: string; evidence: string }>> = {
  MERCHANT_CONTRACT_APPROVAL: { title: '가맹점 계약 승인', region: '가맹점 계약 승인 상세', executed: '승인됨', evidence: '근거 참조' },
  MERCHANT_FIRST_MID_ISSUANCE: { title: '첫 MID 발급', region: '첫 MID 발급 승인 상세', executed: '발급됨', evidence: '근거 참조' },
  MERCHANT_PAYMENT_METHOD_ACTIVATION: { title: '결제수단 개통 승인', region: '결제수단 개통 승인 상세', executed: '개통됨', evidence: '기관(카드사/VAN) 회신 참조' },
  MERCHANT_SITE_REVIEW_APPROVAL: { title: 'Site 심사 승인', region: 'Site 심사 승인 상세', executed: '심사 승인됨', evidence: 'PG 심사 근거' },
  MERCHANT_CURRENCY_ENABLEMENT: { title: '통화 활성화', region: '통화 활성화 승인 상세', executed: '활성화됨', evidence: '기관(카드사/VAN) 회신 참조' },
  MERCHANT_CODE_CHANGE: { title: '가맹점 코드 변경', region: '가맹점 코드 변경 승인 상세', executed: '변경됨', evidence: '근거 참조' },
}
const kindText = (kind: string) => KIND_TEXT[kind] ?? KIND_TEXT.MERCHANT_CONTRACT_APPROVAL!

/**
 * 시청자가 그 대상의 1인 등록자인지(정규 actor `admin:<계정 ID>` 문자열 비교 — 표시만, 권위 판정은 승인 TX1·MASTER): 계약 승인 = Revision 등록자(D-MOPS-008 ③),
 * 결제수단 개통 승인 = 개통 등록자·Site 심사 승인 = Site 등록자(D-MOPS-009 ③ — SUSPENDED 재승인 포함 D-MOPS-010 ②). 첫 MID 발급은 대상 아님.
 */
export function isRegistrant(item: ApprovalView, viewerActorId: string | undefined): boolean {
  if (viewerActorId === undefined) return false
  const raw = item.merchant?.pinnedTarget ?? item.targetSnapshot
  const registeredBy = (() => {
    switch (item.kind) {
      case 'MERCHANT_CONTRACT_APPROVAL':
        return pinnedContractOf(raw)?.registeredBy
      case 'MERCHANT_PAYMENT_METHOD_ACTIVATION':
        return pinnedActivationOf(raw)?.registeredBy
      case 'MERCHANT_SITE_REVIEW_APPROVAL':
        return pinnedSiteReviewOf(raw)?.registeredBy
      default:
        return undefined
    }
  })()
  return registeredBy !== undefined && registeredBy === `admin:${viewerActorId}`
}

/** 코드 변경 pinned의 새 코드(코드 형식일 때만 — 나머지 pinned 키가 없어도 읽는다). 없으면 undefined. */
function pinnedNewCodeOf(item: ApprovalView): string | undefined {
  const p = parsed(item.merchant?.pinnedTarget ?? item.targetSnapshot)
  const code = p?.newCode
  return typeof code === 'string' && MERCHANT_CODE.test(code) ? code : undefined
}

/** 상태 문구 = 머리말 + (있으면) " — " 뒤 참조/사유. */
function statusParts(item: ApprovalView): { head: string; ref?: string } {
  const g = item.merchant
  switch (item.status) {
    case 'PENDING':
      return { head: '승인 대기 중' }
    case 'EXECUTING':
      return { head: '실행 요청 중 — 결과를 자동으로 확인합니다.' }
    case 'EXECUTION_UNKNOWN':
      return { head: '결과 확인 중(자동 재확인) — 수동 종료 없음' }
    case 'EXECUTED': {
      const head = kindText(item.kind).executed
      // reviewFix1 MAJOR-1(§5.6.2·MC-3-19·D-ADMIN-UI-004 ③): 코드 변경의 소유 참조는 `^mer_<ULID>$`(OwnerExecution) — 참조 대신 pinned 새 코드(공개값), 없으면 머리말만.
      // 그 밖 종류(ctr_·mid_·act_·site_·cur_ 참조)는 §5.6.2 "그 밖 대상은 지금 그대로".
      if (item.kind === 'MERCHANT_CODE_CHANGE') {
        const newCode = pinnedNewCodeOf(item)
        return newCode === undefined ? { head } : { head, ref: newCode }
      }
      return { head, ref: g?.ownerReference ?? '-' }
    }
    case 'OWNER_REJECTED': {
      const code = g?.ownerResultCode ?? item.resultCode ?? ''
      return { head: '가맹점 정보 서비스가 거절했습니다:', ref: MERCHANT_REJECTIONS[code] ?? ACTIVATION_REJECTION_MESSAGES[code] ?? code ?? '-' }
    }
    case 'INVALIDATED':
      return { head: '요청 뒤 대상이 바뀌어 무효' }
    default:
      return { head: `상태: ${labelOf(item.status)}` }
  }
}

/**
 * 상세 상태 문구. 마지막 방어(reviewFix1 MAJOR-1 — §5.6.2 "어떤 분기도 내부 키를 텍스트에 넣지 않는다"): 결과에 내부 키(`mer_`·`biz_`)가 있으면 참조 부분을 "—"로 바꾼다.
 */
export function statusText(item: ApprovalView): string {
  const { head, ref } = statusParts(item)
  const sep = item.status === 'OWNER_REJECTED' ? ' ' : ' — '
  const text = ref === undefined ? head : `${head}${sep}${ref}`
  if (!hasInternalKey(text)) return text
  return hasInternalKey(head) ? TARGET_HIDDEN : `${head}${sep}${TARGET_HIDDEN}`
}

/** Asia/Seoul 날짜(시각은 그 날 00:00 KST — 계약 기간 입력 규칙 C-7). */
const kstDate = (iso: string) => formatKst(iso).slice(0, 10)

/**
 * 가맹점 계약 승인 상세(MERCHANT-OPS-002 B2-2 §9.6): 고정 대상(가맹점·계약·Revision·Scope·Site ID·시작/종료 KST 날짜 + "이 날 0시 종료(미포함)")·요청자·**Revision 등록자**
 * (D-MOPS-008 ③ — 본인이 등록자면 "등록자는 승인할 수 없습니다"), 상태별 문구, 실행 기한·마지막 실패. 근거 참조는 상세 응답에 없고 APPROVER·요청자에게만 "근거 보기"(서버가 판정·감사).
 * 조회한 근거는 이 화면 상태에만 둔다(저장소·URL 없음).
 */
export function MerchantContractApprovalDetail({ api, item, currentLabel, viewer, onSessionEnded }: {
  api: AdminApi
  item: ApprovalView
  /** 목록이 불러온 이 대상의 현재 라벨(MERCHANT-CODE-001 §5.6.2 — "→ 현재 <코드>"). 없거나 불러오지 못했으면 undefined/unavailable. */
  currentLabel?: MerchantTargetLabel
  viewer?: { actorId: string; roles: string[] }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const g = item.merchant
  const isMid = item.kind === 'MERCHANT_FIRST_MID_ISSUANCE'
  const isActivation = item.kind === 'MERCHANT_PAYMENT_METHOD_ACTIVATION'
  const isSiteReview = item.kind === 'MERCHANT_SITE_REVIEW_APPROVAL'
  const isCurrency = item.kind === 'MERCHANT_CURRENCY_ENABLEMENT'
  const isCodeChange = item.kind === 'MERCHANT_CODE_CHANGE'
  const raw = g?.pinnedTarget ?? item.targetSnapshot
  const pinned = isMid || isActivation || isSiteReview || isCurrency || isCodeChange ? null : pinnedContractOf(raw)
  const codeChange = isCodeChange ? pinnedCodeChangeOf(raw) : null
  const currency = isCurrency ? pinnedCurrencyOf(raw) : null
  const mid = isMid ? pinnedMidOf(raw) : null
  const activation = isActivation ? pinnedActivationOf(raw) : null
  const siteReview = isSiteReview ? pinnedSiteReviewOf(raw) : null
  const text = kindText(item.kind)
  const title = text.title
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
    // ADMIN-UI-WORKSPACE-001(승인함): 시안 상세 카드(.card > .cardtop h4 → .cardbody), 근거 .notice. 승인 ID는 승인함 .detailarea 제목에 있다. 고정 대상 항목·문구·등록자 안내 조건은 그대로.
    <section role="region" aria-label={text.region} className="card">
      <div className="cardtop">
        <h4>{title}</h4>
      </div>
      <div className="cardbody">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className={tagClass(item.status)} title={item.status}>{labelOf(item.status)}</span>
          <p>{statusText(item)}</p>
        </div>
        {flags.length > 0 && <WarningNote>{`조사 필요 — 수동 종료 없음 (${flags.join(', ')})`}</WarningNote>}
        {registrant && item.status === 'PENDING' && <WarningNote>등록자는 승인할 수 없습니다 — 다른 승인자가 처리해야 합니다.</WarningNote>}
        {pinned && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <MerchantLine name={pinned.merchantName} requestCode={requestCodeOf(pinned.merchantCode, item.targetRef)} current={currentLabel} />
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
            <MerchantLine name={mid.merchantName} requestCode={requestCodeOf(mid.merchantCode, item.targetRef)} current={currentLabel} />
            <li>{'계약 · Revision: '}<span className="font-mono text-xs">{`${mid.contractId} #${mid.revisionNo}`}</span></li>
            <li>{`CSA: ${mid.agreementId}`}</li>
            <li>{`PRIMARY Site: ${mid.primarySiteId} (${labelOf(mid.channelType)})`}</li>
            <li>{`요청 시점 MID 수: ${mid.accountCount}`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {activation && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <MerchantLine name={activation.merchantName} requestCode={requestCodeOf(activation.merchantCode, item.targetRef)} current={currentLabel} />
            <li>{'MID: '}<span className="font-mono text-xs">{activation.merchantAccountId}</span></li>
            <li>{'개통: '}<span className="font-mono text-xs">{activation.activationId}</span>{` (${labelOf(activation.paymentMethod)} · 요청 시점 ${labelOf(activation.status)})`}</li>
            <li>{'대표(PRIMARY) Site: '}<span className="font-mono text-xs">{activation.primarySiteId}</span></li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            <li>{'등록자: '}<span className="font-mono text-xs">{activation.registeredBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {siteReview && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <MerchantLine name={siteReview.merchantName} requestCode={requestCodeOf(siteReview.merchantCode, item.targetRef)} current={currentLabel} />
            <li>{'Site: '}<span className="font-mono text-xs">{siteReview.merchantSiteId}</span>{` (${labelOf(siteReview.channelType)})`}</li>
            <li>{`요청 시점 심사 상태: ${labelOf(siteReview.reviewStatus)}`}</li>
            <li>{`사유: ${siteReview.reasonCode === 'REVIEW_PASSED' ? '심사 통과' : siteReview.reasonCode}`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            <li>{'등록자: '}<span className="font-mono text-xs">{siteReview.registeredBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {currency && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <MerchantLine name={currency.merchantName} requestCode={requestCodeOf(currency.merchantCode, item.targetRef)} current={currentLabel} />
            <li>{'MID: '}<span className="font-mono text-xs">{currency.merchantAccountId}</span>{` (요청 시점 ${labelOf(currency.accountLifecycle)})`}</li>
            <li>{`통화: ${currency.currency}`}</li>
            <li className="font-medium">{currencyStartText(currency)}</li>
            <li>{currency.effectiveTo === null ? '종료일: 없음' : `종료일(KST): ${currency.effectiveTo} — 이 날 0시 종료(미포함)`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
          </ul>
        )}
        {/* MC-1b(§5.5): "현재 → 새 코드"(코드는 공개값). 내부 키(mer_…)는 그리지 않는다(D-ADMIN-UI-004 ③). 등록자 안내 없음(Q-MC-10). */}
        {codeChange && (
          <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
            <li>{'가맹점: '}<span className="font-medium">{codeChange.merchantName}</span></li>
            <li className="font-medium">{'코드: '}<span className="font-mono">{`${codeChange.currentCode} → ${codeChange.newCode}`}</span></li>
            <li>{`사유: ${CODE_CHANGE_REASON_TEXT[codeChange.reasonCode] ?? codeChange.reasonCode}`}</li>
            <li>{'요청자: '}{item.requestedByDisplayName} <span className="font-mono text-xs">{item.requestedBy}</span></li>
            {g && <li title={g.executeBefore ?? undefined}>{`실행 기한: ${g.executeBefore ? formatKst(g.executeBefore) : '-'}`}</li>}
            {g && <li>{`마지막 실패 분류: ${g.lastFailure ?? '-'}`}</li>}
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
            <dt className="font-medium">{text.evidence}</dt>
            <dd className="font-mono">{evidence.evidenceRef}</dd>
          </dl>
        )}
      </div>
    </section>
  )
}
