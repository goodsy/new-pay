import { useId, useState, type ReactNode } from 'react'
import type { AdminApi, BusinessChangeInput, BusinessCommandOutcome, BusinessCreateInput, BusinessDetail, BusinessLookupItem } from '../api/adminApi'
import { ApiError } from '../api/client'
import { DigitsInput } from '../components/DigitsInput'
import { Field, Spinner } from '../components/DataBits'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { REJECTION_MESSAGES } from '../components/RegistrationStep'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
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
import { isRegistrationNumber } from '../lib/registrationNumber'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'

/**
 * 사업자 등록·수정 폼(BUSINESS-PROFILE-001 §8.3 — BIZ-4, 기존 BusinessRegistrationScreen 교체 C-27). 섹션 1 사업자 기본 · 2 사업 내용 · 3 주소 · 4 대표자 · 5 사업체 연락 ·
 * 6 등록 담당자 · 8 버튼(7 증빙은 P1 — 표시하지 않음). 판정·감사·속도 제한은 서버(ADMIN → MASTER), 화면 검사는 같은 규칙의 복제(`lib/businessProfile.ts`).
 * - 생성: 임시저장(DRAFT — 구분·번호·상호, D-BIZ-002) / 등록 완료(REGISTERED). 수정: DRAFT = 임시저장(update)·등록 완료(complete-registration), REGISTERED = 저장(update).
 * - 본문 = 모든 key·선택 값 null·countryCode "KR"(BIZ-3 리뷰 2). 수정·등록 완료에는 번호·상태가 없고 version은 상세 `business.version`(낙관적 잠금).
 * - D-BIZ-005: 생성 모드 번호 칸 아래·저장 버튼 근처에 "저장 후 변경할 수 없습니다"(확인 대화상자 없음). 수정 모드 번호는 읽기 전용(마스킹 — TBD-04).
 * - D-BIZ-003: "중복 확인"은 번호 조회 API(감사·버킷 1회 — 안내일 뿐, 권위는 저장 시 DB). 번호가 바뀌면 결과 즉시 무효. 저장 시 중복이면 같은 안내(기존 상호·마스킹 번호·상세 보기).
 * - 평문 번호는 화면 자체 상태(보낸 즉시 비움 — 화면 전환에도 남지 않음). 재전송할 payload만 [memory]에(기존 리뷰 MINOR-2 규칙).
 * - 결과 불명(503 MASTER_RETRY_SAME_KEY·MASTER_OUTCOME_UNKNOWN·AUDIT_UNAVAILABLE)은 폼 잠금 + 같은 submissionId·같은 본문 재전송(기존 RegistrationStep 규칙 — 재전송 중에는 409만 확정).
 *   문구는 code별로 다르다(BIZ-3 리뷰 3). MASTER_BUSY(첫 전송)는 처리 안 됨 — 잠그지 않는다.
 * - 성공하면 [onSaved](reference) — 호출자가 상세를 다시 읽는다(C-18: 성공 응답에 상태·version이 없다). 사업자 ID(biz_)는 그리지 않는다(D-ADMIN-UI-004 ③).
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
/** 결과를 확정하지 못한 응답 — 같은 submissionId로 다시 보내 확인한다(RegistrationStep과 같다). */
const UNKNOWN_CODES: readonly ConsoleErrorCode[] = ['MASTER_OUTCOME_UNKNOWN', 'MASTER_RETRY_SAME_KEY', 'AUDIT_UNAVAILABLE', 'SERVER_ERROR']
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
  api, mode, onSaved, onOpenBusiness, onCancel, onReload, onAbandon, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected, memoryKey = 'form',
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
    setPending(next)
    setStatus({ kind: 'busy' })
    try {
      const outcome = await call(next.kind, next.payload)
      if (outcome.outcome === 'SUCCEEDED' && outcome.reference) {
        clearAll()
        onSaved(outcome.reference)
        return
      }
      setPending(null)
      setStatus({ kind: 'decided', outcome })
      if (outcome.rejection === 'BUSINESS_CHANGE_REASON_REQUIRED') {
        setReasonForced(true)
        setErrors({ reason: 'REQUIRED' })
      }
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code)) {
        onSessionEnded?.(code)
        return
      }
      const conflict = e instanceof ApiError && e.status === 409
      if (UNKNOWN_CODES.includes(code) || (retrying && !conflict)) {
        setStatus({ kind: 'unknown', code })
        return
      }
      setPending(null)
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
      setStatus({ kind: 'failed', code })
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

  const existingText = (item: BusinessLookupItem) => `${item.businessName} · ${item.registrationNumberMasked}`
  const title = !detail ? '사업자 등록' : detail.business.registrationStatus === 'DRAFT' ? '사업자 이어서 입력' : '사업자 수정'
  const numberError = numberFormatError ? '사업자등록번호는 숫자 10자리입니다.' : err('registrationNumber')

  // ── 목록 행 갱신 ──
  const setRow = <K extends 'businessTypes' | 'businessItems'>(list: K, index: number, value: string) => update({ [list]: form[list].map((v, i) => (i === index ? value : v)) } as Partial<BusinessForm>)
  const addressFields = (prefix: 'registeredAddress' | 'actualAddress', label: string) => {
    const address = form[prefix]
    const set = (patch: Partial<AddressForm>) => update({ [prefix]: { ...address, ...patch } } as Partial<BusinessForm>)
    return (
      <>
        <Field label={`${label} 우편번호`} error={err(`${prefix}.postalCode`)}>
          {(describedBy) => <Input value={address.postalCode} inputMode="numeric" autoComplete="off" onChange={(e) => set({ postalCode: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.postalCode`) || undefined} />}
        </Field>
        <Field label={`${label} 기본주소`} error={err(`${prefix}.baseAddress`)} className="sm:col-span-2">
          {(describedBy) => <Input value={address.baseAddress} autoComplete="off" onChange={(e) => set({ baseAddress: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.baseAddress`) || undefined} />}
        </Field>
        <Field label={`${label} 상세주소(선택)`} error={err(`${prefix}.detailAddress`)} className="sm:col-span-3">
          {(describedBy) => <Input value={address.detailAddress} autoComplete="off" onChange={(e) => set({ detailAddress: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`${prefix}.detailAddress`) || undefined} />}
        </Field>
      </>
    )
  }

  const categoryRows = (list: 'businessTypes' | 'businessItems', label: string) => (
    <div className="flex flex-col gap-2">
      {form[list].map((name, i) => (
        <div key={i} className="flex items-end gap-2">
          <Field label={`${label} ${i + 1}`} error={err(`${list}[${i}]`)} className="flex-1">
            {(describedBy) => <Input value={name} autoComplete="off" onChange={(e) => setRow(list, i, e.target.value)} aria-describedby={describedBy} aria-invalid={!!err(`${list}[${i}]`) || undefined} />}
          </Field>
          <Button type="button" variant="outline" size="sm" onClick={() => update({ [list]: form[list].filter((_, j) => j !== i) } as Partial<BusinessForm>)}>{`${label} ${i + 1} 삭제`}</Button>
        </div>
      ))}
      <div>
        <Button type="button" variant="outline" size="sm" disabled={form[list].length >= MAX_CATEGORIES_PER_TYPE} onClick={() => update({ [list]: [...form[list], ''] } as Partial<BusinessForm>)}>{`${label} 추가`}</Button>
      </div>
    </div>
  )

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title={title}
        description={detail ? `${detail.business.businessName} · ${detail.business.registrationNumberMasked} (${REGISTRATION_STATUS_LABELS[detail.business.registrationStatus] ?? detail.business.registrationStatus})` : '사업자 기본정보를 입력합니다. 임시저장 뒤 이어서 입력할 수 있습니다.'}
      />
      <p className="text-xs text-muted-foreground">
        임시저장은 사업체 구분·사업자등록번호·상호만 있으면 됩니다. 등록 완료에는 개업일·사업장 주소·사업체 전화·대표자·업태·종목·등록 담당자(기본 1명)가 필요하고, 법인은 법인등록번호도 필요합니다.
        등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.
      </p>
      <form noValidate onSubmit={(e) => e.preventDefault()} className="flex flex-col gap-4">
        <fieldset disabled={locked} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
          <Section label="사업자 기본" no={1}>
            <div role="radiogroup" aria-label="사업체 구분" aria-describedby={err('businessType') ? typeErrorId : undefined} className="flex flex-col gap-1.5 text-sm sm:col-span-3">
              <span aria-hidden="true" className="font-medium">사업체 구분</span>
              <span className="flex gap-4">
                {(['INDIVIDUAL', 'CORPORATION'] as const).map((type) => (
                  <label key={type} className="flex items-center gap-1.5">
                    <input type="radio" name={`${primaryGroup}-type`} value={type} checked={form.businessType === type} onChange={() => update({ businessType: type })} />
                    {type === 'INDIVIDUAL' ? '개인' : '법인'}
                  </label>
                ))}
              </span>
              {err('businessType') && <p id={typeErrorId} className="text-xs text-destructive">{err('businessType')}</p>}
            </div>
            {detail ? (
              <div role="group" aria-label="사업자등록번호" className="flex flex-col gap-1.5 text-sm">
                <span aria-hidden="true" className="font-medium">사업자등록번호</span>
                <span className="flex min-h-9 items-center rounded-md border border-dashed px-3 font-mono">{detail.business.registrationNumberMasked}</span>
                <p className="text-xs text-muted-foreground">사업자등록번호 변경은 정책 미정으로 지원하지 않습니다.</p>
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <Field label="사업자등록번호" error={numberError} hint={DRAFT_HINT}>
                  {(describedBy) => <RegistrationNumberInput value={number} onValueChange={changeNumber} aria-describedby={describedBy} aria-invalid={!!numberError || undefined} />}
                </Field>
                <div>
                  <Button type="button" variant="outline" size="sm" onClick={checkDuplicate} aria-busy={dupBusy}>
                    <Spinner active={dupBusy} />
                    중복 확인
                  </Button>
                </div>
                {dupCheck?.kind === 'taken' && (
                  <div role="status" aria-label="중복 확인 결과" className="flex flex-col gap-2 rounded-md border border-destructive/40 px-3 py-2 text-sm">
                    <span>{`이미 등록된 사업자: ${existingText(dupCheck.item)} (${REGISTRATION_STATUS_LABELS[dupCheck.item.registrationStatus] ?? dupCheck.item.registrationStatus})`}</span>
                    <span><Button type="button" variant="outline" size="sm" onClick={() => onOpenBusiness(dupCheck.item.businessId)}>기존 사업자 상세 보기</Button></span>
                  </div>
                )}
                {dupCheck?.kind === 'free' && (
                  <div role="status" aria-label="중복 확인 결과" className="rounded-md border px-3 py-2 text-sm">
                    등록 가능 — 확인한 시점 기준입니다. 저장 시 다시 확인합니다(다른 운영자가 먼저 저장하면 중복 안내).
                  </div>
                )}
                {dupCheck?.kind === 'error' && <WarningNote>{dupCheck.message}</WarningNote>}
              </div>
            )}
            <Field label="상호" error={err('displayName')}>
              {(describedBy) => <Input value={form.displayName} autoComplete="off" onChange={(e) => update({ displayName: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('displayName') || undefined} />}
            </Field>
            {form.businessType === 'CORPORATION' && (
              <Field label="법인등록번호" error={err('registration.corporateRegistrationNumber')}>
                {(describedBy) => (
                  <DigitsInput value={form.corporateRegistrationNumber} onValueChange={(v) => update({ corporateRegistrationNumber: v })} format={formatCorporateNumber} maxDigits={13} placeholder="000000-0000000"
                    aria-describedby={describedBy} aria-invalid={!!err('registration.corporateRegistrationNumber') || undefined} />
                )}
              </Field>
            )}
            <Field label="개업일" error={err('registration.openingDate')}>
              {(describedBy) => <Input type="date" value={form.openingDate} onChange={(e) => update({ openingDate: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.openingDate') || undefined} />}
            </Field>
            <Field label="국가" hint="대한민국(KR)만 — 국외·비영리 확장은 정책 미정(TBD-10)">
              {(describedBy) => <Input value="KR" readOnly aria-describedby={describedBy} />}
            </Field>
            <Field label="영문 상호(선택)" error={err('registration.englishName')}>
              {(describedBy) => <Input value={form.englishName} autoComplete="off" onChange={(e) => update({ englishName: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.englishName') || undefined} />}
            </Field>
          </Section>

          <Section label="사업 내용" no={2} error={err('categories')}>
            {categoryRows('businessTypes', '업태')}
            {categoryRows('businessItems', '종목')}
          </Section>

          <Section label="주소" no={3} error={err('addresses')}>
            {addressFields('registeredAddress', '사업장')}
            <label className="flex items-center gap-2 text-sm sm:col-span-3">
              <input type="checkbox" checked={form.actualDifferent} onChange={(e) => update({ actualDifferent: e.target.checked })} />
              실사업장이 사업장과 다름
            </label>
            {form.actualDifferent && addressFields('actualAddress', '실사업장')}
          </Section>

          <Section label="대표자" no={4} error={err('representatives')}>
            <div className="flex flex-col gap-2 sm:col-span-3">
              {form.representatives.map((r, i) => (
                <div key={i} className="flex flex-wrap items-end gap-2">
                  <Field label={`대표자 ${i + 1} 이름`} error={err(`representatives[${i}].name`)} className="flex-1">
                    {(describedBy) => (
                      <Input value={r.name} autoComplete="off" onChange={(e) => update({ representatives: form.representatives.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}
                        aria-describedby={describedBy} aria-invalid={!!err(`representatives[${i}].name`) || undefined} />
                    )}
                  </Field>
                  <Field label={`대표자 ${i + 1} 유형`} error={err(`representatives[${i}].type`)}>
                    {(describedBy) => (
                      <NativeSelect aria-label={`대표자 ${i + 1} 유형`} value={r.type} onChange={(e) => update({ representatives: form.representatives.map((x, j) => (j === i ? { ...x, type: e.target.value } : x)) })}
                        aria-describedby={describedBy} aria-invalid={!!err(`representatives[${i}].type`) || undefined}>
                        {Object.entries(REPRESENTATIVE_TYPE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
                      </NativeSelect>
                    )}
                  </Field>
                  <Button type="button" variant="outline" size="sm" onClick={() => update({ representatives: form.representatives.filter((_, j) => j !== i) })}>{`대표자 ${i + 1} 삭제`}</Button>
                </div>
              ))}
              <p className="text-xs text-muted-foreground">단독 대표는 1명일 때만, 2명 이상이면 공동·각자를 고르세요.</p>
              <div>
                <Button type="button" variant="outline" size="sm" disabled={form.representatives.length >= MAX_REPRESENTATIVES} onClick={() => update({ representatives: [...form.representatives, { name: '', type: 'JOINT' }] })}>대표자 추가</Button>
              </div>
            </div>
          </Section>

          <Section label="사업체 연락" no={5}>
            <Field label="사업체 전화" error={err('registration.phoneNumber')} hint="등록 완료 시 필수">
              {(describedBy) => (
                <DigitsInput value={form.phoneNumber} onValueChange={(v) => update({ phoneNumber: v })} format={formatPhone} maxDigits={12} aria-describedby={describedBy} aria-invalid={!!err('registration.phoneNumber') || undefined} />
              )}
            </Field>
            <Field label="사업체 이메일(선택)" error={err('registration.email')}>
              {(describedBy) => <Input value={form.email} autoComplete="off" onChange={(e) => update({ email: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err('registration.email') || undefined} />}
            </Field>
          </Section>

          <Section label="등록 담당자" no={6} error={err('contacts')}>
            <div className="flex flex-col gap-3 sm:col-span-3">
              {form.contacts.map((c, i) => {
                const setContact = (patch: Partial<typeof c>) => update({ contacts: form.contacts.map((x, j) => (j === i ? { ...x, ...patch } : x)) })
                return (
                  <div key={i} className="grid gap-2 rounded-md border px-3 py-2.5 sm:grid-cols-3">
                    <Field label={`담당자 ${i + 1} 이름`} error={err(`contacts[${i}].name`)}>
                      {(describedBy) => <Input value={c.name} autoComplete="off" onChange={(e) => setContact({ name: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].name`) || undefined} />}
                    </Field>
                    <Field label={`담당자 ${i + 1} 이메일`} error={err(`contacts[${i}].email`)}>
                      {(describedBy) => <Input value={c.email} autoComplete="off" onChange={(e) => setContact({ email: e.target.value })} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].email`) || undefined} />}
                    </Field>
                    <Field label={`담당자 ${i + 1} 전화`} error={err(`contacts[${i}].phoneNumber`)}>
                      {(describedBy) => (
                        <DigitsInput value={c.phoneNumber} onValueChange={(v) => setContact({ phoneNumber: v })} format={formatPhone} maxDigits={12} aria-describedby={describedBy} aria-invalid={!!err(`contacts[${i}].phoneNumber`) || undefined} />
                      )}
                    </Field>
                    <span className="flex flex-wrap items-center gap-3 sm:col-span-3">
                      <label className="flex items-center gap-1.5 text-sm">
                        <input type="radio" name={`${primaryGroup}-primary`} aria-label={`담당자 ${i + 1} 기본 담당자`} checked={c.primary}
                          onChange={() => update({ contacts: form.contacts.map((x, j) => ({ ...x, primary: j === i })) })} />
                        기본 담당자
                      </label>
                      <Button type="button" variant="outline" size="sm" onClick={() => update({ contacts: form.contacts.filter((_, j) => j !== i) })}>{`담당자 ${i + 1} 삭제`}</Button>
                    </span>
                  </div>
                )
              })}
              <div>
                <Button type="button" variant="outline" size="sm" disabled={form.contacts.length >= MAX_CONTACTS}
                  onClick={() => update({ contacts: [...form.contacts, { name: '', email: '', phoneNumber: '', primary: form.contacts.length === 0 }] })}>담당자 추가</Button>
              </div>
            </div>
          </Section>

          {showReason && (
            <Field label="변경 사유" error={err('reason')} hint="중요정보(상호·사업체 구분·대표자·개업일·법인등록번호)를 바꾸려면 사유가 필요합니다. 개인정보를 적지 마세요.">
              {(describedBy) => <Textarea value={reason} rows={2} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} aria-invalid={!!err('reason') || undefined} />}
            </Field>
          )}
          {showOptionalReason && (
            <Field label="변경 사유(선택)" error={err('reason')} hint="일반정보만 바뀌어 사유는 선택입니다(남기면 변경 이력에 기록). 개인정보를 적지 마세요.">
              {(describedBy) => <Textarea value={reason} rows={2} onChange={(e) => setReason(e.target.value)} aria-describedby={describedBy} aria-invalid={!!err('reason') || undefined} />}
            </Field>
          )}
        </fieldset>

        <div className="flex flex-col items-end gap-2">
          {!detail && <p className="text-xs text-muted-foreground">사업자등록번호는 저장 후 변경할 수 없습니다.</p>}
          <div role="group" aria-label="저장" className="flex flex-wrap justify-end gap-2">
            {(!detail || detail.business.registrationStatus === 'DRAFT') ? (
              <>
                <Button type="button" variant="outline" disabled={locked} aria-busy={status.kind === 'busy'} onClick={() => submit(detail ? 'update' : 'createDraft')}>임시저장</Button>
                <Button type="button" disabled={locked} onClick={() => submit(detail ? 'complete' : 'createRegistered')}>
                  <Spinner active={status.kind === 'busy'} />
                  등록 완료
                </Button>
              </>
            ) : (
              <Button type="button" disabled={locked} aria-busy={status.kind === 'busy'} onClick={() => submit('update')}>
                <Spinner active={status.kind === 'busy'} />
                저장
              </Button>
            )}
            <Button type="button" variant="ghost" disabled={locked} onClick={() => (detail ? onCancel?.() : clearAll())}>취소</Button>
          </div>
        </div>
      </form>

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
                {/* D-BIZ-003: 기존 사업자 상호·마스킹 번호·상세 링크(내부 키는 그리지 않는다). */}
                <span>{existingText(status.outcome.existing)}</span>
                <Button type="button" variant="outline" size="sm" onClick={() => onOpenBusiness(status.outcome.existing!.businessId)}>기존 사업자 상세 보기</Button>
              </span>
            )}
            {status.outcome.rejection === 'BUSINESS_VERSION_CONFLICT' && onReload && (
              <span><Button type="button" variant="outline" size="sm" onClick={onReload}>다시 불러오기</Button></span>
            )}
          </span>
        </ErrorNotice>
      )}
      {status.kind === 'unknown' && (
        <div role="group" aria-label="결과 확인 필요" className="flex flex-col gap-3">
          <ErrorNotice>{`결과 확인 필요 — ${UNKNOWN_MESSAGES[status.code] ?? ERROR_MESSAGES[status.code]} 입력은 잠겨 있습니다.`}</ErrorNotice>
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" onClick={resend}>같은 내용 다시 보내기</Button>
            <Button type="button" variant="outline" onClick={() => setConfirmAbandon(true)}>취소하고 새로 입력</Button>
          </div>
          {confirmAbandon && (
            <div className="flex flex-col gap-2">
              <WarningNote>앞 요청이 이미 처리됐을 수 있습니다 — 사업자 조회로 먼저 확인하세요(같은 번호로 다시 만들면 중복 안내가 나옵니다).</WarningNote>
              <div className="flex justify-end">
                <Button type="button" variant="destructive" onClick={abandon}>그래도 새로 입력</Button>
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  )
}

/** 섹션 카드(번호 + 이름 — 접근 이름은 이름만). [error]는 목록 전체 오류(예: categories TOO_MANY). */
function Section({ label, no, error, children }: { label: string; no: number; error?: string; children: ReactNode }) {
  return (
    <Card as="section" aria-label={label} className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-sm">{`${no}. ${label}`}</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4 px-5 sm:grid-cols-2 lg:grid-cols-3">
        {children}
        {error && <p className="text-xs text-destructive sm:col-span-3">{error}</p>}
      </CardContent>
    </Card>
  )
}
