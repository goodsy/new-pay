import { useId, useState, type FormEvent } from 'react'
import type { AdminApi } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { Field } from './DataBits'
import { ErrorNotice } from './Notice'
import { Button } from './ui/button'
import { Input } from './ui/input'

/** 근거 참조 형식(재매입 `evidence_ref`와 같다 — D-MOPS-008 ①). 화면은 안내만, 권위 검사는 서버(앞뒤 공백도 자르지 않고 서버가 거부). */
export const EVIDENCE_REF = /^[A-Za-z0-9._:#/-]{1,128}$/

/** 계약 승인 요청 거절 → 이 화면 문구(일반 문구가 재매입·보정 취소용이라 여기서 바꾼다). */
const REQUEST_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  TARGET_NOT_ELIGIBLE_ADVISORY: '이 Revision은 지금 승인 요청할 수 없습니다(이미 승인됐거나 효력이 끝났거나 없음). 상세를 다시 확인해 주세요.',
  DUPLICATE_UNRESOLVED: '이 가맹점에 아직 끝나지 않은 승인 요청이 있습니다. 승인함에서 확인해 주세요.',
  INVALID_INPUT: '근거 참조 형식이 올바르지 않습니다(영문·숫자와 . _ : # / - 만, 1~128자, 공백 없음).',
}

/**
 * 조회 상세 Revision 행의 "승인 요청" 폼(MERCHANT-OPS-002 B2-2 §9.6 — OPERATOR, 표시만; 판정·감사는 서버). 근거 참조 입력 필수(자유 문구 불가 — 형식 안내).
 * 성공하면 승인 ID와 "승인함에서 보기"를 보이고 [onRequested]로 상세를 다시 읽게 한다(승인 대기 표시). 근거 참조는 화면 상태에만 두고 저장소·URL에 남기지 않는다.
 */
export function ContractApprovalRequest({ api, merchantId, contractId, revisionNo, onRequested, onOpenApprovals, onSessionEnded }: {
  api: AdminApi
  merchantId: string
  contractId: string
  revisionNo: number
  onRequested?: (approvalId: string) => void
  onOpenApprovals?: () => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const [evidenceRef, setEvidenceRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [created, setCreated] = useState<string | null>(null)
  const hintId = useId()
  const valid = EVIDENCE_REF.test(evidenceRef)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    setError(null)
    try {
      const { approvalId } = await api.requestMerchantContractApproval({ merchantId, contractId, revisionNo, evidenceRef })
      setEvidenceRef('')
      setCreated(approvalId)
      onRequested?.(approvalId)
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    } finally {
      setBusy(false)
    }
  }

  if (created) {
    return (
      <div role="status" aria-label={`승인 요청 완료 revision ${revisionNo}`} className="flex flex-wrap items-center gap-2 text-sm">
        <span>승인 요청을 만들었습니다</span>
        <span className="font-mono text-xs">{created}</span>
        {onOpenApprovals && <Button type="button" variant="outline" size="sm" onClick={onOpenApprovals}>승인함에서 보기</Button>}
      </div>
    )
  }

  return (
    <form onSubmit={submit} aria-label={`승인 요청 revision ${revisionNo}`} className="flex flex-col gap-2">
      <Field label="업무 근거 참조(필수)" hint="계약서 번호 등 — 영문·숫자와 . _ : # / - 만, 1~128자(자유 문구 불가)" className="text-xs">
        {(describedBy) => (
          <Input
            value={evidenceRef}
            onChange={(e) => setEvidenceRef(e.target.value)}
            autoComplete="off"
            aria-describedby={[describedBy, hintId].filter(Boolean).join(' ')}
            aria-invalid={evidenceRef !== '' && !valid}
            className="h-8 font-mono text-xs"
          />
        )}
      </Field>
      {evidenceRef !== '' && !valid && <p id={hintId} className="text-xs text-destructive">형식이 맞지 않습니다(공백·한글·따옴표 불가).</p>}
      {error && <ErrorNotice>{REQUEST_MESSAGES[error] ?? ERROR_MESSAGES[error]}</ErrorNotice>}
      <div>
        <Button type="submit" size="sm" disabled={!valid || busy} aria-busy={busy}>승인 요청 보내기</Button>
      </div>
    </form>
  )
}
