# PayCore v0.1 도메인 구조·정책 Seed·MID–Site — 확정

상태: 사용자 첨부 요청 채택 / 2026-10-03 KST

첨부에 제시된 v0.1 최종 결정표와 구조를 구현 기준으로 기록한다. 구조/계산 기준은 확정하고 실제 운영 숫자는 별도 기준정보로 관리한다. 원문에서 예시로 제시한 요율·세율·주기·한도는 DEMO DATA이며 운영 계약값 승인이나 실행 코드 생성 완료를 의미하지 않는다. 본문 후보 이름은 논리 모델이며 물리 스키마는 구현 명세에서 검증한다. 첨부 원문은 수정하지 않았다. 파일 말미의 기존 결정 연결 및 실행 경계를 함께 적용한다.

---
이 부분은 **“도메인 구조는 지금 확정하고, 실제 운영 숫자값은 기준정보 Seed 값으로 분리”**하는 것이 좋습니다. 특히 요율·정산주기·한도는 실제 PG 계약마다 달라지므로 PayCore의 불변 규칙처럼 하드코딩하면 안 됩니다.

제가 PayCore v0.1에서는 아래 수준까지 확정하는 것을 권장합니다.

### 1. 실제 요율

구조와 계산 규칙만 확정하고 **실제 숫자는 Demo/Seed 값**으로 둡니다.

```text
FeeSchedule
 └─ FeeRule
      ├─ paymentMethod
      ├─ feeType
      ├─ calculationMethod
      ├─ rate / fixedAmount
      ├─ feeRoundingMethod
      ├─ vatCalculationMethod
      ├─ vatRate
      ├─ vatRoundingMethod
      └─ effectivePeriod
```

예를 들어 개발 Seed에서는 임시로:

```text
CARD
RATE
2.8%

VAT_EXCLUDED
VAT 10%
```

처럼 넣어도 되지만 이것은 반드시:

```text
DEMO DATA
```

로 표시합니다.

즉 **2.8% 자체는 PayCore 정책이 아닙니다.**

확정할 것은:

```text
수수료 정책 적용일 = 원 승인일

취소 시
= 원 승인 거래 FeeCalculation 기준 reversal

현재 FeeSchedule 재조회 금지
```

입니다.

---

## 2. 실제 정산주기

여기도 숫자를 고정하지 않고 기준정보로 관리합니다.

예:

```text
SettlementPolicy A
Daily / D+2 Business Days

SettlementPolicy B
Daily / D+5 Business Days

SettlementPolicy C
Monthly / 10th

SettlementPolicy D
SemiMonthly / 15th + Month End
```

PayCore 첫 구현에서는 엔진의 복잡도를 줄이기 위해 우선:

```text
DAILY_OFFSET
```

부터 구현하는 것을 권장합니다.

즉 실제 Seed는:

```text
CARD
D+2 Business Days
```

정도를 사용하고, 도메인은:

```text
DailyOffsetCycle(offset = 2)
```

처럼 표현합니다.

후속으로:

```text
WEEKLY
MONTHLY
SEMI_MONTHLY
```

를 추가합니다.

중요한 확정 정책은:

```text
거래일
→ 비영업일이면 Next Business Day
→ Settlement Base Date
→ Cycle 적용
→ Settlement Due Date
```

입니다.

---

# 3. 운영 한도

한도는 **정책 타입별로 분리**해야 합니다.

하나의:

```text
limitAmount
```

로 만들지 않는 것이 좋습니다.

예를 들어 선지급에는:

```text
AdvancePayoutLimit

perTransactionAmount
dailyAmount
dailyCount
monthlyAmount?
```

Payout에는:

```text
PayoutControlLimit

perTransactionAmount?
dailyAmount?
```

Auto Correction에는:

```text
AutoCorrectionLimit

perCaseAmount
dailyAmount
dailyCount
merchantDailyAmount
```

처럼 각각 의미가 다릅니다.

### v0.1 추천

첫 구현에서는 다음만 지원하면 충분합니다.

```text
Advance Payout
- enabled
- perTransactionLimit
- dailyAmountLimit

Auto Correction
- perCaseAmountLimit
- dailyAmountLimit
- dailyCountLimit
```

숫자는 Demo Seed로 둡니다.

예:

```text
Advance:
건당 10,000,000
일 50,000,000

Auto Adjustment:
건당 1,000
일 100,000
```

이 숫자 역시 **업무 규칙이 아니라 테스트/시연용 값**입니다.

---

# 4. 실제 운영값 변경 방식

매우 중요합니다.

운영자가:

```text
2.8% → 2.7%
D+2 → D+3
1천만원 → 2천만원
```

으로 바꿀 때 기존 행을 UPDATE하는 방식은 피합니다.

```text
Policy v1
effectiveTo = 2026-10-31

Policy v2
effectiveFrom = 2026-11-01
```

로 Version을 증가시킵니다.

그리고 금융 거래에는:

```text
policyId
policyVersion
```

을 저장합니다.

---

# 5. MID–Site 관계는 지금 좀 더 명확히 정하는 것이 좋습니다

제가 앞서:

```text
MerchantSite 1 : N MerchantAccount
MerchantAccount : MerchantSite = N : 1
```

을 추천했는데, 실제 PG 확장성을 생각하면 **조금 수정하는 것이 좋습니다.**

핵심 질문은:

> 하나의 MID를 여러 Site가 공유할 수 있는가?

실제 플랫폼/브랜드 구조를 생각하면 필요할 가능성이 있습니다.

예를 들어:

```text
Business A

MerchantSite
 ├─ Web
 └─ Mobile App
```

Web과 App이 동일 서비스이고 동일 계약/승인/정산 구조라면 하나의 MID를 공유할 수 있습니다.

따라서 물리 모델을 처음부터:

```text
MID.merchantSiteId
```

하나로 박아버리면 나중에 제약이 됩니다.

---

# 6. 권장안: MID–Site를 Mapping으로 분리

저는 최종적으로 이 구조를 추천합니다.

```text
Merchant
 ├─ MerchantSite *
 └─ MerchantAccount *

MerchantSite
       ↕
MerchantAccountSiteMapping
       ↕
MerchantAccount
```

즉 논리적으로 N:M까지 수용합니다.

---

# 7. 그렇다고 Phase 1부터 아무 MID나 여러 Site에 연결하면 안 됩니다

Domain Rule을 둡니다.

### 기본 규칙

```text
MerchantAccount에는 최소 1개 MerchantSite가 연결되어야 한다.
```

Production MID 기준입니다.

### 기본 형태

대부분:

```text
1 MID
↔
1 Site
```

로 시작.

### 공유 허용 조건

다음 조건을 만족할 때만:

```text
1 MID
↔
N Site
```

허용합니다.

추천 조건:

```text
동일 Merchant

동일 ContractServiceAgreement

동일 PaymentMethodActivation 적용 가능

동일 정산 정책 적용

외부기관 심사상 동일 MID 사용 허용
```

---

# 8. 서로 다른 Merchant가 MID를 공유하는 것은 금지

이건 Phase 1에서 강하게 막는 게 좋습니다.

```text
Merchant A Site
        ↘
          MID
        ↗
Merchant B Site
```

금지.

MID는 특정 Merchant의 계약 계정이므로:

```text
MerchantAccount.merchantId
```

는 하나입니다.

GID가 여러 Business/MID를 묶을 수 있는 것과 전혀 다른 문제입니다.

---

# 9. Site Mapping에 역할도 두는 것을 추천

단순 N:M 테이블보다 의미 있는 Mapping으로 만듭니다.

예:

```text
MerchantAccountSiteAssignment

assignmentId

merchantAccountId
merchantSiteId

role

effectivePeriod

status
```

Role 후보:

```text
PRIMARY
ADDITIONAL
```

첫 연결:

```text
PRIMARY
```

추가 Web/App 채널:

```text
ADDITIONAL
```

---

# 10. 왜 EffectivePeriod가 필요한가

Site가 변경될 수 있기 때문입니다.

예:

```text
MID M001

2026-01 ~ 2026-05
old-shop.com

2026-06 ~
new-shop.com
```

기존 Mapping을 지우면 과거 거래 당시 어떤 Site였는지 추적이 어려워집니다.

따라서:

```text
effectiveFrom
effectiveTo
```

를 두고 History를 보존하는 것이 좋습니다.

---

# 11. MID와 Site의 Activation을 섞지 않습니다

중요합니다.

```text
MerchantAccount ACTIVE
```

라고 모든 Site에서 사용할 수 있는 것은 아닙니다.

최종 Runtime 판단은:

```text
MerchantAccount ACTIVE

AND
SiteAssignment ACTIVE

AND
PaymentMethodActivation ACTIVE

AND
Contract ACTIVE

AND
Credential valid

AND
Routing available
```

입니다.

---

# 12. 카드사 Site 심사가 별도로 필요한 경우

향후:

```text
MerchantAccountSiteAssignment
```

과:

```text
PaymentMethodActivation
```

사이에 Site별 심사 결과가 필요할 수 있습니다.

예:

```text
MID M001
 ├─ Site A
 │   CARD ACTIVE
 │
 └─ Site B
     CARD UNDER_REVIEW
```

따라서 PaymentMethodActivation을 무조건:

```text
MID + PaymentMethod
```

만으로 고정하지 말고 향후:

```text
MID
+
PaymentMethod
+
optional Site Scope
```

확장을 고려하면 좋습니다.

Phase 1에서는 MID 단위로 시작해도 됩니다.

---

# 13. 제가 권장하는 v0.1 최종 결정

| 항목 | 결정 |
|---|---|
| 실제 수수료율 | 기준정보 값, 코드 하드코딩 금지 |
| 개발용 요율 | Demo Seed 허용 |
| Fee 적용 기준 | 원 승인일 |
| 취소 Fee/VAT | 원 승인 FeeCalculation/Policy Snapshot |
| 정산주기 | Versioned SettlementPolicy |
| 첫 구현 Cycle | `DAILY_OFFSET(D+N Business Day)` |
| 거래일 | 승인일/취소일/Refund 완료일 |
| Base Date | 비영업일이면 다음 영업일 |
| Holiday | BusinessCalendar 기준정보 |
| 선지급 한도 | 정책 기준정보 |
| Auto Correction 한도 | 별도 정책 기준정보 |
| 운영 값 변경 | UPDATE 대신 새 Version |
| Merchant–MID | Merchant 1:N MID |
| Merchant–Site | Merchant 1:N Site |
| MID–Site | Mapping Entity 사용 |
| 기본 Mapping | MID 1 : Site 1 |
| 확장 | 조건 충족 시 MID 1 : N Site |
| 다른 Merchant 간 MID 공유 | 금지 |
| Site 변경 | Mapping EffectivePeriod 이력 보존 |

### 구조는 최종적으로 이렇게 잡는 것을 추천합니다.

```text
Business
   ↓
Merchant
   ├───────────────┐
   ↓               ↓
MerchantSite *   MerchantAccount *
   │               │
   └───────┬───────┘
           ↓
MerchantAccountSiteAssignment
           │
           ↓
PaymentMethodActivation
           │
           ↓
Routing / Authorization
```

그리고 정책은:

```text
ContractServiceAgreement
        ├───────────────┐
        ↓               ↓
FeeScheduleAssignment   SettlementPolicyAssignment
        ↓               ↓
FeeSchedule         SettlementPolicy
                        ↓
                 BusinessCalendar
```

로 두면 **실제 계약값은 운영 데이터로 바꿀 수 있으면서, 도메인 구조는 안정적으로 유지**할 수 있습니다.

---

# 기존 결정 연결 및 구현 경계

## 1. 발급과 개통

Production MID는 최초 발급 시 같은 Merchant 소속이고 해당 Agreement Scope가 허용하는 Site를 적어도 하나 연결한다. 첫 연결은 PRIMARY다. 최초 Default 발급의 MID·Default 지정·최초 SiteAssignment·성공 요청 결과·Outbox는 같은 MASTER 로컬 TX에서 저장한다. Site 연결을 포함한 중간 실패에도 잔존 MID/부분 지정이 남지 않아야 한다.

이는 계약 승인 후 효력 시작 전 선발급을 금지하는 뜻이 아니다. SiteAssignment를 만들 수 있어도 해당 Site 심사/결제수단 개통/계약 효력/권리/인증/경로가 준비되지 않으면 결제를 차단한다. Source의 Contract ACTIVE는 유효 계약 조건 표현이며 승인/효력 enum을 하나로 합치지 않는다.

종료/중지/연결 종료 후 과거 MID와 Assignment는 보존한다. 유효 Site 연결이 없으면 신규 결제를 허용하지 않고 연결 복구/승인 절차를 요구한다. 추가 Site의 기본 상태만으로 개통을 자동 상속하지 않는다. 기관 승인 근거가 해당 Site를 포함하는지 확인해야 한다.

## 2. 관계 모델

MID는 하나의 Merchant와 발급 근거 계약 서비스에 귀속된다. 기술 모델은 SiteAssignment를 통한 N:M을 수용하고 기본 발급은 MID 하나/Site 하나다. 복수 Site 공유는 본문의 다섯 조건(동일 Merchant·Agreement·개통 적용 가능·정산 정책·기관 심사 허용)을 모두 충족해야 한다. Site 전용 Scope의 계약으로 다른 Site를 추가하지 않는다.

결제 요청의 MID/Site와 적용 기간을 서버에서 검증하고 거래에 당시 관계/계약 근거를 남긴다. 한 Site의 복수 MID는 각각 별도 계약/기관 경로일 수 있지만 하나의 결제 시도 도중 다른 MID로 변경하여 주문 중복 제어를 우회하지 않는다. SiteAssignment PRIMARY는 Merchant Default MID와 다른 개념이다.

PRIMARY 동시 유효 수·교체 직렬화, Assignment 상태 전이/기간 중첩, Default 교체·종료, 추가 MID 승인자의 실제 역할은 상세 구현 계약에서 구체화한다. 이전 대안의 자동 승격 금지/구체 승인 흐름을 본 첨부가 자동 채택했다고 보지 않는다.

## 3. 정책 원본과 Demo Seed

Fee/Settlement/Advance/Correction 정책 정의·Assignment·Calendar의 원본은 MASTER, 적용 Snapshot·계산·한도 점유·금융 사실은 FINANCIAL이다. PAYMENT는 승인 당시 권리와 정책 근거 버전을 보존한다. 객체 관계를 같은 DB 또는 직접 cross-DB JOIN 허용으로 해석하지 않는다.

DEMO DATA는 원문 예시(요율 2.8%, VAT 10%, D+2, 선지급 건당 1천만원/일 5천만원, 자동조정 건당 1천원/일 10만원)를 사용할 수 있다. 샘플은 환경/목적을 명시하고 Simulator 전용 설치 경로로 분리한다. 비율·금액·통화·반올림 단위는 Seed 스키마에 명시한다. 샘플 정책을 운영 마이그레이션의 자동 기본값으로 넣지 않는다.

예시에 없는 반올림 방식·dailyCountLimit 등은 구현자가 운영값으로 추측하지 않는다. 테스트별 fixture는 명시적으로 설정하고 정책 누락/모호함은 테스트에서 검증한다. 실제 운영용 값은 별도 권한/승인·dry-run·효력 기간 검증 후 활성화한다.

활성 정책의 수치/계산 의미는 덮어쓰지 않는다. 새 Version과 적용 기간/Assignment 이력으로 변경한다. 이전 효력 종료를 조정해야 한다면 승인된 전환 절차로 처리하고 과거 거래의 보존한 계산 근거를 바꾸지 않는다.

## 4. 최초 엔진 범위

SettlementCycle의 첫 구현은 DAILY_OFFSET, 영업일 D+N이다. D+2는 Demo Seed다. Weekly/Monthly/SemiMonthly는 확장 대상이며 미구현 유형을 받으면 명확히 거절한다. 거래일→다음 영업일 보정→Cycle→예정일 순서를 유지한다. D 포함 여부·cutoff/timezone은 별도 상세 값이다.

한도 모델은 Advance/Payout/AutoCorrection별로 분리한다. v0.1의 Advance enabled/건당/일 금액과 Correction 건당/일 금액/일 건수의 최소 엔진 범위를 채택한다. 이 축소가 사용 가능 재원·채무 Claim·미입금 노출·UNKNOWN 점유·위험 Hold를 생략하는 뜻은 아니다. 추가 한도 종류는 후속 확장한다.

## 5. 최신 기준과 남은 작업

본 확정본은 관계 기본 구조/조건과 Seed 분리의 현행 기준이다. 정산·선지급·대사 위험/복구 통제는 Settlement Control Policy v0.1 및 그 연결 절을 따른다. 기존 관계 대안은 이 문서와 충돌하면 이 문서를 우선한다.

KRW/체크카드/할부·고객 인증 추가 범위는 본문에서 운영 출시 범위로 직접 승인하지 않았으므로 기존 별도 권장안 상태를 유지한다. Demo 통화/세율 예시가 운영 범위를 자동 승인하지 않는다.

실제 API·DDL·정책 Seed 파일·프로젝트·Simulator 소스는 이번 기록에서 생성하지 않았다. 다음은 실제 저장소를 기준으로 상태 전이·필드 제약·TX·이벤트·명시 fixture/검증 명령을 포함한 첫 수직 기능 명세를 작성하는 단계다.
