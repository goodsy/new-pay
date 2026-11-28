package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.ContractManagerQueryUseCase
import com.paycore.admin.merchantops.application.provided.ContractManagerTarget
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 계약 담당자 후보 목록 `GET /api/contract-managers`(MERCHANT-FLOW-001 FLOW-2d — D-GOV-024 Q-FLOW-39·40): 활성 OPERATOR 조회자만(아니면 403), `no-store`.
 * 응답 `{"items":[{"ref","displayName","self"}],"truncated"}` — 계정 키 대신 이 세션에 묶인 불투명 참조([ContractManagerReferences] — 응답마다 새로 발급, 옛 참조 무효).
 * 로그인 이름·이메일·역할·상태는 없다. 감사·로그에도 표시명·참조를 남기지 않는다.
 */
@RestController
class ContractManagerController(private val query: ContractManagerQueryUseCase, private val references: ContractManagerReferences) {

    /**
     * 재리뷰 MAJOR-1: 선택 질의 `businessId`·`intakeSeq`(둘 다 또는 둘 다 없음 — 아니면 400)를 주면 항목마다 `current`(저장된 담당자와 같은 계정인가 — 서버가 계정 키로 비교)를 더한다.
     * 형식: `biz_` + ULID, 순번 1..999999999 십진(접수 건 경로와 같은 문법). 없는 사업자·접수 건이어도 200(current 없음). 활성 OPERATOR 담당자가 있는 접수 건이면 current로 드러난다 — 같은 역할의 상세 열람 범위 안의 정보이며, 없음·비활성·실패끼리만 구별되지 않는다.
     */
    @GetMapping("/api/contract-managers")
    fun list(
        authentication: Authentication,
        request: HttpServletRequest,
        @RequestParam(required = false) businessId: String?,
        @RequestParam(required = false) intakeSeq: String?,
    ): ResponseEntity<out Any> {
        if ((businessId == null) != (intakeSeq == null)) return invalid()
        val target = if (businessId == null || intakeSeq == null) {
            null
        } else {
            if (!BUSINESS_ID.matches(businessId) || !ContractIntakeBodyParser.intakeSeqWellFormed(intakeSeq)) return invalid()
            ContractManagerTarget(businessId, intakeSeq.toInt())
        }
        val candidates = query.candidates(authentication.name, target)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).body(mapOf("error" to "FORBIDDEN"))
        val refs = references.issue(request.getSession(true), candidates.candidates.map { it.actorId })
        val items = candidates.candidates.zip(refs).map { (c, ref) ->
            linkedMapOf<String, Any>("ref" to ref, "displayName" to c.displayName, "self" to c.self).also { m -> c.current?.let { m["current"] = it } }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(linkedMapOf("items" to items, "truncated" to candidates.truncated))
    }

    private fun invalid(): ResponseEntity<Any> = ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(mapOf("error" to "INVALID_INPUT"))

    private companion object {
        val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
