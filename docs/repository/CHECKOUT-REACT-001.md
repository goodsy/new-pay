# CHECKOUT-REACT-001 결제창 화면 React 전환 명세 (rev 0.1)

- 근거 결정: D-FE-001(결제창도 React, 같은 출처 정적 번들), D-ADMIN-008(같은 출처 정적 번들·CORS 없음), D-CHECKOUT-UI-001(결제창 시안 적용, 약관 제외 → CHECKOUT-TERMS-001).
- 기준 명세: [AUTHN-001 §6·§12](AUTHN-001-PAYMENT-WINDOW.md) — 경로·오류 매핑·쿠키·CSRF·헤더·XSS 규칙. 이 작업은 **화면 렌더링 방식만** 바꾼다.
- 재사용: ADMIN-BOOT-001 프론트 빌드 convention `paycore.frontend`(build-logic) — 고정 Node·격리 npm·lockfile 검사·tsc·Vitest JUnit 보고서·`processResources` 연결.

## 1. 범위

| 포함 | 제외(이유) |
|---|---|
| `GET /checkout/s/{handle}` 화면, 모든 결제창 오류·결과 화면(§6.3), CSRF 거절 화면(403)을 React 번들로 렌더 | 약관 동의(CHECKOUT-TERMS-001 — 약관 버전·동의 기록 서버 저장 필요, 원문 미제공) |
| 시안 레이아웃·색·단계 표시·카드사 격자·할부 선택·주문 요약(D-CHECKOUT-UI-001) | 시안의 샘플 상품·이미지·실제 카드사 목록·"프로토타입" 띠·시나리오 테스트 링크·배송비·주문번호(서버 화면 모델에 없음) |
| payment-api `paycore.frontend` 적용(`apps/payment-api/frontend`), `check`에 프론트 검사 등록 | 새 데이터 노출·새 외부 계약·쿠키/CSRF 의미 변경·Domain/금융 TX 변경 |
| payment-api Thymeleaf 의존 제거 판단(§9) | Simulator 화면(Thymeleaf 유지, D-FE-001) |

## 2. 흐름(외부 계약 불변)

경로·방법·상태 코드·redirect 대상·Set-Cookie·Retry-After·고정 문구는 AUTHN-001 §6.1·§6.3 그대로다.

1. `GET /checkout?token=…` → 303 `/checkout/s/{handle}` + `__Host-pc_checkout_<handle>` (변경 없음 — HTML을 쓰지 않는 응답은 실패 화면만 shell로 바뀐다).
2. `GET /checkout/s/{handle}` → Controller가 기존과 같이 handle 형식·쿠키·`CheckoutPageQuery`로 판정 → **shell HTML**(200/401/409/410/503)을 응답한다.
   shell = Vite가 만든 `index.html`(외부 `<script type="module" src="/checkout/assets/checkout.js">`·`<link rel="stylesheet" href="/checkout/assets/checkout.css">`)의
   `<div id="root"></div>` 하나에 서버가 `data-checkout="<HTML 속성 escape된 JSON>"`을 넣은 것.
3. 브라우저가 같은 출처 번들을 받고, React가 `#root`의 `data-checkout`을 읽어 화면을 그린다. **fetch/XHR 없음**(connect-src 불필요).
4. 상태 변경은 기존과 같은 **form POST 네비게이션**(`application/x-www-form-urlencoded`, 숨김 `_csrf`):
   - 결제하기 → `POST /checkout/s/{handle}/authentication` (`cardCompany`·`plan`·`_csrf`) → 303 Simulator 고객 URL.
   - × (취소 확인 후) → `POST /checkout/s/{handle}/cancel` (`_csrf`) → 303 failUrl(code=USER_CANCELLED).
5. Simulator 복귀 `GET …/authentication/return` → 303 successUrl 또는 shell 오류·결과 화면(200/400/410/503).

새 JSON 조회 endpoint는 만들지 않는다: POST 결과(422·409·410·503) 화면도 같은 응답 안에서 그려야 하므로 응답 HTML에 데이터를 싣는 방식이 하나로 충분하고,
조회 endpoint를 더하면 `connect-src`·추가 판정 경로가 생긴다(가역 구현 결정 CR-01).

## 3. 데이터 계약(`data-checkout` JSON — 서버 → 화면, 표시 전용)

화면 모델은 Thymeleaf 모델이 이미 노출하던 값만 쓴다(새 값 없음). 판정은 POST 처리의 잠금 TX가 다시 한다(AUTHN-001 §6.2).

```text
page:  {"screen":"page", "orderName": string|null, "amountDisplay": string, "currency": string, "siteHost": string,
        "cardCompanies": [{"value","label"}], "plans": [{"value","label"}], "notice": string|null,
        "authenticationAction": "/checkout/s/<handle>/authentication", "cancelAction": "/checkout/s/<handle>/cancel",
        "csrfParameter": "_csrf", "csrfToken": <마스킹된 CSRF 토큰>}
error: {"screen":"error", "message": <CheckoutPages 고정 문구>, "retryUrl": string|null, "failRedirect": string|null}
```

| 값 | 출처(기존 Thymeleaf 모델) | 비고 |
|---|---|---|
| orderName·amountDisplay·currency·siteHost·cardCompanies·plans | `CheckoutPageView` 같은 필드 | `handle`·`formActionOrigins`는 싣지 않는다(handle은 action 경로에만 — 기존과 같음) |
| notice | `notice`(422 `NOT_ALLOWED`) | 고정 문구 |
| authenticationAction·cancelAction | 같은 이름 모델 값 | 서버가 handle을 경로 encode |
| csrfParameter·csrfToken | Thymeleaf `th:action`이 넣던 숨김 `_csrf` | 같은 `XorCsrfTokenRequestAttributeHandler` 마스킹 값 |
| message·retryUrl·failRedirect | 같은 이름 모델 값 | 고정 문구·서버 구성 링크만 |

화면 쪽 방어(심층 방어, 서버 값이 기준): JSON 형식·필드 형식이 하나라도 어긋나면 고정 "일시적인 오류입니다."만 그리고 링크·폼을 만들지 않는다.
action = `^/checkout/s/[A-Za-z0-9_-]{22}/(authentication|cancel)$`, retryUrl = `/checkout/s/<handle>` 또는 그 복귀 경로, failRedirect = `https:` 절대 URL만,
csrfParameter = `_csrf`.

## 4. 보안 통제 대응표(이전 → 이후)

| 통제 | 이전(Thymeleaf) | 이후(React shell) | 시험 |
|---|---|---|---|
| 1회용 토큰 교환 | `GET /checkout?token=` 303 + 쿠키 | 변경 없음 | CheckoutWebSpec(기존) |
| 쿠키 세션 | handle별 `__Host-` HttpOnly·Secure·Lax | 변경 없음(JS는 쿠키를 읽지 못함 — HttpOnly) | CheckoutWebSpec(기존) |
| CSRF | `CheckoutCsrfTokenRepository` 파생 토큰, `th:action` 숨김 필드 | 같은 저장소·같은 마스킹 값을 `data-checkout`으로 전달, React가 숨김 `_csrf` 필드로 POST | CheckoutWebSpec·CheckoutAuthenticationWebSpec(shell 데이터에서 토큰 추출), Vitest 폼 필드 |
| CSP | `script-src 'none'`(모든 응답) | **shell HTML 응답만** `script-src 'self'`. 그 밖(redirect·정적 자원·교환 303)은 `script-src 'none'` 유지. 나머지 지시어(`default-src 'none'`·`style-src 'self'`·`img-src 'self'`·`base-uri 'none'`·`frame-ancestors 'none'`·`form-action`) 불변, `connect-src`·`'unsafe-inline'`·`'unsafe-eval'`·외부 origin 없음 | CheckoutWebSpec(shell CSP 정확 일치·비 shell CSP 정확 일치) |
| 그 밖 응답 헤더 | `CheckoutResponseHeadersFilter` | 변경 없음 | CheckoutWebSpec(기존) |
| inline script/style 없음 | 템플릿 `<script` 금지 | shell: 외부 module script·stylesheet만, inline `<script>`·`<style>`·`style=`·`on*=`·원격 URL 금지(빌드 산출물 검사) + 화면 실행 중 `<style>`·`[style]`·동적 link/script 없음 | CheckoutShellScanSpec(위반 fixture 포함), Vitest noRuntimeStyles |
| CDN·원격 글꼴 없음 | 해당 없음 | 시스템 글꼴만, CSS `@import`·`url(http…)`·`@font-face` 금지 | CheckoutShellScanSpec |
| 가맹점 입력 escape | `th:text` | 서버: JSON → HTML 속성 escape(`&`·`<`·`>`·`"`·`'`), 화면: React 텍스트 노드만(`dangerouslySetInnerHTML` 없음) | CheckoutShellSpec(escape), CheckoutWebSpec(`<script>` 주문명), Vitest(태그 주문명이 텍스트) |
| Site host 검사 | 서버(D-AUTHN-002, form-action Site origin) | 변경 없음 | 기존 |
| 카드정보·비밀 비노출 | 카드번호 입력 없음, 오류 화면 누출 검사 | 같음 — 화면에 카드번호·CVC·인증정보 입력 필드 없음(인증은 Simulator), 데이터에 토큰·쿠키 비밀·state·결과 참조 없음(503 확인 중 재시도 링크만 기존 설계대로 복귀 URL) | CheckoutErrorPageLeakSpec(기존 그대로), Vitest(입력 필드 없음) |
| 오류 화면 | 고정 문구 + 서버 링크 | 같음(고정 문구·서버 링크만, 화면은 형식 검사 후 링크 표시) | CheckoutErrorPageLeakSpec, CheckoutWebSpec, Vitest |
| CSRF 거절 화면 | `staticPage` 정적 HTML | shell 오류 화면(같은 고정 문구, 403) | CheckoutWebSpec |
| shell 직접 접근 | 해당 없음 | `index.html`은 정적 자원으로 두지 않는다(`checkout-shell/index.html` classpath, Controller만 응답) | CheckoutWebSpec(`/checkout/index.html` 404) |

CSP 변경(`script-src 'none'` → shell 응답만 `'self'`)은 D-FE-001(결제창 React 전환)의 필연 결과이며 그 밖 완화는 없다. Coordinator 확인 대상(§10 CR-Q1).

## 5. 화면 설계 대응(D-CHECKOUT-UI-001)

| 시안 | 이 화면 | 데이터 |
|---|---|---|
| 상단 브랜드(fingerpay) | "PayCore" + "안심 결제", 오른쪽 "신용카드 결제" | 고정 |
| 왼쪽 eyebrow·큰 제목·설명 | "PAYCORE CHECKOUT", "주문 내용을 확인해 주세요.", 설명 2줄 | 고정 |
| 주문 카드(가맹점·상품·주문번호·금액) | 가맹점 = Site host, 주문명(없으면 행 생략), 결제 금액, 총 결제 금액 | siteHost·orderName·amountDisplay·currency |
| 오른쪽 카드 "신용카드 결제" + × | × = 취소 확인 → 기존 cancel POST | cancelAction·csrf |
| 단계 1 카드 선택 — 2 카드사 인증 — 3 결제 완료 | 같음(1단계 현재) | 고정 |
| 카드사 버튼 격자 | radio 그룹(`fieldset`/`legend`, 실제 `input type=radio name=cardCompany`) — 기본 선택 없음 | cardCompanies |
| 할부 선택 | `select name=plan`, 첫 항목 기본 | plans |
| "N원 결제하기" + 도움말 | 카드사 선택 전 disabled + "카드사를 선택해 주세요", 선택 후 "카드사 인증 화면으로 이동해요" | amountDisplay·currency |
| 약관 체크 | 없음(CHECKOUT-TERMS-001) | — |
| footer | "PayCore" + "카드번호와 인증정보는 이 화면에서 입력하지 않아요" | 고정 |
| 모바일(≤800px) | 한 열로 쌓기, 설명 숨김 | — |

금액 표시: KRW는 `<amountDisplay>원`, 그 밖 통화는 `<amountDisplay> <currency>`(표시 전용 — 서버 서식 그대로, 화면이 계산하지 않음).
이전 Thymeleaf 화면은 카드사·할부 첫 항목을 미리 선택했다. 이 화면은 카드사 미선택으로 시작한다(시안, 서버 판정 불변 — POST에 `cardCompany`가 없으면 기존 400).

## 6. 구현 배치

- `apps/payment-api/frontend/` — Vite + React 19 + TypeScript, 시험 Vitest(jsdom). 의존은 internal-admin과 같은 고정 버전의 부분 집합(Tailwind·shadcn·아이콘 미사용 —
  화면이 하나라 정적 CSS 한 파일로 충분하고 결제창 공급망을 줄인다, CR-02). lockfile 정책(`before`·undici 보안 override)은 internal-admin과 같다.
- 빌드 산출물: `checkout/assets/checkout.js`·`checkout/assets/checkout.css`(고정 이름 — 결제창은 `Cache-Control: no-store`라 hash 불필요) → `static/checkout/assets/`,
  `index.html` → `checkout-shell/index.html`(정적 서빙 밖, payment-api `processResources`가 경로를 옮긴다).
- 서버: `CheckoutShell`(shell 적재·검사·데이터 주입 View), Controller의 `ModelAndView(view 이름)` → `ModelAndView(CheckoutShell View)`. 판정·상태 코드·헤더 코드는 그대로.
- 삭제: `templates/checkout/{page,error}.html`, `static/checkout/assets/checkout.css`(번들 CSS가 같은 경로), `CheckoutPages.staticPage`.

## 7. 시험 계획(TDD — 각 단계 시험 먼저)

1. Kotlin `CheckoutShellSpec`(단위): escape·JSON 왕복·placeholder 1개·inline script/style 있는 shell 거부·CSP 값.
2. Kotlin `CheckoutShellScanSpec`(`CheckoutTemplateScanSpec` 대체): 빌드된 shell·CSS 검사 + 위반 fixture로 검사기 확인.
3. 기존 web 시험 조정(Thymeleaf 마크업 단언만, 보안 주장 강도 유지): CSRF 토큰 추출을 shell 데이터에서, `href=`·`name="_csrf"` 단언을 데이터 필드 단언으로, `<script` 부재 단언을
   "가맹점 입력 원문 없음 + script는 같은 출처 외부 module 하나뿐"으로, page CSP 기대값을 shell CSP로.
4. Vitest: 데이터 검증, 주문 요약, escape, radio 의미·선택 전 disabled, 폼 action/method/필드, 취소 확인, 오류 화면 링크 검증, 카드정보 입력 없음, noRuntimeStyles,
   index.html inline 없음.

## 8. 8대 원칙

이 작업은 화면 계층만 바꾼다. Financial Consistency·Transaction Safety·Idempotency·Concurrency Control·Recoverability·Reconciliation: 서버 판정·TX·잠금·멱등 처리 코드 불변
(기존 시험 그대로 통과가 증거). Auditability·Observability: 기존 지표(`paycore.checkout.*`·`paycore.authentication.*`)·보안 로그(CSRF_REJECTED) 그대로 — CSRF 거절 처리기는
로그를 유지하고 화면만 shell로 바뀐다. JPA·jOOQ: 변경 없음(화면 값은 기존 jOOQ `CheckoutViewReader` → `CheckoutPageView`). Kafka/Redis: 도입 없음.

## 9. Thymeleaf 의존 제거 판단

**제거한다**(payment-api `spring-boot-starter-thymeleaf` 삭제). 근거:

- payment-api에서 Thymeleaf를 쓰던 곳은 결제창 `templates/checkout/{page,error}.html`뿐이었다(조사: main 코드·설정에 `org.thymeleaf`·`spring.thymeleaf` 사용 없음).
  전환 뒤 남은 사용처가 없으므로 의존을 두면 쓰지 않는 템플릿 엔진·자동 구성이 공격면으로만 남는다.
- 시험 대체: ArchitectureSpec "Thymeleaf is used only by the web adapter (AUTHN-001 §17.3)"(위반 fixture `archfixture.web.DomainUsesThymeleaf`)를
  "어떤 클래스도 Thymeleaf에 의존하지 않고 `org.thymeleaf.TemplateEngine`·`SpringTemplateEngine`이 classpath에 없다"로 바꿨다 — 기존 주장(web adapter 밖 금지)을
  포함하는 더 강한 주장이다. 라이브러리가 없으면 위반 fixture를 컴파일할 수 없어 fixture는 지웠다(classpath 부재 단언이 대신 증명).
  Checkout web 클래스 허용 목록(D-AUTHN-006 규칙)에서 `org.thymeleaf.`를 뺐다(좁힘).
- `CheckoutTemplateScanSpec`(Thymeleaf 템플릿 XSS 검사)은 검사 대상이 없어져 `CheckoutShellScanSpec`(빌드된 shell·CSS 검사 + 위반 fixture)으로 대체했다.
- Simulator(`apps/institution-simulator`)·버전 카탈로그의 Thymeleaf 항목은 그대로다(D-FE-001). internal-admin은 ADMIN-BOOT-001에서 이미 제거했다.

## 10. 구현 결정·확인 요청

- CR-01 데이터 전달 = shell `data-checkout` 속성(조회 endpoint 없음). 가역.
- CR-02 결제창은 Tailwind/shadcn 없이 정적 CSS. 가역.
- CR-03 CSS·JS 고정 파일 이름. 가역.
- CR-Q1(Coordinator 확인): CSP `script-src` 변경 범위 = shell 응답만 `'self'`. D-FE-001의 필연 결과로 보고 그 밖 지시어는 그대로 둔다.

## 11. 결과 기록(구현자 보고 — 자기 판정 아님, 2026-11-03)

### 11.1 TDD 순서

| 단계 | 시각(UTC) | 결과 |
|---|---|---|
| 프론트 RED | 00:46:28 | `frontendTest` 실패 — 5 시험 파일 모두 구현 모듈(`./App`·`./checkoutData`) 없음 |
| 프론트 GREEN | 00:49 | Vitest 25/25, `frontendTypecheck`·`frontendBuild` 성공 |
| Kotlin RED | 00:53:23 | 골격 `CheckoutShell`(TODO) + Thymeleaf Controller 상태에서 20 중 12 실패: CheckoutShellSpec 4(미구현), CheckoutShellScanSpec 1(Thymeleaf 템플릿 잔존), CheckoutWebSpec 3·CheckoutAuthenticationWebSpec 4(응답에 shell 데이터 없음 — Thymeleaf 마크업) |
| Kotlin GREEN | 00:57:52 | 대상 6 spec 70 중 69 통과, 1 실패 = 시험 정규식 오류(마스킹 CSRF 값 base64 padding `=`) → 시험 정규식 수정(완화 아님 — 형식 단언을 실제 Spring 마스킹 형식에 맞춤) |
| 모듈 검사 | 01:02:03 → BUILD SUCCESSFUL 12m15s | `:apps:payment-api:check` exit 0, payment-api JUnit 1972/0/0/0(test-summary PASS) |
| 프론트 재실행 | 01:15 | `frontendLockfileCheck`·`frontendTypecheck`·`frontendTest --rerun` exit 0, Vitest 25/0/0/0(test-summary PASS) |

### 11.2 시험 수 변화

- payment-api Kotlin: 1967 → 1972(+5) — 삭제 CheckoutTemplateScanSpec 2, 추가 CheckoutShellSpec 4·CheckoutShellScanSpec 3, ArchitectureSpec 수 불변(규칙 교체).
- payment-api Vitest(신규): 25.

### 11.3 기존 시험 기대 변경(보안 주장 강도 유지·강화)

| 시험 | 이전 기대 | 새 기대 | 근거 |
|---|---|---|---|
| CheckoutWebSpec 화면 | `body shouldNotContain "<script"`, `name="_csrf"`, page CSP `script-src 'none'` | `<script>`(가맹점 입력 markup) 없음 + 응답 script는 같은 출처 번들 하나뿐, shell 데이터의 orderName 원문·action·CSRF 형식, page CSP = 같은 지시어 + `script-src 'self'`(정확 일치), 데이터에 handle 없음 | §4 CSP 변경(CR-Q1) |
| CheckoutWebSpec 교환 303 | CSP 미검사 | CSP 정확 일치(`script-src 'none'` 유지) | 강화 |
| CheckoutWebSpec 401·400·403 | 고정 문구 | + shell CSP 정확 일치, 링크 없음, script는 번들뿐 | 강화 |
| CheckoutWebSpec connector | `checkout.css` 200 | + `checkout.js` 200 `text/javascript`·공개 포트 404, `/checkout/index.html`·shell 경로 404, 정적 자원 CSP `script-src 'none'` | 강화 |
| CheckoutAuthenticationWebSpec·CheckoutBrowser | 숨김 필드 정규식으로 CSRF 추출, `href="/checkout/s/…"`, 422 `name="_csrf"` | shell 데이터의 csrfToken, retryUrl·failRedirect 값 정확 일치, 503 retryUrl = 같은 복귀 경로, 422 notice·CSRF 존재 | 마크업 → 데이터 |
| CheckoutTemplateScanSpec | Thymeleaf 템플릿 XSS 규칙 | CheckoutShellScanSpec(빌드 shell·CSS, 위반 fixture 9개) | §9 |
| ArchitectureSpec Thymeleaf | web adapter만 의존 | 어떤 클래스도 의존 안 함 + classpath 부재 | §9 |
| CheckoutErrorPageLeakSpec | — | 변경 없음(그대로 통과 — 8개 상태 화면 바이트 누출 검사) | — |

### 11.4 미수행·한계

- 실제 브라우저(Chrome 등) 렌더·CSP 위반 보고 확인은 하지 않았다(jsdom Vitest + 서버 HTTP 시험만). 시안 대비 시각 확인도 하지 않았다.
- 하네스 등록(commands.json·cve-scan FRONTENDS)은 Coordinator 몫 — 아래 §11.5.

### 11.5 하네스 반영 필요(Coordinator)

- `GRADLE-CHECK`: `expectedReports`에 `apps/payment-api/build/test-results/frontend/TEST-*.xml`, `outputMustMatch`에 `^> Task :apps:payment-api:frontend{NodeVerify,Install,LockfileCheck,Typecheck,Test}$` 5줄,
  `minTests`·`TEST-SUMMARY` 하한 +5(Kotlin, payment-api 1967 → 1972).
- `TEST-SUMMARY-FRONTEND`: argv에 `apps/payment-api/build/test-results/frontend` 추가, 하한 +25(internal-admin 기존 하한 + 25).
- `harness/scripts/cve-scan.py` `FRONTENDS`에 `apps/payment-api/frontend` 추가(test_module_registry `test_cve_scan_covers_every_frontend_lockfile`이 요구).
- `GRADLE-DEPS-PAYMENT-API`: runtime 트리에서 Thymeleaf가 빠진다(재실행 증거 갱신).
