package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLTransientConnectionException

/**
 * 분류 규칙 단위 시험(원인 사슬의 MySQL 코드). 실제 MySQL에서 같은 분류가 나오는지는 PaymentStoreAdapterSpec·CreatePayment*Spec이
 * 확인한다(교착·잠금 timeout·연결 종료·중복 키를 실제로 일으킨다).
 */
class PersistenceFailuresSpec : BehaviorSpec({

    fun duplicate(key: String) =
        SQLIntegrityConstraintViolationException("Duplicate entry 'pk-SECRET-VALUE' for key '$key'", "23000", 1062)

    Given("a duplicate-key error wrapped by Spring/Hibernate") {
        Then("it becomes UniqueConflict naming the business constraint, without the duplicated value") {
            val failure = PersistenceFailures.classify("payment", DataIntegrityViolationException("x", RuntimeException(duplicate("payment.uk_payment_key"))))
            failure.shouldBeInstanceOf<PersistenceFailure.UniqueConflict>()
            failure.constraint shouldBe StoreConstraint.PAYMENT_KEY
            failure.message.orEmpty() shouldNotContain "SECRET"
            failure.cause.shouldBeNull()
        }

        Then("each table key maps to its business meaning and unknown keys to OTHER") {
            PersistenceFailures.constraintOf(duplicate("payment.PRIMARY").message) shouldBe StoreConstraint.PAYMENT_ID
            PersistenceFailures.constraintOf(duplicate("payment.uk_payment_order_generation").message) shouldBe
                StoreConstraint.PAYMENT_ORDER_GENERATION
            PersistenceFailures.constraintOf(duplicate("outbox_event.uk_outbox_event_effect_key").message) shouldBe
                StoreConstraint.OUTBOX_EFFECT_KEY
            PersistenceFailures.constraintOf(duplicate("outbox_event.uk_outbox_event_event_id").message) shouldBe
                StoreConstraint.OUTBOX_EVENT_ID
            PersistenceFailures.constraintOf(duplicate("incident_hold.uk_incident_hold_order_payment_reason").message) shouldBe
                StoreConstraint.INCIDENT_HOLD
            PersistenceFailures.constraintOf(duplicate("other.uk_x").message) shouldBe StoreConstraint.OTHER
            // 값 안에 key 문구가 들어 있어도 마지막(실제) key 이름을 쓴다.
            PersistenceFailures.constraintOf("Duplicate entry 'a' for key 'payment.PRIMARY'' for key 'payment.uk_payment_key'") shouldBe
                StoreConstraint.PAYMENT_KEY
        }
    }

    Given("lock, deadlock and connection errors") {
        Then("deadlock, lock wait timeout and connection loss are separated from conflicts") {
            PersistenceFailures.classify("x", RuntimeException(SQLException("Deadlock found", "40001", 1213)))
                .shouldBeInstanceOf<PersistenceFailure.Deadlock>()
            PersistenceFailures.classify("x", RuntimeException(SQLException("Lock wait timeout exceeded", "40001", 1205)))
                .shouldBeInstanceOf<PersistenceFailure.LockTimeout>()
            PersistenceFailures.classify("x", RuntimeException(SQLException("Communications link failure", "08S01", 0)))
                .shouldBeInstanceOf<PersistenceFailure.Unavailable>()
            PersistenceFailures.classify("x", SQLTransientConnectionException("pool timeout"))
                .shouldBeInstanceOf<PersistenceFailure.Unavailable>()
        }
    }

    Given("an optimistic version conflict") {
        Then("it becomes OptimisticConflict") {
            PersistenceFailures.classify("order_payment_control", ObjectOptimisticLockingFailureException("JpaX", "id"))
                .shouldBeInstanceOf<PersistenceFailure.OptimisticConflict>()
        }
    }

    Given("a defect-class error (CHECK violation) or a non-database error") {
        Then("it is not classified (the original exception propagates)") {
            PersistenceFailures.classify("x", RuntimeException(SQLException("Check constraint violated", "HY000", 3819))).shouldBeNull()
            PersistenceFailures.classify("x", IllegalStateException("bug")).shouldBeNull()
        }
    }
})
