import type {
  BusinessContractView, BusinessContractVersionView, ContractIntakeKind, ContractIntakeView, ContractProgress, ContractTransitionView,
} from '../api/adminApi'
import { isCalendarDate, reasonError } from './businessProfile'

/**
 * 사업자 상세 '계약 진행' 섹션 표시·입력 규칙(MERCHANT-FLOW-001 §4A.3·§4A.4·§4A.6·§4A.9·§4B.3·§4B.11 — FLOW-1 읽기 + FLOW-2b 사업자 계약 칸·명령).
 * - '계약 완료'는 영업 기록이다. 가맹점 계약 승인(결제 허용·MID 발급 근거)과 다른 말로 둔다(R-1) — 상태 이름에 '승인'을 쓰지 않는다.
 * - 이관 건(§4A.9)은 근거·담당자·접수 항목 칸을 '이관'으로 그린다(R-6). 이관 완료 건 요약 = "계약 완료(이관) · 근거: 이관".
 * - FLOW-2b: 접수 건 `TERMINATED`(계약 해지) 없음 — 해지는 사업자 계약 명령(FLOW-2c). 입력 검사는 즉시 안내용이고 판정 권위는 서버(ADMIN 1차·MASTER 최종)다.
 */
export const CONTRACT_STATUS_LABELS: Readonly<Record<string, string>> = {
  CONSULTING: '상담 중',
  DOCUMENT_RECEIVED: '계약서 접수',
  COMPLETED: '계약 완료',
  REJECTED: '계약 불가',
}
export const INTAKE_KIND_LABELS: Readonly<Record<ContractIntakeKind, string>> = { NEW: '신규', CHANGE: '변경' }
export const BUSINESS_CONTRACT_STATUS_LABELS: Readonly<Record<string, string>> = {
  NOT_YET_EFFECTIVE: '효력 전',
  ACTIVE: '유효',
  SUSPENDED: '정지',
  TERMINATED: '해지',
  EXPIRED: '만료',
}
export const CONTRACT_EVENT_LABELS: Readonly<Record<string, string>> = { SUSPEND: '정지', RESUME: '정지 해제', TERMINATE: '해지' }
export const INTAKE_CHANNEL_LABELS: Readonly<Record<string, string>> = {
  DIRECT_SALES: '직접 영업',
  PHONE: '전화',
  WEBSITE: '홈페이지',
  SALES_AGENCY: '영업대행사',
  OTHER: '기타',
}
/** 접수 경로 선택지(§4A.6 고정 목록 순서). '영업대행사'는 FLOW-4(대행사 목록) 전까지 고를 수 없다(서버도 NOT_ALLOWED). */
export const INTAKE_CHANNEL_OPTIONS: readonly { code: string; label: string; disabled: boolean }[] = [
  { code: 'DIRECT_SALES', label: '직접 영업', disabled: false },
  { code: 'PHONE', label: '전화', disabled: false },
  { code: 'WEBSITE', label: '홈페이지', disabled: false },
  { code: 'SALES_AGENCY', label: '영업대행사 — 준비 전', disabled: true },
  { code: 'OTHER', label: '기타', disabled: false },
]
export const DESIRED_SERVICE_LABELS: Readonly<Record<string, string>> = { CARD_AUTH: '카드 인증 결제', CARD_KEYIN: '카드 키인 결제' }
/** 사업자 계약 범위(BR-BIZ-329 — 지금은 카드 PG 결제만). 희망 서비스와 다른 값이고 자동 복사하지 않는다(BR-BIZ-330). */
export const CONTRACT_SERVICE_LABELS: Readonly<Record<string, string>> = { CARD_PG_PAYMENT: '카드 PG 결제' }

export const MIGRATED = '이관'
/** 계정을 찾지 못한 관리자 처리자(원문 키를 대신 보이지 않는다 — m-11). */
export const UNKNOWN_ACTOR = '확인 불가'
/** §4A.6 [구현 관례 문구]. */
export const DESIRED_SERVICE_NOTE = '희망 서비스 — 참고용, 실제 서비스는 가맹점 계약 승인·개통에서 정합니다'
/** §4B.11 1(R-1). */
export const BUSINESS_CONTRACT_NOTE = '사업자 계약은 새 가맹점 등록 자격입니다 — 결제 허용은 가맹점 계약 승인에서 정합니다'
export const NO_BUSINESS_CONTRACT = '사업자 계약 없음 — 신규 접수 후 계약 완료가 필요합니다'
/** §4B.3 불변 행 — 계약 완료 대화상자 오타 정정 안내. */
export const VERSION_IMMUTABLE_NOTE = '저장 뒤에는 버전 내용(기간·범위)을 고칠 수 없습니다. 같은 날 만든 버전의 오타는 다음 날 이후 시작하는 새 버전(변경 접수 → 계약 완료)으로만 정정합니다'
/** BR-BIZ-333: 변경 접수가 열려 있고 대상 계약이 해지·만료. */
export const NOT_CHANGEABLE_NOTE = '계약이 해지·만료되어 계약 불가로만 닫을 수 있습니다'

const label = (labels: Readonly<Record<string, string>>, code: string) => labels[code] ?? code

/** 접수 건 요약 줄: 상태(이관 완료 = '계약 완료(이관)') + 계약 완료 건이면 " · 근거: …"(이관 건 = '이관', 아니면 계약 완료 전이의 근거 참조 원문). */
export function intakeSummary(intake: ContractIntakeView): string {
  const status = intake.migrated && intake.status === 'COMPLETED' ? `${CONTRACT_STATUS_LABELS.COMPLETED}(${MIGRATED})` : label(CONTRACT_STATUS_LABELS, intake.status)
  if (!intake.migrated && intake.status !== 'COMPLETED') return status
  const evidence = intake.migrated ? MIGRATED : (intake.transitions.find((t) => t.toStatus === 'COMPLETED')?.documentRef ?? '-')
  return `${status} · 근거: ${evidence}`
}

export function transitionChange(t: ContractTransitionView): string {
  const from = t.fromStatus === null ? (t.migrated ? MIGRATED : '접수') : label(CONTRACT_STATUS_LABELS, t.fromStatus)
  return `${from} → ${label(CONTRACT_STATUS_LABELS, t.toStatus)}`
}

export const transitionEvidence = (t: ContractTransitionView): string => (t.migrated ? MIGRATED : (t.documentRef ?? '-'))

/** 처리자(전이·버전·사건 공통): ADMIN 표시명, 시스템 이관 행 '이관', 그 밖 시스템 '시스템', 계정을 찾지 못하면 '확인 불가'. */
export function actorLabel(a: { actorKind: 'ADMIN' | 'SYSTEM'; actorName: string | null; migrated?: boolean }): string {
  if (a.actorKind === 'SYSTEM') return a.migrated ? MIGRATED : '시스템'
  return a.actorName ?? UNKNOWN_ACTOR
}
export const transitionActor = (t: ContractTransitionView): string => actorLabel(t)

export const intakeChannel = (intake: ContractIntakeView): string =>
  intake.migrated ? MIGRATED : intake.intakeChannel === null ? '-' : label(INTAKE_CHANNEL_LABELS, intake.intakeChannel)

export const contractManager = (intake: ContractIntakeView): string => (intake.migrated ? MIGRATED : (intake.contractManagerName ?? UNKNOWN_ACTOR))

export function desiredServices(intake: ContractIntakeView): string {
  if (intake.migrated) return MIGRATED
  return intake.desiredServices.length === 0 ? '선택 없음' : intake.desiredServices.map((s) => label(DESIRED_SERVICE_LABELS, s)).join(', ')
}

// ── 사업자 계약 칸(§4B.11 1) ─────────────────────────────────────

export const contractServices = (v: BusinessContractVersionView): string => v.services.map((s) => label(CONTRACT_SERVICE_LABELS, s)).join(', ')
/** 기간 표시: 이관 = '이관', 일반 = "시작일 ~ 종료일"(종료일 없으면 '종료일 없음'). */
export const versionPeriod = (v: BusinessContractVersionView): string =>
  v.migrated || v.startDate === null ? MIGRATED : `${v.startDate} ~ ${v.endDate ?? '종료일 없음'}`
/** 버전 한 줄: "버전 n · 범위 · 기간". */
export const versionLine = (v: BusinessContractVersionView): string => `버전 ${v.versionNo} · ${contractServices(v)} · ${versionPeriod(v)}`
export const versionEvidence = (v: BusinessContractVersionView): string => (v.migrated ? MIGRATED : (v.documentRef ?? '-'))
export const versionOf = (c: BusinessContractView, no: number | null): BusinessContractVersionView | null =>
  no === null ? null : (c.versions.find((v) => v.versionNo === no) ?? null)

/** 계약이 있는 상태(효력 전·유효·정지 — 다음 접수는 변경, BR-BIZ-325). 해지·만료는 종결. */
const HAS_CONTRACT = new Set(['NOT_YET_EFFECTIVE', 'ACTIVE', 'SUSPENDED'])
const OPEN = new Set(['CONSULTING', 'DOCUMENT_RECEIVED'])
export const isOpenIntake = (intake: ContractIntakeView) => OPEN.has(intake.status)

/**
 * 화면이 판정한 다음 접수 종류(§4B.6 — 서버가 잠금 뒤 다시 판정하고 다르면 `CONTRACT_INTAKE_KIND_MISMATCH`): 열린 건이 있으면 null(새 접수 불가 — `CONTRACT_INTAKE_OPEN_EXISTS`),
 * 최신 계약이 효력 전·유효·정지면 CHANGE, 없음·해지·만료면 NEW.
 */
export function nextIntakeKind(progress: ContractProgress): ContractIntakeKind | null {
  if (progress.intakes.some(isOpenIntake)) return null
  const c = progress.businessContract
  return c && HAS_CONTRACT.has(c.status) ? 'CHANGE' : 'NEW'
}

/** 변경 접수인데 대상 계약이 최신 계약이 아니거나 해지·만료(BR-BIZ-333 — 계약 완료 불가, 계약 불가로만 닫는다). */
export function changeBlocked(intake: ContractIntakeView, c: BusinessContractView | null | undefined): boolean {
  if (intake.kind !== 'CHANGE' || !isOpenIntake(intake)) return false
  return !c || c.contractSeq !== intake.targetContractSeq || !HAS_CONTRACT.has(c.status)
}

export type IntakeAction = 'DOCUMENT_RECEIVED' | 'CONSULTING' | 'COMPLETE' | 'REJECTED'
export const INTAKE_ACTION_LABELS: Readonly<Record<IntakeAction, string>> = {
  DOCUMENT_RECEIVED: '계약서 접수',
  CONSULTING: '되돌리기',
  COMPLETE: '계약 완료',
  REJECTED: '계약 불가',
}

/** 그 상태에서 허용되는 동작만(§4A.3 전이표 — 상담 중 → 계약 완료 건너뛰기 없음, 종결 건은 없음). */
export function allowedActions(intake: ContractIntakeView, c: BusinessContractView | null | undefined): IntakeAction[] {
  if (intake.migrated) return []
  if (intake.status === 'CONSULTING') return ['DOCUMENT_RECEIVED', 'REJECTED']
  if (intake.status === 'DOCUMENT_RECEIVED') return changeBlocked(intake, c) ? ['CONSULTING', 'REJECTED'] : ['CONSULTING', 'COMPLETE', 'REJECTED']
  return []
}

/** '사유 및 비고'가 필수인 동작(§4A.3a — 되돌리기·계약 불가). */
export const reasonRequired = (action: IntakeAction) => action === 'CONSULTING' || action === 'REJECTED'

// ── 입력 검사(즉시 안내 — 서버가 권위) ─────────────────────────────

const DOCUMENT_REF = /^[A-Za-z0-9._:#/-]{1,128}$/
/** 카드번호 패턴(서버 `RegistrationInputRules.CARD_NUMBER`·`reasonError`와 같은 식 — 근거 참조에도 적용, §4A.4). */
const CARD_NUMBER = /(?<!\p{Nd})(?:\p{Nd}[ -]?){12,18}\p{Nd}(?!\p{Nd})/u

export const CONTRACT_INPUT_MESSAGES = {
  reasonRequired: "'사유 및 비고'를 입력하세요.",
  reasonTooLong: "'사유 및 비고'는 200자 이하로 입력하세요.",
  reasonFormat: "'사유 및 비고'는 앞뒤 공백·줄바꿈·제어 문자 없이 입력하세요.",
  reasonCard: "'사유 및 비고'에 카드번호 모양의 숫자를 넣을 수 없습니다.",
  documentRequired: '계약서 수령 근거 참조를 입력하세요.',
  documentFormat: '근거 참조는 영문·숫자와 . _ : # / - 만 1~128자로 입력하세요.',
  documentCard: '근거 참조에 카드번호 모양의 숫자를 넣을 수 없습니다.',
  startRequired: '시작일을 고르세요.',
  startPast: '시작일은 오늘(한국 시간) 이전으로 고를 수 없습니다.',
  endFormat: '종료일을 날짜로 고르세요.',
  endBeforeStart: '종료일은 시작일과 같거나 뒤여야 합니다.',
  scopeRequired: '계약 범위를 하나 이상 고르세요.',
  receivedRequired: '접수일을 고르세요.',
  receivedFuture: '접수일은 오늘(한국 시간) 이후로 고를 수 없습니다.',
  channelRequired: '접수 경로를 고르세요.',
  channelNotAllowed: '영업대행사 경로는 아직 고를 수 없습니다.',
  managerNotAllowed: '계약 담당자는 활성 운영자 계정만 지정할 수 있습니다.',
  // FLOW-2d 접수 항목 수정(§4A.3 — D-FLOW-009 ①).
  managerRequired: '계약 담당자를 고르세요.',
  reasonForDate: "접수일을 바꾸려면 '사유 및 비고'에 사유를 적어야 합니다.",
  itemsUnchanged: '바뀐 내용이 없습니다.',
  managersUnavailable: '계약 담당자 목록을 불러오지 못했습니다. 다시 열어 주세요.',
  // 재리뷰 MAJOR-1: 저장된 담당자가 목록에 없음(비활성 등) — 표시명으로 추측하지 않고 직접 고르게 한다.
  currentManagerMissing: '기존 담당자가 목록에 없습니다. 담당자를 다시 고르세요.',
} as const

/** FLOW-2d 접수 항목 수정 폼(접수 항목 4칸 + 담당자 참조 + 사유). [original] = 대화상자를 연 때의 값(바뀐 것 판단·접수일 사유 규칙). */
export interface ItemsForm { receivedOn: string; intakeChannel: string; managerRef: string; desiredServices: string[]; reason: string }

/** 항목 수정 검사(즉시 안내 — 서버가 권위): 생성과 같은 항목 규칙 + 담당자 필수 + 접수일을 바꾸면 사유 필수 + 바뀐 것 없음. */
export function itemsErrors(form: ItemsForm, original: ItemsForm, today: string): Record<string, string> {
  const errors = openErrors({ receivedOn: form.receivedOn, intakeChannel: form.intakeChannel, desiredServices: form.desiredServices, reason: form.reason }, today)
  if (form.managerRef === '') errors.contractManager = CONTRACT_INPUT_MESSAGES.managerRequired
  if (!errors.reason && form.receivedOn !== original.receivedOn && form.reason === '') errors.reason = CONTRACT_INPUT_MESSAGES.reasonForDate
  const same = form.receivedOn === original.receivedOn && form.intakeChannel === original.intakeChannel && form.managerRef === original.managerRef &&
    [...form.desiredServices].sort().join() === [...original.desiredServices].sort().join()
  if (Object.keys(errors).length === 0 && same) errors.form = CONTRACT_INPUT_MESSAGES.itemsUnchanged
  return errors
}

/** '사유 및 비고' 검사([required]면 빈 값 오류). 빈 칸은 null로 보낸다. */
export function reasonMessage(value: string, required: boolean): string | null {
  if (value === '') return required ? CONTRACT_INPUT_MESSAGES.reasonRequired : null
  const code = reasonError(value)
  if (code === 'TOO_LONG') return CONTRACT_INPUT_MESSAGES.reasonTooLong
  if (code === 'CARD_NUMBER_PATTERN') return CONTRACT_INPUT_MESSAGES.reasonCard
  return code === null ? null : CONTRACT_INPUT_MESSAGES.reasonFormat
}

export function documentRefMessage(value: string): string | null {
  if (value === '') return CONTRACT_INPUT_MESSAGES.documentRequired
  if (!DOCUMENT_REF.test(value)) return CONTRACT_INPUT_MESSAGES.documentFormat
  return CARD_NUMBER.test(value) ? CONTRACT_INPUT_MESSAGES.documentCard : null
}

export interface CompletionForm { documentRef: string; startDate: string; endDate: string; services: string[]; reason: string }
export interface OpenForm { receivedOn: string; intakeChannel: string; desiredServices: string[]; reason: string }

/** 계약 완료 대화상자 검사(§4A.4·§4B.3): 칸 → 문구. [today] = 서버 KST 오늘(문자열 비교 — `yyyy-MM-dd`). */
export function completionErrors(form: CompletionForm, today: string): Record<string, string> {
  const errors: Record<string, string> = {}
  const doc = documentRefMessage(form.documentRef)
  if (doc) errors.documentRef = doc
  if (!isCalendarDate(form.startDate)) errors.startDate = CONTRACT_INPUT_MESSAGES.startRequired
  else if (form.startDate < today) errors.startDate = CONTRACT_INPUT_MESSAGES.startPast
  if (form.endDate !== '') {
    if (!isCalendarDate(form.endDate)) errors.endDate = CONTRACT_INPUT_MESSAGES.endFormat
    else if (isCalendarDate(form.startDate) && form.endDate < form.startDate) errors.endDate = CONTRACT_INPUT_MESSAGES.endBeforeStart
  }
  if (form.services.length === 0) errors.services = CONTRACT_INPUT_MESSAGES.scopeRequired
  const reason = reasonMessage(form.reason, false)
  if (reason) errors.reason = reason
  return errors
}

/** 접수 건 생성 검사(§4A.6): 접수일 필수·미래 불가(서버 KST 오늘), 경로 필수(영업대행사 불가), 사유 선택. */
export function openErrors(form: OpenForm, today: string): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!isCalendarDate(form.receivedOn)) errors.receivedOn = CONTRACT_INPUT_MESSAGES.receivedRequired
  else if (form.receivedOn > today) errors.receivedOn = CONTRACT_INPUT_MESSAGES.receivedFuture
  if (form.intakeChannel === '') errors.intakeChannel = CONTRACT_INPUT_MESSAGES.channelRequired
  else if (form.intakeChannel === 'SALES_AGENCY') errors.intakeChannel = CONTRACT_INPUT_MESSAGES.channelNotAllowed
  const reason = reasonMessage(form.reason, false)
  if (reason) errors.reason = reason
  return errors
}

/** 서버 400 fieldErrors(path·code) → 칸 문구(값 없음). 모르는 조합은 일반 문구. */
export function serverFieldMessage(path: string, code: string): string {
  const key = path.replace(/\[[0-9]+\]$/, '')
  switch (key) {
    case 'reason': return code === 'REQUIRED' ? CONTRACT_INPUT_MESSAGES.reasonRequired : code === 'TOO_LONG' ? CONTRACT_INPUT_MESSAGES.reasonTooLong
      : code === 'CARD_NUMBER_PATTERN' ? CONTRACT_INPUT_MESSAGES.reasonCard : CONTRACT_INPUT_MESSAGES.reasonFormat
    case 'documentRef': return code === 'REQUIRED' ? CONTRACT_INPUT_MESSAGES.documentRequired : code === 'CARD_NUMBER_PATTERN' ? CONTRACT_INPUT_MESSAGES.documentCard
      : CONTRACT_INPUT_MESSAGES.documentFormat
    case 'startDate': return code === 'NOT_ALLOWED' ? CONTRACT_INPUT_MESSAGES.startPast : CONTRACT_INPUT_MESSAGES.startRequired
    case 'endDate': return code === 'NOT_ALLOWED' ? CONTRACT_INPUT_MESSAGES.endBeforeStart : CONTRACT_INPUT_MESSAGES.endFormat
    case 'services': return CONTRACT_INPUT_MESSAGES.scopeRequired
    case 'receivedOn': return code === 'NOT_ALLOWED' ? CONTRACT_INPUT_MESSAGES.receivedFuture : CONTRACT_INPUT_MESSAGES.receivedRequired
    case 'intakeChannel': return code === 'NOT_ALLOWED' ? CONTRACT_INPUT_MESSAGES.channelNotAllowed : CONTRACT_INPUT_MESSAGES.channelRequired
    case 'contractManager': return CONTRACT_INPUT_MESSAGES.managerNotAllowed
    default: return '입력을 확인하세요.'
  }
}

/** MASTER 저장된 거절 code → 한국어 안내(§4B.7 — code 원문은 화면에 그리지 않는다). */
export const CONTRACT_REJECTION_MESSAGES: Readonly<Record<string, string>> = {
  BUSINESS_NOT_FOUND: '사업자를 찾을 수 없습니다.',
  BUSINESS_NOT_ACTIVE: '종료된 사업자라 계약 진행을 바꿀 수 없습니다.',
  CONTRACT_INTAKE_NOT_FOUND: '접수 건을 찾을 수 없습니다. 계약 진행을 다시 불러왔습니다.',
  CONTRACT_INTAKE_OPEN_EXISTS: '이미 진행 중인 접수 건이 있습니다. 계약 진행을 다시 불러왔습니다.',
  CONTRACT_INTAKE_KIND_MISMATCH: '사업자 계약 상태가 바뀌어 접수 종류가 달라졌습니다. 다시 불러온 내용을 확인하고 접수하세요.',
  CONTRACT_INTAKE_RECEIVED_ON_FUTURE: '접수일은 오늘(한국 시간) 이후로 고를 수 없습니다. 날짜를 다시 고르세요.',
  CONTRACT_INTAKE_VERSION_CONFLICT: '다른 사람이 먼저 이 접수 건을 바꿨습니다. 다시 불러온 내용을 확인하세요.',
  // FLOW-2d 접수 항목 수정(MASTER [구현 관례 code]).
  CONTRACT_INTAKE_ITEMS_NOT_EDITABLE: '끝난 접수 건은 접수 항목을 바꿀 수 없습니다. 다시 불러온 내용을 확인하세요.',
  CONTRACT_INTAKE_ITEMS_UNCHANGED: '바뀐 내용이 없습니다.',
  CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED: '지금 상태에서는 할 수 없는 동작입니다. 다시 불러온 내용을 확인하세요.',
  CONTRACT_INTAKE_REASON_REQUIRED: "'사유 및 비고'를 입력하세요.",
  CONTRACT_INTAKE_DOCUMENT_REQUIRED: '계약서 수령 근거 참조를 입력하세요.',
  BUSINESS_CONTRACT_EXISTS: '이미 계약이 있는 사업자입니다. 변경 접수로 진행하세요.',
  BUSINESS_CONTRACT_NOT_CHANGEABLE: '계약이 해지·만료되어 계약 불가로만 닫을 수 있습니다.',
  BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS: '이미 예정 버전이 있어 새 버전을 만들 수 없습니다.',
  BUSINESS_CONTRACT_START_RETROACTIVE: '시작일은 오늘(한국 시간) 이전으로 고를 수 없습니다.',
  BUSINESS_CONTRACT_PERIOD_INVALID: '기간이 올바르지 않습니다. 종료일은 시작일 이후, 새 시작일은 직전 버전 시작일 뒤이고 직전 종료일 이하여야 합니다.',
  BUSINESS_CONTRACT_SCOPE_INVALID: '계약 범위를 하나 이상 고르세요.',
  BUSINESS_CONTRACT_NOT_FOUND: '최신 사업자 계약이 아닙니다. 계약 진행을 다시 불러왔습니다.',
  BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED: '지금 사업자 계약 상태에서는 할 수 없는 동작입니다. 다시 불러온 내용을 확인하세요.',
  BUSINESS_CONTRACT_STATE_CONFLICT: '다른 사람이 먼저 사업자 계약을 바꿨습니다. 다시 불러온 내용을 확인하세요.',
}

/** FLOW-2c 사업자 계약 명령(§4B.4 전이표 — 허용되는 것만 버튼). */
export type ContractEventAction = 'SUSPEND' | 'RESUME' | 'TERMINATE'
export const CONTRACT_EVENT_ACTION_LABELS: Readonly<Record<ContractEventAction, string>> = { SUSPEND: '정지', RESUME: '정지 해제', TERMINATE: '해지' }
export function allowedContractActions(c: BusinessContractView | null | undefined): ContractEventAction[] {
  if (!c) return []
  if (c.status === 'NOT_YET_EFFECTIVE' || c.status === 'ACTIVE') return ['SUSPEND', 'TERMINATE']
  if (c.status === 'SUSPENDED') return ['RESUME', 'TERMINATE']
  return []
}
/** 해지 확인 단계 안내(BR-BIZ-312·321 — 연결 가맹점 수는 종료 제외, 서버 값). */
export const terminationNotice = (linkedMerchantCount: number) =>
  `연결 가맹점 ${linkedMerchantCount}개(종료 제외) — 기존 가맹점의 결제·정산은 바뀌지 않습니다. 필요하면 가맹점마다 중지/철회하세요.`
export const TERMINATION_IRREVERSIBLE = '해지는 되돌릴 수 없습니다. 새 계약은 신규 접수 → 계약 완료로 만듭니다.'
/** 정지·정지 해제·해지 입력 검사: '사유 및 비고' 필수, 근거 참조 선택(입력하면 형식·카드번호 검사 — D-GOV-022). */
export function contractEventErrors(reason: string, evidenceRef: string): Record<string, string> {
  const errors: Record<string, string> = {}
  const r = reasonMessage(reason, true)
  if (r) errors.reason = r
  if (evidenceRef !== '') {
    const e = documentRefMessage(evidenceRef)
    if (e) errors.evidenceRef = e
  }
  return errors
}
export const rejectionMessage = (code: string | undefined) => (code && CONTRACT_REJECTION_MESSAGES[code]) || '처리하지 못했습니다. 계약 진행을 다시 불러와 확인하세요.'
