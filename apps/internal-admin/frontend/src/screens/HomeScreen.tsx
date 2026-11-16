import { useEffect, useState } from 'react'
import type { AdminApi, ApprovalView, HomeKpiItem, HomeKpis, Me, MerchantLabelsState, TransactionListItem } from '../api/adminApi'
import { MERCHANT_LABEL_MISSING, MERCHANT_LABELS_UNAVAILABLE, environmentMid, merchantLabelText, paymentMethodLabel } from '../lib/merchantLabel'
import { KstTime } from '../components/DataBits'
import { WorkspaceHeader } from '../components/WorkspaceHeader'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { formatKst, formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'
import { tagClass } from '../lib/statusTone'
import type { Menu, MenuId, Page, View } from '../navigation'

/**
 * 서버 승인 목록 조회 상한(`ApprovalQueryService.LIMIT` = 200, 총계·잘림 표시 없음). 목록이 이만큼이면 정확한 수가 아니라 "이상"으로 보인다.
 */
const APPROVAL_LIST_LIMIT = 200
/** 지금 처리할 업무에 승인 요청을 한 줄씩 보이는 최대 수(나머지는 "외 N건" 한 줄). */
const TASK_ROWS = 5
/** 최근 거래: 오늘 포함 최근 7일(KST, 거래 조회 화면 최대 31일 안), 앞에서 5건. */
const RECENT_DAYS = 7
const RECENT_ROWS = 5
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000

type Metric = 'pendingApprovals' | 'myPending' | 'openFindings'

/**
 * 확인이 필요한 업무의 출처 — 각 출처는 그 화면이 이미 쓰는 조회만 부른다(새 API 없음). 화면이 보이지 않는 역할에는 출처도, 호출도 없다.
 * 승인 대기 = 승인함(`approvals('pending')`), 내 요청 대기 = 내 요청(`approvals('mine')` 중 PENDING), 열린 대사 발견 = 대사 발견(`reconciliation().openTotal`).
 * 리스크·통보는 서버 기능이 없어 넣지 않는다(시안의 "리스크 3 · 통보 2"는 샘플).
 */
const METRICS: { id: Metric; page: Page; short: string }[] = [
  { id: 'pendingApprovals', page: 'approvals', short: '승인' },
  { id: 'myPending', page: 'mine', short: '내 요청' },
  { id: 'openFindings', page: 'reconciliation', short: '대사' },
]

/** 정산 흐름 단계(시안 순서·이름) — 정산·지급 기능이 아직 없어 모든 단계가 "준비 전"이다(시각·건수 없음). */
const SETTLEMENT_STEPS = ['정산 데이터 생성', '지급 데이터 검증', '지급 승인', '송금 및 결과 확인']

/** 바로가기(시안 4개 자리): 이미 있는 메뉴만, 이 역할이 볼 수 있을 때만. 메뉴에 탭이 여럿이면 이 역할의 첫 탭. */
const QUICK: { menu: MenuId; label: string; page?: Page }[] = [
  { menu: 'merchants', label: '가맹점 조회', page: 'merchants' },
  { menu: 'payments', label: '거래 조회', page: 'transactions' },
  { menu: 'recon', label: '매입·대사 확인' },
  { menu: 'accounts', label: '계정·권한 관리' },
]

type Count = { state: 'loading' } | { state: 'ok'; value: number; atLeast: boolean } | { state: 'error'; code: ConsoleErrorCode }
type Kpis = { state: 'loading' } | { state: 'ok'; doc: HomeKpis } | { state: 'error'; code: ConsoleErrorCode }
type Recent = { state: 'loading' } | { state: 'ok'; items: TransactionListItem[]; labels: MerchantLabelsState } | { state: 'error'; code: ConsoleErrorCode }

const two = (n: number) => String(n).padStart(2, '0')
function kstParts(now: Date) {
  const k = new Date(now.getTime() + KST_OFFSET_MS)
  return { y: k.getUTCFullYear(), m: k.getUTCMonth() + 1, d: k.getUTCDate() }
}
/** KST 날짜 d일 전 0시(UTC ISO). */
function kstDayStart(now: Date, daysAgo: number): string {
  const { y, m, d } = kstParts(now)
  return new Date(Date.UTC(y, m - 1, d) - KST_OFFSET_MS - daysAgo * DAY_MS).toISOString()
}

/** KPI 금액 문자열(ADMIN-HOME-KPI-001 §4.1·§4.3 — minor unit 정수, `-0`·앞자리 0·소수점·지수 거부). */
const MINOR_AMOUNT = /^(0|-?[1-9][0-9]{0,39})$/
const KPI_ENVIRONMENTS = ['LIVE', 'TEST'] as const
/** 문서 items 상한(§4.3, L-5). */
const KPI_MAX_ITEMS = 64
/** 정확한 키 집합(§4.1 — 서버 `PaymentResponseShape.HOME_KPI` Obj: 필드 이름 집합이 같아야 한다). */
const KPI_DOC_KEYS = ['asOf', 'businessDate', 'from', 'items', 'timeZone']
const KPI_ITEM_KEYS = ['approvedAmountMinor', 'approvedCount', 'cancelledAmountMinor', 'currency', 'environment', 'netApprovedAmountMinor']
/** 서버 TIME·DATE 정규식과 같다(`Date.parse`는 날짜만·offset·밀리초 없음도 받아 주므로 정규식 먼저). */
const KPI_INSTANT = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/
const KPI_DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
/** Asia/Seoul은 DST가 없어 고정 +09:00으로 날짜·0시를 계산한다. */
const SEOUL_OFFSET_MS = KST_OFFSET_MS

const hasExactKeys = (o: Record<string, unknown>, keys: string[]) => {
  const own = Object.keys(o).sort()
  return own.length === keys.length && own.every((k, i) => k === keys[i])
}
/** 엄격한 ISO 순간 → epoch ms(달력에 없는 날 등은 왕복 비교로 거부). */
const instantMs = (s: unknown): number | null => {
  if (typeof s !== 'string' || !KPI_INSTANT.test(s)) return null
  const ms = Date.parse(s)
  return Number.isNaN(ms) || new Date(ms).toISOString() !== s ? null : ms
}

/**
 * KPI 문서가 §4.3 모양·일관성 규칙을 지키는가(fail-closed — ADMIN 서버가 같은 규칙(`PaymentResponseShape.HOME_KPI`·`HomeKpiAnswer`)으로 이미 503을 낸다;
 * 화면은 어긋난 문서로 숫자를 만들지 않는다). 정확한 키 집합, 엄격한 ISO 순간·날짜, 이 요청의 답(businessDate = asOf의 Asia/Seoul 날짜, from = 그 날 00:00 Asia/Seoul,
 * from ≤ asOf, asOf = from ⇒ 빈 items). 금액 비교는 BigInt(부동소수 없음).
 * 정렬(LIVE → TEST, currency 오름차순)은 검사하지 않는다 — 화면이 환경 순서로 직접 다시 묶으므로 순서가 표시를 바꾸지 않는다(서버는 검사한다).
 */
function isHomeKpis(raw: unknown): raw is HomeKpis {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return false
  const doc = raw as Record<string, unknown>
  if (!hasExactKeys(doc, KPI_DOC_KEYS) || doc.timeZone !== 'Asia/Seoul') return false
  const asOf = instantMs(doc.asOf)
  const from = instantMs(doc.from)
  if (asOf === null || from === null || typeof doc.businessDate !== 'string' || !KPI_DATE.test(doc.businessDate)) return false
  if (new Date(asOf + SEOUL_OFFSET_MS).toISOString().slice(0, 10) !== doc.businessDate) return false
  if (Date.parse(`${doc.businessDate}T00:00:00.000Z`) - SEOUL_OFFSET_MS !== from || from > asOf) return false
  if (!Array.isArray(doc.items) || doc.items.length > KPI_MAX_ITEMS) return false
  if (asOf === from && doc.items.length > 0) return false
  const keys = new Set<string>()
  for (const raw of doc.items as unknown[]) {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return false
    const i = raw as Record<string, unknown>
    if (!hasExactKeys(i, KPI_ITEM_KEYS)) return false
    if (!KPI_ENVIRONMENTS.includes(i.environment as (typeof KPI_ENVIRONMENTS)[number])) return false
    if (typeof i.currency !== 'string' || !/^[A-Z]{3}$/.test(i.currency)) return false
    const amounts = [i.approvedAmountMinor, i.cancelledAmountMinor, i.netApprovedAmountMinor]
    if (!amounts.every((a) => typeof a === 'string' && MINOR_AMOUNT.test(a))) return false
    const [approved, cancelled, net] = (amounts as string[]).map((a) => BigInt(a)) as [bigint, bigint, bigint]
    if (approved < 0n || cancelled < 0n || net !== approved - cancelled || (approved === 0n && cancelled === 0n)) return false
    if (typeof i.approvedCount !== 'number' || !Number.isSafeInteger(i.approvedCount) || i.approvedCount < 0) return false
    if (approved === 0n && i.approvedCount !== 0) return false
    const key = `${i.environment as string}/${i.currency}`
    if (keys.has(key)) return false
    keys.add(key)
  }
  return true
}

/** 검사된 minor unit 정수 문자열에 천 단위 쉼표(문자열 그대로 — 2^53 초과도 자리 정확). */
const groupDigits = (minor: string) => minor.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
/**
 * KPI 금액 단위: KRW(소수 자리 없음)만 `원`. 그 밖 통화는 소수 자리 규칙을 명세가 정하지 않아 최소 단위 숫자 + `통화(최소 단위)`로 보인다
 * (`formatMinor`와 같은 관례 — major unit으로 짐작해 바꾸지 않는다).
 */
const kpiUnit = (currency: string) => (currency === 'KRW' ? '원' : `${currency}(최소 단위)`)
/** 환경별 묶음(C-1 — LIVE 먼저, 환경 간 합산 없음). 사실이 있는 환경만. */
const byEnvironment = (items: HomeKpiItem[]) =>
  KPI_ENVIRONMENTS.flatMap((environment) => {
    const rows = items.filter((i) => i.environment === environment)
    return rows.length > 0 ? [{ environment, rows }] : []
  })
/** 화면 표지(D-ADMIN-HOME-002 ③ — 명세 문구 그대로). */
const KPI_LABEL = 'PG 확정 시각 기준 · 정산 금액 아님'
const NO_FACTS = '오늘 완료된 승인 없음'
/** 승인 요청 행 제목: 종류 이름이 이미 "…승인"이면 "요청"만 붙인다(예: 가맹점 계약 승인 요청 / 역할 부여 승인 요청). */
const approvalTitle = (kind: string) => {
  const label = labelOf(kind)
  return label.endsWith('승인') ? `${label} 요청` : `${label} 승인 요청`
}

/**
 * 운영 홈(ADMIN-UI-WORKSPACE-001 2단계 — 참고 시안 app.js home() 순서·class 그대로, D-ADMIN-HOME-001):
 * 쪽 머리(오늘 날짜·사업자 등록) → 안내 띠(실제 알릴 것이 있을 때만) → KPI 4개 → 2열(지금 처리할 업무 / 오늘의 정산 흐름) → 최근 거래 → 바로가기.
 * 숫자는 서버 값으로만 — 표본·가짜 숫자·비교 % 없음. 오늘 승인 금액·건수는 KPI API(ADMIN-HOME-KPI-001 `GET /api/home-kpis` — 환경·통화 행별, 404·501을 포함한 실패는 오류 표시 — Coordinator Q1),
 * 지급 예정액·정산 흐름은 정산 기능이 생길 때까지 "준비 전". 역할이 볼 수 없는 화면의 숫자는 부르지도 그리지도 않는다(표시만 — 판정은 서버).
 */
export function HomeScreen({ api, me, menus, onOpen, onSessionEnded, now = () => new Date() }: {
  api: AdminApi
  me: Me
  menus: Menu[]
  onOpen: (view: View) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 시험 주입용(오늘 KST 날짜·최근 거래 기간). */
  now?: () => Date
}) {
  const visible = new Set(menus.flatMap((m) => m.tabs.map((t) => t.page)))
  const metrics = METRICS.filter((m) => visible.has(m.page))
  const metricKey = metrics.map((m) => m.id).join(',')
  const canSeePayments = visible.has('transactions')
  const [counts, setCounts] = useState<Partial<Record<Metric, Count>>>({})
  const [pendingList, setPendingList] = useState<ApprovalView[]>([])
  const [kpis, setKpis] = useState<Kpis>({ state: 'loading' })
  const [recent, setRecent] = useState<Recent>({ state: 'loading' })
  const [today] = useState(now)

  useEffect(() => {
    let alive = true
    const fail = (e: unknown): ConsoleErrorCode | null => {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) {
        onSessionEnded(code)
        return null
      }
      return code
    }
    const ids = metricKey ? (metricKey.split(',') as Metric[]) : []
    setCounts(Object.fromEntries(ids.map((id) => [id, { state: 'loading' }])))
    const load = async (id: Metric): Promise<{ value: number; atLeast: boolean }> => {
      if (id === 'openFindings') return { value: (await api.reconciliation()).openTotal, atLeast: false }
      const list = (await api.approvals(id === 'pendingApprovals' ? 'pending' : 'mine')).approvals
      const pending = list.filter((a) => a.status === 'PENDING')
      if (id === 'pendingApprovals' && alive) setPendingList(pending)
      return { value: pending.length, atLeast: list.length >= APPROVAL_LIST_LIMIT }
    }
    for (const id of ids) {
      load(id).then(
        (result) => alive && setCounts((c) => ({ ...c, [id]: { state: 'ok', ...result } })),
        (e: unknown) => {
          if (!alive) return
          const code = fail(e)
          if (code) setCounts((c) => ({ ...c, [id]: { state: 'error', code } }))
        },
      )
    }
    if (canSeePayments) {
      setKpis({ state: 'loading' })
      api.getHomeKpis().then(
        // 모양·일관성이 어긋난 문서는 숫자를 만들지 않고 일반 오류로 보인다(§4.3 fail-closed).
        (doc) => alive && setKpis(isHomeKpis(doc) ? { state: 'ok', doc } : { state: 'error', code: 'SERVER_ERROR' }),
        (e: unknown) => {
          if (!alive) return
          // Coordinator Q1(2026-11-16): KPI 서버가 있으므로 404·501도 고장 — "집계 준비 중"이 아니라 다른 실패와 같은 오류 표시.
          const code = fail(e)
          if (code) setKpis({ state: 'error', code })
        },
      )
      setRecent({ state: 'loading' })
      const at = today
      // TXN-LIST-ENRICH-001 M-6: 표시할 5건만 요청한다(limit — PAYMENT·MASTER 읽기 낭비 방지).
      api.transactionSearch({ createdFrom: kstDayStart(at, RECENT_DAYS - 1), createdTo: kstDayStart(at, -1), limit: RECENT_ROWS }).then(
        (page) => alive && setRecent({ state: 'ok', items: page.items.slice(0, RECENT_ROWS), labels: page.merchantLabels }),
        (e: unknown) => {
          if (!alive) return
          const code = fail(e)
          if (code) setRecent({ state: 'error', code })
        },
      )
    }
    return () => {
      alive = false
    }
  }, [api, metricKey, canSeePayments, onSessionEnded, today])

  const okCount = (id: Metric) => {
    const c = counts[id]
    return c?.state === 'ok' ? c : null
  }
  const loaded = metrics.every((m) => counts[m.id] && counts[m.id]?.state !== 'loading')
  const failed = metrics.some((m) => counts[m.id]?.state === 'error')
  const total = metrics.reduce((sum, m) => sum + (okCount(m.id)?.value ?? 0), 0)
  const totalAtLeast = metrics.some((m) => okCount(m.id)?.atLeast)
  const ops = canSeePayments || metrics.length > 0

  // 지금 처리할 업무 행(실제 항목만): 승인 요청은 한 건씩(종류·요청자 — 대상 내부 키는 그리지 않는다), 내 요청·대사 발견은 묶음 한 줄.
  type TaskRow = { key: string; icon: string; blue: boolean; title: string; sub: string; tag: string; tone: string; page: Page }
  const tasks: TaskRow[] = []
  const pendingApprovals = okCount('pendingApprovals')
  if (pendingApprovals && pendingApprovals.value > 0) {
    for (const a of pendingList.slice(0, TASK_ROWS)) {
      tasks.push({
        key: a.approvalId, icon: '✓', blue: true, title: approvalTitle(a.kind), sub: `${a.requestedByDisplayName ?? '요청자'} · ${formatKst(a.requestedAt).slice(0, 16)} 요청`,
        tag: '승인 대기', tone: 'tag blue', page: 'approvals',
      })
    }
    const rest = pendingApprovals.value - Math.min(pendingList.length, TASK_ROWS)
    if (rest > 0) {
      tasks.push({ key: 'approvals-rest', icon: '✓', blue: true, title: `승인을 기다리는 요청이 ${rest}건 더 있어요${pendingApprovals.atLeast ? '(이상)' : ''}`, sub: '승인함에서 검토하고 인증 코드로 승인·거절하세요.', tag: `승인 ${rest}건`, tone: 'tag blue', page: 'approvals' })
    }
  }
  const myPending = okCount('myPending')
  if (myPending && myPending.value > 0) {
    tasks.push({ key: 'mine', icon: '↗', blue: false, title: '내 요청이 승인을 기다리고 있어요', sub: '내 요청에서 진행 상태를 확인하세요.', tag: `요청 ${myPending.value}건${myPending.atLeast ? ' 이상' : ''}`, tone: 'tag orange', page: 'mine' })
  }
  const findings = okCount('openFindings')
  if (findings && findings.value > 0) {
    tasks.push({ key: 'findings', icon: '≠', blue: false, title: '맞지 않는 대사 발견이 있어요', sub: '자동으로 고치거나 닫지 않아요. 대사 발견에서 조사하세요.', tag: `대사 ${findings.value}건`, tone: 'tag orange', page: 'reconciliation' })
  }

  // 안내 띠: 실제로 알릴 것이 있을 때만(정산 문구 없음 — 정산 기능 준비 전).
  const banner = pendingApprovals && pendingApprovals.value > 0
    ? { title: `승인을 기다리는 요청이 ${pendingApprovals.value}건${pendingApprovals.atLeast ? ' 이상' : ''} 있어요`, text: '승인함에서 검토하고 인증 코드로 승인·거절하세요.', action: '승인함 열기', page: 'approvals' as Page }
    : findings && findings.value > 0
      ? { title: `맞지 않는 대사 발견이 ${findings.value}건 있어요`, text: '자동으로 고치거나 닫지 않아요. 대사 발견에서 조사하세요.', action: '대사 발견 열기', page: 'reconciliation' as Page }
      : null

  const { y, m, d } = kstParts(today)
  const canRegister = visible.has('businessRegistration')
  const quick = QUICK.flatMap((q) => {
    const menu = menus.find((x) => x.id === q.menu)
    const page = q.page && menu?.tabs.some((t) => t.page === q.page) ? q.page : q.page ? undefined : menu?.tabs[0]?.page
    return page ? [{ label: q.label, page }] : []
  })

  return (
    <>
      <WorkspaceHeader
        eyebrow="PAYMENT OPERATIONS"
        title={`좋은 하루예요, ${me.displayName}님`}
        description="오늘 처리해야 할 업무와 결제 흐름을 한눈에 확인하세요."
        action={
          <>
            {/* 날짜 고르기 기능이 없어 오늘 날짜(KST)만 보이는 표시(누르는 단추 아님). */}
            <span className="btn datechip">
              <time dateTime={`${y}-${two(m)}-${two(d)}`} aria-label={`오늘(KST) ${y}년 ${m}월 ${d}일`}>{`${y}. ${two(m)}. ${two(d)}`}</time>
            </span>
            {canRegister && (
              <button type="button" className="btn primary" onClick={() => onOpen('businessRegistration')}>
                ＋ 사업자 등록
              </button>
            )}
          </>
        }
      />

      {banner && (
        <div className="banner">
          <span className="symbol" aria-hidden="true">!</span>
          <div>
            <b>{banner.title}</b>
            <p>{banner.text}</p>
          </div>
          <button type="button" onClick={() => onOpen(banner.page)}>{`${banner.action}　→`}</button>
        </div>
      )}

      {ops && (
        <div className="stats">
          <article className="stat" aria-label="오늘 순승인액">
            <div className="stitle">오늘 순승인액</div>
            {!canSeePayments ? (
              <strong className="pending">권한 없음</strong>
            ) : kpis.state === 'ok' ? (
              kpis.doc.items.length === 0 ? (
                <strong className="pending">{NO_FACTS}</strong>
              ) : (
                // 환경별 묶음·환경 표지 항상(C-1), 통화마다 한 줄 — 통화·환경 간 합산 없음.
                byEnvironment(kpis.doc.items).map((group) => (
                  <div role="group" aria-label={`${group.environment} 환경`} key={group.environment}>
                    <span className="tag">{group.environment}</span>
                    {group.rows.map((row) => (
                      <strong key={row.currency}>
                        {groupDigits(row.netApprovedAmountMinor)}
                        <small>{kpiUnit(row.currency)}</small>
                      </strong>
                    ))}
                  </div>
                ))
              )
            ) : (
              <strong className="pending">{kpis.state === 'error' ? '불러오지 못했어요' : '불러오는 중…'}</strong>
            )}
            <div className="hint">
              {kpis.state === 'error'
                ? ERROR_MESSAGES[kpis.code]
                : kpis.state === 'ok'
                  ? `순승인액(취소 차감) · ${KPI_LABEL} · ${formatKst(kpis.doc.asOf).slice(11, 16)} 기준`
                  : `순승인액(취소 차감) · ${KPI_LABEL}`}
            </div>
          </article>
          <article className="stat" aria-label="오늘 승인 건수">
            <div className="stitle">오늘 승인 건수</div>
            {canSeePayments && kpis.state === 'ok' ? (
              kpis.doc.items.length === 0 ? (
                <strong className="pending">{NO_FACTS}</strong>
              ) : (
                // 건수도 환경·통화 행별 그대로(C-1 환경 간 합산 금지, 통화별 분리 — 합계를 만들지 않는다).
                byEnvironment(kpis.doc.items).map((group) => (
                  <div role="group" aria-label={`${group.environment} 환경`} key={group.environment}>
                    <span className="tag">{group.environment}</span>
                    {group.rows.map((row) => (
                      <strong key={row.currency}>
                        {row.approvedCount.toLocaleString('en-US')}
                        <small>{`건 · ${row.currency}`}</small>
                      </strong>
                    ))}
                  </div>
                ))
              )
            ) : (
              <strong className="pending">{!canSeePayments ? '권한 없음' : kpis.state === 'error' ? '불러오지 못했어요' : '불러오는 중…'}</strong>
            )}
            <div className="hint">오늘 승인 · 전액취소 제외 · 환경·통화별</div>
          </article>
          <article className="stat" aria-label="지급 예정액">
            <div className="stitle">지급 예정액</div>
            <strong className="pending">정산 기능 준비 전</strong>
            <div className="hint">정산·지급 기능이 연결되면 표시돼요</div>
          </article>
          <article className="stat" aria-label="확인이 필요한 업무">
            <div className="stitle">확인이 필요한 업무</div>
            {metrics.length === 0 ? (
              <strong className="pending">해당 없음</strong>
            ) : !loaded ? (
              <strong className="pending">불러오는 중…</strong>
            ) : failed ? (
              // 리뷰 MINOR-4: 실패한 출처가 있으면 합계는 정확하지 않다 — 숫자를 보이지 않고 출처별 내역(아래)만 남긴다.
              <strong className="pending">일부 불러오지 못함</strong>
            ) : (
              <strong>
                {`${total}${totalAtLeast ? '+' : ''}`}
                <small>건</small>
              </strong>
            )}
            <div className="hint">
              {metrics.length === 0
                ? '이 역할이 처리할 업무 목록이 없어요'
                : metrics
                    .map((x) => {
                      const c = counts[x.id]
                      return `${x.short} ${c?.state === 'ok' ? `${c.value}${c.atLeast ? '+' : ''}` : c?.state === 'error' ? '오류' : '…'}`
                    })
                    .join(' · ')}
            </div>
            {metrics.flatMap((x) => {
              const c = counts[x.id]
              return c?.state === 'error' ? [<p key={x.id} className="hint error">{`${x.short}: ${ERROR_MESSAGES[c.code]}`}</p>] : []
            })}
          </article>
        </div>
      )}

      {ops && (
        <div className="split">
          <section className="card" aria-label="지금 처리할 업무">
            <div className="cardtop">
              <h2>
                {'지금 처리할 업무 '}
                {tasks.length > 0 && !failed && <span className="count">{`${total}${totalAtLeast ? '+' : ''}`}</span>}
              </h2>
            </div>
            {tasks.map((t) => (
              <button type="button" className="task" key={t.key} onClick={() => onOpen(t.page)}>
                <span className={t.blue ? 'taskicon blue' : 'taskicon'} aria-hidden="true">{t.icon}</span>
                {/* button 안에는 phrasing content만 — span(flex item이라 원본 div처럼 block으로 놓인다). */}
                <span>
                  <b>{t.title}</b>
                  <small>{t.sub}</small>
                </span>
                <span className={t.tone}>{t.tag}</span>
              </button>
            ))}
            {tasks.length === 0 && (
              <p className="mapnote">
                {metrics.length === 0 ? '이 역할에는 처리할 업무 목록이 없어요.' : !loaded ? '불러오는 중…' : failed ? '불러오지 못한 현황이 있어요. 위 카드를 확인하세요.' : '지금 처리할 업무가 없어요.'}
              </p>
            )}
          </section>
          <section className="card" aria-label="오늘의 정산 흐름">
            <div className="cardtop">
              <h2>오늘의 정산 흐름</h2>
            </div>
            <p className="mapnote">정산·지급 기능은 아직 연결 전이에요. 연결되면 단계별 진행이 여기에 표시돼요.</p>
            <div className="pipeline">
              {SETTLEMENT_STEPS.map((step, i) => (
                <div className="pipe" key={step}>
                  <span className="stepno">{i + 1}</span>
                  <div>
                    <b>{step}</b>
                  </div>
                  <span className="tag">준비 전</span>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}

      {canSeePayments && (
        <section className="card" aria-label="최근 거래">
          <div className="cardtop">
            <h2>최근 거래</h2>
            <button type="button" onClick={() => onOpen('transactions')}>전체 거래 보기 →</button>
          </div>
          <div className="tablewrap">
            <table aria-label="최근 거래 목록">
              <thead>
                <tr>
                  <th>주문 / 결제 ID</th>
                  <th>가맹점</th>
                  <th>결제수단</th>
                  <th>거래일시</th>
                  <th>상태</th>
                  <th className="amount">금액</th>
                </tr>
              </thead>
              <tbody>
                {recent.state === 'ok' &&
                  recent.items.map((row) => (
                    <tr key={row.paymentId} aria-label={row.paymentId}>
                      <td>
                        <strong>{row.orderNamePreview === null ? row.merchantOrderId : `${row.orderNamePreview}${row.orderNameTruncated ? '…' : ''}`}</strong>
                        <small>{row.paymentId}</small>
                      </td>
                      <td>
                        {recent.labels === 'UNAVAILABLE' ? (
                          environmentMid(row.environment, row.merchantAccountId)
                        ) : (
                          <>
                            <strong>{merchantLabelText(row.merchant) ?? MERCHANT_LABEL_MISSING}</strong>
                            <small>{environmentMid(row.environment, row.merchantAccountId)}</small>
                          </>
                        )}
                      </td>
                      <td>{paymentMethodLabel(row.paymentMethod)}</td>
                      <td><KstTime iso={row.createdAt} /></td>
                      <td><span className={tagClass(row.status)} title={row.status}>{labelOf(row.status)}</span></td>
                      <td className="amount">{Number.isSafeInteger(row.amountMinor) ? formatMinor(row.amountMinor, row.currency) : '표시할 수 없는 금액'}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
            {recent.state === 'loading' && <div className="empty">불러오는 중…</div>}
            {recent.state === 'error' && <div className="empty">{ERROR_MESSAGES[recent.code]}</div>}
            {recent.state === 'ok' && recent.items.length === 0 && <div className="empty">최근 7일 동안의 거래가 없어요.</div>}
            {recent.state === 'ok' && recent.labels === 'UNAVAILABLE' && recent.items.length > 0 && <p className="mapnote" role="note">{MERCHANT_LABELS_UNAVAILABLE}</p>}
          </div>
          <div className="tablebottom">
            <span>{recent.state === 'ok' ? `최근 ${recent.items.length}건` : '최근 거래'}</span>
            <span>최근 7일(KST) · 생성 시각 순</span>
          </div>
        </section>
      )}

      {quick.length > 0 && (
        <div className="quick" role="group" aria-label="바로가기">
          {quick.map((q) => (
            <button key={q.page} type="button" aria-label={`${q.label} 열기`} onClick={() => onOpen(q.page)}>
              {`${q.label} `}
              <span aria-hidden="true">↗</span>
            </button>
          ))}
        </div>
      )}
    </>
  )
}
