package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.ApprovedCommand

/** 승인 근거 명령의 요청 지문 입력(실행·기한 확인 공통 — 같은 키·같은 지문이어야 서로 재생된다). */
internal object ApprovedCommands {
    /**
     * 명령 필드(§9.4 순서) + 근거 필드 전부(approvalId·요청자·승인자·승인 시각·기한(epoch ms)·근거 참조·`mop1` 지문). 같은 키·다른 근거 = 다른 지문 = 409(§9.3 — fingerprintFields에 근거 포함).
     */
    fun fingerprintFields(command: ApprovedCommand): List<String?> {
        val e = command.evidence
        return command.commandFields + listOf(
            e.approvalId, e.requestedBy.value, e.approvedBy.value, e.approvedAt.toEpochMilli().toString(), e.executeBefore.toEpochMilli().toString(), e.evidenceRef.value,
            e.fingerprint,
        )
    }
}
