import { useSyncExternalStore } from 'react'
import type { MerchantDetail } from '../api/adminApi'
import type { ScreenMemory } from './screenMemory'
import { isLocked, pendingSubmissionOf, submissionStatusOf, type PendingSubmission } from './useUnknownLockedSubmission'

/**
 * 가맹점 상세 안 결과 불명 잠금(보내는 중 포함)의 위치(MERCHANT-IA-001 B4 — 명세 §7 위험과 대응 "잠긴 폼이 보이지 않는 탭에 숨지 않게").
 * 읽기만 한다 — 잠금·submissionId·재전송 규칙은 각 폼(useUnknownLockedSubmission·RegistrationStep)이 그대로 가진다. 잠금을 자동으로 풀지 않는다. 메모리 키는 그 폼들의 키와 같다:
 * - 새 Revision 등록: `revision.<계약 키>.step` → Site·계약 탭
 * - Site 심사 거절: `activation.siteReject.<Site 키>` → Site·계약 탭
 * - 결제수단 개통 등록: `activation.<MID>.register` → MID·개통 탭(어느 MID인지도)
 * 이 가맹점 상세에 있는 계약·Site·MID 키만 본다(App 메모리는 다른 가맹점 잠금도 담는다).
 *
 * B4 리뷰 MINOR-1: 잠금은 있는데 지금 화면에 그 폼(같은 요청 다시 보내기 버튼)이 없을 수 있다 — 예: 다시 읽은 상세에서 Site가 심사 대상이 아니게 됐거나,
 * 개통 등록이 이미 생겨 등록 버튼이 사라졌거나, 이 역할에게 그 버튼이 보이지 않는다. 그런 잠금은 [resendable] = false로 따로 알린다(안내: 상세를 다시 읽어 확인).
 * 폼이 보이는 조건은 각 폼의 표시 조건을 그대로 따른다(새 판정 아님).
 */
export interface MerchantDetailLock {
  tab: 'siteContract' | 'mid'
  /** MID 범위 잠금이면 그 MID. */
  mid?: string
  /** 지금 화면에 같은 요청을 다시 보낼 폼이 있는가. */
  resendable: boolean
}

export interface MerchantDetailLocks {
  siteContract: boolean
  /** 결과 불명 잠금이 있는 MID(상세 응답 순서). */
  mids: string[]
  list: MerchantDetailLock[]
}

/** 폼 표시 권한(화면 표시만 — 판정은 서버). */
export interface LockFormAccess {
  /** 새 Revision 등록 폼(OPERATOR). */
  canRegisterRevision: boolean
  /** 개통 영역·Site 심사의 1인 명령 버튼(OPERATOR). */
  canOperate: boolean
}

const NONE: MerchantDetailLocks = Object.freeze({ siteContract: false, mids: [], list: [] }) as unknown as MerchantDetailLocks
type RegisterPending = PendingSubmission & { payload: { paymentMethod: string } }

export function merchantDetailLocks(memory: ScreenMemory, detail: MerchantDetail, access: LockFormAccess): MerchantDetailLocks {
  const list: MerchantDetailLock[] = []
  for (const c of detail.contracts) {
    if (isLocked(submissionStatusOf(memory, `revision.${c.contractId}.step`))) list.push({ tab: 'siteContract', resendable: access.canRegisterRevision })
  }
  for (const s of detail.sites) {
    if (!isLocked(submissionStatusOf(memory, `activation.siteReject.${s.merchantSiteId}`))) continue
    // SiteReviewTable의 거절 폼 표시 조건 그대로: ACTIVE이고 심사 결정 없음·정지, OPERATOR, MID가 하나 이상(Site 심사 표 표시 조건).
    const actionable = s.lifecycle === 'ACTIVE' && (s.review === null || s.review.decision === 'SUSPENDED')
    list.push({ tab: 'siteContract', resendable: access.canOperate && actionable && detail.accounts.length > 0 })
  }
  for (const a of detail.accounts) {
    const key = `activation.${a.merchantAccountId}.register`
    if (!isLocked(submissionStatusOf(memory, key))) continue
    // MerchantActivationArea의 개통 등록 버튼 표시 조건 그대로: OPERATOR, MID 활성, 보낸 수단에 끝나지 않은 개통이 없음.
    const method = pendingSubmissionOf<RegisterPending>(memory, key)?.payload.paymentMethod
    const open = method !== undefined && !a.paymentMethods.some((m) => m.paymentMethod === method && m.status !== 'TERMINATED')
    list.push({ tab: 'mid', mid: a.merchantAccountId, resendable: access.canOperate && a.lifecycle === 'ACTIVE' && open })
  }
  if (list.length === 0) return NONE
  return { siteContract: list.some((l) => l.tab === 'siteContract'), mids: list.flatMap((l) => (l.mid ? [l.mid] : [])), list }
}

/** [merchantDetailLocks]를 메모리 변화에 맞춰 다시 읽는다(같은 내용이면 같은 문자열 → 다시 그리지 않음). */
export function useMerchantDetailLocks(memory: ScreenMemory, detail: MerchantDetail | null, access: LockFormAccess): MerchantDetailLocks {
  const signature = useSyncExternalStore(memory.subscribe, () => JSON.stringify(detail === null ? NONE : merchantDetailLocks(memory, detail, access)))
  return JSON.parse(signature) as MerchantDetailLocks
}
