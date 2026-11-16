import { Banner } from '../components/ui/Banner'
import { Button } from '../components/ui/Button'
import { Card, CardTop } from '../components/ui/Card'
import { DataTable } from '../components/ui/DataTable'
import { PageHeading } from '../components/ui/PageHeading'
import { Pipeline } from '../components/ui/Pipeline'
import { StatGrid, type StatItem } from '../components/ui/StatGrid'
import { MODULES, SETTLE_PIPELINE } from '../data/modules'
import type { PageId } from '../data/types'
import { DetailPanel } from '../features/DetailPanel'
import { MerchantWizard } from '../features/MerchantWizard'
import { useWorkspace } from '../store/WorkspaceProvider'

/** 처리 업무 목록: [이동 페이지, 아이콘 문자, 제목, 보조설명, 태그] — 시연용 고정 문구 */
const TASKS: readonly (readonly [PageId, string, string, string, string])[] = [
  ['settle', '₩', '지급 승인 요청이 있어요', '모노마켓 · 45,280,000원', '승인 1건'],
  ['acquire', '≠', '원천사 입금 차이를 확인해 주세요', '키움페이 · 차이 금액 128,000원', '대사 1건'],
  ['risk', '!', '리스크 확인이 필요해요', '거래 급증·한도·취소율 확인', '리스크 3건'],
  ['ops', '↗', '전송에 실패한 통보가 있어요', '거래내역 메일 · 재시도 대상', '통보 2건'],
]

const QUICK_LINKS: readonly (readonly [PageId, string])[] = [
  ['merchant', '가맹점 조회'],
  ['source', '원천사 연결·채번'],
  ['acquire', '매입·대사 확인'],
  ['access', '계정·권한 관리'],
]

const STATS: readonly StatItem[] = [
  {
    title: '오늘 승인 금액',
    value: '128,400,000',
    unit: '원',
    hint: (
      <>
        <span className="positive">↗ 12.8%</span>　이전 영업일 대비
      </>
    ),
  },
  { title: '오늘 승인 건수', value: '3,125', unit: '건', hint: '신용카드 · 가상계좌 · 상품권' },
  { title: '지급 예정액', value: '77,920,000', unit: '원', hint: '지급 완료·보류 제외 · 샘플 기준' },
  { title: '확인이 필요한 업무', value: '7', unit: '건', hint: '승인 1 · 대사 1 · 리스크 3 · 통보 2' },
]

/**
 * 운영 홈 (#home)
 * heading → banner → stats(4) → split[ 처리할 업무 카드 1.55fr | 정산 흐름 카드 1fr ] → 최근 거래 카드 → quick 링크 4개
 */
export function HomePage() {
  const { go, openDrawer, rows } = useWorkspace()
  return (
    <>
      <PageHeading
        title="좋은 하루예요, 김 운영님"
        description="오늘 처리해야 할 업무와 결제 흐름을 한눈에 확인하세요."
        actions={
          <>
            <Button onClick={() => go('report')}>2026. 11. 02　⌄</Button>
            <Button variant="primary" onClick={() => openDrawer(<MerchantWizard />)}>
              ＋ 사업자 등록
            </Button>
          </>
        }
      />
      <Banner
        symbol="✓"
        title="오늘의 정산이 지급 승인을 기다리고 있어요"
        description="검증 완료 1건 · 승인 대기 1건 · 지급 보류 1건을 확인해 주세요."
        actionLabel="정산 확인하기　→"
        onAction={() => go('settle')}
      />
      <StatGrid items={STATS} />
      <div className="split">
        <Card>
          <CardTop title="지금 처리할 업무" count={7} linkLabel="운영 현황 →" onLink={() => go('ops')} />
          {TASKS.map(([to, icon, title, desc, tag]) => (
            <div className="task" key={title} onClick={() => go(to)}>
              <span className={to === 'settle' ? 'taskicon blue' : 'taskicon'}>{icon}</span>
              <div>
                <b>{title}</b>
                <small>{desc}</small>
              </div>
              <span className={to === 'settle' ? 'tag blue' : 'tag orange'}>{tag}</span>
            </div>
          ))}
        </Card>
        <Card>
          <CardTop title="오늘의 정산 흐름" linkLabel="상세 보기 →" onLink={() => go('settle')} />
          <Pipeline steps={SETTLE_PIPELINE} />
        </Card>
      </div>
      <Card>
        <CardTop title="최근 거래" linkLabel="전체 거래 보기 →" onLink={() => go('payment')} />
        <DataTable
          cols={MODULES.payment.cols}
          rows={rows.payment}
          onRowClick={(r) => openDrawer(<DetailPanel moduleId="payment" rowKey={r[1]} />)}
        />
      </Card>
      <div className="quick">
        {QUICK_LINKS.map(([to, label]) => (
          <button type="button" key={to} onClick={() => go(to)}>
            {`${label} `}
            <span>↗</span>
          </button>
        ))}
      </div>
    </>
  )
}
