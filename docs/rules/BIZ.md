# BIZ — 사업자·가맹점 기준정보 업무 규칙 대장 (초안 rev 0.1)

- 작성: 2026-11-25, RULE-GOV-001 2단계 추출(문서만 — 코드·DECISIONS·questions·명세 변경 없음). 사용자 확인 전 초안이며, `확정` 표시는 "근거 결정이 사용자 답(또는 baseline 확정 문서)"이라는 뜻이다.
- 형식: `docs/rules/README.md`. 원천: `docs/DECISIONS.md`(D-MD·MS·MS2·MOPS·MCODE·BIZ·BIZLOOKUP·FLOW·ADMIN-UI 업무 의미 부분·PII-001), `harness/questions.json`, baseline(도메인구조-Seed-MID-Site 확정본 = "구조", 초기-확정사항 = "초기", 사업자관리 정책서 PC-BIZ-POL-001 = "POL", 개발지침서 PC-BIZ-DEV-001 = "DEV"), 명세, `apps/master-api`·`apps/internal-admin`·`apps/payment-api` 시험.
- 검증 열: `시험파일::시험 요약`. 파일 위치는 각 앱 `src/test`(화면 시험은 `apps/internal-admin/frontend/src`). (M)=master-api, (A)=internal-admin, (P)=payment-api, (F)=frontend. 같은 이름 파일이 두 앱에 있으면 표시.
- 결제 허용 판정(§9)은 PAYMENT가 판정하지만 입력 조건이 D-MD/D-MS 결정이라 여기에 둔다. PAY 대장과 중복 정리는 진단 (b)-7 참고.

## 1. 사업자(Business) 기본정보·상태

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-001 | 하나의 Business는 여러 Merchant를 가질 수 있고, 같은 사업자의 모든 브랜드를 한 Merchant로 강제하지 않는다 | 확정 | 기준 | 초기 §가맹점·계약·MID | docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md §2.2 Business | MerchantOperationsApiSpec(M)::B1 등록 단계별 SUCCEEDED(같은 사업자에 가맹점 추가) — 직접 단언 없음 |
| BR-BIZ-002 | 사업자등록번호는 ASCII 숫자 10자리만 받고 하이픈·전각 등 다른 표기는 정규화 없이 거절한다 | 확정 | 사용자 | D-MD-008, POL §5 | MASTER-DOMAIN-001 §2.2, BUSINESS-PROFILE-001 §4.2 | MasterValuesSpec(M)::10자리 숫자만, 하이픈·전각 거절 |
| BR-BIZ-003 | 사업자등록번호당 Business는 하나이며(UNIQUE), 동시 요청에서도 DB 제약으로 하나만 커밋되고 나머지는 중복 거절(기존 Business 참조)이 된다 | 확정 | 사용자 | D-MD-008, POL POL-001, DEV §6 | BUSINESS-PROFILE-001 §5, §6.7 | BusinessProfileConcurrencySpec(M)::n 연결 중 1건 SUCCEEDED 나머지 DUPLICATE; MerchantOperationsConcurrencySpec(M)::같은 번호 다른 키 1건만 |
| BR-BIZ-004 | 사업자등록번호는 저장 후 변경할 수 없다(DRAFT 오입력 포함, 정정·해제 정책은 TBD-04·05 결정 전까지 기능 없음) | 확정 | 사용자 | D-BIZ-005, POL POL-004·TBD-04 | BUSINESS-PROFILE-001 §3.3, §4.5 | BusinessHttpSpec(A)::수정 본문의 registrationNumber는 400 |
| BR-BIZ-005 | Business 식별자와 사업자등록번호를 같은 것으로 취급하지 않으며, 1차는 Business 1건에 등록정보 1건만 만든다 | 확정 | 기준 | POL §1-3·§3 | BUSINESS-PROFILE-001 §3.3 TBD-03 | BusinessSchemaSpec(M)::registration 표 사업자당 1행 |
| BR-BIZ-006 | 법인등록번호 일치만으로 사업자를 자동 연결·병합하지 않는다(본점·지점 판정은 후속) | 확정 | 기준 | POL §1-4, TBD-01 | BUSINESS-PROFILE-001 §3.3 | 없음 |
| BR-BIZ-007 | 사업자 생명주기(ACTIVE/TERMINATED, 종료 비가역)와 등록 상태(DRAFT/REGISTERED/INACTIVE)는 별도 축이다 | 확정 | 사용자 | D-BIZ-001, D-MD-006 | BUSINESS-PROFILE-001 §4.4 | BusinessProfileChangeSpec(M)::생성 시 두 상태 축·version 0 |
| BR-BIZ-008 | REGISTERED는 내부 기본정보 등록 완료일 뿐 진위확인·AML/KYB·계약 승인이 아니다 | 확정 | 사용자 | D-BIZ-001, POL §4 | BUSINESS-PROFILE-001 §3.3 TBD-02 | 없음(화면 문구 시험만 — 업무 단언 아님) |
| BR-BIZ-009 | DRAFT 최초 생성의 최소 입력은 사업체 구분(개인/법인)·사업자등록번호·상호이고, 그 밖 항목은 선택이되 입력된 값의 형식은 검사한다 | 확정 | 사용자 | D-BIZ-002, POL §4·§5 | BUSINESS-PROFILE-001 §4.3 | BusinessProfileRulesSpec(M)::TC-04 DRAFT 최소 3항목, 빠지면 REQUIRED |
| BR-BIZ-010 | REGISTERED는 POL §5 필수 항목(구분·상호·번호·개업일·업태·종목 각 1개 이상·사업장 주소·사업체 연락처·현재 대표자 1명 이상·등록 담당자 1명 이상)을 모두 충족해야 하며, REGISTERED 수정도 필수값을 지우면 거절한다 | 확정 | 기준 | POL §5·POL-003, DEV §4 | BUSINESS-PROFILE-001 §4.3·§4.4 | BusinessProfileRulesSpec(M)::TC-05·TC-09·TC-11 필수 누락; BusinessProfileApiSpec(M)::REGISTERED 수정에서 대표 담당자 제거 400 |
| BR-BIZ-011 | 법인 사업자는 REGISTERED에 법인등록번호(13자리 숫자)가 필수다 | 확정 | 기준 | POL §5, §9-1 | BUSINESS-PROFILE-001 §4.2·§4.3 | BusinessProfileRulesSpec(M)::TC-03 법인 REGISTERED 법인등록번호 필요 |
| BR-BIZ-012 | 1차 사업자 대상은 국내 개인·법인사업자이며 등록국가는 KR만 허용한다 | 확정 | 기준 | POL §1-6, §5, TBD-10 | BUSINESS-PROFILE-001 §3.3 | BusinessProfileRulesSpec(M)::country KR only |
| BR-BIZ-013 | 진위확인 결과는 등록 상태와 별도 속성이며, 연동 전에는 NOT_CHECKED만 가능하고 외부기관 통신 실패를 검증 성공으로 보지 않는다 | 확정 | 기준 | POL POL-002·POL-005, TBD-06 | BUSINESS-PROFILE-001 §3.3 | BusinessProfileChangeSpec(M)::TC-21 NOT_CHECKED only; BusinessSchemaSpec(M)::NOT_CHECKED 외 3819 |
| BR-BIZ-014 | 상태 전이는 생성→DRAFT/REGISTERED, DRAFT→DRAFT, DRAFT→REGISTERED, REGISTERED→REGISTERED만 허용하고, 등록 완료는 DRAFT에서만 된다(REGISTERED 재요청은 BUSINESS_NOT_DRAFT) | 확정 | 기준 | DEV §4, POL §4 | BUSINESS-PROFILE-001 §4.4 | BusinessProfileChangeSpec(M)::M-1 REGISTERED는 다시 완료 불가; BusinessProfileApiSpec(M)::다른 키 BUSINESS_NOT_DRAFT |
| BR-BIZ-015 | INACTIVE 전이·재활성화는 정책(TBD-08) 결정 전까지 만들지 않으며, INACTIVE가 생겨도 결제·정산 정지로 연결하지 않는다 | 확정 | 기준 | POL §4·POL-006, DEV §4, TBD-08 | BUSINESS-PROFILE-001 §3.3·§4.4 | 없음 |
| BR-BIZ-016 | 상호·대표자·개업일·법인등록번호 등 중요정보 변경에는 사유가 필수이고 변경 전후 이력을 남긴다(일반정보 변경은 사유 선택) | 확정 | 기준 | POL POL-004 | BUSINESS-PROFILE-001 §4.5 | BusinessProfileServiceSpec(M)::TC-12 일반 변경 사유 없이 성공, TC-13 중요 변경 사유 없으면 거절 |
| BR-BIZ-017 | 사업체 구분 변경과 REGISTERED 사업자의 대표자 추가만 하는 변경도 중요정보 변경으로 본다 | 제안 | 적용 | BUSINESS-PROFILE-001 C-12·M-2(Coordinator·리뷰) | BUSINESS-PROFILE-001 §4.5 | BusinessProfileChangeSpec(M)::TC-13 구분 변경 중요, M-2 대표자 추가만도 중요 |
| BR-BIZ-018 | 중요정보 변경은 2인 승인·재검증 없이 OPERATOR 1인 + 사유 + 전후 이력으로 처리한다(TBD-07 결정 전 기본값) | 제안 | 적용 | POL TBD-07, BUSINESS-PROFILE-001 §3.3 | BUSINESS-PROFILE-001 §3.3 | BusinessProfileServiceSpec(A)::CREATE=UPDATE=COMPLETE={OPERATOR} |
| BR-BIZ-019 | 사업자 수정은 version 비교로 동시 수정 덮어쓰기를 거절한다(BUSINESS_VERSION_CONFLICT) | 확정 | 기준 | POL POL-004, DEV §8 | BUSINESS-PROFILE-001 §4.5 | BusinessProfileConcurrencySpec(M)::1건 성공 1건 VERSION_CONFLICT; BusinessProfileChangeSpec(M)::version 불일치 거절 |
| BR-BIZ-020 | 등록 완료 사업자는 물리 삭제하지 않는다 | 확정 | 기준 | POL POL-006 | BUSINESS-PROFILE-001 §5.4 | BusinessSchemaSpec(M)::BIZ-1-6 runtime 계정 DELETE 불가 |
| BR-BIZ-021 | 사업자 등록·수정은 계약·Merchant·MID·권리·정산 데이터를 만들거나 바꾸지 않는다 | 확정 | 기준 | POL §1-2·§9-7 | BUSINESS-PROFILE-001 §10 | BusinessProfileServiceSpec(M)::BIZ-1-14 TC-19 merchant·contract·MID·entitlement 불변 |
| BR-BIZ-022 | 새 가맹점 등록은 REGISTERED 사업자만 허용하고(DRAFT는 BUSINESS_NOT_REGISTERED), 이미 있는 가맹점·계약·MID의 후속 명령은 등록 상태의 영향을 받지 않는다 | 확정 | 사용자 | D-BIZ-001 | BUSINESS-PROFILE-001 §4.4·§6.7 | MerchantRegistrationGateSpec(M)::DRAFT 거절; MerchantRegistrationGateUseCaseSpec(M)::기존 가맹점 후속 명령 성공; MerchantRegistrationGateApiSpec(M)::DRAFT REJECTED |
| BR-BIZ-023 | 사업자 상호(개인 이름 포함 가능)는 마스킹하지 않는다 | 미결(Q-PII-7) | — | PII-MASKING-POLICY §7.3·§8 | BUSINESS-PROFILE-001 L-3 | 없음 |
| BR-BIZ-024 | 사업자 개인정보(종료 행·이력·감사·백업)의 보관·파기 기간은 결정 전까지 파기 기능 없이 보관한다 | 미결(Q-PII-5) | — | POL POL-006, D-FLOW-002 ④ | BUSINESS-PROFILE-001 §3.3 | 없음 |

## 2. 사업자등록번호·사업자 개인정보 노출·조회 감사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-030 | 관리자 화면 전체(사업자·가맹점 목록·상세, 번호 조회 결과, 등록 중복 안내, 가맹점 등록의 사업자 표시, 변경 이력 대상 표시)와 그 값을 주는 ADMIN·MASTER 조회 응답은 사업자등록번호 원문을 보여 준다 | 확정 | 사용자 | D-BIZ-007, D-PII-001 ① | docs/repository/BRN-UNMASK-001-BRN-DISPLAY.md §1·§2, PII-MASKING-POLICY §3 | BusinessShapeGoldenSpec(A)::원문 10자리, 마스킹·하이픈 거부; MerchantLookupHttpSpec(A)::응답 원문·감사 마스킹; MerchantsScreen.test.tsx(F)::이력 대상 상호·번호 |
| BR-BIZ-031 | 가맹점용 화면·외부 API·내보내기의 사업자등록번호 표시는 그 채널이 생길 때 따로 정한다 | 확정 | 사용자 | D-PII-001 ① | BRN-UNMASK-001 §1 | 없음(해당 채널 없음) |
| BR-BIZ-032 | 감사 detail·변경 이력 저장값·로그·지표·오류 메시지의 사업자등록번호는 앞 3자리만 남기고 마스킹(형식 오류는 INVALID)한다 | 확정 | 사용자 | D-BIZ-007, D-PII-001, D-MD-008, D-MOPS-006 ④ | BRN-UNMASK-001 §3 | BusinessHttpSpec(A)::canary — ADMIN 문자열 열 0건, 감사 brn=731*******; BusinessProfileApiSpec(M)::canary 로그·admin_request·이력·outbox 0건 |
| BR-BIZ-033 | Outbox·통합 이벤트 payload에 사업자등록번호를 싣지 않는다 | 확정 | 사용자 | D-MD-008 | MASTER-DOMAIN-001 §2.2 | MerchantOnboardingServiceSpec(M)::번호는 이력에 마스킹만, outbox payload에 없음 |
| BR-BIZ-034 | 사업자등록번호를 식별자로 쓰지 않는다 — 조회 응답에 내부 사업자 키를 주되 화면에는 표시하지 않고, 원문 번호는 조회 요청에서만 1회 보내며 URL·브라우저 저장소에 두지 않는다 | 확정 | 사용자 | D-BIZLOOKUP-001, D-MCODE-003 ② | MERCHANT-BIZ-LOOKUP-001, BRN-UNMASK-001 §3 | BusinessQueryServiceSpec(A)::조회 문서에 businessId·registrationStatus; MerchantsScreen.test.tsx(F)::MC-2b DOM에 biz_ 없음; BusinessProfileFormScreen.iaB5b.test.tsx(F)::DOM·URL·저장소에 원문 없음 |
| BR-BIZ-035 | 사업자등록번호 정확 일치 검색(가맹점 검색·사업자 번호 조회)은 OPERATOR·APPROVER만 할 수 있다(VIEWER·SECURITY_ADMIN·UNMASK 단독 제외) | 확정 | 사용자 | D-MOPS-003(Q-MOPS-05), D-BIZ-006 ① | MERCHANT-OPS-001 §5.3, BUSINESS-PROFILE-001 §7 | MerchantLookupServiceSpec(A)::VIEWER·SECURITY_ADMIN 금지·감사; BusinessQueryServiceSpec(A)::VIEWER·SECURITY_ADMIN·UNMASK 403 |
| BR-BIZ-036 | 사업자등록번호 검색·조회는 찾음/못 찾음·형식 오류·권한 거절·한도 초과를 포함한 모든 시도를 감사한다(평문 없음 — 마스킹 값·결과 수·찾으면 대상 businessId) | 확정 | 사용자 | D-MOPS-003(Q-MOPS-06·07) | MERCHANT-OPS-001 §5.2·§5.4 | MerchantLookupServiceSpec(A)::찾음·못 찾음·INVALID·UNAVAILABLE·RATE_LIMITED 감사; MerchantLookupHttpSpec(A)::VIEWER 형식 오류도 FORBIDDEN 감사 |
| BR-BIZ-037 | 번호 검색은 oracle로 다룬다 — 찾음/못 찾음 같은 응답 모양, 호출 전 형식 검사, 오류·로그에 값 없음, 속도 제한 | 확정 | 사용자 | D-MOPS-003 | MERCHANT-OPS-001 §5.5 | MerchantOpsApiSpec(M)::찾음·못 찾음 같은 모양; BusinessReaderSpec(M)::번호 조회 0/1건 같은 모양 |
| BR-BIZ-038 | 번호 검색·사업자 번호 조회·사업자 등록(중복 결과 포함)은 하나의 공유 횟수 제한 버킷을 쓴다 | 확정 | 사용자 | D-MOPS-006 ④, D-BIZ-003, D-BIZ-007 | BUSINESS-PROFILE-001 §7.3, MERCHANT-OPS-002 C-13 | BusinessQueryServiceSpec(A)::C-3 공유 버킷 — 조회·가맹점 번호 검색·사업자 등록이 같은 버킷 |
| BR-BIZ-039 | 번호 검색 요청 본문 크기 상한(16 KiB)을 넘으면 해석 없이 거절하고 감사하지 않는다 | Demo값 | 사용자 | D-MOPS-003(Q-MOPS-08) | MERCHANT-OPS-001 §5.2 | MerchantLookupHttpSpec(A)::16 KiB 초과 413, 감사 없음 |
| BR-BIZ-040 | 가맹점·사업자 상세 열람은 매번 감사하고(MERCHANT_VIEWED·BUSINESS_VIEWED, 감사 실패 시 결과 미반환), 목록·변경 이력 조회는 원문 번호가 보여도 감사하지 않는다 | 확정 | 사용자 | D-MOPS-002, D-BIZ-006 ②, D-PII-001 ② | MERCHANT-OPS-001 §5.1, BUSINESS-PROFILE-001 §7.4 | MerchantLookupServiceSpec(A)::FOUND·NOT_FOUND MERCHANT_VIEWED, 감사 실패 시 미반환; BusinessQueryServiceSpec(A)::BUSINESS_VIEWED 반환 전, 목록·이력 감사 없음 |
| BR-BIZ-041 | 사업자 목록·상세·이력 조회는 OPERATOR·APPROVER, 사업자 등록·수정·등록 완료는 OPERATOR만 할 수 있다 | 확정 | 사용자 | D-BIZ-006 ①, D-ADMIN-UI-004 ④ | BUSINESS-PROFILE-001 §7 | BusinessProfileServiceSpec(A)::VIEW={OPERATOR,APPROVER}, CREATE=UPDATE=COMPLETE={OPERATOR}; BusinessHttpSpec(A)::APPROVER 조회 200·명령 403 |
| BR-BIZ-042 | 사업자 상세는 조회 역할에 대표자·담당자 이름·전화·이메일·주소·법인등록번호를 원문 표시하고, 상세 열람마다 감사 + 속도 제한을 적용한다 | 확정 | 사용자 | D-BIZ-006 ② | BUSINESS-PROFILE-001 §9 | BusinessReaderSpec(M)::상세 원문 개인정보; BusinessHttpSpec(A)::31번째 상세 429 RATE_LIMITED 감사 |
| BR-BIZ-043 | 사업자 개인정보는 로그·감사·변경 이력에 항상 마스킹 값만 남긴다 | 확정 | 사용자 | D-BIZ-006 ② | BUSINESS-PROFILE-001 §9 | BusinessProfileChangeSpec(M)::이력 값 마스킹; BusinessProfileServiceSpec(A)::canary 로그 0건 |
| BR-BIZ-044 | 사업자 등록에서 번호가 이미 있으면 조회 권한자에게 기존 사업자의 상호·사업자등록번호·상세 링크를 보여 주고(내부 키 화면 미표시), 그 시도는 감사하고 공유 버킷을 쓴다 | 확정 | 사용자 | D-BIZ-003, D-BIZ-007 | BUSINESS-PROFILE-001 §6.7·§7.3 | BusinessHttpSpec(A)::D-BIZ-003 중복 200 REJECTED existing, 감사 대상 기존 businessId; BusinessProfileServiceSpec(A)::버킷 소비 유지 |
| BR-BIZ-045 | 사업자 등록 중복 시 "이미 등록된 번호"만 표시하고 기존 businessId는 ADMIN 응답·화면·감사에 내보내지 않는다 | 대체됨→BR-BIZ-044 | 사용자 | D-MOPS-006 ③ → D-BIZ-003 | MERCHANT-OPS-002 §4.4(옛) | — |
| BR-BIZ-046 | 사업자등록번호는 어디서나(화면·응답 포함) 마스킹한다 | 대체됨→BR-BIZ-030 | 사용자 | D-BIZ-006 ② → D-BIZ-007, D-PII-001 | BUSINESS-PROFILE-001 §9(옛) | — |
| BR-BIZ-047 | 번호 검색·사업자 번호 조회·중복 안내·가맹점 등록의 사업자 표시 응답은 마스킹 번호(앞 3자리)만 준다 | 대체됨→BR-BIZ-030 | 사용자 | D-MOPS-003, D-ADMIN-UI-004 ⑦, D-BIZ-003, D-MCODE-003 ② → D-BIZ-007, D-PII-001 | MERCHANT-OPS-001 §5(옛) | — |
| BR-BIZ-048 | 사업자 명령 요청 지문의 사업자등록번호 자리는 원문 무염 SHA-256이 아니라 MASTER 비밀 키 HMAC 값으로 저장한다(같은 키·다른 내용=충돌 유지, 기존 행 호환 비교는 Demo 한정) | 확정 | 사용자 | D-MOPS-010 ②(Q-MOPS-26) | MERCHANT-OPS-002 §9.29, PII-MASKING-POLICY §7.1 X-2 | 없음(미구현 — 진단 e-1) |
| BR-BIZ-049 | 사업자등록번호 마스킹 해제(UNMASK) 절차는 두지 않는다 | 확정 | 사용자 | D-PII-001 ③, Q-MOPS-04 | PII-MASKING-POLICY §8 | 없음 |

## 3. 가맹점(Merchant)·가맹점 코드

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-060 | Merchant는 소속 Business가 불변이고, 여러 Site와 여러 MID를 가질 수 있다 | 확정 | 기준 | 구조 §13, 초기 | MASTER-DOMAIN-001 §2.2 Merchant | 없음(직접 단언 없음) |
| BR-BIZ-061 | 가맹점 생명주기는 ACTIVE/TERMINATED(종료 비가역)이고 신규 결제 중지는 사유·행위자를 가진 별도 차단 기록이며 여러 건이 독립이다(신규 승인만 막고 조회·취소·정산은 허용) | 확정 | 사용자 | D-MD-006 | MASTER-DOMAIN-001 §2.2 | EntitlementEvaluatorSpec(P)::TERMINATED·차단은 MERCHANT_SUSPENDED(차단 기록 생성 명령은 미구현) |
| BR-BIZ-062 | 같은 사업자 아래 같은 가맹점 이름을 허용한다(중복 클릭은 같은 요청키 재전송으로 막는다) | 확정 | 사용자 | D-MOPS-006 ⑥ | MERCHANT-OPS-002 §4.4 | 없음 |
| BR-BIZ-063 | 가맹점마다 사람이 읽는 가맹점 코드를 등록 때 필수로 발급하며, 코드는 영문 소문자+숫자 14자 이내이고 운영자가 제안값을 고치거나 직접 입력할 수 있다 | 확정 | 사용자 | D-MCODE-001(Q-MC-2·5·6), D-ADMIN-UI-004 ③ | docs/repository/MERCHANT-CODE-001-MERCHANT-CODE.md §3.1·§3.3 | MerchantCodeRegistrationSpec(M)::MC-1-2 가맹점·seq-1 배정 함께 저장 |
| BR-BIZ-064 | 가맹점 코드 형식은 첫 글자 영문, 6~14자(^[a-z][a-z0-9]{5,13}$)이며 예약어(paycore·admin·system·test·live·demo·api·root·null·undefined) 완전·접두 일치는 거절한다 | 제안 | 적용 | MERCHANT-CODE-001 C-1·C-2 | MERCHANT-CODE-001 §3.1 | MerchantCodeSpec(M)::MC-1-1 형식·경계, 예약어 MERCHANT_CODE_RESERVED |
| BR-BIZ-065 | 가맹점 코드는 MASTER 전체(TEST·LIVE·Demo 공통)에서 하나뿐이고, 사용 중·은퇴 코드와 같으면 거절한다(동시 등록도 하나만 성공) | 확정 | 사용자 | D-MCODE-001(Q-MC-3), D-MCODE-002(Q-MC-7) | MERCHANT-CODE-001 §3.2 | MerchantCodeSchemaSpec(M)::현재·은퇴 코드 1062; MerchantCodeRegistrationSpec(M)::MC-1-3 동시 등록 1건만 |
| BR-BIZ-066 | 가맹점 코드는 첫 계약 승인 전까지 2인 승인으로 한 번만 바꿀 수 있고, 첫 계약 승인 뒤에는 불변이다 | 확정 | 사용자 | D-MCODE-002(Q-MC-7) | MERCHANT-CODE-001 §3.7 | MerchantCodeChangeSpec(M)::이미 변경·계약 승인 뒤 LOCKED; MerchantCodeChangeApprovalSpec(M)::MC-1b-3 거절 목록 |
| BR-BIZ-067 | "첫 계약 승인"은 그 가맹점 대상 계약의 어느 Revision이든 승인 사실이 하나라도 있는 것이다(효력 시작 전 승인 포함, 철회·종료 뒤에도 승인 뒤로 본다) | 제안 | 적용 | MERCHANT-CODE-001 C-14 | MERCHANT-CODE-001 §3.7.1 | MerchantCodeChangeApprovalSpec(M)::LOCKED(계약 승인 뒤) |
| BR-BIZ-068 | 바뀌기 전 코드는 영구 예약되어 어떤 가맹점도(같은 가맹점의 되돌리기 포함) 다시 쓸 수 없다 | 확정 | 사용자 | D-MCODE-002(Q-MC-7) | MERCHANT-CODE-001 §3.2·MC-1b-5 | MerchantCodeChangeApprovalSpec(M)::MC-1b-5 은퇴 코드 재사용 불가 |
| BR-BIZ-069 | 가맹점 코드 변경 2인 승인은 요청자≠승인자만 강제하고, 원 가맹점 등록자도 승인할 수 있다 | 확정 | 사용자 | D-MCODE-002(Q-MC-10) | MERCHANT-CODE-001 §5.5 | MerchantCodeChangeApprovalSpec(M)::승인자=가맹점 created_by 성공, 요청자=승인자 SAME_ACTOR |
| BR-BIZ-070 | 가맹점 코드 변경 사유는 고정 목록(TYPO_CORRECTION·MERCHANT_REQUEST·OTHER_POLICY)에서 고른다 | Demo값 | 적용 | MERCHANT-CODE-001 C-12 | MERCHANT-CODE-001 §3.7 | MerchantCodeChangeSpec(M)::고정 사유 목록 |
| BR-BIZ-071 | 가맹점 코드는 웹훅·결제 생성·조회 응답에 출력만 하고(요청 본문 입력 불가), 식별·인증은 MID·API 키로 한다 | 확정 | 사용자 | D-MCODE-001(Q-MC-1), D-MCODE-002(Q-MC-8) | MERCHANT-CODE-001 §6 | CreatePaymentApiSpec(P)::응답·재생·GET에 merchantCode, 요청 본문 merchantCode 400; MerchantWebhookDeliveryDbSpec(P)::승격 뒤 이벤트에 merchantCode |
| BR-BIZ-072 | 가맹점 코드는 정산서에도 노출한다 | 확정 | 사용자 | D-MCODE-001(Q-MC-1) | MERCHANT-CODE-001 §6 | 없음(정산서 미구현) |
| BR-BIZ-073 | 가맹점 코드는 기관(Simulator·REAL) 전송과 결제 Domain·요청 지문에 쓰지 않는다 | 확정 | 사용자 | D-MCODE-002(Q-MC-8) | MERCHANT-CODE-001 §6 | MerchantCodeExposureScanSpec(P)::MC-4-3 기관 adapter에 코드 없음, MC-4-4 결제 Domain·지문 비의존 |
| BR-BIZ-074 | 새 감사·승인 기록의 가맹점 대상은 가맹점 코드로 남기고, 기존 감사·승인 행은 고치지 않으며 코드로 찾으면 대응 내부 키 행도 함께 보여 준다 | 확정 | 사용자 | D-MCODE-001(Q-MC-4), D-MCODE-002(Q-MC-9) | MERCHANT-CODE-001 §5 | AdminAuditEventTargetRefSpec(A)::MC-3-6 코드 대상; AdminSchemaSpec(A)::MC-3-2·8 기존 행 보존; AuditScreen.targetLabels.test.tsx(F)::은퇴 코드 일치 |

## 4. Site(MerchantSite)·채널·Site 심사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-080 | Site 채널 식별값은 소문자 정규형만 받는다(WEB = 도메인 host만, APP = 점 구분 ID) — 대문자·scheme·경로·포트·IP 리터럴은 자동 정규화 없이 거절하고 www. 등 서브도메인은 다른 채널이다 | 확정 | 사용자 | D-MD-009, D-MD-014 | MASTER-DOMAIN-001 §2.2 MerchantSite | MasterValuesSpec(M)::대문자·scheme·포트·IP 거절; SiteChannelHostVectorsSpec(M)::WEB 벡터 |
| BR-BIZ-081 | 활성 채널 식별값은 같은 Merchant 안에서도, 다른 Merchant와도 중복 등록할 수 없다(예외는 승인 기록 — 후속), 동시 등록도 하나만 성공한다 | 확정 | 사용자 | D-MD-008 | MASTER-DOMAIN-001 §2.2 | MerchantOnboardingServiceSpec(M)::다른 Merchant 활성 채널 거절; MerchantOperationsConcurrencySpec(M)::같은 채널 두 Merchant 1건만 |
| BR-BIZ-082 | 다른 Merchant의 Site가 종료된 뒤 그 채널 식별값을 다른 Merchant가 등록할 수 있는지 | 미결(Q-MD-18) | — | Q-MD-18 | MASTER-DOMAIN-001 §5 | 없음 |
| BR-BIZ-083 | 신규 Site의 PG 심사 상태는 PENDING이고 상태는 PENDING/APPROVED/REJECTED/SUSPENDED, 결정은 사유·행위자를 가진 append-only 기록이며 현재 상태는 마지막 결정이다 | 확정 | 사용자 | D-MS-002(a), D-MD-016 | MASTER-SNAPSHOT-001 §4.4 | SiteConfirmationAndReviewSpec(M)::PENDING 시작, 현재 = 마지막 결정 |
| BR-BIZ-084 | Site 심사 전이는 PENDING→APPROVED/REJECTED, APPROVED→SUSPENDED, SUSPENDED→APPROVED/REJECTED만 허용하고 같은 상태·PENDING 결정은 거절한다 | 제안 | 적용 | MASTER-SNAPSHOT-001 §4.4(관례 — 출처에 개별 전이 없음) | MASTER-SNAPSHOT-001 §4.4 | SiteConfirmationAndReviewSpec(M)::REJECTED 종결, 같은 상태·PENDING 거절 |
| BR-BIZ-085 | Site 심사 REJECTED는 종료 상태로 재심사·재승인하지 않으며, 재신청은 새 Site 등록으로 처음부터 심사한다 | 확정 | 사용자 | D-MS-005 | MASTER-SNAPSHOT-001 §4.4 | SiteConfirmationAndReviewSpec(M)::REJECTED terminal |
| BR-BIZ-086 | 결제는 Site 심사 APPROVED에서만 허용하고, 미승인은 NotEligible METHOD_NOT_ENABLED(내부 로그에만 SITE_REVIEW 구분)로 답한다 | 확정 | 사용자 | D-MS-002(a) | MASTER-SNAPSHOT-001 §6.1 E3 | EntitlementEvaluatorSpec(P)::review 거절 METHOD_NOT_ENABLED 순서; EntitlementSnapshotSpec(M)::결정 없는 Site는 PENDING |
| BR-BIZ-087 | Site 등록은 OPERATOR 1인, Site 심사 APPROVED(재승인 포함)는 2인, Site 심사 REJECTED는 1인이다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③ | MERCHANT-OPS-002 §9.12·§9B | MerchantActivationApprovalServiceSpec(A)::reject code는 2인 경로에서 400 NOT_ALLOWED; MerchantRegistrationServiceSpec(A)::Site 심사 거절 1인 명령 |
| BR-BIZ-088 | Site 등록자는 그 Site의 심사 승인자가 될 수 없고, SUSPENDED→APPROVED 재승인도 원 Site 등록자는 금지한다(중지 실행자는 금지 대상 아님) | 확정 | 사용자 | D-MOPS-009 ③, D-MOPS-010 ①(Q-MOPS-25) | MERCHANT-OPS-002 §9.27 | SiteConfirmationAndReviewSpec(M)::APPROVER_IS_SITE_REGISTRANT PENDING·SUSPENDED, 중지 실행자 허용; MerchantActivationApprovalServiceSpec(A)::Site 등록자 403 |

## 5. 계약(Contract·Revision)·서비스 계약(CSA)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-100 | 계약은 Contract(당사자·Scope·Revision·승인/효력) 1:N ContractServiceAgreement(서비스별 권리) 2단 구조이며, 'ServiceAgreement'·'Agreement'는 CSA의 줄임말이다 | 확정 | 사용자 | D-MD-001 | MASTER-DOMAIN-001 §2.2 Contract | ContractSpec(M)::Revision 1·CSA 없음으로 시작 |
| BR-BIZ-101 | CSA 1건 = 서비스 종류 1개이며, 같은 계약 안 같은 서비스 종류 CSA는 하나다(동시 등록도 하나만) | 확정 | 사용자 | D-MD-002 | MASTER-DOMAIN-001 §2.2 CSA | ContractSpec(M)::같은 종류 두 번째 CSA SERVICE_KIND_DUPLICATE; MerchantOperationsConcurrencySpec(M)::같은 종류 동시 1건만 |
| BR-BIZ-102 | 계약 당사자는 Business, Scope는 MERCHANT 또는 SITES(1개 이상)이고, Scope Merchant는 당사자 Business 소속·SITES는 그 Merchant의 Site만 담는다 | 확정 | 사용자 | D-MD-004, D-MD-010 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::다른 Business Merchant·다른 Merchant Site 거절, 빈 SITES 불가 |
| BR-BIZ-103 | 한 계약의 모든 Revision은 같은 Merchant를 대상으로 하며, 다른 Merchant는 새 계약이다 | 확정 | 사용자 | D-MD-013 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::다른 Merchant 대상 Revision 거절; MerchantOnboardingServiceSpec(M)::같은 Business 다른 Merchant 거절 |
| BR-BIZ-104 | Scope 변경은 새 Revision이며 Revision 내용은 불변, 승인 사실은 append-only, 효력은 UTC [from, to)이다 | 확정 | 사용자 | D-MD-004, D-MD-005 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::Revision 연속 번호, 승인 1회만; MasterValuesSpec(M)::[from,to) |
| BR-BIZ-105 | 시각 t에 적용되는 Revision은 t를 포함하는 승인된 Revision 중 번호가 가장 큰 것이며 이전 Revision은 기간이 남아도 대체된다 | 확정 | 사용자 | D-MD-011 | MASTER-SNAPSHOT-001 §1 R17 | ContractSpec(M)::t5부터 Rev2가 Rev1 대체; EntitlementSnapshotSpec(M)::나중 SITES Revision이 경계에서 대체 |
| BR-BIZ-106 | 효력 기간이 이미 끝난 Revision에는 승인을 기록하지 않는다(판정 시각 = 잠금 뒤 시각) | 확정 | 사용자 | D-MD-014 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::끝난 Revision 승인 거절; FirstDefaultIssuanceAtomicitySpec(M)::대기 중 끝난 Revision 거절 |
| BR-BIZ-107 | 계약(Revision 1)과 새 Revision의 효력 시작이 판정 시각의 KST 오늘 00:00보다 앞이면 CONTRACT_START_RETROACTIVE로 거절한다(ADMIN은 판정하지 않고 MASTER 결과를 전달) | 확정 | 사용자 | D-MOPS-006 ⑤, D-MOPS-007 ② | MERCHANT-OPS-002 §3.4·§9.2 | ContractSpec(M)::KST 경계표 Revision 1·2; MerchantOnboardingServiceSpec(M)::어제 KST 저장된 거절 재생; MerchantRegistrationServiceSpec(A)::ADMIN은 과거 시작을 판정하지 않음 |
| BR-BIZ-108 | 종료(TERMINATED)된 당사자 Business·Scope Merchant로 계약을 등록하지 않는다 | 확정 | 사용자 | D-MD-006 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::종료 Business·종료 Scope Merchant 거절 |
| BR-BIZ-109 | 계약 승인과 효력은 다른 개념이다 — 승인 후 효력 시작 전에도 MID 선발급은 되지만 결제는 효력·개통 조건 충족 시에만 허용한다 | 확정 | 기준 | 초기 셋째 문단, 구조 경계 §1, D-MD-015 | MASTER-DOMAIN-001 §2.2 | MerchantOnboardingServiceSpec(M)::미래 Revision에 MID 발급, 결제 허용은 별도 판정; EntitlementEvaluatorSpec(P)::미래 계약 창 시작 전 거절 |
| BR-BIZ-110 | 계약 철회 시 발급 이력은 보존하고 그 계약의 신규 결제를 차단하며, 재승인만으로 자동 재활성화하지 않는다(재활성화 = 새 Revision 승인 + 사유·행위자를 가진 명시 재활성화 명령) | 확정 | 사용자 | 초기 셋째 문단, D-MD-004 | MASTER-DOMAIN-001 §2.3 | 없음(MASTER-LIFECYCLE-001 미구현) |
| BR-BIZ-111 | 철회와 종료는 다르며 CSA 단위 철회를 허용한다 | 확정 | 사용자 | D-MD-004 | MASTER-DOMAIN-001 §2.2 | 없음(MASTER-LIFECYCLE-001 미구현) |
| BR-BIZ-112 | 같은 CSA 갱신은 재검증 후 MID를 유지하며 MID의 발급 근거 Revision은 바뀌지 않는다(현재 적용 Revision은 판정 시 계산) | 확정 | 사용자 | D-MD-004 | MASTER-DOMAIN-001 §2.2 | 없음(갱신 명령 미구현) |
| BR-BIZ-113 | 계약 초안(계약·Revision·CSA) 등록은 OPERATOR 1인, 계약 Revision 승인은 2인(요청자≠승인자)이다 | 확정 | 사용자 | D-MOPS-004 | MERCHANT-OPS-002 §9 | MerchantContractApprovalDomainSpec(A)::OPERATOR 요청·APPROVER 승인·요청자≠승인자 |
| BR-BIZ-114 | 계약 Revision 등록자는 그 Revision의 승인자가 될 수 없다(ADMIN·MASTER 양쪽 검사) | 확정 | 사용자 | D-MOPS-008 ③ | MERCHANT-OPS-002 §9.3 판정 5 | ContractSpec(M)::APPROVER_IS_REVISION_REGISTRANT Rev1·Rev2; MerchantOpsApprovalServiceSpec(A)::Revision 등록자 403 |
| BR-BIZ-115 | 계약 Scope는 GID에 계약·수수료·정산·접근 권한을 자동 상속하지 않는다 | 확정 | 기준 | 초기 §가맹점·계약·MID 둘째 문단 | MASTER-DOMAIN-001 §2.2 | 없음(GID 모델 없음) |

## 6. MID(MerchantAccount) 발급·Default·Site 연결

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-130 | MID 하나는 계약 계정 하나이고 TEST/LIVE는 Credential·요청의 축이며, 발급되는 MID는 모두 계약 승인이 필요한 Production MID다(계약 없는 체험용 식별자 없음) | 확정 | 사용자 | D-MD-003 | MASTER-DOMAIN-001 §2.2 MID | MerchantAccountIssuanceSpec(M)::미승인 Revision 거절; MerchantOnboardingServiceSpec(M)::미승인 계약 거절 |
| BR-BIZ-131 | MID는 하나의 Merchant에만 속하며 다른 Merchant 간 MID 공유를 금지한다 | 확정 | 기준 | 구조 §8·§13 | MASTER-DOMAIN-001 §2.2 | MerchantAccountIssuanceSpec(M)::다른 Merchant PRIMARY Site 거절; SiteAssignmentRulesSpec(M)::같은 Business 다른 Merchant Site 거절 |
| BR-BIZ-132 | MID는 BusinessId·카드사 가맹점번호·GID와 다른 개념이며 발급 근거 CSA와 Revision 번호를 명시·보존하고 발급 후 바꾸지 않는다 | 확정 | 기준 | 초기 둘째 문단 | MASTER-DOMAIN-001 §2.2 | MerchantAccountIssuanceSpec(M)::발급 근거 CSA·Revision 보존; MasterReconstitutionSpec(M)::Revision 0 근거 복원 거절 |
| BR-BIZ-133 | MID 발급 근거는 발급 시각에 적용되는 Revision만이고, 적용 Revision이 없을 때(효력 시작 전)는 자기 시작 시각에 적용될 승인 Revision만 선발급 근거로 허용한다 — 그 밖은 거절(fail-closed) | 확정 | 사용자 | D-MD-012, D-MD-015 | MASTER-DOMAIN-001 §2.2 | ContractSpec(M)::선발급 (1)~(4) 경계; MerchantOnboardingServiceSpec(M)::대체된 Rev1 REVISION_NOT_APPLICABLE |
| BR-BIZ-134 | 최초 MID는 Default이며, Merchant에 이미 MID가 있으면 "최초 Default" 발급을 거절한다(다른 키 동시 최초 발급도 하나만 성공) | 확정 | 기준 | 초기 §가맹점·계약·MID, 구조 경계 §1 | MASTER-DOMAIN-001 §2.2 Merchant | MerchantAccountIssuanceSpec(M)::두 번째 최초 Default 거절; FirstDefaultIssuanceAtomicitySpec(M)::동시 발급 1건만; FirstMidApprovalApiSpec(M)::다른 approvalId 동시 1건 |
| BR-BIZ-135 | Default MID는 기본 계정 선택 용도이며 계약·권한·정산 조건을 자동 상속하지 않고 SiteAssignment PRIMARY와 다른 개념이다 | 확정 | 기준 | 초기, 구조 경계 §2 | MASTER-DOMAIN-001 §2.2 | 없음 |
| BR-BIZ-136 | Production MID는 최초 발급 때 같은 Merchant 소속이고 발급 근거 Scope가 허용하는 Site를 적어도 하나 PRIMARY로 연결한다 | 확정 | 기준 | 구조 §7 기본 규칙, 구조 경계 §1 | MASTER-DOMAIN-001 §2.2 | MerchantAccountIssuanceSpec(M)::첫 연결 PRIMARY ACTIVE, Scope 밖 Site 거절 |
| BR-BIZ-137 | 최초 Default 발급의 MID·Default 지정·최초 SiteAssignment·요청 결과·Outbox는 한 MASTER 로컬 TX이며 중간 실패에 잔존 MID·부분 지정이 남지 않는다 | 확정 | 기준 | 구조 경계 §1, 초기 셋째 문단 | MASTER-DOMAIN-001 §3.1 | FirstDefaultIssuanceAtomicitySpec(M)::실패 지점마다 아무것도 남지 않음 |
| BR-BIZ-138 | 첫 Default MID 발급은 2인 승인이며 업무 근거 참조가 필수다(계약 Revision 등록자도 MID 발급은 승인할 수 있다) | 확정 | 사용자 | D-MOPS-004, D-MOPS-008 ① | MERCHANT-OPS-002 §9 | FirstMidApprovalApiSpec(M)::Revision 등록자 규칙은 계약 승인에만; MerchantFirstMidDomainSpec(A)::근거 필수 |
| BR-BIZ-139 | MID 발급(ACTIVE)만으로 결제를 허용하지 않는다 — 결제 허용은 MID ACTIVE ∧ SiteAssignment 유효 ∧ 개통 ACTIVE ∧ 계약 유효 ∧ Credential 유효 ∧ Route 가능일 때만이다 | 확정 | 기준 | 구조 §11, 초기 §4 | MASTER-DOMAIN-001 §2.2 | MerchantOnboardingServiceSpec(M)::선발급 MID, 결제 허용은 별도; EntitlementEvaluatorSpec(P)::조건별 거절 |
| BR-BIZ-140 | MID 생명주기는 ACTIVE(발급 완료)/TERMINATED(비가역)이고 운영 중지는 생명주기가 아니라 별도 차단 기록이며, 종료 뒤 과거 MID·Assignment는 보존한다 | 확정 | 사용자 | D-MD-006, 구조 경계 §1 | MASTER-DOMAIN-001 §2.2 | ActivationSpec(M)::종료 MID는 권리 확대 전이 거절·축소 허용 |
| BR-BIZ-141 | 추가 MID 발급의 근거·승인(사유 코드, CSA당 복수 MID, 요청자≠승인자) | 미결(Q-MD-06) | — | Q-MD-06, 초기 | MASTER-DOMAIN-001 §2.2 | 없음 |
| BR-BIZ-142 | Default 교체·종료(발급 전·종료 후 0개, 자동 승격 여부) | 미결(Q-MD-07) | — | Q-MD-07 | MASTER-DOMAIN-001 §2.2 | 없음 |
| BR-BIZ-143 | MID–Site 관계는 SiteAssignment(역할 PRIMARY/ADDITIONAL, 기간, 상태)로 N:M을 수용하되 기본은 MID 1 : Site 1이며, 관계 이력을 지우지 않고 거래 당시 Site와 근거를 보존한다 | 확정 | 기준 | 구조 §6·§9·§10·§13 | MASTER-DOMAIN-001 §2.2 SiteAssignment | SiteAssignmentRulesSpec(M)::같은 MID–Site 재연결은 새 행 |
| BR-BIZ-144 | 1 MID : N Site 공유는 동일 Merchant·동일 CSA·동일 개통 적용 가능·동일 정산 정책·기관 심사상 동일 MID 허용 다섯 조건을 모두 충족할 때만이며, ADDITIONAL 생성 명령은 이 증거(같은 CSA·정산 정책·기관 근거 참조)를 받는다 | 확정 | 사용자 | 구조 §7, D-MD-016 | MASTER-DOMAIN-001 §2.2 | 없음(ADDITIONAL 생성 명령 미구현 — MASTER-LIFECYCLE-001) |
| BR-BIZ-145 | Site 전용 Scope 계약의 MID에 Scope 밖 Site를 추가하지 않는다 | 확정 | 기준 | 구조 경계 §2 | MASTER-DOMAIN-001 §2.2 | MerchantAccountIssuanceSpec(M)::SITES Scope 밖 PRIMARY 거절(추가 연결 명령은 미구현) |
| BR-BIZ-146 | Assignment 기간은 UTC [from, to)이고 같은 MID–Site 기간 중첩 금지·소급 시작·종료 금지·미래 종료 예약 허용, 상태는 ACTIVE/SUSPENDED/ENDED다 | 확정 | 사용자 | D-MD-005 | MASTER-DOMAIN-001 §2.2 | SiteAssignmentRulesSpec(M)::중첩·소급 거절, 예약 종료 뒤 재연결; MasterReconstitutionSpec(M)::소급 시작 복원 거절 |
| BR-BIZ-147 | MID당 유효 PRIMARY는 최대 1개이고, 교체는 기존 종료 + 새 시작을 한 TX로 하며 자동 승격은 없다 | 확정 | 사용자 | D-MD-007 | MASTER-DOMAIN-001 §2.2 | SiteAssignmentRulesSpec(M)::두 번째 PRIMARY 중첩 거절, 종료 시각 정확 교체 허용(교체 명령은 미구현) |
| BR-BIZ-148 | 유효 PRIMARY 없이 ADDITIONAL만 남거나 유효 Site 연결이 없으면 신규 결제를 차단한다(fail-closed) | 확정 | 사용자 | D-MD-007, 구조 경계 §1 | MASTER-SNAPSHOT-001 §6.1 F1 | EntitlementEvaluatorSpec(P)::Assignment 없음·PRIMARY 없음 NotFoundOrForbidden |
| BR-BIZ-149 | 한 결제 시도 도중 다른 MID로 바꿔 주문 중복 제어를 우회하지 않는다 | 확정 | 기준 | 구조 경계 §2 | MASTER-DOMAIN-001 §2.2 | 없음(PAY 주문 키 규칙으로 보장 — PAY 대장) |

## 7. 결제수단 개통·Site 적용 확인·통화 활성화·카드사 Route

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-160 | 결제수단 개통(PaymentMethodActivation)은 발급과 별개이며 (MID, 결제수단) 단위로 UNDER_REVIEW/ACTIVE/SUSPENDED/TERMINATED 상태와 근거 참조를 가진다(Site Scope 확장 경계 유지) | 확정 | 사용자 | D-MD-017, 구조 §12 | MASTER-SNAPSHOT-001 §4.1 | ActivationSpec(M)::신규 등록 UNDER_REVIEW |
| BR-BIZ-161 | 개통 전이는 UNDER_REVIEW→ACTIVE(기관 근거 필수), UNDER_REVIEW→TERMINATED(심사 거절), ACTIVE→SUSPENDED(사유), SUSPENDED→ACTIVE(새 기관 근거 필수), ACTIVE·SUSPENDED→TERMINATED(비가역)만 허용한다 | 확정 | 사용자 | D-MS-003 | MASTER-SNAPSHOT-001 §4.1 | ActivationSpec(M)::승인 전 중지·재개 불가, 두 번 승인 불가, 종료 비가역 |
| BR-BIZ-162 | 재개의 "새 기관 근거"는 그 개통에서 한 번도 쓰지 않은 근거 참조여야 한다 | 제안 | 적용 | MASTER-SNAPSHOT-001 §4.1(관례 해석), review m-2 | MASTER-SNAPSHOT-001 §4.1 | ActivationSpec(M)::이전에 쓴 근거로 재개 거절 |
| BR-BIZ-163 | 재개통은 새 Activation 행이며 같은 (MID, 수단)에 종료되지 않은 행은 하나다 | 확정 | 사용자 | D-MS-003 | MASTER-SNAPSHOT-001 §4.1 | MerchantEntitlementServiceSpec(M)::종료 뒤 새 행 등록, MID·수단당 하나 |
| BR-BIZ-164 | 결제 허용에는 해당 (MID, Site, 수단)의 Site 적용 확인 기록(append-only, 기관 근거·행위자)이 필요하며, PRIMARY 확인은 개통 승인 때 함께 기록하고 ADDITIONAL은 기록 전 결제 차단이다 | 확정 | 사용자 | D-MD-016 | MASTER-SNAPSHOT-001 §4.2 | ActivationSpec(M)::승인 때 PRIMARY 확인 기록, 유효 PRIMARY 없으면 확인 없음; SiteConfirmationAndReviewSpec(M)::ADDITIONAL 근거 확인 |
| BR-BIZ-165 | 추가 Site는 MID의 개통을 자동 상속하지 않으며, 기관 승인 근거가 그 Site를 포함하는지 확인해야 한다 | 확정 | 기준 | 구조 경계 §1, 초기 §v0.1 | MASTER-DOMAIN-001 §2.2 | SiteConfirmationAndReviewSpec(M)::열린 Assignment 없는 Site·다른 Merchant Site 확인 거절 |
| BR-BIZ-166 | Site 적용 확인 철회는 append-only 철회 기록(확인 참조·사유·근거·행위자)이고 재확인은 새 확인 행이며, 개통 재개 때 기존 Site 확인은 유지된다 | 확정 | 사용자 | D-MS-003 | MASTER-SNAPSHOT-001 §4.2 | SiteConfirmationAndReviewSpec(M)::철회 1회, 새 확인 행; 중지된 개통은 확인 유지 |
| BR-BIZ-167 | 개통이 UNDER_REVIEW인 동안 PRIMARY Site의 별도 적용 확인은 거절하고, 같은 (개통, Site)의 유효 확인은 하나만 둔다 | 제안 | 적용 | MASTER-SNAPSHOT-001 §4.2 review M-1 | MASTER-SNAPSHOT-001 §4.2 | SiteConfirmationAndReviewSpec(M)::승인 전 PRIMARY 확인 거절; ActivationSpec(M)::두 번째 유효 PRIMARY 확인 없음 |
| BR-BIZ-168 | 개통 등록(UNDER_REVIEW)은 OPERATOR 1인, 개통 승인은 2인이며 개통 등록자는 그 개통의 승인자가 될 수 없다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③, D-MOPS-009 ③ | MERCHANT-OPS-002 §9.27 | ActivationSpec(M)::APPROVER_IS_ACTIVATION_REGISTRANT; MerchantActivationApprovalServiceSpec(A)::개통 등록자 403 |
| BR-BIZ-169 | 결제수단·카드 Route 재개 2인 승인자는 요청자와 다르고 원 개통(Route) 등록자여도 안 된다(중지 실행자는 금지 대상 아님) | 확정 | 사용자 | D-MOPS-007 ③, D-MOPS-010 ①(Q-MOPS-25) | MERCHANT-OPS-002 §9.27 | 없음(재개 2인 경로 미구현 — B3-4) |
| BR-BIZ-170 | 지금 개통할 수 있는 결제수단은 신용카드뿐이다(목록 구조는 확장 가능하게 — 새 결제수단 정책 채택 아님) | 확정 | 사용자 | D-ADMIN-UI-004 ⑤ | MERCHANT-OPS-002 §4.3 | MerchantRegistrationServiceSpec(A)::CARD 외 결제수단 400 |
| BR-BIZ-171 | 통화 허용은 MID 통화 활성화 기록(MID, 통화, 기간, 근거)이 CreatePayment CURRENCY_NOT_ENABLED 판정 근거이고, 승인 시작에는 (CSA, MID, 통화) 정책 Assignment도 함께 필요하다(어느 하나만으로 허용하지 않음) | 확정 | 사용자 | D-MD-018, D-CP-002 | MASTER-SNAPSHOT-001 §4.3 | EntitlementEvaluatorSpec(P)::통화 거절 CURRENCY_NOT_ENABLED; AuthorizationTermsEvaluatorSpec(P)::A3 수수료·정산 Assignment 각각 필요 |
| BR-BIZ-172 | 통화 활성화는 소급 시작·소급 종료 금지, 미래 종료 예약 허용, 같은 (MID, 통화) 기간 중첩 금지다 | 제안 | 적용 | MASTER-SNAPSHOT-001 §4.3·§14-10(D-MD-005 유추 해석) | MASTER-SNAPSHOT-001 §4.3 | CurrencyEnablementSpec(M)::소급 시작 거절, 중첩 거절, 미래 종료 1회 |
| BR-BIZ-173 | 통화 활성화는 2인 승인이며 효력 시작은 "승인 실행 즉시"(MASTER가 잠금 뒤 시각으로 확정) 또는 "KST 날짜 00:00(내일 이후)" 중 선택한다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-009 ② | MERCHANT-OPS-002 §9.26 | CurrencyEnablementSpec(M)::AT_EXECUTION·DATE 경계; CurrencyEnablementApiSpec(M)::AT_EXECUTION 성공, 비자정 400 |
| BR-BIZ-174 | 통화 단위(ISO 4217 minor unit)는 표준 사실 기준정보이며 허용 통화가 아니다 | 확정 | 사용자 | D-CP-002 | MASTER-SNAPSHOT-001 §4.3 | CurrencyEnablementSpec(M)::minor unit 0..4 |
| BR-BIZ-175 | 카드사 개통(card route activation)은 매입사 코드를 키로 하며 (MID, CARD, 매입사)당 종료되지 않은 개통 1개 = Route 1개(대체 Route 없음)이고, 결제수단 개통이 종료되지 않았을 때만 만들 수 있으며 전이는 결제수단 개통과 같다 | 확정 | 사용자 | D-MS2-004 | MASTER-SNAPSHOT-002 §3.4 | AuthorizationTermsSpec(M)::매입사당 Route 1개, 근거 필요, 종료 비가역 |
| BR-BIZ-176 | 일부 카드사만 개통된 경우 개통된 카드사/Route만 쓸 수 있고, 발급사 지원 여부는 기관 프로파일 allowlist로 판정하며 결제창 선택값은 서버 검증값과 대조만 한다 | 확정 | 사용자 | D-MS2-004, 허용 §4.1-6 | MASTER-SNAPSHOT-002 §3.4 | AuthorizationTermsEvaluatorSpec(P)::A2 Route 없음·비ACTIVE CARD_COMPANY_NOT_ENABLED |
| BR-BIZ-177 | 기관 가맹점 참조(institutionMerchantRef)는 Secret은 아니지만 내부 식별자 등급이다 — 서명 Snapshot으로만 PAYMENT에 전달하고 로그·지표·API 응답·예외 메시지에 원문을 두지 않으며 기관 Credential은 Secret 저장소에만 둔다 | 확정 | 사용자 | D-MS2-005 | MASTER-SNAPSHOT-002 §3.4 | AuthorizationTermsSpec(M)::toString 비노출; MerchantOpsApiSpec(M)::상세에 기관 참조 없음 |
| BR-BIZ-178 | 기관 가맹점 참조는 OPERATOR가 1인 Route 등록에서 입력하고 ADMIN은 저장하지 않으며(감사 detail은 imr=PRESENT만), 관리 조회는 뒤 4자리 마스킹만·원문 조회 없음, 정정은 Route 종료 후 재등록이다 | 확정 | 사용자 | D-MOPS-009 ①, D-MOPS-010 ③ | MERCHANT-OPS-002 §9.25 | 없음(B3-3 미구현) |
| BR-BIZ-179 | 카드사 Route 등록은 1인, Route 개통 승인은 2인이며 Route 등록자는 그 Route의 개통 승인자가 될 수 없다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-009 ③ | MERCHANT-OPS-002 §9.27 | 없음(B3-3 미구현) |

## 8. 거래 한도·수수료/정산 정책 Assignment(승인 시작 조건 원본)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-190 | 같은 (MID, 통화)에 유효한 MID_OVERRIDE 한도가 있으면 그 정책 하나만 적용하고 CSA_DEFAULT와 필드별 병합하지 않는다 | 확정 | 사용자 | D-MS2-001 | MASTER-SNAPSHOT-002 §3.1 | AuthorizationTermsEvaluatorSpec(P)::override 유효 동안 기본 전체 대체 |
| BR-BIZ-191 | 같은 수준·같은 키 한도 정책의 효력 기간 중첩은 MASTER 등록에서 거절하고, PAYMENT에서 2개 이상이면 승인 시작을 fail-closed한다 | 확정 | 사용자 | D-MS2-001 | MASTER-SNAPSHOT-002 §3.1 | AuthorizationTermsSpec(M)::같은 수준·대상·통화 중첩 거절 |
| BR-BIZ-192 | 한도 정책 효력 시작·종료는 Asia/Seoul 00:00 경계만 허용하고(첫 정책 포함) 소급을 금지하며, 하루 중 긴급 축소는 한도 변경이 아니라 신규 결제 중지·카드사 개통 정지로 한다 | 확정 | 사용자 | D-MS2-002, D-MS2-006 | MASTER-SNAPSHOT-002 §3.1 | AuthorizationTermsSpec(M)::Seoul 자정 외 경계 거절, 명령 시각 전 시작 거절 |
| BR-BIZ-193 | 신규 MID는 첫 한도 정책 효력 전까지 승인 시작을 fail-closed(LimitPolicyUnavailable)한다 | 확정 | 사용자 | D-MS2-006 | MASTER-SNAPSHOT-002 §3.1 | AuthorizationTermsEvaluatorSpec(P)::A4 유효 한도 없음 Missing(기본값 없음) |
| BR-BIZ-194 | policy_version은 종류·코드·버전 번호·상태·내용 hash만 가진 식별 등록부이며, 내용 없는 버전은 TEST/Simulator 전용으로 LIVE 허용 Gate의 차단 조건이다 | 확정 | 사용자 | D-MS2-003 | MASTER-SNAPSHOT-002 §3.2 | AuthorizationTermsSpec(M)::버전 번호 1부터, 잘못된 hash 거절(LIVE Gate 차단은 없음) |
| BR-BIZ-195 | 정책 Assignment의 CSA는 그 MID의 발급 CSA여야 하고, (통화, 종류)당 동시에 하나이며 수수료·정산은 독립이다 | 확정 | 사용자 | D-MD-018, D-MD-001 | MASTER-SNAPSHOT-002 §3.3 | AuthorizationTermsSpec(M)::발급 CSA만, (통화, 종류)당 하나 |
| BR-BIZ-196 | 한도 상향 등록/교체·수수료 정책 배정·정산 정책 배정·배정 종료·한도 종료는 2인, 한도 하향은 1인이다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③ | MERCHANT-OPS-002 §9.12 | 없음(B4·B5 미구현 — seed 경로만) |
| BR-BIZ-197 | 수수료·VAT는 원승인 Snapshot 기준이고 실제 요율·주기·한도 숫자는 기준정보(운영값 아님 Demo Seed)로 두며 코드에 하드코딩하지 않는다 | 확정 | 기준 | 구조 §13, CLAUDE.md 핵심 규칙 | MASTER-SNAPSHOT-002 §10 | 없음(FIN 대장 소관 — 진단 b-7) |

## 9. 권리 Snapshot·결제 허용 입력(MASTER 원본 → PAYMENT 판정)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-210 | TEST 환경 CreatePayment eligibility도 LIVE와 같은 조건(계약 효력·개통 ACTIVE·Site 적용 확인·Site 심사·통화 활성화)으로 판정하며 Snapshot에 환경 축이 없다 | 확정 | 사용자 | D-MS-001 | MASTER-SNAPSHOT-001 §6.1 | EntitlementEligibilitySpec(P)::TEST는 LIVE처럼 판정 |
| BR-BIZ-211 | 유효 Assignment가 있어도 MID 발급 계약의 현재 적용 Revision Scope 밖 Site는 CONTRACT_NOT_EFFECTIVE로 거절한다 | 확정 | 사용자 | D-MS-002(b) | MASTER-SNAPSHOT-001 §6.1 E2 | EntitlementEvaluatorSpec(P)::계약·심사·개통·확인·통화 거절 순서; EntitlementSnapshotSpec(M)::Scope 경계에서 Site 이탈 |
| BR-BIZ-212 | MID 운영 중지와 MID TERMINATED는 CreatePayment 계약 변경 없이 NotEligible MERCHANT_SUSPENDED로 답하고, Business/Merchant/MID 구분은 내부 로그·감사에만 남긴다 | 확정 | 사용자 | D-MD-019, D-MS-002(c) | MASTER-SNAPSHOT-001 §6.2 | EntitlementEvaluatorSpec(P)::운영 중지 MERCHANT_SUSPENDED + 내부 scope |
| BR-BIZ-213 | 결제 허용 판정 순서는 Unavailable(Snapshot 없음·미지원·만료) → NotFoundOrForbidden(관계) → MERCHANT_SUSPENDED → CONTRACT_NOT_EFFECTIVE → Site 심사 → 개통·적용 확인 → 통화다 | 제안 | 적용 | MASTER-SNAPSHOT-001 §6.1(순서 관례) | MASTER-SNAPSHOT-001 §6.1 | EntitlementEvaluatorSpec(P)::Unavailable 먼저, 관계 거절, 고정 순서 |
| BR-BIZ-214 | MASTER→PAYMENT 권리 채널은 봉투 HMAC-SHA256 서명(환경별 키, 저장소 미포함, 교체용 2키 동시 허용, 불일치·키 없음 거절) + 운영망 mTLS이며, 운영 키·mTLS는 LIVE 허용 전 별도 구성한다 | 확정 | 사용자 | D-MS-004 | MASTER-SNAPSHOT-001 §7 | EntitlementSettingsSpec(M)::HMAC-SHA256 서명; EntitlementRelaySpec(M)::유효 서명 전달(2키·mTLS 시험 없음) |
| BR-BIZ-215 | 결제 허용 입력(MID·개통·Site·Site 심사·통화·계약 Revision 승인 등)이 바뀌는 명령은 같은 MASTER TX에서 권리 revision을 올리고 Snapshot을 발행하며, 거절된 명령은 revision·Snapshot을 바꾸지 않는다 | 확정 | 기준 | MASTER-SNAPSHOT-001 §1 R9·R2, MERCHANT-OPS-002 review M-2 | MASTER-SNAPSHOT-001 §4.6 | MerchantEntitlementServiceSpec(M)::명령마다 revision +1·HIGH Snapshot, 거절은 변화 없음; EntitlementRevisionMatrixSpec(M)::내용이 바뀌면 같은 명령에서 revision 변경 |
| BR-BIZ-216 | 평상시 승인 요청마다 MASTER를 동기 조회하지 않고 PAYMENT 로컬 Snapshot + 만료 있는 lease로 판정하며, 만료·누락·확인 불가 버전은 신규 승인을 차단한다 | 확정 | 기준 | 초기 §4, MASTER-SNAPSHOT-001 R1·R3 | MASTER-SNAPSHOT-001 §3.3 | EntitlementEligibilitySpec(P)::미전파 SNAPSHOT_MISSING, lease 만료 SNAPSHOT_EXPIRED |
| BR-BIZ-217 | 권리 차단 버전은 늦게 도착한 과거 허용 이벤트로 되돌릴 수 없고, 차단 Snapshot 적용 ACK 뒤에는 옛 권리로 결제를 만들지 않는다 | 확정 | 기준 | 허용 §4.2, MASTER-SNAPSHOT-001 R2·R5 | MASTER-SNAPSHOT-001 §3.4·§3.5 | EntitlementEligibilitySpec(P)::차단 ACK 뒤 옛 권리 사용 없음 |

## 10. 관리 명령 권한·2인 승인·근거(MERCHANT-OPS)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-230 | 등록(Business·Merchant·Site·계약 초안·CSA)과 UNDER_REVIEW로 남는 등록은 OPERATOR 1인이고, 권리를 넓히는 명령(계약 승인·첫 Default MID·결제수단 개통 승인·Site 적용 확인·Site 심사 APPROVED·통화 활성화·Route 개통 승인·한도 상향·수수료/정산 정책 배정·재개)은 2인(요청자≠승인자)이며, 안전 방향(중지·철회·종료)은 1인 즉시다 | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③ | MERCHANT-OPS-002 §2·§9.12 | MerchantContractApprovalDomainSpec(A)::2-TX OPERATOR 요청·APPROVER 승인; MerchantRegistrationServiceSpec(A)::VIEWER·APPROVER·SECURITY_ADMIN 등록 403 |
| BR-BIZ-231 | 2인 승인 명령은 MASTER도 승인 근거(요청자·승인자·승인 ID·시각)를 저장하고 요청자≠승인자를 재확인하며, 위반·승인 ID 재사용·실행 기한 경과는 저장된 거절이다 | 확정 | 사용자 | D-MOPS-007 ① | MERCHANT-OPS-002 §9.3 | ApprovalEvidenceSpec(M)::같은 행위자 APPROVAL_SAME_ACTOR, 창·만료; ApprovedCommandAtomicitySpec(M)::같은 행위자·창 위반·approvalId 재사용 저장 |
| BR-BIZ-232 | 계약 승인·첫 MID 발급(그리고 B3 개통 승인류) 2인 요청은 업무 근거 참조가 필수이며 재매입 evidence_ref와 같은 형식(자유 문구 불가, 카드번호 모양 거부)이고 MASTER 승인 근거 행·지문에 포함한다 | 확정 | 사용자 | D-MOPS-008 ① | MERCHANT-OPS-002 §9.3·§9.15 | MerchantContractApprovalDomainSpec(A)::근거 필수·카드번호 모양 거부; MerchantOpsApprovalServiceSpec(A)::근거 빈 값 400 |
| BR-BIZ-233 | 업무 근거 참조는 APPROVER와 요청자만 별도로 조회할 수 있고 조회마다 감사하며, 승인 상세에는 근거가 없다 | 확정 | 사용자 | D-MOPS-008 ① | MERCHANT-OPS-002 §9.7 | MerchantOpsApprovalServiceSpec(A)::요청자·APPROVER만 근거 조회, 조회마다 감사 |
| BR-BIZ-234 | 업무 근거 참조는 LIVE 전에 원천(계약 문서·심사 기록) 대조가 동작해야 하며, 그 전에는 LIVE에서 2인 명령을 열지 않는다 | 제안 | 적용 | MERCHANT-OPS-002 L-15·L-13(LIVE Gate 메모) | MERCHANT-OPS-002 §12 | 없음 |
| BR-BIZ-235 | ADMIN→MASTER 가맹점 등록·개통 명령은 조회와 같은 키(ADMIN_SERVICE)로 서명하고 별도 조작 키·권한을 두지 않는다(유출 위험 수용, 2인 승인·멱등·감사는 그대로) | 확정 | 사용자 | D-MOPS-005 | MERCHANT-OPS-002 §3.1 | MerchantOperationsApiSpec(M)::무서명·다른 키 401; MerchantOpsApiSpec(M)::ADMIN_SERVICE만 허용 |
| BR-BIZ-236 | 1인 등록 명령의 ADMIN 감사는 MASTER 호출 전 시도와 결과 두 번 남기며, 등록번호가 오간 모든 시도(성공·중복·403·형식 오류)를 감사한다 | 확정 | 사용자 | D-MOPS-006 ②④ | MERCHANT-OPS-002 §6 | MerchantRegistrationServiceSpec(A)::REQUESTED → MASTER → 결과 행; BusinessProfileServiceSpec(A)::create 위반 brn=INVALID 감사 |
| BR-BIZ-237 | 관리 명령은 같은 요청키·같은 내용이면 저장된 결과(업무 거절 포함)를 재생하고, 같은 키·다른 내용은 충돌이다 | 확정 | 기준 | CLAUDE.md 핵심 규칙, MASTER-DOMAIN-001 §3.2 | MASTER-DOMAIN-001 §3.2 | MerchantOnboardingServiceSpec(M)::같은 키 재생, 다른 내용 IdempotencyKeyReused; BusinessProfileApiSpec(M)::같은 키 다른 본문 409 |
| BR-BIZ-238 | Demo seed의 근거 없는 승인 경로는 행위자 system:demo-seed·Demo 모드에서만 허용하고 운영에서는 설정 관문·ArchUnit으로 막는다 | 확정 | 사용자 | D-MOPS-008 ② | MERCHANT-OPS-002 §9.3, DEMO-SEED-001 §4.6 | ApprovedCommandAtomicitySpec(M)::seed 경로 허용, 다른 행위자·LIVE 거절; DemoSeedModeGateSpec(M)::DEMO 외 모드 exit 3 |

## 11. 사람용 식별자·내부 키 노출

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-250 | 내부 키(biz_·mer_·site_·ctr_·csa_·act_·apr_ 등)는 시스템 안에서 유지하고 관리자 화면에 표시하지 않으며, 사람에게는 사람용 값(가맹점 코드·상호·사업자등록번호·채널 식별값·종류명)으로 보인다 | 확정 | 사용자 | D-ADMIN-UI-004 ③, D-MCODE-003, D-ADMIN-UI-005, D-ADMIN-UI-010 ③ | MERCHANT-IA-001, MERCHANT-CODE-001 §5.6 | MerchantsScreen.iaB4.test.tsx(F)::모든 탭 내부 키 0; WorkspaceShell.test.tsx(F)::mer_/biz_ 없음 |
| BR-BIZ-251 | Site·계약에 사람용 코드를 시스템이 자동 발급하며 형식은 site-<가맹점코드>-NN / ctr-<가맹점코드>-NN이다 | 확정 | 사용자 | D-ADMIN-UI-005, Q-SC-1, Q-SC-4 | docs/repository/SITE-CONTRACT-CODE-001-SITE-CONTRACT-CODE.md §3.1 | 없음(미구현) |
| BR-BIZ-252 | Site·계약 코드는 MASTER 전체에서 하나(TEST·LIVE 공통)이고 발급 후 불변·재사용 금지(종료 Site·철회 계약 번호 포함)이며, 가맹점 코드가 나중에 바뀌어도 이미 발급된 코드는 발급 당시 값을 유지한다 | 확정 | 사용자 | D-ADMIN-UI-007 ①②, Q-SC-2·3·5 | SITE-CONTRACT-CODE-001 §3.5 | 없음(미구현) |
| BR-BIZ-253 | 서비스 계약(CSA)은 코드를 발급하지 않고 '<계약 코드> · 서비스 종류'로 표시한다 | 확정 | 사용자 | D-ADMIN-UI-007 ③, Q-SC-6 | SITE-CONTRACT-CODE-001 §2 | 없음(미구현) |
| BR-BIZ-254 | Site·계약 코드는 외부 연동(웹훅·결제 응답)·정산서·Snapshot에 노출하지 않으며 외부 Site 식별은 merchantSiteId(site_<ULID>) 그대로다 | 확정 | 사용자 | D-ADMIN-UI-007 ④, Q-SC-7 | SITE-CONTRACT-CODE-001 §6 | 없음(미구현) |
| BR-BIZ-255 | 가맹점 API 연동에 필요한 merchantSiteId는 가맹점 상세 한 곳에만 표시한다 | 확정 | 사용자 | Q-SC-8 | SITE-CONTRACT-CODE-001 §5.2 | 없음(미구현) |
| BR-BIZ-256 | Site·계약 대상의 감사·승인 기록 저장값은 내부 키를 유지하고 화면은 MASTER 라벨 읽기로 코드를 보여 준다(새 등록 감사 detail에 code= 추가) | 확정 | 사용자 | D-ADMIN-UI-007 ⑤, Q-SC-9 | SITE-CONTRACT-CODE-001 §5.3 | 없음(미구현) |
| BR-BIZ-257 | 가맹점 등록 화면은 Site·계약을 채널·채널 식별값·계약 초안 표시로만 보여 준다 | 대체됨→BR-BIZ-251 | 사용자 | D-ADMIN-UI-005(같은 날 재질문으로 대체) | — | — |

## 12. 사업자 계약 접수(MERCHANT-FLOW-001)

FLOW 배치는 미병합(wip/flow-1 보존)이라 아래 규칙의 검증은 모두 `없음`이다.

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-BIZ-270 | 사업자 아래 MASTER 소유 '계약 접수 건'(사업자 1:N)을 두며, 가맹점 단위 계약 Revision·2인 승인·MID 발급·결제 권리는 그대로이고 접수 건은 결제 권리·정산·Snapshot에 효과가 없다 | 확정 | 사용자 | D-FLOW-001 ① | docs/repository/MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md §4A.1·§4A.2 | 없음 |
| BR-BIZ-271 | 접수 건 상태는 상담 중·계약서 접수(열린 건)와 계약 완료·계약 불가·계약 해지(종결)이며, 허용 전이는 상담 중→계약서 접수, 계약서 접수→상담 중(되돌리기), 계약서 접수→계약 완료, 상담 중→계약 불가, 계약서 접수→계약 불가, 계약 완료→계약 해지뿐이다(상담 중→계약 완료 직접 전이 거절) | 확정 | 사용자 | D-FLOW-001 ①, D-FLOW-003 ①, D-FLOW-005 ① | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-272 | 종결은 되돌리지 못하며 다시 진행하려면 새 접수 건을 만든다(과거 건은 지우지 않음) | 확정 | 사용자 | D-FLOW-001 ② | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-273 | 접수 건 상태 변경은 운영자 1인이며 모든 전이를 감사·이력으로 남긴다 | 확정 | 사용자 | D-FLOW-001 ② | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-274 | 계약 완료 전이에는 계약서 수령 근거 참조(업무 근거 참조 형식, 자유 문구 불가, 카드번호 패턴 거부)가 필수이고 계약서 파일은 받지 않는다 | 확정 | 사용자 | D-FLOW-001 ②, D-FLOW-003 ⑨ | MERCHANT-FLOW-001 §4A.4 | 없음 |
| BR-BIZ-275 | 계약서 수령 근거 참조는 사업자 조회 역할 전체(OPERATOR·APPROVER)에 원문으로 보이고 사업자 상세 열람 감사에 포함된다 | 확정 | 사용자 | D-FLOW-004 ④ | MERCHANT-FLOW-001 §4A.4 | 없음 |
| BR-BIZ-276 | 계약 불가·계약 해지·되돌리기에는 사유가 필수(1~200자, 카드번호 패턴 거부, 이력에 원문)이고 해지 근거 참조는 선택이다 | 확정 | 사용자 | D-FLOW-004 ②, D-FLOW-005 ② | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-277 | 사업자당 열린 접수 건은 하나 이하이고, 계약 완료 건이 있으면 새 접수를 거절한다(해지 뒤에만 새 접수) | 확정 | 사용자 | D-FLOW-003 ①, D-FLOW-004 ① | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-278 | 사업자 DRAFT에서도 접수 건을 만들고 계약 완료까지 진행할 수 있다 | 확정 | 사용자 | D-FLOW-003 ①, D-FLOW-004 ① | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-279 | 새 가맹점 등록은 사업자 REGISTERED이고 그 사업자의 (최신) 접수 건이 계약 완료일 때만 허용한다(기존 가맹점의 후속 명령은 접수 건을 보지 않는다) | 확정 | 사용자 | D-FLOW-001 ①, D-BIZ-001 | MERCHANT-FLOW-001 §4A.5 | 없음 |
| BR-BIZ-280 | 관문의 "최신 접수 건"은 사업자 안 생성 순번이 가장 큰 건이다 | 제안 | 적용 | MERCHANT-FLOW-001 §4A.5 리뷰 m-3 | MERCHANT-FLOW-001 §4A.5 | 없음 |
| BR-BIZ-281 | 계약 해지는 기록만 한다 — 새 가맹점 등록만 막고 기존 가맹점의 결제·계약·MID·Snapshot은 바꾸지 않으며 '연결 가맹점 n개 — 개별 중지/철회 필요'를 안내한다(연쇄는 MASTER-LIFECYCLE-001) | 확정 | 사용자 | D-FLOW-003 ② | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-282 | 기존 사업자는 모두 '계약 완료(이관)' 접수 건으로 이관한다(근거 참조·계약 담당자 없음 허용은 이관 건만) | 확정 | 사용자 | D-FLOW-001 ② | MERCHANT-FLOW-001 §4A.9 | 없음 |
| BR-BIZ-283 | 사업자에 효력 있는 수수료율을 두지 않는다 — 요율은 현행 정책 배정(2인)·원승인 Snapshot이고, 사업자 화면 집계는 SETTLEMENT-CONTRACT-001 뒤 읽기 전용이다 | 확정 | 사용자 | D-FLOW-001 ③ | MERCHANT-FLOW-001 §4A.6 | 없음 |
| BR-BIZ-284 | 접수 항목: 접수일(KST 달력 날짜, 기본 오늘, 미래 불가), 접수 경로 고정 목록(직접 영업·전화·홈페이지·영업대행사·기타), 영업대행사는 경로가 '영업대행사'일 때만 필수, 계약 담당자(관리자 계정) 필수, 희망 서비스 = 카드 인증 결제(선택 가능)·카드 키인·가상계좌('준비 전') | 확정 | 사용자 | D-FLOW-003 ③⑤, D-FLOW-004 ③ | MERCHANT-FLOW-001 §4A.6 | 없음 |
| BR-BIZ-285 | 계약 담당자는 ACTIVE OPERATOR 계정만 고를 수 있다 | 제안 | 적용 | MERCHANT-FLOW-001 §4A.6(구현 관례) | MERCHANT-FLOW-001 §4A.6 | 없음 |
| BR-BIZ-286 | 희망 서비스는 체크 기록만이며 효력이 없다(실제 서비스는 가맹점 계약 승인·개통에서 정함) | 확정 | 사용자 | D-FLOW-003 ③ | MERCHANT-FLOW-001 §4A.6 | 없음 |
| BR-BIZ-287 | 접수 항목(경로·대행사·담당자·희망 서비스)과 접수일은 열린 건에서만 OPERATOR 1인이 수정하고 이력을 남기며, 접수일 수정은 사유 필수·미래 불가다 | 확정 | 사용자 | D-FLOW-007 ④, D-FLOW-009 ① | MERCHANT-FLOW-001 §4A.3 | 없음 |
| BR-BIZ-288 | 영업대행사는 MASTER 기준 목록(이름·사용/중지 상태만, 수수료·정산 없음)이며 등록·이름 수정·중지는 OPERATOR 1인 + 이력, 삭제 없음, 이름 UNIQUE, 중지 대행사는 새 접수에서 선택 불가(기존 표시 유지)다 | 확정 | 사용자 | D-FLOW-003 ⑤, D-FLOW-005 ④ | MERCHANT-FLOW-001 §4A.7 | 없음 |
| BR-BIZ-289 | 담보는 참고 기록만이다 — 효력 없음(선지급·지급 판단에 쓰지 않음), 운영자 1인, 이력, 화면에 '참고 기록 — 선지급·지급 판단에 쓰이지 않음'을 표시한다 | 확정 | 사용자 | D-FLOW-003 ④ | MERCHANT-FLOW-001 §4A.8 | 없음 |
| BR-BIZ-290 | 담보 참고 기록은 사업자 하위 증권별 여러 건(보증보험사·증권번호·금액(정수, 원)·KRW 고정·시작일·만기일)이고 정정은 새 행, 해지·반환은 새 행으로 종료일 + 사유, 만기 알림은 없다 | 확정 | 사용자 | D-FLOW-005 ⑤, D-FLOW-007 ⑥ | MERCHANT-FLOW-001 §4A.8 | 없음 |
| BR-BIZ-291 | 현재 증권 기록 사이에 (보증보험사, 증권번호)가 같으면 거절한다 | 제안 | 적용 | MERCHANT-FLOW-001 §4A.8 리뷰 MINOR-C | MERCHANT-FLOW-001 §4A.8 | 없음 |
| BR-BIZ-292 | 업종분류(직접 입력 1~50자, 빈 값 허용)와 가상자산 취급 여부(예/아니오/미확인, 기본 미확인)는 표시용이며 효력이 없고, 변경은 운영자 1인 + 사유 필수 + 이력이다. 가맹점 등급은 쓰지 않는다 | 확정 | 사용자 | D-FLOW-003 ⑥, D-FLOW-005 ③ | MERCHANT-FLOW-001 §4A.10 | 없음 |
| BR-BIZ-293 | 영중소 등급·상점/카드사 그룹은 지금 두지 않는다 | 확정 | 사용자 | D-FLOW-003 ⑦ | MERCHANT-FLOW-001 §4A.13 | 없음 |
| BR-BIZ-294 | KYC로 대표자 생년월일(대표자마다)과 실소유자('대표자와 같음' 기본, 다르면 이름·생년월일·지분율, 최대 4명)를 수집하며 성별·국적·영문 이름·대표자 본인 지분율은 받지 않는다 | 확정 | 사용자 | D-FLOW-001 ④, D-FLOW-002 ②③, D-FLOW-003 ⑪, D-FLOW-007 ③ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-295 | 실소유자 지분율은 0.01~100.00이고 합이 100 이하이며, 25%는 입력 거부 기준이 아닌 안내 기준이다 | Demo값 | 사용자 | D-FLOW-002 ③, D-FLOW-005 ⑦ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-296 | KYC 입력·변경은 OPERATOR 1인 + 사유 필수이며 등록 완료(REGISTERED)의 필수 조건이 아니다 | 확정 | 사용자 | D-FLOW-005 ⑦ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-297 | 대표자 생년월일·실소유자는 마스킹 표시(생년월일 ****-**-**, 실소유자 이름 홍**, 지분율 비마스킹)하고 원문 조회는 별도 권한·사유(카드번호 패턴 거부)·직전 재인증·조회마다 감사이며, 원문 조회 1회 = 사업자 하나의 KYC 현재 값 전체(감사 1행)다 | 확정 | 사용자 | D-FLOW-002 ①, D-FLOW-005 ⑥⑧, D-FLOW-008 ③ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-298 | UNMASK 권한은 Demo seed 계정에 미리 주지 않고 SECURITY_ADMIN이 기존 2인 역할 부여 절차로 준다 | 확정 | 사용자 | D-FLOW-005 ⑧ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-299 | 마스킹된 KYC 수정은 입력한 칸만 바꾸고(빈 칸 유지), 수정 화면에 원문을 채우지 않으며, 등록된 값 지우기는 칸마다 사유 필수이고 과거 값은 이력으로 저장만 한다 | 확정 | 사용자 | D-FLOW-008 ①, D-FLOW-009 ② | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-300 | 대표자 이름 수정·순서 변경에도 같은 사람이면 생년월일을 유지하고(대표자 안정 식별자), 대표자를 지우고 새 사람을 추가하면 새로 입력한다 | 확정 | 사용자 | D-FLOW-008 ② | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-301 | KYC 현재 값·마스킹 표시·원문 조회는 현재 대표자 행만 대상으로 하고 종료된 대표자 행은 저장만 하며 응답하지 않는다 | 제안 | 적용 | D-FLOW-009 Coordinator 적용 메모(3차 리뷰 MAJOR-3) | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-302 | KYC 보관·파기 기간은 LIVE 전에 정하고 그때까지 파기 기능이 없다(Demo는 지금 수집) | 확정 | 사용자 | D-FLOW-002 ④ | MERCHANT-FLOW-001 §4A.11 | 없음 |
| BR-BIZ-303 | 사업자 메모는 추가만 가능(ADMIN 소유 append-only, 종류 고정 일반·상담·계약·리스크, 1~1,000자, 카드번호 패턴 거부)이며 종료 사업자에도 허용한다 | 확정 | 사용자 | D-FLOW-003 ⑧, D-FLOW-004 ③, D-FLOW-007 ⑤ | MERCHANT-FLOW-001 §4A.12 | 없음 |
| BR-BIZ-304 | 저장(또는 전이) 한 번에 공통 '사유 및 비고' 칸 하나를 두며, 그 저장에 사유 필수 항목이 하나라도 있으면 필수, 없으면 선택이다 | 확정 | 사용자 | D-FLOW-006 | MERCHANT-FLOW-001 §4A.3a | 없음 |
| BR-BIZ-305 | 공통 '사유 및 비고' 칸 전체에 항상 카드번호 패턴 검사를 적용한다(기존 사업자 중요정보 사유 포함, 새 저장부터) | 확정 | 사용자 | D-FLOW-007 ①, Q-PII-6 | MERCHANT-FLOW-001 §4A.3a | 없음(미구현 — 진단 e-2) |
| BR-BIZ-306 | KYC·리스크 표시·담보는 섹션별로 따로 저장한다(섹션 저장 1회 = MASTER 명령·TX 1개) | 확정 | 사용자 | D-FLOW-007 ② | MERCHANT-FLOW-001 §4A.3a | 없음 |
| BR-BIZ-307 | 기존 대표자·담당자 개인정보 표시는 현행(원문) 유지한다 | 확정 | 사용자 | D-FLOW-003 ⑩ | MERCHANT-FLOW-001 §4A.13 | BusinessReaderSpec(M)::상세 원문 개인정보(현행) |
| BR-BIZ-308 | 대표자 생년월일·국적·지분·실소유자 등 AML/KYB 자료는 최초 등록 화면에서 받지 않는다 | 대체됨→BR-BIZ-294 | 기준 | POL POL-003 → D-FLOW-001 ④ | — | — |

## 진단

### (a) 대체 사슬

1. 사업자등록번호 노출: D-MD-008("등록번호 노출 통제") → D-MOPS-003(검색 응답 마스킹만) → D-MOPS-006 ③(중복 시 번호만 안내, businessId 비노출) → D-ADMIN-UI-004 ⑦(조회 응답 상호·마스킹 번호만) → D-MCODE-003 ②("사업자: 상호 · 마스킹 값") → D-BIZ-003(중복 시 상호·마스킹 번호·상세 링크) → D-BIZ-006 ②("어디서나 마스킹") → **D-BIZ-007**(화면·조회 응답 원문) → **D-PII-001**(범위 = 관리자 전체, 목록 감사 없음, Q-MOPS-04 닫음). 대장: BR-BIZ-045·046·047 대체됨 → BR-BIZ-030·044.
2. 중복 사업자 안내: D-MOPS-006 ③ → D-BIZ-003 → (번호 표기만) D-BIZ-007. BR-BIZ-045 → BR-BIZ-044.
3. 사업자 번호 조회 권한·응답: MERCHANT-BIZ-LOOKUP-001 "권한 OPERATOR"·D-ADMIN-UI-004 ⑦ → D-BIZ-006 ①③(OPERATOR·APPROVER, registrationStatus 포함) → D-BIZ-004(BUSINESS-PROFILE-001에 합침).
4. Site·계약 표시: D-ADMIN-UI-005 1안(채널·식별값 표시) → 같은 날 재질문 → 사람용 코드 발급(D-ADMIN-UI-005 괄호 안) → D-ADMIN-UI-007 세부. BR-BIZ-257 → BR-BIZ-251.
5. 계약 시작 소급: D-MOPS-006 ⑤(Revision 1) → D-MOPS-007 ②(새 Revision으로 확장) — 대체가 아닌 확장, BR-BIZ-107 하나로 둠.
6. 2인 승인 범위: D-MOPS-004 → D-MOPS-007 ③(빠진 명령 분류 추가) → D-MOPS-009 ③·D-MOPS-010 ①(등록자≠승인자 확대). 확장 사슬.
7. AML/KYB 수집: POL POL-003·§1-5("이번에 받지 않음") → D-FLOW-001 ④(이번에 수집) → D-FLOW-002·005·007·008·009 세부. BR-BIZ-308 → BR-BIZ-294.
8. 사업자 상세 편집 방식: D-ADMIN-UI-010 ⑥ → D-ADMIN-UI-011 ② → D-FLOW-007 ②(새 섹션 예외). 화면 배치라 규칙 행은 BR-BIZ-306만.
9. 미결 → 답: Q-MD-08 → D-MD-016, Q-MD-09 → D-MD-017, Q-MD-10 → D-MD-018(MASTER-DOMAIN-001 본문은 아직 "미결" — (c)-1), Q-PII-6 → D-FLOW-007 ①.

### (b) 결정 간 충돌·긴장

1. **D-MS-005 × D-MD-008(BR-BIZ-085 × BR-BIZ-081)**: REJECTED Site는 "새 Site 등록으로 재신청"인데, REJECTED는 Site 생명주기를 바꾸지 않고(`MerchantSite` lifecycle ACTIVE 유지 — Site 종료 명령 없음) 채널 중복 검사가 활성 Site 기준이라 같은 채널로 새 Site를 등록하면 `SITE_CHANNEL_DUPLICATE`로 막힌다. 재신청 경로가 사실상 없다. Q-MD-18과 함께 정해야 한다.
2. **D-MOPS-010 번호 매김 불일치**: DECISIONS는 ① 재개 승인자 규칙(Q-25) ② BRN 지문 HMAC(Q-26) ③ Route 종료 먼저(Q-27)인데, MERCHANT-OPS-002 §9.29·L-4는 HMAC을 "D-MOPS-010 ①", §11.2는 Q-MOPS-25를 "D-MOPS-010 ②"로 적고 시험 `SiteConfirmationAndReviewSpec`도 "D-MOPS-010 ②"를 재승인 규칙으로 쓴다. 의미 충돌은 아니나 BR-ID 연결 시 혼동 — 대장은 DECISIONS 번호를 따랐다.
3. **가맹점 코드와 Site·계약 코드의 감사 대상 방침 차이**: D-MCODE-001 Q-MC-4(새 감사·승인 대상 = 코드) vs D-ADMIN-UI-007 ⑤/Q-SC-9(Site·계약은 내부 키 유지 + 화면 라벨). 둘 다 사용자 답이라 충돌은 아니지만 대상 종류마다 저장 규칙이 다르다 — 의도 확인 권장.
4. **가맹점 코드 외부 노출 범위**: D-MCODE-001 Q-MC-1 "외부 연동·정산서에도 노출" vs D-MCODE-002 Q-MC-8 "응답에 보여주기만" — 후자는 "출력 전용(입력·식별 아님)"이라는 한정으로 읽히며 충돌 아님. 정산서 노출(BR-BIZ-072)은 정산서 기능이 없어 미검증.
5. **D-BIZ-003 "마스킹 번호(앞 3자리)" 문구가 D-BIZ-007 후에도 DECISIONS 행에 그대로**: D-BIZ-007이 "응답 마스킹 번호의 화면·응답 부분을 변경"이라고만 적어, D-BIZ-003 행 자체에는 대체 표지가 없다(D-MOPS-006 ③처럼 굵은 대체 표기 없음).
6. **D-FLOW-001 ① "사업자 REGISTERED + 계약 완료" vs MERCHANT-FLOW-001 §4A.5 "최신 접수 건이 계약 완료"**: 사용자 문구에는 "최신"이 없다. 계약 완료 건이 있으면 새 접수를 막으므로(D-FLOW-004 ①) "해지 뒤 새 접수 진행 중"인 경우만 차이가 난다 — 그때 관문을 막는 것이 맞는지(해지된 이력이 있어도 새 건이 완료되기 전까지 막힘) 확인 필요. BR-BIZ-280 제안.
7. **도메인 경계 중복**: §9(결제 허용 판정)·§8(한도·정책 Assignment)는 D-MD/D-MS/D-MS2 결정이라 BIZ에 두었지만 판정 소유자는 PAYMENT(PAY 대장), 요율·Snapshot(BR-BIZ-197)은 FIN. 3단계 종합 때 한 곳을 정본으로 정하고 다른 쪽은 참조만 두어야 한다.

### (c) 정책서·명세 본문이 현재 결정과 다른 곳

1. `docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md` §2.2 MerchantSite·SiteAssignment·PaymentMethodActivation·통화 활성화 행, §2.3: "미결 Q-MD-08/09/10"으로 남아 있음 — D-MD-016·017·018(2026-10-13)로 답해졌다.
2. `MASTER-DOMAIN-001` §3.3: "Site 등록은 entitlement revision을 올리지 않는다" — 코드·MERCHANT-OPS-002 L-6은 올린다(review M-2). 문서 정정 미완.
3. `docs/baseline/PayCore-사업자관리-업무정의-정책서-v1.1.md` §1-5·§6 POL-003("생년월일·실제소유자 정보 등 AML/KYB 자료는 받지 않는다"): D-FLOW-001 ④로 변경됐으나 본문 미반영(RULE-GOV-001 P-3 사례).
4. 같은 정책서 §7 범위 밖 표: "접수일·접수경로·상담중 → 이용신청·영업관리", "담보·보증보험 → 리스크/수납", "가상자산 취급 → AML/KYB·위험평가"로 분리돼 있으나 D-FLOW-001·003은 이들을 사업자 하위(MASTER)에 둔다 — 본문 미반영.
5. 같은 정책서 §4 업무 흐름 "사업자 등록 → … → Merchant 신청": D-FLOW-001 ① 관문(계약 완료)이 흐름에 없음.
6. `MERCHANT-OPS-002` §4.4·L-7: D-MOPS-006 ③(기존 businessId 비노출)을 현행처럼 서술 — D-BIZ-003으로 변경됨(L-7에 일부 언급).
7. `MERCHANT-OPS-002` §9.29·§11.2·L-4: D-MOPS-010 ①/② 번호가 DECISIONS와 반대((b)-2).
8. `MERCHANT-OPS-001` §2(27행) "응답은 마스킹 값만"(D-MOPS-003 원문)·§1(14행) "Q-MOPS-04(PROPOSED, 보류)": D-BIZ-007·D-PII-001로 응답 원문, Q-MOPS-04는 ANSWERED(닫힘). BRN-UNMASK-001은 반영했으나 MERCHANT-OPS-001 본문은 rev 0.1 그대로.
9. `docs/repository/MERCHANT-BIZ-LOOKUP-001-BUSINESS-LOOKUP.md`: 수용 기준 "권한 OPERATOR"·응답 마스킹 — D-BIZ-006 ①③·D-BIZ-007로 변경(작업은 BACKLOG로 남아 있으나 D-BIZ-004로 BUSINESS-PROFILE-001에 합쳐짐 — tasks 상태와 결정 불일치).
10. `docs/repository/PII-MASKING-POLICY.md` §7.3·§8 Q-PII-6: "결정 없음"으로 남아 있음 — D-FLOW-007 ①(2026-11-25)로 닫힘.
11. `MASTER-SNAPSHOT-001` §4.4: "재심사 허용 여부는 새 질문 후보 Q-MS-05" — D-MS-005로 답해짐.

### (d) 시험 없는 확정 규칙

- 미구현 기능이라 시험 없음: BR-BIZ-110·111·112(계약 철회·CSA 철회·갱신 — MASTER-LIFECYCLE-001), BR-BIZ-144(ADDITIONAL 공유 조건 명령), BR-BIZ-169(재개 등록자 규칙 — B3-4), BR-BIZ-178·179(기관 가맹점 참조 입력·마스킹, Route 등록자 — B3-3), BR-BIZ-196(한도·정책 배정 2인/1인 — B4·B5), BR-BIZ-048(BRN 지문 HMAC — B3-3a), BR-BIZ-072(정산서 코드), BR-BIZ-251~256(SITE-CONTRACT-CODE-001 BACKLOG), BR-BIZ-270~306 중 확정 전부(MERCHANT-FLOW-001 미병합).
- 구현은 있으나 직접 단언 시험을 찾지 못함: BR-BIZ-001(사업자 1:N 가맹점), BR-BIZ-006(법인번호 자동 병합 없음), BR-BIZ-008(REGISTERED ≠ 심사 승인), BR-BIZ-015(INACTIVE 전이 없음·결제 비연결), BR-BIZ-060(Merchant 소속 불변), BR-BIZ-062(같은 이름 허용), BR-BIZ-115(GID 비상속), BR-BIZ-135(Default 비상속), BR-BIZ-149(결제 중 MID 변경 금지 — PAY 쪽 확인 필요), BR-BIZ-197.
- 부분 검증: BR-BIZ-061(평가 매핑만, 차단 기록 생성·해제·다건 독립 없음), BR-BIZ-140(종료 명령 없음), BR-BIZ-147(교체 한 TX 명령 없음), BR-BIZ-194(LIVE Gate 차단 시험 없음), BR-BIZ-214(2키 교체·mTLS 없음), BR-BIZ-230(1인 즉시 안전 방향 명령은 Site 심사 거절만).

### (e) 코드가 규칙과 다른 곳

1. BR-BIZ-048(D-MOPS-010 ②): `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/AdminCommandExecutor.kt:427-428` — 모든 관리 명령 지문이 `m1:` 무염 SHA-256(사업자등록번호 원문 포함 필드). PII-MASKING-POLICY X-2와 같음, 예정 배치 B3-3a(LIVE Gate).
2. BR-BIZ-305(D-FLOW-007 ①): `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessChangeReason.kt:8-21` — 사업자 중요정보 사유에 카드번호 패턴 검사 없음(`BusinessTextRule`만).
3. BR-BIZ-082·085 상호작용: `apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/MerchantOnboardingService.kt:128-129` — 채널 중복을 `findActiveByChannel`(활성 Site 기준)로만 본다. 종료 Site 채널은 다른 가맹점이 재사용 가능(Q-MD-18 미결인데 코드가 "허용"으로 정해 둔 상태 — 다만 Site 종료 명령이 없어 지금은 발생 경로 없음), REJECTED Site 채널은 재신청을 막는다((b)-1).
4. BR-BIZ-169(D-MOPS-010 ①): `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/PaymentMethodActivation.kt:82`·`CardRouteActivation.kt:50` `resume(...)`에 원 등록자 검사 없음 — 2인 재개 경로가 아직 없어 위반은 아니나, 경로를 열 때 함께 넣어야 한다.
5. BR-BIZ-279(D-FLOW-001 ①): `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/MasterRejection.kt:105` 근처 — 계약 완료 관문 거절 코드 없음, `Merchant.register`는 REGISTERED만 본다(main 기준 미구현).
6. BR-BIZ-178(D-MOPS-009 ①): 관리 조회 `****1234` 미구현 — 현재는 SELECT 자체를 하지 않아 결정보다 엄격(위반 아님, PII X-3).

### (f) 미결·질문 후보(금융·보안·개인정보·상태 의미)

1. **Q-MD-18(PROPOSED)** 종료 Site 채널의 다른 가맹점 재사용 — 코드는 사실상 "허용"(e-3). 권장안 "예외 승인 없으면 거절"과 다름. 상태 의미.
2. **REJECTED Site 재신청 경로**(새 질문 후보): D-MS-005 "새 Site 등록"과 D-MD-008 활성 채널 중복이 함께 있으면 같은 채널 재신청 불가. ① REJECTED Site를 채널 중복 검사에서 빼기 ② 재신청 전 Site 종료(1인 안전 방향) ③ 예외 승인 기록 — 중 선택 필요. 상태·식별값 의미.
3. **Q-MD-06·Q-MD-07(PROPOSED)** 추가 MID·Default 교체/종료 — MASTER-LIFECYCLE-001 착수 전 필요. 금융 권리 의미.
4. **최신 접수 건 관문**((b)-6): 해지 뒤 새 접수 진행 중이면 새 가맹점 등록을 막는지 — 사용자 문구 확인.
5. **D-FLOW-009 적용 메모·BR-BIZ-301**(종료된 대표자 KYC 비응답): 사용자 답이 아닌 Coordinator 적용 — 개인정보 노출 범위라 다음 보고에서 확인 필요.
6. **"첫 계약 승인" 정의(BR-BIZ-067, C-14)**: 효력 전 승인·철회된 계약도 "승인 뒤"로 보는 해석 — 가맹점 코드 불변 시점(외부 노출 키) 의미라 확인 권장.
7. **Site 심사 개별 전이(BR-BIZ-084)·통화 활성화 소급 금지(BR-BIZ-172)·재개 "새 근거" 정의(BR-BIZ-162)·판정 순서(BR-BIZ-213)**: 명세가 "관례/해석"으로 정한 상태·금융 판정 — 사용자 확인 전 `제안`.
8. **사업체 구분·대표자 추가의 중요정보 취급(BR-BIZ-017)·중요정보 1인 처리(BR-BIZ-018, TBD-07 기본값)**: 감사·승인 수준 의미 — 확인 필요.
9. **Q-PII-5(개인정보 보관·파기)**·**Q-PII-7(상호 마스킹)**: 미결. KYC 수집(D-FLOW-002 ④ LIVE 전 결정)과 묶어 LIVE Gate 질문으로.
10. **업무 근거 참조 원천 대조(BR-BIZ-234)·HMAC 키 관리(L-23)**: LIVE Gate 메모 수준 — 사용자 결정 필요 여부 Coordinator 판단.
11. **사업자 번호 조회 작업 상태**: D-BIZ-004로 MERCHANT-BIZ-LOOKUP-001을 합쳤는데 `harness/tasks.json`에는 BACKLOG — 질문이 아니라 Coordinator 정리 항목.
