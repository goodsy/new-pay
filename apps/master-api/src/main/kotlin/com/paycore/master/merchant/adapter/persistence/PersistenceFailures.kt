package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * DB/JPA 예외 → [PersistenceFailure] 분류(payment-api와 같은 관례 — CP-DB-001, FOUNDATION round4 L-2). Port 메서드는 본문을 [translate]로 감싸고 쓰기 뒤에는
 * 같은 블록 안에서 flush해 제약 위반이 commit 시점이 아니라 Port 안에서 드러나게 한다.
 *
 * 판단은 예외 클래스 이름이 아니라 원인 사슬의 MySQL vendor 오류 코드·SQLState로 한다(Spring 번역 여부와 무관하게 같은 결과):
 * 1062 중복 → UniqueConflict(키 이름만, 값은 버림) · 1213 교착 → Deadlock · 1205 잠금 대기 timeout → LockTimeout ·
 * SQLState 08xxx/연결 예외 → Unavailable · JPA/Hibernate version 충돌 → OptimisticConflict.
 * 그 밖(CHECK 위반 3819, 길이 초과 1406 등)은 업무 충돌이 아니라 결함이므로 분류하지 않고 원래 예외를 그대로 던진다.
 */
internal object PersistenceFailures {

    fun <T> translate(entity: String, block: () -> T): T =
        try {
            block()
        } catch (e: PersistenceFailure) {
            throw e
        } catch (e: RuntimeException) {
            throw classify(entity, e) ?: e
        }

    fun classify(entity: String, failure: Throwable): PersistenceFailure? {
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            return PersistenceFailure.OptimisticConflict(entity)
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: return null
        return when {
            sql.errorCode == MYSQL_DUPLICATE_KEY -> PersistenceFailure.UniqueConflict(constraintOf(sql.message))
            sql.errorCode == MYSQL_DEADLOCK -> PersistenceFailure.Deadlock(failure)
            sql.errorCode == MYSQL_LOCK_WAIT_TIMEOUT -> PersistenceFailure.LockTimeout(failure)
            isConnectionFailure(sql) -> PersistenceFailure.Unavailable(failure)
            else -> null
        }
    }

    private fun isConnectionFailure(sql: SQLException): Boolean =
        sql.sqlState?.startsWith("08") == true ||
            sql is SQLTransientConnectionException ||
            sql is SQLNonTransientConnectionException ||
            sql is SQLRecoverableException ||
            sql.errorCode in MYSQL_CONNECTION_LOST

    /**
     * MySQL 8 중복 오류 원문 `Duplicate entry '<값>' for key '<table>.<index>'`에서 마지막 key 이름만 읽는다. 값 부분은 쓰지 않는다
     * (등록번호·채널 식별값 등 키 값이 예외 필드·로그로 새지 않게 — D-MD-008).
     */
    fun constraintOf(message: String?): StoreConstraint {
        val key = message?.let { KEY_NAME.find(it)?.groupValues?.get(1) } ?: return StoreConstraint.OTHER
        return CONSTRAINTS[key] ?: StoreConstraint.OTHER
    }

    private const val MAX_CAUSE_DEPTH = 20
    private const val MYSQL_DUPLICATE_KEY = 1062
    private const val MYSQL_DEADLOCK = 1213
    private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205

    /** 2006 server has gone away, 2013 lost connection during query. */
    private val MYSQL_CONNECTION_LOST = setOf(2006, 2013)

    private val KEY_NAME = Regex("for key '([^']+)'\\s*$")

    private val CONSTRAINTS = mapOf(
        "business.uk_business_registration_number" to StoreConstraint.BUSINESS_REGISTRATION_NUMBER,
        "merchant_site.uk_merchant_site_active_channel" to StoreConstraint.SITE_ACTIVE_CHANNEL,
        "default_designation.PRIMARY" to StoreConstraint.DEFAULT_DESIGNATION,
        "default_designation.uk_default_designation_account" to StoreConstraint.DEFAULT_DESIGNATION,
        "merchant_account_site_assignment.uk_assignment_open_pair" to StoreConstraint.ASSIGNMENT_OPEN,
        "merchant_account_site_assignment.uk_assignment_open_primary" to StoreConstraint.ASSIGNMENT_OPEN,
        "contract_service_agreement.uk_contract_service_agreement_kind" to StoreConstraint.AGREEMENT_SERVICE_KIND,
        "payment_method_activation.uk_payment_method_activation_account_method" to StoreConstraint.ACTIVATION_METHOD,
        // MASTER-SNAPSHOT-001(V3): 종료 아닌 개통 하나, 열린 통화 기록 하나, append-only 순번·철회·발행 PK.
        "payment_method_activation.uk_payment_method_activation_open_method" to StoreConstraint.ACTIVATION_METHOD,
        "currency_enablement.uk_currency_enablement_open" to StoreConstraint.CURRENCY_OPEN,
        "payment_method_activation_event.PRIMARY" to StoreConstraint.ACTIVATION_EVENT,
        "merchant_site_review_decision.PRIMARY" to StoreConstraint.SITE_REVIEW,
        "site_activation_confirmation_revocation.PRIMARY" to StoreConstraint.CONFIRMATION_REVOCATION,
        "entitlement_snapshot_issue.PRIMARY" to StoreConstraint.ENTITLEMENT_ISSUE,
        "site_activation_confirmation.PRIMARY" to StoreConstraint.IDENTIFIER,
        "site_activation_confirmation.uk_site_activation_confirmation_seq" to StoreConstraint.SITE_CONFIRMATION,
        "currency_enablement.PRIMARY" to StoreConstraint.IDENTIFIER,
        "entitlement_snapshot_issue.uk_entitlement_snapshot_issue_event" to StoreConstraint.OUTBOX_EVENT_ID,
        "contract_revision.PRIMARY" to StoreConstraint.CONTRACT_REVISION,
        // MASTER-SNAPSHOT-002(V5).
        "transaction_limit_policy.uk_transaction_limit_policy_open" to StoreConstraint.LIMIT_POLICY_OPEN,
        "policy_version.uk_policy_version_code" to StoreConstraint.POLICY_VERSION_NO,
        "policy_assignment.uk_policy_assignment_open" to StoreConstraint.POLICY_ASSIGNMENT_OPEN,
        "card_route_activation.uk_card_route_activation_open" to StoreConstraint.CARD_ROUTE_OPEN,
        "card_route_activation_event.PRIMARY" to StoreConstraint.CARD_ROUTE_EVENT,
        "transaction_limit_policy.PRIMARY" to StoreConstraint.IDENTIFIER,
        "policy_version.PRIMARY" to StoreConstraint.IDENTIFIER,
        "policy_assignment.PRIMARY" to StoreConstraint.IDENTIFIER,
        "card_route_activation.PRIMARY" to StoreConstraint.IDENTIFIER,
        "contract_revision_approval.PRIMARY" to StoreConstraint.REVISION_APPROVAL,
        "admin_request.uk_admin_request_scope" to StoreConstraint.ADMIN_REQUEST_SCOPE,
        // MERCHANT-OPS-002 B2-2(V9): approvalId 1회 사용(업무 유일키 — 새 TX 판정 3으로 수렴, m-1), 요청 기록 ID는 서버 생성 UUID.
        "admin_approval_evidence.PRIMARY" to StoreConstraint.APPROVAL_EVIDENCE,
        "admin_approval_evidence.uk_admin_approval_evidence_request_record" to StoreConstraint.IDENTIFIER,
        // MERCHANT-CODE-001 §3.2(V13, C-25): 코드 전역 유일 — 같은 가맹점·같은 코드 행은 uk_mca_code도 어기므로 어느 이름이 보고돼도 같은 분류. PK = 1회 변경, approvalId 1회 사용.
        "merchant_code_assignment.uk_mca_code" to StoreConstraint.MERCHANT_CODE,
        "merchant_code_assignment.uk_mca_merchant_code" to StoreConstraint.MERCHANT_CODE,
        "merchant_code_assignment.PRIMARY" to StoreConstraint.MERCHANT_CODE_SEQ,
        "merchant_code_assignment.uk_mca_approval" to StoreConstraint.MERCHANT_CODE_APPROVAL,
        // BUSINESS-PROFILE-001 §5.2 끝(V15, M-4·C-34): 사업자 하위 표 현재 행 UNIQUE — 결함 분류(businessKey = false, 재시도·재판정 없음). 이름별 한 줄.
        "business_registration.uk_breg_business" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_representative.uk_brep_current_order" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_category.uk_bcat_current_order" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_category.uk_bcat_current_name" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_address.uk_badr_current_type" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_contact.uk_bcon_current_order" to StoreConstraint.BUSINESS_PROFILE_ROW,
        "business_contact.uk_bcon_current_primary" to StoreConstraint.BUSINESS_PROFILE_ROW,
        // MERCHANT-FLOW-001 §4A.3(V19 — V21이 uk_cin_active를 uk_cin_open으로 교체): 접수 건·전이 이력 — 결함 분류(businessKey = false).
        "contract_intake.uk_cin_open" to StoreConstraint.CONTRACT_INTAKE_ROW,
        "contract_intake.PRIMARY" to StoreConstraint.CONTRACT_INTAKE_ROW,
        "contract_intake_transition.PRIMARY" to StoreConstraint.CONTRACT_INTAKE_ROW,
        // MERCHANT-FLOW-001 §4B.5(V21): 사업자 계약 표 — 잠금 직렬화·Domain이 먼저 막으므로 위반은 결함(businessKey = false). 해지 2회 UNIQUE의 고정 code 응답은 FLOW-2c 명령이 정한다.
        "business_contract.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract.uk_bc_origin_intake" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_version.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_version.uk_bcv_origin_intake" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_version_service.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_version_close.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_event.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business_contract_event.uk_bce_terminate" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        // MERCHANT-FLOW-001 FLOW-3(V24): 가맹점당 근거 1행 — 가맹점 ID는 서버 생성이라 PK 중복은 결함(BUSINESS_CONTRACT_ROW 분류, businessKey = false).
        "merchant_registration_basis.PRIMARY" to StoreConstraint.BUSINESS_CONTRACT_ROW,
        "business.PRIMARY" to StoreConstraint.IDENTIFIER,
        "merchant.PRIMARY" to StoreConstraint.IDENTIFIER,
        "merchant_site.PRIMARY" to StoreConstraint.IDENTIFIER,
        "contract.PRIMARY" to StoreConstraint.IDENTIFIER,
        "contract_service_agreement.PRIMARY" to StoreConstraint.IDENTIFIER,
        "merchant_account.PRIMARY" to StoreConstraint.IDENTIFIER,
        "merchant_account_site_assignment.PRIMARY" to StoreConstraint.IDENTIFIER,
        "payment_method_activation.PRIMARY" to StoreConstraint.IDENTIFIER,
        "outbox_event.uk_outbox_event_event_id" to StoreConstraint.OUTBOX_EVENT_ID,
        "outbox_event.uk_outbox_event_effect_key" to StoreConstraint.OUTBOX_EFFECT_KEY,
    )
}
