import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, TransactionDetail, TransactionSearchInput, TransactionSearchPage } from '../api/adminApi'
import { MERCHANT_LABEL_MISSING, MERCHANT_LABELS_UNAVAILABLE, merchantLabelText, paymentMethodLabel } from '../lib/merchantLabel'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { SearchIcon } from '../components/icons'
import { EmptyRow, Field, KstTime, Spinner, StatusBadge } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { formatMinor } from '../lib/format'
import { labelOf } from '../lib/labels'

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

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card as="section" aria-label={title} className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-4">{children}</CardContent>
    </Card>
  )
}

function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-muted-foreground">{term}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

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
              <StatusBadge code={payment.status} />
              {detail.onHold && <Badge variant="destructive">보류(hold) 중</Badge>}
            </span>
          </Item>
          <Item term="금액">{amount(payment.amountMinor, payment.currency)}</Item>
          <Item term="생성 시각"><KstTime iso={payment.createdAt} /></Item>
          <Item term="환경"><span className="font-mono">{payment.environment}</span></Item>
          <Item term="MID"><Mono>{payment.merchantAccountId}</Mono></Item>
          <Item term="Site"><Mono>{payment.merchantSiteId}</Mono></Item>
          <Item term="가맹점 주문번호"><Mono>{payment.merchantOrderId}</Mono></Item>
          <Item term="주문명">{payment.orderName ?? <span className="text-muted-foreground">없음</span>}</Item>
          <Item term="결제수단">{labelOf(payment.paymentMethod)}</Item>
          <Item term="종료 근거">
            {detail.terminationEvidence.length === 0 ? <span className="text-muted-foreground">-</span> : <Mono>{detail.terminationEvidence.join(', ')}</Mono>}
          </Item>
        </dl>
      </Panel>

      <Panel title="승인">
        {authorization === null ? (
          <p className="text-sm text-muted-foreground">승인 시도가 없습니다.</p>
        ) : (
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Item term="승인 ID"><Mono>{authorization.authorizationId}</Mono></Item>
            <Item term="승인 상태"><StatusBadge code={authorization.status} /></Item>
            <Item term="할부">{authorization.planKind === 'INSTALLMENT' ? `${authorization.installmentMonths ?? '-'}개월 할부` : name(PLAN, authorization.planKind)}</Item>
            <Item term="카드 종류">{name(CARD_TYPE, authorization.cardType)}</Item>
            <Item term="시작 시각"><KstTime iso={authorization.openedAt} /></Item>
            {authorization.failureEvidence !== null && <Item term="실패 근거"><Mono>{authorization.failureEvidence}</Mono></Item>}
          </dl>
        )}
      </Panel>

      <Panel title="거래 사실">
        <Table aria-label="거래 사실 목록">
          <TableHeader>
            <TableRow>
              <TableHead>구분</TableHead><TableHead>금액</TableHead><TableHead>승인번호</TableHead><TableHead>기관 거래번호</TableHead>
              <TableHead>기관 거래일</TableHead><TableHead>확정 시각</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {transactions.length === 0 && <EmptyRow columns={6} message="확정된 거래가 없습니다." />}
            {transactions.map((t) => (
              <TableRow key={t.transactionId} aria-label={t.transactionId}>
                <TableCell>
                  <span className="flex flex-col">
                    <span>{name(TRANSACTION_TYPE, t.type)}{t.cancelScope !== null ? ` (${name(SCOPE, t.cancelScope)})` : ''}</span>
                    <Mono>{t.transactionId}</Mono>
                  </span>
                </TableCell>
                <TableCell>{amount(t.amountMinor, t.currency)}</TableCell>
                <TableCell><Mono>{t.approvalNumber ?? '-'}</Mono></TableCell>
                <TableCell><Mono>{t.providerTransactionId}</Mono></TableCell>
                <TableCell className="font-mono text-xs">{t.institutionTransactionDate}</TableCell>
                <TableCell><KstTime iso={t.confirmedAt} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel title="취소 이력">
        <p className="text-xs text-muted-foreground">운영자 보정 취소의 사유·근거는 이 화면에 표시하지 않습니다(개시 주체만 표시).</p>
        <Table aria-label="취소 이력 목록">
          <TableHeader>
            <TableRow>
              <TableHead>취소</TableHead><TableHead>개시</TableHead><TableHead>범위</TableHead><TableHead>상태</TableHead><TableHead>금액</TableHead><TableHead>요청 시각</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {cancellations.length === 0 && <EmptyRow columns={6} message="취소 이력이 없습니다." />}
            {cancellations.map((c) => (
              <TableRow key={c.cancellationId} aria-label={c.cancellationId}>
                <TableCell>
                  <span className="flex flex-col">
                    <Mono>{c.cancellationId}</Mono>
                    {c.merchantCancelId !== null && <span className="text-xs text-muted-foreground">{`가맹점 취소번호 ${c.merchantCancelId}`}</span>}
                  </span>
                </TableCell>
                <TableCell>{name(INITIATOR, c.initiatorKind)}</TableCell>
                <TableCell>{name(SCOPE, c.scope)}</TableCell>
                <TableCell>
                  <span className="flex flex-col gap-0.5">
                    <span>{name(CANCEL_STATUS, c.status)}</span>
                    {c.failureEvidence !== null && <Mono>{c.failureEvidence}</Mono>}
                  </span>
                </TableCell>
                <TableCell>{amount(c.amountMinor, c.currency)}</TableCell>
                <TableCell><KstTime iso={c.openedAt} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel title="반환 잔액">
        {returnBalance === null ? (
          <p className="text-sm text-muted-foreground">반환 잔액 기록이 없습니다(취소가 시작된 적 없음).</p>
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

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="거래 조회" description="결제 거래를 조회합니다. 조회 전용이며, 거래 상세 조회는 감사 기록에 남습니다." />

      <Card className="gap-4 py-5">
        <CardHeader className="px-5">
          <CardTitle className="text-sm">검색</CardTitle>
        </CardHeader>
        <CardContent className="px-5">
          <form onSubmit={search} className="flex flex-col gap-4">
            <Field label="조회 방식">
              {(describedBy) => (
                <NativeSelect value={mode} onChange={(e) => { setMode(e.target.value as typeof mode); setInputError(null) }} aria-describedby={describedBy}>
                  <option value="period">기간·조건</option>
                  <option value="payment">결제 ID</option>
                  <option value="order">MID + 가맹점 주문번호</option>
                </NativeSelect>
              )}
            </Field>
            {mode === 'payment' ? (
              <Field label="결제 ID">
                {(describedBy) => (
                  <Input value={paymentId} onChange={(e) => setPaymentId(e.target.value)} autoComplete="off" placeholder="pay_…" aria-describedby={describedBy} className="font-mono" />
                )}
              </Field>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {mode === 'period' && (
                  <>
                    <Field label="시작일(KST)" hint={`최대 ${MAX_PERIOD_DAYS}일(Demo 기준)`}>
                      {(describedBy) => <Input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} aria-describedby={describedBy} />}
                    </Field>
                    <Field label="종료일(KST)">
                      {(describedBy) => <Input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} aria-describedby={describedBy} />}
                    </Field>
                  </>
                )}
                <Field label="환경">
                  {(describedBy) => (
                    <NativeSelect value={environment} onChange={(e) => setEnvironment(e.target.value)} aria-describedby={describedBy}>
                      <option value="">전체</option>
                      <option value="TEST">TEST</option>
                      <option value="LIVE">LIVE</option>
                    </NativeSelect>
                  )}
                </Field>
                <Field label="MID">
                  {(describedBy) => (
                    <Input value={merchantAccountId} onChange={(e) => setMerchantAccountId(e.target.value)} autoComplete="off" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                {mode === 'period' ? (
                  <Field label="결제 상태">
                    {(describedBy) => (
                      <NativeSelect value={status} onChange={(e) => setStatus(e.target.value)} aria-describedby={describedBy}>
                        <option value="">전체</option>
                        {STATUSES.map((value) => (
                          <option key={value} value={value}>{labelOf(value)}</option>
                        ))}
                      </NativeSelect>
                    )}
                  </Field>
                ) : (
                  <Field label="가맹점 주문번호">
                    {(describedBy) => (
                      <Input value={merchantOrderId} onChange={(e) => setMerchantOrderId(e.target.value)} autoComplete="off" aria-describedby={describedBy} className="font-mono" />
                    )}
                  </Field>
                )}
              </div>
            )}
            {inputError && <p role="alert" className="text-sm text-destructive">{inputError}</p>}
            <div className="flex justify-end">
              <Button type="submit" aria-busy={listBusy}>
                {listBusy ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
                검색
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      {listError && <ErrorNotice>{message(listError)}</ErrorNotice>}

      {page && (
        <div className="flex flex-col gap-3">
          <Table aria-label="거래 목록">
            <TableHeader>
              <TableRow>
                <TableHead>결제 ID</TableHead><TableHead>생성 시각</TableHead><TableHead>가맹점</TableHead><TableHead>결제수단</TableHead><TableHead>주문</TableHead><TableHead>금액</TableHead>
                <TableHead>상태</TableHead><TableHead><span className="sr-only">작업</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.items.length === 0 && <EmptyRow columns={8} message="조건에 맞는 거래가 없습니다." />}
              {page.items.map((row) => (
                <TableRow key={row.paymentId} aria-label={row.paymentId} className={detail?.payment.paymentId === row.paymentId ? 'bg-muted/50' : undefined}>
                  <TableCell><Mono>{row.paymentId}</Mono></TableCell>
                  <TableCell><KstTime iso={row.createdAt} /></TableCell>
                  <TableCell>
                    {/* TXN-LIST-ENRICH-001 §3.4: "<상호> · <가맹점 코드>" + 환경·MID. 쪽 라벨이 UNAVAILABLE이면 MID만(안내는 표 아래 1줄). */}
                    <span className="flex flex-col">
                      {page.merchantLabels !== 'UNAVAILABLE' && (
                        <span className={row.merchant === null ? 'text-xs text-muted-foreground' : 'text-sm'}>{merchantLabelText(row.merchant) ?? MERCHANT_LABEL_MISSING}</span>
                      )}
                      <span className="font-mono text-xs">{row.environment}</span><Mono>{row.merchantAccountId}</Mono>
                    </span>
                  </TableCell>
                  <TableCell>{paymentMethodLabel(row.paymentMethod)}</TableCell>
                  <TableCell>
                    <span className="flex flex-col">
                      <Mono>{row.merchantOrderId}</Mono>
                      <span data-col="orderName" className="text-xs">
                        {row.orderNamePreview === null ? <span className="text-muted-foreground">주문명 없음</span> : `${row.orderNamePreview}${row.orderNameTruncated ? '…' : ''}`}
                      </span>
                    </span>
                  </TableCell>
                  <TableCell>{amount(row.amountMinor, row.currency)}</TableCell>
                  <TableCell><StatusBadge code={row.status} /></TableCell>
                  <TableCell>
                    <Button type="button" variant="outline" size="sm" onClick={() => open(row.paymentId)}>상세</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {page.merchantLabels === 'UNAVAILABLE' && page.items.length > 0 && <p role="note" className="text-xs text-muted-foreground">{MERCHANT_LABELS_UNAVAILABLE}</p>}
          <p className="text-xs text-muted-foreground">목록의 주문명은 앞 10자만 표시합니다. 전체 주문명·승인번호는 상세(감사 기록)에서 확인하세요.</p>
          {page.nextCursor !== null && (
            <div className="flex justify-end">
              <Button type="button" variant="outline" onClick={nextPage} aria-busy={listBusy}>다음</Button>
            </div>
          )}
        </div>
      )}

      {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
      {detailError && <ErrorNotice>{message(detailError)}</ErrorNotice>}
      {detail && (
        <div className="flex flex-col gap-4">
          <h3 className="text-sm font-bold">
            {'거래 상세 '}
            <span className="font-mono text-xs text-muted-foreground">{detail.payment.paymentId}</span>
          </h3>
          <DetailPanels detail={detail} />
        </div>
      )}
    </section>
  )
}

/** `YYYY-MM-DD`에서 [days]일 전 달력일. */
function kstDaysBefore(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00.000Z`) - days * DAY_MS).toISOString().slice(0, 10)
}
