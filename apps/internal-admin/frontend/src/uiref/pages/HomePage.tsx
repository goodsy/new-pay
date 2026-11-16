import type { PageId } from '../data'
import { useUi } from '../context'
import { PageHeading, Pipeline, Stats } from '../components/common'
import { DataTable } from '../components/DataTable'

const tasks: readonly (readonly [PageId, string, string, string, string])[] = [
  ['settle', '₩', '지급 승인 요청이 있어요', '모노마켓 · 45,280,000원', '승인 1건'],
  ['acquire', '≠', '원천사 입금 차이를 확인해 주세요', '키움페이 · 차이 금액 128,000원', '대사 1건'],
  ['risk', '!', '리스크 확인이 필요해요', '거래 급증·한도·취소율 확인', '리스크 3건'],
  ['ops', '↗', '전송에 실패한 통보가 있어요', '거래내역 메일 · 재시도 대상', '통보 2건'],
]

const quick: readonly (readonly [PageId, string])[] = [
  ['merchant', '가맹점 조회'],
  ['source', '원천사 연결·채번'],
  ['acquire', '매입·대사 확인'],
  ['access', '계정·권한 관리'],
]

/** app.js home() — 운영 홈 */
export function HomePage() {
  const ui = useUi()
  return (
    <>
      <PageHeading t="좋은 하루예요, 김 운영님" s="오늘 처리해야 할 업무와 결제 흐름을 한눈에 확인하세요.">
        <button className="btn" onClick={() => ui.go('report')}>
          {'2026. 11. 02　⌄'}
        </button>
        <button className="btn primary" onClick={() => ui.startWizard()}>
          ＋ 사업자 등록
        </button>
      </PageHeading>
      <div className="banner">
        <span className="symbol">✓</span>
        <div>
          <b>오늘의 정산이 지급 승인을 기다리고 있어요</b>
          <p>검증 완료 1건 · 승인 대기 1건 · 지급 보류 1건을 확인해 주세요.</p>
        </div>
        <button onClick={() => ui.go('settle')}>{'정산 확인하기　→'}</button>
      </div>
      <Stats
        items={[
          [
            '오늘 승인 금액',
            '128,400,000',
            '원',
            <>
              <span className="positive">↗ 12.8%</span>
              {'　이전 영업일 대비'}
            </>,
          ],
          ['오늘 승인 건수', '3,125', '건', '신용카드 · 가상계좌 · 상품권'],
          ['지급 예정액', '77,920,000', '원', '지급 완료·보류 제외 · 샘플 기준'],
          ['확인이 필요한 업무', '7', '건', '승인 1 · 대사 1 · 리스크 3 · 통보 2'],
        ]}
      />
      <div className="split">
        <section className="card">
          <div className="cardtop">
            <h2>
              {'지금 처리할 업무 '}
              <span className="count">7</span>
            </h2>
            <button onClick={() => ui.go('ops')}>운영 현황 →</button>
          </div>
          {tasks.map((x) => (
            <div className="task" key={x[0]} onClick={() => ui.go(x[0])}>
              <span className={`taskicon ${x[0] === 'settle' ? 'blue' : ''}`}>{x[1]}</span>
              <div>
                <b>{x[2]}</b>
                <small>{x[3]}</small>
              </div>
              <span className={`tag ${x[0] === 'settle' ? 'blue' : 'orange'}`}>{x[4]}</span>
            </div>
          ))}
        </section>
        <section className="card">
          <div className="cardtop">
            <h2>오늘의 정산 흐름</h2>
            <button onClick={() => ui.go('settle')}>상세 보기 →</button>
          </div>
          <Pipeline />
        </section>
      </div>
      <section className="card">
        <div className="cardtop">
          <h2>최근 거래</h2>
          <button onClick={() => ui.go('payment')}>전체 거래 보기 →</button>
        </div>
        <DataTable rows={ui.rows.payment} id="payment" />
      </section>
      <div className="quick">
        {quick.map((x) => (
          <button key={x[0]} onClick={() => ui.go(x[0])}>
            {x[1] + ' '}
            <span>↗</span>
          </button>
        ))}
      </div>
    </>
  )
}
