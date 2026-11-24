import type { AdminApi } from '../api/adminApi'
import { useDemoQuickLoginAccounts } from '../lib/useDemoQuickLoginAccounts'

/**
 * 로그인 뒤 계정 영역의 데모 간편 로그인 표시(ADMIN-DEMO-LOGIN-001 §7): 서버에서 켜져 있으면 "데모 간편 로그인 켜짐"과 "계정 바꾸기"(= 로그아웃 → 로그인 화면의 데모 계정 버튼).
 * 꺼져 있으면(404·오류·빈 목록·메서드 없음) 아무것도 그리지 않는다 — 기존 계정 영역 그대로. 2인 승인 흐름(요청자 ≠ 승인자)을 혼자 시험할 때 쓴다.
 */
export function DemoQuickLoginStatus({ api, onSwitch }: { api: AdminApi; onSwitch: () => void }) {
  const on = useDemoQuickLoginAccounts(api).length > 0

  if (!on) return null
  return (
    <>
      <small role="status" aria-label="데모 간편 로그인 켜짐">데모 간편 로그인 켜짐</small>
      <button type="button" className="btn" onClick={onSwitch}>
        계정 바꾸기
      </button>
    </>
  )
}
