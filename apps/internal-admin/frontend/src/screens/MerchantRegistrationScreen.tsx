import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi } from '../api/adminApi'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { Field, Spinner } from '../components/DataBits'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { CheckIcon, SearchIcon } from '../components/icons'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { NativeSelect } from '../components/ui/native-select'
import { RegistrationStep, stepLocked, stepReference, submitStep, type StepSpec, type StepStatus } from '../components/RegistrationStep'
import { defaultSubmissionId, kstDate, type BusinessRef, type HandoffState } from '../lib/registration'
import { isRegistrationNumber } from '../lib/registrationNumber'
import { SERVICE_KINDS, serviceKindLabel, type ServiceKindCode } from '../lib/paymentMethods'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { cn } from '../lib/utils'
import { validName } from './BusinessRegistrationScreen'

/**
 * 가맹점 등록 마법사(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ④: 사업자 등록과 화면 분리, 권한 그대로 OPERATOR만. 저장은 MERCHANT-OPS-002 §5 B1 그대로 — 표시만, 판정·감사는 서버).
 * 단계: ① 사업자 선택(저장 없음) ② 가맹점·사이트(가맹점 → Site) ③ 계약 초안·서비스 계약(계약 초안 → 서비스 계약) ④ 검토·완료.
 * - ① 사업자는 **기존 조회 기능**으로 고른다: 사업자등록번호 정확 일치 가맹점 검색(MERCHANT-OPS-001 §5 `merchantSearch` — 매 시도 감사·속도 제한은 서버, D-MOPS-003)의 결과에서
 *   사업자를 묶어 보이고 "선택". 사업자 등록 화면 "이 사업자로 가맹점 등록"으로 넘어오면 그 사업자가 이미 골라져 있다([offerBusiness]). 새 서버 API 없음.
 *   한계: 가맹점이 하나도 없는 기존 사업자는 이 검색으로 찾을 수 없다(사업자 등록 직후 넘겨받는 경우만 가능 — Coordinator 보고 대상).
 * - 사업자 ID(biz_…)는 화면에 그리지 않는다(D-ADMIN-UI-004 ③) — 상호·마스킹 등록번호로 보이고, 내부 키는 가맹점·계약 초안 저장 본문에만 그대로 들어간다(API·감사 불변).
 *   가맹점 ID(mer_…)는 아직 그대로 보인다. 가맹점 코드(MERCHANT-CODE-001)는 MC-1에서 가맹점 카드의 직접 입력만 생겼고, 미리보기·표시 교체(mer_ → 코드)는 MC-2.
 * - "다음 단계 →"는 그 단계의 **기존 저장 호출**을 카드 순서대로, 아직 확정 성공하지 않은 카드만 하나씩 보낸다(같은 body·submissionId·잠금). 하나라도 성공하지 못하면 멈춘다.
 * - "이전"·단계 표시는 이동만 한다. 성공한 카드는 읽기 전용 완료 상태(C-32) — "… 새로 입력" 뒤 다음 제출은 새 submissionId.
 * - MID 발급·개통은 마법사에 없다(계약 승인 2인 → 첫 MID 발급 2인 → 개통). 날짜는 Asia/Seoul(C-7), 소급 판정은 MASTER(§3.4).
 * - submissionId는 결과 확정(200·409)까지 재전송에 같은 값. 결과 불명이면 폼 잠금 + "같은 내용 다시 보내기"(§5.3, 리뷰 M-1) — [RegistrationStep].
 * - 단계·상태·입력은 [memory](App 메모리)에 둔다(화면 전환에도 유지, 세션 경계마다 새로 — 리뷰 m-1·MINOR-1). 검색 입력란의 평문 등록번호는 화면 자체 상태이고 보낸 즉시 비운다.
 * - 앞 카드 성공 값이 다음 카드에 자동으로 채워진다 — 그 카드가 잠겨 있거나 이미 성공해 읽기 전용이면 채우지 않는다(리뷰 m-1).
 */
interface Props {
  api: AdminApi
  /** "가맹점 조회에서 보기" — 조회 탭에서 이 가맹점 상세를 연다(기존 상세 조회 경로 — 감사는 그 화면·서버, 리뷰 m-3). */
  onOpenLookup: (merchantId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 시험 주입용(기본 = crypto.randomUUID 하이픈 제거). */
  newSubmissionId?: () => string
  /** 화면 상태를 둘 App 메모리(화면 전환에도 유지 — 브라우저 저장소 아님). */
  memory?: ScreenMemory
  /** 시험 주입용 "오늘"(Asia/Seoul `YYYY-MM-DD`). 시작일 선택기 최솟값 — 안내일 뿐 판정은 MASTER(§3.4). */
  todayKst?: () => string
}

type ScopeKind = 'MERCHANT' | 'SITES'
type WizardStep = 1 | 2 | 3 | 4
interface CreatedSite {
  merchantId: string
  siteId: string
  channelType: 'WEB' | 'APP'
  channelIdentifier: string
}
/** ④ 검토용 저장 결과(서버가 확정한 ID + 보낸 값). */
interface SavedMerchant { id: string; name: string; business: BusinessRef }
interface SavedContract { id: string; party: BusinessRef; merchantId: string; scopeSiteIds: string[]; from: string; to: string | null }
const NO_SITES: readonly CreatedSite[] = Object.freeze([])
const NO_IDS: readonly string[] = Object.freeze([])
const NO_BUSINESSES: readonly BusinessRef[] | null = null
const IDLE: StepStatus = Object.freeze({ kind: 'idle' })
const defaultTodayKst = () => kstDate(new Date())

const STEPS: readonly { no: WizardStep; label: string }[] = [
  { no: 1, label: '사업자 선택' },
  { no: 2, label: '가맹점·사이트' },
  { no: 3, label: '계약 초안·서비스 계약' },
  { no: 4, label: '검토·완료' },
]
/** 단계별 카드 메모리 키(카드 순서 = 저장 순서). ① 사업자 선택은 저장하지 않는다. */
const STEP_KEYS: Record<WizardStep, readonly string[]> = {
  1: [],
  2: ['step.merchant', 'step.site'],
  3: ['step.contract', 'step.agreement'],
  4: [],
}
const ALL_KEYS = [...STEP_KEYS[2], ...STEP_KEYS[3]]

const ULID = '[0-9A-HJKMNP-TV-Z]{26}'
const BUSINESS_ID = new RegExp(`^biz_${ULID}$`)
const MERCHANT_ID = new RegExp(`^mer_${ULID}$`)
/** MERCHANT-CODE-001 §3.1 가맹점 코드(MASTER와 같은 규칙 — 정규화 없음, 입력 그대로 보낸다). 미리보기·"다시 제안"은 MC-2. */
const MERCHANT_CODE = /^[a-z][a-z0-9]{5,13}$/
const MERCHANT_CODE_HINT = '영문 소문자로 시작, 영문 소문자·숫자 6~14자'
const SITE_ID = new RegExp(`^site_${ULID}$`)
const CONTRACT_ID = new RegExp(`^ctr_${ULID}$`)
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
/** SITES Scope 상한(C-2 — 서버와 같다). */
const MAX_SCOPE_SITES = 50

/** MASTER SiteChannel과 같은 규칙(D-MD-009 — 소문자 정규형만, 자동으로 바꾸지 않는다). */
const WEB_HOST = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/
const NUMERIC_LAST_LABEL = /^([0-9]+|0x[0-9a-f]*)$/
const APP_ID = /^[a-z0-9][a-z0-9_]*(\.[a-z0-9_]+)+$/

/** 오류 표시 초기값(매 렌더 같은 참조). */
const NO_ERRORS: Readonly<Record<never, boolean>> = Object.freeze({})

/** 고른 사업자가 없을 때 가맹점·계약 초안 카드 오류 문구. */
const PICK_BUSINESS_FIRST = '사업자를 먼저 선택하세요(1단계).'

function validChannel(type: string, identifier: string): boolean {
  if (identifier.length > 253) return false
  if (type === 'WEB') return WEB_HOST.test(identifier) && !NUMERIC_LAST_LABEL.test(identifier.slice(identifier.lastIndexOf('.') + 1))
  return type === 'APP' && APP_ID.test(identifier)
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>
/** 사업자 표시(상호 + 마스킹 등록번호 — 사업자 ID 없음). */
const BusinessText = ({ business }: { business: BusinessRef }) => (
  <span className="flex flex-wrap items-baseline gap-x-2">
    <span className="font-medium">{business.name}</span>
    <span className="font-mono text-xs text-muted-foreground">{business.masked}</span>
  </span>
)

/** 카드 하나의 상태 구독(단계 버튼 잠금·완료 표시용). */
function useStatuses(memory: ScreenMemory): Record<string, StepStatus> {
  const [merchant] = useRemembered(memory, 'step.merchant.status', IDLE)
  const [site] = useRemembered(memory, 'step.site.status', IDLE)
  const [contract] = useRemembered(memory, 'step.contract.status', IDLE)
  const [agreement] = useRemembered(memory, 'step.agreement.status', IDLE)
  return { 'step.merchant': merchant, 'step.site': site, 'step.contract': contract, 'step.agreement': agreement }
}
const isLocked = (status: StepStatus | undefined) => status?.kind === 'busy' || status?.kind === 'unknown'
const isDone = (status: StepStatus | undefined) => status?.kind === 'decided' && status.outcome.outcome === 'SUCCEEDED' && !!status.outcome.reference

/** 그 카드가 잠겨 있지 않고(보내는 중·결과 불명) 아직 성공하지 않았을 때만 채운다(리뷰 m-1 — 잠긴·완료 폼은 보낸 값을 그대로 보인다). */
const fillable = (memory: ScreenMemory, memoryKey: string) => !stepLocked(memory, memoryKey) && stepReference(memory, memoryKey) === null

/**
 * 사업자를 고른다(① "선택" 또는 사업자 등록 화면 "이 사업자로 가맹점 등록"): 고른 사업자를 기억하고, 가맹점·계약 초안 카드에 채운다(잠김·완료면 건너뜀).
 * ① 단계에 있으면 ② 가맹점·사이트로 넘긴다(저장 없음).
 */
export function offerBusiness(memory: ScreenMemory, business: BusinessRef) {
  memory.write('selectedBusiness', business)
  memory.write('selectError', false)
  if (fillable(memory, 'step.merchant')) memory.write('merchant.business', business)
  if (fillable(memory, 'step.contract')) memory.write('contract.party', business)
  if (!memory.has('wizard.step') || memory.read('wizard.step') === 1) memory.write('wizard.step', 2)
}

/** 사업자 등록 화면 "이 사업자로 가맹점 등록" 전에 본다(리뷰 MINOR-1 — 진행 중인 마법사에 다른 사업자가 섞이지 않게). */
export function registrationHandoffState(memory: ScreenMemory): HandoffState {
  if (ALL_KEYS.some((key) => stepLocked(memory, key))) return 'locked'
  return memory.keys().some((key) => key !== 'wizard.step') ? 'inProgress' : 'empty'
}

/**
 * 가맹점 등록을 [business]로 새로 시작한다(사업자 등록 화면에서 넘어올 때, 리뷰 MINOR-1): 잠긴 카드가 있으면 아무것도 하지 않고 false.
 * 아니면 마법사 메모리(입력·결과·고른 사업자)를 모두 비운 뒤 그 사업자로 ②를 연다("새 가맹점 등록 시작"과 같은 비우기 — 앞 진행분과 섞지 않는다).
 */
export function startWithBusiness(memory: ScreenMemory, business: BusinessRef): boolean {
  if (registrationHandoffState(memory) === 'locked') return false
  for (const key of memory.keys()) memory.remove(key)
  offerBusiness(memory, business)
  return true
}

/** 검토 목록 한 줄(label/value). */
function Row({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 border-b py-2.5 last:border-b-0 sm:grid-cols-[180px_1fr]">
      <dt className="text-xs text-muted-foreground">{term}</dt>
      <dd className="flex flex-col gap-0.5 text-sm">{children}</dd>
    </div>
  )
}
const NotSaved = () => <span className="text-xs text-muted-foreground">이 마법사에서 저장하지 않음</span>

/** ① 사업자 선택: 사업자등록번호로 찾기(기존 가맹점 검색 — 정확 일치, 매 시도 감사) → 결과의 사업자 "선택". */
function BusinessPicker({ api, memory, onSessionEnded, disabled }: { api: AdminApi; memory: ScreenMemory; onSessionEnded?: (code: ConsoleErrorCode) => void; disabled: boolean }) {
  // 평문 번호는 화면 자체 상태 — 보낸 즉시 비우고 메모리·저장소·URL에 남기지 않는다(MERCHANT-OPS-001 R-3와 같다).
  const [number, setNumber] = useState('')
  const [formatError, setFormatError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [found, setFound] = useRemembered<readonly BusinessRef[] | null>(memory, 'search.found', NO_BUSINESSES)
  const [selected] = useRemembered<BusinessRef | null>(memory, 'selectedBusiness', null)
  const [selectError] = useRemembered(memory, 'selectError', false)

  const search = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    if (!isRegistrationNumber(number)) {
      setFormatError(true)
      return
    }
    setFormatError(false)
    setError(null)
    const registrationNumber = number
    setNumber('')
    setBusy(true)
    try {
      const page = await api.merchantSearch({ registrationNumber })
      const byId = new Map<string, BusinessRef>()
      for (const item of page.items) {
        if (!byId.has(item.businessId)) byId.set(item.businessId, { businessId: item.businessId, name: item.businessName, masked: item.registrationNumberMasked })
      }
      setFound([...byId.values()])
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card as="section" aria-label="사업자 선택" className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-sm">사업자 선택</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-5">
        {selected ? (
          <div role="group" aria-label="선택한 사업자" className="flex flex-wrap items-center gap-2 rounded-md border border-primary/40 bg-primary/5 px-3 py-2.5 text-sm">
            <CheckIcon aria-hidden="true" className="size-4 text-primary" />
            <BusinessText business={selected} />
          </div>
        ) : selectError ? (
          <ErrorNotice>사업자를 선택하세요.</ErrorNotice>
        ) : null}
        <form onSubmit={search} className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <Field label="사업자등록번호" className="sm:max-w-xs sm:flex-1" error={formatError ? '사업자등록번호는 숫자 10자리입니다.' : undefined} hint={formatError ? undefined : '검색은 감사 기록에 남습니다.'}>
            {(describedBy) => (
              <RegistrationNumberInput value={number} onValueChange={setNumber} disabled={disabled} aria-describedby={describedBy} aria-invalid={formatError || undefined} />
            )}
          </Field>
          <div className="sm:pb-7">
            <Button type="submit" variant="outline" disabled={disabled} aria-busy={busy}>
              {busy ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
              찾기
            </Button>
          </div>
        </form>
        {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
        {found && (
          found.length === 0 ? (
            <p className="text-sm text-muted-foreground">찾은 사업자가 없습니다(가맹점이 있는 사업자만 찾습니다).</p>
          ) : (
            <ul aria-label="찾은 사업자" className="flex flex-col gap-2">
              {found.map((business) => (
                <li key={business.businessId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2.5">
                  <BusinessText business={business} />
                  <Button type="button" size="sm" disabled={disabled || selected?.businessId === business.businessId} onClick={() => offerBusiness(memory, business)}>
                    {selected?.businessId === business.businessId ? '선택됨' : '선택'}
                  </Button>
                </li>
              ))}
            </ul>
          )
        )}
      </CardContent>
    </Card>
  )
}

export function MerchantRegistrationScreen({ api, onOpenLookup, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected, todayKst = defaultTodayKst }: Props) {
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  /** 콜백 시점의 메모리 값(지난 렌더 값 아님 — "다음 단계 →"가 같은 클릭 안에서 카드를 이어 보낸다). */
  const read = <T,>(key: string, initial: T): T => (memory.has(key) ? (memory.read(key) as T) : initial)
  const [wizardStep, setWizardStep] = useRemembered<WizardStep>(memory, 'wizard.step', 1)
  const [advancing, setAdvancing] = useState(false)
  const advancingRef = useRef(false)
  /** 리뷰 MINOR-1: 화면이 살아 있는지·지금 App이 준 메모리(세션 경계마다 새로)가 무엇인지 — "다음 단계 →" 연쇄가 이것을 보고 멈춘다. */
  const alive = useRef(true)
  const memoryRef = useRef(memory)
  memoryRef.current = memory
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  const statuses = useStatuses(memory)
  const [selectedBusiness] = useRemembered<BusinessRef | null>(memory, 'selectedBusiness', null)
  // ② 가맹점
  const [merchantBusiness] = useRemembered<BusinessRef | null>(memory, 'merchant.business', null)
  const [merchantName, setMerchantName] = useRemembered(memory, 'merchantName', '')
  const [merchantCode, setMerchantCode] = useRemembered(memory, 'merchantCode', '')
  const [merchantErrors, setMerchantErrors] = useRemembered<{ business?: boolean; name?: boolean; code?: boolean }>(memory, 'merchantErrors', NO_ERRORS)
  const [createdMerchant, setCreatedMerchant] = useRemembered<SavedMerchant | null>(memory, 'createdMerchant', null)
  // ② Site
  const [siteMerchantId, setSiteMerchantId] = useRemembered(memory, 'siteMerchantId', '')
  const [channelType, setChannelType] = useRemembered<'WEB' | 'APP'>(memory, 'channelType', 'WEB')
  const [channelIdentifier, setChannelIdentifier] = useRemembered(memory, 'channelIdentifier', '')
  const [siteErrors, setSiteErrors] = useRemembered<{ id?: boolean; channel?: boolean }>(memory, 'siteErrors', NO_ERRORS)
  /** 이 마법사에서 만든 Site(계약 초안 SITES Scope 체크 후보 — 그 가맹점의 Site만 보인다). */
  const [createdSites, setCreatedSites] = useRemembered<readonly CreatedSite[]>(memory, 'createdSites', NO_SITES)
  // ③ 계약 초안(B1-2)
  const [party, setParty] = useRemembered<BusinessRef | null>(memory, 'contract.party', null)
  const [scopeMerchantId, setScopeMerchantId] = useRemembered(memory, 'contract.scopeMerchantId', '')
  const [scopeKind, setScopeKind] = useRemembered<ScopeKind>(memory, 'contract.scopeKind', 'MERCHANT')
  const [checkedSites, setCheckedSites] = useRemembered<readonly string[]>(memory, 'contract.checkedSites', NO_IDS)
  const [typedSites, setTypedSites] = useRemembered(memory, 'contract.typedSites', '')
  const [startDate, setStartDate] = useRemembered(memory, 'contract.startDate', '')
  const [endDate, setEndDate] = useRemembered(memory, 'contract.endDate', '')
  const [contractErrors, setContractErrors] = useRemembered<{
    party?: boolean; merchant?: boolean; start?: boolean; end?: boolean; sites?: boolean; tooMany?: boolean; typedSites?: boolean
  }>(memory, 'contractErrors', NO_ERRORS)
  const [savedContract, setSavedContract] = useRemembered<SavedContract | null>(memory, 'savedContract', null)
  // ③ 서비스 계약(B1-2)
  const [agreementContractId, setAgreementContractId] = useRemembered(memory, 'agreement.contractId', '')
  const [serviceKind, setServiceKind] = useRemembered<ServiceKindCode>(memory, 'agreement.serviceKind', SERVICE_KINDS[0]!.code)
  const [agreementErrors, setAgreementErrors] = useRemembered<{ id?: boolean }>(memory, 'agreementErrors', NO_ERRORS)
  const [agreement, setAgreement] = useRemembered<{ id: string; serviceKind: string } | null>(memory, 'agreement', null)
  /** "가맹점 조회에서 보기"로 열 가맹점 — 마지막으로 성공한 가맹점, Site의 가맹점 또는 계약의 대상 가맹점. */
  const [lookupMerchantId, setLookupMerchantId] = useRemembered<string | null>(memory, 'lookupMerchantId', null)

  const common = { memory, newSubmissionId, onSessionEnded }
  const fillInto = (memoryKey: string, ...fills: (() => void)[]) => {
    if (fillable(memory, memoryKey)) for (const fill of fills) fill()
  }
  const offeredSites = createdSites.filter((s) => s.merchantId === scopeMerchantId.trim())
  const endDateNoteId = 'merchant-registration-end-date-note'

  const merchantSpec: StepSpec<{ businessId: string; displayName: string; merchantCode: string }> = {
    ...common,
    memoryKey: 'step.merchant',
    api: { send: (input) => api.registerMerchant(input) },
    collect: () => {
      const business = read<BusinessRef | null>('merchant.business', null)
      const name = read<string>('merchantName', '')
      const code = read<string>('merchantCode', '')
      const errors = { business: business === null || !BUSINESS_ID.test(business.businessId), name: !validName(name), code: !MERCHANT_CODE.test(code) }
      setMerchantErrors(errors)
      return errors.business || errors.name || errors.code || business === null ? null : { businessId: business.businessId, displayName: name, merchantCode: code }
    },
    onSucceeded: (reference, payload) => {
      fillInto('step.site', () => setSiteMerchantId(reference))
      const business = read<BusinessRef | null>('merchant.business', null)
      if (business) setCreatedMerchant({ id: reference, name: payload.displayName, business })
      setLookupMerchantId(reference)
      // 계약 초안 자동 채움: 계약 당사자 = 이 가맹점의 사업자, 대상 = 이 가맹점(잠김·완료면 건너뜀).
      fillInto('step.contract', () => { if (business) setParty(business) }, () => setScopeMerchantId(reference))
    },
  }
  const siteSpec: StepSpec<{ merchantId: string; channelType: 'WEB' | 'APP'; channelIdentifier: string }> = {
    ...common,
    memoryKey: 'step.site',
    api: { send: (input) => api.registerMerchantSite(input) },
    collect: () => {
      const id = read<string>('siteMerchantId', '').trim()
      const type = read<'WEB' | 'APP'>('channelType', 'WEB')
      const identifier = read<string>('channelIdentifier', '')
      const errors = { id: !MERCHANT_ID.test(id), channel: !validChannel(type, identifier) }
      setSiteErrors(errors)
      return errors.id || errors.channel ? null : { merchantId: id, channelType: type, channelIdentifier: identifier }
    },
    onSucceeded: (reference, payload) => {
      setLookupMerchantId(payload.merchantId)
      setCreatedSites([...read('createdSites', NO_SITES), { ...payload, siteId: reference }])
      fillInto('step.contract', () => setScopeMerchantId(payload.merchantId))
    },
  }
  const contractSpec: StepSpec<{ partyBusinessId: string; scopeMerchantId: string; scopeSiteIds: string[]; effectiveFromDate: string; effectiveToDate?: string }> = {
    ...common,
    memoryKey: 'step.contract',
    api: { send: (input) => api.registerContract(input) },
    collect: () => {
      const partyRef = read<BusinessRef | null>('contract.party', null)
      const merchant = read<string>('contract.scopeMerchantId', '').trim()
      const kind = read<ScopeKind>('contract.scopeKind', 'MERCHANT')
      const start = read<string>('contract.startDate', '')
      const end = read<string>('contract.endDate', '')
      const offered = new Set(read('createdSites', NO_SITES).filter((s) => s.merchantId === merchant).map((s) => s.siteId))
      const typed = read<string>('contract.typedSites', '').split(',').map((s) => s.trim()).filter((s) => s.length > 0)
      const sites = kind === 'SITES' ? [...new Set([...read('contract.checkedSites', NO_IDS).filter((id) => offered.has(id)), ...typed])] : []
      const errors = {
        party: partyRef === null || !BUSINESS_ID.test(partyRef.businessId),
        merchant: !MERCHANT_ID.test(merchant),
        start: !DATE.test(start),
        end: end !== '' && (!DATE.test(end) || (DATE.test(start) && end <= start)),
        typedSites: kind === 'SITES' && typed.some((id) => !SITE_ID.test(id)),
        sites: kind === 'SITES' && sites.length === 0,
        tooMany: sites.length > MAX_SCOPE_SITES,
      }
      setContractErrors(errors)
      if (Object.values(errors).some(Boolean) || partyRef === null) return null
      // 시작일이 오늘(KST)보다 앞이어도 막지 않는다 — 판정은 MASTER(§3.4, 거절 문구로 안내).
      return { partyBusinessId: partyRef.businessId, scopeMerchantId: merchant, scopeSiteIds: sites, effectiveFromDate: start, ...(end ? { effectiveToDate: end } : {}) }
    },
    onSucceeded: (reference, payload) => {
      const partyRef = read<BusinessRef | null>('contract.party', null)
      if (partyRef) {
        setSavedContract({ id: reference, party: partyRef, merchantId: payload.scopeMerchantId, scopeSiteIds: payload.scopeSiteIds, from: payload.effectiveFromDate, to: payload.effectiveToDate ?? null })
      }
      fillInto('step.agreement', () => setAgreementContractId(reference))
      setLookupMerchantId(payload.scopeMerchantId)
    },
  }
  const agreementSpec: StepSpec<{ contractId: string; serviceKind: ServiceKindCode }> = {
    ...common,
    memoryKey: 'step.agreement',
    api: { send: (input) => api.addServiceAgreement(input) },
    collect: () => {
      const id = read<string>('agreement.contractId', '').trim()
      const kind = read<ServiceKindCode>('agreement.serviceKind', SERVICE_KINDS[0]!.code)
      const errors = { id: !CONTRACT_ID.test(id) }
      setAgreementErrors(errors)
      return errors.id ? null : { contractId: id, serviceKind: kind }
    },
    onSucceeded: (reference, payload) => setAgreement({ id: reference, serviceKind: payload.serviceKind }),
  }

  /** 단계별 저장(카드 순서). 타입이 다른 명세를 같은 목록에 두려고 제출 함수로 감싼다. */
  const runners: Record<WizardStep, readonly { key: string; submit: () => Promise<boolean> }[]> = {
    1: [],
    2: [{ key: merchantSpec.memoryKey, submit: () => submitStep(merchantSpec) }, { key: siteSpec.memoryKey, submit: () => submitStep(siteSpec) }],
    3: [{ key: contractSpec.memoryKey, submit: () => submitStep(contractSpec) }, { key: agreementSpec.memoryKey, submit: () => submitStep(agreementSpec) }],
    4: [],
  }
  const currentLocked = STEP_KEYS[wizardStep].some((key) => isLocked(statuses[key]))
  const anyLocked = ALL_KEYS.some((key) => isLocked(statuses[key]))

  /** "다음 단계 →": ①은 사업자를 골랐는지만 본다(저장 없음). ②·③은 아직 확정 성공하지 않은 카드만 순서대로 보내고, 하나라도 성공하지 못하면 멈춘다. */
  const goNext = async () => {
    if (advancingRef.current) return
    if (wizardStep === 1) {
      if (read<BusinessRef | null>('selectedBusiness', null) === null) {
        memory.write('selectError', true)
        return
      }
      setWizardStep(2)
      return
    }
    advancingRef.current = true
    const from = wizardStep
    // 리뷰 MINOR-1: 시작할 때의 메모리(= 세션). 화면이 사라지거나 App이 메모리를 바꾸면 다음 카드를 보내지 않는다 — 이미 보낸 카드의 결과는 그 메모리에 기록된다.
    const started = memory
    const current = () => alive.current && memoryRef.current === started
    setAdvancing(true)
    try {
      for (const runner of runners[from]) {
        if (!current()) return
        if (stepReference(started, runner.key) !== null) continue
        if (!(await runner.submit())) return
      }
      if (current() && read<WizardStep>('wizard.step', 1) === from && from < 4) setWizardStep((from + 1) as WizardStep)
    } finally {
      advancingRef.current = false
      if (alive.current) setAdvancing(false)
    }
  }
  /** "새 가맹점 등록 시작": 결과 확인이 필요한 카드가 없을 때만 — 메모리(입력·결과·단계·고른 사업자)를 모두 비운다. */
  const startOver = () => {
    if (ALL_KEYS.some((key) => stepLocked(memory, key))) return
    for (const key of memory.keys()) memory.remove(key)
  }
  const reviewBusiness = createdMerchant?.business ?? selectedBusiness

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="가맹점 등록" description="등록된 사업자를 골라 가맹점·사이트·계약 초안을 등록합니다. 계약 승인·첫 MID 발급은 가맹점 조회 상세에서 요청합니다(2인 승인)." />

      <ol aria-label="등록 단계" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {STEPS.map(({ no, label }) => {
          const current = no === wizardStep
          const done = no === 1 ? selectedBusiness !== null : STEP_KEYS[no].length > 0 && STEP_KEYS[no].every((key) => isDone(statuses[key]))
          return (
            <li key={no}>
              <button
                type="button"
                aria-label={`${no}. ${label}`}
                aria-current={current ? 'step' : undefined}
                disabled={advancing}
                onClick={() => setWizardStep(no)}
                className={cn(
                  'flex h-full w-full items-center gap-2.5 rounded-lg border px-3 py-2.5 text-left text-xs text-muted-foreground outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-60',
                  current && 'border-primary bg-primary/5 font-bold text-primary hover:bg-primary/5',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'flex size-7 shrink-0 items-center justify-center rounded-full border text-xs font-bold',
                    current && 'border-primary bg-primary text-primary-foreground',
                    done && !current && 'border-success text-success',
                  )}
                >
                  {done && !current ? <CheckIcon className="size-3.5" /> : no}
                </span>
                <span aria-hidden="true">{label}</span>
              </button>
            </li>
          )
        })}
      </ol>

      {wizardStep === 1 && <BusinessPicker api={api} memory={memory} onSessionEnded={onSessionEnded} disabled={advancing} />}

      {wizardStep === 2 && (
        <>
          <RegistrationStep
            {...merchantSpec}
            title="가맹점"
            submitDisabled={advancing}
            submitLabel="가맹점 등록"
            succeeded={(reference) => <span>가맹점 ID <Mono>{reference}</Mono></span>}
            completion={{ resetLabel: '가맹점 새로 입력', onReset: () => { setMerchantName(''); setMerchantCode(''); setMerchantErrors(NO_ERRORS); if (read<BusinessRef | null>('selectedBusiness', null)) memory.write('merchant.business', read('selectedBusiness', null)) } }}
          >
            {() => (
              <>
                <div role="group" aria-label="사업자" className="flex flex-col gap-1.5 text-sm">
                  <span className="font-medium">사업자</span>
                  <span className="flex min-h-10 items-center rounded-md border border-dashed px-3">
                    {merchantBusiness ? <BusinessText business={merchantBusiness} /> : <span className="text-muted-foreground">선택한 사업자 없음</span>}
                  </span>
                  {merchantErrors.business && <p className="text-xs text-destructive">{PICK_BUSINESS_FIRST}</p>}
                </div>
                <Field label="가맹점명" error={merchantErrors.name ? '1~100자, 앞뒤 공백 없이 입력하세요.' : undefined}>
                  {(describedBy) => (
                    <Input value={merchantName} onChange={(e) => setMerchantName(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={merchantErrors.name || undefined} />
                  )}
                </Field>
                <Field label="가맹점 코드" error={merchantErrors.code ? `${MERCHANT_CODE_HINT}로 입력하세요.` : undefined} hint={merchantErrors.code ? undefined : `${MERCHANT_CODE_HINT}. 한 번 쓴 코드는 다시 쓸 수 없습니다.`}>
                  {(describedBy) => (
                    <Input value={merchantCode} onChange={(e) => setMerchantCode(e.target.value)} autoComplete="off" spellCheck={false} aria-describedby={describedBy} aria-invalid={merchantErrors.code || undefined} className="font-mono" />
                  )}
                </Field>
              </>
            )}
          </RegistrationStep>

          <RegistrationStep
            {...siteSpec}
            title="Site"
            submitDisabled={advancing}
            submitLabel="Site 등록"
            succeeded={(reference) => <span>Site ID <Mono>{reference}</Mono></span>}
            completion={{ resetLabel: '다른 Site 추가 입력', onReset: () => { setChannelIdentifier(''); setSiteErrors(NO_ERRORS) } }}
          >
            {() => (
              <>
                <Field label="가맹점 ID" error={siteErrors.id ? '가맹점 ID 형식(mer_…)이 아닙니다.' : undefined}>
                  {(describedBy) => (
                    <Input value={siteMerchantId} onChange={(e) => setSiteMerchantId(e.target.value)} autoComplete="off" placeholder="mer_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field label="채널">
                  {(describedBy) => (
                    <NativeSelect value={channelType} onChange={(e) => setChannelType(e.target.value as 'WEB' | 'APP')} aria-describedby={describedBy}>
                      <option value="WEB">웹</option>
                      <option value="APP">앱</option>
                    </NativeSelect>
                  )}
                </Field>
                <Field
                  label="채널 식별값"
                  error={siteErrors.channel ? '형식이 맞지 않습니다(소문자 정규형만 — 자동으로 바꾸지 않습니다).' : undefined}
                >
                  {(describedBy) => (
                    <Input
                      value={channelIdentifier}
                      onChange={(e) => setChannelIdentifier(e.target.value)}
                      autoComplete="off"
                      placeholder={channelType === 'WEB' ? 'shop.example.test' : 'com.example.app'}
                      aria-describedby={describedBy}
                      aria-invalid={siteErrors.channel || undefined}
                      className="font-mono"
                    />
                  )}
                </Field>
              </>
            )}
          </RegistrationStep>
        </>
      )}

      {wizardStep === 3 && (
        <>
          <RegistrationStep
            {...contractSpec}
            title="계약 초안"
            submitDisabled={advancing}
            submitLabel="계약 초안 등록"
            succeeded={(reference) => <span>계약 ID <Mono>{reference}</Mono> (Revision 1, {REVISION_AWAITING_APPROVAL})</span>}
            completion={{ resetLabel: '계약 초안 새로 입력', onReset: () => setContractErrors(NO_ERRORS) }}
          >
            {() => (
              <>
                <div role="group" aria-label="계약 당사자" className="flex flex-col gap-1.5 text-sm">
                  <span className="font-medium">계약 당사자</span>
                  <span className="flex min-h-10 items-center rounded-md border border-dashed px-3">
                    {party ? <BusinessText business={party} /> : <span className="text-muted-foreground">선택한 사업자 없음</span>}
                  </span>
                  {contractErrors.party && <p className="text-xs text-destructive">{PICK_BUSINESS_FIRST}</p>}
                </div>
                <Field label="가맹점 ID" error={contractErrors.merchant ? '가맹점 ID 형식(mer_…)이 아닙니다.' : undefined}>
                  {(describedBy) => (
                    <Input value={scopeMerchantId} onChange={(e) => setScopeMerchantId(e.target.value)} autoComplete="off" placeholder="mer_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <fieldset className="flex flex-col gap-2 text-sm">
                  <legend className="mb-1 text-sm font-medium">범위</legend>
                  <label className="flex items-center gap-2">
                    <input type="radio" name="contract-scope" value="MERCHANT" checked={scopeKind === 'MERCHANT'} onChange={() => setScopeKind('MERCHANT')} />
                    가맹점 전체
                  </label>
                  <label className="flex items-center gap-2">
                    <input type="radio" name="contract-scope" value="SITES" checked={scopeKind === 'SITES'} onChange={() => setScopeKind('SITES')} />
                    선택 Site
                  </label>
                  {scopeKind === 'SITES' && offeredSites.map((s) => (
                    <label key={s.siteId} className="flex items-center gap-2 pl-6">
                      <input
                        type="checkbox"
                        checked={checkedSites.includes(s.siteId)}
                        onChange={(e) => setCheckedSites(e.target.checked ? [...checkedSites, s.siteId] : checkedSites.filter((id) => id !== s.siteId))}
                      />
                      <Mono>{s.siteId}</Mono>
                    </label>
                  ))}
                </fieldset>
                {scopeKind === 'SITES' && (
                  <Field
                    label="Site ID 직접 입력"
                    error={
                      contractErrors.typedSites ? 'Site ID 형식(site_…)이 아닙니다.'
                        : contractErrors.sites ? 'Site를 하나 이상 고르거나 입력하세요.'
                          : contractErrors.tooMany ? `Site는 ${MAX_SCOPE_SITES}개까지입니다.` : undefined
                    }
                  >
                    {(describedBy) => (
                      <Input value={typedSites} onChange={(e) => setTypedSites(e.target.value)} autoComplete="off" placeholder="site_…, site_…" aria-describedby={describedBy} className="font-mono" />
                    )}
                  </Field>
                )}
                <Field
                  label="시작일"
                  hint="한국 시간 0시부터 효력"
                  error={contractErrors.start ? '시작일을 고르세요.' : undefined}
                >
                  {(describedBy) => (
                    <Input type="date" min={todayKst()} value={startDate} onChange={(e) => setStartDate(e.target.value)} aria-describedby={describedBy} aria-invalid={contractErrors.start || undefined} />
                  )}
                </Field>
                <div className="flex flex-col gap-1.5">
                  <div className="flex items-end gap-2">
                    <Label className="flex-1">
                      <span>종료일(선택)</span>
                      <Input
                        type="date"
                        value={endDate}
                        onChange={(e) => setEndDate(e.target.value)}
                        aria-describedby={endDateNoteId}
                        aria-invalid={contractErrors.end || undefined}
                      />
                    </Label>
                    {/* C-7 배타 끝: 고정 문구를 입력란 바로 옆에(m-6). */}
                    <span id={endDateNoteId} className="pb-2 text-xs text-muted-foreground whitespace-nowrap">이 날 0시 종료(미포함)</span>
                  </div>
                  {contractErrors.end && <p className="text-xs text-destructive">종료일은 시작일보다 뒤여야 합니다.</p>}
                </div>
              </>
            )}
          </RegistrationStep>

          <RegistrationStep
            {...agreementSpec}
            title="서비스 계약"
            submitDisabled={advancing}
            submitLabel="서비스 계약 추가"
            succeeded={(reference) => <span>서비스 계약 ID <Mono>{reference}</Mono></span>}
            completion={{ resetLabel: '서비스 계약 새로 입력', onReset: () => setAgreementErrors(NO_ERRORS) }}
          >
            {() => (
              <>
                <Field label="계약 ID" error={agreementErrors.id ? '계약 ID 형식(ctr_…)이 아닙니다.' : undefined}>
                  {(describedBy) => (
                    <Input value={agreementContractId} onChange={(e) => setAgreementContractId(e.target.value)} autoComplete="off" placeholder="ctr_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field label="서비스 종류">
                  {(describedBy) => (
                    <NativeSelect value={serviceKind} onChange={(e) => setServiceKind(e.target.value as ServiceKindCode)} aria-describedby={describedBy}>
                      {SERVICE_KINDS.map((kind) => <option key={kind.code} value={kind.code}>{kind.label}</option>)}
                    </NativeSelect>
                  )}
                </Field>
              </>
            )}
          </RegistrationStep>
        </>
      )}

      {wizardStep === 4 && (
        <Card as="section" aria-label="등록 결과" className="gap-3 py-5">
          <CardHeader className="px-5">
            <CardTitle className="text-sm">검토 — 저장된 내용</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4 px-5 text-sm">
            <dl className="flex flex-col">
              <Row term="사업자">
                {reviewBusiness ? <BusinessText business={reviewBusiness} /> : <span className="text-xs text-muted-foreground">선택하지 않음</span>}
              </Row>
              <Row term="가맹점">
                {createdMerchant ? (
                  <>
                    <span className="font-medium">{createdMerchant.name}</span>
                    <Mono>{createdMerchant.id}</Mono>
                  </>
                ) : <NotSaved />}
              </Row>
              <Row term="Site">
                {createdSites.length > 0 ? (
                  <ul aria-label="저장된 Site" className="flex flex-col gap-1">
                    {createdSites.map((s) => (
                      <li key={s.siteId} className="flex flex-wrap items-center gap-x-2">
                        <Mono>{s.siteId}</Mono>
                        <span className="text-xs text-muted-foreground">{s.channelType === 'WEB' ? '웹' : '앱'}</span>
                        <span className="font-mono text-xs">{s.channelIdentifier}</span>
                        {/* 리뷰 NIT-4: 기존 가맹점에 붙인 Site도 섞일 수 있어 소유 가맹점을 행마다 보인다. */}
                        <span className="text-xs text-muted-foreground">가맹점 <Mono>{s.merchantId}</Mono></span>
                      </li>
                    ))}
                  </ul>
                ) : <NotSaved />}
              </Row>
              <Row term={`계약 초안(Revision 1, ${REVISION_AWAITING_APPROVAL})`}>
                {savedContract ? (
                  <>
                    <Mono>{savedContract.id}</Mono>
                    <span className="text-xs">
                      {`범위: ${savedContract.scopeSiteIds.length > 0 ? `선택 Site ${savedContract.scopeSiteIds.length}개` : '가맹점 전체'} · 시작일 ${savedContract.from}(한국 시간 0시)`}
                      {savedContract.to ? ` · 종료일 ${savedContract.to}(이 날 0시 종료, 미포함)` : ' · 종료일 없음'}
                    </span>
                  </>
                ) : <NotSaved />}
              </Row>
              <Row term="서비스 계약">
                {agreement ? (
                  <>
                    <Mono>{agreement.id}</Mono>
                    <span className="text-xs">{serviceKindLabel(agreement.serviceKind)}</span>
                  </>
                ) : <NotSaved />}
              </Row>
            </dl>
            <div className="flex flex-col gap-2 rounded-md bg-muted/40 p-3">
              <p className="text-xs text-muted-foreground">다음: 가맹점 조회 상세에서 계약 승인 → 첫 MID 발급을 요청하세요. 결제수단·Route 개통 전까지 결제는 아직 할 수 없습니다.</p>
              <ol aria-label="다음 단계" className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-medium">
                {/* 화살표는 CSS 내용(::before)이라 목록 항목 글자에 섞이지 않는다. */}
                {['가맹점 조회 상세', '계약 승인 요청 (2인)', '첫 MID 발급 (2인)', '개통'].map((label) => (
                  <li key={label} className="flex items-center gap-2 [&:not(:first-child)]:before:text-muted-foreground [&:not(:first-child)]:before:content-['→']">
                    {label}
                  </li>
                ))}
              </ol>
            </div>
            {anyLocked && <WarningNote>결과 확인이 필요한 단계가 있습니다 — 그 단계에서 '같은 내용 다시 보내기'로 먼저 확인하세요.</WarningNote>}
            <div className="flex flex-wrap justify-end gap-2">
              <Button type="button" variant="outline" disabled={anyLocked} onClick={startOver}>새 가맹점 등록 시작</Button>
              {lookupMerchantId && <Button type="button" onClick={() => onOpenLookup(lookupMerchantId)}>가맹점 조회에서 보기</Button>}
            </div>
          </CardContent>
        </Card>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
        <p className="text-xs text-muted-foreground">
          {wizardStep < 4 ? '단계 표시를 누르면 저장하지 않고 이동합니다.' : '이 마법사는 MID를 발급하지 않습니다.'}
        </p>
        <div className="flex gap-2">
          {wizardStep > 1 && (
            <Button type="button" variant="outline" disabled={advancing} onClick={() => setWizardStep((wizardStep - 1) as WizardStep)}>이전</Button>
          )}
          {wizardStep < 4 && (
            <Button type="button" disabled={advancing || currentLocked} aria-busy={advancing} onClick={() => void goNext()}>다음 단계 →</Button>
          )}
        </div>
      </div>
    </section>
  )
}
