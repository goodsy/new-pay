package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.DemoCredentialIssueCommand
import com.paycore.payment.payment.application.provided.DemoCredentialIssueResult
import com.paycore.payment.payment.application.required.DemoCredentialIssuance
import com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore
import com.paycore.payment.payment.application.required.DemoCredentialProjection
import com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter
import com.paycore.payment.payment.application.required.MidPolicyContents
import com.paycore.payment.payment.application.required.MidPolicyProfilePort
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant

/**
 * DEMO-SEED-001 리뷰 M-1 후속(DB 없음): 동시 실행 루프(DemoCredentialCommandSpec ×20)에서 패자가 1062 대신 **InnoDB 교착 희생**(1213, 고정 발급 행 INSERT)으로
 * 끝나는 경우가 2회 관찰됐다(그때 entitlement_head에는 S 잠금뿐 — X·대기 없음). 이 경우도 아무것도 쓰지 않은 경합 패배이므로 LOST(exit 5)로 분류한다.
 * 다른 단계(투영 INSERT)의 교착은 분류하지 않고 전파한다(exit 1). TX는 rollback된다.
 */
class DemoCredentialIssueServiceSpec : FunSpec({

    val mid = "mid_01J9ZQG1DENA10000000000000"

    class RecordingTm : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) { commits++ }
        override fun rollback(status: TransactionStatus) { rollbacks++ }
    }

    fun service(tm: PlatformTransactionManager, onIssuance: () -> Unit, onProjection: () -> Unit) = DemoCredentialIssueService(
        profiles = object : MidPolicyProfilePort {
            override fun find(mid: MerchantAccountId): MidPolicyContents = MidPolicyContents(demoCount = 2, otherCount = 0)
        },
        issuances = object : DemoCredentialIssuanceStore {
            override fun find(): DemoCredentialIssuance? = null
            override fun insertOnce(issuance: DemoCredentialIssuance) = onIssuance()
        },
        projections = object : DemoCredentialProjectionWriter {
            override fun insert(row: DemoCredentialProjection) = onProjection()
        },
        keys = { DemoCredentialIssueService.KeyMaterial("demoUNIT000000000001", "A".repeat(43)) },
        clock = object : PaymentClock { override fun now(): Instant = Instant.parse("2026-11-01T00:00:00Z") },
        transactionManager = tm,
    )

    val deadlock = { throw PersistenceFailure.Deadlock(RuntimeException("1213")) }

    test("a deadlock victim on the one-time issuance INSERT is LOST and the transaction is rolled back") {
        val tm = RecordingTm()
        service(tm, onIssuance = deadlock, onProjection = {}).issue(DemoCredentialIssueCommand(mid, "demo-credential-u1")) shouldBe DemoCredentialIssueResult.Lost
        (tm.commits to tm.rollbacks) shouldBe (0 to 1)
    }

    test("a deadlock on the projection INSERT is not classified (propagates — exit 1) and the transaction is rolled back") {
        val tm = RecordingTm()
        shouldThrow<PersistenceFailure.Deadlock> {
            service(tm, onIssuance = {}, onProjection = deadlock).issue(DemoCredentialIssueCommand(mid, "demo-credential-u2"))
        }
        (tm.commits to tm.rollbacks) shouldBe (0 to 1)
    }
})
