import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ReconciliationOverview } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { KstTime } from '../components/DataBits'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { ErrorNotice, WarningNote } from '../components/Notice'

/** 사람이 조사해야 하는 대사 실행 결과(자동 정정·수동 종료 없음 — §4.17, I-119·I-120). */
const INVESTIGATE = new Set(['ITEM_ERROR', 'FULL_SCAN_INCOMPLETE', 'PAYMENT_UNAVAILABLE'])

/**
 * 대사 발견(ADMIN-PAYMENT-OPS-001 §4.17·§4.20 — SECURITY_ADMIN·APPROVER): 상단에 열린 발견 수, 종류별 수, 최근 발견(code 열만). 해결·종료 버튼은 없다(운영 절차 LIVE Gate).
 */
export function ReconciliationScreen({ api, onSessionEnded }: { api: AdminApi; onSessionEnded?: (code: ConsoleErrorCode) => void }) {
  const [overview, setOverview] = useState<ReconciliationOverview | null>(null)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)

  const load = useCallback(async () => {
    try {
      setOverview(await api.reconciliation())
    } catch (e) {
      const code = codeOf(e)
      if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
      else setError(code)
    }
  }, [api, onSessionEnded])

  useEffect(() => {
    void load()
  }, [load])

  // ADMIN-UI-WORKSPACE-001(매입·대사 › 대사 발견): 거래 조회와 같이 MenuCard flush 탭 패널 바로 안에 시안 구성 — .mapnote(탭 이름·설명) → 시안 카드 제목 줄(.cardtop h3 열린 발견 수)
  // → .cardbody(종류별 수 .tag·마지막 실행 안내) → .tablewrap(발견 표 — 빈 표는 .empty) → .tablebottom(표시 건수). 조회 조건이 없어 .filters 줄은 없다. 오류는 .mapnote 아래 .cardnote 하나.
  return (
    <>
      <div className="mapnote">
        <h2>대사 발견</h2>
        {' · '}
        <span>승인 기록과 결제 서비스 결과가 서로 맞지 않는 건을 보여 줍니다. 자동으로 고치거나 닫지 않으니 조사해 주세요.</span>
      </div>
      {error && (
        <div className="cardnote">
          <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>
        </div>
      )}
      {overview && (
        <>
          <div className="cardtop">
            <h3>{`열린 대사 발견 ${overview.openTotal}건`}</h3>
          </div>
          <div className="cardbody">
            <ul aria-label="종류별 열린 발견" className="flex flex-wrap gap-x-4 gap-y-2">
              {Object.entries(overview.openCounts).map(([kind, count]) => (
                <li key={kind} className="flex items-center gap-2">
                  <span className="tag orange" title={kind}>{labelOf(kind)}</span>
                  <span className="subtle font-mono">{`${kind}: ${count}`}</span>
                </li>
              ))}
            </ul>
            {overview.lastResult && INVESTIGATE.has(overview.lastResult) ? (
              <WarningNote>{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult} — 조사 필요 — 수동 종료 없음`}</WarningNote>
            ) : (
              <p className="subtle">{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult ?? '기록 없음'} (완전 성공 ${overview.lastRunAt ? formatKst(overview.lastRunAt) : '-'}, 전체 스캔 ${overview.lastFullScanAt ? formatKst(overview.lastFullScanAt) : '-'})`}</p>
            )}
            {overview.truncated && <p className="subtle">최근 200건만 표시합니다.</p>}
          </div>
          <div className="tablewrap">
            <table aria-label="대사 발견 목록">
              <thead>
                <tr>
                  <th>종류</th><th>승인 ID</th><th>결제</th><th>세부(열 이름·code)</th><th>발견 시각</th>
                </tr>
              </thead>
              <tbody>
                {overview.findings.map((f) => (
                  <tr key={f.findingId} aria-label={`${f.kind} ${f.approvalId}`}>
                    <td><span className="tag orange" title={f.kind}>{labelOf(f.kind)}</span></td>
                    <td className="font-mono">{f.approvalId}</td>
                    <td className="font-mono">{f.paymentRef ?? ''}</td>
                    <td className="font-mono">{f.detailCode ?? ''}</td>
                    <td><KstTime iso={f.foundAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {overview.findings.length === 0 && <div className="empty">열린 대사 발견이 없습니다.</div>}
          </div>
          <div className="tablebottom">
            <span>{`표시 ${overview.findings.length}건`}</span>
          </div>
        </>
      )}
    </>
  )
}
