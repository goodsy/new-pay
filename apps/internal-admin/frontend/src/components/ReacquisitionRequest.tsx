import { useState } from 'react'
import type { AcquisitionRecord, AdminApi, ReacquisitionTargets } from '../api/adminApi'
import { ApiError, type InputProblem } from '../api/client'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { describeInputProblem, INPUT_REASONS } from '../errors/inputProblems'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatMinor } from '../lib/format'
import { Field } from './DataBits'
import { ErrorNotice, WarningNote } from './Notice'
import { Input } from './ui/input'
import { NativeSelect } from './ui/native-select'
import { Textarea } from './ui/textarea'

/** 사유 코드(C-9 고정 목록 — 서버 V7 CHECK와 같은 값). 이름은 화면 문구(가역). */
export const REACQUISITION_REASONS: Readonly<Record<string, string>> = {
  INSTITUTION_CONFIRMED_ERROR: '매입사 확인 오류',
  DATA_CORRECTED_AT_SOURCE: '원천 데이터 정정',
  INSTITUTION_REQUESTED: '매입사 재전송 요청',
}

/** FINANCIAL 재매입 불가 사유(ReacquisitionRefusal) → 화면 문구. 판단은 서버 값만(I-8 — 화면은 규칙을 계산하지 않는다). */
export const REFUSALS: Readonly<Record<string, string>> = {
  UNKNOWN_NOT_RECONCILED: '결과 모름 — 대사로 미매입 확인 뒤 가능(D-ACQ-014)',
  NOT_LATEST_GENERATION: '이미 새 세대가 있음(재매입 진행 중)',
  ALREADY_ACCEPTED: '이미 매입 확정됨',
  TARGET_NOT_REACQUIRABLE: '거절된 레코드가 아님',
  CANCEL_NOT_SUPPORTED: '취소 레코드 재매입은 아직 지원하지 않음',
  PAYMENT_HELD: '결제가 보류 중',
  CANCELLATION_UNDECIDED: '판정 전 취소가 있음 — 다음 봉인 뒤 다시 요청',
  TARGET_INCONSISTENT: '결제 사실과 맞지 않음',
  ALREADY_NOT_REQUIRED: '이미 재매입 불요로 종결',
  TARGET_NOT_FOUND: '파일에 없는 순번',
  TARGET_CHANGED: '대상이 바뀜',
}

/** 화면 후보(표시 편의 — 서버 미리 보기가 권위): 현재 상태가 거절·파일 거절인 레코드는 선택 가능, 결과 모름은 안내만. */
const SELECTABLE = new Set(['REJECTED', 'FILE_REJECTED'])
const EVIDENCE = /^[A-Za-z0-9._:#/-]{1,128}$/
/**
 * 카드 번호 패턴(B1-A 리뷰 M-1 — 서버 `OperatorCancelInputRules.containsCardNumber`와 같은 규칙: 십진 숫자 13~19자리, 숫자 사이 공백·하이픈 1개 허용). 화면은 버튼을 막고 안내만 한다 —
 * 판정 권위는 서버(400 INVALID_INPUT evidenceRef·CARD_NUMBER_PATTERN)다.
 */
const CARD_NUMBER = /(?<!\p{Nd})(?:\p{Nd}[ -]?){12,18}\p{Nd}(?!\p{Nd})/u
const MAX_ITEMS = 50

interface Props {
  api: AdminApi
  fileId: string
  records: AcquisitionRecord[]
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

/**
 * 재매입 요청(ACQ-ADMIN-001 B1-A §5.1 — OPERATOR, D-ACQA-002·003). 거절 레코드를 고르면 FINANCIAL 미리 보기(조회 키, 비권위)로 레코드마다 가능 여부·예상 순액을 보이고, **모두 가능할 때만**
 * 사유 코드·근거 참조·사유를 받아 2인 승인 요청을 만든다(부분 요청 없음). 금액은 실행 시점에 FINANCIAL이 다시 계산한다(I-10). 화면은 저장소·URL에 아무것도 남기지 않는다.
 */
export function ReacquisitionRequest({ api, fileId, records, onSessionEnded }: Props) {
  const [selected, setSelected] = useState<number[]>([])
  const [preview, setPreview] = useState<ReacquisitionTargets | null>(null)
  const [reasonCode, setReasonCode] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<{ code: ConsoleErrorCode; problem?: InputProblem } | null>(null)
  const [created, setCreated] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const candidates = records.filter((r) => SELECTABLE.has(r.status) || r.status === 'UNKNOWN')
  if (candidates.length === 0) return null

  const fail = (e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    // 서버 입력 거절은 필드·이유(고정 code)만 보인다(I-111 — 값 원문 없음).
    else setError({ code, problem: e instanceof ApiError ? e.detail : undefined })
  }
  const toggle = (seq: number) => {
    setPreview(null)
    setCreated(null)
    setSelected((s) => (s.includes(seq) ? s.filter((x) => x !== seq) : [...s, seq].sort((a, b) => a - b)))
  }
  const check = async () => {
    setError(null)
    setBusy(true)
    try {
      setPreview(await api.reacquisitionTargets(fileId, selected))
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }
  const allEligible = preview !== null && preview.items.length === selected.length && preview.items.every((i) => i.eligible)
  const evidenceLooksLikeCard = CARD_NUMBER.test(evidenceRef)
  const formReady = allEligible && reasonCode !== '' && EVIDENCE.test(evidenceRef) && !evidenceLooksLikeCard && reason.trim() !== '' && reason === reason.trim()
  const submit = async () => {
    setError(null)
    setBusy(true)
    try {
      const result = await api.requestReacquisition({ fileId, seqs: selected, reasonCode, evidenceRef, reason })
      setCreated(result.approvalId)
      setPreview(null)
      setSelected([])
      setReason('')
      setEvidenceRef('')
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }
  const total = preview?.items.reduce((sum, i) => sum + (i.expectedNetMinor ?? 0), 0) ?? 0

  // ADMIN-UI-WORKSPACE-001(매입·대사): 매입 파일 상세의 다른 패널과 같은 시안 .card > .cardtop(h4) → .cardbody, 미리 보기 표는 .tablewrap, 가능은 .tag.green, 버튼은 .btn.
  // 입력 칸(사유 코드·근거 참조·사유)은 도움말·오류의 aria-describedby 연결을 지키려고 기존 Field 구성요소 그대로(1단계 가맹점 조회의 요청 양식과 같음).
  return (
    <section aria-label="재매입 요청" className="card">
      <div className="cardtop">
        <h4>재매입 요청</h4>
      </div>
      <div className="cardbody">
        <p className="subtle">거절된 레코드를 골라 같은 업무일의 새 버전 파일로 다시 보내도록 요청합니다. 2인 승인 뒤 매입 정보 서비스가 다시 확인하고 실행합니다.</p>
        <ul className="flex flex-col gap-1.5" aria-label="재매입 후보 레코드">
          {candidates.map((r) => (
            <li key={r.seq} className="flex flex-wrap items-center gap-2">
              {SELECTABLE.has(r.status) ? (
                <label className="flex items-center gap-2">
                  {/* B1-A 리뷰 n-4: 미리 보기·요청 중에는 선택을 바꾸지 못한다(응답이 다른 선택에 붙지 않게). */}
                  <input type="checkbox" checked={selected.includes(r.seq)} onChange={() => toggle(r.seq)} disabled={busy || (!selected.includes(r.seq) && selected.length >= MAX_ITEMS)} />
                  <span className="font-mono text-xs">{`레코드 ${r.seq}`}</span>
                </label>
              ) : (
                <span className="subtle font-mono">{`레코드 ${r.seq}`}</span>
              )}
              <span className="font-mono text-xs">{r.transactionId}</span>
              <span>{formatMinor(r.recordAmountMinor, r.currency)}</span>
              {r.status === 'UNKNOWN' && <span className="subtle">{REFUSALS.UNKNOWN_NOT_RECONCILED}</span>}
            </li>
          ))}
        </ul>
        <button type="button" className="btn self-start" disabled={selected.length === 0 || busy} onClick={check}>
          대상 미리 보기
        </button>
        {error && (
          <ErrorNotice>
            {error.code === 'DUPLICATE_UNRESOLVED' ? '이 파일에 아직 끝나지 않은 재매입 승인이 있습니다.' : error.problem ? describeInputProblem(error.problem) : ERROR_MESSAGES[error.code]}
          </ErrorNotice>
        )}
        {created && (
          <p role="status" className="text-sm">
            {'재매입 승인 요청을 만들었습니다 '}
            <span className="font-mono text-xs">{created}</span>
            {'. 승인함에서 다른 관리자(APPROVER)가 인증 코드로 승인합니다.'}
          </p>
        )}
        {preview && (
          <>
            <div className="tablewrap">
              <table aria-label="재매입 대상 미리 보기">
                <thead>
                  <tr>
                    <th>순번</th><th>거래</th><th>세대·상태</th><th className="amount">원승인액</th><th className="amount">받은 취소</th>
                    <th className="amount">예상 순액(표시용)</th><th>가능 여부</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.items.map((i) => (
                    <tr key={i.seq} aria-label={`미리 보기 ${i.seq}`}>
                      <td>{i.seq}</td>
                      <td>{i.transactionId ?? '-'}</td>
                      <td>{i.generation === null ? '-' : `${i.generation} · ${i.status ?? '-'}`}</td>
                      <td className="amount">{i.approvedAmountMinor === null ? '-' : formatMinor(i.approvedAmountMinor, 'KRW')}</td>
                      <td className="amount">{i.cancelledAmountMinor === null ? '-' : formatMinor(i.cancelledAmountMinor, 'KRW')}</td>
                      <td className="amount">{i.expectedNetMinor === null ? '-' : formatMinor(i.expectedNetMinor, 'KRW')}</td>
                      <td>
                        {i.eligible ? <span className="tag green">가능</span> : <span title={i.refusal ?? undefined}>{`불가: ${REFUSALS[i.refusal ?? ''] ?? i.refusal ?? '-'}`}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!allEligible && <WarningNote>재매입할 수 없는 레코드가 있습니다. 그 레코드를 빼고 다시 미리 보기 하세요(묶음 일부만 실행하지 않습니다).</WarningNote>}
            {allEligible && (
              <div className="flex flex-col gap-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="사유 코드">
                    {(describedBy) => (
                      <NativeSelect value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} aria-describedby={describedBy}>
                        <option value="">선택</option>
                        {Object.entries(REACQUISITION_REASONS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
                      </NativeSelect>
                    )}
                  </Field>
                  <Field
                    label="근거 참조"
                    hint="매입사 문의 번호 등(영문·숫자·._:#/- 128자 이내). 카드 번호 금지 — 13~19자리로 이어진 숫자는 거부되니 구분 기호를 넣어 주세요(예: INQ-2026-1105-0001)."
                    error={evidenceLooksLikeCard ? <span role="alert">{INPUT_REASONS.CARD_NUMBER_PATTERN}</span> : undefined}
                  >
                    {(describedBy) => <Input value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} aria-describedby={describedBy} autoComplete="off" />}
                  </Field>
                </div>
                <Field label="사유" hint="ADMIN에만 저장되며 화면·감사에는 표시하지 않습니다(매입 정보 서비스로 보내지 않음). 앞뒤 공백·카드 번호는 넣지 마세요.">
                  {(describedBy) => <Textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} aria-describedby={describedBy} />}
                </Field>
                <WarningNote>
                  {`선택한 ${selected.length}건(예상 순액 합 ${formatMinor(total, 'KRW')})을 같은 업무일의 새 버전 파일로 다시 보냅니다. 금액은 실행 시점의 순액(원승인 − 받은 취소)으로 정해집니다. ` +
                    '매입 확정은 매입사 결과로만 됩니다. 2인 승인 뒤 실행됩니다.'}
                </WarningNote>
                <button type="button" className="btn primary self-start" disabled={!formReady || busy} onClick={submit}>
                  재매입 승인 요청
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )
}
