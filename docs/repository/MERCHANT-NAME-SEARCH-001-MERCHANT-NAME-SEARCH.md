# MERCHANT-NAME-SEARCH-001 가맹점 조회 — 가맹점명 조건 명세 (rev 0.4)

작성 2026-11-20 KST, branch `main`(기준 commit `514685f`). Implementer 작성 — 자기 완료 판정 아님(Verifier·Reviewer 별도).
rev 0.2: MNS-1 리뷰(REVIEW_PASS) MINOR-1~3·NIT-1·2·3·5 반영 — L-3 운영 전 결정 항목, V17 운영 적용 절차(§5.1·L-4), §7 Recoverability, jOOQ 문구, 시험 추가.
rev 0.3 (2026-11-20, 기준 commit `a56ac0a`): MNS-2 ADMIN 백엔드 구현 기록(§11) — 리뷰·게이트 전.
rev 0.4 (2026-11-20, 기준 commit `af53f28`): 리뷰 NIT-3 — §9 MNS-1·MNS-2 상태 정정, MNS-3 화면 구현 기록(§12) — 리뷰·게이트 전.

## 1. 목적·사용자 요청

- 사용자 요청(2026-11-19): "가맹점조회에서 조건이 가맹점명도 추가해줘".
- 가맹점 조회(MERCHANT-OPS-001 §3.1 `POST /internal/v1/admin/merchant-searches`)에 가맹점명(`merchant.display_name`) 앞부분 조건을 더한다. 읽기 전용 — 금융 효과·상태 변경·
  기관 송신 없음.

## 2. 결정 근거(가역 구현 선택 — Coordinator 방침, 저장소 관례)

사업자 상호 검색(BUSINESS-PROFILE-001 §6.2 `displayNamePrefix`, C-5, L-11 — `JooqBusinessDirectoryReader`·`BusinessSearchRequestParser`)과 **같은 방식**을 쓴다.
새 업무 정책이 아니라 기존 확정 규칙의 재사용이다.

| 항목 | 선택 | 근거 |
|---|---|---|
| 일치 방식 | 앞부분 일치 `LIKE ? ESCAPE '!'`(`%`·`_`·`!` 이스케이프 — 입력은 글자 그대로) | BUSINESS-PROFILE-001 C-5 |
| 대소문자 | 구분(열 collation `utf8mb4_0900_bin` 그대로) | 사업자 상호 검색과 같음 |
| 입력 정규화 | 없음(trim·NFC 없음). 형식 위반은 400 | `DisplayName.errorOf` 규칙 |
| 감사 | 목록 조회 감사 없음 | D-MOPS-002(docs/DECISIONS.md), 상호 비개인정보 BUSINESS-PROFILE-001 §9·L-3 |
| 조회 역할 | 변경 없음 | MERCHANT-OPS-001 §5 |
| 정렬·keyset | 기존 `merchant_id` 오름차순, `merchant_id > after`, `limit + 1` | MERCHANT-OPS-001 §3.1 |

## 3. API 필드(MASTER 내부 — 서명 채널, 읽기)

`POST /internal/v1/admin/merchant-searches` 본문에 선택 필드 `displayNamePrefix`(JSON 문자열)를 더한다. 응답 모양·오류 코드·golden은 바뀌지 않는다.

- 형식: `DisplayName` 규칙 — 1~100 code point, 제어문자·짝 없는 서로게이트 금지, 앞뒤 공백 금지. 숫자·null·배열 등 문자열이 아니면 400.
- 위반은 400 `{"error":"REQUEST_MALFORMED"}`(어느 필드인지·입력 값을 응답·로그에 넣지 않는다 — 기존 §5.5와 같다).
- Application: `MerchantSearchFilter.displayNamePrefix: DisplayName?`(Domain 값 객체 — Spring/jOOQ 비의존).

## 4. 조합 규칙

| 함께 온 필드 | 결과 |
|---|---|
| `merchantId`·`businessId`·`lifecycle`·`registrationNumber`·`after`·`limit` | AND |
| `merchantCode` | 400(코드 검색은 단일 기준 — MERCHANT-CODE-001 §3.8, `MerchantSearchFilter` 불변식) |

찾음/못 찾음 응답 모양 동일(항목 수만 다르다). 이름 검색 항목에는 `matchedRetiredCode` 키가 없다(코드 검색 전용).

## 5. DB

- `V17__merchant_display_name_index.sql`: `ALTER TABLE merchant ADD INDEX ix_merchant_name_id (display_name, merchant_id), ALGORITHM = INPLACE, LOCK = NONE`
  (V15 `ix_business_name_id` 선례 — 온라인 추가).
- 새 표·열 없음 → runtime 권한 변경 없음, **V18은 비워 둔다**(V8·V12 선례).
- jOOQ 생성 코드: 새 테이블·컬럼 타입 없음(빌드 산출물에 인덱스 상수만 추가).

### 5.1 V17 운영 적용 절차(LIVE Gate — 이번 작업은 Demo·시험만)

1. 적용 세션에 `lock_wait_timeout`을 짧게 지정한다(값은 LIVE Gate에서 결정). `ALGORITHM = INPLACE, LOCK = NONE`이어도 시작·끝에 `merchant` 표 metadata lock(MDL)을 잠깐 잡으므로,
   **오래 열린 TX가 `merchant`를 읽고 있으면 MDL 대기가 생기고 그 뒤의 읽기·쓰기도 줄줄이 기다린다** — 적용 전 장기 TX 확인(`information_schema.innodb_trx`).
2. 실패(시간 초과·중단) 시: 색인이 만들어지지 않았는지 확인 → `flyway repair`로 실패 이력을 정리한 뒤 재시도. 업무 데이터는 바뀌지 않는다.
3. 되돌리기: `ALTER TABLE merchant DROP INDEX ix_merchant_name_id`(데이터 무변경 — 이름 검색은 색인 없이도 같은 결과, 느릴 뿐). Flyway 이력 정리는 운영 절차를 따른다.

## 6. 감사·관측

- 감사: 목록 조회는 감사하지 않는다(D-MOPS-002). 상세 열람 감사(`MERCHANT_VIEWED`)는 그대로.
- 관측: 기존 검색 계측(endpoint별 수락 수·permit 혼잡 503 `ADMIN_READ_BUSY`)을 그대로 쓴다. 메트릭 label에 검색 값을 넣지 않는다.

## 7. 8대 원칙별 증거(읽기 전용 기능 범위)

| 원칙 | 이번 변경의 증거 |
|---|---|
| Financial Consistency | 금융 사실을 읽거나 바꾸지 않는다(가맹점 마스터 조회). 해당 없음 |
| Transaction Safety | 기존 읽기 전용 `REPEATABLE READ` TX 한 문장(jOOQ). 쓰기 없음 |
| Idempotency | 같은 검색 반복 = 같은 본문, MASTER 표 변화 없음(MerchantOpsApiSpec 기존 Idempotency 시험) |
| Concurrency Control | 기존 read permit(503 `ADMIN_READ_BUSY`) 그대로 |
| Recoverability | 저장소 실패 503 `TEMPORARILY_UNAVAILABLE`(기존). V17은 INPLACE 색인 추가 — 실패해도 업무 데이터 무변경, `lock_wait_timeout` 지정·장기 TX 확인(MDL 대기) 후 적용, 실패 시 Flyway repair 후 재시도, 되돌리기는 DROP INDEX(§5.1·L-4) |
| Reconciliation | 해당 없음(정산·대사 데이터 아님) |
| Auditability | D-MOPS-002 — 목록 미감사, 상세 감사 유지 |
| Observability | 기존 검색 계측. EXPLAIN 계획을 시험 출력에 기록(MNS-1-R4) |

JPA: 이번 변경은 CQRS 조회 측(jOOQ `adapter.query`)만 바꾼다. JPA Entity·Repository·Domain Aggregate는 바뀌지 않는다(쓰기 모델 무변경).
Kafka/Redis: 도입하지 않는다(필요 없음).

## 8. 알려진 한계

- **L-1** 앞부분 일치만 지원(중간 일치 없음) — 필요하면 색인 전략 재검토.
- **L-2** 대소문자 구분(`utf8mb4_0900_bin`). "demo"로 "Demo…"를 찾지 못한다.
- **L-3** filesort: 이름 조건은 `ix_merchant_name_id` 범위를 읽은 뒤 `merchant_id` 오름차순으로 다시 정렬한다(일치 행 전체를 읽은 뒤 `limit + 1`).
  짧은 접두어가 많은 행에 일치하면 비용이 커진다 — 최소 접두 길이·정렬 재검토는 LIVE Gate(EXPLAIN 기록). 시험은 `possible_keys` 포함만 단언한다(행이 적으면 optimizer가 key를 바꾼다).
  **운영 전 결정 항목**: 최소 접두 길이 또는 statement timeout(`max_execution_time`) 중 하나를 LIVE Gate에서 결정한다(이번 작업은 코드 변경 없음 — 정책값을 임의로 두지 않는다).
- **L-4** V17 운영 적용은 §5.1 절차(세션 `lock_wait_timeout`, 장기 TX가 있으면 MDL 대기, 실패 시 Flyway repair 후 재시도, 되돌리기 DROP INDEX — 데이터 무변경)를 LIVE Gate에서 수행한다.

## 9. 배치

| 배치 | 범위 | 상태 |
|---|---|---|
| MNS-1 | MASTER: Filter·parser·jOOQ 조건·`MasterSqlText.likePrefix` 공유(사업자 Reader도 사용)·V17 색인·시험 | 완료(MNS1-gate1, 커밋 a56ac0a) |
| MNS-2 | ADMIN 백엔드: 화면 API 본문 전달·검증(MASTER 먼저 배포 — 모르는 필드 400 방지 순서) | 완료(MNS2-gate1, 커밋 af53f28) |
| MNS-3 | 화면: 가맹점 조회 조건에 가맹점명 입력·시험 | 구현(리뷰·게이트 전, §12) |

## 10. 시험 목록(MNS-1)

- `MerchantNameSearchParserSpec`(단위): P1 정상 접두 그대로(`%_!`·이모지·100 code point — `a`×100과 서로게이트 `😀`×100 경계), P2 빈 값·앞뒤 공백·제어문자·101 code point·숫자·null·배열 → null(400),
  P3 `merchantCode`와 함께 → null, P4 lifecycle·businessId·registrationNumber·merchantId·after·limit과 함께 통과, P5 필드 없음 = 이름 조건 없음.
- `MerchantOpsReaderSpec`(MySQL 8.4 Testcontainers): R1 앞부분만(중간 일치 제외)·대소문자 구분·`%`·`_`·`!`·`\`(백슬래시) 글자 그대로(escape 제거 mutation 시 R1 실패 확인 — 리뷰 MINOR-3), R2 limit=2 keyset 끝까지 merchant_id 오름차순·
  중복·누락 없음·마지막 nextCursor null, R3 businessId·registrationNumber·lifecycle·merchantId AND·찾음/못 찾음 모양, R4 `ix_merchant_name_id` 열 순서
  (display_name, merchant_id)·EXPLAIN `possible_keys` 포함(계획 출력 기록).
- `MerchantOpsApiSpec`: 이름 검색 200(목록 모양·`matchedRetiredCode` 없음)·대소문자 다른 접두 0건 동일 모양, 형식 위반 이름·이름+코드 → 400 `REQUEST_MALFORMED`(입력 비반사), 검색한 이름이 수락·거절 어느 쪽에서도 로그에 없음(CapturedLogs canary).
- 회귀: `BusinessReaderSpec`(공유 `likePrefix` — C-5 이스케이프), migration 목록 기대값 2곳(`MasterApiMainMigrationsBootSpec`·`MasterApiContextSmokeSpec` — V17).

## 11. MNS-2 구현 기록(ADMIN 백엔드 `apps/internal-admin` — 리뷰·게이트 전, Implementer 자기 완료 판정 아님)

화면 API `POST /api/merchant-searches` 본문에 선택 필드 `displayNamePrefix`를 받아 MASTER로 그대로 전달한다. 컨트롤러·`MasterOpsGateway` 인터페이스 시그니처, 응답 모양·오류 코드는 바뀌지 않는다.

| 위치 | 변경 |
|---|---|
| `adapter/web/MerchantSearchBodyParser` | 알려진 문자열 필드에 `displayNamePrefix` 추가(JSON 문자열만 — 숫자·null·배열·객체는 `Malformed`, 감사 없음). 등록번호 시도 스캐너 무변경 |
| `application/provided/MerchantSearchCriteria` | `displayNamePrefix: String? = null`(마지막 인자). toString은 `***`/`null`만(BusinessSearchCriteria와 같다 — 상호에 사람 이름 가능, L-3 취지) |
| `application/MerchantLookupService` | `wellFormed`: 이름이 있으면 `RegistrationInputRules.displayName`으로 MASTER 호출 전 검사. `codeSearchWellFormed`: `displayNamePrefix == null` 추가(코드 + 이름 → 400) |
| `adapter/integration/HttpMasterOpsGateway.search` | 본문 고정 순서 `merchantId, businessId, lifecycle, registrationNumber, after, merchantCode, displayNamePrefix, limit`(기존 본문 바이트 불변), 원문 그대로(trim·LIKE 이스케이프 없음). 응답 모양 `DIRECTORY` |

가역 구현 결정(저장소 관례 — 새 업무 정책 아님):

- **형식 규칙 출처**: `RegistrationInputRules.displayName`(가맹점 등록의 MASTER `DisplayName` 복제, MERCHANT-OPS-002 C-10)을 쓴다. `BusinessProfileInputRules.displayNameWellFormed`와
  판정 집합이 같음을 확인했다(빈 값·앞뒤 `' '`·`isISOControl`·짝 없는 서로게이트·101 code point 이상 거부 — 둘 다 MASTER `BusinessTextRule.errorOf(raw, 100)` = `DisplayName.errorOf`의 복제).
  가맹점명이므로 가맹점 등록 규칙 쪽을 고른다.
- **감사·속도**: 이름만(또는 다른 필터와 AND) 검색은 목록 검색 — 감사 없음(D-MOPS-002), 일반 조회 창(`lookupPerMinute`). 이름 형식 위반은 감사 없는 `InvalidInput`.
  등록번호와 함께면 기존 등록번호 경로 그대로 — 이름 위반도 `MERCHANT_BRN_SEARCHED|INVALID_INPUT|brn=123*******`(마스킹 번호만, 이름은 감사 행·로그에 없다).
- **오류 응답**: 화면 API 형식 위반은 기존과 같이 400 `{"error":"INVALID_INPUT"}`(입력 비반사).

시험(RED 먼저 — `~/gate-logs/mns-2/`):

- `MerchantNameSearchSpec`(새, 9): parser 텍스트 통과(`%_!`·앞뒤 공백·이모지 그대로)·숫자/null/배열/객체 Malformed(false)·등록번호와 함께면 Malformed(true) 유지, 정상 이름(최대 100 code point 경계·다른 필터 AND) MASTER 그대로·감사 0,
  형식 위반(빈 값·앞뒤 공백·줄바꿈·탭·NUL·DEL·101 code point·짝 없는 서로게이트) → InvalidInput·MASTER 미호출·감사 0, 코드 + 이름 → InvalidInput·MASTER 미호출·감사 0,
  이름 + 등록번호 → OK 감사(마스킹)·이름 위반은 INVALID_INPUT 감사, 이름 검색 속도 창(감사 없음), toString `***`, 로그 canary(수락·거절·등록번호 경로·실제 HTTP gateway — 대조군 operation 줄 수 단언).
- `MasterOpsGatewaySpec`(+1): 실제 HTTP 대역 — 본문 `{"lifecycle":"ACTIVE","displayNamePrefix":"%_!홍 길동😀","limit":3}` 바이트 그대로, 서명 검증, query 없음, DIRECTORY 응답 Found.
- `MerchantLookupHttpSpec`(+1): 실제 로그인 세션·MySQL 감사 표 — 이름 검색 200(no-store, 감사 0, MASTER 본문 원문), 형식 위반·숫자·null·이름 + 코드 → 400 `INVALID_INPUT`·MASTER 미호출, 응답·로그에 이름 없음.
- 기존 단언 변경 없음.

8대 원칙(§7과 같다 — 읽기 전용): 금융 사실·상태 변경 없음, ADMIN DB 쓰기 없음(이름 검색은 감사도 없다), MASTER 호출은 DB TX 밖(gateway가 TX 안 호출 거부 — 기존), 같은 검색 반복은 같은 결과,
동시성은 기존 MASTER 조회 permit·속도 창, 실패는 기존 503 분류, 로그·감사에 이름 원문 없음, 관측은 기존 `operation=merchant-search`·`master-ops-search` 분류 줄.
JPA: 이번 배치는 JPA Entity·Repository를 바꾸지 않는다(ADMIN은 MASTER HTTP 조회만 — 쓰기 모델 무변경). Kafka/Redis: 도입하지 않는다.
배포 순서: MASTER(MNS-1) 먼저 — ADMIN만 먼저 나가면 MASTER가 모르는 필드로 400을 돌려 ADMIN은 `Unavailable("HTTP_400")`(이름 검색만 실패, 다른 검색 영향 없음).

## 12. MNS-3 구현 기록(관리자 콘솔 화면 `apps/internal-admin/frontend/src` — 리뷰·게이트 전, Implementer 자기 완료 판정 아님)

가맹점 조회 화면(`MerchantsScreen`) 조회 조건 줄에 가맹점명 입력을 더한다. 화면 API·응답 모양·오류 문구 표는 바뀌지 않는다.

| 위치 | 변경 |
|---|---|
| `api/adminApi.ts` `MerchantSearchInput` | `displayNamePrefix?: string`(앞부분 일치·대소문자 구분·원문 그대로, `merchantCode`와 함께 불가 — 주석) |
| `screens/MerchantsScreen.tsx` | 상태 `displayNamePrefix`. 조회 조건 줄(`.filters` — 사용자 결정으로 가로 배치 유지)에 코드 입력 다음·상태 select 앞 `<input aria-label="가맹점명" placeholder="가맹점명 (앞부분 일치)" autoComplete="off" spellCheck={false}>`(기존 필터 줄 스타일 — 새 색·`style` 속성 없음) |
| `search()` | 코드와 함께면 호출 없이 `combined` 문구. 이름이 있으면 `criteria.displayNamePrefix` = 입력 원문 그대로(trim·정규화 없음 — 앞뒤 공백 등 형식 위반은 서버 400 `INVALID_INPUT`을 기존 고정 문구 "입력 형식이 올바르지 않습니다."로, 사업자 조회 화면과 같은 방식). 다음 쪽은 보낸 조건(`sent.criteria`)에 이름이 들어 있어 그대로 이어진다 |
| 문구 | `combined`: "가맹점 코드 검색은 다른 조건(가맹점명·상태·사업자등록번호)과 함께 쓸 수 없습니다." 조회 안내(`.cardnote`)에 "가맹점명: 앞부분이 같은 이름(영문 대소문자 구분)"(입력란 `aria-describedby`) |

> 이후 변경(2026-11-24): 위 148–150행의 화면 배치(`.filters` 조회 조건 줄·placeholder·`.cardnote` 상시 도움말)는 **MERCHANT-IA-001 §7.7(B6 필터 묶음)로 대체**되었다 — 가맹점명 규칙은 칸 안 짧은 안내 + 서버 형식 거절 때 칸 아래 전체 문구. 검색 조건·호출 인자·서버 판정은 이 문서 그대로.

가역 구현 결정: 이름은 화면에서 검사하지 않는다(코드처럼 형식이 짧지 않고, 판정 규칙 복제를 하나 더 늘리지 않는다 — 서버 MNS-2 `RegistrationInputRules.displayName`이 판정). 이름 입력은 검색 뒤에도 비우지 않는다(개인 식별 번호가 아님 — 사업자 조회 화면 상호와 같다).

시험(`MerchantsScreen.test.tsx`, RED 먼저 — `~/gate-logs/mns-3/`):

- MNS-3-1 입력란 속성(aria-label·placeholder·autocomplete off·spellcheck false)·위치(코드 다음·상태 앞, `.filters` 같은 줄), 이름만 → `{displayNamePrefix}`만, 앞뒤 공백 포함 원문 그대로.
- MNS-3-2 이름 + 상태 → 둘 다. MNS-3-3 코드 + 이름 → 호출 없음·새 `combined` 문구·코드 입력 `aria-invalid`.
- MNS-3-4 다음 쪽 `{displayNamePrefix, after}`(검색 뒤 입력을 바꿔도 보낸 조건 그대로).
- MNS-3-5 400 `INVALID_INPUT` → 기존 고정 문구, 입력값은 입력란 value 밖(문구·속성·목록) 어디에도 없음(`domOccurrences`).
- MNS-3-6 도움말 문구가 `.cardnote` 안·입력란 `aria-describedby` 연결, 검색 전후 `[style]` 0.
- 기존 단언 변경: MC-2-3 `combined` 문구 2곳만(`// MERCHANT-NAME-SEARCH-001 MNS-3 기대값 변경(사용자 요청 가맹점명 조건)`). 그 밖 무변경.

리뷰 수정 1(REVIEW_PASS MINOR 1·NIT 3): 이름 + 등록번호 다음 쪽 `{displayNamePrefix, businessId, after}` 시험(MNS-3-4b), MNS-3-5 코드·등록번호 입력란 value 단언, combined 오류일 때만 가맹점명 입력란 `aria-describedby`에 코드 안내 추가(MNS-3-3b), 도움말 "가맹점명: 앞부분이 같은 이름(영문 대소문자 구분, 앞뒤 공백 없이)".

8대 원칙: 화면은 읽기 전용 조회 조건만 더한다 — 금융 사실·상태·TX·Outbox 변화 없음, 같은 검색 반복은 같은 요청 본문, 늦게 온 앞 요청은 기존 요청 순번으로 버림(Concurrency), 오류는 기존 고정 문구(입력 비반사), 감사·관측은 서버 기존 경로(§6·§11). JPA·jOOQ·Kafka/Redis 무관(화면 변경만).
