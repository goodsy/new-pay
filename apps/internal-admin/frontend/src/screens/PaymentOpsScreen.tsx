import { useState } from 'react'
import type { AdminApi, PaymentTargetView } from '../api/adminApi'
import type { InputProblem } from '../api/client'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { describeInputProblem } from '../errors/inputProblems'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

const PURPOSE_KINDS = ['INCIDENT_OR_RECON_CORRECTION', 'MERCHANT_TERMINATED_OBLIGATION'] as const
const AMOUNT = /^[1-9][0-9]{0,17}$/

interface Props {
  api: AdminApi
  /** 요청 양식 표시(OPERATOR — 표시만, 권한 판정은 서버). */
  canRequest: boolean
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

function problemOf(e: unknown): InputProblem | undefined {
  return typeof e === 'object' && e !== null && 'detail' in e ? (e as { detail?: InputProblem }).detail : undefined
}

/**
 * 운영자 보정 취소(ADMIN-PAYMENT-OPS-001 §4.20): 대상 조회(모든 조회 역할 — 비권위 표시값), 요청 양식(OPERATOR — 금액 기본값 = 반환 가능액 전액, 부분 허용, 목적·근거·사유,
 * 앞뒤 공백은 화면이 정리 — 서버는 자르지 않고 거부한다). 진행 중 취소·보류·취소 불가면 요청 버튼을 막고 이유를 보인다. 결과·문구는 화면 상태에만.
 */
export function PaymentOpsScreen({ api, canRequest, onSessionEnded }: Props) {
  const [paymentId, setPaymentId] = useState('')
  const [target, setTarget] = useState<PaymentTargetView | null>(null)
  const [amount, setAmount] = useState('')
  const [purposeKind, setPurposeKind] = useState<string>(PURPOSE_KINDS[0])
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<{ code: ConsoleErrorCode; problem?: InputProblem; display?: boolean } | null>(null)
  const [created, setCreated] = useState<string | null>(null)

  const fail = (e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError({ code, problem: problemOf(e) })
  }

  const lookUp = async () => {
    setError(null)
    setCreated(null)
    setTarget(null)
    try {
      const found = await api.paymentTarget(paymentId.trim())
      // S8 리뷰 L-2: 응답 금액이 안전 정수가 아니면 정확히 표시·전송할 수 없다 — 표시 오류로 멈춘다.
      if (![found.cancellableMinor, found.authorizedMinor ?? 0, found.balanceRevision].every(Number.isSafeInteger)) {
        setError({ code: 'SERVER_ERROR', problem: undefined, display: true })
        return
      }
      setTarget(found)
      setAmount(String(found.cancellableMinor))
    } catch (e) {
      fail(e)
    }
  }

  // S8 리뷰 L-1(D-CANCEL-001): 보류 건은 가맹점 취소가 막힌 Payment이고 운영자 보정 취소가 바로 그 처리 경로다 — 막지 않고 안내만(I-130).
  const blockedReason = target === null ? null
    : target.inFlightCancel ? '진행 중인 취소가 있어 지금은 요청할 수 없습니다.'
      : !target.cancellable ? '취소할 수 없는 결제 상태입니다.'
        : null
  // S8 리뷰 L-2: JSON 정수로 정확히 보낼 수 있는 범위(Number.isSafeInteger)만 — 정규식만으로 통과시키지 않는다.
  const amountTooLarge = AMOUNT.test(amount) && !Number.isSafeInteger(Number(amount))
  const ready = AMOUNT.test(amount) && !amountTooLarge && reference.trim() !== '' && reason.trim() !== ''

  const submit = async () => {
    if (target === null) return
    setError(null)
    setCreated(null)
    try {
      const result = await api.requestOperatorCancel({
        paymentId: target.paymentId, amountMinor: Number(amount), purposeKind, purposeReference: reference.trim(), reason: reason.trim(),
      })
      setCreated(result.approvalId)
    } catch (e) {
      fail(e)
    }
  }

  return (
    <section>
      <h2>결제 보정 취소</h2>
      <label>
        결제 ID
        <input value={paymentId} onChange={(e) => setPaymentId(e.target.value)} autoComplete="off" />
      </label>
      <button type="button" disabled={paymentId.trim() === ''} onClick={lookUp}>
        대상 조회
      </button>
      {error && (
        <p role="alert">
          {error.display ? '결제 금액을 정확히 표시할 수 없어 조회 결과를 쓰지 않았습니다(표시 오류).' : error.problem ? describeInputProblem(error.problem) : ERROR_MESSAGES[error.code]}
        </p>
      )}
      {created && <p role="status">{`승인 요청을 만들었습니다: ${created}`}</p>}
      {target && (
        <div>
          <p>{`결제 상태: ${target.paymentStatus}`}</p>
          <p>{`원 승인액: ${target.authorizedMinor ?? '-'} ${target.currency ?? ''}`}</p>
          <p>{`반환 가능액: ${target.cancellableMinor} ${target.currency ?? ''}`}</p>
          <p>{`잔액 revision: ${target.balanceRevision}`}</p>
          {target.onHold && <p>보류 중 — 가맹점 취소는 막혀 있고 운영자 보정 취소로만 처리합니다.</p>}
          {blockedReason && <p>{blockedReason}</p>}
        </div>
      )}
      {target && canRequest && (
        <form onSubmit={(e) => e.preventDefault()}>
          <label>
            취소 금액(최소 단위)
            <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          {amountTooLarge && <p>금액이 허용 범위를 넘습니다.</p>}
          <label>
            목적 종류
            <select value={purposeKind} onChange={(e) => setPurposeKind(e.target.value)}>
              {PURPOSE_KINDS.map((kind) => (
                <option key={kind} value={kind}>
                  {kind}
                </option>
              ))}
            </select>
          </label>
          <label>
            근거 참조
            <input value={reference} maxLength={200} onChange={(e) => setReference(e.target.value)} autoComplete="off" />
          </label>
          <label>
            사유
            <textarea value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
          </label>
          <p>카드 번호·유효기간 등 카드 정보는 입력하지 마세요.</p>
          <button type="button" disabled={blockedReason !== null || !ready} onClick={submit}>
            승인 요청
          </button>
        </form>
      )}
    </section>
  )
}
