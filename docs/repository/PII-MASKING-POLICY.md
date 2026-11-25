# PayCore(뉴피지) 개인정보·민감정보 마스킹 규칙

- **rev 0.1c** (2026-11-25) — SEC-BIZ-001 반영(§3 #1·#21, §4.4, §7, §8 Q-PII-6 닫힘 — §9).
- **rev 0.1** (2026-11-24) — PII-MASKING-POLICY-001 초안. 사용자 요청(2026-11-24): "개인정보 마스킹 규칙도 별도로 문서로 만들어줘. 대신 뉴피지의 내부관리자 시스템에서는 사업자번호는 마스킹 안 할게."
- 이 문서는 **새 정책을 만들지 않는다.** `docs/DECISIONS.md`의 사용자·Coordinator 결정과 각 작업 명세의 규칙을 데이터 항목별로 모은 색인이다. 결정 원문과 이 문서가 다르면 **결정 원문이 우선**하고, 이 문서를 고친다.
- 결정이 없는 칸은 **"미정(결정 없음)"**으로 적었다. 미정 항목은 추측으로 채우지 않고 §8 질문으로 올린다.
- 표기: `D-…` = DECISIONS 결정 ID, `Q-…` = harness/questions.json 질문, `C-n`·`L-n` = 해당 명세의 구현자 선택·알려진 한계. "뉴피지" = 사용자가 부르는 내부관리자 시스템(`apps/internal-admin` + 관리자 React 화면).
- 아직 사용자 검토 전이다(자기 완료 판정 아님).

## 1. 목적·적용 범위

### 1.1 목적

개인정보·카드정보·Secret·내부 식별자가 **어느 채널에 원문으로 나가도 되는지, 어디서는 가려야 하는지**를 한곳에서 확인하게 한다. 새 화면·API·감사·로그를 만들 때 이 표를 먼저 보고, 표에 없는 항목은 구현 전에 질문한다.

### 1.2 시스템

| 시스템 | 실행 단위 | 이 문서와의 관계 |
|---|---|---|
| MASTER | `apps/master-api` (MASTER DB) | 사업자·가맹점·계약·Site·MID·카드 Route 원본 보관. 관리 조회 응답을 ADMIN에 준다 |
| PAYMENT | `apps/payment-api` (PAYMENT DB) | 결제·취소·결제창·가맹점 API key 투영·Webhook. 카드번호 열 없음 |
| FINANCIAL | `apps/financial-batch` (FINANCIAL DB) | 매입·재매입·금융 사실. 카드번호 열 없음 |
| ADMIN(뉴피지) | `apps/internal-admin` (ADMIN DB) + React 화면 | 관리자 계정·역할·TOTP·세션·승인·관리자 감사. 업무 원본은 보관하지 않고 소유 서비스 API로 읽는다(D-ADMIN-005) |
| Simulator | `apps/institution-simulator` (Simulator DB, 분리) | 가상 인증·VAN·매입사. 카드번호·CVC 없이 가상 카드 참조만 쓴다 |

### 1.3 채널

| 채널 | 뜻 |
|---|---|
| 관리자 화면 | 뉴피지 React 화면(DOM·URL·브라우저 저장소 포함) |
| 조회 응답 | ADMIN API 응답, 그 값을 주는 MASTER·PAYMENT·FINANCIAL 내부 조회 응답 |
| 명령 요청 본문 | 화면→ADMIN, ADMIN→소유 서비스 서명 본문, 멱등 재전송 바이트 |
| DB 저장 | 각 소유 DB 열(요청 지문·Outbox payload 포함) |
| 감사 | ADMIN `admin_audit_event`(detail 고정 `key=value`), 소유 서비스 자체 감사 행 |
| 변경 이력 | MASTER `master_change_history` 등 업무 변경 전후 기록 |
| 애플리케이션 로그 | 구조화 로그, 예외 메시지, `toString` |
| 지표 | metric 이름·label |
| 오류 메시지 | HTTP 오류 본문, fieldErrors, 화면 오류 문구 |
| 지원 파일/내보내기 | CSV·다운로드·지원용 첨부. **현재 기능 없음**(D-TXOPS-004 — 거래 조회 CSV/내보내기 없음) |

## 2. 등급 정의

결정에 쓰인 용어를 그대로 쓴다. 등급마다 근거 결정을 붙였다.

| 등급 | 뜻 | 기본 규칙 | 근거 |
|---|---|---|---|
| **Secret** | 키·비밀번호·TOTP 시크릿·API key 원문·서명 키·기관 Credential | 비밀 저장소 주입 또는 해시·암호문 저장. 저장소·로그·이벤트·이미지·소스에 없음. 원문은 발급 때 1회만 표시 | D-MS-004, D-MS2-005, D-CPA-001, D-WH-005, D-ADMIN-001, D-OPS-001, D-ACQ-006, baseline 05 §19 |
| **카드정보** | 카드번호(PAN)·유효기간·CVC·인증 비밀번호 | PayCore는 받지 않는다(가상 카드 참조·기관 인증 근거만). 자유 문구에 카드번호 패턴이 오면 거부하는 곳이 있다(§4.4) | baseline 05 §21, 최초구현범위 §52, D-CHECKOUT-CSP-001, D-AUTHN-008 |
| **개인정보** | 대표자·담당자 이름, 전화, 이메일, 주소, 법인등록번호. 개인사업자 사업자등록번호는 "개인정보일 수 있다" | 필드 단위 접근·마스킹·암호화·목적별 보존·파기(baseline). 뉴피지 상세는 조회 역할에 원문 + 열람 감사·속도 제한, 로그·감사·이력은 마스킹 | baseline 05 §22, D-MOPS-002, D-BIZ-006 ②, D-BIZ-007 |
| **내부 식별자 등급** | Secret은 아니지만 원문을 퍼뜨리면 안 되는 식별자(기관 가맹점 참조 `institutionMerchantRef`) | 로그·지표·API 응답·예외 메시지에 원문 금지, 관리 조회는 권한 뒤 마스킹 | D-MS2-005, D-MOPS-009 ① |
| **민감 조회 대상** | 역할만으로 보여 주지 않고 별도 권한·사유·조회마다 감사가 붙는 값(마스킹 해제 데이터, 승인 근거 참조·사유 문구) | 별도 권한(`UNMASK`) + 사유 + 직전 TOTP 또는 지정 역할만 별도 조회 + 조회 감사 | D-ADMIN-002, D-ADMIN-004, D-OPS-010, D-ACQA-005, D-MOPS-008 ① |
| 일반 업무 정보 *(이 문서 분류 — 결정 용어 아님)* | 상호·금액·상태·가맹점 코드 등 | 각 화면 명세 규칙. 내부 키(`biz_`·`mer_` 등)는 화면에 표시하지 않음 | D-ADMIN-UI-004 ③, D-MCODE-003, D-ADMIN-UI-010 ③ |

## 3. 데이터 항목별 규칙 표

"현재"는 2026-11-24 main + 작업 트리 기준 구현 상태다(자세한 대조는 §7). 표의 규칙은 **결정 기준**이다.

| # | 항목 | 등급 | 관리자 화면 | 조회 응답 | 감사 detail | 변경 이력 | 로그·지표·오류 | 저장 | 원문 조회(누가·조건·감사) | 근거 결정 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **사업자등록번호** | 개인정보일 수 있음 | **뉴피지 관리자 화면은 원문(하이픈 표시)** — 사업자 목록·상세, 가맹점 목록·상세, 가맹점 등록 ① 번호 조회 결과, 사업자 등록 중복 안내 **(사용자 재확인 2026-11-24)**. 구현 완료(BRN-UNMASK-001, 2026-11-24 — 조회 응답 필드 `registrationNumber` 원문 10자리, 화면 하이픈, 계약 golden v2; 변경 이력 저장값 `registrationNumberMasked`는 마스킹 유지) | 위 화면 값을 주는 ADMIN·MASTER 조회 응답은 원문. 원문 번호는 번호 조회·등록 **요청 본문**으로 보낸다(조회 키로 쓰지 않음 — D-BIZLOOKUP-001) | 마스킹 `brn=123*******`, 형식 오류 `brn=INVALID`. 번호가 오간 모든 시도 감사 | 마스킹(`registrationNumberMasked` = 앞 3자리) | 원문 금지(로그·지표·오류에 값 없음). 번호 조회는 oracle 취급(찾음/못 찾음 같은 모양·호출 전 형식 검사) | MASTER `business` UNIQUE(D-MD-008). 요청 지문은 **MASTER 비밀 키 HMAC**(D-MOPS-010 ②·D-GOV-001 ② — **구현(SEC-BIZ-001, 2026-11-25)**: 모든 MASTER 관리 명령 지문 = `h1:` + HMAC-SHA256(fingerprint 키, canonical 전체). HMAC 전 `m1:` 무염 행은 Demo에서만 호환 비교 — MERCHANT-OPS-002 C-47 rev·L-25) | 별도 원문 조회 없음 — 화면 원문 표시로 대체(D-BIZ-007). 번호 조회 = OPERATOR·APPROVER, 매 시도 감사, 등록번호 공유 횟수 제한 버킷 유지 | D-MD-008, D-MOPS-002, D-MOPS-003, D-MOPS-006 ④, D-BIZ-003, D-BIZ-006 ①, **D-BIZ-007**, D-BIZLOOKUP-001, D-MOPS-010 ② |
| 2 | 법인등록번호 | 개인정보 | 사업자 상세·수정 화면에 원문(조회 역할). 목록 없음 | 상세 응답만 원문 | 없음 | 마스킹 `110111*******`(앞 6자리) | 원문 금지 | MASTER 원문 | 상세 열람 = OPERATOR·APPROVER, 열람마다 `BUSINESS_VIEWED` 감사 + 조회 창(속도 제한) | D-BIZ-006 ②, BUSINESS-PROFILE-001 §9 |
| 3 | 대표자·담당자 이름 | 개인정보 | 상세·수정 화면 원문. 목록은 대표자 첫 글자 + `*`(예 `홍**`, "외 N명") | 상세 응답 원문, 목록 응답 `representativeNameMasked`(SQL 마스킹) | 없음 | 마스킹 `홍**`(1글자면 `김*`) | 원문 금지 | MASTER 원문 | 2와 같음 | D-BIZ-006 ②, BUSINESS-PROFILE-001 §9·C-2 |
| 4 | 전화(사업체·담당자) | 개인정보 | 상세·수정 화면 원문. 목록 없음 | 상세 응답만 원문 | 없음 | 마스킹 `****5678` | 원문 금지 | MASTER 원문 | 2와 같음 | D-BIZ-006 ②, BUSINESS-PROFILE-001 §9·C-2 |
| 5 | 이메일(사업체·담당자) | 개인정보 | 상세·수정 화면 원문. 목록 없음 | 상세 응답만 원문 | 없음 | 마스킹 `a***@e***.com` | 원문 금지 | MASTER 원문 | 2와 같음 | D-BIZ-006 ②, BUSINESS-PROFILE-001 §9·C-2 |
| 6 | 주소 | 개인정보 | 상세·수정 화면 원문. 목록 없음 | 상세 응답만 원문 | 없음 | 우편번호 + 기본주소 앞 두 어절 + ` ***`, 상세주소 `***` | 원문 금지 | MASTER 원문 | 2와 같음 | D-BIZ-006 ②, BUSINESS-PROFILE-001 §9·C-2 |
| 7 | 상호·영문 상호·업태·종목·개업일 | 일반 업무 정보(개인사업자 상호에 이름이 들어갈 수 있음 — L-3) | 원문 | 원문(목록은 상호만) | 없음(ADMIN 감사에 상호 없음 — C-13) | 원문 | 미정(결정 없음) — 명세는 감사·로그·지표에 상호 없음(C-13) | MASTER 원문 | 해당 없음 | BUSINESS-PROFILE-001 §9·L-3 |
| 8 | 카드번호(PAN)·유효기간·CVC·인증 비밀번호 | 카드정보 | 받지도 표시하지도 않음(결제창은 카드정보를 받지 않음, Simulator는 가상 카드 참조만) | 없음. 거래 조회에 카드번호·BIN·끝 4자리 없음(PAYMENT DB에 열 없음) | 없음 | 없음 | 없음. 자유 문구에 카드번호 패턴이 오면 거부(§4.4) | 열 없음(PAYMENT·FINANCIAL·Simulator) | 없음 | baseline 05 §21, D-CHECKOUT-CSP-001, D-FE-001, D-AUTHN-008, TXN-OPS-001 §15 |
| 9 | 승인번호·기관 거래번호·카드 종류·할부 | 미정(결정 없음 — 등급 지정 없음) | 거래 **상세**(감사 대상)에서만. 매입 레코드 화면은 보여 주지 않음 | 거래 상세 응답만. 매입 레코드 응답은 SELECT하지 않음 | 상세 열람 `PAYMENT_VIEWED`(값 없음) | 해당 없음 | 미정(결정 없음) | PAYMENT·FINANCIAL 원문 | 거래 상세 = VIEW_MASKED 4역할, 상세만 감사(감사 실패 시 결과 미반환) | D-TXOPS-002, D-TXOPS-003, D-ACQV-002 |
| 10 | 결제 주문 정보(주문명·merchantOrderId·paymentId) | 미정(결정 없음) | 목록은 주문명 앞 10자만, 상세에서 주문명 전체 | 같음 | 상세 `PAYMENT_VIEWED`(target = paymentId) | 해당 없음 | 지표 label에 거래 ID 무제한 사용 금지(baseline 07 §15). PaymentExpired 등 이벤트 payload에 paymentKey·카드정보·Secret·개인정보 없음 | PAYMENT 원문 | 10과 같음(VIEW_MASKED 4역할) | D-TXOPS-001, D-TXOPS-003, D-AUTHN-008, baseline 05 §23·07 §15 |
| 11 | **기관 가맹점 참조**(`institutionMerchantRef`) | 내부 식별자 등급 | 뒤 4자리 마스킹 `****1234`(4자 이하 `****`)만, 원문 보기 없음, "정정은 종료 후 재등록". OPERATOR가 1인 Route 등록 화면에서 입력 | MASTER 관리 조회 = SQL 마스킹 값만 | `imr=PRESENT`만(ADMIN), 원문 없음 | MASTER 변경 이력 detail에만 원문 | 원문 금지(ADMIN·MASTER 로그, 지표, 오류, 예외 메시지, Outbox 통합 이벤트) | ADMIN은 저장하지 않음(서명 본문으로만 전달). MASTER `card_route_activation`·변경 이력·HMAC 서명 Snapshot(→PAYMENT)에만. 요청 지문은 원문 제외 또는 HMAC | **원문 조회 기능 없음** | D-MS2-005, D-MOPS-009 ①, D-MOPS-010 ③ |
| 12 | 기관 Credential | Secret | 없음 | 없음 | 없음 | 없음 | 없음 | Secret 저장소에만 | 없음 | D-MS2-005 |
| 13 | 가맹점 API key(`pc_test_sk_`/`pc_live_sk_`) | Secret | 미정(결정 없음 — 발급 화면 미구현, MASTER-CREDENTIAL-001) | 미정(결정 없음) | 미정(결정 없음) | 미정(결정 없음) | Secret 공통(§6) | keyId + SHA-256만. Demo 키는 DEMO 전용 일회성 명령이 TEST 키 1개를 기록(유효 7일, 갱신 없음) | 없음 | D-CPA-001, D-DEMO-002, D-DEMO-004 |
| 14 | Webhook 서명 비밀(`whsec_`) | Secret | 원문은 **발급 때 1회만 표시**(화면 미구현 — WEBHOOK-ENDPOINT-001) | 미정(결정 없음) | 폐기는 사유·행위자 append-only | 미정(결정 없음) | Secret 공통(§6) | PAYMENT가 비밀 저장소 주입 관리 키로 봉투 암호화 보관. ADMIN 전에는 TEST 키 설정만 | 없음(1회 표시 뒤 다시 보지 않음) | D-WH-003, D-WH-005 |
| 15 | 내부 채널 서명 키(MASTER→PAYMENT 권리, ADMIN→PAYMENT·MASTER·FINANCIAL, 매입 SFTP 키) | Secret | 없음 | 없음 | 없음 | 없음 | 없음(저장소·로그에 없음) | 비밀 저장소·환경변수/파일 주입, 교체용 2키 | 없음 | D-MS-004, D-OPS-001, D-MOPS-005, D-ACQA-001, D-ACQ-006 |
| 16 | 관리자 비밀번호·TOTP 시크릿·등록 코드·세션 ID·CSRF 토큰 | Secret | 등록 코드는 1회 표시(별도 채널로 본인 전달) | 없음 | 없음(감사에 비밀번호·코드·시크릿·세션 ID·CSRF 토큰 없음) | 없음 | 없음 | 비밀번호 해시(BCrypt), TOTP 시크릿 암호화 저장(키는 비밀 저장소) | 없음 | D-ADMIN-001, D-ADMIN-004, D-ADMIN1-003, D-ADMIN1-009, ADMIN-BOOT-001 §4.6 |
| 17 | 관리자 `login_name` | 미정(결정 없음 — "권한 표의 마스킹된 조회"로 취급) | **SECURITY_ADMIN에게만**. 그 밖 역할은 표시명·actorId·상태·역할만. 승인 목록·상세 대상 스냅샷에서도 제외 | 같음(그 밖 역할 응답에 열 자체 없음) | 미인증 시도의 login_name은 **비밀 키 HMAC-SHA256** | 해당 없음 | 미정(결정 없음) | ADMIN `admin_account` UNIQUE, 변경 기능 없음 | SECURITY_ADMIN 역할 조회만 | D-ADMIN1-017, D-ADMIN1-020, ADMIN-BOOT-001 n-2·D-30 |
| 18 | 관리자 표시명·actorId(운영자 신원) | 미정(결정 없음) | 뉴피지 화면·감사에 표시(기록 시점 표시명 스냅샷) | 가맹점 대상 조회·Webhook에는 운영자 신원·사유·approvalId 비노출(`initiator: OPERATOR`만) | actorId + 표시명 스냅샷 | actorRef | 미정(결정 없음) | ADMIN 원문 | 해당 없음 | D-ADMIN-004, D-CPQ-002, D-TXOPS-005 |
| 19 | 근거 참조(보정 취소 `purposeReference`, 재매입·가맹점 승인 `evidenceRef`) | 민감 조회 대상 | 승인 상세에서 **APPROVER와 요청자 본인만** 별도 조회로 보임. 목록·VIEWER·그 밖 역할에는 숨김 | 별도 조회 응답만 | 조회마다 `APPROVAL_JUSTIFICATION_VIEWED`(detail 없음) | MASTER 승인 근거 행·지문에 포함(가맹점 승인) | 원문 금지. 카드번호 패턴 거부(§4.4) | ADMIN 명령 행, 소유 서비스 감사·근거 행 | APPROVER 또는 요청자 본인, ACTIVE, 조회 속도 제한, 조회마다 감사 | D-OPS-004, D-OPS-010, D-ACQA-005, D-MOPS-008 ① |
| 20 | 사유 문구 — 운영자 보정 취소·재매입 | 민감 조회 대상 | 보정 취소 사유: 19와 같이 APPROVER·요청자 본인만. 재매입 사유: ADMIN에만 저장, 화면·감사에 표시하지 않음 | 같음 | 없음(자유 문구는 감사 detail 불가) | 해당 없음 | 원문 금지. 카드번호 패턴 거부 | ADMIN 명령 행 | 19와 같음(보정 취소). 재매입 사유는 조회 없음 | D-OPS-010, D-ACQA-005, ADMIN-PAYMENT-OPS-001 R-12 |
| 21 | 사유 문구 — 사업자 '사유 및 비고'(중요정보 변경 사유, 공통 칸) | 자유 문구(운영자가 개인정보를 적을 수 있음, L-4). **카드번호 패턴은 입력 거부**(D-FLOW-007 ① — 칸 전체에 항상, BR-BIZ-305, §4.4) | 사업자 이력 탭에 원문(조회 역할). 입력 칸 안내 "개인정보를 적지 마세요", 카드번호 패턴이면 저장 전 안내 | 이력 응답 원문 | `reason=PRESENT`/`ABSENT`만 | MASTER 이력 원문(1~200자) — 카드번호 패턴은 저장되지 않음(새 저장부터, 기존 이력 행 소급 없음) | 원문 금지. 거부 오류는 고정 code `CARD_NUMBER_PATTERN`만(값 없음) | MASTER `master_change_history`. 요청 지문에는 HMAC으로만(#1 저장 칸) | 사업자 이력 = OPERATOR·APPROVER | BUSINESS-PROFILE-001 C-13·L-4, **D-FLOW-007 ①**(Q-PII-6 닫음), 구현 SEC-BIZ-001 |
| 22 | 사유 문구 — 마스킹 해제 | 민감 조회 대상 | 해당 없음(입력만) | 없음 | **원문 저장**(길이 상한 500, 제어 문자 거부). 자유 문구 `reason`은 `UNMASK_REQUESTED` 행에만 허용 | 해당 없음 | 원문 금지 | ADMIN 감사 | 감사 조회 권한(SECURITY_ADMIN·APPROVER 전체, VIEWER·OPERATOR 자기 기록) | D-ADMIN-002, D-ADMIN1-019, D-ADMIN1-008 |
| 23 | 결제창 토큰·인증 결과 참조 | 미정(결정 없음 — 토큰 원문 저장 금지만 결정) | 해당 없음 | 해당 없음 | 미정(결정 없음) | 해당 없음 | 미정(결정 없음) | DB에는 hash만(256bit 난수, 1회 교환) | 없음 | D-AUTHN-001, D-AUTHN-003 |
| 24 | 정산 지급 계좌 | 개인정보·계좌(baseline) | **미정(결정 없음)** — 미구현(MASTER-PAYOUT-ACCOUNT-001 BACKLOG) | 미정 | 미정 | 미정 | 미정 | 미정(baseline: 필드 단위 접근·마스킹·암호화) | 미정. 변경은 2인 승인 대상으로만 결정됨 | D-ADMIN-003, baseline 05 §22 |
| 25 | 내부 키(`biz_`·`mer_`·`site_`·`ctr_`·`csa_`·`act_`·`apr_` 등) | 일반 업무 정보 | 화면 미표시(사람용 값으로 대체), URL·브라우저 저장소에도 없음 | 응답에 포함 가능(화면 미표시, 요청 본문에만 사용) | targetRef에 허용 | 내부 키 | 해당 없음 | 각 소유 DB | 해당 없음 | D-ADMIN-UI-004 ③, D-BIZLOOKUP-001, D-MCODE-003, D-ADMIN-UI-010 ③ |

표 행 수: **25행**.

## 4. 마스킹 형식

실제로 결정·명세·코드에 쓰이는 형식만 적는다. 새 형식은 만들지 않는다.

### 4.1 사업자·개인정보(MASTER `BusinessPiiMask`·`MasterSqlText`, ADMIN `BusinessAudit`)

| 대상 | 형식 | 예 | 쓰는 곳 |
|---|---|---|---|
| 사업자등록번호 | 앞 3자리 + `*` 7개 | `123*******` | 감사 detail `brn=123*******`, 변경 이력 `registrationNumberMasked`. (D-BIZ-007 구현 전까지는 화면·조회 응답도) |
| 사업자등록번호 형식 오류 | 고정 값 | `brn=INVALID` | ADMIN 감사 |
| 법인등록번호 | 앞 6자리 + `*` | `110111*******` | 변경 이력, `toString` |
| 사람 이름 | 첫 글자(code point) + 나머지 `*`, 1글자면 `김*` | `홍**` | 목록 대표자(SQL), 변경 이력 |
| 이메일 | local 첫 글자 + `***@` + domain 첫 라벨 첫 글자 + `***.` + 마지막 라벨 | `a***@e***.com` | 변경 이력 |
| 전화 | `****` + 끝 4자리 | `****5678` | 변경 이력 |
| 기본주소 / 상세주소 | 앞 두 어절 + ` ***` / 전부 `***` | `서울특별시 중구 ***` / `***` | 변경 이력 |

### 4.2 기관 가맹점 참조

- 뒤 4자리 + 앞 `****`(`****1234`), 4자 이하이면 `****`. MASTER SQL 마스킹(D-MOPS-009 ①). 감사는 값 대신 `imr=PRESENT`.

### 4.3 존재 여부만 남기는 형식

- `imr=PRESENT`(기관 가맹점 참조), `reason=PRESENT|ABSENT`(사업자 변경 사유), `stepUp=DEMO_SKIPPED;method=DEMO_QUICK`(데모 재인증 생략 — §5.3).
- 값 객체 `toString`은 가림: `InstitutionMerchantRef(****)`, `BusinessChangeReason(***)`, `SigningKey(keyId=…, secret=****)`, 검색 조건 `merchantOrderId=****` 등.

### 4.4 카드번호 패턴 거부(마스킹이 아니라 입력 거부)

- 판정: 십진 숫자(`\p{Nd}` — 전각·아랍-인도 숫자 포함) 13~19자리, 숫자 사이 공백 또는 하이픈 1개 허용, 앞뒤가 숫자가 아님, Luhn 검사 없음(오탐을 받아들이고 거부 쪽).
- 적용: 운영자 보정 취소 근거·사유(ADMIN-PAYMENT-OPS-001 I-17·R-12), 재매입 근거 참조(ADMIN + FINANCIAL 이중), 가맹점 승인 근거 참조(MERCHANT-OPS-002), **사업자 '사유 및 비고'(중요정보 변경 사유 — D-FLOW-007 ①, BR-BIZ-305; 화면 `reasonError` + ADMIN `BusinessProfileInputRules`(MASTER 호출 전 400) + MASTER Domain `BusinessChangeReason` 세 계층, 구현 SEC-BIZ-001)**. 오류 응답은 고정 code `CARD_NUMBER_PATTERN`만, 값은 넣지 않는다.
- 판정 정본은 ADMIN `OperatorCancelInputRules.containsCardNumber` 하나다. 다른 곳은 모듈 경계를 넘지 않게 같은 식을 복제하고 공통 시험 벡터(양성 10·음성 6)로 묶는다(MASTER `CardNumberVectors`, ADMIN `BusinessCardNumberVectors`·`OperatorCancelInputRulesSpec`, FINANCIAL `ReacquisitionCommandParserSpec`, 화면 `lib/businessProfile.test.ts`). FINANCIAL `CardNumberPattern`의 정규식은 정본과 같은 문자열이다(2026-11-25 대조).
- 적용하지 않는 곳: 마스킹 해제 사유(D-ADMIN1-019 — "원문 유지 + 마스킹 해제 첫 적용 후속 작업에서 PAN 패턴 거부 재검토"; D-FLOW-005 ⑧과 함께 SEC-BIZ-001 범위 밖).

## 5. 원문 조회 절차

### 5.1 마스킹 해제(UNMASK) — 결정

- 권한: 고정 역할과 별개인 **별도 권한**(`UNMASK`) — "민감 조회 분리"(D-ADMIN-002). 부여는 `ROLE_GRANT`와 같은 2인 승인(ADMIN-BOOT-001 §4.5).
- 사유: 필수, 기록(D-ADMIN-002). 원문을 감사에 저장(길이 상한 — D-ADMIN1-019).
- 재인증: **마스킹 해제 직전 TOTP 재입력**(D-ADMIN-004).
- 감사: 성공·권한 없음 모두 1행(`UNMASK_REQUESTED`). 감사 실패면 행위 실패.
- 대량 다운로드: 마스킹 해제 데이터 대량 다운로드는 2인 승인 대상(D-ADMIN-003). 다운로드 기능 자체는 없음.
- 현재 상태: **틀만 있음**(ADMIN-BOOT-001 AC10 — 대상 데이터 공급 Port 없음, 통과해도 `NoDataSource`). 첫 실제 적용 데이터는 정해지지 않았다(§8 Q-PII-3).

### 5.2 지정 역할 별도 조회(근거 참조·사유 문구)

- 운영자 보정 취소 승인의 근거 참조·사유 문구, 재매입·가맹점 승인의 근거 참조: **APPROVER와 요청자 본인만**, 계정 ACTIVE, 별도 조회 endpoint, 조회자별 분 단위 속도 제한, 조회마다 `APPROVAL_JUSTIFICATION_VIEWED` 감사(감사 실패면 문구를 돌려주지 않음). 목록·그 밖 역할에는 숨김(D-OPS-010, D-ACQA-005, D-MOPS-008 ①).

### 5.3 데모 간편 로그인 세션의 재인증 생략 — **DEMO 한정**

- D-ADMIN-AUTH-DEMO-001 ③: 승인·반려·**마스킹 해제 직전 TOTP 재인증**을 데모에서 생략한다. 조건은 `deployment-mode=DEMO` **그리고** 명시 설정이 켜져 있고, **간편 로그인으로 만든 세션**일 때만. 감사에 생략 사실을 기록하고(`STEP_UP_SKIPPED`·`DEMO_SKIPPED`), 2인 승인(요청자≠승인자)은 그대로다.
- LIVE에서 설정이 켜져 있으면 기동 거부. **운영 준비 전 반드시 꺼야 하는 임시 기능**이다. 운영 규칙은 §5.1(TOTP 재입력) 그대로다.
- 상태: ADMIN-DEMO-LOGIN-001 IN_PROGRESS(작업 트리 변경, 미커밋 — 리뷰·검증 전).

### 5.4 원문을 보는 그 밖의 경로

| 경로 | 누가 | 통제 | 근거 |
|---|---|---|---|
| 사업자 상세 개인정보(이름·전화·이메일·주소·법인등록번호) | OPERATOR·APPROVER | 상세 열람마다 `BUSINESS_VIEWED` 감사 + 조회 창(Demo 30/분, 운영값 아님) | D-BIZ-006 ①② |
| 사업자등록번호(뉴피지 화면) | 사업자·가맹점 조회 역할 | 번호 조회 매 시도 감사·공유 버킷, 상세 열람 감사(가맹점 `MERCHANT_VIEWED`·사업자 `BUSINESS_VIEWED`) | D-BIZ-007, D-MOPS-002, D-MOPS-003 |
| 거래 상세(주문명 전체·승인번호·기관 거래번호) | VIEW_MASKED 4역할 | 상세만 `PAYMENT_VIEWED` 감사 | D-TXOPS-002, D-TXOPS-003 |
| 관리자 login_name | SECURITY_ADMIN | 역할 조회 | D-ADMIN1-017 |

## 6. 로그·감사 공통 규칙

1. **로그·이벤트·지표에서 카드·개인정보·Secret을 제외한다.** paymentId 등 고유 거래 ID를 metric label로 무제한 쓰지 않는다(TECHNOLOGY-AND-PRINCIPLES, baseline 07 §15).
2. 구조화 로그는 진단용, 감사는 행위 증거용, 금융 기록은 금액 정합성용 — 섞지 않는다. 필수 감사를 샘플링·유실 가능한 로그로 대체하지 않는다(baseline 07 §14).
3. ADMIN 감사 detail은 **고정 `key=value` 요약만**(값 문자 `[A-Za-z0-9_:.,*-]`, 한글·공백 불가). 자유 문구 `reason`은 마스킹 해제 행에만(D-ADMIN1-019, ADMIN-BOOT-001).
4. 감사에 비밀번호·등록 코드·TOTP 시크릿·세션 ID·CSRF 토큰을 넣지 않는다. 미인증 login_name은 비밀 키 HMAC(ADMIN-BOOT-001 n-2, LIVE에서 Demo 키면 기동 실패).
5. 감사 쓰기가 실패하면 행위(또는 조회 결과 반환)도 실패한다(fail-closed).
6. 오류 응답·fieldErrors·예외 메시지에 입력 값을 넣지 않는다(고정 code만 — D-MOPS-003, D-MS2-005, I-111).
7. 변경 이력에는 **저장 시점에 마스킹한 값만** 쓴다(원문 이력 없음 — 사유 문구·기관 가맹점 참조는 예외로 MASTER 이력에 원문, §3 #11·#21).
8. Outbox·통합 이벤트 payload에 카드정보·Secret·개인정보·기관 계약계정을 싣지 않는다(D-AUTHN-008, D-MS2-005, FINANCIAL V2 주석).
9. 검색·다운로드·원문 접근은 별도 감사한다(baseline 05 §23). 감사 조회 자체도 감사한다(D-ADMIN1-008).
10. 화면: URL·`location`·`sessionStorage`·`localStorage`에 원문 번호·내부 키를 두지 않는다(BUSINESS-PROFILE-001 §8, MERCHANT-IA-001).
11. 백업과 감사 저장소도 같은 통제 대상이다(baseline 05 §22). 보관·파기 기간은 미정(§8 Q-PII-5).

## 7. 현재 구현 대조

기준: 2026-11-24 main `d834006` + 작업 트리(다른 구현자 변경 중). 줄 번호는 조사 시점 값.

### 7.1 불일치 / 미구현 (결정과 다름)

| # | 항목 | 결정 | 현재 구현 | 위치 | 후속 |
|---|---|---|---|---|---|
| X-1 | 사업자등록번호 화면·조회 응답 원문 | D-BIZ-007(사용자 재확인 2026-11-24): 뉴피지 화면·ADMIN·MASTER 조회 응답 원문(하이픈) | MASTER가 SQL에서 앞 3자리 마스킹해 응답, ADMIN 응답 모양 검사가 마스킹 형식만 허용, 화면은 마스킹 값 표시 | `apps/master-api/.../adapter/query/MasterSqlText.kt:31`(사용처 `JooqBusinessDirectoryReader.kt:49`, `JooqBusinessDetailReader.kt:35·90`, `JooqBusinessLookupReader.kt:21`, `JooqMerchantDetailReader.kt:58`, `JooqMerchantDirectoryReader.kt:47`), `apps/internal-admin/.../merchantops/adapter/integration/MasterResponseShape.kt:86`, 화면 `BusinessesScreen.tsx:445·454·489`, `BusinessProfileFormScreen.tsx:269·339·388`, `lib/historyTarget.ts:46` | **BRN-UNMASK-001**(READY). 감사 `brn=123*******`(`BusinessAudit.kt:56-57`, `MerchantLookupService.kt:79`)·이력 마스킹은 유지해야 함  **해소(2026-11-24, BRN-UNMASK-001)** — 남은 위험: jOOQ DEBUG 실행 로그에 결과 행 원문(L-4, LIVE 전 검토), VIEWER·SECURITY_ADMIN 목록 열람 감사 없음(L-5, D-PII-001 범위) |
| X-2 | 사업자 명령 요청 지문 | D-MOPS-010 ②: 사업자등록번호 원문 무염 SHA-256 → MASTER 비밀 키 HMAC. D-GOV-001 ②: 다른 관리 명령 지문의 개인정보 필드도 전부 HMAC | 모든 MASTER 관리 명령이 `m1:` 무염 SHA-256(번호·개인정보 포함 필드) | `apps/master-api/.../application/AdminCommandExecutor.kt:428` | **해소(2026-11-25, SEC-BIZ-001 — 리뷰·검증 전)**: 지문 전체 `h1:` HMAC-SHA256(`RequestFingerprintDigester` → `HmacRequestFingerprintDigester`, 키 `paycore.master.admin-channel.request-fingerprint-key` — 기본값 없음·32바이트 이상·서명 키와 다름·LIVE Demo 키 거부). `m1:` 행 호환 비교는 Demo만(L-25). 남은 LIVE Gate: 키 보관·교체 절차(L-23) |
| X-3 | 기관 가맹점 참조 관리 조회 `****1234` | D-MOPS-009 ① | 미구현(B3-3 대기). 현재 MASTER 상세는 그 열을 SELECT하지 않아 **아예 미노출**(결정보다 엄격 — 위반 아님) | `JooqMerchantDetailReader.kt:47`, `MerchantDetailView.kt:95`, 값 객체 가림 `InstitutionRefs.kt:24` | MERCHANT-OPS-002 B3-3 |
| X-4 | 마스킹 해제 실제 적용 | D-ADMIN-002/004: 별도 권한 + 사유 + 직전 TOTP | 틀만(권한·사유·TOTP·감사), 데이터 공급 없음 → `NoDataSource` | `apps/internal-admin/.../account/application/UnmaskService.kt:31-68`, `UnmaskController.kt:22` | 첫 적용 대상 미정(§8 Q-PII-3). 그때 PAN 패턴 거부 재검토(D-ADMIN1-019) |
| X-5 | 데모 재인증 생략 | D-ADMIN-AUTH-DEMO-001 ③ | 작업 트리에 구현 중(미커밋): 간편 로그인 세션 + 켜짐이면 TOTP 생략, 감사 `STEP_UP_SKIPPED` | `AdminOwnedCommandService.kt:92-104`, `UnmaskService.kt:35-37` | ADMIN-DEMO-LOGIN-001 리뷰·검증 |
| X-6 | 가맹점 API key 발급·전파 | D-CPA-001 | 미구현(MASTER-CREDENTIAL-001 BACKLOG). 저장 형식(keyId + SHA-256)은 구현됨, Demo 키는 D-DEMO-002 예외 경로 | `apps/payment-api/src/main/resources/db/migration/payment/V5__merchant_api_credential.sql:6·13` | MASTER-CREDENTIAL-001 |
| X-7 | Webhook 비밀 발급·봉투 암호화·1회 표시 | D-WH-005 | 미구현(WEBHOOK-ENDPOINT-001 BACKLOG). TEST 키 설정만 | `apps/payment-api/.../adapter/integration/MerchantWebhookSettings.kt` | WEBHOOK-ENDPOINT-001 |
| X-8 | 정산 지급 계좌 | 결정 없음(2인 승인 대상만) | 미구현 | — | MASTER-PAYOUT-ACCOUNT-001(착수 전 계좌 보안 정책 질문) |

### 7.2 일치 (주요 근거 코드)

| 항목 | 결정 | 위치 |
|---|---|---|
| 사업자 개인정보 마스킹 형식 | D-BIZ-006 ②, C-2 | `apps/master-api/.../merchant/domain/BusinessPiiMask.kt:9-33`, `CorporateRegistrationNumber.kt:13-15`, `BusinessRegistrationNumber.kt:15-17`, SQL 이름 마스킹 `MasterSqlText.kt:37-38` |
| 감사 사업자등록번호 마스킹·INVALID | D-MOPS-003, D-MOPS-006 ④ | `apps/internal-admin/.../merchantops/application/BusinessAudit.kt:56-57`, `MerchantLookupService.kt:79`, `BusinessLookupService.kt:39·63` |
| 변경 사유는 감사에 존재 여부만 | C-13 | `BusinessProfileService.kt`(ADMIN) `:226-227` |
| 감사 detail 고정 형식·자유 문구는 UNMASK만 | ADMIN-BOOT-001, D-ADMIN1-019 | `apps/internal-admin/.../audit/domain/AdminAuditEvent.kt:43-46·68` |
| 마스킹 해제 사유 원문·길이 상한·PAN 미검사 | D-ADMIN1-019 | `UnmaskService.kt:32-33·61·74` |
| login_name SECURITY_ADMIN만 | D-ADMIN1-017 | `AccountQueryService.kt:25-27`, `JooqAdminAccountListReader.kt:14-15`, 화면 `AccountsScreen.tsx:121·276` |
| 승인 대상 스냅샷 login_name 미저장 | D-ADMIN1-020 | `ApprovalService.kt:440` |
| 미인증 login_name HMAC·LIVE Demo 키 거부 | ADMIN-BOOT-001 n-2 | `AccountSecurityConfiguration.kt:93`, `LiveModeStartupGuard.kt:28` |
| 근거 참조 별도 조회(APPROVER·요청자, 감사, 속도 제한) | D-OPS-010, D-ACQA-005, D-MOPS-008 ① | `apps/internal-admin/.../approval/application/OperatorCancelJustificationService.kt:47-75·112` |
| 카드번호 패턴 거부 | ADMIN-PAYMENT-OPS-001 I-17, ACQ-ADMIN-001, D-FLOW-007 ① | `OperatorCancelInputRules.kt:15·30·47·51`, `MerchantOpsInputRules.kt:85-88`, `ReacquisitionInputRules.kt:19`, FINANCIAL `CardNumberPattern.kt`·`ReacquisitionExecutionRule.kt:131-132`, 화면 `ReacquisitionRequest.tsx:40`; 사업자 사유(SEC-BIZ-001): MASTER `domain/CardNumberPattern.kt`·`BusinessChangeReason.kt`, ADMIN `BusinessProfileInputRules.kt`(`reason`), 화면 `lib/businessProfile.ts`(`reasonError`) |
| 관리 명령 요청 지문 HMAC·LIVE Demo 키 거부 | D-MOPS-010 ②, D-GOV-001 ② | MASTER `AdminCommandExecutor.kt`(`fingerprintOf`·`legacyFingerprintOf`), `application/required/RequestFingerprintDigester.kt`, `adapter/integration/HmacRequestFingerprintDigester.kt`·`RequestFingerprintConfiguration.kt` |
| 기관 가맹점 참조 toString·응답 비노출 | D-MS2-005 | `InstitutionRefs.kt:24`, `MerchantOpsQuery.kt:13`, PAYMENT `AuthorizationTermsQuery.kt:52`, ADMIN `MasterResponseShape.kt:10` |
| 카드번호 열 없음(가상 카드 참조) | baseline 05 §21 | Simulator `V1__authentication.sql:1`, `V2__van.sql:2`, FINANCIAL `V2__payment_fact_inbox.sql:60` |
| TOTP 시크릿 암호문 저장 | D-ADMIN-001 | ADMIN `V1__admin_core.sql:23` |
| 상세 열람 감사 | D-MOPS-002, D-BIZ-006 ②, D-TXOPS-002, D-ACQV-001 | `MerchantLookupService.kt:225`, `BusinessQueryService.kt:112`, `TransactionLookupService.kt:189`, `AcquisitionLookupService.kt:181` |
| 검색 조건 toString 가림 | (관례) | `TransactionLookupUseCase.kt:37-40` |

### 7.3 결정 없음 — 현재 구현이 정한 동작(확인 필요)

- ~~사업자 중요정보 변경 사유: 카드번호 패턴 검사 없음~~ → D-FLOW-007 ①로 결정·SEC-BIZ-001로 구현(§4.4). 이력 탭 원문(L-4)은 그대로.
- 상호: 마스킹 없음(L-3) → §8 Q-PII-7.

## 8. 미결·사용자 확인 질문

규칙을 추측으로 만들지 않고 질문으로 남긴다. 답이 오기 전에는 해당 운영 동작을 새로 구현하지 않는다(CLAUDE.md). 각 질문의 권장안은 Coordinator가 사용자에게 물을 때의 제안일 뿐 결정이 아니다.

| ID | 질문 | 권장안 | 대안 | 영향 / 막히는 작업 |
|---|---|---|---|---|
| Q-PII-1 | 사업자등록번호 원문 표시가 **뉴피지 관리자 화면**에 한정되는지. 앞으로 생길 가맹점용 화면·외부 API·내보내기에서는? | 뉴피지(내부관리자)만 원문, 그 밖 채널은 생길 때 따로 결정 | 모든 내부·가맹점 화면 원문 | 이 문서 §3 #1 범위, 후속 가맹점 포털 |
| Q-PII-2 | D-BIZ-007로 화면이 원문이 되면 보류 중인 Q-MOPS-04(사업자등록번호 UNMASK 필요 여부)를 "필요 없음"으로 닫을지 | 닫는다(D-BIZ-007이 대체) | 유지 | harness/questions.json 정리, BRN-UNMASK-001 |
| Q-PII-3 | 마스킹 해제(UNMASK) 틀의 **첫 실제 적용 데이터**는 무엇인지(D-BIZ-006 ②로 사업자 개인정보는 이미 원문, D-BIZ-007로 등록번호도 원문) | 실제 필요가 생길 때까지 보류(정산 지급 계좌 등 후보) | 지금 대상 지정 | UNMASK 데이터 Port, D-ADMIN1-019 PAN 재검토 시점 |
| Q-PII-4 | 등록번호가 원문이 되면 **목록 조회**(사업자·가맹점 목록)에도 원문 번호가 나오는데 목록은 감사하지 않는다(D-MOPS-002·D-BIZ-006 상세만). 목록 열람 감사를 추가할지 | 현행 유지(D-BIZ-007 "조회 감사 유지" = 기존 범위) | 목록 조회도 감사 | BRN-UNMASK-001 |
| Q-PII-5 | 개인정보 보관·파기 기간(종료 행·이력·감사·백업) | 컴플라이언스 확인 후 결정, 그 전까지 파기 기능 없음(BUSINESS-PROFILE-001 L-3·POL-006 TBD 유지) | 임시 기간 지정 | LIVE Gate |
| Q-PII-6 | 사업자 중요정보 변경 사유(MASTER 이력 원문)에 카드번호 패턴 거부를 적용할지 | 적용(보정 취소 사유와 같은 규칙) | 화면 안내만 유지(현재) | BUSINESS-PROFILE 후속 |
| Q-PII-7 | 개인사업자 상호(개인 이름 포함 가능)를 계속 마스킹하지 않을지 | 현행 유지(L-3) | 감사·로그에만 마스킹 | 낮음 |
| Q-PII-8 | 주문명·merchantOrderId(가맹점 자유 텍스트) 등급: 로그·오류 규칙 | 개인정보가 들어갈 수 있는 값으로 보고 로그·지표·오류에 원문 금지 | 일반 업무 정보 | PAYMENT 로그 규칙 |
| Q-PII-9 | 정산 지급 계좌의 화면 마스킹 형식·원문 조회 절차·저장(암호화) | MASTER-PAYOUT-ACCOUNT-001 착수 때 질문(이 문서에서 정하지 않음) | — | MASTER-PAYOUT-ACCOUNT-001 |
| Q-PII-10 | 내보내기·다운로드(CSV 등) 규칙 — 현재 기능 없음(D-TXOPS-004), 마스킹 해제 데이터 대량 다운로드만 2인 승인(D-ADMIN-003) | 기능이 생길 때 별도 결정 | — | 후속 |
| Q-PII-11 | 가맹점 등록 마법사의 사업자 표시 "사업자: <상호> · <마스킹 값>"(D-MCODE-003 ②)도 D-BIZ-007로 원문이 되는지 | 원문(D-BIZ-007이 가맹점 등록 ① 화면을 포함) | 마스킹 유지 | BRN-UNMASK-001 |

**답(2026-11-24, D-PII-001)**: Q-PII-1 → 뉴피지 관리자 **전체**(가맹점 등록 마법사·변경 이력 대상 표시 포함; 가맹점용 화면·외부 API·내보내기는 생길 때 결정). Q-PII-11 → 원문(Q-PII-1 답에 포함). Q-PII-4 → 목록 조회는 감사하지 않음(상세 열람·번호 조회 감사 유지). Q-PII-2(Q-MOPS-04) → 닫음(D-BIZ-007로 대체). 나머지 Q-PII-3·5~10은 미결.

**답(2026-11-25, D-FLOW-007 ①)**: Q-PII-6 → **적용**(공통 '사유 및 비고' 칸 전체에 항상 카드번호 패턴 검사, 기존 사업자 중요정보 사유 포함) — 닫음. 구현 SEC-BIZ-001(§4.4).

## 9. 개정 이력

| rev | 날짜 | 작성 | 내용 |
|---|---|---|---|
| 0.1 | 2026-11-24 | Implementer(PII-MASKING-POLICY-001, 문서만 — 코드·tasks·DECISIONS 변경 없음, 자기 완료 판정 아님) | 최초 작성. DECISIONS 결정 통합(§3 25행), 마스킹 형식, 원문 조회 절차(UNMASK·지정 역할 별도 조회·데모 재인증 생략 DEMO 한정), 로그·감사 공통 규칙, 현재 구현 대조(불일치·미구현 8건), 미결 질문 11건. 사용자 재확인(2026-11-24) "뉴피지 내부관리자 시스템에서는 사업자번호 마스킹 안 함" = D-BIZ-007 그대로 반영 |
| 0.1a | 2026-11-24 | Coordinator | §8 Q-PII-1·2·4·11 사용자 답 반영(D-PII-001) |
| 0.1b | 2026-11-24 | Coordinator | §3 #1·§7 X-1 구현 완료 반영(BRN-UNMASK-001) |
| 0.1c | 2026-11-25 | Implementer(SEC-BIZ-001 — 결정 기록만, 새 정책 없음. 자기 완료 판정 아님) | D-FLOW-007 ①: §3 #21 카드번호 패턴 거부·§4.4 적용 목록(사업자 '사유 및 비고', 세 계층·공통 벡터·FINANCIAL 정규식 대조 일치)·§7.3 해소·§8 Q-PII-6 닫음. D-MOPS-010 ②·D-GOV-001 ②: §3 #1 저장 칸·§7.1 X-2 해소(지문 전체 `h1:` HMAC, m1 Demo 한정 호환)·§7.2 행 추가 |
