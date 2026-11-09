import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, MerchantDetail, MerchantHistoryEntry, MerchantListItem, MerchantSearchInput } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { SearchIcon } from '../components/icons'
import { EmptyRow, Field, KindLabel, KstTime, Spinner, StatusBadge } from '../components/DataBits'
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

/** 사업자등록번호 형식(ASCII 숫자 정확히 10자리 — 서버도 다시 검사한다, MERCHANT-OPS-001 §3.1). */
const REGISTRATION_NUMBER = /^[0-9]{10}$/
const LIFECYCLES = ['ACTIVE', 'TERMINATED'] as const

interface Props {
  api: AdminApi
  /** 사업자등록번호 입력란 표시(OPERATOR·APPROVER — §5.3, 표시만; 권한 판정·감사는 서버). */
  canSearchRegistrationNumber: boolean
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 화면을 열면서 이 가맹점 상세를 연다(가맹점 등록 "가맹점 조회에서 보기" — 행 "상세"와 같은 경로·감사, MERCHANT-OPS-002 §5.2). */
  openMerchantId?: string | null
  /** [openMerchantId]를 한 번 연 뒤 알린다(호출자가 비워 탭을 다시 와도 또 열지 않게). */
  onOpenedMerchant?: () => void
}

/** 마지막으로 보낸 검색 조건(다음 쪽에 그대로). 사업자등록번호는 담지 않는다 — 화면 상태에 번호를 남기지 않는다. */
interface SentSearch {
  criteria: Omit<MerchantSearchInput, 'registrationNumber' | 'after'>
  byRegistrationNumber: boolean
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>
const period = (from: string, to: string | null) => (
  <span className="flex flex-col gap-0.5 text-xs">
    <KstTime iso={from} />
    {to === null ? <span className="text-muted-foreground">종료일 없음</span> : <KstTime iso={to} />}
  </span>
)

/** 한도 값(설정값 — 금융 사실 아님). 무제한은 이름, 금액은 안전 정수일 때만 천 단위 표시(짐작하지 않는다 — S8 리뷰 L-2와 같은 이유). */
function limitText(kind: string, amount: number | null, currency: string): string {
  if (kind !== 'LIMITED') return labelOf(kind)
  return amount !== null && Number.isSafeInteger(amount) ? formatMinor(amount, currency) : '표시할 수 없는 금액'
}

/** 상세 패널 하나(`section` + 이름 → region). */
function Panel({ name, children }: { name: string; children: ReactNode }) {
  return (
    <Card as="section" aria-label={name} className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm">{name}</CardTitle>
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

function DetailPanels({ detail }: { detail: MerchantDetail }) {
  const { merchant, business, sites, contracts, accounts, limits, propagation } = detail
  return (
    <>
      <Panel name="기본정보">
        <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Item term="가맹점">
            <span className="flex flex-col"><span className="font-medium">{merchant.name}</span><Mono>{merchant.merchantId}</Mono></span>
          </Item>
          <Item term="가맹점 상태"><StatusBadge code={merchant.lifecycle} /></Item>
          <Item term="권리 revision"><span className="font-mono">{merchant.entitlementRevision}</span></Item>
          <Item term="등록일"><KstTime iso={merchant.createdAt} /></Item>
          <Item term="사업자">
            <span className="flex flex-col"><span className="font-medium">{business.name}</span><Mono>{business.businessId}</Mono></span>
          </Item>
          <Item term="사업자등록번호(마스킹)"><span className="font-mono">{business.registrationNumberMasked}</span></Item>
          <Item term="사업자 상태"><StatusBadge code={business.lifecycle} /></Item>
        </dl>
      </Panel>

      <Panel name="Site">
        <Table aria-label="Site 목록">
          <TableHeader>
            <TableRow><TableHead>Site</TableHead><TableHead>채널</TableHead><TableHead>상태</TableHead><TableHead>심사 결과</TableHead></TableRow>
          </TableHeader>
          <TableBody>
            {sites.length === 0 && <EmptyRow columns={4} message="등록된 Site가 없습니다." />}
            {sites.map((site) => (
              <TableRow key={site.merchantSiteId} aria-label={site.merchantSiteId}>
                <TableCell><Mono>{site.merchantSiteId}</Mono></TableCell>
                <TableCell><span className="flex flex-col"><span>{labelOf(site.channelType)}</span><Mono>{site.channelIdentifier}</Mono></span></TableCell>
                <TableCell><StatusBadge code={site.lifecycle} /></TableCell>
                <TableCell>
                  {site.review === null ? (
                    <span className="text-muted-foreground">심사 결정 없음</span>
                  ) : (
                    <span className="flex flex-col gap-1"><StatusBadge code={site.review.decision} /><KstTime iso={site.review.decidedAt} /></span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel name="계약·CSA">
        {contracts.length === 0 && <p className="text-sm text-muted-foreground">등록된 계약이 없습니다.</p>}
        {contracts.map((contract) => (
          <div key={contract.contractId} className="flex flex-col gap-2 rounded-lg border p-3">
            <p className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">계약</span><Mono>{contract.contractId}</Mono>
              <span className="text-xs text-muted-foreground">등록</span><KstTime iso={contract.createdAt} />
            </p>
            <Table aria-label={`계약 revision ${contract.contractId}`}>
              <TableHeader>
                <TableRow><TableHead>revision</TableHead><TableHead>범위</TableHead><TableHead>효력 기간</TableHead><TableHead>승인</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {contract.revisions.map((revision) => (
                  <TableRow key={revision.revisionNo}>
                    <TableCell className="font-mono">{revision.revisionNo}</TableCell>
                    <TableCell>
                      <span className="flex flex-col gap-0.5">
                        <span>{labelOf(revision.scopeKind)}</span>
                        {revision.scopeSiteIds.map((siteId) => <Mono key={siteId}>{siteId}</Mono>)}
                      </span>
                    </TableCell>
                    <TableCell>{period(revision.effectiveFrom, revision.effectiveTo)}</TableCell>
                    <TableCell>{revision.approvedAt === null ? <span className="text-muted-foreground">승인 전</span> : <KstTime iso={revision.approvedAt} />}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <ul aria-label={`CSA ${contract.contractId}`} className="flex flex-col gap-1 text-sm">
              {contract.agreements.map((agreement) => (
                <li key={agreement.agreementId} className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-muted-foreground">CSA</span><Mono>{agreement.agreementId}</Mono>
                  <span>{labelOf(agreement.serviceKind)}</span><KstTime iso={agreement.addedAt} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </Panel>

      <Panel name="MID·개통">
        {accounts.length === 0 && <p className="text-sm text-muted-foreground">발급된 MID가 없습니다.</p>}
        {accounts.map((account) => (
          <div key={account.merchantAccountId} className="flex flex-col gap-3 rounded-lg border p-3">
            <p className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">MID</span><Mono>{account.merchantAccountId}</Mono>
              {account.isDefault && <Badge variant="secondary">기본 MID</Badge>}
              <StatusBadge code={account.lifecycle} />
            </p>
            <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>발급 근거: 계약</span><Mono>{account.issuingContractId}</Mono>
              <span>· CSA</span><Mono>{account.issuingAgreementId}</Mono>
              <span>{`· revision ${account.issuingRevisionNo}`}</span>
              <span>· 발급</span><KstTime iso={account.issuedAt} />
            </p>
            <Table aria-label="Site 배정">
              <TableHeader>
                <TableRow><TableHead>Site</TableHead><TableHead>역할</TableHead><TableHead>상태</TableHead><TableHead>효력 기간</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.assignments.length === 0 && <EmptyRow columns={4} message="Site 배정이 없습니다." />}
                {account.assignments.map((assignment) => (
                  <TableRow key={assignment.assignmentId}>
                    <TableCell><Mono>{assignment.merchantSiteId}</Mono></TableCell>
                    <TableCell>{labelOf(assignment.role)}</TableCell>
                    <TableCell><StatusBadge code={assignment.status} /></TableCell>
                    <TableCell>{period(assignment.effectiveFrom, assignment.effectiveTo)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Table aria-label="결제수단 개통">
              <TableHeader>
                <TableRow><TableHead>결제수단</TableHead><TableHead>상태</TableHead><TableHead>등록</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.paymentMethods.length === 0 && <EmptyRow columns={3} message="결제수단 개통이 없습니다." />}
                {account.paymentMethods.map((method) => (
                  <TableRow key={method.activationId}>
                    <TableCell>{labelOf(method.paymentMethod)}</TableCell>
                    <TableCell>{method.status === null ? <span className="text-muted-foreground">상태 없음</span> : <StatusBadge code={method.status} />}</TableCell>
                    <TableCell><KstTime iso={method.registeredAt} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Table aria-label="통화">
              <TableHeader>
                <TableRow><TableHead>통화</TableHead><TableHead>효력 기간</TableHead><TableHead>종료 처리</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.currencies.length === 0 && <EmptyRow columns={3} message="허용 통화가 없습니다." />}
                {account.currencies.map((currency) => (
                  <TableRow key={currency.enablementId}>
                    <TableCell className="font-mono">{currency.currencyCode}</TableCell>
                    <TableCell>{period(currency.effectiveFrom, currency.effectiveTo)}</TableCell>
                    <TableCell>{currency.endedAt === null ? <span className="text-muted-foreground">-</span> : <KstTime iso={currency.endedAt} />}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {/* 카드 Route는 인수사 코드·Route 참조·상태만 그린다(기관 가맹점 번호는 감사 전용 — D-MS2-005). */}
            <Table aria-label="카드 Route">
              <TableHeader>
                <TableRow><TableHead>인수사 코드</TableHead><TableHead>Route 참조</TableHead><TableHead>상태</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.cardRoutes.length === 0 && <EmptyRow columns={3} message="카드 Route 개통이 없습니다." />}
                {account.cardRoutes.map((route) => (
                  <TableRow key={route.activationId}>
                    <TableCell className="font-mono">{route.acquirerCode}</TableCell>
                    <TableCell className="font-mono">{route.routeRef}</TableCell>
                    <TableCell><StatusBadge code={route.status} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ))}
      </Panel>

      <Panel name="한도">
        <p className="text-xs text-muted-foreground">한도는 MASTER 설정값이며 금융 사실이 아닙니다(Demo 환경의 값은 운영값이 아님).</p>
        <Table aria-label="한도 목록">
          <TableHeader>
            <TableRow>
              <TableHead>범위</TableHead><TableHead>통화</TableHead><TableHead>건당 한도</TableHead><TableHead>일 한도</TableHead><TableHead>효력 기간</TableHead><TableHead>종료 처리</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {limits.length === 0 && <EmptyRow columns={6} message="등록된 한도가 없습니다." />}
            {limits.map((limit) => (
              <TableRow key={limit.policyId} aria-label={limit.policyId}>
                <TableCell>
                  <span className="flex flex-col gap-0.5">
                    <span>{labelOf(limit.scopeKind)}</span>
                    <Mono>{limit.merchantAccountId ?? limit.agreementId ?? '-'}</Mono>
                  </span>
                </TableCell>
                <TableCell className="font-mono">{limit.currencyCode}</TableCell>
                <TableCell>{limitText(limit.perTxKind, limit.perTxAmount, limit.currencyCode)}</TableCell>
                <TableCell>{limitText(limit.dailyKind, limit.dailyAmount, limit.currencyCode)}</TableCell>
                <TableCell>{period(limit.effectiveFrom, limit.effectiveTo)}</TableCell>
                <TableCell>{limit.endedAt === null ? <span className="text-muted-foreground">-</span> : <KstTime iso={limit.endedAt} />}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel name="권리 전파 상태">
        {propagation === null ? (
          <p className="text-sm text-muted-foreground">권리 전파 기록이 없습니다.</p>
        ) : (
          <dl className="grid gap-3 sm:grid-cols-3">
            <Item term="발행">
              <span className="flex flex-col"><span>{`최신 revision ${propagation.latestRevision}`}</span><KstTime iso={propagation.latestRevisionIssuedAt} /></span>
            </Item>
            <Item term="결제 서비스 수신">
              <span className="flex flex-col">
                <span>{`수신 확인 revision ${propagation.latestAckRevision ?? '-'}`}</span>
                {propagation.latestAckAt !== null && <KstTime iso={propagation.latestAckAt} />}
              </span>
            </Item>
            <Item term="효력">
              {propagation.effectConfirmedAt === null ? (
                <span className="text-muted-foreground">효력 확인 전</span>
              ) : (
                <span className="flex flex-col">
                  <KstTime iso={propagation.effectConfirmedAt} />
                  {propagation.effectBasis !== null && <span className="text-xs">{`근거: ${labelOf(propagation.effectBasis)}`}</span>}
                </span>
              )}
            </Item>
          </dl>
        )}
      </Panel>
    </>
  )
}

/**
 * 가맹점 조회(MERCHANT-OPS-001 §4.1·§7 AC 11 — 읽기 전용). 검색(가맹점 ID·사업자 ID·상태, 허용 역할에는 사업자등록번호) → 목록(keyset "다음") →
 * 행 "상세"로 상세 패널과 변경 이력(“이력 더 보기”). 사업자등록번호는 POST 본문으로만 보내고, 보낸 즉시 입력란을 비우며 URL·저장소·로그·화면에 남기지 않는다.
 * 화면은 마스킹 값만 보인다. 권한·감사·속도 제한은 서버가 판정한다(화면은 표시만).
 */
export function MerchantsScreen({ api, canSearchRegistrationNumber, onSessionEnded, openMerchantId, onOpenedMerchant }: Props) {
  const [merchantId, setMerchantId] = useState('')
  const [businessId, setBusinessId] = useState('')
  const [lifecycle, setLifecycle] = useState('')
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [registrationNumberError, setRegistrationNumberError] = useState(false)
  const [sent, setSent] = useState<SentSearch | null>(null)
  const [page, setPage] = useState<{ items: MerchantListItem[]; nextCursor: string | null } | null>(null)
  const [listError, setListError] = useState<ConsoleErrorCode | null>(null)
  const [listBusy, setListBusy] = useState(false)

  const [detail, setDetail] = useState<MerchantDetail | null>(null)
  const [detailError, setDetailError] = useState<ConsoleErrorCode | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [history, setHistory] = useState<{ items: MerchantHistoryEntry[]; nextCursor: number | null } | null>(null)
  const [historyError, setHistoryError] = useState<ConsoleErrorCode | null>(null)
  const [historyBusy, setHistoryBusy] = useState(false)
  // 다른 행을 연 뒤 늦게 도착한 앞 행 응답은 버린다.
  const openRequest = useRef(0)

  /** 세션 종료면 로그인으로, 아니면 고정 문구 code. */
  const failure = (e: unknown): ConsoleErrorCode | null => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) {
      onSessionEnded(code)
      return null
    }
    return code
  }

  const runSearch = async (input: MerchantSearchInput, next: SentSearch) => {
    setListError(null)
    setListBusy(true)
    try {
      const result = await api.merchantSearch(input)
      setSent(next)
      setPage(result)
    } catch (e) {
      setListError(failure(e))
    } finally {
      setListBusy(false)
    }
  }

  const search = async (event?: FormEvent) => {
    event?.preventDefault()
    const brn = canSearchRegistrationNumber ? registrationNumber : ''
    if (brn !== '' && !REGISTRATION_NUMBER.test(brn)) {
      setRegistrationNumberError(true)
      return
    }
    setRegistrationNumberError(false)
    const criteria: SentSearch['criteria'] = {}
    if (merchantId.trim() !== '') criteria.merchantId = merchantId.trim()
    if (businessId.trim() !== '') criteria.businessId = businessId.trim()
    if (lifecycle === 'ACTIVE' || lifecycle === 'TERMINATED') criteria.lifecycle = lifecycle
    // 번호는 이 요청 본문에만 — 보내는 즉시 입력란을 비운다(실패해도 다시 입력해야 한다).
    setRegistrationNumber('')
    setPage(null)
    setDetail(null)
    setDetailError(null)
    setHistory(null)
    openRequest.current += 1
    await runSearch(brn === '' ? criteria : { ...criteria, registrationNumber: brn }, { criteria, byRegistrationNumber: brn !== '' })
  }

  const nextPage = async () => {
    if (sent === null || sent.byRegistrationNumber || !page?.nextCursor) return
    await runSearch({ ...sent.criteria, after: page.nextCursor }, sent)
  }

  const loadHistory = async (id: string, after: number | undefined, request: number) => {
    setHistoryError(null)
    setHistoryBusy(true)
    try {
      const result = await api.merchantHistory(id, after)
      if (openRequest.current !== request) return
      setHistory((previous) => ({ items: after === undefined || previous === null ? result.items : [...previous.items, ...result.items], nextCursor: result.nextCursor }))
    } catch (e) {
      if (openRequest.current === request) setHistoryError(failure(e))
    } finally {
      if (openRequest.current === request) setHistoryBusy(false)
    }
  }

  const open = async (id: string) => {
    const request = ++openRequest.current
    setDetail(null)
    setDetailError(null)
    setHistory(null)
    setHistoryError(null)
    setDetailBusy(true)
    try {
      const found = await api.merchantDetail(id)
      if (openRequest.current !== request) return
      setDetail(found)
      setDetailBusy(false)
      // 상세를 받은 뒤에만 이력을 읽는다(상세 실패 — 예: 감사 불가 — 면 이력도 보이지 않는다).
      await loadHistory(id, undefined, request)
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    } finally {
      if (openRequest.current === request) setDetailBusy(false)
    }
  }

  // 요청받은 가맹점 상세를 한 번만 연다(StrictMode 이중 effect에도 상세 조회·감사는 한 번).
  const openedInitial = useRef(false)
  useEffect(() => {
    if (!openMerchantId || openedInitial.current) return
    openedInitial.current = true
    onOpenedMerchant?.()
    void open(openMerchantId)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 마운트 때 받은 요청 한 번만
  }, [openMerchantId])

  const moreHistory = async () => {
    if (detail === null || history?.nextCursor == null) return
    await loadHistory(detail.merchant.merchantId, history.nextCursor, openRequest.current)
  }

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="가맹점 조회" description="가맹점·사업자·MID 개통 상태를 조회합니다. 조회 전용이며, 상세 조회는 감사 기록에 남습니다." />

      <Card className="gap-4 py-5">
        <CardHeader className="px-5">
          <CardTitle className="text-sm">검색</CardTitle>
        </CardHeader>
        <CardContent className="px-5">
          <form onSubmit={search} className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Field label="가맹점 ID">
                {(describedBy) => (
                  <Input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} autoComplete="off" placeholder="mer_…" aria-describedby={describedBy} className="font-mono" />
                )}
              </Field>
              <Field label="사업자 ID">
                {(describedBy) => (
                  <Input value={businessId} onChange={(e) => setBusinessId(e.target.value)} autoComplete="off" placeholder="biz_…" aria-describedby={describedBy} className="font-mono" />
                )}
              </Field>
              <Field label="상태">
                {(describedBy) => (
                  <NativeSelect value={lifecycle} onChange={(e) => setLifecycle(e.target.value)} aria-describedby={describedBy}>
                    <option value="">전체</option>
                    {LIFECYCLES.map((value) => (
                      <option key={value} value={value}>{labelOf(value)}</option>
                    ))}
                  </NativeSelect>
                )}
              </Field>
              {canSearchRegistrationNumber && (
                <Field
                  label="사업자등록번호"
                  error={registrationNumberError ? '사업자등록번호는 숫자 10자리입니다.' : undefined}
                  hint="사업자등록번호 검색은 매번 감사 기록에 남습니다(번호는 마스킹 값만 기록). 정확히 일치하는 번호만 찾습니다."
                >
                  {(describedBy) => (
                    <Input
                      type="text"
                      inputMode="numeric"
                      maxLength={10}
                      autoComplete="off"
                      value={registrationNumber}
                      onChange={(e) => setRegistrationNumber(e.target.value)}
                      aria-describedby={describedBy}
                      aria-invalid={registrationNumberError || undefined}
                      placeholder="숫자 10자리"
                      className="font-mono"
                    />
                  )}
                </Field>
              )}
            </div>
            <div className="flex justify-end">
              <Button type="submit" aria-busy={listBusy}>
                {listBusy ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
                검색
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      {listError && <ErrorNotice>{ERROR_MESSAGES[listError]}</ErrorNotice>}

      {page && (
        <div className="flex flex-col gap-3">
          <Table aria-label="가맹점 목록">
            <TableHeader>
              <TableRow>
                <TableHead>가맹점</TableHead><TableHead>사업자</TableHead><TableHead>사업자등록번호(마스킹)</TableHead><TableHead>상태</TableHead>
                <TableHead>MID 수</TableHead><TableHead>권리 revision</TableHead><TableHead>등록일</TableHead><TableHead><span className="sr-only">작업</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.items.length === 0 && <EmptyRow columns={8} message="조건에 맞는 가맹점이 없습니다." />}
              {page.items.map((row) => (
                <TableRow key={row.merchantId} aria-label={row.merchantId} className={detail?.merchant.merchantId === row.merchantId ? 'bg-muted/50' : undefined}>
                  <TableCell><span className="flex flex-col"><span className="font-medium">{row.merchantName}</span><Mono>{row.merchantId}</Mono></span></TableCell>
                  <TableCell><span className="flex flex-col"><span>{row.businessName}</span><Mono>{row.businessId}</Mono></span></TableCell>
                  <TableCell className="font-mono">{row.registrationNumberMasked}</TableCell>
                  <TableCell><StatusBadge code={row.lifecycle} /></TableCell>
                  <TableCell data-col="midCount" className="font-mono">{row.midCount}</TableCell>
                  <TableCell data-col="entitlementRevision" className="font-mono">{row.entitlementRevision}</TableCell>
                  <TableCell><KstTime iso={row.createdAt} /></TableCell>
                  <TableCell>
                    <Button type="button" variant="outline" size="sm" onClick={() => open(row.merchantId)}>상세</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {page.nextCursor !== null && (
            sent?.byRegistrationNumber ? (
              <p className="text-xs text-muted-foreground">결과가 더 있습니다. 사업자등록번호는 다시 보내지 않으니, 나머지는 사업자 ID로 검색해 주세요.</p>
            ) : (
              <div className="flex justify-end">
                <Button type="button" variant="outline" onClick={nextPage} aria-busy={listBusy}>다음</Button>
              </div>
            )
          )}
        </div>
      )}

      {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
      {detailError && <ErrorNotice>{ERROR_MESSAGES[detailError]}</ErrorNotice>}
      {detail && (
        <div className="flex flex-col gap-4">
          <h3 className="text-sm font-bold">
            {'가맹점 상세 '}
            <span className="font-mono text-xs text-muted-foreground">{detail.merchant.merchantId}</span>
          </h3>
          <DetailPanels detail={detail} />
          <Panel name="변경 이력">
            {historyError && <ErrorNotice>{ERROR_MESSAGES[historyError]}</ErrorNotice>}
            {history && (
              <Table aria-label="변경 이력 목록">
                <TableHeader>
                  <TableRow>
                    <TableHead>순번</TableHead><TableHead>시각</TableHead><TableHead>연산</TableHead><TableHead>대상</TableHead><TableHead>변경</TableHead><TableHead>행위자</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {history.items.length === 0 && <EmptyRow columns={6} message="변경 이력이 없습니다." />}
                  {history.items.map((entry) => (
                    <TableRow key={entry.seq} aria-label={String(entry.seq)}>
                      <TableCell className="font-mono">{entry.seq}</TableCell>
                      <TableCell><KstTime iso={entry.occurredAt} /></TableCell>
                      <TableCell><Mono>{entry.operation}</Mono></TableCell>
                      <TableCell><span className="flex flex-col"><span>{entry.aggregateType}</span><Mono>{entry.aggregateId}</Mono></span></TableCell>
                      <TableCell><KindLabel code={entry.changeType} /></TableCell>
                      <TableCell><Mono>{entry.actorRef}</Mono></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {historyBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />이력을 불러오는 중입니다.</p>}
            {history?.nextCursor != null && (
              <div className="flex justify-end">
                <Button type="button" variant="outline" size="sm" onClick={moreHistory} aria-busy={historyBusy}>이력 더 보기</Button>
              </div>
            )}
          </Panel>
        </div>
      )}
    </section>
  )
}
