# PayCore 화면·UI 구조 진단 (FRONTEND-ASSESSMENT)

- 작성일 2026-11-20, 기준 커밋 `378aff7`. 분석 단계라 소스는 바꾸지 않았고 vite·vitest도 실행하지 않았다.
- 표기
  - **[사실]**: 파일:줄, grep, `git show --numstat`으로 확인한 내용. "~"가 붙은 값은 grep 근사치다.
  - **[추정]**: 해석이나 제안.

## 1. 결론 요약

1. **보안·금융 장치는 잘 갖춰져 있다.** [사실]
   - API 단일 진입점, CSRF, 세션 종료 처리, 내부 키 비노출 DOM 검사, CSP(style 속성 0)가 있다.
   - 결과를 알 수 없을 때 같은 submissionId로만 다시 보내는 잠금이 있다.
   - 결제창은 작고(819줄) 경계가 분명하다. form POST만 쓰고 카드정보 입력 칸이 없다.
2. **비용은 "스타일 체계 두 개 공존"과 "구조 단언 시험"에서 나온다.** [사실]
   - shadcn/Tailwind와 시안 workspace.css를 둘 다 쓰는 파일이 14개다.
   - `workspace` 플래그로 한 컴포넌트가 두세 가지 모양을 그린다. 분기 17곳, 전달 19곳.
   - 최근 레이아웃 변경 6건에서 시험 순증이 main 순증의 **3.6배**였다.
3. **결과 불명 잠금 로직이 세 곳에 복사돼 있고, 한 곳(개통 1인 명령)은 규칙이 다르다.** [사실 — 아래 §5]
   - 금융 안전과 직결되므로 정비의 첫 번째 대상이다. [추정]

## 2. 구조 [사실]

| 항목 | 관리자 콘솔 `apps/internal-admin/frontend` | 결제창 `apps/payment-api/frontend` |
|---|---|---|
| 런타임 | React 19.3, clsx, tailwind-merge, cva, lucide-react | React 19.3만 |
| 개발 | Vite 8.3, Tailwind 4.3.3, Vitest 5, RTL 16, jsdom, TypeScript 7 | Tailwind 없음 |
| shadcn/ui | 패키지 없이 `components/ui/*` 9파일(227줄)을 복사해 씀 | 없음 |
| 빌드·시험 실행 | package.json에 build·test 스크립트가 없다. Gradle `paycore.frontend` 플러그인이 tsc·vitest를 argv로 직접 실행한다 | 같음 |
| 규모 | main 13,595줄, test 12,083줄(0.89배). 로컬 미리보기 전용 uiref 3,622줄은 별도 | 전체 819줄, 시험 23개 |

**관리자 src 구성**

| 디렉터리 | main 파일 / 줄 | test 파일 / 줄 |
|---|---|---|
| screens | 14 / 6,120 | 40 / 9,118 |
| components | 28 / 3,181 | 6 / 525 |
| api | 2 / 1,186 | 1 / 139 |
| lib | 16 / 1,196 | 10 / 865 |
| 그 밖(App, navigation, css, errors, layout, test 도우미) | — | — |

**큰 파일**

| 파일 | 줄 | 내용 |
|---|---|---|
| adminApi.ts | 1,094 | 타입이 약 920줄, 메서드 60개 |
| MerchantRegistrationScreen | 978 | |
| MerchantsScreen | 849 | useState 23개 |
| BusinessProfileFormScreen | 685 | |
| AcquisitionsScreen | 615 | |
| BusinessesScreen | 573 | |
| MerchantActivationArea | 554 | |
| HomeScreen | 524 | |
| workspace.css | 394 | |
| App.tsx | 321 | |

## 3. 화면·상태·API [사실]

- **화면 전환**: 라우터 없이 `useState<View>` 하나로 화면을 고른다(App.tsx:95).
  - 메뉴 표시는 `pagesFor`/`menusFor`가 정하고, 권한 판정은 서버가 한다.
  - flush 판정은 page 10개를 하드코딩한 OR 목록이다(App.tsx:265).
- **화면 목록**: 운영 홈, 승인함, 내 요청, 사업자·가맹점(가맹점·사업자 탭과 등록 하위 화면), 결제 관리(보정 취소·거래 조회), 매입·대사(대사 발견·매입 현황), 계정·권한(계정·접속/감사 이력), 로그인·계정 등록.
- **상태**
  - App 수준 ScreenMemory가 4개 있다. 로그인·세션 종료·로그아웃 세 곳에서 같은 리셋 코드가 반복된다.
  - `useRemembered`(useSyncExternalStore 기반)는 등록 화면에서만 쓰고, 나머지는 지역 useState다.
  - 브라우저 저장소는 테마만 쓴다.
- **API**
  - `client.ts` 93줄이 단일 진입점이다. 경로는 `/api/`만 허용하고, 상태를 바꾸는 요청에 XSRF를 붙이며, `redirect:'error'`·`no-store`를 쓴다.
  - 오류는 `ConsoleErrorCode`로 바꾼 뒤, 화면에서 `SESSION_ENDING` 분기(21파일)와 화면별 문구 표로 처리한다.
  - 같은 justification 경로를 부르는 메서드가 3개 있다.

## 4. 스타일·공통 컴포넌트 [사실]

### 4.1 두 스타일 체계 공존

| 항목 | shadcn/Tailwind | 시안 workspace.css |
|---|---|---|
| 버튼 | `<Button>` 41곳 | `.btn` 52곳 |
| 표 | `<Table>` 15곳 | `.tablewrap` 26곳 |
| 카드 | `<Card>` 1곳 | `card` 14곳 |
| 사용 파일 | 25 | 22 |
| **두 체계를 함께 쓰는 파일** | **14** | |

- 둘이 같이 동작하도록 `@layer theme, base, workspace, components, utilities` 순서로 묶었다(styles.css:10). 이 순서를 시험이 확인한다.

### 4.2 `workspace` 플래그 분기

| 위치 | 분기 |
|---|---|
| ActionButton | `.btn`과 shadcn `Button` 중 하나 |
| RegistrationStep | `workspace`·`rows` 두 플래그로 세 가지 모양 |
| BusinessProfileFormScreen | 조건 12곳 |

- 합계는 플래그 3종, 분기 17곳, 전달 19곳이다.

### 4.3 같은 역할의 중복 구현

| 역할 | 구현 방식 |
|---|---|
| 입력 줄 | `Field`(위에 label, 30곳)와 `FormRow`(왼쪽 140px, 28곳). AccountsScreen에서는 둘이 섞여 있다 |
| 상세 한 줄 | `DetailRow`, 등록 화면 지역 `Row`(.detailgrid), PaymentOps `.detailgrid`, 승인 상세 3종의 직접 작성 dt/dd — 4~5가지 |
| 섹션 카드 | `InfoCard`와 BusinessProfileForm 지역 `Section`이 같은 class 문자열을 각자 가진다 |
| 상세 패널 | `DetailPanels`를 화면마다 따로 만든다(3곳) |
| 지역 컴포넌트 이름 | `Row`가 서로 다른 뜻으로 세 번 쓰인다 |

### 4.4 workspace.css(394줄)

| 구역 | 내용 |
|---|---|
| 시안 원본 14-205 | 원본 블록 순서를 시험(WorkspaceShell:312)이 대조하므로 사실상 고칠 수 없다 |
| 적응 규칙 약 100줄 | `!important` 3곳 |
| 다크 테마 64 선택자 | styles.css token과 같은 hex를 한 번 더 정의 |

- 12줄의 주석 "시안에는 다크 모드가 없어"는 실제와 맞지 않는 낡은 주석이다.

### 4.5 CSP 장치

- main 코드의 `style=`는 0곳이다.
- 테마는 `data-theme` 속성으로만 바꾼다.
- `noRuntimeStyles` 시험 11개(결제창 3개)와 CSS 외부 자원 금지 정규식 시험이 있다.
- `assetsInlineLimit:0`으로 자산을 인라인하지 않는다.

## 5. 금융 안전 장치(보존 자산)와 발견 사항

| 장치 | 위치 [사실] | 시험 [사실] |
|---|---|---|
| ① 결과 불명 잠금(등록 단계) | RegistrationStep.tsx:122-163 `sendStep` | submissionId 언급 76줄/8파일, "같은 내용 다시 보내기" 7파일 |
| ② 같은 규칙을 **복사** | BusinessProfileFormScreen.tsx:89,183-205 | 사업자 폼·App 탭 시험 |
| ③ 개통 1인 명령 `useOneShot` | MerchantActivationArea.tsx:191-217 | 개통 시험 |
| 세션 경계 | `hasUnresolvedRequest`로 결과 미확정 요청이 있으면 로그인 화면에서 한 번 안내 | App 시험 |
| 결과 불명 중 다른 사업자로 넘기기 차단 | MerchantRegistrationScreen:227-232 | App 탭 시험 |
| 내부 키(mer_/biz_) 비노출 | `test/domScan.ts`가 텍스트·모든 속성·쪼개진 텍스트까지 검사 | 21파일 |
| 감사 안내 문구, AUDIT_UNAVAILABLE을 결과 미확정으로 처리 | 각 화면 | 각 화면 시험 |
| CSRF·같은 출처 | client.ts:63-80. 결제창은 form POST, 숨김 `_csrf`, action 경로 검증, https 링크만 허용 | client·checkoutData 시험 |

**발견 [사실]**

- 잠금 규칙이 세 곳에 복사돼 있다. UNKNOWN_CODES 상수도 3개다.
- **③ useOneShot은 컴포넌트 useState에 상태를 둔다.** 그래서 탭을 옮기거나 상세를 닫으면 unknown 상태와 submissionId가 사라진다.
- ③은 재전송 중 409가 아닌 오류(503·429)가 나면 `failed`로 잠금을 푼다. ①의 "재전송 중 409 외에는 잠금 유지" 규칙과 다르다.
- 승인 요청 계열(첫 MID·계약·코드 변경·재매입·보정 취소)은 submissionId가 없다. 중복은 서버 `DUPLICATE_UNRESOLVED`가 막는다.

**[추정]**

- ③의 차이가 명세상 허용된 것인지는 대조하지 않았다.
- 서버 멱등 키가 같다면 금융 이중 효과로 이어지지는 않을 수 있다. 그래도 "같은 키로만 재전송" 원칙과 어긋날 수 있으므로 **재정비 1순위로 확인이 필요하다.**

## 6. 시험 [사실]

| 지표 | 값 |
|---|---|
| 시험 파일 | 66개, `it/test` ~551 |
| 역할 질의(getByRole 등) | 1,438줄/52파일 |
| userEvent | 679줄/51파일 |
| API 호출 단언 | 260줄/38파일 |
| `toHaveClass` | 210줄/16파일 |
| `querySelector` | 304줄/21파일 |
| CSS·소스 파일 직접 읽기(`readFileSync`) | 34줄/11파일 |

- **구조 단언 전용 파일**(`*.workspace.test.tsx` 10개, layout 시험, WorkspaceShell)
  - 규모: 12개 파일, 2,595줄(시험 줄의 21%), 시험 87개(16%).
  - 예: `shape(panel)` DOM 경로 배열 단언, `:scope > .cardnote > p.subtle` 선택자 단언, 시안 원본 CSS 문자열 대조.
- **동작 위주 대형 시험은 class 단언이 거의 없다.** MerchantRegistrationScreen.test(1,615줄, 시험 59개)에 1줄뿐이다.

**레이아웃 변경 6커밋 변경량**

| 커밋 | main | test |
|---|---|---|
| bab658b | +294 −86 | +160 −4 |
| 562950f | +161 −39 | +356 −27 |
| 71d0c48 | +140 −114 | +148 −32 |
| 0530437 | +78 −75 | +155 −16 |
| 08b3737 | +169 −235 | +267 −7 |
| 514685f | +109 −92 | +110 −0 |
| **합계** | **+951 −641(순증 310)** | **+1,196 −86(순증 1,110)** |

- [추정] 모양만 바꿔도 구조 단언을 새로 쓰고 고쳐야 해서 UI 변경 비용이 약 두 배가 된다. 낡은 단언은 지워지지 않고 쌓인다.

## 7. 분류: 유지 / 개선 / 재구현 [추정]

| 분류 | 대상 | 이유 | 규모 |
|---|---|---|---|
| **유지** | client.ts, errors/*, screenMemory, navigation, lib 도메인 헬퍼, domScan, noRuntimeStyles, 결제창 전체 | 작고 단일 책임, 보안·금융 규칙 집약, 동작 시험 | 변경 없음 |
| 유지 + 정리 | App.tsx | 세션 리셋을 `resetSession()` 하나로, flush 하드코딩을 메뉴 메타데이터로 | 수십 줄 |
| **개선 ① 최우선** | 잠금 3곳 → 공통 `useUnknownLockedSubmission`(ScreenMemory 기반) + UNKNOWN_CODES 하나. ③의 규칙 차이 확인·정렬 | 금융 안전 | 약 200줄 + 시험 재사용 |
| 개선 ② | 스타일을 시안 쪽 하나로 정하고 `workspace` 플래그 제거 | 분기 17·전달 19 제거, RegistrationStep 모양 하나로 | 중 |
| 개선 ③ | Field → FormRow, 상세 행 4~5종 → 하나 | 같은 문서 모양 중복 | 사용처 약 80곳 |
| 개선 ④ | 다크 구역을 token 참조로 바꾸고 낡은 주석 정리, shadcn 사용이 줄면 components/ui 축소 | hex 중복 | 소~중 |
| 개선 ⑤ | 구조 단언 → 역할·이름·접근성 단언. CSS 원문 대조 시험("원본 동결")은 **사용자 결정** | 변경 비용 2배 | 약 2,600줄 대상 |
| 개선 ⑥ | adminApi.ts 타입을 도메인별 파일로 분리, justification 3중 메서드 정리 | 충돌·리뷰 비용 | 소~중 |
| **재구현 후보** | MerchantsScreen, MerchantRegistrationScreen, AcquisitionsScreen, MerchantActivationArea → 목록 / 상세 패널 / 명령 영역으로 분할 | 상태 과다, 책임 혼재. 동작 시험을 안전망으로 유지하는 조건에서만 | 화면당 수백 줄 |
| **재구현 비권장** | 결제창 | 작고 경계 명확 | — |

**권장 순서**: ① 잠금 통합 → ② 플래그 제거 → ⑤ 구조 단언 축소 → ③ 줄 컴포넌트 통합 → 큰 화면 분할.

## 8. 사실 vs 추정

- **사실**
  - §2~§6의 줄 수, 파일 수, grep 수치, 파일:줄 인용, 커밋 numstat
  - 의존성 버전
  - 두 스타일 체계를 함께 쓰는 파일 14개, 플래그 분기 수
  - `!important` 3곳, 다크 선택자 64곳, 낡은 주석
  - `style=` 0곳
  - 잠금 3곳 복사와 ③의 규칙 차이
  - 승인 요청에 submissionId가 없음
- **추정**
  - 구조 단언 때문에 변경 비용이 두 배가 된다는 해석
  - ③의 명세 적합성
  - 분류·규모·순서
  - `it` 수와 구조 단언 비율은 grep 근사치
- **확인 안 함**: 시험·빌드 실행
