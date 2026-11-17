import type { AdminApi, TransactionMerchant } from '../api/adminApi'
import { MERCHANT_CODE } from './merchantCode'
import { MERCHANT_LABEL_MISSING, merchantLabelText } from './merchantLabel'

/**
 * 승인·감사 행의 대상 표시(MERCHANT-CODE-001 §5.6.2 — D-ADMIN-UI-004 ③·D-MCODE-003, 모든 행 — 새·기존 공통, fail-closed). 순수 함수만.
 * - 가맹점 대상(키 = `targetMerchantId`): 라벨 있음 "<현재 상호> · <현재 코드>"(`merchantLabelText` 재사용), OK인데 키 없음 [MERCHANT_LABEL_MISSING], UNAVAILABLE·429·실패·로딩 [TARGET_HIDDEN].
 * - 사업자 대상(`biz_`): [BUSINESS_TARGET](종류 단어만 — C-33). 그 밖(`adm_`·`pay_`·`ctr_`·ACQ 등): 원문.
 * - 위 분류 밖인데 `mer_`·`biz_`가 들어 있으면 [TARGET_HIDDEN](방어). **어떤 분기도 내부 키를 결과에 넣지 않는다**(마지막 검사로 한 번 더 막는다).
 * 응답의 `merchantId`·`targetMerchantId`는 라벨 대응에만 쓰고 텍스트·속성에 그리지 않는다(D-BIZLOOKUP-001 — 응답 포함·화면 미표시).
 */
export const TARGET_HIDDEN = '—'
export const BUSINESS_TARGET = '사업자'
/** 한 번의 라벨 호출 상한(§5.6.3 — 서버 1..50). */
export const LABEL_BATCH = 50

export type MerchantTargetLabel = { state: 'found'; label: TransactionMerchant } | { state: 'missing' } | { state: 'unavailable' }
/** 키 → 라벨 상태. 키가 없으면 불러오는 중. */
export type MerchantTargetLabels = ReadonlyMap<string, MerchantTargetLabel>

export interface TargetFields {
  targetActorId?: string | null
  targetRef?: string | null
  targetMerchantId?: string | null
}

const INTERNAL_TOKEN = /(mer|biz)_[0-9A-HJKMNP-TV-Z]{26}/g

export function hasInternalKey(text: string): boolean {
  return text.includes('mer_') || text.includes('biz_')
}

const guard = (text: string) => (hasInternalKey(text) ? TARGET_HIDDEN : text)

/** 가맹점 대상의 라벨 상태(가맹점 대상이 아니면 undefined). */
export function merchantTargetLabel(target: TargetFields, labels: MerchantTargetLabels): MerchantTargetLabel | undefined {
  return target.targetMerchantId ? labels.get(target.targetMerchantId) : undefined
}

/** 표 칸·행 접근 이름에 쓰는 대상 표시 텍스트(§5.6.2 표). */
export function merchantTargetText(target: TargetFields, labels: MerchantTargetLabels): string {
  if (target.targetMerchantId) {
    const label = labels.get(target.targetMerchantId)
    if (label?.state === 'found') return guard(merchantLabelText(label.label) ?? TARGET_HIDDEN)
    if (label?.state === 'missing') return MERCHANT_LABEL_MISSING
    return TARGET_HIDDEN
  }
  if (target.targetActorId) return guard(target.targetActorId)
  const ref = target.targetRef ?? ''
  if (ref.startsWith('biz_')) return BUSINESS_TARGET
  return guard(ref)
}

/** 감사 "당시 표기"(§5.6.2·§5.4): target_ref가 코드 형식이고 현재 코드와 다를 때만 `(당시 <코드>)`. `mer_` 행·라벨 없음은 null. */
export function recordedCodeNote(target: TargetFields, labels: MerchantTargetLabels): string | null {
  const label = merchantTargetLabel(target, labels)
  const ref = target.targetRef ?? ''
  if (label?.state !== 'found' || !MERCHANT_CODE.test(ref) || ref === label.label.code) return null
  return `(당시 ${ref})`
}

/**
 * 감사 detail 방어 치환(C-33): `(mer|biz)_<ULID>` 토큰 → "—"(텍스트·`title` 모두 이 값으로).
 * 마지막 방어(reviewFix1 NIT-1): 치환 뒤에도 `mer_`·`biz_`가 남으면(소문자·짧은 토큰 등 패턴 밖) 요약 전체를 "—"로.
 */
export function sanitizeAuditDetail(detail: string): string {
  return guard(detail.replace(INTERNAL_TOKEN, TARGET_HIDDEN))
}

/** 화면 묶음(§5.6.3): 고유 키를 정렬해 50개씩. 0개면 빈 목록(호출하지 않는다). */
export function labelBatches(ids: readonly (string | null | undefined)[]): string[][] {
  const unique = [...new Set(ids.filter((id): id is string => typeof id === 'string' && id !== ''))].sort()
  const batches: string[][] = []
  for (let i = 0; i < unique.length; i += LABEL_BATCH) batches.push(unique.slice(i, i + LABEL_BATCH))
  return batches
}

/**
 * 묶음을 **순차로** 불러 키별 상태를 만든다. 한 묶음이 UNAVAILABLE·429(RATE_LIMITED — C-35)·그 밖 실패이면 그 묶음 키만 unavailable(오류 알림·세션 종료로 보내지 않는다).
 * 응답 대응 검사(서버 §5.6.3과 같은 기준 — 보낸 집합 밖 키·중복이면 그 묶음 전체 unavailable, 틀린 상호가 다른 행에 붙지 않게).
 */
export async function loadMerchantTargetLabels(api: Pick<AdminApi, 'merchantLabels'>, ids: readonly (string | null | undefined)[]): Promise<Map<string, MerchantTargetLabel>> {
  const result = new Map<string, MerchantTargetLabel>()
  for (const batch of labelBatches(ids)) {
    const states = await loadBatch(api, batch)
    for (const id of batch) result.set(id, states.get(id) ?? { state: 'unavailable' })
  }
  return result
}

async function loadBatch(api: Pick<AdminApi, 'merchantLabels'>, batch: string[]): Promise<Map<string, MerchantTargetLabel>> {
  const unavailable = new Map<string, MerchantTargetLabel>(batch.map((id) => [id, { state: 'unavailable' }]))
  try {
    const page = await api.merchantLabels(batch)
    if (page.labels !== 'OK' || !Array.isArray(page.items)) return unavailable
    const sent = new Set(batch)
    const seen = new Set<string>()
    for (const item of page.items) {
      if (!sent.has(item.merchantId) || seen.has(item.merchantId)) return unavailable
      seen.add(item.merchantId)
    }
    const states = new Map<string, MerchantTargetLabel>(batch.map((id) => [id, { state: 'missing' }]))
    for (const item of page.items) states.set(item.merchantId, { state: 'found', label: { name: item.name, code: item.code } })
    return states
  } catch {
    return unavailable
  }
}

/** 표 아래 안내 1줄 여부: 이 행들의 가맹점 키 중 하나라도 unavailable(로딩 중은 아님). */
export function anyLabelUnavailable(rows: readonly TargetFields[], labels: MerchantTargetLabels): boolean {
  return rows.some((row) => merchantTargetLabel(row, labels)?.state === 'unavailable')
}
