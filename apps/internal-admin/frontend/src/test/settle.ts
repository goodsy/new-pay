import { act } from '@testing-library/react'

/**
 * 시험 대역(fakeApi)이 이미 끝난 Promise(또는 시험이 직접 푼 Promise)로 답할 때, 그 응답과 이어지는 React 갱신이 DOM에 반영될 때까지 기다린다(FLAKY-FIX-001).
 * 결정성의 실제 근거는 React `act`의 종료 처리다 — async act는 끝날 때 React 작업 queue가 빌 때까지 flush를 반복한다(벽시계가 아니라 macrotask 순서). 안의 microtask 20회는
 * React 작업을 거치지 않는 짧은 Promise 사슬을 위한 보조일 뿐이다. `findBy*`/`waitFor`의 벽시계 대기(기본 1초 동안 50ms 간격·DOM 변경마다 질의 반복)와 달리 CPU 부하와 무관하게
 * 같은 순서로 끝난다. 화면 코드가 타이머(setTimeout 등)를 쓰는 경우에는 쓰지 않는다 — 타이머는 진행시키지 않는다. 기대값·단언은 바꾸지 않고 뒤따르는 동기 `getBy*`가 그대로 확인한다.
 * 부재 단언(`queryBy…`.toBeNull, `not.toHaveBeenCalled` 등) 앞에서는 존재 단언(`getBy…`)으로 응답 반영을 먼저 확인할 것 — 반영 전이라 비어 있어 통과하는 헛통과를 막는다.
 */
export async function settle(turns = 20): Promise<void> {
  await act(async () => {
    for (let i = 0; i < turns; i += 1) await Promise.resolve()
  })
}
