import { useId, useState, type ReactNode } from 'react'
import type { AdminApi, BusinessChangeInput, BusinessCommandOutcome, BusinessCreateInput, BusinessDetail, BusinessLookupItem } from '../api/adminApi'
import { ApiError } from '../api/client'
import { DigitsInput } from '../components/DigitsInput'
import { Spinner } from '../components/DataBits'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { BackToListButton, MapNote } from '../components/MapNote'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { REJECTION_MESSAGES } from '../components/RegistrationStep'
import { ActionButton } from '../components/ActionButton'
import { FormRow, type FieldWidth } from '../components/FormRow'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Textarea } from '../components/ui/textarea'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import {
  BRN_RATE_LIMITED, COMMAND_RATE_LIMITED, compactForm, contentOf, contentOfDetail, EMPTY_FORM, fieldErrorMap, fieldErrorMessage, formatCorporateNumber, formatPhone,
  formOf, importantChange, REGISTRATION_STATUS_LABELS, REPRESENTATIVE_TYPE_LABELS, reasonError, validateForm, withInputRows, type AddressForm, type BusinessForm,
} from '../lib/businessProfile'
import { defaultSubmissionId } from '../lib/registration'
import { displayRegistrationNumber, isRegistrationNumber } from '../lib/registrationNumber'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { sendUnknownLocked } from '../lib/useUnknownLockedSubmission'

/**
 * 사업자 등록·수정 폼(BUSINESS-PROFILE-001 §8.3 — BIZ-4, 기존 BusinessRegistrationScreen 교체 C-27). 섹션 1 사업자 기본 · 2 사업 내용 · 3 주소 · 4 대표자 · 5 사업체 연락 ·
 * 6 등록 담당자 · 8 버튼(7 증빙은 P1 — 표시하지 않음). 판정·감사·속도 제한은 서버(ADMIN → MASTER), 화면 검사는 같은 규칙의 복제(`lib/businessProfile.ts`).
 * - 생성: 임시저장(DRAFT — 구분·번호·상호, D-BIZ-002) / 등록 완료(REGISTERED). 수정: DRAFT = 임시저장(update)·등록 완료(complete-registration), REGISTERED = 저장(update).
 * - 본문 = 모든 key·선택 값 null·countryCode "KR"(BIZ-3 리뷰 2). 수정·등록 완료에는 번호·상태가 없고 version은 상세 `business.version`(낙관적 잠금).
 * - D-BIZ-005: 생성 모드 번호 칸 아래·저장 버튼 근처에 "저장 후 변경할 수 없습니다"(확인 대화상자 없음). 수정 모드 번호는 읽기 전용(원문 하이픈 표시 — TBD-04, D-BIZ-007).
 * - D-BIZ-003: "중복 확인"은 번호 조회 API(감사·버킷 1회 — 안내일 뿐, 권위는 저장 시 DB). 번호가 바뀌면 결과 즉시 무효. 저장 시 중복이면 같은 안내(기존 상호·번호(원문 하이픈 — D-BIZ-007·D-PII-001 ①)·상세 보기).
 * - 평문 번호는 화면 자체 상태(보낸 즉시 비움 — 화면 전환에도 남지 않음). 재전송할 payload만 [memory]에(기존 리뷰 MINOR-2 규칙).
 * - 결과 불명(공용 `UNKNOWN_CODES` — lib/useUnknownLockedSubmission)은 폼 잠금 + 같은 submissionId·같은 본문 재전송(공용 [sendUnknownLocked] 규칙 — 재전송 중에는 409만 확정).
 *   문구는 code별로 다르다(BIZ-3 리뷰 3). MASTER_BUSY(첫 전송)는 처리 안 됨 — 잠그지 않는다.
 * - 성공하면 [onSaved](reference) — 호출자가 상세를 다시 읽는다(C-18: 성공 응답에 상태·version이 없다). 사업자 ID(biz_)는 그리지 않는다(D-ADMIN-UI-004 ③).
 * - ADMIN-UI-WORKSPACE-001(WS8 — "모양만", [workspace]): 사업자 등록 탭(MenuCard flush)에서는 참고 시안 구성 — .mapnote(h2 + 설명) → .cardnote(필수 안내) →
 *   form(섹션마다 section > .cardtop h3 + .cardbody · 저장 줄 .cardbody) → (있으면) 결과 안내 .cardnote. 버튼은 .btn/.btn.primary, 스피너 대신 보이는 진행 글자.
 *   [workspace]가 없으면(사업자 조회 상세 안의 수정 폼) 껍데기는 PageHeader·기존 버튼 모양(스피너) 그대로. 동작·조건·문구·호출은 두 모양 모두 같다.
 * - LAYOUT-ROLLOUT B5(사용자 결정 2026-11-19 — 모든 입력 화면 폼 줄): 폼 안쪽(번호 섹션 카드 · 폼 줄 FormRow · 줄 끝 짧은 '삭제' · 입력 열 '추가' · 읽기 전용 번호 줄)은
 *   [workspace]와 상관없이 같다 — [workspace]는 껍데기(.mapnote/.cardnote/.cardbody·'← 목록으로'·.btn·보이는 진행 글자)만 정한다.
 */
export type BusinessFormMode = { kind: 'create' } | { kind: 'edit'; detail: BusinessDetail }

interface Props {
  api: AdminApi
  mode: BusinessFormMode
  /** 성공 → 그 사업자 상세 열기(다시 읽기 — C-18). */
  onSaved: (businessId: string) => void
  /** 중복 안내의 "기존 사업자 상세 보기"(D-BIZ-003 — 메모리 라우팅, URL 없음). */
  onOpenBusiness: (businessId: string) => void
  /** 수정 모드 취소(상세로). */
  onCancel?: () => void
  /** version 충돌 뒤 "다시 불러오기"(상세 재조회). */
  onReload?: () => void
  /**
   * 수정 모드 "그래도 새로 입력"(결과 불명 포기 — 리뷰 MINOR-1): 호출자가 편집 메모리를 비우고 상세를 다시 읽는다(옛 내용 + 새 version 조합으로 덮어쓰지 않게).
   * 생성 모드에는 version이 없어 잠금만 푼다.
   */
  onAbandon?: () => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
  /** 화면 상태를 둘 App 메모리(탭 전환에도 유지 — 브라우저 저장소 아님). */
  memory?: ScreenMemory
  /** 메모리 키 접두사(`<prefix>.form`·`<prefix>.cmd.status`…). */
  memoryKey?: string
  /** 참고 시안 껍데기(사업자 등록 탭 — MenuCard flush 안). 없으면 PageHeader 껍데기(사업자 조회 상세 안 수정 폼). 폼 줄·섹션 카드는 둘 다 같다(B5). */
  workspace?: boolean
  /** [workspace] 제목 줄 '← 목록으로'(D-ADMIN-UI-006 — 사업자 탭 목록으로). 화면 메모리(결과 불명 잠금·입력)는 App에 그대로 남는다. 없으면 버튼 없음. [layout] 'detail'에서는 하단 '목록으로'. */
  onBack?: () => void
  /**
   * 칸 배치(ADMIN-DS-001 §6.3 열거형 variant — §5 "사업자 폼 배치"):
   * - 'sections'(기본): 등록 화면 6섹션(1 사업자 기본 · 2 사업 내용 · 3 주소 · 4 대표자 · 5 사업체 연락 · 6 등록 담당자).
   * - 'detail': MERCHANT-IA-001 B5(D-ADMIN-UI-011 ② pg-lab식 — 사업자 상세 = 항상 편집 폼): 섹션 회사 / 대표자 / 담당자, 회사는 2열, 국가 줄 없음(늘 KR — Q-IA-2, 본문은 그대로 KR),
   *   제목 줄 없음(상세 머리가 있다), 하단 '저장'(DRAFT는 '임시저장'·'등록 완료') + '목록으로'. 바뀐 칸이 없으면 '저장'·'임시저장'은 누를 수 없다('등록 완료'는 그대로).
   * 칸·이름·검사·저장 규칙(사유·version·결과 불명 잠금)은 두 배치가 같다.
   */
  layout?: 'sections' | 'detail'
}

type CommandKind = 'createDraft' | 'createRegistered' | 'update' | 'complete'
type CommandPayload = BusinessCreateInput | BusinessChangeInput
interface Pending { submissionId: string; kind: CommandKind; payload: CommandPayload; sentForm: BusinessForm }
type CommandStatus =
  | { kind: 'idle' }
  | { kind: 'busy' }
  | { kind: 'invalid' }
  | { kind: 'decided'; outcome: BusinessCommandOutcome }
  | { kind: 'failed'; code: ConsoleErrorCode }
  | { kind: 'unknown'; code: ConsoleErrorCode }

type DupCheck = { kind: 'free' } | { kind: 'taken'; item: BusinessLookupItem } | { kind: 'error'; message: string }

const IDLE: CommandStatus = Object.freeze({ kind: 'idle' })
const NO_ERRORS: Readonly<Record<string, string>> = Object.freeze({})
const RESEND = '"같은 내용 다시 보내기"'
/** 결과 불명 문구(BIZ-3 리뷰 3 — code별 구분). */
const UNKNOWN_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  MASTER_RETRY_SAME_KEY: `처리 결과를 아직 확정하지 못했습니다. ${RESEND}로 같은 제출 번호를 다시 보내 확인하세요.`,
  MASTER_OUTCOME_UNKNOWN:
    `처리되었는지 알 수 없습니다. ${RESEND}로 같은 제출 번호를 다시 보내 확인하세요. 같은 결과가 반복되면 운영 담당에게 문의하세요(사업자 조회에서 저장 여부를 확인할 수 있습니다).`,
  AUDIT_UNAVAILABLE: `감사 기록을 남기지 못해 결과가 확정되지 않았습니다. ${RESEND}로 확인하세요.`,
  MASTER_BUSY: `사업자 정보 서비스가 바빠 이번 재전송을 처리하지 않았습니다. 앞 요청 결과는 아직 모릅니다 — 잠시 후 ${RESEND}로 확인하세요.`,
  RATE_LIMITED: `재전송이 너무 잦습니다. 앞 요청 결과는 아직 모릅니다 — 잠시 후 ${RESEND}로 확인하세요(Demo 한도 — 운영값 아님).`,
}
/** 확정 실패 문구(MASTER 미호출 또는 효과 없음). */
const FAILED_MESSAGES: Partial<Record<ConsoleErrorCode, string>> = {
  MASTER_BUSY: '사업자 정보 서비스가 바빠 처리하지 않았습니다. 잠시 후 다시 시도하세요.',
  RATE_LIMITED: COMMAND_RATE_LIMITED,
  FORBIDDEN: '이 작업을 할 권한이 없습니다(사업자 등록·수정·등록 완료는 운영자만).',
  INVALID_INPUT: '입력 형식이 올바르지 않습니다. 입력을 확인하세요.',
}

const MAX_REPRESENTATIVES = 5
const MAX_CATEGORIES_PER_TYPE = 10
const MAX_CONTACTS = 5

const DRAFT_HINT = '사업자등록번호는 저장 후 변경할 수 없습니다. 저장 전에 번호를 확인하세요.'

export function BusinessProfileFormScreen({
  api, mode, onSaved, onOpenBusiness, onCancel, onReload, onAbandon, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected, memoryKey = 'form', workspace = false, onBack,
  layout = 'sections',
}: Props) {
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  const detail = mode.kind === 'edit' ? mode.detail : null
  const [initial] = useState<BusinessForm>(() => (detail ? formOf(detail) : EMPTY_FORM))
  const key = (name: string) => `${memoryKey}.${name}`
  const [form, setForm] = useRemembered<BusinessForm>(memory, key('form'), initial)
  const [reason, setReason] = useRemembered(memory, key('reason'), '')
  const [reasonForced, setReasonForced] = useRemembered(memory, key('reasonForced'), false)
  const [errors, setErrors] = useRemembered<Readonly<Record<string, string>>>(memory, key('errors'), NO_ERRORS)
  const [status, setStatus] = useRemembered<CommandStatus>(memory, key('cmd.status'), IDLE)
  const [pending, setPending] = useRemembered<Pending | null>(memory, key('cmd.pending'), null)
  // 평문 번호·중복 확인 결과는 화면 자체 상태(메모리·저장소 없음 — 화면을 바꾸면 비워진다).
  const [number, setNumber] = useState('')
  const [dupCheck, setDupCheck] = useState<DupCheck | null>(null)
  const [dupBusy, setDupBusy] = useState(false)
  const [numberFormatError, setNumberFormatError] = useState(false)
  const [confirmAbandon, setConfirmAbandon] = useState(false)
  const primaryGroup = useId()
  const typeErrorId = useId()

  const locked = status.kind === 'busy' || status.kind === 'unknown'
  const before = detail ? contentOfDetail(detail) : null
  const important = detail !== null && before !== null &&
    importantChange(detail.business.registrationStatus, detail.business.businessType, before, contentOf(compactForm(form)))
  const showReason = detail !== null && (important || reasonForced)
  // 리뷰 NIT-2(§4.5 "일반정보만이면 사유 선택"): 일반 변경만 있으면 선택 사유 칸을 보인다(비우면 null).
  const changed = detail !== null && before !== null && JSON.stringify(before) !== JSON.stringify(contentOf(compactForm(form)))
  const showOptionalReason = detail !== null && !showReason && changed
  /** 칸에 붙일 수 없는 오류(리뷰 NIT-1 — 국가·버전, 숨은 번호·법인번호 칸): 일반 문구로 알린다. */
  const unshownErrors = Object.keys(errors).filter((k) =>
    k === 'registration.countryCode' || k === 'version' || (k === 'registrationNumber' && detail !== null) ||
    (k === 'registration.corporateRegistrationNumber' && form.businessType !== 'CORPORATION') || (k === 'reason' && !showReason && !showOptionalReason))
  const err = (fieldKey: string) => (errors[fieldKey] ? fieldErrorMessage(fieldKey, errors[fieldKey]!) : undefined)
  const update = (patch: Partial<BusinessForm>) => setForm({ ...form, ...patch })

  const clearAll = () => {
    for (const name of ['form', 'reason', 'reasonForced', 'errors', 'cmd.status', 'cmd.pending']) memory.remove(key(name))
    setNumber('')
    setDupCheck(null)
    setNumberFormatError(false)
    setConfirmAbandon(false)
  }

  const call = (kind: CommandKind, payload: CommandPayload): Promise<BusinessCommandOutcome> => {
    if (kind === 'createDraft' || kind === 'createRegistered') return api.createBusiness(payload as BusinessCreateInput)
    const businessId = detail!.business.businessId
    return kind === 'update' ? api.updateBusiness(businessId, payload as BusinessChangeInput) : api.completeBusinessRegistration(businessId, payload as BusinessChangeInput)
  }

  const send = async (next: Pending, retrying: boolean) => {
    setConfirmAbandon(false)
    // 공용 결과 불명 잠금(LOCK-UNIFY-001 — `cmd.status`·`cmd.pending`): 재전송 중에는 409만 확정.
    const result = await sendUnknownLocked(memory, key('cmd'), next, retrying, (p) => call(p.kind, p.payload), onSessionEnded)
    if (result.kind === 'decided') {
      const { outcome } = result
      if (outcome.outcome === 'SUCCEEDED' && outcome.reference) {
        clearAll()
        onSaved(outcome.reference)
        return
      }
      if (outcome.rejection === 'BUSINESS_CHANGE_REASON_REQUIRED') {
        setReasonForced(true)
        setErrors({ reason: 'REQUIRED' })
      }
    } else if (result.kind === 'failed') {
      const { code, error: e } = result
      if (code === 'INVALID_INPUT' && e instanceof ApiError && e.fieldErrors) {
        // 리뷰 NIT-1: 칸이 없는 path — 담당자 기본 지정은 담당자 묶음 오류로, 사유는 사유 칸을 강제로 보인다(그 밖은 일반 문구 — [unshownErrors]).
        const map: Record<string, string> = { ...fieldErrorMap(e.fieldErrors, next.sentForm) }
        for (const k of Object.keys(map)) {
          if (/^contacts\[[0-9]+\]\.primary$/.test(k)) {
            map.contacts ??= map[k]!
            delete map[k]
          }
        }
        if (map.reason) setReasonForced(true)
        setErrors(map)
      }
    }
  }

  const submit = async (kind: CommandKind) => {
    if (locked) return
    const sentForm = compactForm(form)
    setForm(withInputRows(sentForm))
    const creating = kind === 'createDraft' || kind === 'createRegistered'
    const target = kind === 'createRegistered' || kind === 'complete' || (kind === 'update' && detail?.business.registrationStatus === 'REGISTERED') ? 'REGISTERED' : 'DRAFT'
    const found = validateForm(sentForm, creating ? number : null, target)
    const map: Record<string, string> = { ...fieldErrorMap(found, sentForm) }
    if (showReason || showOptionalReason) {
      const problem = reason === '' ? (showReason ? 'REQUIRED' : null) : reasonError(reason)
      if (problem) map.reason = problem
    }
    setErrors(map)
    if (Object.keys(map).length > 0) {
      setStatus({ kind: 'invalid' })
      return
    }
    const content = contentOf(sentForm)
    const submissionId = newSubmissionId()
    const payload: CommandPayload = creating
      ? { submissionId, registrationStatus: kind === 'createDraft' ? 'DRAFT' : 'REGISTERED', registrationNumber: number, ...content }
      : { submissionId, version: detail!.business.version, reason: (showReason || showOptionalReason) && reason !== '' ? reason : null, ...content }
    if (creating) {
      // 평문 번호는 보낸 즉시 비운다(재전송 payload만 메모리 — 기존 규칙).
      setNumber('')
      setDupCheck(null)
    }
    await send({ submissionId, kind, payload, sentForm }, false)
  }

  const resend = async () => {
    if (pending) await send(pending, true)
  }

  const abandon = () => {
    if (detail && onAbandon) {
      onAbandon()
      return
    }
    setPending(null)
    setStatus(IDLE)
    setConfirmAbandon(false)
  }

  const checkDuplicate = async () => {
    if (dupBusy) return
    if (!isRegistrationNumber(number)) {
      setNumberFormatError(true)
      return
    }
    setNumberFormatError(false)
    setDupBusy(true)
    try {
      const result = await api.lookupBusinessByRegistrationNumber(number)
      const item = result.items[0]
      setDupCheck(item ? { kind: 'taken', item } : { kind: 'free' })
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code)) onSessionEnded?.(code)
      else setDupCheck({ kind: 'error', message: code === 'RATE_LIMITED' ? BRN_RATE_LIMITED : ERROR_MESSAGES[code] })
    } finally {
      setDupBusy(false)
    }
  }

  const changeNumber = (digits: string) => {
    setNumber(digits)
    // 지침서 §3: 번호가 바뀌면 중복 확인 결과는 즉시 무효.
    setDupCheck(null)
    setNumberFormatError(false)
  }

  const existingText = (item: BusinessLookupItem) => `${item.businessName} · ${displayRegistrationNumber(item.registrationNumber)}`
  const title = !detail ? '사업자 등록' : detail.business.registrationStatus === 'DRAFT' ? '사업자 이어서 입력' : '사업자 수정'
  const numberError = numberFormatError ? '사업자등록번호는 숫자 10자리입니다.' : err('registrationNumber')

  // ── 목록 행 갱신 ──
  const setRow = <K extends 'businessTypes' | 'businessItems'>(list: K, index: number, value: string) => update({ [list]: form[list].map((v, i) => (i === index ? value : v)) } as Partial<BusinessForm>)
  const addressFields = (prefix: 'registeredAddress' | 'actualAddress', label: string) => {
    const address = form[prefix]
    const set = (patch: Partial<AddressForm>) => update({ [prefix]: { ...address, ...patch } } as Partial<BusinessForm>)
    return (
      <>
        <Row width="xs" label={`${label} 우편번호`} error={err(`${prefix}.postalCode`)}>
          {(describedBy, id) => <Input id={id} value={address.postalCode} inputMode="numeric" autoComplete="off" onChange={(e) => set({ postalCode: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.postalCode`) || undefined} />}
        </Row>
        <Row width="full" label={`${label} 기본주소`} error={err(`${prefix}.baseAddress`)}>
          {(describedBy, id) => <Input id={id} value={address.baseAddress} autoComplete="off" onChange={(e) => set({ baseAddress: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.baseAddress`) || undefined} />}
        </Row>
        <Row width="full" label={`${label} 상세주소(선택)`} error={err(`${prefix}.detailAddress`)}>
          {(describedBy, id) => <Input id={id} value={address.detailAddress} autoComplete="off" onChange={(e) => set({ detailAddress: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.detailAddress`) || undefined} />}
        </Row>
      </>
    )
  }

  // 사용자 피드백 1(WS8 등록 탭): 업태·종목 한 줄 = 폼 줄(이름 왼쪽 · 입력 md 폭 · 같은 줄 입력 높이의 짧은 '삭제' — 접근 가능한 이름은 '업태 n 삭제' 그대로, 보이는 글자는 이름에 포함),
  // '추가'는 줄들 아래 입력 열에 맞춘 작은 보조 버튼(h-8). 최대 개수·비활성 조건 그대로.
  // LAYOUT-ROLLOUT B5(사용자 결정 2026-11-19 — 모든 입력 화면 폼 줄): 수정 폼(workspace 없음)도 같은 줄 구성. 버튼 모양만 [workspace]를 따른다(.btn ↔ 기존 shadcn Button).
  const removeButton = (name: string, onClick: () => void) => (
    <ActionButton workspace={workspace} type="button" variant="outline" aria-label={name} className="h-9 py-0" onClick={onClick}>삭제</ActionButton>
  )
  const addButton = (label: string, disabled: boolean, onClick: () => void) => (
    <ActionButton workspace={workspace} type="button" variant="outline" size="sm" className="h-8 py-0" disabled={disabled} onClick={onClick}>{label}</ActionButton>
  )
  const categoryRows = (list: 'businessTypes' | 'businessItems', label: string) => (
    <div className="flex flex-col">
      {form[list].map((name, i) => (
        <FormRow
          key={i}
          label={`${label} ${i + 1}`}
          width="md"
          error={err(`${list}[${i}]`)}
          trailing={removeButton(`${label} ${i + 1} 삭제`, () => update({ [list]: form[list].filter((_, j) => j !== i) } as Partial<BusinessForm>))}
        >
          {(describedBy, id) => <Input id={id} value={name} autoComplete="off" onChange={(e) => setRow(list, i, e.target.value)} aria-describedby={describedBy} aria-invalid={!!err(`${list}[${i}]`) || undefined} />}
        </FormRow>
      ))}
      <div className="fieldoffset">
        {addButton(`${label} 추가`, form[list].length >= MAX_CATEGORIES_PER_TYPE, () => update({ [list]: [...form[list], ''] } as Partial<BusinessForm>))}
      </div>
    </div>
  )

  // 중복 확인 결과(두 모양 공통 — 문구·role·이름 그대로).
  const dupResult = (
    <>
      {dupCheck?.kind === 'taken' && (
        <div role="status" aria-label="중복 확인 결과" className="flex flex-col gap-2 rounded-md border border-destructive/40 px-3 py-2 text-sm">
          <span>{`이미 등록된 사업자: ${existingText(dupCheck.item)} (${REGISTRATION_STATUS_LABELS[dupCheck.item.registrationStatus] ?? dupCheck.item.registrationStatus})`}</span>
          <span><ActionButton workspace={workspace} type="button" variant="outline" size="sm" onClick={() => onOpenBusiness(dupCheck.item.businessId)}>기존 사업자 상세 보기</ActionButton></span>
        </div>
      )}
      {dupCheck?.kind === 'free' && (
        <div role="status" aria-label="중복 확인 결과" className="rounded-md border px-3 py-2 text-sm">
          등록 가능 — 확인한 시점 기준입니다. 저장 시 다시 확인합니다(다른 운영자가 먼저 저장하면 중복 안내).
        </div>
      )}
      {dupCheck?.kind === 'error' && <WarningNote>{dupCheck.message}</WarningNote>}
    </>
  )

  const description = detail ? `${detail.business.businessName} · ${displayRegistrationNumber(detail.business.registrationNumber)} (${REGISTRATION_STATUS_LABELS[detail.business.registrationStatus] ?? detail.business.registrationStatus})` : '사업자 기본정보를 입력합니다. 임시저장 뒤 이어서 입력할 수 있습니다.'
  const requirements = (
    <p className={workspace ? 'subtle' : 'text-xs text-muted-foreground'}>
      임시저장은 사업체 구분·사업자등록번호·상호만 있으면 됩니다. 등록 완료에는 개업일·사업장 주소·사업체 전화·대표자·업태·종목·등록 담당자(기본 1명)가 필요하고, 법인은 법인등록번호도 필요합니다.
      등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.
    </p>
  )
  const reasonFields = (
    <>
      {showReason && (
        <Row width="full" label="변경 사유" error={err('reason')} hint="중요정보(상호·사업체 구분·대표자·개업일·법인등록번호)를 바꾸려면 사유가 필요합니다. 개인정보를 적지 마세요.">
          {(describedBy, id) => <Textarea id={id} value={reason} rows={2} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} aria-invalid={!!err('reason') || undefined} />}
        </Row>
      )}
      {showOptionalReason && (
        <Row width="full" label="변경 사유(선택)" error={err('reason')} hint="일반정보만 바뀌어 사유는 선택입니다(남기면 변경 이력에 기록). 개인정보를 적지 마세요.">
          {(describedBy, id) => <Textarea id={id} value={reason} rows={2} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} aria-invalid={!!err('reason') || undefined} />}
        </Row>
      )}
    </>
  )
  const hasNotice = status.kind === 'invalid' || status.kind === 'failed' || status.kind === 'unknown' || (status.kind === 'decided' && status.outcome.outcome === 'REJECTED')

  // MERCHANT-IA-001 B5(D-ADMIN-UI-011 ②): 입력 칸 묶음을 한 번만 정의하고 두 배치(등록 6섹션 / 사업자 상세 회사·대표자·담당자 2열)가 같이 쓴다 — 칸·이름·검사·aria 그대로.
  const typeRow = (
            <div role="radiogroup" aria-label="사업체 구분" aria-describedby={err('businessType') ? typeErrorId : undefined} className="fieldrow border-b py-2 text-sm last:border-b-0">
              <span aria-hidden="true" className="text-xs text-muted-foreground">사업체 구분</span>
              <span className="flex flex-wrap gap-4">
                {(['INDIVIDUAL', 'CORPORATION'] as const).map((type) => (
                  <label key={type} className="flex items-center gap-1.5">
                    <input type="radio" name={`${primaryGroup}-type`} value={type} checked={form.businessType === type} onChange={() => update({ businessType: type })} />
                    {type === 'INDIVIDUAL' ? '개인' : '법인'}
                  </label>
                ))}
              </span>
              {err('businessType') && <p id={typeErrorId} className="fieldnote text-xs text-destructive">{err('businessType')}</p>}
            </div>
  )
  const numberRow = <>
            {detail ? (
              // LAYOUT-ROLLOUT B5: 수정 모드 번호는 읽기 전용 줄(B2 ReadOnlyGroup과 같은 방식 — group 이름 = aria-label, 이름 span aria-hidden 왼쪽, 점선 값 상자 sm(번호 10자리 열),
              // 변경 미지원 문구는 입력 열 아래 .fieldnote). 문구 그대로, 번호는 원문 하이픈 표시(D-BIZ-007). workspace + 수정 모드도 같은 줄(리뷰 NIT — 번호 줄 정리).
              <div role="group" aria-label="사업자등록번호" className="fieldrow border-b py-2 text-sm last:border-b-0">
                <span aria-hidden="true" className="text-xs text-muted-foreground">사업자등록번호</span>
                <div className="fieldcontrol fw-sm">
                  <span className="flex min-h-9 items-center rounded-md border border-dashed px-3 font-mono">{displayRegistrationNumber(detail.business.registrationNumber)}</span>
                </div>
                <p className="fieldnote text-xs text-muted-foreground">사업자등록번호 변경은 정책 미정으로 지원하지 않습니다.</p>
              </div>
            ) : (
              // 사용자 피드백 1(WS8 등록 탭): 폼 줄 — '중복 확인'은 입력 바로 오른쪽(입력 높이 h-9, 좁으면 줄바꿈), 도움말·형식 오류(aria-describedby)는 입력 열 아래, 중복 확인 결과는 그 아래 입력 열.
              // 진행 표시: workspace는 보이는 진행 글자(aria-live), 아니면 기존 버튼 안 스피너.
              <div className="flex min-w-0 flex-col">
                <FormRow
                  label="사업자등록번호"
                  width="sm"
                  error={numberError}
                  hint={DRAFT_HINT}
                  trailing={
                    <>
                      <ActionButton workspace={workspace} type="button" variant="outline" className="h-9 py-0" onClick={checkDuplicate} aria-busy={dupBusy}>
                        {!workspace && <Spinner active={dupBusy} />}
                        중복 확인
                      </ActionButton>
                      {workspace && <span className="subtle" aria-live="polite">{dupBusy ? '확인 중…' : ''}</span>}
                    </>
                  }
                >
                  {(describedBy, id) => <RegistrationNumberInput id={id} value={number} onValueChange={changeNumber} aria-describedby={describedBy} aria-invalid={!!numberError || undefined} />}
                </FormRow>
                {dupCheck && <div className="fieldoffset">{dupResult}</div>}
              </div>
            )}
</>
  const nameRow = <>
            <Row width="lg" label="상호" error={err('displayName')}>
              {(describedBy, id) => <Input id={id} value={form.displayName} autoComplete="off" onChange={(e) => update({ displayName: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('displayName') || undefined} />}
            </Row>
</>
  const corpRow = <>
            {form.businessType === 'CORPORATION' && (
              <Row width="sm" label="법인등록번호" error={err('registration.corporateRegistrationNumber')}>
                {(describedBy, id) => (
                  <DigitsInput id={id} value={form.corporateRegistrationNumber} onValueChange={(v) => update({ corporateRegistrationNumber: v })} format={formatCorporateNumber} maxDigits={13} placeholder="000000-0000000"
                    aria-describedby={describedBy} aria-invalid={!!err('registration.corporateRegistrationNumber') || undefined} />
                )}
              </Row>
            )}
</>
  const openingRow = <>
            <Row width="sm" label="개업일" error={err('registration.openingDate')}>
              {(describedBy, id) => <Input id={id} type="date" value={form.openingDate} onChange={(e) => update({ openingDate: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.openingDate') || undefined} />}
            </Row>
</>
  const countryRow = <>
            <Row width="xs" label="국가" hint="대한민국(KR)만 — 국외·비영리 확장은 정책 미정(TBD-10)">
              {(describedBy, id) => <Input id={id} value="KR" readOnly aria-describedby={describedBy} />}
            </Row>
</>
  const englishRow = <>
            <Row width="lg" label="영문 상호(선택)" error={err('registration.englishName')}>
              {(describedBy, id) => <Input id={id} value={form.englishName} autoComplete="off" onChange={(e) => update({ englishName: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.englishName') || undefined} />}
            </Row>
</>
  const addressBlock = <>
            {addressFields('registeredAddress', '사업장')}
            <label className="fieldoffset flex items-center gap-2 text-sm">
              <input type="checkbox" checked={form.actualDifferent} onChange={(e) => update({ actualDifferent: e.target.checked })} />
              실사업장이 사업장과 다름
            </label>
            {form.actualDifferent && addressFields('actualAddress', '실사업장')}
</>
  const representativesBlock = <>
            <div className="flex flex-col">
              {form.representatives.map((r, i) => (
                <div key={i} className="flex flex-col">
                  <FormRow label={`대표자 ${i + 1} 이름`} width="md" error={err(`representatives[${i}].name`)}
                    trailing={removeButton(`대표자 ${i + 1} 삭제`, () => update({ representatives: form.representatives.filter((_, j) => j !== i) }))}>
                    {(describedBy, id) => (
                      <Input id={id} value={r.name} autoComplete="off" onChange={(e) => update({ representatives: form.representatives.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}
                        aria-describedby={describedBy} aria-invalid={!!err(`representatives[${i}].name`) || undefined} />
                    )}
                  </FormRow>
                  <FormRow label={`대표자 ${i + 1} 유형`} width="sm" error={err(`representatives[${i}].type`)}>
                    {(describedBy, id) => (
                      <NativeSelect id={id} aria-label={`대표자 ${i + 1} 유형`} value={r.type} onChange={(e) => update({ representatives: form.representatives.map((x, j) => (j === i ? { ...x, type: e.target.value } : x)) })}
                        aria-describedby={describedBy} aria-invalid={!!err(`representatives[${i}].type`) || undefined}>
                        {Object.entries(REPRESENTATIVE_TYPE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
                      </NativeSelect>
                    )}
                  </FormRow>
                </div>
              ))}
              <p className="fieldoffset m-0 text-xs text-muted-foreground">단독 대표는 1명일 때만, 2명 이상이면 공동·각자를 고르세요.</p>
              <div className="fieldoffset">
                {addButton('대표자 추가', form.representatives.length >= MAX_REPRESENTATIVES, () => update({ representatives: [...form.representatives, { name: '', type: 'JOINT' }] }))}
              </div>
            </div>
</>
  const phoneRow = <>
            <Row width="sm" label="사업체 전화" error={err('registration.phoneNumber')} hint="등록 완료 시 필수">
              {(describedBy, id) => (
                <DigitsInput id={id} value={form.phoneNumber} onValueChange={(v) => update({ phoneNumber: v })} format={formatPhone} maxDigits={12} aria-describedby={describedBy} aria-invalid={!!err('registration.phoneNumber') || undefined} />
              )}
            </Row>
</>
  const emailRow = <>
            <Row width="full" label="사업체 이메일(선택)" error={err('registration.email')}>
              {(describedBy, id) => <Input id={id} value={form.email} autoComplete="off" onChange={(e) => update({ email: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.email') || undefined} />}
            </Row>
</>
  const contactsBlock = <>
            {/*
              사용자 결정(2026-11-19 폼 줄 — B5부터 수정 폼도): 담당자 n = 이름(md)·이메일(full)·전화(sm) 줄 + 입력 열의 기본 담당자·짧은 '삭제'(이름 그대로).
              담당자 사이는 줄 사이 선으로 구분(테두리 상자는 이름 열 정렬을 어긋나게 해 쓰지 않음).
            */}
            <div className="flex flex-col">
              {form.contacts.map((c, i) => {
                const setContact = (patch: Partial<typeof c>) => update({ contacts: form.contacts.map((x, j) => (j === i ? { ...x, ...patch } : x)) })
                return (
                  <div key={i} className="flex flex-col">
                    <FormRow label={`담당자 ${i + 1} 이름`} width="md" error={err(`contacts[${i}].name`)}>
                      {(describedBy, id) => <Input id={id} value={c.name} autoComplete="off" onChange={(e) => setContact({ name: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].name`) || undefined} />}
                    </FormRow>
                    <FormRow label={`담당자 ${i + 1} 이메일`} width="full" error={err(`contacts[${i}].email`)}>
                      {(describedBy, id) => <Input id={id} value={c.email} autoComplete="off" onChange={(e) => setContact({ email: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].email`) || undefined} />}
                    </FormRow>
                    <FormRow label={`담당자 ${i + 1} 전화`} width="sm" error={err(`contacts[${i}].phoneNumber`)}>
                      {(describedBy, id) => (
                        <DigitsInput id={id} value={c.phoneNumber} onValueChange={(v) => setContact({ phoneNumber: v })} format={formatPhone} maxDigits={12} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].phoneNumber`) || undefined} />
                      )}
                    </FormRow>
                    <span className="fieldoffset flex flex-wrap items-center gap-3">
                      <label className="flex items-center gap-1.5 text-sm">
                        <input type="radio" name={`${primaryGroup}-primary`} aria-label={`담당자 ${i + 1} 기본 담당자`} checked={c.primary}
                          onChange={() => update({ contacts: form.contacts.map((x, j) => ({ ...x, primary: j === i })) })} />
                        기본 담당자
                      </label>
                      {removeButton(`담당자 ${i + 1} 삭제`, () => update({ contacts: form.contacts.filter((_, j) => j !== i) }))}
                    </span>
                  </div>
                )
              })}
              <div className="fieldoffset">
                {addButton('담당자 추가', form.contacts.length >= MAX_CONTACTS, () => update({ contacts: [...form.contacts, { name: '', email: '', phoneNumber: '', primary: form.contacts.length === 0 }] }))}
              </div>
            </div>
</>

  const formNode = (
      <form noValidate onSubmit={(e) => e.preventDefault()} className={workspace ? undefined : 'flex flex-col gap-4'}>
        {/* WS8: 시안 구성에서는 섹션(section > .cardtop + .cardbody)이 위아래 여백을 가지므로 fieldset 간격을 두지 않는다. */}
        <fieldset disabled={locked} className={workspace ? 'm-0 min-w-0 border-0 p-0' : 'm-0 flex min-w-0 flex-col gap-4 border-0 p-0'}>
          {/* 사용자 지시(2026-11-19): 섹션 카드는 사업자 조회 상세(DetailInfo)처럼 lg에서 2열 grid — workspace면 카드 좌우 23px(.cardbody). 수정 폼(B5)도 같은 grid. */}
          <Wrap workspace={workspace}>
          <Section label="사업자 기본" no={1}>
            {/* 사용자 결정(2026-11-19 폼 줄 — B5부터 수정 폼도): 같은 줄 모양(.fieldrow — 이름 왼쪽 span aria-hidden · 선택지 오른쪽 · 오류는 입력 열 아래). 이름·aria는 그대로. */}
            {typeRow}
            {numberRow}
            {nameRow}
            {corpRow}
            {openingRow}
            {countryRow}
            {englishRow}
          </Section>

          <Section label="사업 내용" no={2} error={err('categories')}>
            {categoryRows('businessTypes', '업태')}
            {categoryRows('businessItems', '종목')}
          </Section>

          <Section label="주소" no={3} error={err('addresses')}>
            {addressBlock}
          </Section>

          <Section label="대표자" no={4} error={err('representatives')}>
            {/* 사용자 결정(2026-11-19 폼 줄 — B5부터 수정 폼도): 대표자 n = 이름 줄(md + 짧은 '삭제' — 이름 '대표자 n 삭제' 그대로) + 유형 줄(sm). 안내·추가는 입력 열에. */}
            {representativesBlock}
          </Section>

          <Section label="사업체 연락" no={5}>
            {phoneRow}
            {emailRow}
          </Section>

          <Section label="등록 담당자" no={6} error={err('contacts')}>
            {contactsBlock}
          </Section>
          </Wrap>

          {workspace ? (showReason || showOptionalReason) && <div className="cardbody">{reasonFields}</div> : reasonFields}
        </fieldset>

        {/* WS8: 저장 줄은 .cardbody(카드 좌우 23px) 안 오른쪽 — 안내 p.subtle · 저장 group · 보이는 진행 글자. */}
        <div className={workspace ? 'cardbody items-end' : 'flex flex-col items-end gap-2'}>
          {!detail && <p className={workspace ? 'subtle' : 'text-xs text-muted-foreground'}>사업자등록번호는 저장 후 변경할 수 없습니다.</p>}
          <div role="group" aria-label="저장" className="flex flex-wrap justify-end gap-2">
            {(!detail || detail.business.registrationStatus === 'DRAFT') ? (
              <>
                <ActionButton workspace={workspace} type="button" variant="outline" disabled={locked} aria-busy={status.kind === 'busy'} onClick={() => submit(detail ? 'update' : 'createDraft')}>임시저장</ActionButton>
                <ActionButton workspace={workspace} type="button" disabled={locked} onClick={() => submit(detail ? 'complete' : 'createRegistered')}>
                  {!workspace && <Spinner active={status.kind === 'busy'} />}
                  등록 완료
                </ActionButton>
              </>
            ) : (
              <ActionButton workspace={workspace} type="button" disabled={locked} aria-busy={status.kind === 'busy'} onClick={() => submit('update')}>
                {!workspace && <Spinner active={status.kind === 'busy'} />}
                저장
              </ActionButton>
            )}
            <ActionButton workspace={workspace} type="button" variant="ghost" disabled={locked} onClick={() => (detail ? onCancel?.() : clearAll())}>취소</ActionButton>
          </div>
          {workspace && <span className="subtle" aria-live="polite">{status.kind === 'busy' ? '저장 중…' : ''}</span>}
        </div>
      </form>
  )

  // B5 'detail' 배치: 회사(2열) / 대표자 / 담당자 + 하단 저장 줄. 바뀐 칸이 없으면 저장·임시저장 비활성(등록 완료는 그대로 — 바뀐 칸 없이도 등록을 끝낼 수 있다).
  const detailFormNode = detail && (
    <form noValidate onSubmit={(e) => e.preventDefault()} className="flex flex-col gap-4">
      <fieldset disabled={locked} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
        {/* B5 리뷰 NIT-2: 회사 섹션에는 사업 내용·주소 두 목록 오류가 함께 올 수 있다 — 둘 다 보인다. */}
        <Section label="회사" error={[err('categories'), err('addresses')].filter(Boolean).join(' ') || undefined}>
          <div className="grid gap-x-8 lg:grid-cols-2">
            <div className="flex min-w-0 flex-col">
              {typeRow}
              {numberRow}
              {nameRow}
              {corpRow}
              {openingRow}
              {englishRow}
            </div>
            <div className="flex min-w-0 flex-col">
              {categoryRows('businessTypes', '업태')}
              {categoryRows('businessItems', '종목')}
              {phoneRow}
              {emailRow}
            </div>
          </div>
          {addressBlock}
        </Section>
        <Section label="대표자" error={err('representatives')}>{representativesBlock}</Section>
        <Section label="담당자" error={err('contacts')}>{contactsBlock}</Section>
        {reasonFields}
      </fieldset>
      <div className="flex flex-col items-end gap-2">
        <div role="group" aria-label="저장" className="flex flex-wrap justify-end gap-2">
          {detail.business.registrationStatus === 'DRAFT' ? (
            <>
              <ActionButton workspace={false} type="button" variant="outline" disabled={locked || !changed} aria-busy={status.kind === 'busy'} onClick={() => submit('update')}>임시저장</ActionButton>
              <ActionButton workspace={false} type="button" disabled={locked} onClick={() => submit('complete')}>
                <Spinner active={status.kind === 'busy'} />
                등록 완료
              </ActionButton>
            </>
          ) : (
            <ActionButton workspace={false} type="button" disabled={locked || !changed} aria-busy={status.kind === 'busy'} onClick={() => submit('update')}>
              <Spinner active={status.kind === 'busy'} />
              저장
            </ActionButton>
          )}
          {onBack && <ActionButton workspace={false} type="button" variant="outline" onClick={onBack}>목록으로</ActionButton>}
        </div>
        {!changed && !locked && <p className="text-xs text-muted-foreground">바뀐 칸이 없습니다.</p>}
      </div>
    </form>
  )

  const notices = (
    <>
      {status.kind === 'invalid' && <ErrorNotice>입력을 확인하세요 — 표시한 칸을 고친 뒤 다시 저장하세요.</ErrorNotice>}
      {status.kind === 'failed' && (
        <ErrorNotice>
          {status.code === 'INVALID_INPUT' && Object.keys(errors).length > 0
            ? unshownErrors.length > 0
              ? '입력을 확인하세요 — 화면 칸에 표시할 수 없는 오류(국가·버전 등)가 있습니다. 화면을 다시 불러온 뒤 다시 저장하세요.'
              : '입력을 확인하세요 — 서버가 표시한 칸을 고친 뒤 다시 저장하세요.'
            : FAILED_MESSAGES[status.code] ?? ERROR_MESSAGES[status.code]}
        </ErrorNotice>
      )}
      {status.kind === 'decided' && status.outcome.outcome === 'REJECTED' && (
        <ErrorNotice>
          <span className="flex flex-col gap-2">
            <span>
              {REJECTION_MESSAGES[status.outcome.rejection ?? ''] ?? '저장이 거절되었습니다.'}
              {status.outcome.replayed ? ' (이전에 처리된 결과입니다)' : ''}
            </span>
            {status.outcome.existing && (
              <span className="flex flex-wrap items-center gap-2">
                {/* D-BIZ-003: 기존 사업자 상호·번호(원문 하이픈 — D-BIZ-007)·상세 링크(내부 키는 그리지 않는다). */}
                <span>{existingText(status.outcome.existing)}</span>
                <ActionButton workspace={workspace} type="button" variant="outline" size="sm" onClick={() => onOpenBusiness(status.outcome.existing!.businessId)}>기존 사업자 상세 보기</ActionButton>
              </span>
            )}
            {status.outcome.rejection === 'BUSINESS_VERSION_CONFLICT' && onReload && (
              <span><ActionButton workspace={workspace} type="button" variant="outline" size="sm" onClick={onReload}>다시 불러오기</ActionButton></span>
            )}
          </span>
        </ErrorNotice>
      )}
      {status.kind === 'unknown' && (
        <div role="group" aria-label="결과 확인 필요" className="flex flex-col gap-3">
          <ErrorNotice>{`결과 확인 필요 — ${UNKNOWN_MESSAGES[status.code] ?? ERROR_MESSAGES[status.code]} 입력은 잠겨 있습니다.`}</ErrorNotice>
          <div className="flex flex-wrap justify-end gap-2">
            <ActionButton workspace={workspace} type="button" onClick={resend}>같은 내용 다시 보내기</ActionButton>
            <ActionButton workspace={workspace} type="button" variant="outline" onClick={() => setConfirmAbandon(true)}>취소하고 새로 입력</ActionButton>
          </div>
          {confirmAbandon && (
            <div className="flex flex-col gap-2">
              <WarningNote>앞 요청이 이미 처리됐을 수 있습니다 — 사업자 조회로 먼저 확인하세요(같은 번호로 다시 만들면 중복 안내가 나옵니다).</WarningNote>
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
    // WS8: Fragment라 탭 패널 바로 아래 자식 — .mapnote → .cardnote(필수 안내) → form → (있으면) 결과 안내 .cardnote.
    return (
      <>
        <MapNote title={title} action={onBack && <BackToListButton onClick={onBack} />}>
          <span>{description}</span>
        </MapNote>
        <div className="cardnote">{requirements}</div>
        {formNode}
        {hasNotice && <div className="cardnote">{notices}</div>}
      </>
    )
  }
  if (layout === 'detail' && detailFormNode) {
    return (
      <section aria-label="사업자 정보 편집" className="flex flex-col gap-4">
        {detail?.business.registrationStatus === 'DRAFT' && requirements}
        {detailFormNode}
        {notices}
      </section>
    )
  }
  return (
    <section className="flex flex-col gap-6">
      <PageHeader title={title} description={description} />
      {requirements}
      {formNode}
      {notices}
    </section>
  )
}

/**
 * 섹션 카드 grid 감싸개 — 사업자 조회 상세 DetailInfo의 `grid gap-4 lg:grid-cols-2`와 같은 배치. [workspace](flush 탭 패널 안)면 .cardbody 좌우 여백까지,
 * 아니면(사업자 조회 상세 안 수정 폼 — LAYOUT-ROLLOUT B5) 바깥 화면 여백을 그대로 쓴다.
 */
function Wrap({ workspace, children }: { workspace: boolean; children: ReactNode }) {
  return <div className={workspace ? 'cardbody grid gap-4 lg:grid-cols-2' : 'grid gap-4 lg:grid-cols-2'}>{children}</div>
}

/**
 * 칸 하나 = [FormRow](사용자 결정 2026-11-19 폼 줄 — 이름 왼쪽 · 입력 오른쪽 · 입력 폭 [width] — DB 열 길이 단계). 사업자 등록 탭이 처음 적용했고,
 * LAYOUT-ROLLOUT B5부터 사업자 조회 상세 안 수정 폼도 같은 줄이다(껍데기만 [workspace]로 다르다).
 */
function Row({ width, label, hint, error, children }: {
  width: FieldWidth
  label: string
  hint?: ReactNode
  error?: ReactNode
  children: (describedBy: string | undefined, id: string) => ReactNode
}) {
  return <FormRow label={label} hint={hint} error={error} width={width}>{children}</FormRow>
}

/**
 * 섹션 카드(번호 + 이름 — 접근 이름은 이름만). [error]는 목록 전체 오류(예: categories TOO_MANY).
 * 사업자 조회 상세 [InfoCard](DetailCard.tsx)와 같은 번호 섹션 카드(같은 class·h3 `n. 이름` text-sm font-semibold) + 폼 줄 — 상세와 같은 문서를 편집 모드로(사용자 지시 2026-11-19).
 * region 이름(section aria-label) 그대로. LAYOUT-ROLLOUT B5부터 등록 탭·수정 폼 공통.
 */
function Section({ label, no, error, children }: { label: string; no?: number; error?: string; children: ReactNode }) {
  return (
    <section aria-label={label} className="flex flex-col gap-1 rounded-xl border bg-card px-4 py-3">
      {/* B5: 'detail' 배치 섹션(회사·대표자·담당자)은 번호 없이 이름만. */}
      <h3 className="text-sm font-semibold">{no === undefined ? label : `${no}. ${label}`}</h3>
      <div className="flex flex-col">
        {children}
        {error && <p className="py-2 text-xs text-destructive">{error}</p>}
      </div>
    </section>
  )
}
