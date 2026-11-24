import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { DetailTabs } from './DetailTabs'

/**
 * ADMIN-DS-001 §5 "상세 탭" 정본(MERCHANT-IA-001 B4) — 접근성·키보드·hidden 패널(언마운트 없음)·모양 class(사업자 상세 탭의 기존 모양 그대로)·style 속성 0.
 */
/** 마운트될 때마다 onMount를 부르는 입력(같은 컴포넌트 타입 — Harness 밖에 둔다). */
function Probe({ onMount }: { onMount: () => void }) {
  useState(() => { onMount(); return 0 })
  return <input aria-label="입력" defaultValue="" />
}

function Harness({ onMount }: { onMount: () => void }) {
  const [tab, setTab] = useState<'a' | 'b' | 'c'>('a')
  return (
    <DetailTabs<'a' | 'b' | 'c'> label="예시 보기" selected={tab} onSelect={setTab} tabs={[
      { key: 'a', label: '첫째', panel: <Probe onMount={onMount} /> },
      { key: 'b', label: '둘째', alert: '결과 확인 필요', panel: <p>둘째 내용</p> },
      { key: 'c', label: '셋째', panel: <p>셋째 내용</p> },
    ]} />
  )
}

describe('DetailTabs', () => {
  it('tablist / tab(aria-selected·aria-controls·roving tabIndex) / tabpanel(aria-labelledby, hidden); 모양 class는 사업자 상세 탭과 같다; style 0', () => {
    const { container } = render(<Harness onMount={vi.fn()} />)
    const list = screen.getByRole('tablist', { name: '예시 보기' })
    expect(list).toHaveClass('flex', 'gap-2', 'border-b')
    const [a, b, c] = screen.getAllByRole('tab')
    expect(a).toHaveAttribute('aria-selected', 'true')
    expect(a).toHaveClass('border-b-2', 'border-primary', 'px-3', 'py-2', 'text-sm', 'font-semibold')
    expect(b).toHaveClass('px-3', 'py-2', 'text-sm', 'text-muted-foreground')
    expect([a!.tabIndex, b!.tabIndex, c!.tabIndex]).toEqual([0, -1, -1])
    for (const tab of [a!, b!, c!]) {
      const panel = document.getElementById(tab.getAttribute('aria-controls')!)!
      expect(panel).toHaveAttribute('role', 'tabpanel')
      expect(panel).toHaveAttribute('aria-labelledby', tab.id)
      expect(panel.hidden).toBe(tab !== a)
    }
    // 알림: 접근 이름은 그대로, 설명으로 읽힌다.
    expect(screen.getByRole('tab', { name: '둘째' })).toHaveAccessibleDescription('· 결과 확인 필요')
    expect(container.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('화살표·Home·End로 옮기며 바로 고른다; 탭을 바꿔도 패널은 언마운트되지 않아 입력이 남는다', async () => {
    const onMount = vi.fn()
    render(<Harness onMount={onMount} />)
    await userEvent.type(screen.getByLabelText('입력'), '남는 값')
    screen.getByRole('tab', { name: '첫째' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '둘째' })).toHaveFocus()
    expect(screen.getByRole('tabpanel', { name: '둘째' })).toHaveTextContent('둘째 내용')
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: '셋째' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '첫째' })).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}{Home}')
    expect(screen.getByRole('tab', { name: '첫째' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('입력')).toHaveValue('남는 값')
    expect(onMount).toHaveBeenCalledTimes(1)
  })
})
