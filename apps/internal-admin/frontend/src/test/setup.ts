import '@testing-library/jest-dom/vitest'
import { createElement } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll } from 'vitest'

/**
 * FLAKY-FIX-001: 시험 파일마다 처음 한 번 드는 환경 초기화(jsdom 기본 stylesheet 해석·getComputedStyle, 접근 이름 계산, user-event 문서 준비, React 첫 render)를
 * 파일의 첫 시험 대신 여기서 치른다. 측정(맥, 부하 없음): 같은 시험 본문이 파일 첫 시험이면 약 130ms, 두 번째부터 약 60ms — 게이트 부하에서 이 차이가 몇십 배로 늘어
 * 첫 시험만 5초 제한·findBy 1초 대기를 넘었다. 시험 timeout·기대값은 그대로이고, 이 준비는 DOM에 아무것도 남기지 않는다(끝에 cleanup).
 */
beforeAll(async () => {
  if (typeof document === 'undefined') return // `@vitest-environment node` 순수 함수 시험 — DOM이 없으니 준비할 것도 없다.
  getComputedStyle(document.documentElement)
  render(
    createElement('div', null,
      createElement('table', { 'aria-label': 'warm-up' }, createElement('tbody', null, createElement('tr', null,
        createElement('td', null, 'warm-up'), createElement('td', null, createElement('button', { type: 'button' }, 'warm-up'))))),
      createElement('label', null, 'warm-up', createElement('input', { type: 'text' }))),
  )
  screen.getByRole('row', { name: 'warm-up warm-up' })
  const user = userEvent.setup({ delay: null })
  await user.click(screen.getByRole('button', { name: 'warm-up' }))
  await user.type(screen.getByRole('textbox', { name: 'warm-up' }), 'w')
  cleanup()
})

afterEach(() => {
  cleanup()
})
