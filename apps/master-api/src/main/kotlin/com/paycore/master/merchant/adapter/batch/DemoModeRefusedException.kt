package com.paycore.master.merchant.adapter.batch

import org.springframework.boot.ExitCodeGenerator

/**
 * 명령 속성이 있는데 배치 모드가 정확히 `DEMO`가 아니다(D-DEMO-001 — 명세 §4.6). 컨텍스트 생성 전(환경 준비 단계)에 던져지므로 빈 기반
 * `ExitCodeExceptionMapper`가 없다 — 예외 자체가 [ExitCodeGenerator](exit 3)이고 진입점이 원인 사슬에서 찾아 매핑한다. 메시지는 고정 code뿐(값 없음).
 */
class DemoModeRefusedException : IllegalStateException(CODE), ExitCodeGenerator {
    override fun getExitCode(): Int = DemoSeedExit.MODE_REFUSED.code

    companion object {
        const val CODE = "DEMO_MODE_REQUIRED"
    }
}
