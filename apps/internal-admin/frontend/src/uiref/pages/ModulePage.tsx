import { ALL_STATUS, conf, filterRows, tabFilters, title, type ConfId } from '../data'
import { useUi } from '../context'
import { PageHeading } from '../components/common'
import { DataTable } from '../components/DataTable'

/** app.js moduleView() — conf 기반 업무 화면(탭·조회조건·목록). 정산만 안내 banner가 있다. */
export function ModulePage({ id }: { id: ConfId }) {
  const ui = useUi()
  const c = conf[id]
  const rows = ui.rows[id]
  const statuses = [...new Set(rows.map((r) => r[4]))]
  return (
    <>
      <PageHeading t={title(id)} s={c.sub}>
        <button className="btn primary" onClick={() => ui.primaryAction()}>
          {c.action}
        </button>
      </PageHeading>
      {id === 'settle' ? (
        <div className="banner">
          <span className="symbol">₩</span>
          <div>
            <b>지급은 검증 → 승인 → 송금 순서로 진행해요</b>
            <p>보류 건은 지급 대상에서 제외하고, 변경·재처리는 사유와 이력을 남깁니다.</p>
          </div>
          <button onClick={() => ui.detail('settle', 'ST-20261102-001')}>승인 대기 확인 →</button>
        </div>
      ) : null}
      <section className="card">
        <div className="tabs" role="tablist">
          {c.tabs.map((t, i) => (
            <button className={i === ui.tab ? 'on' : ''} role="tab" aria-selected={i === ui.tab} key={t} onClick={() => ui.setTab(i)}>
              {t}
            </button>
          ))}
        </div>
        {ui.tab > 1 && !tabFilters[id]?.[ui.tab] ? (
          <div className="mapnote">
            <b>{c.tabs[ui.tab]}</b> · 관련 정보를 선택한 대상의 상세 패널에서 확인하세요. 아래는 연결된 대상 목록입니다.
          </div>
        ) : null}
        <div className="filters">
          <input
            aria-label="검색"
            placeholder={`${id === 'payment' ? '가맹점명, TID 또는 주문번호' : '이름 또는 관리번호'} 검색`}
            value={ui.query}
            onChange={(e) => ui.setQuery(e.target.value)}
          />
          <select aria-label="상태" value={ui.status} onChange={(e) => ui.setStatus(e.target.value)}>
            <option>{ALL_STATUS}</option>
            {statuses.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <button className="btn" onClick={() => ui.resetFilters()}>
            초기화
          </button>
          <button className="btn" onClick={() => ui.exportRows()}>
            ↓ CSV
          </button>
        </div>
        <div id="results">
          <DataTable rows={filterRows(rows, id, ui.tab, ui.query, ui.status)} id={id} />
        </div>
      </section>
    </>
  )
}
