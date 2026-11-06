package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.CutoffPolicy

/** 매입 설정 Port(설정 파일 — Demo 값, 운영 기본값 없음). [senderId] = 파일 헤더 송신자 ID, [cutoffPolicy] = 버전 관리 마감 정책(D-ACQ-003). */
interface AcquisitionPolicySource {
    val senderId: String
    val cutoffPolicy: CutoffPolicy
}
