# ADMIN-DEMO-LOGIN-001 데모 간편 로그인 명세 (rev 0.3)

- **rev 0.1** (2026-11-24, Implementer 초안 + 구현 — 사용자 결정 D-ADMIN-AUTH-DEMO-001 적용). Reviewer 독립 검토·Verifier 검증 전, 자기 완료 판정 아님.
- **rev 0.2** (2026-11-24, Implementer — 독립 보안 리뷰 REVIEW_PASS(MAJOR 0) 후속 + 사용자 결정 ③(Q-DEMOLOGIN-3 — 데모 간편 로그인 세션은 재인증 생략, Coordinator 기록)):
  §8 결정 ③ 적용(세션 표시 + 켜짐 조건일 때만 승인·반려·마스킹 해제 TOTP 생략, 감사 `STEP_UP_SKIPPED`), 리뷰 MINOR-1(감사 실패 rollback 시험), MINOR-2(a 모든 주소 허용 기동 거부·b 키 분류 보고·c WARN + gauge),
  MINOR-4(SecurityConfig 모드 비교 정규화), §5 거부 시 세션 로그인 상태 지움, C-6 해소(감사 라벨). 자기 완료 판정 아님.
- **rev 0.3** (2026-11-24, Implementer — 보안 재리뷰 REVIEW_PASS(MAJOR 0) MINOR-A~C·NIT-a~c): 2-TX 경로 시험(T-11b), 기능을 끈 뒤 남은 간편 로그인 세션 폐기(§4 — MINOR-B),
  간편 로그인 성공 응답 플래그를 `/api/auth/me`와 같은 규칙으로(§3 — NIT-c), 재로그인 시 표시 지움 시험(T-13). 자기 완료 판정 아님.
- 표기: 사용자 결정 = docs/DECISIONS.md. C-n = 구현자 가역 선택(Coordinator 확인 대상). R-n = 위험·한계. T-n = 시험.
- 근거: **D-ADMIN-AUTH-DEMO-001**(Q-DEMOLOGIN-1 "데모 계정 고르기", Q-DEMOLOGIN-2 "데모에서만, Tailscale 포함"), ADMIN-BOOT-001 §4.3.2(2단계 로그인)·§4.4(세션)·§4.6(감사)·§4.8.1(IP 허용 목록)·§4.8.2(배치 모드·LIVE 가드), D-ADMIN1-001(새 로그인 우선)·D-ADMIN1-005(실패 잠금).

## 0. 한눈에

| 항목 | 내용 |
|---|---|
| 무엇 | 로그인 화면 위 "데모 간편 로그인" 카드: 설정에 등록된 데모 계정 버튼(표시명·역할)을 누르면 비밀번호·TOTP 없이 **그 계정의 정상 로그인 세션**을 발급 |
| 왜 | 초기 개발 중 Demo에서 OTP 앱 없이 운영자·승인자·보안관리자를 오가며 2인 승인 흐름을 혼자 시험(사용자 요청 2026-11-24) |
| 켜짐 | `paycore.admin.deployment-mode=DEMO` **그리고** `paycore.admin.demo-quick-login.enabled=true` **그리고** 허용 계정 목록 1개 이상. 하나라도 아니면 엔드포인트 없음(404) |
| LIVE | `enabled=true`이면 **기동 거부**(LIVE 가드와 같은 방식 — 설정 이름만 보고) |
| 바뀌지 않는 것 | 세션 고정 방지(세션 ID·CSRF 재발급), 쿠키 속성, idle·절대 한도, 새 로그인 우선(세대 +1), 역할 = 계정 현재 행, 2인 승인(요청자 ≠ 승인자), IP 허용 목록, 감사, 일반 2단계 로그인. 일반 로그인 세션의 재인증 TOTP |
| 재인증 | 결정 ③: **간편 로그인 세션 + 켜짐**일 때만 승인·반려·마스킹 해제의 TOTP 검증 생략(§8) |
| 운영 준비 | **반드시 끄고 코드 제거를 검토하는 임시 기능**(§9 체크리스트) |

## 1. 사용자 결정(D-ADMIN-AUTH-DEMO-001)

1. 방식 = **데모 계정 고르기**. 로그인 자체를 끄는 방식(감사·역할·2인 승인 무력화)은 채택하지 않는다. 데모 계정은 실제 계정 행이며 역할·감사·2인 승인 규칙이 그대로다.
2. 범위 = **데모에서만, Tailscale 포함**. DEMO 모드 + 명시 설정 둘 다일 때만 동작, LIVE에서 설정이 켜져 있으면 기동 거부, 화면에 "데모 간편 로그인" 표시. 접속 주소 제한은 기존 IP 허용 목록이 그대로 한다(Tailscale 대역 포함은 Demo 배치 설정 몫).
3. (Q-DEMOLOGIN-3) 재인증 = **데모에서 생략**. 켜짐 조건(DEMO + enabled)과 세션이 간편 로그인으로 만들어졌다는 표시가 둘 다일 때만 승인·반려·마스킹 해제의 재인증 TOTP 검증을 건너뛴다. 2인 승인 규칙·역할 검사·감사는 그대로, 생략 사실을 감사에 남긴다.

## 2. 켜짐 조건과 거부 동작

| deployment-mode | enabled | accounts | 결과 |
|---|---|---|---|
| DEMO | 없음/false | 무관 | 꺼짐 — 엔드포인트 미등록(GET·POST 404 `NOT_FOUND`), 화면 버튼 없음 |
| DEMO | true | 1개 이상(형식 맞음) | **켜짐** |
| DEMO | true | 비었음 | **기동 거부**(`paycore.admin.demo-quick-login.accounts is required when enabled`) |
| DEMO | true | 형식 오류 항목 | **기동 거부**(`… accounts contains an invalid login name` — 값은 보고하지 않음) |
| LIVE | true | 무관 | **기동 거부**(`paycore.admin.demo-quick-login.enabled is not allowed outside DEMO`) — 웹·1회성 명령 모드 모두(가드 bean은 두 모드에서 만든다) |
| LIVE | 없음/false | 무관 | 꺼짐(엔드포인트 미등록) |

- C-1: 설정 키 `paycore.admin.demo-quick-login.enabled`(기본 false — 안전한 쪽)·`paycore.admin.demo-quick-login.accounts`(로그인 이름 목록, 쉼표 구분 가능, 기본 빈 목록). application.yml은 환경변수 `PAYCORE_ADMIN_DEMO_QUICK_LOGIN_ENABLED`·`PAYCORE_ADMIN_DEMO_QUICK_LOGIN_ACCOUNTS`로 받는다(저장소에 Demo 계정 이름을 두지 않는다).
- C-2: 기동 검사는 `DemoQuickLoginStartupGuard`(LiveModeStartupGuard와 같은 위치·같은 "설정 이름만 보고" 방식, 기존 LIVE 가드 위반 목록은 바꾸지 않는다 — 별도 목록). `liveModeStartupGuard` bean 안에서 함께 실행되어, 가드 bean에 의존하는 모든 보안 bean보다 먼저 실패한다.
- C-3: 엔드포인트 등록은 `@Conditional(DemoQuickLoginCondition)`(account.adapter.web — 웹 adapter는 integration 타입을 쓰지 않는 ArchitectureSpec 규칙 때문에 모드를 문자열로 읽어 대소문자 무시 비교, 배치 모드 DEMO **그리고** enabled=true를 Binder로 읽음). 등록되지 않으면 핸들러가 없어 기존 JSON 404. 보안 체인은 두 경로를 익명 허용(permitAll)으로 **항상** 두되 핸들러가 없으면 404다(익명 허용 자체는 효과 없음). POST는 CSRF 필수 그대로(없으면 403).
- MINOR-2(a): DEMO + enabled인데 IP 허용 목록이 모든 주소(접두 0 — `0.0.0.0/0`·`::/0`, LIVE와 같은 기준)를 허용하면 **기동 거부**(`demo quick login refused: paycore.admin.network.ip-allowlist permits every address` — SecurityConfig의 허용 목록 filter 등록).
- MINOR-4: 그 허용 목록 검사의 배치 모드 비교는 설정 바인딩의 관대 enum 변환과 같은 정규화(글자·숫자만, 대문자 — `live`도 LIVE). 예전에는 소문자 `live`면 이 검사를 건너뛰었다.
- MINOR-2(b) — **보고만**: TOTP 암호 키·login_name HMAC 키가 저장소의 알려진 Demo(시험) 키인지는 판별할 수 있지만, Demo 배치는 gen-env로 만든 임의 키를 쓰므로 "알려진 Demo 키가 아님"이 운영 키라는 근거가 되지 못한다 → 거부하지 않고 기동 WARN에 분류(`knownDemoKeys=true|false`)만 남긴다.
- MINOR-2(c): 켜지면 기동 WARN 1줄(`DEMO QUICK LOGIN ENABLED (demo only — must be off before operation): accounts=<수> knownDemoKeys=<분류>` — 계정 이름·키 값 없음) + gauge `paycore.admin.demo_quick_login.active`(켜짐 1, 꺼짐 0).
- C-4: 방어 심층 — application 서비스도 `DemoQuickLoginSettings.active`(= DEMO && enabled)가 거짓이면 아무 계정도 내지 않고 모든 로그인을 거부한다(감사 없음 — 도달 불가 경로).

## 3. API

| 메서드·경로 | 인증 | 요청 | 응답 |
|---|---|---|---|
| `GET /api/auth/demo-quick-login` | 익명 허용(IP 허용 목록 적용) | — | 200 `{"accounts":[{"loginName","displayName","roles":[…],"available":bool}]}` (허용 목록 순서, DB에 없는 이름은 뺌, `available` = ACTIVE이고 실패 한도 미도달) · `Cache-Control: no-store` |
| `POST /api/auth/demo-quick-login` | 익명 허용 + **세션 필요**(`GET /api/auth/csrf` 먼저) + **CSRF 필수** | `{"loginName"}` | 성공 200 = `/api/auth/me`와 같은 본문·같은 규칙(방금 기록한 세션 표시 DEMO_QUICK **그리고** 서버 켜짐이면 `"demoQuickLogin":true`, 아니면 필드 없음 — 이 컨트롤러가 등록된 상태에서는 항상 true) + 새 세션 쿠키·새 XSRF 쿠키 / 실패 401 `{"error":"INVALID_CREDENTIALS"}`(원인 비구분 — 일반 로그인과 같은 본문) |

- 세션 없이 POST → 실제로는 CSRF filter가 먼저 403(세션이 없으면 저장 토큰이 없다). 컨트롤러의 401 분기는 방어용. CSRF 없음 → 403.
- `GET /api/auth/me`(결정 ③): 간편 로그인 세션 **그리고** 켜짐이면 본문에 `"demoQuickLogin":true`를 덧붙인다. 일반 세션 본문은 기존 형태 그대로(필드 없음).
- 비밀번호·TOTP 입력은 받지 않는다. 응답·로그·감사에 비밀값 없음.

## 4. 세션 규칙 — 기존 세션 생성 경로 그대로

- 성공하면 웹 adapter가 일반 TOTP 성공과 **같은 메서드**(`LoginSessionWriter.renew` — AuthController와 공유하도록 추출, 동작 불변)로: `changeSessionId()`(세션 고정 방지) → 세션 문자열 상태 `AUTHENTICATED`(actorId·securityVersion·sessionGeneration·authenticatedAt) → CSRF 토큰 재발급 + `__Host-PCADMIN-XSRF` 쿠키 → `maxInactiveInterval` = 로그인 idle(Demo 15분).
- application은 계정 행 잠금 TX 1회에서 `AdminAccount.startNewSession`(세대 +1 = **새 로그인 우선** — 같은 계정의 기존 세션은 다음 요청에 401 `SESSION_REPLACED`) → 저장 → 감사 1행.
- 이후 요청은 기존 `AdminSecurityContextRepository`가 그대로 판정(ACTIVE·세대·security_version·절대 한도) — 데모 세션에 대한 별도 분기 없음. 역할은 계정 현재 행.
- 결정 ③ 세션 표시: 세션 문자열 속성 `paycore.admin.loginMethod=DEMO_QUICK`(간편 로그인 성공에서만 기록, `AdminSessionState.loginMethod`). 일반 로그인 성공은 같은 세션이라도 이 속성을 지운다(null 기록). 속성은 서버 세션 저장소에만 있다.
- MINOR-B: 기능을 끈 뒤(재기동) 표시가 남은 세션은 다음 요청에서 폐기한다(401 `SESSION_EXPIRED`, 세션 행 삭제) — `AdminSecurityContextRepository`가 `AdminSessionQuery.demoQuickLoginActive()`(account provided Port, 기본값 false = 안전한 쪽)로 판정. 표시 없는 일반 세션은 영향 없음.
- C-5: 실패 계수는 **0으로 되돌리지 않는다**(비밀값을 확인하지 않았으므로 일반 로그인 성공의 계수 초기화와 다르게 보수적으로). TOTP `last_totp_step`도 건드리지 않는다.
- 로그아웃·idle·절대 한도·동시 세션은 일반 세션과 같다.

## 5. 거부 규칙

| 경우 | 응답 | 감사(action·result·detail) | 계정 변경 |
|---|---|---|---|
| 허용 목록 밖 이름(실제 계정이 있어도) / 목록에 있으나 DB에 없음 / 형식 오류 | 401 | `LOGIN_DEMO_QUICK`·`REJECTED`·`reason=NOT_ALLOWED`, 행위자 ANONYMOUS + login_name 비밀 키 HMAC(일반 로그인 없는 계정과 같음) | 없음 |
| LOCKED·SUSPENDED·PENDING_ENROLLMENT(등록 미완료) | 401 | 그 계정 행위자, `LOGIN_DEMO_QUICK`·`NOT_COUNTED`·`reason=NOT_ACTIVE` | 없음 |
| ACTIVE인데 실패 한도 도달 | 401 | `LOGIN_DEMO_QUICK`·`NOT_COUNTED`·`reason=LIMIT` | 없음 |
| 성공 | 200 | `LOGIN_SUCCEEDED`·`SUCCESS`·`method=DEMO_QUICK` | 세대 +1 |

- 잠금 규칙: 간편 로그인에는 틀릴 비밀값이 없어 실패 계수를 올리지 않는다. 잠긴 계정·한도 도달 계정은 일반 로그인과 같이 거부한다(해제는 기존 2인 승인 `ACCOUNT_UNLOCK`).
- 속도 제한: 기존 로그인에도 IP·요청 단위 속도 제한은 없다(실패 잠금만). 같은 수준 — 별도 제한을 추가하지 않는다(R-3).
- 지표: `paycore.admin.login{step=DEMO_QUICK,result=SUCCESS|REJECTED|NOT_COUNTED|RETRY_LATER|ERROR}`.
- 경합: 저장 경합은 기존과 같이 409 `RETRY_LATER`.
- 거부(401)되면 그 세션의 로그인 상태 속성(단계·actor·세대·방식 등 `AdminSessionState.NAMES`)을 지운다 — 일반 비밀번호 단계 실패와 같다(이미 로그인된 세션에서 거부되면 로그아웃 상태가 된다). CSRF 토큰은 남는다.
- 감사 저장 실패: 성공 감사가 저장되지 않으면 TX 전체 rollback(세대 불변) → 500 `INTERNAL_ERROR`, 세션은 로그인 상태가 되지 않는다(리뷰 MINOR-1 시험).

## 6. 감사

- 성공은 일반 로그인 성공과 **같은 action `LOGIN_SUCCEEDED`**(감사 화면의 로그인 검색에 함께 잡힘) + `detail=method=DEMO_QUICK`로 방식을 구분한다. 일반 로그인 성공 행의 detail은 기존 그대로(없음).
- 거부는 새 action `LOGIN_DEMO_QUICK`. 행위자·IP(remoteAddr)·상관 ID는 기존 규칙.
- 결정 ③ 재인증 생략: `STEP_UP_SKIPPED`·`DEMO_SKIPPED`·`detail=stepUp=DEMO_SKIPPED;method=DEMO_QUICK`(행위자 = 승인자/마스킹 해제 요청자). 승인·반려·마스킹 해제 감사와 **같은 TX·같은 상관 ID**(`correlation_id`로 묶는다 — 승인 ID 열은 비움).
- C-6(rev 0.2 해소): 감사 화면 라벨 `LOGIN_DEMO_QUICK` = "데모 간편 로그인", `STEP_UP_SKIPPED` = "재인증 생략(데모)", 결과 `DEMO_SKIPPED` = "데모 생략"(`lib/labels.ts`, 목록 시험 `lib/labels.test.ts` 확장).

## 7. 화면

- 로그인 화면(LoginScreen): 옵션 조회(`GET`)가 계정 목록을 돌려줄 때만 일반 로그인 카드 위에 "데모 간편 로그인" 영역(경고 색 안내 "데모 전용 — 비밀번호·OTP 없이 들어갑니다")과 계정 버튼(표시명 + 역할 라벨)을 보인다. 404·오류·빈 목록이면 영역 없음(일반 로그인 그대로). `available=false` 계정 버튼은 비활성 + "사용 불가".
- 버튼 → `GET /api/auth/csrf` → `POST` → 성공이면 일반 로그인 성공과 같은 `onAuthenticated`. 실패는 기존 오류 문구.
- 로그인 뒤(App 계정 영역): 옵션 조회가 켜짐이면 "데모 간편 로그인 켜짐" 표시 + "계정 바꾸기" 버튼(= 로그아웃 → 로그인 화면, 그 위에 데모 계정 버튼). 꺼짐이면 아무것도 보이지 않는다(기존 화면 불변).
- 승인함(ApprovalsScreen, 결정 ③): `me.demoQuickLogin === true`이면 PENDING 행의 "인증 코드(6자리)" 입력 대신 "데모: 재인증 생략" 안내를 보이고 승인·거절은 빈 코드로 보낸다. 플래그가 없으면 기존 그대로. 마스킹 해제 화면은 아직 없다(API만).
- 프런트는 표시만 — 켜짐 판정·권한·생략 판정은 서버.

## 8. 재인증(민감 작업 TOTP 재확인) — 결정 ③: 데모 간편 로그인 세션은 생략

재인증이 있는 작업(rev 0.1 조사):

| 작업 | API | 서버 경로 |
|---|---|---|
| 2인 승인 **승인**(모든 종류: 계정·역할·자격 재설정, PAYMENT 운영자 보정 취소, 재매입, 가맹점 계약·첫 MID·결제수단 개통·사이트 심사·통화·가맹점 코드 변경) | `POST /api/approvals/{id}/approve` | ApprovalService·OwnerServiceApprovalService·AcquiringOpsApprovalService·MerchantOpsApprovalService |
| 2인 승인 **반려** | `POST /api/approvals/{id}/reject` | ApprovalService(반려도 TOTP) |
| 마스킹 해제 | `POST /api/unmask` | UnmaskService |

요청·회수·조회·등록 요청에는 재인증이 없다. 등록 코드 완료(`/api/enroll/complete`)는 재인증이 아니라 등록 자체라 바뀌지 않는다.

규칙(결정 ③):
- 위 6개 호출 위치는 모두 `AdminOwnedCommandExecutor.verifyStepUp(actorId, code, context)` 한 곳으로 판정한다. 생략 조건 = `context.demoQuickSession`(웹 adapter가 세션 표시 `loginMethod=DEMO_QUICK`로 채움 — 승인·반려·마스킹 해제 요청만) **그리고** `DemoQuickLoginSettings.active`(DEMO + enabled, 요청 시점). 둘 중 하나라도 아니면 기존 `consumeStepUpTotp`와 완전히 같다(빈 코드 → 실패 → 인증 실패 계수 +1).
- 생략하면: 잠근 현재 행이 ACTIVE여야 하고(아니면 기존과 같이 실패), TOTP 검증·`last_totp_step` 갱신·실패 계수는 없음, 같은 TX에 감사 `STEP_UP_SKIPPED`(§6). 결과 `StepUpResult.DEMO_SKIPPED`는 호출자에게 통과다.
- 순서·규칙 불변: 생략 판정은 원래 재인증 자리에서만 일어난다 — 그 앞의 만료·상태·요청자 ≠ 승인자(`SELF_APPROVAL`)·등록자 배제·APPROVER/UNMASK 역할·세대 검사와 그 뒤의 실행·idempotency·감사는 그대로.
- 마스킹 해제의 `TOTP_REQUIRED`(빈 코드 400)는 생략 조건일 때만 건너뛴다.
- 기능을 끈 뒤 재기동하면 표시가 남은 세션은 먼저 폐기되고(§4 MINOR-B), 그 문맥이 use case에 닿더라도 생략되지 않는다(켜짐 조건은 요청마다 판정 — 방어 심층).

## 9. 위협과 완화

| 위협 | 완화 |
|---|---|
| 운영(LIVE)에서 켜짐 | LIVE + enabled 기동 거부(T-3). 기본값 false. 환경변수로만 켬 |
| `deployment-mode=DEMO`로 운영 배치(LIVE 가드 우회 — D-79 구조적 한계와 같음) | R-1: 운영 준비 체크리스트(§10)에서 차단. 운영 배치는 LIVE여야 한다 |
| Demo 주소에 접근한 누구나 데모 계정으로 로그인 | IP 허용 목록 그대로(T-7, Tailscale 대역만), 허용 목록 계정만, 계정 버튼은 실제 역할 그대로(권한 상승 없음), 감사에 방식·IP 기록. R-2: 허용 주소 안의 사람은 누구나 보안관리자 데모 계정이 될 수 있다 — Demo 데이터·Demo 키만 있는 환경 전제 |
| 세션 고정·CSRF | 일반 로그인과 같은 재발급 메서드(T-5), POST CSRF 필수 |
| 허용 목록 밖 실제 계정으로 로그인 | 정확한 이름 일치만, 거부·익명 감사(T-4) |
| 잠긴·정지·미등록 계정 우회 | 상태·한도 판정 후 거부(T-4) |
| 2인 승인 무력화 | 세션은 각 계정 자신의 것 — 요청자 ≠ 승인자·역할 규칙 불변. 재인증 생략은 간편 로그인 세션 + 켜짐만(§8), 감사 `STEP_UP_SKIPPED` |
| 모든 주소 허용 + 켜짐 | 기동 거부(MINOR-2(a)) |
| 계정 목록 노출(익명 GET) | 켜진 Demo에서만, 표시명·역할·로그인 이름만(비밀 없음) |

## 10. 운영 준비 전 제거 체크(반드시)

- [ ] 모든 운영 후보 배치에서 `PAYCORE_ADMIN_DEMO_QUICK_LOGIN_ENABLED` 미설정/false, `deployment-mode=LIVE` 확인(LIVE면 켜진 설정은 기동 거부).
- [ ] 운영 배치가 `DEMO` 모드가 아님을 배치 검사로 확인(R-1).
- [ ] 이 기능 코드(`DemoQuickLogin*`, 화면 영역, permitAll 두 경로) 제거 또는 유지 결정을 LIVE Gate 항목으로 기록.
- [ ] 감사에서 `method=DEMO_QUICK`·`LOGIN_DEMO_QUICK`·`STEP_UP_SKIPPED` 행이 운영 기간에 0건인지 확인 절차, gauge `paycore.admin.demo_quick_login.active`가 0인지 확인.
- [ ] 재인증 생략 경로(`verifyStepUp`의 DEMO_SKIPPED 분기)도 함께 제거 또는 유지 결정.
- [ ] 끈 뒤 남은 간편 로그인 세션은 다음 요청에 자동 폐기된다(MINOR-B). 운영 전환 때는 관리자 세션 표(`admin_session`)를 비우는 절차를 함께 둔다(남은 세션 없음 확인).

## 11. 시험 목록

| ID | 내용 | 위치 |
|---|---|---|
| T-1 | 모드×설정 조합: DEMO+꺼짐 → GET·POST 404(엔드포인트 없음), DEMO+켜짐 → 200 | DemoQuickLoginHttpSpec(켜짐), DemoQuickLoginDisabledHttpSpec(기본 설정 = 꺼짐) |
| T-2 | 조건 함수: DEMO/LIVE × enabled 유무 → 등록 여부 | DemoQuickLoginStartupGuardSpec(ApplicationContextRunner) |
| T-3 | LIVE + enabled 기동 거부(설정 이름만 보고), DEMO + enabled + 빈 목록·형식 오류 기동 거부, 그 밖 통과 | DemoQuickLoginStartupGuardSpec |
| T-4 | 허용 목록 밖(실제 계정 포함)·없는 이름·형식 오류·잠긴·정지·등록 미완료·ACTIVE인데 실패 한도 도달 → 401 같은 본문, 감사 행(NOT_ALLOWED·NOT_ACTIVE·LIMIT), 계정 불변(계수 초기화 없음) | DemoQuickLoginHttpSpec |
| T-5 | 성공: 세션 ID·CSRF 재발급(세션 고정 방지), 쿠키 속성, idle 900초, `/api/auth/me` 역할 = 계정 현재 역할, CSRF 없는 POST 403, 세션 없는 POST 401, 새 로그인 우선(기존 세션 `SESSION_REPLACED`) | DemoQuickLoginHttpSpec |
| T-6 | 감사: 성공 `LOGIN_SUCCEEDED:SUCCESS` + `method=DEMO_QUICK`, 일반 로그인 성공 detail 불변 | DemoQuickLoginHttpSpec |
| T-7 | IP 허용 목록 밖 주소 → 403 `IP_DENIED`(GET·POST) | DemoQuickLoginHttpSpec |
| T-8 | 옵션: 허용 목록 순서, 없는 이름 제외, 잠긴 계정 `available=false`, 응답에 비밀 없음 | DemoQuickLoginHttpSpec |
| T-9 | 화면: 옵션 있으면 버튼·안내, 404/오류면 없음, 버튼 → csrf → 로그인 → onAuthenticated, 실패 문구, 사용 불가 버튼 | LoginScreen.demoQuick.test.tsx |
| T-10 | 화면: 로그인 뒤 "데모 간편 로그인 켜짐"·"계정 바꾸기"(로그아웃 → 로그인 화면 데모 버튼), 꺼짐이면 표시 없음 | layout/DemoQuickLogin.app.test.tsx |
| T-11b | 결정 ③ 2-TX 경로(MINOR-A): 가맹점 계약 승인 — 등록자 데모 세션 403 APPROVER_IS_REGISTRANT·PENDING·생략 감사 없음, 다른 승인자 EXECUTED + 생략 감사(APPROVAL_EXECUTING과 같은 상관 ID)·계수 0 | approval/DemoQuickMerchantStepUpHttpSpec |
| T-11 | 결정 ③: 간편 로그인 승인자의 빈 TOTP 승인 → 실행, 반려 → REJECTED, 마스킹 해제 → 204, 감사 `STEP_UP_SKIPPED`(같은 상관 ID), 실패 계수·TOTP 단계 불변. 요청자 본인 승인 403 SELF_APPROVAL, APPROVER 없는 계정 403. 같은 켜짐 설정의 일반 세션은 빈 TOTP 403 STEP_UP_FAILED + 계수 +1, 마스킹 해제 400 TOTP_REQUIRED | DemoQuickStepUpHttpSpec |
| T-12 | 꺼짐 설정: 표시를 넣은 세션(주입 1행 확인)은 다음 요청 401 SESSION_EXPIRED·행 삭제, 표시 없는 세션은 유효(MINOR-B). use case에 데모 문맥이 닿아도 빈 TOTP → StepUpFailed + 계수 +1, 생략 감사 없음 | DemoQuickLoginDisabledHttpSpec |
| T-13 | 세션 표시: 간편 로그인 세션 속성 `DEMO_QUICK`, `/api/auth/me` 플래그, 일반 로그인 세션은 속성·플래그 없음, 간편 로그인 뒤 같은 브라우저 일반 로그인 → 속성 지워짐(NIT-b) | DemoQuickLoginHttpSpec |
| T-14 | MINOR-1: 성공 감사 저장 실패 → 500, 세대 불변, `/api/auth/me` 401 | DemoQuickLoginHttpSpec |
| T-15 | MINOR-2(a)·MINOR-4: 모드 정규화(`live`·`Live` → LIVE), LIVE 또는 DEMO+켜짐에서 모든 주소 허용 거부, 좁은 목록 통과 | DeploymentModeAllowlistSpec |
| T-16 | MINOR-2(b): 알려진 Demo 키 분류(보고용) | DemoQuickLoginStartupGuardSpec |
| T-17 | 화면: 간편 로그인 세션 승인함은 "데모: 재인증 생략" + 빈 코드 전송, 일반 세션은 기존 6자리 입력 | ApprovalsScreen.demoQuick.test.tsx |
| 기존 | LoginHttpSpec·IpAllowlistSpec·LiveModeStartupGuardSpec·SpaCsrfSpec·LoginScreen 시험 — 기대값 변경 없음 | — |

## 12. 위험·한계

- R-1: DEMO 모드로 운영에 띄우면 이 기능이 켜질 수 있다(LIVE 가드와 같은 구조적 한계) — §10.
- R-2: 허용 IP 안의 누구나 데모 계정 사용 가능(사용자 결정 범위 — Tailscale 포함).
- R-3: 요청 단위 속도 제한 없음(기존 로그인과 같은 수준). 성공마다 감사 1행·세대 +1이라 반복 호출은 감사 행을 늘린다.
- R-4(결정 ③으로 대체): 간편 로그인 세션은 재인증 없이 승인·반려·마스킹 해제를 할 수 있다 — 허용 IP 안의 누구나 데모 승인자로 승인 가능(R-2와 같은 전제: Demo 데이터·Demo 키 환경). 감사 `STEP_UP_SKIPPED`로 추적.
- R-5: 해소(rev 0.2 — 감사 라벨).
- R-6: MINOR-2(b)는 판별 근거가 없어 거부하지 않는다(WARN 분류만).
