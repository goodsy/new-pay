package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 사업자 관리 Use Case 공용 감사·앞단 판정(BUSINESS-PROFILE-001 §7.1·§7.4). 감사 1행 = ADMIN 로컬 TX 하나(MASTER 호출과 별개 — 감사 실패 = 행위 실패, 호출자가 결과를 돌려주지 않는다).
 * detail은 고정 `key=value`(값 문자 `[A-Za-z0-9_:.,*-]`)만 — 사유 원문·이름·연락처·주소·상호·사업자등록번호 평문은 넣지 않는다(C-13). 사업자 대상이라 `targetMerchantId`는 없다.
 */
internal class BusinessAudit(transactionManager: PlatformTransactionManager, private val sessions: AdminSessionQuery, private val recorder: AdminAuditRecorder) {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    /** 앞단: 계정 현재 행 ACTIVE + 업무 조회 역할([BusinessPermissions.FRONT]). 아니면 null(403, 감사 없음). */
    fun frontGate(actorId: String): AdminSessionView? =
        sessions.current(actorId)?.takeIf { it.isActive && it.roles.any { role -> role in BusinessPermissions.FRONT } }

    /**
     * 감사 1행. 성공하면 true, 실패하면 false(로그 = action·분류만).
     * 대상 열은 사업자 ID(`biz_<ULID>`)나 null만 — 가맹점·승인 대상 표기(`mer_`·코드)는 이 경로로 쓰지 않는다(ApprovalAuditTargetSourceSpec 허용 목록의 조건).
     * 다른 형식이면 쓰지 않고 감사 실패(false)로 닫는다(fail-closed — 호출자는 결과를 돌려주지 않는다).
     */
    @Suppress("LongParameterList")
    fun record(view: AdminSessionView, context: AdminRequestContext, correlationId: String, action: String, resultCode: String, detail: String?, targetRef: String?): Boolean = try {
        require(targetRef == null || BUSINESS_ID.matches(targetRef)) { "business audit target must be a business id" }
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = view.actorId, actorDisplayName = view.displayName,
                    action = action, resultCode = resultCode, correlationId = correlationId, remoteIp = context.remoteIp, detail = detail, targetRef = targetRef,
                ),
            )
        }
        true
    } catch (e: RuntimeException) {
        log.warn("operation=business-audit action={} result=audit-unavailable failure={}", action, e.javaClass.simpleName)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(BusinessAudit::class.java)
        private const val VISIBLE_DIGITS = 3
        private const val MASKED_DIGITS = 7
        const val INVALID_NUMBER = "brn=INVALID"
        val REGISTRATION_NUMBER = Regex("^[0-9]{10}$")
        val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")

        /** 감사 detail의 번호 표기(§9 — `brn=123*******`, 형식 위반 `brn=INVALID`). */
        fun maskedNumber(number: String?): String =
            if (number != null && REGISTRATION_NUMBER.matches(number)) "brn=" + number.take(VISIBLE_DIGITS) + "*".repeat(MASKED_DIGITS) else INVALID_NUMBER
    }
}
