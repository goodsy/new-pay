// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badmessaging.sample.application

import com.example.messaging.MessageProducer

/** 메시지 발행을 Outbox/required Port 없이 application에서 broker client로 직접 수행. */
class DirectPublishService(private val producer: MessageProducer) {
    fun publish(id: String) = producer.send("orders", id)
}
