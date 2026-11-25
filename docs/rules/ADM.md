# ADM 업무 규칙 대장 — 관리자 인증·권한·감사·개인정보

- 상태: **초안**(RULE-GOV-001 2단계, 2026-11-25). 사용자 확인·정책서 정합(4단계) 전이다. 이 파일의 `확정`은 "사용자 결정 원문이 있다"는 뜻이고, 대장 자체의 확정은 4단계에서 한다.
- 범위: 관리자 인증·TOTP·세션·재인증, 계정 수명주기·자격 재설정·부트스트랩·복구, 역할·권한, 2인 승인 틀, 소유 서비스 승인 실행(운영자 보정 취소 D-OPS), 관리자 감사, 민감 조회·마스킹 해제, 개인정보 표시·로그 규칙, 내부 식별자 화면 비노출, IP 제한·배치 모드, 데모 간편 로그인, 운영 홈 KPI.
- 경계: 사업자·가맹점 데이터 자체(항목·상태·전이·업무 승인 대상)는 `BIZ.md`, 취소 실행 의미는 `CAN.md`, 정산·지급 계좌는 `FIN.md`. 여기서는 **관리자 권한·감사·개인정보 취급 관점**만 적고, 겹치는 행은 규칙 끝에 `[경계 BIZ]` 등으로 표시했다.
- 조사 기준: main `41139db` + 작업 트리(문서만 읽음). 원천: `docs/DECISIONS.md`, `harness/questions.json`, 명세(아래 약어), baseline 05·07·10, `apps/internal-admin`(+ 관련 payment-api·master-api 시험).
- 약어(적용 명세 열, 모두 `docs/repository/` 아래): **BOOT1** = `ADMIN-BOOT-001-ADMIN-FOUNDATION.md`, **BOOT2** = `ADMIN-BOOT-002-BOOTSTRAP-SECOND-SA.md`, **OPS1** = `ADMIN-PAYMENT-OPS-001-PAYMENT-OPS.md`, **DEMO1** = `ADMIN-DEMO-LOGIN-001-DEMO-QUICK-LOGIN.md`, **PII** = `PII-MASKING-POLICY.md`, **BRN1** = `BRN-UNMASK-001-BRN-DISPLAY.md`, **HK1** = `ADMIN-HISTORY-KEYS-001-HISTORY-TARGET-LABELS.md`, **FLOW1** = `MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md`, **KPI1** = `ADMIN-HOME-KPI-001-HOME-KPI.md`.
- 검증 열: 시험 파일명(Kotlin `*Spec.kt`는 internal-admin, 다른 앱은 괄호 표기, 화면 시험은 `frontend/src` 기준 경로)과 시험 이름 요약. 찾지 못했으면 `없음`.

## 1. 로그인·비밀번호·TOTP·실패 잠금

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-001 | 관리자 로그인은 자체 계정 DB의 비밀번호 단계와 TOTP 단계를 모두 통과해야 성립하며, 비밀번호 단계만 통과한 세션으로는 어떤 업무 API도 쓸 수 없다. | 확정 | 사용자 | D-ADMIN-001, D-ADMIN-004 | BOOT1 §4.3.2 | LoginHttpSpec::password then TOTP authenticates; LoginHttpSpec::TOTP step without a password-verified session is 401 |
| BR-ADM-002 | 비밀번호는 단방향 해시(BCrypt — D-ADMIN-001의 BCrypt/Argon2id 중 구현 선택)로만 저장하고, 본인 비밀번호 변경 기능은 두지 않는다. | 확정 | 사용자 | D-ADMIN-001 | BOOT1 §4.3.3 | AccountCryptoAdaptersSpec::password: BCrypt with the {bcrypt} prefix; LoginServiceSpec::BCrypt's 72-byte truncation cannot be used |
| BR-ADM-003 | TOTP 시크릿은 암호화해 저장하고 암호화 키는 비밀 저장소(환경 주입)에서만 받는다. | 확정 | 사용자 | D-ADMIN-001 | BOOT1 §4.3.4 | AccountCryptoAdaptersSpec::cipher: round trip / AAD binding / tampered bytes fail closed |
| BR-ADM-004 | 비밀번호·로그인 TOTP·재인증 TOTP 실패는 한 계수기에 합산하고, 한도에 도달하면 계정을 LOCKED로 바꾸며, 계수는 전체 로그인 성공 때만 0이 된다. | 확정 | 사용자 | D-ADMIN1-005 | BOOT1 §4.3.5 | AuthenticationFailureSpec::failures across password, login TOTP and step-up TOTP are summed; LoginServiceSpec::password and TOTP failures are summed |
| BR-ADM-005 | 잠긴 계정은 자동으로 풀리지 않으며, ACCOUNT_UNLOCK 2인 승인으로만 ACTIVE가 된다. | 확정 | 사용자 | D-ADMIN-001, D-ADMIN1-005 | BOOT1 §4.3.1, §4.7.6 | AuthenticationFailureSpec::a LOCKED account is not counted further (no automatic unlock); AdminOwnedCommandSpec::ACCOUNT_UNLOCK: LOCKED → ACTIVE |
| BR-ADM-006 | 없는 계정·잠긴 계정·틀린 비밀번호는 같은 응답(`INVALID_CREDENTIALS`)과 같은 처리 경로(해시 비교 1회·TX 1회·감사 1행)로 구별되지 않게 한다. | 확정 | 사용자 | D-ADMIN1-005 | BOOT1 §4.3.2 | LoginServiceSpec::each make exactly 1 BCrypt compare, 1 local TX and 1 audit row; LoginHttpSpec::every failing branch returns the same 401 body |
| BR-ADM-007 | IP 허용 목록 밖에서 온 요청은 로그인 실패 계수에 넣지 않는다. | 확정 | 사용자 | D-ADMIN1-005 | BOOT1 §4.3.5, §4.8.1 | 없음(필터가 먼저 거부하는 구조 — 계수 불변을 단언하는 시험은 찾지 못함) |
| BR-ADM-008 | 로그인 실패 잠금 한도는 5회다. | Demo값 | 사용자 | D-ADMIN-004, Q-ADMIN-SEC-VALUES | BOOT1 §4.3.5, `application.yml` security.login-max-failures | AuthenticationFailureSpec::the 5th locks the account |
| BR-ADM-009 | 미인증 로그인 시도의 감사에는 login_name 원문 대신 비밀 키 HMAC 값만 남긴다. | 제안 | 적용 | BOOT1 n-2, D-ADMIN-004 | BOOT1 §4.6 | LoginServiceSpec::an unknown login is audited as ANONYMOUS with only the keyed HMAC |
| BR-ADM-010 | 본인은 자기 비밀번호 재설정·TOTP 재등록·잠금 해제를 직접 할 수 없고, SECURITY_ADMIN 요청 + 2인 승인으로만 한다. | 확정 | 사용자 | D-ADMIN-001, D-ADMIN-003 | BOOT1 §4.7.1 | AdminOwnedCommandSpec::non-SECURITY_ADMIN requesters are denied … self-targeting is refused |

## 2. 세션·CSRF

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-011 | 관리자 세션은 서버 세션이며 계정당 유효 로그인 세션은 하나다. 비밀번호+TOTP를 통과한 새 로그인이 기존 세션을 즉시 끊고, 기존 화면은 다음 요청에서 '다른 곳에서 로그인됨'(`SESSION_REPLACED`)을 받는다. 교체는 감사한다. | 확정 | 사용자 | D-ADMIN-004, D-ADMIN1-001 | BOOT1 §4.4.2 | LoginHttpSpec::a newer login … replaces the older session: 401 SESSION_REPLACED; LoginServiceSpec::two TOTP steps of two sessions racing (SingleSessionRace) |
| BR-ADM-012 | 로그인 단계를 통과할 때마다 세션 ID를 재발급한다(세션 고정 방지). | 확정 | 사용자 | D-ADMIN-004 | BOOT1 §4.3.2, §4.4.3 | LoginHttpSpec::the session ID and the CSRF token are renewed at each step (SessionFixation) |
| BR-ADM-013 | 세션 쿠키는 HttpOnly·Secure·SameSite이고, 상태 변경 요청은 세션에 저장된 CSRF 토큰을 헤더로 돌려줘야 한다(쿠키→헤더, double-submit 위조 거부). | 확정 | 사용자 | D-ADMIN-004, D-ADMIN-008 | BOOT1 §4.4.3 | LoginHttpSpec::cookie attributes are __Host- strict; SpaCsrfSpec::forged double-submit … rejected with 403; SpaCsrfSpec::a token from another session is rejected |
| BR-ADM-014 | 세션 idle·절대 만료가 지나면 다음 요청은 `SESSION_EXPIRED`로 끝난다. | 확정 | 사용자 | D-ADMIN-004 | BOOT1 §4.4.1 | LoginHttpSpec::an idle-expired session … SESSION_EXPIRED; LoginHttpSpec::past the absolute limit … SESSION_EXPIRED |
| BR-ADM-015 | 세션 한도: 로그인 idle 15분, 절대 8시간, 비밀번호 단계(TOTP 대기) 5분, 익명·등록 진행 10분. | Demo값 | 사용자 | D-ADMIN-004, Q-ADMIN-SEC-VALUES | `application.yml` security.session | LoginHttpSpec::past the absolute limit; DemoQuickLoginHttpSpec(T-5 idle 900초) |
| BR-ADM-016 | 계정 잠금·정지·역할 회수·자격 재설정 등 계정의 보안 상태가 바뀌면 그 계정의 기존 세션은 즉시 무효다. | 확정 | 사용자 | D-ADMIN1-003, D-ADMIN1-004 | BOOT1 §4.3.1, §4.4.2, §4.7.5 | AdminAccountCommandSpec::an active role is revoked … sessions end; AuthenticationFailureSpec::the 5th locks the account, ends its sessions; LoginHttpSpec::a security_version change … ends the session |
| BR-ADM-017 | 권한은 세션에 저장한 값이 아니라 요청마다 계정 행의 현재 상태(ACTIVE)와 현재 활성 역할로 판정한다. | 제안 | 적용 | D-ADMIN1-003, D-ADMIN1-004(즉시 무효), BOOT1 D-08 | BOOT1 §4.4.1, §4.5.1 | AdminAccountCommandSpec::the requester's permission is judged on the locked (latest committed) row |

## 3. 계정 생성·정지·등록 코드

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-018 | 계정 생성은 SECURITY_ADMIN 1인이 역할 없는 계정으로 하고, 1회용 등록 코드를 한 번만 보여 주어 별도 채널로 본인에게 전달하며, 본인이 비밀번호·TOTP를 등록하면 역할 없는 ACTIVE가 된다. 역할은 2인 승인으로만 붙는다. | 확정 | 사용자 | D-ADMIN1-003 | BOOT1 §4.3.6, §4.7.1 | AdminAccountCommandSpec::a SECURITY_ADMIN creates a role-less PENDING_ENROLLMENT account with a one-time ENROLL code; AdminAccountCommandSpec::a requester without SECURITY_ADMIN … is denied |
| BR-ADM-019 | 등록 코드 유효기간은 24시간이다. | Demo값 | 사용자 | D-ADMIN1-003, Q-ADMIN-SEC-VALUES | `application.yml` security.enrollment-code-validity | EnrollmentFlowSpec::malformed, unknown, expired and reissued codes are the same CODE_INVALID |
| BR-ADM-020 | 등록 코드 원문은 발급할 때 한 번만(1인 생성은 생성자, 재설정은 실행자=승인자에게) 보여 주고, 그 밖의 응답·승인 재조회·감사·로그·지표에는 남기지 않는다. | 확정 | 사용자 | D-ADMIN1-003, D-ADMIN1-009 | BOOT1 §4.3.6, §4.7.7 | ApprovalHttpSpec::the code is in the approval response only (no-store); AdminOwnedCommandSpec::the code appears only in the approval result; AdminNonDisclosureSpec::one-time values only in their own response |
| BR-ADM-021 | 계정 정지는 SECURITY_ADMIN 1인 즉시(세션 즉시 무효)이고, 정지 해제는 ACCOUNT_REACTIVATE 2인 승인이다. | 확정 | 사용자 | D-ADMIN1-003 | BOOT1 §4.3.1, §4.7.1 | AdminAccountCommandSpec::the target is suspended … audited; AdminOwnedCommandSpec::ACCOUNT_REACTIVATE: SUSPENDED → the previous status |
| BR-ADM-022 | 정지하면 대상의 미사용 등록 코드를 모두 폐기하고, 정지 해제로 되살리지 않는다. | 제안 | 적용 | BOOT1 rev 0.3 H-B | BOOT1 §4.3.1 | AdminAccountCommandSpec::its unused enrollment codes are revoked (H-B); EnrollmentFlowSpec(SuspendRevokesEnrollment) |
| BR-ADM-023 | SECURITY_ADMIN은 자기 계정을 정지할 수 없다. | 제안 | 적용 | BOOT1 rev 0.3 n-E | BOOT1 §4.3.1 | AdminAccountCommandSpec::suspending oneself or an unknown target is rejected |
| BR-ADM-024 | 역할을 가진(활성 또는 정지) 계정의 등록 코드는 1인으로 재발급할 수 없고, 2인 재설정 승인 또는 복구 명령으로만 다시 발급한다. | 제안 | 적용 | D-ADMIN1-003("역할 없는 계정"), BOOT1 rev 0.4 H-1 | BOOT1 §4.3.6 | AdminAccountCommandSpec::a role-bearing pending account (bootstrap pair) is refused; AdminAccountCommandSpec::a credential-reset account holding only suspended roles is refused |
| BR-ADM-025 | 관리자 계정은 삭제하지 않으며 행위자 ID(actor ID)는 바뀌지 않고 재사용하지 않는다. login_name 변경 기능은 없다. | 확정 | 사용자 | D-ADMIN-004 | BOOT1 §4.5.2 | AdminDatabaseAccountSpec::creation-immutable columns refuse UPDATE with 1143(계정 DELETE 1142) |

## 4. 자격 재설정·재등록·역할 재활성

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-026 | 비밀번호 재설정·TOTP 재등록을 실행하면 대상의 활성 역할을 모두 정지(이력 보존)하고 대상은 등록 대기가 된다. | 확정 | 사용자 | D-ADMIN1-009 | BOOT1 §4.7.7 | AdminOwnedCommandSpec::PASSWORD_RESET / TOTP_REENROLL: PENDING_ENROLLMENT, every active role SUSPENDed |
| BR-ADM-027 | 본인 재등록은 등록 코드만으로 대상 요소(재설정=비밀번호, 재등록=TOTP)만 다시 설정하며 남은 요소를 확인하지 않는다. | 확정 | 사용자 | D-ADMIN1-011 | BOOT1 §4.3.6 | EnrollmentFlowSpec::PASSWORD_RESET sets only the password; EnrollmentFlowSpec::TOTP_REENROLL sets only the TOTP |
| BR-ADM-028 | 역할을 가진 계정에 확정 자격(비밀번호·TOTP) 중 하나라도 없으면 2인 재발급은 두 요소를 모두 설정하게 한다. | 제안 | 적용 | BOOT1 rev 0.5 m-1 | BOOT1 §4.3.6 | AdminOwnedCommandSpec::a second reset of a pending account … must set both elements |
| BR-ADM-029 | 재등록을 마쳐도 정지된 역할은 그대로이며, 별도 2인 승인 ROLE_REACTIVATE로만 되살린다(그 사이 회수된 역할은 되살리지 않는다). | 확정 | 사용자 | D-ADMIN1-009 | BOOT1 §4.7.7 | AdminPermissionSpec::suspended roles grant nothing after re-enrollment; AdminOwnedCommandSpec::ROLE_REACTIVATE after re-enrollment restores only the roles still suspended |
| BR-ADM-030 | ROLE_REACTIVATE의 요청자·승인자는 원인 재설정(연속 재설정이면 가장 최근 것)의 요청자와도, 승인자(코드 수령자)와도 달라야 한다. | 확정 | 사용자 | D-ADMIN1-009, D-ADMIN1-013 | BOOT1 §4.7.7 | AdminOwnedCommandSpec::the cause reset's requester or approver can neither request nor approve the reactivation; CredentialIssuerExclusionSpec::the second reset re-suspends … whose requester and approver are excluded |
| BR-ADM-031 | 계정의 현재 자격을 만든 발급 근거(2인 재설정 승인, 1인 계정 생성·재발급)의 요청자·승인자·수행자는 다음 재설정 전까지 그 계정의 권한 상승(ROLE_GRANT·ROLE_REACTIVATE) 요청자·승인자가 될 수 없다(코드 사용 여부 무관). | 확정 | 사용자 | D-ADMIN1-014, D-ADMIN1-015 | BOOT1 §4.7.7, BOOT2 §3-7 | CredentialIssuerExclusionSpec::the reset's requester and approver cannot request or approve ROLE_GRANT on it; CredentialIssuerExclusionSpec::its creator … cannot request or approve ROLE_GRANT; BootstrapSecondSecurityAdminSpec ②(409 CREDENTIAL_ISSUER) |

## 5. 부트스트랩·오프라인 복구·운영 인원

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-032 | 최초 계정은 계정 표가 비어 있을 때만 동작하는 1회용 비웹 명령이 SECURITY_ADMIN 1명 + APPROVER 1명을 만든다. | 대체됨→BR-ADM-033 | 사용자 | D-ADMIN1-002 | BOOT1 §4.9.1(현행화 주석) | 없음 |
| BR-ADM-033 | 최초 계정 명령(bootstrap)은 계정 표가 비어 있을 때만 1회 동작해 서로 다른 SECURITY_ADMIN 2명 + APPROVER 1명을 만들고, 각 등록 코드를 TTY 또는 새 0600 파일에 한 번만 출력하며, 감사 행위자는 SYSTEM_BOOTSTRAP이고 계정이 생긴 뒤 재실행은 거부·감사한다. | 확정 | 사용자 | D-ADMIN1-002, D-ADMIN1-022 | BOOT2 §3, BOOT1 §4.9 | BootstrapCommandSpec::bootstrap creates exactly two SECURITY_ADMINs and one APPROVER … 0600 file; BootstrapCommandSpec::accounts already exist → refused; BootstrapCommandSpec::two bootstraps at once; BootstrapSecondSecurityAdminSpec ① |
| BR-ADM-034 | 오프라인 복구 명령은 활성 SECURITY_ADMIN 0명 또는 활성 APPROVER 0명일 때만 동작하며(감사 SYSTEM_RECOVERY, 한 번에 열린 회차 하나), 기존 계정에 새 등록 코드를 발급하거나 새 SECURITY_ADMIN·APPROVER 쌍을 만든다. | 확정 | 사용자 | D-ADMIN1-010, D-ADMIN1-023(조건 유지) | BOOT1 §4.9.2, BOOT2 §4 | RecoveryCommandSpec::both present → refused; RecoveryCommandSpec::two runs that both pass the condition: one commits; BootstrapSecondSecurityAdminSpec::recovery is refused NOT_NEEDED |
| BR-ADM-035 | 복구 대상 기존 계정이 재등록을 마치면 복구 시점에 보유한 SECURITY_ADMIN·APPROVER만 SYSTEM_RECOVERY 명의로 되살리고, 나머지 역할은 일반 ROLE_REACTIVATE 2인 승인으로 되살린다. | 확정 | 사용자 | D-ADMIN1-012 | BOOT1 §4.9.2 | RecoveryCommandSpec::task A … completion restores SECURITY_ADMIN only; RecoveryCommandSpec::roles left suspended … restored only by the normal 2-person ROLE_REACTIVATE |
| BR-ADM-036 | 복구 자동 재활성은 완료 시점에도 그 역할의 활성 계정이 0명이고 회차가 열려 있을 때만 한다. | 제안 | 적용 | BOOT1 rev 0.3 m-B | BOOT1 §4.9.2 | RecoveryCommandSpec::① a task-B APPROVER enrolls first … does not restore APPROVER; RecoveryCommandSpec::③ … ⑤ a code used after the episode expired is refused |
| BR-ADM-037 | 복구 회차 유효기간은 24시간이다(회차 만료 = 그 회차 코드 만료). | Demo값 | 사용자 | Q-ADMIN-SEC-VALUES(notes) | `application.yml` recovery.episode-validity | RecoveryCommandSpec::① … ⑦ task-B codes expire with the episode |
| BR-ADM-038 | LIVE 운영 하한은 활성 SECURITY_ADMIN ≥ 2, 활성 APPROVER ≥ 2이며, 하한 미만이면 지표·경보로 드러낸다. | 확정 | 사용자 | D-ADMIN1-023 | BOOT2 §11 | BootstrapSecondSecurityAdminSpec::active role holder gauges (D-ADMIN1-023); ActiveRoleHolderGaugeServiceSpec::success sets the role gauges |
| BR-ADM-039 | 2인 승인 운영 인원(요청자를 뺀 APPROVER 최소 2명, 재설정→재활성 한 주기에 서로 다른 계정 최대 4개) 확보 여부는 LIVE 전 운영 조직이 확인한다. | 미결(Q-ADMIN-APPROVER-STAFF) | — | Q-ADMIN-APPROVER-STAFF | BOOT1 §4.7.7 | 없음 |

## 6. 역할·권한

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-040 | 관리자 역할은 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN 네 개로 고정이고, 마스킹 해제(UNMASK)는 역할과 독립된 별도 권한이다. 역할은 겸직할 수 있다. | 확정 | 사용자 | D-ADMIN-002, D-ADMIN1-004 | BOOT1 §4.5.1 | AdminPermissionSpec::$role allows exactly its row; AdminPermissionSpec::the permissions are the union of the rows; UNMASK stays independent |
| BR-ADM-041 | ACTIVE가 아닌 계정(LOCKED·SUSPENDED·등록 대기)은 역할을 가지고 있어도 어떤 권한도 없다. | 제안 | 적용 | D-ADMIN1-003, D-ADMIN1-005 | BOOT1 §4.5.1 | AdminPermissionSpec::LOCKED, SUSPENDED and PENDING_ENROLLMENT accounts have no permission even with roles |
| BR-ADM-042 | 역할 부여(SECURITY_ADMIN·UNMASK 포함)는 SECURITY_ADMIN 요청 + APPROVER 2인 승인이고, 역할 회수는 SECURITY_ADMIN 1인 즉시(세션 즉시 무효)다. 역할 변경 이력은 감사에 남긴다. | 확정 | 사용자 | D-ADMIN-002, D-ADMIN-003, D-ADMIN1-004, D-FLOW-005 ⑧ | BOOT1 §4.5.1, §4.7.1 | AdminOwnedCommandSpec::ROLE_GRANT adds the role with a GRANT event by the approver; AdminAccountCommandSpec::an active role is revoked with a REVOKE event … sessions end |
| BR-ADM-043 | 본인 권한은 바꿀 수 없다(자기 역할 회수, 자기 계정을 대상으로 한 승인 요청·승인 거부). | 확정 | 사용자 | D-ADMIN-002, D-ADMIN1-004 | BOOT1 §4.5.1 | AdminAccountCommandSpec::revoking one's own role … is rejected; AdminOwnedCommandSpec::self-approval and approving one's own target are refused |
| BR-ADM-044 | 한 승인 요청에서 요청자·승인자·대상 계정은 모두 달라야 하고, 대상이 사람이 아닌 업무 대상(Payment·가맹점·매입 파일)이면 요청자≠승인자다(겸직자도 자기 요청 승인 불가). | 확정 | 사용자 | D-ADMIN-003, D-ADMIN1-004 | BOOT1 §4.7.3, OPS1 §4.9 | ApprovalTargetSpec::Payment target: requester ≠ approver only; account target: all differ; OwnerServiceApprovalFlowSpec::an OPERATOR who also holds APPROVER cannot approve their own request |
| BR-ADM-045 | 2인 승인의 승인자는 APPROVER 역할이다. | 확정 | 사용자 | D-ADMIN-003 | BOOT1 §4.7.1 | AdminOwnedCommandSpec::an approver without APPROVER … is denied with no change |
| BR-ADM-046 | 업무 운영 명령(운영자 보정 취소·재매입·가맹점 승인 요청)의 요청자는 OPERATOR이고, ADMIN 소유 명령(계정·역할·자격)의 요청자는 SECURITY_ADMIN이다. | 제안 | 적용 | BOOT1 §4.5.1, OPS1 §4.9, D-ADMIN1-003 | BOOT1 §4.5.1 | OwnerServiceApprovalFlowSpec::a non-OPERATOR is denied without calling PAYMENT; AdminOwnedCommandSpec::non-SECURITY_ADMIN requesters are denied |
| BR-ADM-047 | 권한 판정은 서버가 하고 화면은 표시만 한다. 화면이 보낸 역할·금액을 믿지 않는다. | 확정 | 사용자 | D-ADMIN-008, baseline 05 금지사항 | BOOT1 §4.5.1 | BusinessHttpSpec::VIEWER, SECURITY_ADMIN and UNMASK alone → 403 … MASTER is not called; TransactionLookupServiceSpec::UNMASK-only, no role and inactive accounts are forbidden |
| BR-ADM-048 | 가맹점별·영업 담당자별 조회 범위 제한은 두지 않는다(그런 요구가 생길 때 추가). | 확정 | 사용자 | D-ADMIN-002 | — | 없음 |
| BR-ADM-049 | 관리자 감사 조회는 SECURITY_ADMIN·APPROVER가 전체, VIEWER·OPERATOR가 자기 행위 기록만 볼 수 있고, 감사 조회 자체를 감사하며 그 감사를 남기지 못하면 결과를 돌려주지 않는다. | 확정 | 사용자 | D-ADMIN1-008 | BOOT1 §4.6 | AuditQueryPermissionSpec::a viewer without the all-audit permission sees only their own rows … AUDIT_VIEWED is recorded first; AuditQueryPermissionSpec::when AUDIT_VIEWED cannot be recorded no results are returned |
| BR-ADM-050 | 관리자 계정 목록·조회의 login_name은 SECURITY_ADMIN에게만 보이고, 그 밖 역할에는 표시명·actorId·상태·역할만 보인다. | 확정 | 사용자 | D-ADMIN1-017 | BOOT1 §4.5 | AccountEnrollmentHttpSpec::only a SECURITY_ADMIN sees loginName |
| BR-ADM-051 | SECURITY_ADMIN이 아닌 조회자에게는 승인 목록·상세의 대상 스냅샷에서 loginName을 뺀다(표시명·actorId만). | 확정 | 사용자 | D-ADMIN1-020 | BOOT1 §4.7 | ApprovalHttpSpec::no viewing role (… SECURITY_ADMIN) sees the target login name; the stored snapshot has none |
| BR-ADM-052 | 지급·계좌 변경·정산 조정·재처리 권한은 조회 권한과 분리한다. [경계 FIN] | 확정 | 기준 | baseline 05 §16 | — | 없음(해당 기능 미구현) |
| BR-ADM-053 | 운영자 공유 계정과 공유 DB root 계정을 쓰지 않는다. | 확정 | 기준 | baseline 05 금지사항 | — | 없음 |

## 7. 2인 승인 틀

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-054 | 2인 승인 대상: baseline(재지급·고액 조정·Critical 해결·강제 Hold 해제·선지급 예외) + LIVE API key 발급, 한도 상향·무제한 설정, 가맹점 종료·계약 철회, 카드사 개통 승인, 관리자 역할·SECURITY_ADMIN 부여, TOTP 재등록·계정 잠금 해제, 정산 지급 계좌 변경, 수수료율 변경, 마스킹 해제 데이터 대량 다운로드. [경계 BIZ·FIN — 업무 대상별 세부는 각 대장] | 확정 | 사용자 | D-ADMIN-003, baseline 05 §17, INV-15 | BOOT1 §4.7.1 | AdminOwnedCommandSpec::each of the 6 ADMIN-owned kinds(관리자 항목만). 나머지 항목: 없음(미구현 또는 다른 대장) |
| BR-ADM-055 | 차단·정지·한도 축소 같은 안전 방향 명령은 1인 즉시 실행한다. | 확정 | 사용자 | D-ADMIN-003 | BOOT1 §4.7.1 | AdminAccountCommandSpec::the target is suspended; AdminAccountCommandSpec::an active role is revoked |
| BR-ADM-056 | 요청 뒤 대상이 바뀌면(대상 버전 불일치) 승인은 무효(INVALIDATED)이며 실행하지 않는다. | 확정 | 사용자 | D-ADMIN-003 | BOOT1 §4.7.3 | AdminOwnedCommandSpec::the target changed after the request (security_version) → INVALIDATED; OwnerServiceApprovalFlowSpec::TARGET_CHANGED … INVALIDATED |
| BR-ADM-057 | 요청 뒤 요청자가 정지되거나 요청 역할을 잃으면 승인 시 무효(INVALIDATED)로 닫고 실행하지 않는다. | 제안 | 적용 | BOOT1 rev 0.3 n-A | BOOT1 §4.7.3 | AdminOwnedCommandSpec::the requester was suspended after the request → INVALIDATED |
| BR-ADM-058 | 승인 요청·상태는 ADMIN이 관리하되 소유 서비스 명령도 요청자·승인자·승인 ID·대상 버전을 받아 요청자≠승인자·대상 버전 일치를 다시 검사하고 자기 감사에 남긴다. 관리자 앱 단독으로 2인 승인 대상 명령을 실행할 수 없다. | 확정 | 사용자 | D-ADMIN-007 | BOOT1 §4.7.3-7, OPS1 | AdminOwnedCommandSpec::the owner re-check contract; AdminOperatorCancellationApiSpec(payment-api)::same requester and approver is 409 OPERATOR_SAME_PERSON; ApprovalEvidenceSpec(master-api)::the same canonical actor is APPROVAL_SAME_ACTOR |
| BR-ADM-059 | 승인이 곧 실행이다: 승인자가 TOTP와 함께 승인하면 승인 커밋 직후 그 요청이 소유 Use Case를 호출하고, 결과가 불명확하면 같은 approvalId로 재전송하며(승인 재요청 없음) 감사의 실행자는 승인자다. | 확정 | 사용자 | D-ADMIN1-006 | BOOT1 §4.7.3, OPS1 | AdminOwnedCommandSpec::EXECUTED with executor = approver; OwnerServiceApprovalFlowSpec::200 STARTED → EXECUTED; OwnerExecutionRecoverySpec::the same stored body bytes are resent |
| BR-ADM-060 | 같은 (종류, 대상)의 대기 중 승인 요청은 하나만 둔다. | 제안 | 적용 | BOOT1 n-6 | BOOT1 §4.7.2 | AdminOwnedCommandSpec::a second PENDING of the same kind and target is DUPLICATE_PENDING; OwnerServiceApprovalFlowSpec::409 DUPLICATE_UNRESOLVED |
| BR-ADM-061 | 거절도 승인처럼 APPROVER가 직전 TOTP를 다시 입력해야 한다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ③(승인·반려 재인증 전제), BOOT1 n-B | BOOT1 §4.7.8 | AdminOwnedCommandSpec::reject consumes a TOTP step too; ApprovalHttpSpec::APPROVER rejects with TOTP (200 REJECTED) |
| BR-ADM-062 | 철회는 요청자 본인만, 대기(PENDING) 중일 때만 할 수 있다. | 제안 | 적용 | BOOT1 n-B | BOOT1 §4.7.8 | AdminOwnedCommandSpec::withdraw: only the requester, only while PENDING |
| BR-ADM-063 | 승인 요청은 유효기간이 지나면 만료(EXPIRED)되고 실행하지 않는다(판정은 승인 TX 시각). | 제안 | 적용 | BOOT1 n-5 | BOOT1 §4.7.2 | AdminOwnedCommandSpec::expiry is judged with the TX clock |
| BR-ADM-064 | 승인 요청 유효기간은 24시간이다. | Demo값 | 적용 | BOOT1 D-SV | `application.yml` approval.validity | 없음 |
| BR-ADM-065 | 화면 개편은 감사·2인 승인·결과 불명 잠금·되돌릴 수 없음 확인 같은 안전 장치를 줄이지 않는다(위치만 바뀐다). | 확정 | 사용자 | D-ADMIN-UI-010, D-ADMIN-UI-011 ① | MERCHANT-IA-001-SCREEN-ITEMS.md | 없음 |

## 8. 재인증(직전 TOTP)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-066 | 2인 승인(승인·거절)과 마스킹 해제 직전에 TOTP를 다시 입력해야 하고, 같은 TOTP 단계는 다시 쓸 수 없다. | 확정 | 사용자 | D-ADMIN-004 | BOOT1 §4.7.4 | ApprovalHttpSpec::… missing TOTP → 400; wrong TOTP → 403 STEP_UP_FAILED; ApprovalRaceSpec(StepUpTotpReuseRace); AdminOwnedCommandSpec::the approver's TOTP step is still consumed (no reuse) |
| BR-ADM-067 | 재인증 실패는 로그인 실패 계수에 합산되고(한도면 잠김·세션 종료), 승인 행은 바뀌지 않는다. | 확정 | 사용자 | D-ADMIN1-005 | BOOT1 §4.7.4 | AdminOwnedCommandSpec::a wrong code rolls the approval back and counts the failure in a separate TX (StepUpFailureCounts) |
| BR-ADM-068 | 재인증 실패 계수 TX가 재시도 3회 뒤에도 교착·대기 초과로 실패하면 그 1회는 계수하지 않고 500으로 응답하며(승인 효과 없음), 누락은 지표·경보로 관측한다. | 확정 | 사용자 | D-ADMIN1-016 | BOOT1 §4.7.4 | AdminObservabilitySpec::that one failure is not counted … not_recorded makes it visible |

## 9. 관리자 감사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-069 | 감사 행위자는 불변 actor ID + 기록 시점 표시명 스냅샷으로 남긴다. | 확정 | 사용자 | D-ADMIN-004 | BOOT1 §4.5.2, §4.6 | AdminDatabaseAccountSpec::creation-immutable columns refuse UPDATE with 1143 |
| BR-ADM-070 | 감사·역할 이벤트는 추가만 할 수 있고 수정·삭제할 수 없다. | 확정 | 기준 | baseline 05 금지사항, D-ADMIN-002 | BOOT1 §4.2.2, §4.6 | AdminDatabaseAccountSpec(append-only 표 UPDATE·DELETE 1142) |
| BR-ADM-071 | 감사 대상: 로그인 각 단계·잠금·로그아웃·세션 교체, 미인증 경로 IP 거부, 계정 생성·정지·코드 발급/사용/폐기, 역할 부여·회수·정지·재활성, 승인 요청·승인·거절·철회·만료·무효·실행 결과, 재인증, 마스킹 해제, 감사 조회, 부트스트랩·복구. | 제안 | 적용 | BOOT1 §4.6(D-ADMIN-002·D-ADMIN1-001·008 일부) | BOOT1 §4.6 | AuditFailureClosesActionSpec; LogoutAuditFailureSpec; AdminOwnedCommandSpec::withdrawing an expired request expires it with an audit row |
| BR-ADM-072 | 감사를 남기지 못하면 그 행위도 실패한다(상태 변경과 같은 TX, 조회는 결과 미반환). | 제안 | 적용 | BOOT1 §4.6, PII §6-5(사용자 결정은 D-TXOPS-002 거래 상세 조회 한정) | BOOT1 §4.6 | AuditFailureClosesActionSpec::account creation / suspension / enrollment completion / authentication failure; TransactionAuditFailureHttpSpec |
| BR-ADM-073 | 관리자 감사 detail에는 고정 `key=value` 요약만 남기고, 자유 문구는 마스킹 해제 사유(reason 열)에만 허용한다. | 제안 | 적용 | BOOT1 §4.6, D-ADMIN1-019 | PII §6-3 | AdminNonDisclosureSpec::the unmask reason only in its audit reason column; OperatorDecisionReconciliationSpec::a finding record accepts only column names or codes as detail |
| BR-ADM-074 | 감사·로그·지표·오류에 비밀번호·등록 코드·TOTP 시크릿·세션 ID·CSRF 토큰·암호화/HMAC 키를 넣지 않는다. | 확정 | 기준 | baseline 05 §19, D-ADMIN-001 | BOOT1 §6, PII §6-4 | AdminNonDisclosureSpec::no secret in logs or stored rows; ErrorNonDisclosureSpec |
| BR-ADM-075 | IP 허용 목록 거부는 감사 1행을 유지하고(현재 구현 범위: 미인증 경로), ip_denied 지표 급증은 경보하며, 앞단(WAF·LB) 속도 제한은 운영 배치 항목이다. | 확정 | 사용자 | D-ADMIN1-018 | BOOT1 §25.5 | IpAllowlistSpec::a refused authentication-path request is audited anonymously; AdminObservabilitySpec::an authentication path counts as AUTH, an enrollment path as ENROLL and any other path as OTHER |
| BR-ADM-076 | 관리자 로컬 TX는 commit 단계 실패를 rollback한다(잠금 즉시 해제 후 재승인 가능). | 확정 | 사용자 | D-ADMIN1-021 | BOOT1 §25 | ApprovalFailureInjectionSpec::D-ADMIN1-021: a TransactionException in the commit phase is rolled back |
| BR-ADM-077 | 운영 도구는 소유 플랫폼 Use Case를 호출하고 요청 ID·대상 범위·사유·권한·승인·결과·재시도 여부를 남기며, 운영자가 SQL로 거래 상태·금액을 직접 고치지 않는다. ADMIN DB는 관리자 메타데이터만 담고 업무 데이터는 소유 서비스 API로만 읽고 바꾼다. | 확정 | 사용자 | D-ADMIN-005, baseline 07 §17·금지사항 | BOOT1 §4.2 | AdminDatabaseAccountSpec(표 단위 권한 정확 비교); PaymentOpsGatewaySpec::PaymentCallOutsideTransactionSpec |

## 10. 소유 서비스 승인 실행(운영자 보정 취소) [경계 CAN]

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-078 | ADMIN→PAYMENT 내부 API는 HMAC-SHA256 서명(메서드·정규화 경로·시각·본문 SHA-256)을 쓰고 query 문자열을 금지하며, ADMIN 전용 키(MASTER 권리 채널 키와 같으면 PAYMENT 기동 거부)를 비밀 저장소에서 주입하고 교체 중에는 2키를 허용한다. 응답 서명 없음, 운영 mTLS는 LIVE Gate. | 확정 | 사용자 | D-OPS-001 | OPS1 | PaymentOpsGatewaySpec::the POST carries a signature the stub verifies independently, no query string; AdminChannelKeySeparationSpec(payment-api); AdminChannelSecurityChainSpec(payment-api)::a query string is 400 QUERY_NOT_ALLOWED |
| BR-ADM-079 | 내부 API 서명 시각 창은 ±300초다. | Demo값 | 사용자 | D-OPS-001 | OPS1 | AdminCallSignatureSpec(payment-api)::exactly 300 seconds either way is accepted |
| BR-ADM-080 | 보정 취소 명령은 nonce 저장소 없이 approvalId 영구 키로 멱등이며, PAYMENT는 approvalId별 첫 확정 결정(실행 수락 또는 업무 거절)을 고정해 같은 approvalId에 같은 결정을 돌려준다. 일시 거절도 그 승인을 소진한다. | 확정 | 사용자 | D-OPS-001, D-OPS-005 | OPS1 | AdminOperatorCancellationApiSpec(payment-api)::the same bytes again replay it; ::business rejections are decisions (D-OPS-005); ::a temporary rejection stays after its condition is gone |
| BR-ADM-081 | 승인 시각 + 설정 기한 안에서만 PAYMENT가 새 보정 취소를 시작하고, 지나면 APPROVAL_EXECUTION_EXPIRED로 거절·감사하며 ADMIN은 OWNER_REJECTED로 닫는다. 이미 시작된 같은 approvalId는 기한과 무관하게 기존 결과를 돌려준다. | 확정 | 사용자 | D-OPS-002 | OPS1 | AdminOperatorCancellationApiSpec(payment-api)::now >= executeBefore is 409 APPROVAL_EXECUTION_EXPIRED; ::an already started approval replays after its deadline |
| BR-ADM-082 | 승인 실행 기한은 승인 후 24시간이다. | Demo값 | 사용자 | D-OPS-002 | OPS1, `application.yml` payment-ops.execution-window | AdminOperatorCancellationApiSpec(payment-api)::a window over 24h … is APPROVAL_WINDOW_INVALID |
| BR-ADM-083 | 실행 결과 불명(EXECUTION_UNKNOWN)은 PAYMENT 확정 응답(EXECUTED/OWNER_REJECTED)으로만 끝나고, 같은 내용 자동 재전송은 횟수 상한 없이 backoff로 계속하며 오래되면 경보한다. 수동 '미실행 확정'은 없다. | 확정 | 사용자 | D-OPS-003 | OPS1 | OwnerExecutionRecoverySpec::30 s × 2^(n−1) … with no attempt limit; ::the decision lookup is unavailable → no resend this cycle |
| BR-ADM-084 | 실행 중·불명 승인의 요청자·승인자가 정지되거나 역할을 잃으면 재전송을 멈추고 불명 상태를 유지·경보하되, 결정 상태 조회는 계속해 PAYMENT 기록을 반영한다. | 확정 | 사용자 | D-OPS-006 | OPS1 | OwnerExecutionRecoverySpec::ResendHaltOnIneligibleParticipantSpec |
| BR-ADM-085 | PAYMENT의 APPROVAL_CONFLICT·APPROVAL_IDENTITY_MISMATCH는 확정 응답이 아니며, ADMIN은 불명 상태를 유지하고 무결성 경보를 낸 뒤 조회한 PAYMENT 결정을 그대로 반영한다. | 확정 | 사용자 | D-OPS-007 | OPS1 | OwnerExecutionRecoverySpec::IntegrityFlagNoResendSpec; OwnerServiceApprovalFlowSpec::derived 409 → EXECUTION_UNKNOWN + integrity flag |
| BR-ADM-086 | PAYMENT 운영자 결정 목록과 ADMIN 승인을 주기적으로 대조해 승인 없는 실행·상태 불일치를 경보한다. | 확정 | 사용자 | D-OPS-008 | OPS1 | OperatorDecisionReconciliationSpec::ORPHAN_DECISION; ::STATE_DIVERGENCE; ::FINGERPRINT_MISMATCH |
| BR-ADM-087 | 재전송이 멈췄거나 결정 없는 미해결 승인은 기한 경과 뒤 기한 확인 전용 호출로 정리하고, PAYMENT는 자기 시각 기준 now ≥ executeBefore이고 결정이 없을 때만 거절(APPROVAL_EXECUTION_EXPIRED)을 기록하며 취소를 시작하지 않는다. | 확정 | 사용자 | D-OPS-009 | OPS1 | OwnerExecutionRecoverySpec::DeadlineConfirmationRecoverySpec ①~④; AdminOperatorCancellationApiSpec(payment-api)::the deadline confirmation call |
| BR-ADM-088 | 보정 목적 근거 참조는 Demo·TEST에서 형식 검사(1~200자, 허용 문자)만 하고 감사에 남기며, 원천 대조(RECON Case·MASTER 종료 기록)는 LIVE Gate 차단 조건이다. | 확정 | 사용자 | D-OPS-004 | OPS1 | OperatorCancelInputRulesSpec::PurposeReferenceFormat … 1..200 |
| BR-ADM-089 | 거래 상세의 운영자 보정 취소는 '운영자 취소'만 표시하고 사유·요청자·승인자·승인 건 ID는 승인 화면에서만 보이며, 가맹점 대상 조회·Webhook에는 운영자 신원·사유·approvalId를 내보내지 않는다. [경계 CAN·PAY] | 확정 | 사용자 | D-TXOPS-005, D-CPQ-002 | TXN-OPS-001, PII §3 #18 | 없음(ADM 쪽 시험 미확인 — CAN·PAY 대장에서 확인) |

## 11. 민감 조회(근거·사유 열람)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-090 | 운영자 보정 취소 승인의 근거 참조·사유 문구는 APPROVER와 요청자 본인만 별도 조회로 보고, 볼 때마다 조회 감사를 남기며, 목록·VIEWER·그 밖 역할에는 숨긴다. | 확정 | 사용자 | D-OPS-010 | OPS1 S8, PII §5.2 | OperatorCancelJustificationHttpSpec::the requester and an APPROVER read it … each read is audited once; ::another OPERATOR, a SECURITY_ADMIN and a VIEWER get 403 |
| BR-ADM-091 | 재매입 근거 참조, 가맹점 계약 승인·첫 MID 발급 근거 참조도 BR-ADM-090과 같은 열람 규칙이다. [경계 FIN·BIZ] | 확정 | 사용자 | D-ACQA-005, D-MOPS-008 ① | ACQ-ADMIN-001, MERCHANT-OPS-002 §9 | 없음(ADM 쪽 전용 시험 미확인) |
| BR-ADM-092 | 근거·사유 열람은 조회자별 분당 상한(20회)을 두고, 상한 초과 거부는 감사하지 않는다. | Demo값 | 적용 | OPS1 S8 L-3, I-131 | `application.yml` approval.justification-per-minute | OperatorCancelJustificationHttpSpec::a per-actor rate limit … 429 RATE_LIMITED without an audit row |

## 12. 마스킹 해제(UNMASK)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-093 | 마스킹 해제는 UNMASK 권한 + 사유 필수 + 직전 TOTP를 요구하고, 성공과 권한 없는 시도 모두 감사 1행을 남긴다(권한 없는 시도에는 사유 원문을 남기지 않는다). | 확정 | 사용자 | D-ADMIN-002, D-ADMIN-004 | BOOT1 §4.5.3, PII §5.1 | ApprovalHttpSpec::no UNMASK role → 403; missing reason → 400; missing TOTP → 400 … valid → 204 with one audit row keeping the reason; ::a denied attempt … audited as DENIED without the reason text |
| BR-ADM-094 | 마스킹 해제 사유 원문은 감사에 저장한다(길이 상한 500자, 제어 문자 거부). | 확정 | 사용자 | D-ADMIN1-019 | BOOT1 §4.5.3 | ApprovalHttpSpec::a reason over the length limit is 400 REASON_INVALID; AdminNonDisclosureSpec::the unmask reason only in its audit reason column |
| BR-ADM-095 | 마스킹 해제 사유에는 카드번호 패턴 검사를 하지 않고 첫 실제 적용 때 다시 검토한다. | 대체됨→BR-ADM-096 | 사용자 | D-ADMIN1-019 | PII §4.4 | 없음 |
| BR-ADM-096 | 마스킹 해제 사유에 카드번호 패턴이 있으면 저장 전에 거부한다. | 확정 | 사용자 | D-FLOW-005 ⑧ | FLOW1 rev 0.3a | 없음(미구현 — 진단 e-1) |
| BR-ADM-097 | UNMASK 권한은 Demo seed 계정에 미리 주지 않고, SECURITY_ADMIN이 기존 2인 역할 부여 절차로 준다. | 확정 | 사용자 | D-FLOW-005 ⑧ | FLOW1 rev 0.3a | 없음(UNMASK 전용 부여 시험 없음 — ApprovalHttpSpec은 fixture로 직접 부여) |
| BR-ADM-098 | 마스킹 해제의 첫 실제 적용 데이터는 대표자 생년월일·실소유자 정보(KYC)다. [경계 BIZ] | 확정 | 사용자 | D-FLOW-002 ①, Q-PII-3(일부 해소) | FLOW1 rev 0.2a | 없음(미구현 — UnmaskService는 NoDataSource) |
| BR-ADM-099 | KYC 원문 조회 1회는 사업자 하나의 KYC 전체이고 감사 1행이며, 원문 보기는 현재 값만 보여 준다(과거 값은 저장만). [경계 BIZ] | 확정 | 사용자 | D-FLOW-005 ⑥, D-FLOW-008 ③ | FLOW1 rev 0.3b | 없음(미구현) |
| BR-ADM-100 | KYC 마스킹 표시는 생년월일 `****-**-**`, 실소유자 이름 `홍**`, 지분율 비마스킹이며, 마스킹된 KYC 수정 화면에는 원문을 채우지 않는다(빈 칸 = 유지). [경계 BIZ] | 확정 | 사용자 | D-FLOW-005 ⑥, D-FLOW-008 ① | FLOW1 rev 0.3b | 없음(미구현) |
| BR-ADM-101 | KYC 현재 값·마스킹 표시·원문 조회는 현재 대표자 key 행만 대상으로 하고, 종료된 대표자 key 행은 저장만 하며 응답하지 않는다. [경계 BIZ] | 제안 | 적용 | D-FLOW-009 Coordinator 적용 메모, D-FLOW-008 ②③ | FLOW1 rev 0.3c | 없음 |
| BR-ADM-102 | 마스킹 해제 데이터 대량 다운로드는 2인 승인 대상이다(다운로드 기능은 아직 없다). | 확정 | 사용자 | D-ADMIN-003 | PII §5.1 | 없음(기능 없음) |

## 13. 개인정보 표시·로그 규칙

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-103 | 사업자등록번호는 화면·조회 응답을 포함해 어디서나 마스킹 값만 보인다. | 대체됨→BR-ADM-104 | 사용자 | D-BIZ-006 ②, D-MOPS-003(응답 마스킹), D-MCODE-003 ②, D-BIZ-003 | HK1 §2 | 없음 |
| BR-ADM-104 | 뉴피지 관리자 화면 전체(사업자·가맹점 목록·상세, 번호 조회 결과, 등록 중복 안내, 가맹점 등록 마법사의 "사업자: 상호 · 번호", 변경 이력 대상 표시)와 그 값을 주는 ADMIN·MASTER 조회 응답은 사업자등록번호 원문을 보여 준다(화면은 하이픈 표시). [경계 BIZ] | 확정 | 사용자 | D-BIZ-007, D-PII-001 ①, Q-PII-1, Q-PII-11 | BRN1 §1·§2 | BusinessShapeGoldenSpec::registrationNumber carries the raw 10 digits; MerchantCodeShapeGoldenSpec::list/code search/detail carry the raw 10-digit number; lib/historyTarget.test.ts::T-HK-1 business row; MerchantLookupHttpSpec |
| BR-ADM-105 | 사업자등록번호는 감사 detail·변경 이력 저장값·로그·지표·오류 메시지에는 마스킹(앞 3자리 + `*`, 형식 오류는 `INVALID`)으로만 남긴다. | 확정 | 사용자 | D-BIZ-007, D-PII-001, D-MOPS-003 | BRN1 §3, PII §3 #1 | BusinessHttpSpec::BIZ-3-6 canary values never land in ADMIN storage, logs, metric labels or error bodies; MerchantLookupHttpSpec(감사 마스킹) |
| BR-ADM-106 | 원문 번호가 보이는 목록 조회는 감사하지 않고, 상세 열람 감사와 번호 조회의 매 시도 감사·횟수 제한은 유지한다. | 확정 | 사용자 | D-PII-001 ②, Q-PII-4, D-MOPS-002, D-MOPS-003 | BRN1 §3 | MerchantLookupServiceSpec; BusinessQueryServiceSpec(목록 감사 0건 단언 — BRN1 §3 "불변") |
| BR-ADM-107 | 사업자등록번호에는 별도 마스킹 해제 절차를 두지 않는다. | 확정 | 사용자 | D-PII-001 ③, Q-MOPS-04, Q-PII-2 | PII §8 | 없음 |
| BR-ADM-108 | 사업자등록번호를 식별자로 쓰지 않으며 화면 URL·브라우저 저장소에 원문 번호·내부 키를 두지 않는다. | 제안 | 적용 | D-BIZLOOKUP-001, BUSINESS-PROFILE-001 §8 | BRN1 §3, PII §6-10 | BusinessesScreen.test.tsx(window.location.href에 biz_ 없음); test/fakeApi(브라우저 저장소 비어 있음 확인) |
| BR-ADM-109 | 사업자 상세 개인정보(대표자·담당자 이름, 전화, 이메일, 주소, 법인등록번호)는 조회 역할에 원문으로 보이고 상세 열람마다 감사 + 속도 제한을 두며, 로그·감사·변경 이력에는 항상 마스킹한다. [경계 BIZ] | 확정 | 사용자 | D-BIZ-006 ② | PII §3 #2~6 | BusinessHttpSpec::BIZ-3-6; BusinessQueryServiceSpec |
| BR-ADM-110 | 운영자가 쓰는 공통 '사유 및 비고' 칸(사업자 중요정보 변경 사유 포함)은 칸 전체에 카드번호 패턴 검사를 해 입력을 거부한다. [경계 BIZ] | 확정 | 사용자 | D-FLOW-007 ①, D-FLOW-006, Q-PII-6 | FLOW1 rev 0.3b | 없음(미구현 — 진단 e-2) |
| BR-ADM-111 | 카드번호 패턴 판정: 십진 숫자(모든 문자권) 13~19자리, 숫자 사이 공백 또는 하이픈 1개 허용, 앞뒤가 숫자가 아님, Luhn 검사 없음. 거부 응답은 고정 code만 돌려주고 값은 넣지 않는다. | 제안 | 적용 | OPS1 I-17·I-111, PII §4.4 | PII §4.4 | OperatorCancelInputRulesSpec::ReasonCardData (I-17); ::card digit runs in any decimal digit script; ::every refusal has one fixed reason code |
| BR-ADM-112 | 관리자 화면·응답은 카드번호·유효기간·CVC·인증 비밀번호를 받거나 표시하지 않는다. [경계 PAY] | 확정 | 기준 | baseline 05 §21, CLAUDE.md(카드정보 비노출) | PII §3 #8 | 없음(ADM 쪽 시험 없음 — PAY 대장) |
| BR-ADM-113 | 로그·이벤트·지표·오류 메시지에는 카드·개인정보·Secret 원문을 넣지 않고, 오류 응답·fieldErrors에는 입력 값을 넣지 않는다(고정 code만). | 확정 | 기준 | baseline 05 §19·§23, baseline 07 §14, D-MOPS-003 | PII §6-1·6 | ErrorNonDisclosureSpec; AdminNonDisclosureSpec; BusinessHttpSpec::BIZ-3-6 |
| BR-ADM-114 | 검색·다운로드·원문 접근은 별도로 감사한다. | 확정 | 기준 | baseline 05 §23 | PII §6-9 | MerchantLookupServiceSpec; OperatorCancelJustificationHttpSpec |
| BR-ADM-115 | 개인정보·계좌는 필드 단위 접근·마스킹·암호화·목적별 보존·파기를 적용하고, 백업과 감사 저장소도 같은 통제 대상이다. 보관·파기 기간은 컴플라이언스 확인 후 정하며 그 전까지 파기 기능은 없다. | 미결(Q-PII-5) | 기준 | baseline 05 §22, D-FLOW-002 ④, Q-PII-5 | PII §6-11 | 없음 |

## 14. 내부 식별자 화면 비노출

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-116 | 내부 키(biz_·mer_·site_·ctr_·csa_·act_·apr_·이력 대상 키 등)는 화면(본문·속성·입력 값)에 보이지 않게 하고 사람용 값(가맹점 코드·채널 식별값·종류명 등)으로 대신하며, 내부 키는 요청·응답 안에서만 쓴다. | 확정 | 사용자 | D-ADMIN-UI-004 ③, D-ADMIN-UI-005, D-ADMIN-UI-010 ③, D-MCODE-003, D-BIZLOOKUP-001 | HK1 §3, PII §3 #25 | lib/historyTarget.test.ts::T-HK-1 (no mer_ / no biz_, fail-closed "—"); screens/MerchantRegistrationScreen.workspace.test.tsx(domOccurrences biz_ = []); screens/PaymentOpsScreen.workspace.test.tsx(domOccurrences biz_ = []) |
| BR-ADM-117 | Site·계약 사람용 코드는 관리자 화면·MASTER 전용이며 외부 연동(웹훅·결제 응답)·정산서에 내보내지 않고, 감사·승인 기록 저장값은 내부 키를 유지한다. [경계 BIZ] | 확정 | 사용자 | D-ADMIN-UI-007 ④⑤ | SITE-CONTRACT-CODE-001 rev 0.2 | 없음(작업 미병합 — wip/scode-1) |
| BR-ADM-118 | 서버 기능이 없는 메뉴·탭은 보이지 않게 하고, 샘플·가짜 데이터를 넣지 않는다. | 확정 | 사용자 | D-ADMIN-UI-003, D-ADMIN-HOME-001 ④ | ADMIN-UI-001-SHADCN.md | screens/HomeScreen.test.tsx::no fabricated numbers |

## 15. IP 제한·배치 모드·Secret

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-119 | 관리자 앱의 모든 경로는 사내망 IP 허용 목록 뒤에 있으며, 목록 밖 요청은 인증·세션 전에 403으로 거부한다. 프록시 헤더(X-Forwarded-For)는 해석하지 않고, 빈 목록·형식 오류면 기동하지 않는다. | 확정 | 사용자 | D-ADMIN-001(IP 허용 목록 병행) | BOOT1 §4.8.1 | IpAllowlistSpec::an address outside the list is refused with 403 IP_DENIED before any session, ignoring X-Forwarded-For; ::an empty list … is rejected (startup fails) |
| BR-ADM-120 | LIVE 배치는 모든 주소를 허용하는 목록(0.0.0.0/0, ::/0)을 거부한다. | 제안 | 적용 | BOOT1 S4 m-5 | BOOT1 §4.8.1 | IpAllowlistSpec::a LIVE deployment refuses an allowlist that permits every address |
| BR-ADM-121 | 배치 모드(DEMO/LIVE)는 기본값 없이 반드시 지정하고, LIVE에서 TOTP 암호화 키·login_name HMAC 키가 없거나 알려진 Demo 키이거나 보안 수치가 Demo 값이거나 BCrypt cost가 하한 미만이면 기동하지 않는다. | 제안 | 적용 | BOOT1 n-4, Q-ADMIN-SEC-VALUES | BOOT1 §4.8.2 | LiveModeStartupGuardSpec::every Demo key, every Demo security value and a BCrypt cost below the LIVE minimum are reported; ::the deployment mode has no default |
| BR-ADM-122 | 관리자 세션 idle·절대 만료와 로그인 실패 잠금 횟수(복구 회차 유효기간 포함)는 사내 보안·컴플라이언스 기준으로 확정하고, 그 전까지는 Demo 값으로만 운영하며 LIVE Gate에서 막는다. | 미결(Q-ADMIN-SEC-VALUES) | — | D-ADMIN-004, Q-ADMIN-SEC-VALUES | BOOT1 §4.8.2 | LiveModeStartupGuardSpec(Demo 값 거부) |
| BR-ADM-123 | 비밀(키·비밀번호·TOTP 시크릿·서명 키)은 비밀 저장소에서 주입하고 저장소·로그·이벤트·이미지·소스에 두지 않는다. | 확정 | 기준 | baseline 05 §19, D-ADMIN-001, D-OPS-001 | PII §2, BOOT1 §6 | AdminNonDisclosureSpec::no secret in logs or stored rows |

## 16. 데모 간편 로그인(DEMO 한정 임시 기능)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-124 | 데모 간편 로그인은 배치 모드 DEMO **그리고** 명시 설정 켜짐(**그리고** 허용 계정 목록 1개 이상)일 때만 동작하고, 아니면 엔드포인트가 없다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ② | DEMO1 §2 | DemoQuickLoginHttpSpec(T-1 켜짐); DemoQuickLoginDisabledHttpSpec(T-1 꺼짐 404) |
| BR-ADM-125 | LIVE에서 간편 로그인 설정이 켜져 있으면 기동하지 않는다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ② | DEMO1 §2 | DemoQuickLoginStartupGuardSpec(T-3) |
| BR-ADM-126 | 간편 로그인은 허용된 데모 계정의 정상 세션을 비밀번호·TOTP 없이 발급하되, 역할·감사·2인 승인(요청자≠승인자)·세션 규칙(ID 재발급·새 로그인 우선)은 그대로다. 로그인 자체를 끄는 방식은 쓰지 않는다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ① | DEMO1 §4 | DemoQuickLoginHttpSpec::the newer login wins; ::the session is the account's own: role-dependent APIs follow the current roles |
| BR-ADM-127 | 간편 로그인이 켜져 있으면 화면에 '데모 간편 로그인'을 표시한다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ② | DEMO1 §7 | screens/LoginScreen.demoQuick.test.tsx(T-9); layout/DemoQuickLogin.app.test.tsx(T-10) |
| BR-ADM-128 | 간편 로그인으로 만든 세션이고 켜짐 조건일 때만 승인·반려·마스킹 해제의 재인증 TOTP를 생략하고, 생략 사실을 같은 TX의 감사(STEP_UP_SKIPPED)에 남긴다. 일반 세션은 그대로 TOTP를 요구한다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 ③ | DEMO1 §8 | DemoQuickStepUpHttpSpec::unmask without a TOTP passes … records the skip; ::nothing changes: an empty TOTP is 403 STEP_UP_FAILED (일반 세션); DemoQuickMerchantStepUpHttpSpec; DemoQuickOwnerStepUpHttpSpec |
| BR-ADM-129 | 간편 로그인은 운영 준비 전에 반드시 끄는 임시 기능이며, 코드 제거·유지 여부를 LIVE Gate 항목으로 기록한다. | 확정 | 사용자 | D-ADMIN-AUTH-DEMO-001 | DEMO1 §10 | 없음(운영 점검표) |
| BR-ADM-130 | 간편 로그인은 실패 계수를 올리지도 0으로 되돌리지도 않고, 잠김·정지·등록 대기·한도 도달 계정과 허용 목록 밖 이름은 같은 401로 거부·감사한다. | 제안 | 적용 | DEMO1 C-5, §5 | DEMO1 §5 | DemoQuickLoginHttpSpec(T-4) |
| BR-ADM-131 | DEMO + 간편 로그인 켜짐인데 IP 허용 목록이 모든 주소를 허용하면 기동하지 않는다. | 제안 | 적용 | DEMO1 MINOR-2(a) | DEMO1 §2 | DeploymentModeAllowlistSpec(T-15) |
| BR-ADM-132 | 기능을 끈 뒤 남은 간편 로그인 세션은 다음 요청에서 폐기하고, 켜짐 조건은 요청마다 다시 판정한다. | 제안 | 적용 | DEMO1 MINOR-B | DEMO1 §4, §8 | DemoQuickLoginDisabledHttpSpec(T-12)::defense in depth: even a demo-session context … gets no skip |

## 17. 운영 홈 KPI

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-ADM-133 | 오늘 승인 금액 = KST 오늘 0시~조회 시각에 PG 확정(confirmed_at)된 승인 합계 − 같은 기간에 완료된 취소·환불 합계(부분취소 포함, 이전 날짜 승인의 오늘 취소도 차감, 음수 가능). | 확정 | 사용자 | D-ADMIN-HOME-001 ①②, D-ADMIN-HOME-002 ③ | KPI1 rev 0.2 | HomeKpiReaderSpec(payment-api)::T-K1-1 [from, asOf); ::T-K1-3 partial cancel net 7,000; ::T-K1-5 yesterday's approval cancelled today net −5,000 |
| BR-ADM-134 | 오늘 승인 건수 = 오늘 승인된 결제 중 전액 취소되지 않은 건(부분취소 건 포함). | 확정 | 사용자 | D-ADMIN-HOME-001 ③ | KPI1 | HomeKpiReaderSpec(payment-api)::T-K1-3 count 1; ::T-K1-4 full cancels … count 0 |
| BR-ADM-135 | 종료(FAILED/EXPIRED) Payment의 늦은 승인과 그 보정 취소는 금액·건수에서 모두 빼고 '확인 필요'로 세며, 늦은 취소 성공(FORCED)은 차감·전액취소 판정에서 빼고 '확인 필요'로 센다. [경계 CAN] | 확정 | 사용자 | D-ADMIN-HOME-002 ①② | KPI1 | HomeKpiReaderSpec(payment-api)::T-K1-7 FORCED …; ::T-K1-8 … both excluded('확인 필요' 집계는 ADMIN-HOME-ATTENTION-001 BACKLOG — 없음) |
| BR-ADM-136 | 지급 예정액은 정산 기능이 생기기 전까지 '정산 기능 준비 전'으로 표시하고, KPI에는 'PG 확정 시각 기준 · 정산 금액 아님' 표지를 단다. | 확정 | 사용자 | D-ADMIN-HOME-001 ④, D-ADMIN-HOME-002 ③ | KPI1 | screens/HomeScreen.test.tsx::no fabricated numbers … 지급 예정액 shows "정산 기능 준비 전"; HomeScreen.test.tsx(PG 확정 시각 기준 · 정산 금액 아님) |
| BR-ADM-137 | KPI는 통화별로 나누어 보이고, 결과 미확정(UNKNOWN)·진행 중은 금액에서 빼고 '확인 필요'로 세며, TEST와 LIVE를 섞지 않는다. | 제안 | 적용 | D-ADMIN-HOME-001(Coordinator 구현 기본값) | KPI1 | HomeKpiQueryServiceSpec(payment-api)::items are LIVE first then TEST, currency ascending; HomeKpiReaderSpec(payment-api)::T-K1-6 unconfirmed facts … none changes the sums |
| BR-ADM-138 | KPI 조회 역할은 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN이며 UNMASK만 가진 계정은 볼 수 없다. | 제안 | 적용 | KPI1 C-2 | KPI1 | HomeKpiServiceSpec::T-K3-1·2 VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN get the document; UNMASK-only … forbidden |

규칙 수: 138행 — 확정 98(출처 사용자 90·기준 8), 제안 26, Demo값 8, 대체됨 3, 미결 3.

## 진단

### (a) 대체 사슬

| # | 사슬 | 현재 유효 | 남은 흔적 |
|---|---|---|---|
| a-1 | bootstrap 인원: D-ADMIN1-002(SA 1 + APPROVER 1) → **D-ADMIN1-022**(SA 2 + APPROVER 1, 2026-10-29 Demo 교착 409 CREDENTIAL_ISSUER 발견) | BR-ADM-033 | BOOT1 §0·§4.9.1 본문은 1+1 문장 유지(현행화 주석 한 줄만), §4.9.2 작업 B는 여전히 SA 1명 — 아래 f-6 |
| a-2 | 사업자등록번호 표시: D-MOPS-003(응답 마스킹)·D-BIZ-003·D-MCODE-003 ②(사업자: 상호 · 마스킹 값)·D-BIZ-006 ②(어디서나 마스킹) → **D-BIZ-007**(화면·조회 응답 원문) → **D-PII-001 ①**(범위 = 뉴피지 관리자 전체, 마법사·이력 대상 포함) | BR-ADM-104·105·106 | HK1 §0·§2·§3 "마스킹 사업자등록번호"(c-4), `lib/historyTarget.test.ts:29` 시험 이름 "masked"(e-5) |
| a-3 | 번호 원문 조회 절차: Q-MOPS-04(UNMASK 필요 여부 보류) → **D-PII-001 ③** 닫음 | BR-ADM-107 | 없음 |
| a-4 | 마스킹 해제 사유 PAN: D-ADMIN1-019("원문 유지 + 첫 적용 때 PAN 거부 재검토") → **D-FLOW-005 ⑧**(카드번호 패턴 거부 적용). 원문 저장·길이 상한 부분은 유지 | BR-ADM-094·096 | PII §3 #22·§4.4 "적용하지 않는 곳"(c-1), 코드(e-1) |
| a-5 | 사업자 중요정보 변경 사유 PAN: 결정 없음(구현 기본값 = 검사 없음, Q-PII-6) → **D-FLOW-007 ①**(공통 '사유 및 비고' 칸 전체 검사, Q-PII-6 닫음) | BR-ADM-110 | PII §3 #21·§4.4·§7.3·§8 Q-PII-6(c-1), 코드(e-2) |
| a-6 | 마스킹 해제 첫 적용 데이터: AC10 "틀만, 후속" · Q-PII-3(미정) → **D-FLOW-002 ①**(KYC 생년월일·실소유자) — Q-PII-3 일부만 해소 | BR-ADM-098 | BOOT1 §1.2·§4.5.3, PII §5.1(c-2) |
| a-7 | 사업자 조회 응답 범위: D-ADMIN-UI-004 ⑦("상호·마스킹 번호만", OPERATOR) → D-BIZ-006 ③(등록 상태 포함, OPERATOR·APPROVER) → D-BIZ-007(원문) | BIZ 대장 담당 [경계 BIZ] | — |
| a-8 | 재인증: D-ADMIN-004(승인·마스킹 해제 직전 TOTP) + **D-ADMIN-AUTH-DEMO-001 ③**(DEMO·간편 로그인 세션 한정 생략) — 대체가 아니라 좁은 예외 | BR-ADM-066 + BR-ADM-128 | BOOT1 §4.7.4에 예외 언급 없음(c-6) |
| a-9 | 2인 승인: D-ADMIN-003(카드사 개통 승인·한도) + D-DEMO-005·D-MOPS-008 ②(Demo seed 1인 처리) — DEMO 한정 예외. DEMO 접두 결정은 대장 제외 대상이라 행으로 두지 않았다 | BR-ADM-054 | BIZ 대장에서 예외 명시 필요 |
| a-10 | 화면 배치 사슬(대장 제외 — 참고): D-ADMIN-UI-004 ④ → D-ADMIN-UI-006, D-ADMIN-UI-010 ⑥ → D-ADMIN-UI-011 ② → D-FLOW-007 ②(새 섹션 예외), D-ADMIN-UI-005 '채널·식별값 표시' → 사람용 코드 발급(SITE-CONTRACT-CODE-001) | — | 업무 규칙 아님 |
| a-11 | ROLE_REACTIVATE 배제: D-ADMIN1-009 문언은 "재설정 **요청자**와 달라야" → BOOT1 H-A(Coordinator fail-closed로 승인자까지 확대) → D-ADMIN1-013 사용자 답이 "요청자·승인자"를 전제로 함 | BR-ADM-030(확정으로 둠) | 사용자가 확대를 명시 승인한 기록은 D-ADMIN1-013의 전제뿐 — 4단계 확인 권장 |

### (b) 결정 간 충돌

| # | 내용 | 판단 |
|---|---|---|
| b-1 | baseline 05 §16 "내부 운영자는 **SSO**/MFA" ↔ D-ADMIN-001 "자체 계정 DB + TOTP". D-ADMIN-005(ADMIN DB 소유)는 "baseline 01 편차"로 명시됐지만 SSO 편차는 기록이 없다 | 편차 기록 필요(질문 아님 — 사용자 답이 직접 작성) |
| b-2 | D-ADMIN1-020 "SECURITY_ADMIN이 **아니면** 스냅샷에서 loginName 제외"(SA는 볼 수 있다는 뜻) ↔ 구현·시험은 아무에게도 보이지 않음(저장 자체 안 함) | 결정보다 엄격 — 위반 아님, 유지 확인만(f-3) |
| b-3 | D-ADMIN1-018 "거부마다 감사 1행" ↔ 구현은 미인증 경로(`/api/auth/`·`/api/enroll/`)만 감사, 그 밖 경로는 지표만. 질문 원문(BOOT1 §25.5)이 "현재(미인증 경로) 유지"를 물은 것이라 해석상 일치 | 대장 문언을 "미인증 경로 거부마다"로 맞추기 권장(f-4) |
| b-4 | D-PII-001 ①(뉴피지 관리자 **전체** 원문) ↔ D-BIZ-006 ①(사업자 화면 조회 = OPERATOR·APPROVER). 가맹점 목록은 VIEW_MASKED 4역할이라 VIEWER·SECURITY_ADMIN도 감사 없이 원문 번호를 본다(BRN1 L-5 "결정 범위 안") | 충돌은 아니나 "역할 VIEW_MASKED" 이름과 실제 원문 노출이 어긋남 [경계 BIZ] — f-5 |
| b-5 | D-ADMIN-HOME-001이 KPI 통화 분리·UNKNOWN 제외·TEST/LIVE 혼합 금지를 "Coordinator 구현 기본값(사용자 확인 대상 아님)"으로 기록 ↔ RULE-GOV-001 §2.1 "Coordinator 적용 = 제안". TEST/LIVE 혼합·UNKNOWN 금액 처리는 금융 의미라 가역 세부로 보기 어렵다 | BR-ADM-137을 제안으로 둠 — 확인 질문 후보(f-7) |
| b-6 | D-MOPS-008 ①·D-OPS-010(근거 참조 = APPROVER·요청자만 별도 조회·감사) ↔ D-FLOW-004 ④(계약서 수령 근거 참조 = 사업자 조회 역할 전체 원문, 상세 열람 감사에 포함). 대상(2인 승인 근거 vs 계약 접수 건 근거)이 달라 모순은 아니지만 "근거 참조" 이름이 같아 혼동 위험 | [경계 BIZ] 용어 구분 권장 |
| b-7 | D-ADMIN-004 "idle/절대 만료·잠금 횟수 = 사내 보안 기준 확정(확정 전 Demo)" ↔ 구현·하네스는 Demo 값 5회·15분·8시간을 모든 시험 기대값으로 고정 | 충돌 아님(LIVE 가드가 Demo 값 거부) — Q-ADMIN-SEC-VALUES 대기 |

### (c) 명세·정책 본문이 현재 결정과 다른 곳

| # | 위치 | 본문 | 현재 결정 |
|---|---|---|---|
| c-1 | `docs/repository/PII-MASKING-POLICY.md` §4.4 "적용하지 않는 곳", §3 #21·#22, §7.3, §8 Q-PII-6 | 마스킹 해제 사유·사업자 중요정보 사유에 카드번호 패턴 거부 없음, Q-PII-6 미결 | D-FLOW-005 ⑧, D-FLOW-007 ①(Q-PII-6 닫힘) |
| c-2 | PII §5.1 마지막 줄, §8 Q-PII-3 | "첫 실제 적용 데이터는 정해지지 않았다" | D-FLOW-002 ①(KYC — 일부 해소) |
| c-3 | PII §5.3 "상태: IN_PROGRESS(미커밋)", §7.1 X-5 | 데모 간편 로그인 구현 중 | tasks.json ADMIN-DEMO-LOGIN-001 = DONE |
| c-4 | `docs/repository/ADMIN-HISTORY-KEYS-001-HISTORY-TARGET-LABELS.md` §0·§2·§3 표 | 사업자 행 = `<상호> · <마스킹 사업자등록번호>`, "D-MOPS-003: 마스킹 값만" | D-PII-001 ①·Q-PII-11 — 원문(하이픈). BRN1 §1 #9가 구현을 바꿨으나 HK1 본문 미정정 |
| c-5 | PII §3 | KYC 항목(대표자 생년월일·실소유자 이름·지분율) 행이 없음 | D-FLOW-002 ①, D-FLOW-005 ⑥ |
| c-6 | `ADMIN-BOOT-001-ADMIN-FOUNDATION.md` §4.7.4, §4.5.1 마스킹 해제 칸("틀만"), §1.2 "마스킹 해제 첫 적용 화면 — 후속" | 재인증 예외 언급 없음, 첫 적용 미정 | D-ADMIN-AUTH-DEMO-001 ③, D-FLOW-002 ① |
| c-7 | BOOT1 §4.9.1 본문 | "SECURITY_ADMIN 1 + APPROVER 1" 절차 문장(위에 현행화 주석만) | D-ADMIN1-022 |
| c-8 | `docs/baseline/architecture/05-security-architecture.md` 16행 | "SSO/MFA" | D-ADMIN-001(b-1) |
| c-9 | `ADMIN-DEMO-LOGIN-001` §12 R-4 | "결정 ③으로 대체" 표기는 있으나 §9 위협표의 '2인 승인 무력화' 행이 재인증 생략 영향(허용 IP 안 누구나 데모 승인자로 TOTP 없이 승인)을 R-4로만 참조 | 내용 일치, 표현만 분산(정정 불필요 수준) |

### (d) 시험 없는 확정 규칙

- BR-ADM-007(IP 허용 목록 밖 요청 계수 제외 — 구조로만 보장), BR-ADM-048(가맹점 범위 제한 없음), BR-ADM-052·053(기준 05 — 권한 분리·공유 계정 금지), BR-ADM-054의 관리자 외 항목(LIVE API key·한도·종료·계좌·수수료율·대량 다운로드 — 미구현 또는 다른 대장), BR-ADM-065(안전 장치 축소 금지), BR-ADM-089·091(ADM 쪽 시험 미확인 — CAN·FIN·BIZ 대장과 대조 필요), BR-ADM-096·097·098·099·100·102(MERCHANT-FLOW-001·마스킹 해제 미구현), BR-ADM-107, BR-ADM-110(미구현), BR-ADM-112, BR-ADM-117(SITE-CONTRACT-CODE-001 미병합), BR-ADM-129(운영 점검표).
- 시험은 있으나 BR-ADM-097 "UNMASK를 2인 역할 부여로 준다"는 UNMASK 전용 부여 시험이 없다(ApprovalHttpSpec은 fixture `path.grant`로 직접 부여).
- 이름이 계획된 시험(BOOT1 §2 AC 표의 `SelfServiceForbiddenSpec`·`RoleEventAppendOnlySpec`·`ActorIdImmutabilitySpec`·`AdminAuditAppendOnlySpec`·`StaleTargetInvalidatesSpec`·`AccountLifecycleSpec`·`ApproveExecutesSpec`)은 같은 이름의 파일·시험 문구가 없다. 내용은 AdminDatabaseAccountSpec·AdminOwnedCommandSpec·AdminAccountCommandSpec에 합쳐진 것으로 보이며, RULE-TRACE(BR-ID 연결) 때 시험 이름 기준을 정해야 한다.

### (e) 코드가 규칙과 다른 곳

| # | 위치 | 내용 | 규칙 |
|---|---|---|---|
| e-1 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/account/application/UnmaskService.kt:32-33` | 사유 검사 = 빈 값·길이 500·제어 문자만, 카드번호 패턴 검사 없음 | BR-ADM-096(D-FLOW-005 ⑧) — 결정 후 미구현(MERCHANT-FLOW-001 범위로 보임) |
| e-2 | `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessTextRule.kt:8-14`(사용처 `BusinessChangeReason.kt:10`) | 사업자 중요정보 변경 사유에 카드번호 패턴 검사 없음 | BR-ADM-110(D-FLOW-007 ①) — 미구현 [경계 BIZ] |
| e-3 | `UnmaskService.kt:63` | 통과해도 `NoDataSource`(대상 데이터 공급 없음) | BR-ADM-098(D-FLOW-002 ①) — 미구현 |
| e-4 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/application/ApprovalService.kt:440` | 승인 대상 스냅샷에 login_name을 아예 저장하지 않음(SA도 못 봄) | BR-ADM-051보다 엄격(b-2) — 위반 아님 |
| e-5 | `apps/internal-admin/frontend/src/lib/historyTarget.test.ts:29` | 시험 이름 "business row → name · **masked** registration number"인데 단언은 원문 `123-45-67890` | BR-ADM-104 — 시험 이름이 대체된 규칙(BR-ADM-103)을 가리킴. RULE-TRACE §2.3-4 검사에 걸릴 형태 |
| e-6 | `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/AdminCommandExecutor.kt:427-428` | 관리 명령 요청 지문 `m1:` 무염 SHA-256(사업자등록번호 등 개인정보 포함 필드) | D-MOPS-010 ②(MASTER 비밀 키 HMAC) — 미구현, LIVE Gate [경계 BIZ] |
| e-7 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/security/IpAllowlistFilter.kt:28-37` | 감사는 `/api/auth/`·`/api/enroll/` 거부만, 다른 경로 거부는 지표만 | BR-ADM-075 문언 해석(b-3) |

### (f) 미결·질문 후보

| # | 내용 | 상태 / 제안 |
|---|---|---|
| f-1 | Q-ADMIN-SEC-VALUES — 세션 idle/절대 만료, 잠금 횟수, 복구 회차 유효기간(+ 승인 요청 유효 24h·실행 기한 24h·서명 창 ±300초·열람 분당 상한도 같은 성격) | PROPOSED(2026-10-16부터). LIVE Gate. Demo값 행 BR-ADM-008·015·019·037·064·079·082·092를 이 질문에 묶어 한 번에 확인 권장 |
| f-2 | Q-ADMIN-APPROVER-STAFF — APPROVER 운영 인원 | PROPOSED. LIVE Gate |
| f-3 | D-ADMIN1-020: SECURITY_ADMIN에게도 승인 스냅샷 loginName을 보이지 않는 현재 동작을 그대로 둘지 | 질문 후보(낮음) — 권장: 현행 유지(더 엄격) |
| f-4 | D-ADMIN1-018: 인증된 경로의 IP 거부는 감사 없이 지표만인 현재 범위를 규칙으로 확정할지 | 확인 후보 — 권장: "미인증 경로 거부마다 감사"로 문언 확정 |
| f-5 | VIEWER·SECURITY_ADMIN이 가맹점 목록에서 사업자등록번호 원문을 감사 없이 보는 것(BRN1 L-5) | 확인 후보 [경계 BIZ] — D-PII-001 ①② 범위로 이미 수용됐다는 Coordinator 해석을 사용자 확인 |
| f-6 | 복구 작업 B 직후 SA 1명 상태의 역할 부여 교착(BOOT2 §8 Q-1 — D-ADMIN1-023은 bootstrap·하한 경보만 정함) | 확인 후보 — 복구 작업 B도 SA 2명을 만들지 |
| f-7 | 운영 홈 KPI의 TEST/LIVE 분리·UNKNOWN 제외·통화 분리(D-ADMIN-HOME-001 Coordinator 기본값) | 제안 → 사용자 확인(b-5) |
| f-8 | 제안 상태 26행(BR-ADM-009·017·022·023·024·028·036·041·046·057·060·062·063·071·072·073·101·108·111·120·121·130·131·132·137·138)은 명세 리뷰·Coordinator 판정에서 나온 규칙이다. 다수는 fail-closed 보안 판정이라 일괄 확인 질문 1건(목록 제시 + 예외만 개별 질문)으로 묶을 것을 권장 | RULE-GOV 3단계 |
| f-9 | Q-PII-5(보관·파기 기간), Q-PII-7(개인사업자 상호 마스킹), Q-PII-8(주문명·merchantOrderId 등급 [경계 PAY]), Q-PII-9(정산 지급 계좌 [경계 FIN]), Q-PII-10(내보내기·다운로드), Q-PII-3 남은 부분(KYC 외 대상) | PII §8에만 있고 `harness/questions.json`에는 Q-PII-1·4만 독립 항목(Q-PII-5·6은 다른 항목의 참조로만 등장) — 질문 등록 누락 |
| f-10 | 관리자 login_name·표시명·actorId의 정보 등급(PII §3 #17·#18 "미정") | 질문 후보 — 권장: login_name = 권한 표의 마스킹된 조회(D-ADMIN1-017과 같은 범위)로 등급 지정 |
| f-11 | 사업자등록번호 원문이 가맹점용 화면·외부 API·내보내기에 나가는지(D-PII-001 ① "생길 때 결정") | 보류 유지 — 해당 기능 착수 전 관문 항목 |
| f-12 | jOOQ DEBUG 실행 로그에 원문 사업자등록번호가 남을 수 있음(BRN1 L-4) | LIVE 전 검토 항목(로그 설정 — 업무 규칙 아님, Coordinator 판단) |
| f-13 | 데모 간편 로그인 코드 제거·유지 결정(BR-ADM-129), 운영 전환 시 관리자 세션 표 비우기 | LIVE Gate 항목 |
| f-14 | baseline 05 "SSO" 편차 기록(b-1) | 질문 아님 — DECISIONS에 편차 명시 권장 |
