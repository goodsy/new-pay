package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractChange
import com.paycore.master.merchant.domain.BusinessContractEvent
import com.paycore.master.merchant.domain.BusinessContractEventKind
import com.paycore.master.merchant.domain.BusinessContractId
import com.paycore.master.merchant.domain.BusinessContractVersion
import com.paycore.master.merchant.domain.BusinessContractVersionClose
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ServiceKind
import jakarta.persistence.Tuple
import java.time.Instant
import java.time.LocalDate

/**
 * 사업자 계약 Domain ↔ JPA 행(MERCHANT-FLOW-001 §4B.12). 읽기는 저장 Port의 스칼라 투영(`Tuple`)을 Domain 하나로 모으고([toContract] — 불변식은 `BusinessContract.reconstitute`가
 * 다시 검사), 쓰기는 [BusinessContractChange]가 새로 만든 행만 FK 순서 묶음으로 만든다([newRows] — 이관 행은 V21 migration SQL만 만든다).
 */
internal object BusinessContractPersistenceMapper {

    fun toContract(businessId: BusinessId, head: Tuple, versions: List<Tuple>, services: List<Tuple>, closes: List<Tuple>, events: List<Tuple>): BusinessContract {
        val scopes = services.groupBy({ it.int("versionNo") }) { ServiceKind.valueOf(it.get("serviceKind", String::class.java)) }
        return BusinessContract.reconstitute(
            BusinessContractId(businessId, head.int("contractSeq")), head.int("originIntakeSeq"), head.get("migrated") as Boolean,
            head.get("createdAt", Instant::class.java), ActorRef(head.get("createdBy", String::class.java)),
            versions.map { v ->
                val no = v.int("versionNo")
                BusinessContractVersion(
                    no, v.int("originIntakeSeq"), v.get("migrated") as Boolean, v.get("startsAt", Instant::class.java),
                    v.get("startDate", LocalDate::class.java), v.get("endDate", LocalDate::class.java), scopes[no].orEmpty().toSet(),
                    v.get("documentRef", String::class.java)?.let(::ContractDocumentRef), v.get("reason", String::class.java)?.let(::BusinessChangeReason),
                    v.get("createdAt", Instant::class.java), ActorRef(v.get("createdBy", String::class.java)),
                )
            },
            closes.map { x ->
                BusinessContractVersionClose(
                    x.int("versionNo"), x.get("closedAt", Instant::class.java), x.int("closedByVersionNo"), x.get("createdAt", Instant::class.java),
                    ActorRef(x.get("createdBy", String::class.java)),
                )
            },
            events.map { e ->
                BusinessContractEvent(
                    e.int("eventSeq"), BusinessContractEventKind.valueOf(e.get("kind", String::class.java)), BusinessChangeReason(e.get("reason", String::class.java)),
                    e.get("evidenceRef", String::class.java)?.let(::ContractDocumentRef), ActorRef(e.get("actorRef", String::class.java)), e.get("occurredAt", Instant::class.java),
                )
            },
        )
    }

    /** 새 행 묶음(앞 묶음이 FK 부모 — 묶음마다 flush): [계약 머리] → [버전] → [범위 + 닫기] / [사건]. */
    fun newRows(change: BusinessContractChange): List<List<Any>> {
        val id = change.contract.id
        val businessId = id.businessId.value
        val seq = id.contractSeq
        val batches = mutableListOf<List<Any>>()
        if (change.newContract) {
            check(!change.contract.migrated) { "migrated contracts are created by the V21 migration only" }
            batches += listOf(JpaBusinessContractEntity.newRow(businessId, seq, change.contract.originIntakeSeq, change.contract.createdAt, change.contract.createdBy.value))
        }
        change.version?.let { v ->
            check(!v.migrated) { "migrated versions are created by the V21 migration only" }
            batches += listOf(
                JpaBusinessContractVersionEntity.newRow(
                    businessId, seq, v.versionNo, v.originIntakeSeq, v.startsAt, checkNotNull(v.startDate), v.endDate, checkNotNull(v.document).value, v.reason?.value,
                    v.createdAt, v.createdBy.value,
                ),
            )
            batches += v.services.sortedBy { it.name }.map { JpaBusinessContractVersionServiceEntity.newRow(businessId, seq, v.versionNo, it.name) } +
                listOfNotNull(
                    change.close?.let { c ->
                        JpaBusinessContractVersionCloseEntity.newRow(businessId, seq, c.versionNo, c.closedAt, c.closedByVersionNo, c.createdAt, c.createdBy.value)
                    },
                )
        }
        change.event?.let { e ->
            batches += listOf(JpaBusinessContractEventEntity.newRow(businessId, seq, e.eventSeq, e.kind.name, e.reason.value, e.evidence?.value, e.occurredAt, e.actor.value))
        }
        return batches
    }

    private fun Tuple.int(alias: String): Int = (get(alias) as Number).toInt()
}
