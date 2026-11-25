# MERCHANT-FLOW-001 사업자·가맹점 업무 흐름 재설계 명세 (rev 0.3d)

- rev 0.3d (2026-11-25, 4차 리뷰 CHANGES_REQUIRED MAJOR-A·MINOR-A~F 반영 — 대표자 key migration을 SQL로(Coordinator 결정 [구현 관례]), 관문 Port 이름·반환 값, 담보 중복 검사 위치, 섹션 첫 저장 기대값, KYC 새 버전 범위, 원문 조회 스냅샷. 사용자 결정 변경 없음, 남은 사용자 질문 없음. 문서만, 자기 완료 판정 아님).
- rev 0.3c (2026-11-25, Q-FLOW-36·37 사용자 답 반영 — D-FLOW-009, Coordinator 적용 메모(MAJOR-3) 반영, 3차 리뷰 CHANGES_REQUIRED MAJOR-1~3·MINOR-1~8 반영. 남은 사용자 질문 없음. 문서만 — 코드·DB·결정·작업 상태 변경 없음, 자기 완료 판정 아님).
- rev 0.3b (2026-11-25, Q-FLOW-28·28b·29~35 사용자 답 반영 — D-FLOW-007·008, 재리뷰 CHANGES_REQUIRED N-1~N-3·n-1~n-8 반영, 남은 질문 Q-FLOW-36·37. 문서만 — 코드·DB·결정·작업 상태 변경 없음, 자기 완료 판정 아님).
- rev 0.3a (2026-11-25, Q-FLOW-15~27 사용자 답 반영 — D-FLOW-004·005·006, 독립 리뷰 CHANGES_REQUIRED M-1~M-5·m-1~m-13 반영, 남은 질문 Q-FLOW-28~32. 문서만 — 코드·DB·결정·작업 상태 변경 없음, 자기 완료 판정 아님).
- rev 0.3 (2026-11-25, Q-FLOW-2b·3b·6~14 사용자 답 반영 — D-FLOW-003. §4A 확정 모델 신설(구현 가능한 명세), §5 답 줄 추가, §5.2 질문 Q-FLOW-15~24, §6 배치 계획 FLOW-1~10으로 재작성, §7 위험 갱신. 문서만 — 코드·DB·결정·작업 상태 변경 없음, 자기 완료 판정 아님).
- rev 0.2a (2026-11-25, Q-FLOW-5b~5e 사용자 답 반영 — D-FLOW-002, 모두 권장안).
- rev 0.2 (2026-11-25, Q-FLOW-1~5 사용자 답 반영 — D-FLOW-001. 후속 질문 Q-FLOW-2b·3b·5b~5e 추가. 코드·DB 변경 없음).
- rev 0.1 (2026-11-24, 명세 초안 — 코드·DB·결정 변경 없음, 자기 완료 판정 아님. Coordinator 검토 → 사용자 결정 대상).
- 근거: D-ADMIN-UI-011 ①("업무 흐름 변경은 별도 명세에서 사용자 결정 후") — 이 문서가 그 별도 명세다. 작업 카드 `harness/tasks.json` MERCHANT-FLOW-001.
- 표기
  - **[사실-결정]** `docs/DECISIONS.md`에 있는 사용자 결정. **[사실-명세]** 저장소 명세·baseline 문서에 적힌 내용. **[사실-pg-lab]** 사용자가 준 참고 화면(pg-lab 내부관리자, 2026-11-24 캡처)에 보이는 것.
  - **[추측]** 화면만 보고 짐작한 의미(pg-lab 내부 동작·법규 해석 등). 결정 근거로 쓰지 않는다.
  - **Q-FLOW-n** 사용자 질문(권장안은 제안일 뿐 결정 아님). 답 전에는 해당 운영 동작을 구현하지 않는다(CLAUDE.md).
- 참고 자료: pg-lab 캡처 `n2-business-detail.png`(사업자 정보), `s1.png`(계약 진행현황 목록), `n3-store-list.png`(상점 목록), `n4-store-detail.png`(상점 상세). 세션 scratchpad에만 있고 저장소에는 넣지 않았다.
- 관련 문서: `docs/baseline/PayCore-사업자관리-업무정의-정책서-v1.1.md`(PC-BIZ-POL-001), `BUSINESS-PROFILE-001-BUSINESS-PROFILE.md`, `MERCHANT-OPS-002-MERCHANT-ONBOARDING.md`, `MERCHANT-IA-001-SCREEN-ITEMS.md`(§5), `PII-MASKING-POLICY.md`, `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md`.

---

## 1. 목적·범위·비범위

### 1.1 목적

pg-lab은 **사업자 단계에서 영업 접수·계약을 진행**하고, 계약이 완료된 사업자만 상점(가맹점)을 등록한다. PayCore는 사업자를 **기본정보 기준정보**로만 두고,
계약(Revision 승인)·MID·개통은 **가맹점 단위**로 진행한다. 두 흐름의 차이를 드러내고, 어떤 부분을 PayCore에 들여올지 사용자가 결정할 수 있도록 선택지·질문을 정리한다.

### 1.2 범위(이 문서가 다루는 것)

- 사업자 단계의 **계약 진행(영업 파이프라인)** 상태·전이·권한, 가맹점 등록 허용 조건.
- **계약 조건 항목**(접수 경로·영업대행사·계약 담당자·신청 서비스·수수료율·담보)의 위치(사업자/가맹점/별도 도메인)와 효력 여부.
- **등급·분류**(가맹점 등급·영중소 등급·업종분류·가상자산 취급)의 의미와 위치.
- **KYC/KYB 성격 항목**(대표자 생년월일·성별·지분율, 실소유자)과 개인정보 원문 조회 절차.
- **메모**(append-only)와 **계약서 파일** 보관.

### 1.3 비범위

- 정산 실행·지급·입금 확인·선지급(SETTLEMENT-001), 수수료·VAT 계산과 정책 내용 모델(SETTLEMENT-CONTRACT-001), 지급계좌(MASTER-PAYOUT-ACCOUNT-001), API 키(MASTER-CREDENTIAL-001).
- 키인 결제·가상계좌·체크카드 등 **새 결제수단 채택**(CLAUDE.md: "체크카드/할부·인증 범위 추가안은 사용자 채택 전", D-ADMIN-UI-004 ⑤ "지금은 신용카드만, 구조만 확장 가능").
- AML/KYB 심사 요청·승인 연동 자체(PC-BIZ-POL-001 §1-5 [확정] "후속 연동 대상") — rev 0.3은 사용자 결정(D-FLOW-001 ④·D-FLOW-002)에 따라 대표자 생년월일·실소유자를 **수집·표시만** 한다. 심사 요청·승인·반려 상태와 외부 연동은 여전히 범위 밖이다.
- 화면 패턴(섹션 2열 표, 결제 준비 상태 줄, 탭, 필터 묶음, 사업자 상세 항상 편집 폼) — D-ADMIN-UI-011로 이미 적용(MERCHANT-IA-001 B1~B6 DONE).
- 이 문서 자체는 코드·DB·migration을 바꾸지 않는다. rev 0.3부터 §4A·§6이 구현 명세이고, 각 배치는 §6 순서대로 별도 TDD 구현·검증한다.
- 사용자가 "나중에"·"추가 안 함"을 고른 항목: 사업자 수수료율(Q-FLOW-4), 가맹점 등급(Q-FLOW-9), 영중소 등급·상점 그룹·카드사 그룹(Q-FLOW-10), 계약서 파일 업로드(Q-FLOW-12), 기존 개인정보 마스킹 전환(Q-FLOW-13), 국적·홈페이지·대표자 영문 이름(Q-FLOW-14), 성별(Q-FLOW-5c), 해지 연쇄(Q-FLOW-3b — MASTER-LIFECYCLE-001).

---

## 2. 현재 흐름 vs pg-lab 흐름

### 2.1 단계 비교

| 단계 | PayCore 현재 | pg-lab |
|---|---|---|
| 1 | 사업자 등록: 번호 확인 먼저 → DRAFT(구분·번호·상호) → 등록 완료 REGISTERED, OPERATOR 1인 **[사실-결정 D-BIZ-001·002·006, D-ADMIN-UI-011]** | 사업자 등록: 번호 먼저 확인 → 접수(진행 상태 "상담 중") **[사실-pg-lab]** |
| 2 | (없음 — 영업 접수·상담 단계 기록이 없다) | 상담 중 → 계약서 접수 → **계약 완료 / 계약 불가 / 계약 해지** **[사실-pg-lab s1·n2]** |
| 3 | 가맹점 등록 마법사(REGISTERED 사업자만): Merchant·Site·계약 초안(Revision 1)·CSA, OPERATOR 1인 **[사실-결정 D-MOPS-004, D-BIZ-001]** | "계약 완료"인 사업자에서만 "상점 등록" 버튼 **[사실-pg-lab s1: 계약 완료 행에만 버튼]** |
| 4 | 계약 Revision 승인 2인(요청자≠승인자, 등록자≠승인자, 업무 근거 참조 필수) **[사실-결정 D-MOPS-004·007·008]** | 상점 단위 "계약상태" 선택 상자(상점 상세 기본 정보 탭) **[사실-pg-lab n4]**. 승인 방식은 화면에 안 보임 **[추측: 단순 선택 저장]** |
| 5 | 첫 Default MID 발급 2인 → 개통(결제수단·Site 적용 확인·Site 심사·통화·카드 Route) 2인 **[사실-결정 D-MOPS-004·007, D-MD-016~018]** | 상점 머리 "결제 준비: 계약상태·정산 정보·결제수단·카드사 등록 n/8" **[사실-pg-lab n4]** |
| 6 | 한도·수수료·정산 정책 배정 2인(MERCHANT-OPS-002 B4 — 미구현) **[사실-명세]** | 수수료율은 사업자 계약 조건에서 입력, 별도 메뉴 "수수료 변경 승인"·"정산 계좌 변경 승인" **[사실-pg-lab 메뉴]** → 변경은 승인 절차가 있는 듯 **[추측]** |

### 2.2 항목별 비교

| 축 | PayCore 현재 | pg-lab | 비고 |
|---|---|---|---|
| 상태 | 사업자: `lifecycle` ACTIVE/TERMINATED + `registration_status` DRAFT/REGISTERED/INACTIVE(진위확인 `NOT_CHECKED`만) **[사실-결정 D-BIZ-001]**. 계약: Revision 등록(미승인)·승인 사실 append-only·효력 [from,to) **[사실-결정 D-MD-004]** | 사업자 "진행 상태" 상담 중/계약서 접수/계약 완료/계약 불가/(계약 해지) 하나 **[사실-pg-lab]** | PayCore의 REGISTERED는 "내부 기본정보 등록 완료"일 뿐 계약·KYB 아님 **[사실-명세 PC-BIZ-POL-001 §4]** |
| 계약 단위 | **계약 당사자 = Business**, Scope = MERCHANT 또는 SITES, 한 계약의 모든 Revision은 같은 Merchant 대상, 한 Scope = 한 Merchant **[사실-결정 D-MD-004·010·013]**. 즉 "사업자와 맺지만 가맹점 하나에 묶인 계약" | 사업자 하나에 계약 진행 하나, 상점 수 n개 **[사실-pg-lab n2 "상점 수"]** → 사업자 단위 계약 **[추측]** | 중요: PayCore도 계약 상대방은 이미 사업자다. 다른 것은 **Scope(적용 범위)와 승인 단위** |
| 승인 방식 | 계약 승인·MID·개통·수수료 배정 = 2인, 등록·초안 = 1인, 안전 방향 = 1인 즉시 **[사실-결정 D-ADMIN-003, D-MOPS-004·007]** | 진행 상태를 선택 상자로 변경 후 저장 **[사실-pg-lab]**, 2인 여부 불명 **[추측]** | |
| 수수료 위치 | 정책 버전(FEE_SCHEDULE) 식별 등록부 + (CSA, MID, 통화) Assignment(CSA_DEFAULT/MID_OVERRIDE, Override 전체 대체), 배정 2인, 내용·계산은 SETTLEMENT-CONTRACT-001 **[사실-결정 D-MD-001·018, D-MS2-001·003, D-MOPS-004]**. 원승인 Snapshot 재사용 **[사실-CLAUDE.md]** | 사업자 계약 조건: 인증 신용/체크, 키인 신용/체크 %, 가상계좌 건당 원 **[사실-pg-lab n2]** | 수수료율 변경 2인 **[사실-결정 D-ADMIN-003]** |
| 담보 위치 | 없음. 레거시 담보·보증보험은 "리스크/수납 관리" 후속 도메인 **[사실-명세 PC-BIZ-POL-001 §7]** | 사업자 "담보": 보증보험 금액·증권 기간 **[사실-pg-lab]** | |
| 신청 서비스 | CSA = 서비스 종류 1개(카드 PG), 결제수단 개통은 (MID, 수단) PaymentMethodActivation **[사실-결정 D-MD-002·017]**. 지금은 카드만 **[사실-결정 D-ADMIN-UI-004 ⑤]** | 사업자 계약 조건의 체크박스: 인증 결제/키인 결제/가상계좌 **[사실-pg-lab]** | |
| 영업 정보 | 없음. 접수일·접수경로·상담중은 "이용신청·영업관리" 후속 **[사실-명세 PC-BIZ-POL-001 §7]** | 접수 경로(직접 영업·전화·홈페이지)·영업대행사·계약 담당자(admin01)·접수일 **[사실-pg-lab]**, 별도 "영업대행사" 메뉴 | 가맹점 범위 제한은 "영업 담당자별 조회 범위 요구가 생길 때만" **[사실-결정 D-ADMIN-002]** |
| 등급·분류 | 없음. 영중소 등급은 MerchantService·FeePolicy 후속, 가상자산 취급은 AML/KYB·위험평가 후속 **[사실-명세 PC-BIZ-POL-001 §7]** | 사업자: 업종분류·가맹점 등급(B 보통)·가상자산 취급. 상점: 영중소 등급(일반/영세/중소1~3)·카드사 그룹·상점 그룹·카테고리 **[사실-pg-lab]** | |
| 대표자·실소유자 | 대표자 유효기간 행(이름 등), 생년월일·국적·실제소유자는 **수집하지 않음** **[사실-명세 PC-BIZ-POL-001 POL-003, BUSINESS-PROFILE-001 §3.2]** | 대표자 이름·영문·생년월일·성별·휴대폰·지분율, 실소유자("대표자와 같음") **[사실-pg-lab]** | |
| 개인정보 표시 | 사업자 상세는 조회 역할(OPERATOR·APPROVER)에 **원문** + 열람마다 감사·속도 제한, 사업자등록번호는 관리자 화면 원문 **[사실-결정 D-BIZ-006 ②, D-PII-001]** | 대표자·담당자 이름·생년월일·휴대폰·이메일 **마스킹 + 항목별 "원문 조회"**, 수정 시에만 입력 **[사실-pg-lab]** | 방향이 반대 |
| 메모 | 없음 | 종류 + 내용, "남긴 메모는 고치거나 지울 수 없습니다", "개인정보와 카드번호는 적지 않습니다" 안내 **[사실-pg-lab]** | |
| 계약서 | 승인 요청의 **업무 근거 참조**(자유 문구 불가, 지정 역할만 별도 조회·감사) **[사실-결정 D-MOPS-008 ①]**. 파일 저장 기능 없음, 첨부 보관 정책 TBD-09 **[사실-명세]** | 계약서 파일 업로드(contract.pdf) **[사실-pg-lab]** | |

---

## 3. 차이 항목과 의미

| # | 차이 | 금융 | 계약 | 개인정보 | 규제 | 감사 |
|---|---|---|---|---|---|---|
| G-1 | 사업자 단계 계약 진행 상태(상담 중→계약서 접수→완료/불가/해지) | 상태 자체는 금융효과 없음. 단 "해지"가 결제 차단·정산에 이어지면 금융효과(D-MD-006 차단 기록과 연결 필요) | 영업 진행 기록인지, 법적 계약 체결 사실인지 정의가 필요. PayCore의 계약 체결 사실 = Revision 승인(2인) | 없음 | 없음 | 누가 언제 바꿨는지 이력 |
| G-2 | 계약 완료 후에만 가맹점 등록 | 없음(등록은 권리 효과 없음 — D-MOPS-004 1인) | 가맹점 등록 관문이 하나 늘어남. 기존 가맹점·Demo seed 이관 필요 | 없음 | 없음 | 거절 사유 감사 |
| G-3 | 계약 단위(Scope)를 사업자 전체로 | **큼** — MID 발급 근거(D-MD-012·015), 권리 Snapshot, 공유 조건(같은 CSA)이 모두 계약 Revision 기준 | D-MD-004·010·013과 충돌 | 없음 | 없음 | 기존 승인 근거 해석 변경 |
| G-4 | 수수료율을 사업자 계약 조건에 | **큼** — 수수료 원천이 둘이 되면 원승인 Snapshot에 어느 값을 쓰는지 모호. (CSA, MID, 통화) Assignment 규칙(D-MD-018, D-MS2-001)과 충돌 소지 | 수수료율 변경 = 2인(D-ADMIN-003) | 없음 | 영중소 우대수수료와 연결 **[추측: 카드 가맹점 수수료 우대 제도]** | 변경 전후 |
| G-5 | 신청 서비스(인증/키인/가상계좌) | 키인·가상계좌는 새 결제수단·새 금융 흐름(가상계좌 입금 대사 등) | 결제수단 채택은 사용자 결정 전 | 없음 | 키인 결제는 별도 심사 대상일 수 있음 **[추측]** | |
| G-6 | 담보(보증보험) | 선지급 손실 책임·보류금·한도와 연결 가능(baseline 금융통제 대안 §4 "담보/보류금 정책") | 계약 조건 | 없음 | 없음 | 금액·기간 변경 이력 |
| G-7 | 접수 경로·영업대행사·계약 담당자 | 영업대행사에 수수료(대리점 수수료)를 지급한다면 정산 대상 **[추측]** | 대행사 계약은 별도 | 대행사 담당자 개인정보 | 없음 | 담당자별 조회 범위(D-ADMIN-002 단서) |
| G-8 | 가맹점 등급·업종분류·가상자산 취급 | 등급이 한도·지급 보류·선지급 기준에 쓰이면 금융효과 | 없음 | 없음 | 가상자산 취급은 AML 고위험 업종 판단에 쓰일 수 있음 **[추측]** | 등급 변경 이력 |
| G-9 | 영중소 등급(상점) | 카드 수수료율 결정 요인 **[추측: 공시 기준 반기 갱신]** | 수수료 정책 Snapshot에 등급 반영 방식 | 없음 | 있음 **[추측]** | |
| G-10 | 대표자 생년월일·성별·지분율, 실소유자 | 없음 | 없음 | **큼** — 새 개인정보 수집(목적·보관·파기 Q-PII-5 미결) | 고객확인(KYC/KYB) 의무 항목일 수 있음 **[추측]**. 현재 결정은 "수집하지 않음, 후속 단계에서 필요성 검증"(POL-003) | 원문 조회 감사 |
| G-11 | 개인정보 마스킹 + 원문 조회 | 없음 | 없음 | D-BIZ-006 ②(상세 원문)와 반대 방향 | 없음 | 원문 조회마다 감사 |
| G-12 | 메모(append-only) | 없음 | 협의 내용이 사실상 계약 증거가 될 수 있음 | 자유 문구에 개인정보·카드번호 유입 위험 | 없음 | 수정·삭제 불가 |
| G-13 | 계약서 파일 | 없음 | 계약 증빙 원본 | 서명자 개인정보 포함 | 보관 기간 **[추측: 법정 보관 의무 가능]** | 다운로드 감사 |
| G-14 | 상점 그룹·카드사 그룹·카테고리 | 그룹이 정산 묶음·Route 선택에 쓰이면 금융효과 **[추측]** | 없음 | 없음 | 없음 | |

---

## 4. 설계 선택지

### 4.1 선택지 A — 현행 계약 모델 유지 + 사업자 "계약 진행"(영업 파이프라인) 기록 추가

- 사업자에 **계약 접수 건**(가칭 `ContractApplication`, MASTER 소유 — 사업자 하위 기준정보)을 1:N으로 둔다. 열린 접수 건은 사업자당 최대 1개. 접수 건 = 접수일·접수 경로·영업대행사·계약 담당자(관리자 계정)·진행 상태·계약서 수령 근거 참조.
- 진행 상태: `CONSULTING`(상담 중) → `DOCUMENT_RECEIVED`(계약서 접수) → `COMPLETED`(계약 완료) | `REJECTED`(계약 불가, 종결) ; `COMPLETED` → `TERMINATED`(계약 해지, 종결). 종결 뒤 재진행 = 새 접수 건.
- **권리 효과 없음**: 결제 허용·MID 발급 근거는 지금처럼 가맹점 계약 Revision 승인(2인)이 유일하다. "계약 완료"는 "가맹점 등록을 시작해도 된다"는 영업 관문일 뿐이다.
- 가맹점 등록 관문: REGISTERED(D-BIZ-001) **그리고** 열린/완료 접수 건이 COMPLETED(Q-FLOW-2에 따라).
- 수수료·서비스·담보는 사업자에 **효력 있는 값으로 두지 않는다**(Q-FLOW-4·5·6에 따라 참고 기록 여부 결정).
- 장점: D-MD-*·D-MOPS-* 결정과 충돌 없음, MID·Snapshot·PAYMENT 무영향, 데이터 이전은 "기존 사업자에 이관 접수 건(또는 미기록)"만. pg-lab의 눈에 보이는 흐름(접수→계약 완료→상점 등록)을 그대로 얻는다.
- 단점: "계약 완료"(영업)와 "계약 승인"(가맹점 Revision 2인) 두 개념이 공존 → 용어 혼동. 화면 문구로 구분해야 한다(예: "계약 진행: 완료" vs "계약 승인: 승인됨").
- 기존 결정 충돌: 없음. PC-BIZ-POL-001 §7("접수일·접수경로·상담중 → 이용신청·영업관리 후속")을 **그 후속으로 여는 것**이므로 사용자 확인 필요(Q-FLOW-1).
- 2인 승인: 상태 전이는 권리 효과가 없어 1인 후보. "계약 해지"가 가맹점에 연쇄되면 D-ADMIN-003 "가맹점 종료·계약 철회" 2인 대상과 연결(Q-FLOW-3).
- 감사: ADMIN 감사(시도·결과) + MASTER 변경 이력(기존 틀). MASTER 소유권: 사업자 하위 기준정보라 MASTER가 맞다. PAYMENT 전파 없음(권리 입력 아님 → `entitlement_revision` 증가 없음).
- 규모 추정: 중(MASTER V 새 표 1~2 + 명령 3~4 + ADMIN 화면 API·"계약 진행현황" 목록·사업자 상세 섹션 + 가맹점 등록 관문). 기존 배치 크기 기준 3~4배치.

### 4.2 선택지 B — 계약 단위를 사업자로 이동

- 계약 Scope에 `BUSINESS`(사업자의 모든 가맹점)를 추가하거나 사업자 단위 Contract 하나로 바꾸고, Revision 승인(2인)을 사업자에서 한 번 한다. 가맹점은 사업자 계약을 상속. 수수료·서비스·담보를 사업자 계약 Revision 내용으로 둔다.
- 장점: pg-lab과 가장 비슷, 사업자 하나에 가맹점 여러 개일 때 승인 1회.
- 단점·충돌: **D-MD-004(Scope = MERCHANT/SITES), D-MD-010(한 Scope = 한 Merchant), D-MD-013(한 계약 = 같은 Merchant)과 정면 충돌**. MID 발급 근거(D-MD-012·015)·ADDITIONAL 공유 조건(D-MD-016 "같은 CSA")·정책 Assignment 키(D-MD-018 (CSA, MID, 통화))·권리 Snapshot 조립을 모두 다시 설계. 사업자 계약 Revision 하나가 바뀌면 모든 가맹점 권리가 동시에 바뀐다(영향 반경 큼, 원승인 Snapshot 규칙은 유지되지만 어느 Revision이 적용됐는지 판정 범위 확대).
- 데이터 이전: 기존 계약·Revision·승인 근거 행(append-only)을 고칠 수 없으므로 "옛 가맹점 계약 유지 + 새 사업자 계약" 두 체계가 공존하거나 새 Revision 재승인 필요.
- 2인 승인·감사: 승인 종류·근거 계약(§9.3) 재정의, 승인 화면 대상 변경.
- MASTER 소유권: 그대로 MASTER지만 PAYMENT Snapshot 형식(golden) 변경 가능.
- 규모 추정: 대(MASTER Domain·migration·Snapshot·PAYMENT 판정·ADMIN 승인 전부, 기존 결정 5개 이상 재결정). LIVE 전 금융 기능 재검증 필요.

### 4.3 선택지 C — 혼합: 사업자 "기본 계약(계약 체결 사실)" + 가맹점 계약 Revision 유지

- 사업자에 **기본 계약**(계약서 체결 사실·담보·기간)을 두고 "계약 완료"를 **2인 승인 사실**로 확정. 가맹점의 계약 Revision·CSA·수수료 Assignment·MID·개통은 현행(2인). 가맹점 Revision 승인 시 "사업자 기본 계약 유효"를 추가 조건으로 검사.
- 장점: 법적 계약 체결(사업자)과 서비스 권리(가맹점)를 분리해 실제 PG 실무와 가까울 수 있음 **[추측]**. 사업자 계약 해지 → 가맹점 신규 승인 차단을 자연스럽게 표현.
- 단점: 2인 승인이 한 단계 늘어남(운영 부담). 사업자 계약 해지 시 가맹점 연쇄(차단 기록·Revision 철회)를 정의해야 하는데 종료·철회 명령이 아직 없음(MASTER-LIFECYCLE-001 BACKLOG, D-MD-006). 수수료를 기본 계약에 두면 B의 수수료 충돌이 다시 생긴다 → 수수료는 가맹점 Assignment 유지가 전제.
- 기존 결정 충돌: 직접 충돌은 없으나 "가맹점 Revision 승인 조건 추가"는 D-MOPS-004·007 판정 순서 변경(MASTER 재확인 포함) → 사용자 결정 필요.
- 규모 추정: 중~대(A + 2인 승인 종류 1~2개 + Revision 승인 조건 + 해지 연쇄 설계).

### 4.4 권장안: **A(지금) → 필요 시 C로 확장**

근거:
1. 사용자가 원한 흐름(사업자 접수 → 상담 → 계약서 접수 → 계약 완료 → 상점 등록)은 A로 화면·업무 모두 얻는다.
2. PayCore의 금융 권리 판정(MID 발급 근거·Snapshot·결제 허용)은 확정 결정 위에서 이미 구현·검증됐다. B는 이 기반을 다시 여는 대가에 비해 얻는 것이 "승인 횟수 감소" 정도다.
3. 수수료는 원승인 Snapshot 규칙과 (CSA, MID, 통화) Assignment로 이미 위치가 정해져 있고 내용 모델은 SETTLEMENT-CONTRACT-001 미착수다. 지금 사업자에 효력 있는 요율을 두면 원천이 둘이 된다.
4. A의 접수 건은 권리 효과가 없어 1인 운영이 가능하고, 나중에 "계약 완료 = 2인 확정"이 필요하면 같은 표에 승인 근거를 붙여 C로 갈 수 있다(가역).
5. KYB·담보·등급은 각각 정의가 필요한 별도 도메인이라 흐름 변경과 묶지 않는다(§5 질문으로 분리).

→ 사용자 답(D-FLOW-001 ①): **A 채택**. 확정 모델은 §4A.

---

## 4A. 확정 모델(rev 0.3c — D-FLOW-001~009)

이 절은 D-FLOW-001~009와 Q-FLOW-1~37(+2b·3b·5b~5e·28b) 답만 근거로 쓴다. 답이 없는 세부는 **[미결 Q-FLOW-n]**으로 표시하고 §5.5에 질문으로 둔다(rev 0.3c 현재 없음). **[적용 메모]**는 사용자 답이 아니라 Coordinator가 기존 결정에서 끌어낸 적용이다. **[구현 관례]**는 금융·보안 의미가 없는 가역적 선택(저장소 관례로 정하고 기록 — CLAUDE.md)이다. 사용자가 권장안 대신 고른 답은 **(권장안 아님 — 사용자 선택)**으로 표시한다.

### 4A.1 한 줄 요약

- 사업자 아래에 **계약 접수 건**(MASTER 소유, 사업자 1:N)을 둔다. 운영자 1인이 상태를 옮기고, **새 가맹점 등록은 "사업자 REGISTERED + 최신 접수 건 계약 완료"일 때만** 된다.
- 접수 건·희망 서비스·담보 참고 기록·업종분류·가상자산 취급 여부·KYC 항목은 **결제 권리·정산·Snapshot에 아무 효과가 없다**. 결제 허용·MID 발급 근거는 지금처럼 가맹점 계약 Revision 승인(2인)뿐이다(D-MD-004·012·015, D-MOPS-004·007).
- 메모만 ADMIN 소유(운영 기록), 나머지는 모두 MASTER 소유(사업자 하위 기준정보).
- 사유는 저장(또는 전이) 한 번에 **공통 '사유 및 비고' 칸 하나**(D-FLOW-006, §4A.3a).

### 4A.2 소유권·TX 경계(CLAUDE.md "MASTER/PAYMENT/FINANCIAL 소유권, 로컬 TX, cross-DB 직접 SQL/공유 Entity 금지")

| 데이터 | 소유 DB | 쓰는 경로 | 같은 로컬 TX에 묶는 것 |
|---|---|---|---|
| 계약 접수 건·전이 이력·희망 서비스 | MASTER | ADMIN → MASTER 서명 채널 관리 명령(기존 사업자 명령과 같은 틀) | 사업자 행 잠금 → 업무 변경 → `master_change_history` → `admin_request` 결과 |
| 영업대행사 기준 목록 | MASTER | 같음 | 대행사 행 + 이력 + `admin_request` |
| 담보 참고 기록, 업종분류·가상자산 취급 여부, 대표자 생년월일·실소유자(KYC) | MASTER | 같음 | 사업자 행 잠금 → 새 행(INSERT 전용) + 이력 + `admin_request` |
| 사업자 메모 | ADMIN | ADMIN 내부 명령(MASTER 쓰기 없음 — 대상 사업자 존재 확인만 TX 밖 MASTER 조회) | 메모 행 + ADMIN 감사 + ADMIN 멱등 기록 |
| 가맹점 등록 관문 판정 | MASTER | 기존 `RegisterMerchant` 명령 안 | 가맹점 등록 TX 안에서 사업자 행 잠금 뒤 최신 접수 건을 읽고 판정 |

- ADMIN은 MASTER 표를 직접 읽거나 쓰지 않는다. ADMIN 메모 행은 사업자 내부 키(`biz_…`)를 **불투명 문자열**로만 갖는다(FK·cross-DB 조인 없음). 사업자는 물리 삭제가 없으므로(POL-006, runtime 권한 `business` = SELECT·INSERT·UPDATE — `db/runtime-privilege/master/V2__master_runtime_privileges.sql:13`, 하위 표는 V16 — DELETE 없음) 존재 확인 뒤 사라지는 경합은 없다.
- 외부 호출(ADMIN→MASTER)은 ADMIN DB TX 밖이다. 결과 불명(timeout 등)은 같은 멱등 키 재전송으로 과거 결과를 받는다(기존 `admin_request` 틀 — `apps/master-api/src/main/resources/db/migration/master/V1__master_core.sql:270-291`, UNIQUE(operation, idempotency_key)).
- **PAYMENT로 전파하는 것은 없다.** 접수 건·담보·리스크 표시·KYC·영업대행사 변경은 `merchant.entitlement_revision`을 올리지 않고 권리 Snapshot을 발행하지 않는다.
- **Outbox(리뷰 M-1 — [구현 관례])**: 현재 `AdminCommandExecutor.Work.record`는 변경 이력과 함께 **항상** Outbox 통합 이벤트(`INTEGRATION_MASTER_REFERENCE`)를 쓴다(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/AdminCommandExecutor.kt:354-385`). 이 기능의 명령은 그 경로를 쓰지 않고, **Outbox 없이 변경 이력만 남기는 Work 경로**(이름 후보 `recordHistory` [구현 관례])를 새로 둔다. 이유: 소비자가 없는 이벤트를 만들지 않고 "PAYMENT로 안 간다"를 행 수로 증명하기 위해. 증거 = 각 명령 전후 **`outbox_event` 행 증가 0** + `entitlement_revision`·Snapshot 불변 시험. 기존 명령(`BusinessProfileService` 등)의 `record` 동작은 바꾸지 않는다.

### 4A.3 계약 접수 건 — 상태·전이(D-FLOW-001 ①②, D-FLOW-003 ①②, D-FLOW-004 ①②, D-FLOW-005 ①②)

| 상태(code 후보 — [구현 관례]) | 화면 | 종류 |
|---|---|---|
| `CONSULTING` | 상담 중 | 열린 건 |
| `DOCUMENT_RECEIVED` | 계약서 접수 | 열린 건 |
| `COMPLETED` | 계약 완료 | 종결(되돌리지 못함). `TERMINATED`로만 이동 |
| `REJECTED` | 계약 불가 | 종결(끝) |
| `TERMINATED` | 계약 해지 | 종결(끝) |

허용 전이(이 표에 없는 전이는 모두 거절, 같은 상태로의 전이도 거절):

| 전이 | 누가 | 필수 입력 | 근거 |
|---|---|---|---|
| (생성) → 상담 중 | OPERATOR 1인 | 접수 항목(§4A.6) | D-FLOW-001 ②, D-FLOW-003 ① |
| 상담 중 → 계약서 접수 | OPERATOR 1인 | — ('사유 및 비고' 선택) | D-FLOW-001 ① |
| 계약서 접수 → 상담 중(되돌리기) | OPERATOR 1인 | **'사유 및 비고' 필수** | D-FLOW-003 ①, D-FLOW-005 ② |
| 계약서 접수 → 계약 완료 | OPERATOR 1인 | **계약서 수령 근거 참조 필수**(§4A.4), '사유 및 비고' 선택 | D-FLOW-001 ②, D-FLOW-003 ⑨ |
| 상담 중 → 계약 불가 | OPERATOR 1인 | **'사유 및 비고' 필수** | D-FLOW-005 ① |
| 계약서 접수 → 계약 불가 | OPERATOR 1인 | **'사유 및 비고' 필수** | D-FLOW-004 ② |
| 계약 완료 → 계약 해지 | OPERATOR 1인 | **'사유 및 비고' 필수**, 근거 참조 **선택**(있으면 §4A.4 형식) | D-FLOW-004 ② |

- 상담 중 → 계약 완료(계약서 접수 건너뛰기)는 거절한다(D-FLOW-005 ① "계약 완료는 계약서 접수를 거쳐야 함").
- **종결은 되돌리지 못한다.** 다시 진행하려면 새 접수 건을 만든다(D-FLOW-001 ②). 과거 접수 건은 지우지 않는다(이력 1:N).
- **새 접수 건 생성 조건**(D-FLOW-003 ①, D-FLOW-004 ①): ① 열린 건(상담 중·계약서 접수)이 없어야 한다 — 사업자당 열린 건 ≤ 1. ② **계약 완료 건이 있으면 거절 — 해지 뒤에만 새 접수**. 즉 "최신 접수 건"이 계약 불가·계약 해지이거나 접수 건이 없을 때만 생성된다. DB 이중 방어: 열린 건 또는 계약 완료 건일 때만 값이 생기는 생성 열 UNIQUE(기존 `current_order_key` 관례 — `V15__business_profile.sql:81-84`) [구현 관례].
- **사업자 DRAFT에서도 접수 건 생성·계약 완료까지 허용**(D-FLOW-003 ①, D-FLOW-004 ①). 가맹점 등록은 REGISTERED 관문이 따로 막는다(§4A.5).
- 사업자 `lifecycle = TERMINATED`면 접수 건 명령은 기존 사업자 명령처럼 `BUSINESS_NOT_ACTIVE`로 거절한다 [구현 관례 — 기존 `Business.requireActive()` `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/Business.kt:35-37` 재사용].
- 접수 건 현재 상태 열은 전이 때 갱신한다(`@Version` 낙관적 잠금). **전이 이력은 별도 INSERT 전용 표**에 (사업자, 접수 건, 순번)으로 쌓는다(§4A.14 M-2 규칙). 전이 이력 행 = 전·후 상태, 행위자, 시각, '사유 및 비고'(있을 때), 근거 참조(있을 때).
- 모든 전이는 ADMIN 감사(시도·결과 — detail `reason=PRESENT|ABSENT`, 내용 없음) + MASTER 변경 이력을 남긴다(D-FLOW-001 ②).
- **계약 해지 = 기록만**(D-FLOW-003 ②): 새 가맹점 등록만 막힌다. 기존 가맹점의 결제·계약·MID·Snapshot은 바뀌지 않는다. 해지 화면과 사업자 상세에 "연결 가맹점 n개 — 개별 중지/철회 필요" 안내(n = MASTER 조회 값). 연쇄는 MASTER-LIFECYCLE-001.
- **접수 항목 수정(D-FLOW-007 ④)**: 경로·영업대행사·계약 담당자·희망 서비스는 **열린 건(상담 중·계약서 접수)에서만** OPERATOR 1인 수정 + 변경 이력('사유 및 비고' 선택), 종결 건은 수정 불가. 별도 명령(접수 항목 수정 — 상태 전이와 다른 동작). 수정에도 §4A.6 검사(대행사 조건·담당자 ACTIVE OPERATOR·중지 대행사)를 다시 적용한다. **접수일도 열린 건에서만 수정**(D-FLOW-009 ①) — '사유 및 비고' **필수**, 이력, 미래 날짜 불가(§4A.6 KST 이중 검사) 유지.

### 4A.3a 공통 '사유 및 비고' 칸(D-FLOW-006 — 사용자 지시)

- 저장(또는 상태 전이) **한 번에 칸 하나**. 항목마다 사유 칸을 두지 않는다.
- 그 저장에 **사유가 필수인 항목이 하나라도 있으면 필수**, 없으면 선택(비고). 사유 필수 항목: 사업자 중요정보(기존 — BUSINESS-PROFILE-001 §4.5), KYC 변경(D-FLOW-005 ⑦), 리스크 표시 변경(D-FLOW-005 ③), 전이 중 되돌리기·계약 불가·계약 해지(D-FLOW-004 ②, D-FLOW-005 ②). 담보 참고 기록은 결정에 "사유 필수"가 없으므로 선택(비고)이다.
- 규칙(그 칸 하나에): 1~200자(code point — 기존 `BusinessChangeReason` `MAX_CODE_POINTS = 200`, `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessChangeReason.kt:16`), 제어 문자·줄바꿈 불가, **카드번호 패턴 거부 — 칸 전체에 항상**(D-FLOW-007 ①: 기존 사업자 중요정보 사유 포함, 새 항목 사유 D-FLOW-004 ②·D-FLOW-005 ②③⑦). 원문은 MASTER 변경 이력·전이 이력에만, ADMIN 감사는 `reason=PRESENT|ABSENT`.
- 저장은 요청(request_record_id)당 한 번 — 그 요청이 만든 모든 행이 같은 사유를 가리킨다 [구현 관례].
- **기존 사업자 편집 폼**: 이미 공통 사유 칸 하나 구조다(중요정보 변경이면 필수, 일반 변경만이면 선택 — `apps/internal-admin/frontend/src/screens/BusinessProfileFormScreen.tsx:389,394`, 현재 이름 "변경 사유"/"변경 사유(선택)"). FLOW-2에서 ① 칸 이름을 **'사유 및 비고'**(선택일 때 '사유 및 비고(선택)')로 바꾸고 ② **카드번호 패턴 거부를 추가**한다(D-FLOW-007 ① — Q-PII-6 닫힘). 검사는 ADMIN 입력 검사 + MASTER `BusinessChangeReason`(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessChangeReason.kt`) 양쪽, 고정 code `CARD_NUMBER_PATTERN`, 오류에 값 없음. 기존 시험의 기대값은 그대로 두고 거절 시험을 추가한다(약화 아님 — 기존 사유 예시 값에 13~19자리 숫자가 있으면 그 Fixture만 업무 의미를 유지한 값으로 바꾼다).
- **MASTER 카드번호 판정(리뷰 MINOR-3 — [구현 관례])**: MASTER에는 아직 카드번호 판정 코드가 없다(ADMIN `OperatorCancelInputRules.containsCardNumber` — `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/domain/OperatorCancelInputRules.kt:51`, FINANCIAL `CardNumberPattern` — `apps/financial-batch/src/main/kotlin/com/paycore/financial/acquiring/domain/CardNumberPattern.kt`만 있음). MASTER Domain에 같은 판정(PII-MASKING-POLICY §4.4)을 새로 두고(실행 단위 간 코드 공유는 하지 않음 — 기존 관례), **ADMIN·FINANCIAL·MASTER가 같은 시험 벡터**(전각·아랍-인도 숫자, 공백·하이픈 1개, 12/13/19/20자리, 앞뒤 숫자 경계)를 각자 시험에 둔다. **이미 저장된 사유는 소급 검사하지 않는다**(새 저장부터 적용 — 이력 행은 append-only라 고치지 않음).
- **저장 단위(D-FLOW-007 ② — 사용자 결정, D-ADMIN-UI-011 ② "항상 편집 폼 + 하단 저장"의 새 섹션 예외)**: 새 항목(KYC·리스크 표시·담보)은 **섹션별 따로 저장** — 각 섹션에 자기 저장 버튼 + '사유 및 비고' 칸. 기존 회사·대표자·담당자 폼은 하단 저장 그대로. 상태 전이(§4A.3)·접수 항목 수정·메모(§4A.12)도 **각각 별도 동작**이며 하단 저장에 묶이지 않는다.
- **섹션 저장 1회 = MASTER 명령 1개 = MASTER 로컬 TX 1개**(메모는 ADMIN 명령 1개 = ADMIN 로컬 TX 1개). 한 번의 저장이 두 섹션을 함께 바꾸지 않는다.
- **섹션 저장과 사업자 version([구현 관례] — 금융 의미 없음)**: 섹션 저장·전이·접수 항목 수정은 사업자 행을 `PESSIMISTIC_WRITE`로 잠그되 **사업자 version을 올리지 않는다**(§4A.5 m-4와 같음). 따라서 열린 하단 편집 폼은 섹션 저장 때문에 `BUSINESS_VERSION_CONFLICT`를 받지 않는다. 금융 의미: 없음 — 어느 저장도 권리·Snapshot·Outbox를 바꾸지 않는다(§4A.2).
- **섹션 동시 수정(리뷰 MINOR-1 — [구현 관례])**: 화면은 본 섹션의 **기대 순번**(KYC = 사업자 KYC 버전 순번, 리스크 표시 = 사업자 리스크 순번, 담보 = §4A.8의 증권 기록 순번)을 함께 보낸다. **첫 저장**(리뷰 MINOR-D — [구현 관례]): KYC·리스크 표시 섹션에 아직 행이 없으면 기대 순번 = **0**으로 보내고, 저장되면 순번 1이 된다. 행이 있는데 0을 보내거나, 행이 없는데 0이 아닌 값을 보내면 `SECTION_VERSION_CONFLICT`. 담보는 증권 기록 단위(§4A.8 — 새 증권은 기대값 없음). 잠금 뒤 최신 순번과 다르면 `SECTION_VERSION_CONFLICT`(고정 code, 이름 후보) 거절. 같은 기대 순번으로 **동시에** 저장하면 사업자 행 잠금으로 직렬화되어 **먼저 잠근 하나만 성공하고 나머지는 `SECTION_VERSION_CONFLICT`**를 받는다 — DB UNIQUE(사업자, 순번)는 이중 방어일 뿐 정상 경로에서는 위반이 나지 않는다. 그래도 위반이 나면 응답은 같은 고정 code이고 DB 오류 문구·값은 응답·로그에 내보내지 않는다. 시험: 섹션 저장 전후 `business.version` 불변, 같은 기대 순번 동시 저장(실제 MySQL) → 1 성공·나머지 `SECTION_VERSION_CONFLICT`, 첫 저장 기대값 0 → 순번 1·행이 있는데 0 → 충돌·행이 없는데 1 → 충돌, 하단 폼 저장과 섹션 저장 동시 → 둘 다 성공(서로 다른 데이터), 같은 멱등 키 재전송 = 과거 결과, 이력 쓰기 실패 주입 → 섹션 행 rollback.
- **생명주기(리뷰 MINOR-6 — [구현 관례], 기존 사업자 명령과 같음)**: KYC·리스크 표시·담보 섹션 저장과 접수 건 명령은 `Business.requireActive()`를 적용해 종료 사업자에서 `BUSINESS_NOT_ACTIVE`로 거절한다. **메모만 예외**(D-FLOW-007 ⑤ — 종료 사업자 허용, ADMIN 명령).

### 4A.4 계약서 수령 근거 참조(D-FLOW-001 ②, D-FLOW-003 ⑨, D-FLOW-004 ④)

- 형식은 D-MOPS-008 ①의 업무 근거 참조와 같다: **자유 문구 불가**, 1~128자 `[A-Za-z0-9._:#/-]`, 카드번호 패턴 거부(고정 오류 code `CARD_NUMBER_PATTERN`). ADMIN 근거: `ReacquisitionInputRules.EVIDENCE`(`apps/internal-admin/src/main/kotlin/com/paycore/admin/approval/domain/ReacquisitionInputRules.kt:10`)·`MerchantOpsInputRules.evidenceProblem`(`…/MerchantOpsInputRules.kt:85-89`).
- **MASTER 이중 검사(리뷰 m-5 — [구현 관례])**: MASTER `EvidenceRef`(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/EvidenceRef.kt`)는 형식만 보고 카드번호 패턴은 보지 않는다. 기존 승인 근거에 쓰이는 `EvidenceRef`는 바꾸지 않고, 계약서 근거용 값 객체(가칭 `ContractDocumentRef` — `EvidenceRef` 형식 + 카드번호 패턴 거부)를 새로 둔다.
- DB CHECK(리뷰 n-2 — [구현 관례]): **전이 이력 표에도 이관 표시 열**을 두고, 같은 표 CHECK로 "후 상태 = 계약 완료인 행은 근거 참조 NOT NULL, 단 이관 표시 = TRUE면 NULL 허용"을 강제한다(CHECK는 같은 표 열만 볼 수 있으므로 접수 건 표의 이관 표시와 따로 둔다). 접수 건 표에도 이관 표시 열과 "이관 = TRUE일 때만 계약 담당자 NULL" CHECK를 둔다.
- 계약서 **파일은 받지 않는다**(D-FLOW-003 ⑨).
- 표시(D-FLOW-004 ④): **사업자 조회 역할 전체(OPERATOR·APPROVER)에 원문**, 열람은 사업자 상세 열람 감사(`BUSINESS_VIEWED`)에 포함. 별도 조회·조회별 감사 없음. 목록(현황)에는 보이지 않는다.

### 4A.5 가맹점 등록 관문(D-FLOW-001 ①)

- 새 가맹점 등록 = 사업자 `REGISTERED` **그리고** 그 사업자의 **최신 접수 건이 `COMPLETED`**(이관 건 포함 — §4A.9).
- **"최신"의 뜻(리뷰 m-3 — [구현 관례])**: 사업자 안 접수 건 **생성 순번**(사업자 행 잠금 아래 부여하는 단조 seq)이 가장 큰 건. 판정은 사업자 행 잠금 뒤 **같은 TX에서 JPA 저장 Port(`ContractIntakeRepository.latestStatus` — 최신 상태 값만 반환, §4A.14 ③)로 읽어** Domain(`Merchant.register`)이 한다(jOOQ Reader로 판정하지 않음 — Reader는 화면 조회 전용).
- MASTER 거절 code 1개 추가(이름 후보 `BUSINESS_CONTRACT_NOT_COMPLETED` [구현 관례]). `MasterRejection`의 `BUSINESS_NOT_REGISTERED` 옆에 둔다(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/MasterRejection.kt:105`). reference 없음, 저장된 거절·같은 키 재생은 기존 `BUSINESS_NOT_REGISTERED`와 같다.
- 판정 순서: `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → **새 거절** → 코드 예약어(Domain `Merchant.register` — `apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/Merchant.kt:86-96`의 `requireRegistered()` 바로 다음. 참고: Application `MerchantOnboardingService.kt:98`은 사업자 잠금 전에 코드 예약어를 먼저 검사하므로 실제 응답 순서는 그 검사가 앞선다 — 이 순서는 바꾸지 않는다).
- 직렬화: 가맹점 등록은 이미 사업자 행을 잠근다(`MerchantOnboardingService.kt:99` `businesses.lock`). 접수 건 명령도 같은 사업자 행을 먼저 잠그므로 "계약 해지와 가맹점 등록이 동시에" 와도 둘 중 하나의 순서로 직렬화된다.
- **잠금은 사업자 version을 올리지 않는다(리뷰 m-4 — [구현 관례])**: 접수 건 명령은 `JpaBusinessRepository.lock`(`…/adapter/persistence/JpaBusinessRepository.kt:67` → `PessimisticRowLock.kt:17-21`, `PESSIMISTIC_WRITE` = `SELECT … FOR UPDATE`, version 증가 없음)을 쓴다. `PESSIMISTIC_FORCE_INCREMENT`(`JpaBusinessRepository.kt:117`, 사업자 정보 수정용)는 쓰지 않는다 — 그래야 접수 건 전이 때문에 열려 있는 사업자 편집 폼이 `BUSINESS_VERSION_CONFLICT`를 받지 않는다. 시험: 전이 전후 `business.version` 불변.
- ADMIN 가맹점 등록 ① 사업자 선택: 계약 완료가 아니면 "계약 완료 후 가맹점 등록 가능" + 사업자 상세 링크(D-BIZ-006 ③의 DRAFT 안내와 같은 모양).
- **기존 가맹점의 Site·계약·MID·개통 명령은 접수 건을 보지 않는다**(D-BIZ-001 "기존 무영향"과 같은 원칙 — `Merchant.kt:89` 주석).
- 영향 — Demo seed: `DemoMerchantSeedService`는 가맹점 등록 전에 접수 건을 만들고 계약서 접수 → 계약 완료까지 옮긴다(일반 1인 명령 경로, 행위자 `system:demo-seed`, Demo 근거 참조 값, 계약 담당자 = Demo 관리자 계정 — 근거 없는 Demo 예외는 만들지 않는다). **재실행 때 이미 계약 완료 접수 건(이관 포함)이 있으면 접수 단계를 건너뛴다**(리뷰 m-1 — [구현 관례], 저장된 단계 결과·멱등 키 재사용).
- 영향 — 기존 시험(리뷰 m-6): `MerchantRegistrationGateSpec`(Domain, `apps/master-api/src/test/kotlin/com/paycore/master/merchant/domain/`)·`MerchantRegistrationGateUseCaseSpec`·`MerchantRegistrationGateApiSpec`·`BusinessDemoSeedSpec`·`BusinessLegacySeedMigrationSpec`과 가맹점을 새로 등록하는 모든 시험 Fixture. **기대값을 완화하지 않고** Fixture에 계약 완료 접수 건을 명시적으로 추가한다.
- **FLOW-1~3 사이 새 사업자(리뷰 m-12 — [구현 관례])**: FLOW-1 이관은 그 migration 시점의 사업자만 대상이다. FLOW-1 뒤·FLOW-3 전에 만든 사업자는 접수 건 없이 지금처럼 가맹점을 등록할 수 있다(관문이 아직 없음). FLOW-3 배포 뒤에는 모든 사업자에 관문이 적용되므로, 그 사이 만든 사업자는 FLOW-2 명령으로 계약 완료를 거쳐야 새 가맹점을 등록할 수 있다(이미 등록한 가맹점은 무영향). 운영 데이터가 없는 Demo 단계라 추가 이관은 하지 않는다. FLOW-3 Demo 확인 때 이 경우를 1건 시연한다.

### 4A.6 접수 항목(D-FLOW-003 ③⑤, D-FLOW-004 ③)

| 항목 | 규칙 | 근거 |
|---|---|---|
| 접수일 | 운영자가 고르는 **달력 날짜(KST)**, 기본 오늘, **미래 불가**. 시계 기준(리뷰 n-4 — [구현 관례]): "오늘" = 서버 시계 UTC 순간을 `Asia/Seoul`로 바꾼 날짜. ADMIN Application이 ADMIN 시계로 먼저 검사하고, MASTER Domain이 **판정 시각(`lockedNow`)** 기준으로 다시 검사한다(이중 검사). 자정 경계에서 둘이 다르면 MASTER 판정이 최종(거절 code 고정, 화면은 다시 고르게 안내). 화면 기본값은 브라우저가 아니라 ADMIN 응답의 오늘 날짜 | D-FLOW-004 ③ |
| 접수 경로 | 고정 목록: 직접 영업·전화·홈페이지·영업대행사·기타 | D-FLOW-003 ⑤, D-FLOW-004 ③ |
| 영업대행사 | MASTER 기준 목록(§4A.7) 참조. **경로가 '영업대행사'일 때만 필수**, 그 밖에는 빈 값(값이 오면 거절). 중지 대행사는 고를 수 없음 | D-FLOW-004 ③, D-FLOW-005 ④ |
| 계약 담당자 | **필수**(**권장안 아님 — 사용자 선택**). 관리자 계정 참조(actorId), ACTIVE OPERATOR 계정만. **ACTIVE OPERATOR 검사는 ADMIN Application**이 자기 `admin_account`로 한다(MASTER는 관리자 계정을 모름 — MASTER는 actorId 형식만 검사). 같은 멱등 키 재전송은 저장된 결과를 돌려주며 **재검사하지 않는다**(리뷰 n-3 — [구현 관례]). MASTER는 actorId만 저장하고, **화면 표시명은 ADMIN Application이 자기 `admin_account`에서 채운다**(리뷰 m-11 — [구현 관례]). `login_name` 비노출(D-ADMIN1-017) | D-FLOW-003 ⑤, D-FLOW-004 ③ |
| 희망 서비스 | 체크 기록만, 효력 없음. 카드 인증 결제(선택 가능)·카드 키인 결제·가상계좌('준비 전' — 선택 불가) | D-FLOW-003 ③, D-FLOW-004 ③ |
| 계약서 수령 근거 참조 | §4A.4. 계약 완료 전이 때 필수, 해지 때 선택 | D-FLOW-001 ②, D-FLOW-004 ② |

- 희망 서비스는 가맹점 CSA·결제수단 개통(2인)과 무관하다. 화면 문구: "희망 서비스 — 참고용, 실제 서비스는 가맹점 계약 승인·개통에서 정합니다" [구현 관례 문구].
- 계약 담당자 계정이 나중에 비활성이 되어도 기록은 그대로 보여 준다(기록 시점 참조) [구현 관례].
- 수수료율 입력란 없음(D-FLOW-001 ③). 영중소 등급·상점 그룹·카드사 그룹 없음(D-FLOW-003 ⑦).

### 4A.7 영업대행사 기준 목록(D-FLOW-003 ⑤, D-FLOW-005 ④)

- MASTER 기준정보: **이름·상태(사용/중지)**만. 대행사 수수료·정산·담당자 개인정보 없음.
- 등록·이름 수정·중지 = **OPERATOR 1인 + 변경 이력**. 삭제 없음. **이름 UNIQUE**(전체).
- 중지 대행사는 새 접수 건에서 고를 수 없고, 기존 접수 건 표시는 유지.
- 대행사 행은 현재 값 갱신(이름·상태 UPDATE) + `master_change_history`(전후 값) — append-only 대상 아님(§4A.14 M-2 범위 밖, 변경 이력이 증거).
- 담당자별 조회 범위 제한은 추가하지 않는다(D-ADMIN-002 단서 유지).

### 4A.8 담보 참고 기록(D-FLOW-003 ④ — **권장안 아님, 사용자 선택**; 세부 D-FLOW-005 ⑤)

- 효력 없음. 선지급·지급 보류·한도·정산 어디에서도 읽지 않는다(코드 경로 없음 — 시험으로 고정).
- 운영자 1인. 화면 고정 문구 **"참고 기록 — 선지급·지급 판단에 쓰이지 않음"**(섹션 제목·각 행 옆에 항상).
- 사업자 하위 **증권별 여러 건**. 항목: 보증보험사, 증권번호(근거 참조 형식 1~128자 `[A-Za-z0-9._:#/-]`, 카드번호 패턴 거부), 금액(정수, 원), 통화 **KRW 고정**, 시작일·만기일(달력 날짜). 만기 알림 없음.
- **정정 = 새 행**(INSERT 전용, 현재 = 최신 순번 — §4A.14). '사유 및 비고'는 선택(비고).
- **담보 순번·기대값(리뷰 MINOR-4 — [구현 관례])**: 증권 기록 하나 = 담보 기록 key(`col_…`) 하나, 그 안에 순번 1, 2, …. 섹션 기대 순번은 **증권 기록 key + 그 기록의 순번** 단위다. **새 증권 추가**는 기대값 "없음"(key 없이 보냄 → 새 key·순번 1). 정정·종료는 그 key의 최신 순번을 기대값으로 보낸다.
- **중복 증권(리뷰 MINOR-C — [구현 관례], 제한 방향)**: 현재(종료되지 않은) 증권 기록 사이에서 (보증보험사, 증권번호)가 같으면 거절(고정 code). 검사는 **사업자 행 잠금 아래 Application 검사**이고, **DB 이중 방어는 없다**(INSERT 전용 표라 "현재 기록"을 생성 열 UNIQUE로 표현할 수 없음 — 잠금 직렬화가 유일한 보장). 새 증권 추가뿐 아니라 **정정으로 다른 현재 증권과 같아지는 경우도 거절**한다. 시험: 동시 같은 증권 추가(실제 MySQL) → 1건만, 정정으로 중복 → 거절.
- 보증보험사 문자 규칙(길이 등)은 결정 없음 → 기존 상호 규칙(`BusinessTextRule`)과 같은 문자 규칙·1~50자 [구현 관례 — 금융 의미 없음].
- **증권 종료(해지·반환 — D-FLOW-007 ⑥)**: 새 행으로 **종료일 + '사유 및 비고' 필수**, 이전 행 유지. 종료된 증권은 정정(새 행)만 가능하고 화면에서 "종료"로 구분한다. 종료일은 시작일 이상 [구현 관례].
- 효력 부여는 정산·리스크 작업에서 별도 결정.

### 4A.9 기존 사업자 이관(D-FLOW-001 ②)

- FLOW-1 migration 시점에 존재하는 **모든 사업자**(DRAFT·REGISTERED, 가맹점 유무 무관)에 이관 접수 건 1개: 상태 `COMPLETED`, 이관 표시 열 TRUE, 근거 참조 **없음**, 계약 담당자 없음(이관만 예외 — DB CHECK로 이관 건에만 허용), 행위자 고정값(예 `system:migration` [구현 관례]). 이관 건은 사업자 안 **생성 순번 1**을 받고, 전이 이력 표에 **이관 행 1개**(전 상태 없음 → 계약 완료, 이관 표시 TRUE, 근거 NULL)를 만든다(리뷰 n-2 — [구현 관례]). 이관 뒤 새 접수 건은 순번 2부터.
- 화면은 근거·담당자 칸에 "이관"으로 표시한다(R-6).
- 이관 건도 일반 계약 완료와 같이 관문을 통과시키고, 계약 해지로만 옮길 수 있다.
- migration은 기존 V15 관례대로 문장별 ALGORITHM·부분 실패 복구 SQL·정확성 시험을 둔다(`V15__business_profile.sql:9-11`). 현재 MASTER 마지막 migration은 `V17__merchant_display_name_index.sql`, runtime 권한은 `db/runtime-privilege/master/V16__business_profile_privileges.sql` — 새 번호는 구현 시점에 다시 확인한다.
- **FLOW-1 표는 결정된 열만 만든다(리뷰 m-2 — [구현 관례])**: 영업대행사 참조 열과 대행사 표 FK는 대행사 표가 생기는 FLOW-4 migration에서 추가한다(FLOW-1~3의 '영업대행사' 경로 선택은 FLOW-4 전까지 화면·서버 모두 막는다).

### 4A.10 업종분류·가상자산 취급 여부(D-FLOW-003 ⑥ — **권장안 아님, 사용자 선택**: 표시용 추가, 가맹점 등급 미사용, 업종 직접 입력, 가상자산 표시용; 세부 D-FLOW-005 ③)

- **표시용, 효력 없음**. 한도·지급 보류·선지급·모니터링·Route 어디에서도 읽지 않는다. 화면 문구 "표시용 — 한도·지급 판단에 쓰이지 않음" [구현 관례 문구].
- 운영자 1인, 변경 때 **'사유 및 비고' 필수**(§4A.3a), INSERT 전용 이력(현재 = 최신 순번).
- 업종분류 = 직접 입력 **1~50자, 빈 값 허용(미입력)**, 제어 문자·카드번호 패턴 거부. 코드 관리는 후속. 기존 업태·종목(`business_category` — 사업자등록증 항목, `V15__business_profile.sql:93-117`)과 **다른 항목**이다. 화면 이름으로 구분("업종분류(내부)" [구현 관례]).
- 가상자산 취급 여부 = **예/아니오/미확인, 기본 미확인**.
- **가맹점 등급은 만들지 않는다**(Q-FLOW-9 후속 답).

### 4A.11 KYC — 대표자 생년월일·실소유자(D-FLOW-001 ④ — **권장안 아님, 사용자 선택**; 세부 D-FLOW-002·D-FLOW-005 ⑥⑦⑧, 모두 권장안)

- 수집 항목: **대표자 생년월일**(공동·각자대표는 **대표자마다**), **실소유자** = "대표자와 같음"(기본) / 다르면 이름·생년월일·지분율, **최대 4명**.
- 지분율 = **0.01~100.00**(소수 둘째 자리), **실소유자 지분 합 ≤ 100**. **25%는 입력 거부 기준이 아니라 안내 문구**(Demo 설정값, 운영값 아님 — D-FLOW-005 ⑦).
- **대표자 본인 지분율은 받지 않는다**(D-FLOW-007 ③ — **권장안 아님, 사용자 선택**). 지분율은 대표자와 다른 실소유자에게만 받는다. D-FLOW-001 ④의 "지분율"은 이 답으로 실소유자 지분율로 한정된다.
- **성별은 수집하지 않는다**(D-FLOW-002 ②). 국적·대표자 영문 이름도 없다(D-FLOW-003 ⑪).
- 입력·변경 = **OPERATOR 1인**, 변경 때 **'사유 및 비고' 필수**(§4A.3a). 종료 사업자 거절(`requireActive` — §4A.3a MINOR-6). **등록 완료(REGISTERED)의 필수 조건이 아니다**(선택 입력 — 기존 등록 완료 규칙·시험 무영향).
- 소유: MASTER(사업자 하위 기준정보). 저장 = **INSERT 전용 버전 묶음**(사업자별 KYC 버전 순번 + 그 버전의 대표자 생년월일·실소유자 행, 현재 = 최신 순번 — §4A.14) [구현 관례]. 대표자 생년월일 행은 아래 **대표자 안정 식별자**를 가리킨다(`representative_id`가 아님).
- **대표자 안정 식별자(D-FLOW-008 ② — 리뷰 N-2)**: 지금 `business_representative.representative_id`는 AUTO_INCREMENT 행 번호이고(`V15__business_profile.sql:72`), 사업자 수정은 **내용 + 순서**로 행을 짝지어 이름 오타 수정·순서 변경만으로도 옛 행 종료 + 새 행 추가를 한다(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/adapter/persistence/JpaBusinessRepository.kt:88-90`). 그대로면 같은 사람의 생년월일이 끊긴다. 설계([구현 관례] — 업무 의미는 D-FLOW-008 ②가 정함):
  - **열·형식**: `business_representative`에 대표자 key 열(예 `representative_key`, NOT NULL). 형식 두 가지를 CHECK가 허용한다: ① 새 행 = 앱이 만드는 `rep_<ULID>` ② 이관 행 = SQL이 만드는 정해진 형식(예 `rep_m` + 0으로 채운 `representative_id` — `rep_m0000000000000000123` [구현 관례 형식]). 둘 다 내부 키라 화면에 보이지 않는다(D-ADMIN-UI-004 ③). 같은 사람의 연속된 유효기간 행은 같은 key. 현재 행 안 key 하나(생성 열 UNIQUE — 기존 `current_order_key` 관례).
  - **Domain 동일성은 그대로(리뷰 MAJOR-1 ①)**: 지금 `BusinessRepresentative`는 `data class(name, type)`(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessRepresentative.kt:4`)이고, 변경 계산 `BusinessProfileChange`도 (대표자, 순서)를 동일성 키로 쓴다(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/domain/BusinessProfileChange.kt:68-72`, `rows` `:99-111`). **key는 이 동일성·변경 비교·중요정보 판정에 넣지 않는다**. key는 별도 **대응표(요청 안 대표자 위치(순서) → key)**로 받고, 저장 adapter가 새 행에 key를 붙일 때만 쓴다. 그래서 "무엇이 바뀌었나·중요정보인가·version +1"은 지금과 똑같이 계산된다.
  - **key 규칙(리뷰 MAJOR-1 ②~④)** — 모두 사업자 행 잠금 **뒤** 그 사업자(`business_id`) 범위에서 검사, 거절은 하나의 고정 code(이름 후보 `REPRESENTATIVE_KEY_INVALID`), 오류에 값·존재 여부 없음:
    - ② 내용 + 순서가 DB 현재 행과 **같은** 대표자에 요청 key가 붙어 왔는데 그 행의 DB key와 **다르면 거절**(맞바꾸기 방지).
    - ③ key 중복 → 거절. 검사 시점(리뷰 MINOR-A): 요청 key와 **암묵 유지 key(아래 "key 없이 온 대표자" 규칙으로 정해진 DB key)를 모두 확정한 뒤 최종 key 집합**에서 중복을 본다(요청에 명시한 key가 다른 위치의 암묵 유지 key와 같은 경우도 거절). DB UNIQUE는 이중 방어 — 위반이 나도 같은 고정 code, DB 문구 비노출.
    - ④ key가 그 사업자의 **현재 대표자가 아니면** 거절 — 다른 사업자의 key, 종료된 key, 형식 오류 key를 **구별하지 않고** 같은 code(존재 여부 비노출). 사업자 간 key 이동 불가(리뷰 MINOR-5).
    - key 없이 온 대표자: 내용 + 순서가 DB 현재 행과 같으면 **그 행의 DB key 유지**(옛 클라이언트 요청 호환), 다르면 새 사람(새 key).
  - **변경 이력(리뷰 MAJOR-1 ⑤)**: `BusinessChangeEntry.Rows`의 masked map(이름 마스킹·유형·순서)과 `master_change_history.detail` 모양은 **바꾸지 않는다**(key는 이력에 넣지 않음 — 내부 키는 화면 이력에 불필요). 영향: 사업자 상세 **조회 응답·편집 요청 계약**에만 key 필드 추가 → 해당 계약 golden 새 판(추가형, 기존 필드 기대값 유지). 이력 응답 golden은 불변.
  - **migration·배포(리뷰 MINOR-5, 4차 MAJOR-A — Coordinator 결정 [구현 관례])**: MASTER jOOQ 생성(`generateJooqMain`)은 `src/main/resources/db/migration/master`의 **파일 SQL만** MySQL 컨테이너에 적용하고 그 결과가 main 소스가 된다(`apps/master-api/build.gradle.kts:70,83-87,92`) — main 코드에 있는 Java/Kotlin migration은 이 경로에서 돌지 않고 순환 의존이 생긴다. 그래서 **Java migration을 쓰지 않는다**. **SQL migration** 한 파일(V15 관례 문장별 ALGORITHM): ① key 열 NULL 추가 → ② `UPDATE`로 기존 모든 행(현재·종료)에 이관 형식 key 채움(`representative_id`마다 다름 — 종료된 행도 행마다 별도 key, 같은 사람 추정 안 함) → ③ NOT NULL·형식 CHECK(두 형식)·현재 행 UNIQUE. 부분 실패 복구 SQL(`db/recovery/master/…`)·Testcontainers 정확성 시험(채워진 key 수 = 행 수, 형식, 현재 행 UNIQUE) 유지. **배포 순서**: MASTER(열·key 선택 수신·key 없는 요청 호환) 먼저 → ADMIN(key를 보냄). MASTER만 배포된 동안 ADMIN 옛 요청은 "key 없음 + 내용 동일 행 DB key 유지" 규칙으로 KYC가 끊기지 않는다(그 사이 이름 수정은 새 key — 기존 동작과 같음). 사업자 간 key 이동 불가(규칙 ④).
  - 시험: "이름 오타 수정 → 같은 key, KYC 생년월일 유지", "순서 변경 → 같은 key, KYC 유지", "대표자 삭제 + 새 사람 추가 → 새 key, 새 사람 KYC 없음(빈 칸)", "두 대표자 key 맞바꾸기 → 거절", "요청 안 key 중복 → 거절", "명시 key가 다른 위치의 암묵 유지 key와 같음 → 거절(MINOR-A)", "key 없는 옛 요청 + 내용 동일 행 → DB key 유지", "다른 사업자·종료·형식 오류 key → 같은 고정 code", 기존 BUSINESS-PROFILE 시험 전부 기대값 불변.
- **마스킹된 KYC 수정(D-FLOW-008 ① — 리뷰 N-3)**: 수정 화면에 원문을 채우지 않는다(MASTER가 원문을 응답하지 않음). 등록된 칸은 '등록됨(가림)'으로 보이고 **빈 칸으로 두면 기존 값 유지, 입력한 칸만 변경**. 저장 요청은 칸마다 "유지 / 새 값 / 지우기"를 구분해 보낸다 [구현 관례 형식]. **지우기(D-FLOW-009 ②)**: 칸마다 '지우기' 선택 + '사유 및 비고' 필수, 새 버전에서 그 칸 없음, 과거 값은 이력(이전 버전)으로 저장만 — 파기는 보관 기간 결정 뒤(KYC-RETENTION-001). 실소유자를 "대표자와 같음"으로 되돌리면 실소유자 행이 새 버전에서 빠지는 것도 같은 규칙(사유 필수).
- **KYC 이력 표시(D-FLOW-008 ③)**: 변경 이력 탭에는 **'KYC 변경' + '사유 및 비고'만** 보인다(바뀐 칸 이름·마스킹 값도 보이지 않음 [구현 관례 — 답 문구 그대로]). 과거 버전 값은 저장만 하고 어떤 화면·응답에도 내보내지 않는다.
- **삭제된 대표자의 생년월일([적용 메모] — D-FLOW-009의 Coordinator 적용 메모, 사용자 답 아님. D-FLOW-008 ②"삭제 후 새 사람은 새 입력"·③"원문 = 현재 값만"에서 노출을 줄이는 방향으로 끌어냄, 3차 리뷰 MAJOR-3)**: KYC 현재 값·마스킹 표시·원문 조회는 **현재 대표자 key의 행만** 대상으로 한다. 대표자가 사업자 편집 폼에서 지워져 key가 종료되면, 그 key의 생년월일 행은 저장만 하고 어떤 응답(일반 상세·수정 화면·UNMASK)에도 내보내지 않는다. KYC 저장은 사업자 행 잠금 **뒤** 요청 대표자 key가 현재 대표자인지 다시 검사한다(아니면 `REPRESENTATIVE_KEY_INVALID`). 대표자 삭제와 KYC 저장은 같은 사업자 행 잠금으로 직렬화된다.
- **새 KYC 버전 범위(리뷰 MINOR-E — [구현 관례], 위 [적용 메모]의 구현)**: KYC를 저장해 새 버전을 만들 때 **현재 대표자 key의 행만** 이전 버전에서 옮기거나(빈 칸 = 유지) 새 값으로 쓴다. 종료된 key의 행은 새 버전에 옮기지 않는다(이전 버전에만 저장으로 남음). 시험: 대표자 삭제 뒤 KYC 저장 → 새 버전에 삭제된 key 행 없음.
- **마스킹(D-FLOW-005 ⑥)**: 생년월일 `****-**-**`(전부 가림), 실소유자 이름 `홍**`(기존 이름 형식 — PII-MASKING-POLICY §4.1), 지분율·"대표자와 같음" 여부는 마스킹하지 않음.
- **마스킹은 MASTER에서(리뷰 M-5)**: MASTER 일반 사업자 상세 응답에는 KYC **마스킹 값만** 담는다 — 원문은 일반 응답으로 ADMIN에 오지 않는다. 목록·현황 jOOQ Reader는 KYC 열을 SELECT하지 않는다.
- **원문 조회(UNMASK — D-FLOW-002 ①, D-FLOW-005 ⑥⑧)**: 별도 권한 `UNMASK` + '사유'(UNMASK 사유 — 감사 원문, 이 칸에도 **카드번호 패턴 거부 적용**, D-ADMIN1-019 재검토 이행) + **직전 재인증(TOTP)** + 조회마다 감사 `UNMASK_REQUESTED` 1행. **1회 = 사업자 하나의 KYC 현재 값 전체**(과거 버전 제외 — D-FLOW-008 ③). 이것이 UNMASK 틀의 첫 실제 적용 데이터다(Q-PII-3 일부 해소).
  - MASTER 쪽: 원문 조회는 **서명 채널 전용 operation 하나**(ADMIN→MASTER 내부 서명 — 기존 채널 키), 사업자 목록·상세 Reader와 분리, 최신 버전만 읽음, **현재 대표자 목록과 KYC 행을 한 스냅샷에서 읽는다**(리뷰 MINOR-F — 구현 조건: 한 SQL로 조인해 읽거나, `readOnly` + `REPEATABLE_READ` 로컬 TX 하나 안에서 두 조회 — 대표자 삭제가 사이에 끼어 삭제된 사람의 원문이 섞이지 않게), MASTER 로그·예외·지표에 원문 없음. 캐시(리뷰 n-8): 응답 `Cache-Control: no-store` 헤더 시험 + 이 경로에 캐시 구성이 없음(`@Cacheable`·Spring Cache·HTTP 캐시 설정 부재) 확인.
  - ADMIN 쪽: 현재 UNMASK 틀은 권한·사유·재인증·감사까지만 하고 데이터 공급 Port가 없어 `NoDataSource`를 돌려준다(`apps/internal-admin/src/main/kotlin/com/paycore/admin/account/application/UnmaskService.kt:18,63`). FLOW-10이 "KYC 원문 공급" Port(구현 = MASTER 조회 adapter)를 붙인다. 순서: ADMIN TX(잠금·권한·재인증·감사 1행 커밋) → TX 밖 MASTER 원문 조회 → 응답(`no-store`, ADMIN 저장·로그 없음).
  - **감사 결과 code의 뜻(리뷰 m-8 — [구현 관례])**: `UNMASK_REQUESTED` `SUCCESS` = "권한·사유·재인증을 통과해 원문 조회가 **허가됨**"이다(원문 전달 완료를 뜻하지 않음). 그 뒤 MASTER 조회가 실패하면 원문 없이 고정 오류(재시도 안내)를 돌려주고 값 없는 진단 로그만 남긴다(추가 감사 행 없음 — 허가 사실은 이미 기록됨). `DENIED`(권한 없음)·재인증 실패는 기존 그대로. PII-MASKING-POLICY §5.1에 이 뜻을 적는다.
  - **UNMASK 역할 부여(D-FLOW-005 ⑧)**: Demo seed 계정에 미리 주지 않는다. Demo에서도 SECURITY_ADMIN이 기존 2인 역할 부여 절차(ADMIN-BOOT-001 §4.5)로 준다. Demo 간편 로그인 세션의 재인증 생략(PII-MASKING-POLICY §5.3)은 DEMO 한정 그대로.
- **보관·파기 기간은 LIVE 전 결정**, 그때까지 파기 기능 없음(D-FLOW-002 ④). 리뷰 m-9 배정: LIVE Gate 점검 항목 = 후속 카드 **KYC-RETENTION-001**(가칭 — Coordinator가 tasks.json에 등록, Q-PII-5 연결). baseline PC-BIZ-POL-001 POL-003·§1-5·§7의 "생년월일·실제소유자 수집 안 함" 문구에 "D-FLOW-001 ④로 변경됨" 주석을 다는 문서 반영은 **FLOW-9b 수용 기준**에 넣는다.
- 기존 대표자·담당자 이름·전화·이메일·주소·법인등록번호 표시는 **현행 유지**(상세 원문 + 열람 감사 — D-FLOW-003 ⑩, D-BIZ-006 ②).

PII-MASKING-POLICY.md §3 표에 추가해야 할 행(FLOW-2·6·7·8·9b·10 각 배치에서 해당 행을 추가):

| 추가 행(가칭) | 등급 | 관리자 화면 | 원문 조회 | 근거 |
|---|---|---|---|---|
| #26 대표자 생년월일 | 개인정보(민감 조회 대상) | `****-**-**` | UNMASK(권한·사유·직전 TOTP·감사, 사업자 KYC 전체 1회) | D-FLOW-002 ①, D-FLOW-005 ⑥ |
| #27 실소유자 이름 | 개인정보(민감 조회 대상) | `홍**` | UNMASK | D-FLOW-002 ①③, D-FLOW-005 ⑥ |
| #28 실소유자 생년월일 | 개인정보(민감 조회 대상) | `****-**-**` | UNMASK | D-FLOW-002 ①③, D-FLOW-005 ⑥ |
| #29 실소유자 지분율 | 일반 업무 정보(사용자 결정 — 비마스킹) | 원문 | 해당 없음(UNMASK 범위 밖 — 원문 표시) | D-FLOW-005 ⑥. 대표자 본인 지분율은 받지 않음(D-FLOW-007 ③) |
| #30 실소유자 "대표자와 같음" 여부 | 일반 업무 정보 | 원문 | 해당 없음 | D-FLOW-002 ③ |
| #31 사업자 메모 내용 | 미정(자유 문구 — 개인정보 유입 가능, R-4) | 사업자 상세 메모 섹션 원문(사업자 조회 역할) | 해당 없음 | D-FLOW-003 ⑧. 감사 detail `memo=PRESENT`·종류만 |
| #32 계약서 수령 근거 참조 | 사업자 상세 조회 범위(민감 조회 대상 아님 — 사용자 결정) | 사업자 상세 원문(OPERATOR·APPROVER), 목록 없음 | 사업자 상세 열람 감사에 포함 | D-FLOW-004 ④ |
| #33 공통 '사유 및 비고'(모든 저장·전이 — 기존 중요정보 사유 포함) | #21과 같은 성격 | 이력 탭 원문(사업자 조회 역할). KYC 저장은 이력 탭에 'KYC 변경' + 사유만(D-FLOW-008 ③) | 해당 없음 | D-FLOW-006, D-FLOW-007 ①. 칸 전체 카드번호 패턴 거부 |
| #34 담보 참고 기록(보증보험사·증권번호·금액·기간) | 일반 업무 정보(증권번호는 근거 참조 형식) | 원문(사업자 조회 역할) | 해당 없음 | D-FLOW-005 ⑤ |

- §4.1 마스킹 형식 표에 생년월일 `****-**-**` 행을 추가한다(D-FLOW-005 ⑥). §4.4 카드번호 패턴 거부 "적용" 목록에 계약서 근거 참조·공통 '사유 및 비고'(칸 전체)·메모·업종분류·증권번호·**UNMASK 사유**를 추가하고, "적용하지 않는 곳"에서 마스킹 해제 사유를 뺀다(D-FLOW-005 ⑧). §5.1 "현재 상태: 틀만 있음"은 FLOW-10 완료 시 "첫 적용 데이터 = KYC 항목"으로, 결과 code 뜻(위 m-8)을 함께 적는다. §8 Q-PII-3은 "일부 해소(D-FLOW-002 ①)"로 표시한다.
- **Q-PII-6 닫힘에 따른 PII-MASKING-POLICY 변경(D-FLOW-007 ①)** — FLOW-2 수용 기준으로 그 배치에서 PII 문서를 고친다: §3 #21(사업자 중요정보 변경 사유) 이름을 "공통 '사유 및 비고'"로 넓히고 로그·오류 칸에 "카드번호 패턴 거부" 추가, §4.4 "적용" 목록에 사업자 중요정보 변경 사유 추가·"적용하지 않는 곳"에서 제거, §7 "사업자 중요정보 변경 사유: 카드번호 패턴 검사 없음(`BusinessChangeReason.kt:3-13`)" 항목을 구현 완료로 갱신, §8 Q-PII-6에 답(D-FLOW-007 ①) 표시.
- 대표자 key(`rep_…`)는 §3 #25 내부 키와 같은 등급(화면 미표시, 요청·응답 본문에만) — #25 목록에 추가.

### 4A.12 사업자 메모(D-FLOW-003 ⑧, D-FLOW-004 ③)

- **ADMIN 소유 append-only**. DB runtime 권한 INSERT·SELECT만(UPDATE·DELETE 없음). 고치거나 지우는 기능 없음, 화면 안내 "남긴 메모는 고치거나 지울 수 없습니다 · 개인정보·카드번호를 적지 마세요".
- 종류 = 고정 목록 **일반·상담·계약·리스크**(D-FLOW-004 ③).
- 내용 1~1,000자, 제어 문자 거부, **카드번호 패턴 거부**(PII-MASKING-POLICY §4.4 판정, 고정 code `CARD_NUMBER_PATTERN`, 오류에 값 없음). 메모는 '사유 및 비고'가 아니라 별도 기록이다(공통 사유 칸 규칙 대상 아님).
- 작성 = OPERATOR. 열람 = 사업자 조회 역할(OPERATOR·APPROVER — D-BIZ-006 ①), 열람은 사업자 상세 열람 감사에 포함. 작성 감사는 시도·결과, detail `memo=PRESENT`·종류만.
- **감사 fail-closed**: 감사 쓰기가 실패하면 메모도 저장하지 않는다(같은 ADMIN 로컬 TX — PII-MASKING-POLICY §6 ⑤ 기존 규칙).
- **종료(TERMINATED) 사업자에도 메모 허용**(D-FLOW-007 ⑤). 메모는 효력 없는 운영 기록이라 사업자 생명주기와 무관하게 남길 수 있다. 시험: ACTIVE·TERMINATED 사업자 모두 작성 성공(TERMINATED Fixture는 Named Fixture로 명시).
- 대상은 지금은 사업자만. 가맹점 메모는 후속.

### 4A.13 하지 않는 것(사용자 답)

- 사업자에 효력 있는 수수료·요율 없음(D-FLOW-001 ③). 가맹점 등급 없음(D-FLOW-003 ⑥). 영중소 등급·상점/카드사 그룹 없음(⑦). 국적·홈페이지·영문 이름 없음(⑪). 성별 없음(D-FLOW-002 ②). 계약서 파일 없음(⑨). 해지 연쇄 없음(②). 기존 개인정보 표시 변경 없음(⑩). 계약 완료 2인 승인 없음(D-FLOW-001 ②). 담보 만기 알림 없음(D-FLOW-005 ⑤). 항목별 사유 칸 없음(D-FLOW-006). 대표자 본인 지분율 없음(D-FLOW-007 ③). KYC 과거 값 표시·원문 조회 없음(D-FLOW-008 ③). 수정 화면 원문 채움 없음(D-FLOW-008 ①).

### 4A.14 8대 원칙 — 배치별 완료 증거(TECHNOLOGY-AND-PRINCIPLES)

**append-only 저장 규칙(리뷰 M-2 — 하나로 고정, [구현 관례])**: 전이 이력·담보 참고 기록·리스크 표시·KYC 버전 묶음은 **INSERT 전용 표**다. 현재 값 = (사업자[·증권 기록 키]) 안 **최신 순번** 행. 순번은 사업자 행 잠금 아래에서 부여하고 UNIQUE(사업자, [키,] 순번)로 이중 방어. runtime DB 권한 **SELECT, INSERT만**(UPDATE·DELETE 없음). 기존 V15 대표자·업태 표의 "유효기간 행 + `effective_to` UPDATE"(`db/runtime-privilege/master/V16__business_profile_privileges.sql:11-15`, "종료 열 외 UPDATE 0" 시험 관례) 방식은 새 표에 쓰지 않는다. 접수 건 행(현재 상태)·영업대행사 행(이름·상태)은 append-only가 아니며 `@Version` 갱신 + `master_change_history`가 증거다.

| 원칙 | 이 기능에서의 의미 | 완료 증거(시험) |
|---|---|---|
| Financial Consistency | 접수 건·담보·리스크·KYC는 금융 값이 아니다. 금융 효과 0을 증명 | 명령 전후 `merchant.entitlement_revision`·권리 Snapshot 불변, **`outbox_event` 행 증가 0**(M-1 — 이력 전용 Work 경로), 담보·리스크 값을 읽는 권리·정산 코드 경로 없음 — ArchUnit 규칙(리뷰 n-7, 3차 MAJOR-2로 수정): ① `com.paycore.master.merchant..` 안 권리 관련 타입(`Entitlement*` 이름 클래스, 권리 Snapshot 발행기)은 새 타입(`ContractIntake*`·`SalesAgency*`·`Collateral*`·`RiskDisplay*`·`Kyc*` [구현 관례 이름])에 의존하지 않는다 ② 새 타입을 **소유·변경하는** Application 서비스(접수 건·대행사·담보·리스크·KYC 명령 서비스)는 `Work.publishEntitlement`·`OutboxStore`·`Work.record`를 호출하지 않는다(이력은 `recordHistory`만) ③ **가맹점 등록 경로 예외**(4차 MINOR-B로 이름·반환 값 수정): `MerchantOnboardingService`는 원래 `publishEntitlement`·`work.record`를 부르는 기존 서비스다(`apps/master-api/src/main/kotlin/com/paycore/master/merchant/application/MerchantOnboardingService.kt`). 관문은 이 서비스가 **required 저장 Port의 판정용 조회 메서드 하나**만 부르게 한다 — ARCHITECTURE-CONVENTIONS §14에서 `…Query`는 provided Port 이름이므로(`docs/repository/ARCHITECTURE-CONVENTIONS.md:323`) required 쪽인 `ContractIntakeRepository`(application.required)의 메서드(이름 후보 `latestStatus(businessId)`)로 둔다. 이 메서드는 **최신 접수 건의 상태 값**(없으면 없음)만 돌려주고, 계약 완료인지 판정은 Domain(`Merchant.register`가 그 상태 값을 입력으로 받아 판정)이 한다. 규칙: "가맹점 등록 경로(`MerchantOnboardingService`·`Merchant`)가 참조하는 새 타입은 `ContractIntakeRepository.latestStatus`와 상태 enum뿐" — `ContractIntake` Aggregate 변경 메서드·`Collateral*`·`RiskDisplay*`·`Kyc*` 참조 금지 유지. payment-api·financial-batch는 별도 실행 단위라 MASTER 코드에 의존하지 않음(기존 경계) |
| Transaction Safety | 업무 변경·이력·`admin_request` 결과가 MASTER 한 로컬 TX. 메모·감사·멱등 기록이 ADMIN 한 로컬 TX | 이력·결과 쓰기 실패 주입 시 업무 행 rollback 시험 |
| Idempotency | 같은 멱등 키 + 같은 내용 = 과거 결과, 다른 내용 = 충돌 | MASTER `admin_request`(operation, idempotency_key) 재전송·내용 충돌 시험, ADMIN 결과 불명 재전송 시험 |
| Concurrency Control | 동시 전이·동시 생성·해지 vs 가맹점 등록 | 실제 MySQL 경쟁 시험: 같은 사업자 접수 건 동시 생성 → 1건만, 같은 건 동시 전이 → 하나만 성공·나머지 version/상태 거절, 해지와 가맹점 등록 동시 → 직렬화 결과 중 하나, 같은 기대 순번 섹션 동시 저장 → 1 성공·나머지 `SECTION_VERSION_CONFLICT`(DB 오류 문구 비노출), KYC 저장 vs 대표자 삭제 동시 → 직렬화(삭제가 먼저면 KYC 저장 거절). 전이 전후 `business.version` 불변(m-4) |
| Recoverability | ADMIN→MASTER 응답 유실 | 응답 유실 후 같은 키 재전송으로 결과 회수, PENDING 커밋 없음 |
| Reconciliation | 금융 대사 대상 아님. 대신 이관 정확성 | 이관 migration 뒤 "사업자 수 = 이관 접수 건 수", 부분 실패 복구 SQL 정확성 시험(V15 관례) |
| Auditability | 모든 전이·기록·UNMASK 감사, append-only | 감사 1행/시도, 감사 실패 시 행위 실패(fail-closed), INSERT 전용 표에 UPDATE·DELETE 시도 → 권한 오류(1142) 시험 |
| Observability | 로그·지표에 개인정보·근거 참조·메모·사유 원문 없음 | 로그 캡처 시험(ADMIN·MASTER — KYC 원문·메모 내용·사유·근거 참조 부재), 지표 label에 사업자 키 무제한 사용 없음 |

### 4A.15 구현 구조(CLAUDE.md 필수 기술, ARCHITECTURE-CONVENTIONS §14)

- 패키지: 기존 `com.paycore.master.merchant`(MASTER)·`com.paycore.admin.merchantops`(ADMIN 사업자 화면) 모듈 안. 새 Modulith 모듈·`@NamedInterface` 위치 변경 없음.
- 이름(§14): 순수 Domain Aggregate `ContractIntake`(가칭) → 저장 Port `ContractIntakeRepository`(application.required) → `JpaContractIntakeRepository` + `JpaContractIntakeEntity` + Mapper(adapter.persistence). 전이 이력·담보·리스크 표시·KYC 버전은 Aggregate가 아닌 결정 로그 → `…Store`(§14 기준 ①, 예 `ContractIntakeTransitionStore`). 영업대행사 `SalesAgency` → `SalesAgencyRepository`. 목록·현황 조회는 jOOQ `…Reader` → `Jooq…Reader`(adapter.query — 예 기존 `JooqBusinessDirectoryReader`). 메모는 ADMIN `BusinessMemoStore` [구현 관례 이름].
- JPA 선택을 쉽게(이번 코드 기준):
  - **역할**: JPA Entity는 DB 행 모양만, 규칙은 순수 Domain. 예: "계약서 접수 → 계약 완료에는 근거 참조 필수", "완료 건이 있으면 새 접수 거절"은 `ContractIntake`/사업자 단위 Domain 판정이 검사하고 `JpaContractIntakeEntity`는 열만 가진다. 이유: Domain 시험을 DB 없이 돌리고 Domain이 JPA·Spring에 의존하지 않게.
  - **잠금**: 사업자 행 `PESSIMISTIC_WRITE`(`lockRow` — version 증가 없음, §4A.5 m-4). 이유: "열린 건 ≤1", "최신 건 판정", "해지 vs 가맹점 등록"은 여러 행에 걸친 규칙이라 사업자 단위로 직렬화. 주의: 잠금 순서는 항상 사업자 → 접수 건(교착 방지), 짧은 로컬 TX 안에서만.
  - **version**: 접수 건 행 `@Version` — 화면이 본 version과 다르면 덮어쓰기 거절.
  - **INSERT 전용**: 이력·담보·리스크·KYC Entity는 persist만(기존 관례 — `PessimisticRowLock.kt` 주석 "새 행은 persist만, Spring Data save는 merge가 될 수 있다"). 주의: dirty checking이 UPDATE를 보내지 않도록 Entity를 `@Immutable`로 두고, DB 권한(UPDATE 없음)으로 이중 방어.
  - **조회**: 현황 목록(필터·정렬·keyset 페이지)은 jOOQ Reader(CQRS). 관문 판정은 Reader가 아니라 잠금 뒤 JPA(§4A.5 m-3).
- 버전: Spring Boot 4.1.1 BOM, Kotlin 2.3.21(`gradle/libs.versions.toml:5-7`) 그대로 — 새 의존성 없음. Kafka/Redis 없음: 멱등은 `admin_request` UNIQUE, 동시성은 MySQL 행 잠금·UNIQUE로 보장한다(Kafka/Redis가 이 보장을 대신하지 않는다).
- Test Fixture Policy: 접수 건은 Domain Factory → Named Fixture(예 `aConsultingIntake()`, `aCompletedIntake(evidenceRef)`, `aMigratedCompletedIntake()`) 우선. 상태·근거를 독립 random으로 만들지 않는다. Fixture는 `src/test`에만.

---

## 5. 질문 목록(Q-FLOW-n)

### 5.1 답한 질문(Q-FLOW-1~14, 2b·3b·5b~5e)

형식: 질문 / 권장안 / 대안 / 영향 / 막히는 작업. 각 질문 아래 **답** 줄이 사용자 결정이다(DECISIONS D-FLOW-001·002·003). 아래 권장·대안 문단은 질문 당시 기록이며 답과 다르면 답이 우선한다.

#### Q-FLOW-1 설계 선택지(A/B/C)

**답(2026-11-25, D-FLOW-001)**: A — 현행 계약 모델 유지 + 사업자 '계약 접수' 추가(권장).

- 권장: **A**(현행 계약 모델 유지 + 사업자 계약 진행 기록 추가). PC-BIZ-POL-001 §7의 "이용신청·영업관리" 후속을 이 작업으로 연다.
- 대안: B(계약 단위 사업자 이동 — D-MD-004·010·013 재결정), C(사업자 기본 계약 2인 확정 + 가맹점 Revision 유지).
- 영향: B는 MASTER·PAYMENT 권리 계산 전면 재검증. A·C는 MASTER 하위 기록 추가.
- 막히는 작업: 이 문서의 모든 구현 배치(§6).

#### Q-FLOW-2 진행 상태 정의·전이·가맹점 등록 관문

**답(2026-11-25, D-FLOW-001)**: Q-FLOW-1·3 선택지 설명에 담긴 범위로 확정 — 상태 5종·전이, 새 가맹점 등록 = REGISTERED + 계약 완료, 종결 비가역·재진행은 새 접수 건, 기존 사업자 '계약 완료(이관)'. 나머지 세부는 Q-FLOW-2b.

- 권장: 상태 5종 `상담 중 → 계약서 접수 → 계약 완료 | 계약 불가`, `계약 완료 → 계약 해지`. 계약 불가·해지는 종결(비가역), 재진행은 새 접수 건(사업자당 열린 접수 건 최대 1개, 이력 1:N). 뒤로 가기(계약서 접수 → 상담 중)는 허용(사유 필수). **새 가맹점 등록 = REGISTERED + 최신 접수 건 계약 완료**. 기존 사업자(가맹점이 이미 있는 사업자 포함)는 이관 접수 건 "계약 완료(이관)"로 넣어 기존 흐름이 끊기지 않게 한다. 사업자 DRAFT 상태에서도 접수 건 생성 허용(pg-lab처럼 접수가 먼저).
- 대안 ①: 관문 없이 진행 상태는 표시만. ②: 기존 사업자는 "미기록"으로 두고 관문을 새 사업자에만 적용. ③: 상태를 사업자 열 하나로(이력 없이 덮어쓰기 — 감사성 낮음, 비권장).
- 영향: MASTER 가맹점 등록 판정 순서에 거절 1개 추가(D-BIZ-001 `BUSINESS_NOT_REGISTERED` 옆), Demo seed가 접수 건을 만들어야 함, 이관 migration.
- 막히는 작업: FLOW-1(접수 건 Domain·DB), FLOW-3(관문).

#### Q-FLOW-3 진행 상태를 누가 바꾸는가(2인 승인 여부)와 "계약 해지"의 효과

**답(2026-11-25, D-FLOW-001)**: 운영자 1인 + 근거(권장) — 계약 완료는 계약서 수령 근거 참조 필수, 모든 전이 감사. 계약 해지의 기존 가맹점 연쇄 효과는 Q-FLOW-3b.

- 권장: 상태 전이는 **OPERATOR 1인**(권리 효과 없음 — D-MOPS-004 "등록·초안 1인", D-MOPS-007 ③ "UNDER_REVIEW로 남는 등록 1인"과 같은 방향), 계약 완료 전이는 계약서 수령 근거 참조(Q-FLOW-12) 필수. **계약 해지는 이 단계에서 기록만**(새 가맹점 등록 차단), 기존 가맹점의 결제 차단·계약 철회는 하지 않고 화면에 "연결 가맹점 n개 — 개별 중지/철회 필요" 안내. 연쇄 효과는 MASTER-LIFECYCLE-001에서 별도 결정.
- 대안 ①: 계약 완료를 2인(C로 이동). ②: 계약 해지 시 모든 가맹점 신규 결제 차단 기록 자동 생성(안전 방향 1인 즉시 — D-MOPS-004, D-MD-006 차단 기록). ③: 해지도 2인(D-ADMIN-003 "가맹점 종료·계약 철회" 준용).
- 영향: ②는 결제 차단이라는 금융·운영 효과 → Snapshot 발행·`entitlement_revision` 증가·Demo 시험 필요.
- 막히는 작업: FLOW-2(상태 명령), 해지 연쇄.

#### Q-FLOW-4 수수료율을 어디에 두는가

**답(2026-11-25, D-FLOW-001)**: 정산 계약 작업에서 정함(권장) — 사업자에 효력 있는 요율 없음.

- 권장: **사업자에는 효력 있는 요율을 두지 않는다.** 효력 있는 요율은 지금처럼 정책 버전 + (CSA, MID, 통화) Assignment(2인, D-MOPS-004)이고 원승인 Snapshot이 계산 근거(CLAUDE.md, D-MS2-003). 사업자 상세에는 SETTLEMENT-CONTRACT-001 이후 **연결 가맹점에 배정된 수수료 정책을 읽기 전용으로 모아 보여 준다**. 영업 협의 단계의 요율은 메모(Q-FLOW-11)로만.
- 대안 ①: 접수 건에 "협의 요율(효력 없음)"을 구조화 값으로 두고 가맹점 수수료 배정 요청의 기본값으로 채움(배정은 여전히 2인). ②: 사업자 계약 조건 요율이 효력 원천(B 방식 — D-MD-018·D-MS2-001과 충돌).
- 영향: ①은 화면 편의가 크지만 "협의 요율 ≠ 적용 요율" 혼동·두 값 불일치 위험. 요율 값은 Demo 값이며 운영값 아님(CLAUDE.md).
- 막히는 작업: 사업자 상세 "계약 조건" 섹션, SETTLEMENT-CONTRACT-001과의 순서.

#### Q-FLOW-5 대표자 생년월일·성별·지분율·실소유자(KYC/KYB) 수집

**답(2026-11-25, D-FLOW-001 ④)**: **이번에 함께 수집**(**권장안 아님 — 사용자 선택**). PC-BIZ-POL-001 POL-003·§1-5 변경. 표시·원문 조회·보관·항목별 목적은 Q-FLOW-5b~5e 답 전까지 KYC 입력란을 구현하지 않는다. → 5b~5e 답 완료(D-FLOW-002). 남은 입력·표시 세부는 Q-FLOW-17~19.

- 권장: **이번 흐름 변경에서는 수집하지 않는다**(PC-BIZ-POL-001 POL-003·§1-5 [확정] 유지). AML/KYB 작업(가칭 KYB-001)을 별도로 열어 법적 필요 항목·보관 기간(Q-PII-5)·원문 조회 절차를 먼저 정한 뒤 수집한다. 그때 생년월일 등 새 항목은 **마스킹 표시 + UNMASK 원문 조회(별도 권한·사유·직전 TOTP·조회 감사 — PII-MASKING-POLICY §5.1)**를 권장(Q-PII-3 "UNMASK 첫 적용 데이터" 후보).
- 대안 ①: 지금 pg-lab 항목 그대로 수집(사업자 상세 원문 규칙 D-BIZ-006 ② 적용). ②: 실소유자 "대표자와 같음" 여부 체크만 먼저.
- 영향: ①은 POL-003 변경(사용자 결정)·새 개인정보 보관 근거 필요. 성별은 업무 목적이 불분명 **[추측: KYC 본인확인 보조]** → 목적 없으면 수집하지 않는 것이 최소수집 원칙에 맞음.
- 막히는 작업: 사업자 상세 "대표자·실소유자" 섹션 확장, KYB-001 착수.

#### Q-FLOW-2b 진행 세부(선택지 설명에 없던 부분)

**답(2026-11-25, D-FLOW-003 ①)**: 뒤로 가기(계약서 접수 → 상담 중) 허용·사유 필수, 사업자 DRAFT에서도 접수 건 생성, 사업자당 열린 접수 건 ≤ 1(권장).

- 권장: 뒤로 가기(계약서 접수 → 상담 중) 허용·사유 필수, 사업자 DRAFT에서도 접수 건 생성 허용(접수가 먼저), 사업자당 열린 접수 건 최대 1개.
- 대안: 앞으로만 진행 / REGISTERED 사업자만 접수.
- 막히는 작업: FLOW-1·2.

#### Q-FLOW-3b 계약 해지의 기존 가맹점 효과

**답(2026-11-25, D-FLOW-003 ②)**: 이번엔 기록만(권장) — 새 가맹점 등록 차단 + '연결 가맹점 n개 — 개별 중지/철회 필요' 안내, 기존 가맹점 결제 불변, 연쇄는 MASTER-LIFECYCLE-001.

- 권장: 이번 단계는 **기록만**(새 가맹점 등록 차단 + 화면에 "연결 가맹점 n개 — 개별 중지/철회 필요" 안내). 결제 차단 연쇄는 MASTER-LIFECYCLE-001에서 별도 결정.
- 대안: 해지 시 연결 가맹점 신규 결제 차단 기록 자동 생성(Snapshot 발행·금융 운영 효과) / 해지는 2인 승인.
- 막히는 작업: FLOW-2 해지 명령.

#### Q-FLOW-5b~5e KYC 항목 세부(Q-FLOW-5 '이번에 수집' 후속)

- 5b 표시 방식 — 권장: 마스킹 표시 + 원문 조회(UNMASK: 별도 권한·사유·직전 TOTP·조회 감사, PII-MASKING-POLICY §5.1, Q-PII-3 첫 적용 데이터). 대안: 다른 사업자 개인정보와 같이 상세 원문(D-BIZ-006 ②).
- 5c 성별 — 권장: 업무 목적이 정해지지 않아 수집하지 않음(최소수집). 대안: 수집.
- 5d 실소유자 범위 — 권장: '대표자와 같음' 여부 + 다르면 이름·생년월일·지분율(최대 몇 명은 질문). 대안: 지분율만.
- 5e 보관·파기 — 권장: Demo에서는 수집 허용, 보관 기간(Q-PII-5)은 LIVE 전 결정·그 전까지 파기 기능 없음. 대안: 보관 기간을 먼저 정한 뒤 수집.
- 막히는 작업: 사업자 상세 '대표자·실소유자' 섹션.

**답(2026-11-25, D-FLOW-002)**: 5b 마스킹 + 원문 조회, 5c 성별 수집 안 함, 5d '대표자와 같음' + 다르면 이름·생년월일·지분율(최대 4명, 25% 이상 — Demo 값), 5e Demo는 지금 수집·보관 기간은 LIVE 전 결정(모두 권장안).

#### Q-FLOW-6 신청 서비스(인증/키인/가상계좌)

**답(2026-11-25, D-FLOW-003 ③)**: 희망 서비스 체크만(권장) — 효력 없음, 카드 외 '준비 전'.

- 권장: 사업자 접수 건에는 **"희망 서비스" 체크 기록만**(효력 없음, 카드 외 항목은 "준비 전" 표시). 실제 서비스는 지금처럼 가맹점 CSA·결제수단 개통(2인). 키인·가상계좌·체크카드 요율 구분은 새 결제수단 채택 결정 전까지 만들지 않는다.
- 대안: 희망 서비스도 두지 않음 / 새 결제수단 채택 결정을 먼저 받음.
- 영향: 새 결제수단은 승인·매입·정산 전 영역 설계 필요(이 문서 범위 밖).
- 막히는 작업: 접수 건 항목 확정.

#### Q-FLOW-7 담보(보증보험)

**답(2026-11-25, D-FLOW-003 ④)**: **참고 기록만 지금**(**권장안 아님 — 사용자 선택**). 효력 없음·운영자 1인·이력, 화면에 '참고 기록 — 선지급·지급 판단에 쓰이지 않음' 표시, 효력 부여는 정산·리스크 작업에서 별도 결정. 항목·통화 세부는 Q-FLOW-15.

- 권장: 이번에는 **기록하지 않는다**. 담보가 선지급 한도·지급 보류·손실 책임과 연결되는지(baseline 금융통제 §4) SETTLEMENT/리스크 작업에서 먼저 결정하고, 기록한다면 MASTER 별도 기록(금액·통화·증권 기간·보험사·증권번호, 변경 2인, append-only 이력).
- 대안: 지금 참고 기록(금액·기간, 효력 없음, 1인)만 추가.
- 영향: 참고 기록이 "담보 확보"로 오해되면 선지급 판단 오류 위험. 만기 알림 등 운영 기능이 따라 필요.
- 막히는 작업: 사업자 "담보" 섹션.

#### Q-FLOW-8 접수 경로·영업대행사·계약 담당자

**답(2026-11-25, D-FLOW-003 ⑤)**: 접수 경로 고정 목록 + 계약 담당자 = 관리자 계정 + 영업대행사 MASTER 기준 목록(이름·상태, 수수료·정산 없음)(권장). 목록 값·관리 세부는 Q-FLOW-20·22.

- 권장: 접수 경로 = 고정 목록(직접 영업·전화·홈페이지·영업대행사·기타). 계약 담당자 = 관리자 계정 참조(actorId, 화면은 표시명 — `login_name` 비노출 D-ADMIN1-017). 영업대행사 = **MASTER 기준 목록(이름·상태만)**, 대행사 수수료·정산은 만들지 않음. 담당자별 조회 범위 제한은 추가하지 않음(D-ADMIN-002 단서 유지).
- 대안 ①: 영업대행사를 자유 문구로. ②: 대행사 수수료까지 설계(정산 대상 추가 — SETTLEMENT 범위).
- 영향: 대행사 목록 관리 화면 1개 추가. 대행사 담당자 개인정보를 받으면 PII 표 행 추가 필요.
- 막히는 작업: 계약 진행현황 목록 필터(접수 경로·영업대행사).

#### Q-FLOW-9 가맹점 등급·업종분류·가상자산 취급(리스크 의미)

**답(2026-11-25, D-FLOW-003 ⑥)**: **표시용으로 지금 추가**(**권장안 아님 — 사용자 선택**). 후속 답: 가맹점 등급은 **사용하지 않음**, 업종분류는 **직접 입력**(코드 관리는 후속), 가상자산 취급 여부는 표시용 — 효력 없음·운영자 1인·사유·이력. 입력 규칙 세부는 Q-FLOW-16.

- 권장: **정의가 정해질 때까지 만들지 않는다.** 등급이 한도·지급 보류·선지급·모니터링에 쓰이면 리스크 정책(버전 관리 — CLAUDE.md "정책/한도는 버전 관리")의 입력이므로 KYB/리스크 작업에서 정의(등급 체계·산정 근거·변경 권한·2인 여부)부터 질문한다. 업종분류는 카드사 업종 코드와 대응이 필요한지 함께 결정.
- 대안: 지금 표시용 속성으로 추가(효력 없음, 1인).
- 영향: 표시용 속성이 나중에 효력을 가지면 과거 값의 신뢰성(누가 근거 없이 바꿨는지) 문제 → 처음부터 이력·사유가 필요.
- 막히는 작업: 사업자 정보 섹션의 해당 행.

#### Q-FLOW-10 영중소 등급·상점 그룹·카드사 그룹(가맹점 목록 항목)

**답(2026-11-25, D-FLOW-003 ⑦)**: 나중에(권장) — 이번에 만들지 않음.

- 권장: 영중소 등급은 **수수료 정책 입력**으로 SETTLEMENT-CONTRACT-001 질문에 넘긴다(등급 갱신 주기·원승인 Snapshot에 등급 포함 여부). 상점 그룹·카드사 그룹은 의미(정산 묶음? Route 묶음?)가 확인될 때까지 만들지 않는다 **[추측 의미 — 화면만으로 불명]**.
- 대안: 영중소 등급을 표시용으로 먼저 추가.
- 영향: 등급이 요율을 바꾸면 소급 금지·원승인 Snapshot 규칙 적용 필요.
- 막히는 작업: 가맹점 목록 필터 추가.

#### Q-FLOW-11 메모(append-only)

**답(2026-11-25, D-FLOW-003 ⑧)**: 추가만 가능한 메모(권장) — ADMIN append-only, 종류 고정, 1~1,000자, 카드번호 패턴 거부, 작성 OPERATOR. 종류 값 확인은 Q-FLOW-20.

- 권장: 사업자(와 이후 가맹점) 메모를 **ADMIN 소유 append-only 기록**으로 둔다(운영 메모는 MASTER 기준정보·권리 입력이 아니고 PAYMENT로 전파되지 않음). 종류(일반·상담·계약·리스크 — 고정 목록), 내용 1~1,000자, 제어 문자 거부, **카드번호 패턴 거부**(PII-MASKING-POLICY §4.4와 같은 판정·고정 오류 code), 화면 안내 "개인정보·카드번호를 적지 마세요". DB 권한 INSERT·SELECT만(UPDATE·DELETE 없음). 작성 = OPERATOR, 열람 = 사업자 조회 역할, 감사는 작성 시도·결과(내용 없이 `memo=PRESENT`·종류), 열람은 사업자 상세 열람 감사에 포함.
- 대안 ①: MASTER 소유(사업자 하위 이력과 같은 곳). ②: 정정은 "취소 표시 + 새 메모"(원문은 남김).
- 영향: 자유 문구라 개인정보가 들어갈 수 있음 → 보관·파기(Q-PII-5) 대상. PII 표에 행 추가 필요.
- 막히는 작업: 사업자 상세 "메모" 섹션.

#### Q-FLOW-12 계약서 파일 보관

**답(2026-11-25, D-FLOW-003 ⑨)**: 근거 번호만, 파일은 별도 작업(권장).

- 권장: **파일 업로드는 이번에 만들지 않는다.** 계약 완료 전이에는 계약서 **수령 근거 참조**(D-MOPS-008 ①의 업무 근거 참조와 같은 형식 — 외부 문서 관리 번호, 자유 문구 불가, 카드번호 패턴 거부)를 필수로 받는다. 파일 저장은 저장소(객체 저장소·암호화·악성 검사·MIME/크기·서명 URL 금지 여부)·보관 기간(TBD-09, Q-PII-5)·다운로드 권한·감사를 정하는 별도 작업으로.
- 대안: 지금 파일 업로드(PDF만, 크기 상한, DB 밖 저장, 다운로드 감사).
- 영향: 파일 저장소는 PayCore에 아직 없는 인프라 — 보안 검토 범위가 크다. 근거 참조만으로는 원본 확인을 외부 시스템에 의존.
- 막히는 작업: 계약 진행 "계약서" 칸.

#### Q-FLOW-13 개인정보 표시 방식(pg-lab 마스킹 + 원문 조회 vs 현행 원문)

**답(2026-11-25, D-FLOW-003 ⑩)**: 기존 대표자·담당자 개인정보 표시 현행 유지(권장). 마스킹 + 원문 조회는 새 KYC 항목만(D-FLOW-002 ①).

- 권장: **현행 유지**(D-BIZ-006 ② — 사업자 상세는 조회 역할에 원문, 열람마다 감사·속도 제한). 마스킹 + 원문 조회 방식은 새 KYB 항목(Q-FLOW-5)에만 적용.
- 대안: 사업자 상세의 대표자·담당자 이름·전화·이메일도 마스킹 기본 + 항목별 원문 조회(조회마다 감사) — D-BIZ-006 ②·D-ADMIN-UI-011 ②(항상 편집 폼) 변경 필요(편집 폼이 원문을 채워야 하므로 "수정할 때만 입력" pg-lab 방식으로 폼 동작도 변경).
- 영향: 대안은 화면·API·시험 변경이 크고 운영 편의 감소, 대신 열람 노출이 줄어듦.
- 막히는 작업: 없음(현행 유지 시).

#### Q-FLOW-14 사업자 정보 추가 항목(국적·홈페이지·영문 대표자명)

**답(2026-11-25, D-FLOW-003 ⑪)**: 국적·홈페이지·영문 이름 추가 안 함(권장).

- 권장: 국적은 지금 국내만(PC-BIZ-POL-001 §1-6 [확정]) → 화면 고정값, 추가 안 함. 홈페이지는 Site 채널 식별값과 중복되므로 사업자에 두지 않고 가맹점 Site로 보여 준다. 대표자 영문 이름은 해외 연동 필요가 생길 때(MERCHANT-IA-001 §5).
- 대안: pg-lab처럼 사업자에 홈페이지·영문 대표자명 추가.
- 영향: 낮음. 홈페이지를 사업자에 두면 Site 심사 대상과 두 값이 생김.
- 막히는 작업: 없음.

### 5.2 rev 0.3 질문(Q-FLOW-15~27 — 모두 답함)

rev 0.3에서 새로 묻고 rev 0.3a에서 답을 받은 질문이다. 질문 당시의 권장·대안을 줄여 적고, **답** 줄이 사용자 결정이다(D-FLOW-004·005·006).

#### Q-FLOW-15 담보 참고 기록의 항목·통화·건수·위치
- 권장: 사업자 하위 증권별 여러 건(정정은 새 행), 보증보험사·증권번호(근거 참조 형식, 카드번호 패턴 거부)·금액(정수, 원)·KRW 고정·시작일·만기일, 만기 알림 없음. 대안: 금액·기간만 / 접수 건 하위 / 통화 선택.
- **답(2026-11-25, D-FLOW-005 ⑤)**: 증권별 여러 건, KRW 고정(권장). → §4A.8

#### Q-FLOW-16 업종분류·가상자산 입력 규칙
- 권장: 업종분류 1~50자·빈 값 허용·제어 문자/카드번호 패턴 거부, 가상자산 예/아니오/미확인(기본 미확인), 변경 사유 1~200자·카드번호 패턴 거부. 대안: 가상자산 예/아니오만.
- **답(2026-11-25, D-FLOW-005 ③)**: 예/아니오/미확인 + 사유(권장). 사유는 D-FLOW-006 공통 칸으로 받는다. → §4A.10

#### Q-FLOW-17 KYC 마스킹 형식·원문 조회 단위·지분율 등급
- 권장: 생년월일 `****-**-**`, 실소유자 이름 `홍**`, 지분율 비마스킹, 원문 조회 1회 = 사업자 하나의 KYC 전체(감사 1행). 대안: 생년만 표시 / 항목별 원문 조회.
- **답(2026-11-25, D-FLOW-005 ⑥)**: 생년월일 전부 가림(권장). → §4A.11

#### Q-FLOW-18 KYC 입력 규칙
- 권장: OPERATOR 1인, 변경 사유 필수, REGISTERED 필수 조건 아님, 공동대표 각자 생년월일, 지분율 0.01~100.00·실소유자 합 ≤ 100, 25%는 입력 거부 기준이 아닌 안내(Demo 설정값). 대안: 등록 완료 필수.
- **답(2026-11-25, D-FLOW-005 ⑦)**: 선택 입력 + 변경 사유(권장). 대표자 본인 지분율은 Q-FLOW-29로 분리. → §4A.11

#### Q-FLOW-19 UNMASK 첫 적용 보안 세부
- 권장: Demo seed 계정에 UNMASK 미부여(SECURITY_ADMIN이 기존 2인 역할 부여 절차로), UNMASK 사유 카드번호 패턴 거부 적용. 대안: Demo 계정 하나에 미리 부여.
- **답(2026-11-25, D-FLOW-005 ⑧)**: 보안관리자가 부여, 사유 카드번호 거부(권장). → §4A.11

#### Q-FLOW-20 접수 항목·고정 목록 값
- 권장: 접수일 = 운영자 선택 날짜(KST, 기본 오늘, 미래 불가), 경로 = 직접 영업·전화·홈페이지·영업대행사·기타, 대행사는 경로 '영업대행사'일 때만 필수, 계약 담당자 **선택 입력**(ACTIVE OPERATOR), 희망 서비스 = 카드 인증(선택 가능)·키인·가상계좌('준비 전'), 메모 종류 = 일반·상담·계약·리스크. 대안: 담당자 필수 / 접수일 = 기록 시각.
- **답(2026-11-25, D-FLOW-004 ③)**: **계약 담당자 필수**(**권장안 아님 — 사용자 선택**). 나머지 목록·규칙은 권장안 그대로. → §4A.6·§4A.12

#### Q-FLOW-21 계약 불가·계약 해지 입력
- 권장: 둘 다 사유 필수(1~200자, 카드번호 패턴 거부, 이력 원문), 해지 근거 참조 선택. 대안: 사유 고정 목록 / 해지 근거 필수.
- **답(2026-11-25, D-FLOW-004 ②)**: 사유 필수, 해지 근거는 선택(권장). → §4A.3

#### Q-FLOW-22 영업대행사 기준 목록 관리
- 권장: 사용/중지, 등록·이름 수정·중지 = OPERATOR 1인 + 이력, 삭제 없음, 이름 UNIQUE, 중지 대행사는 새 접수에서 선택 불가·기존 표시 유지. 대안: 보안관리자 관리 / 중지 대행사 선택 가능.
- **답(2026-11-25, D-FLOW-005 ④)**: 운영자 1인, 사용/중지(권장). → §4A.7

#### Q-FLOW-23 계약 완료 이후 새 접수 건·DRAFT 사업자의 계약 완료
- 권장: 계약 완료 건이 있으면 새 접수 거절(해지 뒤에만), DRAFT 사업자도 계약 완료 허용. 대안: 계약 중 재계약 접수 허용 / DRAFT는 계약서 접수까지만.
- **답(2026-11-25, D-FLOW-004 ①)**: 해지 뒤에만 새 접수(권장). → §4A.3

#### Q-FLOW-24 계약서 수령 근거 참조 열람 범위
- 권장: 사업자 조회 역할(OPERATOR·APPROVER) 원문, 사업자 상세 열람 감사에 포함. 대안: 전이 행위자·APPROVER만 별도 조회 + 조회마다 감사.
- **답(2026-11-25, D-FLOW-004 ④)**: 사업자 조회 역할 전체(권장). → §4A.4

#### Q-FLOW-25 '상담 중'에서 바로 '계약 불가' 전이(리뷰 M-3)
- 권장: 허용(사유 필수), 계약 완료는 계약서 접수를 거쳐야 함. 대안: 계약서 접수 뒤에만.
- **답(2026-11-25, D-FLOW-005 ①)**: 허용(권장). → §4A.3

#### Q-FLOW-26 계약서 접수 → 상담 중 되돌리기 사유 규칙(리뷰 M-4)
- 권장: 불가·해지 사유와 같게(1~200자, 카드번호 패턴 거부, 이력 원문). 대안: 길이만 제한.
- **답(2026-11-25, D-FLOW-005 ②)**: 불가·해지 사유와 같게(권장). → §4A.3·§4A.3a

#### Q-FLOW-27 사유 입력 방식(항목마다 사유 vs 공통 칸)
- 질문이 아니라 사용자 지시로 받음(대안: 항목별 사유 칸).
- **답(2026-11-25, D-FLOW-006 — 사용자 지시)**: "사유 및 비고 란은 공통으로 사용하고 입력이 필수인 항목만 필수입력값으로" — 저장(전이) 한 번에 공통 칸 하나, 사유 필수 항목이 하나라도 있으면 필수·없으면 선택. 기존 중요정보 사유의 카드번호 검사(Q-PII-6)는 정하지 않음. → §4A.3a

### 5.3 rev 0.3a 질문(Q-FLOW-28~35 — 모두 답함)

#### Q-FLOW-28 공통 '사유 및 비고' 칸의 카드번호 패턴 검사
- 권장: 칸 전체 항상 검사, Q-PII-6도 함께 닫음. 대안: 기존 중요정보만 바꾼 저장은 검사 없음.
- **답(2026-11-25, D-FLOW-007 ①)**: 칸 전체 항상 검사(권장). Q-PII-6 닫힘. → §4A.3a, §4A.11 PII 변경 목록

#### Q-FLOW-28b 새 항목(KYC·리스크 표시·담보) 저장 방식
- 권장: 섹션별 따로 저장(각 섹션 저장 버튼 + '사유 및 비고' 칸), 기존 회사·대표자·담당자 폼은 하단 저장 그대로. 대안: 하단 저장 하나로 합침(D-ADMIN-UI-011 ② 그대로).
- **답(2026-11-25, D-FLOW-007 ②)**: 섹션별 따로 저장(권장 — 질문에 현행 "항상 편집 폼 + 하단 저장"을 명시하고 받은 답, D-ADMIN-UI-011 ②의 새 섹션 예외). → §4A.3a

#### Q-FLOW-29 대표자 본인 지분율
- 권장: 대표자마다 선택 입력. 대안: 받지 않음.
- **답(2026-11-25, D-FLOW-007 ③)**: **받지 않음**(**권장안 아님 — 사용자 선택**). → §4A.11

#### Q-FLOW-30 접수 항목 생성 뒤 수정
- 권장: 열린 건에서만 OPERATOR 1인 수정 + 이력. 대안: 생성 뒤 고정.
- **답(2026-11-25, D-FLOW-007 ④)**: 권장안(경로·대행사·담당자·희망 서비스). 접수일은 결정 문구에 없음 → Q-FLOW-36. → §4A.3

#### Q-FLOW-31 종료(TERMINATED) 사업자 메모
- 권장: 허용. 대안: 금지.
- **답(2026-11-25, D-FLOW-007 ⑤)**: 허용(권장). → §4A.12

#### Q-FLOW-32 담보 증권 종료(해지·반환) 표시
- 권장: 새 행으로 종료일 + 사유.
- **답(2026-11-25, D-FLOW-007 ⑥)**: 권장안. → §4A.8

#### Q-FLOW-33 마스킹된 KYC 수정 방식(재리뷰 N-3)
- 권장: 빈 칸은 유지('등록됨(가림)' 표시), 입력한 칸만 변경, 원문 응답 없음. 대안: 전체 재입력 / 원문 보기 후에만 수정.
- **답(2026-11-25, D-FLOW-008 ①)**: 권장안. 값 지우기는 Q-FLOW-37. → §4A.11

#### Q-FLOW-34 대표자 이름 수정·순서 변경 시 등록된 생년월일(재리뷰 N-2)
- 권장: 같은 사람이면 유지(대표자 안정 식별자), 삭제 후 새 사람 추가면 새로 입력. 대안: 변경 시 '재확인 필요'.
- **답(2026-11-25, D-FLOW-008 ②)**: 같은 사람이면 유지(권장). → §4A.11 대표자 안정 식별자

#### Q-FLOW-35 UNMASK 원문 범위(재리뷰 n-1)
- 권장: 현재 값만, 이력 탭은 'KYC 변경 + 사유'만 표시. 대안: 과거 버전 포함.
- **답(2026-11-25, D-FLOW-008 ③)**: 현재 값만(권장). → §4A.11

### 5.4 rev 0.3b 질문(Q-FLOW-36·37 — 모두 답함)

#### Q-FLOW-36 접수일 수정
- 권장: 열린 건에서만 수정 허용(같은 KST·미래 불가 규칙, 이력 + '사유 및 비고' 필수). 대안: 생성 뒤 고정 / 사유 선택.
- **답(2026-11-25, D-FLOW-009 ①)**: 권장안. → §4A.3

#### Q-FLOW-37 등록된 KYC 값 지우기
- 권장: 칸마다 '지우기' + '사유 및 비고' 필수(새 버전에서 그 칸 없음, 과거 버전은 저장만). 대안: 지우기 없음 / 섹션 전체 초기화만.
- **답(2026-11-25, D-FLOW-009 ②)**: 권장안(파기는 보관 기간 결정 후). → §4A.11

**[적용 메모 — 사용자 답 아님]** D-FLOW-009에 기록된 Coordinator 적용: 3차 리뷰 MAJOR-3(삭제된 대표자 생년월일)을 D-FLOW-008 ②③에서 끌어내 "현재 대표자 key 행만 응답, 종료 key 행은 저장만, KYC 저장은 잠금 뒤 현재 key 재검사"로 적용(§4A.11).

### 5.5 남은 질문

- rev 0.3c 현재 **사용자에게 물을 질문 없음**. 3차 리뷰 지적은 모두 기존 결정·구현 관례로 해소했다(§4A, [구현 관례]·[적용 메모] 표시). 구현 중 금융·보안 의미의 새 미결이 나오면 Q-FLOW-38부터 Coordinator에 반환한다.

---

## 6. 단계별 구현 배치(rev 0.3c — 화면 하나씩)

공통: 배치마다 명세 rev 확인 → 리뷰 → TDD(RED → 최소 구현) → Verifier 게이트 → 커밋 → **Demo에서 그 화면 확인**(D-PROC-007, 화면 하나씩). 구현자는 DONE을 스스로 판정하지 않는다. Kafka/Redis 없음 — 멱등 `admin_request` UNIQUE, 동시성 MySQL 행 잠금·UNIQUE. 모든 MASTER 배치는 §4A.14 8대 원칙 증거를 남기고, **금융 효과 0 시험**(`entitlement_revision`·Snapshot 불변, `outbox_event` 행 증가 0 — §4A.2 M-1)과 INSERT 전용 표의 UPDATE·DELETE 권한 오류 시험(§4A.14 M-2)을 포함한다. 공통 '사유 및 비고'(§4A.3a)를 쓰는 배치는 "필수 항목 포함 시 필수·없으면 선택" 시험을 둔다.

| 배치 | 화면(Demo 확인) | 내용 | 필요한 답(확정) | 막는 미결 | 수용 기준 |
|---|---|---|---|---|---|
| FLOW-1 | 사업자 상세 '계약 진행' 섹션(읽기 전용) | MASTER 접수 건 순수 Domain(상태·전이·생성 조건 규칙) + JPA adapter + 접수 건 표·INSERT 전용 전이 이력 표 + 생성 열 UNIQUE + **기존 사업자 이관 migration**(계약 완료(이관), 근거·담당자 없음) + 복구 SQL(이관 건 = 생성 순번 1 + 전이 이력 이관 행 1 — n-2) + jOOQ 상세 조회 + ADMIN 섹션 표시 | Q-FLOW-1·2·2b·3·23·25, D-FLOW-001 ②, D-FLOW-004 ①, D-FLOW-005 ① | 없음 | Domain 전이 표 전체(허용 7종·나머지 거절·같은 상태 거절·종결 비가역·상담 중 → 계약 완료 거절)·생성 조건(열린 건 있음/완료 건 있음 → 거절, 불가·해지 뒤 → 허용) 시험, migration 뒤 사업자 수 = 이관 건 수, 부분 실패 복구 시험, 표에 결정된 열만(대행사 열 없음 — m-2), 이관 건만 근거·담당자 NULL 허용 DB CHECK(접수 건 표·전이 이력 표 각각), 섹션에 "계약 완료(이관) · 근거: 이관", `entitlement_revision`·Outbox 불변 |
| FLOW-2 | 사업자 상세 '계약 진행' 섹션(생성·전이) + 사업자 편집 폼 칸 이름 | 접수 건 생성(DRAFT 사업자 포함 — 접수일·경로·**계약 담당자 필수**·희망 서비스; '영업대행사' 경로는 FLOW-4 전까지 막음)·상태 전이 명령(MASTER 서명 채널, OPERATOR 1인), **이력 전용 Work 경로 `recordHistory`(Outbox 없음 — M-1, n-6에 따라 이 배치에서 도입)**, 열린 건 접수 항목 수정 명령(경로·대행사·담당자·희망 서비스 — D-FLOW-007 ④, 접수일 + 사유 필수 — D-FLOW-009 ①), MASTER 카드번호 판정 신규 + 공통 시험 벡터(MINOR-3), 담당자 ACTIVE OPERATOR 검사는 ADMIN(n-3), 접수일 KST 이중 검사(n-4), 공통 '사유 및 비고'(되돌리기·불가·해지 필수), 계약 완료 근거 참조(ADMIN·MASTER 이중 — `ContractDocumentRef`, m-5), 해지 근거 선택, 해지 안내 "연결 가맹점 n개", 계약 담당자 표시명은 ADMIN이 채움(m-11), 근거 참조 원문 표시(사업자 상세 열람 감사 범위 — D-FLOW-004 ④). **기존 `BusinessProfileFormScreen` 사유 칸 이름을 '사유 및 비고'/'사유 및 비고(선택)'로 변경 + 기존 중요정보 사유에 카드번호 패턴 거부 추가(ADMIN·MASTER — D-FLOW-007 ①)**, PII-MASKING-POLICY Q-PII-6 관련 변경(§4A.11 목록) | Q-FLOW-2b·3·3b·6·12·20·21·24·26·27·28·30·36, D-FLOW-003 ①②③⑨, D-FLOW-004 ②③④, D-FLOW-005 ②, D-FLOW-006, D-FLOW-007 ①④, D-FLOW-009 ① | 없음 | 실제 MySQL: 동시 생성 → 1건, 동시 전이 → 하나만 성공, 해지 동시 → 직렬화, 전이 전후 `business.version` 불변(m-4). 같은 키 재전송 = 과거 결과, 다른 내용 = 충돌, 응답 유실 회수. 근거 없는 계약 완료 거절, 근거·사유 카드번호 패턴 거부(ADMIN·MASTER 각각), 미래 접수일 거절, 담당자 없음/비 OPERATOR 거절. 감사 1행/시도(내용 없음)·감사 실패 시 행위 실패. 종결 건 접수 항목 수정 거절, 수정 전후 `business.version` 불변, 같은 키 재전송은 담당자 재검사 없이 저장된 결과. 기존 사업자 편집 폼 시험은 칸 이름 외 기대값 불변 + 중요정보 사유 카드번호 거절 시험 추가, 이력 전용 경로 Outbox 행 증가 0, PII 표 #32·#33 추가·Q-PII-6 반영 |
| FLOW-3 | 가맹점 등록 ① 사업자 선택 안내 | **가맹점 등록 관문**: MASTER 새 거절 code, 최신 = 생성 순번·잠금 뒤 같은 TX JPA 저장 Port `ContractIntakeRepository.latestStatus`(상태 값) → Domain 판정(m-3, MINOR-B), 판정 순서 §4A.5, ADMIN 안내 "계약 완료 후 가맹점 등록 가능" + 사업자 상세 링크. **Demo seed가 접수 건 생성 → 계약서 접수 → 계약 완료** 수행, 재실행 시 완료 건 있으면 건너뜀(m-1). 기존 가맹점 관련 시험 Fixture에 계약 완료 접수 건 추가 | Q-FLOW-1·2·23, D-FLOW-001 ①, D-FLOW-004 ① | 없음 | DRAFT → `BUSINESS_NOT_REGISTERED`, REGISTERED + 접수 없음/열린 건/불가/해지 → 새 거절, REGISTERED + 계약 완료(이관 포함) → 성공. 해지 vs 가맹점 등록 동시 → 직렬화. 기존 가맹점 Site·계약·MID·개통 명령 무영향 시험. Demo seed 재실행 멱등. (자동 시험 아님 — Demo 시연 항목) FLOW-1 뒤 만든 사업자 동작(m-12) 1건 시연. **기존 시험 기대값을 완화하지 않음**(`MerchantRegistrationGateSpec`(m-6)·`MerchantRegistrationGateUseCaseSpec`·`MerchantRegistrationGateApiSpec`·`BusinessDemoSeedSpec`·`BusinessLegacySeedMigrationSpec`). 단독 배치·FULL 게이트 |
| FLOW-4 | 영업대행사 관리 화면 + 접수 건 대행사 선택 | MASTER 영업대행사 기준 목록(이름 UNIQUE·사용/중지), 등록·이름 수정·중지 명령 + 이력(OPERATOR 1인, 삭제 없음), 접수 건에 대행사 참조 열·FK 추가 migration(m-2), '영업대행사' 경로 열기 | Q-FLOW-8·20·22, D-FLOW-003 ⑤, D-FLOW-004 ③, D-FLOW-005 ④ | 없음 | 이름 중복 거절(동시 등록 → 1건, 실제 MySQL), 중지 대행사 새 접수 선택 거절·기존 건 표시 유지, 경로 ≠ 영업대행사인데 대행사 값 → 거절, 경로 = 영업대행사인데 없음 → 거절, 대행사에 수수료·정산 열 없음, Outbox 불변 |
| FLOW-5 | 계약 진행현황 목록 | jOOQ `…Reader`(접수일·상태·접수 경로·영업대행사 필터, keyset 페이지), 행에서 사업자 상세 이동, "계약 완료" 행에만 가맹점 등록 진입 | Q-FLOW-8·20·24 | 없음 | 필터 조합 SQL 시험(실제 MySQL), URL·저장소에 내부 키 없음, 목록에 근거 참조·사유·KYC·개인정보 열 없음 |
| FLOW-6 | 사업자 상세 '메모' 섹션 | ADMIN append-only 메모(INSERT·SELECT 권한만), 종류 일반·상담·계약·리스크, 1~1,000자, 카드번호 패턴 거부, OPERATOR 작성, 감사 `memo=PRESENT`·fail-closed | Q-FLOW-11·20·31, D-FLOW-003 ⑧, D-FLOW-004 ③, D-FLOW-007 ⑤ | 없음 | runtime 권한 UPDATE·DELETE 없음 시험, 카드번호 패턴 거부(고정 code, 값 없음), 감사에 내용 없음, 감사 실패 시 메모 미저장, ACTIVE·TERMINATED 사업자 모두 작성 성공, ADMIN 멱등 재전송, PII 표 #31 추가 |
| FLOW-7 | 사업자 상세 '리스크 표시' 행(업종분류·가상자산) | MASTER 표시용 속성(섹션 따로 저장 — D-FLOW-007 ②, INSERT 전용, 1인, 공통 '사유 및 비고' 필수, 이력), 업종 1~50자·빈 값 허용, 가상자산 예/아니오/미확인(기본 미확인), 문구 "표시용 — 한도·지급 판단에 쓰이지 않음" | Q-FLOW-9·16·27, D-FLOW-003 ⑥, D-FLOW-005 ③, D-FLOW-006 | 없음 | 사유 없는 변경 거절, 업종·사유 카드번호 패턴 거부, 현재 = 최신 순번, UPDATE 권한 오류, 정산·한도 코드가 값을 읽지 않음(의존 검사), §4A.3a 섹션 시험 묶음(version 불변·같은 기대 순번 동시 저장 → 1 성공·나머지 `SECTION_VERSION_CONFLICT`·첫 저장 기대값 0(MINOR-D)·하단 폼 vs 섹션 둘 다 성공·재전송·롤백), 종료 사업자 거절, Outbox 불변 |
| FLOW-8 | 사업자 상세 '담보(참고)' 섹션 | MASTER 담보 참고 기록(섹션 따로 저장, 증권별 여러 건, 종료 = 새 행 종료일 + 사유 필수, INSERT 전용, 정정 = 새 행, 1인, '사유 및 비고' 선택), KRW 고정·정수 원, 고정 문구 "참고 기록 — 선지급·지급 판단에 쓰이지 않음" | Q-FLOW-7·15·28b·32, D-FLOW-003 ④, D-FLOW-005 ⑤, D-FLOW-007 ②⑥ | 없음 | 문구가 섹션·행에 항상 보임(화면 시험), 증권번호 형식·카드번호 패턴 거부, KRW 외 통화·소수 금액·시작일 > 만기일 거절, 정정 = 새 행·이전 행 유지, 종료에 사유 없음 → 거절·종료일 < 시작일 거절, 선지급·지급 보류·한도 코드 경로 없음, PII 표 #34, 증권 key·순번 기대값(새 증권 = 기대값 없음), 현재 기록 (보험사, 증권번호) 중복 거절 — 잠금 아래 앱 검사, DB 이중 방어 없음(MINOR-C), 동시 같은 증권 추가 → 1건만, 정정으로 중복 → 거절, §4A.3a 섹션 시험 묶음(version 불변·동시 저장·하단 폼 vs 섹션 둘 다 성공·재전송·롤백 — MINOR-2), 종료 사업자 거절, Outbox 불변 |
| FLOW-9a | 사업자 상세 '대표자' 묶음(편집 폼 — 화면 변화 없음, 내부 key만) | **대표자 안정 식별자 단독 배치**(리뷰 MINOR-7): 대표자 key 열 + **SQL migration**(이관 형식 key 채움 — Java migration 없음, MAJOR-A) + 복구 SQL, MASTER key 선택 수신·대응표·규칙 ②~④, 상세 응답·편집 요청 계약에 key(golden 새 판), 배포 순서 MASTER → ADMIN | Q-FLOW-34, D-FLOW-008 ② | 없음 | "이름 오타 수정 → 같은 key", "순서 변경 → 같은 key", "삭제 + 새 사람 → 새 key", "두 key 맞바꾸기 → 거절", "요청 안 key 중복 → 거절", "명시 key = 다른 위치 암묵 유지 key → 거절", 형식 CHECK가 두 형식(앱 `rep_<ULID>`·이관 형식)만 허용, "key 없는 옛 요청 + 내용 동일 행 → DB key 유지", "다른 사업자·종료·형식 오류 key → 같은 고정 code", migration 정확성·부분 실패 복구 시험, `BusinessProfileChange` 변경 계산·중요정보 판정·version +1·이력 detail **불변**(기존 BUSINESS-PROFILE 시험 기대값 전부 그대로), 이력 golden 불변. **단독 배치·FULL 게이트** |
| FLOW-9b | 사업자 상세 '대표자·실소유자' 섹션(마스킹 표시·입력) | MASTER KYC(섹션 따로 저장 — D-FLOW-007 ②, 대표자 key별 생년월일, 실소유자 '대표자와 같음' 기본 / 이름·생년월일·지분율 최대 4명, 지분 합 ≤ 100, 25% 안내 문구 — Demo 설정값), INSERT 전용 버전 묶음, 공통 '사유 및 비고' 필수(변경·지우기), 칸별 지우기(D-FLOW-009 ②), 등록 완료 필수 아님, 성별 없음, **대표자 본인 지분율 없음**(D-FLOW-007 ③), 마스킹 칸 수정 = 빈 칸 유지·입력 칸만 변경·원문 미응답(D-FLOW-008 ①), 이력 탭 'KYC 변경' + 사유만(D-FLOW-008 ③), 현재 대표자 key 행만 응답([적용 메모]). **MASTER 일반 상세 응답은 마스킹 값만**(M-5), PII 표 #26~30·§4.1 생년월일 형식 추가, baseline PC-BIZ-POL-001 POL-003·§1-5·§7 "D-FLOW-001 ④로 변경" 주석(m-9) | Q-FLOW-5·5b~5e·17·18·27·28b·29·33·35·37, D-FLOW-002, D-FLOW-005 ⑥⑦, D-FLOW-006, D-FLOW-007 ②③, D-FLOW-008 ①③, D-FLOW-009 ② | FLOW-9a 완료 | "이름 오타 수정·순서 변경 → KYC 유지", "대표자 삭제 + 새 사람 → 새 사람 KYC 빈 칸", 첫 저장 기대 순번 0 시험(MINOR-D), 대표자 삭제 뒤 KYC 저장 → 새 버전에 종료 key 행 없음(MINOR-E), **"대표자 삭제 → 일반 상세·수정 화면 응답에 그 사람 생년월일 없음"**, **"KYC 저장 vs 대표자 삭제 동시 실행(실제 MySQL) → 직렬화, 삭제가 먼저면 KYC 저장 `REPRESENTATIVE_KEY_INVALID`"**, 빈 칸 저장 → 기존 값 유지, 지우기 + 사유 없음 → 거절, 지우기 → 새 버전에 칸 없음·과거 버전 저장 유지, 이력 탭에 과거 값 없음, 5명째 거절, 지분 합 > 100 거절, 25% 미만 지분 입력 허용(안내만), §4A.3a 섹션 시험 묶음(version 불변·동시 저장·하단 폼 vs 섹션 둘 다 성공·재전송·롤백 — MINOR-2), 종료 사업자 거절, **MASTER 쪽 시험**: 일반 상세·목록·jOOQ Reader 응답에 원문 없음(마스킹 `****-**-**`·`홍**`), MASTER·ADMIN 로그·감사·이력에 원문 없음, 기존 등록 완료·대표자·담당자 표시 시험 불변, 파기 기능 없음 |
| FLOW-10 | 같은 섹션의 '원문 조회' | ADMIN UNMASK 틀에 KYC 원문 공급 Port 연결(MASTER **서명 채널 전용 원문 operation**, TX 밖 호출), 권한·사유(카드번호 패턴 거부 — D-FLOW-005 ⑧)·직전 TOTP·감사 1행, 1회 = 사업자 KYC **현재 값** 전체(과거 버전 제외 — D-FLOW-008 ③), `no-store`, 결과 code 뜻 문서화(m-8), PII-MASKING-POLICY §4.4·§5.1·Q-PII-3 갱신 | Q-FLOW-5b·17·19·35, D-FLOW-002 ①, D-FLOW-005 ⑥⑧, D-FLOW-008 ③, D-FLOW-009 적용 메모 | 없음 | UNMASK 권한 없음 → 거절+감사(`DENIED`), 재인증 실패 → 원문 없음, 감사 실패 → 원문 없음, 사유 카드번호 패턴 → 거절(값 없음), MASTER 실패 → 감사(`SUCCESS` = 허가)만 남고 원문 없음, **MASTER 쪽 시험**: 원문 operation은 서명 채널 외 호출 거절, 목록/jOOQ Reader에 없음, MASTER 로그에 원문 없음, 응답 `Cache-Control: no-store` 헤더 시험 + 이 경로 캐시 구성 없음 확인(n-8), 과거 버전 값이 응답에 없음, **"대표자 삭제 → 원문 응답에 그 사람 없음"**([적용 메모] — MAJOR-3), 원문 조회는 현재 대표자 목록·KYC를 한 스냅샷에서 읽음(MINOR-F 구현 조건 — 코드 리뷰·시험), **"UNMASK vs 대표자 삭제 동시 실행(실제 MySQL) → 응답은 커밋된 한쪽 상태와 일치(삭제된 사람 원문 없음)"**. Demo seed 계정에 UNMASK 없음, DEMO 재인증 생략은 DEMO 간편 로그인 세션에서만 |
| (후속) | — | 수수료 읽기 전용 집계(SETTLEMENT-CONTRACT-001 뒤), 계약서 파일(별도 작업), 해지 연쇄(MASTER-LIFECYCLE-001), 업종 코드 관리, **KYC-RETENTION-001**(KYC 보관·파기 기간 — LIVE Gate 점검 항목, Q-PII-5, m-9), 담보 효력(정산·리스크) | Q-FLOW-3b·4·9·12, D-FLOW-002 ④ | — | 각각 별도 task 카드(Coordinator가 등록) |

- 순서 이유: FLOW-2(완료 전이 명령)가 FLOW-3(관문)보다 먼저여야 관문을 켠 뒤에도 Demo·운영자가 새 사업자를 계약 완료로 옮길 수 있다. 이관(FLOW-1)이 관문보다 먼저여야 기존 사업자의 가맹점 등록이 끊기지 않는다.
- FLOW-3은 Demo seed와 기존 시험 다수를 바꾼다 — 가장 작은 단위로 단독 배치·FULL 게이트 대상.

---

## 7. 위험·미결

| ID | 내용 | 대응 |
|---|---|---|
| R-1 | "계약 완료"(영업)와 "계약 승인"(가맹점 Revision 2인) 용어 혼동 → 운영자가 계약 완료만으로 결제 가능하다고 오해 | 화면 용어 분리("계약 진행: 계약 완료" vs "계약 승인: 승인됨"), 가맹점 머리 "결제 준비 상태" 줄은 승인·개통 사실만 사용 |
| R-2 | 효력 없는 값(희망 서비스·담보·리스크 표시)이 효력 값과 다르게 보임 | 사업자에 요율 없음(D-FLOW-001 ③). 희망 서비스·담보·리스크 표시 섹션에 "참고/표시용" 고정 문구 |
| R-3 | 계약 해지가 기존 가맹점에 연쇄되지 않아 해지 사업자의 가맹점이 계속 결제 | 사용자 결정(D-FLOW-003 ②) — 해지 화면에 연결 가맹점 n개·개별 중지/철회 안내, 연쇄는 MASTER-LIFECYCLE-001 |
| R-4 | 메모·근거 참조·사유 자유 문구에 개인정보·카드번호 유입 | 카드번호 패턴 거부(공통 사유 칸 전체 — D-FLOW-007 ①, Q-PII-6 닫힘)·안내·감사에 내용 없음, 보관·파기는 Q-PII-5 미결 |
| R-5 | **(사용자 선택으로 변경)** KYC 항목을 수집 근거·보관 기간 확정 전에 수집 → 개인정보 축적 | 사용자가 "이번에 수집"을 선택(D-FLOW-001 ④, 권장안 아님) + Demo는 지금·기간은 LIVE 전(D-FLOW-002 ④). 대응: 마스킹 + UNMASK만, 성별 미수집, 파기 기능 없음 상태를 **KYC-RETENTION-001 LIVE Gate 점검 항목**으로 등록(운영 준비 전 보관·파기 기간 결정 필수). Demo 데이터는 가상 값만 사용 |
| R-6 | 이관 접수 건 "계약 완료(이관)"가 실제 계약서 없이 완료로 보임 | 이관 표시를 따로 두고 근거·담당자 칸 "이관", NULL은 이관 건만 DB CHECK |
| R-7 | pg-lab 화면의 내부 동작(승인 절차·그룹 의미·등급 기준)은 화면만 보고 짐작 | [추측] 표시 항목은 결정 근거로 쓰지 않음 |
| R-8 | 법규(고객확인·영중소 우대수수료·계약서 보관 기간) 해석은 이 문서에서 확정하지 않음 | 컴플라이언스 확인으로 남김(KYC 보관 기간은 LIVE 전) |
| R-9 | **담보 참고 오해** — 금액·기간이 보이면 "담보 확보됨"으로 읽혀 선지급·지급 판단에 쓰일 위험(사용자 선택 D-FLOW-003 ④) | 고정 문구 "참고 기록 — 선지급·지급 판단에 쓰이지 않음", 선지급·지급 보류·한도 코드가 값을 읽지 않음을 시험으로 고정, 효력 부여는 별도 결정 전 금지. 종료 표시 없이 만기 전 해지된 증권이 유효해 보일 수 있음 → Q-FLOW-32 |
| R-10 | **업종 자유 문구 품질** — 직접 입력이라 같은 업종이 여러 표기로 쌓여 나중 코드 관리·리스크 판단 전환 때 정리 비용, 개인정보·카드번호 유입 가능 | 1~50자·카드번호 패턴 거부(D-FLOW-005 ③), 이력·사유로 출처 추적, 코드 관리 작업에서 매핑·정리(그 전에는 효력 없음) |
| R-11 | **UNMASK 첫 실제 데이터** — 지금까지 `NoDataSource`만 돌려주던 틀이 처음 원문을 내보냄. 권한 부여·재인증·감사·캐시·DEMO 재인증 생략·MASTER 원문 operation 노출의 실제 결함이 처음 드러날 수 있음 | FLOW-10 단독 배치, 거절·실패 경로 전부 시험(ADMIN·MASTER 양쪽), MASTER 원문 operation 서명 채널 전용·Reader 분리, `no-store`, Demo seed 계정에 UNMASK 없음, Demo 재인증 생략은 DEMO 한정·LIVE 기동 거부 유지 |
| R-12 | FLOW-3 관문이 Demo seed·기존 시험을 대거 깨뜨림 | 기대값 완화 금지, Fixture에 계약 완료 접수 건을 명시 추가, 단독 배치·FULL 게이트 |
| R-13 | 공통 '사유 및 비고' 칸 하나에 서로 다른 검사 규칙이 섞임 | **해소**(D-FLOW-007 ①) — 칸 전체 항상 카드번호 검사. 기존 중요정보 사유에 13~19자리 숫자를 쓰던 운영 습관이 있으면 거절될 수 있음 → 화면 안내 "개인정보·카드번호를 적지 마세요" 유지 |
| R-14 | 기존 `Work.record`를 그대로 쓰면 소비자 없는 Outbox 이벤트가 쌓여 "PAYMENT 무영향" 증거가 흐려짐(리뷰 M-1) | 이력 전용 Work 경로 + Outbox 행 증가 0 시험(§4A.2) |
| R-15 | **대표자 key 도입**이 기존 BUSINESS-PROFILE 저장·변경 계산을 흔들 위험, 맞바꾸기·중복·다른 사업자 key로 KYC가 엉뚱한 사람에게 붙을 위험, 과거 종료 행의 같은 사람 추정 불가 | key는 Domain 동일성·변경 계산에서 제외(대응표), 잠금 뒤 사업자 범위 검사·맞바꾸기/중복 거절·고정 code, 기존 시험 기대값 불변, FLOW-9a 단독 배치·FULL 게이트, 과거 종료 행은 행마다 별도 key. 남는 한계: "이름 수정 + key 맞바꾸기" 조작은 "순서 변경 + 이름 수정"과 구별할 수 없다 — 대표자 변경은 중요정보라 '사유 및 비고'·변경 이력으로 추적한다 |
| R-16 | 섹션별 저장(D-FLOW-007 ②)과 하단 저장이 한 화면에 공존 → 운영자가 하단 저장으로 KYC가 저장된다고 오해 | 섹션마다 저장 버튼·'사유 및 비고' 칸을 섹션 안에 두고, 저장되지 않은 섹션 입력이 있으면 이동 전 안내 [구현 관례] |
| R-17 | 삭제된 대표자의 생년월일이 저장은 남아 있음(파기 기능 없음) | 어떤 응답에도 내보내지 않음([적용 메모]), 파기는 KYC-RETENTION-001(LIVE 전) |
| 미결 | Q-FLOW-1~37·2b·3b·5b~5e·28b 모두 답(D-FLOW-001~009). 남은 사용자 질문 없음(§5.5) | |

---

## 8. 개정 이력

| rev | 날짜 | 작성 | 내용 |
|---|---|---|---|
| 0.1 | 2026-11-24 | Implementer | 명세 초안(차이·선택지·질문) |
| 0.2 | 2026-11-25 | Coordinator | Q-FLOW-1~5 답(D-FLOW-001), 후속 질문 2b·3b·5b~5e |
| 0.2a | 2026-11-25 | Coordinator | Q-FLOW-5b~5e 답(D-FLOW-002) |
| 0.3 | 2026-11-25 | Implementer(문서만) | Q-FLOW-2b·3b·6~14 답(D-FLOW-003) 반영. §4A 확정 모델, §5.2 질문 Q-FLOW-15~24, §6 FLOW-1~10, §7 R-5 갱신·R-9~12 |
| 0.3a | 2026-11-25 | Implementer(문서만 — 코드·DB·DECISIONS·questions·tasks 변경 없음, 자기 완료 판정 아님) | Q-FLOW-15~27 답(D-FLOW-004·005·006) 반영 — 계약 담당자 필수(사용자 선택), 상담 중 → 계약 불가, 되돌리기 사유, 공통 '사유 및 비고' 칸(§4A.3a). 독립 리뷰 반영: M-1(이력 전용 Work 경로·Outbox 증가 0), M-2(INSERT 전용·최신 순번으로 통일), M-5(마스킹은 MASTER·원문 operation 서명 채널 전용·MASTER 시험), m-1~m-13(구현 관례 표시, m-7·m-10·m-13은 질문). 남은 질문 Q-FLOW-28~32(§5.3), §7 R-13·R-14 |
| 0.3b | 2026-11-25 | Implementer(문서만 — 코드·DB·DECISIONS·questions·tasks 변경 없음, 자기 완료 판정 아님) | Q-FLOW-28·28b·29~35 답(D-FLOW-007·008) 반영 — 칸 전체 카드번호 검사·Q-PII-6 닫힘, 섹션별 따로 저장(D-ADMIN-UI-011 ② 새 섹션 예외), 대표자 본인 지분율 미수집(사용자 선택), 접수 항목 수정·종료 사업자 메모·담보 종료, KYC 수정·대표자 안정 식별자·UNMASK 현재 값만. 재리뷰 반영: N-1(별도 동작·섹션 저장 = 명령 1개 = TX 1개·사업자 version 불변), N-2(대표자 key 설계·시험), N-3, n-1~n-8. 남은 질문 Q-FLOW-36·37(§5.4), §7 R-13 해소·R-15·R-16 |
| 0.3c | 2026-11-25 | Implementer(문서만 — 코드·DB·DECISIONS·questions·tasks 변경 없음, 자기 완료 판정 아님) | Q-FLOW-36·37 답(D-FLOW-009) 반영 — 접수일 열린 건 수정 + 사유, KYC 칸별 지우기 + 사유. [적용 메모](D-FLOW-009, 사용자 답 아님) 반영 — 현재 대표자 key 행만 응답. 3차 리뷰 반영: MAJOR-1(key는 Domain 동일성 밖 대응표·맞바꾸기/중복/범위 거절·이력 불변·golden 영향), MAJOR-2(가맹점 등록 경로 좁은 Port 예외), MAJOR-3(삭제 대표자 응답 제외·동시 실행 시험), MINOR-1~8(섹션 동시 저장·시험 묶음·MASTER 카드번호 판정·담보 순번/중복·key migration·배포 순서·requireActive·FLOW-9a/9b 분리·제목/표 정리). 남은 사용자 질문 없음 |

| 0.3d | 2026-11-25 | Implementer(문서만 — 코드·DB·DECISIONS·questions·tasks 변경 없음, 자기 완료 판정 아님) | 4차 리뷰 반영: MAJOR-A(대표자 key는 SQL migration·이관 형식 + 앱 ULID 두 형식 CHECK, Java migration 제거 — Coordinator 결정 [구현 관례], `generateJooqMain` 근거), MINOR-A(최종 key 집합 중복 검사), MINOR-B(관문 = `ContractIntakeRepository.latestStatus` 상태 값 → Domain 판정, ArchUnit ③), MINOR-C(담보 중복 잠금 아래 앱 검사·DB 방어 없음·정정 중복 거절), MINOR-D(섹션 첫 저장 기대값 0), MINOR-E(새 KYC 버전에 종료 key 행 제외), MINOR-F(원문 조회 한 스냅샷), R-15 한계 한 줄 |