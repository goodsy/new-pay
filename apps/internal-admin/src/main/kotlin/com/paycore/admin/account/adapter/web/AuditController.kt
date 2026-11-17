package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuditViewResult
import com.paycore.admin.account.application.provided.AuditViewUseCase
import com.paycore.admin.audit.application.provided.AuditFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/audit`(D-ADMIN1-008·S3 리뷰 m-3): 쿼리 파라미터는 **필터뿐**(범위·역할 없음). 범위는 [AuditViewUseCase]가 세션 actor의 계정 현재 행으로 정한다.
 * 형식 오류 400 `BAD_REQUEST`, 권한 없음 403 `FORBIDDEN`, `AUDIT_VIEWED` 기록 실패 503(`AuditUnavailable` — [AccountWebErrors]).
 */
@RestController
class AuditController(private val audit: AuditViewUseCase) {

    @Suppress("LongParameterList")
    @GetMapping("/api/audit")
    fun search(
        authentication: Authentication,
        request: HttpServletRequest,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(required = false) targetActorId: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) targetMerchantId: String?,
    ): ResponseEntity<out Any> {
        val filter = try {
            AuditFilter(
                actorId, targetActorId, action, from?.let(Instant::parse), to?.let(Instant::parse), before, limit ?: AuditFilter.DEFAULT_LIMIT,
                // MERCHANT-CODE-001 MC-3a(§5.6.4): 형식 위반(mer_ + ULID 아님) → 400(조회·감사 없음).
                targetMerchantId = targetMerchantId,
            )
        } catch (_: RuntimeException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore()).body(mapOf("error" to "BAD_REQUEST"))
        }
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))
        return when (val result = audit.search(authentication.name, filter, context)) {
            AuditViewResult.Denied -> ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).body(mapOf("error" to "FORBIDDEN"))
            is AuditViewResult.Entries -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                mapOf(
                    "entries" to result.entries.map {
                        linkedMapOf(
                            "eventId" to it.eventId, "occurredAt" to it.occurredAt.toString(), "actorKind" to it.actorKind, "actorId" to it.actorId,
                            "actorDisplayName" to it.actorDisplayName, "executorActorId" to it.executorActorId, "action" to it.action,
                            "targetActorId" to it.targetActorId, "resultCode" to it.resultCode, "approvalId" to it.approvalId,
                            "correlationId" to it.correlationId, "detail" to it.detail, "reason" to it.reason,
                            // L-13(MERCHANT-OPS-002 B2-2 Demo 개선 C-3): 계정이 아닌 대상(가맹점·Payment·매입 파일·계약 등)도 화면 "대상" 열에 보이게.
                            "targetRef" to it.targetRef,
                            // MERCHANT-CODE-001 MC-3a(§5.6.4): 가맹점 관련 행의 내부 키(target_merchant_key — 응답 포함·화면 미표시), targetRef 바로 뒤.
                            "targetMerchantId" to it.targetMerchantId,
                        )
                    },
                ),
            )
        }
    }
}
