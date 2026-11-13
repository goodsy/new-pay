# ADMIN-UI-MERCHANT-002 — 관리자 화면 Demo 피드백 반영 (구현 기록)

- 결정: [D-ADMIN-UI-004](../DECISIONS.md) (2026-11-13). 범위: `apps/internal-admin/frontend/src/**` 화면만. 서버 API·역할·payload·감사 변경 없음.
- 상태: 구현·관련 시험 통과(Implementer). DONE 판정은 Verifier/Reviewer.

## 반영 내용

| # | 피드백 | 화면 반영 | 근거·주의 |
|---|---|---|---|
| 1 | 글꼴·스타일 (2026-11-13: 처음의 전역 상향·사이드바 15px bold 취소 → 갱신된 시안 `pg-ops-workspace`에 맞춤) | 글꼴 `-apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic"`; body 14px/21px #334155, 제목 #0f172a(`heading` 토큰), muted #64748b, 선 #e2e8f0; 사이드바 항목 14px/500 #475569·선택 700 #1d4ed8+#edf4ff, 그룹 제목 11px; 브랜드 21px/700; h1 28px/700 −1px; 설명 12px/20.4px; 카드·화면 제목 17px/700; 버튼 13px/600; 표 th 13px/600 #475569 배경 #f9fafc 13px 20px, td 14px 17px 20px; 배지 12px/600 5px 7px 파랑·주황·초록 칩; 입력 13px(데스크톱) | 웹폰트·CDN 없음(로컬 글꼴만), CSP 불변. 휴대폰 폭(md 미만) 입력·선택 16px 유지(iOS 확대 방지). 초록 칩 글자는 시안 rgb(57,161,119)이 #ebf6f0 위 약 2.9:1이라 같은 계열 #237a57(약 4.9:1)로 둠. 다크 모드 토큰 함께 정의 |
| 2 | 사업자/가맹점 등록 분리 | `사업자·가맹점` 메뉴 탭: 가맹점 조회 / **사업자 등록**(`BusinessRegistrationScreen`) / **가맹점 등록**(`MerchantRegistrationScreen` ① 사업자 선택 ② 가맹점·사이트 ③ 계약 초안·서비스 계약 ④ 검토). 쪽 머리 '＋ 사업자 등록' 버튼 제거 | 둘 다 OPERATOR만(D-MOPS-006 ① 그대로). 저장 호출·body·submissionId·결과 불명 잠금·재전송 규칙 그대로(`RegistrationStep`) |
| 2-a | 기존 사업자 고르기 | 가맹점 등록 ①: 기존 `merchantSearch({ registrationNumber })`(정확 일치·매 시도 감사·속도 제한 — D-MOPS-003) 결과를 사업자별로 묶어 "선택". 사업자 등록 성공 뒤 "이 사업자로 가맹점 등록"은 내부 키를 메모리로 넘김(`offerBusiness`) | **한계**: 가맹점이 0개인 기존 사업자는 이 검색으로 못 찾음(아래 미결) |
| 3 | 하이픈 자동 | `RegistrationNumberInput`: 표시 `000-00-00000`, 상태·서버 값·검사는 숫자 10자리 그대로. 변환 위치 = 입력란 onChange(입력→숫자)/value(숫자→표시) | 붙여넣기 하이픈·공백 제거, 11자리 이상은 잘라 맞추지 않음(형식 오류), maxLength 없음. 하이픈 앞뒤 Backspace/Delete는 옆 숫자 삭제 |
| 4 | 설명 최소화 | 입력 항목 아래 긴 hint 제거·축약 | 유지: 필수/형식 오류, 등록번호 검색 감사 안내, 종료일 "이 날 0시 종료(미포함)", 결과 불명·취소 경고, 소급 거절 문구 |
| 5 | 사업자 ID 미표시 | 등록 화면·검토·가맹점 조회 목록/상세에서 `biz_…` 제거, 조회 "사업자 ID" 검색란 제거. 등록번호 검색 결과 "다음"은 결과의 내부 사업자 키로 이어 찾기(번호 재전송 없음) | 내부 키·API·감사 불변. 가맹점 ID(`mer_…`)는 그대로(MERCHANT-CODE-001 전) |
| 6 | 결제수단 목록 구조 | `lib/paymentMethods.ts` `PAYMENT_METHODS`(CARD 하나)·`SERVICE_KINDS`(CARD_PG_PAYMENT 하나). 개통 등록 버튼·서비스 종류 선택을 목록으로 렌더 | 새 결제수단·정책 채택 아님 — 목록 고정 시험(`paymentMethods.test.ts`)이 무단 추가를 막음. 개통 점검표 판정 로직은 그대로(카드) |

## 확정·후속

- **가맹점 없는 기존 사업자 선택 — 확정: D-ADMIN-UI-004 ⑦, 후속 MERCHANT-BIZ-LOOKUP-001.** 이번 작업은 현 상태 그대로다: 서버에 사업자등록번호 → 사업자 조회 API가 없어(가맹점 검색만), 가맹점이 하나도 없는 기존 사업자는 사업자 등록 직후 "이 사업자로 가맹점 등록"으로만 고를 수 있다. 사업자 조회는 후속 작업에서 추가한다.

## 리뷰 반영(조건부 PASS 후속)

- **MINOR-1**: 사업자 등록 → "이 사업자로 가맹점 등록"이 진행 중인 마법사에 섞이지 않게 했다. 결과 불명 카드가 있으면 넘기지 않고 경고, 진행 내용이 있으면 "새로 시작" 확인 뒤 마법사 메모리를 비우고 그 사업자로 ②를 연다(`registrationHandoffState`·`startWithBusiness`). 마법사 안 ① "선택"은 기존처럼 잠김·완료 카드는 건드리지 않고 채운다.
- **MINOR-2**: 개통 등록 one-shot(submissionId)을 수단 버튼들이 공유하므로, 결과 불명 동안 그 수단 버튼만 재전송하고 다른 수단 버튼은 막는다(시험 전용 가상 수단으로 고정 — `MerchantActivationArea.methods.test.tsx`).
  **둘째 결제수단 추가 전 필수**: ① 수단별 one-shot 분리 ② 개통 점검표(`activationChecklist`) ③·④의 'CARD' 고정 판정 정리(수단별 점검 정책 결정 필요).

## 알려진 한계

- **MINOR-4(대비)**: 주 버튼 #3182f6 위 흰 글자 약 3.7:1, 단계 표시 primary 글자 약 3.5:1 — WCAG AA 일반 글자 4.5:1에 못 미친다. 사용자가 지정한 시안 브랜드 색이라 변경하지 않는다(브랜드 색은 `styles.css` `--brand` 한 곳에서 바꿀 수 있다).
