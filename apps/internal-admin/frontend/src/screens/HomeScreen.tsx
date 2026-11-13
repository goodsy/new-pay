import { useEffect, useState } from 'react'
import type { AdminApi, Me } from '../api/adminApi'
import { ArrowRightIcon, ChevronRightIcon } from '../components/icons'
import { WorkspaceHeader } from '../components/WorkspaceHeader'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { pagesOf, type Menu, type Page, type View } from '../navigation'

/**
 * 서버 승인 목록 조회 상한(`ApprovalQueryService.LIMIT` = 200, 총계·잘림 표시 없음). 목록이 이만큼이면 정확한 수가 아니라 "이상"으로 보인다.
 */
const APPROVAL_LIST_LIMIT = 200

type Metric = 'pendingApprovals' | 'myPending' | 'openFindings'

/**
 * 현황 카드 정의 — 각 카드는 그 화면이 이미 쓰는 조회만 부른다(새 API 없음). 화면이 보이지 않는 역할에는 카드도, 호출도 없다.
 * 승인 대기 = 승인함(`approvals('pending')`), 내 요청 대기 = 내 요청(`approvals('mine')` 중 PENDING), 열린 대사 발견 = 대사 발견(`reconciliation().openTotal`).
 */
const METRICS: { id: Metric; page: Page; title: string; todo: string; todoHint: string }[] = [
  { id: 'pendingApprovals', page: 'approvals', title: '승인 대기', todo: '승인을 기다리는 요청이 있어요', todoHint: '승인함에서 검토하고 인증 코드로 승인·거절하세요.' },
  { id: 'myPending', page: 'mine', title: '내 요청 대기', todo: '내 요청이 승인을 기다리고 있어요', todoHint: '내 요청에서 진행 상태를 확인하세요.' },
  { id: 'openFindings', page: 'reconciliation', title: '열린 대사 발견', todo: '맞지 않는 대사 발견이 있어요', todoHint: '자동으로 고치거나 닫지 않아요. 대사 발견에서 조사하세요.' },
]

type Count = { state: 'loading' } | { state: 'ok'; value: number; atLeast: boolean } | { state: 'error'; code: ConsoleErrorCode }

/**
 * 운영 홈(rev 0.4, D-ADMIN-UI-003): 인사 머리 + 실제 서버 값으로만 만든 현황 카드·지금 처리할 업무 + 이 역할이 쓸 수 있는 메뉴 바로가기.
 * 표본·가짜 숫자·차트 없음. 볼 수 있는 숫자가 없는 역할(VIEWER·UNMASK)은 인사와 바로가기만.
 */
export function HomeScreen({ api, me, menus, onOpen, onSessionEnded }: {
  api: AdminApi
  me: Me
  menus: Menu[]
  onOpen: (view: View) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
}) {
  const visible = new Set(menus.flatMap((m) => m.tabs.map((t) => t.page)))
  const metrics = METRICS.filter((m) => visible.has(m.page))
  const metricKey = metrics.map((m) => m.id).join(',')
  const [counts, setCounts] = useState<Partial<Record<Metric, Count>>>({})

  useEffect(() => {
    let alive = true
    const ids = metricKey ? (metricKey.split(',') as Metric[]) : []
    setCounts(Object.fromEntries(ids.map((id) => [id, { state: 'loading' }])))
    const load = async (id: Metric): Promise<{ value: number; atLeast: boolean }> => {
      if (id === 'openFindings') return { value: (await api.reconciliation()).openTotal, atLeast: false }
      const list = (await api.approvals(id === 'pendingApprovals' ? 'pending' : 'mine')).approvals
      return { value: list.filter((a) => a.status === 'PENDING').length, atLeast: list.length >= APPROVAL_LIST_LIMIT }
    }
    for (const id of ids) {
      load(id).then(
        (result) => alive && setCounts((c) => ({ ...c, [id]: { state: 'ok', ...result } })),
        (e: unknown) => {
          if (!alive) return
          const code = codeOf(e)
          if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
          else setCounts((c) => ({ ...c, [id]: { state: 'error', code } }))
        },
      )
    }
    return () => {
      alive = false
    }
  }, [api, metricKey, onSessionEnded])

  const todos = metrics.flatMap((m) => {
    const count = counts[m.id]
    return count?.state === 'ok' && count.value > 0 ? [{ metric: m, count }] : []
  })
  const loaded = metrics.every((m) => counts[m.id] && counts[m.id]?.state !== 'loading')
  const failed = metrics.some((m) => counts[m.id]?.state === 'error')
  // 메뉴가 여는 모든 화면(탭 + 쪽 머리 주요 버튼 — pagesOf)을 바로가기에 둔다. ADMIN-UI-MERCHANT-002부터 사업자 등록·가맹점 등록(OPERATOR)은 탭이다.
  const shortcuts = menus.flatMap((m) => pagesOf(m).map((t) => ({ menu: m.label, tab: t })))
  const amount = (value: number, atLeast: boolean) => `${value}건${atLeast ? ' 이상' : ''}`

  return (
    <>
      <WorkspaceHeader eyebrow="OVERVIEW" title={`안녕하세요, ${me.displayName}님`} description="지금 처리할 업무와 내가 쓸 수 있는 메뉴를 한눈에 확인하세요." />
      {metrics.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 sm:gap-4 xl:grid-cols-4">
            {metrics.map((m) => {
              const count = counts[m.id]
              return (
                <article key={m.id} aria-label={m.title} className="flex flex-col rounded-xl border bg-card p-4 text-card-foreground md:p-[21px]">
                  <span className="text-xs text-muted-foreground">{m.title}</span>
                  {count?.state === 'ok' ? (
                    <strong className="my-3 text-[29px] leading-none font-bold tracking-[-1px] text-heading">
                      {count.value}
                      <small className="ml-1 text-sm font-normal text-muted-foreground">{`건${count.atLeast ? ' 이상' : ''}`}</small>
                    </strong>
                  ) : count?.state === 'error' ? (
                    <p className="my-3 text-xs text-destructive">{ERROR_MESSAGES[count.code]}</p>
                  ) : (
                    <span className="my-3 text-sm text-muted-foreground">불러오는 중…</span>
                  )}
                </article>
              )
            })}
          </div>
          <section aria-labelledby="home-todo" className="overflow-hidden rounded-[13px] border bg-card text-card-foreground">
            <div className="flex items-center gap-2 px-[23px] pt-[22px] pb-[17px]">
              <h2 id="home-todo" className="text-[17px] font-bold text-heading">지금 처리할 업무</h2>
              {todos.length > 0 && <span className="rounded bg-sidebar-accent px-1.5 py-[3px] text-xs font-semibold text-sidebar-accent-foreground">{todos.length}</span>}
            </div>
            {todos.length > 0 ? (
              <ul className="flex flex-col">
                {todos.map(({ metric, count }) => (
                  <li key={metric.id} className="border-t">
                    <button type="button" onClick={() => onOpen(metric.page)} className="flex w-full items-center gap-4 px-[23px] py-4 text-left outline-none hover:bg-muted/60 focus-visible:bg-muted/60">
                      <span className="flex min-w-0 flex-1 flex-col gap-1">
                        <span className="text-[13px] font-bold">{metric.todo}</span>
                        <span className="text-[11px] text-muted-foreground">{metric.todoHint}</span>
                      </span>
                      <span className="rounded bg-sidebar-accent px-[7px] py-[5px] text-xs font-semibold text-sidebar-accent-foreground">{amount(count.value, count.atLeast)}</span>
                      <ChevronRightIcon aria-hidden="true" className="size-4 text-muted-foreground" />
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="border-t px-[23px] py-5 text-xs text-muted-foreground">{!loaded ? '불러오는 중…' : failed ? '불러오지 못한 현황이 있어요. 위 카드를 확인하세요.' : '지금 처리할 업무가 없어요.'}</p>
            )}
          </section>
        </>
      )}
      <section aria-labelledby="home-shortcuts" className="flex flex-col gap-3">
        <h2 id="home-shortcuts" className="text-[17px] font-bold text-heading">바로가기</h2>
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 sm:gap-4 xl:grid-cols-4">
          {shortcuts.map(({ menu, tab }) => (
            <button
              key={tab.page}
              type="button"
              aria-label={`${tab.label} 열기`}
              onClick={() => onOpen(tab.page)}
              className="flex items-center justify-between gap-3 rounded-xl border bg-card px-4 py-4 text-left text-card-foreground outline-none hover:border-primary/40 focus-visible:ring-[3px] focus-visible:ring-ring/50"
            >
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-xs font-medium">{tab.label}</span>
                <span className="text-[11px] text-muted-foreground">{menu}</span>
              </span>
              <ArrowRightIcon aria-hidden="true" className="size-3.5 text-muted-foreground" />
            </button>
          ))}
        </div>
      </section>
    </>
  )
}
