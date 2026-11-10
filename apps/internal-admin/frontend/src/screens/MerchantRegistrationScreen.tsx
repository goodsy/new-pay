import { useState, type ReactNode } from 'react'
import type { AdminApi } from '../api/adminApi'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { type ConsoleErrorCode } from '../errors/errorCodes'
import { Field } from '../components/DataBits'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { NativeSelect } from '../components/ui/native-select'
import { RegistrationStep, type StepStatus } from '../components/RegistrationStep'
import { defaultSubmissionId, kstDate } from '../lib/registration'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'

/**
 * 가맹점 등록(MERCHANT-OPS-002 §5 — B1-1: ① 사업자 → ② 가맹점 → ③ Site, B1-2: ④ 계약 초안 → ⑤ 서비스 계약, OPERATOR만 — 표시만, 판정·감사는 서버).
 * - ④ 날짜는 Asia/Seoul 날짜(C-7 — 서버가 그 날 0시 KST로 바꾼다), 종료일은 "이 날 0시 종료(미포함)". 시작일 선택기 최솟값(오늘 KST)은 안내일 뿐 — 소급 판정은 MASTER(§3.4).
 *   계약 초안은 미승인이라 결제 권리를 바꾸지 않는다. 계약은 업무 유일키가 없어 결과 불명 뒤 새로 보내면 중복될 수 있다(§5.3 — 같은 submissionId 재전송).
 * - 폼 제출 단위 `submissionId`(UUID v4 소문자 hex 32자)를 결과가 확정(200·409)될 때까지 **재전송에 같은 값**으로 보낸다. 확정 뒤 다시 쓰면 새 값.
 * - 결과 불명(503 MASTER_OUTCOME_UNKNOWN·MASTER_RETRY_SAME_KEY·AUDIT_UNAVAILABLE, 네트워크 오류)이면 폼을 **잠그고** "같은 내용 다시 보내기"만 준다(§5.3). "취소하고 새로 입력"은 경고 뒤에만.
 *   잠긴 뒤 재전송의 답은 확정(200 SUCCEEDED·REJECTED) 또는 409만 잠금을 푼다 — 그 밖의 오류(503 MASTER_BUSY·429·403 등)는 같은 submissionId로 잠금을 유지하고 그 오류 문구만 보인다(리뷰 M-1).
 * - 단계 상태·입력은 [memory](App 메모리)에 둔다 — 가맹점 조회 탭으로 갔다 와도 잠금·submissionId가 그대로다(리뷰 m-1). 주입이 없으면 화면 자체 메모리.
 *   단, 제출 전 평문 사업자등록번호 입력값은 화면 자체 상태라 탭을 바꾸면 비워진다(리뷰 MINOR-2) — 재전송할 payload만 메모리에 남는다. App은 세션 경계(로그인·로그아웃·세션 종료)마다 메모리를 새로 만든다.
 * - 등록번호 입력란은 제출 뒤 비우고, 화면은 서버가 돌려주지 않는 평문 대신 입력값에서 계산한 마스킹 값만 보인다. submissionId·입력은 메모리에만(브라우저 저장소 없음 — L-3).
 * - 이전 단계 성공 ID가 다음 단계 입력에 자동으로 채워지고(그 단계가 보내는 중·결과 불명으로 잠겨 있으면 채우지 않는다 — 잠긴 폼은 보낸 값을 그대로 보인다, 리뷰 m-1), 각 단계는 "기존 ID로 이어서"(직접 입력)도 받는다(형식만 검사).
 * - 업무 거절은 사유별 고정 문구(중복 등록번호는 "이미 등록된 사업자등록번호입니다"만 — 기존 사업자 ID·링크 없음, D-MOPS-006 ③). 같은 이름 가맹점은 경고하지 않는다(D-MOPS-006 ⑥).
 */
interface Props {
  api: AdminApi
  /** "가맹점 조회에서 보기" — 조회 탭에서 이 가맹점 상세를 연다(기존 상세 조회 경로 — 감사는 그 화면·서버, 리뷰 m-3). */
  onOpenLookup: (merchantId: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 시험 주입용(기본 = crypto.randomUUID 하이픈 제거). */
  newSubmissionId?: () => string
  /** 화면 상태를 둘 App 메모리(탭 전환에도 유지 — 브라우저 저장소 아님). */
  memory?: ScreenMemory
  /** 시험 주입용 "오늘"(Asia/Seoul `YYYY-MM-DD`, 기본 = 지금의 KST 날짜). 시작일 선택기 최솟값 — 안내일 뿐 판정은 MASTER(§3.4). */
  todayKst?: () => string
}

type ScopeKind = 'MERCHANT' | 'SITES'
interface CreatedSite {
  merchantId: string
  siteId: string
}
const NO_SITES: readonly CreatedSite[] = Object.freeze([])
const NO_IDS: readonly string[] = Object.freeze([])
const defaultTodayKst = () => kstDate(new Date())

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

export function MerchantRegistrationScreen({ api, onOpenLookup, onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected, todayKst = defaultTodayKst }: Props) {
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  // ① 사업자
  // 평문 등록번호 입력값은 화면 자체 상태 — 제출하지 않은 번호를 탭 전환 너머로 App 메모리에 남기지 않는다(리뷰 MINOR-2). 같은 키 재전송용 payload는 단계 메모리(pending)에 그대로.
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [businessName, setBusinessName] = useRemembered(memory, 'businessName', '')
  const [businessErrors, setBusinessErrors] = useRemembered<{ number?: boolean; name?: boolean }>(memory, 'businessErrors', NO_ERRORS)
  const [business, setBusiness] = useRemembered<{ id: string; masked: string } | null>(memory, 'business', null)
  // ② 가맹점
  const [businessId, setBusinessId] = useRemembered(memory, 'businessId', '')
  const [merchantName, setMerchantName] = useRemembered(memory, 'merchantName', '')
  const [merchantErrors, setMerchantErrors] = useRemembered<{ id?: boolean; name?: boolean }>(memory, 'merchantErrors', NO_ERRORS)
  const [merchantId, setMerchantId] = useRemembered(memory, 'merchantId', '')
  const [createdMerchant, setCreatedMerchant] = useRemembered<string | null>(memory, 'createdMerchant', null)
  // ③ Site
  const [siteMerchantId, setSiteMerchantId] = useRemembered(memory, 'siteMerchantId', '')
  const [channelType, setChannelType] = useRemembered<'WEB' | 'APP'>(memory, 'channelType', 'WEB')
  const [channelIdentifier, setChannelIdentifier] = useRemembered(memory, 'channelIdentifier', '')
  const [siteErrors, setSiteErrors] = useRemembered<{ id?: boolean; channel?: boolean }>(memory, 'siteErrors', NO_ERRORS)
  const [siteId, setSiteId] = useRemembered<string | null>(memory, 'siteId', null)
  /** ③에서 만든 Site(④ SITES Scope 체크 후보 — 그 가맹점의 Site만 보인다). */
  const [createdSites, setCreatedSites] = useRemembered<readonly CreatedSite[]>(memory, 'createdSites', NO_SITES)
  // ④ 계약 초안(B1-2)
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
  const [contractId, setContractId] = useRemembered<string | null>(memory, 'contractId', null)
  // ⑤ 서비스 계약(B1-2)
  const [agreementContractId, setAgreementContractId] = useRemembered(memory, 'agreement.contractId', '')
  const [agreementErrors, setAgreementErrors] = useRemembered<{ id?: boolean }>(memory, 'agreementErrors', NO_ERRORS)
  const [agreementId, setAgreementId] = useRemembered<string | null>(memory, 'agreementId', null)
  /** "가맹점 조회에서 보기"로 열 가맹점 — 마지막으로 성공한 ② 가맹점, ③ Site의 가맹점 또는 ④ 계약의 대상 가맹점. */
  const [lookupMerchantId, setLookupMerchantId] = useRemembered<string | null>(memory, 'lookupMerchantId', null)

  const common = { memory, newSubmissionId, onSessionEnded }
  /**
   * 앞 단계 성공 ID를 다음 단계 입력에 자동으로 채운다 — 단, 그 단계가 잠겨 있으면(보내는 중·결과 불명) 건너뛴다(리뷰 m-1).
   * 잠긴 폼은 "같은 내용 다시 보내기"가 보낼 pending payload와 같은 값을 계속 보여야 한다. 잠금 여부는 콜백 시점의 메모리 값으로 읽는다(지난 렌더 값 아님).
   */
  const stepLocked = (memoryKey: string) => {
    const status = memory.read(`${memoryKey}.status`) as StepStatus | undefined
    return status?.kind === 'busy' || status?.kind === 'unknown'
  }
  const fillInto = (memoryKey: string, ...fills: (() => void)[]) => {
    if (!stepLocked(memoryKey)) for (const fill of fills) fill()
  }
  const offeredSites = createdSites.filter((s) => s.merchantId === scopeMerchantId.trim())
  const endDateNoteId = 'merchant-registration-end-date-note'

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="가맹점 등록"
        description="사업자 → 가맹점 → Site → 계약 초안 → 서비스 계약 순서로 등록합니다(운영자 1인). 등록할 때마다 감사 기록에 남고, 사업자등록번호는 마스킹 값만 기록됩니다. 계약 승인·첫 MID 발급은 가맹점 조회 상세에서 요청하고, 결제수단·Route 개통 전까지 결제는 할 수 없습니다."
      />

      <RegistrationStep<{ registrationNumber: string; displayName: string }>
        {...common}
        memoryKey="step.business"
        title="① 사업자"
        submitLabel="사업자 등록"
        api={{ send: (input) => api.registerBusiness(input) }}
        collect={() => {
          const errors = { number: !REGISTRATION_NUMBER.test(registrationNumber), name: !validName(businessName) }
          setBusinessErrors(errors)
          return errors.number || errors.name ? null : { registrationNumber, displayName: businessName }
        }}
        afterSubmit={() => setRegistrationNumber('')}
        onSucceeded={(reference, payload) => {
          setBusiness({ id: reference, masked: maskRegistrationNumber(payload.registrationNumber) })
          fillInto('step.merchant', () => setBusinessId(reference))
          fillInto('step.contract', () => setPartyBusinessId(reference))
        }}
        succeeded={(reference) => (
          <span className="flex flex-col gap-0.5">
            <span>사업자 ID <Mono>{reference}</Mono></span>
            {business && business.id === reference && <span>사업자등록번호(마스킹) <span className="font-mono">{business.masked}</span></span>}
          </span>
        )}
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

      <RegistrationStep<{ businessId: string; displayName: string }>
        {...common}
        memoryKey="step.merchant"
        title="② 가맹점"
        submitLabel="가맹점 등록"
        api={{ send: (input) => api.registerMerchant(input) }}
        collect={() => {
          const id = businessId.trim()
          const errors = { id: !BUSINESS_ID.test(id), name: !validName(merchantName) }
          setMerchantErrors(errors)
          return errors.id || errors.name ? null : { businessId: id, displayName: merchantName }
        }}
        onSucceeded={(reference, payload) => {
          setMerchantId(reference)
          fillInto('step.site', () => setSiteMerchantId(reference))
          setCreatedMerchant(reference)
          setLookupMerchantId(reference)
          // ④ 자동 채움: 계약 당사자 = 이 가맹점의 사업자, 대상 = 이 가맹점(④가 잠겨 있으면 건너뜀).
          fillInto('step.contract', () => setPartyBusinessId(payload.businessId), () => setScopeMerchantId(reference))
        }}
        succeeded={(reference) => <span>가맹점 ID <Mono>{reference}</Mono></span>}
      >
        {() => (
          <>
            <Field
              label="사업자 ID"
              hint="① 결과가 자동으로 채워집니다. 이미 있는 사업자로 이어서 하려면 직접 입력하세요."
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

      <RegistrationStep<{ merchantId: string; channelType: 'WEB' | 'APP'; channelIdentifier: string }>
        {...common}
        memoryKey="step.site"
        title="③ Site"
        submitLabel="Site 등록"
        api={{ send: (input) => api.registerMerchantSite(input) }}
        collect={() => {
          const id = siteMerchantId.trim()
          const errors = { id: !MERCHANT_ID.test(id), channel: !validChannel(channelType, channelIdentifier) }
          setSiteErrors(errors)
          return errors.id || errors.channel ? null : { merchantId: id, channelType, channelIdentifier }
        }}
        onSucceeded={(reference, payload) => {
          setSiteId(reference)
          setLookupMerchantId(payload.merchantId)
          setCreatedSites([...createdSites, { merchantId: payload.merchantId, siteId: reference }])
          fillInto('step.contract', () => setScopeMerchantId(payload.merchantId))
        }}
        succeeded={(reference) => <span>Site ID <Mono>{reference}</Mono></span>}
      >
        {() => (
          <>
            <Field
              label="가맹점 ID"
              hint="② 결과가 자동으로 채워집니다. 이미 있는 가맹점으로 이어서 하려면 직접 입력하세요."
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

      <RegistrationStep<{ partyBusinessId: string; scopeMerchantId: string; scopeSiteIds: string[]; effectiveFromDate: string; effectiveToDate?: string }>
        {...common}
        memoryKey="step.contract"
        title="④ 계약 초안"
        submitLabel="계약 초안 등록"
        api={{ send: (input) => api.registerContract(input) }}
        collect={() => {
          const party = partyBusinessId.trim()
          const merchant = scopeMerchantId.trim()
          const offered = new Set(offeredSites.map((s) => s.siteId))
          const typed = typedSites.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
          const sites = scopeKind === 'SITES' ? [...new Set([...checkedSites.filter((id) => offered.has(id)), ...typed])] : []
          const errors = {
            party: !BUSINESS_ID.test(party),
            merchant: !MERCHANT_ID.test(merchant),
            start: !DATE.test(startDate),
            end: endDate !== '' && (!DATE.test(endDate) || (DATE.test(startDate) && endDate <= startDate)),
            typedSites: scopeKind === 'SITES' && typed.some((id) => !SITE_ID.test(id)),
            sites: scopeKind === 'SITES' && sites.length === 0,
            tooMany: sites.length > MAX_SCOPE_SITES,
          }
          setContractErrors(errors)
          if (Object.values(errors).some(Boolean)) return null
          // 시작일이 오늘(KST)보다 앞이어도 막지 않는다 — 판정은 MASTER(§3.4, 거절 문구로 안내).
          return { partyBusinessId: party, scopeMerchantId: merchant, scopeSiteIds: sites, effectiveFromDate: startDate, ...(endDate ? { effectiveToDate: endDate } : {}) }
        }}
        onSucceeded={(reference, payload) => {
          setContractId(reference)
          fillInto('step.agreement', () => setAgreementContractId(reference))
          setLookupMerchantId(payload.scopeMerchantId)
        }}
        succeeded={(reference) => <span>계약 ID <Mono>{reference}</Mono> (Revision 1, {REVISION_AWAITING_APPROVAL})</span>}
      >
        {() => (
          <>
            <Field
              label="사업자 ID"
              hint="계약 당사자. ①·② 결과가 자동으로 채워집니다."
              error={contractErrors.party ? '사업자 ID 형식(biz_…)이 아닙니다.' : undefined}
            >
              {(describedBy) => (
                <Input value={partyBusinessId} onChange={(e) => setPartyBusinessId(e.target.value)} autoComplete="off" placeholder="biz_…" aria-describedby={describedBy} className="font-mono" />
              )}
            </Field>
            <Field
              label="가맹점 ID"
              hint="계약 대상 가맹점. ② 결과가 자동으로 채워집니다."
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
                hint={`③에서 만들지 않은 기존 Site는 쉼표로 구분해 입력하세요(최대 ${MAX_SCOPE_SITES}개).`}
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

      <RegistrationStep<{ contractId: string; serviceKind: 'CARD_PG_PAYMENT' }>
        {...common}
        memoryKey="step.agreement"
        title="⑤ 서비스 계약"
        submitLabel="서비스 계약 추가"
        api={{ send: (input) => api.addServiceAgreement(input) }}
        collect={() => {
          const id = agreementContractId.trim()
          const errors = { id: !CONTRACT_ID.test(id) }
          setAgreementErrors(errors)
          return errors.id ? null : { contractId: id, serviceKind: 'CARD_PG_PAYMENT' }
        }}
        onSucceeded={(reference) => setAgreementId(reference)}
        succeeded={(reference) => <span>서비스 계약 ID <Mono>{reference}</Mono></span>}
      >
        {() => (
          <>
            <Field
              label="계약 ID"
              hint="④ 결과가 자동으로 채워집니다. 이미 있는 계약으로 이어서 하려면 직접 입력하세요."
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

      {(createdMerchant || siteId || contractId || agreementId) && (
        <Card as="section" aria-label="등록 결과" className="gap-3 py-5">
          <CardHeader className="px-5">
            <CardTitle className="text-sm">등록 결과</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 px-5 text-sm">
            <dl className="grid gap-2 sm:grid-cols-3">
              {business && (<div><dt className="text-xs text-muted-foreground">사업자</dt><dd><Mono>{business.id}</Mono></dd></div>)}
              {merchantId && (<div><dt className="text-xs text-muted-foreground">가맹점</dt><dd><Mono>{merchantId}</Mono></dd></div>)}
              {siteId && (<div><dt className="text-xs text-muted-foreground">Site</dt><dd><Mono>{siteId}</Mono></dd></div>)}
              {contractId && (<div><dt className="text-xs text-muted-foreground">계약(Revision 1, {REVISION_AWAITING_APPROVAL})</dt><dd><Mono>{contractId}</Mono></dd></div>)}
              {agreementId && (<div><dt className="text-xs text-muted-foreground">서비스 계약</dt><dd><Mono>{agreementId}</Mono></dd></div>)}
            </dl>
            <p className="text-xs text-muted-foreground">다음: 가맹점 조회 상세에서 계약 승인 → 첫 MID 발급을 요청하세요. 결제수단·Route 개통 전까지 결제는 아직 할 수 없습니다.</p>
            {lookupMerchantId && (
              <div className="flex justify-end">
                <Button type="button" variant="outline" onClick={() => onOpenLookup(lookupMerchantId)}>가맹점 조회에서 보기</Button>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </section>
  )
}
