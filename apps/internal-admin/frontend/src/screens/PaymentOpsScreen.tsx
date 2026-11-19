import { memo, useMemo, useState } from 'react'
import type { AdminApi, PaymentTargetView } from '../api/adminApi'
import type { InputProblem } from '../api/client'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { describeInputProblem } from '../errors/inputProblems'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { FormRow } from '../components/FormRow'
import { ErrorNotice, StatusNotice, WarningNote } from '../components/Notice'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Textarea } from '../components/ui/textarea'
import { formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'
import { cn } from '../lib/utils'

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

const STEPS = ['대상 조회', '요청 작성', '승인 대기'] as const

/**
 * 진행 단계 표시(ADMIN-UI-001 — 표시만). 현재 단계는 aria-current="step".
 * ADMIN-UI-WORKSPACE-001(결제 보정 취소): 시안 사업자 등록 `.wizard`(span마다 `n. 단계 이름`, 현재 단계 `.current` — 위 선·글자 파랑) DOM 그대로.
 * 목록 의미는 role(list·listitem)로 유지한다. 끝난 단계는 원본처럼 파란 위 선 + 기존 MINOR-6 info 글자 token(진행 원 대신 단계 띠의 선·글자 색).
 */
const Steps = memo(function Steps({ current }: { current: 1 | 2 | 3 }) {
  return (
    <div role="list" aria-label="진행 단계" className="wizard">
      {STEPS.map((label, index) => {
        const step = index + 1
        const done = step < current
        const active = step === current
        return (
          <span
            key={label}
            role="listitem"
            aria-current={active ? 'step' : undefined}
            className={cn(
              active && 'current',
              // 리뷰 MINOR-6: 쪽 바탕 위 브랜드 글자 대신 한 단계 진한 info 글자색(4.5:1 이상, 다크 #93c5fd).
              done && 'border-primary text-chip-info-fg',
            ) || undefined}
          >
            {`${step}. ${label}`}
          </span>
        )
      })}
    </div>
  )
})

/**
 * 조회 대상 요약(표시만). 요청 양식을 입력하는 동안 다시 그리지 않게 memo — 원래 문장(시험·캡처가 읽는 `반환 가능액: 7000 KRW` 형식)은 이름 칸에,
 * 읽기 쉬운 값(한국어 상태·천 단위 금액)은 값 칸에 둔다. ADMIN-UI-WORKSPACE-001: 시안 상세 `.detailgrid` 배치 — reviewFix1 MINOR-2: 입력과 연결되지 않는 label 대신
 * 정의 목록(dl > div > dt + dd — 거래 조회·가맹점 조회 상세와 같은 의미), 글자 값은 workspace.css의 `.detailgrid dt/dd`(원본 label·strong 최종값).
 */
const TargetSummary = memo(function TargetSummary({ target, blockedReason }: { target: PaymentTargetView; blockedReason: string | null }) {
  return (
    <>
      <dl className="detailgrid">
        <div>
          <dt>{`결제 상태: ${target.paymentStatus}`}</dt>
          <dd>{labelOf(target.paymentStatus)}</dd>
        </div>
        <div>
          <dt>{`원 승인액: ${target.authorizedMinor ?? '-'} ${target.currency ?? ''}`}</dt>
          <dd>{target.authorizedMinor === null ? '-' : formatMinor(target.authorizedMinor, target.currency)}</dd>
        </div>
        <div>
          <dt>{`반환 가능액: ${target.cancellableMinor} ${target.currency ?? ''}`}</dt>
          <dd>{formatMinor(target.cancellableMinor, target.currency)}</dd>
        </div>
        <div>
          <dt>{`잔액 revision: ${target.balanceRevision}`}</dt>
          <dd className="font-mono">{target.balanceRevision}</dd>
        </div>
      </dl>
      {target.onHold && <WarningNote>보류 중 — 가맹점 취소는 막혀 있고 운영자 보정 취소로만 처리합니다.</WarningNote>}
      {blockedReason && <WarningNote>{blockedReason}</WarningNote>}
    </>
  )
})

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
  // 표시용 진행 표시(버튼을 막지 않는다 — 기존 동작 유지).
  const [busy, setBusy] = useState<'lookup' | 'submit' | null>(null)

  const fail = (e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError({ code, problem: problemOf(e) })
  }

  const lookUp = async () => {
    setError(null)
    setCreated(null)
    setTarget(null)
    setBusy('lookup')
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
    } finally {
      setBusy(null)
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
    setBusy('submit')
    try {
      const result = await api.requestOperatorCancel({
        paymentId: target.paymentId, amountMinor: Number(amount), purposeKind, purposeReference: reference.trim(), reason: reason.trim(),
      })
      setCreated(result.approvalId)
    } catch (e) {
      fail(e)
    } finally {
      setBusy(null)
    }
  }

  const step: 1 | 2 | 3 = created ? 3 : target ? 2 : 1
  const amountPreview = AMOUNT.test(amount) && !amountTooLarge ? `= ${formatMinor(Number(amount), target?.currency)}` : undefined
  const waitingFor = blockedReason !== null ? null
    : !AMOUNT.test(amount) ? '취소 금액을 1 이상의 정수로 입력해 주세요.'
      : amountTooLarge ? null
        : reference.trim() === '' || reason.trim() === '' ? '근거 참조와 사유를 입력하면 요청할 수 있습니다.'
          : null

  // 요청 양식을 입력하는 동안 조회 카드는 다시 만들지 않는다(표시 성능 — 입력마다 전체를 다시 그리지 않게). 의존 값이 바뀌면 새로 만든다.
  // ADMIN-UI-WORKSPACE-001: 카드 제목 줄(.cardtop h3) + 본문(.cardbody — 안내 .subtle · 결제 ID 폼 줄 · 오류 · 조회 값). Fragment라 탭 패널 바로 아래 자식이 된다.
  // LAYOUT-ROLLOUT B3(사용자 결정 2026-11-19 폼 줄 — 시안 .rolebar 대체): 결제 ID는 FormRow(md — payment_id VARCHAR(64)이지만 실제 값 pay_+ULID 30자, 코디네이터 결정).
  // 이름은 진짜 label(글자 '결제 ID' 그대로), '대상 조회'·진행 글자는 입력 바로 오른쪽(trailing, 입력 높이 h-9 — 가맹점 등록 '찾기' 선례), 도움말은 비어 있을 때만(기존 조건) 입력 열 아래 .fieldnote(aria-describedby).
  // 입력은 Input(.rolebar 밖이라 시안 입력 모양 대신 다른 폼 줄과 같은 모양) — .fieldcontrol 첫 자식이라 최소 폭·폭 단계 규칙이 입력에 걸린다.
  // 검색 버튼 선례처럼 아이콘·스피너 없이 aria-busy만(비활성 조건 그대로). 조회 전 주 버튼(.btn.primary), 조회 뒤 보조 버튼(.btn) — 기존 default/outline 전환과 같다.
  const lookupCard = useMemo(() => (
    <>
      <div className="cardtop">
        <h3>1. 대상 조회</h3>
      </div>
      <div className="cardbody">
        <p className="subtle">조회 값은 참고용이며, 실제 취소 가능 여부는 승인 시점에 결제 서비스가 다시 판정합니다.</p>
        <FormRow
          label="결제 ID"
          width="md"
          hint={paymentId.trim() === '' ? '결제 ID(pay_…)를 입력하면 조회할 수 있습니다.' : undefined}
          trailing={
            <>
              <button type="button" className={target ? 'btn h-9 py-0' : 'btn primary h-9 py-0'} disabled={paymentId.trim() === ''} onClick={lookUp} aria-busy={busy === 'lookup'}>
                대상 조회
              </button>
              {/* reviewFix1 MINOR-1: 스피너 대신 보이는 진행 글자(버튼 이름은 그대로 — 접근 가능한 이름이 바뀌지 않게). 늘 있는 polite 영역이라 글자가 생길 때 읽힌다. */}
              <span className="subtle" aria-live="polite">{busy === 'lookup' ? '조회 중…' : ''}</span>
            </>
          }
        >
          {(describedBy, id) => (
            <Input
              id={id}
              className="font-mono"
              value={paymentId}
              onChange={(e) => setPaymentId(e.target.value)}
              autoComplete="off"
              placeholder="pay_…"
              aria-describedby={describedBy}
            />
          )}
        </FormRow>
        {error && (
          <ErrorNotice>
            {error.display ? '결제 금액을 정확히 표시할 수 없어 조회 결과를 쓰지 않았습니다(표시 오류).' : error.problem ? describeInputProblem(error.problem) : ERROR_MESSAGES[error.code]}
          </ErrorNotice>
        )}
        {target && <TargetSummary target={target} blockedReason={blockedReason} />}
      </div>
    </>
  ), [paymentId, target, busy, error, blockedReason, api, onSessionEnded])

  // 입력 칸도 자기 값이 바뀔 때만 다시 만든다(한 칸에 입력할 때 다른 칸을 다시 그리지 않게).
  // LAYOUT-ROLLOUT B3(사용자 결정 2026-11-19 폼 줄): Field → FormRow(이름 왼쪽 label[for] · 입력 오른쪽 · 도움말/오류 .fieldnote — 문구·aria-describedby·aria-invalid 그대로).
  // 폭 = ADMIN V5 admin_operator_cancel_command 열 길이 단계: 금액 sm(BIGINT — 화면 상한 18자리, 코디네이터 결정) · 목적 md(purpose_kind VARCHAR(40)) · 근거 full(purpose_ref 200) · 사유 full(reason 500).
  const amountField = useMemo(() => (
    <FormRow
      label="취소 금액(최소 단위)"
      width="sm"
      error={amountTooLarge ? '금액이 허용 범위를 넘습니다.' : undefined}
      hint={amountPreview ?? '기본값은 반환 가능액 전액이며, 일부만 취소할 수 있습니다.'}
    >
      {(describedBy, id) => (
        <Input id={id} inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} aria-describedby={describedBy} aria-invalid={amountTooLarge || undefined} className="font-mono" />
      )}
    </FormRow>
  ), [amount, amountTooLarge, amountPreview])
  const purposeField = useMemo(() => (
    <FormRow label="목적 종류" width="md" hint={labelOf(purposeKind)}>
      {(describedBy, id) => (
        <NativeSelect id={id} value={purposeKind} onChange={(e) => setPurposeKind(e.target.value)} aria-describedby={describedBy}>
          {PURPOSE_KINDS.map((kind) => (
            <option key={kind} value={kind}>
              {kind}
            </option>
          ))}
        </NativeSelect>
      )}
    </FormRow>
  ), [purposeKind])
  const referenceField = useMemo(() => (
    <FormRow label="근거 참조" hint="1~200자. 문자·숫자·공백과 - _ . : / # 만 쓸 수 있습니다. 13~19자리로 이어진 숫자는 카드 번호로 보아 거부되니 구분 기호를 넣어 주세요(예: RECON-2026-1028-01).">
      {(describedBy, id) => (
        <Input id={id} value={reference} maxLength={200} onChange={(e) => setReference(e.target.value)} autoComplete="off" aria-describedby={describedBy} />
      )}
    </FormRow>
  ), [reference])
  const reasonField = useMemo(() => (
    <FormRow label="사유" hint="1~500자. 승인자가 판단할 수 있게 구체적으로 적어 주세요.">
      {(describedBy, id) => <Textarea id={id} value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} />}
    </FormRow>
  ), [reason])

  // ADMIN-UI-WORKSPACE-001(결제 관리 › 결제 보정 취소): 거래 조회·계정 관리와 같이 MenuCard flush 탭 패널 바로 안에 시안 구성 —
  // .mapnote(탭 이름 h2 + 기존 설명) → 진행 단계 .wizard → [1. 대상 조회] .cardtop h3 + .cardbody → (조회 뒤) 운영자 아님 안내 .cardnote | [2. 요청 작성] .cardtop h3 + .cardbody
  // → (요청 뒤) 결과 안내 .cardnote. 입력 칸(금액·목적·근거·사유)은 폼 줄(LAYOUT-ROLLOUT B3 — 2열 grid 대신 위에서 아래로 한 묶음, 줄 사이 선은 FormRow). 동작·조건·문구 그대로.
  return (
    <>
      <div className="mapnote">
        <h2>결제 보정 취소</h2>
        {' · '}
        <span>사고·대사 보정이 필요한 결제를 조회하고, 다른 관리자의 승인을 거쳐 실행되는 취소를 요청합니다.</span>
      </div>
      <Steps current={step} />
      {lookupCard}
      {target && !canRequest && (
        <div className="cardnote">
          <p className="subtle">보정 취소 요청은 운영자 역할만 만들 수 있습니다.</p>
        </div>
      )}
      {target && canRequest && (
        <>
          <div className="cardtop">
            <h3>2. 요청 작성</h3>
          </div>
          <div className="cardbody">
            <p className="subtle">요청은 다른 승인자가 인증 코드로 승인해야 실행됩니다.</p>
            <form onSubmit={(e) => e.preventDefault()} className="flex flex-col gap-4">
              <div>
                {amountField}
                {purposeField}
                {referenceField}
                {reasonField}
              </div>
              <p className="subtle">카드 번호·유효기간 등 카드 정보는 입력하지 마세요.</p>
              <div className="flex flex-col items-end gap-1.5">
                {/* 스피너 대신 버튼 옆 보이는 진행 글자(reviewFix1 MINOR-1 — 버튼 이름·비활성 조건 그대로, 진행 중 비활성화는 후속 PAYOPS-DOUBLE-SUBMIT). */}
                <button type="button" className="btn primary" disabled={blockedReason !== null || !ready} onClick={submit} aria-busy={busy === 'submit'}>
                  승인 요청
                </button>
                <span className="subtle" aria-live="polite">{busy === 'submit' ? '요청 중…' : ''}</span>
                {waitingFor && <p className="subtle">{waitingFor}</p>}
              </div>
            </form>
          </div>
        </>
      )}
      {created && (
        <div className="cardnote">
          <StatusNotice tone="success">{`승인 요청을 만들었습니다: ${created}`}</StatusNotice>
        </div>
      )}
    </>
  )
}
