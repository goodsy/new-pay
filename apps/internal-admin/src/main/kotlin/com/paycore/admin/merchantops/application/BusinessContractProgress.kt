package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.merchantops.application.required.MasterCallResult

/**
 * 사업자 상세 '계약 진행' 섹션의 화면 문서(MERCHANT-FLOW-001 §6 FLOW-1 — 읽기 전용). MASTER 섹션 문서(모양 검사 통과분)를 화면 모양으로 다시 만든다.
 *
 * - **표시명은 ADMIN이 채운다**(리뷰 m-11): 계약 담당자·전이 행위자의 관리자 참조(`admin:adm_…`)를 ADMIN 계정 현재값([AdminSessionQuery.current] — PK 단건 비잠금 읽기, DB TX 밖)의
 *   표시명으로 바꾼다. 계정이 나중에 비활성이어도 표시명을 보인다(기록 시점 참조 — §4A.6 [구현 관례]). 계정을 찾지 못하면 null(원문 키로 대신하지 않는다).
 *   `login_name`(D-ADMIN1-017)·관리자 내부 키·MASTER 행위자 참조 원문·사업자 키는 화면 문서에 싣지 않는다. 계정 조회는 cross-DB 조인이 아니라 ADMIN 자기 DB 조회다(§4A.2).
 * - 시스템 행위자(`system:…` — 이관 `system:migration` 등)는 `actorKind = SYSTEM`, 이름 없음. 화면은 이관 행을 '이관'으로 표시한다(R-6).
 * - 근거 참조 원문은 그대로 싣는다(사업자 조회 역할 전체 — D-FLOW-004 ④, 열람은 상세 열람 감사 `BUSINESS_VIEWED`에 포함). '사유 및 비고' 원문은 MASTER가 주지 않는다.
 * - fail-safe(Coordinator 결정 Q1): 섹션 조회가 실패하면(Unavailable·NotFound) `available=false`·빈 목록 — 상세 전체를 실패시키지 않는다. 쓰기 동작은 없다.
 */
internal class BusinessContractProgress(private val sessions: AdminSessionQuery) {

    @Suppress("UNCHECKED_CAST")
    fun of(section: MasterCallResult): Map<String, Any?> {
        if (section !is MasterCallResult.Found) return unavailable()
        val names = HashMap<String, String?>()
        fun nameOf(adminRef: String): String? = names.getOrPut(adminRef) { sessions.current(adminRef.removePrefix(ADMIN_PREFIX))?.displayName }
        val intakes = (section.document["intakes"] as List<Map<String, Any?>>).map { intake ->
            linkedMapOf(
                "intakeSeq" to intake["intakeSeq"], "status" to intake["status"], "migrated" to intake["migrated"], "receivedOn" to intake["receivedOn"],
                "intakeChannel" to intake["intakeChannel"], "contractManagerName" to (intake["contractManager"] as String?)?.let(::nameOf),
                "desiredServices" to intake["desiredServices"], "version" to intake["version"], "createdAt" to intake["createdAt"], "updatedAt" to intake["updatedAt"],
                "transitions" to (intake["transitions"] as List<Map<String, Any?>>).map { t ->
                    val actor = t["actorRef"] as String
                    val admin = ADMIN_ACTOR.matches(actor)
                    linkedMapOf(
                        "transitionSeq" to t["transitionSeq"], "fromStatus" to t["fromStatus"], "toStatus" to t["toStatus"], "migrated" to t["migrated"],
                        "documentRef" to t["documentRef"], "actorKind" to if (admin) ADMIN else SYSTEM, "actorName" to if (admin) nameOf(actor) else null,
                        "occurredAt" to t["occurredAt"],
                    )
                },
            )
        }
        return linkedMapOf("available" to true, "intakes" to intakes)
    }

    companion object {
        /** 화면 상세 문서의 섹션 키. */
        const val KEY = "contractProgress"
        private const val ADMIN_PREFIX = "admin:"
        private const val ADMIN = "ADMIN"
        private const val SYSTEM = "SYSTEM"
        private val ADMIN_ACTOR = Regex("^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$")

        fun unavailable(): Map<String, Any?> = linkedMapOf("available" to false, "intakes" to emptyList<Any>())
    }
}
