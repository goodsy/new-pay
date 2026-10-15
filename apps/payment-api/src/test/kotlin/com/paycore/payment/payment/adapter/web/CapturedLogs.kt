package com.paycore.payment.payment.adapter.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * 로그 수집(Logback ListAppender, root — master-api `CapturedLogs`와 같은 방식). 민감값(키·주문 ID·paymentKey)과 예외 메시지 원문이 어떤 로그 줄·예외
 * 사슬에도 나오지 않는지 확인하는 데 쓴다(MASTER-DOMAIN-001 review2 n-3·n-4).
 */
class CapturedLogs : AutoCloseable {
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    init {
        root.addAppender(appender)
    }

    /** 각 이벤트의 로거 이름·형식화된 메시지·예외 사슬(클래스·메시지). */
    fun lines(): List<String> = appender.list.toList().map { e ->
        buildString {
            append(e.loggerName).append(" ").append(e.formattedMessage)
            var t: IThrowableProxy? = e.throwableProxy
            while (t != null) {
                append(" | ").append(t.className).append(": ").append(t.message)
                t = t.cause
            }
        }
    }

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}
