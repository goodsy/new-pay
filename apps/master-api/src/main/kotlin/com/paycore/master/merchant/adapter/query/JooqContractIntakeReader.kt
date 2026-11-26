package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTRACT
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTRACT_EVENT
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTRACT_VERSION
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTRACT_VERSION_CLOSE
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTRACT_VERSION_SERVICE
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.CONTRACT_INTAKE
import com.paycore.master.jooq.generated.Tables.CONTRACT_INTAKE_TRANSITION
import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.application.required.ContractIntakeReader
import com.paycore.master.merchant.application.required.ContractIntakeSection
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractEvent
import com.paycore.master.merchant.domain.BusinessContractEventKind
import com.paycore.master.merchant.domain.BusinessContractId
import com.paycore.master.merchant.domain.BusinessContractVersion
import com.paycore.master.merchant.domain.BusinessContractVersionClose
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ServiceKind
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사업자 상세 '계약 진행' 섹션(MERCHANT-FLOW-001 FLOW-1 — jOOQ CQRS 조회, [JooqBusinessDetailReader]와 같은 방식). 한 읽기 전용 REPEATABLE READ TX라 사업자·접수 건·전이 이력이 같은 시점이다.
 * Aggregate를 로드하지 않는다(명령 측 `ContractIntakeRepository`와 별개). SELECT 열은 섹션에 필요한 것만 — 전이 이력의 '사유 및 비고'(`reason`)는 읽지 않는다(이력 탭 소관).
 * 시각은 저장 UTC 문자열(TZ-FIX-001 — [MasterSqlText.instant]).
 * FLOW-2b(§4B.11 1): 같은 스냅샷에서 **최신 사업자 계약**(머리 + 버전·범위·닫기·사건)과 연결 가맹점 수(종료 제외 — BR-BIZ-312)를 읽어 Domain으로 복원해 돌려준다(상태 계산은 Query Service —
 * 표시용). 사건 사유 원문은 읽지 않는다(섹션에 싣지 않음 — 복원에 필요한 값은 자리 표시 사유로 채운다).
 */
@Component
class JooqContractIntakeReader(private val dsl: DSLContext) : ContractIntakeReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun section(businessId: BusinessId): ContractIntakeSection? {
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
            MasterSqlText.instant(i.CREATED_AT), MasterSqlText.instant(i.UPDATED_AT), i.KIND, i.TARGET_CONTRACT_SEQ,
        )
            .from(i)
            .where(i.BUSINESS_ID.eq(id))
            .orderBy(i.INTAKE_SEQ.desc())
            .fetch { r ->
                ContractIntakeSectionView.Intake(
                    intakeSeq = r.value1(), status = r.value2(), kind = r.value12(), targetContractSeq = r.value13(), migrated = r.value3() == 1.toByte(), receivedOn = r.value4(), intakeChannel = r.value5(),
                    contractManager = r.value6(),
                    desiredServices = buildList {
                        if (r.value7() == 1.toByte()) add("CARD_AUTH")
                        if (r.value8() == 1.toByte()) add("CARD_KEYIN")
                    },
                    version = r.value9(), createdAt = Instant.parse(r.value10()), updatedAt = Instant.parse(r.value11()),
                    transitions = transitions[r.value1()].orEmpty(),
                )
            }
        val m = MERCHANT
        val linked = dsl.selectCount().from(m).where(m.BUSINESS_ID.eq(id).and(m.LIFECYCLE.ne(TERMINATED))).fetchOne(0, Int::class.java) ?: 0
        return ContractIntakeSection(ContractIntakeSectionView(id, intakes), latestContract(businessId), linked)
    }

    /** 최신 사업자 계약(계약 순번 최대) — 머리 1 + 하위 표 4문장. 없으면 null. */
    private fun latestContract(businessId: BusinessId): BusinessContract? {
        val id = businessId.value
        val c = BUSINESS_CONTRACT
        val head = dsl.select(c.CONTRACT_SEQ, c.ORIGIN_INTAKE_SEQ, c.MIGRATED, MasterSqlText.instant(c.CREATED_AT), c.CREATED_BY)
            .from(c).where(c.BUSINESS_ID.eq(id)).orderBy(c.CONTRACT_SEQ.desc()).limit(1).fetchOne() ?: return null
        val seq = head.value1()
        val s = BUSINESS_CONTRACT_VERSION_SERVICE
        val scopes = dsl.select(s.VERSION_NO, s.SERVICE_KIND).from(s).where(s.BUSINESS_ID.eq(id).and(s.CONTRACT_SEQ.eq(seq))).fetch()
            .groupBy({ it.value1() }) { ServiceKind.valueOf(it.value2()) }
        val v = BUSINESS_CONTRACT_VERSION
        val versions = dsl.select(
            v.VERSION_NO, v.ORIGIN_INTAKE_SEQ, v.MIGRATED, MasterSqlText.instant(v.STARTS_AT), v.START_DATE, v.END_DATE, v.DOCUMENT_REF,
            MasterSqlText.instant(v.CREATED_AT), v.CREATED_BY,
        ).from(v).where(v.BUSINESS_ID.eq(id).and(v.CONTRACT_SEQ.eq(seq))).orderBy(v.VERSION_NO).fetch { r ->
            // '사유 및 비고'(`reason`)는 읽지 않는다(섹션에 싣지 않음 — 이력 탭 소관). 버전 사유는 선택 값이라 null로 복원해도 불변식에 영향 없다.
            BusinessContractVersion(
                r.value1(), r.value2(), r.value3() == 1.toByte(), Instant.parse(r.value4()), r.value5(), r.value6(), scopes[r.value1()].orEmpty().toSet(),
                r.value7()?.let(::ContractDocumentRef), null, Instant.parse(r.value8()), ActorRef(r.value9()),
            )
        }
        val x = BUSINESS_CONTRACT_VERSION_CLOSE
        val closes = dsl.select(x.VERSION_NO, MasterSqlText.instant(x.CLOSED_AT), x.CLOSED_BY_VERSION_NO, MasterSqlText.instant(x.CREATED_AT), x.CREATED_BY)
            .from(x).where(x.BUSINESS_ID.eq(id).and(x.CONTRACT_SEQ.eq(seq))).orderBy(x.VERSION_NO)
            .fetch { r -> BusinessContractVersionClose(r.value1(), Instant.parse(r.value2()), r.value3(), Instant.parse(r.value4()), ActorRef(r.value5())) }
        val e = BUSINESS_CONTRACT_EVENT
        // 사유 원문은 읽지 않는다 — 복원 불변식(사유 필수)만 채우는 자리 표시 값(표시하지 않음).
        val events = dsl.select(e.EVENT_SEQ, e.KIND, e.EVIDENCE_REF, e.ACTOR_REF, MasterSqlText.instant(e.OCCURRED_AT))
            .from(e).where(e.BUSINESS_ID.eq(id).and(e.CONTRACT_SEQ.eq(seq))).orderBy(e.EVENT_SEQ)
            .fetch { r ->
                BusinessContractEvent(
                    r.value1(), BusinessContractEventKind.valueOf(r.value2()), NOT_READ_REASON, r.value3()?.let(::ContractDocumentRef), ActorRef(r.value4()), Instant.parse(r.value5()),
                )
            }
        return BusinessContract.reconstitute(
            BusinessContractId(businessId, seq), head.value2(), head.value3() == 1.toByte(), Instant.parse(head.value4()), ActorRef(head.value5()), versions, closes, events,
        )
    }

    private companion object {
        const val TERMINATED = "TERMINATED"
        val NOT_READ_REASON = BusinessChangeReason("not-read")
    }
}
