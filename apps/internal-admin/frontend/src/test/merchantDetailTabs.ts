import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/**
 * 가맹점 상세 탭 열기(MERCHANT-IA-001 B4 — D-ADMIN-UI-010 ① 탭 6개). 탭 패널은 hidden으로 숨으므로 접근성 쿼리(*ByRole)는 연 탭 안만 찾는다.
 * 기존 시험은 이 단계만 더하고 단언의 의미(무엇이 보이고 무엇을 보내는가)는 바꾸지 않는다(명세 §7 위험과 대응).
 */
export type MerchantTabName = '개요' | 'Site·계약' | 'MID·개통' | '한도' | '변경 이력' | '기술 정보'

export async function openMerchantTab(name: MerchantTabName) {
  const tab = await screen.findByRole('tab', { name })
  if (tab.getAttribute('aria-selected') !== 'true') await userEvent.click(tab)
}
