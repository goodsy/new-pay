import { conf, type ConfId, type Row } from '../data'
import { useUi } from '../context'
import { StatusBadge } from './common'

/** app.js table(rows,id) — .tablewrap과 .tablebottom 두 형제를 그대로 낸다(Fragment, wrapper 없음). */
export function DataTable({ rows, id }: { rows: readonly Row[]; id: ConfId }) {
  const ui = useUi()
  const c = conf[id]
  return (
    <>
      <div className="tablewrap">
        <table>
          <thead>
            <tr>
              {c.cols.map((x, i) => (
                <th className={i === 4 ? 'amount' : ''} key={x}>
                  {x}
                </th>
              ))}
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr
                className="clickable"
                tabIndex={0}
                key={r[1]}
                onClick={() => ui.detail(id, r[1])}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') ui.detail(id, r[1])
                }}
              >
                <td>
                  <strong>{r[0]}</strong>
                  <small>{r[1]}</small>
                </td>
                <td>{r[2]}</td>
                <td>{r[3]}</td>
                <td>
                  <StatusBadge s={r[4]} />
                </td>
                <td className="amount">{r[5]}</td>
                <td>›</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length ? null : <div className="empty">조회 결과가 없어요. 검색 조건을 변경해 주세요.</div>}
      </div>
      <div className="tablebottom">
        <span>{`샘플 ${rows.length}건`}</span>
        <span>{'1　 /　 1'}</span>
      </div>
    </>
  )
}
