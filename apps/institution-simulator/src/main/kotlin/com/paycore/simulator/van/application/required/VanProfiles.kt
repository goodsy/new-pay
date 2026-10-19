package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanProfile

/** 기관 가맹점 계정(institutionAccount)별 Capability 프로파일(Demo 설정). 등록되지 않은 계정이면 null — 그 요청은 기록 없이 연결을 닫는다. */
fun interface VanProfiles {
    fun profileOf(institutionAccount: String): VanProfile?
}
