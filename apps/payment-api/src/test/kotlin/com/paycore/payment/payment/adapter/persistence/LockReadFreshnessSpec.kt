package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.attributes
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.created
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.issuedAt
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.session
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.token
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.attributes as paymentAttributes
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.requested
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.AuthenticationStatus
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.RequestedPaymentExpiry
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 잠금 읽기 최신성(AUTHN-001 중간 검토 #2 m-1, ARCHITECTURE-CONVENTIONS §10 함정). 같은 TX가 잠금 전에 비잠금으로 읽은 행(영속성 컨텍스트에 남은 Entity)을 다른 TX가
 * 바꾸고 커밋한 뒤 잠금 읽기를 하면, 잠금 Port는 **최신 커밋 값**을 돌려줘야 한다(JPQL `@Lock` 조회는 관리 중인 Entity를 다시 읽지 않아 오래된 상태·version을 준다).
 *
 * 실 MySQL 두 연결, 결정적 순서: TX A 비잠금 읽기 → TX B(다른 스레드) 변경·커밋 → TX A 잠금 읽기. 진 쪽(A)은 낙관적 충돌·UNIQUE 충돌이 아니라 바뀐 상태를 보고
 * 업무 판정("이미 사용됨")을 할 수 있어야 한다.
 */
@PaymentDbTest
class LockReadFreshnessSpec(
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }

    /** 다른 연결·TX에서 [block]을 실행하고 커밋까지 기다린다(현재 스레드의 TX와 섞이지 않게 별도 스레드). */
    fun <T> committedElsewhere(block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        try {
            return pool.submit<T> { inTx(block) }.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    fun storedPayment(): Payment = requested(newOrder()).also { p -> inTx { payments.insertNew(p, paymentAttributes()) } }

    Given("a token read by digest before another transaction consumed it") {
        Then("lock returns CONSUMED with the new version (the loser sees 'already used', not a stale ISSUED)") {
            val p = storedPayment()
            val t = token(p).also { inTx { tokens.insert(it) } }
            val seen = inTx {
                tokens.findByDigest(t.digest)!!.status shouldBe CheckoutTokenStatus.ISSUED
                committedElsewhere {
                    val locked = tokens.lock(t.id)!!
                    tokens.update(locked.value.consumed(issuedAt.plusSeconds(3)), locked.version)
                }
                tokens.lock(t.id)!!
            }
            seen.value.status shouldBe CheckoutTokenStatus.CONSUMED
            seen.version shouldBe 1L
        }
    }

    Given("a checkout session read by handle before another transaction revoked it") {
        Then("lock returns REVOKED with the new version") {
            val p = storedPayment()
            val t = token(p).also { inTx { tokens.insert(it) } }
            val s = session(t).also { inTx { checkoutSessions.insert(it) } }
            val seen = inTx {
                checkoutSessions.findByHandle(s.handle)!!.status shouldBe CheckoutSessionStatus.ACTIVE
                committedElsewhere {
                    val locked = checkoutSessions.lock(s.id)!!
                    checkoutSessions.update(locked.value.closed(CheckoutSessionCloseReason.SUPERSEDED, issuedAt.plusSeconds(4)), locked.version)
                }
                checkoutSessions.lock(s.id)!!
            }
            seen.value.status shouldBe CheckoutSessionStatus.REVOKED
            seen.version shouldBe 1L
        }
    }

    Given("an authentication session already in the persistence context when another transaction started it") {
        Then("lock and lockAllOfPayment return IN_PROGRESS with the new version (n-6: the version re-check compares fresh values)") {
            val p = storedPayment()
            val t = token(p).also { inTx { tokens.insert(it) } }
            val cs = session(t).also { inTx { checkoutSessions.insert(it) } }
            val s = created(p).also { inTx { sessions.insertNew(it, attributes(cs.id)) } }
            val (single, all) = inTx {
                entityManager.find(JpaAuthenticationSessionEntity::class.java, s.id.value)!!.status shouldBe "CREATED"
                committedElsewhere {
                    val locked = sessions.lock(s.id)!!
                    locked.value.start("sat_fresh0001")
                    sessions.update(locked.value, locked.version)
                }
                // 같은 TX에서 이미 잠근 행을 목록 잠금이 다시 읽어도 더 오래된 스냅샷(시험 TX는 MySQL 기본 REPEATABLE READ)으로 되돌아가지 않아야 한다.
                sessions.lock(s.id)!! to sessions.lockAllOfPayment(p.id)
            }
            single.value.status shouldBe AuthenticationStatus.IN_PROGRESS
            single.version shouldBe 1L
            all.single().value.status shouldBe AuthenticationStatus.IN_PROGRESS
            all.single().version shouldBe 1L
        }
    }

    Given("a payment read by its creation record before another transaction expired it") {
        Then("lock returns EXPIRED with the new version") {
            val p = storedPayment()
            val seen = inTx {
                payments.findCreationRecord(p.id)!!.paymentId shouldBe p.id
                committedElsewhere {
                    val locked = payments.lock(p.id)!!
                    RequestedPaymentExpiry.expire(locked.value, emptyList(), issuedAt.plusSeconds(5))
                    payments.update(locked.value, locked.version)
                }
                payments.lock(p.id)!!
            }
            seen.value.status shouldBe PaymentStatus.EXPIRED
            seen.version shouldBe 1L
        }
    }
})
