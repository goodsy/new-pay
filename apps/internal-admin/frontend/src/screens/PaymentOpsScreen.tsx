import { memo, useMemo, useState } from 'react'
import type { AdminApi, PaymentTargetView } from '../api/adminApi'
import type { InputProblem } from '../api/client'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { describeInputProblem } from '../errors/inputProblems'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { CheckIcon, SearchIcon } from '../components/icons'
import { Field, Spinner } from '../components/DataBits'
import { ErrorNotice, StatusNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card'
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

/** 진행 단계 표시(ADMIN-UI-001 — 표시만). 현재 단계는 aria-current="step". */
const Steps = memo(function Steps({ current }: { current: 1 | 2 | 3 }) {
  return (
    <ol aria-label="진행 단계" className="flex flex-wrap items-center gap-2 text-sm">
      {STEPS.map((label, index) => {
        const step = index + 1
        const done = step < current
        const active = step === current
        return (
          <li key={label} aria-current={active ? 'step' : undefined} className="flex items-center gap-2">
            {index > 0 && <span aria-hidden="true" className={cn('h-px w-6', done || active ? 'bg-primary' : 'bg-border')} />}
            <span
              className={cn(
                'flex size-6 items-center justify-center rounded-full border text-xs font-semibold',
                active && 'border-primary bg-primary text-primary-foreground',
                done && 'border-primary text-primary',
                !active && !done && 'text-muted-foreground',
              )}
            >
              {done ? <CheckIcon aria-hidden="true" className="size-3.5" /> : step}
            </span>
            <span className={cn(active ? 'font-medium text-foreground' : 'text-muted-foreground')}>{label}</span>
          </li>
        )
      })}
    </ol>
  )
})

/**
 * 조회 대상 요약(표시만). 요청 양식을 입력하는 동안 다시 그리지 않게 memo — 원래 문장(시험·캡처가 읽는 `반환 가능액: 7000 KRW` 형식)은 작은 글씨로,
 * 읽기 쉬운 값(한국어 상태·천 단위 금액)은 크게 둔다.
 */
const TargetSummary = memo(function TargetSummary({ target, blockedReason }: { target: PaymentTargetView; blockedReason: string | null }) {
  return (
    <>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="flex flex-col gap-1 rounded-lg border p-3">
          <p className="text-xs text-muted-foreground">{`결제 상태: ${target.paymentStatus}`}</p>
          <p className="text-lg font-bold">{labelOf(target.paymentStatus)}</p>
        </div>
        <div className="flex flex-col gap-1 rounded-lg border p-3">
          <p className="text-xs text-muted-foreground">{`원 승인액: ${target.authorizedMinor ?? '-'} ${target.currency ?? ''}`}</p>
          <p className="text-lg font-bold">{target.authorizedMinor === null ? '-' : formatMinor(target.authorizedMinor, target.currency)}</p>
        </div>
        <div className="flex flex-col gap-1 rounded-lg border border-primary/40 bg-primary/5 p-3">
          <p className="text-xs text-muted-foreground">{`반환 가능액: ${target.cancellableMinor} ${target.currency ?? ''}`}</p>
          <p className="text-lg font-bold text-primary">{formatMinor(target.cancellableMinor, target.currency)}</p>
        </div>
        <div className="flex flex-col gap-1 rounded-lg border p-3">
          <p className="text-xs text-muted-foreground">{`잔액 revision: ${target.balanceRevision}`}</p>
          <p className="font-mono text-lg font-bold">{target.balanceRevision}</p>
        </div>
      </div>
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
  const lookupCard = useMemo(() => (
    <Card className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-sm">1. 대상 조회</CardTitle>
        <CardDescription className="text-xs">조회 값은 참고용이며, 실제 취소 가능 여부는 승인 시점에 결제 서비스가 다시 판정합니다.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-5">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
          <Field label="결제 ID" hint={paymentId.trim() === '' ? '결제 ID(pay_…)를 입력하면 조회할 수 있습니다.' : undefined} className="flex-1">
            {(describedBy) => (
              <Input value={paymentId} onChange={(e) => setPaymentId(e.target.value)} autoComplete="off" placeholder="pay_…" aria-describedby={describedBy} className="font-mono" />
            )}
          </Field>
          <Button type="button" variant={target ? 'outline' : 'default'} disabled={paymentId.trim() === ''} onClick={lookUp} aria-busy={busy === 'lookup'} className="sm:mt-6">
            {busy === 'lookup' ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
            대상 조회
          </Button>
        </div>
        {error && (
          <ErrorNotice>
            {error.display ? '결제 금액을 정확히 표시할 수 없어 조회 결과를 쓰지 않았습니다(표시 오류).' : error.problem ? describeInputProblem(error.problem) : ERROR_MESSAGES[error.code]}
          </ErrorNotice>
        )}
        {target && <TargetSummary target={target} blockedReason={blockedReason} />}
      </CardContent>
    </Card>
  ), [paymentId, target, busy, error, blockedReason, api, onSessionEnded])

  // 입력 칸도 자기 값이 바뀔 때만 다시 만든다(한 칸에 입력할 때 다른 칸을 다시 그리지 않게).
  const amountField = useMemo(() => (
    <Field
      label="취소 금액(최소 단위)"
      error={amountTooLarge ? '금액이 허용 범위를 넘습니다.' : undefined}
      hint={amountPreview ?? '기본값은 반환 가능액 전액이며, 일부만 취소할 수 있습니다.'}
    >
      {(describedBy) => (
        <Input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} aria-describedby={describedBy} aria-invalid={amountTooLarge || undefined} className="font-mono" />
      )}
    </Field>
  ), [amount, amountTooLarge, amountPreview])
  const purposeField = useMemo(() => (
    <Field label="목적 종류" hint={labelOf(purposeKind)}>
      {(describedBy) => (
        <NativeSelect value={purposeKind} onChange={(e) => setPurposeKind(e.target.value)} aria-describedby={describedBy}>
          {PURPOSE_KINDS.map((kind) => (
            <option key={kind} value={kind}>
              {kind}
            </option>
          ))}
        </NativeSelect>
      )}
    </Field>
  ), [purposeKind])
  const referenceField = useMemo(() => (
    <Field label="근거 참조" hint="1~200자. 문자·숫자·공백과 - _ . : / # 만 쓸 수 있습니다. 13~19자리로 이어진 숫자는 카드 번호로 보아 거부되니 구분 기호를 넣어 주세요(예: RECON-2026-1028-01).">
      {(describedBy) => (
        <Input value={reference} maxLength={200} onChange={(e) => setReference(e.target.value)} autoComplete="off" aria-describedby={describedBy} />
      )}
    </Field>
  ), [reference])
  const reasonField = useMemo(() => (
    <Field label="사유" hint="1~500자. 승인자가 판단할 수 있게 구체적으로 적어 주세요.">
      {(describedBy) => <Textarea value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} />}
    </Field>
  ), [reason])

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="결제 보정 취소" description="사고·대사 보정이 필요한 결제를 조회하고, 다른 관리자의 승인을 거쳐 실행되는 취소를 요청합니다." />
      <Steps current={step} />
      {lookupCard}
      {target && !canRequest && (
        <p className="text-sm text-muted-foreground">보정 취소 요청은 운영자 역할만 만들 수 있습니다.</p>
      )}
      {target && canRequest && (
        <Card className="gap-4 py-5">
          <CardHeader className="px-5">
            <CardTitle className="text-sm">2. 요청 작성</CardTitle>
            <CardDescription className="text-xs">요청은 다른 승인자가 인증 코드로 승인해야 실행됩니다.</CardDescription>
          </CardHeader>
          <CardContent className="px-5">
            <form onSubmit={(e) => e.preventDefault()} className="flex flex-col gap-4">
              <div className="grid gap-4 sm:grid-cols-2">
                {amountField}
                {purposeField}
              </div>
              {referenceField}
              {reasonField}
              <p className="text-xs text-muted-foreground">카드 번호·유효기간 등 카드 정보는 입력하지 마세요.</p>
              <div className="flex flex-col items-end gap-1.5">
                <Button type="button" disabled={blockedReason !== null || !ready} onClick={submit} aria-busy={busy === 'submit'}>
                  <Spinner active={busy === 'submit'} />
                  승인 요청
                </Button>
                {waitingFor && <p className="text-xs text-muted-foreground">{waitingFor}</p>}
              </div>
            </form>
          </CardContent>
        </Card>
      )}
      {created && <StatusNotice tone="success">{`승인 요청을 만들었습니다: ${created}`}</StatusNotice>}
    </section>
  )
}
