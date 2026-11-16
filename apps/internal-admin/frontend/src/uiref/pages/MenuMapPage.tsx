import { useState } from 'react'
import { destination, title, type LegacyRow } from '../data'
import { useUi } from '../context'
import { PageHeading } from '../components/common'

function MapTable({ rows, note }: { rows: readonly LegacyRow[]; note?: string }) {
  const ui = useUi()
  return (
    <div className="tablewrap">
      <table>
        <thead>
          <tr>
            <th>기존 메뉴 / MENU_ID</th>
            <th>레벨</th>
            <th>개편 후 업무</th>
            <th>검토 사항</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.B}>
              <td>
                {r.A}
                <small>{r.B}</small>
              </td>
              <td>{r.C}</td>
              <td>
                <button className="tag blue" onClick={() => ui.go(destination(r))}>
                  {(r.C === '0' ? '공통 인증' : title(destination(r))) + ' →'}
                </button>
              </td>
              <td>{r.G || '업무 기준 재배치 제안'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {/* 원본은 fetch 전·실패 시 빈 표만 보인다. 로딩·실패 상태 안내만 덧붙인다(성공 시 원본과 같은 DOM). */}
      {note ? <div className="empty" role="status">{note}</div> : null}
    </div>
  )
}

/** app.js mapView() — legacy.json(레거시 메뉴 165개) 대응 지도 */
export function MenuMapPage() {
  const ui = useUi()
  const [q, setQ] = useState('')
  const legacy = ui.legacy
  return (
    <>
      <PageHeading t="메뉴 개편 지도" s="첨부 파일의 내부 관리자 메뉴 165개를 보존하고 새 업무 영역으로 연결했습니다." />
      <section className="card">
        <div className="mapnote">
          <b>3단계 메뉴 탐색 → 업무 영역 + 화면 탭 + 상세 패널</b>
          <br />
          아래는 1차 재배치 제안입니다. 원본 상위 메뉴 추론·중복 명칭은 그대로 표시하고, 실제 기능 통합 여부와 권한은 별도 확인이 필요합니다. 로그인·로그아웃은 공통 인증으로 분리합니다.
        </div>
        <div className="filters">
          <input aria-label="레거시 메뉴 검색" placeholder="기존 메뉴명 또는 MENU_ID 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div id="maprows">
          {legacy.state === 'ok' ? (
            <MapTable rows={legacy.rows.filter((r) => (r.A + r.B).includes(q))} />
          ) : (
            <MapTable rows={[]} note={legacy.state === 'loading' ? '메뉴 지도 데이터를 불러오는 중이에요' : '메뉴 지도 데이터를 불러오지 못했어요. 새로고침해 주세요.'} />
          )}
        </div>
      </section>
    </>
  )
}
