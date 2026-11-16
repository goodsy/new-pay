import { useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, TransactionDetail, TransactionSearchInput, TransactionSearchPage } from '../api/adminApi'
import { MERCHANT_LABEL_MISSING, MERCHANT_LABELS_UNAVAILABLE, merchantLabelText, paymentMethodLabel } from '../lib/merchantLabel'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { KstTime, Spinner } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'
import { tagClass } from '../lib/statusTone'

/** 결제 상태(payment-api PaymentStatus — 서버도 다시 검사한다, TXN-OPS-001 §3.1). */
const STATUSES = ['REQUESTED', 'AUTHORIZING', 'UNKNOWN', 'AUTHORIZED', 'FAILED', 'EXPIRED'] as const
/** 기간 상한(D-TXOPS-001 — Demo 값, 운영값 아님. 서버가 판정하고 화면은 미리 안내만 한다). */
const MAX_PERIOD_DAYS = 31
const DAY_MS = 24 * 60 * 60 * 1000
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/

/** 화면 고유 이름(같은 code가 다른 화면에서 다른 뜻인 값 — 공용 labels에 넣지 않는다). */
const INITIATOR: Readonly<Record<string, string>> = { MERCHANT: '가맹점 요청', OPERATOR: '운영자 보정' }
const TRANSACTION_TYPE: Readonly<Record<string, string>> = { AUTHORIZATION: '승인', CANCEL: '취소' }
const PLAN: Readonly<Record<string, string>> = { LUMP_SUM: '일시불', INSTALLMENT: '할부' }
const CARD_TYPE: Readonly<Record<string, string>> = { CREDIT: '신용', CHECK: '체크' }
const SCOPE: Readonly<Record<string, string>> = { FULL: '전액', PARTIAL: '부분' }
const CANCEL_STATUS: Readonly<Record<string, string>> = { OPEN: '진행 중', UNKNOWN: '결과 확인 중', COMPLETED: '완료', FAILED: '실패' }
const name = (table: Readonly<Record<string, string>>, code: string | null) => (code === null ? '-' : table[code] ?? code)

/** 읽기 화면의 결제 서비스 불가 문구(공용 문구는 보정 취소 저장 기준이라 조회에 맞게 바꾼다). */
const READ_UNAVAILABLE = '결제 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
const message = (code: ConsoleErrorCode) => (code === 'PAYMENT_UNAVAILABLE' ? READ_UNAVAILABLE : ERROR_MESSAGES[code])

/** 오늘(KST 달력일) `YYYY-MM-DD`. */
function todayKst(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 10)
}

/** KST 달력일 → 그 날 00:00 KST 순간(UTC ISO ms). 끝 날짜는 포함이라 다음 날 00:00 KST를 `createdTo`로 보낸다(I-4). */
function kstStart(date: string, plusDays = 0): string {
  return new Date(Date.parse(`${date}T00:00:00.000Z`) - KST_OFFSET_MS + plusDays * DAY_MS).toISOString()
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>

/**
 * 상세 패널 하나(`section` + 이름 → region). ADMIN-UI-WORKSPACE-001(거래 조회): 가맹점 조회와 같은 시안 `.card` > `.cardtop`(h4 — 상세 제목 h3 아래 단계) → `.cardbody`.
 */
function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card" aria-label={title}>
      <div className="cardtop">
        <h4>{title}</h4>
      </div>
      <div className="cardbody">{children}</div>
    </section>
  )
}

/** 항목 이름·값(시안 `.detailgrid` label 13px·500 / 값 14px — 가맹점 조회 상세와 같은 값, 정의 목록 의미는 유지). */
function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <dt className="text-[13px] font-medium text-subtle">{term}</dt>
      <dd className="text-sm text-heading">{children}</dd>
    </div>
  )
}

/** 상태 code → 시안 `.tag`(색 의미는 StatusBadge와 같은 statusTone — 문구는 labelOf, title은 원래 code). */
const StatusTag = ({ code }: { code: string }) => <span className={tagClass(code)} title={code}>{labelOf(code)}</span>

function amount(minor: number, currency: string) {
  return Number.isSafeInteger(minor) ? formatMinor(minor, currency) : '표시할 수 없는 금액'
}

function DetailPanels({ detail }: { detail: TransactionDetail }) {
  const { payment, authorization, transactions, cancellations, returnBalance } = detail
  return (
    <>
      <Panel title="결제">
        <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Item term="결제 ID"><Mono>{payment.paymentId}</Mono></Item>
          <Item term="상태">
            <span className="flex flex-wrap items-center gap-2">
              <StatusTag code={payment.status} />
              {detail.onHold && <span className="tag red">보류(hold) 중</span>}
            </span>
          </Item>
          <Item term="금액">{amount(payment.amountMinor, payment.currency)}</Item>
          <Item term="생성 시각"><KstTime iso={payment.createdAt} /></Item>
          <Item term="환경"><span className="font-mono">{payment.environment}</span></Item>
          <Item term="MID"><Mono>{payment.merchantAccountId}</Mono></Item>
          <Item term="Site"><Mono>{payment.merchantSiteId}</Mono></Item>
          <Item term="가맹점 주문번호"><Mono>{payment.merchantOrderId}</Mono></Item>
          <Item term="주문명">{payment.orderName ?? <span className="subtle">없음</span>}</Item>
          <Item term="결제수단">{labelOf(payment.paymentMethod)}</Item>
          <Item term="종료 근거">
            {detail.terminationEvidence.length === 0 ? <span className="subtle">-</span> : <Mono>{detail.terminationEvidence.join(', ')}</Mono>}
          </Item>
        </dl>
      </Panel>

      <Panel title="승인">
        {authorization === null ? (
          <p className="subtle">승인 시도가 없습니다.</p>
        ) : (
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Item term="승인 ID"><Mono>{authorization.authorizationId}</Mono></Item>
            <Item term="승인 상태"><StatusTag code={authorization.status} /></Item>
            <Item term="할부">{authorization.planKind === 'INSTALLMENT' ? `${authorization.installmentMonths ?? '-'}개월 할부` : name(PLAN, authorization.planKind)}</Item>
            <Item term="카드 종류">{name(CARD_TYPE, authorization.cardType)}</Item>
            <Item term="시작 시각"><KstTime iso={authorization.openedAt} /></Item>
            {authorization.failureEvidence !== null && <Item term="실패 근거"><Mono>{authorization.failureEvidence}</Mono></Item>}
          </dl>
        )}
      </Panel>

      <Panel title="거래 사실">
        <div className="tablewrap">
          <table aria-label="거래 사실 목록">
            <thead>
              <tr>
                <th>구분</th><th className="amount">금액</th><th>승인번호</th><th>기관 거래번호</th><th>기관 거래일</th><th>확정 시각</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((t) => (
                <tr key={t.transactionId} aria-label={t.transactionId}>
                  <td>
                    <strong>{name(TRANSACTION_TYPE, t.type)}{t.cancelScope !== null ? ` (${name(SCOPE, t.cancelScope)})` : ''}</strong>
                    <small>{t.transactionId}</small>
                  </td>
                  <td className="amount">{amount(t.amountMinor, t.currency)}</td>
                  <td>{t.approvalNumber ?? '-'}</td>
                  <td>{t.providerTransactionId}</td>
                  <td>{t.institutionTransactionDate}</td>
                  <td><KstTime iso={t.confirmedAt} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          {transactions.length === 0 && <div className="empty">확정된 거래가 없습니다.</div>}
        </div>
      </Panel>

      <Panel title="취소 이력">
        <p className="subtle">운영자 보정 취소의 사유·근거는 이 화면에 표시하지 않습니다(개시 주체만 표시).</p>
        <div className="tablewrap">
          <table aria-label="취소 이력 목록">
            <thead>
              <tr>
                <th>취소</th><th>개시</th><th>범위</th><th>상태</th><th className="amount">금액</th><th>요청 시각</th>
              </tr>
            </thead>
            <tbody>
              {cancellations.map((c) => (
                <tr key={c.cancellationId} aria-label={c.cancellationId}>
                  <td>
                    <strong>{c.cancellationId}</strong>
                    {c.merchantCancelId !== null && <small>{`가맹점 취소번호 ${c.merchantCancelId}`}</small>}
                  </td>
                  <td>{name(INITIATOR, c.initiatorKind)}</td>
                  <td>{name(SCOPE, c.scope)}</td>
                  <td>
                    <span>{name(CANCEL_STATUS, c.status)}</span>
                    {c.failureEvidence !== null && <small>{c.failureEvidence}</small>}
                  </td>
                  <td className="amount">{amount(c.amountMinor, c.currency)}</td>
                  <td><KstTime iso={c.openedAt} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          {cancellations.length === 0 && <div className="empty">취소 이력이 없습니다.</div>}
        </div>
      </Panel>

      <Panel title="반환 잔액">
        {returnBalance === null ? (
          <p className="subtle">반환 잔액 기록이 없습니다(취소가 시작된 적 없음).</p>
        ) : (
          <dl className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
            <Item term="승인액">{amount(returnBalance.authorizedMinor, returnBalance.currency)}</Item>
            <Item term="취소 완료">{amount(returnBalance.completedMinor, returnBalance.currency)}</Item>
            <Item term="취소 진행 중(예약)">{amount(returnBalance.reservedMinor, returnBalance.currency)}</Item>
            <Item term="강제 반영">{amount(returnBalance.forcedMinor, returnBalance.currency)}</Item>
            <Item term="revision"><span className="font-mono">{returnBalance.revision}</span></Item>
          </dl>
        )}
      </Panel>
    </>
  )
}

interface Props {
  api: AdminApi
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 시험용 현재 시각(기본 Date.now). */
  now?: () => number
}

/**
 * 거래 조회(TXN-OPS-001 §4.1·§7 AC 10 — 읽기 전용). 조회 방식 세 가지: 기간(KST 달력일, 최대 31일) + 환경·MID·상태 필터 / 결제 ID 정확 조회 / MID + 가맹점 주문번호.
 * 목록은 최신순(keyset "다음")이고 주문명은 앞 10자만 보인다. 행 "상세"로 결제·승인·거래 사실·취소 이력·반환 잔액을 연다 — 상세는 서버가 감사 기록을 남긴 뒤에만 온다.
 * 권한·형식·감사·속도 제한은 서버가 판정한다(화면은 미리 안내만). 저장소·URL에 아무것도 남기지 않는다.
 */
export function TransactionsScreen({ api, onSessionEnded, now = Date.now }: Props) {
  const today = todayKst(now())
  const [mode, setMode] = useState<'period' | 'payment' | 'order'>('period')
  const [fromDate, setFromDate] = useState(kstDaysBefore(today, 6))
  const [toDate, setToDate] = useState(today)
  const [environment, setEnvironment] = useState('')
  const [merchantAccountId, setMerchantAccountId] = useState('')
  const [status, setStatus] = useState('')
  const [paymentId, setPaymentId] = useState('')
  const [merchantOrderId, setMerchantOrderId] = useState('')
  const [inputError, setInputError] = useState<string | null>(null)
  const [sent, setSent] = useState<TransactionSearchInput | null>(null)
  const [page, setPage] = useState<TransactionSearchPage | null>(null)
  const [listError, setListError] = useState<ConsoleErrorCode | null>(null)
  const [listBusy, setListBusy] = useState(false)
  const [detail, setDetail] = useState<TransactionDetail | null>(null)
  const [detailError, setDetailError] = useState<ConsoleErrorCode | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  // 다른 행을 연 뒤 늦게 도착한 앞 행 응답은 버린다.
  const openRequest = useRef(0)
  const periodNoteId = useId()

  const failure = (e: unknown): ConsoleErrorCode | null => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) {
      onSessionEnded(code)
      return null
    }
    return code
  }

  /** 입력 → 검색 조건(화면 안내용 검사 — 서버가 다시 판정). 문제가 있으면 안내 문구. */
  const criteria = (): TransactionSearchInput | string => {
    if (mode === 'payment') {
      const id = paymentId.trim()
      return id === '' ? '결제 ID를 입력해 주세요.' : { paymentId: id }
    }
    const mid = merchantAccountId.trim()
    const base: TransactionSearchInput = {}
    if (environment === 'TEST' || environment === 'LIVE') base.environment = environment
    if (mid !== '') base.merchantAccountId = mid
    if (mode === 'order') {
      const order = merchantOrderId.trim()
      if (mid === '' || order === '') return 'MID와 가맹점 주문번호를 모두 입력해 주세요.'
      return { ...base, merchantOrderId: order }
    }
    if (!DATE.test(fromDate) || !DATE.test(toDate)) return '조회 기간을 입력해 주세요.'
    const days = (Date.parse(toDate) - Date.parse(fromDate)) / DAY_MS + 1
    if (days < 1) return '시작일이 종료일보다 늦습니다.'
    if (days > MAX_PERIOD_DAYS) return `조회 기간은 최대 ${MAX_PERIOD_DAYS}일입니다.`
    if (status !== '') base.status = status
    return { createdFrom: kstStart(fromDate), createdTo: kstStart(toDate, 1), ...base }
  }

  const runSearch = async (input: TransactionSearchInput, base: TransactionSearchInput) => {
    setListError(null)
    setListBusy(true)
    try {
      const result = await api.transactionSearch(input)
      setSent(base)
      setPage(result)
    } catch (e) {
      setListError(failure(e))
    } finally {
      setListBusy(false)
    }
  }

  const search = async (event?: FormEvent) => {
    event?.preventDefault()
    const built = criteria()
    if (typeof built === 'string') {
      setInputError(built)
      return
    }
    setInputError(null)
    setPage(null)
    setDetail(null)
    setDetailError(null)
    openRequest.current += 1
    await runSearch(built, built)
  }

  const nextPage = async () => {
    if (sent === null || !page?.nextCursor) return
    await runSearch({ ...sent, after: page.nextCursor }, sent)
  }

  const open = async (id: string) => {
    const request = ++openRequest.current
    setDetail(null)
    setDetailError(null)
    setDetailBusy(true)
    try {
      const found = await api.transactionDetail(id)
      if (openRequest.current === request) setDetail(found)
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    } finally {
      if (openRequest.current === request) setDetailBusy(false)
    }
  }

  // ADMIN-UI-WORKSPACE-001(거래 조회): 참고 시안 업무 화면(app.js moduleView — 카드 안 탭 → .filters → .tablewrap → .tablebottom, 상세는 detail()의 eyebrow·제목·카드).
  // 가맹점 조회와 같이 MenuCard flush 탭 패널 바로 안에 그린다. 입력란 이름은 aria-label(시안처럼 보이는 label 없음 — placeholder·선택지로 무엇인지 보인다),
  // 기간 안내·입력 오류·목록 오류는 필터 아래 .cardnote. 조회 방식에 따라 입력란만 바뀌고 줄 모양은 같다(.filters.many — 좁은 폭에서 줄바꿈).
  return (
    <>
      <div className="mapnote">
        <h2>거래 조회</h2>
        {' · 결제 거래를 조회합니다. 조회 전용이며, 거래 상세 조회는 감사 기록에 남습니다.'}
      </div>
      <form className="filters many" onSubmit={search}>
        <select aria-label="조회 방식" value={mode} onChange={(e) => { setMode(e.target.value as typeof mode); setInputError(null) }}>
          <option value="period">기간·조건</option>
          <option value="payment">결제 ID</option>
          <option value="order">MID + 가맹점 주문번호</option>
        </select>
        {mode === 'payment' ? (
          <input aria-label="결제 ID" value={paymentId} onChange={(e) => setPaymentId(e.target.value)} autoComplete="off" spellCheck={false} placeholder="결제 ID (pay_…)" className="font-mono" />
        ) : (
          <>
            {mode === 'period' && (
              <>
                <input type="date" aria-label="시작일(KST)" value={fromDate} onChange={(e) => setFromDate(e.target.value)} aria-describedby={periodNoteId} />
                <input type="date" aria-label="종료일(KST)" value={toDate} onChange={(e) => setToDate(e.target.value)} aria-describedby={periodNoteId} />
              </>
            )}
            <select aria-label="환경" value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              <option value="">전체 환경</option>
              <option value="TEST">TEST</option>
              <option value="LIVE">LIVE</option>
            </select>
            <input aria-label="MID" value={merchantAccountId} onChange={(e) => setMerchantAccountId(e.target.value)} autoComplete="off" spellCheck={false} placeholder="MID" className="font-mono" />
            {mode === 'period' ? (
              <select aria-label="결제 상태" value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">전체 상태</option>
                {STATUSES.map((value) => (
                  <option key={value} value={value}>{labelOf(value)}</option>
                ))}
              </select>
            ) : (
              <input aria-label="가맹점 주문번호" value={merchantOrderId} onChange={(e) => setMerchantOrderId(e.target.value)} autoComplete="off" spellCheck={false} placeholder="가맹점 주문번호" className="font-mono" />
            )}
          </>
        )}
        <button type="submit" className="btn primary" aria-busy={listBusy}>검색</button>
      </form>
      {/* 리뷰 NIT-1: 안내·오류가 있을 때만 그린다(결제 ID·주문번호 방식에서 빈 여백 없음). 날짜 칸에는 placeholder가 없어 안내에 "시작일 ~ 종료일"을 쓴다(리뷰 MINOR-2). */}
      {(mode === 'period' || inputError || listError) && (
        <div className="cardnote">
          {mode === 'period' && <p id={periodNoteId} className="subtle">{`조회 기간(시작일 ~ 종료일): KST 달력일 기준, 최대 ${MAX_PERIOD_DAYS}일(Demo 기준).`}</p>}
          {inputError && <p role="alert" className="error">{inputError}</p>}
          {listError && <ErrorNotice>{message(listError)}</ErrorNotice>}
        </div>
      )}

      {page && (
        <>
          <div className="tablewrap">
            <table aria-label="거래 목록">
              <thead>
                <tr>
                  <th>주문 / 결제 ID</th><th>가맹점</th><th>결제수단</th><th>생성 시각</th><th>상태</th><th className="amount">금액</th>
                  <th><span className="sr-only">작업</span></th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.paymentId} aria-label={row.paymentId} className={detail?.payment.paymentId === row.paymentId ? 'selected' : undefined}>
                    <td>
                      <strong>{row.merchantOrderId}</strong>
                      <small data-col="orderName">{row.orderNamePreview === null ? '주문명 없음' : `${row.orderNamePreview}${row.orderNameTruncated ? '…' : ''}`}</small>
                      <small>{row.paymentId}</small>
                    </td>
                    <td>
                      {/* TXN-LIST-ENRICH-001 §3.4: "<상호> · <가맹점 코드>" + 환경·MID. 쪽 라벨이 UNAVAILABLE이면 환경·MID만(안내는 표 아래 1줄). MID는 따로 찾을 수 있게 자기 요소에. */}
                      {page.merchantLabels === 'UNAVAILABLE' ? (
                        <><span>{row.environment}</span>{' · '}<span>{row.merchantAccountId}</span></>
                      ) : (
                        <>
                          <strong>{merchantLabelText(row.merchant) ?? MERCHANT_LABEL_MISSING}</strong>
                          <small><span>{row.environment}</span>{' · '}<span>{row.merchantAccountId}</span></small>
                        </>
                      )}
                    </td>
                    <td>{paymentMethodLabel(row.paymentMethod)}</td>
                    <td><KstTime iso={row.createdAt} /></td>
                    <td><StatusTag code={row.status} /></td>
                    <td className="amount">{amount(row.amountMinor, row.currency)}</td>
                    <td>
                      <button type="button" className="btn" onClick={() => open(row.paymentId)}>상세</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {page.items.length === 0 && <div className="empty">조건에 맞는 거래가 없습니다.</div>}
            {page.merchantLabels === 'UNAVAILABLE' && page.items.length > 0 && <p role="note" className="mapnote">{MERCHANT_LABELS_UNAVAILABLE}</p>}
          </div>
          <div className="tablebottom">
            <span>{`이 쪽 ${page.items.length}건 · 목록의 주문명은 앞 10자만 표시합니다. 전체 주문명·승인번호는 상세(감사 기록)에서 확인하세요.`}</span>
            {page.nextCursor !== null && (
              <button type="button" className="btn" onClick={nextPage} aria-busy={listBusy}>다음</button>
            )}
          </div>
        </>
      )}

      {(detailBusy || detailError || detail) && (
        <div className="detailarea">
          {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
          {detailError && <ErrorNotice>{message(detailError)}</ErrorNotice>}
          {detail && (
            <>
              <div>
                <div className="eyebrow" aria-hidden="true">결제 관리 / 상세</div>
                <h3>
                  {'거래 상세 '}
                  <small>{detail.payment.paymentId}</small>
                </h3>
              </div>
              <DetailPanels detail={detail} />
            </>
          )}
        </div>
      )}
    </>
  )
}

/** `YYYY-MM-DD`에서 [days]일 전 달력일. */
function kstDaysBefore(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00.000Z`) - days * DAY_MS).toISOString().slice(0, 10)
}
