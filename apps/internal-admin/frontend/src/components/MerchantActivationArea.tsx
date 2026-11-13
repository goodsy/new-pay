import { useId, useState, type FormEvent } from 'react'
import type { AdminApi, MerchantDetail, RegistrationOutcome } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { ACTIVATION_REJECTION_MESSAGES, codeOf, ERROR_MESSAGES } from '../errors/messages'
import { labelOf } from '../lib/labels'
import { PAYMENT_METHODS } from '../lib/paymentMethods'
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
  CONTRACT: ['MERCHANT_CONTRACT_APPROVAL', 'MERCHANT_FIRST_MID_ISSUANCE'],
  PM: ['MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_PAYMENT_METHOD_RESUME'],
  SITE_REVIEW: ['MERCHANT_SITE_REVIEW_APPROVAL'],
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
  const route = account.cardRoutes.some((r) => r.status === 'ACTIVE')
  const p = detail.propagation
  const acked = p !== null && p.effectConfirmedAt !== null && p.latestAckRevision === p.latestRevision
  return [
    { key: 'contract', group: '결제 생성 조건', label: '① 계약 적용 중(승인 Revision이 오늘 포함)', state: state(contractApplies) },
    { key: 'review', group: '결제 생성 조건', label: '② 대표 Site 심사 승인', state: state(primarySite?.review?.decision === 'APPROVED') },
    { key: 'method', group: '결제 생성 조건', label: '③ 결제수단(카드) 개통 활성', state: state(methodActive) },
    { key: 'confirmation', group: '결제 생성 조건', label: '④ 대표 Site 적용 확인 유효', state: state(confirmed) },
    { key: 'currency', group: '결제 생성 조건', label: '⑤ 통화(KRW) 활성', state: state(krw), note: krw ? undefined : 'B3-2 통화 활성화 이후' },
    { key: 'route', group: '승인 시작 조건', label: '⑥ 카드 Route 활성', state: state(route), note: route ? undefined : 'B3-3 Route 개통 이후' },
    { key: 'terms', group: '승인 시작 조건', label: '⑦ 수수료·정산 배정·한도', state: 'LATER', note: 'B4 이후' },
    { key: 'propagation', group: '전파', label: '⑧ 최신 권리 revision을 결제 서비스가 수신 확인', state: state(acked) },
  ]
}

const CHECK_TEXT: Record<CheckState, string> = { MET: '충족', NOT_MET: '미충족', LATER: '이후 배치' }

/** 결과 불명(같은 submissionId로 다시 보내 확인) 응답 code. */
const UNKNOWN_CODES: readonly ConsoleErrorCode[] = ['MASTER_OUTCOME_UNKNOWN', 'MASTER_RETRY_SAME_KEY', 'AUDIT_UNAVAILABLE', 'SERVER_ERROR']

const defaultSubmissionId = () => crypto.randomUUID().replace(/-/g, '')

/** 1인 명령 상태(제출 단위 submissionId — 결과 불명이면 같은 값으로 재전송, 결정되면 다음 제출은 새 값). */
type OneShot = { kind: 'idle' } | { kind: 'busy' } | { kind: 'decided'; outcome: RegistrationOutcome } | { kind: 'failed'; code: ConsoleErrorCode } | { kind: 'unknown'; code: ConsoleErrorCode }

function useOneShot(newSubmissionId: () => string, onSessionEnded?: (code: ConsoleErrorCode) => void) {
  const [status, setStatus] = useState<OneShot>({ kind: 'idle' })
  const [submissionId, setSubmissionId] = useState<string | null>(null)
  const run = async (send: (submissionId: string) => Promise<RegistrationOutcome>, onDecided?: (o: RegistrationOutcome) => void) => {
    const id = status.kind === 'unknown' && submissionId !== null ? submissionId : newSubmissionId()
    setSubmissionId(id)
    setStatus({ kind: 'busy' })
    try {
      const outcome = await send(id)
      setStatus({ kind: 'decided', outcome })
      setSubmissionId(null)
      onDecided?.(outcome)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setStatus(UNKNOWN_CODES.includes(code) ? { kind: 'unknown', code } : { kind: 'failed', code })
    }
  }
  return { status, run }
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
function SiteRejectForm({ api, site, newSubmissionId, onDone, onSessionEnded }: {
  api: AdminApi
  site: Site
  newSubmissionId: () => string
  onDone: () => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const [reasonCode, setReasonCode] = useState(SITE_REJECT_REASONS[0]!.code)
  const [evidenceRef, setEvidenceRef] = useState('')
  const [confirming, setConfirming] = useState(false)
  const { status, run } = useOneShot(newSubmissionId, onSessionEnded)
  const evidenceOk = evidenceRef === '' || EVIDENCE_REF.test(evidenceRef)
  const dialogId = useId()
  const send = () => {
    setConfirming(false)
    void run((submissionId) => api.rejectSiteReview({
      submissionId, merchantSiteId: site.merchantSiteId, reasonCode, ...(evidenceRef === '' ? {} : { evidenceRef }),
    }), (o) => { if (o.outcome === 'SUCCEEDED') onDone() })
  }
  return (
    <div className="flex flex-col gap-2" aria-label={`심사 거절 ${site.merchantSiteId}`} role="group">
      <Field label="거절 사유" className="text-xs">
        {(describedBy) => (
          <NativeSelect value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} aria-describedby={describedBy}>
            {SITE_REJECT_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
          </NativeSelect>
        )}
      </Field>
      <Field label="PG 심사 근거(선택)" hint="영문·숫자와 . _ : # / - 만, 1~128자" className="text-xs">
        {(describedBy) => (
          <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} autoComplete="off" aria-describedby={describedBy}
            aria-invalid={!evidenceOk} className="h-8 font-mono text-xs" />
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
      <OneShotResult status={status} success={(ref) => `거절되었습니다 — ${ref}`} />
    </div>
  )
}

/**
 * 가맹점 상세 MID 아래 "개통" 영역(MERCHANT-OPS-002 B3-1 §9.17 — D-ADMIN-UI-003: 배포된 배치의 버튼만). 버튼은 [canOperate](OPERATOR)일 때만 보이며 권위 판정·감사는 서버다.
 * 결제수단(상태·등록자, "개통 등록" 1인 → "개통 승인 요청" 2인), Site 표(심사 상태·등록자, "심사 승인 요청" 2인·"심사 거절" 1인), Site 적용 확인 목록, 개통 점검표(참고용).
 * 등록자를 표시한다 — D-MOPS-009 ③: 등록자는 그 개통·Site 심사의 2인 승인자가 될 수 없다(판정 근거 = 이 값, 권위 판정은 승인 TX1 403·MASTER; 승인함은 등록자에게 안내를 보인다).
 */
export function MerchantActivationArea({ api, detail, account, canOperate, now, onChanged, onRequested, onSessionEnded, newSubmissionId = defaultSubmissionId }: {
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
}) {
  const [requestFor, setRequestFor] = useState<string | null>(null)
  const [rejectFor, setRejectFor] = useState<string | null>(null)
  const register = useOneShot(newSubmissionId, onSessionEnded)
  /** 마지막으로 누른 개통 등록 수단(결과 불명 재전송 버튼 위치 — 리뷰 MINOR-2). */
  const [registerMethod, setRegisterMethod] = useState<string | null>(null)
  const merchantId = detail.merchant.merchantId
  const pmPending = pendingIn(detail, APPROVAL_GROUPS.PM)
  const sitePending = pendingIn(detail, APPROVAL_GROUPS.SITE_REVIEW)
  const primary = openPrimarySite(account, now)
  // ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ⑤): 개통 등록 버튼은 결제수단 목록(PAYMENT_METHODS)에서 그린다 — 지금은 카드 하나. 수단마다 끝나지 않은 개통이 없을 때만.
  const registrable = PAYMENT_METHODS.filter((pm) => !account.paymentMethods.some((m) => m.paymentMethod === pm.code && m.status !== 'TERMINATED'))
  const midActive = account.lifecycle === 'ACTIVE'
  const checklist = activationChecklist(detail, account, now)
  const confirmations = account.siteConfirmations ?? []

  return (
    <section aria-label={`개통 ${account.merchantAccountId}`} className="flex flex-col gap-3 rounded-md border border-dashed p-3">
      <h4 className="text-sm font-semibold">개통</h4>

      <Table aria-label="결제수단 개통">
        <TableHeader>
          <TableRow><TableHead>결제수단</TableHead><TableHead>상태</TableHead><TableHead>등록</TableHead><TableHead>등록자</TableHead>{canOperate && <TableHead><span className="sr-only">작업</span></TableHead>}</TableRow>
        </TableHeader>
        <TableBody>
          {account.paymentMethods.length === 0 && <EmptyRow columns={canOperate ? 5 : 4} message="결제수단 개통이 없습니다." />}
          {account.paymentMethods.map((method) => (
            <TableRow key={method.activationId} aria-label={method.activationId}>
              <TableCell>{labelOf(method.paymentMethod)}</TableCell>
              <TableCell>{method.status === null ? <span className="text-muted-foreground">상태 없음</span> : <StatusBadge code={method.status} />}</TableCell>
              <TableCell><KstTime iso={method.registeredAt} /></TableCell>
              <TableCell><span className="font-mono text-xs">{method.registeredBy ?? '-'}</span></TableCell>
              {canOperate && (
                <TableCell>
                  {method.status === 'UNDER_REVIEW' && midActive && primary !== null && !pmPending && (
                    requestFor === method.activationId ? (
                      <EvidenceRequest
                        name={`개통 승인 요청 ${method.activationId}`}
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
                    setRegisterMethod(pm.code)
                    void register.run((submissionId) => api.registerPaymentMethodActivation({ submissionId, merchantAccountId: account.merchantAccountId, paymentMethod: pm.code }),
                      (o) => { if (o.outcome === 'SUCCEEDED') onChanged() })
                  }}>
                  {unknownHere ? '같은 개통 등록 다시 보내기' : `개통 등록(${pm.label})`}
                </Button>
              )
            })}
          </div>
          <OneShotResult status={register.status} success={(ref) => `개통 등록되었습니다(심사 중) — ${ref}`} />
        </div>
      )}

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
              <TableRow key={site.merchantSiteId} aria-label={`심사 ${site.merchantSiteId}`}>
                <TableCell>
                  <span className="flex flex-col"><span className="font-mono text-xs">{site.merchantSiteId}</span>
                    {site.merchantSiteId === primary && <span className="text-xs text-muted-foreground">대표(PRIMARY)</span>}</span>
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
                              name={`심사 승인 요청 ${site.merchantSiteId}`}
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
                        {rejectFor === site.merchantSiteId ? (
                          <SiteRejectForm api={api} site={site} newSubmissionId={newSubmissionId} onDone={onChanged} onSessionEnded={onSessionEnded} />
                        ) : (
                          <div><Button type="button" variant="ghost" size="sm" onClick={() => setRejectFor(site.merchantSiteId)}>심사 거절</Button></div>
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

      <Table aria-label="Site 적용 확인">
        <TableHeader>
          <TableRow><TableHead>Site</TableHead><TableHead>결제수단</TableHead><TableHead>개통</TableHead><TableHead>확인</TableHead><TableHead>상태</TableHead></TableRow>
        </TableHeader>
        <TableBody>
          {confirmations.length === 0 && <EmptyRow columns={5} message="Site 적용 확인이 없습니다." />}
          {confirmations.map((c) => (
            <TableRow key={c.confirmationId} aria-label={c.confirmationId}>
              <TableCell><span className="font-mono text-xs">{c.merchantSiteId}</span></TableCell>
              <TableCell>{labelOf(c.paymentMethod)}</TableCell>
              <TableCell><span className="font-mono text-xs">{c.activationId}</span></TableCell>
              <TableCell><KstTime iso={c.confirmedAt} /></TableCell>
              <TableCell>{c.revoked ? <span className="text-muted-foreground">철회</span> : <span>유효</span>}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

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
      </section>
    </section>
  )
}
