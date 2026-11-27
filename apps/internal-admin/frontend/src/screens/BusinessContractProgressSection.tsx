import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type {
  AdminApi, BusinessContractEventInput, BusinessContractView, ContractIntakeCompleteInput, ContractIntakeKind, ContractIntakeMoveInput, ContractIntakeOpenInput, ContractIntakeView, ContractProgress,
  RegistrationOutcome,
} from '../api/adminApi'
import { ApiError } from '../api/client'
import { KstTime } from '../components/DataBits'
import { DetailRow as Row } from '../components/DetailCard'
import { FormRow } from '../components/FormRow'
import { ErrorNotice, StatusNotice, WarningNote } from '../components/Notice'
import { Badge, type BadgeVariant } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { Textarea } from '../components/ui/textarea'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { ERROR_MESSAGES } from '../errors/messages'
import { COMMAND_RATE_LIMITED } from '../lib/businessProfile'
import {
  actorLabel, allowedActions, BUSINESS_CONTRACT_NOTE, BUSINESS_CONTRACT_STATUS_LABELS, changeBlocked, completionErrors, CONTRACT_EVENT_LABELS, CONTRACT_SERVICE_LABELS,
  contractManager, contractServices, DESIRED_SERVICE_LABELS, DESIRED_SERVICE_NOTE, desiredServices, INTAKE_ACTION_LABELS, INTAKE_CHANNEL_OPTIONS, INTAKE_KIND_LABELS,
  intakeChannel, intakeSummary, MIGRATED, NO_BUSINESS_CONTRACT, NOT_CHANGEABLE_NOTE, nextIntakeKind, openErrors, reasonMessage, reasonRequired, rejectionMessage,
  serverFieldMessage, transitionActor, transitionChange, transitionEvidence, VERSION_IMMUTABLE_NOTE, versionEvidence, versionLine, versionOf, versionPeriod,
  type CompletionForm, type IntakeAction, type OpenForm,
  allowedContractActions, CONTRACT_EVENT_ACTION_LABELS, contractEventErrors, TERMINATION_IRREVERSIBLE, terminationNotice, type ContractEventAction,
} from '../lib/contractProgress'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { useUnknownLockedSubmission } from '../lib/useUnknownLockedSubmission'

/**
 * 사업자 상세 '계약 진행' 섹션(MERCHANT-FLOW-001 §4B.11 화면 1·2 — FLOW-1 읽기 표시 + FLOW-2b 사업자 계약 칸·접수 건 생성·전이·계약 완료).
 * - 사업자 계약 칸(위): 상태 배지, 적용 중·예정 버전, 버전 이력 표(기록 시각 병기 — MINOR-4), 정지·해지 기록(근거 원문 — 사유 원문은 이력 탭), R-1 문구. 해지·정지 버튼은 FLOW-2c.
 * - 접수 건(아래, 최신부터): 종류 배지·요약·접수 항목·전이 이력 표, 그 상태에서 허용되는 동작 버튼만([canEdit] OPERATOR — 판정은 서버). '새 접수' 버튼 이름 = 화면이 판정한 종류.
 * - 명령은 화면 안 대화상자(role=dialog — 브라우저 confirm 아님). 결과 불명은 입력 잠금 + 같은 submissionId 재전송([useUnknownLockedSubmission] — App 메모리, 브라우저 저장소 아님).
 *   성공하면 상세를 다시 읽는다([onChanged] — 성공 응답에 상태·version이 없다, C-18). 저장된 거절은 한국어 안내(code 원문 없음).
 * - 내부 키(biz_·adm_)·계정 키·로그인 이름·version 값은 그리지 않는다(계정 키는 요청 본문에만). 근거 참조 원문은 사업자 조회 역할 전체(D-FLOW-004 ④).
 * - 계약 담당자 = 로그인한 운영자 본인([actorId]·[actorName] — [구현 관례]: 다른 운영자 지정 목록은 FLOW-2d 접수 항목 수정 때 검토, 서버는 ACTIVE OPERATOR를 검사).
 */
interface Props {
  progress: ContractProgress | undefined
  businessId: string
  api: AdminApi
  memory: ScreenMemory
  canEdit: boolean
  actorId?: string
  actorName?: string
  newSubmissionId: () => string
  onSessionEnded?: (code: ConsoleErrorCode) => void
  onChanged: () => void
}

type Dialog =
  | { kind: 'open'; businessId: string; intakeKind: ContractIntakeKind; form: OpenForm }
  | { kind: 'move'; businessId: string; intakeSeq: number; action: Exclude<IntakeAction, 'COMPLETE'>; reason: string }
  | { kind: 'complete'; businessId: string; intakeSeq: number; form: CompletionForm }
  // 리뷰 MAJOR-1: 대화상자를 연 때 본 계약 순번·사건 수를 고정한다 — 전송은 이 값만 쓰고, 재조회 결과가 다르면 대화상자를 닫는다(다른 계약·옛 사건 수로 보내지 않게).
  | { kind: 'event'; businessId: string; contractSeq: number; expectedEventCount: number; action: ContractEventAction; reason: string; evidenceRef: string; confirming: boolean }

type Payload =
  | { op: 'open'; businessId: string; body: Omit<ContractIntakeOpenInput, 'submissionId'> }
  | { op: 'move'; businessId: string; intakeSeq: number; body: Omit<ContractIntakeMoveInput, 'submissionId'> }
  | { op: 'complete'; businessId: string; intakeSeq: number; body: Omit<ContractIntakeCompleteInput, 'submissionId'> }
  | { op: 'event'; action: ContractEventAction; businessId: string; contractSeq: number; body: Omit<BusinessContractEventInput, 'submissionId'> }

const KEY = 'contract.cmd'
const SAVED = '저장했습니다. 계약 진행을 다시 불러왔습니다.'
const CONTRACT_CHANGED = '사업자 계약이 바뀌었습니다. 다시 불러온 내용을 확인하고 새로 여세요.'
const nullIfEmpty = (value: string) => (value === '' ? null : value)

const STATUS_TONE: Readonly<Record<string, BadgeVariant>> = {
  NOT_YET_EFFECTIVE: 'default', ACTIVE: 'success', SUSPENDED: 'warning', TERMINATED: 'secondary', EXPIRED: 'secondary',
}

export function BusinessContractProgressSection({ progress, businessId, api, memory, canEdit, actorId, actorName, newSubmissionId, onSessionEnded, onChanged }: Props) {
  const [dialog, setDialog] = useRemembered<Dialog | null>(memory, 'contract.dialog', null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<{ tone: 'error' | 'info'; text: string } | null>(null)
  const [saved, setSaved] = useState(false)
  // 리뷰 NIT-B: 이 섹션의 명령은 중복이 서버에서 막힌다(전이·완료 = 기대 version, 정지·해지 = 기대 사건 수, 접수 생성 = 열린 건 UNIQUE uk_cin_open·expectedKind) —
  // 그래서 재전송 400(INVALID_INPUT)에 잠금을 풀고 상세를 다시 읽는다(남은 결과 불명 감사의 정본은 MASTER 이력).
  const command = useUnknownLockedSubmission<Payload, RegistrationOutcome>(memory, KEY, newSubmissionId, onSessionEnded, true)
  const current = dialog && dialog.businessId === businessId ? dialog : null
  const [stale, setStale] = useState(false)
  const shownContract = progress?.available ? (progress.businessContract ?? null) : undefined
  useEffect(() => {
    if (current?.kind !== 'event' || command.locked || shownContract === undefined) return
    if (shownContract === null || shownContract.contractSeq !== current.contractSeq || shownContract.events.length !== current.expectedEventCount) {
      setDialog(null)
      setFieldErrors({})
      setNotice(null)
      setStale(true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 표시 계약이 바뀔 때만 본다
  }, [shownContract, current?.kind])
  const writable = canEdit && actorId !== undefined && progress?.available === true

  const send = (submissionId: string, p: Payload): Promise<RegistrationOutcome> => {
    if (p.op === 'open') return api.openContractIntake(p.businessId, { submissionId, ...p.body })
    if (p.op === 'move') return api.moveContractIntake(p.businessId, p.intakeSeq, { submissionId, ...p.body })
    if (p.op === 'event') {
      const body = { submissionId, ...p.body }
      if (p.action === 'SUSPEND') return api.suspendBusinessContract(p.businessId, p.contractSeq, body)
      if (p.action === 'RESUME') return api.resumeBusinessContract(p.businessId, p.contractSeq, body)
      return api.terminateBusinessContract(p.businessId, p.contractSeq, body)
    }
    return api.completeContractIntake(p.businessId, p.intakeSeq, { submissionId, ...p.body })
  }

  // 400 필드 오류(path·code — 값 없음)는 상태 메모리에 남기지 않고 이 화면에서만 칸 문구로 바꾼다.
  const lastFieldErrors = useRef<{ path: string; code: string }[] | undefined>(undefined)
  const sendCapturing = (submissionId: string, p: Payload) =>
    send(submissionId, p).catch((e: unknown) => {
      lastFieldErrors.current = e instanceof ApiError ? e.fieldErrors : undefined
      throw e
    })
  const failed = (code: ConsoleErrorCode) => {
    const errors = lastFieldErrors.current
    if (code === 'INVALID_INPUT' && errors && errors.length > 0) {
      setFieldErrors(Object.fromEntries(errors.map((e) => [e.path.replace(/\[[0-9]+\]$/, ''), serverFieldMessage(e.path, e.code)])))
      return
    }
    setNotice({ tone: 'error', text: code === 'RATE_LIMITED' ? COMMAND_RATE_LIMITED : ERROR_MESSAGES[code] })
  }
  const runCapturing = async (payload: Payload) => {
    setNotice(null)
    setSaved(false)
    lastFieldErrors.current = undefined
    const resend = command.locked
    await command.run(payload, sendCapturing, (outcome) => {
      if (outcome.outcome === 'SUCCEEDED') {
        setDialog(null)
        setFieldErrors({})
        setSaved(true)
      } else {
        setNotice({ tone: 'error', text: rejectionMessage(outcome.rejection) })
      }
      onChanged()
    })
    const status = memory.read(`${KEY}.status`) as { kind: string; code?: ConsoleErrorCode } | undefined
    if (status?.kind === 'failed' && status.code) {
      failed(status.code)
      // 리뷰 NIT-B: 결과 불명 잠금 뒤 재전송이 확정 실패(400 등)로 끝나면 잠금이 풀린다 — 그 사이 바뀌었을 수 있는 상태를 다시 읽는다.
      if (resend) onChanged()
    }
  }

  const openDialog = (next: Dialog) => {
    if (command.locked) return
    setStale(false)
    command.resetIfUnlocked()
    setFieldErrors({})
    setNotice(null)
    setSaved(false)
    setDialog(next)
  }
  const close = () => {
    if (command.locked) return
    setDialog(null)
    setFieldErrors({})
    setNotice(null)
  }

  const unknownText = command.status.kind === 'unknown' ? ERROR_MESSAGES[command.status.code] : null
  const shared = { locked: command.locked, busy: command.status.kind === 'busy', unknownText, notice, fieldErrors, onCancel: close }

  if (!progress?.available) {
    return (
      <Shell>
        <WarningNote>계약 진행을 불러오지 못했습니다. 사업자 상세를 다시 열어 확인하세요.</WarningNote>
      </Shell>
    )
  }
  const today = progress.today ?? ''
  const contract = progress.businessContract ?? null
  const nextKind = nextIntakeKind(progress)
  const kindLabel = nextKind === 'CHANGE' ? '변경 접수' : '신규 접수'

  return (
    <Shell>
      {saved && <StatusNotice tone="success">{SAVED}</StatusNotice>}
      {stale && <WarningNote>{CONTRACT_CHANGED}</WarningNote>}
      <ContractColumn contract={contract} writable={writable && current === null}
        onAction={(action) => contract && openDialog({
          kind: 'event', businessId, contractSeq: contract.contractSeq, expectedEventCount: contract.events.length, action, reason: '', evidenceRef: '', confirming: false,
        })}>
        {current?.kind === 'event' && contract && (
          <ContractEventDialog dialog={current} linkedMerchantCount={contract.linkedMerchantCount} {...shared}
            onChange={(next) => setDialog({ ...current, ...next })}
            onSubmit={() => {
              const errors = contractEventErrors(current.reason, current.evidenceRef)
              setFieldErrors(errors)
              if (Object.keys(errors).length > 0) return
              if (current.action === 'TERMINATE' && !current.confirming) {
                setDialog({ ...current, confirming: true })
                return
              }
              void runCapturing({
                op: 'event', action: current.action, businessId, contractSeq: current.contractSeq,
                body: { expectedEventCount: current.expectedEventCount, reason: current.reason, evidenceRef: nullIfEmpty(current.evidenceRef) },
              })
            }} />
        )}
      </ContractColumn>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
        <h4 className="text-sm font-semibold">접수 건</h4>
        {writable && nextKind !== null && current === null && (
          <Button type="button" size="sm" variant="outline"
            onClick={() => openDialog({ kind: 'open', businessId, intakeKind: nextKind, form: { receivedOn: today, intakeChannel: '', desiredServices: [], reason: '' } })}>
            {kindLabel}
          </Button>
        )}
      </div>
      {current?.kind === 'open' && (
        <OpenDialog dialog={current} today={today} actorName={actorName ?? ''} {...shared}
          onChange={(form) => setDialog({ ...current, form })}
          onSubmit={() => {
            const errors = openErrors(current.form, today)
            setFieldErrors(errors)
            if (Object.keys(errors).length > 0 || actorId === undefined) return
            void runCapturing({
              op: 'open', businessId,
              body: {
                expectedKind: current.intakeKind, receivedOn: current.form.receivedOn, intakeChannel: current.form.intakeChannel, contractManagerId: actorId,
                desiredServices: current.form.desiredServices, reason: nullIfEmpty(current.form.reason),
              },
            })
          }} />
      )}
      {progress.intakes.length === 0 ? (
        <p className="text-sm text-muted-foreground">계약 접수 건이 없습니다.</p>
      ) : (
        progress.intakes.map((intake) => (
          <Intake key={intake.intakeSeq} intake={intake} contract={contract} writable={writable && current === null}
            onAction={(action) => openDialog(action === 'COMPLETE'
              ? { kind: 'complete', businessId, intakeSeq: intake.intakeSeq, form: { documentRef: '', startDate: today, endDate: '', services: ['CARD_PG_PAYMENT'], reason: '' } }
              : { kind: 'move', businessId, intakeSeq: intake.intakeSeq, action, reason: '' })}>
            {current?.kind === 'move' && current.intakeSeq === intake.intakeSeq && (
              <MoveDialog dialog={current} {...shared}
                onChange={(reason) => setDialog({ ...current, reason })}
                onSubmit={() => {
                  const reason = reasonMessage(current.reason, reasonRequired(current.action))
                  setFieldErrors(reason ? { reason } : {})
                  if (reason) return
                  void runCapturing({
                    op: 'move', businessId, intakeSeq: intake.intakeSeq,
                    body: { expectedVersion: intake.version, target: current.action, reason: nullIfEmpty(current.reason) },
                  })
                }} />
            )}
            {current?.kind === 'complete' && current.intakeSeq === intake.intakeSeq && (
              <CompleteDialog dialog={current} today={today} {...shared}
                onChange={(form) => setDialog({ ...current, form })}
                onSubmit={() => {
                  const errors = completionErrors(current.form, today)
                  setFieldErrors(errors)
                  if (Object.keys(errors).length > 0) return
                  const f = current.form
                  void runCapturing({
                    op: 'complete', businessId, intakeSeq: intake.intakeSeq,
                    body: {
                      expectedVersion: intake.version, documentRef: f.documentRef, startDate: f.startDate, endDate: nullIfEmpty(f.endDate), services: f.services,
                      reason: nullIfEmpty(f.reason),
                    },
                  })
                }} />
            )}
          </Intake>
        ))
      )}
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <section aria-label="계약 진행" className="flex flex-col gap-3 rounded-xl border bg-card px-4 py-3">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-semibold">계약 진행</h3>
        <p className="text-xs text-muted-foreground">영업 계약 진행 기록입니다. 결제 허용·MID 발급은 가맹점 계약 승인으로 정합니다.</p>
      </div>
      {children}
    </section>
  )
}

// ── 사업자 계약 칸(§4B.11 1) ─────────────────────────────────────

function ContractColumn({ contract, writable, onAction, children }: {
  contract: BusinessContractView | null
  writable: boolean
  onAction: (action: ContractEventAction) => void
  children?: ReactNode
}) {
  if (!contract) {
    return (
      <div role="group" aria-label="사업자 계약" className="flex flex-col gap-2">
        <h4 className="text-sm font-semibold">사업자 계약</h4>
        <p className="text-sm text-muted-foreground">{NO_BUSINESS_CONTRACT}</p>
        <p className="text-xs text-muted-foreground">{BUSINESS_CONTRACT_NOTE}</p>
      </div>
    )
  }
  const applying = versionOf(contract, contract.applyingVersionNo)
  const scheduled = versionOf(contract, contract.scheduledVersionNo)
  return (
    <div role="group" aria-label="사업자 계약" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-semibold">사업자 계약</h4>
        <Badge variant={STATUS_TONE[contract.status] ?? 'secondary'}>{BUSINESS_CONTRACT_STATUS_LABELS[contract.status] ?? contract.status}</Badge>
        {writable && allowedContractActions(contract).map((action) => (
          <Button key={action} type="button" size="sm" variant={action === 'TERMINATE' ? 'outline' : 'secondary'} onClick={() => onAction(action)}>
            {CONTRACT_EVENT_ACTION_LABELS[action]}
          </Button>
        ))}
      </div>
      {children}
      <p className="text-xs text-muted-foreground">{BUSINESS_CONTRACT_NOTE}</p>
      <dl>
        <Row term="적용 중 버전">{applying ? versionLine(applying) : '-'}</Row>
        {scheduled && <Row term="예정 버전">{versionLine(scheduled)}</Row>}
        <Row term="연결 가맹점">{`${contract.linkedMerchantCount}개`}</Row>
      </dl>
      <Table aria-label="사업자 계약 버전 이력">
        <TableHeader>
          <TableRow>
            <TableHead>버전</TableHead><TableHead>기간</TableHead><TableHead>범위</TableHead><TableHead>근거 참조</TableHead><TableHead>기록</TableHead><TableHead>기록 시각</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {contract.versions.map((v) => (
            <TableRow key={v.versionNo}>
              <TableCell>{v.versionNo}</TableCell>
              <TableCell>{versionPeriod(v)}</TableCell>
              <TableCell>{contractServices(v)}</TableCell>
              <TableCell><span className="font-mono text-xs break-all">{versionEvidence(v)}</span></TableCell>
              <TableCell>{actorLabel(v)}</TableCell>
              <TableCell><KstTime iso={v.createdAt} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {contract.events.length > 0 && (
        <Table aria-label="사업자 계약 정지·해지 기록">
          <TableHeader>
            <TableRow>
              <TableHead>순번</TableHead><TableHead>종류</TableHead><TableHead>근거 참조</TableHead><TableHead>처리자</TableHead><TableHead>시각</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {contract.events.map((e) => (
              <TableRow key={e.eventSeq}>
                <TableCell>{e.eventSeq}</TableCell>
                <TableCell>{CONTRACT_EVENT_LABELS[e.kind] ?? e.kind}</TableCell>
                <TableCell><span className="font-mono text-xs break-all">{e.evidenceRef ?? '-'}</span></TableCell>
                <TableCell>{actorLabel(e)}</TableCell>
                <TableCell><KstTime iso={e.occurredAt} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}

// ── 접수 건(§4B.11 2) ─────────────────────────────────────

function Intake({ intake, contract, writable, onAction, children }: {
  intake: ContractIntakeView
  contract: BusinessContractView | null
  writable: boolean
  onAction: (action: IntakeAction) => void
  children?: ReactNode
}) {
  const name = `계약 접수 ${intake.intakeSeq}`
  const actions = writable ? allowedActions(intake, contract) : []
  return (
    <div role="group" aria-label={name} className="flex flex-col gap-2 border-t pt-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-semibold">{name}</h4>
        <Badge variant={intake.kind === 'CHANGE' ? 'warning' : 'default'}>{INTAKE_KIND_LABELS[intake.kind]}</Badge>
      </div>
      <dl>
        <Row term="진행 상태"><span>{intakeSummary(intake)}</span></Row>
        <Row term="접수일">{intake.migrated ? MIGRATED : (intake.receivedOn ?? '-')}</Row>
        <Row term="접수 경로">{intakeChannel(intake)}</Row>
        <Row term="계약 담당자">{contractManager(intake)}</Row>
        <Row term="희망 서비스">
          <span>{desiredServices(intake)}</span>
          {!intake.migrated && <span className="text-xs text-muted-foreground">{DESIRED_SERVICE_NOTE}</span>}
        </Row>
      </dl>
      {changeBlocked(intake, contract) && <WarningNote>{NOT_CHANGEABLE_NOTE}</WarningNote>}
      {actions.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {actions.map((action) => (
            <Button key={action} type="button" size="sm" variant={action === 'REJECTED' ? 'outline' : action === 'COMPLETE' ? 'default' : 'secondary'} onClick={() => onAction(action)}>
              {INTAKE_ACTION_LABELS[action]}
            </Button>
          ))}
        </div>
      )}
      {children}
      <Table aria-label={`${name} 진행 이력`}>
        <TableHeader>
          <TableRow>
            <TableHead>순번</TableHead><TableHead>변경</TableHead><TableHead>근거 참조</TableHead><TableHead>처리자</TableHead><TableHead>시각</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {intake.transitions.map((t) => (
            <TableRow key={t.transitionSeq}>
              <TableCell>{t.transitionSeq}</TableCell>
              <TableCell>{transitionChange(t)}</TableCell>
              <TableCell><span className="font-mono text-xs break-all">{transitionEvidence(t)}</span></TableCell>
              <TableCell>{transitionActor(t)}</TableCell>
              <TableCell><KstTime iso={t.occurredAt} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

// ── 대화상자 ─────────────────────────────────────

interface Shared {
  locked: boolean
  busy: boolean
  unknownText: string | null
  notice: { tone: 'error' | 'info'; text: string } | null
  fieldErrors: Record<string, string>
  onCancel: () => void
}

function DialogShell({ name, shared, submitLabel, onSubmit, children }: { name: string; shared: Shared; submitLabel: string; onSubmit: () => void; children: ReactNode }) {
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!shared.busy) onSubmit()
  }
  return (
    <form role="dialog" aria-label={name} onSubmit={submit} noValidate className="flex flex-col gap-2 rounded-lg border p-3">
      <h5 className="text-sm font-semibold">{name}</h5>
      <fieldset disabled={shared.locked} className="flex flex-col gap-1">{children}</fieldset>
      {shared.unknownText && <ErrorNotice>{shared.unknownText}</ErrorNotice>}
      {shared.notice && <ErrorNotice>{shared.notice.text}</ErrorNotice>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="sm" disabled={shared.busy}>{shared.unknownText ? '같은 내용으로 다시 보내기' : submitLabel}</Button>
        {!shared.locked && <Button type="button" size="sm" variant="outline" onClick={shared.onCancel}>취소</Button>}
      </div>
    </form>
  )
}

function ReasonRow({ value, required, error, onChange }: { value: string; required: boolean; error?: string; onChange: (v: string) => void }) {
  return (
    <FormRow label={required ? '사유 및 비고' : '사유 및 비고(선택)'} hint="1~200자, 카드번호 모양의 숫자는 넣을 수 없습니다." error={error}>
      {(describedBy, id) => (
        <Textarea id={id} value={value} onChange={(e) => onChange(e.target.value)} aria-describedby={describedBy} aria-invalid={!!error || undefined} rows={2} />
      )}
    </FormRow>
  )
}

function OpenDialog({ dialog, today, actorName, onChange, onSubmit, ...shared }: Shared & {
  dialog: Extract<Dialog, { kind: 'open' }>
  today: string
  actorName: string
  onChange: (form: OpenForm) => void
  onSubmit: () => void
}) {
  const f = dialog.form
  const toggle = (code: string) =>
    onChange({ ...f, desiredServices: f.desiredServices.includes(code) ? f.desiredServices.filter((s) => s !== code) : [...f.desiredServices, code] })
  return (
    <DialogShell name={dialog.intakeKind === 'CHANGE' ? '변경 접수' : '신규 접수'} shared={shared} submitLabel="접수 저장" onSubmit={onSubmit}>
      <FormRow label="접수일" hint="한국 시간 기준 오늘 이후는 고를 수 없습니다." error={shared.fieldErrors.receivedOn}>
        {(describedBy, id) => (
          <Input id={id} type="date" max={today} value={f.receivedOn} onChange={(e) => onChange({ ...f, receivedOn: e.target.value })} aria-describedby={describedBy}
            aria-invalid={!!shared.fieldErrors.receivedOn || undefined} />
        )}
      </FormRow>
      <FormRow label="접수 경로" error={shared.fieldErrors.intakeChannel}>
        {(describedBy, id) => (
          <NativeSelect id={id} value={f.intakeChannel} onChange={(e) => onChange({ ...f, intakeChannel: e.target.value })} aria-describedby={describedBy}
            aria-invalid={!!shared.fieldErrors.intakeChannel || undefined}>
            <option value="">고르세요</option>
            {INTAKE_CHANNEL_OPTIONS.map((o) => <option key={o.code} value={o.code} disabled={o.disabled}>{o.label}</option>)}
          </NativeSelect>
        )}
      </FormRow>
      <dl>
        <Row term="계약 담당자">{`${actorName}(본인)`}</Row>
      </dl>
      {shared.fieldErrors.contractManager && <p className="text-xs text-destructive">{shared.fieldErrors.contractManager}</p>}
      <fieldset aria-label="희망 서비스" className="flex flex-col gap-1 py-1">
        <legend className="text-xs text-muted-foreground">희망 서비스</legend>
        {Object.entries(DESIRED_SERVICE_LABELS).map(([code, text]) => (
          <label key={code} className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={f.desiredServices.includes(code)} onChange={() => toggle(code)} />
            {text}
          </label>
        ))}
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          <input type="checkbox" disabled checked={false} onChange={() => undefined} />
          가상계좌 — 준비 전
        </label>
        <span className="text-xs text-muted-foreground">{DESIRED_SERVICE_NOTE}</span>
      </fieldset>
      <ReasonRow value={f.reason} required={false} error={shared.fieldErrors.reason} onChange={(reason) => onChange({ ...f, reason })} />
    </DialogShell>
  )
}

function MoveDialog({ dialog, onChange, onSubmit, ...shared }: Shared & {
  dialog: Extract<Dialog, { kind: 'move' }>
  onChange: (reason: string) => void
  onSubmit: () => void
}) {
  const required = reasonRequired(dialog.action)
  return (
    <DialogShell name={INTAKE_ACTION_LABELS[dialog.action]} shared={shared} submitLabel="확인" onSubmit={onSubmit}>
      <ReasonRow value={dialog.reason} required={required} error={shared.fieldErrors.reason} onChange={onChange} />
    </DialogShell>
  )
}

function CompleteDialog({ dialog, today, onChange, onSubmit, ...shared }: Shared & {
  dialog: Extract<Dialog, { kind: 'complete' }>
  today: string
  onChange: (form: CompletionForm) => void
  onSubmit: () => void
}) {
  const f = dialog.form
  const errors = shared.fieldErrors
  return (
    <DialogShell name="계약 완료" shared={shared} submitLabel="계약 완료 저장" onSubmit={onSubmit}>
      <WarningNote>{VERSION_IMMUTABLE_NOTE}</WarningNote>
      <FormRow label="계약서 수령 근거 참조" hint="영문·숫자와 . _ : # / - 만 1~128자(계약서 파일은 받지 않습니다)." error={errors.documentRef}>
        {(describedBy, id) => (
          <Input id={id} value={f.documentRef} onChange={(e) => onChange({ ...f, documentRef: e.target.value })} aria-describedby={describedBy} aria-invalid={!!errors.documentRef || undefined}
            autoComplete="off" />
        )}
      </FormRow>
      <FormRow label="시작일" hint="한국 시간 기준 오늘부터 고를 수 있습니다(그날 0시부터 적용)." error={errors.startDate}>
        {(describedBy, id) => (
          <Input id={id} type="date" min={today} value={f.startDate} onChange={(e) => onChange({ ...f, startDate: e.target.value })} aria-describedby={describedBy}
            aria-invalid={!!errors.startDate || undefined} />
        )}
      </FormRow>
      <FormRow label="종료일(선택)" hint="종료일 당일까지 유효합니다." error={errors.endDate}>
        {(describedBy, id) => (
          <Input id={id} type="date" min={f.startDate || today} value={f.endDate} onChange={(e) => onChange({ ...f, endDate: e.target.value })} aria-describedby={describedBy}
            aria-invalid={!!errors.endDate || undefined} />
        )}
      </FormRow>
      <fieldset aria-label="계약 범위" className="flex flex-col gap-1 py-1">
        <legend className="text-xs text-muted-foreground">계약 범위(희망 서비스와 별개)</legend>
        {Object.entries(CONTRACT_SERVICE_LABELS).map(([code, text]) => (
          <label key={code} className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={f.services.includes(code)}
              onChange={() => onChange({ ...f, services: f.services.includes(code) ? f.services.filter((s) => s !== code) : [...f.services, code] })} />
            {text}
          </label>
        ))}
        {errors.services && <span className="text-xs text-destructive">{errors.services}</span>}
      </fieldset>
      <ReasonRow value={f.reason} required={false} error={errors.reason} onChange={(reason) => onChange({ ...f, reason })} />
    </DialogShell>
  )
}

/** FLOW-2c 정지·정지 해제·해지 대화상자(§4B.11 3): '사유 및 비고' 필수, 근거 참조 선택(D-GOV-022). 해지는 확인 단계에서 연결 가맹점 안내 뒤 확정(BR-BIZ-312·321). */
function ContractEventDialog({ dialog, linkedMerchantCount, onChange, onSubmit, ...shared }: Shared & {
  dialog: Extract<Dialog, { kind: 'event' }>
  linkedMerchantCount: number
  onChange: (next: { reason?: string; evidenceRef?: string; confirming?: boolean }) => void
  onSubmit: () => void
}) {
  const label = CONTRACT_EVENT_ACTION_LABELS[dialog.action]
  const confirming = dialog.action === 'TERMINATE' && dialog.confirming
  const submitLabel = dialog.action === 'TERMINATE' ? (confirming ? '해지 확정' : '다음') : `${label} 저장`
  return (
    <DialogShell name={`사업자 계약 ${label}`} shared={shared} submitLabel={submitLabel} onSubmit={onSubmit}>
      {confirming ? (
        <div className="flex flex-col gap-1 text-sm">
          <WarningNote>{terminationNotice(linkedMerchantCount)}</WarningNote>
          <p className="text-xs text-muted-foreground">{TERMINATION_IRREVERSIBLE}</p>
          <Button type="button" size="sm" variant="ghost" onClick={() => onChange({ confirming: false })}>입력 고치기</Button>
        </div>
      ) : (
        <>
          <ReasonRow value={dialog.reason} required error={shared.fieldErrors.reason} onChange={(reason) => onChange({ reason })} />
          <FormRow label="근거 참조(선택)" hint="영문·숫자와 . _ : # / - 만 1~128자. 비워 두어도 됩니다." error={shared.fieldErrors.evidenceRef}>
            {(describedBy, id) => (
              <Input id={id} value={dialog.evidenceRef} onChange={(e) => onChange({ evidenceRef: e.target.value })} aria-describedby={describedBy}
                aria-invalid={!!shared.fieldErrors.evidenceRef || undefined} />
            )}
          </FormRow>
          {/* 리뷰 MINOR-2: 정지·정지 해제에도 같은 n 안내(n = 대화상자 직전 섹션 조회 값, 종료 제외 — BR-BIZ-312·321). */}
          {dialog.action !== 'TERMINATE' && <p className="text-xs text-muted-foreground">{terminationNotice(linkedMerchantCount)}</p>}
          <p className="text-xs text-muted-foreground">즉시 효력 — 새 가맹점 등록 자격만 바뀌고 기존 가맹점의 결제·정산·계약은 바뀌지 않습니다.</p>
        </>
      )}
    </DialogShell>
  )
}
