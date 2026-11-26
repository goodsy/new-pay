package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.DesiredService

/** 접수 건 Domain ↔ JPA 행(MERCHANT-FLOW-001 §4A.15). 이관 건은 migration SQL만 만들므로 새 행 변환([newRow])은 이관이 아닌 건만 받는다. */
internal object ContractIntakePersistenceMapper {

    fun newRow(intake: ContractIntake): JpaContractIntakeEntity {
        check(!intake.migrated) { "migrated intakes are created by the V19 migration only" }
        val items = checkNotNull(intake.items) { "a non-migrated intake has intake items" }
        return JpaContractIntakeEntity.newRow(
            intake.businessId.value, intake.seq, intake.status.name, intake.kind.name, intake.targetContractSeq, items.receivedOn, items.channel.name, items.contractManager.value,
            DesiredService.CARD_AUTH in items.desiredServices, DesiredService.CARD_KEYIN in items.desiredServices, intake.createdAt, intake.createdBy.value,
        )
    }

    fun toIntake(row: JpaContractIntakeEntity): ContractIntake {
        val items = if (row.migrated) {
            null
        } else {
            ContractIntakeItems(
                checkNotNull(row.receivedOn), ContractIntakeChannel.valueOf(checkNotNull(row.intakeChannel)), ActorRef(checkNotNull(row.contractManager)),
                buildSet {
                    if (row.desiresCardAuth) add(DesiredService.CARD_AUTH)
                    if (row.desiresCardKeyin) add(DesiredService.CARD_KEYIN)
                },
            )
        }
        return ContractIntake.reconstitute(
            BusinessId(row.businessId), row.intakeSeq, ContractIntakeStatus.valueOf(row.status), row.migrated, items, row.createdAt, ActorRef(row.createdBy),
            row.updatedAt, ActorRef(row.updatedBy), checkNotNull(row.version) { "stored intake has a version" }, ContractIntakeKind.valueOf(row.kind), row.targetContractSeq,
        )
    }
}
