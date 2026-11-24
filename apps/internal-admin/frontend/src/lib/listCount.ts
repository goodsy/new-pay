/**
 * 목록 아래 건수(B6 — pg-lab '총 n건'). 목록 API는 keyset이라 전체 건수를 주지 않는다 — 다음 쪽이 없을 때만 "총 n건"(다 읽음),
 * 더 있으면 "n건 표시 · 더 있음"(지금까지 읽은 수 — 전체 건수처럼 보이지 않게).
 */
export function listCountText(shown: number, more: boolean): string {
  return more ? `${shown}건 표시 · 더 있음` : `총 ${shown}건`
}
