# BRN-UNMASK-001 관리자 화면·조회 응답 사업자등록번호 원문 표시 (rev 0.2)

- **rev 0.1** (2026-11-24, Implementer — 명세 + TDD 구현. 자기 완료 판정 아님 — Verifier·Reviewer 별도).
- **rev 0.2** (2026-11-24, Implementer — 독립 리뷰 REVIEW_PASS(조건부, MAJOR 0) 반영 + Coordinator 결정 M-1(지금 이름 변경): 응답 필드 `registrationNumber`·v2 golden, M-2 결과 toString 마스킹, M-3·N-2~N-4, 위험 L-4·L-5, 배포 절차 §6. 기준 main `248c8bd`(ADMIN-DEMO-LOGIN-001 커밋 뒤)).
- rev 0.1 기준: main `765a8bc` + 다른 작업(ADMIN-DEMO-LOGIN-001)의 미커밋 변경(이 작업은 그 파일을 건드리지 않는다).
- 근거(사용자 결정): **D-BIZ-007**(2026-11-19 — 화면·조회 응답만 원문), **D-PII-001**(2026-11-24 — ① 범위 = 뉴피지 관리자 전체, ② 목록 조회 감사 추가 없음, ③ Q-MOPS-04 대체).
  규칙 색인 `docs/repository/PII-MASKING-POLICY.md` §3 #1·§4.1·§5.4·§6 #10·§7 X-1. 관련: D-MD-008, D-BIZ-003, D-BIZ-006 ②, D-MOPS-003, D-MOPS-006 ④, D-MCODE-003 ②, D-BIZLOOKUP-001.
- 근거 문서 hash(sha256): `docs/DECISIONS.md` `76c83dbbf6853923888cdfd6b6cd44377764f8c628b386870e143c63b5e8e93a`, `docs/repository/PII-MASKING-POLICY.md` `fd57b63f58a4bd6f56b74b363c08084d80da9a0f3f1516585022be8a77349aeb`.
- 표기: C-n = 구현자 가역 선택(저장소 관례), L-n = 알려진 한계.

## 1. 범위 (D-PII-001 ①)

| # | 화면/응답 | 지금 | 바꾼 뒤 | 위치 |
|---|---|---|---|---|
| 1 | 사업자 목록(검색 결과) | `123*******` | `123-45-67890` | `BusinessesScreen.tsx` 목록 표 |
| 2 | 사업자등록번호 조회 결과(사업자 목록의 번호 조회) | 마스킹 | 원문(하이픈) | `BusinessesScreen.tsx` 조회 결과 표 |
| 3 | 사업자 상세 "사업자등록번호" | 마스킹 | 원문(하이픈) | `BusinessesScreen.tsx` DetailInfo |
| 4 | 사업자 수정 화면 머리 설명·번호 읽기 전용 칸 | 마스킹 | 원문(하이픈) | `BusinessProfileFormScreen.tsx` |
| 5 | 사업자 등록 중복 안내(중복 확인 결과·저장 시 중복 거절 `existing`) | `상호 · 마스킹` | `상호 · 원문(하이픈)` | `BusinessProfileFormScreen.tsx` |
| 6 | 가맹점 목록 열 "사업자등록번호(마스킹)" | 라벨·값 마스킹 | 라벨 "사업자등록번호", 값 원문(하이픈) | `MerchantsScreen.tsx` |
| 7 | 가맹점 상세 기본정보 "사업자등록번호(마스킹)" | 라벨·값 마스킹 | 라벨 "사업자등록번호", 값 원문(하이픈) | `MerchantsScreen.tsx` |
| 8 | 가맹점 등록 마법사 "사업자: 상호 · 번호"(① 번호 조회 결과·고른 사업자·계약 당사자·등록 결과) | 마스킹 | 원문(하이픈) | `MerchantRegistrationScreen.tsx`, `lib/registration.ts` `BusinessRef` |
| 9 | 가맹점 변경 이력 "대상"(그 가맹점의 사업자 = "상호 · 번호") | 마스킹 | 원문(하이픈) | `lib/historyTarget.ts` |
| 10 | MASTER 조회 응답(사업자 목록·번호 조회·상세·중복 요약, 가맹점 목록·코드 검색·상세의 business) | SQL 마스킹 | 저장 원문 10자리 | `MasterSqlText`·jOOQ Reader 6곳 |
| 11 | ADMIN 중계 응답(위 응답을 화면에 그대로 전달) | 형식 검사 `^[0-9]{3}\*{7}$` | 형식 검사 `^[0-9]{10}$` | `MasterResponseShape` |

범위 밖(D-PII-001 ① — 생길 때 결정): 가맹점용 화면·외부 API·내보내기(지금 없음).

## 2. API 응답 필드 결정 — **`registrationNumber`(원문 10자리), v2 계약 (M-1)**

- rev 0.1은 이름 `registrationNumberMasked`를 유지했다(C-1 — 당시 `adminApi.ts` 잠금). 리뷰 뒤 **Coordinator 결정 M-1**(2026-11-24): 지금 이름을 바꾼다. 이름이 "Masked"인데 값이 원문이면 로그·감사에 옮겨 적는 오해 위험이 있다(옛 L-1).
- 조회 응답(MASTER 사업자 목록·번호 조회·상세·중복 요약 `existing`, 가맹점 목록·코드 검색·상세 `business`, ADMIN 중계)의 키 = **`registrationNumber`**, 값 = 저장 원문 10 ASCII 숫자(`1234567890`). 하이픈은 **화면이 표시할 때만**(`123-45-67890` — `lib/registrationNumber.ts` `displayRegistrationNumber`, 입력란과 같은 3-2-5 규칙).
- 계약 golden: `business-profile-response-shapes-v2.golden.json`, `merchant-ops-response-shapes-v2.golden.json`(MASTER·ADMIN 사본 같은 바이트 — `*ShapeGoldenSpec`이 확인). **v1 파일은 지웠다** — 저장소 관례(AUTHN-001-PAYMENT-WINDOW.md 증거 표 #4 "MASTER v2 golden 파일 삭제(v3로 대체)… MASTER는 현재 버전만 조립", MS2 v1 선례: 생산자는 현재 버전 golden만 고정하고 옛 파일 부재를 확인). MASTER golden 시험이 v1 부재와 옛 키 0건을 단언한다.
- ADMIN 형식 검사는 **새 이름만** 허용한다(키 집합 정확 일치). 옛 키 `registrationNumberMasked`가 새 키 옆에 오거나 대신 오면 해석 불가 = fail-closed(503) — `BusinessShapeGoldenSpec`·`MerchantCodeShapeGoldenSpec`·`MasterBusinessGatewaySpec`.
- **구분 — 저장 계약은 바꾸지 않는다**: MASTER `master_change_history.detail`의 키 `registrationNumberMasked`(값 = 앞 3자리 + `*`)는 **이력 저장값**(이미 저장된 행과 같은 키 — D-MD-008, D-BIZ-007 "이력 마스킹 유지")이라 이름·값 모두 그대로다(`BusinessProfileService` 이력 detail, `BusinessReaderSpec` 옛 이력 행, MASTER `BusinessProfileServiceSpec`). 조회 응답 필드와 이름이 다른 것은 의도다.
- 값 표기를 10자리로 둔 근거: 번호 조회·등록 요청 본문·저장 표기와 같다(표기 하나 — D-MD-008 "암묵 정규화 없음"과 같은 방향).
- 대안(채택 안 함): ⓐ 이름 유지(rev 0.1 — 오해 위험) ⓑ 새 필드 추가 + 마스킹 필드 유지(같은 값 두 번) ⓒ MASTER가 하이픈 표기로 응답(표기 두 개).

## 3. 유지 항목 (바꾸지 않는다)

| 항목 | 값 | 근거 | 지키는 시험 |
|---|---|---|---|
| ADMIN 감사 detail | `brn=123*******`, 형식 오류 `brn=INVALID` | D-BIZ-007, D-MOPS-003 | ADMIN `BusinessHttpSpec` BIZ-3-6(원문 상세 응답을 받은 뒤에도 감사·DB·로그·지표에 원문 0건 — **강화**), `MerchantLookupHttpSpec`(응답 원문 + 감사 마스킹 — **강화**), `BusinessQueryServiceSpec`·`MerchantLookupServiceSpec`(불변) |
| MASTER 변경 이력 저장값 | `master_change_history.detail.registrationNumberMasked` = 앞 3자리 + `*`(**키 이름도 유지** — 저장 계약, §2 구분) | D-BIZ-007, D-MD-008 | MASTER `BusinessProfileApiSpec` BIZ-2-8(이력·admin_request·outbox·로그·400 본문에 원문 0건), `BusinessProfileServiceSpec`(불변) |
| 로그·지표·오류 메시지 | 번호 없음 | D-BIZ-007, PII §6 | MASTER BIZ-2-8(로그·400), ADMIN BIZ-3-6(로그·지표·오류 본문 — 400 본문은 원문 없음 유지) |
| Domain `toString`·`masked()` | 앞 3자리 | D-MD-008 | `MasterValuesSpec`, `BusinessProfileChangeSpec`(불변) |
| 번호 조회 횟수 제한(공유 버킷)·매 시도 감사 | 불변 | D-MOPS-003, D-BIZ-007 | 기존 `BusinessQueryServiceSpec`·`MerchantLookupServiceSpec`·`MerchantLookupHttpSpec`(불변) |
| 상세 열람 감사(`BUSINESS_VIEWED`·`MERCHANT_VIEWED`) | 불변 | D-PII-001 ② | 기존 시험(불변) |
| 목록 조회 감사 | **추가하지 않음** | D-PII-001 ② | 기존 목록 시험(감사 0건 단언 불변) |
| 번호를 식별자로 쓰지 않음 | 내부 키 `businessId` 그대로, URL·브라우저 저장소에 번호 없음 | D-BIZLOOKUP-001, PII §6 #10 | 화면 시험: 상세 열기 후 `location`·`sessionStorage`·`localStorage`에 원문 없음(**추가**) |
| 명령 요청 지문 | `m1:` 무염 SHA-256(D-MOPS-010 ② HMAC는 별도 X-2) | — | 범위 밖 |

## 4. 시험 목록 (TDD — RED 먼저)

MASTER (`apps/master-api`)
- T-M1 `BusinessReaderSpec`: 목록·번호 조회·상세·중복 요약이 저장 원문 10자리를 돌려준다(기대값 변경 — D-BIZ-007/D-PII-001 주석).
- T-M2 `MerchantOpsReaderSpec`: 가맹점 상세 business·목록·코드 검색이 원문.
- T-M3 `BusinessProfileApiSpec` TC-06·조회 모양·상세: 응답 원문. BIZ-2-8 재구성: 번호 캔어리는 **목록·번호 조회·상세·중복 요약 응답에 있다(대조)**, 로그·400 본문·`admin_request`·`master_change_history`·`outbox_event`에는 0건(유지·약화 없음). SQL 포획 단언은 "원문 열 SELECT 없음" → "원문 열을 SELECT하는 조회가 실제로 있다"(대조)로 바뀐다.
- T-M4 `MerchantOpsApiSpec`: 상세·검색 응답 원문.
- T-M6 `BusinessReaderSpec`·`MerchantOpsReaderSpec`: View `toString`에 원문 없음(로그 마스킹 유지 — C-5).
- T-M5 golden v2 `business-profile-response-shapes-v2`·`merchant-ops-response-shapes-v2`: 키 `registrationNumber`, 값 원문(Demo 값 `0000000000`·`9999999999`·`7780000001`), v1 파일 부재·옛 키 0건(M-1).

ADMIN (`apps/internal-admin`)
- T-A1 `BusinessShapeGoldenSpec`·`MerchantCodeShapeGoldenSpec`(golden v2 — MASTER와 같은 바이트): 원문 10자리 통과, **마스킹 값·하이픈 표기·9/11자리·옛 키 `registrationNumberMasked`(추가·대체) 거부**.
- T-A2 `BusinessHttpSpec` BIZ-3-6 강화: 상세 Stub에 캔어리 번호 → 화면 응답에 원문(대조) + ADMIN DB 모든 문자열 열·로그·지표·오류 본문에 원문 0건, 감사는 `brn=731*******`/`INVALID`만.
- T-A3 `MerchantLookupHttpSpec`: 검색 응답에 원문, 감사 detail은 마스킹·원문 0건.
- T-A5 `MerchantLookupServiceSpec` M-2: `MerchantLookupResult.Found.toString()` = `Found(***)`(원문 없음, 값은 그대로).
- T-A4 Stub·Fixture 값 원문으로(`StubMasterOpsServer`, `BusinessQueryServiceSpec`, `BusinessProfileServiceSpec`, `MasterBusinessGatewaySpec`).

화면 (`apps/internal-admin/frontend`)
- T-F1 `lib/registrationNumber.test.ts` `displayRegistrationNumber`: `1234567890` → `123-45-67890`, 10자리 숫자가 아니면 그대로(표시만 — 검사는 ADMIN 모양).
- T-F2 사업자 목록·번호 조회 결과·상세·수정 화면·중복 안내: 하이픈 원문.
- T-F3 가맹점 목록 열·상세 라벨 "사업자등록번호"(마스킹 문구 없음)·값 하이픈 원문.
- T-F4 가맹점 등록 마법사 "상호 · 123-45-67890", 사업자 → 가맹점 등록 넘겨주기 `BusinessRef.registrationNumber`.
- T-F5 `historyTarget`: "상호 · 123-45-67890".
- T-F6 URL·브라우저 저장소에 원문 없음(사업자 상세 열기 뒤, **가맹점 상세 열기 뒤** — N-2).

## 5. 구현자 선택·한계·위험

- C-1(rev 0.1 이름 유지)은 **M-1로 대체**(§2). C-2 하이픈 표시 함수 하나(`lib/registrationNumber.ts` `displayRegistrationNumber` — 입력란과 같은 3-2-5 규칙 재사용)를 모든 화면이 쓴다. C-3 `BusinessRef.masked` → `registrationNumber`(화면 내부 타입).
- C-4 `MasterSqlText.maskedRegistrationNumber()` → `registrationNumber()`(원문 열을 읽는 곳을 한 함수로). 쓰지 않게 된 `lib/registration.ts` `maskRegistrationNumber`는 지웠다(M-3 — 사용처 없음, 오래된 설명).
- C-5 **원문을 담는 data class의 `toString` 마스킹**(로그·예외 문자열 비노출). Grep(`registrationNumber`·`document: Map`·`existing: Map`)으로 찾은 목록:
  - MASTER(`registrationNumberLogText` — 앞 3자리 + `*` 7개): `BusinessDirectoryItem`, `BusinessSummary`, `BusinessDetailView.BusinessBlock`, `MerchantDirectoryItem`, `MerchantDetailView.BusinessSummary`(바깥 `BusinessDirectoryPage`·`MerchantDirectoryPage`·`MerchantDetailView`·`BusinessDetailView`는 이 항목의 toString을 쓴다).
  - ADMIN(문서 통째로 가림): `MasterCallResult.Found`(기존 `Found(***)`), `BusinessQueryResult.Found`(기존), `MasterBusinessCommandResult.Decided`·`BusinessProfileResult.Decided`의 `existing`(기존 `***`), **`MerchantLookupResult.Found`(M-2 — 이번에 추가 `Found(***)`)**. 그 밖(`MerchantLabelResult`·`MerchantTargetLabelResult`·승인 대상 `Found<T>`)은 번호를 담지 않는다.
- L-2(rev 0.1 `adminApi.ts` 주석)는 정정함(M-1).
- L-3 **배포**: §6.
- L-4 **jOOQ 실행 로그(N-3 후속 위험)**: jOOQ `LoggerListener`는 DEBUG에서 SQL과 **결과 행 일부**를 로그로 남긴다 — 원문 번호를 SELECT하게 되었으므로 `org.jooq` DEBUG가 켜지면 원문이 로그에 남을 수 있다. 현재 설정(확인 2026-11-24): MASTER `application.yml`에 `spring.jooq`는 `sql-dialect: MYSQL`만, `logging.level`에 `org.jooq` 항목 없음(루트 기본 INFO 상속 → DEBUG 출력 없음), jOOQ `Settings.executeLogging`은 설정하지 않음(jOOQ 기본 true — 리스너는 걸려 있고 DEBUG에서만 출력). **LIVE 전 검토 권장**: 운영 로그 설정에서 `org.jooq` DEBUG 금지를 명시하거나 `executeLogging=false`(Settings 사용자 정의) — 별도 작업(코드 변경 없음, Coordinator 판단).
- L-5 **열람 범위(결정 범위 안 — 수정 대상 아님)**: VIEWER·SECURITY_ADMIN도 가맹점 목록(VIEW_MASKED 4역할 — 기존 권한)에서 사업자등록번호 원문을 **감사 없이** 본다(목록 조회 감사 없음 — D-PII-001 ②, 범위 = 관리자 전체 — D-PII-001 ①). 번호로 **검색**하는 것은 여전히 OPERATOR·APPROVER만·매 시도 감사·공유 버킷.

## 6. 배포 절차

- **MASTER·ADMIN 동시 배포 필수**(MERCHANT-CODE-001 C-22와 같은 배포 단위). ADMIN은 새 키 `registrationNumber`·원문 10자리만 받는다 — 새 ADMIN + 옛 MASTER면 사업자·가맹점 조회가 503(fail-closed), 옛 ADMIN + 새 MASTER도 503. 금융 효과는 없다(조회 전용). 화면 번들은 ADMIN과 같이 나간다(같은 출처 정적 번들).
- DB migration 없음. 이력 저장값 불변.
