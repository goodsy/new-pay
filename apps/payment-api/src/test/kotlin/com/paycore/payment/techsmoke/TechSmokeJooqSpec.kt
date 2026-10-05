package com.paycore.payment.techsmoke

import com.paycore.payment.architecture.ArchitectureSpec
import com.paycore.payment.techsmoke.jooq.tables.TechSmokeItem.TECH_SMOKE_ITEM
import com.paycore.techsmoke.payment.TechSmokeItemEntity
import com.paycore.techsmoke.payment.TechSmokeItemRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * jOOQ codegen 기술 조합 smoke. TECH_SMOKE_ITEM은 Flyway migration을 실제 MySQL에 적용한 schema에서 생성된 클래스다
 * (build/generated-sources/jooq/test). JPA와 jOOQ가 같은 commit된 데이터를 보는지 확인한다.
 * 두 기술이 같은 TX를 공유하는지는 여기서 검사하지 않는다(각 쓰기는 commit 후 다른 쪽이 읽는다).
 */
@TechSmokeTest
class TechSmokeJooqSpec(
    private val repository: TechSmokeItemRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dsl: DSLContext,
) : FunSpec({

    val tx = TransactionTemplate(transactionManager)

    test("generated TECH_SMOKE_ITEM matches the Flyway-created table") {
        TECH_SMOKE_ITEM.name shouldBe "tech_smoke_item"
        TECH_SMOKE_ITEM.fields().map { it.name } shouldContainExactly listOf("id", "name", "version")
    }

    test("main codegen (main migrations only) does not contain test-only tables") {
        // main 생성 패키지는 main migration만 반영한다. 현재 main schema가 비어 있어 생성 클래스가 없다.
        shouldThrow<ClassNotFoundException> {
            Class.forName("${ArchitectureSpec.GENERATED_JOOQ_PACKAGE}.tables.TechSmokeItem")
        }
    }

    test("jOOQ query reads the row committed through JPA") {
        tx.executeWithoutResult { repository.save(TechSmokeItemEntity(id = 10, name = "via-jpa")) }

        val record = dsl.selectFrom(TECH_SMOKE_ITEM).where(TECH_SMOKE_ITEM.ID.eq(10L)).fetchOne()!!

        record.name shouldBe "via-jpa"
        record.version shouldBe 0L
    }

    test("JPA reads the row committed through jOOQ") {
        dsl.insertInto(TECH_SMOKE_ITEM)
            .set(TECH_SMOKE_ITEM.ID, 11L)
            .set(TECH_SMOKE_ITEM.NAME, "via-jooq")
            .set(TECH_SMOKE_ITEM.VERSION, 0L)
            .execute()

        val loaded = tx.execute { repository.findById(11L).orElseThrow() }!!

        loaded.name shouldBe "via-jooq"
        loaded.version shouldBe 0L
    }
})
