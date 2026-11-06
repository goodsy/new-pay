import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import type {
  AcquisitionFileDetail, AcquisitionFileItem, AcquisitionRecord, AcquisitionRecordPage, AcquisitionSearchInput, AcquisitionSearchPage, AdminApi,
} from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { SearchIcon } from '../components/icons'
import { EmptyRow, Field, KstTime, Spinner } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { Badge, type BadgeVariant } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { formatMinor } from '../lib/format'

/** 기간 상한(I-3 — Demo 값, 운영값 아님. 서버가 판정하고 화면은 미리 안내만 한다). */
const MAX_PERIOD_DAYS = 31
const DAY_MS = 24 * 60 * 60 * 1000
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/

type Named = Readonly<Record<string, { label: string; tone: BadgeVariant }>>

/** 3축 상태(FINANCIAL 저장 값 — 서로 추정하지 않는다, D-ACQ-004). 화면 고유 이름(같은 code가 다른 화면에서 다른 뜻 — 공용 labels에 넣지 않는다). */
const TRANSMISSION: Named = {
  SEALED: { label: '봉인됨(전송 전)', tone: 'secondary' },
  TRANSMITTING: { label: '전송 중', tone: 'warning' },
  TRANSMITTED: { label: '전송 완료', tone: 'success' },
  TRANSMISSION_UNKNOWN: { label: '전송 결과 모름', tone: 'warning' },
  TRANSMISSION_QUARANTINED: { label: '전송 격리', tone: 'destructive' },
}
const RECEIPT: Named = {
  NONE: { label: '수신 확인 전', tone: 'secondary' },
  RECEIVED: { label: '수신 확인', tone: 'success' },
  FILE_REJECTED: { label: '파일 전체 거절', tone: 'destructive' },
  RECEIPT_CONFLICT: { label: '수신 내용 불일치', tone: 'destructive' },
}
const RESULT: Named = {
  AWAITING: { label: '결과 대기', tone: 'secondary' },
  COMPLETE: { label: '결과 완료', tone: 'success' },
  DEADLINE_PASSED: { label: '결과 마감 지남', tone: 'warning' },
}
const RECORD_STATUS: Named = {
  AWAITING: { label: '결과 대기', tone: 'secondary' },
  ACCEPTED: { label: '매입 확정', tone: 'success' },
  REJECTED: { label: '거절', tone: 'destructive' },
  FILE_REJECTED: { label: '파일 거절', tone: 'destructive' },
  UNKNOWN: { label: '결과 모름', tone: 'warning' },
  CONFLICT: { label: '상충', tone: 'destructive' },
}
const RESULT_ROW: Named = {
  ACCEPTED: { label: '수락', tone: 'success' },
  REJECTED: { label: '거절', tone: 'destructive' },
  UNKNOWN: { label: '결과 모름(마감)', tone: 'warning' },
  CONFLICT: { label: '상충', tone: 'destructive' },
}

/** "확인 필요" 사유(서버 계산 — ACQ-VIEW-001 §4.4). 화면은 계산하지 않고 이름과 설명만 붙인다. */
const ATTENTION: Readonly<Record<string, { badge: string; text: string }>> = {
  TRANSMISSION_UNKNOWN: { badge: '전송 결과 모름', text: '전송 결과를 알 수 없습니다. 자동으로 다시 확인하고 있으며, 대사 대상입니다.' },
  TRANSMISSION_QUARANTINED: { badge: '전송 격리', text: '같은 파일 ID에 다른 내용이 있어 격리했습니다. 운영자 확인이 필요합니다.' },
  RECEIPT_CONFLICT: { badge: '수신 불일치', text: '기관이 받은 내용이 봉인한 파일과 다릅니다. 격리했습니다.' },
  RESPONSE_QUARANTINED: { badge: '격리된 응답', text: '자동으로 반영하지 않고 격리한 기관 응답이 있습니다.' },
  RECORD_UNKNOWN: { badge: '결과 모름', text: '결과 마감까지 결과가 오지 않은 레코드가 있습니다. 대사 대상입니다.' },
  RECORD_CONFLICT: { badge: '상충 결과', text: '같은 레코드에 서로 다른 결과가 왔습니다. 격리했습니다.' },
  RESULT_DEADLINE_PASSED: { badge: '결과 마감 지남', text: '결과 마감이 지났습니다. 늦게 오는 결과는 자동으로 반영됩니다.' },
  RESULT_OVERDUE: { badge: '결과 지연', text: '결과 마감 시각이 지났지만 아직 마감 처리 전입니다.' },
}
const REACQUISITION = { badge: '재매입 검토 대상', text: '거절된 레코드가 있습니다. 재매입은 2인 승인 화면에서 진행합니다(이 화면은 조회 전용).' }

const RECORD_TYPE: Readonly<Record<string, string>> = { SALE: '매출', CANCEL: '취소' }
const READ_UNAVAILABLE = ERROR_MESSAGES.FINANCIAL_UNAVAILABLE
const message = (code: ConsoleErrorCode) => ERROR_MESSAGES[code] ?? READ_UNAVAILABLE

function todayKst(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 10)
}

function daysBefore(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00.000Z`) - days * DAY_MS).toISOString().slice(0, 10)
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>

function Tone({ table, code }: { table: Named; code: string }) {
  const named = table[code]
  return <Badge variant={named?.tone ?? 'secondary'} title={code}>{named?.label ?? code}</Badge>
}

function amount(minor: number, currency: string) {
  return Number.isSafeInteger(minor) ? formatMinor(minor, currency) : '표시할 수 없는 금액'
}

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

/** 확인 필요 배지 + 재매입 검토 배지(목록·상세 공용). */
function Marks({ file }: { file: AcquisitionFileItem }) {
  if (file.attention.length === 0 && !file.reacquisitionCandidate) return <span className="text-xs text-muted-foreground">-</span>
  return (
    <span className="flex flex-wrap gap-1">
      {file.attention.map((code) => (
        <Badge key={code} variant="warning" title={code}>{`확인 필요: ${ATTENTION[code]?.badge ?? code}`}</Badge>
      ))}
      {file.reacquisitionCandidate && <Badge variant="outline" title="REACQUISITION_CANDIDATE">{REACQUISITION.badge}</Badge>}
    </span>
  )
}

function Counts({ file }: { file: AcquisitionFileItem }) {
  const c = file.recordStatusCounts
  const parts = [
    ['매입 확정', c.accepted], ['거절', c.rejected + c.fileRejected], ['결과 모름', c.unknown], ['상충', c.conflict], ['결과 대기', c.awaiting],
  ].filter(([, n]) => (n as number) > 0)
  return <span className="text-xs whitespace-nowrap">{parts.length === 0 ? '레코드 없음' : parts.map(([label, n]) => `${label} ${n}`).join(' · ')}</span>
}

function RecordRows({ records }: { records: AcquisitionRecord[] }) {
  return (
    <>
      {records.map((r) => (
        <TableRow key={r.seq} aria-label={`레코드 ${r.seq}`}>
          <TableCell className="font-mono text-xs">{r.seq}</TableCell>
          <TableCell>
            <span className="flex flex-col">
              <span>{RECORD_TYPE[r.recordType] ?? r.recordType}{r.generation > 0 ? ` (재매입 ${r.generation}세대)` : ''}</span>
              <Mono>{r.transactionId}</Mono>
              {r.originalTransactionId !== null && <span className="text-xs text-muted-foreground">{`원거래 ${r.originalTransactionId}`}</span>}
            </span>
          </TableCell>
          <TableCell><Mono>{r.merchantAccountId}</Mono></TableCell>
          <TableCell>
            <span className="flex flex-col">
              <span>{amount(r.recordAmountMinor, r.currency)}</span>
              {r.nettedCancelAmountMinor > 0 && <span className="text-xs text-muted-foreground">{`승인 ${amount(r.approvedAmountMinor, r.currency)} − 상계 ${amount(r.nettedCancelAmountMinor, r.currency)}`}</span>}
            </span>
          </TableCell>
          <TableCell className="font-mono text-xs">{r.institutionTransactionDate}</TableCell>
          <TableCell><Tone table={RECORD_STATUS} code={r.status} /></TableCell>
          <TableCell>
            {r.results.length === 0 ? (
              <span className="text-xs text-muted-foreground">결과 없음</span>
            ) : (
              <ol className="flex flex-col gap-1">
                {r.results.map((x) => (
                  <li key={x.entryNo} className="flex flex-wrap items-center gap-1 text-xs">
                    <Tone table={RESULT_ROW} code={x.result} />
                    {x.observedResult !== null && <span>{`(관찰 ${x.observedResult})`}</span>}
                    {x.reasonCode !== null && <Mono>{x.reasonCode}</Mono>}
                    <span className="text-muted-foreground">{x.responseRef === null ? '결과 마감' : `${x.responseRef.kind} ${x.responseRef.sequenceNo}회`}</span>
                    <KstTime iso={x.recognizedAt} />
                  </li>
                ))}
              </ol>
            )}
          </TableCell>
        </TableRow>
      ))}
    </>
  )
}

function DetailPanels({ detail, records, recordStatus, onRecordStatus, onMoreRecords, recordsBusy }: {
  detail: AcquisitionFileDetail
  records: AcquisitionRecordPage
  recordStatus: string
  onRecordStatus: (status: string) => void
  onMoreRecords: () => void
  recordsBusy: boolean
}) {
  const { file, attempts, responses } = detail
  return (
    <>
      <Panel title="파일 요약">
        <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Item term="파일 ID"><Mono>{file.fileId}</Mono></Item>
          <Item term="업무일·매입사"><span className="font-mono text-xs">{`${file.businessDate} · ${file.acquirerCode} · ${file.environment} · v${file.fileVersion}`}</span></Item>
          <Item term="건수">{`매출 ${file.saleCount}건 · 취소 ${file.cancelCount}건`}</Item>
          <Item term="순액">{amount(file.netAmountMinor, file.currency)}</Item>
          <Item term="마감 정책"><span className="font-mono text-xs">{file.cutoffPolicyVersion}</span></Item>
          <Item term="마감 시각"><KstTime iso={file.cutoffAt} /></Item>
          <Item term="봉인 시각"><KstTime iso={file.sealedAt} /></Item>
          <Item term="봉인 hash(SHA-256)"><span title={file.contentSha256} className="font-mono text-xs">{`${file.contentSha256.slice(0, 16)}… (${file.contentBytes}바이트)`}</span></Item>
        </dl>
      </Panel>

      <Panel title="상태">
        <dl className="grid gap-3 sm:grid-cols-3">
          <Item term="전송">
            <span className="flex flex-col gap-1">
              <Tone table={TRANSMISSION} code={file.transmissionState} />
              {file.transmittedAt !== null && <span className="text-xs">완료 <KstTime iso={file.transmittedAt} /></span>}
            </span>
          </Item>
          <Item term="수신 확인(ACK)"><Tone table={RECEIPT} code={file.receiptState} /></Item>
          <Item term="결과"><Tone table={RESULT} code={file.resultState} /></Item>
        </dl>
        {file.attention.length === 0 && !file.reacquisitionCandidate ? (
          <p className="text-sm text-muted-foreground">확인할 일이 없습니다.</p>
        ) : (
          <ul aria-label="확인 필요 사유" className="flex flex-col gap-1.5">
            {file.attention.map((code) => (
              <li key={code} className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant="warning" title={code}>{ATTENTION[code]?.badge ?? code}</Badge>
                <span>{ATTENTION[code]?.text ?? code}{code === 'RESPONSE_QUARANTINED' ? ` (${responses.quarantined}건)` : ''}</span>
              </li>
            ))}
            {file.reacquisitionCandidate && (
              <li className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant="outline">{REACQUISITION.badge}</Badge>
                <span>{REACQUISITION.text}</span>
              </li>
            )}
          </ul>
        )}
      </Panel>

      <Panel title="레코드별 결과">
        <div className="flex items-end justify-between gap-3">
          <Field label="레코드 상태">
            {(describedBy) => (
              <NativeSelect value={recordStatus} onChange={(e) => onRecordStatus(e.target.value)} aria-describedby={describedBy}>
                <option value="">전체</option>
                {Object.entries(RECORD_STATUS).map(([code, named]) => <option key={code} value={code}>{named.label}</option>)}
              </NativeSelect>
            )}
          </Field>
          <p className="text-xs text-muted-foreground">조회 시각 <KstTime iso={detail.readAt} /></p>
        </div>
        <Table aria-label="레코드 목록">
          <TableHeader>
            <TableRow>
              <TableHead>순번</TableHead><TableHead>구분·거래</TableHead><TableHead>MID</TableHead><TableHead>레코드 금액</TableHead><TableHead>기관 거래일</TableHead>
              <TableHead>현재 상태</TableHead><TableHead>결과 이력</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {records.items.length === 0 && <EmptyRow columns={7} message="조건에 맞는 레코드가 없습니다." />}
            <RecordRows records={records.items} />
          </TableBody>
        </Table>
        {records.nextCursor !== null && (
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={onMoreRecords} aria-busy={recordsBusy}>레코드 더 보기</Button>
          </div>
        )}
      </Panel>

      <Panel title="전송 시도">
        <Table aria-label="전송 시도 목록">
          <TableHeader>
            <TableRow><TableHead>시도</TableHead><TableHead>시작</TableHead><TableHead>결과</TableHead><TableHead>근거·사유</TableHead><TableHead>결과 상태</TableHead></TableRow>
          </TableHeader>
          <TableBody>
            {attempts.latest.length === 0 && <EmptyRow columns={5} message="전송 시도가 없습니다." />}
            {attempts.latest.map((a) => (
              <TableRow key={a.attemptNo} aria-label={`전송 시도 ${a.attemptNo}`}>
                <TableCell className="font-mono text-xs">{a.attemptNo}</TableCell>
                <TableCell><KstTime iso={a.claimedAt} /></TableCell>
                <TableCell><Mono>{a.outcome ?? '진행 중'}</Mono></TableCell>
                <TableCell><Mono>{a.evidence ?? a.reason ?? '-'}</Mono></TableCell>
                <TableCell>{a.resultingState === null ? '-' : <Tone table={TRANSMISSION} code={a.resultingState} />}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {attempts.total > attempts.latest.length && <p className="text-xs text-muted-foreground">{`최근 ${attempts.latest.length}건만 표시합니다(전체 ${attempts.total}건).`}</p>}
      </Panel>

      <Panel title="기관 응답 기록">
        <Table aria-label="응답 기록 목록">
          <TableHeader>
            <TableRow><TableHead>응답</TableHead><TableHead>처리</TableHead><TableHead>ACK 결과</TableHead><TableHead>근거·격리 사유</TableHead><TableHead>받은 시각</TableHead></TableRow>
          </TableHeader>
          <TableBody>
            {responses.latest.length === 0 && <EmptyRow columns={5} message="받은 기관 응답이 없습니다." />}
            {responses.latest.map((r) => (
              <TableRow key={`${r.kind}-${r.sequenceNo}-${r.observedAt}`} aria-label={`${r.kind} ${r.sequenceNo}`}>
                <TableCell className="font-mono text-xs">{`${r.kind} ${r.sequenceNo}회`}</TableCell>
                <TableCell>{r.disposition === 'QUARANTINED' ? <Badge variant="destructive">격리</Badge> : <Badge variant="success">반영</Badge>}</TableCell>
                <TableCell><Mono>{r.ackOutcome === null ? '-' : `${r.ackOutcome}${r.ackReasonCode ? ` (${r.ackReasonCode})` : ''}`}</Mono></TableCell>
                <TableCell><Mono>{r.quarantineReason ?? r.evidence ?? '-'}</Mono></TableCell>
                <TableCell><KstTime iso={r.observedAt} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {responses.total > responses.latest.length && <p className="text-xs text-muted-foreground">{`최근 ${responses.latest.length}건만 표시합니다(전체 ${responses.total}건).`}</p>}
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
 * 매입 현황(ACQ-VIEW-001 §4.6 — 조회 전용, 조작 버튼 없음). 업무일 기간(KST 달력일, 최대 31일) + 매입사·환경·3축 상태·"확인 필요만" 필터, 또는 파일 ID 정확 조회.
 * 목록은 최신 업무일순 keyset("더 보기"로 이어 붙임). "상세"로 파일 요약·3축 상태·확인 필요 사유·레코드별 결과·전송 시도·기관 응답 기록을 연다 — 상세·레코드 쪽은 서버가 감사 기록을 남긴
 * 뒤에만 온다(D-ACQV-001). 승인번호·기관 거래번호·발급사·카드 종류·할부는 서버가 보내지 않는다(D-ACQV-002). 권한·형식·감사·속도 제한은 서버가 판정한다. 저장소·URL에 아무것도 남기지 않는다.
 */
export function AcquisitionsScreen({ api, onSessionEnded, now = Date.now }: Props) {
  const today = todayKst(now())
  const [mode, setMode] = useState<'period' | 'file'>('period')
  const [fromDate, setFromDate] = useState(daysBefore(today, 6))
  const [toDate, setToDate] = useState(today)
  const [acquirerCode, setAcquirerCode] = useState('')
  const [environment, setEnvironment] = useState('')
  const [transmissionState, setTransmissionState] = useState('')
  const [receiptState, setReceiptState] = useState('')
  const [resultState, setResultState] = useState('')
  const [needsAttention, setNeedsAttention] = useState(false)
  const [fileId, setFileId] = useState('')
  const [inputError, setInputError] = useState<string | null>(null)
  const [sent, setSent] = useState<AcquisitionSearchInput | null>(null)
  const [page, setPage] = useState<AcquisitionSearchPage | null>(null)
  const [listError, setListError] = useState<ConsoleErrorCode | null>(null)
  const [listBusy, setListBusy] = useState(false)
  const [detail, setDetail] = useState<AcquisitionFileDetail | null>(null)
  const [records, setRecords] = useState<AcquisitionRecordPage | null>(null)
  const [recordStatus, setRecordStatus] = useState('')
  const [recordsBusy, setRecordsBusy] = useState(false)
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
  const criteria = (): AcquisitionSearchInput | string => {
    if (mode === 'file') {
      const id = fileId.trim()
      return id === '' ? '파일 ID를 입력해 주세요.' : { fileId: id }
    }
    if (!DATE.test(fromDate) || !DATE.test(toDate)) return '업무일 기간을 입력해 주세요.'
    const days = (Date.parse(toDate) - Date.parse(fromDate)) / DAY_MS + 1
    if (days < 1) return '시작일이 종료일보다 늦습니다.'
    if (days > MAX_PERIOD_DAYS) return `조회 기간은 최대 ${MAX_PERIOD_DAYS}일입니다.`
    const input: AcquisitionSearchInput = { businessDateFrom: fromDate, businessDateTo: toDate }
    const acquirer = acquirerCode.trim()
    if (acquirer !== '') input.acquirerCode = acquirer
    if (environment === 'TEST' || environment === 'LIVE') input.environment = environment
    if (transmissionState !== '') input.transmissionState = transmissionState
    if (receiptState !== '') input.receiptState = receiptState
    if (resultState !== '') input.resultState = resultState
    if (needsAttention) input.needsAttention = true
    return input
  }

  const runSearch = async (input: AcquisitionSearchInput, base: AcquisitionSearchInput, append: boolean) => {
    setListError(null)
    setListBusy(true)
    try {
      const result = await api.acquisitionSearch(input)
      setSent(base)
      setPage((previous) => (append && previous ? { items: [...previous.items, ...result.items], nextCursor: result.nextCursor } : result))
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
    setRecords(null)
    setDetailError(null)
    openRequest.current += 1
    await runSearch(built, built, false)
  }

  const more = async () => {
    if (sent === null || !page?.nextCursor) return
    await runSearch({ ...sent, after: page.nextCursor }, sent, true)
  }

  const open = async (id: string) => {
    const request = ++openRequest.current
    setDetail(null)
    setRecords(null)
    setRecordStatus('')
    setDetailError(null)
    setDetailBusy(true)
    setRecordsBusy(false)
    try {
      const found = await api.acquisitionDetail(id)
      if (openRequest.current === request) {
        setDetail(found)
        setRecords(found.records)
      }
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    } finally {
      if (openRequest.current === request) setDetailBusy(false)
    }
  }

  const loadRecords = async (status: string, after: number, append: boolean) => {
    if (detail === null) return
    const request = openRequest.current
    setRecordsBusy(true)
    setDetailError(null)
    try {
      const next = await api.acquisitionRecords(detail.file.fileId, { ...(status === '' ? {} : { status }), ...(after > 0 ? { afterSeq: after } : {}) })
      if (openRequest.current === request) {
        setRecords((previous) => (append && previous ? { items: [...previous.items, ...next.items], nextCursor: next.nextCursor } : next))
      }
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    } finally {
      // 앞 파일의 늦은 응답은 지금 연 파일의 진행 표시를 끄지 않는다(리뷰 NIT — 결과와 같은 openRequest 검사).
      if (openRequest.current === request) setRecordsBusy(false)
    }
  }

  const stateSelect = (label: string, value: string, onChange: (v: string) => void, table: Named) => (
    <Field label={label}>
      {(describedBy) => (
        <NativeSelect value={value} onChange={(e) => onChange(e.target.value)} aria-describedby={describedBy}>
          <option value="">전체</option>
          {Object.entries(table).map(([code, named]) => <option key={code} value={code}>{named.label}</option>)}
        </NativeSelect>
      )}
    </Field>
  )

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="매입 현황" description="매입 파일의 전송·수신·결과 상태를 조회합니다. 조회 전용이며, 파일 상세 조회는 감사 기록에 남습니다." />

      <Card className="gap-4 py-5">
        <CardHeader className="px-5">
          <CardTitle className="text-sm">검색</CardTitle>
        </CardHeader>
        <CardContent className="px-5">
          <form onSubmit={search} className="flex flex-col gap-4">
            <Field label="조회 방식">
              {(describedBy) => (
                <NativeSelect value={mode} onChange={(e) => { setMode(e.target.value as typeof mode); setInputError(null) }} aria-describedby={describedBy}>
                  <option value="period">업무일·조건</option>
                  <option value="file">파일 ID</option>
                </NativeSelect>
              )}
            </Field>
            {mode === 'file' ? (
              <Field label="파일 ID">
                {(describedBy) => (
                  <Input value={fileId} onChange={(e) => setFileId(e.target.value)} autoComplete="off" placeholder="ACQ.…" aria-describedby={describedBy} className="font-mono" />
                )}
              </Field>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Field label="업무일 시작(KST)" hint={`최대 ${MAX_PERIOD_DAYS}일(Demo 기준)`}>
                  {(describedBy) => <Input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} aria-describedby={describedBy} />}
                </Field>
                <Field label="업무일 종료(KST)">
                  {(describedBy) => <Input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} aria-describedby={describedBy} />}
                </Field>
                <Field label="매입사 코드">
                  {(describedBy) => (
                    <Input value={acquirerCode} onChange={(e) => setAcquirerCode(e.target.value)} autoComplete="off" aria-describedby={describedBy} className="font-mono" />
                  )}
                </Field>
                <Field label="환경">
                  {(describedBy) => (
                    <NativeSelect value={environment} onChange={(e) => setEnvironment(e.target.value)} aria-describedby={describedBy}>
                      <option value="">전체</option>
                      <option value="TEST">TEST</option>
                      <option value="LIVE">LIVE</option>
                    </NativeSelect>
                  )}
                </Field>
                {stateSelect('전송 상태', transmissionState, setTransmissionState, TRANSMISSION)}
                {stateSelect('수신 상태', receiptState, setReceiptState, RECEIPT)}
                {stateSelect('결과 상태', resultState, setResultState, RESULT)}
                <label className="flex items-center gap-2 self-end pb-2 text-sm">
                  <input type="checkbox" checked={needsAttention} onChange={(e) => setNeedsAttention(e.target.checked)} />
                  확인 필요만
                </label>
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
          <Table aria-label="매입 파일 목록">
            <TableHeader>
              <TableRow>
                <TableHead>업무일</TableHead><TableHead>파일</TableHead><TableHead>건수·순액</TableHead><TableHead>전송·수신·결과</TableHead>
                <TableHead>레코드 결과</TableHead><TableHead>표시</TableHead><TableHead><span className="sr-only">작업</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.items.length === 0 && <EmptyRow columns={7} message="조건에 맞는 매입 파일이 없습니다." />}
              {page.items.map((row) => (
                <TableRow key={row.fileId} aria-label={row.fileId} className={detail?.file.fileId === row.fileId ? 'bg-muted/50' : undefined}>
                  <TableCell className="font-mono text-xs whitespace-nowrap">{row.businessDate}</TableCell>
                  <TableCell>
                    <span className="flex flex-col">
                      <span className="font-mono text-xs whitespace-nowrap">{row.fileId}</span>
                      <span className="text-xs text-muted-foreground">{`${row.acquirerCode} · ${row.environment} · v${row.fileVersion}`}</span>
                    </span>
                  </TableCell>
                  <TableCell className="whitespace-nowrap">
                    <span className="flex flex-col">
                      <span>{amount(row.netAmountMinor, row.currency)}</span>
                      <span className="text-xs text-muted-foreground">{`매출 ${row.saleCount} · 취소 ${row.cancelCount}`}</span>
                    </span>
                  </TableCell>
                  <TableCell>
                    <span className="flex flex-col items-start gap-1">
                      <Tone table={TRANSMISSION} code={row.transmissionState} />
                      <Tone table={RECEIPT} code={row.receiptState} />
                      <Tone table={RESULT} code={row.resultState} />
                    </span>
                  </TableCell>
                  <TableCell><Counts file={row} /></TableCell>
                  <TableCell><Marks file={row} /></TableCell>
                  <TableCell>
                    <Button type="button" variant="outline" size="sm" onClick={() => open(row.fileId)}>상세</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="text-xs text-muted-foreground">승인번호·기관 거래번호·카드 정보는 이 화면에 표시하지 않습니다. 거래 ID로 거래 조회에서 확인하세요.</p>
          {page.nextCursor !== null && (
            <div className="flex justify-end">
              <Button type="button" variant="outline" onClick={more} aria-busy={listBusy}>더 보기</Button>
            </div>
          )}
        </div>
      )}

      {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
      {detailError && <ErrorNotice>{message(detailError)}</ErrorNotice>}
      {detail && records && (
        <div className="flex flex-col gap-4">
          <h3 className="text-sm font-bold">
            {'매입 파일 상세 '}
            <span className="font-mono text-xs text-muted-foreground">{detail.file.fileId}</span>
          </h3>
          <DetailPanels
            detail={detail}
            records={records}
            recordStatus={recordStatus}
            recordsBusy={recordsBusy}
            onRecordStatus={(status) => {
              setRecordStatus(status)
              void loadRecords(status, 0, false)
            }}
            onMoreRecords={() => {
              if (records.nextCursor !== null) void loadRecords(recordStatus, records.nextCursor, true)
            }}
          />
        </div>
      )}
    </section>
  )
}
