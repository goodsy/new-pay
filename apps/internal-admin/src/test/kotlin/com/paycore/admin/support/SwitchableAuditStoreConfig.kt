package com.paycore.admin.support

import com.paycore.admin.audit.adapter.persistence.JpaAdminAuditStore
import com.paycore.admin.audit.application.required.AdminAuditStore
import com.paycore.admin.audit.domain.AdminAuditEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.atomic.AtomicReference

/**
 * 감사 저장 실패 주입(시험 전용). 기본은 실제 JPA 저장소로 위임하고, [failOn]에 맞는 행위의 append에서 예외를 던진다(DB 장애·제약 위반과 같은 위치 —
 * 호출자 TX 안). `AuditFailureClosesActionSpec`·`AuditQueryPermissionSpec`이 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
class SwitchableAuditStoreConfig {
    @Bean
    @Primary
    fun switchableAuditStore(delegate: JpaAdminAuditStore): SwitchableAuditStore = SwitchableAuditStore(delegate)
}

class SwitchableAuditStore(private val delegate: JpaAdminAuditStore) : AdminAuditStore {
    private val failing = AtomicReference<((AdminAuditEvent) -> Boolean)?>(null)

    fun failOn(predicate: ((AdminAuditEvent) -> Boolean)?) = failing.set(predicate)

    override fun append(event: AdminAuditEvent) {
        if (failing.get()?.invoke(event) == true) throw InjectedAuditFailure()
        delegate.append(event)
    }
}

class InjectedAuditFailure : RuntimeException("injected audit store failure")
