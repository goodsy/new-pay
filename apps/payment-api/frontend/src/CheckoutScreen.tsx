import { useState } from 'react'
import { amountLabel, type PageData } from './checkoutData'

/**
 * 결제창 화면(AUTHN-001 §6.2 값만, D-CHECKOUT-UI-001 배치). 상태 변경은 기존 form POST 네비게이션 그대로다 — fetch 없음, 숨김 `_csrf` 필드(서버 마스킹 값).
 * 카드번호·인증정보 입력은 없다(인증은 카드사(Simulator) 화면). 가맹점 입력(주문명)은 React 텍스트로만 그린다.
 */
export function CheckoutScreen({ data }: { data: PageData }) {
  const [cardCompany, setCardCompany] = useState<string | null>(null)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const amount = amountLabel(data.amountDisplay, data.currency)
  const csrf = <input type="hidden" name={data.csrfParameter} value={data.csrfToken} />

  return (
    <main className="layout">
      <section className="intro">
        <div className="eyebrow">PAYCORE CHECKOUT</div>
        <h1>
          주문 내용을{' '}
          <br />
          확인해 주세요.
        </h1>
        <p className="intro-text">
          주문을 확인하고 사용할 카드를 선택해 주세요.
          <br />
          카드사 인증 후 결제가 완료돼요.
        </p>
        <section className="order-card" aria-label="주문 정보">
          <div className="merchant">
            <span className="shop-icon" aria-hidden="true">
              {data.siteHost.charAt(0).toUpperCase()}
            </span>
            <div>
              <b>{data.siteHost}</b>
              <small>가맹점</small>
            </div>
            <span className="tag" aria-hidden="true">
              주문 정보
            </span>
          </div>
          <dl className="breakdown">
            {data.orderName !== null && (
              <div className="order-name">
                <dt>주문명</dt>
                <dd>{data.orderName}</dd>
              </div>
            )}
            <div>
              <dt>결제 금액</dt>
              <dd>{amount}</dd>
            </div>
            <div className="total">
              <dt>총 결제 금액</dt>
              <dd>
                {data.amountDisplay}
                <small>{data.currency === 'KRW' ? '원' : ` ${data.currency}`}</small>
              </dd>
            </div>
          </dl>
          <div className="order-foot">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <rect x="5" y="10" width="14" height="11" rx="3" />
              <path d="M8 10V7a4 4 0 0 1 8 0v3" />
            </svg>
            카드 인증은 카드사 화면에서 진행돼요
          </div>
        </section>
      </section>

      <section className="checkout-card" aria-label="신용카드 결제창">
        <div className="inner">
          <div className="card-top">
            <b>신용카드 결제</b>
            <button type="button" className="icon-btn" aria-label="결제창 닫기" onClick={() => setConfirmingCancel(true)}>
              ×
            </button>
          </div>

          {confirmingCancel && (
            <div className="confirm" role="alertdialog" aria-labelledby="cancel-title" aria-describedby="cancel-desc">
              <p id="cancel-title" className="confirm-title">
                결제를 취소할까요?
              </p>
              <p id="cancel-desc" className="confirm-desc">
                취소하면 결제가 진행되지 않고 가맹점 화면으로 돌아가요.
              </p>
              <form method="post" action={data.cancelAction} className="confirm-actions">
                {csrf}
                <button type="button" className="outline" autoFocus onClick={() => setConfirmingCancel(false)}>
                  계속 결제
                </button>
                <button type="submit" className="danger">
                  결제 취소
                </button>
              </form>
            </div>
          )}

          <ol className="steps" aria-label="결제 단계">
            <li className="on" aria-current="step">
              <i>1</i>카드 선택
            </li>
            <li>
              <i>2</i>카드사 인증
            </li>
            <li>
              <i>3</i>결제 완료
            </li>
          </ol>

          {data.notice !== null && (
            <p className="notice" role="alert">
              {data.notice}
            </p>
          )}

          <h2>어떤 카드로 결제할까요?</h2>
          <p className="sub">결제에 사용할 카드사를 선택해 주세요.</p>

          <form method="post" action={data.authenticationAction} onSubmit={() => setSubmitting(true)}>
            {csrf}
            <fieldset className="cards">
              <legend>카드사 선택</legend>
              <div className="card-grid">
                {data.cardCompanies.map((company) => (
                  <label key={company.value} className={cardCompany === company.value ? 'card-btn selected' : 'card-btn'}>
                    <input
                      type="radio"
                      className="sr-only"
                      name="cardCompany"
                      value={company.value}
                      required
                      checked={cardCompany === company.value}
                      onChange={() => setCardCompany(company.value)}
                    />
                    {company.label}
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="pay-option">
              <label htmlFor="plan">할부 선택</label>
              <select id="plan" name="plan" defaultValue={data.plans[0]?.value}>
                {data.plans.map((plan) => (
                  <option key={plan.value} value={plan.value}>
                    {plan.label}
                  </option>
                ))}
              </select>
            </div>
            <p className="install-note">할부 가능 여부와 수수료는 카드사 조건에 따라 달라져요.</p>

            <div className="pay-bottom">
              <button type="submit" className="primary" disabled={cardCompany === null || submitting}>
                {amount} 결제하기
              </button>
              <p className="button-note" aria-live="polite">
                {cardCompany === null ? '카드사를 선택해 주세요' : '카드사 인증 화면으로 이동해요'}
              </p>
            </div>
          </form>
        </div>
      </section>
    </main>
  )
}
