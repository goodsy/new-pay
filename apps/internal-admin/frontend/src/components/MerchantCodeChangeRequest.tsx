import { useState, type FormEvent } from 'react'
import type { AdminApi, MerchantCodeChangeInput, MerchantDetail } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { cryptoRandomBytes, MERCHANT_CODE, MERCHANT_CODE_HINT, suggestMerchantCode, type RandomBytes } from '../lib/merchantCode'
import { APPROVAL_GROUPS, pendingIn } from './MerchantActivationArea'
import { EVIDENCE_REF } from './ContractApprovalRequest'
import { Field } from './DataBits'
import { ErrorNotice } from './Notice'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { NativeSelect } from './ui/native-select'

/** C-12 변경 사유(Demo 목록 — 운영 사유 체계 아님, 서버도 같은 목록만 받는다). */
export const CODE_CHANGE_REASONS: readonly { code: MerchantCodeChangeInput['reasonCode']; label: string }[] = [
  { code: 'TYPO_CORRECTION', label: '오타 수정' },
  { code: 'MERCHANT_REQUEST', label: '가맹점 요청' },
  { code: 'OTHER_POLICY', label: '그 밖의 정책 사유' },
]

/** 대화상자 안내 문구(§5.5 — D-MCODE-002 Q-MC-7). */
export const CODE_CHANGE_NOTICE = '계약 승인 전 1회만 바꿀 수 있고, 옛 코드는 다시 쓸 수 없습니다.'

/**
 * "코드 변경 요청" 표시 조건(MERCHANT-CODE-001 §5.5 — 표시만, 권위 판정은 서버·MASTER): MASTER `merchantCodeChangeable`(계약 승인 전·변경 0회·ACTIVE — advisory)이고
 * 같은 그룹(계약 승인·첫 MID·코드 변경 — C-15) 승인 대기가 없을 때.
 */
export function mayRequestCodeChange(detail: MerchantDetail): boolean {
  return detail.merchantCodeChangeable === true && !pendingIn(detail, APPROVAL_GROUPS.CONTRACT)
}

const MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  TARGET_NOT_ELIGIBLE_ADVISORY: '지금은 코드를 바꿀 수 없습니다(계약 승인 뒤이거나 이미 한 번 바꿨습니다). 상세를 다시 확인해 주세요.',
  DUPLICATE_UNRESOLVED: '이 가맹점에 아직 끝나지 않은 승인 요청(계약 승인·첫 MID·코드 변경)이 있습니다. 승인함에서 확인해 주세요.',
  INVALID_INPUT: '입력 형식이 올바르지 않습니다(새 코드는 현재 코드와 달라야 하고, 근거 참조는 영문·숫자와 . _ : # / - 만, 1~128자).',
}

/**
 * 가맹점 상세의 "코드 변경 요청" 폼(MERCHANT-CODE-001 MC-1b §5.5 — OPERATOR). 새 코드(직접 입력 또는 "영문 상호" 미리보기 — MC-2 라이브러리 재사용) + 사유 + 근거 참조.
 * 내부 키(merchantId)는 요청 본문에만 쓰고 화면에 그리지 않는다(D-ADMIN-UI-004 ③). 원 가맹점 등록자 안내는 없다(D-MCODE-002 Q-MC-10 — 요청자 ≠ 승인자만).
 */
export function MerchantCodeChangeRequest({ api, detail, onRequested, onSessionEnded, randomBytes = cryptoRandomBytes }: {
  api: AdminApi
  detail: MerchantDetail
  onRequested: (approvalId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  randomBytes?: RandomBytes
}) {
  const current = detail.merchant.merchantCode
  const [englishName, setEnglishName] = useState('')
  const [newCode, setNewCode] = useState('')
  const [reasonCode, setReasonCode] = useState<MerchantCodeChangeInput['reasonCode']>('TYPO_CORRECTION')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const codeOk = MERCHANT_CODE.test(newCode) && newCode !== current
  const valid = codeOk && EVIDENCE_REF.test(evidenceRef)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    setError(null)
    try {
      const { approvalId } = await api.requestMerchantCodeChange({ merchantId: detail.merchant.merchantId, newCode, reasonCode, evidenceRef })
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
    <form onSubmit={submit} role="dialog" aria-label="가맹점 코드 변경 요청" className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2">
      <h4 className="text-sm font-semibold sm:col-span-2">가맹점 코드 변경 요청</h4>
      <p className="text-sm text-muted-foreground sm:col-span-2">{CODE_CHANGE_NOTICE}</p>
      <p className="text-sm sm:col-span-2">{'현재 코드: '}<span className="font-mono">{current}</span></p>
      <Field label="영문 상호(코드 제안용 — 저장 안 함)" className="text-xs">
        {() => (
          <div className="flex gap-2">
            <Input value={englishName} onChange={(e) => setEnglishName(e.target.value)} autoComplete="off" className="h-8 text-xs" />
            <Button type="button" variant="outline" size="sm" onClick={() => setNewCode(suggestMerchantCode(englishName, randomBytes))}>제안</Button>
          </div>
        )}
      </Field>
      <Field label="새 코드" hint={MERCHANT_CODE_HINT} error={newCode !== '' && !codeOk ? (newCode === current ? '현재 코드와 같습니다.' : '형식이 맞지 않습니다.') : undefined} className="text-xs">
        {(describedBy) => (
          <Input value={newCode} onChange={(e) => setNewCode(e.target.value)} autoComplete="off" spellCheck={false} aria-describedby={describedBy} aria-invalid={(newCode !== '' && !codeOk) || undefined} className="h-8 font-mono text-xs" />
        )}
      </Field>
      <Field label="변경 사유" className="text-xs">
        {() => (
          <NativeSelect value={reasonCode} onChange={(e) => setReasonCode(e.target.value as MerchantCodeChangeInput['reasonCode'])}>
            {CODE_CHANGE_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
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
        <Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy}>변경 요청 보내기</Button>
      </div>
    </form>
  )
}
