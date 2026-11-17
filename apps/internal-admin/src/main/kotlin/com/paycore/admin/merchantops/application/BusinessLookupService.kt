package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.merchantops.application.provided.BusinessLookupUseCase
import com.paycore.admin.merchantops.application.provided.BusinessQueryResult
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * [BusinessLookupUseCase](BUSINESS-PROFILE-001 §7.2 — MERCHANT-BIZ-LOOKUP-001 rev 0.3 §4.2 순서, 역할만 [BusinessPermissions.VIEW] = OPERATOR·APPROVER — D-BIZ-006 ①):
 * ① 앞단(감사 없음) ② 권한(403 + `FORBIDDEN`) ③ 형식·본문 해석 실패(400 + `INVALID_INPUT`, `brn=INVALID`) ④ **공유 등록번호 버킷**([RegistrationNumberBudget.admit] — 가맹점 BRN 검색·
 * 사업자 생성과 같은 버킷, C-3) ⑤ 사업자 조회 창([BusinessReadWindow] — 목록·상세·이력과 공유, m-2) ⑥ MASTER(TX 밖) ⑦ 감사 `OK`(`brn=123*******;results=N`, 찾으면 targetRef =
 * businessId)/`UNAVAILABLE`. 매 시도 감사(D-MOPS-003), 감사 실패 = 결과 미반환. action `BUSINESS_BRN_LOOKUP`(C-25).
 * 응답 문서 = MASTER 모양 검사 통과분 그대로(`businessId`·`registrationStatus` 포함 — D-BIZLOOKUP-001·D-BIZ-006 ③, 평문 번호 키 없음). 로그·지표에 번호 없음.
 */
@Service
class BusinessLookupService(
    transactionManager: PlatformTransactionManager,
    sessions: AdminSessionQuery,
    recorder: AdminAuditRecorder,
    private val gateway: MasterBusinessGateway,
    private val registrationNumbers: RegistrationNumberBudget,
    private val window: BusinessReadWindow,
    private val metrics: MerchantOpsMetrics,
    private val businessMetrics: BusinessOpsMetrics,
) : BusinessLookupUseCase {

    private val audit = BusinessAudit(transactionManager, sessions, recorder)

    override fun lookup(actorId: String, registrationNumber: String?, context: AdminRequestContext): BusinessQueryResult {
        val view = audit.frontGate(actorId) ?: return BusinessQueryResult.Forbidden.also { finish("FORBIDDEN_FRONT", it) }
        val masked = BusinessAudit.maskedNumber(registrationNumber)
        fun audited(resultCode: String, outcome: BusinessQueryResult, detail: String = masked, targetRef: String? = null): BusinessQueryResult {
            val result = if (audit.record(view, context, context.correlationId, ACTION, resultCode, detail, targetRef)) outcome else BusinessQueryResult.AuditUnavailable
            return finish(if (result == BusinessQueryResult.AuditUnavailable) "AUDIT_UNAVAILABLE" else resultCode, result)
        }

        if (view.roles.none { it in BusinessPermissions.VIEW }) return audited("FORBIDDEN", BusinessQueryResult.Forbidden)
        if (registrationNumber == null || !BusinessAudit.REGISTRATION_NUMBER.matches(registrationNumber)) {
            return audited("INVALID_INPUT", BusinessQueryResult.InvalidInput, BusinessAudit.INVALID_NUMBER)
        }
        if (!registrationNumbers.admit(actorId)) {
            metrics.rateLimited(REGISTRATION_NUMBER)
            return audited("RATE_LIMITED", BusinessQueryResult.RateLimited)
        }
        if (!window.admit(actorId)) {
            metrics.rateLimited(BUSINESS_READ)
            return audited("RATE_LIMITED", BusinessQueryResult.RateLimited)
        }
        return when (val result = gateway.lookup(registrationNumber)) {
            is MasterCallResult.Found -> {
                @Suppress("UNCHECKED_CAST")
                val items = result.document["items"] as List<Map<String, Any?>>
                // 번호 UNIQUE — 결과는 0/1(모양 검사 max 1). 찾았으면 정확히 어느 사업자를 조회했는지 targetRef로 남긴다.
                val businessId = items.singleOrNull()?.get("businessId") as String?
                audited("OK", BusinessQueryResult.Found(result.document), "$masked;results=${items.size}", businessId)
            }
            MasterCallResult.NotFound -> audited("UNAVAILABLE", BusinessQueryResult.Unavailable(UNEXPECTED_NOT_FOUND))
            is MasterCallResult.Unavailable -> audited("UNAVAILABLE", BusinessQueryResult.Unavailable(result.reason))
        }
    }

    private fun finish(resultCode: String, result: BusinessQueryResult): BusinessQueryResult {
        businessMetrics.lookup(resultCode)
        log.info("operation=business-brn-lookup result={} audit={}", result.javaClass.simpleName, resultCode)
        return result
    }

    private companion object {
        val log = LoggerFactory.getLogger(BusinessLookupService::class.java)
        const val ACTION = "BUSINESS_BRN_LOOKUP"
        const val REGISTRATION_NUMBER = "REGISTRATION_NUMBER"
        const val BUSINESS_READ = "BUSINESS_READ"
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
    }
}
