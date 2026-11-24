import type { MerchantDetail } from '../api/adminApi'
import { labelOf } from './labels'

/**
 * 가맹점 상세의 사람용 이름(MERCHANT-IA-001 B3 — 명세 §7 B3, 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로").
 * 내부 키(site_·ctr_·csa_·act_·apr_·sac_·cra_·tlp_·pas_·cur_·asg_ …)는 상세·명령 호출 인자와 화면 메모리 키로만 쓰고 DOM(글자·속성·입력값)에는 그리지 않는다.
 * 여기 함수는 그 키를 받아 같은 상세 응답(MerchantDetail) 안의 사람이 읽는 값으로 바꾼다. 찾지 못하면 키를 내보내지 않고 고정 문구를 돌려준다(fail-closed).
 * MID(mid_)는 외부 연동 식별자(가맹점이 API에 쓰는 값 — 명세 §3.6)라 그대로 보인다.
 */

export const UNKNOWN_SITE = '알 수 없는 Site'
export const UNKNOWN_CONTRACT = '알 수 없는 계약'

type Site = MerchantDetail['sites'][number]

/**
 * Site 이름 = "<채널 종류> · <채널 식별값>"(예: "웹 · shop.example.test", "앱 · com.example.app") — 같은 식별값이라도 웹/앱이 구별된다(B3 리뷰 MINOR-1).
 * 같은 종류·같은 식별값의 Site가 여럿이면(예: 해지 뒤 같은 도메인으로 새 Site) 상태와 응답 순서로 구별한다 — "웹 · shop.example.test (해지 · 1번째)".
 * 순서는 MASTER 상세 응답 순서(ULID ID 오름차순 ≈ 생성순)이며 화면은 다시 정렬하지 않는다. Site에는 효력 기간이 없다.
 */
export function siteLabel(detail: Pick<MerchantDetail, 'sites'>, siteId: string): string {
  const site = detail.sites.find((s) => s.merchantSiteId === siteId)
  if (site === undefined) return UNKNOWN_SITE
  return siteLabelOf(detail.sites, site)
}

export function siteLabelOf(sites: readonly Site[], site: Site): string {
  return `${labelOf(site.channelType)} · ${siteIdentifierOf(sites, site)}`
}

/** 채널 종류를 따로 보이는 칸(Site 목록)용 — 식별값과, 같은 종류·식별값이 여럿일 때만 붙는 "(상태 · n번째)". */
export function siteIdentifierOf(sites: readonly Site[], site: Site): string {
  const same = sites.filter((s) => s.channelType === site.channelType && s.channelIdentifier === site.channelIdentifier)
  if (same.length <= 1) return site.channelIdentifier
  return `${site.channelIdentifier} (${labelOf(site.lifecycle)} · ${same.indexOf(site) + 1}번째)`
}

/** 계약 이름 = "계약 n"(상세 응답의 계약 순서, 1부터). 사람용 계약 코드(SITE-CONTRACT-CODE-001)가 생기면 그것으로 바꾼다. */
export function contractLabel(detail: Pick<MerchantDetail, 'contracts'>, contractId: string): string {
  const index = detail.contracts.findIndex((c) => c.contractId === contractId)
  return index < 0 ? UNKNOWN_CONTRACT : `계약 ${index + 1}`
}

/** 서비스 약정(CSA) 이름 = "서비스: <서비스 종류>". */
export function agreementLabel(detail: Pick<MerchantDetail, 'contracts'>, agreementId: string): string {
  for (const contract of detail.contracts) {
    const agreement = contract.agreements.find((a) => a.agreementId === agreementId)
    if (agreement) return `서비스: ${labelOf(agreement.serviceKind)}`
  }
  return '서비스: 알 수 없음'
}

/** 한도 범위 = MID 범위면 "MID <mid>", 서비스 약정 범위면 "서비스: <종류>", 그 밖에는 범위 종류 이름만. */
export function limitScopeLabel(detail: Pick<MerchantDetail, 'contracts'>, limit: MerchantDetail['limits'][number]): string {
  if (limit.merchantAccountId !== null) return `MID ${limit.merchantAccountId}`
  if (limit.agreementId !== null) return agreementLabel(detail, limit.agreementId)
  return labelOf(limit.scopeKind)
}

/** Revision 등록 결과 참조(`<계약 키>#<번호>`)에서 사람용 "Revision n"만. 모양이 다르면 키를 보이지 않고 "Revision". */
export function revisionReferenceLabel(reference: string): string {
  const match = /#([0-9]+)$/.exec(reference)
  return match ? `Revision ${match[1]}` : 'Revision'
}

/**
 * 변경 이력 대상(가맹점·사업자 말고)의 사람용 값 — 종류 이름 옆에 붙는다. 상세 응답에서 찾을 수 없으면 빈 문자열(종류 이름만 보인다).
 * MID는 외부 식별자라 그대로, 그 밖에는 MID·Site 채널·결제수단·통화·인수사 코드·한도 범위 같은 사람이 읽는 값.
 */
export function historyTargetValue(aggregateType: string, aggregateId: string, detail: MerchantDetail): string {
  const accounts = detail.accounts
  switch (aggregateType) {
    case 'MerchantSite':
      return detail.sites.some((s) => s.merchantSiteId === aggregateId) ? siteLabel(detail, aggregateId) : ''
    case 'Contract':
      return detail.contracts.some((c) => c.contractId === aggregateId) ? contractLabel(detail, aggregateId) : ''
    case 'ContractServiceAgreement':
      return detail.contracts.some((c) => c.agreements.some((a) => a.agreementId === aggregateId)) ? agreementLabel(detail, aggregateId) : ''
    case 'MerchantAccount':
      return accounts.some((a) => a.merchantAccountId === aggregateId) ? aggregateId : ''
    case 'SiteAssignment': {
      for (const account of accounts) {
        const found = account.assignments.find((x) => x.assignmentId === aggregateId)
        if (found) return `${account.merchantAccountId} · ${siteLabel(detail, found.merchantSiteId)}`
      }
      return ''
    }
    case 'PaymentMethodActivation': {
      for (const account of accounts) {
        const found = account.paymentMethods.find((x) => x.activationId === aggregateId)
        if (found) return `${account.merchantAccountId} · ${labelOf(found.paymentMethod)}`
      }
      return ''
    }
    case 'SiteActivationConfirmation': {
      for (const account of accounts) {
        const found = (account.siteConfirmations ?? []).find((x) => x.confirmationId === aggregateId)
        if (found) return `${siteLabel(detail, found.merchantSiteId)} · ${labelOf(found.paymentMethod)}`
      }
      return ''
    }
    case 'CurrencyEnablement': {
      for (const account of accounts) {
        const found = account.currencies.find((x) => x.enablementId === aggregateId)
        if (found) return `${account.merchantAccountId} · ${found.currencyCode}`
      }
      return ''
    }
    case 'CardRouteActivation': {
      for (const account of accounts) {
        const found = account.cardRoutes.find((x) => x.activationId === aggregateId)
        if (found) return `${account.merchantAccountId} · 인수사 ${found.acquirerCode}`
      }
      return ''
    }
    case 'TransactionLimitPolicy': {
      const found = detail.limits.find((x) => x.policyId === aggregateId)
      return found ? `${limitScopeLabel(detail, found)} · ${found.currencyCode}` : ''
    }
    default:
      // PolicyAssignment 등 상세 응답에 없는 대상 — 종류 이름만.
      return ''
  }
}
