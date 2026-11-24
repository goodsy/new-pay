package com.paycore.admin.security

/**
 * 데모 간편 로그인 켜짐 시험 공통 설정(ADMIN-DEMO-LOGIN-001). 두 spec(DemoQuickLoginHttpSpec·DemoQuickStepUpHttpSpec)이 같은 속성·Import를 써서 Spring 시험 컨텍스트 하나를
 * 공유한다(같은 DB). 옵션 목록 시험은 앞쪽 `dq-` 묶음만 본다 — 다른 spec이 만든 `su-`·`mo-` 계정이 실행 순서에 따라 뒤에 붙을 수 있다. 이름은 시험 전용(운영·Demo 계정 아님) — 각 spec이 자기 이름의 계정만 만든다.
 */
object DemoQuickLoginTestConfig {
    const val ACCOUNTS =
        "paycore.admin.demo-quick-login.accounts=dq-approver,dq-sa,dq-locked,dq-suspended,dq-pending,dq-missing,dq-replaced,dq-limit,dq-relogin," +
            "su-approver,su-both,su-unmask,su-normal,mo-registrant,mo-approver"
}
