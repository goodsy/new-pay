# FIN — 매입·정산·지급·대사 업무 규칙 대장 (초안)

- 상태: RULE-GOV-001 2단계 초안(2026-11-25). 사용자 확인 전. 형식은 `docs/rules/README.md`.
- 범위: 결제 사실 전달(Fact Relay), 결제 사실 정합성 보류, 매입(Acquiring)·전송 후 취소·재매입, 매입 조회·노출, FINANCIAL 채널·DB, 수수료/VAT Snapshot, 정책 기준정보·Assignment, 정산 날짜·Calendar, 지급·선지급, Settlement/Payout Hold, 대사·자동 보정·Adjustment, FIN 명령의 2인 승인·감사.
- 기준 문서 지위(BASELINE-INDEX): `PayCore-Settlement-Control-Policy-v0.1.md`(이하 SCP)·`PayCore-초기-확정사항.md`·`PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md`(이하 도메인구조)·`PayCore-결제허용-기관연동-권장안.md`는 채택 문서(출처 `기준`). `PayCore-정산지급방식-확정과-상세권장안.md`의 "상세 권장안"과 `PayCore-금융통제-관계-자동대사-대안.md`는 역사 자료(채택 부분만 초기-확정사항 경유로 인용). SCP 본문 중 "후보·권장"으로만 적힌 항목은 `제안`.
- 명세 안 Implementer 해석(I-n)·Coordinator 결정(C-n)은 사용자 결정이 아니므로 출처 `적용`, 상태 `제안`.
- 구현 현황: FINANCIAL 코드(`apps/financial-batch`)는 `paymentfact`·`acquiring` 모듈뿐이다. 정산·수수료 계산·지급·선지급·Hold·대사(RECON)는 코드가 없다(SETTLEMENT-CONTRACT-001·SETTLEMENT-001·RECON-CONTRACT-001·RECON-001·MASTER-PAYOUT-ACCOUNT-001 BACKLOG). 확정 결정이 있으면 규칙으로 넣고 검증은 `없음`.
- 다른 도메인과 겹치는 규칙(incident hold·취소 수수료 반환·정책 배정 2인 등)은 FIN 관점만 적는다.

## 1. 결제 사실 전달 (PAYMENT → FINANCIAL)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-001 | PAYMENT의 승인·취소 사실은 PAYMENT Outbox → 서명된 내부 HTTP push → FINANCIAL Inbox로만 전달하고 PAYMENT·FINANCIAL 사이 직접 SQL·JOIN은 쓰지 않는다. | 확정 | 사용자 | D-ACQ-001, architecture/02 금지사항, architecture/10 INV-02 | docs/repository/FIN-FACT-RELAY-001-PAYMENT-FACT-RELAY.md §1·§4.1 | FinancialFactRelayDbSpec::FR-R1 공식 경로 승인·부분취소 전달, PaymentFactInboxHttpSpec::FR-H1 |
| BR-FIN-002 | FINANCIAL은 Inbox 완료 기록과 사실 투영(보류 포함)을 같은 FINANCIAL 로컬 TX로 커밋한 뒤에만 ACK한다. | 확정 | 사용자 | D-ACQ-001, architecture/02 트랜잭션 경계 3, INV-05 | FIN-FACT-RELAY-001 §4.2 | PaymentFactInboxDbSpec::FR-I9 커밋 전 저장 실패, FR-I10 커밋 응답 유실 시 ACK 없음 |
| BR-FIN-003 | 같은 effect key의 사실은 여러 번 전달돼도 금융 효과를 한 번만 기록한다: 같은 eventId·같은 원문은 처음 결과를 돌려주고, 같은 eventId·다른 원문이나 다른 eventId의 같은 effect key는 충돌로 거절한다. | 확정 | 사용자 | D-ACQ-001(effect key unique), INV-06 | FIN-FACT-RELAY-001 §4.2 | PaymentFactInboxDbSpec::FR-I1·FR-I2·FR-I3·FR-I7 동시 8회 전달 |
| BR-FIN-004 | FINANCIAL의 결제 사실 업무 키는 transactionId이며 기관이 확정한 금융 효과 1건(승인·취소·부분취소 각각) = 사실 1건이다. | 확정 | 사용자 | D-AUTH-003 | FIN-FACT-RELAY-001 §4.1(effectKey = eventType:transactionId) | PaymentFactInboxHttpSpec::FR-H4 effectKey 불일치 422, PaymentFactRelayGoldenSpec::golden envelopes |
| BR-FIN-005 | FINANCIAL이 모르는 (eventType, schemaVersion) 사실은 UNSUPPORTED로 원문을 보존한 뒤 ACK하고 투영하지 않는다. | 확정 | 사용자 | D-FFR-004 | FIN-FACT-RELAY-001 §2 I-3 | PaymentFactInboxDbSpec::FR-I6 |
| BR-FIN-006 | 결제 사실은 보류 대상이어도 버리지 않고 append-only로 투영하며 기록된 사실은 수정하지 않는다. | 확정 | 사용자 | D-FFR-003(보류 원본 불변), CLAUDE.md 확정 금융 사실 append-only | FIN-FACT-RELAY-001 §4.3 | PaymentFactInboxDbSpec::FR-I11 append-only 투영, PaymentFactRepositorySurfaceSpec::save/delete 없음 |
| BR-FIN-007 | PAYMENT runtime 계정은 outbox_event의 publish_status·published_at 두 열(FINANCIAL 전달 표시)만 갱신할 수 있고 payload 등 나머지 열 수정과 DELETE는 DB가 거부한다. | 확정 | 사용자 | D-FFR-001, D-WH-009 | FIN-FACT-RELAY-001 §2 | DatabaseAccountSeparationSpec::D-WH-009 + D-FFR-001 outbox 열 권한, FinancialFactRelayDbSpec::FR-R8b |
| BR-FIN-008 | 전달 실패는 재시도(상한 없음, 연령 경보)하고, FINANCIAL 계약 코드가 붙은 400·409·422만 격리하며 격리는 완료가 아니다. | 확정 | 사용자 | FIN-FACT-RELAY-001 I-2(Implementer 해석), D-GOV-005 ③, D-GOV-010 ② | FIN-FACT-RELAY-001 §2·§4.1 | FinancialFactRelayContractSpec::FR-P3, FinancialFactRelayDbSpec::FR-R2·FR-R4 |

## 2. 결제 사실 정합성 보류

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-009 | 원거래(원승인)를 모르는 취소 사실은 ORIGINAL_UNKNOWN 보류로 투영한다. | 확정 | 사용자 | D-ACQ-002 | FIN-FACT-RELAY-001 §3 | PaymentFactProjectionRuleSpec::FR-D2, PaymentFactInboxDbSpec::FR-I4 |
| BR-FIN-010 | 정합성 보류 5종(ORIGINAL_MISMATCH·RETURN_SEQUENCE_CONFLICT·RETURN_EXCEEDS_AUTHORIZED·AUTHORIZATION_CONFLICT·IDENTITY_MISMATCH)에 해당하는 사실은 fail-closed로 보류한다. | 확정 | 사용자 | D-FFR-003(5종 유지) | FIN-FACT-RELAY-001 §2 I-5 | PaymentFactProjectionRuleSpec::FR-D4~FR-D7, PaymentFactInboxDbSpec::FR-I5·FR-I12 |
| BR-FIN-011 | ORIGINAL_UNKNOWN 보류는 원승인이 도착하고 금액·통화·식별이 MATCH하면 append-only 해제 기록으로 자동 해제하고, 불일치면 REVIEW_REQUIRED(운영자 검토)로 남기며 보류 원본 행은 수정하지 않는다. | 확정 | 사용자 | D-FFR-003 | docs/repository/ACQUIRING-001-ACQUIRING.md §4.3 | HoldReleaseRuleSpec::HR-D1·HR-D2, HoldReleaseDbSpec::HR-1·HR-2·HR-7 |
| BR-FIN-012 | 원승인 자체가 정합성 보류이면 딸린 ORIGINAL_UNKNOWN 취소는 자동 해제하지 않고 REVIEW_REQUIRED로 둔다. | 확정 | 사용자 | ACQUIRING-001 I-13, D-GOV-010 ① | ACQUIRING-001 §3 I-13 | HoldReleaseRuleSpec::HR-D5, HoldReleaseDbSpec::HR-5 |
| BR-FIN-013 | REVIEW_REQUIRED 보류의 운영자 해소는 2인 승인("강제 Hold 해제")이다. | 확정 | 사용자 | D-ADMIN-003 적용(ACQ-ADMIN-001 §2), D-GOV-010 ① | docs/repository/ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md §3.3 | 없음 |
| BR-FIN-014 | REVIEW_REQUIRED 해소 결과는 "현재 사실로 재판정해 불일치가 없을 때만 해제" 또는 "보류 유지 + 대사 이관" 두 가지뿐이고 운영자가 불일치를 덮는 강제 해제는 없다. | 미결(Q-ACQA-07) | — | Q-ACQA-07 권장안 | ACQ-ADMIN-001 §3.3 | 없음 |

## 3. 매입 대상·상계

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-015 | 매입 대상(SALE)은 FINANCIAL이 받은 PAYMENT 승인 확정 사실뿐이다. | 확정 | 사용자 | D-ACQ-002 | ACQUIRING-001 §2 | AcquisitionCandidateRuleSpec::AQ-D1 |
| BR-FIN-016 | 파일 대상 목록(membership) 확정 전에 받은 취소는 상계한다: 부분 취소는 차감한 순액으로 매입하고, 누적 전액 취소면 매입 제외로 기록한다. | 확정 | 사용자 | D-ACQ-002, D-ACQ-012 | ACQUIRING-001 §2·§4.2 | AcquisitionCandidateRuleSpec::AQ-D2·AQ-D3, AcquisitionSealDbSpec::AQ-C2 취소 먼저 |
| BR-FIN-017 | membership 확정 이후 받은 취소는 상계하지 않고 원거래를 참조하는 별도 CANCEL 레코드로 처리한다(최종 순액은 같다). | 확정 | 사용자 | D-ACQ-012, D-ACQ-002 | ACQUIRING-001 §2·§4.7 | AcquisitionSealDbSpec::AQ-C3 봉인 먼저, CancelCandidateRuleSpec::CC-6 |
| BR-FIN-018 | LIVE 매입은 실제 기관 계약번호가 승인 시점 값으로 전달(PaymentAuthorized v2)되기 전까지 차단하고, Demo 매입은 PayCore MID + 기관 거래 참조로 대조한다. | 확정 | 사용자 | D-ACQ-008 | ACQUIRING-001 §2·§14-2 | AcquisitionCandidateRuleSpec::AQ-D5 LIVE, AcquisitionTransmissionDbSpec::AQ-T19 LIVE 파일 |
| BR-FIN-019 | 같은 Payment에 해제되지 않은 결제 사실 보류가 하나라도 있으면 그 Payment의 모든 매입 후보를 대기시킨다. | 대체됨→BR-FIN-138 | 적용 | ACQUIRING-001 C-5(Coordinator 결정, 리뷰 MJ-2), D-GOV-010 ③ | ACQUIRING-001 §3 I-4 | AcquisitionCandidateRuleSpec::AQ-D4, AcquisitionSealDbSpec::AQ-DB7a·AQ-DB7b |
| BR-FIN-138 | 같은 Payment의 결제 사실 중 해제되지 않은 보류가 걸린 사실(그 승인 또는 그 취소)만 매입 대기하고, 보류가 없는 사실은 매입을 진행한다. | 확정 | 사용자 | D-GOV-010 ③ | ACQUIRING-001 C-5(정비 대상) | 없음 — 정비 대상(AcquisitionCandidateRule.kt:23-24 현재 CANCELLATION_HELD·PAYMENT_HELD로 보류 없는 원승인까지 대기) |
| BR-FIN-020 | KRW 외 통화 승인은 매입하지 않고 대기시키며 종결 판정을 기록하지 않는다. | 확정 | 사용자 | ACQUIRING-001 I-5, D-AUTH1-002(승인 KRW만), Q-SETTLE-CURRENCY, D-GOV-011 ③ | ACQUIRING-001 §3 I-5 | AcquisitionCandidateRuleSpec::AQ-D5 non-KRW |
| BR-FIN-021 | 매입 기능은 거절·UNKNOWN 사실 기록과 알림만 하고 지급 보류·회수 등 돈의 효과는 만들지 않는다(돈의 효과는 정산에서 정함). | 확정 | 사용자 | D-ACQ-005 | ACQUIRING-001 §2 | 없음 |

## 4. 매입 파일·마감·전송

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-022 | 매입사별로 업무일(Asia/Seoul)당 정기 매입 파일 하나를 만든다. | 확정 | 사용자 | D-ACQ-003 | ACQUIRING-001 §2(매입사·환경·업무일) | AcquisitionSealDbSpec::AQ-C1 같은 lane·날 경합, AcquisitionSubmissionJobSpec::AQ-J1 |
| BR-FIN-023 | 매입 마감 시각은 버전 관리 설정으로 정한다. | 확정 | 사용자 | D-ACQ-003 | ACQUIRING-001 §2 | AcquisitionCandidateRuleSpec::AQ-D8 |
| BR-FIN-024 | 이미 봉인한 업무일의 마감 정책 버전이 바뀌었거나 사라졌으면 그 날 재봉인을 거부한다(새 값은 새 버전·새 시행일로). | 확정 | 사용자 | ACQUIRING-001 I-21(D-ACQ-003 버전 관리에서 끌어냄), D-GOV-010 ① | ACQUIRING-001 §3 I-21 | AcquisitionSealDbSpec::AQ-DB8 |
| BR-FIN-025 | 매입 업무일을 달력일로 보는 것은 Q-CALENDAR 확정 전 Demo 한정 임시 규칙이다. | Demo값 | 사용자 | D-ACQ-003, Q-CALENDAR | ACQUIRING-001 §2·§14-4 | AcquisitionCandidateRuleSpec::AQ-D8 |
| BR-FIN-026 | "매입 요청" 인정은 외부기관 전송 완료 근거가 영속된 경우만이다: 내부 저장·파일 생성·부분 업로드는 불충분하고, 기관 접수 ACK·매입 확정은 선행 요구가 아니며, 불명확하면 확인 전까지 인정하지 않고, 파일에 실제 포함된 거래에만 연결한다. | 확정 | 기준 | PayCore-초기-확정사항 "선지급 기준"(매입 요청 = 외부기관 전송 완료) | ACQUIRING-001 §2(기준선 행)·§4.5 | AcquisitionTransmissionDbSpec::AQ-T2·AQ-T3·AQ-T11, AcquisitionTransmissionRuleSpec::AQ-TD2 |
| BR-FIN-027 | 전송 결과가 불명확하면(응답 유실·lease 만료·조회 NOT_FOUND) 실패나 미수신으로 단정하지 않고 결과 불명 상태로 두어 확인한다. | 확정 | 기준 | PayCore-초기-확정사항 §5, CLAUDE.md 핵심 규칙(timeout·NOT_FOUND·lease), INV-11 | ACQUIRING-001 §3 I-24·I-25 | AcquisitionTransmissionDbSpec::AQ-T3·AQ-T4·AQ-T8, AcquisitionTransmissionRuleSpec::AQ-TD3·AQ-TD4 |
| BR-FIN-028 | 전송 불명 회복은 같은 파일 ID·같은 봉인 바이트의 확인 후 재업로드로만 하고 새 파일 ID로 다시 보내지 않는다. | 확정 | 사용자 | ACQUIRING-001 I-10·I-25, D-GOV-010 ① | ACQUIRING-001 §4.5 | AcquisitionTransmissionDbSpec::AQ-T10·AQ-T16 |
| BR-FIN-029 | 같은 파일 ID 최종명에 봉인과 다른 바이트가 있으면 덮어쓰지 않고 격리(TRANSMISSION_QUARANTINED)하며 자동 처리하지 않는다. | 확정 | 기준 | PayCore-결제허용-기관연동-권장안 §5.4(같은 ID 다른 내용 격리), ACQUIRING-001 I-23 | ACQUIRING-001 §4.5 | AcquisitionTransmissionDbSpec::AQ-T5 |
| BR-FIN-030 | 전송 격리 해소는 2인 승인 + 기관 확인 근거로 TRANSMISSION_UNKNOWN에 되돌리고 그 뒤 같은 바이트 확인일 때만 전송 완료로 본다(운영자가 전송 완료를 지정하지 않음). | 미결(Q-ACQA-05) | — | Q-ACQA-05 권장안 | ACQ-ADMIN-001 §3.4 | 없음 |
| BR-FIN-031 | 격리 응답 재반영은 TRANSMISSION_QUARANTINED 사유 응답만 같은 규칙으로 다시 판정하고 운영자가 결과를 고르지 않는다(승인 수준 포함 미정). | 미결(Q-ACQA-06) | — | Q-ACQA-06 권장안 | ACQ-ADMIN-001 §3.5 | 없음 |
| BR-FIN-032 | 승인·매입 요청(전송)·수신 ACK·매입 확정·기관 입금·정산·지급은 독립 상태이며 하나의 완료로 다른 완료를 추정하지 않는다. | 확정 | 기준 | PayCore-초기-확정사항 §5, INV-12 | ACQUIRING-001 §4.1 | AcquirerResponseRuleSpec::RS-7, AcquirerResponseDbSpec::AQ-R2 |

## 5. 매입 결과

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-033 | 파일 수신 ACK와 레코드별 결과(ACCEPTED/REJECTED)는 별개 상태다. | 확정 | 사용자 | D-ACQ-004 | ACQUIRING-001 §4.6 | AcquirerResponseRuleSpec::RS-1·RS-7 |
| BR-FIN-034 | 결과 마감까지 결과가 없는 레코드는 UNKNOWN으로 기록해 대사로 넘기고 자동 재전송하지 않는다. | 확정 | 사용자 | D-ACQ-004 | ACQUIRING-001 §4.6 | RecordResultRuleSpec::RR-4, AcquirerResponseDbSpec::AQ-R6 |
| BR-FIN-035 | 기관 거절(레코드 REJECTED·파일 전체 FILE_REJECTED)은 그 파일에서 종결한다. | 확정 | 사용자 | D-ACQ-004 | ACQUIRING-001 §4.6 | RecordResultRuleSpec::RR-5, AcquirerResponseRuleSpec::RS-3, AcquirerResponseDbSpec::AQ-R4 |
| BR-FIN-036 | 결과 마감 뒤 늦게 온 유효 결과는 UNKNOWN 행을 보존한 채 append-only로 자동 반영하고, ACCEPTED 인정 시각은 수신 시각이며, 상충 결과는 격리한다. | 확정 | 사용자 | D-ACQ-010 | ACQUIRING-001 §4.6 | RecordResultRuleSpec::RR-2·RR-3·RR-4, AcquirerResponseDbSpec::AQ-R5·AQ-R6 |
| BR-FIN-037 | 매입 확정은 기관의 레코드 ACCEPTED뿐이며 재매입 승인이나 전송 완료는 매입 사실이 아니다. | 확정 | 기준 | PayCore-초기-확정사항 "선지급 기준"(매입 확정 = 거래별 매입 확정 결과), D-ACQ-004 | ACQUIRING-001 §2(기준선 행), ACQ-ADMIN-001 §3.2 | AcquirerResponseDbSpec::AQ-R1 |
| BR-FIN-038 | 같은 거래·종류에는 세대를 넘어 ACCEPTED가 하나뿐이며, 다른 세대가 이미 ACCEPTED면 들어온 ACCEPTED는 상충으로 격리한다. | 확정 | 사용자 | D-ACQ-014 | ACQUIRING-001 §3 I-56 | RecordResultRuleSpec::RR-8, AcquirerResponseDbSpec::AQ-R14 |

## 6. 전송 후 취소(CANCEL 레코드)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-039 | 전송 후 취소의 CANCEL 레코드는 원 SALE이 ACCEPTED일 때만 보낸다: 결과 대기면 자동 대기, UNKNOWN이면 대사·경보, REJECTED면 '취소 매입 불요'로 기록한다. | 확정 | 사용자 | D-ACQ-009 | ACQUIRING-001 §4.7 | CancelCandidateRuleSpec::CC-1·CC-2, AcquirerResponseDbSpec::AQ-X1 |
| BR-FIN-040 | 원 SALE이 재매입된 경우 그 뒤 받은 취소는 가장 높은 세대 원 SALE의 결과를 기준으로 판정한다. | 제안 | 적용 | ACQ-ADMIN-001 I-14·L-6(CC-4 기대값 변경) | ACQ-ADMIN-001 §15 L-6 | CancelCandidateRuleSpec::CC-8, ReacquisitionResultDbSpec::RR-1·RR-2 |
| BR-FIN-041 | 원 SALE 상계액 + 이미 봉인한 CANCEL + 이번 취소가 원승인액을 넘거나 원거래와 값이 어긋나면 CANCEL 레코드를 만들지 않고 그 취소만 대기시킨다. | 확정 | 사용자 | ACQUIRING-001 I-47, D-GOV-010 ① | ACQUIRING-001 §3 I-47 | CancelCandidateRuleSpec::CC-3·CC-4 |

## 7. 재매입

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-042 | 재매입(파일 전체 거절 뒤 포함)은 원 파일을 참조하는 같은 업무일의 새 버전 파일로만 하며 2인 승인 없이는 실행하지 않는다. | 확정 | 사용자 | D-ACQ-004, D-ACQ-011 | ACQ-ADMIN-001 §3.2 | ReacquisitionRuleSpec::RQ-1·RQ-2, ReacquisitionExecutionRuleSpec::EX-7 |
| BR-FIN-043 | 재매입은 OPERATOR가 요청하고 APPROVER가 직전 TOTP로 승인하며 요청자≠승인자이고, FINANCIAL도 요청자·승인자·대상 버전을 다시 검사한다. | 확정 | 사용자 | D-ACQA-002, D-ADMIN-007 | ACQ-ADMIN-001 §2·§3.1 | ReacquisitionRuleSpec::RQ-3, ReacquisitionExecutionRuleSpec::EX-4, AcquiringOpsApprovalDomainSpec::AD-1 |
| BR-FIN-044 | 재매입 승인 하나는 같은 원 파일의 레코드 1~50건이며 실행 때 하나라도 대상이 바뀌면 승인 전체를 거절한다(부분 실행 없음). | 확정 | 사용자 | D-ACQA-003 | ACQ-ADMIN-001 §2 | ReacquisitionExecutionRuleSpec::EX-2, ReacquisitionExecutionDbSpec::RX-2 |
| BR-FIN-045 | 요청 뒤 대상이 바뀌면(새 세대·결과 변경·앞 세대의 늦은 ACCEPTED) 재매입 승인은 무효다(TARGET_CHANGED). | 확정 | 사용자 | D-ACQ-014, D-ADMIN-003 | ACQ-ADMIN-001 §3.2 | ReacquisitionRuleSpec::RQ-5·RQ-8 |
| BR-FIN-046 | 재매입 레코드 금액은 원승인액 − 실행 시점까지 받은 모든 취소(상계·취소 매입 불요·전송 후 취소, 각 한 번)이고 '취소 매입 불요' 기록은 유지한다. | 확정 | 사용자 | D-ACQ-013 | ACQ-ADMIN-001 §2·§3.2 | ReacquisitionRuleSpec::RQ-9, ReacquisitionExecutionRuleSpec::EX-1, ReacquisitionExecutionDbSpec::RX-1·RX-13 |
| BR-FIN-047 | 실행 시점 순액이 0원이면 파일 없이 '재매입 불요'로 종결하고 재매입 검토 표시를 끈다. | 확정 | 사용자 | D-ACQA-004 | ACQ-ADMIN-001 §2 | ReacquisitionExecutionRuleSpec::EX-3, ReacquisitionExecutionDbSpec::RX-3 |
| BR-FIN-048 | 결과 UNKNOWN 레코드는 대사로 미매입이 확정된 경우에만 재매입 대상이다(대사 근거가 없으면 거절). | 확정 | 사용자 | D-ACQ-014 | ACQUIRING-001 §3 I-55 | ReacquisitionRuleSpec::RQ-2, ReacquisitionExecutionDbSpec::RX-4U |
| BR-FIN-049 | 어느 세대든 이미 ACCEPTED인 거래·종류는 재매입 대상이 아니다. | 확정 | 사용자 | D-ACQ-014 | ACQUIRING-001 §3 I-50 | ReacquisitionRuleSpec::RQ-7 |
| BR-FIN-050 | CANCEL 레코드의 재매입은 원 SALE이 현재 ACCEPTED일 때만 한다. | 확정 | 사용자 | ACQ-ADMIN-001 I-7(D-ACQ-009에서 끌어냄), D-GOV-010 ① | ACQ-ADMIN-001 §2·§3.2 | 없음 |
| BR-FIN-051 | 봉인한 재매입 파일의 레코드가 그 사이 다른 세대에서 ACCEPTED되면 그 파일 전송을 보류(TRANSMISSION_WITHHELD)한다. | 확정 | 사용자 | ACQ-ADMIN-001 L-9(D-ACQ-014에서 끌어냄), D-GOV-010 ① | ACQ-ADMIN-001 §15 L-9 | AcquisitionTransmissionRuleSpec::AQ-TD6, ReacquisitionResultDbSpec::RR-3·RR-4 |
| BR-FIN-052 | 전송 보류(TRANSMISSION_WITHHELD) 재매입 파일은 해소하지 않고 종결하며 대사 기록만 남긴다. | 미결(Q-ACQA-10) | — | Q-ACQA-10 권장안 | ACQ-ADMIN-001 §17.11 | 없음 |
| BR-FIN-053 | 재매입 승인은 승인 뒤 실행 기한 안에서만 실행되고 기한이 지나면 실행하지 않는다(운영자 보정 취소 D-OPS-002·009 선례). | 확정 | 사용자 | ACQ-ADMIN-001 I-2·I-3(D-OPS 선례 적용), D-GOV-010 ① | ACQ-ADMIN-001 §12 끝 | ReacquisitionExecutionDbSpec::RX-8·RX-10 |

## 8. 매입 조회·노출·감사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-054 | 매입 현황 조회는 VIEW_MASKED 4역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN)만 하고, 파일 상세·레코드 조회만 반환 전에 ACQUISITION_FILE_VIEWED 감사를 남기며 감사 실패 시 결과를 주지 않고, 목록 검색은 감사하지 않는다. | 확정 | 사용자 | D-ACQV-001 | docs/repository/ACQ-VIEW-001-ACQUIRING-VIEW.md §2 | AcquisitionLookupServiceSpec::the view roles·audit (D-ACQV-001), AcquisitionAuditFailureHttpSpec::503 AUDIT_UNAVAILABLE |
| BR-FIN-055 | 매입 레코드 화면·API는 승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부를 내보내지 않는다(읽는 SQL에 열이 없다). | 확정 | 사용자 | D-ACQV-002 | ACQ-VIEW-001 §4.3·§4.5 | AcquisitionFileViewReaderSpec::the statements the reader sends |
| BR-FIN-056 | 재매입 승인 상세의 근거 참조는 APPROVER와 요청자 본인만 별도 조회로 보고 조회마다 감사하며 그 밖 역할·목록에는 숨긴다. | 확정 | 사용자 | D-ACQA-005, D-OPS-010 | ACQ-ADMIN-001 §5.2 | AcquiringOpsApprovalHttpSpec::HT-3 |
| BR-FIN-057 | 재매입 근거 참조가 카드번호 패턴이면 거부한다. | 확정 | 사용자 | ACQ-ADMIN-001 rev 0.5 M-1·I-23, D-GOV-010 ① | ACQ-ADMIN-001 §11 | ReacquisitionCommandParserSpec::card number verdict |

## 9. FINANCIAL 채널·DB

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-058 | ADMIN→FINANCIAL 쓰기 명령(재매입·해소·재반영)은 조회 키와 다른 쓰기 전용 키로만 서명·검증하고 조회 키로는 쓰기 경로를 거부한다. | 확정 | 사용자 | D-ACQA-001 | ACQ-ADMIN-001 §2·§4 | AdminAcquiringOperationsHttpSpec::D-ACQA-001 operation/read key |
| BR-FIN-059 | 매입 기관 SFTP는 클라이언트 키 인증과 서버 호스트 키 고정(모르는 호스트 거부)으로만 접속하고 키는 환경변수·파일로만 주입하며 저장소·로그에 두지 않는다. | 확정 | 사용자 | D-ACQ-006 | ACQUIRING-001 §8 | AcquisitionTransmissionDbSpec::AQ-T6·AQ-T15, AcquiringTransportSettingsSpec::AQ-S2 |
| BR-FIN-060 | FINANCIAL은 migration·runtime 계정을 분리해 확정 사실 표의 runtime UPDATE·DELETE를 DB가 거부한다. | 확정 | 사용자 | D-FFR-002 | docs/repository/FIN-DBACCOUNT-001-FINANCIAL-DB-ACCOUNTS.md §2·§4 | FinancialDatabaseAccountSpec::the resulting FINANCIAL accounts and privileges |

## 10. 수수료·VAT

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-061 | 수수료 정책 적용 기준일은 원 승인일이다. | 확정 | 기준 | SCP §1·§29, PayCore-초기-확정사항 "수수료·정산 날짜" | 없음(SETTLEMENT-CONTRACT-001 미작성) | 없음 |
| BR-FIN-062 | 취소·부분취소의 수수료·VAT 반환은 원승인 FeeCalculation과 원승인일 Policy Snapshot(FeeType·계산 방법·요율/정액·반올림·VAT 계산·세율·반올림)으로 하고 취소일의 현재 정책으로 다시 계산하지 않는다. | 확정 | 기준 | SCP §1·§29, 도메인구조 §1, D-CANCEL-008 | 없음 | PaymentCancellationSpec::copies the original policy and entitlement versions (CX-11, PAYMENT 쪽 근거 보존만) |
| BR-FIN-063 | 수수료·VAT의 계산 방법·반올림·세율·효력 기간은 코드에 고정하지 않고 버전 있는 기준정보에서 선택하며, 활성 정책은 수정하지 않고 새 Version을 만든다. | 확정 | 기준 | SCP §2, PayCore-초기-확정사항 "Settlement Control Policy", 도메인구조 연결 §3 | 없음 | 없음 |
| BR-FIN-064 | 부분취소의 k번째 수수료 반환 = round(원 수수료 × 누적 취소액 ÷ 승인액) − 이전 반환 합이며 VAT도 같은 방식이고 반올림은 원승인 Snapshot 규칙을 따르며 마지막 취소가 끝전을 정확히 흡수한다. | 확정 | 사용자 | D-CANCEL-008 | 없음 | 없음 |
| BR-FIN-065 | 고정 수수료(최소 수수료 하한 포함)와 그 VAT는 누적 취소액이 승인액에 도달하는 취소에서만 반환하고, 누적 비례 공식은 요율 부분과 그 VAT에만 적용한다. | 확정 | 사용자 | D-CANCEL-009 | 없음 | 없음 |
| BR-FIN-066 | 별도 취소 수수료는 받지 않는다. | 확정 | 사용자 | D-CANCEL-008 | 없음 | 없음 |
| BR-FIN-067 | 수수료·반환 계산은 FINANCIAL(SETTLEMENT)이 소유한다. | 확정 | 사용자 | D-CANCEL-008, 도메인구조 연결 §3 | 없음 | 없음 |
| BR-FIN-068 | 사업자(Business)에는 효력 있는 수수료율을 두지 않으며 요율은 가맹점 서비스 계약·MID 정책 배정(2인 승인) + 원승인 Snapshot으로만 정한다. | 확정 | 사용자 | D-FLOW-001 ③, Q-FLOW-4 | docs/repository/MERCHANT-FLOW-001-BUSINESS-CONTRACT-FLOW.md §5 | 없음 |

## 11. 정책 기준정보·버전·Assignment

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-069 | Fee·Settlement·Advance·Correction 정책 정의·Assignment·Calendar 원본은 MASTER, 적용 Snapshot·계산·한도 점유·금융 사실은 FINANCIAL이 소유하고, PAYMENT는 승인 당시 정책 근거 버전을 보존한다. | 확정 | 기준 | 도메인구조 연결 §3, architecture/02 핵심 규칙 | docs/repository/MASTER-SNAPSHOT-002-APPROVAL-CONDITION-SNAPSHOT.md | PaymentCancellationSpec::CX-11 (PAYMENT 버전 보존) |
| BR-FIN-070 | 수수료·정산 정책은 적용 정책이 정확히 하나여야 하고 둘 이상이면 AmbiguousPolicy로 실패하며 MID→GID→Merchant→Default 같은 숨은 우선순위를 만들지 않는다. | 확정 | 기준 | SCP §28·연결 C | 없음 | 없음 |
| BR-FIN-071 | 승인 시작은 (CSA, MID, 통화) 수수료·정산 정책 Assignment가 있어야 하며 통화 활성화 기록만으로 허용하지 않는다. | 확정 | 사용자 | D-MD-018, D-MD-001 | MASTER-SNAPSHOT-002 | AuthorizationTermsSpec::policy assignments of an issued MID (D-MD-018) |
| BR-FIN-072 | 수수료·정산 policy_version은 지금 식별 등록부(종류·코드·번호·상태·내용 hash)만이며, 내용 없는 버전은 TEST/Simulator 전용이고 LIVE 허용 Gate의 차단 조건이다. | 확정 | 사용자 | D-MS2-003 | MASTER-SNAPSHOT-002 | AuthorizationTermsSpec::the policy version registry (D-MS2-003), AuthorizationTermsSchemaSpec::policy versions and assignments |
| BR-FIN-073 | 수수료 정책 배정·정산 정책 배정·배정 종료·수수료율 변경은 2인 승인(요청자≠승인자)이다. | 확정 | 사용자 | D-MOPS-004, D-MOPS-007 ③, D-ADMIN-003 | docs/repository/MERCHANT-OPS-002-MERCHANT-ONBOARDING.md §B2–B5 | 없음 |
| BR-FIN-074 | 운영 정책값 변경은 기존 행 UPDATE가 아니라 새 Version과 적용 기간으로 하고 과거 거래의 계산 근거 Snapshot은 바꾸지 않는다. | 확정 | 기준 | 도메인구조 §4·연결 §3, INV-10, architecture/02 제약 | 없음 | 없음 |
| BR-FIN-075 | Default MID·GID는 계약·수수료·정산 조건을 자동 상속하지 않는다. | 확정 | 기준 | PayCore-초기-확정사항 "가맹점·계약·MID 기본 관계", SCP §11 | 없음 | 없음 |
| BR-FIN-076 | SettlementPolicy는 계약 본문에 넣지 않고 ContractServiceAgreement → SettlementPolicyAssignment → SettlementPolicy로 연결한다. | 확정 | 기준 | SCP §8, D-MD-001 | 없음 | 없음 |

## 12. 정산 날짜·영업일

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-077 | 정산 거래일은 금융 사실이 실제 발생한 날(승인일·취소일·Refund 완료일)이며 승인과 취소는 각각 독립 SettlementEntry를 만든다. | 확정 | 기준 | SCP §3·§29, PayCore-초기-확정사항 | 없음 | 없음 |
| BR-FIN-078 | 취소 수수료의 원승인 정책 참조와 취소 정산의 거래일(취소일)은 구분한다. | 확정 | 기준 | PayCore-초기-확정사항 "수수료·정산 날짜" | 없음 | 없음 |
| BR-FIN-079 | Refund 완료일은 확정된 외부 반환 사실의 업무 시각을 기준으로 하고 PAYMENT에 늦게 기록된 수신 시각과 구분한다. | 확정 | 기준 | SCP 연결 B | 없음 | 없음 |
| BR-FIN-080 | 정산 기준일은 거래일이 영업일이면 거래일, 비영업일이면 다음 영업일이다. | 확정 | 기준 | SCP §4 | 없음 | 없음 |
| BR-FIN-081 | 정산 예정일 = 기준일 + SettlementCycle이며 transactionDate·settlementBaseDate·settlementDueDate를 구분해 보존한다. | 확정 | 기준 | SCP §5 | 없음 | 없음 |
| BR-FIN-082 | Cutoff(거래일 귀속)·Business Calendar(영업일 여부)·Settlement Cycle(지급 시점)은 서로 다른 개념으로 분리한다. | 확정 | 기준 | SCP §9 | 없음 | 없음 |
| BR-FIN-083 | 영업일은 버전 있는 BusinessCalendar 기준정보(초기 PAYCORE_KR)로 판정하고 주말 계산만으로 끝내지 않는다. | 확정 | 기준 | SCP §6·§29 | 없음 | 없음 |
| BR-FIN-084 | Calendar가 사후 변경돼도 이미 생성된 Entry의 기준일·예정일은 다시 계산하지 않고 계산 당시 결과(필요 시 calendarCode·version)를 보존하며, 확정 전 재산출은 명시 작업으로 새 계산 근거를 기록하고 확정 후는 Adjustment·일정 변경 절차로만 바꾼다. | 확정 | 기준 | SCP §7·연결 B | 없음 | 없음 |
| BR-FIN-085 | 첫 SettlementCycle 엔진은 DAILY_OFFSET(영업일 D+N)이며 Weekly·Monthly·SemiMonthly 등 미구현 유형은 명확히 거절한다. | 확정 | 기준 | 도메인구조 §2·연결 §4 | 없음 | 없음 |
| BR-FIN-086 | D+N의 N 계산·D 포함 여부·정산 마감·적용 시간대·휴일 변경 적용 기준. | 미결(Q-CALENDAR) | — | Q-CALENDAR(PROPOSED), SCP 연결 B·E | 없음 | 없음 |
| BR-FIN-087 | 외화 승인 거래의 가맹점 정산 통화·환율(현재는 거래통화를 승인 거래에 보존만 한다). | 미결(Q-SETTLE-CURRENCY) | — | D-CP-002, Q-SETTLE-CURRENCY("나중에 결정") | 없음 | 없음 |

## 13. 지급·선지급

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-088 | 기관 입금 후 지급과 기관 입금 전 선지급을 모두 지원하고 기준정보·버전 있는 정책 설정으로 선택한다. | 확정 | 기준 | PayCore-초기-확정사항 "입금 후 지급·선지급 모두 지원"(사용자 결정 원문 인용) | 없음 | 없음 |
| BR-FIN-089 | 정산 계산·지급채무 확정·지급 실행·기관 입금은 별도 상태로 유지한다. | 확정 | 기준 | PayCore-초기-확정사항, INV-12 | 없음 | 없음 |
| BR-FIN-090 | 선지급 기준은 승인 완료·매입 요청(외부기관 전송 완료)·매입 확정 세 가지를 지원하고 선지급 정책에 적용 기준을 연결한다. | 확정 | 기준 | PayCore-초기-확정사항 "선지급 기준", CLAUDE.md 핵심 규칙 (진단 b-1: D-ACQ-005와 충돌 가능) | 없음 | 없음 |
| BR-FIN-091 | 지급 기준(승인 시점 / 매입 결과)은 기준정보 설정으로 선택하며 매입 결과에 따른 지급 보류는 정산에서 정한다. | 확정 | 사용자 | D-ACQ-005, Q-ACQ-05 | 없음 | 없음 |
| BR-FIN-092 | 같은 거래가 승인 완료→매입 요청→매입 확정으로 진행돼도 지급채무·선지급은 한 번만 생긴다(채무·지급 업무키 유지). | 확정 | 기준 | PayCore-초기-확정사항 "선지급 기준" | 없음 | 없음 |
| BR-FIN-093 | 선지급은 별도 Financing Domain 없이 확정 Settlement·Payable의 Early Release와 AdvancePayoutEligibility로 판단하며 Payable 금액을 바꾸지 않는다. | 확정 | 기준 | SCP §10·§29 | 없음 | 없음 |
| BR-FIN-094 | 선지급은 최소한 정책 enabled·계약 유효·Payable OPEN·Settlement 확정·미해결 Critical 대사 Case 없음·진행 중 지급 없음·지급 UNKNOWN 없음·정산/지급 Hold 없음·한도 여유를 모두 만족해야 한다. | 확정 | 기준 | SCP §12 | 없음 | 없음 |
| BR-FIN-095 | 재원 조건(기관 입금 여부)과 일정 조건(예정일 전 지급)은 각각 판정한다: 예정일 도래로 미입금 조건을 무시하지 않고, 선지급 enabled만으로 미확정 채무를 지급하지 않는다. | 확정 | 기준 | SCP 연결 A | 없음 | 없음 |
| BR-FIN-096 | 승인 완료 기준 선지급 정책에 매입 확정을 숨은 필수 조건으로 강제하지 않는다. | 확정 | 기준 | SCP 연결 A | 없음 | 없음 |
| BR-FIN-097 | 기관 입금 뒤에는 이미 실행한 선지급을 다시 실행하지 않는다. | 확정 | 기준 | SCP 연결 A | 없음 | 없음 |
| BR-FIN-098 | 별도 Financing Domain을 두지 않아도 기관 미수·입금 배분·선지급 노출·사용 가능 재원 기록은 FINANCIAL 금융 통제로 유지한다. | 확정 | 기준 | SCP 연결 A, 도메인구조 연결 §4 | 없음 | 없음 |
| BR-FIN-099 | 선지급 한도 최소 엔진은 enabled·건당 금액·일 금액이다. | 확정 | 기준 | 도메인구조 §3·연결 §4 | 없음 | 없음 |
| BR-FIN-100 | 선지급 정책 Scope는 ContractServiceAgreement 또는 명시적 MID Override이며 GID 기반 자동 상속은 없다. | 제안 | 기준 | SCP §11("권장"), 연결 C | 없음 | 없음 |
| BR-FIN-101 | 지급 결과가 UNKNOWN이면 자금 예약·실행 점유를 유지하고, lease 만료·재시작·DB에 지급 기록 없음만으로 해제하거나 재지급하지 않는다. | 확정 | 기준 | architecture/06, INV-11·INV-16, CLAUDE.md 핵심 규칙 | 없음 | 없음 |
| BR-FIN-102 | 선지급 거래의 매입 거절·UNKNOWN·후발 취소·기관 미입금에 따른 보류·회수·상계와 그 적용 시점. | 미결(Q-FIN-RG-01 후보) | — | D-ACQ-005(정산에서 정함), PayCore-초기-확정사항, SCP 연결 A·E | 없음 | 없음 |
| BR-FIN-103 | 담보(보증보험) 기록은 참고용이며 선지급·지급 판단에 쓰지 않는다(효력 부여는 정산·리스크 작업에서 별도 결정). | 확정 | 사용자 | D-FLOW-003 ④, Q-FLOW-7 | MERCHANT-FLOW-001 | 없음 |
| BR-FIN-104 | MID·가맹점 신규 결제 차단은 신규 승인만 막고 정산은 막지 않는다. | 확정 | 사용자 | D-MD-006 | 없음 | 없음 |
| BR-FIN-105 | 가맹점 정산 지급 계좌 변경은 2인 승인이다. | 확정 | 사용자 | D-ADMIN-003 | 없음(MASTER-PAYOUT-ACCOUNT-001 BACKLOG) | 없음 |
| BR-FIN-106 | TEST 환경 매입분을 정산에 포함할지 여부. | 미결(Q-FIN-RG-02 후보) | — | ACQUIRING-001 §3 I-6·§14-7, D-CPD-001 | ACQUIRING-001 §14 | 없음 |

## 14. 정산·지급 Hold

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-107 | Hold는 범위를 ADVANCE_ONLY(선지급만)와 ALL_PAYOUT(모든 지급)으로 구분한다. | 확정 | 기준 | SCP §13·§29, PayCore-초기-확정사항 | 없음 | 없음 |
| BR-FIN-108 | 초기에는 SettlementHold와 PayoutHold를 별도로 둔다. | 확정 | 기준 | SCP §14, PayCore-초기-확정사항 | 없음 | 없음 |
| BR-FIN-109 | Hold가 여러 개면 하나를 해제해도 다른 유효 Hold는 유지된다. | 확정 | 기준 | SCP 연결 D | 없음 | 없음 |
| BR-FIN-110 | High/Critical 대사 Case가 생기면 위험 영향범위에 맞는 Scope로 Hold를 걸고 처음부터 전 가맹점을 막지 않는다. | 확정 | 기준 | SCP §20 | 없음 | 없음 |
| BR-FIN-111 | HoldType 후보는 RISK·RECONCILIATION·CONTRACT·ACCOUNT·ADVANCE_LIMIT·MANUAL이고 Scope 후보는 MERCHANT_ACCOUNT·SETTLEMENT_ENTRY·SETTLEMENT_STATEMENT·PAYABLE·PAYOUT이다. | 확정 | 사용자 | SCP §14·§20("후보"), D-GOV-010 ① | 없음 | 없음 |

## 15. 대사·자동 보정·Adjustment

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-112 | 대사 차이는 데이터를 자동 수정하지 않고 ReconciliationCase → 위험 판정 → AUTO_RECOVERY / AUTO_ADJUSTMENT / MANUAL_REVIEW / BLOCK으로 처리한다. | 확정 | 기준 | SCP §15·§29, PayCore-초기-확정사항 | 없음(RECON-CONTRACT-001 미작성) | 없음 |
| BR-FIN-113 | 자동 Recovery(기존 금융 사실은 있고 상태 반영 누락)와 자동 Adjustment(확정 금액의 차액 보정)를 구분하고, Adjustment는 기존 원장을 UPDATE하지 않는 별도 기록이다. | 확정 | 기준 | SCP §16, D-AUTH-002, architecture/06 | 없음 | 없음 |
| BR-FIN-114 | 위험 등급은 LOW·MEDIUM·HIGH·CRITICAL이고 LOW는 정책 허용 범위의 자동 Recovery·제한적 Adjustment, MEDIUM은 수동 검토, HIGH·CRITICAL은 Hold + 수동 검토다. | 확정 | 기준 | SCP §18·§19·§29 | 없음 | 없음 |
| BR-FIN-115 | 자동 처리 여부는 금액만이 아니라 차이 유형·출처 신뢰성·금액·중복 위험·실제 자금 이동 영향·UNKNOWN 존재로 판단한다. | 확정 | 기준 | SCP §17 | 없음 | 없음 |
| BR-FIN-116 | exact-match 복구 사례만으로 HIGH·CRITICAL을 LOW로 낮추지 않으며, 자동 처리는 승인된 AutoCorrectionPolicy가 허용하고 원 요청·통화·MID·기관 계정·수취인·증거 신뢰성·중복 위험을 검증한 경우에만 한다. | 확정 | 기준 | SCP 연결 D | 없음 | 없음 |
| BR-FIN-117 | 보정 완료·요청 수락만으로 Case를 해결하지 않고 보정 → 재대사 → MATCH 확인 뒤에만 RESOLVED로 하며 MATCH가 아니면 닫지 않는다. | 확정 | 기준 | SCP §21·연결 D, CLAUDE.md 핵심 규칙, D-AUTH-002 | 없음 | 없음 |
| BR-FIN-118 | 자동 보정은 UNIQUE(reconciliation_case_id, correction_type)와 Case 안정키·원 금융 사실·조정의 업무 유일키로 멱등을 지켜 새 Run·새 Case로 같은 보정을 다시 실행하지 못하게 한다. | 확정 | 기준 | SCP §22·연결 D | 없음 | 없음 |
| BR-FIN-119 | 자동 보정은 kill switch로 즉시 멈출 수 있고 실행 직전 다시 검사하며 이미 생긴 외부 효과는 취소하지 않고 복구한다. | 확정 | 기준 | SCP §23·연결 D | 없음 | 없음 |
| BR-FIN-120 | 자동 보정 한도 최소 엔진은 건당 금액·일 금액·일 건수다. | 확정 | 기준 | 도메인구조 연결 §4 | 없음 | 없음 |
| BR-FIN-121 | 보정은 소유 도메인의 Recovery·Adjustment API로만 하고, 대사가 PAYMENT DB를 직접 조회·수정하거나 mismatch를 맞추는 SQL UPDATE를 하지 않으며, 외부 재전송·재송금은 별도 통제 명령이다. | 확정 | 기준 | SCP 연결 D, INV-02 | 없음 | 없음 |
| BR-FIN-122 | 재처리는 미생성 데이터 = 멱등 Backfill, 확정 전 결과 = Rebuild, 확정 후 금융 오류 = Adjustment, 대사 결과 = 새 Observation·Case로 하고 확정 원장을 단순 UPDATE하지 않는다. | 확정 | 기준 | SCP §27 | 없음 | 없음 |
| BR-FIN-123 | FeePolicy·SettlementPolicy·BusinessCalendar·AutoCorrectionPolicy 변경은 운영 적용 전 과거 업무일 대상 Dry Run(기존 결과 대비)으로 비교한다. | 확정 | 사용자 | SCP §26("권장"), D-GOV-010 ① | 없음 | 없음 |
| BR-FIN-124 | 거절·만료 종결 뒤 기관의 늦은 성공·상충 결과는 자동 보상취소하지 않고 incident hold + 대사 Case(HIGH/CRITICAL)로 다루며, 해제는 2인 승인 + 재대사 MATCH(이중 승인이면 보정 취소 참조 추가) 뒤에만 한다. | 확정 | 사용자 | D-AUTH-002, D-AUTH-011, D-AUTH-012, D-AUTH-013 | docs/repository/AUTH-001-AUTHORIZATION.md(해제 실행은 RECON-CONTRACT-001) | 없음(해제 실행 Use Case 미구현) |
| BR-FIN-125 | 대사로 이관된 취소 UNKNOWN은 임시 출구 없이 예약·차단을 유지하며 RECON이 정리할 수 있기 전에는 LIVE를 열지 않는다. | 확정 | 사용자 | D-CANCEL1-003 | docs/repository/CANCEL-001-CANCELLATION.md | 없음 |
| BR-FIN-126 | 보정 근거 참조(사고·대사 Case)의 원천 대조는 LIVE 허용 Gate 차단 조건이고 Demo·TEST에서는 형식 검사만 한다. | 확정 | 사용자 | D-OPS-004 | docs/repository/ADMIN-PAYMENT-OPS-001-PAYMENT-OPS.md | 없음 |

## 16. FIN 명령의 2인 승인·감사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-127 | 재지급·고액 조정·Critical 대사 해결·강제 Hold 해제·선지급 예외는 2인 승인(요청자≠승인자, 승인자 APPROVER, 요청 후 대상 변경 시 무효)이다. | 확정 | 사용자 | D-ADMIN-003, SCP §24 | 없음 | 없음 |
| BR-FIN-128 | 2인 승인 대상 명령은 소유 서비스(FINANCIAL)도 요청자≠승인자·역할·승인한 입력 버전 = 실행 입력을 다시 검사하고 자기 기록을 남기며 관리자 앱 단독으로 실행할 수 없다. | 확정 | 사용자 | D-ADMIN-007, SCP 연결 D | ACQ-ADMIN-001 §2 | ReacquisitionExecutionRuleSpec::EX-4, ReacquisitionExecutionDbSpec::RX-4 |
| BR-FIN-129 | FeePolicy·SettlementPolicy·Holiday Calendar 변경, Adjustment 승인, Hold 생성·해제, 재지급 승인, 대사 수동 해결, 선지급 예외는 누가·언제·왜를 감사로 남긴다. | 확정 | 기준 | SCP §25 | 없음 | 없음 |

## 17. 정산서·외부 노출

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-130 | 가맹점 코드는 정산서에도 노출한다. | 확정 | 사용자 | D-MCODE-001(Q-MC-1) | 없음(정산서 미구현) | 없음 |
| BR-FIN-131 | Site 코드·계약 코드는 정산서에 노출하지 않는다(외부 Site 식별은 merchantSiteId). | 확정 | 사용자 | D-ADMIN-UI-007 ④(Q-SC-7) | 없음 | 없음 |

## 18. Demo 값 (운영값 아님)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-FIN-132 | Demo Seed 정책은 Simulator 전용 경로로 설치하고 운영 migration의 자동 기본값으로 넣지 않으며, 예시에 없는 반올림·한도 값은 운영값으로 추측하지 않는다. | 확정 | 기준 | 도메인구조 연결 §3, CLAUDE.md 핵심 규칙 | 없음 | 없음 |
| BR-FIN-133 | 수수료 CARD 2.8%·VAT_EXCLUDED VAT 10%는 Demo 값이다. | Demo값 | 기준 | 도메인구조 §1·연결 §3 | 없음 | 없음 |
| BR-FIN-134 | 정산 주기 D+2 영업일은 Demo 값이다. | Demo값 | 기준 | 도메인구조 §2·연결 §4 | 없음 | 없음 |
| BR-FIN-135 | 선지급 건당 10,000,000원·일 50,000,000원은 Demo 값이다. | Demo값 | 기준 | 도메인구조 §3·연결 §3 | 없음 | 없음 |
| BR-FIN-136 | 자동 조정 건당 1,000원·일 100,000원은 Demo 값이다. | Demo값 | 기준 | 도메인구조 §3·연결 §3 | 없음 | 없음 |
| BR-FIN-137 | 매입 마감 시각·결과 마감 시간·스케줄 주기·지표 경보 임계는 Demo 값이다. | Demo값 | 사용자 | D-ACQ-003, ACQUIRING-001 §14-4·§14-16 | ACQUIRING-001 §14 | AcquisitionCandidateRuleSpec::AQ-D8 |

## 진단

### (a) 대체 사슬

1. FIN-FACT-RELAY-001 I-1(전달 상태는 새 표만, `publish_status` 불변) → **D-FFR-001**(두 열 UPDATE 허용). 명세 §2에 대체 표시 있음.
2. FIN-FACT-RELAY-001 I-3 → D-FFR-004, I-4·I-5 → D-FFR-003. D-ACQ-002("원거래 모르는 취소는 Hold") → D-FFR-003이 자동 해제 규칙을 덧붙임(대체가 아니라 확장).
3. ACQUIRING-001 I-1(상계 경계 해석) → **D-ACQ-012**(사용자 결정으로 승격).
4. ACQ-VIEW-001/ACQ-ADMIN-001 I-24(근거 참조 노출) → **D-ACQA-005**.
5. assistant 권고 사슬: "초기 선지급 미지원" → 입금 후 지급·선지급 모두 지원 / "매입 확정만 선지급" → 3기준 / "기관 접수 ACK 기준" → 외부기관 전송 완료(모두 초기-확정사항). 이어서 **D-ACQ-005**가 "지급 기준(승인 시점/매입 결과)"을 말하며 "매입 전송 완료 기준의 존속 여부는 정산 작업에서 확인"이라 적어, 3기준이 대체됐는지 불명확(→ b-1).
6. 반올림 방식 확인 질문 → SCP v0.1(버전 있는 기준정보에서 선택, 실제 값은 미정).
7. SCP 연결 B·초기-확정사항의 "정률·정액 반환 비율·부분취소 끝전·별도 취소비는 별도 확정" → **D-CANCEL-008·009**(누적 비례, 고정 수수료는 누적 전액 도달 시, 별도 취소비 없음).
8. 금융통제-자동대사 대안 문서 → SCP v0.1(충돌 시 SCP 우선).
9. CancelCandidateRule CC-4(세대 0만 정합) → ACQ-ADMIN-001 L-6(rev 0.7, 세대 ≥ 1 개방, 시험 기대값을 CC-8로 이동). 사용자 결정 없이 Implementer 근거로 바뀐 규칙(BR-FIN-040).
10. Q-ACQA-04 권장안 문구("결정 EXECUTED(NET_ZERO)") → D-ACQA-004·구현(결정 NOT_REQUIRED).
11. BR-FIN-019(Payment 전체 대기) → **BR-FIN-138**(보류 걸린 사실만 대기, D-GOV-010 ③). 원승인 선매입 뒤 보류 취소의 상계·대사 경로는 정비 명세에서 정함(D-GOV-010 ③).

### (b) 결정 간 충돌

1. **선지급 기준 3종 vs D-ACQ-005**: 초기-확정사항·CLAUDE.md는 "승인 완료·매입 전송 완료·매입 확정 세 기준 유지"(BR-FIN-090). D-ACQ-005 사용자 원문은 "승인시점과 매입결과 선택"이고 기록자는 "매입 전송 완료 기준의 존속 여부는 정산 작업에서 확인"이라 적었다. 매입 요청(전송 완료) 기준이 남는지, "매입결과"가 매입 확정만인지 거절·UNKNOWN까지 포함한 판단인지 확정되지 않았다.
2. **D-ACQ-005 "지급 기준"의 적용 범위**: 질문(Q-ACQ-05)은 선지급 건이었는데, 답은 "지급 기준"이라 입금 후 지급에도 적용되는지 모호하다(BR-FIN-091).
3. **정산 거래일의 시각 원천**: D-ADMIN-HOME-001은 운영 홈 순승인액을 "정산일 규칙과 같은 완료일 기준"이라 하면서 PG 확정 시각(confirmed_at, KST)을 원천으로 정했다. SCP 연결 B는 Refund 완료일을 "확정 외부 반환 사실의 업무 시각"으로 하고 PAYMENT 수신 시각과 구분한다. 결제 사실에는 `approvedAt`·`institutionTransactionDate`·`institutionBusinessDate`(nullable)가 함께 있다(AuthorizedPaymentFact.kt:21-23). 정산 거래일이 어느 값인지 정해야 한다(KPI는 "정산 금액 아님" 표지가 있어 직접 충돌은 아님).
4. **Assignment 수준**: SCP §8·§11·연결 C는 CSA 단위 Assignment + 명시적 MID Override 수렴을 말하지만, D-MD-018과 MASTER V5 `policy_assignment`는 (CSA, MID, 통화, 종류) MID별 배정만 있다(CSA 기본 배정 + MID Override 구조 없음). 한도 정책만 D-MS2-001(Override 우선·전체 대체)로 정해졌고, 수수료·정산 정책의 CSA 기본/Override 해석 규칙은 결정이 없다.
5. **BR-FIN-016(membership 확정 전 받은 취소는 상계) vs BR-FIN-138(보류 취소 미판정인 채 원승인 매입 진행)**: 보류가 걸린 취소는 상계되지 않은 채 원승인이 먼저 매입될 수 있다. 보류 취소 상계 시점은 정비 명세에서 정함(D-GOV-010 ③).

### (c) 정책서·명세 본문이 현재 결정과 다른 곳

1. `PayCore-초기-확정사항.md` "수수료·정산 날짜"의 "원승인 수수료 반환 비율/고정비 반환 여부의 값은 별도 설정 대상", SCP 연결 B "정률/정액 반환 비율·부분취소 마지막 잔액·별도 취소비 등은 별도 확정" — D-CANCEL-008·009로 확정됐는데 본문에 반영되지 않았다.
2. `CLAUDE.md` 핵심 규칙 "실제 통화/체크카드/할부·인증 범위 추가안은 사용자 채택 전"과 `BASELINE-INDEX.md` "최초 구현 범위 권장안: … 채택 전" — D-AUTH-001이 KRW·신용+체크·일시불·일반 할부를 채택했다. FIN 매입의 카드 종류(`AuthorizedPaymentFact.CARD_TYPES` = CREDIT·CHECK)와 KRW 한정의 근거가 이 문구와 어긋나 보인다.
3. SCP §8 그림(CSA → SettlementPolicyAssignment)은 D-MD-018의 (CSA, MID, 통화) 범위를 반영하지 않았다.
4. 명세 상태 문구가 오래됐다: `ACQUIRING-001` 머리 "Verifier/Reviewer 판정 전(DONE 아님)", `FIN-DBACCOUNT-001` "DONE 아님" — `harness/archive/tasks-done-2026-11-22.json`에서는 둘 다 DONE.
5. `ACQUIRING-001` 근거가 "매입 요청 = 외부기관 전송 완료"를 `PayCore-정산지급방식-확정과-상세권장안.md`(BASELINE-INDEX상 역사 자료)에서 인용한다. 같은 규칙은 채택 문서 초기-확정사항에도 있으므로 근거를 그쪽으로 옮겨야 한다.
6. `ACQ-ADMIN-001` §12 Q-ACQA-04 권장안 "결정 EXECUTED(NET_ZERO)" ↔ D-ACQA-004·코드 `AcquisitionOpsDecision.kt:53`(EXECUTED·NOT_REQUIRED·REJECTED).
7. D-ACQ-003 "매입사별 하루 1파일" ↔ 명세·코드 "(매입사, 환경, 업무일)당 1파일"(환경 축 추가 — 해석, 결정 문구 미반영).
8. RULE-GOV-001 §3 FIN 주요 결정 접두(ACQ, ACQA, ACQV, FFR)에 FIN 규칙을 담은 D-CANCEL-008·009, D-MS2-003, D-MD-018, D-MOPS-004·007, D-FLOW-001·003, D-MCODE-001, D-ADMIN-UI-007, D-AUTH-002·011~013, D-CANCEL1-003이 빠져 있다.

### (d) 시험 없는 확정 규칙

- 정산·수수료·지급·Hold·대사 전부(코드 없음): BR-FIN-061, 063~068, 070, 074~085, 088~099, 101, 103~105, 107~110, 112~122, 124~127, 129~132.
- 매입 쪽: BR-FIN-021(매입은 돈의 효과를 만들지 않음 — 구조로만 지켜짐, 이를 고정하는 ArchUnit·시험 없음).
- 정책 배정 2인(BR-FIN-073): MASTER·ADMIN 명령 경로에서 시험을 찾지 못했다(관리자 승인 종류에 수수료·정산 정책 배정 kind 미발견 — BIZ 대장과 대조 필요).
- 부분 검증만 있음: BR-FIN-062·069는 PAYMENT가 원승인 정책·권리 버전을 취소에 복사하는 것만 시험(PaymentCancellationSpec CX-11). FINANCIAL 쪽 원승인 FeeCalculation 참조는 없다.

### (e) 코드가 규칙과 다른 곳·규칙을 앞서 간 곳

1. `apps/financial-batch/src/main/kotlin/com/paycore/financial/acquiring/domain/AcquisitionCandidateRule.kt:24` — PAYMENT_HELD(같은 Payment의 다른 보류로 전체 대기)는 Coordinator 결정 C-5다. 사용자 결정 없이 매입 대기(돈 흐름 지연) 효과를 만든다. fail-closed 방향이지만 BR-FIN-019 확인이 필요하다. [처리: D-GOV-010 ③ BR-FIN-019→BR-FIN-138(보류 걸린 사실만 대기) — `:23-24` CANCELLATION_HELD·PAYMENT_HELD 정비 대상]
2. 같은 파일 `:25` — KRW 외 대기(I-5). D-AUTH1-002(승인 KRW만)와는 맞지만 FIN 결정은 아니다(BR-FIN-020). [처리: D-GOV-011 ③ BR-FIN-020 확정]
3. `.../acquiring/domain/ReacquisitionRule.kt:33` — UNKNOWN은 언제나 UNKNOWN_NOT_RECONCILED로 거절된다. D-ACQ-014가 허용한 "대사 미매입 확정 후 재매입" 경로가 없다(RECON 미구현). 결과적으로 UNKNOWN 매입 레코드는 RECON 전까지 출구가 없다(D-ACQ-004 "UNKNOWN → 대사"의 대사 쪽 공백).
4. `.../acquiring/domain/CutoffPolicy.kt:28` — 업무일 = Asia/Seoul 달력일(D-ACQ-003 Demo 한정). Q-CALENDAR가 확정되면 바꿔야 한다. LIVE는 `AcquisitionCandidateRule.kt:20` LIVE_BLOCKED로 막혀 있다.
5. `.../acquiring/domain/CancelCandidateRule.kt:48` — 세대 ≥ 1 원 SALE 판정 개방(L-6). D-ACQ-009를 재매입 세대로 넓힌 해석이 코드와 시험 기대값(CC-4 → CC-8)을 바꿨다(BR-FIN-040, 사용자 확인 대상). [처리: BR-FIN-040 확정 보류·정책 분석 중(D-GOV-010 ④)]
6. `apps/master-api/src/main/resources/db/migration/master/V5__authorization_terms.sql:87` — `content_status IN ('UNDEFINED','DEMO')`라 수수료·정산 내용이 없다. FINANCIAL 사실은 `policyAssignmentVersion` 문자열만 가진다(`.../paymentfact/domain/AuthorizedPaymentFact.kt:24`). 원승인 FeeCalculation Snapshot(SCP §1)을 만들 입력이 아직 없다. D-MS2-003대로라 위반은 아니지만, LIVE 전 필수 공백이다.
7. 같은 V5 `:110·:113` — 열린 배정 UNIQUE가 (MID, 통화, 종류)다. CSA 기본 배정 + MID Override 구조(SCP 연결 C)는 표현되지 않는다(b-4).

### (f) 미결·질문 후보

기존 미결: Q-CALENDAR(PROPOSED, 미질문), Q-SETTLE-CURRENCY(나중에 결정), Q-ACQA-05·06·07·10(PENDING, ACQ-ADMIN-002), Q-ACQA-08(Demo 허락 — 업무 규칙 아님), Q-BATCH-JOBPARAM(기술 식별 — 대장 제외).

새 질문 후보(ID는 가칭, questions.json 미등록):

| 가칭 | 질문 | 권장안 | 대안 | 영향·막히는 작업 |
|---|---|---|---|---|
| Q-FIN-RG-03 | 선지급 기준 3종(승인 완료·매입 전송 완료·매입 확정)과 D-ACQ-005 "승인시점/매입결과"의 관계 | 3종 유지. D-ACQ-005는 "매입 결과(거절·UNKNOWN)에 따른 보류는 정산 정책이 정한다"는 뜻으로 기록 | 2종(승인 완료·매입 확정)으로 축소, 전송 완료 기준 폐기 | BR-FIN-090·091, SETTLEMENT-CONTRACT-001 선지급 모델 |
| Q-FIN-RG-04 | D-ACQ-005 지급 기준 선택이 선지급에만 적용되는지, 입금 후 지급에도 적용되는지 | 선지급 정책에만 적용(입금 후 지급은 입금 확인 + 정산 확정) | 모든 지급에 적용 | BR-FIN-091 |
| Q-FIN-RG-01 | 선지급 뒤 매입 거절·UNKNOWN·후발 취소·기관 미입금 시 보류·회수·상계 규칙과 적용 시점 | 자동 회수 없음, 영향 Payable Hold(ADVANCE_ONLY 또는 ALL_PAYOUT은 위험 등급별) + 대사 Case, 회수·상계는 계약 근거와 2인 승인 | 다음 정산 자동 차감 | BR-FIN-102, SETTLEMENT-001 |
| Q-FIN-RG-02 | TEST 환경 매입분을 정산에 포함할지 | TEST는 Simulator 정산까지만, LIVE 정산과 분리(Statement·Payable 환경 축) | TEST 정산 생략 | BR-FIN-106 |
| Q-FIN-RG-05 | 정산 거래일의 시각 원천(기관 거래일 vs PG 확정 시각 KST)과 Refund 완료일의 정의 | 기관 확정 사실의 업무 시각(기관 거래일) 기준, PAYMENT 수신·확정 시각과 구분(SCP 연결 B). 기관 거래일이 없으면 정산 대기 | PG 확정 시각(KST) 기준 | BR-FIN-077·079, 운영 홈 KPI와의 정합 |
| Q-FIN-RG-06 | 수수료·정산 정책 Assignment의 CSA 기본 + MID Override 수준 | 한도와 같이 CSA_DEFAULT + MID_OVERRIDE, Override 우선·전체 대체(D-MS2-001 준용), 그 밖 모호는 AmbiguousPolicy | 지금처럼 MID별 배정만 | BR-FIN-070·071·076, MASTER V5 |
| Q-FIN-RG-07 | 결제 사실 정합성 보류(5종·ORIGINAL_UNKNOWN)가 걸린 거래의 정산·지급 포함 여부 | 해제·대사 이관 전에는 정산 Entry 생성 대기(매입과 같은 fail-closed) | 정산 포함 + PayoutHold | SETTLEMENT-CONTRACT-001 |
| Q-FIN-RG-08 | Coordinator·Implementer가 정한 매입 규칙의 사용자 확인: PAYMENT_HELD(BR-FIN-019), KRW 외 대기(020), 재봉인 거부(024), 같은 ID 재업로드(028), 재매입 세대 취소 판정(040), 누적 초과 취소 대기(041), CANCEL 재매입(050), 전송 보류(051), 재매입 실행 기한(053), REVIEW_REQUIRED 해소 2인(013) | 일괄 확인(모두 fail-closed 방향 유지) | 항목별 재검토 | 해당 BR을 `확정`으로 올리기 [처리: D-GOV-010 ① 013·024·028·041·050·051·053 확정, D-GOV-010 ③ BR-FIN-019→BR-FIN-138, D-GOV-011 ③ BR-FIN-020 확정, BR-FIN-040 정책 분석 중(D-GOV-010 ④)] |
| Q-FIN-RG-09 | 대사(RECON) 없이 쌓이는 UNKNOWN(매입 결과·전송·이관 취소)의 LIVE 전 처리 | RECON-CONTRACT-001을 LIVE 필수 선행으로 명시, 그 전 LIVE 매입·LIVE 취소 차단 유지 | 임시 운영자 확인 절차 | BR-FIN-034·048·125 |
