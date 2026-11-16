import type { Row } from '../../data/types'
import { StatusBadge } from './Tag'

interface Props {
  cols: readonly string[]
  rows: readonly Row[]
  onRowClick?: (row: Row) => void
}

/**
 * .tablewrap > table + .tablebottom — 모듈 공통 목록 테이블
 * 셀 규칙: [대표명(strong)+식별키(small)] | 컬럼1 | 컬럼2 | 상태 태그 | 금액/담당자(.amount 우측정렬) | ›
 * 행 클릭/Enter → 상세 Drawer
 */
export function DataTable({ cols, rows, onRowClick }: Props) {
  return (
    <>
      <div className="tablewrap">
        <table>
          <thead>
            <tr>
              {cols.map((c, i) => (
                <th key={c} className={i === 4 ? 'amount' : ''}>
                  {c}
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr
                key={r[1]}
                className="clickable"
                tabIndex={0}
                onClick={() => onRowClick?.(r)}
                onKeyDown={(e) => {
                  if (e.key !== 'Enter') return
                  // Drawer 가 열리며 닫기 버튼으로 포커스가 옮겨진다 — 같은 Enter 의 keypress 가 그 버튼을 눌러 바로 닫지 않게 기본 동작을 막는다(tr 의 Enter 기본 동작은 없음)
                  e.preventDefault()
                  onRowClick?.(r)
                }}
              >
                <td>
                  <strong>{r[0]}</strong>
                  <small>{r[1]}</small>
                </td>
                <td>{r[2]}</td>
                <td>{r[3]}</td>
                <td>
                  <StatusBadge status={r[4]} />
                </td>
                <td className="amount">{r[5]}</td>
                <td>›</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <div className="empty">조회 결과가 없어요. 검색 조건을 변경해 주세요.</div>}
      </div>
      <div className="tablebottom">
        <span>{`샘플 ${rows.length}건`}</span>
        <span>1　 /　 1</span>
      </div>
    </>
  )
}
