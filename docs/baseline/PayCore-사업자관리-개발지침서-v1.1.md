# PayCore 사업자 기본정보 관리 — 기능 구현·Claude Code 개발 지침서

- 문서 ID: PC-BIZ-DEV-001 / 버전 v1.1 / 2026-11-17
- 상위 정책: `01_PayCore_사업자관리_업무정의_정책서.md`
- 목표: **PayCore 신규 프로젝트의 사업자 기본정보 기능**을 독립 구현한다.

> **필수 전제**
> - 기존 IMS와 신규 PayCore는 아무 연관이 없다. 기존 IMS를 탐색·참조·연동·마이그레이션하지 말 것.
> - 첨부된 레거시 화면은 업무 항목 분류를 위한 참고일 뿐이다.
> - 1차 신규 생성 관계는 `Business 1 : BusinessRegistration 1`이며, 본점·지점 동일 법적 주체 판정/연결·병합은 **구현하지 않는다**(TBD).
> - AML/KYB 심사 요청·승인과 계약/MID/정산 연동은 **후속 개발**이다. 이번에 심사 요청 API, AML 호출, 더미 승인, 자동 개통을 만들지 않는다.

## 1. 개발 착수 순서 (순서 변경 금지)

### Step 0 — PayCore 코드베이스만 분석

1. 현재 열려 있는 **PayCore 프로젝트 루트**와 README, 빌드 파일을 확인한다.
2. 기술스택(프론트·백엔드·DB), 프로젝트 구조, 인증·권한, 공통 오류·응답, 테스트·마이그레이션 방식을 파악한다.
3. 사업자 관련 기존 클래스·테이블·API의 존재 여부를 확인하고 **PayCore 내부에서만** 재사용 대상을 찾는다.
4. 레거시 IMS 경로나 기존 IMS DB 접근을 시도하지 않는다.
5. 코드 변경 전 설계·충돌사항을 요약한다. 정보가 부족한 부분은 `TBD`로 남긴다.

### Step 1 — 업무/도메인/DB 설계

- Business / BusinessRegistration / Representative / Category / Address / Contact / Document / Verification / Audit를 논리적으로 분리한다.
- API 요청/응답, 검증 단계, 상태 전이, 중복제약, 변경감사를 확정한다.
- 1차 관계는 `Business 1 : BusinessRegistration 1`, 추후 1:N 고려하되 **이번에 복수 등록정보 생성 기능은 노출하지 않는다.**
- 식별키는 내부 ID를 사용하고 사업자번호를 PK로 사용하지 않는다.
- DB DDL은 기존 PayCore 표준 마이그레이션 프로세스를 따른다.

### Step 2 — P0 구현 및 검증

사업자 등록, 임시저장, 등록완료, 목록·상세조회, 수정, 중복확인, 대표자/업태/종목/주소/담당자 관리, 권한·감사이력부터 구현한다.

### Step 3 — P1 구현

사업자등록증 업로드 및 조회, 국세청 진위·영업상태 조회를 **실제 연동 요건이 준비된 경우** 추가한다. 연동 준비가 안 됐으면 인터페이스와 `NOT_CHECKED`를 유지하고, 성공 결과를 가짜로 반환하지 않는다.

## 2. 기능 요구사항

| ID | 기능 | 필수 규칙 | 우선순위 |
|---|---|---|---|
| F-001 | 사업자 목록/검색 | 사업자번호·상호·상태, 페이지·정렬 | P0 |
| F-002 | 신규등록/임시저장 | 부분 입력 허용, 형식 검증 | P0 |
| F-003 | 등록 완료 | 필수항목·중복·상태 전이 검증 | P0 |
| F-004 | 상세조회·수정 | 낙관적 잠금, 변경사유, 감사이력 | P0 |
| F-005 | 내부 중복확인 | 동일 사업자번호 기존 사업자 안내 | P0 |
| F-006 | 대표자/담당자 | 복수 대표자, 기본 등록 담당자 1명 | P0 |
| F-007 | 주소·업태·종목 | 주소 유형·복수 업태/종목 | P0 |
| F-008 | 인증·권한 | 조회·등록·수정·비활성화 권한 분리 | P0 |
| F-009 | 파일 증빙 | 권한 검사·파일검증·접근 제한 | P1 |
| F-010 | 진위·영업상태 확인 | 미검증과 통신실패 정확하게 구분 | P1/외부 조건 |
| F-011 | 사용중지 | 사유 기록·허용 상태 검증 | P1 |

**이번 범위에서 제외:** AML/KYB 고객확인 등록·심사 요청·승인, 실제소유자 수집, Merchant 생성, 계약, MID/TID 발급, 원천사 매핑, 결제서비스, 수수료·담보·정산 정책, 본점·지점 동일주체 판단.

## 3. 화면 및 UX 구성

**메뉴:** PayCore 관리자 > 사업자 관리 > 사업자 기본정보

### 목록
- 검색: 사업자번호, 상호, 개인/법인, 등록상태, 등록일자
- 표시: Business ID, 사업자번호, 상호, 대표자, 사업자유형, 상태, 수정일
- 검색 결과는 서버 페이징; 안정적 고정 정렬 키 사용
- 신규등록, 상세조회, 편집(권한 있는 경우)

### 등록/수정 폼 (섹션)
1. **사업자 기본:** 개인/법인, 사업자번호+중복조회, 상호, 법인번호(법인만), 개업일, KR, 영문명(선택)
2. **사업 내용:** 업태·종목 각각 추가/삭제 가능한 복수행
3. **주소:** 사업장 주소, 실사업장 동일 여부, 다른 경우 별도 주소
4. **대표자:** 1명 이상, 단독/공동/각자 유형, 복수행
5. **사업체 연락:** 사업체 연락처 필수, 이메일 선택
6. **등록 담당자:** 이름·이메일·연락처 필수
7. **증빙:** 사업자등록증 선택 첨부 (P1)
8. **버튼:** 임시저장 / 등록 완료 / 취소

- 등록 완료 후 상세로 이동하고 `REGISTERED` 표시.
- 중복 확인 결과는 입력 값이 바뀌면 즉시 무효화.
- 프론트와 서버 양쪽에서 형식검증, 서버에서 전체 업무검증 및 인가.
- 프로젝트가 React라면 기존 라우터/폼/상태관리/UI 컴포넌트 규칙을 따른다. 기존 스택이 다르면 임의로 React를 추가하지 않는다.

## 4. 등록 정책과 상태 머신

- `DRAFT` → `DRAFT` (부분수정)
- `DRAFT` → `REGISTERED` (등록 완료 및 전체 필수값 통과)
- `REGISTERED` → `INACTIVE` (관리상 중지, 권한·사유 필요)
- `INACTIVE` → `REGISTERED`는 TBD; 임의 구현 금지.

`REGISTERED`는 **내부 기본정보 등록 완료**만 뜻한다. AML/KYB, 계약, MID가 승인된 상태가 아니다. 서비스 개통 코드와 연동하지 않는다.

### REGISTERED 필수값
- 사업자유형, 사업자등록번호, 상호, 개업일, 사업장 주소, 사업체 연락처
- 법인일 때 법인등록번호
- 현재 유효 대표자 1명 이상
- 업태 1건 이상 및 종목 1건 이상
- 등록 담당자(이름·이메일·연락처) 1명 이상
- 사업자등록증은 현재 선택. 심사 요청 시 필수 조건은 **후속 정책**.

## 5. API 초안 (기존 PayCore API 규칙 우선)

| HTTP | 예시 경로 | 동작 |
|---|---|---|
| GET | `/api/v1/businesses` | 조건검색/페이징 |
| POST | `/api/v1/businesses` | DRAFT 또는 유효한 REGISTERED 생성 |
| GET | `/api/v1/businesses/{businessId}` | 상세조회 |
| PUT | `/api/v1/businesses/{businessId}` | 수정, 버전·변경사유 필수 |
| POST | `/api/v1/businesses/{businessId}/register` | DRAFT → REGISTERED |
| GET | `/api/v1/business-registrations/duplicates?businessNo=...` | 내부 중복조회 |
| GET | `/api/v1/businesses/{businessId}/histories` | 감사이력 |
| POST | `/api/v1/businesses/{businessId}/deactivate` | 관리상 중지(P1) |
| POST | `/api/v1/businesses/{businessId}/documents` | 사업자등록증(P1) |
| GET | `/api/v1/businesses/{businessId}/documents` | 문서목록(P1) |
| GET | `/api/v1/businesses/{businessId}/documents/{documentId}/download` | 안전한 다운로드(P1) |

외부 진위·상태 조회 API는 **연동 도입 결정 이후** 구체적 요청/응답 및 경로를 추가한다. **심사요청·AML API는 이번에 정의하거나 구현하지 않는다.**

예시 등록 DTO (구조 제안):

```json
{
  "registrationStatus": "DRAFT",
  "businessType": "CORPORATION",
  "registration": {
    "businessRegistrationNumber": "1234567890",
    "corporateRegistrationNumber": "1234567890123",
    "businessName": "예시상점",
    "openingDate": "2026-11-17",
    "countryCode": "KR",
    "phoneNumber": "0212345678"
  },
  "representatives": [{"name":"홍길동","representativeType":"SOLE"}],
  "categories": [
    {"categoryType":"BUSINESS_TYPE","name":"도소매"},
    {"categoryType":"BUSINESS_ITEM","name":"전자상거래"}
  ],
  "addresses": [{"addressType":"REGISTERED","postalCode":"00000","baseAddress":"예시 주소"}],
  "contacts": [{"name":"김담당","email":"example@example.com","phoneNumber":"01012345678","primary":true}]
}
```

- DRAFT 최초 생성 최소 필드 규칙은 TBD. 확정 전까지 부분 입력 허용 설계와 최소 유효 요청값을 구분해 문서화한다.
- 동일 요청 반복/버튼 더블클릭에 의한 중복 생성을 방지한다. 가능하면 idempotency key 사용.
- 공통 오류 규약이 있다면 신규 오류코드·응답 래핑은 기존 규약에 맞춘다.

## 6. DB 논리 모델 (새 DB 설계안, DDL 확정 전 점검 필수)

| 테이블 제안 | 핵심 필드 | 주요 제약 |
|---|---|---|
| `BUSINESS` | business_id PK, business_type, registration_status, version, created_at/by, updated_at/by | 1차 Business와 Registration 1:1 생성 |
| `BUSINESS_REGISTRATION` | registration_id PK, business_id FK, business_no, corporate_no, business_name, opening_date, country_code, phone, email | 사업자번호 중복 금지, 1차 business_id UNIQUE |
| `BUSINESS_REPRESENTATIVE` | representative_id PK, business_id FK, name, representative_type, effective_from/to, display_order | 등록 완료 시 1명 이상 |
| `BUSINESS_CATEGORY` | category_id PK, registration_id FK, category_type, name, display_order | 업태/종목 각 1개 이상 |
| `BUSINESS_ADDRESS` | address_id PK, registration_id FK, address_type, postal_code, base_address, detail_address | 등록주소 필수 |
| `BUSINESS_CONTACT` | contact_id PK, business_id FK, name, email, phone, contact_role, is_primary | 기본 등록담당자 필수 |
| `BUSINESS_DOCUMENT` | document_id PK, business_id FK, doc_type, storage_key, file metadata, created_at/by | P1, 비공개 저장 |
| `BUSINESS_VERIFICATION` | verification_id PK, registration_id FK, type, status, provider, checked_at, input_fingerprint | P1, 실제 검증만 기록 |
| `BUSINESS_AUDIT` | audit_id PK, business_id, action, field, masked_before/after, reason, changed_at/by | 수정·상태변경 시 필수 |

### 제약 조건 결정 규칙

1. 사업자번호는 하이픈 없이 숫자 10자리로 정규화한다.
2. 앱에서 중복검사를 하더라도 **DB 유니크 제약**으로 동시 등록 중복을 방지한다.
3. 사업자번호 정정·변경 시 동일번호의 이력 구조 및 유니크 정책은 TBD. 구현 중 임의의 물리삭제·재사용을 허용하지 않는다.
4. 트랜잭션은 Business 및 필수 하위 엔티티와 감사이력 전체를 원자적으로 저장한다.
5. 파일 업로드와 외부 API 호출은 긴 DB 트랜잭션 안에서 수행하지 않는다.
6. 낙관적 잠금으로 동시 수정 충돌을 처리한다.
7. DB 컬럼·인덱스·ENUM은 PayCore의 기존 네이밍·마이그레이션 관례에 맞춘다.

## 7. 보안·권한·감사

- PayCore 관리자 인증 및 권한을 사용한다. **구 IMS 권한을 조회·연동하지 않는다.**
- 조회/등록/수정/비활성화/파일접근 권한을 구분하고 모든 요청에서 서버가 검사한다.
- 사업자번호, 연락처, 대표자 정보는 로그와 감사기록에 필요한 범위만 마스킹·보호한다.
- 변경 사유와 작업자 식별자를 서버에서 확정하고 클라이언트 입력을 그대로 신뢰하지 않는다.
- 파일명 경로조작, 비정상 확장자, MIME 위장, 과도한 파일크기를 방어한다.
- 보존기간·파기 정책 미확정 부분은 임의 숫자로 구현하지 않는다.

## 8. 예외·트랜잭션

| 상황 | 기대 처리 |
|---|---|
| 동일 사업자번호 동시 생성 | 1건만 성공, 다른 요청 409 등 충돌 반환 |
| 부분 저장 중 하위 엔티티 실패 | 전체 롤백 |
| 등록 완료 필수값 누락 | 저장/상태전이 차단, 필드별 오류 반환 |
| 법인인데 법인등록번호 누락 | 등록 완료 차단 |
| 이미 존재하는 Business 조회 | 기존 ID를 안내, 자동 복제 금지 |
| 두 사용자가 동시 수정 | 버전 충돌로 덮어쓰기 방지 |
| 등록 후 사업자번호 변경 | 직접 변경 제한, 정책 미정 알림 |
| 진위확인 연동 미구축 | NOT_CHECKED 유지, VERIFY 성공을 위조하지 않음 |
| 등록 후 AML/KYB 상태 조회 | 이번에 제공하지 않음 |
| 등록 후 MID·계약 조회 | 이번에 생성하지 않음 |

## 9. 테스트 계획 및 완료 기준

| ID | 시나리오 | 기대 |
|---|---|---|
| TC-01 | 개인사업자 등록 완료 | 정상 등록 |
| TC-02 | 법인사업자 등록 완료 | 정상 등록 |
| TC-03 | 법인등록번호 누락 | 등록 완료 실패 |
| TC-04 | 임시저장 시 필수값 누락 | 저장 성공 |
| TC-05 | 등록완료 시 필수값 누락 | 실패 |
| TC-06 | 동일 사업자번호 재등록 | 기존 사업자 안내·생성 차단 |
| TC-07 | 동일 번호 동시 등록 | DB 기준 1건만 성공 |
| TC-08 | 공동대표 2명 등록 | 정상 저장·조회 |
| TC-09 | 현재 대표자 0명 | 등록 완료 실패 |
| TC-10 | 업태·종목 복수 등록 | 정상 저장 |
| TC-11 | 등록 담당자 누락 | 등록 완료 실패 |
| TC-12 | 일반정보 수정 | 변경 이력 기록 |
| TC-13 | 중요정보 변경 요청 | 정책에 따른 제한 및 사유 기록 |
| TC-14 | 버전 충돌 | 덮어쓰기 거부 |
| TC-15 | 미인가 등록·수정 | 접근 거부 |
| TC-16 | 등록 완료 재요청 | 상태 이상·중복 데이터 없음 |
| TC-17 | 중간 저장 오류 | 전체 롤백 |
| TC-18 | 등록 직후 조회 | Business / Registration 1:1 |
| TC-19 | 등록 직후 KYB·계약·MID 데이터 | 자동 생성 없음 |
| TC-20 | 기존 IMS 연결 코드 검사 | 연동·이관·의존성 없음 |
| TC-21 | 진위확인 미연동 | 미검증 표시 유지 |
| TC-22 | 파일 업로드/다운로드(P1) | 형식·권한 검증 |

## 10. Claude Code 실행 요청문

다음 작업을 **PayCore 프로젝트 소스 기준**으로 진행하라.

1. `01_PayCore_사업자관리_업무정의_정책서.md`와 이 개발 지침서를 먼저 읽는다.
2. 현재 열려 있는 PayCore 프로젝트의 기술스택, 화면 구조, DB, 인증/권한/마이그레이션 방식과 관련 코드만 분석한다.
3. **기존 IMS와 PayCore는 완전히 독립**이다. 구 IMS 코드를 탐색하거나 재활용하거나 연동 또는 데이터 이관하지 않는다.
4. 1차 사업자 등록에서 Business : BusinessRegistration을 **1:1로 생성**한다. 본점·지점의 동일 법적 주체 판정과 연결은 TBD로만 남기고 구현하지 않는다.
5. AML/KYB 심사 연동, 심사 요청·승인, 실제소유자, 계약, Merchant 생성, MID 발급, 결제서비스·정산 설정은 **이번 작업 범위에서 제외**한다.
6. 우선 기존 코드 분석 결과, 신규 DB 논리 모델, API 명세, 화면 컴포넌트 구성, 정책 미정사항, 예상 변경 파일 목록을 산출한다.
7. 승인된 범위 내에서 단계별로 구현하고, 각 단계마다 테스트와 변경 파일 목록을 보고한다.
8. 미확정 업무는 임의 구현하거나 성공 응답으로 가장하지 않는다.

**완료 보고 형식:** 구현 기능 / 변경 파일 / 설계 결정 / DB 변경 / 테스트 실행 결과 / 남은 TBD / 기존 PayCore 기능 영향.
