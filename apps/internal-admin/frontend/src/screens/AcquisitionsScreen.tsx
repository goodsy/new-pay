import { useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type {
  AcquisitionFileDetail, AcquisitionFileItem, AcquisitionRecord, AcquisitionRecordPage, AcquisitionSearchInput, AcquisitionSearchPage, AdminApi,
} from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { KstTime, Spinner } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import type { BadgeVariant } from '../components/ui/badge'
import { formatMinor } from '../lib/format'
import { toneTagClass } from '../lib/statusTone'
import { ReacquisitionRequest } from '../components/ReacquisitionRequest'

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
  // ACQ-ADMIN-001 B2(§15 L-9): 재매입 파일의 거래가 다른 세대에서 이미 매입 확정 — 보내지 않고 보류(대사, 자동 해제 없음).
  TRANSMISSION_WITHHELD: { label: '전송 보류(대사)', tone: 'destructive' },
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
  TRANSMISSION_WITHHELD: { badge: '전송 보류', text: '이 재매입 파일의 거래가 다른 세대에서 이미 매입 확정되어 보내지 않고 보류했습니다. 대사 대상입니다.' },
  RECEIPT_CONFLICT: { badge: '수신 불일치', text: '기관이 받은 내용이 봉인한 파일과 다릅니다. 격리했습니다.' },
  RESPONSE_QUARANTINED: { badge: '격리된 응답', text: '자동으로 반영하지 않고 격리한 기관 응답이 있습니다.' },
  RECORD_UNKNOWN: { badge: '결과 모름', text: '결과 마감까지 결과가 오지 않은 레코드가 있습니다. 대사 대상입니다.' },
  RECORD_CONFLICT: { badge: '상충 결과', text: '같은 레코드에 서로 다른 결과가 왔습니다. 격리했습니다.' },
  RESULT_DEADLINE_PASSED: { badge: '결과 마감 지남', text: '결과 마감이 지났습니다. 늦게 오는 결과는 자동으로 반영됩니다.' },
  RESULT_OVERDUE: { badge: '결과 지연', text: '결과 마감 시각이 지났지만 아직 마감 처리 전입니다.' },
}
/** 재매입 배지(B1-A 리뷰 m-5 — §5.1): 이 화면에 요청 패널이 생겨 "조회 전용" 문구를 역할별로 바꿨다. OPERATOR는 아래 패널에서 요청, 그 밖 역할은 누가 요청·승인하는지만 안내. */
const REACQUISITION = {
  badge: '재매입 검토 대상',
  operatorText: '거절된 레코드가 있습니다. 아래에서 재매입을 요청하면 승인함에서 다른 관리자(APPROVER)가 승인합니다.',
  viewerText: '거절된 레코드가 있습니다. 재매입은 운영자(OPERATOR)가 이 화면에서 요청하고, 다른 관리자(APPROVER)가 승인함에서 승인합니다.',
}
/** 재매입 진행 중(ACQ-ADMIN-001 B2 — §5.1 3상태): 새 세대가 있고 검토할 거절은 없다. 새 세대가 매입 확정되면 서버가 두 표시를 모두 끈다(배지 없음). */
const REACQUISITION_IN_PROGRESS = {
  badge: '재매입 진행 중',
  text: '새 버전 파일로 재매입이 진행 중입니다. 매입사 결과가 매입 확정이면 이 표시는 사라집니다.',
}

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

/** 3축·레코드·결과 이름표 → 시안 `.tag`(색 의미는 표의 tone — StatusBadge와 같은 statusTone 규칙, 문구는 화면 고유 이름, title은 원래 code). */
function Tone({ table, code }: { table: Named; code: string }) {
  const named = table[code]
  return <span className={toneTagClass(named?.tone ?? 'secondary')} title={code}>{named?.label ?? code}</span>
}

function amount(minor: number, currency: string) {
  return Number.isSafeInteger(minor) ? formatMinor(minor, currency) : '표시할 수 없는 금액'
}

/**
 * 상세 패널 하나(`section` + 이름 → region). ADMIN-UI-WORKSPACE-001(매입·대사): 거래 조회와 같은 시안 `.card` > `.cardtop`(h4 — 상세 제목 h3 아래 단계) → `.cardbody`.
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

/** 항목 이름·값(시안 `.detailgrid` label 13px·500 / 값 14px — 거래 조회 상세와 같은 값, 정의 목록 의미는 유지). */
function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <dt className="text-[13px] font-medium text-subtle">{term}</dt>
      <dd className="text-sm text-heading">{children}</dd>
    </div>
  )
}

/** 확인 필요 배지 + 재매입 검토 배지(목록·상세 공용). 색은 원래 Badge tone 그대로(확인 필요 warning → orange, 재매입 outline·secondary → 회색). */
function Marks({ file }: { file: AcquisitionFileItem }) {
  if (file.attention.length === 0 && !file.reacquisitionCandidate && !file.reacquisitionInProgress) return <span className="subtle">-</span>
  return (
    <span className="flex flex-wrap gap-1">
      {file.attention.map((code) => (
        <span key={code} className="tag orange" title={code}>{`확인 필요: ${ATTENTION[code]?.badge ?? code}`}</span>
      ))}
      {file.reacquisitionCandidate && <span className="tag" title="REACQUISITION_CANDIDATE">{REACQUISITION.badge}</span>}
      {file.reacquisitionInProgress && <span className="tag" title="REACQUISITION_IN_PROGRESS">{REACQUISITION_IN_PROGRESS.badge}</span>}
    </span>
  )
}

function Counts({ file }: { file: AcquisitionFileItem }) {
  const c = file.recordStatusCounts
  const parts = [
    ['매입 확정', c.accepted], ['거절', c.rejected + c.fileRejected], ['결과 모름', c.unknown], ['상충', c.conflict], ['결과 대기', c.awaiting],
  ].filter(([, n]) => (n as number) > 0)
  return <span>{parts.length === 0 ? '레코드 없음' : parts.map(([label, n]) => `${label} ${n}`).join(' · ')}</span>
}

function RecordRows({ records }: { records: AcquisitionRecord[] }) {
  return (
    <>
      {records.map((r) => (
        <tr key={r.seq} aria-label={`레코드 ${r.seq}`}>
          <td>{r.seq}</td>
          <td>
            <strong>{RECORD_TYPE[r.recordType] ?? r.recordType}{r.generation > 0 ? ` (재매입 ${r.generation}세대)` : ''}</strong>
            <small>{r.transactionId}</small>
            {r.originalTransactionId !== null && <small>{`원거래 ${r.originalTransactionId}`}</small>}
          </td>
          <td>{r.merchantAccountId}</td>
          <td className="amount">
            {amount(r.recordAmountMinor, r.currency)}
            {r.nettedCancelAmountMinor > 0 && <small>{`승인 ${amount(r.approvedAmountMinor, r.currency)} − 상계 ${amount(r.nettedCancelAmountMinor, r.currency)}`}</small>}
          </td>
          <td>{r.institutionTransactionDate}</td>
          <td><Tone table={RECORD_STATUS} code={r.status} /></td>
          <td>
            {r.results.length === 0 ? (
              <span className="subtle">결과 없음</span>
            ) : (
              <ol className="flex flex-col gap-1">
                {r.results.map((x) => (
                  <li key={x.entryNo} className="flex flex-wrap items-center gap-1">
                    <Tone table={RESULT_ROW} code={x.result} />
                    {x.observedResult !== null && <span>{`(관찰 ${x.observedResult})`}</span>}
                    {x.reasonCode !== null && <Mono>{x.reasonCode}</Mono>}
                    <span className="subtle">{x.responseRef === null ? '결과 마감' : `${x.responseRef.kind} ${x.responseRef.sequenceNo}회`}</span>
                    <KstTime iso={x.recognizedAt} />
                  </li>
                ))}
              </ol>
            )}
          </td>
        </tr>
      ))}
    </>
  )
}

function DetailPanels({ detail, records, recordStatus, onRecordStatus, onMoreRecords, recordsBusy, request }: {
  detail: AcquisitionFileDetail
  /** 재매입 요청 패널(OPERATOR일 때만 — 표시만, 권한은 서버가 판정). 레코드별 결과 카드 바로 다음 카드로 그린다. */
  request?: ReactNode
  records: AcquisitionRecordPage
  recordStatus: string
  onRecordStatus: (status: string) => void
  onMoreRecords: () => void
  recordsBusy: boolean
}) {
  const { file, attempts, responses } = detail
  const recordStatusLabelId = useId()
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
            <span className="flex flex-col items-start gap-1">
              <Tone table={TRANSMISSION} code={file.transmissionState} />
              {file.transmittedAt !== null && <span className="subtle">완료 <KstTime iso={file.transmittedAt} /></span>}
            </span>
          </Item>
          <Item term="수신 확인(ACK)"><Tone table={RECEIPT} code={file.receiptState} /></Item>
          <Item term="결과"><Tone table={RESULT} code={file.resultState} /></Item>
        </dl>
        {file.attention.length === 0 && !file.reacquisitionCandidate && !file.reacquisitionInProgress ? (
          <p className="subtle">확인할 일이 없습니다.</p>
        ) : (
          <ul aria-label="확인 필요 사유" className="flex flex-col gap-1.5">
            {file.attention.map((code) => (
              <li key={code} className="flex flex-wrap items-center gap-2">
                <span className="tag orange" title={code}>{ATTENTION[code]?.badge ?? code}</span>
                <span>{ATTENTION[code]?.text ?? code}{code === 'RESPONSE_QUARANTINED' ? ` (${responses.quarantined}건)` : ''}</span>
              </li>
            ))}
            {file.reacquisitionCandidate && (
              <li className="flex flex-wrap items-center gap-2">
                <span className="tag">{REACQUISITION.badge}</span>
                <span>{request ? REACQUISITION.operatorText : REACQUISITION.viewerText}</span>
              </li>
            )}
            {file.reacquisitionInProgress && (
              <li className="flex flex-wrap items-center gap-2">
                <span className="tag">{REACQUISITION_IN_PROGRESS.badge}</span>
                <span>{REACQUISITION_IN_PROGRESS.text}</span>
              </li>
            )}
          </ul>
        )}
      </Panel>

      <Panel title="레코드별 결과">
        {/* 시안 .rolebar(계정·권한 화면 `<b>역할 선택</b><select>…<span class="subtle">`) — 선택의 이름은 굵은 글(aria-labelledby). */}
        <div className="rolebar">
          <b id={recordStatusLabelId}>레코드 상태</b>
          <select aria-labelledby={recordStatusLabelId} value={recordStatus} onChange={(e) => onRecordStatus(e.target.value)}>
            <option value="">전체</option>
            {Object.entries(RECORD_STATUS).map(([code, named]) => <option key={code} value={code}>{named.label}</option>)}
          </select>
          <span className="subtle">조회 시각 <KstTime iso={detail.readAt} /></span>
        </div>
        <div className="tablewrap">
          <table aria-label="레코드 목록">
            <thead>
              <tr>
                <th>순번</th><th>구분·거래</th><th>MID</th><th className="amount">레코드 금액</th><th>기관 거래일</th><th>현재 상태</th><th>결과 이력</th>
              </tr>
            </thead>
            <tbody>
              <RecordRows records={records.items} />
            </tbody>
          </table>
          {records.items.length === 0 && <div className="empty">조건에 맞는 레코드가 없습니다.</div>}
        </div>
        {records.nextCursor !== null && (
          <div className="flex justify-end">
            <button type="button" className="btn" onClick={onMoreRecords} aria-busy={recordsBusy}>레코드 더 보기</button>
          </div>
        )}
      </Panel>

      {request}

      <Panel title="전송 시도">
        <div className="tablewrap">
          <table aria-label="전송 시도 목록">
            <thead>
              <tr><th>시도</th><th>시작</th><th>결과</th><th>근거·사유</th><th>결과 상태</th></tr>
            </thead>
            <tbody>
              {attempts.latest.map((a) => (
                <tr key={a.attemptNo} aria-label={`전송 시도 ${a.attemptNo}`}>
                  <td>{a.attemptNo}</td>
                  <td><KstTime iso={a.claimedAt} /></td>
                  <td>{a.outcome ?? '진행 중'}</td>
                  <td>{a.evidence ?? a.reason ?? '-'}</td>
                  <td>{a.resultingState === null ? '-' : <Tone table={TRANSMISSION} code={a.resultingState} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {attempts.latest.length === 0 && <div className="empty">전송 시도가 없습니다.</div>}
        </div>
        {attempts.total > attempts.latest.length && <p className="subtle">{`최근 ${attempts.latest.length}건만 표시합니다(전체 ${attempts.total}건).`}</p>}
      </Panel>

      <Panel title="기관 응답 기록">
        <div className="tablewrap">
          <table aria-label="응답 기록 목록">
            <thead>
              <tr><th>응답</th><th>처리</th><th>ACK 결과</th><th>근거·격리 사유</th><th>받은 시각</th></tr>
            </thead>
            <tbody>
              {responses.latest.map((r) => (
                <tr key={`${r.kind}-${r.sequenceNo}-${r.observedAt}`} aria-label={`${r.kind} ${r.sequenceNo}`}>
                  <td>{`${r.kind} ${r.sequenceNo}회`}</td>
                  <td>{r.disposition === 'QUARANTINED' ? <span className="tag red">격리</span> : <span className="tag green">반영</span>}</td>
                  <td>{r.ackOutcome === null ? '-' : `${r.ackOutcome}${r.ackReasonCode ? ` (${r.ackReasonCode})` : ''}`}</td>
                  <td>{r.quarantineReason ?? r.evidence ?? '-'}</td>
                  <td><KstTime iso={r.observedAt} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          {responses.latest.length === 0 && <div className="empty">받은 기관 응답이 없습니다.</div>}
        </div>
        {responses.total > responses.latest.length && <p className="subtle">{`최근 ${responses.latest.length}건만 표시합니다(전체 ${responses.total}건).`}</p>}
      </Panel>
    </>
  )
}

interface Props {
  api: AdminApi
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 재매입 요청 버튼 표시(OPERATOR — ACQ-ADMIN-001 D-ACQA-002, 표시만 — 서버가 판정). */
  canRequest?: boolean
  /** 시험용 현재 시각(기본 Date.now). */
  now?: () => number
}

/**
 * 매입 현황(ACQ-VIEW-001 §4.6 조회 + ACQ-ADMIN-001 §5.1 재매입 요청 — OPERATOR에게만 요청 패널, 실행은 승인함의 2인 승인 뒤 FINANCIAL). 업무일 기간(KST 달력일, 최대 31일) + 매입사·환경·3축 상태·"확인 필요만" 필터, 또는 파일 ID 정확 조회.
 * 목록은 최신 업무일순 keyset("더 보기"로 이어 붙임). "상세"로 파일 요약·3축 상태·확인 필요 사유·레코드별 결과·전송 시도·기관 응답 기록을 연다 — 상세·레코드 쪽은 서버가 감사 기록을 남긴
 * 뒤에만 온다(D-ACQV-001). 승인번호·기관 거래번호·발급사·카드 종류·할부는 서버가 보내지 않는다(D-ACQV-002). 권한·형식·감사·속도 제한은 서버가 판정한다. 저장소·URL에 아무것도 남기지 않는다.
 */
export function AcquisitionsScreen({ api, onSessionEnded, canRequest = false, now = Date.now }: Props) {
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

  const stateSelect = (label: string, allLabel: string, value: string, onChange: (v: string) => void, table: Named) => (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allLabel}</option>
      {Object.entries(table).map(([code, named]) => <option key={code} value={code}>{named.label}</option>)}
    </select>
  )

  // ADMIN-UI-WORKSPACE-001(매입·대사 › 매입 현황): 거래 조회(16cc494)와 같은 참고 시안 업무 화면(app.js moduleView — 카드 안 탭 → .filters → .tablewrap → .tablebottom, 상세는 detail()의 eyebrow·제목·카드).
  // MenuCard flush 탭 패널 바로 안에 그린다. 입력란 이름은 aria-label(placeholder·선택지 문구로 무엇인지 보인다), 기간 안내·입력 오류·목록 오류는 필터 아래 .cardnote(있을 때만).
  // 조회 방식에 따라 입력란만 바뀌고 줄 모양은 같다(.filters.many — 좁은 폭에서 줄바꿈). 재매입 요청은 OPERATOR에게만, 레코드별 결과 카드 다음 카드로.
  return (
    <>
      <div className="mapnote">
        <h2>매입 현황</h2>
        {' · '}
        <span>
          {canRequest
            ? '매입 파일의 전송·수신·결과 상태를 조회하고, 거절된 레코드의 재매입을 요청합니다. 파일 상세 조회는 감사 기록에 남습니다.'
            : '매입 파일의 전송·수신·결과 상태를 조회합니다. 파일 상세 조회는 감사 기록에 남습니다.'}
        </span>
      </div>
      <form className="filters many" onSubmit={search}>
        <select aria-label="조회 방식" value={mode} onChange={(e) => { setMode(e.target.value as typeof mode); setInputError(null) }}>
          <option value="period">업무일·조건</option>
          <option value="file">파일 ID</option>
        </select>
        {mode === 'file' ? (
          <input aria-label="파일 ID" value={fileId} onChange={(e) => setFileId(e.target.value)} autoComplete="off" spellCheck={false} placeholder="파일 ID (ACQ.…)" className="font-mono" />
        ) : (
          <>
            <input type="date" aria-label="업무일 시작(KST)" value={fromDate} onChange={(e) => setFromDate(e.target.value)} aria-describedby={periodNoteId} />
            <input type="date" aria-label="업무일 종료(KST)" value={toDate} onChange={(e) => setToDate(e.target.value)} aria-describedby={periodNoteId} />
            <input aria-label="매입사 코드" value={acquirerCode} onChange={(e) => setAcquirerCode(e.target.value)} autoComplete="off" spellCheck={false} placeholder="매입사 코드" className="font-mono" />
            <select aria-label="환경" value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              <option value="">전체 환경</option>
              <option value="TEST">TEST</option>
              <option value="LIVE">LIVE</option>
            </select>
            {stateSelect('전송 상태', '전체 전송 상태', transmissionState, setTransmissionState, TRANSMISSION)}
            {stateSelect('수신 상태', '전체 수신 상태', receiptState, setReceiptState, RECEIPT)}
            {stateSelect('결과 상태', '전체 결과 상태', resultState, setResultState, RESULT)}
            <label className="flex items-center gap-2 whitespace-nowrap">
              <input type="checkbox" checked={needsAttention} onChange={(e) => setNeedsAttention(e.target.checked)} />
              확인 필요만
            </label>
          </>
        )}
        <button type="submit" className="btn primary" aria-busy={listBusy}>검색</button>
      </form>
      {/* 거래 조회 리뷰 NIT-1: 안내·오류가 있을 때만 그린다(파일 ID 방식에서 빈 여백 없음). 날짜 칸에는 placeholder가 없어 안내에 "시작일 ~ 종료일"을 쓴다(리뷰 MINOR-2). */}
      {(mode === 'period' || inputError || listError) && (
        <div className="cardnote">
          {mode === 'period' && <p id={periodNoteId} className="subtle">{`업무일 기간(시작일 ~ 종료일): KST 달력일 기준, 최대 ${MAX_PERIOD_DAYS}일(Demo 기준).`}</p>}
          {inputError && <p role="alert" className="error">{inputError}</p>}
          {listError && <ErrorNotice>{message(listError)}</ErrorNotice>}
        </div>
      )}

      {page && (
        <>
          <div className="tablewrap">
            <table aria-label="매입 파일 목록">
              <thead>
                <tr>
                  <th>파일 / 매입사</th><th>업무일</th><th>건수</th><th>전송·수신·결과</th><th>레코드 결과</th><th>표시</th><th className="amount">순액</th>
                  <th><span className="sr-only">작업</span></th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.fileId} aria-label={row.fileId} className={detail?.file.fileId === row.fileId ? 'selected' : undefined}>
                    <td>
                      <strong>{row.fileId}</strong>
                      <small>{`${row.acquirerCode} · ${row.environment} · v${row.fileVersion}`}</small>
                      {row.originalFileId !== null && <small title={row.originalFileId}>{`재매입 · 원 파일 ${row.originalFileId}`}</small>}
                    </td>
                    <td>{row.businessDate}</td>
                    <td>{`매출 ${row.saleCount} · 취소 ${row.cancelCount}`}</td>
                    <td>
                      <span className="flex flex-col items-start gap-1">
                        <Tone table={TRANSMISSION} code={row.transmissionState} />
                        <Tone table={RECEIPT} code={row.receiptState} />
                        <Tone table={RESULT} code={row.resultState} />
                      </span>
                    </td>
                    <td><Counts file={row} /></td>
                    <td><Marks file={row} /></td>
                    <td className="amount">{amount(row.netAmountMinor, row.currency)}</td>
                    <td>
                      <button type="button" className="btn" onClick={() => open(row.fileId)}>상세</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {page.items.length === 0 && <div className="empty">조건에 맞는 매입 파일이 없습니다.</div>}
          </div>
          <div className="tablebottom">
            <span>{`목록 ${page.items.length}건 · 승인번호·기관 거래번호·카드 정보는 이 화면에 표시하지 않습니다. 거래 ID로 거래 조회에서 확인하세요.`}</span>
            {page.nextCursor !== null && (
              <button type="button" className="btn" onClick={more} aria-busy={listBusy}>더 보기</button>
            )}
          </div>
        </>
      )}

      {(detailBusy || detailError || (detail && records)) && (
        <div className="detailarea">
          {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
          {detailError && <ErrorNotice>{message(detailError)}</ErrorNotice>}
          {detail && records && (
            <>
              <div>
                <div className="eyebrow" aria-hidden="true">매입·대사 / 상세</div>
                <h3>
                  {'매입 파일 상세 '}
                  <small>{detail.file.fileId}</small>
                </h3>
              </div>
              <DetailPanels
                detail={detail}
                records={records}
                recordStatus={recordStatus}
                recordsBusy={recordsBusy}
                request={canRequest ? (
                  <ReacquisitionRequest key={detail.file.fileId} api={api} fileId={detail.file.fileId} records={records.items} onSessionEnded={onSessionEnded} />
                ) : undefined}
                onRecordStatus={(status) => {
                  setRecordStatus(status)
                  void loadRecords(status, 0, false)
                }}
                onMoreRecords={() => {
                  if (records.nextCursor !== null) void loadRecords(recordStatus, records.nextCursor, true)
                }}
              />
            </>
          )}
        </div>
      )}
    </>
  )
}
