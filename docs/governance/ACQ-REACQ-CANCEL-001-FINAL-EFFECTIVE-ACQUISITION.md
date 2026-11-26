# ACQ-REACQ-CANCEL-001 재매입 원승인의 취소 매입·재매입 통합 업무 정책 — 최종 유효 매입 상태(EAS)

> 상태: **rev 0.3a — 통합 업무 정책·영향 범위·변경 설계(소스 수정 전 검토본)**.
> - D-GOV-012(Q-ARC-1~3)와 D-GOV-017(Q-ARC-4·5)을 하나의 정책으로 정리했다. 사용자 요청("소스 수정 전 정책·영향 범위·변경 설계·시험 시나리오 검토")에 따라 **구현하지 않았다**.
> - 이 문서는 소스·시험·대장·`docs/DECISIONS.md`를 바꾸지 않는다. 시험 기대값을 바꾸지 않았다. 정본 확정·대장 반영·구현 착수 판단은 Coordinator와 사용자가 한다.
>
> 기준: `main` HEAD `45d18d2`. 작성 2026-11-26 KST.
> 근거 결정: **D-GOV-017 ①②**, **D-GOV-012 ①~④**, D-ACQ-004·009·010·012·013·014, D-ACQA-003·004, D-GOV-010 ①·③·④.
> 관련 대장 행: BR-FIN-016·036~041·046~053·138.

**개정 이력**
- **0.1·0.1a** (2026-11-26): 분석 초안, 리뷰 m-7~m-9 반영(부록 A~D).
- **0.2·0.2a·0.2b** (2026-11-26): D-GOV-012 반영 통합 정책, 독립 리뷰 CHANGES_REQUIRED·재리뷰 반영(EAS·매트릭스 25행·직렬화 요구·충돌 후보·질문 R-1·R-2).
- **0.3a** (2026-11-26, 리뷰 CHANGES_REQUIRED 반영 — 이 문서만 변경):
  - A-1: M26·MULTIPLE_ACCEPTED 전체(M19b·M26·ARC-28·29)는 대사(RECON)가 UNKNOWN 재매입을 연 뒤에만 도달한다고 정정했다. AQ-R14는 root로 만든 인공 상태다(`AcquirerResponseDbSpec.kt:539-560`). 도달 가능 목록에서 빼고 S-1 문안을 고쳤다.
  - A-2: '두 번째 성공 vs 상충' 경계를 [해석]으로 표시했다(§3.2·BR-FIN-038·S-1).
  - A-3: §10.4 응답 TX 잠금 방식, 결제 행 잠금 밖 상태 변화가 종결 판정을 뒤집지 않는 이유, 변이 시험, 처리량 영향을 적었다.
  - A-4: §12 BR-FIN-040의 S-1 부분을 미결 행으로, BR-FIN-049의 I-50 부분을 제안 행으로 분리했다.
  - A-5: §13 '확정 충돌 0'을 S-3 권장안 기준으로 한정하고, `decide(candidate, OriginalSaleResult, cutoff)` 오버로드 유지를 명시했다.
  - A-6: 사유 분류 정책 버전·결정 행 스냅샷을 후속 명세 요구로 넣었다.
  - A-7: 8대 원칙 증거표(§10.6)를 추가했다.
  - A-8: anomaly 멱등 방식을 고쳤다.
  - A-9: ARC-24 Given 순서를 고쳤다.
- **0.3** (2026-11-26, 사용자 답 D-GOV-017 반영 — 이 문서만 변경):
  - R-1은 Q-ARC-4로 답이 났다: **Q-ARC-2 우선, 유효 성공 세대(표지) 기준 전송 + 이중 매입 의심 등록**. `ACQUIRED_CONTESTED` 처리를 확정했다.
  - R-2는 Q-ARC-5로 답이 났다: 미성립 확정 + 재매입 가능 사유, 사유 목록 전 자동 재매입 불허·수동 2인.
  - §2 정책 문장을 P-50까지 넓혔다(사용자 판단표 5행·원칙 5개·Q-ARC-5). §2.3에 Q-ARC-1·2·4·5 정합표를 두었다.
  - §3에 '유효 성공 2건 이상'·'표지 세대 유효성 불확실'의 코드상 정의, 재매입 판정(실패 확정과 재매입 가능 분리)을 넣었다.
  - §4 매트릭스(31행), §5 전이표(재매입 생애 추가).
  - 새 절:
    - §8 현재 최고 세대 기준 동작의 소스·영향 범위
    - §9 원거래 식별 방식·표지 세대 기준 CANCEL 가능성 검증
    - §10 변경 설계
    - §11 미확정 정책 목록
  - §12 대장 정합, §13 시험 정합(R-2 충돌 후보 재분류), §14 신규 시험, §15 남은 결정 3개.

## 1. 배경

- BR-FIN-040(`docs/rules/FIN.md:79`, `제안`)은 "재매입된 원 SALE 뒤 취소는 최고 세대 결과 기준"이다. 사용자 결정 없이 ACQ-ADMIN-001 L-6과 CC-4 → CC-8 기대값 이동으로 들어왔다(부록 B).
- 사용자는 D-GOV-010 ④ → D-GOV-012 → D-GOV-017 순서로 답했다. "원승인 전체 매입 이력에서 매입사가 정상 수신한 유효 성공 세대 기준으로 취소 매입하고, 재매입은 미성립 확정 + 재매입 가능 사유일 때만"이라는 정책으로 정했다.

## 2. 통합 업무 정책

### 2.1 정책 문장

용어:
- **매입 이력**: 한 원승인의 SALE 레코드 전부(세대 0..n)·결과·파일 전송/수신 상태·매입 확정 표지.
- **유효 성공 세대**: 매입사가 정상 수신해 ACCEPTED로 확정한 세대. 코드상 표지 세대이며 §3.2 조건을 만족해야 한다.
- **이중 매입 의심**: 유효 성공 세대 1건 + 다른 세대 미결·UNKNOWN·CONFLICT.
- **이상 거래**: 유효 성공 2건 이상(§3.2).
- **누락 의심**: 이미 쓰인 '불요'가 유효 매입 순액에 반영되지 않았을 수 있음(§3.4).

**D-GOV-012 ① (Q-ARC-1)**

| ID | 업무 규칙 |
|---|---|
| P-01 | 취소 매입 여부는 최고·최초 세대 하나가 아니라 원승인의 **전체 매입 이력**으로 계산한 EAS로 정한다. |
| P-02 | 유효 성공 세대가 **하나로 식별**될 때 그 세대를 기준으로 취소 매입한다(다른 세대 미결·상충의 처리는 P-31). |
| P-03 | 유효 성공 세대를 **확정할 수 없을 때만**(성공 세대 없음 + 미결, 또는 표지 세대 자체의 유효성 불확실) 자동 판정을 보류하고 대사로 넘긴다(P-34로 범위 확정). |
| P-04 | '취소 매입 불요'는 **모든 매입 이력이 실패로 확정**된 경우에만 기록한다(UNKNOWN은 실패가 아니다 — P-36). |
| P-05 | 진행 중·미확정의 무기한 대기를 막는다. 재조회·대사 전환·운영 알림 기준을 둔다(§6). |
| P-06 | 전체·부분·반복 부분취소, 중복 취소 매입 방지, 동시성을 모두 검증한다. |
| P-07 | 기존 시험 기대값은 임의로 바꾸지 않는다. 확정 정책은 신규 시험으로 고정하고, 충돌하는 기존 시험은 보고한다(§13). |
| P-08 | 전체 매입 상태별 판단 매트릭스·상태 전이표를 두고 문서·도메인 로직·시험이 같은 표를 따른다(§4·§5). |

**D-GOV-012 ② (Q-ARC-2)**

| ID | 업무 규칙 |
|---|---|
| P-09 | 유효 성공 세대가 정확히 식별되고 취소 매입이 가능하면(거래 단위 대기·누적 한도 통과) 다음 정기 파일로 **즉시 전송**한다. |
| P-10 | 유효 성공 세대가 식별되었는데 다른 세대에 미결·UNKNOWN·CONFLICT가 있으면 **이중 매입 의심 건으로 등록**하고 별도로 대사한다. 취소는 P-31대로 전송한다. |
| P-11 | 유효 매입 대상을 식별할 수 없으면 자동 전송 없이 대사로 보류한다. |
| P-12 | 복수 매입 성공이 확인되면 일반 취소와 **중복 매입 보정**을 구분해 처리한다. |
| P-13 | 취소 매입은 **전송·성공 확정·대사 완료**를 구분해 관리한다(§5.2). |
| P-14 | 중복 취소 매입·초과 취소 매입을 막는다. 같은 취소는 상계·CANCEL·불요 중 정확히 하나이고, 누적(유효 세대 상계 + 봉인 CANCEL + 이번)은 원승인액 이하다. |
| P-15 | 미확정 매입·취소로 인한 가맹점 과다 정산·지급 방지 정책은 **별도로 정의**한다(§7 F-1). |
| P-16 | 매입사별 취소 매입 식별 방식·응답 규격을 검증한다(§9, §11). |
| P-17 | BR-FIN-040과 통합한 매트릭스로 관리하고 기존 규칙·시험 정합을 검증한다(§12·§13). |

**D-GOV-012 ③ (Q-ARC-3)**

| ID | 업무 규칙 |
|---|---|
| P-18 | 기존 취소 매입 누락 의심 거래는 **읽기 전용 조회**로 식별하고 자동 보정하지 않는다. |
| P-19 | 누락 의심은 대사·**2인 승인**을 거쳐 필요한 경우에만 append-only로 보정한다. |
| P-20 | 누락 의심 거래의 지급 보류는 **해당 거래 단위**로 한다. |
| P-21 | 이미 지급된 누락 의심 거래는 **별도 환수 정책**을 따른다(§7 F-2). |
| P-22 | 재매입 가능 여부도 **전체 매입 이력**으로 판정한다. |
| P-23 | 이력에 CONFLICT·UNKNOWN·처리 진행 중·유효 성공이 있으면 재매입을 차단한다. |
| P-24 | 재매입은 매입 미성립 확정 + 재매입 가능 사유일 때만 허용한다(P-39~P-44로 구체화). |
| P-25 | 재매입·취소 판정은 멱등이고, EAS를 읽는 판정과 결과 반영은 원승인 단위로 직렬화한다(§10.4). |

**D-GOV-012 ④ (공통)**

| ID | 업무 규칙 |
|---|---|
| P-26 | Q-ARC-1~5를 BR-FIN-040과 통합한 하나의 정책(이 문서)으로 관리한다. |
| P-27 | 비슷한 판단은 **공통 도메인 정책 하나**(EAS)로 두고 기능별로 중복 구현하지 않는다. |
| P-28 | 정책 문서·전이표·매트릭스·시험을 함께 정비한다. |
| P-29 | 불필요한 모듈 분리·과도한 설계를 하지 않는다. |

**D-GOV-017 ① (Q-ARC-4 — Q-ARC-2 우선)**

| ID | 업무 규칙 |
|---|---|
| P-30 | 취소 매입의 기준 세대는 최고 세대가 아니라 매입사가 정상 수신한 **유효 성공 세대(표지 세대)**다. |
| P-31 | 다른 세대가 미결·UNKNOWN·CONFLICT여도 유효 성공 세대가 명확히 식별되면 **보류하지 않고** 표지 세대 기준으로 전송하며, 이중 매입 의심을 등록한다. |
| P-32 | 유효 성공이 **2건 이상**이면 **이상 거래로 등록하고 별도 처리**한다(취소 전송 여부는 §15 S-1). |
| P-33 | **표지 세대 자체의 유효성이 불확실**하면 보류한다(대사 보류). |
| P-34 | Q-ARC-1의 보류는 유효 성공 세대를 확정할 수 없을 때(P-33, 또는 성공 세대 없음 + 미결)**만** 적용한다. |
| P-35 | 승인 매입 정합성 관리(이중 매입 의심·이상 거래·대사)와 취소 매입 전송 판단을 **분리**한다. 의심·이상 등록이 취소 전송을 막지 않는다(P-32의 S-1 제외). |
| P-36 | UNKNOWN을 실패로 간주하지 않는다(불요·재매입 근거가 되지 않음). |
| P-37 | 취소 매입이 성공 확정된 뒤에도 다른 세대 미결·이중 매입 의심 관리는 해소될 때까지 유지한다. |
| P-38 | 전체취소와 부분취소(반복 포함)에 같은 원칙을 적용한다(금액 한도만 다름). |

**D-GOV-017 ② (Q-ARC-5)**

| ID | 업무 규칙 |
|---|---|
| P-39 | 재매입은 **기존 매입 미성립 확정**과 **재매입 가능한 실패 사유**를 둘 다 만족할 때만 한다. |
| P-40 | 재매입 가능 여부는 **매입사별 응답코드 기준**으로 관리한다(코드 목록 자체는 미확정 정책 — §11). |
| P-41 | 사유 목록이 확정되기 전에는 **자동 재매입을 허용하지 않는다**. 미매입 확정 거래만 사유를 확인하고 2인 승인 후 수동으로 재매입한다. |
| P-42 | FILE_REJECTED는 **파일 안 거래 전체의 미매입이 보장될 때만** 미성립 확정으로 본다(§3.5). |
| P-43 | UNKNOWN 자체로는 재매입하지 않는다. 매입사 대사·공식 결과 확인으로 미매입이 확정되면 실패 확정으로 전환할 수 있다. 대사 데이터 미존재·응답 지연은 미매입 확정 근거가 아니다. |
| P-44 | 실패 확정 뒤에도 재매입 가능 여부는 **별도로 판단**한다(실패 확정 ≠ 재매입 가능). |
| P-45 | 유효 성공 세대 또는 미해소 미결·상충 세대가 있으면 재매입을 차단한다(P-23과 같음). |
| P-46 | 중복 요청·동시 실행으로 인한 이중 매입을 막는다. |
| P-47 | 재매입은 새 세대를 만들고 기존 전송 이력을 보존한다(append-only). |
| P-48 | 실패 사유·미매입 확정 근거·요청자·승인자·실행 이력을 관리한다. |
| P-49 | 매입사별 응답코드·미매입 확정 기준은 **임의로 정의하지 않는다**(미확정 정책 — §11). |
| P-50 | 소스 수정 전에 정책·영향 범위·변경 설계·시험 시나리오를 검토한다(이 문서 §8~§10·§14). |

**정책 문장 수: 50.** P-03·P-10·P-24는 0.2b 문장을 D-GOV-017로 고쳤다.

### 2.2 사용자 판단표(D-GOV-017 ① 원문) → 이 문서의 EAS

| 사용자 판단표 | EAS(§3.2) | 취소 처리 | 등록 |
|---|---|---|---|
| 유효 성공 세대 없음 + 미결 | `PENDING` / `UNRESOLVED` | 보류(대기 / 대사 보류) | 대사 경보(UNRESOLVED) |
| 유효 성공 1건 + 다른 세대 실패 | `ACQUIRED(g)` | 표지 세대 기준 정상 전송 | — |
| 유효 성공 1건 + 다른 세대 미결·UNKNOWN·CONFLICT | `ACQUIRED_CONTESTED(g)` | 표지 세대 기준 전송 | 이중 매입 의심 |
| 유효 성공 2건 이상 | `MULTIPLE_ACCEPTED(g)` | **S-1**(권장: 표지 세대 기준 일반 취소 전송) | 이상 거래 |
| 표지 세대 자체의 유효성 불확실 | `UNRESOLVED`(표지 불확실) | 보류(대사 보류) | 대사 경보 |
| (판단표 밖) 모든 이력 실패 확정 | `NOT_ACQUIRED(g)` | 불요 | — |

### 2.3 Q-ARC-1·2·4·5 정합표(충돌 없음 확인)

| 쟁점 | Q-ARC-1(D-GOV-012 ①) | Q-ARC-2(②) | Q-ARC-4(D-GOV-017 ①) | Q-ARC-5(②) | 이 문서의 하나의 읽기 |
|---|---|---|---|---|---|
| 기준 세대 | 전체 이력, 단일 식별 | 정확히 식별 | 표지 세대, 최고 세대 아님 | — | P-01·P-30: 전체 이력에서 표지 세대 |
| 다른 세대 미결·UNKNOWN·CONFLICT | "확정 불가면 보류" | "의심 등록·별도 대사" | **Q-ARC-2 우선**, 식별되면 보류 안 함 | — | P-31·P-34: 식별되면 전송 + 의심, 보류는 식별 불가일 때만 |
| 복수 성공 | "확정 불가면 보류" | "일반 취소와 중복 매입 보정 구분" | "이상 거래 등록·별도 처리" | — | P-12·P-32: 이상 거래. 일반 취소 전송 여부만 S-1 |
| 불요 | 모든 이력 실패 확정만 | — | UNKNOWN ≠ 실패 | UNKNOWN은 공식 확인으로만 실패 확정 | P-04·P-36·P-43 |
| 재매입 차단 | — | — | — | 유효 성공·미해소 미결·상충 차단 | P-23·P-45(Q-ARC-3 ③과 같음) |
| 재매입 허용 | — | — | — | 미성립 확정 + 가능 사유, 목록 전 수동 2인 | P-39~P-41(Q-ARC-3 ③ "가능 사유"의 구체화) |
| 누락 의심 | — | — | — | — | P-18~P-21(Q-ARC-3 ③) — 다른 답과 겹치지 않음 |
| 정합성 관리 vs 전송 | — | 의심 등록 + 전송 | 분리, 성공 뒤에도 관리 유지 | — | P-35·P-37 |

남는 해석 공백은 세 가지다(§15 질문). 그 밖에는 충돌이 없다.
- 복수 성공 때 일반 취소를 보낼지(S-1)
- '표지 유효성 불확실'의 범위(S-2)
- FILE_REJECTED '전체 미매입 보장'의 Demo/TEST 기준(S-3)

### 2.4 이전 서술 대체

| 이전(0.2b) | 0.3 |
|---|---|
| R-1 미결, 답 전 현행 유지, `ACQUIRED_CONTESTED` 판정 보류 | **확정**: 표지 세대 기준 전송 + 의심 등록(P-31). §10.2 어댑터의 "CONTESTED → 최고 세대 결과" 임시 규칙은 폐기 |
| 표지 + 관찰 ACCEPTED → (0.2) 보류 / (0.2a) R-1 | 관찰 ACCEPTED를 두 종류로 나눈다(§3.2). 앞선 확정 결과 없이 들어온 ACCEPTED는 '유효 성공 2건 이상'(MULTIPLE_ACCEPTED, S-1). REJECTED 뒤 상충은 'CONFLICT'(CONTESTED, 전송 + 의심) |
| R-2(재매입 사유) 권장: 목록 전 모든 REJECTED 허용, 2인 승인자 판단 | Q-ARC-5와 같은 방향이다: 목록 전 자동 재매입 불허, 사유 확인 + 2인 승인 수동. 추가로 FILE_REJECTED 보장 조건(P-42)과 실패 확정/재매입 가능 분리(P-44) |
| §9 R-2 충돌 후보(`AMOUNT_MISMATCH` Fixture 시험) | 재분류: 현 코드는 자동 재매입이 없고 모두 2인 승인 명령이다(§13). 충돌 없음 |

## 3. 공통 판정 — EAS

### 3.1 입력(원승인 하나, §10.4 직렬화 아래)

- 그 원승인의 SALE 레코드 전부: 세대, 파일 ID·순번, 상계액, 결과 행 목록(결과·관찰 결과·사유·출처·순번 — `acquisition_record_result`).
- 매입 확정 표지(`acquisition_accepted_record`, 거래·SALE당 하나).
- 각 파일의 전송 상태·수신 상태(`acquisition_file.transmission_state`·`receipt_state`)와 보류 근거의 직전 상태(`acquisition_transmission_withhold.prior_state` — SEALED = 기관에 간 적 없음, TRANSMITTING·TRANSMISSION_UNKNOWN = 갔을 수 있음: `V19__…:13`).
- 미매입 확정 근거(UNKNOWN의 공식 대사 확인 — RECON 전에는 없음).

### 3.2 EAS 값과 코드상 정의(위에서부터 먼저 맞는 것)

먼저 세대별 레코드를 다음으로 분류한다(결과 행은 append-only라 판정 재현 가능).

| 세대 분류 | 코드상 조건 |
|---|---|
| **유효 성공** | 표지가 가리키는 레코드. 그 레코드에 CONFLICT 행이 없고, 그 파일이 수신 축 `RECEIPT_CONFLICT`가 아니며 전송 축 `TRANSMISSION_QUARANTINED`가 아님(S-2) |
| **표지 불확실** | 표지가 가리키는데 위 조건을 깸: ① 그 레코드에 CONFLICT 행(ACCEPTED 뒤 상충 결과 — `RecordResultRule.kt:50-58`) ② (S-2 권장 포함) 그 파일 수신 축 RECEIPT_CONFLICT(모순된 ACK — ACQUIRING-001 I-45) 또는 전송 축 격리 |
| **두 번째 성공** | 표지가 다른 레코드에 있어 들어온 ACCEPTED가 CONFLICT 행(관찰 ACCEPTED)이 되었는데, 그 레코드에 **그 전 확정 행(ACCEPTED·REJECTED)이 없었음**(AWAITING·UNKNOWN에서 매입사가 수락 — `RecordResultRule.kt:44-46`). 매입사 입장에서는 정상 수락이다. **[해석]** '두 번째 성공'과 '상충'을 그 레코드의 앞선 확정 행 유무로 가르는 기준은 Coordinator 해석이다(D-GOV-017 ① '유효 성공 2건 이상'의 코드상 정의). **자연 도달**: 세대 ≥ 1은 앞 세대가 REJECTED·FILE_REJECTED로 종결된 뒤에만 생기고(`ReacquisitionRule.kt:29-35`), 그 뒤 앞 세대에 온 ACCEPTED는 '상충'이 된다(`RecordResultRule.kt:50-58` — M13). 그래서 두 번째 성공은 대사(RECON)가 UNKNOWN 재매입을 연 뒤에만 생긴다. AQ-R14(`AcquirerResponseDbSpec.kt:539-560`)는 root로 만든 인공 상태다 |
| **상충(CONFLICT)** | 그 밖의 CONFLICT 행: REJECTED 뒤 ACCEPTED·다른 사유 REJECTED 등 자기 모순(`:50-58`) |
| **미결** | 결과 대기(SEALED 점유됨·TRANSMITTING·TRANSMITTED·TRANSMISSION_UNKNOWN), UNKNOWN(미매입 확정 근거 없음), WITHHELD이면서 prior_state ≠ SEALED, 전송 격리 |
| **실패 확정** | REJECTED(RESULT_FILE). FILE_REJECTED는 §3.5 보장 조건을 만족할 때만. UNKNOWN + 공식 미매입 확정 근거 |
| **미전송** | SEALED 미점유(표지가 다른 세대에 있으면 점유 때 보류됨), WITHHELD이면서 prior_state = SEALED. 기관에 간 적이 없으므로 매입 효과가 없다 |

| EAS | 조건 | 취소 처리 | 등록 | 재매입 |
|---|---|---|---|---|
| `UNRESOLVED` | 표지 불확실, **또는** 표지 없음 + 어느 세대든 상충·두 번째 성공 관찰·미결 중 UNKNOWN·격리·결과 마감 뒤 TRANSMISSION_UNKNOWN·FILE_REJECTED 보장 불충족, **또는** 표지 없는 ACCEPTED 상태(무결성) | 대사 보류 | 대사 경보 | 차단 |
| `MULTIPLE_ACCEPTED(g)` | 유효 성공 g + 다른 세대에 두 번째 성공([해석] — RECON 전 자연 경로 없음) | **S-1**(권장: g 기준 일반 취소 전송) | **이상 거래** | 차단 |
| `ACQUIRED_CONTESTED(g)` | 유효 성공 g + 다른 세대에 미결·상충(두 번째 성공 제외) | g 기준 **전송**(P-31) | **이중 매입 의심** | 차단 |
| `ACQUIRED(g)` | 유효 성공 g + 다른 세대 모두 실패 확정·미전송 | g 기준 전송 | — | 차단 |
| `PENDING(g)` | 표지 없음, 상충 없음, 최고 세대 g가 정상 결과 대기(결과 마감 전) | 대기 | — | 차단(처리 진행 중) |
| `NOT_ACQUIRED(g)` | 표지 없음, **모든** 세대 실패 확정 또는 미전송(최고 세대는 실패 확정) | 불요(세대 g 레코드) | — | §3.5 |

누적 한도: 원승인액 − **세대 g 레코드의 상계액** − 봉인 CANCEL 합. g가 최고 세대가 아니어도 같다. 해석 근거는 D-GOV-012 ①·D-GOV-017 ① "그 매입 기준"이다.

### 3.3 이중 매입 의심·이상 거래 등록(P-10·P-32·P-35·P-37)

- **등록**: 원승인 단위 append-only(§10.5 저장소). 종류는 DOUBLE_ACQUISITION_SUSPECTED(CONTESTED) / MULTIPLE_ACCEPTED(이상 거래) / CANCEL_MISSING_SUSPECTED(누락 의심 — §3.4)이다.
- **해소**: 대사 결과 행으로만. 취소 매입 성공 확정은 해소 사유가 아니다(P-37).
- **분리**: 등록은 취소 전송을 막지 않는다(P-35). 지급 영향은 F-1(별도 정의)이다. P-20(거래 단위 지급 보류)은 누락 의심에만 적용한다.

### 3.4 누락 의심(P-18 — 읽기 전용)

'불요' 기록 n(근거 레코드 세대 b)이 다음 중 하나에 해당하면 누락 의심이다.
- **근거 세대 b 이하에 표지가 있다**.
- **그 원승인의 어느 SALE 세대에든(근거 레코드 자신 포함) CONFLICT 행이 있다**.

추가 기준:
- (0.3 추가) 근거 레코드가 FILE_REJECTED인데 그 파일이 §3.5 보장 조건을 깬다(수신 축 RECEIPT_CONFLICT).
- 표지 세대 > b인데 순액에서 빠진 경우는 I-17 대조(`reacquisitionNettingMismatches`)가 잡는다.
- 정상 진행(M08·M11)과 정상 재매입 성공(M03 → M09)은 0건이다.

### 3.5 재매입 판정 — 실패 확정과 재매입 가능 분리(P-39~P-48)

| 단계 | 조건 | 지금 코드 |
|---|---|---|
| ① 미성립 확정(EAS = NOT_ACQUIRED, 대상 = 최고 세대 레코드 — I-50 해석 유지) | 모든 세대 실패 확정·미전송. **FILE_REJECTED 보장 조건**: ⓐ ACK FILE_REJECTED가 봉인 파일 이름·hash와 맞음(I-8) ⓑ 그 파일 수신 축이 RECEIPT_CONFLICT가 아님 ⓒ 그 파일 어느 레코드에도 ACCEPTED·CONFLICT 결과 행이 없음 ⓓ 매입사 규격이 FILE_REJECTED를 "레코드 처리 없음"으로 정의(가상 매입사는 "레코드 판정 없음" — `AcquirerScenarioAction.kt:25`, Simulator 규격. 실제 기관은 미확정 정책 — S-3). **UNKNOWN**: 공식 대사 확인으로만 실패 확정(P-43 — RECON 전에는 불가) | 대상 레코드 상태 REJECTED·FILE_REJECTED + 표지 없음 + 최신 세대만 본다(`ReacquisitionRule.kt:28-36`). ⓑ·ⓒ·다른 세대 CONFLICT를 보지 않는다(M15·M28) |
| ② 재매입 가능 사유 | 매입사별 응답코드 분류(P-40): REACQUIRABLE / NOT_REACQUIRABLE / UNCLASSIFIED. **목록 확정 전 = 모든 코드 UNCLASSIFIED** → 자동 재매입 없음, 사람이 사유를 확인하고 2인 승인한 수동 재매입만(P-41). NOT_REACQUIRABLE은 2인 승인이 있어도 거절 | 사유 분류 없음. 재매입은 **이미 수동 2인 승인 명령뿐**이다(ADMIN 서명 채널 `AdminAcquiringOperationsController.kt:38` → `ReacquisitionExecutionService.execute`, 요청자 ≠ 승인자 `ReacquisitionRule.kt:26-27`·`ReacquisitionExecutionRule.kt:39`). 배치 스케줄러에는 재매입 경로가 없다(`AcquisitionScheduler` — 봉인·전송·응답·마감만) → P-41과 같음 |
| ③ 실행 조건(현행 유지) | 미판정 취소 없음, 보류 없음, 기한 안, 묶음 전체 무효, 순액 0이면 재매입 불요 | `ReacquisitionExecutionRule.kt:35-67` |
| ④ 멱등·동시성(P-46) | 승인 ID UNIQUE, (파일, 순번, 새 세대) UNIQUE, (거래, 종류, 세대) UNIQUE, 재매입 파일 전송 보류, + §10.4 직렬화 | `V17__…:26·43`, `V5__…:102`, `AcquisitionTransmissionService.kt:167-182` |
| ⑤ 이력(P-47·P-48) | 새 세대 파일·레코드(원 참조), 결정 행(요청자·승인자·승인 시각·기한·결정·거절 코드 — `V17__…:10-24`), 승인 행(사유 코드·근거 참조), ADMIN 명령 행(사유 문구), 실패 사유 = 대상 레코드 결과 행 `reason_code`(append-only라 (파일, 순번)으로 재현 가능 — 따로 복사하지 않음), 미매입 확정 근거 = 근거 참조 + (RECON 뒤) 대사 기록 | 있음(대사 기록 제외) |

## 4. 통합 판단 매트릭스

**표기**:
- 거래 단위 대기(LIVE·마감 뒤·보류 BR-FIN-138·KRW 외·원거래 불일치·누적 초과 INCONSISTENT·표현 불가)는 모든 행에서 먼저 적용된다.
- **전체취소·부분취소·반복 부분취소는 같은 판정**이다(P-38). 금액만 다르다: 전체 = 남은 금액 전부(한도 경계), 부분 = 한 건, 반복 부분 = (취소 시각, 거래 ID) 순 누적. 그래서 한 열로 쓴다.
- "현재" = 지금 코드(최고 세대 기준).

| # | 매입 이력 조합 | EAS | 취소(전체·부분·반복) | 등록 | 재매입 | 현재(취소 / 재매입) |
|---|---|---|---|---|---|---|
| M01 | g0 결과 대기 | PENDING(0) | 대기 | — | 차단 | 같음 / 같음 |
| M02 | g0 ACCEPTED | ACQUIRED(0) | 전송(한도 = 원승인 − g0 상계) | — | 차단 | 같음 / 같음 |
| M03 | g0 REJECTED, 재매입 없음 | NOT_ACQUIRED(0) | 불요 | — | 수동 2인(사유 확인) | 같음 / 같음 |
| M04 | g0 UNKNOWN | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |
| M05 | g0 표지 + 그 레코드 CONFLICT(성공 뒤 상충) | UNRESOLVED(표지 불확실) | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |
| M06 | g0 상충(표지 없음), 재매입 없음 | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |
| M07 | g0 REJECTED + 재매입 실행 중·승인 대기 | NOT_ACQUIRED(0) | 불요(실행 순액이 뺌) | — | 같은 대상 두 번째 승인 차단 | 같음 / 같음 |
| M08 | g0 REJECTED + g1 결과 대기 | PENDING(1) | 대기 | — | 차단 | 같음 / 같음 |
| M09 | g0 REJECTED + g1 ACCEPTED | ACQUIRED(1) | 전송(한도 = 원승인 − g1 상계) | — | 차단 | 같음 / 같음 |
| M10 | g0 REJECTED + g1 REJECTED | NOT_ACQUIRED(1) | 불요(g1) | — | g2 수동 2인 | 같음 / 같음 |
| M11 | g0 REJECTED + g1 UNKNOWN | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |
| M12 | g0 REJECTED + g1 상충(표지 없음) | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |
| M13 | g0 상충(REJECTED 뒤 관찰 ACCEPTED) + g1 ACCEPTED(표지 g1) | ACQUIRED_CONTESTED(1) | **전송**(g1) | 이중 매입 의심 | 차단 | 같음(전송) / 같음 — 등록만 새로 |
| M14 | g0 상충(표지 없음) + g1 결과 대기 | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | **다름**(현재 대기 — 둘 다 안 보냄) / 같음 |
| M15 | g0 상충(표지 없음) + g1 REJECTED | UNRESOLVED | 대사 보류 | 대사 경보 | **차단** | **다름**(현재 불요 — 누락) / **다름**(현재 g2 승인) |
| M16a | g0 표지 + g1 SEALED 미점유·WITHHELD(prior SEALED) | ACQUIRED(0) | 전송(g0) | — | 차단 | **다름**(현재 무기한 대기) / 같음 |
| M16b | g0 표지 + g1 WITHHELD(prior TRANSMITTING·TRANSMISSION_UNKNOWN — 갔을 수 있음) | ACQUIRED_CONTESTED(0) | 전송(g0) | 이중 매입 의심 | 차단 | **다름**(현재 무기한 대기) / 같음 |
| M17 | **g0 표지 + g1 전송됨·결과 대기·TRANSMISSION_UNKNOWN** | ACQUIRED_CONTESTED(0) | **전송**(g0) | 이중 매입 의심 | 차단 | **다름**(현재 대기) / 같음 |
| M18 | g0 표지 + g1 REJECTED | ACQUIRED(0) | 전송(g0) | — | 차단 | **다름**(현재 불요 — 누락) / 같음 |
| M19a | g0 표지 + g1 상충(REJECTED 뒤 관찰 ACCEPTED) | ACQUIRED_CONTESTED(0) | 전송(g0) | 이중 매입 의심 | 차단 | **다름**(현재 대사 보류) / 같음 |
| M19b | g0 표지 + g1 두 번째 성공(결과 대기·UNKNOWN에서 ACCEPTED → CONFLICT) | MULTIPLE_ACCEPTED(0) | **S-1**(권장 전송) | **이상 거래** | 차단 | 현재 대사 보류 / 같음 — S-1 답이 '전송'이면 다름 |
| M20 | **g0 표지 + g1 UNKNOWN** | ACQUIRED_CONTESTED(0) | 전송(g0) | 이중 매입 의심 | 차단 | **다름**(현재 대사 보류) / 같음 |
| M21 | g0 표지 + g1 상충(관찰 REJECTED) | ACQUIRED_CONTESTED(0) | 전송(g0) | 이중 매입 의심 | 차단 | **다름**(현재 대사 보류) / 같음 |
| M22 | g0..gn−1 REJECTED + gn 결과 대기 | PENDING(n) | 대기 | — | 차단 | 같음 / 같음 |
| M23 | g0 REJECTED + g1 격리 또는 결과 마감 뒤 TRANSMISSION_UNKNOWN | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | **다름**(현재 무기한 대기 — 둘 다 안 보냄) / 같음 |
| M24 | 이미 '불요'가 있고 §3.4 누락 의심 | (현재 EAS) | 자동 재판정 없음 → 탐지 → 대사 → 2인 승인 필요 시 보정 | 누락 의심(거래 단위 지급 보류) | EAS대로 | **다름**(탐지 없음) / — |
| M25 | ACQUIRED(g) + 앞선 CANCEL 레코드 실패(REJECTED·UNKNOWN·CONFLICT) 뒤 새 부분취소 | ACQUIRED(g) | 새 취소 전송(한도 계산 문제 없음 — 0.2a M25 주). 실패한 앞 CANCEL은 F-7(BR-FIN-050, ACQ-ADMIN-002) | — | 차단 | 같음 / 같음 |
| M26 | g1 표지 + g0 두 번째 성공(g0 결과 대기·UNKNOWN에서 늦은 ACCEPTED — RECON 뒤에만 도달, AQ-R14는 인공 상태) | MULTIPLE_ACCEPTED(1) | **S-1** | 이상 거래 | 차단 | 현재 전송 / 같음 — S-1 답이 '보류'면 다름 |
| M27 | g0 표지 + 그 파일 수신 축 RECEIPT_CONFLICT 또는 전송 격리(표지 뒤) | UNRESOLVED(표지 불확실 — S-2) | 대사 보류 | 대사 경보 | 차단 | **다름**(현재 전송) / 같음 — S-2 답이 '제외'면 같음 |
| M28 | g0 FILE_REJECTED + 그 파일 수신 축 RECEIPT_CONFLICT(모순 ACK) | UNRESOLVED(보장 불충족) | 대사 보류 | 대사 경보 | **차단** | **다름**(현재 불요) / **다름**(현재 재매입 가능) |
| M29 | g0 FILE_REJECTED(보장 충족), 재매입 없음 | NOT_ACQUIRED(0) | 불요 | — | 수동 2인(사유 확인) | 같음 / 같음(S-3 답에 따름) |
| M30 | g0 UNKNOWN + 공식 대사 미매입 확정(RECON 뒤) | NOT_ACQUIRED(0) | 불요 | — | 수동 2인(사유 확인) | 현재 대사 보류 / 거절(UNKNOWN_NOT_RECONCILED) — 경로 없음(RECON 전 도달 불가) |
| M31 | g0 UNKNOWN + 대사 데이터 없음·응답 지연 | UNRESOLVED | 대사 보류 | 대사 경보 | 차단 | 같음 / 같음 |

**매트릭스 행 수: 31**(M16·M19가 a/b로 나뉘어 표의 행은 31).
- 현재 코드와 확정적으로 다른 칸:
  - 취소 처리 12칸: M14·M15·M16a·M16b·M17·M18·M19a·M20·M21·M23·M24·M28.
  - 재매입 2칸: M15·M28.
  - S-1·S-2에 따라 달라지는 칸 3개: M19b·M26·M27.
- M13은 판정이 같고 등록만 새로 생긴다.
- **지금 도달 가능**: M13·M14·M15·M23·M24·M27·M28. M16~M21(M19b 포함)·M26·M30은 대사(RECON)가 UNKNOWN 재매입을 열 때 생긴다. MULTIPLE_ACCEPTED 전체는 RECON 전 자연 경로가 없다(0.3a 정정).

## 5. 상태 전이표

### 5.1 원승인 EAS 전이

| 현재 \ 이벤트 | 유효 성공 세대 ACCEPTED 수신 | 다른 세대 결과 대기·UNKNOWN 중 ACCEPTED 수신(두 번째 성공) | 다른 세대 REJECTED 수신 | 다른 세대 상충·마감 UNKNOWN | 재매입 실행 | 대사 결과 |
|---|---|---|---|---|---|---|
| PENDING(g) | ACQUIRED(g) | — | 남은 진행 세대 없으면 NOT_ACQUIRED(g) | UNRESOLVED | (차단) | — |
| NOT_ACQUIRED(g) | (종결 레코드에 ACCEPTED → 상충) UNRESOLVED | — | 그대로 | UNRESOLVED | PENDING(g+1) | — |
| ACQUIRED(g) | 중복 | MULTIPLE_ACCEPTED(g) + 이상 거래 | 그대로 | ACQUIRED_CONTESTED(g) + 의심 | (차단) | — |
| ACQUIRED_CONTESTED(g) | 중복 | MULTIPLE_ACCEPTED(g) | 남은 미결·상충 세대가 없으면 ACQUIRED(g)(의심 등록은 대사 해소 전까지 유지 — P-37) | 그대로 | (차단) | 미매입 확인 → 의심 해소, 이중 매입 확인 → MULTIPLE_ACCEPTED(보정 F-5) |
| MULTIPLE_ACCEPTED(g) | 중복 | 그대로 | 그대로 | 그대로 | (차단) | 중복 매입 보정 기록(F-5) 뒤에도 이력은 남음 |
| UNRESOLVED | 늦은 결과 반영 뒤 다시 계산(상충 행은 남는다) | 다시 계산 | 다시 계산 | 그대로 | (차단) | 대사 완료 기록으로만 확정(F-6) |

표지 레코드 자신에 상충 결과가 오거나, 표지 파일이 수신 충돌·격리되면 어느 상태에서든 UNRESOLVED(표지 불확실)로 간다.

### 5.2 취소 매입 생애(P-13·P-37)

| 상태 | 다음 | 현재 구현 |
|---|---|---|
| 미판정 → 판정 | 대기 / 대사 보류 / 불요 / 포함 | 있음 |
| 대기 | 다음 봉인 재판정, 기준 초과 알림(§6) | 대기 있음, 알림 없음 |
| 대사 보류 | 다음 봉인 재판정, 대사 완료 → 재판정 | 로그·지표만 |
| 불요(종결) | 누락 의심이면 M24 | 있음 |
| 포함(봉인, 세대 0 CANCEL 레코드) | 전송 / 전송 격리(정기 파일은 WITHHELD 없음) | 있음 |
| 전송됨 | 성공 확정(ACCEPTED) / 실패(REJECTED) / UNKNOWN / 상충 | 있음 |
| **성공 확정** | 대사 MATCH → 대사 완료. **의심·이상 거래 등록은 별도로 유지**(P-37) | 성공 확정 있음, 대사 완료 없음 |
| 실패 | CANCEL 재매입(BR-FIN-050 — ACQ-ADMIN-002) 또는 대사 | 경로 없음(F-7) |
| 대사 완료 | 끝 | 없음(F-6) |

### 5.3 재매입 생애(P-39~P-48)

| 상태 | 들어오는 조건 | 다음 | 현재 구현 |
|---|---|---|---|
| 재매입 검토 대상 | 최고 세대 실패 확정(EAS NOT_ACQUIRED) | 요청(OPERATOR) | 배지 CANDIDATE — 다른 세대 CONFLICT·FILE_REJECTED 보장을 보지 않음 |
| 사유 확인 | 요청 화면에 결과·사유 코드 표시(ACQ-VIEW-001). 사유 분류 NOT_REACQUIRABLE이면 요청 불가, UNCLASSIFIED면 수동 확인 | 2인 승인(APPROVER + TOTP) | 표시 있음, 분류 없음 |
| 승인됨 | 2인 승인 | FINANCIAL 실행 TX: EAS·대상 버전 재검사 → 새 세대 봉인 / 재매입 불요 / 거절 | 있음(EAS 대신 표지·최신 세대) |
| 새 세대 진행 | 새 버전 파일 SEALED | 전송·결과(EAS PENDING) | 있음 |
| 종결 | 새 세대 ACCEPTED(ACQUIRED) / REJECTED(다시 검토 대상) / UNKNOWN(대사) | — | 있음 |

## 6. 무기한 대기 방지(P-05) — 0.2b §6(부록 E.4) + 추가

- 기존 장치: 회차별 재판정, D-ACQ-010 늦은 결과, 결과 마감 UNKNOWN, 확인 우선 회복, 화면 확인 필요.
- 이 정책으로 정한 것: 결과가 올 경로가 없는 상태(격리, 결과 마감 뒤 TRANSMISSION_UNKNOWN, FILE_REJECTED 보장 불충족)는 UNRESOLVED.
- 알림 기한은 Demo 값으로 기록한다(운영값은 LIVE Gate). PENDING 영업일 2일 알림·5일 대사 전환 표시, INCONSISTENT·UNREPRESENTABLE 즉시 알림·1일 대사 전환, UNRESOLVED·의심·이상 거래 1일 안 대사 착수, SEALED 체류는 다음 마감까지, 격리 CANCEL 파일은 즉시, 결과 마감 설정 없음은 기동 경보, 누락 의심 탐지는 매 영업일.
- **0.3 추가**: 이중 매입 의심·이상 거래 등록은 취소 매입 성공 확정 뒤에도 미해소면 같은 기한으로 상향 알림(P-37).

## 7. '별도 정의' 항목 — 0.2b §7(부록 E.5) 그대로(F-1~F-9)

- F-1 과다 정산·지급 방지(의심·이상 거래 포함)
- F-2 환수
- F-3 매입사별 원거래 식별(§9로 Demo 범위 검증, 실제 기관은 §11)
- F-4 응답 규격
- F-5 보정 수단
- F-6 RECON
- F-7 CANCEL 실패
- F-8 사유 목록(§11)
- F-9 직렬화 방식(§10.4)

## 8. 현재 '최고 세대 기준' 동작의 소스·영향 범위(작업 1)

경로 접두 `apps/financial-batch/src/main/kotlin/com/paycore/financial/acquiring/` = `…/`.

| # | 위치 | 최고 세대·단일 레코드 가정 | 호출자 | 영향 |
|---|---|---|---|---|
| S1 | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:51-64`(`:61` `rows.maxBy { it.generation }`) | 원 SALE = 최고 세대 레코드 하나 | `…/application/AcquisitionSealService.kt:237`(`decideCancels`) ← `:180`(봉인 TX) ← `AcquisitionScheduler` 봉인 단계 | 전송 후 취소 판정 전체(M14~M21·M23·M26~M28) |
| S2 | `…/application/required/AcquisitionMembershipStore.kt:23`·`:50-51`(`SaleMembership` KDoc "원승인의 첫 매입 SALE 레코드" — 실제는 최고 세대, 문구도 낡음) | 단일 레코드 Port | S1 | Port 모양 변경 대상 |
| S3 | `…/application/AcquisitionSealService.kt:239`·`:258-259` | 그 레코드 하나의 결과 → `RecordResultRule.toOriginalSaleResult` → `CancelCandidateRule.decide` | 봉인 TX | 판정 입력 |
| S4 | `…/domain/CancelCandidateRule.kt:20-54` | 입력이 `OriginalSaleResult` 하나, 한도 = `originalSale.nettedCancelAmountMinor` | S3 | 판정·한도(유효 세대 상계로 바꿔야 함) |
| S5 | `…/domain/CancelRecordRule.kt:11-16`, `…/domain/RecordResultRule.kt:72-78` | 단일 결과 → 판정 매핑 | S4 | EAS 매핑으로 흡수 |
| S6 | `…/domain/ReacquisitionRule.kt:25-48`(`decide`: 대상 상태·`acceptedInAnyGeneration`·`latestGeneration`) | 대상 레코드 + 표지 유무만, 다른 세대 CONFLICT·UNKNOWN·FILE_REJECTED 보장을 안 봄 | `…/domain/ReacquisitionExecutionRule.kt:46`(`plan`) ← `…/application/ReacquisitionExecutionService.kt:134`(실행)·`:111`(`preview` ← `targets` `:103` ← ADMIN 미리 보기 `AdminAcquiringOperationsController.kt:63`) | 재매입 승인·실행·미리 보기(M15·M28) |
| S7 | `…/domain/ReacquisitionRule.kt:54-64`(`revalidate`) | 같음 | **운영 호출자 없음**(시험 RQ-8만) | 정리 대상(EAS로 대체하면 `decide` 하나로 충분) |
| S8 | `…/application/ReacquisitionExecutionService.kt:174-189`(`itemTargets` — `:183` 상태, `:184` `approvals.latestGeneration`, `:185` `results.acceptedRecordOf`) | 대상 레코드 단위 입력 조립 | S6 | 입력 조립 변경 |
| S9 | `…/adapter/persistence/JpaReacquisitionApprovalStore.kt:39-46`(`latestGeneration` — 레코드·승인 표 MAX) | 최신 세대 | S8 | 유지(대상 = 최고 세대 확인용) |
| S10 | `…/domain/ReacquisitionBadgeRule.kt:11-16` + `…/adapter/query/JooqAcquisitionFileViewReader.kt:204`(SQL 사본) ← `…/application/AcquisitionFileViewService.kt:87` | 레코드 단위 거절·표지·불요·상위 세대 | ADMIN 매입 현황 화면 | 배지(M15·M28에서 CANDIDATE가 잘못 보임) |
| S11 | `…/application/AcquirerResponseService.kt:248-268` | 다른 세대 표지 → CONFLICT, 불요 경보는 **그 레코드 자신**을 가리키는 불요만(`:266`) | 응답 반영 TX | 누락 의심 사후 경보 범위 |
| S12 | `…/application/AcquisitionTransmissionService.kt:167-182`(`withhold`) | 이미 세대를 넘어 표지를 봄 | 전송 점유 TX | 변경 불요(EAS의 '미전송' 분류 입력) |
| S13 | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:66-86`(`cancelledAmounts`·`decidedCancellationIds` — 세대 0 CANCEL) | CANCEL은 언제나 세대 0 | 봉인 TX·재매입 실행 | 변경 불요(§9 — CANCEL은 세대 0 유지) |

## 9. 원거래 식별 방식·표지 세대 기준 CANCEL 가능성 검증(작업 2)

**PayCore가 보내는 CANCEL 레코드**(`…/domain/CancelCandidateRule.kt:86-95`, 파일 계약 v1 21필드 — ACQUIRING-001 §5 `:237`):
- `originalTransactionId` = **원승인 거래 ID**(PayCore 거래 ID — 세대와 무관하게 같은 값)
- `originalProviderTransactionId` = **원승인 기관 거래번호**
- `approvalNumber` = 원승인 승인번호, 승인액 칸 = 원승인액
- `generation = 0`, `reacquisitionOf = null` — CANCEL 레코드 **자신의** 세대이며, 원 SALE의 세대·파일·순번을 가리키지 않는다.
- 즉 CANCEL은 **원승인 거래**를 참조하고 어느 SALE 매입 레코드(세대)를 참조하지 않는다.

**가상 매입사(Simulator) 대조**(`apps/institution-simulator/src/main/kotlin/com/paycore/simulator/acquirer/`):
- 원 SALE 찾기: `application/AcquirerWorkerService.kt:225` `view.acceptedSale(originalTransactionId)` → `adapter/persistence/JpaAcquirerReceiptStore.kt:90-95`(그 매입사에서 SALE이 ACCEPTED된 결정 중 `accepted_transaction_id = 원승인 거래 ID`). 같은 거래의 SALE ACCEPTED는 매입사당 하나다(Simulator `V10__acquirer.sql:58` UNIQUE(acquirer, record_type, accepted_transaction_id)). → **어느 세대 파일로 수락되었는지와 무관하게** 찾는다.
- 대조(`domain/RecordJudgementRule.kt` `judgeCancel`):
  - 원 SALE 미수락이면 ORIGINAL_NOT_ACQUIRED.
  - 원 기관 거래번호 ≠ 수락 SALE의 기관 거래번호이면 CANCELLATION_MISMATCH.
  - VAN 취소 원장 대조.
  - 소비 한도: 수락 SALE의 상계액 + 수락 CANCEL 합 ≤ VAN 취소 합. 수락 SALE이 g1이면 그 상계액(실행 전 판정 끝난 취소)이 들어가므로 FINANCIAL 한도(유효 세대 상계)와 같은 기준이다.
- 이중 매입 방어: 같은 거래 ID·같은 VAN 승인 행의 두 번째 SALE은 DUPLICATE_RECORD(`RecordJudgementRule` judgeSale). 그래서 가상 매입사에서는 '유효 성공 2건 이상'이 나오지 않는다. g0가 수락된 뒤 g1이 오면 g1은 REJECTED(DUPLICATE_RECORD)이고 M18·M17 경로가 된다.

**결론**:
- (1) **가상 매입사 계약(v1)에서는 표지 세대 g ≥ 1 기준 CANCEL 전송이 가능하다.** CANCEL이 원승인 거래 ID·기관 거래번호로 참조하므로 세대와 무관하고, 지금처럼 CANCEL을 세대 0 레코드로 만들어도 된다. 코드 변경은 판정(EAS)과 한도의 상계 기준 세대뿐이다.
- (2) 검증은 코드를 읽어서만 했다. **g1 수락 뒤 CANCEL의 Simulator 판정 시험은 없다**(AS-10은 세대 0 SALE만 — `AcquirerSftpWorkerSpec.kt:364`. FINANCIAL RR-1은 결과를 시험이 주입). 그래서 신규 시험 ARC-23·24를 둔다.
- (3) **실제 매입사**가 CANCEL에 매입 접수번호·매입 파일/순번 같은 **매입 레코드 참조**를 요구하면 파일 계약 v1로는 표현할 수 없다. 이는 매입사별 규격(LIVE Gate)이며 미확정 정책(§11 U-3)이다. LIVE 매입은 지금 차단되어 있다(D-ACQ-008 — `CancelCandidateRule.kt:22`).

## 10. 변경 설계(검토용 — 구현하지 않음)

### 10.1 공통 판정 `EffectiveAcquisitionRule`

- **위치**: `…/domain/EffectiveAcquisitionRule.kt`(object, 순수 규칙). 결과 타입은 `…/domain/EffectiveAcquisition.kt`, 입력 타입은 `…/domain/AcquisitionHistory.kt`(ARCHITECTURE-CONVENTIONS §14 — 주요 타입당 파일 하나). 새 모듈·패키지는 없다.
- **입력** `AcquisitionHistory`:
  - `transactionId`
  - `records: List<GenerationRecord>`
  - `acceptedMark: RecordRef?`
  - `GenerationRecord`의 필드: `fileId`, `record: AcquisitionRecord`, `entries: List<RecordResultEntry>`, `transmissionState`, `receiptState`, `withheldPriorState?`, `confirmedNotAcquired: Boolean`(RECON 뒤 — 지금은 언제나 false)
- **출력** `EffectiveAcquisition`:
  - `state`: `ACQUIRED`·`ACQUIRED_CONTESTED`·`MULTIPLE_ACCEPTED`·`PENDING`·`NOT_ACQUIRED`·`UNRESOLVED`
  - `effective: GenerationRecord?`(유효 세대 — 한도·CANCEL 기준)
  - `latest: GenerationRecord`(재매입 대상 확인용)
  - `registrations: Set<AcquisitionAnomalyKind>`(DOUBLE_ACQUISITION_SUSPECTED·MULTIPLE_ACCEPTED)
  - `unresolvedReason?`(MARK_UNCERTAIN·CONFLICT·UNKNOWN·NO_RESULT_PATH·FILE_REJECTION_UNGUARANTEED·INTEGRITY)
- **규칙 본문**: §3.2 표 그대로. `RecordResultRule.status`를 재사용하고 결과 행 판정을 새로 만들지 않는다.

### 10.2 바뀌는 클래스

| 클래스 | 변경 |
|---|---|
| `CancelCandidateRule` | **기존 `decide(candidate, originalResult: OriginalSaleResult, cutoff)` 오버로드는 그대로 둔다**(CC-1~CC-8 호출부 불변). 새 오버로드 `decide(candidate, eas: EffectiveAcquisition, cutoff)`가 EAS → `OriginalSaleResult`로 바꿔 기존 것을 부른다. 한도는 `eas.effective.record.nettedCancelAmountMinor`. **기존 판정 타입 유지**: `CancelDecision.Reconcile(candidate, OriginalSaleResult)`은 어댑터(UNRESOLVED → 원인이 UNKNOWN이면 UNKNOWN, 그 밖 CONFLICT, PENDING → AWAITING 대기 사유 `ORIGINAL_AWAITING_RESULT` 그대로)로 유지한다 — CC-2·CC-8·RX-7 기대 리터럴 불변. `CancelCandidate.originalSale`은 `eas.effective`(없으면 최고 세대)로 채운다 |
| `CancelRecordRule` | EAS → `CancelRecordDecision` 매핑으로 바꾸거나 `CancelCandidateRule` 안으로 흡수한다(파일 하나 줄이는 쪽 — 구현 때 선택) |
| `ReacquisitionRule` | `ReacquisitionTarget`에 `eas: EffectiveAcquisition`과 `reasonClass: ReacquisitionReasonClass`를 더한다. 판정 순서: 기존 채널·사람 → **EAS ≠ NOT_ACQUIRED면 새 거절 코드**(예: `ORIGINAL_NOT_SETTLED` — 이름 가역). 단 기존 경우는 기존 코드 유지: 표지 있음 → `ALREADY_ACCEPTED`, 대상 상태 UNKNOWN → `UNKNOWN_NOT_RECONCILED`, 대상 AWAITING·CONFLICT → `TARGET_NOT_REACQUIRABLE`. 그다음 `NOT_LATEST_GENERATION`·`TARGET_CHANGED`(기존) → **사유 NOT_REACQUIRABLE → 새 거절 코드**(예: `REASON_NOT_REACQUIRABLE`). `revalidate`는 운영 호출자가 없어 삭제 후보(RQ-8은 `decide` 재검사로 같은 단언 — 시험 정리는 기대값 불변 범위에서만, 아니면 남김) |
| `ReacquisitionExecutionRule` | 변경 없음(항목마다 `ReacquisitionRule.decide`) |
| `ReacquisitionBadgeRule` | `recordStage(eas, notRequired, higherGeneration)` — CANDIDATE = EAS NOT_ACQUIRED ∧ 대상이 최고 세대 ∧ 사유 ≠ NOT_REACQUIRABLE |
| 새 `ReacquisitionReasonClass`(enum: REACQUIRABLE·NOT_REACQUIRABLE·UNCLASSIFIED) + `ReacquisitionReasonCatalog`(required Port: `classify(acquirerCode, source, reasonCode)`) | 구현 하나: 설정 기반(`paycore.financial.acquiring.reacquisition.reasons.<acquirer>` — 비어 있으면 모든 코드 UNCLASSIFIED). **DB 표 없음**(과도한 설계 지양). 매입사별 확장은 설정 항목만 더한다. 자동 재매입 경로는 만들지 않는다(REACQUIRABLE도 2인 승인 수동). **후속 명세 요구(A-6)**: 분류 목록을 채우기 전에 ① 분류 정책 버전 ID(매입사별, 적용 시작 시각 — 소급 금지)를 두고 ② 재매입 결정 행에 적용한 분류와 정책 버전을 스냅샷으로 남긴다. ②는 결정 표 열 추가 migration이 필요하다. 목록이 빈 동안(모두 UNCLASSIFIED)은 '분류 없음'이 결정에 영향을 주지 않으므로 지금은 만들지 않는다 |

### 10.3 바뀌는 Port·쿼리

| Port / 구현 | 변경 |
|---|---|
| `AcquisitionMembershipStore.saleMemberships` → `saleHistories(originalTransactionIds): Map<String, AcquisitionHistory>` | 원승인별 SALE 레코드 **전부** + 결과 행 + 파일 전송·수신 상태 + 보류 직전 상태 + 표지를 한 번에(거래 ID 묶음 chunk, 기존 `CHUNK`). JPA 대신 native 조회 3개(레코드·파일 join, 결과 행, 표지·보류)로 N+1을 피한다 |
| `ReacquisitionExecutionService.itemTargets` | 대상 레코드의 거래 ID로 `saleHistories`를 같이 읽어 `ReacquisitionTarget.eas`를 채운다. `results.acceptedRecordOf`(`:185`) 대신 이력의 표지를 쓴다. `approvals.latestGeneration`은 승인만 있는 세대(실행 전)를 세기 위해 유지 |
| `AcquirerResponseService` 불요 경보(`:266`) | `notRequiredCancellationsOf(ref)`를 "그 원승인의 모든 '불요'"로 넓힌다 + 등록(§10.5) 호출 |
| `JooqAcquisitionFileViewReader` 배지 SQL(`:204`) | EAS 정의에 맞춘 SQL 사본(다른 세대 CONFLICT·FILE_REJECTED 수신 충돌 제외 조건 추가) + 기존 대조 시험 유지 |
| 새 읽기 전용 `CancelMissingSuspicionReader`(jOOQ — CQRS 조회 관례) | ARC-17 SQL 두 개. ADMIN 화면·정기 탐지 입력 |

### 10.4 직렬화(F-9 — 결정 필요 아님, 구현 명세에서 확정)

- 0.2a §3.6의 선택지 ①(응답 TX가 결제 행까지 잠금)과 ②(EAS를 읽는 TX가 원승인 모든 세대 파일 행을 file_id 순으로 잠금)를 유지한다.
- **설계 권장: ①**. 이유:
  - 봉인·재매입 실행이 이미 결제 행을 잠근다(`AcquisitionSealService.kt:157`, `ReacquisitionExecutionService.kt:130`).
  - 응답 TX(`AcquirerResponseService.kt:137` 파일 행) 뒤에 결제 행만 더하면 공통 지점이 된다. 응답이 결제 행 뒤에 다른 잠금을 잡지 않아 순환이 없다.
  - ②는 실행이 대상 파일을 먼저 잡아(`:126`) 보류 판정의 오름차순(I-28)과 순서가 섞인다.
- **응답 TX 잠금 방식(A-3)**: 파일 행 잠금(`:137`) 직후, 그 응답 파일 레코드들의 원승인 결제 행 **전부**를 기존 `lockAndRead`(봉인이 쓰는 같은 Port — `AcquisitionSealService.kt:157`)로 **한 번에 payment_id 오름차순** 잠근다. 결과 줄마다 잠그지 않는다(줄별 잠금은 순서가 섞여 봉인 TX와 교착할 수 있다). 묶음이 커서 chunk로 나누면 chunk 사이에도 오름차순을 유지한다.
- **결제 행 잠금 밖에서 바뀌는 값이 종결 판정을 뒤집지 않는 이유**: 종결 판정('불요' 기록, CANCEL 포함)을 만드는 입력은 결과 행의 확정 결과·CONFLICT와 표지뿐이고, 이것은 응답 TX만 쓴다(이제 결제 행 잠금 아래). 잠금 밖에서 바뀌는 `transmission_state`(점유·전송·보류), 보류 `prior_state`, 결과 마감 UNKNOWN 행(`AcquisitionResultCloseService`)은 레코드를 '결과 대기 ↔ 미결 ↔ 미전송' 사이에서만 옮긴다. 그래서 판정은 대기·대사 보류(비종결, 다음 회차에 다시 판정)나 의심 등록 여부(M16a↔M16b — 둘 다 전송)만 바뀐다. 실패 확정·유효 성공을 새로 만들지 않는다. 등록을 놓친 경우는 다음 봉인·응답 회차의 재계산과 ARC-17 탐지가 잡는다. 변이 시험: 봉인 TX와 동시에 g1 전송 점유·보류·결과 마감을 커밋해도 불요·CANCEL 결정이 바뀌지 않음(ARC-36), 응답 TX의 결제 행 잠금 제거 → ARC-21 RED.
- **처리량 영향**: 응답 반영 1회가 그 파일의 결제 행 수(파일 레코드 수 이하)만큼 잠금을 잡는다. 같은 lane 정기 봉인 TX(lane → 결제 행)와 겹치는 결제는 직렬화된다. 응답 반영이 봉인 TX 길이만큼 기다릴 수 있고 반대도 같다. 응답 TX가 짧은지(결과 행·표지 INSERT만)와 봉인 회차 주기를 구현 명세에서 측정 기준(대기 시간 지표)으로 둔다. 교착이 생기면 기존 재시도 분류(`AcquiringPersistenceFailure.Deadlock`)로 회차 재시도한다.
- 잠금 뒤 EAS를 다시 계산한다.
- 응답 TX가 PAYMENT의 결제 사실 투영 행을 FINANCIAL 안에서 잠그는 것이므로 소유 DB는 FINANCIAL 하나다(cross-DB 아님).
- 시험은 ARC-21·22(실제 두 연결)와 잠금 제거 변이 RED로 고정한다. 백업 장치는 ARC-17 사후 탐지다.

### 10.5 migration 필요 여부

- **필요: 1개 + 권한 1개**(다음 번호 V21·V22 — 현재 최고 V20).
- `acquisition_anomaly`(append-only): `id`, `original_transaction_id`, `kind`(DOUBLE_ACQUISITION_SUSPECTED·MULTIPLE_ACCEPTED·CANCEL_MISSING_SUSPECTED), `effective_file_id`·`effective_seq`(NULL 가능), `evidence_file_id`·`evidence_seq`(그 원인 레코드), `registered_at`, `source`(SEAL·RESPONSE·DETECTION).
  - UNIQUE(original_transaction_id, kind, evidence_file_id, evidence_seq): 같은 원인은 한 번만 등록(멱등).
  - **멱등 쓰기 방식(A-8)**: 등록은 봉인·응답 TX 안(결제 행 잠금 아래)에서 같은 키를 먼저 조회하고 없을 때만 INSERT하거나, `INSERT IGNORE`(MySQL)로 쓴다. UNIQUE 위반 예외를 잡아 넘기는 방식은 쓰지 않는다. JPA flush 예외가 TX를 rollback-only로 만들어 같은 TX의 업무 효과(CANCEL·결과 행)까지 잃는다.
  - 해소는 별도 append-only 표 또는 RECON 표로 넘긴다(RECON 명세 — 지금은 등록만).
- 권한: runtime SELECT·INSERT만(append-only 1142 목록에 추가 — V20 선례).
- 영향 시험: `FinancialDatabaseAccountSpec`·`MainMigrationLocationSpec`의 migration 번호·표 목록 기대에 **추가**가 필요하다(V19·V20 때와 같은 "추가만" — D-FFR-002). 이것은 기존 기대값 변경이므로 **사용자 보고 대상**으로 표시한다(§13).
- 재매입 사유 분류는 설정이라 migration이 없다. 결정 행의 사유 스냅샷도 결과 행이 append-only라 필요 없다.

### 10.6 8대 원칙 증거(검토용 — 구현 뒤 실제 증거로 채움)

| 원칙 | 이 설계의 장치 | 증거(시험·기록) |
|---|---|---|
| Financial Consistency | 유효 성공 세대 기준 CANCEL과 한도(유효 세대 상계 + 봉인 CANCEL + 이번 ≤ 원승인), 불요는 모든 이력 실패 확정만 | ARC-01·02·06·10·25·26, CC-4·CC-8(불변), I-17 대조 |
| Transaction Safety | 판정·CANCEL·불요·anomaly 등록이 봉인 로컬 TX 하나. 결과 행·표지·anomaly가 응답 TX 하나. 외부 전송은 TX 밖(기존) | ARC-12·21, 기존 AQ-T·RX-5a rollback 시험 |
| Idempotency | 같은 취소 = 상계·CANCEL·불요 중 하나(잠금 아래 재확인 + PK·UNIQUE), anomaly 원인당 UNIQUE + 조회 후 INSERT/INSERT IGNORE, 재매입 승인 ID UNIQUE·내용 지문 | ARC-14·15·16·26(등록 1건), RX-6·RX-11 |
| Concurrency Control | 응답 TX 결제 행 오름차순 일괄 잠금 → 봉인·재매입 실행과 원승인 단위 직렬화(§10.4), 전송 점유 보류 잠금(I-28) | ARC-21·22·34·36, RR-4, 잠금 제거 변이 RED |
| Recoverability | 대기·대사 보류는 기록 없이 다음 회차 재판정, 늦은 결과 자동 반영, TRANSMISSION_UNKNOWN 확인 우선 회복, 교착은 회차 재시도 | ARC-27, 기존 AQ-T 회복 시험 |
| Reconciliation | UNRESOLVED·이중 매입 의심·이상 거래·누락 의심을 append-only 등록해 대사 입력으로, 자동 보정 없음(2인 승인 Adjustment), MATCH 전 해결 금지 | ARC-17(탐지)·28·30·31, F-5·F-6(후속) |
| Auditability | 결과 행·표지·보류 근거·불요·anomaly·재매입 결정 행(요청자·승인자·기한·거절 코드) 모두 append-only, (후속) 사유 분류 정책 버전 스냅샷 | 기존 FinancialDatabaseAccountSpec append-only 1142 + V21 추가 |
| Observability | 대사 경보·의심/이상 등록 지표, 대기 체류·INCONSISTENT·격리 알림(§6 Demo 값), 응답 TX 결제 행 잠금 대기 시간 지표 | ARC-20, 지표 delta 시험(구현 때) |

### 10.7 시험 계획

- 순서:
  1. Domain `EffectiveAcquisitionRuleSpec`(매트릭스 31행 표 시험 — ARC-01)
  2. `CancelCandidateRuleSpec`·`ReacquisitionRuleSpec` 신규 Given(기존 Given 기대값 불변)
  3. DB(`AcquisitionSealDbSpec`·`ReacquisitionExecutionDbSpec`·`ReacquisitionResultDbSpec` 신규 Given)
  4. 동시성(ARC-21·22)
  5. Simulator 통합(ARC-23·24)
  6. Reader(배지·누락 의심)
  7. ADMIN HTTP(새 거절 코드 표시 — 추가만)
- RED 먼저: EAS 규칙 stub → 행별 RED. 변이: 최고 세대 선택 되돌리기, 직렬화 잠금 제거, 사유 분류 무시 → 각 RED.
- 게이트: FINANCIAL GRADLE-CHECK + Simulator + ADMIN(배지·거절 코드) SCOPED, 마지막 FULL(D-PROC-007).

## 11. 미확정 정책(임의 정의 금지 — P-49)

| ID | 항목 | 지금 다루는 방식 |
|---|---|---|
| U-1 | 매입사별 응답코드 목록과 재매입 가능 여부 분류 | 설정 비움 = 모든 코드 UNCLASSIFIED → 수동 2인만(P-41) |
| U-2 | 매입사별 미매입 확정 기준(대사 파일·공식 결과 형식, UNKNOWN 해소 근거) | 없음 → UNKNOWN 재매입 불가(현행 UNKNOWN_NOT_RECONCILED) |
| U-3 | 매입사별 취소 매입 원거래 식별(매입 레코드 참조 요구 여부) | 파일 계약 v1(원승인 거래 ID·기관 거래번호)로 가상 매입사만. LIVE 차단 |
| U-4 | 실제 매입사의 FILE_REJECTED 의미(레코드 처리 없음 보장 여부) | 가상 매입사 규격만(S-3) |
| U-5 | 결과 회신 기한·늦은 결과 수용 기간·결과 재발행 규칙 | Demo `results.deadline` |
| U-6 | 알림 기한 운영값 | Demo 값(§6) |

## 12. 기존 규칙 정합표(대장 변경안 — 적용은 Coordinator)

0.2b §8(부록 E.6)을 다음과 같이 갱신한다(바뀐 행만 적는다. 나머지는 0.2b 그대로 — 016·036·037·046·047·051·052·053·138 유지).

| 행 | 관계 | 대장 변경안 문장(제안) |
|---|---|---|
| BR-FIN-038 | 보완 — **[해석] 표시** | 덧붙임: "격리된 두 번째 ACCEPTED가 그 레코드의 앞선 확정 결과 없이 들어온 것이면 유효 성공 2건 이상으로 이상 거래로 등록하고, REJECTED 뒤 상충이면 이중 매입 의심으로 등록한다(D-GOV-017 ①; 두 경우를 가르는 기준은 Coordinator 해석)." — R-1 미결 표기 제거 |
| BR-FIN-039 | 보완 | "전송 후 취소의 CANCEL은 원승인 EAS의 유효 성공 세대 기준으로 보낸다: 유효 성공 1건이면(다른 세대가 미결·상충이어도) 전송, 유효 성공 세대를 확정할 수 없으면 보류(대기·대사), 모든 이력이 실패로 확정될 때만 '취소 매입 불요'." |
| BR-FIN-040 | **대체** | "재매입 여부와 관계없이 전송 후 취소는 원승인의 전체 매입 이력에서 매입사가 정상 수신한 유효 성공 세대(매입 확정 표지 세대)를 기준으로 판정한다. 유효 성공 세대가 하나로 식별되면 다른 세대가 미결·UNKNOWN·CONFLICT여도 그 세대 기준으로 CANCEL을 보내고 이중 매입 의심으로 등록하며, 유효 성공이 2건 이상이면 이상 거래로 등록·별도 처리하고, 표지 세대 자체의 유효성이 불확실하거나 성공 세대가 없이 미결이면 보류한다(D-GOV-012 ①②, D-GOV-017 ①)." 상태 `확정`, 근거 D-GOV-012·D-GOV-017, 검증 = 신규 ARC. (유효 성공 2건 이상의 일반 취소 전송 여부는 아래 별도 미결 행) |
| (신규 — 미결) BR-FIN-040a | **미결(Q-ARC-S1 — §15 S-1)** | "유효 성공이 2건 이상이면 [고객 취소의 일반 취소 매입을 표지 세대 기준으로 보낸다 / 이상 거래 처리 완료까지 보류한다]." 상태 `미결`, 검증 없음(RECON 전 자연 경로 없음) |
| BR-FIN-041 | 보완 — 해석 표시 | "… 원 SALE 상계액은 유효 성공 세대 레코드의 상계액이다(Coordinator 해석 — '그 매입 기준')." |
| BR-FIN-048 | 보완 | "UNKNOWN은 실패가 아니며, 매입사 대사·공식 결과 확인으로 미매입이 확정된 경우에만 실패 확정으로 전환한다. 대사 데이터 미존재·응답 지연은 근거가 아니다(D-GOV-017 ②)." |
| BR-FIN-049 | 대체 | "재매입은 기존 매입 미성립 확정(모든 세대 실패 확정, FILE_REJECTED는 파일 전체 미매입 보장 시만, UNKNOWN은 공식 미매입 확정 시만)과 재매입 가능 실패 사유를 모두 만족할 때만 한다. 유효 성공 세대나 미해소 미결·상충 세대가 있으면 차단한다. 사유 목록 확정 전에는 자동 재매입 없이 사유 확인 + 2인 승인으로만 수동 재매입한다(D-GOV-017 ②)." 상태 `확정`, 근거 D-GOV-017 ② |
| (신규 — 제안) BR-FIN-049a | **제안**(출처 `적용` — ACQUIRING-001 I-50 Implementer 해석) | "재매입 대상은 그 거래·종류의 최고 세대 레코드이며 그보다 낮은 세대는 대상이 아니다." 상태 `제안`(사용자 확인 대상 — 일괄 확인 후보), 검증 ReacquisitionRuleSpec::RQ-5 |
| BR-FIN-050 | 보완 | "… 원승인 EAS에 유효 성공 세대가 있을 때만 …" |
| (신규) | P-10·P-32·P-35·P-37 | "이중 매입 의심·이상 거래는 원승인 단위로 append-only 등록·대사하며 취소 전송 판단과 분리하고, 취소 매입 성공 뒤에도 해소될 때까지 관리한다(D-GOV-012 ②, D-GOV-017 ①)." |
| (신규) | P-40·P-41·P-49 | "재매입 가능 여부는 매입사별 응답코드 분류로 관리하며, 분류 목록은 사용자·기관 확인 전 임의로 정하지 않고 그 전에는 모든 코드를 미분류(수동 2인 승인만)로 본다(D-GOV-017 ②)." |
| (신규, 0.2b 유지) | P-18~P-21, P-25 | 부록 E.6 문장 그대로(누락 의심 정의는 §3.4로) |

## 13. 기존 시험 정합표(기대값 불변)

0.2b §9에서 바뀐 판정만 적는다.

| 시험 | 0.2b | 0.3 | 근거 |
|---|---|---|---|
| CC-2(`CancelCandidateRuleSpec.kt:36-37`)·CC-8(`:90`) | 충돌 후보(조건부) | **어댑터 조건으로 유효**(§10.2 — `Reconcile(…, OriginalSaleResult)` 유지). 어댑터를 두지 않으면 충돌 → 사용자 보고 | |
| RX-7(`ReacquisitionExecutionDbSpec.kt:265`) | 불변 | 불변(PENDING → `ORIGINAL_AWAITING_RESULT`) | |
| RR-1~RR-4, RX-1·RX-2·RX-3·RX-13, AcquisitionFileViewReaderSpec, AcquiringOperationsDbSpec, EX-1, RQ-1(`AMOUNT_MISMATCH` Fixture) | R-2 조건부 충돌 후보 | **유효**. 이 시험들의 재매입은 모두 2인 승인 명령(수동)이고(§3.5 ② 근거), Q-ARC-5는 목록 전 수동 2인 재매입을 허용한다. 사유 분류 설정이 비어 있으면 `AMOUNT_MISMATCH`는 UNCLASSIFIED → 수동 허용 | D-GOV-017 ② |
| RQ-1·RQ-4·RQ-5·RQ-7·RQ-8, EX-*, ReacquisitionBadgeRuleSpec | 입력 형태 변경 | 입력 형태 변경(`ReacquisitionTarget`에 EAS·사유 분류) + **기존 거절 코드 유지**(§10.2). RQ-8은 `revalidate`를 지우면 시험 대상이 사라지므로 **지우지 않거나** `decide` 재검사로 같은 단언을 옮긴다 — 옮기면 시험 변경이라 사용자 보고 대상. 권장: `revalidate` 유지 | |
| RQ-2(FILE_REJECTED 재매입 가능, UNKNOWN 거절) | 유효 | 유효 — FILE_REJECTED Fixture가 §3.5 보장 조건(수신 충돌 없음·ACCEPTED 없음)을 만족(도메인 Fixture에 수신 상태가 없으면 기본값 = 보장) | S-3 |
| AQ-R14(`AcquirerResponseDbSpec`) | 유효 | 유효(결과 단언만 — M26 이상 거래 등록은 신규 시험) | |
| `FinancialDatabaseAccountSpec`·`MainMigrationLocationSpec` | — | **migration 추가 시 기대 목록에 추가 필요**(§10.5 — 추가만, V19·V20 선례). 기대값 변경이므로 **사용자 보고 대상** | D-FFR-002 |

**결론**:
- 확정 충돌 0건 — **S-3 권장안 기준**이다(S-3 대안 (b)면 RQ-2·RX 계열 FILE_REJECTED 재매입 기대가 충돌). **`CancelCandidateRule.decide(candidate, OriginalSaleResult, cutoff)` 기존 오버로드를 유지**한다는 전제다. 새 EAS 입력 오버로드는 이 기존 시그니처를 감싼다. 그래서 CC-2·CC-8 호출부와 기대 리터럴이 바뀌지 않는다.
- 사용자 보고 대상 3건:
  1. 어댑터를 두지 않는 구현(CC-2·CC-8)
  2. RQ-8을 옮기는 경우
  3. migration 추가에 따른 DB 계정·migration 목록 시험의 추가 기대값(권장 설계에서 발생)

## 14. 신규 시험 시나리오(ARC-*)

0.2b ARC-01~ARC-22를 유지하고, D-GOV-017로 기대값이 정해진 것과 새 시험을 적는다. **'표지 g0 성공 + g1 미결'** 조합은 ARC-25~ARC-28이다.

| ID | 계층 | Given | When | Then | 매트릭스 |
|---|---|---|---|---|---|
| ARC-01 | Domain | 31행 이력 조합(명시 Fixture) | `EffectiveAcquisitionRule` | 행마다 EAS·유효 세대·등록 종류 | 전 행 |
| ARC-02 | Domain | 같은 조합 | 취소 판정·재매입 판정·배지 | 세 곳이 같은 EAS를 쓰고 매트릭스와 같다(P-27) | 전 행 |
| ARC-08 | DB | M13·M17·M19a·M20·M21 | 취소 봉인 | **표지 세대 기준 CANCEL 1건 + 이중 매입 의심 등록 1건**(같은 원인 재봉인에도 등록 1건 — UNIQUE) | CONTESTED |
| ARC-23 | Simulator 통합 | g0 REJECTED → g1(재매입) Simulator ACCEPTED | 전송 후 취소 2,000 봉인·전송 | Simulator CANCEL ACCEPTED(원승인 거래 ID로 g1 수락 SALE을 찾음), 소비 = g1 상계 + 2,000 ≤ VAN 취소 합 | M09, §9 |
| ARC-24 | Simulator 통합 | (명시 상태 — RECON 전 자연 경로 없음, 순서 고정) ① g0 UNKNOWN ② g1 재매입 봉인 + **g1 전송 점유 커밋**(TRANSMITTING — 표지 없음이라 보류 안 됨) ③ 그 뒤 g0 Simulator ACCEPTED 결과 반영(표지 g0) ④ g1 업로드 완료 | g1 판정 | g1 REJECTED(DUPLICATE_RECORD) → FINANCIAL M18 → 취소 CANCEL 전송 → Simulator ACCEPTED | M18, §9 |
| ARC-25 | DB | **g0 표지 + g1 전송됨·결과 대기** | 전체취소(남은 전액) 봉인 | CANCEL = 원승인 − g0 상계(경계), 의심 등록 1, 1원 초과 INCONSISTENT | M17 |
| ARC-26 | DB | **g0 표지 + g1 결과 대기** | 부분취소 3건(반복) 세 회차 | 회차마다 CANCEL, 누적 한도 g0 기준, 의심 등록은 1건(멱등) | M17 반복 부분 |
| ARC-27 | DB | **g0 표지 + g1 UNKNOWN** | 취소 봉인 → g1 늦은 REJECTED | 봉인 때 CANCEL + 의심. 늦은 REJECTED 뒤 EAS = ACQUIRED(0), 의심 등록은 대사 해소 전까지 유지(P-37) | M20 → M18 |
| ARC-28 | DB | (명시 상태 — RECON 뒤 경로) **g0 표지 + g1 결과 대기**, CANCEL 전송·ACCEPTED(성공 확정) | g1 ACCEPTED(두 번째 성공) 수신 | EAS MULTIPLE_ACCEPTED, 이상 거래 등록 1, 이미 성공 확정된 CANCEL은 그대로(이중 CANCEL 없음), 의심 등록 유지 | M17 → M19b |
| ARC-29 | DB | M26(AQ-R14 경로) | 취소 봉인 | S-1 답대로(권장: CANCEL 1 + 이상 거래 1) | M26 |
| ARC-30 | DB | M05(표지 레코드 상충) / M27(표지 파일 수신 충돌 — S-2) | 취소 봉인 | 대사 보류, 전송 0 | M05·M27 |
| ARC-31 | DB | M28(FILE_REJECTED + 수신 충돌) | 취소 봉인 / 재매입 실행 | 대사 보류 / 새 거절 코드, 봉인 0 | M28 |
| ARC-32 | Domain/DB | 사유 분류 설정 비움 / `X`=NOT_REACQUIRABLE 설정 | `X`로 거절된 레코드 2인 승인 재매입 | 비움: 실행(수동) / NOT_REACQUIRABLE: 거절 코드 | P-40·P-41 |
| ARC-33 | Domain | g0 UNKNOWN, 대사 확인 없음 / `confirmedNotAcquired = true` Fixture | 재매입 판정 | 거절 UNKNOWN_NOT_RECONCILED / (RECON 뒤) 허용 대상 | M30·M31 |
| ARC-34 | DB 동시성 | 같은 대상 두 승인을 실제 두 연결로 동시 실행 | 동시 | 새 세대 1개, 다른 쪽 NOT_LATEST_GENERATION(기존 RX-6과 같은 단언 + EAS 경로) | P-46 |
| ARC-35 | Reader | M15·M28 | 배지 | CANDIDATE 아님(SQL ↔ Domain 대조) | 배지 |
| ARC-36 | DB 동시성(실제 두 연결) | g0 REJECTED + g1 결과 대기, 전송 후 취소 1건 | 봉인 TX와 동시에 g1 전송 점유 / 보류 / 결과 마감 UNKNOWN 커밋(결제 행 잠금 밖 변화) | 불요 0·CANCEL 0(대기 또는 대사 보류 — 비종결)이고 종결 결정이 바뀌지 않음(§10.4 A-3) | M08·M11·M23 |

ARC-03~07·09~22(0.2b)는 EAS 이름만 이 표대로 읽는다. ARC-07(M18)은 "CANCEL 전송, 의심 등록 0"이고, ARC-08은 위로 대체한다.

## 15. 남은 결정 필요(금융 의미, 3개)

**S-1 유효 성공 2건 이상(이상 거래)일 때 고객 취소의 일반 취소 매입(M19b·M26)**
- 배경: D-GOV-017 판단표는 "이상 거래 등록 및 별도 처리"이고, D-GOV-012 ②는 "복수 성공 확인 시 일반 취소와 중복 매입 보정 구분"이다. 일반 취소를 지금 보낼지는 적혀 있지 않다. 코드상 '유효 성공 2건 이상' = 표지가 있는 원승인의 다른 세대 레코드에 앞선 확정 결과 없이 ACCEPTED가 들어와 CONFLICT(관찰 ACCEPTED)가 된 상태다([해석] — §3.2).
- 권장안: **표지 세대 기준 일반 취소는 전송하고, 이상 거래를 등록해 중복 매입 보정은 별도로 처리한다.** 근거: 고객 취소는 어느 세대가 매입되었든 매입사에 한 번 반영되어야 하고, CANCEL은 원승인 거래로 참조한다(§9). 일반 취소를 보류하면 확정된 취소 매입이 늦어진다(P-35 분리 원칙).
- 대안: (b) 이상 거래 처리(대사·보정)가 끝날 때까지 취소도 보류 (c) **RECON 착수 때까지 결정을 보류**(그 전에는 이 상태가 자연히 생기지 않으므로 현행 동작 유지).
- 영향: 권장안은 매입사가 두 건을 매입했다면 일반 취소 한 건 뒤에도 중복 매입분 보정이 따로 필요하다(F-5). (b)는 고객 취소 금액만큼 매입사 입금·가맹점 지급이 대사 완료까지 많다(F-1).
- 막히는 작업: M19b·M26 기대값, ARC-28·29, BR-FIN-040a. **현재 자연 경로 없음**(세대 ≥ 1은 앞 세대 종결 거절 뒤에만 생김 — RECON의 UNKNOWN 재매입 개방 뒤 도달), **RECON 전에는 막히는 운영 동작이 없다**.

**S-2 '표지 세대 자체의 유효성이 불확실'의 범위(M05·M27)**
- 배경: 표지 레코드 자신의 상충 결과(M05)는 분명히 불확실이다. 표지가 생긴 뒤 그 **파일**이 수신 충돌(모순된 ACK — RECEIPT_CONFLICT)이나 전송 격리가 되는 경우(M27)를 포함할지가 남는다. 표지 레코드의 결과 행은 그대로이고, 파일 단위 근거만 흔들린다.
- 권장안: **포함(M27 보류)** — 파일 수신 근거가 모순되면 그 파일의 ACCEPTED를 "매입사가 정상 수신"한 것으로 볼 수 없다(D-GOV-017 ① "정상 수신한 유효 성공 세대").
- 대안: (b) 레코드 상충(M05)만 불확실로 보고 파일 축 충돌은 이중 매입 의심만 등록(전송).
- 영향: 권장안은 파일 축 충돌 건의 취소가 대사까지 늦어진다. (b)는 매입사가 그 파일을 실제로 처리하지 않았다면 매입되지 않은 거래에 CANCEL을 보낸다(매입사 거절 → F-7).
- 막히는 작업: M27 기대값, ARC-30, `EffectiveAcquisitionRule` 입력에 파일 수신 상태를 넣을지.

**S-3 Demo/TEST에서 FILE_REJECTED '파일 내 전체 미매입 보장' 기준(M28·M29, P-42)**
- 배경: Q-ARC-5는 "FILE_REJECTED도 파일 내 거래 전체 미매입이 보장될 때만"이고 "매입사별 기준은 임의 정의 금지"다. 가상 매입사 규격은 FILE_REJECTED를 "레코드 판정 없음"으로 정의한다(`AcquirerScenarioAction.kt:25`).
- 권장안: **가상 매입사(Demo/TEST)는 그 규격 + PayCore 쪽 확인 조건(ACK 이름·hash 일치, 수신 충돌 없음, 그 파일에 ACCEPTED·CONFLICT 결과 없음)을 보장으로 본다.** 실제 매입사는 규격 확인 전까지 FILE_REJECTED를 미성립 확정으로 보지 않는다(LIVE는 이미 차단 — U-4).
- 대안: (b) 가상 매입사도 대사 확인 전까지 FILE_REJECTED를 미성립 확정으로 보지 않음(FILE_REJECTED 재매입·불요 중단) (c) PayCore 쪽 조건 없이 규격만으로 보장.
- 영향: 권장안은 현행 FILE_REJECTED 재매입(RQ-2·RX 계열)을 유지하고 M28만 막는다. (b)는 Demo의 파일 전체 거절 재매입 시연이 막히고 RQ-2 기대가 충돌한다. (c)는 모순 ACK 건(M28)을 놓친다.
- 막히는 작업: M28·M29, ARC-31, BR-FIN-049 문장의 FILE_REJECTED 조건.

## 16. 이번에 하지 않은 것

- 소스·시험·migration·대장·DECISIONS·harness 미변경, 커밋 없음, 기대값 미변경.
- 시험·탐지 SQL·Simulator 판정을 실행하지 않았다. §8·§9는 코드 읽기, 매트릭스 "현재" 열의 기존 시험 없는 칸은 추론이다.
- §10 변경 설계는 검토용이며 구현하지 않았다. 이름·Port 모양은 가역 세부다.
- §7·§11의 별도 정의·미확정 정책은 정하지 않았다.

---

# 부록 — rev 0.1·0.1a 근거 기록(기록용)

부록 C·D의 '제안' 열은 rev 0.1a 기준이다. 본문과 다른 곳은 본문을 따른다. 0.2b 본문(§3.6 직렬화 사실표·§9 시험표·§10 ARC-03~22·ARC-17 SQL)은 이 판에 요약 참조로만 남겼다. 원문은 아래 부록 E에 있다(E.1 직렬화, E.2 시험 정합, E.3 ARC-03~22·탐지 SQL, E.4 무기한 대기, E.5 별도 정의, E.6 대장 정합).

> 부록 안의 절 번호(§2·§4·§5·§7·§8, #1~#21, ARC-01~21, Q-ARC-1~3)는 rev 0.1a 번호다. 본문 rev 0.3의 매트릭스 행은 M01~M31, 시험은 §14 ARC-*, 질문은 §15 S-1~S-3이다.

## 부록 A. 현재 구현 사실(코드 근거)

경로 접두 `apps/financial-batch/src/main/kotlin/com/paycore/financial/acquiring/` 는 `…/`로, migration `apps/financial-batch/src/main/resources/db/migration/financial/` 은 `V*/`로 줄인다.

### A.1 매입 레코드·세대·결과 상태

| 사실 | 근거 |
|---|---|
| 매입 레코드 = 파일의 한 줄. 종류 SALE·CANCEL. `generation` 0 = 첫 매입, ≥ 1 = 재매입이며 재매입만 `reacquisitionOf = {fileId}#{seq}`를 가진다 | `…/domain/AcquisitionRecord.kt:14-35`, `:75-76`, `:92` |
| CANCEL 레코드는 언제나 generation 0으로 만든다(CANCEL 재매입 없음) | `…/domain/CancelCandidateRule.kt:86-95`(`generation = 0, reacquisitionOf = null`) |
| 레코드 결과는 append-only 결과 행에서 계산한다. 상태 값 = AWAITING·ACCEPTED·REJECTED·FILE_REJECTED·UNKNOWN·CONFLICT. CONFLICT 행이 하나라도 있으면 CONFLICT, 그다음 첫 확정 행(ACCEPTED/REJECTED), 없으면 UNKNOWN 행 여부 | `…/domain/RecordResultRule.kt:18-27`, `:139` |
| 같은 결과 반복 = 중복(행 없음), 확정 뒤 다른 결과 = CONFLICT 행(격리). AWAITING/UNKNOWN에서 결과가 오면 반영(늦은 결과 자동 반영 — D-ACQ-010) | `…/domain/RecordResultRule.kt:35-61` |
| 마감까지 결과 없는 레코드만 UNKNOWN 행. 마감 처리는 **전송 축 TRANSMITTED 파일만** 본다 — 전송 보류·격리 파일 레코드는 AWAITING에 머문다 | `…/domain/RecordResultRule.kt:64-66`, `…/application/AcquisitionResultCloseService.kt:73` |
| **매입 확정 표지**: ACCEPTED 결과 행을 쓰는 같은 TX가 `acquisition_accepted_record`(PK(transaction_id, record_type) — 세대를 넘어 하나)를 INSERT. 다른 세대에 표지가 있으면 들어온 ACCEPTED는 CONFLICT 행 | `V14__acquisition_accepted_record.sql:15-27`, `…/application/AcquirerResponseService.kt:248-253`, `…/domain/RecordResultRule.kt:45-46` |
| 원 SALE이 CONFLICT가 되면 그 레코드를 가리키는 "취소 매입 불요" 기록이 있을 때만 error 로그(대사) | `…/application/AcquirerResponseService.kt:264-268` |

### A.2 재매입 조건(SALE만)

| 사실 | 근거 |
|---|---|
| 대상 = 상태 REJECTED·FILE_REJECTED, 그 거래·종류의 최고 세대, **어느 세대에도 ACCEPTED 없음**(ALREADY_ACCEPTED) | `…/domain/ReacquisitionRule.kt:28-36` |
| UNKNOWN 대상은 대사 기능 전이라 거절(UNKNOWN_NOT_RECONCILED) | `…/domain/ReacquisitionRule.kt:33` (BR-FIN-048, I-55) |
| 실행 직전 재검사: 어느 세대든 ACCEPTED가 됐거나 상태·세대가 바뀌면 TARGET_CHANGED | `…/domain/ReacquisitionRule.kt:54-64` |
| CANCEL 레코드 재매입은 미지원(CANCEL_NOT_SUPPORTED — ACQ-ADMIN-002로 이월) | `…/domain/ReacquisitionExecutionRule.kt:50` |
| 아직 매입 판정이 안 된 취소가 있으면 실행 거절(CANCELLATION_UNDECIDED), 미해제 보류면 PAYMENT_HELD | `…/domain/ReacquisitionExecutionRule.kt:52-53` |
| 재매입 레코드 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(각 한 번), 순액 0이면 파일 없이 '재매입 불요' | `…/domain/ReacquisitionExecutionRule.kt:57-67`, `…/domain/ReacquisitionRule.kt:70-75` (D-ACQ-013, D-ACQA-004) |
| 묶음 중 하나라도 거절이면 승인 전체 거절 | `…/domain/ReacquisitionExecutionRule.kt:43-49` (D-ACQA-003) |

**자연 도달 범위(명세 ACQ-ADMIN-001 §15 L-1)**: 세대 1은 세대 0이 REJECTED·FILE_REJECTED로 **종결된 뒤에만** 생긴다. 종결 뒤 들어온 ACCEPTED는 ACCEPTED가 아니라 CONFLICT가 된다. 그래서 "앞 세대가 ACCEPTED(표지)인데 뒤 세대도 있는" 상태는 지금 규칙으로는 자연히 생기지 않는다. 대사(RECON)가 UNKNOWN 재매입을 열면 생긴다(UNKNOWN → 재매입 → 앞 세대의 늦은 ACCEPTED). 반면 "앞 세대 CONFLICT + 뒤 세대 존재"는 **지금도 생긴다**(앞 세대 REJECTED → 재매입 실행 → 매입사가 앞 세대에 상충 ACCEPTED를 보냄).

### A.3 결과 반영

- 응답 파일의 줄마다 `RecordResultRule.apply` → Append(결정)·Conflict(격리 행)·Duplicate·AlreadyConflicted. ACCEPTED Append면 같은 TX에서 표지 INSERT(`…/application/AcquirerResponseService.kt:232-276`).
- 파일 전체 거절(ACK FILE_REJECTED)은 그 파일 레코드 전부를 FILE_REJECTED로 종결(`…/domain/RecordResultRule.kt:11`, `:24`).
- 동시 경합은 표지 PK 위반 → 재시도 → CONFLICT로 수렴(ACQUIRING-001 I-56).

### A.4 취소 후보 판정(전송 후 취소)

| 단계 | 근거 |
|---|---|
| 봉인 TX에서 lane 잠금 → 결제별 행 FOR UPDATE → 판정 끝난 취소(상계·CANCEL 레코드·불요) 다시 제외 | `…/application/AcquisitionSealService.kt:120`, `:157`, `:234-235` |
| **원 SALE 고르기 = 그 원승인의 SALE 레코드 중 `maxBy { generation }`** — 최고 세대. 확정(표지) 세대가 아니다 | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:51-64`(`:61`) |
| 고른 레코드 **하나의** 결과 상태만 계산해 넘긴다 | `…/application/AcquisitionSealService.kt:237-239`, `:258-259` |
| 판정: LIVE·마감 뒤·보류·통화·비정합(누적 한도 포함)이 먼저, 그다음 원 SALE 결과로 ACCEPTED → CANCEL 레코드, AWAITING → 대기, UNKNOWN·CONFLICT → 대사(기록 없음, 다음 봉인에 다시 판정), REJECTED·FILE_REJECTED → '취소 매입 불요' 기록(종결) | `…/domain/CancelCandidateRule.kt:20-42`, `…/domain/CancelRecordRule.kt:11-16`, `…/domain/RecordResultRule.kt:72-78` |
| 정합 검사: 원 레코드가 이 거래의 SALE이고 승인액이 같으며, 원 레코드 상계액 + 이미 봉인한 CANCEL + 이 취소 ≤ 원승인액. rev 0.7에서 `sale.generation != 0` 조건을 뺐다 | `…/domain/CancelCandidateRule.kt:44-54`(`:48-50`) |
| 같은 원승인의 여러 취소는 (취소 시각, 거래 ID) 순으로 누적 | `…/application/AcquisitionSealService.kt:224`, `:248` |

### A.5 중복 전송 방지 장치

| 장치 | 막는 것 | 근거 |
|---|---|---|
| UNIQUE(transaction_id, record_type, generation) | 같은 세대 레코드 두 번. CANCEL은 언제나 세대 0이라 **취소 하나당 CANCEL 레코드 하나** | `V5__acquisition_submission.sql:102` |
| 상계 PK(cancellation_transaction_id), 불요 PK(cancellation_transaction_id) | 같은 취소의 상계·불요 두 번 | `V5__…:128`, `V10__acquisition_response.sql:88` |
| 잠금 아래 판정 끝남 재확인(상계 ∪ CANCEL 레코드 ∪ 불요) | 같은 취소가 세 경로 중 둘 이상으로 판정됨(표를 넘는 DB 제약은 없고 잠금 + 재확인이 지킨다) | `…/adapter/persistence/JpaAcquisitionMembershipStore.kt:78-86` |
| 누적 한도(상계 + 봉인 CANCEL + 이 취소 ≤ 원승인액) | 원승인액을 넘는 취소 매입 | `…/domain/CancelCandidateRule.kt:51-53` |
| 매입 확정 표지 PK(거래, 종류) | 세대를 넘는 두 번째 ACCEPTED를 결정으로 인정 | `V14__…:23` |
| 재매입 승인 UNIQUE(거래, 종류, 새 세대)·(파일, 순번, 새 세대), 승인 ID UNIQUE | 같은 세대 재매입 두 번·같은 승인 재실행 | `V12__…:23`, `V17__…:26`, `:43` |
| 전송 보류 TRANSMISSION_WITHHELD | 봉인한 재매입 파일의 레코드가 그 사이 다른 세대에서 표지를 얻으면 보내지 않음(다른 세대 파일 행 잠금 아래 재확인, 종결·자동 해제 없음) | `…/application/AcquisitionTransmissionService.kt:167-182`, `…/domain/AcquisitionFile.kt:58`, `:68-76`, `V19__…:24` |
| 전송 확인 우선(최종명 hash MATCH면 다시 올리지 않음) | 같은 파일 재업로드 | `…/application/AcquisitionTransmissionService.kt`(upload — `Check.MATCH`) |

남은 위험으로 명세가 적은 것(ACQ-ADMIN-001 §15 L-9 rev 0.8): m-3 보류 중 받은 취소가 최고 세대(보류된 파일)를 기다리며 무기한 대기, m-4 전송 점유 커밋 **뒤** 도착한 원 세대 ACCEPTED는 막을 수 없어 이중 매입 요청 가능(CONFLICT·대사로 처리).

## 부록 B. CC-4와 CC-8 — 변경 전후 정의와 진단

### B.1 변경 전(커밋 `4bd0105`, 2026-11-06 17:06 KST — ACQUIRING-001. 아래 인용은 0.1a에서 `git show 4bd0105:<경로>`로 다시 확인)

- 원 SALE 조회: `saleMemberships`가 **세대 0만** 읽었다 — `"... r.recordType = 'SALE' and r.generation = 0 ..."`(`git show 4bd0105:…/JpaAcquisitionMembershipStore.kt:54`).
- 정합 규칙: `if (sale.type != SALE || sale.generation != 0 || ...) return false`(`git show 4bd0105:…/CancelCandidateRule.kt:48`).
- CC-4 넷째 벡터(원문, `git show 4bd0105:…/CancelCandidateRuleSpec.kt:63`):
  ```kotlin
  cancelAfterMembership(original).let { it.copy(originalSale = it.originalSale.copy(generation = 1, reacquisitionOf = "ACQ.SIMACQA.TEST.20261105.001#1")) },
  ```
  기대값: `CancelCandidateRule.decide(it, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.INCONSISTENT)` — 세대 1 원 SALE은 결과가 ACCEPTED여도 대기.
- 이 시점에는 세대 1 레코드를 원 SALE로 넘기는 경로가 없어서(조회가 세대 0만) 방어 벡터였다.

### B.2 중간 단계(ACQ-ADMIN-001 rev 0.2 B1 — 커밋 없음, 명세 기록으로만 남음)

git 이력에서 `CancelCandidateRule.kt`·`JpaAcquisitionMembershipStore.kt`·`CancelCandidateRuleSpec.kt`를 바꾼 커밋은 `4bd0105`와 `1ad6ed8` 두 개뿐이다(`git log --oneline -- <세 파일>`). ACQ-ADMIN-001 명세 파일도 `1ad6ed8`에서 처음 추가됐다(`git show 1ad6ed8 --stat` — 823줄 추가). 따라서 B1(rev 0.2)과 B2(rev 0.7)는 **따로 커밋되지 않았다**. 아래 B1 상태는 명세 rev 0.2 기록(§11 I-14, §15 L-6, §17 실행 기록)으로만 확인할 수 있고, git으로 되돌려 볼 수 있는 시점은 없다.

- I-14(명세 `:283`): `saleMemberships`가 **최고 세대**를 고르게 바뀌었다. 이유(현재 코드 주석 `JpaAcquisitionMembershipStore.kt:52-54`): 세대 0 REJECTED로 '취소 매입 불요'를 써 버리면 세대 1이 ACCEPTED될 때 그 취소가 매입사에 가지 않는다.
- 명세 rev 0.2 기록상 이때 세대 ≥ 1 원 SALE은 CC-4 넷째 벡터대로 INCONSISTENT 대기였다. 그래서 잘못된 종결 기록이 없었다(L-6 `:372`, §17 RX-7 "INCONSISTENT 대기 관찰" `:413`).

### B.3 변경 후(커밋 `1ad6ed8`, 2026-11-07 22:08 KST — ACQ-ADMIN-001 B1+B2를 한 번에, 명세 rev 0.8 상태로 커밋. 인용은 `git show 1ad6ed8`로 다시 확인)

- `saleMemberships`: `r.generation = 0` 조건 삭제 + `rows.maxBy { it.generation }`(`git show 1ad6ed8:…/JpaAcquisitionMembershipStore.kt:61` — 현재 HEAD도 같은 줄).
- 정합 규칙에서 `sale.generation != 0` 삭제(`…/domain/CancelCandidateRule.kt:48-50`, 커밋 diff `-` 줄과 `+` 줄로 확인).
- CC-4: 넷째 벡터 삭제, 자리에 "원 레코드 종류 CANCEL", "원 레코드 승인액 10,001" 불일치 벡터 2개 추가(강화) — `CancelCandidateRuleSpec.kt:57-76`, 주석 `:60-61`.
- CC-8 신설(`CancelCandidateRuleSpec.kt:78-108`) — 원문 제목: "a cancellation received after the original was re-acquired: judged against the highest generation SALE (I-14)". 입력: 원승인 10,000, 실행 전 판정 끝난 취소 3,000(상계액), 실행 뒤 받은 취소 2,000. 기대값:
  - `OriginalSaleResult.ACCEPTED` → `Include`, CANCEL 레코드(원승인액 10,000, 금액 2,000, 상계 0, 세대 0, reacquisitionOf null)
  - `REJECTED` → `NotRequired`, `AWAITING` → `Wait(ORIGINAL_AWAITING_RESULT)`, `UNKNOWN` → `Reconcile`
  - 세대 2도 ACCEPTED → `Include`
  - 누적 한도: 2,001 + 3,000 + 5,000 > 10,000 → INCONSISTENT, 2,000이면 Include, REJECTED여도 초과면 INCONSISTENT
  - 다른 거래·승인액 다른 재매입 레코드 → INCONSISTENT
- 함께 바뀐 기대값: `ReacquisitionExecutionDbSpec` RX-7 `cancelWaiting = {INCONSISTENT:1}` → `{ORIGINAL_AWAITING_RESULT:1}`(불요 0·CANCEL 0 단언은 그대로) — `ReacquisitionExecutionDbSpec.kt:258-266`.
- 명세 근거: ACQ-ADMIN-001 rev 0.7 이력 줄(`ACQ-ADMIN-001-ACQUIRING-OPERATIONS.md:17` "L-6 CancelCandidateRule 세대 ≥ 1 개방(CC-4 기대값 변경 — 근거 L-6), … 사용자 결정 변경 없음"), §15 L-6(`:372`), §17.10 "바뀐 기존 기대값과 근거" 표(`:703-705`). L-6 원문(rev 0.2): "새 세대 ACCEPTED 뒤 CANCEL 레코드·새 세대 REJECTED 뒤 불요 판정은 B2에서 CancelCandidateRule을 바꿔 연다(기존 CC-4 기대값 변경 = B2 명세 근거 필요)."
- B2 독립 검토: PASS(MAJOR 0), 남은 위험 m-2·m-3·m-4를 기록만(`:376-379`). m-3 원문이 "코드 수정(확정 세대 우선 선택)은 ACQ-ADMIN-002"라고 적어, **최고 세대가 아니라 확정 세대를 써야 한다는 점은 이미 그때 알려져 있었다**.

### B.4 진단

| 질문 | 판단 | 근거 |
|---|---|---|
| 정책 변경이 시험 기대값 이동으로 들어갔는가 | **그렇다.** "세대 ≥ 1 원 SALE 뒤 취소 = 자동 판정하지 않음(대기)"에서 "최고 세대 결과로 자동 CANCEL·불요 판정"으로 바뀐 것은 취소 매입 여부(금융 의미)를 바꾼다. 이것이 Implementer 명세(L-6)와 CC-4 → CC-8 기대값 이동으로만 들어갔다 | rev 0.7 이력 "사용자 결정 변경 없음", FIN.md:78 출처 `적용` |
| 사용자 승인 근거가 있는가 | **없다.** D-ACQ-009는 "원 SALE ACCEPTED일 때만"(세대 언급 없음), D-ACQ-013은 재매입 금액, D-ACQ-014는 세대를 넘는 ACCEPTED 하나. 어느 것도 "재매입 뒤 취소는 최고 세대 기준"을 정하지 않았다. 사용자 질문 기록도 없다 | DECISIONS.md:208·212·213, PC-66 |
| 변경이 위험을 줄였는가 늘렸는가 | 세대 0 REJECTED 뒤 세대 1 ACCEPTED 경우(정상 재매입 성공)는 **바르게** CANCEL을 보낸다(RR-1) — rev 0.6은 이 취소를 무기한 대기시켰다. 그러나 "최고 세대 = 유효 세대"라는 가정이 깨지는 경우(앞 세대 CONFLICT·앞 세대 표지 + 뒤 세대 거절·보류)에는 잘못된 종결 또는 무기한 대기가 생긴다(§5 다른 칸) | §5 |
| 원칙 위반 여부 | CLAUDE.md "정책·테스트 기대값·게이트 기준을 바꿔서 검사 통과시키지 않는다. 새 정책은 사용자 답변과 근거를 기록한다"에 걸린다. 다만 검사를 통과시키려고 바꾼 것이 아니라 명세(L-6)를 먼저 고친 뒤 RED → GREEN으로 바꿨다(§17.10 RED 기록 "CC-8: INCONSISTENT"). 절차 위반은 **사용자 승인 없이 금융 규칙을 연 것**이다 | ACQ-ADMIN-001 §17.10 |

**결론**: CC-8의 기대값 자체(세대 ≥ 1 SALE 결과를 세대 0처럼 판정)는 "다른 세대에 표지·상충이 없을 때"에 한해 아래 제안 정책과 같다. 틀린 부분은 기대값이 아니라 **입력을 정하는 곳**(최고 세대 하나만 고르는 `saleMemberships`)이다. 그래서 CC-8의 기존 기대값은 바꿀 필요가 없고, 제안 정책이 승인되면 **새 벡터를 더하는 것**으로 충분할 것으로 본다(§7).


## 부록 C. rev 0.1a 상태 전이표·대조표

### C.1 레코드(세대) 수준 — 현행 유지(차이 없음)

| 레코드 상태 \ 이벤트 | 전송 완료 | ACCEPTED 수신 | REJECTED 수신 | ACK 파일 거절 | 결과 마감(미수신) | 다른 세대 표지 있음 + ACCEPTED 수신 |
|---|---|---|---|---|---|---|
| AWAITING | AWAITING(전송 축만 변함) | ACCEPTED + 표지 | REJECTED | FILE_REJECTED | UNKNOWN(TRANSMITTED 파일만) | CONFLICT(관찰 ACCEPTED) |
| UNKNOWN | — | ACCEPTED + 표지(늦은 결과) | REJECTED | FILE_REJECTED | 변화 없음 | CONFLICT |
| ACCEPTED | — | 중복(행 없음) | CONFLICT | CONFLICT | 변화 없음 | — |
| REJECTED·FILE_REJECTED | — | CONFLICT | 같은 사유면 중복, 다르면 CONFLICT | 같으면 중복 | 변화 없음 | CONFLICT |
| CONFLICT | — | 행 없음(격리 유지) | 행 없음 | 행 없음 | 변화 없음 | 행 없음 |

근거: `RecordResultRule.kt:35-66`. 재매입 승인·실행은 앞 세대 레코드 상태를 바꾸지 않고 새 세대 레코드(AWAITING)를 만든다.

### C.2 원승인 수준 — 취소 도착 시 판정(현재 코드 vs 제안)

표기: g0 = 세대 0, g1 = 세대 1. "표지"는 매입 확정 표지. **≠** = 현재 코드와 제안 정책의 판정이 다른 칸. 권장안 기준(Q-ARC-2 = CANCEL + 경보).

| # | 원승인 매입 사실 묶음(취소 도착 시점) | 현재 코드(최고 세대 하나) | 제안 EAS → 판정 | 차이 | 지금 도달 가능? | 기존 시험 |
|---|---|---|---|---|---|---|
| 1 | g0 AWAITING | 대기 | IN_PROGRESS → 대기 | = | 예 | CC-2, AQ-X1 |
| 2 | g0 ACCEPTED(표지) | CANCEL | ACQUIRED(0) → CANCEL | = | 예 | CC-1, AQ-X1 |
| 3 | g0 REJECTED·FILE_REJECTED, 재매입 없음 | 불요 | NOT_ACQUIRED(0) → 불요 | = | 예 | CC-2, AQ-X1 |
| 4 | g0 UNKNOWN | 대사 | UNRESOLVED → 대사 | = | 예 | CC-2, AQ-X1 |
| 5 | g0 CONFLICT, 재매입 없음 | 대사 | UNRESOLVED → 대사 | = | 예 | CC-2 |
| 6 | g0 REJECTED, 재매입 승인만 있음(미실행) | 불요(g0) | NOT_ACQUIRED(0) → 불요, 실행 때 순액 차감 | = | 예 | RX-1·RX-13 |
| 7 | g0 REJECTED, g1 결과 대기(정상 전송 경로) | 대기 | IN_PROGRESS(1) → 대기 | = | 예 | RX-7 |
| 8 | g0 REJECTED, g1 ACCEPTED(표지 g1) | CANCEL | ACQUIRED(1) → CANCEL | = | 예 | CC-8, RR-1 |
| 9 | g0 REJECTED, g1 REJECTED | 불요(g1) | NOT_ACQUIRED(1) → 불요(g1) | = | 예 | CC-8, RR-2 |
| 10 | g0 REJECTED, g1 UNKNOWN | 대사 | UNRESOLVED → 대사 | = | 예 | CC-8(도메인만) |
| 11 | g0 REJECTED, g1 CONFLICT, 표지 없음 | 대사 | UNRESOLVED → 대사 | = | 예 | 없음 |
| 12 | g0 CONFLICT(앞 세대 상충 ACCEPTED), g1 ACCEPTED(표지 g1) | CANCEL | ACQUIRED_DISPUTED(1) → CANCEL + 이중 매입 대사 경보 | = (경보만 추가) | 예 | AQ-R14(결과만, 취소 없음) |
| 13 | g0 CONFLICT, g1 결과 대기, 표지 없음 | 대기 | UNRESOLVED → 대사 | **≠** (금액 효과 없음 — 둘 다 안 보냄) | 예 | 없음 |
| 14 | g0 CONFLICT, g1 REJECTED, 표지 없음 | **불요(g1) — 종결** | UNRESOLVED → 대사 | **≠ (취소 누락 위험)** | **예** | 없음 |
| 15 | g0 ACCEPTED(표지 g0, UNKNOWN에서 늦게), g1 파일 TRANSMISSION_WITHHELD(레코드 AWAITING) | 대기(무기한 — m-3) | ACQUIRED(0) → CANCEL | **≠ (취소 무기한 미전송)** | 아니오(UNKNOWN 재매입 개방 뒤) | RR-3(보류만, 취소 없음) |
| 16 | g0 ACCEPTED(표지 g0), g1 전송됨·결과 대기 | 대기 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음) | 없음 |
| 17 | g0 ACCEPTED(표지 g0), g1 REJECTED(중복 재매입 거절) | **불요(g1) — 종결** | ACQUIRED(0) → CANCEL | **≠ (취소 누락 — 확정)** | 아니오(같음) | 없음 |
| 18 | g0 ACCEPTED(표지 g0), g1 CONFLICT(관찰 ACCEPTED — 이중 매입 의심) | 대사 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음, m-4 경로는 예) | 없음 |
| 19 | g0 ACCEPTED(표지 g0), g1 UNKNOWN | 대사 | ACQUIRED_DISPUTED(0) → CANCEL + 경보 | **≠** | 아니오(같음) | 없음 |
| 20 | g0 REJECTED, g1·g2… 반복 거절 뒤 최고 세대 gn 결과 대기 | 대기 | IN_PROGRESS(n) → 대기 | = | 예 | RR-2(g2 실행까지) |
| 21 | g0 REJECTED, g1 파일 TRANSMISSION_QUARANTINED 또는 TRANSMISSION_WITHHELD(표지 없음) | 대기(무기한) | UNRESOLVED → 대사 | **≠** (금액 효과 없음) | 예(격리) | 없음 |

**다른 칸 수: 8칸**(#13·14·15·16·17·18·19·21. 0.1a: §5.3 재매입 승인 1행을 더하면 모두 9칸). 이 중 **금액 효과가 있는 칸 3개**: #14(지금 도달 가능 — 매입사가 앞 세대에 ACCEPTED를 보냈을 수 있는데 취소를 '불요'로 종결해 취소 매입 누락 가능), #17(취소 매입 누락 — UNKNOWN 재매입 개방 뒤), #15(취소가 매입사에 무기한 가지 않음). #16·18·19는 Q-ARC-2 답에 따라 같음/다름이 바뀐다(대안 '보류'를 고르면 #16은 대기→대사로 금액 효과 없는 차이, #18·19는 같음). #13·21은 보내지 않는다는 점은 같고 대기 대신 대사 경보로 드러내는 차이다.

### C.3 원승인 수준 — 그 밖 이벤트(0.1a: 재매입 승인 1행이 다름 — 나머지는 현재 코드 = 제안)

| 이벤트 | 현재 동작 | 제안 | 근거 |
|---|---|---|---|
| 재매입 승인 요청(표지 있음) | 거절 ALREADY_ACCEPTED | 같음 | `ReacquisitionRule.kt:28`, RQ-7, RX-2 |
| **(0.1a — 리뷰 m-8) 재매입 승인 요청: g0 CONFLICT(표지 없음 — 매입사가 상충 ACCEPTED를 보냄) + 최고 세대 g1 REJECTED** | **g2 승인·실행 가능.** 대상 검사는 최고 세대 g1의 상태(REJECTED)와 `acceptedInAnyGeneration`(표지 유무)만 본다(`ReacquisitionRule.kt:28-36`). 실행 재검사도 대상 세대·상태·표지만 본다(`:54-64`). g0의 CONFLICT는 보지 않는다 | **≠ 사용자 결정 필요(Q-ARC-3 ②)**. 권장: EAS가 UNRESOLVED(어느 세대든 CONFLICT)면 재매입 거절(새 거절 사유 — 예 `ORIGINAL_UNRESOLVED`) → 대사 확인 뒤에만 재매입 | 이중 매입 경로: 매입사가 g0를 실제로 매입했다면 g2가 두 번째 매입 요청이 된다. 표지 PK(V14)는 g2 ACCEPTED를 결정으로 인정하지만(g0에 표지가 없으므로), 매입사 쪽 이중 매입은 막지 못한다. 기존 시험 없음. 지금 도달 가능(§2.2) |
| 재매입 승인 요청(최고 세대 UNKNOWN) | 거절 UNKNOWN_NOT_RECONCILED | 같음(대사 기능 전) | `ReacquisitionRule.kt:33` |
| 재매입 실행 직전 앞 세대 상태 변화 | TARGET_CHANGED | 같음 | `ReacquisitionRule.kt:54-64`, RQ-8 |
| 재매입 실행 시 미판정 취소 있음 | CANCELLATION_UNDECIDED 거절 | 같음 | `ReacquisitionExecutionRule.kt:53`, RX-4 |
| 재매입 실행 시 전액 취소됨(순액 0) | '재매입 불요' 종결, 파일 없음 | 같음 | `ReacquisitionExecutionRule.kt:63-64`, RX-3 |
| 봉인한 재매입 파일 전송 전 다른 세대 표지 발생 | TRANSMISSION_WITHHELD(종결) | 같음. 단 그 뒤 취소는 #15처럼 표지 세대로 판정 | `AcquisitionTransmissionService.kt:167-182`, RR-3·RR-4 |
| CANCEL 레코드 REJECTED | 재매입 경로 없음(CANCEL_NOT_SUPPORTED) | 같음(ACQ-ADMIN-002, BR-FIN-050 — 이 문서 범위 밖) | `ReacquisitionExecutionRule.kt:50` |

## 부록 D. (rev 0.1a) 사용자가 든 6개 경우별 분석

### ① 최초 매입 실패 → 재매입 성공
- **현재**: g0 REJECTED 동안 받은 취소 → '불요'(g0)로 종결(#3·6), 재매입 순액이 그 취소를 뺀다(RX-1·RX-13). 실행 뒤 받은 취소 → g1 결과 대기(#7, RX-7), g1 ACCEPTED 뒤 → CANCEL(#8, RR-1). I-17 무결성 대조가 "취소가 순액과 CANCEL 양쪽에 들어가지 않음"을 지킨다(RR-1 세 번째 Then).
- **제안**: 같다.
- **차이·위험**: 없음. 이중 취소 매입 위험은 '불요' 기록 + 순액 차감 + 판정 끝남 재확인으로 막혀 있다.

### ② 최초 매입 성공 → 중복 재매입 실패
- **현재**: g0 표지가 있으면 재매입 승인·실행이 거절된다(RQ-7, RX-2) — 정상 경로로는 중복 재매입이 생기지 않는다. 그러나 g0가 UNKNOWN에서 늦게 ACCEPTED된 경우(대사로 UNKNOWN 재매입이 열린 뒤) 세대 1이 이미 있을 수 있고, 그때 취소는 **최고 세대 g1** 결과로 판정된다: g1이 보류면 무기한 대기(#15), 전송됐고 매입사가 중복으로 거절하면 '불요' 종결(#17).
- **제안**: 표지 세대 g0가 유효 → CANCEL(#15·17), g1이 아직 진행 중·UNKNOWN·CONFLICT면 CANCEL + 대사 경보(#16·18·19, Q-ARC-2).
- **차이·위험**: #17은 **취소 매입 누락**(매입사는 원승인을 매입했고 고객 취소는 매입사에 전달되지 않음 → 매입사 입금 과다·가맹점 지급 과다 가능). 지금은 UNKNOWN 재매입이 막혀 도달하지 않지만, 대사(RECON)가 열리는 순간 생긴다. 기존 시험 없음.

### ③ 반복 재매입·결과 미수신
- **현재**: g1 REJECTED → g2 실행(RR-2), 최고 세대 결과 대기면 대기(#20). 전송된 파일이 마감까지 결과가 없으면 UNKNOWN → 취소는 대사(#10, 봉인마다 다시 판정 — 늦은 ACCEPTED면 다음 봉인이 CANCEL). UNKNOWN 재매입은 거절. 전송 결과 모름(TRANSMISSION_UNKNOWN)은 확인 우선 재업로드로 수렴, 그동안 대기. 전송 격리·보류 파일은 마감 처리 대상이 아니라 레코드가 AWAITING에 머물고 취소가 무기한 대기(#21).
- **제안**: #21만 다르다 — 결과가 올 정상 경로가 없는 파일은 UNRESOLVED(대사 경보)로 드러낸다.
- **차이·위험**: 금액 효과 없음(둘 다 보내지 않음). 무기한 대기가 지표에 '대기'로만 보여 대사 대상에서 빠질 위험. 기존 시험 없음.

### ④ 재매입 처리 중 전체·부분취소
- **현재**(시점별):
  - (a) g0 종결 거절 뒤·승인 전: 다음 봉인이 '불요'(g0)로 종결 → 실행 순액에서 차감(RX-1).
  - (b) 승인 뒤·실행 전: 봉인이 먼저 돌면 (a)와 같고, 아니면 실행이 CANCELLATION_UNDECIDED로 승인 전체 거절(RX-4) → 다시 요청. 승인 기록에는 금액이 없어(I-54) 승인 시점 금액이 낡지 않는다.
  - (c) 실행 전 전액 취소: 순액 0 → '재매입 불요'(RX-3).
  - (d) 실행 뒤 g1 결과 대기: 대기(RX-7) → g1 ACCEPTED면 CANCEL(RR-1, 부분취소만 시험), g1 REJECTED면 '불요'(g1) → g2 순액에서 차감(RR-2).
  - 누적 한도: g1 상계액 + 봉인 CANCEL + 이 취소 ≤ 원승인액(CC-8 두 번째 Then).
- **제안**: 같다. 단 (d)에서 g0가 CONFLICT가 되면 #13·14 규칙(대사)으로 바뀐다.
- **차이·위험**: (d)에서 g1 ACCEPTED 뒤 **전액**(남은 금액 전부) 취소, 여러 번 부분취소의 누적, (b)의 봉인·실행 동시 경합에서 같은 취소가 '불요'와 순액에 두 번 들어가지 않는지는 DB 시험이 부분적이다(RX-12는 잠금 순서만). 새 시험 필요(§7 T-09·T-10·T-11).

### ⑤ 취소 매입 중복 전송 방지
- **현재**: §2.5 장치. CANCEL은 언제나 세대 0이라 UNIQUE(거래, CANCEL, 0)가 취소 하나당 CANCEL 레코드 하나를 DB에서 보장한다. 상계·불요·CANCEL 세 경로 사이의 배타성은 DB 제약이 아니라 lane 잠금 + 결제별 행 잠금 + 잠금 아래 재확인이 지킨다. CANCEL 레코드 파일의 재전송은 확인 우선(hash MATCH면 재업로드 없음). CANCEL REJECTED의 재매입은 미지원이라 두 번 보낼 경로가 없다.
- **제안**: 같다. EAS를 바꿔도 "판정 끝난 취소는 다시 판정하지 않는다"(규칙 5)가 유지되므로 #15·17에서 CANCEL을 새로 보내도 같은 취소의 두 번째 CANCEL은 생기지 않는다.
- **차이·위험**: 없음. 세 경로 배타성의 동시 봉인 경합 DB 시험이 있으면 좋다(§7 T-12, 기존 RX-12·AQ 봉인 경합 시험과 대응 확인 필요).

### ⑥ 매입사 응답 결과와 내부 매입 상태 정합
- **현재**: 결과 행 append-only, 중복/상충 구분, 세대를 넘는 ACCEPTED 하나(앱 규칙 + V14 PK), 파일 거절 종결, 늦은 결과 자동 반영. 원 SALE이 CONFLICT가 되면 그 레코드를 가리키는 '불요'가 있을 때만 경보한다(`AcquirerResponseService.kt:264-268`). 원승인 **수준**의 유효 상태는 어디에도 계산되지 않고, 취소 판정은 최고 세대 레코드 하나만 본다.
- **제안**: EAS를 원승인 수준의 유일한 판정 입력으로 둔다. CONFLICT 경보를 "그 원승인의 다른 세대 레코드를 가리키는 '불요'·CANCEL"까지 넓힌다(#14가 지금 경보 없이 종결되는 이유).
- **차이·위험**: #14. 매입사가 g0에 상충 ACCEPTED를 보낸 뒤 g1이 거절되면, 지금은 취소가 '불요'(g1)로 경보 없이 종결된다. 매입사 쪽에서는 g0가 매입됐을 수 있다. 대사(RECON) 기능이 없어서 이 불일치를 맞출 경로가 아직 없다. 같은 상태(g0 CONFLICT + g1 REJECTED)에서 g2 재매입도 승인될 수 있어(§5.3 m-8) **이중 매입**으로 번질 수 있다. 이미 쓰인 '불요'는 예외 5-a(§4.2)와 ARC-20으로 탐지한다.


## 부록 E. rev 0.2b 본문 발췌(직렬화 사실표·시험 정합표·ARC-03~22·탐지 SQL — 기록용)

> 이 발췌의 M 번호·R-1·R-2·EAS 판정은 0.2b 기준이다. `ACQUIRED_CONTESTED`의 "R-1 미결/답 전 현행" 서술은 D-GOV-017로 대체되었다(본문 §2.4). 0.3에서 바뀐 매트릭스 번호: 0.2b M16→0.3 M16a/b, M19→M19a/b, 새 M26~M31.

### E.1 (0.2b §3.6) 직렬화 요구(P-25 — 방식은 구현 명세에서 확정)

**지금 잠금 지점(사실)**:

| TX | 잠그는 것(순서) | 근거 |
|---|---|---|
| 정기 봉인(취소 판정 포함) | lane 행 → 결제별 행(payment_id 순) | `AcquisitionSealService.kt:120`, `:157` |
| 응답 반영(결과 행·표지) | 그 응답의 파일 행 하나 | `AcquirerResponseService.kt:137` |
| 재매입 실행 | 대상(최고 세대) 레코드의 파일 행 → lane 행 → 결제별 행 | `ReacquisitionExecutionService.kt:126-130` |
| 재매입 파일 전송 점유(보류 판정) | 다른 세대 파일 행(ID 오름차순) → 자기 파일 행 | `AcquisitionTransmissionService.kt:167-176`, ACQ-ADMIN-001 I-28 |

**남는 경합**(서로 다른 행을 잠그므로 직렬화되지 않음):
- (a) 봉인 TX가 g0·g1 결과를 읽어 '불요'(g1)를 쓰는 동안, 응답 TX가 낮은 세대 g0 파일 행만 잠그고 CONFLICT(관찰 ACCEPTED)를 커밋한다. 결과는 M15형 '불요'다. 응답 TX의 사후 경보(`notRequiredCancellationsOf` — 근거 레코드 자신만)도 이 경우 놓친다.
- (b) 재매입 실행이 g1 파일 행을 잠그고 g2를 봉인하는 동안, 응답 TX가 g0 파일 행에 CONFLICT를 쓴다. 결과는 M15 재매입 경로(이중 매입 요청)다.
- (c) 봉인 TX가 '표지 없음'으로 읽은 직후 응답 TX가 표지를 쓴다. 지금은 대기·대사 쪽으로 판정되어 안전 쪽이지만, 같은 원리로 '불요'가 될 수 있는 조합(표지가 낮은 세대에 늦게 생김 — M18형)이 있다.

**요구**: EAS를 읽고 그 결과로 쓰는 TX(봉인의 취소 판정, 재매입 실행, 재매입 파일 점유)와, 결과 행·표지를 쓰는 응답 TX는 **원승인 단위의 같은 잠금 대상**에서 직렬화한다. 선택지(구현 명세에서 확정):
- **선택지 ①**: 응답 TX가 파일 행 잠금 뒤, 그 파일 레코드들의 원승인 결제별 행을 payment_id 순으로 FOR UPDATE한다. 봉인·재매입 실행이 이미 결제별 행을 잠그므로 공통 지점이 생긴다.
  - 순서는 응답이 파일 → 결제, 봉인이 lane → 결제, 실행이 파일 → lane → 결제가 된다. 응답이 결제 행 뒤에 다른 잠금을 잡지 않으면 순환이 없을 것으로 보이지만 검증이 필요하다.
  - 응답 파일 하나가 많은 결제 행을 잠그는 비용이 있다.
- **선택지 ②**: EAS를 읽는 TX가 그 원승인의 **모든 세대 파일 행**을 file_id 순으로 FOR UPDATE한다(I-28과 같은 방식). 응답 TX의 파일 행 잠금과 직렬화된다.
  - 봉인은 lane → 결제 → 파일 행, 실행은 대상 파일 → lane → 결제 → 다른 세대 파일 행이 된다.
  - 실행이 대상 파일 행을 먼저 잡으므로, 보류 판정(다른 세대 오름차순 → 자기)과 RX-12에서 고정한 봉인·실행 잠금 순서(I-5)와 순환하지 않는지 검토해야 한다.
- 어느 쪽이든 잠금 뒤 EAS를 다시 계산하고(빠른 경로는 비권위), 실제 두 연결 경합 시험 ARC-21·ARC-22로 고정한다.
- **백업 장치**: 직렬화를 넣어도 m-4(전송 점유 커밋 뒤 도착한 결과)처럼 소유 DB 잠금으로 막을 수 없는 경로가 남는다. 그래서 누락 의심 탐지 ARC-17(읽기 전용, 정기)과 CONFLICT 경보를 사후 백업으로 둔다.

### E.2 (0.2b §9) 기존 시험 정합표(기대값은 바꾸지 않음)

판정 기준:
- **유효**: 그대로 맞음.
- **범위 좁힘**: 기대값은 맞고, 시험이 가정한 범위를 밝힘(기대값 불변).
- **입력 형태 변경**: 규칙 인자가 바뀌면 호출 Fixture만 고침(기대 리터럴 불변).
- **충돌 후보**: 구현 방식에 따라 기대 리터럴이 바뀔 수 있음 → 사용자 보고 대상.

| 시험 | 판정 | 메모 |
|---|---|---|
| CancelCandidateRuleSpec CC-1 | 유효 · 입력 형태 변경 | M02 |
| CC-2 | **충돌 후보**(조건부) | 기대 리터럴에 `OriginalSaleResult`가 있다: `CancelDecision.Reconcile(c, OriginalSaleResult.UNKNOWN)`·`(c, OriginalSaleResult.CONFLICT)`(`CancelCandidateRuleSpec.kt:36-37`). 판정 인자를 EAS로 바꾸고 `Reconcile`이 EAS를 실으면 기대값이 바뀐다. **구현 조건**(§3.5): 기존 판정 타입을 유지하는 어댑터 → 그러면 유효(세대 0만 있을 때 — M01·M03·M04·M06) |
| CC-3 | 유효 | 거래 단위 대기 먼저 |
| CC-4 | 유효 | 유효 세대 상계 기준과 같은 값 |
| CC-5·CC-6·CC-7 | 유효 | |
| CC-8 | **충돌 후보**(조건부) · 범위 좁힘 | `CancelDecision.Reconcile(c, OriginalSaleResult.UNKNOWN)`(`:90`)도 같은 문제(어댑터 조건이면 유효). 기대 의미(세대 ≥ 1 SALE 결과를 세대 0처럼 판정)는 **D-GOV-012 ①과 일치하는 범위**(다른 세대에 표지·CONFLICT·UNKNOWN이 없을 때)에서만 맞다. Fixture는 그 범위 안이다 |
| ReacquisitionResultDbSpec RR-1 | 유효(R-2 조건부) | M09. 결과 헬퍼 거절 사유 `AMOUNT_MISMATCH`(`ReacquisitionResultDbSpec.kt:135`) |
| RR-2 | 유효(R-2 조건부) | M10 |
| RR-3·RR-4 | 유효(R-2 조건부 — 같은 헬퍼) | M16 전제 |
| ReacquisitionExecutionDbSpec RX-1·RX-2·RX-3·RX-13 | 유효(R-2 조건부) | 헬퍼 사유 `AMOUNT_MISMATCH`(`ReacquisitionExecutionDbSpec.kt:156`) |
| RX-4·RX-4U | 유효 | |
| RX-6·RX-11·RX-12 | 유효 | §3.6 선택지 ②면 RX-12의 잠금 순서 전제를 다시 검토(기대값이 아니라 시험 설계 — 구현 명세) |
| RX-7 | **유효(불변)** | 대기 사유 `ORIGINAL_AWAITING_RESULT`(`ReacquisitionExecutionDbSpec.kt:265`)는 EAS PENDING → 어댑터로 같은 값. 바꾸지 않는다 |
| RX-8·RX-10·RX-14 | 유효 | |
| ReacquisitionRuleSpec RQ-1 | 입력 형태 변경(R-2 조건부) | 도메인 `ReacquisitionTarget`에 거절 사유 칸이 없다(`ReacquisitionRule.kt:112-118`). 사유 조건을 넣으면 Fixture에 사유가 생긴다 |
| RQ-2 | 유효(R-2 ② 권장이면) | UNKNOWN 거절 그대로 |
| RQ-4·RQ-5·RQ-7·RQ-8 | 입력 형태 변경 + **기존 거절 코드 유지** | `TARGET_NOT_REACQUIRABLE`·`NOT_LATEST_GENERATION`·`TARGET_CHANGED`·`ALREADY_ACCEPTED`를 EAS 판정 뒤에도 같은 경우에 같은 코드로 돌려준다(새 차단 사유는 새 코드로 따로 둔다) |
| RQ-9 | 유효 | |
| ReacquisitionExecutionRuleSpec EX-1~EX-7 | 입력 형태 변경(EX-1 R-2 조건부) | |
| ReacquisitionBadgeRuleSpec | 입력 형태 변경 | M15 조합의 기존 단언 없음 |
| AcquisitionFileViewReaderSpec | 유효(R-2 조건부) | 결과 헬퍼 기본 사유 `AMOUNT_MISMATCH`(`AcquisitionFileViewReaderSpec.kt:164`) — 배지 CANDIDATE 단언이 사유 조건에 걸릴 수 있음 |
| AcquiringOperationsDbSpec | 유효(R-2 조건부) | 결과 헬퍼 사유 `AMOUNT_MISMATCH`(`AcquiringOperationsDbSpec.kt:155`) |
| internal-admin AcquiringOpsApprovalHttpSpec·FinancialOpsGatewaySpec(stub `StubFinancialOpsServer`) | 유효(R-2 조건부) | 새 거절 코드가 FINANCIAL 응답으로 나오면 ADMIN 허용 문법·stub 값에 추가가 필요할 수 있음(추가만) |
| RecordResultRuleSpec RR-1~RR-8 | 유효 | 레코드 수준 |
| AcquirerResponseDbSpec AQ-R14 | 유효 | M13 전제(취소 단언 없음) |
| AQ-X1 | 유효 | |
| AcquisitionTransmissionRuleSpec AQ-TD6 | 유효 | |

**충돌 후보 목록(사용자 보고 대상)**:
1. **CC-2**(`CancelCandidateRuleSpec.kt:36-37`)와 **CC-8**(`:90`): 판정 인자를 EAS로 바꾸면서 `Reconcile`이 실어 나르는 타입을 바꾸면 기대 리터럴이 바뀐다. 기존 판정 타입 유지 어댑터를 구현 조건으로 두면 충돌 없음.
2. **R-2를 "사유 목록 제한"으로 답하는 경우**: `AMOUNT_MISMATCH`가 목록 밖이면 RR-1~RR-4, RX-1·RX-2·RX-3·RX-13, AcquisitionFileViewReaderSpec, AcquiringOperationsDbSpec의 재매입·배지 기대가 충돌한다. RQ-1·EX-1은 Fixture 추가(입력 형태 변경), ADMIN HTTP 시험은 추가만. R-2 권장안이면 충돌 없음.
- 확정 충돌(구현 방식과 무관하게 기대값이 반드시 바뀌는 시험): **0건**.

### E.3 (0.2b §10) 신규 시험 시나리오(ARC-*)

명시 Fixture 우선(Test Fixture Policy — 세대·결과·금액은 Named Fixture, 연관 상태 독립 random 금지). 자연 도달 불가 조합은 명시 상태 Fixture로 만든다(L-1 선례). R-1 행의 시험은 답이 온 뒤 기대값을 정한다(그 전에는 "현행 유지"만 고정).

| ID | 계층 | Given | When | Then | 매트릭스 |
|---|---|---|---|---|---|
| ARC-01 | Domain | 매트릭스 25행의 이력 조합 | `EffectiveAcquisitionRule` | 행마다 EAS·유효 세대·의심 등록 여부 | 전 행 |
| ARC-02 | Domain | 같은 조합 | 취소 판정·재매입 판정 | 두 판정이 같은 EAS를 쓰고 매트릭스와 같다(한쪽만 바뀌면 RED — P-27) | 전 행 |
| ARC-03 | DB | g0 REJECTED → g1 실행 → g0 상충(관찰 ACC·REJ 각각) → g1 REJECTED | 취소 봉인 | 대사 보류, 불요 0, 대사 경보, 의심 등록 0 | M15 |
| ARC-04 | DB | ARC-03 상태 | g2 재매입 실행 | 차단(새 거절 코드), 봉인 없음, 결정 기록 1 | M15 |
| ARC-05 | DB | g0 관찰 ACC/REJ + g1 결과 대기 | 취소 봉인 | 대사 보류(대기 아님) | M14 |
| ARC-06 | DB | g0 표지 + g1 WITHHELD(명시 상태) | 전체취소 봉인 | CANCEL = 원승인 − g0 상계(경계), 1원 초과 INCONSISTENT | M16 |
| ARC-07 | DB | g0 표지 + g1 REJECTED(명시 상태) | 취소 봉인 | CANCEL 전송, 불요 0, 의심 등록 0 | M18 |
| ARC-08 | DB | M13·M17·M19·M20·M21 각 상태 | 취소 봉인 | R-1 답 전: 현행 취소 동작의 회귀 고정만(M13 전송·M17 대기·M19~M21 대사 보류). 의심 등록 기대는 등록 기능 구현 시 추가. 답 뒤 기대값은 새 시험으로 | R-1 행 |
| ARC-09 | DB | g1 파일 격리 / 결과 마감 뒤 TRANSMISSION_UNKNOWN | 취소 봉인 | 대사 보류, 경보 | M23 |
| ARC-10 | DB | g1 ACCEPTED, 실행 전 상계 3,000 | 부분취소 2,000 → 3,000 → 2,000 세 회차 | CANCEL 3건(누적 7,000), 네 번째 1원 INCONSISTENT + 알림(§6.2) | M09 반복 부분 |
| ARC-11 | DB | g0 REJECTED, 반복 부분취소 합 = 원승인 | 봉인 → 재매입 실행 | 불요 3건, 순액 0 → 재매입 불요 | M03·M07 |
| ARC-12 | DB 동시성 | 같은 원승인, 봉인 TX vs g1 ACCEPTED 응답 TX(실제 두 연결) | 동시 커밋 | 대기 또는 CANCEL 중 하나, 불요 0, CANCEL ≤ 1 | M08 → M09 |
| ARC-13 | DB 동시성 | g0 UNKNOWN(명시 상태) 재매입 실행 vs g0 늦은 ACCEPTED 응답 | 동시 | 실행 차단 또는 실행 뒤 g1 보류(RR-4 경로) 중 하나, 이중 전송 0 | M04 → M16 |
| ARC-14 | DB 동시성 | 같은 취소, 같은 lane 봉인 두 회차 동시 | 동시 | 상계·CANCEL·불요 중 정확히 하나 | P-14 |
| ARC-15 | DB 멱등 | 같은 재매입 승인 ID 재전송(같은 내용·다른 내용) | 실행 | 같은 결과 / 충돌 거절, 세대 하나 | M10 |
| ARC-16 | DB | CANCEL 포함 파일 TRANSMISSION_UNKNOWN | 재전송 회차 | hash MATCH → 재업로드 없음, CANCEL 수 불변 | 중복 취소 매입 |
| ARC-17 | 읽기 전용 탐지 | 명시 상태로 만든 M15·M18 '불요' DB / 정상 재매입 성공(M03 → M09) DB | 누락 의심 조회(아래 SQL) | 탐지 1건씩 / **0건** | M24 |
| ARC-18 | DB | 취소 매입 생애 | CANCEL 전송 → ACCEPTED → (대사 MATCH Fixture) | 전송·성공 확정·대사 완료 구분(대사 완료는 RECON 뒤) | §5.2 |
| ARC-19 | 화면 Reader | M15 상태 | 배지 조회 | CANDIDATE 아님(Reader SQL ↔ Domain 대조) | M15 |
| ARC-20 | 알림 | PENDING·INCONSISTENT·UNREPRESENTABLE 대기, 격리 CANCEL 파일 | 알림 회차(Demo 기한 Fixture) | 운영 알림 1, 대사 전환 표시 | §6.2 |
| ARC-21 | DB 동시성(실제 두 연결) | g0 REJECTED + g1 REJECTED, 전송 후 취소 1건 | 봉인 TX(취소 판정)와 g0 상충 ACCEPTED 응답 TX를 barrier로 동시에 | 직렬화 뒤: 불요 0(대사 보류) **또는** 불요 1 + 같은 TX 순서상 응답이 뒤라 누락 의심 탐지 1. "불요 1 + 탐지 0"이면 RED. 직렬화 장치를 제거하는 변이 → RED | §3.6 (a), M15 |
| ARC-22 | DB 동시성(실제 두 연결) | g0 REJECTED + g1 REJECTED, g2 재매입 승인 | 재매입 실행 TX와 g0 상충 ACCEPTED 응답 TX 동시 | 실행 차단(g2 봉인 0) **또는** 응답이 실행 뒤에 직렬화되어 g2 파일 전송 점유가 EAS UNRESOLVED로 전송 보류·대사. g2가 그대로 전송되면 RED | §3.6 (b), M15 |

**ARC-17 탐지 조회**(실행하지 않음):
- Demo·TEST FINANCIAL DB의 조회 전용 계정으로 SELECT만 한다. 운영 DB에는 쓰지 않는다.
- 결과는 거래 ID·파일 ID·순번·금액만 본다.
- 열 이름은 V5·V10·V14로 확인했다(`V10__acquisition_response.sql:79-87`).
- §3.4 조건 1("순액에 반영 안 됨")은 표지 세대 ≤ 근거 세대(A) 또는 CONFLICT 존재(B)로 근사한다. 표지 세대 > 근거 세대인데 순액에 빠진 경우는 기존 I-17 대조(`reacquisitionNettingMismatches`, 지표 `integrity.reacquisition_netting_mismatch`)가 잡는다.

```sql
-- (A) M18형: '불요'의 근거 레코드 세대 이하에 매입 확정 표지가 있다(그 '불요'는 유효 세대 순액에 반영되지 않음).
SELECT n.cancellation_transaction_id, n.original_transaction_id, n.original_file_id, n.original_seq, r.generation AS basis_generation,
       a.file_id AS accepted_file_id, a.seq AS accepted_seq, a.generation AS accepted_generation, n.amount_minor, n.decided_at
FROM acquisition_cancel_not_required n
JOIN acquisition_file_record r ON r.file_id = n.original_file_id AND r.seq = n.original_seq
JOIN acquisition_accepted_record a ON a.transaction_id = n.original_transaction_id AND a.record_type = 'SALE'
WHERE a.generation <= r.generation;

-- (B) M15형: 같은 원승인의 SALE 레코드(근거 레코드 자신 포함) 중 CONFLICT 결과 행이 있는 것이 있다.
SELECT n.cancellation_transaction_id, n.original_transaction_id, n.original_file_id, n.original_seq, r.generation AS basis_generation,
       r2.file_id AS conflict_file_id, r2.seq AS conflict_seq, r2.generation AS conflict_generation, n.amount_minor, n.decided_at
FROM acquisition_cancel_not_required n
JOIN acquisition_file_record r ON r.file_id = n.original_file_id AND r.seq = n.original_seq
JOIN acquisition_file_record r2 ON r2.transaction_id = n.original_transaction_id AND r2.record_type = 'SALE'
WHERE EXISTS (SELECT 1 FROM acquisition_record_result x WHERE x.file_id = r2.file_id AND x.seq = r2.seq AND x.result = 'CONFLICT');
```

- (A)와 (B)는 겹칠 수 있다. 원승인·취소 거래 ID로 합쳐서 본다.
- 결과 0건은 "누락 없음"의 증거가 아니다. 결과가 아직 없는 세대가 나중에 상충할 수 있다. 그래서 매 영업일 탐지한다(§6.2 Demo).


### E.4 (0.2b §6) 무기한 대기 방지(P-05)

#### E.4.1 기존 결정으로 정해진 것

| 장치 | 내용 | 근거 |
|---|---|---|
| 회차별 재조회 | 대기·대사 보류 취소는 다음 봉인 회차마다 다시 판정 | ACQUIRING-001 I-47, `AcquisitionSealService.kt:226-283` |
| 늦은 결과 자동 반영 | 마감 뒤 유효 결과는 UNKNOWN 행을 보존한 채 반영 | D-ACQ-010, BR-FIN-036 |
| 결과 마감 → UNKNOWN | TRANSMITTED 파일만. 전송 완료 + `results.deadline` 뒤 결과 없음 → UNKNOWN → 대사(자동 재전송 없음). 설정이 없으면 UNKNOWN을 만들지 않음(fail-closed) | D-ACQ-004, ACQUIRING-001 I-49, `AcquiringResultSettings.kt`, `AcquisitionResultCloseService.kt:45-46·73` |
| 전송 결과 모름 회복 | 확인 우선 재시도(최종명 hash MATCH면 재업로드 없음) | ACQUIRING-001 전송 회복(`AcquisitionTransmissionService` upload `Check.MATCH`) |
| 화면 '확인 필요' | RESULT_OVERDUE·TRANSMISSION_WITHHELD 등 파일 단위 | ACQ-VIEW-001 I-11, 확인 필요 사유 표(`ACQ-VIEW-001-ACQUIRING-VIEW.md:159`) |
| 이 정책으로 정해지는 것 | 결과가 올 정상 경로가 없는 상태(격리, 결과 마감 뒤 TRANSMISSION_UNKNOWN)는 대기가 아니라 UNRESOLVED(대사 보류 + 경보) | P-03·P-05, §3.2 ⑤ |

#### E.4.2 알림 대상과 기한 — Demo 값 기록(운영값은 LIVE Gate, 질문에서 뺌)

ACQUIRING-001 §14-16("지표 경보 임계·대기 체류는 운영값 아님")과 같은 취급이다. 아래는 **Demo 값**이고 운영값은 LIVE Gate에서 실제 기관 결과 회신 기한과 함께 정한다. 판정(전송·불요·대사)은 바꾸지 않고 알림·전환 표시만 정한다.

| 알림 대상 | Demo 값 |
|---|---|
| PENDING 취소 대기 체류 | 취소 수신 뒤 영업일 2일이면 운영 알림, 5일이면 대사 전환 표시(판정은 계속 회차마다) |
| 거래 단위 대기 **INCONSISTENT·UNREPRESENTABLE**(값 불일치·표현 불가 — 자연 해소가 드묾) | 첫 대기 즉시 운영 알림, 영업일 1일이면 대사 전환 표시 |
| UNRESOLVED·이중 매입 의심 등록 미처리 | 영업일 1일 안에 대사 착수, 아니면 상향 알림 |
| 봉인 뒤 전송 안 된 파일(SEALED 체류) | 다음 정기 마감 시각까지 미전송이면 알림 |
| CANCEL 포함 파일 전송 격리 | 즉시 알림 + 대사 |
| 결과 마감 뒤 TRANSMISSION_UNKNOWN | 결과 마감과 같은 기한으로 UNRESOLVED |
| 결과 마감 설정 없음 | 기동 경보(지금은 error 로그만 — `AcquisitionResultCloseService.kt:45-46`) |
| 누락 의심 탐지(ARC-17) | 매 영업일 1회 |

### E.5 (0.2b §7) '별도 정의' 항목(이 문서에서 정하지 않음)

| ID | 항목 | D-GOV-012 근거 | 후속 위치(제안) | 질문 후보 |
|---|---|---|---|---|
| F-1 | 미확정 매입·취소(PENDING·UNRESOLVED·**이중 매입 의심**·누락 의심) 때문에 가맹점에 과다 정산·지급이 생기지 않게 하는 정책(보류 대상 금액·해제 조건·정산일 영향) | ② "별도 정의". ③의 거래 단위 지급 보류는 누락 의심에 한함 | 정산 명세(SETTLEMENT-CONTRACT-001·SETTLEMENT-001 — 아직 정산 코드 없음) | 의심 건 지급 보류 여부·금액 |
| F-2 | 이미 지급된 거래의 환수 | ③ "별도 환수 정책" | 정산·지급 명세 | 환수 수단·순서·통지 |
| F-3 | 매입사별 취소 매입 식별 방식(지금은 파일 v1 원거래 ID·원 기관 거래번호) | ② | 매입 기관 연동(LIVE Gate) | — |
| F-4 | 매입사 응답 규격 검증(결과 코드·재발행·늦은 결과 수용 기간·취소 결과 의미) | ② | ACQUIRING-001 LIVE Gate §14 | — |
| F-5 | 누락 의심·이중 매입 보정의 수단(정정 CANCEL, 중복 매입 보정 레코드, 수기 정산 기록)과 2인 승인 기록 | ③, ② "일반 취소와 중복 매입 보정 구분" | 대사·Adjustment 명세(RECON) | — |
| F-6 | 대사(RECON): 미매입 확정, 이중 매입 확인, 대사 완료(MATCH) | ①·② | RECON 작업 | — |
| F-7 | 취소 매입 실패(CANCEL REJECTED·UNKNOWN·CONFLICT·격리)의 처리. 정책은 BR-FIN-050(확정)이고 구현은 ACQ-ADMIN-002. 실패 금액의 한도 반영은 현행 유지(M25 주) | ② "성공 확정 구분" | ACQ-ADMIN-002 | — |
| F-8 | 재매입 가능 실패 사유 목록(매입사 사유 코드 분류) | ③ | F-4 + R-2 | R-2 |
| F-9 | §3.6 직렬화 방식(선택지 ①·②) | ③ "멱등성·동시성 제어" | 구현 명세(가역 — 금융 의미는 P-25가 정함) | — |

### E.6 (0.2b §8) 기존 규칙 정합표(대장 변경안 — 적용은 Coordinator)

| 행 | 새 정책과의 관계 | 대장 변경안 문장(제안) |
|---|---|---|
| BR-FIN-016 | 유지(세대 0 봉인 전 단계) | 변경 없음 |
| BR-FIN-036 | 유지(EAS 재계산의 입력) | 변경 없음 |
| BR-FIN-037 | 유지(표지 = 유효 성공의 유일한 근거) | 변경 없음 |
| BR-FIN-038 | 유지 + 보완(**R-1 미결**) | 덧붙임: "격리된 두 번째 ACCEPTED(관찰 ACCEPTED)가 있으면 그 원승인은 이중 매입 의심으로 등록하고 대사한다. 그때 표지 세대 기준 취소 매입을 할지는 R-1 답에 따른다(ACQ-REACQ-CANCEL-001)." |
| BR-FIN-039 | 보완(원 SALE = EAS) | "전송 후 취소의 CANCEL 레코드는 원승인의 EAS가 ACQUIRED일 때 보낸다: PENDING이면 자동 대기, UNRESOLVED이면 대사 보류, 모든 매입 이력이 실패로 확정(NOT_ACQUIRED)일 때만 '취소 매입 불요'로 기록한다. ACQUIRED_CONTESTED는 R-1." |
| BR-FIN-040 | **대체**(**R-1 미결 표기**) | "재매입 여부와 관계없이 전송 후 취소는 원승인의 전체 매입 이력으로 계산한 EAS로 판정한다. 유효 매입 성공이 하나로 식별되고 상충이 없을 때만 그 세대 기준으로 CANCEL을 보내고, 식별할 수 없으면(CONFLICT·UNKNOWN·성공 레코드의 상충 등) 대사 보류한다. 유효 성공이 식별되었는데 다른 세대에 UNKNOWN·CONFLICT가 있으면 이중 매입 의심으로 등록·대사하며, 그때의 취소 전송 여부는 미결(R-1 — 답 전 현행 동작)이다(D-GOV-012 ①②)." 상태 `확정`(R-1 부분 제외), 출처 `사용자`, 근거 D-GOV-012. **검증란: CC-8 제거, ARC-01~03·05~09·17 등 신규 시험으로**(CC-8은 §9처럼 D-GOV-012 ①과 일치하는 범위의 기존 시험으로만 남음) |
| BR-FIN-041 | 보완 — **해석 표시** | "… 원 SALE 상계액은 EAS가 가리키는 유효 세대 레코드의 상계액이다(Coordinator 해석 — D-GOV-012 ① '그 매입 기준'에서 끌어냄)." |
| BR-FIN-046 | 유지 | 변경 없음 |
| BR-FIN-047 | 유지 | 변경 없음 |
| BR-FIN-048 | 유지 + 보완 | 덧붙임: "미매입 확정 전 UNKNOWN 이력은 어느 세대에 있든 그 원승인의 재매입을 차단한다(D-GOV-012 ③). 대사로 미매입이 확정된 UNKNOWN은 실패 확정으로 본다(R-2 ②)." |
| BR-FIN-049 | 대체(확장) — **일부 해석 표시** | "원승인의 매입 이력 어디에든 CONFLICT·UNKNOWN·처리 진행 중·유효 매입 성공이 있으면 재매입하지 않는다. 재매입은 매입 미성립 확정(EAS NOT_ACQUIRED)이고 재매입 가능한 실패 사유일 때만 한다(D-GOV-012 ③). 대상은 그 거래·종류의 최고 세대 레코드다(Implementer 해석 ACQUIRING-001 I-50 유지 — Coordinator 확인)." |
| BR-FIN-050 | 보완 | "… 원승인 EAS가 ACQUIRED일 때만 …"(구현 ACQ-ADMIN-002) |
| BR-FIN-051 | 유지 | 덧붙임: "보류된 세대는 EAS에서 '보내지 않은 이력'으로 보며 의심을 만들지 않는다." |
| BR-FIN-052 | 미결 유지(Q-ACQA-10) | 변경 없음(보류 해소 의미는 EAS와 함께) |
| BR-FIN-053 | 유지 | 변경 없음 |
| BR-FIN-138 | 유지(거래 단위 대기 — EAS보다 먼저). 재매입 실행의 PAYMENT_HELD(Payment 전체) 거절과는 범위가 다르다 — BR-FIN-138 정비 때 함께 본다 | 변경 없음 |
| (신규) | P-18~P-21 | "취소 매입 누락 의심 거래('불요'의 근거 세대 이하에 매입 확정 표지가 있거나, 그 원승인의 어느 SALE 세대에든 CONFLICT가 있는 것. 표지 세대가 근거 세대보다 높은데 순액에서 빠진 경우는 재매입 상계 무결성 대조가 맡는다)는 읽기 전용 조회로 식별하고 자동 보정하지 않으며, 대사·2인 승인을 거쳐 필요할 때만 append-only로 보정한다. 지급 보류는 그 거래 단위, 이미 지급된 거래는 별도 환수 정책(D-GOV-012 ③)." |
| (신규) | P-10·P-12·P-13 | "이중 매입 의심은 유효 성공이 식별되고 다른 세대에 UNKNOWN·CONFLICT가 있을 때 원승인 단위로 등록·대사하며, 복수 성공이 확인되면 일반 취소와 중복 매입 보정을 구분한다. 취소 매입은 전송·성공 확정·대사 완료를 구분해 관리한다(D-GOV-012 ②)." |
| (신규) | P-25 | "EAS를 읽어 판정하는 봉인·재매입 실행·재매입 전송 점유와 결과 반영은 원승인 단위로 직렬화한다(방식은 구현 명세)." |

