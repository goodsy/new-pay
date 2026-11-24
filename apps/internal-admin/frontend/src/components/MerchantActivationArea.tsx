import { useEffect, useId, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import type { AdminApi, MerchantDetail, RegistrationOutcome } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { ACTIVATION_REJECTION_MESSAGES, codeOf, ERROR_MESSAGES } from '../errors/messages'
import { labelOf } from '../lib/labels'
import { siteLabel } from '../lib/merchantLabels'
import { PAYMENT_METHODS, type PaymentMethodCode } from '../lib/paymentMethods'
import { CURRENCIES, kstTomorrowOf, nextKstDate, type EffectiveFromMode } from '../lib/currencies'
import { createScreenMemory, type ScreenMemory } from '../lib/screenMemory'
import { isLocked, submissionStatusOf, useUnknownLockedSubmission, type SubmissionStatus } from '../lib/useUnknownLockedSubmission'
import { EVIDENCE_REF } from './ContractApprovalRequest'
import { EmptyRow, Field, KstTime, StatusBadge } from './DataBits'
import { ErrorNotice, WarningNote } from './Notice'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { NativeSelect } from './ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'

type Account = MerchantDetail['accounts'][number]
type Site = MerchantDetail['sites'][number]

/**
 * 승인 대기 그룹(MERCHANT-OPS-002 C-35 — 서버 미해결 키 그룹과 같다). 화면은 같은 그룹이 대기 중일 때 그 그룹의 2인 요청 버튼만 숨긴다.
 * **Site 1인 "심사 거절"은 어떤 대기에도 숨기지 않는다**(안전 방향 — Coordinator 결정, 거절이 먼저면 대기 승인은 MASTER에서 SITE_REVIEW_TRANSITION_NOT_ALLOWED).
 */
export const APPROVAL_GROUPS = {
  // MERCHANT-CODE-001 MC-1b(C-15): 코드 변경은 계약 승인·첫 MID와 같은 'MERCHANT:<mer_>' 그룹.
  CONTRACT: ['MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE', 'MERCHANT_CODE_CHANGE'],
  PM: ['MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_PAYMENT_METHOD_RESUME'],
  SITE_REVIEW: ['MERCHANT_SITE_REVIEW_APPROVAL'],
  CURRENCY: ['MERCHANT_CURRENCY_ENABLEMENT'],
} as const

export function pendingIn(detail: MerchantDetail, group: readonly string[]): boolean {
  return (detail.pendingApprovals ?? []).some((p) => group.includes(p.kind))
}

/** C-37 Site 심사 거절 사유(Demo 목록 — 운영 사유 체계 아님, 서버도 같은 목록만 받는다). */
export const SITE_REJECT_REASONS: readonly { code: string; label: string }[] = [
  { code: 'DOCUMENT_INSUFFICIENT', label: '서류 미비' },
  { code: 'PROHIBITED_BUSINESS', label: '취급 불가 업종' },
  { code: 'SITE_UNREACHABLE', label: 'Site 접속 불가' },
  { code: 'OTHER_POLICY', label: '그 밖의 정책 사유' },
]

const isOpenAt = (from: string, to: string | null, t: number) => new Date(from).getTime() <= t && (to === null || new Date(to).getTime() > t)

/**
 * [now]에 열린 PRIMARY Site(ACTIVE·기간 안, 표시만). 없거나 둘 이상이면 null.
 * MASTER `isOpenAt`과 다름(review m-1): MASTER는 ENDED가 아닌 모든 상태를 열린 것으로 보지만 화면은 ACTIVE만 — 더 보수적(버튼을 덜 보임, fail-closed). 권위 판정은 MASTER.
 */
export function openPrimarySite(account: Account, now: Date): string | null {
  const open = account.assignments.filter((a) => a.role === 'PRIMARY' && a.status === 'ACTIVE' && isOpenAt(a.effectiveFrom, a.effectiveTo, now.getTime()))
  return open.length === 1 ? open[0]!.merchantSiteId : null
}

export type CheckState = 'MET' | 'NOT_MET' | 'LATER'
export interface CheckItem { key: string; group: string; label: string; state: CheckState; note?: string }

/**
 * 개통 점검표(C-43 — **참고용, 권위 아님**): MASTER 상세 값으로 화면이 계산한다. 결제 가능 여부의 권위는 결제 시점 PAYMENT 판정이다(§9.19).
 * ① 계약 적용 중 ② (PRIMARY) Site 심사 APPROVED ③ 수단 ACTIVE ④ PRIMARY 적용 확인 유효 ⑤ 통화 KRW → 결제 생성 조건 / ⑥ 카드 Route ACTIVE ⑦ 수수료·정산·한도(B4) → 승인 시작 조건 /
 * ⑧ 전파: 최신 revision을 PAYMENT가 수신 확인(effectConfirmedAt).
 */
export function activationChecklist(detail: MerchantDetail, account: Account, now: Date): CheckItem[] {
  const t = now.getTime()
  const state = (ok: boolean): CheckState => (ok ? 'MET' : 'NOT_MET')
  const primary = openPrimarySite(account, now)
  const primarySite = detail.sites.find((s) => s.merchantSiteId === primary)
  const contractApplies = detail.contracts.some((c) => c.revisions.some((r) => r.approvedAt !== null && isOpenAt(r.effectiveFrom, r.effectiveTo, t)))
  const methodActive = account.paymentMethods.some((m) => m.paymentMethod === 'CARD' && m.status === 'ACTIVE')
  const confirmed = (account.siteConfirmations ?? []).some((c) => c.merchantSiteId === primary && c.paymentMethod === 'CARD' && !c.revoked)
  const krw = account.currencies.some((c) => c.currencyCode === 'KRW' && c.endedAt === null && isOpenAt(c.effectiveFrom, c.effectiveTo, t))
  // B3-2(D-MOPS-009 ②): 날짜 지정으로 아직 시작 전인 KRW — 미충족이지만 "효력 전"으로 구별한다(§9.24 Demo).
  const krwLater = !krw && account.currencies.some((c) => c.currencyCode === 'KRW' && new Date(c.effectiveFrom).getTime() > t)
  const route = account.cardRoutes.some((r) => r.status === 'ACTIVE')
  const p = detail.propagation
  const acked = p !== null && p.effectConfirmedAt !== null && p.latestAckRevision === p.latestRevision
  return [
    { key: 'contract', group: '결제 생성 조건', label: '① 계약 적용 중(승인 Revision이 오늘 포함)', state: state(contractApplies) },
    { key: 'review', group: '결제 생성 조건', label: '② 대표 Site 심사 승인', state: state(primarySite?.review?.decision === 'APPROVED') },
    { key: 'method', group: '결제 생성 조건', label: '③ 결제수단(카드) 개통 활성', state: state(methodActive) },
    { key: 'confirmation', group: '결제 생성 조건', label: '④ 대표 Site 적용 확인 유효', state: state(confirmed) },
    { key: 'currency', group: '결제 생성 조건', label: '⑤ 통화(KRW) 활성', state: state(krw), note: krw ? undefined : krwLater ? '효력 전' : '통화 활성화 승인 필요' },
    // MERCHANT-IA-001 B1(명세 rev 0.2 §3.6): 작업 배치 이름("B3-3 Route 개통 이후"·"B4 이후")은 운영자에게 뜻이 없어 "준비 전"으로 — 판정(state)은 그대로.
    { key: 'route', group: '승인 시작 조건', label: '⑥ 카드 Route 활성', state: state(route), note: route ? undefined : '준비 전' },
    { key: 'terms', group: '승인 시작 조건', label: '⑦ 수수료·정산 배정·한도', state: 'LATER' },
    { key: 'propagation', group: '전파', label: '⑧ 최신 권리 revision을 결제 서비스가 수신 확인', state: state(acked) },
  ]
}

/** MERCHANT-IA-001 B1: LATER(아직 서버 기능이 없는 항목)는 작업 배치 이름("이후 배치") 대신 "준비 전". */
const CHECK_TEXT: Record<CheckState, string> = { MET: '충족', NOT_MET: '미충족', LATER: '준비 전' }

/** 통화 기록 한 줄의 [now] 기준 상태(표시만 — 권위는 결제 시점 PAYMENT 판정). */
function currencyState(c: Account['currencies'][number], now: Date): '효력 전' | '효력 중' | '종료' {
  const t = now.getTime()
  if (new Date(c.effectiveFrom).getTime() > t) return '효력 전'
  if (c.effectiveTo !== null && new Date(c.effectiveTo).getTime() <= t) return '종료'
  return '효력 중'
}

const ISO_DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/

/**
 * 행 이름(MERCHANT-IA-001 B3): 사람용 이름이 같은 행이 여럿이면 "이름 (n번째)" — 내부 키를 접근 이름에 쓰지 않으면서 행을 구별한다.
 */
function nth<T>(rows: readonly T[], row: T, name: (x: T) => string): string {
  const same = rows.filter((x) => name(x) === name(row))
  return same.length <= 1 ? name(row) : `${name(row)} (${same.indexOf(row) + 1}번째)`
}

/**
 * B3-2 통화 활성화 요청 폼(MERCHANT-OPS-002 §8.4 B3-2-6 — D-MOPS-009 ②, KRW만 C-41). 효력 시작: "승인 실행 즉시"(MASTER가 실행할 때의 시각으로 확정 — 승인 시각이 아니다) 또는
 * "날짜 지정(내일 이후)"(KST 날짜 — 날짜 선택기 최솟값 = 내일 KST, 날짜 없이 보낼 수 없음). 종료일(선택)은 그 날 0시 종료(미포함 — C-7). 근거 = 기관(카드사/VAN) 회신 참조(필수).
 * 날짜 판정은 화면·ADMIN 모두 advisory이고 권위는 MASTER(소급 거절 `CURRENCY_ENABLEMENT_RETROACTIVE`). 입력값은 화면 상태에만(저장소·URL 없음).
 */
function CurrencyRequest({ api, merchantId, account, now, onRequested, onSessionEnded }: {
  api: AdminApi
  merchantId: string
  account: Account
  now: Date
  onRequested: (approvalId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const [mode, setMode] = useState<EffectiveFromMode>('AT_EXECUTION')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const groupName = useId()
  const tomorrow = kstTomorrowOf(now)
  const fromOk = mode === 'AT_EXECUTION' || (ISO_DATE.test(fromDate) && fromDate >= tomorrow)
  const toMin = mode === 'DATE' && fromOk ? nextKstDate(fromDate) : tomorrow
  const toOk = toDate === '' || (ISO_DATE.test(toDate) && toDate >= toMin)
  const evidenceOk = EVIDENCE_REF.test(evidenceRef)
  const valid = evidenceOk && fromOk && toOk
  const currency = CURRENCIES[0]!
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    setError(null)
    try {
      const { approvalId } = await api.requestMerchantCurrencyEnablement({
        merchantId, merchantAccountId: account.merchantAccountId, currency: currency.code, effectiveFromMode: mode,
        ...(mode === 'DATE' ? { effectiveFromDate: fromDate } : {}), ...(toDate === '' ? {} : { effectiveToDate: toDate }), evidenceRef,
      })
      setEvidenceRef('')
      onRequested(approvalId)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    } finally {
      setBusy(false)
    }
  }
  return (
    <form onSubmit={submit} aria-label={`통화 활성화 요청 ${account.merchantAccountId}`} className="flex flex-col gap-2 rounded-md border p-3">
      <p className="text-xs">{`통화: ${currency.label}`}</p>
      <fieldset className="flex flex-col gap-1 text-sm">
        <legend className="text-xs font-medium">효력 시작</legend>
        <label className="flex items-center gap-2">
          <input type="radio" name={groupName} checked={mode === 'AT_EXECUTION'} onChange={() => setMode('AT_EXECUTION')} />
          <span>승인 실행 즉시</span>
        </label>
        <label className="flex items-center gap-2">
          <input type="radio" name={groupName} checked={mode === 'DATE'} onChange={() => setMode('DATE')} />
          <span>날짜 지정(내일 이후)</span>
        </label>
        <p className="text-xs text-muted-foreground">
          {mode === 'AT_EXECUTION' ? '승인 뒤 MASTER가 실제로 실행한 시각부터 효력이 시작됩니다.' : '그 날 00:00(KST)부터 효력이 시작됩니다. 승인 전에 그 시각이 지나면 거절됩니다.'}
        </p>
      </fieldset>
      {mode === 'DATE' && (
        <Field label="효력 시작일(KST)" hint={`${tomorrow} 이후`} className="text-xs">
          {(describedBy) => (
            <Input type="date" min={tomorrow} value={fromDate} onChange={(e) => setFromDate(e.target.value)} aria-describedby={describedBy}
              aria-invalid={fromDate !== '' && !fromOk} className="h-8 text-xs" />
          )}
        </Field>
      )}
      <Field label="종료일(선택)" hint="이 날 0시 종료(미포함) — 비우면 종료 없음" className="text-xs">
        {(describedBy) => (
          <Input type="date" min={toMin} value={toDate} onChange={(e) => setToDate(e.target.value)} aria-describedby={describedBy}
            aria-invalid={!toOk} className="h-8 text-xs" />
        )}
      </Field>
      <Field label="기관(카드사/VAN) 회신 참조(필수)" hint="카드사·VAN 회신 번호 — 영문·숫자와 . _ : # / - 만, 1~128자(자유 문구 불가)" className="text-xs">
        {(describedBy) => (
          <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} autoComplete="off" aria-describedby={describedBy}
            aria-invalid={evidenceRef !== '' && !evidenceOk} className="h-8 font-mono text-xs" />
        )}
      </Field>
      {error && <ErrorNotice>{CURRENCY_REQUEST_MESSAGES[error] ?? REQUEST_MESSAGES[error] ?? ERROR_MESSAGES[error]}</ErrorNotice>}
      <div><Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy}>승인 요청 보내기</Button></div>
    </form>
  )
}

const defaultSubmissionId = () => crypto.randomUUID().replace(/-/g, '')

/** 1인 명령 상태(제출 단위 submissionId — 결과 불명이면 같은 값으로 재전송, 결정되면 다음 제출은 새 값). */
type OneShot = SubmissionStatus<RegistrationOutcome>

/** 개통 1인 명령의 App 메모리 키(LOCK-UNIFY-001 — 상세를 닫거나 탭을 바꿔도 결과 불명 잠금·submissionId 유지, [hasUnresolvedRequest]가 본다). */
const registerKey = (merchantAccountId: string) => `activation.${merchantAccountId}.register`
const siteRejectKey = (merchantSiteId: string) => `activation.siteReject.${merchantSiteId}`

/**
 * 1인 명령 하나(MERCHANT-OPS-002 §4.2·§5.3 — 공용 [useUnknownLockedSubmission]): 상태·보낸 본문은 [memory]에.
 * 화면이 사라질 때 잠기지 않은 지난 결과(결정·확정 실패)는 지운다 — 다시 열면 새로 시작(잠긴 결과만 남는다).
 */
function useOneShot<P>(memory: ScreenMemory, key: string, newSubmissionId: () => string, onSessionEnded?: (code: ConsoleErrorCode) => void) {
  const shot = useUnknownLockedSubmission<P, RegistrationOutcome>(memory, key, newSubmissionId, onSessionEnded)
  const { resetIfUnlocked } = shot
  const resetRef = useRef(resetIfUnlocked)
  resetRef.current = resetIfUnlocked
  useEffect(() => () => resetRef.current(), [])
  return shot
}

/** 결과 불명으로 잠긴 Site 거절(있으면 그 Site ID — 다시 열면 그 폼을 연다, 다른 Site 거절은 막는다). */
function useLockedSiteReject(memory: ScreenMemory, siteIds: readonly string[]): string | null {
  return useSyncExternalStore(memory.subscribe, () => siteIds.find((siteId) => isLocked(submissionStatusOf(memory, siteRejectKey(siteId)))) ?? null)
}

function OneShotResult({ status, success }: { status: OneShot; success: (reference: string) => string }) {
  if (status.kind === 'decided') {
    return status.outcome.outcome === 'SUCCEEDED' ? (
      <p role="status" className="text-xs">{success(status.outcome.reference ?? '-')}</p>
    ) : (
      <ErrorNotice>{ACTIVATION_REJECTION_MESSAGES[status.outcome.rejection ?? ''] ?? '처리가 거절되었습니다.'}</ErrorNotice>
    )
  }
  if (status.kind === 'unknown') return <WarningNote>{`${ERROR_MESSAGES[status.code]} — 같은 요청으로 다시 보내 결과를 확인하세요.`}</WarningNote>
  if (status.kind === 'failed') return <ErrorNotice>{ERROR_MESSAGES[status.code]}</ErrorNotice>
  return null
}

const REQUEST_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  TARGET_NOT_ELIGIBLE_ADVISORY: '지금은 승인 요청할 수 없는 상태입니다. 상세를 다시 확인해 주세요.',
  DUPLICATE_UNRESOLVED: '같은 종류의 승인 요청이 이미 대기 중입니다. 승인함에서 확인해 주세요.',
  INVALID_INPUT: '근거 참조 형식이 올바르지 않습니다(영문·숫자와 . _ : # / - 만, 1~128자, 공백 없음).',
}

/** B3-2 통화 요청 400(입력 규칙 — 날짜·모드·통화 포함). */
const CURRENCY_REQUEST_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  INVALID_INPUT: '입력이 올바르지 않습니다(시작일은 내일 이후, 종료일은 시작 뒤, 근거 참조 형식 확인).',
}

/** 2인 승인 요청 폼(근거 참조 필수 — 라벨이 의미를 말한다: 기관 회신 참조 / PG 심사 근거, minor-5). 근거는 화면 상태에만(저장소·URL 없음). */
function EvidenceRequest({ name, evidenceLabel, hint, send, onRequested, onSessionEnded }: {
  name: string
  evidenceLabel: string
  hint: string
  send: (evidenceRef: string) => Promise<{ approvalId: string }>
  onRequested: (approvalId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const [evidenceRef, setEvidenceRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const valid = EVIDENCE_REF.test(evidenceRef)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    setError(null)
    try {
      const { approvalId } = await send(evidenceRef)
      setEvidenceRef('')
      onRequested(approvalId)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    } finally {
      setBusy(false)
    }
  }
  return (
    <form onSubmit={submit} aria-label={name} className="flex flex-col gap-2">
      <Field label={`${evidenceLabel}(필수)`} hint={hint} className="text-xs">
        {(describedBy) => (
          <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} autoComplete="off" aria-describedby={describedBy}
            aria-invalid={evidenceRef !== '' && !valid} className="h-8 font-mono text-xs" />
        )}
      </Field>
      {error && <ErrorNotice>{REQUEST_MESSAGES[error] ?? ERROR_MESSAGES[error]}</ErrorNotice>}
      <div><Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy}>승인 요청 보내기</Button></div>
    </form>
  )
}

/** Site 1인 심사 거절(되돌릴 수 없음 — 사유 코드 선택, 근거 참조 선택, 확인 대화상자). */
type SiteRejectBody = { merchantSiteId: string; reasonCode: string; evidenceRef?: string }

function SiteRejectForm({ api, site, label, memory, newSubmissionId, onDone, onSessionEnded }: {
  api: AdminApi
  site: Site
  /** 화면 이름(채널 식별값 — B3). 메모리 키·호출 인자는 site.merchantSiteId. */
  label: string
  /** 결과 불명 잠금·보낸 본문을 둘 App 메모리(다른 Site 거절 열기 차단은 부모가 이 메모리로 본다 — 리뷰 MINOR-1). */
  memory: ScreenMemory
  newSubmissionId: () => string
  onDone: () => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const [reasonInput, setReasonCode] = useState(SITE_REJECT_REASONS[0]!.code)
  const [evidenceInput, setEvidenceRef] = useState('')
  const [confirming, setConfirming] = useState(false)
  const { status, pending, locked: inputLocked, run } = useOneShot<SiteRejectBody>(memory, siteRejectKey(site.merchantSiteId), newSubmissionId, onSessionEnded)
  // §5.3: 보내는 중·결과 불명이면 입력을 잠근다 — 같은 submissionId 재전송은 같은 본문이어야 한다(바뀌면 409). 잠긴 동안은 보낸 본문을 보인다(다시 연 폼 포함).
  const sent = inputLocked && pending !== null ? pending.payload : null
  const reasonCode = sent ? sent.reasonCode : reasonInput
  const evidenceRef = sent ? (sent.evidenceRef ?? '') : evidenceInput
  const evidenceOk = evidenceRef === '' || EVIDENCE_REF.test(evidenceRef)
  const dialogId = useId()
  const send = () => {
    setConfirming(false)
    void run({ merchantSiteId: site.merchantSiteId, reasonCode, ...(evidenceRef === '' ? {} : { evidenceRef }) },
      (submissionId, body) => api.rejectSiteReview({ submissionId, ...body }),
      (o) => { if (o.outcome === 'SUCCEEDED') onDone() })
  }
  return (
    <div className="flex flex-col gap-2" aria-label={`심사 거절 ${label}`} role="group">
      <Field label="거절 사유" className="text-xs">
        {(describedBy) => (
          <NativeSelect value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} aria-describedby={describedBy} disabled={inputLocked}>
            {SITE_REJECT_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
          </NativeSelect>
        )}
      </Field>
      <Field label="PG 심사 근거(선택)" hint="영문·숫자와 . _ : # / - 만, 1~128자" className="text-xs">
        {(describedBy) => (
          <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} autoComplete="off" aria-describedby={describedBy}
            aria-invalid={!evidenceOk} disabled={inputLocked} className="h-8 font-mono text-xs" />
        )}
      </Field>
      {confirming ? (
        <div role="alertdialog" aria-label="심사 거절 확인" aria-describedby={dialogId} className="flex flex-col gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
          <p id={dialogId} className="font-medium">되돌릴 수 없습니다. 재신청은 새 Site로 해야 합니다.</p>
          <div className="flex gap-2">
            <Button type="button" variant="destructive" size="sm" onClick={send}>거절 확정</Button>
            <Button type="button" variant="outline" size="sm" onClick={() => setConfirming(false)}>취소</Button>
          </div>
        </div>
      ) : (
        <div>
          <Button type="button" variant="destructive" size="sm" disabled={!evidenceOk || status.kind === 'busy'} aria-busy={status.kind === 'busy'} onClick={() => setConfirming(true)}>
            {status.kind === 'unknown' ? '같은 거절 다시 보내기' : '심사 거절'}
          </Button>
        </div>
      )}
      {/* MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③ — 내부 키는 호출 인자·메모리 키로만, DOM에는 사람용 값): 결과 참조(<site 키>#n)는 보이지 않는다. */}
      <OneShotResult status={status} success={() => '거절되었습니다'} />
    </div>
  )
}

/**
 * 가맹점 Site 심사 표(MERCHANT-OPS-002 B3-1 §9.17 — "심사 승인 요청" 2인·"심사 거절" 1인). 버튼은 [canOperate](OPERATOR)일 때만 보이며 권위 판정·감사는 서버다.
 * MERCHANT-IA-001 B1(명세 rev 0.2 §7 B1, "한 사실은 한 곳에"): Site 심사는 가맹점 단위라 MID "개통" 영역마다 반복하지 않고 가맹점 상세에서 한 번 그린다(B4 탭 전까지 Site 패널 안).
 * 동작은 개통 영역 안에 있던 때와 같다 — 같은 그룹 대기면 2인 버튼만 숨기고 1인 거절은 숨기지 않는다(C-35), 거절 확인 대화상자, 결과 불명 잠금 메모리 키
 * `activation.siteReject.<site>`(LOCK-UNIFY-001 — App 메모리, 다시 연 상세에서 잠긴 폼을 연다, 그 동안 다른 Site 거절은 막는다).
 * 등록자를 표시한다 — D-MOPS-009 ③: 등록자는 그 Site 심사의 2인 승인자가 될 수 없다(판정 근거 = 이 값, 권위 판정은 승인 TX1 403·MASTER).
 * "대표(PRIMARY)"는 [now]에 어느 MID의 열린 대표 Site인지로 표시한다(표시만 — 버튼 조건이 아니다).
 */
export function SiteReviewTable({ api, detail, canOperate, now, onChanged, onRequested, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected }: {
  api: AdminApi
  detail: MerchantDetail
  canOperate: boolean
  now: Date
  /** 1인 명령 성공 뒤 상세 다시 읽기. */
  onChanged: () => void
  /** 2인 승인 요청 성공(승인 ID, 무엇). */
  onRequested: (approvalId: string, what: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
  /** 결과 불명 잠금·submissionId를 둘 App 메모리(LOCK-UNIFY-001). 없으면 이 표 자체 메모리. */
  memory?: ScreenMemory
}) {
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  const [requestFor, setRequestFor] = useState<string | null>(null)
  const [rejectFor, setRejectFor] = useState<string | null>(null)
  // 리뷰 MINOR-1: 결과 불명으로 잠긴 거절이 있으면 그 폼을 열어 두고(다시 연 상세 포함) 다른 Site 거절은 막는다 — 열 수 있는(actionable) Site 중에서만 본다.
  const lockedReject = useLockedSiteReject(memory, detail.sites.filter((s) => s.lifecycle === 'ACTIVE' && (s.review === null || s.review.decision === 'SUSPENDED')).map((s) => s.merchantSiteId))
  const rejectLocked = lockedReject !== null
  const openReject = lockedReject ?? rejectFor
  // 다시 연 잠긴 폼이 결정된 뒤에도 그 결과를 보이게 열린 폼으로 이어 둔다.
  useEffect(() => {
    if (lockedReject !== null) setRejectFor(lockedReject)
  }, [lockedReject])
  const merchantId = detail.merchant.merchantId
  const sitePending = pendingIn(detail, APPROVAL_GROUPS.SITE_REVIEW)
  const primaries = new Set(detail.accounts.map((account) => openPrimarySite(account, now)).filter((site): site is string => site !== null))

  return (
      <Table aria-label="Site 심사">
        <TableHeader>
          <TableRow><TableHead>Site</TableHead><TableHead>상태</TableHead><TableHead>심사</TableHead><TableHead>등록자</TableHead>{canOperate && <TableHead><span className="sr-only">작업</span></TableHead>}</TableRow>
        </TableHeader>
        <TableBody>
          {detail.sites.length === 0 && <EmptyRow columns={canOperate ? 5 : 4} message="등록된 Site가 없습니다." />}
          {detail.sites.map((site) => {
            const reviewOpen = site.review === null || site.review.decision === 'SUSPENDED'
            const actionable = site.lifecycle === 'ACTIVE' && reviewOpen
            return (
              <TableRow key={site.merchantSiteId} aria-label={`심사 ${siteLabel(detail, site.merchantSiteId)}`}>
                <TableCell>
                  {/* MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③ — 내부 키는 호출 인자·메모리 키로만, DOM에는 사람용 값): Site 키 대신 채널 식별값. */}
                  <span className="flex flex-col"><span className="font-mono text-xs">{siteLabel(detail, site.merchantSiteId)}</span>
                    {primaries.has(site.merchantSiteId) && <span className="text-xs text-muted-foreground">대표(PRIMARY)</span>}</span>
                </TableCell>
                <TableCell><StatusBadge code={site.lifecycle} /></TableCell>
                <TableCell>{site.review === null ? <StatusBadge code="PENDING" /> : <StatusBadge code={site.review.decision} />}</TableCell>
                <TableCell><span className="font-mono text-xs">{site.registeredBy ?? '-'}</span></TableCell>
                {canOperate && (
                  <TableCell>
                    {actionable && (
                      <div className="flex flex-col gap-2">
                        {!sitePending && (
                          requestFor === site.merchantSiteId ? (
                            <EvidenceRequest
                              name={`심사 승인 요청 ${siteLabel(detail, site.merchantSiteId)}`}
                              evidenceLabel="PG 심사 근거"
                              hint="PG 심사 기록 번호 — 영문·숫자와 . _ : # / - 만, 1~128자. 사유: 심사 통과(REVIEW_PASSED)"
                              send={(evidenceRef) => api.requestMerchantSiteReviewApproval({ merchantId, merchantSiteId: site.merchantSiteId, reasonCode: 'REVIEW_PASSED', evidenceRef })}
                              onRequested={(id) => { setRequestFor(null); onRequested(id, 'Site 심사 승인') }}
                              onSessionEnded={onSessionEnded}
                            />
                          ) : (
                            <div><Button type="button" variant="outline" size="sm" onClick={() => setRequestFor(site.merchantSiteId)}>심사 승인 요청</Button></div>
                          )
                        )}
                        {openReject === site.merchantSiteId ? (
                          <SiteRejectForm api={api} site={site} label={siteLabel(detail, site.merchantSiteId)} memory={memory} newSubmissionId={newSubmissionId} onDone={onChanged} onSessionEnded={onSessionEnded} />
                        ) : (
                          // 리뷰 MINOR-1: 열린 거절이 보내는 중·결과 불명이면 다른 Site 거절을 열 수 없다(열면 그 폼이 사라져 같은 submissionId 재전송을 잃는다).
                          <div><Button type="button" variant="ghost" size="sm" disabled={rejectLocked} onClick={() => setRejectFor(site.merchantSiteId)}>심사 거절</Button></div>
                        )}
                      </div>
                    )}
                  </TableCell>
                )}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
  )
}

/**
 * 가맹점 상세 MID 아래 "개통" 영역(MERCHANT-OPS-002 B3-1 §9.17 — D-ADMIN-UI-003: 배포된 배치의 버튼만). 버튼은 [canOperate](OPERATOR)일 때만 보이며 권위 판정·감사는 서버다.
 * 결제수단(상태·등록자, "개통 등록" 1인 → "개통 승인 요청" 2인), Site 적용 확인 목록, 통화, 개통 점검표(참고용). Site 심사 표는 MERCHANT-IA-001 B1부터 가맹점에서 한 번 —
 * [SiteReviewTable].
 * 등록자를 표시한다 — D-MOPS-009 ③: 등록자는 그 개통의 2인 승인자가 될 수 없다(판정 근거 = 이 값, 권위 판정은 승인 TX1 403·MASTER; 승인함은 등록자에게 안내를 보인다).
 */
export function MerchantActivationArea({ api, detail, account, canOperate, now, onChanged, onRequested, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected }: {
  api: AdminApi
  detail: MerchantDetail
  account: Account
  canOperate: boolean
  now: Date
  /** 1인 명령 성공 뒤 상세 다시 읽기. */
  onChanged: () => void
  /** 2인 승인 요청 성공(승인 ID, 무엇). */
  onRequested: (approvalId: string, what: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
  /** 1인 명령 결과 불명 잠금·submissionId를 둘 App 메모리(LOCK-UNIFY-001 — 상세 닫기·탭 전환에도 유지). 없으면 이 영역 자체 메모리. */
  memory?: ScreenMemory
}) {
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  const [requestFor, setRequestFor] = useState<string | null>(null)
  const register = useOneShot<{ paymentMethod: PaymentMethodCode }>(memory, registerKey(account.merchantAccountId), newSubmissionId, onSessionEnded)
  /** 마지막으로 누른 개통 등록 수단(결과 불명 재전송 버튼 위치 — 리뷰 MINOR-2): 보낸 본문의 수단. */
  const registerMethod = register.pending?.payload.paymentMethod ?? null
  const merchantId = detail.merchant.merchantId
  const pmPending = pendingIn(detail, APPROVAL_GROUPS.PM)
  const currencyPending = pendingIn(detail, APPROVAL_GROUPS.CURRENCY)
  const [currencyRequest, setCurrencyRequest] = useState(false)
  // B3-2: 같은 통화(KRW)의 끝나지 않은 기록(효력 전 포함)이 있으면 요청 버튼을 숨긴다(서버 미리 보기와 같은 판정 — 권위는 MASTER 중첩 판정).
  const unendedKrw = account.currencies.some((c) => c.currencyCode === 'KRW' && (c.effectiveTo === null || new Date(c.effectiveTo).getTime() > now.getTime()))
  const primary = openPrimarySite(account, now)
  // ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ⑤): 개통 등록 버튼은 결제수단 목록(PAYMENT_METHODS)에서 그린다 — 지금은 카드 하나. 수단마다 끝나지 않은 개통이 없을 때만.
  const registrable = PAYMENT_METHODS.filter((pm) => !account.paymentMethods.some((m) => m.paymentMethod === pm.code && m.status !== 'TERMINATED'))
  const midActive = account.lifecycle === 'ACTIVE'
  const checklist = activationChecklist(detail, account, now)
  const confirmations = account.siteConfirmations ?? []
  const methodLabel = (method: Account['paymentMethods'][number]) => nth(account.paymentMethods, method, (x) => labelOf(x.paymentMethod))

  return (
    <section aria-label={`개통 ${account.merchantAccountId}`} className="flex flex-col gap-3 rounded-md border border-dashed p-3">
      <h4 className="text-sm font-semibold">개통</h4>

      <Table aria-label="결제수단 개통">
        <TableHeader>
          <TableRow><TableHead>결제수단</TableHead><TableHead>상태</TableHead><TableHead>등록</TableHead><TableHead>등록자</TableHead>{canOperate && <TableHead><span className="sr-only">작업</span></TableHead>}</TableRow>
        </TableHeader>
        <TableBody>
          {account.paymentMethods.length === 0 && <EmptyRow columns={canOperate ? 5 : 4} message="결제수단 개통이 없습니다." />}
          {/* MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③ — 내부 키는 호출 인자·메모리 키로만, DOM에는 사람용 값): 개통 키(act_) 대신 결제수단 이름(같은 수단이 여럿이면 순서). */}
          {account.paymentMethods.map((method) => (
            <TableRow key={method.activationId} aria-label={methodLabel(method)}>
              <TableCell>{labelOf(method.paymentMethod)}</TableCell>
              <TableCell>{method.status === null ? <span className="text-muted-foreground">상태 없음</span> : <StatusBadge code={method.status} />}</TableCell>
              <TableCell><KstTime iso={method.registeredAt} /></TableCell>
              <TableCell><span className="font-mono text-xs">{method.registeredBy ?? '-'}</span></TableCell>
              {canOperate && (
                <TableCell>
                  {method.status === 'UNDER_REVIEW' && midActive && primary !== null && !pmPending && (
                    requestFor === method.activationId ? (
                      <EvidenceRequest
                        name={`개통 승인 요청 ${methodLabel(method)}`}
                        evidenceLabel="기관(카드사/VAN) 회신 참조"
                        hint="카드사·VAN 심사 회신 번호 — 영문·숫자와 . _ : # / - 만, 1~128자(자유 문구 불가)"
                        send={(evidenceRef) => api.requestMerchantPaymentMethodActivation({ merchantId, merchantAccountId: account.merchantAccountId, activationId: method.activationId, evidenceRef })}
                        onRequested={(id) => { setRequestFor(null); onRequested(id, '결제수단 개통 승인') }}
                        onSessionEnded={onSessionEnded}
                      />
                    ) : (
                      <Button type="button" variant="outline" size="sm" onClick={() => setRequestFor(method.activationId)}>개통 승인 요청</Button>
                    )
                  )}
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {canOperate && midActive && registrable.length > 0 && (
        <div className="flex flex-col gap-1">
          <div className="flex flex-wrap gap-2">
            {registrable.map((pm) => {
              // 리뷰 MINOR-2: one-shot(submissionId) 하나를 모든 수단 버튼이 함께 쓴다 — 결과 불명이면 그 수단 버튼만 "같은 개통 등록 다시 보내기"로 남기고
              // 다른 수단 버튼은 막는다(다른 수단이 같은 submissionId로 나가지 않게). 둘째 수단을 실제로 넣기 전 수단별 one-shot으로 나눠야 한다(paymentMethods.ts).
              const unknownHere = register.status.kind === 'unknown' && registerMethod === pm.code
              const blocked = register.status.kind === 'busy' || (register.status.kind === 'unknown' && registerMethod !== pm.code)
              return (
                <Button key={pm.code} type="button" variant="outline" size="sm" disabled={blocked} aria-busy={register.status.kind === 'busy' && registerMethod === pm.code}
                  onClick={() => {
                    void register.run({ paymentMethod: pm.code },
                      (submissionId, { paymentMethod }) => api.registerPaymentMethodActivation({ submissionId, merchantAccountId: account.merchantAccountId, paymentMethod }),
                      (o) => { if (o.outcome === 'SUCCEEDED') onChanged() })
                  }}>
                  {unknownHere ? '같은 개통 등록 다시 보내기' : `개통 등록(${pm.label})`}
                </Button>
              )
            })}
          </div>
          <OneShotResult status={register.status} success={() => '개통 등록되었습니다(심사 중)'} />
        </div>
      )}

      <Table aria-label="Site 적용 확인">
        <TableHeader>
          {/* MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③ — 내부 키는 호출 인자·메모리 키로만, DOM에는 사람용 값): '개통'(act_) 열을 뺐다 — 결제수단 열이 같은 뜻. Site는 채널 식별값. */}
          <TableRow><TableHead>Site</TableHead><TableHead>결제수단</TableHead><TableHead>확인</TableHead><TableHead>상태</TableHead></TableRow>
        </TableHeader>
        <TableBody>
          {confirmations.length === 0 && <EmptyRow columns={4} message="Site 적용 확인이 없습니다." />}
          {confirmations.map((c) => (
            <TableRow key={c.confirmationId} aria-label={nth(confirmations, c, (x) => `${siteLabel(detail, x.merchantSiteId)} ${labelOf(x.paymentMethod)}`)}>
              <TableCell><span className="font-mono text-xs">{siteLabel(detail, c.merchantSiteId)}</span></TableCell>
              <TableCell>{labelOf(c.paymentMethod)}</TableCell>
              <TableCell><KstTime iso={c.confirmedAt} /></TableCell>
              <TableCell>{c.revoked ? <span className="text-muted-foreground">철회</span> : <span>유효</span>}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Table aria-label="통화">
        <TableHeader>
          <TableRow><TableHead>통화</TableHead><TableHead>효력 시작</TableHead><TableHead>종료</TableHead><TableHead>종료 처리</TableHead><TableHead>상태</TableHead></TableRow>
        </TableHeader>
        <TableBody>
          {account.currencies.length === 0 && <EmptyRow columns={5} message="통화 활성화가 없습니다." />}
          {account.currencies.map((c) => (
            <TableRow key={c.enablementId} aria-label={nth(account.currencies, c, (x) => x.currencyCode)}>
              <TableCell>{c.currencyCode}</TableCell>
              <TableCell><KstTime iso={c.effectiveFrom} /></TableCell>
              <TableCell>{c.effectiveTo === null ? <span className="text-muted-foreground">없음</span> : <KstTime iso={c.effectiveTo} />}</TableCell>
              {/* MERCHANT-IA-001 B1: 가맹점 상세에 따로 있던 같은 통화 표(효력 기간·종료 처리)를 지우고, 그 표에만 있던 '종료 처리'(종료를 기록한 시각) 열을 여기로 옮겼다. */}
              <TableCell>{c.endedAt === null ? <span className="text-muted-foreground">-</span> : <KstTime iso={c.endedAt} />}</TableCell>
              <TableCell>{currencyState(c, now)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {canOperate && midActive && !currencyPending && !unendedKrw && (
        currencyRequest ? (
          <CurrencyRequest api={api} merchantId={merchantId} account={account} now={now} onSessionEnded={onSessionEnded}
            onRequested={(id) => { setCurrencyRequest(false); onRequested(id, '통화 활성화') }} />
        ) : (
          <div><Button type="button" variant="outline" size="sm" onClick={() => setCurrencyRequest(true)}>통화 활성화 요청</Button></div>
        )
      )}

      <section aria-label="개통 점검표" className="flex flex-col gap-2 rounded-md bg-muted/40 p-3 text-sm">
        <h5 className="font-semibold">개통 점검표</h5>
        <p className="text-xs text-muted-foreground">이 표는 참고용입니다. 실제 판정은 결제 시점에 PAYMENT가 합니다.</p>
        <ul className="flex flex-col gap-1">
          {checklist.map((item) => (
            <li key={item.key} aria-label={item.label} data-state={item.state} className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-muted-foreground">{item.group}</span>
              <span>{item.label}</span>
              <span className={item.state === 'MET' ? 'font-medium text-success' : 'text-muted-foreground'}>{CHECK_TEXT[item.state]}</span>
              {item.note && <span className="text-xs text-muted-foreground">{`(${item.note})`}</span>}
            </li>
          ))}
        </ul>
        <p className="text-xs">{`결제 생성 조건: ${checklist.filter((c) => c.group === '결제 생성 조건').every((c) => c.state === 'MET') ? '충족' : '미충족'}(참고)`}</p>
        {/* MERCHANT-IA-001 B1(명세 rev 0.2 §3.6): "승인 시작 조건: Route·수수료·정산·한도 필요(B3-3·B4)" 줄은 항목 ⑥⑦과 같은 말이라 뺐다. L-17(새 MID는 가맹점 API
            Credential이 없어 Demo 결제 호출 불가) 안내는 화면이 Demo/운영을 구별할 값이 없어 환경을 말하지 않는 사실 문구로 바꿨다 — API 키 발급 화면은 관리자 화면에 없다. */}
        <p className="text-xs text-muted-foreground">가맹점의 결제 호출에는 API 키가 따로 필요하며, 이 화면에서 발급하지 않습니다.</p>
      </section>
    </section>
  )
}
