import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ReconciliationOverview } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'

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

  return (
    <section>
      {error && <p role="alert">{ERROR_MESSAGES[error]}</p>}
      {overview && (
        <>
          <h2>{`열린 대사 발견 ${overview.openTotal}건`}</h2>
          {overview.lastResult && INVESTIGATE.has(overview.lastResult) ? (
            <p>{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult} — 조사 필요 — 수동 종료 없음`}</p>
          ) : (
            <p>{`마지막 대사 실행(이 인스턴스 기준): ${overview.lastResult ?? '기록 없음'} (완전 성공 ${overview.lastRunAt ?? '-'}, 전체 스캔 ${overview.lastFullScanAt ?? '-'})`}</p>
          )}
          <ul>
            {Object.entries(overview.openCounts).map(([kind, count]) => (
              <li key={kind}>{`${kind}: ${count}`}</li>
            ))}
          </ul>
          {overview.truncated && <p>최근 200건만 표시합니다.</p>}
          <table>
            <thead>
              <tr>
                <th>종류</th>
                <th>승인 ID</th>
                <th>결제</th>
                <th>세부(열 이름·code)</th>
                <th>발견 시각</th>
              </tr>
            </thead>
            <tbody>
              {overview.findings.map((f) => (
                <tr key={f.findingId} aria-label={`${f.kind} ${f.approvalId}`}>
                  <td>{f.kind}</td>
                  <td>{f.approvalId}</td>
                  <td>{f.paymentRef ?? ''}</td>
                  <td>{f.detailCode ?? ''}</td>
                  <td>{f.foundAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}
