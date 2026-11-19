import { useState, type FormEvent, type ReactNode } from 'react'
import type { RegistrationOutcome } from '../api/adminApi'
import { ApiError } from '../api/client'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { Spinner } from './DataBits'
import { ErrorNotice, StatusNotice, WarningNote } from './Notice'
import { ActionButton } from './ActionButton'
import { Card, CardContent, CardHeader, CardTitle } from './ui/card'

/**
 * 등록 단계 하나(MERCHANT-OPS-002 §4.2·§5.3 — 가맹점 등록 화면 ①~⑤와 조회 상세 B2-1 Revision 폼이 같이 쓴다): 제출·같은 submissionId 재전송·결과 불명 잠금·결과 표시.
 * 상태·보낸 입력은 호출자가 준 [ScreenMemory]에 둔다(탭 전환·폼 닫기에도 유지 — 브라우저 저장소 아님).
 */

/** 결과를 확정하지 못한 응답 — 같은 submissionId로 다시 보내 확인한다. */
const UNKNOWN_CODES: readonly ConsoleErrorCode[] = ['MASTER_OUTCOME_UNKNOWN', 'MASTER_RETRY_SAME_KEY', 'AUDIT_UNAVAILABLE', 'SERVER_ERROR']

export const REJECTION_MESSAGES: Readonly<Record<string, string>> = {
  BUSINESS_REGISTRATION_NUMBER_DUPLICATE: '이미 등록된 사업자등록번호입니다',
  SITE_CHANNEL_DUPLICATE: '이미 다른 Site가 쓰는 채널 식별값입니다',
  BUSINESS_NOT_FOUND: '사업자를 찾을 수 없습니다',
  BUSINESS_NOT_ACTIVE: '활성 상태가 아닌 사업자입니다',
  MERCHANT_NOT_FOUND: '가맹점을 찾을 수 없습니다',
  MERCHANT_NOT_ACTIVE: '활성 상태가 아닌 가맹점입니다',
  // B1-2 계약 초안·서비스 계약(§5.2) — 거절에는 기존 계약·Site ID가 오지 않는다(C-12).
  CONTRACT_START_RETROACTIVE: '시작일이 오늘보다 앞입니다',
  EFFECTIVE_PERIOD_EMPTY: '효력 기간이 비어 있습니다(종료일이 시작일보다 뒤여야 합니다)',
  SCOPE_SITE_OF_OTHER_MERCHANT: '다른 가맹점의 Site는 계약 범위에 넣을 수 없습니다',
  SCOPE_MERCHANT_OF_OTHER_BUSINESS: '다른 사업자의 가맹점입니다',
  SITE_NOT_FOUND: 'Site를 찾을 수 없습니다',
  SITE_NOT_ACTIVE: '활성 상태가 아닌 Site입니다',
  CONTRACT_NOT_FOUND: '계약을 찾을 수 없습니다',
  SERVICE_KIND_DUPLICATE: '이미 같은 서비스 종류의 계약이 있습니다',
  // B2-1 계약 Revision 등록(§9.2) — 대상 가맹점은 계약의 가맹점과 같아야 한다(D-MD-013).
  CONTRACT_MERCHANT_MISMATCH: '계약 대상 가맹점과 다른 가맹점입니다',
  // MERCHANT-CODE-001 MC-1(§5.5 문구): 은퇴·사용 중 코드를 구별하지 않는다(C-18).
  MERCHANT_CODE_TAKEN: '이미 사용 중이거나 사용했던 코드입니다',
  MERCHANT_CODE_RESERVED: '사용할 수 없는 코드입니다',
  // BUSINESS-PROFILE-001 §8.5(BIZ-4): 사업자 명령 거절 + 가맹점 등록의 새 거절(C-27 — D-BIZ-001 새 가맹점은 REGISTERED 사업자만).
  BUSINESS_NOT_REGISTERED: '등록 완료된 사업자만 가맹점을 등록할 수 있습니다',
  BUSINESS_NOT_DRAFT: '이미 등록 완료된 사업자입니다',
  BUSINESS_VERSION_CONFLICT: '다른 사용자가 먼저 수정했습니다. 다시 불러온 뒤 수정하세요',
  BUSINESS_CHANGE_REASON_REQUIRED: '중요정보를 바꾸려면 변경 사유가 필요합니다',
  BUSINESS_PROFILE_UNCHANGED: '바뀐 내용이 없습니다',
}

/** 등록 화면 전용 문구 — 결과 감사 실패는 MASTER 효과가 확정된 뒤일 수 있다(§4.4-7). 조회 화면 문구(ERROR_MESSAGES)는 그대로. */
const REGISTRATION_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  AUDIT_UNAVAILABLE: '감사 기록을 남기지 못했습니다. 처리 여부가 확실하지 않으니 가맹점 조회로 확인하거나 같은 내용으로 다시 보내세요.',
}
const messageOf = (code: ConsoleErrorCode) => REGISTRATION_MESSAGES[code] ?? ERROR_MESSAGES[code]

/** 기억 저장소 초기값(매 렌더 같은 참조). */
const IDLE: StepStatus = { kind: 'idle' }

export type StepStatus =
  | { kind: 'idle' }
  | { kind: 'busy' }
  | { kind: 'decided'; outcome: RegistrationOutcome }
  | { kind: 'failed'; code: ConsoleErrorCode }
  | { kind: 'unknown'; code: ConsoleErrorCode }

/**
 * 저장 단계 하나의 명세(ADMIN-UI-MERCHANT-001): [RegistrationStep] 양식과 등록 마법사 "다음 단계 →"가 **같은** 제출 경로를 쓴다.
 * 상태·보낸 입력은 [memory]의 `${memoryKey}.status`·`${memoryKey}.pending`에 둔다. [collect]·[onSucceeded]는 호출 시점의 값을 읽어야 한다(마법사가 앞 단계 성공 직후 이어 부른다).
 */
export interface StepSpec<P> {
  memory: ScreenMemory
  /** 이 단계 상태를 둘 메모리 키 접두사. */
  memoryKey: string
  api: { send: (input: P & { submissionId: string }) => Promise<RegistrationOutcome> }
  newSubmissionId: () => string
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 입력 검사 → 보낼 값(아니면 null — 오류 표시는 호출자). */
  collect: () => P | null
  /** 제출 직후(예: 등록번호 입력란 비우기). */
  afterSubmit?: (payload: P) => void
  onSucceeded: (reference: string, payload: P) => void
}

interface StepProps<P> extends StepSpec<P> {
  title: string
  submitLabel: string
  /** 성공 뒤 표시(만든 ID 등). */
  succeeded?: (reference: string) => ReactNode
  children: (locked: boolean) => ReactNode
  /**
   * 성공 뒤 완료 상태(C-32 — 선택): 주면 성공한 폼은 읽기 전용·제출 버튼 없음이 되고 [resetLabel] 버튼만 남는다. 그 버튼이 상태를 비우고 [onReset](입력 초기화)을 부른다 —
   * 다음 제출은 새 submissionId. 주지 않으면 성공 뒤에도 바로 다시 입력 가능.
   */
  completion?: { resetLabel: string; onReset: () => void }
  /** 제출 버튼을 잠시 막는다(선택 — 등록 마법사 "다음 단계 →"가 보내는 동안, 리뷰 NIT-3). 결과 불명 재전송 버튼은 막지 않는다. */
  submitDisabled?: boolean
  /**
   * ADMIN-UI-WORKSPACE-001(가맹점 등록 WS8): 참고 시안 모양 — section(같은 region 이름) > 카드 제목 줄(.cardtop h3) + 본문(.cardbody), 버튼은 .btn/.btn.primary,
   * 스피너 대신 제출 버튼 옆 보이는 진행 글자(aria-live). 주지 않으면 지금 카드 모양 그대로(가맹점 조회 상세 계약 Revision 폼 — 아직 옮기지 않은 화면).
   */
  workspace?: boolean
}

/** 메모리에 있는 단계 상태(콜백 시점 값 — 지난 렌더 값 아님). */
export const stepStatusOf = (memory: ScreenMemory, memoryKey: string): StepStatus => (memory.read(`${memoryKey}.status`) as StepStatus | undefined) ?? IDLE
/** 보내는 중이거나 결과 불명(같은 submissionId 재전송만 허용). */
export const stepLocked = (memory: ScreenMemory, memoryKey: string) => {
  const kind = stepStatusOf(memory, memoryKey).kind
  return kind === 'busy' || kind === 'unknown'
}
/** 확정 성공(SUCCEEDED + reference)이면 그 reference. */
export const stepReference = (memory: ScreenMemory, memoryKey: string): string | null => {
  const status = stepStatusOf(memory, memoryKey)
  return status.kind === 'decided' && status.outcome.outcome === 'SUCCEEDED' && status.outcome.reference ? status.outcome.reference : null
}

/** [retrying] — 결과 불명으로 잠긴 뒤 같은 submissionId로 다시 보내는 중. 확정 성공이면 true. */
export async function sendStep<P>(spec: StepSpec<P>, submissionId: string, payload: P, retrying: boolean): Promise<boolean> {
  const { memory, memoryKey } = spec
  const setPending = (value: { submissionId: string; payload: P } | null) => memory.write(`${memoryKey}.pending`, value)
  const setStatus = (value: StepStatus) => memory.write(`${memoryKey}.status`, value)
  setPending({ submissionId, payload })
  setStatus({ kind: 'busy' })
  try {
    const outcome = await spec.api.send({ ...payload, submissionId })
    setPending(null)
    setStatus({ kind: 'decided', outcome })
    if (outcome.outcome === 'SUCCEEDED' && outcome.reference) {
      spec.onSucceeded(outcome.reference, payload)
      return true
    }
    return false
  } catch (e) {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code)) {
      spec.onSessionEnded?.(code)
      return false
    }
    // 재전송 중에는 409만 확정 — 그 밖의 오류(503 MASTER_BUSY·429·403 등)는 앞 요청 결과를 여전히 모르므로 잠금 유지(§5.3, 리뷰 M-1).
    const conflict = e instanceof ApiError && e.status === 409
    if (UNKNOWN_CODES.includes(code) || (retrying && !conflict)) {
      // 결과 불명 — 같은 submissionId·같은 입력을 메모리에 그대로 둔다(잠금).
      setStatus({ kind: 'unknown', code })
      return false
    }
    // 확정된 실패(첫 제출의 MASTER 미호출 또는 409 결함 신호) — 다음 제출은 새 submissionId.
    setPending(null)
    setStatus({ kind: 'failed', code })
    return false
  }
}

/** 새 제출(입력 검사 → 새 submissionId). 잠겨 있으면 보내지 않는다. 확정 성공이면 true. */
export async function submitStep<P>(spec: StepSpec<P>): Promise<boolean> {
  if (stepLocked(spec.memory, spec.memoryKey)) return false
  const payload = spec.collect()
  if (payload === null) return false
  spec.afterSubmit?.(payload)
  return sendStep(spec, spec.newSubmissionId(), payload, false)
}

/** 단계 하나: 제출·같은 submissionId 재전송·결과 불명 잠금·결과 표시. */
export function RegistrationStep<P>({ title, submitLabel, succeeded, children, completion, submitDisabled = false, workspace = false, ...spec }: StepProps<P>) {
  const { memory, memoryKey } = spec
  const [pending, setPending] = useRemembered<{ submissionId: string; payload: P } | null>(memory, `${memoryKey}.pending`, null)
  const [status, setStatus] = useRemembered<StepStatus>(memory, `${memoryKey}.status`, IDLE)
  const [confirmCancel, setConfirmCancel] = useState(false)
  const locked = status.kind === 'busy' || status.kind === 'unknown'
  const completed = completion !== undefined && status.kind === 'decided' && status.outcome.outcome === 'SUCCEEDED'

  const send = async (submissionId: string, payload: P, retrying: boolean) => {
    setConfirmCancel(false)
    await sendStep(spec, submissionId, payload, retrying)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (locked || completed || submitDisabled) return
    setConfirmCancel(false)
    await submitStep(spec)
  }

  const resend = async () => {
    if (pending) await send(pending.submissionId, pending.payload, true)
  }

  const startOver = () => {
    setPending(null)
    setStatus(IDLE)
    completion?.onReset()
  }

  const abandon = () => {
    setPending(null)
    setStatus(IDLE)
    setConfirmCancel(false)
  }

  const content = (
    <>
        {/* noValidate: 형식 검사는 화면(collect)이 하고 판정은 서버가 한다 — 브라우저 제약(예: 시작일 min)이 제출을 막아 서버 판정(소급 거절 문구)을 가리지 않게. */}
        <form onSubmit={submit} noValidate className="flex flex-col gap-4">
          {/* LAYOUT-ROLLOUT B2: workspace(가맹점 등록)는 폼 줄(FormRow — 이름 왼쪽·입력 오른쪽)이 위→아래로 쌓인다(사업자 등록 fieldset과 같은 class). 아니면 기존 grid 그대로(계약 Revision 폼 — B5). */}
          <fieldset disabled={locked || completed} className={workspace ? 'm-0 min-w-0 border-0 p-0' : 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3'}>{children(locked || completed)}</fieldset>
          <div className={workspace ? 'flex flex-wrap items-center justify-end gap-2' : 'flex justify-end'}>
            {completed ? (
              <ActionButton workspace={workspace} type="button" variant="outline" onClick={startOver}>{completion.resetLabel}</ActionButton>
            ) : (
              <>
                {/* WS8: 스피너 대신 보이는 진행 글자(버튼 이름은 그대로 — 결제 보정 취소 reviewFix1 MINOR-1 선례). 늘 있는 polite 영역이라 글자가 생길 때 읽힌다. */}
                {workspace && <span className="subtle" aria-live="polite">{status.kind === 'busy' ? '보내는 중…' : ''}</span>}
                <ActionButton workspace={workspace} type="submit" disabled={locked || submitDisabled} aria-busy={status.kind === 'busy'}>
                  {!workspace && <Spinner active={status.kind === 'busy'} />}
                  {submitLabel}
                </ActionButton>
              </>
            )}
          </div>
        </form>

        {status.kind === 'decided' && status.outcome.outcome === 'SUCCEEDED' && status.outcome.reference && (
          <StatusNotice tone="success">
            <span className="flex flex-col gap-1">
              <span>등록되었습니다.{status.outcome.replayed ? ' 이전에 처리된 결과입니다.' : ''}</span>
              {succeeded?.(status.outcome.reference)}
            </span>
          </StatusNotice>
        )}
        {status.kind === 'decided' && status.outcome.outcome === 'REJECTED' && (
          <ErrorNotice>
            {REJECTION_MESSAGES[status.outcome.rejection ?? ''] ?? '등록이 거절되었습니다.'}
            {status.outcome.replayed ? ' (이전에 처리된 결과입니다)' : ''}
          </ErrorNotice>
        )}
        {status.kind === 'failed' && <ErrorNotice>{messageOf(status.code)}</ErrorNotice>}
        {status.kind === 'unknown' && (
          <div className="flex flex-col gap-3" role="group" aria-label="결과 확인 필요">
            <ErrorNotice>
              결과 확인 필요 — {messageOf(status.code)} 입력은 잠겨 있습니다. 같은 내용으로 다시 보내면 이미 처리된 경우 그 결과를 돌려받습니다.
            </ErrorNotice>
            <div className="flex flex-wrap justify-end gap-2">
              <ActionButton workspace={workspace} type="button" onClick={resend}>같은 내용 다시 보내기</ActionButton>
              <ActionButton workspace={workspace} type="button" variant="outline" onClick={() => setConfirmCancel(true)}>취소하고 새로 입력</ActionButton>
            </div>
            {confirmCancel && (
              <div className="flex flex-col gap-2">
                <WarningNote>앞 요청이 이미 처리됐을 수 있습니다 — 가맹점 조회로 먼저 확인하세요. 가맹점·계약은 이름 중복이 허용되어 새로 보내면 중복 등록될 수 있습니다.</WarningNote>
                <div className="flex justify-end">
                  <ActionButton workspace={workspace} type="button" variant="destructive" onClick={abandon}>그래도 새로 입력</ActionButton>
                </div>
              </div>
            )}
          </div>
        )}
    </>
  )

  if (workspace) {
    return (
      <section aria-label={title}>
        <div className="cardtop">
          <h3>{title}</h3>
        </div>
        <div className="cardbody">{content}</div>
      </section>
    )
  }
  return (
    <Card as="section" aria-label={title} className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-5">{content}</CardContent>
    </Card>
  )
}
