// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { businessSearchBody } from '../api/adminApi'
import { parseFieldErrors } from '../errors/fieldErrors'
import { individualRegisteredDetail, draftMinimalDetail, jointRegisteredDetail } from '../test/businessFixtures'
import {
  compactForm, contentOf, contentOfDetail, EMPTY_FORM, fieldErrorMessage, fieldKeyOf, formatCorporateNumber, formatPhone, formOf, importantChange, reasonError,
  representativeSummary, validateForm, verificationLabel,
} from './businessProfile'

/** BUSINESS-PROFILE-001 BIZ-4 화면 규칙(§4.2·§4.3 복제, §4.5 변경 감지 표 — r-6, BIZ-3 리뷰 1·2). 서버가 권위 — 이 시험은 화면 복제가 같은 규칙인지 고정한다. */
describe('importantChange (§4.5 표 — 서버 BusinessProfileChange와 같은 규칙)', () => {
  const reg = individualRegisteredDetail()
  const before = contentOfDetail(reg)
  const after = (patch: (c: ReturnType<typeof contentOfDetail>) => void) => {
    const c = structuredClone(before)
    patch(c)
    return c
  }
  it.each([
    ['상호 변경', (c: typeof before) => { c.displayName = '새 상호' }, true],
    ['구분 변경', (c: typeof before) => { c.businessType = 'CORPORATION' }, true],
    ['개업일 변경', (c: typeof before) => { c.registration.openingDate = '2026-02-01' }, true],
    ['개업일 삭제', (c: typeof before) => { c.registration.openingDate = null }, true],
    ['REGISTERED 대표자 추가만 (M-2)', (c: typeof before) => { c.representatives.push({ name: '이몽룡', representativeType: 'JOINT' }) }, true],
    ['REGISTERED 대표자 유형만', (c: typeof before) => { c.representatives[0]!.representativeType = 'EACH' }, true],
    ['업태 이름 변경(일반)', (c: typeof before) => { c.categories[0]!.name = '서비스' }, false],
    ['이메일·영문 상호(일반)', (c: typeof before) => { c.registration.email = 'a@b.cd'; c.registration.englishName = 'Demo' }, false],
    ['주소·담당자(일반)', (c: typeof before) => { c.addresses[0]!.detailAddress = null; c.contacts[0]!.phoneNumber = '01099998888' }, false],
  ])('REGISTERED: %s → %s', (_, patch, expected) => {
    expect(importantChange('REGISTERED', 'INDIVIDUAL', before, after(patch))).toBe(expected)
  })
  it('REGISTERED 대표자 순서만 변경 → 중요 (M-4); 같은 내용 → 일반', () => {
    const joint = contentOfDetail(jointRegisteredDetail())
    const swapped = { ...joint, representatives: [joint.representatives[1]!, joint.representatives[0]!] }
    expect(importantChange('REGISTERED', 'INDIVIDUAL', joint, swapped)).toBe(true)
    expect(importantChange('REGISTERED', 'INDIVIDUAL', joint, structuredClone(joint))).toBe(false)
  })
  it('DRAFT: 0 → 1 첫 대표자·첫 개업일·첫 법인번호는 일반, 유지 + 추가는 일반, 기존 행 종료는 중요; 구분 NULL → 첫 값은 일반', () => {
    const draft = contentOfDetail(draftMinimalDetail())
    const first = { ...draft, representatives: [{ name: '홍길동', representativeType: 'SOLE' }], registration: { ...draft.registration, openingDate: '2026-01-01' } }
    expect(importantChange('DRAFT', 'INDIVIDUAL', draft, first)).toBe(false)
    const added = { ...first, representatives: [{ name: '홍길동', representativeType: 'JOINT' }] }
    expect(importantChange('DRAFT', 'INDIVIDUAL', first, { ...first, representatives: [...first.representatives, { name: '이몽룡', representativeType: 'JOINT' }] })).toBe(false)
    expect(importantChange('DRAFT', 'INDIVIDUAL', first, added)).toBe(true)
    expect(importantChange('DRAFT', null, { ...draft, businessType: '' }, draft)).toBe(false)
  })
})

describe('content / validation / field keys', () => {
  it('contentOf: every key present, optional values null, countryCode KR, 업태 → 종목 order (E-4), corporate number only for 법인, blank rows dropped', () => {
    const form = compactForm({ ...EMPTY_FORM, businessType: 'INDIVIDUAL', displayName: '상호', corporateRegistrationNumber: '1101111234567', businessItems: ['종목A', ''], businessTypes: ['업태A'] })
    expect(contentOf(form)).toEqual({
      businessType: 'INDIVIDUAL', displayName: '상호',
      registration: { corporateRegistrationNumber: null, openingDate: null, countryCode: 'KR', englishName: null, phoneNumber: null, email: null },
      representatives: [], categories: [{ categoryType: 'BUSINESS_TYPE', name: '업태A' }, { categoryType: 'BUSINESS_ITEM', name: '종목A' }], addresses: [], contacts: [],
    })
  })
  it('formOf → contentOf round-trips a REGISTERED detail', () => {
    const detail = individualRegisteredDetail()
    expect(contentOf(compactForm(formOf(detail)))).toEqual(contentOfDetail(detail))
  })
  it('validateForm: DRAFT minimum (D-BIZ-002) and REGISTERED requirements; format errors short-circuit the aggregate rules (E-10 ①)', () => {
    expect(validateForm(compactForm(EMPTY_FORM), '', 'DRAFT').map((e) => `${e.path}:${e.code}`)).toEqual(['businessType:REQUIRED', 'displayName:REQUIRED', 'registrationNumber:REQUIRED'])
    const minimal = compactForm({ ...EMPTY_FORM, businessType: 'CORPORATION', displayName: '법인' })
    expect(validateForm(minimal, '7781234560', 'DRAFT')).toEqual([])
    expect(validateForm(minimal, '7781234560', 'REGISTERED').map((e) => `${e.path}:${e.code}`)).toEqual([
      'addresses:REQUIRED', 'categories:BUSINESS_TYPE_ITEM_REQUIRED', 'contacts:REQUIRED', 'registration.corporateRegistrationNumber:REQUIRED', 'registration.openingDate:REQUIRED',
      'registration.phoneNumber:REQUIRED', 'representatives:REQUIRED',
    ])
    const bad = compactForm({ ...EMPTY_FORM, businessType: 'INDIVIDUAL', displayName: ' 앞공백', phoneNumber: '9123', openingDate: '2026-02-30', representatives: [{ name: 'a', type: 'SOLE' }, { name: 'b', type: 'SOLE' }] })
    expect(validateForm(bad, '77812', 'DRAFT').map((e) => `${e.path}:${e.code}`)).toEqual([
      'displayName:FORMAT', 'registration.openingDate:FORMAT', 'registration.phoneNumber:FORMAT', 'registrationNumber:FORMAT',
    ])
    expect(validateForm({ ...bad, displayName: '상호', phoneNumber: '', openingDate: '' }, '7781234560', 'DRAFT').map((e) => `${e.path}:${e.code}`)).toEqual(['representatives:SOLE_WITH_OTHERS'])
  })
  it('fieldKeyOf maps categories[i] / addresses[i] to the screen rows', () => {
    const form = compactForm({ ...EMPTY_FORM, businessTypes: ['A', 'B'], businessItems: ['C'], registeredAddress: { postalCode: '1', baseAddress: '', detailAddress: '' }, actualDifferent: true, actualAddress: { postalCode: '2', baseAddress: '', detailAddress: '' } })
    expect(fieldKeyOf('categories[1].name', form)).toBe('businessTypes[1]')
    expect(fieldKeyOf('categories[2].name', form)).toBe('businessItems[0]')
    expect(fieldKeyOf('addresses[1].postalCode', form)).toBe('actualAddress.postalCode')
    expect(fieldKeyOf('representatives[0].type', form)).toBe('representatives[0].type')
  })
  it('parseFieldErrors keeps §4.6 path/code only (all or nothing — no values)', () => {
    expect(parseFieldErrors({ fieldErrors: [{ path: 'categories', code: 'TOO_MANY' }, { path: 'representatives[2].type', code: 'REQUIRED' }] })).toEqual([
      { path: 'categories', code: 'TOO_MANY' }, { path: 'representatives[2].type', code: 'REQUIRED' },
    ])
    expect(parseFieldErrors({ fieldErrors: [{ path: 'categories', code: 'TOO_MANY' }, { path: 'secret', code: 'REQUIRED' }] })).toBeUndefined()
    expect(parseFieldErrors({ fieldErrors: [{ path: 'displayName', code: 'REQUIRED', value: 'x' }] })).toEqual([{ path: 'displayName', code: 'REQUIRED' }])
    expect(parseFieldErrors({ fieldErrors: [] })).toBeUndefined()
  })
  it('businessSearchBody omits empty fields — never null (BIZ-3 리뷰 1)', () => {
    expect(businessSearchBody({ displayNamePrefix: '', businessType: undefined, after: 'biz_x', limit: 20 })).toEqual({ after: 'biz_x', limit: 20 })
    expect(businessSearchBody({ displayNamePrefix: null as unknown as string })).toEqual({})
  })
  it('display helpers', () => {
    expect(formatPhone('0212345678')).toBe('02-1234-5678')
    expect(formatPhone('021234567')).toBe('02-123-4567')
    expect(formatPhone('01012345678')).toBe('010-1234-5678')
    expect(formatPhone('15881234')).toBe('1588-1234')
    expect(formatCorporateNumber('1101111234567')).toBe('110111-1234567')
    expect(representativeSummary('홍**', 3)).toBe('홍** 외 2명')
    expect(representativeSummary(null, 0)).toBe('미입력')
    expect(verificationLabel('NOT_CHECKED')).toBe('미검증')
  })
})

/**
 * SEC-BIZ-001 M-1(BR-BIZ-305, D-FLOW-007 ①): '사유 및 비고' 칸 카드번호 패턴 거부. 공통 시험 벡터 — 판정 정본 ADMIN `OperatorCancelInputRules.containsCardNumber`,
 * MASTER `CardNumberVectors`·ADMIN `BusinessCardNumberVectors`와 같은 목록(복제 — 바꾸면 세 곳 함께). 서버가 권위, 화면은 즉시 안내.
 */
describe('reasonError card-number pattern (common vector)', () => {
  const CARD_LIKE = [
    'card 4111111111111111', '4111 1111 1111 1111', '4111-1111-1111-1111', '1234567890123', '1234567890123456789', 'ref 5500-0000-0000-0004 x',
    'card ' + '４１１１'.repeat(4), 'card ' + '٤١١١'.repeat(4), 'card 4111 １１１１ 1111 1111', 'RECON-20261028-000001',
  ]
  const NOT_CARD = ['amount 7000 won', 'ticket 123456789012', 'tel 010-1234-5678', '12345678901234567890 is too long to be a card', 'RECON-2026-0001', 'INQ-2026-1105-0001']
  it('card-like digit runs → CARD_NUMBER_PATTERN; other digit runs pass', () => {
    expect(CARD_LIKE.map(reasonError)).toEqual(CARD_LIKE.map(() => 'CARD_NUMBER_PATTERN'))
    expect(NOT_CARD.map(reasonError)).toEqual(NOT_CARD.map(() => null))
  })
  it('existing character rules come first (FORMAT · TOO_LONG); the message never repeats the value', () => {
    expect([' 4111111111111111', '4111111111111111\n', '4111111111111111' + '가'.repeat(185)].map(reasonError)).toEqual(['FORMAT', 'FORMAT', 'TOO_LONG'])
    const message = fieldErrorMessage('reason', 'CARD_NUMBER_PATTERN')
    expect(message).toContain('카드 번호')
    expect(message).not.toContain('4111')
  })
  it('the server field error code is accepted by parseFieldErrors', () => {
    expect(parseFieldErrors({ fieldErrors: [{ path: 'reason', code: 'CARD_NUMBER_PATTERN' }] })).toEqual([{ path: 'reason', code: 'CARD_NUMBER_PATTERN' }])
  })
})
