package com.paycore.master.merchant.application.required

/**
 * 저장소 Port가 던지는 기술 실패(payment-api와 같은 관례, ARCHITECTURE-CONVENTIONS §9). persistence adapter가 Port 메서드 안에서 flush해
 * DB/JPA 예외를 이 종류로 바꾼다. application은 `org.springframework.dao`·JDBC 예외를 알지 않는다. 메시지에 키 값을 넣지 않는다.
 * 이 예외가 나면 같은 TX는 더 쓸 수 없다 — 호출자는 TX 전체를 rollback하고, 다시 시도한다면 새 TX에서 처음부터 같은 의도로 한다.
 */
sealed class PersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {

    /** UNIQUE/PK 위반. [constraint]가 어떤 업무 키인지 알려준다(원문 값은 담지 않는다). */
    class UniqueConflict(val constraint: StoreConstraint) : PersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : PersistenceFailure("concurrent modification of $entity", null)

    class Deadlock(cause: Throwable) : PersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable) : PersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable) : PersistenceFailure("database unavailable", cause)
}

/** UNIQUE/PK 제약의 의미. [businessKey]가 참이면 업무 유일성(동시 요청 경합 → 새 TX에서 재판정하면 업무 거절로 수렴), 거짓이면 서버 생성 ID 충돌. */
enum class StoreConstraint(val businessKey: Boolean) {
    BUSINESS_REGISTRATION_NUMBER(true),
    SITE_ACTIVE_CHANNEL(true),
    DEFAULT_DESIGNATION(true),
    ASSIGNMENT_OPEN(true),
    AGREEMENT_SERVICE_KIND(true),
    ACTIVATION_METHOD(true),
    CONTRACT_REVISION(true),
    REVISION_APPROVAL(true),
    ADMIN_REQUEST_SCOPE(true),
    CURRENCY_OPEN(true),
    ACTIVATION_EVENT(true),
    SITE_REVIEW(true),
    CONFIRMATION_REVOCATION(true),
    ENTITLEMENT_ISSUE(true),
    SITE_CONFIRMATION(true),

    // MASTER-SNAPSHOT-002: 열린 정책·Assignment·카드사 개통 하나, 정책 버전 번호, 개통 이력 순번.
    LIMIT_POLICY_OPEN(true),
    POLICY_VERSION_NO(true),
    POLICY_ASSIGNMENT_OPEN(true),
    CARD_ROUTE_OPEN(true),
    CARD_ROUTE_EVENT(true),

    // MERCHANT-OPS-002 §9.3(B2-2 V9): 승인 근거 PK(approvalId 1회 사용 — 동시 경합은 새 TX 판정 3 `APPROVAL_ID_ALREADY_USED`로 수렴, review m-1).
    APPROVAL_EVIDENCE(true),

    // MERCHANT-CODE-001 §3.2(C-25 — V13 배정 표): 코드 전역 유일(현재·은퇴 — 경합 진 쪽은 새 TX 사전 조회로 `MERCHANT_CODE_TAKEN` 수렴), (merchant_id, seq) PK(1회 변경 — MC-1b),
    // approvalId 1회 사용(MC-1b). 모두 업무 유일키 → 실행기 새 TX 재판정.
    MERCHANT_CODE(true),
    MERCHANT_CODE_SEQ(true),
    MERCHANT_CODE_APPROVAL(true),

    // BUSINESS-PROFILE-001 §5.2 끝(M-4·C-34): 사업자 하위 표의 현재 행 UNIQUE 7개(등록정보 1:1·대표자/담당자 현재 순서·기본 담당자·주소 유형·업태/종목 순서·이름).
    // **업무 유일키가 아니다** — Domain 규칙(§4.3)과 business FOR UPDATE 직렬화가 먼저 막으므로 위반은 diff·flush 순서 결함 또는 잠금 우회다.
    // 결함 — 재시도·재판정 없음(실행기 새 TX 재판정 대상 아님 → 503 TEMPORARILY_UNAVAILABLE + 결함 로그(제약 이름만)).
    BUSINESS_PROFILE_ROW(false),

    // MERCHANT-FLOW-001 §4A.3(V19): 접수 건 열린·완료 건 하나(uk_cin_active)·(사업자, 순번) PK·전이 이력 PK. **업무 유일키가 아니다** — Domain 생성 조건과 사업자 행
    // FOR UPDATE 직렬화가 먼저 막으므로 위반은 순번 부여 결함 또는 잠금 우회다(BUSINESS_PROFILE_ROW와 같은 결함 분류 — 재시도·재판정 없음).
    CONTRACT_INTAKE_ROW(false),
    IDENTIFIER(false),
    OUTBOX_EVENT_ID(false),
    OUTBOX_EFFECT_KEY(false),
    OTHER(false),
}
