import { Fragment } from 'react'
import { menus, title } from '../data'
import { useUi } from '../context'

/** index.html aside + app.js drawNav() — 1단 메뉴, PAYMENT OPERATIONS(i=1)·WORKSPACE(i=7) 구분 label */
export function Sidebar() {
  const ui = useUi()
  return (
    <aside>
      <a className="brand" href="#home">
        <i>p</i> pg workspace
      </a>
      <div className="workspace">
        핑거페이 <small>내부 관리자</small> ⌄
      </div>
      <nav id="nav">
        {menus.map((m, i) => (
          <Fragment key={m[0]}>
            {i === 1 ? <div className="label">PAYMENT OPERATIONS</div> : i === 7 ? <div className="label">WORKSPACE</div> : null}
            <button className={ui.page === m[0] ? 'active' : ''} aria-current={ui.page === m[0] ? 'page' : undefined} onClick={() => ui.go(m[0])}>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d={m[2]} />
              </svg>
              {m[1]}
              {m[0] === 'risk' ? <em>3</em> : null}
            </button>
          </Fragment>
        ))}
      </nav>
      <div className="bottom">
        <button onClick={() => ui.go('map')}>▦　메뉴 개편 지도</button>
        <hr />
        <b>김 운영</b>
        <small>운영 관리자 · 데모</small>
      </div>
    </aside>
  )
}

export function Header() {
  const ui = useUi()
  return (
    <header>
      <span id="crumb">{'워크스페이스　/　' + title(ui.page)}</span>
      <button className="search" onClick={() => ui.openPanel({ kind: 'search' })}>
        {'⌕　메뉴, 가맹점, TID 검색　 '}
        <kbd>⌘ K</kbd>
      </button>
      <span className="tag blue">프로토타입</span>
    </header>
  )
}

export function Footer() {
  return <footer>{'PG WORKSPACE　　화면 설계용 샘플 데이터 · 실제 결제 및 송금이 실행되지 않습니다'}</footer>
}
