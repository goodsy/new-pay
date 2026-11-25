# NORMALIZATION-PLAN — 관리체계 정상화 계획 (rev 0.1a, 제안)

- 작성: 2026-11-25, GOV-DIAG-001 종합 단계. 기준 커밋 `41139db`(main). 소스·DB·기존 문서·git은 바꾸지 않았다.
- **제안 문서다.** 정책을 정하지 않는다. 정책 충돌·미결은 [POLICY-CONFLICTS.md](POLICY-CONFLICTS.md)(PC-nn)에 근거·대안만 있다. 사용자 결정 전에는 해당 운영 동작을 구현하지 않는다.
- **실행 검증 아님.** 이 문서와 원 진단 5종은 정적 읽기·grep·스크립트 집계다. 시험·게이트·빌드를 실행하지 않았다. "구현"은 코드·시험 파일이 있다는 뜻이지 통과·운영 준비를 뜻하지 않는다.
- **진단 완료 전 전면 리팩터링 금지**를 유지한다(RULE-GOV-001 "지금 바로 멈추고 전체 도메인부터", [RECOVERY-ROADMAP](../assessment/RECOVERY-ROADMAP.md) 원칙 "사용자 결정이 필요한 단계는 결정 전에 시작하지 않는다").
- 범위 밖: Spring Boot BOM 기반 버전 호환 점검(CLAUDE.md 필수 기술)은 이번 진단에서 하지 않았다(TECH-BASELINE·TECH-001 소관).
- 원천 약어는 POLICY-CONFLICTS 머리와 같다(`DC-M`·`DC-P`·`SC`·`UI`·`INV`·대장 `BIZ`~`ADM` (a)~(f)). 기존 진단 [docs/assessment](../assessment/)(기준 `378aff7`)는 다시 쓰지 않고 참조만 한다.

## 목차

1. 현재 실제 구현 수준 요약
2. 관리체계 문제 요약
3. 전체 문제 통합 목록(P0 → P4)
4. 기준선 문서 구성안
5. 정비 단계 제안
6. 사용자가 우선 결정할 사항

---

## 1. 현재 실제 구현 수준 요약

### 1.1 도메인별 수준 (증거 기반, 실행 검증 아님)

판정: **구현** = main에 코드·시험 파일 있음(통과 미확인) / **부분** = 일부 단계·경로 없음 / **문서만** = 결정·명세만 있고 main 코드 없음.

| 영역 | 수준 | 빠진 것 | 근거 |
|---|---|---|---|
| 사업자(Business) 등록·수정·두 축 상태 | 구현 | INACTIVE 전이, 진위확인 4값(NOT_CHECKED만) | DC-M §3.1 |
| 가맹점(Merchant)·가맹점 코드 | 구현 | 종료·차단 | DC-M §3.2 |
| Site·Site 심사(PENDING/APPROVED/SUSPENDED/REJECTED) | 구현 | Site 종료, 같은 채널 재신청(PC-01) | DC-M §3.3 |
| 계약·Revision·CSA·적용 Revision·MID 발급 근거 | 구현 | 철회·종료·재활성화, CSA 철회 | DC-M §3.4 |
| MID·최초 Default·PRIMARY 연결 | 부분 | Merchant당 MID 1개 고정, 추가 MID·ADDITIONAL·Default 교체·연결 종료 | DC-M §3.5, DC-M-11·12 |
| 권리 Snapshot(MASTER → PAYMENT) | 구현 | 차단 기록(`blocked=false` 고정), 철회 Revision 제외 | DC-M §3.6 |
| MASTER 생명주기(종료·차단·철회·교체) | 문서만 | 전부(MASTER-LIFECYCLE-001 BACKLOG) | DC-M-05·06 |
| 개통(결제수단 등록·승인, 통화, 한도 조회, 코드 변경) | 부분 | 카드 Route(B3-3), 재개(B3-4), 한도·수수료·정산 정책(B4), 안전 중지(B5) | INV §5-4 #2~#5 |
| 사업자 계약 접수·KYC(MERCHANT-FLOW-001) | 문서만(main) | 전부 — `wip/flow-1` 미병합·미검증 | DC-M §2·DC-M-16, INV §5-4 #1 |
| 결제 생성·주문 key·결제시도·결제창·인증·승인·UNKNOWN 회복 | 구현 | 외화 승인(KRW만, PC-03) | DC-P §4.1, PAY 대장 |
| 카드 취소·부분취소·반환 잔액 | 구현 | 계좌 환불(v1 범위 밖, D-CANCEL-007) | DC-P §4.2 |
| 사건 hold(늦은 성공·상충·이중 승인) | 부분 | 감지·차단은 코드, 해제 실행 경로 0(domain 메서드만) | DC-P-03, PAY (e)-2 |
| 가맹점 Webhook | 구현 | endpoint 등록·비밀 발급·운영자 재전송(WEBHOOK-ENDPOINT-001 BACKLOG) | WH (d)·(f)-6 |
| 결제 사실 전달(PAYMENT → FINANCIAL) | 부분 | `FINANCIAL_FACT`만 전달, RISK_INCIDENT·OPERATIONAL_RISK 미전달 | DC-P §5.3 |
| 매입·재매입(financial-batch `acquiring`) | 부분 | LIVE 봉인 차단, 보류·격리 해소 3종(ACQ-ADMIN-002), 매입 대사 | DC-P §4.3, INV §5-4 #6 |
| 정산·수수료 계산·지급·선지급·Settlement/PayoutHold | 문서만 | 모듈·Aggregate·표 0, 수수료 Snapshot은 버전 문자열만 | DC-P §4.4·§4.5·DC-P-09·10 |
| 대사(RECON)·Adjustment·자동 Recovery | 문서만 | 입력 이벤트·보류 표지만 코드, 소비자 0 | DC-P §4.6 |
| 관리자 인증·계정·역할·2인 승인·감사 | 구현 | 승인 상세 ADMIN 계정 6종 화면 | INV §4·§5-2 |
| 운영자 보정 취소·재매입 | 구현 | — | INV PAY-01·REC-03 |
| 마스킹 해제 | 부분 | 백엔드 틀만(대상 공급자 없음, 항상 204), 화면 없음 | INV ACC-05, ADM (e)-3 |
| 대사 발견 화면 | 부분 | 조회만(해결·종료·보정 없음) | INV REC-01 |
| 운영 홈 KPI | 부분 | 정산 흐름·지급 예정액 자리표시, '확인 필요' 셈 일부 | INV HOME-04, CAN (e)-3 |
| 가상 기관 Simulator | 구현 | 은행 모듈 없음(VAN·매입사·인증·가맹점만) | DC-P §4.5 |

### 1.2 규모·정합 지표 (원 진단 수치 인용)

| 지표 | 값 | 출처 |
|---|---|---|
| Kotlin main / test | 1,620파일 100,910줄 / 755파일 105,117줄 | SC 머리 |
| 관리자 화면 main / test | 14,960줄 / 15,850줄(88파일) | UI §2 |
| 관리자 기능 | 44개: 완성 34 · 부분 4 · 화면만 5(의도 4, 미구현 자리 1) · 백엔드만 1. ADMIN API 61개 중 프런트 호출 60 | INV §4·§6 |
| 강제 규칙 위반 | Domain 금지 import 0/313, application → adapter 0, ArchUnit 공통 규칙 적용 81/90(실제 틈 5), Modulith 4/5, provided `package-info` 10/15 | SC §5 |
| TX 경계 | persistence MANDATORY 79/79, TX 블록 안 원격 호출 0/142(휴리스틱), 원격 adapter 실행 시점 확인 4/12 | SC §5 |
| 결제·금융 코드 변경 | `378aff7..HEAD` payment-api·financial-batch main 0줄(기존 진단 유효) | DC-P §2 |
| MASTER 코드 변경 | `378aff7..HEAD` master-api 소스 변경 커밋 1개(`cd9faa4`) | DC-M §2 |
| 업무 규칙 대장 초안 | 790행: 확정 612 · 제안 119 · Demo값 27 · 대체됨 14 · 미결 17 (WH 1행 미분류) | POLICY-CONFLICTS §5.1 집계 |
| 시험 없는 확정 규칙 | FIN (d) 목록 61행(정산·수수료·지급·Hold·대사 전부 + BR-FIN-021·073), 그 밖 도메인은 각 대장 (d) 목록 | 각 대장 (d) |
| 시험에 BR-ID 연결 | 0(CAN (d) 확인, 다른 대장도 연결 고리는 D-ID·CX-/K- 번호뿐) | CAN (d) |
| 시험 실행 | **이번 진단에서 실행하지 않음.** 참고: 기존 진단의 기록 "전체 게이트 PASS(Kotlin 5,096 / FE 694)"는 `378aff7` 기준 기록이며 이번에 재확인하지 않았다 | RECOVERY-ROADMAP 전체 완료 기준 표 |

### 1.3 한 줄 판단 (진단 인용)

- 감지·차단 쪽(멱등·UNKNOWN 유지·append-only·TX 경계·hold 개설)은 코드에 있고, **끝맺음 쪽(hold 해제·RECON·정산·지급)은 문서만**이다(DC-P §1-2).
- MASTER의 확정 불변식은 Domain + DB 이중으로 있고, 불일치 대부분은 **확정됐지만 아직 만들지 않은 생명주기**에서 나온다(DC-M §1).
- 강제 장치가 있는 규칙은 거의 지켜지고, 틈은 **강제되지 않는 규칙과 같은 규칙의 복제**에 몰려 있다(SC §1-3).
- 관리자 화면의 가장 큰 위험은 **정한 규칙과 화면이 다른 곳**이고, 시험이 그 노출을 단언한다(UI §0).

---

## 2. 관리체계 문제 요약

RULE-GOV-001 §1의 P-1~P-6에 이번 진단 근거를 붙였다. G-7~G-9는 이번 진단에서 추가로 관찰한 것이다.

| # | 문제 | RULE-GOV-001 사례 | 이번 진단 근거 | 관련 NP |
|---|---|---|---|---|
| P-1 | 규칙이 착수 전에 정리되지 않고 리뷰에서 뒤늦게 발견 | MERCHANT-FLOW-001 명세 5회 개정, 질문 37개 | "최신 접수 건" 관문이 사용자 문구에 없이 리뷰 m-3에서 추가(BIZ (b)-6, BR-BIZ-280). 재매입 세대 취소 판정이 ACQ-ADMIN-001 rev 0.7 L-6에서 열림(FIN (a)-9) | NP-21·22 |
| P-2 | 현재 유효 규칙의 정본 없음 | BRN 마스킹 D-BIZ-006 → 007 → D-PII-001 | 790행 규칙이 DECISIONS·questions·명세·baseline에 분산. BRN 노출 대체 사슬 9단계(BIZ (a)-1), 옛 결정 4건 역참조 없음(DC-M-03). 대장끼리도 같은 규칙이 겹침(BIZ (b)-7, WH (f)-10) | NP-06·108 |
| P-3 | 결정이 바뀌어도 정책서·명세 본문 그대로 | D-FLOW-001 ④ ↔ POL-003 | 사업자 정책서(PC-55), 범위 문서·CLAUDE.md:42(PC-56), 답변된 질문이 "미결"로 남은 본문 8종(PC-58), 폐기 설계가 남은 명세 9종(PC-59), DOMAIN-DEFINITION 2026-10-20 이후 미갱신(PC-61) | NP-91~101 |
| P-4 | 화면 요청이 업무 규칙 변경으로 들어오는 관문 없음 | pg-lab 참고 화면 → 계약 단계·KYC·담보 | 화면 명세(MERCHANT-OPS-002 §9)가 개인정보 규칙(PII #25)과 반대로 정해짐(PC-08). 승인 조회 권한이 화면과 서버에서 다르고 결정 근거 없음(PC-46) | NP-24·26 |
| P-5 | 규칙과 시험 연결 미검사 | 게이트는 시험 통과만 확인 | 시험에 BR-ID 0. 시험이 결정 위반을 기대값으로 고정(UI-02 `ApprovalsScreen.merchant.test.tsx:125-126`, UI-05 6줄, INV `navigation.test.ts:21`). 대체된 규칙 이름을 단 시험(`historyTarget.test.ts:29`, `CreatePaymentApiSpec.kt:260`). CLAUDE.md 핵심 규칙 "전액 반환 뒤 재승인 금지"의 실제 취소 경로 시험 없음(PAY (d) BR-PAY-022) | NP-63 |
| P-6 | Coordinator 해석이 규칙으로 들어가는 통로 | Q-IA-4, D-FLOW-009 적용 메모 | 대장 `제안` 119행. D-ADMIN-HOME-001이 금융 의미 KPI 정의를 "확인 대상 아님"으로 기록(PC-70). D-AUTH-009 질문 없이 적용(PC-74). Implementer 해석 I-9·I-2·I-5, Coordinator C-5가 돈 흐름 효과를 정함(PC-66~68). 시험 기대값 CC-4 → CC-8 이동(PC-66) | NP-12·19·20·21·29·30 |
| G-7 | 질문 대장 누락 | — | Q-PII-3·5·7·8·9·10이 PII-MASKING-POLICY §8에만 있고 questions.json에 없다(DC-M-23, ADM (f)-9) | NP-103 |
| G-8 | 작업 대장과 실제 불일치 | — | MERCHANT-BIZ-LOOKUP-001 BACKLOG(D-BIZ-004로 합쳐짐), SITE-CONTRACT-CODE-001 "로컬 브랜치"(실제 `origin/wip/scode-1`), WEBHOOK-ENDPOINT-001 선행 모두 DONE인데 명세 없음(DC-M-22, WH (f)-6) | NP-102 |
| G-9 | 강제 없는 관례·복제 | — | 영속 enum 이름 고정이 주석뿐(SC-01), TX 밖 실행 확인 4/12(SC-04), 카드번호 정규식 4벌·LocalTransactions 11벌·D-CPC-002 형식 4벌(SC-05·09·10), 관리자 화면 접두어 목록 4벌(UI-04) | NP-28·47·48·52·53·55 |

---

## 3. 전체 문제 통합 목록 (P0 → P4)

- ID `NP-nn`(Normalization Problem). 여러 진단의 같은 문제는 한 행으로 합치고 출처를 모두 적었다. 정책 결정이 걸린 행은 `PC`에 POLICY-CONFLICTS ID를 적었다.
- 난이도·예상 영향·검증 방법은 원 진단 값을 옮겼다. 원 진단에 없어 종합 단계에서 붙인 값에는 `*`를 달았다. 검증 방법은 제안이며 실행하지 않았다.
- P0·P1 표의 `원칙` 열은 CLAUDE.md 8대 원칙 약어다: FC Financial Consistency, TS Transaction Safety, ID Idempotency, CC Concurrency Control, RV Recoverability, RC Reconciliation, AU Auditability, OB Observability. 보안·상태 전이·관리체계 문제는 8대 원칙 밖이라 따로 적었다.
- 병합 행이 여러 우선순위를 담으면 행 안에 `하위:`로 원래 우선순위를 남겼다(부풀림 방지).
- 집계: **P0 4 · P1 26 · P2 36 · P3 24 · P4 32 = 122건**. 입력은 번호 붙은 진단 항목 96건(DC-M 25 · DC-P 19 · SC 22 · UI 30) + 규칙 대장 진단 절 6개 + 기능 인벤토리 식별 절이다. 병합: DC-M-14+DC-P-11, DC-M-24+SC-19, SC-12+UI-21, DC-M-18+UI-20+DC-P-16, DC-M-19+DC-P-15+DC-P-17, DC-M-01+BIZ (e)-1+ADM (e)-6 등.

### 3.1 P0 — 금융 정합성·보안·데이터 손상 (4)

| ID | 문제 | 출처 | PC | 원칙 | 난이도 | 예상 영향 | 검증 방법 |
|---|---|---|---|---|---|---|---|
| NP-01 | 관리 명령 멱등 지문이 사업자등록번호·개인정보를 넣은 무염 SHA-256(D-MOPS-010 ② HMAC 미적용) | DC-M-01, BIZ (e)-1, ADM (e)-6 | PC-12 | ID·보안 | 중 | DB 접근자가 번호·개인정보를 대입 확인. Demo, LIVE Gate 추적 중 | 지문 단위 시험(키 없이 재현 불가), 같은 키·다른 내용 409 회귀, 기존 행 호환 비교(Demo 한정) |
| NP-02 | 기관이 확정한 효과(종료 뒤 늦은 승인, 거절 뒤 늦은 취소 성공, 이관 UNKNOWN 중 실제 효과)를 PAYMENT 거래·FINANCIAL 사실로 기록할 경로 없음. D-CANCEL-011 보정은 실제로 도달 불가 | DC-P-01, CAN (d) BR-CAN-042·044·045 | PC-38 | FC·RC | 상 | [추정] 고객 청구·반환 사실과 가맹점 정산 불일치. 현재 TEST 한정, hold로 주문만 막음 | Simulator 늦은 성공 주입 → `payment_transaction`·`payment_fact_*` 행 수 확인 |
| NP-03 | PAYMENT 사건 hold·RISK_INCIDENT·OPERATIONAL_RISK가 FINANCIAL에 안 감 → 매입이 사건 결제 SALE도 정기 파일에 넣음 | DC-P-02·§5.3 | PC-37 | FC·RC | 중 | [추정] 반환된 승인의 SALE 매입, 이중 승인 건 정상 정산 | 늦은 취소 성공 뒤 다음 봉인 파일에 해당 SALE 포함 여부(시험 존재 미확인) |
| NP-04 | 사유 칸 카드번호 패턴 거부 미구현(D-FLOW-005 ⑧ 마스킹 해제 사유, D-FLOW-007 ① 사업자 '사유 및 비고') | BIZ (e)-2, ADM (e)-1·(e)-2, 관련 SC-05 | PC-13 | 보안·AU | 하* | 사유로 카드번호가 MASTER·감사에 저장 가능. P0 부여 근거*: CLAUDE.md 검증과 안전 "Secret·카드정보 비노출", 사업자 중요정보 변경 사유는 main에 이미 있는 자유 입력 경로(`BusinessChangeReason.kt:8-21`, BIZ (e)-2). 완화 사정: 결정일 2026-11-25·FLOW-2 시행 예정, Demo 한정(실데이터 없음), 마스킹 해제는 대상 공급자가 없어 항상 204(INV ACC-05) | 카드번호 공통 벡터(SC-05 제안)로 두 사유 입력 거부 시험 |

### 3.2 P1 — 정책 충돌·잘못된 상태 전이 (26)

| ID | 문제 | 출처 | PC | 원칙 | 난이도 | 예상 영향 | 검증 방법 |
|---|---|---|---|---|---|---|---|
| NP-05 | 거절 Site를 같은 채널로 재신청 불가(D-MS-005 × D-MD-008) | DC-M-02, BIZ (b)-1·(e)-3·(f)-2 | PC-01 | —(상태 전이) | 중 | 재심사 불가, 화면 안내와 동작 불일치 | 등록 → 거절 → 같은 채널 새 Site → `SITE_CHANNEL_DUPLICATE` 확인 시험 |
| NP-06 | 결정 원장 대체·변경 역참조 누락: BRN 마스킹 옛 결정 4건, D-WH-009(→D-FFR-001), D-ADMIN1-002(→022), D-AUTH-003(D-CANCEL-012 예외), D-CANCEL1-001 진입점 문언. **하위: BRN 마스킹 4건 P1(DC-M-03), 나머지 P4(PC-53·54·57)** | DC-M-03, BIZ (a)-1·(b)-5, ADM (a)-1·(a)-2, WH (a)-1, CAN (a)-6·(b)-3 | PC-52·53·54·57 | —(관리체계) | 하 | 옛 결정 근거로 마스킹 부활 등 | 원장 grep: 대체된 결정 행마다 `[변경: …]` 표시(하네스 점검 스크립트 후보) |
| NP-07 | 한 Merchant의 동시 유효 계약·같은 종류 CSA 복수 — 결정 없음, 코드 무제한 | DC-M-04 | PC-33 | FC | 중 | 수수료·정산 정책 근거 CSA 모호(지금은 MID 1개라 영향 없음) | 같은 Merchant MERCHANT Scope 계약 2개 등록·승인 성공 여부 시험 |
| NP-08 | PAYMENT IncidentHold 해제 실행 경로 0(Use Case·명령·INSERT 권한), CANCEL_RESULT_CONFLICT는 domain이 해제 거절. 해제 규칙 "2인 + 재대사 MATCH"는 확정(D-AUTH-002, CLAUDE.md "보정 후 MATCH 확인 전 해결 금지") — 문제는 실행 경로 부재 | DC-P-03, PAY (e)-2·F-9, CAN (d) | PC-38 | RV·RC | 중 | hold된 주문은 영구히 새 결제·가맹점 취소 차단 | main 호출자 수(`IncidentHoldSettlement`), 권한 표 |
| NP-09 | 대사 이관된 UNKNOWN(승인·취소) 종결 주체 없음. 취소 UNKNOWN의 "임시 출구 없음 + RECON 전 LIVE 금지"는 확정(D-CANCEL1-003, BR-CAN-030·031, BR-FIN-125), 승인 UNKNOWN의 이관 뒤 유지도 확정(D-AUTH-009) — 문제는 실행 주체(RECON) 부재 | DC-P-04, CAN (d) BR-CAN-031, FIN (f) RG-09 | PC-38 | RV·RC | 상 | 주문 영구 차단, 반환 예약 RESERVED 영구, 한도 점유 | 이관 이벤트 `publish_status` 분포(Demo DB, 미확인) |
| NP-10 | 매입 결과 UNKNOWN·취소 매입 RECONCILE 소비자 없음, UNKNOWN 재매입은 대사 전 거절 → 영구 미매입 | DC-P-08, FIN (e)-3 | PC-38 | RC·FC | 상 | 미매입·취소 미반영 | UNKNOWN 레코드·RECONCILE 건수 |
| NP-11 | 선지급 "매입 전송 완료" 기준 존속 충돌(+D-ACQ-005 적용 범위) | DC-P-05, FIN (b)-1·(b)-2 | PC-02 | FC | 하(결정)/중(반영) | 정산 적격성 모델 | 문서 대조 |
| NP-12 | "전송 완료" 근거에 RENAMED 포함(I-9 Implementer 해석) | DC-P-06 | PC-67 | FC·RC | 하 | 확인 안 된 전송이 선지급 근거가 될 수 있음 | 근거 종류별 TRANSMITTED 비율, 정산 계약 사용처 |
| NP-13 | FINANCIAL 정합성 보류 5종·REVIEW_REQUIRED 해소 경로 없음 | DC-P-07, INV §5-4 #6, FIN (f) | PC-32 | RV·FC | 중 | 미매입 → 미입금 → 미지급 | 보류 건수 지표, WaitReason 로그 |
| NP-14 | 외화 Payment 생성은 되고 승인은 KRW만 → 만료로만 끝남 | PAY (b)-1·(e)-4 | PC-03 | FC | 하* | 승인 불가 결제 생성(USD 활성 시) | USD Snapshot: 생성 성공 → confirm 거절 → 만료 시험 |
| NP-15 | 취소 사건 hold 중 운영자 보정 취소 허용(D-CANCEL-006 문언보다 좁음) | CAN (b)-1·(e)-1 | PC-07 | RC·FC | 하* | 해제 선행 조건 의미 | `CancelStartSpec` FORCED + hold 시험과 결정 대조 |
| NP-16 | 정산 거래일 시각 원천 불일치(KPI confirmed_at vs 기관 거래일) + Q-CALENDAR | CAN (a)-10·(b)-2, FIN (b)-3 | PC-04·27 | FC | 중* | 자정 근처·늦은 확정 건 정산일 차이 | 자정 근처 fixture로 두 날짜 비교(정산 명세 시험) |
| NP-17 | 수수료·정산 정책 Assignment 수준(SCP CSA+Override vs MID별만) | FIN (b)-4·(c)-3·(e)-7 | PC-05 | FC | 중* | MASTER schema·해석 규칙 | 명세 리뷰 |
| NP-18 | 가맹점 Webhook 순서 보장(baseline 03 vs WH-11) | WH (b)-1 | PC-06 | —(외부 계약) | 하* | 가맹점 공개 계약 의미 | 계약 문서 대조 |
| NP-19 | GAVE_UP 뒤 늦은 2xx → DELIVERED(결정 없는 전이) | WH (e)-2 | PC-69 | OB | 하* | GAVE_UP 경보 소실 | `MerchantWebhookDeliverySpec` 기대값과 결정 대조 |
| NP-20 | FINANCIAL 사실 재시도 상한 없음·4xx 분류(I-2), 채널마다 다름 | WH (b)-2·(b)-3 | PC-68 | RV·OB | 하* | 사실 유실 방지 vs 적체 | `FinancialFactRelayDbSpec` FR-R2와 결정 대조 |
| NP-21 | 매입 효과를 만든 Coordinator/Implementer 결정(C-5 PAYMENT_HELD, I-5 KRW 외 대기, L-6 세대 취소 — 시험 기대값 CC-4 → CC-8) | FIN (a)-9·(e)-1·(e)-2·(e)-5 | PC-66 | FC | 하* | 돈 흐름 지연·취소 매입 판정 | 대장 BR-FIN 상태 + 시험 기대값 이력(git) 대조 |
| NP-22 | 새 가맹점 관문 "최신 접수 건"이 사용자 문구에 없음 | BIZ (b)-6·(f)-4 | PC-45 | —(상태 전이) | 하* | 해지 뒤 새 접수 중 등록 차단 여부 | FLOW-3 인수 시험 |
| NP-23 | 개인정보 노출 범위의 Coordinator 해석(VIEW_MASKED 4역할 BRN 원문 무감사, D-FLOW-009 KYC 비응답, ROLE_REACTIVATE 배제 확대) | ADM (a)-11·(b)-4·(f)-5, BIZ (f)-5 | PC-71 | 보안·AU | 하* | 개인정보·권한 범위 | 권한·감사 시험과 결정 대조 |
| NP-24 | 승인 대기 목록·임의 승인 상세를 조회4 역할 모두 API로 조회(화면은 APPROVER만), 결정 근거 미확인 | INV §5-5 #2·#3 | PC-46 | 보안 | 하* | 승인 대상 정보 노출 범위 | `ApprovalHttpSpec` VIEWER 호출 시험과 결정 대조 |
| NP-25 | 승인·거절·철회 응답 유실/5xx → "잠시 후 다시 시도"만, 재조회 없음(결과 불명 원칙 미적용), 시험 0 | UI-01 | — | ID·RV | 하 | 운영자가 실패로 오해하고 재승인 시도 | Vitest: `api.approve` reject → "결과 확인 필요" 문구 + 목록 재조회 1회 |
| NP-26 | 승인 상세가 `ctr_`·`csa_`·`site_`·`act_` 표시, 시험이 고정 | UI-02 | PC-08 | 보안 | 중 | 오승인 위험 | 승인 상세에 13접두어 `domOccurrences` |
| NP-27 | 가맹점 등록 화면 내부 키 표시 6곳·직접 입력 2칸(D-ADMIN-UI-005 미이행) | UI-03, INV §5-4 #12, BIZ (a)-4 | PC-14 | 보안 | 중~상 | 키 복사 입력 오류 | `grep site_\|ctr_\|csa_` 0(주석 제외), 등록 화면 13접두어 domScan |
| NP-28 | 영속 거절 enum 이름 고정이 주석뿐(`valueOf` 복원), MASTER `admin_request.result_code` CHECK 없음 | SC-01 | — | ID | 하 | 이름 변경 시 같은 키 재요청이 오류 — 멱등 붕괴(잠재) | 영속 enum 이름 전체 snapshot Spec(추가만 허용) |
| NP-29 | 대장 `제안` 119행이 사용자 미확인(일괄 확인 대상). **하위: 금융·보안 의미분 PC-66~71 P1, PC-72·73 P2, PC-74 P4 — 나머지는 일괄 확인** | 6 대장 (f), ADM (f)-8, WH (f)-4, PAY F-2 | PC-65·72·73·74 | —(관리체계) | 하* | 대장 확정·"확정 규칙만 착수" 관문 불가 | RULE-TRACE: 작업 카드가 확정 아닌 BR 참조 시 WAITING_DECISION |
| NP-30 | 운영 홈 KPI 금액 정의(통화·환경 분리, UNKNOWN 제외)를 "확인 대상 아님"으로 기록 | ADM (b)-5·(f)-7, CAN (f) Q-CAN-9 | PC-70 | FC | 하* | 금융 의미 정의가 Coordinator 기본값 | 결정 기록 후 `HomeKpiQueryServiceSpec` 기대값 대조 |

### 3.3 P2 — 도메인 경계·아키텍처 (36)

| ID | 문제 | 출처 | PC | 난이도 | 예상 영향 | 검증 방법 |
|---|---|---|---|---|---|---|
| NP-31 | D-MD-006 신규 결제 차단 기록 없음, Snapshot `blocked=false` 고정 | DC-M-05 | — | 상 | MASTER에 즉시 차단 수단 없음(개통 정지로 대신) | LIFECYCLE 인수: 차단 2건 → 1건 해제 → 계속 차단 |
| NP-32 | MASTER 생명주기 전이 전무, runtime INSERT 전용 | DC-M-06, INV §5-4 #10 | — | 상 | D-MD-004~007 미실행 | 전이별 Domain·동시성·권한(1142) 시험 |
| NP-33 | FLOW "계약 해지 → 개별 중지/철회" 안내가 가리키는 명령 없음 | DC-M-07 | —(NP-32 생명주기에 종속, 용어 혼동은 NP-69·PC-10) | 중 | 안내를 따라도 할 일이 개통 정지뿐 | 해지 시나리오에서 가능한 명령 목록 대조 |
| NP-34 | CSA는 Revision 승인 대상 아님 — 승인 뒤 1인 추가가 바로 MID 근거 | DC-M-08 | PC-34 | 중 | 서비스 종류 확장 시 승인 범위 확대 | 승인 뒤 CSA 추가 → 그 CSA로 MID 발급 성공 여부 |
| NP-35 | Revision 승인·CSA 추가가 당사자·대상 생명주기 미검사 | DC-M-09 | — | 하 | 종료 경로가 생기면 종료된 가맹점 승인 | TERMINATED 재구성 후 approve 거절 시험 |
| NP-36 | DefaultDesignation 소속 문서(Merchant)·코드(MID) 다름 | DC-M-10 | PC-17 | 하~중 | Default 교체 설계 혼동 | 설계 리뷰 |
| NP-37 | Merchant당 MID 1개 고정(문서 1:N), 추가 MID·Default 교체 PROPOSED | DC-M-11 | PC-16·30 | 상 | 여러 계약·기관 경로 표현 불가 | — |
| NP-38 | ADDITIONAL·Site 공유 5조건 없음, 다른 MID 간 같은 Site 규칙 없음 | DC-M-12 | PC-16·35 | 중 | 추가 MID 시 조건 없이 열림 | 추가 MID 작업의 동시성·조건 시험 |
| NP-39 | 종료 Site 채널 재사용(Q-MD-18) — DB 유일성이 ACTIVE만 | DC-M-13, BIZ (e)-3·(f)-1 | PC-15 | 하~중 | 도메인 이전·사칭 | Site 종료 구현 시 시험 |
| NP-40 | 소비자 없는 Outbox 범주 무기한 PENDING(MASTER `INTEGRATION_MASTER_REFERENCE`, PAYMENT RISK_INCIDENT·OPERATIONAL_RISK), 보존 규칙 없음 | DC-M-14, DC-P-11 | PC-37 | 중 / 하 | 표 무한 누적, 백로그 지표 오해. **해결 경로는 별개**: (a) MASTER REFERENCE — 소비자 없는 범주 기록 중단 또는 `NO_CONSUMER` 상태(DC-M-14, 기술 선택·Coordinator 가능) (b) PAYMENT RISK·OPERATIONAL_RISK — DC-P-02·RECON 소비자 지정과 함께(DC-P-11, PC-37·38 결정 뒤) | 범주별 PENDING 행 수·연령 지표 포함 여부(미확인) |
| NP-41 | master Modulith 모듈 1개, domain 88타입 한 패키지 | DC-M-15, DOMAIN-ASSESSMENT §4.3 | — | 중 | FLOW가 같은 모듈에 더 추가 | ArchUnit 하위 패키지 의존 규칙 |
| NP-42 | 수수료·VAT Snapshot이 버전 문자열뿐, MASTER→FINANCIAL 채널 없음, MASTER 정책 내용 UNDEFINED/DEMO | DC-P-09, FIN (e)-6 | PC-39 | 상 | 정산 착수 시 새 서비스 간 계약 | 설계 검토 |
| NP-43 | Settlement·Payout·Reconciliation 모듈·표 0(문서는 소비 TX에 정산 후보 생성 서술) | DC-P-10, INV HOME-04·§5-4 #8·#9 | — | 상 | 돈 흐름 후반부 부재 | 모듈 목록 |
| NP-44 | 조회 전용 값(HomeKpiFigures·Window)이 `payment.domain`에 있음 | DC-P-12 | — | 하 | domain 의미 희석(경미) | ArchUnit 규칙 검토 |
| NP-45 | simulator 공통 ArchUnit 18개 중 10개, Modulith 없음 | SC-02 | — | 하 | 기관 DTO 누출·순환 미검출 | `rules.all()` 기반 Spec 보강 후 통과 |
| NP-46 | internal-admin에 jOOQ 쓰기 금지 규칙 없음 | SC-03 | — | 하 | 조회 adapter의 감사·승인 표 쓰기 미검출 | 규칙 시험 1개 추가 |
| NP-47 | "외부 호출 TX 밖" 실행 시점 확인이 원격 adapter 12개 중 4개 | SC-04 | — | 중 | 새 경로가 TX 안에서 기관 호출 시 미보장 | 원격 Port 구현 진입부 TX 비활성 확인 + 기존 시험 |
| NP-48 | 카드번호 탐지 정규식 4벌, 공통 벡터 없음 | SC-05 | PC-13 | 하 | 한 벌만 고치면 카드번호 저장 경로 | `contracts/` 공통 벡터로 4벌 같은 판정 시험 |
| NP-49 | 관리자 감사 action 코드 자유 문자열 | SC-06 | — | 중 | 오타 action이 조용히 저장, 조회·경보 누락 | action 리터럴 집합 vs 닫힌 목록 시험 |
| NP-50 | admin application이 시계 직접 읽기 9파일 12곳 | SC-07 | — | 하 | 감사 시각·한도 경계 시험 불안정 | ArchUnit: application의 `Instant.now()` 금지 |
| NP-51 | admin 승인 서비스 4종 보조 함수 복제(`holdsNow` 3벌 등, 2,295줄) | SC-08 | — | 중 | 권한 재확인이 승인 종류별로 갈라질 위험 | 추출 전후 승인 Flow·Http·경쟁 Spec 무변경 통과 |
| NP-52 | `LocalTransactions` 11벌·`CommitOutcomeUnknownException` 8벌 | SC-09 | — | 중 | 모듈마다 Transaction Safety 차이 | 복제본 동일성 시험 또는 공통 계약 Spec |
| NP-53 | D-CPC-002 형식이 payment-api 안에 4벌 | SC-10 | — | 하 | web 400과 domain 거부 엇갈림 | 파서별 형식 시험에 domain `isValid` 대조 표 |
| NP-54 | 결제창 세션·토큰 상태 전이가 `application.required` Port 파일에 | SC-11 | — | 중 | 전이 접근 제한 미적용 | 이동 후 Checkout 시험 전부 통과 |
| NP-55 | 화면 금지 접두어 목록 4벌(DS 5·PII 7+·labels 11·시험 13) | UI-04 | PC-08 | 하 | 새 화면 누출 미검출 | 공용 상수 1개, 복사본 0 |
| NP-56 | `apr_` 4화면 5곳 노출, 가맹점 화면만 숨김 | UI-05 | PC-09 | 하 / 결정 필요 | 화면마다 다른 규칙 | `approvalId}` 렌더 위치 grep |
| NP-57 | shadcn·시안 class 공존(27파일, 혼용 16) | UI-06 | — | 상 | 버튼·입력·초점 링 불일치 | DS §7.3 B2 시험(shadcn import 0) |
| NP-58 | `workspace` boolean 분기 증가(조건 22·전달 29) | UI-07 | — | 중 | 같은 폼이 두 모양 | B2 "모양 분기 prop 0" 시험 |
| NP-59 | 구조 단언 증가(`toHaveClass` 224·`querySelector` 341 등) | UI-08 | — | 상 | 모양 정리 때 시험 수정량 과대 | 배치마다 시험 순증 ≤ main 순증 |
| NP-60 | Tailwind 반응형 접두사 35곳 → 캡처 12폭 유지 | UI-09 | — | 중 | 캡처 비용 2배 | 반응형 utility 0 → 6폭 |
| NP-61 | 로그인·세션 종료·로그아웃 화면 메모리 리셋 3벌 | UI-10 | — | 하 | [추정] 메모리 추가 시 이전 사용자 입력(원문 BRN 포함) 잔존 | Vitest: 재로그인 뒤 각 메모리 비었는지 |
| NP-62 | `adminApi.ts` 1,125줄·타입 80, 같은 `/justification` 메서드 3개 | UI-11, INV §5-3 #6 | — | 중 | 병렬 작업 충돌 | 파일별 줄 수, 같은 경로 메서드 1개 |
| NP-63 | 확정 규칙 시험 공백·BR-ID 연결 0: 정산·지급·대사 전부(FIN (d) 61행), 핵심 규칙 BR-PAY-022(실제 취소 뒤 재주문 차단)·BR-PAY-070(confirm 409 ON_HOLD/ALREADY_AUTHORIZED API), unmask 계열·`UnmaskFrameworkSpec` 이름 시험 없음, BOOT1 계획 시험 7개 이름 없음, WH endpoint 규칙 등 | PAY (d), CAN (d), FIN (d), WH (d), BIZ (d), ADM (d), INV §6-6, RULE-GOV P-5 | — | 상* | 규칙별 보증 여부를 알 수 없음 | RULE-TRACE 3·4단계(RULE-GOV §2.3) |
| NP-64 | 멱등 기록 30일 경과 삭제 Job 없음(조회 시 갱신만) | PAY (e)-3 | — | 하* | 기록 누적 | 행 수 추이, CPC §8 대조 |
| NP-65 | 내부 채널 격리·UNSUPPORTED 재처리 도구 없음(baseline 03 요구) | WH (c)-10·(f)-8 | PC-19 | 중* | 격리 이벤트 처리 불가 | 채널별 격리 행 처리 경로 점검 |
| NP-66 | 복구 작업 B 직후 SA 1명 → 역할 부여 교착 | ADM (f)-6 | PC-48 | 하* | 관리자 권한 부여 불능 | 복구 시나리오 시험 |

### 3.4 P3 — 관리자 기능·UI (24)

| ID | 문제 | 출처 | PC | 난이도 | 예상 영향 | 검증 방법 |
|---|---|---|---|---|---|---|
| NP-67 | MERCHANT-FLOW-001(계약 접수·관문·KYC·영업대행사·메모·리스크·담보) main 미구현, `wip/flow-1` 미병합 | DC-M-16, INV §5-4 #1 | PC-45 | 중 | 결정 뒤에도 접수 없이 가맹점 등록(권리 효과 없음) | FLOW-3 인수 시험(`MerchantRegistrationGate*Spec` 확장) |
| NP-68 | INACTIVE 전이 불명확, INACTIVE 행 수정이 `check()` 결함 | DC-M-17 | PC-36 | 하 | 운영자 혼동·오류 응답 | INACTIVE 재구성 → updateProfile 시험 |
| NP-69 | 업무 용어 혼재·용어집 없음('계약', 'Hold' 4계통, admin Reconciliation, 'Site/MID/Revision', CSA 3표기, '근거 참조') | DC-M-18, UI-20, DC-P-16, ADM (b)-6 | PC-10·49 | 하 / 결정 필요 | 운영 오해·교육 비용 | 용어집 기준 grep 0 |
| NP-70 | 개통 남은 배치(카드 Route B3-3, 재개 B3-4, 한도·수수료 B4, 안전 중지 B5) 화면·API 없음 | INV §5-4 #2~#5·MER-11·12, BIZ (d)·(e)-4 | — | 상* | 점검표 ⑥⑦ "준비 전" | 각 배치 인수 시험 |
| NP-71 | 운영 처리 화면 없음(hold 해제 요청, UNKNOWN 이관 처리, REVIEW_REQUIRED·격리 해소, 대사 해결) | DC-P-13, INV §5-4 #6·#8 | PC-32·38 | 중 | 사건을 보고도 처리 불가 | 화면 점검 |
| NP-72 | 마스킹 해제 백엔드 틀만(204), 화면·대상 없음 | INV §5-2 ACC-05·§5-4 #7, ADM (e)-3 | — | 중* | UNMASK 권한을 줘도 쓸 곳 없음 | KYC 원문 조회 인수 시험 |
| NP-73 | UNMASK 단독 계정에 감사 탭 노출 → 403 | INV §5-5 #1 | PC-22 | 하* | 유일 탭 403 | `navigation.test.ts:21` 기대값 대조 |
| NP-74 | 승인 상세 버튼이 결제·재매입·가맹점만, ADMIN 계정 대상 6종 없음 | INV §5-2·§6-10 | — | 중* | 계정 승인 상세 확인 불가 | 화면 점검 |
| NP-75 | 중복 기능: BRN 정확 조회 3화면 각각 구현, 결제 한 건 보기 2경로 연결 없음 | INV §5-3 #1·#2 | — | 하* | 문구·오류 규칙 3벌 | 공통 컴포넌트 사용처 수 |
| NP-76 | 계좌 환불 미지원 안내(D-CANCEL-007) 화면·API 문서에 없음 | CAN (d)·(e)-2·(f) Q-CAN-10 | PC-20 | 하* | 안내 누락 | 화면·API 문서 문자열 확인 |
| NP-77 | 운영 홈 '확인 필요'가 UNKNOWN·진행 중 취소·FORCED를 안 셈 | CAN (e)-3, INV §5-4 #11 | PC-21 | 중* | 결제 예외 비노출 | ADMIN-HOME-ATTENTION-001 인수 시험 |
| NP-78 | 큰 파일(MerchantsScreen 1,140 등, 상위 10파일 8,045줄) | UI-12 | — | 상 | 1,000줄 리뷰 | 줄 수 추이 |
| NP-79 | 제목 줄 3방식(MapNote 4 / PageHeader 3 / 직접 7) | UI-13 | — | 하 | 초점 이동 규칙 차이 | 제목 컴포넌트 1종 grep |
| NP-80 | 목록 조회 조건 3방식·건수 문구 4종 | UI-14 | — | 중 | 목록마다 조작법 재학습 | FilterPanel 사용 수, 건수 문구 grep |
| NP-81 | 상세 표시 2방식, 지역 Panel·DetailPanels 3벌 | UI-15 | — | 중 | 위치·복귀 방식 차이 | 공용 DetailPanels 사용처 수 |
| NP-82 | 입력 줄 3방식(Field 위 label vs FormRow 왼쪽), ADMIN-UI-001 §3.6 낡은 문구 | UI-16 | PC-59 | 중 | 같은 폼 안 이름 위치 다름 | Field 사용처 = DS 예외만 |
| NP-83 | 상세 한 줄 4방식, 승인 상세 `<ul><li>` 나열 | UI-17 | — | 중 | 승인자 상세 배치 차이 | DetailRow 외 표현 0 |
| NP-84 | 2인 요청 폼 6벌 복사(오류 표 4벌·근거 정규식 2벌·안내 11곳) | UI-18 | — | 중 | 근거 규칙 변경 시 6곳 | 공용 컴포넌트 1 + 정규식 1 |
| NP-85 | 결과 불명 잠금 표시 2모양, 1인 명령에 "새로 입력" 없음(의도 미기록) | UI-19 | — | 하 | 잠금 인지 차이 | 잠금 표시 컴포넌트 1종 |
| NP-86 | `labels.ts` 밖 code→한국어 표 약 24개, MASTER 거절 메시지 표 3곳(82개 중 51개만) | UI-21, SC-12 | — | 중 | 서버 enum 증가 미검출 | labels 밖 표 0 또는 대조 시험, enum 차집합 시험 |
| NP-87 | 감사 조회 조건이 action code·`adm_` 입력 | UI-22 | — | 하~중 | 코드 암기 필요 | 화면 문자열 `adm_` 0 |
| NP-88 | 상태 태그 렌더러 2개(StatusBadge·`.tag`) | UI-23 | — | 하 | 크기·모양 차이 | shadcn Badge import 0 |
| NP-89 | 금지 utility 453개(색 159·글자 207 등) | UI-24 | — | 상 | 토큰 변경이 일부 글자에 안 미침 | B5 금지 utility 시험 |
| NP-90 | 감사 표 접근 가능한 이름 없음 | UI-25 | — | 하 | 낭독기 구분 어려움 | `getByRole('table', { name })` |

### 3.5 P4 — 문서·명명·편의 (32)

| ID | 문제 | 출처 | PC | 난이도 | 예상 영향 | 검증 방법 |
|---|---|---|---|---|---|---|
| NP-91 | `CLAUDE.md:42`·BASELINE-INDEX "통화/체크/할부 채택 전" stale — **사용자 지침 문구** | PAY (c)-1·F-6, FIN (c)-2 | PC-56 | 하 | 착수 전 관문 오판 | 문서 대조(사용자 승인 후) |
| NP-92 | baseline 대체 표시 없음(사업자 정책서·지침서, 범위 권장안·초기-확정, 시도 §4, SCP 반환 비율, architecture 01·02·05 편차) | DC-M-21, BIZ (c)-3~5, PAY (c)-2~4, FIN (c)-1, DC-P-19·§6, ADM (b)-1 | PC-55·57·62 | 하 | 옛 문장이 권위로 인용 | BASELINE-INDEX 대체 표시 점검 |
| NP-93 | DOMAIN-DEFINITION.md 2026-10-20 이후 미갱신(Q-MD-08 미결 표기, 인증기관 미결, Attempt 수·AcquisitionSubmission·HOLD 표현, Agent/Seller 역할) | DC-M-19, PAY (c)-9, DC-P-15·17 | PC-61·25 | 하 | 색인 문서로 옛 규칙 인용 | 결정 ID 목록 대비 DD 등재 대조 |
| NP-94 | MASTER-DOMAIN-001 제목·§1·§2.2·§2.3·§3.3, MASTER-SNAPSHOT-001 §4.4 stale | DC-M-20, BIZ (c)-1·(c)-2·(c)-11 | PC-18·58 | 하 | 증가 지점 오구현 시 Snapshot 결함 재발 | 문서 대조 |
| NP-95 | MERCHANT-OPS-001·002·MERCHANT-BIZ-LOOKUP-001 stale, D-MOPS-010 ①/② 번호 반대 | BIZ (b)-2·(c)-6~9 | PC-59·63 | 하 | BR-ID 연결 혼동 | 문서·시험 주석 대조 |
| NP-96 | PII-MASKING-POLICY stale(§4.4·§3 #21·#22·§7.3·§8 Q-PII-6, §5.1 첫 적용, §5.3 IN_PROGRESS, KYC 행 없음) | ADM (c)-1·(c)-2·(c)-3·(c)-5, BIZ (c)-10 | PC-58·59 | 하 | 닫힌 질문을 미결로 오독 | 문서 대조 |
| NP-97 | ADMIN-HISTORY-KEYS-001 "마스킹 BRN", BOOT1 §0·§4.7.4·§4.9 stale, 시험 이름 "masked" | ADM (a)-1·(c)-4·(c)-6·(c)-7·(e)-5 | PC-54·26 | 하 | RULE-TRACE 검사에 걸림 | 시험 이름·본문 grep |
| NP-98 | 결제 명세·주석 stale(AUTH-001:246, AC1:314·§3 표·403행, CPC 10·14·43, `PaymentMethod.kt:3-5`, `CreatePaymentApiSpec.kt:260` 이름) | PAY (c)-5~8·(e)-1, DC-P-14 | PC-25·26·60 | 하 | 계약 독자 혼동 | 문서 대조 |
| NP-99 | 취소 명세 stale(CANCEL-CONTRACT-001 제목·§4.5·§4.11·§6·§9.1·§9.3, CANCEL-001 §5.2·§5.3·§4.1, 오류 code 이름 경로별 차이, TXN-OPS-001 §2) | CAN (c)-1~8 | PC-59 | 하* | 명세 독자 혼동 | 문서 대조 |
| NP-100 | 금융 명세 stale(ACQUIRING-001·FIN-DBACCOUNT-001 "DONE 아님", 역사 자료 인용, ACQ-ADMIN-001 §12, D-ACQ-003 환경 축) | FIN (a)-10·(c)-4~7 | PC-60·74 | 하* | 상태 오판 | 문서 대조 |
| NP-101 | WEBHOOK-001 stale(§0·§4.4 미결 표기, §4.2.1 createdAt·failedAt, §5.1·§5.3·§11 HELD_DECISION, §5.3 표 단위 UPDATE, §4.1 "항상", §1.2 소유) | WH (a)-3·(c)-1~8 | PC-58·59 | 하* | 폐기 설계 오독 | 문서 대조 |
| NP-102 | tasks.json과 실제 불일치(MERCHANT-BIZ-LOOKUP-001 BACKLOG, SCODE-1 위치, WEBHOOK-ENDPOINT-001 명세 없음), 기존 진단 수치 시점 | DC-M-22, BIZ (f)-11, DC-P-18, WH (f)-6 | PC-60·64 | 하 | 재개 판단 오류 | tasks.json 상태 점검 |
| NP-103 | questions.json에 Q-PII-3·5·7·8·9·10 없음 | DC-M-23, ADM (f)-9 | PC-47 | 하 | 재개 때 미결 누락 | questions.json ID 대조 |
| NP-104 | 문자열 well-formed·제어문자 검사 11파일 중복(master DisplayName·BusinessTextRule 포함) | DC-M-24, SC-19 | — | 하 | parser 400과 Domain 거절 어긋남 | 같은 벡터로 구현별 결과 비교 시험 |
| NP-105 | Site 채널 DB CHECK가 Domain보다 느슨(WEB `_`·연속 점) | DC-M-25 | — | 하 | 수동 SQL 우회 때만 | MasterSchemaSpec WEB `_` 삽입 시험 |
| NP-106 | D-ADMIN1-018·020 문언과 구현 범위(더 엄격) | ADM (b)-2·(b)-3·(f)-3·(f)-4 | PC-24 | 하* | 문언 불일치 | 대장 문언 확정 후 대조 |
| NP-107 | Webhook `maxResponseBytes` 미사용, TEST http/https 층별 기준 차이 | WH (e)-1·(e)-3 | PC-23 | 하* | 뜻 없는 설정값 | 설정 사용처 grep |
| NP-108 | 규칙 대장 경계 중복(BIZ §8·§9 ↔ PAY·FIN, WH-065·071~080 ↔ PAY·FIN, CAN·ADM 경계 표시), RULE-GOV §3 FIN 주요 결정 접두 누락 | BIZ (b)-7, WH (f)-10, FIN (c)-8, CAN·ADM 머리 경계 | — | 하* | 같은 규칙 이중 정본 | BR 행 근거 D-ID 교차 중복 검사 |
| NP-109 | required Port 101/216 §14 미정 범주(`…Port` 13·`…Gateway` 8 혼재) | SC-13 | — | 하 | 역할 탐색 어려움 | 범주별 이름 규칙(선택) |
| NP-110 | `…Reader` 구현 2개가 JPA(1개 문서 밖 예외) | SC-14 | — | 하 | 이름·기술 불일치 | — |
| NP-111 | required Port 구현 17개가 application 패키지 | SC-15 | — | 하 | 계층 탐색 혼란 | — |
| NP-112 | 파일 규칙 이탈 13파일(`EntitlementSnapshot.kt` 최상위 28타입 등) | SC-16 | — | 하 | 파일 찾기 어려움 | 파일명 ≠ 공개 타입 검사 스크립트 |
| NP-113 | provided `package-info` 5패키지 없음(financial acquiring, simulator 4) | SC-17 | — | 하 | 첫 모듈 간 사용 때 Modulith 실패(financial) | — |
| NP-114 | payment·financial command 서비스가 jOOQ View Reader 주입(규칙 앱마다 다름) | SC-18 | — | 하 | 새 서비스가 View로 판정 가능 | — |
| NP-115 | build-logic BFS 복제·시험 의존성 반복·jOOQ codegen 4벌, 형식 검사 도구 없음 | SC-20 | — | 하 | 빌드 변경 4~5곳 | `./gradlew check` 결과 동일 |
| NP-116 | 문서 정합성(settings.gradle.kts:36, CONVENTIONS §1·§2·§3·§23 C-2) | SC-21 | PC-26 | 하 | 틀린 전제로 시작 | 문서 대조 |
| NP-117 | admin provided 결과 6개가 `Map<String, Any?>` | SC-22 | — | 중 | 컴파일러가 필드 확인 못 함 | — |
| NP-118 | 지역 일반 이름(`Row`·`Panel` 등), 다중 export 파일, 한 글자 차이 파일명 | UI-26 | — | 하 | 검색·리뷰 혼동 | §6.2 이름 grep 0 |
| NP-119 | 낡은 주석·문서 수치(대비 주석 5, DS §2 수치, ADMIN-UI-001 §4 brand) | UI-27 | PC-26·59 | 하 | 낡은 근거로 덮어쓰기 반복 | 주석 grep `4.47` 0 |
| NP-120 | 화면 말투 혼재(해요체 15·합쇼체 466) | UI-28 | PC-49 | 하 | 같은 화면 어조 불일치 | 말투 grep |
| NP-121 | 쓰지 않는 코드(ErrorScreen — 보호 시험만 사용, export 약 50) | UI-29 | — | 하 | 지우면 §7.1 보호 대상 변경 — Coordinator 확인 필요 | main import 0 export 목록 |
| NP-122 | 시험 파일 이름이 작업 배치 ID(iaB1~B6·workspace, 같은 화면 최대 11파일) | UI-30 | — | 하 | 회귀 시 시험 찾기 어려움 | — |

### 3.6 사용자 결정 없이 진행 가능한 항목 (진단의 분류 인용)

- DC-M §6: DC-M-03·10·14(기술 선택)·15·19~25는 Coordinator가 처리 가능(가역·문서).
- UI §9-2: UI-01(문구는 Coordinator 확인), UI-04·10·25는 결정 없이 가능한 작은 작업.
- SC: SC-01(시험 추가만, DB CHECK는 별도 결정)·SC-03·SC-07·SC-08·SC-17은 기대값 무변경 시험·규칙 추가로 진행 가능. SC-02 Modulith 도입·SC-20 형식 도구는 의존성 추가라 별도 결정.
- 단, **§5 정비 단계 순서를 지키고**, 진단 완료와 사용자 결정 전에는 전면 리팩터링을 시작하지 않는다.

---

## 4. 기준선 문서 구성안 (6종)

원칙: **한 사실은 한 곳에**(HARNESS-GUIDE §12). 정본 하나를 정하고 나머지는 ID·링크로만 참조한다. baseline 원문은 보존하고 대체 표시만 붙인다(DC-M-21 대안 ①). 정본 위치는 제안이며 대안을 함께 적는다.

- **CLAUDE.md는 상위 지침**이다. 기준선 6종 어느 것도 CLAUDE.md 문장을 다시 쓰지 않고, 대장 행은 CLAUDE.md를 근거로 **인용만** 한다(재기술 금지). CLAUDE.md 문구를 바꾸는 것은 사용자 지침 변경이다(§6 U-12-A).
- **상태 값은 한 곳**: 기능·작업 상태는 `harness/tasks.json`(과 점검 스크립트)이 정본이다. 다른 기준선 문서는 상태를 따로 적지 않는다.

### 4.1 6종 구성표

| # | 기준선 | 정본(원본) 위치 제안 | 지금 흩어진 곳 | 정본으로 옮길 것 | 참조로 둘 것 | 갱신 책임·시점 | 대안 |
|---|---|---|---|---|---|---|---|
| 1 | 기능·메뉴 인벤토리 | 메뉴 정의는 코드 `apps/internal-admin/frontend/src/navigation.ts`가 정본. 인벤토리 문서는 **기능 ID ↔ API ↔ 권한 ↔ BR-ID 대응표만** 담는다(`docs/governance/ADMIN-FEATURE-INVENTORY.md`를 그 형태로 승격). 구현 상태는 적지 않고 tasks.json·점검 스크립트로 본다 | `navigation.ts`·`App.tsx`, MERCHANT-IA-001(화면 항목), MERCHANT-OPS-002 §9(배치), tasks.json(작업 상태), CURRENT-STATE §5(기능표, `378aff7`), `docs/merchant-onboarding/` | 기능 ID, API 대응, 권한 대응(§5-5), 근거 BR-ID | 구현 상태(완성/부분 등)는 tasks.json·점검 스크립트, 화면 항목 상세는 MERCHANT-IA-001, 배치 계획은 각 명세 | Coordinator — API·권한·BR 대응이 바뀌는 커밋에서 같은 커밋으로 | (b) 인벤토리를 tasks.json 필드로 생성(문서 없음) (c) 가맹점 API·배치 기능까지 포함한 전체 기능 인벤토리 새로 작성 — 지금 인벤토리는 관리자 화면만 다룬다(가맹점 API 기능 인벤토리는 없음) |
| 2 | 도메인 Glossary·책임 | `docs/repository/DOMAIN-DEFINITION.md`를 **용어·Aggregate 책임·소유 DB**만 담는 정본으로 축소·갱신(규칙 문장은 대장으로) | DD §5 용어사전(2026-10-20), DC-M §3·DC-P §4 Aggregate 표, baseline 도메인구조, MASTER-DOMAIN-001, ARCH-STRUCTURE-001-MAPPING, 화면 `labels.ts`(UI-20), 각 대장 범위 머리 | 정식 용어·허용 별칭·금지어(UI-20 제안 형식), Aggregate 루트·식별자·책임·상태 목록, 동명이의 구분(PC-10) | 불변식·규칙은 BR-ID 링크, 패키지 배치는 ARCH-STRUCTURE-001, 화면 문구는 `labels.ts`가 용어집을 대조 | Coordinator — 새 Aggregate·용어가 생기는 명세 승인 시. 정식 용어 선택은 사용자 확인(PC-49) | (b) 새 `docs/GLOSSARY.md` + DD 폐기 표시 (c) DD 머리에 "기준일 2026-10-20" 경고만(DC-M-19 ③) |
| 3 | 업무 정책 관리 기준 | **`docs/rules/<도메인>.md` 대장을 정본 후보**로(RULE-GOV-001 §2.1). `docs/DECISIONS.md`는 결정 이력, `harness/questions.json`은 질문 원문 | DECISIONS 행, questions.json, 명세 본문, baseline 정책서·SCP·초기-확정, PII-MASKING-POLICY, CLAUDE.md 핵심 규칙(상위 지침 — 대장은 인용만) | 현재 유효 규칙 1행 1문장(상태·출처·근거·적용 명세·검증), 대체 사슬, 도메인 경계(NP-108) | 결정 전문은 DECISIONS·questions(HARNESS-GUIDE §12), 명세는 BR-ID로 규칙 참조, baseline은 원문 보존 + 대체 표시 | Coordinator — RULE-GOV §2.4대로 **대장·정책서·DECISIONS를 같은 커밋**에서. 대장 확정(제안 → 확정)은 사용자 답 뒤 | 아래 §4.2 |
| 4 | 아키텍처·코딩 컨벤션 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§14 명명) + `ARCH-STRUCTURE-001-MAPPING.md`(패키지 배치). 강제 정본은 `PayCoreArchitectureRules`·각 앱 ArchitectureSpec | TECHNOLOGY-AND-PRINCIPLES(8대 원칙·기술), CLAUDE-ARCHITECTURE-REFACTORING-GUIDE, TECH-BASELINE, ADR-001, CLAUDE.md 핵심 규칙(TX·소유권), SC §2.1 규칙 출처 | §14 미정 범주(외부·모듈 간 호출·설정·지표·식별자·시계 — SC-13), 원격 Port TX 비활성 확인 관례(SC-04), command 서비스 Reader 사용 관례(SC-18), 복제 허용 목록과 동일성 시험 기준(SC-05·09·10) | 원칙·기술 선택은 TECHNOLOGY-AND-PRINCIPLES, 리팩터링 절차는 REFACTORING-GUIDE | Coordinator — 관례 결정 시(가역). 규칙 추가는 ArchitectureSpec과 같은 커밋 | (b) CONVENTIONS에 강제 상태 열(ArchUnit/리뷰/없음) 추가 — SC §2.2·§2.3 표를 옮김 |
| 5 | UI 디자인 시스템 | `docs/repository/ADMIN-DS-001-DESIGN-SYSTEM.md` | ADMIN-UI-001-SHADCN(§3.6 "label 위"·§4 brand 등 낡은 부분), MERCHANT-IA-001, D-ADMIN-UI-* 결정, PII #25 접두어 목록, `labels.ts`, 시험 `PREFIXES` 2벌(UI-04) | 컴포넌트 카탈로그 정본, 입력 줄 규칙(D-ADMIN-UI-005 ④). 화면 금지 접두어는 **코드 공용 상수 하나가 정본**(UI-04 개선 방향 — 시험·화면이 import)이고 DS·PII #25는 그 상수를 참조만 한다 | 개인정보 표시 규칙은 PII/ADM 대장, 화면 항목은 MERCHANT-IA-001, 업무 의미가 있는 D-ADMIN-UI 부분은 ADM·BIZ 대장 | Coordinator — DS 배치(B2~B5) 커밋마다. 업무 의미 변경은 RULE-GOV §2.4 경로 | (b) ADMIN-UI-001을 DS에 흡수하고 "대체됨" 표시 |
| 6 | 기능 개발·완료 기준 | `docs/HARNESS-GUIDE.md`(상태·게이트·기록) + RULE-GOV-001 §2.2 착수 전 관문·§2.3 RULE-TRACE | CLAUDE.md(하네스·검증과 안전 — 상위 지침, 인용만), HARNESS-VERIFICATION-CONTRACT, PRINCIPLES-VERIFICATION-MAP, TEST-PERF-001, `harness/commands.json` | 착수 전 관문 점검표(작업 카드 필드), RULE-TRACE 4단계, "확정 규칙만 착수" 조건 | 8대 원칙 검증 지도는 PRINCIPLES-VERIFICATION-MAP, 명령 정의는 commands.json | Coordinator 제안 → **CLAUDE.md 변경은 사용자 승인**, HARNESS-GUIDE·RULE-GOV는 관문 강제 시점 사용자 확인 후(RULE-GOV 머리) | (b) RULE-GOV-001 내용을 HARNESS-GUIDE §13으로 합침 |

### 4.2 `docs/rules` 대장의 위치 — 업무 정책 관리 기준의 정본 후보

| 안 | 내용 | 장점 | 주의 |
|---|---|---|---|
| ① (RULE-GOV-001 설계) | `docs/rules/<도메인>.md`가 현재 유효 규칙 정본. DECISIONS는 이력, 명세는 BR-ID 참조 | 규칙 1곳·RULE-TRACE 검사 가능 | 790행 유지 비용, 대장 간 경계(NP-108) 먼저 정리 필요 |
| ② | DECISIONS에 `SUPERSEDED BY` 열 + "현재 유효 규칙 색인" 1장(RECOVERY-ROADMAP 1-4), 대장 없음 | 새 문서 없음 | 규칙-시험 연결(P-5)을 표현할 곳이 없다 |
| ③ | 명세가 각자 정본, `docs/rules`는 색인(ID·명세 §·시험)만 | 명세 작업 흐름 유지 | 명세 본문 stale 패턴(PC-59)이 그대로 남는다 |

- 권장안 — 미결정: 이 문서는 정하지 않는다. RULE-GOV-001 rev 0.1은 ①로 설계돼 있고 "관문 강제 시점은 사용자 확인 후"다(§6 U-12-B).

### 4.3 중복 제거 대상

| 대상 | 겹치는 곳 | 제안(대안) |
|---|---|---|
| DOMAIN-DEFINITION.md stale | 대장·DC-M/DC-P §3·§4, MASTER-DOMAIN-001 | §4.1 #2 — 용어·책임만 남기고 규칙 문장은 BR-ID 링크 / DD 폐기 표시 |
| PII-MASKING-POLICY ↔ ADM 대장 | 개인정보 등급·화면 표시·마스킹 해제 규칙이 두 곳(ADM (c)-1~5, 대장 BR-ADM-094~117) | (a) PII = 정보 등급 표·화면 표시 상세(정본), ADM 대장 행은 PII § 링크 (b) PII 규칙 문장을 ADM 대장으로 옮기고 PII는 등급 표만 (c) 현행 + 상호 링크. 어느 쪽이든 §8 질문 목록은 questions.json으로(NP-103) |
| 명세 본문 stale 패턴 | WEBHOOK-001·CANCEL-CONTRACT-001·CANCEL-001·MASTER-DOMAIN-001·MERCHANT-OPS-001/002·HK1·BOOT1·ACQUIRING-001(PC-58·59·60) | 명세 본문 = 현재 규칙만(HARNESS-GUIDE §12 이미 규정), 개정 이력은 파일 끝. 규칙 문장은 BR-ID 참조로 대체 |
| baseline 정책서·범위 문서의 대체된 문장 | 사업자 정책서·지침서, 범위 권장안, 초기-확정, 시도 §4, SCP 연결 B | 원문 보존 + BASELINE-INDEX "부분 대체" 표(DC-M-21 ①) / baseline 말미 주석(②) |
| 결정 원장 옛 행 | D-BIZ-006 ②·D-MOPS-003·D-BIZ-003·D-MCODE-003 ②·D-WH-009·D-ADMIN1-002 등 | 역참조 표시(내용 무변경) — Coordinator 처리 가능(DC-M §5.2) |
| 화면 금지 접두어 목록 4벌 | DS §3.2·PII #25·`merchantLabels.ts:6`·시험 `PREFIXES` 2벌 | 코드 공용 상수 하나가 정본, DS·PII #25는 참조(§4.1 #5와 같음) |
| 대장 간 같은 규칙 | BIZ §8·§9 ↔ PAY·FIN, WH-065·071~080 ↔ PAY·FIN, 취소 수수료 ↔ FIN | 규칙의 "판정 소유자" 도메인 대장 하나가 정본, 다른 대장은 BR-ID 참조(BIZ (b)-7 제안) |
| 기존 진단 문서(assessment) | 이번 진단과 같은 항목(DOMAIN §5·§4.3, COMPLEXITY §3.2, FRONTEND §4~§7) | 진단 문서는 고쳐 쓰지 않는다. 이번 진단이 확인·정정한 사실(SC §6, UI §2, DC-M §2, DC-P §2)을 대응표로 참조 |

---

## 5. 정비 단계 제안

RULE-GOV-001 §4 진행 순서와 RECOVERY-ROADMAP 단계를 잇는다. RECOVERY-ROADMAP의 1~5단계(프로세스·금융 안전·중복·화면)는 다시 쓰지 않고 S5에서 순서만 정한다. 일부는 이미 해소됐다(UI §2: 결과 불명 잠금 통합 LOCK-UNIFY-001, ONESHOT-LOCK-001).

| 단계 | 내용 | 산출물 | 완료 기준 |
|---|---|---|---|
| **S1 진단** (현재) | 진단 5종 + 대장 초안 6종 + 종합 3종 | `docs/governance/*`, `docs/rules/*` 초안 | 이 3문서 Reviewer 확인, 사용자에게 §6 결정 묶음 제시. 진단 기준 커밋(`41139db`) 이후 코드 변경이 있으면 해당 진단 재확인 |
| **S2 결정** | §6 결정 묶음에 사용자 답. 새 후보 질문(PC 가칭)과 누락 질문(Q-PII-3·5·7~10)을 questions.json에 등록 | questions.json(답변 원문·시각), DECISIONS 행 | §6 U-01~U-12 각각 ANSWERED 또는 "보류(이유·재질문 시점)" 기록. 답 없는 항목에 연결된 작업은 WAITING_DECISION |
| **S3 문서 정합** | 대장 제안 → 확정, 결정 원장 역참조, baseline 대체 표시, 명세 stale 정리(NP-91~103), 대장 경계 정리(NP-108), 기준선 6종 정본 지정(§4) | 확정 대장, 갱신된 DECISIONS·BASELINE-INDEX·명세, (사용자 승인 시) CLAUDE.md 문구 | 대장에 `제안` 0(또는 보류 사유 기록), 대체된 결정 행마다 역참조, 답변된 질문을 "미결"로 적은 본문 0, 정본 6종 위치가 BASELINE-INDEX에 표시 |
| **S4 관문·추적 게이트** | 착수 전 관문 점검표를 작업 카드 필드로, RULE-TRACE 1~4단계 구현, 시험에 BR-ID 연결(도메인별 점진) | 하네스 검사 스크립트·commands.json 항목, 작업 카드 필드 | RULE-TRACE가 형식 오류·확정 아닌 BR 참조·시험 없는 확정 규칙·대체 규칙 시험을 보고한다. 시험 기대값·게이트 기준은 바꾸지 않는다(CLAUDE.md) |
| **S5 코드 정비** | P0 → P1 → P2 순. P0(NP-01~04)는 결정된 것부터(NP-04 결정 완료·시점만, NP-01은 D-MOPS-010 ② 범위(사업자 등록 지문의 번호)는 결정 완료·다른 명령 지문의 개인정보 필드는 U-01 ② 답 뒤). P1 중 정책 답이 난 것. 그 뒤 RECOVERY-ROADMAP 3~5단계(중복 제거·화면 통합·분할)를 SC·UI 항목 순서로 | 작업 카드별 명세·시험·게이트 증거 | 각 카드 FULL PASS + Reviewer. 금융 시험(동시성·golden·rollback·FaultInjection) 무변경 통과(RECOVERY-ROADMAP 기준) |

- 전면 리팩터링(구조·패키지 대이동, 화면 체계 전환 B2 이후)은 **S3 완료 전에는 시작하지 않는다.** S1~S2 사이에 할 수 있는 것은 §3.6의 결정 불필요 항목 중 기대값을 바꾸지 않는 시험 추가·문서 표시뿐이다.
- 진행 중 작업과의 관계
  - MERCHANT-FLOW-001: tasks.json `nextResumeAction`은 "main 병합은 GOV-DIAG-001 완료 + BIZ 진단에 FLOW-1 관련 충돌 없음 확인 후"다. 이번 종합에서 FLOW와 관련된 항목은 PC-45(최신 접수 건 — Coordinator 노트상 FLOW-1 schema 영향은 생성순번 정도, BR-BIZ-280), PC-13(사유 카드번호 검사 — FLOW-2 예정), PC-71(D-FLOW-009 적용 메모, BR-BIZ-301), PC-10(계약 용어 — FLOW-2 화면), NP-41(FLOW가 master 단일 모듈에 추가)이다. 병합 판단은 Coordinator가 이 목록으로 한다(이 문서는 판정하지 않는다).
  - RULE-GOV-001: "관문·추적 게이트 구현은 정상화 계획 승인 후" — S4가 그 단계다.

---

## 6. 사용자가 우선 결정할 사항 (12개, 우선순위 순)

- 금융·보안을 먼저 두었다. 권장안은 모두 원 진단 인용이며 **권장안 — 미결정**이다.
- **확정된 정책은 다시 묻지 않는다.** 각 행의 "이미 확정" 칸에 근거를 먼저 적고, 남은 질문만 묻는다. 확정 규칙을 바꾸는 대안에는 "○○ 변경안"이라고 표시했다.
- LIVE 준비 때 물을 값(Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF·운영 수치·약관 — PC-31·51)은 지금 묶음에서 뺐다.

| # | 이미 확정(다시 묻지 않음) | 질문 | 권장안 — 미결정 | 대안 | 영향 | 막히는 작업 | 관련 |
|---|---|---|---|---|---|---|---|
| U-01 | D-MOPS-010 ②: 사업자 등록 요청 지문의 사업자등록번호 → MASTER 비밀 키 HMAC. D-FLOW-005 ⑧·D-FLOW-007 ①: 두 사유 칸 카드번호 거부 | **(보안)** ① 이 두 결정을 언제 적용할까? ② 다른 관리 명령 지문에 들어간 대표자/담당자 이름·전화·이메일도 HMAC으로 바꾸거나 지문에서 뺄까(D-MOPS-010 ② 문언 밖 — **범위 확장·설계 변경**) | ①: DC-M §6 HMAC "결정 그대로, FLOW 구현 전 또는 LIVE 전". 카드번호 검사는 진단에 권장 시점 없음. ②: DC-M-01 대안 ①(전환) 또는 ②(개인정보 필드 제외 + 별도 HMAC 필드) — 진단은 고르지 않음 | ①: 각자 예정 배치(B3-3a·FLOW-2) / S5 첫 작업으로 선행. ②: 현행(번호만 결정 범위) | 개인정보 대입 확인·카드번호 저장 위험(Demo) | MERCHANT-OPS-002 B3-3a, FLOW-2, LIVE Gate | NP-01·04, PC-12·13 |
| U-02 | D-CANCEL1-003·BR-CAN-030·031·BR-FIN-125: 이관된 **취소** UNKNOWN은 임시 출구 없음, RECON 전 LIVE 금지. D-AUTH-009: 이관 뒤 **승인** UNKNOWN 유지. D-AUTH-002: hold 해제 = 2인 + 재대사 MATCH. CLAUDE.md: "보정 후 MATCH 확인 전 해결 금지" | **(금융)** ① 같은 "RECON 전 LIVE 금지" 조건을 승인 UNKNOWN 이관·IncidentHold·FORCED(늦은 취소 성공)·매입 UNKNOWN/RECONCILE에도 LIVE Gate 조건으로 명시할까? ② RECON-001 범위에 매입 대사(DC-P-08)를 넣을까? | ①: Q-DC-P-04 "예 — LIVE Gate 체크리스트 항목화". ②: 진단에 권장 없음(DC-P-08 "RECON-001 범위에 매입 대사 포함 여부 결정") | ①: 취소 UNKNOWN만 조건으로 두고 나머지는 LIVE 준비 때 개별 판단. ②: 매입 대사를 별도 작업으로. "임시 운영자 확인 절차" 안은 **D-CANCEL1-003·D-AUTH-002·CLAUDE.md 변경안**이라 대안으로 제시하지 않는다 | 주문 영구 차단·한도 점유·영구 미매입이 LIVE에서 생기는지 | LIVE 준비, RECON-CONTRACT-001·RECON-001, Q-CORRECTION | NP-02·08·09·10, PC-28·38 |
| U-03 | D-ACQ-002 "원거래 모르는 취소는 Hold", D-FFR-003 ORIGINAL_UNKNOWN 자동 해제 | **(금융)** ① PAYMENT 사건 hold·RISK_INCIDENT를 FINANCIAL 매입·정산에 어떻게 반영할까? ② 정합성 보류 거래를 정산에 넣을까? ③ **이미 질문됨(PENDING) — 재제시**: Q-ACQA-05·06·07·10(전송 격리 해소·격리 응답 재반영·REVIEW_REQUIRED 해소·전송 보류 해소의 금융 의미) | ①: Q-DC-P-01 "(a) RISK_INCIDENT 전달 → FINANCIAL 결제 보류로 투영". ②: RG-07 "해제 전 정산 Entry 대기". ③: 각 질문 기록의 권장안 | ① (b) 직전 PAYMENT 조회 (c) 정산에서만 차단. ② 정산 포함 + PayoutHold | 사건 결제 SALE 매입·정상 정산, 보류 결제 영구 미매입 | 매입 파일 구성, ACQ-ADMIN-002, SETTLEMENT-CONTRACT-001 | NP-03·13, PC-32·37·42 |
| U-04 | 초기-확정사항·CLAUDE.md:41 "승인 완료/매입 전송 완료/매입 확정 선지급 기준 유지". 정산지급방식 "매입 요청 = 외부기관 전송 완료" | **(금융)** ① D-ACQ-005("승인시점과 매입결과 선택")를 3기준과 어떻게 읽을까, 선지급에만 적용될까? ② 전송 완료 근거에 RENAMED(재확인 실패)를 인정할까? ③ 선지급 뒤 거절·UNKNOWN·후발 취소·미입금 시 보류·회수 규칙은? | ①: Q-DC-P-02·RG-03 "3종 유지", RG-04 "선지급에만 적용". ②: Q-DC-P-03 "(c) 전송 축 유지 + 정산 적격성은 재확인 근거만". ③: RG-01 "자동 회수 없음 + Payable Hold + 대사 Case, 회수·상계는 계약 근거·2인" | ① 2종(승인 완료·매입 확정)으로 축소 — **채택 시 CLAUDE.md:41 변경(사용자 지침, U-12-A와 함께)**. ② RENAMED 인정. ③ 다음 정산 자동 차감 | 정산 적격성·선지급 모델 | SETTLEMENT-CONTRACT-001 | NP-11·12, PC-02·40·67 |
| U-05 | 정산 거래일 = 금융 사실 실제 발생일(승인일·취소일·Refund 완료일), Refund 완료일 = 확정 외부 반환 사실의 업무 시각(BR-FIN-077·079 확정, CLAUDE.md:40, DC-P §6 "확정 현행") | **(금융)** ① 운영 홈 KPI 결정 문언 D-ADMIN-HOME-001 ② "정산일 규칙과 같은 완료일 기준"과 ③ PG 확정 시각(confirmed_at)이 다르게 말하는 것을 어떻게 정리할까? ② Q-CALENDAR(D+N·마감·시간대·휴일) | ①: Q-CAN-2·RG-05 "정산 = 기관 거래일, KPI = PG 확정 시각 유지(② 문언 정리)". ②: 질문 기록 권장안 | ① KPI도 기관 거래일로. 정산을 PG 확정 시각으로 보는 안은 **BR-FIN-077·079 해석 변경안** | KPI·정산 날짜 정합 | SETTLEMENT-CONTRACT-001, 운영 홈 KPI | NP-16, PC-04·27 |
| U-06 | D-AUTH-001·D-AUTH1-002: 승인은 KRW만 | **(금융)** 생성 단계 외화 허용(D-CP-002)과 승인 KRW 한정의 불일치를 어떻게 할까(Q-SETTLE-CURRENCY 연결) | PAY F-1 "정산 통화 결정 전까지 기준정보에서 외화 비활성" | 현행 유지 / 생성 단계 KRW 외 거절 | 승인 불가 결제 생성 | 외화 Demo, SETTLEMENT-CONTRACT-001 | NP-14, PC-03 |
| U-07 | D-MD-001(CSA = 정책 Assignment 기준), D-MD-018·D-MS2-001(한도 Override 우선) | **(금융)** 한 가맹점의 같은 종류 유효 계약 복수 허용? 수수료·정산 정책 Assignment 수준? 수수료 Snapshot 채널? CSA 추가 승인 수준? | DC-M §6 "기간 겹치는 유효 계약 금지(갱신은 Revision)", RG-06 "D-MS2-001 준용", Q-DC-P-05 "(a) MASTER→FINANCIAL 정책 Snapshot Outbox", DC-M §6 "CSA는 확장 전까지 현행" | 허용 + 근거 CSA 하나 명시 / MID별만 / PAYMENT 사본·API 조회 / CSA 2인 | 수수료·정산 근거 모호성 | SETTLEMENT-CONTRACT-001·001, Q-MD-06 | NP-07·17·34·42, PC-05·33·34·39 |
| U-08 | D-PII-001 ①(관리자 화면 BRN 원문), PII #25·D-ADMIN-UI-010 ③(내부 키 화면 미표시) | **(보안·개인정보)** 승인 상세 대상 표시(내부 키 vs 사람용 값)? `apr_`는 외부 식별자인가? VIEW_MASKED 4역할의 BRN 원문 무감사 해석을 받아들일까? 종료 대표자 KYC 비응답(D-FLOW-009 적용)? 승인 목록·상세 API 조회 범위? | UI §9-1 "[추정] 사람용 값 + 접힌 기술 정보, `apr_`는 PII #25 예외 명시". 나머지는 진단에 권장 없음(Coordinator 해석 확인 요청) | 현행 유지 + 예외 명시 / 역할·감사 조정 | 오승인·개인정보 노출 범위 | 승인 상세 정리, ADM·BIZ 대장 확정, FLOW | NP-23·24·26·56, PC-08·09·46·71 |
| U-09 | D-MS-005(재신청은 새 Site), D-MD-008(활성 채널 유일, 예외는 승인 기록), D-MD-004~007(생명주기) | **(상태 전이)** 거절 Site는 같은 채널로 어떻게 재신청할까? 종료 채널 재사용(Q-MD-18)? 추가 MID·Default 교체(Q-MD-06·07)? 한 Site 복수 MID 조건? MASTER-LIFECYCLE-001 착수 순서? 사업자 사용중지? | DC-M §6: "거절 시 채널 점유 해제 또는 Site 종료 선행", Q-MD-18 "예외 승인 없으면 거절", "같은 Merchant + 별도 계약/기관 경로 증거 + 2인", "차단 기록 → Site 종료 → 연결 종료/교체 → 철회", 사용중지는 "별도 작업, 그 전 INACTIVE 수정은 업무 거절" | PC-01 ①~④ 등 각 행 대안(④는 **D-MS-005 문구 축소안**) | 재심사 불가·사칭·확장 불가 | MASTER-LIFECYCLE-001, Site 심사 운영 | NP-05·31·32·37·38·39·68, PC-01·15·16·30·35·36 |
| U-10 | D-CANCEL-006(hold 해제 전 추가 취소 차단), D-WH-006(유한 재시도·GAVE_UP은 완료 아님·경보) | **(상태·계약)** hold 중 운영자 보정 취소를 허용하는 해석을 받아들일까? Webhook 순서 비보장을 확정할까? GAVE_UP 뒤 늦은 2xx는? FINANCIAL 사실 재시도 상한·4xx 분류는? | Q-CAN-1 "가맹점만 차단, 운영자 보정 허용"(**D-CANCEL-006 문언 축소(해석)안 — 채택 시 결정 행 문언 보정**), WH-Q1 "순서 비보장(현 구현)". WH-Q2·Q3은 진단에 권장 없음 | 운영자 보정도 차단 / Payment 단위 순서 보장(**baseline 03 문언대로**) / GAVE_UP 유지 / 상한 + 격리 | 해제 경로·가맹점 계약·경보·사실 유실 | 대장 확정, 가맹점 계약 문서 | NP-15·18·19·20, PC-06·07·68·69 |
| U-11 | RULE-GOV-001 §2.1: Coordinator 적용 규칙은 사용자 확인 전 `제안` | **(규칙 확정)** 사용자 결정 없이 정한 업무 동작 119행을 일괄 확인할까? 개별 확인 권장분: 매입 효과(C-5·I-5·L-6, 시험 기대값 CC-4→CC-8), 운영 홈 KPI 정의, "최신 접수 건" 관문, "첫 계약 승인" 정의, 취소 운영 해석(Q-CAN-3·5~8) | 각 대장 권장: "일괄 확인(fail-closed 유지), 예외만 개별"(FIN RG-08·ADM f-8·WH f-4·PAY F-2), Q-CAN-9 "KPI 현행 유지, 사용자 확인" | 도메인별 나눠 확인 / 행별 재검토 | 대장 확정 → RULE-TRACE 관문 가능 | S3 대장 확정, FLOW-3, 각 도메인 후속 착수 | NP-21·22·29·30, PC-45·65·66·70·72·73·74 |
| U-12 | — | **U-12-A (사용자 지침 변경)**: `CLAUDE.md:42` "실제 통화/체크카드/할부·인증 범위 추가안은 사용자 채택 전" 문구를 D-AUTH-001·D-AUTH1-003에 맞게 고칠까? (U-04 ①에서 2종 축소를 고르면 `CLAUDE.md:41`도 함께) **U-12-B (관리체계 선택)**: ② `docs/rules` 대장을 업무 규칙 정본으로 지정할까(§4.2 ①~③) ③ 착수 전 관문·RULE-TRACE를 언제부터 강제할까 ④ baseline은 원문 보존 + 대체 표시로 할까 | A: PAY F-6 "현재 결정에 맞춤(CLAUDE.md는 사용자 확인)". B: RULE-GOV-001 rev 0.1 설계(② ① 대장 정본, ③ 사용자 확인 후 강제), DC-M-21 ① "원문 보존 + BASELINE-INDEX 부분 대체 표" | A: 현행 문구 유지. B: §4.2 ②·③ / 강제 없이 권고만 / baseline 말미 주석 | 착수 전 관문 정확도, 정본 위치 | S3 문서 정합, S4 관문 | NP-91·92·108, PC-55·56 |

### 6.1 12개 밖으로 둔 것

- 해당 작업 착수 전 관문에서 묻는다(HARNESS-GUIDE §4-6 "필요한 시점에 묶어"): 정식 용어·말투와 동명이의 정리(PC-10·49), Webhook endpoint·수동 재전송(PC-50), 범위 권장안 §4·§5 채택(PC-43), 권리 변화 중 결제 사후 처리·지급계좌 효과(PC-44), TEST 매입 정산(PC-41), 개인정보 미등록 질문 묶음(PC-47), 복구 B SA 교착(PC-48), 가맹점 코드·Site 코드 방침(PC-11), 정산·대사 Job 식별 Q-BATCH-JOBPARAM(PC-29 — 기술 식별).
- LIVE Gate: PC-31·51, Q-PII-5 보관·파기, 데모 간편 로그인 제거(ADM f-13), jOOQ DEBUG 로그(ADM f-12).

---

## 7. 개정 이력

| rev | 날짜 | 내용 |
|---|---|---|
| 0.1 | 2026-11-25 | 최초 작성(GOV-DIAG-001 종합 — 진단 5종·대장 6종 진단 절·RULE-GOV-001 통합, 기준 `41139db`) |
| 0.1a | 2026-11-25 | 독립 리뷰 반영(MAJOR 4·MINOR 8): §6 결정 묶음에 "이미 확정" 칸 추가·확정 정책 재질문 제거(U-02·U-05), 확정 규칙 변경 대안 표시(U-02·U-04·U-05·U-09·U-10), U-03에 PENDING Q-ACQA 재제시, U-11에 PC-45, U-12를 A(사용자 지침)/B(관리체계)로 분리, §6.1에 PC-10·29. §4 중복 제거 정리(인벤토리 = 대응표만, 접두어 정본 = 코드 상수, CLAUDE.md 인용만). P0·P1 표에 8대 원칙 열, NP-04 근거 보강, NP-06·29 하위 우선순위, NP-40 해결 경로 분리, NP-08·09 확정 규칙 표시, NP-33 연결 정정, BOM 점검 범위 밖 명시 |
