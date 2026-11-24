import { useEffect, useState } from 'react'
import type { AdminApi, DemoQuickLoginAccount } from '../api/adminApi'

/**
 * 데모 간편 로그인 계정 목록 조회(ADMIN-DEMO-LOGIN-001 §7 — 로그인 화면 카드와 로그인 뒤 "켜짐" 표시가 함께 쓴다). 서버가 꺼져 있거나(404 = null)·오류·빈 목록·메서드 없음이면
 * 빈 배열. unmount 뒤 응답은 버린다. 켜짐 판정·권한은 서버 — 화면은 이 결과로 표시만 한다.
 */
export function useDemoQuickLoginAccounts(api: AdminApi): DemoQuickLoginAccount[] {
  const [accounts, setAccounts] = useState<DemoQuickLoginAccount[]>([])

  useEffect(() => {
    let alive = true
    const load = api.demoQuickLoginOptions
    if (!load) return
    load.call(api)
      .then((list) => alive && setAccounts(list ?? []))
      .catch(() => alive && setAccounts([]))
    return () => {
      alive = false
    }
  }, [api])

  return accounts
}
