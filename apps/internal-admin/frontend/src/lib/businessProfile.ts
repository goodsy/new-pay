import type { BusinessDetail, BusinessProfileContent, BusinessRegistrationStatus } from '../api/adminApi'
import type { FieldError } from '../errors/fieldErrors'

/**
 * 사업자 기본정보 화면 공용 규칙(BUSINESS-PROFILE-001 §4.2·§4.3·§4.5·§8 — BIZ-4).
 * - 형식·필수 검사는 MASTER `BusinessProfileRules`·값 객체 규칙의 **복제**다(즉시 안내용 — 권위는 서버, ADMIN `BusinessProfileInputRules`와 같은 위치).
 *   오류는 서버와 같은 path 문법(§4.6)으로 만들어 서버 `fieldErrors`와 같은 경로로 칸에 붙인다.
 * - 중요정보 변경 감지([importantChange])는 §4.5 표와 같은 규칙(r-6) — 이 파일 한 곳. 서버 판정(`BUSINESS_CHANGE_REASON_REQUIRED`)이 권위.
 * - 내용은 "모든 key 필수·선택 값 null", `countryCode` "KR"(BIZ-3 리뷰 2). 빈 행은 보내지 않는다(§4.3 — 보내기 전에 폼에서 지운다).
 */

export const BUSINESS_TYPE_LABELS: Readonly<Record<string, string>> = { INDIVIDUAL: '개인', CORPORATION: '법인' }
/** INACTIVE(비활성)는 DB 값만 있고 이번 범위에 전이 경로가 없다(TBD-08 — 사용중지 정책 미정). */
export const REGISTRATION_STATUS_LABELS: Readonly<Record<string, string>> = { DRAFT: '임시저장', REGISTERED: '등록 완료', INACTIVE: '비활성(사용중지)' }
export const REPRESENTATIVE_TYPE_LABELS: Readonly<Record<string, string>> = { SOLE: '단독', JOINT: '공동', EACH: '각자' }
export const CATEGORY_TYPE_LABELS: Readonly<Record<string, string>> = { BUSINESS_TYPE: '업태', BUSINESS_ITEM: '종목' }
export const ADDRESS_TYPE_LABELS: Readonly<Record<string, string>> = { REGISTERED: '사업장', ACTUAL: '실사업장' }
export const CHANGE_TYPE_LABELS: Readonly<Record<string, string>> = {
  BusinessCreated: '생성',
  BusinessProfileUpdated: '수정',
  BusinessRegistrationCompleted: '등록 완료',
  // 옛 사업자 등록 경로(BIZ-4 전환에서 제거 — C-27)가 남긴 이력 행.
  BusinessRegistered: '등록(이전 경로)',
}
/** 이력 `changes[].field` → 항목 이름(MASTER `BusinessProfileChange` field 이름). */
export const HISTORY_FIELD_LABELS: Readonly<Record<string, string>> = {
  registrationStatus: '등록 상태',
  displayName: '상호',
  businessType: '사업체 구분',
  'registration.corporateRegistrationNumber': '법인등록번호',
  'registration.openingDate': '개업일',
  'registration.englishName': '영문 상호',
  'registration.phoneNumber': '사업체 전화',
  'registration.email': '사업체 이메일',
  representatives: '대표자',
  categories: '업태·종목',
  addresses: '주소',
  contacts: '등록 담당자',
}

/** 진위확인(TC-21): 이번 범위는 `NOT_CHECKED`만 — 항상 "미검증". 다른 값이 오면 꾸미지 않고 원래 code(성공 위조 없음). */
export const verificationLabel = (code: string) => (code === 'NOT_CHECKED' ? '미검증' : code)
export const labelIn = (labels: Readonly<Record<string, string>>, code: string | null | undefined, empty = '미입력') => (code ? labels[code] ?? code : empty)

/** 429 문구(BIZ-3 리뷰 4 — L-14): 한도 숫자는 Demo 설정 값(운영값 아님). */
export const BUSINESS_READ_RATE_LIMITED = '사업자 조회가 너무 잦습니다. 잠시 후 다시 시도하세요(사업자 조회 30회/분 — 목록·상세·이력·번호 조회 합산, Demo 값·운영값 아님).'
export const BRN_RATE_LIMITED =
  '번호 조회가 너무 잦습니다. 잠시 후 다시 시도하세요(사업자등록번호 10회/분 — 번호 조회·가맹점 번호 검색·사업자 생성 합산, 번호 조회는 사업자 조회 30회/분에도 포함 · Demo 값·운영값 아님).'
export const COMMAND_RATE_LIMITED =
  '저장 요청이 너무 잦습니다. 잠시 후 다시 시도하세요(사업자 저장 20회/분, 생성은 사업자등록번호 10회/분도 함께 — Demo 값·운영값 아님).'

// ── 입력 폼 ─────────────────────────────────────────────────────────────

export interface AddressForm { postalCode: string; baseAddress: string; detailAddress: string }
export interface RepresentativeForm { name: string; type: string }
export interface ContactForm { name: string; email: string; phoneNumber: string; primary: boolean }

/** 화면 입력 상태(문자열 — 빈 문자열 = 미입력). 번호(사업자등록번호)는 여기 두지 않는다(평문은 화면 자체 상태 — §8.3). 전화·법인번호는 숫자만(하이픈은 표시만). */
export interface BusinessForm {
  businessType: string
  displayName: string
  corporateRegistrationNumber: string
  openingDate: string
  englishName: string
  phoneNumber: string
  email: string
  businessTypes: string[]
  businessItems: string[]
  registeredAddress: AddressForm
  actualDifferent: boolean
  actualAddress: AddressForm
  representatives: RepresentativeForm[]
  contacts: ContactForm[]
}

const NO_ADDRESS: AddressForm = Object.freeze({ postalCode: '', baseAddress: '', detailAddress: '' }) as AddressForm

export const EMPTY_FORM: BusinessForm = Object.freeze({
  businessType: '',
  displayName: '',
  corporateRegistrationNumber: '',
  openingDate: '',
  englishName: '',
  phoneNumber: '',
  email: '',
  businessTypes: [''],
  businessItems: [''],
  registeredAddress: NO_ADDRESS,
  actualDifferent: false,
  actualAddress: NO_ADDRESS,
  representatives: [{ name: '', type: 'SOLE' }],
  contacts: [{ name: '', email: '', phoneNumber: '', primary: true }],
}) as BusinessForm

/** 상세 → 수정 폼(현재 값 그대로). */
export function formOf(detail: BusinessDetail): BusinessForm {
  const reg = detail.registration
  const address = (type: string): AddressForm | undefined => {
    const found = detail.addresses.find((a) => a.addressType === type)
    return found && { postalCode: found.postalCode, baseAddress: found.baseAddress, detailAddress: found.detailAddress ?? '' }
  }
  const actual = address('ACTUAL')
  const names = (type: string) => detail.categories.filter((c) => c.categoryType === type).map((c) => c.name)
  return {
    businessType: detail.business.businessType ?? '',
    displayName: detail.business.businessName,
    corporateRegistrationNumber: reg.corporateRegistrationNumber ?? '',
    openingDate: reg.openingDate ?? '',
    englishName: reg.englishName ?? '',
    phoneNumber: reg.phoneNumber ?? '',
    email: reg.email ?? '',
    businessTypes: withBlank(names('BUSINESS_TYPE')),
    businessItems: withBlank(names('BUSINESS_ITEM')),
    registeredAddress: address('REGISTERED') ?? NO_ADDRESS,
    actualDifferent: actual !== undefined,
    actualAddress: actual ?? NO_ADDRESS,
    representatives: detail.representatives.length > 0 ? detail.representatives.map((r) => ({ name: r.name, type: r.representativeType })) : [{ name: '', type: 'SOLE' }],
    contacts: detail.contacts.length > 0 ? detail.contacts.map((c) => ({ ...c })) : [{ name: '', email: '', phoneNumber: '', primary: true }],
  }
}

const withBlank = (values: string[]) => (values.length > 0 ? values : [''])
const blankAddress = (a: AddressForm) => a.postalCode === '' && a.baseAddress === '' && a.detailAddress === ''

/** 빈 행 지우기(보내기 전 — 서버로 가는 목록 색인과 화면 행 색인을 같게 둔다, 필드 오류 path 매핑). 대표자 행은 이름이 비면 빈 행(유형은 기본값이 있다). */
export function compactForm(form: BusinessForm): BusinessForm {
  return {
    ...form,
    businessTypes: form.businessTypes.filter((n) => n !== ''),
    businessItems: form.businessItems.filter((n) => n !== ''),
    representatives: form.representatives.filter((r) => r.name !== ''),
    contacts: form.contacts.filter((c) => c.name !== '' || c.email !== '' || c.phoneNumber !== ''),
  }
}

/**
 * 보낸 뒤 화면에 둘 폼: [compactForm] 결과에서 비어 버린 목록에만 빈 입력 행 하나를 **끝에** 다시 둔다(입력 칸이 사라지지 않게 — 보낸 행의 색인은 그대로라 필드 오류 path 매핑 불변).
 */
export function withInputRows(form: BusinessForm): BusinessForm {
  return {
    ...form,
    businessTypes: form.businessTypes.length > 0 ? form.businessTypes : [''],
    businessItems: form.businessItems.length > 0 ? form.businessItems : [''],
    representatives: form.representatives.length > 0 ? form.representatives : [{ name: '', type: 'SOLE' }],
    contacts: form.contacts.length > 0 ? form.contacts : [{ name: '', email: '', phoneNumber: '', primary: true }],
  }
}

/** 폼 → 명령 내용(모든 key·선택 null·countryCode "KR"). [form]은 [compactForm] 결과. 법인등록번호는 법인일 때만(개인이면 null — 칸도 숨긴다). */
export function contentOf(form: BusinessForm): BusinessProfileContent {
  const orNull = (value: string) => (value === '' ? null : value)
  const addresses: BusinessProfileContent['addresses'] = []
  if (!blankAddress(form.registeredAddress)) addresses.push({ addressType: 'REGISTERED', ...addressContent(form.registeredAddress) })
  if (form.actualDifferent && !blankAddress(form.actualAddress)) addresses.push({ addressType: 'ACTUAL', ...addressContent(form.actualAddress) })
  return {
    businessType: form.businessType,
    displayName: form.displayName,
    registration: {
      corporateRegistrationNumber: form.businessType === 'CORPORATION' ? orNull(form.corporateRegistrationNumber) : null,
      openingDate: orNull(form.openingDate),
      countryCode: 'KR',
      englishName: orNull(form.englishName),
      phoneNumber: orNull(form.phoneNumber),
      email: orNull(form.email),
    },
    representatives: form.representatives.map((r) => ({ name: r.name, representativeType: r.type })),
    // E-4: 업태 → 종목 순서(유형 안 순서만 저장된다).
    categories: [
      ...form.businessTypes.map((name) => ({ categoryType: 'BUSINESS_TYPE', name })),
      ...form.businessItems.map((name) => ({ categoryType: 'BUSINESS_ITEM', name })),
    ],
    addresses,
    contacts: form.contacts.map((c) => ({ name: c.name, email: c.email, phoneNumber: c.phoneNumber, primary: c.primary })),
  }
}

const addressContent = (a: AddressForm) => ({ postalCode: a.postalCode, baseAddress: a.baseAddress, detailAddress: a.detailAddress === '' ? null : a.detailAddress })

/** 상세 → 저장된 내용(변경 감지의 "이전" 값). */
export function contentOfDetail(detail: BusinessDetail): BusinessProfileContent {
  return {
    businessType: detail.business.businessType ?? '',
    displayName: detail.business.businessName,
    registration: { ...detail.registration, countryCode: 'KR' },
    representatives: detail.representatives.map((r) => ({ ...r })),
    categories: detail.categories.map((c) => ({ ...c })),
    addresses: detail.addresses.map((a) => ({ ...a })),
    contacts: detail.contacts.map((c) => ({ ...c })),
  }
}

// ── 형식·필수 규칙(복제 — §4.2·§4.3) ───────────────────────────────────

/** 짝 없는 서로게이트(UTF-16 code unit 단위 — ES2022 lib에는 isWellFormed가 없다). */
const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/

/** 공통 문자 규칙(§4.2): 앞뒤 공백·제어문자·짝 없는 서로게이트 거절, code point 길이 상한. */
function textError(value: string, max: number): string | null {
  if (value === '') return 'REQUIRED'
  if (/^\s|\s$/u.test(value) || /\p{Cc}/u.test(value) || LONE_SURROGATE.test(value)) return 'FORMAT'
  return [...value].length > max ? 'TOO_LONG' : null
}

/** 변경 사유(§4.2 `BusinessChangeReason` — 1~200 code point, 같은 문자 규칙·줄바꿈 불가). */
export const reasonError = (value: string) => textError(value, 200)

const DIGITS = (n: string) => new RegExp(`^[0-9]{${n}}$`)
const REGISTRATION_NUMBER = DIGITS('10')
const CORPORATE_NUMBER = DIGITS('13')
const POSTAL_CODE = DIGITS('5')
const PHONE = /^[01][0-9]{7,11}$/
const EMAIL = /^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}\.)+[A-Za-z0-9-]{2,63}$/
const ENGLISH = /^[\x20-\x7E]+$/
const ISO_DATE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/

/** 실재 달력 날짜(§4.2 C-21 — 범위 규칙 없음). */
export function isCalendarDate(value: string): boolean {
  const m = ISO_DATE.exec(value)
  if (!m) return false
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const date = new Date(Date.UTC(y, mo - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === mo - 1 && date.getUTCDate() === d
}

const formatOrNull = (ok: boolean) => (ok ? null : 'FORMAT')
const emailError = (value: string) => (value.length < 3 || value.length > 254 ? 'FORMAT' : formatOrNull(EMAIL.test(value)))
const englishError = (value: string) => (value.length > 100 ? 'TOO_LONG' : formatOrNull(ENGLISH.test(value) && value === value.trim()))

/**
 * 즉시 안내용 검사(§4.2·§4.3 복제 — 서버가 권위): [form]은 [compactForm] 결과, [registrationNumber]는 생성일 때만(수정·등록 완료는 null — 번호 칸 없음).
 * [target] DRAFT = 공통 규칙 + 최소(구분·번호·상호, D-BIZ-002), REGISTERED = 필수 전부. 형식 위반이 있으면 형식 오류만(E-10 ① — 서버와 같은 순서).
 */
export function validateForm(form: BusinessForm, registrationNumber: string | null, target: 'DRAFT' | 'REGISTERED'): FieldError[] {
  const errors: FieldError[] = []
  const add = (path: string, code: string | null) => {
    if (code) errors.push({ path, code })
  }
  const optional = (path: string, value: string, check: (v: string) => string | null) => {
    if (value !== '') add(path, check(value))
  }
  if (form.businessType === '') add('businessType', 'REQUIRED')
  if (registrationNumber !== null) add('registrationNumber', registrationNumber === '' ? 'REQUIRED' : formatOrNull(REGISTRATION_NUMBER.test(registrationNumber)))
  add('displayName', textError(form.displayName, 100))
  if (form.businessType === 'CORPORATION') optional('registration.corporateRegistrationNumber', form.corporateRegistrationNumber, (v) => formatOrNull(CORPORATE_NUMBER.test(v)))
  optional('registration.openingDate', form.openingDate, (v) => formatOrNull(isCalendarDate(v)))
  optional('registration.englishName', form.englishName, englishError)
  optional('registration.phoneNumber', form.phoneNumber, (v) => formatOrNull(PHONE.test(v)))
  optional('registration.email', form.email, emailError)
  form.representatives.forEach((r, i) => {
    add(`representatives[${i}].name`, textError(r.name, 50))
    if (!REPRESENTATIVE_TYPE_LABELS[r.type]) add(`representatives[${i}].type`, 'REQUIRED')
  })
  const categories = [...form.businessTypes, ...form.businessItems]
  categories.forEach((name, i) => add(`categories[${i}].name`, textError(name, 50)))
  const addresses = contentOf(form).addresses
  addresses.forEach((a, i) => {
    add(`addresses[${i}].postalCode`, a.postalCode === '' ? 'REQUIRED' : formatOrNull(POSTAL_CODE.test(a.postalCode)))
    add(`addresses[${i}].baseAddress`, textError(a.baseAddress, 200))
    if (a.detailAddress !== null) add(`addresses[${i}].detailAddress`, textError(a.detailAddress, 200))
  })
  form.contacts.forEach((c, i) => {
    add(`contacts[${i}].name`, textError(c.name, 50))
    add(`contacts[${i}].email`, c.email === '' ? 'REQUIRED' : emailError(c.email))
    add(`contacts[${i}].phoneNumber`, c.phoneNumber === '' ? 'REQUIRED' : formatOrNull(PHONE.test(c.phoneNumber)))
  })
  if (errors.some((e) => e.code !== 'REQUIRED' || e.path.includes('['))) return sorted(errors)

  // Aggregate 규칙(§4.3) — 상한(C-19)은 화면 행 추가 버튼이 막는다.
  if (form.representatives.length > 1 && form.representatives.some((r) => r.type === 'SOLE')) add('representatives', 'SOLE_WITH_OTHERS')
  const seen = new Set<string>()
  categories.forEach((name, i) => {
    const key = `${i < form.businessTypes.length ? 'T' : 'I'}:${name}`
    if (seen.has(key)) add(`categories[${i}].name`, 'DUPLICATE')
    seen.add(key)
  })
  const [registered, actual] = [addresses.find((a) => a.addressType === 'REGISTERED'), addresses.find((a) => a.addressType === 'ACTUAL')]
  if (registered && actual && registered.postalCode === actual.postalCode && registered.baseAddress === actual.baseAddress && registered.detailAddress === actual.detailAddress) {
    add('addresses', 'ACTUAL_SAME_AS_REGISTERED')
  }
  const primaries = form.contacts.filter((c) => c.primary).length
  if (primaries > 1) add('contacts', 'PRIMARY_MULTIPLE')
  if (target === 'REGISTERED') {
    if (form.openingDate === '') add('registration.openingDate', 'REQUIRED')
    if (form.phoneNumber === '') add('registration.phoneNumber', 'REQUIRED')
    if (form.businessType === 'CORPORATION' && form.corporateRegistrationNumber === '') add('registration.corporateRegistrationNumber', 'REQUIRED')
    if (!registered) add('addresses', 'REQUIRED')
    if (form.representatives.length === 0) add('representatives', 'REQUIRED')
    if (form.businessTypes.length === 0 || form.businessItems.length === 0) add('categories', 'BUSINESS_TYPE_ITEM_REQUIRED')
    if (form.contacts.length === 0) add('contacts', 'REQUIRED')
    else if (primaries === 0) add('contacts', 'PRIMARY_REQUIRED')
  }
  return sorted(errors)
}

const sorted = (errors: FieldError[]) => [...errors].sort((a, b) => (a.path === b.path ? a.code.localeCompare(b.code) : a.path < b.path ? -1 : 1))

// ── 필드 오류 → 화면 칸 ─────────────────────────────────────────────────

/**
 * 서버·화면 path(§4.6) → 화면 칸 key. 목록 색인은 보낸 목록 기준이라 화면 행으로 옮긴다:
 * `categories[i]` → 업태 행 i 또는 종목 행 i − 업태 수, `addresses[i]` → 보낸 주소 순서(사업장 → 실사업장). [form]은 보낸 폼([compactForm] 결과).
 * 칸 key 예: `displayName`·`registration.phoneNumber`·`representatives[1].name`·`businessItems[0]`·`registeredAddress.postalCode`·`categories`(목록 전체).
 */
export function fieldKeyOf(path: string, form: BusinessForm): string {
  const category = /^categories\[([0-9]+)\]\.(type|name)$/.exec(path)
  if (category) {
    const i = Number(category[1])
    return i < form.businessTypes.length ? `businessTypes[${i}]` : `businessItems[${i - form.businessTypes.length}]`
  }
  const address = /^addresses\[([0-9]+)\]\.(type|postalCode|baseAddress|detailAddress)$/.exec(path)
  if (address) {
    const sent = contentOf(form).addresses[Number(address[1])]
    const prefix = sent?.addressType === 'ACTUAL' ? 'actualAddress' : 'registeredAddress'
    return address[2] === 'type' ? 'addresses' : `${prefix}.${address[2]}`
  }
  return path
}

export function fieldErrorMap(errors: readonly FieldError[], form: BusinessForm): Readonly<Record<string, string>> {
  const map: Record<string, string> = {}
  for (const e of errors) {
    const key = fieldKeyOf(e.path, form)
    if (!(key in map)) map[key] = e.code
  }
  return map
}

const FIELD_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  REQUIRED: '필수 입력입니다.',
  FORMAT: '형식이 올바르지 않습니다.',
  TOO_LONG: '허용 길이를 넘었습니다.',
  TOO_MANY: '개수 상한을 넘었습니다(대표자 5·업태 10·종목 10·주소 유형별 1·담당자 5).',
  DUPLICATE: '같은 이름이 이미 있습니다.',
  NOT_ALLOWED: '이 사업체 구분에서는 입력할 수 없습니다.',
  SOLE_WITH_OTHERS: '단독 대표는 대표자가 1명일 때만 고를 수 있습니다(2명 이상이면 공동·각자).',
  PRIMARY_REQUIRED: '기본 등록 담당자를 1명 고르세요.',
  PRIMARY_MULTIPLE: '기본 등록 담당자는 1명만 고를 수 있습니다.',
  ACTUAL_SAME_AS_REGISTERED: '실사업장 주소가 사업장 주소와 같습니다(다를 때만 입력하세요).',
  BUSINESS_TYPE_ITEM_REQUIRED: '업태와 종목을 각각 1개 이상 입력하세요.',
}

/** 칸별 형식 안내(FORMAT일 때 무엇이 맞는 형식인지). */
const FORMAT_HINTS: Readonly<Record<string, string>> = {
  registrationNumber: '사업자등록번호는 숫자 10자리입니다.',
  'registration.corporateRegistrationNumber': '법인등록번호는 숫자 13자리입니다.',
  'registration.openingDate': '실제 날짜(YYYY-MM-DD)를 입력하세요.',
  'registration.englishName': '영문·숫자·기호(ASCII)만, 앞뒤 공백 없이 입력하세요.',
  'registration.phoneNumber': '전화번호는 숫자 8~12자리(0 또는 1로 시작)입니다.',
  'registration.email': '이메일 형식(예: name@example.com)이 아닙니다.',
  postalCode: '우편번호는 숫자 5자리입니다.',
  phoneNumber: '전화번호는 숫자 8~12자리(0 또는 1로 시작)입니다.',
  email: '이메일 형식(예: name@example.com)이 아닙니다.',
}

export function fieldErrorMessage(key: string, code: string): string {
  if (code === 'FORMAT') {
    const hint = FORMAT_HINTS[key] ?? FORMAT_HINTS[key.slice(key.lastIndexOf('.') + 1)]
    if (hint) return hint
    return '형식이 올바르지 않습니다(앞뒤 공백·제어 문자 불가).'
  }
  if (code === 'TOO_LONG') {
    if (key === 'reason') return '200자 이하로 입력하세요.'
    if (key === 'displayName' || key === 'registration.englishName') return '100자 이하로 입력하세요.'
    if (key.endsWith('Address') || key.includes('Address.')) return '200자 이하로 입력하세요.'
    return '50자 이하로 입력하세요.'
  }
  return FIELD_ERROR_MESSAGES[code] ?? '입력을 확인하세요.'
}

// ── 중요정보 변경 감지(§4.5 — r-6) ─────────────────────────────────────

/**
 * 이 변경이 중요정보 변경인지(사유 필수 — POL-004, §4.5 표 그대로). 서버 `BusinessProfileChange`와 같은 규칙:
 * 상호 변경, 사업체 구분 변경(migration 행의 NULL → 첫 값 제외), 개업일·법인등록번호의 기존 값 변경·삭제(첫 입력 제외),
 * 대표자 — REGISTERED는 현재 대표자 목록의 모든 변경(추가·종료·유형·순서, M-2), DRAFT는 기존 행 종료가 있을 때만(0명 → 첫 입력·유지 + 추가는 일반).
 * 하위 행 동일성 = 내용 + 순서(M-4). 업태·종목·주소·연락처·담당자·영문 상호는 일반.
 */
export function importantChange(beforeStatus: BusinessRegistrationStatus, beforeType: string | null, before: BusinessProfileContent, after: BusinessProfileContent): boolean {
  if (before.displayName !== after.displayName) return true
  if (beforeType !== null && beforeType !== after.businessType) return true
  const changedOrRemoved = (b: string | null, a: string | null) => b !== null && b !== a
  if (changedOrRemoved(before.registration.openingDate, after.registration.openingDate)) return true
  if (changedOrRemoved(before.registration.corporateRegistrationNumber, after.registration.corporateRegistrationNumber)) return true
  const b = before.representatives
  const a = after.representatives
  const kept = (i: number) => a[i] !== undefined && a[i]!.name === b[i]!.name && a[i]!.representativeType === b[i]!.representativeType
  if (beforeStatus === 'REGISTERED') return b.length !== a.length || b.some((_, i) => !kept(i))
  return b.some((_, i) => !kept(i))
}

// ── 표시 ────────────────────────────────────────────────────────────────

/** 전화 표시용 하이픈(보내는 값은 숫자만 — §8.3): 02 지역번호, 8자리 대표번호(15xx·16xx·18xx), 그 밖 3-3/4-4. */
export function formatPhone(digits: string): string {
  if (digits.startsWith('02')) {
    if (digits.length <= 2) return digits
    if (digits.length <= 5) return `02-${digits.slice(2)}`
    const middle = digits.length >= 10 ? 4 : 3
    return `02-${digits.slice(2, 2 + middle)}-${digits.slice(2 + middle)}`
  }
  if (digits.length === 8 && digits.startsWith('1')) return `${digits.slice(0, 4)}-${digits.slice(4)}`
  if (digits.length <= 3) return digits
  if (digits.length <= 6) return `${digits.slice(0, 3)}-${digits.slice(3)}`
  const middle = digits.length >= 11 ? 4 : 3
  return `${digits.slice(0, 3)}-${digits.slice(3, 3 + middle)}-${digits.slice(3 + middle)}`
}

/** 법인등록번호 표시 `000000-0000000`(보내는 값은 숫자만). */
export const formatCorporateNumber = (digits: string) => (digits.length <= 6 ? digits : `${digits.slice(0, 6)}-${digits.slice(6)}`)

/** 목록 대표자 표시: 마스킹 이름 + "외 N명"(§8.2). */
export function representativeSummary(masked: string | null, count: number): string {
  if (masked === null || count === 0) return '미입력'
  return count > 1 ? `${masked} 외 ${count - 1}명` : masked
}
