package com.paycore.financial.paymentfact.adapter.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.Repository

/**
 * 검토 m-2: 결제 사실 Spring Data 인터페이스는 `Repository`만 상속하고 선언한 조회·자리 확보 메서드만 가진다. `save*`·`delete*`·`flush`·`saveAndFlush` 같은 쓰기 표면이 없어
 * append-only 사실(Inbox·승인·취소)을 지우거나 merge하는 경로가 생기지 않는다. 보류 표에는 Spring Data 인터페이스 자체가 없다(Store가 persist만 한다).
 */
class PaymentFactRepositorySurfaceSpec : FunSpec({

    val repositories = listOf(
        PaymentFactInboxJpaRepository::class.java,
        AuthorizedPaymentFactJpaRepository::class.java,
        CancelledPaymentFactJpaRepository::class.java,
        PaymentFactStreamJpaRepository::class.java,
    )

    test("each payment fact repository extends only Repository and declares no save/delete/flush methods") {
        repositories.forEach { r ->
            Repository::class.java.isAssignableFrom(r) shouldBe true
            CrudRepository::class.java.isAssignableFrom(r) shouldBe false
            ListCrudRepository::class.java.isAssignableFrom(r) shouldBe false
            r.interfaces.toList() shouldBe listOf(Repository::class.java)
            r.methods.map { it.name }.filter { n -> WRITE_PREFIXES.any { n.startsWith(it) } } shouldBe emptyList()
        }
        repositories.associate { it.simpleName to it.methods.map { m -> m.name }.sorted() } shouldBe mapOf(
            "PaymentFactInboxJpaRepository" to listOf("findByEffect", "findByEvent"),
            "AuthorizedPaymentFactJpaRepository" to listOf("findByPayment"),
            "CancelledPaymentFactJpaRepository" to listOf("findByPayment"),
            "PaymentFactStreamJpaRepository" to listOf("insertIfAbsent"),
        )
    }
}) {
    private companion object {
        val WRITE_PREFIXES = listOf("save", "delete", "flush", "remove")
    }
}
