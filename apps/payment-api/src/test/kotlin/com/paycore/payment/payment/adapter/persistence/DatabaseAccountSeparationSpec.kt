package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.expire
import com.paycore.payment.PaymentDbAccounts
import com.paycore.payment.PaymentDbAccounts.FINANCIAL_DATABASE
import com.paycore.payment.PaymentDbAccounts.FINANCIAL_USER
import com.paycore.payment.PaymentDbAccounts.MASTER_DATABASE
import com.paycore.payment.PaymentDbAccounts.MASTER_MIGRATION_USER
import com.paycore.payment.PaymentDbAccounts.MASTER_RUNTIME_USER
import com.paycore.payment.PaymentDbAccounts.PAYMENT_GRANTOR
import com.paycore.payment.PaymentDbAccounts.PAYMENT_MIGRATION_USER
import com.paycore.payment.PaymentDbAccounts.PAYMENT_RUNTIME_ROLE
import com.paycore.payment.PaymentDbAccounts.PAYMENT_RUNTIME_USER
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.SQLException

/**
 * PAYMENT DB 계정·표 단위 runtime 권한(PAYMENT-DBACCOUNT-001, D1/D2). 실제 MySQL 8.4.11에 로컬 개발 초기화 스크립트
 * (docker/mysql/init/01-owned-databases.sh)를 그대로 넣고, 운영 migration(V1 업무 schema + V2 runtime 권한)을 migration 계정으로 적용한
 * 결과를 runtime 계정·migration 계정·root(관찰 전용)로 확인한다.
 *
 * 권한 구조: runtime 계정 `paycore_payment`는 DB 단위 권한이 없고 기본 role `paycore_payment_runtime`의 표 단위 권한만 가진다.
 * 표 단위 권한은 V2가 초기화 스크립트의 procedure `paycore_payment_privilege.set_runtime_table_privileges`(DEFINER = 잠긴 grantor,
 * 받는 쪽 = runtime role 고정)로 준다. migration 계정은 GRANT OPTION이 없고 그 procedure의 EXECUTE만 있다.
 *
 * 오류 코드: 1142 = 테이블 명령 권한 없음, 1044 = DB 접근 권한 없음, 1227 = 전역 권한 필요(CREATE USER·role 부여),
 * 1370 = routine 실행 권한 없음, 1644 = procedure의 SIGNAL(허용되지 않은 요청).
 *
 * CP-DB-001의 같은 이름 spec은 임시 계정에 DB 단위 DML(SELECT/INSERT/UPDATE/DELETE ON db.*)을 준 형태를 확인했다. 그 형태는 append-only 표의
 * UPDATE/DELETE를 막지 못해(CP-DB-001 review L-3) 이 작업에서 폐기했고, 이 spec은 실제 초기화 스크립트 계정으로 새 형태를 고정한다.
 */
@PaymentDbTest
class DatabaseAccountSeparationSpec(
    private val createPayment: CreatePaymentUseCase,
    private val incidents: IncidentReporter,
    private val payments: JpaPaymentRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val db: DbAccess,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val privilegeProcedure = "${PaymentDbAccounts.PAYMENT_DATABASE}_privilege.set_runtime_table_privileges"

    /**
     * 표별 runtime 권한(mysql.tables_priv 표기). 근거는 현재 코드의 실제 쓰기 경로(V2__payment_runtime_privileges.sql 주석·
     * ARCHITECTURE-CONVENTIONS §9 표): append-only 확정 사실 표(종료 근거·incident hold)는 SELECT, INSERT만, DELETE 경로는 어느 표에도 없다.
     */
    val expectedRuntimeTablePrivileges = mapOf(
        "payment" to "Select,Insert,Update",
        "payment_termination_evidence" to "Select,Insert",
        "order_payment_control" to "Select,Insert,Update",
        "incident_hold" to "Select,Insert",
        "idempotency_request" to "Select,Insert,Update",
        "outbox_event" to "Select,Insert",
        // MASTER-SNAPSHOT-001 V4: Inbox·revision별 내용·MID 소속은 append-only, head는 IODKU·잠금 읽기·갱신.
        "inbox_event" to "Select,Insert",
        "entitlement_head" to "Select,Insert,Update",
        "entitlement_snapshot" to "Select,Insert",
        "entitlement_mid_owner" to "Select,Insert",
        // CP-API-001 V6: Credential 투영은 읽기만(투영 적용 쓰기는 MASTER-CREDENTIAL-001이 그 경로와 함께 추가).
        // DEMO-SEED-001 V30(D-DEMO-002 — 사용자 결정 2026-10-31): DEMO 전용 `demo-credential` 명령의 INSERT만 추가(UPDATE·DELETE 없음, I-24). LIVE Gate 검토 항목.
        "merchant_api_credential" to "Select,Insert",
        // DEMO-SEED-001 V30: Demo Credential 1회성·감사 행은 INSERT 전용.
        "demo_credential_issuance" to "Select,Insert",
        // AUTHN-001 V8: 토큰·결제창 세션·인증 세션은 INSERT·UPDATE(삭제 Job 없음), 결정 로그와 채우기 기준 pin은 append-only.
        "checkout_token" to "Select,Insert,Update",
        "checkout_session" to "Select,Insert,Update",
        "authentication_session" to "Select,Insert,Update",
        "authentication_session_event" to "Select,Insert",
        "payment_checkout_window_pin" to "Select,Insert",
        // AUTH-001 V10(명세 §7.2): 승인·Attempt·사용량·점유는 INSERT·UPDATE, 관찰·거래는 append-only, 해제 기록은 읽기만(쓰기 경로 없음 — RECON이 추가).
        "payment_authorization" to "Select,Insert,Update",
        "authorization_attempt" to "Select,Insert,Update",
        "institution_observation" to "Select,Insert",
        "payment_transaction" to "Select,Insert",
        "daily_limit_usage" to "Select,Insert,Update",
        "limit_reservation" to "Select,Insert,Update",
        "incident_hold_release" to "Select",
        // AUTH-001 V12·V13(§16.11 D-S6-1): 회복 일정은 bookkeeping — INSERT(TX1)·UPDATE(TXd·TX2·회복), 삭제 경로 없음(결론나면 closed_at).
        "authorization_recovery_schedule" to "Select,Insert,Update",
        // CANCEL-001 V16·V19(명세 §8.2): 취소·Attempt·반환 잔액·예약은 INSERT·UPDATE, 취소 관찰은 append-only, 취소 회복 일정은 bookkeeping(삭제 경로 없음).
        "payment_cancellation" to "Select,Insert,Update",
        "cancel_attempt" to "Select,Insert,Update",
        "cancel_observation" to "Select,Insert",
        "return_balance" to "Select,Insert,Update",
        "return_reservation" to "Select,Insert,Update",
        "cancellation_recovery_schedule" to "Select,Insert,Update",
        // D-CANCEL1-006: 운영자 명령 거절 감사는 append-only.
        "operator_cancel_rejection" to "Select,Insert",
        // WEBHOOK-001 V22: guard는 fan-out FOR UPDATE 직렬화(잠금 읽기에 UPDATE 필요), 순번은 IODKU, 가맹점 이벤트는 insert-only, 전달은 INSERT·UPDATE(삭제 경로 없음).
        "merchant_webhook_fanout_guard" to "Select,Insert,Update",
        "merchant_webhook_stream" to "Select,Insert,Update",
        "merchant_webhook_event" to "Select,Insert",
        "merchant_webhook_delivery" to "Select,Insert,Update",
        // WEBHOOK-001 V24: fan-out 비종결 실패 기록(IODKU, 삭제 경로 없음).
        "merchant_notification_failure" to "Select,Insert,Update",
        // WEBHOOK-001 V26: 전송 시도 기록은 append-only.
        "merchant_webhook_attempt" to "Select,Insert",
        // ADMIN-PAYMENT-OPS-001 V28: approvalId별 최초 확정 결정은 INSERT 전용(D-OPS-005).
        "operator_approval_decision" to "Select,Insert",
    )

    fun grantsOf(account: String): Set<String> = db.withRoot { c ->
        c.createStatement().use { s ->
            s.executeQuery("SHOW GRANTS FOR $account").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }


    /** PAYMENT 계정 4개 + 같은 서버의 MASTER runtime/migration 계정의 SHOW GRANTS 전체(V1·V2 적용 뒤의 고정 기대값). */
    val expectedAccountGrants = mapOf(
        PAYMENT_RUNTIME_USER to setOf(
            "GRANT USAGE ON *.* TO `paycore_payment`@`%`",
            "GRANT `paycore_payment_runtime`@`%` TO `paycore_payment`@`%`",
        ),
        PAYMENT_RUNTIME_ROLE to setOf(
            "GRANT USAGE ON *.* TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`idempotency_request` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`incident_hold` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`order_payment_control` TO `paycore_payment_runtime`@`%`",
            // D-WH-009(WEBHOOK-001 V24): 알림 표시 두 열만 열 단위 UPDATE. 나머지 열 UPDATE는 1143, DELETE는 1142(아래 시험).
            "GRANT SELECT, INSERT, UPDATE (`merchant_notification_status`, `merchant_notified_at`) ON `paycore_payment`.`outbox_event` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`payment` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`payment_termination_evidence` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`inbox_event` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`entitlement_head` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`entitlement_snapshot` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`entitlement_mid_owner` TO `paycore_payment_runtime`@`%`",
            // DEMO-SEED-001 V30(D-DEMO-002): Demo Credential 명령의 INSERT만 추가(UPDATE·DELETE 없음).
            "GRANT SELECT, INSERT ON `paycore_payment`.`merchant_api_credential` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`checkout_token` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`checkout_session` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`authentication_session` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`authentication_session_event` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`payment_checkout_window_pin` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`payment_authorization` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`authorization_attempt` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`institution_observation` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`payment_transaction` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`daily_limit_usage` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`limit_reservation` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT ON `paycore_payment`.`incident_hold_release` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`authorization_recovery_schedule` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`payment_cancellation` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`cancel_attempt` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`cancel_observation` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`return_balance` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`return_reservation` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`cancellation_recovery_schedule` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`operator_cancel_rejection` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`merchant_webhook_fanout_guard` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`merchant_webhook_stream` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`merchant_webhook_event` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`merchant_webhook_delivery` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT, UPDATE ON `paycore_payment`.`merchant_notification_failure` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`merchant_webhook_attempt` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`operator_approval_decision` TO `paycore_payment_runtime`@`%`",
            "GRANT SELECT, INSERT ON `paycore_payment`.`demo_credential_issuance` TO `paycore_payment_runtime`@`%`",
        ),
        PAYMENT_MIGRATION_USER to setOf(
            "GRANT USAGE ON *.* TO `paycore_payment_migration`@`%`",
            "GRANT ALL PRIVILEGES ON `paycore_payment`.* TO `paycore_payment_migration`@`%`",
            "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`set_runtime_table_privileges` TO `paycore_payment_migration`@`%`",
            "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`grant_runtime_column_update` TO `paycore_payment_migration`@`%`",
        ),
        PAYMENT_GRANTOR to setOf(
            "GRANT USAGE ON *.* TO `paycore_payment_grantor`@`localhost`",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_payment`.* TO `paycore_payment_grantor`@`localhost` WITH GRANT OPTION",
            "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`set_runtime_table_privileges` TO `paycore_payment_grantor`@`localhost`",
            "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`grant_runtime_column_update` TO `paycore_payment_grantor`@`localhost`",
        ),
        MASTER_RUNTIME_USER to setOf(
            "GRANT USAGE ON *.* TO `paycore_master`@`%`",
            "GRANT `paycore_master_runtime`@`%` TO `paycore_master`@`%`",
        ),
        MASTER_MIGRATION_USER to setOf(
            "GRANT USAGE ON *.* TO `paycore_master_migration`@`%`",
            "GRANT ALL PRIVILEGES ON `paycore_master`.* TO `paycore_master_migration`@`%`",
            "GRANT EXECUTE ON PROCEDURE `paycore_master_privilege`.`set_runtime_table_privileges` TO `paycore_master_migration`@`%`",
        ),
    )

    fun actualAccountGrants(): Map<String, Set<String>> = mapOf(
        PAYMENT_RUNTIME_USER to grantsOf("'$PAYMENT_RUNTIME_USER'@'%'"),
        PAYMENT_RUNTIME_ROLE to grantsOf("'$PAYMENT_RUNTIME_ROLE'@'%'"),
        PAYMENT_MIGRATION_USER to grantsOf("'$PAYMENT_MIGRATION_USER'@'%'"),
        PAYMENT_GRANTOR to grantsOf("'$PAYMENT_GRANTOR'@'localhost'"),
        MASTER_RUNTIME_USER to grantsOf("'$MASTER_RUNTIME_USER'@'%'"),
        MASTER_MIGRATION_USER to grantsOf("'$MASTER_MIGRATION_USER'@'%'"),
    )

    /** root로 단일 값 조회. */
    fun rootScalar(sql: String, vararg args: Any?): Any? = db.withRoot { c -> with(db) { c.scalar(sql, *args) } }

    /** role의 표 하나에 대한 runtime 권한(mysql.tables_priv 표기, 없으면 null). */
    fun runtimeTablePrivilege(table: String): String? =
        rootScalar(
            "SELECT Table_priv FROM mysql.tables_priv WHERE User = ? AND Host = '%' AND Db = ? AND Table_name = ?",
            PAYMENT_RUNTIME_ROLE, PaymentDbAccounts.PAYMENT_DATABASE, table,
        )?.toString()

    /** 문장 하나를 실행하고 MySQL vendor 오류 코드(성공이면 null)를 돌려준다(SELECT·CALL·USE·DDL 모두 execute로 보낸다). */
    fun Connection.codeOf(sql: String): Int? =
        try {
            createStatement().use { it.execute(sql) }
            null
        } catch (e: SQLException) {
            e.errorCode
        }

    /** 업무 경로로 종료 근거 1건(EXPIRED)과 incident hold 1건을 만든다(runtime 계정 pool). */
    fun expiredPaymentWithHold(): Pair<OrderKey, PaymentId> {
        val order = newOrder()
        val created = createPayment.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        val paymentId = created.payment.paymentId
        tx.executeWithoutResult {
            val p = payments.lock(paymentId)!!
            p.value.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            payments.update(p.value, p.version)
        }
        incidents.report(order, paymentId, t0)
        return order to paymentId
    }

    Given("the application pool connects with the PAYMENT runtime account") {
        Then("only the runtime role is active and the account has no database-level privileges") {
            jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String::class.java) shouldBe "$PAYMENT_RUNTIME_USER@%"
            jdbcTemplate.queryForObject("SELECT CURRENT_ROLE()", String::class.java) shouldBe "`$PAYMENT_RUNTIME_ROLE`@`%`"
            db.withRoot { c ->
                with(db) {
                    c.scalar("SELECT COUNT(*) FROM mysql.db WHERE User = ? AND Db = ?", PAYMENT_RUNTIME_USER, PaymentDbAccounts.PAYMENT_DATABASE)
                }
            }.let { (it as Number).toLong() } shouldBe 0
        }

        Then("the business path (CreatePayment, expiry with evidence, retry on generation 2, incident hold) succeeds within its privileges") {
            val order = newOrder()
            val first = createPayment.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            tx.executeWithoutResult {
                val p = payments.lock(first.payment.paymentId)!!
                p.value.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
                payments.update(p.value, p.version)
            }
            val second = createPayment.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            second.outcome shouldBe CreatePaymentOutcome.CREATED
            incidents.report(order, first.payment.paymentId, t0)

            val mid = order.merchantAccountId.value
            mapOf(
                "payment" to db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", mid),
                "evidence" to db.count(
                    "SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ?", first.payment.paymentId.value,
                ),
                "generation" to db.count("SELECT generation FROM order_payment_control WHERE merchant_account_id = ?", mid),
                "idempotency" to db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ?", mid),
                "outbox" to db.count("SELECT COUNT(*) FROM outbox_event WHERE payload->>'$.merchantAccountId' = ?", mid),
                "hold" to db.count("SELECT COUNT(*) FROM incident_hold WHERE merchant_account_id = ?", mid),
            ) shouldBe mapOf("payment" to 2L, "evidence" to 1L, "generation" to 2L, "idempotency" to 2L, "outbox" to 2L, "hold" to 1L)
        }

        Then("append-only confirmed-fact tables reject UPDATE and DELETE with 1142 and the rows stay unchanged") {
            val (order, paymentId) = expiredPaymentWithHold()
            val mid = order.merchantAccountId.value
            db.withApp { c ->
                mapOf(
                    "evidence update" to c.codeOf(
                        "UPDATE payment_termination_evidence SET evidence = 'TIMEOUT' WHERE payment_id = '${paymentId.value}'",
                    ),
                    "evidence delete" to c.codeOf("DELETE FROM payment_termination_evidence WHERE payment_id = '${paymentId.value}'"),
                    "hold update" to c.codeOf("UPDATE incident_hold SET payment_id = 'other' WHERE merchant_account_id = '$mid'"),
                    "hold delete" to c.codeOf("DELETE FROM incident_hold WHERE merchant_account_id = '$mid'"),
                    "evidence lock" to c.codeOf("SELECT * FROM payment_termination_evidence WHERE payment_id = '${paymentId.value}' FOR UPDATE"),
                    // AUTHN-001: 인증 세션 결정 로그·채우기 기준 pin도 append-only다.
                    "auth event update" to c.codeOf("UPDATE authentication_session_event SET kind = 'CREATED' WHERE authentication_session_id = 'none'"),
                    "auth event delete" to c.codeOf("DELETE FROM authentication_session_event WHERE authentication_session_id = 'none'"),
                    "auth event lock" to c.codeOf("SELECT * FROM authentication_session_event WHERE authentication_session_id = 'none' FOR UPDATE"),
                    "pin update" to c.codeOf("UPDATE payment_checkout_window_pin SET validity_version = 'x' WHERE pin_id = 1"),
                    "pin delete" to c.codeOf("DELETE FROM payment_checkout_window_pin WHERE pin_id = 1"),
                    // AUTH-001: 기관 결과 관찰·확정 금융 사실(거래)은 append-only, 해제 기록은 이 작업에서 쓰지 않는다.
                    "observation update" to c.codeOf("UPDATE institution_observation SET outcome = 'APPROVED' WHERE attempt_id = 'none'"),
                    "observation delete" to c.codeOf("DELETE FROM institution_observation WHERE attempt_id = 'none'"),
                    "observation lock" to c.codeOf("SELECT * FROM institution_observation WHERE attempt_id = 'none' FOR UPDATE"),
                    "transaction update" to c.codeOf("UPDATE payment_transaction SET amount_minor = 1 WHERE transaction_id = 'none'"),
                    "transaction delete" to c.codeOf("DELETE FROM payment_transaction WHERE transaction_id = 'none'"),
                    "release insert" to c.codeOf(
                        "INSERT INTO incident_hold_release (incident_hold_id, requested_by, approved_by, reconciliation_match_ref, finding, reason, released_at) " +
                            "VALUES (0, 'a', 'b', 'm', 'NO_EFFECT_CONFIRMED', 'r', NOW(3))",
                    ),
                    // ADMIN-PAYMENT-OPS-001 V28(review n-2): approvalId별 결정 기록은 INSERT 전용.
                    "decision update" to c.codeOf("UPDATE operator_approval_decision SET decision = 'REJECTED' WHERE approval_id = 'none'"),
                    "decision delete" to c.codeOf("DELETE FROM operator_approval_decision WHERE approval_id = 'none'"),
                    "decision lock" to c.codeOf("SELECT * FROM operator_approval_decision WHERE approval_id = 'none' FOR UPDATE"),
                )
            } shouldBe mapOf(
                "evidence update" to 1142, "evidence delete" to 1142, "hold update" to 1142, "hold delete" to 1142, "evidence lock" to 1142,
                "auth event update" to 1142, "auth event delete" to 1142, "auth event lock" to 1142, "pin update" to 1142, "pin delete" to 1142,
                "observation update" to 1142, "observation delete" to 1142, "observation lock" to 1142, "transaction update" to 1142, "transaction delete" to 1142,
                "release insert" to 1142, "decision update" to 1142, "decision delete" to 1142, "decision lock" to 1142,
            )
            db.count(
                "SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ? AND evidence = 'NOT_TRANSMITTED_AND_SESSION_DISCARDED'",
                paymentId.value,
            ) shouldBe 1
            db.count("SELECT COUNT(*) FROM incident_hold WHERE merchant_account_id = ? AND payment_id = ?", mid, paymentId.value) shouldBe 1
        }

        Then("no table can be deleted from (no DELETE path in the current code) and outbox events cannot be updated") {
            db.withApp { c ->
                mapOf(
                    "payment delete" to c.codeOf("DELETE FROM payment WHERE payment_id = 'none'"),
                    "control delete" to c.codeOf("DELETE FROM order_payment_control WHERE merchant_account_id = 'none'"),
                    "idempotency delete" to c.codeOf("DELETE FROM idempotency_request WHERE merchant_account_id = 'none'"),
                    "outbox delete" to c.codeOf("DELETE FROM outbox_event WHERE event_id = 'none'"),
                    "outbox update" to c.codeOf("UPDATE outbox_event SET publish_status = 'PENDING' WHERE event_id = 'none'"),
                    "token delete" to c.codeOf("DELETE FROM checkout_token WHERE checkout_token_id = 'none'"),
                    "checkout session delete" to c.codeOf("DELETE FROM checkout_session WHERE checkout_session_id = 'none'"),
                    "auth session delete" to c.codeOf("DELETE FROM authentication_session WHERE authentication_session_id = 'none'"),
                    "authorization delete" to c.codeOf("DELETE FROM payment_authorization WHERE authorization_id = 'none'"),
                    "attempt delete" to c.codeOf("DELETE FROM authorization_attempt WHERE attempt_id = 'none'"),
                    "usage delete" to c.codeOf("DELETE FROM daily_limit_usage WHERE merchant_account_id = 'none'"),
                    "reservation delete" to c.codeOf("DELETE FROM limit_reservation WHERE authorization_id = 'none'"),
                    "recovery schedule delete" to c.codeOf("DELETE FROM authorization_recovery_schedule WHERE attempt_id = 'none'"),
                )
            } shouldBe mapOf(
                "payment delete" to 1142, "control delete" to 1142, "idempotency delete" to 1142, "outbox delete" to 1142,
                "outbox update" to 1143, "token delete" to 1142, "checkout session delete" to 1142, "auth session delete" to 1142,
                "authorization delete" to 1142, "attempt delete" to 1142, "usage delete" to 1142, "reservation delete" to 1142,
                "recovery schedule delete" to 1142,
            )
        }

        Then("D-WH-009: on outbox_event only the two merchant notification columns are updatable; every other column is 1143, DELETE stays 1142") {
            val columns = db.withRoot { c ->
                with(db) {
                    c.rows("SELECT COLUMN_NAME AS col FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'outbox_event'", PaymentDbAccounts.PAYMENT_DATABASE)
                }
            }.map { it["col"] as String }
            val notification = setOf("merchant_notification_status", "merchant_notified_at")
            columns.containsAll(notification + setOf("publish_status", "payload", "event_id", "aggregate_id")) shouldBe true
            val codes = db.withApp { c ->
                columns.associateWith { col -> c.codeOf("UPDATE outbox_event SET `$col` = `$col` WHERE id = -1") }
            }
            // 알림 열 두 개만 허용(행 없음 — 0행 UPDATE 성공), 나머지 전부(publish_status·payload·집계 열 포함) 열 접근 거부.
            codes.filterKeys { it in notification } shouldBe notification.associateWith { null }
            codes.filterKeys { it !in notification }.values.toSet() shouldBe setOf(1143)
            codes.size shouldBe columns.size
            db.withApp { c ->
                mapOf(
                    "delete" to c.codeOf("DELETE FROM outbox_event WHERE id = -1"),
                    // 수용한 부수 효과(D-WH-009): 열 UPDATE 권한이 잠금 읽기를 연다. 현재 코드에 Outbox 잠금 읽기 경로는 없다(fan-out은 guard 직렬화 + PK UPDATE).
                    "lock read" to c.codeOf("SELECT id FROM outbox_event WHERE id = -1 FOR UPDATE"),
                )
            } shouldBe mapOf("delete" to 1142, "lock read" to null)
        }

        Then("the column privilege helper grants UPDATE only on existing columns of a base table, with validated names") {
            val helper = "${PaymentDbAccounts.PAYMENT_DATABASE}_privilege.grant_runtime_column_update"
            val refused = db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                mapOf(
                    "missing column" to c.codeOf("CALL $helper('outbox_event', 'no_such_column')"),
                    "one missing of two" to c.codeOf("CALL $helper('outbox_event', 'merchant_notified_at, no_such_column')"),
                    "injection" to c.codeOf("CALL $helper('outbox_event', 'payload) ON *.* TO paycore_master; --')"),
                    "missing table" to c.codeOf("CALL $helper('no_such_table', 'id')"),
                    "flyway history" to c.codeOf("CALL $helper('flyway_schema_history', 'version')"),
                    "upper case" to c.codeOf("CALL $helper('outbox_event', 'PAYLOAD')"),
                    "empty" to c.codeOf("CALL $helper('outbox_event', '')"),
                )
            }
            refused.values.toSet() shouldBe setOf(1644)
            db.withApp { c -> c.codeOf("CALL $helper('outbox_event', 'payload')") } shouldBe 1370
            // 거부된 호출은 권한을 넓히지 않았다.
            db.withApp { c -> c.codeOf("UPDATE outbox_event SET payload = payload WHERE id = -1") } shouldBe 1143
            // 재실행 안전(멱등): 같은 열을 다시 주어도 권한 줄은 그대로다.
            db.connectAs(PAYMENT_MIGRATION_USER).use { c -> c.codeOf("CALL $helper('outbox_event', 'merchant_notification_status, merchant_notified_at')") } shouldBe null
            grantsOf("'$PAYMENT_RUNTIME_ROLE'@'%'").filter { it.contains("`outbox_event`") } shouldBe listOf(
                "GRANT SELECT, INSERT, UPDATE (`merchant_notification_status`, `merchant_notified_at`) ON `paycore_payment`.`outbox_event` TO `paycore_payment_runtime`@`%`",
            )
        }

        Then("it cannot change the schema, read the Flyway history or call the privilege procedure") {
            db.withApp { c ->
                mapOf(
                    "create" to c.codeOf("CREATE TABLE sneaky (id INT PRIMARY KEY)"),
                    "alter" to c.codeOf("ALTER TABLE payment ADD COLUMN sneaky INT"),
                    "drop" to c.codeOf("DROP TABLE outbox_event"),
                    "truncate" to c.codeOf("TRUNCATE TABLE idempotency_request"),
                    "history" to c.codeOf("SELECT COUNT(*) FROM flyway_schema_history"),
                    "procedure" to c.codeOf("CALL $privilegeProcedure('incident_hold', 'SELECT, INSERT, UPDATE, DELETE')"),
                )
            } shouldBe mapOf("create" to 1142, "alter" to 1142, "drop" to 1142, "truncate" to 1142, "history" to 1142, "procedure" to 1370)
        }

        Then("it cannot read other owned databases (no cross-DB access)") {
            db.withApp { c ->
                c.codeOf("SELECT COUNT(*) FROM $MASTER_DATABASE.flyway_schema_history")!! shouldBeIn setOf(1142, 1044)
                c.codeOf("SELECT COUNT(*) FROM $FINANCIAL_DATABASE.flyway_schema_history")!! shouldBeIn setOf(1142, 1044)
            }
        }
    }

    Given("the PAYMENT migration account (Flyway)") {
        Then("Flyway installed every migration as the migration account in a +00:00 session although the server default is not UTC") {
            db.withRoot { c ->
                with(db) {
                    mapOf(
                        "global" to c.scalar("SELECT @@global.time_zone"),
                        "system" to c.scalar("SELECT @@system_time_zone"),
                        "installedBy" to c.rows("SELECT DISTINCT installed_by AS u FROM flyway_schema_history").map { it["u"] }.toSet(),
                        "versions" to c.rows("SELECT version AS v FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank")
                            .map { it["v"] },
                        "probe" to c.rows("SELECT session_zone AS z, migration_user AS u FROM tech_flyway_session_probe").single(),
                    )
                }
            } shouldBe mapOf(
                "global" to "SYSTEM",
                "system" to "KST",
                "installedBy" to setOf(PAYMENT_MIGRATION_USER),
                "versions" to listOf("1", "2", "3", "4", "5", "6", "7", "8", "8.1", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "950"),
                "probe" to mapOf("z" to "+00:00", "u" to "$PAYMENT_MIGRATION_USER@%"),
            )
        }

        Then("it cannot grant privileges to any account directly, grant the runtime role, or create accounts") {
            db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                mapOf(
                    "to master runtime" to c.codeOf("GRANT SELECT ON payment TO '$MASTER_RUNTIME_USER'@'%'"),
                    "to financial" to c.codeOf("GRANT SELECT ON payment_termination_evidence TO '$FINANCIAL_USER'@'%'"),
                    "to payment runtime account" to c.codeOf("GRANT UPDATE, DELETE ON incident_hold TO '$PAYMENT_RUNTIME_USER'@'%'"),
                    "to runtime role" to c.codeOf("GRANT DELETE ON payment_termination_evidence TO '$PAYMENT_RUNTIME_ROLE'@'%'"),
                    "role to outside" to c.codeOf("GRANT '$PAYMENT_RUNTIME_ROLE'@'%' TO '$MASTER_RUNTIME_USER'@'%'"),
                    "create user" to c.codeOf("CREATE USER 'paycore_outside'@'%' IDENTIFIED BY 'x'"),
                )
            } shouldBe mapOf(
                "to master runtime" to 1142, "to financial" to 1142, "to payment runtime account" to 1142, "to runtime role" to 1142,
                "role to outside" to 1227, "create user" to 1227,
            )
            db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                // DB 단위 GRANT도 거부된다(오류 코드는 DB 접근 거부 1044 또는 명령 거부 1142).
                c.codeOf("GRANT SELECT ON ${PaymentDbAccounts.PAYMENT_DATABASE}.* TO '$MASTER_RUNTIME_USER'@'%'")!! shouldBeIn setOf(1044, 1142)
            }
            grantsOf("'$MASTER_RUNTIME_USER'@'%'") shouldBe setOf(
                "GRANT USAGE ON *.* TO `$MASTER_RUNTIME_USER`@`%`",
                "GRANT `paycore_master_runtime`@`%` TO `$MASTER_RUNTIME_USER`@`%`",
            )
        }

        Then("the privilege procedure only accepts the allowed privilege sets on base tables of this database") {
            val refused = db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                c.codeOf("CREATE VIEW tech_privilege_view AS SELECT payment_id FROM payment") shouldBe null
                try {
                    mapOf(
                        "extra privilege" to c.codeOf("CALL $privilegeProcedure('payment', 'SELECT, INSERT, UPDATE, DELETE, DROP')"),
                        "grantee injection" to c.codeOf(
                            "CALL $privilegeProcedure('payment', 'SELECT ON *.* TO paycore_master; --')",
                        ),
                        "lower case" to c.codeOf("CALL $privilegeProcedure('payment', 'select')"),
                        "flyway history" to c.codeOf("CALL $privilegeProcedure('flyway_schema_history', 'SELECT')"),
                        "missing table" to c.codeOf("CALL $privilegeProcedure('no_such_table', 'SELECT')"),
                        "view" to c.codeOf("CALL $privilegeProcedure('tech_privilege_view', 'SELECT')"),
                        "quoted name" to c.codeOf("CALL $privilegeProcedure('payment` TO paycore_master; --', 'SELECT')"),
                        "trailing newline" to c.codeOf("CALL $privilegeProcedure('payment\n', 'SELECT')"),
                        "carriage return" to c.codeOf("CALL $privilegeProcedure('pay\rment', 'SELECT')"),
                    )
                } finally {
                    c.codeOf("DROP VIEW tech_privilege_view") shouldBe null
                }
            }
            refused shouldBe mapOf(
                "extra privilege" to 1644, "grantee injection" to 1644, "lower case" to 1644, "flyway history" to 1644,
                "missing table" to 1644, "view" to 1644, "quoted name" to 1644, "trailing newline" to 1644, "carriage return" to 1644,
            )
            // 거부된 호출은 권한을 바꾸지 않았다(아래 SHOW GRANTS 고정 시험과 함께).
            grantsOf("'$MASTER_RUNTIME_USER'@'%'").none { it.contains("paycore_payment") } shouldBe true
        }

        Then("the privilege procedure replaces a table's runtime privileges with exactly the given set (narrowing and widening)") {
            val table = "tech_privilege_probe"
            db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                c.codeOf("CREATE TABLE $table (id INT PRIMARY KEY)") shouldBe null
                try {
                    val observed = listOf(
                        "SELECT, INSERT, UPDATE, DELETE", "SELECT", "SELECT, INSERT, UPDATE", "SELECT, INSERT", "SELECT, INSERT, UPDATE, DELETE",
                        "SELECT, INSERT",
                    ).map { set ->
                        c.codeOf("CALL $privilegeProcedure('$table', '$set')") shouldBe null
                        set to runtimeTablePrivilege(table)
                    }
                    observed shouldBe listOf(
                        "SELECT, INSERT, UPDATE, DELETE" to "Select,Insert,Update,Delete",
                        "SELECT" to "Select",
                        "SELECT, INSERT, UPDATE" to "Select,Insert,Update",
                        "SELECT, INSERT" to "Select,Insert",
                        "SELECT, INSERT, UPDATE, DELETE" to "Select,Insert,Update,Delete",
                        "SELECT, INSERT" to "Select,Insert",
                    )
                } finally {
                    // DROP TABLE은 표 단위 권한 행을 지우지 않는다(MySQL). 시험 표의 권한은 root가 지운다.
                    db.withRoot { r -> r.codeOf("REVOKE IF EXISTS ALL PRIVILEGES ON paycore_payment.$table FROM '$PAYMENT_RUNTIME_ROLE'@'%'") }
                    c.codeOf("DROP TABLE $table") shouldBe null
                }
            }
            runtimeTablePrivilege(table) shouldBe null
        }

        Then("it cannot use other owned databases or their privilege procedures, nor change its own privilege procedure") {
            db.connectAs(PAYMENT_MIGRATION_USER).use { c ->
                mapOf(
                    "use master" to c.codeOf("USE $MASTER_DATABASE"),
                    "use financial" to c.codeOf("USE $FINANCIAL_DATABASE"),
                    "master procedure" to c.codeOf("CALL ${MASTER_DATABASE}_privilege.set_runtime_table_privileges('x', 'SELECT')"),
                    "drop procedure" to c.codeOf("DROP PROCEDURE $privilegeProcedure"),
                    "privilege schema table" to c.codeOf("CREATE TABLE ${PaymentDbAccounts.PAYMENT_DATABASE}_privilege.sneaky (id INT)"),
                )
            } shouldBe mapOf(
                "use master" to 1044, "use financial" to 1044, "master procedure" to 1370, "drop procedure" to 1370,
                "privilege schema table" to 1142,
            )
        }
    }

    Given("the resulting PAYMENT accounts and privileges") {
        Then("SHOW GRANTS is exact: runtime account = role only, role = table-level grants, migration = DB ALL + procedure EXECUTE, grantor locked") {
            actualAccountGrants() shouldBe expectedAccountGrants
            db.withRoot { c ->
                with(db) {
                    c.rows(
                        "SELECT User AS u, account_locked AS l FROM mysql.user WHERE User IN (?, ?, ?, ?) ORDER BY User",
                        PAYMENT_RUNTIME_USER, PAYMENT_RUNTIME_ROLE, PAYMENT_MIGRATION_USER, PAYMENT_GRANTOR,
                    ).associate { it["u"] to it["l"] }
                }
            } shouldBe mapOf(PAYMENT_RUNTIME_USER to "N", PAYMENT_GRANTOR to "Y", PAYMENT_MIGRATION_USER to "N", PAYMENT_RUNTIME_ROLE to "Y")
        }

        Then("every business table has an explicit runtime grant (a new table without a privilege migration fails here)") {
            db.withRoot { c ->
                with(db) {
                    val tables = c.rows(
                        "SELECT TABLE_NAME AS t FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
                            "AND TABLE_NAME NOT IN ('flyway_schema_history', 'tech_flyway_session_probe')",
                        PaymentDbAccounts.PAYMENT_DATABASE,
                    ).map { it["t"] as String }.toSet()
                    val granted = c.rows(
                        "SELECT Table_name AS t, Table_priv AS p FROM mysql.tables_priv WHERE User = ? AND Host = '%' AND Db = ?",
                        PAYMENT_RUNTIME_ROLE, PaymentDbAccounts.PAYMENT_DATABASE,
                    ).associate { it["t"] as String to it["p"].toString() }
                    tables shouldBe expectedRuntimeTablePrivileges.keys
                    granted shouldBe expectedRuntimeTablePrivileges
                }
            }
        }

        Then("no triggers, routines, events or views exist in the owned databases (any future use needs a reviewed expectation change)") {
            listOf(PaymentDbAccounts.PAYMENT_DATABASE, MASTER_DATABASE).associateWith { schema ->
                mapOf(
                    "triggers" to rootScalar("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ?", schema),
                    "routines" to rootScalar("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ?", schema),
                    "events" to rootScalar("SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ?", schema),
                    "views" to rootScalar("SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ?", schema),
                ).mapValues { (it.value as Number).toLong() }
            } shouldBe listOf(PaymentDbAccounts.PAYMENT_DATABASE, MASTER_DATABASE).associateWith {
                mapOf("triggers" to 0L, "routines" to 0L, "events" to 0L, "views" to 0L)
            }
        }
    }

    Given("a volume created before MASTER-BOOT-001 (runtime account with ALL on its DB WITH GRANT OPTION) and then upgraded by re-running the init script") {
        Then("the runtime accounts end with no database-level privilege, the migration accounts without GRANT OPTION, grantors locked, table grants kept") {
            db.withRoot { r ->
                for (owner in listOf(PaymentDbAccounts.PAYMENT_DATABASE, MASTER_DATABASE)) {
                    r.codeOf("GRANT ALL PRIVILEGES ON $owner.* TO '$owner'@'%' WITH GRANT OPTION") shouldBe null
                    r.codeOf("GRANT ALL PRIVILEGES ON $owner.* TO '${owner}_migration'@'%' WITH GRANT OPTION") shouldBe null
                    r.codeOf("ALTER USER '${owner}_grantor'@'localhost' ACCOUNT UNLOCK") shouldBe null
                }
            }
            // 사전 상태가 실제로 위험한지(대조): runtime 계정이 DB 단위 ALL을 가진다.
            (rootScalar("SELECT COUNT(*) FROM mysql.db WHERE User = ? AND Db = ?", PAYMENT_RUNTIME_USER, PaymentDbAccounts.PAYMENT_DATABASE) as Number)
                .toLong() shouldBe 1

            val rerun = container.execInContainer("bash", "/docker-entrypoint-initdb.d/01-owned-databases.sh")
            rerun.exitCode shouldBe 0

            mapOf(
                "runtime db rows" to rootScalar(
                    "SELECT COUNT(*) FROM mysql.db WHERE User IN (?, ?)", PAYMENT_RUNTIME_USER, MASTER_RUNTIME_USER,
                ).let { (it as Number).toLong() },
                "grant option db rows" to rootScalar(
                    "SELECT COUNT(*) FROM mysql.db WHERE User IN (?, ?, ?, ?) AND Grant_priv = 'Y'",
                    PAYMENT_RUNTIME_USER, MASTER_RUNTIME_USER, PAYMENT_MIGRATION_USER, MASTER_MIGRATION_USER,
                ).let { (it as Number).toLong() },
                "unlocked grantors" to rootScalar(
                    "SELECT COUNT(*) FROM mysql.user WHERE User IN ('paycore_payment_grantor', 'paycore_master_grantor') AND account_locked <> 'Y'",
                ).let { (it as Number).toLong() },
            ) shouldBe mapOf("runtime db rows" to 0L, "grant option db rows" to 0L, "unlocked grantors" to 0L)
            actualAccountGrants() shouldBe expectedAccountGrants
            // 업그레이드 뒤에도 append-only 표는 runtime이 지우지 못한다.
            db.withApp { c ->
                mapOf(
                    "evidence delete" to c.codeOf("DELETE FROM payment_termination_evidence WHERE payment_id = 'none'"),
                    "hold drop" to c.codeOf("DROP TABLE incident_hold"),
                )
            } shouldBe mapOf("evidence delete" to 1142, "hold drop" to 1142)
        }
    }
})
