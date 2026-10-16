package com.paycore.master.merchant.application.required

/** 서버 생성 식별자(ULID 계열 aggregate ID, 요청 기록 ID, 이벤트 UUID). 시험은 충돌을 주입하려고 대역을 쓴다. */
interface MasterIdGenerator {
    /** `<prefix>_` + ULID 26자. */
    fun newId(prefix: String): String

    /** 요청 기록 ID·이벤트 ID(UUID 문자열 36자). */
    fun newUuid(): String
}
