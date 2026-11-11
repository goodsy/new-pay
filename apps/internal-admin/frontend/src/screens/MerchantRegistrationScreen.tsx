import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { AdminApi } from '../api/adminApi'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { type ConsoleErrorCode } from '../errors/errorCodes'
import { Field } from '../components/DataBits'
import { WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { CheckIcon } from '../components/icons'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { NativeSelect } from '../components/ui/native-select'
import { RegistrationStep, stepLocked, stepReference, submitStep, type StepSpec, type StepStatus } from '../components/RegistrationStep'
import { defaultSubmissionId, kstDate } from '../lib/registration'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { cn } from '../lib/utils'

/**
 * 사업자 등록 마법사(ADMIN-UI-MERCHANT-001 — 사용자 지시 2026-11-11, 참고 시안 #merchant의 '＋ 사업자 등록' 4단계. 저장은 MERCHANT-OPS-002 §5 B1 그대로, OPERATOR만 — 표시만, 판정·감사는 서버).
 * 단계: ① 사업자 정보(사업자) ② 가맹점·사이트(가맹점 → Site) ③ 계약 초안·서비스 계약(계약 초안 → 서비스 계약) ④ 검토·완료(저장 결과 요약 + 다음 실제 단계 안내).
 * - "다음 단계 →"는 그 단계의 **기존 저장 호출**을 카드 순서대로, 아직 확정 성공하지 않은 카드만 하나씩 보낸다(같은 body·submissionId·잠금 — 끝에 몰아 보내지 않는다).
 *   한 카드라도 성공하지 못하면(입력 오류·거절·실패·결과 불명) 그 자리에서 멈추고 단계를 넘기지 않는다. 결과 불명 카드가 있으면 버튼이 잠긴다.
 * - "이전"·단계 표시는 이동만 한다(다시 보내지 않는다). 성공한 카드는 읽기 전용 완료 상태(C-32)이고 "… 새로 입력"을 눌러야 다시 쓸 수 있다 — 다음 제출은 새 submissionId.
 * - 각 카드의 등록 버튼으로 하나씩 저장할 수도 있다("기존 ID로 이어서" — 형식만 검사). ①에는 "기존 사업자로 이어서"(사업자 등록 없이 ②로 이동 — ②에서 사업자 ID 직접 입력).
 * - MID 발급·개통은 마법사에 없다(계약 승인 2인 → 첫 MID 발급 2인 → 개통, 확정 규칙). 원천사·수수료·정산 입력도 없다(B3-3·B4 — 서버 기능 전).
 * - ④ 날짜는 Asia/Seoul 날짜(C-7 — 서버가 그 날 0시 KST로 바꾼다), 종료일은 "이 날 0시 종료(미포함)". 시작일 선택기 최솟값(오늘 KST)은 안내일 뿐 — 소급 판정은 MASTER(§3.4).
 *   계약 초안은 미승인이라 결제 권리를 바꾸지 않는다. 계약은 업무 유일키가 없어 결과 불명 뒤 새로 보내면 중복될 수 있다(§5.3 — 같은 submissionId 재전송).
 * - 폼 제출 단위 `submissionId`(UUID v4 소문자 hex 32자)를 결과가 확정(200·409)될 때까지 **재전송에 같은 값**으로 보낸다. 확정 뒤 다시 쓰면 새 값.
 * - 결과 불명(503 MASTER_OUTCOME_UNKNOWN·MASTER_RETRY_SAME_KEY·AUDIT_UNAVAILABLE, 네트워크 오류)이면 폼을 **잠그고** "같은 내용 다시 보내기"만 준다(§5.3). "취소하고 새로 입력"은 경고 뒤에만.
 *   잠긴 뒤 재전송의 답은 확정(200 SUCCEEDED·REJECTED) 또는 409만 잠금을 푼다 — 그 밖의 오류(503 MASTER_BUSY·429·403 등)는 같은 submissionId로 잠금을 유지하고 그 오류 문구만 보인다(리뷰 M-1).
 * - 단계 위치·상태·입력은 [memory](App 메모리)에 둔다 — 가맹점 조회로 갔다 와도 단계·잠금·submissionId가 그대로다(리뷰 m-1). 주입이 없으면 화면 자체 메모리.
 *   단, 제출 전 평문 사업자등록번호 입력값은 화면 자체 상태라 화면을 바꾸면 비워진다(리뷰 MINOR-2) — 재전송할 payload만 메모리에 남는다. App은 세션 경계(로그인·로그아웃·세션 종료)마다 메모리를 새로 만든다.
 * - 등록번호 입력란은 제출 뒤 비우고, 화면은 서버가 돌려주지 않는 평문 대신 입력값에서 계산한 마스킹 값만 보인다. submissionId·입력은 메모리에만(브라우저 저장소 없음 — L-3).
 * - 이전 카드 성공 ID가 다음 카드 입력에 자동으로 채워진다 — 그 카드가 보내는 중·결과 불명으로 잠겨 있거나 이미 성공해 읽기 전용이면 채우지 않는다(잠긴·완료 폼은 보낸 값을 그대로 보인다, 리뷰 m-1).
 * - 업무 거절은 사유별 고정 문구(중복 등록번호는 "이미 등록된 사업자등록번호입니다"만 — 기존 사업자 ID·링크 없음, D-MOPS-006 ③). 같은 이름 가맹점은 경고하지 않는다(D-MOPS-006 ⑥).
 * - 카드의 [StepSpec.collect]·[StepSpec.onSucceeded]는 렌더 값이 아니라 메모리 값을 읽는다 — "다음 단계 →"가 앞 카드 성공 직후 같은 클릭 안에서 다음 카드를 보내기 때문이다.
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
  /** 시험 주입용 "오늘"(Asia/Seoul `YYYY-MM-DD`, 기본 = 지금의 KST 날짜). 시작일 선택기 최솟값 — 안내일 뿐 판정은 MASTER(§3.4). */
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
/** ④ 검토용 저장 결과(서버가 확정한 ID + 보낸 값 — 평문 사업자등록번호는 없다). */
interface SavedBusiness { id: string; masked: string; name: string }
interface SavedMerchant { id: string; name: string; businessId: string }
interface SavedContract { id: string; partyBusinessId: string; merchantId: string; scopeSiteIds: string[]; from: string; to: string | null }
const NO_SITES: readonly CreatedSite[] = Object.freeze([])
const NO_IDS: readonly string[] = Object.freeze([])
const IDLE: StepStatus = Object.freeze({ kind: 'idle' })
const defaultTodayKst = () => kstDate(new Date())

const STEPS: readonly { no: WizardStep; label: string }[] = [
  { no: 1, label: '사업자 정보' },
  { no: 2, label: '가맹점·사이트' },
  { no: 3, label: '계약 초안·서비스 계약' },
  { no: 4, label: '검토·완료' },
]
/** 단계별 카드 메모리 키(카드 순서 = 저장 순서). */
const STEP_KEYS: Record<WizardStep, readonly string[]> = {
  1: ['step.business'],
  2: ['step.merchant', 'step.site'],
  3: ['step.contract', 'step.agreement'],
  4: [],
}
const ALL_KEYS = [...STEP_KEYS[1], ...STEP_KEYS[2], ...STEP_KEYS[3]]

const ULID = '[0-9A-HJKMNP-TV-Z]{26}'
const BUSINESS_ID = new RegExp(`^biz_${ULID}$`)
const MERCHANT_ID = new RegExp(`^mer_${ULID}$`)
const SITE_ID = new RegExp(`^site_${ULID}$`)
const CONTRACT_ID = new RegExp(`^ctr_${ULID}$`)
const REGISTRATION_NUMBER = /^[0-9]{10}$/
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
/** SITES Scope 상한(C-2 — 서버와 같다). */
const MAX_SCOPE_SITES = 50

/** MASTER SiteChannel과 같은 규칙(D-MD-009 — 소문자 정규형만, 자동으로 바꾸지 않는다). */
const WEB_HOST = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/
const NUMERIC_LAST_LABEL = /^([0-9]+|0x[0-9a-f]*)$/
const APP_ID = /^[a-z0-9][a-z0-9_]*(\.[a-z0-9_]+)+$/

/** 오류 표시 초기값(매 렌더 같은 참조). */
const NO_ERRORS: Readonly<Record<never, boolean>> = Object.freeze({})

export const maskRegistrationNumber = (value: string) => `${value.slice(0, 3)}*******`

const validName = (value: string) => value.length > 0 && [...value].length <= 100 && value === value.trim() && !/\p{Cc}/u.test(value)

function validChannel(type: string, identifier: string): boolean {
  if (identifier.length > 253) return false
  if (type === 'WEB') return WEB_HOST.test(identifier) && !NUMERIC_LAST_LABEL.test(identifier.slice(identifier.lastIndexOf('.') + 1))
  return type === 'APP' && APP_ID.test(identifier)
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>

/** 카드 하나의 상태 구독(단계 버튼 잠금·완료 표시용). */
function useStatuses(memory: ScreenMemory): Record<string, StepStatus> {
  const [business] = useRemembered(memory, 'step.business.status', IDLE)
  const [merchant] = useRemembered(memory, 'step.merchant.status', IDLE)
  const [site] = useRemembered(memory, 'step.site.status', IDLE)
  const [contract] = useRemembered(memory, 'step.contract.status', IDLE)
  const [agreement] = useRemembered(memory, 'step.agreement.status', IDLE)
  return { 'step.business': business, 'step.merchant': merchant, 'step.site': site, 'step.contract': contract, 'step.agreement': agreement }
}
const isLocked = (status: StepStatus | undefined) => status?.kind === 'busy' || status?.kind === 'unknown'
const isDone = (status: StepStatus | undefined) => status?.kind === 'decided' && status.outcome.outcome === 'SUCCEEDED' && !!status.outcome.reference

/** 검토 목록 한 줄(label/value). */
function Row({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 border-b py-2.5 last:border-b-0 sm:grid-cols-[160px_1fr]">
      <dt className="text-xs text-muted-foreground">{term}</dt>
      <dd className="flex flex-col gap-0.5 text-sm">{children}</dd>
    </div>
  )
}
const NotSaved = () => <span className="text-xs text-muted-foreground">이 마법사에서 저장하지 않음</span>

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
  // ① 사업자
  // 평문 등록번호 입력값은 화면 자체 상태 — 제출하지 않은 번호를 화면 전환 너머로 App 메모리에 남기지 않는다(리뷰 MINOR-2). 같은 키 재전송용 payload는 단계 메모리(pending)에 그대로.
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [businessName, setBusinessName] = useRemembered(memory, 'businessName', '')
  const [businessErrors, setBusinessErrors] = useRemembered<{ number?: boolean; name?: boolean }>(memory, 'businessErrors', NO_ERRORS)
  const [business, setBusiness] = useRemembered<SavedBusiness | null>(memory, 'business', null)
  // ② 가맹점
  const [businessId, setBusinessId] = useRemembered(memory, 'businessId', '')
  const [merchantName, setMerchantName] = useRemembered(memory, 'merchantName', '')
  const [merchantErrors, setMerchantErrors] = useRemembered<{ id?: boolean; name?: boolean }>(memory, 'merchantErrors', NO_ERRORS)
  const [createdMerchant, setCreatedMerchant] = useRemembered<SavedMerchant | null>(memory, 'createdMerchant', null)
  // ② Site
  const [siteMerchantId, setSiteMerchantId] = useRemembered(memory, 'siteMerchantId', '')
  const [channelType, setChannelType] = useRemembered<'WEB' | 'APP'>(memory, 'channelType', 'WEB')
  const [channelIdentifier, setChannelIdentifier] = useRemembered(memory, 'channelIdentifier', '')
  const [siteErrors, setSiteErrors] = useRemembered<{ id?: boolean; channel?: boolean }>(memory, 'siteErrors', NO_ERRORS)
  /** 이 마법사에서 만든 Site(계약 초안 SITES Scope 체크 후보 — 그 가맹점의 Site만 보인다). */
  const [createdSites, setCreatedSites] = useRemembered<readonly CreatedSite[]>(memory, 'createdSites', NO_SITES)
  // ③ 계약 초안(B1-2)
  const [partyBusinessId, setPartyBusinessId] = useRemembered(memory, 'contract.partyBusinessId', '')
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
  const [agreementErrors, setAgreementErrors] = useRemembered<{ id?: boolean }>(memory, 'agreementErrors', NO_ERRORS)
  const [agreementId, setAgreementId] = useRemembered<string | null>(memory, 'agreementId', null)
  /** "가맹점 조회에서 보기"로 열 가맹점 — 마지막으로 성공한 가맹점, Site의 가맹점 또는 계약의 대상 가맹점. */
  const [lookupMerchantId, setLookupMerchantId] = useRemembered<string | null>(memory, 'lookupMerchantId', null)

  const common = { memory, newSubmissionId, onSessionEnded }
  /**
   * 앞 카드 성공 ID를 다음 카드 입력에 자동으로 채운다 — 단, 그 카드가 잠겨 있거나(보내는 중·결과 불명) 이미 성공해 읽기 전용이면 건너뛴다(리뷰 m-1).
   * 잠긴·완료 폼은 보낸 값을 계속 보여야 한다. 상태는 콜백 시점의 메모리 값으로 읽는다(지난 렌더 값 아님).
   */
  const fillInto = (memoryKey: string, ...fills: (() => void)[]) => {
    if (!stepLocked(memory, memoryKey) && stepReference(memory, memoryKey) === null) for (const fill of fills) fill()
  }
  const offeredSites = createdSites.filter((s) => s.merchantId === scopeMerchantId.trim())
  const endDateNoteId = 'merchant-registration-end-date-note'

  const businessSpec: StepSpec<{ registrationNumber: string; displayName: string }> = {
    ...common,
    memoryKey: 'step.business',
    api: { send: (input) => api.registerBusiness(input) },
    collect: () => {
      const name = read<string>('businessName', '')
      const errors = { number: !REGISTRATION_NUMBER.test(registrationNumber), name: !validName(name) }
      setBusinessErrors(errors)
      return errors.number || errors.name ? null : { registrationNumber, displayName: name }
    },
    afterSubmit: () => setRegistrationNumber(''),
    onSucceeded: (reference, payload) => {
      setBusiness({ id: reference, masked: maskRegistrationNumber(payload.registrationNumber), name: payload.displayName })
      fillInto('step.merchant', () => setBusinessId(reference))
      fillInto('step.contract', () => setPartyBusinessId(reference))
    },
  }
  const merchantSpec: StepSpec<{ businessId: string; displayName: string }> = {
    ...common,
    memoryKey: 'step.merchant',
    api: { send: (input) => api.registerMerchant(input) },
    collect: () => {
      const id = read<string>('businessId', '').trim()
      const name = read<string>('merchantName', '')
      const errors = { id: !BUSINESS_ID.test(id), name: !validName(name) }
      setMerchantErrors(errors)
      return errors.id || errors.name ? null : { businessId: id, displayName: name }
    },
    onSucceeded: (reference, payload) => {
      fillInto('step.site', () => setSiteMerchantId(reference))
      setCreatedMerchant({ id: reference, name: payload.displayName, businessId: payload.businessId })
      setLookupMerchantId(reference)
      // 계약 초안 자동 채움: 계약 당사자 = 이 가맹점의 사업자, 대상 = 이 가맹점(잠김·완료면 건너뜀).
      fillInto('step.contract', () => setPartyBusinessId(payload.businessId), () => setScopeMerchantId(reference))
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
      const party = read<string>('contract.partyBusinessId', '').trim()
      const merchant = read<string>('contract.scopeMerchantId', '').trim()
      const kind = read<ScopeKind>('contract.scopeKind', 'MERCHANT')
      const start = read<string>('contract.startDate', '')
      const end = read<string>('contract.endDate', '')
      const offered = new Set(read('createdSites', NO_SITES).filter((s) => s.merchantId === merchant).map((s) => s.siteId))
      const typed = read<string>('contract.typedSites', '').split(',').map((s) => s.trim()).filter((s) => s.length > 0)
      const sites = kind === 'SITES' ? [...new Set([...read('contract.checkedSites', NO_IDS).filter((id) => offered.has(id)), ...typed])] : []
      const errors = {
        party: !BUSINESS_ID.test(party),
        merchant: !MERCHANT_ID.test(merchant),
        start: !DATE.test(start),
        end: end !== '' && (!DATE.test(end) || (DATE.test(start) && end <= start)),
        typedSites: kind === 'SITES' && typed.some((id) => !SITE_ID.test(id)),
        sites: kind === 'SITES' && sites.length === 0,
        tooMany: sites.length > MAX_SCOPE_SITES,
      }
      setContractErrors(errors)
      if (Object.values(errors).some(Boolean)) return null
      // 시작일이 오늘(KST)보다 앞이어도 막지 않는다 — 판정은 MASTER(§3.4, 거절 문구로 안내).
      return { partyBusinessId: party, scopeMerchantId: merchant, scopeSiteIds: sites, effectiveFromDate: start, ...(end ? { effectiveToDate: end } : {}) }
    },
    onSucceeded: (reference, payload) => {
      setSavedContract({
        id: reference, partyBusinessId: payload.partyBusinessId, merchantId: payload.scopeMerchantId, scopeSiteIds: payload.scopeSiteIds,
        from: payload.effectiveFromDate, to: payload.effectiveToDate ?? null,
      })
      fillInto('step.agreement', () => setAgreementContractId(reference))
      setLookupMerchantId(payload.scopeMerchantId)
    },
  }
  const agreementSpec: StepSpec<{ contractId: string; serviceKind: 'CARD_PG_PAYMENT' }> = {
    ...common,
    memoryKey: 'step.agreement',
    api: { send: (input) => api.addServiceAgreement(input) },
    collect: () => {
      const id = read<string>('agreement.contractId', '').trim()
      const errors = { id: !CONTRACT_ID.test(id) }
      setAgreementErrors(errors)
      return errors.id ? null : { contractId: id, serviceKind: 'CARD_PG_PAYMENT' }
    },
    onSucceeded: (reference) => setAgreementId(reference),
  }

  /** 단계별 저장(카드 순서). 타입이 다른 명세를 같은 목록에 두려고 제출 함수로 감싼다. */
  const runners: Record<WizardStep, readonly { key: string; submit: () => Promise<boolean> }[]> = {
    1: [{ key: businessSpec.memoryKey, submit: () => submitStep(businessSpec) }],
    2: [{ key: merchantSpec.memoryKey, submit: () => submitStep(merchantSpec) }, { key: siteSpec.memoryKey, submit: () => submitStep(siteSpec) }],
    3: [{ key: contractSpec.memoryKey, submit: () => submitStep(contractSpec) }, { key: agreementSpec.memoryKey, submit: () => submitStep(agreementSpec) }],
    4: [],
  }
  const currentLocked = STEP_KEYS[wizardStep].some((key) => isLocked(statuses[key]))
  const anyLocked = ALL_KEYS.some((key) => isLocked(statuses[key]))

  /** "다음 단계 →": 아직 확정 성공하지 않은 카드만 순서대로 보낸다. 하나라도 성공하지 못하면 멈춘다(넘기지 않음). 이미 성공한 카드는 다시 보내지 않는다. */
  const goNext = async () => {
    // 같은 렌더 안의 재클릭·두 번 클릭도 막는다(state는 다음 렌더에야 보인다).
    if (advancingRef.current) return
    advancingRef.current = true
    const from = wizardStep
    // 리뷰 MINOR-1: 시작할 때의 메모리(= 세션). 화면이 사라지거나 App이 메모리를 바꾸면(로그아웃·다른 계정 로그인) 다음 카드를 보내지 않는다 —
    // 이미 보낸 카드의 결과는 지금처럼 그 메모리에 기록된다(sendStep). 다른 세션으로 새 요청이 나가 행위자가 바뀌는 것을 막는다.
    const started = memory
    const current = () => alive.current && memoryRef.current === started
    setAdvancing(true)
    try {
      for (const runner of runners[from]) {
        if (!current()) return
        if (stepReference(started, runner.key) !== null) continue
        if (!(await runner.submit())) return
      }
      // 보내는 동안 사용자가 다른 단계로 옮겼으면 그 위치를 존중한다.
      if (current() && read<WizardStep>('wizard.step', 1) === from && from < 4) setWizardStep((from + 1) as WizardStep)
    } finally {
      advancingRef.current = false
      if (alive.current) setAdvancing(false)
    }
  }
  /** "새 사업자 등록 시작": 결과 확인이 필요한 카드가 없을 때만 — 메모리(입력·결과·단계)를 모두 비운다. */
  const startOver = () => {
    if (ALL_KEYS.some((key) => stepLocked(memory, key))) return
    for (const key of memory.keys()) memory.remove(key)
    setRegistrationNumber('')
  }

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <p aria-hidden="true" className="text-[10px] tracking-[1.6px] text-eyebrow uppercase">MERCHANT ONBOARDING</p>
        <PageHeader
          title="사업자 등록"
          description="사업자 정보 → 가맹점·사이트 → 계약 초안·서비스 계약 순서로 등록합니다(운영자 1인). 각 단계의 '다음 단계 →'가 그 단계를 저장하고, 등록할 때마다 감사 기록에 남습니다. 사업자등록번호는 마스킹 값만 기록됩니다. 계약 승인·첫 MID 발급은 가맹점 조회 상세에서 요청(2인 승인)하고, 결제수단·Route 개통 전까지 결제는 할 수 없습니다."
        />
      </div>

      <ol aria-label="등록 단계" className="grid gap-2 sm:grid-cols-4">
        {STEPS.map(({ no, label }) => {
          const current = no === wizardStep
          const done = STEP_KEYS[no].length > 0 && STEP_KEYS[no].every((key) => isDone(statuses[key]))
          return (
            <li key={no}>
              <button
                type="button"
                aria-label={`${no}. ${label}`}
                aria-current={current ? 'step' : undefined}
                disabled={advancing}
                onClick={() => setWizardStep(no)}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-lg border px-3 py-2.5 text-left text-xs text-muted-foreground outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-60',
                  current && 'border-primary bg-primary/5 font-bold text-primary hover:bg-primary/5',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'flex size-6 shrink-0 items-center justify-center rounded-full border text-[11px] font-bold',
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

      {wizardStep === 1 && (
        <>
          <RegistrationStep
            {...businessSpec}
            title="사업자"
            submitDisabled={advancing}
            submitLabel="사업자 등록"
            succeeded={(reference) => (
              <span className="flex flex-col gap-0.5">
                <span>사업자 ID <Mono>{reference}</Mono></span>
                {business && business.id === reference && <span>사업자등록번호(마스킹) <span className="font-mono">{business.masked}</span></span>}
              </span>
            )}
            completion={{ resetLabel: '사업자 새로 입력', onReset: () => { setBusinessName(''); setBusinessErrors(NO_ERRORS) } }}
          >
            {() => (
              <>
                <Field
                  label="사업자등록번호"
                  error={businessErrors.number ? '사업자등록번호는 숫자 10자리입니다(하이픈 없이).' : undefined}
                  hint="숫자 10자리. 제출하면 입력란을 비웁니다."
                >
                  {(describedBy) => (
                    <Input
                      type="text"
                      inputMode="numeric"
                      maxLength={10}
                      autoComplete="off"
                      value={registrationNumber}
                      onChange={(e) => setRegistrationNumber(e.target.value)}
                      aria-describedby={describedBy}
                      aria-invalid={businessErrors.number || undefined}
                      className="font-mono"
                    />
                  )}
                </Field>
                <Field label="사업자명" error={businessErrors.name ? '1~100자, 앞뒤 공백 없이 입력하세요.' : undefined}>
                  {(describedBy) => (
                    <Input value={businessName} onChange={(e) => setBusinessName(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={businessErrors.name || undefined} />
                  )}
                </Field>
              </>
            )}
          </RegistrationStep>
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-dashed px-4 py-3 text-xs text-muted-foreground">
            <span>이미 등록된 사업자에 가맹점을 추가하나요? 사업자 등록 없이 다음 단계로 가서 사업자 ID를 직접 입력하세요.</span>
            <Button type="button" variant="outline" size="sm" disabled={advancing} onClick={() => setWizardStep(2)}>기존 사업자로 이어서</Button>
          </div>
        </>
      )}

      {wizardStep === 2 && (
        <>
          <RegistrationStep
            {...merchantSpec}
            title="가맹점"
            submitDisabled={advancing}
            submitLabel="가맹점 등록"
            succeeded={(reference) => <span>가맹점 ID <Mono>{reference}</Mono></span>}
            completion={{ resetLabel: '가맹점 새로 입력', onReset: () => { setMerchantName(''); setMerchantErrors(NO_ERRORS) } }}
          >
            {() => (
              <>
                <Field
                  label="사업자 ID"
                  hint="사업자 정보 단계 결과가 자동으로 채워집니다. 이미 있는 사업자로 이어서 하려면 직접 입력하세요."
                  error={merchantErrors.id ? '사업자 ID 형식(biz_…)이 아닙니다.' : undefined}
                >
                  {(describedBy) => (
                    <Input value={businessId} onChange={(e) => setBusinessId(e.target.value)} autoComplete="off" placeholder="biz_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field label="가맹점명" error={merchantErrors.name ? '1~100자, 앞뒤 공백 없이 입력하세요.' : undefined}>
                  {(describedBy) => (
                    <Input value={merchantName} onChange={(e) => setMerchantName(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={merchantErrors.name || undefined} />
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
                <Field
                  label="가맹점 ID"
                  hint="가맹점 결과가 자동으로 채워집니다. 이미 있는 가맹점으로 이어서 하려면 직접 입력하세요."
                  error={siteErrors.id ? '가맹점 ID 형식(mer_…)이 아닙니다.' : undefined}
                >
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
                  hint="웹은 소문자 도메인 이름만(예: shop.example.test — 대문자·포트·경로 없이), 앱은 점으로 구분한 소문자 앱 식별자. 자동으로 바꾸지 않습니다."
                  error={siteErrors.channel ? '형식이 맞지 않습니다(소문자 정규형만 — 자동으로 바꾸지 않습니다).' : undefined}
                >
                  {(describedBy) => (
                    <Input value={channelIdentifier} onChange={(e) => setChannelIdentifier(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={siteErrors.channel || undefined} className="font-mono" />
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
                <Field
                  label="사업자 ID"
                  hint="계약 당사자. 사업자·가맹점 결과가 자동으로 채워집니다."
                  error={contractErrors.party ? '사업자 ID 형식(biz_…)이 아닙니다.' : undefined}
                >
                  {(describedBy) => (
                    <Input value={partyBusinessId} onChange={(e) => setPartyBusinessId(e.target.value)} autoComplete="off" placeholder="biz_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field
                  label="가맹점 ID"
                  hint="계약 대상 가맹점. 가맹점 결과가 자동으로 채워집니다."
                  error={contractErrors.merchant ? '가맹점 ID 형식(mer_…)이 아닙니다.' : undefined}
                >
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
                    hint={`이 마법사에서 만들지 않은 기존 Site는 쉼표로 구분해 입력하세요(최대 ${MAX_SCOPE_SITES}개).`}
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
                  hint="오늘(한국 시간) 이후 날짜. 그 날 0시부터 효력 — 오늘보다 앞이면 서버가 거절합니다."
                  error={contractErrors.start ? '시작일을 고르세요.' : undefined}
                >
                  {(describedBy) => (
                    <Input type="date" min={todayKst()} value={startDate} onChange={(e) => setStartDate(e.target.value)} aria-describedby={describedBy} aria-invalid={contractErrors.start || undefined} />
                  )}
                </Field>
                <div className="flex flex-col gap-1.5">
                  <div className="flex items-end gap-2">
                    <Label className="flex-1">
                      <span>종료일</span>
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
                  {contractErrors.end
                    ? <p className="text-xs text-destructive">종료일은 시작일보다 뒤여야 합니다.</p>
                    : <p className="text-xs text-muted-foreground">선택 — 비우면 종료 없음.</p>}
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
                <Field
                  label="계약 ID"
                  hint="계약 초안 결과가 자동으로 채워집니다. 이미 있는 계약으로 이어서 하려면 직접 입력하세요."
                  error={agreementErrors.id ? '계약 ID 형식(ctr_…)이 아닙니다.' : undefined}
                >
                  {(describedBy) => (
                    <Input value={agreementContractId} onChange={(e) => setAgreementContractId(e.target.value)} autoComplete="off" placeholder="ctr_…" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field label="서비스 종류">
                  {(describedBy) => (
                    <NativeSelect value="CARD_PG_PAYMENT" onChange={() => undefined} aria-describedby={describedBy}>
                      <option value="CARD_PG_PAYMENT">카드 PG 결제</option>
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
                {business ? (
                  <>
                    <span className="font-medium">{business.name}</span>
                    <Mono>{business.id}</Mono>
                    <span className="text-xs">사업자등록번호(마스킹) <span className="font-mono">{business.masked}</span></span>
                  </>
                ) : <NotSaved />}
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
                {agreementId ? (
                  <>
                    <Mono>{agreementId}</Mono>
                    <span className="text-xs">카드 PG 결제</span>
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
              <Button type="button" variant="outline" disabled={anyLocked} onClick={startOver}>새 사업자 등록 시작</Button>
              {lookupMerchantId && <Button type="button" onClick={() => onOpenLookup(lookupMerchantId)}>가맹점 조회에서 보기</Button>}
            </div>
          </CardContent>
        </Card>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
        <p className="text-xs text-muted-foreground">
          {wizardStep < 4 ? (
            <>
              {"'다음 단계 →'는 이 단계에서 아직 저장하지 않은 카드만 보냅니다. 카드의 등록 버튼으로 하나씩 저장할 수도 있습니다."}
              <br />
              {'위 단계 표시를 누르면 저장하지 않고 이동합니다(예: Site·서비스 계약을 나중에 하려면 건너뛰기).'}
            </>
          ) : '이 마법사는 MID를 발급하지 않습니다.'}
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
