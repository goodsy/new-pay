# 사용자 확정 기록

2026-10-03 KST. 사용자가 첨부한 PayCore Settlement Control Policy v0.1 안을 “으로 해줘”로 채택했다. 다음 첨부 본문을 현행 정산·선지급·대사 구현 기준으로 기록한다. 본문의 권장/후보 모델명은 승인된 설계 방향을 표현하며 실제 클래스/enum/DDL·운영 수치·실제 기관 규약의 검증 완료를 뜻하지 않는다. 이전 대안 문서는 검토 이력이며 본 확정본과 충돌하는 제안은 본 확정본을 우선한다. 첨부 원문은 수정하지 않았다. 파일 말미의 기존 결정 연결·실행 경계도 함께 적용한다.

---
아래 내용으로 **PayCore 정산·선지급·대사 v0.1 권장안**을 정리해 확정하는 게 좋습니다.

# PayCore Settlement Control Policy v0.1

Status: CONFIRMED / 사용자 채택 / 2026-10-03 KST

## 1. Fee 적용 기준

수수료 정책 적용 기준일은 **원 승인일자**로 한다.

```text
Authorization 승인일
        ↓
FeePolicy Resolve
        ↓
FeeCalculation
```

Cancellation 시 현재 수수료 정책을 다시 적용하지 않는다.

```text
Cancellation
        ↓
Original Authorization
        ↓
Original FeeCalculation
        ↓
Fee Reversal
```

### 확정 권장

```text
Fee Policy Applied Date
= Original Authorization Date
```

취소 시에도 다음은 원 승인일 기준 Policy Snapshot을 사용한다.

- FeeType
- FeeCalculationMethod
- Fee Rate / Fixed Amount
- Fee Rounding Method
- VAT Calculation Method
- VAT Rate
- VAT Rounding Method

---

## 2. Fee / VAT 기준정보

다음 값은 코드에 고정하지 않고 **Versioned Reference / Policy**로 관리한다.

```text
FeeType

FeeCalculationMethod

FeeRoundingMethod

FeeVatCalculationMethod

VatRate

VatRoundingMethod

EffectivePeriod
```

예:

```text
FeeSchedule v1
2026-01-01 ~ 2026-10-31

FeeSchedule v2
2026-11-01 ~
```

활성 정책을 직접 수정하지 않고 새 Version을 생성한다.

---

# 3. Settlement 거래일

Settlement의 거래일은 금융사실이 실제 발생한 날짜를 사용한다.

```text
Authorization
→ 승인일

Cancellation
→ 취소일

Refund
→ Refund 완료일
```

승인과 취소는 각각 독립 SettlementEntry를 생성한다.

예:

```text
승인: 10/01
취소: 10/05
```

이면 각각 자기 거래일을 가진다.

---

# 4. Settlement Base Date

정산 기준일은 **영업일 기준**으로 계산한다.

```text
거래일이 영업일
→ 거래일

거래일이 비영업일
→ 다음 영업일
```

공식:

```text
settlementBaseDate =
    transactionDate.isBusinessDay
        ? transactionDate
        : nextBusinessDay(transactionDate)
```

예:

```text
토요일 거래
→ 월요일

월요일 공휴일
→ 화요일
```

---

# 5. Settlement Due Date

정산 예정일은:

```text
Transaction Date
        ↓
Settlement Base Date
        ↓
Settlement Cycle
        ↓
Settlement Due Date
```

순서로 계산한다.

따라서 다음 세 값은 반드시 구분한다.

```text
transactionDate

settlementBaseDate

settlementDueDate
```

---

# 6. Holiday / Business Calendar

영업일 계산을 위한 별도 기준정보를 둔다.

권장:

```text
BusinessCalendar

calendarCode

BusinessCalendarDate *
```

예:

```text
BusinessCalendarDate

date
calendarCode
businessDayYn
holidayName?
source?
```

초기 Calendar:

```text
PAYCORE_KR
```

향후 필요 시:

```text
KR_BANKING

KR_CARD

Provider-specific Calendar
```

등으로 확장한다.

단순 주말 계산으로 끝내지 않는다.

---

# 7. Calendar 변경

임시공휴일처럼 Calendar가 사후 변경될 수 있다.

이미 생성된 SettlementEntry의:

```text
settlementBaseDate
settlementDueDate
```

를 현재 Calendar로 다시 계산하지 않는다.

계산 당시 결과를 Snapshot한다.

필요하면:

```text
calendarCode
calendarVersion
```

도 남긴다.

---

# 8. SettlementPolicy

권장 구조:

```text
SettlementPolicy

settlementPolicyId
version

cutoffPolicy
baseDateRule
settlementCycle
dayBasis
holidayAdjustmentPolicy

effectivePeriod
```

Contract 내부에 직접 넣지 않는다.

```text
ContractServiceAgreement
        ↓
SettlementPolicyAssignment
        ↓
SettlementPolicy
```

형태를 권장한다.

---

# 9. Cutoff와 Base Date 분리

다음을 하나의 개념으로 만들지 않는다.

```text
Cutoff
= 어느 거래일에 귀속되는가

Business Calendar
= 해당 날짜가 영업일인가

Settlement Cycle
= 기준일에서 언제 지급하는가
```

즉:

```text
Transaction Attribution
→ Business Day Adjustment
→ Settlement Cycle
```

이다.

---

# 10. 선지급 정책

PayCore v0.1에서는 별도 Financing Domain을 만들지 않는다.

**Payable Early Release 방식**을 사용한다.

```text
Settlement
    ↓
Payable
    ↓
AdvancePayoutEligibility
    ↓
Payout
```

Payable 자체의 금액을 변경하지 않는다.

정상 Due Date보다 먼저 Payout 가능한지만 판단한다.

---

# 11. AdvancePayoutPolicy

후보:

```text
AdvancePayoutPolicy

policyId
version

enabled

perTransactionLimit?
dailyAmountLimit?
dailyCountLimit?

effectivePeriod
```

정책 Scope는 우선:

```text
ContractServiceAgreement

또는 명시적 MerchantAccount Override
```

를 권장한다.

GID 기반 자동 상속은 사용하지 않는다.

---

# 12. 선지급 가능 조건

최소:

```text
AdvancePayoutPolicy enabled

Contract valid

Payable OPEN

Settlement confirmed

No unresolved critical reconciliation case

No active payout

No payout UNKNOWN

No settlement/payout hold

Advance limit available
```

초기 구현에서는 복잡한 비율형 선지급보다 다음 정도부터 시작하는 것이 좋다.

```text
선지급 허용 여부

건당 한도

일 한도

미해결 Critical Risk 여부
```

---

# 13. 선지급 Hold

Hold는 범위를 구분한다.

```text
ADVANCE_ONLY

ALL_PAYOUT
```

예:

```text
정산 소액 차이
→ ADVANCE_ONLY Hold

중복 지급 의심
→ ALL_PAYOUT Hold
```

---

# 14. HoldType

후보:

```text
RISK

RECONCILIATION

CONTRACT

ACCOUNT

ADVANCE_LIMIT

MANUAL
```

초기에는:

```text
SettlementHold

PayoutHold
```

를 별도로 구현하고, 공통 패턴이 확인되면 향후 `FinancialHold`로 통합한다.

---

# 15. Reconciliation 자동 보정

대사 차이는 자동으로 데이터를 수정하지 않는다.

기본 흐름:

```text
Mismatch
    ↓
ReconciliationCase
    ↓
Risk Assessment
    ↓
AUTO_RECOVERY
AUTO_ADJUSTMENT
MANUAL_REVIEW
BLOCK
```

---

# 16. 자동 Recovery와 자동 Adjustment 구분

### Auto Recovery

기존 금융사실은 존재하지만 상태 반영이 누락된 경우.

예:

```text
Payout UNKNOWN

Bank COMPLETED

externalTransactionId exact match
amount exact match
```

→ 기존 Payout을 Recovery.

또는:

```text
PaymentTransaction 존재
FeeCalculation 존재
SettlementEntry 누락
```

→ Idempotent Backfill 가능.

---

### Auto Adjustment

이미 확정된 금융금액에 차액 보정이 필요한 경우.

예:

```text
Settlement Fee Difference
100원
```

원인이 명확하고 Policy가 허용할 때:

```text
SettlementAdjustment
```

생성.

기존 금융원장을 UPDATE하지 않는다.

---

# 17. 자동 보정 Risk Policy

추천:

```text
AutoCorrectionPolicy

scope

discrepancyType

maxAmount?

allowAutoRecovery

allowAutoAdjustment

riskLevel

requiresApproval
```

자동 여부를 단순 금액만으로 판단하지 않는다.

판단 요소:

```text
DiscrepancyType

Source Reliability

Amount

Duplicate Risk

Actual Money Movement Impact

UNKNOWN Presence
```

---

# 18. Reconciliation RiskLevel

권장:

```text
LOW

MEDIUM

HIGH

CRITICAL
```

예:

### LOW

```text
Known rounding mismatch

Deterministic fee mismatch

Recoverable downstream missing record
```

### MEDIUM

```text
Settlement missing

Provider late record
```

### HIGH

```text
Payout status mismatch

Unexpected refund
```

### CRITICAL

```text
Duplicate payout

MISSING_INTERNAL bank transfer

Bank amount mismatch

Unexplained external success
```

---

# 19. Risk별 처리

```text
LOW
↓
Auto Recovery / Limited Auto Adjustment
```

```text
MEDIUM
↓
Manual Review
```

```text
HIGH / CRITICAL
↓
Hold
↓
Manual Review
```

---

# 20. 리스크 차단

High/Critical Case가 발생하면 관련 Scope를 차단할 수 있어야 한다.

후보 Scope:

```text
MERCHANT_ACCOUNT

SETTLEMENT_ENTRY

SETTLEMENT_STATEMENT

PAYABLE

PAYOUT
```

처음부터 전 Merchant를 무조건 막지 않고 위험 영향범위에 맞게 Hold를 설정한다.

---

# 21. 자동보정 후 재대사 필수

매우 중요한 원칙:

```text
Auto Correction 완료
!= Reconciliation 해결
```

반드시:

```text
Correction
    ↓
Re-Reconciliation
    ↓
MATCH 확인
    ↓
Case RESOLVED
```

해야 한다.

MATCH가 아니면 Case를 닫지 않는다.

---

# 22. Auto Correction 멱등성

자동보정은 중복 실행되면 금액 사고가 발생한다.

최소 Business Key:

```text
reconciliationCaseId
+
correctionType
```

예:

```text
UNIQUE(
    reconciliation_case_id,
    correction_type
)
```

동일 Case에 Adjustment가 두 번 발생하지 않도록 한다.

---

# 23. Auto Correction Kill Switch

자동보정 기능은 즉시 중단할 수 있어야 한다.

예:

```text
AutoCorrectionPolicy.enabled
```

또는 운영 Feature Control.

권장 제한:

```text
perCaseAmountLimit

dailyCorrectionCountLimit

dailyCorrectionAmountLimit

merchantDailyLimit
```

---

# 24. Approval이 필요한 작업

다음은 기본적으로 2인 승인 구조를 수용하는 것이 좋다.

```text
Re-Payout

High-value Adjustment

Critical Reconciliation Resolve

Force Hold Release

Advance Payout Exception
```

Phase 1에서 실제 UI까지 만들지 않더라도 Domain은:

```text
requestedBy

approvedBy
```

를 수용할 수 있어야 한다.

---

# 25. Audit

최소 다음은 Audit 대상이다.

```text
FeePolicy 변경

SettlementPolicy 변경

Holiday Calendar 변경

Adjustment 승인

Hold 생성

Hold 해제

Re-Payout 승인

Reconciliation Manual Resolution

Advance Payout Exception
```

누가/언제/왜 변경했는지 남긴다.

---

# 26. Dry Run

다음 Policy 변경은 운영 적용 전 Dry Run을 권장한다.

```text
FeePolicy

SettlementPolicy

BusinessCalendar

AutoCorrectionPolicy
```

과거 특정 BusinessDate 거래를 대상으로:

```text
Old Result
vs
New Policy Result
```

을 비교할 수 있도록 한다.

---

# 27. Replay / Backfill

정산/대사는 과거 특정 일자의 재처리가 가능해야 한다.

하지만 다음 원칙을 따른다.

```text
미생성 데이터
→ Idempotent Backfill

확정 전 결과
→ Rebuild 가능

확정 후 금융 오류
→ Adjustment

대사 결과
→ 새 Observation / Case
```

이미 확정된 금융원장을 단순 UPDATE하지 않는다.

---

# 28. Policy Resolver

Fee/Settlement Policy는 암묵적 우선순위를 최소화한다.

가능하면:

```text
exactly one applicable policy
```

를 원칙으로 한다.

둘 이상 매칭되면:

```text
AmbiguousPolicy
```

로 실패시킨다.

다음처럼 숨겨진 우선순위를 만들지 않는다.

```text
MID
→ GID
→ Merchant
→ Default
```

---

# 29. 최종 확정 권장안

## Fee

```text
Policy Applied Date
= Original Authorization Date

Cancellation Fee Reversal
= Original FeeCalculation 기준

VAT Calculation/Rounding
= Original Approval Date Policy Snapshot
```

---

## Settlement

```text
Transaction Date
= 승인일 / 취소일 / 환불완료일

Settlement Base Date
= 거래일이 영업일이면 거래일
= 비영업일이면 다음 영업일

Settlement Due Date
= Base Date + SettlementCycle

Holiday
= Versioned BusinessCalendar
```

---

## Advance Payout

```text
Payable Early Release

+

AdvancePayoutEligibilityPolicy

+

Advance-only Hold / All-Payout Hold
```

---

## Reconciliation

```text
Mismatch
→ ReconciliationCase
→ Risk Assessment
```

```text
LOW
→ Auto Recovery / Limited Auto Adjustment

MEDIUM
→ Manual Review

HIGH / CRITICAL
→ Hold + Manual Review
```

그리고:

```text
Correction
→ Re-Reconciliation
→ MATCH
→ RESOLVED
```

를 강제한다.

---

# 30. PayCore v0.1 구현 우선순위

지금 당장 구현할 필요가 있는 순서:

```text
1. BusinessCalendar

2. FeePolicy 적용기준 / Snapshot

3. SettlementBaseDateCalculator

4. SettlementPolicy / Cycle

5. ReconciliationCase + RiskLevel

6. AutoCorrectionPolicy

7. SettlementHold / PayoutHold

8. AdvancePayoutEligibilityPolicy
```

선지급 실제 지급 기능은 일반 Payout이 안정된 이후 추가해도 된다.

대사 자동보정은 먼저:

```text
AutoRecovery
```

부터 구현하고:

```text
AutoAdjustment
```

는 그 이후 추가하는 것을 권장한다.

이렇게 정리하면 지금까지 말씀하신 운영 경험을 PayCore에 반영하면서도, **수수료·정산은 기준정보 기반**, **선지급은 별도 Eligibility 통제**, **대사는 자동복구와 자동금액보정을 구분**, **위험 건은 Hold**라는 구조가 깔끔하게 유지됩니다.

---

# 기존 확정사항 연결 및 실행 경계

다음은 첨부의 채택 범위를 기존 결정과 연결하는 해석/구현 주의사항이다. 새로운 운영 금액·기간·계약 조건을 임의 확정하지 않는다.

## A. 선지급 의미의 두 축

기존 결정인 입금 후 지급/기관 입금 전 선지급 모드와 선지급 기준 세 종류(승인 완료·외부기관 매입 전송 완료·매입 확정)는 유지한다. Payable Early Release는 확정 채무를 예정일보다 먼저 지급할 수 있는 Eligibility 경계이며, 기관 입금 여부라는 재원 조건과 예정일 전 지급이라는 일정 조건은 각각 판정한다. 예정일이 도래했다고 기관 미입금 조건을 무시하지 않고, 선지급 enabled만으로 채무 미확정 상태를 지급하지 않는다.

선택한 거래 단계에 맞춰 Settlement의 적격성을 판정해 Statement를 확정하고 Payable을 생성한다. 승인 완료 기준 정책에 매입 확정을 숨은 필수 조건으로 강제하여 기존 결정을 무효화하지 않는다. 원 승인/매입/취소 사실, 수수료, 위험 보류 등 해당 정책의 근거는 확보해야 한다.

별도 Financing Domain을 만들지 않는다는 결정은 기관 미수/입금 배분·선지급 노출·사용 가능 재원 기록을 생략하는 뜻이 아니다. 초기에는 FINANCIAL 내부 금융 통제로 구현할 수 있다. 입금 후에는 기존 선지급을 다시 실행하지 않는다. 노출/재원 한도의 상세 구성과 금액은 별도 확정 대상이다.

## B. 기준정보와 날짜

Fee/VAT 계산·세율·반올림은 버전 있는 기준정보/정책에서 선택한다. 기존 반올림 방식 확인 질문은 이 결정으로 해소되며 HALF_UP 등 실제 적용값을 고정한 것은 아니다. 취소는 원승인 FeeCalculation과 Snapshot을 참조한다. 정률/정액 반환 비율·부분취소 마지막 잔액·별도 취소비 등의 구체 정책값은 별도 확정한다.

Refund 완료일은 확정 외부 반환 사실의 업무시각에 기반하고 PAYMENT에 늦게 기록된 수신시각과 구분한다. 적용 시간대/기관 거래일 규약은 별도 상세화한다.

Calendar 변경이 기존 Entry에 보존한 기준일/예정일을 자동으로 바꾸지는 않는다. §27 확정 전 Rebuild는 명시된 재산출 작업으로 새 계산 근거/버전을 기록하며 과거 Snapshot을 조용히 덮어쓰지 않는다. 확정 후에는 통제된 Adjustment/일정 변경 절차를 사용한다. SettlementCycle의 값·cutoff·D 포함 여부는 별도 정책값이다.

## C. 정책 해석

명시적 MID Override와 Agreement Assignment는 resolver가 사전에 정한 계약으로 하나의 유효 정책에 수렴해야 한다. 두 후보가 동시에 적용되는 모호함은 실패한다. 숨은 GID/Default 상속을 만들지 않는다. Override의 효력 범위/기간·대체 관계를 상세 명세에서 정의한다.

## D. 자동 보정과 위험

§16의 exact-match Recovery는 가능한 복구 사례다. §18~19에서 HIGH/CRITICAL로 판정되는 사건을 그 예시만으로 LOW로 낮추지 않는다. 기본 분류가 HIGH인 Payout 상태 차이는 Hold+Manual Review를 따른다. 자동으로 처리할 구체 exact-match 유형은 승인된 Risk/AutoCorrectionPolicy가 허용하고 원 요청/통화/MID/기관계정/수취인/증거 신뢰성·중복 위험을 검증한 경우에만 실행한다.

보정은 소유 도메인의 Recovery/Adjustment API로 수행한다. Reconciliation이 PAYMENT DB를 직접 조회/수정하지 않는다. 외부 요청 재전송·재송금은 재생/자동 보정과 별도의 통제 명령이다. mismatch만으로 금액을 맞추는 SQL UPDATE를 실행하지 않는다. 기존 원장은 보존한다.

Case+correctionType 유일키 외에도 Case 안정키와 원 금융 사실/조정의 업무 유일키를 유지한다. 새 Run/새 Case가 같은 업무 보정을 다시 실행하는 우회를 막는다. 보정 완료/요청 수락만으로 Case를 해결하지 않고 재대사 MATCH를 확인한다.

2인 승인은 필드를 두는 것에 그치지 않고 요청자≠승인자, 역할/Scope, 승인한 입력 버전과 실행 입력 일치를 검증한다. Hold가 여러 개면 하나 해제로 다른 유효 Hold가 사라지지 않는다. Kill switch는 실행 직전 재검증하며 이미 발생한 외부 효과는 취소하지 않고 복구한다.

## E. 현재 미확정 및 이 문서가 확정하지 않은 사항

- 운영 요율·세율·반올림 실제 선택값, 정산 주기/마감, 한도·노출 해제·회수 조건, Calendar 적용 timezone.
- MID–Site 구조는 후속 [도메인 구조·Seed·관계 확정본](PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md)에서 Assignment N:M 수용·기본 1:1·조건부 복수 Site 공유로 확정됐다. Default 교체/종료·추가 발급 승인 등의 잔여 세부는 별도 구체화한다.
- KRW/체크카드/일반 할부·고객 인증의 최초 구현 범위 추가안은 별도 채택 대상이다.
- 실제 저장소·DDL·API/이벤트 schema·상태 전이표·실행 코드·테스트·기관 인증은 아직 구현/검증 완료가 아니다.

§30은 정산·선지급·대사 영역 내부의 구현 우선순위다. 전체 PG의 최초 MID·주문/승인·기본 Payout·DB/이벤트 검증 선행조건을 대체하지 않는다.
