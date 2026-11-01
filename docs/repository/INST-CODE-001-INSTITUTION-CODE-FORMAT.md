# INST-CODE-001 — 기관 식별 코드 공통 형식 `^[A-Z0-9]{1,32}$` 통일(MASTER·PAYMENT·Simulator·golden)

- 상태: 구현 명세 rev 0.1(2026-10-31, 구현자). DONE 판정은 Verifier·Reviewer·Coordinator.
- 근거 결정: **D-INST-CODE-001**(Q-DS-06, 2026-10-31 — "두 형식 모두 금지하는 공통 규칙" + 범위 "기관 식별 코드 전부"). 유지 결정: D-MS2-004(카드사 개통 키 = 매입사), D-MS2-005(기관 계약계정 = 내부 식별자 등급), D-AUTHN-003(바인딩 echo 대조), AUTH-001 §5.2(VAN 계약), CANCEL-001 §6(sim-van-2).
- 기준: HEAD `406ebbf`(feat/foundation-setup). 작업 트리에는 DEMO-SEED-001의 미커밋 변경이 있다 — 이 작업은 그 파일들을 고치지 않는다(§9).
- 관례: [ARCHITECTURE-CONVENTIONS §14](ARCHITECTURE-CONVENTIONS.md), 기존 migration 불변(새 버전만).

## 1. 문제

DEMO-SEED-001 구현 중 발견: MASTER `AcquirerCode`와 V5 CHECK `ck_card_route_activation_acquirer`는 `[A-Z0-9_]`, MASTER `RouteRef`·V5 `ck_card_route_activation_route`는 `[A-Za-z0-9._:-]{1,64}`인데 Simulator 카탈로그(`SIMACQ-A`·`SIMISS-A`·`SIMAUTH-1`), VAN codec(`[A-Z0-9-]`), PAYMENT 시험 설정·golden의 Route(`SIM-VAN-01`)는 하이픈을 쓴다. 그래서 Simulator가 인증 결과로 돌려주는 매입사 코드를 MASTER 카드사 개통에 그대로 등록할 수 없다(MASTER 개통 매입사 = 인증 결과 매입사 대조가 Demo에서 성립하지 않음). D-INST-CODE-001은 하이픈·밑줄을 **모두** 금지하는 공통 형식으로 전 앱을 맞춘다.

## 2. 범위 분류

### 2.1 대상(기관 식별 코드) — 형식 `^[A-Z0-9]{1,32}$`

| 코드 | 위치 | 이 작업 전 | 이 작업 후 |
|---|---|---|---|
| 매입사 코드 `acquirerCode` | MASTER `AcquirerCode`·`card_route_activation.acquirer_code` CHECK, Snapshot `cardRoutes[].acquirerCode`, PAYMENT Snapshot 해석, 인증 결과 evidence, VAN `authentication.acquirerCode` | MASTER `[A-Z0-9_]{1,32}`, Simulator VAN `[A-Z0-9-]{1,32}`, PAYMENT 인증 결과 1..128 아무 문자, PAYMENT Snapshot 비어 있지 않음만 | 전부 `^[A-Z0-9]{1,32}$` |
| 발급사 코드 `issuerCode`(= 결제창 카드사 `cardCompany`) | Simulator 카탈로그·등록 요청 `cardCompany`, 인증 결과 evidence, VAN `authentication.issuerCode`, PAYMENT 인증 프로파일 `card-companies[].code` | Simulator `[A-Z0-9-]{1,32}`, PAYMENT 인증 결과 1..128, 프로파일 검사 없음 | 전부 `^[A-Z0-9]{1,32}$` |
| 인증 기관 코드 `providerCode` | Simulator 카탈로그, 인증 결과 evidence, VAN `authentication.providerCode` | Simulator VAN `[A-Z0-9-]`, PAYMENT 1..128 | `^[A-Z0-9]{1,32}$` |
| Route 코드 `routeRef` | MASTER `RouteRef`·`card_route_activation.route_ref` CHECK, Snapshot `cardRoutes[].routeRef`, PAYMENT Route 등록부 키(`paycore.authorization.routes.<routeRef>`), VAN `routeRef` | MASTER `[A-Za-z0-9._:-]{1,64}`, PAYMENT 등록부 `[A-Za-z0-9._-]{1,64}`, Simulator VAN `[A-Za-z0-9._-]{1,64}` | 전부 `^[A-Z0-9]{1,32}$` |
| VAN 기관 코드 `institution`(Route 설정 `institution`) | PAYMENT Route 등록부 | 비어 있지 않음만 | `^[A-Z0-9]{1,32}$`(기관 식별 코드 — "전부" 범위) |

### 2.2 제외(기관 식별 코드가 아님) — 형식 변경 없음

| 값 | 예 | 제외 이유 |
|---|---|---|
| 가상 카드 참조 | `VC-CREDIT-OK` | 카드 시나리오 참조 이름(결정문 명시 제외) |
| 기관 계약계정 / 기관 가맹점 참조 | `SIMMERCH0001`, `SIMVAN-DEMO-0001`, `sim-acct-01` | 기관 쪽 가맹점 계정(D-MS2-005 내부 식별자 등급, 결정문 명시 제외). MASTER `InstitutionMerchantRef`·VAN `institutionAccount` 형식 유지 |
| 가맹점 참조 `merchantRef` | `mid-demo-0001` | PayCore MID |
| 프로토콜·프로파일·Capability 버전 | `sim-van-1`, `sim-van-2`, `sim-van-a-demo-2`, `cancel-a-demo-1`, `SIM-PROTO-1` | 계약/설정 버전 식별자 |
| 키 ID | `sim-van-local-1`, `sim-van-test-1` | HMAC 키 식별자 |
| 기관 거래 ID·승인번호·근거 참조 | `PTX-SIM-0001`, `SIMPTX-…`, `A0001`, `sim-ev-A` | 기관 거래/근거 식별자 |
| 카드 종류 `cardType` | `CREDIT`, `PREPAID` | 계약의 열린 토큰 `^[A-Z][A-Z0-9_]{0,31}$`(PG R10 판정) — 기관 식별 코드 아님 |
| 정책 코드 `PolicyCode` | `FEE-SCHEMA1` | 수수료·정산 정책 이름(D-MS2-003) |
| 멱등키·요청 ID·실패 code·client ID | `ar_…`, `pg-local` | 프로토콜 식별자 |

## 3. 매핑(최종)

| 이전 | 이후 | 쓰임 |
|---|---|---|
| `SIMACQ-A` | `SIMACQA` | Simulator 카탈로그 매입사(모든 가상 카드), golden, 시험 |
| `SIMACQ-B` | `SIMACQB` | 시험(다른 매입사) |
| `SIMISS-A` | `SIMISSA` | Simulator 카탈로그 발급사, 결제창 카드사 allowlist, golden, 시험 |
| `SIMISS-B` | `SIMISSB` | Simulator `VC-SPLIT-OK`·`VC-OTHER-ISSUER` 발급사, allowlist, 시험 |
| `SIMISS-Z` | `SIMISSZ` | 시험(allowlist 밖 카드사) |
| `SIMISS-OTHER` | `SIMISSOTHER` | 시험(다른 발급사) |
| `SIMAUTH-1` | `SIMAUTH1` | Simulator 카탈로그 인증 기관, golden, 시험 |
| `SIMAUTH-2` | `SIMAUTH2` | Simulator `VC-SPLIT-OK` 인증 기관, 시험 |
| `SIM-VAN-01` | `SIMVAN01` | Route(로컬 Demo `.env.example`, 시험 yml, VAN golden, MASTER 시험) |
| `SIM-VAN-02` | `SIMVAN02` | 시험(다른 Route) |
| `SIM-VAN-99` | `SIMVAN99` | 시험(모르는 Route) |
| `SIM-VAN-A`·`-B`·`-C`·`-CB`·`-CLOSED` | `SIMVANA`·`SIMVANB`·`SIMVANC`·`SIMVANCB`·`SIMVANCLOSED` | 시험 Route·기관 namespace |

`SIMCARD01`·`OTHERCARD`·`THIRD`·`OLDCARD`·`SIMVAN`(Route `institution`)은 이미 형식에 맞아 그대로다.

## 4. 불변식과 시험

| ID | 불변식 | 시험 |
|---|---|---|
| IC-1 | MASTER `AcquirerCode`·`RouteRef`는 `^[A-Z0-9]{1,32}$`만 생성된다. 하이픈·밑줄·소문자·빈 값·33자는 거절, 메시지에 입력 원문 없음 | `master-api` `InstitutionCodeFormatSpec` |
| IC-2 | MASTER DB가 이중 방어: `acquirer_code`·`route_ref`에 하이픈·밑줄·소문자 → 3819(runtime 계정) | `AuthorizationTermsSchemaSpec`(카드사 개통 Given 확장) |
| IC-3 | 종단 대조: Simulator 인증 결과 golden(VC-CREDIT-OK)의 `acquirerCode`가 MASTER `AcquirerCode`·DB CHECK에 그대로 등록되고, 그 값이 Snapshot `cardRoutes[].acquirerCode`로 그대로 나간다 | `master-api` `InstitutionCodeFormatSpec`(golden 매입사 → 값 객체·`AuthorizationTermsSchemaSpec` 등록 성공), PAYMENT `EntitlementAuthorizationAdmissionAdapterSpec`(Snapshot 매입사 = 인증 결과 매입사로 Route 선택 — 기존), 하네스 `test_institution_code_format.py`(카탈로그 ↔ golden ↔ MASTER CHECK) |
| IC-4 | PAYMENT Snapshot 해석: `cardRoutes[].acquirerCode`·`routeRef`가 형식 밖이면 `SnapshotContractViolation`(Inbox 거절 — 기존 계약 위반 경로) | `payment-api` `AuthorizationTermsContentSpec` |
| IC-5 | PAYMENT 인증 결과 해석: evidence `issuerCode`·`acquirerCode`·`providerCode`가 형식 밖이면 `Unverifiable(MALFORMED)`(결과로 쓰지 않음) | golden `malformedLookups` 새 벡터 3개(하이픈 매입사·밑줄 발급사·하이픈 인증 기관) → `SimulatorCardAuthenticationAdapterSpec`(기존 반복 시험) |
| IC-6 | PAYMENT 기동 검사: Route 키·Route `institution`·인증 프로파일 `card-companies[].code`가 형식 밖이면 기동 실패 | `ConfiguredInstitutionRouteRegistrySpec`, `AuthenticationBackendIsolationSpec` |
| IC-7 | Simulator 등록 요청 `cardCompany`가 형식 밖이면 400 INVALID_REQUEST | `SimulatorApiContractSpec`(등록 규칙 확장) |
| IC-8 | Simulator VAN codec: `routeRef`·`authentication.{issuer,acquirer,provider}Code`가 형식 밖이면 FRAMING | `VanFrameCodecSpec`(변조 벡터 확장) |
| IC-9 | Simulator 카탈로그의 모든 발급사·매입사·인증 기관 코드가 형식을 따른다 | `AuthenticationTransactionSpec`(카탈로그 Then 추가), 하네스 `test_institution_code_format.py` |
| IC-10 | 세 앱의 형식 정의가 같은 문자열 `^[A-Z0-9]{1,32}$`다(MASTER 값 객체·V7 CHECK, PAYMENT 형식 상수, Simulator CODE·CARD_COMPANY) | 하네스 `test_institution_code_format.py` |

## 5. Migration

- MASTER: 새 `db/migration/master/V7__institution_code_format.sql`(다음 빈 번호 — V6은 runtime 권한). V5는 고치지 않는다. 한 `ALTER TABLE card_route_activation` 문으로 `ck_card_route_activation_acquirer`·`ck_card_route_activation_route`를 지우고 `ck_card_route_activation_acquirer_code`·`ck_card_route_activation_route_code`(`REGEXP_LIKE(…, '^[A-Z0-9]{1,32}$', 'c')`)를 더한다. 한 문장이라 MySQL에서 원자적이다 — 기존 행이 새 CHECK를 어기면 문장 전체가 실패하고 V5 CHECK가 그대로 남는다(제약 없는 중간 상태 없음). 이름을 바꾼 이유: 같은 문장에서 같은 이름을 지우고 다시 만드는 동작에 기대지 않는다. 컬럼 길이(`route_ref VARCHAR(64)`)는 그대로 — CHECK가 32자로 좁힌다.
- **기존 행**: 운영 DB 없음(LIVE 미운영). 비운영 DB(로컬 Demo·개발)에 형식 밖 행(`SIM-VAN-01` route 등)이 있으면 V7이 실패한다. 자동 데이터 변환은 하지 않는다 — 카드사 개통 행을 고치면 개통 키(`open_route_key`)·Snapshot 내용·이력 근거가 바뀌므로 이 작업이 정할 일이 아니다. 비운영 DB는 초기화 후 다시 seed한다(§8 참고 1).
- MASTER runtime 권한: 변경 없음(DDL 아님 — 새 표·권한 없음). Flyway 적용 목록 시험(`MasterApiContextSmokeSpec`·`MasterApiMainMigrationsBootSpec`)에 `"7"`이 더해진다.
- PAYMENT: 기관 코드 열(`authentication_session.issuer_code`·`acquirer_code`·`provider_code`·`card_company`, `payment_authorization.route_profile` 등)에 형식 CHECK가 **없다** — 바꿀 CHECK가 없어 migration 없음. 새 CHECK를 더하지 않는다(결정 범위는 "PAYMENT 계약" 검증이고, 저장된 비운영 행을 깨는 ALTER는 범위 밖).
- Simulator: 기관 코드 열(`card_company` 등)에 형식 CHECK 없음 — migration 없음.

## 6. 검증 위치(PAYMENT·Simulator)

- PAYMENT 형식 검사는 **계약 경계**(Inbox Snapshot 해석, 인증 결과 해석, 기동 설정)에서 한다. Domain 재구성(`AuthenticatedDetails`·`AuthenticationSession`·`ProviderRequestRef`·`AuthorizationAdmission`)에는 형식 `require`를 더하지 않는다 — 저장된 비운영 행의 재구성을 예외로 만들지 않기 위해서다. 새 값은 모두 경계를 지나 들어오므로 새로 생기는 값은 형식을 따른다.
- PAYMENT Snapshot 해석(`EntitlementContent.fromJson`)은 저장된 projection 재해석에도 쓰인다. 비운영 DB에 이전 형식 projection이 있으면 그 MID의 승인 판정이 계약 위반으로 fail-closed된다(초기화 필요 — §8 참고 1).
- PAYMENT VAN codec(`SimVanMessages`)은 코드를 **쓰기만** 한다(응답에 기관 코드 없음). 쓰는 값은 검사된 Route 설정·인증 결과에서 오므로 codec 자체 검사는 더하지 않는다. Simulator VAN codec이 읽는 쪽에서 FRAMING으로 거절한다(IC-8).
- PAYMENT 인증 결과의 바인딩 echo `cardCompany`는 형식 검사 대신 기존 바인딩 대조(D-AUTHN-003)로 거절된다 — 보낸 값은 형식을 따르는 allowlist 값뿐이다.
- 형식 정의: PAYMENT `payment.adapter.integration.institution.InstitutionCodeFormat`(internal object — Route 등록부·인증 설정·인증 결과 adapter), `entitlement.domain` Snapshot 해석의 private 상수(모듈 경계를 넘는 공유 타입을 만들지 않음). MASTER는 `merchant.domain.InstitutionCodeFormat`(internal) — `AcquirerCode`·`RouteRef`가 쓴다.

## 7. golden 재생성(하네스 독립 생성)

| 생성기 | 변경 | 다시 쓴 파일 |
|---|---|---|
| `harness/tests/test_authentication_contract_golden.py` | 매핑 적용, `malformedLookups`에 `hyphenatedAcquirerCode`·`underscoreIssuerCode`·`hyphenatedProviderCode` 추가, 대상에 `master-api` 추가(IC-3) | `institution-simulator`·`payment-api`·`master-api` `contracts/authentication-contract-v1.golden.json` |
| `harness/tests/test_van_contract_golden.py` | 매핑 적용. sim-van-1 파일 고정 hash `SIM_VAN_1_SHA256`을 새 파일 hash로 바꾼다 — **결정된 계약 변경**(m-7 고정은 "검토 없는 변경" 방지용, 이번 변경 근거 = D-INST-CODE-001). sim-van-2는 sim-van-1 벡터를 그대로 포함하는 관계 유지 | 두 앱 `van-contract-sim-van-1.golden.json`·`van-contract-sim-van-2.golden.json` |
| `harness/tests/test_entitlement_golden.py` | `routeRef` 매핑(`SIMCARD01`은 그대로) | `payment-api` v2·v3, `master-api` v3(v1은 cardRoutes 없음 — 바이트 불변) |
| `harness/tests/test_institution_code_format.py`(신규) | IC-3·IC-9·IC-10 정적 교차 검사(파일 읽기만) | — |

## 8. 시험 기대값 변경(결정된 형식 변경만)

1. 모든 시험·설정의 대상 코드 문자열을 §3 매핑으로 바꾼다(`master-api`·`payment-api`·`institution-simulator` 시험 코드, `payment-api` 시험 `config/application.yml`의 카드사·Route 키, `.env.example` Route 키). 판정·상태·금액 기대값은 바꾸지 않는다.
2. `AuthorizationTermsSpec`: 기존 `AcquirerCode("lower")` 거절은 유지(IC-1에서 확장).
3. Flyway 적용 버전 목록에 `"7"` 추가(§5).
4. sim-van-1 golden 고정 hash 갱신(§7).
5. 강화만 있다: 이전에 허용되던 하이픈·밑줄·소문자·33~64자 Route가 거절된다. 완화된 기대값 없음.

참고 1(운영 메모, 결정 아님): 로컬 `.env`(git 무시 파일, 사용자 소유)에 `SIM-VAN-01` Route가 있으면 payment-api 기동이 실패한다(IC-6) — `.env.example`처럼 `SIMVAN01`로 바꾸고, 로컬 MASTER·PAYMENT·Simulator DB는 초기화한다. 이 작업은 사용자 `.env`를 고치지 않는다.

## 9. 다른 작업과의 경계

- DEMO-SEED-001(미커밋 작업 트리): `DemoMerchantSeedValues.kt`(`SIMACQ-A`·`SIM-VAN-01`), `DemoMerchantSeedServiceSpec.kt`, `DEMO-SEED-001` 문서는 고치지 않는다. 이 작업 뒤 MASTER 값 객체가 `SIMACQ-A`·`SIM-VAN-01`을 거절하므로 DEMO-SEED-001은 재개 때 §3 매핑(`SIMACQA`·`SIMVAN01`)으로 갱신하고, seed 매입사 = Simulator 카탈로그 매입사 단언을 두는 것을 권장한다.
- 이전 명세(AUTH-001·AUTHN-001·CANCEL-001·MASTER-SNAPSHOT-002)의 예시 코드는 고치지 않는다(완료 작업의 명세 hash 보존). 이 문서 §3이 새 형식의 기준이다.

## 10. 가역적 구현 결정

| ID | 결정 |
|---|---|
| I-1 | V7 CHECK 이름은 `_code` 접미사(§5 — 같은 이름 재생성에 기대지 않음) |
| I-2 | 형식 정의는 앱별 한 곳(MASTER domain internal, PAYMENT 모듈별 한 곳, Simulator는 기존 상수 수정). 앱 간 공유 라이브러리를 만들지 않는다 — 앱은 Gradle 의존 없이 계약(golden)으로만 공유한다 |
| I-3 | 인증 결과의 `evidenceRef` 길이 규칙(1..128)은 그대로 — 기관 식별 코드가 아님 |
| I-4 | MASTER 값 객체 오류 메시지에 입력 원문을 넣지 않는다(기존 관례) |

## 11. 8대 원칙(이번 변경 범위)

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 매입사 코드가 MASTER 개통·Snapshot·인증 결과·VAN 전문에서 같은 문자열이라 Route 선택(매입사 일치)이 형식 차이로 갈라지지 않는다(IC-3) |
| Transaction Safety | 코드 변경만 — TX 경계·외부 호출 위치 변화 없음. V7은 단일 원자 DDL |
| Idempotency | VAN payload hash는 업무 필드 canonical — 코드 값이 바뀌어 golden hash가 재생성됐다(같은 요청 재전송 = 같은 hash 관계 유지, golden 시험) |
| Concurrency Control | 변화 없음 |
| Recoverability | 형식 밖 값은 경계에서 fail-closed(계약 위반·MALFORMED·FRAMING·기동 실패). 모르는 Route는 기존처럼 null(연결 안 함) — 무효과로 단정하지 않는다 |
| Reconciliation | 기관 namespace(`institution`) 형식 고정 — 대사 키 문자열이 앱 사이에서 같다 |
| Auditability | 개통 이력 표 불변. 기존 행 자동 변환 없음(근거 보존) |
| Observability | 변화 없음(코드 값은 로그에 Demo 식별자로만, 기관 계약계정은 계속 가림) |

Kafka/Redis 도입 없음 — 이 변경과 무관하다.
