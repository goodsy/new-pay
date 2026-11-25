# ACQ-ADMIN-001 매입 조작(재매입 2인 승인·보류 검토 해소·전송 격리 해소·격리 응답 재반영) 명세 (rev 0.8 — B2에서 종결, 남은 일은 ACQ-ADMIN-002, Verifier·Reviewer 대상)

- **rev 0.1** (2026-11-06, Implementer — Phase 1 명세 초안 + 질문 후보). 코드·Gradle 실행 없음. 사용자 질문 Q-ACQA-01~08은 Coordinator 경유 전이며 답이 없는 부분은 구현하지 않는다.
- **rev 0.2** (2026-11-06, Implementer — B1 착수): 사용자 결정 **D-ACQA-001~004**(Q-ACQA-01~04 — 모두 권장안) 반영, Coordinator 결정 C-1~C-9 권장안 채택(tasks.json
  coordinatorDecisions (2)(3) — C-8: 002도 거절되면 Simulator를 고치지 않고 보고). **B1 중 FINANCIAL 절반(B1-F)만 구현·시험 완료**, ADMIN 절반(B1-A — 승인함 새 종류·요청/승인
  2-TX·회복·화면)은 미착수(§17). Q-ACQA-05~08은 PENDING — 그 부분 운영 동작 없음. 새 해석 I-12~I-16, I-5 확정(잠금 순서 변경). §17 실행 기록.
- **rev 0.3** (2026-11-07, Implementer — B1-F 독립 검토 FAIL 지적 반영, 사용자 결정 변경 없음): MJ-1 상계 무결성 세대 분리(세대 0 기존 검사 + 세대 ≥ 1 새 검사 I-17, 지표
  `integrity.reacquisition_netting_mismatch`, 실행 TX 끝 대조), m-1 판정 시각을 모든 잠금 뒤에 읽음(I-18), m-2 결정 INSERT 직전 barrier 경합 시험 + M3 변이, m-3 재매입 vs 봉인
  두 잠금 순서 시험(I-5 근거 문구 정정), m-4 실제 UNKNOWN 레코드 거절, m-5 원 파일 상계 + 불요 한 번씩, m-6 ArchUnit 쓰기 Port 호출자 제한, m-7 실제 레코드로 결정 FK 1452,
  n-1 문서 정합(제목·의사코드 잠금 순서·§6 decision 값), n-5 조작 키 kid 충돌 기동 거부 시험. m-8은 B2 할 일로 기록(§15 L-9). §17.6 실행 기록.
- **rev 0.4** (2026-11-07, Implementer — B1-A ADMIN 절반, 사용자 결정 변경 없음): ADMIN V7·V8(승인 종류 `ACQ_REACQUISITION`·대상 `ACQUISITION_FILE`·`ACQFILE:` 미해결
  하나·지문 `oct1|acd1`·명령 표·권한), 승인 틀 일반화(OwnerServiceApprovalService 종류 분기·OwnerOutcomeApplier target_ref·회복 job 분기·ApprovalService 감사 대상·JPA 대상 매핑),
  요청·승인 2-TX(FINANCIAL 조작 키 호출 DB TX 밖, 조작 키 = 조회 키면 기동 거부), 화면(매입 현황 재매입 요청·승인함 새 종류), Vitest. 새 해석 I-19~I-22. §15 L-10에 리뷰 지적
  n-2·n-3·n-4·n-6 원문과 처리 사유. §17.7 실행 기록.
- **rev 0.5** (2026-11-07, Implementer — B1-A 독립 검토 FAIL 지적 반영, 사용자 결정 변경 없음): M-1 근거 참조 카드 번호 패턴 거부(입력 규칙·명령 Snapshot 불변식·화면), m-1 재매입 요청 400 `ACQUISITION_FILE_NOT_FOUND`·승인 409 `TARGET_NOT_FOUND` 화면 문구, m-2 승인 상세에 근거 참조(사유 문구는 계속 숨김), m-3 시험 공백(자기 승인·TOTP 실패·`ACQFILE:` 실제 두 연결 경합·400 경보), m-4 실행 행 지문 접두·소유 참조를 승인 종류와 대조(Factory·결과 반영·회복 job), m-5 "조회 전용" 문구를 역할별로, NIT n-1~n-5 처리·n-6 기록, 모듈 검사 실패(`LoginServiceSpec` TX 2) 원인 조사 — B1-A와 무관한 기존 불안정성. 새 해석 I-23~I-26. §17.8 실행 기록.
- **rev 0.6** (2026-11-07, Implementer — B1-A 재검토 지적 반영): 사용자 결정 **D-ACQA-005**(Q-ACQA-09 — 재매입 근거 참조는 D-OPS-010과 같이 APPROVER·요청자 본인만 별도 조회 + 조회마다 감사, 그 밖 역할·목록 숨김)로 I-24 대체(일반 상세에서 근거 참조 제거, `/justification` 종류 분기, 화면 "근거 보기"), HT-2 기대값 변경(근거 D-ACQA-005), Coordinator 결정으로 시험 설정 세션 정리 cron 끔(`LoginServiceSpec` 불안정성 원인 제거 — 기대값 불변), NIT: PAYMENT 회복 분기 `conformsTo` 사전 대조. §17.9 실행 기록.
- **rev 0.7** (2026-11-07, Implementer — B2, 사용자 결정 변경 없음): 재매입 파일을 기존 전송·응답 경로로 보내고 결과 반영(전용 경로 없음), L-9 전송 보류(TRANSMISSION_WITHHELD — V19·V20, 다른 세대 파일 행 잠금 아래 표지 재확인, D-ACQ-014), L-6 CancelCandidateRule 세대 ≥ 1 개방(CC-4 기대값 변경 — 근거 L-6), I-17 ③ 재정의(L-8), 배지 3상태(§5.1·AC9·M10 — FINANCIAL Reader + Domain 규칙 `ReacquisitionBadgeRule`, ADMIN 화면·Vitest), n-3 Domain 방어, n-6 counter, I-23 FINANCIAL 카드 패턴 거부, m-A 사유 힌트(I-24 갱신), n-A 근거 조회 감사 실패 시험. 새 해석 I-27·I-28. CANCEL 재매입(I-7)·R-m1(AC5)·N-1 ArchUnit(AC6)은 이번에 하지 않음(§17.10 남은 일). §17.10 실행 기록.
- **rev 0.8** (2026-11-07, Implementer — 종결 정리, 사용자 결정 변경 없음): 사용자 지시로 이 작업은 **B2에서 종결**하고 남은 일(B3·B4, Q-ACQA-10, I-7, AC5 R-m1, AC6 N-1, B2 리뷰 n-1·n-3, m-3 코드 수정)은 후속 작업 **ACQ-ADMIN-002**로 옮긴다(작업 상태 기록은 Coordinator — harness/tasks.json). B2 독립 검토 m-1(승인함 상세가 승인·거절·철회 뒤 낡은 PENDING으로 남음) 수정 + Vitest, m-2·m-3·m-4는 §15 L-9 남은 위험으로 기록만(코드 수정 없음), n-2는 §15 L-7 Demo·배포 순서. §14 범위 갱신. Coordinator Demo E2E 기록. §17.11 종결 기록.
- **S3-DOC-001 문서 정합 표시** (2026-11-25 — 코드·시험·정책 변경 없음, 자기 완료 판정 아님): §12 Q-ACQA-04 권장안의 결정 값 "EXECUTED(NET_ZERO)" 옆에 `→ 정정(답 D-ACQA-004)` 표시만 붙였다(원문 보존, NP-100·PC-58·FIN 대장 진단 (a)-10·(c)-6). Q-ACQA-05~08은 여전히 PENDING(바꾸지 않음).
- 기준: branch `feat/foundation-setup`, HEAD `41d3794`. 선행: ACQUIRING-001 rev 1.1, ACQ-VIEW-001 rev 0.6(DONE), ADMIN-PAYMENT-OPS-001 rev 0.4.
- 표기: **사용자 결정** = `docs/DECISIONS.md`의 결정. **Q-ACQA-n** = 사용자 질문 후보(미결). **I-n** = Implementer 해석(가역, 사용자 승인 아님 — Reviewer 확인). **C-n** = Coordinator가 정할 가역 선택(권장안 표시).
- 근거: D-ACQ-004·009·010·011·013·014, D-ADMIN-003·004·005·007·008, D-OPS-001~009(운영자 보정 취소 선례), D-FFR-002·003, D-ACQV-001·002, ACQUIRING-001 §4·§11·§13(N-1~N-6)·§14(8·11·13·17·19), ACQ-VIEW-001 §3·§4·§11(L-9), TECHNOLOGY-AND-PRINCIPLES §1~§5, ARCHITECTURE-CONVENTIONS §14.

## 0. 한눈에

| 조작 | 지금(ACQUIRING-001 끝) | 이 작업 뒤 | 승인 |
|---|---|---|---|
| (a) 재매입 | 규칙·기록 표만 있고 실행 불가 3겹(I-51) | ADMIN 요청 → 2인 승인 → FINANCIAL 재검사·기록 → 새 버전 파일 봉인 → 기존 전송·결과 경로 | 2인(D-ACQ-011) |
| (b) 보류 REVIEW_REQUIRED 해소 | 자동 판정이 UNIQUE(hold_id)로 종결, 해소 경로 없음(LIVE Gate 11) | 보류 검토 목록 + 해소 명령(재판정 해제 / 대사 이관) | 2인(D-ADMIN-003 "강제 Hold 해제") — 의미는 Q-ACQA-07 |
| (c) TRANSMISSION_QUARANTINED 해소 | 격리 뒤 자동 처리 없음(I-23) | 기관 확인 근거 기록 → TRANSMISSION_UNKNOWN 복귀 → 기존 "확인 먼저" 회복 | Q-ACQA-05 |
| (d) 격리 응답 재반영 | 보존 바이트 재반영 없음(N-6) | 보존 바이트를 같은 규칙으로 다시 판정(append-only 재반영 기록) | Q-ACQA-06 |

Kafka/Redis는 쓰지 않는다(ADR-001). 중복·경합·재시작 안전성은 FINANCIAL·ADMIN 소유 DB의 로컬 TX, 행 잠금, UNIQUE/PK, `@Version`, approvalId별 최초 결정 기록이 지킨다. 브로커·분산 잠금이 이 불변식을 대신한다고 보지 않는다.

## 1. 범위와 제외

| 포함 | 제외(후속·다른 작업) |
|---|---|
| ADMIN→FINANCIAL 서명 채널에 **쓰기 명령** 추가(키는 Q-ACQA-01) | 대사(RECON) Case·Adjustment, UNKNOWN 레코드 재매입(D-ACQ-014 — 대사 미매입 확정 근거 기능 뒤) |
| (a) 재매입: 승인 요청·승인·FINANCIAL 재검사·기록·실행(새 버전 파일 봉인, 순액 D-ACQ-013) | 정합성 보류 5종(ORIGINAL_MISMATCH 등 — REVIEW_REQUIRED가 아닌 보류) 해소(Q-ACQA-07 권장: 범위 밖) |
| (b) REVIEW_REQUIRED 보류 검토 목록(jOOQ) + 해소 | RECEIPT_CONFLICT·RECORD_MISMATCH·NAME_REUSED·UNKNOWN_FILE·AFTER_CONFLICT 격리 해소(내용 상충 — 대사, Q-ACQA-06 권장) |
| (c) TRANSMISSION_QUARANTINED 해소 | RENAMED_BUT_ABSENT 해소(TRANSMISSION_UNKNOWN 회복 경로가 이미 있음) |
| (d) TRANSMISSION_QUARANTINED 사유로 격리된 응답 재반영 | 실제 매입사 문의 연동, LIVE 매입(D-ACQ-008 차단 유지) |
| R-m1 PK 경합 시험, N-1 markAccepted 전 경로, 승인 표 FK·복합 UNIQUE(n-4), L-9 배지 | 감사 화면 targetRef 표시(ACQ-VIEW-001 L-13 — ADMIN audit 후속) |
| ADMIN 승인함 새 종류·화면(매입 현황 상세 버튼, 보류 검토 탭), Vitest, 배치마다 Demo 화면 | Simulator 변경(허용 경로 밖 — §10 C-8) |

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| D-ACQ-011 | 재매입(파일 전체 거절 뒤 포함) = 2인 승인. 이 작업이 I-51 세 겹(채널 없음·INSERT 1142·실행 거절)을 **함께** 연다 |
| D-ACQ-013 | 재매입 레코드 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(`ReacquisitionRule.netAmountMinor`), 승인 기록에 금액 없음(I-54). 실행 TX가 결제별 행 잠금 아래에서 계산 |
| D-ACQ-014 | UNKNOWN 대상 거절(UNKNOWN_NOT_RECONCILED 유지), 실행 직전 `revalidate`(TARGET_CHANGED), 세대를 넘는 ACCEPTED 하나(V14 PK + 앱 규칙) |
| D-ACQ-004·010 | 자동 재전송 없음 — 재매입은 **새 버전·새 파일 ID**, 격리 해소도 같은 파일 ID 같은 봉인 바이트만 다시 보냄(I-10). 늦은 결과 append-only |
| D-ACQ-009 | CANCEL 레코드 재매입은 원 SALE ACCEPTED일 때만(I-7) |
| D-ADMIN-003 | "강제 Hold 해제"는 2인 승인 목록에 있다 → (b) 해소는 2인. **해소의 금융 의미는 Q-ACQA-07** |
| D-ADMIN-004 | 승인 직전 TOTP 재입력, 서버 세션·CSRF |
| D-ADMIN-007 | ADMIN이 승인 상태 관리 + FINANCIAL도 요청자≠승인자·승인 ID·대상 버전을 다시 검사하고 자기 기록을 남긴다. ADMIN 단독 실행 불가 |
| D-FFR-002 | 새 표마다 runtime 최소 권한 migration + `FinancialDatabaseAccountSpec` 기대값 **추가** |
| D-FFR-003 | ORIGINAL_UNKNOWN 자동 해제 그대로. 불일치(REVIEW_REQUIRED)만 운영자 검토 — 이 작업의 (b) |
| D-ACQV-001·002 | 상세·레코드 조회 감사·역할 그대로, 카드사 쪽 값(승인번호·기관 거래번호·발급사·카드 종류·할부)은 조작 화면·승인함·명령 본문에도 넣지 않는다 |
| **D-ACQA-001** (Q-ACQA-01) | "조작 전용 키 따로": FINANCIAL `admin-channel.operation-keys`(필수, 조회 키·결제 사실 키와 kid·바이트 모두 달라야 기동). 조작 경로 `/internal/v1/admin/acquiring-operations/**`는 권한 `ADMIN_OPERATION`(조작 키 서명)만, 그 밖 조회 경로는 `ADMIN_SERVICE`(조회 키)만 — 서로 401 |
| **D-ACQA-002** (Q-ACQA-02) | 재매입 요청 OPERATOR, 승인 APPROVER + 직전 TOTP(ADMIN 절반 B1-A에서 적용) |
| **D-ACQA-003** (Q-ACQA-03) | 승인 하나 = 같은 원 파일 레코드 1~50건, 하나라도 거절이면 승인 전체 REJECTED(부분 실행 없음) — `ReacquisitionExecutionRule.plan`, 승인 표 PK(approval_id, file_id, seq) |
| **D-ACQA-004** (Q-ACQA-04) | 실행 시점 순액 0 → 레코드 없이 `acquisition_reacquisition_not_required` 종결(PK 거래·종류), 묶음 전체가 0이면 결정 NOT_REQUIRED(파일 없음), 이후 요청은 ALREADY_NOT_REQUIRED |
| **D-ACQA-005** (Q-ACQA-09, 2026-11-07) | "보정 취소와 같게 (Recommended)": 재매입 승인 상세의 근거 참조(evidenceRef)는 D-OPS-010과 같이 APPROVER와 요청자 본인만 별도 조회로 보고 조회마다 감사, 그 밖 역할·목록에는 숨김 — rev 0.6에서 I-24를 대체(§5.2, §11 I-24, §17.9) |

## 3. 조작별 설계

### 3.1 공통: 2인 승인 흐름(ADMIN 승인함 D-ADMIN-007 재사용)

```
OPERATOR(화면) ─요청─▶ ADMIN TX: 승인 행 PENDING + 명령 행(고정 대상 pinned_target) + 감사
APPROVER(승인함, TOTP) ─승인─▶ A: TX 밖 FINANCIAL 대상 재조회(서명 GET)
                           B(TX1): 승인 행 FOR UPDATE → 참여자·TOTP → pinned vs 현재 비교(다르면 INVALIDATED(TARGET_CHANGED))
                                    → 본문(approvalId·대상·대상 버전·요청자·승인자·사유 코드·근거·approvedAt·executeBefore) 저장 → EXECUTING
                           C: TX 밖 서명 POST(FINANCIAL 쓰기 명령)
                           D(TX2): 결과 반영 EXECUTED / OWNER_REJECTED(code) / EXECUTION_UNKNOWN(회복 job)
FINANCIAL: 서명 검증(채널) → approvalId 결정 기록 조회 → 대상 잠금 → D-ADMIN-007 재검사 + 조작별 규칙 → 업무 효과 + 결정 기록 같은 로컬 TX
```

- ADMIN은 `OPERATOR_CORRECTION_CANCEL`과 같은 **OWNER_SERVICE 실행 모델**에 새 종류를 더한다(I-1): `ACQ_REACQUISITION`, `ACQ_HOLD_REVIEW_RESOLUTION`, `ACQ_TRANSMISSION_QUARANTINE_RESOLUTION`(이름은 가역). 요청 역할·승인 역할은 Q-ACQA-02.
- `ApprovalTargetKind`에 `ACQUISITION_RECORD_SET`(원 파일 ID + 레코드 순번 목록), `PAYMENT_FACT_HOLD`(hold id), `ACQUISITION_FILE`(file ID) 추가. 미해결 하나 규칙(`unresolved_key` 생성 열 확장): 같은 (거래, 종류) 레코드·같은 hold·같은 파일에 미해결 승인은 하나(ADMIN V7).
- **FINANCIAL의 승인 사실 재확인**(질문 3):
  1. 서명: ADMIN 채널 서명 v1(ACQ-VIEW-001 §3.1) — 쓰기 키는 Q-ACQA-01. 서명이 "ADMIN이 보냈다"를 증명한다. FINANCIAL은 ADMIN을 동기 호출하지 않는다(D-CANCEL1-001과 같은 방향).
  2. D-ADMIN-007 재검사: 요청자≠승인자(Domain + DB CHECK), 대상 버전 일치, `approvedAt ≤ now + 창`, `now < executeBefore`, `executeBefore − approvedAt ≤ max-execution-window`(D-OPS-002 선례 — I-2).
  3. **approvalId별 최초 확정 결정**(D-OPS-005 선례 — I-3): FINANCIAL 새 표 `acquisition_ops_decision`(UNIQUE approval_id, 내용 hash). 같은 approvalId·같은 내용 → 기존 결정 재생, 다른 내용 → `APPROVAL_CONFLICT`(파생, 비종료). 409는 커밋된 결정이 뒷받침할 때만, 그 밖 일시 실패는 503.
  4. 기한 확인 전용 호출(D-OPS-009 선례): 결정 NONE + 기한 지남 → FINANCIAL이 자기 시각으로 `REJECTED(APPROVAL_EXECUTION_EXPIRED)` 기록, 업무 효과 없음 → ADMIN 미해결 차단 해제.
  5. 대사: ADMIN이 FINANCIAL 결정 목록(서명 GET, watermark)을 ADMIN 승인과 주기 대조(D-OPS-008 선례) — C-5(배치 B5).
- 감사: ADMIN `admin_audit_event`(target_ref = 파일 ID·`fileId#seq`·hold 참조 — I-15 형식 확장), FINANCIAL 결정 기록·업무 기록(append-only). 사유 자유 문구는 ADMIN에만, FINANCIAL에는 사유 **코드**·근거 참조만(V12 열 그대로 — I-4).

### 3.2 (a) 재매입

**금융 의미**: 기관이 종결 거절한 매입 대상(원승인 또는 전송 후 취소)을 같은 업무일의 **다음 버전 파일**로 한 번 더 보낸다. 매입 확정은 여전히 기관 ACCEPTED뿐이다. 승인은 "보내도 된다"는 결정이지 매입 사실이 아니다.

**대상 조건**(규칙 `ReacquisitionRule.decide` — 기존 RQ-1..9 유지):
- 레코드 상태 REJECTED·FILE_REJECTED, 그 (거래, 종류)의 가장 높은 generation, 어느 세대도 ACCEPTED 아님(매입 확정 표지 없음), 미실행 승인 없음.
- UNKNOWN → 거절(UNKNOWN_NOT_RECONCILED), CONFLICT·AWAITING → 거절.
- CANCEL 레코드는 원 SALE의 현재 ACCEPTED(표지)가 있을 때만(D-ACQ-009 — I-7).
- 승인 단위: Q-ACQA-03(권장: 같은 원 파일의 레코드 묶음 1~50건, 하나라도 대상이 바뀌면 승인 전체 무효).

**대상 버전**(pinned): 레코드마다 (generation, status, 마지막 결과 행 entry_no) + 원 파일 결과 축. ADMIN 승인 단계 A가 FINANCIAL `GET …/reacquisition-targets`로 다시 읽어 비교한다.

**FINANCIAL 실행 TX**(C-1 권장: 승인 기록과 새 버전 봉인을 같은 TX — PAYMENT 선례 "STARTED 결정 + 취소 행 같은 TX"와 같은 모양):
```
REQUIRES_NEW, READ COMMITTED
결정 기록 조회(approvalId) → 있으면 재생
원 파일 행 FOR UPDATE(응답 TX와 같은 행 — 늦은 결과와 직렬화, 없으면 REJECTED(TARGET_NOT_FOUND)) → lane FOR UPDATE(봉인과 같은 잠금 — 버전 번호 직렬화)
 → 결정 기록 재조회(같은 승인 ID 경쟁 TX가 먼저 커밋했으면 재생) → 결제별 직렬화 행 FOR UPDATE(payment_id 오름차순 한 문장)
 → 판정 시각 now = 모든 잠금 뒤 시계(I-18 — 기한 판정·decided_at·봉인 시각)
 → 레코드마다 revalidate(D-ACQ-014) + D-ADMIN-007 재검사 → 하나라도 실패 → REJECTED(code) 결정, 효과 없음
 → 순액 = netAmountMinor(원승인액, 실행 시점까지 받은 모든 취소: 상계·취소 매입 불요·전송 후 취소 각 한 번)
 → 순액 0 레코드 처리 = Q-ACQA-04
 → 새 버전 파일(버전 = 그 업무일 최대 + 1, originalFileId = 원 파일) + 레코드(generation + 1, reacquisitionOf = {원 fileId}#{seq}) + 바이트
 → 재매입 상계 무결성 대조(I-17 — 어긋나면 전체 rollback, 결정 없음)
 → EXECUTED(파일 있음)·NOT_REQUIRED(전부 순액 0) 결정 행 → 승인 기록 행(레코드마다 — 결정 FK) → 불요 종결 행 → COMMIT
전송: 기존 transmitPending(SEALED 새 파일) — TX 밖, 기존 lease·@Version·확인 먼저
결과: 기존 AcquirerResponseService → ACCEPTED면 같은 TX에서 markAccepted(N-1, I-56)
```
- 잠금 순서(rev 0.2 확정 — I-5): **원 파일 행 → lane → 결제별 행**(승인 하나 = 원 파일 하나라 파일 행은 하나). 봉인은 lane → 결제별 행, 응답 TX는 파일 행만, 취소 Inbox는 결제별 행만 잡아 순환 없음. 외부 호출 중 잠금 없음.
- 기존 정기 봉인의 `OUT_OF_ORDER`(업무일 순서)는 정기 파일(버전 001)만 대상. 재매입 파일은 이미 봉인한 업무일에만(I-6).
- 멱등: approvalId UNIQUE(결정 표) + 승인 표 UNIQUE(거래, 종류, 새 세대)·(파일, 순번, 새 세대) + 레코드 표 UNIQUE(거래, 종류, generation). 재시도는 새 TX에서 커밋된 결정 재생.
- 경합: 두 승인이 같은 레코드 → 두 번째 1062 → 재시도 → 결정 재생 또는 ALREADY_REACQUIRED(409). 실행 vs 원 파일 늦은 결과 → 파일 행 잠금으로 직렬화: 결과 먼저면 revalidate가 TARGET_CHANGED, 실행 먼저면 늦은 결과는 기존 규칙(같은 결과 중복 / 다른 결과 CONFLICT — 원 세대 레코드에 기록).
- **열리는 세 겹(I-51 해제)**: ① `UnavailableReacquisitionApprovalVerifier` 삭제 — 채널 인증(ADMIN_SERVICE_WRITE)이 그 역할(C-7), ② runtime INSERT 권한(V18), ③ `ExecuteReacquisitionUseCase` 실제 구현.

### 3.3 (b) 보류 REVIEW_REQUIRED 해소

**배경**: ORIGINAL_UNKNOWN 보류는 원승인 도착 + MATCH면 자동 RELEASED(D-FFR-003). 불일치면 REVIEW_REQUIRED로 판정이 **종결**된다(UNIQUE(hold_id) — 같은 보류에 RELEASED를 더 쓸 수 없음). 그 Payment의 모든 매입 후보는 PAYMENT_HELD로 무기한 대기한다(C-5 of ACQUIRING-001).

**권장 의미(Q-ACQA-07 권장안 — 답 전 구현 금지)**:
- 결과 두 가지만, 둘 다 새 표 `payment_fact_hold_review`(append-only, UNIQUE hold_id, FK hold·release 판정·결정 기록) 1행:
  - `RELEASED_ON_REVIEW`: FINANCIAL이 실행 TX에서 `HoldReleaseRule`을 **지금 이력으로 다시** 적용해 불일치가 없을 때만(예: 원승인 자체 보류 ORIGINAL_HELD가 그 사이 풀린 경우). 운영자가 불일치를 "괜찮다"고 덮지 않는다. 사실·금액은 바꾸지 않는다.
  - `RETAINED_FOR_RECON`: 검토 완료, 보류 유지·매입 제외 지속, 대사 이관 표시(목록에서 "검토 대기"에서 빠짐). 대사 Case는 RECON 작업.
- 매입 봉인 쪽 "미해제 보류" 조회(`JpaPaymentFactAcquisitionReadStore`)가 RELEASED 해제 **또는** RELEASED_ON_REVIEW를 보도록 바꾼다(LIVE Gate 11). 해제 뒤에도 후보 규칙의 비정합 대기·통화·LIVE 검사는 그대로 적용된다(해제는 보류 장벽만 걷는다).
- 대상 버전: (hold_id, release 판정 id, mismatch 목록). 실행 TX: 결제별 직렬화 행 FOR UPDATE(Inbox·봉인과 같은 행) → 재판정 → 기록.
- 경합: 같은 Payment의 봉인 TX와 결제별 행으로 직렬화 — 해제 커밋 뒤 봉인만 그 Payment를 후보로 본다. 같은 hold 두 명령 → UNIQUE(hold_id) 1062 → 재시도 → 재생.
- 모듈: `paymentfact`(보류 소유). 웹 진입은 `paymentfact.adapter.web.admin`, 보안 체인은 기존 `/internal/v1/admin/**` 공유(C-4).
- 화면 목록: 새 탭 "매입·대사 > 보류 검토"(jOOQ Reader — Payment ID·보류 사유·불일치 이름·관련 거래 ID·금액·판정 시각; 카드사 값 없음).

### 3.4 (c) TRANSMISSION_QUARANTINED 해소

**배경**: 같은 파일 ID 최종명에 봉인과 다른 바이트가 있었다(I-23). 기관이 그 다른 내용을 받아 처리했는지 FINANCIAL은 모른다 → 자동 처리 없음.

**권장 의미(Q-ACQA-05)**: 운영자가 기관 확인 근거(문의 번호 등 — 근거 참조 형식 D-OPS-004와 같음)를 붙여 요청, 2인 승인 → FINANCIAL이 전송 축 `TRANSMISSION_QUARANTINED → TRANSMISSION_UNKNOWN`으로 되돌리고 해소 기록(append-only `acquisition_quarantine_resolution`, UNIQUE (file_id, approval_id), 파일당 해소 횟수 기록).
- 그다음은 **기존 회복 경로**(I-24·I-25 "확인 먼저"): 최종명 되읽기 hash = 봉인 → TRANSMITTED(VERIFIED_FINAL_PRESENT) / 없음 → 같은 봉인 바이트 재업로드 / 여전히 다름 → 다시 격리. 운영자가 "TRANSMITTED"를 직접 지정하는 경로는 만들지 않는다(전송 완료 근거는 I-9의 세 가지뿐).
- 상태 전이는 `AcquisitionFile` Aggregate에 새 전이 `resolveQuarantine`(QUARANTINED만, `@Version`) — Domain 규칙 시험 먼저.
- 대상 버전: (file version 열, transmission_state). 경합: 전송 claim은 QUARANTINED를 점유하지 않으므로 해소 TX와 겹치지 않는다. 같은 파일 두 해소 → @Version + 상태 조건 → 두 번째 TARGET_CHANGED.

### 3.5 (d) 격리 응답 재반영(N-6)

**권장 의미(Q-ACQA-06)**: 대상 = `quarantine_reason = TRANSMISSION_QUARANTINED`인 응답만, 그 파일이 더는 QUARANTINED가 아닐 때. 보존 바이트(`acquisition_response.content`)를 **수신 때와 같은 규칙**(`AcquirerResponseRule`·`RecordResultRule`)으로 다시 판정한다. 운영자는 내용을 고치지 않고 판정 결과를 고르지 않는다.
- 기록: 새 표 `acquisition_response_reapplication`(append-only, **UNIQUE response_id** — 응답당 1회, 처분 APPLIED/QUARANTINED + 새 사유). 원 응답 행은 불변. 결과 행(`acquisition_record_result`)은 원 응답 id를 출처로 append, ACCEPTED면 같은 TX에서 markAccepted(N-1).
- 재판정이 hash 불일치 등으로 다시 격리되면 그 사유를 기록(정상 — 기관이 받은 것이 다른 바이트였다는 증거).
- 실행 TX: 파일 행 FOR UPDATE(응답 TX와 같은 잠금) → TRANSMITTING이면 미룸 → 재판정 → 기록. 내부 순서는 응답 id 오름차순(ACK → 결과 회차, 기존 처리 순서).
- 다른 격리 사유(HASH_MISMATCH·RECORD_MISMATCH·RECEIPT_CONFLICT·AFTER_CONFLICT 등)는 재반영 대상이 아니다(내용 상충 — 대사).

## 4. 채널(ADMIN→FINANCIAL 쓰기 명령)

| 항목 | 내용 |
|---|---|
| 서명 | v1 그대로(canonical·시각 창·크기·query 금지·원인 비구별 401). 명령 멱등은 nonce 대신 approvalId 결정 기록(D-OPS-001·005 선례) |
| 키 | **Q-ACQA-01** — 권장: 쓰기 전용 별도 키(kid·바이트 모두 조회 키·Inbox 키·payment-ops·master-ops 키와 달라야 기동). FINANCIAL 권한 `ADMIN_SERVICE_WRITE`는 쓰기 키로 서명된 요청에만, 쓰기 경로는 그 권한만 |
| 경로(FINANCIAL) | `GET /internal/v1/admin/reacquisition-targets/{fileId}`(대상 재조회) · `POST /internal/v1/admin/reacquisitions` · `POST /internal/v1/admin/hold-reviews` · `POST /internal/v1/admin/quarantine-resolutions` · `POST /internal/v1/admin/response-reapplications` · `GET /internal/v1/admin/ops-decisions/{approvalId}` · `POST /internal/v1/admin/ops-decisions/{approvalId}/deadline-confirmation` · `GET /internal/v1/admin/ops-decisions/after/{watermark}`(B5) · `POST /internal/v1/admin/hold-review-searches`(조회) |
| 본문 | 엄격 JSON(모르는 필드·중복 key·뒤 토큰·강제 변환·null 거부), 16 KiB. 응답 `{"error":"<CODE>"}`만, 값 echo 없음 |
| permit | 쓰기 permit 별도(`write-permits`, Demo 1, 대기 0 → 503 `ADMIN_WRITE_BUSY`) — 조회 permit·Inbox 연결과 분리 |
| 응답 문법 | ADMIN `FinancialResponseShape`에 새 응답 모양 추가, FINANCIAL 저장 문법 = ADMIN 허용 문법(ACQ-VIEW-001 §4.7 방식 대조표) |
| golden | `admin-financial-ops-api-v1.golden.json`(요청·응답·ADMIN 상태 매핑, harness Python 독립 생성 — ADMIN-PAYMENT-OPS-001 §4.19 ③과 같은 방식) |

## 5. 화면

### 5.1 매입 현황 상세(기존 `AcquisitionsScreen`에 버튼 추가)

| 위치 | 보이는 조건(서버가 계산한 값만 사용 — I-8) | 버튼·표시 | 역할 |
|---|---|---|---|
| 레코드 행 | `reacquisition.eligible = true`(REJECTED·FILE_REJECTED 최신 세대, 표지 없음, 미해결 승인 없음) | 체크박스 + "재매입 요청"(선택 묶음) | 요청 역할(Q-ACQA-02) |
| 레코드 행 | UNKNOWN | 비활성 + "대사로 미매입 확인 뒤 가능(D-ACQ-014)" | 모두 |
| 레코드 행 | 미해결 승인 있음 / 새 세대 있음 | 배지 "재매입 승인 대기"·"재매입 진행 중(세대 n, 파일 …002)" | 모두 |
| 파일 요약 | 전송 축 TRANSMISSION_QUARANTINED, 미해결 해소 승인 없음 | "격리 해소 요청" | 요청 역할 |
| 응답 기록 표 | 사유 TRANSMISSION_QUARANTINED, 재반영 기록 없음, 파일이 QUARANTINED 아님 | "격리 응답 다시 반영" | Q-ACQA-06 |

**L-9 배지 규칙**(FINANCIAL Reader 계산 — I-9): "재매입 검토 대상" = 위 eligible 레코드가 하나 이상. 새 세대 레코드가 있으면 "재매입 진행 중", 새 세대 ACCEPTED(표지가 새 세대를 가리킴)면 **배지 없음**. 원 레코드 상태 값은 그대로 REJECTED로 보인다(이력 불변).

**화면 문구(rev 0.5 — 리뷰 m-5)**: 재매입 요청 패널이 생겨 ACQ-VIEW-001의 "조회 전용" 문구(배지 설명·PageHeader·KDoc)를 역할별로 바꿨다 — OPERATOR: 머리말 "…조회하고, 거절된 레코드의 재매입을 요청합니다", 배지 "아래에서 재매입을 요청하면 승인함에서 다른 관리자(APPROVER)가 승인합니다"; 그 밖 역할: 머리말 "…조회합니다", 배지 "재매입은 운영자(OPERATOR)가 이 화면에서 요청하고, 다른 관리자(APPROVER)가 승인함에서 승인합니다". 근거 참조 칸 힌트에 "카드 번호 금지"(I-23). 미리 보기·요청 중에는 체크박스 비활성(n-4). 요청 400 `ACQUISITION_FILE_NOT_FOUND`·입력 거절 필드(근거 참조·사유 코드)는 고정 문구로 보인다(m-1).

### 5.2 승인함(`ApprovalsScreen`)

- 새 종류 목록 이름: "재매입", "보류 검토 해소", "전송 격리 해소". 고정 값 vs 승인 시점 현재 값(레코드별 세대·상태, hold 불일치 목록, 파일 전송 상태)을 나란히, 다르면 "승인 시 무효" 경고.
- 재매입: 원 파일 ID, 레코드(순번·거래 ID·종류·원승인액·지금까지 취소 합·**예상 순액**(표시용 — 실행 TX가 다시 계산, I-10)), 사유 코드·근거.
- 결과: EXECUTED(+ 새 파일 ID 링크) / OWNER_REJECTED(code 문구) / EXECUTION_UNKNOWN "자동 확인 중".
- (rev 0.5) 예상 순액 칸은 고정 값(요청 시점)을 그대로 두고 현재 값은 "현재 같음 / 현재 N원 / 현재 -"로 따로 적는다(조용한 대체 없음 — 리뷰 n-5). 승인 A 단계 409 `TARGET_NOT_FOUND`는 전용 문구("대상을 찾을 수 없어 처리하지 않았습니다(상태는 바뀌지 않았습니다)…" — 리뷰 m-1).
- (rev 0.6 — **D-ACQA-005**, rev 0.5 I-24 대체) 근거 참조는 상세(조회 4역할 공통)에 보이지 않는다. APPROVER 역할과 요청자 본인에게만 "근거 보기" 버튼을 두고(표시만 — 서버가 판정), 누르면 `GET /api/approvals/{id}/justification`(운영자 보정 취소 D-OPS-010과 같은 endpoint)으로 받은 근거 참조를 화면 상태에만 잠깐 보인다(저장소·URL 없음). 거부(403)는 오류 문구만. 그 밖 역할에는 버튼이 없다. 사유 문구는 계속 보이지 않는다.

### 5.3 확인 문구(예 — 가역 문구, 의미는 결정에 묶임)

- 재매입 요청: "선택한 N건을 같은 업무일의 새 버전 파일로 다시 보냅니다. 금액은 실행 시점의 순액(원승인 − 받은 취소)으로 정해집니다. 매입 확정은 매입사 결과로만 됩니다. 2인 승인 뒤 실행됩니다."
- 격리 해소: "매입사에 확인한 근거를 입력하세요. 승인되면 이 파일은 '전송 결과 모름'으로 돌아가고, 시스템이 매입사 파일을 다시 읽어 같은 내용일 때만 전송 완료로 봅니다."
- 보류 해소(권장안 기준): "해제는 지금 사실로 다시 확인해 불일치가 없을 때만 됩니다. 불일치가 남으면 '대사 이관'으로 기록되고 매입 제외가 유지됩니다."
- 재반영: "보존된 매입사 응답을 수신 때와 같은 규칙으로 다시 판정합니다. 결과를 고를 수 없습니다."

## 6. 표·migration·권한(번호는 착수 시 재확인)

| 파일 | 내용 | runtime 권한 |
|---|---|---|
| FINANCIAL `V17__acquisition_reacquisition_execution.sql`(실제 이름) | `acquisition_ops_decision`(seq PK, UNIQUE approval_id, kind CHECK(REACQUISITION), decision CHECK(**EXECUTED·NOT_REQUIRED·REJECTED** — 모양 CHECK: EXECUTED ⇔ result_file_id 있음, NOT_REQUIRED·REJECTED ⇔ 없음, REJECTED만 rejection_code), result_file_id FK → 파일, content_hash `acd1:`, requested_by·approved_by, approved_at, execute_before(> approved_at), decided_at, recorded_at DEFAULT NOW(3), origin(EXECUTION_COMMAND·DEADLINE_CONFIRMATION — 후자는 REJECTED(APPROVAL_EXECUTION_EXPIRED)만)). **요청자 ≠ 승인자 CHECK를 두지 않는다**(rev 0.3 — n-1): 같은 사람 명령도 FINANCIAL이 `REJECTED(SAME_PERSON)` 결정으로 **기록**해야 하므로(D-ADMIN-007 자기 기록·재전송 재생) 이 표가 막으면 거절을 남길 수 없다. 사람 분리는 Domain(`ReacquisitionExecutionRule`) + 효과 행인 승인 표 CHECK `ck_acquisition_reacquisition_approval_people`(V12)가 지킨다 | SELECT, INSERT(V18) |
| 〃 | `acquisition_reacquisition_approval` 재정의(표가 비어 있음 보장 — V13이 INSERT를 막아 왔다, I-11): PK (approval_id, file_id, seq), **FK approval_id → acquisition_ops_decision**, FK (file_id, seq) → 레코드, UNIQUE (transaction_id, record_type, new_generation), **UNIQUE (file_id, seq, new_generation)**(n-4 복합), 기존 CHECK 유지 | SELECT, INSERT(V18 — I-51 해제) |
| 〃 | `acquisition_reacquisition_not_required`(Q-ACQA-04 답에 따라 — 순액 0) | SELECT, INSERT |
| `V19__payment_fact_hold_review.sql` | `payment_fact_hold_review`(UNIQUE hold_id, FK hold·hold_release(REVIEW_REQUIRED 행)·결정, decision CHECK) | SELECT, INSERT(V20) |
| `V21__acquisition_quarantine_resolution.sql` | `acquisition_quarantine_resolution`(PK id, UNIQUE (file_id, approval_id), FK 파일·결정), `acquisition_response_reapplication`(UNIQUE response_id, FK 응답, 처분·사유 CHECK) | SELECT, INSERT(V22). `acquisition_file` 열 권한 변화 없음(transmission_state 이미 UPDATE 가능) |
| ADMIN `V7__acquiring_ops_approval.sql` | kind·target_kind CHECK 확장, `unresolved_key` 생성 열 확장, `admin_acquiring_ops_command`(INSERT-only, pinned_target JSON) | V8: SELECT, INSERT |

`FinancialDatabaseAccountSpec`·`MainMigrationLocationSpec` 기대값은 **추가만**(기존 단언 불변). 승인 표 INSERT 1142 단언(AQ-G2)은 I-51 해제로 바뀌므로 근거(D-ACQ-011 "ADMIN 입력은 ACQ-ADMIN-001")를 기록하고 새 단언(채널 없는 INSERT 경로 없음 — ArchUnit + 서명 없는 요청 401)으로 대체한다. 완화가 아니라 결정된 개방이다.

## 7. 시험(acceptanceCriteria 대응)

| AC | 시험 | 핵심 |
|---|---|---|
| 1 재매입 2인(양쪽) | ADMIN `AcquiringOpsApprovalSpec`(같은 사람·역할·TOTP·대상 변경 INVALIDATED), FINANCIAL `ReacquisitionCommandDbSpec`(요청자=승인자 409+기록, 대상 버전 불일치, 기한 지남, 같은 approvalId 재생·다른 내용 CONFLICT) | 실제 MySQL runtime 계정 |
| 2 REVIEW_REQUIRED 해소 | `HoldReviewDbSpec`(재판정 MATCH만 해제, 불일치 RETAINED, 봉인과 경합 — 결제별 행) | Q-ACQA-07 답 뒤 |
| 3 FINANCIAL 기록·서명 | `AdminWriteChannelSpec`(조회 키로 쓰기 401, 쓰기 키로 조회 경로 — Q-ACQA-01 답대로, golden 서명), 결정 기록 불변식(409 ⇔ 커밋된 결정) | |
| 4 격리 해소 | `AcquisitionTransmissionRuleSpec` 새 전이, `QuarantineResolutionDbSpec`(해소 → UNKNOWN → transmitPending: 같은 바이트 TRANSMITTED / 없음 재업로드 / 다른 바이트 재격리 — 실제 시험 SSHD) | |
| 5 **R-m1 PK 경합** | `AcceptedRecordRaceDbSpec`: 같은 (거래, SALE)의 세대 0·세대 1 레코드가 각자 다른 파일에서 결과 대기 → 두 응답 TX를 `CyclicBarrier`로 표지 INSERT 직전에 맞춤 → 한쪽 1062 → 재시도 → **표지 1행 + 다른 쪽 CONFLICT 행**(관찰 ACCEPTED), 두 TX 모두 커밋 | 지금 규칙으로는 세대 0이 종결 거절이어야 세대 1이 생기므로 "둘 다 대기" 상태는 **명시적 상태 Fixture**(`AcquisitionRaceFixture.twoGenerationsAwaiting` — 의도: DB 이중 방어만 검사, 앱 규칙 우회 사실을 이름·KDoc에 기록, Test Fixture Policy) |
| 6 **N-1** | ArchUnit: `acquisition_record_result` ACCEPTED 쓰기는 `RecordResultWriter` 하나만, 그 메서드가 같은 TX에서 `markAccepted` 호출(재반영·재매입 결과 경로 포함). 변이 "재반영 경로가 markAccepted 생략" → RED | |
| 7 순액·UNKNOWN | `ReacquisitionExecutionDbSpec`: 승인 뒤·실행 전 취소 도착 → 실행 순액에 반영, 상계·불요·전송 후 취소 각 한 번, UNKNOWN 대상 거절, 실행 직전 늦은 ACCEPTED → TARGET_CHANGED, 실행 TX 중간 실패 rollback(파일·레코드·승인·결정 0행) | |
| 8 FK·복합 UNIQUE·재반영 | 마이그레이션 시험: 고아 approval_id FK 1452, (파일, 순번, 새 세대) 1062, 재반영 response_id 1062; `ResponseReapplicationDbSpec`(TRANSMISSION_QUARANTINED만, 같은 규칙, 응답당 1회, 경합 2 스레드) | |
| 9 **L-9 배지** | `AcquisitionFileViewReaderSpec` 추가: 검토 대상 → 진행 중 → 새 세대 ACCEPTED 뒤 꺼짐(동치 기준 = Domain 규칙), Vitest 배지 3상태 | |

공통: 응답 유실(FINANCIAL 커밋 뒤 응답 끊김 → ADMIN EXECUTION_UNKNOWN → 회복 job 결정 조회로 EXECUTED), FINANCIAL 장애 503, 키·카드값 비노출(로그·응답·감사), 화면 Vitest(역할별 버튼·확인 문구·TOTP·CSRF).

## 8. 8대 원칙 적용표

| 원칙 | 적용 | 증거(시험) |
|---|---|---|
| Financial Consistency | 순액 = 실행 시점 규칙(D-ACQ-013), 세대를 넘는 ACCEPTED 하나(PK+앱), 확정 사실·원 레코드·보류 원본 불변, 해제는 재판정 MATCH만(권장) | AC5·AC7, HoldReviewDbSpec |
| Transaction Safety | FINANCIAL 업무 효과 + 결정 기록 같은 로컬 TX, ADMIN 2-TX(외부 호출 TX 밖), 1062는 잡고 계속하지 않음(TX 전체 rollback) | 중간 실패 rollback, 변이 "1062 잡고 계속" |
| Idempotency | approvalId 최초 결정(UNIQUE), 승인 표 복합 UNIQUE, 재반영 UNIQUE(response_id), 해소 UNIQUE(file, approval) | 같은/다른 내용 재전송, 재시작 |
| Concurrency Control | 잠금 순서 lane → 파일 행 → 결제별 행, @Version(파일), PK 경합 재시도 | R-m1 CyclicBarrier, 실행 vs 늦은 결과, 두 승인 동시 |
| Recoverability | EXECUTION_UNKNOWN → 결정 조회·재전송·기한 확인, 격리 해소 뒤 기존 "확인 먼저" 회복, 재매입 파일은 기존 전송 회복 | 응답 유실·FINANCIAL 재시작 |
| Reconciliation | UNKNOWN 재매입 거절(대사 우선), RETAINED_FOR_RECON, 결정 목록 대사(B5), 재반영 결과도 같은 규칙 | 대사 발견 kind 시험(B5) |
| Auditability | ADMIN 감사(요청·승인·거절·실행·불명), FINANCIAL 결정·업무 기록(요청자·승인자·사유 코드·근거·대상 버전), 사유 원문은 ADMIN만 | 감사 행 단언, 감사 실패 처리 |
| Observability | 지표 `paycore.financial.admin_channel.write{endpoint,result}`, 결정 결과 분류, ADMIN `ops.unresolved{kind}` — label에 ID·금액 없음 | 지표 delta·label 키 시험 |

## 9. 기술 메모(TECHNOLOGY-AND-PRINCIPLES §3~§5 — 이번 변경에 연결)

- **JPA — 순수 Domain과 Entity 분리**: 이번 작업에서 하는 일 → `AcquisitionFile`(Domain)에 `resolveQuarantine` 전이를 더하고, adapter의 `JpaAcquisitionFileEntity`가 Mapper로 상태와 version을 반영한다. 예 → 해소 TX가 파일을 읽어 Domain 전이 후 저장하면 `@Version`이 함께 올라, 같은 순간 다른 해소 TX는 `OptimisticLock`으로 실패하고 다시 읽어 "이미 해소됨"을 본다. 이유 → 상태 규칙은 Domain 시험으로, 동시성은 DB로 지킨다. 주의 → 분리한 Domain 객체 변경은 자동 추적되지 않으므로 Mapper가 version을 반드시 실어야 하고, stale 객체로 최신 Entity를 덮지 않는다.
- **JPA — append-only Entity**: 결정·승인·해소·재반영 기록 Entity는 `@Immutable` + 모든 열 `updatable=false`, Store는 `persist`만(merge 금지). 예 → 같은 approvalId 두 번째 INSERT는 flush에서 1062가 나고 Port가 `UniqueConflict`로 던져 TX 전체가 rollback된다. 주의 → flush 전에는 1062가 안 보이므로 쓰기 끝에서 `flush()`(ADMIN-PAYMENT-OPS-001 §4.4.4와 같음). Kotlin Entity에 data class를 쓰지 않는다.
- **JPA — 잠금**: 결제별 행 `PESSIMISTIC_WRITE`(= `SELECT … FOR UPDATE`)는 봉인·취소 Inbox·보류 해소가 같은 Payment를 순서대로 처리하게 한다. 외부(SFTP·HTTP) 호출 동안 잠금을 잡지 않는다.
- **jOOQ CQRS**: 보류 검토 목록·재매입 대상 재조회·배지 계산은 `adapter.query`의 jOOQ Reader(SELECT만, MAX_EXECUTION_TIME). codegen 대상을 `acquisition_*`에서 `payment_fact_hold*`·`acquisition_ops_decision`까지 넓힌다(C-6). 명령은 JPA, 조회는 jOOQ — 같은 TX라고 가정하지 않는다(조회는 판정에 쓰지 않음, 판정은 잠금 아래 JPA 읽기).
- **버전**: 새 의존성 없음. Boot 4.1.1 BOM(Hibernate 7.4.5.Final·jOOQ 3.21.7) 그대로. Verifier가 resolved tree·CVE 재확인.
- **Kafka/Redis**: 쓰지 않는다. 2인 승인 상태·미해결 하나·최초 결정은 소유 DB 제약이 지킨다. 캐시·메시지가 이를 대신하지 않는다.

## 10. Coordinator가 정할 가역 선택(권장안)

| ID | 선택 | 권장 | 대안 |
|---|---|---|---|
| C-1 | 재매입 실행 시점 | 승인 명령 TX에서 기록 + 새 버전 봉인 함께(결정 = 효과, 승인당 파일 하나) | 승인 기록만 → 스케줄러가 승인 묶음을 업무일별 한 파일로 봉인(파일 수 적음, 경로 하나 더) |
| C-2 | FINANCIAL 결정 기록 | 세 조작 공통 `acquisition_ops_decision` + D-OPS-005·009 규칙 그대로 | 조작별 결정 표 |
| C-3 | ADMIN 일반화 | `OwnerServiceApprovalService`를 종류별 소유 서비스 gateway(paymentops·financialops)로 일반화, 회복 job 공유 | 매입 전용 승인 서비스 복제 |
| C-4 | 보류 해소 진입 | `paymentfact.adapter.web.admin` + 기존 `/internal/v1/admin/**` 체인 공유 | acquiring 컨트롤러가 paymentfact provided 호출 |
| C-5 | FINANCIAL 결정 대사(D-OPS-008 선례) | 마지막 배치 B5 | LIVE Gate로 미룸 |
| C-6 | jOOQ codegen 범위 확장 | `payment_fact_hold*`·`acquisition_ops_decision` 추가 | 해당 Reader만 JPA native |
| C-7 | 검증 Port | `ReacquisitionApprovalVerifier`·`UnavailableReacquisitionApprovalVerifier` 삭제(채널 필터가 인증, Domain `ApprovalChannel.ADMIN_SIGNED`는 채널에서 설정) | Port 유지 + 채널 구현 |
| C-8 | Demo 재매입 시나리오 | 기존 Simulator 시나리오 `SCENARIO_FILE_REJECTED`(파일 ID 지정 — 버전 001만) → 재매입 파일 002는 정상 판정(VAN 원장 대조)으로 ACCEPTED 기대. Simulator 코드 변경 없음. 실제로 002가 거절되면 Simulator 경로 추가를 Coordinator에 요청 | Simulator에 세대별 시나리오 추가(허용 경로 추가 필요) |
| C-9 | 사유 코드 | 조작별 고정 코드 목록(예: `INSTITUTION_CONFIRMED_ERROR`·`DATA_CORRECTED_AT_SOURCE`·`INSTITUTION_REQUESTED`) + 근거 참조, 자유 사유는 ADMIN만 | 자유 코드 |

## 11. Implementer 해석(가역 — Reviewer 확인)

| ID | 해석 |
|---|---|
| I-1 | 새 승인 종류는 OWNER_SERVICE 실행 모델(기존 단일 TX 경로는 fail-closed — ADMIN-PAYMENT-OPS-001 §4.10과 같음) |
| I-2 | 실행 기한(executeBefore)·창 검사는 D-OPS-002 값 구조 재사용(Demo 값), FINANCIAL `max-execution-window` 설정 필수 |
| I-3 | approvalId별 최초 결정·파생 CONFLICT·409/404/400/503 불변식은 D-OPS-005 규칙을 FINANCIAL에 그대로 옮긴 것(새 정책 아님) |
| I-4 | FINANCIAL은 사유 코드·근거 참조만 저장(V12 열 그대로), 사유 문구는 ADMIN 명령 행 |
| I-5 | 잠금 순서 lane → 파일 행(ID 오름차순) → 결제별 행(payment_id 오름차순) |
| I-6 | 재매입 파일은 이미 봉인된 업무일에만, 버전 = 그 날 최대 + 1(≤ 999, 넘으면 거절 VERSION_EXHAUSTED) |
| I-7 | CANCEL 레코드 재매입 금액 = 원 취소액(상계 없음), 조건 = 원 SALE 표지 있음(D-ACQ-009에서 끌어냄). 첫 배치는 SALE만, CANCEL은 B2 끝 |
| I-8 | 버튼 표시 조건은 FINANCIAL이 계산한 `eligible`·사유로만(화면 규칙 중복 금지 — ACQ-VIEW-001 I-4) |
| I-9 | 배지 3상태(검토 대상·진행 중·없음)는 FINANCIAL Reader 계산, 동치 기준 = Domain 규칙 |
| I-10 | 승인함의 예상 순액은 표시용(권위 아님) — 실행 TX 값이 권위, 다르면 결과 화면에 실제 금액 |
| I-11 | V12 표 재정의는 "runtime INSERT가 막혀 행이 없다"는 전제 — V17이 결정 행 FK를 더하므로 행이 남아 있으면 1452로 migration이 멈춘다(자기 검사) |
| I-5 확정 | (rev 0.2) 실행 TX 잠금 순서 = **원 파일 행 → lane → 결제별 행**(rev 0.1의 lane → 파일에서 바꿈): 없는 파일 ID로 lane 행(IODKU)을 만들지 않기 위해. 봉인(lane → 결제별)·응답(파일 행만)·취소 Inbox(결제별만)·다른 재매입(같은 순서)과 순환 없음. **시험 근거(rev 0.3 정정 — m-3)**: 재매입끼리 = RX-6(실제 연결 CyclicBarrier), 재매입 vs 같은 lane 정기 봉인 = RX-12(두 순서를 lane 잠금 직후 hook으로 강제 — 재매입 먼저: 봉인이 lane에서 기다리고 재매입은 판정 전 취소로 CANCELLATION_UNDECIDED, 봉인 먼저: 재매입이 파일 행을 잡은 채 lane에서 기다린 뒤 판정 끝난 취소로 순액 실행 — 교착·1213 없음). 응답 TX·취소 Inbox와의 겹침은 잠금 집합 논증(공통 행 하나 이하)이며 별도 경합 시험은 없다 |
| I-12 | (rev 0.2) 판정 끝나지 않은 취소(상계·CANCEL·불요 기록 없음)가 원승인에 있으면 실행 거절 CANCELLATION_UNDECIDED: D-ACQ-013 "그때까지 받은 모든 취소, 각 한 번"을 셀 때 그 취소가 다음 봉인에서 다시 판정되면 두 번 셀 수 있다. 다음 봉인(→ 취소 매입 불요) 뒤 다시 요청. 미해제 보류는 PAYMENT_HELD(fail-closed) |
| I-13 | (rev 0.2) 재매입 레코드 = 원 레코드 값 그대로 + seq 1..n(원 순번 순) + generation + 1 + reacquisitionOf + **상계액 칸 = 원승인액 − 순액**(그때까지 받은 모든 취소 합). 파일 헤더: 원 파일의 정책 버전·마감 시각, originalFileId = 대상 레코드의 파일. **(rev 0.4 — 리뷰 n-2) 헤더 senderId는 원 파일 값이 아니라 실행 시점 설정(`ReacquisitionExecutionService` — `acquiring.sender-id`)에서 가져온다**: 재매입 파일은 지금 새로 보내는 파일이고 senderId는 "송신 시점의 보내는 쪽 신원"이라 송신 시점 값이 맞다고 보아 유지한다(원 파일 뒤 설정이 바뀌었다면 매입사는 새 신원으로 받아야 한다). 금액·레코드 값은 원 파일·실행 TX 규칙 그대로라 이 선택은 금액 효과가 없다 |
| I-14 | (rev 0.2) 원 SALE membership 조회(`saleMemberships`)가 가장 높은 세대를 고른다 — 재매입 뒤 받은 취소를 거절된 옛 세대 기준으로 "취소 매입 불요" 종결하지 않게. 세대 ≥ 1 원 SALE의 CANCEL 판정은 기존 CancelCandidateRule(CC-4)이 INCONSISTENT로 대기시킨다(잘못된 기록 없음 — 새 세대 결과 연결은 B2, §15 L-6) |
| I-15 | (rev 0.2) 미리 보기는 실행과 같은 규칙(`ReacquisitionExecutionRule.preview` — 사람·기한 검사만 뺀 내부 probe 명령)으로 계산하고 잠그지 않는다(비권위). 조회 키 경로 `POST /internal/v1/admin/acquisition-files/{fileId}/reacquisition-targets` |
| I-16 | (rev 0.2) 응답 불변식(채널): 200 결정 본문 = 커밋된 결정 행(REJECTED도 200 + decision 필드 — PAYMENT 채널은 409였으나 ADMIN·FINANCIAL 양쪽을 이 작업이 정하므로 결정은 모두 200), 409 = APPROVAL_CONFLICT(파생), 400 = 형식, 그 밖 503. 기한 확인 = 같은 저장 본문, 경로 승인 ID = 본문 승인 ID |
| I-17 | (rev 0.3 — 리뷰 MJ-1) 상계 무결성 세대 분리: 기존 검사(`nettingMismatches`·지표 `integrity.netting_mismatch`)의 레코드 쪽은 **세대 0만**(상계 기록은 정기 봉인만 쓰고 세대 0 레코드만 가리킨다). 재매입 레코드 r(세대 ≥ 1) 검사 = r.상계액 ≠ ① 원 파일 상계 기록 합(`acquisition_netting`, 그 원승인, file_id 있음) + ② r보다 **낮은 세대** 레코드를 원 SALE로 가리키는 "취소 매입 불요" 합 + ③ 세대 0 CANCEL 레코드 합(그 원승인)인 건수 — 0이어야 한다. I-13(상계액 = 원승인액 − 순액)과 I-12(실행은 모든 취소가 판정 끝났을 때만)에서 끌어냄: 실행 시점 판정 끝난 취소 합 = 결제 사실 투영의 취소 합. 쓰는 곳: 지표 `integrity.reacquisition_netting_mismatch`(전체), 재매입 실행 TX 끝 대조(새 파일 — 어긋나면 결정 없이 전체 rollback, ADMIN은 503 → 회복; 봉인 TX §16 NIT 검사와 같은 처리). ②의 "낮은 세대" 조건은 r 실행 뒤 새 세대에 대한 판정을 r에 섞지 않기 위함. ③의 세대 기준은 B2가 새 세대 CANCEL을 열 때 다시 정한다(§15 L-8). **rev 0.7 재정의(L-8 해소)**: ③ = 0 — CANCEL 레코드는 그 원승인의 가장 높은 세대 SALE이 ACCEPTED일 때만 생기고(D-ACQ-009·CC-8) SALE 재매입은 어느 세대에도 표지가 없을 때만 실행되므로(D-ACQ-014), r 실행 시점에 CANCEL 레코드는 존재할 수 없다. 재매입 뒤 CANCEL은 언제나 r 실행 뒤의 판정이라 r 상계에 섞지 않는다(rev 0.6 정의는 그것을 섞어 거짓 불일치 — RR-1·변이 M-I17로 확인). 도달 불가 상태(실행 시점 CANCEL 존재)는 r.상계액 > ①+②로 드러난다(약화 아님) |
| I-19 | (rev 0.4 — B1-A, 가역) ADMIN 미해결 하나 키 = **원 파일당** `ACQFILE:<fileId>`(같은 원 파일에 미해결 재매입 승인 하나 — 레코드 겹침을 파일 단위로 막는다. 같은 파일의 다른 레코드 묶음은 앞 승인이 끝난 뒤 요청). FINANCIAL이 UNIQUE(거래, 종류, 새 세대)·결정 표로 다시 막는다. FINANCIAL 결정 `NOT_REQUIRED`(D-ACQA-004 — 묶음 전체 순액 0)는 ADMIN `EXECUTED` + result_code·owner_result_code `NOT_REQUIRED`(파일 참조 없음)로 둔다(Q-ACQA-04 권장안 문구 "EXECUTED(NET_ZERO)"와 같은 뜻 — 승인 소진, 효과는 불요 종결) |
| I-20 | (rev 0.4 — B1-A, 가역) 파일 대상의 `target_security_version`은 0 고정(V7 CHECK) — 권위 대상 버전은 명령 행 `pinned_target`의 레코드별 (세대, 상태). 승인 TX1 비교 = 레코드마다 세대·상태 같고 지금도 FINANCIAL `eligible`(아니면 INVALIDATED(TARGET_CHANGED) — TOTP 소비). 요청 advisory = 묶음 전부 eligible이어야(하나라도 아니면 `TARGET_NOT_ELIGIBLE_ADVISORY`, 저장 없음 — D-ACQA-003 부분 실행 없음과 같은 방향) |
| I-21 | (rev 0.4 — B1-A, 가역) 회복 job·결과 반영의 backoff·배치 크기·기한 여유(`deadline-skew`)는 운영자 보정 취소와 **같은 회복 job 정책**(`paycore.admin.payment-ops` 값 — job 하나 공유, C-3)을 쓰고, 재매입 실행 기한 폭·stale 지연은 financial-ops 설정(`operation-execution-window` Demo 24h ≤ FINANCIAL `max-execution-window` PT24H, stale = connect + read + 30초)에서 온다. 400 형식 거절은 PAYMENT 선례대로 OWNER_REJECTED(code) + 무결성 경보, 409 `APPROVAL_CONFLICT`는 EXECUTION_UNKNOWN + 무결성 플래그(조회로만 수렴), 결정 내용(승인 ID·원 파일·요청자·승인자·acd1 hash)이 ADMIN과 다르면 `DECISION_CONTENT_MISMATCH` 플래그 먼저 후 반영 |
| I-22 | (rev 0.4 — B1-A, 가역) 화면 미리 보기 `POST /api/acquisition-files/{fileId}/reacquisition-targets`는 상세·레코드 쪽과 같은 조회 4역할·속도 제한·감사(`ACQUISITION_FILE_VIEWED`, FINANCIAL이 답했을 때만)를 쓴다. 재매입 후보 체크박스는 표시 편의로 현재 상태 REJECTED·FILE_REJECTED 레코드에만 두고(UNKNOWN은 "대사로 미매입 확인 뒤 가능" 안내), 요청 가능 판단은 미리 보기 `eligible`만(I-8) |
| I-18 | (rev 0.3 — 리뷰 m-1) 실행 판정 시각 = **모든 잠금(파일·lane·결제별 행)을 얻은 뒤** 읽은 시계. 기한 판정(`now < executeBefore`)·결정 decided_at·새 파일 sealed_at·불요 decided_at이 모두 이 값. 잠금 대기 중 기한이 지나면 REJECTED(APPROVAL_EXECUTION_EXPIRED)(RX-10). lane IODKU 생성 시각·TARGET_NOT_FOUND 결정 시각은 그 시점 시계(판정과 무관) |
| I-23 | (rev 0.5 — 리뷰 M-1, 가역) 재매입 근거 참조(`evidenceRef`)는 형식(`^[A-Za-z0-9._:#/-]{1,128}$`) 검사 **뒤** 운영자 보정 취소와 같은 카드 번호 패턴(`OperatorCancelInputRules.containsCardNumber` — 십진 숫자 13~19자리, 숫자 사이 공백·하이픈 1개 허용, Luhn 없음)이면 400 `INVALID_INPUT`(evidenceRef·`CARD_NUMBER_PATTERN`)으로 거부한다(TX·FINANCIAL 호출 전). 같은 불변식을 `ReacquisitionRequestSnapshot.init`에 두어 다른 경로로도 저장되지 않게 한다(저장된 행이 어긋나면 재구성 실패 — fail-closed). 화면은 같은 정규식으로 버튼을 막고 경고만 보인다(권위는 서버). 날짜·일련번호를 구분 기호 없이 13자리 이상 이으면 거부되므로 힌트에 예(INQ-2026-1105-0001)를 둔다. **B2 판단 항목**: FINANCIAL `ReacquisitionCommandParser`에도 같은 검사를 둘지(ADMIN ⊆ FINANCIAL 원칙상 FINANCIAL이 더 넓으면 ADMIN 거부로 충분하지만, 채널을 직접 부르는 다른 호출자·서명 키 유출 시 append-only 기록에 카드 번호가 남을 수 있다 — 권장: 둔다, financial-batch는 이번 범위 밖). DB CHECK(`ck_admin_acquiring_ops_command_evidence`)에 카드 패턴을 더하는 이중 방어는 기록만(리뷰 n-6) |
| I-24 | **(rev 0.7 갱신 — B1 리뷰 m-A, Coordinator 결정 (b))** 재매입 사유 자유 문구는 ADMIN 명령 행(`admin_acquiring_ops_command`)에만 저장되고 승인 상세(4역할)·근거 조회(`/justification` — D-ACQA-005, **근거 참조만** 돌려준다)·감사 detail·FINANCIAL 어디에도 나가지 않는다. 요청 화면 힌트를 "ADMIN에만 저장되며 화면·감사에는 표시하지 않습니다(매입 정보 서비스로 보내지 않음)…"로 사실대로 고쳤다(rev 0.6까지 "승인자와 감사에만 남습니다"는 사실과 달랐다). 운영자 보정 취소의 사유 문구 노출(D-OPS-010)과 다르다는 점은 D-ACQA-005가 근거 참조만 정했기 때문이다. 아래 rev 0.6·0.5 원문은 기록용. |
| I-24 | **(rev 0.6 — D-ACQA-005로 대체됨, 아래 원문은 기록용)** (rev 0.5 — 리뷰 m-2, 가역) 승인 상세(`GET /api/approvals/{id}` → `acquiring.evidenceRef`, jOOQ 상세 조회)에 근거 참조를 보인다 — §5.2 "사유 코드·근거" 표시 요구. 사유 문구는 계속 응답에 없다(I-4, 운영자 보정 취소 D-OPS-010·I-98 선례). 목록에는 근거도 없다. **선례와의 차이(Coordinator 확인 후보)**: 운영자 보정 취소의 근거 참조(purposeReference)는 D-OPS-010으로 APPROVER·요청자만 별도 권한 조회 + 조회 감사로 보인다. 재매입 근거 참조는 상세 조회 4역할(VIEWER 포함)에게 감사 없이 보인다 — 재매입 근거는 매입사 문의 번호 같은 기관 참조이고 카드 번호 패턴은 요청 때 거부(I-23)되며 FINANCIAL 업무 기록에도 남는 값이라 이렇게 두었다. 같은 수준(D-OPS-010 방식)으로 좁혀야 하면 사용자 결정이 필요하다(질문 후보로 Coordinator에 반환) |
| I-25 | (rev 0.5 — 리뷰 m-4, 가역) 실행 행 형식 검사(지문 `^(oct1\|acd1):`, 소유 참조 `pcn_…` 또는 파일 ID)는 두 종류의 합집합이라, 종류와의 대조는 승인 행을 아는 쪽이 한다: ① 종류를 받는 Factory `OwnerExecution.start(kind, …)`(운영 경로 두 서비스가 이것만 씀 — 재매입 TX1에서 어긋나면 rollback + 무결성 위반 `fingerprint`, POST 없음), ② `OwnerOutcomeApplier.apply`가 잠근 실행 행의 `conformsTo(kind)`와 도착 참조의 `referenceFits(kind, …)`를 대조(어긋나면 rollback + `APPROVAL_INTEGRITY_VIOLATION` column=`execution_kind`·`owner_reference`, 결과 `Executing` — 회복 job이 이어받음), ③ 재매입 회복 job은 FINANCIAL 호출(결정 조회·기한 확인·재전송) 전에 `conformsTo`를 확인해 어긋나면 호출 없이 무결성 위반만 남긴다(기존 `reportIntegrity` 선례 — 행이 그대로면 backoff마다 감사 1행 반복). V7 CHECK에 종류↔접두 대조를 더하는 DB 이중 방어는 기록만(리뷰 n-6) |
| I-26 | (rev 0.5 — 리뷰 n-3, 가역) 실행 POST 200 결정 본문의 승인 ID가 보낸 본문의 승인 ID와 다르면 결정으로 보지 않는다(`RESPONSE_UNREADABLE` → 결과 불명확, 조회로만 수렴) — 결정 조회·기한 확인(경로 승인 ID 대조)과 같은 요청-응답 대응. `AcquiringOpsGateway.execute(approvalId, body)`로 Port가 승인 ID를 받는다(client의 쓰지 않던 인자 정리) |
| I-27 | (rev 0.7 — B2, 가역) 전송 보류(TRANSMISSION_WITHHELD) 파일에 응답이 와도 전송 축을 올리지 않는다(수신·결과 축만 기록 — 그 파일이 기관에 갔는지는 대사가 정한다). 보류는 종결: 다시 점유하지 않고(확인 먼저 재업로드도 없음) 자동 해제 없음. 보류 해소 경로는 이 작업 범위 밖(대사·후속 — 질문 후보 Q-ACQA-10). 보류 뒤 늦게 끝난 앞 시도의 SUPERSEDED 관찰 행은 resulting_state = TRANSMISSION_WITHHELD를 가질 수 있다(V19 CHECK 확장 — 경로 시험 없음) |
| I-28 | (rev 0.7 — B2, 가역) 보류 판정 잠금: 재매입 파일 점유 TX가 그 레코드의 **다른 세대 레코드가 있는 파일 행**을 파일 ID 오름차순으로 FOR UPDATE(응답 TX가 표지를 쓸 때 잡는 행과 같다) → 표지 재조회 → 이 파일 행  UPDATE. 응답 TX(파일 행 하나)·재매입 실행(원 파일 → lane → 결제별)과 순환 없음(점유끼리는 오름차순 하나). 보류 근거는 레코드마다 `acquisition_transmission_withhold`(append-only, PK(file_id, seq), 표지가 가리키던 다른 세대 레코드 FK, prior_state). 지표: gauge `files.transmission_withheld`, counter `transmission{outcome=WITHHELD,detail=ACCEPTED_IN_OTHER_GENERATION}`, 화면 "확인 필요: 전송 보류" |

## 12. 사용자 질문 후보(Coordinator 경유 — 답 전 해당 배치 운영 동작 구현 금지)

| ID | 질문 | 권장안 | 대안 | 영향 | 막히는 배치 |
|---|---|---|---|---|---|
| Q-ACQA-01 | ADMIN→FINANCIAL 쓰기 명령(재매입·해소·재반영)의 서명 키 | **쓰기 전용 별도 키**(조회 키와 kid·바이트 다름, FINANCIAL은 쓰기 경로를 쓰기 키 서명만 허용) | 조회 키 하나로 조회·쓰기(운영자 보정 취소 채널과 같은 방식) | 조회 키가 새어도 매입 상태를 바꿀 수 없게 할지(보안 경계). 별도 키면 설정·Demo 키 1개 추가 | B1 |
| Q-ACQA-02 | 새 2인 승인 종류의 요청·승인 역할 | 요청 **OPERATOR**, 승인 **APPROVER + 직전 TOTP**(운영자 보정 취소와 같음) | 요청 SECURITY_ADMIN / 승인에 SECURITY_ADMIN 추가 | 누가 재매입·해소를 시작·승인하나(권한) | B1 |
| Q-ACQA-03 | 재매입 승인 단위 | **같은 원 파일 레코드 묶음 1~50건을 승인 하나로**, 실행 때 하나라도 대상이 바뀌면 승인 전체 무효(부분 실행 없음) | 레코드 하나당 승인 하나(파일 전체 거절이면 레코드 수만큼 승인) | 승인 횟수, 부분 실행 여부, 승인 표 키 | B1 |
| Q-ACQA-04 | 재매입 실행 시점 순액이 0원(그 사이 전액 취소)이면 | **"재매입 불요" 종결 기록**(append-only, 파일 없음, 승인 결정 EXECUTED(NET_ZERO) — 검토 대상 배지 꺼짐) **→ 정정(답 D-ACQA-004 '재매입 불요'로 종결, S3-DOC-001): 결정 값은 EXECUTED(NET_ZERO)가 아니라 `NOT_REQUIRED`(§2 D-ACQA-004 행·I-19, `AcquisitionOpsDecision.kt:53` EXECUTED·NOT_REQUIRED·REJECTED)** | 승인 거절(OWNER_REJECTED) 후 레코드는 검토 대상 유지 / 0원 레코드 전송 | 금액 0 거래의 종결 상태 확정성 | B1 |
| Q-ACQA-05 | 전송 격리(TRANSMISSION_QUARANTINED) 해소의 의미와 승인 수준 | **2인 승인 + 기관 확인 근거 → TRANSMISSION_UNKNOWN 복귀**, 이후 시스템이 매입사 파일을 다시 읽어 같은 바이트일 때만 전송 완료(운영자가 전송 완료를 직접 지정하지 않음) | 1인(OPERATOR) 승인 / 해소 없음(대사만) | 기관이 다른 내용을 받았을 수 있는 파일을 다시 보낼지(이중 매입 위험 판단 주체) | B3 |
| Q-ACQA-06 | 격리 응답 재반영의 대상과 승인 수준 | 대상 = **TRANSMISSION_QUARANTINED 사유 응답만**, **OPERATOR 1인 명령 + 감사**(같은 규칙 재판정이라 사람이 결과를 고르지 않음) | 2인 승인 / 격리 해소 승인 뒤 자동 재반영 / 다른 격리 사유도 포함 | 재반영으로 ACCEPTED(매입 확정)가 생길 수 있음 — 승인 수준 | B3 |
| Q-ACQA-07 | 보류 REVIEW_REQUIRED "해소"의 금융 의미(2인은 D-ADMIN-003로 이미 정해짐) | 결과 두 가지만: **재판정 일치 시 해제**(지금 사실로 규칙을 다시 돌려 불일치 없음) / **대사 이관 기록(보류 유지, 매입 제외)**. 운영자가 불일치를 덮는 강제 해제 없음. 정합성 보류 5종은 범위 밖 | 운영자 판단 강제 해제(불일치가 남아도 매입 진행) / 기록만(해제 없음) / 5종도 포함 | 사실이 어긋난 Payment가 매입될 수 있는지 | B4 |
| Q-ACQA-08 | Demo 준비 허락(배치 B3·B4) | **허락**: B3 — Demo 매입사 SFTP root(호스트)에 다른 바이트 파일을 최종명으로 미리 두어 격리를 만든 뒤 지움(기관 확인 흉내), B4 — Demo 키로 서명한 순서 뒤바뀐·불일치 결제 사실 Fixture를 FINANCIAL Inbox에 넣어 REVIEW_REQUIRED를 만듦(실제 Relay 아님, TEST 환경만) | B3·B4는 Demo 화면 없이 시험만 | Demo 데이터·Simulator 파일 조작 범위 | B3·B4 Demo |
| Q-ACQA-09 | 재매입 승인 상세의 근거 참조(evidenceRef) 노출 범위(rev 0.5 I-24에서 반환) | **운영자 보정 취소(D-OPS-010)와 같게**: APPROVER와 요청자 본인만, 별도 조회, 조회마다 감사 | 상세 조회 4역할 모두 감사 없이 표시 / 당분간 표시 안 함 | 기관 참조 값의 열람 범위·감사 | B1-A(답변됨 → D-ACQA-005) |

**답변 상태(rev 0.2)**: Q-ACQA-01~04 → D-ACQA-001~004(모두 권장안, 2026-11-06). Q-ACQA-05~08 = PENDING(B3·B4 전에 Coordinator가 묻는다 — 그 부분 운영 동작 없음). (rev 0.6) Q-ACQA-09 → **D-ACQA-005**(권장안, 2026-11-07).

질문하지 않은 것(해석으로 처리): 실행 기한·결정 기록·회복 job 규칙(D-OPS 선례 — I-2·I-3), 잠금 순서(I-5), 버전 번호(I-6), 배지 문구(I-9), 사유 코드 목록(C-9).

## 13. TDD 순서와 핵심 변이

1. golden 먼저: harness `test_admin_financial_ops_api_golden.py`(요청·응답·상태 매핑) + 서명 golden에 쓰기 경로 벡터 → RED(파일 없음).
2. Domain: `ReacquisitionRule`(묶음 전체 무효·순액 0·CANCEL 조건), `AcquisitionFile.resolveQuarantine`, `HoldReviewRule`(재판정), ADMIN `ApprovalKind`·판정표 → RED → 최소 구현.
3. FINANCIAL DB 시험(실제 MySQL runtime 계정): 결정 기록·실행 TX·경합(R-m1)·rollback → RED(stub `TODO()`).
4. 채널: 쓰기 키 필터·권한·엄격 JSON·응답 매핑.
5. ADMIN: 요청·승인 2-TX·회복·기한 확인 → 실제 HTTP 대역 FINANCIAL.
6. 화면 Vitest → 구현 → Demo.

핵심 변이(각 RED 확인 후 원복):
- M1 실행 TX에서 revalidate 생략 → AC7 RED. M2 순액 대신 원승인액 → AC7 RED. M3 결정 INSERT 1062를 잡고 계속 → "결정 없는 효과" 시험 RED.
- M4 markAccepted 생략(재반영 경로) → AC6·R-m1 RED. M5 표지 PK 재시도 제거 → R-m1 RED(예외 전파).
- M6 FINANCIAL 요청자=승인자 검사 제거 → DB CHECK로 잡히는지 + 앱 시험 RED. M7 쓰기 경로에 조회 키 허용 → AC3 RED(Q-ACQA-01 권장안 기준).
- M8 보류 재판정 없이 해제 → HoldReview RED. M9 해소 시 TRANSMITTED 직접 지정 → AC4 RED. M10 배지 계산에서 새 세대 무시 → AC9 RED.
- M11 잠금 순서 뒤집기(결제별 행 먼저) → 교착 시험(봉인 vs 재매입) RED 또는 1213 재시도 증거.

## 14. 배치 계획(각 배치 끝에 Demo 화면으로 보일 것)

권장 순서: **재매입을 먼저**(사용자 핵심 기능, 기존 Demo 거절 시나리오로 재현 가능) → 격리 → 보류(Demo 재현이 가장 어렵다).

| 배치 | 내용 | 필요한 답 | 끝에 화면으로 보일 것 |
|---|---|---|---|
| **B1** 재매입 요청·승인·실행(봉인까지) | 쓰기 채널·FINANCIAL 결정 기록·승인 표 재정의(V17·V18)·대상 재조회, ADMIN 새 종류·요청·승인 2-TX·회복·기한 확인, FINANCIAL 실행 TX(승인 기록 + 새 버전 파일 봉인, 순액 — C-1), 매입 현황 상세 "재매입 요청" 버튼·승인함. SALE만 | Q-ACQA-01·02·03·04 | 파일 거절 파일 상세 → 레코드 선택 → 재매입 요청 → 승인함(고정 값·현재 값·TOTP) → 승인 → 같은 업무일 파일 `…002`(봉인됨, 세대 1·`reacquisitionOf`)가 목록에 보임 |
| **B2** 재매입 전송·결과·배지 | 기존 전송·결과 경로로 002 처리, R-m1 PK 경합, N-1 ArchUnit, L-9 배지 3상태, CANCEL 재매입(I-7) | — | 002 전송 → 가상 매입사 ACCEPTED → 원 파일 "재매입 검토 대상" 배지 꺼짐(진행 중 → 없음) |
| **B3** 전송 격리 해소 + 격리 응답 재반영 | `resolveQuarantine`, 해소 기록, 재반영 기록, 버튼 2개 | Q-ACQA-05·06·08 | 격리 파일(확인 필요 배지) → 해소 요청·승인 → "전송 결과 모름" → 자동 재확인 → 전송 완료 → 격리 응답 "다시 반영" → 결과 표시 |
| **B4** 보류 검토 | 보류 검토 탭(jOOQ), 해소 명령, 봉인 조회 변경(LIVE Gate 11) | Q-ACQA-07·08 | 보류 검토 목록 → 해소 요청·승인 → "대사 이관" 또는 "해제" → 다음 봉인에서 그 Payment 매입 여부 |
| **B5** 결정 대사(C-5) | ADMIN이 FINANCIAL 결정 목록 대조, 발견 표시 | — | 승인함 상단 대사 발견 수(정상이면 0) |

**rev 0.8 범위 확정(사용자 지시 — B2에서 종결)**: ACQ-ADMIN-001의 범위는 **B1 + B2(재매입 SALE의 요청·승인·실행·전송·결과·배지·L-9 전송 보류)**까지다. 위 표에서 하지 않은 것은 모두 후속 작업 **ACQ-ADMIN-002**로 옮긴다.

| 옮기는 항목(→ ACQ-ADMIN-002) | 출처 |
|---|---|
| B3 전송 격리 해소 + 격리 응답 재반영 — §1 포함 (c)·(d) | Q-ACQA-05·06·08 |
| B4 보류 REVIEW_REQUIRED 검토·해소 — §1 포함 (b) | Q-ACQA-07·08 |
| B5 결정 대사(C-5) | §10 C-5 |
| Q-ACQA-10 전송 보류 해제 의미(영향에 §15 L-9 m-2·m-3 포함) | §17.10-7 |
| CANCEL 레코드 재매입 | §11 I-7 |
| R-m1 PK 경합 시험(`AcquisitionRaceFixture`/`AcceptedRecordRaceDbSpec`) | AC5 |
| N-1 ArchUnit(`RecordResultWriter` 경로 하나) | AC6 |
| B2 리뷰 n-1: RR-4를 실제 `AcquirerResponseService` 경로로 | §17.11 |
| B2 리뷰 n-3: SUPERSEDED 관찰 행·보류 파일에 온 응답의 DB 시험 | §17.10-7 |
| B2 리뷰 m-3 코드 수정(원 SALE 선택을 확정 세대 기준으로) | §15 L-9 |

B2 표의 "R-m1 PK 경합, N-1 ArchUnit, CANCEL 재매입(I-7)"은 ACQ-ADMIN-001에서 하지 않았다(위 표). §1 표의 포함 항목 중 (b)·(c)·(d)와 R-m1·N-1도 같다 — §1은 원래 계획 기록으로 남긴다.

각 배치: Implementer는 단일 spec 단위 실행만, 모듈 전체 실행은 Coordinator(OOM 이력 — ACQ-VIEW-001 C(7)). 배치마다 Reviewer → Verifier → Demo 캡처(Simulator Demo 성공이며 운영 준비 완료 아님).

## 15. 위험·한계

- L-1 R-m1 경합 상태는 지금 규칙으로 자연 도달하지 않는다(세대 1은 세대 0 종결 거절 뒤에만 생김) → 명시 상태 Fixture로 DB 이중 방어만 증명한다. 대사가 UNKNOWN 재매입을 열면 자연 경로가 생긴다.
- L-2 Simulator가 재매입 파일 002를 정상 수락하는지는 Demo에서 처음 확인(C-8). 거절되면 Simulator 경로 추가 필요.
- L-3 승인당 파일 하나(C-1 권장)는 업무일 버전 999 상한에 닿을 수 있다(Demo 무관, LIVE Gate).
- L-4 FINANCIAL은 승인자의 역할을 직접 확인하지 못한다(서명 = ADMIN이 보냄) — 역할 검사는 ADMIN, FINANCIAL은 사람 동일성·대상·기한·결정 기록, 사후 대사(B5)로 보완.
- L-5 격리 해소는 "기관이 다른 내용을 처리하지 않았다"는 운영자 근거에 기댄다 — 근거 원천 대조는 LIVE Gate(D-OPS-004 L-2와 같음).
- L-6 (rev 0.2) 재매입 뒤 받은 전송 후 취소는 지금 INCONSISTENT 대기(기존 CC-4 — generation 0만 정합). 새 세대 ACCEPTED 뒤 CANCEL 레코드·새 세대 REJECTED 뒤 불요 판정은 B2에서 CancelCandidateRule을 바꿔 연다(기존 CC-4 기대값 변경 = B2 명세 근거 필요). 그 전에는 대기 지표로만 보인다 — 잘못된 종결 기록은 없다(RX-7). **rev 0.7 처리**: CancelCandidateRule 정합 검사에서 `generation != 0` 조건을 뺐다(원 SALE = 가장 높은 세대, 누적 한도·거래·원승인액 대조는 그대로). 바뀐 기대값: CancelCandidateRuleSpec CC-4 넷째 벡터(세대 1 → INCONSISTENT)를 CC-8로 옮겨 결과별 판정으로, ReacquisitionExecutionDbSpec RX-7 대기 사유 INCONSISTENT → ORIGINAL_AWAITING_RESULT(불요 0·CANCEL 0 단언은 그대로) — 근거 모두 이 L-6.
- L-7 (rev 0.2) financial-batch 설정에 조작 키·실행 기한이 **필수**가 되어 Demo FINANCIAL 재기동에는 `.env.example`의 새 변수(PAYCORE_FINANCIAL_ADMIN_OPERATION_*)가 필요하다(B2 Demo 준비 — Coordinator). **rev 0.8 — B2 리뷰 n-2(Demo·배포 순서)**: FINANCIAL(V19·V20 적용본)을 **ADMIN보다 먼저** 올린다. ADMIN의 엄격한 응답 모양 검사(FinancialOpsGateway)가 매입 파일 항목의 `reacquisitionInProgress` 키를 요구하므로, 옛 FINANCIAL에 새 ADMIN이 붙으면 매입 현황 조회가 응답 모양 오류로 실패한다(안전 쪽 실패 — 금액 효과 없음). Coordinator Demo 실행에서 확인한 추가 설정(값은 기록하지 않음): 양쪽 `extra.env`의 조작 키, `financial-json.py` ADMIN 채널 `operation-keys`·`operation-permits`·`max-execution-window`, Simulator 시나리오 0을 FILEID로 v001에만 한정(§17.11).
- L-8 (rev 0.3 — I-17) 재매입 상계 무결성의 ③(세대 0 CANCEL 레코드)은 "CANCEL은 세대 0 SALE에만 붙는다"(지금 CC-4)는 전제다. B2가 새 세대 SALE에 CANCEL 레코드·불요 판정을 열면 CANCEL 레코드가 어느 SALE 세대를 가리키는지 기록(또는 판정 시각 기준)을 정하고 I-17 ③을 고친다 — 그 전에 B2를 열면 재매입 레코드가 거짓 불일치로 보일 수 있다(지표가 드러냄, 금액 효과 없음). **rev 0.7 처리**: I-17 ③ = 0으로 재정의(§11 I-17).
- L-9 (rev 0.3 — 리뷰 m-8, **B2 할 일 — 이번에 코드로 고치지 않음**) 재매입 파일을 봉인한 뒤 **전송 전에** 원 세대(세대 0)의 늦은 결과가 ACCEPTED로 관찰되는 경우: 지금 전송 경로(`transmitPending`)는 봉인 파일의 레코드가 다른 세대에서 매입 확정됐는지 보지 않으므로 세대 1 파일을 그대로 보내 **이중 매입 요청**이 될 수 있다(매입 확정 표지 PK — V14 — 는 두 번째 ACCEPTED를 CONFLICT로 남길 뿐 기관 송신을 막지 않는다). B2 처리 계획: (a) 재매입 파일 전송 claim TX에서 그 레코드들의 매입 확정 표지를 잠금 아래 다시 확인해 하나라도 있으면 전송하지 않고 파일을 보류(새 전송 상태 또는 격리 사유)·대사 이관, (b) 그 상태 전이·지표·시험(늦은 ACCEPTED vs 전송 claim 경합 — 파일 행 잠금으로 직렬화)을 B2 명세에 둔다. B1-F에서는 재매입 파일이 SEALED로 남고 Demo·운영에서 전송 job이 돌기 전 B2가 먼저 와야 한다(Coordinator 순서 관리). **rev 0.7 처리**: (a)·(b) 구현 — 새 전송 상태 TRANSMISSION_WITHHELD(V19), 보류 근거 표, 잠금 순서(I-28), 시험 RR-3·RR-4(경합 — 잠금 제거 변이 M12 RED). 이 상태는 지금 규칙으로 자연 도달하지 않아(L-1과 같음) 명시 상태 Fixture로 검사했다.
  **rev 0.8 — B2 독립 검토(PASS, MAJOR 0)의 남은 위험(기록만 — 이번에 코드로 고치지 않음)**: 아래 셋 모두 지금 규칙으로 도달하지 않는다(L-1 — 세대 1은 세대 0 종결 거절 뒤에만 생기고, 그 뒤 원 세대 ACCEPTED가 올 자연 경로가 없다). Q-ACQA-10의 영향과 ACQ-ADMIN-002로 넘긴다.
  - **m-2 파일 단위 보류의 곁가지**: 늦게 확정된 레코드 하나 때문에 재매입 **파일 전체**가 TRANSMISSION_WITHHELD가 되어, 같은 파일의 다른 레코드도 묶인다 — 전송되지 않고, 실행 기한이 없고, 재매입 대상도 아니며(이미 더 높은 세대 있음), 원 파일 배지는 "재매입 진행 중"에 머문다. 금액 효과는 없고(보내지 않음) 보류 gauge·확인 필요 사유로 보인다. 해소 의미는 Q-ACQA-10.
  - **m-3 보류 중 받은 취소의 무기한 대기**: 보류 중에 받은 전송 후 취소는 `JpaAcquisitionMembershipStore.kt:61`(`saleMemberships`)이 **확정(ACCEPTED) 세대가 아니라 가장 높은 세대**를 원 SALE로 고르므로, 보류된 새 세대의 결과를 기다리며 `ORIGINAL_AWAITING_RESULT`에 영원히 머문다. 안전 쪽 실패(CANCEL 레코드·불요 판정을 잘못 쓰지 않음)지만 해소 경로가 없다. 코드 수정(확정 세대 우선 선택)은 ACQ-ADMIN-002.
  - **m-4 점유 커밋 뒤 도착한 원 세대 ACCEPTED**: 재매입 파일 전송 점유 TX가 커밋된 **뒤** 원 세대의 ACCEPTED가 도착하면 소유 DB 잠금(I-28 — 다른 세대 파일 행 잠금)으로 막을 수 없어 이중 매입 요청이 될 수 있다(외부 송신은 이미 결정됨). 매입 확정 표지 PK(V14)가 두 번째 ACCEPTED를 CONFLICT로 남기므로 CONFLICT 경보와 대사로 처리한다 — 소유 DB 잠금의 한계이며 Kafka/Redis 같은 메시징·캐시가 이 경합을 대신 막지 않는다.
- L-10 (rev 0.4 — B1-F 독립 검토 nit 원문과 처리 사유, Coordinator가 원문 전달):
  - **n-2** 원문: "재매입 파일 헤더 senderId를 원 파일이 아니라 현재 설정에서 가져온다(`ReacquisitionExecutionService`)." 처리: **코드 유지** — senderId는 송신 시점 신원으로 본다. 선택과 이유를 I-13에 적었다.
  - **n-3** 원문: "`AcquisitionFile.sealReacquisition`이 lane 일치와 originalFileId(같은 lane·업무일·더 낮은 버전)를 Domain에서 검사하지 않는다." 처리: **B2 할 일**(이번에 코드로 고치지 않음 — B1-A 허용 경로·범위 밖) — 지금은 유일한 호출 경로(`ReacquisitionExecutionService` — 원 파일 행에서 lane·업무일을 읽고 버전 = 그 날 최대 + 1)가 조건을 보장하므로 잘못된 봉인 경로가 없다. B2에서 Domain 방어 검사(lane 같음·originalFileId가 같은 lane·업무일의 더 낮은 버전)와 Domain 시험을 더한다. **rev 0.7 처리**: `sealReacquisition`이 모든 레코드 원 참조가 헤더 originalFileId의 레코드(`{id}#{seq≥1}`)인지 검사(lane·업무일·낮은 버전은 헤더 init이 이미 지킴 — EX-7 n-3 시험).
  - **n-4** 원문: "UNIQUE(file_id, seq, new_generation)의 1062가 기존 UNIQUE와 따로 증명되지 않는다." 처리: **유지(시험 추가 없음)** — 세대 CHECK(새 세대 = 원 세대 + 1)와 (file_id, seq) → (거래, 종류) 함수 관계 때문에 기존 UNIQUE(transaction_id, record_type, new_generation)와 실질적으로 같은 제약이라, 한쪽만 위반하는 행을 만들 수 없다(따로 1062를 증명할 상태가 없다). 복합 UNIQUE는 이중 방어로 남긴다.
  - **n-6**(선택) 원문: "TX 끝 무결성 대조 실패는 rollback돼 지표에 잡히지 않는다 — label 없는 counter `paycore.financial.acquiring.reacquisition.integrity_rejected`를 추가하면 좋다." 처리: **이번에 하지 않음(미처리)** — B1-A는 ADMIN 절반에 집중했고 financial-batch 변경·시험 재실행 비용을 피했다. 지금은 rollback → 결정 없음 → ADMIN 503 → EXECUTION_UNKNOWN → 회복 job 반복이 ADMIN `paycore.admin.ops.unresolved{status}`·`unknown_oldest_age`와 FINANCIAL gauge `integrity.reacquisition_netting_mismatch`(커밋된 행 기준이라 이 경우 0)로만 보인다. B2에서 counter(label 없음 — ID·금액 없음)와 지표 delta 시험을 더하는 것을 권장한다. **rev 0.7 처리**: label 없는 counter `paycore.financial.acquiring.reacquisition.integrity_rejected` 추가(RX-14 delta +1·tags 없음, 변이 M-n6 RED).

## 16. 허용 경로 확인

변경 예정: `apps/financial-batch/**`, `apps/internal-admin/**`, `harness/commands.json`, `harness/tests/**`, `harness/e2e/**`, `.env.example`, `docker/mysql/init/**`(필요 시), `docs/repository/ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md`, `docs/repository/ACQUIRING-001-ACQUIRING.md`(I-51·LIVE Gate 8·11·13·19 갱신), `docs/repository/ARCHITECTURE-CONVENTIONS.md`. 모두 tasks.json allowedWritePaths 안. 허용 밖: `apps/institution-simulator/**`(C-8 대안일 때만 추가 요청), `docs/DECISIONS.md`·`harness/questions.json`(Coordinator 소관).

## 17. 실행 기록(B1-F — FINANCIAL 절반, Implementer 실행, Verifier 재검증 대상, 2026-11-06 KST)

환경: WSL Ubuntu, `./gradlew --no-daemon -q :apps:financial-batch:test --tests <spec 하나>`(Coordinator 지시 — 단일 spec foreground만, 모듈 전체 실행 없음), Testcontainers MySQL 8.4,
시험 SSHD. Demo·/tmp/paycore-demo·실행 중 앱은 건드리지 않았다.

### 17.1 변경(apps/financial-batch)

- migration V17(`acquisition_ops_decision` 신설, 승인 표 PK(approval_id, file_id, seq)·결정 FK·UNIQUE(file_id, seq, new_generation) — n-4, `acquisition_reacquisition_not_required` 신설),
  runtime V18(세 표 SELECT, INSERT — 승인 표 INSERT 개방은 D-ACQ-011이 정한 입력 경로와 함께, 완화 아님).
- Domain: `ReacquisitionExecutionRule`(plan·preview·`ReacquisitionCommand.contentHash` `acd1:`), `AcquisitionOpsDecision`, `AcquisitionFile.sealReacquisition`, 거절 사유 8개 추가.
- Application: `ReacquisitionExecutionService`(실행·결정 조회·기한 확인·미리 보기 — 한 로컬 TX, 잠금 뒤 결정 재확인, UNIQUE·1213·version 재시도 3회),
  `ExecuteReacquisitionUseCase` 계약 교체, `ReacquisitionTargetQuery`·`AcquisitionOpsDecisionStore`·`ReacquisitionExecutionPolicy` 신설, `ReacquisitionApprovalStore` 묶음·불요.
  **삭제(I-51 세 겹 해제 — D-ACQ-011)**: `ReacquisitionApprovalService`·`RecordReacquisitionApprovalUseCase`·`ReacquisitionApprovalVerifier`·`UnavailableReacquisitionApprovalVerifier`(C-7).
- paymentfact provided: `paymentIdsOf`·`readWithoutLock`(미리 보기 — 잠금 없음). membership: `saleMemberships` 가장 높은 세대(I-14), 파일 `latestVersion`.
- 채널: `AdminChannelSettings` operation-keys·operation-permits·max-execution-window(필수, 키 공간 분리 기동 검사), 필터가 조작 키 → `ADMIN_OPERATION`, 보안 체인 경로별 권한,
  조작 permit 분리, `AdminAcquiringOperationsController`(4 endpoint), `ReacquisitionCommandParser`(엄격 JSON), `ReacquisitionExecutionSettings`(integration adapter — ArchUnit: web adapter는 required Port 구현 금지).
- 시험 설정·Fixture: 시험 조작 키 `fops-op-test-k1`(운영 키 아님), lane `SFTPQ1..20`. `.env.example` 새 변수.

### 17.2 RED·GREEN

| Spec | RED | GREEN |
|---|---|---|
| `ReacquisitionExecutionRuleSpec`(EX-1..7) | 운영 코드 `TODO()` stub으로 7건 중 6건 실패(NotImplementedError — EX-6 생성자 검사만 통과), 보고서 scratchpad `demo/acqadmin/red/` | 7/0 fail/0 skip(독립 Python 해시 벡터 포함) |
| `ReacquisitionExecutionDbSpec`(RX-1..9) | 첫 실행 2건 실패: (a) 시험이 TX 밖에서 MANDATORY Port 호출 — 시험 수정, (b) RX-7이 INCONSISTENT 대기 관찰 — 기존 CC-4 규칙 그대로임을 확인하고 기대를 "불요 종결 없음·대기"로 고정(L-6) | 12/0/0 |
| `AdminAcquiringOperationsHttpSpec` | (처음 실행 GREEN — RED는 변이 M6로 확인) | 6/0/0 |
| `FinancialAdminChannelSettingsSpec` | — | 11/0/0(+2 조작 키 규칙) |
| 회귀 | `ArchitectureSpec` 1건 실패(web adapter가 required Port 구현) → integration adapter로 이동 | ArchitectureSpec 23, AcquiringOperationsDbSpec 6, FinancialDatabaseAccountSpec 23, MainMigrationLocationSpec 4, AdminAcquisitionApiSpec 10, FinancialAdminCallSignatureGoldenSpec 38, AdminAcquisitionControllerSpec 3, AcquisitionSealDbSpec 17, AcquirerResponseDbSpec 24 — 모두 0 fail/0 skip |

기대값 변경(완화 아님 — 근거 D-ACQ-011 "ADMIN 입력은 ACQ-ADMIN-001"): FinancialDatabaseAccountSpec 승인 표 권한 Select → Select,Insert 와 "INSERT 1142" 단언 → "결정 행 없는 INSERT 1452"(FK가 결정 없는 승인을 막음),
Flyway 1..16 → 1..18, 표 목록 +2. AcquiringOperationsDbSpec AQ-G1·G3 삭제(채널 없음·실행 거절 단언 — 입력 경로가 열려 성립하지 않음, 대체 = RX·HTTP spec), AQ-G2에 결정 FK 1452 추가.

### 17.3 핵심 변이(각 RED 확인 후 원복 — 원복 전후 sha256 앞 12자 동일)

| 변이 | 대상 spec | 결과 |
|---|---|---|
| M1 다른 세대 ACCEPTED 무시(`accepted = false`) | ReacquisitionExecutionDbSpec | RED(1 fail — RX-2) |
| M2 순액 대신 원승인액(`recordAmount = approved`, 상계 0) | ReacquisitionExecutionDbSpec | RED(2 fail — RX-1·RX-9) |
| M5 묶음 중 거절 항목을 건너뛰고 나머지 실행(D-ACQA-003 위반) | ReacquisitionExecutionRuleSpec | RED(1 fail — EX-2) |
| M6 조작 경로에 조회 키 권한 허용(D-ACQA-001 위반) | AdminAcquiringOperationsHttpSpec | RED(1 fail) |
| (동등 변이 기록) 잠금 뒤 결정 재확인 제거 | — | 실행하지 않음: 같은 승인 ID 경합은 UNIQUE(approval_id) 1062 → 재시도가 재생으로 수렴(권위는 UNIQUE) |

### 17.4 8대 원칙 증거(B1-F)

Financial Consistency(RX-1 순액·RX-3 불요·M2) · Transaction Safety(RX-5 중간 실패 전부 rollback) · Idempotency(RX-1 재생·충돌, RX-6 같은 승인 동시) · Concurrency(RX-6 실제 연결
CyclicBarrier — 서로 다른 승인 하나만 실행) · Recoverability(RX-8 기한 확인·늦은 실행 재생, 503 재전송 계약) · Reconciliation(UNKNOWN 거절 D-ACQ-014, 결정 목록 대사는 B5) ·
Auditability(결정·승인·불요 append-only, 요청자 ≠ 승인자 Domain + DB CHECK) · Observability(`paycore.financial.admin_channel.operation{endpoint,result}`, busy 지표 — label에 ID·금액 없음).

### 17.5 남은 일 — B1-A(ADMIN 절반, 미착수)

1. ADMIN V7: kind `ACQ_REACQUISITION`·target_kind `ACQUISITION_FILE` CHECK, `ck_…_target`·`ck_…_owner_states` 확장, `unresolved_key`에 `ACQFILE:<fileId>`(원 파일당 미해결 승인 하나 — 가역 해석),
   `admin_owner_execution` 지문 CHECK `^(oct1|acd1):`, `admin_acquiring_ops_command`(INSERT-only, pinned_target JSON = 레코드별 세대·상태·예상 순액). V8 권한.
2. Domain: `ApprovalKind.ACQ_REACQUISITION(OWNER_SERVICE, OPERATOR, ACQUISITION_FILE)`(D-ACQA-002), `ApprovalTarget.AcquisitionFile`, `ApprovalParticipants.distinct`, `OwnerExecution` 지문 형식,
   요청 스냅샷·입력 규칙(사유 코드 고정 목록 C-9, 근거 = FINANCIAL 문법 `[A-Za-z0-9._:#/-]{1,128}`, 사유 문구 = 기존 reasonProblem). `acd1:` 계산은 위 독립 벡터로 대조.
3. financialops: 조작 키 설정(`operation-key-id`·`operation-signing-key` — 조회·PAYMENT·MASTER 키와 분리 기동 검사), 조작 gateway(실행·결정 조회·기한 확인 — 조작 키, 미리 보기 — 조회 키), 응답 모양 검사.
4. approval: `AcquiringOpsApprovalService`(요청 TX·승인 A(미리 보기 재조회)·TX1(EXECUTING + 실행 행)·C(조작 POST)·TX2(`OwnerOutcomeApplier` — target_ref 일반화)), `OwnerServiceApprovalService.approve`
   종류 분기, `OwnerExecutionRecoveryService` 종류 분기(FINANCIAL 결정 조회·기한 확인·재전송), `ApprovalService` 감사 target_ref 일반화, JPA 대상 매핑.
5. 화면: 매입 현황 상세 레코드 선택 + "재매입 요청"(OPERATOR, 미리 보기 eligible만), 승인함 새 종류 표시(고정 값 vs 현재 값·예상 순액·TOTP), Vitest. 시험 대역 데이터 캡처 → scratchpad `demo/acqadmin/`.

### 17.6 rev 0.3 — B1-F 독립 검토(FAIL) 지적 반영(2026-11-07 KST, Implementer 실행, Verifier 재검증 대상)

환경: WSL Ubuntu, 스크립트 `/tmp/acqfix/run.sh`(`./gradlew --no-daemon -q :apps:financial-batch:test --tests <spec 하나>` foreground), Testcontainers MySQL 8.4, 시험 SSHD. Demo·/tmp/paycore-demo·Demo MySQL은 건드리지 않았다.
기존 시험 기대값·게이트 기준 완화 없음(RX-4의 UNKNOWN 고정 항목은 이름만 "UNKNOWN으로 고정한 승인 vs 실제 REJECTED = 대상 버전 불일치(TARGET_CHANGED)"로 정정 — 단언 그대로, 실제 UNKNOWN은 RX-4U가 새로 단언).

| 지적 | 처리 | 시험(spec — 이름) |
|---|---|---|
| MJ-1 | 세대 0 한정(`AcquisitionNettingIntegritySql.GENERATION_ZERO_MISMATCH` — 지표·파일별 검사 공용) + 세대 ≥ 1 새 검사(I-17: Port `reacquisitionNettingMismatches`, 지표 `integrity.reacquisition_netting_mismatch`, 실행 TX 끝 대조) | ReacquisitionExecutionDbSpec RX-1 "MJ-1 … does not move either netting integrity gauge", RX-1 "MJ-1 … caught by the new check"(손상 주입 — 시험 TX rollback), RX-14(손상 주입 — 실행 TX fail-closed rollback), RX-13 |
| m-1 | 판정 시각을 모든 잠금 뒤 읽음(I-18) | RX-10(lane Port 래퍼 hook이 잠금 직후 시계를 기한 뒤로 — REJECTED(APPROVAL_EXECUTION_EXPIRED), decided_at = 잠금 뒤 시각) |
| m-2 | 결정 Port 래퍼 INSERT 직전 CyclicBarrier — (a) 같은 승인·같은 내용(원 파일 없음 경로 — 파일 잠금 없음) INSERT 2회 → 1062 → 재시도 재생, 결정 1행, (b) 같은 승인 ID·다른 원 파일 → 진 쪽 봉인까지 rollback → 재시도 CONFLICT, 새 파일 1개 | RX-11 (a)·(b) |
| m-3 | 재매입 vs 같은 lane 정기 봉인, 두 잠금 순서 강제(I-5 근거 정정) | RX-12 (1)·(2) |
| m-4 | 결과 마감으로 실제 UNKNOWN을 만든 뒤 UNKNOWN_NOT_RECONCILED(고정 상태 UNKNOWN·REJECTED 둘 다, 미리 보기도) | RX-4U |
| m-5 | 원 파일 상계 2,000 + 뒤 불요 1,000 → 재매입 7,000·상계 3,000(각 한 번), 두 지표 불변 | RX-13 |
| m-6 | 승인·결정 Port 호출자 = 실행 서비스 + JPA 구현만(시험 소스 포함 import 시 위반 검출 확인) | ArchitectureSpec "ACQ-ADMIN-001 rev 0.3 (review m-6) …" |
| m-7 | 같은 TX에 실제 lane·파일·레코드 → 결정 없는 승인 1452가 `fk_acquisition_reacquisition_approval_decision`, 결정 넣으면 승인 INSERT 성공, 전부 rollback | FinancialDatabaseAccountSpec "ACQ-ADMIN-001 B1 (V18 …)" |
| n-1 | 제목·rev, §3.2 의사코드·잠금 순서(파일 → lane → 결제별), §6 decision 값(NOT_REQUIRED)·사람 CHECK 없는 이유 | 문서 |
| n-5 | 조작 키 kid = Inbox kid(바이트 다름) 거부 — 단위·Spring 기동 | FinancialAdminChannelSettingsSpec "(… n-5) an operation key whose kid equals …", "(rev 0.3 — n-5) an operation key id shared …" |
| m-8 | 코드 변경 없음 — §15 L-9 B2 할 일 | — |
| n-2·n-3·n-4 | (rev 0.3) 원문 미전달 → (rev 0.4) 원문·처리 사유를 §15 L-10에 기록(n-2 유지·I-13, n-3 B2, n-4 유지, n-6 미처리) | — |

RED 증거(같은 시험, 고치기 전 동작으로 되돌린 상태 — 되돌린 두 파일은 백업 sha256으로 원복 확인):

| 실행 | 결과 |
|---|---|
| RED: `GENERATION_ZERO_MISMATCH` 세대 조건 제거 + `REACQUISITION_MISMATCH` 무효(`1 = 0`) + 판정 시각을 잠금 전 값으로 | ReacquisitionExecutionDbSpec 22 중 **5 실패**: RX-1 MJ-1 gauge(expected 0 but was 1), RX-1 새 검사(expected (0, 1, 1) but was (0, 0, 0)), RX-10(expected REJECTED/EXPIRED but was EXECUTED), RX-13(netting gauge 3 → 4), RX-14(예외 없음) |
| GREEN | ReacquisitionExecutionDbSpec 22/0 fail/0 skip |
| 변이 M3(결정 INSERT 1062 `UniqueConflict`를 잡고 계속) | RX-11 **RED**(1 fail): `UnexpectedRollbackException` — Hibernate가 flush 실패로 TX를 rollback-only로 표시해 진 쪽 효과도 커밋되지 않는다(이중 방어). 즉 "잡고 계속"은 결정 없는 효과를 만들지 못하고 실행 실패로 드러난다. 원복 sha256 확인 |
| 변이 n-5(키 분리 검사에서 조작 키 제외) | FinancialAdminChannelSettingsSpec **3 실패**(기존 조작 키 바이트 공유 1 + 새 n-5 2). 원복 sha256 확인 |
| m-6 | 규칙 안에 음성 대조 포함(시험 소스까지 import하면 ReacquisitionExecutionDbSpec이 위반으로 잡힘) |

GREEN 회귀(단일 spec): ReacquisitionExecutionDbSpec 22(이전 12, +10), ArchitectureSpec 24(이전 23, +1), FinancialAdminChannelSettingsSpec 13(이전 11, +2), FinancialDatabaseAccountSpec 23(같음 — 기존 시험 강화), AcquiringOperationsDbSpec 6, AcquisitionSealDbSpec 17, AcquirerResponseDbSpec 24 — 모두 0 fail/0 skip. financial-batch 시험 수 하한 391 → 404(+13). 모듈 전체 check는 Coordinator가 실행한다.

8대 원칙(rev 0.3 증거): Financial Consistency(RX-13 각 취소 한 번, I-17 세대별 상계 대조) · Transaction Safety(RX-14 무결성 실패 전체 rollback, RX-11(b) 1062 진 쪽 봉인 rollback, M3) · Idempotency(RX-11(a) 1062 → 재생) · Concurrency(RX-11 결정 INSERT 실제 겹침, RX-12 봉인과 두 잠금 순서) · Recoverability(RX-10 기한 지남은 효과 없는 결정, RX-14는 결정 없음 → ADMIN 회복 경로) · Reconciliation(RX-4U 실제 UNKNOWN 거절, 지표 2종) · Auditability(decided_at = 판정 시각, 결정 FK 실증 m-7) · Observability(`integrity.reacquisition_netting_mismatch` — label 없음, ID·금액 없음).
Kafka/Redis는 쓰지 않았다 — 위 경합·멱등은 FINANCIAL 로컬 TX·행 잠금·UNIQUE·FK가 지킨다.

JPA 메모(이번 변경): 결정 Entity는 `persist` + `flush`라 1062가 그 호출에서 `UniqueConflict`로 나온다. Hibernate는 flush 실패 뒤 세션·TX를 rollback-only로 표시하므로 같은 TX를 계속 쓸 수 없다 — 그래서 재시도는 반드시 새 TX(`AcquiringLocalTransactions`)이고, M3처럼 잡아도 커밋되지 않는다. 무결성 대조는 native count라 직전 `files.insert`의 명시 `flush()` 뒤에 실행해야 새 레코드를 본다(지금 순서).

### 17.7 rev 0.4 — B1-A(ADMIN 절반) 실행 기록(2026-11-07 KST, Implementer 실행, Verifier·Reviewer 재검증 대상)

환경: WSL Ubuntu, `/tmp/acqa/run.sh`(`./gradlew --no-daemon -q :apps:internal-admin:test --tests <spec 하나>` foreground), Testcontainers MySQL 8.4.11, FINANCIAL은 실제 HTTP 대역
`StubFinancialOpsServer`(서명을 앱과 독립으로 조회 키·조작 키 각각 검증, acd1 내용 hash도 시험 쪽 독립 구현). Vitest는 Gradle이 받은 node 24.22.1로 파일 단위(`vitest run <파일>`).
Demo·/tmp/paycore-demo·Demo MySQL은 건드리지 않았다. financial-batch 코드 변경 없음. 기존 시험 기대값·게이트 기준 완화 없음(기존 목록·권한 표 기대값에 새 표·새 종류를 **추가**만 — AdminSchemaSpec 표 목록,
AdminDatabaseAccountSpec 표 권한·append-only 표, AdminPersistenceMappingSpec Entity 대조, labels.test 서버 enum 목록).

**변경(apps/internal-admin)**
- migration V7(`admin_approval_request` kind·target·owner_states CHECK 확장 — 파일 대상은 ACQ_REACQUISITION만·버전 0·파일 ID 문법, `unresolved_key`에 `ACQFILE:`, 실행 행 지문 `^(oct1|acd1):`,
  `admin_acquiring_ops_command` INSERT-only), runtime V8(새 표 SELECT, INSERT).
- Domain: `ApprovalKind.ACQ_REACQUISITION`(OWNER_SERVICE, OPERATOR), `ApprovalTargetKind.ACQUISITION_FILE`, `ApprovalTarget.AcquisitionFile`, `ApprovalParticipants.distinct`,
  `ApprovalRequest.executedWith(code)`, `OwnerExecution` 지문·참조 문법, `PinnedReacquisitionTarget`, `ReacquisitionRequestSnapshot`, `ReacquisitionInputRules`(C-9 사유 코드 3개);
  financialops `domain.ReacquisitionCommandBody`(acd1 + 실행 본문 — FINANCIAL 독립 벡터 `acd1:8ea4f6b6…cf7f`와 일치).
- financialops: 설정 `operation-key-id`·`operation-signing-key`·`operation-permits`·`operation-execution-window`(필수), 기동 검사(조작 키 kid·바이트 ≠ 조회 키, ≠ PAYMENT·MASTER 키,
  LIVE 시험 조작 키 거부), `HttpFinancialOpsGateway`가 조작 Port(`AcquiringOpsGateway` — 조작 키·조작 permit, 결정 모양 fail-closed)도 구현, `AcquiringOpsService`(I-16 응답 분류),
  provided `AcquiringOpsClient`·`ReacquisitionContract`·`AcquiringOpsPolicy`, 화면 미리 보기 `POST /api/acquisition-files/{fileId}/reacquisition-targets`(조회 키·감사).
- approval: `AcquiringOpsApprovalService`(요청 TX·승인 A/TX1/C/TX2), `AcquiringOutcomes`(결과·경보·내용 대조), `OwnerServiceApprovalService.approve` 종류 분기(PAYMENT 경로는 잠근 행이
  운영자 보정 취소일 때만 — 종류 검사가 실행 모델 검사보다 좁아짐, 완화 아님), `OwnerOutcomeApplier`(target_ref = 소유 대상 참조, `Completed(NOT_REQUIRED)`), `OwnerExecutionRecoveryService`
  재매입 분기(결정 조회 → 기한 확인 → 참여자 → 무결성 플래그 → 같은 바이트 재전송), `ApprovalService` 감사 target_ref·0단계 대상 일반화, JPA 대상 매핑(버전 0 아니면 재구성 실패),
  `JpaAcquiringOpsCommandEntity`(@Immutable)·Store, jOOQ 상세 `acquiring`, `POST /api/approvals/acq-reacquisition`.
- 화면: 매입 현황 상세 "재매입 요청" 패널(OPERATOR만 표시 — 후보 선택 → 미리 보기(eligible·불가 사유·예상 순액) → 모두 가능할 때만 사유 코드·근거·사유 → 요청, §5.3 확인 문구), 목록에 재매입 파일의
  원 파일 표시, 승인함 새 종류 "재매입"·상세(고정 값 vs 현재 값, "승인 시 무효" 경고, 결과 문구 EXECUTED 새 파일 / NOT_REQUIRED / OWNER_REJECTED / 자동 확인 중).
- 설정: 운영 application.yml에 `PAYCORE_ADMIN_FINANCIAL_OPS_OPERATION_KEY_ID`·`_SIGNING_KEY`, `.env.example` 새 변수(값 없음). **Demo internal-admin 재기동에는 이 두 변수가 필요하다**
  (FINANCIAL `PAYCORE_FINANCIAL_ADMIN_OPERATION_*`와 같은 값 — Coordinator, L-7과 함께).

**RED → GREEN(단일 spec)**

| Spec | RED | GREEN |
|---|---|---|
| `ReacquisitionCommandBodySpec`(RB-1..3) | `TODO()` stub — 3/3 실패(NotImplementedError) | 3/0 fail(독립 벡터 일치) |
| `AcquiringOpsApprovalDomainSpec`(AD-1..3) | stub — 7 중 4 실패(NotImplementedError 3, 지문 oct1 전용 1; 종류·대상 enum은 stub 단계에 이미 넣어 3건 통과) | 7/0 |
| `AdminSchemaSpec`(+4 V7) | V7·V8을 잠시 치운 상태 — 30 중 5 실패(V7 4 + 표 목록) | 30/0 |
| `AcquiringOpsApprovalFlowSpec`(AQ-1..4, 12) | 서비스 `TODO()` stub — 12/12 실패 | 12/0 |
| `AcquiringOpsGatewaySpec`(GW-1..3) | 구현 뒤 작성 — 변이 MA2·MA3로 RED 확인(아래) | 3/0 |
| `AcquiringOpsApprovalHttpSpec`(HT-1·2) | 구현 뒤 작성(첫 실행 실패는 시험 코드의 JSON 반복 방식 — 시험 수정) | 2/0 |
| Vitest `AcquisitionsScreen.reacquisition.test.tsx`(4)·`ApprovalsScreen.acquiring.test.tsx`(4) | 구현 뒤 작성 — 변이 FM1·FM2로 RED 확인(아래) | 8/0 |

핵심 변이(각 RED 확인 후 원복 — 원복 전후 sha256 앞 12자 동일):

| 변이 | 대상 | 결과 |
|---|---|---|
| MA1 승인 TX1의 고정 값 vs 현재 비교 생략 + MA2 조작 호출을 조회 키로 서명(D-ACQA-001 위반) + MA3 조작 kid = 조회 kid 기동 검사 제거 | AcquiringOpsApprovalFlowSpec / AcquiringOpsGatewaySpec | RED(12 중 4 / 3 중 1) |
| FM1 승인함이 파일 대상 상세를 열지 않음 + FM2 묶음 중 하나만 가능해도 요청 허용(D-ACQA-003 위반) | Vitest 두 파일 | RED(8 중 4) |

회귀(단일 spec, 모두 0 fail/0 skip): ArchitectureSpec 34(+1 — AcquiringOpsClient 호출자 제한·approval→financialops 내부 금지), OwnerServiceApprovalFlowSpec 13, OwnerExecutionRecoverySpec 26,
AdminDatabaseAccountSpec 24, AdminPersistenceMappingSpec 10, FinancialOpsGatewaySpec 17, AcquisitionLookupServiceSpec 10, AcquisitionLookupHttpSpec 12, OwnerServiceApprovalHttpSpec 3,
OwnerServiceOnLegacyPathSpec 7, ApprovalDecisionTableSpec 19(종류 전수 표에 ACQ_REACQUISITION **추가** — 기존 행 불변), ApprovalTargetSpec 7, OwnerExecutionSpec 8, UnresolvedApprovalRaceSpec 4,
OperatorDecisionReconciliationRunSpec 14, OwnerServicePersistenceSpec 5, OwnerApprovalFailureInjectionSpec 4. 새 시험 +32건(Kotlin: RB 3·AD 7·Schema +4·Flow 12·Gateway 3·HTTP 2·Architecture +1) → internal-admin
시험 수 하한 699 → 731. Vitest +8(174 → 182). 화면 문구 "재매입은 2인 승인 화면에서 진행합니다(이 화면은 조회 전용)"은 기존 Vitest가 고정해 이번에 바꾸지 않았다(재매입 요청 패널이 생겨 문구가
낡음 — 다음 회차 문구 정리 후보, 기대값 변경은 문구만).
Vitest 회귀(파일 단위): ApprovalsScreen 3, ApprovalsScreen.owner 8, AcquisitionsScreen 6, errorCodes 5, labels 10, App 12, OwnerApprovalDetail.compare 5, noRuntimeStyles 9, `tsc -p` 0 오류.
internal-admin 모듈 전체 check·Vitest 전체는 Coordinator가 실행한다.

**8대 원칙 증거(B1-A)**: Financial Consistency(금액은 표시용 — 실행 TX가 권위, ADMIN은 금액을 보내지 않는다 I-10, 결정 내용 대조) · Transaction Safety(요청 TX·TX1·TX2 각 로컬, FINANCIAL 호출은 TX 밖 —
client가 TX 안 호출 거부, TX1 실패면 상태·TOTP 불변) · Idempotency(같은 저장 바이트 재전송, FINANCIAL approvalId 결정 재생, ADMIN `ACQFILE:` UNIQUE 1062 → DUPLICATE_UNRESOLVED — AQ-1·AQ-3) ·
Concurrency(승인 행 FOR UPDATE + @Version, 미해결 UNIQUE, 회복 선점 SKIP LOCKED 공유) · Recoverability(응답 유실 503 → EXECUTION_UNKNOWN → 결정 조회로 EXECUTED, 기한 지나면 기한 확인 →
OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED) — AQ-3) · Reconciliation(UNKNOWN 레코드 선택 불가·FINANCIAL 거절 UNKNOWN_NOT_RECONCILED, 결정 목록 대사는 B5) · Auditability(요청·실행 중·실행·소유 거절·
무효·거절·철회 감사, target_ref = 원 파일 ID, 미리 보기 `ACQUISITION_FILE_VIEWED`, 사유 문구·근거는 상세 응답에 없음 — HT-2) · Observability(`paycore.admin.approval{operation,kind=ACQ_REACQUISITION}`,
`ops.integrity_alert{code}`(DECISION_CONTENT_MISMATCH·APPROVAL_CONFLICT·400 code), `ops.resend{result}` — label에 ID·금액 없음). Kafka/Redis 없음 — 미해결 하나·최초 결정은 소유 DB 제약이 지킨다.

JPA 메모(이번 변경): `JpaAcquiringOpsCommandEntity`는 `@Immutable` + 모든 열 `updatable=false`, Store는 persist + flush(1062가 그 호출에서 보인다). MySQL JSON 열은 키 순서·공백을 정규화하므로
`pinned_target`은 문자열 비교가 아니라 필드로 엄격 해석한다(자료형 강제 변환 없음). 승인 행 대상 매핑은 `target_kind`로 sealed `ApprovalTarget`을 고르고, 파일 대상의 버전 열이 0이 아니면 재구성을
거부한다(fail-closed — Domain과 DB CHECK 두 겹). jOOQ 상세 조회는 명령 판정에 쓰지 않는다(판정은 잠근 JPA 행 + FINANCIAL 재조회).

**남은 일(B2 이후)**: L-9(전송 전 늦은 ACCEPTED — 재매입 파일 전송 차단), n-3 Domain 방어 검사, n-6 counter, 결정 목록 대사(B5), golden `admin-financial-ops-api-v1`(§4 — 이번 회차에 만들지 않음:
ADMIN·FINANCIAL 응답 모양 대조는 `FinancialResponseShape`의 키 집합을 FINANCIAL 컨트롤러 키 순서와 손으로 맞췄다 — Reviewer 확인 대상), Demo 화면 확인(실제 FINANCIAL·Simulator — C-8, Coordinator).

### 17.8 rev 0.5 — B1-A 독립 검토(FAIL) 지적·모듈 검사 실패 반영(2026-11-07 KST, Implementer 실행, Verifier·Reviewer 재검증 대상)

환경: §17.7과 같음(WSL Ubuntu, `./gradlew --no-daemon -q :apps:internal-admin:test --tests <spec 하나>` foreground, Testcontainers MySQL 8.4.11, FINANCIAL = `StubFinancialOpsServer`; Vitest = Gradle node 24.22.1
파일 단위). Demo·Demo MySQL·financial-batch 코드는 건드리지 않았다. 기존 시험 기대값·게이트 기준 완화 없음 — 바꾼 기대값은 지적이 요구한 것만(아래 "기대값 변경" 3건, 근거 기록).

**모듈 검사 실패 — `LoginServiceSpec`(LoginPathEquivalence) TX 수 2: B1-A와 무관한 기존 불안정성(운영 코드·시험 미변경)**
- 원인: 시험 계측 `CountingTransactionManager`(LoginProbeConfig — `transactionManager` 빈을 감싼다)의 `begins`는 **스레드 구분 없는 전역 계수**다. Spring Session JDBC(4.1.1)
  `JdbcHttpSessionConfiguration`은 유일한 `PlatformTransactionManager`(= 감싼 빈)로 `TransactionTemplate`을 만들고, 만료 정리 `cleanUpExpiredSessions`를 cron
  `paycore.admin.session.jdbc.cleanup-cron: "0 * * * * *"`(매분 0초 — `application.yml`, ADMIN-BOOT-001 514a747부터, 시험 설정이 끄지 않음)로 스케줄러 스레드에서 실행한다(javap로
  `cleanUpExpiredSessions` → `transactionOperations.execute` 확인). 그래서 측정 구간이 매분 0초에 걸리면 다른 스레드의 정리 TX가 +1로 섞인다.
- 근거: 실패 보고서(Coordinator 모듈 실행 `TEST-…LoginServiceSpec.xml`)에서 컨텍스트 기동 완료 `02:10:59.537`, 실패 시험 소요 0.621초 → 측정 구간 02:10:59.54~02:11:00.16이
  **02:11:00.000을 지난다**. 실패는 7개 probe 중 하나(index 2 `NOT A VALID LOGIN`)에서만 TX 2(BCrypt·감사 1 그대로) — 다른 스레드 TX 한 번과 맞는다.
- 재현율: 단일 spec 3회(`--rerun`, 02:18~02:21 KST) 3/3 통과(11 tests, 0 fail). 이론 재현율 ≈ 측정 구간(수백 ms)/60초 — 실행당 약 1% 이하. B1-A 변경 파일(diff)에 로그인·세션·
  계측 코드 없음, 새 TX 리스너·스케줄러 없음(B1-A의 회복 job 분기는 시험에서 `recovery.enabled=false`).
- 조치: 지시대로 시험·운영 코드 미변경. 후보(Coordinator 판단): 시험 설정에서 세션 정리 cron을 끄거나(`cleanup-cron: "-"`) 계수를 측정 스레드로 한정.

**지적별 처리(RED → GREEN)**

| 지적 | 처리 | 시험(RED 증거) |
|---|---|---|
| M-1 근거 참조 카드 번호 | `ReacquisitionInputRules.problem`: 형식 뒤 `containsCardNumber` → `evidenceRef`·`CARD_NUMBER_PATTERN`. `ReacquisitionRequestSnapshot.init` 같은 불변식. 화면: 힌트 "카드 번호 금지", 같은 정규식이면 버튼 비활성 + 경고(role=alert), 서버 입력 거절 필드 표시(`INPUT_FIELDS`에 근거 참조·사유 코드). FINANCIAL 파서 검사·DB CHECK는 B2 판단/기록(I-23) | Domain `AD-3` 입력 규칙(+3 사례)·Snapshot 거부(새 Then) — RED: 9 중 2 실패(값 null·예외 없음). Flow `AQ-1` 400 목록(+2) — 변이 MF1(입력 규칙 줄 삭제)로 RED: Snapshot 불변식이 `IllegalArgumentException`으로 잡음(두 겹 확인). Vitest "card number…" — RED |
| m-1 400·409 문구 | `errorCodes.ts` `ACQUISITION_FILE_NOT_FOUND: [400, 404]`(PAYMENT_NOT_FOUND 선례), `TARGET_NOT_FOUND: [409]` + 문구(계정 명령 409에도 쓰여 일반 문구) | Vitest errorCodes "B1-A review m-1" — RED(`REQUEST_REJECTED`). 승인함 "409 TARGET_NOT_FOUND dedicated message" — RED. 요청 화면 400 사례는 `apiError`가 상태 매핑을 거치지 않아 처음부터 통과(매핑 RED는 errorCodes 시험이 담당) |
| m-2 상세 근거 | jOOQ 상세 `a.EVIDENCE_REF` → `AcquiringDetailRow/View.evidenceRef` → 응답 `acquiring.evidenceRef`, 화면 "근거 참조: …". 사유 문구 계속 없음, 목록에 근거 없음(I-24 — D-OPS-010과의 차이는 Coordinator 확인 후보) | HTTP `HT-2`(기대값 변경 ①: "근거 없음" → "근거 있음·사유 없음·목록에 근거 없음") — 변이(컨트롤러 evidenceRef 제거)로 RED 1/2. Vitest "m-2" — RED |
| m-3 시험 공백 | (a) OPERATOR+APPROVER 같은 사람 → `Refused(SELF_APPROVAL)`, PENDING·POST 0·실행 행 0·TOTP 불변·거부 감사. 기존 시험은 이름을 "역할 부족 Denied"로 고침(기대값 불변). (b) 틀린 TOTP → `StepUpFailed`, TX1 rollback(실행 행 0·EXECUTING 감사 없음), 실패 +1(별도 TX), 단계 미소비, 뒤에 같은 승인 정상 실행. (c) `UnresolvedApprovalRaceSpec`에 `ACQFILE:` 실제 두 연결(두 번째가 UNIQUE 검사에서 기다림을 performance_schema로 확인 → 1062 → DuplicateUnresolved, 1행) + 종료 상태 해제·UNKNOWN 유지. (d) 400 → OWNER_REJECTED(REQUEST_MALFORMED) + `ops.integrity_alert{code}` +1 + 파일 해제 | 기존 동작을 고정하는 시험 — 첫 실행 GREEN(RED 없음, 변이로 확인하지 않음) |
| m-4 종류↔접두 | `OwnerExecution.conformsTo(kind)`·`referenceFits`·`fingerprintPrefix`, Factory `start(kind, …)`(두 서비스 운영 경로), 결과 반영·회복 job 대조(I-25) | Domain AD-2 새 Then — RED(stub `TODO()` NotImplementedError). Flow AQ-3 새 Then 2개 — 변이 MF2(회복 job 대조 끔: 결정 조회 1회 나감)·MF3(참조 대조 끔: EXECUTED로 반영)로 RED |
| m-5 "조회 전용" 문구 | 역할별 문구(§5.1), KDoc 갱신 | `AcquisitionsScreen.test.tsx` 기대값 변경 ②(문구만 — 비OPERATOR 문구), Vitest "m-5" — RED |
| n-1 | `PinnedReacquisitionTarget.parse` 삭제(운영 미사용). 그 왕복 단언 한 줄을 지움(기대값 변경 ③ — 지운 코드의 시험, 결정적 JSON 모양 단언은 유지) | — |
| n-2 | `PinnedReacquisitionItem`을 별도 파일로(내용 불변) | — |
| n-3 | `AcquiringOpsGateway.execute(approvalId, body)` + 응답 승인 ID 대조(I-26) | Gateway GW-3 새 Then — RED(다른 승인 결정이 Decided로 통과) |
| n-4 | 미리 보기·요청 중 체크박스 비활성 | Vitest "n-4" — RED |
| n-5 | 예상 순액 칸: 고정 값 + "현재 같음 / 현재 N원 / 현재 -"(§5.2) | Vitest "n-5" — RED |
| n-6 | DB 이중 방어(근거 카드 패턴 CHECK·종류↔지문 접두 CHECK)는 기록만(I-23·I-25) — V7 변경 없음 | — |

모든 변이는 RED 확인 후 원복(원복 전후 sha256 앞 12자 동일: `ReacquisitionInputRules` 3176c925cc84, `OwnerExecutionRecoveryService` 357330b3accb,
`OwnerOutcomeApplier` 6c04a87e70ea, `ApprovalController` e86bc19df16b).

**GREEN·회귀(단일 spec, 모두 0 fail/0 skip)**: AcquiringOpsApprovalDomainSpec 9(+2), AcquiringOpsApprovalFlowSpec 17(+5), UnresolvedApprovalRaceSpec 6(+2), AcquiringOpsApprovalHttpSpec 2,
AcquiringOpsGatewaySpec 4(+1), AcquisitionLookupServiceSpec 10, 회귀 ArchitectureSpec 34, OwnerServiceApprovalFlowSpec 13, OwnerExecutionRecoverySpec 26, OwnerApprovalStepUpSpec 3, OwnerExecutionSpec 8, OwnerApprovalFailureInjectionSpec 4, OwnerOpsMetricsSpec 1. Kotlin 시험 +10 → internal-admin 시험 수 하한 731 → 741.
Vitest(파일 단위): errorCodes 6(+1), AcquisitionsScreen.reacquisition 8(+4), ApprovalsScreen.acquiring 7(+3), AcquisitionsScreen 6, ApprovalsScreen 3, ApprovalsScreen.owner 8, labels 10, App 12,
OwnerApprovalDetail.compare 5, noRuntimeStyles 9, PaymentOpsScreen 7, AccountsScreen 6, ErrorScreen 4, `tsc -p` 0 오류. Vitest +8 → 모듈 기준 157 → 165. internal-admin 모듈 전체 check·Vitest 전체는 Coordinator.

**8대 원칙 증거(rev 0.5 추가분)**: Financial Consistency(카드 번호가 FINANCIAL append-only 기록으로 가지 않음 — 입력·Snapshot 두 겹) · Transaction Safety(TOTP 실패 TX1 rollback, 종류 불일치는
rollback 뒤 별도 TX 감사) · Idempotency(실행 POST 응답도 승인 ID 대응 — 다른 승인 결정을 내 결과로 반영하지 않음) · Concurrency(`ACQFILE:` UNIQUE를 실제 두 연결로 증명) · Recoverability(종류 불일치
행은 재전송하지 않고 무결성 위반으로 멈춤 — 조사 대상) · Reconciliation(변경 없음) · Auditability(자기 승인 거부·무결성 위반 감사 단언) · Observability(400 경보 delta 단언). Kafka/Redis 없음.

JPA 메모: 이번 변경은 JPA Entity를 바꾸지 않았다. 상세의 근거 참조는 jOOQ 조회(CQRS — 명령 판정에 쓰지 않음)에만 더했고, 종류↔지문 대조는 JPA 재구성(`JpaOwnerExecutionRepository.toDomain`)이 아니라
승인 행을 함께 아는 Application(결과 반영·회복 job)에서 한다 — 실행 행 표에 종류 열이 없어 Entity만으로는 판정할 수 없기 때문이다.

**미결(Coordinator)**: I-24 근거 참조 노출 범위(D-OPS-010 수준으로 좁힐지 — 사용자 결정 필요 시 질문), I-23 FINANCIAL 파서 카드 검사(B2), LoginServiceSpec 계측 안정화 방식(시험 설정 변경 승인 필요).
화면 캡처(시험 대역 데이터 — Demo 아님): scratchpad `demo/acqadmin/acqadmin-0*-r2.png`(01·01b·02·03 다시, 04 카드 번호 경고 새로).


### 17.9 rev 0.6 — B1-A 재검토 지적·D-ACQA-005 반영(2026-11-07 KST, Implementer 실행, Verifier·Reviewer 재검증 대상)

환경: §17.8과 같음(WSL Ubuntu, `./gradlew --no-daemon -q :apps:internal-admin:test --tests <spec 하나>` foreground, Testcontainers MySQL 8.4.11, FINANCIAL = `StubFinancialOpsServer`,
PAYMENT = `StubPaymentOpsServer`; Vitest = Gradle node 24.22.1 `vitest run <파일 하나>`, `tsc -p . --noEmit`). Demo·Demo MySQL·financial-batch 코드는 건드리지 않았다. 모듈 전체 실행은 Coordinator.
기존 시험 기대값·게이트 기준 완화 없음 — 바꾼 기대값은 HT-2 하나(근거 D-ACQA-005, 아래).

**1. 사용자 결정 D-ACQA-005(Q-ACQA-09, 2026-11-07 — "보정 취소와 같게 (Recommended)") — I-24 대체**

- 선례(D-OPS-010 `OperatorCancelJustificationService`)를 그대로 따른다: **같은 endpoint** `GET /api/approvals/{id}/justification`, 같은 판정(조회자 계정 현재 행 ACTIVE + APPROVER 역할,
  또는 그 승인의 요청자 본인 — 서버 판정), **같은 감사 action** `APPROVAL_JUSTIFICATION_VIEWED`(시도마다 1행, 결과 OK·FORBIDDEN, detail 없음 — 문구 없음, 같은 TX, 감사 실패면 503
  `AUDIT_UNAVAILABLE`로 문구 없음), 같은 조회자별 분당 상한(I-131 — 두 종류가 한 창을 공유), 응답 코드 200/403/404/429/503 그대로. 서비스가 승인 종류로 갈라
  `ACQ_REACQUISITION`이면 `AcquiringOpsCommandStore.find`로 근거 참조만 읽어 `JustificationResult.EvidenceFound` → 200 `{"approvalId","evidenceRef"}`. 감사 target_ref = 원 파일 ID.
  **사유 문구는 이 조회에도 넣지 않는다**(D-ACQA-005는 근거 참조만 정함 — I-4 그대로. 운영자 보정 취소의 사유 문구 노출은 D-OPS-010이 따로 정한 것).
- 일반 상세(`GET /api/approvals/{id}` — 조회 4역할 공통)·목록에서 근거 참조를 뺐다: jOOQ 상세 조회가 `EVIDENCE_REF` 열을 더는 고르지 않고(CQRS 조회 모델 `AcquiringDetailRow/View`에서 필드 삭제),
  컨트롤러 `acquiring` JSON에서도 뺐다. 명령 쪽(JPA `JpaAcquiringOpsCommandEntity`·FINANCIAL 실행 본문의 `evidenceRef`)은 바뀌지 않는다.
- 화면: `ReacquisitionApprovalDetail`이 "근거 참조: …" 줄을 지우고, APPROVER 역할이거나 요청자 본인(`viewer.actorId === item.requestedBy`)일 때만 "근거 보기" 버튼(표시만 — 서버가 판정).
  누르면 `api.reacquisitionEvidence(id)`(같은 `/justification` URL) 결과를 화면 상태에만 둔다(저장소·URL 없음). 403 등은 공용 오류 문구, 세션 종료 코드는 기존 `onSessionEnded`. 그 밖 역할은 버튼 없음.
- 기대값 변경 ②(HT-2, `AcquiringOpsApprovalHttpSpec`): rev 0.5 "상세에 근거 참조 있음" → "상세 `acquiring`에 `evidenceRef` 키 없음·본문에 근거 문자열 없음(OPERATOR·VIEWER 둘 다)". 근거: D-ACQA-005.
  Vitest "B1-A review m-2"(상세에 "근거 참조: INQ-…")는 D-ACQA-005 시험으로 대체(같은 근거).

| 시험 | 내용 | RED(구현 전) |
|---|---|---|
| HT-2(기대값 변경) | 상세 `acquiring.has("evidenceRef") == false`, 본문·VIEWER 상세에 근거 문자열 없음, 목록에 없음 | 실패: index 4 `true`(키 있음) |
| HT-3 ① 새 | VIEWER·OPERATOR(요청자 아님)·SECURITY_ADMIN → 403 `{"error":"FORBIDDEN"}`, 감사 FORBIDDEN 3행 | 실패: 404 `NOT_FOUND`(종류 분기 없음) |
| HT-3 ② 새 | 요청자 본인·APPROVER → 200 `{"approvalId","evidenceRef":"INQ-EVD-0005"}`, no-store, 조회마다 OK 감사 **정확히 1행**(target_ref = 원 파일, detail 없음, 감사에 근거·사유 문자열 없음) | 실패: 404 |
| HT-3 ③ 새 | 요청자 본인의 상세·목록에도 근거·사유 문자열 없음, `acquiring`에 키 없음 | 실패: 상세에 `"evidenceRef":"INQ-EVD-0005"` |
| Vitest D-ACQA-005 ①(대체) | 상세에 근거 없음 → APPROVER "근거 보기" → 근거 표시, 호출 인자 승인 ID, 저장소·URL 비어 있음 | 실패: 상세에 `INQ-2026-0001` 보임 / 버튼 없음 |
| Vitest ② 새 | 요청자(OPERATOR, 승인자 아님) 버튼 있음, 403이면 "권한이 없습니다"·근거 없음 | 실패: 버튼 없음 |
| Vitest ③ 새 | VIEWER·SECURITY_ADMIN·요청자 아닌 OPERATOR → 버튼 없음·근거 없음 | 실패: 상세에 근거 보임 |

RED 실행: `AcquiringOpsApprovalHttpSpec` 09:06:55~09:09:42 KST exit 1 — tests 5, failures 4(HT-1만 통과). Vitest `ApprovalsScreen.acquiring.test.tsx` 09:06:47 exit 1 — 9 중 3 실패.

**2. LoginServiceSpec 불안정성 — Coordinator 결정(시험 설정만, 가역, 기대값 불변)**

- 조치: `apps/internal-admin/src/test/resources/config/application.yml`에 `spring.session.jdbc.cleanup-cron: "-"`(시험 classpath 전용, 운영 `application.yml`의 `"0 * * * * *"` 그대로).
- Spring Session 4.1.1 확인(javap, `spring-session-jdbc-4.1.1.jar`): `JdbcIndexedSessionRepository.afterPropertiesSet`이 `"-".equals(cleanupCron)`이면(`ifne 51` → return) `ThreadPoolTaskScheduler`를
  만들지 않고 `CronTrigger` 스케줄도 하지 않는다. `setCleanupCron`도 `"-"`이면 cron 식 검사를 건너뛴다. Boot 4.1.1 `JdbcSessionAutoConfiguration`은 `JdbcSessionProperties::getCleanupCron` →
  `JdbcIndexedSessionRepository::setCleanupCron`으로 값을 넘긴다(상수 풀 MethodHandle 확인). 즉 이 값이면 정리 스케줄 스레드가 없다.
- 정리 동작을 직접 단언하는 시험 조사: `AnonymousSessionGrowthSpec`은 `sessions.cleanUpExpiredSessions()`를 **직접 호출**해 만료 행 삭제를 단언한다(cron 무관 — 오히려 배경 정리가
  `rows()` 계수에 끼어들 여지가 사라짐). `AdminDatabaseAccountSpec`은 세션 표 DML 권한만, `LoginHttpSpec`은 세션 행을 직접 지운다. cron 스케줄 자체(값·스레드)를 단언하는 시험은 없다 → 끄기 진행.
- 실행: `LoginServiceSpec` 단일 — 결과는 아래 회귀 표. 불안정성은 확률적(매분 0초 경계)이라 단일 통과는 "재현 안 됨"일 뿐 증명이 아니다. 근거는 원인 제거(스케줄러 미생성 — javap)다.

**3. NIT — PAYMENT(운영자 보정 취소) 회복 분기 `conformsTo` 사전 대조(재매입 분기와 대칭, I-25 ③)**

- `OwnerExecutionRecoveryService.process`: 승인 상태 확인 직후, PAYMENT 결정 조회·기한 확인·재전송 **전에** `execution.conformsTo(request.kind)`가 거짓이면 `reportIntegrity(id, "execution_kind", paymentId)`
  하고 끝(외부 호출 없음, 상태 유지 — 행이 그대로면 backoff마다 감사 1행 반복, 기존 선례).
- 시험(`OwnerExecutionRecoverySpec` 새 Given): 공식 경로 EXECUTION_UNKNOWN 승인의 지문 접두를 root로 `acd1:`로 바꾼 뒤 회복 → 결정 조회 0·재전송 POST 그대로 1(승인 때 1회)·기한 확인 0,
  `integrity_violation{check=execution_kind}` +1, 감사 `SYSTEM_JOB|column=execution_kind|<paymentId>`. RED(구현 전, 09:09:48~09:11:57 exit 1, 27 중 1 실패): 결정 조회 1·POST 2.

**GREEN·회귀(단일 spec, 모두 0 fail/0 skip)**

| spec / 파일 | 결과 |
|---|---|
| AcquiringOpsApprovalHttpSpec | 5 tests(+3: HT-3 ①②③), 0 fail — 09:17:33~09:19:39 exit 0 |
| OwnerExecutionRecoverySpec | 27 tests(+1: NIT), 0 fail — ~09:21:23 exit 0 |
| OperatorCancelJustificationHttpSpec(D-OPS-010 선례 회귀) | 4, 0 fail — exit 0 |
| OperatorCancelJustificationWindowSpec(생성자 인자 하나 추가 — 기대값 불변) | 1, 0 fail — exit 0 |
| LoginServiceSpec(cron 끈 뒤 단일) | 11, 0 fail — 09:23:38~09:25:07 exit 0(spec 실행 55초 — 분 경계 00:25:00 포함) |
| AnonymousSessionGrowthSpec(정리 직접 호출 시험) | 1, 0 fail — 09:25:08~09:26:52 exit 0 |
| PaymentOpsNonDisclosureSpec·ArchitectureSpec·AcquiringOpsApprovalFlowSpec | **미실행** — 순차 회귀 실행이 09:26:52 이후 PaymentOpsNonDisclosureSpec 중 호스트 메모리 부족으로 도구가 중단(시험 실패 아님, 결과 없음). 지시에 따라 다시 돌리지 않음 → Coordinator 모듈 실행에서 확인 필요 |
| Vitest `ApprovalsScreen.acquiring.test.tsx` | 9(+2: 7 → 9 — m-2 1건 대체 + 새 2건), 0 fail |
| Vitest 회귀 `ApprovalsScreen.owner` 8 · `ApprovalsScreen` 3 · `api/client` 11 · `App` 12 · `OwnerApprovalDetail.compare` 5 | 0 fail |
| `tsc -p . --noEmit` | exit 0 |

시험 수: internal-admin Kotlin 하한 741 → **745**(+4), admin Vitest 하한 165 → **167**(+2). 끝에 `./gradlew --stop`(데몬 없음), Vite 서버 내림.

**8대 원칙 증거(rev 0.6 추가분)**: Financial Consistency(변경 없음 — 금액·상태 경로 미변경) · Transaction Safety(근거 조회의 허용 판정·감사가 한 TX, 감사 실패면 문구 없음 — 선례 코드 재사용) ·
Idempotency(변경 없음) · Concurrency(변경 없음) · Recoverability(PAYMENT 회복도 종류 불일치 행에 외부 호출 없음 — 재매입과 대칭) · Reconciliation(변경 없음) · Auditability(근거 조회 시도마다
감사 1행 — OK·FORBIDDEN, 문구 없음, HT-3 단언) · Observability(`integrity_violation{check=execution_kind}` delta 단언). Kafka/Redis 없음 — 이번 지적은 권한·감사·회복 대조로 해결하며 메시징이 대신하지 않는다.

JPA 메모: JPA Entity·Repository는 바꾸지 않았다. 근거 참조는 명령 Snapshot(`JpaAcquiringOpsCommandStore.find` → 순수 Domain `ReacquisitionRequestSnapshot`)에서 읽는다 — 권한 판정과 감사가
같은 짧은 TX 안이라 명령 쪽 저장 Port를 쓰는 선례(D-OPS-010이 `OperatorCancelCommandStore.find`를 씀)를 따랐다. 반대로 4역할 공통 jOOQ 상세(CQRS 조회 모델)에서는 그 열을 아예 고르지 않아
조회 경로로 새지 않게 했다.

가역 구현 선택(기록): 서비스·인터페이스 이름 `OperatorCancelJustification*`은 그대로 두고 종류 분기만 더함(이름 정리는 별도 — 공개 endpoint·감사 action 불변). 화면 API는 같은 URL에 별도 메서드
`reacquisitionEvidence`(응답 모양이 달라서).

**미결(Coordinator)**: 위 미실행 회귀 3건(모듈 실행으로 확인), I-23 FINANCIAL 파서 카드 검사(B2). D-ACQA-005로 I-24 미결은 닫힘.
화면 캡처(시험 대역 데이터 — Demo 아님, `shot.mjs` `ONLY=inbox SUFFIX=-r3`): scratchpad `demo/acqadmin/acqadmin-02-approvals-inbox-r3.png`(APPROVER — 상세에 근거 없음·"근거 보기" 버튼),
`acqadmin-02b-approvals-evidence-r3.png`(버튼을 누른 뒤 근거 참조 표시).

### 17.10 rev 0.7 — B2 재매입 전송·결과·배지(2026-11-07 KST, Implementer 실행, Verifier·Reviewer 재검증 대상)

환경: WSL Ubuntu, `./gradlew -q :apps:<module>:test --tests <spec 하나>` foreground(스크립트마다 끝에 `./gradlew --stop`), Testcontainers MySQL 8.4.11, 시험 SSHD; Vitest = Gradle node 24.22.1
`vitest run <파일 하나>`, `tsc -p . --noEmit`. Demo·Demo MySQL·`/tmp/paycore-demo`는 건드리지 않았다. 모듈 전체 실행은 Coordinator. Simulator 코드 변경 없음(C-8 — 시나리오 설정만으로 재현).
기존 시험 기대값·게이트 기준 완화 없음 — 바꾼 기대값은 아래 표에 근거와 함께 적었다.

**1. 변경 요약**

| 항목 | 내용 |
|---|---|
| 재매입 파일 전송·결과 | 전용 경로 없음 — 세대 ≥ 1 파일도 기존 `transmitPending`·`AcquirerResponseService`로 보내고 반영(ACCEPTED면 같은 TX 표지). RR-1·RR-2·AC9가 실제 SFTP·응답 서비스로 확인 |
| L-9 전송 보류 | `AcquisitionFile.withholdTransmission`(재매입 파일·점유 가능 상태만 → TRANSMISSION_WITHHELD, 종결), `NotClaimableReason.WITHHELD`, 점유 TX에서 다른 세대 파일 행 잠금 → 표지 재조회(I-28) → 보류 + 근거 행. V19(CHECK 확장·`acquisition_transmission_withhold`·SUPERSEDED 관찰 CHECK 확장), V20(SELECT, INSERT). 지표 gauge `files.transmission_withheld`, 확인 필요 사유 `TRANSMISSION_WITHHELD`, 검색 필터 값 |
| L-6·L-8 | CancelCandidateRule 세대 ≥ 1 정합(누적 한도 유지), I-17 ③ = 0 재정의(`AcquisitionNettingIntegritySql`) |
| 배지 3상태 | Domain `ReacquisitionBadgeRule`(레코드 단계·파일 배지), jOOQ Reader 단계 건수(표지·재매입 불요·더 높은 세대 상관 부질의), `AcquisitionFileViewService` 배지, JSON `reacquisitionInProgress` 추가, ADMIN 응답 모양(키 추가·전송 상태·확인 필요 사유 9개), 화면 "재매입 진행 중"·"전송 보류(대사)" |
| n-3·n-6·I-23·m-A·n-A | §15 L-10·§11 I-23·I-24 참고. I-23: FINANCIAL Domain `CardNumberPattern`(ADMIN과 같은 정규식) → `ReacquisitionCommand` 불변식 → 파서 400 `REQUEST_MALFORMED`. n-A: HT-3 ④는 감사 실패 주입 구성이 있는 `ApprovalFailureInjectionSpec`에 두었다(HTTP spec에 주입 구성을 더하면 Spring 컨텍스트가 하나 는다 — TEST-PERF-002). 서비스 결과 AuditUnavailable → 503 `AUDIT_UNAVAILABLE`은 기존 컨트롤러 분기 |

**2. 바뀐 기존 기대값과 근거**

| 시험 | rev 0.6 기대 | rev 0.7 기대 | 근거 |
|---|---|---|---|
| CancelCandidateRuleSpec CC-4 넷째 벡터 | 세대 1 원 SALE → INCONSISTENT | CC-8로 이동(결과별 판정). 자리에 원 레코드 종류·원승인액 불일치 벡터 2개 추가(강화) | §15 L-6 |
| ReacquisitionExecutionDbSpec RX-7 | `cancelWaiting = {INCONSISTENT:1}` | `{ORIGINAL_AWAITING_RESULT:1}`(불요 0·CANCEL 0 그대로) | §15 L-6 |
| AcquisitionFileViewServiceSpec "file rejection … candidate" | 입력 = 거절 건수·수신 축 | 입력 = Reader 단계 건수(같은 결과 true) | §5.1 L-9 배지 규칙·AC9·MINOR-6 |
| AdminAcquisitionApiSpec 목록 항목 키 | … `reacquisitionCandidate` | + `reacquisitionInProgress`(추가만) | §5.1 3상태 |
| FinancialDatabaseAccountSpec·MainMigrationLocationSpec | Flyway 1..18, 표 목록 | 1..20, + `acquisition_transmission_withhold`(Select,Insert·append-only 1142 목록 추가) | D-FFR-002(추가만) |
| ADMIN FinancialOpsGatewaySpec(stub 항목 JSON) | 키 집합 B1 | + `reacquisitionInProgress`, resultingState 값 집합 + TRANSMISSION_WITHHELD | FINANCIAL 저장 문법 = ADMIN 허용 문법 |

**3. RED·GREEN(단일 spec — 모두 0 skip)**

| spec / 파일 | RED(구현 전·변이) | GREEN |
|---|---|---|
| CancelCandidateRuleSpec | 09:53:49 exit 1 — 10 중 2 실패(CC-8: INCONSISTENT) | 10/0 |
| AcquisitionTransmissionRuleSpec(AQ-TD6) | 09:56:16 exit 1 — 15 중 4 실패(TODO stub) | 15/0 |
| ReacquisitionExecutionRuleSpec(EX-7 n-3) | 09:57:51 exit 1 — 8 중 1 실패(예외 없음) | 8/0 |
| ReacquisitionCommandParserSpec(새, I-23) | 09:59:22 exit 1 — 3 중 3 실패 | 3/0 |
| ReacquisitionBadgeRuleSpec(새) | 10:18:46 exit 1 — 4 중 4 실패(stub) | 4/0 |
| ReacquisitionResultDbSpec(새 — RR-1..4) | 구현 뒤 작성 → 변이로 RED(아래 표) | 8/0(10:54:41~10:56:57, V19 최종본) |
| AcquisitionFileViewReaderSpec(AC9 추가) | 변이 M10 RED | 17/0 |
| AcquisitionFileViewServiceSpec | — | 10/0 |
| ReacquisitionExecutionDbSpec(RX-7 변경·RX-14 counter) | 변이 M-n6 RED | 22/0 |
| 회귀: AcquisitionTransmissionDbSpec 29, AcquisitionSealDbSpec 17, FinancialDatabaseAccountSpec 23(V19에 SUPERSEDED CHECK를 더하기 전 실행), MainMigrationLocationSpec 4, ArchitectureSpec 24, AdminAcquisitionApiSpec 10 | — | 모두 0 fail |
| ADMIN FinancialOpsGatewaySpec | 변이(모양에서 새 키 제거) RED 6 실패 | 18/0 |
| ADMIN ApprovalFailureInjectionSpec(HT-3 ④ — n-A) | 구현 변경 없는 새 시험(RED 없음) | 9/0 |
| Vitest `AcquisitionsScreen.badge.test.tsx`(새) | 10:51:15 exit 1 — 3 중 3 실패 | 3/0 |
| Vitest `AcquisitionsScreen.reacquisition.test.tsx`(m-A 단언 추가) | 10:52:16 exit 1 — 8 중 1 실패 | 8/0 |
| Vitest 회귀 `AcquisitionsScreen.test.tsx` 6 · `noRuntimeStyles.test.tsx` 9, `tsc --noEmit` | — | 0 fail, exit 0 |

**4. 핵심 변이(각 RED 확인 후 원복 — 원복 전후 sha256 앞 12자 동일)**

| 변이 | 대상 | 결과 |
|---|---|---|
| M-CC4 `sale.generation != 0` 되돌림(rev 0.6 규칙) | ReacquisitionResultDbSpec | RED 2(RR-1 CANCEL 레코드 없음, RR-2 세대 2 실행 실패) |
| M-I17 ③ 옛 정의(세대 0 CANCEL 합) 되돌림 | ReacquisitionResultDbSpec | RED 1(RR-1 I-17) |
| M-L9 보류 호출 제거 | ReacquisitionResultDbSpec | RED 3(RR-3 2, RR-4 1 — 업로드됨) |
| M12 다른 세대 파일 잠금 제거 | ReacquisitionResultDbSpec | RED 1(RR-4 — 미커밋 표지를 기다리지 않고 업로드) |
| M10 배지 계산에서 새 세대 무시 | AcquisitionFileViewReaderSpec | RED 1(AC9) |
| M-n6 counter 증가 제거 | ReacquisitionExecutionDbSpec | RED 1(RX-14) |

시험 수 하한(단일 spec 증가분 합 — 모듈 실행으로 확인 필요): financial-batch 404 → **428**(+24: RR 8, ParserSpec 3, BadgeRuleSpec 4, AQ-TD6 4, CC-8 3, EX-7 1, AC9 2, ViewServiceSpec 1 — 새 Then 단위),
internal-admin Kotlin 745 → **747**(+2: Gateway 1, HT-3 ④ 1), Vitest 167 → **170**(+3). Simulator: 변경 없음(수 불변).

**5. 8대 원칙 증거(B2)**: Financial Consistency(세대를 넘는 확정 하나 — 보류로 이중 매입 요청 차단 RR-3, 순액·상계 각 한 번 RR-2, 누적 한도 CC-8) · Transaction Safety(보류 상태 + 근거 행 같은 점유 TX, 실패 시 rollback) ·
Idempotency(보류 PK(file, seq), 보류 파일 재점유 없음 RR-3) · Concurrency(다른 세대 파일 행 잠금 → 표지 재조회, RR-4 실제 연결 + M12) · Recoverability(보류는 종결 — 자동 재전송 없음, 대사 입력) ·
Reconciliation(보류 근거 표·gauge·확인 필요 사유, I-17 재정의로 거짓 불일치 제거) · Auditability(append-only 보류 근거, 원 레코드 상태 불변 — 배지는 표시만) · Observability(`files.transmission_withheld`,
`transmission{outcome=WITHHELD}`, `reacquisition.integrity_rejected` — label에 ID·금액 없음). Kafka/Redis 없음 — 이중 매입 방지는 소유 DB 행 잠금·PK·상태 전이가 지키며 메시징이 대신하지 않는다.

JPA 메모: 보류 근거 `JpaAcquisitionTransmissionWithholdEntity`는 `@Immutable` + 모든 열 `updatable=false`, Store는 persist + flush(merge 없음) — 같은 파일 두 번째 보류 근거는 PK 위반으로 TX 전체 rollback.
파일 상태 전이는 순수 Domain `AcquisitionFile.withholdTransmission` → Mapper가 `@Version`을 실어 UPDATE(동시 점유는 OptimisticConflict → IN_FLIGHT). 다른 세대 파일 잠금은 `PESSIMISTIC_WRITE`(= `SELECT … FOR UPDATE`):
READ COMMITTED에서 없는 행의 잠금 읽기는 gap 잠금이 없어 표지 INSERT를 막지 못하므로, 표지를 쓰는 응답 TX가 잡는 **파일 행**을 공통 잠금으로 썼다. 배지 계산은 jOOQ Reader(조회 전용, 비권위)이고 판정·전송 결정에는 쓰지 않는다.

**6. Demo 시나리오(Coordinator 실행 — Simulator Demo 성공이며 운영 준비 완료가 아님)**

- 새 env(값은 Coordinator, `.env.example`에 이름만): `PAYCORE_SIMULATOR_ACQUIRER_ACQUIRERS_0_SCENARIOS_0_{ACTION,FILEID}`(= `FILE_REJECT`, `ACQ.SIMACQA.TEST.<업무일>.001`), 거절 장면이 필요하면
  `…_SCENARIOS_1_{ACTION,FILEID,TRANSACTIONID,REASON}`(= `REJECT`, `…<업무일>.002`, 그 거래 ID, 예 `DEMO_REJECTED`). B1의 `PAYCORE_FINANCIAL_ADMIN_OPERATION_*`·ADMIN 조작 키도 필요(L-7). FINANCIAL에 V19·V20 migration 적용 필요.
- Simulator 매입사: 파일 시나리오는 파일 ID로 고르므로 001만 파일 거절, 002는 시나리오가 없으면 VAN 원장 대조로 판정(같은 VAN 승인 → ACCEPTED 기대 — L-2/C-8, Demo에서 처음 확인). 거래 단위
  `REJECT`(+`_FILEID=…002`)를 두면 002의 그 레코드만 REJECTED. Simulator 코드 변경 없음. 002가 예상과 달리 거절되면 Simulator 경로 추가를 Coordinator에 요청(C-8).
- 화면 순서: ① 매입 현황 — 001 "재매입 검토 대상" → ② 상세 재매입 요청(OPERATOR) → ③ 승인함 승인(APPROVER + TOTP) → ④ 목록에 002(봉인됨), 001 "재매입 진행 중" → ⑤ 전송 job 뒤 002 전송 완료 →
  ⑥ 결과 수신 뒤 ACCEPTED면 001 배지 없음(레코드 상태는 "거절" 그대로, 002 레코드 "매입 확정") / REJECTED 장면이면 002가 "재매입 검토 대상", 001은 "재매입 진행 중" →
  ⑦(선택) 재매입 뒤 취소 → 다음 정기 봉인에서 002 ACCEPTED면 CANCEL 레코드, REJECTED면 "취소 매입 불요". 전송 보류(L-9)는 자연 경로가 없어 Demo 장면 없음(시험 RR-3·RR-4로만).
- 캡처(시험 대역 데이터 — Demo 아님, `shot.mjs` `ONLY=badge SUFFIX=`): scratchpad `demo/acqadmin/acqadmin-05-badge-{candidate,in-progress,none,withheld}.png`.

**7. 남은 일·미결**

- **미실행(§14 B2에 있으나 이번에 하지 않음)**: CANCEL 레코드 재매입(I-7), AC5 R-m1 PK 경합 `AcquisitionRaceFixture`/`AcceptedRecordRaceDbSpec`, AC6 N-1 ArchUnit(`RecordResultWriter`) — 다음 배치 후보.
- 경로 시험 없음: 보류 뒤 늦은 앞 시도의 SUPERSEDED 관찰 행(V19 CHECK 확장만), 보류 파일에 온 응답(Domain AQ-TD6만). V19 최종본(SUPERSEDED CHECK 포함)은 ReacquisitionResultDbSpec 재실행으로 migration 적용만 확인.
- 질문 후보 **Q-ACQA-10**(Coordinator 경유): 전송 보류 파일의 해소 의미. 권장안: 해소 없음(종결 — 보류 파일은 대사 기록만). 대안: 대사가 다른 세대 확정을 무효로 판정하면 2인 승인으로 보류 해제 후 전송.
  영향: 이중 매입 요청 위험의 판단 주체. 막히는 작업: 없음(B2는 종결로 구현 — 해제 경로 없음).
- 모듈 전체 실행·Verifier·Reviewer·Demo는 Coordinator.

### 17.11 rev 0.8 — 종결 기록(B2에서 종결, 2026-11-07 KST, Implementer 실행, Verifier·Reviewer 재검증 대상)

사용자 지시: B2 독립 검토 PASS(MAJOR 0) 뒤 이 작업을 **B2에서 마치고** 가맹점 화면으로 넘어간다. 남은 일은 후속 작업 **ACQ-ADMIN-002**(목록은 §14 rev 0.8 표). 작업 상태(DONE 판정·tasks.json·questions.json)는 Coordinator·Verifier 소관이며 Implementer는 바꾸지 않았다. 이 기록은 Simulator Demo 성공이며 운영 준비 완료가 아니다.

**1. 변경(rev 0.8)**

| 파일 | 내용 |
|---|---|
| `apps/internal-admin/frontend/src/screens/ApprovalsScreen.tsx` | B2 리뷰 **m-1**: `act(id, action)` — 승인·거절·철회 성공 뒤 목록을 다시 읽고, **그 승인이 상세 패널에 열려 있으면 `api.approvalDetail(id)`로 상세도 다시 읽는다**(기존 "상세" 버튼과 같은 관용 — 지우지 않고 갱신). 그래서 `ReacquisitionApprovalDetail`·`OwnerApprovalDetail`이 낡은 PENDING으로 고정 값 대 현재 값 비교를 계속하지 않는다(비교는 `item.status === 'PENDING'`일 때만). 다른 승인을 처리하면 열린 상세는 그대로. 다시 읽기 실패는 기존 `fail`(세션 종료 코드면 세션 종료, 아니면 오류 알림). 서버 판정·권한 변경 없음 |
| `…/screens/ApprovalsScreen.acquiring.test.tsx` | 새 시험 2: 승인 뒤 재매입 상세가 EXECUTED(새 파일 표시, 비교 안내 없음, 대상 재조회 1회), 철회 뒤 WITHDRAWN |
| `…/screens/ApprovalsScreen.owner.test.tsx` | 새 시험 1: 거절 뒤 소유 서비스 상세가 REJECTED |
| 이 명세 | 제목·rev 0.8, §14 범위 확정, §15 L-7(n-2)·L-9(m-2·m-3·m-4), §17.11 |

**2. RED·GREEN(Vitest = WSL node 24.21.0 `npx vitest run <파일>`, `npx tsc --noEmit -p .`)**

| 대상 | RED | GREEN |
|---|---|---|
| `ApprovalsScreen.acquiring.test.tsx` | 수정 전 11:16:40 exit 1 — 11 중 2 실패(상세에 `EXECUTED`·`WITHDRAWN` 표지 없음 — 낡은 PENDING) | 수정 뒤 11/0 |
| `ApprovalsScreen.owner.test.tsx` | 다시 읽기 한 줄을 뺀 변이로 RED 1(REJECTED 표지 없음 — acquiring 2와 함께 3 실패, 원복) | 수정 뒤 9/0 |
| 회귀 `ApprovalsScreen.test.tsx` | — | 0 fail(위 세 파일 합 23/0, exit 0) |
| Vitest 전체(internal-admin frontend) | — | 26 파일 · **173**/0, exit 0 |
| `tsc --noEmit` | — | exit 0 |

Gradle·모듈 실행 없음(Demo JVM 가동 중 — 메모리). Vitest 하한 170 → **173**(+3). Kotlin 시험 수 변화 없음(financial-batch 428·internal-admin 747 하한 그대로 — 모듈 실행은 Coordinator).
참고: Vitest 실행이 `apps/internal-admin/build/test-results/frontend/TEST-vitest.xml`을 마지막 실행(전체 173)으로 덮어썼다.

**3. B2 독립 검토 지적 처리**

| 지적 | 처리 |
|---|---|
| m-1 승인함 상세 낡은 PENDING | 이번에 수정(위 1·2) |
| m-2 파일 단위 보류로 같은 파일 다른 레코드가 묶임 | 기록만 — §15 L-9, Q-ACQA-10 영향, ACQ-ADMIN-002 |
| m-3 보류 중 받은 취소 `ORIGINAL_AWAITING_RESULT` 무기한(`JpaAcquisitionMembershipStore.kt:61` 가장 높은 세대 선택) | 기록만 — §15 L-9, 코드 수정은 ACQ-ADMIN-002 |
| m-4 점유 커밋 뒤 원 세대 ACCEPTED → 이중 매입 요청 가능 | 기록만 — §15 L-9, CONFLICT 경보·대사에 맡김 |
| n-1 RR-4를 실제 `AcquirerResponseService` 경로로 | ACQ-ADMIN-002 |
| n-2 Demo·배포 순서(FINANCIAL V19·V20 먼저) | §15 L-7에 기록 |
| n-3 SUPERSEDED 관찰 행·보류 파일에 온 응답 DB 시험 | ACQ-ADMIN-002 |

m-2·m-3·m-4는 모두 지금 규칙으로 도달하지 않는 상태다(§15 L-1).

**4. Demo E2E(Coordinator 실행, 2026-11-07 — 실제 internal-admin + financial-batch + institution-simulator, Simulator Demo 성공이며 운영 준비 완료 아님)**

- 승인 `apr_01M6SW383D8WMFTS0CH0TE11J3`: OPERATOR 요청 → APPROVER TOTP 승인. 대상 = `ACQ.SIMACQA.TEST.20261106.001`의 레코드 6(13,013원, REJECTED).
- 실행 결과 `ACQ.SIMACQA.TEST.20261106.002` 봉인(버전 2, 세대 1, `reacquisition_of …001#6`) → 전송 → 결과 파일(RESULT_FILE) 수신, 결과 ACCEPTED. C-8·L-2: Simulator 코드 변경 없이 002 수락 확인.
- 원 파일 배지: "재매입 검토 대상" → 없음. "재매입 진행 중"은 결과가 30초 안에 돌아와 Demo에서 캡처하지 못했다 — 시험 대역 캡처(`acqadmin-05-badge-in-progress.png`, §17.10-6)가 그 상태를 보인다.
- Demo에 필요했던 설정(값·키는 기록하지 않음): 양쪽(ADMIN·FINANCIAL) `extra.env`의 조작 키, `financial-json.py` ADMIN 채널 `operation-keys`·`operation-permits`·`max-execution-window`, Simulator 매입사 시나리오 0을 FILEID로 v001에만 한정. 기동 순서는 FINANCIAL(V19·V20) → ADMIN(§15 L-7 n-2).
- 캡처: Coordinator scratchpad `acqadmin`의 `demo-11` ~ `demo-15`.

**5. 8대 원칙(rev 0.8 변경분)**: m-1은 화면 표시 정합(Observability·Auditability 보조 — 결정 뒤 상세가 서버의 현재 상태를 보임)이며 금액·상태·권한 판정은 바꾸지 않는다(서버가 계속 판정). m-2·m-3·m-4는 Financial Consistency·Reconciliation의 남은 위험으로 기록했다 — 지금 방어는 소유 DB 행 잠금·PK·상태 전이와 CONFLICT 경보·대사이고, Kafka/Redis 도입이 이를 대신하지 않는다. JPA·jOOQ·migration 변경 없음.

**6. 미결**: Q-ACQA-05~08·Q-ACQA-10은 ACQ-ADMIN-002에서 다룬다(사용자 질문은 Coordinator 경유). 이 rev는 새 질문 후보가 없다.
