package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.AdminDatabaseAccountSpec
import com.paycore.admin.approval.adapter.persistence.JpaApprovalRequestEntity
import com.paycore.admin.approval.adapter.persistence.JpaOperatorCancelCommandEntity
import com.paycore.admin.approval.adapter.persistence.JpaOwnerExecutionEntity
import com.paycore.admin.approval.adapter.persistence.JpaReconciliationCheckpointEntity
import com.paycore.admin.approval.adapter.persistence.JpaReconciliationFindingEntity
import com.paycore.admin.audit.adapter.persistence.JpaAdminAuditEventEntity
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.column
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.persister.entity.EntityPersister
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.mysql.MySQLContainer

/**
 * R-12(S3 진입 조건, S2 리뷰): JPA Entity의 updatable·insertable 열 = DB 열 권한. Hibernate는 updatable 열 **전체**를 SET에 넣으므로(관례 §9.2 주의)
 * 생성 불변 열이 updatable이면 runtime UPDATE가 1143으로 실패한다. 기대값은 [AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES](권한 migration과 같은 값).
 * 또 §4.7.5 security_version 사건 표가 계정 표의 모든 열을 분류하는지(새 열의 분류 누락 감지) 확인한다.
 */
@SpringBootTest
@AdminDbTest
class AdminPersistenceMappingSpec(private val emf: EntityManagerFactory, private val container: MySQLContainer) : FunSpec({

    fun persister(type: Class<*>): EntityPersister = emf.unwrap(SessionFactoryImplementor::class.java).mappingMetamodel.getEntityDescriptor(type)

    fun selectables(type: Class<*>): Triple<Set<String>, Set<String>, Set<String>> {
        val p = persister(type)
        val ids = mutableSetOf<String>()
        p.identifierMapping.forEachSelectable { _, s -> ids += s.selectableName }
        val updatable = mutableSetOf<String>()
        val insertable = mutableSetOf<String>()
        p.forEachSelectable { _, s ->
            if (s.isInsertable) insertable += s.selectableName
            if (s.isUpdateable && s.selectableName !in ids) updatable += s.selectableName
        }
        return Triple(updatable, insertable + ids, ids)
    }

    fun tableColumns(table: String, generated: Boolean? = null): Set<String> = AdminDbAccounts.root(container).use { c ->
        c.column(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?" +
                when (generated) { null -> ""; true -> " AND extra LIKE '%GENERATED%'"; false -> " AND extra NOT LIKE '%GENERATED%'" },
            AdminDbAccounts.ADMIN_DATABASE, table,
        ).toSet()
    }

    test("admin_account: Entity updatable columns are exactly the column-level UPDATE privileges (immutable actor_id/login_name/created_at excluded)") {
        selectables(JpaAdminAccountEntity::class.java).first shouldBe AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES.getValue("admin_account").toSet()
    }

    test("admin_enrollment_code: Entity updatable columns are exactly used_at and revoked_at") {
        selectables(JpaEnrollmentCodeEntity::class.java).first shouldBe AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES.getValue("admin_enrollment_code").toSet()
    }

    test("admin_approval_request: Entity updatable columns are exactly the decision columns (S5 — kind/target/requester/version snapshot immutable)") {
        selectables(JpaApprovalRequestEntity::class.java).first shouldBe AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES.getValue("admin_approval_request").toSet()
    }

    test("admin_owner_execution (ADMIN-PAYMENT-OPS-001 V6): Entity updatable columns are exactly the column-level UPDATE privileges (stored body/fingerprint/deadline immutable)") {
        selectables(JpaOwnerExecutionEntity::class.java).first shouldBe AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES.getValue("admin_owner_execution").toSet()
    }

    test("S7 (I-76): reconciliation checkpoint Entity updatable columns are exactly its column-level UPDATE privileges; findings are an immutable entity") {
        selectables(JpaReconciliationCheckpointEntity::class.java).first shouldBe
            AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES.getValue("admin_ops_reconciliation_checkpoint").toSet()
        persister(JpaReconciliationFindingEntity::class.java).isMutable shouldBe false
        // ACQ-ADMIN-001 B1-A: 재매입 요청 고정 값도 @Immutable(V8 권한 SELECT·INSERT와 두 겹).
        persister(com.paycore.admin.approval.adapter.persistence.JpaAcquiringOpsCommandEntity::class.java).isMutable shouldBe false
    }

    test("every mapped table: Entity insertable columns are exactly the stored (non-generated) table columns") {
        mapOf(
            "admin_account" to JpaAdminAccountEntity::class.java, "admin_enrollment_code" to JpaEnrollmentCodeEntity::class.java,
            "admin_role_event" to JpaAdminRoleEventEntity::class.java, "admin_audit_event" to JpaAdminAuditEventEntity::class.java,
            "admin_approval_request" to JpaApprovalRequestEntity::class.java, "admin_operator_cancel_command" to JpaOperatorCancelCommandEntity::class.java,
            "admin_owner_execution" to JpaOwnerExecutionEntity::class.java, "admin_ops_reconciliation_checkpoint" to JpaReconciliationCheckpointEntity::class.java,
            "admin_ops_reconciliation_finding" to JpaReconciliationFindingEntity::class.java,
            // ACQ-ADMIN-001 B1-A V7: 재매입 요청 고정 값(INSERT-only).
            "admin_acquiring_ops_command" to com.paycore.admin.approval.adapter.persistence.JpaAcquiringOpsCommandEntity::class.java,
        ).forEach { (table, type) ->
            val (_, insertable, ids) = selectables(type)
            val autoIncrement = when (table) {
                "admin_role_event", "admin_audit_event" -> setOf("event_id")
                "admin_ops_reconciliation_finding" -> setOf("finding_id")
                else -> emptySet()
            }
            (insertable - autoIncrement + (ids - autoIncrement)) shouldBe (tableColumns(table, generated = false) - autoIncrement - NOT_YET_MAPPED[table].orEmpty())
        }
    }

    test("I-77 resolved (S5): admin_audit_event.target_ref is mapped by the audit Entity; no stored column is left unmapped") {
        NOT_YET_MAPPED shouldBe emptyMap()
        val (_, insertable, _) = selectables(JpaAdminAuditEventEntity::class.java)
        ("target_ref" in insertable) shouldBe true
    }

    test("append-only tables are mapped as immutable entities (Hibernate never issues UPDATE — runtime has no UPDATE: 1142)") {
        listOf(JpaAdminRoleEventEntity::class.java, JpaAdminAuditEventEntity::class.java, JpaOperatorCancelCommandEntity::class.java).associateWith { persister(it).isMutable } shouldBe
            mapOf(JpaAdminRoleEventEntity::class.java to false, JpaAdminAuditEventEntity::class.java to false, JpaOperatorCancelCommandEntity::class.java to false)
    }

    test("generated columns (pending_key, unresolved_key) are not mapped — the database computes them from status") {
        tableColumns("admin_approval_request", generated = true) shouldBe setOf("pending_key", "unresolved_key")
        val (updatable, insertable, _) = selectables(JpaApprovalRequestEntity::class.java)
        ((updatable + insertable) intersect setOf("pending_key", "unresolved_key")) shouldBe emptySet()
    }

    test("§4.7.5: every admin_account column is classified for security_version (a new unclassified column fails here)") {
        SECURITY_VERSION_CLASSIFICATION.keys shouldBe tableColumns("admin_account")
    }
}) {
    companion object {
        /** 표에 있으나 아직 Entity가 쓰지 않는 열(위 시험이 정확히 고정 — S5에서 비운다). */
        val NOT_YET_MAPPED: Map<String, Set<String>> = emptyMap()

        /**
         * admin_account 열 → security_version 분류(§4.7.5). INCREMENTS: 이 열이 바뀌는 사건은 +1(상태·역할·자격·대기 사유/근거·표시명).
         * EXCLUDED: 인증 활동(실패 계수·마지막 TOTP 단계·세대 단독) — 승인을 무효로 만들지 않는다. IMMUTABLE/TECHNICAL: 바뀌지 않거나 기술 값.
         */
        val SECURITY_VERSION_CLASSIFICATION: Map<String, String> = linkedMapOf(
            "actor_id" to "IMMUTABLE", "login_name" to "IMMUTABLE", "created_at" to "IMMUTABLE",
            "display_name" to "INCREMENTS", "status" to "INCREMENTS", "status_before_suspend" to "INCREMENTS", "pending_purpose" to "INCREMENTS",
            "pending_basis" to "INCREMENTS", "password_hash" to "INCREMENTS", "totp_secret_ciphertext" to "INCREMENTS", "totp_key_version" to "INCREMENTS",
            "locked_at" to "INCREMENTS", "active_roles" to "INCREMENTS", "suspended_roles" to "INCREMENTS",
            "failed_attempts" to "EXCLUDED", "last_totp_step" to "EXCLUDED", "session_generation" to "EXCLUDED",
            "security_version" to "TECHNICAL", "version" to "TECHNICAL", "updated_at" to "TECHNICAL",
            // D-ADMIN1-014: 코드 발급과 함께만 바뀐다(코드 발급 = +1 사건).
            "credential_basis" to "INCREMENTS", "credential_basis_requester" to "INCREMENTS", "credential_basis_approver" to "INCREMENTS", "credential_basis_carried" to "INCREMENTS",
        )
    }
}
