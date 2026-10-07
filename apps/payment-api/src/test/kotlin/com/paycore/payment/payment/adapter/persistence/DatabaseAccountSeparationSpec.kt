package com.paycore.payment.payment.adapter.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.SQLException
import java.util.UUID

/**
 * Flyway migration 계정과 DML 제한 runtime 계정 분리 검토(CP-DB-001 acceptance). 실제 MySQL 8.4에서 소유 schema 하나에 두 계정을 만들고
 * (docker/mysql/init/01-owned-databases.sh 주석의 제안 grant와 같은 형태) 권한 경계가 실제로 지켜지는지 본다. 일회용 컨테이너 안의 임시
 * 계정·임의 비밀번호이며 운영 계정/키가 아니다. 운영·compose 적용은 compose.yaml/.env.example 변경이 필요한 후속(openItems).
 *
 * 오류 코드: 1142 = 테이블 명령 권한 없음, 1044 = DB 접근 권한 없음.
 */
@PaymentDbTest
class DatabaseAccountSeparationSpec(private val db: DbAccess) : BehaviorSpec({

    val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
    val schema = "paycore_payment_sep_$suffix"
    val migrator = "pc_mig_$suffix"
    val runtime = "pc_run_$suffix"
    val migratorPassword = UUID.randomUUID().toString()
    val runtimePassword = UUID.randomUUID().toString()

    beforeSpec {
        db.withRoot { c ->
            with(db) {
                c.update("CREATE DATABASE $schema")
                c.update("CREATE USER '$migrator'@'%' IDENTIFIED BY '$migratorPassword'")
                c.update("GRANT ALL PRIVILEGES ON $schema.* TO '$migrator'@'%'")
                c.update("CREATE USER '$runtime'@'%' IDENTIFIED BY '$runtimePassword'")
                c.update("GRANT SELECT, INSERT, UPDATE, DELETE ON $schema.* TO '$runtime'@'%'")
            }
        }
    }

    afterSpec {
        db.withRoot { c ->
            with(db) {
                c.update("DROP USER IF EXISTS '$migrator'@'%', '$runtime'@'%'")
                c.update("DROP DATABASE IF EXISTS $schema")
            }
        }
    }

    fun flyway(user: String, password: String): Flyway =
        Flyway.configure().dataSource(db.urlFor(schema), user, password).locations("classpath:db/migration/payment").load()

    Given("an owned PAYMENT schema with a migration account and a DML-only runtime account") {
        Then("the runtime account cannot create the schema (Flyway as runtime fails), the migration account applies V1") {
            shouldThrow<FlywayException> { flyway(runtime, runtimePassword).migrate() }
            flyway(migrator, migratorPassword).migrate().migrationsExecuted shouldBe 1
        }

        Then("the runtime account runs DML on business tables") {
            db.connect(schema, runtime, runtimePassword).use { c ->
                with(db) {
                    c.update(PaymentSchemaRows.INSERT_CONTROL, "TEST", "mid-sep", "order-1", "pay-1") shouldBe 1
                    c.update("UPDATE order_payment_control SET generation = 2 WHERE merchant_account_id = 'mid-sep'") shouldBe 1
                    (c.scalar("SELECT generation FROM order_payment_control WHERE merchant_account_id = 'mid-sep'") as Number).toLong() shouldBe 2
                    c.update("DELETE FROM order_payment_control WHERE merchant_account_id = 'mid-sep'") shouldBe 1
                }
            }
        }

        Then("the runtime account cannot change the schema (CREATE / ALTER / DROP / TRUNCATE denied)") {
            db.connect(schema, runtime, runtimePassword).use { c ->
                with(db) {
                    c.errorCodeOf("CREATE TABLE sneaky (id INT PRIMARY KEY)") shouldBe 1142
                    c.errorCodeOf("ALTER TABLE payment ADD COLUMN sneaky INT") shouldBe 1142
                    c.errorCodeOf("DROP TABLE outbox_event") shouldBe 1142
                    c.errorCodeOf("TRUNCATE TABLE idempotency_request") shouldBe 1142
                }
            }
        }

        Then("the runtime account cannot read another owned database (no cross-DB access)") {
            db.connect(schema, runtime, runtimePassword).use { c ->
                val denied = shouldThrow<SQLException> { with(db) { c.rows("SELECT COUNT(*) FROM ${db.databaseName}.payment") } }
                denied.errorCode shouldBeIn setOf(1142, 1044)
            }
        }
    }
})
