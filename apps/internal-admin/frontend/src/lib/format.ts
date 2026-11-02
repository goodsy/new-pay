/** 표시 형식(ADMIN-UI-001 — 표시만, 서버로 보내는 값은 바꾸지 않는다). */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000 // 한국 표준시(일광 절약 없음)

/** ISO 시각 → KST `YYYY-MM-DD HH:mm:ss`. 해석할 수 없으면 원문 그대로. */
export function formatKst(iso: string): string {
  const time = Date.parse(iso)
  if (Number.isNaN(time)) return iso
  const kst = new Date(time + KST_OFFSET_MS)
  const two = (n: number) => String(n).padStart(2, '0')
  return `${kst.getUTCFullYear()}-${two(kst.getUTCMonth() + 1)}-${two(kst.getUTCDate())} ${two(kst.getUTCHours())}:${two(kst.getUTCMinutes())}:${two(kst.getUTCSeconds())}`
}

/**
 * 최소 단위 금액 → 천 단위 구분. KRW(소수 자리 없음)만 `원`, 그 밖 통화는 최소 단위 숫자 + 통화 code,
 * 통화를 모르면(null·undefined) `(통화 미상)` — 원으로 짐작해 보이지 않는다(리뷰 m-2).
 */
export function formatMinor(minor: number, currency: string | null | undefined): string {
  // Intl 객체를 매번 만들지 않는다(입력할 때마다 다시 그려져 비용이 크다) — 정수 천 단위 구분만.
  const digits = String(minor).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  if (currency === null || currency === undefined) return `${digits}(통화 미상)`
  if (currency === 'KRW') return `${digits}원`
  return `${digits} ${currency}(최소 단위)`
}
