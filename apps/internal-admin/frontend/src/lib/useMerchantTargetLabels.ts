import { useEffect, useState } from 'react'
import type { AdminApi } from '../api/adminApi'
import { loadMerchantTargetLabels, type MerchantTargetLabel, type MerchantTargetLabels, type TargetFields } from './merchantTarget'

const EMPTY: MerchantTargetLabels = new Map<string, MerchantTargetLabel>()

/**
 * 화면의 행 목록이 바뀔 때마다(목록을 다시 읽을 때마다 — 코드 변경 직후 옛 코드가 남지 않게) 가맹점 대상 라벨을 한 번 묶어 부른다(MERCHANT-CODE-001 §5.6.3, 캐시 없음).
 * 그 행 목록의 결과가 오기 전에는 빈 상태(= 불러오는 중 "—"). 늦게 온 이전 목록의 결과는 버린다. 가맹점 키가 0개면 부르지 않는다.
 */
export function useMerchantTargetLabels(api: Pick<AdminApi, 'merchantLabels'>, rows: readonly TargetFields[]): MerchantTargetLabels {
  const [state, setState] = useState<{ rows: readonly TargetFields[]; labels: MerchantTargetLabels } | null>(null)
  useEffect(() => {
    let live = true
    void loadMerchantTargetLabels(api, rows.map((row) => row.targetMerchantId)).then((labels) => {
      if (live) setState({ rows, labels })
    })
    return () => {
      live = false
    }
  }, [api, rows])
  return state !== null && state.rows === rows ? state.labels : EMPTY
}
