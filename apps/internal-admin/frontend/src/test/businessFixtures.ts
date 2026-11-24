import type { BusinessCommandOutcome, BusinessDetail, BusinessHistoryEntry, BusinessListItem, BusinessLookupItem } from '../api/adminApi'

/**
 * 사업자 화면 시험용 Named Fixture(BUSINESS-PROFILE-001 §14 Test Fixture Policy — `src/test`에만, 명시 Demo 값·운영값 아님).
 * 정상 Fixture는 §4.3 불변식을 만족한다(REGISTERED = 필수값 전부, 대표자 1명 SOLE, 기본 담당자 1명). 식별자는 업무 의미 없는 고정값(화면 시험 — 공유 DB 없음).
 */
export const bizId = (n: number) => `biz_${String(n).padStart(26, '0')}`

/** 개인 REGISTERED 사업자(필수값 충족 — 개업일·사업장 주소·사업체 전화·대표 1명 SOLE·업태·종목·기본 담당자 1명). */
export function individualRegisteredDetail(n = 1, name = '데모 사업자'): BusinessDetail {
  return {
    business: {
      businessId: bizId(n), businessName: name, registrationNumber: '7781234567', businessType: 'INDIVIDUAL', registrationStatus: 'REGISTERED',
      verificationStatus: 'NOT_CHECKED', lifecycle: 'ACTIVE', version: 3, createdAt: '2026-11-17T01:00:00.000Z', updatedAt: '2026-11-17T02:00:00.000Z', merchantCount: 1,
    },
    registration: { corporateRegistrationNumber: null, openingDate: '2026-01-01', countryCode: 'KR', englishName: null, phoneNumber: '0212345678', email: null },
    representatives: [{ name: '홍길동', representativeType: 'SOLE' }],
    categories: [{ categoryType: 'BUSINESS_TYPE', name: '도소매' }, { categoryType: 'BUSINESS_ITEM', name: '전자상거래' }],
    addresses: [{ addressType: 'REGISTERED', postalCode: '04524', baseAddress: '서울특별시 중구 세종대로 110', detailAddress: '데모동 101호' }],
    contacts: [{ name: '김담당', email: 'contact.demo@example.test', phoneNumber: '01012345678', primary: true }],
  }
}

/** 공동대표 2명인 REGISTERED 사업자(대표자 순서 변경 시험용). */
export function jointRegisteredDetail(n = 1): BusinessDetail {
  const detail = individualRegisteredDetail(n)
  return { ...detail, representatives: [{ name: '홍길동', representativeType: 'JOINT' }, { name: '이몽룡', representativeType: 'JOINT' }] }
}

/** DRAFT 최소(구분·번호·상호만 — D-BIZ-002). 하위 행 0개. */
export function draftMinimalDetail(n = 2, name = '임시 사업자'): BusinessDetail {
  return {
    business: {
      businessId: bizId(n), businessName: name, registrationNumber: '1234567890', businessType: 'INDIVIDUAL', registrationStatus: 'DRAFT',
      verificationStatus: 'NOT_CHECKED', lifecycle: 'ACTIVE', version: 0, createdAt: '2026-11-17T01:00:00.000Z', updatedAt: '2026-11-17T01:00:00.000Z', merchantCount: 0,
    },
    registration: { corporateRegistrationNumber: null, openingDate: null, countryCode: 'KR', englishName: null, phoneNumber: null, email: null },
    representatives: [],
    categories: [],
    addresses: [],
    contacts: [],
  }
}

/** migration 행·옛 경로 DRAFT(구분 NULL — X-1·L-7). 가맹점이 이미 있다. */
export function legacyDraftDetail(n = 3): BusinessDetail {
  const detail = draftMinimalDetail(n, 'DEMO 옛 사업자')
  return { ...detail, business: { ...detail.business, businessType: null, registrationNumber: '9990000009', merchantCount: 1 } }
}

export function listItemOf(detail: BusinessDetail): BusinessListItem {
  const first = detail.representatives[0]
  return {
    businessId: detail.business.businessId,
    businessName: detail.business.businessName,
    registrationNumber: detail.business.registrationNumber,
    businessType: detail.business.businessType,
    registrationStatus: detail.business.registrationStatus,
    verificationStatus: detail.business.verificationStatus,
    representativeNameMasked: first ? `${[...first.name][0]}${'*'.repeat([...first.name].length - 1)}` : null,
    representativeCount: detail.representatives.length,
    merchantCount: detail.business.merchantCount,
    updatedAt: detail.business.updatedAt,
  }
}

export function lookupItemOf(detail: BusinessDetail): BusinessLookupItem {
  return {
    businessId: detail.business.businessId,
    businessName: detail.business.businessName,
    registrationNumber: detail.business.registrationNumber,
    registrationStatus: detail.business.registrationStatus,
  }
}

export const commandSucceeded = (reference: string, replayed = false): BusinessCommandOutcome => ({ outcome: 'SUCCEEDED', reference, replayed })
export const commandRejected = (rejection: string, replayed = false): BusinessCommandOutcome => ({ outcome: 'REJECTED', rejection, replayed })

/** 이력 2건(생성 → 상호 정정 — 마스킹 값·사유 원문, MASTER 골든과 같은 모양). */
export const HISTORY: BusinessHistoryEntry[] = [
  {
    seq: 41, changeType: 'BusinessCreated', actorRef: 'admin:adm_01JC00000000000000000000V1', occurredAt: '2026-11-17T01:00:00.000Z', reason: null,
    changes: [{ field: 'displayName', after: '데모 사업자' }, { field: 'representatives', added: [{ name: '홍**', type: 'SOLE', order: 1 }] }],
  },
  {
    seq: 42, changeType: 'BusinessProfileUpdated', actorRef: 'admin:adm_01JC00000000000000000000V1', occurredAt: '2026-11-17T02:00:00.000Z', reason: '상호 정정',
    changes: [
      { field: 'displayName', before: '데모 사업자 (구)', after: '데모 사업자' },
      { field: 'contacts', ended: [{ name: '김**', email: 'c***@e***.test', phoneNumber: '****5678', primary: true, order: 1 }], added: [] },
    ],
  },
]
