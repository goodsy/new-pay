import { useMemo, useState, type FormEvent } from 'react'
import type { AdminApi, MerchantDetail } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { EVIDENCE_REF } from './ContractApprovalRequest'
import { Field } from './DataBits'
import { ErrorNotice } from './Notice'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { NativeSelect } from './ui/native-select'

type Revision = MerchantDetail['contracts'][number]['revisions'][number]

/**
 * 발급 근거 후보(승인되고 끝나지 않은 Revision — 화면 안내). 적용 Revision 판정(지금 적용 또는 시작 시 적용 — D-MD-011·015)은 MASTER가 한다(권위, REVISION_NOT_APPLICABLE).
 */
export function issuableRevisions(detail: MerchantDetail, now: Date): { contractId: string; revision: Revision }[] {
  const t = now.getTime()
  return detail.contracts.flatMap((c) => c.revisions
    .filter((r) => r.approvedAt !== null && (r.effectiveTo === null || new Date(r.effectiveTo).getTime() > t))
    .map((revision) => ({ contractId: c.contractId, revision })))
}

/** "첫 MID 발급 요청" 표시 조건(§9.6 — MID 0개, 승인 대기 없음, 발급 근거 Revision·그 계약의 CSA·ACTIVE Site가 있음). 표시만 — 판정·감사는 서버. */
export function mayRequestFirstMid(detail: MerchantDetail, now: Date): boolean {
  // C-35(B3-1): 계약 승인·첫 MID 발급 그룹 대기만 막는다(MID 0개라 B3 대기는 생길 수 없지만 그룹 규칙을 한 곳에 맞춘다).
  if (detail.accounts.length > 0 || (detail.pendingApprovals ?? []).some((p) => p.kind === 'MERCHANT_CONTRACT_APPROVAL' || p.kind === 'MERCHANT_FIRST_MID_ISSUANCE')) return false
  const candidates = issuableRevisions(detail, now)
  return candidates.some(({ contractId }) => (detail.contracts.find((c) => c.contractId === contractId)?.agreements.length ?? 0) > 0) &&
    detail.sites.some((s) => s.lifecycle === 'ACTIVE')
}

const MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  TARGET_NOT_ELIGIBLE_ADVISORY: '지금은 첫 MID를 발급할 수 없습니다(MID 있음·Revision 미승인·CSA·Site 조건). 상세를 다시 확인해 주세요.',
  DUPLICATE_UNRESOLVED: '이 가맹점에 아직 끝나지 않은 승인 요청이 있습니다. 승인함에서 확인해 주세요.',
  INVALID_INPUT: '입력 형식이 올바르지 않습니다(근거 참조는 영문·숫자와 . _ : # / - 만, 1~128자).',
}

/**
 * 조회 상세의 "첫 MID 발급 요청" 폼(MERCHANT-OPS-002 B2-3 §9.6 — OPERATOR). 발급 근거 Revision(승인·적용), 그 계약의 CSA, Scope 안 ACTIVE PRIMARY Site를 고르고 근거 참조를 넣는다
 * (자유 문구 불가). 성공하면 [onRequested](승인 ID) — 결과 표시는 화면이 상세 위에 남긴다(Demo 개선 C-1).
 */
export function FirstMidRequest({ api, detail, now, onRequested, onSessionEnded }: {
  api: AdminApi
  detail: MerchantDetail
  now: Date
  onRequested: (approvalId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const candidates = useMemo(() => issuableRevisions(detail, now), [detail, now])
  const [basis, setBasis] = useState(candidates[0] ? `${candidates[0].contractId}#${candidates[0].revision.revisionNo}` : '')
  const chosen = candidates.find((c) => `${c.contractId}#${c.revision.revisionNo}` === basis)
  const agreements = detail.contracts.find((c) => c.contractId === chosen?.contractId)?.agreements ?? []
  const sites = detail.sites.filter((s) => s.lifecycle === 'ACTIVE' && (chosen?.revision.scopeKind !== 'SITES' || chosen.revision.scopeSiteIds.includes(s.merchantSiteId)))
  const [agreementId, setAgreementId] = useState(agreements[0]?.agreementId ?? '')
  const [siteId, setSiteId] = useState(sites[0]?.merchantSiteId ?? '')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  // B2-3 리뷰 m-3: 선택값이 지금 옵션 목록 안에 있을 때만 보낼 수 있다(Revision을 바꾼 뒤 이전 계약의 CSA·Site가 남지 않게).
  const valid = chosen !== undefined && agreements.some((a) => a.agreementId === agreementId) && sites.some((s) => s.merchantSiteId === siteId) && EVIDENCE_REF.test(evidenceRef)

  /** Revision(발급 근거)을 바꾸면 CSA·PRIMARY Site를 새 옵션 목록의 첫 값으로 다시 맞춘다(리뷰 m-3). */
  const chooseBasis = (value: string) => {
    setBasis(value)
    const next = candidates.find((c) => `${c.contractId}#${c.revision.revisionNo}` === value)
    const nextAgreements = detail.contracts.find((c) => c.contractId === next?.contractId)?.agreements ?? []
    const nextSites = detail.sites.filter((s) => s.lifecycle === 'ACTIVE' && (next?.revision.scopeKind !== 'SITES' || next.revision.scopeSiteIds.includes(s.merchantSiteId)))
    setAgreementId(nextAgreements[0]?.agreementId ?? '')
    setSiteId(nextSites[0]?.merchantSiteId ?? '')
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!valid || busy || chosen === undefined) return
    setBusy(true)
    setError(null)
    try {
      const { approvalId } = await api.requestMerchantFirstMid({
        merchantId: detail.merchant.merchantId, contractId: chosen.contractId, agreementId, revisionNo: chosen.revision.revisionNo, primarySiteId: siteId, evidenceRef,
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
    <form onSubmit={submit} aria-label="첫 MID 발급 요청" className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2">
      <h4 className="text-sm font-semibold sm:col-span-2">첫 MID 발급 요청</h4>
      <Field label="Revision" hint="승인되고 끝나지 않은 Revision만(적용 여부는 MASTER가 판정)" className="text-xs">
        {(describedBy) => (
          <NativeSelect value={basis} onChange={(e) => chooseBasis(e.target.value)} aria-describedby={describedBy}>
            {candidates.map((c) => (
              <option key={`${c.contractId}#${c.revision.revisionNo}`} value={`${c.contractId}#${c.revision.revisionNo}`}>
                {`${c.contractId} #${c.revision.revisionNo} · ${formatKst(c.revision.effectiveFrom).slice(0, 10)} 시작`}
              </option>
            ))}
          </NativeSelect>
        )}
      </Field>
      <Field label="서비스 계약(CSA)" className="text-xs">
        {() => (
          <NativeSelect value={agreementId} onChange={(e) => setAgreementId(e.target.value)}>
            {agreements.map((a) => <option key={a.agreementId} value={a.agreementId}>{`${a.agreementId} · ${labelOf(a.serviceKind)}`}</option>)}
          </NativeSelect>
        )}
      </Field>
      <Field label="PRIMARY Site" hint="계약 범위 안 ACTIVE Site만" className="text-xs">
        {(describedBy) => (
          <NativeSelect value={siteId} onChange={(e) => setSiteId(e.target.value)} aria-describedby={describedBy}>
            {sites.map((s) => <option key={s.merchantSiteId} value={s.merchantSiteId}>{`${s.merchantSiteId} · ${labelOf(s.channelType)}`}</option>)}
          </NativeSelect>
        )}
      </Field>
      <Field label="업무 근거 참조(필수)" hint="영문·숫자와 . _ : # / - 만, 1~128자(자유 문구 불가)" className="text-xs">
        {(describedBy) => (
          <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={evidenceRef !== '' && !EVIDENCE_REF.test(evidenceRef)} className="h-8 font-mono text-xs" />
        )}
      </Field>
      {error && <div className="sm:col-span-2"><ErrorNotice>{MESSAGES[error] ?? ERROR_MESSAGES[error]}</ErrorNotice></div>}
      <div className="sm:col-span-2">
        <Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy}>발급 요청 보내기</Button>
      </div>
    </form>
  )
}
