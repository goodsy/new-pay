import type { ErrorData } from './checkoutData'

/** 결제창 오류·결과 화면(AUTHN-001 §6.3): 서버 고정 문구와 서버가 만든 링크(다시 시도·가맹점 failUrl)만. 상태 코드·쿠키 처리는 서버가 이미 했다. */
export function ErrorScreen({ data }: { data: ErrorData }) {
  return (
    <main className="layout single">
      <section className="checkout-card result" aria-label="결제 안내">
        <div className="inner">
          <div className="result-icon" aria-hidden="true">
            !
          </div>
          <p className="result-message" role="alert">
            {data.message}
          </p>
          {(data.retryUrl !== null || data.failRedirect !== null) && (
            <div className="result-actions">
              {data.retryUrl !== null && (
                <a className="primary link-button" href={data.retryUrl}>
                  다시 시도
                </a>
              )}
              {data.failRedirect !== null && (
                <a className="outline link-button" href={data.failRedirect}>
                  가맹점으로 돌아가기
                </a>
              )}
            </div>
          )}
        </div>
      </section>
    </main>
  )
}
