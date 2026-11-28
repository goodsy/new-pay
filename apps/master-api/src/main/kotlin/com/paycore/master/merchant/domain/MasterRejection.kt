package com.paycore.master.merchant.domain

/**
 * MASTER 업무 규칙 거절 사유. application이 관리 명령의 **업무 거절 결과**로 저장·재생한다(같은 키 재요청에 같은 거절). 이름은 영속 값이라
 * 바꾸지 않는다(추가만).
 */
enum class MasterRejection {
    BUSINESS_NOT_FOUND,
    BUSINESS_NOT_ACTIVE,
    BUSINESS_REGISTRATION_NUMBER_DUPLICATE,
    MERCHANT_NOT_FOUND,
    MERCHANT_NOT_ACTIVE,
    SITE_NOT_FOUND,
    SITE_NOT_ACTIVE,
    SITE_CHANNEL_DUPLICATE,
    SITE_OF_OTHER_MERCHANT,
    CONTRACT_NOT_FOUND,
    CONTRACT_PARTY_MISMATCH,
    SCOPE_MERCHANT_OF_OTHER_BUSINESS,
    SCOPE_SITE_OF_OTHER_MERCHANT,
    REVISION_NOT_FOUND,
    REVISION_ALREADY_APPROVED,
    REVISION_NOT_APPROVED,
    REVISION_ENDED,
    SITE_OUTSIDE_CONTRACT_SCOPE,
    AGREEMENT_NOT_FOUND,
    SERVICE_KIND_DUPLICATE,
    DEFAULT_ALREADY_ISSUED,
    MERCHANT_ACCOUNT_NOT_FOUND,
    MERCHANT_ACCOUNT_NOT_ACTIVE,
    ASSIGNMENT_RETROACTIVE,
    ASSIGNMENT_PERIOD_OVERLAP,
    PRIMARY_ALREADY_EFFECTIVE,
    ACTIVATION_DUPLICATE,
    REVISION_NOT_APPLICABLE,
    CONTRACT_MERCHANT_MISMATCH,
    EFFECTIVE_PERIOD_EMPTY,

    // MASTER-SNAPSHOT-001(D-MD-016~018, D-MS-002·003)
    ACTIVATION_NOT_FOUND,
    ACTIVATION_TRANSITION_NOT_ALLOWED,
    ACTIVATION_EVIDENCE_NOT_NEW,
    ACTIVATION_TERMINATED,
    ASSIGNMENT_NOT_OPEN,
    SITE_CONFIRMATION_DUPLICATE,
    SITE_CONFIRMATION_NOT_FOUND,
    SITE_CONFIRMATION_ALREADY_REVOKED,
    CURRENCY_UNKNOWN,
    CURRENCY_ENABLEMENT_RETROACTIVE,
    CURRENCY_ENABLEMENT_OVERLAP,
    CURRENCY_ENABLEMENT_NOT_FOUND,
    CURRENCY_ENABLEMENT_ALREADY_ENDED,
    SITE_REVIEW_TRANSITION_NOT_ALLOWED,
    PRIMARY_CONFIRMED_BY_APPROVAL,

    // MASTER-SNAPSHOT-002(D-AUTH-006, D-MD-017·018, D-MS2-001~005)
    LIMIT_POLICY_NOT_FOUND,
    LIMIT_POLICY_RETROACTIVE,
    LIMIT_POLICY_OVERLAP,
    LIMIT_POLICY_ALREADY_ENDED,
    LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT,
    POLICY_VERSION_NOT_FOUND,
    POLICY_CONTENT_HASH_INVALID,
    POLICY_KIND_MISMATCH,
    POLICY_ASSIGNMENT_NOT_FOUND,
    POLICY_ASSIGNMENT_RETROACTIVE,
    POLICY_ASSIGNMENT_OVERLAP,
    POLICY_ASSIGNMENT_ALREADY_ENDED,
    POLICY_ASSIGNMENT_AGREEMENT_MISMATCH,
    CARD_ROUTE_NOT_FOUND,
    CARD_ROUTE_DUPLICATE,
    CARD_ROUTE_METHOD_NOT_ACTIVATED,
    CARD_ROUTE_TRANSITION_NOT_ALLOWED,
    CARD_ROUTE_EVIDENCE_NOT_NEW,

    // MERCHANT-OPS-002 §3.4·§9.2(D-MOPS-006 ⑤·D-MOPS-007 ②): 계약(Revision 1)·Revision 등록의 효력 시작이 판정 시각의 KST 날짜 00:00보다 앞(소급 등록 거절)
    CONTRACT_START_RETROACTIVE,

    // MERCHANT-OPS-002 §9.3(B2-2 — D-MOPS-007 ①·D-MOPS-008 ③, D-ADMIN-007): 2인 승인 근거 판정 1~5(저장된 거절 — 영속 이름, 끝에 추가만)
    APPROVAL_SAME_ACTOR,
    APPROVAL_WINDOW_INVALID,
    APPROVAL_ID_ALREADY_USED,
    APPROVAL_EXPIRED,
    APPROVER_IS_REVISION_REGISTRANT,

    // MERCHANT-OPS-002 C-45(rev 0.8.1): 근거 경로 개통 승인의 고정 PRIMARY Site(`primarySiteId`)가 실행 시점 열린 PRIMARY와 다름(없음 포함)
    PRIMARY_SITE_MISMATCH,

    // MERCHANT-OPS-002 §9.27(B3-1F — D-MOPS-009 ③, D-MOPS-010 ②): 1인 등록자 ≠ 2인 승인자. 근거 경로(`approveWithEvidence`)만 — seed 경로는 검사 없음(C-31 구조)
    APPROVER_IS_ACTIVATION_REGISTRANT,
    APPROVER_IS_SITE_REGISTRANT,

    // MERCHANT-CODE-001 MC-1(§3.1·§3.3, C-2·C-18): 가맹점 코드 예약어(완전·접두 일치) / 배정 이력에 이미 있는 코드(사용 중·은퇴 구별 없음 — reference 없음, C-12)
    MERCHANT_CODE_RESERVED,
    MERCHANT_CODE_TAKEN,

    // MERCHANT-CODE-001 MC-1b(§3.7.3 판정 8~10 — D-MCODE-002 Q-MC-7): 이미 1회 변경 / 첫 계약 승인 뒤 불변 / 요청의 현재 코드 ≠ 저장된 현재 코드(정상 경로에서 불가능 — 결함 신호·ADMIN 경보).
    // 원 가맹점 등록자 계열 거절은 만들지 않는다(D-MCODE-002 Q-MC-10 — 요청자≠승인자만).
    MERCHANT_CODE_ALREADY_CHANGED,
    MERCHANT_CODE_LOCKED,
    MERCHANT_CODE_MISMATCH,

    // BUSINESS-PROFILE-001 §6.7(D-BIZ-001·C-12·M-1): 새 가맹점 등록은 REGISTERED 사업자만(호출은 BIZ-4 전환) / 등록 완료는 DRAFT만 / 요청 version ≠ 잠근 행 version(덮어쓰기 거부 — TC-14) /
    // 중요정보 변경 + 사유 없음 / 수정 내용이 저장된 내용과 같음(update만 — 등록 완료에는 적용하지 않음). 모두 reference 없음.
    BUSINESS_NOT_REGISTERED,
    BUSINESS_NOT_DRAFT,
    BUSINESS_VERSION_CONFLICT,
    BUSINESS_CHANGE_REASON_REQUIRED,
    BUSINESS_PROFILE_UNCHANGED,

    // MERCHANT-FLOW-001 §4A.3(FLOW-1 — 순수 Domain 규칙, 명령 경로는 FLOW-2): 새 접수 건 생성 조건(D-FLOW-003 ① 열린 건 ≤ 1), 허용 전이 표 밖·같은 상태·종결 되돌리기,
    // 요청 version ≠ 잠근 접수 건 version(덮어쓰기 거부). 모두 reference 없음. 이름은 [구현 관례](명세 §4A.3 "code 후보").
    // FLOW-2a(§4B.7): `CONTRACT_INTAKE_COMPLETED_EXISTS`는 삭제(D-GOV-014 ① — 완료 건이 있어도 새 접수). FLOW-1에는 생성 명령이 없어 저장된 거절 행이 0이어야 하고
    // V21 첫 단계 guard가 0행을 확인한다(0이 아니면 V21이 1146으로 멈춤 — 재생 호환용 enum 값을 남길 필요가 없다).
    CONTRACT_INTAKE_OPEN_EXISTS,
    CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED,

    /** MERCHANT-FLOW-001 FLOW-2d(§4A.3 — D-FLOW-007 ④, [구현 관례 code]): 종결 건(계약 완료·계약 불가·이관)의 접수 항목 수정. */
    CONTRACT_INTAKE_ITEMS_NOT_EDITABLE,

    /** FLOW-2d([구현 관례 code]): 접수 항목 수정 요청이 저장된 값과 같다(이력·version을 늘리지 않는다). */
    CONTRACT_INTAKE_ITEMS_UNCHANGED,
    CONTRACT_INTAKE_VERSION_CONFLICT,

    // MERCHANT-FLOW-001 §4B.6·§4B.7(FLOW-2a — 순수 Domain 규칙, 명령 경로는 FLOW-2b·2c): 접수 종류 불일치(화면이 본 종류 ≠ 잠금 뒤 판정 — BR-BIZ-325),
    // 전이 입력 Domain 검사(MINOR-1 — 되돌리기·계약 불가 사유 필수, 계약 완료 근거 필수, 그 밖 근거 금지 — BR-BIZ-274·339). 모두 reference 없음. 이름은 [구현 관례].
    CONTRACT_INTAKE_KIND_MISMATCH,
    CONTRACT_INTAKE_REASON_REQUIRED,
    CONTRACT_INTAKE_DOCUMENT_REQUIRED,
    CONTRACT_INTAKE_DOCUMENT_NOT_ALLOWED,
    // FLOW-2b(명령 경로): 명령 대상 접수 건 순번이 그 사업자에 없음 / 접수일이 MASTER 판정 시각의 KST 오늘보다 뒤(§4A.6 n-4 이중 검사). reference 없음. 이름은 [구현 관례].
    CONTRACT_INTAKE_NOT_FOUND,
    CONTRACT_INTAKE_RECEIVED_ON_FUTURE,

    // MERCHANT-FLOW-001 §4B.4·§4B.7(FLOW-2a — 사업자 계약 순수 Domain): 대상 식별 틀림(최신 아님 포함 — 명령은 2b·2c) / 변경 완료 대상 해지·만료(BR-BIZ-333) /
    // 신규 완료인데 계약 있음(BR-BIZ-315 방어) / 예정 버전 있음(BR-BIZ-345) / 시작일 소급(BR-BIZ-335) / 기간 위반(BR-BIZ-317·346) / 범위 없음(BR-BIZ-329) /
    // 전이표 '거절' 칸(BR-BIZ-336·342·344) / 기대 사건 수 불일치. 모두 reference 없음. 관문 거절 `BUSINESS_CONTRACT_NOT_EFFECTIVE`는 FLOW-3이 더한다.
    BUSINESS_CONTRACT_NOT_FOUND,
    BUSINESS_CONTRACT_NOT_CHANGEABLE,
    BUSINESS_CONTRACT_EXISTS,
    BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS,
    BUSINESS_CONTRACT_START_RETROACTIVE,
    BUSINESS_CONTRACT_PERIOD_INVALID,
    BUSINESS_CONTRACT_SCOPE_INVALID,
    BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
    BUSINESS_CONTRACT_STATE_CONFLICT,

    // MERCHANT-FLOW-001 §4B.8(FLOW-3): 새 가맹점 등록 관문 — 최신 사업자 계약이 판정 시각에 유효가 아니거나 적용 중 버전 범위에 카드 PG 결제가 없음(사유별 code 없음 — 화면은 사업자 상세 링크).
    BUSINESS_CONTRACT_NOT_EFFECTIVE,

    // BIZ-NORM-002 M-3(BR-BIZ-311, D-GOV-005 ②): 사용중지(INACTIVE) 사업자의 정보 수정(update) 거절. reference 없음. 등록 완료는 그대로 `BUSINESS_NOT_DRAFT`.
    // 추가만(기존 저장 행 영향 없음 — `admin_request.result_code` VARCHAR(48), 값 집합 CHECK 없음).
    BUSINESS_INACTIVE,

    // BIZ-CRIT-APPROVAL-001 §1.1·§3.3·§4.1(D-GOV-016 ③·D-GOV-025·D-GOV-026): 1인 경로에 2인 칸 변경(BR-BIZ-373·375) / 차단 칸 — 이력 있는 사업자의 기존 값 구분·법인등록번호 변경(BR-BIZ-385) /
    // 제안 경로에 2인 칸이 아닌 변경 / 제안 요청자 ≠ 근거 요청자(M-6 — BR-BIZ-398) / 제안 ↔ 승인 명령 불일치(businessId·baseVersion·proposalId·digest). 모두 reference 없음. 추가만.
    BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED,
    BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED,
    BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED,
    PROPOSAL_REQUESTER_MISMATCH,
    PROPOSAL_CONTENT_MISMATCH,
}

/**
 * MASTER 업무 규칙 위반. [reference]는 거절 판단의 근거가 된 기존 대상 ID(예: 이미 발급된 Default MID)이며 없을 수 있다.
 * 메시지에는 사유 이름만 넣는다(입력값·등록번호 없음).
 */
class MasterRuleViolation(val rejection: MasterRejection, val reference: String? = null) :
    RuntimeException("master rule violation: $rejection", null, false, false)
