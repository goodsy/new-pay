package com.paycore.payment.payment.adapter.persistence

import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import java.util.concurrent.CopyOnWriteArrayList

/** 시험 전용 jOOQ listener(ADMIN-PAYMENT-OPS-001 review M-2): [capture] 블록 동안 실행된 SQL 문자열을 모은다(운영 코드에는 없다). */
class SqlCapture : ExecuteListener {
    @Volatile
    private var armed = false
    private val sqls = CopyOnWriteArrayList<String>()

    override fun executeStart(ctx: ExecuteContext) {
        if (armed) ctx.sql()?.let(sqls::add)
    }

    fun capture(block: () -> Unit): List<String> {
        sqls.clear()
        armed = true
        try {
            block()
        } finally {
            armed = false
        }
        return sqls.toList()
    }
}
