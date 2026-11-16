import { useMemo, useState } from 'react'
import { Banner } from '../components/ui/Banner'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DataTable } from '../components/ui/DataTable'
import { PageHeading } from '../components/ui/PageHeading'
import { Tabs } from '../components/ui/Tabs'
import { pageTitle } from '../data/menus'
import { MODULES, TAB_FILTERS } from '../data/modules'
import type { ModuleId } from '../data/types'
import { DetailPanel } from '../features/DetailPanel'
import { MerchantWizard } from '../features/MerchantWizard'
import { SourceForm } from '../features/SourceForm'
import { downloadRowsCsv } from '../lib/csv'
import { useWorkspace } from '../store/WorkspaceProvider'

const ALL = '전체 상태'

/**
 * 업무 모듈 공통 템플릿 (merchant/source/payment/acquire/settle/risk/report/ops)
 * heading(+primary 액션) → (settle 전용 banner) → card[ tabs → (안내 mapnote) → filters → #results table ]
 * 이동마다 App 이 key(페이지 + 이동 순번)로 재마운트해 tab/query/status 가 초기화된다 (원본 go() 동작).
 */
export function ModulePage({ moduleId }: { moduleId: ModuleId }) {
  const { rows, openDrawer, toast } = useWorkspace()
  const conf = MODULES[moduleId]
  const moduleRows = rows[moduleId]
  const [tab, setTab] = useState(0)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState(ALL)

  const tabFilter = TAB_FILTERS[moduleId]?.[tab]
  const filtered = useMemo(
    () =>
      moduleRows.filter(
        (r) => (!tabFilter || tabFilter(r)) && r.join(' ').toLowerCase().includes(query.toLowerCase()) && (status === ALL || r[4] === status),
      ),
    [moduleRows, tabFilter, query, status],
  )
  const statusOptions = useMemo(() => [...new Set(moduleRows.map((r) => r[4]))], [moduleRows])

  const reset = () => {
    setQuery('')
    setStatus(ALL)
  }
  const exportCsv = () => {
    downloadRowsCsv(`pg-${moduleId}-sample.csv`, conf.cols, filtered)
    toast('현재 조회 결과를 내보냈어요')
  }
  const openDetail = (key: string) => openDrawer(<DetailPanel moduleId={moduleId} rowKey={key} />)

  // 우상단 primary 액션 분기 (원본 primaryAction)
  const primaryAction = () => {
    const first = moduleRows[0]
    if (moduleId === 'merchant') openDrawer(<MerchantWizard />)
    else if (moduleId === 'source') openDrawer(<SourceForm />)
    else if (/내보내기/.test(conf.action)) exportCsv()
    else if (first) openDetail(first[1])
  }

  return (
    <>
      <PageHeading
        title={pageTitle(moduleId)}
        description={conf.sub}
        actions={
          <Button variant="primary" onClick={primaryAction}>
            {conf.action}
          </Button>
        }
      />
      {moduleId === 'settle' && (
        <Banner
          symbol="₩"
          title="지급은 검증 → 승인 → 송금 순서로 진행해요"
          description="보류 건은 지급 대상에서 제외하고, 변경·재처리는 사유와 이력을 남깁니다."
          actionLabel="승인 대기 확인 →"
          onAction={() => openDetail('ST-20261102-001')}
        />
      )}
      <Card>
        <Tabs
          items={conf.tabs}
          active={tab}
          onSelect={(i) => {
            setTab(i)
            reset()
          }}
        />
        {tab > 1 && !tabFilter && (
          <div className="mapnote">
            <b>{conf.tabs[tab]}</b> · 관련 정보를 선택한 대상의 상세 패널에서 확인하세요. 아래는 연결된 대상 목록입니다.
          </div>
        )}
        <div className="filters">
          <input
            aria-label="검색"
            placeholder={`${moduleId === 'payment' ? '가맹점명, TID 또는 주문번호' : '이름 또는 관리번호'} 검색`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <select aria-label="상태" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option>{ALL}</option>
            {statusOptions.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
          <Button onClick={reset}>초기화</Button>
          <Button onClick={exportCsv}>↓ CSV</Button>
        </div>
        <div id="results">
          <DataTable cols={conf.cols} rows={filtered} onRowClick={(r) => openDetail(r[1])} />
        </div>
      </Card>
    </>
  )
}
