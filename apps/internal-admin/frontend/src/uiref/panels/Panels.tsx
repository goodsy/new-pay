import { Fragment, useRef, type FormEvent, type ReactNode } from 'react'
import { conf, menus, title, confIds, type ConfId } from '../data'
import { useUi } from '../context'
import { Pipeline, StatusBadge } from '../components/common'

/** app.js panel(html) — 오른쪽 drawer. 닫기 버튼 다음에 내용이 바로 온다. */
export function Drawer({ children }: { children: ReactNode }) {
  const ui = useUi()
  return (
    <section className="drawer" role="dialog" aria-modal="true">
      <button className="close" aria-label="닫기" onClick={() => ui.closePanel()}>
        ×
      </button>
      {children}
    </section>
  )
}

/** app.js detail(id,key) — 업무별 상세 패널(시연용) */
export function DetailPanel({ id, rowKey }: { id: ConfId; rowKey: string }) {
  const ui = useUi()
  const reason = useRef<HTMLInputElement>(null)
  const r = ui.rows[id].find((x) => x[1] === rowKey)
  if (!r) return null
  const submitReview = () => {
    if (!reason.current?.value.trim()) {
      ui.toast('검토 사유를 입력해 주세요')
      return
    }
    ui.toast('데모 검토 이력을 기록했어요')
    ui.closePanel()
  }
  let body: ReactNode
  if (id === 'merchant') {
    body = (
      <>
        <h3>서비스 개통 현황</h3>
        <div className="timeline">
          <p>
            사업자·계약 등록<small>사업자와 계약을 기준으로 MID를 연결합니다.</small>
          </p>
          <p>
            원천사 연결<small>NICE / 키움페이 · 원천사 채번과 내부 MID는 별도 관리</small>
          </p>
          <p>
            가맹점 MID 발급<small>{r[1] + ' · GID: G-DEMO01 · VID: V-DEMO01'}</small>
          </p>
          <p>
            수수료·정산정책<small>샘플 정책: 카드 2.4% · VAT 별도 · D+3</small>
          </p>
        </div>
        <div className="actions">
          <button className="btn" onClick={() => ui.go('payment')}>
            결제 보기
          </button>
          <button className="btn" onClick={() => ui.go('settle')}>
            정산 보기
          </button>
        </div>
      </>
    )
  } else if (id === 'payment') {
    body = (
      <>
        <h3>거래 타임라인</h3>
        <div className="timeline">
          <p>
            결제 요청<small>{r[3] + ' · 주문번호 ORD-DEMO-001'}</small>
          </p>
          <p>
            {r[4]}
            <small>{'원천사 TID: SRC-' + r[1]}</small>
          </p>
          {r[4] === '부분 취소' ? (
            <p>
              부분취소 16,000원<small>원승인 80,000원 · 잔액 64,000원 · 별도 취소 TID 연결</small>
            </p>
          ) : null}
          <p>
            매입 / 정산<small>처리 결과는 매입·정산 내역에서 확인합니다.</small>
          </p>
        </div>
        <button className="btn" onClick={() => ui.go('acquire')}>
          연결된 매입 업무 →
        </button>
      </>
    )
  } else if (id === 'settle') {
    body = (
      <>
        <h3>정산 처리 단계</h3>
        <Pipeline />
        <div className="notice">승인자 분리, 보류 제외, 중복 지급 방지는 실제 시스템 연동 시 서버에서 검증해야 합니다. 이 화면은 요청 흐름 시연입니다.</div>
        <div className="formrow">
          <label htmlFor="reason">검토 사유</label>
          <input id="reason" ref={reason} placeholder="검증 결과와 요청 사유 입력" />
        </div>
        <button className="btn primary" disabled={/보류|완료/.test(r[4]) && !r[4].includes('검증')} onClick={submitReview}>
          지급 승인 요청 시연
        </button>
      </>
    )
  } else if (id === 'source') {
    body = (
      <>
        <h3>채번 및 연결 정보</h3>
        <div className="notice">원천사 관리코드 → 원천사 가맹점번호 → 내부 MID 매핑을 구분합니다. 발급 주체와 번호 체계는 원천사별 확인이 필요합니다.</div>
        <div className="detailgrid">
          <div>
            <label>연결 내부 MID</label>
            <strong>mono000001</strong>
          </div>
          <div>
            <label>사용 환경</label>
            <strong>테스트</strong>
          </div>
        </div>
        <button className="btn" onClick={() => ui.go('merchant')}>
          연결 가맹점 보기 →
        </button>
      </>
    )
  } else {
    body = (
      <>
        <h3>처리 기록</h3>
        <div className="timeline">
          <p>
            대상 수집<small>2026.11.02 09:00 · 샘플 실행 이력</small>
          </p>
          <p>
            {r[4]}
            <small>차이 내역과 원천 데이터를 확인 후 처리합니다.</small>
          </p>
        </div>
        <div className="formrow">
          <label htmlFor="reason">검토 메모</label>
          <input id="reason" ref={reason} placeholder="검토 내용 입력" />
        </div>
        <button className="btn primary" onClick={submitReview}>
          검토 기록 남기기
        </button>
      </>
    )
  }
  return (
    <Drawer>
      <div className="eyebrow">{title(id) + ' / 상세'}</div>
      <h2>{r[0]}</h2>
      <p className="subtle">
        {r[1] + '　'}
        <StatusBadge s={r[4]} />
      </p>
      <div className="detailgrid">
        {conf[id].cols.slice(1).map((x, i) => (
          <div key={x}>
            <label>{x}</label>
            <strong>{r[i + 2]}</strong>
          </div>
        ))}
      </div>
      {body}
      {/* 원본 style="margin-top:28px"는 CSP(style-src 'self')로 uiref-csp.css의 .uiref-drawer-note 규칙이 대신한다. */}
      <p className="subtle uiref-drawer-note">화면 시연용 데이터 · 실제 시스템에 반영되지 않습니다.</p>
    </Drawer>
  )
}

/** app.js sourceForm() — 원천사 데모 기준정보 등록(실제 채번 아님) */
export function SourceFormPanel() {
  const ui = useUi()
  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    ui.saveSource(new FormData(e.currentTarget))
  }
  return (
    <Drawer>
      <h2>원천사 등록</h2>
      <p className="subtle">기준정보와 외부 발급 번호를 구분해 관리합니다.</p>
      <form onSubmit={onSubmit}>
        <div className="formrow">
          <label htmlFor="src-name">원천사명</label>
          <input id="src-name" name="name" required />
        </div>
        <div className="formrow">
          <label htmlFor="src-method">결제수단</label>
          <select id="src-method" name="method">
            <option>신용카드</option>
            <option>가상계좌</option>
            <option>문화상품권</option>
          </select>
        </div>
        <div className="formrow">
          <label htmlFor="src-mid">원천사 발급 가맹점번호</label>
          <input id="src-mid" name="mid" required />
        </div>
        <div className="notice">실제 채번은 원천사 연동이 필요합니다. 여기서는 입력한 발급 번호를 데모 기준정보에 연결합니다.</div>
        <br />
        <button className="btn primary">데모 등록</button>
      </form>
    </Drawer>
  )
}

const wizardLabels = ['사업자 정보', '계약·원천사', '가맹점 설정', '검토·등록'] as const

/** app.js wizard() — 사업자 등록 4단계(데모 MID만 만든다. 실제 채번·심사 없음) */
export function WizardPanel() {
  const ui = useUi()
  const { wiz, form } = ui
  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    ui.wizardNext(new FormData(e.currentTarget))
  }
  const fields = [
    <>
      <div className="formrow">
        <label htmlFor="wz-name">사업자명 *</label>
        <input id="wz-name" name="name" required defaultValue={form.name || ''} placeholder="예: 모노마켓" />
      </div>
      <div className="formrow">
        <label htmlFor="wz-biz">사업자등록번호 *</label>
        <input id="wz-biz" name="biz" required pattern="[0-9]{3}-[0-9]{2}-[0-9]{5}" defaultValue={form.biz || ''} placeholder="123-45-67890" />
      </div>
      <div className="formrow">
        <label htmlFor="wz-owner">담당자 *</label>
        <input id="wz-owner" name="owner" required defaultValue={form.owner || ''} />
      </div>
    </>,
    <>
      <div className="formrow">
        <label htmlFor="wz-contract">계약 유형</label>
        <select id="wz-contract" name="contract">
          <option>일반 가맹점</option>
          <option>오픈마켓</option>
        </select>
      </div>
      <div className="formrow">
        <label htmlFor="wz-source">원천사</label>
        <select id="wz-source" name="source" defaultValue={form.source}>
          <option>NICE</option>
          <option>키움페이</option>
        </select>
      </div>
      <div className="notice">사업자 등록 → 계약·심사 → 원천사 가맹점번호 연결 → 내부 MID 발급 순서로 진행합니다. 원천사 번호 발급은 별도 연동 대상입니다.</div>
    </>,
    <>
      <div className="formrow">
        <label htmlFor="wz-service">서비스명 *</label>
        <input id="wz-service" name="service" required defaultValue={form.service || form.name || ''} />
      </div>
      <div className="formrow">
        <label htmlFor="wz-cycle">정산 주기</label>
        <select id="wz-cycle" name="cycle" defaultValue={form.cycle}>
          <option>D+3</option>
          <option>반월</option>
          <option>월 정산</option>
        </select>
      </div>
      <div className="formrow">
        <label htmlFor="wz-fee">카드 수수료율 (%, VAT 별도) *</label>
        <input id="wz-fee" name="fee" type="number" min="0" max="100" step="0.01" required defaultValue={form.fee || '2.4'} />
      </div>
    </>,
    <>
      <div className="detailgrid">
        {(
          [
            ['사업자명', form.name],
            ['사업자번호', form.biz],
            ['원천사', form.source],
            ['정산 주기', form.cycle],
            ['서비스명', form.service],
            ['수수료', form.fee + '% · VAT 별도'],
          ] as const
        ).map((x) => (
          <div key={x[0]}>
            <label>{x[0]}</label>
            <strong>{x[1] ?? ''}</strong>
          </div>
        ))}
      </div>
      <div className="notice">내부 MID는 데모 번호로 생성됩니다. 원천사 번호 발급·중복 사업자 확인·심사는 실제 연동 범위입니다. 등록 후 새로고침하면 초기화됩니다.</div>
    </>,
  ]
  return (
    <Drawer>
      <div className="eyebrow">MERCHANT ONBOARDING</div>
      <h2>사업자 등록</h2>
      <div className="wizard">
        {wizardLabels.map((x, i) => (
          <span className={i <= wiz ? 'current' : ''} key={x} aria-current={i === wiz ? 'step' : undefined}>
            {`${i + 1}. ${x}`}
          </span>
        ))}
      </div>
      <form onSubmit={onSubmit}>
        {/* 단계마다 입력을 새로 만든다(원본은 단계마다 innerHTML을 다시 그린다). */}
        <Fragment key={wiz}>{fields[wiz]}</Fragment>
        {/* 원본 style="margin-top:30px"는 uiref-csp.css의 .drawer form>.actions 규칙이 대신한다(CSP). */}
        <div className="actions">
          {wiz ? (
            <button type="button" className="btn" onClick={() => ui.wizardPrev()}>
              이전
            </button>
          ) : null}
          <button className="btn primary">{wiz === 3 ? '등록하고 데모 MID 발급' : '다음 단계 →'}</button>
        </div>
      </form>
    </Drawer>
  )
}

/** app.js accounts() */
export function AccountsPanel() {
  return (
    <Drawer>
      <h2>계정 관리</h2>
      <div className="timeline">
        <p>
          김 운영<small>운영 담당자 · 활성 · 전체 가맹점 조회</small>
        </p>
        <p>
          이 정산<small>정산 담당자 · 활성 · 정산 수정 가능</small>
        </p>
        <p>
          박 승인<small>승인 관리자 · 활성 · 지급 승인 가능</small>
        </p>
      </div>
      <div className="notice">계정 발급·잠금·만료·MFA 및 역할 부여는 실제 인증 시스템 연동 대상입니다.</div>
    </Drawer>
  )
}

/** app.js audit() */
export function AuditPanel() {
  return (
    <Drawer>
      <h2>접속·감사 이력</h2>
      <div className="timeline">
        <p>
          14:32 · 김 운영 · 가맹점 조회<small>조회 대상 mono000001 · 샘플</small>
        </p>
        <p>
          10:20 · 이 정산 · 지급 검증<small>ST-20261102-001 · 샘플</small>
        </p>
        <p>
          09:00 · 김 운영 · 로그인<small>내부 관리자 · 샘플</small>
        </p>
      </div>
    </Drawer>
  )
}

/** app.js openSearch()/searchAll() — 메뉴·샘플 행 검색 modal */
export function SearchModal({ q, setQ }: { q: string; setQ: (q: string) => void }) {
  const ui = useUi()
  const results: ReactNode[] = menus
    .filter((m) => m[1].includes(q))
    .map((m) => (
      <button className="result" key={'m' + m[0]} onClick={() => ui.go(m[0])}>
        {m[1] + ' '}
        <span className="subtle">메뉴 →</span>
      </button>
    ))
  if (q)
    confIds.forEach((id) =>
      ui.rows[id]
        .filter((r) => r.join(' ').toLowerCase().includes(q.toLowerCase()))
        .forEach((r) =>
          results.push(
            <button className="result" key={id + r[1]} onClick={() => ui.detail(id, r[1])}>
              {r[0] + ' '}
              <span className="subtle">{r[1] + ' · ' + title(id)}</span>
            </button>,
          ),
        ),
    )
  const shown = results.slice(0, 20)
  return (
    <section className="modal" role="dialog" aria-modal="true">
      <button className="close" aria-label="닫기" onClick={() => ui.closePanel()}>
        ×
      </button>
      <input id="globalq" aria-label="통합 검색" placeholder="메뉴, 가맹점명 또는 TID 검색" value={q} onChange={(e) => setQ(e.target.value)} />
      <div id="globalresults">{shown.length ? shown : <div className="empty">검색 결과가 없어요</div>}</div>
    </section>
  )
}
