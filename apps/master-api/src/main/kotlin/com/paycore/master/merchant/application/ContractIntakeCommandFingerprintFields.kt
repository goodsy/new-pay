package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.UpdateContractIntakeItemsCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand

/**
 * 접수 건 명령의 멱등 지문 필드(MERCHANT-FLOW-001 §4B.7 — [BusinessCommandFingerprintFields]와 같은 규칙: 공통 필드 제외 명령 필드 고정 순서, 목록은 개수 접두, null = `~`).
 * 저장 지문은 `h1:` HMAC(SEC-BIZ-001)이라 사유·근거 원문이 무염 해시로 남지 않는다. 희망 서비스·범위는 이름순으로 넣는다(집합 — 요청 순서와 무관하게 같은 내용 = 같은 지문).
 */
internal object ContractIntakeCommandFingerprintFields {

    fun of(c: OpenContractIntakeCommand): List<String?> = buildList {
        add(c.businessId.value); add(c.expectedKind.name); add(c.items.receivedOn.toString()); add(c.items.channel.name); add(c.items.contractManager.value)
        val desired = c.items.desiredServices.map { it.name }.sorted()
        add(desired.size.toString()); addAll(desired)
        add(c.reason?.value)
    }

    /** FLOW-2d 접수 항목 수정: 순번·기대 version·접수 항목(희망 서비스 이름순)·사유. */
    fun of(c: UpdateContractIntakeItemsCommand): List<String?> = buildList {
        add(c.businessId.value); add(c.intakeSeq.toString()); add(c.expectedVersion.toString())
        add(c.items.receivedOn.toString()); add(c.items.channel.name); add(c.items.contractManager.value)
        val desired = c.items.desiredServices.map { it.name }.sorted()
        add(desired.size.toString()); addAll(desired)
        add(c.reason?.value)
    }

    fun of(c: MoveContractIntakeCommand): List<String?> =
        listOf(c.businessId.value, c.intakeSeq.toString(), c.expectedVersion.toString(), c.target.name, c.reason?.value)

    fun of(c: CompleteContractIntakeCommand): List<String?> = buildList {
        add(c.businessId.value); add(c.intakeSeq.toString()); add(c.expectedVersion.toString()); add(c.document.value)
        add(c.terms.startDate.toString()); add(c.terms.endDate?.toString())
        val services = c.terms.services.map { it.name }.sorted()
        add(services.size.toString()); addAll(services)
        add(c.reason?.value)
    }
}
