package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.merchantops.application.required.MasterCallResult
import java.time.Instant
import java.time.ZoneId

/**
 * 사업자 상세 '계약 진행' 섹션의 화면 문서(MERCHANT-FLOW-001 §6 FLOW-1 읽기 → **FLOW-2b**: 사업자 계약 칸·종류·ADMIN 오늘 날짜). MASTER 섹션 문서(golden v2 모양 검사 통과분)를 화면 모양으로 다시 만든다.
 *
 * - **표시명은 ADMIN이 채운다**(리뷰 m-11): 계약 담당자·전이 행위자·버전 기록자·사건 행위자의 관리자 참조(`admin:adm_…`)를 ADMIN 계정 현재값([AdminSessionQuery.current] — PK 단건
 *   비잠금 읽기, DB TX 밖)의 표시명으로 바꾼다. 계정이 나중에 비활성이어도 표시명을 보인다(기록 시점 참조 — §4A.6 [구현 관례]). 계정을 찾지 못하면 null(원문 키로 대신하지 않는다).
 *   `login_name`(D-ADMIN1-017)·관리자 내부 키·MASTER 행위자 참조 원문·사업자 키·판정 시각(judgedAt — MASTER 시계)은 화면 문서에 싣지 않는다. 계정 조회는 ADMIN 자기 DB 조회다(§4A.2).
 * - 시스템 행위자(`system:…` — 이관 `system:migration` 등)는 `actorKind = SYSTEM`, 이름 없음. 화면은 이관 행을 '이관'으로 표시한다(R-6).
 * - 근거 참조 원문은 그대로 싣는다(사업자 조회 역할 전체 — D-FLOW-004 ④, 열람은 상세 열람 감사 `BUSINESS_VIEWED`에 포함). '사유 및 비고' 원문은 MASTER가 주지 않는다.
 * - **오늘 날짜(`today`)**: ADMIN 시계 순간을 `Asia/Seoul`로 바꾼 날짜(§4A.6 n-4 — 화면 접수일·시작일 기본값은 브라우저가 아니라 이 값). 판정은 MASTER가 최종.
 * - 버전 행마다 기록 시각(`createdAt`)을 싣는다(MINOR-4 — 오늘 시작 버전은 그날 0시부터 적용으로 표시되므로 기록 시각을 함께 보인다).
 * - fail-safe(Coordinator 결정 Q1): 섹션 조회가 실패하면(Unavailable·NotFound) `available=false`·빈 목록 — 상세 전체를 실패시키지 않는다(이 모양은 FLOW-1과 같다).
 */
internal class BusinessContractProgress(private val sessions: AdminSessionQuery, private val clock: () -> Instant = Instant::now) {

    @Suppress("UNCHECKED_CAST")
    fun of(section: MasterCallResult): Map<String, Any?> {
        if (section !is MasterCallResult.Found) return unavailable()
        val names = HashMap<String, String?>()
        fun nameOf(adminRef: String): String? = names.getOrPut(adminRef) { sessions.current(adminRef.removePrefix(ADMIN_PREFIX))?.displayName }
        fun actor(ref: String): Pair<String, String?> = if (ADMIN_ACTOR.matches(ref)) ADMIN to nameOf(ref) else SYSTEM to null
        val doc = section.document
        val contract = (doc["businessContract"] as Map<String, Any?>?)?.let { c ->
            linkedMapOf(
                "contractSeq" to c["contractSeq"], "migrated" to c["migrated"], "status" to c["status"], "applyingVersionNo" to c["applyingVersionNo"],
                "scheduledVersionNo" to c["scheduledVersionNo"], "linkedMerchantCount" to c["linkedMerchantCount"], "createdAt" to c["createdAt"],
                "versions" to (c["versions"] as List<Map<String, Any?>>).map { v ->
                    val (kind, name) = actor(v["createdBy"] as String)
                    linkedMapOf(
                        "versionNo" to v["versionNo"], "migrated" to v["migrated"], "startsAt" to v["startsAt"], "startDate" to v["startDate"], "endDate" to v["endDate"],
                        "services" to v["services"], "documentRef" to v["documentRef"], "closedAt" to v["closedAt"], "createdAt" to v["createdAt"],
                        "actorKind" to kind, "actorName" to name,
                    )
                },
                "events" to (c["events"] as List<Map<String, Any?>>).map { e ->
                    val (kind, name) = actor(e["actorRef"] as String)
                    linkedMapOf(
                        "eventSeq" to e["eventSeq"], "kind" to e["kind"], "evidenceRef" to e["evidenceRef"], "actorKind" to kind, "actorName" to name, "occurredAt" to e["occurredAt"],
                    )
                },
            )
        }
        val intakes = (doc["intakes"] as List<Map<String, Any?>>).map { intake ->
            linkedMapOf(
                "intakeSeq" to intake["intakeSeq"], "status" to intake["status"], "kind" to intake["kind"], "targetContractSeq" to intake["targetContractSeq"],
                "migrated" to intake["migrated"], "receivedOn" to intake["receivedOn"], "intakeChannel" to intake["intakeChannel"],
                // FLOW-3 Coordinator 결정: Demo seed 담당자는 계정 조회 없이 고정 표시(원문 참조는 싣지 않는다).
                "contractManagerName" to (intake["contractManager"] as String?)?.let { if (it == DEMO_SEED_ACTOR) DEMO_SEED_NAME else nameOf(it) },
                "desiredServices" to intake["desiredServices"], "version" to intake["version"], "createdAt" to intake["createdAt"], "updatedAt" to intake["updatedAt"],
                "transitions" to (intake["transitions"] as List<Map<String, Any?>>).map { t ->
                    val (kind, name) = actor(t["actorRef"] as String)
                    linkedMapOf(
                        "transitionSeq" to t["transitionSeq"], "fromStatus" to t["fromStatus"], "toStatus" to t["toStatus"], "migrated" to t["migrated"],
                        "documentRef" to t["documentRef"], "actorKind" to kind, "actorName" to name, "occurredAt" to t["occurredAt"],
                    )
                },
            )
        }
        return linkedMapOf("available" to true, "today" to todayKst(), "businessContract" to contract, "intakes" to intakes)
    }

    /** ADMIN 시계의 KST 날짜(`yyyy-MM-dd`). */
    fun todayKst(): String = clock().atZone(SEOUL).toLocalDate().toString()

    companion object {
        /** 화면 상세 문서의 섹션 키. */
        const val KEY = "contractProgress"
        private const val ADMIN_PREFIX = "admin:"
        private const val ADMIN = "ADMIN"
        private const val SYSTEM = "SYSTEM"
        private val ADMIN_ACTOR = Regex("^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$")
        private const val DEMO_SEED_ACTOR = "system:demo-seed"
        private const val DEMO_SEED_NAME = "Demo seed"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun unavailable(): Map<String, Any?> = linkedMapOf("available" to false, "intakes" to emptyList<Any>())
    }
}
