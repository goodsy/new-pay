package com.paycore.admin.account.application.provided

/** 1213(교착)·1205(대기 초과)·version 충돌 — 전체 rollback 뒤 재시도로도 끝나지 않음. 웹은 409 `RETRY_LATER`(성공·결과 불명확으로 매핑하지 않는다 — §4.13). */
class AdminRetryLaterException(cause: Throwable) : RuntimeException("retry later (transaction rolled back)", cause)
