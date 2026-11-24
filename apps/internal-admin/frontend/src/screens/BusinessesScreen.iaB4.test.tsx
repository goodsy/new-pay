import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen } from './BusinessesScreen'
import { createScreenMemory } from '../lib/screenMemory'
import { fakeApi } from '../test/fakeApi'
import { HISTORY, individualRegisteredDetail, listItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-IA-001 B4: 사업자 상세의 기존 탭(기본정보 / 변경 이력)을 공용 DetailTabs로 바꿨다 — 모양 class 그대로, tabpanel·aria-controls·키보드가 생겼고,
 * 이력은 그 탭을 처음 고를 때 읽는다(기존 동작 그대로). 시험 값은 명시 Demo fixture.
 */
const REG = individualRegisteredDetail(1)

describe('BusinessesScreen 상세 탭 — DetailTabs(B4)', () => {
  it('같은 모양의 탭 두 개, 화살표로 변경 이력 → 그때 이력을 한 번 읽는다; 기본정보 패널은 hidden으로 남는다', async () => {
    const businessHistory = vi.fn(async () => ({ items: HISTORY, nextAfter: null }))
    const api = fakeApi({ searchBusinesses: vi.fn(async () => ({ items: [listItemOf(REG)], next: null })), businessDetail: vi.fn(async () => REG), businessHistory })
    render(<BusinessesScreen api={api} memory={createScreenMemory()} canEdit onContinue={vi.fn()} handoffState={() => 'empty'} />)
    await userEvent.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    const info = await screen.findByRole('tab', { name: '기본정보' })
    const history = screen.getByRole('tab', { name: '변경 이력' })
    // 기대값 변경(MERCHANT-IA-001 B5 — D-ADMIN-UI-010 ⑧ '연결 가맹점' 탭 추가, D-ADMIN-UI-011 ② 편집 폼): 탭이 셋(기본정보 / 연결 가맹점 (n) / 변경 이력) — 변경 이력은 End로 간다.
    expect(screen.getByRole('tablist', { name: '사업자 상세 보기' })).toHaveClass('flex', 'gap-2', 'border-b')
    expect(info).toHaveClass('border-b-2', 'border-primary', 'px-3', 'py-2', 'text-sm', 'font-semibold')
    expect(history).toHaveClass('px-3', 'py-2', 'text-sm', 'text-muted-foreground')
    expect(businessHistory).not.toHaveBeenCalled()
    info.focus()
    await userEvent.keyboard('{End}')
    expect(history).toHaveAttribute('aria-selected', 'true')
    expect(history).toHaveFocus()
    expect(await screen.findByRole('table', { name: '변경 이력' })).toBeInTheDocument()
    expect(businessHistory).toHaveBeenCalledTimes(1)
    const infoPanel = document.getElementById(info.getAttribute('aria-controls')!)!
    expect(infoPanel.hidden).toBe(true)
    expect(infoPanel.querySelector('section[aria-label="사업자 정보 편집"]')).not.toBeNull()
    await userEvent.keyboard('{Home}')
    expect(infoPanel.hidden).toBe(false)
    expect(businessHistory).toHaveBeenCalledTimes(1)
  })
})
