import type { CheckoutData } from './checkoutData'
import { CheckoutScreen } from './CheckoutScreen'
import { ErrorScreen } from './ErrorScreen'

/** 결제창 틀(D-CHECKOUT-UI-001): 브랜드 머리·본문·바닥. 본문은 서버 데이터의 screen으로만 고른다(라우터 없음 — 경로는 서버가 정한다). */
export function App({ data }: { data: CheckoutData }) {
  return (
    <div className="shell">
      <header className="site-header">
        <span className="brand">
          <span className="logo" aria-hidden="true">P</span>
          <span className="brand-name">PayCore</span>
          <span className="brand-line" aria-hidden="true" />
          <small>안심 결제</small>
        </span>
        <span className="secure">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z" />
            <path d="m8 12 3 3 5-6" />
          </svg>
          신용카드 결제
        </span>
      </header>
      {data.screen === 'page' ? <CheckoutScreen data={data} /> : <ErrorScreen data={data} />}
      <footer className="site-footer">
        <span className="footer-brand">PayCore</span>
        <span>카드번호와 인증정보는 이 화면에서 입력하지 않아요</span>
      </footer>
    </div>
  )
}
