package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.CONTRACT_INTAKE
import com.paycore.master.jooq.generated.Tables.CONTRACT_INTAKE_TRANSITION
import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.application.required.ContractIntakeReader
import com.paycore.master.merchant.domain.BusinessId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사업자 상세 '계약 진행' 섹션(MERCHANT-FLOW-001 FLOW-1 — jOOQ CQRS 조회, [JooqBusinessDetailReader]와 같은 방식). 한 읽기 전용 REPEATABLE READ TX라 사업자·접수 건·전이 이력이 같은 시점이다.
 * Aggregate를 로드하지 않는다(명령 측 `ContractIntakeRepository`와 별개). SELECT 열은 섹션에 필요한 것만 — 전이 이력의 '사유 및 비고'(`reason`)는 읽지 않는다(이력 탭 소관).
 * 시각은 저장 UTC 문자열(TZ-FIX-001 — [MasterSqlText.instant]).
 */
@Component
class JooqContractIntakeReader(private val dsl: DSLContext) : ContractIntakeReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun section(businessId: BusinessId): ContractIntakeSectionView? {
        val id = businessId.value
        dsl.selectOne().from(BUSINESS).where(BUSINESS.BUSINESS_ID.eq(id)).fetchOne() ?: return null

        val t = CONTRACT_INTAKE_TRANSITION
        val transitions = dsl.select(
            t.INTAKE_SEQ, t.TRANSITION_SEQ, t.FROM_STATUS, t.TO_STATUS, t.MIGRATED, t.DOCUMENT_REF, t.ACTOR_REF, MasterSqlText.instant(t.OCCURRED_AT),
        )
            .from(t)
            .where(t.BUSINESS_ID.eq(id))
            .orderBy(t.INTAKE_SEQ, t.TRANSITION_SEQ)
            .fetch()
            .groupBy({ it.value1() }) { r ->
                ContractIntakeSectionView.Transition(
                    transitionSeq = r.value2(), fromStatus = r.value3(), toStatus = r.value4(), migrated = r.value5() == 1.toByte(), documentRef = r.value6(),
                    actorRef = r.value7(), occurredAt = Instant.parse(r.value8()),
                )
            }

        val i = CONTRACT_INTAKE
        val intakes = dsl.select(
            i.INTAKE_SEQ, i.STATUS, i.MIGRATED, i.RECEIVED_ON, i.INTAKE_CHANNEL, i.CONTRACT_MANAGER, i.DESIRES_CARD_AUTH, i.DESIRES_CARD_KEYIN, i.VERSION,
            MasterSqlText.instant(i.CREATED_AT), MasterSqlText.instant(i.UPDATED_AT),
        )
            .from(i)
            .where(i.BUSINESS_ID.eq(id))
            .orderBy(i.INTAKE_SEQ.desc())
            .fetch { r ->
                ContractIntakeSectionView.Intake(
                    intakeSeq = r.value1(), status = r.value2(), migrated = r.value3() == 1.toByte(), receivedOn = r.value4(), intakeChannel = r.value5(),
                    contractManager = r.value6(),
                    desiredServices = buildList {
                        if (r.value7() == 1.toByte()) add("CARD_AUTH")
                        if (r.value8() == 1.toByte()) add("CARD_KEYIN")
                    },
                    version = r.value9(), createdAt = Instant.parse(r.value10()), updatedAt = Instant.parse(r.value11()),
                    transitions = transitions[r.value1()].orEmpty(),
                )
            }
        return ContractIntakeSectionView(id, intakes)
    }
}
